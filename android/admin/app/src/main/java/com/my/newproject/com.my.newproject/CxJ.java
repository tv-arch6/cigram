package com.my.newproject;

import org.json.JSONArray;
import org.json.JSONObject;

/** Tiny JSON helpers that never throw, so screens stay readable. */
final class CxJ {

    private CxJ() {}

    static JSONObject put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value == null ? "" : value);
        } catch (Exception ignored) { }
        return o;
    }

    static JSONObject obj() {
        return new JSONObject();
    }

    static JSONObject parse(String text) {
        try {
            if (text == null || text.trim().length() == 0) return new JSONObject();
            return new JSONObject(text);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static String s(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return "";
        return o.optString(key, "").trim();
    }

    static int n(JSONObject o, String key) {
        String v = s(o, key);
        if (v.length() == 0) return 0;
        try {
            return (int) Double.parseDouble(v);
        } catch (Exception e) {
            return 0;
        }
    }

    static JSONArray arr(JSONObject o, String key) {
        JSONArray a = o == null ? null : o.optJSONArray(key);
        return a == null ? new JSONArray() : a;
    }

    static JSONObject at(JSONArray a, int i) {
        JSONObject o = a == null ? null : a.optJSONObject(i);
        return o == null ? new JSONObject() : o;
    }

    static boolean isUrl(String v) {
        if (v == null) return false;
        String t = v.trim().toLowerCase(java.util.Locale.ROOT);
        return t.startsWith("http://") || t.startsWith("https://");
    }

    static String firstNonEmpty(String a, String b) {
        return a != null && a.length() > 0 ? a : (b == null ? "" : b);
    }
}
