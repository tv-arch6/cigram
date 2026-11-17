package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 19.6 — «التحكم بسلاسة التطبيق» from the admin app (الإدارة ← سلاسة التطبيق).
 *
 * The admin saves a small JSON; this class reads it from the Worker's public {@code /channels-config} (the admin stores it
 * under the reserved key {@link #KEY}, so no Worker deployment is needed), caches it, and every knob has a safe default:
 * without any config the app behaves exactly as the owner asked (11 posters, 20 s change check, background on).
 *
 * <pre>
 *   pulse_s     how often (s) the open app checks whether the catalogue changed (10..120, default 20)
 *   prefetch    posters downloaded ahead after a catalogue change (0..400, default 120)
 *   poster      card poster size from TMDB: "w342" (light) | "w500" (default) | "w780" (sharp)
 *   lite        true = no decorative motion (fades / flights), for weak phones and TV boxes
 *   keepalive   true = Cigram keeps running in the background after it is left (default true)
 * </pre>
 */
final class CigramPerfConfig {

    static final String KEY = "__cigram_perf__";
    static final String URL = "https://cigram-admin-api.wwq-mixtv.workers.dev/channels-config";
    private static final String PREFS = "cigram_perf_config";
    private static final long REFRESH_MS = 10L * 60L * 1000L;

    private static volatile JSONObject CACHE;
    private static volatile long lastFetch;
    private static volatile boolean fetching;

    private CigramPerfConfig() { }

    static JSONObject get(Context c) {
        JSONObject cached = CACHE;
        if (cached != null) return cached;
        JSONObject o = new JSONObject();
        try {
            if (c != null) {
                String raw = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", "");
                if (raw != null && raw.length() > 1) o = new JSONObject(raw);
            }
        } catch (Throwable ignored) { }
        CACHE = o;
        return o;
    }

    static long pulseMs(Context c, long def) {
        int s = get(c).optInt("pulse_s", 0);
        if (s <= 0) return def;
        return Math.max(10, Math.min(120, s)) * 1000L;
    }

    static int prefetch(Context c) {
        JSONObject o = get(c);
        if (!o.has("prefetch")) return 120;
        return Math.max(0, Math.min(400, o.optInt("prefetch", 120)));
    }

    /** "w342" | "w500" | "w780" — the TMDB size used for card posters. */
    static String posterSize(Context c) {
        String p = c == null ? "" : get(c).optString("poster", "");
        if ("w342".equals(p) || "w780".equals(p) || "w500".equals(p) || "w185".equals(p)) return p;
        return "w500";
    }

    static boolean lite(Context c) {
        return c != null && get(c).optBoolean("lite", false);
    }

    static boolean keepAliveDefault(Context c) {
        return c == null || get(c).optBoolean("keepalive", true);
    }

    /** The poster size for code that has no Context (the shared poster URL normaliser). */
    static String posterSizeNow() {
        JSONObject o = CACHE;
        String p = o == null ? "" : o.optString("poster", "");
        if ("w342".equals(p) || "w780".equals(p) || "w185".equals(p)) return p;
        return "w500";
    }

    /** Reads the admin's config in the background (at most every 10 minutes). */
    static void refresh(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        long now = System.currentTimeMillis();
        if (fetching || (lastFetch > 0L && now - lastFetch < REFRESH_MS)) return;
        fetching = true;
        lastFetch = now;
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(URL).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setUseCaches(false);
                    if (c.getResponseCode() != 200) return;
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0 && out.size() < 512 * 1024) out.write(buf, 0, n);
                    JSONObject root = new JSONObject(out.toString("UTF-8"));
                    JSONObject channels = root.optJSONObject("channels");
                    JSONObject entry = channels == null ? null : channels.optJSONObject(KEY);
                    String payload = entry == null ? "" : entry.optString("last_check_at", "");
                    JSONObject cfg = payload.startsWith("{") ? new JSONObject(payload) : new JSONObject();
                    CACHE = cfg;
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("json", cfg.toString()).apply();
                    try { CigramKeepAlive.configChanged(app); } catch (Throwable ignored) { }
                } catch (Throwable error) {
                    android.util.Log.w("CigramPerfConfig", "config not read", error);
                } finally {
                    fetching = false;
                    if (c != null) c.disconnect();
                }
            }
        }, "cigram-perf-config").start();
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
