package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 18.4 — the saved catalogue behind the SAME {@link SharedPreferences} calls the 40 existing call sites already use
 * ({@code getString("json")}, {@code edit().putString("json", ..).apply()} ...), but stored in {@link CigramCatalogStore}:
 * atomic generations, SHA-256 verification, recovery of the last valid snapshot, no 10 MB XML rewrite per save.
 *
 * Mapping: "json" = catalogue, "search_index_json" = search index, every other key is a small property of the snapshot.
 * Reads are served from memory; {@code apply()} updates memory at once and writes on one background thread, so the UI thread
 * never waits for disk. A write that the store refuses (empty / unusable catalogue, zero records) is rolled back in memory too, so a
 * bad value can never become the "current" catalogue of this process.
 *
 * Migration: the first time, a catalogue saved by an older version in the legacy preferences file is copied into the store, verified,
 * and only then removed from the legacy file (which shrinks that file from ~10 MB to a few bytes). If anything fails the legacy data
 * stays where it was and keeps being read.
 */
public final class CigramCatalogPrefs implements SharedPreferences {

    private static final String TAG = "CigramCatalogPrefs";
    public static final String LEGACY_FILE = "cigram_catalog_cache";
    public static final String KEY_JSON = "json";
    public static final String KEY_INDEX = "search_index_json";

    private static volatile CigramCatalogPrefs INSTANCE;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "cigram-catalog-writer"); t.setPriority(Thread.NORM_PRIORITY - 1); t.setDaemon(true); return t; }
    });

    public static CigramCatalogPrefs of(Context context) {
        CigramCatalogPrefs p = INSTANCE;
        if (p != null) return p;
        synchronized (CigramCatalogPrefs.class) {
            if (INSTANCE == null) {
                Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                INSTANCE = new CigramCatalogPrefs(app);
            }
            return INSTANCE;
        }
    }

    /** The store behind the facade (the sync manager commits through it directly). */
    public static CigramCatalogStore storeOf(Context context) {
        return of(context).store;
    }

    /** Where the older version's catalogue sits (a supplier, so the legacy file is only opened when the store is empty). Tests pass a fake. */
    interface LegacySource { SharedPreferences get(); }

    private final LegacySource legacySource;
    final CigramCatalogStore store;
    private final Object lock = new Object();
    private volatile boolean loaded;
    private String display = "";
    private String index = "";
    private final HashMap<String, String> strings = new HashMap<String, String>();
    private final HashMap<String, Long> longs = new HashMap<String, Long>();
    /** true once the legacy preferences were looked at (migrated or found empty). */
    private boolean migrationChecked;

    private CigramCatalogPrefs(final Context app) {
        this(new File(app.getFilesDir(), "catalog_v2"), new LegacySource() {
            @Override public SharedPreferences get() { return app.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE); }
        });
    }

    /** Package-private for the JVM tests. */
    CigramCatalogPrefs(File dir, LegacySource legacySource) {
        this.legacySource = legacySource;
        this.store = new CigramCatalogStore(dir);
    }

    // ------------------------------------------------------------------------------------------------ loading + migration

    private void ensureLoaded() {
        if (loaded) return;
        synchronized (lock) {
            if (loaded) return;
            try {
                CigramCatalogStore.Snapshot s = store.load();
                if (s == null && !migrationChecked) {
                    migrationChecked = true;
                    s = migrateLegacy();
                }
                if (s != null) adopt(s);
            } catch (Throwable t) {
                Log.w(TAG, "load failed", t);
            }
            loaded = true;
        }
    }

    private void adopt(CigramCatalogStore.Snapshot s) {
        display = s.display;
        index = s.index;
        strings.clear(); strings.putAll(s.strings);
        longs.clear(); longs.putAll(s.longs);
    }

    private CigramCatalogStore.Snapshot migrateLegacy() {
        try {
            SharedPreferences legacy = legacySource == null ? null : legacySource.get();
            if (legacy == null) return null;
            String json = legacy.getString(KEY_JSON, "");
            if (json == null || json.length() == 0) return null;
            int records = CigramCatalogDelta.countRecords(json);
            if (records < 1) return null;                               // not a usable catalogue: leave it alone
            HashMap<String, String> s = new HashMap<String, String>();
            HashMap<String, Long> l = new HashMap<String, Long>();
            for (String k : new String[]{"json_hash_v2", "catalog_raw_hash_v1", "catalog_etag_v1"}) {
                String v = legacy.getString(k, "");
                if (v != null && v.length() > 0) s.put(k, v);
            }
            for (String k : new String[]{"updated_at", "search_index_updated_at"}) {
                if (legacy.contains(k)) l.put(k, legacy.getLong(k, 0L));
            }
            l.put("records", (long) records);
            l.put("rev", 0L);
            CigramCatalogStore.Result r = store.commit(json, legacy.getString(KEY_INDEX, ""), s, l);
            if (!r.ok) { Log.w(TAG, "migration refused: " + r.reason); return null; }
            CigramCatalogStore.Snapshot back = store.load();
            if (back == null || !json.equals(back.display)) return null;      // verified read-back or nothing is removed
            legacy.edit().remove(KEY_JSON).remove(KEY_INDEX).remove("json_hash_v2").remove("catalog_raw_hash_v1")
                    .remove("catalog_etag_v1").remove("updated_at").remove("search_index_updated_at").apply();
            Log.i(TAG, "legacy catalogue migrated: " + records + " records");
            return back;
        } catch (Throwable t) {
            Log.w(TAG, "migration failed, legacy data kept", t);
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------ SharedPreferences (reads)

    @Override public Map<String, ?> getAll() {
        ensureLoaded();
        synchronized (lock) {
            HashMap<String, Object> all = new HashMap<String, Object>();
            all.putAll(strings);
            all.putAll(longs);
            if (display.length() > 0) all.put(KEY_JSON, display);
            if (index.length() > 0) all.put(KEY_INDEX, index);
            return all;
        }
    }

    @Override public String getString(String key, String defValue) {
        ensureLoaded();
        synchronized (lock) {
            if (KEY_JSON.equals(key)) return display.length() > 0 ? display : defValue;
            if (KEY_INDEX.equals(key)) return index.length() > 0 ? index : defValue;
            String v = strings.get(key);
            return v != null ? v : defValue;
        }
    }

    @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
    @Override public int getInt(String key, int defValue) { return (int) getLong(key, defValue); }
    @Override public float getFloat(String key, float defValue) { return defValue; }
    @Override public boolean getBoolean(String key, boolean defValue) { return getLong(key, defValue ? 1L : 0L) != 0L; }

    @Override public long getLong(String key, long defValue) {
        ensureLoaded();
        synchronized (lock) {
            Long v = longs.get(key);
            return v != null ? v.longValue() : defValue;
        }
    }

    @Override public boolean contains(String key) {
        ensureLoaded();
        synchronized (lock) {
            if (KEY_JSON.equals(key)) return display.length() > 0;
            if (KEY_INDEX.equals(key)) return index.length() > 0;
            return strings.containsKey(key) || longs.containsKey(key);
        }
    }

    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

    // ------------------------------------------------------------------------------------------------ Editor

    @Override public Editor edit() { return new Ed(); }

    private final class Ed implements Editor {
        private final HashMap<String, Object> puts = new HashMap<String, Object>();
        private final HashSet<String> removes = new HashSet<String>();
        private boolean clear;

        @Override public Editor putString(String k, String v) { if (v == null) return remove(k); puts.put(k, v); removes.remove(k); return this; }
        @Override public Editor putStringSet(String k, Set<String> v) { return this; }
        @Override public Editor putInt(String k, int v) { puts.put(k, Long.valueOf(v)); removes.remove(k); return this; }
        @Override public Editor putLong(String k, long v) { puts.put(k, Long.valueOf(v)); removes.remove(k); return this; }
        @Override public Editor putFloat(String k, float v) { return this; }
        @Override public Editor putBoolean(String k, boolean v) { puts.put(k, Long.valueOf(v ? 1L : 0L)); removes.remove(k); return this; }
        @Override public Editor remove(String k) { puts.remove(k); removes.add(k); return this; }
        @Override public Editor clear() { clear = true; return this; }

        @Override public boolean commit() { return run(true); }
        @Override public void apply() { run(false); }

        private boolean run(boolean sync) {
            ensureLoaded();
            final String newDisplay, newIndex;
            final HashMap<String, String> s;
            final HashMap<String, Long> l;
            final boolean blobsChanged;
            synchronized (lock) {
                String d = display, ix = index;
                s = new HashMap<String, String>(strings);
                l = new HashMap<String, Long>(longs);
                if (clear) { s.clear(); l.clear(); d = ""; ix = ""; }
                for (String k : removes) { if (KEY_JSON.equals(k)) d = ""; else if (KEY_INDEX.equals(k)) ix = ""; else { s.remove(k); l.remove(k); } }
                for (Map.Entry<String, Object> e : puts.entrySet()) {
                    String k = e.getKey();
                    Object v = e.getValue();
                    if (KEY_JSON.equals(k) && v instanceof String) d = (String) v;
                    else if (KEY_INDEX.equals(k) && v instanceof String) ix = (String) v;
                    else if (v instanceof Long) l.put(k, (Long) v);
                    else if (v instanceof String) s.put(k, (String) v);
                }
                // an empty / removed catalogue is NEVER applied: the saved one stays (the guard of the whole data layer)
                if (d.length() == 0 && display.length() > 0) { d = display; }
                if (ix.length() == 0 && index.length() > 0 && d.equals(display)) { ix = index; }
                blobsChanged = !d.equals(display) || !ix.equals(index);
                newDisplay = d;
                newIndex = ix;
                if (d.length() > 0 && !CigramCatalogStore.looksLikeJson(d)) {                 // HTML / garbage: refuse
                    Log.w(TAG, "refused an unusable catalogue (" + d.length() + " chars)");
                    return false;
                }
                display = newDisplay; index = newIndex;
                strings.clear(); strings.putAll(s);
                longs.clear(); longs.putAll(l);
            }
            Runnable write = new Runnable() {
                @Override public void run() { persist(newDisplay, newIndex, s, l, blobsChanged); }
            };
            if (sync) { write.run(); return true; }
            WRITER.execute(write);
            return true;
        }
    }

    /** Disk side of an edit. A refusal by the store restores the memory copy from the last valid snapshot. */
    private void persist(String d, String ix, HashMap<String, String> s, HashMap<String, Long> l, boolean blobsChanged) {
        try {
            if (d.length() == 0) return;
            if (!blobsChanged) {
                if (store.load() != null) store.updateProps(s, l);
                return;
            }
            l.put("records", (long) CigramCatalogDelta.countRecords(d));          // a property of THIS catalogue text (a carried-over count would be stale)
            CigramCatalogStore.Result r = store.commit(d, ix, s, l);
            if (!r.ok) {
                Log.w(TAG, "commit refused: " + r.reason + " -> keeping the last valid snapshot");
                synchronized (lock) {
                    CigramCatalogStore.Snapshot good = store.load();
                    if (good != null) adopt(good);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "persist failed", t);
        }
    }

    /** True when a usable catalogue is saved (after the one-time migration). Screens use it to decide "show saved content" vs "nothing to show". */
    public boolean hasCatalogue() {
        ensureLoaded();
        synchronized (lock) { return display.length() > 0; }
    }

    /** The sync manager commits through the store directly: re-read the snapshot it wrote so every SharedPreferences reader sees it. */
    public void reload() {
        synchronized (lock) {
            CigramCatalogStore.Snapshot s = store.load();
            if (s != null) adopt(s);
            loaded = true;
        }
    }

    /** Waits until queued writes reached the disk (tests, and before a process is expected to die). */
    public static void flush() {
        try { WRITER.submit(new Runnable() { @Override public void run() { } }).get(); } catch (Throwable ignored) { }
    }
}
