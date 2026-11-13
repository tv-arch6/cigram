package com.Cigram.vid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;

/**
 * v10.62: البحث الذكي — يعمل فقط عندما لا يجد البحث العادي أي نتيجة.
 *
 *  1) يتحمل الأخطاء الإملائية:  «انسبشن» ← «انسيبشن»، «braking bad» ← «Breaking Bad».
 *  2) بين العربي والإنجليزي:     «انسبشن» ← Inception، «الجوكر» ← Joker، «sarab» ← «سراب».
 *     نحوّل الكلمتين إلى «هيكل صوتي» (حروف ساكنة متقاربة النطق) ونقارن بالمسافة بين الحروف.
 *  3) يحترم نفس الفلاتر (النوع / السنة / التصنيف / أقل تقييم).
 *
 * الاستدعاء من SearchXmlActivity عند found == 0:
 *   CigramSmartSearch.fuzzy(items, query, cgType[0], year, category, rating)
 */
public final class CigramSmartSearch {

    private static final int MAX_RESULTS = 12;
    private static final String[] TITLE_KEYS = {"title", "series_title", "name", "title_ar", "title_en",
            "original_title", "original_name", "aliases", "alternative_titles", "alt_titles"};

    private CigramSmartSearch() { }

    public static ArrayList<JSONObject> fuzzy(JSONArray items, String query, String type, String year,
                                              String category, float minRating) {
        final ArrayList<JSONObject> out = new ArrayList<JSONObject>();
        if (items == null || query == null) return out;
        String q = normalize(query);
        if (q.length() < 2) return out;
        String[] qTokens = tokens(q);
        if (qTokens.length == 0) return out;
        boolean qArabic = hasArabic(q);
        String qSkel = skeleton(q);

        final IdentityHashMap<JSONObject, Integer> scores = new IdentityHashMap<JSONObject, Integer>();
        HashSet<String> dedupe = new HashSet<String>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String itemType = text(item, "type");
            if (type != null && !"all".equals(type) && !type.equals(itemType)) continue;
            if (!CigramSearchFilter.yearMatches(year, text(item, "year"))) continue;
            if (category != null && !"all".equals(category)
                    && !normalize(text(item, "category")).contains(normalize(category))) continue;
            if (number(text(item, "imdb")) < minRating) continue;

            int best = 0;
            for (String key : TITLE_KEYS) {
                String value = text(item, key);
                if (value.length() == 0) continue;
                String n = normalize(value);
                if (n.length() == 0) continue;
                int s = similarity(qTokens, n);
                // across scripts: Arabic query vs Latin title (and the other way round)
                if (qArabic != hasArabic(n)) s = Math.max(s, skeletonScore(qSkel, skeleton(n)));
                if (s > best) best = s;
            }
            if (best < 50) continue;
            String dup = itemType + "|" + normalize(text(item, "series_title", "title")) + "|" + text(item, "year");
            if (!dedupe.add(dup)) continue;
            try { best += (int) Math.min(9f, number(text(item, "imdb"))); } catch (Throwable ignored) { }
            scores.put(item, best);
            out.add(item);
        }
        Collections.sort(out, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return scores.get(b) - scores.get(a);
            }
        });
        while (out.size() > MAX_RESULTS) out.remove(out.size() - 1);
        return out;
    }

    // ------------------------------------------------------------------ scoring

    /** 0..100: every query word must find a close word in the title (one may be missing in long queries). */
    static int similarity(String[] qTokens, String title) {
        String[] tTokens = tokens(title);
        if (tTokens.length == 0) return 0;
        int cost = 0;
        int missing = 0;
        for (String qt : qTokens) {
            int best = Integer.MAX_VALUE;
            for (String tt : tTokens) {
                if (tt.equals(qt) || (qt.length() >= 3 && tt.startsWith(qt))) { best = 0; break; }
                int d = distance(qt, tt, allowed(qt) + 1);
                if (d < best) best = d;
            }
            if (best > allowed(qt)) {
                missing++;
                if (qTokens.length < 3 || missing > 1) return 0;
                cost += 25;
            } else {
                cost += best * 12;
            }
        }
        // the whole query glued together vs the whole title («breakingbad»)
        int glued = distance(q(qTokens), q(tTokens), 3);
        int s = 100 - cost;
        if (glued <= 1) s = Math.max(s, 92 - glued * 10);
        return Math.max(0, s);
    }

    private static String q(String[] tokens) {
        StringBuilder b = new StringBuilder();
        for (String t : tokens) b.append(t);
        return b.toString();
    }

    static int skeletonScore(String a, String b) {
        if (a.length() < 2 || b.length() < 2) return 0;
        int allowed = a.length() <= 3 ? 0 : a.length() <= 6 ? 1 : 2;
        int d = distance(a, b, allowed + 1);
        if (d <= allowed) return 88 - d * 12;
        // the title is longer («inception» vs a query «inceptn» is fine, but also «the dark knight» vs «dark knight»)
        if (b.length() > a.length() && b.contains(a) && a.length() >= 4) return 70;
        return 0;
    }

    private static int allowed(String token) {
        int n = token.length();
        if (n <= 3) return 0;
        if (n <= 5) return 1;
        if (n <= 8) return 2;
        return 3;
    }

    /** Damerau-Levenshtein (optimal string alignment) with an early exit above max. */
    static int distance(String a, String b, int max) {
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > max) return max + 1;
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            int rowMin = Integer.MAX_VALUE;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                }
                d[i][j] = v;
                if (v < rowMin) rowMin = v;
            }
            if (rowMin > max) return max + 1;
        }
        return d[n][m];
    }

    // ------------------------------------------------------------------ phonetic skeleton

    /**
     * Arabic and Latin letters folded to the same small consonant alphabet, vowels dropped:
     *   «انسبشن» → nsbsn ,  "inception" → nsbsn
     *   «جوكر»   → jkr   ,  "joker"     → jkr
     */
    static String skeleton(String s) {
        String t = s.toLowerCase(java.util.Locale.ROOT);
        // Latin digraphs first
        t = t.replace("ph", "f").replace("th", "t").replace("sh", "s").replace("ch", "s").replace("kh", "k")
                .replace("gh", "j").replace("ck", "k").replace("qu", "k").replace("tion", "sn").replace("sion", "sn")
                .replace("x", "ks");
        // drop a leading Arabic article ال / Latin "the " / "al-"
        if (t.startsWith("ال") && t.length() > 3) t = t.substring(2);
        if (t.startsWith("the ")) t = t.substring(4);
        if (t.startsWith("al ")) t = t.substring(3);
        StringBuilder b = new StringBuilder();
        char last = 0;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            boolean nextIsVowel = i + 1 < t.length() && "eiy".indexOf(t.charAt(i + 1)) >= 0;
            char k = fold(ch, nextIsVowel);
            if (k == 0 || k == last) continue;          // drop vowels / doubled letters
            b.append(k);
            last = k;
        }
        return b.toString();
    }

    private static char fold(char c, boolean softNext) {
        switch (c) {
            // Latin
            case 'b': case 'p': return 'b';
            case 'c': return softNext ? 's' : 'k';
            case 'd': return 'd';
            case 'f': case 'v': return 'f';
            case 'g': case 'j': return 'j';
            case 'k': case 'q': return 'k';
            case 'l': return 'l';
            case 'm': return 'm';
            case 'n': return 'n';
            case 'r': return 'r';
            case 's': case 'z': return 's';
            case 't': return 't';
            // Arabic
            case 'ب': case 'پ': return 'b';
            case 'ت': case 'ط': case 'ث': return 't';
            case 'ج': case 'گ': case 'غ': return 'j';
            case 'خ': case 'ك': case 'ق': return 'k';
            case 'د': case 'ض': return 'd';
            case 'ذ': case 'ز': case 'ظ': case 'س': case 'ص': case 'ش': case 'چ': return 's';
            case 'ر': return 'r';
            case 'ف': case 'ڤ': return 'f';
            case 'ل': return 'l';
            case 'م': return 'm';
            case 'ن': return 'n';
            default:
                // vowels, h, w, y, ع, ح, ه, ا, و, ي, ء, digits and spaces are dropped
                if (c >= '0' && c <= '9') return c;
                return 0;
        }
    }

    // ------------------------------------------------------------------ text helpers (same folding as the search screen)

    private static final java.util.regex.Pattern RX_MARKS = java.util.regex.Pattern.compile("\\p{M}+");
    private static final java.util.regex.Pattern RX_NOT_WORD = java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final java.util.regex.Pattern RX_SPACES = java.util.regex.Pattern.compile("\\s+");
    /** Titles / categories / queries are folded again and again (every keystroke, every item): remember the short ones. */
    private static final android.util.LruCache<String, String> FOLDED = new android.util.LruCache<String, String>(4096);
    private static final int FOLD_CACHE_MAX_CHARS = 120;

    /**
     * The one text folding used by every search path (search screen, home filters, fuzzy fallback): case, diacritics,
     * tatweel, alef / yeh / teh-marbuta / hamza forms, Arabic-Indic and Persian digits, punctuation. The patterns are
     * compiled once (String.replaceAll compiled three of them on every call) and short results are cached.
     */
    static String normalize(String s) {
        if (s == null || s.length() == 0) return "";
        final boolean cacheable = s.length() <= FOLD_CACHE_MAX_CHARS;
        if (cacheable) {
            String hit = FOLDED.get(s);
            if (hit != null) return hit;
        }
        String t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD);
        t = RX_MARKS.matcher(t).replaceAll("").replace("ـ", "").toLowerCase(java.util.Locale.ROOT);
        char[] c = t.toCharArray();
        for (int i = 0; i < c.length; i++) {
            switch (c[i]) {
                case 'أ': case 'إ': case 'آ': case 'ٱ': c[i] = 'ا'; break;
                case 'ى': case 'ئ': case 'ی': c[i] = 'ي'; break;     // alef maqsura, hamza-on-yeh, Persian yeh
                case 'ة': c[i] = 'ه'; break;
                case 'ؤ': c[i] = 'و'; break;
                case 'ک': c[i] = 'ك'; break;                          // Persian keheh
                default:
                    if (c[i] >= '٠' && c[i] <= '٩') c[i] = (char) ('0' + (c[i] - '٠'));        // Arabic-Indic digits
                    else if (c[i] >= '۰' && c[i] <= '۹') c[i] = (char) ('0' + (c[i] - '۰'));   // extended (Persian) digits
            }
        }
        t = RX_NOT_WORD.matcher(new String(c)).replaceAll(" ").trim();
        t = RX_SPACES.matcher(t).replaceAll(" ");
        if (cacheable) FOLDED.put(s, t);
        return t;
    }

    private static String[] tokens(String normalized) {
        if (normalized.length() == 0) return new String[0];
        return normalized.split(" ");
    }

    private static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0600 && c <= 0x06FF) return true;
        }
        return false;
    }

    private static String text(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String key : keys) {
            Object v = o.opt(key);
            if (v != null && v != JSONObject.NULL && String.valueOf(v).trim().length() > 0) return String.valueOf(v).trim();
        }
        return "";
    }

    private static float number(String s) {
        try { return Float.parseFloat(s.replace(',', '.')); } catch (Throwable e) { return 0f; }
    }
}
