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
import java.util.HashMap;
import java.util.Locale;

/**
 * v10.37: the order of the home sections comes from the Admin app (الأقسام / ترتيب الصفحة الرئيسية),
 * served by the worker at {@code /home-sections}. The last answer is cached, so the order is right
 * from the first frame on the next launch and when offline.
 *
 * Only sections the admin registered are ordered (registry order, ascending); a section that is not
 * in the registry keeps the app's own order after them. A section the admin disabled, hid from the
 * home ("show_home": false) or moved to «المزيد» only ("more_only": true) is not drawn on the home.
 * Without a registry (the worker answers source != "registry") nothing changes.
 */
final class CigramHomeOrder {
    private static final String URL = "https://cigram-admin-api.wwq-mixtv.workers.dev/home-sections";
    private static final String PREFS = "cigram_home_sections";
    private static final String KEY = "json";
    private static final long REFRESH_GAP_MS = 20000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastTry;
    private static boolean fetching;

    private CigramHomeOrder() { }

    /** Parsed registry; {@link #rank} / {@link #hiddenOnHome} answer per section. */
    static final class Plan {
        final HashMap<String, Integer> order = new HashMap<String, Integer>();
        final HashMap<String, Boolean> hidden = new HashMap<String, Boolean>();
        boolean any;

        private static String key(String s) {
            return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        }

        private String find(String id, String name) {
            String[] candidates = {id, name};
            for (String c : candidates) {
                String k = key(c);
                if (k.length() > 0 && order.containsKey(k)) return k;
            }
            return null;
        }

        /** Registry position (1 = first), or Integer.MAX_VALUE when the admin does not know it. */
        int rank(String id, String name) {
            String k = find(id, name);
            return k == null ? Integer.MAX_VALUE : order.get(k).intValue();
        }

        boolean hiddenOnHome(String id, String name) {
            String k = find(id, name);
            return k != null && hidden.get(k).booleanValue();
        }

        boolean disabled(String id, String name) {
            return hidden.containsKey("off:" + key(id)) || hidden.containsKey("off:" + key(name));
        }
    }

    static Plan plan(Context context) {
        Plan plan = new Plan();
        try {
            String json = prefs(context).getString(KEY, "");
            if (json == null || json.length() == 0) return plan;
            JSONObject root = new JSONObject(json);
            if (!"registry".equals(root.optString("source"))) return plan;
            // 18.3: first entry wins (a later duplicate of an id / slug / name no longer overwrites the admin's earlier position)
            CigramSectionDedupe.buildOrder(root.optJSONArray("sections"), plan.order, plan.hidden);
            JSONArray off = root.optJSONArray("disabled");
            if (off != null) {
                for (int i = 0; i < off.length(); i++) {
                    String k = Plan.key(off.optString(i));
                    if (k.length() > 0) plan.hidden.put("off:" + k, Boolean.TRUE);
                }
            }
            plan.any = !plan.order.isEmpty();
        } catch (Throwable ignored) { }
        return plan;
    }

    /**
     * Fetches the registry in the background; runs {@code onChanged} on the main thread when the
     * answer differs from the cached one. Cheap to call often.
     */
    static void refresh(final Context context, final Runnable onChanged) {
        final Context app = context.getApplicationContext();
        long now = android.os.SystemClock.elapsedRealtime();
        if (fetching || now - lastTry < REFRESH_GAP_MS) return;
        fetching = true;
        lastTry = now;
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection c = null;
                try {
                    CigramPerf.request("home-sections");
                    c = (HttpURLConnection) new URL(URL).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("Cache-Control", "no-cache");
                    if (c.getResponseCode() != 200) return;
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    String body = out.toString("UTF-8");
                    JSONObject check = new JSONObject(body);
                    if (!check.has("sections")) return;
                    // updatedAt moves on every save; compare what matters
                    String fresh = check.optJSONArray("sections").toString()
                            + check.optJSONArray("disabled") + check.optString("source");
                    String old = "";
                    try {
                        JSONObject prev = new JSONObject(prefs(app).getString(KEY, "{}"));
                        old = (prev.optJSONArray("sections") == null ? "" : prev.optJSONArray("sections").toString())
                                + prev.optJSONArray("disabled") + prev.optString("source");
                    } catch (Throwable ignored) { }
                    prefs(app).edit().putString(KEY, body).apply();
                    if (!fresh.equals(old) && onChanged != null) MAIN.post(onChanged);
                } catch (Throwable ignored) {
                } finally {
                    fetching = false;
                    if (c != null) c.disconnect();
                }
            }
        }, "cigram-home-order").start();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
