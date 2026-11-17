package com.Cigram.subhub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Subtitle sources (OpenSubtitles, SubDL, Podnapisi, TVSubtitles): request building, answer parsing, archives, the
 * episode rule and failure handling — on fixtures in the shape each source documents / serves. No network is used:
 * what these tests prove is the parsers and the flow, NOT that a site still answers in this shape today.
 * The same file exists in the admin project; both copies must stay identical.
 */
public class SubProvidersTest {

    /** Fake transport: answers by URL prefix, records every call. */
    static final class Fake implements SubProviders.Http {
        final Map<String, SubProviders.Response> routes = new LinkedHashMap<String, SubProviders.Response>();
        final List<String> calls = new ArrayList<String>();
        final List<String> bodies = new ArrayList<String>();
        final List<Map<String, String>> headers = new ArrayList<Map<String, String>>();

        Fake on(String prefix, int code, byte[] body) { routes.put(prefix, new SubProviders.Response(code, body, "")); return this; }
        Fake on(String prefix, int code, String body) { return on(prefix, code, utf8(body)); }

        @Override public SubProviders.Response call(String url, Map<String, String> h, byte[] body, String contentType, int maxBytes) throws IOException {
            calls.add(url);
            bodies.add(body == null ? "" : new String(body, "UTF-8"));
            headers.add(h == null ? new LinkedHashMap<String, String>() : h);
            for (Map.Entry<String, SubProviders.Response> e : routes.entrySet()) if (url.startsWith(e.getKey())) return e.getValue();
            throw new IOException("no route for " + url);
        }
    }

    static byte[] utf8(String s) {
        try { return s.getBytes("UTF-8"); } catch (Exception e) { throw new RuntimeException(e); }
    }

    static byte[] zip(String... nameThenText) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(out);
        for (int i = 0; i + 1 < nameThenText.length; i += 2) {
            z.putNextEntry(new ZipEntry(nameThenText[i]));
            z.write(utf8(nameThenText[i + 1]));
            z.closeEntry();
        }
        z.close();
        return out.toByteArray();
    }

    static final String SRT = "1\n00:00:01,000 --> 00:00:03,000\nمرحباً\n\n2\n00:00:04,000 --> 00:00:06,000\nكيف حالك؟\n";

    static SubProviders.Query film() {
        SubProviders.Query q = new SubProviders.Query();
        q.title = "The Matrix";
        q.year = 1999;
        q.imdbId = "tt0133093";
        return q;
    }

    static SubProviders.Query episode() {
        SubProviders.Query q = new SubProviders.Query();
        q.title = "Breaking Bad";
        q.year = 2008;
        q.imdbId = "tt0903747";
        q.season = 2;
        q.episode = 5;
        return q;
    }

    // ================================================================ OpenSubtitles

    static final String OS_ANSWER = "{\"total_pages\":1,\"total_count\":2,\"page\":1,\"data\":["
            + "{\"id\":\"9000\",\"type\":\"subtitle\",\"attributes\":{\"subtitle_id\":\"9000\",\"language\":\"ar\",\"download_count\":41234,"
            + "\"hearing_impaired\":false,\"release\":\"The.Matrix.1999.1080p.BluRay \\u2014 \\\"final\\\"\",\"url\":\"https://www.opensubtitles.com/ar/subtitles/x\","
            + "\"feature_details\":{\"feature_id\":1,\"feature_type\":\"Movie\",\"year\":1999,\"title\":\"The Matrix\",\"imdb_id\":133093},"
            + "\"files\":[{\"file_id\":777001,\"cd_number\":1,\"file_name\":\"The.Matrix.ar.srt\"}]}},"
            + "{\"id\":\"9001\",\"type\":\"subtitle\",\"attributes\":{\"language\":\"en\",\"download_count\":900000,\"hearing_impaired\":true,"
            + "\"release\":\"The.Matrix.1999.WEB\",\"feature_details\":{\"year\":1999,\"title\":\"The Matrix\"},\"files\":[{\"file_id\":777002}]}},"
            + "{\"id\":\"9002\",\"attributes\":{\"language\":\"fr\",\"files\":[]}}]}";

    @Test
    public void openSubtitlesIsAskedTheDocumentedWayAndItsAnswerIsRead() throws Exception {
        SubProviders.OpenSubtitles os = new SubProviders.OpenSubtitles("KEY");
        assertEquals("https://api.opensubtitles.com/api/v1/subtitles?imdb_id=133093&languages=ar,en&type=movie", os.searchUrl(film()));
        assertEquals("https://api.opensubtitles.com/api/v1/subtitles?episode_number=5&languages=ar,en&parent_imdb_id=903747&season_number=2",
                os.searchUrl(episode()));
        SubProviders.Query byName = new SubProviders.Query();
        byName.title = "Some Film";
        byName.year = 2020;
        assertEquals("https://api.opensubtitles.com/api/v1/subtitles?languages=ar,en&query=some+film&type=movie&year=2020", os.searchUrl(byName));

        Fake http = new Fake().on("https://api.opensubtitles.com/api/v1/subtitles", 200, OS_ANSWER);
        List<SubProviders.Result> r = os.search(film(), http);
        assertEquals("KEY", http.headers.get(0).get("Api-Key"));
        assertEquals(2, r.size());                       // the French entry has no file: left out
        assertEquals("ar", r.get(0).language);
        assertEquals("777001", r.get(0).handle);
        assertEquals(41234, r.get(0).downloads);
        assertEquals("The.Matrix.1999.1080p.BluRay — \"final\"", r.get(0).release);
        assertEquals(1999, r.get(0).year);
        assertTrue(r.get(1).hearingImpaired);
        assertTrue(r.get(0).label().startsWith("العربية · OpenSubtitles"));
    }

    @Test
    public void openSubtitlesDownloadAsksForALinkThenFetchesTheFile() throws Exception {
        SubProviders.OpenSubtitles os = new SubProviders.OpenSubtitles("KEY");
        SubProviders.Result r = new SubProviders.Result();
        r.handle = "777001";
        Fake http = new Fake()
                .on("https://api.opensubtitles.com/api/v1/download", 200, "{\"link\":\"https://dl.opensubtitles.com/file/abc.srt\",\"file_name\":\"a.srt\",\"remaining\":4}")
                .on("https://dl.opensubtitles.com/file/abc.srt", 200, SRT);
        byte[] file = os.download(r, http);
        assertEquals("{\"file_id\":777001,\"sub_format\":\"srt\"}", http.bodies.get(0));
        assertEquals(2, SubCodec.read(file).size());

        // quota used up / key refused / a link that is not https: clear reasons, never a file
        try { os.download(r, new Fake().on("https://api.opensubtitles.com/api/v1/download", 406, "{}")); fail(); }
        catch (SubProviders.ProviderException e) { assertEquals(406, e.status); }
        try { os.search(film(), new Fake().on("https://api.opensubtitles.com", 403, "{\"message\":\"You cannot consume this service\"}")); fail(); }
        catch (SubProviders.ProviderException e) { assertEquals(403, e.status); }
        try { os.download(r, new Fake().on("https://api.opensubtitles.com/api/v1/download", 200, "{\"link\":\"http://plain/file.srt\"}")); fail(); }
        catch (SubProviders.ProviderException e) { assertEquals(0, e.status); }
        assertFalse("no key: the provider is skipped, not asked", new SubProviders.OpenSubtitles("").supports(film()));
    }

    // ================================================================ SubDL

    static final String SUBDL_ANSWER = "{\"status\":true,\"results\":[{\"sd_id\":1,\"type\":\"tv\",\"name\":\"Breaking Bad\",\"imdb_id\":\"tt0903747\",\"year\":2008}],"
            + "\"subtitles\":["
            + "{\"release_name\":\"Breaking.Bad.S02E05.720p\",\"name\":\"SUBDL::bb-s02e05-ar.zip\",\"lang\":\"arabic\",\"language\":\"AR\",\"author\":\"x\",\"url\":\"/subtitle/100-200.zip\",\"season\":2,\"episode\":5,\"hi\":false},"
            + "{\"release_name\":\"Breaking.Bad.S02.COMPLETE\",\"name\":\"pack\",\"lang\":\"arabic\",\"language\":\"AR\",\"url\":\"/subtitle/100-201.zip\",\"season\":2,\"episode\":0,\"episode_from\":1,\"episode_end\":13,\"hi\":false},"
            + "{\"release_name\":\"Breaking.Bad.S02E06-E13\",\"name\":\"late pack\",\"lang\":\"english\",\"language\":\"EN\",\"url\":\"/subtitle/100-202.zip\",\"season\":2,\"episode\":0,\"episode_from\":6,\"episode_end\":13,\"hi\":true}"
            + "]}";

    @Test
    public void subDlIsAskedByIdAndASeasonPackGivesOnlyTheWantedEpisode() throws Exception {
        SubProviders.SubDl sd = new SubProviders.SubDl("KEY");
        assertEquals("https://api.subdl.com/api/v1/subtitles?api_key=KEY&imdb_id=tt0903747&type=tv&season_number=2&episode_number=5&languages=AR,EN&subs_per_page=30",
                sd.searchUrl(episode()));
        assertEquals("https://api.subdl.com/api/v1/subtitles?api_key=KEY&imdb_id=tt0133093&type=movie&languages=AR,EN&subs_per_page=30", sd.searchUrl(film()));
        Fake http = new Fake().on("https://api.subdl.com/api/v1/subtitles", 200, SUBDL_ANSWER)
                .on("https://dl.subdl.com/subtitle/100-201.zip", 200, zip("Breaking.Bad.S02E04.srt", "1\n00:00:01,000 --> 00:00:02,000\nالحلقة الرابعة\n",
                        "Breaking.Bad.S02E05.srt", SRT, "Breaking.Bad.S02E05.nfo", "junk", "Breaking.Bad.S02E15.srt", "1\n00:00:01,000 --> 00:00:02,000\nخمسة عشر\n"));
        List<SubProviders.Result> r = sd.search(episode(), http);
        assertEquals("the pack that does not contain episode 5 is left out", 2, r.size());
        assertEquals("ar", r.get(0).language);
        assertEquals(5, r.get(1).episode);
        byte[] file = sd.download(r.get(1), http);
        assertEquals("كيف حالك؟", SubCodec.read(file).cues.get(1).text);

        // the live answer for a refused key (recorded 2026-11-11)
        try { sd.search(film(), new Fake().on("https://api.subdl.com", 403, "{\"status\":false,\"statusCode\":403,\"error\":\"not_authorized\",\"message\":\"Not Authorized\"}")); fail(); }
        catch (SubProviders.ProviderException e) { assertEquals(403, e.status); }
        // "nothing found" is an empty list, not an error
        assertTrue(sd.search(film(), new Fake().on("https://api.subdl.com", 200, "{\"status\":false,\"error\":\"can't find movie or tv\"}")).isEmpty());
        SubProviders.Result bad = new SubProviders.Result();
        bad.handle = "/../secret";
        try { sd.download(bad, http); fail("a path that climbs out is refused"); } catch (SubProviders.ProviderException expected) { }
    }

    // ================================================================ Podnapisi

    static final String PODNAPISI_XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><results><pagination><current>1</current><count>1</count><results>2</results></pagination>"
            + "<subtitle><id>123</id><pid>AbCd</pid><title>The Matrix</title><year>1999</year><url>https://www.podnapisi.net/ar/subtitles/ar-the-matrix-1999/AbCd</url>"
            + "<uploaderId>1</uploaderId><tvSeason>0</tvSeason><tvEpisode>0</tvEpisode><language>ar</language><languageId>12</languageId>"
            + "<release>The.Matrix.1999.BluRay The.Matrix.1999.WEB</release><flags>n</flags><downloads>512</downloads><rating>4.5</rating></subtitle>"
            + "<subtitle><pid>EfGh</pid><title><![CDATA[The Matrix & Friends]]></title><year>1999</year><tvSeason>0</tvSeason><tvEpisode>0</tvEpisode>"
            + "<language>ar</language><release></release><flags></flags><downloads>7</downloads></subtitle>"
            + "<subtitle><pid>bad id!</pid><title>x</title></subtitle></results>";

    @Test
    public void podnapisiSearchAnswerIsReadAndTheZipIsOpened() throws Exception {
        SubProviders.Podnapisi p = new SubProviders.Podnapisi();
        assertEquals("https://www.podnapisi.net/subtitles/search/old?sXML=1&sL=ar&sK=The+Matrix&sY=1999", p.searchUrl(film(), "ar", 1));
        assertEquals("https://www.podnapisi.net/subtitles/search/old?sXML=1&sL=en&sK=Breaking+Bad&sTS=2&sTE=5", p.searchUrl(episode(), "en", 1));
        List<SubProviders.Result> r = p.parse(PODNAPISI_XML, "ar");
        assertEquals(2, r.size());
        assertEquals("AbCd", r.get(0).handle);
        assertEquals("The.Matrix.1999.BluRay", r.get(0).release);
        assertTrue(r.get(0).hearingImpaired);
        assertEquals(512, r.get(0).downloads);
        assertEquals("The Matrix & Friends", r.get(1).title);
        Fake http = new Fake().on("https://www.podnapisi.net/subtitles/AbCd/download", 200, zip("readme.txt", "hello", "The.Matrix.srt", SRT));
        assertEquals(2, SubCodec.read(p.download(r.get(0), http)).size());
        assertEquals("https://www.podnapisi.net/subtitles/AbCd/download?container=zip", http.calls.get(0));
        // a page that is not the search answer (the site changed, a challenge page): a clear failure
        try { p.search(film(), new Fake().on("https://www.podnapisi.net", 200, "<html><body>Just a moment...</body></html>")); fail(); }
        catch (SubProviders.ProviderException expected) { }
    }

    // ================================================================ TVSubtitles

    static final String TVS_SEARCH = "<html><body><div class=\"left_articles\"><ul>"
            + "<li><div style=\"float:left\"><a href=\"/tvshow-99.html\">Breaking Bad Extras (2009-2010)</a></div></li>"
            + "<li><div style=\"float:left\"><a href=\"/tvshow-88.html\">Breaking Bad (2008-2013)</a></div></li></ul></div></body></html>";
    static final String TVS_SEASON = "<table id=\"table5\"><tr><th>#</th><th>title</th></tr>"
            + "<tr align=\"middle\"><td>2x04</td><td align=\"left\"><a href=\"episode-7003.html\"><b>Down</b></a></td><td>12</td></tr>"
            + "<tr align=\"middle\"><td>2x05</td><td align=\"left\"><a href=\"episode-7004.html\"><b>Breakage</b></a></td><td>9</td></tr>"
            + "<tr align=\"middle\"><td>2x15</td><td align=\"left\"><a href=\"episode-7099.html\"><b>x</b></a></td></tr></table>";
    static final String TVS_EPISODE = "<div class=\"left_articles\">"
            + "<a href=\"/subtitle-55001.html\"><div class=\"subtitlen\"><div style=\"float:left\"><h5 style=\"display:inline\"><img src=\"images/flags/ar.gif\" width=\"18\"> Breaking Bad 2x05 (HDTV.XOR)</h5>"
            + "<p title=\"rip\">HDTV</p><p title=\"downloaded\"><img src=\"images/downloads.png\"> 412</p></div></div></a>"
            + "<a href=\"/subtitle-55002.html\"><div class=\"subtitlen\"><div style=\"float:left\"><h5 style=\"display:inline\"><img src=\"images/flags/gr.gif\"> Breaking Bad 2x05</h5></div></div></a>"
            + "<a href=\"/subtitle-55003.html\"><div class=\"subtitlen\"><h5><img src=\"images/flags/en.gif\"> Breaking Bad 2x05 (720p)</h5></div></a></div>";

    @Test
    public void tvSubtitlesIsWalkedFromShowToSeasonToEpisodeToFile() throws Exception {
        SubProviders.TvSubtitles t = new SubProviders.TvSubtitles();
        assertFalse("episodes only", t.supports(film()));
        assertEquals("the exact show, not the look-alike", 88, t.showId(TVS_SEARCH, "Breaking Bad", 2008));
        assertEquals(0, t.showId(TVS_SEARCH, "Better Call Saul", 2015));
        Map<Integer, Integer> eps = t.episodes(TVS_SEASON, 2);
        assertEquals(Integer.valueOf(7004), eps.get(5));
        assertEquals(3, eps.size());
        Fake http = new Fake()
                .on("https://www.tvsubtitles.net/search.php", 200, TVS_SEARCH)
                .on("https://www.tvsubtitles.net/tvshow-88-2.html", 200, TVS_SEASON)
                .on("https://www.tvsubtitles.net/episode-7004.html", 200, TVS_EPISODE)
                .on("https://www.tvsubtitles.net/download-55001.html", 200, zip("Breaking Bad - 2x05 - Breakage.ar.srt", SRT));
        List<SubProviders.Result> r = t.search(episode(), http);
        assertEquals("qs=Breaking+Bad", http.bodies.get(0));
        assertEquals(3, r.size());
        assertEquals("ar", r.get(0).language);
        assertEquals("el", r.get(1).language);              // the site's "gr" flag is Greek
        assertEquals(412, r.get(0).downloads);
        assertEquals("Breaking Bad 2x05 (HDTV.XOR)", r.get(0).release);
        assertEquals(2, SubCodec.read(t.download(r.get(0), http)).size());
        // a show the site does not have: no results, no error
        assertTrue(t.search(episode(), new Fake().on("https://www.tvsubtitles.net/search.php", 200, "<html>nothing</html>")).isEmpty());
    }

    // ================================================================ archives and the episode rule

    @Test
    public void anEpisodeNeverTakesAnotherEpisodesFile() throws Exception {
        byte[] pack = zip("Show.S02E04.srt", "1\n00:00:01,000 --> 00:00:02,000\nأربعة\n", "Show.2x05.vtt", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nخمسة\n");
        assertEquals("خمسة", SubCodec.read(SubProviders.fromArchive(pack, 2, 5)).cues.get(0).text);
        try { SubProviders.fromArchive(pack, 2, 9); fail("episode 9 is not in the archive"); } catch (IOException expected) { }
        // E05 must not match E050 or S12E05
        byte[] tricky = zip("Show.S12E05.srt", SRT, "Show.S02E050.srt", SRT);
        try { SubProviders.fromArchive(tricky, 2, 5); fail(); } catch (IOException expected) { }
        // a film archive: the .srt is preferred over the .vtt, text files are ignored
        byte[] filmZip = zip("a.txt", "x", "film.vtt", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nvtt\n", "film.srt", SRT);
        assertEquals(2, SubCodec.read(SubProviders.fromArchive(filmZip, 0, 0)).size());
        assertEquals("not a zip: the bytes are the subtitle", SRT.length() > 0, SubProviders.fromArchive(utf8(SRT), 0, 0).length > 0);
        try { SubProviders.fromArchive(zip("only.txt", "x"), 0, 0); fail(); } catch (IOException expected) { }

        SubProviders.Result wrongEpisode = new SubProviders.Result();
        wrongEpisode.season = 2;
        wrongEpisode.episode = 6;
        assertFalse(SubProviders.matches(episode(), wrongEpisode));
        SubProviders.Result episodeForFilm = new SubProviders.Result();
        episodeForFilm.season = 1;
        episodeForFilm.episode = 1;
        assertFalse(SubProviders.matches(film(), episodeForFilm));
    }

    @Test
    public void severalSourcesAreAskedArabicComesFirstAndAFailingSourceIsOnlyANote() throws Exception {
        Fake http = new Fake()
                .on("https://api.opensubtitles.com/api/v1/subtitles", 200, OS_ANSWER)
                .on("https://api.subdl.com", 403, "{\"status\":false,\"error\":\"not_authorized\"}")
                .on("https://www.podnapisi.net/subtitles/search/old?sXML=1&sL=ar", 200, PODNAPISI_XML)
                .on("https://www.podnapisi.net/subtitles/search/old?sXML=1&sL=en", 200, "<results><pagination><results>0</results></pagination></results>");
        SubProviders.Outcome o = SubProviders.searchAll(film(), SubProviders.all("KEY", "KEY2"), http);
        assertEquals(4, o.results.size());                         // OpenSubtitles ar + en, Podnapisi ar × 2
        assertEquals("ar", o.results.get(0).language);
        assertEquals("ar", o.results.get(2).language);
        assertEquals("the English file, however popular, comes after every Arabic one", "en", o.results.get(3).language);
        assertEquals("opensubtitles", o.results.get(0).provider);  // 41 234 downloads beat 512
        assertTrue(o.notes.get("subdl").contains("403"));
        assertTrue(o.notes.get("tvsubtitles").contains("لا يدعم"));
        assertFalse(o.notes.containsKey("opensubtitles"));
        // without keys the two key-based sources say so and the others still work
        SubProviders.Outcome noKeys = SubProviders.searchAll(film(), SubProviders.all("", ""), http);
        assertEquals(2, noKeys.results.size());
        assertTrue(noKeys.notes.get("opensubtitles").contains("مفتاح"));
        assertNotNull(noKeys.notes.get("subdl"));
    }

    @Test
    public void idsAreNormalisedForEachSource() {
        SubProviders.Query q = new SubProviders.Query();
        q.imdbId = "tt0133093";
        assertEquals("133093", q.imdbDigits());
        assertEquals("tt0133093", q.imdbTt());
        q.imdbId = "903747";
        assertEquals("tt0903747", q.imdbTt());
        q.imdbId = "tt12345678";
        assertEquals("tt12345678", q.imdbTt());
        q.imdbId = "not-an-id";
        assertEquals("", q.imdbDigits());
        assertEquals("", q.imdbTt());
        assertEquals("a \"b\" é", SubProviders.jsonUnescape("a \\\"b\\\" \\u00e9"));
    }
}
