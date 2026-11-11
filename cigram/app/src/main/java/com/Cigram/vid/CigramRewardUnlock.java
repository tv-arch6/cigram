package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.OnUserEarnedRewardListener;
import com.google.android.gms.ads.rewarded.RewardItem;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAd;
import com.google.android.gms.ads.rewardedinterstitial.RewardedInterstitialAdLoadCallback;

import java.util.HashSet;

/**
 * v10.35: rewarded unlock (same idea as OTTO TV).
 *
 * «شاهد هسه», an episode picked from the list and the automatic next episode ask the viewer to
 * watch ONE rewarded interstitial to the end. Only then the film / episode opens. What was
 * unlocked stays open until the app process ends (kept in memory, exactly like OTTO), so going
 * back and pressing the same episode again does not ask for a second ad.
 *
 * v10.36: the wait before the ad is a mandatory plain black PORTRAIT (vertical) screen
 * ({@link CigramWaitActivity}) with only "Please wait (5)..." counting 5, 4, 3, 2, 1 in the
 * middle (no text, no buttons, back is disabled); the ad then starts by itself on that screen.
 *
 * The viewer is never blocked for reasons that are not theirs: offline, no ad available (no
 * fill), a load that takes longer than WAIT_TIMEOUT_MS, a show failure or an ad-free account =>
 * the title opens without an AdMob ad (an in-house break ad is shown first when one is cached).
 * Closing the ad before the end => nothing opens, and "يجب إكمال مشاهدة الإعلان للمتابعة".
 *
 * Remote switch: "reward_unlock_enabled": false in the ads-config Worker brings back the old
 * interstitial on every press ({@link CigramEpisodeAds#beforeEpisode}).
 */
public final class CigramRewardUnlock {
    private static final String TAG = "CigramRewardUnlock";
    private static final String PLACEMENT = "reward_unlock";
    /** Seconds on the black wait screen before the ad starts by itself. */
    private static final int INTRO_SECONDS = 5;
    /** Longest wait for an ad after the press; past it the title opens without one. */
    private static final long WAIT_TIMEOUT_MS = 15000L;
    /** Rewarded ads expire after 1 h at AdMob; refresh a bit earlier. */
    private static final long AD_MAX_AGE_MS = 45L * 60L * 1000L;
    /** A busy flag older than this is stale (a callback never came back). */
    private static final long BUSY_STALE_MS = 3L * 60L * 1000L;
    /** A late reward callback may arrive just after the ad was closed. */
    private static final long REWARD_GRACE_MS = 400L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object TOKEN = new Object();
    private static final HashSet<String> UNLOCKED = new HashSet<String>();

    private static RewardedInterstitialAd ready;
    private static long readyAt;
    /** Every ad is shown on the portrait wait screen, so every ad is requested for portrait. */
    private static final boolean AD_LANDSCAPE = false;
    private static boolean readyLandscape;
    private static boolean loading;
    private static long loadStartedAt;
    private static long nextLoadAt;
    private static int failures;
    private static int initTries;
    private static boolean showing;
    private static boolean busy;
    private static long busySince;
    private static Request current;

    /** One press: everything the intro screen and the ad need. */
    private static final class Request {
        Activity activity;
        Context app;
        String key;
        boolean episode;
        Runnable pause;
        Runnable go;
        /** The black portrait wait screen (null until it is up, and after it was closed). */
        Activity host;
        TextView status;
        boolean deferredFail;
        int left = INTRO_SECONDS;
        boolean countdownDone;
        boolean started;
        boolean finished;
        boolean opened;
        Runnable tick;
        Runnable timeout;
    }

    private CigramRewardUnlock() {
    }

    /** True when this film / episode was already unlocked in this app session. */
    public static boolean isUnlocked(String key) {
        return key != null && key.length() > 0 && UNLOCKED.contains(key);
    }

    /**
     * @param key     stable key of the video that will play ("v|" + video url); "" = not remembered
     * @param episode true for an episode, false for a film (only changes the wording)
     * @param pause   optional: pauses what is playing right before the ad appears
     * @param proceed opens the film / episode; runs at most once, and only when allowed
     */
    public static void unlock(final Activity activity, final String key, final boolean episode,
                              final Runnable pause, final Runnable proceed) {
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
        // A television: AdMob rewarded ads are not served there, so the black "Please wait" screen and the
        // wait of up to 15 s for an ad that cannot appear only delayed every title. Open it at once.
        // (Phones and tablets are unchanged.)
        try {
            if (activity != null && CigramTvMode.isTv(activity)) {
                go.run();
                return;
            }
        } catch (Throwable ignored) { }
        try {
            if (activity == null || !AdsUi.alive(activity)) {
                go.run();
                return;
            }
            final Context app = activity.getApplicationContext();
            if (!CigramAdConfig.rewardUnlockEnabled(app)) {
                // remote switch off: the old behaviour (interstitial on every press)
                CigramEpisodeAds.beforeEpisode(activity, pause, proceed);
                return;
            }
            if (CigramAdConfig.adFreeUser(app)) {
                go.run();
                return;
            }
            final String k = key == null ? "" : key.trim();
            if (isUnlocked(k)) {
                go.run();
                return;
            }
            if (busy && SystemClock.elapsedRealtime() - busySince < BUSY_STALE_MS) {
                return; // double tap while the intro screen or the ad is up
            }
            if (!isOnline(app)) {
                go.run(); // no internet: never block the viewer for an ad that cannot load
                return;
            }
            busy = true;
            busySince = SystemClock.elapsedRealtime();

            Request r = new Request();
            r.activity = activity;
            r.app = app;
            r.key = k;
            r.episode = episode;
            r.pause = pause;
            r.go = go;
            current = r;
            CigramAdManager.report(app, "reward_intro", PLACEMENT, episode ? "episode" : "movie");

            preload(activity, true);   // a press always gets a fresh request (no back-off)
            startTimeout(r);
            if (!launchHost(r)) {
                finish(r);
                go.run(); // the wait screen could not open: never block the viewer
            }
        } catch (Throwable error) {
            Log.w(TAG, "unlock failed", error);
            busy = false;
            go.run();
        }
    }

    // ------------------------------------------------------------------ loading

    /** Gets a rewarded ad ready in the background. Safe to call as often as you like. */
    public static void preload(Activity activity) {
        preload(activity, false);
    }

    private static void preload(final Activity activity, final boolean force) {
        if (activity == null) return;
        final Context app = activity.getApplicationContext();
        try {
            if (!CigramAdConfig.rewardUnlockEnabled(app) || CigramAdConfig.adFreeUser(app)) return;
            CigramAdManager.initialize(app);
            final boolean land = AD_LANDSCAPE;
            long now = SystemClock.elapsedRealtime();
            if (loading && now - loadStartedAt > 30000L) loading = false; // watchdog: lost callback
            if (ready != null && now - readyAt > AD_MAX_AGE_MS) ready = null;
            // AdMob draws the ad in the orientation it was loaded in: get one for this screen
            if (ready != null && readyLandscape != land && !showing) ready = null;
            if (showing || loading || ready != null) return;
            if (!force && now < nextLoadAt) return;
            if (!isOnline(app) || activity.isFinishing() || activity.isDestroyed()) return;
            if (!force && !CigramAdManager.isInitialized()) {
                if (initTries++ < 12) {
                    MAIN.postDelayed(new Runnable() {
                        @Override public void run() { preload(activity, false); }
                    }, 1000L);
                } else {
                    initTries = 0;
                }
                return;
            }
            initTries = 0;
            loading = true;
            loadStartedAt = now;
            CigramAdManager.report(app, "ad_request", PLACEMENT, force ? "press" : "");
            RewardedInterstitialAd.load(
                    activity, // Activity context: required by some mediation adapters
                    CigramAdConfig.rewardedInterstitialUnit(),
                    new AdRequest.Builder().build(),
                    new RewardedInterstitialAdLoadCallback() {
                        @Override public void onAdLoaded(RewardedInterstitialAd ad) {
                            loading = false;
                            ready = ad;
                            readyAt = SystemClock.elapsedRealtime();
                            readyLandscape = land;
                            failures = 0;
                            nextLoadAt = 0L;
                            CigramAdManager.report(app, "ad_loaded", PLACEMENT,
                                    String.valueOf(readyAt - loadStartedAt));
                            onLoadResult(true);
                        }

                        @Override public void onAdFailedToLoad(LoadAdError error) {
                            loading = false;
                            ready = null;
                            failures++;
                            // back-off for background preloads: 30 s, 60 s, 2 min, 4 min, 5 min max
                            nextLoadAt = SystemClock.elapsedRealtime()
                                    + Math.min(300000L, 30000L << Math.min(failures - 1, 4));
                            CigramAdManager.report(app, "ad_failed", PLACEMENT,
                                    "load:" + (error == null ? -1 : error.getCode()));
                            Log.w(TAG, "rewarded interstitial load failed: " + error);
                            onLoadResult(false);
                        }
                    });
        } catch (Throwable error) {
            loading = false;
            Log.w(TAG, "preload failed", error);
        }
    }

    private static void onLoadResult(boolean ok) {
        Request r = current;
        if (r == null || r.finished || r.started) return;
        if (ok) maybeStart(r);
        else if (r.host == null) r.deferredFail = true; // the wait screen is not up yet: it asks again
        else noFill(r, "load_failed");
    }

    // ------------------------------------------------------------------ wait screen

    private static boolean launchHost(Request r) {
        try {
            android.content.Intent intent = new android.content.Intent(r.activity, CigramWaitActivity.class);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION);
            r.activity.startActivity(intent);
            r.activity.overridePendingTransition(0, 0);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "wait screen failed", error);
            return false;
        }
    }

    /** Called by {@link CigramWaitActivity} once its black screen is up. False = nothing to wait for. */
    static boolean attachHost(Activity host, TextView label) {
        Request r = current;
        if (r == null || r.finished || r.host != null) return false;
        r.host = host;
        r.status = label;
        setStatus(r, waitText(r.left));
        startTick(r);
        if (r.deferredFail) {
            noFill(r, "load_failed");
            return true;
        }
        maybeStart(r);
        return true;
    }

    /** True while {@code host} is the wait screen of a request that is still running. */
    static boolean isHostActive(Activity host) {
        Request r = current;
        return r != null && !r.finished && r.host == host;
    }

    /** The wait screen was destroyed (swiped away, process trimmed) before the ad started. */
    static void hostDestroyed(Activity host) {
        Request r = current;
        if (r == null || r.host != host) return;
        r.host = null;
        if (!r.started) abandon(r);
    }

    private static String waitText(int seconds) {
        return seconds > 0 ? "Please wait (" + seconds + ")..." : "Please wait...";
    }

    private static void startTick(final Request r) {
        r.tick = new Runnable() {
            @Override public void run() {
                if (r.finished || r.started || r.countdownDone) return;
                if (!AdsUi.alive(r.activity)) {
                    abandon(r);
                    return;
                }
                r.left--;
                if (r.left <= 0) {
                    r.countdownDone = true;
                    setStatus(r, waitText(0));
                    maybeStart(r);
                    return;
                }
                setStatus(r, waitText(r.left));
                MAIN.postDelayed(this, 1000L);
            }
        };
        MAIN.postDelayed(r.tick, 1000L);
    }

    private static void startTimeout(final Request r) {
        r.timeout = new Runnable() {
            @Override public void run() {
                if (r.finished || r.started) return;
                noFill(r, "timeout");
            }
        };
        MAIN.postDelayed(r.timeout, WAIT_TIMEOUT_MS);
    }

    private static void setStatus(Request r, String s) {
        try {
            if (r.status != null) r.status.setText(s);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ decisions

    /** Starts the ad once the countdown is over (or the button was pressed) and an ad is ready. */
    private static void maybeStart(Request r) {
        if (r.finished || r.started || r.host == null) return;
        if (!AdsUi.alive(r.activity)) {
            abandon(r);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (ready != null && now - readyAt > AD_MAX_AGE_MS) ready = null;
        if (ready != null && readyLandscape != AD_LANDSCAPE) ready = null;
        if (ready == null) {
            if (!loading) preload(r.activity, true);
            if (!loading && ready == null) {
                noFill(r, "no_request"); // nothing could be requested: do not keep the viewer waiting
                return;
            }
            if (ready == null) {
                if (r.countdownDone) setStatus(r, waitText(0));
                return; // onLoadResult() comes back here
            }
        }
        if (!r.countdownDone) return; // the ad is ready; it starts when the countdown ends
        startAd(r);
    }

    private static void startAd(final Request r) {
        final RewardedInterstitialAd ad = ready;
        if (ad == null || !CigramAdManager.acquireFullScreen(TOKEN)) {
            noFill(r, ad == null ? "no_ad" : "other_ad_showing");
            return;
        }
        r.started = true;
        stopTimers(r);
        ready = null;
        showing = true;
        final Context app = r.app;
        final boolean[] earned = {false};
        final boolean[] decided = {false};
        try {
            if (r.status != null) r.status.setVisibility(View.INVISIBLE); // plain black page behind the ad
        } catch (Throwable ignored) { }
        runQuietly(r.pause);
        try {
            ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override public void onAdShowedFullScreenContent() {
                    // counts toward the shared hourly / session caps and the cooldown
                    CigramAdManager.recordPlayerAdShown(app);
                    CigramEpisodeAds.markShownNow();
                    CigramAdManager.report(app, "ad_show", PLACEMENT, "");
                }

                @Override public void onAdDismissedFullScreenContent() {
                    showing = false;
                    CigramAdManager.releaseFullScreen(TOKEN);
                    CigramAdManager.report(app, "ad_closed", PLACEMENT, earned[0] ? "earned" : "not_earned");
                    MAIN.postDelayed(new Runnable() {
                        @Override public void run() {
                            decided[0] = true;
                            finish(r);
                            if (earned[0]) {
                                unlockAndGo(r);
                            } else {
                                closeHost(r);
                                CigramAdManager.report(app, "reward_not_earned", PLACEMENT, "");
                                try {
                                    Toast.makeText(app, "يجب إكمال مشاهدة الإعلان للمتابعة", Toast.LENGTH_LONG).show();
                                } catch (Throwable ignored) { }
                            }
                            preload(r.activity, false); // next one ready for the next episode
                        }
                    }, REWARD_GRACE_MS);
                }

                @Override public void onAdFailedToShowFullScreenContent(AdError adError) {
                    showing = false;
                    CigramAdManager.releaseFullScreen(TOKEN);
                    CigramAdManager.report(app, "ad_failed", PLACEMENT,
                            "show:" + (adError == null ? -1 : adError.getCode()));
                    decided[0] = true;
                    finish(r);
                    afterHost(r, r.go); // not the viewer's fault: open the title
                }
            });
            ad.show(r.host != null ? r.host : r.activity, new OnUserEarnedRewardListener() {
                @Override public void onUserEarnedReward(RewardItem rewardItem) {
                    earned[0] = true;
                    // a very late reward (after the "must finish" message): honour it anyway
                    if (decided[0]) unlockAndGo(r);
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "show failed", error);
            showing = false;
            CigramAdManager.releaseFullScreen(TOKEN);
            finish(r);
            afterHost(r, r.go);
        }
    }

    private static void unlockAndGo(final Request r) {
        if (r.opened) return;
        r.opened = true;
        if (r.key != null && r.key.length() > 0) UNLOCKED.add(r.key);
        CigramAdManager.report(r.app, "reward_earned", PLACEMENT, "");
        afterHost(r, r.go); // the black screen goes first, so the chooser / player appears on the film page
    }

    /**
     * No AdMob ad could be shown (not the viewer's fault): the content opens straight away (r.go), with no replacement ad
     * of any kind. The black wait screen is closed first (afterHost), exactly as on a rewarded unlock.
     */
    private static void noFill(final Request r, String why) {
        if (r.finished || r.started) return;
        finish(r);
        CigramAdManager.report(r.app, "ad_skipped", PLACEMENT, why);
        afterHost(r, r.go);
    }

    /** The screen went away while the wait screen was up: stop quietly. */
    private static void abandon(Request r) {
        if (r.finished) return;
        finish(r);
        closeHost(r);
    }

    private static void finish(Request r) {
        r.finished = true;
        stopTimers(r);
        if (current == r) current = null;
        busy = false;
    }

    private static void stopTick(Request r) {
        try { if (r.tick != null) MAIN.removeCallbacks(r.tick); } catch (Throwable ignored) { }
    }

    private static void stopTimers(Request r) {
        stopTick(r);
        try { if (r.timeout != null) MAIN.removeCallbacks(r.timeout); } catch (Throwable ignored) { }
    }

    /** Closes the black wait screen. True when one was up. */
    private static boolean closeHost(Request r) {
        Activity h = r.host;
        r.host = null;
        r.status = null;
        if (h == null) return false;
        try {
            h.finish();
            h.overridePendingTransition(0, 0);
        } catch (Throwable ignored) { }
        return true;
    }

    /** Closes the wait screen, then runs {@code then} once the film page is back on top. */
    private static void afterHost(final Request r, final Runnable then) {
        if (!closeHost(r)) {
            runQuietly(then);
            return;
        }
        MAIN.postDelayed(new Runnable() {
            @Override public void run() { runQuietly(then); }
        }, 300L);
    }

    // ------------------------------------------------------------------ helpers

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
