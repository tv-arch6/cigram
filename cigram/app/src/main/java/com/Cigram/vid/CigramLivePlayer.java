package com.Cigram.vid;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.images.WebImage;

import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

/**
 * v10.35 — the channel player (LiveActivity): its own look, every button without a background.
 *
 *  ┌──────────────────────────────────────────────────────────────────────┐
 *  │ ⚙   ⧉   ⟳   ⤢   📺                       HD  اسم القناة  (شعار)   →  │
 *  │                                            الرياضة · 3 من 24          │
 *  │                                                                        │
 *  │   ◀▮  القناة التالية             ❚❚             القناة السابقة  ▮▶    │
 *  │       MBC 2                                          beIN 2           │
 *  │                                                                        │
 *  │ ●━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━  │
 *  │ مباشر ●                                       🔒 قفل      📺 القنوات   │
 *  └──────────────────────────────────────────────────────────────────────┘
 *
 *  - Icons and labels float on the picture (soft shadow + light scrim), no circles or pills
 *  - Next / previous show the neighbour channel's name: a zapper, not a seek bar
 *  - The live line is not seekable: it shows how far the picture is from the live edge
 *    (RTL: the live edge is the pulsing dot on the left)
 *
 *  - Cast to TV: Chromecast / Google TV, DLNA TVs (Samsung, LG, Sony ...) and screen mirroring
 *  - Screen size: fit / fill / stretch / 16:9 / 4:3, pinch to zoom, double tap fit ↔ fill
 *  - Orientation: landscape / portrait / auto (button + settings)
 *  - Quality (HLS levels), sleep timer, lock, picture-in-picture
 *  - Swipe up/down: brightness (left half), volume (right half)
 *  - Channel switching (previous / next / channel list) without leaving the player
 *  - Auto reconnect when the stream drops
 *
 * LiveActivity's Sketchware block only calls {@link #attach(Activity)}.
 */
public final class CigramLivePlayer {

    private static final String TAG = "CigramLivePlayer";
    private static final String PREFS = "cigram_live_prefs";

    private static final int RED = 0xFFE21B14;
    private static final int PANEL = 0xF2081A23;
    private static final int MUTED = 0xFFA9B6BD;
    private static final int SOFT = 0xFFD9E1E5;

    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static final String[] ASPECT_LABELS = {"ملاءمة", "ملء", "تمديد", "16:9", "4:3"};
    private static final String[] ORIENT_LABELS = {"أفقي", "عمودي", "تلقائي"};
    private static final String[] SLEEP_LABELS = {"إيقاف", "15 د", "30 د", "60 د", "90 د"};
    private static final int[] SLEEP_MIN = {0, 15, 30, 60, 90};

    private CigramLivePlayer() { }

    // ================================================================== queue (from the channels page)

    /** One channel of the player's list. */
    public static final class Entry {
        public String key = "";
        public String name = "";
        public String url = "";
        public String image = "";
        public String section = "";
        WeakReference<View> origin;   // built-in card of ChannelsActivity (its own click has the link)
    }

    private static final ArrayList<Entry> QUEUE = new ArrayList<Entry>();
    private static int pendingIndex = -1;
    /** The screen is being recreated (rotation without configChanges): keep the current channel. */
    private static boolean keepIndex;
    /** Links of the built-in channels, learned the first time they are opened. */
    private static final HashMap<String, String> LEARNED = new HashMap<String, String>();

    public static Entry entry(String key, String name, String url, String image, String section, View origin) {
        Entry e = new Entry();
        e.key = key == null ? "" : key;
        e.name = name == null ? "" : name;
        e.url = url == null ? "" : url.trim();
        e.image = image == null ? "" : image;
        e.section = section == null ? "" : section;
        e.origin = origin == null ? null : new WeakReference<View>(origin);
        return e;
    }

    /** Called by the channels page right before it opens LiveActivity. */
    public static void setQueue(List<Entry> list, int index) {
        synchronized (QUEUE) {
            QUEUE.clear();
            if (list != null) QUEUE.addAll(list);
            pendingIndex = index;
        }
    }

    private static String urlOf(Entry e) {
        if (e == null) return "";
        if (e.url.length() > 0) return e.url;
        String l;
        synchronized (LEARNED) { l = LEARNED.get(e.key); }
        return l == null ? "" : l;
    }

    // ================================================================== per screen state

    private static final class S {
        Activity a;
        SharedPreferences prefs;
        FrameLayout root;
        FrameLayout stage;
        PlayerView view;
        ExoPlayer player;
        DefaultTrackSelector selector;

        FrameLayout controls;
        TextView title;
        TextView sub;
        ImageView logo;
        TextView logoLetter;
        ImageView playIcon;
        Ic playGlyph;
        View prevBtn;
        View nextBtn;
        TextView prevName;
        TextView nextName;
        View centerRow;
        TextView liveEdge;
        LiveLine liveLine;
        TextView quality;
        String qualityText = "";
        int liveMode = -1;    // 0 at the live edge, 1 behind (paused / rewound)
        Ic castGlyph;
        View pipBtn;
        ProgressBar spinner;
        TextView spinnerText;
        LinearLayout errorBox;
        TextView errorText;
        LinearLayout castBox;
        TextView castDevice;
        TextView castChannel;
        TextView bubble;
        TextView unlockChip;
        LinearLayout drawer;
        LinearLayout drawerRow;
        HorizontalScrollView drawerScroll;

        final ArrayList<Entry> list = new ArrayList<Entry>();
        int index = -1;
        String url = "";
        int aspect;
        int orient;
        boolean controlsShown = true;
        boolean locked;
        boolean drawerOpen;
        /** v10.66 TV remote: the play button, and whether the viewer is moving between the buttons. */
        View tvPlay;
        boolean tvBrowse;
        int retries;
        String mime;          // stream type being tried (CigramStreams); null = let ExoPlayer decide
        int typeTry = -1;     // index in CigramStreams.FALLBACK while guessing a link without a hint
        boolean released;
        boolean resumeOnStart;
        long sleepAt;

        // cast
        CastContext cast;
        SessionManagerListener<CastSession> castListener;
        MediaRouter router;
        MediaRouter.Callback routerCallback;
        boolean wantCast;
        boolean casting;
        CigramDlna.Device dlna;
        String castName = "";
        boolean dlnaStarted;

        // gestures
        float brightness = -1f;
        int volumeStart;
        float startY;
        float downX;
        int gestureMode;     // 0 none, 1 brightness, 2 volume
        Object backCallback;

        final Runnable hide = new Runnable() {
            @Override public void run() { showControls(S.this, false); }
        };
        int lastState = -1;
        boolean lastPlaying;
        Object lastError;
        final Runnable watch = new Runnable() {
            @Override public void run() { onWatch(S.this); }
        };
        final Runnable tick = new Runnable() {
            @Override public void run() { onTick(S.this); }
        };
        final Runnable hideBubble = new Runnable() {
            @Override public void run() { if (bubble != null) bubble.animate().alpha(0f).setDuration(200L).start(); }
        };
        final Runnable hideUnlock = new Runnable() {
            @Override public void run() { if (unlockChip != null) unlockChip.setVisibility(View.GONE); }
        };
    }

    private static final WeakHashMap<Activity, S> STATES = new WeakHashMap<Activity, S>();
    private static boolean lifecycleRegistered;

    private static S state(Activity a) {
        synchronized (STATES) { return STATES.get(a); }
    }

    private static boolean alive(Activity a) {
        return a != null && !a.isFinishing() && !(Build.VERSION.SDK_INT >= 17 && a.isDestroyed());
    }

    private static int dp(Context c, float v) { return CigramUI.dp(c, v); }

    // ================================================================== entry point

    public static void attach(final Activity a) {
        if (a == null) return;
        try {
            registerLifecycle(a);
            final S s = new S();
            s.a = a;
            s.prefs = a.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            s.aspect = Math.max(0, Math.min(4, s.prefs.getInt("aspect", 0)));
            s.orient = Math.max(0, Math.min(2, s.prefs.getInt("orient", 0)));
            synchronized (STATES) { STATES.put(a, s); }

            setupWindow(a);
            applyOrientation(s);
            resolveQueue(s);

            FrameLayout root = (FrameLayout) a.findViewById(android.R.id.content);
            root.removeAllViews();
            root.setBackgroundColor(Color.BLACK);
            s.root = root;

            buildVideo(s);
            buildTouchLayer(s);
            buildControls(s);
            buildDrawer(s);
            buildStatus(s);
            buildCastBox(s);
            setupBack(s);
            setupCast(s);

            Entry e = current(s);
            bindTitle(s);
            String startUrl = e == null ? "" : urlOf(e);
            if (startUrl.length() == 0) {
                Toast.makeText(a.getApplicationContext(), "رابط القناة غير موجود", Toast.LENGTH_LONG).show();
                showError(s, "رابط القناة غير موجود");
            } else {
                play(s, startUrl);
            }
            showControls(s, true);
            UI.postDelayed(s.tick, 1000L);
            CigramAnalytics.track(a, "live_open", "channel", e == null ? "" : e.name);
            tvKeys(s);   // v10.66: TV remote (TV only)
        } catch (Throwable error) {
            Log.w(TAG, "attach failed", error);
        }
    }

    private static void setupWindow(Activity a) {
        try {
            a.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = a.getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                a.getWindow().setAttributes(lp);
            }
            immersive(a);
        } catch (Throwable error) {
            Log.w(TAG, "window setup failed", error);
        }
    }

    private static void immersive(Activity a) {
        try {
            a.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } catch (Throwable ignored) { }
    }

    /** Works out the list and the current channel from the channels page (or the intent alone). */
    private static void resolveQueue(S s) {
        String intentUrl = "";
        String intentTitle = "";
        try {
            intentUrl = clean(s.a.getIntent().getStringExtra("live_url"));
            if (intentUrl.length() == 0) intentUrl = clean(s.a.getIntent().getStringExtra("video"));
            intentTitle = clean(s.a.getIntent().getStringExtra("title"));
        } catch (Throwable ignored) { }
        int idx;
        boolean keep;
        synchronized (QUEUE) {
            s.list.addAll(QUEUE);
            idx = pendingIndex;
            pendingIndex = -1;
            keep = keepIndex;
            keepIndex = false;
        }
        if (keep && idx >= 0 && idx < s.list.size() && urlOf(s.list.get(idx)).length() > 0) {
            s.index = idx;
            return;
        }
        if (idx < 0 || idx >= s.list.size()) {
            idx = -1;
            for (int i = 0; i < s.list.size() && intentUrl.length() > 0; i++) {
                if (intentUrl.equals(urlOf(s.list.get(i)))) { idx = i; break; }
            }
        } else {
            Entry e = s.list.get(idx);
            String known = urlOf(e);
            if (known.length() > 0 && intentUrl.length() > 0 && !known.equals(intentUrl)) idx = -1;
        }
        if (idx < 0) {
            s.list.clear();
            s.list.add(entry("", intentTitle.length() > 0 ? intentTitle : "بث مباشر", intentUrl, "", "", null));
            idx = 0;
        }
        Entry e = s.list.get(idx);
        if (e.url.length() == 0 && intentUrl.length() > 0) {
            synchronized (LEARNED) { LEARNED.put(e.key, intentUrl); }
        }
        s.index = idx;
    }

    private static Entry current(S s) {
        return s.index >= 0 && s.index < s.list.size() ? s.list.get(s.index) : null;
    }

    // ================================================================== video

    private static void buildVideo(final S s) {
        Activity a = s.a;
        s.stage = new FrameLayout(a);
        s.stage.setBackgroundColor(Color.BLACK);
        s.root.addView(s.stage, new FrameLayout.LayoutParams(-1, -1));

        s.view = new PlayerView(a);
        s.view.setUseController(false);
        s.view.setBackgroundColor(Color.BLACK);
        s.stage.addView(s.view, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        s.selector = new DefaultTrackSelector(a);
        // 18.3: 10 s / 30 s window as before; the start / re-buffer thresholds (1.5 s / 3 s) now follow the connection
        DefaultLoadControl load = CigramAdaptiveLoadControl.create(a, CigramBufferPolicy.custom(10000, 30000, 0), true);
        s.player = new ExoPlayer.Builder(a)
                .setTrackSelector(s.selector)
                .setLoadControl(load)
                // 18.3: audio focus (a call / another app pauses or ducks the channel, it resumes afterwards)
                .setAudioAttributes(new com.google.android.exoplayer2.audio.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .setHandleAudioBecomingNoisy(true)       // headphones unplugged: pause instead of blasting the speaker
                .setMediaSourceFactory(CigramStreams.sourceFactory(a))
                .build();
        s.view.setPlayer(s.player);
        // v10.34: NO Player.Listener. Sketchware's D8 build crashes inside ExoPlayer's ListenerSet
        // with listener default methods (same rule as the movie player): the state is polled.
        UI.postDelayed(s.watch, 250L);

        s.stage.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) {
                    UI.post(new Runnable() { @Override public void run() { applyAspect(s); } });
                }
            }
        });
        applyAspect(s);
    }

    private static void play(S s, String url) {
        if (s.released) return;
        s.url = url == null ? "" : url.trim();
        s.retries = 0;
        s.lastError = null;
        s.lastState = -1;
        s.mime = null;
        s.typeTry = -1;
        hideError(s);
        setSpinner(s, true);
        final String asked = s.url;
        final S st = s;
        // Any link: the type comes from the link itself, or the server is asked (links without a hint).
        CigramStreams.sniff(asked, new CigramStreams.MimeCallback() {
            @Override public void onMime(String mime) {
                if (st.released || !asked.equals(st.url)) return;   // another channel was chosen meanwhile
                start(st, mime);
            }
        });
    }

    /** Opens s.url as the given type (null = unknown: ExoPlayer guesses, then FALLBACK is tried). */
    private static void start(S s, String mime) {
        s.mime = mime;
        s.lastError = null;
        String missing = CigramStreams.missingModule(mime);
        if (missing.length() > 0) {
            Log.w(TAG, "missing ExoPlayer module " + missing + " for " + mime);
            setSpinner(s, false);
            showError(s, CigramStreams.missingText(missing));
            return;
        }
        try {
            s.player.setMediaItem(CigramStreams.apply(new MediaItem.Builder(), s.url, mime).build());
            s.player.prepare();
            if (!s.casting) s.player.play();
        } catch (Throwable error) {
            Log.w(TAG, "play failed", error);
            if (!nextType(s)) showError(s, "تعذر تشغيل القناة");
        }
    }

    /** The link was not the type tried: tries the next one. False when every type was tried. */
    private static boolean nextType(S s) {
        String[] order = CigramStreams.FALLBACK;
        int i = s.typeTry + 1;
        while (i < order.length && (order[i].equals(s.mime) || CigramStreams.missingModule(order[i]).length() > 0)) i++;
        if (i >= order.length) return false;
        s.typeTry = i;
        Log.i(TAG, "retrying the link as " + order[i]);
        start(s, order[i]);
        return true;
    }

    /** Reconnects up to 4 times (2s, 4s, 6s, 8s), then shows the error with a retry button. */
    private static void retry(final S s, boolean fromError) {
        if (s.released || s.casting) return;
        if (s.retries >= 4) {
            setSpinner(s, false);
            showError(s, "تعذر تشغيل القناة حالياً");
            return;
        }
        s.retries++;
        setSpinner(s, true);
        if (s.spinnerText != null) s.spinnerText.setText("جارٍ إعادة الاتصال… (" + s.retries + ")");
        UI.postDelayed(new Runnable() {
            @Override public void run() {
                if (s.released || s.casting) return;
                try {
                    s.player.seekToDefaultPosition();
                    s.player.prepare();
                    s.player.play();
                } catch (Throwable ignored) { }
            }
        }, 2000L * s.retries);
    }

    // ================================================================== screen size / orientation

    private static void applyAspect(S s) {
        if (s.view == null || s.stage == null) return;
        try {
            int w = s.stage.getWidth();
            int h = s.stage.getHeight();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) s.view.getLayoutParams();
            if (s.aspect >= 3 && w > 0 && h > 0) {
                float ratio = s.aspect == 3 ? 16f / 9f : 4f / 3f;
                int vw = w, vh = Math.round(w / ratio);
                if (vh > h) { vh = h; vw = Math.round(h * ratio); }
                lp.width = vw;
                lp.height = vh;
                s.view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
            } else {
                lp.width = -1;
                lp.height = -1;
                s.view.setResizeMode(s.aspect == 1 ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        : s.aspect == 2 ? AspectRatioFrameLayout.RESIZE_MODE_FILL
                        : AspectRatioFrameLayout.RESIZE_MODE_FIT);
            }
            lp.gravity = Gravity.CENTER;
            s.view.setLayoutParams(lp);
        } catch (Throwable error) {
            Log.w(TAG, "aspect failed", error);
        }
    }

    private static void setAspect(S s, int mode, boolean announce) {
        s.aspect = (mode % 5 + 5) % 5;
        s.prefs.edit().putInt("aspect", s.aspect).apply();
        applyAspect(s);
        if (announce) bubble(s, "أبعاد الشاشة: " + ASPECT_LABELS[s.aspect]);
    }

    private static void applyOrientation(S s) {
        try {
            if (CigramTvMode.isTv(s.a)) {   // v10.66: a TV stays landscape whatever was saved on a phone
                s.a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                return;
            }
            int o = s.orient == 1 ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    : s.orient == 2 ? ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            s.a.setRequestedOrientation(o);
        } catch (Throwable ignored) { }
    }

    private static void setOrientation(S s, int mode) {
        s.orient = (mode % 3 + 3) % 3;
        s.prefs.edit().putInt("orient", s.orient).apply();
        applyOrientation(s);
        bubble(s, "اتجاه الشاشة: " + ORIENT_LABELS[s.orient]);
    }

    // ================================================================== gestures

    private static void buildTouchLayer(final S s) {
        final Activity a = s.a;
        View touch = new View(a);
        s.root.addView(touch, new FrameLayout.LayoutParams(-1, -1));

        final GestureDetector taps = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (s.locked) {
                    showUnlock(s);
                    return true;
                }
                if (s.drawerOpen) {
                    openDrawer(s, false);
                    return true;
                }
                showControls(s, !s.controlsShown);
                return true;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (s.locked || s.casting) return false;
                setAspect(s, s.aspect == 1 ? 0 : 1, true);
                return true;
            }
        });
        final ScaleGestureDetector pinch = new ScaleGestureDetector(a,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    float total = 1f;
                    @Override public boolean onScaleBegin(ScaleGestureDetector d) { total = 1f; return true; }
                    @Override public boolean onScale(ScaleGestureDetector d) { total *= d.getScaleFactor(); return true; }
                    @Override public void onScaleEnd(ScaleGestureDetector d) {
                        if (s.locked || s.casting) return;
                        if (total > 1.12f && s.aspect != 1) setAspect(s, 1, true);
                        else if (total < 0.9f && s.aspect != 0) setAspect(s, 0, true);
                    }
                });
        touch.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                pinch.onTouchEvent(e);
                taps.onTouchEvent(e);
                if (s.locked || s.casting || e.getPointerCount() > 1) {
                    s.gestureMode = 0;
                    return true;
                }
                handleSwipe(s, v, e);
                return true;
            }
        });
    }

    /** Vertical swipe: left half = brightness, right half = volume. */
    private static void handleSwipe(S s, View v, MotionEvent e) {
        int h = Math.max(1, v.getHeight());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                s.startY = e.getY();
                s.gestureMode = 0;
                s.downX = e.getX();
                break;
            case MotionEvent.ACTION_MOVE: {
                float dy = s.startY - e.getY();
                if (s.gestureMode == 0) {
                    if (Math.abs(dy) < dp(s.a, 18)) return;
                    // ignore the system edges (status / navigation gestures)
                    if (s.startY < dp(s.a, 40) || s.startY > h - dp(s.a, 40)) return;
                    s.gestureMode = s.downX < v.getWidth() / 2f ? 1 : 2;
                    if (s.gestureMode == 1) {
                        float cur = s.a.getWindow().getAttributes().screenBrightness;
                        if (cur < 0f) {
                            try {
                                cur = android.provider.Settings.System.getInt(s.a.getContentResolver(),
                                        android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f;
                            } catch (Throwable ignored) { cur = 0.5f; }
                        }
                        s.brightness = cur;
                    } else {
                        AudioManager am = (AudioManager) s.a.getSystemService(Context.AUDIO_SERVICE);
                        s.volumeStart = am == null ? 0 : am.getStreamVolume(AudioManager.STREAM_MUSIC);
                    }
                    s.startY = e.getY();
                    dy = 0f;
                    showControls(s, false);
                }
                float frac = dy / (h * 0.8f);
                if (s.gestureMode == 1) {
                    float b = Math.max(0.01f, Math.min(1f, s.brightness + frac));
                    WindowManager.LayoutParams lp = s.a.getWindow().getAttributes();
                    lp.screenBrightness = b;
                    s.a.getWindow().setAttributes(lp);
                    bubble(s, "☀  السطوع " + Math.round(b * 100) + "%");
                } else if (s.gestureMode == 2) {
                    AudioManager am = (AudioManager) s.a.getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        int vol = Math.max(0, Math.min(max, s.volumeStart + Math.round(frac * max)));
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
                        bubble(s, "🔊  الصوت " + Math.round(vol * 100f / Math.max(1, max)) + "%");
                    }
                }
                break;
            }
            default:
                s.gestureMode = 0;
                break;
        }
    }

    // ================================================================== controls

    private static void buildControls(final S s) {
        final Activity a = s.a;
        s.controls = new FrameLayout(a);
        s.root.addView(s.controls, new FrameLayout.LayoutParams(-1, -1));

        // v10.35: the buttons have no background of their own, so while the controls are up the
        // whole picture is dimmed a little and the edges get a deeper shade.
        View scrim = new View(a);
        scrim.setBackgroundColor(0x2E000000);
        s.controls.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        View topShade = new View(a);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3000000, 0x4D000000, 0x00000000}));
        s.controls.addView(topShade, new FrameLayout.LayoutParams(-1, dp(a, 130), Gravity.TOP));
        View bottomShade = new View(a);
        bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xC7000000, 0x59000000, 0x00000000}));
        s.controls.addView(bottomShade, new FrameLayout.LayoutParams(-1, dp(a, 150), Gravity.BOTTOM));

        // ---- top bar, physical order left → right: tools … quality, name, logo, back
        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(a, 10), dp(a, 10), dp(a, 10), 0);

        top.addView(iconButton(a, new Ic(a, Ic.SETTINGS), "الإعدادات", new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(s); }
        }), btnLp(a, 0));
        s.pipBtn = iconButton(a, new Ic(a, Ic.PIP), "نافذة عائمة", new View.OnClickListener() {
            @Override public void onClick(View v) { enterPip(s); }
        });
        s.pipBtn.setVisibility(pipSupported(a) ? View.VISIBLE : View.GONE);
        top.addView(s.pipBtn, btnLp(a, 4));
        View rotateBtn = iconButton(a, new Ic(a, Ic.ROTATE), "تدوير الشاشة", new View.OnClickListener() {
            @Override public void onClick(View v) { setOrientation(s, s.orient == 0 ? 1 : 0); scheduleHide(s); }
        });
        top.addView(rotateBtn, btnLp(a, 4));
        top.addView(iconButton(a, new Ic(a, Ic.ASPECT), "أبعاد الشاشة", new View.OnClickListener() {
            @Override public void onClick(View v) { setAspect(s, s.aspect + 1, true); scheduleHide(s); }
        }), btnLp(a, 4));
        s.castGlyph = new Ic(a, Ic.CAST);
        View castBtn = iconButton(a, s.castGlyph, "بث إلى التلفاز", new View.OnClickListener() {
            @Override public void onClick(View v) { onCastButton(s); }
        });
        top.addView(castBtn, btnLp(a, 4));
        if (CigramTvMode.isTv(a)) {
            // v10.66: on a TV the app is the screen: no «بث إلى التلفاز», and no rotation to portrait
            castBtn.setVisibility(View.GONE);
            rotateBtn.setVisibility(View.GONE);
        }

        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.RIGHT);
        titles.setPadding(dp(a, 14), 0, dp(a, 12), 0);

        // name row: [HD] اسم القناة — the quality tag sits right before the name
        LinearLayout nameRow = new LinearLayout(a);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        nameRow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        s.quality = CigramUI.label(a, "", 10f, Color.WHITE, true);
        s.quality.setTypeface(CigramTypefaces.bold());
        s.quality.setGravity(Gravity.CENTER);
        s.quality.setIncludeFontPadding(false);
        s.quality.setPadding(dp(a, 5), dp(a, 3), dp(a, 5), dp(a, 3));
        s.quality.setBackground(CigramUI.round(0x00000000, 0xD9FFFFFF, 4, a));   // outline only
        s.quality.setContentDescription("جودة البث");
        s.quality.setVisibility(View.GONE);
        s.quality.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSettings(s); }
        });
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(-2, -2);
        qlp.rightMargin = dp(a, 8);
        nameRow.addView(s.quality, qlp);
        s.title = CigramUI.label(a, "", 18f, Color.WHITE, true);
        s.title.setTypeface(CigramStage1InitProvider.cigramFont(a), android.graphics.Typeface.BOLD);
        s.title.setSingleLine(true);
        s.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        s.title.setGravity(Gravity.RIGHT);
        shadow(s.title);
        nameRow.addView(s.title, new LinearLayout.LayoutParams(-2, -2));
        titles.addView(nameRow, new LinearLayout.LayoutParams(-1, -2));
        s.sub = CigramUI.label(a, "", 12f, SOFT, false);
        s.sub.setGravity(Gravity.RIGHT);
        s.sub.setSingleLine(true);
        s.sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        shadow(s.sub);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(a, 1);
        titles.addView(s.sub, subLp);
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        FrameLayout logoBox = new FrameLayout(a);
        logoBox.setBackground(CigramUI.round(0xFF1E1E21, 0x40FFFFFF, 14, a));
        logoBox.setClipToOutline(true);
        s.logoLetter = CigramUI.label(a, "", 17f, 0x99FFFFFF, true);
        s.logoLetter.setGravity(Gravity.CENTER);
        logoBox.addView(s.logoLetter, new FrameLayout.LayoutParams(-1, -1));
        s.logo = new ImageView(a);
        s.logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logoBox.addView(s.logo, new FrameLayout.LayoutParams(-1, -1));
        top.addView(logoBox, new LinearLayout.LayoutParams(dp(a, 46), dp(a, 46)));

        top.addView(iconButton(a, new Ic(a, Ic.BACK), "رجوع", new View.OnClickListener() {
            @Override public void onClick(View v) { CigramNav.back(a); }
        }), btnLp(a, 6));
        s.controls.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // ---- center: the zapper. Physical order: [next channel ◀▮] [ ❚❚ ] [▮▶ previous channel]
        //      each side shows the neighbour channel's name (RTL: the next channel is on the left)
        LinearLayout center = new LinearLayout(a);
        center.setOrientation(LinearLayout.HORIZONTAL);
        center.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        center.setGravity(Gravity.CENTER_VERTICAL);
        s.nextName = CigramUI.label(a, "", 14f, Color.WHITE, true);
        s.nextBtn = zapButton(a, new Ic(a, Ic.NEXT_LEFT), "القناة التالية", s.nextName, true,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { step(s, +1); }
                });
        center.addView(s.nextBtn, new LinearLayout.LayoutParams(dp(a, 184), -2));

        s.playGlyph = new Ic(a, Ic.PAUSE);
        FrameLayout playBtn = new FrameLayout(a);
        playBtn.setContentDescription("تشغيل / إيقاف");
        playBtn.setClickable(true);
        playBtn.setFocusable(true);
        ImageView playIv = new ImageView(a);
        playIv.setImageDrawable(s.playGlyph);
        playBtn.addView(playIv, new FrameLayout.LayoutParams(dp(a, 58), dp(a, 58), Gravity.CENTER));
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(s); }
        });
        pressFx(playBtn);
        s.tvPlay = playBtn;
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(a, 92), dp(a, 92));
        plp.leftMargin = dp(a, 14);
        plp.rightMargin = dp(a, 14);
        center.addView(playBtn, plp);

        s.prevName = CigramUI.label(a, "", 14f, Color.WHITE, true);
        s.prevBtn = zapButton(a, new Ic(a, Ic.PREV_RIGHT), "القناة السابقة", s.prevName, false,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { step(s, -1); }
                });
        center.addView(s.prevBtn, new LinearLayout.LayoutParams(dp(a, 184), -2));
        s.centerRow = center;
        s.controls.addView(center, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // ---- bottom: the live line, then [● مباشر] … [🔒 قفل] [📺 القنوات]
        LinearLayout bottomBox = new LinearLayout(a);
        bottomBox.setOrientation(LinearLayout.VERTICAL);
        bottomBox.setPadding(dp(a, 16), 0, dp(a, 16), dp(a, 6));

        s.liveLine = new LiveLine(a);
        bottomBox.addView(s.liveLine, new LinearLayout.LayoutParams(-1, dp(a, 24)));

        LinearLayout bottom = new LinearLayout(a);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        bottom.setGravity(Gravity.CENTER_VERTICAL);

        s.liveEdge = CigramUI.label(a, "مباشر", 13f, Color.WHITE, true);
        s.liveEdge.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        // the dot lines up with the live edge of the line above it
        s.liveEdge.setPadding(dp(a, 7), dp(a, 8), dp(a, 12), dp(a, 8));
        s.liveEdge.setCompoundDrawablePadding(dp(a, 8));
        s.liveEdge.setContentDescription("العودة للبث المباشر");
        shadow(s.liveEdge);
        s.liveEdge.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (s.casting) return;
                s.player.seekToDefaultPosition();
                s.player.play();
                bubble(s, "عدت إلى البث المباشر");
            }
        });
        pressFx(s.liveEdge);
        bottom.addView(s.liveEdge, new LinearLayout.LayoutParams(-2, -2));
        bottom.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));

        bottom.addView(textButton(a, new Ic(a, Ic.LOCK), "قفل", new View.OnClickListener() {
            @Override public void onClick(View v) { setLocked(s, true); }
        }), new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(-2, -2);
        chLp.leftMargin = dp(a, 14);
        View channelsBtn = textButton(a, new Ic(a, Ic.LIST), "القنوات", new View.OnClickListener() {
            @Override public void onClick(View v) { openDrawer(s, !s.drawerOpen); }
        });
        bottom.addView(channelsBtn, chLp);
        channelsBtn.setVisibility(s.list.size() > 1 ? View.VISIBLE : View.GONE);
        bottomBox.addView(bottom, new LinearLayout.LayoutParams(-1, -2));
        s.controls.addView(bottomBox, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setLiveMode(s, false);

        boolean many = s.list.size() > 1;
        s.prevBtn.setVisibility(many ? View.VISIBLE : View.INVISIBLE);
        s.nextBtn.setVisibility(many ? View.VISIBLE : View.INVISIBLE);
    }

    /** ● مباشر at the live edge, or «العودة للمباشر» when the picture is behind it. */
    private static void setLiveMode(S s, boolean behind) {
        int mode = behind ? 1 : 0;
        if (s.liveEdge == null || mode == s.liveMode) return;
        s.liveMode = mode;
        Ic ic = new Ic(s.a, behind ? Ic.GO_LIVE : Ic.DOT);
        int size = dp(s.a, behind ? 18 : 10);
        ic.setBounds(0, 0, size, size);
        s.liveEdge.setCompoundDrawables(ic, null, null, null);
        s.liveEdge.setText(behind ? "العودة للمباشر" : "مباشر");
        s.liveEdge.setTextColor(behind ? SOFT : Color.WHITE);
    }

    /** HD / FHD / 4K tag next to the channel name, from the picture that is playing now. */
    private static void updateQuality(S s) {
        if (s.quality == null) return;
        String q = "";
        try {
            Format f = s.casting || s.player == null ? null : s.player.getVideoFormat();
            if (f != null) {
                int side = f.width > 0 && f.height > 0 ? Math.min(f.width, f.height) : Math.max(f.width, f.height);
                q = side >= 2000 ? "4K" : side >= 1000 ? "FHD" : side >= 700 ? "HD" : side > 0 ? "SD" : "";
            }
        } catch (Throwable ignored) { }
        if (q.equals(s.qualityText)) return;
        s.qualityText = q;
        s.quality.setText(q);
        s.quality.setVisibility(q.length() > 0 ? View.VISIBLE : View.GONE);
    }

    private static void bindTitle(S s) {
        Entry e = current(s);
        if (e == null) return;
        s.title.setText(e.name);
        String section = e.section.length() > 0 ? e.section : "بث مباشر";
        int n = s.list.size();
        s.sub.setText(n > 1 ? section + "  ·  " + (s.index + 1) + " من " + n : section);
        if (n > 1) {
            Entry next = s.list.get((s.index + 1) % n);
            Entry prev = s.list.get(((s.index - 1) % n + n) % n);
            if (s.nextName != null) s.nextName.setText(next.name);
            if (s.prevName != null) s.prevName.setText(prev.name);
        }
        s.logoLetter.setText(e.name.length() > 0 ? e.name.substring(0, 1).toUpperCase(Locale.US) : "");
        s.logo.setImageDrawable(null);
        View origin = e.origin == null ? null : e.origin.get();
        Drawable d = originDrawable(origin);
        if (d != null) {
            s.logo.setImageDrawable(d);
            s.logoLetter.setVisibility(View.GONE);
        } else {
            s.logoLetter.setVisibility(View.VISIBLE);
            if (e.image.length() > 0) CigramChannels.loadLogo(e.image, s.logo, s.logoLetter);
        }
        if (s.castChannel != null) s.castChannel.setText(e.name);
        highlightDrawer(s);
    }

    private static Drawable originDrawable(View origin) {
        try {
            ImageView iv = findImage(origin);
            if (iv == null || iv.getDrawable() == null) return null;
            Drawable d = iv.getDrawable();
            return d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ImageView findImage(View v) {
        if (v instanceof ImageView) return (ImageView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ImageView f = findImage(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    private static void togglePlay(S s) {
        if (s.casting) return;
        if (s.player.isPlaying()) {
            s.player.pause();
            showControls(s, true);
        } else {
            if (s.player.getPlaybackState() == Player.STATE_IDLE || s.player.getPlaybackState() == Player.STATE_ENDED) {
                s.player.prepare();
            }
            s.player.seekToDefaultPosition();
            s.player.play();
        }
        updatePlayIcon(s);
    }

    private static void updatePlayIcon(S s) {
        if (s.playGlyph == null) return;
        boolean playing = s.player != null && s.player.getPlayWhenReady();
        s.playGlyph.setKind(playing ? Ic.PAUSE : Ic.PLAY);
    }

    private static void showControls(final S s, boolean show) {
        if (s.controls == null) return;
        UI.removeCallbacks(s.hide);
        if (s.locked || inPip(s.a)) show = false;
        s.controlsShown = show;
        s.controls.animate().cancel();
        if (show) {
            s.controls.setVisibility(View.VISIBLE);
            if (s.liveLine != null) s.liveLine.invalidate();   // restarts the live pulse
            s.controls.animate().alpha(1f).setDuration(160L).start();
            scheduleHide(s);
        } else {
            s.controls.animate().alpha(0f).setDuration(200L).withEndAction(new Runnable() {
                @Override public void run() { if (!s.controlsShown) s.controls.setVisibility(View.GONE); }
            }).start();
            immersive(s.a);
        }
    }

    private static void scheduleHide(S s) {
        UI.removeCallbacks(s.hide);
        if (s.player != null && s.player.isPlaying() && !s.drawerOpen && !s.casting) {
            UI.postDelayed(s.hide, 4000L);
        }
    }

    private static void step(S s, int delta) {
        if (s.list.size() < 2) return;
        int n = s.list.size();
        switchTo(s, ((s.index + delta) % n + n) % n);
    }

    private static void switchTo(S s, int i) {
        if (i < 0 || i >= s.list.size() || s.released) return;
        Entry e = s.list.get(i);
        String url = urlOf(e);
        if (url.length() == 0) {
            // built-in channel not opened yet: its own card knows the link
            View origin = e.origin == null ? null : e.origin.get();
            if (origin != null && origin.isAttachedToWindow()) {
                setQueue(s.list, i);
                try {
                    origin.performClick();
                    s.a.finish();
                    s.a.overridePendingTransition(0, 0);
                } catch (Throwable error) {
                    Log.w(TAG, "origin open failed", error);
                }
                return;
            }
            bubble(s, "القناة غير متوفرة حالياً");
            return;
        }
        s.index = i;
        bindTitle(s);
        CigramChannels.noteRecent(s.a, e.key);
        if (s.casting) {
            castLoad(s);
        } else {
            play(s, url);
        }
        bubble(s, e.name);
        showControls(s, true);
    }

    // ================================================================== TV remote (v10.66)

    /**
     * TV only. ▲ / ▼ (and CH+ / CH−, ⏭ / ⏮) zap to the next / previous channel; ◀ / ▶ and MENU open
     * the channel strip; OK brings the controls and puts the remote on play / pause (then the arrows
     * move between the buttons); ⏯ plays / pauses; Back first hides the controls.
     * The lock and the channel strip keep their own keys (Back closes them as before).
     */
    private static void tvKeys(final S s) {
        try {
            if (!CigramTvMode.isTv(s.a)) return;
            CigramTvFocus.setPlayerMode(s.a);
            CigramTvFocus.setDefaultFocus(s.a, s.tvPlay);
            CigramTvFocus.setRingFilter(s.a, new CigramTvFocus.RingFilter() {
                @Override public boolean ringVisible(View target) {
                    return s.controlsShown || s.drawerOpen;
                }
            });
            CigramTvFocus.setKeyHandler(s.a, new CigramTvFocus.KeyHandler() {
                @Override public boolean onKey(android.view.KeyEvent e) {
                    if (s.released || s.locked || s.drawerOpen) return false;
                    int c = e.getKeyCode();
                    boolean down = e.getAction() == android.view.KeyEvent.ACTION_DOWN;
                    boolean first = down && e.getRepeatCount() == 0;
                    boolean up = e.getAction() == android.view.KeyEvent.ACTION_UP && !e.isCanceled();
                    switch (c) {
                        case android.view.KeyEvent.KEYCODE_CHANNEL_UP:
                        case android.view.KeyEvent.KEYCODE_MEDIA_NEXT:
                            if (first) step(s, 1);
                            return true;
                        case android.view.KeyEvent.KEYCODE_CHANNEL_DOWN:
                        case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                            if (first) step(s, -1);
                            return true;
                        case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                            if (first) togglePlay(s);
                            return true;
                        case android.view.KeyEvent.KEYCODE_MEDIA_PLAY:
                            if (first && !s.lastPlaying) togglePlay(s);
                            return true;
                        case android.view.KeyEvent.KEYCODE_MEDIA_PAUSE:
                        case android.view.KeyEvent.KEYCODE_MEDIA_STOP:
                            if (first && s.lastPlaying) togglePlay(s);
                            return true;
                        default:
                            break;
                    }
                    if (!s.controlsShown) s.tvBrowse = false;
                    if ((c == android.view.KeyEvent.KEYCODE_DPAD_UP || c == android.view.KeyEvent.KEYCODE_DPAD_DOWN)
                            && !s.tvBrowse) {
                        if (first) step(s, c == android.view.KeyEvent.KEYCODE_DPAD_UP ? 1 : -1);
                        return true;
                    }
                    if (!s.tvBrowse && (c == android.view.KeyEvent.KEYCODE_MENU
                            || c == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                            || c == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
                        if (first) tvOpenStrip(s);
                        return true;
                    }
                    if (CigramTvFocus.isConfirm(c) && !s.tvBrowse) {
                        if (first) {
                            s.tvBrowse = true;
                            showControls(s, true);
                            if (s.tvPlay != null && s.tvPlay.isShown()) s.tvPlay.requestFocus();
                        }
                        return true;
                    }
                    if (!s.controlsShown) return false;
                    if (c == android.view.KeyEvent.KEYCODE_BACK) {
                        if (up) {
                            s.tvBrowse = false;
                            showControls(s, false);
                        }
                        return true;
                    }
                    if (c == android.view.KeyEvent.KEYCODE_MENU) {
                        if (first) tvOpenStrip(s);
                        return true;
                    }
                    if (down) scheduleHide(s);   // every key keeps the controls up
                    return false;
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "tv keys failed", error);
        }
    }

    /** Opens the channel strip with the remote on the channel that is playing. */
    private static void tvOpenStrip(final S s) {
        s.tvBrowse = false;
        openDrawer(s, true);
        if (s.drawerRow == null) return;
        s.drawerRow.postDelayed(new Runnable() {
            @Override public void run() {
                if (!s.drawerOpen || s.drawerRow == null) return;
                for (int k = 0; k < s.drawerRow.getChildCount(); k++) {
                    View t = s.drawerRow.getChildAt(k);
                    if (Integer.valueOf(s.index).equals(t.getTag())) {
                        if (!t.isFocusable()) t.setFocusable(true);
                        t.requestFocus();
                        return;
                    }
                }
            }
        }, 80L);
    }

    // ================================================================== lock

    private static void setLocked(S s, boolean locked) {
        s.locked = locked;
        if (locked) {
            openDrawer(s, false);
            showControls(s, false);
            showUnlock(s);
            bubble(s, "تم قفل الشاشة");
        } else {
            if (s.unlockChip != null) s.unlockChip.setVisibility(View.GONE);
            showControls(s, true);
        }
        updateBack(s);
    }

    private static void showUnlock(S s) {
        if (s.unlockChip == null) return;
        s.unlockChip.setVisibility(View.VISIBLE);
        UI.removeCallbacks(s.hideUnlock);
        UI.postDelayed(s.hideUnlock, 2500L);
    }

    // ================================================================== channel drawer

    private static void buildDrawer(final S s) {
        final Activity a = s.a;
        s.drawer = new LinearLayout(a);
        s.drawer.setOrientation(LinearLayout.VERTICAL);
        s.drawer.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF2000000, 0xE6000000, 0x00000000}));
        s.drawer.setPadding(0, dp(a, 34), 0, dp(a, 14));
        s.drawer.setVisibility(View.GONE);
        s.drawer.setClickable(true);

        TextView head = CigramUI.label(a, "القنوات", 15f, Color.WHITE, true);
        head.setTypeface(CigramStage1InitProvider.cigramFont(a), android.graphics.Typeface.BOLD);
        head.setGravity(Gravity.RIGHT);
        head.setPadding(dp(a, 18), 0, dp(a, 18), dp(a, 8));
        s.drawer.addView(head, new LinearLayout.LayoutParams(-1, -2));

        s.drawerScroll = new HorizontalScrollView(a);
        s.drawerScroll.setHorizontalScrollBarEnabled(false);
        s.drawerScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.drawerScroll.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        s.drawerRow = new LinearLayout(a);
        s.drawerRow.setOrientation(LinearLayout.HORIZONTAL);
        s.drawerRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        s.drawerRow.setPadding(dp(a, 12), 0, dp(a, 12), 0);
        // item 0 at the far right (RTL)
        for (int i = s.list.size() - 1; i >= 0; i--) {
            s.drawerRow.addView(drawerTile(s, i));
        }
        s.drawerScroll.addView(s.drawerRow, new HorizontalScrollView.LayoutParams(-2, -2));
        s.drawer.addView(s.drawerScroll, new LinearLayout.LayoutParams(-1, -2));
        s.root.addView(s.drawer, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    private static View drawerTile(final S s, final int i) {
        Activity a = s.a;
        Entry e = s.list.get(i);
        LinearLayout tile = new LinearLayout(a);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setTag(Integer.valueOf(i));
        FrameLayout pic = new FrameLayout(a);
        pic.setBackground(CigramUI.round(0xFF1E1E21, 0x33FFFFFF, 12, a));
        pic.setClipToOutline(true);
        TextView letter = CigramUI.label(a, e.name.length() > 0 ? e.name.substring(0, 1) : "؟", 22f, 0x66FFFFFF, true);
        letter.setGravity(Gravity.CENTER);
        pic.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pic.addView(img, new FrameLayout.LayoutParams(-1, -1));
        Drawable d = originDrawable(e.origin == null ? null : e.origin.get());
        if (d != null) {
            img.setImageDrawable(d);
            letter.setVisibility(View.GONE);
        } else if (e.image.length() > 0) {
            CigramChannels.loadLogo(e.image, img, letter);
        }
        tile.addView(pic, new LinearLayout.LayoutParams(dp(a, 128), dp(a, 72)));
        TextView name = CigramUI.label(a, e.name, 12f, Color.WHITE, false);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, dp(a, 6), 0, 0);
        tile.addView(name, new LinearLayout.LayoutParams(dp(a, 128), -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(a, 6);
        lp.rightMargin = dp(a, 6);
        tile.setLayoutParams(lp);
        tile.setClickable(true);
        tile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openDrawer(s, false);
                if (i != s.index) switchTo(s, i);
            }
        });
        return tile;
    }

    private static void highlightDrawer(S s) {
        if (s.drawerRow == null) return;
        for (int k = 0; k < s.drawerRow.getChildCount(); k++) {
            View tile = s.drawerRow.getChildAt(k);
            Object tag = tile.getTag();
            boolean on = tag instanceof Integer && ((Integer) tag) == s.index;
            if (tile instanceof LinearLayout && ((LinearLayout) tile).getChildCount() > 0) {
                View pic = ((LinearLayout) tile).getChildAt(0);
                pic.setBackground(on ? CigramUI.round(0xFF1E1E21, RED, 12, s.a)
                        : CigramUI.round(0xFF1E1E21, 0x33FFFFFF, 12, s.a));
                if (on) {
                    GradientDrawable g = (GradientDrawable) pic.getBackground();
                    g.setStroke(dp(s.a, 2), RED);
                }
            }
            tile.setAlpha(on ? 1f : 0.85f);
        }
    }

    private static void openDrawer(final S s, boolean open) {
        if (s.drawer == null || s.drawerOpen == open) return;
        if (open && (s.locked || s.list.size() < 2)) return;
        s.drawerOpen = open;
        if (open) {
            showControls(s, false);
            s.drawer.setVisibility(View.VISIBLE);
            s.drawer.setAlpha(0f);
            s.drawer.setTranslationY(dp(s.a, 40));
            s.drawer.animate().alpha(1f).translationY(0f).setDuration(200L).start();
            highlightDrawer(s);
            // bring the current channel into view
            s.drawerScroll.post(new Runnable() {
                @Override public void run() {
                    for (int k = 0; k < s.drawerRow.getChildCount(); k++) {
                        View t = s.drawerRow.getChildAt(k);
                        if (Integer.valueOf(s.index).equals(t.getTag())) {
                            int x = t.getLeft() - (s.drawerScroll.getWidth() - t.getWidth()) / 2;
                            s.drawerScroll.scrollTo(Math.max(0, x), 0);
                            break;
                        }
                    }
                }
            });
        } else {
            s.drawer.animate().alpha(0f).translationY(dp(s.a, 40)).setDuration(180L).withEndAction(new Runnable() {
                @Override public void run() { if (!s.drawerOpen) s.drawer.setVisibility(View.GONE); }
            }).start();
        }
        updateBack(s);
    }

    // ================================================================== status layers

    private static void buildStatus(final S s) {
        final Activity a = s.a;
        LinearLayout loading = new LinearLayout(a);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER_HORIZONTAL);
        s.spinner = new ProgressBar(a);
        s.spinner.setIndeterminate(true);
        try {
            s.spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        } catch (Throwable ignored) { }
        loading.addView(s.spinner, new LinearLayout.LayoutParams(dp(a, 44), dp(a, 44)));
        s.spinnerText = CigramUI.label(a, "", 13f, 0xDDFFFFFF, false);
        s.spinnerText.setGravity(Gravity.CENTER);
        s.spinnerText.setPadding(0, dp(a, 8), 0, 0);
        loading.addView(s.spinnerText, new LinearLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        llp.topMargin = dp(a, 110);
        s.root.addView(loading, llp);
        s.spinner.setTag(loading);

        s.errorBox = new LinearLayout(a);
        s.errorBox.setOrientation(LinearLayout.VERTICAL);
        s.errorBox.setGravity(Gravity.CENTER_HORIZONTAL);
        s.errorBox.setBackground(CigramUI.round(PANEL, 0x22FFFFFF, 20, a));
        s.errorBox.setPadding(dp(a, 26), dp(a, 20), dp(a, 26), dp(a, 18));
        s.errorBox.setClickable(true);
        s.errorText = CigramUI.label(a, "", 16f, Color.WHITE, true);
        s.errorText.setGravity(Gravity.CENTER);
        s.errorBox.addView(s.errorText, new LinearLayout.LayoutParams(-2, -2));
        TextView hint = CigramUI.label(a, "تأكد من الإنترنت أو جرّب قناة أخرى", 12f, MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(a, 6), 0, dp(a, 14));
        s.errorBox.addView(hint, new LinearLayout.LayoutParams(-2, -2));
        TextView again = CigramUI.label(a, "إعادة المحاولة", 14f, Color.WHITE, true);
        again.setGravity(Gravity.CENTER);
        again.setBackground(CigramUI.round(RED, 18, a));
        again.setPadding(dp(a, 26), dp(a, 9), dp(a, 26), dp(a, 9));
        again.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                Entry e = current(s);
                play(s, e == null ? s.url : urlOf(e));
            }
        });
        s.errorBox.addView(again, new LinearLayout.LayoutParams(-2, -2));
        s.errorBox.setVisibility(View.GONE);
        s.root.addView(s.errorBox, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        s.bubble = CigramUI.label(a, "", 14f, Color.WHITE, true);
        s.bubble.setGravity(Gravity.CENTER);
        s.bubble.setBackground(CigramUI.round(0xCC000000, 16, a));
        s.bubble.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 8));
        s.bubble.setAlpha(0f);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        blp.topMargin = dp(a, 76);
        s.root.addView(s.bubble, blp);

        // v10.35: no chip behind it, the icon and the words float with a shadow like the other buttons
        s.unlockChip = CigramUI.label(a, "اضغط لفتح القفل", 13f, Color.WHITE, true);
        s.unlockChip.setGravity(Gravity.CENTER);
        s.unlockChip.setPadding(dp(a, 18), dp(a, 10), dp(a, 18), dp(a, 10));
        shadow(s.unlockChip);
        Ic lockIc = new Ic(a, Ic.UNLOCK);
        lockIc.setBounds(0, 0, dp(a, 30), dp(a, 30));
        s.unlockChip.setCompoundDrawables(null, lockIc, null, null);
        s.unlockChip.setCompoundDrawablePadding(dp(a, 6));
        pressFx(s.unlockChip);
        s.unlockChip.setVisibility(View.GONE);
        s.unlockChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setLocked(s, false); }
        });
        FrameLayout.LayoutParams ulp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        ulp.bottomMargin = dp(a, 28);
        s.root.addView(s.unlockChip, ulp);
    }

    private static void setSpinner(S s, boolean on) {
        if (s.spinner == null) return;
        View box = (View) s.spinner.getTag();
        if (box == null) box = s.spinner;
        boolean show = on && !s.casting;
        box.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && s.spinnerText != null && s.retries == 0) {
            Entry e = current(s);
            s.spinnerText.setText(e == null ? "جارٍ الاتصال…" : "جارٍ الاتصال بـ " + e.name + "…");
        }
    }

    private static void showError(S s, String text) {
        if (s.errorBox == null) return;
        setSpinner(s, false);
        s.errorText.setText(text);
        s.errorBox.setVisibility(View.VISIBLE);
        showControls(s, true);
    }

    private static void hideError(S s) {
        if (s.errorBox != null) s.errorBox.setVisibility(View.GONE);
    }

    private static void bubble(S s, String text) {
        if (s.bubble == null) return;
        s.bubble.setText(text);
        s.bubble.animate().cancel();
        s.bubble.setAlpha(1f);
        UI.removeCallbacks(s.hideBubble);
        UI.postDelayed(s.hideBubble, 1300L);
    }

    /** Every 250 ms: what a Player.Listener would report (state, playing, error). */
    private static void onWatch(S s) {
        if (s.released) return;
        try {
            PlaybackException error = s.player.getPlayerError();
            if (error != null && error != s.lastError) {
                s.lastError = error;
                Log.w(TAG, "playback error " + error.getErrorCodeName(), error);
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    s.player.seekToDefaultPosition();
                    s.player.prepare();
                } else if (CigramStreams.isDrmError(error)) {
                    // Encrypted (Widevine / PlayReady) stream: it needs the owner's license, retrying cannot help.
                    setSpinner(s, false);
                    showError(s, "هذه القناة مشفّرة (DRM) ولا تعمل إلا داخل تطبيق مالكها");
                } else if (CigramStreams.isTypeError(error) && nextType(s)) {
                    // handled: the same link is being opened as another type
                } else {
                    retry(s, true);
                }
            } else if (error == null) {
                s.lastError = null;
            }
            int state = s.player.getPlaybackState();
            if (state != s.lastState) {
                s.lastState = state;
                if (state == Player.STATE_BUFFERING) {
                    setSpinner(s, true);
                } else if (state == Player.STATE_READY) {
                    setSpinner(s, false);
                    s.retries = 0;
                    hideError(s);
                } else if (state == Player.STATE_ENDED) {
                    retry(s, false);      // a live stream "ended": reconnect
                } else if (error == null) {
                    setSpinner(s, false);
                }
            }
            boolean playing = s.player.isPlaying();
            if (playing != s.lastPlaying) {
                s.lastPlaying = playing;
                updatePlayIcon(s);
                if (playing) scheduleHide(s);
            }
        } catch (Throwable error) {
            Log.w(TAG, "watch failed", error);
        }
        UI.postDelayed(s.watch, 250L);
    }

    /** Every second: live-edge pill, picture-in-picture state, sleep timer. */
    private static void onTick(S s) {
        if (s.released) return;
        try {
            boolean behind = false;
            if (!s.casting && s.player.isCurrentMediaItemLive()) {
                long off = s.player.getCurrentLiveOffset();
                behind = off != C.TIME_UNSET && off > 20000L;
            }
            if (!s.player.getPlayWhenReady() && !s.casting) behind = true;
            setLiveMode(s, behind);
            if (s.liveLine != null) {
                float frac = 1f;
                if (behind) {
                    long dur = s.player.getDuration();
                    long pos = s.player.getCurrentPosition();
                    frac = dur != C.TIME_UNSET && dur > 0 ? pos / (float) dur : 0.85f;
                }
                s.liveLine.set(!behind, frac);
            }
            updateQuality(s);
            if (inPip(s.a)) {
                if (s.controls.getVisibility() == View.VISIBLE) {
                    s.controlsShown = false;
                    s.controls.setVisibility(View.GONE);
                }
                if (s.drawerOpen) openDrawer(s, false);
            }
            if (s.sleepAt > 0 && System.currentTimeMillis() >= s.sleepAt) {
                s.sleepAt = 0;
                s.player.pause();
                s.a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                showError(s, "توقف البث (مؤقت النوم)");
            }
        } catch (Throwable ignored) { }
        UI.postDelayed(s.tick, 1000L);
    }

    // ================================================================== settings

    private static void openSettings(final S s) {
        final Activity a = s.a;
        try {
            UI.removeCallbacks(s.hide);
            final CigramPlayerSheet sheet = new CigramPlayerSheet(a, s.root, "إعدادات البث");
            Format f = s.player.getVideoFormat();
            sheet.item("جودة الفيديو", f != null && f.height > 0 ? f.height + "p" : "تلقائي", new CigramPlayerSheet.Source() {
                final ArrayList<int[]> tracks = new ArrayList<int[]>();
                MappingTrackSelector.MappedTrackInfo mapped;

                @Override public CigramPlayerSheet.Options load() {
                    tracks.clear();
                    mapped = s.selector.getCurrentMappedTrackInfo();
                    if (mapped == null) return CigramPlayerSheet.Options.message("لم تُقرأ جودات القناة بعد");
                    ArrayList<String> labels = new ArrayList<String>();
                    labels.add("تلقائي");
                    java.util.HashSet<Integer> seen = new java.util.HashSet<Integer>();
                    for (int r = 0; r < mapped.getRendererCount(); r++) {
                        if (mapped.getRendererType(r) != C.TRACK_TYPE_VIDEO) continue;
                        TrackGroupArray groups = mapped.getTrackGroups(r);
                        for (int g = 0; g < groups.length; g++) {
                            for (int k = 0; k < groups.get(g).length; k++) {
                                if (mapped.getTrackSupport(r, g, k) != C.FORMAT_HANDLED) continue;
                                int h = groups.get(g).getFormat(k).height;
                                if (h <= 0 || seen.contains(h)) continue;
                                seen.add(h);
                                tracks.add(new int[]{r, g, k, h});
                            }
                        }
                    }
                    java.util.Collections.sort(tracks, new java.util.Comparator<int[]>() {
                        @Override public int compare(int[] x, int[] y) { return y[3] - x[3]; }
                    });
                    for (int[] t : tracks) labels.add(t[3] + "p");
                    if (tracks.size() < 2) return CigramPlayerSheet.Options.message("هذه القناة تبث بجودة واحدة");
                    int sel = 0;
                    int chosen = s.prefs.getInt("quality_h", 0);
                    for (int i = 0; i < tracks.size(); i++) if (tracks.get(i)[3] == chosen) sel = i + 1;
                    return new CigramPlayerSheet.Options(labels.toArray(new String[0]), sel);
                }

                @Override public String pick(int which) {
                    try {
                        DefaultTrackSelector.Parameters.Builder b = s.selector.buildUponParameters();
                        for (int r = 0; r < mapped.getRendererCount(); r++) {
                            if (mapped.getRendererType(r) == C.TRACK_TYPE_VIDEO) b.clearSelectionOverrides(r);
                        }
                        if (which <= 0) {
                            s.selector.setParameters(b);
                            s.prefs.edit().putInt("quality_h", 0).apply();
                            return "تلقائي";
                        }
                        int[] t = tracks.get(which - 1);
                        b.setSelectionOverride(t[0], mapped.getTrackGroups(t[0]),
                                new DefaultTrackSelector.SelectionOverride(t[1], t[2]));
                        s.selector.setParameters(b);
                        s.prefs.edit().putInt("quality_h", t[3]).apply();
                        return t[3] + "p";
                    } catch (Throwable error) {
                        Log.w(TAG, "quality failed", error);
                        return null;
                    }
                }
            });
            sheet.item("أبعاد الشاشة", ASPECT_LABELS[s.aspect], new CigramPlayerSheet.Source() {
                @Override public CigramPlayerSheet.Options load() {
                    return new CigramPlayerSheet.Options(ASPECT_LABELS, s.aspect);
                }
                @Override public String pick(int which) {
                    setAspect(s, which, false);
                    return ASPECT_LABELS[s.aspect];
                }
            });
            sheet.item("اتجاه الشاشة", ORIENT_LABELS[s.orient], new CigramPlayerSheet.Source() {
                @Override public CigramPlayerSheet.Options load() {
                    return new CigramPlayerSheet.Options(ORIENT_LABELS, s.orient);
                }
                @Override public String pick(int which) {
                    setOrientation(s, which);
                    return ORIENT_LABELS[s.orient];
                }
            });
            sheet.item("مؤقت النوم", sleepLabel(s), new CigramPlayerSheet.Source() {
                @Override public CigramPlayerSheet.Options load() {
                    return new CigramPlayerSheet.Options(SLEEP_LABELS, sleepIndex(s));
                }
                @Override public String pick(int which) {
                    int min = SLEEP_MIN[Math.max(0, Math.min(SLEEP_MIN.length - 1, which))];
                    s.sleepAt = min == 0 ? 0 : System.currentTimeMillis() + min * 60000L;
                    s.prefs.edit().putInt("sleep_min", min).apply();
                    return SLEEP_LABELS[which];
                }
            });
            sheet.setOnDismiss(new Runnable() {
                @Override public void run() { immersive(a); scheduleHide(s); }
            });
            sheet.show();
        } catch (Throwable error) {
            Log.w(TAG, "settings failed", error);
        }
    }

    private static int sleepIndex(S s) {
        if (s.sleepAt <= 0) return 0;
        int min = s.prefs.getInt("sleep_min", 0);
        for (int i = 0; i < SLEEP_MIN.length; i++) if (SLEEP_MIN[i] == min) return i;
        return 0;
    }

    private static String sleepLabel(S s) {
        if (s.sleepAt <= 0) return SLEEP_LABELS[0];
        long left = Math.max(0L, s.sleepAt - System.currentTimeMillis()) / 60000L + 1;
        return "بعد " + left + " د";
    }

    // ================================================================== picture in picture

    private static boolean pipSupported(Activity a) {
        try {
            return Build.VERSION.SDK_INT >= 26
                    && a.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean inPip(Activity a) {
        try {
            return Build.VERSION.SDK_INT >= 24 && a != null && a.isInPictureInPictureMode();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void enterPip(S s) {
        if (Build.VERSION.SDK_INT < 26) return;
        if (!CigramPlusUI.requirePlus(s.a, "الصورة داخل الصورة")) return;     // Cigram Plus service
        try {
            Format f = s.player.getVideoFormat();
            int w = f != null && f.width > 0 ? f.width : 16;
            int h = f != null && f.height > 0 ? f.height : 9;
            float r = (float) w / h;
            if (r > 2.39f) { w = 239; h = 100; }
            if (r < 0.42f) { w = 42; h = 100; }
            openDrawer(s, false);
            showControls(s, false);
            android.app.PictureInPictureParams p = new android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(new android.util.Rational(w, h)).build();
            s.a.enterPictureInPictureMode(p);
        } catch (Throwable error) {
            Log.w(TAG, "pip failed", error);
            Toast.makeText(s.a, "النافذة العائمة غير مدعومة على هذا الجهاز", Toast.LENGTH_SHORT).show();
        }
    }

    // ================================================================== cast to TV

    private static void setupCast(final S s) {
        final Context app = s.a.getApplicationContext();
        try {
            CigramDlna.start(app);
            s.dlnaStarted = true;
        } catch (Throwable error) {
            Log.w(TAG, "dlna start failed", error);
        }
        try {
            s.cast = CastContext.getSharedInstance(app);
        } catch (Throwable error) {
            s.cast = null;   // no Google Play services: DLNA and mirroring still work
        }
        try {
            s.router = MediaRouter.getInstance(app);
            s.routerCallback = new MediaRouter.Callback() { };
            s.router.addCallback(selector(s), s.routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
        } catch (Throwable error) {
            s.router = null;
        }
        if (s.cast == null) return;
        s.castListener = new SessionManagerListener<CastSession>() {
            @Override public void onSessionStarting(CastSession session) { }
            @Override public void onSessionStarted(CastSession session, String id) {
                if (s.wantCast) castLoad(s);
            }
            @Override public void onSessionStartFailed(CastSession session, int error) {
                s.wantCast = false;
                if (!s.released) Toast.makeText(app, "تعذر الاتصال بالتلفاز", Toast.LENGTH_SHORT).show();
            }
            @Override public void onSessionEnding(CastSession session) { }
            @Override public void onSessionEnded(CastSession session, int error) {
                if (s.casting && s.dlna == null) castStopped(s, true);
            }
            @Override public void onSessionResuming(CastSession session, String id) { }
            @Override public void onSessionResumed(CastSession session, boolean wasSuspended) {
                if (s.wantCast) castLoad(s);
            }
            @Override public void onSessionResumeFailed(CastSession session, int error) { }
            @Override public void onSessionSuspended(CastSession session, int reason) { }
        };
        try {
            s.cast.getSessionManager().addSessionManagerListener(s.castListener, CastSession.class);
        } catch (Throwable error) {
            Log.w(TAG, "cast listener failed", error);
        }
    }

    private static MediaRouteSelector selector(S s) {
        MediaRouteSelector merged = null;
        try { if (s.cast != null) merged = s.cast.getMergedSelector(); } catch (Throwable ignored) { }
        if (merged != null) return merged;
        return new MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(
                        CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .build();
    }

    private static CastSession session(S s) {
        try {
            CastSession cs = s.cast == null ? null : s.cast.getSessionManager().getCurrentCastSession();
            return cs != null && cs.isConnected() ? cs : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void onCastButton(final S s) {
        UI.removeCallbacks(s.hide);
        if (s.casting) {
            castOptions(s);
            return;
        }
        if (session(s) != null) {
            // already connected to a Chromecast (e.g. from a movie): send this channel
            s.wantCast = true;
            castLoad(s);
            return;
        }
        showChooser(s);
    }

    private static void showChooser(final S s) {
        final Activity a = s.a;
        try {
            final CigramUI.Sheet sheet = CigramUI.sheet(a);
            LinearLayout list = new LinearLayout(a);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            int count = 0;
            if (s.router != null) {
                try {
                    MediaRouteSelector sel = selector(s);
                    for (final MediaRouter.RouteInfo r : s.router.getRoutes()) {
                        if (r.isDefault() || !r.isEnabled() || !r.matchesSelector(sel)) continue;
                        list.addView(deviceRow(a, String.valueOf(r.getName()), "Chromecast", new Runnable() {
                            @Override public void run() {
                                sheet.dismiss();
                                s.wantCast = true;
                                s.castName = String.valueOf(r.getName());
                                bubble(s, "جارٍ الاتصال بـ " + s.castName + "…");
                                try { s.router.selectRoute(r); } catch (Throwable error) { Log.w(TAG, "select route", error); }
                            }
                        }));
                        count++;
                    }
                } catch (Throwable error) {
                    Log.w(TAG, "routes failed", error);
                }
            }
            for (final CigramDlna.Device d : CigramDlna.devices()) {
                list.addView(deviceRow(a, d.name, "DLNA", new Runnable() {
                    @Override public void run() {
                        sheet.dismiss();
                        s.dlna = d;
                        s.castName = d.name;
                        castLoad(s);
                    }
                }));
                count++;
            }
            list.addView(deviceRow(a, "عرض الشاشة على التلفاز", "Miracast", new Runnable() {
                @Override public void run() {
                    sheet.dismiss();
                    CigramCastDetails.openMirroring(a);
                }
            }));
            list.addView(deviceRow(a, "إعادة البحث عن التلفاز", "", new Runnable() {
                @Override public void run() {
                    sheet.dismiss();
                    try { CigramDlna.rescan(a); } catch (Throwable ignored) { }
                    Toast.makeText(a, "جارٍ البحث… افتح القائمة مرة ثانية بعد 5 ثوانٍ", Toast.LENGTH_SHORT).show();
                }
            }));
            sheet.icon(CigramUI.ICON_INFO, RED)
                    .title("بث القناة إلى التلفاز")
                    .message(count == 0
                            ? "لم يظهر أي تلفاز بعد. تأكد أن الهاتف والتلفاز على نفس شبكة Wi-Fi، أو استخدم «عرض الشاشة على التلفاز»."
                            : "اختر التلفاز (نفس شبكة Wi-Fi)")
                    .secondary("إلغاء", new CigramUI.Click() {
                        @Override public boolean onClick(CigramUI.Sheet sh) { return false; }
                    });
            sheet.view(list);
            sheet.show();
        } catch (Throwable error) {
            Log.w(TAG, "chooser failed", error);
        }
    }

    private static View deviceRow(Activity a, String name, String kind, final Runnable onClick) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(CigramUI.round(0x22FFFFFF, 14f, a));
        row.setPadding(dp(a, 16), dp(a, 13), dp(a, 16), dp(a, 13));
        row.setClickable(true);
        TextView k = CigramUI.label(a, kind, 11f, CigramUI.MUTED, false);
        row.addView(k, new LinearLayout.LayoutParams(-2, -2));
        TextView n = CigramUI.label(a, name, 16f, CigramUI.TEXT, false);
        n.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView ic = new ImageView(a);
        ic.setImageDrawable(new Ic(a, Ic.CAST));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(a, 22), dp(a, 22));
        il.leftMargin = dp(a, 10);
        row.addView(ic, il);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onClick.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(a, 8);
        row.setLayoutParams(lp);
        return row;
    }

    private static String mimeOf(String url) {
        String m = CigramStreams.mimeOf(url);
        if (m == null || "application/x-mpegURL".equals(m)) return "application/x-mpegURL";   // most live channels are HLS
        return m;
    }

    /** Sends the current channel to the connected TV (Chromecast or DLNA). */
    private static void castLoad(final S s) {
        final Entry e = current(s);
        final String url = CigramStreams.cleanUrl(e == null ? s.url : urlOf(e));
        if (url.length() == 0) return;
        final String name = e == null ? "بث مباشر" : e.name;
        final Context app = s.a.getApplicationContext();
        if (s.dlna != null) {
            final CigramDlna.Device d = s.dlna;
            bubble(s, "جارٍ التشغيل على التلفاز…");
            new Thread(new Runnable() {
                @Override public void run() {
                    boolean ok = false;
                    try {
                        ok = CigramDlna.setUri(d, url, name, mimeOf(url)) && CigramDlna.play(d);
                    } catch (Throwable error) {
                        Log.w(TAG, "dlna live failed", error);
                    }
                    final boolean done = ok;
                    UI.post(new Runnable() {
                        @Override public void run() {
                            if (s.released) return;
                            if (done) {
                                CigramCastKeepAlive.start(app, name, true);
                                castStarted(s);
                            } else {
                                s.dlna = null;
                                Toast.makeText(app, "التلفاز رفض تشغيل هذه القناة، جرّب «عرض الشاشة»", Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                }
            }, "cigram-live-dlna").start();
            return;
        }
        CastSession cs = session(s);
        if (cs == null) return;
        try {
            RemoteMediaClient client = cs.getRemoteMediaClient();
            if (client == null) return;
            try { CigramCastDetails.forgetPlayback(); } catch (Throwable ignored) { }
            MediaMetadata md = new MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC);
            md.putString(MediaMetadata.KEY_TITLE, name);
            md.putString(MediaMetadata.KEY_SUBTITLE, "بث مباشر");
            if (e != null && e.image.startsWith("http")) md.addImage(new WebImage(Uri.parse(e.image)));
            JSONObject cd = new JSONObject();
            cd.put("cigram_role", "live");
            MediaInfo info = new MediaInfo.Builder(url)
                    .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
                    .setContentType(mimeOf(url))
                    .setMetadata(md)
                    .setCustomData(cd)
                    .build();
            MediaQueueItem item = new MediaQueueItem.Builder(info).setAutoplay(true).build();
            client.queueLoad(new MediaQueueItem[]{item}, 0, MediaStatus.REPEAT_MODE_REPEAT_OFF, null);
            try {
                if (cs.getCastDevice() != null) s.castName = cs.getCastDevice().getFriendlyName();
            } catch (Throwable ignored) { }
            CigramCastKeepAlive.start(app, name, false);
            castStarted(s);
        } catch (Throwable error) {
            Log.w(TAG, "chromecast live failed", error);
            Toast.makeText(app, "تعذر التشغيل على التلفاز", Toast.LENGTH_SHORT).show();
        }
    }

    private static void castStarted(S s) {
        s.wantCast = false;
        s.casting = true;
        try { s.player.pause(); } catch (Throwable ignored) { }
        setSpinner(s, false);
        hideError(s);
        if (s.castGlyph != null) s.castGlyph.setActive(true);
        if (s.castDevice != null) s.castDevice.setText(s.castName.length() > 0 ? s.castName : "التلفاز");
        Entry e = current(s);
        if (s.castChannel != null) s.castChannel.setText(e == null ? "" : e.name);
        if (s.castBox != null) s.castBox.setVisibility(View.VISIBLE);
        if (s.centerRow != null) s.centerRow.setVisibility(View.INVISIBLE);
        CigramAnalytics.track(s.a, "live_cast", "kind", s.dlna != null ? "dlna" : "chromecast");
        showControls(s, true);
    }

    /** The TV stopped (or the user ended it): continue on the phone when asked. */
    private static void castStopped(S s, boolean resumeHere) {
        s.casting = false;
        s.wantCast = false;
        if (s.castGlyph != null) s.castGlyph.setActive(false);
        if (s.castBox != null) s.castBox.setVisibility(View.GONE);
        if (s.centerRow != null) s.centerRow.setVisibility(View.VISIBLE);
        if (s.dlna == null && session(s) == null) {
            try { CigramCastKeepAlive.stop(s.a.getApplicationContext()); } catch (Throwable ignored) { }
        }
        if (resumeHere && !s.released) {
            Entry e = current(s);
            play(s, e == null ? s.url : urlOf(e));
        }
    }

    private static void stopCasting(final S s) {
        final CigramDlna.Device d = s.dlna;
        s.dlna = null;
        if (d != null) {
            new Thread(new Runnable() {
                @Override public void run() { try { CigramDlna.stop(d); } catch (Throwable ignored) { } }
            }, "cigram-live-dlna-stop").start();
            castStopped(s, true);
            return;
        }
        try {
            if (s.cast != null) s.cast.getSessionManager().endCurrentSession(true);
        } catch (Throwable error) {
            Log.w(TAG, "end session failed", error);
        }
        castStopped(s, true);
    }

    private static void castOptions(final S s) {
        try {
            CigramUI.sheet(s.a)
                    .icon(CigramUI.ICON_INFO, RED)
                    .title("يُعرض على " + (s.castName.length() > 0 ? s.castName : "التلفاز"))
                    .message("القناة تعمل الآن على التلفاز. يمكنك تغيير القناة من هنا.")
                    .danger("إيقاف البث على التلفاز", new CigramUI.Click() {
                        @Override public boolean onClick(CigramUI.Sheet sh) { stopCasting(s); return false; }
                    })
                    .secondary("إغلاق", new CigramUI.Click() {
                        @Override public boolean onClick(CigramUI.Sheet sh) { return false; }
                    })
                    .show();
        } catch (Throwable error) {
            Log.w(TAG, "cast options failed", error);
        }
    }

    private static void buildCastBox(final S s) {
        final Activity a = s.a;
        s.castBox = new LinearLayout(a);
        s.castBox.setOrientation(LinearLayout.VERTICAL);
        s.castBox.setGravity(Gravity.CENTER);
        s.castBox.setBackgroundColor(0xF2050E14);
        s.castBox.setClickable(false);
        ImageView tv = new ImageView(a);
        Ic big = new Ic(a, Ic.CAST);
        big.setActive(true);
        tv.setImageDrawable(big);
        s.castBox.addView(tv, new LinearLayout.LayoutParams(dp(a, 64), dp(a, 64)));
        TextView on = CigramUI.label(a, "يُعرض الآن على التلفاز", 18f, Color.WHITE, true);
        on.setTypeface(CigramStage1InitProvider.cigramFont(a), android.graphics.Typeface.BOLD);
        on.setGravity(Gravity.CENTER);
        on.setPadding(0, dp(a, 14), 0, dp(a, 4));
        s.castBox.addView(on, new LinearLayout.LayoutParams(-2, -2));
        s.castChannel = CigramUI.label(a, "", 15f, 0xFFE0E6E9, false);
        s.castChannel.setGravity(Gravity.CENTER);
        s.castBox.addView(s.castChannel, new LinearLayout.LayoutParams(-2, -2));
        s.castDevice = CigramUI.label(a, "", 12f, MUTED, false);
        s.castDevice.setGravity(Gravity.CENTER);
        s.castDevice.setPadding(0, dp(a, 2), 0, dp(a, 18));
        s.castBox.addView(s.castDevice, new LinearLayout.LayoutParams(-2, -2));
        TextView stop = CigramUI.label(a, "إيقاف البث ومتابعة على الهاتف", 14f, Color.WHITE, true);
        stop.setGravity(Gravity.CENTER);
        stop.setBackground(CigramUI.round(RED, 20, a));
        stop.setPadding(dp(a, 24), dp(a, 10), dp(a, 24), dp(a, 10));
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { stopCasting(s); }
        });
        s.castBox.addView(stop, new LinearLayout.LayoutParams(-2, -2));
        s.castBox.setVisibility(View.GONE);
        // above the touch layer, under the controls (channel buttons keep working while casting)
        int at = s.controls == null ? s.root.getChildCount() : s.root.indexOfChild(s.controls);
        s.root.addView(s.castBox, Math.max(0, at), new FrameLayout.LayoutParams(-1, -1));
    }

    // ================================================================== back key

    private static void setupBack(final S s) {
        try {
            if (!(s.a instanceof androidx.activity.ComponentActivity)) return;
            androidx.activity.OnBackPressedCallback cb = new androidx.activity.OnBackPressedCallback(false) {
                @Override public void handleOnBackPressed() {
                    if (s.drawerOpen) openDrawer(s, false);
                    else if (s.locked) showUnlock(s);
                    updateBack(s);
                }
            };
            ((androidx.activity.ComponentActivity) s.a).getOnBackPressedDispatcher().addCallback(cb);
            s.backCallback = cb;
        } catch (Throwable error) {
            Log.w(TAG, "back setup failed", error);
        }
    }

    private static void updateBack(S s) {
        try {
            if (s.backCallback instanceof androidx.activity.OnBackPressedCallback) {
                ((androidx.activity.OnBackPressedCallback) s.backCallback).setEnabled(s.drawerOpen || s.locked);
            }
        } catch (Throwable ignored) { }
    }

    // ================================================================== lifecycle

    private static void registerLifecycle(Activity a) {
        if (lifecycleRegistered) return;
        try {
            ((Application) a.getApplicationContext()).registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        @Override public void onActivityCreated(Activity x, Bundle b) { }
                        @Override public void onActivityStarted(Activity x) {
                            S s = state(x);
                            if (s == null || s.released || s.casting) return;
                            if (s.resumeOnStart) {
                                s.resumeOnStart = false;
                                try {
                                    s.player.seekToDefaultPosition();
                                    s.player.prepare();
                                    s.player.play();
                                } catch (Throwable ignored) { }
                            }
                        }
                        @Override public void onActivityResumed(Activity x) {
                            S s = state(x);
                            if (s != null) immersive(x);
                        }
                        @Override public void onActivityPaused(Activity x) { }
                        @Override public void onActivityStopped(Activity x) {
                            S s = state(x);
                            if (s == null || s.released || s.casting) return;
                            try {
                                s.resumeOnStart = s.player.getPlayWhenReady();
                                s.player.pause();
                            } catch (Throwable ignored) { }
                        }
                        @Override public void onActivitySaveInstanceState(Activity x, Bundle b) { }
                        @Override public void onActivityDestroyed(Activity x) {
                            S s;
                            synchronized (STATES) { s = STATES.remove(x); }
                            if (s == null) return;
                            boolean recreating = false;
                            try { recreating = x.isChangingConfigurations(); } catch (Throwable ignored) { }
                            if (recreating) {
                                synchronized (QUEUE) {
                                    QUEUE.clear();
                                    QUEUE.addAll(s.list);
                                    pendingIndex = s.index;
                                    keepIndex = true;
                                }
                            }
                            release(s);
                        }
                    });
            lifecycleRegistered = true;
        } catch (Throwable error) {
            Log.w(TAG, "lifecycle failed", error);
        }
    }

    private static void release(S s) {
        if (s.released) return;
        s.released = true;
        UI.removeCallbacks(s.tick);
        UI.removeCallbacks(s.watch);
        UI.removeCallbacks(s.hide);
        UI.removeCallbacks(s.hideBubble);
        UI.removeCallbacks(s.hideUnlock);
        try { if (s.view != null) s.view.setPlayer(null); } catch (Throwable ignored) { }
        try { if (s.player != null) s.player.release(); } catch (Throwable ignored) { }
        try {
            if (s.cast != null && s.castListener != null) {
                s.cast.getSessionManager().removeSessionManagerListener(s.castListener, CastSession.class);
            }
        } catch (Throwable ignored) { }
        try { if (s.router != null && s.routerCallback != null) s.router.removeCallback(s.routerCallback); } catch (Throwable ignored) { }
        if (s.dlnaStarted) {
            try { CigramDlna.stop(); } catch (Throwable ignored) { }
        }
    }

    // ================================================================== small views (v10.35: no backgrounds)

    private static LinearLayout.LayoutParams btnLp(Context c, int leftMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44));
        lp.leftMargin = dp(c, leftMarginDp);
        return lp;
    }

    /** A bare icon with a 44dp touch area: no circle, the glyph carries its own shadow. */
    private static View iconButton(Context c, Drawable icon, String label, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(c);
        b.setContentDescription(label);
        b.setClickable(true);
        b.setFocusable(true);
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(icon);
        b.addView(iv, new FrameLayout.LayoutParams(dp(c, 24), dp(c, 24), Gravity.CENTER));
        b.setOnClickListener(click);
        pressFx(b);
        return b;
    }

    /**
     * Previous / next channel: the arrow next to the play button and, on the outer side,
     * a small caption with the neighbour channel's name under it.
     */
    private static View zapButton(Context c, Ic icon, String caption, TextView name, boolean leftSide,
                                  View.OnClickListener click) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        b.setGravity((leftSide ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        b.setPadding(dp(c, 6), dp(c, 10), dp(c, 6), dp(c, 10));
        b.setContentDescription(caption);
        b.setClickable(true);
        b.setFocusable(true);

        int side = leftSide ? Gravity.RIGHT : Gravity.LEFT;
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(side);
        TextView cap = CigramUI.label(c, caption, 11f, SOFT, false);
        cap.setGravity(side);
        cap.setSingleLine(true);
        shadow(cap);
        texts.addView(cap, new LinearLayout.LayoutParams(-2, -2));
        name.setGravity(side);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setMaxWidth(dp(c, 128));
        shadow(name);
        texts.addView(name, new LinearLayout.LayoutParams(-2, -2));

        ImageView iv = new ImageView(c);
        iv.setImageDrawable(icon);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(c, 32), dp(c, 32));
        if (leftSide) {
            ilp.leftMargin = dp(c, 12);
            b.addView(texts, new LinearLayout.LayoutParams(-2, -2));
            b.addView(iv, ilp);
        } else {
            ilp.rightMargin = dp(c, 12);
            b.addView(iv, ilp);
            b.addView(texts, new LinearLayout.LayoutParams(-2, -2));
        }
        b.setOnClickListener(click);
        pressFx(b);
        return b;
    }

    /** Icon + word, no pill: the icon on the right (RTL), the word after it. */
    private static View textButton(Context c, Ic icon, String text, View.OnClickListener click) {
        TextView t = CigramUI.label(c, text, 13f, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 8), dp(c, 8), dp(c, 6), dp(c, 8));
        icon.setBounds(0, 0, dp(c, 22), dp(c, 22));
        t.setCompoundDrawables(null, null, icon, null);
        t.setCompoundDrawablePadding(dp(c, 8));
        t.setContentDescription(text);
        shadow(t);
        t.setOnClickListener(click);
        pressFx(t);
        return t;
    }

    /** Soft text shadow: keeps the words readable on a bright picture without a background. */
    private static void shadow(TextView t) {
        t.setShadowLayer(dp(t.getContext(), 6), 0f, dp(t.getContext(), 1), 0xA6000000);
    }

    /** Press feedback for bare buttons: a small shrink and fade instead of a highlighted circle. */
    private static void pressFx(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                int act = e.getActionMasked();
                if (act == MotionEvent.ACTION_DOWN) {
                    view.animate().cancel();
                    view.animate().scaleX(0.9f).scaleY(0.9f).alpha(0.65f).setDuration(90L).start();
                } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                    view.animate().cancel();
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(160L).start();
                }
                return false;
            }
        });
    }

    private static String clean(String v) {
        return v == null ? "" : v.trim();
    }

    // ================================================================== live line

    /**
     * Not a seek bar: a thin line that shows how far the picture is from the live edge.
     * RTL: the live edge is on the left, a red pulsing dot while the channel is live.
     */
    static final class LiveLine extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float d;
        private boolean live = true;
        private float frac = 1f;

        LiveLine(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.FILL);
        }

        void set(boolean atLive, float f) {
            f = Math.max(0.02f, Math.min(1f, f));
            if (atLive == live && Math.abs(f - frac) < 0.002f) return;
            live = atLive;
            frac = f;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cy = h / 2f;
            float th = 3f * d;
            float dot = 5f * d;
            float left = 12f * d;          // room for the pulse ring on the live side
            float right = w - 2f * d;
            // shadow under the line
            p.setColor(0x40000000);
            r.set(left, cy - th / 2f - d, right, cy + th / 2f + d);
            c.drawRoundRect(r, th, th, p);
            // track
            p.setColor(0x3DFFFFFF);
            r.set(left, cy - th / 2f, right, cy + th / 2f);
            c.drawRoundRect(r, th / 2f, th / 2f, p);
            // what has been watched, from the right (oldest) to the picture now
            float f = live ? 1f : frac;
            float x = right - (right - left) * f;
            p.setColor(live ? RED : 0xE6FFFFFF);
            r.set(x, cy - th / 2f, right, cy + th / 2f);
            c.drawRoundRect(r, th / 2f, th / 2f, p);
            if (live) {
                float t = (SystemClock.uptimeMillis() % 1600L) / 1600f;
                p.setColor(RED);
                p.setAlpha(Math.round(120 * (1f - t)));
                c.drawCircle(x, cy, dot * (1f + 1.2f * t), p);
                p.setAlpha(255);
                c.drawCircle(x, cy, dot, p);
                p.setColor(Color.WHITE);
                c.drawCircle(x, cy, dot * 0.38f, p);
                if (isShown()) postInvalidateDelayed(40L);
            } else {
                // where the live edge is
                p.setColor(RED);
                c.drawCircle(left, cy, dot * 0.6f, p);
                p.setColor(0x59000000);
                c.drawCircle(x, cy + d * 0.6f, dot + d, p);
                p.setColor(Color.WHITE);
                c.drawCircle(x, cy, dot, p);
            }
        }
    }

    // ================================================================== icons

    /**
     * v10.35 line icons on a 24-unit grid: white, round caps and joins. Each glyph draws its own
     * soft shadow (two wider, transparent passes under it), so it reads on any picture without a
     * circle behind it — and it works with hardware acceleration on every Android version.
     */
    static final class Ic extends Drawable {
        static final int BACK = 1, CAST = 2, ASPECT = 3, ROTATE = 4, SETTINGS = 5, PIP = 6,
                PLAY = 7, PAUSE = 8, NEXT_LEFT = 9, PREV_RIGHT = 10, LOCK = 11, UNLOCK = 12, LIST = 13,
                DOT = 14, GO_LIVE = 15;

        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private int kind;
        private boolean active;
        private int alpha = 255;
        /** Added to every line width while a shadow pass is drawn. */
        private float extra;

        Ic(Context c, int kind) {
            this.kind = kind;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            fill.setStyle(Paint.Style.FILL);
            fill.setStrokeCap(Paint.Cap.ROUND);
            fill.setStrokeJoin(Paint.Join.ROUND);
        }

        void setKind(int k) {
            if (kind == k) return;
            kind = k;
            invalidateSelf();
        }

        void setActive(boolean on) {
            active = on;
            invalidateSelf();
        }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float u = Math.min(b.width(), b.height()) / 24f;
            if (u <= 0) return;
            c.save();
            c.translate(b.left + (b.width() - 24 * u) / 2f, b.top + (b.height() - 24 * u) / 2f);
            c.scale(u, u);
            c.save();
            c.translate(0f, 0.55f);
            pass(c, 0x1A000000, 3.6f);
            pass(c, 0x40000000, 1.7f);
            c.restore();
            pass(c, 0, 0f);
            c.restore();
        }

        /** shade == 0 draws the glyph itself, otherwise a shadow of that colour, every line wider by grow. */
        private void pass(Canvas c, int shade, float grow) {
            extra = grow;
            int col = shade != 0 ? shade : ((active && kind == CAST) || kind == DOT ? RED : Color.WHITE);
            int a = Math.round((col >>> 24) * alpha / 255f);
            col = (a << 24) | (col & 0x00FFFFFF);
            stroke.setColor(col);
            fill.setColor(col);
            path.reset();
            glyph(c);
        }

        private Paint line(float width) {
            stroke.setStrokeWidth(width + extra);
            return stroke;
        }

        /** A filled shape; `round` > 0 rounds its corners (drawn as fill + round-joined stroke). */
        private Paint solid(float round) {
            float sw = round + extra;
            if (sw > 0f) {
                fill.setStyle(Paint.Style.FILL_AND_STROKE);
                fill.setStrokeWidth(sw);
            } else {
                fill.setStyle(Paint.Style.FILL);
            }
            return fill;
        }

        private void glyph(Canvas c) {
            switch (kind) {
                case BACK:   // → (back in RTL)
                    c.drawLine(4.5f, 12, 18.5f, 12, line(2.2f));
                    path.moveTo(12.8f, 5.8f); path.lineTo(19, 12); path.lineTo(12.8f, 18.2f);
                    c.drawPath(path, line(2.2f));
                    break;
                case CAST: {
                    // screen open at the bottom-left corner for the waves
                    path.moveTo(3, 9); path.lineTo(3, 7);
                    path.quadTo(3, 5, 5, 5); path.lineTo(19, 5);
                    path.quadTo(21, 5, 21, 7); path.lineTo(21, 17);
                    path.quadTo(21, 19, 19, 19); path.lineTo(13.2f, 19);
                    c.drawPath(path, line(1.9f));
                    rect.set(3 - 6.2f, 19 - 6.2f, 3 + 6.2f, 19 + 6.2f);
                    c.drawArc(rect, 270, 90, false, line(1.9f));
                    rect.set(3 - 2.8f, 19 - 2.8f, 3 + 2.8f, 19 + 2.8f);
                    c.drawArc(rect, 270, 90, false, line(1.9f));
                    c.drawCircle(3.6f, 18.4f, 1.15f, solid(0));
                    if (active) {
                        rect.set(7, 8.5f, 17.5f, 15.5f);
                        c.drawRoundRect(rect, 1.4f, 1.4f, solid(0));
                    }
                    break;
                }
                case ASPECT:   // four corner brackets
                    path.moveTo(4, 9.5f); path.lineTo(4, 6); path.quadTo(4, 4, 6, 4); path.lineTo(9.5f, 4);
                    path.moveTo(14.5f, 4); path.lineTo(18, 4); path.quadTo(20, 4, 20, 6); path.lineTo(20, 9.5f);
                    path.moveTo(20, 14.5f); path.lineTo(20, 18); path.quadTo(20, 20, 18, 20); path.lineTo(14.5f, 20);
                    path.moveTo(9.5f, 20); path.lineTo(6, 20); path.quadTo(4, 20, 4, 18); path.lineTo(4, 14.5f);
                    c.drawPath(path, line(2f));
                    break;
                case ROTATE:   // a small phone inside a clockwise arrow
                    rect.set(4, 4, 20, 20);
                    c.drawArc(rect, 300, 300, false, line(1.9f));
                    path.moveTo(4.62f, 4.02f); path.lineTo(8.69f, 4.67f); path.lineTo(7.22f, 8.52f);
                    c.drawPath(path, line(1.9f));
                    rect.set(9.6f, 8.4f, 14.4f, 15.6f);
                    c.drawRoundRect(rect, 1.3f, 1.3f, line(1.7f));
                    break;
                case SETTINGS: {   // gear outline
                    for (int i = 0; i < 8; i++) {
                        double t = Math.PI / 4 * i;
                        float ri = 7.1f, ro = 9.7f;
                        float x1 = 12 + (float) Math.cos(t - 0.36) * ri, y1 = 12 + (float) Math.sin(t - 0.36) * ri;
                        float x2 = 12 + (float) Math.cos(t - 0.19) * ro, y2 = 12 + (float) Math.sin(t - 0.19) * ro;
                        float x3 = 12 + (float) Math.cos(t + 0.19) * ro, y3 = 12 + (float) Math.sin(t + 0.19) * ro;
                        float x4 = 12 + (float) Math.cos(t + 0.36) * ri, y4 = 12 + (float) Math.sin(t + 0.36) * ri;
                        if (i == 0) path.moveTo(x1, y1); else path.lineTo(x1, y1);
                        path.lineTo(x2, y2); path.lineTo(x3, y3); path.lineTo(x4, y4);
                    }
                    path.close();
                    c.drawPath(path, line(1.8f));
                    c.drawCircle(12, 12, 3, line(1.8f));
                    break;
                }
                case PIP:
                    rect.set(3, 5, 21, 19);
                    c.drawRoundRect(rect, 2.6f, 2.6f, line(1.9f));
                    rect.set(11.8f, 11.6f, 18.2f, 16.4f);
                    c.drawRoundRect(rect, 1.2f, 1.2f, solid(0));
                    break;
                case PLAY:   // rounded triangle
                    path.moveTo(8, 5.6f); path.lineTo(18.6f, 12); path.lineTo(8, 18.4f); path.close();
                    c.drawPath(path, solid(2.8f));
                    break;
                case PAUSE:
                    rect.set(6.6f, 4.8f, 10.2f, 19.2f);
                    c.drawRoundRect(rect, 1.8f, 1.8f, solid(0));
                    rect.set(13.8f, 4.8f, 17.4f, 19.2f);
                    c.drawRoundRect(rect, 1.8f, 1.8f, solid(0));
                    break;
                case NEXT_LEFT:   // ◀▮ pointing left (the next channel in RTL)
                    path.moveTo(17.6f, 6.4f); path.lineTo(9.2f, 12); path.lineTo(17.6f, 17.6f); path.close();
                    c.drawPath(path, solid(2.4f));
                    c.drawLine(5.4f, 6, 5.4f, 18, line(2.4f));
                    break;
                case PREV_RIGHT:  // ▮▶ pointing right (the previous channel in RTL)
                    path.moveTo(6.4f, 6.4f); path.lineTo(14.8f, 12); path.lineTo(6.4f, 17.6f); path.close();
                    c.drawPath(path, solid(2.4f));
                    c.drawLine(18.6f, 6, 18.6f, 18, line(2.4f));
                    break;
                case LOCK:
                case UNLOCK:
                    rect.set(5.2f, 10.6f, 18.8f, 20.6f);
                    c.drawRoundRect(rect, 3, 3, line(1.9f));
                    path.moveTo(8.4f, 10.6f); path.lineTo(8.4f, 7.8f);
                    path.cubicTo(8.4f, 2.6f, 15.6f, 2.6f, 15.6f, 7.8f);
                    if (kind == LOCK) path.lineTo(15.6f, 10.6f);
                    c.drawPath(path, line(1.9f));
                    c.drawLine(12, 14.4f, 12, 16.8f, line(2.2f));
                    break;
                case LIST:   // a TV set: the channels
                    rect.set(3, 7.5f, 21, 20);
                    c.drawRoundRect(rect, 2.8f, 2.8f, line(1.9f));
                    path.moveTo(8.5f, 3); path.lineTo(12, 7); path.lineTo(15.5f, 3);
                    c.drawPath(path, line(1.9f));
                    c.drawLine(8, 16.6f, 12.5f, 16.6f, line(1.7f));
                    break;
                case DOT:
                    c.drawCircle(12, 12, 8, solid(0));
                    break;
                case GO_LIVE:   // ▮« toward the live edge (left in RTL)
                    c.drawLine(4.6f, 6, 4.6f, 18, line(2.2f));
                    path.moveTo(12.6f, 6.4f); path.lineTo(7.4f, 12); path.lineTo(12.6f, 17.6f);
                    path.moveTo(19.4f, 6.4f); path.lineTo(14.2f, 12); path.lineTo(19.4f, 17.6f);
                    c.drawPath(path, line(2.2f));
                    break;
                default:
                    break;
            }
        }

        @Override public void setAlpha(int alpha) {
            this.alpha = Math.max(0, Math.min(255, alpha));
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter cf) {
            stroke.setColorFilter(cf);
            fill.setColorFilter(cf);
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

        @Override public int getIntrinsicWidth() { return 72; }

        @Override public int getIntrinsicHeight() { return 72; }
    }
}
