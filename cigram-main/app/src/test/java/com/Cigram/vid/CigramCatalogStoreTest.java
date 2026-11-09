package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

/** Persistence guarantees of {@link CigramCatalogStore}: atomic generations, integrity, recovery, guards. */
public class CigramCatalogStoreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static String catalogue(int n, String tag) {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"movies\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"m").append(i).append("\",\"title\":\"").append(tag).append(i).append(" مرحبا\"}");
        }
        return sb.append("]}").toString();
    }

    private static Map<String, Long> longs(long rev, long records) {
        Map<String, Long> l = new HashMap<String, Long>();
        l.put("rev", rev); l.put("records", records); l.put("updated_at", 111L);
        return l;
    }

    private static Map<String, String> strings(String etag) {
        Map<String, String> s = new HashMap<String, String>();
        s.put("catalog_etag_v1", etag);
        return s;
    }

    private File dir() { return new File(tmp.getRoot(), "catalog"); }

    @Test public void commitThenReopenGivesTheSameDataIncludingArabicTextAndProps() {
        CigramCatalogStore a = new CigramCatalogStore(dir());
        String display = catalogue(50, "t");
        assertTrue(a.commit(display, "[{\"i\":1}]", strings("\"e1\""), longs(7, 50)).ok);
        CigramCatalogStore b = new CigramCatalogStore(dir());          // a new process / app restart
        CigramCatalogStore.Snapshot s = b.load();
        assertNotNull(s);
        assertEquals(display, s.display);
        assertEquals("[{\"i\":1}]", s.index);
        assertEquals("\"e1\"", s.str("catalog_etag_v1"));
        assertEquals(7L, s.num("rev"));
        assertEquals(50L, s.num("records"));
        assertEquals(0, b.recoveredFromOlderGeneration);
    }

    @Test public void anEmptyOrUnusableCatalogueIsNeverCommittedAndTheOldOneStays() {
        CigramCatalogStore st = new CigramCatalogStore(dir());
        String good = catalogue(30, "g");
        assertTrue(st.commit(good, "[]", strings("a"), longs(1, 30)).ok);
        assertFalse(st.commit("", "[]", strings("b"), longs(2, 0)).ok);
        assertFalse(st.commit("   ", "[]", strings("b"), longs(2, 0)).ok);
        assertFalse(st.commit("<html>502 Bad Gateway</html>", "[]", strings("b"), longs(2, 0)).ok);
        assertFalse(st.commit(null, "[]", strings("b"), longs(2, 0)).ok);
        CigramCatalogStore.Result zero = st.commit("{\"ok\":true,\"movies\":[]}", "[]", strings("b"), longs(2, 0));
        assertFalse("records=0 is refused", zero.ok);
        assertEquals(good, new CigramCatalogStore(dir()).load().display);
    }

    @Test public void aTruncatedNewestGenerationFallsBackToThePreviousValidOne() throws Exception {
        CigramCatalogStore st = new CigramCatalogStore(dir());
        String v1 = catalogue(40, "one"), v2 = catalogue(41, "two");
        assertTrue(st.commit(v1, "[]", strings("1"), longs(1, 40)).ok);
        assertTrue(st.commit(v2, "[]", strings("2"), longs(2, 41)).ok);
        long newest = st.load().gen;
        RandomAccessFile f = new RandomAccessFile(new File(new File(dir(), "g" + newest), "display.json"), "rw");
        f.setLength(f.length() / 2);                              // a power cut in the middle of a write
        f.close();
        CigramCatalogStore reopened = new CigramCatalogStore(dir());
        CigramCatalogStore.Snapshot s = reopened.load();
        assertNotNull(s);
        assertEquals("recovered the last valid snapshot", v1, s.display);
        assertEquals(1, reopened.recoveredFromOlderGeneration);
        assertTrue(reopened.damagedGenerationsSeen >= 1);
        // and the next commit works on top of the recovered state
        assertTrue(reopened.commit(catalogue(42, "three"), "[]", strings("3"), longs(3, 42)).ok);
        assertEquals(catalogue(42, "three"), new CigramCatalogStore(dir()).load().display);
    }

    @Test public void aSilentBitFlipWithTheSameLengthIsDetectedByTheChecksum() throws Exception {
        CigramCatalogStore st = new CigramCatalogStore(dir());
        String v1 = catalogue(40, "one");
        assertTrue(st.commit(v1, "[]", strings("1"), longs(1, 40)).ok);
        assertTrue(st.commit(catalogue(40, "two"), "[]", strings("2"), longs(2, 40)).ok);
        long newest = st.load().gen;
        File display = new File(new File(dir(), "g" + newest), "display.json");
        RandomAccessFile f = new RandomAccessFile(display, "rw");
        f.seek(30); int b = f.read(); f.seek(30); f.write(b == 'x' ? 'y' : 'x');
        f.close();
        assertEquals(v1, new CigramCatalogStore(dir()).load().display);
    }

    @Test public void aMissingOrGarbageCurrentPointerIsRecoveredFromTheNewestValidGeneration() throws Exception {
        CigramCatalogStore st = new CigramCatalogStore(dir());
        String v2 = catalogue(45, "two");
        assertTrue(st.commit(catalogue(44, "one"), "[]", strings("1"), longs(1, 44)).ok);
        assertTrue(st.commit(v2, "[]", strings("2"), longs(2, 45)).ok);
        FileOutputStream o = new FileOutputStream(new File(dir(), "CURRENT"));
        o.write("not a number".getBytes("UTF-8")); o.close();
        assertEquals(v2, new CigramCatalogStore(dir()).load().display);
        assertTrue(new File(dir(), "CURRENT").delete());
        assertEquals(v2, new CigramCatalogStore(dir()).load().display);
    }

    @Test public void leftoversOfAnInterruptedWriteAreIgnoredAndCleaned() throws Exception {
        CigramCatalogStore st = new CigramCatalogStore(dir());
        String v1 = catalogue(30, "one");
        assertTrue(st.commit(v1, "[]", strings("1"), longs(1, 30)).ok);
        File half = new File(dir(), "t9");                         // the process died while writing generation 9
        assertTrue(half.mkdirs());
        FileOutputStream o = new FileOutputStream(new File(half, "display.json")); o.write("{\"ok\":tr".getBytes("UTF-8")); o.close();
        FileOutputStream c = new FileOutputStream(new File(dir(), "CURRENT.tmp")); c.write("9".getBytes("UTF-8")); c.close();
        CigramCatalogStore reopened = new CigramCatalogStore(dir());
        assertEquals(v1, reopened.load().display);
        assertTrue(reopened.interruptedWritesCleaned >= 2);
        assertFalse(half.exists());
        assertFalse(new File(dir(), "CURRENT.tmp").exists());
    }

    @Test public void onlyTheNewestTwoGenerationsStayOnDisk() {
        CigramCatalogStore st = new CigramCatalogStore(dir());
        for (int i = 1; i <= 6; i++) assertTrue(st.commit(catalogue(30 + i, "v" + i), "[]", strings("e" + i), longs(i, 30 + i)).ok);
        String[] gens = dir().list(new java.io.FilenameFilter() { @Override public boolean accept(File d, String n) { return n.startsWith("g"); } });
        assertEquals(2, gens.length);
    }

    @Test public void propertiesCanBeUpdatedWithoutRewritingTheCatalogue() {
        CigramCatalogStore st = new CigramCatalogStore(dir());
        String v = catalogue(60, "p");
        assertTrue(st.commit(v, "[]", strings("e"), longs(3, 60)).ok);
        File blob = new File(new File(dir(), "g" + st.load().gen), "display.json");
        long len = blob.length();
        long modified = blob.lastModified();
        Map<String, Long> l = longs(3, 60);
        l.put("updated_at", 999L);
        assertTrue(st.updateProps(strings("e"), l).ok);
        assertEquals(len, blob.length());
        assertEquals(modified, blob.lastModified());
        CigramCatalogStore.Snapshot s = new CigramCatalogStore(dir()).load();
        assertEquals(999L, s.num("updated_at"));
        assertEquals(v, s.display);
    }

    @Test public void whenEveryGenerationIsDamagedTheStoreIsEmptyNotCrashing() throws Exception {
        CigramCatalogStore st = new CigramCatalogStore(dir());
        assertTrue(st.commit(catalogue(30, "a"), "[]", strings("a"), longs(1, 30)).ok);
        assertTrue(st.commit(catalogue(31, "b"), "[]", strings("b"), longs(2, 31)).ok);
        for (File g : dir().listFiles()) if (g.getName().startsWith("g")) new File(g, "display.json").delete();
        CigramCatalogStore reopened = new CigramCatalogStore(dir());
        assertNull(reopened.load());
        assertFalse(reopened.hasSnapshot());
        assertTrue("a fresh commit works afterwards", reopened.commit(catalogue(32, "c"), "[]", strings("c"), longs(3, 32)).ok);
    }

    @Test public void auxFilesAreReplacedAtomically() {
        CigramCatalogStore st = new CigramCatalogStore(dir());
        assertTrue(st.writeAux("x.json", "one".getBytes()));
        assertTrue(st.writeAux("x.json", "two".getBytes()));
        assertEquals("two", new String(st.readAux("x.json")));
        st.deleteAux("x.json");
        assertNull(st.readAux("x.json"));
    }

    @Test public void aBigCatalogueRoundTripsAndTheMeasuredCostIsReported() {
        String big = catalogue(6000, "titel");                      // ~ the size class of the real catalogue
        CigramCatalogStore st = new CigramCatalogStore(dir());
        long t0 = System.nanoTime();
        assertTrue(st.commit(big, "[]", strings("e"), longs(1, 6000)).ok);
        long commitMs = (System.nanoTime() - t0) / 1000000L;
        long t1 = System.nanoTime();
        CigramCatalogStore.Snapshot s = new CigramCatalogStore(dir()).load();
        long loadMs = (System.nanoTime() - t1) / 1000000L;
        assertEquals(big, s.display);
        System.out.println("STORE " + big.length() + " chars: commit " + commitMs + " ms, verified load " + loadMs + " ms (JVM)");
    }
}
