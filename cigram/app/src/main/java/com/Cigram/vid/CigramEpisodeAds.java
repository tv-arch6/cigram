package com.Cigram.vid;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

/**
 * Ad between two episodes (next button, episode list, auto-next at the end of an episode).
 *
 * Order of preference:
 *   1. AdMob interstitial (preloaded, so the user never waits for it),
 *   2. full-screen ad from the Admin app (cached copy only),
 *   3. nothing: the episode just opens.
 *
 * The episode is ALWAYS opened afterwards: every path ends with the `proceed` runnable running
 * exactly once, whether the ad was shown, failed, was skipped by policy or was not ready.
 * The one exception is a second request made while an episode ad is already on screen (a
 * double tap on an episode): it is ignored, so the episode can never open twice.
 *
 * v6.2 (owner's request):
 *   - the ad follows the screen: portrait page -> portrait ad, landscape player -> landscape ad.
 *     The screen is no longer turned for the ad; an ad loaded in the other orientation is dropped
 *     and a fresh one is requested (AdMob renders in the orientation the ad was loaded in).
 *   - every user press (شاهد هَسه, an episode card, next episode, the player's episode list) shows
 *     an ad: the cooldown and the 2-minute gap no longer apply to presses. The hourly / session
 *     safety caps (CigramAdConfig) and ad-free users still apply.
 *
 * Wiring: CigramStage1InitProvider calls {@link #preload(Activity)} when a series page or the
 * player opens, and {@link #beforeEpisode(Activity, Runnable, Runnable)} when an episode is
 * picked from the list and when an episode ends and the next one starts by itself.
 */
public final class CigramEpisodeAds {
    private static final String TAG = "CigramEpisodeAds";
    private static final long AD_MAX_AGE_MS = 45L * 60L * 1000L;
    /** An "ad is showing" flag older than this is stale (the ad callback never came back). */
    private static final long SHOWING_STALE_MS = 2L * 60L * 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object TOKEN = new Object();

    private static InterstitialAd ready;
    private static long readyAt;
    /** Orientation the ready ad was loaded in. */
    private static boolean readyLandscape;
    private static boolean loading;
    private static long loadStartedAt;
    private static long nextLoadAt;
    private static int failures;
    private static boolean showing;
    private static long showingSince;
    private static long lastShownAt;
    private static int initTries;
    /** v5.20: "جاري تحميل الإعلان" while the interstitial is requested after the user pressed play. */
    private static final long WAIT_TIMEOUT_MS = 15000L;
    private static boolean waiting;
    private static Runnable waitCb;
    private static Runnable waitTimeout;
    /** Full black "جاري تحميل الإعلان" page, shown on every press before the ad (v5.20.1). */
    private static final long COVER_MIN_MS = 900L;
    private static Dialog coverDialog;
    private static long coverShownAt;
    private static android.view.View coverContent;
    /**
     * v5.27: the episode ad (and its loading page) is always shown in landscape. A portrait screen
     * (series page, cast page) is turned to landscape for the ad and turned back afterwards.
     * AdMob renders an interstitial in the orientation it was LOADED in, so the ad is only
     * requested while the screen is already landscape.
     */
    private static Activity rotatedActivity;
    private static int rotatedFrom;
    private static final long ROTATE_WAIT_MS = 1500L;

    private CigramEpisodeAds() {
    }

    private static long lastAnyShownAt;
    /** v10.34: the user pressed «شاهد هسه»: the next request ignores the caps and the back-off. */
    private static boolean pressForce;

    /** True when an episode / watch-now ad was on screen within the last `ms` (avoids a second ad right after). */
    public static boolean shownWithin(long ms) {
        return lastAnyShownAt != 0L && SystemClock.elapsedRealtime() - lastAnyShownAt < ms;
    }

    /** v10.35: a rewarded-unlock ad (CigramRewardUnlock) was just on screen: same effect as an episode ad. */
    public static void markShownNow() {
        lastShownAt = SystemClock.elapsedRealtime();
        lastAnyShownAt = lastShownAt;
    }

    /** Temporary diagnostics (CigramAdConfig.DIAG): tells on screen why an ad did not appear. */
    private static void diag(final Context c, final String msg) {
        if (!CigramAdConfig.DIAG || c == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                try { CigramSilent.toast(c.getApplicationContext(), "AD: " + msg, android.widget.Toast.LENGTH_LONG); } catch (Throwable ignored) { }
            }
        });
    }

    /** Warm up AdMob + request the next interstitial. Safe to call as often as you like. */
    public static void preload(final Activity activity) {
        if (activity == null) return;
        // v10.35: the rewarded-unlock ad is warmed up on the same screens (series page, details, player)
        try { CigramRewardUnlock.preload(activity); } catch (Throwable ignored) { }
        final Context app = activity.getApplicationContext();
        try {
            if (!CigramAdConfig.episodeAdsEnabled(app) || CigramAdConfig.adFreeUser(app)) return;
            CigramAdManager.initialize(app);
            final boolean land = isLandscape(activity);
            long now = SystemClock.elapsedRealtime();
            if (loading && now - loadStartedAt > 20000L) loading = false; // watchdog
            if (ready != null && now - readyAt > AD_MAX_AGE_MS) ready = null;
            // v6.2: an ad loaded in the other orientation would show sideways: get one for this screen
            if (ready != null && readyLandscape != land && !showing) ready = null;
            if (showing || loading || ready != null || now < nextLoadAt) return;
            if (!isOnline(app)) return;
            // do not spend requests on an ad the shared hourly / session cap would refuse anyway
            String capWhy = CigramAdManager.episodeAdBlockReason(app, TOKEN);
            if (!pressForce && ("hourly_cap".equals(capWhy) || "session_cap".equals(capWhy))) return;
            if (!CigramAdManager.isInitialized()) {
                if (initTries++ < 12) {
                    MAIN.postDelayed(new Runnable() {
                        @Override public void run() { preload(activity); }
                    }, 1000L);
                } else {
                    initTries = 0; // out of retries: the next preload() call starts a fresh round
                }
                return;
            }
            initTries = 0;
            if (activity.isFinishing() || activity.isDestroyed()) return;

            loading = true;
            loadStartedAt = now;
            pressForce = false;
            CigramAdManager.report(app, "ad_request", "episode_break", "");
            InterstitialAd.load(
                    activity, // Activity context: required by some mediation adapters
                    CigramAdManager.playerInterstitialUnitId(),
                    new AdRequest.Builder().build(),
                    new InterstitialAdLoadCallback() {
                        @Override public void onAdLoaded(InterstitialAd ad) {
                            loading = false;
                            ready = ad;
                            readyLandscape = land;
                            readyAt = SystemClock.elapsedRealtime();
                            failures = 0;
                            nextLoadAt = 0L;
                            CigramAdManager.report(app, "ad_loaded", "episode_break", "");
                            diag(app, "ad loaded in " + (SystemClock.elapsedRealtime() - loadStartedAt) + "ms");
                            finishWait();
                        }

                        @Override public void onAdFailedToLoad(LoadAdError error) {
                            loading = false;
                            ready = null;
                            failures++;
                            // back-off: 30 s, 60 s, 2 min, 4 min, 5 min max
                            nextLoadAt = SystemClock.elapsedRealtime()
                                    + Math.min(300000L, 30000L << Math.min(failures - 1, 4));
                            CigramAdManager.report(app, "ad_failed", "episode_break",
                                    "load:" + (error == null ? -1 : error.getCode()));
                            Log.w(TAG, "episode interstitial load failed: " + error);
                            diag(app, "load failed code=" + (error == null ? -1 : error.getCode())
                                    + " " + (error == null ? "" : error.getMessage()));
                            finishWait();
                        }
                    });
        } catch (Throwable error) {
            loading = false;
            Log.w(TAG, "preload failed", error);
        }
    }

    /**
     * @param pause   optional: pauses the current episode right before an ad appears
     * @param proceed opens the next episode; always runs exactly once
     */
    public static void beforeEpisode(final Activity activity, final Runnable pause, final Runnable proceed) {
        beforeEpisode(activity, pause, proceed, true);
    }

    /**
     * Mid-roll inside a running player (v5.20): same shared limits, but never shows a loading
     * dialog and never makes the viewer wait: no ad ready means the film simply continues.
     */
    public static void midRoll(final Activity activity, final Runnable pause, final Runnable resume) {
        beforeEpisode(activity, pause, resume, false);
    }

    private static void beforeEpisode(final Activity activity, final Runnable pause,
                                      final Runnable proceed, final boolean allowWait) {
        if (waiting) return; // double tap while the loading dialog is up
        final boolean[] done = {false};
        final Runnable go = new Runnable() {
            @Override public void run() {
                if (done[0]) return;
                done[0] = true;
                try {
                    if (proceed != null) proceed.run();
                } catch (Throwable error) {
                    Log.w(TAG, "proceed failed", error);
                }
            }
        };
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                hideCover();
                go.run();
                return;
            }
            if (showing) {
                if (SystemClock.elapsedRealtime() - showingSince < SHOWING_STALE_MS) {
                    return; // duplicate request while an episode ad is on screen
                }
                showing = false; // stale flag: recover instead of blocking episodes forever
                CigramAdManager.releaseFullScreen(TOKEN);
            }
            final Context app = activity.getApplicationContext();
            long now = SystemClock.elapsedRealtime();
            boolean enabled = CigramAdConfig.episodeAdsEnabled(app);
            // v4.1: same hourly / session / cooldown limits as the mid-roll (shared, persisted)
            String why = enabled ? CigramAdManager.episodeAdBlockReason(app, TOKEN) : "episode_ads_disabled";
            // v6.2 / v10.34: a press of «شاهد هسه» ALWAYS gets its ad: no cooldown, no hourly or
            // session cap, and a failed earlier request does not stop a fresh one now.
            if (allowWait && ("cooldown".equals(why) || "hourly_cap".equals(why)
                    || "session_cap".equals(why))) why = "";
            if (allowWait && why.length() == 0) {
                pressForce = true;
                nextLoadAt = 0L;
            }
            if (!allowWait && why.length() == 0 && lastShownAt != 0L
                    && now - lastShownAt < CigramAdConfig.EPISODE_AD_GAP_MS) {
                why = "episode_gap";
            }
            if (why.length() > 0) {
                if (enabled && !"ad_free".equals(why)) {
                    CigramAdManager.report(app, "ad_skipped_by_policy", "episode_break", why);
                }
                diag(app, "skipped:" + why);
                preload(activity);
                go.run();
                return;
            }

            if (allowWait) {
                showCover(activity);   // black page first, whatever happens next
                setCoverContentVisible(true);
            }

            // 1) AdMob — in the orientation the viewer holds the phone in (v6.2: no forced turn)
            if (ready != null && now - readyAt > AD_MAX_AGE_MS) ready = null;
            if (ready != null && readyLandscape != isLandscape(activity)) ready = null;
            if (CigramAdConfig.ADMOB_ENABLED && allowWait && ready == null && startWait(activity, new Runnable() {
                @Override public void run() { beforeEpisode(activity, pause, proceed, false); }
            })) {
                return; // the loading dialog is up; this method runs again once the ad is ready or failed
            }
            final InterstitialAd ad = ready;
            if (ad != null && CigramAdManager.acquireFullScreen(TOKEN)) {
                ready = null;
                showing = true;
                showingSince = now;
                runQuietly(pause);
                ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                    @Override public void onAdShowedFullScreenContent() {
                        lastShownAt = SystemClock.elapsedRealtime();
                        lastAnyShownAt = lastShownAt;
                        // counts toward the shared hourly / session caps and the cooldown, and only
                        // now that the ad really is on screen (a failed show is never recorded)
                        CigramAdManager.recordPlayerAdShown(app);
                        CigramAdManager.report(app, "ad_show", "episode_break", "");
                    }

                    @Override public void onAdDismissedFullScreenContent() {
                        finish();
                    }

                    @Override public void onAdFailedToShowFullScreenContent(AdError adError) {
                        diag(app, "show failed " + (adError == null ? "" : adError.getCode() + " " + adError.getMessage()));
                        CigramAdManager.report(app, "ad_failed", "episode_break", "show");
                        finish();
                    }

                    private void finish() {
                        showing = false;
                        CigramAdManager.releaseFullScreen(TOKEN);
                        CigramAdManager.report(app, "ad_closed", "episode_break", "");
                        // open the episode first: when this screen is about to finish, preload()
                        // skips it, and the next player requests the following ad itself
                        go.run();
                        hideCoverSoon();
                        preload(activity);
                    }
                });
                Runnable doShow = new Runnable() {
                    @Override public void run() {
                        try {
                            ad.show(activity);
                        } catch (Throwable error) {
                            showing = false;
                            CigramAdManager.releaseFullScreen(TOKEN);
                            Log.w(TAG, "show failed", error);
                            go.run();
                            hideCover();
                        }
                    }
                };
                long visibleFor = coverDialog == null ? 0L
                        : COVER_MIN_MS - (SystemClock.elapsedRealtime() - coverShownAt);
                if (visibleFor > 0L) MAIN.postDelayed(doShow, visibleFor); else doShow.run();
                return;
            }


            // 2) nothing ready: open the episode now, get an ad ready for next time
            diag(app, "no ad ready (init=" + CigramAdManager.isInitialized() + " loading=" + loading
                    + " backoffMs=" + Math.max(0L, nextLoadAt - SystemClock.elapsedRealtime()) + ")");
            preload(activity);
            go.run();
            hideCoverSoon();
        } catch (Throwable error) {
            showing = false;
            CigramAdManager.releaseFullScreen(TOKEN);
            Log.w(TAG, "beforeEpisode failed", error);
            go.run();
            hideCover();
        }
    }

    /**
     * Shows "جاري تحميل الإعلان…" and waits (max 10 s) for the interstitial. Returns false when
     * waiting makes no sense (offline, no request can start, back-off after a failure) so the
     * caller carries on at once and the viewer is never blocked for nothing.
     */
    private static boolean startWait(final Activity activity, final Runnable resume) {
        try {
            final Context app = activity.getApplicationContext();
            long now = SystemClock.elapsedRealtime();
            if (!isOnline(app) || now < nextLoadAt || activity.isFinishing() || activity.isDestroyed()) return false;
            waiting = true;
            waitCb = resume;
            waitTimeout = new Runnable() {
                @Override public void run() { finishWait(); }
            };
            MAIN.postDelayed(waitTimeout, WAIT_TIMEOUT_MS);
            preload(activity);
            // preload() may return without a request (cap, destroyed screen): do not wait for nothing
            if (CigramAdManager.isInitialized() && !loading && ready == null) {
                finishWait();
                return true; // resume runs through finishWait
            }
            if (ready != null) finishWait();
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "startWait failed", error);
            Runnable cb = waitCb;
            clearWait();
            // fall through to the normal path (no dialog) by resuming immediately
            if (cb != null) { MAIN.post(cb); return true; }
            return false;
        }
    }

    /** Ends the wait exactly once: closes the dialog and resumes the pending episode flow. */
    private static void finishWait() {
        Runnable cb = waitCb;
        if (!waiting || cb == null) return;
        clearWait();
        MAIN.post(cb);
    }

    private static void clearWait() {
        waiting = false;
        waitCb = null;
        try { if (waitTimeout != null) MAIN.removeCallbacks(waitTimeout); } catch (Throwable ignored) { }
        waitTimeout = null;
    }

    private static void showCover(Activity a) {
        try {
            if (coverDialog != null) return;
            final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            d.setCancelable(false);
            android.widget.FrameLayout box = new android.widget.FrameLayout(a);
            box.setBackgroundColor(0xFF000000);
            android.widget.LinearLayout col = new android.widget.LinearLayout(a);
            col.setOrientation(android.widget.LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            float dn = a.getResources().getDisplayMetrics().density;
            col.setPadding((int) (32 * dn), (int) (24 * dn), (int) (32 * dn), (int) (24 * dn));
            android.widget.ProgressBar pb = new android.widget.ProgressBar(a);
            pb.setIndeterminate(true);
            try { pb.getIndeterminateDrawable().setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN); } catch (Throwable ignored) { }
            col.addView(pb, new android.widget.LinearLayout.LayoutParams((int) (44 * dn), (int) (44 * dn)));
            TextView t = new TextView(a);
            t.setText("جاري تحميل الإعلان");
            t.setTextColor(0xFFFFFFFF);
            t.setTextSize(18f);
            t.setGravity(Gravity.CENTER);
            try { t.setTypeface(CigramStage1InitProvider.cigramFont(a)); } catch (Throwable ignored) { }
            android.widget.LinearLayout.LayoutParams tp = new android.widget.LinearLayout.LayoutParams(-2, -2);
            tp.topMargin = (int) (18 * dn);
            col.addView(t, tp);
            box.addView(col, new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
            coverContent = col;
            d.setContentView(box);
            if (d.getWindow() != null) {
                android.view.Window w = d.getWindow();
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
                w.setLayout(-1, -1);
                try {
                    w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        android.view.WindowManager.LayoutParams lp = w.getAttributes();
                        lp.layoutInDisplayCutoutMode = 1; // SHORT_EDGES: fill behind the notch too
                        w.setAttributes(lp);
                    }
                    w.getDecorView().setSystemUiVisibility(0x00000002 | 0x00000004 | 0x00000200
                            | 0x00000400 | 0x00000100 | 0x00001000);
                } catch (Throwable ignored) { }
            }
            coverShownAt = SystemClock.elapsedRealtime();
            coverDialog = d;
            d.show();
        } catch (Throwable error) {
            Log.w(TAG, "cover failed", error);
            coverDialog = null;
        }
    }

    private static void hideCover() {
        try { if (coverDialog != null && coverDialog.isShowing()) coverDialog.dismiss(); } catch (Throwable ignored) { }
        coverDialog = null;
        coverContent = null;
        restoreOrientationSoon();
    }

    private static void setCoverContentVisible(boolean visible) {
        try {
            if (coverContent != null) coverContent.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
        } catch (Throwable ignored) { }
    }

    private static boolean isLandscape(Activity a) {
        try {
            return a.getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Asks for landscape. True when the screen has to turn first (it was portrait). */
    private static boolean toLandscape(Activity a) {
        try {
            if (a == null || isLandscape(a)) return false;
            int req = a.getRequestedOrientation();
            if (req == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) return false; // already asked
            if (rotatedActivity == null) {
                rotatedActivity = a;
                rotatedFrom = req;
            }
            a.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "landscape request failed", error);
            return false;
        }
    }

    private static void awaitLandscape(final Activity a, final long deadline, final Runnable then) {
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                boolean gone = a == null || a.isFinishing() || a.isDestroyed();
                if (gone || isLandscape(a) || SystemClock.elapsedRealtime() >= deadline) {
                    then.run();
                } else {
                    awaitLandscape(a, deadline, then);
                }
            }
        }, 120L);
    }

    /** Gives the screen its own orientation back once the next page (player) is on top. */
    private static void restoreOrientationSoon() {
        final Activity a = rotatedActivity;
        final int from = rotatedFrom;
        rotatedActivity = null;
        if (a == null) return;
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (!a.isFinishing() && !a.isDestroyed()) a.setRequestedOrientation(from);
                } catch (Throwable ignored) { }
            }
        }, 900L);
    }

    /** Lets the next screen draw underneath first, so no flash of the old page appears. */
    private static void hideCoverSoon() {
        MAIN.postDelayed(new Runnable() {
            @Override public void run() { hideCover(); }
        }, 350L);
    }

    private static void runQuietly(Runnable r) {
        try {
            if (r != null) r.run();
        } catch (Throwable ignored) {
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
}
