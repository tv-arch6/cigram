package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Admin-controlled badges (حصري / جديد / الأكثر مشاهدة / اختيار سيكرام / مميز).
 *
 * Two sources, no hardcoded titles:
 *  1) the catalog item itself: "badges": ["exclusive","cigram_pick"]  (or "featured_type")
 *  2) overrides published by the admin page (/content-featured), keyed by content id
 *     ("id:<id>") or normalized title ("t:<title>"). An override REPLACES the item's own
 *     badges, an override with an empty list removes them. Nothing here can be written
 *     from the app: the endpoint is read-only for users.
 */
public final class CigramFeatured {

    private static final String PREFS = "cigram_featured_v1";
    private static final long STALE_MS = 20L * 60L * 1000L;

    private static final Object LOCK = new Object();
    private static HashMap<String, List<String>> map = null;
    private static volatile int version = 0;
    private static boolean fetching = false;

    private CigramFeatured() { }

    public static int version() {
        return version;
    }

    private static HashMap<String, List<String>> current(Context context) {
        synchronized (LOCK) {
            if (map == null) {
                map = new HashMap<String, List<String>>();
                try {
                    if (context != null) {
                        String raw = context.getApplicationContext()
                                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", "{}");
                        install(new JSONObject(raw == null ? "{}" : raw), false);
                    }
                } catch (Throwable ignored) { }
            }
            return map;
        }
    }

    private static void install(JSONObject items, boolean bump) {
        HashMap<String, List<String>> fresh = new HashMap<String, List<String>>();
        Iterator<String> it = items.keys();
        while (it.hasNext()) {
            String key = it.next();
            JSONArray arr = items.optJSONArray(key);
            if (arr == null) continue;
            ArrayList<String> ids = new ArrayList<String>();
            for (int i = 0; i < arr.length() && ids.size() < 6; i++) {
                String id = String.valueOf(arr.opt(i)).trim().toLowerCase(Locale.US);
                if (id.length() > 0 && id.length() <= 32 && !ids.contains(id)) ids.add(id);
            }
            fresh.put(key, ids);
        }
        synchronized (LOCK) {
            map = fresh;
            if (bump) version++;
        }
    }

    /** Badge ids of an item (admin override first, then the item's own data). */
    public static List<String> badgesFor(JSONObject item) {
        if (item == null) return new ArrayList<String>();
        HashMap<String, List<String>> m;
        synchronized (LOCK) {
            m = map == null ? new HashMap<String, List<String>>() : map;
        }
        if (!m.isEmpty()) {
            String id = CigramContent.text(item, "id", "movie_id", "item_id", "content_id");
            String sid = CigramContent.text(item, "series_id");
            String title = CigramContent.norm(CigramContent.text(item,
                    "series_title", "title", "name", "title_ar", "title_en"));
            String[] keys = new String[]{"id:" + id, "id:" + sid, "t:" + title};
            for (int i = 0; i < keys.length; i++) {
                List<String> hit = m.get(keys[i]);
                if (hit != null) return hit;
            }
        }
        return CigramContent.badges(item);
    }

    public static void refreshIfStale(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        current(app);   // make sure the cached copy is loaded for the first render
        final SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = prefs.getLong("fetched_at", 0L);
        synchronized (LOCK) {
            if (fetching || System.currentTimeMillis() - last < STALE_MS) return;
            fetching = true;
        }
        CigramSync.runIO(new Runnable() {
            @Override public void run() {
                try {
                    String body = CigramSync.get(CigramSync.API_BASE + "/content-featured", null);
                    if (body == null) return;
                    JSONObject json = new JSONObject(body);
                    JSONObject items = json.optJSONObject("items");
                    if (items == null) return;
                    String text = items.toString();
                    boolean changed = !text.equals(prefs.getString("json", "{}"));
                    prefs.edit().putString("json", text)
                            .putLong("fetched_at", System.currentTimeMillis()).apply();
                    install(items, changed);
                    if (changed) {
                        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                        h.post(new Runnable() {
                            @Override public void run() {
                            }
                        });
                    }
                } catch (Throwable ignored) {
                } finally {
                    synchronized (LOCK) { fetching = false; }
                }
            }
        });
    }
}
