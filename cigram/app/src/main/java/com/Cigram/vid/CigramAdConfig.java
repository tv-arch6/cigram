package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Single source of truth for ad behaviour (v3.1/v3.2).
 *
 * LOCAL SAFE DEFAULTS (used whenever remote config is absent / invalid / expired):
 *   first player ad ..... after 15 min of playback
 *   mid-roll interval ... 15 min
 *   max ads per hour .... 4   (rolling window, persisted, shared by every Activity/episode)
 *   cooldown ............ 10 min between any two ads (player or support)
 *   max per session ..... 8 (per film: 4)   (per app process)
 *   The hourly cap, the session cap and the cooldown are SHARED by mid-roll ads and episode-break
 *   ads (v4.1); the optional support ad only feeds the cooldown (it is a user action).
 *
 * REMOTE CONFIG (v3.2): ADS_REMOTE_CONFIG_URL points to the Cloudflare ads-config Worker => ACTIVE.
 * If the Worker is unreachable/invalid the cached copy (<=24 h) or the local defaults below are used.
 * When a URL is set: fetched on app start, then at most every 6 h; last valid copy is cached
 * in SharedPreferences "cigram_ads_remote" and expires after 24 h; every field is validated
 * (booleans must be real booleans, numbers are clamped, negatives fall back to the default).
 *
 * PRE-ROLL on the phone player is NOT IMPLEMENTED. preRollEnabled() always returns false and
 * the remote key "pre_roll_enabled" is intentionally ignored.
 *
 * Production AdMob unit IDs are unchanged. DEBUG=false. Test IDs below are never used unless
 * DEBUG is flipped manually.
 */
public final class CigramAdConfig {
    public static final boolean DEBUG = false;
    /** Shows a toast saying why an ad did not appear. Set to false after diagnosing. */
    public static final boolean DIAG = false;

    /** v3.4: support unit now lives under the "Cigram" AdMob app (~1229517223), same as the player unit. */
    public static final String REAL_SUPPORT_INTERSTITIAL =
            "ca-app-pub-7010031756978837/1093799161";
    /** Old support unit (belongs to the "Sigram" AdMob app). Kept for reference only, never used. */
    public static final String LEGACY_SUPPORT_INTERSTITIAL =
            "ca-app-pub-7010031756978837/7877448959";
    public static final String REAL_PLAYER_INTERSTITIAL =
            "ca-app-pub-7010031756978837/3504326778";

    public static final String TEST_BANNER =
            "ca-app-pub-3940256099942544/6300978111";
    public static final String TEST_INTERSTITIAL =
            "ca-app-pub-3940256099942544/1033173712";
    public static final String TEST_REWARDED =
            "ca-app-pub-3940256099942544/5224354917";

    // ---- v10.35: app-open ad + rewarded unlock (same idea as OTTO TV) ----
    // Both units live under the "Cigram" AdMob app (~1229517223), same as the player and support units.
    /** v10.35: App open unit "Cigram_App_Open", shown when the app is opened (at most once every 4 h). */
    public static final String REAL_APP_OPEN =
            "ca-app-pub-7010031756978837/1118620402";
    /** v10.35: Rewarded interstitial unit "Cigram_Reward_Unlock" that unlocks a film / episode. */
    public static final String REAL_REWARDED_INTERSTITIAL =
            "ca-app-pub-7010031756978837/4281251626";

    public static final String TEST_APP_OPEN =
            "ca-app-pub-3940256099942544/9257395921";
    public static final String TEST_REWARDED_INTERSTITIAL =
            "ca-app-pub-3940256099942544/5354046379";

    // ---- feature flags (local defaults) ----
    public static final boolean ADS_ENABLED = true;
    public static final boolean PLAYER_ADS_ENABLED = true;
    public static final boolean MID_ROLL_ENABLED = true;
    /** The support button shows ONE optional interstitial per press (not a rewarded ad). */
    public static final boolean SUPPORT_AD_ENABLED = true;
    /** Phone-player pre-roll does not exist in this build. Never enable by flag alone. */
    public static final boolean PRE_ROLL_IMPLEMENTED = false;

    // ---- player ad policy (local defaults) ----
    // v6.2 (owner's request): an ad on every press + up to 4 breaks inside each title.
    // The caps below are safety limits only, so a very long session cannot turn into an ad wall.
    public static final int PLAYER_FIRST_AD_DELAY_MIN = 5;
    public static final int PLAYER_MIDROLL_INTERVAL_MIN = 25;   // used only when the duration is unknown
    public static final int PLAYER_MAX_ADS_PER_HOUR = 20;   // v10.34: room for 4 breaks + every press
    public static final int PLAYER_AD_COOLDOWN_MIN = 3;
    public static final int PLAYER_MAX_ADS_PER_SESSION = 60;
    /** Mid-rolls per film/episode (positions from CigramAdBreaks). */
    public static final int PLAYER_MAX_ADS_PER_FILM = 4;

    // legacy names kept for existing call sites (derived from the policy above)
    public static final long PLAYER_MIN_START_MS = PLAYER_FIRST_AD_DELAY_MIN * 60L * 1000L;
    public static final long PLAYER_FALLBACK_INTERVAL_MS = PLAYER_MIDROLL_INTERVAL_MIN * 60L * 1000L;
    public static final int PLAYER_MAX_PER_HOUR = PLAYER_MAX_ADS_PER_HOUR;
    public static final long PLAYER_COOLDOWN_MS = PLAYER_AD_COOLDOWN_MIN * 60L * 1000L;
    public static final long PLAYER_END_GUARD_MS = 2L * 60L * 1000L;
    /**
     * Request the mid-roll this long before its break. 60 s was too short: one slow or failed
     * request (back-off 30 s+) meant no ad was ready when the break came, so the break was lost.
     */
    public static final long PLAYER_PRELOAD_MS = 3L * 60L * 1000L;
    /** How long after the break point a late-loading ad may still start (was 12 s). */
    public static final long PLAYER_BREAK_GRACE_MS = 30L * 1000L;
    /** Mid-roll: how long after the break point the player may wait for a quiet moment (v4.1). */
    public static final long PLAYER_QUIET_WINDOW_MS = 90L * 1000L;
    public static final long PLAYER_CAP_WINDOW_MS = 60L * 60L * 1000L;

    // ---- support ad UX ----
    public static final int SUPPORT_COUNTDOWN_FROM = 3;
    public static final long SUPPORT_ZERO_HOLD_MS = 450L;
    public static final long SUPPORT_AD_WAIT_TIMEOUT_MS = 12L * 1000L;
    public static final long SUPPORT_SECOND_AD_WAIT_MS = 5L * 1000L;
    /** A preloaded support interstitial older than this is thrown away and requested again. */
    public static final long SUPPORT_AD_MAX_AGE_MS = 45L * 60L * 1000L;

    // ---- remote config ----
    /** EMPTY => remote config NOT ACTIVE. Must be https when set. */
    public static final String ADS_REMOTE_CONFIG_URL = "https://cigram-ads-config.wwq-mixtv.workers.dev/config";
    private static final long REMOTE_TTL_MS = 24L * 60L * 60L * 1000L;
    private static final long REMOTE_REFRESH_MS = 6L * 60L * 60L * 1000L;
    private static final String RPREFS = "cigram_ads_remote";
    private static final int MAX_AD_FREE_IDS = 500;
    private static final Object RLOCK = new Object();
    private static JSONObject remote;
    private static long remoteAt;
    private static boolean remoteLoaded;
    private static boolean fetching;

    private CigramAdConfig() {
    }

    public static String playerInterstitial() {
        return DEBUG ? TEST_INTERSTITIAL : REAL_PLAYER_INTERSTITIAL;
    }

    public static String supportInterstitial() {
        return DEBUG ? TEST_INTERSTITIAL : REAL_SUPPORT_INTERSTITIAL;
    }

    public static String appOpenUnit() {
        return DEBUG ? TEST_APP_OPEN : REAL_APP_OPEN;
    }

    public static String rewardedInterstitialUnit() {
        return DEBUG ? TEST_REWARDED_INTERSTITIAL : REAL_REWARDED_INTERSTITIAL;
    }

    public static boolean remoteConfigActive() {
        return ADS_REMOTE_CONFIG_URL.startsWith("https://");
    }

    // ---------------------------------------------------------- effective values

    /**
     * MASTER SWITCH for Google AdMob.
     * true (current, v1.6.8) = AdMob is fully back: app-open, interstitial, rewarded, episode, support, mid-roll in the player.
     * false = the AdMob SDK is never started, no AdMob ad is requested or shown (app-open, interstitial,
     * rewarded, episode, support, TV) and the remote config cannot turn it back on.
     * true = the old behaviour (AdMob + remote switches).
     */
    public static final boolean ADMOB_ENABLED = true;


    public static boolean adsEnabled(Context c) {
        if (!ADMOB_ENABLED) return false;
        return flag(c, "ads_enabled", ADS_ENABLED);
    }

    public static boolean playerAdsEnabled(Context c) {
        return adsEnabled(c) && flag(c, "player_ads_enabled", PLAYER_ADS_ENABLED);
    }

    public static boolean midRollEnabled(Context c) {
        return playerAdsEnabled(c) && flag(c, "mid_roll_enabled", MID_ROLL_ENABLED);
    }

    /** Always false: there is no phone-player pre-roll implementation. */
    public static boolean preRollEnabled(Context c) {
        return PRE_ROLL_IMPLEMENTED && playerAdsEnabled(c);
    }

    /** Ad shown when the user moves to another episode (next button, episode list, auto-next). */
    public static final boolean EPISODE_AD_ENABLED = true;
    /** Minimum time between two episode-change ads. */
    public static final long EPISODE_AD_GAP_MS = 2L * 60L * 1000L;

    public static boolean episodeAdsEnabled(Context c) {
        return adsEnabled(c) && flag(c, "episode_ads_enabled", EPISODE_AD_ENABLED);
    }

    public static boolean supportAdEnabled(Context c) {
        return adsEnabled(c) && flag(c, "support_ads_enabled", SUPPORT_AD_ENABLED);
    }

    // ---- v10.35: app-open ad (CigramAppOpenAd) ----
    /** AdMob app-open ad when the app starts. Remote key "app_open_ads_enabled". */
    public static final boolean APP_OPEN_ADS_ENABLED = true;
    /** At most one app-open ad every 4 hours (stored on the device, counted from the last one shown). */
    public static final long APP_OPEN_GAP_MS = 4L * 60L * 60L * 1000L;

    public static boolean appOpenAdsEnabled(Context c) {
        return adsEnabled(c) && flag(c, "app_open_ads_enabled", APP_OPEN_ADS_ENABLED);
    }

    // ---- v10.35: rewarded unlock (CigramRewardUnlock) ----
    /**
     * «شاهد هسه», an episode in the list and the automatic next episode ask for ONE rewarded ad
     * watched to the end; the film / episode then stays open until the app is closed.
     * Remote key "reward_unlock_enabled": false brings back the old interstitial on every press.
     */
    public static final boolean REWARD_UNLOCK_ENABLED = true;

    public static boolean rewardUnlockEnabled(Context c) {
        return adsEnabled(c) && flag(c, "reward_unlock_enabled", REWARD_UNLOCK_ENABLED);
    }

    // ---- TV casting ad (v5.24) ----
    /** Ad before a film / episode is sent to the TV. Remote key "tv_ads_enabled". */
    public static final boolean TV_ADS_ENABLED = true;
    /** Google's public sample VAST tag: used only while DEBUG is true. */
    public static final String TEST_TV_VAST_TAG =
            "https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/single_ad_samples"
                    + "&sz=640x480&cust_params=sample_ct%3Dlinear&ciu_szs=300x250%2C728x90"
                    + "&gdfp_req=1&output=vast&unviewed_position_start=1&env=vp&impl=s&correlator=[CACHEBUSTING]";

    public static boolean tvAdsEnabled(Context c) {
        return adsEnabled(c) && flag(c, "tv_ads_enabled", TV_ADS_ENABLED);
    }

    /**
     * VAST tag (Google Ad Manager video ad unit) played ON THE TV before the film, with the
     * "تخطي بعد 5" button on the phone. Remote key "tv_vast_tag" (https only).
     * Empty => no TV video ad: a real AdMob interstitial is shown on the phone instead
     * (AdMob itself has no VAST tags, its ads can only be shown inside the app).
     */
    public static String tvVastTag(Context c) {
        try {
            JSONObject r = activeRemote(c);
            String v = r == null ? "" : r.optString("tv_vast_tag", "").trim();
            if (v.startsWith("https://")) return v;
        } catch (Throwable ignored) {
        }
        return DEBUG ? TEST_TV_VAST_TAG : "";
    }

    /**
     * v10.34: the ad limits (first ad, interval, per hour, cooldown) are the app's own values
     * again. The remote Worker still sent the old v3.1 limits (15 min / 15 min / 4 per hour /
     * 10 min cooldown), which overrode the v6.2 policy and skipped most ads. Set this to true
     * only after the Worker sends the limits you want.
     */
    public static final boolean REMOTE_LIMITS = false;

    private static long limit(Context c, String key, long def, long lo, long hi) {
        return REMOTE_LIMITS ? clamped(c, key, def, lo, hi) : def;
    }

    public static long firstAdDelayMs(Context c) {
        return 60000L * limit(c, "first_ad_delay_min", PLAYER_FIRST_AD_DELAY_MIN, 5, 120);
    }

    /** Next break position strictly after pos: first, first+interval, ... capped at PLAYER_MAX_ADS_PER_FILM; Long.MAX_VALUE when none left. */
    public static long nextBreakAfter(Context c, long pos) {
        long first = Math.max(60000L, firstAdDelayMs(c));
        long iv = Math.max(60000L, midrollIntervalMs(c));
        long idx = pos < first ? 0L : (pos - first) / iv + 1L;
        return idx >= PLAYER_MAX_ADS_PER_FILM ? Long.MAX_VALUE : first + idx * iv;
    }

    public static long midrollIntervalMs(Context c) {
        return 60000L * limit(c, "midroll_interval_min", PLAYER_MIDROLL_INTERVAL_MIN, 10, 180);
    }

    public static int maxAdsPerHour(Context c) {
        return (int) limit(c, "max_ads_per_hour", PLAYER_MAX_ADS_PER_HOUR, 0, 30);
    }

    public static long cooldownMs(Context c) {
        return 60000L * limit(c, "cooldown_min", PLAYER_AD_COOLDOWN_MIN, 1, 120);
    }

    public static int maxAdsPerSession() {
        return PLAYER_MAX_ADS_PER_SESSION;
    }

    // legacy accessors (call sites from v3.0)
    public static boolean playerAdEnabled(Context c) { return playerAdsEnabled(c); }
    public static int playerMaxPerHour(Context c) { return maxAdsPerHour(c); }
    public static long playerMinStartMs(Context c) { return firstAdDelayMs(c); }
    public static long playerIntervalMs(Context c) { return midrollIntervalMs(c); }
    public static long playerCooldownMs(Context c) { return cooldownMs(c); }

    // ------------------------------------------------------------------ ad-free

    /**
     * Ad-free if (a) the device flag was set explicitly with setAdFree(), or (b) the CURRENT
     * logged-in account's stable id (auth_session.user_id) is in the remote ad_free_user_ids.
     * (b) is derived live from the real session on every call, so login/logout are reflected
     * immediately and no account-specific state has to be stored or cleared.
     */
    public static boolean adFreeUser(Context c) {
        try {
            if (c == null) return false;
            Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
            if (CigramPlus.isPlus(app)) return true;      // Cigram Plus: no ads of any kind
            if (app.getSharedPreferences("cigram_ads_state", Context.MODE_PRIVATE)
                    .getBoolean("ad_free", false)) return true;
            String owner = CigramUserData.owner(app);
            if (owner == null || owner.length() == 0 || "guest".equals(owner)) return false;
            String id = CigramUserData.authUserId(app);
            if (id.length() == 0) return false;
            JSONObject r = activeRemote(app);
            JSONArray list = r == null ? null : r.optJSONArray("ad_free_user_ids");
            if (list == null) return false;
            for (int i = 0; i < list.length() && i < MAX_AD_FREE_IDS; i++) {
                if (id.equals(String.valueOf(list.opt(i)).trim())) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static void setAdFree(Context c, boolean value) {
        try {
            if (c == null) return;
            c.getApplicationContext().getSharedPreferences("cigram_ads_state", Context.MODE_PRIVATE)
                    .edit().putBoolean("ad_free", value).apply();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ remote

    private static boolean flag(Context c, String key, boolean def) {
        try {
            JSONObject r = activeRemote(c);
            if (r != null && r.opt(key) instanceof Boolean) return r.getBoolean(key);
        } catch (Throwable ignored) {
        }
        return def;
    }

    /** Non-number or outside [lo, hi] => the safe local default (a bad remote value never "almost" applies). */
    private static long clamped(Context c, String key, long def, long lo, long hi) {
        try {
            JSONObject r = activeRemote(c);
            if (r != null && r.opt(key) instanceof Number) {
                long v = r.getLong(key);
                if (v < lo || v > hi) return def;
                return v;
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static JSONObject activeRemote(Context c) {
        synchronized (RLOCK) {
            if (!remoteLoaded && c != null) {
                remoteLoaded = true;
                try {
                    SharedPreferences p = c.getApplicationContext()
                            .getSharedPreferences(RPREFS, Context.MODE_PRIVATE);
                    String raw = p.getString("json", "");
                    remoteAt = p.getLong("at", 0L);
                    if (raw != null && raw.length() > 0) remote = new JSONObject(raw);
                } catch (Throwable ignored) {
                    remote = null;
                }
            }
            if (remote == null) return null;
            long age = System.currentTimeMillis() - remoteAt;
            if (age < 0L || age > REMOTE_TTL_MS) return null;
            return remote;
        }
    }

    /** No-op while ADS_REMOTE_CONFIG_URL is empty. Otherwise: once at start, then >= 6 h apart. */
    public static void refreshRemote(Context context) {
        try {
            if (context == null || !remoteConfigActive()) return;
            final Context app = context.getApplicationContext();
            activeRemote(app);
            synchronized (RLOCK) {
                if (fetching) return;
                long age = System.currentTimeMillis() - remoteAt;
                if (remote != null && age >= 0L && age < REMOTE_REFRESH_MS) return;
                fetching = true;
            }
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    HttpURLConnection h = null;
                    try {
                        h = (HttpURLConnection) new URL(ADS_REMOTE_CONFIG_URL).openConnection();
                        h.setConnectTimeout(6000);
                        h.setReadTimeout(6000);
                        if (h.getResponseCode() != 200) return;
                        BufferedReader br = new BufferedReader(new InputStreamReader(h.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null && sb.length() < 16384) sb.append(line);
                        br.close();
                        JSONObject o = new JSONObject(sb.toString());
                        if (o.length() == 0) return;
                        long now = System.currentTimeMillis();
                        synchronized (RLOCK) {
                            remote = o;
                            remoteAt = now;
                        }
                        app.getSharedPreferences(RPREFS, Context.MODE_PRIVATE).edit()
                                .putString("json", o.toString()).putLong("at", now).apply();
                    } catch (Throwable ignored) {
                        // 5xx / offline / malformed JSON: keep cached copy or local defaults
                    } finally {
                        try { if (h != null) h.disconnect(); } catch (Throwable ignored2) { }
                        synchronized (RLOCK) {
                            fetching = false;
                        }
                    }
                }
            }, "cigram-adcfg");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
            synchronized (RLOCK) {
                fetching = false;
            }
        }
    }
}
