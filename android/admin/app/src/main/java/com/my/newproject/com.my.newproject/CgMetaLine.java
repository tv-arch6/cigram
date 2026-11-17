package com.my.newproject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 19.6 — every film / series the admin saves carries its genres as «كوميديا | دراما» (the app shows
 * «2025 ¦ كوميديا | دراما» under the IMDb score). Same rules as the app's CigramMetaLine.
 */
final class CgMetaLine {

    static final String GENRE_SEP = " | ";
    private static final Pattern SPLIT = Pattern.compile("\\s*(?:[,،؛;|\u00A6/•·\\n]+|\\s-\\s|\\s–\\s)\\s*");

    private CgMetaLine() { }

    static List<String> genres(String category) {
        ArrayList<String> out = new ArrayList<>();
        if (category == null) return out;
        String raw = category.trim();
        if (raw.length() == 0) return out;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String part : SPLIT.split(raw)) {
            String g = part == null ? "" : part.trim();
            if (g.length() > 0 && seen.add(g.toLowerCase(Locale.ROOT))) out.add(g);
        }
        return out;
    }

    static String category(String category) {
        List<String> g = genres(category);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.size(); i++) {
            if (i > 0) sb.append(GENRE_SEP);
            sb.append(g.get(i));
        }
        return sb.toString();
    }

    /** Rewrites every "category" text inside a request body (items, changes, rows, episodes…) in place. */
    static void normalizeBody(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Iterator<String> it = o.keys();
            List<String> keys = new ArrayList<>();
            while (it.hasNext()) keys.add(it.next());
            for (String k : keys) {
                Object v = o.opt(k);
                if ("category".equals(k) && v instanceof String) {
                    String n = category((String) v);
                    if (n.length() > 0) {
                        try { o.put(k, n); } catch (Throwable ignored) { }
                    }
                } else if (v instanceof JSONObject || v instanceof JSONArray) {
                    normalizeBody(v);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) normalizeBody(a.opt(i));
        }
    }
}
