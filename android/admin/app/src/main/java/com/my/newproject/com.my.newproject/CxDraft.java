package com.my.newproject;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;

/**
 * Local drafts (SharedPreferences). One entry per draft id; auto-saved while typing, resumed on demand,
 * removed after a successful publish. Nothing is sent to the server.
 */
final class CxDraft {

    private CxDraft() {}

    static final String PREFS = "cx_drafts_v1";

    static String newId() {
        return Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void save(Context c, String id, String kind, String title, JSONObject data, int step) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("kind", kind);
            o.put("title", title == null ? "" : title);
            o.put("updated", System.currentTimeMillis());
            o.put("step", step);
            o.put("data", data == null ? new JSONObject() : data);
            sp(c).edit().putString("d_" + id, o.toString()).apply();
        } catch (Exception ignored) { }
    }

    static JSONObject load(Context c, String id) {
        if (id == null || id.length() == 0) return null;
        String raw = sp(c).getString("d_" + id, null);
        if (raw == null) return null;
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    static void delete(Context c, String id) {
        if (id == null || id.length() == 0) return;
        sp(c).edit().remove("d_" + id).apply();
    }

    /** Newest first. kind == null returns every draft. */
    static ArrayList<JSONObject> list(Context c, String kind) {
        ArrayList<JSONObject> out = new ArrayList<JSONObject>();
        Map<String, ?> all = sp(c).getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (!e.getKey().startsWith("d_") || !(e.getValue() instanceof String)) continue;
            try {
                JSONObject o = new JSONObject((String) e.getValue());
                if (kind == null || kind.equals(o.optString("kind"))) out.add(o);
            } catch (Exception ignored) { }
        }
        Collections.sort(out, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                long x = a.optLong("updated");
                long y = b.optLong("updated");
                return x < y ? 1 : x > y ? -1 : 0;
            }
        });
        return out;
    }

    static JSONObject latest(Context c, String kind) {
        ArrayList<JSONObject> l = list(c, kind);
        return l.isEmpty() ? null : l.get(0);
    }

    static int count(Context c) {
        return list(c, null).size();
    }

    static String kindLabel(String kind) {
        if ("movie".equals(kind)) return "فيلم";
        if ("series".equals(kind)) return "مسلسل";
        if ("episode".equals(kind)) return "حلقة";
        if ("season".equals(kind)) return "موسم كامل";
        if ("carousel".equals(kind)) return "كاروسيل";
        if ("channel".equals(kind)) return "قناة";
        return kind;
    }

    /** Debounced auto-save. The screen supplies what to store; nothing is written while the form is still empty. */
    interface Source {
        boolean worthSaving();

        JSONObject collect();

        String title();

        int step();
    }

    static final class Auto {
        private final Context ctx;
        private final String kind;
        private final Source src;
        private final Handler h = new Handler(Looper.getMainLooper());
        private String id;
        private boolean paused;
        private final Runnable job = new Runnable() {
            @Override
            public void run() {
                flush();
            }
        };

        Auto(Context ctx, String kind, String id, Source src) {
            this.ctx = ctx;
            this.kind = kind;
            this.id = id == null || id.length() == 0 ? newId() : id;
            this.src = src;
        }

        String id() {
            return id;
        }

        void touch() {
            if (paused) return;
            h.removeCallbacks(job);
            h.postDelayed(job, 1200);
        }

        void flush() {
            h.removeCallbacks(job);
            if (paused) return;
            try {
                if (src.worthSaving()) save(ctx, id, kind, src.title(), src.collect(), src.step());
            } catch (Exception ignored) { }
        }

        /** After publishing: drop the draft and stop saving. */
        void finish() {
            paused = true;
            h.removeCallbacks(job);
            delete(ctx, id);
        }
    }

    /** JSON array of drafts as a short summary string for banners. */
    static String summary(Context c) {
        JSONArray a = new JSONArray();
        ArrayList<JSONObject> l = list(c, null);
        for (int i = 0; i < l.size() && i < 3; i++) a.put(l.get(i).optString("title"));
        return a.toString();
    }
}
