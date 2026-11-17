package com.my.newproject;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 18.4 — the admin app's view of the catalogue, through the same protocol as the main app (see CgCatSync): the saved copy is a
 * crash-safe generation on disk, the Worker is asked "what changed?" instead of "send everything", and an empty / malformed /
 * failed answer never replaces the saved catalogue.
 *
 * Before this: every refresh downloaded the whole 5.7 MB /movies, an answer with no record list silently became an EMPTY catalogue
 * on screen and in the saved cache, and the saved cache was one ~6 MB SharedPreferences string.
 *
 * Admin writes are unchanged: every edit still goes to the Worker's targeted routes (/admin/item/update, /admin/item/create,
 * /admin/season/commit ... by content id, with compare-and-swap, a pre-write backup and the undo journal). This class only reads.
 */
final class CgCatalog {

    private CgCatalog() { }

    static final class Result {
        /** The catalogue records, or null when nothing usable exists (no saved copy and the Worker did not deliver one). */
        JSONArray items;
        /** The Worker could not be reached / refused: {@link #items} (if any) is the last saved copy. */
        boolean stale;
        String reason = "";
        String mode = "";
        int requests;
    }

    private static final CgCatSync.Pipeline IDENTITY = new CgCatSync.Pipeline() {
        @Override public String normalizeRecords(String docJson) { return docJson; }
        @Override public CgCatSync.Prepared prepare(String docJson) { return new CgCatSync.Prepared(docJson, ""); }
    };

    private static final Object LOCK = new Object();
    private static CgCatStore store;
    private static long parsedGen = -1L;
    private static JSONArray parsed;

    private static CgCatStore store(Context c) {
        synchronized (LOCK) {
            if (store == null) store = new CgCatStore(new File(c.getApplicationContext().getFilesDir(), "catalog_admin"));
            return store;
        }
    }

    /** Blocking; call off the UI thread. */
    static Result load(Activity a, boolean forceFull) {
        Result res = new Result();
        CgCatStore st = store(a);
        migrateLegacyCache(a, st);
        CgCatSync.Config cfg = new CgCatSync.Config();
        cfg.now = System.currentTimeMillis();
        cfg.forceFull = forceFull;
        CgCatSync.Outcome o = CgCatSync.syncOnce(st, new Transport(), IDENTITY, cfg);
        res.mode = o.toString();
        res.requests = o.requests;
        boolean ok = o.kind == CgCatSync.Kind.UP_TO_DATE || o.kind == CgCatSync.Kind.UPDATED;
        res.stale = !ok;
        res.reason = o.reason;
        res.items = itemsOf(st);                                   // the saved copy, whatever the outcome: never an empty replacement
        return res;
    }

    /** The saved catalogue records without any network (offline start, Worker down). Null when nothing is saved. */
    static JSONArray saved(Context c) {
        return itemsOf(store(c));
    }

    private static JSONArray itemsOf(CgCatStore st) {
        CgCatStore.Snapshot s = st.load();
        if (s == null || s.display.length() == 0) return null;
        synchronized (LOCK) {
            if (parsed != null && parsedGen == s.gen) return parsed;   // the same generation is parsed once per process
        }
        try {
            JSONArray list = CgCatDelta.open(s.display).list;
            synchronized (LOCK) { parsed = list; parsedGen = s.gen; }
            return list;
        } catch (Throwable e) {
            return null;
        }
    }

    /** The old cache kept every record inside one SharedPreferences string: import it once (verified), then drop it from there. */
    private static void migrateLegacyCache(Context c, CgCatStore st) {
        try {
            if (st.hasSnapshot()) return;
            SharedPreferences sp = c.getApplicationContext().getSharedPreferences(CgCfg.PREFS, Context.MODE_PRIVATE);
            String raw = sp.getString(CgData.CACHE_KEY, "");
            if (raw == null || raw.length() == 0) return;
            JSONObject o = new JSONObject(raw);
            JSONArray items = o.optJSONArray("items");
            if (items == null || items.length() == 0) return;
            JSONObject doc = new JSONObject();
            doc.put("ok", true);
            doc.put("movies", items);
            String json = doc.toString();
            int records = CgCatDelta.countRecords(json);
            if (records < 1) return;
            Map<String, Long> l = new HashMap<String, Long>();
            l.put(CgCatSync.L_RECORDS, (long) records);
            l.put(CgCatSync.L_REV, 0L);
            if (!st.commit(json, "", new HashMap<String, String>(), l).ok) return;
            CgCatStore.Snapshot back = st.load();
            if (back == null || CgCatDelta.countRecords(back.display) != records) return;
            o.remove("items");                                      // verified: shrink the preferences entry to meta + sections
            sp.edit().putString(CgData.CACHE_KEY, o.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** HTTP for the manager: the public GET routes of the admin Worker (no token needed, none is sent). */
    private static final class Transport implements CgCatSync.Transport {
        @Override public CgCatSync.Reply get(String path, String etag) throws IOException {
            return one(path, etag);
        }

        @Override public CgCatSync.Reply getFull(String etag) throws IOException {
            return one("/movies", etag);
        }

        private CgCatSync.Reply one(String path, String etag) throws IOException {
            String[][] h = etag != null && etag.length() > 0 ? new String[][]{{"If-None-Match", etag}} : null;
            CgHttp.Res r = CgHttp.call("GET", CgCfg.API + path, null, false, 25000, h);
            if (r.error != null) throw new IOException(r.error.getClass().getSimpleName());
            if (r.code == 304) return new CgCatSync.Reply(304, r.etag.length() > 0 ? r.etag : etag, "");
            if (r.code != 200) return new CgCatSync.Reply(r.code, r.etag, "");
            return new CgCatSync.Reply(200, r.etag, r.body);
        }
    }
}
