package com.Cigram.vid;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 19.6 — one format for the quiet line under the IMDb score of every card (home rows, «المزيد», search, TV):
 *
 * <pre>  2025 ¦ كوميديا | دراما</pre>
 *
 * The catalogue stores categories in every shape the admin ever typed ("كوميديا، دراما", "كوميديا,دراما",
 * "كوميديا | دراما", "كوميديا / دراما"...). Nothing is rewritten on the server: every old record is shown in the new
 * format the moment it is drawn, and the admin app now saves new records as "كوميديا | دراما".
 * Pure Java (unit-tested), no Android types.
 */
final class CigramMetaLine {

    /** Between the year and the first genre. */
    static final String YEAR_SEP = " \u00A6 ";
    /** Between two genres. */
    static final String GENRE_SEP = " | ";

    /** Every separator a category list was ever written with (a hyphen only counts with spaces around it). */
    private static final Pattern SPLIT = Pattern.compile("\\s*(?:[,،؛;|\u00A6/•·\\n]+|\\s-\\s|\\s–\\s)\\s*");
    private static final Pattern YEAR = Pattern.compile("^(?:19|20)\\d{2}$");

    private CigramMetaLine() { }

    /** The genres of a category text, trimmed, de-duplicated, in the stored order. */
    static List<String> genres(String category) {
        ArrayList<String> out = new ArrayList<String>();
        if (category == null) return out;
        String raw = category.trim();
        if (raw.length() == 0 || "null".equalsIgnoreCase(raw)) return out;
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String part : SPLIT.split(raw)) {
            String g = part == null ? "" : part.trim();
            if (g.length() == 0) continue;
            if (seen.add(g.toLowerCase(Locale.ROOT))) out.add(g);
        }
        return out;
    }

    /** "كوميديا | دراما" (what the admin app stores from 19.6 on). */
    static String category(String category) {
        List<String> g = genres(category);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.size(); i++) {
            if (i > 0) sb.append(GENRE_SEP);
            sb.append(g.get(i));
        }
        return sb.toString();
    }

    /** "2025 ¦ كوميديا | دراما"; a missing year or category simply drops its part (and the separator). */
    static String line(String year, String category) {
        String y = year == null ? "" : year.trim();
        if ("null".equalsIgnoreCase(y) || "0".equals(y)) y = "";
        if (y.length() > 4 && y.matches("^(?:19|20)\\d{2}-.*")) y = y.substring(0, 4);   // "2025-03-14" -> "2025"
        String c = category(category);
        if (y.length() == 0) return c;
        if (c.length() == 0) return y;
        return y + YEAR_SEP + c;
    }

    /**
     * Re-formats a meta text that was already joined somewhere else ("2025، كوميديا، دراما", "2025 · دراما"): the first part
     * is taken as the year when it looks like one. Anything else is returned unchanged.
     */
    static String normalize(CharSequence text) {
        if (text == null) return "";
        String raw = text.toString().trim();
        if (raw.length() == 0) return raw;
        List<String> parts = genres(raw);
        if (parts.isEmpty()) return raw;
        String first = parts.get(0);
        if (YEAR.matcher(first).matches()) {
            StringBuilder rest = new StringBuilder();
            for (int i = 1; i < parts.size(); i++) {
                if (rest.length() > 0) rest.append(GENRE_SEP);
                rest.append(parts.get(i));
            }
            return line(first, rest.toString());
        }
        return category(raw);
    }

    /** The first genre only (TV spotlight, chips). */
    static String firstGenre(String category) {
        List<String> g = genres(category);
        return g.isEmpty() ? "" : g.get(0);
    }
}
