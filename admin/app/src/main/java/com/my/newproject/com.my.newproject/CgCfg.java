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

    // 2026 Cigram identity: the same charcoal ladder as the Cigram app (near-black page, four graduated surfaces),
    // the Cigram red as the one primary action colour and Cigram gold as the second accent. Only the values changed
    // (every screen reads them from here), so no screen logic is touched.
    static final int PAGE = 0xFF0B0B0D;      // window / page background (deepest)
    static final int PAGE2 = 0xFF111114;     // hero / header wash
    static final int CARD = 0xFF16161A;      // cards
    static final int CARD2 = 0xFF1E1E23;     // raised / pressed / tonal surfaces
    static final int CARD3 = 0xFF27272D;     // highest surface (chips, inner tiles)
    static final int STROKE = 0xFF303037;    // dividers, hairlines
    static final int STROKE_SOFT = 0xFF222228; // barely-there separators
    static final int FIELD = 0xFF101013;     // text inputs
    static final int TEXT = 0xFFF5F5F7;      // primary text
    static final int MUTED = 0xFFA1A1AA;     // secondary text
    static final int FAINT = 0xFF71717A;     // tertiary / faint captions
    static final int ON_ACCENT = 0xFFFFFFFF; // text on accent fills

    static final int ACCENT = 0xFFFF6A5E;    // links, icons, selection (light Cigram red, readable on charcoal)
    static final int ACCENT_D = 0xFFE21B14;  // primary button fill (Cigram red)
    static final int ACCENT_P = 0xFFB8150F;  // primary pressed
    static final int ACCENT_2 = 0xFFF2C14E;  // Cigram gold (second accent)
    static final int ACCENT_G1 = 0xFFF0362B; // primary gradient start
    static final int ACCENT_G2 = 0xFFB3120D; // primary gradient end
    static final int CYAN = 0xFF7CC7E8;      // cool highlight (charts)

    static final int ORANGE = 0xFFFFB45D;    // highlights / current step
    static final int GOOD = 0xFF3DDC97;
    static final int WARN = 0xFFFFC24D;
    static final int BAD = 0xFFFF6B7D;
    static final int GRAY = 0xFF7F8CA6;
}
