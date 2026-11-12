package com.cigram.player;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Local "continue watching" list (the player is a separate app with its own storage). Kept on the device only.
 * Stores: key, position, duration, time, title and - only for plain links that carry no request headers - the link.
 * Links that needed headers (cookies/tokens) are never stored.
 */
final class ResumeStore {

    private static final String PREFS = "cigram_player_resume";
    private static final String KEY = "map";
    private static final int MAX_ENTRIES = 100;

    private ResumeStore() { }

    static final class Entry {
        String key = "", title = "", url = "";
        long position, duration, time;
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** ms to start from; 0 when nothing useful is saved (too early, finished, or within 15 s of the end). */
    static long get(Context c, String key) {
        try {
            JSONObject e = new JSONObject(sp(c).getString(KEY, "{}")).optJSONObject(key);
            if (e == null) return 0L;
            long p = e.optLong("p"), d = e.optLong("d");
            if (p < 5000L) return 0L;
            if (d > 0L && d - p <= 15000L) return 0L;
            return p;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** {@code url} may be "" (not stored). */
    static void put(Context c, String key, long pos, long dur, String title, String url) {
        try {
            JSONObject all = new JSONObject(sp(c).getString(KEY, "{}"));
            all.put(key, new JSONObject().put("p", pos).put("d", dur).put("t", System.currentTimeMillis())
                    .put("n", title == null ? "" : title).put("u", url == null ? "" : url));
            while (all.length() > MAX_ENTRIES) {
                String oldest = null;
                long oldestT = Long.MAX_VALUE;
                Iterator<String> it = all.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject o = all.optJSONObject(k);
                    long t = o == null ? 0L : o.optLong("t");
                    if (t < oldestT) { oldestT = t; oldest = k; }
                }
                if (oldest == null) break;
                all.remove(oldest);
            }
            sp(c).edit().putString(KEY, all.toString()).apply();
        } catch (Throwable ignored) { }
    }

    static void clear(Context c, String key) {
        try {
            JSONObject all = new JSONObject(sp(c).getString(KEY, "{}"));
            all.remove(key);
            sp(c).edit().putString(KEY, all.toString()).apply();
        } catch (Throwable ignored) { }
    }

    static void clearAll(Context c) {
        sp(c).edit().remove(KEY).apply();
    }

    /** Newest first; only entries that can be replayed (have a stored link) and are not finished. */
    static List<Entry> list(Context c, int max) {
        List<Entry> out = new ArrayList<Entry>();
        try {
            JSONObject all = new JSONObject(sp(c).getString(KEY, "{}"));
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject o = all.optJSONObject(k);
                if (o == null || o.optString("u").length() == 0) continue;
                long p = o.optLong("p"), d = o.optLong("d");
                if (p < 5000L || (d > 0L && d - p <= 15000L)) continue;
                Entry e = new Entry();
                e.key = k;
                e.title = o.optString("n");
                e.url = o.optString("u");
                e.position = p;
                e.duration = d;
                e.time = o.optLong("t");
                out.add(e);
            }
        } catch (Throwable ignored) { }
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return a.time == b.time ? 0 : (a.time < b.time ? 1 : -1); }
        });
        return out.size() > max ? new ArrayList<Entry>(out.subList(0, max)) : out;
    }
}
