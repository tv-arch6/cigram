package com.Cigram.vid;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.HorizontalScrollView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CigramStage1InitProvider extends ContentProvider {

    public static final long CIGRAM_CACHE_TTL_MS = 60L * 60L * 1000L;

    private static final String CATALOG_URL =
            "https://cigram-admin-api.wwq-mixtv.workers.dev/movies";

    private static final String PREF_CATALOG = "cigram_catalog_cache";
    private static final String KEY_JSON = "json";
    private static final String KEY_UPDATED = "updated_at";
    private static final String KEY_SEARCH_JSON = "search_index_json";
    private static final String KEY_SEARCH_UPDATED = "search_index_updated_at";
    private static final String KEY_JSON_HASH = "json_hash_v2";
    private static final long CATALOG_RESUME_REFRESH_MS = 5L * 60L * 1000L;

    private static final Object CATALOG_MEMORY_LOCK = new Object();
    private static final java.util.HashMap<String, JSONObject> CATALOG_BY_KEY =
            new java.util.HashMap<String, JSONObject>();
    private static final java.util.ArrayList<JSONObject> CATALOG_MEMORY_ITEMS =
            new java.util.ArrayList<JSONObject>();
    private static final Map<Activity, String> MAIN_DELIVERED_HASH =
            new WeakHashMap<Activity, String>();
    private static final ExecutorService IMAGE_PRELOAD_IO = CigramPool.fixed(4, "img-preload");
    private static volatile String CATALOG_MEMORY_JSON = "";
    private static volatile String CATALOG_MEMORY_HASH = "";
    private static volatile long LAST_CATALOG_NETWORK_ATTEMPT_AT = 0L;
    private static volatile long LAST_CATALOG_NETWORK_SUCCESS_AT = 0L;
    private static volatile boolean CATALOG_NETWORK_RUNNING = false;
    /** Last catalogue load result in plain text; shown on screen by Home when the sections stay empty. */
    static volatile String LAST_DIAG = "لم يبدأ تحميل الكتالوج";
    /** Short breadcrumb trail of the Home build stages, shown with the on-screen diagnosis. */
    static volatile String HOME_TRACE = "";
    /** true = show the on-screen diagnosis dialog when Home stays empty (debugging only; keep false for release). */
    static final boolean SHOW_DIAG = false;
    static synchronized void trace(String step) {
        String t = HOME_TRACE + ">" + step;
        HOME_TRACE = t.length() > 240 ? t.substring(t.length() - 240) : t;
    }
    private static java.lang.ref.WeakReference<Activity> ACTIVE_MAIN =
            new java.lang.ref.WeakReference<Activity>(null);

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_CATALOG_BYTES = 16 * 1024 * 1024;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = CigramPool.fixed(3, "cigram-io");
    /** Own pool for home posters: they used to queue behind catalogue / network work on the 3-thread IO pool. */
    /** The catalogue download gets its own thread so it can never queue behind disk work on IO. */
    private static final ExecutorService NET_IO = CigramPool.fixed(2, "cigram-net");
    private static final ExecutorService POSTER_IO = CigramPool.fixed(4, "cigram-poster");

    private static final Map<Activity, Boolean> MAIN_SETUP =
            new WeakHashMap<Activity, Boolean>();
    private static final Map<Activity, Boolean> SEARCH_SETUP =
            new WeakHashMap<Activity, Boolean>();
    private static final Map<Activity, PlayerMonitor> PLAYER_MONITORS =
            new WeakHashMap<Activity, PlayerMonitor>();
    private static final Map<Activity, SupportAdController> SUPPORT_AD_CONTROLLERS =
            new WeakHashMap<Activity, SupportAdController>();
    private static final Map<Activity, StrictSectionController> STRICT_SECTION_CONTROLLERS =
            new WeakHashMap<Activity, StrictSectionController>();
    private static final Map<View, Boolean> PULL_FALLBACK_SETUP =
            new WeakHashMap<View, Boolean>();

    private static final String PREF_WATCH_LATER = "cigram_watch_later";
    private static final String PREF_SERIES_SUBSCRIPTIONS = "cigram_series_subscriptions";
    private static final String KEY_STAGE3_ITEMS = "items_json";
    private static final String TAG_STAGE3_ACCOUNT_LIST = "cigram_stage3_watch_later_section";
    private static final String PREF_STAGE3_REACTIONS = "cigram_stage3_reactions_v1";
    private static final int STAGE3_REACTION_NONE = 0;
    private static final int STAGE3_REACTION_LIKE = 1;
    private static final int STAGE3_REACTION_DISLIKE = 2;
    private static final String STAGE3_REACTIONS_URL =
            "https://cigram-auth-api.wwq-mixtv.workers.dev/reactions";
    private static final long STAGE3_REACTION_REFRESH_MS = 15000L;
    private static final Map<Activity, Boolean> STAGE3_REACTION_LOADING =
            new WeakHashMap<Activity, Boolean>();
    private static final Map<Activity, Boolean> STAGE3_REACTION_SUBMITTING =
            new WeakHashMap<Activity, Boolean>();
    private static final Map<Activity, Boolean> DETAILS_STAGE3_SETUP =
            new WeakHashMap<Activity, Boolean>();
    private static final Map<Activity, Boolean> DETAILS_BACK_SETUP =
            new WeakHashMap<Activity, Boolean>();
    private static final Object DETAILS_FAVORITES_LOCK = new Object();
    private static final Map<Activity, JSONObject> DETAILS_DATA_CACHE =
            new WeakHashMap<Activity, JSONObject>();
    private static final int DETAILS_WARM_MAX_ITEMS = 64;
    private static final java.util.LinkedHashMap<String, JSONObject> DETAILS_WARM_ITEMS =
            new java.util.LinkedHashMap<String, JSONObject>(64, 0.75f, true);
    private static final android.util.LruCache<String, android.graphics.Bitmap>
            DETAILS_IMAGE_MEMORY =
            new android.util.LruCache<String, android.graphics.Bitmap>(detailsImageCacheKb()) {
                @Override
                protected int sizeOf(String key, android.graphics.Bitmap value) {
                    if (value == null || value.isRecycled()) return 1;
                    try {
                        return Math.max(1, value.getByteCount() / 1024);
                    } catch (Throwable ignored) {
                        return Math.max(1, value.getRowBytes() * value.getHeight() / 1024);
                    }
                }
            };
    private static int detailsImageCacheKb() {
        long heapKb = Runtime.getRuntime().maxMemory() / 1024L;
        long wanted = heapKb / 16L;
        return (int) Math.max(8L * 1024L, Math.min(24L * 1024L, wanted));
    }

    private static final class PosterWait {
        final long at = System.currentTimeMillis();
        final java.util.ArrayList<android.widget.ImageView> views = new java.util.ArrayList<android.widget.ImageView>();
    }
    private static final java.util.HashMap<String, PosterWait> POSTER_INFLIGHT =
            new java.util.HashMap<String, PosterWait>();

    private static final java.util.HashSet<String> DETAILS_IMAGE_PRELOADS =
            new java.util.HashSet<String>();
    private static final java.util.HashMap<String, DetailsLaunch> DETAILS_LAUNCHES =
            new java.util.HashMap<String, DetailsLaunch>();
    private static final Object DETAILS_LAUNCH_LOCK = new Object();

    private static final class DetailsLaunch {
        final String imageUrl;
        final boolean shared;
        final long createdAt;
        boolean hasRect;
        int srcLeft;
        int srcTop;
        int srcWidth;
        int srcHeight;
        /** Half-size picture of the page the poster was tapped on: kept visible (dimmed) behind the opening flight. */
        volatile android.graphics.Bitmap snapshot;

        DetailsLaunch(String value, boolean useShared) {
            imageUrl = value == null ? "" : value;
            shared = useShared;
            createdAt = System.currentTimeMillis();
        }
    }
    private static final Map<Activity, Boolean> ACCOUNT_STAGE3_SETUP =
            new WeakHashMap<Activity, Boolean>();

    public static final long CIGRAM_DEFAULT_INTRO_START_MS = 0L;
    public static final long CIGRAM_DEFAULT_INTRO_END_MS = 120L * 1000L;

    private static final String PREF_PLAYER_STAGE4 = "cigram_player_stage4";
    private static final String KEY_PLAYER_SPEED = "playback_speed";
    private static final String TAG_INTRO_SKIP = "cigram_stage4_intro_skip";

    public static final String CIGRAM_ADMOB_REAL_INTERSTITIAL_UNIT_ID =
            CigramAdConfig.REAL_PLAYER_INTERSTITIAL;
    public static final long CIGRAM_AD_MIN_START_MS = CigramAdConfig.PLAYER_MIN_START_MS;
    public static final long CIGRAM_AD_END_GUARD_MS = CigramAdConfig.PLAYER_END_GUARD_MS;
    public static final long CIGRAM_AD_FALLBACK_INTERVAL_MS = CigramAdConfig.PLAYER_FALLBACK_INTERVAL_MS;
    public static final long CIGRAM_AD_PRELOAD_MS = CigramAdConfig.PLAYER_PRELOAD_MS;
    public static final long CIGRAM_AD_BREAK_GRACE_MS = CigramAdConfig.PLAYER_BREAK_GRACE_MS;
    public static final int CIGRAM_AD_MAX_PER_HOUR = CigramAdConfig.PLAYER_MAX_PER_HOUR;
    // Support-ad load back-off (shared by all controllers): a failed first load blocks
    // new real AdMob requests for 5s, 10s, 20s, 40s, then 60s; any success resets it.
    private static long CG_SUPPORT_NEXT_LOAD_AT = 0L;
    private static int CG_SUPPORT_FAILURES = 0;

    private static final String PREF_STAGE6 = "cigram_stage6_notifications";
    private static final String KEY_STAGE6_LAST_CHECK = "last_check_at";
    private static final String KEY_STAGE6_PERMISSION_REQUESTED = "permission_requested";
    private static final String KEY_STAGE6_PERMISSION_LATER_AT = "permission_later_at";
    private static final long STAGE6_PERMISSION_LATER_COOLDOWN_MS = 3L * 24L * 60L * 60L * 1000L;
    private static final String KEY_STAGE6_SIGNATURES = "last_known_episode_signatures";
    private static final String KEY_STAGE6_BASELINE = "stage6_baseline_initialized";
    private static final String KEY_STAGE6_HISTORY = "history_json";
    private static final String KEY_STAGE6_UNREAD = "unread";
    private static final String STAGE6_CHANNEL_ID = "cigram_episodes_v2";      // v10.67: HIGH (pop-up + sound)
    private static final String STAGE6_CHANNEL_ID_OLD = "cigram_new_episodes";
    private static final long STAGE6_MIN_CHECK_INTERVAL_MS = 15L * 60L * 1000L;
    private static final long STAGE6_PERIODIC_INTERVAL_MS = 30L * 60L * 1000L;
    private static final int STAGE6_JOB_ID = 6606;
    /** v5.21: alert every user when a NEW movie or series appears in the catalog. */
    private static final String KEY_STAGE6_KNOWN_TITLES = "known_title_keys_v1";
    private static final String KEY_STAGE6_NEWREL_ENABLED = "new_release_alerts";
    private static final String STAGE6_TITLES_CHANNEL_ID = "cigram_titles_v2";  // v10.67: HIGH (pop-up + sound)
    private static final String STAGE6_TITLES_CHANNEL_ID_OLD = "cigram_new_titles";
    /** More than this many "new" titles at once means the catalog was reshuffled, not released. */
    private static final int STAGE6_NEW_TITLE_MAX_BATCH = 25;

    private static volatile boolean STAGE6_CHECK_RUNNING;
    private static boolean STAGE6_PERIODIC_STARTED;
    private static boolean STAGE6_PROCESS_OPEN_CHECK_STARTED;

    private static Context appContext;

    /** v10.49: gives image memory back when Android asks (severity 1 .. 3, see CigramStability). */
    static void trimMemory(int severity) {
        CigramStability.shrink(DETAILS_IMAGE_MEMORY, severity);
        CigramStability.shrink(SECTION_IMAGE_MEMORY, severity);
    }

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) return true;

        appContext = context.getApplicationContext();
        CigramStability.init(appContext);   // v10.49: memory trimming + crash record, first of all
        CigramTypefaces.warm(appContext);   // app font parsed on a background thread, ready before the first label
        if (appContext instanceof Application) CigramFontGuard.install((Application) appContext);
        if (appContext instanceof Application) CigramTheme.install((Application) appContext);   // v10.70 Light / Dark
        // v10.42: the catalog warm-up MUST stay synchronous so the cached catalog is in memory
        // before Home builds; backgrounding it (v10.39) made Home wait on the network instead,
        // which left the splash up for ~8-10 s. MediaStore/AdConfig/FCM/Session already spawn
        // their own threads, and the periodic check is only scheduled (postDelayed), so this
        // sequence does not block the cold start meaningfully.
        // Perf: analytics start-up (prefs load, queue parse, PackageManager call, two events) is not
        // needed for the first frame. If an activity asks for it first, init() simply runs there once.
        IO.execute(new Runnable() { @Override public void run() { CigramAnalytics.init(appContext); } });
        CigramMediaStore.init(appContext);
        CigramAdConfig.refreshRemote(appContext);
        startCatalogProcessWarmup(appContext);
        CigramFcm.initNow(appContext);           // v10.67: Firebase ready before any push service starts
        stage6CreateNotificationChannel(appContext);
        stage6CreateTitlesChannel(appContext);
        CigramPush.createChannels(appContext);
        stage6StartPeriodicChecks();
        CigramFcm.sync(appContext);
        CigramSession.warmUp(appContext);
        try {
            CigramOrder.init(appContext);            // v10.67: newest-first memory
            CigramProfile.restore(appContext);       // v10.67: name / gender / photo come back after a login
            CigramAvatar.prewarm(appContext);        // v10.67: the photo is decoded before the first page
        } catch (Throwable error) {
            android.util.Log.w("CigramInit", "profile warm-up failed", error);
        }

        if (appContext instanceof Application) {
            ((Application) appContext).registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        public void onActivityPreCreated(Activity activity, Bundle state) {
                            CigramSplash.mark(activity);   // start-up metric (no fixed hold any more)
                            try { CigramProfile.restore(activity); } catch (Throwable ignored) { }
                            cigramStripAdTitleBar(activity);
                            CigramTvMode.apply(activity);   // v10.64: وضع التلفاز (بالعرض + تكبير) — لا أثر على الجوال
                            if (Build.VERSION.SDK_INT >= 29 && activity != null) {
                                String activityName = activity.getClass().getSimpleName();
                                cigramDarkenWindow(activity);
                                if ("MainActivity".equals(activityName)) {
                                    applyLaunchSplashTheme(activity);
                                }
                                if ("DetailsActivity".equals(activityName)) {
                                    prepareDetailsWindowSurface(activity);
                                    installDetailsFirstFrameGate(activity);
                                }
                                if ("AccountActivity".equals(activityName)) {
                                    installAccountCircleImageCompatibility(activity);
                                }
                            }
                        }

                        // Runs right after DetailsActivity.onCreate() returns. If the real bind
                        // has not happened yet (Sketchware delayed initializeLogic), hide the
                        // raw details.xml sample content; the real bind fills it in later.
                        public void onActivityPostCreated(Activity activity, Bundle state) {
                            if (Build.VERSION.SDK_INT >= 29 && activity != null
                                    && "DetailsActivity".equals(activity.getClass().getSimpleName())) {
                                try {
                                    boolean bound;
                                    synchronized (DETAILS_STAGE3_SETUP) {
                                        bound = Boolean.TRUE.equals(DETAILS_STAGE3_SETUP.get(activity));
                                    }
                                    if (!bound) clearDetailsPlaceholders(activity);
                                } catch (Throwable error) {
                                    android.util.Log.w("CigramDetails", "post-created cleanup failed", error);
                                }
                            }
                        }

                        @Override
                        public void onActivityCreated(final Activity activity, Bundle state) {
                            cigramStripAdTitleBar(activity);
                            CigramTvMode.apply(activity);   // v10.64: (Android 9 وما قبل: onActivityPreCreated غير موجود)
                            cigramDarkenWindow(activity);
                            try {
                                String an = activity.getClass().getSimpleName();
                                if ("MainActivity".equals(an)) {
                                    CigramSplash.mark(activity);   // Android 9 and older have no PreCreated
                                    // the launch background (page colour + logo) did its job on the first frame; Home gets
                                    // the plain page colour of the SAME mode, so there is no flash between the two
                                    try {
                                        activity.getWindow().setBackgroundDrawable(
                                                new android.graphics.drawable.ColorDrawable(CigramTheme.page(activity)));
                                    } catch (Throwable ignored) { }
                                    CigramAnalytics.timerStart("home_load_ms");
                                } else if ("DetailsActivity".equals(an)) {
                                    CigramAnalytics.timerStart("details_first_render_ms");
                                } else if ("PlayerActivity".equals(an)) {
                                    CigramAnalytics.timerStart("player_startup_ms");
                                }
                                CigramAnalytics.screen(activity);
                            } catch (Throwable ignored) { }
                            whenActivityViewReady(activity, new Runnable() {
                                @Override public void run() {
                                    bindActivityFeatures(activity);
                                }
                            }, 1200L);
                        }

                        @Override
                        public void onActivityStarted(Activity activity) {
                            CigramAnalytics.onForeground(activity);
                            CigramAdManager.onActivityStarted();
                            // before the first frame: no full-width bar flashes on a tab switch
                            CigramBottomNav.compact(activity);
                            // cold start only: hand-off from the launch screen to Home (leaves when Home is ready)
                            CigramSplash.begin(activity);
                        }

                        @Override
                        public void onActivityResumed(final Activity activity) {
                            try { CigramTheme.onResumed(activity); } catch (Throwable ignored) { }   // v10.70
                            try {
                                CigramProfile.restore(activity);
                                CigramProfile.snapshot(activity);
                                CigramProfile.syncPending(activity);
                                CigramAvatar.prewarm(activity);
                            } catch (Throwable ignored) { }
                            CigramTvMode.refresh(activity);   // v10.64: يثبّت تكبير التلفاز إن استبدل النظام الموارد
                            CigramTvMode.showCrashNotice(activity);   // v10.65: على التلفاز فقط، مرة واحدة بعد انهيار
                            CigramPlusPromo.onResumed(activity);   // Plus pop-up: free users, Home only, max 3 per week
                            cigramHideAdTitleView(activity);
                            final Runnable bind = new Runnable() {
                                @Override public void run() {
                                    whenActivityViewReady(activity, new Runnable() {
                                        @Override public void run() {
                                            bindActivityFeatures(activity);
                                        }
                                    }, 800L);
                                }
                            };
                            // v10.56: a tab page that comes back (pages stay alive) already has its features;
                            // re-binding them took ~0.9 s on the main thread right when the bar slides, so the
                            // slide froze. Now it waits until the slide and the first frames are done.
                            boolean again;
                            String an = activity.getClass().getSimpleName();
                            synchronized (RESUMED_ONCE) {
                                again = RESUMED_ONCE.containsKey(activity);
                                RESUMED_ONCE.put(activity, Boolean.TRUE);
                            }
                            boolean tab = "MainActivity".equals(an) || "SearchXmlActivity".equals(an)
                                    || "ChannelsActivity".equals(an) || "AccountActivity".equals(an);
                            // a bottom-bar page is on screen: fetch the latest films / series / alerts / channels
                            if (tab) {
                                MAIN.postDelayed(new Runnable() {
                                    @Override public void run() { CigramTabRefresh.onTabShown(activity); }
                                }, 450L);
                            }
                            if (again && "MainActivity".equals(an)) {
                                // v10.83: back on Home (it stays alive): only what can have changed, in small steps
                                // after the switch has drawn. The one-time steps (ads init, update check, launch
                                // intent, sync ...) are not run again: they made the return to Home stutter.
                                MAIN.postDelayed(new Runnable() {
                                    @Override public void run() { homeReturned(activity); }
                                }, 380L);
                            } else if (again && tab) MAIN.postDelayed(bind, 520L);
                            else bind.run();
                        }

                        @Override
                        public void onActivityPaused(Activity activity) {
                            CigramTheme.onPaused(activity);   // v10.70: picture for the theme / rotation cross-fade
                            CigramTvMode.paused(activity);   // v10.66: TV only (stops that page's focus guard)
                        }

                        @Override
                        public void onActivityStopped(Activity activity) {
                            CigramAnalytics.onBackground(activity);
                            CigramAdManager.onActivityStopped();
                            CigramBottomNav.stopped(activity);
                            try { CigramProfile.snapshot(activity); } catch (Throwable ignored) { }
                            if ("PlayerActivity".equals(activity.getClass().getSimpleName())) {
                                PlayerMonitor monitor;
                                synchronized (PLAYER_MONITORS) {
                                    monitor = PLAYER_MONITORS.get(activity);
                                }
                                if (monitor != null) monitor.stopForBackground();
                            }
                        }

                        @Override
                        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
                        }

                        @Override
                        public void onActivityDestroyed(Activity activity) {
                            synchronized (MAIN_SETUP) {
                                MAIN_SETUP.remove(activity);
                            }
                            synchronized (SEARCH_SETUP) {
                                SEARCH_SETUP.remove(activity);
                            }
                            synchronized (DETAILS_STAGE3_SETUP) {
                                DETAILS_STAGE3_SETUP.remove(activity);
                            }
                            synchronized (DETAILS_BACK_SETUP) {
                                DETAILS_BACK_SETUP.remove(activity);
                            }
                            synchronized (DETAILS_DATA_CACHE) {
                                DETAILS_DATA_CACHE.remove(activity);
                            }
                            synchronized (ACCOUNT_STAGE3_SETUP) {
                                ACCOUNT_STAGE3_SETUP.remove(activity);
                            }
                            PlayerMonitor monitor;
                            synchronized (PLAYER_MONITORS) {
                                monitor = PLAYER_MONITORS.remove(activity);
                            }
                            if (monitor != null) monitor.destroy();

                            SupportAdController supportController;
                            synchronized (SUPPORT_AD_CONTROLLERS) {
                                supportController = SUPPORT_AD_CONTROLLERS.remove(activity);
                            }
                            if (supportController != null) supportController.destroy();

                            StrictSectionController strictSection;
                            synchronized (STRICT_SECTION_CONTROLLERS) {
                                strictSection = STRICT_SECTION_CONTROLLERS.remove(activity);
                            }
                            if (strictSection != null) strictSection.destroy();
                            CigramCastHome.unbind(activity);
                            // Maps whose values reference the page itself would keep every destroyed page alive:
                            synchronized (EPISODE_REFRESH) { EPISODE_REFRESH.remove(activity); }
                            synchronized (DETAILS_FLIGHTS) { DETAILS_FLIGHTS.remove(activity); }
                            CigramTheme.onDestroyed(activity);
                            CigramChannels.onDestroyed(activity);
                            CigramSketchwareNotifications.onDestroyed(activity);
                        }
                    }
            );
        }
        return true;
    }

    private static void installAccountCircleImageCompatibility(final Activity activity) {
        if (activity == null) return;
        try {
            final android.view.LayoutInflater inflater = activity.getLayoutInflater();
            if (inflater == null) return;
            if (inflater.getFactory() != null || inflater.getFactory2() != null) return;

            inflater.setFactory2(new android.view.LayoutInflater.Factory2() {
                @Override
                public android.view.View onCreateView(android.view.View parent, String name,
                        Context context, android.util.AttributeSet attrs) {
                    return createSafeCircleImage(name, context, attrs);
                }

                @Override
                public android.view.View onCreateView(String name, Context context,
                        android.util.AttributeSet attrs) {
                    return createSafeCircleImage(name, context, attrs);
                }

                private android.view.View createSafeCircleImage(String name, Context context,
                        android.util.AttributeSet attrs) {
                    try {
                        String actualName = name;
                        if ("view".equals(name) && attrs != null) {
                            String declared = attrs.getAttributeValue(null, "class");
                            if (declared != null && !declared.trim().isEmpty()) {
                                actualName = declared.trim();
                            }
                        }
                        if ("de.hdodenhof.circleimageview.CircleImageView".equals(actualName)) {
                            return new SafeAccountCircleImageView(context, attrs);
                        }
                    } catch (Throwable ignored) {
                    }
                    return null;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public static class SafeAccountCircleImageView
            extends de.hdodenhof.circleimageview.CircleImageView {

        public SafeAccountCircleImageView(Context context) {
            super(context);
        }

        public SafeAccountCircleImageView(Context context, android.util.AttributeSet attrs) {
            super(context, attrs);
        }

        public SafeAccountCircleImageView(Context context, android.util.AttributeSet attrs,
                int defStyleAttr) {
            super(context, attrs, defStyleAttr);
        }

        @Override
        public void setScaleType(android.widget.ImageView.ScaleType scaleType) {
            // v4.4: CircleImageView throws for EVERY scale type except CENTER_CROP (not only
            // FIT_CENTER), so any other value is mapped instead of crashing the screen.
            super.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        }

        @Override
        public void setAdjustViewBounds(boolean adjustViewBounds) {
            // CircleImageView throws for true; the circle never needs it.
            if (!adjustViewBounds) super.setAdjustViewBounds(false);
        }
    }

    private static void whenActivityViewReady(
            final Activity activity,
            final Runnable task,
            final long maxWaitMs
    ) {
        if (activity == null || task == null || !isAlive(activity)) return;
        final View root;
        try {
            root = activity.findViewById(android.R.id.content);
        } catch (Throwable error) {
            android.util.Log.w("CigramLifecycle", "find content root failed", error);
            MAIN.post(task);
            return;
        }
        if (root == null) {
            MAIN.post(task);
            return;
        }
        final long deadline = android.os.SystemClock.uptimeMillis()
                + Math.max(200L, maxWaitMs);
        root.post(new Runnable() {
            @Override public void run() {
                if (!isAlive(activity)) return;
                if ((root.getWidth() > 0 && root.getHeight() > 0)
                        || android.os.SystemClock.uptimeMillis() >= deadline) {
                    try {
                        task.run();
                    } catch (Throwable error) {
                        android.util.Log.w(
                                "CigramLifecycle",
                                "view-ready binding failed for "
                                        + activity.getClass().getSimpleName(),
                                error
                        );
                    }
                    return;
                }
                root.postDelayed(this, 16L);
            }
        });
    }

    private static final java.util.WeakHashMap<Activity, Boolean> RESUMED_ONCE =
            new java.util.WeakHashMap<Activity, Boolean>();

    /** v10.83: Home came back to the front: the few cheap things that may have changed, one per frame. */
    private static void homeReturned(final Activity activity) {
        if (!isAlive(activity)) return;
        final Runnable[] steps = new Runnable[]{
                new Runnable() { public void run() { CigramBottomNav.bind(activity); } },
                new Runnable() { public void run() { CigramSketchwareNotifications.bindHomeBell(activity); } },
                new Runnable() { public void run() { refreshOfflineBanner(activity); } },
                new Runnable() { public void run() { maybeRefreshCatalogOnResume(activity); } },
        };
        for (int i = 0; i < steps.length; i++) {
            final Runnable step = steps[i];
            MAIN.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!isAlive(activity)) return;
                    try { step.run(); } catch (Throwable error) {
                        android.util.Log.w("CigramLifecycle", "home return step failed", error);
                    }
                }
            }, 32L * i);
        }
    }

    private static void bindActivityFeatures(final Activity activity) {
        if (activity == null || !isAlive(activity)) return;
        String name = activity.getClass().getSimpleName();

        if ("MainActivity".equals(name)) {
            setupMain(activity);
            // v10.56: the rest of Home's binding runs as separate small steps (each is its own
            // message), so a frame can be drawn between them instead of one long freeze.
            final Runnable[] steps = new Runnable[]{
                    new Runnable() { public void run() {
                        // AdMob used to be initialised only inside the player / support button, so the very
                        // first ad request always had to wait for it. Start it as soon as Home is shown.
                        try { CigramAdManager.initialize(activity.getApplicationContext()); } catch (Throwable ignored) { }
                    } },
                    new Runnable() { public void run() { maybeRefreshCatalogOnResume(activity); } },
                    new Runnable() { public void run() { refreshOfflineBanner(activity); } },
                    new Runnable() { public void run() { CigramSketchwareNotifications.bindHomeBell(activity); } },
                    new Runnable() { public void run() { CigramCastHome.bind(activity); } },
                    new Runnable() { public void run() { CigramBottomNav.bind(activity); } },
                    new Runnable() { public void run() { stage6HandleMainOpen(activity); } },
                    new Runnable() { public void run() { CigramPush.handleLaunchIntent(activity); } },
                    new Runnable() { public void run() { CigramUpdateChecker.check(activity); } },
                    new Runnable() { public void run() { CigramHome.bind(activity); } },
            };
            for (int i = 0; i < steps.length; i++) {
                final Runnable step = steps[i];
                MAIN.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!isAlive(activity)) return;
                        try { step.run(); } catch (Throwable error) {
                            android.util.Log.w("CigramLifecycle", "home step failed", error);
                        }
                    }
                }, i == 0 ? 0L : 40L * i);
            }
        } else if ("NotificationsActivity".equals(name)) {
            CigramSketchwareNotifications.bindNotificationsPage(activity);
        } else if ("SearchXmlActivity".equals(name)) {
            setupSearch(activity);
            refreshOfflineBanner(activity);
            applySavedSearchIndex(activity);
            CigramBottomNav.bind(activity);
        } else if ("DetailsActivity".equals(name)) {
            CigramBottomNav.installOnDetails(activity);   // v10.47: the bottom bar on the film page too
            boolean firstDetailsBind;
            synchronized (DETAILS_STAGE3_SETUP) {
                firstDetailsBind = !Boolean.TRUE.equals(DETAILS_STAGE3_SETUP.get(activity));
                if (firstDetailsBind) DETAILS_STAGE3_SETUP.put(activity, Boolean.TRUE);
            }

            if (!firstDetailsBind) {
                Long boundAt;
                synchronized (DETAILS_BOUND_AT) { boundAt = DETAILS_BOUND_AT.get(activity); }
                // v10.34: onCreated + onResumed both re-ran this right after the first bind,
                // in the middle of the opening animation. Not needed: the page is fresh.
                long since = boundAt == null ? Long.MAX_VALUE
                        : android.os.SystemClock.uptimeMillis() - boundAt.longValue();
                if (since < 1500L) {
                    // run it once, after the page has settled (keeps the support ad etc.)
                    boolean schedule;
                    synchronized (DETAILS_DEFERRED_REFRESH) {
                        schedule = !DETAILS_DEFERRED_REFRESH.containsKey(activity);
                        if (schedule) DETAILS_DEFERRED_REFRESH.put(activity, Boolean.TRUE);
                    }
                    if (schedule) {
                        MAIN.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (isAlive(activity)) bindActivityFeatures(activity);
                            }
                        }, 1500L - since + 20L);
                    }
                    return;
                }
            }
            if (firstDetailsBind) {
                // hide the page + arm the poster flight BEFORE the first frame is drawn
                prepareDetailsWindow(activity);
                prepareDetailsActivity(activity);
                bindModernDetailsLayout(activity);
                refreshStage3Details(activity);
                setupDetails(activity);
            } else {
                refreshStage3Details(activity);
                refreshTitleEpisodeState(activity);
            }
            refreshDetailsFavoriteState(activity);
            refreshOfflineBanner(activity);
        } else if ("AccountActivity".equals(name)) {
            setupStage3Account(activity, 0);
            CigramBottomNav.bind(activity);
        } else if ("ChannelsActivity".equals(name)) {
            CigramBottomNav.bind(activity);
        } else if ("MyContentActivity".equals(name)) {
            hideStage3MyContentList(activity);
            CigramMyContent.bind(activity);
        } else if ("SectionActivity".equals(name)) {
            ensureStrictSectionController(activity).start();
        } else if ("PlayerActivity".equals(name)) {
            applyPlayerEdgeToEdge(activity);
            CigramCastHome.hidePlayerCast(activity);
            ensurePlayerMonitor(activity).start();
        }

        setupStage5SupportAd(activity, 0);
        CigramTvMode.bound(activity);   // v10.66: TV only — remote focus, side rail, page layouts
    }

    private static void startCatalogProcessWarmup(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        // Perf: this runs inside the ContentProvider's onCreate, i.e. before the first activity exists.
        // Loading the SharedPreferences file that holds the multi-MB catalogue used to happen right here
        // on the main thread and delayed every cold start; it now runs on the IO pool. Readers of
        // CATALOG_MEMORY_JSON already fall back to the preferences when it is still empty.
        IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    final SharedPreferences prefs = catalogPrefs(app);
                    final String cached = prefs.getString(KEY_JSON, "");
                    final String cachedIndex = prefs.getString(KEY_SEARCH_JSON, "");
                    if (cached == null || cached.trim().length() == 0) return;
                    CATALOG_MEMORY_JSON = cached;
                    CATALOG_MEMORY_HASH = prefs.getString(KEY_JSON_HASH, "");
                    if (!isValidCatalog(cached)) return;

                    if (isValidCatalog(cachedIndex)) {
                        String hash = CATALOG_MEMORY_HASH;
                        if (hash == null || hash.length() == 0) hash = catalogHash(cached);
                        installCatalogInMemory(cached, cachedIndex);
                        if (!hash.equals(prefs.getString(KEY_JSON_HASH, ""))) {
                            prefs.edit().putString(KEY_JSON_HASH, hash).apply();
                        }
                        preloadCatalogImages(app, cachedIndex);
                        return;
                    }

                    PreparedCatalog prepared = prepareCatalog(cached);
                    String hash = catalogHash(prepared.displayJson);
                    installCatalogInMemory(prepared.displayJson, prepared.searchIndexJson);
                    prefs.edit()
                            .putString(KEY_JSON, prepared.displayJson)
                            .putString(KEY_JSON_HASH, hash)
                            .putString(KEY_SEARCH_JSON, prepared.searchIndexJson)
                            .apply();
                    preloadCatalogImages(app, prepared.searchIndexJson);
                } catch (Throwable error) {
                    android.util.Log.w("CigramInit", "catalog warm-up failed", error);
                }
            }
        });

        requestEarlyCatalogRefresh(app, true);
    }

    private static void maybeRefreshCatalogOnResume(Activity activity) {
        if (activity == null || !isAlive(activity)) return;
        ACTIVE_MAIN = new java.lang.ref.WeakReference<Activity>(activity);
        long now = System.currentTimeMillis();
        long anchor = Math.max(LAST_CATALOG_NETWORK_ATTEMPT_AT,
                LAST_CATALOG_NETWORK_SUCCESS_AT);
        if (anchor <= 0L || now - anchor >= CATALOG_RESUME_REFRESH_MS) {
            requestEarlyCatalogRefresh(activity.getApplicationContext(), false);
        }
    }

    private static final java.util.ArrayList<Runnable> CATALOG_DONE_LISTENERS = new java.util.ArrayList<Runnable>();

    /** Downloads the catalogue now (a tab was opened). {@code onDone} runs on the main thread when the download ended. */
    static void refreshCatalogNow(Context context, Runnable onDone) {
        if (context == null) return;
        if (onDone != null) {
            synchronized (CATALOG_DONE_LISTENERS) { CATALOG_DONE_LISTENERS.add(onDone); }
        }
        requestEarlyCatalogRefresh(context.getApplicationContext(), true);
    }

    /** Re-applies the saved search index to an open search page (after the catalogue changed). */
    static void reapplySearchIndex(Activity activity) {
        if (activity == null || !isAlive(activity)) return;
        applySavedSearchIndex(activity);
    }

    private static void requestEarlyCatalogRefresh(final Context context, final boolean coldStart) {
        if (context == null) return;
        synchronized (CATALOG_MEMORY_LOCK) {
            if (CATALOG_NETWORK_RUNNING) return;
            long now = System.currentTimeMillis();
            if (!coldStart && LAST_CATALOG_NETWORK_ATTEMPT_AT > 0L
                    && now - LAST_CATALOG_NETWORK_ATTEMPT_AT < CATALOG_RESUME_REFRESH_MS) {
                return;
            }
            CATALOG_NETWORK_RUNNING = true;
            LAST_CATALOG_NETWORK_ATTEMPT_AT = now;
        }

        NET_IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    FetchResult result = fetchCatalog(context);
                    if (!result.ok || !isValidCatalog(result.body)) return;

                    PreparedCatalog prepared = prepareCatalog(result.body);
                    final String display = prepared.displayJson;
                    final String index = prepared.searchIndexJson;
                    final String hash = catalogHash(display);
                    long now = System.currentTimeMillis();

                    SharedPreferences prefs = catalogPrefs(context);
                    String oldHash = prefs.getString(KEY_JSON_HASH, "");
                    if (oldHash.length() == 0) {
                        oldHash = catalogHash(prefs.getString(KEY_JSON, ""));
                    }

                    installCatalogInMemory(display, index);
                    prefs.edit()
                            .putString(KEY_JSON, display)
                            .putString(KEY_JSON_HASH, hash)
                            .putLong(KEY_UPDATED, now)
                            .putString(KEY_SEARCH_JSON, index)
                            .putLong(KEY_SEARCH_UPDATED, now)
                            .apply();
                    LAST_CATALOG_NETWORK_SUCCESS_AT = now;
                    preloadCatalogImages(context.getApplicationContext(), index);

                    final boolean contentChanged = !hash.equals(oldHash);
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            Activity main = ACTIVE_MAIN == null ? null : ACTIVE_MAIN.get();
                            if (!isAlive(main)) { trace("earlyNoMain"); return; }
                            removeTagged(main, "cigram_stage1_main_error");
                            removeTagged(main, "cigram_offline_screen");
                            removeTagged(main, "cigram_stage1_offline_banner");
                            boolean delivered = deliverCatalogIfChanged(
                                    main, "movies", display, hash, false);
                            if (contentChanged && delivered) {
                                renderDynamicSections(main, display);
                            }
                        }
                    });
                } finally {
                    synchronized (CATALOG_MEMORY_LOCK) {
                        CATALOG_NETWORK_RUNNING = false;
                    }
                    java.util.ArrayList<Runnable> done;
                    synchronized (CATALOG_DONE_LISTENERS) {
                        done = new java.util.ArrayList<Runnable>(CATALOG_DONE_LISTENERS);
                        CATALOG_DONE_LISTENERS.clear();
                    }
                    for (Runnable listener : done) MAIN.post(listener);
                }
            }
        });
    }

    private static boolean looksLikeCatalogText(String json) {
        if (json == null) return false;
        String value = stripCatalogBom(json).trim();
        if (value.length() < 2) return false;
        return (value.charAt(0) == '[' && value.charAt(value.length() - 1) == ']')
                || (value.charAt(0) == '{' && value.charAt(value.length() - 1) == '}');
    }

    private static String catalogHash(String json) {
        String value = json == null ? "" : json;
        if (value.length() == 0) return "";
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format(java.util.Locale.US, "%02x", b & 255));
            }
            return out.toString();
        } catch (Throwable ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static boolean deliverCatalogIfChanged(
            Activity activity,
            String tag,
            String json,
            String hash,
            boolean firstCacheDelivery
    ) {
        if (!isAlive(activity) || !looksLikeCatalogText(json)) return false;
        String safeHash = hash == null || hash.length() == 0 ? catalogHash(json) : hash;
        synchronized (MAIN_DELIVERED_HASH) {
            String last = MAIN_DELIVERED_HASH.get(activity);
            if (!firstCacheDelivery && safeHash.equals(last)) return false;
            MAIN_DELIVERED_HASH.put(activity, safeHash);
        }
        return deliverCatalogToMain(activity, tag, json);
    }

    private static void installCatalogInMemory(String displayJson, String searchIndexJson) {
        if (displayJson == null || displayJson.trim().length() == 0) return;
        try {
            String indexText = searchIndexJson;
            JSONArray index;
            if (indexText != null && indexText.trim().startsWith("[")) {
                index = new JSONArray(indexText);
            } else {
                Object root = new JSONTokener(displayJson).nextValue();
                index = buildSearchIndex(root);
                indexText = index.toString();
            }

            java.util.HashMap<String, JSONObject> byKey =
                    new java.util.HashMap<String, JSONObject>();
            java.util.ArrayList<JSONObject> items =
                    new java.util.ArrayList<JSONObject>();
            for (int i = 0; i < index.length(); i++) {
                JSONObject source = index.optJSONObject(i);
                if (source == null) continue;
                JSONObject item = copyDetailsJson(source);
                items.add(item);
                String type = inferContentType(item, "");
                String id = jsonText(item, "id", "movie_id", "item_id", "content_id");
                String sid = jsonText(item, "series_id");
                if ("series".equals(type) && sid.length() == 0) sid = id;
                if (id.length() > 0) {
                    byKey.put("id|" + normalizeSearch(id), item);
                    byKey.put(type + "|id|" + normalizeSearch(id), item);
                }
                if (sid.length() > 0) {
                    byKey.put("series|id|" + normalizeSearch(sid), item);
                    byKey.put("id|" + normalizeSearch(sid), item);
                }
                String title = normalizeSearch(jsonText(
                        item, "series_title", "title", "name", "title_ar", "title_en"));
                if (title.length() > 0) byKey.put(type + "|title|" + title, item);
            }

            synchronized (CATALOG_MEMORY_LOCK) {
                CATALOG_BY_KEY.clear();
                CATALOG_BY_KEY.putAll(byKey);
                CATALOG_MEMORY_ITEMS.clear();
                CATALOG_MEMORY_ITEMS.addAll(items);
                CATALOG_MEMORY_JSON = displayJson;
                CATALOG_MEMORY_HASH = catalogHash(displayJson);
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramCatalog", "memory index build failed", error);
        }
    }

    // ---- public bridge used by the v2.3 helper classes (CigramContent, CigramUserData, ...)
    public static String cigramNormalize(String value) {
        return normalizeSearch(value);
    }

    public static java.util.ArrayList<JSONObject> cigramCatalogSnapshot() {
        synchronized (CATALOG_MEMORY_LOCK) {
            return new java.util.ArrayList<JSONObject>(CATALOG_MEMORY_ITEMS);
        }
    }

    public static JSONObject cigramCatalogFind(String type, String id, String title) {
        return findCatalogMemoryItem(type, id, title);
    }

    public static JSONArray cigramEpisodes(JSONObject series) {
        return stage6EpisodeArray(series);
    }

    /** True when this player screen is playing a series episode (used by the settings sheet). */
    public static boolean playerIsSeries(Activity activity) {
        if (activity == null) return false;
        PlayerMonitor monitor;
        synchronized (PLAYER_MONITORS) {
            monitor = PLAYER_MONITORS.get(activity);
        }
        return monitor != null && monitor.stage4IsSeries();
    }

    public static Typeface cigramFont(Context context) {
        return cigramTypeface(context);
    }

    public static int cigramDp(Context context, float value) {
        return dpInt(context, value);
    }

    public static GradientDrawable cigramRounded(int color, float radiusPx) {
        return roundedDrawable(color, radiusPx);
    }

    public static void cigramLoadPoster(Context context, String url, ImageView view) {
        loadDynamicPoster(context, url, view);
    }

    // «من البداية» on the TV details page: the NEXT player launch (within a minute) ignores the saved position.
    private static volatile long START_OVER_AT = 0L;

    public static void cigramStartOverOnce() { START_OVER_AT = android.os.SystemClock.uptimeMillis(); }

    public static void cigramPlay(Activity activity, JSONObject source, JSONObject parentSeries) {
        openPlayerForTitleItem(activity, source, parentSeries, parentSeries != null);
    }

    /** Catalog items the signed-in viewer liked (from the reactions cache of this device). */
    public static java.util.ArrayList<JSONObject> cigramLikedItems(Context context) {
        java.util.ArrayList<JSONObject> out = new java.util.ArrayList<JSONObject>();
        try {
            String email = stage3ReactionEmail(context);
            if (email.length() == 0) return out;
            SharedPreferences prefs = context.getSharedPreferences(
                    PREF_STAGE3_REACTIONS, Context.MODE_PRIVATE);
            String userHash = Integer.toHexString(email.hashCode());
            java.util.ArrayList<JSONObject> all = cigramCatalogSnapshot();
            for (int i = 0; i < all.size(); i++) {
                JSONObject item = all.get(i);
                java.util.ArrayList<String> keys = new java.util.ArrayList<String>();
                keys.add(stage3ContentKey(item));
                String id = clean(jsonText(item, "id", "series_id"));
                if (id.length() > 0 && stage3IsSeriesItem(item)) {
                    keys.add("series|" + id);
                    keys.add("series|id|" + id);
                }
                for (int k = 0; k < keys.size(); k++) {
                    String key = keys.get(k);
                    if (key == null || key.length() == 0) continue;
                    int reaction = prefs.getInt("global_reaction_"
                            + Integer.toHexString(key.hashCode()) + "_" + userHash, STAGE3_REACTION_NONE);
                    if (reaction == STAGE3_REACTION_LIKE) {
                        out.add(item);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    public static JSONArray cigramListRead(Context context, String which) {
        if ("watch_later".equals(which)) return stage3ReadItems(context, PREF_WATCH_LATER);
        if ("subscriptions".equals(which)) return stage3ReadItems(context, PREF_SERIES_SUBSCRIPTIONS);
        if ("favorites".equals(which)) return readDetailsFavorites(context);
        return new JSONArray();
    }

    public static void cigramListWrite(Context context, String which, JSONArray items) {
        if (context == null || items == null) return;
        if ("watch_later".equals(which)) {
            stage3WriteItems(context, PREF_WATCH_LATER, items);
        } else if ("subscriptions".equals(which)) {
            stage3WriteItems(context, PREF_SERIES_SUBSCRIPTIONS, items);
        } else if ("favorites".equals(which)) {
            synchronized (DETAILS_FAVORITES_LOCK) {
                context.getSharedPreferences("favorites", Context.MODE_PRIVATE).edit()
                        .putString(detailsFavoriteItemsKey(context), items.toString()).apply();
            }
        }
    }

    private static JSONObject findCatalogMemoryItem(String type, String id, String title) {
        String t = normalizeSearch(type);
        String i = normalizeSearch(id);
        String q = normalizeSearch(title);
        synchronized (CATALOG_MEMORY_LOCK) {
            JSONObject hit = null;
            if (i.length() > 0) {
                hit = CATALOG_BY_KEY.get(t + "|id|" + i);
                if (hit == null && "series".equals(t)) hit = CATALOG_BY_KEY.get("series|id|" + i);
                if (hit == null) hit = CATALOG_BY_KEY.get("id|" + i);
            }
            if (hit == null && q.length() > 0) hit = CATALOG_BY_KEY.get(t + "|title|" + q);
            return hit == null ? null : copyDetailsJson(hit);
        }
    }

    private static void preloadCatalogImages(final Context context, String searchIndexJson) {
        if (context == null || searchIndexJson == null || !searchIndexJson.trim().startsWith("[")) return;
        try {
            JSONArray index = new JSONArray(searchIndexJson);
            java.util.LinkedHashSet<String> primary = new java.util.LinkedHashSet<String>();
            java.util.LinkedHashSet<String> rest = new java.util.LinkedHashSet<String>();
            java.util.LinkedHashSet<String> backdrops = new java.util.LinkedHashSet<String>();
            for (int i = 0; i < index.length(); i++) {
                JSONObject item = index.optJSONObject(i);
                if (item == null) continue;
                String poster = normalizedPosterUrl(jsonText(
                        item, "poster", "series_poster", "season_poster", "image"));
                String backdrop = normalizedPosterUrl(jsonText(
                        item, "backdrop", "landscape_poster", "source_backdrop"));
                if (poster.length() > 0) {
                    if (i < 24) primary.add(poster); else rest.add(poster);
                }
                if (backdrop.length() > 0) backdrops.add(backdrop);
            }
            java.util.ArrayList<String> queue = new java.util.ArrayList<String>();
            queue.addAll(primary);
            queue.addAll(rest);
            queue.addAll(backdrops);
            for (final String url : queue) {
                IMAGE_PRELOAD_IO.execute(new Runnable() {
                    @Override public void run() {
                        preloadCatalogImageToDisk(context, url);
                    }
                });
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramCatalog", "image preload queue failed", error);
        }
    }

    private static void preloadCatalogImageToDisk(Context context, String rawUrl) {
        String url = normalizedPosterUrl(rawUrl);
        if (context == null || url.length() == 0) return;
        java.io.File target = dynamicPosterCacheFile(context, url);
        if (target.isFile() && target.length() > 0L) return;
        if (!(url.startsWith("https://") || url.startsWith("http://"))) return;

        HttpURLConnection connection = null;
        InputStream input = null;
        java.io.FileOutputStream output = null;
        java.io.File temp = new java.io.File(
                target.getParentFile(), target.getName() + ".preload." + Thread.currentThread().getId());
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(6500);
            connection.setReadTimeout(9000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(true);
            connection.setRequestProperty("Accept", "image/*");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return;
            input = new BufferedInputStream(connection.getInputStream());
            output = new java.io.FileOutputStream(temp, false);
            byte[] buffer = new byte[16 * 1024];
            long total = 0L;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > 16L * 1024L * 1024L) {
                    throw new java.io.IOException("preload image too large");
                }
                output.write(buffer, 0, count);
            }
            output.flush();
            if (!target.exists() && !temp.renameTo(target)) {
                java.io.FileInputStream in = new java.io.FileInputStream(temp);
                java.io.FileOutputStream out = new java.io.FileOutputStream(target, false);
                try {
                    byte[] copy = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(copy)) != -1) out.write(copy, 0, n);
                    out.flush();
                } finally {
                    try { in.close(); } catch (Throwable ignored) { }
                    try { out.close(); } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (input != null) input.close(); } catch (Throwable ignored) { }
            try { if (output != null) output.close(); } catch (Throwable ignored) { }
            if (connection != null) connection.disconnect();
            if (temp.exists()) temp.delete();
        }
    }

    private static void setupMain(final Activity activity) {
        if (!isAlive(activity)) return;

        synchronized (MAIN_SETUP) {
            if (Boolean.TRUE.equals(MAIN_SETUP.get(activity))) {
                ACTIVE_MAIN = new java.lang.ref.WeakReference<Activity>(activity);
                return;
            }
            MAIN_SETUP.put(activity, Boolean.TRUE);
        }

        ACTIVE_MAIN = new java.lang.ref.WeakReference<Activity>(activity);
        setupPullToRefresh(activity);
        try { CigramDesign.styleHomeHeaders(activity); } catch (Throwable ignored) { }   // v4.4

        // Perf: loading the multi-MB catalogue preferences used to happen here, on the main thread,
        // right after the first frame. Read it on the IO pool, then deliver/render on the main thread.
        final SharedPreferences prefs = catalogPrefs(activity);
        IO.execute(new Runnable() {
            @Override public void run() {
                final String cached = prefs.getString(KEY_JSON, "");
                if (looksLikeCatalogText(cached)) {
                    String hash0 = prefs.getString(KEY_JSON_HASH, "");
                    if (hash0.length() == 0) hash0 = "cache|" + cached.length() + "|" + cached.hashCode();
                    final String hash = hash0;
                    try {
                        // saved sections for exactly this catalogue: drawn at once, no 3 MB parse
                        String snapHash = prefs.getString(KEY_JSON_HASH, "");
                        if (snapHash.length() == 0) snapHash = catalogHash(cached);
                        SectionsSnap snap = loadSectionsSnapshot(activity.getApplicationContext(), snapHash);
                        if (snap != null) {
                            CigramOrder.primeClaims(cached, snap.claims);
                            synchronized (PRE_LOCK) {
                                PRE_JSON = cached;
                                PRE_GROUPED = snap.grouped;
                                PRE_FAILED = false;
                            }
                            trace("snap" + snap.grouped.size());
                        }
                    } catch (Throwable error) {
                        android.util.Log.w("CigramStage2", "sections snapshot skipped", error);
                    }
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            if (!isAlive(activity)) return;
                            deliverCatalogIfChanged(activity, "movies_cache", cached, hash, true);
                            renderDynamicSections(activity, cached);
                            refreshOfflineBanner(activity);
                        }
                    });
                }
                if (!isValidCatalog(cached)) return;
                PreparedCatalog prepared = prepareCatalog(cached);
                String hash = catalogHash(prepared.displayJson);
                installCatalogInMemory(prepared.displayJson, prepared.searchIndexJson);
                prefs.edit()
                        .putString(KEY_JSON, prepared.displayJson)
                        .putString(KEY_JSON_HASH, hash)
                        .putString(KEY_SEARCH_JSON, prepared.searchIndexJson)
                        .apply();
                preloadCatalogImages(activity.getApplicationContext(), prepared.searchIndexJson);
            }
        });
    }

    private static void setupPullToRefresh(final Activity activity) {

        final View mainContent = findByIdName(activity, "linear1");
        if (mainContent == null) return;

        try {
            final Class<?> swipeClass =
                    Class.forName("androidx.swiperefreshlayout.widget.SwipeRefreshLayout");
            final Class<?> listenerClass =
                    Class.forName("androidx.swiperefreshlayout.widget.SwipeRefreshLayout$OnRefreshListener");

            View current = mainContent;
            Object existingSwipe = null;

            while (current != null) {
                android.view.ViewParent parent = current.getParent();
                if (parent == null) break;
                if (swipeClass.isInstance(parent)) {
                    existingSwipe = parent;
                    break;
                }
                if (!(parent instanceof View)) break;
                current = (View) parent;
            }

            if (existingSwipe != null) {
                final Object finalSwipe = existingSwipe;
                Object listener = Proxy.newProxyInstance(
                        listenerClass.getClassLoader(),
                        new Class<?>[]{listenerClass},
                        new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, Method method, Object[] args) {
                                if ("onRefresh".equals(method.getName())) {
                                    fetchAndApplyCatalog(activity, true, finalSwipe);
                                }
                                return null;
                            }
                        }
                );

                Method setListener = swipeClass.getMethod(
                        "setOnRefreshListener", listenerClass);
                setListener.invoke(finalSwipe, listener);

                try {
                    Method setColors = swipeClass.getMethod(
                            "setColorSchemeColors", int[].class);
                    setColors.invoke(finalSwipe,
                            new Object[]{new int[]{0xFFE21B14, 0xFFFFFFFF}});
                } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "invoke", ignored); }
                return;
            }
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "invoke", ignored); }

        setupPullFallback(activity, mainContent);
    }

    private static void setupPullFallback(final Activity activity, final View mainContent) {
        if (mainContent == null) return;
        synchronized (PULL_FALLBACK_SETUP) {
            if (Boolean.TRUE.equals(PULL_FALLBACK_SETUP.get(mainContent))) return;
            PULL_FALLBACK_SETUP.put(mainContent, Boolean.TRUE);
        }

        final float[] downY = new float[]{0f};
        final boolean[] fired = new boolean[]{false};
        final float threshold = dp(activity, 110f);

        mainContent.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event == null) return false;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downY[0] = event.getRawY();
                    fired[0] = false;
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float delta = event.getRawY() - downY[0];
                    if (!fired[0]
                            && delta >= threshold
                            && !v.canScrollVertically(-1)) {
                        fired[0] = true;
                        showTransientTopProgress(activity, true);
                        fetchAndApplyCatalog(activity, true, null);
                    }
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    fired[0] = false;
                }
                return false;
            }
        });
    }

    private static void fetchAndApplyCatalog(
            final Activity activity,
            final boolean manual,
            final Object swipeObject
    ) {
        if (!isAlive(activity)) return;

        if (manual) {
            setSwipeRefreshing(swipeObject, true);
            showTransientTopProgress(activity, true);
        }

        IO.execute(new Runnable() {
            @Override public void run() {
                final FetchResult result = fetchCatalog(activity);

                if (result.ok && isValidCatalog(result.body)) {
                    final long now = System.currentTimeMillis();
                    final PreparedCatalog prepared = prepareCatalog(result.body);
                    final String displayJson = prepared.displayJson;
                    final String searchIndexJson = prepared.searchIndexJson;
                    final String hash = catalogHash(displayJson);

                    installCatalogInMemory(displayJson, searchIndexJson);
                    catalogPrefs(activity).edit()
                            .putString(KEY_JSON, displayJson)
                            .putString(KEY_JSON_HASH, hash)
                            .putLong(KEY_UPDATED, now)
                            .putString(KEY_SEARCH_JSON, searchIndexJson)
                            .putLong(KEY_SEARCH_UPDATED, now)
                            .apply();
                    LAST_CATALOG_NETWORK_ATTEMPT_AT = now;
                    LAST_CATALOG_NETWORK_SUCCESS_AT = now;
                    preloadCatalogImages(activity.getApplicationContext(), searchIndexJson);

                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            setSwipeRefreshing(swipeObject, false);
                            showTransientTopProgress(activity, false);
                            if (!isAlive(activity)) return;
                            removeTagged(activity, "cigram_stage1_main_error");
                            removeTagged(activity, "cigram_offline_screen");
                            removeTagged(activity, "cigram_stage1_offline_banner");
                            boolean changed = deliverCatalogIfChanged(
                                    activity, "movies", displayJson, hash, false);
                            if (changed) {
                                renderDynamicSections(activity, displayJson);
                            }
                        }
                    });
                } else {
                    LAST_CATALOG_NETWORK_ATTEMPT_AT = System.currentTimeMillis();
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            setSwipeRefreshing(swipeObject, false);
                            showTransientTopProgress(activity, false);
                            if (!isAlive(activity)) return;

                            String cached = catalogPrefs(activity).getString(KEY_JSON, "");
                            boolean hasCache = isValidCatalog(cached);

                            if (manual && hasCache) {
                                Toast.makeText(activity, "تعذر التحديث", Toast.LENGTH_SHORT).show();
                                if (!isOnline(activity)) showOfflineBanner(activity);
                            } else if (hasCache) {
                                if (!isOnline(activity)) showOfflineBanner(activity);
                            } else {
                                showRetryError(
                                        activity,
                                        "cigram_stage1_main_error",
                                        result.userMessage,
                                        new Runnable() {
                                            @Override public void run() {
                                                fetchAndApplyCatalog(activity, false, null);
                                            }
                                        }
                                );
                            }
                        }
                    });
                }
            }
        });
    }

    private static boolean deliverCatalogToMain(Activity activity, String tag, String json) {
        try {
            Object listener = getFieldValue(activity, "_req_movies_request_listener");
            if (listener == null) { trace("noListener"); return false; }

            Method method = findMethod(listener.getClass(), "onResponse", 3);
            if (method == null) { trace("noMethod"); return false; }
            method.setAccessible(true);
            trace("deliver:" + tag);
            method.invoke(listener, tag, json, new HashMap<String, Object>());
            return true;
        } catch (Throwable error) {
            android.util.Log.w("CigramStage1", "Catalog delivery failed", error);
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            trace("deliverErr:" + cause.getClass().getSimpleName() + ":" + cause.getMessage());
            return false;
        }
    }

    private static void setupSearch(final Activity activity) {
        if (!isAlive(activity)) return;

        synchronized (SEARCH_SETUP) {
            if (Boolean.TRUE.equals(SEARCH_SETUP.get(activity))) {
                applySavedSearchIndex(activity);
                return;
            }
            SEARCH_SETUP.put(activity, Boolean.TRUE);
        }

        IO.execute(new Runnable() {
            @Override public void run() {
                final SharedPreferences prefs = catalogPrefs(activity);
                String index = prefs.getString(KEY_SEARCH_JSON, "");

                if (!isValidCatalog(index)) {
                    String cached = prefs.getString(KEY_JSON, "");
                    if (isValidCatalog(cached)) {
                        PreparedCatalog prepared = prepareCatalog(cached);
                        index = prepared.searchIndexJson;
                        prefs.edit()
                                .putString(KEY_JSON, prepared.displayJson)
                                .putString(KEY_SEARCH_JSON, index)
                                .apply();
                    }
                }

                if (isValidCatalog(index)) {
                    final String finalIndex = index;
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            if (!isAlive(activity)) return;
                            removeTagged(activity, "cigram_stage1_search_error");
                            applySearchIndexWithRetry(activity, finalIndex, 0);
                            refreshOfflineBanner(activity);
                        }
                    });
                    return;
                }

                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (!isAlive(activity)) return;
                        showLoadingMessage(
                                activity,
                                "cigram_stage1_search_error",
                                "جاري تحميل بيانات البحث..."
                        );
                    }
                });

                final FetchResult result = fetchCatalog(activity);
                if (result.ok && isValidCatalog(result.body)) {
                    final long now = System.currentTimeMillis();
                    final PreparedCatalog prepared = prepareCatalog(result.body);
                    prefs.edit()
                            .putString(KEY_JSON, prepared.displayJson)
                            .putLong(KEY_UPDATED, now)
                            .putString(KEY_SEARCH_JSON, prepared.searchIndexJson)
                            .putLong(KEY_SEARCH_UPDATED, now)
                            .apply();

                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            if (!isAlive(activity)) return;
                            removeTagged(activity, "cigram_stage1_search_error");
                            applySearchIndexWithRetry(
                                    activity, prepared.searchIndexJson, 0);
                        }
                    });
                } else {
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            if (!isAlive(activity)) return;
                            showRetryError(
                                    activity,
                                    "cigram_stage1_search_error",
                                    result.userMessage,
                                    new Runnable() {
                                        @Override public void run() {
                                            synchronized (SEARCH_SETUP) {
                                                SEARCH_SETUP.remove(activity);
                                            }
                                            setupSearch(activity);
                                        }
                                    }
                            );
                        }
                    });
                }
            }
        });
    }

    private static void applySavedSearchIndex(final Activity activity) {
        if (!isAlive(activity)) return;
        IO.execute(new Runnable() {
            @Override public void run() {
                SharedPreferences prefs = catalogPrefs(activity);
                String index = prefs.getString(KEY_SEARCH_JSON, "");
                if (!isValidCatalog(index)) {
                    String cached = prefs.getString(KEY_JSON, "");
                    if (isValidCatalog(cached)) {
                        PreparedCatalog prepared = prepareCatalog(cached);
                        index = prepared.searchIndexJson;
                        prefs.edit()
                                .putString(KEY_JSON, prepared.displayJson)
                                .putString(KEY_SEARCH_JSON, index)
                                .apply();
                    }
                }
                final String finalIndex = index;
                if (!isValidCatalog(finalIndex)) return;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (isAlive(activity)) {
                            applySearchIndexWithRetry(activity, finalIndex, 0);
                        }
                    }
                });
            }
        });
    }

    private static void applySearchIndexWithRetry(
            final Activity activity,
            final String indexJson,
            final int attempt
    ) {
        if (!isAlive(activity) || !isValidCatalog(indexJson)) return;

        boolean applied = false;
        try {
            setFieldValue(activity, "search_json", indexJson);

            View input = findByIdName(activity, "edittext_search");
            Object engine = input == null ? null : input.getTag();
            if (engine != null) {
                Method reload = findMethod(engine.getClass(), "reload", 0);
                Method queue = findMethod(engine.getClass(), "queue", 0);
                if (reload != null) {
                    reload.setAccessible(true);
                    reload.invoke(engine);
                }
                if (queue != null) {
                    queue.setAccessible(true);
                    queue.invoke(engine);
                }
                applied = reload != null || queue != null;
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramStage2", "Search index apply failed", error);
        }

        if (!applied && attempt < 8) {
            MAIN.postDelayed(new Runnable() {
                @Override public void run() {
                    applySearchIndexWithRetry(activity, indexJson, attempt + 1);
                }
            }, 180L);
        }
    }

    private static final String TAG_DYNAMIC_SECTIONS =
            "cigram_stage2_dynamic_sections";

    private static final android.util.LruCache<String, android.graphics.Bitmap>
            SECTION_IMAGE_MEMORY =
            new android.util.LruCache<String, android.graphics.Bitmap>(detailsImageCacheKb()) {
                @Override
                protected int sizeOf(String key, android.graphics.Bitmap value) {
                    if (value == null || value.isRecycled()) return 1;
                    try {
                        return Math.max(1, value.getByteCount() / 1024);
                    } catch (Throwable ignored) {
                        return Math.max(1, value.getRowBytes() * value.getHeight() / 1024);
                    }
                }
            };

    private static final class DynamicHomeSection {
        final String key;
        String id;
        String name;
        long rank;
        boolean keepOrder;   // items are already in the wanted order (best first)
        boolean noMore;      // programmatic section without a "more" page
        String style = "";   // v10.70 Netflix style: row | top10 | landscape | large | billboard (catalogue or Admin app)
        final java.util.LinkedHashMap<String, JSONObject> items =
                new java.util.LinkedHashMap<String, JSONObject>();
        /** Episodes gathered per item while the catalogue is read; written into the items once, at the end. */
        final java.util.HashMap<String, java.util.LinkedHashMap<String, JSONObject>> episodeAcc =
                new java.util.HashMap<String, java.util.LinkedHashMap<String, JSONObject>>();

        DynamicHomeSection(String value) {
            key = value;
        }
    }

    // ---- sections snapshot --------------------------------------------------------------------------
    // The home sections used to be rebuilt from the multi-MB catalogue on EVERY start (parse + group +
    // claims, several seconds), so they appeared ~10 s after the first rows and all at once. The result
    // is now kept in a small file keyed by the catalogue hash and drawn at once on the next start;
    // when the catalogue changes it is rebuilt and the file is refreshed.
    private static final String SECTIONS_SNAP_FILE = "cigram_sections_snapshot_v1.json";
    private static final int SECTIONS_SNAP_ITEMS = 80;
    private static final java.util.HashSet<String> SNAP_DROP = new java.util.HashSet<String>(
            java.util.Arrays.asList("series_episodes_json", "episodes", "seasons", "_search_normalized"));

    private static final class SectionsSnap {
        java.util.LinkedHashMap<String, DynamicHomeSection> grouped =
                new java.util.LinkedHashMap<String, DynamicHomeSection>();
        java.util.HashSet<String> claims = new java.util.HashSet<String>();
    }

    private static JSONObject trimForSnapshot(JSONObject item) throws Exception {
        JSONObject copy = new JSONObject();
        java.util.Iterator<String> it = item.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (SNAP_DROP.contains(k)) continue;
            copy.put(k, item.opt(k));
        }
        long stamp = CigramOrder.stampHint(item);   // keep "a series is as fresh as its newest episode"
        if (stamp > 0L) copy.put("created_at_ms", stamp);
        return copy;
    }

    private static void saveSectionsSnapshot(
            Context context, String catalogJson,
            java.util.LinkedHashMap<String, DynamicHomeSection> grouped) {
        if (context == null || grouped == null || catalogJson == null || catalogJson.length() == 0) return;
        java.io.File dir = context.getFilesDir();
        java.io.File target = new java.io.File(dir, SECTIONS_SNAP_FILE);
        java.io.File temp = new java.io.File(dir, SECTIONS_SNAP_FILE + ".tmp");
        java.io.FileOutputStream out = null;
        try {
            JSONObject root = new JSONObject();
            root.put("hash", catalogHash(catalogJson));
            java.util.HashSet<String> claims = new java.util.HashSet<String>();
            JSONArray sections = new JSONArray();
            for (DynamicHomeSection s : grouped.values()) {
                if (s == null || s.items.isEmpty()) continue;
                java.util.ArrayList<JSONObject> list = new java.util.ArrayList<JSONObject>(s.items.values());
                for (JSONObject item : list) CigramOrder.addClaimKeys(claims, item);
                if (!s.keepOrder) CigramOrder.sortJson(list);   // keep the NEWEST titles when capping
                JSONObject so = new JSONObject();
                so.put("key", s.key);
                so.put("id", s.id == null ? "" : s.id);
                so.put("name", s.name == null ? "" : s.name);
                so.put("rank", s.rank);
                so.put("style", s.style == null ? "" : s.style);
                so.put("keepOrder", s.keepOrder);
                so.put("noMore", s.noMore);
                JSONArray items = new JSONArray();
                for (int i = 0; i < list.size() && i < SECTIONS_SNAP_ITEMS; i++) {
                    items.put(trimForSnapshot(list.get(i)));
                }
                so.put("items", items);
                sections.put(so);
            }
            root.put("sections", sections);
            JSONArray claimArray = new JSONArray();
            for (String c : claims) claimArray.put(c);
            root.put("claims", claimArray);

            out = new java.io.FileOutputStream(temp, false);
            out.write(root.toString().getBytes("UTF-8"));
            out.flush();
            out.close();
            out = null;
            if (target.exists()) target.delete();
            if (!temp.renameTo(target)) throw new java.io.IOException("rename failed");
            CigramOrder.primeClaims(catalogJson, claims);
        } catch (Throwable error) {
            android.util.Log.w("CigramStage2", "sections snapshot not saved", error);
            try { if (temp.exists()) temp.delete(); } catch (Throwable ignored) { }
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
    }

    /** The saved sections for exactly this catalogue (hash), or null. Cheap: a few hundred KB. */
    private static SectionsSnap loadSectionsSnapshot(Context context, String expectedHash) {
        if (context == null || expectedHash == null || expectedHash.length() == 0) return null;
        java.io.File file = new java.io.File(context.getFilesDir(), SECTIONS_SNAP_FILE);
        if (!file.isFile() || file.length() < 20L || file.length() > 8L * 1024L * 1024L) return null;
        java.io.DataInputStream in = null;
        try {
            byte[] data = new byte[(int) file.length()];
            in = new java.io.DataInputStream(new java.io.FileInputStream(file));
            in.readFully(data);
            JSONObject root = new JSONObject(new String(data, "UTF-8"));
            if (!expectedHash.equals(root.optString("hash", ""))) return null;
            JSONArray sections = root.optJSONArray("sections");
            if (sections == null || sections.length() == 0) return null;
            SectionsSnap snap = new SectionsSnap();
            for (int i = 0; i < sections.length(); i++) {
                JSONObject so = sections.optJSONObject(i);
                if (so == null) continue;
                JSONArray items = so.optJSONArray("items");
                if (items == null || items.length() == 0) continue;
                DynamicHomeSection s = new DynamicHomeSection(so.optString("key", "snap:" + i));
                s.id = so.optString("id", "");
                s.name = so.optString("name", "");
                s.rank = so.optLong("rank", 0L);
                s.style = so.optString("style", "");
                s.keepOrder = so.optBoolean("keepOrder", false);
                s.noMore = so.optBoolean("noMore", false);
                for (int n = 0; n < items.length(); n++) {
                    JSONObject item = items.optJSONObject(n);
                    if (item == null) continue;
                    String key = dynamicDisplayItemKey(item);
                    if (key.length() == 0) key = "item:" + n;
                    s.items.put(key, item);
                }
                if (!s.items.isEmpty()) snap.grouped.put(s.key, s);
            }
            if (snap.grouped.isEmpty()) return null;
            JSONArray claimArray = root.optJSONArray("claims");
            for (int i = 0; claimArray != null && i < claimArray.length(); i++) {
                String c = claimArray.optString(i, "");
                if (c.length() > 0) snap.claims.add(c);
            }
            return snap;
        } catch (Throwable error) {
            android.util.Log.w("CigramStage2", "sections snapshot unreadable", error);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
    }

    private static String DYNAMIC_LAST_JSON;
    private static java.lang.ref.WeakReference<Activity> DYNAMIC_LAST_ACTIVITY;

    /** Registry order first (stable), then the sections the admin does not know; hidden ones are dropped. */
    private static void applyAdminSectionOrder(
            java.util.ArrayList<DynamicHomeSection> sections, final CigramHomeOrder.Plan plan) {
        for (int i = sections.size() - 1; i >= 0; i--) {
            DynamicHomeSection s = sections.get(i);
            if (plan.disabled(s.id, s.name) || plan.hiddenOnHome(s.id, s.name)) sections.remove(i);
        }
        java.util.Collections.sort(sections, new java.util.Comparator<DynamicHomeSection>() {
            @Override public int compare(DynamicHomeSection a, DynamicHomeSection b) {
                int ra = plan.rank(a.id, a.name);
                int rb = plan.rank(b.id, b.name);
                return ra == rb ? 0 : (ra < rb ? -1 : 1);
            }
        });
    }

    private static final java.util.WeakHashMap<View, Boolean> HIDDEN_BY_ORDER =
            new java.util.WeakHashMap<View, Boolean>();

    /** Gives the two fixed home rows back to the home layout before the block is rebuilt. */
    private static void restoreBuiltInHomeRows(Activity activity, LinearLayout home) {
        try {
            View block = home.findViewWithTag(TAG_DYNAMIC_SECTIONS);
            if (!(block instanceof ViewGroup) || block.getParent() != home) return;
            ViewGroup from = (ViewGroup) block;
            int pos = home.indexOfChild(block);
            String[] ids = {"linear_latest_movies_header", "hscroll1", "linear8", "hscroll_series"};
            for (String id : ids) {
                View v = findByIdName(activity, id);
                if (v == null || v.getParent() != from) continue;
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                from.removeView(v);
                home.addView(v, pos++, lp);
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramStage2", "restore home rows failed", error);
        }
    }

    private static final class OrderedRow {
        final int rank;
        final int seq;
        final java.util.ArrayList<View> views = new java.util.ArrayList<View>();
        OrderedRow(int rank, int seq) { this.rank = rank; this.seq = seq; }
    }

    /**
     * Fills {@code block} with every home section in the order the Admin app set: the dynamic
     * sections and the fixed «أحدث الأفلام» / «المسلسلات المميزة» rows. Rows the admin does not
     * know keep the app's own order after the registered ones; hidden ones are not drawn.
     */
    private static void fillBlockInAdminOrder(Activity activity, LinearLayout home, LinearLayout block,
                                              java.util.ArrayList<DynamicHomeSection> sections,
                                              CigramHomeOrder.Plan plan) throws Exception {
        java.util.ArrayList<OrderedRow> rows = new java.util.ArrayList<OrderedRow>();
        int seq = 0;
        for (DynamicHomeSection section : sections) {
            if (section.items.isEmpty()) continue;
            OrderedRow row = new OrderedRow(plan.rank(section.id, section.name), seq++);
            row.views.add(makeDynamicSectionView(activity, section));
            rows.add(row);
        }
        String[][] builtIns = {
                {"latest_movies", "أحدث الأفلام", "linear_latest_movies_header", "hscroll1"},
                {"featured_series", "المسلسلات المميزة", "linear8", "hscroll_series"},
        };
        for (String[] b : builtIns) {
            View first = directChildUnder(home, findByIdName(activity, b[2]));
            View second = directChildUnder(home, findByIdName(activity, b[3]));
            if (first == null || second == null) continue;
            boolean hide = plan.disabled(b[0], b[1]) || plan.hiddenOnHome(b[0], b[1]);
            if (hide) {
                first.setVisibility(View.GONE);
                second.setVisibility(View.GONE);
                HIDDEN_BY_ORDER.put(first, Boolean.TRUE);
                HIDDEN_BY_ORDER.put(second, Boolean.TRUE);
                continue;
            }
            if (HIDDEN_BY_ORDER.remove(first) != null) first.setVisibility(View.VISIBLE);
            if (HIDDEN_BY_ORDER.remove(second) != null) second.setVisibility(View.VISIBLE);
            OrderedRow row = new OrderedRow(plan.rank(b[0], b[1]), seq++);
            row.views.add(first);
            row.views.add(second);
            rows.add(row);
        }
        java.util.Collections.sort(rows, new java.util.Comparator<OrderedRow>() {
            @Override public int compare(OrderedRow a, OrderedRow b) {
                if (a.rank != b.rank) return a.rank < b.rank ? -1 : 1;
                return a.seq < b.seq ? -1 : (a.seq == b.seq ? 0 : 1);
            }
        });
        for (OrderedRow row : rows) {
            for (View v : row.views) {
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
                block.addView(v, lp != null ? lp : new LinearLayout.LayoutParams(-1, -2));
            }
        }
    }

    // Perf: parsing the whole catalogue (JSONTokener + section grouping) used to run right here on the
    // main thread, a multi-second freeze just after the first frame. It now runs on the IO pool and
    // this method is re-entered on the main thread with the finished result (identity-matched).
    private static final Object PRE_LOCK = new Object();
    private static String PRE_JSON;
    private static java.util.LinkedHashMap<String, DynamicHomeSection> PRE_GROUPED;
    private static boolean PRE_FAILED;

    private static void renderDynamicSections(final Activity activity, final String catalogJson) {
        if (!isAlive(activity) || catalogJson == null || catalogJson.trim().length() == 0) {
            return;
        }
        boolean havePre;
        synchronized (PRE_LOCK) {
            havePre = PRE_JSON == catalogJson && (PRE_GROUPED != null || PRE_FAILED);
        }
        if (!havePre && Looper.myLooper() == Looper.getMainLooper()) {
            IO.execute(new Runnable() {
                @Override public void run() {
                    java.util.LinkedHashMap<String, DynamicHomeSection> grouped = null;
                    boolean failed = false;
                    try {
                        Object root = new JSONTokener(catalogJson).nextValue();
                        grouped = new java.util.LinkedHashMap<String, DynamicHomeSection>();
                        collectDynamicSectionContent(root, "", null, grouped, 0);
                        saveSectionsSnapshot(activity.getApplicationContext(), catalogJson, grouped);
                    } catch (Throwable error) {
                        android.util.Log.w("CigramStage2", "background section parse failed", error);
                        grouped = null;
                        failed = true;
                    }
                    synchronized (PRE_LOCK) {
                        PRE_JSON = catalogJson;
                        PRE_GROUPED = grouped;
                        PRE_FAILED = failed;
                    }
                    MAIN.post(new Runnable() {
                        @Override public void run() { renderDynamicSections(activity, catalogJson); }
                    });
                }
            });
            return;
        }

        final View rootView = findByIdName(activity, "linear1");
        if (!(rootView instanceof LinearLayout)) return;
        final LinearLayout home = (LinearLayout) rootView;

        restoreBuiltInHomeRows(activity, home);
        removeTagged(activity, TAG_DYNAMIC_SECTIONS);

        try {
            java.util.LinkedHashMap<String, DynamicHomeSection> prepared = null;
            synchronized (PRE_LOCK) {
                if (PRE_JSON == catalogJson) {
                    prepared = PRE_GROUPED;
                    PRE_JSON = null;          // consumed once: sections are mutated while rendering
                    PRE_GROUPED = null;
                    PRE_FAILED = false;
                }
            }
            final java.util.LinkedHashMap<String, DynamicHomeSection> grouped;
            if (prepared != null) {
                grouped = prepared;
            } else {
                // fallback (background parse failed / called off the main thread): the original path
                Object root = new JSONTokener(catalogJson).nextValue();
                grouped = new java.util.LinkedHashMap<String, DynamicHomeSection>();
                collectDynamicSectionContent(root, "", null, grouped, 0);
            }

            if (grouped.isEmpty() && !CigramHomeOrder.plan(activity).any) return;

            java.util.ArrayList<DynamicHomeSection> sections =
                    new java.util.ArrayList<DynamicHomeSection>(grouped.values());

            java.util.Collections.sort(
                    sections,
                    new java.util.Comparator<DynamicHomeSection>() {
                        @Override
                        public int compare(DynamicHomeSection a, DynamicHomeSection b) {
                            if (a.rank == b.rank) return 0;
                            return a.rank < b.rank ? 1 : -1;
                        }
                    }
            );

            // v10.37: the Admin app decides the order (and what the home shows); the taste-based
            // promotion only applies while the admin has not registered any section.
            CigramHomeOrder.Plan adminPlan = CigramHomeOrder.plan(activity);
            if (adminPlan.any) applyAdminSectionOrder(sections, adminPlan);
            else promoteSectionsForUser(activity, sections);
            DYNAMIC_LAST_JSON = catalogJson;
            DYNAMIC_LAST_ACTIVITY = new java.lang.ref.WeakReference<Activity>(activity);
            final Runnable rerender = new Runnable() {
                @Override public void run() {
                    Activity a = DYNAMIC_LAST_ACTIVITY == null ? null : DYNAMIC_LAST_ACTIVITY.get();
                    if (a != null && isAlive(a) && DYNAMIC_LAST_JSON != null) {
                        renderDynamicSections(a, DYNAMIC_LAST_JSON);
                    }
                }
            };
            CigramHomeOrder.refresh(activity, rerender);
            CigramLayoutConfig.refresh(activity, rerender);   // v10.70: Netflix layout from the Admin app

            final LinearLayout block = new LinearLayout(activity);
            block.setTag(TAG_DYNAMIC_SECTIONS);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            block.setGravity(Gravity.RIGHT);
            block.setPadding(0, 0, 0, dpInt(activity, 8));

            // where the block goes: right above the fixed «أحدث الأفلام» row (found BEFORE any row moves)
            int insertAt = home.getChildCount();
            View latestHeader = findByIdName(activity, "linear_latest_movies_header");
            if (latestHeader != null) {
                View direct = directChildUnder(home, latestHeader);
                if (direct != null) {
                    int index = home.indexOfChild(direct);
                    if (index >= 0) insertAt = index;
                }
            }

            if (adminPlan.any) {
                // v10.37: ONE ordered list for every section, the two fixed rows included
                fillBlockInAdminOrder(activity, home, block, sections, adminPlan);
            } else {
                // v18.2: build EVERY section's header + first screenful in one pass, so the whole
                // catalogue appears together instead of popping in section-by-section. Each row still
                // fills its remaining cards in small batches inside makeDynamicSectionView, and the
                // per-card build is now cheap (cached view ids), so this no longer freezes the first
                // frame the way the original all-at-once build did.
                for (DynamicHomeSection section : sections) {
                    if (section.items.isEmpty()) continue;
                    try {
                        block.addView(
                                makeDynamicSectionView(activity, section),
                                new LinearLayout.LayoutParams(-1, -2)
                        );
                    } catch (Throwable error) {
                        android.util.Log.w("CigramStage2", "section skipped", error);
                    }
                }
            }

            if (block.getChildCount() == 0) return;

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            home.addView(block, Math.max(0, Math.min(insertAt, home.getChildCount())), lp);
            // v10.70: sections arrive softly instead of popping in
            block.setAlpha(0f);
            block.animate().alpha(1f).setDuration(220L).start();

            installNetflixTop(activity, home, sections);

        } catch (Throwable error) {
            android.util.Log.w("CigramStage2", "Dynamic sections render failed", error);
        }
    }

    /**
     * v10.70: Netflix top of the home page: the chips under the header and, when the Admin app turned
     * it on, the billboard (which then replaces the old hero carousel). Rebuilt on every render so a
     * change in the Admin app shows at once.
     */
    private static void installNetflixTop(Activity activity, LinearLayout home,
                                          java.util.List<DynamicHomeSection> sections) {
        try {
            CigramNetflix.removeTagged(home, CigramNetflix.TAG_CHIPS);
            CigramNetflix.removeTagged(home, CigramNetflix.TAG_BILLBOARD + "_top");
            int at = 0;
            View header = findByIdName(activity, "linear7");
            View direct = header == null ? null : directChildUnder(home, header);
            if (direct != null) at = home.indexOfChild(direct) + 1;

            View chips = CigramNetflix.chips(activity);
            if (chips != null) {
                home.addView(chips, Math.min(at, home.getChildCount()), new LinearLayout.LayoutParams(-1, -2));
                at++;
            }

            JSONObject cfg = CigramLayoutConfig.billboard(activity);
            JSONObject item = null;
            if (cfg != null) {
                String id = cfg.optString("item_id", "").trim();
                if (id.length() > 0) item = findCatalogMemoryItem(cfg.optString("type", ""), id, cfg.optString("title", ""));
                String sid = cfg.optString("section_id", "").trim();
                if (item == null && sid.length() > 0 && sections != null) {
                    for (DynamicHomeSection s : sections) {
                        if ((sid.equalsIgnoreCase(s.id) || sid.equalsIgnoreCase(s.name)) && !s.items.isEmpty()) {
                            item = s.items.values().iterator().next();
                            break;
                        }
                    }
                }
            }
            View carousel = home.findViewWithTag("cigram_admin_hero_carousel");
            View billboard = item == null ? null : CigramNetflix.billboard(activity, item);
            if (billboard != null) {
                billboard.setTag(CigramNetflix.TAG_BILLBOARD + "_top");
                home.addView(billboard, Math.min(at, home.getChildCount()), new LinearLayout.LayoutParams(-1, -2));
                if (carousel != null) carousel.setVisibility(View.GONE);
            } else if (carousel != null) {
                carousel.setVisibility(View.VISIBLE);
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramNetflix", "top of home failed", error);
        }
    }

    // ---- v10.70 public bridges for CigramNetflix
    public static void cigramPlayTitle(Activity activity, JSONObject item) {
        openPlayerForTitleItem(activity, item, null, false);
    }

    public static boolean cigramToggleFavorite(Context context, JSONObject item) {
        return toggleDetailsFavorite(context, item);
    }

    public static boolean cigramIsFavorite(Context context, JSONObject item) {
        return isDetailsFavorite(context, item);
    }

    private static void collectDynamicSectionContent(
            Object node,
            String inheritedType,
            JSONObject parentSection,
            java.util.LinkedHashMap<String, DynamicHomeSection> grouped,
            int depth
    ) throws Exception {
        try {
            collectDynamicSectionContentInner(node, inheritedType, parentSection, grouped, depth);
        } finally {
            if (depth == 0) finalizeDynamicSections(grouped);
        }
    }

    /** Writes each series' gathered episodes (sorted) into its item, once per catalogue read. */
    private static void finalizeDynamicSections(
            java.util.LinkedHashMap<String, DynamicHomeSection> grouped) {
        if (grouped == null) return;
        for (DynamicHomeSection section : grouped.values()) {
            if (section == null || section.episodeAcc.isEmpty()) continue;
            for (java.util.Map.Entry<String, java.util.LinkedHashMap<String, JSONObject>> entry
                    : section.episodeAcc.entrySet()) {
                try {
                    JSONObject target = section.items.get(entry.getKey());
                    if (target == null || entry.getValue().isEmpty()) continue;
                    java.util.ArrayList<JSONObject> episodeList =
                            new java.util.ArrayList<JSONObject>(entry.getValue().values());
                    java.util.Collections.sort(
                            episodeList,
                            new java.util.Comparator<JSONObject>() {
                                @Override
                                public int compare(JSONObject a, JSONObject b) {
                                    int sa = intText(a, "season", "season_number");
                                    int sb = intText(b, "season", "season_number");
                                    if (sa != sb) return sa < sb ? -1 : 1;
                                    int ea = intText(a, "episode", "episode_number", "ep", "number");
                                    int eb = intText(b, "episode", "episode_number", "ep", "number");
                                    return ea < eb ? -1 : (ea == eb ? 0 : 1);
                                }
                            }
                    );
                    JSONArray episodes = new JSONArray();
                    for (JSONObject episode : episodeList) episodes.put(episode);
                    target.put("series_episodes_json", episodes.toString());
                } catch (Throwable error) {
                    android.util.Log.w("CigramStage2", "episode merge failed", error);
                }
            }
            section.episodeAcc.clear();
        }
    }

    private static void collectDynamicSectionContentInner(
            Object node,
            String inheritedType,
            JSONObject parentSection,
            java.util.LinkedHashMap<String, DynamicHomeSection> grouped,
            int depth
    ) throws Exception {
        if (node == null || node == JSONObject.NULL || depth > 14) return;

        if (node instanceof String) {
            String text = ((String) node).trim();
            if (text.startsWith("[") || text.startsWith("{")) {
                try {
                    collectDynamicSectionContentInner(
                            new JSONTokener(text).nextValue(),
                            inheritedType,
                            parentSection,
                            grouped,
                            depth + 1
                    );
                } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "compare", ignored); }
            }
            return;
        }

        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collectDynamicSectionContentInner(
                        array.opt(i), inheritedType, parentSection, grouped, depth + 1);
            }
            return;
        }

        if (!(node instanceof JSONObject)) return;
        JSONObject object = (JSONObject) node;

        boolean hasCatalogContainer = false;
        for (String key : new String[]{
                "movies", "series", "items", "data", "results",
                "content", "sections", "catalog", "entries"
        }) {
            Object child = object.opt(key);
            if (child instanceof JSONArray || child instanceof JSONObject) {
                hasCatalogContainer = true;
                break;
            }
            if (child instanceof String) {
                String text = ((String) child).trim();
                if (text.startsWith("[") || text.startsWith("{")) {
                    hasCatalogContainer = true;
                    break;
                }
            }
        }

        boolean looksLikeContent = !hasCatalogContainer
                && jsonText(
                    object,
                    "title", "series_title", "name",
                    "title_ar", "title_en", "original_title"
                ).length() > 0
                && (object.has("id") || object.has("series_id")
                    || object.has("poster") || object.has("video")
                    || object.has("link") || object.has("type")
                    || object.has("episodes") || object.has("seasons")
                    || object.has("series_episodes_json"));

        if (looksLikeContent) {
            JSONObject item = normalizeSearchItem(object, inheritedType, parentSection);
            addDynamicSectionItem(grouped, item);
            return;
        }

        JSONObject sectionContext = parentSection;
        if (object.has("section_id") || object.has("section_name")) {
            sectionContext = object;
        } else if (hasCatalogContainer
                && object.has("id")
                && (object.has("name") || object.has("title"))) {

            sectionContext = object;
        }

        for (String key : new String[]{
                "movies", "series", "items", "data", "results",
                "content", "sections", "catalog", "entries"
        }) {
            if (!object.has(key)) continue;
            Object child = object.opt(key);
            String nextType = inheritedType;
            if ("movies".equals(key)) nextType = "movie";
            else if ("series".equals(key)) nextType = "series";

            collectDynamicSectionContentInner(
                    child, nextType, sectionContext, grouped, depth + 1);
        }
    }

    private static void addDynamicSectionItem(
            java.util.LinkedHashMap<String, DynamicHomeSection> grouped,
            JSONObject item
    ) throws Exception {
        if (item == null) return;

        String sectionId = jsonText(item, "section_id", "sectionId");
        String sectionName = jsonText(item, "section_name", "sectionName");
        if (sectionId.length() == 0 && sectionName.length() == 0) return;
        if (isBuiltInHomeSection(sectionId, sectionName)) return;

        String sectionKey = sectionId.length() > 0
                ? "id:" + normalizeSearch(sectionId)
                : "name:" + normalizeSearch(sectionName);

        DynamicHomeSection section = grouped.get(sectionKey);
        if (section == null) {
            section = new DynamicHomeSection(sectionKey);
            section.id = sectionId;
            section.name = sectionName.length() > 0 ? sectionName : sectionId;
            grouped.put(sectionKey, section);
        }
        if (section.style.length() == 0) section.style = CigramLayoutConfig.normStyle(jsonText(item, "section_style"));

        long explicit = sectionExplicitRank(item);
        section.rank = Math.max(
                section.rank,
                explicit > 0L ? explicit : contentRecency(item)
        );

        String itemKey = dynamicDisplayItemKey(item);
        if (itemKey.length() == 0) {
            itemKey = "item:" + section.items.size() + ":" + contentRecency(item);
        }

        JSONObject old = section.items.get(itemKey);
        if (old == null) {
            section.items.put(itemKey, new JSONObject(item.toString()));
        } else {
            JSONObject merged = mergeSearchItems(old, item);

            if ("series".equals(inferContentType(merged, ""))) {
                // Do NOT rebuild the whole episode list for every extra row of the same series: for a
                // series with hundreds of episode rows that was quadratic (parse + sort + serialise a
                // growing list each time) and took minutes on a 3 MB catalogue, so the claims / sections
                // never finished and Home stayed empty. Gather here, write once in finalizeDynamicSections.
                java.util.LinkedHashMap<String, JSONObject> acc = section.episodeAcc.get(itemKey);
                if (acc == null) {
                    acc = new java.util.LinkedHashMap<String, JSONObject>();
                    collectEpisodesFromItem(old, acc);
                    section.episodeAcc.put(itemKey, acc);
                }
                collectEpisodesFromItem(item, acc);
            }

            section.items.put(itemKey, merged);
        }
    }

    private static String dynamicDisplayItemKey(JSONObject item) {
        if (item == null) return "";
        String type = inferContentType(item, "");

        if ("series".equals(type)) {
            String seriesId = normalizeSearch(jsonText(item, "series_id"));
            if (seriesId.length() > 0) return "series|id|" + seriesId;

            String title = normalizeSearch(jsonText(
                    item, "series_title", "title", "title_ar",
                    "title_en", "original_title", "name"));
            String year = normalizeSearch(jsonText(item, "year"));
            return title.length() == 0 ? "" : "series|" + title + "|" + year;
        }

        String id = normalizeSearch(jsonText(item, "id", "movie_id", "item_id", "content_id"));
        String title = normalizeSearch(jsonText(
                item, "title", "title_ar", "title_en", "original_title", "name"));
        String video = normalizeSearch(jsonText(
                item, "video", "link", "video_url", "movie_url", "stream_url", "play_url"));
        String poster = normalizeSearch(jsonText(item, "poster", "image", "backdrop"));

        String key = "movie|" + id + "|" + title + "|" + video;
        if ((id + title + video).length() == 0) key += "|" + poster;
        return key;
    }

    /**
     * v10.67: identity keys of every title that sits in a CUSTOM section (anime, ...). The fixed
     * «أحدث الأفلام» / «المسلسلات المميزة» rows skip them, so one title lives in one section only.
     */
    public static java.util.HashSet<String> customSectionClaims(String catalogJson) {
        java.util.HashSet<String> out = new java.util.HashSet<String>();
        if (catalogJson == null || catalogJson.trim().length() == 0) return out;
        try {
            Object root = new JSONTokener(catalogJson).nextValue();
            java.util.LinkedHashMap<String, DynamicHomeSection> grouped =
                    new java.util.LinkedHashMap<String, DynamicHomeSection>();
            collectDynamicSectionContent(root, "", null, grouped, 0);
            for (DynamicHomeSection section : grouped.values()) {
                if (section == null || section.items == null) continue;
                for (JSONObject item : section.items.values()) CigramOrder.addClaimKeys(out, item);
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramStage2", "claims failed", error);
        }
        return out;
    }

    public static String getSectionItemsJson(
            String catalogJson,
            String targetSectionId,
            String targetSectionName
    ) {
        if (catalogJson == null || catalogJson.trim().length() == 0) return "[]";

        try {
            Object root = new JSONTokener(catalogJson).nextValue();
            java.util.LinkedHashMap<String, DynamicHomeSection> grouped =
                    new java.util.LinkedHashMap<String, DynamicHomeSection>();
            collectDynamicSectionContent(root, "", null, grouped, 0);

            String idKey = targetSectionId == null
                    ? ""
                    : "id:" + normalizeSearch(targetSectionId);
            String nameKey = targetSectionName == null
                    ? ""
                    : "name:" + normalizeSearch(targetSectionName);

            DynamicHomeSection section = null;
            if (idKey.length() > 3) section = grouped.get(idKey);
            if (section == null && nameKey.length() > 5) section = grouped.get(nameKey);

            if (section == null) {
                String targetId = normalizeSearch(targetSectionId);
                String targetName = normalizeSearch(targetSectionName);
                for (DynamicHomeSection candidate : grouped.values()) {
                    if (candidate == null) continue;
                    if ((targetId.length() > 0
                            && targetId.equals(normalizeSearch(candidate.id)))
                            || (targetName.length() > 0
                            && targetName.equals(normalizeSearch(candidate.name)))) {
                        section = candidate;
                        break;
                    }
                }
            }

            if (section == null || section.items.isEmpty()) return "[]";

            java.util.ArrayList<JSONObject> items =
                    new java.util.ArrayList<JSONObject>(section.items.values());
            CigramOrder.sortJson(items);   // v10.67: newest first (date, then numeric id, then first seen)

            JSONArray output = new JSONArray();
            for (JSONObject item : items) output.put(item);
            return output.toString();
        } catch (Throwable error) {
            android.util.Log.w("CigramStage2", "Section extraction failed", error);
            return "[]";
        }
    }

    private static boolean isBuiltInHomeSection(String id, String name) {
        String normalizedId = normalizeSearch(id);
        String normalizedName = normalizeSearch(name);
        return "latest movies".equals(normalizedId)
                || "latest_movies".equals(id)
                || "featured series".equals(normalizedId)
                || "featured_series".equals(id)
                || "احدث الافلام".equals(normalizedName)
                || "المسلسلات المميزه".equals(normalizedName);
    }

    private static String dynamicSectionType(DynamicHomeSection section) {
        if (section == null || section.items == null || section.items.isEmpty()) return "";

        String detected = "";
        for (JSONObject item : section.items.values()) {
            if (item == null) continue;
            String type = inferContentType(item, "");
            if (type.length() == 0) continue;
            if (detected.length() == 0) {
                detected = type;
            } else if (!detected.equals(type)) {
                return "";
            }
        }
        return detected;
    }

    private static View directChildUnder(ViewGroup parent, View child) {
        if (parent == null || child == null) return null;
        View current = child;
        while (current != null) {
            android.view.ViewParent p = current.getParent();
            if (p == parent) return current;
            if (!(p instanceof View)) return null;
            current = (View) p;
        }
        return null;
    }

    /** Programmatic home row with exactly the look of the dynamic sections. */
    public static View cigramSectionView(Activity activity, String title,
                                         java.util.List<JSONObject> items) {
        try {
            DynamicHomeSection section = new DynamicHomeSection("cigram_v23_" + title);
            section.id = "";
            section.name = title;
            section.keepOrder = true;
            section.noMore = true;
            for (int i = 0; i < items.size(); i++) {
                section.items.put("k" + i, items.get(i));
            }
            return makeDynamicSectionView(activity, section);
        } catch (Throwable error) {
            android.util.Log.w("CigramHome", "section view failed", error);
            return null;
        }
    }

    private static void promoteSectionsForUser(
            Context context,
            java.util.ArrayList<DynamicHomeSection> sections
    ) {
        try {
            if (!CigramHome.personalizationReady(context) || sections.size() < 3) return;
            java.util.ArrayList<DynamicHomeSection> promoted = new java.util.ArrayList<DynamicHomeSection>();
            for (int i = 0; i < sections.size() && promoted.size() < 2; i++) {
                DynamicHomeSection candidate = sections.get(i);
                if (i < 2) continue;   // already near the top
                if (CigramHome.sectionScore(context, candidate.items.values()) >= 0.6d) {
                    promoted.add(candidate);
                }
            }
            for (int i = promoted.size() - 1; i >= 0; i--) {
                sections.remove(promoted.get(i));
                sections.add(0, promoted.get(i));
            }
        } catch (Throwable ignored) { }
    }

    private static View makeDynamicSectionView(
            final Activity activity,
            final DynamicHomeSection section
    ) throws Exception {
        // v10.70: the Admin app may restyle / rename any section (CigramLayoutConfig, ADMIN-LAYOUT.md)
        String adminTitle = CigramLayoutConfig.titleFor(activity, section.id, section.name);
        if (adminTitle != null) section.name = adminTitle;
        String style = CigramLayoutConfig.styleFor(activity, section.id, section.name);
        if (style.length() == 0) style = section.style;
        if ("billboard".equals(style) && !section.items.isEmpty()) {
            View bb = CigramNetflix.billboard(activity, section.items.values().iterator().next());
            if (bb != null) return bb;
        }

        final LinearLayout outer = new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        outer.setGravity(Gravity.RIGHT);
        outer.setPadding(0, dpInt(activity, 8), 0, dpInt(activity, 12));

        // v4.4: header = [«المزيد» pill][title][red accent bar], same as the built-in home headers
        // and the Channels page (CigramDesign). Same «المزيد» click target and extras as before.
        TextView more = new TextView(activity);
        more.setText("المزيد");
        more.setClickable(true);
        more.setFocusable(true);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    android.content.Intent intent = new android.content.Intent();
                    intent.setClassName(
                            activity,
                            activity.getPackageName() + ".SectionActivity"
                    );

                    String launchSectionId =
                            section.id == null ? "" : section.id.trim();
                    String launchSectionTitle =
                            section.name == null ? "" : section.name.trim();

                    intent.putExtra("section_id", launchSectionId);
                    intent.putExtra("section_title", launchSectionTitle);
                    intent.putExtra("section_type", dynamicSectionType(section));

                    intent.putExtra("section_name", launchSectionTitle);

                    activity.startActivity(intent);
                    activity.overridePendingTransition(
                            android.R.anim.fade_in,
                            android.R.anim.fade_out
                    );
                } catch (Throwable error) {
                    Toast.makeText(
                            activity,
                            "تعذر فتح القسم",
                            Toast.LENGTH_SHORT
                    ).show();
                }
            }
        });

        LinearLayout header = CigramDesign.sectionHeader(
                activity, section.name == null ? "" : section.name, section.noMore ? null : more);
        outer.addView(header, new LinearLayout.LayoutParams(-1, dpInt(activity, 52)));

        if ("top10".equals(style) || "landscape".equals(style) || "large".equals(style)) {
            java.util.ArrayList<JSONObject> styled = new java.util.ArrayList<JSONObject>(section.items.values());
            if (!section.keepOrder && !"top10".equals(style)) CigramOrder.sortJson(styled);
            View row = "top10".equals(style) ? CigramNetflix.top10Row(activity, styled)
                    : "landscape".equals(style) ? CigramNetflix.landscapeRow(activity, styled)
                    : CigramNetflix.largeRow(activity, styled);
            outer.addView(row, new LinearLayout.LayoutParams(-1, -2));
            return outer;
        }

        final android.widget.HorizontalScrollView scroll =
                new android.widget.HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        scroll.setFillViewport(true);
        // v4.4: explicit LTR. Cards are still prepended (index 0) and the row is still scrolled to
        // FOCUS_RIGHT, so the first/newest card is at the far RIGHT. Before, the row asked for RTL,
        // which the app ignores today (no supportsRtl); had it ever been honoured the order would
        // have flipped. Now the result is the same in both cases.
        scroll.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        final LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.RIGHT | Gravity.TOP);
        row.setPadding(dpInt(activity, 12), 0, dpInt(activity, 12), 0);
        final int cardWidth = CigramDesign.cardWidth(activity);

        final java.util.ArrayList<JSONObject> content =
                new java.util.ArrayList<JSONObject>(section.items.values());
        if (!section.keepOrder) CigramOrder.sortJson(content);   // v10.67: a new title is always the first card (far right)

        // Perf: a row used to inflate EVERY card of its section in one go on the main thread (hundreds of
        // layout inflations = the long freeze after the first frame). Now the first screenful is built
        // at once and the rest follows in small batches, one every ~2 frames, so touch / scroll / the
        // other rows stay responsive and content is on screen as soon as possible.
        final int firstBatch = Math.min(6, content.size());
        for (int i = 0; i < firstBatch; i++) addDynamicRowCard(activity, row, content.get(i), cardWidth);

        if (content.size() > firstBatch) {
            final int[] next = {firstBatch};
            final int cardSlot = cardWidth + 2 * dpInt(activity, 4);
            final Runnable step = new Runnable() {
                @Override public void run() {
                    if (!isAlive(activity) || outer.getParent() == null) return;   // page closed / section re-rendered
                    if (homeBusyScrolling()) { MAIN.postDelayed(this, 90L); return; }   // yield the main thread while the user scrolls
                    int end = Math.min(content.size(), next[0] + 2);
                    int added = 0;
                    final CigramRowAnchor.Mark anchorBefore = CigramRowAnchor.mark(scroll);
                    for (; next[0] < end; next[0]++) {
                        try {
                            addDynamicRowCard(activity, row, content.get(next[0]), cardWidth);
                            added++;
                        } catch (Throwable error) {
                            android.util.Log.w("CigramStage2", "row card failed", error);
                        }
                    }
                    if (added > 0) {
                        // cards are added on the LEFT: the newest stays at the far right until the user scrolls
                        CigramRowAnchor.restore(scroll, anchorBefore);
                    }
                    if (next[0] < content.size()) MAIN.postDelayed(this, 75L);
                }
            };
            MAIN.postDelayed(step, 150L);
        }

        scroll.addView(
                row,
                new android.widget.HorizontalScrollView.LayoutParams(-2, -2)
        );
        outer.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        CigramRowAnchor.reset(scroll);
        CigramRowAnchor.restore(scroll, null);   // newest card at the far right as soon as the row is laid out

        return outer;
    }

    private static void addDynamicRowCard(Activity activity, LinearLayout row, JSONObject item, int cardWidth)
            throws Exception {
        View card = makeDynamicCard(activity, item);
        LinearLayout.LayoutParams cardLp =
                new LinearLayout.LayoutParams(cardWidth, -2);     // v4.4: adapts to the screen
        cardLp.setMargins(dpInt(activity, 4), 0, dpInt(activity, 4), 0);
        row.addView(card, 0, cardLp);
    }

    // Card view ids resolved ONCE per process. getResources().getIdentifier() is a slow string
    // lookup in the resource table; doing it 5× for every card (the layout + 4 fields) added up to a
    // visible cost while building the home rows. -1 = not resolved yet, 0 = not found (use fallback).
    private static volatile int CARD_LAYOUT_ID = -1;
    private static volatile int CARD_ID_POSTER = 0;
    private static volatile int CARD_ID_TITLE = 0;
    private static volatile int CARD_ID_IMDB = 0;
    private static volatile int CARD_ID_YEARCAT = 0;

    // While the user is actively touching / scrolling Home, the staggered card building yields the
    // main thread so the gesture stays at full frame rate (this is what kept the first few seconds
    // janky: layout passes from the drip-fed cards fought the scroll). The window auto-expires a
    // moment after the last scroll event, so building resumes as soon as the finger rests. No content
    // is lost: the first screenful of every row is already on screen; only the off-screen tail waits.
    private static volatile long HOME_INTERACT_UNTIL_MS = 0L;

    /** Called from the Home scroll / touch listeners: hold off background building for a short window. */
    public static void markHomeInteracting() {
        HOME_INTERACT_UNTIL_MS = android.os.SystemClock.uptimeMillis() + 320L;
    }

    /** True while a scroll / touch gesture is in flight on Home (the background builders then yield). */
    public static boolean homeBusyScrolling() {
        return android.os.SystemClock.uptimeMillis() < HOME_INTERACT_UNTIL_MS;
    }

    private static void ensureCardIds(Context context) {
        if (CARD_LAYOUT_ID != -1) return;
        try {
            android.content.res.Resources res = context.getResources();
            String pkg = context.getPackageName();
            CARD_ID_POSTER = res.getIdentifier("imageview_poster", "id", pkg);
            CARD_ID_TITLE = res.getIdentifier("textview_title", "id", pkg);
            CARD_ID_IMDB = res.getIdentifier("textview_item_imdb", "id", pkg);
            CARD_ID_YEARCAT = res.getIdentifier("textview_item_year_category", "id", pkg);
            CARD_LAYOUT_ID = res.getIdentifier("movie_item", "layout", pkg);
        } catch (Throwable ignored) {
            CARD_LAYOUT_ID = 0;   // fall back to the hand-built card
        }
    }

    private static View makeDynamicCard(final Activity activity, final JSONObject item)
            throws Exception {
        ensureCardIds(activity);
        int layoutId = CARD_LAYOUT_ID;
        if (layoutId == 0) {

            return makeDynamicCardFallback(activity, item);
        }

        final View cell = activity.getLayoutInflater().inflate(layoutId, null, false);

        final android.widget.ImageView poster =
                (android.widget.ImageView) cell.findViewById(CARD_ID_POSTER);
        final TextView name =
                (TextView) cell.findViewById(CARD_ID_TITLE);
        final TextView imdb =
                (TextView) cell.findViewById(CARD_ID_IMDB);
        final TextView yearCategory =
                (TextView) cell.findViewById(CARD_ID_YEARCAT);

        if (poster == null || name == null || imdb == null || yearCategory == null) {
            return makeDynamicCardFallback(activity, item);
        }

        final Typeface font = cigramTypeface(activity);
        name.setTypeface(font, Typeface.BOLD);
        imdb.setTypeface(font);
        yearCategory.setTypeface(font);

        String titleText = jsonText(
                item,
                "series_title", "title", "title_ar", "title_en", "original_title", "name");
        if (titleText.length() == 0) titleText = "بدون عنوان";
        name.setText(titleText);

        String rating = jsonText(item, "imdb", "rating", "imdb_rating", "vote_average", "score");
        imdb.setText(rating.length() == 0 ? "--" : rating);

        String year = jsonText(item, "year", "release_year");
        String category = jsonText(item, "category", "genre", "genres");
        yearCategory.setText(
                year
                        + ((year.length() == 0 || category.length() == 0) ? "" : "، ")
                        + category
        );
        yearCategory.setBackground(null);
        yearCategory.setTextColor(0xFF707980);
        yearCategory.setShadowLayer(0f, 0f, 0f, 0x00000000);

        GradientDrawable posterBg = new GradientDrawable();
        posterBg.setColor(0xFF0E0E10);
        posterBg.setCornerRadius(dp(activity, 14f));
        poster.setBackground(posterBg);
        if (Build.VERSION.SDK_INT >= 21) poster.setClipToOutline(true);
        poster.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        poster.setAdjustViewBounds(true);
        try {
            // Perf: give the poster its final 2:3 height up front. Without it every card was measured
            // twice (once with the sample height, once after the layout listener below fixed it).
            ViewGroup.LayoutParams plp = poster.getLayoutParams();
            if (plp != null) plp.height = Math.round(CigramDesign.cardWidth(activity) * 1.5f);
        } catch (Throwable ignored) { }

        String posterUrl = jsonText(
                item,
                "poster", "series_poster", "season_poster", "image", "backdrop");
        loadDynamicPoster(activity, posterUrl, poster);

        poster.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(
                    View v,
                    int left,
                    int top,
                    int right,
                    int bottom,
                    int oldLeft,
                    int oldTop,
                    int oldRight,
                    int oldBottom
            ) {
                int width = right - left;
                if (width <= 0 || v.getLayoutParams() == null) return;
                int wanted = Math.round(width * 1.5f);
                if (wanted > 0 && v.getLayoutParams().height != wanted) {
                    ViewGroup.LayoutParams lp = v.getLayoutParams();
                    lp.height = wanted;
                    v.setLayoutParams(lp);
                }
            }
        });

        final String finalTitle = titleText;
        attachDetailsWarmTouch(activity, cell, item);
        cell.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View clicked) {
                openDynamicDetails(activity, item, finalTitle, clicked);
            }
        });

        try { CigramHome.decorateCard(activity, cell, item); } catch (Throwable ignored) { }
        CigramDesign.styleMovieCard(cell);     // v4.4: same card look as the MainActivity rows
        return cell;
    }

    private static View findInsideByIdName(View root, Context context, String idName) {
        if (root == null || context == null || idName == null) return null;
        try {
            int id = context.getResources().getIdentifier(idName, "id", context.getPackageName());
            return id == 0 ? null : root.findViewById(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static View makeDynamicCardFallback(final Activity activity, final JSONObject item)
            throws Exception {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.RIGHT);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        card.setClickable(true);
        card.setFocusable(true);

        final android.widget.ImageView image = new android.widget.ImageView(activity);
        image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(true);
        image.setBackground(roundedDrawable(0xFF0E0E10, dp(activity, 14f)));
        if (Build.VERSION.SDK_INT >= 21) image.setClipToOutline(true);
        card.addView(image, new LinearLayout.LayoutParams(-1, dpInt(activity, 168)));

        String titleText = jsonText(
                item,
                "series_title", "title", "title_ar", "title_en", "original_title", "name");
        if (titleText.length() == 0) titleText = "بدون عنوان";

        String rating = jsonText(item, "imdb", "rating", "imdb_rating", "vote_average", "score");
        TextView imdb = new TextView(activity);
        imdb.setText(imdbLogoText(activity, rating.length() == 0 ? "--" : rating, 13f));
        imdb.setTextColor(Color.WHITE);
        imdb.setTextSize(13f);
        imdb.setGravity(Gravity.CENTER);
        imdb.setSingleLine(true);
        imdb.setTypeface(cigramTypeface(activity), Typeface.BOLD);
        LinearLayout.LayoutParams imdbLp = new LinearLayout.LayoutParams(-1, -2);
        imdbLp.topMargin = dpInt(activity, 5);
        card.addView(imdb, imdbLp);

        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextColor(Color.WHITE);
        title.setTextSize(14f);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTypeface(cigramTypeface(activity), Typeface.BOLD);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        String year = jsonText(item, "year", "release_year");
        String category = jsonText(item, "category", "genre", "genres");
        TextView meta = new TextView(activity);
        meta.setText(year + ((year.length() == 0 || category.length() == 0) ? "" : "، ") + category);
        meta.setTextColor(0xFF707980);
        meta.setTextSize(11f);
        meta.setGravity(Gravity.CENTER);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.setTypeface(cigramTypeface(activity));
        card.addView(meta, new LinearLayout.LayoutParams(-1, -2));

        String poster = jsonText(item, "poster", "series_poster", "season_poster", "image", "backdrop");
        loadDynamicPoster(activity, poster, image);

        final String finalTitle = titleText;
        attachDetailsWarmTouch(activity, card, item);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View clicked) {
                openDynamicDetails(activity, item, finalTitle, clicked);
            }
        });
        return card;
    }

    private static void openDynamicDetails(
            final Activity activity,
            final JSONObject item,
            String title,
            View clicked
    ) {
        openTitleDetails(activity, item, clicked);
    }

    // The app font (Beiruti) is loaded once per process by CigramTypefaces; every card / label / dialog
    // shares that one Typeface instead of parsing the asset again.
    private static Typeface cigramTypeface(Context context) {
        return CigramTypefaces.app(context);
    }

    private static GradientDrawable roundedDrawable(int color, float radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    /** v5.1: status-bar icon = monochrome Cigram logo (ic_stat_cigram); falls back to the old bell. */
    public static int notificationSmallIcon(Context context) {
        int id = 0;
        try {
            id = context.getResources().getIdentifier("ic_stat_cigram", "drawable", context.getPackageName());
            if (id == 0) id = context.getResources().getIdentifier(
                    "ic_notifications_white", "drawable", context.getPackageName());
        } catch (Throwable ignored) { }
        return id != 0 ? id : android.R.drawable.stat_notify_more;
    }

    private static int dpInt(Context context, float value) {
        return Math.round(dp(context, value));
    }

    /** 0 = no early download (idle / failed), 1 = running, 2 = finished OK in this process. */
    static int earlyCatalogState() {
        if (CATALOG_NETWORK_RUNNING) return 1;
        long at = LAST_CATALOG_NETWORK_SUCCESS_AT;
        return at > 0L && System.currentTimeMillis() - at < 120000L ? 2 : 0;   // fresh only
    }

    /** Poster file shared by the home rows, the dynamic sections and the start-up image preload. */
    static java.io.File posterCacheFile(Context context, String url) {
        return dynamicPosterCacheFile(context, normalizedPosterUrl(url));
    }

    private static String normalizedPosterUrl(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.startsWith("//")) url = "https:" + url;
        if (url.contains("image.tmdb.org/t/p/")) {
            url = url.replaceAll("/t/p/(w[0-9]+|h[0-9]+|original)/", "/t/p/w500/");   // cards: w500 is sharp enough and loads ~2x faster than w780
        }
        return url.replace(" ", "%20");
    }

    private static void loadDynamicPoster(
            final Context context,
            String rawUrl,
            final android.widget.ImageView view
    ) {
        final String url = normalizedPosterUrl(rawUrl);
        if (url.length() == 0 || view == null) return;

        final String token = "cigram-stage2-poster:" + url;
        // A view never loaded by us still holds the design-time robot (default_image);
        // a view reused for another title holds the previous poster. Neither may stay
        // visible while the real poster loads.
        Object previousTag = view.getTag();
        boolean freshOrReused = previousTag == null
                || !String.valueOf(previousTag).equals(token);
        view.setTag(token);

        android.graphics.Bitmap hit = getSharedDetailsBitmap(url);
        if (hit == null || hit.isRecycled()) hit = SECTION_IMAGE_MEMORY.get(url);
        if (hit != null && !hit.isRecycled()) {
            putSharedDetailsBitmap(url, hit);
            view.setImageBitmap(hit);
            return;
        }

        try {
            if (freshOrReused
                    && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                view.setImageDrawable(CigramSkeleton.create(context, 0f));
            }
        } catch (Throwable ignored) { }

        // v4.3: one download/decode per URL. A second view asking for the same poster while it is
        // in flight just waits for that result (entries older than 30 s are ignored, so a crashed
        // fetch can never block the URL forever).
        synchronized (POSTER_INFLIGHT) {
            PosterWait pending = POSTER_INFLIGHT.get(url);
            if (pending != null && System.currentTimeMillis() - pending.at < 30000L) {
                pending.views.add(view);
                return;
            }
            PosterWait mine = new PosterWait();
            mine.views.add(view);
            POSTER_INFLIGHT.put(url, mine);
        }

        POSTER_IO.execute(new Runnable() {
            @Override public void run() {
                android.graphics.Bitmap bitmap = null;
                java.io.File target = dynamicPosterCacheFile(context, url);

                if (target.isFile() && target.length() > 0L) {
                    bitmap = decodeDynamicPoster(target);
                }

                if (bitmap == null && (url.startsWith("https://") || url.startsWith("http://"))) {
                    HttpURLConnection connection = null;
                    InputStream input = null;
                    java.io.FileOutputStream output = null;
                    java.io.File temp = new java.io.File(
                            target.getParentFile(),
                            target.getName() + "." + Thread.currentThread().getId() + ".part"
                    );
                    try {
                        connection = (HttpURLConnection) new URL(url).openConnection();
                        connection.setConnectTimeout(7000);
                        connection.setReadTimeout(10000);
                        connection.setInstanceFollowRedirects(true);
                        connection.setUseCaches(true);
                        connection.setRequestProperty("User-Agent", "Cigram/1.0 Android");
                        int status = connection.getResponseCode();
                        if (status >= 200 && status < 300) {
                            input = new BufferedInputStream(connection.getInputStream());
                            output = new java.io.FileOutputStream(temp);
                            byte[] buffer = new byte[16 * 1024];
                            int count;
                            long total = 0L;
                            while ((count = input.read(buffer)) != -1) {
                                total += count;
                                if (total > 12L * 1024L * 1024L) {
                                    throw new java.io.IOException("Image too large");
                                }
                                output.write(buffer, 0, count);
                            }
                            output.flush();
                            if (!temp.renameTo(target)) {
                                target = temp;
                            }
                            bitmap = decodeDynamicPoster(target);
                        }
                    } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "run", ignored); } finally {
                        try { if (input != null) input.close(); } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "run", ignored); }
                        try { if (output != null) output.close(); } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "run", ignored); }
                        if (connection != null) connection.disconnect();
                        if (temp.exists() && !temp.equals(target)) temp.delete();
                    }
                }

                if (bitmap != null) {
                    SECTION_IMAGE_MEMORY.put(url, bitmap);
                    putSharedDetailsBitmap(url, bitmap);
                }

                final android.graphics.Bitmap result = bitmap;
                final java.util.ArrayList<android.widget.ImageView> targets = new java.util.ArrayList<android.widget.ImageView>();
                synchronized (POSTER_INFLIGHT) {
                    PosterWait done = POSTER_INFLIGHT.remove(url);
                    if (done != null) targets.addAll(done.views);
                }
                if (targets.isEmpty()) targets.add(view);
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        for (int i = 0; i < targets.size(); i++) {
                            android.widget.ImageView v = targets.get(i);
                            if (v == null) continue;
                            if (!token.equals(String.valueOf(v.getTag()))) continue;   // the view moved on to another title
                            if (result != null && !result.isRecycled()) {
                                // v4.4: a poster that arrives from the network replaces the
                                // skeleton with a short fade instead of a hard pop. Views that take
                                // part in the details flight (transition name) or that are dimmed
                                // on purpose (alpha < 1) keep the old instant swap.
                                boolean fade = v.getDrawable() instanceof CigramSkeleton
                                        && v.getAlpha() >= 1f
                                        && (Build.VERSION.SDK_INT < 21 || v.getTransitionName() == null)
                                        && v.getWindowToken() != null;
                                v.setImageBitmap(result);
                                if (fade) {
                                    v.setAlpha(0f);
                                    v.animate().alpha(1f).setDuration(180L).start();
                                }
                            } else {
                                // Download failed: stop the skeleton animation, keep a calm static block.
                                v.setImageDrawable(new android.graphics.drawable.ColorDrawable(
                                        CigramTheme.adapt(v.getContext(), 0xFF1B1B1F)));
                            }
                        }
                    }
                });
            }
        });
    }

    private static java.io.File dynamicPosterCacheFile(Context context, String url) {
        java.io.File dir = CigramMediaStore.dir(context, CigramMediaStore.POSTERS);
        return new java.io.File(dir, dynamicPosterCacheName(url));
    }

    private static String dynamicPosterCacheName(String url) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) {
                out.append(String.format(java.util.Locale.US, "%02x", b & 255));
            }
            return out.toString() + ".img";
        } catch (Throwable ignored) {
            return Integer.toHexString(url.hashCode()) + ".img";
        }
    }

    private static android.graphics.Bitmap decodeDynamicPoster(java.io.File source) {
        if (source == null || !source.isFile() || source.length() <= 0L) return null;
        try {
            android.graphics.BitmapFactory.Options bounds =
                    new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (largest / sample > 720) sample *= 2;   // cards are ~130 dp wide: 720 px is plenty (was 1024)

            android.graphics.BitmapFactory.Options options =
                    new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
            return android.graphics.BitmapFactory.decodeFile(source.getAbsolutePath(), options);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static StrictSectionController ensureStrictSectionController(Activity activity) {
        synchronized (STRICT_SECTION_CONTROLLERS) {
            StrictSectionController controller = STRICT_SECTION_CONTROLLERS.get(activity);
            if (controller == null) {
                controller = new StrictSectionController(activity);
                STRICT_SECTION_CONTROLLERS.put(activity, controller);
            }
            return controller;
        }
    }

    private static final class StrictSectionController {
        private final Activity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private ViewGroup itemsRoot;
        private TextView titleView;
        private android.widget.ScrollView scrollView;
        private android.view.ViewTreeObserver.OnScrollChangedListener scrollListener;
        private JSONArray exactItems;
        private int renderedCount;
        private boolean appending;
        private String sectionId = "";
        private String sectionTitle = "";
        private String sectionType = "";
        private String sectionMode = "";
        private String relatedCategory = "";
        private String relatedExcludeKey = "";
        private boolean guardAttached;
        private boolean rendering;
        private boolean destroyed;
        private boolean fetching;
        private long lastFetchAt;

        private final Runnable enforceRunnable = new Runnable() {
            @Override public void run() {
                if (destroyed || rendering || exactItems == null || !isAlive(activity)) return;
                if (!strictChildrenOnly()) renderExact();
            }
        };

        StrictSectionController(Activity activity) {
            this.activity = activity;
        }

        void start() {
            if (destroyed || !isAlive(activity)) return;
            readTarget();
            bindViews();

            if (itemsRoot == null) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!destroyed) start();
                    }
                }, 180L);
                return;
            }

            attachGuard();
            applyTitle();

            String cached = catalogPrefs(activity).getString(KEY_JSON, "");
            if (isValidCatalog(cached)) {
                setExactFromRaw(cached);
            } else if (exactItems == null) {
                showSectionMessage("جاري تحميل المحتوى...");
            }

            long now = System.currentTimeMillis();
            if (!fetching && isOnline(activity) && now - lastFetchAt > 8000L) {
                fetchLatest();
            }
        }

        private void readTarget() {
            try {
                android.content.Intent intent = activity.getIntent();
                if (intent != null) {
                    sectionId = clean(intent.getStringExtra("section_id"));
                    sectionTitle = clean(intent.getStringExtra("section_title"));
                    if (sectionTitle.length() == 0) {
                        sectionTitle = clean(intent.getStringExtra("section_name"));
                    }
                    sectionType = normalizeSearch(clean(intent.getStringExtra("section_type")));
                    sectionMode = normalizeSearch(clean(intent.getStringExtra("section_mode")));
                    relatedCategory = clean(intent.getStringExtra("related_category"));
                    relatedExcludeKey = clean(intent.getStringExtra("related_exclude_key"));
                }
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "readTarget", ignored); }

            if (sectionId.length() == 0) {
                String normalizedTitle = normalizeSearch(sectionTitle);
                if ("احدث الافلام".equals(normalizedTitle)) {
                    sectionId = "latest_movies";
                } else if ("المسلسلات المميزه".equals(normalizedTitle)) {
                    sectionId = "featured_series";
                } else if ("احدث المسلسلات".equals(normalizedTitle)) {
                    sectionId = "latest_series";
                } else if (sectionTitle.length() == 0 && "movie".equals(sectionType)) {
                    sectionId = "latest_movies";
                    sectionTitle = "أحدث الأفلام";
                } else if (sectionTitle.length() == 0 && "series".equals(sectionType)) {
                    sectionId = "featured_series";
                    sectionTitle = "المسلسلات المميزة";
                }
            }

            if ("related".equals(sectionMode) || "__related__".equals(sectionId)) {
                sectionTitle = "ذات صلة";
            }

            if (sectionTitle.length() == 0) {
                if ("latest_movies".equalsIgnoreCase(sectionId)) {
                    sectionTitle = "أحدث الأفلام";
                } else if ("featured_series".equalsIgnoreCase(sectionId)) {
                    sectionTitle = "المسلسلات المميزة";
                } else if ("latest_series".equalsIgnoreCase(sectionId)) {
                    sectionTitle = "أحدث المسلسلات";
                }
            }
        }

        private void bindViews() {
            View root = findByIdName(activity, "linear_section_items");
            if (root instanceof ViewGroup) {
                itemsRoot = (ViewGroup) root;
                itemsRoot.setBackgroundColor(0xFF0E0E10);
                itemsRoot.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                itemsRoot.setPadding(dpInt(activity, 8), dpInt(activity, 10),
                        dpInt(activity, 8), dpInt(activity, 20));
            }
            View title = findByIdName(activity, "textview_section_title");
            if (title instanceof TextView) titleView = (TextView) title;
            View scroll = findByIdName(activity, "vscroll_section");
            if (scroll instanceof android.widget.ScrollView) {
                scrollView = (android.widget.ScrollView) scroll;
                scrollView.setBackgroundColor(0xFF0E0E10);
                scrollView.setVerticalScrollBarEnabled(false);
            }
            View pageRoot = findByIdName(activity, "linear_section_root");
            if (pageRoot != null) pageRoot.setBackgroundColor(0xFF0E0E10);
            View header = findByIdName(activity, "linear_section_header");
            if (header instanceof LinearLayout) {
                ((LinearLayout) header).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            }
            final View back = findByIdName(activity, "imageview_section_back");
            if (back != null) {
                back.setClickable(true);
                back.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) { activity.finish(); }
                });
            }
        }

        private void applyTitle() {
            if (titleView == null) return;
            titleView.setText(sectionTitle.length() == 0 ? "القسم" : sectionTitle);
            titleView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            titleView.setTextDirection(View.TEXT_DIRECTION_RTL);
            titleView.setSingleLine(true);
            titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titleView.setTypeface(cigramTypeface(activity), Typeface.BOLD);
        }

        private void attachGuard() {
            if (guardAttached || itemsRoot == null) return;
            guardAttached = true;
            itemsRoot.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) {
                    if (rendering || exactItems == null || destroyed) return;
                    handler.removeCallbacks(enforceRunnable);
                    handler.postDelayed(enforceRunnable, 70L);
                }

                @Override public void onChildViewRemoved(View parent, View child) {
                    if (rendering || exactItems == null || destroyed) return;
                    handler.removeCallbacks(enforceRunnable);
                    handler.postDelayed(enforceRunnable, 70L);
                }
            });
            attachSectionScrollWatcher();
        }

        private void attachSectionScrollWatcher() {
            if (scrollView == null || scrollListener != null) return;
            scrollListener = new android.view.ViewTreeObserver.OnScrollChangedListener() {
                @Override public void onScrollChanged() {
                    if (destroyed || scrollView == null || exactItems == null) return;
                    View child = scrollView.getChildCount() > 0 ? scrollView.getChildAt(0) : null;
                    if (child == null) return;
                    int remaining = child.getHeight() - (scrollView.getScrollY() + scrollView.getHeight());
                    if (remaining <= dpInt(activity, 900)) appendNextSectionBatch();
                }
            };
            try {
                scrollView.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
            } catch (Throwable ignored) { }
        }

        private boolean strictChildrenOnly() {
            if (itemsRoot == null) return false;
            int expected;
            if (exactItems == null || exactItems.length() == 0) {
                expected = 1;
            } else {
                int visible = Math.min(Math.max(0, renderedCount), exactItems.length());
                expected = Math.max(1, (visible + 2) / 3);
            }
            if (itemsRoot.getChildCount() != expected) return false;
            for (int i = 0; i < itemsRoot.getChildCount(); i++) {
                Object tag = itemsRoot.getChildAt(i).getTag();
                String text = tag == null ? "" : String.valueOf(tag);
                if (!text.startsWith("cigram_strict_section_")) return false;
            }
            return true;
        }

        private void setExactFromRaw(String raw) {
            final JSONArray extracted;
            if ("related".equals(sectionMode) || "__related__".equals(sectionId)) {
                extracted = extractRelatedCatalogItems(
                        raw, sectionType, relatedCategory, relatedExcludeKey, 0);
            } else {
                extracted = extractStrictSectionItems(
                        raw, sectionId, sectionTitle, sectionType);
            }
            exactItems = extracted;
            handler.post(new Runnable() {
                @Override public void run() {
                    if (!destroyed && isAlive(activity)) renderExact();
                }
            });
        }

        private void fetchLatest() {
            fetching = true;
            lastFetchAt = System.currentTimeMillis();
            IO.execute(new Runnable() {
                @Override public void run() {
                    try {
                        FetchResult result = fetchCatalog(activity);
                        if (result.ok && isValidCatalog(result.body)) {
                            PreparedCatalog prepared = prepareCatalog(result.body);
                            long now = System.currentTimeMillis();
                            String hash = catalogHash(prepared.displayJson);
                            installCatalogInMemory(prepared.displayJson, prepared.searchIndexJson);
                            catalogPrefs(activity).edit()
                                    .putString(KEY_JSON, prepared.displayJson)
                                    .putString(KEY_JSON_HASH, hash)
                                    .putLong(KEY_UPDATED, now)
                                    .putString(KEY_SEARCH_JSON, prepared.searchIndexJson)
                                    .putLong(KEY_SEARCH_UPDATED, now)
                                    .apply();
                            preloadCatalogImages(activity.getApplicationContext(), prepared.searchIndexJson);
                            setExactFromRaw(prepared.displayJson);
                        } else if (exactItems == null) {
                            handler.post(new Runnable() {
                                @Override public void run() {
                                    if (!destroyed) showSectionMessage("تعذر تحميل محتوى القسم");
                                }
                            });
                        }
                    } finally {
                        fetching = false;
                    }
                }
            });
        }

        private void renderExact() {
            if (itemsRoot == null || destroyed) return;
            rendering = true;
            try {
                itemsRoot.removeAllViews();
                renderedCount = 0;

                if (exactItems == null || exactItems.length() == 0) {
                    addMessageView("لا يوجد محتوى في هذا القسم");
                    return;
                }
            } finally {
                rendering = false;
            }
            appendNextSectionBatch();
        }

        private void appendNextSectionBatch() {
            if (itemsRoot == null || destroyed || exactItems == null || appending) return;
            if (renderedCount >= exactItems.length()) return;
            appending = true;
            rendering = true;
            try {
                final int startIndex = renderedCount;
                final int endIndex = Math.min(exactItems.length(), startIndex + 30);
                for (int start = startIndex; start < endIndex; start += 3) {
                    LinearLayout row = new LinearLayout(activity);
                    row.setTag("cigram_strict_section_row:" + (start / 3));

                    // v4.4: item order reads right → left in every RTL setting (newest at the far
                    // right of the first row), same data and paging as before.
                    View[] cards = new View[3];
                    for (int slot = 0; slot < 3; slot++) {
                        int index = start + slot;
                        View card = null;
                        if (index < endIndex && index < exactItems.length()) {
                            JSONObject item = exactItems.optJSONObject(index);
                            card = item == null ? null : makeSectionGridCard(activity, item);
                        }
                        if (card == null) {
                            card = new View(activity);
                            card.setVisibility(View.INVISIBLE);
                        }
                        cards[slot] = card;
                    }
                    CigramDesign.fillGridRow(row, cards, dpInt(activity, 5));

                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    rowLp.bottomMargin = dpInt(activity, 16);
                    itemsRoot.addView(row, rowLp);
                }
                renderedCount = endIndex;
            } finally {
                rendering = false;
                appending = false;
            }
        }

        private void showSectionMessage(String message) {
            if (itemsRoot == null) return;
            rendering = true;
            try {
                itemsRoot.removeAllViews();
                renderedCount = 0;
                addMessageView(message);
            } finally {
                rendering = false;
            }
        }

        private void addMessageView(String message) {
            TextView text = new TextView(activity);
            text.setTag("cigram_strict_section_message");
            text.setText(message);
            text.setTextColor(0xFFB7C0C5);
            text.setTextSize(15f);
            text.setGravity(Gravity.CENTER);
            text.setTypeface(cigramTypeface(activity));
            text.setPadding(dpInt(activity, 18), dpInt(activity, 34),
                    dpInt(activity, 18), dpInt(activity, 34));
            itemsRoot.addView(text, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void destroy() {
            destroyed = true;
            handler.removeCallbacks(enforceRunnable);
            if (itemsRoot != null) {
                try { itemsRoot.setOnHierarchyChangeListener(null); }
                catch (Throwable ignored) { android.util.Log.w("CigramInjected", "destroy", ignored); }
            }
            if (scrollView != null && scrollListener != null) {
                try {
                    android.view.ViewTreeObserver observer = scrollView.getViewTreeObserver();
                    if (observer.isAlive()) observer.removeOnScrollChangedListener(scrollListener);
                } catch (Throwable ignored) { }
            }
            scrollListener = null;
        }
    }

    private static JSONArray extractStrictSectionItems(
            String raw,
            String targetSectionId,
            String targetSectionName,
            String targetSectionType
    ) {
        JSONArray result = new JSONArray();
        if (raw == null || raw.trim().length() == 0) return result;

        try {
            Object root = new JSONTokener(raw).nextValue();
            java.util.ArrayList<JSONObject> matches = new java.util.ArrayList<JSONObject>();
            collectStrictSectionContent(
                    root,
                    "",
                    null,
                    normalizeSearch(targetSectionId),
                    normalizeSearch(targetSectionName),
                    normalizeSearch(targetSectionType),
                    matches,
                    0);

            java.util.LinkedHashMap<String, JSONObject> unique =
                    new java.util.LinkedHashMap<String, JSONObject>();
            java.util.LinkedHashMap<String, java.util.LinkedHashMap<String, JSONObject>> episodes =
                    new java.util.LinkedHashMap<String, java.util.LinkedHashMap<String, JSONObject>>();

            for (JSONObject item : matches) {
                if (item == null) continue;
                String key = dynamicDisplayItemKey(item);
                if (key.length() == 0) {
                    key = "item|" + unique.size() + "|" + contentRecency(item);
                }

                JSONObject old = unique.get(key);
                if (old == null) {
                    unique.put(key, new JSONObject(item.toString()));
                } else {
                    unique.put(key, mergeSearchItems(old, item));
                }

                if ("series".equals(inferContentType(item, ""))) {
                    java.util.LinkedHashMap<String, JSONObject> group = episodes.get(key);
                    if (group == null) {
                        group = new java.util.LinkedHashMap<String, JSONObject>();
                        episodes.put(key, group);
                    }
                    collectEpisodesFromItem(item, group);
                }
            }

            java.util.ArrayList<JSONObject> ordered =
                    new java.util.ArrayList<JSONObject>(unique.values());
            CigramOrder.sortJson(ordered);   // v10.67

            for (JSONObject item : ordered) {
                String key = dynamicDisplayItemKey(item);
                java.util.LinkedHashMap<String, JSONObject> group = episodes.get(key);
                if (group != null && !group.isEmpty()) {
                    java.util.ArrayList<JSONObject> list =
                            new java.util.ArrayList<JSONObject>(group.values());
                    java.util.Collections.sort(list, new java.util.Comparator<JSONObject>() {
                        @Override public int compare(JSONObject a, JSONObject b) {
                            int sa = intText(a, "season", "season_number");
                            int sb = intText(b, "season", "season_number");
                            if (sa != sb) return sa < sb ? -1 : 1;
                            int ea = intText(a, "episode", "episode_number", "ep", "number");
                            int eb = intText(b, "episode", "episode_number", "ep", "number");
                            return ea < eb ? -1 : (ea == eb ? 0 : 1);
                        }
                    });
                    JSONArray episodeArray = new JSONArray();
                    for (JSONObject episode : list) episodeArray.put(episode);
                    item.put("series_episodes_json", episodeArray.toString());
                }
                result.put(item);
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramStage2", "Strict section extraction failed", error);
        }
        return result;
    }

    private static void collectStrictSectionContent(
            Object node,
            String inheritedType,
            JSONObject parentSection,
            String targetId,
            String targetName,
            String targetType,
            java.util.ArrayList<JSONObject> out,
            int depth
    ) throws Exception {
        if (node == null || node == JSONObject.NULL || depth > 14) return;

        if (node instanceof String) {
            String text = ((String) node).trim();
            if (text.startsWith("[") || text.startsWith("{")) {
                try {
                    collectStrictSectionContent(
                            new JSONTokener(text).nextValue(), inheritedType, parentSection,
                            targetId, targetName, targetType, out, depth + 1);
                } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "compare", ignored); }
            }
            return;
        }

        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collectStrictSectionContent(
                        array.opt(i), inheritedType, parentSection,
                        targetId, targetName, targetType, out, depth + 1);
            }
            return;
        }

        if (!(node instanceof JSONObject)) return;
        JSONObject object = (JSONObject) node;

        boolean hasContainer = false;
        for (String key : new String[]{
                "movies", "series", "items", "data", "results",
                "content", "sections", "catalog", "entries"
        }) {
            Object child = object.opt(key);
            if (child instanceof JSONArray || child instanceof JSONObject) {
                hasContainer = true;
                break;
            }
            if (child instanceof String) {
                String text = ((String) child).trim();
                if (text.startsWith("[") || text.startsWith("{")) {
                    hasContainer = true;
                    break;
                }
            }
        }

        boolean looksLikeContent = !hasContainer
                && jsonText(object, "title", "series_title", "name", "title_ar", "title_en").length() > 0
                && (object.has("id") || object.has("series_id") || object.has("poster")
                    || object.has("video") || object.has("link") || object.has("type")
                    || object.has("episodes") || object.has("seasons")
                    || object.has("series_episodes_json"));

        if (looksLikeContent) {
            JSONObject item = normalizeSearchItem(object, inheritedType, parentSection);
            if (strictSectionMatches(item, targetId, targetName, targetType)) {
                out.add(item);
            }
            return;
        }

        JSONObject sectionContext = parentSection;
        if (object.has("section_id") || object.has("section_name")) {
            sectionContext = object;
        } else if (object.has("id") && object.has("name") && hasContainer) {
            sectionContext = object;
        }

        java.util.ArrayList<String> keys = new java.util.ArrayList<String>();
        java.util.Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) keys.add(iterator.next());

        for (String key : keys) {
            Object child = object.opt(key);
            String nextType = inheritedType;
            if ("movies".equals(key)) nextType = "movie";
            else if ("series".equals(key)) nextType = "series";

            if (child instanceof JSONArray || child instanceof JSONObject || child instanceof String) {
                collectStrictSectionContent(
                        child, nextType, sectionContext,
                        targetId, targetName, targetType, out, depth + 1);
            }
        }
    }

    private static boolean strictSectionMatches(
            JSONObject item,
            String targetId,
            String targetName,
            String targetType
    ) {
        if (item == null) return false;

        String itemId = normalizeSearch(jsonText(item, "section_id", "sectionId"));
        String itemName = normalizeSearch(jsonText(item, "section_name", "sectionName", "section"));

        if (itemId.length() == 0 && itemName.length() == 0) return false;

        boolean match = false;
        if (targetId.length() > 0) {
            if (itemId.length() > 0) {
                match = targetId.equals(itemId);
            } else if (targetName.length() > 0 && itemName.length() > 0) {
                match = targetName.equals(itemName);
            }
        } else if (targetName.length() > 0 && itemName.length() > 0) {
            match = targetName.equals(itemName);
        }

        if (!match) return false;

        if (targetType.length() == 0) return true;
        String itemType = inferContentType(item, "");
        return targetType.equals(itemType);
    }

    private static View makeStrictSectionRow(
            final Activity activity,
            final JSONObject item
    ) {
        try {
            final float d = activity.getResources().getDisplayMetrics().density;
            final Typeface font = cigramTypeface(activity);

            final LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            row.setGravity(Gravity.RIGHT | Gravity.TOP);
            row.setPadding(dpInt(activity, 10), dpInt(activity, 10),
                    dpInt(activity, 10), dpInt(activity, 10));
            row.setClickable(true);
            row.setFocusable(true);

            GradientDrawable rowBackground = new GradientDrawable();
            rowBackground.setColor(0xFF06232D);
            rowBackground.setCornerRadius(dp(activity, 16f));
            rowBackground.setStroke(Math.max(1, dpInt(activity, 1)), 0xFF50656E);
            row.setBackground(rowBackground);

            final android.widget.ImageView posterView =
                    new android.widget.ImageView(activity);
            posterView.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            posterView.setAdjustViewBounds(true);
            GradientDrawable posterBackground = new GradientDrawable();
            posterBackground.setColor(0xFF1C1C20);
            posterBackground.setCornerRadius(dp(activity, 14f));
            posterView.setBackground(posterBackground);
            if (Build.VERSION.SDK_INT >= 21) posterView.setClipToOutline(true);

            LinearLayout.LayoutParams posterLp =
                    new LinearLayout.LayoutParams(dpInt(activity, 112), dpInt(activity, 158));
            posterLp.leftMargin = dpInt(activity, 12);
            row.addView(posterView, posterLp);

            LinearLayout details = new LinearLayout(activity);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            details.setGravity(Gravity.RIGHT);
            details.setPadding(0, dpInt(activity, 4), 0, 0);

            String type = inferContentType(item, "");
            String title = jsonText(item,
                    "series_title", "title", "title_ar", "title_en", "original_title", "name");
            if (title.length() == 0) title = "بدون عنوان";

            TextView titleText = new TextView(activity);
            titleText.setText(title);
            titleText.setTextColor(Color.WHITE);
            titleText.setTextSize(19f);
            titleText.setGravity(Gravity.RIGHT);
            titleText.setTextDirection(View.TEXT_DIRECTION_RTL);
            titleText.setMaxLines(2);
            titleText.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titleText.setTypeface(font, Typeface.BOLD);
            details.addView(titleText, new LinearLayout.LayoutParams(-1, -2));

            String year = jsonText(item, "year", "release_year");
            String category = jsonText(item, "category", "genre", "genres");
            StringBuilder metaBuilder = new StringBuilder();
            if (year.length() > 0) metaBuilder.append(year);
            if (category.length() > 0) {
                if (metaBuilder.length() > 0) metaBuilder.append(" · ");
                metaBuilder.append(category);
            }

            TextView meta = new TextView(activity);
            meta.setText(metaBuilder.toString());
            meta.setTextColor(0xFF91A1A9);
            meta.setTextSize(14f);
            meta.setGravity(Gravity.RIGHT);
            meta.setTextDirection(View.TEXT_DIRECTION_RTL);
            meta.setTypeface(font);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, -2);
            metaLp.topMargin = dpInt(activity, 6);
            details.addView(meta, metaLp);

            String rating = jsonText(item, "imdb", "rating", "imdb_rating", "vote_average", "score");
            if (rating.length() > 0) {
                TextView imdb = new TextView(activity);
                imdb.setText(imdbLogoText(activity, rating, 14f));
                imdb.setTextColor(0xFF71D5BB);
                imdb.setTextSize(14f);
                imdb.setGravity(Gravity.RIGHT);
                imdb.setTypeface(font, Typeface.BOLD);
                LinearLayout.LayoutParams imdbLp = new LinearLayout.LayoutParams(-1, -2);
                imdbLp.topMargin = dpInt(activity, 7);
                details.addView(imdb, imdbLp);
            }

            row.addView(details, new LinearLayout.LayoutParams(
                    0, dpInt(activity, 158), 1f));

            String posterUrl = "series".equals(type)
                    ? jsonText(item, "series_poster", "poster", "season_poster", "image", "backdrop")
                    : jsonText(item, "poster", "image", "backdrop");
            loadDynamicPoster(activity, posterUrl, posterView);

            final String finalTitle = title;
            attachDetailsWarmTouch(activity, row, item);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(final View view) {
                    // v5.29: open at once (the press animation used to delay the details page)
                    openDynamicDetails(activity, item, finalTitle, view);
                }
            });

            return row;
        } catch (Throwable error) {
            android.util.Log.w("CigramStage2", "Strict section row failed", error);
            return null;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }


    // details.xml still carries design-time sample content (robot default_image,
    // sample texts, six sample "related" cards). Neutralize it before the first
    // frame so it can never be seen, including when no data is available yet and
    // bindModernDetailsLayout() returns early. Bind steps set the real values.
    private static void clearDetailsPlaceholders(Activity activity) {
        if (activity == null || !isAlive(activity)) return;
        try {
            ImageView poster = imageViewById(activity, "imageview_details_poster");
            if (poster != null) {
                Object posterTag = poster.getTag();
                boolean realImageRequested = posterTag != null
                        && String.valueOf(posterTag).startsWith("cigram-stage2-poster:");
                if (!realImageRequested) {
                    poster.setImageDrawable(null);
                    poster.setBackgroundColor(0xFF1B1B1F);
                }
            }

            String[] sampleTextIds = new String[]{
                    "textview_details_title", "textview_imdb", "textview_year",
                    "textview_category", "textview_quality", "textview_description",
                    "text_watch_also_title"
            };
            for (int i = 0; i < sampleTextIds.length; i++) {
                TextView sample = textViewById(activity, sampleTextIds[i]);
                if (sample != null) sample.setText("");
            }

            String[] sampleBlockIds = new String[]{
                    "linear_imdb", "linear_meta",
                    "linear_watch_also_row1", "linear_watch_also_row2",
                    "text_watch_also_more"
            };
            for (int i = 0; i < sampleBlockIds.length; i++) {
                View block = findByIdName(activity, sampleBlockIds[i]);
                if (block != null) block.setVisibility(View.GONE);
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "placeholder cleanup failed", error);
        }
    }

    // Android 13+: tell the system which theme to use for the NEXT cold-start splash,
    // so it is dark instead of white. Reflection keeps this independent of compileSdk.
    /**
     * Every screen reachable from the bottom bar gets the app's dark window background before
     * its first frame. The tab switch fades the new page in from alpha 0, so whatever sits
     * behind the page is what the user sees: with the default window it was white.
     */
    /** AdMob's full-screen ad screen (AdActivity and friends), not one of the app's pages. */
    private static boolean isAdMobScreen(Activity activity) {
        return activity != null
                && activity.getClass().getName().startsWith("com.google.android.gms.ads.");
    }

    /**
     * v5.29: AdActivity uses the framework Theme.Translucent, which has a window title bar, so
     * the ad opened under a grey "Cigram" strip. Ask for a window without a title before the ad
     * sets its content (pre-created / created run inside super.onCreate()). The ad itself is
     * untouched.
     */
    private static void cigramStripAdTitleBar(Activity activity) {
        if (!isAdMobScreen(activity)) return;
        try {
            activity.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        } catch (Throwable ignored) { }
        try {
            android.app.ActionBar bar = activity.getActionBar();
            if (bar != null) bar.hide();
        } catch (Throwable ignored) { }
    }

    /** Fallback when the title could not be removed in time: hide the title strip view. */
    private static void cigramHideAdTitleView(Activity activity) {
        if (!isAdMobScreen(activity)) return;
        try {
            android.app.ActionBar bar = activity.getActionBar();
            if (bar != null) bar.hide();
        } catch (Throwable ignored) { }
        try {
            android.view.Window window = activity.getWindow();
            if (window == null) return;
            View title = window.getDecorView().findViewById(android.R.id.title);
            if (title == null) return;
            View content = window.getDecorView().findViewById(android.R.id.content);
            View strip = title.getParent() instanceof View ? (View) title.getParent() : title;
            // never hide a container that holds the ad content
            if (content != null && strip instanceof ViewGroup && isAncestor((ViewGroup) strip, content)) {
                strip = title;
            }
            strip.setVisibility(View.GONE);
        } catch (Throwable ignored) { }
    }

    private static boolean isAncestor(ViewGroup parent, View child) {
        android.view.ViewParent p = child.getParent();
        while (p != null) {
            if (p == parent) return true;
            p = p.getParent();
        }
        return false;
    }

    private static void cigramDarkenWindow(Activity activity) {
        if (activity == null) return;
        try {
            String n = activity.getClass().getSimpleName();
            if (!("MainActivity".equals(n) || "ChannelsActivity".equals(n)
                    || "SearchXmlActivity".equals(n) || "AccountActivity".equals(n)
                    || "SectionActivity".equals(n) || "MyContentActivity".equals(n)
                    || "SettingsActivity".equals(n) || "NotificationsActivity".equals(n))) {
                return;
            }
            android.view.Window window = activity.getWindow();
            if (window == null) return;
            int page = CigramTheme.page(activity);   // v10.70: light page in Light mode
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(page));
            View decor = window.getDecorView();
            if (decor != null) decor.setBackgroundColor(page);
        } catch (Throwable error) {
            android.util.Log.w("CigramLifecycle", "dark window setup failed", error);
        }
    }

    /** Android 13+: keeps the system splash in step with the in-app Light / Dark choice (see CigramSplash). */
    private static void applyLaunchSplashTheme(Activity activity) {
        try {
            CigramSplash.syncSystemSplashTheme(activity);
        } catch (Throwable error) {
            android.util.Log.w("CigramLifecycle", "splash theme setup failed", error);
        }
    }

    // Blocks drawing of the DetailsActivity window until the first bind has run
    // (hard cap 500ms), so no frame of the unbound details.xml can reach the screen.
    private static void installDetailsFirstFrameGate(final Activity activity) {
        if (Build.VERSION.SDK_INT < 21 || activity == null) return;
        try {
            final View decor = activity.getWindow().getDecorView();
            // v5.29: 250 ms max (was 500): the old page stayed frozen too long on slow binds
            final long deadline = android.os.SystemClock.uptimeMillis() + 250L;
            decor.getViewTreeObserver().addOnPreDrawListener(
                    new android.view.ViewTreeObserver.OnPreDrawListener() {
                        @Override public boolean onPreDraw() {
                            boolean ready;
                            synchronized (DETAILS_STAGE3_SETUP) {
                                ready = Boolean.TRUE.equals(DETAILS_STAGE3_SETUP.get(activity));
                            }
                            if (ready || !isAlive(activity)
                                    || android.os.SystemClock.uptimeMillis() >= deadline) {
                                android.view.ViewTreeObserver observer = decor.getViewTreeObserver();
                                if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                                return true;
                            }
                            return false;
                        }
                    });
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "first-frame gate failed", error);
        }
    }

    private static final Map<Activity, Long> DETAILS_BOUND_AT = new WeakHashMap<Activity, Long>();
    private static final Map<Activity, Boolean> DETAILS_DEFERRED_REFRESH = new WeakHashMap<Activity, Boolean>();

    public static void bindTitleDetailsNow(final Activity activity) {
        if (activity == null || !isAlive(activity)) return;
        synchronized (DETAILS_STAGE3_SETUP) {
            DETAILS_STAGE3_SETUP.put(activity, Boolean.TRUE);
        }
        synchronized (DETAILS_BOUND_AT) {
            DETAILS_BOUND_AT.put(activity, android.os.SystemClock.uptimeMillis());
        }
        clearDetailsPlaceholders(activity);
        // Configures the shared-element enter/return transition, postpones it and
        // guarantees it starts (360ms cap). This was defined but never called before.
        prepareDetailsWindow(activity);
        try {
            prepareDetailsActivity(activity);
            bindModernDetailsLayout(activity);
            refreshStage3Details(activity);
            setupDetails(activity);
        } finally {
            // Sketchware's own click block (DetailsActivity$3) can be attached after us when
            // its initializeLogic() is delayed; it crashes on a missing "video" extra.
            // Re-assert our handler now and shortly after, whatever happened above.
            bindWatchNowSafely(activity);
            MAIN.postDelayed(new Runnable() {
                @Override public void run() { bindWatchNowSafely(activity); }
            }, 700L);
            MAIN.postDelayed(new Runnable() {
                @Override public void run() { bindWatchNowSafely(activity); }
            }, 1800L);
        }
    }

    /**
     * v10.35: «شاهد هَسه» -> rewarded unlock (one ad watched to the end, once per title per app
     * session), then the solo / together chooser. Was (v6.2) one interstitial on every press.
     */
    private static void watchNowWithAd(final Activity activity, final JSONObject current) {
        boolean series = false;
        try { series = stage3IsSeriesItem(current); } catch (Throwable ignored) { }
        CigramRewardUnlock.unlock(activity, rewardKeyOf(current), series, null, new Runnable() {
            @Override public void run() {
                if (!isAlive(activity)) return;
                CigramTogether.onWatchNow(activity, current, new Runnable() {
                    @Override public void run() { openPlayerForTitleItem(activity, current, null, false); }
                });
            }
        });
    }

    /**
     * v10.35: key of the video a title / episode will really play (same rule as
     * openPlayerForTitleItem: a series without its own video plays its first episode), so «شاهد هَسه»,
     * the episode list and the automatic next episode all unlock the same thing.
     */
    static String rewardKeyOf(JSONObject source) {
        try {
            if (source == null) return "";
            String video = jsonText(source,
                    "video", "link", "video_url", "movie_url", "stream_url", "play_url");
            if (video.length() == 0 && stage3IsSeriesItem(source)) {
                JSONObject first = stage6EpisodeArray(source).optJSONObject(0);
                if (first != null) {
                    video = jsonText(first,
                            "video", "link", "video_url", "movie_url", "stream_url", "play_url");
                }
            }
            if (video.length() > 0) return "v|" + video;
            return CigramContent.itemKey(source);
        } catch (Throwable error) {
            return "";
        }
    }

    private static void bindWatchNowSafely(final Activity activity) {
        if (activity == null || !isAlive(activity)) return;
        try {
            final View watchNow = findByIdName(activity, "button_watch_now");
            if (watchNow == null) return;
            watchNow.setClickable(true);
            watchNow.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    try {
                        final JSONObject current = stage3DetailsItem(activity);
                        // v6.2: «شاهد هَسه» -> one ad -> the chooser (بمفردك / مع صديقك, no ads inside)
                        watchNowWithAd(activity, current);
                    } catch (Throwable error) {
                        android.util.Log.w("CigramDetails", "watch click failed", error);
                    }
                }
            });
            syncDetailsIntentExtras(activity, stage3DetailsItem(activity));
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "bindWatchNowSafely failed", error);
        }
    }

    // Keeps the Intent extras Sketchware's generated code reads ("video", "subtitle")
    // non-null and up to date with what the catalog resolved.
    private static void syncDetailsIntentExtras(Activity activity, JSONObject item) {
        if (activity == null || activity.getIntent() == null) return;
        try {
            android.content.Intent intent = activity.getIntent();
            String video = item == null ? "" : jsonText(item, "video", "link", "video_url", "movie_url");
            if (video.length() > 0) intent.putExtra("video", video);
            else if (!intent.hasExtra("video")) intent.putExtra("video", "");
            String subtitle = item == null ? "" : jsonText(item, "subtitle", "subtitle_url");
            if (subtitle.length() > 0) intent.putExtra("subtitle", subtitle);
            else if (!intent.hasExtra("subtitle")) intent.putExtra("subtitle", "");
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "syncDetailsIntentExtras failed", error);
        }
    }

    private static void bindModernDetailsLayout(final Activity activity) {
        if (activity == null || !isAlive(activity)) return;
        final View rootView = findByIdName(activity, "linear1");
        if (!(rootView instanceof LinearLayout)) return;
        final LinearLayout root = (LinearLayout) rootView;
        root.setVisibility(View.VISIBLE);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(0xFF0E0E10);

        final JSONObject item = stage3DetailsItem(activity);
        if (item == null || item.length() == 0) return;
        final Typeface font = cigramTypeface(activity);

        RelativeLayout hero = null;
        View heroView = findByIdName(activity, "relativelayout1");
        if (heroView instanceof RelativeLayout) hero = (RelativeLayout) heroView;
        if (hero != null) {
            hero.setBackgroundColor(0xFF0E0E10);
            ViewGroup.LayoutParams baseLp = hero.getLayoutParams();
            if (baseLp != null) {
                // v10.45 (cinematic): the picture fills the top of the page, about 1.2 x the screen width
                int wide = activity.getResources().getDisplayMetrics().widthPixels;
                baseLp.height = Math.max(dpInt(activity, 400), Math.min(Math.round(wide * 1.2f), dpInt(activity, 540)));
                hero.setLayoutParams(baseLp);
            }

            ImageView backdrop = null;
            View found = hero.findViewWithTag("cigram_title_details_backdrop");
            if (found instanceof ImageView) backdrop = (ImageView) found;
            if (backdrop == null) {
                backdrop = new ImageView(activity);
                backdrop.setTag("cigram_title_details_backdrop");
                backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
                hero.addView(backdrop, 0, new RelativeLayout.LayoutParams(-1, -1));
            }
            String backdropUrl = jsonText(item,
                    "poster", "series_poster", "season_poster", "image",
                    "backdrop", "landscape_poster", "source_backdrop");
            // v10.46: in the cinematic page the poster view itself is the full-width picture
            // (so the card -> poster flight lands on it); the extra backdrop view stays empty.
            backdrop.setVisibility(DETAILS_CINEMATIC ? View.GONE : View.VISIBLE);
            if (!DETAILS_CINEMATIC && backdropUrl.length() > 0) loadDynamicPoster(activity, backdropUrl, backdrop);

            View shade = hero.findViewWithTag("cigram_title_details_shade");
            if (shade == null) {
                shade = new View(activity);
                shade.setTag("cigram_title_details_shade");
                // v4.4: darker lower half so the title / meta line read on any backdrop.
                GradientDrawable gd = new GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[]{0x260E0E10, 0x5C0E0E10, 0xC20E0E10, 0xFF0E0E10});
                shade.setBackground(gd);
                hero.addView(shade, 1, new RelativeLayout.LayoutParams(-1, -1));
            }

            final ImageView poster = imageViewById(activity, "imageview_details_poster");
            if (poster != null) {
                String posterUrl = jsonText(item,
                        "poster", "series_poster", "season_poster", "image", "backdrop");
                poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
                poster.setBackgroundColor(0xFF1B1B1F);
                final int screenWidth = activity.getResources().getDisplayMetrics().widthPixels;
                RelativeLayout.LayoutParams lp;
                if (DETAILS_CINEMATIC) {
                    // the poster is the picture at the top, full width; the flight from the card ends here
                    ViewGroup.LayoutParams heroLp = hero.getLayoutParams();
                    int heroHeight = heroLp != null && heroLp.height > 0 ? heroLp.height : dpInt(activity, 450);
                    lp = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heroHeight);
                    lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
                } else {
                    lp = new RelativeLayout.LayoutParams(dpInt(activity, 132), dpInt(activity, 198));
                    lp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                    lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                    lp.setMargins(dpInt(activity, 12), 0, dpInt(activity, 18), dpInt(activity, 18));
                }
                poster.setLayoutParams(lp);
                if (Build.VERSION.SDK_INT >= 21) {
                    final float radius = dp(activity, 14f);
                    if (!DETAILS_CINEMATIC) {
                        poster.setOutlineProvider(new android.view.ViewOutlineProvider() {
                            @Override public void getOutline(View view, android.graphics.Outline outline) {
                                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                            }
                        });
                        poster.setClipToOutline(true);
                        poster.setElevation(dp(activity, 8f));
                    } else {
                        poster.setClipToOutline(false);
                        poster.setElevation(0f);
                    }
                    poster.setTransitionName(detailsTransitionName(activity.getIntent()));
                }
                if (posterUrl.length() > 0) loadDynamicPoster(activity, posterUrl, poster);
                if (DETAILS_CINEMATIC) {
                    // under the shade, the header and the title
                    if (poster.getParent() instanceof ViewGroup) ((ViewGroup) poster.getParent()).removeView(poster);
                    hero.addView(poster, 0, lp);
                } else {
                    poster.bringToFront();
                }
                final int wantedPosterWidth = DETAILS_CINEMATIC ? screenWidth : dpInt(activity, 132);
                poster.getViewTreeObserver().addOnPreDrawListener(
                        new android.view.ViewTreeObserver.OnPreDrawListener() {
                            @Override public boolean onPreDraw() {
                                if (poster.getWidth() != wantedPosterWidth) return true;
                                android.view.ViewTreeObserver observer = poster.getViewTreeObserver();
                                if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                                // false = redraw once so the first frame already has the overlay
                                return !startDetailsFlight(activity, poster);
                            }
                        });
                poster.post(new Runnable() {
                    @Override public void run() {
                        startDetailsPostponedEnter(activity);
                    }
                });
            }

            final View header = findByIdName(activity, "linear_header");
            if (header != null) {
                header.setBackgroundColor(Color.TRANSPARENT);
                header.bringToFront();
            }

            final TextView title = textViewById(activity, "textview_details_title");
            final View heroBottomView = findByIdName(activity, "linear_hero_bottom");
            if (title != null && heroBottomView instanceof LinearLayout) {
                LinearLayout heroBottom = (LinearLayout) heroBottomView;
                ViewGroup parent = (ViewGroup) title.getParent();
                if (parent != heroBottom) {
                    if (parent != null) parent.removeView(title);
                    heroBottom.addView(title, 0, new LinearLayout.LayoutParams(-1, -2));
                }
                title.setText(valueOrFallback(jsonText(item,
                        "series_title", "title", "name", "title_ar", "title_en"), "بدون عنوان"));
                title.setTextColor(Color.WHITE);
                title.setTextSize(DETAILS_CINEMATIC ? 27f : 24f);
                title.setTypeface(font, Typeface.BOLD);
                title.setGravity(DETAILS_CINEMATIC ? Gravity.CENTER_HORIZONTAL : Gravity.RIGHT);
                title.setTextDirection(View.TEXT_DIRECTION_RTL);
                // v4.4: a very long title used to grow up over the header buttons of the
                // 450dp hero; three lines + ellipsis keeps it inside. The soft shadow keeps it
                // readable on bright backdrops.
                title.setMaxLines(3);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                title.setLineSpacing(0f, 1.08f);
                title.setShadowLayer(dp(activity, 6f), 0f, dp(activity, 1f), 0xB3000A10);

                heroBottom.setOrientation(LinearLayout.VERTICAL);
                heroBottom.setGravity((DETAILS_CINEMATIC ? Gravity.CENTER_HORIZONTAL : Gravity.RIGHT) | Gravity.BOTTOM);
                heroBottom.setPadding(0, 0, 0, 0);
                heroBottom.setBackgroundColor(Color.TRANSPARENT);
                RelativeLayout.LayoutParams infoLp = new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                infoLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
                infoLp.setMargins(dpInt(activity, 18), 0,
                        DETAILS_CINEMATIC ? dpInt(activity, 18) : dpInt(activity, 166), dpInt(activity, 18));
                heroBottom.setLayoutParams(infoLp);

                View oldImdb = findByIdName(activity, "linear_imdb");
                if (oldImdb != null) oldImdb.setVisibility(View.GONE);
                View oldMeta = findByIdName(activity, "linear_meta");
                if (oldMeta != null) oldMeta.setVisibility(View.GONE);

                TextView meta = null;
                View metaFound = heroBottom.findViewWithTag("cigram_title_meta_line");
                if (metaFound instanceof TextView) meta = (TextView) metaFound;
                if (meta == null) {
                    meta = new TextView(activity);
                    meta.setTag("cigram_title_meta_line");
                    meta.setTextColor(0xFFC9D2D7);
                    meta.setTextSize(13f);
                    meta.setTypeface(font);
                    meta.setShadowLayer(dp(activity, 4f), 0f, dp(activity, 1f), 0x99000A10);
                    meta.setGravity(Gravity.RIGHT);
                    meta.setTextDirection(View.TEXT_DIRECTION_RTL);
                    LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
                    mlp.topMargin = dpInt(activity, 8);
                    heroBottom.addView(meta, mlp);
                }
                meta.setGravity(DETAILS_CINEMATIC ? Gravity.CENTER_HORIZONTAL : Gravity.RIGHT);
                java.util.ArrayList<CharSequence> metaBits = new java.util.ArrayList<CharSequence>();
                String rating = jsonText(item, "imdb", "rating", "imdb_rating", "vote_average", "score");
                String quality = jsonText(item, "quality", "resolution", "video_quality");
                String year = jsonText(item, "year", "release_year");
                String category = jsonText(item, "category", "genre", "genres");
                final java.util.ArrayList<String> genres = new java.util.ArrayList<String>();
                for (String g : category.split("[,،|/]")) {
                    if (g.trim().length() > 0 && genres.size() < 3) genres.add(g.trim());
                }
                if (DETAILS_CINEMATIC && !genres.isEmpty()) category = "";   // shown as chips below
                else if (category.length() == 0) {
                    category = "series".equals(inferContentType(item, "")) ? "مسلسل" : "فيلم";
                }
                if (rating.length() > 0) metaBits.add(imdbLogoText(activity, rating, 13f));
                if (quality.length() > 0) metaBits.add(quality);
                if (year.length() > 0) metaBits.add(year);
                if (category.length() > 0) metaBits.add(category);
                android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder();
                for (CharSequence bit : metaBits) {
                    if (line.length() > 0) line.append("  •  ");
                    line.append(bit);
                }
                meta.setText(line);
                if (DETAILS_CINEMATIC) bindGenreChips(activity, heroBottom, genres);
                CigramReport.bindBadges(activity, heroBottom, item);
            }
        }

        bindDetailsHeader(activity, item);

        setTextById(activity, "textview_imdb", valueOrFallback(
                jsonText(item, "imdb", "rating", "imdb_rating", "vote_average", "score"), "0.0"));
        setTextById(activity, "textview_year", jsonText(item, "year", "release_year"));
        setTextById(activity, "textview_category", jsonText(item, "category", "genre", "genres"));
        setTextById(activity, "textview_quality", jsonText(item, "quality", "resolution", "video_quality"));

        styleTitleDetailsActions(activity, item);
        bindTitleDescription(activity, root, item);
        bindTitleExtraInfo(activity, root, item);
        bindTitleEpisodes(activity, root, item);
        CigramReport.bindReportLink(activity, root, item);

        TextView relatedTitle = textViewById(activity, "text_watch_also_title");
        if (relatedTitle != null) {
            relatedTitle.setText("ذات صلة");     // v4.4: the label of details.xml
            CigramDesign.decorateTitle(relatedTitle);
            relatedTitle.setPadding(dpInt(activity, 10), dpInt(activity, 6),
                    dpInt(activity, 16), dpInt(activity, 2));
        }
        bindModernRelatedPreview(activity, item);
        bindModernDetailsActions(activity, item);
        // v10.34: the cast button is set up after the first frame (cast/DLNA init is slow)
        root.post(new Runnable() {
            @Override public void run() {
                if (isAlive(activity)) CigramCastDetails.bind(activity);
            }
        });
        try {
            CigramAnalytics.timerStop(activity, "details_first_render_ms");
            CigramAnalytics.trackOnce(activity, "details_loaded", String.valueOf(System.identityHashCode(activity)),
                    "kind", castIsSeries(item) ? "series" : "movie");
        } catch (Throwable ignored) { }
        ensureStage3Reactions(activity);
        refreshStage3Reactions(activity);
        applyTypefaceRecursively(root, font);
    }

    private static int statusBarHeight(Context context) {
        if (context == null) return 0;
        try {
            int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id != 0) return Math.max(0, context.getResources().getDimensionPixelSize(id));
        } catch (Throwable ignored) { }
        return 0;
    }

    private static void bindDetailsHeader(final Activity activity, final JSONObject item) {
        if (activity == null || item == null || !isAlive(activity)) return;
        View headerView = findByIdName(activity, "linear_header");
        if (!(headerView instanceof LinearLayout)) return;

        final LinearLayout header = (LinearLayout) headerView;
        final ImageView favorite = imageViewById(activity, "image_favorite");
        final ImageView back = imageViewById(activity, "image_back");
        if (favorite == null || back == null) return;

        TextView headerTitle = null;
        View oldHeaderTitle = header.findViewWithTag("cigram_header_title");
        if (oldHeaderTitle instanceof TextView) headerTitle = (TextView) oldHeaderTitle;
        if (headerTitle == null) {
            headerTitle = new TextView(activity);
            headerTitle.setTag("cigram_header_title");
        }

        final String displayTitle = valueOrFallback(jsonText(
                item, "series_title", "title", "name", "title_ar", "title_en"), "بدون عنوان");
        headerTitle.setText(displayTitle);
        headerTitle.setTextColor(Color.WHITE);
        headerTitle.setTextSize(19f);
        headerTitle.setTypeface(cigramTypeface(activity), Typeface.BOLD);
        headerTitle.setSingleLine(true);
        headerTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headerTitle.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        headerTitle.setTextDirection(View.TEXT_DIRECTION_RTL);
        headerTitle.setPadding(dpInt(activity, 8), 0, dpInt(activity, 8), 0);

        int status = statusBarHeight(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        header.setPadding(dpInt(activity, 12), status + dpInt(activity, 4),
                dpInt(activity, 12), dpInt(activity, 4));
        ViewGroup.LayoutParams oldHeaderLp = header.getLayoutParams();
        if (oldHeaderLp != null) {
            oldHeaderLp.height = status + dpInt(activity, 56);
            header.setLayoutParams(oldHeaderLp);
        }
        header.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x52000000, 0x00000000}));

        ViewGroup favoriteParent = (ViewGroup) favorite.getParent();
        if (favoriteParent != null) favoriteParent.removeView(favorite);
        ViewGroup titleParent = (ViewGroup) headerTitle.getParent();
        if (titleParent != null) titleParent.removeView(headerTitle);
        ViewGroup backParent = (ViewGroup) back.getParent();
        if (backParent != null) backParent.removeView(back);
        header.removeAllViews();

        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                dpInt(activity, 48), dpInt(activity, 48));
        favorite.setMinimumWidth(dpInt(activity, 48));
        favorite.setMinimumHeight(dpInt(activity, 48));
        favorite.setPadding(dpInt(activity, 10), dpInt(activity, 10),
                dpInt(activity, 10), dpInt(activity, 10));
        favorite.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        favorite.setBackgroundColor(Color.TRANSPARENT);
        header.addView(favorite, new LinearLayout.LayoutParams(iconLp));

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, dpInt(activity, 48), 1f);
        header.addView(headerTitle, titleLp);

        back.setMinimumWidth(dpInt(activity, 48));
        back.setMinimumHeight(dpInt(activity, 48));
        back.setPadding(dpInt(activity, 10), dpInt(activity, 10),
                dpInt(activity, 10), dpInt(activity, 10));
        back.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
        header.addView(back, new LinearLayout.LayoutParams(iconLp));

        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                performDetailsBack(activity);
            }
        });

        refreshDetailsFavoriteState(activity);
        favorite.setClickable(true);
        favorite.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(final View view) {
                boolean selected = toggleDetailsFavorite(activity, stage3DetailsItem(activity));
                CigramAnalytics.track(activity, selected ? "favorite_added" : "favorite_removed");
                updateDetailsFavoriteIcon(activity, selected);
                view.animate().cancel();
                view.setScaleX(1f);
                view.setScaleY(1f);
                view.animate().scaleX(1.25f).scaleY(1.25f).setDuration(85L)
                        .withEndAction(new Runnable() {
                            @Override public void run() {
                                view.animate().scaleX(1f).scaleY(1f).setDuration(95L).start();
                            }
                        }).start();
            }
        });

        bindDetailsBackDispatcher(activity);
        header.bringToFront();
    }

    private static void bindDetailsBackDispatcher(final Activity activity) {
        if (activity == null || !isAlive(activity)) return;
        synchronized (DETAILS_BACK_SETUP) {
            if (Boolean.TRUE.equals(DETAILS_BACK_SETUP.get(activity))) return;
            DETAILS_BACK_SETUP.put(activity, Boolean.TRUE);
        }
        try {
            if (activity instanceof androidx.activity.ComponentActivity) {
                final androidx.activity.ComponentActivity component =
                        (androidx.activity.ComponentActivity) activity;
                component.getOnBackPressedDispatcher().addCallback(
                        component,
                        new androidx.activity.OnBackPressedCallback(true) {
                            @Override public void handleOnBackPressed() {
                                performDetailsBack(activity);
                            }
                        });
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "Back dispatcher bind failed", error);
        }
    }

    private static void performDetailsBack(Activity activity) {
        if (activity == null || !isAlive(activity)) return;
        if (!activity.isTaskRoot()) {
            if (startDetailsReturnFlight(activity)) return;
            try {
                activity.finish();
                activity.overridePendingTransition(R.anim.cg_details_back_in, R.anim.cg_details_exit);
                return;
            } catch (Throwable ignored) {
                activity.finish();
                return;
            }
        }
        try {
            Class<?> mainClass = Class.forName(activity.getPackageName() + ".MainActivity");
            android.content.Intent intent = new android.content.Intent(activity, mainClass);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "Root back -> MainActivity failed", error);
        }
        activity.finish();
    }

    private static String detailsFavoriteItemsKey(Context context) {
        if (context == null) return "items_0";
        SharedPreferences auth = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE);
        String email = clean(auth.getString("email", ""));
        String token = clean(auth.getString("token", ""));
        String identity = email.length() > 0
                ? email.toLowerCase(java.util.Locale.US)
                : token;
        return "items_" + Integer.toHexString(identity.hashCode());
    }

    private static String detailsFavoriteIdentity(JSONObject item) {
        String key = relatedIdentityKey(item);
        if (key.length() == 0) key = stage3ContentKey(item);
        return key;
    }

    private static JSONArray readDetailsFavorites(Context context) {
        if (context == null) return new JSONArray();
        try {
            SharedPreferences prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE);
            String raw = prefs.getString(detailsFavoriteItemsKey(context), "[]");
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }

    private static boolean isDetailsFavorite(Context context, JSONObject item) {
        if (context == null || item == null) return false;
        String wanted = detailsFavoriteIdentity(item);
        if (wanted.length() == 0) return false;
        JSONArray items = readDetailsFavorites(context);
        for (int i = 0; i < items.length(); i++) {
            JSONObject old = items.optJSONObject(i);
            if (old != null && wanted.equals(detailsFavoriteIdentity(old))) return true;
        }
        return false;
    }

    private static JSONObject detailsFavoriteSnapshot(JSONObject item) {
        JSONObject out = new JSONObject();
        if (item == null) return out;
        String[] fields = new String[]{
                "id", "series_id", "title", "series_title", "name", "type",
                "poster", "series_poster", "season_poster", "backdrop",
                "video", "link", "video_url", "movie_url", "subtitle",
                "season", "episode", "episode_number", "series_episodes_json",
                "year", "release_year", "category", "genre", "genres",
                "imdb", "rating", "quality", "description"
        };
        try {
            for (String field : fields) {
                if (!item.has(field) || item.isNull(field)) continue;
                Object value = item.opt(field);
                if (value != null) out.put(field, value);
            }
            String type = inferContentType(item, "");
            if (type.length() > 0) out.put("type", type);
            String title = jsonText(item, "title", "series_title", "name");
            if (title.length() > 0) out.put("title", title);
            String poster = jsonText(item, "poster", "series_poster", "season_poster", "image", "backdrop");
            if (poster.length() > 0) out.put("poster", poster);
            String video = jsonText(item, "video", "link", "video_url", "movie_url", "url");
            if (video.length() > 0) out.put("video", video);
        } catch (Throwable ignored) { }
        return out;
    }

    private static boolean toggleDetailsFavorite(Context context, JSONObject item) {
        if (context == null || item == null) return false;
        synchronized (DETAILS_FAVORITES_LOCK) {
            String wanted = detailsFavoriteIdentity(item);
            if (wanted.length() == 0) return false;
            SharedPreferences prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE);
            JSONArray oldItems = readDetailsFavorites(context);
            boolean exists = false;
            for (int i = 0; i < oldItems.length(); i++) {
                JSONObject old = oldItems.optJSONObject(i);
                if (old != null && wanted.equals(detailsFavoriteIdentity(old))) {
                    exists = true;
                    break;
                }
            }

            JSONArray out = new JSONArray();
            if (!exists) out.put(detailsFavoriteSnapshot(item));
            for (int i = 0; i < oldItems.length() && out.length() < 100; i++) {
                JSONObject old = oldItems.optJSONObject(i);
                if (old == null) continue;
                if (wanted.equals(detailsFavoriteIdentity(old))) continue;
                out.put(old);
            }
            prefs.edit().putString(detailsFavoriteItemsKey(context), out.toString()).apply();
            try { CigramUserData.touch(context, "favorites"); } catch (Throwable ignored) { }
            return !exists;
        }
    }

    private static void updateDetailsFavoriteIcon(Activity activity, boolean selected) {
        if (activity == null) return;
        ImageView favorite = imageViewById(activity, "image_favorite");
        if (favorite == null) return;
        int drawable = activity.getResources().getIdentifier(
                selected ? "fav_fill" : "fav_border", "drawable", activity.getPackageName());
        if (drawable != 0) favorite.setImageResource(drawable);
        int color = selected ? 0xFFFFFFFF : 0xFFB5C0C6;
        favorite.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
    }

    private static void refreshDetailsFavoriteState(Activity activity) {
        if (activity == null || !isAlive(activity)) return;
        JSONObject item = stage3DetailsItem(activity);
        updateDetailsFavoriteIcon(activity, isDetailsFavorite(activity, item));
    }

    /** v10.45: the cinematic film / series page (full-width picture, centred title, own watch row). */
    private static final boolean DETAILS_CINEMATIC = true;

    private static void styleTitleDetailsActions(Activity activity, JSONObject item) {
        View actionView = findByIdName(activity, "linear_actions");
        if (actionView instanceof LinearLayout) {
            LinearLayout actions = (LinearLayout) actionView;
            actions.setBackgroundColor(0xFF0E0E10);
            // v4.4: physical LTR row, see the reorder below.
            actions.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            actions.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            actions.setPadding(dpInt(activity, 12), dpInt(activity, 10),
                    dpInt(activity, 14), dpInt(activity, 10));
            ViewGroup.LayoutParams lp = actions.getLayoutParams();
            if (lp != null) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                actions.setLayoutParams(lp);
            }
            if (!(actions.getParent() instanceof HorizontalScrollView)
                    && actions.getParent() instanceof ViewGroup) {
                ViewGroup parent = (ViewGroup) actions.getParent();
                int at = parent.indexOfChild(actions);
                parent.removeView(actions);
                final HorizontalScrollView scroll = new HorizontalScrollView(activity);
                scroll.setTag("cigram_title_actions_scroll");
                scroll.setHorizontalScrollBarEnabled(false);
                scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
                scroll.setFillViewport(true);
                scroll.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                actions.setLayoutParams(new HorizontalScrollView.LayoutParams(-1, -2));
                scroll.addView(actions);
                parent.addView(scroll, at, new ViewGroup.LayoutParams(-1, -2));
                scroll.post(new Runnable() {
                    @Override public void run() { scroll.fullScroll(View.FOCUS_RIGHT); }
                });
            }
        }

        View reactions = findByIdName(activity, "linear_reactions");
        if (reactions instanceof LinearLayout && actionView instanceof LinearLayout) {
            LinearLayout actions = (LinearLayout) actionView;
            LinearLayout reactionRow = (LinearLayout) reactions;
            View like = findByIdName(activity, "linear_like");
            View dislike = findByIdName(activity, "linear_dislike");
            if (like != null && like.getParent() != actions) {
                ViewGroup parent = (ViewGroup) like.getParent();
                if (parent != null) parent.removeView(like);
                actions.addView(like);
            }
            if (dislike != null && dislike.getParent() != actions) {
                ViewGroup parent = (ViewGroup) dislike.getParent();
                if (parent != null) parent.removeView(dislike);
                actions.addView(dislike);
            }
            reactionRow.setVisibility(View.GONE);
        }

        View watchView = findByIdName(activity, "button_watch_now");
        if (watchView instanceof TextView) {
            TextView watch = (TextView) watchView;
            watch.setText("شاهد هَسه");     // v4.4: the label of details.xml
            watch.setTextColor(Color.WHITE);
            watch.setTextSize(15.5f);
            watch.setTypeface(cigramTypeface(activity), Typeface.BOLD);
            watch.setAllCaps(false);
            watch.setGravity(Gravity.CENTER);
            watch.setSingleLine(true);
            watch.setPadding(dpInt(activity, 22), 0, dpInt(activity, 22), 0);
            watch.setMinWidth(dpInt(activity, 132));
            watch.setMinimumWidth(dpInt(activity, 132));
            watch.setMinHeight(0);
            watch.setMinimumHeight(0);
            watch.setBackgroundTintList(null);
            if (Build.VERSION.SDK_INT >= 21) {
                watch.setStateListAnimator(null);
                watch.setElevation(0f);
            }
            watch.setBackground(CigramDesign.primaryPill(activity, 23f));
        }

        View laterView = findByIdName(activity, "linear_watch_later");
        if (laterView != null) {
            laterView.setBackground(CigramDesign.ghostPill(activity, 23f));
            laterView.setPadding(dpInt(activity, 16), 0, dpInt(activity, 16), 0);
            laterView.setMinimumWidth(dpInt(activity, 120));
            if (laterView instanceof LinearLayout) {
                ((LinearLayout) laterView).setGravity(Gravity.CENTER);
            }
            ImageView plus = imageViewById(activity, "img_plus");
            if (plus != null) plus.setVisibility(View.GONE);
            TextView label = textViewById(activity, "text_watch_later");
            if (label != null) {
                label.setText("المشاهدة لاحقاً");
                label.setGravity(Gravity.CENTER);
                label.setTextSize(14.5f);
                label.setTypeface(cigramTypeface(activity), Typeface.BOLD);
                label.setSingleLine(true);
                ViewGroup.LayoutParams ll = label.getLayoutParams();
                if (ll != null) {
                    ll.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    label.setLayoutParams(ll);
                }
            }
        }

        View subscribe = findByIdName(activity, "button_subscribe");
        if (subscribe instanceof TextView) {
            TextView sub = (TextView) subscribe;
            sub.setAllCaps(false);
            sub.setTextColor(Color.WHITE);
            sub.setTextSize(14.5f);
            sub.setTypeface(cigramTypeface(activity), Typeface.BOLD);
            sub.setGravity(Gravity.CENTER);
            sub.setSingleLine(true);
            sub.setPadding(dpInt(activity, 16), 0, dpInt(activity, 16), 0);
            sub.setMinHeight(0);
            sub.setMinimumHeight(0);
            sub.setBackgroundTintList(null);
            if (Build.VERSION.SDK_INT >= 21) {
                sub.setStateListAnimator(null);
                sub.setElevation(0f);
            }
            sub.setBackground(CigramDesign.ghostPill(activity, 23f));
        }

        for (String reaction : new String[]{"linear_like", "linear_dislike"}) {
            View host = findByIdName(activity, reaction);
            if (host == null) continue;
            host.setBackground(CigramDesign.ghostPill(activity, 23f));
            host.setPadding(dpInt(activity, 14), 0, dpInt(activity, 14), 0);
            if (host instanceof LinearLayout) ((LinearLayout) host).setGravity(Gravity.CENTER);
        }

        // v4.4: one physical order, left → right: [dislike][like][subscribe][later][watch].
        // «شاهد هَسه» is the far-right (first-read, first-seen) button in every RTL setting; the
        // row is still scrolled to its right end. Views are only re-parented in place: their ids,
        // click listeners and visibility (subscribe = series only) are untouched.
        if (actionView instanceof LinearLayout) {
            LinearLayout actions = (LinearLayout) actionView;
            String[] order = {"linear_dislike", "linear_like", "button_subscribe",
                    "linear_watch_later", "button_watch_now"};
            java.util.ArrayList<View> views = new java.util.ArrayList<View>();
            for (String id : order) {
                View v = findByIdName(activity, id);
                if (v != null && v.getParent() == actions) views.add(v);
            }
            for (View v : views) actions.removeView(v);
            for (View v : views) {
                LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dpInt(activity, 46));
                vlp.leftMargin = dpInt(activity, 4);
                vlp.rightMargin = dpInt(activity, 4);
                vlp.gravity = Gravity.CENTER_VERTICAL;
                actions.addView(v, vlp);
            }
            if (DETAILS_CINEMATIC) arrangeCinematicActions(activity, actions);
        }
    }

    /** v10.45: genre chips under the meta line (centred, up to three). */
    private static void bindGenreChips(Activity activity, LinearLayout heroBottom, java.util.List<String> genres) {
        View old = heroBottom.findViewWithTag("cigram_genre_chips");
        if (old != null) heroBottom.removeView(old);
        if (genres == null || genres.isEmpty()) return;
        LinearLayout row = new LinearLayout(activity);
        row.setTag("cigram_genre_chips");
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setGravity(Gravity.CENTER);
        for (String g : genres) {
            TextView chip = new TextView(activity);
            chip.setText(g);
            chip.setTextColor(Color.WHITE);
            chip.setTextSize(11.5f);
            chip.setTypeface(cigramTypeface(activity));
            chip.setSingleLine(true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dpInt(activity, 11), dpInt(activity, 3), dpInt(activity, 11), dpInt(activity, 3));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x24FFFFFF);
            bg.setCornerRadius(dp(activity, 14f));
            bg.setStroke(Math.max(1, dpInt(activity, 1)), 0x33FFFFFF);
            chip.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dpInt(activity, 3);
            lp.rightMargin = dpInt(activity, 3);
            row.addView(chip, lp);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dpInt(activity, 9);
        heroBottom.addView(row, rlp);
    }

    /** v10.45: «شاهد هَسه» gets its own full-width row; the other buttons sit in a centred row below it. */
    private static void arrangeCinematicActions(Activity activity, LinearLayout actions) {
        View watch = findByIdName(activity, "button_watch_now");
        if (watch == null || !(actions.getParent() instanceof ViewGroup)) return;
        if (watch.getParent() != actions) return;            // already moved
        ViewGroup scroll = (ViewGroup) actions.getParent();
        if (!(scroll.getParent() instanceof ViewGroup)) return;
        ViewGroup page = (ViewGroup) scroll.getParent();
        int at = page.indexOfChild(scroll);
        actions.removeView(watch);
        LinearLayout row = new LinearLayout(activity);
        row.setTag("cigram_watch_row");
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(0xFF0E0E10);
        row.setPadding(dpInt(activity, 16), dpInt(activity, 14), dpInt(activity, 16), dpInt(activity, 2));
        if (watch instanceof TextView) {
            ((TextView) watch).setMinWidth(0);
            ((TextView) watch).setMinimumWidth(0);
            ((TextView) watch).setTextSize(16.5f);
        }
        watch.setBackground(CigramDesign.primaryPill(activity, 26f));
        row.addView(watch, new LinearLayout.LayoutParams(-1, dpInt(activity, 52)));
        page.addView(row, at, new LinearLayout.LayoutParams(-1, -2));
        actions.setGravity(Gravity.CENTER);
    }

    private static void bindTitleDescription(
            final Activity activity,
            final LinearLayout root,
            JSONObject item
    ) {
        final TextView description = textViewById(activity, "textview_description");
        if (description == null) return;
        final String value = valueOrFallback(
                jsonText(item, "description", "overview", "plot", "synopsis"),
                "لا يوجد وصف متاح");
        description.setText(value);
        description.setTextColor(0xFFE8EEF1);
        description.setTextSize(15f);
        description.setLineSpacing(0f, 1.22f);     // v4.4: Arabic synopsis needs air between lines
        description.setGravity(Gravity.RIGHT);
        description.setTextDirection(View.TEXT_DIRECTION_RTL);
        description.setBackgroundColor(0xFF0E0E10);
        description.setPadding(dpInt(activity, 18), dpInt(activity, 14),
                dpInt(activity, 18), dpInt(activity, 4));
        description.setMaxLines(4);
        description.setEllipsize(android.text.TextUtils.TruncateAt.END);

        TextView toggle = null;
        View found = root.findViewWithTag("cigram_description_toggle");
        if (found instanceof TextView) toggle = (TextView) found;
        if (toggle == null) {
            toggle = new TextView(activity);
            toggle.setTag("cigram_description_toggle");
            toggle.setText("عرض المزيد");
            toggle.setTextColor(0xFFE21B14);
            toggle.setTextSize(13f);
            toggle.setGravity(Gravity.RIGHT);
            toggle.setPadding(dpInt(activity, 18), dpInt(activity, 2),
                    dpInt(activity, 18), dpInt(activity, 12));
            int pos = root.indexOfChild(description);
            root.addView(toggle, Math.min(root.getChildCount(), pos + 1),
                    new LinearLayout.LayoutParams(-1, -2));
        }
        final TextView toggleView = toggle;
        final boolean[] expanded = new boolean[]{false};
        View.OnClickListener listener = new View.OnClickListener() {
            @Override public void onClick(View v) {
                expanded[0] = !expanded[0];
                description.setMaxLines(expanded[0] ? Integer.MAX_VALUE : 4);
                description.setEllipsize(expanded[0] ? null
                        : android.text.TextUtils.TruncateAt.END);
                toggleView.setText(expanded[0] ? "عرض أقل" : "عرض المزيد");
            }
        };
        description.setClickable(true);
        description.setOnClickListener(listener);
        toggleView.setClickable(true);
        toggleView.setOnClickListener(listener);
        toggleView.setVisibility(View.VISIBLE);
        description.post(new Runnable() {
            @Override public void run() {
                if (!isAlive(activity) || expanded[0]) return;
                android.text.Layout layout = description.getLayout();
                boolean truncated = false;
                if (layout != null && layout.getLineCount() > 0) {
                    int last = Math.min(layout.getLineCount() - 1, 3);
                    truncated = layout.getLineCount() >= 4
                            && layout.getEllipsisCount(last) > 0;
                }
                toggleView.setVisibility(truncated ? View.VISIBLE : View.GONE);
                description.setClickable(truncated);
            }
        });
    }

    private static void bindTitleExtraInfo(Activity activity, LinearLayout root, JSONObject item) {
        View old = root.findViewWithTag("cigram_title_extra_info");
        if (old != null) root.removeView(old);
        LinearLayout box = new LinearLayout(activity);
        box.setTag("cigram_title_extra_info");
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box.setPadding(dpInt(activity, 18), dpInt(activity, 8),
                dpInt(activity, 18), dpInt(activity, 10));
        String[][] fields = new String[][]{
                {"البلد", "country", "production_country", "origin_country"},
                {"اللغة", "language", "original_language"},
                {"المخرج", "director"},
                {"الممثلون", "actors", "cast", "stars"}
        };
        int count = 0;
        for (String[] field : fields) {
            String[] keys = new String[field.length - 1];
            System.arraycopy(field, 1, keys, 0, keys.length);
            String value = jsonText(item, keys);
            if (value.length() == 0) continue;
            TextView row = new TextView(activity);
            row.setText(field[0] + ":  " + value);
            row.setTextColor(0xFFB8C3C8);
            row.setTextSize(14f);
            row.setGravity(Gravity.RIGHT);
            row.setTextDirection(View.TEXT_DIRECTION_RTL);
            row.setPadding(0, dpInt(activity, 4), 0, dpInt(activity, 4));
            box.addView(row, new LinearLayout.LayoutParams(-1, -2));
            count++;
        }
        if (count == 0) return;
        View related = findByIdName(activity, "linear_watch_also");
        int pos = related == null ? root.getChildCount() : root.indexOfChild(related);
        if (pos < 0) pos = root.getChildCount();
        root.addView(box, pos, new LinearLayout.LayoutParams(-1, -2));
    }

    private static void bindTitleEpisodes(
            final Activity activity,
            LinearLayout root,
            final JSONObject item
    ) {
        View old = root.findViewWithTag("cigram_title_episodes");
        if (old != null) root.removeView(old);
        if (!stage3IsSeriesItem(item)) return;
        final JSONArray episodes = stage6EpisodeArray(item);
        if (episodes.length() == 0) return;
        CigramEpisodeAds.preload(activity); // so the ad is ready when an episode is picked

        final LinearLayout section = new LinearLayout(activity);
        section.setTag("cigram_title_episodes");
        section.setOrientation(LinearLayout.VERTICAL);
        section.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        section.setPadding(dpInt(activity, 16), dpInt(activity, 8),
                dpInt(activity, 16), dpInt(activity, 14));

        TextView heading = new TextView(activity);
        heading.setText("الحلقات");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(20f);
        heading.setTypeface(cigramTypeface(activity), Typeface.BOLD);
        heading.setGravity(Gravity.RIGHT);
        section.addView(heading, new LinearLayout.LayoutParams(-1, dpInt(activity, 44)));

        final LinearLayout episodeHost = new LinearLayout(activity);
        episodeHost.setOrientation(LinearLayout.VERTICAL);
        episodeHost.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        java.util.LinkedHashSet<Integer> seasons = new java.util.LinkedHashSet<Integer>();
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject e = episodes.optJSONObject(i);
            int season = e == null ? 1 : intText(e, "season", "season_number");
            if (season <= 0) season = 1;
            seasons.add(Integer.valueOf(season));
        }
        final HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        final LinearLayout tabs = new LinearLayout(activity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        // v2.9: explicit LTR container, tabs added highest-first, so on screen the order is
        // الموسم 1 (far right) ← الموسم 2 ← الموسم 3 ... whether or not the app honours RTL.
        tabs.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        tabs.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        tabs.setMinimumWidth(activity.getResources().getDisplayMetrics().widthPixels - dpInt(activity, 32));
        scroll.addView(tabs, new HorizontalScrollView.LayoutParams(-2, -2));
        section.addView(scroll, new LinearLayout.LayoutParams(-1, dpInt(activity, 50)));

        int firstSeason = seasons.iterator().next().intValue();
        final String cigramSeriesKey = CigramContent.seriesKey(item);
        try {
            int remembered = CigramUserData.lastSeason(activity, cigramSeriesKey);
            if (remembered > 0 && seasons.contains(Integer.valueOf(remembered))) {
                firstSeason = remembered;    // reopen the season the viewer used last
            }
        } catch (Throwable ignored) { }
        final int[] cigramShownSeason = new int[]{firstSeason};
        java.util.ArrayList<Integer> seasonOrder = new java.util.ArrayList<Integer>(seasons);
        java.util.Collections.sort(seasonOrder);
        java.util.Collections.reverse(seasonOrder);   // highest first = leftmost
        for (final Integer season : seasonOrder) {
            final TextView tab = new TextView(activity);
            tab.setText("الموسم " + season);
            tab.setTextColor(Color.WHITE);
            tab.setTextSize(13f);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(dpInt(activity, 14), 0, dpInt(activity, 14), 0);
            tab.setBackground(roundedDrawable(
                    season.intValue() == firstSeason ? 0xFFE21B14 : 0xFF1B1B1F,
                    dp(activity, 18f)));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, dpInt(activity, 38));
            tlp.setMargins(dpInt(activity, 4), dpInt(activity, 4),
                    dpInt(activity, 4), dpInt(activity, 4));
            tabs.addView(tab, tlp);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    for (int n = 0; n < tabs.getChildCount(); n++) {
                        View child = tabs.getChildAt(n);
                        child.setBackground(roundedDrawable(
                                child == tab ? 0xFFE21B14 : 0xFF1B1B1F,
                                dp(activity, 18f)));
                    }
                    cigramShownSeason[0] = season.intValue();
                    try { CigramUserData.setLastSeason(activity, cigramSeriesKey, season.intValue()); }
                    catch (Throwable ignored) { }
                    renderTitleEpisodeRows(activity, item, episodes,
                            season.intValue(), episodeHost);
                }
            });
        }
        scroll.post(new Runnable() {
            @Override public void run() { scroll.scrollTo(Math.max(0, tabs.getWidth() - scroll.getWidth()), 0); }
        });
        section.addView(episodeHost, new LinearLayout.LayoutParams(-1, -2));
        renderTitleEpisodeRows(activity, item, episodes, firstSeason, episodeHost);
        final JSONObject cigramSeriesItem = item;
        synchronized (EPISODE_REFRESH) {
            EPISODE_REFRESH.put(activity, new Runnable() {
                @Override public void run() {
                    renderTitleEpisodeRows(activity, cigramSeriesItem, episodes,
                            cigramShownSeason[0], episodeHost);
                }
            });
        }

        View related = findByIdName(activity, "linear_watch_also");
        int pos = related == null ? root.getChildCount() : root.indexOfChild(related);
        if (pos < 0) pos = root.getChildCount();
        root.addView(section, pos, new LinearLayout.LayoutParams(-1, -2));
    }

    private static final Map<Activity, Runnable> EPISODE_REFRESH =
            new WeakHashMap<Activity, Runnable>();

    /** Re-draws the episode rows (watched / new state) when the viewer comes back from the player. */
    private static void refreshTitleEpisodeState(Activity activity) {
        Runnable job;
        synchronized (EPISODE_REFRESH) { job = EPISODE_REFRESH.get(activity); }
        if (job != null) {
            try { job.run(); } catch (Throwable ignored) { }
        }
    }

    private static void renderTitleEpisodeRows(
            final Activity activity,
            final JSONObject series,
            JSONArray episodes,
            int wantedSeason,
            LinearLayout host
    ) {
        host.removeAllViews();
        for (int i = 0; i < episodes.length(); i++) {
            final JSONObject episode = episodes.optJSONObject(i);
            if (episode == null) continue;
            int season = intText(episode, "season", "season_number");
            if (season <= 0) season = 1;
            if (season != wantedSeason) continue;

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            // v2.9: LTR container with [details][preview]: the preview sits on the right and the
            // details directly beside it, regardless of RTL support.
            row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dpInt(activity, 8), dpInt(activity, 7),
                    dpInt(activity, 8), dpInt(activity, 7));
            row.setBackground(roundedDrawable(0xFF071E28, dp(activity, 10f)));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
            rlp.setMargins(0, dpInt(activity, 4), 0, dpInt(activity, 4));
            host.addView(row, rlp);

            ImageView thumb = new ImageView(activity);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (Build.VERSION.SDK_INT >= 21) thumb.setClipToOutline(true);
            String image = jsonText(episode,
                    "backdrop", "still", "thumbnail", "poster", "image");
            if (image.length() == 0) image = jsonText(series,
                    "backdrop", "poster", "series_poster", "image");
            if (image.length() > 0) loadDynamicPoster(activity, image, thumb);
            LinearLayout texts = new LinearLayout(activity);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(0, -2, 1f);
            xlp.setMargins(dpInt(activity, 10), 0, dpInt(activity, 10), 0);
            row.addView(texts, xlp);
            // v5.1 (episode cards, RTL): the thumbnail is a rounded card that carries the progress strip.
            FrameLayout thumbBox = new FrameLayout(activity);
            thumbBox.setBackground(roundedDrawable(0xFF1B1B1F, dp(activity, 8f)));
            if (Build.VERSION.SDK_INT >= 21) {
                final float thumbRadius = dp(activity, 8f);
                thumbBox.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override public void getOutline(View v, android.graphics.Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), thumbRadius);
                    }
                });
                thumbBox.setClipToOutline(true);
            }
            thumbBox.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
            row.addView(thumbBox, new LinearLayout.LayoutParams(
                    dpInt(activity, 112), dpInt(activity, 68)));

            int number = intText(episode, "episode", "episode_number", "ep", "number");
            try {
                addEpisodeProgressBar(activity, series, episode, season, number, thumbBox);
            } catch (Throwable ignored) { }
            // series name in dim gray above each episode (as in Cinemana)
            String seriesName = jsonText(series, "series_title", "title", "name");
            if (seriesName.length() > 0) {
                TextView seriesLine = new TextView(activity);
                seriesLine.setText(seriesName);
                seriesLine.setTextColor(0xFF7D8B93);
                seriesLine.setTextSize(12f);
                seriesLine.setGravity(Gravity.RIGHT);
                seriesLine.setSingleLine(true);
                seriesLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
                seriesLine.setTypeface(cigramTypeface(activity), Typeface.NORMAL);
                texts.addView(seriesLine, new LinearLayout.LayoutParams(-1, -2));
            }
            TextView title = new TextView(activity);
            String episodeTitle = jsonText(episode, "title", "name", "episode_title");
            if (episodeTitle.length() == 0) {
                episodeTitle = number > 0 ? String.valueOf(number) : "حلقة";
            }
            // v10.51: only the episode's own name (the Admin app saves the number as the name when none is typed)
            title.setText(episodeTitle);
            title.setTextColor(Color.WHITE);
            title.setTextSize(14f);
            title.setGravity(Gravity.RIGHT);
            title.setMaxLines(Integer.MAX_VALUE);
            title.setTypeface(cigramTypeface(activity), Typeface.BOLD);
            texts.addView(title, new LinearLayout.LayoutParams(-1, -2));

            String duration = jsonText(episode, "duration", "runtime");
            if (duration.length() > 0) {
                TextView durationView = new TextView(activity);
                durationView.setText(duration);
                durationView.setTextColor(0xFF92A3AC);
                durationView.setTextSize(12f);
                durationView.setGravity(Gravity.RIGHT);
                texts.addView(durationView, new LinearLayout.LayoutParams(-1, -2));
            }

            try {
                addEpisodeStateChips(activity, series, episode, season, number, texts);
            } catch (Throwable ignored) { }

            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // v10.35: rewarded unlock first (once per episode per app session), then the player
                    CigramRewardUnlock.unlock(activity, rewardKeyOf(episode), true, null, new Runnable() {
                        @Override public void run() {
                            openPlayerForTitleItem(activity, episode, series, true);
                        }
                    });
                }
            });
        }
    }

    /** v5.1: only the "جديد" chip stays as text; watched/progress now live on the thumbnail strip. */
    private static void addEpisodeStateChips(Activity activity, JSONObject series, JSONObject episode,
                                             int season, int number, LinearLayout texts) {
        if (number <= 0) return;
        String seriesKey = CigramContent.seriesKey(series);
        String key = CigramContent.episodeKey(seriesKey, season, number);
        if (key.length() == 0) return;
        boolean seen = CigramUserData.isSeen(activity, key);
        boolean isNew = !seen && CigramContent.isRecent(
                CigramContent.addedAtMs(episode), System.currentTimeMillis());
        if (!isNew) return;

        LinearLayout chips = new LinearLayout(activity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        chips.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        View t = CigramHome.newBadge(activity, 13);
        if (t == null) t = CigramHome.badgeView(activity, "جديد", CigramContent.badgeColor("new"), 11f);
        ((LinearLayout.LayoutParams) t.getLayoutParams()).setMarginStart(dpInt(activity, 6));
        chips.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dpInt(activity, 3);
        texts.addView(chips, lp);
    }

    /**
     * v5.1 (episode cards): a 4dp strip along the bottom of the episode thumbnail. It fills from the
     * right (RTL): red while watching, green + a check badge once finished. Same thresholds as before.
     */
    private static void addEpisodeProgressBar(Activity activity, JSONObject series, JSONObject episode,
                                              int season, int number, FrameLayout box) {
        if (number <= 0) return;
        String seriesKey = CigramContent.seriesKey(series);
        String key = CigramContent.episodeKey(seriesKey, season, number);
        if (key.length() == 0) return;
        boolean seen = CigramUserData.isSeen(activity, key);
        float progress = CigramUserData.progressOf(activity, key);
        boolean done = progress >= 0.95f || (seen && progress >= 0.9f);
        float shown = done ? 1f : Math.max(0f, Math.min(1f, progress));
        if (shown <= 0f) return;

        LinearLayout track = new LinearLayout(activity);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        track.setWeightSum(1f);
        track.setBackgroundColor(0x99000000);
        View rest = new View(activity);
        View fill = new View(activity);
        fill.setBackgroundColor(done ? 0xFF3DDC97 : 0xFFE21B14);
        track.addView(rest, new LinearLayout.LayoutParams(0, -1, 1f - shown));   // physical [rest][fill]: fill on the right
        track.addView(fill, new LinearLayout.LayoutParams(0, -1, shown));
        box.addView(track, new FrameLayout.LayoutParams(-1, dpInt(activity, 4), Gravity.BOTTOM));

        if (done) {
            // v5.29: a watched episode is covered (full width and height of its poster) by a
            // green layer with a check icon. The state comes from the logged-in account.
            CigramWatchedMark.cover(box);
        }
    }

    private static void openPlayerForTitleItem(
            Activity activity,
            JSONObject source,
            JSONObject parentSeries,
            boolean episodeTransition
    ) {
        if (activity == null || source == null) return;
        String video = jsonText(source,
                "video", "link", "video_url", "movie_url", "stream_url", "play_url");
        if (video.length() == 0 && parentSeries == null && stage3IsSeriesItem(source)) {
            JSONArray eps = stage6EpisodeArray(source);
            JSONObject first = eps.optJSONObject(0);
            if (first != null) {
                openPlayerForTitleItem(activity, first, source, true);
                return;
            }
        }
        if (video.length() == 0) {
            Toast.makeText(activity, "رابط الفيديو غير متوفر", Toast.LENGTH_SHORT).show();
            return;
        }
        // 2026: a malformed link from the Admin app (no scheme, no host, "null", spaces ...) is explained here
        // instead of opening a player that can only fail
        if (CigramPlaybackError.validateUrl(video) != CigramPlaybackError.UNKNOWN) {
            Toast.makeText(activity, CigramPlaybackError.message(CigramPlaybackError.INVALID_URL), Toast.LENGTH_LONG).show();
            android.util.Log.w("CigramDetails", "invalid video link refused: " + video);
            return;
        }
        try {
            boolean isEp = parentSeries != null;
            CigramAnalytics.track(activity, episodeTransition ? "episode_switch"
                            : (isEp ? "episode_open" : "watch_now_clicked"),
                    "kind", isEp ? "episode" : "movie",
                    "season", jsonText(source, "season"),
                    "episode", jsonText(source, "episode"));
            CigramAnalytics.track(activity, "play_requested", "kind", isEp ? "episode" : "movie");
        } catch (Throwable ignored) { }
        if (CigramCastDetails.tryCast(activity, source, parentSeries)) return;
        try {
            java.io.File dataRoot = activity.getFilesDir().getParentFile();
            java.io.File target = new java.io.File(dataRoot, "video_url.txt");
            java.io.FileOutputStream out = new java.io.FileOutputStream(target, false);
            try {
                out.write(video.getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "video_url.txt write failed", error);
        }
        try {
            Class<?> playerClass = Class.forName(activity.getPackageName() + ".PlayerActivity");
            android.content.Intent intent = new android.content.Intent(activity, playerClass);
            String title = jsonText(source, "title", "name", "episode_title");
            if (title.length() == 0 && parentSeries != null) {
                title = jsonText(parentSeries, "series_title", "title", "name");
            }
            intent.putExtra("video", video);
            intent.putExtra("video_url", video);
            intent.putExtra("movie_url", video);
            intent.putExtra("link", video);
            intent.putExtra("title", title);
            String subtitle = jsonText(source, "subtitle", "subtitle_url");
            if (subtitle.length() > 0) {
                intent.putExtra("subtitle", subtitle);
                intent.putExtra("subtitle_url", subtitle);
            }
            for (String key : new String[]{"id", "type", "series_id", "series_title",
                    "season", "episode", "episode_number", "skip_segments"}) {
                String value = jsonText(source, key);
                if (value.length() == 0 && parentSeries != null) value = jsonText(parentSeries, key);
                if (value.length() > 0) intent.putExtra(key, value);
            }
            if (parentSeries != null && jsonText(source, "series_title").length() == 0
                    && jsonText(parentSeries, "series_title").length() == 0) {
                // the player keys history/progress by the series title, never by the episode title
                String parentTitle = jsonText(parentSeries, "title", "name", "title_ar", "title_en");
                if (parentTitle.length() > 0) intent.putExtra("series_title", parentTitle);
            }
            String poster = jsonText(source, "backdrop", "poster", "image");
            if (poster.length() == 0 && parentSeries != null) {
                poster = jsonText(parentSeries, "backdrop", "poster", "series_poster", "image");
            }
            if (poster.length() > 0) intent.putExtra("poster", poster);
            if (START_OVER_AT > 0L && android.os.SystemClock.uptimeMillis() - START_OVER_AT < 60000L) {
                intent.putExtra("cigram_start_over", true);
            }
            START_OVER_AT = 0L;
            intent.putExtra("episode_transition", episodeTransition);
            activity.startActivity(intent);
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "player launch failed", error);
            Toast.makeText(activity, "تعذر فتح المشغل", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- helpers for CigramCastDetails (v2.6)
    static JSONObject castDetailsItem(Activity activity) { return stage3DetailsItem(activity); }
    static boolean castIsSeries(JSONObject item) { return stage3IsSeriesItem(item); }
    static JSONArray castEpisodes(JSONObject series) { return stage6EpisodeArray(series); }

    private static void bindModernDetailsActions(final Activity activity, final JSONObject item) {
        if (activity == null || item == null) return;
        // v4.4: the «شاهد هَسه» chooser shows an interstitial on both choices; warm it up now
        // (respects ad-free users and the shared caps, does nothing when one is already ready).
        try { CigramEpisodeAds.preload(activity); } catch (Throwable ignored) { }

        final View watchNow = findByIdName(activity, "button_watch_now");
        if (watchNow != null) {
            watchNow.setClickable(true);
            watchNow.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    final JSONObject current = stage3DetailsItem(activity);
                    watchNowWithAd(activity, current);
                }
            });
        }

        final View watchLater = findByIdName(activity, "linear_watch_later");
        if (watchLater != null) {
            watchLater.setClickable(true);
            watchLater.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    if (!stage3WatchLaterAvailable(activity)) {
                        Toast.makeText(activity, "سجّل دخولك لحفظ العناصر في المشاهدة لاحقًا",
                                Toast.LENGTH_SHORT).show();
                        try {
                            activity.startActivity(new android.content.Intent(activity, AccountActivity.class));
                        } catch (Throwable ignored) { }
                        return;
                    }
                    boolean selected = toggleStage3WatchLater(activity, stage3DetailsItem(activity));
                    CigramAnalytics.track(activity, selected ? "watch_later_added" : "watch_later_removed");
                    Toast.makeText(activity,
                            selected ? "تمت الإضافة إلى المشاهدة لاحقًا" : "تمت الإزالة من المشاهدة لاحقًا",
                            Toast.LENGTH_SHORT).show();
                    refreshStage3Details(activity);
                }
            });
        }

        final View subscribeView = findByIdName(activity, "button_subscribe");
        if (subscribeView instanceof TextView) {
            final TextView subscribe = (TextView) subscribeView;
            if (!stage3IsSeriesItem(item)) {
                subscribe.setVisibility(View.GONE);
            } else {
                subscribe.setVisibility(View.VISIBLE);
                subscribe.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        JSONObject current = stage3DetailsItem(activity);
                        boolean subscribed = toggleStage3Subscription(activity, current);
                        CigramAnalytics.track(activity, subscribed ? "subscribe_added" : "subscribe_removed");
                        Toast.makeText(activity,
                                subscribed ? "تم الاشتراك" : "تم إلغاء الاشتراك",
                                Toast.LENGTH_SHORT).show();
                        if (subscribed) {
                            stage3ForceSubscriptionNotificationsEnabled(activity, current);
                            stage6RequestNotificationPermissionIfNeeded(activity, true);
                        }
                        stage6RefreshBackgroundJob(activity);
                        refreshStage3Details(activity);
                    }
                });
            }
        }
    }

    private static void refreshModernDetailsActions(Activity activity, JSONObject item) {
        if (activity == null || item == null) return;

        View watchLater = findByIdName(activity, "linear_watch_later");
        if (watchLater != null) {
            boolean selected = stage3WatchLaterContains(activity, item);
            ImageView plus = imageViewById(activity, "img_plus");
            TextView label = textViewById(activity, "text_watch_later");
            if (plus != null) {
                plus.setColorFilter(selected ? 0xFFE21B14 : Color.WHITE);
            }
            if (label != null) {
                label.setTextColor(selected ? 0xFFE21B14 : Color.WHITE);
            }
        }

        View subscribeView = findByIdName(activity, "button_subscribe");
        if (subscribeView instanceof TextView) {
            TextView subscribe = (TextView) subscribeView;
            if (!stage3IsSeriesItem(item)) {
                subscribe.setVisibility(View.GONE);
            } else {
                subscribe.setVisibility(View.VISIBLE);
                boolean subscribed = stage3FindSubscription(activity, item) != null;
                subscribe.setText(subscribed ? "تم الاشتراك" : "الاشتراك");
            }
        }
    }

    // v10.34: related titles are computed off the main thread and cached per title, so the
    // details page opens at once; the «ذات صلة» grid fills in a moment later (instantly if cached).
    private static final java.util.Map<String, JSONArray> RELATED_CACHE =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, JSONArray>(32, .75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, JSONArray> e) {
                    return size() > 40;
                }
            });
    private static final java.util.Map<Activity, String> RELATED_PENDING =
            java.util.Collections.synchronizedMap(new WeakHashMap<Activity, String>());

    private static void bindModernRelatedPreview(final Activity activity, final JSONObject current) {
        if (activity == null || current == null) return;
        final String type = inferContentType(current, "");
        final String category = jsonText(current, "category", "genre", "genres");
        final String excludeKey = relatedIdentityKey(current);
        final String cacheKey = type + "|" + category + "|" + excludeKey;
        JSONArray cached = RELATED_CACHE.get(cacheKey);
        if (cached != null) {
            buildModernRelatedPreview(activity, cached);
            return;
        }
        if (cacheKey.equals(RELATED_PENDING.get(activity))) return;
        RELATED_PENDING.put(activity, cacheKey);
        View oldRow1 = findByIdName(activity, "linear_watch_also_row1");
        View oldRow2 = findByIdName(activity, "linear_watch_also_row2");
        if (oldRow1 != null) oldRow1.setVisibility(View.GONE);
        if (oldRow2 != null) oldRow2.setVisibility(View.GONE);
        final Context app = activity.getApplicationContext();
        IO.execute(new Runnable() {
            @Override public void run() {
                JSONArray result;
                try {
                    String raw = CATALOG_MEMORY_JSON;
                    if (!looksLikeCatalogText(raw)) raw = catalogPrefs(app).getString(KEY_JSON, "");
                    result = extractRelatedCatalogItems(raw, type, category, excludeKey, 0);
                } catch (Throwable error) {
                    android.util.Log.w("CigramDetails", "related lookup failed", error);
                    result = new JSONArray();
                }
                RELATED_CACHE.put(cacheKey, result);
                final JSONArray related = result;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (!isAlive(activity)) return;
                        if (!cacheKey.equals(RELATED_PENDING.get(activity))) return;
                        RELATED_PENDING.remove(activity);
                        try {
                            buildModernRelatedPreview(activity, related);
                        } catch (Throwable error) {
                            android.util.Log.w("CigramDetails", "related build failed", error);
                        }
                    }
                });
            }
        });
    }

    private static void buildModernRelatedPreview(final Activity activity, final JSONArray related) {
        if (activity == null || related == null || !isAlive(activity)) return;

        View oldRow1 = findByIdName(activity, "linear_watch_also_row1");
        View oldRow2 = findByIdName(activity, "linear_watch_also_row2");
        if (oldRow1 != null) oldRow1.setVisibility(View.GONE);
        if (oldRow2 != null) oldRow2.setVisibility(View.GONE);

        View parentView = findByIdName(activity, "linear_watch_also");
        final TextView more = textViewById(activity, "text_watch_also_more");
        if (parentView instanceof LinearLayout) {
            LinearLayout parent = (LinearLayout) parentView;
            parent.setBackgroundColor(0xFF0E0E10);
            parent.setPadding(dpInt(activity, 8), dpInt(activity, 12),
                    dpInt(activity, 8), dpInt(activity, 12));

            View oldGrid = parent.findViewWithTag("cigram_related_grid");
            if (oldGrid != null && oldGrid.getParent() == parent) parent.removeView(oldGrid);

            LinearLayout grid = new LinearLayout(activity);
            grid.setTag("cigram_related_grid");
            grid.setOrientation(LinearLayout.VERTICAL);
            grid.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            grid.setBackgroundColor(Color.TRANSPARENT);
            grid.setPadding(0, 0, 0, 0);

            int shown = Math.min(6, related.length());
            for (int start = 0; start < shown; start += 3) {
                LinearLayout row = new LinearLayout(activity);
                // v4.4: the first related title sits at the far RIGHT of its row (physical order)
                View[] cards = new View[3];
                for (int slot = 0; slot < 3; slot++) {
                    int index = start + slot;
                    View card;
                    if (index < shown) {
                        JSONObject item = related.optJSONObject(index);
                        card = item == null ? null : makeSectionGridCard(activity, item);
                    } else {
                        card = null;
                    }
                    if (card == null) {
                        card = new View(activity);
                        card.setVisibility(View.INVISIBLE);
                    }
                    cards[slot] = card;
                }
                CigramDesign.fillGridRow(row, cards, dpInt(activity, 5));

                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (start > 0) rowLp.topMargin = dpInt(activity, 16);
                grid.addView(row, rowLp);
            }

            int insertAt = more != null && more.getParent() == parent
                    ? parent.indexOfChild(more) : parent.getChildCount();
            parent.addView(grid, Math.max(0, insertAt), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (more != null) {
            boolean showMore = related.length() > 6;
            more.setVisibility(showMore ? View.VISIBLE : View.GONE);
            more.setText("عرض المزيد");
            more.setTextColor(Color.WHITE);
            more.setTextSize(14.5f);
            more.setTypeface(cigramTypeface(activity), Typeface.BOLD);
            more.setGravity(Gravity.CENTER);
            more.setClickable(showMore);
            more.setBackground(CigramDesign.ghostPill(activity, 23f));     // v4.4
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dpInt(activity, 48));
            lp.leftMargin = dpInt(activity, 4);
            lp.rightMargin = dpInt(activity, 4);
            lp.topMargin = showMore ? dpInt(activity, 16) : 0;
            lp.bottomMargin = showMore ? dpInt(activity, 4) : 0;
            more.setLayoutParams(lp);
            if (showMore) {
                more.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        try {
                            JSONObject active = stage3DetailsItem(activity);
                            Class<?> sectionClass = Class.forName(
                                    activity.getPackageName() + ".SectionActivity");
                            android.content.Intent intent = new android.content.Intent(activity, sectionClass);
                            intent.putExtra("section_id", "__related__");
                            intent.putExtra("section_title", "ذات صلة");
                            intent.putExtra("section_name", "ذات صلة");
                            intent.putExtra("section_mode", "related");
                            intent.putExtra("section_type", inferContentType(active, ""));
                            intent.putExtra("related_category", jsonText(active, "category", "genre", "genres"));
                            intent.putExtra("related_exclude_key", relatedIdentityKey(active));
                            activity.startActivity(intent);
                        } catch (Throwable error) {
                            android.util.Log.w("CigramRelated", "Unable to open related section", error);
                        }
                    }
                });
            } else {
                more.setOnClickListener(null);
            }
        }
    }

    private static JSONArray extractRelatedCatalogItems(
            String raw,
            String wantedType,
            String wantedCategory,
            String excludeKey,
            int limit
    ) {
        JSONArray out = new JSONArray();
        if (raw == null || raw.trim().length() == 0) return out;
        String type = normalizeSearch(wantedType);
        String category = wantedCategory == null ? "" : wantedCategory.trim();

        try {
            java.util.ArrayList<JSONObject> sourceItems =
                    new java.util.ArrayList<JSONObject>();
            synchronized (CATALOG_MEMORY_LOCK) {
                if (!CATALOG_MEMORY_ITEMS.isEmpty()) sourceItems.addAll(CATALOG_MEMORY_ITEMS);
            }
            if (sourceItems.isEmpty()) {
                Object root = new JSONTokener(raw).nextValue();
                JSONArray index = buildSearchIndex(root);
                for (int i = 0; i < index.length(); i++) {
                    JSONObject source = index.optJSONObject(i);
                    if (source != null) sourceItems.add(source);
                }
            }

            java.util.ArrayList<JSONObject> matches = new java.util.ArrayList<JSONObject>();
            java.util.ArrayList<JSONObject> fallback = new java.util.ArrayList<JSONObject>();
            java.util.HashSet<String> seen = new java.util.HashSet<String>();

            for (int i = 0; i < sourceItems.size(); i++) {
                JSONObject item = sourceItems.get(i);
                if (item == null) continue;
                String itemType = normalizeSearch(inferContentType(item, ""));
                if (type.length() > 0 && !type.equals(itemType)) continue;
                String key = relatedIdentityKey(item);
                if (key.length() == 0 || key.equals(excludeKey)) continue;
                boolean sameCategory = category.length() == 0
                        || relatedCategoryMatches(category, jsonText(item, "category", "genre", "genres"));
                if (sameCategory) {
                    if (seen.add(key)) matches.add(item);
                } else {
                    fallback.add(item);
                }
            }

            java.util.Comparator<JSONObject> newestFirst = new java.util.Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) {
                    long ra = contentRecency(a);
                    long rb = contentRecency(b);
                    if (ra == rb) return 0;
                    return ra < rb ? 1 : -1;
                }
            };
            java.util.Collections.sort(matches, newestFirst);
            java.util.Collections.sort(fallback, newestFirst);

            if (matches.size() < 3) {
                for (int i = 0; i < fallback.size() && matches.size() < 3; i++) {
                    JSONObject item = fallback.get(i);
                    String key = relatedIdentityKey(item);
                    if (key.length() == 0 || key.equals(excludeKey) || !seen.add(key)) continue;
                    matches.add(item);
                }
            }

            int count = limit > 0 ? Math.min(limit, matches.size()) : matches.size();
            for (int i = 0; i < count; i++) out.put(matches.get(i));
        } catch (Throwable error) {
            android.util.Log.w("CigramRelated", "Related extraction failed", error);
        }
        return out;
    }

    private static boolean relatedCategoryMatches(String a, String b) {
        if (a == null || b == null) return false;
        String na = normalizeSearch(a);
        String nb = normalizeSearch(b);
        if (na.length() == 0 || nb.length() == 0) return false;
        if (na.equals(nb) || na.contains(nb) || nb.contains(na)) return true;

        String[] aa = a.split("[,،|/;]+");
        String[] bb = b.split("[,،|/;]+");
        for (String x : aa) {
            String nx = normalizeSearch(x);
            if (nx.length() == 0) continue;
            for (String y : bb) {
                String ny = normalizeSearch(y);
                if (ny.length() > 0 && nx.equals(ny)) return true;
            }
        }
        return false;
    }

    private static String relatedIdentityKey(JSONObject item) {
        if (item == null) return "";
        String type = inferContentType(item, "");
        if ("series".equals(type)) {
            String sid = normalizeSearch(jsonText(item, "series_id"));
            if (sid.length() > 0) return "series|" + sid;
            String seriesTitle = normalizeSearch(jsonText(item, "series_title"));
            if (seriesTitle.length() > 0) return "series|title|" + seriesTitle;
            String id = normalizeSearch(jsonText(item, "id"));
            if (id.length() > 0) return "series|id|" + id;
        } else {
            String id = normalizeSearch(jsonText(item, "id", "movie_id", "item_id", "content_id"));
            if (id.length() > 0) return "movie|" + id;
        }
        String title = normalizeSearch(jsonText(
                item, "series_title", "title", "name", "title_ar", "title_en"));
        String year = normalizeSearch(jsonText(item, "year", "release_year"));
        return type + "|" + title + "|" + year;
    }

    private static void bindExistingCatalogCard(
            final Activity activity,
            View cardView,
            final JSONObject item
    ) {
        if (!(cardView instanceof ViewGroup) || item == null) return;
        ViewGroup card = (ViewGroup) cardView;
        ImageView poster = null;
        TextView title = null;
        ViewGroup ratingGroup = null;
        ViewGroup metaGroup = null;

        for (int i = 0; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            if (poster == null && child instanceof ImageView) {
                poster = (ImageView) child;
            } else if (child instanceof ViewGroup) {
                if (ratingGroup == null) ratingGroup = (ViewGroup) child;
                else if (metaGroup == null) metaGroup = (ViewGroup) child;
            } else if (title == null && child instanceof TextView) {
                title = (TextView) child;
            }
        }

        if (poster != null) {
            String url = jsonText(item, "poster", "series_poster", "season_poster", "image", "backdrop");
            if (url.length() > 0) loadDynamicPoster(activity, url, poster);
        }

        if (title != null) {
            title.setText(valueOrFallback(jsonText(
                    item, "series_title", "title", "name", "title_ar", "title_en"), "بدون عنوان"));
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }

        if (ratingGroup != null) {
            TextView rating = firstTextView(ratingGroup);
            ImageView imdbImage = firstImageView(ratingGroup);
            if (rating != null) {
                rating.setText(valueOrFallback(jsonText(
                        item, "imdb", "rating", "imdb_rating", "vote_average", "score"), "0.0"));
            }
            if (imdbImage != null) setImdbDrawable(activity, imdbImage);
        }

        if (metaGroup != null) {
            String category = jsonText(item, "category", "genre", "genres");
            String year = jsonText(item, "year", "release_year");
            java.util.ArrayList<TextView> texts = directTextViews(metaGroup);
            TextView separator = null;
            TextView yearView = null;
            TextView categoryView = null;
            for (TextView text : texts) {
                String current = text.getText() == null ? "" : text.getText().toString().trim();
                if ("|".equals(current)) separator = text;
                else if (current.matches("[0-9]{4}")) yearView = text;
                else if (categoryView == null) categoryView = text;
            }
            if (separator == null && texts.size() >= 2) separator = texts.get(1);
            if (categoryView == null && texts.size() >= 1) categoryView = texts.get(0);
            if (yearView == null && texts.size() >= 3) yearView = texts.get(2);
            if (categoryView != null) categoryView.setText(category);
            if (separator != null) separator.setText("|");
            if (yearView != null) yearView.setText(year);
        }

        applyTypefaceRecursively(card, cigramTypeface(activity));
        final String displayTitle = valueOrFallback(jsonText(
                item, "series_title", "title", "name", "title_ar", "title_en"), "بدون عنوان");
        card.setClickable(true);
        attachDetailsWarmTouch(activity, card, item);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                openDynamicDetails(activity, item, displayTitle, view);
            }
        });
    }

    private static final class CigramAspectPosterView extends ImageView {
        CigramAspectPosterView(Context context) { super(context); }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = View.MeasureSpec.getSize(widthMeasureSpec);
            if (width <= 0) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                width = getMeasuredWidth();
            }
            int height = Math.max(1, Math.round(width * 1.5f));
            setMeasuredDimension(width, height);
        }
    }

    private static boolean meaningfulRating(String value) {
        String text = clean(value);
        if (text.length() == 0) return false;
        try { return Double.parseDouble(text.replace(',', '.')) > 0.0001d; }
        catch (Throwable ignored) { return !"0".equals(text) && !"0.0".equals(text); }
    }

    private static String firstCategory(String value) {
        String text = clean(value);
        if (text.length() == 0) return "";
        String[] parts = text.split("[,،|/]");
        return parts.length == 0 ? text : clean(parts[0]);
    }

    private static View makeSectionGridCard(
            final Activity activity,
            final JSONObject item
    ) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.RIGHT);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        card.setPadding(0, 0, 0, 0);
        card.setBackgroundColor(Color.TRANSPARENT);
        card.setClickable(true);

        final CigramAspectPosterView poster = new CigramAspectPosterView(activity);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(roundedDrawable(0xFF1B1B1F, dp(activity, 14f)));
        if (Build.VERSION.SDK_INT >= 21) {
            final float radius = dp(activity, 14f);
            poster.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            poster.setClipToOutline(true);
        }
        card.addView(poster, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        CigramDesign.posterFrame(poster, 14f);     // v4.4

        String ratingValue = jsonText(
                item, "imdb", "rating", "imdb_rating", "vote_average", "score");
        LinearLayout ratingRow = new LinearLayout(activity);
        ratingRow.setOrientation(LinearLayout.HORIZONTAL);
        ratingRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        ratingRow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ratingRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpInt(activity, 24));
        ratingRowLp.topMargin = dpInt(activity, 6);
        card.addView(ratingRow, ratingRowLp);

        ImageView imdb = new ImageView(activity);
        imdb.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        setImdbDrawable(activity, imdb);
        LinearLayout.LayoutParams imdbLp = new LinearLayout.LayoutParams(
                dpInt(activity, 36), dpInt(activity, 18));
        ratingRow.addView(imdb, imdbLp);

        TextView rating = new TextView(activity);
        rating.setText(ratingValue);
        rating.setTextColor(CigramDesign.IMDB);     // v4.4: same score colour as the home cards
        rating.setTextSize(12.5f);
        rating.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        rating.setTypeface(cigramTypeface(activity), Typeface.BOLD);
        LinearLayout.LayoutParams ratingLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dpInt(activity, 24));
        ratingLp.leftMargin = dpInt(activity, 5);
        ratingRow.addView(rating, ratingLp);
        ratingRow.setVisibility(meaningfulRating(ratingValue) ? View.VISIBLE : View.GONE);

        TextView title = new TextView(activity);
        title.setText(valueOrFallback(jsonText(
                item, "series_title", "title", "name", "title_ar", "title_en"), "بدون عنوان"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(CigramDesign.CARD_TITLE_SP);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);     // v4.4: right, like the home cards
        title.setTypeface(cigramTypeface(activity), Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dpInt(activity, 4);
        card.addView(title, titleLp);

        String category = firstCategory(jsonText(item, "category", "genre", "genres"));
        String year = clean(jsonText(item, "year", "release_year"));
        String metaText = category;
        if (category.length() > 0 && year.length() > 0) metaText += " · " + year;
        else if (metaText.length() == 0) metaText = year;

        TextView meta = new TextView(activity);
        meta.setText(metaText);
        meta.setTextColor(CigramDesign.TEXT_3);
        meta.setTextSize(CigramDesign.CARD_META_SP);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        meta.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        meta.setTypeface(cigramTypeface(activity));
        meta.setVisibility(metaText.length() > 0 ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = dpInt(activity, 2);
        card.addView(meta, metaLp);

        String posterUrl = jsonText(item, "poster", "series_poster", "season_poster", "image", "backdrop");
        if (posterUrl.length() > 0) loadDynamicPoster(activity, posterUrl, poster);

        final String displayTitle = valueOrFallback(jsonText(
                item, "series_title", "title", "name", "title_ar", "title_en"), "بدون عنوان");
        attachDetailsWarmTouch(activity, card, item);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                openDynamicDetails(activity, item, displayTitle, view);
            }
        });
        return card;
    }

    private static TextView makeMetaText(Activity activity, String value) {
        TextView text = new TextView(activity);
        text.setText(value == null ? "" : value);
        text.setTextColor(0xFF8E8E93);
        text.setTextSize(11f);
        text.setGravity(Gravity.CENTER);
        text.setSingleLine(true);
        text.setTypeface(cigramTypeface(activity));
        return text;
    }

    /** v10.39: the rounded IMDb logo followed by the rating (see {@link CigramImdbIcon}). */
    static CharSequence imdbLogoText(Activity activity, String rating, float textSp) {
        return CigramImdbIcon.text(activity, rating, textSp);
    }

    private static void setImdbDrawable(Activity activity, ImageView image) {
        if (activity == null || image == null) return;
        android.view.ViewGroup.LayoutParams lp = image.getLayoutParams();
        CigramImdbIcon.apply(image, lp != null && lp.height > 0 ? lp.height : dpInt(activity, 18));
    }

    private static TextView firstTextView(ViewGroup group) {
        if (group == null) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) return (TextView) child;
        }
        return null;
    }

    private static ImageView firstImageView(ViewGroup group) {
        if (group == null) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ImageView) return (ImageView) child;
        }
        return null;
    }

    private static java.util.ArrayList<TextView> directTextViews(ViewGroup group) {
        java.util.ArrayList<TextView> out = new java.util.ArrayList<TextView>();
        if (group == null) return out;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) out.add((TextView) child);
        }
        return out;
    }

    private static TextView textViewById(Activity activity, String idName) {
        View view = findByIdName(activity, idName);
        return view instanceof TextView ? (TextView) view : null;
    }

    private static ImageView imageViewById(Activity activity, String idName) {
        View view = findByIdName(activity, idName);
        return view instanceof ImageView ? (ImageView) view : null;
    }

    private static void setTextById(Activity activity, String idName, String value) {
        TextView view = textViewById(activity, idName);
        if (view != null && value != null && value.trim().length() > 0) view.setText(value.trim());
    }

    private static String valueOrFallback(String value, String fallback) {
        return value == null || value.trim().length() == 0 ? fallback : value.trim();
    }

    private static void applyTypefaceRecursively(View view, Typeface typeface) {
        if (view == null || typeface == null) return;
        if (view instanceof TextView) {
            // v4.4: keep the weight already chosen for this text (bold titles, buttons). The plain
            // setTypeface(typeface) used before reset every title of the details page to regular.
            TextView text = (TextView) view;
            Typeface current = text.getTypeface();
            int style = current == null ? Typeface.NORMAL : current.getStyle();
            text.setTypeface(typeface, style);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTypefaceRecursively(group.getChildAt(i), typeface);
            }
        }
    }
    private static void setupDetails(final Activity activity) {
        if (!isAlive(activity)) return;
        refreshOfflineBanner(activity);

        IO.execute(new Runnable() {
            @Override public void run() {
                String cached = catalogPrefs(activity).getString(KEY_JSON, "");

                if (!isValidCatalog(cached)) {
                    if (!isOnline(activity)) return;

                    FetchResult result = fetchCatalog(activity);
                    if (!result.ok || !isValidCatalog(result.body)) return;

                    long now = System.currentTimeMillis();
                    PreparedCatalog prepared = prepareCatalog(result.body);
                    catalogPrefs(activity).edit()
                            .putString(KEY_JSON, prepared.displayJson)
                            .putLong(KEY_UPDATED, now)
                            .putString(KEY_SEARCH_JSON, prepared.searchIndexJson)
                            .putLong(KEY_SEARCH_UPDATED, now)
                            .apply();
                    cached = prepared.displayJson;
                }

                JSONObject catalogItem = findCatalogMemoryItem(
                        activity.getIntent() == null ? "" : activity.getIntent().getStringExtra("type"),
                        activity.getIntent() == null ? "" : activity.getIntent().getStringExtra("id"),
                        activity.getIntent() == null ? "" : activity.getIntent().getStringExtra("title"));
                try {
                    if (catalogItem == null) catalogItem = findDetailsCatalogItem(activity);
                } catch (Throwable error) {
                    android.util.Log.w("CigramDetails", "Background catalog lookup failed", error);
                }

                if (catalogItem == null || catalogItem.length() == 0) return;

                final JSONObject resolved;
                try {
                    resolved = new JSONObject(catalogItem.toString());
                } catch (Throwable error) {
                    return;
                }

                warmDetailsItem(resolved);

                long openedAt;
                synchronized (DETAILS_BOUND_AT) {
                    Long t = DETAILS_BOUND_AT.get(activity);
                    openedAt = t == null ? 0L : t.longValue();
                }
                // v10.34: never rebuild the page while it is still animating in
                long wait = Math.max(0L, openedAt + 450L - android.os.SystemClock.uptimeMillis());
                MAIN.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!isAlive(activity)) return;

                        JSONObject current = new JSONObject();
                        synchronized (DETAILS_DATA_CACHE) {
                            JSONObject stored = DETAILS_DATA_CACHE.get(activity);
                            if (stored != null) {
                                try {
                                    current = new JSONObject(stored.toString());
                                } catch (Throwable ignored) {
                                    current = stored;
                                }
                            }
                        }

                        JSONObject merged = mergeDetailsObjects(resolved, current);
                        mergeIntentDetails(merged, activity.getIntent());
                        restoreSeriesIdentity(merged, resolved);

                        boolean changed = true;
                        try {
                            changed = current == null || !merged.toString().equals(current.toString());
                        } catch (Throwable ignored) {}

                        synchronized (DETAILS_DATA_CACHE) {
                            DETAILS_DATA_CACHE.put(activity, merged);
                        }

                        if (changed) {
                            bindModernDetailsLayout(activity);
                            refreshStage3Details(activity);
                        }
                    }
                }, wait);
            }
        });
    }
    private static void refreshStage3Details(final Activity activity) {
        if (!isAlive(activity)) return;

        JSONObject item = stage3DetailsItem(activity);
        syncDetailsIntentExtras(activity, item);
        refreshModernDetailsActions(activity, item);
        ensureStage3Reactions(activity);
        refreshStage3Reactions(activity);
    }



    private static final class Stage3ReactionViews {
        TextView likeLabel;
        TextView dislikeLabel;
        View likeHost;
        View dislikeHost;
    }

    private static final class Stage3ReactionResult {
        boolean ok;
        int status;
        int likes;
        int dislikes;
        int selected = STAGE3_REACTION_NONE;
        String message = "";
    }

    private static void ensureStage3Reactions(final Activity activity) {
        if (activity == null || !isAlive(activity)) return;
        final View root = activity.findViewById(android.R.id.content);
        if (root == null) return;

        final Stage3ReactionViews views = stage3FindReactionViews(root);
        if (views.likeLabel == null || views.dislikeLabel == null) return;

        views.likeHost = stage3ReactionHost(views.likeLabel, true);
        views.dislikeHost = stage3ReactionHost(views.dislikeLabel, false);

        final View likeClick = views.likeHost == null ? views.likeLabel : views.likeHost;
        final View dislikeClick = views.dislikeHost == null ? views.dislikeLabel : views.dislikeHost;

        likeClick.setClickable(true);
        likeClick.setFocusable(true);
        likeClick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                stage3HandleReaction(activity, STAGE3_REACTION_LIKE);
            }
        });

        dislikeClick.setClickable(true);
        dislikeClick.setFocusable(true);
        dislikeClick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                stage3HandleReaction(activity, STAGE3_REACTION_DISLIKE);
            }
        });

        if (likeClick != views.likeLabel) {
            views.likeLabel.setClickable(true);
            views.likeLabel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    stage3HandleReaction(activity, STAGE3_REACTION_LIKE);
                }
            });
        }
        if (dislikeClick != views.dislikeLabel) {
            views.dislikeLabel.setClickable(true);
            views.dislikeLabel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    stage3HandleReaction(activity, STAGE3_REACTION_DISLIKE);
                }
            });
        }
    }

    private static void stage3HandleReaction(final Activity activity, final int wanted) {
        if (activity == null || !isAlive(activity)) return;
        synchronized (STAGE3_REACTION_SUBMITTING) {
            if (Boolean.TRUE.equals(STAGE3_REACTION_SUBMITTING.get(activity))) return;
        }

        final String email = stage3ReactionEmail(activity);
        final String token = stage3ReactionToken(activity);
        if (email.length() == 0 || token.length() == 0) {
            Toast.makeText(
                    activity,
                    "سجّل الدخول بالبريد الإلكتروني حتى تتمكن من التفاعل",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        JSONObject item = stage3DetailsItem(activity);
        final String contentKey = stage3ContentKey(item);
        if (contentKey.length() == 0) return;

        final SharedPreferences prefs = activity.getSharedPreferences(
                PREF_STAGE3_REACTIONS, Context.MODE_PRIVATE);
        final String contentHash = Integer.toHexString(contentKey.hashCode());
        final String userHash = Integer.toHexString(email.hashCode());
        final int oldLikes = Math.max(0, prefs.getInt("global_likes_" + contentHash, 0));
        final int oldDislikes = Math.max(0, prefs.getInt("global_dislikes_" + contentHash, 0));
        final int oldSelected = prefs.getInt(
                "global_reaction_" + contentHash + "_" + userHash,
                STAGE3_REACTION_NONE
        );
        final long oldUpdatedAt = prefs.getLong("global_updated_" + contentHash, 0L);

        if (oldSelected == wanted) {
            stage3FetchGlobalReactions(activity, true);
            return;
        }

        int optimisticLikes = oldLikes;
        int optimisticDislikes = oldDislikes;
        if (oldSelected == STAGE3_REACTION_LIKE) optimisticLikes = Math.max(0, optimisticLikes - 1);
        if (oldSelected == STAGE3_REACTION_DISLIKE) optimisticDislikes = Math.max(0, optimisticDislikes - 1);
        if (wanted == STAGE3_REACTION_LIKE) optimisticLikes++;
        if (wanted == STAGE3_REACTION_DISLIKE) optimisticDislikes++;

        stage3SaveReactionCache(
                activity,
                contentKey,
                email,
                optimisticLikes,
                optimisticDislikes,
                wanted,
                System.currentTimeMillis()
        );
        stage3RenderReactionCache(activity, contentKey);

        synchronized (STAGE3_REACTION_SUBMITTING) {
            STAGE3_REACTION_SUBMITTING.put(activity, Boolean.TRUE);
        }

        IO.execute(new Runnable() {
            @Override public void run() {
                final Stage3ReactionResult result = stage3RequestReactionWithRenewal(
                        activity,
                        contentKey,
                        token,
                        wanted,
                        true
                );
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        synchronized (STAGE3_REACTION_SUBMITTING) {
                            STAGE3_REACTION_SUBMITTING.remove(activity);
                        }
                        if (!isAlive(activity)) return;
                        String activeKey = stage3ContentKey(stage3DetailsItem(activity));
                        if (!contentKey.equals(activeKey)) return;

                        if (result.ok) {
                            stage3SaveReactionCache(
                                    activity,
                                    contentKey,
                                    email,
                                    result.likes,
                                    result.dislikes,
                                    result.selected,
                                    System.currentTimeMillis()
                            );
                            stage3RenderReactionCache(activity, contentKey);
                            return;
                        }

                        stage3SaveReactionCache(
                                activity,
                                contentKey,
                                email,
                                oldLikes,
                                oldDislikes,
                                oldSelected,
                                oldUpdatedAt
                        );
                        stage3RenderReactionCache(activity, contentKey);

                        String message;
                        if (result.status == 401) {
                            stage3ShowSessionExpired(activity);
                            return;
                        } else if (result.status == 404) {
                            message = "خدمة الإعجابات العالمية غير مفعّلة على السيرفر بعد";
                        } else {
                            message = result.message.length() > 0
                                    ? result.message
                                    : "تعذر حفظ التفاعل، تحقق من الإنترنت";
                        }
                        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private static void refreshStage3Reactions(Activity activity) {
        if (activity == null || !isAlive(activity)) return;
        JSONObject item = stage3DetailsItem(activity);
        String contentKey = stage3ContentKey(item);
        if (contentKey.length() == 0) return;

        stage3RenderReactionCache(activity, contentKey);

        SharedPreferences prefs = activity.getSharedPreferences(
                PREF_STAGE3_REACTIONS, Context.MODE_PRIVATE);
        String contentHash = Integer.toHexString(contentKey.hashCode());
        long updatedAt = prefs.getLong("global_updated_" + contentHash, 0L);
        boolean stale = updatedAt <= 0L
                || System.currentTimeMillis() - updatedAt >= STAGE3_REACTION_REFRESH_MS;
        if (stale) stage3FetchGlobalReactions(activity, false);
    }

    private static void stage3FetchGlobalReactions(final Activity activity, boolean force) {
        if (activity == null || !isAlive(activity)) return;
        JSONObject item = stage3DetailsItem(activity);
        final String contentKey = stage3ContentKey(item);
        if (contentKey.length() == 0) return;

        synchronized (STAGE3_REACTION_LOADING) {
            if (Boolean.TRUE.equals(STAGE3_REACTION_LOADING.get(activity))) return;
            if (!force) {
                SharedPreferences prefs = activity.getSharedPreferences(
                        PREF_STAGE3_REACTIONS, Context.MODE_PRIVATE);
                String hash = Integer.toHexString(contentKey.hashCode());
                long updated = prefs.getLong("global_updated_" + hash, 0L);
                if (updated > 0L
                        && System.currentTimeMillis() - updated < STAGE3_REACTION_REFRESH_MS) {
                    return;
                }
            }
            STAGE3_REACTION_LOADING.put(activity, Boolean.TRUE);
        }

        final String token = stage3ReactionToken(activity);
        final String email = stage3ReactionEmail(activity);
        IO.execute(new Runnable() {
            @Override public void run() {
                final Stage3ReactionResult result = stage3RequestReactionWithRenewal(
                        activity,
                        contentKey,
                        token,
                        STAGE3_REACTION_NONE,
                        false
                );
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        synchronized (STAGE3_REACTION_LOADING) {
                            STAGE3_REACTION_LOADING.remove(activity);
                        }
                        if (!isAlive(activity)) return;
                        String activeKey = stage3ContentKey(stage3DetailsItem(activity));
                        if (!contentKey.equals(activeKey)) return;
                        if (!result.ok) return;

                        stage3SaveReactionCache(
                                activity,
                                contentKey,
                                email,
                                result.likes,
                                result.dislikes,
                                result.selected,
                                System.currentTimeMillis()
                        );
                        stage3RenderReactionCache(activity, contentKey);
                    }
                });
            }
        });
    }

    /**
     * الرمز المحفوظ قد يكون منتهياً: يُجدَّد بصمت قبل الطلب إن اقترب انتهاؤه، وعند رد 401
     * يُجدَّد ويعاد الطلب مرة واحدة. القراءة (GET) تعود بدون رمز إن تعذر التجديد حتى تظهر الأعداد.
     */
    private static Stage3ReactionResult stage3RequestReactionWithRenewal(
            Context context,
            String contentKey,
            String token,
            int wanted,
            boolean update
    ) {
        String active = token == null ? "" : token.trim();
        if (active.length() > 0 && CigramSession.expiresSoon(context)) {
            String renewed = CigramSession.renewBlocking(context);
            if (renewed.length() > 0) active = renewed;
        }
        Stage3ReactionResult result = stage3RequestReaction(contentKey, active, wanted, update);
        if (result.status != 401) return result;

        String renewed = CigramSession.renewBlocking(context);
        if (renewed.length() > 0) {
            result = stage3RequestReaction(contentKey, renewed, wanted, update);
            if (result.status != 401) return result;
        }
        if (!update && active.length() > 0) {
            Stage3ReactionResult anonymous = stage3RequestReaction(contentKey, "", wanted, false);
            if (anonymous.ok) return anonymous;
        }
        return result;
    }

    /** الجلسة انتهت فعلاً ولم يمكن تجديدها: نقول ذلك بوضوح ونعرض زر تسجيل الدخول. */
    private static void stage3ShowSessionExpired(final Activity activity) {
        if (activity == null || !isAlive(activity)) return;
        try {
            CigramUI.sheet(activity).icon(CigramUI.ICON_LOCK, CigramUI.AMBER)
                    .title("انتهت جلسة الحساب")
                    .message("سجّل الدخول من جديد مرة واحدة، وبعدها ستبقى جلستك محفوظة وتتجدد تلقائياً.")
                    .primary("تسجيل الدخول", CigramUI.AMBER, new CigramUI.Click() {
                        @Override public boolean onClick(CigramUI.Sheet sheet) {
                            try {
                                SharedPreferences auth = activity.getSharedPreferences(
                                        "auth_session", Context.MODE_PRIVATE);
                                auth.edit()
                                        .putBoolean("logged_in", false)
                                        .remove("token")
                                        .remove("expires_at")
                                        .apply();
                                android.content.Intent intent = new android.content.Intent(activity,
                                        Class.forName("com.Cigram.vid.AccountActivity"));
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                                        | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                                activity.startActivity(intent);
                            } catch (Throwable error) {
                                android.util.Log.w("CigramReactions", "open login failed", error);
                            }
                            return false;
                        }
                    }).secondary("لاحقاً", null).show();
        } catch (Throwable error) {
            Toast.makeText(activity, "انتهت جلسة الحساب، سجّل الدخول من جديد",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static Stage3ReactionResult stage3RequestReaction(
            String contentKey,
            String token,
            int wanted,
            boolean update
    ) {
        Stage3ReactionResult out = new Stage3ReactionResult();
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            String endpoint = STAGE3_REACTIONS_URL;
            if (!update) {
                endpoint += "?content_id="
                        + java.net.URLEncoder.encode(contentKey, "UTF-8");
            }

            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Cigram-Android/1.0");
            if (token != null && token.trim().length() > 0) {
                connection.setRequestProperty("Authorization", "Bearer " + token.trim());
            }

            if (update) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                JSONObject body = new JSONObject();
                body.put("content_id", contentKey);
                body.put("reaction", wanted == STAGE3_REACTION_DISLIKE ? "dislike" : "like");
                byte[] bytes = body.toString().getBytes(Charset.forName("UTF-8"));
                connection.setFixedLengthStreamingMode(bytes.length);
                java.io.OutputStream output = connection.getOutputStream();
                try {
                    output.write(bytes);
                    output.flush();
                } finally {
                    try { output.close(); } catch (Throwable ignored) { }
                }
            } else {
                connection.setRequestMethod("GET");
            }

            int status = connection.getResponseCode();
            out.status = status;
            input = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            String response = "";
            if (input != null) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
                byte[] bytes = new byte[4096];
                int count;
                int total = 0;
                while ((count = input.read(bytes)) != -1) {
                    total += count;
                    if (total > 64 * 1024) break;
                    buffer.write(bytes, 0, count);
                }
                response = new String(buffer.toByteArray(), Charset.forName("UTF-8"));
            }

            JSONObject json = response.trim().length() == 0
                    ? new JSONObject()
                    : new JSONObject(response);
            out.message = clean(json.optString("message", json.optString("error", "")));
            if (status < 200 || status >= 300
                    || !(json.optBoolean("success", false) || json.optBoolean("ok", false))) {
                return out;
            }

            out.likes = Math.max(0, json.optInt("likes", 0));
            out.dislikes = Math.max(0, json.optInt("dislikes", 0));
            String reaction = clean(json.optString("reaction", "none"));
            if ("like".equalsIgnoreCase(reaction)) {
                out.selected = STAGE3_REACTION_LIKE;
            } else if ("dislike".equalsIgnoreCase(reaction)) {
                out.selected = STAGE3_REACTION_DISLIKE;
            } else {
                out.selected = STAGE3_REACTION_NONE;
            }
            out.ok = true;
            return out;
        } catch (SocketTimeoutException error) {
            out.message = "انتهت مهلة الاتصال";
        } catch (UnknownHostException error) {
            out.message = "لا يوجد اتصال بالإنترنت";
        } catch (ConnectException error) {
            out.message = "لا يوجد اتصال بالإنترنت";
        } catch (Throwable error) {
            android.util.Log.w("CigramReactions", "Reaction request failed", error);
            out.message = "تعذر الاتصال بخدمة الإعجابات";
        } finally {
            try { if (input != null) input.close(); } catch (Throwable ignored) { }
            try { if (connection != null) connection.disconnect(); } catch (Throwable ignored) { }
        }
        return out;
    }

    private static void stage3SaveReactionCache(
            Context context,
            String contentKey,
            String email,
            int likes,
            int dislikes,
            int selected,
            long updatedAt
    ) {
        if (context == null || contentKey == null || contentKey.length() == 0) return;
        String contentHash = Integer.toHexString(contentKey.hashCode());
        SharedPreferences.Editor editor = context.getSharedPreferences(
                PREF_STAGE3_REACTIONS, Context.MODE_PRIVATE).edit();
        editor.putInt("global_likes_" + contentHash, Math.max(0, likes));
        editor.putInt("global_dislikes_" + contentHash, Math.max(0, dislikes));
        if (updatedAt > 0L) editor.putLong("global_updated_" + contentHash, updatedAt);
        if (email != null && email.trim().length() > 0) {
            String userHash = Integer.toHexString(email.trim().toLowerCase(java.util.Locale.US).hashCode());
            editor.putInt("global_reaction_" + contentHash + "_" + userHash, selected);
        }
        editor.apply();
    }

    private static void stage3RenderReactionCache(Activity activity, String contentKey) {
        if (activity == null || !isAlive(activity) || contentKey == null || contentKey.length() == 0) return;
        View root = activity.findViewById(android.R.id.content);
        if (root == null) return;

        Stage3ReactionViews views = stage3FindReactionViews(root);
        if (views.likeLabel == null || views.dislikeLabel == null) return;
        views.likeHost = stage3ReactionHost(views.likeLabel, true);
        views.dislikeHost = stage3ReactionHost(views.dislikeLabel, false);

        SharedPreferences prefs = activity.getSharedPreferences(
                PREF_STAGE3_REACTIONS, Context.MODE_PRIVATE);
        String contentHash = Integer.toHexString(contentKey.hashCode());
        int likes = Math.max(0, prefs.getInt("global_likes_" + contentHash, 0));
        int dislikes = Math.max(0, prefs.getInt("global_dislikes_" + contentHash, 0));

        int selected = STAGE3_REACTION_NONE;
        String email = stage3ReactionEmail(activity);
        if (email.length() > 0) {
            String userHash = Integer.toHexString(email.hashCode());
            selected = prefs.getInt(
                    "global_reaction_" + contentHash + "_" + userHash,
                    STAGE3_REACTION_NONE
            );
        }

        views.likeLabel.setText("أعجبني " + likes);
        views.dislikeLabel.setText("لا يعجبني " + dislikes);
        stage3ApplyReactionVisual(
                views.likeHost == null ? views.likeLabel : views.likeHost,
                views.likeLabel,
                selected == STAGE3_REACTION_LIKE
        );
        stage3ApplyReactionVisual(
                views.dislikeHost == null ? views.dislikeLabel : views.dislikeHost,
                views.dislikeLabel,
                selected == STAGE3_REACTION_DISLIKE
        );
    }

    private static String stage3ReactionToken(Context context) {
        if (context == null) return "";
        try {
            SharedPreferences auth = context.getSharedPreferences(
                    "auth_session", Context.MODE_PRIVATE);
            String token = auth.getString("token", "");
            if (token == null) return "";
            return token.trim();
        } catch (Throwable error) {
            android.util.Log.w("CigramReactions", "Unable to read auth token", error);
            return "";
        }
    }

    private static String stage3ReactionEmail(Context context) {
        if (context == null) return "";
        try {
            SharedPreferences auth = context.getSharedPreferences(
                    "auth_session", Context.MODE_PRIVATE);
            Map<String, ?> values = auth.getAll();
            Object rawLogged = values == null ? null : values.get("logged_in");
            boolean loggedIn = false;
            if (rawLogged instanceof Boolean) {
                loggedIn = ((Boolean) rawLogged).booleanValue();
            } else if (rawLogged != null) {
                String value = String.valueOf(rawLogged).trim();
                loggedIn = "true".equalsIgnoreCase(value)
                        || "1".equals(value)
                        || "yes".equalsIgnoreCase(value);
            }
            String token = stage3ReactionToken(context);
            if (!loggedIn && token.length() == 0) return "";

            String email = auth.getString("email", "");
            if (email == null) return "";
            email = email.trim().toLowerCase(java.util.Locale.US);
            return email.length() == 0 ? "" : email;
        } catch (Throwable error) {
            android.util.Log.w("CigramReactions", "Unable to read signed-in email", error);
            return "";
        }
    }

    private static Stage3ReactionViews stage3FindReactionViews(View root) {
        Stage3ReactionViews out = new Stage3ReactionViews();
        stage3FindReactionViewsRecursive(root, out);
        return out;
    }

    private static void stage3FindReactionViewsRecursive(View view, Stage3ReactionViews out) {
        if (view == null || out == null) return;
        if (view instanceof TextView) {
            int kind = stage3ReactionTextKind((TextView) view);
            if (kind == STAGE3_REACTION_LIKE && out.likeLabel == null) {
                out.likeLabel = (TextView) view;
            } else if (kind == STAGE3_REACTION_DISLIKE && out.dislikeLabel == null) {
                out.dislikeLabel = (TextView) view;
            }
        }
        if (out.likeLabel != null && out.dislikeLabel != null) return;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                stage3FindReactionViewsRecursive(group.getChildAt(i), out);
                if (out.likeLabel != null && out.dislikeLabel != null) return;
            }
        }
    }

    private static int stage3ReactionTextKind(TextView view) {
        if (view == null) return STAGE3_REACTION_NONE;
        CharSequence raw = view.getText();
        String text = raw == null ? "" : raw.toString().trim();
        if (text.length() == 0) return STAGE3_REACTION_NONE;
        String normalized = text
                .replace('أ', 'ا')
                .replace('إ', 'ا')
                .replace('آ', 'ا')
                .replace('ى', 'ي');
        if (normalized.contains("لا يعجبني") || normalized.contains("لم يعجبني")) {
            return STAGE3_REACTION_DISLIKE;
        }
        if (normalized.contains("اعجبني")) {
            return STAGE3_REACTION_LIKE;
        }
        return STAGE3_REACTION_NONE;
    }

    private static View stage3ReactionHost(TextView label, boolean like) {
        if (label == null) return null;
        View best = label;
        android.view.ViewParent parent = label.getParent();
        for (int depth = 0; depth < 4 && parent instanceof ViewGroup; depth++) {
            ViewGroup group = (ViewGroup) parent;
            if (stage3ContainsOppositeReaction(group, like)) break;
            best = group;
            parent = group.getParent();
        }
        return best;
    }

    private static boolean stage3ContainsOppositeReaction(View view, boolean like) {
        if (view == null) return false;
        if (view instanceof TextView) {
            int kind = stage3ReactionTextKind((TextView) view);
            if (like && kind == STAGE3_REACTION_DISLIKE) return true;
            if (!like && kind == STAGE3_REACTION_LIKE) return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (stage3ContainsOppositeReaction(group.getChildAt(i), like)) return true;
            }
        }
        return false;
    }

    private static void stage3ApplyReactionVisual(
            View host,
            TextView label,
            boolean selected
    ) {
        if (label != null) {
            label.setTextColor(selected ? 0xFFE21B14 : 0xFF9AA4AA);
        }
        if (host != null) {
            stage3TintReactionImages(host, selected ? 0xFFE21B14 : 0xFF7F8A90);
        }
    }

    private static void stage3TintReactionImages(View view, int color) {
        if (view == null) return;
        if (view instanceof ImageView) {
            try {
                ((ImageView) view).setColorFilter(color);
            } catch (Throwable ignored) { }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                stage3TintReactionImages(group.getChildAt(i), color);
            }
        }
    }

    private static void stage3ForceSubscriptionNotificationsEnabled(Context context, JSONObject item) {
        if (context == null || item == null) return;
        String key = stage3SeriesKey(item);
        if (key.length() == 0) return;

        JSONArray old = stage3ReadItems(context, PREF_SERIES_SUBSCRIPTIONS);
        JSONArray out = new JSONArray();
        boolean changedAny = false;
        for (int i = 0; i < old.length(); i++) {
            JSONObject current = old.optJSONObject(i);
            if (current == null) continue;
            if (key.equals(current.optString("series_key", ""))
                    && !current.optBoolean("notifications_enabled", true)) {
                try {
                    JSONObject changed = new JSONObject(current.toString());
                    changed.put("notifications_enabled", true);
                    out.put(changed);
                    changedAny = true;
                } catch (Throwable ignored) {
                    out.put(current);
                }
            } else {
                out.put(current);
            }
        }
        if (changedAny) stage3WriteItems(context, PREF_SERIES_SUBSCRIPTIONS, out);
    }
    private static JSONObject stage3DetailsItem(Activity activity) {
        JSONObject item = resolvedDetailsItem(activity);
        if (item == null) item = new JSONObject();
        if (activity == null) return item;

        try {
            Bundle extras = activity.getIntent() == null ? null : activity.getIntent().getExtras();
            if (extras != null) {
                for (String key : extras.keySet()) {
                    Object value = extras.get(key);
                    if (value == null) continue;
                    if (value instanceof String
                            || value instanceof Number
                            || value instanceof Boolean
                            || value instanceof Character) {
                        String text = String.valueOf(value);
                        if (text.trim().length() == 0 && jsonText(item, key).length() > 0) continue;
                        item.put(key, text);
                    }
                }
            }

            String type = jsonText(item, "type").toLowerCase(java.util.Locale.US);
            if (type.length() == 0) {
                type = stage3IsSeriesItem(item) ? "series" : "movie";
                item.put("type", type);
            } else if ("episode".equals(type)) {
                item.put("type", "series");
            }

            if (jsonText(item, "title").length() == 0) {
                String title = jsonText(item, "series_title", "name");
                if (title.length() > 0) item.put("title", title);
            }
            restoreSeriesIdentity(item, null);
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "stage3DetailsItem failed", error);
        }
        return item;
    }

    /**
     * A series page must show the series, not its newest episode. Episodes share the series_id, so the
     * warm cache, the intent extras or the background merge could lay the last episode's title,
     * description and still over the series header ("الموتى السائرون" became "الاخيرة"). The header
     * from the catalog wins for these identity fields; episode-only fields (video, season...) are kept.
     * `candidate` is used when it is itself a series header (catalog not in memory yet).
     */
    private static void restoreSeriesIdentity(JSONObject item, JSONObject candidate) {
        if (item == null) return;
        try {
            if (!"series".equals(inferContentType(item, ""))) return;
            String sid = jsonText(item, "series_id", "id");
            if (sid.length() == 0) return;
            JSONObject header = findCatalogMemoryItem("series", sid, "");
            if ((header == null || isFlatEpisodeRow(header)) && candidate != null && !isFlatEpisodeRow(candidate)
                    && sid.equals(jsonText(candidate, "series_id", "id"))) {
                header = candidate;
            }
            if (header == null || isFlatEpisodeRow(header)) return; // no header in the catalog: nothing better to show
            String title = jsonText(header, "series_title", "title", "name", "title_ar", "title_en");
            if (title.length() > 0) {
                item.put("title", title);
                item.put("series_title", title);
            }
            for (String key : new String[]{"description", "backdrop", "poster", "series_poster"}) {
                String value = jsonText(header, key);
                if (value.length() > 0) item.put(key, value);
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "restoreSeriesIdentity failed", error);
        }
    }

    private static boolean stage3IsSeriesDetails(Activity activity) {
        return stage3IsSeriesItem(stage3DetailsItem(activity));
    }

    private static boolean stage3IsSeriesItem(JSONObject item) {
        if (item == null) return false;
        String type = jsonText(item, "type").toLowerCase(java.util.Locale.US);
        if ("series".equals(type) || "episode".equals(type)) return true;
        return jsonText(item, "series_id").length() > 0
                || jsonText(item, "series_episodes_json").length() > 0;
    }

    private static String stage3ContentKey(JSONObject item) {
        if (item == null) return "";
        if (stage3IsSeriesItem(item)) {
            String seriesId = clean(jsonText(item, "series_id"));
            if (seriesId.length() > 0) return "series|" + seriesId;
            String id = clean(jsonText(item, "id"));
            if (id.length() > 0) return "series|id|" + id;
            String title = normalizeSearch(jsonText(item, "series_title", "title", "name"));
            return title.length() == 0 ? "" : "series|title|" + title;
        }

        String id = clean(jsonText(item, "id", "movie_id", "item_id"));
        if (id.length() > 0) return "movie|" + id;
        String title = normalizeSearch(jsonText(item, "title", "name"));
        String video = normalizeSearch(jsonText(item, "video", "link", "video_url", "movie_url"));
        return (title + video).length() == 0 ? "" : "movie|" + title + "|" + video;
    }

    private static String stage3SeriesKey(JSONObject item) {
        if (item == null) return "";
        String seriesId = clean(jsonText(item, "series_id"));
        if (seriesId.length() > 0) return "series|" + seriesId;
        String id = clean(jsonText(item, "id"));
        if (id.length() > 0) return "series|id|" + id;
        String title = normalizeSearch(jsonText(item, "series_title", "title", "name"));
        return title.length() == 0 ? "" : "series|title|" + title;
    }

    /**
     * v5.29: "المشاهدة لاحقاً" belongs to the account, not to the device. Each logged-in account
     * keeps its own list under items_json::<owner>; a guest has no list at all (reads are empty,
     * writes are ignored). Other lists keep the device-wide key.
     */
    private static String stage3ItemsKey(Context context, String prefName) {
        if (!PREF_WATCH_LATER.equals(prefName)) return KEY_STAGE3_ITEMS;
        String owner;
        try {
            owner = CigramUserData.isLoggedIn(context) ? CigramUserData.owner(context) : "";
        } catch (Throwable ignored) {
            owner = "";
        }
        return owner == null || owner.length() == 0 ? null : KEY_STAGE3_ITEMS + "::" + owner;
    }

    static boolean stage3WatchLaterAvailable(Context context) {
        return context != null && stage3ItemsKey(context, PREF_WATCH_LATER) != null;
    }

    private static JSONArray stage3ReadItems(Context context, String prefName) {
        try {
            String key = stage3ItemsKey(context, prefName);
            if (key == null) return new JSONArray();
            SharedPreferences prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE);
            if (!KEY_STAGE3_ITEMS.equals(key) && !prefs.contains(key) && prefs.contains(KEY_STAGE3_ITEMS)) {
                // one-time move of the old device-wide list into the first account that opens it
                prefs.edit().putString(key, prefs.getString(KEY_STAGE3_ITEMS, "[]"))
                        .remove(KEY_STAGE3_ITEMS).apply();
            }
            String raw = prefs.getString(key, "[]");
            if (raw == null || raw.trim().length() == 0) return new JSONArray();
            return new JSONArray(raw);
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }

    private static void stage3WriteItems(Context context, String prefName, JSONArray items) {
        if (context == null || items == null) return;
        String key = stage3ItemsKey(context, prefName);
        if (key == null) return; // guest: watch later is not stored outside an account
        context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                .edit()
                .putString(key, items.toString())
                .apply();
        try {
            CigramUserData.touch(context, PREF_WATCH_LATER.equals(prefName)
                    ? "watch_later" : "subscriptions");
        } catch (Throwable ignored) { }
        // v10.67: follow / unfollow -> the per-series push topic follows too
        if (PREF_SERIES_SUBSCRIPTIONS.equals(prefName)) {
            try { CigramFcm.sync(context); } catch (Throwable ignored) { }
        }
    }

    /** v10.67: ids of the series this viewer follows (each one is an FCM topic for new episodes). */
    public static java.util.List<String> subscribedSeriesIds(Context context) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        if (context == null) return out;
        try {
            JSONArray items = stage3ReadItems(context, PREF_SERIES_SUBSCRIPTIONS);
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) continue;
                String id = jsonText(o, "series_id");
                if (id.length() > 0 && !out.contains(id)) out.add(id);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static boolean stage3WatchLaterContains(Context context, JSONObject item) {
        String key = stage3ContentKey(item);
        if (key.length() == 0) return false;
        JSONArray items = stage3ReadItems(context, PREF_WATCH_LATER);
        for (int i = 0; i < items.length(); i++) {
            JSONObject current = items.optJSONObject(i);
            if (current != null && key.equals(current.optString("watch_later_key", ""))) {
                return true;
            }
        }
        return false;
    }

    private static boolean toggleStage3WatchLater(Context context, JSONObject item) {
        if (context == null || item == null) return false;
        String key = stage3ContentKey(item);
        if (key.length() == 0) return false;

        JSONArray old = stage3ReadItems(context, PREF_WATCH_LATER);
        boolean exists = false;
        for (int i = 0; i < old.length(); i++) {
            JSONObject current = old.optJSONObject(i);
            if (current != null && key.equals(current.optString("watch_later_key", ""))) {
                exists = true;
                break;
            }
        }

        JSONArray out = new JSONArray();
        if (!exists) {
            try {
                JSONObject current = new JSONObject(item.toString());
                current.put("watch_later_key", key);
                current.put("added_at", System.currentTimeMillis());
                out.put(current);
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "toggleStage3WatchLater", ignored); }
        }

        for (int i = 0; i < old.length(); i++) {
            JSONObject current = old.optJSONObject(i);
            if (current == null) continue;
            if (key.equals(current.optString("watch_later_key", ""))) continue;
            out.put(current);
            if (out.length() >= 100) break;
        }

        stage3WriteItems(context, PREF_WATCH_LATER, out);
        return !exists;
    }

    private static void removeStage3WatchLater(Context context, String key) {
        if (context == null || key == null || key.length() == 0) return;
        JSONArray old = stage3ReadItems(context, PREF_WATCH_LATER);
        JSONArray out = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject current = old.optJSONObject(i);
            if (current == null) continue;
            if (key.equals(current.optString("watch_later_key", ""))) continue;
            out.put(current);
        }
        stage3WriteItems(context, PREF_WATCH_LATER, out);
    }

    private static JSONObject stage3FindSubscription(Context context, JSONObject item) {
        String key = stage3SeriesKey(item);
        if (key.length() == 0) return null;
        JSONArray items = stage3ReadItems(context, PREF_SERIES_SUBSCRIPTIONS);
        for (int i = 0; i < items.length(); i++) {
            JSONObject current = items.optJSONObject(i);
            if (current != null && key.equals(current.optString("series_key", ""))) {
                return current;
            }
        }
        return null;
    }

    private static boolean toggleStage3Subscription(Context context, JSONObject item) {
        if (context == null || item == null) return false;
        String key = stage3SeriesKey(item);
        if (key.length() == 0) return false;

        JSONArray old = stage3ReadItems(context, PREF_SERIES_SUBSCRIPTIONS);
        boolean exists = false;
        JSONObject existing = null;
        for (int i = 0; i < old.length(); i++) {
            JSONObject current = old.optJSONObject(i);
            if (current != null && key.equals(current.optString("series_key", ""))) {
                exists = true;
                existing = current;
                break;
            }
        }

        JSONArray out = new JSONArray();
        if (!exists) {
            try {
                JSONObject current = new JSONObject();
                current.put("series_key", key);
                current.put("series_id", jsonText(item, "series_id", "id"));
                current.put("title", jsonText(item, "series_title", "title", "name"));
                current.put("poster", jsonText(item, "series_poster", "poster", "season_poster"));
                current.put("notifications_enabled", true);
                current.put("added_at", System.currentTimeMillis());
                JSONArray knownEpisodes = stage6EpisodeArray(item);
                current.put("last_known_episode_count", knownEpisodes.length());
                current.put(KEY_STAGE6_SIGNATURES, stage6EpisodeSignatures(knownEpisodes).toString());
                current.put(KEY_STAGE6_BASELINE, true);
                out.put(current);
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "toggleStage3Subscription", ignored); }
        }

        for (int i = 0; i < old.length(); i++) {
            JSONObject current = old.optJSONObject(i);
            if (current == null) continue;
            if (key.equals(current.optString("series_key", ""))) {
                if (exists) continue;
                if (existing != null) continue;
            }
            out.put(current);
            if (out.length() >= 100) break;
        }

        stage3WriteItems(context, PREF_SERIES_SUBSCRIPTIONS, out);
        return !exists;
    }

    private static Boolean toggleStage3SeriesNotifications(Context context, JSONObject item) {
        if (context == null || item == null) return null;
        String key = stage3SeriesKey(item);
        if (key.length() == 0) return null;

        JSONArray old = stage3ReadItems(context, PREF_SERIES_SUBSCRIPTIONS);
        JSONArray out = new JSONArray();
        Boolean newState = null;

        for (int i = 0; i < old.length(); i++) {
            JSONObject current = old.optJSONObject(i);
            if (current == null) continue;
            if (key.equals(current.optString("series_key", ""))) {
                try {
                    JSONObject changed = new JSONObject(current.toString());
                    boolean enabled = !current.optBoolean("notifications_enabled", true);
                    changed.put("notifications_enabled", enabled);
                    if (enabled) {
                        JSONArray knownEpisodes = stage6EpisodeArray(item);
                        if (knownEpisodes.length() > 0) {
                            changed.put("last_known_episode_count", knownEpisodes.length());
                            changed.put(
                                    KEY_STAGE6_SIGNATURES,
                                    stage6EpisodeSignatures(knownEpisodes).toString()
                            );
                            changed.put(KEY_STAGE6_BASELINE, true);
                        }
                    }
                    out.put(changed);
                    newState = Boolean.valueOf(enabled);
                } catch (Throwable ignored) {
                    out.put(current);
                }
            } else {
                out.put(current);
            }
        }

        if (newState != null) stage3WriteItems(context, PREF_SERIES_SUBSCRIPTIONS, out);
        return newState;
    }

    private static int stage3EpisodeCount(JSONObject item) {
        if (item == null) return 0;
        String raw = jsonText(item, "series_episodes_json");
        if (raw.length() == 0) return 0;
        try {
            Object parsed = new JSONTokener(raw).nextValue();
            if (parsed instanceof JSONArray) return ((JSONArray) parsed).length();
            if (parsed instanceof JSONObject) {
                JSONObject object = (JSONObject) parsed;
                JSONArray episodes = object.optJSONArray("episodes");
                if (episodes != null) return episodes.length();
            }
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stage3EpisodeCount", ignored); }
        return 0;
    }

    private static void stage6StartPeriodicChecks() {
        synchronized (CigramStage1InitProvider.class) {
            if (STAGE6_PERIODIC_STARTED) return;
            STAGE6_PERIODIC_STARTED = true;
        }

        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (appContext != null) {
                        stage6CheckForNewEpisodes(appContext, false);
                    }
                } finally {
                    MAIN.postDelayed(this, STAGE6_PERIODIC_INTERVAL_MS);
                }
            }
        }, STAGE6_PERIODIC_INTERVAL_MS);
    }

    private static void stage6RefreshBackgroundJob(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 21) return;
        try {
            android.app.job.JobScheduler scheduler =
                    (android.app.job.JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return;

            if (!stage6HasEnabledSubscriptions(context) && !stage6NewReleaseAlertsEnabled(context)) {
                scheduler.cancel(STAGE6_JOB_ID);
                return;
            }

            boolean alreadyScheduled = false;
            if (Build.VERSION.SDK_INT >= 24) {
                alreadyScheduled = scheduler.getPendingJob(STAGE6_JOB_ID) != null;
            } else {
                java.util.List<android.app.job.JobInfo> jobs = scheduler.getAllPendingJobs();
                if (jobs != null) {
                    for (android.app.job.JobInfo info : jobs) {
                        if (info != null && info.getId() == STAGE6_JOB_ID) {
                            alreadyScheduled = true;
                            break;
                        }
                    }
                }
            }
            if (alreadyScheduled) return;

            android.content.ComponentName component = new android.content.ComponentName(
                    context,
                    CigramStage6JobService.class
            );
            android.app.job.JobInfo job = new android.app.job.JobInfo.Builder(
                    STAGE6_JOB_ID,
                    component
            )
                    .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(STAGE6_PERIODIC_INTERVAL_MS)
                    .setPersisted(true)
                    .build();
            int result = scheduler.schedule(job);
            if (result != android.app.job.JobScheduler.RESULT_SUCCESS) {
                android.util.Log.w("CigramStage6", "Background job was not accepted by JobScheduler");
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramStage6", "Background job schedule failed", error);
        }
    }

    private static void stage6HandleMainOpen(Activity activity) {
        if (activity == null || !isAlive(activity)) return;
        stage6RefreshBackgroundJob(activity);
        // Removed: the app no longer prompts to enable notifications on launch. Notifications are
        // only requested when the user deliberately opts in (Settings / subscribing to a title).

        boolean shouldForce = false;
        synchronized (CigramStage1InitProvider.class) {
            if (!STAGE6_PROCESS_OPEN_CHECK_STARTED) {
                STAGE6_PROCESS_OPEN_CHECK_STARTED = true;
                shouldForce = true;
            }
        }
        stage6CheckForNewEpisodes(activity, shouldForce);
        // v10.67: topics (re-subscribe if a previous attempt failed) + the admin's messages
        try { CigramFcm.sync(activity); } catch (Throwable ignored) { }
        try { CigramPush.syncAnnouncements(activity, false, null); } catch (Throwable ignored) { }
        // v10.62: request statuses + share-link capability, both quiet and rate-limited
        try { CigramRequests.refreshQuietly(activity); } catch (Throwable ignored) { }
        try { CigramLinks.refresh(activity); } catch (Throwable ignored) { }
    }

    /** v10.62: immediate subscription check (FCM push). Same path as the background job, forced. */
    public static void stage6CheckNow(Context context) {
        if (context == null) return;
        try {
            stage6CheckForNewEpisodes(context, true);
        } catch (Throwable error) {
            android.util.Log.w("CigramStage6", "check now failed", error);
        }
    }

    private static boolean stage6HasEnabledSubscriptions(Context context) {
        if (context == null) return false;

        JSONArray items = stage3ReadItems(context, PREF_SERIES_SUBSCRIPTIONS);
        for (int i = 0; i < items.length(); i++) {
            if (items.optJSONObject(i) != null) return true;
        }
        return false;
    }

    private static void stage6RequestNotificationPermissionIfNeeded(
            final Activity activity,
            boolean fromUserAction
    ) {
        if (activity == null || !isAlive(activity)) return;
        if (Build.VERSION.SDK_INT < 33) return;
        if (!stage6HasEnabledSubscriptions(activity) && !stage6NewReleaseAlertsEnabled(activity) && !fromUserAction) return;

        try {
            if (stage6HasNotificationPermission(activity)) return;

            final SharedPreferences prefs =
                    activity.getSharedPreferences(PREF_STAGE6, Context.MODE_PRIVATE);
            final boolean requested =
                    prefs.getBoolean(KEY_STAGE6_PERMISSION_REQUESTED, false);

            if (requested) {
                if (fromUserAction) stage6OpenNotificationSettings(activity);
                return;
            }

            // «ليس الآن» / «تخطّي»: لا نعيد الشاشة تلقائياً قبل انتهاء المهلة، إلا بطلب من المستخدم.
            if (!fromUserAction) {
                long laterAt = prefs.getLong(KEY_STAGE6_PERMISSION_LATER_AT, 0L);
                long now = System.currentTimeMillis();
                if (laterAt > 0L && now >= laterAt
                        && now - laterAt < STAGE6_PERMISSION_LATER_COOLDOWN_MS) {
                    return;
                }
            }

            // The custom «تفعيل الإشعارات» screen was removed from the app. A deliberate opt-in
            // (from Settings, or by subscribing to a title) now goes straight to the system
            // notification-permission dialog, with no intermediate screen.
            try {
                prefs.edit()
                        .putBoolean(KEY_STAGE6_PERMISSION_REQUESTED, true)
                        .apply();
                activity.requestPermissions(
                        new String[]{"android.permission.POST_NOTIFICATIONS"},
                        6606
                );
            } catch (Throwable error) {
                android.util.Log.w(
                        "CigramStage6",
                        "Notification permission request failed",
                        error
                );
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramStage6", "Notification permission flow failed", error);
        }
    }

    public static boolean areStage6SystemNotificationsEnabled(Context context) {
        if (context == null) return false;
        if (!stage6HasNotificationPermission(context)) return false;
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                android.app.NotificationManager manager =
                        (android.app.NotificationManager) context.getSystemService(
                                Context.NOTIFICATION_SERVICE);
                if (manager == null || !manager.areNotificationsEnabled()) return false;
                if (Build.VERSION.SDK_INT >= 26) {
                    android.app.NotificationChannel channel =
                            manager.getNotificationChannel(STAGE6_CHANNEL_ID);
                    if (channel != null
                            && channel.getImportance() == android.app.NotificationManager.IMPORTANCE_NONE) {
                        return false;
                    }
                }
                return true;
            } catch (Throwable error) {
                android.util.Log.w(
                        "CigramStage6",
                        "Unable to read notification enabled state",
                        error
                );
                return false;
            }
        }
        return true;
    }

    public static void requestStage6NotificationPermission(
            Activity activity,
            boolean fromUserAction
    ) {
        stage6RequestNotificationPermissionIfNeeded(activity, fromUserAction);
    }

    public static void openStage6NotificationSettings(Activity activity) {
        stage6OpenNotificationSettings(activity);
    }

    private static void stage6OpenNotificationSettings(Activity activity) {
        if (activity == null) return;
        try {
            android.content.Intent intent =
                    new android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            activity.startActivity(intent);
        } catch (Throwable first) {
            try {
                android.content.Intent intent =
                        new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(android.net.Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
            } catch (Throwable second) {
                android.util.Log.w(
                        "CigramStage6",
                        "Unable to open notification settings",
                        second
                );
            }
        }
    }

    private static boolean stage6HasNotificationPermission(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT < 33) return true;
        try {
            return context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void stage6CreateNotificationChannel(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 26) return;
        try {
            android.app.NotificationManager manager =
                    (android.app.NotificationManager) context.getSystemService(
                            Context.NOTIFICATION_SERVICE);
            if (manager == null) return;

            android.app.NotificationChannel existing =
                    manager.getNotificationChannel(STAGE6_CHANNEL_ID);
            if (existing != null) return;

            android.app.NotificationChannel channel =
                    new android.app.NotificationChannel(
                            STAGE6_CHANNEL_ID,
                            "حلقات جديدة",
                            stage6MigratedImportance(manager, STAGE6_CHANNEL_ID_OLD)
                    );
            channel.setDescription("إشعارات الحلقات الجديدة للمسلسلات المشترك بها");
            channel.enableVibration(true);
            manager.createNotificationChannel(channel);
            try { manager.deleteNotificationChannel(STAGE6_CHANNEL_ID_OLD); } catch (Throwable ignored) { }
        } catch (Throwable error) {
            android.util.Log.w("CigramStage6", "Notification channel creation failed", error);
        }
    }

    private static void stage6CheckForNewEpisodes(final Context context, final boolean force) {
        stage6CheckForNewEpisodes(context, force, null);
    }

    public static void runStage6ScheduledCheck(
            final Context context,
            final Runnable completion
    ) {
        stage6CheckForNewEpisodes(context, false, completion);
    }

    public static void runStage6UserRefresh(
            final Context context,
            final Runnable completion
    ) {
        stage6CheckForNewEpisodes(context, true, completion);
    }

    private static void stage6FinishCompletion(final Runnable completion) {
        if (completion == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                try { completion.run(); }
                catch (Throwable ignored) { android.util.Log.w("CigramInjected", "run", ignored); }
            }
        });
    }

    private static void stage6CheckForNewEpisodes(
            final Context context,
            final boolean force,
            final Runnable completion
    ) {
        if (context == null) {
            stage6FinishCompletion(completion);
            return;
        }
        if (!stage6HasEnabledSubscriptions(context) && !stage6NewReleaseAlertsEnabled(context)) {
            stage6FinishCompletion(completion);
            return;
        }

        final Context safeContext = context.getApplicationContext() == null
                ? context
                : context.getApplicationContext();

        long now = System.currentTimeMillis();
        long lastCheck = safeContext.getSharedPreferences(PREF_STAGE6, Context.MODE_PRIVATE)
                .getLong(KEY_STAGE6_LAST_CHECK, 0L);
        if (!force && lastCheck > 0L
                && now - lastCheck < STAGE6_MIN_CHECK_INTERVAL_MS) {
            stage6FinishCompletion(completion);
            return;
        }

        synchronized (CigramStage1InitProvider.class) {
            if (STAGE6_CHECK_RUNNING) {
                stage6FinishCompletion(completion);
                return;
            }
            STAGE6_CHECK_RUNNING = true;
        }

        IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    FetchResult result = fetchCatalog(safeContext);
                    if (!result.ok || !isValidCatalog(result.body)) return;

                    PreparedCatalog prepared = prepareCatalog(result.body);
                    JSONArray index;
                    try {
                        index = new JSONArray(prepared.searchIndexJson);
                    } catch (Throwable error) {
                        index = new JSONArray();
                    }
                    if (index.length() == 0) return;

                    JSONArray subscriptions =
                            stage3ReadItems(safeContext, PREF_SERIES_SUBSCRIPTIONS);
                    JSONArray updated = new JSONArray();

                    for (int i = 0; i < subscriptions.length(); i++) {
                        JSONObject oldSubscription = subscriptions.optJSONObject(i);
                        if (oldSubscription == null) continue;

                        JSONObject subscription;
                        try {
                            subscription = new JSONObject(oldSubscription.toString());
                        } catch (Throwable ignored) {
                            subscription = oldSubscription;
                        }

                        JSONObject series = stage6FindSeries(index, subscription);
                        if (series == null) {
                            android.util.Log.w(
                                    "CigramStage6",
                                    "Subscribed series not found in catalog. series_id="
                                            + jsonText(subscription, "series_id", "id")
                                            + ", title="
                                            + jsonText(subscription, "title", "series_title")
                            );
                            updated.put(subscription);
                            continue;
                        }

                        JSONArray episodes = stage6EpisodeArray(series);
                        int currentCount = episodes.length();
                        int previousCount = subscription.optInt("last_known_episode_count", -1);
                        boolean baselineInitialized =
                                subscription.optBoolean(KEY_STAGE6_BASELINE, previousCount >= 0);

                        if (!baselineInitialized || previousCount < 0) {
                            subscription.put("last_known_episode_count", currentCount);
                            subscription.put(
                                    KEY_STAGE6_SIGNATURES,
                                    stage6EpisodeSignatures(episodes).toString()
                            );
                            subscription.put(KEY_STAGE6_BASELINE, true);
                            stage6RefreshSubscriptionIdentity(subscription, series);
                            updated.put(subscription);
                            continue;
                        }

                        if (currentCount < previousCount) {
                            stage6RefreshSubscriptionIdentity(subscription, series);
                            updated.put(subscription);
                            continue;
                        }

                        if (currentCount > previousCount) {
                            java.util.ArrayList<JSONObject> newEpisodes =
                                    stage6FindNewEpisodes(subscription, episodes, previousCount);
                            if (newEpisodes.isEmpty()) {
                                int delta = currentCount - previousCount;
                                int start = Math.max(0, currentCount - delta);
                                for (int epIndex = start; epIndex < currentCount; epIndex++) {
                                    JSONObject episode = episodes.optJSONObject(epIndex);
                                    if (episode != null) newEpisodes.add(episode);
                                }
                            }

                            for (JSONObject episode : newEpisodes) {
                                stage6RecordNotificationHistory(
                                        safeContext,
                                        subscription,
                                        series,
                                        episode
                                );
                                if (stage6HasNotificationPermission(safeContext)) {
                                    stage6ShowEpisodeNotification(
                                            safeContext,
                                            subscription,
                                            series,
                                            episode
                                    );
                                }
                            }
                        }

                        subscription.put("last_known_episode_count", currentCount);
                        subscription.put(
                                KEY_STAGE6_SIGNATURES,
                                stage6EpisodeSignatures(episodes).toString()
                        );
                        subscription.put(KEY_STAGE6_BASELINE, true);
                        stage6RefreshSubscriptionIdentity(subscription, series);
                        updated.put(subscription);
                    }

                    stage3WriteItems(safeContext, PREF_SERIES_SUBSCRIPTIONS, updated);
                    stage6CheckNewTitles(safeContext, index);
                    safeContext.getSharedPreferences(PREF_STAGE6, Context.MODE_PRIVATE)
                            .edit()
                            .putLong(KEY_STAGE6_LAST_CHECK, System.currentTimeMillis())
                            .apply();

                } catch (Throwable error) {
                    android.util.Log.w("CigramStage6", "Episode check failed", error);
                } finally {
                    synchronized (CigramStage1InitProvider.class) {
                        STAGE6_CHECK_RUNNING = false;
                    }
                    stage6FinishCompletion(completion);
                }
            }
        });
    }

    private static JSONObject stage6FindSeries(JSONArray index, JSONObject subscription) {
        if (index == null || subscription == null) return null;

        String wantedId = normalizeSearch(jsonText(subscription, "series_id", "id"));
        String wantedTitle = normalizeSearch(jsonText(
                subscription, "title", "series_title", "name"));
        String wantedKey = normalizeSearch(subscription.optString("series_key", ""));
        JSONObject titleMatch = null;

        for (int i = 0; i < index.length(); i++) {
            JSONObject item = index.optJSONObject(i);
            if (item == null) continue;
            if (!"series".equals(inferContentType(item, ""))) continue;

            String itemId = normalizeSearch(jsonText(item, "series_id", "id"));
            if (wantedId.length() > 0 && wantedId.equals(itemId)) return item;

            String itemKey = normalizeSearch(stage3SeriesKey(item));
            if (wantedKey.length() > 0 && wantedKey.equals(itemKey)) return item;

            String itemTitle = normalizeSearch(jsonText(
                    item,
                    "series_title", "title", "name", "title_ar", "title_en"
            ));
            if (wantedTitle.length() > 0
                    && wantedTitle.equals(itemTitle)
                    && titleMatch == null) {
                titleMatch = item;
            }
        }
        return titleMatch;
    }

    private static void stage6RefreshSubscriptionIdentity(
            JSONObject subscription,
            JSONObject series
    ) {
        if (subscription == null || series == null) return;
        try {
            String id = jsonText(series, "series_id", "id");
            String title = jsonText(series, "series_title", "title", "name");
            String poster = jsonText(series, "series_poster", "poster", "season_poster");
            if (id.length() > 0) subscription.put("series_id", id);
            if (title.length() > 0) subscription.put("title", title);
            if (poster.length() > 0) subscription.put("poster", poster);
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "equals", ignored); }
    }

    private static JSONArray stage6EpisodeArray(JSONObject series) {
        JSONArray result = new JSONArray();
        if (series == null) return result;

        try {
            java.util.LinkedHashMap<String, JSONObject> unique =
                    new java.util.LinkedHashMap<String, JSONObject>();
            collectEpisodesFromItem(series, unique);

            java.util.ArrayList<JSONObject> list =
                    new java.util.ArrayList<JSONObject>(unique.values());
            java.util.Collections.sort(list, new java.util.Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) {
                    int sa = intText(a, "season", "season_number");
                    int sb = intText(b, "season", "season_number");
                    if (sa != sb) return sa < sb ? -1 : 1;
                    int ea = intText(a, "episode", "episode_number", "ep", "number");
                    int eb = intText(b, "episode", "episode_number", "ep", "number");
                    if (ea != eb) return ea < eb ? -1 : 1;
                    long ra = contentRecency(a);
                    long rb = contentRecency(b);
                    return ra < rb ? -1 : (ra == rb ? 0 : 1);
                }
            });

            for (JSONObject episode : list) result.put(episode);
        } catch (Throwable error) {
            android.util.Log.w("CigramStage6", "Episode extraction failed", error);
        }
        return result;
    }

    private static JSONArray stage6EpisodeSignatures(JSONArray episodes) {
        JSONArray result = new JSONArray();
        if (episodes == null) return result;
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject episode = episodes.optJSONObject(i);
            if (episode == null) continue;
            String signature = stage6EpisodeSignature(episode);
            if (signature.length() > 0) result.put(signature);
        }
        return result;
    }

    private static String stage6EpisodeSignature(JSONObject episode) {
        if (episode == null) return "";
        String season = clean(jsonText(episode, "season", "season_number"));
        String number = clean(jsonText(episode, "episode", "episode_number", "ep", "number"));
        if (season.length() > 0 || number.length() > 0) {
            return "s:" + season + "|e:" + number;
        }

        String id = clean(jsonText(episode, "id", "episode_id"));
        if (id.length() > 0) return "id:" + id;

        String video = normalizeSearch(jsonText(
                episode, "video", "link", "url", "video_url", "movie_url"));
        return video.length() == 0 ? "" : "v:" + video;
    }

    private static java.util.HashSet<String> stage6ReadSignatureSet(JSONObject subscription) {
        java.util.HashSet<String> result = new java.util.HashSet<String>();
        if (subscription == null) return result;
        String raw = subscription.optString(KEY_STAGE6_SIGNATURES, "");
        if (raw == null || raw.trim().length() == 0) return result;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                if (value.length() > 0) result.add(value);
            }
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stage6ReadSignatureSet", ignored); }
        return result;
    }

    private static java.util.ArrayList<JSONObject> stage6FindNewEpisodes(
            JSONObject subscription,
            JSONArray currentEpisodes,
            int previousCount
    ) {
        java.util.ArrayList<JSONObject> result = new java.util.ArrayList<JSONObject>();
        if (currentEpisodes == null || currentEpisodes.length() == 0) return result;

        java.util.HashSet<String> old = stage6ReadSignatureSet(subscription);
        if (!old.isEmpty()) {
            for (int i = 0; i < currentEpisodes.length(); i++) {
                JSONObject episode = currentEpisodes.optJSONObject(i);
                if (episode == null) continue;
                String signature = stage6EpisodeSignature(episode);
                if (signature.length() > 0 && !old.contains(signature)) {
                    result.add(episode);
                }
            }
            return result;
        }

        int delta = currentEpisodes.length() - Math.max(0, previousCount);
        int start = Math.max(0, currentEpisodes.length() - Math.max(0, delta));
        for (int i = start; i < currentEpisodes.length(); i++) {
            JSONObject episode = currentEpisodes.optJSONObject(i);
            if (episode != null) result.add(episode);
        }
        return result;
    }

    private static String stage6HistoryStorageKey(Context context) {
        String identity = "guest";
        try {
            SharedPreferences auth = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE);
            String email = auth.getString("email", "");
            if (email != null && email.trim().length() > 0) {
                identity = email.trim().toLowerCase(java.util.Locale.US);
            }
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stage6HistoryStorageKey", ignored); }
        return KEY_STAGE6_HISTORY + "_" + Integer.toHexString(identity.hashCode());
    }

    private static String stage6UnreadStorageKey(Context context) {
        String identity = "guest";
        try {
            SharedPreferences auth = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE);
            String email = auth.getString("email", "");
            if (email != null && email.trim().length() > 0) {
                identity = email.trim().toLowerCase(java.util.Locale.US);
            }
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stage6UnreadStorageKey", ignored); }
        return KEY_STAGE6_UNREAD + "_" + Integer.toHexString(identity.hashCode());
    }

    public static boolean hasStage6Unread(Context context) {
        if (context == null) return false;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_STAGE6, Context.MODE_PRIVATE);
            return prefs.getBoolean(stage6UnreadStorageKey(context), false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void markStage6NotificationsRead(Context context) {
        if (context == null) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_STAGE6, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(stage6UnreadStorageKey(context), false).apply();
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "markStage6NotificationsRead", ignored); }
        try {
            CigramSketchwareNotifications.notifyUnreadChanged();
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "markStage6NotificationsRead", ignored); }
    }

    private static void stage6MarkUnread(Context context) {
        if (context == null) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_STAGE6, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(stage6UnreadStorageKey(context), true).apply();
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stage6MarkUnread", ignored); }
        try {
            CigramSketchwareNotifications.notifyUnreadChanged();
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stage6MarkUnread", ignored); }
    }

    private static JSONArray stage6ReadHistory(Context context) {
        if (context == null) return new JSONArray();
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_STAGE6, Context.MODE_PRIVATE);
            String key = stage6HistoryStorageKey(context);
            String raw = prefs.getString(key, null);

            if (raw == null) {
                raw = prefs.getString(KEY_STAGE6_HISTORY, "[]");
                String guestKey = KEY_STAGE6_HISTORY + "_" + Integer.toHexString("guest".hashCode());
                if (!guestKey.equals(key)) {
                    raw = stage6UniteHistory(raw, prefs.getString(guestKey, "[]"));
                }
                prefs.edit().putString(key, raw == null ? "[]" : raw).apply();
            }
            if (raw == null || raw.trim().length() == 0) return new JSONArray();
            return new JSONArray(raw);
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }

    public static JSONArray getStage6History(Context context) {
        return stage6ReadHistory(context);
    }

    /**
     * Episode notifications are permanent: they stay in the account's history until the app data
     * itself is wiped. Kept as a no-op so nothing can remove them by accident.
     */
    public static void deleteStage6HistoryItem(Context context, String notificationKey) {
        android.util.Log.i("CigramStage6", "Notification history is permanent; delete ignored");
    }

    /** Union of two saved histories (by notification_key), newest first. Never drops an entry. */
    private static String stage6UniteHistory(String first, String second) {
        try {
            java.util.LinkedHashMap<String, JSONObject> map = new java.util.LinkedHashMap<String, JSONObject>();
            String[] both = new String[]{first, second};
            for (String raw : both) {
                if (raw == null || raw.trim().length() == 0) continue;
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String k = o.optString("notification_key", "");
                    if (k.length() == 0 || map.containsKey(k)) continue;
                    map.put(k, o);
                }
            }
            java.util.ArrayList<JSONObject> list = new java.util.ArrayList<JSONObject>(map.values());
            java.util.Collections.sort(list, new java.util.Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) {
                    long x = a.optLong("created_at", 0L);
                    long y = b.optLong("created_at", 0L);
                    return x == y ? 0 : (x < y ? 1 : -1);
                }
            });
            JSONArray out = new JSONArray();
            for (JSONObject o : list) out.put(o);
            return out.toString();
        } catch (Throwable error) {
            return first == null ? "[]" : first;
        }
    }

    /** Saved notification + any missing detail (story, genre, year, rating, posters) from the catalog. */
    public static JSONObject stage6CardDetails(JSONObject saved) {
        JSONObject out = new JSONObject();
        if (saved == null) return out;
        try {
            out = new JSONObject(saved.toString());
            JSONObject series = findCatalogMemoryItem(
                    "series", saved.optString("series_id", ""), saved.optString("title", ""));
            if (series == null) return out;
            String[][] fill = new String[][]{
                    {"description", "description", "overview", "plot", "synopsis"},
                    {"category", "category", "genre", "genres"},
                    {"year", "year", "release_year"},
                    {"rating", "imdb", "rating", "imdb_rating", "vote_average", "score"},
                    {"quality", "quality"},
                    {"backdrop", "backdrop", "landscape_poster", "source_backdrop"},
                    {"poster", "poster", "series_poster", "season_poster", "image"}
            };
            for (String[] f : fill) {
                if (out.optString(f[0], "").trim().length() > 0) continue;
                String[] keys = java.util.Arrays.copyOfRange(f, 1, f.length);
                String value = jsonText(series, keys);
                if (value.length() > 0) out.put(f[0], value);
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramStage6", "card details failed", error);
        }
        return out;
    }

    /** "تشغيل الحلقة الجديدة": opens the player on the exact episode of the notification. */
    public static void playStage6Episode(Activity activity, JSONObject saved) {
        if (activity == null || saved == null || !isAlive(activity)) return;
        try {
            String seriesId = saved.optString("series_id", "");
            String title = saved.optString("title", "");
            String season = saved.optString("season", "");
            String number = saved.optString("episode", "");

            JSONObject series = findCatalogMemoryItem("series", seriesId, title);
            JSONObject episode = null;
            if (series != null) {
                JSONArray eps = stage6EpisodeArray(series);
                int wantSeason = parseIntSafe(season);
                int wantEp = parseIntSafe(number);
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject e = eps.optJSONObject(i);
                    if (e == null) continue;
                    if (intText(e, "season", "season_number") == wantSeason
                            && intText(e, "episode", "episode_number", "ep", "number") == wantEp
                            && jsonText(e, "video", "link", "url", "video_url", "movie_url").length() > 0) {
                        episode = new JSONObject(e.toString());
                        break;
                    }
                }
            }
            if (episode == null) {
                // catalog not loaded (or episode moved): use the link saved with the notification
                String video = saved.optString("video", "");
                if (video.length() == 0) {
                    Toast.makeText(activity, "رابط الحلقة غير متوفر حالياً", Toast.LENGTH_SHORT).show();
                    return;
                }
                episode = new JSONObject();
                episode.put("video", video);
                episode.put("subtitle", saved.optString("subtitle", ""));
                episode.put("title", saved.optString("episode_title", ""));
            }
            if (jsonText(episode, "season").length() == 0) episode.put("season", season);
            if (jsonText(episode, "episode").length() == 0) episode.put("episode", number);
            episode.put("type", "series");
            if (jsonText(episode, "series_id").length() == 0 && seriesId.length() > 0) {
                episode.put("series_id", seriesId);
                episode.put("id", seriesId);
            }
            String seriesTitle = saved.optString("series_title", "");
            if (seriesTitle.length() == 0) seriesTitle = title;
            if (jsonText(episode, "series_title").length() == 0 && seriesTitle.length() > 0) {
                episode.put("series_title", seriesTitle);
            }
            if (jsonText(episode, "poster", "backdrop").length() == 0) {
                episode.put("poster", saved.optString("backdrop", saved.optString("poster", "")));
            }

            JSONObject parent = series != null ? series : new JSONObject();
            if (series == null) {
                parent.put("series_title", seriesTitle);
                parent.put("title", seriesTitle);
                parent.put("series_id", seriesId);
                parent.put("poster", saved.optString("poster", ""));
            }
            openPlayerForTitleItem(activity, episode, parent, false);
        } catch (Throwable error) {
            android.util.Log.w("CigramStage6", "play episode failed", error);
            Toast.makeText(activity, "تعذر تشغيل الحلقة", Toast.LENGTH_SHORT).show();
        }
    }

    private static int parseIntSafe(String value) {
        String text = value == null ? "" : value.replaceAll("[^0-9-]", "");
        if (text.length() == 0 || "-".equals(text)) return 0;
        try { return Integer.parseInt(text); } catch (Throwable ignored) { return 0; }
    }

    private static void stage6RecordNotificationHistory(
            Context context,
            JSONObject subscription,
            JSONObject series,
            JSONObject episode
    ) {
        if (context == null || series == null || episode == null) return;
        try {
            String title = jsonText(
                    series, "series_title", "title", "name", "title_ar", "title_en");
            if (title.length() == 0 && subscription != null) {
                title = jsonText(subscription, "title", "series_title");
            }

            String poster = jsonText(
                    series, "series_poster", "poster", "season_poster", "backdrop");
            if (poster.length() == 0 && subscription != null) {
                poster = jsonText(subscription, "poster", "series_poster");
            }

            String season = jsonText(episode, "season", "season_number");
            String number = jsonText(episode, "episode", "episode_number", "ep", "number");
            String seriesKey = subscription == null
                    ? stage3SeriesKey(series)
                    : subscription.optString("series_key", stage3SeriesKey(series));
            String seriesId = jsonText(series, "series_id", "id");
            if (seriesId.length() == 0 && subscription != null) {
                seriesId = jsonText(subscription, "series_id");
            }

            String uniqueKey = seriesKey + "|" + season + "|" + number;
            SharedPreferences prefs = context.getSharedPreferences(PREF_STAGE6, Context.MODE_PRIVATE);
            JSONArray old = stage6ReadHistory(context);

            for (int i = 0; i < old.length(); i++) {
                JSONObject current = old.optJSONObject(i);
                if (current == null) continue;
                if (uniqueKey.equals(current.optString("notification_key", ""))) {
                    return;
                }
            }

            JSONArray out = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("notification_key", uniqueKey);
            item.put("series_key", seriesKey);
            item.put("series_id", seriesId);
            item.put("title", title);
            item.put("poster", poster);
            item.put("season", season);
            item.put("episode", number);
            item.put("created_at", System.currentTimeMillis());
            // full snapshot so the card stays complete even if the catalog changes later
            item.put("series_title", title);
            item.put("backdrop", jsonText(series, "backdrop", "landscape_poster", "source_backdrop"));
            item.put("description", jsonText(series, "description", "overview", "plot", "synopsis"));
            item.put("category", jsonText(series, "category", "genre", "genres"));
            item.put("year", jsonText(series, "year", "release_year"));
            item.put("rating", jsonText(series, "imdb", "rating", "imdb_rating", "vote_average", "score"));
            item.put("quality", jsonText(series, "quality"));
            item.put("episode_title", jsonText(episode, "title", "name", "episode_title"));
            item.put("video", jsonText(episode, "video", "link", "url", "video_url", "movie_url"));
            item.put("subtitle", jsonText(episode, "subtitle", "subtitle_url"));
            out.put(item);

            for (int i = 0; i < old.length(); i++) {
                JSONObject current = old.optJSONObject(i);
                if (current == null) continue;
                out.put(current);
            }

            prefs.edit().putString(stage6HistoryStorageKey(context), out.toString()).apply();
            stage6MarkUnread(context);
        } catch (Throwable error) {
            android.util.Log.w("CigramStage6", "Notification history save failed", error);
        }
    }

    // ------------------------------------------------------------------ v5.21: new movie / series alerts

    private static boolean stage6NewReleaseAlertsEnabled(Context context) {
        if (context == null) return false;
        try {
            return context.getSharedPreferences(PREF_STAGE6, Context.MODE_PRIVATE)
                    .getBoolean(KEY_STAGE6_NEWREL_ENABLED, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Compares the catalog with the titles this phone already knew. The first run only memorizes
     * the catalog (no alerts). Later runs alert for every unseen movie / series. A batch bigger
     * than STAGE6_NEW_TITLE_MAX_BATCH is treated as a catalog rebuild and stays silent.
     */
    private static void stage6CheckNewTitles(Context context, JSONArray index) {
        if (context == null || index == null || index.length() == 0) return;
        try {
            if (!stage6NewReleaseAlertsEnabled(context)) return;
            SharedPreferences prefs = context.getSharedPreferences(PREF_STAGE6, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_STAGE6_KNOWN_TITLES, null);
            boolean baseline = raw == null || raw.trim().length() == 0;

            java.util.LinkedHashSet<String> known = new java.util.LinkedHashSet<String>();
            if (!baseline) {
                JSONArray old = new JSONArray(raw);
                for (int i = 0; i < old.length(); i++) {
                    String k = old.optString(i, "");
                    if (k.length() > 0) known.add(k);
                }
            }

            java.util.ArrayList<JSONObject> fresh = new java.util.ArrayList<JSONObject>();
            java.util.ArrayList<String> freshKeys = new java.util.ArrayList<String>();
            for (int i = 0; i < index.length(); i++) {
                JSONObject item = index.optJSONObject(i);
                if (item == null) continue;
                String key = searchContentKey(item);
                if (key.length() == 0) continue;
                if (known.add(key) && !baseline) {
                    fresh.add(item);
                    freshKeys.add(key);
                }
            }

            JSONArray saved = new JSONArray();
            for (String k : known) saved.put(k);
            prefs.edit().putString(KEY_STAGE6_KNOWN_TITLES, saved.toString()).apply();

            if (baseline || fresh.isEmpty()) return;
            if (fresh.size() > STAGE6_NEW_TITLE_MAX_BATCH) {
                android.util.Log.w("CigramStage6", "New-title batch of " + fresh.size() + " ignored (catalog rebuild)");
                return;
            }

            for (int i = fresh.size() - 1; i >= 0; i--) {
                if (stage6WasPushed(context, stage6PushNameKey(stage6NewTitleName(fresh.get(i))))) {
                    fresh.remove(i);          // already announced by a push
                    freshKeys.remove(i);
                }
            }
            if (fresh.isEmpty()) return;
            for (int i = 0; i < fresh.size(); i++) {
                stage6RecordNewTitleHistory(context, fresh.get(i), freshKeys.get(i));
            }
            if (stage6HasNotificationPermission(context)) {
                stage6ShowNewTitlesNotification(context, fresh);
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramStage6", "New-title check failed", error);
        }
    }

    private static String stage6NewTitleName(JSONObject item) {
        String t = jsonText(item, "series_title", "title", "title_ar", "title_en", "name");
        return t.length() == 0 ? "سيكرام" : t;
    }

    private static void stage6RecordNewTitleHistory(Context context, JSONObject item, String key) {
        if (context == null || item == null) return;
        try {
            String type = inferContentType(item, "");
            String uniqueKey = "new|" + key;
            SharedPreferences prefs = context.getSharedPreferences(PREF_STAGE6, Context.MODE_PRIVATE);
            JSONArray old = stage6ReadHistory(context);
            for (int i = 0; i < old.length(); i++) {
                JSONObject cur = old.optJSONObject(i);
                if (cur != null && uniqueKey.equals(cur.optString("notification_key", ""))) return;
            }
            String title = stage6NewTitleName(item);
            JSONObject h = new JSONObject();
            h.put("notification_key", uniqueKey);
            h.put("kind", "new_title");
            h.put("content_type", type);
            h.put("series_key", stage3SeriesKey(item));
            h.put("series_id", "series".equals(type)
                    ? jsonText(item, "series_id", "id") : jsonText(item, "id", "movie_id", "item_id", "content_id"));
            h.put("title", title);
            h.put("series_title", title);
            h.put("poster", jsonText(item, "series_poster", "poster", "season_poster", "image"));
            h.put("backdrop", jsonText(item, "backdrop", "landscape_poster", "source_backdrop"));
            h.put("description", jsonText(item, "description", "overview", "plot", "synopsis"));
            h.put("category", jsonText(item, "category", "genre", "genres"));
            h.put("year", jsonText(item, "year", "release_year"));
            h.put("rating", jsonText(item, "imdb", "rating", "imdb_rating", "vote_average", "score"));
            h.put("quality", jsonText(item, "quality"));
            h.put("created_at", System.currentTimeMillis());

            JSONArray out = new JSONArray();
            out.put(h);
            for (int i = 0; i < old.length(); i++) {
                JSONObject cur = old.optJSONObject(i);
                if (cur != null) out.put(cur);
            }
            prefs.edit().putString(stage6HistoryStorageKey(context), out.toString()).apply();
            stage6MarkUnread(context);
        } catch (Throwable error) {
            android.util.Log.w("CigramStage6", "New-title history save failed", error);
        }
    }

    private static void stage6CreateTitlesChannel(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 26) return;
        try {
            android.app.NotificationManager manager =
                    (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null || manager.getNotificationChannel(STAGE6_TITLES_CHANNEL_ID) != null) return;
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    STAGE6_TITLES_CHANNEL_ID, "أفلام ومسلسلات جديدة",
                    stage6MigratedImportance(manager, STAGE6_TITLES_CHANNEL_ID_OLD));
            channel.setDescription("تنبيه عند نزول فيلم أو مسلسل جديد في سيكرام");
            channel.enableVibration(true);
            manager.createNotificationChannel(channel);
            try { manager.deleteNotificationChannel(STAGE6_TITLES_CHANNEL_ID_OLD); } catch (Throwable ignored) { }
        } catch (Throwable error) {
            android.util.Log.w("CigramStage6", "Titles channel creation failed", error);
        }
    }

    /**
     * v10.67: the old channels were IMPORTANCE_DEFAULT (no pop-up). The new ones are HIGH, unless the
     * viewer had switched the old channel off — that choice is kept.
     */
    private static int stage6MigratedImportance(android.app.NotificationManager manager, String oldId) {
        int importance = android.app.NotificationManager.IMPORTANCE_HIGH;
        try {
            android.app.NotificationChannel old = manager.getNotificationChannel(oldId);
            if (old != null && old.getImportance() == android.app.NotificationManager.IMPORTANCE_NONE) {
                importance = android.app.NotificationManager.IMPORTANCE_NONE;
            }
        } catch (Throwable ignored) { }
        return importance;
    }

    /**
     * v10.67: an admin message (push or /announcements) as a row of the notifications page.
     * Returns true when it was not there yet (the bell gets its red dot).
     */
    public static boolean recordInboxAnnouncement(Context context, String id, String title, String body,
                                                  String level, long createdAt) {
        if (context == null || id == null || id.trim().length() == 0) return false;
        synchronized (CigramStage1InitProvider.class) {
            try {
                String uniqueKey = "ann|" + id.trim();
                SharedPreferences prefs = context.getSharedPreferences(PREF_STAGE6, Context.MODE_PRIVATE);
                JSONArray old = stage6ReadHistory(context);
                for (int i = 0; i < old.length(); i++) {
                    JSONObject cur = old.optJSONObject(i);
                    if (cur != null && uniqueKey.equals(cur.optString("notification_key", ""))) return false;
                }
                JSONObject h = new JSONObject();
                h.put("notification_key", uniqueKey);
                h.put("kind", "announcement");
                h.put("announcement_id", id.trim());
                h.put("title", title == null ? "" : title);
                h.put("body", body == null ? "" : body);
                h.put("level", level == null || level.length() == 0 ? "info" : level);
                h.put("created_at", createdAt > 0L ? createdAt : System.currentTimeMillis());
                java.util.ArrayList<JSONObject> list = new java.util.ArrayList<JSONObject>();
                list.add(h);
                for (int i = 0; i < old.length(); i++) {
                    JSONObject cur = old.optJSONObject(i);
                    if (cur != null) list.add(cur);
                }
                java.util.Collections.sort(list, new java.util.Comparator<JSONObject>() {
                    @Override public int compare(JSONObject a, JSONObject b) {
                        long x = a.optLong("created_at", 0L);
                        long y = b.optLong("created_at", 0L);
                        return x == y ? 0 : (x < y ? 1 : -1);
                    }
                });
                JSONArray out = new JSONArray();
                for (JSONObject o : list) out.put(o);
                prefs.edit().putString(stage6HistoryStorageKey(context), out.toString()).apply();
                stage6MarkUnread(context);
                return true;
            } catch (Throwable error) {
                android.util.Log.w("CigramStage6", "announcement history save failed", error);
                return false;
            }
        }
    }

    /** One title: opens its details page. Several: one summary that opens the app. */
    private static void stage6ShowNewTitlesNotification(Context context, java.util.List<JSONObject> fresh) {
        if (context == null || fresh == null || fresh.isEmpty()) return;
        stage6CreateTitlesChannel(context);
        try {
            JSONObject first = fresh.get(0);
            String firstName = stage6NewTitleName(first);
            String heading;
            String message;
            android.content.Intent intent = null;
            int requestCode;

            if (fresh.size() == 1) {
                boolean series = "series".equals(inferContentType(first, ""));
                heading = series ? "مسلسل جديد وصل" : "فيلم جديد وصل";
                message = firstName;
                try {
                    intent = new android.content.Intent(context, Class.forName(context.getPackageName() + ".DetailsActivity"));
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    putMinimalDetailsExtras(intent, first, firstName);
                    intent.putExtra("open_from_notification", "1");
                } catch (Throwable error) {
                    intent = null;
                }
                requestCode = 660800 + Math.abs(searchContentKey(first).hashCode() % 100000);
            } else {
                heading = "وصل محتوى جديد";
                message = fresh.size() + " أفلام ومسلسلات جديدة، أحدثها: " + firstName;
                requestCode = 660799;
            }
            if (intent == null) {
                intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                if (intent == null) return;
            }

            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            android.app.PendingIntent pending =
                    android.app.PendingIntent.getActivity(context, requestCode, intent, flags);

            int smallIcon = notificationSmallIcon(context);
            if (smallIcon == 0) smallIcon = android.R.drawable.stat_notify_more;

            android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new android.app.Notification.Builder(context, STAGE6_TITLES_CHANNEL_ID)
                    : new android.app.Notification.Builder(context);
            builder.setSmallIcon(smallIcon)
                    .setContentTitle(heading)
                    .setContentText(message)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(message))
                    .setAutoCancel(true)
                    .setContentIntent(pending)
                    .setColor(0xFFE21B14)
                    .setWhen(System.currentTimeMillis());
            if (Build.VERSION.SDK_INT < 26) {
                builder.setDefaults(android.app.Notification.DEFAULT_ALL);
                builder.setPriority(android.app.Notification.PRIORITY_HIGH);
            }
            android.app.NotificationManager manager =
                    (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(requestCode, builder.build());
        } catch (Throwable error) {
            android.util.Log.w("CigramStage6", "New-title notification failed", error);
        }
    }

    /** Used by CigramFcm: should this phone receive new-release pushes? */
    public static boolean fcmNewReleaseEnabled(Context context) {
        return stage6NewReleaseAlertsEnabled(context);
    }

    private static final String KEY_STAGE6_PUSHED = "pushed_titles_v1";

    private static String stage6PushNameKey(String title) {
        return normalizeSearch(title == null ? "" : title);
    }

    private static boolean stage6WasPushed(Context context, String nameKey) {
        if (nameKey == null || nameKey.length() == 0) return false;
        try {
            String raw = context.getSharedPreferences(PREF_STAGE6, Context.MODE_PRIVATE)
                    .getString(KEY_STAGE6_PUSHED, null);
            if (raw == null) return false;
            JSONArray arr = new JSONArray(raw);
            long limit = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && nameKey.equals(o.optString("n")) && o.optLong("t") > limit) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void stage6RememberPushed(Context context, String nameKey) {
        if (nameKey == null || nameKey.length() == 0) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_STAGE6, Context.MODE_PRIVATE);
            JSONArray old = new JSONArray(prefs.getString(KEY_STAGE6_PUSHED, "[]"));
            JSONArray out = new JSONArray();
            long limit = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000;
            JSONObject mine = new JSONObject();
            mine.put("n", nameKey);
            mine.put("t", System.currentTimeMillis());
            out.put(mine);
            for (int i = 0; i < old.length() && out.length() < 60; i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null && o.optLong("t") > limit && !nameKey.equals(o.optString("n"))) out.put(o);
            }
            prefs.edit().putString(KEY_STAGE6_PUSHED, out.toString()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** A new-release push arrived (FCM). Shows the alert, records history, and silences the poller for it. */
    public static void stage6OnPush(Context context, java.util.Map<String, String> data) {
        if (context == null || data == null) return;
        try {
            if (!stage6NewReleaseAlertsEnabled(context)) return;
            String title = data.get("title");
            if (title == null) title = "";
            String kind = data.get("kind");
            int count = 1;
            try { count = Math.max(1, Integer.parseInt(data.get("count"))); } catch (Throwable ignored) { }

            if ("new_title".equals(kind) && count == 1 && title.trim().length() > 0) {
                String nameKey = stage6PushNameKey(title);
                if (stage6WasPushed(context, nameKey)) return;      // duplicate push
                stage6RememberPushed(context, nameKey);

                JSONObject item = new JSONObject();
                String type = data.get("content_type");
                String id = data.get("content_id");
                item.put("title", title);
                item.put("type", "series".equals(type) ? "series" : "movie");
                if (id != null && id.length() > 0) {
                    item.put("id", id);
                    if ("series".equals(type)) item.put("series_id", id);
                }
                String poster = data.get("poster");
                if (poster != null && poster.length() > 0) item.put("poster", poster);

                String key = searchContentKey(item);
                // the poller must not alert again for this title (only if it already has a baseline)
                SharedPreferences prefs = context.getSharedPreferences(PREF_STAGE6, Context.MODE_PRIVATE);
                String raw = prefs.getString(KEY_STAGE6_KNOWN_TITLES, null);
                boolean alreadyAnnounced = false;
                if (raw != null && raw.trim().length() > 0 && key.length() > 0) {
                    JSONArray known = new JSONArray(raw);
                    boolean has = false;
                    for (int i = 0; i < known.length(); i++) {
                        if (key.equals(known.optString(i, ""))) { has = true; break; }
                    }
                    if (!has) {
                        known.put(key);
                        prefs.edit().putString(KEY_STAGE6_KNOWN_TITLES, known.toString()).apply();
                    } else {
                        // v10.67: the app's own catalog check already found (and announced) it
                        alreadyAnnounced = true;
                    }
                }
                if (alreadyAnnounced) return;
                stage6RecordNewTitleHistory(context, item, key.length() > 0 ? key : "push|" + nameKey);
                if (stage6HasNotificationPermission(context)) {
                    java.util.ArrayList<JSONObject> one = new java.util.ArrayList<JSONObject>();
                    one.add(item);
                    stage6ShowNewTitlesNotification(context, one);
                }
            } else {
                // several titles at once: one summary that opens the app
                String latest = title.trim().length() > 0 ? title : "سيكرام";
                String nameKey = "batch|" + count + "|" + stage6PushNameKey(data.get("body"));
                if (stage6WasPushed(context, nameKey)) return;
                stage6RememberPushed(context, nameKey);
                if (!stage6HasNotificationPermission(context)) return;
                JSONObject item = new JSONObject();
                item.put("title", latest);
                java.util.ArrayList<JSONObject> list = new java.util.ArrayList<JSONObject>();
                for (int i = 0; i < Math.max(2, count); i++) list.add(item);
                stage6ShowNewTitlesNotification(context, list);
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramStage6", "push handling failed", error);
        }
    }

    /** Opens the details page of a "new title" card from the notifications page. */
    public static void openStage6TitleDetails(Activity activity, JSONObject saved) {
        if (activity == null || saved == null || !isAlive(activity)) return;
        try {
            String type = saved.optString("content_type", "movie");
            String id = saved.optString("series_id", "");
            String title = saved.optString("title", "");
            JSONObject item = findCatalogMemoryItem(type, id, title);
            if (item == null) {
                item = new JSONObject();
                item.put("title", title);
                item.put("poster", saved.optString("poster", ""));
                item.put("type", type);
                if (id.length() > 0) {
                    item.put("id", id);
                    if ("series".equals(type)) item.put("series_id", id);
                }
            }
            android.content.Intent intent = new android.content.Intent(
                    activity, Class.forName(activity.getPackageName() + ".DetailsActivity"));
            putMinimalDetailsExtras(intent, item, title);
            intent.putExtra("open_from_notification", "1");
            activity.startActivity(intent);
        } catch (Throwable error) {
            android.util.Log.w("CigramStage6", "open title details failed", error);
            Toast.makeText(activity, "تعذر فتح الصفحة", Toast.LENGTH_SHORT).show();
        }
    }

    private static void stage6ShowEpisodeNotification(
            Context context,
            JSONObject subscription,
            JSONObject series,
            JSONObject episode
    ) {
        if (context == null || series == null || episode == null) return;
        stage6CreateNotificationChannel(context);

        try {
            String title = jsonText(
                    series, "series_title", "title", "name", "title_ar", "title_en");
            if (title.length() == 0 && subscription != null) {
                title = jsonText(subscription, "title", "series_title");
            }
            if (title.length() == 0) title = "سيكرام";

            String season = jsonText(episode, "season", "season_number");
            String number = jsonText(episode, "episode", "episode_number", "ep", "number");
            if (season.length() == 0) season = "؟";
            if (number.length() == 0) number = "؟";

            String message = "تمت إضافة الحلقة " + number
                    + " من الموسم " + season
                    + " من " + title;

            android.content.Intent intent;
            try {
                Class<?> detailsClass = Class.forName(context.getPackageName() + ".DetailsActivity");
                intent = new android.content.Intent(context, detailsClass);
            } catch (Throwable error) {
                intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                if (intent == null) return;
            }

            intent.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
            stage6PutDetailsExtras(intent, series, subscription);
            intent.putExtra("open_from_notification", "1");

            String seriesKey = subscription == null
                    ? stage3SeriesKey(series)
                    : subscription.optString("series_key", stage3SeriesKey(series));
            int requestCode = 660600 + Math.abs(
                    (seriesKey + "|" + season + "|" + number).hashCode() % 100000
            );

            int pendingFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                pendingFlags |= android.app.PendingIntent.FLAG_IMMUTABLE;
            }
            android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                    context,
                    requestCode,
                    intent,
                    pendingFlags
            );

            int smallIcon = notificationSmallIcon(context);
            if (smallIcon == 0) smallIcon = android.R.drawable.stat_notify_more;

            android.app.Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= 26) {
                builder = new android.app.Notification.Builder(context, STAGE6_CHANNEL_ID);
            } else {
                builder = new android.app.Notification.Builder(context);
            }

            builder.setSmallIcon(smallIcon)
                    .setContentTitle("حلقة جديدة وصلت")
                    .setContentText(message)
                    .setStyle(new android.app.Notification.BigTextStyle().bigText(message))
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setColor(0xFFE21B14)
                    .setWhen(System.currentTimeMillis());

            if (Build.VERSION.SDK_INT < 26) {
                builder.setDefaults(android.app.Notification.DEFAULT_ALL);
                builder.setPriority(android.app.Notification.PRIORITY_HIGH);
            }

            android.app.NotificationManager manager =
                    (android.app.NotificationManager) context.getSystemService(
                            Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(requestCode, builder.build());

        } catch (Throwable error) {
            android.util.Log.w("CigramStage6", "Notification display failed", error);
        }
    }

    public static void sendStage6TestNotification(Context context) {
        if (context == null) return;
        stage6CreateNotificationChannel(context);
        if (!stage6HasNotificationPermission(context)) return;
        try {
            int smallIcon = notificationSmallIcon(context);
            if (smallIcon == 0) smallIcon = android.R.drawable.stat_notify_more;

            android.content.Intent launch =
                    context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            android.app.PendingIntent pendingIntent = null;
            if (launch != null) {
                int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 23) {
                    flags |= android.app.PendingIntent.FLAG_IMMUTABLE;
                }
                pendingIntent = android.app.PendingIntent.getActivity(
                        context, 660699, launch, flags);
            }

            android.app.Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= 26) {
                builder = new android.app.Notification.Builder(context, STAGE6_CHANNEL_ID);
            } else {
                builder = new android.app.Notification.Builder(context);
            }
            builder.setSmallIcon(smallIcon)
                    .setContentTitle("اختبار إشعارات سيكرام")
                    .setContentText("إذا ظهر هذا التنبيه فالإشعارات تعمل بشكل صحيح")
                    .setAutoCancel(true)
                    .setColor(0xFFE21B14)
                    .setWhen(System.currentTimeMillis());
            if (pendingIntent != null) builder.setContentIntent(pendingIntent);
            if (Build.VERSION.SDK_INT < 26) {
                builder.setDefaults(android.app.Notification.DEFAULT_ALL);
                builder.setPriority(android.app.Notification.PRIORITY_HIGH);
            }
            android.app.NotificationManager manager =
                    (android.app.NotificationManager) context.getSystemService(
                            Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(660699, builder.build());
        } catch (Throwable error) {
            android.util.Log.w("CigramStage6", "Test notification failed", error);
        }
    }

    private static void stage6PutDetailsExtras(
            android.content.Intent intent,
            JSONObject series,
            JSONObject subscription
    ) {
        if (intent == null || series == null) return;

        String fallbackTitle = jsonText(series, "series_title", "title", "name");
        if (fallbackTitle.length() == 0 && subscription != null) {
            fallbackTitle = jsonText(subscription, "title", "series_title");
        }
        putMinimalDetailsExtras(intent, series, fallbackTitle);

        String id = jsonText(series, "series_id", "id");
        if (id.length() == 0 && subscription != null) {
            id = jsonText(subscription, "series_id");
        }
        if (id.length() > 0) {
            intent.putExtra("id", id);
            intent.putExtra("series_id", id);
        }
        intent.putExtra("type", "series");
    }

    private static void setupStage3Account(final Activity activity, final int attempt) {
        if (!isAlive(activity)) return;

        hideStage3MyListSection(activity);

        LinearLayout host = stage3AccountHost(activity);
        if (host == null) {
            if (attempt < 30) {
                MAIN.postDelayed(new Runnable() {
                    @Override public void run() {
                        setupStage3Account(activity, attempt + 1);
                    }
                }, 32L);
            }
            return;
        }

        synchronized (ACCOUNT_STAGE3_SETUP) {
            ACCOUNT_STAGE3_SETUP.put(activity, Boolean.TRUE);
        }

        renderStage3Account(activity, host);
    }

    private static void hideStage3MyContentList(Activity activity) {
        if (activity == null) return;
        String[] ids = new String[]{
                "textview_my_list_title",
                "linear_my_list",
                "hscroll_my_list"
        };
        for (String id : ids) {
            View view = findByIdName(activity, id);
            if (view != null) view.setVisibility(View.GONE);
        }
    }

    private static void hideStage3MyListSection(Activity activity) {
        if (activity == null) return;
        View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (root == null) return;
        LinearLayout host = stage3AccountHost(activity);
        hideStage3MyListRecursive(root, host);
    }

    private static void hideStage3MyListRecursive(View view, LinearLayout host) {
        if (view == null) return;
        if (view instanceof TextView) {
            CharSequence raw = ((TextView) view).getText();
            String text = raw == null ? "" : raw.toString().trim();
            if ("قائمتي".equals(text) || text.contains("قسم قائمتي")) {
                View target = view;
                if (host != null) {
                    View direct = directChildUnder(host, view);
                    if (direct != null) target = direct;
                } else if (view.getParent() instanceof View) {
                    target = (View) view.getParent();
                }
                target.setVisibility(View.GONE);
                return;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                hideStage3MyListRecursive(group.getChildAt(i), host);
            }
        }
    }

    private static LinearLayout stage3AccountHost(Activity activity) {
        View preferred = findByIdName(activity, "linear_account_content");
        if (preferred instanceof LinearLayout) return (LinearLayout) preferred;

        View scroll = findByIdName(activity, "vscroll_account");
        if (scroll instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) scroll;
            if (group.getChildCount() > 0 && group.getChildAt(0) instanceof LinearLayout) {
                return (LinearLayout) group.getChildAt(0);
            }
        }

        View profile = findByIdName(activity, "linear_profile");
        View current = profile;
        while (current != null && current.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) current.getParent();
            if (parent instanceof LinearLayout && parent.getParent() == scroll) {
                return (LinearLayout) parent;
            }
            current = parent;
        }
        return null;
    }

    /**
     * v5.29: the account page no longer carries a "watch later" poster row. Watch later lives in
     * the stats strip («المشاهدة لاحقاً» next to «المفضلة») and opens the full list in «محتوياتي».
     * This only removes a row left by an older build and lays out the footer group.
     */
    private static void renderStage3Account(final Activity activity, final LinearLayout host) {
        if (!isAlive(activity) || host == null) return;
        View old = host.findViewWithTag(TAG_STAGE3_ACCOUNT_LIST);
        if (old != null && old.getParent() instanceof ViewGroup) {
            ((ViewGroup) old.getParent()).removeView(old);
        }
        try { CigramAccountExtras.arrange(activity); } catch (Throwable ignored) { }
    }

    private static final class PreparedCatalog {
        final String displayJson;
        final String searchIndexJson;

        PreparedCatalog(String displayJson, String searchIndexJson) {
            this.displayJson = displayJson == null ? "" : displayJson;
            this.searchIndexJson = searchIndexJson == null ? "[]" : searchIndexJson;
        }
    }

    public static void prepareAndStoreCatalogAsync(final Context context, final String raw) {
        if (context == null || raw == null || raw.trim().length() == 0) return;
        final Context app = context.getApplicationContext();
        IO.execute(new Runnable() {
            @Override public void run() {
                prepareAndStoreCatalog(app, raw);
            }
        });
    }

    public static String prepareAndStoreCatalog(Context context, String raw) {
        if (context == null || !isValidCatalog(raw)) return "";
        try {
            PreparedCatalog prepared = prepareCatalog(CigramTitles.normalizeCatalog(raw));
            long now = System.currentTimeMillis();
            String hash = catalogHash(prepared.displayJson);
            installCatalogInMemory(prepared.displayJson, prepared.searchIndexJson);
            catalogPrefs(context).edit()
                    .putString(KEY_JSON, prepared.displayJson)
                    .putString(KEY_JSON_HASH, hash)
                    .putLong(KEY_UPDATED, now)
                    .putString(KEY_SEARCH_JSON, prepared.searchIndexJson)
                    .putLong(KEY_SEARCH_UPDATED, now)
                    .apply();
            preloadCatalogImages(context.getApplicationContext(), prepared.searchIndexJson);
            return prepared.displayJson;
        } catch (Throwable error) {
            android.util.Log.w("CigramStage2", "Catalog store failed", error);
            return "";
        }
    }

    public static View createSectionMovieCard(Activity activity, JSONObject item) {
        if (activity == null || item == null) return null;
        try {
            return makeDynamicCard(activity, item);
        } catch (Throwable error) {
            try {
                return makeDynamicCardFallback(activity, item);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static PreparedCatalog prepareCatalog(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return new PreparedCatalog("", "[]");
        }

        try {
            Object root = new JSONTokener(raw).nextValue();
            sortCatalogSections(root, "", 0);
            String display = String.valueOf(root);
            JSONArray index = buildSearchIndex(root);
            return new PreparedCatalog(display, index.toString());
        } catch (Throwable error) {
            android.util.Log.w("CigramStage2", "Catalog preparation failed", error);
            return new PreparedCatalog(raw, "[]");
        }
    }

    private static void sortCatalogSections(Object node, String parentKey, int depth)
            throws Exception {
        if (node == null || node == JSONObject.NULL || depth > 12) return;

        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            if ("sections".equals(parentKey)) {
                reorderExplicitSections(array);
            } else if (isContentContainerKey(parentKey)) {
                reorderFlatContentBySection(array);
            }

            for (int i = 0; i < array.length(); i++) {
                sortCatalogSections(array.opt(i), parentKey, depth + 1);
            }
            return;
        }

        if (!(node instanceof JSONObject)) return;
        JSONObject object = (JSONObject) node;
        java.util.ArrayList<String> keys = new java.util.ArrayList<String>();
        java.util.Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) keys.add(iterator.next());

        for (String key : keys) {
            Object child = object.opt(key);
            if (child instanceof String) {
                String text = ((String) child).trim();
                if (text.startsWith("[") || text.startsWith("{")) {
                    try { child = new JSONTokener(text).nextValue(); }
                    catch (Throwable ignored) { child = null; }
                }
            }
            if (child instanceof JSONArray || child instanceof JSONObject) {
                sortCatalogSections(child, key, depth + 1);
            }
        }
    }

    private static boolean isContentContainerKey(String key) {
        return "movies".equals(key)
                || "series".equals(key)
                || "items".equals(key)
                || "data".equals(key)
                || "results".equals(key)
                || "content".equals(key)
                || "catalog".equals(key);
    }

    private static final class SectionBucket {
        final String key;
        final int firstIndex;
        final java.util.ArrayList<Object> values = new java.util.ArrayList<Object>();
        long rank;
        boolean explicitRank;

        SectionBucket(String key, int firstIndex) {
            this.key = key;
            this.firstIndex = firstIndex;
        }
    }

    private static void reorderFlatContentBySection(JSONArray array) throws Exception {
        if (array == null || array.length() < 2) return;

        java.util.LinkedHashMap<String, SectionBucket> groups =
                new java.util.LinkedHashMap<String, SectionBucket>();
        java.util.ArrayList<Object> loose = new java.util.ArrayList<Object>();
        int explicitGroupCount = 0;

        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (!(value instanceof JSONObject)) {
                loose.add(value);
                continue;
            }

            JSONObject item = (JSONObject) value;
            String sectionId = jsonText(item, "section_id", "sectionId");
            String sectionName = jsonText(item, "section_name", "sectionName");
            String sectionKey = sectionId.length() > 0
                    ? "id:" + normalizeSearch(sectionId)
                    : sectionName.length() > 0
                        ? "name:" + normalizeSearch(sectionName)
                        : "";

            if (sectionKey.length() == 0) {
                loose.add(value);
                continue;
            }

            SectionBucket bucket = groups.get(sectionKey);
            if (bucket == null) {
                bucket = new SectionBucket(sectionKey, i);
                groups.put(sectionKey, bucket);
                explicitGroupCount++;
            }
            bucket.values.add(value);

            long explicit = sectionExplicitRank(item);
            if (explicit > 0L) {
                bucket.explicitRank = true;
                bucket.rank = Math.max(bucket.rank, explicit);
            } else if (!bucket.explicitRank) {
                bucket.rank = Math.max(bucket.rank, contentRecency(item));
            }
        }

        if (explicitGroupCount < 2) return;

        java.util.ArrayList<SectionBucket> ordered =
                new java.util.ArrayList<SectionBucket>(groups.values());
        java.util.Collections.sort(ordered, new java.util.Comparator<SectionBucket>() {
            @Override public int compare(SectionBucket a, SectionBucket b) {
                if (a.rank != b.rank) return a.rank < b.rank ? 1 : -1;
                return a.firstIndex < b.firstIndex ? -1 : (a.firstIndex == b.firstIndex ? 0 : 1);
            }
        });

        JSONArray replacement = new JSONArray();
        for (SectionBucket bucket : ordered) {
            for (Object value : bucket.values) replacement.put(value);
        }
        for (Object value : loose) replacement.put(value);
        replaceArrayContents(array, replacement);
    }

    private static void reorderExplicitSections(JSONArray sections) throws Exception {
        if (sections == null || sections.length() < 2) return;

        final java.util.ArrayList<JSONObject> objects =
                new java.util.ArrayList<JSONObject>();
        final java.util.ArrayList<Object> extras = new java.util.ArrayList<Object>();

        for (int i = 0; i < sections.length(); i++) {
            Object value = sections.opt(i);
            if (value instanceof JSONObject) objects.add((JSONObject) value);
            else extras.add(value);
        }
        if (objects.size() < 2) return;

        java.util.Collections.sort(objects, new java.util.Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                long ra = explicitSectionObjectRank(a);
                long rb = explicitSectionObjectRank(b);
                if (ra == rb) return 0;
                return ra < rb ? 1 : -1;
            }
        });

        JSONArray replacement = new JSONArray();
        for (JSONObject object : objects) replacement.put(object);
        for (Object value : extras) replacement.put(value);
        replaceArrayContents(sections, replacement);
    }

    private static long explicitSectionObjectRank(JSONObject section) {
        long rank = sectionExplicitRank(section);
        if (rank > 0L) return rank;

        for (String key : new String[]{"items", "movies", "series", "content", "data"}) {
            JSONArray items = section.optJSONArray(key);
            if (items == null) continue;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) rank = Math.max(rank, contentRecency(item));
            }
        }
        return rank;
    }

    private static long sectionExplicitRank(JSONObject object) {
        if (object == null) return 0L;

        for (String key : new String[]{
                "section_created_at_ms", "section_added_at_ms", "section_updated_at_ms",
                "section_order", "section_number", "section_index"
        }) {
            long value = numericRank(object.opt(key));
            if (value > 0L) return value;
        }

        for (String key : new String[]{
                "section_created_at", "section_added_at", "section_updated_at"
        }) {
            long value = parseDateRank(jsonText(object, key));
            if (value > 0L) return value;
        }

        String sectionId = jsonText(object, "section_id", "id");
        long id = numericRank(sectionId);
        return id > 0L ? id : 0L;
    }

    /** v10.36: how new an item is (same rule as the home rows); SectionActivity orders its grid by it. */
    static long itemRecency(JSONObject item) {
        return contentRecency(item);
    }

    private static long contentRecency(JSONObject item) {
        if (item == null) return 0L;
        for (String key : new String[]{
                "created_at_ms", "added_at_ms", "published_at_ms", "updated_at_ms"
        }) {
            long value = numericRank(item.opt(key));
            if (value > 0L) return value;
        }
        for (String key : new String[]{
                "created_at", "added_at", "published_at", "updated_at"
        }) {
            long value = parseDateRank(jsonText(item, key));
            if (value > 0L) return value;
        }
        long id = numericRank(item.opt("id"));
        return id > 0L ? id : 0L;
    }

    private static long numericRank(Object value) {
        if (value == null || value == JSONObject.NULL) return 0L;
        if (value instanceof Number) return ((Number) value).longValue();
        String text = String.valueOf(value).trim();
        if (text.length() == 0) return 0L;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("([0-9]{1,18})").matcher(text);
        if (!matcher.find()) return 0L;
        try { return Long.parseLong(matcher.group(1)); }
        catch (Throwable ignored) { return 0L; }
    }

    private static long parseDateRank(String value) {
        if (value == null) return 0L;
        String text = value.trim();
        if (text.length() == 0) return 0L;
        long numeric = numericRank(text);
        if (text.matches("^[0-9]{10,18}$")) return numeric;

        String[] patterns = new String[]{
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd"
        };
        for (String pattern : patterns) {
            try {
                java.text.SimpleDateFormat format =
                        new java.text.SimpleDateFormat(pattern, java.util.Locale.US);
                format.setLenient(false);
                format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date date = format.parse(text);
                if (date != null) return date.getTime();
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "parseDateRank", ignored); }
        }
        return 0L;
    }

    private static void replaceArrayContents(JSONArray target, JSONArray source) throws Exception {

        for (int i = target.length() - 1; i >= 0; i--) target.remove(i);
        for (int i = 0; i < source.length(); i++) target.put(source.opt(i));
    }

    private static JSONArray buildSearchIndex(Object root) throws Exception {
        java.util.LinkedHashMap<String, JSONObject> unique =
                new java.util.LinkedHashMap<String, JSONObject>();
        java.util.LinkedHashMap<String, java.util.LinkedHashMap<String, JSONObject>> episodes =
                new java.util.LinkedHashMap<String, java.util.LinkedHashMap<String, JSONObject>>();

        collectSearchContent(root, "", null, unique, episodes, 0);

        java.util.ArrayList<JSONObject> resultItems =
                new java.util.ArrayList<JSONObject>(unique.values());
        java.util.Collections.sort(resultItems, new java.util.Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                long ra = contentRecency(a);
                long rb = contentRecency(b);
                if (ra == rb) return 0;
                return ra < rb ? 1 : -1;
            }
        });

        JSONArray result = new JSONArray();
        for (JSONObject item : resultItems) {
            String key = searchContentKey(item);
            java.util.LinkedHashMap<String, JSONObject> group = episodes.get(key);
            if (group != null && !group.isEmpty()) {
                java.util.ArrayList<JSONObject> list =
                        new java.util.ArrayList<JSONObject>(group.values());
                java.util.Collections.sort(list, new java.util.Comparator<JSONObject>() {
                    @Override public int compare(JSONObject a, JSONObject b) {
                        int sa = intText(a, "season", "season_number");
                        int sb = intText(b, "season", "season_number");
                        if (sa != sb) return sa < sb ? -1 : 1;
                        int ea = intText(a, "episode", "episode_number", "ep", "number");
                        int eb = intText(b, "episode", "episode_number", "ep", "number");
                        return ea < eb ? -1 : (ea == eb ? 0 : 1);
                    }
                });
                JSONArray episodeArray = new JSONArray();
                for (JSONObject episode : list) episodeArray.put(episode);
                item.put("series_episodes_json", episodeArray.toString());
            }

            item.put("_search_normalized", buildNormalizedSearchText(item));
            result.put(item);
        }
        return result;
    }

    private static void collectSearchContent(
            Object node,
            String inheritedType,
            JSONObject parentSection,
            java.util.LinkedHashMap<String, JSONObject> unique,
            java.util.LinkedHashMap<String, java.util.LinkedHashMap<String, JSONObject>> episodes,
            int depth
    ) throws Exception {
        if (node == null || node == JSONObject.NULL || depth > 14) return;

        if (node instanceof String) {
            String text = ((String) node).trim();
            if (text.startsWith("[") || text.startsWith("{")) {
                try {
                    collectSearchContent(new JSONTokener(text).nextValue(), inheritedType,
                            parentSection, unique, episodes, depth + 1);
                } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "compare", ignored); }
            }
            return;
        }

        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collectSearchContent(array.opt(i), inheritedType, parentSection,
                        unique, episodes, depth + 1);
            }
            return;
        }

        if (!(node instanceof JSONObject)) return;
        JSONObject object = (JSONObject) node;

        boolean hasContainer = false;
        for (String key : new String[]{
                "movies", "series", "items", "data", "results", "content", "sections", "catalog"
        }) {
            Object child = object.opt(key);
            if (child instanceof JSONArray || child instanceof JSONObject) {
                hasContainer = true;
                break;
            }
            if (child instanceof String) {
                String text = ((String) child).trim();
                if (text.startsWith("[") || text.startsWith("{")) {
                    hasContainer = true;
                    break;
                }
            }
        }

        boolean looksLikeContent = !hasContainer
                && jsonText(object, "title", "series_title", "name", "title_ar", "title_en").length() > 0
                && (object.has("id") || object.has("series_id") || object.has("poster")
                    || object.has("video") || object.has("link") || object.has("type")
                    || object.has("episodes") || object.has("seasons")
                    || object.has("series_episodes_json"));

        if (looksLikeContent) {
            JSONObject item = normalizeSearchItem(object, inheritedType, parentSection);
            String key = searchContentKey(item);
            if (key.length() == 0) return;

            JSONObject existing = unique.get(key);
            if (existing == null) {
                unique.put(key, item);
            } else {
                unique.put(key, mergeSearchItems(existing, item));
            }

            if ("series".equals(jsonText(item, "type"))) {
                java.util.LinkedHashMap<String, JSONObject> group = episodes.get(key);
                if (group == null) {
                    group = new java.util.LinkedHashMap<String, JSONObject>();
                    episodes.put(key, group);
                }
                collectEpisodesFromItem(item, group);
            }
            return;
        }

        JSONObject sectionContext = parentSection;
        if (object.has("section_id") || object.has("section_name")) {
            sectionContext = object;
        } else if (object.has("id") && object.has("name") && hasContainer) {
            sectionContext = object;
        }

        java.util.ArrayList<String> keys = new java.util.ArrayList<String>();
        java.util.Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) keys.add(iterator.next());

        for (String key : keys) {
            Object child = object.opt(key);
            String nextType = inheritedType;
            if ("movies".equals(key)) nextType = "movie";
            else if ("series".equals(key)) nextType = "series";

            if (child instanceof JSONArray || child instanceof JSONObject || child instanceof String) {
                collectSearchContent(child, nextType, sectionContext,
                        unique, episodes, depth + 1);
            }
        }
    }

    private static JSONObject normalizeSearchItem(
            JSONObject source,
            String inheritedType,
            JSONObject parentSection
    ) throws Exception {
        JSONObject item = new JSONObject(source.toString());
        String type = inferContentType(item, inheritedType);
        item.put("type", type);

        if (jsonText(item, "title").length() == 0) {
            item.put("title", jsonText(item,
                    "series_title", "title_ar", "title_en", "original_title", "name"));
        }
        if (jsonText(item, "poster").length() == 0) {
            item.put("poster", jsonText(item,
                    "series_poster", "season_poster", "image", "backdrop"));
        }
        if (jsonText(item, "video").length() == 0) {
            item.put("video", jsonText(item,
                    "link", "url", "video_url", "movie_url", "stream_url", "play_url"));
        }
        if (jsonText(item, "category").length() == 0) {
            item.put("category", jsonText(item, "genre", "genres"));
        }
        if (jsonText(item, "imdb").length() == 0) {
            item.put("imdb", jsonText(item, "rating", "imdb_rating", "vote_average", "score"));
        }

        if (parentSection != null) {
            if (jsonText(item, "section_id").length() == 0) {
                item.put("section_id", jsonText(parentSection, "section_id", "id"));
            }
            if (jsonText(item, "section_style").length() == 0) {
                String st = jsonText(parentSection, "section_style", "style", "layout", "display");
                if (st.length() > 0) item.put("section_style", st);
            }
            if (jsonText(item, "section_name").length() == 0) {
                item.put("section_name", jsonText(parentSection, "section_name", "name", "title"));
            }
        }
        return item;
    }

    private static String inferContentType(JSONObject item, String inheritedType) {
        String raw = normalizeSearch(jsonText(item, "type", "content_type", "media_type"));
        if ("series".equals(raw) || "tv".equals(raw) || "show".equals(raw)
                || raw.contains("مسلسل")) return "series";
        if ("movie".equals(raw) || "film".equals(raw) || raw.contains("فيلم")) return "movie";
        if (jsonText(item, "season", "episode", "series_title", "series_id").length() > 0
                || item.has("episodes") || item.has("seasons") || item.has("series_episodes_json")) {
            return "series";
        }
        return "series".equals(inheritedType) ? "series" : "movie";
    }

    private static String searchContentKey(JSONObject item) {
        if (item == null) return "";
        String type = inferContentType(item, "");
        if ("series".equals(type)) {
            String id = jsonText(item, "series_id");
            if (id.length() > 0) return "series|id|" + normalizeSearch(id);
            String title = normalizeSearch(jsonText(item,
                    "series_title", "title", "title_ar", "title_en", "original_title", "name"));
            String year = normalizeSearch(jsonText(item, "year"));
            return title.length() == 0 ? "" : "series|" + title + "|" + year;
        }

        String id = jsonText(item, "id", "movie_id", "item_id", "content_id");
        if (id.length() > 0) return "movie|id|" + normalizeSearch(id);
        String title = normalizeSearch(jsonText(item,
                "title", "title_ar", "title_en", "original_title", "name"));
        String year = normalizeSearch(jsonText(item, "year"));
        return title.length() == 0 ? "" : "movie|" + title + "|" + year;
    }

    /** A flat catalogue row that describes one episode (not the series header, not a nested series). */
    private static boolean isFlatEpisodeRow(JSONObject o) {
        return o != null
                && jsonText(o, "episode", "episode_number", "ep").length() > 0
                && !o.has("episodes") && !o.has("seasons");
    }

    private static JSONObject mergeSearchItems(JSONObject older, JSONObject newer) throws Exception {
        // The series header and its episodes share one key. The header must keep the identity
        // (title, poster, description...) even when the episodes are newer, otherwise the series
        // shows the title of its last episode.
        boolean olderEp = isFlatEpisodeRow(older);
        boolean newerEp = isFlatEpisodeRow(newer);
        if (olderEp != newerEp) {
            JSONObject header = olderEp ? newer : older;
            JSONObject ep = olderEp ? older : newer;
            JSONObject base = new JSONObject(header.toString());
            String[] borrow = {"category", "year", "imdb", "quality", "backdrop", "series_poster",
                    "poster", "description", "section_id", "section_name", "imdb_id", "tmdb", "slug"};
            for (String key : borrow) {
                Object current = base.opt(key);
                if ((current == null || current == JSONObject.NULL
                        || String.valueOf(current).trim().length() == 0) && ep.has(key)) {
                    base.put(key, ep.opt(key));
                }
            }
            // keep "recently added" ordering: the series is as fresh as its newest episode
            if (contentRecency(ep) > contentRecency(header)) {
                for (String key : new String[]{"created_at_ms", "created_at"}) {
                    if (ep.has(key)) base.put(key, ep.opt(key));
                }
            }
            return base;
        }
        boolean newerWins = contentRecency(newer) > contentRecency(older);
        JSONObject base = new JSONObject((newerWins ? newer : older).toString());
        JSONObject fallback = newerWins ? older : newer;

        java.util.Iterator<String> iterator = fallback.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            Object current = base.opt(key);
            if (current == null || current == JSONObject.NULL
                    || String.valueOf(current).trim().length() == 0) {
                base.put(key, fallback.opt(key));
            }
        }
        return base;
    }

    /**
     * Every season/episode of a series: the ones the player was opened with, completed with
     * the ones the catalogue knows for the same series (matched by id or by title). Returns the
     * original text unchanged when nothing can be added.
     */
    public static String completeSeriesEpisodes(Context context, String seriesId, String seriesTitle,
                                                String currentJson) {
        try {
            java.util.LinkedHashMap<String, JSONObject> map =
                    new java.util.LinkedHashMap<String, JSONObject>();
            if (currentJson != null && currentJson.trim().length() > 0) {
                JSONObject holder = new JSONObject();
                holder.put("series_episodes_json", currentJson);
                collectEpisodesFromItem(holder, map);
            }
            int before = map.size();

            String wantTitle = normalizeSearch(seriesTitle == null ? "" : seriesTitle);
            String wantId = seriesId == null ? "" : seriesId.trim();
            String index = context == null ? "" : catalogPrefs(context).getString(KEY_SEARCH_JSON, "");
            if (index != null && index.length() > 0 && (wantTitle.length() > 0 || wantId.length() > 0)) {
                JSONArray items = new JSONArray(index);
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null || !"series".equals(inferContentType(item, ""))) continue;
                    boolean sameId = wantId.length() > 0
                            && (wantId.equals(jsonText(item, "series_id")) || wantId.equals(jsonText(item, "id")));
                    boolean sameTitle = wantTitle.length() > 0
                            && wantTitle.equals(normalizeSearch(jsonText(item, "series_title", "title",
                            "title_ar", "title_en", "name")));
                    if (sameId || sameTitle) collectEpisodesFromItem(item, map);
                }
            }
            if (map.size() <= before) return currentJson;

            java.util.ArrayList<JSONObject> list = new java.util.ArrayList<JSONObject>(map.values());
            java.util.Collections.sort(list, new java.util.Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) {
                    int sa = intText(a, "season", "season_number");
                    int sb = intText(b, "season", "season_number");
                    if (sa != sb) return sa < sb ? -1 : 1;
                    int ea = intText(a, "episode", "episode_number", "ep", "number");
                    int eb = intText(b, "episode", "episode_number", "ep", "number");
                    return ea < eb ? -1 : (ea == eb ? 0 : 1);
                }
            });
            JSONArray out = new JSONArray();
            for (JSONObject episode : list) out.put(episode);
            return out.toString();
        } catch (Throwable error) {
            android.util.Log.w("CigramPlaylist", "completeSeriesEpisodes failed", error);
            return currentJson;
        }
    }

    private static void collectEpisodesFromItem(
            JSONObject item,
            java.util.LinkedHashMap<String, JSONObject> out
    ) {
        try {
            for (String field : new String[]{"series_episodes_json", "episodes", "seasons"}) {
                if (!item.has(field)) continue;
                collectEpisodeNode(item.opt(field), item, "", out, 0);
            }

            boolean directEpisode = jsonText(item, "episode", "episode_number", "ep").length() > 0;
            if (directEpisode) addEpisodeToMap(item, item, jsonText(item, "season"), out);
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "valueOf", ignored); }
    }

    private static void collectEpisodeNode(
            Object node,
            JSONObject parent,
            String inheritedSeason,
            java.util.LinkedHashMap<String, JSONObject> out,
            int depth
    ) throws Exception {
        if (node == null || node == JSONObject.NULL || depth > 12) return;
        if (node instanceof String) {
            String text = ((String) node).trim();
            if (text.startsWith("[") || text.startsWith("{")) {
                collectEpisodeNode(new JSONTokener(text).nextValue(), parent,
                        inheritedSeason, out, depth + 1);
            }
            return;
        }
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collectEpisodeNode(array.opt(i), parent, inheritedSeason, out, depth + 1);
            }
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject object = (JSONObject) node;
        String season = jsonText(object, "season", "season_number");
        if (season.length() == 0) season = inheritedSeason;

        if (object.has("seasons")) {
            collectEpisodeNode(object.opt("seasons"), parent, season, out, depth + 1);
            return;
        }
        if (object.has("episodes")) {
            if (season.length() == 0) season = jsonText(object, "number");
            collectEpisodeNode(object.opt("episodes"), parent, season, out, depth + 1);
            return;
        }
        addEpisodeToMap(object, parent, season, out);
    }

    private static void addEpisodeToMap(
            JSONObject source,
            JSONObject parent,
            String inheritedSeason,
            java.util.LinkedHashMap<String, JSONObject> out
    ) throws Exception {
        JSONObject episode = new JSONObject(source.toString());
        String season = jsonText(episode, "season", "season_number");
        if (season.length() == 0) season = inheritedSeason;
        if (season.length() == 0) season = "1";
        episode.put("season", season);

        String number = jsonText(episode, "episode", "episode_number", "ep", "number");
        if (number.length() > 0) episode.put("episode", number);
        if (jsonText(episode, "video").length() == 0) {
            episode.put("video", jsonText(episode, "link", "url", "video_url", "movie_url"));
        }
        if (jsonText(episode, "series_title").length() == 0) {
            episode.put("series_title", jsonText(parent, "series_title", "title", "name"));
        }
        if (jsonText(episode, "series_id").length() == 0) {
            episode.put("series_id", jsonText(parent, "series_id", "id"));
        }
        episode.put("type", "series");

        String key = season + "|" + number + "|" + jsonText(episode, "video", "link");
        if (!out.containsKey(key)) out.put(key, episode);
    }

    private static int intText(JSONObject object, String... keys) {
        String text = jsonText(object, keys).replaceAll("[^0-9-]", "");
        if (text.length() == 0 || "-".equals(text)) return 0;
        try { return Integer.parseInt(text); }
        catch (Throwable ignored) { return 0; }
    }

    private static String buildNormalizedSearchText(JSONObject item) {
        StringBuilder builder = new StringBuilder();
        for (String key : new String[]{
                "title", "series_title", "title_ar", "title_en", "original_title",
                "original_name", "name", "aliases", "alternative_titles", "alt_titles",
                "category", "genre", "genres", "year", "description", "overview", "plot"
        }) {
            String value = jsonText(item, key);
            if (value.length() > 0) builder.append(' ').append(value);
        }
        return normalizeSearch(builder.toString());
    }

    private static String jsonText(JSONObject object, String... keys) {
        if (object == null || keys == null) return "";
        for (String key : keys) {
            if (key == null) continue;
            Object value = object.opt(key);
            if (value != null && value != JSONObject.NULL) {
                String text = String.valueOf(value).trim();
                if (text.length() > 0 && !"null".equalsIgnoreCase(text)) return text;
            }
        }
        return "";
    }

    // Perf: String.replaceAll() compiles its regex on every call; this runs for every title / id of the
    // catalogue (search index, memory index, merges). Compiled once.
    private static final java.util.regex.Pattern RX_MARKS = java.util.regex.Pattern.compile("\\p{M}+");
    private static final java.util.regex.Pattern RX_NOT_WORD = java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final java.util.regex.Pattern RX_SPACES = java.util.regex.Pattern.compile("\\s+");

    private static String normalizeSearch(String value) {
        if (value == null) return "";
        String text = value;
        try {
            text = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD);
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "normalizeSearch", ignored); }
        String folded = RX_MARKS.matcher(text.trim().toLowerCase(java.util.Locale.ROOT)).replaceAll("");
        folded = folded
                .replace("ـ", "")
                .replace('أ', 'ا')
                .replace('إ', 'ا')
                .replace('آ', 'ا')
                .replace('ٱ', 'ا')
                .replace('ى', 'ي')
                .replace('ة', 'ه')
                .replace('ؤ', 'و')
                .replace('ئ', 'ي')
                .replace('٠', '0')
                .replace('١', '1')
                .replace('٢', '2')
                .replace('٣', '3')
                .replace('٤', '4')
                .replace('٥', '5')
                .replace('٦', '6')
                .replace('٧', '7')
                .replace('٨', '8')
                .replace('٩', '9');
        folded = RX_NOT_WORD.matcher(folded).replaceAll(" ").trim();
        return RX_SPACES.matcher(folded).replaceAll(" ");
    }

    public static void applyPlayerEdgeToEdge(final Activity activity) {
        if (activity == null) return;
        try {
            final android.view.Window window = activity.getWindow();
            if (window == null) return;

            window.addFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams attributes = window.getAttributes();
                if (Build.VERSION.SDK_INT >= 30) {
                    attributes.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                } else {
                    attributes.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }
                window.setAttributes(attributes);
            }

            if (Build.VERSION.SDK_INT >= 21) {
                window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
                window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            }

            final View decor = window.getDecorView();
            if (decor != null) {
                decor.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                decor.setKeepScreenOn(true);
            }

            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
                android.view.WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.hide(
                            android.view.WindowInsets.Type.statusBars()
                                    | android.view.WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController
                                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramPlayerInsets", "applyPlayerEdgeToEdge failed", error);
        }
    }

    private static PlayerMonitor ensurePlayerMonitor(Activity activity) {
        synchronized (PLAYER_MONITORS) {
            PlayerMonitor monitor = PLAYER_MONITORS.get(activity);
            if (monitor == null) {
                monitor = new PlayerMonitor(activity);
                PLAYER_MONITORS.put(activity, monitor);
            }
            return monitor;
        }
    }

    private static final class PlayerMonitor {
        private final Activity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private boolean running;
        private boolean destroyed;
        private boolean errorVisible;
        private boolean retrying;
        private boolean wasOnline;
        private int offlineBufferTicks;
        private long failedPosition;
        private Object player;
        private View playerView;

        private boolean introSkipped;
        private String introMediaKey = "";
        private boolean endTransitionScheduled;
        private Object speedAppliedPlayer;
        private float lastObservedSpeed = -1f;
        private View patchedSpeedRow;
        private String stage4EpisodesRaw = "";
        private java.util.ArrayList<Stage4Episode> stage4Episodes =
                new java.util.ArrayList<Stage4Episode>();

        private com.google.android.gms.ads.interstitial.InterstitialAd stage5Interstitial;
        private boolean stage5LoadInFlight;
        private long stage5LoadStartedAt;
        private long stage5LastAdClosedAt;
        private String stage5AdMediaKey = "";
        private long stage5LoadedAt;
        private long stage5NextLoadAt;
        private int stage5LoadFailures;
        private boolean stage5AdShowing;
        private long stage5AdShowingSince;
        private boolean stage5WasPlaying;
        private long stage5SavedPosition;
        private Stage5ChromeState stage5ChromeState;
        private String stage5MediaKey = "";
        private String stage5BreaksRaw = null;
        private java.util.ArrayList<Long> stage5ExplicitBreaks =
                new java.util.ArrayList<Long>();
        private final java.util.HashSet<String> stage5HandledBreaks =
                new java.util.HashSet<String>();
        private final java.util.ArrayList<Long> stage5ShownTimes =
                new java.util.ArrayList<Long>();

        PlayerMonitor(Activity activity) {
            this.activity = activity;
            this.wasOnline = isOnline(activity);
        }

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running || destroyed || !isAlive(activity)) return;

                try {
                    resolvePlayer();
                    configureControllerAutoHide();
                    CigramCastHome.hidePlayerCast(activity);
                    if (player != null) {
                        Object error = invokeNoArg(player, "getPlayerError");
                        int state = intValue(invokeNoArg(player, "getPlaybackState"), -1);
                        boolean online = isOnline(activity);
                        // how long every wait after a seek / stall really takes (written to cigram_embed.txt)
                        long nowMs = android.os.SystemClock.elapsedRealtime();
                        if (state == 2 && lastObservedState != 2) bufferStartedAt = nowMs;
                        else if (state == 3 && lastObservedState == 2 && bufferStartedAt > 0L) {
                            long took = nowMs - bufferStartedAt;
                            if (took > 700L) {
                                CigramEmbedResolver.log(activity, "buffering took " + took + " ms at "
                                        + longValue(invokeNoArg(player, "getCurrentPosition"), 0L) + " ms");
                            }
                            bufferStartedAt = 0L;
                        }
                        lastObservedState = state;
                        if (state == 3) prefetchNextEmbedEpisode();
                        // v10.79: data still arriving (slow server) = keep waiting; only a real stop is treated
                        boolean bytesFlowing = nowMs - CigramSegmentAccel.lastBytesAt < 4000L;
                        if (state == 2 && bufferStartedAt > 0L && nowMs - bufferStartedAt > 12000L && !bytesFlowing
                                && !embedRecovering && !errorVisible && error == null && isAlive(activity)) {
                            // still loading after 7 s (typically right after a seek on an embed stream): fetch a fresh link
                            if (tryEmbedRecovery("stalled " + (nowMs - bufferStartedAt) + " ms")) bufferStartedAt = nowMs;
                        }
                        if (state == 3 && error == null) {
                            CigramAnalytics.timerStop(activity, "player_startup_ms");
                            checkAudioTracks();
                        }

                        if (error != null && !errorVisible && !loggedError) logPlaybackFailure(error);
                        if (error == null) loggedError = false;
                        if (error != null && !errorVisible && !embedRecovering && tryEmbedRecovery(error)) {
                            // a resolved embed stream stopped (expired link / blocked segment): it is being fetched again
                        } else if (error != null && !errorVisible && !embedRecovering) {
                            CigramAnalytics.track(activity, "player_error", "type",
                                    error.getClass().getSimpleName());
                            failedPosition = Math.max(0L,
                                    longValue(invokeNoArg(player, "getCurrentPosition"), 0L));
                            pausePlayer();
                            showPlayerError(playerErrorMessage(error));
                        } else if (!online && state == 2 && !errorVisible) {
                            offlineBufferTicks++;
                            if (offlineBufferTicks >= 2) {
                                failedPosition = Math.max(0L,
                                        longValue(invokeNoArg(player, "getCurrentPosition"), 0L));
                                pausePlayer();
                                showPlayerError("انقطع الاتصال بالإنترنت");
                            }
                        } else {
                            offlineBufferTicks = 0;
                        }

                        if (errorVisible && online && !wasOnline && !retrying) {
                            retry();
                        }

                        if (retrying && errorVisible && error == null && state == 3) {
                            retrying = false;
                            removePlayerError();
                            setBufferingMode(1);
                        }

                        stage4ObserveAndPersistSpeed();
                        stage4PatchVisibleSpeedRow();
                        stage4UpdateIntroButton();
                        stage4HandleAutoNext(state);

                        stage5Tick();

                        wasOnline = online;
                    }
                } catch (Throwable error) {
                    android.util.Log.w("CigramStage1", "Player health check failed", error);
                }

                handler.postDelayed(this, 1000L);
            }
        };

        void start() {
            if (destroyed || !isAlive(activity)) return;
            running = true;
            applyPlayerEdgeToEdge(activity);
            try {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                View decor = activity.getWindow().getDecorView();
                if (decor != null) decor.setKeepScreenOn(true);
            } catch (Throwable error) {
                android.util.Log.w("CigramPlayer", "start keep-screen-on failed", error);
            }
            resolvePlayer();
            configureControllerAutoHide();
            CigramCastHome.hidePlayerCast(activity);
            stage4ApplySavedSpeedOnce();
            stage5EnsureAdsInitialized();
            // a series episode ends with an ad break: have it ready long before the end
            if (stage4IsSeries()) CigramEpisodeAds.preload(activity);
            if (playerView != null) {
                try {
                    playerView.setKeepScreenOn(true);
                } catch (Throwable error) {
                    android.util.Log.w("CigramPlayer", "playerView keep-screen-on failed", error);
                }
            }
            handler.removeCallbacks(poll);
            handler.post(poll);
        }

        private void configureControllerAutoHide() {
            if (playerView == null) return;
            try {
                Method timeout = findMethod(
                        playerView.getClass(), "setControllerShowTimeoutMs", 1);
                if (timeout != null) {
                    timeout.setAccessible(true);
                    timeout.invoke(playerView, Integer.valueOf(3000));
                }
                Method hideOnTouch = findMethod(
                        playerView.getClass(), "setControllerHideOnTouch", 1);
                if (hideOnTouch != null) {
                    hideOnTouch.setAccessible(true);
                    hideOnTouch.invoke(playerView, Boolean.TRUE);
                }
                Method autoShow = findMethod(
                        playerView.getClass(), "setControllerAutoShow", 1);
                if (autoShow != null) {
                    autoShow.setAccessible(true);
                    autoShow.invoke(playerView, Boolean.TRUE);
                }
            } catch (Throwable error) {
                android.util.Log.w("CigramPlayer", "configureControllerAutoHide failed", error);
            }
        }

        void stopForBackground() {
            running = false;
            handler.removeCallbacks(poll);
            boolean inPip = false;
            try {
                inPip = Build.VERSION.SDK_INT >= 26 && activity.isInPictureInPictureMode();
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stopForBackground", ignored); }
            if (!inPip) pausePlayer();
            try {
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                View decor = activity.getWindow().getDecorView();
                if (decor != null) decor.setKeepScreenOn(false);
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stopForBackground", ignored); }
        }

        void destroy() {
            destroyed = true;
            running = false;
            handler.removeCallbacksAndMessages(null);
            removePlayerError();
            removeTagged(activity, TAG_INTRO_SKIP);
            patchedSpeedRow = null;
            stage5Interstitial = null;
            stage5LoadInFlight = false;
            CigramAdManager.setLoading(PlayerMonitor.this, false);
            try {
                if (stage5LastAdClosedAt > 0L
                        && android.os.SystemClock.elapsedRealtime() - stage5LastAdClosedAt < 60000L) {
                    CigramAdManager.reportEx(activity, "exit_after_ad", "player_midroll", "",
                            "content_id", stage4Extra("id"), "series_id", stage4Extra("series_id"));
                }
            } catch (Throwable ignored) { }
            stage5AdShowing = false;
            CigramAdManager.releaseFullScreen(this);
            stage5RestoreAdChrome(stage5ChromeState);
            stage5ChromeState = null;
            stage5HandledBreaks.clear();
            stage5ShownTimes.clear();
            resolvePlayer();

            if (playerView != null) {
                try {
                    Method setPlayer = findMethod(playerView.getClass(), "setPlayer", 1);
                    if (setPlayer != null) {
                        setPlayer.setAccessible(true);
                        setPlayer.invoke(playerView, new Object[]{null});
                    }
                } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "destroy", ignored); }
            }

            if (player != null) {
                try { invokeNoArg(player, "pause"); } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "destroy", ignored); }
                try { invokeNoArg(player, "release"); } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "destroy", ignored); }
            }
            player = null;
            playerView = null;
        }

        private void resolvePlayer() {
            if (player != null) return;
            View candidate = findPlayerView(activity);
            if (candidate != null) {
                playerView = candidate;
                try {
                    Object value = invokeNoArg(candidate, "getPlayer");
                    if (value != null) player = value;
                } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "resolvePlayer", ignored); }
            }

            if (player == null) {
                Object reflected = findFieldObjectByTypeName(activity, "ExoPlayer");
                if (reflected != null) player = reflected;
            }
        }

        private void pausePlayer() {
            if (player == null) return;
            try { invokeNoArg(player, "pause"); } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "pausePlayer", ignored); }
            try {
                Method m = findMethod(player.getClass(), "setPlayWhenReady", 1);
                if (m != null) {
                    m.setAccessible(true);
                    m.invoke(player, Boolean.FALSE);
                }
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "pausePlayer", ignored); }
        }

        private String prefetchedFor = "";

        /** Series on an embed site: in the last 2.5 minutes the next episode's stream is looked up in the background. */
        private void prefetchNextEmbedEpisode() {
            try {
                if (!(player instanceof com.google.android.exoplayer2.ExoPlayer) || !stage4IsSeries()) return;
                com.google.android.exoplayer2.ExoPlayer exo = (com.google.android.exoplayer2.ExoPlayer) player;
                long duration = exo.getDuration();
                long position = exo.getCurrentPosition();
                if (duration <= 0L || position <= 0L || duration - position > 150000L) return;
                String key = stage4CurrentMediaUrl();
                if (key.equals(prefetchedFor)) return;
                prefetchedFor = key;
                java.util.ArrayList<Stage4Episode> episodes = stage4GetEpisodes();
                int index = stage4EpisodeIndex(episodes);
                if (index < 0 || index + 1 >= episodes.size()) return;
                String next = episodes.get(index + 1).video;
                if (next == null || !CigramEmbedResolver.isKnownHost(next)) return;
                android.util.Log.i("CigramEmbed", "resolving the next episode ahead: " + next);
                CigramEmbedResolver.resolveAsync(activity, next, new CigramEmbedResolver.Callback() {
                    @Override public void onResolved(String url) { }
                    @Override public void onFailed(String reason) { }
                });
            } catch (Throwable ignored) { }
        }

        private boolean loggedError;

        /**
         * Writes what is needed to understand a failure: the server's own answer (status, headers and the first
         * bytes of its body), the playlist type and the segments around the second that failed. Written to
         * cigram_embed.txt, and sent by the «إرسال التشخيص» button of the error panel.
         */
        private void logPlaybackFailure(Object error) {
            loggedError = true;
            try {
                StringBuilder sb = new StringBuilder("PLAYER ERROR: " + error);
                if (error instanceof com.google.android.exoplayer2.PlaybackException) {
                    Throwable c = ((com.google.android.exoplayer2.PlaybackException) error).getCause();
                    if (c instanceof com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException) {
                        com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException h =
                                (com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException) c;
                        sb.append("\n  failing address: ").append(h.dataSpec == null ? "?" : h.dataSpec.uri);
                        sb.append("\n  status: ").append(h.responseCode).append(' ').append(h.responseMessage);
                        sb.append("\n  answer headers: ").append(h.headerFields);
                        if (h.responseBody != null && h.responseBody.length > 0) {
                            sb.append("\n  answer body: ").append(new String(h.responseBody, 0,
                                    Math.min(400, h.responseBody.length), "UTF-8"));
                        }
                    } else if (c != null) {
                        sb.append("\n  cause: ").append(c);
                    }
                }
                if (player instanceof com.google.android.exoplayer2.ExoPlayer) {
                    com.google.android.exoplayer2.ExoPlayer exo = (com.google.android.exoplayer2.ExoPlayer) player;
                    long position = exo.getCurrentPosition();
                    sb.append("\n  position: ").append(position).append(" ms of ").append(exo.getDuration());
                    Object manifest = exo.getCurrentManifest();
                    if (manifest instanceof com.google.android.exoplayer2.source.hls.HlsManifest) {
                        com.google.android.exoplayer2.source.hls.playlist.HlsMediaPlaylist mp =
                                ((com.google.android.exoplayer2.source.hls.HlsManifest) manifest).mediaPlaylist;
                        sb.append("\n  playlist: ").append(mp.segments.size()).append(" segments, type=").append(mp.playlistType)
                                .append(", endTag=").append(mp.hasEndTag).append(", target=").append(mp.targetDurationUs / 1000L).append(" ms");
                        int at = 0;
                        for (int i = 0; i < mp.segments.size(); i++) {
                            if (mp.segments.get(i).relativeStartTimeUs <= position * 1000L) at = i; else break;
                        }
                        for (int i = Math.max(0, at - 1); i <= Math.min(mp.segments.size() - 1, at + 1); i++) {
                            sb.append("\n  segment ").append(i).append(i == at ? " (at the position)" : "").append(": ")
                                    .append(mp.segments.get(i).url);
                        }
                        if (mp.segments.size() > 1) sb.append("\n  first segment: ").append(mp.segments.get(0).url);
                    }
                }
                CigramEmbedResolver.logNow(sb.toString());
            } catch (Throwable ignored) { }
        }

        /** Sends the diagnostics file (its last part) and the error to any app: WhatsApp, Telegram, e-mail, copy. */
        private void shareDiagnostics(String message) {
            try {
                String report = "Cigram " + CigramUpdateChecker.installedName(activity) + "\n"
                        + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " (Android " + android.os.Build.VERSION.RELEASE + ")\n"
                        + "error: " + message.replace('\n', ' ') + "\n\n"
                        + CigramEmbedResolver.logTail(activity, 9000);
                android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(android.content.Intent.EXTRA_TEXT, report);
                activity.startActivity(android.content.Intent.createChooser(send, "إرسال التشخيص"));
            } catch (Throwable ignored) { }
        }

        private boolean embedRecovering;
        private int embedRecoveries;
        private int lastObservedState = -1;
        private long bufferStartedAt;
        private Object audioCheckedItem;

        private final java.util.HashSet<String> audioTried = new java.util.HashSet<String>();
        private int audioRecoveries;
        private String audioPage = "";

        /** Once per film: after the stream had time to show its tracks, checks that it really has sound. */
        private void checkAudioTracks() {
            try {
                if (!(player instanceof com.google.android.exoplayer2.ExoPlayer)) return;
                com.google.android.exoplayer2.ExoPlayer exo = (com.google.android.exoplayer2.ExoPlayer) player;
                final Object item = exo.getCurrentMediaItem();
                if (item == null || item == audioCheckedItem) return;
                audioCheckedItem = item;
                // a separate sound playlist can show up a moment after the picture: judge after 4.5 s, not at once
                handler.postDelayed(new Runnable() {
                    @Override public void run() { evaluateAudio(item); }
                }, 4500L);
            } catch (Throwable ignored) { }
        }

        /**
         * No sound track, or only one this phone cannot decode: try the other ways to play the same title that
         * the resolver kept (master playlist, separate sound joined to the picture, a file ...), one after the
         * other, and stay with the first that has working sound. Only when none is left the user is told why.
         */
        private void evaluateAudio(Object item) {
            try {
                if (destroyed || !isAlive(activity) || !(player instanceof com.google.android.exoplayer2.ExoPlayer)) return;
                final com.google.android.exoplayer2.ExoPlayer exo = (com.google.android.exoplayer2.ExoPlayer) player;
                if (exo.getCurrentMediaItem() != item) return;   // another title is playing now
                int groups = 0;
                boolean playable = false;
                String firstMime = "";
                for (com.google.android.exoplayer2.Tracks.Group g : exo.getCurrentTracks().getGroups()) {
                    if (g.getType() != com.google.android.exoplayer2.C.TRACK_TYPE_AUDIO) continue;
                    groups++;
                    if (g.isSupported()) playable = true;
                    if (firstMime.length() == 0 && g.length > 0) firstMime = String.valueOf(g.getTrackFormat(0).sampleMimeType);
                }
                if (groups > 0 && playable) return;   // sound is there
                String current = CigramStreams.cleanUrl(((com.google.android.exoplayer2.MediaItem) item).localConfiguration.uri.toString());
                String page = CigramEmbedResolver.pageFor(current);
                CigramEmbedResolver.logNow("audio check: " + groups + " sound track(s), playable=" + playable
                        + (firstMime.length() > 0 ? " (" + firstMime + ")" : "") + "  stream=" + current);
                if (page != null) {
                    if (!page.equals(audioPage)) { audioPage = page; audioTried.clear(); audioRecoveries = 0; }
                    audioTried.add(current);
                    String alt = audioRecoveries < 5 ? CigramEmbedResolver.nextAlternative(page, audioTried) : null;
                    if (alt != null) {
                        audioRecoveries++;
                        audioTried.add(CigramStreams.cleanUrl(alt));
                        CigramEmbedResolver.logNow("no sound -> trying another way to play it (" + audioRecoveries + "): "
                                + CigramStreams.cleanUrl(alt));
                        long position = Math.max(0L, exo.getCurrentPosition());
                        exo.setMediaItem(CigramStreams.apply(new com.google.android.exoplayer2.MediaItem.Builder(), alt).build(), position);
                        exo.prepare();
                        exo.setPlayWhenReady(true);
                        audioCheckedItem = null;   // check the new stream the same way
                        return;
                    }
                }
                String message = groups == 0 ? "هذا المصدر لا يحتوي مسار صوت"
                        : "صوت هذا الفيديو بصيغة لا يدعمها جهازك (" + firstMime + ")";
                CigramEmbedResolver.logNow("audio: " + message + " (nothing left to try)");
                android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) { }
        }

        /**
         * A stream that came from an embed page (vidbolt, ...) carries a short-lived token. When it fails
         * (typically after a seek or a while into the film) the page is resolved again and playback
         * continues from the same second. Two attempts; then the normal error panel is shown.
         */
        private boolean tryEmbedRecovery(Object error) {
            try {
                if (embedRecoveries >= 2 || !(player instanceof com.google.android.exoplayer2.ExoPlayer)) return false;
                final com.google.android.exoplayer2.ExoPlayer exo = (com.google.android.exoplayer2.ExoPlayer) player;
                com.google.android.exoplayer2.MediaItem item = exo.getCurrentMediaItem();
                if (item == null || item.localConfiguration == null) return false;
                final String page = CigramEmbedResolver.pageFor(item.localConfiguration.uri.toString());
                if (page == null) return false;
                android.util.Log.w("CigramEmbed", "stream failed, resolving again: " + error);
                embedRecoveries++;
                embedRecovering = true;
                final long position = Math.max(0L, exo.getCurrentPosition());
                setBufferingMode(1);
                CigramEmbedResolver.invalidate(page);
                CigramEmbedResolver.resolveAsync(activity, page, new CigramEmbedResolver.Callback() {
                    @Override public void onResolved(String url) {
                        try {
                            if (!isAlive(activity) || destroyed) return;
                            exo.setMediaItem(CigramStreams.apply(
                                    new com.google.android.exoplayer2.MediaItem.Builder(), url).build(), position);
                            exo.prepare();
                            exo.setPlayWhenReady(true);
                        } catch (Throwable t) {
                            android.util.Log.w("CigramEmbed", "resume failed", t);
                        }
                        embedRecovering = false;
                    }
                    @Override public void onFailed(String reason) { embedRecovering = false; }
                });
                return true;
            } catch (Throwable t) {
                embedRecovering = false;
                return false;
            }
        }

        private void retry() {
            if (retrying || player == null || !isOnline(activity)) return;
            retrying = true;
            setBufferingMode(1);

            try {
                Method seek = findCompatibleLongMethod(player.getClass(), "seekTo");
                if (seek != null) {
                    seek.setAccessible(true);
                    if (seek.getParameterTypes().length == 1) {
                        seek.invoke(player, Long.valueOf(Math.max(0L, failedPosition)));
                    }
                }
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "retry", ignored); }

            try { invokeNoArg(player, "prepare"); } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "retry", ignored); }

            try {
                Method playWhenReady = findMethod(player.getClass(), "setPlayWhenReady", 1);
                if (playWhenReady != null) {
                    playWhenReady.setAccessible(true);
                    playWhenReady.invoke(player, Boolean.TRUE);
                }
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "retry", ignored); }

            try { invokeNoArg(player, "play"); } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "retry", ignored); }

            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!errorVisible) retrying = false;
                    else if (retrying) retrying = false;
                }
            }, 5000L);
        }

        private static final class Stage4Episode {
            String season = "";
            String episode = "";
            String title = "";
            String video = "";
            String subtitle = "";
            String skip = "";
            long introStartMs = -1L;
            long introEndMs = -1L;
            JSONObject raw;
        }

        private String stage4Extra(String... keys) {
            if (activity == null || activity.getIntent() == null) return "";
            for (String key : keys) {
                try {
                    String value = activity.getIntent().getStringExtra(key);
                    if (value != null && value.trim().length() > 0) return value.trim();
                } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stage4Extra", ignored); }
            }
            return "";
        }

        private boolean stage4IsSeries() {
            String type = stage4Extra("type", "content_type").toLowerCase(java.util.Locale.US);
            return "series".equals(type)
                    || "episode".equals(type)
                    || stage4Extra("season").length() > 0
                    || stage4Extra("episode").length() > 0
                    || stage4Extra("series_episodes_json", "episodes", "series_episodes").length() > 0;
        }

        private void stage4ApplySavedSpeedOnce() {
            if (player == null || speedAppliedPlayer == player) return;
            float speed = 1f;
            try {
                speed = activity.getSharedPreferences(PREF_PLAYER_STAGE4, Context.MODE_PRIVATE)
                        .getFloat(KEY_PLAYER_SPEED, 1f);
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "getSharedPreferences", ignored); }
            if (speed < 0.25f || speed > 4f) speed = 1f;
            stage4SetSpeed(speed, false);
            speedAppliedPlayer = player;
            lastObservedSpeed = speed;
        }

        private float stage4CurrentSpeed() {
            if (player == null) return 1f;
            try {
                Object parameters = invokeNoArg(player, "getPlaybackParameters");
                Object speed = getFieldValue(parameters, "speed");
                if (speed instanceof Number) return ((Number) speed).floatValue();
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stage4CurrentSpeed", ignored); }
            return lastObservedSpeed > 0f ? lastObservedSpeed : 1f;
        }

        private void stage4SetSpeed(float speed, boolean save) {
            if (player == null) return;
            try {
                Method method = findMethod(player.getClass(), "setPlaybackSpeed", 1);
                if (method != null) {
                    method.setAccessible(true);
                    method.invoke(player, Float.valueOf(speed));
                    lastObservedSpeed = speed;
                }
            } catch (Throwable error) {
                android.util.Log.w("CigramStage4", "Unable to set playback speed", error);
            }
            if (save) {
                try {
                    activity.getSharedPreferences(PREF_PLAYER_STAGE4, Context.MODE_PRIVATE)
                            .edit()
                            .putFloat(KEY_PLAYER_SPEED, speed)
                            .apply();
                } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "putFloat", ignored); }
            }
        }

        private void stage4ObserveAndPersistSpeed() {
            stage4ApplySavedSpeedOnce();
            float current = stage4CurrentSpeed();
            if (current < 0.25f || current > 4f) return;
            if (lastObservedSpeed < 0f || Math.abs(current - lastObservedSpeed) > 0.001f) {
                lastObservedSpeed = current;
                try {
                    activity.getSharedPreferences(PREF_PLAYER_STAGE4, Context.MODE_PRIVATE)
                            .edit()
                            .putFloat(KEY_PLAYER_SPEED, current)
                            .apply();
                } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "putFloat", ignored); }
            }
        }

        private String stage4SpeedLabel(float value) {
            if (Math.abs(value - 0.5f) < 0.001f) return "0.5x";
            if (Math.abs(value - 0.75f) < 0.001f) return "0.75x";
            if (Math.abs(value - 1f) < 0.001f) return "1x";
            if (Math.abs(value - 1.25f) < 0.001f) return "1.25x";
            if (Math.abs(value - 1.5f) < 0.001f) return "1.5x";
            if (Math.abs(value - 2f) < 0.001f) return "2x";
            return String.valueOf(value) + "x";
        }

        private void stage4PatchVisibleSpeedRow() {
            if (!isAlive(activity)) return;
            TextView row = null;

            try {
                View decor = activity.getWindow().getDecorView();
                row = stage4FindTextView(decor, "سرعة العرض");
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stage4PatchVisibleSpeedRow", ignored); }

            if (row == null) {
                try {
                    Class<?> globalClass = Class.forName("android.view.WindowManagerGlobal");
                    Method getInstance = globalClass.getDeclaredMethod("getInstance");
                    getInstance.setAccessible(true);
                    Object global = getInstance.invoke(null);
                    Field viewsField = globalClass.getDeclaredField("mViews");
                    viewsField.setAccessible(true);
                    Object views = viewsField.get(global);
                    if (views instanceof java.util.List) {
                        java.util.List<?> list = (java.util.List<?>) views;
                        for (Object object : list) {
                            if (object instanceof View) {
                                TextView candidate = stage4FindTextView((View) object, "سرعة العرض");
                                if (candidate != null) {
                                    row = candidate;
                                    break;
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stage4PatchVisibleSpeedRow", ignored); }
            }

            if (row == null) return;
            final TextView speedRow = row;
            speedRow.setText("سرعة العرض: " + stage4SpeedLabel(stage4CurrentSpeed()));

            if (patchedSpeedRow == speedRow) return;
            patchedSpeedRow = speedRow;

            speedRow.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    final float[] values = new float[]{0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
                    android.widget.PopupMenu menu = new android.widget.PopupMenu(activity, speedRow);
                    for (float value : values) {
                        menu.getMenu().add(stage4SpeedLabel(value));
                    }
                    menu.setOnMenuItemClickListener(
                            new android.widget.PopupMenu.OnMenuItemClickListener() {
                                @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                                    String selected = String.valueOf(item.getTitle()).replace("x", "");
                                    float speed = 1f;
                                    try { speed = Float.parseFloat(selected); }
                                    catch (Throwable ignored) { android.util.Log.w("CigramInjected", "onMenuItemClick", ignored); }
                                    stage4SetSpeed(speed, true);
                                    speedRow.setText("سرعة العرض: " + stage4SpeedLabel(speed));
                                    return true;
                                }
                            }
                    );
                    menu.show();
                }
            });
        }

        private TextView stage4FindTextView(View root, String prefix) {
            if (root == null) return null;
            if (root instanceof TextView) {
                CharSequence value = ((TextView) root).getText();
                if (value != null && String.valueOf(value).trim().startsWith(prefix)) {
                    return (TextView) root;
                }
            }
            if (root instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) root;
                for (int i = 0; i < group.getChildCount(); i++) {
                    TextView found = stage4FindTextView(group.getChildAt(i), prefix);
                    if (found != null) return found;
                }
            }
            return null;
        }

        private long stage4ParseTimeMs(Object value, boolean alreadyMs) {
            if (value == null || value == JSONObject.NULL) return -1L;
            String raw = String.valueOf(value).trim();
            if (raw.length() == 0) return -1L;
            try {
                if (raw.indexOf(':') >= 0) {
                    String[] parts = raw.split(":");
                    double seconds = 0d;
                    for (String part : parts) seconds = seconds * 60d + Double.parseDouble(part.trim());
                    return Math.max(0L, Math.round(seconds * 1000d));
                }
                double number = Double.parseDouble(raw);
                return Math.max(0L, Math.round(alreadyMs ? number : number * 1000d));
            } catch (Throwable ignored) {
                return -1L;
            }
        }

        private long stage4JsonIntro(JSONObject object, String secondsKey, String millisKey) {
            if (object == null) return -1L;
            try {
                if (object.has(millisKey)) {
                    long value = stage4ParseTimeMs(object.opt(millisKey), true);
                    if (value >= 0L) return value;
                }
                if (object.has(secondsKey)) {
                    return stage4ParseTimeMs(object.opt(secondsKey), false);
                }
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stage4JsonIntro", ignored); }
            return -1L;
        }

        private long stage4IntentIntro(String secondsKey, String millisKey, long fallback) {
            String ms = stage4Extra(millisKey);
            if (ms.length() > 0) {
                long value = stage4ParseTimeMs(ms, true);
                if (value >= 0L) return value;
            }
            String seconds = stage4Extra(secondsKey);
            if (seconds.length() > 0) {
                long value = stage4ParseTimeMs(seconds, false);
                if (value >= 0L) return value;
            }
            return fallback;
        }

        private void stage4ParseEpisodeArray(
                JSONArray array,
                String defaultSeason,
                java.util.ArrayList<Stage4Episode> output
        ) {
            if (array == null) return;
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                JSONArray nested = object.optJSONArray("episodes");
                if (nested != null) {
                    String season = object.optString("season", object.optString("number", defaultSeason));
                    stage4ParseEpisodeArray(nested, season, output);
                    continue;
                }

                Stage4Episode episode = new Stage4Episode();
                episode.raw = object;
                episode.season = object.optString("season", defaultSeason).trim();
                episode.episode = object.optString("episode", object.optString("number", "")).trim();
                episode.title = object.optString("title", object.optString("name", "")).trim();
                episode.video = object.optString("video", "").trim();
                if (episode.video.length() == 0) episode.video = object.optString("link", "").trim();
                if (episode.video.length() == 0) episode.video = object.optString("video_url", "").trim();
                episode.subtitle = object.optString("subtitle", object.optString("subtitle_url", "")).trim();
                episode.skip = object.optString("skip_segments", "").trim();
                episode.introStartMs = stage4JsonIntro(object, "intro_start", "intro_start_ms");
                episode.introEndMs = stage4JsonIntro(object, "intro_end", "intro_end_ms");
                if (episode.video.length() > 0 || episode.episode.length() > 0) output.add(episode);
            }
        }

        private int stage4NaturalNumber(String value) {
            if (value == null) return Integer.MAX_VALUE;
            try {
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(value);
                if (matcher.find()) return Integer.parseInt(matcher.group(1));
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stage4NaturalNumber", ignored); }
            return Integer.MAX_VALUE;
        }

        private java.util.ArrayList<Stage4Episode> stage4GetEpisodes() {
            String raw = stage4Extra("series_episodes_json", "episodes", "series_episodes");
            if (raw.equals(stage4EpisodesRaw)) return stage4Episodes;

            stage4EpisodesRaw = raw;
            final java.util.ArrayList<Stage4Episode> result = new java.util.ArrayList<Stage4Episode>();
            if (raw.length() > 0) {
                try {
                    Object root = new JSONTokener(raw).nextValue();
                    if (root instanceof JSONArray) {
                        stage4ParseEpisodeArray((JSONArray) root, "", result);
                    } else if (root instanceof JSONObject) {
                        JSONObject object = (JSONObject) root;
                        JSONArray episodes = object.optJSONArray("episodes");
                        if (episodes != null) stage4ParseEpisodeArray(episodes, object.optString("season", ""), result);
                        JSONArray seasons = object.optJSONArray("seasons");
                        if (seasons != null) stage4ParseEpisodeArray(seasons, "", result);
                    }
                } catch (Throwable error) {
                    android.util.Log.w("CigramStage4", "Episode JSON parse failed", error);
                }
            }

            java.util.Collections.sort(result, new java.util.Comparator<Stage4Episode>() {
                @Override public int compare(Stage4Episode left, Stage4Episode right) {
                    int ls = stage4NaturalNumber(left.season);
                    int rs = stage4NaturalNumber(right.season);
                    if (ls != rs) return ls < rs ? -1 : 1;
                    int le = stage4NaturalNumber(left.episode);
                    int re = stage4NaturalNumber(right.episode);
                    if (le != re) return le < re ? -1 : 1;
                    return 0;
                }
            });
            stage4Episodes = result;
            return stage4Episodes;
        }

        private String stage4CurrentMediaUrl() {
            if (player != null) {
                try {
                    Object mediaItem = invokeNoArg(player, "getCurrentMediaItem");
                    if (mediaItem != null) {
                        Object config = getFieldValue(mediaItem, "localConfiguration");
                        if (config == null) config = getFieldValue(mediaItem, "playbackProperties");
                        Object uri = getFieldValue(config, "uri");
                        if (uri != null) {
                            String stream = String.valueOf(uri).trim();
                            // a stream resolved from an embed page (vidbolt ...) is known by the episode's own link:
                            // the real address changes with every fresh token, the episode does not
                            String page = CigramEmbedResolver.pageFor(stream);
                            return page != null ? page : stream;
                        }
                    }
                } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stage4CurrentMediaUrl", ignored); }
            }
            return stage4Extra("video", "movie_url", "link", "video_url");
        }

        private Stage4Episode stage4CurrentEpisode() {
            java.util.ArrayList<Stage4Episode> episodes = stage4GetEpisodes();
            if (episodes.isEmpty()) return null;
            String media = stage4CurrentMediaUrl();
            if (media.length() > 0) {
                for (Stage4Episode episode : episodes) {
                    if (media.equals(episode.video)) return episode;
                }
            }
            String season = stage4Extra("season");
            String number = stage4Extra("episode");
            for (Stage4Episode episode : episodes) {
                if (episode.season.equals(season) && episode.episode.equals(number)) return episode;
            }
            return null;
        }

        private void stage4UpdateIntroButton() {

            if (player == null) {
                removeTagged(activity, TAG_INTRO_SKIP);
                return;
            }

            String mediaKey = stage4CurrentMediaUrl();
            if (!mediaKey.equals(introMediaKey)) {
                introMediaKey = mediaKey;
                introSkipped = false;
                removeTagged(activity, TAG_INTRO_SKIP);
            }

            long start = stage4IntentIntro(
                    "intro_start", "intro_start_ms", CIGRAM_DEFAULT_INTRO_START_MS);
            long end = stage4IntentIntro(
                    "intro_end", "intro_end_ms", CIGRAM_DEFAULT_INTRO_END_MS);

            Stage4Episode current = stage4CurrentEpisode();
            if (current != null) {
                if (current.introStartMs >= 0L) start = current.introStartMs;
                if (current.introEndMs >= 0L) end = current.introEndMs;
            }

            if (end <= start) end = Math.max(start + 1000L, CIGRAM_DEFAULT_INTRO_END_MS);
            final long introEnd = end;
            long position = Math.max(0L, longValue(invokeNoArg(player, "getCurrentPosition"), 0L));
            boolean shouldShow = !introSkipped && position >= start && position < end;

            FrameLayout root = contentRoot(activity);
            if (root == null) return;
            View old = root.findViewWithTag(TAG_INTRO_SKIP);

            if (!shouldShow) {
                if (old != null) old.setVisibility(View.GONE);
                return;
            }

            final TextView button;
            if (old instanceof TextView) {
                button = (TextView) old;
                button.setVisibility(View.VISIBLE);
            } else {
                button = text(activity, "تخطي المقدمة", 15f, Color.BLACK, sima(activity));
                button.setTag(TAG_INTRO_SKIP);
                button.setGravity(Gravity.CENTER);
                button.setClickable(true);
                button.setFocusable(true);
                button.setPadding(dp(activity, 22f), 0, dp(activity, 22f), 0);
                GradientDrawable background = new GradientDrawable();
                background.setColor(Color.WHITE);
                background.setCornerRadius(dp(activity, 999f));
                button.setBackground(background);
                button.setElevation(dp(activity, 12f));

                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(activity, 46f),
                        Gravity.BOTTOM | Gravity.RIGHT
                );
                lp.rightMargin = dp(activity, 18f);
                lp.bottomMargin = dp(activity, 76f);
                root.addView(button, lp);
            }

            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    try {
                        Method seek = findCompatibleLongMethod(player.getClass(), "seekTo");
                        if (seek != null) {
                            seek.setAccessible(true);
                            seek.invoke(player, Long.valueOf(introEnd));
                        }
                    } catch (Throwable error) {
                        android.util.Log.w("CigramStage4", "Intro seek failed", error);
                    }
                    introSkipped = true;
                    button.setVisibility(View.GONE);
                }
            });
        }

        private int stage4EpisodeIndex(java.util.ArrayList<Stage4Episode> episodes) {
            if (episodes == null || episodes.isEmpty()) return -1;
            String media = stage4CurrentMediaUrl();
            if (media.length() > 0) {
                for (int i = 0; i < episodes.size(); i++) {
                    if (media.equals(episodes.get(i).video)) return i;
                }
            }
            String season = stage4Extra("season");
            String number = stage4Extra("episode");
            for (int i = 0; i < episodes.size(); i++) {
                Stage4Episode episode = episodes.get(i);
                if (episode.season.equals(season) && episode.episode.equals(number)) return i;
            }
            return -1;
        }

        private void stage4HandleAutoNext(int state) {
            if (!stage4IsSeries()) return;
            // «بدء الحلقة التالية تلقائياً» switched off in the settings sheet: stay on the end
            if (!CigramPlayerSheet.autoNextEnabled(activity)) return;
            if (state != 4) {
                endTransitionScheduled = false;
                return;
            }
            if (endTransitionScheduled) return;
            endTransitionScheduled = true;

            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (destroyed || !isAlive(activity)) return;
                    resolvePlayer();
                    int currentState = intValue(invokeNoArg(player, "getPlaybackState"), -1);
                    if (currentState != 4) {
                        endTransitionScheduled = false;
                        return;
                    }

                    java.util.ArrayList<Stage4Episode> episodes = stage4GetEpisodes();
                    int index = stage4EpisodeIndex(episodes);
                    if (index >= 0 && index + 1 < episodes.size()) {
                        Stage4Episode next = episodes.get(index + 1);
                        if (next != null && next.video.length() > 0) {
                            stage4LaunchEpisodeAfterAd(next);
                            return;
                        }
                    }

                    try { activity.finish(); } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "run", ignored); }
                }
            }, 900L);
        }

        /**
         * v10.35: automatic next episode -> rewarded unlock (intro screen with a countdown and
         * "لا شكراً", then the ad). An episode already unlocked in this app session opens at once.
         * No ad available / offline => the next episode opens anyway.
         */
        private void stage4LaunchEpisodeAfterAd(final Stage4Episode next) {
            String key = next == null || next.video.length() == 0 ? "" : "v|" + next.video;
            CigramRewardUnlock.unlock(activity, key, true, null, new Runnable() {
                @Override public void run() {
                    if (destroyed) return;
                    stage4LaunchEpisode(next);
                }
            });
        }

        private void stage4LaunchEpisode(Stage4Episode next) {
            if (next == null || next.video.length() == 0 || !isAlive(activity)) return;
            try {
                android.content.Intent intent = new android.content.Intent(activity, activity.getClass());
                Bundle extras = activity.getIntent() == null ? null : activity.getIntent().getExtras();
                if (extras != null) intent.putExtras(new Bundle(extras));

                intent.putExtra("type", "series");
                intent.putExtra("video", next.video);
                intent.putExtra("link", next.video);
                intent.putExtra("video_url", next.video);
                intent.putExtra("subtitle", next.subtitle);
                intent.putExtra("subtitle_url", next.subtitle);
                intent.putExtra("season", next.season);
                intent.putExtra("episode", next.episode);
                if (next.title.length() > 0) intent.putExtra("title", next.title);
                intent.putExtra("skip_segments", next.skip);

                if (next.introStartMs >= 0L) {
                    intent.putExtra("intro_start_ms", String.valueOf(next.introStartMs));
                    intent.putExtra("intro_start", String.valueOf(next.introStartMs / 1000d));
                } else {
                    intent.removeExtra("intro_start_ms");
                    intent.removeExtra("intro_start");
                }
                if (next.introEndMs >= 0L) {
                    intent.putExtra("intro_end_ms", String.valueOf(next.introEndMs));
                    intent.putExtra("intro_end", String.valueOf(next.introEndMs / 1000d));
                } else {
                    intent.removeExtra("intro_end_ms");
                    intent.removeExtra("intro_end");
                }

                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION);
                activity.startActivity(intent);
                try { activity.overridePendingTransition(0, 0); } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stage4LaunchEpisode", ignored); }
                activity.finish();
            } catch (Throwable error) {
                android.util.Log.w("CigramStage4", "Unable to launch next episode", error);
                endTransitionScheduled = false;
            }
        }

        private String stage5AdUnitId() {
            return CigramAdManager.playerInterstitialUnitId();
        }

        private void stage5EnsureAdsInitialized() {
            CigramAdManager.initialize(activity.getApplicationContext());
        }

        private void stage5LoadAd() {
            long cgNow = android.os.SystemClock.elapsedRealtime();
            // watchdog: a load that never answers must not block ads for the whole session
            if (stage5LoadInFlight && cgNow - stage5LoadStartedAt > 20000L) stage5LoadInFlight = false;
            // loaded interstitials expire (~1 h); drop stale ones and reload
            if (stage5Interstitial != null && cgNow - stage5LoadedAt > 45L * 60L * 1000L) {
                stage5Interstitial = null;
            }
            if (stage5AdShowing || stage5LoadInFlight || stage5Interstitial != null) return;
            // back-off after failures so a no-fill never hammers AdMob every tick
            if (cgNow < stage5NextLoadAt) return;
            stage5EnsureAdsInitialized();
            if (!CigramAdManager.isInitialized() || !isAlive(activity) || !isOnline(activity)) return;
            // never preload an ad the central policy would refuse to show (saves requests)
            if (CigramAdManager.playerAdBlockReason(activity, this, Long.MAX_VALUE / 4L,
                    errorVisible, isAlive(activity) && !destroyed).length() > 0) return;

            stage5LoadInFlight = true;
            CigramAdManager.setLoading(this, true);
            stage5LoadStartedAt = cgNow;
            stage5Report("ad_request", "");
            try {
                com.google.android.gms.ads.AdRequest request =
                        new com.google.android.gms.ads.AdRequest.Builder().build();

                com.google.android.gms.ads.interstitial.InterstitialAd.load(
                        activity, // Activity context: required by some mediation adapters
                        stage5AdUnitId(),
                        request,
                        new com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback() {
                            @Override public void onAdLoaded(
                                    com.google.android.gms.ads.interstitial.InterstitialAd ad) {
                                stage5LoadInFlight = false;
                                CigramAdManager.setLoading(PlayerMonitor.this, false);
                                stage5Interstitial = ad;
                                stage5LoadedAt = android.os.SystemClock.elapsedRealtime();
                                stage5LoadFailures = 0;
                                stage5NextLoadAt = 0L;
                                stage5Report("ad_loaded", "");
                                if (stage5AdShowing && stage5ChromeState != null) {
                                    stage5EnforceAdChrome(activity, stage5ChromeState);
                                }
                                ad.setFullScreenContentCallback(
                                        new com.google.android.gms.ads.FullScreenContentCallback() {
                                            @Override public void onAdShowedFullScreenContent() {
                                                stage5AdShowing = true;
                                                stage5Interstitial = null;
                                                stage5EnforceAdChrome(activity, stage5ChromeState);
                                                stage5ShownTimes.add(
                                                        Long.valueOf(android.os.SystemClock.elapsedRealtime()));
                                                CigramAdManager.recordPlayerAdShown(activity);
                                                stage5Report("ad_show", "");
                                            }

                                            @Override public void onAdDismissedFullScreenContent() {
                                                stage5LastAdClosedAt = android.os.SystemClock.elapsedRealtime();
                                                stage5Report("ad_closed", "");
                                                stage5AdShowing = false;
                                                CigramAdManager.releaseFullScreen(PlayerMonitor.this);
                                                stage5RestoreAdChrome(stage5ChromeState);
                                                stage5ChromeState = null;
                                                stage5RestoreAfterAd();
                                            }

                                            @Override public void onAdFailedToShowFullScreenContent(
                                                    com.google.android.gms.ads.AdError adError) {
                                                stage5Report("ad_failed", "show");
                                                stage5AdShowing = false;
                                                stage5Interstitial = null;
                                                CigramAdManager.releaseFullScreen(PlayerMonitor.this);
                                                stage5RestoreAdChrome(stage5ChromeState);
                                                stage5ChromeState = null;
                                                stage5RestoreAfterAd();
                                            }
                                        });
                            }

                            @Override public void onAdFailedToLoad(
                                    com.google.android.gms.ads.LoadAdError error) {
                                stage5LoadInFlight = false;
                                CigramAdManager.setLoading(PlayerMonitor.this, false);
                                stage5Interstitial = null;
                                stage5Report("ad_failed", "load:" + (error == null ? -1 : error.getCode()));
                                stage5LoadFailures++;
                                stage5NextLoadAt = android.os.SystemClock.elapsedRealtime()
                                        + Math.min(300000L, 30000L << Math.min(stage5LoadFailures - 1, 4));
                                android.util.Log.w("CigramStage5", "Interstitial load failed: " + error);
                            }
                        });
            } catch (Throwable error) {
                stage5LoadInFlight = false;
                CigramAdManager.setLoading(PlayerMonitor.this, false);
                stage5Interstitial = null;
                stage5LoadFailures++;
                stage5NextLoadAt = android.os.SystemClock.elapsedRealtime()
                        + Math.min(300000L, 30000L << Math.min(stage5LoadFailures - 1, 4));
                android.util.Log.w("CigramStage5", "Interstitial load exception", error);
            }
        }

        private void stage5RestoreAfterAd() {
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (destroyed || !isAlive(activity)) return;
                    try {
                        CigramPlayerOrientation.afterAd();   // v10.81: follows the phone again (landscape or portrait)
                    } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "run", ignored); }
                    applyPlayerEdgeToEdge(activity);
                    handler.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (!destroyed && isAlive(activity)) {
                                applyPlayerEdgeToEdge(activity);
                                try {
                                    View root = activity.findViewById(android.R.id.content);
                                    if (root != null && Build.VERSION.SDK_INT >= 20) {
                                        root.requestApplyInsets();
                                    }
                                } catch (Throwable ignored) {
                                    android.util.Log.w("CigramPlayerInsets", "restore insets failed", ignored);
                                }
                            }
                        }
                    }, 160L);
                    resolvePlayer();
                    if (player == null) return;
                    if (stage5AdMediaKey.length() > 0 && !stage5AdMediaKey.equals(stage5CurrentMediaKey())) {
                        // the user moved to other content while the ad was up: do not seek/resume it
                        stage5Report("play_resume_failed_after_ad", "content_changed");
                        stage5LoadInFlight = false;
                        return;
                    }

                    boolean cgResumeOk = true;
                    try {
                        Method seek = findCompatibleLongMethod(player.getClass(), "seekTo");
                        if (seek != null) {
                            seek.setAccessible(true);
                            if (seek.getParameterTypes().length == 1) {
                                seek.invoke(player, Long.valueOf(Math.max(0L, stage5SavedPosition)));
                            }
                        }
                    } catch (Throwable error) {
                        cgResumeOk = false;
                        stage5Report("play_resume_failed_after_ad", "seek");
                        android.util.Log.w("CigramStage5", "Restore seek failed", error);
                    }

                    if (stage5WasPlaying) {
                        try {
                            Method ready = findMethod(player.getClass(), "setPlayWhenReady", 1);
                            if (ready != null) {
                                ready.setAccessible(true);
                                ready.invoke(player, Boolean.TRUE);
                            }
                        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "run", ignored); }
                        try { invokeNoArg(player, "play"); } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "run", ignored); }
                    }

                    stage5LoadInFlight = false;
                    if (cgResumeOk) stage5Report("play_resume_after_ad", "");
                }
            }, 250L);
        }

        private String stage5CurrentMediaKey() {
            String media = stage4CurrentMediaUrl();
            if (media.length() == 0) media = stage4Extra("id", "title", "series_title");
            return media;
        }

        /** Player ad analytics: placement + watch position only (no URLs, no content ids, no PII). */
        private void stage5Report(String event, String detail) {
            try {
                long pos = 0L;
                if (player != null) pos = Math.max(0L, longValue(invokeNoArg(player, "getCurrentPosition"), 0L));
                // no content / series ids: together with the anonymous install id they would be a viewing history
                CigramAdManager.reportEx(activity, event, "player_midroll", detail,
                        "watch_pos_s", String.valueOf(pos / 1000L));
            } catch (Throwable ignored) { }
        }

        private boolean stage5PlayerIsPlaying() {
            if (player == null) return false;
            try {
                Object value = invokeNoArg(player, "isPlaying");
                if (value instanceof Boolean) return ((Boolean) value).booleanValue();
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stage5PlayerIsPlaying", ignored); }
            try {
                Object value = invokeNoArg(player, "getPlayWhenReady");
                if (value instanceof Boolean) return ((Boolean) value).booleanValue();
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stage5PlayerIsPlaying", ignored); }
            return false;
        }

        private void stage5TrimHourlyLimit() {
            long now = android.os.SystemClock.elapsedRealtime();
            for (int i = stage5ShownTimes.size() - 1; i >= 0; i--) {
                Long time = stage5ShownTimes.get(i);
                if (time == null || now - time.longValue() >= 60L * 60L * 1000L) {
                    stage5ShownTimes.remove(i);
                }
            }
        }

        private long stage5BreakValueMs(Object value) {
            if (value == null || value == JSONObject.NULL) return -1L;
            try {
                if (value instanceof JSONObject) {
                    JSONObject object = (JSONObject) value;
                    String[] msKeys = {"time_ms", "position_ms", "at_ms", "ms"};
                    for (String key : msKeys) {
                        if (object.has(key)) {
                            long parsed = stage4ParseTimeMs(object.opt(key), true);
                            if (parsed >= 0L) return parsed;
                        }
                    }
                    String[] secondKeys = {"time", "position", "at", "seconds", "second"};
                    for (String key : secondKeys) {
                        if (object.has(key)) {
                            long parsed = stage4ParseTimeMs(object.opt(key), false);
                            if (parsed >= 0L) return parsed;
                        }
                    }
                    return -1L;
                }
                return stage4ParseTimeMs(value, false);
            } catch (Throwable ignored) {
                return -1L;
            }
        }

        private java.util.ArrayList<Long> stage5Breaks() {
            String raw = stage4Extra("ad_breaks", "ad_breaks_json", "ads_breaks");
            if (raw.equals(stage5BreaksRaw)) return stage5ExplicitBreaks;

            stage5BreaksRaw = raw;
            java.util.ArrayList<Long> result = new java.util.ArrayList<Long>();
            if (raw.length() > 0) {
                try {
                    Object root = new JSONTokener(raw).nextValue();
                    JSONArray array = null;
                    if (root instanceof JSONArray) {
                        array = (JSONArray) root;
                    } else if (root instanceof JSONObject) {
                        JSONObject object = (JSONObject) root;
                        array = object.optJSONArray("ad_breaks");
                        if (array == null) array = object.optJSONArray("breaks");
                        if (array == null) array = object.optJSONArray("items");
                    }
                    if (array != null) {
                        for (int i = 0; i < array.length(); i++) {
                            long value = stage5BreakValueMs(array.opt(i));
                            if (value >= 0L) result.add(Long.valueOf(value));
                        }
                    } else {
                        long single = stage5BreakValueMs(root);
                        if (single >= 0L) result.add(Long.valueOf(single));
                    }
                } catch (Throwable ignored) {
                    String[] pieces = raw.split("[,;|]");
                    for (String piece : pieces) {
                        long value = stage5BreakValueMs(piece);
                        if (value >= 0L) result.add(Long.valueOf(value));
                    }
                }
            }

            java.util.Collections.sort(result);
            stage5ExplicitBreaks = result;
            return stage5ExplicitBreaks;
        }

        private String stage5BreakKey(long breakMs) {
            return stage5MediaKey + "|" + breakMs;
        }

        private boolean stage5AllowedAt(long breakMs, long duration) {
            if (breakMs < CigramAdConfig.playerMinStartMs(activity)) return false;
            if (duration > 0L && breakMs > duration - CIGRAM_AD_END_GUARD_MS) return false;
            return true;
        }

        /**
         * AdMob is off: show the in-house ad (cached copy, never waits) at this break point, through the same shared
         * limits as every other break (CigramEpisodeAds.midRoll). Pauses the film only when an ad is really on screen
         * and resumes it where it stopped.
         */
        private void stage5InHouseBreak(long breakMs) {
            String key = stage5BreakKey(breakMs);
            if (stage5HandledBreaks.contains(key)) return;
            stage5HandledBreaks.add(key);                       // one attempt per break point
            if (!CigramAdConfig.inHouseBreaksEnabled(activity) || !stage5PlayerIsPlaying()) return;
            final boolean[] paused = {false};
            stage5AdMediaKey = stage5CurrentMediaKey();
            stage5SavedPosition = Math.max(0L, longValue(invokeNoArg(player, "getCurrentPosition"), 0L));
            stage5WasPlaying = true;
            CigramEpisodeAds.midRoll(activity,
                    new Runnable() {
                        @Override public void run() {
                            paused[0] = true;
                            stage5AdShowing = true;
                            stage5AdShowingSince = android.os.SystemClock.elapsedRealtime();
                            pausePlayer();
                        }
                    },
                    new Runnable() {
                        @Override public void run() {
                            stage5AdShowing = false;
                            if (paused[0]) stage5RestoreAfterAd();
                        }
                    });
        }

        private void stage5MaybeShow(long breakMs) {
            if (!CigramAdConfig.ADMOB_ENABLED) {
                stage5InHouseBreak(breakMs);
                return;
            }
            String key = stage5BreakKey(breakMs);
            if (stage5HandledBreaks.contains(key)) return;

            long cgPos = player == null ? 0L
                    : Math.max(0L, longValue(invokeNoArg(player, "getCurrentPosition"), 0L));
            String cgWhy = CigramAdManager.playerAdBlockReason(activity, this, cgPos,
                    errorVisible, isAlive(activity) && !destroyed);
            if (cgWhy.length() > 0) {
                // transient states are retried inside the break grace window;
                // policy refusals (cap / cooldown / flags / ad-free) skip this break
                boolean transientWhy = "other_ad_showing".equals(cgWhy) || "other_ad_loading".equals(cgWhy)
                        || "player_error".equals(cgWhy) || "activity".equals(cgWhy) || "error".equals(cgWhy);
                if (!transientWhy) {
                    stage5HandledBreaks.add(key); // once per break point, so this is reported once
                    if (!"ad_free".equals(cgWhy) && !"mid_roll_disabled".equals(cgWhy)) {
                        stage5Report("ad_skipped_by_policy", cgWhy);
                    }
                }
                return;
            }

            if (!stage5PlayerIsPlaying()) return;
            if (stage5Interstitial != null
                    && android.os.SystemClock.elapsedRealtime() - stage5LoadedAt > 45L * 60L * 1000L) {
                stage5Interstitial = null;
            }
            if (stage5Interstitial == null) {
                stage5LoadAd();
                return;
            }
            if (!CigramAdManager.acquireFullScreen(this)) return;

            stage5HandledBreaks.add(key);
            stage5AdMediaKey = stage5CurrentMediaKey();
            stage5SavedPosition = Math.max(0L,
                    longValue(invokeNoArg(player, "getCurrentPosition"), 0L));
            stage5WasPlaying = stage5PlayerIsPlaying();
            pausePlayer();
            stage5AdShowing = true;
            stage5AdShowingSince = android.os.SystemClock.elapsedRealtime();
            stage5ChromeState = stage5BeginAdChrome(activity, stage5ChromeState);

            try {
                activity.setRequestedOrientation(
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "stage5MaybeShow", ignored); }

            try {
                com.google.android.gms.ads.interstitial.InterstitialAd ad = stage5Interstitial;
                stage5Interstitial = null;
                if (ad == null) {
                    stage5AdShowing = false;
                    CigramAdManager.releaseFullScreen(this);
                    stage5RestoreAdChrome(stage5ChromeState);
                    stage5ChromeState = null;
                    stage5RestoreAfterAd();
                    return;
                }
                stage5EnforceAdChrome(activity, stage5ChromeState);
                ad.show(activity);
            } catch (Throwable error) {
                stage5AdShowing = false;
                stage5Interstitial = null;
                CigramAdManager.releaseFullScreen(this);
                stage5RestoreAdChrome(stage5ChromeState);
                stage5ChromeState = null;
                android.util.Log.w("CigramStage5", "Interstitial show exception", error);
                stage5RestoreAfterAd();
            }
        }

        /**
         * The ad screen takes window focus from the player. If the player has had focus back for
         * a while and no ad callback arrived, the "showing" state is stale: without this check
         * stage5Tick returned early for the rest of the film and the held full-screen lock also
         * blocked the episode ads.
         */
        private void stage5RecoverStaleAdShowing() {
            if (!stage5AdShowing) return;
            if (android.os.SystemClock.elapsedRealtime() - stage5AdShowingSince < 30000L) return;
            boolean focused;
            try {
                focused = activity.hasWindowFocus();
            } catch (Throwable ignored) {
                focused = false;
            }
            if (!focused) return;
            android.util.Log.w("CigramStage5", "Mid-roll ad callback lost: recovering");
            stage5Report("ad_failed", "stale_showing");
            stage5AdShowing = false;
            stage5Interstitial = null;
            CigramAdManager.releaseFullScreen(this);
            stage5RestoreAdChrome(stage5ChromeState);
            stage5ChromeState = null;
            stage5RestoreAfterAd();
        }

        private void stage5Tick() {
            stage5RecoverStaleAdShowing();
            if (stage5AdShowing || player == null || destroyed || !isAlive(activity)) return;
            if (errorVisible || retrying || !isOnline(activity)) return;

            String media = stage4CurrentMediaUrl();
            if (media.length() == 0) {
                media = stage4Extra("id", "title", "series_title");
            }
            if (!media.equals(stage5MediaKey)) {
                stage5MediaKey = media;
                stage5HandledBreaks.clear();
                stage5ExplicitBreaks.clear();
                stage5BreaksRaw = null;
                // keep a loaded interstitial: it is not tied to the content and stays valid
                // (dropping it here left the next break of the new episode without an ad)
                stage5LoadInFlight = false;
            }

            long position = Math.max(0L,
                    longValue(invokeNoArg(player, "getCurrentPosition"), 0L));
            long duration = longValue(invokeNoArg(player, "getDuration"), -1L);
            if (duration < 0L || duration > 24L * 60L * 60L * 1000L) duration = -1L;

            java.util.ArrayList<Long> explicit = stage5Breaks();
            // v6.2: no breaks set by the admin -> 1..4 breaks spread over this title (CigramAdBreaks)
            if ((explicit == null || explicit.isEmpty()) && duration > 0L) {
                explicit = CigramAdBreaks.asList(CigramAdBreaks.forItem(stage5MediaKey, duration));
            }
            CigramAdBreaks.setCurrent(activity, explicit);
            if (explicit != null && !explicit.isEmpty()) {
                for (Long boxed : explicit) {
                    if (boxed == null) continue;
                    long point = boxed.longValue();
                    String key = stage5BreakKey(point);
                    if (stage5HandledBreaks.contains(key)) continue;
                    if (!stage5AllowedAt(point, duration)) {
                        stage5HandledBreaks.add(key);
                        continue;
                    }

                    if (position > point + CIGRAM_AD_BREAK_GRACE_MS) {
                        stage5HandledBreaks.add(key);
                        continue;
                    }
                    if (position >= point) {
                        stage5MaybeShow(point);
                        return;
                    }
                    if (point - position <= CIGRAM_AD_PRELOAD_MS) {
                        stage5LoadAd();
                        return;
                    }
                    return;
                }
                return;
            }

            // breaks: first at firstAdDelay (15 min), then every interval (15 min), max 4 per film
            long first = Math.max(60L * 1000L, CigramAdConfig.firstAdDelayMs(activity));
            long interval = Math.max(60L * 1000L, CigramAdConfig.midrollIntervalMs(activity));
            long currentPoint = position < first ? 0L : first + ((position - first) / interval) * interval;
            // at most PLAYER_MAX_ADS_PER_FILM breaks per film/episode (15, 30, 45, 60 min)
            long cgMaxIndex = CigramAdConfig.PLAYER_MAX_ADS_PER_FILM - 1L;
            if (position >= first && (currentPoint - first) / interval > cgMaxIndex) return;
            if (position >= first) {
                String currentKey = stage5BreakKey(currentPoint);
                if (!stage5HandledBreaks.contains(currentKey)) {
                    // With audio data the break may wait for a quiet moment; without it the old 12 s window applies.
                    long cgGrace = CigramQuiet.hasSignal()
                            ? CigramAdConfig.PLAYER_QUIET_WINDOW_MS : CIGRAM_AD_BREAK_GRACE_MS;
                    if (!stage5AllowedAt(currentPoint, duration)) {
                        stage5HandledBreaks.add(currentKey);
                    } else if (position <= currentPoint + cgGrace) {
                        if (CigramQuiet.allowBreak(position - currentPoint, cgGrace)) {
                            stage5MaybeShow(currentPoint);
                        }
                        return;
                    } else {
                        stage5HandledBreaks.add(currentKey);
                    }
                }
            }

            long nextPoint = position < first ? first : currentPoint + interval;
            if ((nextPoint - first) / interval > cgMaxIndex) return;
            if (stage5AllowedAt(nextPoint, duration)
                    && nextPoint - position <= CIGRAM_AD_PRELOAD_MS) {
                stage5LoadAd();
            }
        }

        private String playerErrorMessage(Object error) {
            // 2026: the real reason (unsupported format / missing / expired / timeout / server / offline ...),
            // decided in one place; "no internet" only for a network error while the device is really offline
            return CigramPlaybackError.describe(activity, error);
        }

        private void showPlayerError(String message) {
            if (!isAlive(activity)) return;
            errorVisible = true;
            setBufferingMode(0);

            FrameLayout root = contentRoot(activity);
            if (root == null) return;
            if (root.findViewWithTag("cigram_stage1_player_error") != null) return;

            final float density = activity.getResources().getDisplayMetrics().density;
            final Typeface font = sima(activity);

            FrameLayout overlay = new FrameLayout(activity);
            overlay.setTag("cigram_stage1_player_error");
            overlay.setBackgroundColor(0xD9000000);
            overlay.setClickable(true);

            LinearLayout panel = new LinearLayout(activity);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setGravity(Gravity.CENTER);
            panel.setPadding(px(density, 22), px(density, 20), px(density, 22), px(density, 20));

            TextView status = text(activity, message, 17f, Color.WHITE, font);
            status.setGravity(Gravity.CENTER);
            panel.addView(status, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            rowLp.topMargin = px(density, 18);
            panel.addView(row, rowLp);

            TextView retry = pillButton(activity, "إعادة المحاولة", 0xFFE21B14, font);
            TextView back = pillButton(activity, "رجوع", 0xFF1B252B, font);

            LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                    px(density, 170),
                    px(density, 48)
            );
            buttonLp.setMargins(px(density, 6), 0, px(density, 6), 0);
            row.addView(retry, buttonLp);
            row.addView(back, new LinearLayout.LayoutParams(buttonLp));

            // one tap sends what the app recorded about this failure (so it can be fixed from facts)
            final String shownMessage = message;
            TextView share = text(activity, "إرسال التشخيص", 13f, 0xFFA3A3A8, font);
            share.setGravity(Gravity.CENTER);
            share.setPadding(px(density, 14), px(density, 12), px(density, 14), px(density, 4));
            share.setClickable(true);
            share.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { shareDiagnostics(shownMessage); }
            });
            panel.addView(share, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            retry.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    retry();
                }
            });
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    activity.finish();
                }
            });

            FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
            );
            overlay.addView(panel, panelLp);
            root.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }

        private void removePlayerError() {
            removeTagged(activity, "cigram_stage1_player_error");
            errorVisible = false;
        }

        private void setBufferingMode(int mode) {
            if (playerView == null) return;
            try {
                Method method = findMethod(playerView.getClass(), "setShowBuffering", 1);
                if (method != null) {
                    method.setAccessible(true);
                    method.invoke(playerView, Integer.valueOf(mode));
                }
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "setBufferingMode", ignored); }
        }
    }

    private static final class FetchResult {
        boolean ok;
        boolean retryable;
        String body = "";
        String userMessage = "تعذر تحميل المحتوى. حاول مرة أخرى.";
    }

    // The catalogue fetch retries a few times on transient failures (timeout, 5xx, dropped
    // connection) with a short backoff, so a brief network blip no longer leaves Home empty.
    private static final int CATALOG_FETCH_ATTEMPTS = 3;
    private static final long CATALOG_RETRY_BACKOFF_MS = 400L;

    /**
     * Removes a leading UTF-8/UTF-16 byte-order mark and any leading whitespace before the first
     * JSON token. {@link String#trim()} does NOT strip U+FEFF, so a BOM-prefixed body (some CDNs /
     * proxies add one) was being rejected as an invalid catalogue even though the JSON was fine.
     */
    private static String stripCatalogBom(String s) {
        if (s == null) return "";
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '﻿' || c == '￾' || c == '​' || c <= ' ') { i++; continue; }
            break;
        }
        return i == 0 ? s : s.substring(i);
    }

    /** Catalogue JSON already held in memory, or the saved copy from preferences. "" if none yet. */
    public static String cachedCatalogJson(Context context) {
        String memory = CATALOG_MEMORY_JSON;
        if (looksLikeCatalogText(memory)) return memory;
        if (context == null) return "";
        try {
            String cached = catalogPrefs(context).getString(KEY_JSON, "");
            return cached == null ? "" : cached;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Blocking catalogue load for screens that need the full catalogue off the main thread
     * (e.g. a section page). Tries a hardened network fetch first (which already retries on transient
     * failures), then falls back to the in-memory / saved copy, so a momentary blip never leaves the
     * screen blank. Returns "" only when there is genuinely nothing to show (first run, offline, no
     * cache). MUST be called off the main thread.
     */
    public static String loadCatalogJsonBlocking(Context context) {
        try {
            FetchResult result = fetchCatalog(context);
            if (result.ok && isValidCatalog(result.body)) return result.body;
        } catch (Throwable ignored) { }
        return cachedCatalogJson(context);
    }

    private static FetchResult fetchCatalog(Context context) {
        if (!isOnline(context)) {
            FetchResult offline = new FetchResult();
            offline.userMessage = "لا يوجد اتصال بالإنترنت";
            return offline;
        }
        FetchResult result = new FetchResult();
        for (int attempt = 1; attempt <= CATALOG_FETCH_ATTEMPTS; attempt++) {
            result = fetchCatalogOnce(context);
            if (result.ok || !result.retryable) return result;
            if (attempt < CATALOG_FETCH_ATTEMPTS) {
                if (!isOnline(context)) {
                    result.userMessage = "لا يوجد اتصال بالإنترنت";
                    return result;
                }
                try {
                    Thread.sleep(CATALOG_RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return result;
                }
                android.util.Log.w("CigramDiag", "fetchCatalog retry "
                        + (attempt + 1) + "/" + CATALOG_FETCH_ATTEMPTS);
            }
        }
        return result;
    }

    private static FetchResult fetchCatalogOnce(Context context) {
        FetchResult result = new FetchResult();
        HttpURLConnection connection = null;
        InputStream input = null;

        final long cgT0 = android.os.SystemClock.elapsedRealtime();
        try {
            connection = (HttpURLConnection) new URL(CATALOG_URL).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Cigram-Android/1.0");

            int status = connection.getResponseCode();
            LAST_DIAG = "رد السيرفر http=" + status;
            android.util.Log.i("CigramDiag", "fetchCatalog http=" + status + " url=" + CATALOG_URL);
            if (status >= 500) {
                android.util.Log.e("CigramDiag", "fetchCatalog SERVER ERROR http=" + status + " url=" + CATALOG_URL);
                CigramAnalytics.track(context, "api_error", "api", "catalog", "status", String.valueOf(status));
                result.userMessage = "حدث خطأ من السيرفر. حاول لاحقًا.";
                result.retryable = true;   // 5xx is usually transient: worth another attempt
                return result;
            }
            if (status < 200 || status >= 300) {
                android.util.Log.e("CigramDiag", "fetchCatalog BAD STATUS http=" + status + " url=" + CATALOG_URL);
                CigramAnalytics.track(context, "api_error", "api", "catalog", "status", String.valueOf(status));
                result.userMessage = "تعذر تحميل المحتوى. حاول مرة أخرى.";
                return result;
            }

            input = new BufferedInputStream(connection.getInputStream());
            ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);
            byte[] buffer = new byte[16 * 1024];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_CATALOG_BYTES) {
                    throw new java.io.IOException("Catalog too large");
                }
                output.write(buffer, 0, read);
            }

            String json = CigramTitles.normalizeCatalog(
                    stripCatalogBom(new String(output.toByteArray(), Charset.forName("UTF-8"))));
            if (!isValidCatalog(json)) {
                // DIAG: reached the server but the body is not a usable catalogue (empty / error JSON / HTML)
                android.util.Log.e("CigramDiag", "fetchCatalog INVALID BODY bytes=" + json.length()
                + " head=" + (json.length() > 200 ? json.substring(0, 200) : json).replace('\n', ' '));
                result.userMessage = "تعذر قراءة بيانات المحتوى";
                LAST_DIAG = "http=" + status + " لكن الرد غير صالح، الحجم=" + json.length() + " بداية: "
                        + (json.length() > 80 ? json.substring(0, 80) : json).replace('\n', ' ');
                return result;
            }

            LAST_DIAG = "تم التحميل بنجاح، الحجم=" + json.length();
            result.ok = true;
            result.body = json;
            CigramAnalytics.track(context, "perf", "metric", "catalog_fetch_ms",
                    "ms", String.valueOf(android.os.SystemClock.elapsedRealtime() - cgT0),
                    "kb", String.valueOf(json.length() / 1024));
            return result;

        } catch (SocketTimeoutException error) {
            LAST_DIAG = "انتهت مهلة الاتصال بالسيرفر (timeout)";
            android.util.Log.e("CigramDiag", "fetchCatalog TIMEOUT url=" + CATALOG_URL, error);
            CigramAnalytics.track(context, "api_error", "api", "catalog", "status", "timeout");
            result.userMessage = "انتهت مهلة الاتصال. حاول مرة أخرى.";
            result.retryable = true;   // timeout: retry may succeed on a better connection
        } catch (UnknownHostException error) {
            LAST_DIAG = "تعذر الوصول لاسم السيرفر (DNS) cigram-admin-api.wwq-mixtv.workers.dev";
            android.util.Log.e("CigramDiag", "fetchCatalog DNS FAIL (host not found) url=" + CATALOG_URL, error);
            result.userMessage = "لا يوجد اتصال بالإنترنت";
        } catch (ConnectException error) {
            result.userMessage = "لا يوجد اتصال بالإنترنت";
            result.retryable = true;   // connection refused/reset is often transient
        } catch (Throwable error) {
            LAST_DIAG = "فشل الاتصال: " + error.getClass().getSimpleName() + " " + error.getMessage();
            android.util.Log.e("CigramDiag", "fetchCatalog FAILED " + error.getClass().getName() + ": " + error.getMessage(), error);
            if (!isOnline(context)) {
                result.userMessage = "لا يوجد اتصال بالإنترنت";
            } else {
                result.userMessage = "تعذر تحميل المحتوى. حاول مرة أخرى.";
                result.retryable = true;   // unexpected I/O error while online: retry once or twice
            }
        } finally {
            try { if (input != null) input.close(); } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "fetchCatalog", ignored); }
            try { if (connection != null) connection.disconnect(); } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "fetchCatalog", ignored); }
        }
        return result;
    }

    private static boolean isValidCatalog(String json) {
        if (!looksLikeCatalogText(json)) return false;
        try {
            Object value = new JSONTokener(json).nextValue();
            if (value instanceof JSONArray) {
                return ((JSONArray) value).length() > 0;
            }
            if (!(value instanceof JSONObject)) return false;
            JSONObject object = (JSONObject) value;
            if (object.length() == 0 || !object.optBoolean("ok", true)) return false;
            for (String key : new String[]{
                    "movies", "series", "items", "data", "results",
                    "content", "sections", "catalog"
            }) {
                if (object.has(key)) return true;
            }
            return jsonText(object, "title", "series_title", "name").length() > 0
                    && (object.has("id") || object.has("series_id")
                        || object.has("poster") || object.has("video")
                        || object.has("link") || object.has("type"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String detailsImageKey(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("//")) value = "https:" + value;
        return value;
    }

    public static android.graphics.Bitmap getSharedDetailsBitmap(String raw) {
        String key = detailsImageKey(raw);
        if (key.length() == 0) return null;
        synchronized (DETAILS_IMAGE_MEMORY) {
            android.graphics.Bitmap bitmap = DETAILS_IMAGE_MEMORY.get(key);
            return bitmap != null && !bitmap.isRecycled() ? bitmap : null;
        }
    }

    public static void putSharedDetailsBitmap(String raw, android.graphics.Bitmap bitmap) {
        String key = detailsImageKey(raw);
        if (key.length() == 0 || bitmap == null || bitmap.isRecycled()) return;
        synchronized (DETAILS_IMAGE_MEMORY) {
            DETAILS_IMAGE_MEMORY.put(key, bitmap);
        }
    }

    private static android.graphics.Bitmap bitmapFromView(View source) {
        if (source == null) return null;
        if (source instanceof android.widget.ImageView) {
            Drawable drawable = ((android.widget.ImageView) source).getDrawable();
            if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
                android.graphics.Bitmap bitmap =
                        ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
                if (bitmap != null && !bitmap.isRecycled()) return bitmap;
            }
        }
        if (source instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) source;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.graphics.Bitmap bitmap = bitmapFromView(group.getChildAt(i));
                if (bitmap != null && !bitmap.isRecycled()) return bitmap;
            }
        }
        return null;
    }

    private static android.widget.ImageView findImageInside(View source) {
        if (source == null) return null;
        if (source instanceof android.widget.ImageView) {
            return (android.widget.ImageView) source;
        }
        if (source instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) source;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.ImageView found = findImageInside(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static android.graphics.Bitmap obtainSharedDetailsBitmap(
            Context context,
            String raw
    ) {
        String key = detailsImageKey(raw);
        if (context == null || key.length() == 0) return null;

        android.graphics.Bitmap memory = getSharedDetailsBitmap(key);
        if (memory != null) return memory;

        java.io.File target = dynamicPosterCacheFile(context, key);
        android.graphics.Bitmap bitmap = decodeDynamicPoster(target);
        if (bitmap != null) {
            putSharedDetailsBitmap(key, bitmap);
            return bitmap;
        }

        if (!(key.startsWith("http://") || key.startsWith("https://"))) return null;

        HttpURLConnection connection = null;
        InputStream input = null;
        java.io.FileOutputStream output = null;
        java.io.File temp = new java.io.File(
                target.getParentFile(),
                target.getName() + ".details.part"
        );
        try {
            String fetchUrl = key.replace(" ", "%20");
            connection = (HttpURLConnection) new URL(fetchUrl).openConnection();
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(true);
            connection.setRequestProperty("Accept", "image/*");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return null;

            input = new BufferedInputStream(connection.getInputStream());
            output = new java.io.FileOutputStream(temp, false);
            byte[] buffer = new byte[16 * 1024];
            int count;
            long total = 0L;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > 24L * 1024L * 1024L) {
                    throw new java.io.IOException("details image too large");
                }
                output.write(buffer, 0, count);
            }
            output.flush();
            if (target.exists()) target.delete();
            if (!temp.renameTo(target)) target = temp;
            bitmap = decodeDynamicPoster(target);
            if (bitmap != null) putSharedDetailsBitmap(key, bitmap);
            return bitmap;
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "shared image preload failed", error);
            return null;
        } finally {
            try { if (input != null) input.close(); } catch (Throwable ignored) {}
            try { if (output != null) output.close(); } catch (Throwable ignored) {}
            if (connection != null) connection.disconnect();
            if (temp.exists() && !temp.equals(target)) temp.delete();
        }
    }

    public static void preloadDetailsImages(
            final Context context,
            String poster,
            String backdrop
    ) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        final String[] urls = new String[]{detailsImageKey(poster), detailsImageKey(backdrop)};
        for (final String url : urls) {
            if (url.length() == 0 || getSharedDetailsBitmap(url) != null) continue;
            synchronized (DETAILS_IMAGE_PRELOADS) {
                if (!DETAILS_IMAGE_PRELOADS.add(url)) continue;
            }
            IO.execute(new Runnable() {
                @Override public void run() {
                    try {
                        obtainSharedDetailsBitmap(app, url);
                    } finally {
                        synchronized (DETAILS_IMAGE_PRELOADS) {
                            DETAILS_IMAGE_PRELOADS.remove(url);
                        }
                    }
                }
            });
        }
    }

    private static String detailsIdentity(android.content.Intent intent) {
        if (intent == null) return "details";
        String id = clean(intent.getStringExtra("id"));
        String seriesId = clean(intent.getStringExtra("series_id"));
        String type = clean(intent.getStringExtra("type"));
        String title = clean(intent.getStringExtra("title"));
        return type + "|" + seriesId + "|" + id + "|" + title;
    }

    public static String detailsTransitionName(android.content.Intent intent) {
        return "cigram_details_poster_"
                + Integer.toHexString(detailsIdentity(intent).hashCode());
    }

    private static DetailsLaunch detailsLaunch(android.content.Intent intent) {
        String key = detailsIdentity(intent);
        synchronized (DETAILS_LAUNCH_LOCK) {
            DetailsLaunch launch = DETAILS_LAUNCHES.get(key);
            if (launch != null && System.currentTimeMillis() - launch.createdAt <= 5000L) {
                return launch;
            }
            if (launch != null) DETAILS_LAUNCHES.remove(key);
            return null;
        }
    }

    public static String pendingDetailsSharedImageUrl(android.content.Intent intent) {
        DetailsLaunch launch = detailsLaunch(intent);
        return launch == null ? "" : launch.imageUrl;
    }

    public static boolean isSharedDetailsLaunch(android.content.Intent intent) {
        DetailsLaunch launch = detailsLaunch(intent);
        return launch != null && launch.shared;
    }

    private static String detailsWarmKey(JSONObject item) {
        if (item == null) return "";
        String key = stage3ContentKey(item);
        return key == null ? "" : key.trim();
    }

    private static JSONObject copyDetailsJson(JSONObject source) {
        if (source == null) return new JSONObject();
        try {
            return new JSONObject(source.toString());
        } catch (Throwable error) {
            return source;
        }
    }

    private static void warmDetailsItem(JSONObject item) {
        if (item == null || item.length() == 0) return;
        String key = detailsWarmKey(item);
        if (key.length() == 0) return;

        JSONObject copy = copyDetailsJson(item);
        synchronized (DETAILS_WARM_ITEMS) {
            DETAILS_WARM_ITEMS.put(key, copy);
            while (DETAILS_WARM_ITEMS.size() > DETAILS_WARM_MAX_ITEMS) {
                java.util.Iterator<Map.Entry<String, JSONObject>> iterator =
                        DETAILS_WARM_ITEMS.entrySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
        }
    }

    private static JSONObject detailsWarmLookup(android.content.Intent intent) {
        if (intent == null) return null;

        JSONObject probe = new JSONObject();
        mergeIntentDetails(probe, intent);
        String key = detailsWarmKey(probe);

        synchronized (DETAILS_WARM_ITEMS) {
            if (key.length() > 0) {
                JSONObject exact = DETAILS_WARM_ITEMS.get(key);
                if (exact != null) return copyDetailsJson(exact);
            }

            String wantedId = normalizeSearch(jsonText(probe, "id", "movie_id", "item_id", "content_id"));
            String wantedSeriesId = normalizeSearch(jsonText(probe, "series_id"));
            String wantedTitle = normalizeSearch(jsonText(probe, "series_title", "title", "name"));
            String wantedType = normalizeSearch(jsonText(probe, "type"));

            // Pass 1: identity by id / series_id only. A title match must never win
            // over an exact id match that sits later in the warm cache.
            for (JSONObject candidate : DETAILS_WARM_ITEMS.values()) {
                if (candidate == null) continue;

                String id = normalizeSearch(jsonText(candidate, "id", "movie_id", "item_id", "content_id"));
                String seriesId = normalizeSearch(jsonText(candidate, "series_id", "id"));

                if (wantedSeriesId.length() > 0
                        && (wantedSeriesId.equals(seriesId) || wantedSeriesId.equals(id))) {
                    return copyDetailsJson(candidate);
                }
                if (wantedId.length() > 0
                        && (wantedId.equals(id) || wantedId.equals(seriesId))) {
                    return copyDetailsJson(candidate);
                }
            }

            // Pass 2: title fallback, only when no id match exists.
            for (JSONObject candidate : DETAILS_WARM_ITEMS.values()) {
                if (candidate == null) continue;

                String type = normalizeSearch(inferContentType(candidate, ""));
                String title = normalizeSearch(jsonText(candidate, "series_title", "title", "name", "title_ar", "title_en"));

                if (wantedTitle.length() > 0 && wantedTitle.equals(title)
                        && (wantedType.length() == 0 || wantedType.equals(type))) {
                    return copyDetailsJson(candidate);
                }
            }
        }
        return null;
    }

    private static JSONObject mergeDetailsObjects(JSONObject base, JSONObject overlay) {
        JSONObject out = copyDetailsJson(base);
        if (overlay == null) return out;
        try {
            java.util.Iterator<String> keys = overlay.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = overlay.opt(key);
                if (value == null || value == JSONObject.NULL) continue;
                if (value instanceof String && ((String) value).trim().length() == 0) continue;
                out.put(key, value);
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "Details merge helper failed", error);
        }
        return out;
    }

    private static void mergeIntentDetails(JSONObject target, android.content.Intent intent) {
        if (target == null || intent == null) return;
        String[] keys = new String[]{
                "id", "type", "title", "series_title", "name",
                "poster", "backdrop", "series_id",
                "imdb", "rating", "imdb_rating", "vote_average", "score",
                "year", "release_year",
                "quality", "resolution", "video_quality",
                "category", "genre", "genres",
                "description", "overview", "plot", "synopsis",
                "video", "link", "video_url", "movie_url", "stream_url", "play_url",
                "subtitle", "subtitle_url",
                "season", "episode", "series_episodes_json", "skip_segments", "duration"
        };

        for (String key : keys) {
            try {
                if (!intent.hasExtra(key)) continue;
                Object value = intent.getExtras() == null ? null : intent.getExtras().get(key);
                if (value == null) continue;
                String text = String.valueOf(value).trim();
                if (text.length() > 0) target.put(key, text);
            } catch (Throwable ignored) {}
        }
    }

    private static void attachDetailsWarmTouch(
            final Activity activity,
            final View card,
            final JSONObject item
    ) {
        if (activity == null || card == null || item == null) return;

        warmDetailsItem(item);

        final String warmPoster = jsonText(
                item, "poster", "series_poster", "season_poster", "image");
        final String warmBackdrop = jsonText(
                item, "backdrop", "landscape_poster", "source_backdrop");

        card.post(new Runnable() {
            @Override public void run() {
                if (!isAlive(activity) || card.getWindowToken() == null) return;
                android.graphics.Rect visible = new android.graphics.Rect();
                if (card.getGlobalVisibleRect(visible)
                        && visible.width() > 0
                        && visible.height() > 0) {
                    preloadDetailsImages(activity, warmPoster, warmBackdrop);
                }
            }
        });

        card.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event == null) return false;
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    warmDetailsItem(item);
                    preloadDetailsImages(activity, warmPoster, warmBackdrop);
                }
                return false;
            }
        });
    }

    public static void openTitleDetails(
            Activity activity,
            JSONObject item,
            View clickedView
    ) {
        if (activity == null || item == null) return;
        try {
            JSONObject safe = copyDetailsJson(item);
            String type = inferContentType(safe, "");
            String id = "series".equals(type)
                    ? jsonText(safe, "series_id", "id", "movie_id", "item_id", "content_id")
                    : jsonText(safe, "id", "movie_id", "item_id", "content_id");
            if (id.length() == 0) {
                String seed = relatedIdentityKey(safe);
                if (seed.length() == 0) {
                    seed = jsonText(safe, "series_title", "title", "name")
                            + "|" + jsonText(safe, "year", "release_year");
                }
                id = "local_" + Integer.toHexString(seed.hashCode());
                safe.put("id", id);
                if ("series".equals(type)) safe.put("series_id", id);
            }
            String titleHint = jsonText(
                    safe, "series_title", "title", "name", "title_ar", "title_en");
            JSONObject canonical = findCatalogMemoryItem(type, id, titleHint);
            if (canonical != null && canonical.length() > 0) {
                safe = mergeDetailsObjects(canonical, safe);
            }
            safe.put("type", type);
            warmDetailsItem(safe);
            // v10.81: a film from an embed site (vidbolt ...) is looked up while the page opens, so pressing
            // «مشاهدة» starts it at once (the answer is kept for 8 minutes)
            try {
                String early = jsonText(safe, "video", "link", "video_url", "movie_url");
                if (early.length() > 0 && !"series".equals(type) && CigramEmbedResolver.isKnownHost(early)) {
                    CigramEmbedResolver.resolveAsync(activity, early, new CigramEmbedResolver.Callback() {
                        @Override public void onResolved(String url) { }
                        @Override public void onFailed(String reason) { }
                    });
                }
            } catch (Throwable ignored) { }

            Class<?> detailsClass = Class.forName(activity.getPackageName() + ".DetailsActivity");
            android.content.Intent intent = new android.content.Intent(activity, detailsClass);
            intent.putExtra("id", id);
            intent.putExtra("type", type);
            // Sketchware's own "watch" block reads these extras; never leave them null.
            intent.putExtra("video", jsonText(safe, "video", "link", "video_url", "movie_url"));
            intent.putExtra("subtitle", jsonText(safe, "subtitle", "subtitle_url"));

            View shared = findImageInside(clickedView);
            if (shared == null) shared = clickedView;
            String source = jsonText(safe,
                    "poster", "series_poster", "season_poster", "image", "backdrop");
            preloadDetailsImages(
                    activity,
                    jsonText(safe, "poster", "series_poster", "season_poster", "image"),
                    jsonText(safe, "backdrop", "landscape_poster", "source_backdrop")
            );
            openDetailsWithTransition(activity, intent, shared, source);
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "openTitleDetails failed", error);
        }
    }

    public static void openTitleDetails(
            Activity activity,
            java.util.Map<String, ?> item,
            View clickedView
    ) {
        JSONObject object = new JSONObject();
        if (item != null) {
            try {
                for (java.util.Map.Entry<String, ?> entry : item.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    object.put(entry.getKey(), entry.getValue());
                }
            } catch (Throwable ignored) { }
        }
        openTitleDetails(activity, object, clickedView);
    }

    public static void openDetailsWithTransition(
            Activity activity,
            android.content.Intent intent,
            View sharedPoster,
            String sourceImageUrl
    ) {
        if (activity == null || intent == null) return;

        String poster = clean(intent.getStringExtra("poster"));
        String backdrop = clean(intent.getStringExtra("backdrop"));
        String sourceUrl = clean(sourceImageUrl);
        if (sourceUrl.length() == 0) sourceUrl = poster.length() > 0 ? poster : backdrop;

        android.graphics.Bitmap visible = bitmapFromView(sharedPoster);
        if (visible != null && sourceUrl.length() > 0) {
            putSharedDetailsBitmap(sourceUrl, visible);
        }
        preloadDetailsImages(activity, poster, backdrop);

        boolean shared = Build.VERSION.SDK_INT >= 21
                && sharedPoster != null
                && sharedPoster.getWidth() > 0
                && sharedPoster.getHeight() > 0;

        String identity = detailsIdentity(intent);
        synchronized (DETAILS_LAUNCH_LOCK) {
            java.util.Iterator<Map.Entry<String, DetailsLaunch>> iterator =
                    DETAILS_LAUNCHES.entrySet().iterator();
            long now = System.currentTimeMillis();
            while (iterator.hasNext()) {
                Map.Entry<String, DetailsLaunch> entry = iterator.next();
                if (now - entry.getValue().createdAt > 5000L) iterator.remove();
            }
            DetailsLaunch launch = new DetailsLaunch(sourceUrl, shared);
            if (shared) {
                try {
                    int[] where = new int[2];
                    sharedPoster.getLocationOnScreen(where);
                    launch.srcLeft = where[0];
                    launch.srcTop = where[1];
                    launch.srcWidth = sharedPoster.getWidth();
                    launch.srcHeight = sharedPoster.getHeight();
                    launch.hasRect = launch.srcWidth > 0 && launch.srcHeight > 0;
                } catch (Throwable error) {
                    android.util.Log.w("CigramDetails", "source rect failed", error);
                }
            }
            DETAILS_LAUNCHES.put(identity, launch);
            if (shared) captureLaunchSnapshot(activity, launch);
        }

        // v10.79: Cinemana-style open. The title page rises a little while fading in over the page the user
        // was on (which recedes slightly). No flying poster: the page is drawn at once with the poster the
        // card already showed (putSharedDetailsBitmap above), so nothing pops in or jumps after it opens.
        activity.startActivity(intent);
        activity.overridePendingTransition(R.anim.cg_details_enter, R.anim.cg_details_hold);
    }

    /**
     * Takes a half-size picture of the page the user is on, so the black gap between the two windows
     * is filled with that page (dimmed) while the poster flies. PixelCopy (API 26+) is a GPU copy and
     * does not stall the tap; older phones draw the view once at half size.
     */
    private static void captureLaunchSnapshot(Activity activity, final DetailsLaunch launch) {
        try {
            final View root = activity.getWindow().getDecorView();
            int w = root.getWidth() / 2;
            int h = root.getHeight() / 2;
            if (w <= 0 || h <= 0) return;
            final android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    w, h, android.graphics.Bitmap.Config.ARGB_8888);
            if (Build.VERSION.SDK_INT >= 26) {
                android.view.PixelCopy.request(activity.getWindow(), bmp,
                        new android.view.PixelCopy.OnPixelCopyFinishedListener() {
                            @Override public void onPixelCopyFinished(int result) {
                                if (result == android.view.PixelCopy.SUCCESS) launch.snapshot = bmp;
                            }
                        }, MAIN);
            } else {
                android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
                canvas.scale(0.5f, 0.5f);
                root.draw(canvas);
                launch.snapshot = bmp;
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "launch snapshot failed", error);
        }
    }

    private static void prepareDetailsWindowSurface(Activity activity) {
        if (activity == null) return;
        try {
            android.view.Window window = activity.getWindow();
            if (window == null) return;
            final int pageColor = CigramTheme.page(activity);   // Light mode: no dark flash behind the page
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(pageColor));
            android.view.WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.format = android.graphics.PixelFormat.OPAQUE;
            window.setAttributes(attrs);
            View decor = window.getDecorView();
            if (decor != null) decor.setBackgroundColor(pageColor);
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "prepareDetailsWindowSurface failed", error);
        }
    }

    private static final Map<Activity, Boolean> DETAILS_WINDOW_PREPARED =
            new WeakHashMap<Activity, Boolean>();

    public static void prepareDetailsWindow(final Activity activity) {
        if (activity == null || !isAlive(activity)) return;
        // Runs once per screen: a second call (bind from two entry points) used to hide the
        // already visible page again and restart the fade = visible flash on open.
        synchronized (DETAILS_WINDOW_PREPARED) {
            if (DETAILS_WINDOW_PREPARED.containsKey(activity)) return;
            DETAILS_WINDOW_PREPARED.put(activity, Boolean.TRUE);
        }
        try {
            prepareDetailsWindowSurface(activity);
            final android.view.Window window = activity.getWindow();

            View root = findByIdName(activity, "vscroll1");
            if (root != null) root.setBackgroundColor(0xFF0E0E10);
            View page = findByIdName(activity, "linear1");
            if (page != null) page.setBackgroundColor(0xFF0E0E10);

            View poster = findByIdName(activity, "imageview_details_poster");
            if (Build.VERSION.SDK_INT >= 21 && poster != null) {
                poster.setTransitionName(detailsTransitionName(activity.getIntent()));
            }

            // Netflix-style open: the poster grows from its card into the page (startDetailsFlight)
            // while the page fades in under it. Opens that did not come from a card (link, push,
            // notification) have no source rectangle: they get a short plain fade instead.
            final View content = activity.findViewById(android.R.id.content);
            if (content != null) {
                content.animate().cancel();
                content.setScaleX(1f);
                content.setScaleY(1f);
                if (!beginDetailsFlight(activity) && !DETAILS_SYSTEM_MOTION) {
                    content.setAlpha(0f);
                    content.animate().alpha(1f).setDuration(140L).start();
                } else if (DETAILS_SYSTEM_MOTION) {
                    content.animate().cancel();
                    content.setAlpha(1f);   // visible at once: the window animation fades the whole page in
                }
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "prepareDetailsWindow failed", error);
        }
    }

    // ---- Self-driven poster flight --------------------------------------------------
    // Works the same on every device: no dependency on window feature flags, theme or
    // vendor ROM handling of scene transitions. Skipped on low-RAM devices or when the
    // user turned animations off (content then simply fades in).
    /** v10.79: open/close title pages with the window animations (cg_details_*) instead of the poster flight. */
    private static final boolean DETAILS_SYSTEM_MOTION = true;

    private static final class DetailsFlight {
        DetailsLaunch launch;
        ImageView overlay;
        ImageView backdrop;
        boolean started;
        boolean done;
        boolean returning;
    }

    private static final Map<Activity, DetailsFlight> DETAILS_FLIGHTS =
            new WeakHashMap<Activity, DetailsFlight>();

    private static DetailsFlight detailsFlight(Activity activity) {
        synchronized (DETAILS_FLIGHTS) {
            return DETAILS_FLIGHTS.get(activity);
        }
    }

    private static boolean detailsMotionAllowed(Context context) {
        if (context == null) return false;
        try {
            float scale = android.provider.Settings.Global.getFloat(
                    context.getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            if (scale <= 0f) return false;
        } catch (Throwable ignored) { }
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.isLowRamDevice()) return false;
        } catch (Throwable ignored) { }
        return true;
    }

    private static boolean beginDetailsFlight(final Activity activity) {
        if (DETAILS_SYSTEM_MOTION) return false;   // v10.79: the window animation does the open (see openDetailsWithTransition)
        if (activity == null) return false;
        DetailsLaunch launch = detailsLaunch(activity.getIntent());
        if (launch == null || !launch.hasRect || !detailsMotionAllowed(activity)) return false;
        if (detailsFlight(activity) != null) return true;
        View content = activity.findViewById(android.R.id.content);
        if (content == null) return false;
        DetailsFlight flight = new DetailsFlight();
        flight.launch = launch;
        synchronized (DETAILS_FLIGHTS) {
            DETAILS_FLIGHTS.put(activity, flight);
        }
        content.animate().cancel();
        content.setAlpha(0f);
        // Fill the gap immediately: the page the user came from (dimmed) and the tapped poster at its
        // own place, instead of an empty black window until this page has built its own poster.
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            android.graphics.Bitmap snap = launch.snapshot;
            if (snap != null && !snap.isRecycled()) {
                ImageView backdrop = new ImageView(activity);
                backdrop.setScaleType(ImageView.ScaleType.FIT_XY);
                backdrop.setImageBitmap(snap);
                backdrop.setColorFilter(0x99000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
                decor.addView(backdrop, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                flight.backdrop = backdrop;
            }
            android.graphics.Bitmap cardBitmap = getSharedDetailsBitmap(launch.imageUrl);
            if (cardBitmap != null && !cardBitmap.isRecycled()) {
                flight.overlay = detailsFlightOverlay(activity, decor, cardBitmap,
                        new int[]{launch.srcLeft, launch.srcTop}, launch.srcWidth, launch.srcHeight);
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "early flight layers failed", error);
        }
        // Safety net: whatever happens (poster not loaded yet, busy thread) the page is visible after 700 ms.
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                finishDetailsFlight(activity);
            }
        }, 700L);
        return true;
    }

    private static void finishDetailsFlight(Activity activity) {
        if (activity == null) return;
        DetailsFlight flight = detailsFlight(activity);
        try {
            if (flight != null) {
                if (flight.done) return;
                flight.done = true;
                if (flight.overlay != null) {
                    ViewGroup parent = (ViewGroup) flight.overlay.getParent();
                    if (parent != null) parent.removeView(flight.overlay);
                    flight.overlay = null;
                }
                if (flight.backdrop != null) {
                    final ImageView old = flight.backdrop;
                    flight.backdrop = null;
                    old.animate().cancel();
                    old.animate().alpha(0f).setDuration(120L).withEndAction(new Runnable() {
                        @Override public void run() {
                            ViewGroup parent = (ViewGroup) old.getParent();
                            if (parent != null) parent.removeView(old);
                        }
                    }).start();
                }
            }
            View poster = findByIdName(activity, "imageview_details_poster");
            if (poster != null) poster.setAlpha(1f);
            View content = activity.findViewById(android.R.id.content);
            if (content != null && content.getAlpha() < 1f) {
                content.animate().cancel();
                content.setAlpha(1f);
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "finishDetailsFlight failed", error);
        }
    }

    private static ImageView detailsFlightOverlay(
            Activity activity, ViewGroup decor, android.graphics.Bitmap bitmap,
            int[] posterOnScreen, int width, int height) {
        final float radius = dp(activity, 14f);
        ImageView overlay = new ImageView(activity);
        overlay.setScaleType(ImageView.ScaleType.CENTER_CROP);
        overlay.setImageBitmap(bitmap);
        overlay.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        overlay.setClipToOutline(true);
        overlay.setElevation(dp(activity, 16f));
        int[] decorOnScreen = new int[2];
        decor.getLocationOnScreen(decorOnScreen);
        android.widget.FrameLayout.LayoutParams lp =
                new android.widget.FrameLayout.LayoutParams(width, height);
        lp.leftMargin = posterOnScreen[0] - decorOnScreen[0];
        lp.topMargin = posterOnScreen[1] - decorOnScreen[1];
        decor.addView(overlay, lp);
        overlay.setPivotX(0f);
        overlay.setPivotY(0f);
        return overlay;
    }

    private static android.graphics.Bitmap detailsFlightBitmap(DetailsLaunch launch, ImageView poster) {
        android.graphics.Bitmap bitmap = launch == null ? null : getSharedDetailsBitmap(launch.imageUrl);
        if ((bitmap == null || bitmap.isRecycled()) && poster != null
                && poster.getDrawable() instanceof android.graphics.drawable.BitmapDrawable) {
            bitmap = ((android.graphics.drawable.BitmapDrawable) poster.getDrawable()).getBitmap();
        }
        return bitmap != null && !bitmap.isRecycled() ? bitmap : null;
    }

    // Returns true when the flight was started (caller should redraw).
    private static boolean startDetailsFlight(final Activity activity, final ImageView poster) {
        final DetailsFlight flight = detailsFlight(activity);
        if (flight == null || flight.started || flight.done || poster == null
                || !isAlive(activity)) return false;
        flight.started = true;
        try {
            final DetailsLaunch launch = flight.launch;
            android.graphics.Bitmap bitmap = detailsFlightBitmap(launch, poster);
            if (bitmap == null || poster.getWidth() <= 0 || poster.getHeight() <= 0) {
                finishDetailsFlight(activity);
                return false;
            }
            if (!(poster.getDrawable() instanceof android.graphics.drawable.BitmapDrawable)) {
                poster.setImageBitmap(bitmap);
            }
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            int[] at = new int[2];
            poster.getLocationOnScreen(at);
            int width = poster.getWidth();
            int height = poster.getHeight();
            poster.setAlpha(0f);
            ImageView early = flight.overlay;
            if (early != null && launch.srcWidth > 0 && launch.srcHeight > 0) {
                // the tapped poster is already on screen at its card: grow it to this page's poster
                early.animate()
                        .scaleX((float) width / launch.srcWidth)
                        .scaleY((float) height / launch.srcHeight)
                        .translationX(at[0] - launch.srcLeft)
                        .translationY(at[1] - launch.srcTop)
                        .setDuration(300L)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(2.2f))
                        .withEndAction(new Runnable() {
                            @Override public void run() { finishDetailsFlight(activity); }
                        }).start();
            } else {
                ImageView overlay = detailsFlightOverlay(activity, decor, bitmap, at, width, height);
                flight.overlay = overlay;
                overlay.setScaleX((float) launch.srcWidth / width);
                overlay.setScaleY((float) launch.srcHeight / height);
                overlay.setTranslationX(launch.srcLeft - at[0]);
                overlay.setTranslationY(launch.srcTop - at[1]);
                overlay.animate()
                        .scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                        .setDuration(300L)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(2.2f))
                        .withEndAction(new Runnable() {
                            @Override public void run() { finishDetailsFlight(activity); }
                        }).start();
            }
            if (flight.backdrop != null) {
                flight.backdrop.animate().alpha(0.35f).setStartDelay(120L).setDuration(260L).start();
            }

            View content = activity.findViewById(android.R.id.content);
            if (content != null) {
                content.animate().cancel();
                content.animate().alpha(1f).setStartDelay(90L).setDuration(220L).start();
            }
            return true;
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "startDetailsFlight failed", error);
            finishDetailsFlight(activity);
            return false;
        }
    }

    // Back: the page settles back (fade + slight shrink) over the page we came from, then closes.
    private static boolean startDetailsReturnFlight(final Activity activity) {
        final DetailsFlight flight = detailsFlight(activity);
        if (flight == null || !flight.started || flight.returning || flight.launch == null
                || !detailsMotionAllowed(activity)) return false;
        try {
            final android.graphics.Bitmap snap = flight.launch.snapshot;
            if (snap == null || snap.isRecycled()) return false;   // nothing to show behind: plain close
            flight.returning = true;
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            if (flight.overlay != null) {
                ViewGroup old = (ViewGroup) flight.overlay.getParent();
                if (old != null) old.removeView(flight.overlay);
                flight.overlay = null;
            }
            ImageView back = new ImageView(activity);
            back.setScaleType(ImageView.ScaleType.FIT_XY);
            back.setImageBitmap(snap);
            // under the page (index 0), above the window colour: the page fades and the old page shows through
            decor.addView(back, 0, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            flight.backdrop = back;

            final Runnable close = new Runnable() {
                @Override public void run() {
                    try {
                        if (!activity.isFinishing()) {
                            activity.finish();
                            activity.overridePendingTransition(0, 0);
                        }
                    } catch (Throwable ignored) { }
                }
            };
            View content = activity.findViewById(android.R.id.content);
            if (content != null) {
                content.animate().cancel();
                content.setPivotX(content.getWidth() / 2f);
                content.setPivotY(content.getHeight() * 0.4f);
                content.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(190L)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                        .withEndAction(close).start();
            } else {
                close.run();
            }
            MAIN.postDelayed(close, 500L);
            return true;
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "return flight failed", error);
            return false;
        }
    }

    public static void startDetailsPostponedEnter(final Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 21) return;
        try {
            activity.startPostponedEnterTransition();
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "startPostponedEnterTransition failed", error);
        }
    }

    public static void postDetailsStage(
            final Activity activity,
            final String name,
            long delayMs,
            final Runnable action
    ) {
        if (activity == null || action == null) return;
        final Runnable guarded = new Runnable() {
            @Override public void run() {
                if (!isAlive(activity)) return;
                try {
                    action.run();
                } catch (Throwable error) {
                    android.util.Log.w("CigramDetails", "stage " + name + " failed", error);
                }
            }
        };
        View anchor = findByIdName(activity, "vscroll1");
        if (anchor == null) anchor = activity.findViewById(android.R.id.content);
        if (anchor != null) anchor.post(guarded); else MAIN.post(guarded);
    }

    private static void putDetailsExtraFromJson(
            android.content.Intent intent,
            JSONObject item,
            String outputKey,
            String... inputKeys
    ) {
        if (intent == null || item == null || outputKey == null || inputKeys == null) return;
        String value = jsonText(item, inputKeys);
        if (value != null && value.trim().length() > 0) {
            intent.putExtra(outputKey, value.trim());
        }
    }

    public static void putMinimalDetailsExtras(
            android.content.Intent intent,
            JSONObject item,
            String fallbackTitle
    ) {
        if (intent == null) return;
        JSONObject safe = item == null ? new JSONObject() : copyDetailsJson(item);
        try {
            String type = inferContentType(safe, "");
            String id = "series".equals(type)
                    ? jsonText(safe, "series_id", "id", "movie_id", "item_id", "content_id")
                    : jsonText(safe, "id", "movie_id", "item_id", "content_id");
            if (id.length() == 0) {
                String seed = relatedIdentityKey(safe);
                if (seed.length() == 0) seed = clean(fallbackTitle);
                id = "local_" + Integer.toHexString(seed.hashCode());
                if ("series".equals(type)) safe.put("series_id", id);
                safe.put("id", id);
            }
            safe.put("type", type);
            warmDetailsItem(safe);
            intent.putExtra("id", id);
            intent.putExtra("type", type);
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "minimal id/type extras failed", error);
        }
    }

    public static void putMinimalDetailsExtras(
            android.content.Intent intent,
            java.util.Map<String, ?> item,
            String fallbackTitle
    ) {
        JSONObject object = new JSONObject();
        if (item != null) {
            try {
                for (java.util.Map.Entry<String, ?> entry : item.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    object.put(entry.getKey(), entry.getValue());
                }
            } catch (Throwable error) {
                android.util.Log.w("CigramDetails", "Map to details JSON failed", error);
            }
        }
        putMinimalDetailsExtras(intent, object, fallbackTitle);
    }

    public static boolean prepareDetailsActivity(Activity activity) {
        if (activity == null || !isAlive(activity)) return false;

        synchronized (DETAILS_DATA_CACHE) {
            if (DETAILS_DATA_CACHE.containsKey(activity)) {
                JSONObject cached = DETAILS_DATA_CACHE.get(activity);
                return cached != null && cached.length() > 0;
            }
        }

        android.content.Intent intent = activity.getIntent();
        String wantedType = intent == null ? "" : clean(intent.getStringExtra("type"));
        String wantedId = intent == null ? "" : clean(intent.getStringExtra("id"));
        JSONObject memory = findCatalogMemoryItem(wantedType, wantedId, "");
        JSONObject warm = detailsWarmLookup(intent);
        JSONObject merged = memory != null
                ? copyDetailsJson(memory)
                : warm == null ? new JSONObject() : copyDetailsJson(warm);
        if (warm != null) merged = mergeDetailsObjects(merged, warm);
        mergeIntentDetails(merged, intent);

        try {
            if (jsonText(merged, "type").length() == 0) {
                merged.put("type", inferContentType(merged, ""));
            }
            if ("series".equals(inferContentType(merged, ""))) {
                String sid = jsonText(merged, "series_id", "id");
                if (sid.length() > 0) merged.put("series_id", sid);
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramDetails", "Fast details merge failed", error);
        }
        restoreSeriesIdentity(merged, null);

        synchronized (DETAILS_DATA_CACHE) {
            DETAILS_DATA_CACHE.put(activity, merged);
        }

        if (merged.length() > 0) {
            android.util.Log.w(
                    "CigramDetails",
                    warm != null
                            ? "Details resolved from warm cache"
                            : "Details resolved from intent; catalog enrichment continues in background"
            );
            return true;
        }

        android.util.Log.w(
                "CigramDetails",
                "No immediate details payload; catalog enrichment continues in background"
        );
        return false;
    }

    public static String detailsValue(Activity activity, String... keys) {
        if (activity == null || keys == null) return "";
        prepareDetailsActivity(activity);

        android.content.Intent intent = activity.getIntent();
        if (intent != null) {
            for (String key : keys) {
                if (key == null) continue;
                try {
                    String value = intent.getStringExtra(key);
                    if (value != null && value.trim().length() > 0) return value.trim();
                } catch (Throwable error) {
                    android.util.Log.w("CigramDetails", "Intent read failed for " + key, error);
                }
            }
        }

        JSONObject item = resolvedDetailsItem(activity);
        for (String key : keys) {
            if (key == null || item == null) continue;
            Object value = item.opt(key);
            if (value != null && value != JSONObject.NULL) {
                String text = String.valueOf(value).trim();
                if (text.length() > 0) return text;
            }
        }
        return "";
    }

    private static JSONObject resolvedDetailsItem(Activity activity) {
        if (activity == null) return new JSONObject();
        prepareDetailsActivity(activity);
        synchronized (DETAILS_DATA_CACHE) {
            JSONObject item = DETAILS_DATA_CACHE.get(activity);
            if (item == null) return new JSONObject();
            try {
                return new JSONObject(item.toString());
            } catch (Throwable error) {
                return item;
            }
        }
    }

    private static JSONObject findDetailsCatalogItem(Activity activity) throws Exception {
        if (activity == null) return null;
        android.content.Intent intent = activity.getIntent();
        if (intent == null) return null;

        JSONObject memory = findCatalogMemoryItem(
                intent.getStringExtra("type"),
                intent.getStringExtra("id"),
                intent.getStringExtra("title"));
        if (memory != null && memory.length() > 0) return memory;

        String raw = catalogPrefs(activity).getString(KEY_JSON, "");
        if (!isValidCatalog(raw)) return null;

        Object root = new JSONTokener(raw).nextValue();
        JSONArray index = buildSearchIndex(root);
        if (index == null || index.length() == 0) return null;

        String wantedType = normalizeSearch(intent.getStringExtra("type"));
        String wantedId = normalizeSearch(intent.getStringExtra("id"));
        String wantedSeriesId = normalizeSearch(intent.getStringExtra("series_id"));
        String wantedTitle = normalizeSearch(intent.getStringExtra("title"));

        JSONObject titleMatch = null;
        for (int i = 0; i < index.length(); i++) {
            JSONObject item = index.optJSONObject(i);
            if (item == null) continue;

            String type = inferContentType(item, "");
            String itemId = normalizeSearch(jsonText(item, "id", "movie_id", "item_id", "content_id"));
            String itemSeriesId = normalizeSearch(jsonText(item, "series_id", "id"));
            String itemTitle = normalizeSearch(jsonText(
                    item, "series_title", "title", "name", "title_ar", "title_en"));

            if ("series".equals(wantedType) || wantedSeriesId.length() > 0) {
                if (wantedSeriesId.length() > 0
                        && (wantedSeriesId.equals(itemSeriesId) || wantedSeriesId.equals(itemId))) {
                    return item;
                }
                if (wantedId.length() > 0
                        && (wantedId.equals(itemSeriesId) || wantedId.equals(itemId))) {
                    return item;
                }
            } else if (wantedId.length() > 0 && wantedId.equals(itemId)) {
                return item;
            }

            if (titleMatch == null && wantedTitle.length() > 0 && wantedTitle.equals(itemTitle)) {
                if (wantedType.length() == 0 || wantedType.equals(type)) titleMatch = item;
            }
        }
        return titleMatch;
    }
    public static void showDetailsError(final Activity activity, final String message) {
        if (activity == null || !isAlive(activity)) return;
        final String safeMessage = message == null || message.trim().length() == 0
                ? "تعذر تحميل صفحة التفاصيل"
                : message.trim();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(new Runnable() {
                @Override public void run() {
                    if (isAlive(activity)) {
                        Toast.makeText(activity, safeMessage, Toast.LENGTH_SHORT).show();
                    }
                }
            });
            return;
        }
        Toast.makeText(activity, safeMessage, Toast.LENGTH_SHORT).show();
    }

    public static void detailsBlockFailed(Activity activity, String blockId, Throwable error) {
        android.util.Log.w(
                "CigramDetails",
                "onCreate block " + blockId + " failed",
                error
        );
        showDetailsError(activity, "تعذر تحميل جزء من صفحة التفاصيل. يمكنك الرجوع والمحاولة مرة أخرى.");
    }

    private static SharedPreferences catalogPrefs(Context context) {
        return context.getSharedPreferences(PREF_CATALOG, Context.MODE_PRIVATE);
    }

    private static boolean isOnline(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return true; // can't tell -> let the request itself decide

            if (Build.VERSION.SDK_INT >= 23) {
                Network network = manager.getActiveNetwork();
                if (network == null) return false;
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                // Require only that the active transport claims INTERNET.
                // Do NOT require NET_CAPABILITY_VALIDATED: Android sets that flag only after
                // its own reachability probe to Google endpoints succeeds. That probe is
                // commonly blocked, throttled or simply slow (cold start, some ISPs, VPNs,
                // captive-portal checks) in regions where the Cloudflare catalog API is still
                // perfectly reachable. Requiring VALIDATED made the app treat a working
                // connection as "offline" and skip the catalog download, so no movies, series
                // or sections ever appeared. The real fetchCatalog() already reports genuine
                // offline cases through UnknownHostException / ConnectException / timeout.
                return caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }

            NetworkInfo info = manager.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable ignored) {
            return true; // on any error, don't block the fetch; the HTTP call decides
        }
    }

    private static void refreshOfflineBanner(final Activity activity) {
        if (!isAlive(activity)) return;
        IO.execute(new Runnable() {
            @Override public void run() {
                final boolean hasCache = isValidCatalog(
                        catalogPrefs(activity).getString(KEY_JSON, "")
                );
                final boolean offline = !isOnline(activity);
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (!isAlive(activity)) return;
                        if (hasCache && offline) showOfflineBanner(activity);
                        else removeTagged(activity, "cigram_stage1_offline_banner");
                    }
                });
            }
        });
    }

    private static void showOfflineBanner(Activity activity) {
        FrameLayout root = contentRoot(activity);
        if (root == null) return;
        removeTagged(activity, "cigram_offline_screen");
        if (root.findViewWithTag("cigram_stage1_offline_banner") != null) return;

        TextView banner = text(
                activity,
                "أنت تتصفح بدون اتصال",
                13f,
                Color.WHITE,
                sima(activity)
        );
        banner.setTag("cigram_stage1_offline_banner");
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(activity, 12f), dp(activity, 8f), dp(activity, 12f), dp(activity, 8f));
        banner.setBackgroundColor(0xE6111B22);
        banner.setElevation(dp(activity, 12f));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
        );
        root.addView(banner, lp);
    }

    private static void showRetryError(
            final Activity activity,
            final String tag,
            String message,
            final Runnable retryAction
    ) {
        if (!isAlive(activity)) return;
        FrameLayout root = contentRoot(activity);
        if (root == null) return;
        removeTagged(activity, tag);
        removeTagged(activity, "cigram_offline_screen");
        removeTagged(activity, "cigram_home_retry_box");

        final float density = activity.getResources().getDisplayMetrics().density;
        Typeface font = sima(activity);

        FrameLayout overlay = new FrameLayout(activity);
        overlay.setTag(tag);
        overlay.setBackgroundColor(0xF50E0E10);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setElevation(px(density, 100));

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(px(density, 26), px(density, 24), px(density, 26), px(density, 24));

        final TextView status = text(activity, message, 16f, Color.WHITE, font);
        status.setGravity(Gravity.CENTER);
        panel.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        final ProgressBar loading = new ProgressBar(activity);
        loading.setVisibility(View.GONE);
        LinearLayout.LayoutParams loadingLp = new LinearLayout.LayoutParams(
                px(density, 34), px(density, 34)
        );
        loadingLp.gravity = Gravity.CENTER_HORIZONTAL;
        loadingLp.topMargin = px(density, 16);
        panel.addView(loading, loadingLp);

        final TextView retry = pillButton(activity, "إعادة المحاولة", 0xFFE21B14, font);
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                px(density, 180), px(density, 48)
        );
        retryLp.gravity = Gravity.CENTER_HORIZONTAL;
        retryLp.topMargin = px(density, 18);
        panel.addView(retry, retryLp);

        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                retry.setEnabled(false);
                loading.setVisibility(View.VISIBLE);
                status.setText("جاري إعادة المحاولة...");
                MAIN.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (isAlive(activity)) retryAction.run();
                    }
                }, 80L);
            }
        });

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        overlay.addView(panel, panelLp);
        FrameLayout.LayoutParams overlayLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        // keep the tab bar reachable: this panel is clickable and used to sit on top of it
        overlayLp.bottomMargin = CigramBottomNav.reservedBottom(activity);
        root.addView(overlay, overlayLp);
    }

    private static void showLoadingMessage(Activity activity, String tag, String message) {
        if (!isAlive(activity)) return;
        FrameLayout root = contentRoot(activity);
        if (root == null) return;
        removeTagged(activity, tag);

        final float density = activity.getResources().getDisplayMetrics().density;
        LinearLayout panel = new LinearLayout(activity);
        panel.setTag(tag);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(px(density, 24), px(density, 30), px(density, 24), px(density, 30));
        panel.setBackgroundColor(0xF00E0E10);

        ProgressBar progress = new ProgressBar(activity);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(px(density, 38), px(density, 38));
        p.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(progress, p);

        TextView status = text(activity, message, 15f, Color.WHITE, sima(activity));
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams s = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        s.topMargin = px(density, 12);
        panel.addView(status, s);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        root.addView(panel, lp);
    }

    private static void showTransientTopProgress(Activity activity, boolean show) {
        FrameLayout root = contentRoot(activity);
        if (root == null) return;
        View old = root.findViewWithTag("cigram_stage1_top_progress");
        if (!show) {
            if (old != null && old.getParent() instanceof ViewGroup) {
                ((ViewGroup) old.getParent()).removeView(old);
            }
            return;
        }
        if (old != null) return;

        ProgressBar bar = new ProgressBar(activity);
        bar.setTag("cigram_stage1_top_progress");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(activity, 32f),
                dp(activity, 32f),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL
        );
        lp.topMargin = dp(activity, 8f);
        root.addView(bar, lp);
    }

    private static boolean isAlive(Activity activity) {
        if (activity == null || activity.isFinishing()) return false;
        return Build.VERSION.SDK_INT < 17 || !activity.isDestroyed();
    }

    private static FrameLayout contentRoot(Activity activity) {
        try {
            View root = activity.findViewById(android.R.id.content);
            if (root instanceof FrameLayout) return (FrameLayout) root;
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "contentRoot", ignored); }
        return null;
    }

    private static View findByIdName(Activity activity, String name) {
        try {
            int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
            return id == 0 ? null : activity.findViewById(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class ViewParentInfo {
        final ViewGroup parent;
        final int index;
        ViewParentInfo(ViewGroup parent, int index) {
            this.parent = parent;
            this.index = index;
        }
    }

    private static ViewParentInfo parentInfo(View view) {
        if (view != null && view.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view.getParent();
            return new ViewParentInfo(parent, parent.indexOfChild(view));
        }
        return new ViewParentInfo(null, -1);
    }

    private static View findPlayerView(Activity activity) {
        FrameLayout root = contentRoot(activity);
        if (root == null) return null;

        ArrayDeque<View> queue = new ArrayDeque<View>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View view = queue.removeFirst();
            String className = view.getClass().getName();
            if (className.endsWith(".PlayerView")
                    || className.equals("com.google.android.exoplayer2.ui.PlayerView")) {
                return view;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }
        return null;
    }

    private static Object getFieldValue(Object object, String name) {
        if (object == null) return null;
        Class<?> type = object.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static boolean setFieldValue(Object object, String name, Object value) {
        if (object == null) return false;
        Class<?> type = object.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(object, value);
                return true;
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return false;
    }

    private static Object findFieldObjectByTypeName(Object object, String part) {
        if (object == null) return null;
        Class<?> type = object.getClass();
        while (type != null) {
            Field[] fields;
            try { fields = type.getDeclaredFields(); }
            catch (Throwable error) { fields = new Field[0]; }
            for (Field field : fields) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);
                    if (value != null && value.getClass().getName().contains(part)) {
                        return value;
                    }
                } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "findFieldObjectByTypeName", ignored); }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        Class<?> cursor = type;
        while (cursor != null) {
            Method[] methods;
            try { methods = cursor.getDeclaredMethods(); }
            catch (Throwable error) { methods = new Method[0]; }
            for (Method method : methods) {
                if (name.equals(method.getName())
                        && method.getParameterTypes().length == parameterCount) {
                    return method;
                }
            }
            cursor = cursor.getSuperclass();
        }
        try {
            for (Method method : type.getMethods()) {
                if (name.equals(method.getName())
                        && method.getParameterTypes().length == parameterCount) {
                    return method;
                }
            }
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "getParameterTypes", ignored); }
        return null;
    }

    private static Method findCompatibleLongMethod(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                for (Method method : cursor.getDeclaredMethods()) {
                    Class<?>[] p = method.getParameterTypes();
                    if (name.equals(method.getName())
                            && p.length == 1
                            && (p[0] == Long.TYPE || p[0] == Long.class)) {
                        return method;
                    }
                }
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "findCompatibleLongMethod", ignored); }
            cursor = cursor.getSuperclass();
        }
        try {
            for (Method method : type.getMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (name.equals(method.getName())
                        && p.length == 1
                        && (p[0] == Long.TYPE || p[0] == Long.class)) {
                    return method;
                }
            }
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "findCompatibleLongMethod", ignored); }
        return null;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method method = findMethod(target.getClass(), methodName, 0);
            if (method == null) return null;
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (Throwable ignored) { return fallback; }
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Throwable ignored) { return fallback; }
    }

    private static void setSwipeRefreshing(Object swipe, boolean refreshing) {
        if (swipe == null) return;
        try {
            Method method = swipe.getClass().getMethod("setRefreshing", Boolean.TYPE);
            method.invoke(swipe, Boolean.valueOf(refreshing));
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "setSwipeRefreshing", ignored); }
    }

    private static final class Stage5ChromeState {
        final java.util.ArrayList<View> normalViews = new java.util.ArrayList<View>();
        final java.util.ArrayList<Integer> normalVisibilities = new java.util.ArrayList<Integer>();
        android.app.ActionBar frameworkActionBar;
        boolean frameworkActionBarWasShowing;
        Object supportActionBar;
        boolean supportActionBarWasShowing;
        View adCover;
        boolean restored;
    }

    private static Stage5ChromeState stage5BeginAdChrome(
            Activity activity, Stage5ChromeState current) {
        if (activity == null || !isAlive(activity)) return current;
        Stage5ChromeState state = current;
        if (state == null) {
            state = new Stage5ChromeState();
            stage5SnapshotActionBars(activity, state);
            View decor = null;
            try {
                if (activity.getWindow() != null) {
                    decor = activity.getWindow().getDecorView();
                }
            } catch (Throwable ignored) {
                android.util.Log.w("CigramStage5", "Unable to read decor before ad", ignored);
            }
            if (decor != null) stage5SnapshotCigramViews(activity, decor, decor, state);
        }
        if (state.adCover == null) state.adCover = CigramAdCover.show(activity);
        stage5EnforceAdChrome(activity, state);
        return state;
    }

    private static void stage5SnapshotActionBars(Activity activity, Stage5ChromeState state) {
        try {
            android.app.ActionBar bar = activity.getActionBar();
            if (bar != null) {
                state.frameworkActionBar = bar;
                state.frameworkActionBarWasShowing = bar.isShowing();
                bar.hide();
            }
        } catch (Throwable ignored) {
            android.util.Log.w("CigramStage5", "Framework ActionBar snapshot failed", ignored);
        }

        try {
            Method getter = findMethod(activity.getClass(), "getSupportActionBar", 0);
            if (getter != null) {
                getter.setAccessible(true);
                Object bar = getter.invoke(activity);
                if (bar != null) {
                    state.supportActionBar = bar;
                    state.supportActionBarWasShowing = stage5ActionBarShowing(bar);
                    stage5SetActionBarVisible(bar, false);
                }
            }
        } catch (Throwable ignored) {
            android.util.Log.w("CigramStage5", "Support ActionBar snapshot failed", ignored);
        }
    }

    private static void stage5EnforceAdChrome(Activity activity, Stage5ChromeState state) {
        if (activity == null || state == null || state.restored || !isAlive(activity)) return;
        try {
            android.app.ActionBar bar = activity.getActionBar();
            if (bar != null) bar.hide();
        } catch (Throwable ignored) {
            android.util.Log.w("CigramStage5", "Framework ActionBar hide failed", ignored);
        }
        try {
            Method getter = findMethod(activity.getClass(), "getSupportActionBar", 0);
            if (getter != null) {
                getter.setAccessible(true);
                Object bar = getter.invoke(activity);
                if (bar != null) stage5SetActionBarVisible(bar, false);
            }
        } catch (Throwable ignored) {
            android.util.Log.w("CigramStage5", "Support ActionBar hide failed", ignored);
        }

        View decor = null;
        try {
            if (activity.getWindow() != null) decor = activity.getWindow().getDecorView();
        } catch (Throwable ignored) {
            android.util.Log.w("CigramStage5", "Unable to read decor during ad", ignored);
        }
        if (decor != null) stage5HideCigramViews(activity, decor, decor);
    }

    private static void stage5RestoreAdChrome(Stage5ChromeState state) {
        if (state == null || state.restored) return;
        state.restored = true;
        CigramAdCover.hide(state.adCover);
        state.adCover = null;
        for (int i = 0; i < state.normalViews.size() && i < state.normalVisibilities.size(); i++) {
            View view = state.normalViews.get(i);
            Integer visibility = state.normalVisibilities.get(i);
            if (view == null || visibility == null) continue;
            try {
                view.setVisibility(visibility.intValue());
            } catch (Throwable ignored) {
                android.util.Log.w("CigramStage5", "Cigram chrome restore failed", ignored);
            }
        }
        try {
            if (state.frameworkActionBar != null) {
                if (state.frameworkActionBarWasShowing) state.frameworkActionBar.show();
                else state.frameworkActionBar.hide();
            }
        } catch (Throwable ignored) {
            android.util.Log.w("CigramStage5", "Framework ActionBar restore failed", ignored);
        }
        if (state.supportActionBar != null) {
            stage5SetActionBarVisible(state.supportActionBar, state.supportActionBarWasShowing);
        }
    }

    private static boolean stage5ActionBarShowing(Object bar) {
        if (bar == null) return false;
        try {
            Method method = findMethod(bar.getClass(), "isShowing", 0);
            if (method != null) {
                method.setAccessible(true);
                Object value = method.invoke(bar);
                if (value instanceof Boolean) return ((Boolean) value).booleanValue();
            }
        } catch (Throwable ignored) {
            android.util.Log.w("CigramStage5", "ActionBar state read failed", ignored);
        }
        return true;
    }

    private static void stage5SetActionBarVisible(Object bar, boolean visible) {
        if (bar == null) return;
        try {
            Method method = findMethod(bar.getClass(), visible ? "show" : "hide", 0);
            if (method != null) {
                method.setAccessible(true);
                method.invoke(bar);
            }
        } catch (Throwable ignored) {
            android.util.Log.w("CigramStage5", "ActionBar visibility change failed", ignored);
        }
    }

    private static void stage5SnapshotCigramViews(
            Context context, View root, View decor, Stage5ChromeState state) {
        if (root == null || state == null) return;
        if (root instanceof TextView && stage5IsCigramLabel((TextView) root)) {
            View target = stage5ResolveCigramBar(context, (TextView) root, decor);
            if (target != null && !state.normalViews.contains(target)) {
                state.normalViews.add(target);
                state.normalVisibilities.add(Integer.valueOf(target.getVisibility()));
                try { target.setVisibility(View.GONE); }
                catch (Throwable ignored) {
                    android.util.Log.w("CigramStage5", "Cigram bar snapshot hide failed", ignored);
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                stage5SnapshotCigramViews(context, group.getChildAt(i), decor, state);
            }
        }
    }

    private static void stage5HideCigramViews(Context context, View root, View decor) {
        if (root == null) return;
        if (root instanceof TextView && stage5IsCigramLabel((TextView) root)) {
            View target = stage5ResolveCigramBar(context, (TextView) root, decor);
            if (target != null) {
                try { target.setVisibility(View.GONE); }
                catch (Throwable ignored) {
                    android.util.Log.w("CigramStage5", "Cigram bar hide failed", ignored);
                }
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                stage5HideCigramViews(context, group.getChildAt(i), decor);
            }
        }
    }

    private static boolean stage5IsCigramLabel(TextView view) {
        if (view == null) return false;
        CharSequence raw = view.getText();
        if (raw == null) return false;
        String value = String.valueOf(raw)
                .replace("\u200E", "")
                .replace("\u200F", "")
                .replace(" ", "")
                .replace("\n", "")
                .replace("\r", "")
                .trim();
        return "cigram".equalsIgnoreCase(value);
    }

    private static View stage5ResolveCigramBar(Context context, TextView label, View decor) {
        View candidate = label;
        int maxHeight = dp(context, 120);
        int rootWidth = decor == null ? 0 : decor.getWidth();
        android.view.ViewParent parent = label.getParent();
        for (int level = 0; level < 2 && parent instanceof View; level++) {
            View parentView = (View) parent;
            if (parentView == decor) break;
            ViewGroup.LayoutParams lp = parentView.getLayoutParams();
            int height = parentView.getHeight();
            int width = parentView.getWidth();
            boolean compactHeight = height > 0
                    ? height <= maxHeight
                    : lp == null
                        || lp.height == ViewGroup.LayoutParams.WRAP_CONTENT
                        || (lp.height > 0 && lp.height <= maxHeight);
            boolean wideEnough = rootWidth <= 0
                    || width <= 0
                    || width >= (int) (rootWidth * 0.45f)
                    || (lp != null && lp.width == ViewGroup.LayoutParams.MATCH_PARENT);
            if (!compactHeight || !wideEnough) break;
            candidate = parentView;
            parent = parentView.getParent();
        }
        return candidate;
    }

    private static void setupStage5SupportAd(final Activity activity, final int attempt) {
        if (!isAlive(activity)) return;

        View root;
        try {
            root = activity.findViewById(android.R.id.content);
        } catch (Throwable ignored) {
            root = null;
        }
        if (root == null) return;

        // Only the real support page. Matching on screen text would also pick up the "Help &
        // Support" row of the Account page and could bind the ad logic to the wrong view.
        String name = activity.getClass().getSimpleName();
        boolean supportPage = "AdsActivity".equals(name) || "Ads".equals(name);

        if (!supportPage) {
            if (attempt < 3) {
                MAIN.postDelayed(new Runnable() {
                    @Override public void run() {
                        setupStage5SupportAd(activity, attempt + 1);
                    }
                }, 300L);
            }
            return;
        }

        // the button has a fixed id in ads.xml; the text search is only a fallback
        View button = findByIdName(activity, "button_ads_watch");
        if (button == null) button = stage5FindSupportAdButton(root);
        if (button == null) {
            if (attempt < 8) {
                MAIN.postDelayed(new Runnable() {
                    @Override public void run() {
                        setupStage5SupportAd(activity, attempt + 1);
                    }
                }, 300L);
            }
            return;
        }

        // support ads disabled (local flag or remote config): hide the button, no timeout dance
        if (!CigramAdManager.canShowSupportAd(activity)) {
            try {
                button.setVisibility(View.GONE);
            } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "support button hide", ignored); }
            return;
        }

        SupportAdController controller;
        synchronized (SUPPORT_AD_CONTROLLERS) {
            controller = SUPPORT_AD_CONTROLLERS.get(activity);
            if (controller == null) {
                controller = new SupportAdController(activity);
                SUPPORT_AD_CONTROLLERS.put(activity, controller);
            }
        }
        controller.bind(button);
        controller.load();
    }

    private static View stage5FindSupportAdButton(View root) {
        if (root == null) return null;

        if (root instanceof TextView && root.isClickable()) {
            CharSequence value = ((TextView) root).getText();
            String textValue = value == null ? "" : String.valueOf(value).trim();
            String normalized = textValue
                    .replace("إ", "ا")
                    .replace("أ", "ا")
                    .replace("آ", "ا");
            if (normalized.contains("تهيئة")
                    || (normalized.contains("اعلان")
                        && (normalized.contains("شاهد")
                            || normalized.contains("مشاهدة")
                            || normalized.contains("اضغط")))) {
                return root;
            }
        }

        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = stage5FindSupportAdButton(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void stage5ClearAdMobTestDeviceIds() {
        if (appContext != null) CigramAdManager.initialize(appContext);
    }

    private static final class SupportAdController {
        private final Activity activity;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private View boundButton;
        private com.google.android.gms.ads.interstitial.InterstitialAd ad;
        private com.google.android.gms.ads.interstitial.InterstitialAd secondAd;
        private boolean loading;
        private boolean loadingSecond;
        private boolean showing;
        private boolean pendingShow;
        private boolean pendingSecondShow;
        private boolean secondLoadFailed;
        private boolean firstCompleted;
        private boolean chainFinished = true;
        private boolean destroyed;
        private boolean countdownRunning;
        private boolean countdownFinished;
        private boolean lastLoadFailed;
        private int countdownValue = -1;
        private long adLoadedAt;
        private int initWaits;
        /** v5.27: the support unit had no ad, so this request uses the player unit instead. */
        private boolean useFallbackUnit;
        /** Last AdMob load error code (shown in the "unavailable" message), -1 when none. */
        private int lastErrorCode = -1;
        private int adCount;
        private int chainGeneration;
        /** v6.2: ads already shown in this press; the support button now plays SUPPORT_CHAIN in a row. */
        private int chainShown;
        private static final int SUPPORT_CHAIN = 3;
        private CharSequence originalButtonText;
        private Stage5ChromeState chromeState;

        private final Runnable countdownRunnable = new Runnable() {
            @Override public void run() {
                if (destroyed || !countdownRunning || !isAlive(activity)) return;

                if (countdownValue > 0) {
                    countdownValue--;
                    setCountdownState(countdownValue);
                    handler.postDelayed(
                            this,
                            countdownValue == 0
                                    ? CigramAdConfig.SUPPORT_ZERO_HOLD_MS
                                    : 1000L
                    );
                    return;
                }

                countdownRunning = false;
                countdownFinished = true;

                dropStaleAd();
                if (ad != null) {
                    showSlot(1);
                    return;
                }

                if (lastLoadFailed && !loading) {
                    failChain(unavailableMessage());
                    return;
                }

                setWaitingForAdState();
                load();
                handler.removeCallbacks(adWaitTimeoutRunnable);
                handler.postDelayed(
                        adWaitTimeoutRunnable,
                        CigramAdConfig.SUPPORT_AD_WAIT_TIMEOUT_MS
                );
            }
        };

        private final Runnable adWaitTimeoutRunnable = new Runnable() {
            @Override public void run() {
                if (destroyed || chainFinished || !pendingShow || showing || ad != null) return;
                failChain("الإعلان يحتاج وقتاً أطول، حاول مرة ثانية بعد قليل");
            }
        };

        SupportAdController(Activity activity) {
            this.activity = activity;
        }

        void bind(View button) {
            if (destroyed || button == null || boundButton == button) return;
            boundButton = button;
            if (button instanceof TextView) {
                originalButtonText = ((TextView) button).getText();
            }
            button.setClickable(true);
            button.setFocusable(true);
            setIdleButtonState();
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (destroyed || !isAlive(activity)) return;
                    if (!chainFinished || showing || countdownRunning || adCount != 0) return;
                    if (!isOnline(activity)) {
                        Toast.makeText(
                                activity,
                                "تحقق من اتصال الإنترنت",
                                Toast.LENGTH_SHORT
                        ).show();
                        return;
                    }

                    chainGeneration++;
                    chainFinished = false;
                    chainShown = 0;
                    adCount = 1;
                    pendingShow = true;
                    pendingSecondShow = false;
                    secondLoadFailed = false;
                    firstCompleted = false;
                    lastLoadFailed = false;
                    secondAd = null;
                    beginCountdown();
                }
            });
        }

        private void beginCountdown() {
            countdownRunning = true;
            countdownFinished = false;
            countdownValue = CigramAdConfig.SUPPORT_COUNTDOWN_FROM;
            handler.removeCallbacks(countdownRunnable);
            handler.removeCallbacks(adWaitTimeoutRunnable);
            setCountdownState(countdownValue);
            load();
            handler.postDelayed(countdownRunnable, 1000L);
        }

        private void setCountdownState(int value) {
            View button = boundButton;
            if (button == null) return;
            try {
                button.setEnabled(false);
                button.setAlpha(0.82f);
                if (button instanceof TextView) {
                    ((TextView) button).setText(
                            value > 0
                                    ? "سيبدأ الإعلان خلال " + value
                                    : "0"
                    );
                }
            } catch (Throwable ignored) {
                android.util.Log.w("CigramAdsUi", "support countdown state failed", ignored);
            }
        }

        private void setWaitingForAdState() {
            View button = boundButton;
            if (button == null) return;
            try {
                button.setEnabled(false);
                button.setAlpha(0.76f);
                if (button instanceof TextView) {
                    ((TextView) button).setText("جاري تحميل الإعلان...");
                }
            } catch (Throwable ignored) {
                android.util.Log.w("CigramAdsUi", "support waiting state failed", ignored);
            }
        }

        private void setIdleButtonState() {
            View button = boundButton;
            if (button == null) return;
            try {
                button.setEnabled(true);
                button.setAlpha(1f);
                if (button instanceof TextView && originalButtonText != null) {
                    ((TextView) button).setText(originalButtonText);
                }
            } catch (Throwable ignored) {
                android.util.Log.w("CigramAdsUi", "support button state failed", ignored);
            }
        }

        private void failChain(String message) {
            if (chainShown > 0) {
                // at least one ad of the series was watched: end with the thank-you, not an error
                finishChain(true);
                return;
            }
            finishChain(false);
            if (isAlive(activity) && message != null && message.length() > 0) {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            }
        }

        void load() {
            loadAd(false);
        }

        /** The AdMob error code tells why: 3 = no ad to show (account / unit), 0 / 2 = network or blocked. */
        private String unavailableMessage() {
            String m = "الإعلان غير متوفر حالياً، حاول بعد قليل";
            return lastErrorCode >= 0 ? m + " (رمز " + lastErrorCode + ")" : m;
        }

        /** A loaded interstitial expires after about an hour; never show (or keep) an expired one. */
        private void dropStaleAd() {
            if (ad != null && android.os.SystemClock.elapsedRealtime() - adLoadedAt
                    > CigramAdConfig.SUPPORT_AD_MAX_AGE_MS) {
                ad = null;
            }
        }

        private void loadSecond() {
            loadAd(true);
        }

        private void loadAd(final boolean second) {
            if (destroyed || !isAlive(activity)) return;
            if (!CigramAdManager.canShowSupportAd(activity)) return;
            if (second) {
                if (chainFinished || adCount == 0 || loadingSecond || secondAd != null) return;
            } else {
                dropStaleAd();
                if (loading || showing || ad != null) return;
                // v10.34: a press of «مشاهدة إعلان» always tries a fresh request (no back-off wait)
                if (!pendingShow && android.os.SystemClock.elapsedRealtime() < CG_SUPPORT_NEXT_LOAD_AT) {
                    // still cooling down after a failure: answer "unavailable" without a request
                    lastLoadFailed = true;
                    if (pendingShow && countdownFinished && adCount == 1 && !chainFinished) {
                        failChain(unavailableMessage());
                    }
                    return;
                }
            }

            if (!isOnline(activity)) {
                if (second) {
                    secondLoadFailed = true;
                    if (firstCompleted && adCount == 2) finishChain(true);
                } else if (pendingShow && countdownFinished) {
                    failChain("تحقق من اتصال الإنترنت");
                }
                return;
            }

            CigramAdManager.initialize(activity.getApplicationContext());
            if (!CigramAdManager.isInitialized()) {
                // wait for AdMob to finish starting, but not forever (about 20 s)
                if (++initWaits > 50) {
                    initWaits = 0;
                    if (!second && pendingShow && countdownFinished && adCount == 1 && !chainFinished) {
                        failChain("الإعلان غير جاهز حالياً، حاول بعد قليل");
                    }
                    return;
                }
                handler.postDelayed(new Runnable() {
                    @Override public void run() {
                        loadAd(second);
                    }
                }, 400L);
                return;
            }
            initWaits = 0;

            final int requestGeneration = chainGeneration;
            CigramAdManager.report(activity, "support_ad_request", second ? "second" : "first", "");
            if (second) loadingSecond = true;
            else {
                loading = true;
                CigramAdManager.setLoading(this, true);
                lastLoadFailed = false;
            }

            try {
                com.google.android.gms.ads.AdRequest request =
                        new com.google.android.gms.ads.AdRequest.Builder().build();
                com.google.android.gms.ads.interstitial.InterstitialAd.load(
                        activity, // Activity context: required by some mediation adapters
                        (!second && useFallbackUnit)
                                ? CigramAdManager.playerInterstitialUnitId()
                                : CigramAdManager.supportInterstitialUnitId(),
                        request,
                        new com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback() {
                            @Override public void onAdLoaded(
                                    com.google.android.gms.ads.interstitial.InterstitialAd loadedAd) {
                                CigramAdManager.report(activity, "support_ad_loaded", second ? "second" : "first", "");
                                CigramAdManager.setLoading(SupportAdController.this, false);
                                if (!second) {
                                    CG_SUPPORT_FAILURES = 0;
                                    CG_SUPPORT_NEXT_LOAD_AT = 0L;
                                    useFallbackUnit = false;
                                    lastErrorCode = -1;
                                }
                                if (second) loadingSecond = false;
                                else loading = false;
                                if (destroyed) return;

                                if (second) {
                                    if (chainFinished
                                            || requestGeneration != chainGeneration
                                            || adCount == 0) return;
                                    secondLoadFailed = false;
                                    secondAd = loadedAd;
                                    if (firstCompleted && adCount == 2
                                            && pendingSecondShow && !showing) {
                                        showSlot(2);
                                    }
                                } else {
                                    lastLoadFailed = false;
                                    ad = loadedAd;
                                    adLoadedAt = android.os.SystemClock.elapsedRealtime();
                                    if (pendingShow && countdownFinished
                                            && adCount == 1 && !chainFinished) {
                                        showSlot(1);
                                    }
                                }
                            }

                            @Override public void onAdFailedToLoad(
                                    com.google.android.gms.ads.LoadAdError error) {
                                CigramAdManager.report(activity, "support_ad_failed", second ? "second" : "first",
                                        "load:" + (error == null ? -1 : error.getCode()));
                                CigramAdManager.setLoading(SupportAdController.this, false);
                                int cgCode = error == null ? -1 : error.getCode();
                                if (!second && !useFallbackUnit && !destroyed) {
                                    // v5.27: the support unit had nothing: try the player unit at once
                                    // (same AdMob app) before telling the user there is no ad
                                    loading = false;
                                    useFallbackUnit = true;
                                    lastErrorCode = cgCode;
                                    android.util.Log.w("CigramStage5",
                                            "Support unit failed (" + cgCode + "), trying player unit");
                                    loadAd(false);
                                    return;
                                }
                                if (second) loadingSecond = false;
                                else {
                                    loading = false;
                                    lastLoadFailed = true;
                                    useFallbackUnit = false;
                                    lastErrorCode = cgCode;
                                    CG_SUPPORT_FAILURES++;
                                    CG_SUPPORT_NEXT_LOAD_AT = android.os.SystemClock.elapsedRealtime()
                                            + Math.min(60000L, 5000L << Math.min(CG_SUPPORT_FAILURES - 1, 4));
                                }
                                android.util.Log.w(
                                        "CigramStage5",
                                        second
                                            ? "Second support interstitial failed to load: " + error
                                            : "Support interstitial failed to load: " + error
                                );

                                if (second) {
                                    if (requestGeneration != chainGeneration || chainFinished) return;
                                    secondAd = null;
                                    secondLoadFailed = true;
                                    if (firstCompleted && adCount == 2) finishChain(true);
                                } else {
                                    ad = null;
                                    if (pendingShow && countdownFinished
                                            && adCount == 1 && !chainFinished) {
                                        failChain(unavailableMessage());
                                    }
                                }
                            }
                        });
            } catch (Throwable error) {
                CigramAdManager.setLoading(this, false);
                if (second) loadingSecond = false;
                else {
                    loading = false;
                    lastLoadFailed = true;
                }
                android.util.Log.w(
                        "CigramStage5",
                        second
                            ? "Second support interstitial load exception"
                            : "Support interstitial load exception",
                        error
                );
                if (second) {
                    secondAd = null;
                    secondLoadFailed = true;
                    if (firstCompleted && adCount == 2) finishChain(true);
                } else {
                    ad = null;
                    if (pendingShow && countdownFinished && adCount == 1 && !chainFinished) {
                        failChain("تعذر تحميل الإعلان، حاول بعد قليل");
                    }
                }
            }
        }

        private void attachCallbacks(
                com.google.android.gms.ads.interstitial.InterstitialAd target,
                final int slot,
                final int generation) {
            if (target == null) return;
            target.setFullScreenContentCallback(
                    new com.google.android.gms.ads.FullScreenContentCallback() {
                        @Override public void onAdShowedFullScreenContent() {
                            CigramAdManager.report(activity, "support_ad_show", "slot" + slot, "");
                            if (generation != chainGeneration || chainFinished) return;
                            showing = true;
                            chainShown++;
                            stage5EnforceAdChrome(activity, chromeState);
                            CigramAdManager.recordSupportAdShown(activity);
                        }

                        @Override public void onAdDismissedFullScreenContent() {
                            CigramAdManager.report(activity, "support_ad_closed", "slot" + slot, "");
                            if (generation != chainGeneration || chainFinished) return;
                            showing = false;
                            // v6.2: SUPPORT_CHAIN ads per press (was one), then the thank-you
                            if (chainShown < SUPPORT_CHAIN && !destroyed && isAlive(activity)) {
                                nextInChain();
                                return;
                            }
                            finishChain(true);
                        }

                        @Override public void onAdFailedToShowFullScreenContent(
                                com.google.android.gms.ads.AdError error) {
                            CigramAdManager.report(activity, "support_ad_failed", "slot" + slot,
                                    "show:" + (error == null ? -1 : error.getCode()));
                            if (generation != chainGeneration || chainFinished) return;
                            showing = false;
                            android.util.Log.w(
                                    "CigramStage5",
                                    slot == 1
                                        ? "Support interstitial failed to show: " + error
                                        : "Second support interstitial failed to show: " + error
                            );
                            failChain("تعذر عرض الإعلان، حاول مرة ثانية");
                        }
                    });
        }

        private void showSlot(final int slot) {
            if (destroyed || chainFinished || showing || !isAlive(activity)) return;
            if (slot != adCount) return;
            if (slot == 1 && !countdownFinished) return;

            final int generation = chainGeneration;
            com.google.android.gms.ads.interstitial.InterstitialAd ready;
            if (slot == 1) {
                ready = ad;
                ad = null;
                pendingShow = false;
                handler.removeCallbacks(adWaitTimeoutRunnable);
                if (!CigramAdManager.acquireFullScreen(this)) {
                    ad = ready;
                    pendingShow = true;
                    failChain("يوجد إعلان آخر قيد العرض، حاول بعد قليل");
                    return;
                }
            } else {
                ready = secondAd;
                secondAd = null;
                pendingSecondShow = false;
            }
            if (ready == null) {
                if (slot == 2 && firstCompleted && secondLoadFailed) finishChain(true);
                return;
            }

            countdownRunning = false;
            countdownFinished = false;
            countdownValue = -1;
            handler.removeCallbacks(countdownRunnable);
            setIdleButtonState();
            chromeState = stage5BeginAdChrome(activity, chromeState);
            attachCallbacks(ready, slot, generation);
            showing = true;
            try {
                ready.show(activity);
            } catch (Throwable error) {
                showing = false;
                android.util.Log.w(
                        "CigramStage5",
                        slot == 1
                            ? "Support interstitial show exception"
                            : "Second support interstitial show exception",
                        error
                );
                if (slot == 2 && firstCompleted) finishChain(true);
                else failChain("تعذر فتح الإعلان، حاول مرة ثانية");
            }
        }

        /** Loads and shows the next ad of the same press ("إعلان 2 من 3"). */
        private void nextInChain() {
            CigramAdManager.releaseFullScreen(this);
            adCount = 1;
            pendingShow = true;
            countdownRunning = false;
            countdownFinished = true;
            lastLoadFailed = false;
            View button = boundButton;
            if (button instanceof TextView) {
                try {
                    button.setEnabled(false);
                    button.setAlpha(0.76f);
                    ((TextView) button).setText("جاري تحميل الإعلان " + (chainShown + 1) + " من " + SUPPORT_CHAIN);
                } catch (Throwable ignored) { }
            }
            dropStaleAd();
            if (ad != null) {
                showSlot(1);
                return;
            }
            loadAd(false);
            handler.removeCallbacks(adWaitTimeoutRunnable);
            handler.postDelayed(adWaitTimeoutRunnable, CigramAdConfig.SUPPORT_AD_WAIT_TIMEOUT_MS);
        }

        private void scheduleSecondFallback(final int generation) {
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (destroyed
                            || chainFinished
                            || generation != chainGeneration
                            || adCount != 2
                            || !firstCompleted
                            || showing
                            || secondAd != null) return;
                    finishChain(true);
                }
            }, CigramAdConfig.SUPPORT_SECOND_AD_WAIT_MS);
        }

        private void finishChain(boolean grantResult) {
            if (chainFinished) return;
            chainFinished = true;
            showing = false;
            pendingShow = false;
            pendingSecondShow = false;
            loadingSecond = false;
            secondLoadFailed = false;
            countdownRunning = false;
            countdownFinished = false;
            lastLoadFailed = false;
            countdownValue = -1;
            adCount = 0;
            secondAd = null;
            firstCompleted = false;
            chainGeneration++;
            handler.removeCallbacks(countdownRunnable);
            handler.removeCallbacks(adWaitTimeoutRunnable);

            stage5RestoreAdChrome(chromeState);
            chromeState = null;
            CigramAdManager.releaseFullScreen(this);
            setIdleButtonState();

            if (grantResult && isAlive(activity)) {
                Toast.makeText(
                        activity,
                        "شكراً لدعم سيكرام",
                        Toast.LENGTH_SHORT
                ).show();
            }

            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!destroyed) load();
                }
            }, 1200L);
        }

        void destroy() {
            destroyed = true;
            chainFinished = true;
            CigramAdManager.setLoading(this, false);
            ad = null;
            secondAd = null;
            pendingShow = false;
            pendingSecondShow = false;
            countdownRunning = false;
            countdownFinished = false;
            showing = false;
            stage5RestoreAdChrome(chromeState);
            chromeState = null;
            CigramAdManager.releaseFullScreen(this);
            setIdleButtonState();
            boundButton = null;
            handler.removeCallbacksAndMessages(null);
        }
    }

    private static Typeface sima(Context context) {
        return cigramTypeface(context);
    }

    private static TextView text(
            Context context,
            String value,
            float size,
            int color,
            Typeface typeface
    ) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(typeface);
        view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return view;
    }

    private static TextView pillButton(
            Context context,
            String value,
            int color,
            Typeface typeface
    ) {
        TextView button = text(context, value, 15f, Color.WHITE, typeface);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        button.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(context, 999f));
        button.setBackground(bg);
        return button;
    }

    private static void removeTagged(Activity activity, String tag) {
        if (activity == null || tag == null) return;
        try {
            View root = activity.findViewById(android.R.id.content);
            if (!(root instanceof ViewGroup)) return;
            removeTaggedRecursive((ViewGroup) root, tag);
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "removeTagged", ignored); }
    }

    private static void removeTaggedRecursive(ViewGroup group, String tag) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            Object value = child.getTag();
            if (tag.equals(value)) {
                group.removeViewAt(i);
                continue;
            }
            if (child instanceof ViewGroup) {
                removeTaggedRecursive((ViewGroup) child, tag);
            }
        }
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int px(float density, int value) {
        return Math.round(value * density);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection,
                                String[] selectionArgs) { return 0; }
}
