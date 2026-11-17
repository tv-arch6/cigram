package com.my.newproject;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * 3.0.2 — what the last «Cigram Live Health» scan found, kept on the phone.
 *
 * <p>Live Health used to remember only «this key was down» booleans. «قنوات تحتاج رابطاً» needs the channels themselves
 * (name, section, the stopped link, the reason), so the scan now also stores the list of stopped channels. That list is the
 * second source of the screen: it works with no server support at all, while the first source (what the viewers reported with
 * «بلّغ المدير») needs the Worker route {@code /admin/channel-reports}.
 *
 * <p>Pure data helpers (no views): {@link #downJson} and {@link #merge} are unit-tested on a plain JVM.
 */
final class LiveHealthStore {

    private LiveHealthStore() { }

    static final String PREFS = "cigram_live_health";
    static final String KEY_ITEMS = "down_items";
    static final String KEY_LAST = "last";
    /** Cap: a catalogue of thousands where everything is down must not bloat the preferences file. */
    static final int MAX_ITEMS = 300;

    /** One stopped channel as the report screen and the replace sheet read it. */
    static JSONObject downJson(String id, String name, String section, String url, String detail) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id == null ? "" : id);
            o.put("name", name == null ? "" : name);
            o.put("section", section == null ? "" : section);
            o.put("url", url == null ? "" : url);
            o.put("detail", detail == null ? "" : detail);
            o.put("kind", "health");
        } catch (Exception ignored) { }
        return o;
    }

    static void save(Context c, long when, List<JSONObject> down) {
        JSONArray a = new JSONArray();
        for (int i = 0; down != null && i < down.size() && i < MAX_ITEMS; i++) a.put(down.get(i));
        try {
            SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            e.putString(KEY_ITEMS, a.toString()).putLong(KEY_LAST, when);
            e.apply();
        } catch (Throwable ignored) { }
    }

    static JSONArray down(Context c) {
        try {
            String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, "");
            return s.length() == 0 ? new JSONArray() : new JSONArray(s);
        } catch (Throwable e) {
            return new JSONArray();
        }
    }

    static long last(Context c) {
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L);
        } catch (Throwable e) {
            return 0L;
        }
    }

    /** A channel whose link was just replaced is no longer «stopped» until the next scan proves otherwise. */
    static void forget(Context c, String name) {
        JSONArray cur = down(c);
        JSONArray keep = new JSONArray();
        String want = norm(name);
        for (int i = 0; i < cur.length(); i++) {
            JSONObject o = cur.optJSONObject(i);
            if (o != null && !norm(o.optString("name")).equals(want)) keep.put(o);
        }
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ITEMS, keep.toString()).apply();
        } catch (Throwable ignored) { }
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * Reported channels first (viewers are waiting for them), then the stopped ones from the last scan that nobody reported yet.
     * Both lists are arrays of objects with at least {@code name}; a channel never appears twice.
     */
    static JSONArray merge(JSONArray reported, JSONArray stopped) {
        JSONArray out = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        for (int i = 0; reported != null && i < reported.length(); i++) {
            JSONObject o = reported.optJSONObject(i);
            if (o == null || !seen.add(norm(o.optString("name")))) continue;
            try { if (!o.has("kind")) o.put("kind", "report"); } catch (Exception ignored) { }
            out.put(o);
        }
        for (int i = 0; stopped != null && i < stopped.length(); i++) {
            JSONObject o = stopped.optJSONObject(i);
            if (o == null || !seen.add(norm(o.optString("name")))) continue;
            out.put(o);
        }
        return out;
    }
}
