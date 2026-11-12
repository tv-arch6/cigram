package com.my.newproject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** Studio 3.0: the Bulk Upload Wizard's plan and Cigram Live Health's playlist logic (JVM, no device, no network). */
public class StudioLogicTest {

    private static BulkPlan.Item file(String name, long size) {
        BulkPlan.Item i = new BulkPlan.Item();
        i.name = name;
        i.size = size;
        i.uri = "content://x/" + name;
        return i;
    }

    // ---------------------------------------------------------------- Bulk Upload Wizard

    @Test
    public void aSeasonIsReadFromTheFileNamesWithItsHolesDoublesAndStrangers() {
        List<BulkPlan.Item> files = new ArrayList<BulkPlan.Item>(Arrays.asList(
                file("Show.S02E01.1080p.mkv", 900L << 20), file("Show.S02E02.1080p.mkv", 910L << 20), file("Show.S02E04.mkv", 880L << 20),
                file("Show.S02E04.REPACK.mkv", 870L << 20), file("Show.S02E01.ar.srt", 60000), file("Show.S02E09.ar.srt", 61000),
                file("Show.S03E01.mkv", 1L << 30), file("Show.S02E05E06.mkv", 1L << 30), file("cover.jpg", 30000), file("Show.final.mkv", 5), file("Show.S02E07.mp4", 0)));
        BulkPlan.Plan p = BulkPlan.season(files);
        assertEquals(2, p.season);
        assertEquals(8, p.videos);
        assertEquals(2, p.subtitles);
        assertEquals(1, p.others);
        assertEquals(Arrays.asList(4), p.duplicateEpisodes);
        assertEquals(Arrays.asList(3), p.missingEpisodes);
        assertFalse("a doubled episode blocks the hand-over", p.ready());
        java.util.Map<String, String> problem = new java.util.HashMap<String, String>();
        for (BulkPlan.Item i : p.items) problem.put(i.name, i.problem);
        assertEquals("", problem.get("Show.S02E01.1080p.mkv"));
        assertTrue(problem.get("Show.S02E04.mkv").contains("مكررة"));
        assertTrue(problem.get("Show.S03E01.mkv").contains("الموسم 3"));
        assertTrue(problem.get("Show.S02E05E06.mkv").contains("مزدوجة"));
        assertTrue(problem.get("cover.jpg").contains("غير مدعومة"));
        assertTrue(problem.get("Show.final.mkv").contains("رقم حلقة"));
        assertTrue(problem.get("Show.S02E07.mp4").contains("فارغ"));
        assertTrue(problem.get("Show.S02E09.ar.srt").contains("بلا فيديو"));
        assertEquals("", problem.get("Show.S02E01.ar.srt"));
        assertEquals("the plan is ordered by episode", 1, p.items.get(0).episode);
        assertTrue(p.headline().contains("8 فيديو") && p.headline().contains("الموسم 2"));

        // a clean season is ready and says so
        BulkPlan.Plan ok = BulkPlan.season(new ArrayList<BulkPlan.Item>(Arrays.asList(file("A.S01E01.mkv", 100), file("A.S01E02.mkv", 100), file("A.S01E03.mkv", 100))));
        assertTrue(ok.ready());
        assertEquals(0, ok.problems);
        assertTrue(ok.missingEpisodes.isEmpty() && ok.notes.isEmpty());
        assertFalse("subtitles alone are not a season to upload", BulkPlan.season(new ArrayList<BulkPlan.Item>(Arrays.asList(file("A.S01E01.srt", 9)))).ready());
    }

    @Test
    public void filmTitlesAndYearsComeOutOfReleaseNamesAndRepeatsAreFlagged() {
        int[] y = new int[1];
        assertEquals("The Matrix", BulkPlan.cleanTitle("The.Matrix.1999.1080p.BluRay.x264.mkv", y));
        assertEquals(1999, y[0]);
        assertEquals("Blade Runner 2049", BulkPlan.cleanTitle("Blade.Runner.2049.2017.2160p.UHD.mkv", y));
        assertEquals(2017, y[0]);
        assertEquals("1917", BulkPlan.cleanTitle("1917.2019.WEB-DL.mp4", y));
        assertEquals(2019, y[0]);
        assertEquals("Some Film", BulkPlan.cleanTitle("[Group] Some_Film (2021) [1080p].mkv", y));
        assertEquals(2021, y[0]);
        assertEquals("فيلم عربي", BulkPlan.cleanTitle("فيلم عربي 720p.mp4", y));
        assertEquals(0, y[0]);
        assertEquals("video", BulkPlan.kindOf("X.MKV"));
        assertEquals("subtitle", BulkPlan.kindOf("x.ar.srt"));
        assertEquals("other", BulkPlan.kindOf("x.nfo"));

        BulkPlan.Plan p = BulkPlan.films(new ArrayList<BulkPlan.Item>(Arrays.asList(
                file("The.Matrix.1999.1080p.mkv", 100), file("The.Matrix.1999.720p.mp4", 90), file("Heat.1995.mkv", 100), file("New.Film.2024.mkv", 100), file("notes.txt", 5))),
                new HashSet<String>(Arrays.asList(BulkPlan.norm("Heat"))));
        assertEquals(4, p.videos);
        java.util.Map<String, BulkPlan.Item> by = new java.util.HashMap<String, BulkPlan.Item>();
        for (BulkPlan.Item i : p.items) by.put(i.name, i);
        assertTrue(by.get("The.Matrix.1999.1080p.mkv").problem.contains("ملفان"));
        assertTrue(by.get("Heat.1995.mkv").exists && by.get("Heat.1995.mkv").problem.contains("موجود"));
        assertEquals("", by.get("New.Film.2024.mkv").problem);
        assertEquals(2024, by.get("New.Film.2024.mkv").year);
    }

    // ---------------------------------------------------------------- Cigram Live Health

    @Test
    public void playlistsAreToldApartAndTheirAddressesResolvedLikeAPlayerDoes() {
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2400000\nhttps://cdn.example/hi/index.m3u8?t=1\n";
        String media = "#EXTM3U\r\n#EXT-X-TARGETDURATION:6\r\n#EXTINF:6.0,\r\nseg_100.ts\r\n#EXTINF:6.0,\r\nseg_101.ts\r\n\r\n";
        assertTrue(LiveHealth.isPlaylist(master) && LiveHealth.isPlaylist("\n  #EXTM3U\n"));
        assertFalse(LiveHealth.isPlaylist("<html>blocked</html>"));
        assertTrue(LiveHealth.isMaster(master));
        assertFalse(LiveHealth.isMaster(media));
        assertEquals(Arrays.asList("low/index.m3u8", "https://cdn.example/hi/index.m3u8?t=1"), LiveHealth.uris(master));
        assertEquals(Arrays.asList("seg_100.ts", "seg_101.ts"), LiveHealth.uris(media));
        assertEquals("https://tv.example/live/ch1/low/index.m3u8", LiveHealth.resolve("https://tv.example/live/ch1/master.m3u8?token=9", "low/index.m3u8"));
        assertEquals("https://tv.example/x/seg.ts", LiveHealth.resolve("https://tv.example/live/a.m3u8", "/x/seg.ts"));
        assertEquals("https://cdn.example/hi/index.m3u8?t=1", LiveHealth.resolve("https://tv.example/live/a.m3u8", "https://cdn.example/hi/index.m3u8?t=1"));
        // the app's "url|Referer=…" form keeps its headers on every hop
        assertEquals("https://tv.example/live/seg_1.ts|Referer=https://site.example/", LiveHealth.resolve("https://tv.example/live/a.m3u8|Referer=https://site.example/", "seg_1.ts"));
    }

    @Test
    public void aRealSegmentIsRecognisedAndAnErrorPageIsNot() {
        byte[] ts = new byte[188];
        ts[0] = 0x47;
        assertTrue(LiveHealth.looksLikeMedia(ts, 188, "application/octet-stream"));
        byte[] mp4 = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        assertTrue(LiveHealth.looksLikeMedia(mp4, mp4.length, ""));
        byte[] html = "<html><body>403 Forbidden</body></html>".getBytes();
        assertFalse(LiveHealth.looksLikeMedia(html, html.length, "text/html"));
        assertFalse("an HTML page served as a video type is still not a video", LiveHealth.looksLikeMedia(html, html.length, "video/mp2t"));
        assertFalse(LiveHealth.looksLikeMedia(new byte[0], 0, "video/mp2t"));
        assertEquals("refused", LiveHealth.classifyHttp(403).reason);
        assertEquals("gone", LiveHealth.classifyHttp(404).reason);
        assertEquals("server_error", LiveHealth.classifyHttp(502).reason);
        assertEquals("ok", LiveHealth.classifyHttp(206).reason);
        LiveHealth.Report bad = LiveHealth.check("rtmp://not-http/stream");
        assertEquals(LiveHealth.DOWN, bad.state);
        assertEquals("bad_url", bad.reason);
        assertTrue(bad.detail().length() > 3 && "متوقفة".equals(bad.label()));
    }

    @Test
    public void whatTheAdminTypesInTheSubtitleLabIsUnderstood() {
        assertEquals(Long.valueOf(1500), SubLabActivity.seconds("1.5"));
        assertEquals(Long.valueOf(-800), SubLabActivity.seconds("-0.8"));
        assertEquals(Long.valueOf(-800), SubLabActivity.seconds("‎-0.8"));
        assertEquals(Long.valueOf(2500), SubLabActivity.seconds("٢٫٥"));
        assertEquals(null, SubLabActivity.seconds("abc"));
        assertEquals(Long.valueOf(3723500), SubLabActivity.clock("01:02:03.500"));
        assertEquals(Long.valueOf(3723500), SubLabActivity.clock("01:02:03,5"));
        assertEquals(Long.valueOf(125000), SubLabActivity.clock("2:05"));
        assertEquals(Long.valueOf(90000), SubLabActivity.clock("90"));
        assertEquals(null, SubLabActivity.clock("1:2:3:4"));
        assertEquals(null, SubLabActivity.clock(""));
    }
}
