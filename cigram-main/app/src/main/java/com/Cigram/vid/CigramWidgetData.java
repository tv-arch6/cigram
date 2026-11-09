package com.Cigram.vid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure data side of the home-screen widget (no Android classes, so it is tested on a plain JVM):
 * reads the same /carousel payload the Home page uses and turns it into widget slides.
 */
final class CigramWidgetData {

    static final int MAX_SLIDES = 8;
    static final int FEATURED_MAX = 5;     // the admin carousel gets the first places, the newest titles fill the rest

    private CigramWidgetData() { }

    static final class Slide {
        String title = "";
        String meta = "";
        String imageUrl = "";
        /** What DetailsActivity needs (id, type, title, poster, backdrop), as JSON text. */
        String details = "{}";
        String key = "";
    }

    static String text(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            Object v = o.opt(k);
            if (v != null && v != JSONObject.NULL) {
                String s = String.valueOf(v).trim();
                if (s.length() > 0) return s;
            }
        }
        return "";
    }

    static boolean isHttp(String u) {
        return u != null && (u.startsWith("https://") || u.startsWith("http://"));
    }

    /** "[ ... ]" or { enabled, items | carousel: [ ... ] }. enabled=false (admin switched the carousel off) = no slides. */
    static List<Slide> parse(String raw) {
        List<Slide> out = new ArrayList<Slide>();
        try {
            if (raw == null) return out;
            String t = raw.trim();
            if (t.length() == 0) return out;
            JSONArray items;
            if (t.startsWith("[")) {
                items = new JSONArray(t);
            } else {
                JSONObject p = new JSONObject(t);
                if (!p.optBoolean("enabled", true)) return out;
                items = p.optJSONArray("items");
                if (items == null) items = p.optJSONArray("carousel");
            }
            if (items == null) return out;
            for (int i = 0; i < items.length() && out.size() < MAX_SLIDES; i++) {
                Slide s = slide(items.optJSONObject(i));
                if (s != null) out.add(s);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static Slide slide(JSONObject item) {
        if (item == null) return null;
        String image = text(item, "landscape_poster", "hero", "poster_horizontal", "source_backdrop", "backdrop");
        if (!isHttp(image)) image = text(item, "poster", "source_poster", "image");
        if (!isHttp(image)) return null;                       // no picture, no slide
        String title = text(item, "title", "name", "series_title");
        String id = text(item, "content_id", "id", "series_id");
        if (title.length() == 0 && id.length() == 0) return null;   // nothing to open
        Slide s = new Slide();
        s.imageUrl = image;
        s.title = title;
        s.meta = meta(item);
        s.details = detailsJson(item).toString();
        return s;
    }

    static String meta(JSONObject item) {
        String type = text(item, "type").toLowerCase(Locale.ROOT);
        String label = type.length() == 0 ? "" : (type.contains("series") || type.contains("tv") || type.contains("مسلسل") ? "مسلسل" : "فيلم");
        String year = text(item, "year", "release_year");
        if (year.length() > 4) year = year.substring(0, 4);
        if (label.length() > 0 && year.length() > 0) return label + " · " + year;
        return label.length() > 0 ? label : year;
    }

    /** Same shape the Home carousel passes to openTitleDetails when the title is not in memory yet. */
    static JSONObject detailsJson(JSONObject carousel) {
        JSONObject d = new JSONObject();
        try {
            String id = text(carousel, "content_id", "id");
            d.put("id", id);
            d.put("series_id", text(carousel, "content_id", "series_id"));
            d.put("type", text(carousel, "type"));
            d.put("title", text(carousel, "title", "name"));
            d.put("poster", text(carousel, "source_poster", "poster"));
            d.put("backdrop", text(carousel, "source_backdrop", "landscape_poster", "backdrop"));
        } catch (Throwable ignored) { }
        return d;
    }

    /** True when the JSON text from a widget tap is something DetailsActivity can open. */
    static boolean openable(JSONObject d) {
        return d != null && (text(d, "id", "series_id").length() > 0 || text(d, "title").length() > 0);
    }

    // ------------------------------------------------------------------ the posters of the app itself (catalogue)

    /** True for the "hidden" flag in any of its spellings. */
    private static boolean truthy(Object v) {
        if (v == null || v == JSONObject.NULL) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        String t = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return t.equals("true") || t.equals("1") || t.equals("yes");
    }

    /** Millisecond time a title was added (newest first ordering). */
    private static long added(JSONObject o) {
        Object ms = o.opt("created_at_ms");
        if (ms instanceof Number) return ((Number) ms).longValue();
        try { if (ms != null && ms != JSONObject.NULL) return Long.parseLong(String.valueOf(ms).trim()); } catch (Exception ignored) { }
        String d = text(o, "created_at", "added", "date");
        if (d.length() >= 10) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return f.parse(d.substring(0, 10)).getTime();
            } catch (Exception ignored) { }
        }
        return 0L;
    }

    /**
     * The newest titles of the app (the cached /movies answer the app already keeps): films and series (one slide per series,
     * even when the list holds its episodes), hidden ones skipped, newest first, only those with a picture.
     */
    static List<Slide> fromCatalog(String raw, int limit) {
        List<Slide> out = new ArrayList<Slide>();
        try {
            if (raw == null || raw.trim().length() == 0) return out;
            String t = raw.trim();
            JSONArray rows;
            if (t.startsWith("[")) {
                rows = new JSONArray(t);
            } else {
                JSONObject p = new JSONObject(t);
                rows = null;
                for (String k : new String[]{"movies", "items", "data", "results", "content", "catalog", "entries"}) {
                    rows = p.optJSONArray(k);
                    if (rows != null) break;
                }
            }
            if (rows == null) return out;
            List<JSONObject> list = new ArrayList<JSONObject>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.optJSONObject(i);
                if (o != null && !truthy(o.opt("hidden"))) list.add(o);
            }
            java.util.Collections.sort(list, new java.util.Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) { return Long.compare(added(b), added(a)); }
            });
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            for (JSONObject o : list) {
                if (out.size() >= limit) break;
                String seriesId = text(o, "series_id");
                boolean series = seriesId.length() > 0 || text(o, "type").toLowerCase(Locale.ROOT).contains("series");
                String title = series ? text(o, "series_title", "title", "name") : text(o, "title", "name", "title_ar", "title_en");
                if (title.length() == 0) continue;
                String id = series ? (seriesId.length() > 0 ? seriesId : text(o, "id")) : text(o, "id", "movie_id", "item_id");
                String key = (series ? "s:" : "m:") + (id.length() > 0 ? id : title.toLowerCase(Locale.ROOT));
                if (!seen.add(key)) continue;
                String image = text(o, "backdrop", "landscape_poster", "source_backdrop");
                if (!isHttp(image)) image = text(o, "poster", "series_poster", "season_poster", "image");
                if (!isHttp(image)) continue;
                Slide s = new Slide();
                s.title = title;
                s.imageUrl = image;
                s.key = key;
                String year = text(o, "year", "release_year");
                if (year.length() > 4) year = year.substring(0, 4);
                s.meta = (series ? "مسلسل" : "فيلم") + (year.length() > 0 ? " · " + year : "");
                JSONObject d = new JSONObject();
                d.put("id", id);
                if (series) d.put("series_id", id);
                d.put("type", series ? "series" : "movie");
                d.put("title", title);
                d.put("poster", text(o, "poster", "series_poster", "image"));
                d.put("backdrop", text(o, "backdrop", "landscape_poster", "source_backdrop"));
                s.details = d.toString();
                out.add(s);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** Featured slides first (the admin's carousel), then the newest titles; one slide per title, at most {@code max}. */
    static List<Slide> merge(List<Slide> featured, List<Slide> newest, int max) {
        List<Slide> out = new ArrayList<Slide>();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        List<List<Slide>> lists = new ArrayList<List<Slide>>();
        lists.add(featured);
        lists.add(newest);
        for (List<Slide> l : lists) {
            if (l == null) continue;
            for (Slide s : l) {
                if (out.size() >= max) return out;
                String k = s.title.trim().toLowerCase(Locale.ROOT);
                if (k.length() == 0 || !seen.add(k)) continue;
                out.add(s);
            }
        }
        return out;
    }

    /** Changes when a title or its picture changes: the widget is redrawn only then (no flicker every 2 minutes). */
    static String signature(List<Slide> slides) {
        StringBuilder sb = new StringBuilder();
        for (Slide s : slides) sb.append(s.title).append('|').append(s.imageUrl).append('\n');
        return Integer.toHexString(sb.toString().hashCode()) + ":" + slides.size();
    }
}
