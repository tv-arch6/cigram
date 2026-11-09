package com.Cigram.vid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 19.0 — PrimeTV-style discovery rows computed from the REAL catalogue, in addition to the sections the administrator created.
 *
 * Nothing is invented and nothing of the administrator's is replaced:
 *  - every row is built only from records that exist in the catalogue, ranked by fields that exist on them
 *    (imdb rating, created_at_ms, the newest episode of a series, category / country / language text)
 *  - a row whose subject the administrator already has a section for (by name) is NOT added
 *  - a row with fewer than {@link #MIN_ITEMS} titles is not shown (no sparse rows), none has more than {@link #MAX_ITEMS}
 *  - a row never contains the same title twice, never a hidden record, never a series without a playable episode
 *  - the rows are appended AFTER the administrator's sections and are ordinary sections for the rest of the app: the
 *    administrator can still hide / reorder them by id (derived_*), as with any other section
 *
 * Rows (id → what it is):
 *  derived_top_movies        «أفلام الأعلى تقييماً»       movies by imdb rating
 *  derived_top_series        «مسلسلات الأعلى تقييماً»     series by imdb rating
 *  derived_recent_series     «مسلسلات تتجدد»              series by their newest episode / head
 *  derived_documentaries     «أفلام وثائقية»              category says documentary
 *  derived_turkish_series    «دراما تركية»                series whose category / country / language says Turkish
 *  derived_korean_series     «دراما كورية»                the same for Korean
 *  derived_arabic            «أعمال عربية»                movies + series that say Arabic
 * The nationality rows need a nationality in the data (category text, or country / origin_country / language fields). The
 * catalogue of 2026-11-09 has none, so they stay absent until the administrator's data says so: nothing is guessed.
 */
final class CigramHomeGroups {

    static final int MIN_ITEMS = 6;
    static final int MIN_ITEMS_SMALL = 3;     // documentaries: a genre row may be short
    static final int MAX_ITEMS = 30;

    /** One derived row: its identity and the records (heads + their episodes) it is made of. */
    static final class Group {
        final String id;
        final String name;
        final List<JSONObject> heads = new ArrayList<JSONObject>();
        final List<JSONObject> episodes = new ArrayList<JSONObject>();
        Group(String id, String name) { this.id = id; this.name = name; }
    }

    private CigramHomeGroups() { }

    // ------------------------------------------------------------------ public

    /** The flat list of records inside a catalogue document (array, or an object with movies / items / data / ...). */
    static JSONArray records(Object root) {
        if (root instanceof JSONArray) return (JSONArray) root;
        if (root instanceof JSONObject) {
            JSONObject o = (JSONObject) root;
            for (String k : new String[]{"movies", "items", "data", "results", "content", "catalog"}) {
                Object v = o.opt(k);
                if (v instanceof JSONArray) return (JSONArray) v;
                if (v instanceof JSONObject) {
                    JSONArray inner = records(v);
                    if (inner != null) return inner;
                }
            }
        }
        return null;
    }

    /**
     * @param records       the catalogue records
     * @param existingNames names of the sections that already exist (administrator's + the fixed rows): a subject covered there is skipped
     */
    static List<Group> compute(JSONArray records, Collection<String> existingNames) {
        List<Group> out = new ArrayList<Group>();
        if (records == null || records.length() == 0) return out;

        List<JSONObject> movies = new ArrayList<JSONObject>();
        List<JSONObject> series = new ArrayList<JSONObject>();
        Map<String, List<JSONObject>> episodesOf = new HashMap<String, List<JSONObject>>();
        Map<String, Long> newestEpisode = new HashMap<String, Long>();
        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject r = records.optJSONObject(i);
            if (r == null || hidden(r)) continue;
            String type = type(r);
            if ("movie".equals(type)) {
                if (usable(r, true) && seen.add("m|" + text(r, "id"))) movies.add(r);
            } else if ("series".equals(type)) {
                if (usable(r, false) && seen.add("s|" + seriesKey(r))) series.add(r);
            } else if ("episode".equals(type)) {
                String sid = text(r, "series_id");
                if (sid.length() == 0) continue;
                List<JSONObject> list = episodesOf.get(sid);
                if (list == null) { list = new ArrayList<JSONObject>(); episodesOf.put(sid, list); }
                list.add(r);
                long ts = created(r);
                Long old = newestEpisode.get(sid);
                if (old == null || ts > old) newestEpisode.put(sid, ts);
            }
        }
        // a series without a single episode cannot be played: never shown
        List<JSONObject> playable = new ArrayList<JSONObject>();
        for (JSONObject s : series) if (episodesOf.containsKey(seriesKey(s)) || episodesOf.containsKey(text(s, "id"))) playable.add(s);
        series = playable;

        Set<String> have = normalizedNames(existingNames);

        // ---- top rated movies / series (imdb)
        if (!covered(have, "الاعلي تقييما", "اعلي تقييم", "top rated", "افلام شائعه", "افلام رائجه")) {
            Group g = new Group("derived_top_movies", "أفلام الأعلى تقييماً");
            g.heads.addAll(top(movies, MIN_RATING, new RatingOrder()));
            if (g.heads.size() >= MIN_ITEMS) out.add(g);
        }
        if (!covered(have, "مسلسلات الاعلي تقييما", "مسلسلات شائعه", "مسلسلات رائجه", "top rated series")) {
            Group g = new Group("derived_top_series", "مسلسلات الأعلى تقييماً");
            g.heads.addAll(top(series, MIN_RATING, new RatingOrder()));
            if (g.heads.size() >= MIN_ITEMS) { attachEpisodes(g, episodesOf); out.add(g); }
        }
        // ---- recently updated series (newest episode, else the head's own date)
        if (!covered(have, "مسلسلات تتجدد", "احدث المسلسلات", "مسلسلات جديده", "مسلسلات حديثه", "recent series", "new series")) {
            final Map<String, Long> activity = new HashMap<String, Long>();
            for (JSONObject s : series) {
                String key = seriesKey(s);
                Long ep = newestEpisode.get(key);
                if (ep == null) ep = newestEpisode.get(text(s, "id"));
                activity.put(key, Math.max(created(s), ep == null ? 0L : ep));
            }
            List<JSONObject> sorted = new ArrayList<JSONObject>(series);
            Collections.sort(sorted, new Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) {
                    return Long.compare(activity.get(seriesKey(b)), activity.get(seriesKey(a)));
                }
            });
            Group g = new Group("derived_recent_series", "مسلسلات تتجدد");
            for (JSONObject s : sorted) { if (g.heads.size() >= MAX_ITEMS) break; if (activity.get(seriesKey(s)) > 0L) g.heads.add(s); }
            if (g.heads.size() >= MIN_ITEMS) { attachEpisodes(g, episodesOf); out.add(g); }
        }
        // ---- documentaries (category text)
        if (!covered(have, "وثايقي", "وثائقي", "documentar")) {
            Group g = new Group("derived_documentaries", "أفلام وثائقية");
            List<JSONObject> all = new ArrayList<JSONObject>(movies);
            all.addAll(series);
            for (JSONObject r : sortedByRecency(all)) {
                if (g.heads.size() >= MAX_ITEMS) break;
                if (matches(r, DOC_TOKENS, false)) g.heads.add(r);
            }
            if (g.heads.size() >= MIN_ITEMS_SMALL) { attachEpisodes(g, episodesOf); out.add(g); }
        }
        // ---- nationality rows: only when the DATA says so
        addNational(out, have, "derived_turkish_series", "دراما تركية", series, episodesOf, TR_TOKENS, TR_CODES, new String[]{"تركي", "تركيه"});
        addNational(out, have, "derived_korean_series", "دراما كورية", series, episodesOf, KR_TOKENS, KR_CODES, new String[]{"كوري"});
        if (!covered(have, "عربي", "عربيه", "arabic")) {
            Group g = new Group("derived_arabic", "أعمال عربية");
            List<JSONObject> all = new ArrayList<JSONObject>(movies);
            all.addAll(series);
            for (JSONObject r : sortedByRecency(all)) {
                if (g.heads.size() >= MAX_ITEMS) break;
                if (matches(r, AR_TOKENS, false) || matches(r, AR_CODES, true)) g.heads.add(r);
            }
            if (g.heads.size() >= MIN_ITEMS) { attachEpisodes(g, episodesOf); out.add(g); }
        }
        return out;
    }

    // ------------------------------------------------------------------ rules

    private static final double MIN_RATING = 6.0;
    private static final String[] DOC_TOKENS = {"وثائقي", "وثايقي", "documentary", "documentaries"};
    private static final String[] TR_TOKENS = {"تركي", "تركيه", "turkish", "turkey"};
    private static final String[] TR_CODES = {"tr", "tur"};
    private static final String[] KR_TOKENS = {"كوري", "كوريه", "korean", "korea"};
    private static final String[] KR_CODES = {"ko", "kr", "kor"};
    private static final String[] AR_TOKENS = {"عربي", "عربيه", "arabic"};
    private static final String[] AR_CODES = {"ar", "ara"};

    private static void addNational(List<Group> out, Set<String> have, String id, String name, List<JSONObject> series,
                                    Map<String, List<JSONObject>> episodesOf, String[] tokens, String[] codes, String[] aliases) {
        if (covered(have, aliases)) return;
        Group g = new Group(id, name);
        for (JSONObject r : sortedByRecency(series)) {
            if (g.heads.size() >= MAX_ITEMS) break;
            if (matches(r, tokens, false) || matches(r, codes, true)) g.heads.add(r);
        }
        if (g.heads.size() >= MIN_ITEMS) { attachEpisodes(g, episodesOf); out.add(g); }
    }

    /** Text of category (+ title tags) for words; the exact code fields (country / origin_country / language / lang) for codes. */
    private static boolean matches(JSONObject r, String[] needles, boolean codesOnly) {
        if (codesOnly) {
            for (String field : new String[]{"country", "origin_country", "language", "lang", "original_language"}) {
                String v = norm(text(r, field));
                if (v.length() == 0) continue;
                for (String part : v.split("[ ,|/]+")) for (String n : needles) if (part.equals(n)) return true;
            }
            return false;
        }
        String hay = norm(text(r, "category") + " " + text(r, "country") + " " + text(r, "origin_country") + " " + text(r, "language"));
        for (String n : needles) if (hay.contains(norm(n))) return true;
        return false;
    }

    private static boolean covered(Set<String> have, String... aliases) {
        for (String a : aliases) {
            String n = norm(a);
            for (String h : have) if (h.contains(n)) return true;
        }
        return false;
    }

    private static Set<String> normalizedNames(Collection<String> names) {
        Set<String> out = new HashSet<String>();
        if (names != null) for (String s : names) if (s != null && s.trim().length() > 0) out.add(norm(s));
        return out;
    }

    private static final class RatingOrder implements Comparator<JSONObject> {
        @Override public int compare(JSONObject a, JSONObject b) {
            int c = Double.compare(rating(b), rating(a));
            return c != 0 ? c : Long.compare(created(b), created(a));
        }
    }

    private static List<JSONObject> top(List<JSONObject> all, double min, Comparator<JSONObject> order) {
        List<JSONObject> rated = new ArrayList<JSONObject>();
        for (JSONObject r : all) if (rating(r) >= min) rated.add(r);
        Collections.sort(rated, order);
        return rated.size() > MAX_ITEMS ? new ArrayList<JSONObject>(rated.subList(0, MAX_ITEMS)) : rated;
    }

    private static List<JSONObject> sortedByRecency(List<JSONObject> all) {
        List<JSONObject> s = new ArrayList<JSONObject>(all);
        Collections.sort(s, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) { return Long.compare(created(b), created(a)); }
        });
        return s;
    }

    private static void attachEpisodes(Group g, Map<String, List<JSONObject>> episodesOf) {
        for (JSONObject s : g.heads) {
            if (!"series".equals(type(s))) continue;
            List<JSONObject> eps = episodesOf.get(seriesKey(s));
            if (eps == null) eps = episodesOf.get(text(s, "id"));
            if (eps != null) g.episodes.addAll(eps);
        }
    }

    // ------------------------------------------------------------------ fields

    static String type(JSONObject r) {
        String t = norm(text(r, "type", "content_type", "media_type"));
        if (t.equals("episode") || t.equals("حله")) return "episode";
        if (t.equals("series") || t.equals("tv") || t.equals("show") || t.contains("مسلسل")) return "series";
        if (t.equals("movie") || t.equals("film") || t.equals("فيلم")) return "movie";
        return "";
    }

    static boolean hidden(JSONObject r) {
        Object h = r.opt("hidden");
        if (h instanceof Boolean) return (Boolean) h;
        String s = h == null ? "" : String.valueOf(h).trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("yes");
    }

    private static boolean usable(JSONObject r, boolean needsVideo) {
        if (text(r, "title", "name").length() == 0) return false;
        if (text(r, "poster", "image", "backdrop").length() == 0) return false;
        return !needsVideo || text(r, "video", "link", "video_url", "stream_url").length() > 0;
    }

    private static String seriesKey(JSONObject r) {
        String sid = text(r, "series_id");
        return sid.length() > 0 ? sid : text(r, "id");
    }

    static double rating(JSONObject r) {
        String v = text(r, "imdb", "rating");
        if (v.length() == 0) return 0d;
        try {
            double d = Double.parseDouble(v.replace(',', '.'));
            return d > 0d && d <= 10d ? d : 0d;
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    static long created(JSONObject r) {
        long ms = r.optLong("created_at_ms", 0L);
        if (ms > 0L) return ms;
        String iso = text(r, "created_at");
        if (iso.length() >= 19) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return f.parse(iso.substring(0, 19)).getTime();
            } catch (Throwable ignored) { }
        }
        return 0L;
    }

    private static String text(JSONObject r, String... keys) {
        for (String k : keys) {
            Object v = r.opt(k);
            if (v == null || v == JSONObject.NULL) continue;
            String s = String.valueOf(v).trim();
            if (s.length() > 0 && !"null".equals(s)) return s;
        }
        return "";
    }

    /** Lower case, no diacritics, alef / ya / ta-marbuta unified: the comparison form of names and category text. */
    static String norm(String s) {
        if (s == null) return "";
        String t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").replace("ـ", "").toLowerCase(Locale.ROOT)
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا').replace('ى', 'ي').replace('ة', 'ه').replace('ؤ', 'و').replace('ئ', 'ي');
        return t.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** For logs and tests: id -> number of titles. */
    static Map<String, Integer> sizes(List<Group> groups) {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        for (Group g : groups) m.put(g.id, g.heads.size());
        return m;
    }
}
