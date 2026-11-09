package com.my.newproject;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Local log of additions that the Worker does not track (channels live in a separate Worker). */
final class CxRecent {

    private CxRecent() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("cx_recent_v1", Context.MODE_PRIVATE);
    }

    static void push(Context c, String kind, String title, String image, String extra) {
        try {
            JSONArray old = new JSONArray(sp(c).getString(kind, "[]"));
            JSONArray out = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("kind", kind);
            o.put("title", title == null ? "" : title);
            o.put("poster", image == null ? "" : image);
            o.put("extra", extra == null ? "" : extra);
            o.put("at_ms", System.currentTimeMillis());
            out.put(o);
            for (int i = 0; i < old.length() && out.length() < 15; i++) out.put(old.get(i));
            sp(c).edit().putString(kind, out.toString()).apply();
        } catch (Exception ignored) { }
    }

    static JSONArray list(Context c, String kind) {
        try {
            return new JSONArray(sp(c).getString(kind, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }
}
