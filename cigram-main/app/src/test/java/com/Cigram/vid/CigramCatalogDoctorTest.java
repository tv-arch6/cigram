package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** The diagnosis (read-only) and the one repair the app makes on its own: a verifying full download. */
public class CigramCatalogDoctorTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String SOUND = "{\"ok\":true,\"count\":6,\"movies\":["
            + "{\"id\":\"m1\",\"type\":\"movie\",\"title\":\"A\",\"poster\":\"p\"},"
            + "{\"id\":\"s1\",\"type\":\"series\",\"series_id\":\"s1\",\"title\":\"S\",\"poster\":\"p\"},"
            + "{\"id\":\"e1\",\"type\":\"episode\",\"series_id\":\"s1\",\"season\":1,\"episode\":1},"
            + "{\"id\":\"e2\",\"type\":\"episode\",\"series_id\":\"s1\",\"season\":1,\"episode\":2},"
            + "{\"id\":\"e3\",\"type\":\"episode\",\"series_id\":\"s1\",\"season\":1,\"episode\":3},"
            + "{\"id\":\"m2\",\"type\":\"movie\",\"title\":\"B\",\"poster\":\"p\"}]}";

    @Test public void aSoundCatalogueHasNoFindings() {
        CigramCatalogDoctor.Report r = CigramCatalogDoctor.diagnose(SOUND);
        assertNotNull(r);
        assertEquals(6, r.records);
        assertFalse(r.structural());
        assertEquals(0, r.duplicateIds + r.orphanEpisodes + r.duplicateEpisodeRows + r.seasonsWithHoles + r.missingPosters + r.seriesWithoutEpisodes);
        assertFalse(r.envelopeCountMismatch);
    }

    @Test public void everyKindOfFindingIsCountedAndNothingIsChanged() {
        String bad = "{\"ok\":true,\"count\":88,\"movies\":["
                + "{\"id\":\"m1\",\"type\":\"movie\",\"title\":\"A\"},"                                   // no poster
                + "{\"id\":\"m1\",\"type\":\"movie\",\"title\":\"A again\",\"poster\":\"p\"},"            // duplicate id
                + "{\"id\":\"s1\",\"type\":\"series\",\"series_id\":\"s1\",\"poster\":\"p\"},"
                + "{\"id\":\"s2\",\"type\":\"series\",\"series_id\":\"s2\",\"poster\":\"p\"},"            // a header without episodes
                + "{\"id\":\"e1\",\"type\":\"episode\",\"series_id\":\"s1\",\"season\":1,\"episode\":1},"
                + "{\"id\":\"e4\",\"type\":\"episode\",\"series_id\":\"s1\",\"season\":1,\"episode\":4},"  // season hole (2, 3 missing)
                + "{\"id\":\"e1b\",\"type\":\"episode\",\"series_id\":\"s1\",\"season\":1,\"episode\":1}," // same (series,season,episode)
                + "{\"id\":\"x1\",\"type\":\"episode\",\"series_id\":\"gone\",\"season\":1,\"episode\":1},"// orphan
                + "{\"title\":\"no id\"}]}";
        String before = bad;
        CigramCatalogDoctor.Report r = CigramCatalogDoctor.diagnose(bad);
        assertNotNull(r);
        assertEquals(1, r.duplicateIds);
        assertEquals(1, r.orphanEpisodes);
        assertEquals(1, r.duplicateEpisodeRows);
        assertEquals(1, r.seriesWithoutEpisodes);
        assertEquals(1, r.seasonsWithHoles);
        assertEquals(1, r.missingPosters);
        assertEquals(1, r.recordsWithoutId);
        assertTrue(r.envelopeCountMismatch);
        assertTrue(r.structural());
        assertEquals("the diagnosis never rewrites the document", before, bad);
        assertTrue(r.toJson().contains("\"orphan_episodes\":1"));
    }

    @Test public void somethingThatIsNotACatalogueIsNull() {
        assertNull(CigramCatalogDoctor.diagnose("<html>x</html>"));
        assertNull(CigramCatalogDoctor.diagnose("{\"ok\":true}"));
    }

    // ------------------------------------------------------------------------------------------------ the one automatic repair

    @Test public void aStructuralFindingAfterADeltaSchedulesOneVerifyingFullDownloadNotAnInventedRepair() throws Exception {
        CigramCatalogSyncTest.FakeServer s = new CigramCatalogSyncTest.FakeServer(60);
        CigramCatalogStore store = new CigramCatalogStore(new File(tmp.getRoot(), "c"));
        long now = 1_800_000_000_000L;
        CigramCatalogSync.Config c = new CigramCatalogSync.Config(); c.now = now;
        assertEquals(CigramCatalogSync.Kind.UPDATED, CigramCatalogSync.syncOnce(store, s, CigramCatalogSyncTest.IDENTITY, c).kind);
        now += 7L * 3600L * 1000L;                                // the last full download is older than the 6 h gap

        List<JSONObject> ups = new ArrayList<JSONObject>();       // an episode whose series header does not exist
        ups.add(new JSONObject().put("id", "orphan1").put("type", "episode").put("series_id", "nowhere").put("season", 1).put("episode", 1).put("created_at_ms", 9000L));
        s.change(ups, new ArrayList<String>());
        c = new CigramCatalogSync.Config(); c.now = now;
        CigramCatalogSync.Outcome d = CigramCatalogSync.syncOnce(store, s, CigramCatalogSyncTest.IDENTITY, c);
        assertEquals("delta", d.mode);
        assertEquals("the orphan is kept as the server has it, nothing is deleted or repaired by guessing", 61, CigramCatalogDelta.countRecords(store.load().display));
        assertEquals(1L, store.load().num(CigramCatalogSync.L_AUDIT_NOW));
        assertTrue(store.load().str(CigramCatalogSync.S_DOCTOR).contains("\"orphan_episodes\":1"));

        CigramCatalogSync.Config metered = new CigramCatalogSync.Config(); metered.now = now + 1000; metered.metered = true;
        s.add("later", "later");
        assertEquals("metered networks never spend data on the self-check", "delta", CigramCatalogSync.syncOnce(store, s, CigramCatalogSyncTest.IDENTITY, metered).mode);
        assertEquals(1L, store.load().num(CigramCatalogSync.L_AUDIT_NOW));

        int fullBefore = s.fullCalls;
        c = new CigramCatalogSync.Config(); c.now = now + 2000;
        CigramCatalogSync.Outcome audit = CigramCatalogSync.syncOnce(store, s, CigramCatalogSyncTest.IDENTITY, c);
        assertEquals(audit.toString(), "full", audit.mode);
        assertEquals(fullBefore + 1, s.fullCalls);
        assertEquals(0L, store.load().num(CigramCatalogSync.L_AUDIT_NOW));
        assertEquals(62, CigramCatalogDelta.countRecords(store.load().display));

        // a persistent server-side problem does not cause a full download on every delta: the gap is 6 hours
        s.add("another", "another");
        c = new CigramCatalogSync.Config(); c.now = now + 60_000;
        assertEquals("delta", CigramCatalogSync.syncOnce(store, s, CigramCatalogSyncTest.IDENTITY, c).mode);
        assertEquals(0L, store.load().num(CigramCatalogSync.L_AUDIT_NOW));
    }
}
