package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.google.android.ump.ConsentForm;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.FormError;
import com.google.android.ump.UserMessagingPlatform;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Google User Messaging Platform (UMP) consent flow.
 *
 * AdMob policy requires a certified CMP for users in the EEA / UK / Switzerland. Flow:
 *   1. {@link #gather(Activity)} from the launcher Activity (MainActivity.onCreate).
 *   2. {@link CigramAdManager#initialize(Context)} refuses to start the Mobile Ads SDK until
 *      {@link #canRequestAds(Context)} is true, so no ad request leaves the device before consent.
 *   3. Settings screen: when {@link #privacyOptionsRequired(Context)} is true, show a
 *      "Privacy settings" row that calls {@link #showPrivacyOptions(Activity, Runnable)}.
 *
 * Message to configure in AdMob console: Privacy &amp; messaging -> European regulations.
 */
final class CigramConsent {

    private static final String TAG = "CigramConsent";
    private static final AtomicBoolean GATHERING = new AtomicBoolean(false);

    private CigramConsent() {
    }

    /** True when consent has been resolved (or is not required) for this user. Fails closed. */
    static boolean canRequestAds(Context context) {
        try {
            return UserMessagingPlatform.getConsentInformation(context).canRequestAds();
        } catch (Throwable t) {
            Log.w(TAG, "canRequestAds failed", t);
            return false;
        }
    }

    /** Call from the launcher Activity. Safe to call repeatedly; shows the form only if required. */
    static void gather(final Activity activity) {
        if (activity == null) return;
        if (!CigramAdConfig.ADMOB_ENABLED) return;    // no AdMob = no EU consent form for it
        final Context app = activity.getApplicationContext();

        // Consent stored by a previous session: start ads immediately, refresh in the background.
        if (canRequestAds(app)) CigramAdManager.initialize(app);

        if (!GATHERING.compareAndSet(false, true)) return;
        try {
            final ConsentInformation info = UserMessagingPlatform.getConsentInformation(activity);
            ConsentRequestParameters params = new ConsentRequestParameters.Builder().build();
            info.requestConsentInfoUpdate(
                    activity,
                    params,
                    new ConsentInformation.OnConsentInfoUpdateSuccessListener() {
                        @Override
                        public void onConsentInfoUpdateSuccess() {
                            if (activity.isFinishing() || activity.isDestroyed()) {
                                finish(app, info);
                                return;
                            }
                            UserMessagingPlatform.loadAndShowConsentFormIfRequired(
                                    activity,
                                    new ConsentForm.OnConsentFormDismissedListener() {
                                        @Override
                                        public void onConsentFormDismissed(FormError error) {
                                            if (error != null) {
                                                Log.w(TAG, "consent form: " + error.getErrorCode() + " " + error.getMessage());
                                            }
                                            finish(app, info);
                                        }
                                    });
                        }
                    },
                    new ConsentInformation.OnConsentInfoUpdateFailureListener() {
                        @Override
                        public void onConsentInfoUpdateFailure(FormError error) {
                            Log.w(TAG, "consent update: " + error.getErrorCode() + " " + error.getMessage());
                            finish(app, info);
                        }
                    });
        } catch (Throwable t) {
            GATHERING.set(false);
            Log.w(TAG, "gather failed", t);
        }
    }

    private static void finish(Context app, ConsentInformation info) {
        GATHERING.set(false);
        if (info.canRequestAds()) CigramAdManager.initialize(app);
    }

    /** True when the user must be able to reopen the consent choices (show the "Privacy settings" row). */
    static boolean privacyOptionsRequired(Context context) {
        try {
            return UserMessagingPlatform.getConsentInformation(context).getPrivacyOptionsRequirementStatus()
                    == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
        } catch (Throwable t) {
            return false;
        }
    }

    static void showPrivacyOptions(Activity activity, final Runnable after) {
        if (activity == null) return;
        try {
            UserMessagingPlatform.showPrivacyOptionsForm(activity, new ConsentForm.OnConsentFormDismissedListener() {
                @Override
                public void onConsentFormDismissed(FormError error) {
                    if (error != null) Log.w(TAG, "privacy options: " + error.getMessage());
                    if (after != null) after.run();
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "showPrivacyOptions failed", t);
        }
    }
}
