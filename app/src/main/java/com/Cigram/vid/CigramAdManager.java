package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.RequestConfiguration;
import com.google.android.gms.ads.initialization.InitializationStatus;
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;

import java.util.ArrayList;
import java.util.Collections;

public final class CigramAdManager {
    private static final String TAG = "CigramAds";
    private static final String PREFS = "cigram_ads_state";
    private static final String KEY_PLAYER_SHOWN_TIMES = "player_shown_times";
    private static final String KEY_LAST_ANY_AD = "last_any_ad_at";
    private static final long LOADING_EXPIRY_MS = 30000L;
    /**
     * Ads may be requested before MobileAds reports "initialised": the SDK queues the request
     * itself. Waiting for the callback forever (it can be late or never arrive) stopped every ad
     * for the rest of the process, so after this grace period requests go ahead anyway.
     */
    private static final long INIT_GRACE_MS = 8000L;
    /**
     * A full-screen lock older than this is stale (an ad callback never came back). Without
     * this, one lost callback blocked every later ad with "other_ad_showing" until the process died.
     */
    private static final long FULLSCREEN_STALE_MS = 5L * 60L * 1000L;
    /** The per-session cap starts again after the app has been in the background this long. */
    private static final long SESSION_GAP_MS = 30L * 60L * 1000L;
    private static final Object LOCK = new Object();
    private static final java.util.HashMap<Object, Long> LOADING = new java.util.HashMap<Object, Long>();
    private static int sessionPlayerAds;

    private static boolean initStarted;
    private static boolean initReady;
    private static long initCalledAt;
    private static Object fullScreenOwner;
    private static long fullScreenSince;
    private static int startedActivities;
    private static long backgroundAt;

    private CigramAdManager() {
    }

    public static void initialize(Context context) {
        if (context == null) return;
        if (!CigramAdConfig.ADMOB_ENABLED) return;   // AdMob switched off: never start the SDK
        final Context app = context.getApplicationContext();
        // self-throttled (6 h): keeps the remote ad config fresh in long-lived processes too,
        // instead of fetching it only once per process
        try {
            CigramAdConfig.refreshRemote(app);
        } catch (Throwable ignored) {
        }
        final long initT0 = android.os.SystemClock.elapsedRealtime();
        if (!CigramConsent.canRequestAds(app)) return; // UMP: wait until consent is resolved
        synchronized (LOCK) {
            if (initReady || initStarted) return;
            initStarted = true;
            if (initCalledAt == 0L) initCalledAt = initT0;
        }

        // Perf / ANR: the SDK start-up must not run on the main thread (the callback still arrives there).
        new Thread(new Runnable() { @Override public void run() {
        try {
            RequestConfiguration configuration =
                    new RequestConfiguration.Builder()
                            .setTestDeviceIds(Collections.<String>emptyList())
                            .build();
            MobileAds.setRequestConfiguration(configuration);
            MobileAds.initialize(
                    app,
                    new OnInitializationCompleteListener() {
                        @Override
                        public void onInitializationComplete(InitializationStatus status) {
                            synchronized (LOCK) {
                                initReady = true;
                                initStarted = false;
                            }
                            try {
                                StringBuilder sb = new StringBuilder("init ok " + (android.os.SystemClock.elapsedRealtime() - initT0) + "ms:");
                                for (java.util.Map.Entry<String, com.google.android.gms.ads.initialization.AdapterStatus> e
                                        : status.getAdapterStatusMap().entrySet()) {
                                    String n = e.getKey();
                                    n = n.substring(n.lastIndexOf('.') + 1);
                                    sb.append(' ').append(n).append('=').append(e.getValue().getInitializationState());
                                }
                                diagToast(app, sb.toString());
                            } catch (Throwable ignored) { }
                        }
                    }
            );
        } catch (Throwable error) {
            synchronized (LOCK) {
                initReady = false;
                initStarted = false;
                initCalledAt = 0L; // the SDK did not start: do not send requests on the grace period
            }
            Log.w(TAG, "MobileAds initialization failed", error);
            diagToast(app, "init EXCEPTION " + error);
        }
        } }, "cigram-ads-sdk-init").start();
        // watchdog: if the callback never arrives, report it and allow a new attempt
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                boolean stuck;
                synchronized (LOCK) {
                    stuck = !initReady && initStarted;
                    if (stuck) initStarted = false;
                }
                if (stuck) diagToast(app, "init TIMEOUT 15s (no callback)");
            }
        }, 15000L);
    }

    static void diagToast(final Context c, final String msg) {
        if (!CigramAdConfig.DIAG || c == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try { CigramSilent.toast(c, "AD: " + msg, android.widget.Toast.LENGTH_LONG); } catch (Throwable ignored) { }
            }
        });
    }

    /** True when ad requests may be sent: AdMob is ready, or its start has had INIT_GRACE_MS. */
    public static boolean isInitialized() {
        synchronized (LOCK) {
            return initReady || (initCalledAt > 0L
                    && android.os.SystemClock.elapsedRealtime() - initCalledAt >= INIT_GRACE_MS);
        }
    }

    /** Call from every Activity onStart: starts a new ad session after a long background gap. */
    public static void onActivityStarted() {
        synchronized (LOCK) {
            if (startedActivities == 0 && backgroundAt > 0L
                    && android.os.SystemClock.elapsedRealtime() - backgroundAt >= SESSION_GAP_MS) {
                // the process outlived the viewing session: the session cap must not stop ads forever
                sessionPlayerAds = 0;
            }
            startedActivities++;
            backgroundAt = 0L;
        }
    }

    /** Call from every Activity onStop. */
    public static void onActivityStopped() {
        synchronized (LOCK) {
            if (startedActivities > 0) startedActivities--;
            if (startedActivities == 0) backgroundAt = android.os.SystemClock.elapsedRealtime();
        }
    }

    public static String playerInterstitialUnitId() {
        return CigramAdConfig.playerInterstitial();
    }

    public static String supportInterstitialUnitId() {
        return CigramAdConfig.supportInterstitial();
    }

    public static boolean acquireFullScreen(Object owner) {
        if (owner == null) return false;
        synchronized (LOCK) {
            releaseStaleFullScreenLocked();
            if (fullScreenOwner == null || fullScreenOwner == owner) {
                fullScreenSince = android.os.SystemClock.elapsedRealtime();
                fullScreenOwner = owner;
                return true;
            }
            return false;
        }
    }

    private static void releaseStaleFullScreenLocked() {
        if (fullScreenOwner != null
                && android.os.SystemClock.elapsedRealtime() - fullScreenSince > FULLSCREEN_STALE_MS) {
            Log.w(TAG, "stale full-screen lock released");
            fullScreenOwner = null;
        }
    }

    public static void releaseFullScreen(Object owner) {
        if (owner == null) return;
        synchronized (LOCK) {
            if (fullScreenOwner == owner) fullScreenOwner = null;
        }
    }

    /** True while another owner holds the full-screen lock (stale locks are released first). */
    private static boolean otherShowingLocked(Object owner) {
        releaseStaleFullScreenLocked();
        return fullScreenOwner != null && fullScreenOwner != owner;
    }

    // ------------------------------------------------------------------
    // Central decision for player ads (v3.1). Player code only asks; it never repeats rules.
    // ------------------------------------------------------------------

    /**
     * Returns "" when a player ad may be shown now, otherwise a short reason code:
     * activity, player_error, mid_roll_disabled, ad_free, min_watch, other_ad_showing,
     * other_ad_loading, session_cap, hourly_cap, cooldown.
     *
     * @param owner the caller (used so an ad is not blocked by its own loading/showing state)
     */
    public static String playerAdBlockReason(Context context, Object owner, long positionMs,
                                             boolean playerError, boolean activityValid) {
        try {
            if (context == null) return "activity";
            if (!activityValid) return "activity";
            if (playerError) return "player_error";
            if (!CigramAdConfig.midRollEnabled(context)) return "mid_roll_disabled";
            if (CigramAdConfig.adFreeUser(context)) return "ad_free";
            if (positionMs < CigramAdConfig.firstAdDelayMs(context)) return "min_watch";
            synchronized (LOCK) {
                if (otherShowingLocked(owner)) return "other_ad_showing";
                if (otherLoadingLocked(owner)) return "other_ad_loading";
                if (sessionPlayerAds >= CigramAdConfig.maxAdsPerSession()) return "session_cap";
                ArrayList<Long> times = readAndPrunePlayerTimes(context);
                if (times.size() >= CigramAdConfig.maxAdsPerHour(context)) return "hourly_cap";
                long last = lastAnyAdAt(context, times);
                if (last > 0L && System.currentTimeMillis() - last < CigramAdConfig.cooldownMs(context)) {
                    return "cooldown";
                }
            }
            return "";
        } catch (Throwable error) {
            // a broken policy check must never show extra ads
            return "error";
        }
    }

    /**
     * Same shared limits as the mid-roll (ad-free, session cap, hourly cap, cooldown, other ad on
     * screen) for the ad between two episodes. Returns "" when allowed, else a reason.
     * No min_watch rule here: the user already finished or left the previous episode.
     * No other_ad_loading rule either: the episode ad is shown from its own preloaded copy, and a
     * request still loading elsewhere (player mid-roll preload, support page) used to cancel it
     * for nothing. The shared cooldown already keeps a second ad from following right after.
     */
    public static String episodeAdBlockReason(Context context, Object owner) {
        try {
            if (context == null) return "activity";
            if (CigramAdConfig.adFreeUser(context)) return "ad_free";
            synchronized (LOCK) {
                if (otherShowingLocked(owner)) return "other_ad_showing";
                if (sessionPlayerAds >= CigramAdConfig.maxAdsPerSession()) return "session_cap";
                ArrayList<Long> times = readAndPrunePlayerTimes(context);
                if (times.size() >= CigramAdConfig.maxAdsPerHour(context)) return "hourly_cap";
                long last = lastAnyAdAt(context, times);
                if (last > 0L && System.currentTimeMillis() - last < CigramAdConfig.cooldownMs(context)) {
                    return "cooldown";
                }
            }
            return "";
        } catch (Throwable error) {
            return "error";
        }
    }

    public static boolean canShowPlayerAd(Context context, Object owner, long positionMs,
                                          boolean playerError, boolean activityValid) {
        return playerAdBlockReason(context, owner, positionMs, playerError, activityValid).length() == 0;
    }

    /** Legacy v3.0 signature: policy without position / player state. */
    public static boolean canShowPlayerAd(Context context) {
        return playerAdBlockReason(context, null, Long.MAX_VALUE / 4L, false, context != null).length() == 0;
    }

    /** The manual "support Cigram" ad is optional and user-initiated; only the flag gates it. */
    public static boolean canShowSupportAd(Context context) {
        try {
            return context != null && CigramAdConfig.supportAdEnabled(context);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Marks that an ad request by this owner is in flight (expires by itself after 30 s). */
    public static void setLoading(Object owner, boolean loading) {
        if (owner == null) return;
        synchronized (LOCK) {
            if (loading) LOADING.put(owner, Long.valueOf(android.os.SystemClock.elapsedRealtime()));
            else LOADING.remove(owner);
        }
    }

    private static boolean otherLoadingLocked(Object owner) {
        long now = android.os.SystemClock.elapsedRealtime();
        java.util.Iterator<java.util.Map.Entry<Object, Long>> it = LOADING.entrySet().iterator();
        boolean other = false;
        while (it.hasNext()) {
            java.util.Map.Entry<Object, Long> e = it.next();
            if (now - e.getValue().longValue() > LOADING_EXPIRY_MS) {
                it.remove();
            } else if (e.getKey() != owner) {
                other = true;
            }
        }
        return other;
    }

    private static long lastAnyAdAt(Context context, ArrayList<Long> playerTimes) {
        long last = 0L;
        for (int i = 0; i < playerTimes.size(); i++) last = Math.max(last, playerTimes.get(i).longValue());
        try {
            long stored = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_ANY_AD, 0L);
            if (stored > 0L && stored <= System.currentTimeMillis()) last = Math.max(last, stored);
        } catch (Throwable ignored) {
        }
        return last;
    }

    /**
     * Milliseconds since the last ad of ANY kind (mid-roll, episode break or support) was shown on
     * this device, or Long.MAX_VALUE when none was recorded. Used to keep two ads from landing
     * back to back.
     */
    public static long msSinceLastAnyAd(Context context) {
        try {
            if (context == null) return Long.MAX_VALUE;
            long last;
            synchronized (LOCK) {
                last = lastAnyAdAt(context, readAndPrunePlayerTimes(context));
            }
            if (last <= 0L) return Long.MAX_VALUE;
            return Math.max(0L, System.currentTimeMillis() - last);
        } catch (Throwable error) {
            return Long.MAX_VALUE;
        }
    }

    /** Support ad shown: counts for the cooldown (so a player ad does not follow it at once). */
    public static void recordSupportAdShown(Context context) {
        try {
            if (context == null) return;
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_LAST_ANY_AD, System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {
        }
    }

    /** Central ad analytics: ad_request / ad_loaded / ad_show / ad_failed / ad_closed / ad_skipped_by_policy / support_ad_*. */
    public static void report(Context context, String event, String placement, String detail) {
        reportEx(context, event, placement, detail);
    }

    /** Same as report() plus extra key/value pairs (content_id, watch_pos_s, ...). */
    public static void reportEx(Context context, String event, String placement, String detail,
                                String... extra) {
        try {
            int n = extra == null ? 0 : extra.length;
            String[] kv = new String[8 + n];
            kv[0] = "placement";
            kv[1] = placement == null ? "" : placement;
            kv[2] = "detail";
            kv[3] = detail == null ? "" : detail;
            long since = -1L;
            int perHour = 0;
            if (context != null) {
                synchronized (LOCK) {
                    ArrayList<Long> times = readAndPrunePlayerTimes(context);
                    perHour = times.size();
                    long last = lastAnyAdAt(context, times);
                    if (last > 0L) since = Math.max(0L, (System.currentTimeMillis() - last) / 1000L);
                }
            }
            kv[4] = "time_since_last_ad_s";
            kv[5] = String.valueOf(since);
            kv[6] = "ads_this_hour";
            kv[7] = String.valueOf(perHour);
            if (n > 0) System.arraycopy(extra, 0, kv, 8, n);
            CigramAnalytics.track(context, event, kv);
        } catch (Throwable ignored) {
        }
    }

    public static void recordPlayerAdShown(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            ArrayList<Long> times = readAndPrunePlayerTimes(context);
            long now = System.currentTimeMillis();
            times.add(Long.valueOf(now));
            writePlayerTimes(context, times);
            sessionPlayerAds++;
            try {
                context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_LAST_ANY_AD, now).apply();
            } catch (Throwable ignored) {
            }
        }
    }

    private static ArrayList<Long> readAndPrunePlayerTimes(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_PLAYER_SHOWN_TIMES, "");
        long now = System.currentTimeMillis();
        ArrayList<Long> result = new ArrayList<Long>();
        boolean changed = false;

        if (raw != null && raw.length() > 0) {
            String[] pieces = raw.split(",");
            for (String piece : pieces) {
                if (piece == null || piece.trim().length() == 0) continue;
                try {
                    long value = Long.parseLong(piece.trim());
                    long age = now - value;
                    if (value > 0L && age >= 0L && age < CigramAdConfig.PLAYER_CAP_WINDOW_MS) {
                        result.add(Long.valueOf(value));
                    } else {
                        changed = true;
                    }
                } catch (Throwable ignored) {
                    changed = true;
                }
            }
        }

        if (changed) writePlayerTimes(context, result);
        return result;
    }

    private static void writePlayerTimes(Context context, ArrayList<Long> times) {
        Context app = context.getApplicationContext();
        StringBuilder value = new StringBuilder();
        if (times != null) {
            for (Long time : times) {
                if (time == null) continue;
                if (value.length() > 0) value.append(',');
                value.append(time.longValue());
            }
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PLAYER_SHOWN_TIMES, value.toString())
                .apply();
    }
}
