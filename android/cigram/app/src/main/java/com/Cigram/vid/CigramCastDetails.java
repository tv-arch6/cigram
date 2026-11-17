package com.Cigram.vid;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.mediarouter.media.MediaRouteSelector;
import androidx.mediarouter.media.MediaRouter;

import com.google.android.gms.cast.CastMediaControlIntent;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.MediaTrack;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.images.WebImage;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Details page "بث إلى التلفاز" (v2.6).
 *
 * - A transparent cast icon at the bottom-left of the Details page. It shows up only while a Cast
 *   receiver (TV) is reachable on the same Wi-Fi, and is red while connected.
 * - When the TV session starts, the movie (or the episode the user stopped at / the first one)
 *   is loaded on the TV. While connected, "تشغيل الآن" and every episode card play on the TV.
 * - The receiver plays by itself, so playback continues with the phone in the background (the
 *   Cast media notification keeps the app alive). When the movie/episode finishes, the session is
 *   ended and the TV returns to its home screen.
 * - A skippable video ad (pre-roll, VAST tag or direct video) is queued before the movie. On the
 *   phone a yellow "تخطي بعد 5" button counts down and then skips to the movie.
 * - Progress is written to the permanent history, so "أكمل المشاهدة" works for TV watching too.
 *
 * v5.24:
 * - The round button always shows a TV image inside it (the stock MediaRouteButton icon was hidden by
 *   androidx whenever only a DLNA TV was found, which left an empty red circle).
 * - Works on phones without Google Play services (Huawei, Honor ...): a failed Cast init no longer
 *   stops the button and the DLNA search.
 * - Ad: a Google Ad Manager VAST tag (remote "tv_vast_tag") plays ON THE TV with "تخطي بعد 5" on the
 *   phone. Without such a tag a real AdMob interstitial is shown on the phone before the TV starts
 *   (AdMob has no VAST tags, its ads can only be shown inside the app).
 * - CigramCastKeepAlive holds Wi-Fi / CPU locks (and a foreground service for DLNA) while a TV is
 *   connected, so watching continues with the app in the background.
 *
 * v6.4: the button shows whenever the phone can reach a TV (Wi-Fi on / LAN / hotspot), not only
 *   after one answered: many commercial Android TVs have no Chromecast and no DLNA receiver turned
 *   on, so nothing ever answered and the button stayed hidden. It is bright when a TV was found,
 *   dimmed otherwise. The chooser always offers «عرض الشاشة على التلفاز» (Miracast / Screen
 *   mirroring, built into nearly every Android TV, no shared network needed) and «إعادة البحث».
 *
 * v6.3: the TV button is on EVERY screen of the app (home, details, sections, search, account,
 *   channels, notifications ...), not only Home and Details; the players keep their own. It still
 *   appears only while a TV / receiver answers on the same Wi-Fi (or one is connected). Every
 *   screen keeps its own button and all of them are refreshed: before, only the button of the last
 *   page opened was tracked, so Home stopped updating after a visit to a Details page.
 *
 * v6.1: a video the TV refuses no longer ends the session ("يعتذر ويفصل"). The main item is
 *   retried without the subtitle track, then with a plain video/mp4 type (and https for an http
 *   link). Only when every try fails, the file itself is probed (first and last bytes) and the
 *   viewer is told the real reason: H.265 video, AC3/DTS audio, a dead link... The TV stays
 *   connected so another title can be played, and "cast_error" is sent to the statistics with
 *   the reason so the admin sees which files fail.
 *
 * v10.66: on a TV / TV box the button is not shown at all (the app IS the TV; no search runs there).
 *   On a phone it shows only once the search has found a TV on the same Wi-Fi (or one is
 *   connected), always in red: the grey "maybe" button of v6.4 is gone.
 *
 * v5.26: the TV search (DLNA + Chromecast discovery) really starts again. The page is bound after it
 *   is laid out, so the attach listener was added too late and never ran; the button only showed
 *   when another screen happened to be searching. While a TV is connected the button stays visible
 *   (red) even if the TV stops answering the search during playback.
 */
public final class CigramCastDetails {

    private static final String TAG = "CigramCast";
    private static final String VIEW_TAG = "cigram_details_cast";
    private static final String OVERLAY_TAG = "cigram_cast_ad_overlay";
    private static final String ROLE_KEY = "cigram_role";
    private static final String ROLE_AD = "ad";
    private static final String ROLE_MAIN = "main";

    /** Turn the TV ad off with false. */
    public static final boolean ADS_ENABLED = true;
    /**
     * The TV ad source now comes from CigramAdConfig.tvVastTag(): a VAST tag URL (Google Ad Manager /
     * any VAST 2-4 server) or a direct video link (.mp4 / .m3u8), set remotely with "tv_vast_tag".
     */
    public static final int SKIP_AFTER_SECONDS = 5;

    private static final int YELLOW = 0xFFFFD60A;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();

    private static Context appCtx;
    private static CastContext castContext;
    private static boolean inited;
    private static boolean lifecycleRegistered;
    private static long castRetryAt;
    private static WeakReference<Activity> detailsRef = new WeakReference<Activity>(null);
    private static WeakReference<Activity> topRef = new WeakReference<Activity>(null);
    /** v6.3: one round button per screen, each with its TV image. */
    private static final java.util.WeakHashMap<View, Glyph> BOXES = new java.util.WeakHashMap<View, Glyph>();

    private static CigramDlna.Device dlnaDev;
    private static volatile boolean dlnaSkip;
    private static volatile long dlnaPos;
    private static volatile long dlnaDur;
    private static RemoteMediaClient client;
    private static RemoteMediaClient.Callback clientCallback;
    private static int generation;
    private static boolean mainSeen;
    private static boolean mainRequested;
    private static boolean ended;
    private static boolean adActive;
    private static boolean adSkipped;
    private static boolean adImpressionSent;
    private static AdInfo currentAd;
    private static JSONObject recInfo;
    private static long lastRecordAt;

    private static LinearLayout overlayView;
    private static TextView overlayLabel;
    private static TextView overlaySkip;

    private CigramCastDetails() { }

    // ------------------------------------------------------------------ setup

    /**
     * @return true when Chromecast is usable. DLNA works either way, so callers never stop on false
     * (phones without Google Play services: Huawei, Honor ...).
     */
    private static boolean init(Activity activity) {
        if (appCtx == null) appCtx = activity.getApplicationContext();
        if (!lifecycleRegistered) {
            try {
                ((Application) appCtx).registerActivityLifecycleCallbacks(LIFECYCLE);
                lifecycleRegistered = true;
            } catch (Throwable error) {
                Log.w(TAG, "lifecycle callbacks failed", error);
            }
        }
        if (inited) return castContext != null;
        if (System.currentTimeMillis() < castRetryAt) return false;
        try {
            castContext = CastContext.getSharedInstance(appCtx);
            castContext.getSessionManager().addSessionManagerListener(SESSION_LISTENER, CastSession.class);
            inited = true;
            CastSession s = castContext.getSessionManager().getCurrentCastSession();
            if (s != null && s.isConnected()) {
                attachClient(s);
                CigramCastKeepAlive.start(appCtx, "", false);
            }
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "cast init failed (DLNA only)", error);
            castContext = null;
            inited = false;
            castRetryAt = System.currentTimeMillis() + 60000L;
            return false;
        }
    }

    private static boolean connected() {
        try {
            if (castContext == null) return false;
            CastSession s = castContext.getSessionManager().getCurrentCastSession();
            return s != null && s.isConnected();
        } catch (Throwable error) {
            return false;
        }
    }

    private static boolean alive(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    // ------------------------------------------------------------------ the button

    /** Screens that get the floating TV button: every page of this app except the video players. */
    private static boolean wantsButton(Activity a) {
        if (a == null) return false;
        String full = a.getClass().getName();
        if (!full.startsWith("com.Cigram.vid.")) return false;           // ads, system pickers ...
        if (CigramTvMode.isTv(a)) return false;   // v10.66: the app runs on the TV itself
        String n = a.getClass().getSimpleName();
        return !"PlayerActivity".equals(n) && !"CigramTogetherPlayerActivity".equals(n)
                && !"LiveActivity".equals(n) && !"CigramBannerActivity".equals(n);
    }

    /** Called from the page bindings and on every resumed screen. Idempotent. */
    public static void bind(final Activity activity) {
        if (!alive(activity)) return;
        if (!wantsButton(activity)) return;
        String screen = activity.getClass().getSimpleName();
        final boolean home = "MainActivity".equals(screen);
        final boolean bottomBar = CigramBottomNav.hasBar(activity);
        try {
            init(activity); // false = no Google Play services: the DLNA TVs still show up
            if ("DetailsActivity".equals(screen)) detailsRef = new WeakReference<Activity>(activity);
            topRef = new WeakReference<Activity>(activity);

            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return;
            FrameLayout root = (FrameLayout) content;
            View existing = root.findViewWithTag(VIEW_TAG);
            if (existing != null) {
                existing.bringToFront();
                synchronized (BOXES) {
                    if (!BOXES.containsKey(existing)) {
                        View ic = existing.findViewWithTag(VIEW_TAG + "_icon");
                        Object d = ic instanceof android.widget.ImageView
                                ? ((android.widget.ImageView) ic).getDrawable() : null;
                        BOXES.put(existing, d instanceof Glyph ? (Glyph) d : null);
                    }
                }
                // v5.26: closing the Details page stopped the 5 s refresh for Home too; restart it here
                UI.removeCallbacks(PERIODIC);
                UI.postDelayed(PERIODIC, 5000L);
                refreshButton();
                return;
            }

            final FrameLayout box = new FrameLayout(activity);
            box.setTag(VIEW_TAG);
            box.setVisibility(View.GONE);
            box.setClipChildren(false);
            // round, translucent red floating button (same look as the reference recording)
            box.setBackground(CigramUI.round(0xCCD12A2A, 40f, activity));
            box.setElevation(CigramUI.dp(activity, 6));

            // stock Cast button: kept invisible, only used as a fallback chooser. androidx hides
            // its icon whenever no Chromecast is found (DLNA TVs only), so it can not be the image.
            final androidx.mediarouter.app.MediaRouteButton button =
                    new androidx.mediarouter.app.MediaRouteButton(activity);
            button.setContentDescription("البث إلى التلفاز");
            button.setBackground(null);
            button.setAlpha(0f);
            button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            try {
                if (castContext != null) CastButtonFactory.setUpMediaRouteButton(activity, button);
            } catch (Throwable error) {
                Log.w(TAG, "cast button setup failed", error);
            }
            box.addView(button, new FrameLayout.LayoutParams(CigramUI.dp(activity, 52), CigramUI.dp(activity, 52)));

            // the TV image inside the round button: always drawn while a TV is on the Wi-Fi
            android.widget.ImageView icon = new android.widget.ImageView(activity);
            icon.setTag(VIEW_TAG + "_icon");
            icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            icon.setPadding(CigramUI.dp(activity, 12), CigramUI.dp(activity, 12),
                    CigramUI.dp(activity, 12), CigramUI.dp(activity, 12));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            Glyph glyph = new Glyph();
            icon.setImageDrawable(glyph);
            box.addView(icon, new FrameLayout.LayoutParams(CigramUI.dp(activity, 52), CigramUI.dp(activity, 52)));
            // transparent layer on top: disconnected -> open the device chooser;
            // connected -> app-styled "disconnect" dialog instead of the stock controller dialog
            View hit = new View(activity);
            hit.setClickable(true);
            hit.setContentDescription("البث إلى التلفاز");
            hit.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (connected() || dlnaDev != null) confirmDisconnect(topRef.get());
                    else showChooser(topRef.get() != null ? topRef.get() : activity, button);
                }
            });
            box.addView(hit, new FrameLayout.LayoutParams(CigramUI.dp(activity, 52), CigramUI.dp(activity, 52)));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    CigramUI.dp(activity, 52), CigramUI.dp(activity, 52), Gravity.BOTTOM | Gravity.LEFT);
            lp.leftMargin = CigramUI.dp(activity, 16);
            lp.bottomMargin = (bottomBar ? CigramUI.dp(activity, 84) : CigramUI.dp(activity, 22))
                    + navBarHeight(activity);
            synchronized (BOXES) {
                BOXES.put(box, glyph);
            }

            final MediaRouter router = MediaRouter.getInstance(activity.getApplicationContext());
            final MediaRouteSelector selector = selector();
            final MediaRouter.Callback routeCallback = new MediaRouter.Callback() {
                @Override public void onRouteAdded(MediaRouter r, MediaRouter.RouteInfo i) { refreshButton(); }
                @Override public void onRouteRemoved(MediaRouter r, MediaRouter.RouteInfo i) { refreshButton(); }
                @Override public void onRouteChanged(MediaRouter r, MediaRouter.RouteInfo i) { refreshButton(); }
                @Override public void onProviderAdded(MediaRouter r, MediaRouter.ProviderInfo p) { refreshButton(); }
                @Override public void onProviderRemoved(MediaRouter r, MediaRouter.ProviderInfo p) { refreshButton(); }
                @Override public void onRouteSelected(MediaRouter r, MediaRouter.RouteInfo i) { refreshButton(); }
                @Override public void onRouteUnselected(MediaRouter r, MediaRouter.RouteInfo i) { refreshButton(); }
            };
            // v5.26: the listener is added BEFORE the box joins the screen. bind() runs once the page is
            // laid out (already attached), and addView() attaches the box at once, so a listener added
            // afterwards never heard onViewAttachedToWindow: the TV search never started and the
            // button stayed hidden although the TV was on the same Wi-Fi.
            box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {
                    try {
                        CigramDlna.setOnChanged(new Runnable() {
                            @Override public void run() { UI.post(new Runnable() {
                                @Override public void run() { refreshButton(); } }); }
                        });
                        CigramDlna.start(v.getContext());
                        UI.removeCallbacks(PERIODIC);
                        UI.postDelayed(PERIODIC, 5000L);
                    } catch (Throwable error) {
                        Log.w(TAG, "dlna discovery failed", error);
                    }
                    try {
                        router.addCallback(selector, routeCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY);
                    } catch (Throwable error) {
                        Log.w(TAG, "route discovery failed", error);
                    }
                    refreshButton();
                }
                @Override public void onViewDetachedFromWindow(View v) {
                    try { router.removeCallback(routeCallback); } catch (Throwable ignored) { }
                    try { CigramDlna.stop(); } catch (Throwable ignored) { }
                    synchronized (BOXES) { BOXES.remove(v); }
                    UI.removeCallbacks(PERIODIC);
                    // v6.3: the other screens' buttons keep refreshing
                    if (anyBoxAttached()) UI.postDelayed(PERIODIC, 5000L);
                }
            });
            root.addView(box, lp);
            box.bringToFront();
            refreshButton();
        } catch (Throwable error) {
            Log.w(TAG, "bind failed", error);
        }
    }

    private static final Runnable PERIODIC = new Runnable() {
        @Override public void run() {
            refreshButton();
            if (anyBoxAttached()) UI.postDelayed(this, 5000L);
        }
    };

    private static MediaRouteSelector selector() {
        MediaRouteSelector merged = null;
        try { if (castContext != null) merged = castContext.getMergedSelector(); } catch (Throwable ignored) { }
        if (merged != null) return merged;
        return new MediaRouteSelector.Builder()
                .addControlCategory(CastMediaControlIntent.categoryForCast(
                        CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
                .build();
    }

    private static boolean anyBoxAttached() {
        synchronized (BOXES) {
            for (View v : BOXES.keySet()) if (v != null && v.isAttachedToWindow()) return true;
        }
        return false;
    }

    /** Shows the icon (on every screen) only when a TV is reachable (or already connected). */
    private static void refreshButton() {
        java.util.ArrayList<View> boxes = new java.util.ArrayList<View>();
        synchronized (BOXES) {
            for (View v : BOXES.keySet()) if (v != null && v.isAttachedToWindow()) boxes.add(v);
        }
        if (boxes.isEmpty()) return;
        boolean isConnected = connected() || dlnaDev != null;
        boolean found = isConnected;
        try {
            if (!found) found = !CigramDlna.devices().isEmpty();
            if (!found) {
                MediaRouter router = MediaRouter.getInstance(boxes.get(0).getContext().getApplicationContext());
                found = router.isRouteAvailable(selector(), MediaRouter.AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE);
            }
        } catch (Throwable error) {
            Log.w(TAG, "availability check failed", error);
        }
        // v10.66: shown only once the search found a TV on the same network (or one is connected)
        boolean available = found;
        try {
            if (CigramTvMode.isTv(boxes.get(0).getContext())) available = false;   // never on a TV
        } catch (Throwable ignored) { }
        for (View box : boxes) {
            Glyph g;
            synchronized (BOXES) { g = BOXES.get(box); }
            refreshBox(box, g, isConnected, available, found);
        }
    }

    private static void refreshBox(View box, Glyph g, boolean isConnected, boolean available, boolean found) {
        try {
            if (g != null) {
                g.setColor(Color.WHITE);
                g.setConnected(isConnected);
            }
            box.setBackground(CigramUI.round(isConnected ? 0xE6E21B14 : 0xCCD12A2A, 40f, box.getContext()));
            box.setContentDescription(isConnected ? "متصل بالتلفاز" : "البث إلى التلفاز");
            if (available && box.getVisibility() != View.VISIBLE) {
                box.setAlpha(0f);
                box.setScaleX(0.6f);
                box.setScaleY(0.6f);
                box.setVisibility(View.VISIBLE);
                box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320L)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.4f)).start();
            } else if (!available && box.getVisibility() == View.VISIBLE) {
                box.setVisibility(View.GONE);
            } else if (available && box.getScaleX() >= 0.99f) {
                box.setAlpha(1f);
            }
            box.invalidate();
        } catch (Throwable error) {
            Log.w(TAG, "refreshButton failed", error);
        }
    }

    // ------------------------------------------------------------------ DLNA TVs

    private static void connectDlna(Activity a, CigramDlna.Device d) {
        try {
            if (connected() && castContext != null) castContext.getSessionManager().endCurrentSession(true);
        } catch (Throwable ignored) { }
        resetPlayback();
        dlnaDev = d;
        CigramCastKeepAlive.start(appCtx, "", true);
        CigramAnalytics.track(appCtx, "cast_connected", "kind", "dlna");
        if (appCtx != null) CigramSilent.toast(appCtx, "تم الاتصال بجهاز الاستقبال", android.widget.Toast.LENGTH_SHORT);
        refreshButton();
        Activity det = detailsRef.get();
        if (alive(det) && det == a) castCurrent(det);
    }

    static void disconnectDlna(boolean stopTv) {
        final CigramDlna.Device d = dlnaDev;
        if (d != null) CigramAnalytics.track(appCtx, "cast_disconnected", "kind", "dlna");
        dlnaDev = null;
        dlnaSkip = true;
        resetPlayback();
        if (!connected()) CigramCastKeepAlive.stop(appCtx);
        if (stopTv && d != null) {
            new Thread(new Runnable() {
                @Override public void run() { CigramDlna.stop(d); }
            }).start();
        }
        refreshButton();
    }

    private static void startDlna(final Context ctx, final String video, final String title,
                                  final long resume, final int gen) {
        final CigramDlna.Device dev = dlnaDev;
        if (dev == null) return;
        dlnaSkip = false;
        CigramCastKeepAlive.start(ctx, title, true);
        CigramSilent.toast(ctx, "جارٍ التشغيل على التلفاز…", android.widget.Toast.LENGTH_SHORT);
        final String adTag = tvAdTag(ctx);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    AdInfo ad = null;
                    if (adTag.length() > 0) {
                        try { ad = resolveAd(adTag); } catch (Throwable error) { Log.w(TAG, "ad resolve failed", error); }
                    }
                    if (gen != generation) return;
                    if (ad != null && ad.url.length() > 0) runDlnaAd(dev, ad, gen);
                    if (gen != generation) return;
                    runDlnaMain(dev, video, title, resume, gen);
                } catch (Throwable error) {
                    Log.w(TAG, "dlna run failed", error);
                }
            }
        }, "cigram-dlna-play").start();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private static void runDlnaAd(final CigramDlna.Device dev, final AdInfo ad, final int gen) {
        currentAd = ad;
        if (!CigramDlna.setUri(dev, ad.url, "إعلان", ad.mime) || !CigramDlna.play(dev)) return;
        long t0 = System.currentTimeMillis();
        boolean playing = false;
        while (gen == generation && !dlnaSkip && dlnaDev == dev) {
            sleep(1000L);
            CigramDlna.Info i = CigramDlna.info(dev);
            long now = System.currentTimeMillis();
            if (i == null) {
                if (now - t0 > 12000L) break;
                continue;
            }
            boolean isPlaying = "PLAYING".equalsIgnoreCase(i.state);
            if (isPlaying) {
                playing = true;
                dlnaPos = i.positionMs;
                dlnaDur = i.durationMs;
                UI.post(new Runnable() {
                    @Override public void run() {
                        if (gen != generation) return;
                        if (!adActive) enterAd();
                        updateOverlay();
                    }
                });
            } else if (playing && ("STOPPED".equalsIgnoreCase(i.state)
                    || "NO_MEDIA_PRESENT".equalsIgnoreCase(i.state))) {
                break;
            } else if (!playing && now - t0 > 12000L) {
                break; // the ad never started: never block the movie
            }
        }
        UI.post(new Runnable() {
            @Override public void run() {
                if (gen == generation && adActive) exitAd(dlnaSkip);
            }
        });
    }

    private static void runDlnaMain(final CigramDlna.Device dev, String video, String title,
                                    long resume, final int gen) {
        boolean sent = CigramDlna.setUri(dev, video, title, mimeOf(video)) && CigramDlna.play(dev);
        if (!sent && !"video/mp4".equals(mimeOf(video))) {
            // v6.1: many TVs accept an MKV announced as video/mp4
            sent = CigramDlna.setUri(dev, video, title, "video/mp4") && CigramDlna.play(dev);
        }
        if (!sent) {
            final String videoUrl = video;
            final String[] why = probe(videoUrl);
            final Context ctx = appCtx;
            UI.post(new Runnable() {
                @Override public void run() {
                    if (gen != generation || ctx == null) return;
                    CigramSilent.toast(ctx, why[1], android.widget.Toast.LENGTH_LONG);
                    CigramAnalytics.track(ctx, "cast_error", "reason", why[0], "ext", extOf(videoUrl), "kind", "dlna");
                }
            });
            return; // the TV stays selected
        }
        long t0 = System.currentTimeMillis();
        long lastRec = 0L;
        long lastPos = 0L;
        long lastDur = 0L;
        int misses = 0;
        boolean playing = false;
        boolean seeked = false;
        while (gen == generation && dlnaDev == dev) {
            sleep(1000L);
            CigramDlna.Info i = CigramDlna.info(dev);
            long now = System.currentTimeMillis();
            if (i == null) {
                if (++misses > 20) {
                    dlnaFailed(gen, "انقطع الاتصال بالتلفاز");
                    return;
                }
                continue;
            }
            misses = 0;
            String st = i.state.toUpperCase(Locale.US);
            if ("PLAYING".equals(st)) {
                if (!playing) {
                    playing = true;
                    mainSeen = true;
                    if (resume > 0L && !seeked) {
                        seeked = true;
                        CigramDlna.seek(dev, resume);
                    }
                }
                if (i.positionMs > 0L) lastPos = i.positionMs;
                if (i.durationMs > 0L) lastDur = i.durationMs;
                if (now - lastRec >= 10000L && lastPos > 0L) {
                    lastRec = now;
                    saveDlnaProgress(lastPos, lastDur, false);
                }
            } else if (playing && ("STOPPED".equals(st) || "NO_MEDIA_PRESENT".equals(st))) {
                final boolean finished = lastDur > 0L && lastPos >= lastDur - 20000L;
                saveDlnaProgress(lastPos, lastDur, finished);
                final String msg = finished ? "انتهى العرض وتم قطع البث" : "توقف العرض على التلفاز";
                UI.post(new Runnable() {
                    @Override public void run() {
                        if (gen != generation) return;
                        if (appCtx != null) CigramSilent.toast(appCtx, msg, android.widget.Toast.LENGTH_SHORT);
                        disconnectDlna(false);
                    }
                });
                return;
            } else if (!playing && now - t0 > 30000L) {
                dlnaFailed(gen, "تعذر تشغيل الفيديو على التلفاز");
                return;
            }
        }
    }

    private static void saveDlnaProgress(long pos, long dur, boolean finished) {
        try {
            if (recInfo == null || appCtx == null) return;
            CigramUserData.record(appCtx, recInfo, finished ? dur : pos, dur, finished);
        } catch (Throwable error) {
            Log.w(TAG, "saveDlnaProgress failed", error);
        }
    }

    private static void dlnaFailed(final int gen, final String msg) {
        UI.post(new Runnable() {
            @Override public void run() {
                if (gen != generation) return;
                if (appCtx != null) CigramSilent.toast(appCtx, msg, android.widget.Toast.LENGTH_LONG);
                disconnectDlna(true);
            }
        });
    }

    /** One list with every TV found: Chromecast / Google TV devices and DLNA TVs. */
    private static void showChooser(final Activity a, final View castButton) {
        if (!alive(a)) return;
        try {
            final MediaRouter router = MediaRouter.getInstance(a.getApplicationContext());
            final MediaRouteSelector sel = selector();
            final CigramUI.Sheet sheet = CigramUI.sheet(a);
            LinearLayout list = new LinearLayout(a);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            int count = 0;
            try {
                for (final MediaRouter.RouteInfo r : router.getRoutes()) {
                    if (r.isDefault() || !r.isEnabled() || !r.matchesSelector(sel)) continue;
                    list.addView(deviceRow(a, String.valueOf(r.getName()), "Chromecast", new Runnable() {
                        @Override public void run() {
                            sheet.dismiss();
                            if (dlnaDev != null) disconnectDlna(true);
                            router.selectRoute(r);
                        }
                    }));
                    count++;
                }
            } catch (Throwable error) {
                Log.w(TAG, "route list failed", error);
            }
            for (final CigramDlna.Device d : CigramDlna.devices()) {
                list.addView(deviceRow(a, d.name, "DLNA", new Runnable() {
                    @Override public void run() {
                        sheet.dismiss();
                        connectDlna(a, d);
                    }
                }));
                count++;
            }
            // v6.4: works with every Android TV: screen mirroring (Miracast) needs no receiver app
            list.addView(deviceRow(a, "عرض الشاشة على التلفاز", "Miracast", new Runnable() {
                @Override public void run() {
                    sheet.dismiss();
                    openMirroring(a);
                }
            }));
            list.addView(deviceRow(a, "إعادة البحث عن التلفاز", "", new Runnable() {
                @Override public void run() {
                    sheet.dismiss();
                    try { CigramDlna.rescan(a); } catch (Throwable ignored) { }
                    CigramSilent.toast(a, "جارٍ البحث… افتح القائمة مرة ثانية بعد 5 ثوانٍ", android.widget.Toast.LENGTH_SHORT);
                }
            }));
            sheet.icon(CigramUI.ICON_INFO, 0xFFE21B14)
                    .title("اختر جهاز التلفاز")
                    .message(count == 0
                            ? "لم يرد أي تلفاز على البث المباشر. استخدم «عرض الشاشة على التلفاز» (يعمل مع أغلب شاشات أندرويد)، أو فعّل Chromecast / DLNA في التلفاز."
                            : "الأجهزة المتاحة على شبكتك")
                    .secondary("إلغاء", new CigramUI.Click() {
                        @Override public boolean onClick(CigramUI.Sheet sh) { return false; }
                    });
            sheet.view(list);
            sheet.show();
        } catch (Throwable error) {
            Log.w(TAG, "showChooser failed", error);
            try { if (castButton != null) castButton.performClick(); } catch (Throwable ignored) { }
        }
    }

    /**
     * Opens the phone's own screen-mirroring (Miracast / "Smart View" / "Screen cast") panel. The
     * video then plays on the phone and the TV shows the phone's screen. Tries the standard Android
     * screen and the common vendor ones, then the wireless settings.
     */
    static void openMirroring(Activity a) {
        if (!alive(a)) return;
        String[] actions = {
                "android.settings.CAST_SETTINGS",
                "android.settings.WIFI_DISPLAY_SETTINGS",
                "com.samsung.android.smartmirroring.CAPTURE",
                "android.settings.WIRELESS_SETTINGS",
        };
        for (String action : actions) {
            try {
                android.content.Intent i = new android.content.Intent(action);
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                // no resolveActivity(): Android 11+ hides other apps' screens from it (package
                // visibility); starting it and catching "not found" works on every version
                a.startActivity(i);
                CigramAnalytics.track(a, "cast_mirroring_opened", "action", action);
                CigramSilent.toast(a, "اختر التلفاز من القائمة، ثم ارجع وشغّل الفيديو", android.widget.Toast.LENGTH_LONG);
                return;
            } catch (Throwable ignored) { }
        }
        CigramSilent.toast(a, "افتح «عرض الشاشة» أو «Smart View» من الإعدادات السريعة للهاتف", android.widget.Toast.LENGTH_LONG);
    }

    private static View deviceRow(Activity a, String name, String kind, final Runnable onClick) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(CigramUI.round(0x22FFFFFF, 14f, a));
        row.setPadding(CigramUI.dp(a, 16), CigramUI.dp(a, 13), CigramUI.dp(a, 16), CigramUI.dp(a, 13));
        row.setClickable(true);
        TextView k = CigramUI.label(a, kind, 11f, CigramUI.MUTED, false);
        row.addView(k, new LinearLayout.LayoutParams(-2, -2));
        TextView n = CigramUI.label(a, name, 16f, CigramUI.TEXT, false);
        n.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onClick.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = CigramUI.dp(a, 8);
        row.setLayoutParams(lp);
        return row;
    }

    private static void confirmDisconnect(Activity a) {
        if (!alive(a)) return;
        try {
            CigramUI.sheet(a)
                    .icon(CigramUI.ICON_LOGOUT, 0xFFE21B14)
                    .title("قطع الاتصال مع جهاز التلفاز")
                    .message("سيتوقف العرض على التلفاز ويعود التلفاز إلى شاشته الرئيسية.")
                    .danger("قطع الاتصال", new CigramUI.Click() {
                        @Override public boolean onClick(CigramUI.Sheet sh) {
                            try {
                                if (dlnaDev != null) {
                                    disconnectDlna(true);
                                    return false;
                                }
                                if (castContext != null) castContext.getSessionManager().endCurrentSession(true);
                            } catch (Throwable error) {
                                Log.w(TAG, "disconnect failed", error);
                            }
                            return false;
                        }
                    })
                    .secondary("إلغاء", new CigramUI.Click() {
                        @Override public boolean onClick(CigramUI.Sheet sh) { return false; }
                    })
                    .show();
        } catch (Throwable error) {
            Log.w(TAG, "confirmDisconnect failed", error);
        }
    }

    private static int navBarHeight(Context c) {
        try {
            int id = c.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) return c.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) { }
        return 0;
    }

    // ------------------------------------------------------------------ session

    private static final SessionManagerListener<CastSession> SESSION_LISTENER =
            new SessionManagerListener<CastSession>() {
                @Override public void onSessionStarting(CastSession s) { refreshButton(); }
                @Override public void onSessionStarted(CastSession s, String id) {
                    if (dlnaDev != null) disconnectDlna(true);
                    attachClient(s);
                    CigramCastKeepAlive.start(appCtx, "", false);
                    refreshButton();
                    CigramAnalytics.track(appCtx, "cast_connected", "kind", "chromecast");
                    if (appCtx != null) CigramSilent.toast(appCtx, "تم الاتصال بجهاز الاستقبال", android.widget.Toast.LENGTH_SHORT);
                    Activity d = detailsRef.get();
                    if (alive(d) && topRef.get() == d) castCurrent(d);
                }
                @Override public void onSessionStartFailed(CastSession s, int error) {
                    CigramAnalytics.track(appCtx, "cast_failed", "kind", "chromecast", "code", String.valueOf(error));
                    refreshButton();
                    Context c = appCtx;
                    if (c != null) CigramSilent.toast(c, "تعذر الاتصال بالتلفاز", android.widget.Toast.LENGTH_SHORT);
                }
                @Override public void onSessionEnding(CastSession s) { }
                @Override public void onSessionEnded(CastSession s, int error) {
                    CigramAnalytics.track(appCtx, "cast_disconnected", "kind", "chromecast", "code", String.valueOf(error));
                    detachClient();
                    resetPlayback();
                    if (dlnaDev == null) CigramCastKeepAlive.stop(appCtx);
                    refreshButton();
                }
                @Override public void onSessionResuming(CastSession s, String id) { }
                @Override public void onSessionResumed(CastSession s, boolean wasSuspended) {
                    attachClient(s);
                    CigramCastKeepAlive.start(appCtx, "", false);
                    refreshButton();
                }
                @Override public void onSessionResumeFailed(CastSession s, int error) {
                    detachClient();
                    refreshButton();
                }
                @Override public void onSessionSuspended(CastSession s, int reason) { }
            };

    private static void attachClient(CastSession session) {
        try {
            if (session == null) return;
            RemoteMediaClient c = session.getRemoteMediaClient();
            if (c == null || c == client) return;
            detachClient();
            client = c;
            clientCallback = new RemoteMediaClient.Callback() {
                @Override public void onStatusUpdated() { onStatus(); }
            };
            c.registerCallback(clientCallback);
            UI.removeCallbacks(TICK);
            UI.postDelayed(TICK, 1000L);
        } catch (Throwable error) {
            Log.w(TAG, "attachClient failed", error);
        }
    }

    private static void detachClient() {
        try {
            if (client != null && clientCallback != null) client.unregisterCallback(clientCallback);
        } catch (Throwable ignored) { }
        client = null;
        clientCallback = null;
        UI.removeCallbacks(TICK);
    }

    /** v6.1: what the main item was built from, to rebuild it for a retry. */
    private static JSONObject lastInfo;
    private static boolean lastSeries;
    private static String lastSeriesTitle = "";
    private static int lastSeason;
    private static int lastEpisode;
    private static String lastSub = "";
    private static long lastResume;
    /** 0 = as sent, 1 = without subtitles, 2 = plain video/mp4 (+ https), 3 = given up. */
    private static int retryStep;
    /** The old item's error status can still arrive right after a reload: ignore it for a moment. */
    private static long lastReloadAt;

    /** v10.34: the live player loads its own media on the TV: forget the movie state (no retry / no end). */
    static void forgetPlayback() {
        resetPlayback();
    }

    private static void resetPlayback() {
        generation++;
        retryStep = 0;
        mainSeen = false;
        mainRequested = false;
        ended = false;
        adActive = false;
        adSkipped = false;
        adImpressionSent = false;
        currentAd = null;
        recInfo = null;
        detachOverlay();
    }

    // ------------------------------------------------------------------ what to play

    private static JSONObject[] target(Activity a) {
        JSONObject item = CigramStage1InitProvider.castDetailsItem(a);
        if (item == null) return null;
        if (vid(item).length() > 0) return new JSONObject[]{item, null};
        if (!CigramStage1InitProvider.castIsSeries(item)) return null;
        JSONArray eps = CigramStage1InitProvider.castEpisodes(item);
        if (eps == null || eps.length() == 0) return null;
        int idx = 0;
        try {
            String st = norm(txt(item, "series_title", "title", "name"));
            JSONObject last = null;
            ArrayList<JSONObject> hist = CigramUserData.history(a);
            for (int i = 0; i < hist.size(); i++) {
                JSONObject h = hist.get(i);
                if (!"episode".equals(h.optString("type"))) continue;
                if (!st.equals(norm(h.optString("series_title")))) continue;
                if (last == null || h.optLong("watched_at", 0L) > last.optLong("watched_at", 0L)) last = h;
            }
            if (last != null) {
                int found = -1;
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject e = eps.optJSONObject(i);
                    if (e == null) continue;
                    if (num(e, "season", "season_number") == Math.max(1, last.optInt("season", 1))
                            && num(e, "episode", "episode_number", "ep", "number") == last.optInt("episode", 0)) {
                        found = i;
                        break;
                    }
                }
                if (found >= 0) {
                    idx = found;
                    if (last.optBoolean("finished", false) && found + 1 < eps.length()) idx = found + 1;
                }
            }
        } catch (Throwable ignored) { }
        JSONObject ep = eps.optJSONObject(idx);
        if (ep == null || vid(ep).length() == 0) return null;
        return new JSONObject[]{ep, item};
    }

    private static void castCurrent(Activity a) {
        JSONObject[] t = target(a);
        if (t == null) {
            CigramSilent.toast(a, "رابط الفيديو غير متوفر", android.widget.Toast.LENGTH_SHORT);
            return;
        }
        startWithAd(a, t[0], t[1]);
    }

    /** The VAST tag for the TV ad, or "" (ads off, ad-free user, or no tag configured). */
    private static String tvAdTag(Context c) {
        try {
            if (!ADS_ENABLED || c == null) return "";
            if (!CigramAdConfig.tvAdsEnabled(c) || CigramAdConfig.adFreeUser(c)) return "";
            return CigramAdConfig.tvVastTag(c);
        } catch (Throwable error) {
            return "";
        }
    }

    /**
     * Sends the item to the TV. With a TV ad tag the ad plays on the TV (see play()); without one,
     * a real AdMob interstitial is shown on the phone first (same caps as the episode ads), then the
     * film starts on the TV.
     */
    private static void startWithAd(final Activity a, final JSONObject playable, final JSONObject parent) {
        final Context app = a.getApplicationContext();
        final String kind = dlnaDev != null ? "dlna" : "chromecast";
        boolean phoneAd = false;
        try {
            phoneAd = ADS_ENABLED && CigramAdConfig.tvAdsEnabled(app) && !CigramAdConfig.adFreeUser(app)
                    && CigramAdConfig.tvVastTag(app).length() == 0
                    && !CigramEpisodeAds.shownWithin(90000L) // v6.2: the press already showed one
                    // v10.35: a film / episode unlocked with a rewarded ad gets no second ad on the phone
                    && !CigramRewardUnlock.isUnlocked(CigramStage1InitProvider.rewardKeyOf(playable));
        } catch (Throwable ignored) { }
        if (!phoneAd) {
            sendToTv(app, playable, parent, kind);
            return;
        }
        CigramEpisodeAds.beforeEpisode(a, null, new Runnable() {
            @Override public void run() {
                if (!(connected() || dlnaDev != null)) return; // disconnected during the ad
                sendToTv(app, playable, parent, kind);
            }
        });
    }

    private static void sendToTv(Context app, JSONObject playable, JSONObject parent, String kind) {
        boolean started = play(app, playable, parent);
        CigramAnalytics.track(app, started ? "cast_started" : "cast_failed", "kind", kind);
        if (!started) CigramSilent.toast(app, "تعذر التشغيل على التلفاز", android.widget.Toast.LENGTH_SHORT);
    }

    /** "قطع الاتصال" from the keep-alive notification. */
    static void disconnectFromNotification() {
        try {
            if (dlnaDev != null) {
                disconnectDlna(true);
            } else if (castContext != null && connected()) {
                castContext.getSessionManager().endCurrentSession(true);
            }
        } catch (Throwable error) {
            Log.w(TAG, "disconnect from notification failed", error);
        }
        CigramCastKeepAlive.stop(appCtx);
    }

    /**
     * Used by the Details "تشغيل الآن" button and the episode cards.
     * @return true when the item was sent to the TV (the phone player must not open)
     */
    public static boolean tryCast(Activity activity, JSONObject source, JSONObject parent) {
        try {
            if (activity == null || source == null) return false;
            init(activity);
            if (!(connected() || dlnaDev != null)) return false;
            if (vid(source).length() == 0) return false;
            startWithAd(activity, source, parent);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "tryCast failed", error);
            return false;
        }
    }

    private static boolean play(Context ctx, JSONObject playable, JSONObject parent) {
        try {
            CastSession session = null;
            RemoteMediaClient c = null;
            if (dlnaDev == null) {
                if (castContext == null) return false;
                session = castContext.getSessionManager().getCurrentCastSession();
                c = session == null ? null : session.getRemoteMediaClient();
                if (c == null) return false;
                attachClient(session);
            }
            resetPlayback();
            final int gen = generation;
            mainRequested = true;

            boolean series = parent != null || "series".equalsIgnoreCase(txt(playable, "type"))
                    || "episode".equalsIgnoreCase(txt(playable, "type"));
            String seriesTitle = parent != null ? txt(parent, "series_title", "title", "name")
                    : txt(playable, "series_title");
            String title = txt(playable, "title", "name", "episode_title");
            if (title.length() == 0) title = seriesTitle;
            int season = num(playable, "season", "season_number");
            int episode = num(playable, "episode", "episode_number", "ep", "number");
            String poster = txt(playable, "backdrop", "poster", "image");
            if (poster.length() == 0 && parent != null) poster = txt(parent, "backdrop", "poster", "series_poster", "image");
            String video = vid(playable);
            String sub = txt(playable, "subtitle", "subtitle_url");

            JSONObject info = new JSONObject();
            info.put("type", series ? "series" : "movie");
            info.put("id", txt(playable, "id"));
            info.put("title", title);
            info.put("poster", poster);
            info.put("backdrop", poster);
            info.put("video", video);
            info.put("series_id", parent != null ? txt(parent, "series_id", "id") : txt(playable, "series_id"));
            info.put("series_title", series ? seriesTitle : "");
            info.put("season", series ? Math.max(1, season) : 0);
            info.put("episode", series ? episode : 0);
            info.put("category", txt(parent != null ? parent : playable, "category", "genre", "genres"));
            info.put("year", txt(parent != null ? parent : playable, "year", "release_year"));
            recInfo = info;

            long resume = resumeMs(ctx, info);
            if (dlnaDev != null) {
                startDlna(ctx, video, title, resume, gen);
                return true;
            }
            lastInfo = info;
            lastSeries = series;
            lastSeriesTitle = seriesTitle;
            lastSeason = season;
            lastEpisode = episode;
            lastSub = sub;
            lastResume = resume;
            final MediaQueueItem mainItem = buildMainItem(info, series, seriesTitle, season, episode, sub, resume);
            final RemoteMediaClient fc = c;

            CigramSilent.toast(ctx, "جارٍ التشغيل على التلفاز…", android.widget.Toast.LENGTH_SHORT);
            final String adTag = tvAdTag(ctx);
            if (adTag.length() == 0) {
                loadQueue(fc, null, mainItem);
                return true;
            }
            NET.execute(new Runnable() {
                @Override public void run() {
                    AdInfo ad = null;
                    try { ad = resolveAd(adTag); } catch (Throwable error) { Log.w(TAG, "ad resolve failed", error); }
                    final AdInfo result = ad;
                    UI.post(new Runnable() {
                        @Override public void run() {
                            if (gen != generation || !connected()) return;
                            loadQueue(fc, result, mainItem);
                        }
                    });
                }
            });
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "play failed", error);
            return false;
        }
    }

    private static void loadQueue(RemoteMediaClient c, AdInfo ad, MediaQueueItem mainItem) {
        try {
            ArrayList<MediaQueueItem> items = new ArrayList<MediaQueueItem>();
            if (ad != null && ad.url.length() > 0) {
                currentAd = ad;
                MediaMetadata md = new MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC);
                md.putString(MediaMetadata.KEY_TITLE, "إعلان");
                JSONObject cd = new JSONObject();
                cd.put(ROLE_KEY, ROLE_AD);
                MediaInfo adInfo = new MediaInfo.Builder(ad.url)
                        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                        .setContentType(ad.mime)
                        .setMetadata(md)
                        .setCustomData(cd)
                        .build();
                items.add(new MediaQueueItem.Builder(adInfo).setAutoplay(true).setPreloadTime(3.0).build());
            }
            items.add(mainItem);
            MediaQueueItem[] arr = items.toArray(new MediaQueueItem[0]);
            c.queueLoad(arr, 0, MediaStatus.REPEAT_MODE_REPEAT_OFF, null);
        } catch (Throwable error) {
            Log.w(TAG, "loadQueue failed", error);
            CigramSilent.toast(appCtx, "تعذر التشغيل على التلفاز", android.widget.Toast.LENGTH_SHORT);
        }
    }

    private static MediaQueueItem buildMainItem(JSONObject info, boolean series, String seriesTitle,
                                                int season, int episode, String subtitle, long resumeMs)
            throws Exception {
        return buildMainItem(info, series, seriesTitle, season, episode, subtitle, resumeMs, null, null);
    }

    private static MediaQueueItem buildMainItem(JSONObject info, boolean series, String seriesTitle,
                                                int season, int episode, String subtitle, long resumeMs,
                                                String urlOverride, String typeOverride)
            throws Exception {
        String video = urlOverride != null ? urlOverride : info.optString("video");
        MediaMetadata md = new MediaMetadata(series ? MediaMetadata.MEDIA_TYPE_TV_SHOW : MediaMetadata.MEDIA_TYPE_MOVIE);
        md.putString(MediaMetadata.KEY_TITLE, info.optString("title"));
        if (series) {
            if (seriesTitle.length() > 0) md.putString(MediaMetadata.KEY_SERIES_TITLE, seriesTitle);
            if (season > 0) md.putInt(MediaMetadata.KEY_SEASON_NUMBER, season);
            if (episode > 0) md.putInt(MediaMetadata.KEY_EPISODE_NUMBER, episode);
        }
        String poster = info.optString("poster");
        if (poster.startsWith("http")) md.addImage(new WebImage(Uri.parse(poster)));

        JSONObject cd = new JSONObject();
        cd.put(ROLE_KEY, ROLE_MAIN);
        MediaInfo.Builder mb = new MediaInfo.Builder(video)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(typeOverride != null ? typeOverride : mimeOf(video))
                .setMetadata(md)
                .setCustomData(cd);
        boolean withTrack = subtitle != null && subtitle.toLowerCase(Locale.US).split("\\?")[0].endsWith(".vtt");
        if (withTrack) {
            List<MediaTrack> tracks = new ArrayList<MediaTrack>();
            tracks.add(new MediaTrack.Builder(1L, MediaTrack.TYPE_TEXT)
                    .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    .setName("العربية")
                    .setLanguage("ar")
                    .setContentId(subtitle)
                    .setContentType("text/vtt")
                    .build());
            mb.setMediaTracks(tracks);
        }
        MediaQueueItem.Builder qb = new MediaQueueItem.Builder(mb.build()).setAutoplay(true).setPreloadTime(10.0);
        if (resumeMs > 0L) qb.setStartTime(resumeMs / 1000.0);
        if (withTrack) qb.setActiveTrackIds(new long[]{1L});
        return qb.build();
    }

    private static long resumeMs(Context ctx, JSONObject info) {
        try {
            boolean ep = "series".equals(info.optString("type"));
            String st = norm(info.optString("series_title"));
            String title = norm(info.optString("title"));
            String id = info.optString("id");
            ArrayList<JSONObject> hist = CigramUserData.history(ctx);
            for (int i = 0; i < hist.size(); i++) {
                JSONObject h = hist.get(i);
                boolean match;
                if (ep) {
                    match = "episode".equals(h.optString("type"))
                            && st.equals(norm(h.optString("series_title")))
                            && h.optInt("season", 1) == Math.max(1, info.optInt("season", 1))
                            && h.optInt("episode", 0) == info.optInt("episode", 0);
                } else {
                    match = "movie".equals(h.optString("type"))
                            && ((id.length() > 0 && id.equals(h.optString("id")))
                            || title.equals(norm(h.optString("title"))));
                }
                if (!match) continue;
                long pos = h.optLong("last_position", 0L);
                long dur = h.optLong("duration", 0L);
                if (!h.optBoolean("finished", false) && pos >= 5000L && (dur <= 0L || dur - pos > 15000L)) return pos;
                return 0L;
            }
        } catch (Throwable ignored) { }
        return 0L;
    }

    // ------------------------------------------------------------------ status handling

    private static String roleOf(MediaInfo mi) {
        try {
            if (mi == null) return "";
            JSONObject cd = mi.getCustomData();
            return cd == null ? "" : cd.optString(ROLE_KEY, "");
        } catch (Throwable error) {
            return "";
        }
    }

    private static void onStatus() {
        try {
            if (client == null || ended) return;
            MediaStatus st = client.getMediaStatus();
            if (st == null) return;
            int state = st.getPlayerState();
            String role = roleOf(st.getMediaInfo());
            if (state == MediaStatus.PLAYER_STATE_PLAYING || state == MediaStatus.PLAYER_STATE_BUFFERING
                    || state == MediaStatus.PLAYER_STATE_PAUSED || state == MediaStatus.PLAYER_STATE_LOADING) {
                if (ROLE_AD.equals(role)) {
                    if (state == MediaStatus.PLAYER_STATE_PLAYING) enterAd();
                } else if (ROLE_MAIN.equals(role)) {
                    mainSeen = true;
                    if (adActive) exitAd(false);
                }
            } else if (state == MediaStatus.PLAYER_STATE_IDLE) {
                int reason = st.getIdleReason();
                if (reason == MediaStatus.IDLE_REASON_ERROR) {
                    if (adActive || (ROLE_AD.equals(role) && !mainSeen)) {
                        // the ad could not play: never block the movie
                        exitAd(true);
                        if (client != null) client.queueNext(null);
                    } else if (mainRequested) {
                        // v6.1: retry in a lighter form, never end the session for a refused video
                        retryMain();
                    }
                } else if (reason == MediaStatus.IDLE_REASON_FINISHED && mainSeen && !adActive) {
                    saveProgress(true);
                    CigramSilent.toast(appCtx, "انتهى العرض وتم قطع البث", android.widget.Toast.LENGTH_SHORT);
                    endCast();
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "onStatus failed", error);
        }
    }

    /** The TV refused the main video: try without subtitles, then as plain mp4 (https); then explain. */
    private static void retryMain() {
        if (client == null || lastInfo == null) return;
        if (System.currentTimeMillis() - lastReloadAt < 2500L) return;
        try {
            String video = lastInfo.optString("video");
            boolean hadTrack = lastSub != null && lastSub.toLowerCase(Locale.US).split("\\?")[0].endsWith(".vtt");
            if (retryStep == 0 && hadTrack) {
                retryStep = 1;
                Log.w(TAG, "main refused, retry without subtitles");
                reload(buildMainItem(lastInfo, lastSeries, lastSeriesTitle, lastSeason, lastEpisode, "", lastResume));
                return;
            }
            if (retryStep <= 1) {
                retryStep = 2;
                String url = video.startsWith("http://") ? "https://" + video.substring(7) : video;
                boolean differs = !url.equals(video) || !"video/mp4".equals(mimeOf(video));
                if (differs) {
                    Log.w(TAG, "main refused, retry as video/mp4 " + (url.equals(video) ? "" : "(https)"));
                    reload(buildMainItem(lastInfo, lastSeries, lastSeriesTitle, lastSeason, lastEpisode, "",
                            lastResume, url, "video/mp4"));
                    return;
                }
            }
            retryStep = 3;
            giveUp(video);
        } catch (Throwable error) {
            Log.w(TAG, "retryMain failed", error);
            giveUp(lastInfo.optString("video"));
        }
    }

    private static void reload(MediaQueueItem item) {
        lastReloadAt = System.currentTimeMillis();
        mainSeen = false;
        mainRequested = true;
        ended = false;
        loadQueue(client, null, item);
    }

    /** Every try failed: keep the TV connected, find out why, tell the viewer and the statistics. */
    private static void giveUp(final String video) {
        detachOverlay();
        mainRequested = false;
        final Context ctx = appCtx;
        if (ctx != null) CigramSilent.toast(ctx, "التلفاز رفض هذا الفيديو. جارٍ فحص السبب…", android.widget.Toast.LENGTH_SHORT);
        final int gen = generation;
        NET.execute(new Runnable() {
            @Override public void run() {
                final String[] why = probe(video);
                UI.post(new Runnable() {
                    @Override public void run() {
                        if (gen != generation || ctx == null) return;
                        CigramSilent.toast(ctx, why[1], android.widget.Toast.LENGTH_LONG);
                        CigramAnalytics.track(ctx, "cast_error", "reason", why[0], "ext", extOf(video),
                                "kind", dlnaDev != null ? "dlna" : "chromecast");
                    }
                });
            }
        });
    }

    private static String extOf(String url) {
        String p = url == null ? "" : url.toLowerCase(Locale.US).split("\\?")[0];
        int dot = p.lastIndexOf('.');
        int slash = p.lastIndexOf('/');
        return dot > slash && p.length() - dot <= 6 ? p.substring(dot + 1) : "";
    }

    /**
     * Reads the start and the end of the file (where the codec boxes live in MP4 / MKV) and returns
     * {reason code, Arabic message}. Network only, never on the UI thread.
     */
    static String[] probe(String video) {
        try {
            if (video == null || video.length() == 0) return new String[]{"no_url", "رابط الفيديو فارغ."};
            int[] code = new int[1];
            byte[] head = rangeGet(video, "bytes=0-524287", code);
            if (code[0] == 401 || code[0] == 403 || code[0] == 404 || code[0] == 410) {
                return new String[]{"http_" + code[0], "رابط الفيديو لا يعمل (HTTP " + code[0] + "). أصلح الرابط من الأدمن."};
            }
            if (head == null || head.length == 0) {
                return new String[]{"unreachable", "تعذر الوصول إلى ملف الفيديو من الشبكة. جرّب مرة أخرى."};
            }
            byte[] tail = rangeGet(video, "bytes=-1048576", new int[1]);
            String h = latin(head) + (tail == null ? "" : latin(tail));
            boolean mkv = (head.length > 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45
                    && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3);
            if (h.contains("hvc1") || h.contains("hev1") || h.contains("V_MPEGH/ISO/HEVC")) {
                return new String[]{"hevc", "هذا الفيديو بترميز H.265 (HEVC) وجهاز البث لا يدعمه. أعد رفعه بخيار «توافق أعلى» (H.264) في الأدمن."};
            }
            if (h.contains("A_DTS") || h.contains("dtsc") || h.contains("A_AC3") || h.contains("A_EAC3")
                    || h.contains("ac-3") || h.contains("ec-3") || h.contains("A_TRUEHD")) {
                return new String[]{"audio", "صوت هذا الفيديو بصيغة AC3/DTS وجهاز البث لا يشغّلها. أعد رفعه بخيار «توافق أعلى» (صوت AAC) في الأدمن."};
            }
            if (mkv) {
                return new String[]{"mkv", "الفيديو بصيغة MKV وجهاز البث لم يقبلها. أعد رفعه بخيار «توافق أعلى» (MP4) في الأدمن."};
            }
            if (video.startsWith("http://")) {
                return new String[]{"http_link", "رابط الفيديو غير آمن (http). جهاز البث يحتاج رابط https."};
            }
            return new String[]{"unknown", "جهاز البث لم يستطع تشغيل هذا الفيديو. جرّب فيديو آخر أو شاهده على الهاتف."};
        } catch (Throwable error) {
            return new String[]{"probe_failed", "جهاز البث لم يستطع تشغيل هذا الفيديو."};
        }
    }

    private static byte[] rangeGet(String url, String range, int[] codeOut) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Range", range);
            int code = c.getResponseCode();
            codeOut[0] = code;
            if (code != 200 && code != 206) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            int max = 1100000; // a server that ignores Range sends the whole file: stop early
            while ((r = in.read(buf)) > 0 && out.size() < max) out.write(buf, 0, r);
            in.close();
            return out.toByteArray();
        } catch (Throwable error) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String latin(byte[] b) {
        try {
            return new String(b, "ISO-8859-1");
        } catch (Throwable error) {
            return "";
        }
    }

    private static void endCast() {
        CigramAnalytics.track(appCtx, "cast_ended");
        ended = true;
        UI.removeCallbacks(TICK);
        detachOverlay();
        try {
            if (castContext != null) castContext.getSessionManager().endCurrentSession(true);
        } catch (Throwable error) {
            Log.w(TAG, "endCurrentSession failed", error);
        }
        refreshButton();
    }

    private static void saveProgress(boolean finished) {
        try {
            if (recInfo == null || appCtx == null || client == null) return;
            long pos = client.getApproximateStreamPosition();
            long dur = client.getStreamDuration();
            if (!finished && (pos <= 0L || dur <= 0L)) return;
            CigramUserData.record(appCtx, recInfo, finished ? dur : pos, dur, finished);
        } catch (Throwable error) {
            Log.w(TAG, "saveProgress failed", error);
        }
    }

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            try {
                if (client == null || ended) return;
                if (adActive) updateOverlay();
                MediaStatus st = client.getMediaStatus();
                if (st != null && mainSeen && !adActive && st.getPlayerState() == MediaStatus.PLAYER_STATE_PLAYING
                        && ROLE_MAIN.equals(roleOf(st.getMediaInfo()))) {
                    long now = System.currentTimeMillis();
                    if (now - lastRecordAt >= 10000L) {
                        lastRecordAt = now;
                        saveProgress(false);
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "tick failed", error);
            }
            UI.postDelayed(this, 1000L);
        }
    };

    // ------------------------------------------------------------------ the ad (phone side)

    private static void enterAd() {
        if (adActive) return;
        adActive = true;
        adSkipped = false;
        if (currentAd != null && !adImpressionSent) {
            adImpressionSent = true;
            fire(currentAd.impressions);
            fire(currentAd.events.get("start"));
            fire(currentAd.events.get("creativeView"));
        }
        attachOverlay(topRef.get());
        updateOverlay();
    }

    private static void exitAd(boolean skipped) {
        if (!adActive) return;
        adActive = false;
        if (currentAd != null) {
            if (skipped) {
                if (adSkipped) fire(currentAd.events.get("skip"));
            } else {
                fire(currentAd.events.get("firstQuartile"));
                fire(currentAd.events.get("midpoint"));
                fire(currentAd.events.get("thirdQuartile"));
                fire(currentAd.events.get("complete"));
            }
        }
        detachOverlay();
    }

    private static void skipAd() {
        try {
            if (!adActive) return;
            if (dlnaDev != null) {
                adSkipped = true;
                dlnaSkip = true;
                exitAd(true);
                return;
            }
            if (client == null) return;
            adSkipped = true;
            client.queueNext(null);
            exitAd(true);
        } catch (Throwable error) {
            Log.w(TAG, "skipAd failed", error);
        }
    }

    private static void attachOverlay(Activity a) {
        try {
            detachOverlay();
            if (!alive(a)) return;
            if ("PlayerActivity".equals(a.getClass().getSimpleName())) return;
            View content = a.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return;
            FrameLayout root = (FrameLayout) content;

            LinearLayout col = new LinearLayout(a);
            col.setTag(OVERLAY_TAG);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.RIGHT);
            col.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

            TextView label = CigramUI.label(a, "إعلان", 12f, 0xFFFFFFFF, false);
            label.setGravity(Gravity.CENTER);
            label.setBackground(CigramUI.round(0xCC111113, 14, a));
            label.setPadding(CigramUI.dp(a, 12), CigramUI.dp(a, 5), CigramUI.dp(a, 12), CigramUI.dp(a, 5));
            LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(-2, -2);
            l1.bottomMargin = CigramUI.dp(a, 8);
            col.addView(label, l1);

            TextView skip = CigramUI.label(a, "تخطي بعد " + SKIP_AFTER_SECONDS, 15f, 0xFF000000, false);
            skip.setGravity(Gravity.CENTER);
            skip.setBackground(CigramUI.round(YELLOW, 12, a));
            skip.setPadding(CigramUI.dp(a, 20), CigramUI.dp(a, 11), CigramUI.dp(a, 20), CigramUI.dp(a, 11));
            skip.setClickable(true);
            skip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (skipReady()) skipAd();
                }
            });
            col.addView(skip, new LinearLayout.LayoutParams(-2, -2));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
            lp.rightMargin = CigramUI.dp(a, 14);
            lp.bottomMargin = CigramUI.dp(a, 28) + navBarHeight(a);
            root.addView(col, lp);
            col.bringToFront();
            overlayView = col;
            overlayLabel = label;
            overlaySkip = skip;
            updateOverlay();
        } catch (Throwable error) {
            Log.w(TAG, "attachOverlay failed", error);
        }
    }

    private static void detachOverlay() {
        try {
            if (overlayView != null && overlayView.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlayView.getParent()).removeView(overlayView);
            }
        } catch (Throwable ignored) { }
        overlayView = null;
        overlayLabel = null;
        overlaySkip = null;
    }

    private static long adElapsedSec() {
        try {
            if (dlnaDev != null) return dlnaPos / 1000L;
            return client == null ? 0L : client.getApproximateStreamPosition() / 1000L;
        } catch (Throwable e) { return 0L; }
    }

    private static boolean skipReady() {
        return adElapsedSec() >= SKIP_AFTER_SECONDS;
    }

    private static void updateOverlay() {
        try {
            if (!adActive) return;
            if (overlayView == null) attachOverlay(topRef.get());
            if (overlayView == null || (client == null && dlnaDev == null)) return;
            long elapsed = adElapsedSec();
            long total = dlnaDev != null ? dlnaDur / 1000L : client.getStreamDuration() / 1000L;
            if (overlayLabel != null) {
                long left = Math.max(0L, total - elapsed);
                overlayLabel.setText(total > 0L
                        ? "إعلان • " + (left / 60L) + ":" + (left % 60L < 10L ? "0" : "") + (left % 60L)
                        : "إعلان");
            }
            if (overlaySkip != null) {
                long remain = SKIP_AFTER_SECONDS - elapsed;
                overlaySkip.setText(remain > 0L ? "تخطي بعد " + remain : "تخطي الإعلان");
            }
        } catch (Throwable error) {
            Log.w(TAG, "updateOverlay failed", error);
        }
    }

    private static final Application.ActivityLifecycleCallbacks LIFECYCLE =
            new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityCreated(Activity a, Bundle b) { }
                @Override public void onActivityStarted(Activity a) { }
                @Override public void onActivityResumed(Activity a) {
                    topRef = new WeakReference<Activity>(a);
                    if (adActive) attachOverlay(a);
                    // v6.3: every screen of the app gets the TV button (bind is idempotent)
                    if (wantsButton(a)) {
                        final Activity target = a;
                        UI.post(new Runnable() {
                            @Override public void run() { bind(target); }
                        });
                    }
                    refreshButton();
                }
                @Override public void onActivityPaused(Activity a) {
                    if (topRef.get() == a) detachOverlay();
                }
                @Override public void onActivityStopped(Activity a) { }
                @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                @Override public void onActivityDestroyed(Activity a) {
                    if (detailsRef.get() == a) detailsRef = new WeakReference<Activity>(null);
                }
            };

    // ------------------------------------------------------------------ ads: VAST / direct

    private static final class AdInfo {
        String url = "";
        String mime = "video/mp4";
        final List<String> impressions = new ArrayList<String>();
        final Map<String, List<String>> events = new HashMap<String, List<String>>();
    }

    private static AdInfo resolveAd(String tag) throws Exception {
        String t = macros(tag.trim());
        String path = t.toLowerCase(Locale.US).split("\\?")[0];
        AdInfo ad = new AdInfo();
        if (path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".webm")) {
            ad.url = t;
            ad.mime = mimeOf(t);
            return ad;
        }
        return vast(t, 0, ad) ? ad : null;
    }

    private static String macros(String s) {
        String r = String.valueOf(Math.abs(new Random().nextLong() % 100000000L));
        return s.replace("[CACHEBUSTING]", r).replace("%5BCACHEBUSTING%5D", r)
                .replace("[TIMESTAMP]", String.valueOf(System.currentTimeMillis()))
                .replace("[random]", r);
    }

    private static boolean vast(String url, int depth, AdInfo acc) throws Exception {
        if (depth > 5) return false;
        String xml = httpGet(url);
        if (xml.length() == 0 || xml.indexOf("<Ad") < 0) return false;
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));
        NodeList ads = doc.getElementsByTagName("Ad");
        if (ads.getLength() == 0) return false;
        Element ad = (Element) ads.item(0);

        NodeList imps = ad.getElementsByTagName("Impression");
        for (int i = 0; i < imps.getLength(); i++) {
            String u = text(imps.item(i));
            if (u.length() > 0) acc.impressions.add(macros(u));
        }
        NodeList trk = ad.getElementsByTagName("Tracking");
        for (int i = 0; i < trk.getLength(); i++) {
            Element e = (Element) trk.item(i);
            String ev = e.getAttribute("event");
            String u = text(e);
            if (ev.length() == 0 || u.length() == 0) continue;
            List<String> l = acc.events.get(ev);
            if (l == null) {
                l = new ArrayList<String>();
                acc.events.put(ev, l);
            }
            l.add(macros(u));
        }
        NodeList wrap = ad.getElementsByTagName("VASTAdTagURI");
        if (wrap.getLength() > 0) {
            String next = text(wrap.item(0));
            return next.length() > 0 && vast(macros(next), depth + 1, acc);
        }

        NodeList files = ad.getElementsByTagName("MediaFile");
        String best = "";
        String bestMime = "video/mp4";
        int bestH = -1;
        String small = "";
        int smallH = Integer.MAX_VALUE;
        for (int i = 0; i < files.getLength(); i++) {
            Element f = (Element) files.item(i);
            String type = f.getAttribute("type").toLowerCase(Locale.US);
            String delivery = f.getAttribute("delivery").toLowerCase(Locale.US);
            String u = text(f);
            if (u.length() == 0 || !type.startsWith("video/mp4") || "streaming".equals(delivery)) continue;
            int h = 0;
            try { h = Integer.parseInt(f.getAttribute("height").trim()); } catch (Throwable ignored) { }
            if (h <= 720 && h > bestH) {
                bestH = h;
                best = u;
            }
            if (h < smallH) {
                smallH = h;
                small = u;
            }
        }
        if (best.length() == 0) best = small;
        if (best.length() == 0) {
            for (int i = 0; i < files.getLength(); i++) {
                Element f = (Element) files.item(i);
                String type = f.getAttribute("type").toLowerCase(Locale.US);
                String u = text(f);
                if (u.length() > 0 && (type.contains("mpegurl") || u.contains(".m3u8"))) {
                    best = u;
                    bestMime = "application/x-mpegURL";
                    break;
                }
            }
        }
        if (best.length() == 0) return false;
        acc.url = best;
        acc.mime = bestMime;
        return true;
    }

    private static String text(Node n) {
        if (n == null) return "";
        String s = n.getTextContent();
        return s == null ? "" : s.trim();
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(5000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Cigram");
        try {
            if (c.getResponseCode() / 100 != 2) return "";
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 600000) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void fire(final List<String> urls) {
        if (urls == null || urls.isEmpty()) return;
        final ArrayList<String> copy = new ArrayList<String>(urls);
        NET.execute(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < copy.size(); i++) {
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(copy.get(i)).openConnection();
                        c.setConnectTimeout(4000);
                        c.setReadTimeout(4000);
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Cigram");
                        c.getResponseCode();
                        c.disconnect();
                    } catch (Throwable ignored) { }
                }
            }
        });
    }

    // ------------------------------------------------------------------ small helpers

    private static String vid(JSONObject o) {
        return txt(o, "video", "link", "video_url", "movie_url", "stream_url", "play_url");
    }

    private static String txt(JSONObject o, String... keys) {
        if (o == null) return "";
        for (int i = 0; i < keys.length; i++) {
            String v = o.optString(keys[i], "");
            if (v != null) {
                v = v.trim();
                if (v.length() > 0 && !"null".equalsIgnoreCase(v)) return v;
            }
        }
        return "";
    }

    private static int num(JSONObject o, String... keys) {
        String s = txt(o, keys).replaceAll("[^0-9]", "");
        if (s.length() == 0 || s.length() > 6) return 0;
        try { return Integer.parseInt(s); } catch (Throwable e) { return 0; }
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String mimeOf(String url) {
        String p = url.toLowerCase(Locale.US).split("\\?")[0];
        if (p.endsWith(".m3u8")) return "application/x-mpegURL";
        if (p.endsWith(".mpd")) return "application/dash+xml";
        if (p.endsWith(".webm")) return "video/webm";
        if (p.endsWith(".mkv")) return "video/x-matroska";
        return "video/mp4";
    }

    // ------------------------------------------------------------------ icon

    /** TV image: a screen on a stand with the cast waves; the waves fill in while connected. */
    private static final class Glyph extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int color = Color.WHITE;
        private boolean connected;

        Glyph() {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int c) {
            if (color == c) return;
            color = c;
            invalidateSelf();
        }

        void setConnected(boolean c) {
            if (connected == c) return;
            connected = c;
            invalidateSelf();
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            if (s <= 0f) return;
            float l = b.left + (b.width() - s) / 2f;
            float t = b.top + (b.height() - s) / 2f;
            float stroke = Math.max(2f, s * 0.08f);
            paint.setColor(color);
            paint.setStrokeWidth(stroke);

            // screen
            paint.setStyle(Paint.Style.STROKE);
            rect.set(l + s * 0.06f, t + s * 0.12f, l + s * 0.94f, t + s * 0.74f);
            canvas.drawRoundRect(rect, s * 0.08f, s * 0.08f, paint);
            // stand
            canvas.drawLine(l + s * 0.50f, t + s * 0.74f, l + s * 0.50f, t + s * 0.86f, paint);
            canvas.drawLine(l + s * 0.30f, t + s * 0.88f, l + s * 0.70f, t + s * 0.88f, paint);

            // cast waves from the bottom-left corner of the screen
            float cx = l + s * 0.22f;
            float cy = t + s * 0.62f;
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy, s * 0.045f, paint);
            paint.setStyle(Paint.Style.STROKE);
            float[] r = {s * 0.15f, s * 0.28f};
            for (int i = 0; i < r.length; i++) {
                rect.set(cx - r[i], cy - r[i], cx + r[i], cy + r[i]);
                canvas.drawArc(rect, 270f, 90f, false, paint);
            }
            if (connected) {
                // a small filled dot on the screen's top-right corner = "connected"
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(l + s * 0.80f, t + s * 0.26f, s * 0.06f, paint);
            }
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
