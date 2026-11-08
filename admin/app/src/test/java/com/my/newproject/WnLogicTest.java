package com.my.newproject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Pure-logic tests for "What's New?" (no Android, no network). */
public class WnLogicTest {

    @Test
    public void feedRequestCarriesChipFiltersPageAndRefresh() throws Exception {
        WnLogic.Filters f = new WnLogic.Filters();
        f.genre = "28";
        f.minRating = "7";
        f.year = "2025";
        assertTrue(f.active());
        JSONObject r = WnLogic.feedRequest(WnLogic.chip("new"), f, 3, true);
        assertEquals("all:new_releases", r.getString("feed"));
        assertEquals("release", r.getString("sort"));
        assertEquals("28", r.getString("genre"));
        assertEquals("7", r.getString("min_rating"));
        assertEquals("2025", r.getString("year"));
        assertEquals(3, r.getInt("page"));
        assertTrue(r.getBoolean("refresh"));
        assertFalse(r.has("language"));
        JSONObject plain = WnLogic.feedRequest(WnLogic.chip("movies"), new WnLogic.Filters(), 0, false);
        assertEquals(1, plain.getInt("page"));
        assertEquals("movie", plain.getString("type"));
        assertFalse(plain.has("refresh"));
        assertEquals("all:new_releases", WnLogic.chip("does-not-exist")[2]);
    }

    @Test
    public void everyChipMapsToAWorkerFeedOrView() {
        List<String> known = Arrays.asList("all:new_releases", "movie:new_releases", "tv:recent_episodes", "all:trending", "movie:upcoming",
                "tv:new_seasons", "collections", "recently_added", "ready", "unverified", "missing_video", "missing_subtitle", "imported");
        for (String[] c : WnLogic.CHIPS) assertTrue(c[0], known.contains(c[2]));
        assertEquals(14, WnLogic.CHIPS.length);
    }

    @Test
    public void chunkSplitsWithoutLosingOrReordering() {
        List<Integer> all = new ArrayList<>();
        for (int i = 1; i <= 30; i++) all.add(i);
        List<List<Integer>> c = WnLogic.chunk(all, WnLogic.IMPORT_CHUNK);
        assertEquals(3, c.size());
        assertEquals(12, c.get(0).size());
        assertEquals(6, c.get(2).size());
        assertEquals(Integer.valueOf(13), c.get(1).get(0));
        assertEquals(0, WnLogic.chunk(new ArrayList<Integer>(), 5).size());
        assertEquals(1, WnLogic.chunk(all, 0).get(0).size());
    }

    @Test
    public void requestIdsAreUniquePerActionAndSafe() {
        String a = WnLogic.newRequestId("movie:1001");
        String b = WnLogic.newRequestId("movie:1001");
        assertTrue(a.length() <= 80);
        assertTrue(a.matches("[A-Za-z0-9:_-]+"));
        assertFalse(a.equals(b));
        assertTrue(WnLogic.newRequestId("طويل جداً \"; drop").matches("[A-Za-z0-9:_-]+"));
        assertTrue(WnLogic.newRequestId(repeat("x", 200)).length() <= 80);
    }

    @Test
    public void moviePlanHasTheShapeTheWorkerExpects() throws Exception {
        WnLogic.Placement p = new WnLogic.Placement();
        p.newSection = "أفلام العيد";
        p.newSectionType = "movie";
        p.categories = Arrays.asList("أكشن", "عيد");
        WnLogic.Flags fl = new WnLogic.Flags();
        fl.ackUnverified = true;
        fl.notify = false;
        fl.quality = "1080p";
        JSONObject plan = WnLogic.basePlan("movie", 1001, p, fl, "req-1");
        assertEquals("1080p", plan.getJSONObject("options").getString("quality"));
        WnLogic.withMovie(plan, "tok.v", Arrays.asList("fb1", "fb2"), "subtok");
        assertEquals("movie", plan.getString("type"));
        assertEquals(1001, plan.getLong("tmdb_id"));
        assertEquals("req-1", plan.getString("request_id"));
        assertTrue(plan.getBoolean("ack_unverified"));
        assertEquals("أفلام العيد", plan.getJSONObject("section").getString("new_name"));
        assertFalse(plan.getJSONObject("section").has("id"));
        assertEquals(2, plan.getJSONArray("categories").length());
        assertFalse(plan.getJSONObject("options").getBoolean("notify"));
        JSONObject m = plan.getJSONObject("movie");
        assertEquals("tok.v", m.getString("video_token"));
        assertEquals("subtok", m.getString("subtitle_token"));
        assertEquals(2, m.getJSONArray("fallback_tokens").length());
    }

    @Test
    public void existingSectionWinsAndNullCategoriesKeepTmdbGenres() throws Exception {
        WnLogic.Placement p = new WnLogic.Placement();
        p.sectionId = "action";
        p.newSection = "ignored";
        JSONObject plan = WnLogic.basePlan("movie", 5, p, null, "r");
        assertEquals("action", plan.getJSONObject("section").getString("id"));
        assertFalse(plan.getJSONObject("section").has("new_name"));
        assertFalse("null categories = keep the TMDB genres", plan.has("categories"));
        assertFalse(plan.getBoolean("ack_unverified"));
        assertTrue(plan.getJSONObject("options").getBoolean("notify"));
        p.categories = new ArrayList<>();
        assertEquals("empty list = explicitly no categories", 0, WnLogic.basePlan("movie", 5, p, null, "r").getJSONArray("categories").length());
    }

    @Test
    public void seriesPlanBindsEachTokenToItsOwnEpisode() throws Exception {
        List<JSONObject> eps = new ArrayList<>();
        eps.add(WnLogic.episodeEntry(1, "t1", null, ""));
        eps.add(WnLogic.episodeEntry(3, "t3", Arrays.asList("f"), "s3"));
        JSONObject plan = WnLogic.withSeason(WnLogic.basePlan("series", 2001, null, null, "r"), 2, eps);
        JSONObject s = plan.getJSONArray("seasons").getJSONObject(0);
        assertEquals(2, s.getInt("season"));
        JSONArray a = s.getJSONArray("episodes");
        assertEquals(1, a.getJSONObject(0).getInt("episode"));
        assertEquals("t1", a.getJSONObject(0).getString("video_token"));
        assertFalse(a.getJSONObject(0).has("subtitle_token"));
        assertEquals(3, a.getJSONObject(1).getInt("episode"));
        assertEquals("t3", a.getJSONObject(1).getString("video_token"));
        assertEquals("s3", a.getJSONObject(1).getString("subtitle_token"));
        assertEquals("S02E03", WnLogic.epCode(2, 3));
    }

    @Test
    public void importGateNeedsAReadyReportAndTheAcknowledgementForUnprovableSources() throws Exception {
        JSONObject ready = new JSONObject("{\"ok\":true,\"requires_ack\":true,\"state\":\"ready\"}");
        assertFalse(WnLogic.canImport(ready, false));
        assertTrue(WnLogic.canImport(ready, true));
        JSONObject provable = new JSONObject("{\"ok\":true,\"requires_ack\":false}");
        assertTrue(WnLogic.canImport(provable, false));
        assertFalse(WnLogic.canImport(new JSONObject("{\"ok\":false,\"requires_ack\":false}"), true));
        assertFalse(WnLogic.canImport(null, true));
    }

    @Test
    public void bestSourcePrefersTheWorkersChoiceAndNeverReturnsAnUnimportableOne() throws Exception {
        JSONObject target = new JSONObject("{\"best\":\"cdn\",\"sources\":["
                + "{\"provider\":\"dead\",\"importable\":false,\"token\":\"\",\"verdict\":\"unavailable\"},"
                + "{\"provider\":\"vidbolt\",\"importable\":true,\"token\":\"v\",\"verdict\":\"embed_reachable\"},"
                + "{\"provider\":\"cdn\",\"importable\":true,\"token\":\"c\",\"verdict\":\"playable\"}]}");
        JSONObject best = WnLogic.bestSource(target);
        assertEquals("cdn", best.getString("provider"));
        List<String> fb = WnLogic.fallbackTokens(target, best);
        assertEquals(Arrays.asList("v"), fb);
        JSONObject none = new JSONObject("{\"best\":\"\",\"sources\":[{\"provider\":\"x\",\"importable\":false,\"token\":\"\"}]}");
        assertNull(WnLogic.bestSource(none));
        assertNull(WnLogic.bestSource(null));
        JSONObject noToken = new JSONObject("{\"sources\":[{\"provider\":\"x\",\"importable\":true,\"token\":\"\"}]}");
        assertNull("an importable verdict without a signed token is unusable", WnLogic.bestSource(noToken));
    }

    @Test
    public void verdictWordingNeverCallsAnUnprovenSourcePlayable() {
        assertTrue(WnLogic.verdictLabel("playable").contains("تم التحقق"));
        assertTrue(WnLogic.verdictLabel("embed_reachable").contains("غير مُثبت"));
        assertFalse(WnLogic.verdictLabel("embed_reachable").contains("صالح"));
        assertTrue(WnLogic.verdictNeedsAck("embed_reachable"));
        assertTrue(WnLogic.verdictNeedsAck("unverified"));
        assertFalse(WnLogic.verdictNeedsAck("playable"));
        assertFalse(WnLogic.verdictImportable("unavailable"));
        assertFalse(WnLogic.verdictImportable("drm"));
        assertFalse(WnLogic.verdictImportable("mismatch"));
        assertEquals(CgCfg.GOOD, WnLogic.verdictColor("playable"));
        assertEquals(CgCfg.BAD, WnLogic.verdictColor("unreachable"));
        assertEquals("فيديو غير مُتحقَّق", WnLogic.cardVideoLabel("unverified"));
    }

    @Test
    public void everyRequiredImportStateHasWording() {
        String[] states = {"ready", "missing_video", "missing_metadata", "subtitle_missing", "invalid_subtitle", "duplicate", "import_failed", "imported"};
        for (String s : states) assertTrue(s, WnLogic.stateLabel(s).length() > 0 && !WnLogic.stateLabel(s).equals(s));
        assertEquals("جاهز للاستيراد", WnLogic.stateLabel("ready"));
        assertEquals("تم الاستيراد بنجاح", WnLogic.stateLabel("imported"));
        assertEquals(CgCfg.GOOD, WnLogic.stateColor("imported"));
        assertEquals(CgCfg.BAD, WnLogic.stateColor("missing_video"));
    }

    @Test
    public void firstProblemFindsTheFailingLineEvenInsideAnItem() throws Exception {
        JSONObject top = new JSONObject("{\"state\":\"duplicate\",\"checks\":[{\"status\":\"pass\",\"label\":\"a\",\"detail\":\"\"},{\"status\":\"fail\",\"label\":\"التكرار\",\"detail\":\"موجود\"}]}");
        assertEquals("التكرار: موجود", WnLogic.firstProblem(top));
        JSONObject nested = new JSONObject("{\"state\":\"missing_video\",\"checks\":[],\"items\":[{\"label\":\"S01E02\",\"checks\":[{\"status\":\"fail\",\"label\":\"الفيديو\",\"detail\":\"لا مصدر\"}]}]}");
        assertEquals("S01E02 — الفيديو: لا مصدر", WnLogic.firstProblem(nested));
        assertEquals("", WnLogic.firstProblem(null));
    }

    @Test
    public void errorKeyPrefersTheWorkersMachineKey() throws Exception {
        CxApi.Failure f = new CxApi.Failure("m", "DUPLICATE_CONTENT", new JSONObject("{\"error\":\"duplicate_content\"}"), 409);
        assertEquals("duplicate_content", WnLogic.errorKey(f));
        assertEquals("NETWORK", WnLogic.errorKey(new CxApi.Failure("m", "NETWORK", null, 0)));
        assertEquals("", WnLogic.errorKey(null));
    }

    @Test
    public void filterDescriptionListsOnlyWhatIsSet() {
        WnLogic.Filters f = new WnLogic.Filters();
        assertFalse(f.active());
        assertEquals("", f.describe(null));
        f.genre = "28";
        f.minRating = "7";
        assertEquals("أكشن · تقييم ≥ 7", f.describe("أكشن"));
        f.clear();
        assertFalse(f.active());
        assertNotNull(WnLogic.ratingText(0));
        assertEquals("7.8", WnLogic.ratingText(7.84));
    }

    @Test
    public void chunkReportsMergeIntoOne() throws Exception {
        JSONObject a = new JSONObject("{\"ok\":true,\"requires_ack\":true,\"state\":\"ready\",\"checks\":[{\"id\":\"tmdb\"}],\"items\":[{\"id\":\"S01E01\"}],\"warnings\":[\"w1\"]}");
        JSONObject b = new JSONObject("{\"ok\":false,\"requires_ack\":false,\"state\":\"missing_video\",\"checks\":[],\"items\":[{\"id\":\"S01E13\"}],\"warnings\":[]}");
        JSONObject m = WnLogic.mergeReports(Arrays.asList(a, b));
        assertFalse(m.getBoolean("ok"));
        assertFalse("an unacknowledged embed does not matter once the plan is not importable", m.getBoolean("requires_ack"));
        assertEquals("missing_video", m.getString("state"));
        assertEquals(2, m.getJSONArray("items").length());
        assertEquals(1, m.getJSONArray("checks").length());
        JSONObject ok = WnLogic.mergeReports(Arrays.asList(a, new JSONObject("{\"ok\":true,\"state\":\"ready\"}")));
        assertTrue(ok.getBoolean("ok"));
        assertTrue(ok.getBoolean("requires_ack"));
        assertFalse(WnLogic.mergeReports(new ArrayList<JSONObject>()).getBoolean("ok"));
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}
