package com.my.newproject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;

/**
 * 3.0.2 fixes, plain JVM (no Android runtime needed):
 *  - «قنوات تحتاج رابطاً» no longer fails with «العنصر غير موجود» when the Worker lacks the route (HTTP 404 not_found),
 *  - the stopped channels of the last Live Health scan feed that screen,
 *  - the replace sheet's link rules,
 *  - the chart palette used by the statistics.
 */
public class AdminFixesTest {

    private static CgHttp.Res res(int code, String body) throws Exception {
        CgHttp.Res r = new CgHttp.Res();
        r.code = code;
        r.body = body;
        String t = body.trim();
        if (t.startsWith("{")) r.json = new JSONObject(t);
        return r;
    }

    // ------------------------------------------------------------------ the 404 that used to be shown as an error

    @Test
    public void theRouterMissAnswerOfTheLiveWorkerIsRecognisedAsAMissingRoute() throws Exception {
        // exactly what https://cigram-admin-api.…workers.dev/admin/channel-reports answered on 2026-11-14
        CgHttp.Res r = res(404, "{\"ok\":false,\"error\":\"not_found\",\"path\":\"/admin/channel-reports\"}");
        assertFalse(r.ok());
        assertTrue(r.routeMissing());
        assertEquals("العنصر غير موجود.", r.message());           // the sentence the admin used to see
    }

    @Test
    public void otherFailuresAreNotMistakenForAMissingRoute() throws Exception {
        assertFalse("401 is a wrong token", res(401, "{\"ok\":false,\"error\":\"unauthorized\"}").routeMissing());
        assertFalse("500 is a server error", res(500, "{\"ok\":false,\"error\":\"internal_error\"}").routeMissing());
        assertFalse("a handler that says 'item not found' has no router path", res(404, "{\"ok\":false,\"error\":\"not_found\"}").routeMissing());
        assertFalse("200 with data", res(200, "{\"ok\":true,\"data\":{\"items\":[]}}").routeMissing());
        CgHttp.Res net = new CgHttp.Res();
        net.error = new IOException("offline");
        assertFalse("no network is a real failure, not a missing route", net.routeMissing());
        assertTrue("a 404 page that is not JSON at all (proxy / CDN) is treated as missing too", res(404, "<html>404</html>").routeMissing());
    }

    // ------------------------------------------------------------------ stopped channels of the last scan

    @Test
    public void reportedChannelsComeFirstAndNoChannelIsListedTwice() throws Exception {
        JSONArray reported = new JSONArray()
                .put(new JSONObject().put("name", "beIN SPORTS 3").put("url", "https://a/1.m3u8").put("count", 4))
                .put(new JSONObject().put("name", "Mbc Action 4k").put("url", "https://a/2.m3u8").put("count", 1));
        JSONArray stopped = new JSONArray()
                .put(LiveHealthStore.downJson("id9", "bein  sports 3", "رياضة", "https://a/1.m3u8", "متوقفة"))   // same channel (case / spaces differ)
                .put(LiveHealthStore.downJson("id7", "mbc 4", "ترفيه", "https://b/4.m3u8", "الرابط غير موجود (404)"));
        JSONArray all = LiveHealthStore.merge(reported, stopped);
        assertEquals(3, all.length());
        assertEquals("beIN SPORTS 3", all.getJSONObject(0).getString("name"));
        assertEquals("report", all.getJSONObject(0).getString("kind"));
        assertEquals("report", all.getJSONObject(1).getString("kind"));
        assertEquals("mbc 4", all.getJSONObject(2).getString("name"));
        assertEquals("health", all.getJSONObject(2).getString("kind"));
        assertEquals("id7", all.getJSONObject(2).getString("id"));
    }

    @Test
    public void withoutReportsTheStoppedListStillFillsTheScreen() throws Exception {
        JSONArray stopped = new JSONArray().put(LiveHealthStore.downJson("", "mbc 4", "", "https://b/4.m3u8", "x"));
        assertEquals(1, LiveHealthStore.merge(null, stopped).length());
        assertEquals(0, LiveHealthStore.merge(null, null).length());
        assertEquals(0, LiveHealthStore.merge(new JSONArray(), new JSONArray()).length());
    }

    @Test
    public void channelNamesAreComparedWithoutCaseOrExtraSpaces() {
        assertEquals(LiveHealthStore.norm("  beIN   Movies  2  Action "), LiveHealthStore.norm("bein movies 2 action"));
        assertNotEquals(LiveHealthStore.norm("mbc 4"), LiveHealthStore.norm("mbc 5"));
        assertEquals("", LiveHealthStore.norm(null));
    }

    // ------------------------------------------------------------------ the replace sheet

    @Test
    public void onlyRealStreamAddressesAreAccepted() {
        assertTrue(ChannelLinkSheet.validStream("https://cdn.example.com/live/ch1/index.m3u8"));
        assertTrue(ChannelLinkSheet.validStream("http://1.2.3.4:8000/live/a.ts"));
        assertTrue(ChannelLinkSheet.validStream("  HTTPS://CDN.EXAMPLE.COM/a.m3u8  "));
        assertTrue(ChannelLinkSheet.validStream("https://tv.example/a.m3u8|Referer=https://site.example/&User-Agent=Mozilla/5.0 (Linux)"));
        assertTrue(ChannelLinkSheet.validStream("rtmp://live.example/app/stream"));
        assertFalse(ChannelLinkSheet.validStream(""));
        assertFalse(ChannelLinkSheet.validStream(null));
        assertFalse(ChannelLinkSheet.validStream("t.me/channel"));
        assertFalse(ChannelLinkSheet.validStream("https://"));
        assertFalse(ChannelLinkSheet.validStream("https:///x"));
        assertFalse(ChannelLinkSheet.validStream("ftp://example.com/a.m3u8"));
    }

    @Test
    public void theSheetExplainsWhyALinkCannotBeSaved() {
        String old = "https://old.example/1.m3u8";
        assertEquals("", ChannelLinkSheet.problem("https://new.example/1.m3u8", old));
        assertEquals("", ChannelLinkSheet.problem("https://new.example/1.m3u8", ""));
        assertTrue(ChannelLinkSheet.problem("", old).length() > 0);
        assertTrue(ChannelLinkSheet.problem("hello", old).length() > 0);
        assertTrue("the same link again is refused", ChannelLinkSheet.problem(" " + old + " ", old).contains("الحالي"));
    }

    // ------------------------------------------------------------------ statistics colours

    @Test
    public void theChartPaletteHasSeveralDistinctColours() {
        java.util.HashSet<Integer> distinct = new java.util.HashSet<Integer>();
        for (int c : CgCfg.SERIES) distinct.add(c);
        assertEquals(CgCfg.SERIES.length, distinct.size());
        assertTrue(distinct.size() >= 5);
        assertTrue(distinct.contains(CgCfg.CH_GREEN) && distinct.contains(CgCfg.CH_RED) && distinct.contains(CgCfg.CH_ORANGE));
        assertEquals(CgCfg.CH_GREEN, CgCfg.series(0));
        assertEquals("the rotation wraps around, also for negative indexes", CgCfg.series(-1), CgCfg.series(CgCfg.SERIES.length - 1));
        assertEquals(CgCfg.series(1), CgCfg.series(CgCfg.SERIES.length + 1));
    }

    @Test
    public void metricTokensOfTheHomeChartsAreRealColoursAgain() {
        int[] metric = {NxUi.BLUE, NxUi.VIOLET, NxUi.ORANGE, NxUi.TEAL, NxUi.PINK, NxUi.UP, NxUi.DOWN};
        for (int c : metric) {
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            assertTrue("a metric colour must not be white / grey", Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 40);
        }
    }

    // ------------------------------------------------------------------ icons

    @Test
    public void aGlyphIsNeverInvisibleOnItsBlackTile() {
        assertEquals(StudioUi.TILE_GLYPH, StudioUi.glyph(0xFF000000));
        assertEquals(StudioUi.TILE_GLYPH, StudioUi.glyph(0xFF06281D));           // the old dark-green "check" glyph
        assertEquals(StudioUi.TILE_GLYPH, StudioUi.glyph(0x00FFFFFF));           // transparent
        assertEquals(CgCfg.GOOD, StudioUi.glyph(CgCfg.GOOD));                    // status colours stay as they are
        assertEquals(CgCfg.BAD, StudioUi.glyph(CgCfg.BAD));
        assertEquals(0xFFFFFFFF, StudioUi.glyph(0xFFFFFFFF));
        assertEquals(0xFF000000, StudioUi.TILE_BG);
    }
}
