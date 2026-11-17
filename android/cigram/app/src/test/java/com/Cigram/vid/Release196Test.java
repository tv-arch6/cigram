package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 19.6 — the owner's requests that can be checked without a device. */
public class Release196Test {

    // ---- «2025 ¦ كوميديا | دراما»

    @Test public void metaLineFromEveryStoredShape() {
        assertEquals("2025 ¦ كوميديا | دراما", CigramMetaLine.line("2025", "كوميديا، دراما"));
        assertEquals("2025 ¦ كوميديا | دراما", CigramMetaLine.line("2025", "كوميديا,دراما"));
        assertEquals("2025 ¦ كوميديا | دراما", CigramMetaLine.line("2025", "كوميديا | دراما"));
        assertEquals("2025 ¦ كوميديا | دراما", CigramMetaLine.line("2025", " كوميديا / دراما "));
        assertEquals("2026 ¦ حركة | إثارة", CigramMetaLine.line("2026", "حركة، إثارة"));
        assertEquals("2025 ¦ رسوم متحركة | كوميديا | دراما", CigramMetaLine.line("2025-03-14", "رسوم متحركة، كوميديا، دراما"));
    }

    @Test public void metaLineMissingParts() {
        assertEquals("كوميديا | دراما", CigramMetaLine.line("", "كوميديا، دراما"));
        assertEquals("2025", CigramMetaLine.line("2025", ""));
        assertEquals("", CigramMetaLine.line(null, null));
        assertEquals("خيال علمي | فانتازيا", CigramMetaLine.category("خيال علمي - فانتازيا"));
        assertEquals("Sci-Fi", CigramMetaLine.category("Sci-Fi"));   // an inner hyphen is part of the name
    }

    @Test public void metaLineNormalizeIsIdempotent() {
        String once = CigramMetaLine.normalize("2025، كوميديا، دراما");
        assertEquals("2025 ¦ كوميديا | دراما", once);
        assertEquals(once, CigramMetaLine.normalize(once));
        assertEquals("كوميديا | كوميديا".length() > 0, true);
        assertEquals("كوميديا", CigramMetaLine.category("كوميديا، كوميديا"));   // duplicates dropped
    }

    // ---- 11 posters per home row

    @Test public void homeRowCapsAtEleven() {
        List<Integer> many = new ArrayList<Integer>();
        for (int i = 0; i < 40; i++) many.add(i);
        List<Integer> row = CigramDesign.homeRow(many);
        assertEquals(11, row.size());
        assertEquals(Integer.valueOf(0), row.get(0));
        assertEquals(40, many.size());                                    // the source (the «المزيد» page) is untouched
        assertEquals(3, CigramDesign.homeRow(Arrays.asList(1, 2, 3)).size());
        assertEquals(11, CigramDesign.HOME_ROW_MAX);
        assertEquals(11, CigramHomeGroups.MAX_ITEMS);
        assertEquals(11, CigramWatchlist.ROW_LIMIT);
    }

    // ---- a title the admin adds is fetched at once (no TTL wait)

    @Test public void changedSkipsTheTtl() {
        CigramRefreshPolicy.State s = new CigramRefreshPolicy.State();
        s.now = 10L * 60_000L;
        s.snapshotConfirmedAt = s.now - 10_000L;     // confirmed 10 s ago: every other reason waits minutes
        s.lastAttemptAt = s.now - 10_000L;
        assertFalse(CigramRefreshPolicy.shouldRefresh(CigramRefreshPolicy.Reason.TAB_SHOWN, s));
        assertFalse(CigramRefreshPolicy.shouldRefresh(CigramRefreshPolicy.Reason.RESUME, s));
        assertTrue(CigramRefreshPolicy.shouldRefresh(CigramRefreshPolicy.Reason.CHANGED, s));
    }

    @Test public void changedKeepsSingleFlightAndGap() {
        CigramRefreshPolicy.State s = new CigramRefreshPolicy.State();
        s.now = 100_000L;
        s.lastAttemptAt = s.now - 1_000L;
        assertFalse("3 s minimum gap", CigramRefreshPolicy.shouldRefresh(CigramRefreshPolicy.Reason.CHANGED, s));
        s.lastAttemptAt = s.now - 5_000L;
        s.running = true;
        assertFalse("single flight", CigramRefreshPolicy.shouldRefresh(CigramRefreshPolicy.Reason.CHANGED, s));
        s.running = false;
        s.consecutiveFailures = 6;
        assertFalse("back-off after failures", CigramRefreshPolicy.shouldRefresh(CigramRefreshPolicy.Reason.CHANGED, s));
        s.lastAttemptAt = s.now - 61_000L;
        assertTrue("back-off capped at one minute", CigramRefreshPolicy.shouldRefresh(CigramRefreshPolicy.Reason.CHANGED, s));
    }

    @Test public void etagNormalised() {
        assertEquals("0f46c873", CigramCatalogPulse.normalize("W/\"0f46c873\""));
        assertEquals("0f46c873", CigramCatalogPulse.normalize("\"0f46c873\""));
        assertEquals("", CigramCatalogPulse.normalize(null));
    }

    // ---- notifications only for complete titles

    @Test public void readyGateNeedsCompleteInformation() throws Exception {
        JSONObject ok = new JSONObject()
                .put("title", "Ghost Soldier").put("poster", "https://image.tmdb.org/t/p/w500/a.jpg")
                .put("video", "https://vidbolt.pro/movie/1562642").put("year", "2026").put("category", "حركة، إثارة");
        assertEquals("", CigramReadyGate.missingInfo(ok, true));
        assertEquals("poster", CigramReadyGate.missingInfo(new JSONObject(ok.toString()).put("poster", ""), true));
        assertEquals("video", CigramReadyGate.missingInfo(new JSONObject(ok.toString()).put("video", ""), true));
        assertEquals("title", CigramReadyGate.missingInfo(new JSONObject(ok.toString()).put("title", ""), true));
        JSONObject noInfo = new JSONObject(ok.toString()).put("year", "").put("category", "");
        assertEquals("info", CigramReadyGate.missingInfo(noInfo, true));
        assertEquals("", CigramReadyGate.missingInfo(noInfo.put("description", "قصة كاملة عن الجندي الشبح"), true));
        assertEquals("", CigramReadyGate.missingInfo(new JSONObject(ok.toString()).put("video", ""), false));   // a series header
    }

    @Test public void readyGateMergesEpisodeIntoSeries() throws Exception {
        JSONObject series = new JSONObject().put("title", "كابتن تسوباسا").put("poster", "https://image.tmdb.org/s.jpg")
                .put("year", "2018").put("category", "رسوم متحركة").put("episodes", "x");
        JSONObject ep = new JSONObject().put("season", "2").put("episode", "39").put("video", "https://vidbolt.pro/tv/77240/2/39")
                .put("poster", "https://image.tmdb.org/e.jpg");
        JSONObject m = CigramReadyGate.merged(series, ep);
        assertEquals("https://image.tmdb.org/s.jpg", m.optString("poster"));   // the series poster wins
        assertEquals("https://vidbolt.pro/tv/77240/2/39", m.optString("video"));
        assertFalse(m.has("episodes"));
        assertEquals("", CigramReadyGate.missingInfo(m, true));
    }

    // ---- the admin deleted / hid a section: it leaves Home, its titles stay

    @Test public void removedSectionsLeaveHome() {
        CigramHomeOrder.Plan plan = new CigramHomeOrder.Plan();
        plan.order.put("mslslat_anmy", 1);
        plan.order.put("مسلسلات آنمي", 1);
        plan.hidden.put("mslslat_anmy", Boolean.FALSE);
        plan.hidden.put("مسلسلات آنمي", Boolean.FALSE);
        plan.any = true;
        assertFalse(plan.removedFromHome("mslslat_anmy", "مسلسلات آنمي"));
        assertTrue("deleted / hidden in the admin app", plan.removedFromHome("rswm_mthrka", "رسوم متحركة"));
        assertFalse("a programmatic row without an id", plan.removedFromHome("", "الأعلى تقييماً"));
        CigramHomeOrder.Plan none = new CigramHomeOrder.Plan();
        assertFalse("no registry: nothing is removed", none.removedFromHome("rswm_mthrka", "رسوم متحركة"));
    }
}
