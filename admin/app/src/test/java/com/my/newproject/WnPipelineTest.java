package com.my.newproject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Android pipeline against the REAL Worker code (worker/dist, in-memory R2) over real HTTP; only TMDB / VidBolt /
 * subtitle providers are mocked (worker/test/serve.mjs). Skipped when node or the built Worker is not available.
 */
public class WnPipelineTest {

    private static Process server;
    private static String base;

    @BeforeClass
    public static void startWorker() throws Exception {
        String root = System.getProperty("repo.root", System.getProperty("user.dir"));
        java.io.File script = new java.io.File(root, "worker/test/serve.mjs");
        java.io.File dist = new java.io.File(root, "worker/dist/cigram-admin-api.worker.js");
        Assume.assumeTrue("worker test server not found (run `npm run build` in worker/)", script.exists() && dist.exists());
        try {
            ProcessBuilder pb = new ProcessBuilder("node", script.getAbsolutePath());
            pb.redirectErrorStream(true);
            server = pb.start();
        } catch (Exception e) {
            Assume.assumeNoException("node is not installed", e);
        }
        final BufferedReader out = new BufferedReader(new InputStreamReader(server.getInputStream(), "UTF-8"));
        String line;
        long end = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < end && (line = out.readLine()) != null) {
            if (line.startsWith("PORT=")) {
                base = "http://127.0.0.1:" + line.substring(5).trim();
                break;
            }
        }
        Assume.assumeTrue("worker test server did not start", base != null);
        WnApi.use(new WnApi.HttpBackend(base, "test-admin-token"));
    }

    @AfterClass
    public static void stopWorker() {
        WnApi.use(null);
        if (server != null) server.destroy();
    }

    @Before
    public void reset() throws Exception {
        raw("POST", "/__reset", "{}");
    }

    @After
    public void noop() { }

    // ------------------------------------------------------------------ helpers

    private static String raw(String method, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod(method);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        return CgHttp.readAll(in, 8 * 1024 * 1024);
    }

    private static JSONArray library() throws Exception {
        return new JSONArray(raw("GET", "/__library", null));
    }

    private static JSONObject card(String type, long tmdb, String title) throws Exception {
        return new JSONObject().put("type", type).put("tmdb_id", tmdb).put("title", title);
    }

    private static WnPipeline.Options ack() {
        WnPipeline.Options o = new WnPipeline.Options();
        o.flags.ackUnverified = true;
        o.flags.notify = false;
        return o;
    }

    private static final class Log implements WnPipeline.Listener {
        final List<String> steps = new ArrayList<>();
        final AtomicBoolean cancel = new AtomicBoolean(false);
        int cancelAfter = -1;

        @Override public synchronized void step(String key, String text) {
            steps.add(key + " | " + text);
            if (cancelAfter >= 0 && steps.size() >= cancelAfter) cancel.set(true);
        }
        @Override public void progress(String key, int done, int total) { }
        @Override public boolean cancelled() { return cancel.get(); }
    }

    private static int count(String type) throws Exception {
        int n = 0;
        JSONArray lib = library();
        for (int i = 0; i < lib.length(); i++) if (type.equals(lib.getJSONObject(i).optString("type"))) n++;
        return n;
    }

    private static JSONObject byId(String id) throws Exception {
        JSONArray lib = library();
        for (int i = 0; i < lib.length(); i++) if (id.equals(lib.getJSONObject(i).optString("id"))) return lib.getJSONObject(i);
        return null;
    }

    // ------------------------------------------------------------------ movie

    @Test
    public void movieIsFoundValidatedWithSubtitleAndImported() throws Exception {
        WnPipeline.Options o = ack();
        o.placement.sectionId = "action";
        Log log = new Log();
        WnPipeline.Result r = WnPipeline.runMovie(card("movie", 1001, "فيلم 1001"), o, log);
        assertEquals(r.message + " " + r.notes, "imported", r.state);
        assertEquals(1, r.created);
        assertEquals(1, count("movie"));
        JSONObject m = library().getJSONObject(0);
        assertEquals("https://vidbolt.pro/movie/1001", m.getString("video"));
        assertEquals("action", m.getString("section_id"));
        assertTrue("an Arabic subtitle was fetched, validated and linked", m.getString("subtitle").endsWith(".ar.srt"));
        assertTrue(log.steps.size() >= 4);
        assertEquals(1, r.undoIds.size());
    }

    @Test
    public void unprovableEmbedWaitsForTheAcknowledgementAndWritesNothing() throws Exception {
        WnPipeline.Options o = new WnPipeline.Options();
        o.flags.notify = false;
        WnPipeline.Result r = WnPipeline.runMovie(card("movie", 1001, "x"), o, new Log());
        assertEquals("needs_ack", r.state);
        assertEquals(0, library().length());
        o.flags.ackUnverified = true;
        assertEquals("imported", WnPipeline.runMovie(card("movie", 1001, "x"), o, new Log()).state);
    }

    @Test
    public void missingVideoIsReportedAndNothingIsImported() throws Exception {
        raw("POST", "/__scenario", "{\"vidbolt_404\":[\"/movie/1005\"]}");
        WnPipeline.Result r = WnPipeline.runMovie(card("movie", 1005, "x"), ack(), new Log());
        assertEquals("missing_video", r.state);
        assertEquals("video", r.stage);
        assertEquals(0, library().length());
    }

    @Test
    public void duplicateIsDetectedOnTheSecondRun() throws Exception {
        assertEquals("imported", WnPipeline.runMovie(card("movie", 1004, "x"), ack(), new Log()).state);
        WnPipeline.Result again = WnPipeline.runMovie(card("movie", 1004, "x"), ack(), new Log());
        assertEquals("duplicate", again.state);
        assertEquals(1, count("movie"));
    }

    @Test
    public void newCategoryIsCreatedOnceThroughTheImport() throws Exception {
        WnPipeline.Options o = ack();
        o.placement.newSection = "أفلام العيد";
        o.placement.newSectionType = "movie";
        o.subtitles = false;
        assertEquals("imported", WnPipeline.runMovie(card("movie", 1001, "x"), o, new Log()).state);
        assertEquals("imported", WnPipeline.runMovie(card("movie", 1004, "x"), o, new Log()).state);
        JSONArray lib = library();
        assertEquals(lib.getJSONObject(0).getString("section_id"), lib.getJSONObject(1).getString("section_id"));
        assertEquals("أفلام العيد", lib.getJSONObject(0).getString("section_name"));
    }

    @Test
    public void cancelStopsBeforeAnythingIsWritten() throws Exception {
        Log log = new Log();
        log.cancelAfter = 1;
        WnPipeline.Result r = WnPipeline.runMovie(card("movie", 1001, "x"), ack(), log);
        assertEquals("cancelled", r.state);
        assertEquals(0, library().length());
    }

    @Test
    public void bulkLoopContinuesAfterOneTitleFails() throws Exception {
        raw("POST", "/__scenario", "{\"vidbolt_404\":[\"/movie/1005\"]}");
        long[] ids = {1005, 1001, 1004};
        List<String> states = new ArrayList<>();
        for (long id : ids) states.add(WnPipeline.run(card("movie", id, "x"), ack(), new Log()).state);
        assertEquals(Arrays.asList("missing_video", "imported", "imported"), states);
        assertEquals(2, count("movie"));
        // retrying only the failed one (still unavailable) fails the same way and leaves the others alone
        assertEquals("missing_video", WnPipeline.run(card("movie", 1005, "x"), ack(), new Log()).state);
        assertEquals(2, count("movie"));
    }

    // ------------------------------------------------------------------ series

    @Test
    public void completeSeriesImportsEveryAiredEpisodeWithItsOwnUrl() throws Exception {
        WnPipeline.Options o = ack();
        o.subtitles = false;
        o.placement.sectionId = "drama";
        WnPipeline.Result r = WnPipeline.runSeries(card("series", 2001, "المسلسل"), o, new Log());
        assertEquals(r.message + r.notes, "imported", r.state);
        assertEquals("S1 e1-3 and S2 e1 (S2 e2 has not aired)", 4, r.created);
        assertEquals(1, count("series"));
        assertEquals(4, count("episode"));
        Map<String, String> expected = new HashMap<>();
        expected.put("2001_s1e1", "https://vidbolt.pro/tv/2001/1/1");
        expected.put("2001_s1e2", "https://vidbolt.pro/tv/2001/1/2");
        expected.put("2001_s1e3", "https://vidbolt.pro/tv/2001/1/3");
        expected.put("2001_s2e1", "https://vidbolt.pro/tv/2001/2/1");
        for (Map.Entry<String, String> e : expected.entrySet()) {
            JSONObject ep = byId(e.getKey());
            assertEquals(e.getKey(), e.getValue(), ep.getString("video"));
            assertEquals("series_2001", ep.getString("series_id"));
        }
        boolean noted = false;
        for (String n : r.notes) if (n.contains("S02E02")) noted = true;
        assertTrue("the unaired episode is reported, not silently dropped", noted);
    }

    @Test
    public void selectedEpisodesOnlyThenTheRestSkippingWhatExists() throws Exception {
        WnPipeline.Options o = ack();
        o.subtitles = false;
        o.episodes = new HashMap<>();
        o.episodes.put(1, Arrays.asList(2));
        WnPipeline.Result first = WnPipeline.runSeries(card("series", 2001, "x"), o, new Log());
        assertEquals("imported", first.state);
        assertEquals(1, first.created);
        assertEquals(1, count("episode"));
        assertEquals("2001_s1e2", library().getJSONObject(0).getString("id").equals("series_2001") ? library().getJSONObject(1).getString("id") : library().getJSONObject(0).getString("id"));

        WnPipeline.Options rest = ack();
        rest.subtitles = false;
        WnPipeline.Result second = WnPipeline.runSeries(card("series", 2001, "x"), rest, new Log());
        assertEquals("imported", second.state);
        assertEquals("S1 e1 + e3 + S2 e1", 3, second.created);
        assertEquals(1, second.skippedExisting);
        assertEquals(4, count("episode"));
        WnPipeline.Result third = WnPipeline.runSeries(card("series", 2001, "x"), ack(), new Log());
        assertEquals("duplicate", third.state);
        assertEquals(4, count("episode"));
    }

    @Test
    public void oneEpisodeWithoutAVideoMakesItPartialButTheRestIsStillImported() throws Exception {
        raw("POST", "/__scenario", "{\"vidbolt_404\":[\"/tv/2001/1/2\"]}");
        WnPipeline.Options o = ack();
        o.subtitles = false;
        WnPipeline.Result r = WnPipeline.runSeries(card("series", 2001, "x"), o, new Log());
        assertEquals("partial", r.state);
        assertEquals(3, r.created);
        assertEquals(1, r.missingVideo);
        assertEquals(null, byId("2001_s1e2"));
        assertTrue(byId("2001_s1e3") != null);
    }

    @Test
    public void episodeSubtitlesAreAttachedPerEpisode() throws Exception {
        raw("POST", "/__scenario", "{\"subdl\":{\"2001\":true}}");
        WnPipeline.Options o = ack();
        o.episodes = new HashMap<>();
        o.episodes.put(1, Arrays.asList(1, 2));
        WnPipeline.Result r = WnPipeline.runSeries(card("series", 2001, "x"), o, new Log());
        assertEquals(r.message + r.notes, "imported", r.state);
        JSONObject e2 = byId("2001_s1e2");
        assertTrue(java.net.URLDecoder.decode(e2.getString("subtitle"), "UTF-8").endsWith("season-01/S01E02.ar.srt"));
    }

    @Test
    public void unacknowledgedEmbedSeriesWritesNothing() throws Exception {
        WnPipeline.Options o = new WnPipeline.Options();
        o.subtitles = false;
        o.flags.notify = false;
        WnPipeline.Result r = WnPipeline.runSeries(card("series", 2001, "x"), o, new Log());
        assertEquals("needs_ack", r.state);
        assertEquals(0, library().length());
    }

    // ------------------------------------------------------------------ client / contract

    @Test
    public void wrongTokenIsReportedAsUnauthorized() throws Exception {
        WnApi.use(new WnApi.HttpBackend(base, "wrong-token"));
        try {
            WnApi.summary();
            org.junit.Assert.fail("expected a failure");
        } catch (CxApi.Failure f) {
            assertEquals("UNAUTHORIZED", f.code);
            assertEquals(401, f.http);
        } finally {
            WnApi.use(new WnApi.HttpBackend(base, "test-admin-token"));
        }
    }

    @Test
    public void summaryFeedDetailsAndHistoryAnswerWithTheFieldsTheScreensRead() throws Exception {
        JSONObject sum = WnApi.summary();
        assertTrue(sum.has("counts") && sum.has("available") && sum.has("synced"));
        JSONObject feed = WnApi.feed(WnLogic.feedRequest(WnLogic.chip("movies"), new WnLogic.Filters(), 1, false));
        JSONObject c = feed.getJSONArray("cards").getJSONObject(0);
        for (String k : new String[]{"key", "type", "tmdb_id", "title", "original_title", "year", "poster", "rating", "genres", "overview", "release_date", "imported", "video", "subtitle"}) {
            assertTrue("card field " + k, c.has(k));
        }
        JSONObject det = WnApi.details("series", 2001);
        for (String k : new String[]{"seasons", "season_count", "latest_episode", "import", "sections", "existing_categories", "library_episodes", "providers"}) {
            assertTrue("details field " + k, det.has(k));
        }
        JSONObject sync = WnApi.sync(2);
        assertEquals("ok", sync.getJSONObject("run").getString("status"));
        JSONObject hist = WnApi.history();
        assertEquals(1, hist.getJSONArray("runs").length());
        assertTrue(WnApi.genres().getJSONArray("movie").length() > 0);
        assertFalse(WnApi.config().getJSONObject("config").getBoolean("auto_sync"));
    }
}
