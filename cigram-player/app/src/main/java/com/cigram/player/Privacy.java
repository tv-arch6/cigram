package com.cigram.player;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The user's choice about ad statistics.
 *   ALLOWED  - the app sends a random per-installation id with ad impression/click counts to the Cigram ads server.
 *   DECLINED - ads can still be shown (they are fetched without any id), but NO id and NO impression/click events are sent.
 *   UNSET    - nothing is sent and no ad is shown until the user has answered (the question is asked before the first ad).
 * The id is never linked to an account and is not the Google advertising ID.
 */
final class Privacy {

    static final int UNSET = 0;
    static final int ALLOWED = 1;
    static final int DECLINED = 2;

    private static final String PREFS = "cigram_player_privacy";

    private Privacy() { }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int state(Context c) {
        return sp(c).getInt("ads_stats", UNSET);
    }

    static boolean decided(Context c) {
        return state(c) != UNSET;
    }

    static boolean statsAllowed(Context c) {
        return state(c) == ALLOWED;
    }

    static void set(Context c, boolean allow) {
        sp(c).edit().putInt("ads_stats", allow ? ALLOWED : DECLINED).putLong("ads_stats_at", System.currentTimeMillis()).apply();
        if (!allow) AdsClient.forgetTracking(c);
    }
}
