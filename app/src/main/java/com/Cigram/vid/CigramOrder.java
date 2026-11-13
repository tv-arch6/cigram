package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v10.67: one rule for "newest first" and one rule for "an item lives in ONE section".
 *
 * Newest first (home rows, section pages): the row is drawn right to left, so the newest item is
 * the first one. Order of evidence:
 *   1. the date the item (or, for a series, its newest episode) was added — when both have one;
 *   2. the numeric id (a bigger id is a later addition) — when both have one;
 *   3. the time THIS device first saw the item (saved, so a title added tomorrow is first tomorrow
 *      even when the catalog carries neither a date nor a numeric id);
 *   4. the catalog order (the sort is stable).
 * Before, a plain {@code Long.parseLong(id)} turned every id like "m_45" into 0 and strings of
 * different date formats were compared as text, so a new title landed anywhere in the row.
 *
 * One section per item: a title that the admin put in a custom section (anime series, ...) no
 * longer also fills the fixed «أحدث الأفلام» / «المسلسلات المميزة» rows.
 */
public final class CigramOrder {

    private static final String PREFS = "cigram_order_v1";
    private static final String KEY_SEEN = "first_seen";
    private static final int MAX_SEEN = 8000;
    private static final Object LOCK = new Object();
    private static final String[] MAP_KEYS = {
            "type", "id", "series_id", "title", "series_title", "name", "year",
            "created_at_ms", "added_at_ms", "published_at_ms", "updated_at_ms",
            "created_at", "added_at", "published_at", "updated_at",
            "series_episodes_json"};

    private static Context app;
    private static LinkedHashMap<String, Long> seen;
    private static boolean seenDirty;

    private CigramOrder() { }

    public static void init(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (app == null) app = context.getApplicationContext();
        }
    }

    // ------------------------------------------------------------------ ranks

    private static final class Rank {
        long stamp;
        long id;
        long seen;
    }

    public static void sortMaps(List<java.util.HashMap<String, Object>> list) {
        if (list == null || list.size() < 2) return;
        try {
            final IdentityHashMap<Object, Rank> ranks = new IdentityHashMap<Object, Rank>();
            ArrayList<String> keys = new ArrayList<String>();
            for (java.util.HashMap<String, Object> m : list) {
                JSONObject o = view(m);
                Rank r = rankOf(o);
                ranks.put(m, r);
                keys.add(identity(o));
            }
            learn(keys);
            for (int i = 0; i < list.size(); i++) ranks.get(list.get(i)).seen = firstSeen(keys.get(i));
            Collections.sort(list, byRank(ranks));
        } catch (Throwable error) {
            android.util.Log.w("CigramOrder", "sortMaps failed", error);
        }
    }

    public static void sortJson(List<JSONObject> list) {
        if (list == null || list.size() < 2) return;
        try {
            final IdentityHashMap<Object, Rank> ranks = new IdentityHashMap<Object, Rank>();
            ArrayList<String> keys = new ArrayList<String>();
            for (JSONObject o : list) {
                ranks.put(o, rankOf(o));
                keys.add(identity(o));
            }
            learn(keys);
            for (int i = 0; i < list.size(); i++) ranks.get(list.get(i)).seen = firstSeen(keys.get(i));
            Collections.sort(list, byRank(ranks));
        } catch (Throwable error) {
            android.util.Log.w("CigramOrder", "sortJson failed", error);
        }
    }

    private static <T> Comparator<T> byRank(final IdentityHashMap<Object, Rank> ranks) {
        return new Comparator<T>() {
            @Override public int compare(T a, T b) {
                Rank x = ranks.get(a);
                Rank y = ranks.get(b);
                if (x == null || y == null) return 0;
                if (x.stamp > 0L && y.stamp > 0L && x.stamp != y.stamp) return x.stamp < y.stamp ? 1 : -1;
                if (x.id > 0L && y.id > 0L && x.id != y.id) return x.id < y.id ? 1 : -1;
                if (x.seen != y.seen) return x.seen < y.seen ? 1 : -1;
                return 0;
            }
        };
    }

    private static Rank rankOf(JSONObject item) {
        Rank r = new Rank();
        r.stamp = stampOf(item);
        r.id = idOf(item);
        return r;
    }

    /** Added-at of the item, or of its newest episode for a series (0 when there is none). */
    private static long stampOf(JSONObject item) {
        long best = 0L;
        for (String key : new String[]{"created_at_ms", "added_at_ms", "published_at_ms", "updated_at_ms",
                "created_at", "added_at", "published_at", "updated_at"}) {
            long v = CigramContent.parseTime(CigramContent.text(item, key));
            if (v > best) best = v;
            if (best > 0L && (key.endsWith("_ms") || key.equals("created_at"))) break;
        }
        JSONArray eps = episodes(item);
        for (int i = 0; eps != null && i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e == null) continue;
            long v = CigramContent.parseTime(CigramContent.text(e, "created_at_ms", "added_at_ms",
                    "published_at_ms", "created_at", "added_at", "published_at"));
            if (v > best) best = v;
        }
        return best;
    }

    /** Biggest id of the item (and of its episodes): a larger id is a later addition. */
    private static long idOf(JSONObject item) {
        long best = numericId(CigramContent.text(item, "id", "movie_id", "item_id", "content_id"));
        JSONArray eps = episodes(item);
        for (int i = 0; eps != null && i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e == null) continue;
            long v = numericId(CigramContent.text(e, "id", "episode_id"));
            if (v > best) best = v;
        }
        return best;
    }

    // compiled once (it used to be compiled for every item of the catalogue on every sort)
    private static final java.util.regex.Pattern NUMERIC_ID =
            java.util.regex.Pattern.compile("^[A-Za-z_\\-]{0,12}([0-9]{1,18})$");
    private static final java.util.regex.Pattern TRAILING_ZEROS = java.util.regex.Pattern.compile("\\.0+$");

    /** "45", "45.0" and "m_45" are ids; a mixed string (a uuid, a hash) is not trusted. */
    static long numericId(String raw) {
        if (raw == null) return 0L;
        String s = TRAILING_ZEROS.matcher(raw.trim()).replaceAll("");
        if (s.length() == 0 || s.length() > 18 + 12) return 0L;
        java.util.regex.Matcher m = NUMERIC_ID.matcher(s);
        if (!m.matches()) return 0L;
        try { return Long.parseLong(m.group(1)); } catch (Throwable ignored) { return 0L; }
    }

    private static JSONArray episodes(JSONObject item) {
        if (item == null) return null;
        Object nested = item.opt("series_episodes_json");
        try {
            if (nested instanceof JSONArray) return (JSONArray) nested;
            if (nested instanceof String && ((String) nested).trim().startsWith("[")) {
                return new JSONArray((String) nested);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static JSONObject view(Map<String, Object> m) {
        JSONObject o = new JSONObject();
        if (m == null) return o;
        for (String key : MAP_KEYS) {
            Object v = m.get(key);
            if (v == null) continue;
            try { o.put(key, v instanceof Number || v instanceof Boolean ? v : String.valueOf(v)); }
            catch (Throwable ignored) { }
        }
        return o;
    }

    // ------------------------------------------------------------------ first seen

    private static String identity(JSONObject o) {
        boolean series = "series".equalsIgnoreCase(CigramContent.text(o, "type"));
        String id = series ? CigramContent.text(o, "series_id") : CigramContent.text(o, "id", "movie_id");
        if (id.length() == 0) {
            id = CigramContent.norm(CigramContent.text(o, "series_title", "title", "name"))
                    + "|" + CigramContent.text(o, "year");
        }
        return (series ? "s|" : "m|") + id;
    }

    private static void loadSeen() {
        if (seen != null) return;
        seen = new LinkedHashMap<String, Long>();
        if (app == null) return;
        try {
            String raw = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SEEN, "{}");
            JSONObject o = new JSONObject(raw == null ? "{}" : raw);
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                seen.put(k, Long.valueOf(o.optLong(k, 0L)));
            }
        } catch (Throwable ignored) { }
    }

    private static void learn(List<String> keys) {
        synchronized (LOCK) {
            loadSeen();
            long now = System.currentTimeMillis();
            for (String k : keys) {
                if (k == null || k.length() == 0 || seen.containsKey(k)) continue;
                seen.put(k, Long.valueOf(now));
                seenDirty = true;
            }
            while (seen.size() > MAX_SEEN) {
                Iterator<String> it = seen.keySet().iterator();
                it.next();
                it.remove();
            }
            if (seenDirty && app != null) {
                seenDirty = false;
                try {
                    JSONObject o = new JSONObject();
                    for (Map.Entry<String, Long> e : seen.entrySet()) o.put(e.getKey(), e.getValue().longValue());
                    SharedPreferences.Editor ed = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
                    ed.putString(KEY_SEEN, o.toString()).apply();
                } catch (Throwable ignored) { }
            }
        }
    }

    private static long firstSeen(String key) {
        synchronized (LOCK) {
            loadSeen();
            Long v = seen.get(key);
            return v == null ? 0L : v.longValue();
        }
    }

    // ------------------------------------------------------------------ one section per item

    /** Identity keys of every title the admin put in a CUSTOM home section. */
    public static HashSet<String> claims(String catalogJson) {
        HashSet<String> out = new HashSet<String>();
        if (catalogJson == null || catalogJson.trim().length() == 0) return out;
        // the same catalog comes back on every refresh: parse it once
        int stamp = catalogJson.hashCode() * 31 + catalogJson.length();
        synchronized (LOCK) {
            HashSet<String> hit = claimsMemo.get(Integer.valueOf(stamp));
            if (hit != null) return new HashSet<String>(hit);
        }
        try {
            String normalized = CigramTitles.normalizeCatalog(catalogJson);
            out.addAll(CigramStage1InitProvider.customSectionClaims(
                    normalized == null || normalized.length() == 0 ? catalogJson : normalized));
            synchronized (LOCK) {
                claimsMemo.put(Integer.valueOf(stamp), new HashSet<String>(out));
                while (claimsMemo.size() > 3) {
                    Iterator<Integer> it = claimsMemo.keySet().iterator();
                    it.next();
                    it.remove();
                }
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramOrder", "claims failed", error);
        }
        return out;
    }

    private static final LinkedHashMap<Integer, HashSet<String>> claimsMemo =
            new LinkedHashMap<Integer, HashSet<String>>();

    /** Seeds the claims memo for a catalogue whose claims are already known (sections snapshot). */
    public static void primeClaims(String catalogJson, Set<String> claims) {
        if (catalogJson == null || claims == null) return;
        int stamp = catalogJson.hashCode() * 31 + catalogJson.length();
        synchronized (LOCK) {
            claimsMemo.put(Integer.valueOf(stamp), new HashSet<String>(claims));
            while (claimsMemo.size() > 3) {
                Iterator<Integer> it = claimsMemo.keySet().iterator();
                it.next();
                it.remove();
            }
        }
    }

    /** Added-at used for "newest first" (includes the newest episode of a series); 0 when unknown. */
    public static long stampHint(JSONObject item) {
        return item == null ? 0L : stampOf(item);
    }

    static void addClaimKeys(Set<String> out, JSONObject item) {
        if (out == null || item == null) return;
        for (String k : keysOf(item)) out.add(k);
    }

    /** Same rule for a catalogue record that is already JSON (the Home sections are built from JSON, not from maps). */
    public static boolean claimed(Set<String> claims, JSONObject item) {
        if (claims == null || claims.isEmpty() || item == null) return false;
        try {
            for (String k : keysOf(item)) if (claims.contains(k)) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    /** True when the home item belongs to a custom section and must not repeat in a fixed row. */
    public static boolean claimed(Set<String> claims, Map<String, Object> item) {
        if (claims == null || claims.isEmpty() || item == null) return false;
        try {
            for (String k : keysOf(view(item))) if (claims.contains(k)) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    private static List<String> keysOf(JSONObject o) {
        ArrayList<String> keys = new ArrayList<String>();
        boolean series = "series".equalsIgnoreCase(CigramContent.text(o, "type"))
                || CigramContent.isSeries(o);
        String p = series ? "s|" : "m|";
        String sid = CigramContent.text(o, "series_id");
        String id = CigramContent.text(o, "id", "movie_id", "item_id", "content_id");
        String title = CigramContent.norm(CigramContent.text(o, "series_title", "title", "name", "title_ar", "title_en"));
        String year = CigramContent.text(o, "year");
        if (series) {
            if (sid.length() > 0) keys.add(p + "sid|" + sid);
            if (title.length() > 0) keys.add(p + "t|" + title);
        } else {
            if (id.length() > 0) keys.add(p + "id|" + id);
            if (title.length() > 0) keys.add(p + "t|" + title + "|" + year);
        }
        return keys;
    }
}
