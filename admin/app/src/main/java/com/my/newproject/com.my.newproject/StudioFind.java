package com.my.newproject;

import java.util.ArrayList;
import java.util.List;

/**
 * The Studio's search: finds tools by what the admin types, in Arabic or English, forgiving the usual Arabic spelling
 * differences (أ إ آ → ا · ى → ي · ة → ه · tashkeel · tatweel · Arabic-Indic digits). Pure logic (no Android).
 *
 * Every word of the query must match, compared by its light Arabic stem ({@link #stem}) so ترجمة finds ترجمات. A word that
 * STARTS a word of the title scores most, then one inside the title, then the keywords, then the one-line description.
 * Ties keep the order of {@link StudioMap#TOOLS}.
 */
final class StudioFind {

    private StudioFind() { }

    private static final int W_TITLE_START = 100;
    private static final int W_TITLE_IN = 70;
    private static final int W_KEYS_START = 55;
    private static final int W_KEYS_IN = 40;
    private static final int W_SUB_IN = 20;
    private static final int W_EXACT = 100;

    /** Lower-cases and folds Arabic spelling variants; keeps letters, digits and single spaces. */
    static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'ً' && c <= 'ٟ') continue;        // tashkeel
            if (c == 'ـ') continue;                          // tatweel
            if (c == 'أ' || c == 'إ' || c == 'آ' || c == 'ٱ') c = 'ا';   // أ إ آ ٱ → ا
            else if (c == 'ى') c = 'ي';                 // ى → ي
            else if (c == 'ة') c = 'ه';                 // ة → ه
            else if (c >= '٠' && c <= '٩') c = (char) ('0' + (c - '٠'));          // ٠-٩
            else if (c >= '۰' && c <= '۹') c = (char) ('0' + (c - '۰'));          // ۰-۹
            c = Character.toLowerCase(c);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
                space = false;
            } else if (!space) {
                sb.append(' ');
                space = true;
            }
        }
        int n = sb.length();
        if (n > 0 && sb.charAt(n - 1) == ' ') sb.setLength(n - 1);
        return sb.toString();
    }

    static String[] tokens(String query) {
        String q = norm(query);
        return q.length() == 0 ? new String[0] : q.split(" ");
    }

    private static boolean startsAWord(String hay, String token) {
        if (hay.startsWith(token)) return true;
        return hay.contains(" " + token);
    }

    private static boolean isArabic(char c) {
        return c >= 'ء' && c <= 'ي';
    }

    private static final String[] SUFFIXES = {"ات", "ون", "ين", "ه", "ي"};   // ات ون ين ه(ة) ي(ى)

    /**
     * Light Arabic stem of an ALREADY normalised word, so word forms meet: ترجمة · ترجمات · الترجمة · ترجمتها all give «ترجم».
     * Words that are not Arabic, or are too short to cut safely (under 4 letters), come back unchanged.
     */
    static String stem(String w) {
        if (w == null || w.length() < 4 || !isArabic(w.charAt(0))) return w;
        String s = w;
        if (s.startsWith("ال") && s.length() - 2 >= 3) s = s.substring(2);            // ال
        for (int i = 0; i < SUFFIXES.length; i++) {
            String x = SUFFIXES[i];
            if (s.endsWith(x) && s.length() - x.length() >= 3) {
                s = s.substring(0, s.length() - x.length());
                break;
            }
        }
        return s;
    }

    /** Score of one tool for the query tokens, or -1 when some word matches nowhere. */
    static int score(StudioMap.Tool t, String[] tokens) {
        if (tokens.length == 0) return -1;
        String title = norm(t.title);
        String keys = norm(t.keys);
        String sub = norm(t.sub);
        int total = 0;
        for (int i = 0; i < tokens.length; i++) {
            String w = stem(tokens[i]);
            int best;
            if (startsAWord(title, w)) best = W_TITLE_START;
            else if (title.contains(w)) best = W_TITLE_IN;
            else if (startsAWord(keys, w)) best = W_KEYS_START;
            else if (keys.contains(w)) best = W_KEYS_IN;
            else if (sub.contains(w)) best = W_SUB_IN;
            else return -1;
            total += best;
        }
        if (norm(t.title).equals(joinTokens(tokens))) total += W_EXACT;
        return total;
    }

    private static String joinTokens(String[] tokens) {          // String.join needs API 26, the app supports 21
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(tokens[i]);
        }
        return sb.toString();
    }

    /** The best {@code limit} tools for the query, best first. Empty query → empty list. */
    static List<StudioMap.Tool> tools(String query, int limit) {
        String[] tk = tokens(query);
        ArrayList<StudioMap.Tool> out = new ArrayList<StudioMap.Tool>();
        if (tk.length == 0 || limit <= 0) return out;
        int n = StudioMap.TOOLS.length;
        int[] sc = new int[n];
        Integer[] order = new Integer[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            sc[i] = score(StudioMap.TOOLS[i], tk);
            if (sc[i] >= 0) order[m++] = i;
        }
        final int[] scores = sc;
        java.util.Arrays.sort(order, 0, m, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                if (scores[a] != scores[b]) return scores[b] - scores[a];
                return a - b;                                  // stable: registry order
            }
        });
        for (int i = 0; i < m && out.size() < limit; i++) out.add(StudioMap.TOOLS[order[i]]);
        return out;
    }
}
