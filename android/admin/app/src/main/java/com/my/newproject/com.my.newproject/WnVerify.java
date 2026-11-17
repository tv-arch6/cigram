package com.my.newproject;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 19.6 — «ما الجديد» verifies the VidBolt link of every synced title BY ITSELF, on this phone, right after a sync (and
 * whenever a title appears on screen without a verdict). The owner never presses «فحص» and never acknowledges anything:
 * a poster carries «✓ شغال ومفحوص» (one tap adds it) or «✗ لا يعمل» with the reason.
 *
 * One title at a time (the check drives a hidden browser), newest first, results kept per title key ("movie:123").
 */
final class WnVerify {

    interface Listener { void changed(String key); }

    static final String STATE_OK = "ok";
    static final String STATE_BAD = "bad";
    static final String STATE_RUNNING = "running";
    static final String STATE_NONE = "";

    private static final String PREFS = "wn_verify_v1";
    private static final long KEEP_OK_MS = 6L * 60L * 60L * 1000L;
    private static final long KEEP_BAD_MS = 20L * 60L * 1000L;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private static final LinkedHashMap<String, JSONObject> QUEUE = new LinkedHashMap<>();
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static volatile String running = "";
    private static volatile boolean worker;
    private static Context APP;

    private WnVerify() { }

    // ------------------------------------------------------------------ the link to check for a card

    /** The VidBolt watch link the import will create for this card (movie, or the newest aired episode of a series). */
    static String linkFor(JSONObject card) {
        if (card == null) return "";
        String tmdb = String.valueOf(card.optLong("tmdb_id"));
        if ("0".equals(tmdb)) return "";
        if (!"series".equals(card.optString("type"))) return CgVidbolt.movie(tmdb);
        JSONObject le = card.optJSONObject("latest_episode");
        int s = le == null ? 1 : Math.max(1, le.optInt("season", 1));
        int e = le == null ? 1 : Math.max(1, le.optInt("episode", 1));
        return CgVidbolt.tv(tmdb, s, e);
    }

    // ------------------------------------------------------------------ verdicts

    static String state(Context c, String key) {
        if (key == null || key.length() == 0) return STATE_NONE;
        if (key.equals(running)) return STATE_RUNNING;
        JSONObject v = verdict(c, key);
        if (v == null) {
            synchronized (QUEUE) { if (QUEUE.containsKey(key)) return STATE_RUNNING; }
            return STATE_NONE;
        }
        return v.optBoolean("ok") ? STATE_OK : STATE_BAD;
    }

    /** {ok, label, reason, at, link, stream} or null when there is no fresh verdict. */
    static JSONObject verdict(Context c, String key) {
        if (c == null || key == null) return null;
        try {
            String raw = prefs(c).getString(key, null);
            if (raw == null) return null;
            JSONObject v = new JSONObject(raw);
            long age = System.currentTimeMillis() - v.optLong("at");
            if (age < 0 || age > (v.optBoolean("ok") ? KEEP_OK_MS : KEEP_BAD_MS)) return null;
            return v;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String label(Context c, String key) {
        String st = state(c, key);
        if (STATE_RUNNING.equals(st)) return "يُفحص على VidBolt…";
        JSONObject v = verdict(c, key);
        if (v == null) return "بانتظار الفحص التلقائي";
        return v.optString("label");
    }

    static int color(Context c, String key) {
        String st = state(c, key);
        if (STATE_OK.equals(st)) return CgCfg.GOOD;
        if (STATE_BAD.equals(st)) return CgCfg.BAD;
        return CgCfg.GRAY;
    }

    // ------------------------------------------------------------------ the runner

    static void listen(Listener l) { synchronized (LISTENERS) { if (!LISTENERS.contains(l)) LISTENERS.add(l); } }

    static void unlisten(Listener l) { synchronized (LISTENERS) { LISTENERS.remove(l); } }

    /** Queues cards without a fresh verdict (imported ones are skipped). Returns how many were queued. */
    static int enqueue(Context c, List<JSONObject> cards, boolean front) {
        if (c == null || cards == null) return 0;
        APP = c.getApplicationContext();
        int added = 0;
        synchronized (QUEUE) {
            LinkedHashMap<String, JSONObject> head = new LinkedHashMap<>();
            for (JSONObject card : cards) {
                if (card == null || card.optBoolean("imported", false) || "collection".equals(card.optString("type"))) continue;
                String key = card.optString("key");
                if (key.length() == 0 || verdict(c, key) != null || key.equals(running) || QUEUE.containsKey(key)) continue;
                if (front) head.put(key, card); else QUEUE.put(key, card);
                added++;
            }
            if (front && !head.isEmpty()) { head.putAll(QUEUE); QUEUE.clear(); QUEUE.putAll(head); }
        }
        if (added > 0) {
            for (String k : keysSnapshot()) fire(k);
            startWorker();
        }
        return added;
    }

    /** Re-checks one card now (the owner pressed «إعادة الفحص»). */
    static void recheck(Context c, JSONObject card) {
        if (c == null || card == null) return;
        prefs(c).edit().remove(card.optString("key")).apply();
        List<JSONObject> one = new ArrayList<>();
        one.add(card);
        enqueue(c, one, true);
    }

    static int pending() {
        synchronized (QUEUE) { return QUEUE.size() + (running.length() > 0 ? 1 : 0); }
    }

    private static List<String> keysSnapshot() {
        synchronized (QUEUE) { return new ArrayList<>(QUEUE.keySet()); }
    }

    private static void startWorker() {
        if (worker) return;
        worker = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    while (true) {
                        JSONObject card;
                        String key;
                        synchronized (QUEUE) {
                            if (QUEUE.isEmpty()) break;
                            key = QUEUE.keySet().iterator().next();
                            card = QUEUE.remove(key);
                        }
                        running = key;
                        fire(key);
                        JSONObject out = check(APP, card);
                        try { prefs(APP).edit().putString(key, out.toString()).apply(); } catch (Throwable ignored) { }
                        running = "";
                        fire(key);
                    }
                } finally {
                    running = "";
                    worker = false;
                }
            }
        }, "wn-verify");
        t.setDaemon(true);
        t.start();
    }

    /** Blocking full check of one card (background thread). */
    static JSONObject check(Context c, JSONObject card) {
        JSONObject out = new JSONObject();
        String link = linkFor(card);
        try {
            out.put("at", System.currentTimeMillis());
            out.put("link", link);
            if (link.length() == 0) {
                out.put("ok", false);
                out.put("reason", "no_video");
                out.put("label", "✗ " + CgStreamProbe.friendly("no_video"));
                return out;
            }
            CgStreamProbe.Result r = CgStreamProbe.verify(c, link, true);
            out.put("ok", r.ok);
            out.put("reason", r.reason);
            out.put("stream", r.stream);
            out.put("kind", r.kind);
            out.put("label", (r.ok ? "" : "✗ ") + r.label());
        } catch (Throwable ignored) { }
        return out;
    }

    private static void fire(final String key) {
        UI.post(new Runnable() {
            @Override public void run() {
                List<Listener> copy;
                synchronized (LISTENERS) { copy = new ArrayList<>(LISTENERS); }
                for (Listener l : copy) {
                    try { l.changed(key); } catch (Throwable ignored) { }
                }
            }
        });
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
