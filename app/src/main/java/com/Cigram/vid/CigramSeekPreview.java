package com.Cigram.vid;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaDataSource;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v10.79: YouTube-style seek preview. While the finger drags the time bar a card follows it with the picture
 * of that moment and its time. For playlists (HLS) the picture comes from the piece under the finger, fetched
 * through {@link CigramSegmentAccel}: that piece is then already downloading when the finger is lifted, so
 * the video shows right after the seek. Plain files (MP4) use the platform's frame reader.
 *
 * Everything heavy runs on one background thread and only the latest position counts, so dragging stays smooth.
 */
final class CigramSeekPreview {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final AtomicInteger GENERATION = new AtomicInteger();
    /** Pictures already made, by piece address (HLS) or by 5-second step (files). */
    private static final LruCache<String, Bitmap> THUMBS = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private static FrameLayout card;
    private static ImageView picture;
    private static TextView time;
    private static SeekBar bar;
    private static Activity activity;
    private static long[] starts;          // piece start times (ms) of the playing playlist
    private static String[] urls;          // piece addresses
    private static String fileUrl;         // plain file being played (no playlist)
    private static String shownKey;
    private static Runnable pending;
    private static MediaMetadataRetriever fileReader;
    private static String fileReaderFor;

    private CigramSeekPreview() { }

    // ================================================================== called by the player screen

    static void start(Activity a, SeekBar seekBar, Object player) {
        try {
            if (a == null || seekBar == null) return;
            activity = a;
            bar = seekBar;
            starts = null;
            urls = null;
            fileUrl = null;
            shownKey = null;
            readSource(player);
            ensureCard(a);
            if (picture != null) {
                picture.setImageDrawable(null);
                picture.setVisibility(urls != null || fileUrl != null ? View.VISIBLE : View.GONE);
            }
            card.animate().cancel();
            card.setAlpha(0f);
            card.setVisibility(View.VISIBLE);
            card.animate().alpha(1f).setDuration(120L).start();
        } catch (Throwable t) {
            android.util.Log.w("CigramPreview", "start", t);
        }
    }

    static void move(final long targetMs, long durationMs) {
        try {
            if (card == null || bar == null || activity == null) return;
            time.setText(format(targetMs));
            place(targetMs, durationMs);
            if (pending != null) MAIN.removeCallbacks(pending);
            final int gen = GENERATION.incrementAndGet();
            pending = new Runnable() { @Override public void run() { request(targetMs, gen); } };
            MAIN.postDelayed(pending, 240L);   // only when the finger rests: no downloads for places it merely passes
        } catch (Throwable t) {
            android.util.Log.w("CigramPreview", "move", t);
        }
    }

    static void stop() {
        try {
            GENERATION.incrementAndGet();
            if (pending != null) MAIN.removeCallbacks(pending);
            pending = null;
            if (card != null) {
                final View c = card;
                c.animate().cancel();
                c.animate().alpha(0f).setDuration(140L).withEndAction(new Runnable() {
                    @Override public void run() { c.setVisibility(View.GONE); }
                }).start();
            }
            WORK.execute(new Runnable() { @Override public void run() { releaseFileReader(); } });
        } catch (Throwable ignored) { }
    }

    /** The player page is going away: drop every static reference to it and take the card out of its window. */
    static void release(Activity a) {
        if (a == null || activity != a) return;
        GENERATION.incrementAndGet();
        if (pending != null) MAIN.removeCallbacks(pending);
        pending = null;
        FrameLayout c = card;
        card = null;
        picture = null;
        time = null;
        bar = null;
        activity = null;
        if (c != null) {
            c.animate().cancel();
            if (c.getParent() instanceof ViewGroup) ((ViewGroup) c.getParent()).removeView(c);
        }
    }

    // ================================================================== source of the pictures

    private static void readSource(Object player) {
        if (!(player instanceof com.google.android.exoplayer2.ExoPlayer)) return;
        com.google.android.exoplayer2.ExoPlayer exo = (com.google.android.exoplayer2.ExoPlayer) player;
        Object manifest = exo.getCurrentManifest();
        if (manifest instanceof com.google.android.exoplayer2.source.hls.HlsManifest) {
            com.google.android.exoplayer2.source.hls.playlist.HlsMediaPlaylist mp =
                    ((com.google.android.exoplayer2.source.hls.HlsManifest) manifest).mediaPlaylist;
            int n = mp.segments.size();
            if (n == 0) return;
            long[] s = new long[n];
            String[] u = new String[n];
            for (int i = 0; i < n; i++) {
                s[i] = mp.segments.get(i).relativeStartTimeUs / 1000L;
                u[i] = com.google.android.exoplayer2.util.UriUtil.resolve(mp.baseUri, mp.segments.get(i).url);
            }
            starts = s;
            urls = u;
            return;
        }
        if (manifest != null) return;   // DASH and others: time only
        com.google.android.exoplayer2.MediaItem item = exo.getCurrentMediaItem();
        if (item == null || item.localConfiguration == null) return;
        String u = item.localConfiguration.uri.toString();
        if (u.startsWith("http://") || u.startsWith("https://")) fileUrl = u;
    }

    private static int pieceAt(long ms) {
        long[] s = starts;
        if (s == null) return -1;
        int lo = 0, hi = s.length - 1, at = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (s[mid] <= ms) { at = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return at;
    }

    private static void request(final long targetMs, final int gen) {
        final String[] u = urls;
        final String file = fileUrl;
        if (u != null) {
            int i = pieceAt(targetMs);
            if (i < 0 || i >= u.length) return;
            final String url = u[i];
            if (url.equals(shownKey)) return;
            Bitmap hit = THUMBS.get(url);
            if (hit != null) { show(url, hit); return; }
            WORK.execute(new Runnable() { @Override public void run() { pictureOfPiece(url, gen); } });
        } else if (file != null) {
            final long step = targetMs / 5000L * 5000L;
            final String key = file + "#" + step;
            if (key.equals(shownKey)) return;
            Bitmap hit = THUMBS.get(key);
            if (hit != null) { show(key, hit); return; }
            WORK.execute(new Runnable() { @Override public void run() { pictureOfFile(file, step, key, gen); } });
        }
    }

    /** The piece under the finger: start (or join) its download, then draw its first picture. */
    private static void pictureOfPiece(String url, int gen) {
        if (gen != GENERATION.get()) return;
        CigramSegmentAccel.Entry e = CigramSegmentAccel.prefetch(url);
        long deadline = System.currentTimeMillis() + 8000L;
        int want = 220 * 1024;
        byte[] head = null;
        while (System.currentTimeMillis() < deadline) {
            if (gen != GENERATION.get()) return;   // the finger moved on: the newer request decides
            boolean done;
            synchronized (e) { done = e.complete || e.failed || e.cancelled; }
            head = e.head(2 * 1024 * 1024);
            if (head.length >= want || done) break;
            try { Thread.sleep(120L); } catch (InterruptedException ie) { return; }
        }
        if (head == null || head.length < 2048 || gen != GENERATION.get()) return;
        Bitmap bmp = frameFromBytes(head);
        if (bmp == null && head.length < 2 * 1024 * 1024) {
            // the first key frame was larger than what had arrived: wait a little more, once
            try { Thread.sleep(500L); } catch (InterruptedException ie) { return; }
            if (gen != GENERATION.get()) return;
            bmp = frameFromBytes(e.head(2 * 1024 * 1024));
        }
        if (bmp != null) {
            THUMBS.put(url, bmp);
            final Bitmap b = bmp;
            final String key = url;
            MAIN.post(new Runnable() { @Override public void run() { if (gen == GENERATION.get()) show(key, b); } });
        }
    }

    private static void pictureOfFile(String file, long ms, final String key, final int gen) {
        if (gen != GENERATION.get()) return;
        try {
            if (fileReader == null || !file.equals(fileReaderFor)) {
                releaseFileReader();
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                HashMap<String, String> h = new HashMap<String, String>(CigramStreams.CURRENT_HEADERS);
                r.setDataSource(CigramStreams.cleanUrl(file), h);
                fileReader = r;
                fileReaderFor = file;
            }
            if (gen != GENERATION.get()) return;
            Bitmap raw = fileReader.getFrameAtTime(ms * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            final Bitmap b = scale(raw);
            if (b == null) return;
            THUMBS.put(key, b);
            MAIN.post(new Runnable() { @Override public void run() { if (gen == GENERATION.get()) show(key, b); } });
        } catch (Throwable t) {
            releaseFileReader();
        }
    }

    private static void releaseFileReader() {
        MediaMetadataRetriever r = fileReader;
        fileReader = null;
        fileReaderFor = null;
        if (r != null) {
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    // ================================================================== decoding a piece

    /** Pieces are often MPEG-TS hidden behind a few bytes of another format: start at the first TS packet. */
    private static int tsStart(byte[] b) {
        int limit = Math.min(b.length - 3 * 188, 64 * 1024);
        for (int k = 0; k < limit; k++) {
            if (b[k] == 0x47 && b[k + 188] == 0x47 && b[k + 376] == 0x47) return k;
        }
        return -1;
    }

    static Bitmap frameFromBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 2048) return null;
        int off = tsStart(bytes);
        int start = Math.max(0, off);
        int size = bytes.length - start;
        if (off >= 0) size -= size % 188;
        if (size <= 0) return null;
        final byte[] data = bytes;
        final int base = start;
        final int length = size;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(new MediaDataSource() {
                @Override public int readAt(long position, byte[] buffer, int offset, int count) {
                    if (position >= length) return -1;
                    int n = (int) Math.min(count, length - position);
                    System.arraycopy(data, (int) (base + position), buffer, offset, n);
                    return n;
                }
                @Override public long getSize() { return length; }
                @Override public void close() { }
            });
            Bitmap raw = r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (raw == null) raw = r.getFrameAtTime(-1L);
            return scale(raw);
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    private static Bitmap scale(Bitmap raw) {
        if (raw == null) return null;
        int w = raw.getWidth(), h = raw.getHeight();
        if (w <= 0 || h <= 0) return null;
        int tw = 320;
        if (w <= tw) return raw;
        Bitmap s = Bitmap.createScaledBitmap(raw, tw, Math.max(1, h * tw / w), true);
        if (s != raw) raw.recycle();
        return s;
    }

    // ================================================================== the card

    private static int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private static void ensureCard(Activity a) {
        if (card != null && card.getContext() == a && card.getParent() != null) return;
        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        FrameLayout c = new FrameLayout(a);
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable imgBg = new GradientDrawable();
        imgBg.setColor(0xFF111111);
        imgBg.setCornerRadius(dp(8));
        imgBg.setStroke(dp(2), Color.WHITE);
        img.setBackground(imgBg);
        img.setClipToOutline(true);
        int pad = dp(2);
        img.setPadding(pad, pad, pad, pad);
        column.addView(img, new LinearLayout.LayoutParams(dp(176), dp(99)));
        TextView t = new TextView(a);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setTypeface(CigramTypefaces.bold());
        t.setShadowLayer(4f, 0f, 1f, 0xCC000000);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = dp(4);
        column.addView(t, tl);
        c.addView(column, new FrameLayout.LayoutParams(-2, -2));
        c.setVisibility(View.GONE);
        c.setElevation(dp(12));
        decor.addView(c, new FrameLayout.LayoutParams(-2, -2));
        card = c;
        picture = img;
        time = t;
    }

    /** Above the finger on the bar, kept inside the screen. */
    private static void place(long targetMs, long durationMs) {
        if (durationMs <= 0L) return;
        int[] at = new int[2];
        bar.getLocationInWindow(at);
        float fraction = Math.max(0f, Math.min(1f, targetMs / (float) durationMs));
        if (bar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) fraction = 1f - fraction;
        float track = bar.getWidth() - bar.getPaddingLeft() - bar.getPaddingRight();
        float x = at[0] + bar.getPaddingLeft() + track * fraction;
        int w = card.getWidth() > 0 ? card.getWidth() : dp(176);
        int h = card.getHeight() > 0 ? card.getHeight() : dp(130);
        int screen = ((View) card.getParent()).getWidth();
        float left = Math.max(dp(8), Math.min(screen - w - dp(8), x - w / 2f));
        card.setX(left);
        card.setY(Math.max(dp(8), at[1] - h - dp(14)));
    }

    private static void show(String key, Bitmap b) {
        if (picture == null || b == null || b.isRecycled()) return;
        shownKey = key;
        picture.setVisibility(View.VISIBLE);
        picture.setImageBitmap(b);
    }

    static String format(long ms) {
        long s = Math.max(0L, ms / 1000L);
        long h = s / 3600L, m = (s % 3600L) / 60L, sec = s % 60L;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%02d:%02d", m, sec);
    }
}
