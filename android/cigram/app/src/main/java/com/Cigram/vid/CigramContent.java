package com.Cigram.vid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Read-only helpers over the catalog that is already held in memory by
 * CigramStage1InitProvider. Nothing here touches the network or SharedPreferences.
 * All the features (home sections, discover, search, report, badges) read content
 * metadata through this one class so they agree with each other.
 */
public final class CigramContent {

    private CigramContent() { }

    // ---------------------------------------------------------------- basics

    public static String norm(String value) {
        try {
            return CigramStage1InitProvider.cigramNormalize(value);
        } catch (Throwable ignored) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }
    }

    public static String text(JSONObject object, String... keys) {
        if (object == null || keys == null) return "";
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            if (key == null) continue;
            Object value = object.opt(key);
            if (value != null && value != JSONObject.NULL) {
                String s = String.valueOf(value).trim();
                if (s.length() > 0 && !"null".equalsIgnoreCase(s)) return s;
            }
        }
        return "";
    }

    public static ArrayList<JSONObject> catalog() {
        try {
            return CigramStage1InitProvider.cigramCatalogSnapshot();
        } catch (Throwable ignored) {
            return new ArrayList<JSONObject>();
        }
    }

    public static JSONObject find(String type, String id, String title) {
        try {
            return CigramStage1InitProvider.cigramCatalogFind(type, id, title);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isSeries(JSONObject item) {
        if (item == null) return false;
        String t = norm(text(item, "type", "content_type", "media_type"));
        if ("series".equals(t) || "episode".equals(t) || "tv".equals(t)) return true;
        if ("movie".equals(t)) return false;
        return text(item, "series_id", "series_episodes_json", "season", "episode").length() > 0;
    }

    public static String typeOf(JSONObject item) {
        return isSeries(item) ? "series" : "movie";
    }

    public static String title(JSONObject item) {
        return text(item, "series_title", "title", "name", "title_ar", "title_en");
    }

    // ------------------------------------------------------------------ keys

    /** Stable key of a series, independent of which screen produced the object. */
    public static String seriesKey(JSONObject item) {
        String t = norm(text(item, "series_title", "title", "name", "title_ar", "title_en"));
        return t.length() == 0 ? "" : "s|" + t;
    }

    public static String seriesKeyOfTitle(String seriesTitle) {
        String t = norm(seriesTitle);
        return t.length() == 0 ? "" : "s|" + t;
    }

    /** Unique key of one episode: series + season + episode number. */
    public static String episodeKey(String seriesKey, int season, int episode) {
        if (seriesKey == null || seriesKey.length() == 0) return "";
        return "e|" + seriesKey + "|" + (season <= 0 ? 1 : season) + "|" + episode;
    }

    public static String movieKey(JSONObject item) {
        String id = text(item, "id", "movie_id", "item_id", "content_id");
        if (id.length() > 0) return "m|" + norm(id);
        String t = norm(text(item, "title", "name", "title_ar", "title_en"));
        return t.length() == 0 ? "" : "m|t|" + t;
    }

    /** Key used to compare two catalog items (series by title, movie by id/title). */
    public static String itemKey(JSONObject item) {
        return isSeries(item) ? seriesKey(item) : movieKey(item);
    }

    public static int intOf(JSONObject o, String... keys) {
        String s = text(o, keys).replaceAll("[^0-9]", "");
        if (s.length() == 0 || s.length() > 6) return 0;
        try { return Integer.parseInt(s); } catch (Throwable ignored) { return 0; }
    }

    // -------------------------------------------------------------- metadata

    public static int year(JSONObject item) {
        String s = text(item, "year", "release_year", "release_date");
        for (int i = 0; i + 4 <= s.length(); i++) {
            String part = s.substring(i, i + 4);
            boolean digits = true;
            for (int k = 0; k < 4; k++) {
                if (!Character.isDigit(part.charAt(k))) { digits = false; break; }
            }
            if (digits) {
                int y = Integer.parseInt(part);
                if (y >= 1900 && y <= 2100) return y;
            }
        }
        return 0;
    }

    public static double rating(JSONObject item) {
        String s = text(item, "imdb", "imdb_rating", "rating", "vote_average", "score");
        if (s.length() == 0) return 0d;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c) || c == '.') b.append(c);
            else if (c == ',') b.append('.');
            else if (b.length() > 0) break;
        }
        try {
            double v = Double.parseDouble(b.toString());
            return v >= 0d && v <= 10d ? v : 0d;
        } catch (Throwable ignored) {
            return 0d;
        }
    }

    /** Normalized genre tokens ("action", "دراما"...). */
    public static List<String> genres(JSONObject item) {
        ArrayList<String> out = new ArrayList<String>();
        if (item == null) return out;
        String raw = "";
        for (String key : new String[]{"category", "genre", "genres"}) {
            Object v = item.opt(key);
            if (v == null || v == JSONObject.NULL) continue;
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < a.length(); i++) {
                    Object e = a.opt(i);
                    String name = e instanceof JSONObject
                            ? text((JSONObject) e, "name", "title") : String.valueOf(e);
                    if (b.length() > 0) b.append(',');
                    b.append(name);
                }
                raw = b.toString();
            } else {
                raw = String.valueOf(v);
            }
            if (raw.trim().length() > 0) break;
        }
        if (raw.trim().length() == 0) return out;
        String[] parts = raw.split("[,،/|؛;\\n]+");
        for (int i = 0; i < parts.length; i++) {
            String g = norm(parts[i]);
            if (g.length() > 0 && !out.contains(g)) out.add(g);
        }
        return out;
    }

    /** Display form of the genres, as written by the admin (for filter chips). */
    public static List<String> genreLabels(JSONObject item) {
        ArrayList<String> out = new ArrayList<String>();
        String raw = text(item, "category", "genre", "genres");
        if (raw.startsWith("[")) {
            raw = raw.replace("[", "").replace("]", "").replace("\"", "");
        }
        String[] parts = raw.split("[,،/|؛;\\n]+");
        for (int i = 0; i < parts.length; i++) {
            String g = parts[i].trim();
            if (g.length() > 0 && !out.contains(g)) out.add(g);
        }
        return out;
    }

    public static int commonGenres(JSONObject a, JSONObject b) {
        List<String> ga = genres(a);
        List<String> gb = genres(b);
        int n = 0;
        for (int i = 0; i < ga.size(); i++) if (gb.contains(ga.get(i))) n++;
        return n;
    }

    // ----------------------------------------------------------------- dates

    private static final String[] DATE_FORMATS = new String[]{
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"
    };

    /** Parses ms-epoch, seconds-epoch or an ISO-like date. 0 when unknown. */
    public static long parseTime(String raw) {
        if (raw == null) return 0L;
        String s = raw.trim();
        if (s.length() == 0) return 0L;
        boolean numeric = true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) { numeric = false; break; }
        }
        if (numeric) {
            try {
                long v = Long.parseLong(s);
                if (v >= 1000000000000L) return v;
                if (v >= 1000000000L) return v * 1000L;
            } catch (Throwable ignored) { }
            return 0L;
        }
        synchronized (TIME_MEMO) {
            Long known = TIME_MEMO.get(s);
            if (known != null) return known.longValue();
        }
        long result = 0L;
        SimpleDateFormat[] formats = FORMATS.get();
        for (int i = 0; i < formats.length; i++) {
            try {
                Date d = formats[i].parse(s);
                if (d != null) { result = d.getTime(); break; }
            } catch (Throwable ignored) { }
        }
        synchronized (TIME_MEMO) { TIME_MEMO.put(s, Long.valueOf(result)); }
        return result;
    }

    // Perf: a new SimpleDateFormat for every format on every call (per item, per episode, per sort)
    // was one of the biggest CPU costs while Home was built. One set per thread, and a memo of the
    // answers (the same dates come back on every render).
    private static final ThreadLocal<SimpleDateFormat[]> FORMATS = new ThreadLocal<SimpleDateFormat[]>() {
        @Override protected SimpleDateFormat[] initialValue() {
            SimpleDateFormat[] out = new SimpleDateFormat[DATE_FORMATS.length];
            for (int i = 0; i < out.length; i++) {
                SimpleDateFormat f = new SimpleDateFormat(DATE_FORMATS[i], Locale.US);
                f.setLenient(false);
                if (DATE_FORMATS[i].indexOf('X') < 0) f.setTimeZone(TimeZone.getTimeZone("UTC"));
                out[i] = f;
            }
            return out;
        }
    };
    private static final java.util.LinkedHashMap<String, Long> TIME_MEMO =
            new java.util.LinkedHashMap<String, Long>(256, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
                    return size() > 6000;
                }
            };

    /**
     * Real "added" date of an item. Old items without any date return 0 and are
     * therefore never treated as new (the numeric id is deliberately NOT used).
     */
    private static final java.util.Map<JSONObject, Long> ADDED_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<JSONObject, Long>());
    private static final java.util.Map<JSONObject, Long> EPISODE_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<JSONObject, Long>());

    public static long addedAtMs(JSONObject item) {
        if (item == null) return 0L;
        Long hit = ADDED_CACHE.get(item);
        if (hit != null) return hit.longValue();
        long value = computeAddedAtMs(item);
        ADDED_CACHE.put(item, Long.valueOf(value));
        return value;
    }

    private static long computeAddedAtMs(JSONObject item) {
        for (String key : new String[]{"created_at_ms", "added_at_ms", "published_at_ms"}) {
            long v = parseTime(text(item, key));
            if (v > 0L) return v;
        }
        for (String key : new String[]{"created_at", "added_at", "published_at"}) {
            long v = parseTime(text(item, key));
            if (v > 0L) return v;
        }
        return 0L;
    }

    /** Newest episode date of a series (0 when episodes carry no date). */
    public static long newestEpisodeAddedMs(JSONObject series) {
        if (series == null) return 0L;
        Long hit = EPISODE_CACHE.get(series);
        if (hit != null) return hit.longValue();
        long value = computeNewestEpisode(series);
        EPISODE_CACHE.put(series, Long.valueOf(value));
        return value;
    }

    /** Cheap variant for UI code on the main thread: 0 until the background pass computed it. */
    public static long newestEpisodeAddedMsIfKnown(JSONObject series) {
        Long hit = series == null ? null : EPISODE_CACHE.get(series);
        return hit == null ? 0L : hit.longValue();
    }

    private static long computeNewestEpisode(JSONObject series) {
        long best = 0L;
        try {
            JSONArray eps = CigramStage1InitProvider.cigramEpisodes(series);
            for (int i = 0; i < eps.length(); i++) {
                long v = addedAtMs(eps.optJSONObject(i));
                if (v > best) best = v;
            }
        } catch (Throwable ignored) { }
        return best;
    }

    // ---------------------------------------------------------------- badges

    public static final int WEEK_DAYS = 7;

    public static boolean isRecent(long ms, long nowMs) {
        return ms > 0L && ms <= nowMs + 3600000L
                && nowMs - ms <= WEEK_DAYS * 24L * 3600000L;
    }

    /** Internal badge ids stored in the catalog ("badges": [...] or "featured_type"). */
    public static List<String> badges(JSONObject item) {
        ArrayList<String> out = new ArrayList<String>();
        if (item == null) return out;
        Object v = item.opt("badges");
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) addBadge(out, String.valueOf(a.opt(i)));
        } else if (v != null && v != JSONObject.NULL) {
            String s = String.valueOf(v);
            if (s.startsWith("[")) {
                try {
                    JSONArray a = new JSONArray(s);
                    for (int i = 0; i < a.length(); i++) addBadge(out, String.valueOf(a.opt(i)));
                } catch (Throwable ignored) { }
            } else {
                String[] parts = s.split("[,،|;]+");
                for (int i = 0; i < parts.length; i++) addBadge(out, parts[i]);
            }
        }
        addBadge(out, text(item, "featured_type"));
        return out;
    }

    private static void addBadge(List<String> out, String raw) {
        if (raw == null) return;
        String id = raw.trim().toLowerCase(Locale.US);
        if (id.length() == 0 || "null".equals(id) || out.contains(id)) return;
        out.add(id);
    }

    public static String badgeLabel(String id) {
        if (id == null) return "";
        String k = id.trim().toLowerCase(Locale.US);
        if ("exclusive".equals(k)) return "حصري";
        if ("new".equals(k)) return "جديد";
        if ("trending".equals(k) || "most_watched".equals(k)) return "الأكثر مشاهدة";
        if ("cigram_pick".equals(k) || "pick".equals(k)) return "اختيار سيكرام";
        if ("featured".equals(k)) return "مميز";
        return "";
    }

    public static int badgeColor(String id) {
        String k = id == null ? "" : id.trim().toLowerCase(Locale.US);
        if ("exclusive".equals(k)) return 0xFFE21B14;
        if ("new".equals(k)) return 0xFF1E9E5A;
        if ("trending".equals(k) || "most_watched".equals(k)) return 0xFFE0841B;
        if ("cigram_pick".equals(k) || "pick".equals(k)) return 0xFF7B3FE4;
        return 0xFF1B6FE2;
    }
}
