package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * v10.63 — the channels catalogue, kept in memory for the whole run.
 *
 * The old page parsed the saved JSON and rebuilt every card each time it was opened. Now the JSON is
 * parsed ONCE, off the main thread (the home page asks for it while it settles), and every later
 * visit of the channels tab gets the ready model at once. The network is asked at most every
 * {@link #FRESH_MS}, and a download that equals what is saved changes nothing on screen.
 *
 * Storage is the same as before, so nothing is lost on update:
 *  - catalogue : prefs "cigram_channels_cache_v2" (json, fetched_at, error)
 *  - favourites / recently watched : prefs "cigram_channels_v3" (fav, recent) — the full-screen
 *    player writes "recent" there too
 */
final class CigramChannelStore {

    static final String CACHE_PREFS = "cigram_channels_cache_v2";
    static final String USER_PREFS = "cigram_channels_v3";
    private static final String TAG = "CigramChannelStore";
    private static final String ENDPOINT = "https://sigram-channels-api.wwq-mixtv.workers.dev/channels";
    private static final long FRESH_MS = 3L * 60L * 1000L;

    /** Channels of this height or more belong to the Plus tier (1080p - 4K). */
    static final int PLUS_MIN_HEIGHT = 1080;

    /** "quality" | "height" | "resolution" | "res" of a catalogue entry: a number (1080) or text ("4K", "UHD", "2160p", "FHD"). */
    static int qualityOf(JSONObject o) {
        Object v = o.opt("quality");
        if (v == null) v = o.opt("height");
        if (v == null) v = o.opt("resolution");
        if (v == null) v = o.opt("res");
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(java.util.Locale.US);
            if (s.contains("4k") || s.contains("uhd") || s.contains("2160")) return 2160;
            if (s.contains("2k") || s.contains("1440")) return 1440;
            if (s.contains("1080") || s.contains("fhd")) return 1080;
            if (s.contains("720")) return 720;
            if (s.contains("480")) return 480;
        }
        return 0;
    }

    // ------------------------------------------------------------------ model

    static final class Ch {
        String key = "";
        /** Video height of the channel when the catalogue says so ("quality": 1080 | 2160 | "4K" ...), else 0. */
        int quality;
        /** Plus tier: quality of 1080p-4K (or "plus": true). Only Cigram Plus accounts can play it. */
        boolean plus;
        String name = "";
        String url = "";
        String image = "";
        String section = "";
        /** Name without diacritics / hamza variants, for searching. */
        String search = "";
        /** The same for the package name, so "رياضة" finds the sports channels. */
        String sectionKey = "";
        /** Name of a drawable that ships in the app (the two built-in channels). */
        String drawable = "";
        boolean available = true;

        /** Key of the logo in {@link CigramLogos}: the picture link, or "res:name". */
        String logoKey() {
            if (image.length() > 0) return image;
            return drawable.length() > 0 ? "res:" + drawable : "";
        }
    }

    static final class Pkg {
        String name = "";
        final ArrayList<Ch> channels = new ArrayList<Ch>();

        Ch cover() {
            for (int i = 0; i < channels.size(); i++) {
                if (channels.get(i).logoKey().length() > 0) return channels.get(i);
            }
            return channels.isEmpty() ? null : channels.get(0);
        }
    }

    static final class Catalog {
        /** Every channel, grouped by package in package order. */
        final ArrayList<Ch> all = new ArrayList<Ch>();
        final ArrayList<Pkg> packages = new ArrayList<Pkg>();
        final HashMap<String, Ch> byKey = new HashMap<String, Ch>();
    }

    interface Listener {
        /** Main thread. changed = the list is different from the one delivered before. */
        void onCatalog(Catalog catalog, boolean changed);
    }

    // ------------------------------------------------------------------ state

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService PARSE = CigramPool.fixed(1, "channels-parse");
    private static final ExecutorService NET = CigramPool.fixed(1, "channels-net");
    private static final ArrayList<Listener> LISTENERS = new ArrayList<Listener>();
    private static volatile Catalog current;
    private static boolean parsing;
    private static boolean fetching;
    private static HashSet<String> favs;

    private CigramChannelStore() { }

    static Catalog peek() {
        return current;
    }

    // ------------------------------------------------------------------ loading

    /** Called by the home page: parses the saved catalogue and refreshes it when it is old. */
    static void prewarm(Context context) {
        try {
            load(context, null);
        } catch (Throwable error) {
            Log.w(TAG, "prewarm failed", error);
        }
    }

    /** Main thread. The listener gets the catalogue now (if it is ready) and again when it changes. */
    static void load(final Context context, Listener listener) {
        final Context app = context.getApplicationContext();
        if (listener != null && !LISTENERS.contains(listener)) LISTENERS.add(listener);
        Catalog ready = current;
        if (ready != null) {
            if (listener != null) listener.onCatalog(ready, false);
            refresh(app, false);
            return;
        }
        if (parsing) return;
        parsing = true;
        PARSE.execute(new Runnable() {
            @Override public void run() {
                final Catalog built = build(app, app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                        .getString("json", ""));
                UI.post(new Runnable() {
                    @Override public void run() {
                        parsing = false;
                        current = built;
                        deliver(built, false);
                        prefetchLogos(app, built);
                        refresh(app, built.all.isEmpty());
                    }
                });
            }
        });
    }

    static void unlisten(Listener listener) {
        LISTENERS.remove(listener);
    }

    private static void deliver(Catalog catalog, boolean changed) {
        ArrayList<Listener> copy = new ArrayList<Listener>(LISTENERS);
        for (int i = 0; i < copy.size(); i++) {
            try {
                copy.get(i).onCatalog(catalog, changed);
            } catch (Throwable error) {
                Log.w(TAG, "listener failed", error);
            }
        }
    }

    private static void prefetchLogos(Context app, Catalog catalog) {
        ArrayList<String> keys = new ArrayList<String>();
        for (int i = 0; i < catalog.all.size() && keys.size() < 80; i++) {
            String k = catalog.all.get(i).image;
            if (k.length() > 0) keys.add(k);
        }
        CigramLogos.prefetch(app, keys);
    }

    // ------------------------------------------------------------------ network

    /** Asks the server for the catalogue when the saved one is older than a few minutes. */
    /** 18.3: consecutive failed refreshes / last attempt (in-process): they drive the back-off of {@link CigramRefreshPolicy}. */
    private static volatile int failures;
    private static volatile boolean failurePermanent;
    private static volatile long lastAttemptAt;
    /** Set by download() when the server answered 304 (the saved list is still current). */
    private static volatile boolean notModified;
    private static volatile String lastEtag = "";

    static void refresh(final Context context, final boolean force) {
        final Context app = context.getApplicationContext();
        if (fetching) return;
        final SharedPreferences sp = app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
        long at = sp.getLong("fetched_at", 0L);
        long now = System.currentTimeMillis();
        CigramRefreshPolicy.State st = new CigramRefreshPolicy.State();
        st.now = now;
        st.lastAttemptAt = lastAttemptAt;
        st.snapshotConfirmedAt = (current != null || sp.contains("json")) ? at : 0L;
        st.running = fetching;
        st.consecutiveFailures = failures;
        st.lastFailurePermanent = failurePermanent;
        st.jitterSeed = now;
        // the channel list keeps its own 3-minute TTL (FRESH_MS); a tab switch no longer forces a download, and a failing
        // server is asked again with an exponential back-off instead of on every tab switch
        if (!CigramRefreshPolicy.shouldRefresh(force ? CigramRefreshPolicy.Reason.FORCED : CigramRefreshPolicy.Reason.TAB_SHOWN, st, FRESH_MS)) return;
        fetching = true;
        lastAttemptAt = now;
        NET.execute(new Runnable() {
            @Override public void run() {
                String fresh = null;
                notModified = false;
                try {
                    fresh = download(sp.contains("json") ? sp.getString("etag", "") : "");
                } catch (Throwable error) {
                    Log.w(TAG, "catalogue refresh failed", error);
                }
                final SharedPreferences prefs = app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
                if (notModified) {
                    // HTTP 304: the saved list is current; only its age restarts
                    failures = 0;
                    failurePermanent = false;
                    prefs.edit().putLong("fetched_at", System.currentTimeMillis()).putString("error", "").apply();
                    UI.post(new Runnable() { @Override public void run() { fetching = false; } });
                    return;
                }
                if (fresh == null) {
                    failures = Math.min(failures + 1, 12);   // a failure never replaces the saved list
                    prefs.edit().putString("error", "تعذر تحديث القنوات؛ آخر نسخة محفوظة باقية").apply();
                    UI.post(new Runnable() { @Override public void run() { fetching = false; } });
                    return;
                }
                failures = 0;
                failurePermanent = false;
                String old = prefs.getString("json", "");
                boolean same = fresh.equals(old);
                prefs.edit().putString("json", fresh).putLong("fetched_at", System.currentTimeMillis())
                        .putString("etag", lastEtag).putString("error", "").apply();
                final Catalog built = same ? null : build(app, fresh);
                UI.post(new Runnable() {
                    @Override public void run() {
                        fetching = false;
                        if (built == null) return;
                        current = built;
                        deliver(built, true);
                        prefetchLogos(app, built);
                    }
                });
            }
        });
    }

    private static String download(String etag) throws Exception {
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        try {
            // 18.3: no cache-busting query: a conditional request (If-None-Match) needs a stable address
            CigramPerf.request("channels");
            connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache");
            if (etag != null && etag.length() > 0) connection.setRequestProperty("If-None-Match", etag);
            int code = connection.getResponseCode();
            if (code == 304) { notModified = true; return null; }
            if (code < 200 || code >= 300) {
                failurePermanent = code >= 400 && code < 500 && code != 408 && code != 429;   // a 4xx is not retried quickly
                throw new java.io.IOException("HTTP " + code);
            }
            String got = connection.getHeaderField("ETag");
            lastEtag = got == null ? "" : got.trim();
            reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
                if (json.length() > 4000000) throw new java.io.IOException("Catalog too large");
            }
            JSONObject root = new JSONObject(json.toString());
            if (!root.optBoolean("ok", true) || root.optJSONArray("categories") == null) {
                throw new java.io.IOException("Invalid channels catalog");
            }
            return root.toString();   // same normal form the old page saved
        } finally {
            try { if (reader != null) reader.close(); } catch (Throwable ignored) { }
            if (connection != null) connection.disconnect();
        }
    }

    // ------------------------------------------------------------------ parsing

    static String norm(String v) {
        if (v == null) return "";
        return v.trim().toLowerCase(Locale.US)
                .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
                .replace("ة", "ه").replace("ى", "ي")
                .replaceAll("[\\s_\\-]+", "");
    }

    private static String sectionName(String raw) {
        String k = norm(raw);
        if (k.contains("رياض") || k.contains("sport")) return "الرياضة";
        if (k.contains("اخبار") || k.contains("خبر") || k.contains("news")) return "الأخبارية";
        return raw == null || raw.trim().length() == 0 ? "القنوات" : raw.trim();
    }

    private static Catalog build(Context app, String raw) {
        ArrayList<Ch> list = new ArrayList<Ch>();
        HashSet<String> seen = new HashSet<String>();
        // the two channels that live in the app itself come first
        String[][] builtin = CigramBuiltinChannels.LIST;
        for (int i = 0; i < builtin.length; i++) {
            Ch ch = new Ch();
            ch.name = builtin[i][0];
            ch.url = builtin[i][1];
            ch.section = sectionName(builtin[i][2]);
            ch.drawable = builtin[i][3];
            ch.key = norm(ch.name);
            ch.search = ch.key;
            ch.sectionKey = norm(ch.section);
            if (ch.key.length() == 0 || !seen.add(ch.key)) continue;
            list.add(ch);
        }
        try {
            if (raw != null && raw.trim().length() > 0) {
                JSONArray groups = new JSONObject(raw).optJSONArray("categories");
                for (int g = 0; groups != null && g < groups.length(); g++) {
                    JSONObject group = groups.optJSONObject(g);
                    if (group == null) continue;
                    String section = sectionName(group.optString("name", ""));
                    int groupQuality = qualityOf(group);                    // a whole package can be an HD / 4K package
                    boolean groupPlus = group.optBoolean("plus", false);
                    JSONArray items = group.optJSONArray("channels");
                    for (int i = 0; items != null && i < items.length(); i++) {
                        JSONObject o = items.optJSONObject(i);
                        if (o == null) continue;
                        String name = o.optString("name", "").trim();
                        String key = norm(name);
                        if (key.length() == 0 || !seen.add(key)) continue;
                        Ch ch = new Ch();
                        ch.name = name;
                        ch.key = key;
                        ch.search = key;
                        ch.section = section;
                        ch.sectionKey = norm(section);
                        ch.url = o.optString("url", o.optString("link", "")).trim();
                        ch.image = o.optString("image", o.optString("poster", o.optString("logo",
                                o.optString("image_url", o.optString("poster_url", ""))))).trim();
                        ch.available = o.optBoolean("available", true) && ch.url.length() > 0;
                        int q = qualityOf(o);
                        if (q <= 0) q = groupQuality;
                        ch.quality = q;
                        ch.plus = o.optBoolean("plus", groupPlus) || q >= PLUS_MIN_HEIGHT;
                        list.add(ch);
                    }
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "catalogue parse failed", error);
        }

        // packages: sports, news, then the rest in catalogue order
        ArrayList<String> order = new ArrayList<String>();
        order.add("الرياضة");
        order.add("الأخبارية");
        for (int i = 0; i < list.size(); i++) if (!order.contains(list.get(i).section)) order.add(list.get(i).section);
        Catalog catalog = new Catalog();
        for (int p = 0; p < order.size(); p++) {
            Pkg pkg = null;
            for (int i = 0; i < list.size(); i++) {
                Ch ch = list.get(i);
                if (!order.get(p).equals(ch.section)) continue;
                if (pkg == null) {
                    pkg = new Pkg();
                    pkg.name = order.get(p);
                    catalog.packages.add(pkg);
                }
                pkg.channels.add(ch);
                catalog.all.add(ch);
                catalog.byKey.put(ch.key, ch);
            }
        }
        CigramChannelReports.check(app, catalog);   // a reported channel that got a new link = «تم تفعيل القناة»
        return catalog;
    }

    // ------------------------------------------------------------------ favourites / recent

    private static SharedPreferences user(Context c) {
        return c.getApplicationContext().getSharedPreferences(USER_PREFS, Context.MODE_PRIVATE);
    }

    private static ArrayList<String> readList(Context c, String key) {
        ArrayList<String> out = new ArrayList<String>();
        try {
            JSONArray arr = new JSONArray(user(c).getString(key, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
        } catch (Throwable ignored) { }
        return out;
    }

    private static void writeList(Context c, String key, ArrayList<String> v) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < v.size(); i++) arr.put(v.get(i));
        user(c).edit().putString(key, arr.toString()).apply();
    }

    private static HashSet<String> favSet(Context c) {
        if (favs == null) favs = new HashSet<String>(readList(c, "fav"));
        return favs;
    }

    static boolean isFav(Context c, String key) {
        return favSet(c).contains(key);
    }

    /** Newest first. */
    static ArrayList<String> favourites(Context c) {
        return readList(c, "fav");
    }

    /** @return true when the channel is a favourite after the call. */
    static boolean toggleFav(Context c, String key) {
        ArrayList<String> list = readList(c, "fav");
        boolean now;
        if (list.contains(key)) {
            list.remove(key);
            now = false;
        } else {
            list.add(0, key);
            now = true;
        }
        writeList(c, "fav", list);
        favs = new HashSet<String>(list);
        return now;
    }

    /** Most recent first, at most 10. */
    static ArrayList<String> recent(Context c) {
        return readList(c, "recent");
    }

    static void pushRecent(Context c, String key) {
        if (key == null || key.length() == 0) return;
        ArrayList<String> r = readList(c, "recent");
        r.remove(key);
        r.add(0, key);
        while (r.size() > 10) r.remove(r.size() - 1);
        writeList(c, "recent", r);
    }
}
