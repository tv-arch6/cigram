package com.Cigram.vid;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Does the heavy part of Home's catalogue handling OFF the main thread: normalising the titles,
 * flattening / de-duplicating the JSON and turning every item into a map. All of that used to run
 * inside the network callback on the main thread (with a new Gson per item), which froze the first
 * screen for seconds on a big catalogue and ended in "Cigram isn't responding".
 *
 * Flow: MainActivity's callback asks {@link #isReady}; if not, {@link #prepareAsync} works in the
 * background and calls the callback again on the main thread, which then {@link #take}s the result.
 */
final class HomePrep {

    static final class Result {
        final JSONArray items;
        final ArrayList<HashMap<String, Object>> maps;
        Result(JSONArray items, ArrayList<HashMap<String, Object>> maps) {
            this.items = items;
            this.maps = maps;
        }
    }

    /** Marks a response whose background preparation failed: the callback then falls back to the old path. */
    private static final Object FAILED = new Object();

    // Keyed by CONTENT (length:hash), not by String identity. Home gets the same catalogue from many
    // sources (own request, early download, tab refresh, watchdogs, cache), each as a NEW String object;
    // identity keys + "newest wins" made every new delivery cancel the parse still running for the one
    // before it, so with a multi-MB catalogue no result ever reached the rows (they stayed empty).
    private static final java.util.LinkedHashMap<String, Object> DONE = new java.util.LinkedHashMap<String, Object>();
    private static final java.util.HashMap<String, ArrayList<Runnable>> PENDING =
            new java.util.HashMap<String, ArrayList<Runnable>>();
    private static String snapKey = "";
    private static final int KEEP = 3;
    private static int latest;

    private static String key(String raw) {
        return raw == null ? "" : raw.length() + ":" + raw.hashCode();
    }

    /** Keeps the last few prepared catalogues (never the start-up snapshot marker). Caller holds the lock. */
    private static void evict() {
        java.util.Iterator<String> it = DONE.keySet().iterator();
        while (DONE.size() > KEEP && it.hasNext()) {
            String k = it.next();
            if (!k.equals(snapKey)) it.remove();
        }
    }
    private static ExecutorService executor;

    private HomePrep() { }

    private static synchronized ExecutorService executor() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "cigram-home-prep");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        return executor;
    }

    // ---- v10.80: a small snapshot of what Home shows (the first titles of both rows, already sorted) is kept
    // between runs. It is read and drawn at once at start-up, before the multi-MB catalogue has been read,
    // parsed and sorted, which is what kept the two rows empty for several seconds.
    static final String SNAPSHOT = "cigram-home-snapshot:";
    private static final String SNAP_PREFS = "cigram_home_snapshot";

    static boolean isSnapshot(String raw) {
        return raw != null && raw.startsWith(SNAPSHOT);
    }

    private static String versionOf(android.content.Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    /** Reads the snapshot (call off the main thread). Returns the marker string to hand to Home, or null. */
    static String loadSnapshot(android.content.Context context) {
        try {
            android.content.Context app = context.getApplicationContext();
            android.content.SharedPreferences p = app.getSharedPreferences(SNAP_PREFS, android.content.Context.MODE_PRIVATE);
            String json = p.getString("items", "");
            if (json == null || json.length() < 10 || !versionOf(app).equals(p.getString("version", ""))) return null;
            JSONArray items = new JSONArray(json);
            if (items.length() == 0) return null;
            com.google.gson.Gson gson = new com.google.gson.Gson();
            ArrayList<HashMap<String, Object>> maps = new ArrayList<HashMap<String, Object>>(items.length());
            for (int i = 0; i < items.length(); i++) {
                @SuppressWarnings("unchecked")
                HashMap<String, Object> entry = (HashMap<String, Object>) gson.fromJson(items.getJSONObject(i).toString(), HashMap.class);
                maps.add(entry);
            }
            String raw = new String(SNAPSHOT + items.length());
            synchronized (HomePrep.class) {
                snapKey = key(raw);
                DONE.put(snapKey, new Result(items, maps));
            }
            return raw;
        } catch (Throwable t) {
            android.util.Log.w("CigramHome", "snapshot unreadable", t);
            return null;
        }
    }

    /** Keeps what Home shows for the next start: up to {@code perType} titles of each kind with all their entries. */
    static void saveSnapshot(final android.content.Context context, final java.util.List<HashMap<String, Object>> sorted,
                             final java.util.Set<String> claimed, final HomeCatalog catalog, final int perType) {
        final ArrayList<HashMap<String, Object>> copy = new ArrayList<HashMap<String, Object>>(sorted);
        executor().execute(new Runnable() {
            @Override public void run() {
                try {
                    java.util.HashSet<String> movies = new java.util.HashSet<String>();
                    java.util.HashSet<String> series = new java.util.HashSet<String>();
                    java.util.HashSet<String> chosen = new java.util.HashSet<String>();
                    ArrayList<HashMap<String, Object>> out = new ArrayList<HashMap<String, Object>>();
                    for (HashMap<String, Object> e : copy) {
                        if (out.size() >= 1500) break;
                        if (CigramOrder.claimed(claimed, e)) continue;
                        boolean isSeries = "series".equalsIgnoreCase(String.valueOf(e.get("type")));
                        String title = e.get("title") == null ? "" : String.valueOf(e.get("title"));
                        String key = (isSeries ? "s|" : "m|") + catalog.normal(title) + "|" + (title.length() == 0 ? String.valueOf(e.get("id")) : "");
                        java.util.HashSet<String> bucket = isSeries ? series : movies;
                        if (!chosen.contains(key)) {
                            if (bucket.size() >= perType) continue;
                            bucket.add(key);
                            chosen.add(key);
                        }
                        out.add(e);
                    }
                    if (out.isEmpty()) return;
                    String json = new com.google.gson.Gson().toJson(out);
                    android.content.Context app = context.getApplicationContext();
                    app.getSharedPreferences(SNAP_PREFS, android.content.Context.MODE_PRIVATE).edit()
                            .putString("items", json).putString("version", versionOf(app)).apply();
                } catch (Throwable t) {
                    android.util.Log.w("CigramHome", "snapshot not saved", t);
                }
            }
        });
    }

    /** True once any response (cached or from the network) has been handed in for preparation. */
    static synchronized boolean requested() {
        return latest > 0;
    }

    static synchronized boolean isReady(String raw) {
        return raw != null && DONE.containsKey(key(raw));
    }

    /** The prepared result for this exact response object (once), or null when it failed / is absent. */
    static synchronized Result take(String raw) {
        if (raw == null) return null;
        Object v = isSnapshot(raw) ? DONE.remove(key(raw)) : DONE.get(key(raw));
        return v instanceof Result ? (Result) v : null;
    }

    /**
     * Prepares {@code raw} in the background, then runs {@code done} (any thread). The same content is
     * parsed once (waiting callers share the result) and a finished result is never thrown away.
     */
    static void prepareAsync(final String raw, final Runnable done) {
        final String k = key(raw);
        boolean ready;
        synchronized (HomePrep.class) {
            ready = DONE.containsKey(k);
            if (!ready) {
                ArrayList<Runnable> waiters = PENDING.get(k);
                if (waiters != null) {
                    if (done != null) waiters.add(done);
                    return;
                }
                waiters = new ArrayList<Runnable>();
                if (done != null) waiters.add(done);
                PENDING.put(k, waiters);
                latest++;
            }
        }
        if (ready) {
            try { if (done != null) done.run(); } catch (Throwable ignored) { }
            return;
        }
        executor().execute(new Runnable() {
            @Override public void run() {
                Object outcome;
                final long t0 = android.os.SystemClock.elapsedRealtime();
                try {
                    JSONArray items = new JSONArray();
                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    ArrayList<HashMap<String, Object>> maps = new ArrayList<HashMap<String, Object>>();
                    // The early download (CigramStage1InitProvider) has already normalised, de-duplicated and
                    // indexed this catalogue: reuse it instead of repeating the same multi-second work here.
                    ArrayList<org.json.JSONObject> ready = CigramStage1InitProvider.cigramCatalogSnapshot();
                    if (!ready.isEmpty()) {
                        for (org.json.JSONObject source : ready) {
                            String text = source.toString();
                            items.put(new org.json.JSONObject(text));
                            @SuppressWarnings("unchecked")
                            HashMap<String, Object> entry = (HashMap<String, Object>) gson.fromJson(text, HashMap.class);
                            maps.add(entry);
                        }
                        CigramStage1InitProvider.trace("mem" + items.length() + "@" + (android.os.SystemClock.elapsedRealtime() - t0));
                    } else {
                        String normalized = CigramTitles.normalizeCatalog(raw);
                        CigramStage1InitProvider.trace("norm@" + (android.os.SystemClock.elapsedRealtime() - t0));
                        items = new HomeCatalog().parse(normalized);
                        CigramStage1InitProvider.trace("parse" + items.length() + "@" + (android.os.SystemClock.elapsedRealtime() - t0));
                        for (int i = 0; i < items.length(); i++) {
                            @SuppressWarnings("unchecked")
                            HashMap<String, Object> entry = (HashMap<String, Object>)
                                    gson.fromJson(items.getJSONObject(i).toString(), HashMap.class);
                            maps.add(entry);
                        }
                        CigramStage1InitProvider.trace("maps@" + (android.os.SystemClock.elapsedRealtime() - t0));
                    }
                    // also off the main thread: the newest-first sort and the custom-section claims (memoised)
                    CigramOrder.sortMaps(maps);
                    CigramStage1InitProvider.trace("sort@" + (android.os.SystemClock.elapsedRealtime() - t0));
                    CigramOrder.claims(raw);
                    CigramStage1InitProvider.trace("claims@" + (android.os.SystemClock.elapsedRealtime() - t0));
                    outcome = new Result(items, maps);
                } catch (Throwable error) {
                    android.util.Log.e("CigramDiag", "PARSE FAIL (HomePrep) " + error.getClass().getSimpleName()
                            + ": " + error.getMessage() + " rawLen=" + (raw == null ? -1 : raw.length())
                            + " head=" + (raw == null ? "NULL" : raw.substring(0, Math.min(200, raw.length())).replace('\n', ' ')), error);
                    CigramStage1InitProvider.LAST_DIAG = "فشل تحليل الكتالوج: " + error.getClass().getSimpleName()
                            + " " + error.getMessage();
                    outcome = FAILED;
                }
                ArrayList<Runnable> waiters;
                synchronized (HomePrep.class) {
                    DONE.put(k, outcome);
                    evict();
                    waiters = PENDING.remove(k);
                }
                CigramStage1InitProvider.trace("prepDone");
                if (waiters != null) {
                    for (Runnable r : waiters) {
                        try { r.run(); } catch (Throwable ignored) { }
                    }
                }
            }
        });
    }
}
