package com.Cigram.vid;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Everything the Search page shows BEFORE the user types, kept on disk so the page opens complete on its first frame:
 *
 *  - recent queries  (the words the user searched for, newest first)
 *  - recent titles   (the titles opened from a search result, newest first)
 *  - last results    (the last query, its tab and the titles it found)
 *  - suggestions     (default content: the newest films / series, refreshed by Home whenever the catalogue changes)
 *
 * One small JSON file (a few tens of KB, titles trimmed to what a card needs). {@link #load} reads it synchronously: it is the only
 * thing the page does on open, so there is no catalogue parse, no request and no loading state. Writes are coalesced on one background
 * thread and are atomic (temp file + rename), so a crash can never leave half a file.
 */
final class CigramSearchCache {

    static final int MAX_QUERIES = 8;
    static final int MAX_RECENT = 12;
    static final int MAX_LAST = 24;
    static final int MAX_SUGGESTIONS = 14;

    private static final String FILE = "cigram_search_state_v1.json";
    private static final String[] CARD_KEYS = {
            "type", "id", "series_id", "title", "series_title", "title_ar", "title_en", "year", "category", "genre", "imdb",
            "rating", "poster", "series_poster", "season_poster", "image", "backdrop", "quality", "description"};

    /** What the page needs, as plain lists. Never null members. */
    static final class State {
        final ArrayList<String> queries = new ArrayList<String>();
        final ArrayList<JSONObject> recent = new ArrayList<JSONObject>();
        final ArrayList<JSONObject> suggestMovies = new ArrayList<JSONObject>();
        final ArrayList<JSONObject> suggestSeries = new ArrayList<JSONObject>();
        String lastQuery = "";
        String lastType = "all";
        final ArrayList<JSONObject> lastResults = new ArrayList<JSONObject>();

        boolean isEmpty() {
            return queries.isEmpty() && recent.isEmpty() && suggestMovies.isEmpty() && suggestSeries.isEmpty()
                    && (lastQuery.length() == 0 || lastResults.isEmpty());
        }
    }

    private static final Object LOCK = new Object();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "cigram-search-cache");
            t.setDaemon(true);
            return t;
        }
    });
    private static State state;
    private static boolean writePending;

    private CigramSearchCache() { }

    // ------------------------------------------------------------------ read

    /** The saved state (a small file read, once per process; later calls are memory only). Never null, never throws. */
    static State load(Context context) {
        synchronized (LOCK) {
            if (state != null) return state;
            State s = new State();
            File file = new File(context.getApplicationContext().getFilesDir(), FILE);
            if (file.isFile() && file.length() > 2L && file.length() < 2L * 1024L * 1024L) {
                FileInputStream in = null;
                try {
                    byte[] data = new byte[(int) file.length()];
                    in = new FileInputStream(file);
                    int off = 0;
                    while (off < data.length) {
                        int n = in.read(data, off, data.length - off);
                        if (n < 0) break;
                        off += n;
                    }
                    parse(new String(data, 0, off, "UTF-8"), s);
                } catch (Throwable error) {
                    android.util.Log.w("CigramSearchCache", "state unreadable, starting empty", error);
                    s = new State();
                } finally {
                    try { if (in != null) in.close(); } catch (Throwable ignored) { }
                }
            }
            state = s;
            return s;
        }
    }

    static void parse(String json, State out) throws org.json.JSONException {
        JSONObject root = new JSONObject(json);
        strings(root.optJSONArray("queries"), out.queries, MAX_QUERIES);
        objects(root.optJSONArray("recent"), out.recent, MAX_RECENT);
        objects(root.optJSONArray("suggest_movies"), out.suggestMovies, MAX_SUGGESTIONS);
        objects(root.optJSONArray("suggest_series"), out.suggestSeries, MAX_SUGGESTIONS);
        JSONObject last = root.optJSONObject("last");
        if (last != null) {
            out.lastQuery = last.optString("query", "").trim();
            String type = last.optString("type", "all");
            out.lastType = "movie".equals(type) || "series".equals(type) ? type : "all";
            objects(last.optJSONArray("results"), out.lastResults, MAX_LAST);
        }
    }

    // ------------------------------------------------------------------ update

    /** A query the user ran (kept once, newest first). Empty / one-letter queries are not kept. */
    static void rememberQuery(Context context, String query) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) return;
        State s = load(context);
        synchronized (LOCK) {
            for (int i = s.queries.size() - 1; i >= 0; i--) {
                if (s.queries.get(i).equalsIgnoreCase(q)) s.queries.remove(i);
            }
            s.queries.add(0, q);
            while (s.queries.size() > MAX_QUERIES) s.queries.remove(s.queries.size() - 1);
        }
        scheduleWrite(context);
    }

    static void clearQueries(Context context) {
        State s = load(context);
        synchronized (LOCK) {
            s.queries.clear();
            s.lastQuery = "";
            s.lastResults.clear();
        }
        scheduleWrite(context);
    }

    /** A title the user opened from the search page (kept once, newest first, trimmed to a card). */
    static void rememberOpened(Context context, JSONObject item) {
        if (item == null) return;
        State s = load(context);
        JSONObject card = trim(item);
        String key = CigramSectionStore.identity(card);
        synchronized (LOCK) {
            for (int i = s.recent.size() - 1; i >= 0; i--) {
                if (CigramSectionStore.identity(s.recent.get(i)).equals(key)) s.recent.remove(i);
            }
            s.recent.add(0, card);
            while (s.recent.size() > MAX_RECENT) s.recent.remove(s.recent.size() - 1);
        }
        scheduleWrite(context);
    }

    /** The results of the query the user just ran: shown again, at once, the next time the page opens. */
    static void rememberResults(Context context, String query, String type, List<JSONObject> hits) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2 || hits == null) return;
        State s = load(context);
        ArrayList<JSONObject> cards = new ArrayList<JSONObject>();
        for (int i = 0; i < hits.size() && cards.size() < MAX_LAST; i++) cards.add(trim(hits.get(i)));
        synchronized (LOCK) {
            s.lastQuery = q;
            s.lastType = "movie".equals(type) || "series".equals(type) ? type : "all";
            s.lastResults.clear();
            s.lastResults.addAll(cards);
        }
        scheduleWrite(context);
    }

    /** Default content, written by the Home pipeline when the catalogue is read (background thread). Writes only when it changed. */
    static void putSuggestions(Context context, List<JSONObject> movies, List<JSONObject> series) {
        if (context == null) return;
        State s = load(context);
        ArrayList<JSONObject> m = trimAll(movies, MAX_SUGGESTIONS);
        ArrayList<JSONObject> t = trimAll(series, MAX_SUGGESTIONS);
        if (m.isEmpty() && t.isEmpty()) return;
        synchronized (LOCK) {
            if (sameCards(s.suggestMovies, m) && sameCards(s.suggestSeries, t)) return;
            s.suggestMovies.clear();
            s.suggestMovies.addAll(m);
            s.suggestSeries.clear();
            s.suggestSeries.addAll(t);
        }
        scheduleWrite(context);
    }

    // ------------------------------------------------------------------ write

    private static void scheduleWrite(final Context context) {
        final Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (writePending) return;
            writePending = true;
        }
        WRITER.execute(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(400L); } catch (InterruptedException ignored) { }   // coalesce a burst of updates into one write
                String json;
                synchronized (LOCK) {
                    writePending = false;
                    json = toJson(state == null ? new State() : state);
                }
                File dir = app.getFilesDir();
                File target = new File(dir, FILE);
                File temp = new File(dir, FILE + ".tmp");
                FileOutputStream out = null;
                try {
                    out = new FileOutputStream(temp);
                    out.write(json.getBytes("UTF-8"));
                    out.getFD().sync();
                    out.close();
                    out = null;
                    if (!temp.renameTo(target)) {
                        target.delete();
                        temp.renameTo(target);
                    }
                } catch (Throwable error) {
                    android.util.Log.w("CigramSearchCache", "state not saved", error);
                } finally {
                    try { if (out != null) out.close(); } catch (Throwable ignored) { }
                }
            }
        });
    }

    static String toJson(State s) {
        try {
            JSONObject root = new JSONObject();
            root.put("v", 1);
            root.put("queries", new JSONArray(s.queries));
            root.put("recent", array(s.recent));
            root.put("suggest_movies", array(s.suggestMovies));
            root.put("suggest_series", array(s.suggestSeries));
            JSONObject last = new JSONObject();
            last.put("query", s.lastQuery);
            last.put("type", s.lastType);
            last.put("results", array(s.lastResults));
            root.put("last", last);
            return root.toString();
        } catch (Throwable error) {
            return "{}";
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The fields a card (and the details page's first paint) needs; everything else is looked up in the catalogue when a title opens. */
    static JSONObject trim(JSONObject item) {
        JSONObject out = new JSONObject();
        if (item == null) return out;
        for (String key : CARD_KEYS) {
            Object v = item.opt(key);
            if (v == null || v == JSONObject.NULL) continue;
            String text = String.valueOf(v);
            if (text.trim().length() == 0) continue;
            try { out.put(key, text.length() > 400 ? text.substring(0, 400) : v); } catch (Throwable ignored) { }
        }
        return out;
    }

    private static ArrayList<JSONObject> trimAll(List<JSONObject> items, int max) {
        ArrayList<JSONObject> out = new ArrayList<JSONObject>();
        if (items == null) return out;
        for (int i = 0; i < items.size() && out.size() < max; i++) out.add(trim(items.get(i)));
        return out;
    }

    private static boolean sameCards(List<JSONObject> a, List<JSONObject> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).toString().equals(b.get(i).toString())) return false;
        }
        return true;
    }

    private static JSONArray array(List<JSONObject> items) {
        JSONArray out = new JSONArray();
        for (JSONObject item : items) out.put(item);
        return out;
    }

    private static void strings(JSONArray in, List<String> out, int max) {
        for (int i = 0; in != null && i < in.length() && out.size() < max; i++) {
            String v = in.optString(i, "").trim();
            if (v.length() > 0) out.add(v);
        }
    }

    private static void objects(JSONArray in, List<JSONObject> out, int max) {
        for (int i = 0; in != null && i < in.length() && out.size() < max; i++) {
            JSONObject o = in.optJSONObject(i);
            if (o != null) out.add(o);
        }
    }

    /** Test hook: drop the in-memory copy so the next {@link #load} reads the file again. */
    static void resetForTest() {
        synchronized (LOCK) {
            state = null;
            writePending = false;
        }
    }
}
