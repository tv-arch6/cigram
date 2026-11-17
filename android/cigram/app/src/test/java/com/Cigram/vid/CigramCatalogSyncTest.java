package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The synchronisation manager against a fake Cloudflare that behaves like the Worker's /catalog feed (docs/backend). */
public class CigramCatalogSyncTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    // ------------------------------------------------------------------------------------------------ fake server
    static final class FakeServer implements CigramCatalogSync.Transport {
        final LinkedHashMap<String, JSONObject> records = new LinkedHashMap<String, JSONObject>();
        final List<long[]> logRevs = new ArrayList<long[]>();           // unused marker, kept simple
        final List<JSONObject> log = new ArrayList<JSONObject>();       // {rev, up:[...], rm:[...]}
        long rev = 1, windowFrom = 1;
        boolean feed = true;
        int manifestStatus = 200, changesStatus = 200, fullStatus = 200;
        boolean manifestIo, changesIo, fullIo, ignoreConditional, lieAboutDigest, serveEmptyFull;
        int failChangesAfterPages = -1;
        String fullBodyOverride;
        int manifestCalls, changesCalls, fullCalls;
        final List<String> changeRequests = new ArrayList<String>();
        long fullBytes, changeBytes, manifestBytes;

        FakeServer(int n) {
            for (int i = 0; i < n; i++) records.put("m" + i, rec("m" + i, "Movie " + i, 1000L + i));
        }

        static JSONObject rec(String id, String title, long created) {
            try {
                return new JSONObject().put("id", id).put("type", "movie").put("title", title).put("created_at_ms", created).put("video", "https://v.example.invalid/" + id);
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        String etag() { return "\"cat-" + rev + "-" + records.size() + "\""; }
        String fileEtag() { return "f" + rev; }

        void change(List<JSONObject> ups, List<String> rms) throws Exception {
            rev++;
            JSONArray ua = new JSONArray(), ra = new JSONArray();
            for (JSONObject o : ups) { records.put(o.getString("id"), o); ua.put(o); }
            for (String id : rms) { records.remove(id); ra.put(id); }
            log.add(new JSONObject().put("rev", rev).put("up", ua).put("rm", ra));
        }

        void add(String id, String title) throws Exception { List<JSONObject> l = new ArrayList<JSONObject>(); l.add(rec(id, title, 5000L + rev)); change(l, new ArrayList<String>()); }
        void edit(String id, String title) throws Exception { List<JSONObject> l = new ArrayList<JSONObject>(); l.add(rec(id, title, records.get(id).optLong("created_at_ms"))); change(l, new ArrayList<String>()); }
        void remove(String id) throws Exception { List<String> r = new ArrayList<String>(); r.add(id); change(new ArrayList<JSONObject>(), r); }
        void reset() { rev++; windowFrom = rev; log.clear(); }

        String idsDigest() { return CigramCatalogDelta.idsDigest(records.keySet()); }

        String fullDoc() {
            try {
                JSONArray a = new JSONArray();
                for (JSONObject o : records.values()) a.put(o);
                return new JSONObject().put("ok", true).put("count", 88).put("movies", a).toString();
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Override public CigramCatalogSync.Reply get(String path, String etag) throws IOException {
            try {
                if (path.startsWith("/catalog/manifest")) {
                    manifestCalls++;
                    if (manifestIo) throw new IOException("timeout");
                    if (manifestStatus != 200) return new CigramCatalogSync.Reply(manifestStatus, "", "");
                    if (!feed) return new CigramCatalogSync.Reply(404, "", "{\"ok\":false}");
                    if (!ignoreConditional && etag != null && etag.equals(etag())) return new CigramCatalogSync.Reply(304, etag(), "");
                    String body = new JSONObject().put("ok", true).put("schema", 1).put("rev", rev).put("records", records.size())
                            .put("ids_digest", lieAboutDigest ? "0000000000000000000000000000000000000000000000000000000000000000" : idsDigest())
                            .put("etag", fileEtag()).put("window_from", windowFrom).toString();
                    manifestBytes += body.length();
                    return new CigramCatalogSync.Reply(200, etag(), body);
                }
                changesCalls++;
                changeRequests.add(path);
                if (changesIo || (failChangesAfterPages >= 0 && changesCalls > failChangesAfterPages)) throw new IOException("reset");
                if (changesStatus != 200) return new CigramCatalogSync.Reply(changesStatus, "", "");
                long since = Long.parseLong(path.replaceAll(".*since=(\\d+).*", "$1"));
                int max = Integer.parseInt(path.replaceAll(".*max=(\\d+).*", "$1"));
                JSONObject head = new JSONObject().put("rev", rev).put("records", records.size())
                        .put("ids_digest", lieAboutDigest ? "1111111111111111111111111111111111111111111111111111111111111111" : idsDigest()).put("etag", fileEtag());
                if (since > rev || since < windowFrom) return ok(new JSONObject().put("ok", true).put("full", true).put("reason", "window").put("head", head));
                LinkedHashMap<String, JSONObject> up = new LinkedHashMap<String, JSONObject>();
                List<String> rm = new ArrayList<String>();
                long to = since; int count = 0;
                for (JSONObject e : log) {
                    if (e.getLong("rev") <= since) continue;
                    int size = e.getJSONArray("up").length() + e.getJSONArray("rm").length();
                    if (to > since && count + size > max) break;
                    for (int i = 0; i < e.getJSONArray("up").length(); i++) { JSONObject r = e.getJSONArray("up").getJSONObject(i); up.put(r.getString("id"), r); rm.remove(r.getString("id")); }
                    for (int i = 0; i < e.getJSONArray("rm").length(); i++) { String id = e.getJSONArray("rm").getString(i); rm.add(id); up.remove(id); }
                    count += size; to = e.getLong("rev");
                }
                JSONArray ua = new JSONArray(); for (JSONObject o : up.values()) ua.put(o);
                JSONArray ra = new JSONArray(); for (String id : rm) ra.put(id);
                return ok(new JSONObject().put("ok", true).put("from", since).put("to", to).put("more", to < rev).put("upserts", ua).put("removed", ra).put("head", head));
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private CigramCatalogSync.Reply ok(JSONObject o) { String s = o.toString(); changeBytes += s.length(); return new CigramCatalogSync.Reply(200, "", s); }

        @Override public CigramCatalogSync.Reply getFull(String etag) throws IOException {
            fullCalls++;
            if (fullIo) throw new IOException("timeout");
            if (fullStatus != 200) return new CigramCatalogSync.Reply(fullStatus, "", "");
            if (!ignoreConditional && etag != null && !etag.isEmpty() && etag.equals(fileEtag())) return new CigramCatalogSync.Reply(304, etag, "");
            String body = serveEmptyFull ? "{\"ok\":true,\"count\":0,\"movies\":[]}" : fullBodyOverride != null ? fullBodyOverride : fullDoc();
            fullBytes += body.length();
            return new CigramCatalogSync.Reply(200, fileEtag(), body);
        }
    }

    static final CigramCatalogSync.Pipeline IDENTITY = new CigramCatalogSync.Pipeline() {
        @Override public String normalizeRecords(String docJson) { return docJson; }
        @Override public CigramCatalogSync.Prepared prepare(String docJson) { return new CigramCatalogSync.Prepared(docJson, "[]"); }
    };

    private CigramCatalogStore store;
    private long now = 1_800_000_000_000L;

    @Before public void setUp() { store = new CigramCatalogStore(new File(tmp.getRoot(), "catalog")); }

    private CigramCatalogSync.Config cfg() { CigramCatalogSync.Config c = new CigramCatalogSync.Config(); c.now = now; return c; }
    private CigramCatalogSync.Outcome run(FakeServer s) { return CigramCatalogSync.syncOnce(store, s, IDENTITY, cfg()); }
    private CigramCatalogSync.Outcome run(FakeServer s, CigramCatalogSync.Config c) { return CigramCatalogSync.syncOnce(store, s, IDENTITY, c); }
    private int localRecords() { return CigramCatalogDelta.countRecords(store.load().display); }

    // ------------------------------------------------------------------------------------------------ the happy paths

    @Test public void firstInstallDownloadsOnceAndRecordsTheRevisionAndDigest() {
        FakeServer s = new FakeServer(100);
        CigramCatalogSync.Outcome o = run(s);
        assertEquals(o.toString(), CigramCatalogSync.Kind.UPDATED, o.kind);
        assertEquals("full", o.mode);
        assertEquals(1, s.fullCalls);
        CigramCatalogStore.Snapshot snap = store.load();
        assertEquals(100, localRecords());
        assertEquals(1L, snap.num(CigramCatalogSync.L_REV));
        assertEquals(s.idsDigest(), snap.str(CigramCatalogSync.S_IDS_DIGEST));
        assertEquals(100L, snap.num(CigramCatalogSync.L_RECORDS));
    }

    @Test public void nothingChangedCostsOneTinyRequestAndNoRewrite() {
        FakeServer s = new FakeServer(100);
        run(s);
        long gen = store.load().gen;
        long fullBefore = s.fullBytes;
        CigramCatalogSync.Outcome o = run(s);
        assertEquals(CigramCatalogSync.Kind.UP_TO_DATE, o.kind);
        assertEquals("manifest_304", o.mode);
        assertEquals(1, o.requests);
        assertEquals(1, s.fullCalls);
        assertEquals("no new generation was written", gen, store.load().gen);
        assertEquals(fullBefore, s.fullBytes);
    }

    @Test public void aNewMovieArrivesAsAOneRecordDeltaNotAFullDownload() throws Exception {
        FakeServer s = new FakeServer(500);
        run(s);
        s.add("m_new", "Brand New");
        CigramCatalogSync.Outcome o = run(s);
        assertEquals(o.toString(), CigramCatalogSync.Kind.UPDATED, o.kind);
        assertEquals("delta", o.mode);
        assertEquals(1, o.upserts);
        assertEquals(2, o.requests);                              // manifest + one changes page
        assertEquals(1, s.fullCalls);
        assertTrue(o.contentChanged);
        assertEquals(501, localRecords());
        assertNotNull(CigramCatalogDelta.byId(store.load().display).get("m_new"));
        assertEquals(2L, store.load().num(CigramCatalogSync.L_REV));
        assertTrue("the delta is tiny compared with the catalogue: " + s.changeBytes + " vs " + s.fullBytes, s.changeBytes * 20 < s.fullBytes);
    }

    @Test public void editAndDeleteTouchOnlyTheirOwnRecords() throws Exception {
        FakeServer s = new FakeServer(300);
        run(s);
        Map<String, JSONObject> before = CigramCatalogDelta.byId(store.load().display);
        s.edit("m5", "Edited five");
        s.remove("m7");
        CigramCatalogSync.Outcome o = run(s);
        assertEquals(o.toString(), CigramCatalogSync.Kind.UPDATED, o.kind);
        Map<String, JSONObject> after = CigramCatalogDelta.byId(store.load().display);
        assertEquals(299, after.size());
        assertEquals("Edited five", after.get("m5").optString("title"));
        assertFalse(after.containsKey("m7"));
        for (Map.Entry<String, JSONObject> e : before.entrySet()) {
            if (e.getKey().equals("m5") || e.getKey().equals("m7")) continue;
            assertEquals("untouched record " + e.getKey(), e.getValue().toString(), after.get(e.getKey()).toString());
        }
    }

    @Test public void contentIdsAreNeverChangedOrInvented() throws Exception {
        FakeServer s = new FakeServer(50);
        run(s);
        s.add("show_ep_9", "Episode"); s.edit("m1", "x"); s.remove("m2");
        run(s);
        List<String> ids = CigramCatalogDelta.distinctIds(CigramCatalogDelta.open(store.load().display).list);
        assertEquals(new java.util.TreeSet<String>(s.records.keySet()), new java.util.TreeSet<String>(ids));
    }

    @Test public void theSameDeltaAppliedTwiceGivesTheSameCatalogue() throws Exception {
        FakeServer s = new FakeServer(40);
        run(s);
        s.add("x1", "one"); s.edit("m3", "three");
        run(s);
        String once = store.load().display;
        // simulate a client that lost its revision (older than the content) and replays the same changes
        Map<String, Long> l = new java.util.HashMap<String, Long>(store.load().longs); l.put(CigramCatalogSync.L_REV, 1L);
        Map<String, String> st = new java.util.HashMap<String, String>(store.load().strings); st.put(CigramCatalogSync.S_MANIFEST_ETAG, "");
        store.updateProps(st, l);
        CigramCatalogSync.Outcome o = run(s);
        assertEquals(o.toString(), CigramCatalogSync.Kind.UPDATED, o.kind);
        assertEquals(CigramCatalogDelta.byId(once).keySet(), CigramCatalogDelta.byId(store.load().display).keySet());
        assertEquals(CigramCatalogDelta.byId(once).get("m3").toString(), CigramCatalogDelta.byId(store.load().display).get("m3").toString());
    }

    @Test public void aLongGapIsFetchedInPagesAndVerified() throws Exception {
        FakeServer s = new FakeServer(200);
        run(s);
        for (int i = 0; i < 12; i++) s.add("n" + i, "new " + i);
        CigramCatalogSync.Config c = cfg(); c.pageSize = 3;
        CigramCatalogSync.Outcome o = run(s, c);
        assertEquals(o.toString(), CigramCatalogSync.Kind.UPDATED, o.kind);
        assertTrue("several pages: " + s.changeRequests, s.changeRequests.size() >= 4);
        assertEquals(212, localRecords());
        assertEquals(13L, store.load().num(CigramCatalogSync.L_REV));
    }

    // ------------------------------------------------------------------------------------------------ failures keep the saved catalogue

    @Test public void timeoutsAndServerErrorsNeverTouchTheSavedCatalogue() {
        FakeServer s = new FakeServer(80);
        run(s);
        String saved = store.load().display;
        long gen = store.load().gen;
        s.manifestIo = true;
        assertEquals(CigramCatalogSync.Kind.FAILED, run(s).kind);
        s.manifestIo = false; s.manifestStatus = 502;
        CigramCatalogSync.Outcome o = run(s);
        assertEquals(CigramCatalogSync.Kind.FAILED, o.kind);
        assertTrue(o.retryable);
        s.manifestStatus = 503;
        assertEquals(CigramCatalogSync.Kind.FAILED, run(s).kind);
        assertEquals(saved, store.load().display);
        assertEquals(gen, store.load().gen);
    }

    @Test public void aServerThatAnswersAnEmptyCatalogueNeverEmptiesTheSavedOne() {
        FakeServer s = new FakeServer(80);
        run(s);
        String saved = store.load().display;
        s.feed = false;                                           // legacy path: /movies only
        s.serveEmptyFull = true;
        s.ignoreConditional = true;
        CigramCatalogSync.Outcome o = run(s);
        assertEquals(o.toString(), CigramCatalogSync.Kind.REJECTED, o.kind);
        assertEquals(saved, store.load().display);
        assertEquals(80, localRecords());
    }

    @Test public void malformedBodiesAreRejectedNotSaved() {
        FakeServer s = new FakeServer(80);
        run(s);
        String saved = store.load().display;
        s.feed = false; s.ignoreConditional = true;
        for (String bad : new String[]{"", "<html>Bad gateway</html>", "{\"ok\":false,\"error\":\"x\"}", "{\"ok\":true}", "[", "null"}) {
            s.fullBodyOverride = bad;
            CigramCatalogSync.Outcome o = run(s);
            assertTrue("body '" + bad + "' -> " + o, o.kind == CigramCatalogSync.Kind.REJECTED || o.kind == CigramCatalogSync.Kind.FAILED);
            assertEquals(saved, store.load().display);
        }
    }

    @Test public void aDeltaThatWouldEmptyTheCatalogueIsRefused() throws Exception {
        FakeServer s = new FakeServer(25);
        run(s);
        for (int i = 0; i < 25; i++) s.remove("m" + i);
        CigramCatalogSync.Outcome o = run(s);
        assertTrue(o.toString(), o.kind == CigramCatalogSync.Kind.REJECTED || o.kind == CigramCatalogSync.Kind.HELD);
        assertEquals(25, localRecords());
    }

    @Test public void aBigShrinkIsHeldAndOnlyAppliedWhenTheSameAnswerIsSeenAgainLater() throws Exception {
        FakeServer s = new FakeServer(100);
        run(s);
        s.feed = false; s.ignoreConditional = true;               // a full snapshot with only 30 of the 100 records
        for (int i = 30; i < 100; i++) s.records.remove("m" + i);
        s.rev++;
        CigramCatalogSync.Outcome first = run(s);
        assertEquals(first.toString(), CigramCatalogSync.Kind.HELD, first.kind);
        assertEquals(100, localRecords());
        now += 60_000L;                                           // one minute later: still held
        assertEquals(CigramCatalogSync.Kind.HELD, run(s).kind);
        assertEquals(100, localRecords());
        now += 6L * 60_000L;                                      // the same answer after the confirmation window: a real bulk delete
        CigramCatalogSync.Outcome third = run(s);
        assertEquals(third.toString(), CigramCatalogSync.Kind.UPDATED, third.kind);
        assertEquals(30, localRecords());
    }

    // ------------------------------------------------------------------------------------------------ resume, verification, fallbacks

    @Test public void anInterruptedSyncResumesFromTheLastReceivedPage() throws Exception {
        FakeServer s = new FakeServer(100);
        run(s);
        for (int i = 0; i < 9; i++) s.add("n" + i, "new " + i);
        CigramCatalogSync.Config c = cfg(); c.pageSize = 3;
        s.failChangesAfterPages = 1;                              // page 1 arrives, then the connection drops
        CigramCatalogSync.Outcome broken = run(s, c);
        assertEquals(broken.toString(), CigramCatalogSync.Kind.FAILED, broken.kind);
        assertEquals(100, localRecords());                        // nothing half-applied
        assertNotNull("progress is saved", store.readAux("sync_pending.json"));
        s.failChangesAfterPages = -1;
        s.changeRequests.clear();
        CigramCatalogSync.Outcome ok = run(s, c);
        assertEquals(ok.toString(), CigramCatalogSync.Kind.UPDATED, ok.kind);
        assertEquals(109, localRecords());
        assertTrue("it did NOT start again from the base revision: " + s.changeRequests, !s.changeRequests.get(0).contains("since=1&"));
        assertNull(store.readAux("sync_pending.json"));
    }

    @Test public void aKilledProcessResumesToo() throws Exception {
        FakeServer s = new FakeServer(100);
        run(s);
        for (int i = 0; i < 9; i++) s.add("n" + i, "new " + i);
        CigramCatalogSync.Config c = cfg(); c.pageSize = 3;
        s.failChangesAfterPages = 2;
        run(s, c);
        store = new CigramCatalogStore(new File(tmp.getRoot(), "catalog"));   // the app was killed: a new process reads the disk
        s.failChangesAfterPages = -1; s.changeRequests.clear();
        CigramCatalogSync.Outcome ok = run(s, c);
        assertEquals(ok.toString(), CigramCatalogSync.Kind.UPDATED, ok.kind);
        assertEquals(109, localRecords());
        assertFalse(s.changeRequests.get(0).contains("since=1&"));
    }

    @Test public void aDeltaThatDoesNotMatchTheServersDigestIsNeverCommittedTheFullSnapshotIsUsed() throws Exception {
        FakeServer s = new FakeServer(60);
        run(s);
        s.add("n1", "new");
        s.lieAboutDigest = true;                                  // a feed bug: head digest does not describe the content
        CigramCatalogSync.Outcome o = run(s);
        // the full snapshot is the authority; it is committed, the wrong delta never was
        assertEquals(o.toString(), CigramCatalogSync.Kind.UPDATED, o.kind);
        assertEquals("full", o.mode);
        assertTrue(o.reason.contains("verify_failed"));
        assertEquals(61, localRecords());
    }

    @Test public void aServerThatSaysFullMakesTheClientDownloadTheCatalogue() throws Exception {
        FakeServer s = new FakeServer(60);
        run(s);
        s.add("a", "a"); s.reset();                               // a bulk import: older clients must download once
        int fullBefore = s.fullCalls;
        CigramCatalogSync.Outcome o = run(s);
        assertEquals(o.toString(), CigramCatalogSync.Kind.UPDATED, o.kind);
        assertEquals(fullBefore + 1, s.fullCalls);
        assertEquals(61, localRecords());
        assertEquals(s.rev, store.load().num(CigramCatalogSync.L_REV));
    }

    @Test public void aWorkerWithoutTheFeedStillWorksThroughTheConditionalFullDownload() {
        FakeServer s = new FakeServer(70);
        s.feed = false;
        assertEquals(CigramCatalogSync.Kind.UPDATED, run(s).kind);
        assertEquals(0L, store.load().num(CigramCatalogSync.L_REV));
        CigramCatalogSync.Outcome again = run(s);
        assertEquals(again.toString(), CigramCatalogSync.Kind.UP_TO_DATE, again.kind);
        assertEquals("full_304", again.mode);
        assertEquals(70, localRecords());
    }

    @Test public void aServerThatIgnoresIfNoneMatchStillCostsNoParseNoRewrite() {
        FakeServer s = new FakeServer(70);
        s.feed = false; s.ignoreConditional = true;
        run(s);
        long gen = store.load().gen;
        CigramCatalogSync.Outcome o = run(s);
        assertEquals(o.toString(), CigramCatalogSync.Kind.UP_TO_DATE, o.kind);
        assertEquals("same_body", o.mode);
        assertEquals(gen, store.load().gen);
    }

    @Test public void aLegacySnapshotWithoutARevisionIsAdoptedByOneFullDownloadThenDeltas() throws Exception {
        FakeServer s = new FakeServer(90);
        s.feed = false; s.ignoreConditional = true;
        run(s);                                                   // what the old app saved: no revision
        s.feed = true; s.ignoreConditional = false;
        CigramCatalogSync.Outcome o = run(s);
        assertEquals(o.toString(), CigramCatalogSync.Kind.UP_TO_DATE, o.kind);        // same body: adopted without a rewrite
        assertEquals(s.rev, store.load().num(CigramCatalogSync.L_REV));
        s.add("fresh", "fresh");
        assertEquals("delta", run(s).mode);
    }

    @Test public void theWeeklySelfCheckRunsOnWifiOnlyAndHealsDrift() throws Exception {
        FakeServer s = new FakeServer(50);
        run(s);
        // the server's file changed WITHOUT a feed entry that the client could see (an un-tracked change): same rev, same ids
        s.records.put("m3", FakeServer.rec("m3", "Changed silently", 1003L));
        now += 8L * 24L * 3600L * 1000L;
        CigramCatalogSync.Config metered = cfg(); metered.metered = true;
        assertEquals("metered: the self check is skipped", CigramCatalogSync.Kind.UP_TO_DATE, run(s, metered).kind);
        assertFalse("Changed silently".equals(CigramCatalogDelta.byId(store.load().display).get("m3").optString("title")));
        CigramCatalogSync.Outcome o = run(s);                     // unmetered: audit
        assertEquals(o.toString(), CigramCatalogSync.Kind.UPDATED, o.kind);
        assertEquals("Changed silently", CigramCatalogDelta.byId(store.load().display).get("m3").optString("title"));
    }

    // ------------------------------------------------------------------------------------------------ digest contract with the Worker

    @Test public void theIdDigestMatchesTheWorkersImplementationByteForByte() {
        // computed with node: sha256(["m1","m10","m2","série_1","ep_1_3","Z","مسلسل_5"].sort().join("\n"))
        List<String> ids = java.util.Arrays.asList("m1", "m10", "m2", "série_1", "ep_1_3", "Z", "مسلسل_5");
        assertEquals("f8c8beae0a2fad3fdc6b778ee1f3611611152671bc3e8db0ac77103847211dd" + "f", CigramCatalogDelta.idsDigest(ids));
    }

    @Test public void newRecordsAreInsertedNewestFirstAndEverythingElseKeepsItsPosition() throws Exception {
        String base = "{\"ok\":true,\"count\":88,\"movies\":[{\"id\":\"a\",\"created_at_ms\":300},{\"id\":\"b\",\"created_at_ms\":200},{\"id\":\"c\",\"created_at_ms\":100}]}";
        JSONArray ups = new JSONArray("[{\"id\":\"n\",\"created_at_ms\":250},{\"id\":\"b\",\"created_at_ms\":200,\"title\":\"B2\"}]");
        CigramCatalogDelta.Merge m = CigramCatalogDelta.apply(base, ups, new JSONArray("[\"c\"]"));
        JSONArray out = new JSONObject(m.json).getJSONArray("movies");
        assertEquals("a", out.getJSONObject(0).getString("id"));
        assertEquals("n", out.getJSONObject(1).getString("id"));
        assertEquals("b", out.getJSONObject(2).getString("id"));
        assertEquals("B2", out.getJSONObject(2).getString("title"));
        assertEquals(3, out.length());
        assertEquals(88, new JSONObject(m.json).getInt("count"));       // the envelope is untouched
    }
}
