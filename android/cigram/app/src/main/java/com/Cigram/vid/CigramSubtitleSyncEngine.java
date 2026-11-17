package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;

import com.Cigram.subhub.SubCodec;
import com.Cigram.subhub.SubCue;
import com.Cigram.subhub.SubDocument;
import com.Cigram.subhub.SubSync;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.ui.CaptionStyleCompat;
import com.google.android.exoplayer2.ui.SubtitleView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * CigramSubtitleSyncEngine — the player's subtitle timing engine.
 *
 * <p>The subtitle file is no longer handed to ExoPlayer's SRT/VTT decoders (ExoPlayer 2.19.1 reads ",5" as 5 ms,
 * drops SRT cues written with '.', ignores Windows-1256 — see {@link SubCodec}). It is downloaded once, decoded and
 * parsed by CigramSubtitleHub, and drawn by a second {@link SubtitleView} placed exactly over ExoPlayer's own one:</p>
 * <ul>
 *   <li>what is on screen = the cues active at {@code player.getCurrentPosition() − delay}, checked on every frame
 *   while playing — the same clock ExoPlayer's own text renderer uses;</li>
 *   <li>the viewer's delay is applied ONCE, here, at display time; the file itself is never shifted;</li>
 *   <li>changing the delay is instant (no re-buffering), reset-to-zero is one tap;</li>
 *   <li>ExoPlayer's view stays the "visibility master": every existing on/off / PiP / insets path keeps working, our
 *   view copies its visibility and padding and ExoPlayer's view is made transparent while the engine owns the text;</li>
 *   <li>switching quality reloads the same subtitle URL from memory (no new download, same delay).</li>
 * </ul>
 * Nothing runs on the main thread except the per-frame lookup (a binary search).
 */
public final class CigramSubtitleSyncEngine {

    private static final String TAG = "CigramSubSync";
    private static final String PREFS = "subtitle_settings";
    /** Hidden kill-switch: true = the pre-18.2 path (ExoPlayer decoders). */
    static final String K_LEGACY = "subtitle_engine_legacy";
    private static final long DISK_TTL_MS = 12L * 3600L * 1000L;

    public interface Listener {
        void onLoaded(SubDocument doc);

        void onFailed(String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static OkHttpClient http;

    /** Small in-memory cache: quality switches and next/previous episodes do not download again. */
    private static final Map<String, SubDocument> MEMORY = Collections.synchronizedMap(new LinkedHashMap<String, SubDocument>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, SubDocument> e) {
            return size() > 4;
        }
    });

    private final Activity activity;
    private final Player player;
    private final SubtitleView master;
    private final SubtitleView view;
    private final SharedPreferences prefs;

    private String url = "";
    private SubDocument doc;
    private SubSync.Timeline timeline;
    private long delayMs;
    private int generation;
    private List<SubCue> shown = Collections.emptyList();
    private boolean released;
    private boolean frameQueued;
    private Listener listener;
    private String lastError = "";

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            frameQueued = false;
            tick();
        }
    };
    private final Runnable slowTick = new Runnable() {
        @Override public void run() {
            tick();
        }
    };

    private CigramSubtitleSyncEngine(Activity a, Player p, SubtitleView master) {
        this.activity = a;
        this.player = p;
        this.master = master;
        this.prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.view = new SubtitleView(a);
        view.setClickable(false);
        view.setFocusable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setApplyEmbeddedStyles(false);
        view.setApplyEmbeddedFontSizes(false);
        try {
            ViewGroup parent = (ViewGroup) master.getParent();
            int at = parent.indexOfChild(master) + 1;
            parent.addView(view, at, new ViewGroup.LayoutParams(master.getLayoutParams() == null ? new ViewGroup.LayoutParams(-1, -1) : master.getLayoutParams()));
        } catch (Throwable e) {
            Log.w(TAG, "overlay not attached", e);
        }
        view.setVisibility(master.getVisibility());
    }

    /** Creates the engine over ExoPlayer's subtitle view, or returns null when the legacy path is forced. */
    static CigramSubtitleSyncEngine attach(Activity a, Player p, SubtitleView master) {
        if (a == null || p == null || master == null) return null;
        if (a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_LEGACY, false)) return null;
        if (!(master.getParent() instanceof ViewGroup)) return null;
        return new CigramSubtitleSyncEngine(a, p, master);
    }

    void setListener(Listener l) {
        listener = l;
    }

    // ------------------------------------------------------------------ style (same look as ExoPlayer's view)

    void applyStyle(CaptionStyleCompat style, float fractionalTextSize, float bottomPaddingFraction) {
        try {
            view.setStyle(style);
            view.setFractionalTextSize(fractionalTextSize);
            view.setBottomPaddingFraction(bottomPaddingFraction);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ loading

    String url() {
        return url;
    }

    SubDocument document() {
        return doc;
    }

    boolean isActive() {
        return url.length() > 0;
    }

    String lastError() {
        return lastError;
    }

    /** Loads (or keeps) the subtitle of the current title. An empty url clears the engine. */
    void load(final String subtitleUrl) {
        final String u = subtitleUrl == null ? "" : subtitleUrl.trim();
        if (u.length() == 0) {
            clear();
            return;
        }
        if (u.equals(url) && doc != null) {
            master.setAlpha(0f);
            schedule();
            return;
        }
        final int gen = ++generation;
        url = u;
        doc = null;
        timeline = null;
        lastError = "";
        delayMs = SubSync.clampDelay(prefs.getLong(delayKey(u), 0L));
        master.setAlpha(0f);     // the engine owns the text from now on
        showCues(Collections.<SubCue>emptyList());
        SubDocument cached = MEMORY.get(u);
        if (cached != null) {
            ready(gen, cached);
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                SubDocument d = null;
                String err = null;
                try {
                    byte[] raw = fetch(activity.getApplicationContext(), u);
                    d = SubCodec.read(raw);
                    if (d.isEmpty()) err = "ملف الترجمة لا يحتوي أي مقطع صالح.";
                } catch (Throwable e) {
                    err = e instanceof IllegalArgumentException && e.getMessage() != null ? e.getMessage() : "تعذر تحميل ملف الترجمة.";
                    Log.w(TAG, "subtitle load failed: " + e.getClass().getSimpleName());
                }
                final SubDocument fd = d;
                final String fe = err;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (released || gen != generation) return;
                        if (fe != null) {
                            lastError = fe;
                            if (listener != null) listener.onFailed(fe);
                            return;
                        }
                        MEMORY.put(u, fd);
                        ready(gen, fd);
                    }
                });
            }
        }, "cigram-subtitle");
        t.setDaemon(true);
        t.start();
    }

    private void ready(int gen, SubDocument d) {
        if (gen != generation) return;
        doc = d;
        timeline = new SubSync.Timeline(d);
        shown = Collections.emptyList();
        if (listener != null) listener.onLoaded(d);
        schedule();
    }

    void clear() {
        generation++;
        url = "";
        doc = null;
        timeline = null;
        lastError = "";
        showCues(Collections.<SubCue>emptyList());
        master.setAlpha(1f);
    }

    void release() {
        released = true;
        clear();
        MAIN.removeCallbacks(slowTick);
        try {
            if (frameQueued) Choreographer.getInstance().removeFrameCallback(frame);
        } catch (Throwable ignored) { }
        frameQueued = false;
    }

    // ------------------------------------------------------------------ delay

    long delayMs() {
        return delayMs;
    }

    /** Sets the viewer's delay for THIS subtitle file (positive = later). Saved per file; applied once, at display. */
    void setDelayMs(long ms) {
        delayMs = SubSync.clampDelay(ms);
        if (url.length() > 0) {
            SharedPreferences.Editor e = prefs.edit();
            if (delayMs == 0) e.remove(delayKey(url)); else e.putLong(delayKey(url), delayMs);
            e.apply();
        }
        shown = null;   // force a redraw now, even when paused
        tick();
    }

    /** The cue nearest to the current position, for the delay sheet preview. */
    SubCue previewCue() {
        if (timeline == null) return null;
        try {
            return timeline.nearest(player.getCurrentPosition(), delayMs);
        } catch (Throwable e) {
            return null;
        }
    }

    long positionMs() {
        try {
            return player.getCurrentPosition();
        } catch (Throwable e) {
            return 0;
        }
    }

    static String delayKey(String url) {
        return "delay:" + sha1(CigramStreams.cleanUrl(url));
    }

    // ------------------------------------------------------------------ frame loop

    private void schedule() {
        if (released) return;
        boolean playing = false;
        try {
            playing = player.isPlaying();
        } catch (Throwable ignored) { }
        if (playing && view.isShown()) {
            if (!frameQueued) {
                frameQueued = true;
                Choreographer.getInstance().postFrameCallback(frame);
            }
        } else {
            MAIN.removeCallbacks(slowTick);
            MAIN.postDelayed(slowTick, view.isShown() ? 120L : 500L);
        }
    }

    private void tick() {
        if (released || activity.isFinishing()) return;
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        // follow ExoPlayer's view: visibility (on/off, PiP ...) and padding (insets / cutout)
        int vis = master.getVisibility();
        if (view.getVisibility() != vis) view.setVisibility(vis);
        if (view.getPaddingLeft() != master.getPaddingLeft() || view.getPaddingRight() != master.getPaddingRight()
                || view.getPaddingTop() != master.getPaddingTop() || view.getPaddingBottom() != master.getPaddingBottom()) {
            view.setPadding(master.getPaddingLeft(), master.getPaddingTop(), master.getPaddingRight(), master.getPaddingBottom());
        }
        if (url.length() > 0 && master.getAlpha() != 0f) master.setAlpha(0f);
        SubSync.Timeline tl = timeline;
        if (tl == null) {
            if (url.length() > 0) schedule();
            return;
        }
        List<SubCue> now = vis == View.VISIBLE ? tl.activeAt(positionMs(), delayMs) : Collections.<SubCue>emptyList();
        if (shown == null || !now.equals(shown)) showCues(now);
        schedule();
    }

    private void showCues(List<SubCue> cues) {
        shown = cues;
        try {
            view.setCues(toExoCues(cues));
        } catch (Throwable e) {
            Log.w(TAG, "setCues failed", e);
        }
    }

    // ------------------------------------------------------------------ cue rendering

    private static final Pattern AN = Pattern.compile("\\{\\\\an([1-9])\\}");
    private static final Pattern LINE = Pattern.compile("line:(-?\\d+(?:\\.\\d+)?)(%?)");

    /** Cues without a position are joined into one bottom cue (in start order) so they never draw over each other. */
    static List<Cue> toExoCues(List<SubCue> cues) {
        if (cues == null || cues.isEmpty()) return Collections.emptyList();
        List<Cue> out = new ArrayList<Cue>(cues.size());
        SpannableStringBuilder bottom = null;
        for (SubCue c : cues) {
            float line = position(c);
            CharSequence text = render(c.text);
            if (text.length() == 0) continue;
            if (Float.isNaN(line)) {
                if (bottom == null) bottom = new SpannableStringBuilder();
                else bottom.append('\n');
                bottom.append(text);
            } else {
                out.add(new Cue.Builder().setText(text).setTextAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setLine(line, Cue.LINE_TYPE_FRACTION).setLineAnchor(line < 0.5f ? Cue.ANCHOR_TYPE_START : Cue.ANCHOR_TYPE_MIDDLE).build());
            }
        }
        if (bottom != null) out.add(new Cue.Builder().setText(bottom).setTextAlignment(Layout.Alignment.ALIGN_CENTER).build());
        return out;
    }

    /** Fraction from the top, or NaN for "default (bottom)". */
    private static float position(SubCue c) {
        Matcher a = AN.matcher(c.text);
        if (a.find()) {
            int n = Integer.parseInt(a.group(1));
            if (n >= 7) return 0.05f;
            if (n >= 4) return 0.5f;
            return Float.NaN;
        }
        if (c.settings.length() > 0) {
            Matcher l = LINE.matcher(c.settings.toLowerCase(Locale.US));
            if (l.find() && "%".equals(l.group(2))) {
                float v = Float.parseFloat(l.group(1)) / 100f;
                if (v >= 0f && v < 0.8f) return v;
            }
        }
        return Float.NaN;
    }

    /** {@code <i> <b> <u>} → spans; every other tag removed; entities decoded. Arabic is laid out RTL by the view. */
    static CharSequence render(String raw) {
        String s = raw == null ? "" : raw;
        s = s.replaceAll("\\{\\\\[^}]*\\}", "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ");
        s = s.replaceAll("<\\d{1,3}:\\d{2}(?::\\d{2})?[.,]\\d{1,3}>", "");
        SpannableStringBuilder b = new SpannableStringBuilder();
        int[] open = new int[3];
        boolean[] on = new boolean[3];
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '<') {
                int close = s.indexOf('>', i);
                if (close > i && close - i <= 80) {
                    String tag = s.substring(i + 1, close).trim().toLowerCase(Locale.US);
                    boolean end = tag.startsWith("/");
                    String name = (end ? tag.substring(1) : tag).split("[\\s.]")[0];
                    int k = "i".equals(name) ? 0 : "b".equals(name) ? 1 : "u".equals(name) ? 2 : -1;
                    if (k >= 0) {
                        if (!end && !on[k]) { on[k] = true; open[k] = b.length(); }
                        else if (end && on[k]) { span(b, k, open[k]); on[k] = false; }
                    }
                    i = close + 1;
                    continue;
                }
            }
            if (ch == '&') {
                int semi = s.indexOf(';', i);
                if (semi > i && semi - i <= 6) {
                    String ent = s.substring(i, semi + 1);
                    String rep = "&amp;".equals(ent) ? "&" : "&lt;".equals(ent) ? "<" : "&gt;".equals(ent) ? ">" : "&nbsp;".equals(ent) ? " " : "&lrm;".equals(ent) ? "‎" : "&rlm;".equals(ent) ? "‏" : null;
                    if (rep != null) { b.append(rep); i = semi + 1; continue; }
                }
            }
            b.append(ch);
            i++;
        }
        for (int k = 0; k < 3; k++) if (on[k]) span(b, k, open[k]);
        // trim blank lines at both ends
        while (b.length() > 0 && (b.charAt(b.length() - 1) == '\n' || b.charAt(b.length() - 1) == ' ')) b.delete(b.length() - 1, b.length());
        while (b.length() > 0 && b.charAt(0) == '\n') b.delete(0, 1);
        return b;
    }

    private static void span(SpannableStringBuilder b, int kind, int start) {
        if (start >= b.length()) return;
        Object span = kind == 0 ? new StyleSpan(Typeface.ITALIC) : kind == 1 ? new StyleSpan(Typeface.BOLD) : new UnderlineSpan();
        b.setSpan(span, start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ------------------------------------------------------------------ normalized file (players without the overlay)

    interface FileCallback {
        /** file:// URI of a clean UTF-8 SRT (three millisecond digits), or null when the subtitle could not be read. */
        void done(String fileUri);
    }

    /**
     * For players that keep ExoPlayer's own text renderer (watch-together): the same download + CigramSubtitleHub
     * parsing, written back as a normalized SRT that ExoPlayer reads at the right times. Timing is not changed.
     */
    static void normalizeToFile(final Context c, final String url, final FileCallback cb) {
        final Context app = c.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String out = null;
                try {
                    SubDocument d = MEMORY.get(url);
                    if (d == null) {
                        d = SubCodec.read(fetch(app, url));
                        if (!d.isEmpty()) MEMORY.put(url, d);
                    }
                    if (!d.isEmpty()) {
                        File dir = new File(app.getCacheDir(), "cg_subs");
                        if (!dir.isDirectory()) dir.mkdirs();
                        File f = new File(dir, sha1(CigramStreams.cleanUrl(url)) + ".norm.srt");
                        FileOutputStream o = new FileOutputStream(f);
                        try { o.write(SubCodec.writeBytes(d, SubDocument.Format.SRT)); } finally { o.close(); }
                        out = android.net.Uri.fromFile(f).toString();
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "normalize failed: " + e.getClass().getSimpleName());
                }
                final String r = out;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        cb.done(r);
                    }
                });
            }
        }, "cigram-subtitle-norm");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ network + disk cache

    private static synchronized OkHttpClient client() {
        if (http == null) {
            http = new OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
                    .followRedirects(true).followSslRedirects(true).build();
        }
        return http;
    }

    static byte[] fetch(Context c, String rawUrl) throws Exception {
        String clean = CigramStreams.cleanUrl(rawUrl);
        File dir = new File(c.getCacheDir(), "cg_subs");
        File f = new File(dir, sha1(clean) + ".bin");
        if (f.isFile() && System.currentTimeMillis() - f.lastModified() < DISK_TTL_MS && f.length() > 0 && f.length() <= SubCodec.MAX_BYTES) {
            return readAll(new FileInputStream(f), SubCodec.MAX_BYTES);
        }
        byte[] data;
        String lower = clean.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            Request.Builder rb = new Request.Builder().url(clean).header("User-Agent", "Cigram/" + BuildConfig.VERSION_NAME + " (Android)");
            Map<String, String> h = CigramStreams.headersOf(rawUrl);
            if (h != null) for (Map.Entry<String, String> e : h.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) rb.header(e.getKey(), e.getValue());
            }
            Response r = client().newCall(rb.build()).execute();
            try {
                if (!r.isSuccessful() || r.body() == null) throw new IllegalStateException("HTTP " + r.code());
                long len = r.body().contentLength();
                if (len > SubCodec.MAX_BYTES) throw new IllegalArgumentException("ملف الترجمة كبير جداً.");
                data = readAll(r.body().byteStream(), SubCodec.MAX_BYTES);
            } finally {
                r.close();
            }
        } else if (lower.startsWith("content://") || lower.startsWith("file://") || lower.startsWith("android.resource://")) {
            InputStream in = c.getContentResolver().openInputStream(android.net.Uri.parse(clean));
            if (in == null) throw new IllegalStateException("cannot open");
            data = readAll(in, SubCodec.MAX_BYTES);
            return data;
        } else {
            throw new IllegalArgumentException("رابط الترجمة غير مدعوم.");
        }
        try {
            if (!dir.isDirectory()) dir.mkdirs();
            FileOutputStream out = new FileOutputStream(f);
            try { out.write(data); } finally { out.close(); }
            pruneCache(dir);
        } catch (Throwable ignored) { }
        return data;
    }

    private static void pruneCache(File dir) {
        File[] all = dir.listFiles();
        if (all == null || all.length <= 40) return;
        java.util.Arrays.sort(all, new java.util.Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (int i = 0; i < all.length - 40; i++) all[i].delete();
    }

    private static byte[] readAll(InputStream in, int max) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new IllegalArgumentException("ملف الترجمة كبير جداً.");
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder(40);
            for (byte x : d) b.append(String.format(Locale.US, "%02x", x));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
