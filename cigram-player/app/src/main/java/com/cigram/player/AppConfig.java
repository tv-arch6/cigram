package com.cigram.player;

/**
 * Values the publisher must supply before a Google Play submission.
 * Nothing here is invented: empty means "not set yet" and the matching UI is hidden or flagged.
 */
final class AppConfig {

    private AppConfig() { }

    /**
     * Google AdMob inside the player: an "app open" ad when the app is opened from its icon, and an interstitial when the
     * user presses watch-now (hand-over from the Cigram app, or play in this app). IDs live in res/values/admob.xml
     * (test IDs until you replace them - see ADMOB-SETUP.md).
     */
    static final boolean ADMOB_ENABLED = true;

    /**
     * The in-house ad system of the Cigram ads worker (pre-roll, mid-rolls, pause card). OFF: the only ads are the two
     * AdMob placements above. Set true to use the in-house ads as well (they then ask for their own statistics consent).
     */
    static final boolean OWN_ADS_ENABLED = false;

    /** Package id of the main Cigram app (optional companion). */
    static final String MAIN_APP_PACKAGE = "com.Cigram.vid";

    /**
     * REQUIRED for Google Play: public https URL of your privacy policy (also entered in Play Console).
     * Example shape only: "https://your-domain.example/privacy". Left empty on purpose.
     */
    static final String PRIVACY_POLICY_URL = "";

    /** Optional support e-mail shown on the About/Privacy screen. Left empty on purpose. */
    static final String SUPPORT_EMAIL = "";
}
