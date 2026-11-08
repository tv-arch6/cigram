package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

/**
 * v10.70: the Netflix-style home is described by data the Admin app controls (see ADMIN-LAYOUT.md at
 * the root of the project). The worker serves it at {@code /home-layout}; the last answer is cached so
 * the layout is right from the first frame and works offline. Without an answer (endpoint not built
 * yet, no network) the defaults below apply and the app looks and behaves as designed.
 *
 * Every part is optional:
 * <pre>
 * {
 *   "version": 1,
 *   "theme":     { "mode": "system" },                          // system | light | dark
 *   "chips":     [ { "title": "مسلسلات", "type": "series" },     // top chips under the header
 *                  { "title": "أفلام",   "type": "movie" },
 *                  { "title": "أنمي",    "section_id": "anime" } ],
 *   "billboard": { "enabled": true, "item_id": "1234", "type": "movie",   // the big title at the top
 *                  "section_id": "trending" },                             // or: first title of a section
 *   "sections":  { "trending": { "style": "top10" },                      // per section id or name
 *                  "new":      { "style": "landscape", "title": "جديد" },
 *                  "anime":    { "style": "large" } }
 * }
 * </pre>
 * Section styles: {@code row} (default), {@code top10}, {@code landscape}, {@code large}, {@code billboard}.
 * A section can also carry its style inside the catalogue itself ({@code "style": "top10"} on the section).
 */
public final class CigramLayoutConfig {

    static final String URL = "https://cigram-admin-api.wwq-mixtv.workers.dev/home-layout";
    private static final String PREFS = "cigram_home_layout";
    private static final long REFRESH_GAP_MS = 60000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile JSONObject cached;
    private static long lastTry;
    private static boolean fetching;

    private CigramLayoutConfig() { }

    /** The current layout description (cached answer, or empty = defaults). Never null. */
    static JSONObject current(Context context) {
        JSONObject c = cached;
        if (c != null) return c;
        try {
            String raw = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", "");
            c = raw == null || raw.length() == 0 ? new JSONObject() : new JSONObject(raw);
        } catch (Throwable ignored) {
            c = new JSONObject();
        }
        cached = c;
        return c;
    }

    /** "system" | "light" | "dark". */
    static String themeMode(Context context) {
        JSONObject theme = current(context).optJSONObject("theme");
        String mode = theme == null ? "system" : theme.optString("mode", "system").trim().toLowerCase(Locale.ROOT);
        return "light".equals(mode) || "dark".equals(mode) ? mode : "system";
    }

    /** Style the Admin app set for a section (by id, slug or name), or "" when it set none. */
    static String styleFor(Context context, String id, String name) {
        JSONObject sections = current(context).optJSONObject("sections");
        if (sections == null) return "";
        for (String k : new String[]{id, name}) {
            if (k == null || k.trim().length() == 0) continue;
            JSONObject s = sections.optJSONObject(k.trim());
            if (s == null) s = sections.optJSONObject(k.trim().toLowerCase(Locale.ROOT));
            if (s != null) return normStyle(s.optString("style", s.optString("layout", "")));
        }
        return "";
    }

    /** Title the Admin app set for a section, or null to keep the catalogue's. */
    static String titleFor(Context context, String id, String name) {
        JSONObject sections = current(context).optJSONObject("sections");
        if (sections == null) return null;
        for (String k : new String[]{id, name}) {
            if (k == null || k.trim().length() == 0) continue;
            JSONObject s = sections.optJSONObject(k.trim());
            if (s != null && s.optString("title", "").trim().length() > 0) return s.optString("title").trim();
        }
        return null;
    }

    static String normStyle(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (s.equals("top10") || s.equals("ranked") || s.equals("top")) return "top10";
        if (s.equals("landscape") || s.equals("wide") || s.equals("backdrop")) return "landscape";
        if (s.equals("large") || s.equals("big") || s.equals("tall")) return "large";
        if (s.equals("billboard") || s.equals("hero")) return "billboard";
        if (s.equals("row") || s.equals("poster") || s.equals("default")) return "row";
        return "";
    }

    /** Top chips under the header. None by default; the Admin app may send some. */
    static ArrayList<JSONObject> chips(Context context) {
        ArrayList<JSONObject> out = new ArrayList<JSONObject>();
        JSONObject root = current(context);
        JSONArray list = root.optJSONArray("chips");
        if (list == null) return out;   // hidden by default: only chips the Admin app sends are shown
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null && o.optString("title", "").trim().length() > 0) out.add(o);
        }
        return out;
    }

    /** The billboard description, or null when the Admin app did not turn it on. */
    static JSONObject billboard(Context context) {
        JSONObject b = current(context).optJSONObject("billboard");
        return b != null && b.optBoolean("enabled", true) ? b : null;
    }

    /**
     * Fetches the layout in the background (at most once a minute); runs {@code onChanged} on the main
     * thread when it differs from the cached one. A missing endpoint (404) simply keeps the defaults.
     */
    static void refresh(final Context context, final Runnable onChanged) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (CigramLayoutConfig.class) {
            if (fetching || (lastTry > 0L && now - lastTry < REFRESH_GAP_MS)) return;
            fetching = true;
            lastTry = now;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(URL).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("Cache-Control", "no-cache");
                    if (c.getResponseCode() != 200) return;
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0 && out.size() < 256 * 1024) out.write(buf, 0, n);
                    in.close();
                    String body = out.toString("UTF-8");
                    JSONObject fresh = new JSONObject(body);
                    if (fresh.has("layout") && fresh.optJSONObject("layout") != null) fresh = fresh.optJSONObject("layout");
                    String text = fresh.toString();
                    SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    if (text.equals(p.getString("json", ""))) return;
                    p.edit().putString("json", text).apply();
                    final String oldMode = themeMode(app);
                    cached = fresh;
                    final String newMode = themeMode(app);
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            // 2026: a theme mode forced by the Admin app is NOT applied to the running app any more.
                            // setDefaultNightMode recreates every open page; arriving during a cold start it destroyed
                            // and rebuilt Home behind the launch screen (a second, empty-looking start-up). The new mode
                            // is saved above and CigramTheme.install applies it at the next cold start. The user's own
                            // choice (Account / Settings) still applies at once and still wins over the Admin's mode.
                            if (!oldMode.equals(newMode)) android.util.Log.i("CigramLayout", "admin theme " + newMode + " applies next start");
                            if (onChanged != null) onChanged.run();
                        }
                    });
                } catch (Throwable ignored) {
                    // offline / not deployed yet: defaults stay
                } finally {
                    if (c != null) c.disconnect();
                    synchronized (CigramLayoutConfig.class) { fetching = false; }
                }
            }
        }, "cigram-home-layout").start();
    }
}
