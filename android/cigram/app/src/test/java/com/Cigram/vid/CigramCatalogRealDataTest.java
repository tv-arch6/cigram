package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * The store, merge and diagnosis on the REAL catalogue (path in -Dcigram.catalogue or CIGRAM_CATALOGUE, a copy of the public
 * movies.json). Skipped when no file is given: the file is production data and is not part of the source archive.
 */
public class CigramCatalogRealDataTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static String real() throws Exception {
        String path = System.getProperty("cigram.catalogue", System.getenv("CIGRAM_CATALOGUE"));
        Assume.assumeTrue("no real catalogue file given", path != null && path.length() > 0 && Files.exists(Paths.get(path)));
        return new String(Files.readAllBytes(Paths.get(path)), Charset.forName("UTF-8"));
    }

    @Test public void realCatalogueThroughTheWholeDataLayer() throws Exception {
        String json = real();
        long t0 = System.nanoTime();
        CigramCatalogSync.Facts facts = CigramCatalogSync.Facts.of(json);
        long factsMs = (System.nanoTime() - t0) / 1000000L;
        assertNotNull(facts);
        assertTrue(facts.records > 1000);

        CigramCatalogStore store = new CigramCatalogStore(new File(tmp.getRoot(), "c"));
        java.util.HashMap<String, Long> l = new java.util.HashMap<String, Long>();
        l.put("records", (long) facts.records);
        t0 = System.nanoTime();
        assertTrue(store.commit(json, "[]", new java.util.HashMap<String, String>(), l).ok);
        long commitMs = (System.nanoTime() - t0) / 1000000L;
        t0 = System.nanoTime();
        CigramCatalogStore.Snapshot s = new CigramCatalogStore(new File(tmp.getRoot(), "c")).load();
        long loadMs = (System.nanoTime() - t0) / 1000000L;
        assertEquals(json, s.display);

        // one new episode folded into the real catalogue
        JSONArray ups = new JSONArray().put(new JSONObject().put("id", "real_test_new").put("type", "movie").put("title", "x").put("created_at_ms", System.currentTimeMillis()));
        t0 = System.nanoTime();
        CigramCatalogDelta.Merge m = CigramCatalogDelta.apply(json, ups, new JSONArray());
        long mergeMs = (System.nanoTime() - t0) / 1000000L;
        assertEquals(facts.records + 1, m.records);

        t0 = System.nanoTime();
        CigramCatalogDoctor.Report r = CigramCatalogDoctor.diagnose(json);
        long doctorMs = (System.nanoTime() - t0) / 1000000L;
        assertNotNull(r);
        System.out.println("REAL DATA LAYER: " + json.length() + " chars, " + facts.records + " records, ids_digest " + facts.idsDigest
                + "\n  facts " + factsMs + " ms, store commit " + commitMs + " ms, verified load " + loadMs + " ms, one-record merge " + mergeMs
                + " ms, diagnosis " + doctorMs + " ms (JVM, desktop CPU: a phone is several times slower)\n  doctor " + r.toJson());
    }
}
