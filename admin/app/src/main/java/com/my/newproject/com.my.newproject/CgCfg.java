package com.my.newproject;

/**
 * CIGRAM ADMIN v3 - central configuration.
 * The admin token is never in the source (the repo is public). The admin types it once in the app; it is kept in this
 * phone's private preferences (allowBackup=false) and read into {@link #TOKEN} when the app starts.
 */
final class CgCfg {

    private CgCfg() {}

    static final String API = "https://cigram-admin-api.wwq-mixtv.workers.dev";
    static final String R2 = "https://pub-eabe41d14a0644e5800344c99ef4d971.r2.dev";
    static final String CHANNELS_API = "https://sigram-channels-api.wwq-mixtv.workers.dev";
    static final String METADATA_API = "https://cigram-metadata-api.wwq-mixtv.workers.dev";
    /** Set by {@link #loadToken}; empty until the admin has entered it. */
    static volatile String TOKEN = "";

    private static final String TOKEN_KEY = "admin_token";

    static void loadToken(android.content.Context c) {
        try {
            TOKEN = c.getApplicationContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(TOKEN_KEY, "").trim();
        } catch (Exception e) {
            TOKEN = "";
        }
    }

    static void saveToken(android.content.Context c, String token) {
        TOKEN = token == null ? "" : token.trim();
        try {
            c.getApplicationContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString(TOKEN_KEY, TOKEN).apply();
        } catch (Exception ignored) { }
    }

    static boolean hasToken() {
        return TOKEN != null && TOKEN.length() > 0;
    }

    static final String PREFS = "cigram_admin_v2";

    // v3.0 "Aurora Night": a deeper blue-black base with a real elevation ladder, a vivid indigo→violet
    // primary and a cyan second accent. Surfaces are rounded and softly lifted (CgUi gives them elevation);
    // gradients come back, but only on the accents (primary buttons, section icons, chart fills).
    static final int PAGE = 0xFF080A12;      // window / page background (deepest)
    static final int PAGE2 = 0xFF0C1020;     // hero / header wash
    static final int CARD = 0xFF121726;      // cards
    static final int CARD2 = 0xFF1B2236;     // raised / pressed / tonal surfaces
    static final int CARD3 = 0xFF232C44;     // highest surface (chips, inner tiles)
    static final int STROKE = 0xFF2A3350;    // dividers, hairlines
    static final int STROKE_SOFT = 0xFF1E2538; // barely-there separators
    static final int FIELD = 0xFF0C101B;     // text inputs
    static final int TEXT = 0xFFF4F6FC;      // primary text
    static final int MUTED = 0xFF9AA7C4;     // secondary text
    static final int FAINT = 0xFF6A748E;     // tertiary / faint captions
    static final int ON_ACCENT = 0xFFFFFFFF; // text on accent fills

    static final int ACCENT = 0xFF8FA6FF;    // links, icons, selection
    static final int ACCENT_D = 0xFF5B74FF;  // primary button fill
    static final int ACCENT_P = 0xFF4659E0;  // primary pressed
    static final int ACCENT_2 = 0xFF8A6CFF;  // violet (gradient far end / second accent)
    static final int ACCENT_G1 = 0xFF6C8CFF; // primary gradient start
    static final int ACCENT_G2 = 0xFF9A6CFF; // primary gradient end
    static final int CYAN = 0xFF35D6E0;      // cool highlight

    static final int ORANGE = 0xFFFFB45D;    // highlights / current step
    static final int GOOD = 0xFF3DDC97;
    static final int WARN = 0xFFFFC24D;
    static final int BAD = 0xFFFF6B7D;
    static final int GRAY = 0xFF7F8CA6;
}
