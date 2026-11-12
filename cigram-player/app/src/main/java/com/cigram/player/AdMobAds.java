package com.cigram.player;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.appopen.AppOpenAd;
import com.google.android.gms.ads.initialization.InitializationStatus;
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.ump.ConsentForm;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.FormError;
import com.google.android.ump.UserMessagingPlatform;

import java.util.ArrayList;
import java.util.List;

/**
 * Google AdMob for the Cigram Player. Two placements only, as requested:
 *   1) APP OPEN   - a full-screen "app open" ad when the player app is opened from its icon (cold start), at most once every 4 h.
 *   2) WATCH NOW  - an interstitial when the user presses «شاهد هسه» (the Cigram app hands the title over) or presses play on
 *                   a title in this app. Never in the middle of the film, never on pause, never on its own.
 * Flow: EU/UK consent (Google UMP) -> SDK start on a background thread -> request. If consent is refused, the SDK cannot be
 * started, the network is down, or the ad is slow (3.5 s), playback simply starts: an ad can never block the film.
 * Earnings go to the AdMob account that owns the IDs in res/values/admob.xml (test IDs until you replace them).
 */
final class AdMobAds {

    private static final String TAG = "CigramAdMob";
    private static final String PREFS = "cigram_player_admob";
    private static final long READY_TIMEOUT_MS = 8000L;
    private static final long INTERSTITIAL_WAIT_MS = 3500L;
    private static final long INTERSTITIAL_GAP_MS = 45000L;        // blocks an accidental double tap, not a real second press
    private static final long AD_MAX_AGE_MS = 45L * 60L * 1000L;   // AdMob interstitials expire after ~1 h
    private static final long APP_OPEN_GAP_MS = 4L * 60L * 60L * 1000L;
    private static final long APP_OPEN_WAIT_MS = 4000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Ready> WAITING = new ArrayList<Ready>();

    private static boolean sdkStarted, sdkStarting, consentRunning;
    private static InterstitialAd interstitial;
    private static long interstitialAt, lastInterstitialShown;
    private static boolean interstitialLoading;
    private static final List<Runnable> LOAD_WAITERS = new ArrayList<Runnable>();
    private static boolean fullScreenShowing;
    private static boolean appOpenLoading;

    private AdMobAds() { }

    interface Ready { void onReady(boolean canRequestAds); }

    // ================================================================== consent + SDK start

    static boolean canRequestAds(Context c) {
        try {
            return UserMessagingPlatform.getConsentInformation(c).canRequestAds();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Consent (UMP) then SDK start. {@code cb} runs once on the main thread; false = no ads this time. */
    static void whenReady(final Activity a, final Ready cb) {
        if (!AppConfig.ADMOB_ENABLED || a == null) { cb.onReady(false); return; }
        if (sdkStarted) { cb.onReady(true); return; }
        WAITING.add(cb);
        if (consentRunning || sdkStarting) return;
        consentRunning = true;
        final Context app = a.getApplicationContext();
        MAIN.postDelayed(new Runnable() {
            @Override public void run() { if (!sdkStarted) flush(false); }
        }, READY_TIMEOUT_MS);
        try {
            if (canRequestAds(app)) startSdk(app);       // consent stored by an earlier session
            final ConsentInformation info = UserMessagingPlatform.getConsentInformation(a);
            info.requestConsentInfoUpdate(a, new ConsentRequestParameters.Builder().build(),
                    new ConsentInformation.OnConsentInfoUpdateSuccessListener() {
                        @Override public void onConsentInfoUpdateSuccess() {
                            if (!Ui.alive(a)) { consentDone(app, info); return; }
                            UserMessagingPlatform.loadAndShowConsentFormIfRequired(a, new ConsentForm.OnConsentFormDismissedListener() {
                                @Override public void onConsentFormDismissed(FormError error) {
                                    if (error != null) Log.w(TAG, "consent form: " + error.getMessage());
                                    consentDone(app, info);
                                }
                            });
                        }
                    },
                    new ConsentInformation.OnConsentInfoUpdateFailureListener() {
                        @Override public void onConsentInfoUpdateFailure(FormError error) {
                            Log.w(TAG, "consent update: " + error.getMessage());
                            consentDone(app, info);
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "consent failed", t);
            consentRunning = false;
            flush(false);
        }
    }

    private static void consentDone(Context app, ConsentInformation info) {
        consentRunning = false;
        if (info.canRequestAds()) startSdk(app); else flush(false);
    }

    private static void startSdk(final Context app) {
        if (sdkStarted || sdkStarting) return;
        sdkStarting = true;
        // Google recommends starting the SDK off the main thread
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    MobileAds.initialize(app, new OnInitializationCompleteListener() {
                        @Override public void onInitializationComplete(InitializationStatus status) {
                            sdkStarted = true;
                            sdkStarting = false;
                            flush(true);
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "MobileAds.initialize failed", t);
                    sdkStarting = false;
                    flush(false);
                }
            }
        }, "cigram-admob-init").start();
    }

    private static void flush(final boolean ok) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                List<Ready> copy = new ArrayList<Ready>(WAITING);
                WAITING.clear();
                for (Ready r : copy) {
                    try { r.onReady(ok); } catch (Throwable t) { Log.w(TAG, "ready callback failed", t); }
                }
            }
        });
    }

    /** True when the user must be able to reopen the consent choices (EU/UK). */
    static boolean privacyOptionsRequired(Context c) {
        try {
            return AppConfig.ADMOB_ENABLED && UserMessagingPlatform.getConsentInformation(c)
                    .getPrivacyOptionsRequirementStatus() == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
        } catch (Throwable t) {
            return false;
        }
    }

    static void showPrivacyOptions(Activity a) {
        try {
            UserMessagingPlatform.showPrivacyOptionsForm(a, new ConsentForm.OnConsentFormDismissedListener() {
                @Override public void onConsentFormDismissed(FormError error) {
                    if (error != null) Log.w(TAG, "privacy options: " + error.getMessage());
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "showPrivacyOptions failed", t);
        }
    }

    // ================================================================== placement 2: WATCH NOW (interstitial)

    /** Warm the next interstitial (call after one was shown, or as soon as consent allows). */
    static void preloadInterstitial(final Activity a) {
        whenReady(a, new Ready() {
            @Override public void onReady(boolean ok) {
                if (ok && Ui.alive(a)) loadInterstitial(a);
            }
        });
    }

    private static InterstitialAd takeFresh() {
        InterstitialAd ad = interstitial;
        if (ad != null && SystemClock.elapsedRealtime() - interstitialAt > AD_MAX_AGE_MS) ad = null;
        interstitial = null;
        return ad;
    }

    private static void loadInterstitial(final Activity a) {
        if (interstitial != null || interstitialLoading) return;
        interstitialLoading = true;
        try {
            InterstitialAd.load(a, a.getString(R.string.admob_interstitial_unit), new AdRequest.Builder().build(),
                    new InterstitialAdLoadCallback() {
                        @Override public void onAdLoaded(InterstitialAd ad) {
                            interstitialLoading = false;
                            interstitial = ad;
                            interstitialAt = SystemClock.elapsedRealtime();
                            runWaiters();
                        }

                        @Override public void onAdFailedToLoad(LoadAdError error) {
                            interstitialLoading = false;
                            interstitial = null;
                            Log.w(TAG, "interstitial load failed: " + (error == null ? "" : error.getCode() + " " + error.getMessage()));
                            runWaiters();
                        }
                    });
        } catch (Throwable t) {
            interstitialLoading = false;
            Log.w(TAG, "interstitial load error", t);
            runWaiters();
        }
    }

    private static void runWaiters() {
        List<Runnable> copy = new ArrayList<Runnable>(LOAD_WAITERS);
        LOAD_WAITERS.clear();
        for (Runnable r : copy) r.run();
    }

    /**
     * The «watch now» ad. {@code done} runs exactly once, on the main thread, when the ad was closed or when no ad
     * could be shown (consent refused, offline, no fill, too slow, too soon after the last one).
     */
    static void showWatchNowAd(final Activity a, final Runnable done) {
        final boolean[] fin = {false};
        final Runnable once = new Runnable() {
            @Override public void run() {
                if (fin[0]) return;
                fin[0] = true;
                done.run();
            }
        };
        whenReady(a, new Ready() {
            @Override public void onReady(boolean ok) {
                if (!ok || !Ui.alive(a) || fullScreenShowing) { once.run(); return; }
                if (lastInterstitialShown != 0L && SystemClock.elapsedRealtime() - lastInterstitialShown < INTERSTITIAL_GAP_MS) {
                    once.run();
                    loadInterstitial(a);
                    return;
                }
                InterstitialAd ready = takeFresh();
                if (ready != null) { show(a, ready, once); return; }

                final Runnable timeout = new Runnable() {
                    @Override public void run() { once.run(); }          // too slow: play now; the load keeps going for next time
                };
                MAIN.postDelayed(timeout, INTERSTITIAL_WAIT_MS);
                LOAD_WAITERS.add(new Runnable() {
                    @Override public void run() {
                        MAIN.removeCallbacks(timeout);
                        if (fin[0]) return;                                // timed out already: leave the ad cached
                        InterstitialAd ad = takeFresh();
                        if (ad == null || !Ui.alive(a)) { once.run(); return; }
                        show(a, ad, once);
                    }
                });
                loadInterstitial(a);
            }
        });
    }

    private static void show(final Activity a, InterstitialAd ad, final Runnable once) {
        fullScreenShowing = true;
        ad.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override public void onAdShowedFullScreenContent() {
                lastInterstitialShown = SystemClock.elapsedRealtime();
            }

            @Override public void onAdDismissedFullScreenContent() {
                fullScreenShowing = false;
                once.run();
                if (Ui.alive(a)) loadInterstitial(a);
            }

            @Override public void onAdFailedToShowFullScreenContent(AdError error) {
                fullScreenShowing = false;
                Log.w(TAG, "interstitial show failed: " + (error == null ? "" : error.getMessage()));
                once.run();
            }
        });
        try {
            ad.show(a);
        } catch (Throwable t) {
            fullScreenShowing = false;
            Log.w(TAG, "interstitial show error", t);
            once.run();
        }
    }

    // ================================================================== placement 1: APP OPEN

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** App-open ad when the app is opened from its icon; at most once every 4 hours; never delays the screen. */
    static void showAppOpenIfDue(final Activity a) {
        if (!AppConfig.ADMOB_ENABLED || a == null || appOpenLoading) return;
        if (System.currentTimeMillis() - sp(a).getLong("app_open_at", 0L) < APP_OPEN_GAP_MS) return;
        whenReady(a, new Ready() {
            @Override public void onReady(boolean ok) {
                if (!ok || !Ui.alive(a) || fullScreenShowing) return;
                appOpenLoading = true;
                final long started = SystemClock.elapsedRealtime();
                final int orientation = a.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE ? 2 : 1;
                try {
                    AppOpenAd.load(a, a.getString(R.string.admob_app_open_unit), new AdRequest.Builder().build(), orientation,
                            new AppOpenAd.AppOpenAdLoadCallback() {
                                @Override public void onAdLoaded(final AppOpenAd ad) {
                                    appOpenLoading = false;
                                    // a late ad would pop up over something the user is already doing: only inside the launch window
                                    if (SystemClock.elapsedRealtime() - started > APP_OPEN_WAIT_MS || !Ui.alive(a) || fullScreenShowing) return;
                                    fullScreenShowing = true;
                                    ad.setFullScreenContentCallback(new FullScreenContentCallback() {
                                        @Override public void onAdShowedFullScreenContent() {
                                            sp(a).edit().putLong("app_open_at", System.currentTimeMillis()).apply();
                                        }

                                        @Override public void onAdDismissedFullScreenContent() { fullScreenShowing = false; }

                                        @Override public void onAdFailedToShowFullScreenContent(AdError error) { fullScreenShowing = false; }
                                    });
                                    try {
                                        ad.show(a);
                                    } catch (Throwable t) {
                                        fullScreenShowing = false;
                                    }
                                }

                                @Override public void onAdFailedToLoad(LoadAdError error) {
                                    appOpenLoading = false;
                                    Log.w(TAG, "app open load failed: " + (error == null ? "" : error.getCode() + " " + error.getMessage()));
                                }
                            });
                } catch (Throwable t) {
                    appOpenLoading = false;
                    Log.w(TAG, "app open error", t);
                }
            }
        });
    }
}
