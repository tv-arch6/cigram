package com.my.newproject;

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
public class CgCatSyncTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    // ------------------------------------------------------------------------------------------------ fake server
    static final class FakeServer implements CgCatSync.Transport {
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

        String idsDigest() { return CgCatDelta.idsDigest(records.keySet()); }

        String fullDoc() {
            try {
                JSONArray a = new JSONArray();
                for (JSONObject o : records.values()) a.put(o);
                return new JSONObject().put("ok", true).put("count", 88).put("movies", a).toString();
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Override public CgCatSync.Reply get(String path, String etag) throws IOException {
            try {
                if (path.startsWith("/catalog/manifest")) {
                    manifestCalls++;
                    if (manifestIo) throw new IOException("timeout");
                    if (manifestStatus != 200) return new CgCatSync.Reply(manifestStatus, "", "");
                    if (!feed) return new CgCatSync.Reply(404, "", "{\"ok\":false}");
                    if (!ignoreConditional && etag != null && etag.equals(etag())) return new CgCatSync.Reply(304, etag(), "");
                    String body = new JSONObject().put("ok", true).put("schema", 1).put("rev", rev).put("records", records.size())
                            .put("ids_digest", lieAboutDigest ? "0000000000000000000000000000000000000000000000000000000000000000" : idsDigest())
                            .put("etag", fileEtag()).put("window_from", windowFrom).toString();
                    manifestBytes += body.length();
                    return new CgCatSync.Reply(200, etag(), body);
                }
                changesCalls++;
                changeRequests.add(path);
                if (changesIo || (failChangesAfterPages >= 0 && changesCalls > failChangesAfterPages)) throw new IOException("reset");
                if (changesStatus != 200) return new CgCatSync.Reply(changesStatus, "", "");
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

        private CgCatSync.Reply ok(JSONObject o) { String s = o.toString(); changeBytes += s.length(); return new CgCatSync.Reply(200, "", s); }

        @Override public CgCatSync.Reply getFull(String etag) throws IOException {
            fullCalls++;
            if (fullIo) throw new IOException("timeout");
            if (fullStatus != 200) return new CgCatSync.Reply(fullStatus, "", "");
            if (!ignoreConditional && etag != null && !etag.isEmpty() && etag.equals(fileEtag())) return new CgCatSync.Reply(304, etag, "");
            String body = serveEmptyFull ? "{\"ok\":true,\"count\":0,\"movies\":[]}" : fullBodyOverride != null ? fullBodyOverride : fullDoc();
            fullBytes += body.length();
            return new CgCatSync.Reply(200, fileEtag(), body);
        }
    }

    static final CgCatSync.Pipeline IDENTITY = new CgCatSync.Pipeline() {
        @Override public String normalizeRecords(String docJson) { return docJson; }
        @Override public CgCatSync.Prepared prepare(String docJson) { return new CgCatSync.Prepared(docJson, "[]"); }
    };

    private CgCatStore store;
    private long now = 1_800_000_000_000L;

    @Before public void setUp() { store = new CgCatStore(new File(tmp.getRoot(), "catalog")); }

    private CgCatSync.Config cfg() { CgCatSync.Config c = new CgCatSync.Config(); c.now = now; return c; }
    private CgCatSync.Outcome run(FakeServer s) { return CgCatSync.syncOnce(store, s, IDENTITY, cfg()); }
    private CgCatSync.Outcome run(FakeServer s, CgCatSync.Config c) { return CgCatSync.syncOnce(store, s, IDENTITY, c); }
    private int localRecords() { return CgCatDelta.countRecords(store.load().display); }

    // ------------------------------------------------------------------------------------------------ the happy paths

    @Test public void firstInstallDownloadsOnceAndRecordsTheRevisionAndDigest() {
        FakeServer s = new FakeServer(100);
        CgCatSync.Outcome o = run(s);
        assertEquals(o.toString(), CgCatSync.Kind.UPDATED, o.kind);
        assertEquals("full", o.mode);
        assertEquals(1, s.fullCalls);
        CgCatStore.Snapshot snap = store.load();
        assertEquals(100, localRecords());
        assertEquals(1L, snap.num(CgCatSync.L_REV));
        assertEquals(s.idsDigest(), snap.str(CgCatSync.S_IDS_DIGEST));
        assertEquals(100L, snap.num(CgCatSync.L_RECORDS));
    }

    @Test public void nothingChangedCostsOneTinyRequestAndNoRewrite() {
        FakeServer s = new FakeServer(100);
        run(s);
        long gen = store.load().gen;
        long fullBefore = s.fullBytes;
        CgCatSync.Outcome o = run(s);
        assertEquals(CgCatSync.Kind.UP_TO_DATE, o.kind);
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
        CgCatSync.Outcome o = run(s);
        assertEquals(o.toString(), CgCatSync.Kind.UPDATED, o.kind);
        assertEquals("delta", o.mode);
        assertEquals(1, o.upserts);
        assertEquals(2, o.requests);                              // manifest + one changes page
        assertEquals(1, s.fullCalls);
        assertTrue(o.contentChanged);
        assertEquals(501, localRecords());
        assertNotNull(CgCatDelta.byId(store.load().display).get("m_new"));
        assertEquals(2L, store.load().num(CgCatSync.L_REV));
        assertTrue("the delta is tiny compared with the catalogue: " + s.changeBytes + " vs " + s.fullBytes, s.changeBytes * 20 < s.fullBytes);
    }

    @Test public void editAndDeleteTouchOnlyTheirOwnRecords() throws Exception {
        FakeServer s = new FakeServer(300);
        run(s);
        Map<String, JSONObject> before = CgCatDelta.byId(store.load().display);
        s.edit("m5", "Edited five");
        s.remove("m7");
        CgCatSync.Outcome o = run(s);
        assertEquals(o.toString(), CgCatSync.Kind.UPDATED, o.kind);
        Map<String, JSONObject> after = CgCatDelta.byId(store.load().display);
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
        List<String> ids = CgCatDelta.distinctIds(CgCatDelta.open(store.load().display).list);
        assertEquals(new java.util.TreeSet<String>(s.records.keySet()), new java.util.TreeSet<String>(ids));
    }

    @Test public void theSameDeltaAppliedTwiceGivesTheSameCatalogue() throws Exception {
        FakeServer s = new FakeServer(40);
        run(s);
        s.add("x1", "one"); s.edit("m3", "three");
        run(s);
        String once = store.load().display;
        // simulate a client that lost its revision (older than the content) and replays the same changes
        Map<String, Long> l = new java.util.HashMap<String, Long>(store.load().longs); l.put(CgCatSync.L_REV, 1L);
        Map<String, String> st = new java.util.HashMap<String, String>(store.load().strings); st.put(CgCatSync.S_MANIFEST_ETAG, "");
        store.updateProps(st, l);
        CgCatSync.Outcome o = run(s);
        assertEquals(o.toString(), CgCatSync.Kind.UPDATED, o.kind);
        assertEquals(CgCatDelta.byId(once).keySet(), CgCatDelta.byId(store.load().display).keySet());
        assertEquals(CgCatDelta.byId(once).get("m3").toString(), CgCatDelta.byId(store.load().display).get("m3").toString());
    }

    @Test public void aLongGapIsFetchedInPagesAndVerified() throws Exception {
        FakeServer s = new FakeServer(200);
        run(s);
        for (int i = 0; i < 12; i++) s.add("n" + i, "new " + i);
        CgCatSync.Config c = cfg(); c.pageSize = 3;
        CgCatSync.Outcome o = run(s, c);
        assertEquals(o.toString(), CgCatSync.Kind.UPDATED, o.kind);
        assertTrue("several pages: " + s.changeRequests, s.changeRequests.size() >= 4);
        assertEquals(212, localRecords());
        assertEquals(13L, store.load().num(CgCatSync.L_REV));
    }

    // ------------------------------------------------------------------------------------------------ failures keep the saved catalogue

    @Test public void timeoutsAndServerErrorsNeverTouchTheSavedCatalogue() {
        FakeServer s = new FakeServer(80);
        run(s);
        String saved = store.load().display;
        long gen = store.load().gen;
        s.manifestIo = true;
        assertEquals(CgCatSync.Kind.FAILED, run(s).kind);
        s.manifestIo = false; s.manifestStatus = 502;
        CgCatSync.Outcome o = run(s);
        assertEquals(CgCatSync.Kind.FAILED, o.kind);
        assertTrue(o.retryable);
        s.manifestStatus = 503;
        assertEquals(CgCatSync.Kind.FAILED, run(s).kind);
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
        CgCatSync.Outcome o = run(s);
        assertEquals(o.toString(), CgCatSync.Kind.REJECTED, o.kind);
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
            CgCatSync.Outcome o = run(s);
            assertTrue("body '" + bad + "' -> " + o, o.kind == CgCatSync.Kind.REJECTED || o.kind == CgCatSync.Kind.FAILED);
            assertEquals(saved, store.load().display);
        }
    }

    @Test public void aDeltaThatWouldEmptyTheCatalogueIsRefused() throws Exception {
        FakeServer s = new FakeServer(25);
        run(s);
        for (int i = 0; i < 25; i++) s.remove("m" + i);
        CgCatSync.Outcome o = run(s);
        assertTrue(o.toString(), o.kind == CgCatSync.Kind.REJECTED || o.kind == CgCatSync.Kind.HELD);
        assertEquals(25, localRecords());
    }

    @Test public void aBigShrinkIsHeldAndOnlyAppliedWhenTheSameAnswerIsSeenAgainLater() throws Exception {
        FakeServer s = new FakeServer(100);
        run(s);
        s.feed = false; s.ignoreConditional = true;               // a full snapshot with only 30 of the 100 records
        for (int i = 30; i < 100; i++) s.records.remove("m" + i);
        s.rev++;
        CgCatSync.Outcome first = run(s);
        assertEquals(first.toString(), CgCatSync.Kind.HELD, first.kind);
        assertEquals(100, localRecords());
        now += 60_000L;                                           // one minute later: still held
        assertEquals(CgCatSync.Kind.HELD, run(s).kind);
        assertEquals(100, localRecords());
        now += 6L * 60_000L;                                      // the same answer after the confirmation window: a real bulk delete
        CgCatSync.Outcome third = run(s);
        assertEquals(third.toString(), CgCatSync.Kind.UPDATED, third.kind);
        assertEquals(30, localRecords());
    }

    // ------------------------------------------------------------------------------------------------ resume, verification, fallbacks

    @Test public void anInterruptedSyncResumesFromTheLastReceivedPage() throws Exception {
        FakeServer s = new FakeServer(100);
        run(s);
        for (int i = 0; i < 9; i++) s.add("n" + i, "new " + i);
        CgCatSync.Config c = cfg(); c.pageSize = 3;
        s.failChangesAfterPages = 1;                              // page 1 arrives, then the connection drops
        CgCatSync.Outcome broken = run(s, c);
        assertEquals(broken.toString(), CgCatSync.Kind.FAILED, broken.kind);
        assertEquals(100, localRecords());                        // nothing half-applied
        assertNotNull("progress is saved", store.readAux("sync_pending.json"));
        s.failChangesAfterPages = -1;
        s.changeRequests.clear();
        CgCatSync.Outcome ok = run(s, c);
        assertEquals(ok.toString(), CgCatSync.Kind.UPDATED, ok.kind);
        assertEquals(109, localRecords());
        assertTrue("it did NOT start again from the base revision: " + s.changeRequests, !s.changeRequests.get(0).contains("since=1&"));
        assertNull(store.readAux("sync_pending.json"));
    }

    @Test public void aKilledProcessResumesToo() throws Exception {
        FakeServer s = new FakeServer(100);
        run(s);
        for (int i = 0; i < 9; i++) s.add("n" + i, "new " + i);
        CgCatSync.Config c = cfg(); c.pageSize = 3;
        s.failChangesAfterPages = 2;
        run(s, c);
        store = new CgCatStore(new File(tmp.getRoot(), "catalog"));   // the app was killed: a new process reads the disk
        s.failChangesAfterPages = -1; s.changeRequests.clear();
        CgCatSync.Outcome ok = run(s, c);
        assertEquals(ok.toString(), CgCatSync.Kind.UPDATED, ok.kind);
        assertEquals(109, localRecords());
        assertFalse(s.changeRequests.get(0).contains("since=1&"));
    }

    @Test public void aDeltaThatDoesNotMatchTheServersDigestIsNeverCommittedTheFullSnapshotIsUsed() throws Exception {
        FakeServer s = new FakeServer(60);
        run(s);
        s.add("n1", "new");
        s.lieAboutDigest = true;                                  // a feed bug: head digest does not describe the content
        CgCatSync.Outcome o = run(s);
        // the full snapshot is the authority; it is committed, the wrong delta never was
        assertEquals(o.toString(), CgCatSync.Kind.UPDATED, o.kind);
        assertEquals("full", o.mode);
        assertTrue(o.reason.contains("verify_failed"));
        assertEquals(61, localRecords());
    }

    @Test public void aServerThatSaysFullMakesTheClientDownloadTheCatalogue() throws Exception {
        FakeServer s = new FakeServer(60);
        run(s);
        s.add("a", "a"); s.reset();                               // a bulk import: older clients must download once
        int fullBefore = s.fullCalls;
        CgCatSync.Outcome o = run(s);
        assertEquals(o.toString(), CgCatSync.Kind.UPDATED, o.kind);
        assertEquals(fullBefore + 1, s.fullCalls);
        assertEquals(61, localRecords());
        assertEquals(s.rev, store.load().num(CgCatSync.L_REV));
    }

    @Test public void aWorkerWithoutTheFeedStillWorksThroughTheConditionalFullDownload() {
        FakeServer s = new FakeServer(70);
        s.feed = false;
        assertEquals(CgCatSync.Kind.UPDATED, run(s).kind);
        assertEquals(0L, store.load().num(CgCatSync.L_REV));
        CgCatSync.Outcome again = run(s);
        assertEquals(again.toString(), CgCatSync.Kind.UP_TO_DATE, again.kind);
        assertEquals("full_304", again.mode);
        assertEquals(70, localRecords());
    }

    @Test public void aServerThatIgnoresIfNoneMatchStillCostsNoParseNoRewrite() {
        FakeServer s = new FakeServer(70);
        s.feed = false; s.ignoreConditional = true;
        run(s);
        long gen = store.load().gen;
        CgCatSync.Outcome o = run(s);
        assertEquals(o.toString(), CgCatSync.Kind.UP_TO_DATE, o.kind);
        assertEquals("same_body", o.mode);
        assertEquals(gen, store.load().gen);
    }

    @Test public void aLegacySnapshotWithoutARevisionIsAdoptedByOneFullDownloadThenDeltas() throws Exception {
        FakeServer s = new FakeServer(90);
        s.feed = false; s.ignoreConditional = true;
        run(s);                                                   // what the old app saved: no revision
        s.feed = true; s.ignoreConditional = false;
        CgCatSync.Outcome o = run(s);
        assertEquals(o.toString(), CgCatSync.Kind.UP_TO_DATE, o.kind);        // same body: adopted without a rewrite
        assertEquals(s.rev, store.load().num(CgCatSync.L_REV));
        s.add("fresh", "fresh");
        assertEquals("delta", run(s).mode);
    }

    @Test public void theWeeklySelfCheckRunsOnWifiOnlyAndHealsDrift() throws Exception {
        FakeServer s = new FakeServer(50);
        run(s);
        // the server's file changed WITHOUT a feed entry that the client could see (an un-tracked change): same rev, same ids
        s.records.put("m3", FakeServer.rec("m3", "Changed silently", 1003L));
        now += 8L * 24L * 3600L * 1000L;
        CgCatSync.Config metered = cfg(); metered.metered = true;
        assertEquals("metered: the self check is skipped", CgCatSync.Kind.UP_TO_DATE, run(s, metered).kind);
        assertFalse("Changed silently".equals(CgCatDelta.byId(store.load().display).get("m3").optString("title")));
        CgCatSync.Outcome o = run(s);                     // unmetered: audit
        assertEquals(o.toString(), CgCatSync.Kind.UPDATED, o.kind);
        assertEquals("Changed silently", CgCatDelta.byId(store.load().display).get("m3").optString("title"));
    }

    // ------------------------------------------------------------------------------------------------ digest contract with the Worker

    @Test public void theIdDigestMatchesTheWorkersImplementationByteForByte() {
        // computed with node: sha256(["m1","m10","m2","série_1","ep_1_3","Z","مسلسل_5"].sort().join("\n"))
        List<String> ids = java.util.Arrays.asList("m1", "m10", "m2", "série_1", "ep_1_3", "Z", "مسلسل_5");
        assertEquals("f8c8beae0a2fad3fdc6b778ee1f3611611152671bc3e8db0ac77103847211dd" + "f", CgCatDelta.idsDigest(ids));
    }

    @Test public void newRecordsAreInsertedNewestFirstAndEverythingElseKeepsItsPosition() throws Exception {
        String base = "{\"ok\":true,\"count\":88,\"movies\":[{\"id\":\"a\",\"created_at_ms\":300},{\"id\":\"b\",\"created_at_ms\":200},{\"id\":\"c\",\"created_at_ms\":100}]}";
        JSONArray ups = new JSONArray("[{\"id\":\"n\",\"created_at_ms\":250},{\"id\":\"b\",\"created_at_ms\":200,\"title\":\"B2\"}]");
        CgCatDelta.Merge m = CgCatDelta.apply(base, ups, new JSONArray("[\"c\"]"));
        JSONArray out = new JSONObject(m.json).getJSONArray("movies");
        assertEquals("a", out.getJSONObject(0).getString("id"));
        assertEquals("n", out.getJSONObject(1).getString("id"));
        assertEquals("b", out.getJSONObject(2).getString("id"));
        assertEquals("B2", out.getJSONObject(2).getString("title"));
        assertEquals(3, out.length());
        assertEquals(88, new JSONObject(m.json).getInt("count"));       // the envelope is untouched
    }
}
