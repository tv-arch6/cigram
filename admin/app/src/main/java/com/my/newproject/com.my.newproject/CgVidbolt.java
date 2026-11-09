package com.my.newproject;

/**
 * VidBolt embed helpers. VidBolt serves an iframe player (no direct file) at vidbolt.pro,
 * driven by TMDB ids for movies/TV and AniList or MAL ids for anime. The "video" field of an
 * item holds one of these embed URLs and the player renders it inside a WebView / iframe:
 *
 *   movie : https://vidbolt.pro/movie/{tmdb}
 *   tv    : https://vidbolt.pro/tv/{tmdb}/{season}/{episode}
 */
final class CgVidbolt {

    static final String EMBED = "https://vidbolt.pro";

    /** Exact iframe attributes VidBolt documents for its player. */
    static final String ALLOW = "autoplay; encrypted-media; picture-in-picture";

    private CgVidbolt() {}

    /** True for any VidBolt link (vidbolt.pro, or the older vidbolt.xyz links saved earlier). */
    static boolean is(String url) {
        if (url == null) return false;
        String t = url.trim().toLowerCase();
        return t.contains("vidbolt.pro") || t.contains("vidbolt.xyz");
    }

    /** Normalizes whatever the admin pasted (or an older .xyz link) to a vidbolt.pro embed URL. */
    static String embed(String url) {
        if (url == null) return "";
        String t = url.trim();
        if (t.length() == 0) return "";
        return t.replace("vidbolt.xyz", "vidbolt.pro");
    }

    static String movie(String tmdbId) {
        String id = id(tmdbId);
        if (id.length() == 0) return "";
        return EMBED + "/movie/" + id;
    }

    static String tv(String tmdbId, int season, int episode) {
        String id = id(tmdbId);
        if (id.length() == 0 || season < 1 || episode < 1) return "";
        return EMBED + "/tv/" + id + "/" + season + "/" + episode;
    }

    static String anime(String anilistId, int episode) {
        return EMBED + "/anime/" + id(anilistId) + "/" + episode;
    }

    static String animeMal(String malId, int episode) {
        return EMBED + "/anime/mal/" + id(malId) + "/" + episode;
    }

    /** The trailing numeric part of a TMDB id or URL: "themoviedb.org/tv/194583" -> "194583". */
    static String id(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.length() == 0) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*$").matcher(t);
        if (m.find()) return m.group(1);
        return "";
    }

    /**
     * The TMDB id of a series header, however it was stored: "tmdb" / "tmdb_id" (number, text or a themoviedb.org link),
     * else the number inside "series_1402". "" when the header has none.
     */
    static String tmdbOf(org.json.JSONObject h) {
        if (h == null) return "";
        String[] keys = {"tmdb", "tmdb_id"};
        for (String k : keys) {
            String v = h.optString(k, "").trim();
            if (v.length() > 0 && !"null".equals(v)) {
                String id = id(v);
                if (id.length() > 0) return id;
            }
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^series_(\\d+)$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(h.optString("series_id", h.optString("id", "")).trim());
        return m.find() ? m.group(1) : "";
    }

    /** The full iframe markup for a VidBolt URL (same attributes as the VidBolt docs). */
    static String iframe(String url) {
        return "<iframe id=\"vidbolt-player\" src=\"" + html(embed(url)) + "\" width=\"100%\" height=\"100%\""
                + " frameborder=\"0\" allowfullscreen allow=\"" + ALLOW + "\"></iframe>";
    }

    /** Minimal HTML-attribute escaping so an embed URL can be placed in the iframe src. */
    static String html(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
