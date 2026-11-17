package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** The SharedPreferences facade over the durable store: migration, the empty-catalogue guard, instant reads. */
public class CigramCatalogPrefsTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** The old version's preferences file, in memory. */
    static final class FakeLegacy implements SharedPreferences {
        final HashMap<String, Object> m = new HashMap<String, Object>();
        int removeCalls;
        @Override public Map<String, ?> getAll() { return m; }
        @Override public String getString(String k, String d) { Object v = m.get(k); return v instanceof String ? (String) v : d; }
        @Override public Set<String> getStringSet(String k, Set<String> d) { return d; }
        @Override public int getInt(String k, int d) { return d; }
        @Override public long getLong(String k, long d) { Object v = m.get(k); return v instanceof Long ? (Long) v : d; }
        @Override public float getFloat(String k, float d) { return d; }
        @Override public boolean getBoolean(String k, boolean d) { return d; }
        @Override public boolean contains(String k) { return m.containsKey(k); }
        @Override public Editor edit() {
            return new Editor() {
                final java.util.HashSet<String> rm = new java.util.HashSet<String>();
                @Override public Editor putString(String k, String v) { m.put(k, v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { return this; }
                @Override public Editor putInt(String k, int v) { return this; }
                @Override public Editor putLong(String k, long v) { m.put(k, v); return this; }
                @Override public Editor putFloat(String k, float v) { return this; }
                @Override public Editor putBoolean(String k, boolean v) { return this; }
                @Override public Editor remove(String k) { rm.add(k); return this; }
                @Override public Editor clear() { m.clear(); return this; }
                @Override public boolean commit() { apply(); return true; }
                @Override public void apply() { for (String k : rm) m.remove(k); removeCalls++; }
            };
        }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
    }

    private static String catalogue(int n) {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"movies\":[");
        for (int i = 0; i < n; i++) { if (i > 0) sb.append(','); sb.append("{\"id\":\"m").append(i).append("\",\"title\":\"t").append(i).append("\"}"); }
        return sb.append("]}").toString();
    }

    private File dir() { return new File(tmp.getRoot(), "catalog_v2"); }

    private CigramCatalogPrefs open(final FakeLegacy legacy) {
        return new CigramCatalogPrefs(dir(), new CigramCatalogPrefs.LegacySource() { @Override public SharedPreferences get() { return legacy; } });
    }

    @Test public void theCatalogueSavedByTheOldVersionIsMigratedVerifiedAndOnlyThenRemovedFromTheOldFile() {
        FakeLegacy legacy = new FakeLegacy();
        String json = catalogue(120);
        legacy.m.put("json", json);
        legacy.m.put("search_index_json", "[{\"id\":\"m1\"}]");
        legacy.m.put("json_hash_v2", "abc");
        legacy.m.put("catalog_etag_v1", "\"e5\"");
        legacy.m.put("updated_at", 12345L);
        CigramCatalogPrefs p = open(legacy);
        assertEquals(json, p.getString("json", ""));                       // available immediately, from the migrated copy
        assertEquals("[{\"id\":\"m1\"}]", p.getString("search_index_json", ""));
        assertEquals("abc", p.getString("json_hash_v2", ""));
        assertEquals("\"e5\"", p.getString("catalog_etag_v1", ""));
        assertEquals(12345L, p.getLong("updated_at", 0L));
        assertFalse("the ~10 MB legacy entry is gone", legacy.m.containsKey("json"));
        assertEquals(120L, new CigramCatalogStore(dir()).load().num("records"));
        // a second process reads the store and never needs the legacy file again
        CigramCatalogPrefs again = new CigramCatalogPrefs(dir(), null);
        assertEquals(json, again.getString("json", ""));
    }

    @Test public void anUnusableLegacyValueIsLeftAloneAndNothingIsInvented() {
        FakeLegacy legacy = new FakeLegacy();
        legacy.m.put("json", "<html>502</html>");
        CigramCatalogPrefs p = open(legacy);
        assertEquals("", p.getString("json", ""));
        assertFalse(p.contains("json"));
        assertTrue("the legacy value stays where it was", legacy.m.containsKey("json"));
    }

    @Test public void anEmptyCatalogueCanNeverReplaceOrRemoveTheSavedOne() {
        FakeLegacy legacy = new FakeLegacy();
        CigramCatalogPrefs p = open(legacy);
        String good = catalogue(60);
        p.edit().putString("json", good).putString("json_hash_v2", "h1").putLong("updated_at", 5L).apply();
        assertEquals("apply() is visible at once, before the disk write", good, p.getString("json", ""));
        CigramCatalogPrefs.flush();
        p.edit().putString("json", "").apply();
        p.edit().remove("json").apply();
        p.edit().putString("json", "<html>Bad gateway</html>").apply();
        p.edit().clear().apply();
        assertEquals(good, p.getString("json", ""));
        // the well-formed but EMPTY catalogue is refused by the store and rolled back
        p.edit().putString("json", "{\"ok\":true,\"count\":0,\"movies\":[]}").apply();
        CigramCatalogPrefs.flush();
        assertEquals(good, p.getString("json", ""));
        assertEquals(good, new CigramCatalogPrefs(dir(), null).getString("json", ""));
    }

    @Test public void aNewCatalogueIsPersistedAtomicallyWithItsProperties() {
        CigramCatalogPrefs p = open(new FakeLegacy());
        p.edit().putString("json", catalogue(50)).putString("search_index_json", "[1]").putString("json_hash_v2", "h").apply();
        CigramCatalogPrefs.flush();
        String v2 = catalogue(51);
        p.edit().putString("json", v2).putString("json_hash_v2", "h2").putString("catalog_etag_v1", "\"x\"").putLong("updated_at", 9L).apply();
        CigramCatalogPrefs.flush();
        CigramCatalogPrefs reopened = new CigramCatalogPrefs(dir(), null);
        assertEquals(v2, reopened.getString("json", ""));
        assertEquals("h2", reopened.getString("json_hash_v2", ""));
        assertEquals(9L, reopened.getLong("updated_at", 0));
        assertEquals(51L, new CigramCatalogStore(dir()).load().num("records"));
    }

    @Test public void touchingOnlyPropertiesDoesNotRewriteTheCatalogueFile() throws Exception {
        CigramCatalogPrefs p = open(new FakeLegacy());
        p.edit().putString("json", catalogue(70)).putString("json_hash_v2", "h").apply();
        CigramCatalogPrefs.flush();
        long gen = new CigramCatalogStore(dir()).load().gen;
        p.edit().putLong("updated_at", 77L).putLong("search_index_updated_at", 77L).apply();
        CigramCatalogPrefs.flush();
        CigramCatalogStore.Snapshot s = new CigramCatalogStore(dir()).load();
        assertEquals("no new generation", gen, s.gen);
        assertEquals(77L, s.num("updated_at"));
    }

    @Test public void theFacadeSeesWhatTheSyncManagerCommittedThroughTheStore() {
        CigramCatalogPrefs p = open(new FakeLegacy());
        p.edit().putString("json", catalogue(40)).apply();
        CigramCatalogPrefs.flush();
        String fromSync = catalogue(41);
        java.util.HashMap<String, Long> l = new java.util.HashMap<String, Long>();
        l.put("records", 41L); l.put("rev", 3L);
        assertTrue(p.store.commit(fromSync, "[]", new java.util.HashMap<String, String>(), l).ok);
        assertEquals("stale until reload()", catalogue(40), p.getString("json", ""));
        p.reload();
        assertEquals(fromSync, p.getString("json", ""));
        assertEquals(3L, p.getLong("rev", 0));
    }
}
