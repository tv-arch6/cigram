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

    private static final String TOKEN_SEALED_KEY = "admin_token_sealed";

    /**
     * 3.0: the token is kept ENCRYPTED (see {@link CgVault}). A plain token saved by an earlier version is sealed here
     * once and its plain copy removed. Where sealing is impossible (Android 5.x) the previous behaviour stays.
     */
    static void loadToken(android.content.Context c) {
        // 19.6: a build that carries the owner's token (assets/cigram_admin_token.txt) never asks for anything
        readAssetToken(c);
        String built = builtToken();
        if (built.length() > 0) {
            TOKEN = built;
            return;
        }
        try {
            android.content.SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
            String sealed = p.getString(TOKEN_SEALED_KEY, "");
            String plain = p.getString(TOKEN_KEY, "").trim();
            String opened = sealed.length() > 0 ? CgVault.open(sealed) : null;
            if (opened != null && opened.trim().length() > 0) {
                TOKEN = opened.trim();
                if (plain.length() > 0) p.edit().remove(TOKEN_KEY).apply();
                return;
            }
            TOKEN = plain;
            if (plain.length() > 0) {                                  // migrate the plain copy
                String s = CgVault.seal(plain);
                if (s != null && plain.equals(CgVault.open(s))) p.edit().putString(TOKEN_SEALED_KEY, s).remove(TOKEN_KEY).apply();
            }
        } catch (Exception e) {
            TOKEN = "";
        }
    }

    static void saveToken(android.content.Context c, String token) {
        TOKEN = token == null ? "" : token.trim();
        try {
            android.content.SharedPreferences.Editor e = c.getApplicationContext().getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit();
            String s = TOKEN.length() == 0 ? null : CgVault.seal(TOKEN);
            if (s != null && TOKEN.equals(CgVault.open(s))) e.putString(TOKEN_SEALED_KEY, s).remove(TOKEN_KEY);
            else e.putString(TOKEN_KEY, TOKEN).remove(TOKEN_SEALED_KEY);
            e.apply();
        } catch (Exception ignored) { }
    }

    static boolean hasToken() {
        return TOKEN != null && TOKEN.length() > 0;
    }

    /** Read once from assets/cigram_admin_token.txt ("" when the APK has no such file). */
    private static volatile String ASSET_TOKEN = null;

    private static void readAssetToken(android.content.Context c) {
        if (ASSET_TOKEN != null || c == null) return;
        String t = "";
        java.io.InputStream in = null;
        try {
            in = c.getApplicationContext().getAssets().open("cigram_admin_token.txt");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 4096) out.write(buf, 0, n);
            t = out.toString("UTF-8").trim();
        } catch (Throwable ignored) {
            t = "";                               // no file in this build: the one-time link sheet is used
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
        }
        ASSET_TOKEN = t;
    }

    /** The token built into this APK: assets/cigram_admin_token.txt, else BuildConfig ("" when the build had none). */
    static String builtToken() {
        String a = ASSET_TOKEN;
        if (a != null && a.length() > 0) return a;
        try {
            String t = BuildConfig.ADMIN_TOKEN;
            return t == null ? "" : t.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    static final String PREFS = "cigram_admin_v2";

    // 3.0 «Cigram Studio»: cool graphite surfaces separated by one hairline (the previous warm charcoal ladder is gone);
    // red stays the one primary colour, gold the second accent. Every screen reads its colours from here.
    // (2026 note kept for history) Cigram identity: the same charcoal ladder as the Cigram app (near-black page, four graduated surfaces),
    // the Cigram red as the one primary action colour and Cigram gold as the second accent. Only the values changed
    // (every screen reads them from here), so no screen logic is touched.
    static final int PAGE = 0xFF000000;      // window / page background (deepest)
    static final int PAGE2 = 0xFF0A0A0B;     // hero / header wash
    static final int CARD = 0xFF111113;      // cards
    static final int CARD2 = 0xFF1C1C1F;     // raised / pressed / tonal surfaces
    static final int CARD3 = 0xFF2C2C2F;     // highest surface (chips, inner tiles)
    static final int STROKE = 0xFF2C2C2F;    // dividers, hairlines
    static final int STROKE_SOFT = 0xFF1C1C1F; // barely-there separators
    static final int FIELD = 0xFF0E0E10;     // text inputs
    static final int TEXT = 0xFFFFFFFF;      // primary text
    static final int MUTED = 0xFFAEAEB2;     // secondary text
    static final int FAINT = 0xFF8E8E93;     // tertiary / faint captions
    static final int ON_ACCENT = 0xFFFFFFFF; // text on accent fills

    static final int ACCENT = 0xFFFFFFFF;    // links, icons, selection (light Cigram red, readable on charcoal)
    static final int ACCENT_D = 0xFFE21B14;  // primary button fill (Cigram red)
    static final int ACCENT_P = 0xFFB8150F;  // primary pressed
    static final int ACCENT_2 = 0xFFE5E5EA;  // Cigram gold (second accent)
    static final int ACCENT_G1 = 0xFFF0362B; // primary gradient start
    static final int ACCENT_G2 = 0xFFB3120D; // primary gradient end
    static final int CYAN = 0xFF4DA3FF;      // cool highlight (charts, numbers)

    static final int ORANGE = 0xFFFF9F43;    // highlights / current step
    static final int GOOD = 0xFF3DDC97;
    static final int WARN = 0xFFFFC24D;
    static final int BAD = 0xFFFF6B7D;
    static final int GRAY = 0xFF8E8E93;

    // 3.0.2 — charts and statistics use several colours (one per metric) so numbers and bars can be told apart at a glance.
    // Icons do NOT use these: an icon is always a white glyph on a black tile (see StudioUi.iconTile).
    static final int CH_GREEN = 0xFF3DDC97;
    static final int CH_RED = 0xFFFF5A64;
    static final int CH_ORANGE = 0xFFFF9F43;
    static final int CH_BLUE = 0xFF4DA3FF;
    static final int CH_YELLOW = 0xFFFFC24D;
    static final int CH_PURPLE = 0xFFB388FF;
    static final int CH_TEAL = 0xFF2DD4BF;

    /** Rotation for series / ranked rows: green, orange, red, blue, yellow, purple, teal. */
    static final int[] SERIES = {CH_GREEN, CH_ORANGE, CH_RED, CH_BLUE, CH_YELLOW, CH_PURPLE, CH_TEAL};

    static int series(int index) {
        return SERIES[Math.floorMod(index, SERIES.length)];
    }
}
