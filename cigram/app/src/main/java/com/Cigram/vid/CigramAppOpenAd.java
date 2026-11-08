package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.appopen.AppOpenAd;

/**
 * v10.35: AdMob "App open" ad when the app starts — at most once every 4 hours (same idea as OTTO TV).
 *
 * The App open format is used on purpose: AdMob does not allow a normal interstitial on app load,
 * App open ads are the format made for this moment.
 *
 * Wiring (AdsOverlay.onAppOpen, called from MainActivity onCreate):
 *   1. {@link #preload(Activity)} starts the request at once, in parallel with the in-house splash.
 *   2. {@link #showOrSkip(Activity, Runnable)} runs only when the in-house (paid) splash did NOT show
 *      this launch. It shows the ad if it is ready, or waits for it while we are still inside
 *      LAUNCH_WINDOW_MS after the app opened. Past that window it gives up, so the ad never jumps in
 *      after the viewer has started using the app.
 *
 * Rules: one attempt per app process; the 4-hour gap is stored on the device and counted from the
 * moment an ad really appeared (a failed request does not use up the 4 hours); ad-free accounts
 * and the remote switch "app_open_ads_enabled": false skip it.
 */
public final class CigramAppOpenAd {
    private static final String TAG = "CigramAppOpen";
    private static final String PREFS = "cigram_ads_state";
    private static final String KEY_LAST_SHOWN = "app_open_last_show";
    /** How long after the app opened the ad may still appear. */
    private static final long LAUNCH_WINDOW_MS = 5000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object TOKEN = new Object();

    private static boolean started;       // preload() already ran in this process
    private static long openedAt;         // elapsedRealtime when the app opened
    private static boolean loading;
    private static boolean shown;         // an ad was shown in this process
    private static AppOpenAd ready;

    // a showOrSkip() call waiting for the request that is still in flight
    private static Activity waitActivity;
    private static Runnable waitNoAd;
    private static Runnable waitTimeout;

    private CigramAppOpenAd() {
    }

    /** True when an app-open ad may be shown now (enabled, not ad-free, 4 hours since the last one). */
    public static boolean isDue(Context c) {
        try {
            if (c == null) return false;
            Context app = c.getApplicationContext();
            if (!CigramAdConfig.appOpenAdsEnabled(app) || CigramAdConfig.adFreeUser(app)) return false;
            long last = prefs(app).getLong(KEY_LAST_SHOWN, 0L);
            long now = System.currentTimeMillis();
            // a time in the future means the clock was changed: do not lock the ad out for days
            return last <= 0L || last > now || now - last >= CigramAdConfig.APP_OPEN_GAP_MS;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * No app-open ad for this launch (the in-house splash will show). Also stops a later
     * MainActivity re-creation in the same process from starting one in the middle of a session.
     */
    public static void skipThisLaunch() {
        if (started) return;
        started = true;
        openedAt = SystemClock.elapsedRealtime();
    }

    /** Starts the request as early as possible. Only the first call per process does anything. */
    public static void preload(final Activity activity) {
        if (activity == null || started) return;
        started = true;
        openedAt = SystemClock.elapsedRealtime();
        final Context app = activity.getApplicationContext();
        try {
            if (!isDue(app) || !isOnline(app)) return;
            CigramAdManager.initialize(app);
            loading = true;
            CigramAdManager.report(app, "ad_request", "app_open", "");
            // 1 = portrait, 2 = landscape (the ad is drawn in the orientation it was loaded in)
            int orientation = isLandscape(activity) ? 2 : 1;
            AppOpenAd.load(
                    activity,
                    CigramAdConfig.appOpenUnit(),
                    new AdRequest.Builder().build(),
                    orientation,
                    new AppOpenAd.AppOpenAdLoadCallback() {
                        @Override public void onAdLoaded(AppOpenAd ad) {
                            loading = false;
                            ready = ad;
                            CigramAdManager.report(app, "ad_loaded", "app_open",
                                    String.valueOf(SystemClock.elapsedRealtime() - openedAt));
                            Activity a = waitActivity;
                            Runnable noAd = takeWait();
                            if (noAd != null) show(a, noAd);
                        }

                        @Override public void onAdFailedToLoad(LoadAdError error) {
                            loading = false;
                            ready = null;
                            CigramAdManager.report(app, "ad_failed", "app_open",
                                    "load:" + (error == null ? -1 : error.getCode()));
                            Log.w(TAG, "app open ad load failed: " + error);
                            Runnable noAd = takeWait();
                            if (noAd != null) noAd.run();
                        }
                    });
        } catch (Throwable error) {
            loading = false;
            Log.w(TAG, "preload failed", error);
        }
    }

    /**
     * Shows the app-open ad when it is due and ready (or arrives inside the launch window).
     * noAd runs exactly once when NO ad is shown (the caller then shows its daily popup);
     * it never runs when the ad was shown.
     */
    public static void showOrSkip(final Activity activity, final Runnable noAd) {
        try {
            if (activity == null || !AdsUi.alive(activity) || shown || waitNoAd != null
                    || !isDue(activity)) {
                runQuietly(noAd);
                return;
            }
            long left = LAUNCH_WINDOW_MS - (SystemClock.elapsedRealtime() - openedAt);
            if (left <= 0L) {
                runQuietly(noAd);
                return;
            }
            if (ready != null) {
                show(activity, noAd);
                return;
            }
            if (!loading) {
                runQuietly(noAd);
                return;
            }
            // the request is still in flight: wait for it, but only inside the launch window
            waitActivity = activity;
            waitNoAd = noAd;
            waitTimeout = new Runnable() {
                @Override public void run() {
                    Runnable r = takeWait();
                    if (r != null) r.run();
                }
            };
            MAIN.postDelayed(waitTimeout, left);
        } catch (Throwable error) {
            Log.w(TAG, "showOrSkip failed", error);
            runQuietly(noAd);
        }
    }

    private static void show(final Activity activity, final Runnable noAd) {
        final AppOpenAd ad = ready;
        if (ad == null || activity == null || !AdsUi.alive(activity)
                || !CigramAdManager.acquireFullScreen(TOKEN)) {
            runQuietly(noAd);
            return;
        }
        ready = null;
        shown = true;
        final Context app = activity.getApplicationContext();
        final boolean[] finished = {false};
        try {
            ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override public void onAdShowedFullScreenContent() {
                    try {
                        prefs(app).edit().putLong(KEY_LAST_SHOWN, System.currentTimeMillis()).apply();
                    } catch (Throwable ignored) {
                    }
                    // feeds the shared cooldown, so a player ad does not follow right after
                    CigramAdManager.recordSupportAdShown(app);
                    CigramAdManager.report(app, "ad_show", "app_open", "");
                }

                @Override public void onAdDismissedFullScreenContent() {
                    if (finished[0]) return;
                    finished[0] = true;
                    CigramAdManager.releaseFullScreen(TOKEN);
                    CigramAdManager.report(app, "ad_closed", "app_open", "");
                }

                @Override public void onAdFailedToShowFullScreenContent(AdError adError) {
                    if (finished[0]) return;
                    finished[0] = true;
                    CigramAdManager.releaseFullScreen(TOKEN);
                    CigramAdManager.report(app, "ad_failed", "app_open",
                            "show:" + (adError == null ? -1 : adError.getCode()));
                    runQuietly(noAd);
                }
            });
            ad.show(activity);
        } catch (Throwable error) {
            Log.w(TAG, "show failed", error);
            if (!finished[0]) {
                finished[0] = true;
                CigramAdManager.releaseFullScreen(TOKEN);
                runQuietly(noAd);
            }
        }
    }

    /** Ends a pending wait exactly once and returns its noAd runnable (null when nothing waited). */
    private static Runnable takeWait() {
        Runnable r = waitNoAd;
        waitNoAd = null;
        waitActivity = null;
        try {
            if (waitTimeout != null) MAIN.removeCallbacks(waitTimeout);
        } catch (Throwable ignored) {
        }
        waitTimeout = null;
        return r;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean isLandscape(Activity a) {
        try {
            return a.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isOnline(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }

    private static void runQuietly(Runnable r) {
        try {
            if (r != null) r.run();
        } catch (Throwable ignored) {
        }
    }
}
