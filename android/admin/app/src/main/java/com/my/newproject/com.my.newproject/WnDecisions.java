package com.my.newproject;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * ما الجديد؟ — management decisions on discovery suggestions: «قائمة المراجعة», «معتمد», «مرفوض», «مُستورد».
 *
 * Nothing is imported or published by a decision: «معتمد» only unlocks the existing import (which validates again on the
 * server and refuses a title without a playable video — such an approved title waits as a draft in «معتمد» until its video
 * is linked). Decisions are kept on this phone and mirrored to the Worker ({@code /admin/discover/decision}) when the
 * Worker has that route; without it they stay local (the admin is told once).
 */
final class WnDecisions {

    private WnDecisions() {}

    static final String REVIEW = "review", APPROVED = "approved", REJECTED = "rejected", IMPORTED = "imported";
    private static final String PREFS = "cigram_wn_decisions";

    static String label(String d) {
        if (REVIEW.equals(d)) return "قيد المراجعة";
        if (APPROVED.equals(d)) return "معتمد";
        if (REJECTED.equals(d)) return "مرفوض";
        if (IMPORTED.equals(d)) return "مُستورد";
        return "";
    }

    static int color(String d) {
        if (APPROVED.equals(d)) return CgCfg.GOOD;
        if (REJECTED.equals(d)) return CgCfg.BAD;
        if (IMPORTED.equals(d)) return CgCfg.CYAN;
        return CgCfg.WARN;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject all(Context c) {
        try {
            return new JSONObject(prefs(c).getString("all", "{}"));
        } catch (Throwable e) {
            return new JSONObject();
        }
    }

    static String of(Context c, String key) {
        JSONObject o = all(c).optJSONObject(key);
        return o == null ? "" : o.optString("decision");
    }

    /** Records a decision for a card (the card's public data is kept so local lists can show it). */
    static void set(Context c, JSONObject card, String decision, String note) {
        String key = card.optString("key");
        if (key.length() == 0) return;
        JSONObject a = all(c);
        try {
            JSONObject brief = new JSONObject();
            for (String k : new String[]{"key", "type", "tmdb_id", "imdb_id", "title", "original_title", "year", "release_date", "poster", "backdrop",
                    "overview", "rating", "popularity", "genres", "language", "imported"}) {
                if (card.has(k)) brief.put(k, card.get(k));
            }
            JSONObject entry = new JSONObject().put("decision", decision).put("note", note == null ? "" : note)
                    .put("at", System.currentTimeMillis()).put("at_iso", SubCenterStore.nowIso()).put("card", brief);
            JSONObject prev = a.optJSONObject(key);
            JSONArray hist = prev == null ? new JSONArray() : prev.optJSONArray("history") == null ? new JSONArray() : prev.optJSONArray("history");
            hist.put(new JSONObject().put("decision", decision).put("at_iso", SubCenterStore.nowIso()).put("note", note == null ? "" : note));
            entry.put("history", hist);
            a.put(key, entry);
            prefs(c).edit().putString("all", a.toString()).apply();
        } catch (Throwable ignored) { }
        mirror(c, key, decision, note);
    }

    static void clear(Context c, String key) {
        JSONObject a = all(c);
        a.remove(key);
        prefs(c).edit().putString("all", a.toString()).apply();
        mirror(c, key, "", "");
    }

    /** Cards with this decision (newest first), for the local chips. */
    static List<JSONObject> cards(Context c, String decision) {
        JSONObject a = all(c);
        List<JSONObject> out = new ArrayList<JSONObject>();
        Iterator<String> it = a.keys();
        while (it.hasNext()) {
            JSONObject e = a.optJSONObject(it.next());
            if (e == null || !decision.equals(e.optString("decision"))) continue;
            JSONObject card = e.optJSONObject("card");
            if (card != null) {
                try { card.put("_decided_at", e.optLong("at")); } catch (Throwable ignored) { }
                out.add(card);
            }
        }
        java.util.Collections.sort(out, new java.util.Comparator<JSONObject>() {
            @Override public int compare(JSONObject x, JSONObject y) { return Long.compare(y.optLong("_decided_at"), x.optLong("_decided_at")); }
        });
        return out;
    }

    private static void mirror(final Context c, final String key, final String decision, final String note) {
        if (prefs(c).getBoolean("server_missing", false)) return;
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                try {
                    CxApi.post("/admin/discover/decision", new JSONObject().put("key", key).put("decision", decision).put("note", note));
                } catch (CxApi.Failure f) {
                    if ("ROUTE_MISSING".equals(f.code)) prefs(c).edit().putBoolean("server_missing", true).apply();
                } catch (Throwable ignored) { }
            }
        });
    }

    static boolean serverMissing(Context c) {
        return prefs(c).getBoolean("server_missing", false);
    }
}
