package com.Cigram.vid;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * يصلح أسماء المسلسلات في الكتالوج قبل أن تصل لأي شاشة.
 *
 * المشكلة: كل شاشات التطبيق (الرئيسية، البحث، الأقسام، الفهرس المحفوظ) تفضّل الحقل
 * series_title على title لسجل المسلسل، وتكتبه فوق title. فإذا وصل series_title من لوحة الإدارة
 * تالفاً (ترميز عربي مكسور «Ø§Ù„…»، أو %D8%A7، أو اسم ملف/معرّف مولّد) يظهر المسلسل باسم غير
 * مفهوم ويختفي من البحث بالاسم الصحيح، لأن title الأصلي يُستبدل ويضيع.
 *
 * الحل (مرة واحدة عند استلام الكتالوج):
 * 1. إصلاح الترميز المكسور في حقول الأسماء.
 * 2. لسجل المسلسل: إن كان series_title تالفاً ويوجد اسم مقروء (title/name/...) يُستخدم المقروء.
 * 3. حلقات المسلسل ترث الاسم المصلح.
 * 4. كل الأسماء المقروءة تُضاف إلى alt_titles حتى يجدها البحث بأي منها.
 */
public final class CigramTitles {

    private static final String[] NAME_KEYS = {
            "series_title", "title", "name", "title_ar", "title_en", "original_title", "original_name"
    };
    private static final String[] TEXT_KEYS = {
            "series_title", "title", "name", "title_ar", "title_en", "original_title", "original_name",
            "episode_title", "description", "overview", "category", "genre"
    };
    private static final String[] NESTED_KEYS = {"series_episodes_json", "episodes", "seasons"};
    private static final String[] COLLECTION_KEYS = {
            "movies", "series", "items", "data", "results", "content", "sections", "catalog"
    };

    private static final Pattern PERCENT = Pattern.compile("(%[0-9A-Fa-f]{2}){2,}");
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9A-Fa-f]{4})");
    private static final Pattern HTML_ENTITY = Pattern.compile("&#(x?)([0-9A-Fa-f]+);");
    /** حرف لاتيني موسع يليه بايت استمرار UTF-8 = نص UTF-8 قُرئ كـ Latin-1/Windows-1252. */
    private static final Pattern MOJIBAKE = Pattern.compile(
            "[\\u00C2-\\u00F4][\\u0080-\\u00BF\\u0152\\u0153\\u0160\\u0161\\u0178\\u017D\\u017E\\u0192"
                    + "\\u02C6\\u02DC\\u2013\\u2014\\u2018-\\u201E\\u2020-\\u2022\\u2026\\u2030\\u2039\\u203A"
                    + "\\u20AC\\u2122]");
    private static final Pattern MACHINE_NAME = Pattern.compile(
            "(?i)^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                    + "|[0-9a-f]{16,}"
                    + "|(?:series|serie|show|tv|s|id|item|content|upload|file)[_\\-.]?[0-9]{3,}[0-9a-z_\\-.]*"
                    + "|[0-9]{6,}"
                    + "|.*\\.(?:mp4|mkv|m3u8|avi|mov|webm|ts|jpg|jpeg|png|webp)"
                    + "|[a-z0-9]+(?:[._\\-][a-z0-9]+)*[._\\-]s[0-9]{1,2}e[0-9]{1,3}(?:[._\\-].*)?)$");

    private CigramTitles() { }

    // ------------------------------------------------------------------ catalog

    /**
     * يزيل علامة ترتيب البايت (BOM) وأي فراغ بادئ قبل أول رمز JSON.
     * String#trim() لا يحذف U+FEFF، فكان الرد المسبوق بـ BOM يُرفض ككتالوج غير صالح رغم سلامة JSON.
     */
    static String stripBom(String raw) {
        if (raw == null) return "";
        int i = 0;
        int n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            if (c == '﻿' || c == '￾' || c == '​' || c <= ' ') { i++; continue; }
            break;
        }
        return i == 0 ? raw : raw.substring(i);
    }

    /** يعيد نسخة مصلحة من JSON الكتالوج، أو النص الأصلي كما هو إن لم يتغير شيء أو تعذر التحليل. */
    public static String normalizeCatalog(String raw) {
        if (raw == null) return "";
        String cleaned = stripBom(raw);
        String trimmed = cleaned.trim();
        if (trimmed.length() == 0) return raw;
        try {
            Object root = new JSONTokener(trimmed).nextValue();
            boolean[] changed = {false};
            Object fixed = walk(root, null, changed, 0);
            if (!changed[0]) return cleaned;
            return String.valueOf(fixed);
        } catch (Throwable error) {
            android.util.Log.w("CigramTitles", "catalog normalize skipped", error);
            return cleaned;
        }
    }

    private static Object walk(Object node, String parentSeriesTitle, boolean[] changed, int depth) throws Exception {
        if (node == null || depth > 14) return node;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                Object child = a.opt(i);
                Object fixed = walk(child, parentSeriesTitle, changed, depth + 1);
                if (fixed != child) a.put(i, fixed);
            }
            return a;
        }
        if (!(node instanceof JSONObject)) return node;
        JSONObject o = (JSONObject) node;

        for (String key : TEXT_KEYS) {
            Object v = o.opt(key);
            if (!(v instanceof String)) continue;
            String repaired = repair((String) v);
            if (!repaired.equals(v)) {
                o.put(key, repaired);
                changed[0] = true;
            }
        }

        boolean container = isSeriesContainer(o);
        String seriesName = parentSeriesTitle;
        if (container) {
            seriesName = bestSeriesTitle(o);
            if (seriesName.length() > 0) {
                String current = text(o, "series_title");
                if (current.length() > 0 && !seriesName.equals(current)) {
                    o.put("series_title", seriesName);
                    changed[0] = true;
                }
                String title = text(o, "title");
                if (title.length() == 0 || looksBroken(title)) {
                    o.put("title", seriesName);
                    changed[0] = true;
                }
                if (addAltTitles(o)) changed[0] = true;
            }
        } else if (parentSeriesTitle != null && parentSeriesTitle.length() > 0) {
            String current = text(o, "series_title");
            if (current.length() > 0 && looksBroken(current)) {
                o.put("series_title", parentSeriesTitle);
                changed[0] = true;
            }
        }

        for (String key : NESTED_KEYS) {
            Object child = o.opt(key);
            if (child == null) continue;
            if (child instanceof String) {
                String s = ((String) child).trim();
                if (!(s.startsWith("[") || s.startsWith("{"))) continue;
                boolean[] inner = {false};
                Object parsed = walk(new JSONTokener(s).nextValue(), seriesName, inner, depth + 1);
                if (inner[0]) {
                    o.put(key, String.valueOf(parsed));
                    changed[0] = true;
                }
            } else {
                walk(child, seriesName, changed, depth + 1);
            }
        }

        for (String key : COLLECTION_KEYS) {
            Object child = o.opt(key);
            if (child == null) continue;
            if (child instanceof String) {
                String s = ((String) child).trim();
                if (!(s.startsWith("[") || s.startsWith("{"))) continue;
                boolean[] inner = {false};
                Object parsed = walk(new JSONTokener(s).nextValue(), null, inner, depth + 1);
                if (inner[0]) {
                    o.put(key, String.valueOf(parsed));
                    changed[0] = true;
                }
            } else {
                walk(child, null, changed, depth + 1);
            }
        }
        return o;
    }

    /** سجل المسلسل نفسه (وليس حلقة مفردة). */
    private static boolean isSeriesContainer(JSONObject o) {
        if (o.has("seasons") || o.has("episodes") || o.has("series_episodes_json")) return true;
        String type = text(o, "type", "content_type", "media_type").toLowerCase(Locale.ROOT);
        boolean series = type.equals("series") || type.equals("tv") || type.equals("show") || type.contains("مسلسل");
        return series && text(o, "episode", "episode_number").length() == 0;
    }

    /** أول اسم مقروء: series_title إن كان سليماً، وإلا title/name/... */
    public static String bestSeriesTitle(JSONObject o) {
        String fallback = "";
        for (String key : NAME_KEYS) {
            String v = repair(text(o, key));
            if (v.length() == 0) continue;
            if (fallback.length() == 0) fallback = v;
            if (!looksBroken(v)) return v;
        }
        return fallback;
    }

    private static boolean addAltTitles(JSONObject o) throws Exception {
        Object raw = o.opt("alt_titles");
        if (raw != null && raw != JSONObject.NULL && !(raw instanceof String)) return false;
        String existing = text(o, "alt_titles");
        LinkedHashSet<String> found = new LinkedHashSet<String>();
        for (String key : NAME_KEYS) {
            String v = repair(text(o, key));
            if (v.length() > 0 && !looksBroken(v)) found.add(v);
        }
        if (found.size() < 2) return false;
        LinkedHashSet<String> names = new LinkedHashSet<String>();
        if (existing.length() > 0) names.add(existing);
        names.addAll(found);
        StringBuilder out = new StringBuilder();
        for (String n : names) {
            if (out.length() > 0) out.append(" | ");
            out.append(n);
        }
        String joined = out.toString();
        if (joined.equals(existing)) return false;
        o.put("alt_titles", joined);
        return true;
    }

    // -------------------------------------------------------------------- text

    /** يصلح أشهر صور التلف: ترميز مكسور، ‎%XX، ‎\\uXXXX، ‎&#NNNN; */
    public static String repair(String value) {
        if (value == null) return "";
        String s = value.trim();
        if (s.length() == 0) return s;

        if (s.indexOf("\\u") >= 0) {
            Matcher m = UNICODE_ESCAPE.matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(
                        String.valueOf((char) Integer.parseInt(m.group(1), 16))));
            }
            m.appendTail(sb);
            s = sb.toString();
        }

        if (s.indexOf("&#") >= 0) {
            Matcher m = HTML_ENTITY.matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                try {
                    int cp = Integer.parseInt(m.group(2), m.group(1).length() > 0 ? 16 : 10);
                    m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
                } catch (Throwable ignored) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                }
            }
            m.appendTail(sb);
            s = sb.toString();
        }

        if (PERCENT.matcher(s).find()) {
            try {
                String decoded = java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
                if (decoded.indexOf('�') < 0) s = decoded;
            } catch (Throwable ignored) { }
        }

        // قد يكون الترميز مكسوراً مرتين.
        for (int i = 0; i < 2 && MOJIBAKE.matcher(s).find(); i++) {
            String fixed = undoMojibake(s);
            if (fixed == null || fixed.equals(s)) break;
            s = fixed;
        }
        return s.trim();
    }

    /** هل يبدو الاسم غير صالح للعرض/البحث؟ */
    public static boolean looksBroken(String value) {
        if (value == null) return true;
        String s = value.trim();
        if (s.length() == 0) return true;
        if (s.indexOf('�') >= 0) return true;
        if (MOJIBAKE.matcher(s).find()) return true;
        if (PERCENT.matcher(s).find()) return true;
        if (s.contains("\\u") || s.contains("&#")) return true;
        boolean letter = false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) { letter = true; break; }
        }
        // أسماء رقمية قصيرة حقيقية مثل «24» و«1899» ليست تالفة.
        if (!letter) return !s.matches("[0-9]{1,5}");
        return MACHINE_NAME.matcher(s).matches();
    }

    private static String undoMojibake(String s) {
        Charset cp1252;
        try {
            cp1252 = Charset.forName("windows-1252");
        } catch (Throwable error) {
            cp1252 = Charset.forName("ISO-8859-1");
        }
        byte[] bytes = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0xFF) {
                bytes[i] = (byte) c;
                continue;
            }
            byte[] b = String.valueOf(c).getBytes(cp1252);
            if (b.length != 1 || (b[0] == '?' && c != '?')) return null;
            bytes[i] = b[0];
        }
        CharsetDecoder decoder = Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer out = decoder.decode(ByteBuffer.wrap(bytes));
            return out.toString();
        } catch (CharacterCodingException error) {
            return null;
        }
    }

    private static String text(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String key : keys) {
            Object v = o.opt(key);
            if (v == null || v == JSONObject.NULL) continue;
            String s = String.valueOf(v).trim();
            if (s.length() > 0) return s;
        }
        return "";
    }
}
