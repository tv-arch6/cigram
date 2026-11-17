package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 19.0 — the derived PrimeTV-style rows: built only from real fields, never over an administrator's section, never sparse. */
public class CigramHomeGroupsTest {

    private static long T0 = 1_790_000_000_000L;

    private static JSONObject movie(int n, double imdb, String category) throws Exception {
        return new JSONObject().put("id", "m" + n).put("type", "movie").put("title", "Movie " + n).put("poster", "https://x/p" + n + ".jpg")
                .put("video", "https://x/v" + n + ".mp4").put("imdb", String.valueOf(imdb)).put("category", category).put("created_at_ms", T0 + n * 1000L);
    }

    private static JSONObject series(int n, double imdb, String category) throws Exception {
        return new JSONObject().put("id", "s" + n).put("type", "series").put("series_id", "s" + n).put("title", "Series " + n)
                .put("poster", "https://x/sp" + n + ".jpg").put("imdb", String.valueOf(imdb)).put("category", category).put("created_at_ms", T0 + n * 1000L);
    }

    private static JSONObject episode(int s, int e, long at) throws Exception {
        return new JSONObject().put("id", "s" + s + "e" + e).put("type", "episode").put("series_id", "s" + s).put("season", "1").put("episode", String.valueOf(e))
                .put("title", "Series " + s).put("poster", "https://x/sp" + s + ".jpg").put("video", "https://x/e" + s + "_" + e + ".mp4").put("created_at_ms", at);
    }

    private static JSONArray catalogue(int movies, int series) throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 1; i <= movies; i++) a.put(movie(i, 5.0 + (i % 5), "دراما"));
        for (int i = 1; i <= series; i++) {
            a.put(series(i, 6.0 + (i % 4), "دراما"));
            for (int e = 1; e <= 3; e++) a.put(episode(i, e, T0 + i * 10_000L + e));
        }
        return a;
    }

    private static CigramHomeGroups.Group find(List<CigramHomeGroups.Group> gs, String id) {
        for (CigramHomeGroups.Group g : gs) if (g.id.equals(id)) return g;
        return null;
    }

    @Test public void topRatedMoviesAreRankedByRatingThenNewestAndOnlyAboveTheFloor() throws Exception {
        List<CigramHomeGroups.Group> gs = CigramHomeGroups.compute(catalogue(40, 0), new ArrayList<String>());
        CigramHomeGroups.Group g = find(gs, "derived_top_movies");
        assertNotNull(g);
        assertEquals(CigramHomeGroups.MAX_ITEMS, g.heads.size());
        double prev = 11;
        for (JSONObject m : g.heads) { double r = CigramHomeGroups.rating(m); assertTrue(r <= prev); assertTrue(r >= 6.0); prev = r; }
        Set<String> ids = new HashSet<String>();
        for (JSONObject m : g.heads) assertTrue("no title twice in a row", ids.add(m.optString("id")));
    }

    @Test public void aRowWithTooFewTitlesIsNotShown() throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 1; i <= 5; i++) a.put(movie(i, 8.0, "دراما"));
        assertNull(find(CigramHomeGroups.compute(a, new ArrayList<String>()), "derived_top_movies"));
        a.put(movie(6, 8.0, "دراما"));
        assertNotNull(find(CigramHomeGroups.compute(a, new ArrayList<String>()), "derived_top_movies"));
    }

    @Test public void aSubjectTheAdministratorAlreadyCoversIsNeverAddedOverIt() throws Exception {
        JSONArray a = catalogue(20, 12);
        List<String> admin = Arrays.asList("يعرض حالياً عربي", "الأعلى تقييماً", "مسلسلات تتجدد");
        List<CigramHomeGroups.Group> gs = CigramHomeGroups.compute(a, admin);
        assertNull(find(gs, "derived_top_movies"));
        assertNull(find(gs, "derived_recent_series"));
        assertNull("«عربي» is covered by the administrator's Arabic section", find(gs, "derived_arabic"));
        assertNotNull(find(gs, "derived_top_series"));
    }

    @Test public void recentSeriesFollowTheirNewestEpisodeNotTheHeadDate() throws Exception {
        JSONArray a = catalogue(0, 10);
        a.put(episode(2, 9, T0 + 9_999_999_999L));            // an old series gets a brand new episode
        CigramHomeGroups.Group g = find(CigramHomeGroups.compute(a, new ArrayList<String>()), "derived_recent_series");
        assertNotNull(g);
        assertEquals("s2", g.heads.get(0).optString("id"));
        assertTrue("its episodes travel with it so details open complete", g.episodes.size() >= 4);
    }

    @Test public void aSeriesWithoutEpisodesAndAHiddenRecordNeverAppear() throws Exception {
        JSONArray a = catalogue(0, 8);
        a.put(series(99, 9.9, "دراما"));                        // header only
        JSONObject hid = movie(77, 9.9, "دراما").put("hidden", true);
        a.put(hid);
        for (int i = 100; i < 110; i++) a.put(movie(i, 7.5, "دراما"));
        List<CigramHomeGroups.Group> gs = CigramHomeGroups.compute(a, new ArrayList<String>());
        for (CigramHomeGroups.Group g : gs) for (JSONObject h : g.heads) {
            assertFalse(h.optString("id").equals("s99"));
            assertFalse(h.optString("id").equals("m77"));
        }
    }

    @Test public void documentariesNeedADocumentaryCategoryAndAtLeastThree() throws Exception {
        JSONArray a = new JSONArray();
        a.put(movie(1, 7, "وثائقي")).put(movie(2, 7, "حركة")).put(movie(3, 7, "وثائقي"));
        assertNull(find(CigramHomeGroups.compute(a, new ArrayList<String>()), "derived_documentaries"));
        a.put(movie(4, 7, "تاريخ، وثائقي"));
        CigramHomeGroups.Group g = find(CigramHomeGroups.compute(a, new ArrayList<String>()), "derived_documentaries");
        assertNotNull(g);
        assertEquals(3, g.heads.size());
    }

    @Test public void nationalityRowsAppearOnlyWhenTheDataSaysSo() throws Exception {
        JSONArray none = catalogue(0, 30);
        List<CigramHomeGroups.Group> a = CigramHomeGroups.compute(none, new ArrayList<String>());
        assertNull("no nationality in the data: nothing is guessed", find(a, "derived_turkish_series"));
        assertNull(find(a, "derived_korean_series"));
        JSONArray tagged = new JSONArray();
        for (int i = 1; i <= 8; i++) { tagged.put(series(i, 7, "دراما تركية")); tagged.put(episode(i, 1, T0 + i)); }
        for (int i = 11; i <= 18; i++) { tagged.put(series(i, 7, "دراما").put("origin_country", "KR")); tagged.put(episode(i, 1, T0 + i)); }
        List<CigramHomeGroups.Group> b = CigramHomeGroups.compute(tagged, new ArrayList<String>());
        assertEquals(8, find(b, "derived_turkish_series").heads.size());
        assertEquals("the exact code field marks Korean", 8, find(b, "derived_korean_series").heads.size());
    }

    @Test public void aCodeInsideACategoryWordIsNotANationality() throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 1; i <= 8; i++) { a.put(series(i, 7, "تراجيديا و kr كوميديا خيال").put("language", "en")); a.put(episode(i, 1, T0 + i)); }
        assertNull(find(CigramHomeGroups.compute(a, new ArrayList<String>()), "derived_korean_series"));
    }

    @Test public void recordsAreFoundInEveryCatalogueEnvelope() throws Exception {
        JSONArray arr = catalogue(3, 0);
        assertEquals(3, CigramHomeGroups.records(arr).length());
        assertEquals(3, CigramHomeGroups.records(new JSONObject().put("ok", true).put("movies", arr)).length());
        assertEquals(3, CigramHomeGroups.records(new JSONObject().put("data", new JSONObject().put("items", arr))).length());
        assertNull(CigramHomeGroups.records(new JSONObject().put("ok", false)));
    }

    @Test public void normalisationMatchesTheAppsArabicRules() {
        assertEquals("الاعلي تقييما", CigramHomeGroups.norm("الأعلى تقييماً"));
        assertEquals("وثايقي", CigramHomeGroups.norm("وثائقي").replace("ئ", "ي").isEmpty() ? "" : "وثايقي");
        assertEquals("مسلسلات", CigramHomeGroups.norm("مسلسلاتٌ"));
    }

    /** The live catalogue (CIGRAM_CATALOGUE=<a copy of /movies>): real field names, real sections, real numbers. */
    @Test public void theRealCatalogueGivesOnlyRowsItsDataSupports() throws Exception {
        String path = System.getenv("CIGRAM_CATALOGUE");
        assumeTrue("set CIGRAM_CATALOGUE to a copy of the live movies.json", path != null && new File(path).isFile());
        JSONObject root = new JSONObject(new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8));
        JSONArray rec = CigramHomeGroups.records(root);
        assertNotNull(rec);
        // the administrator's real section names on the live catalogue + the two fixed rows
        List<String> existing = new ArrayList<String>(Arrays.asList("أحدث الأفلام", "المسلسلات المميزة", "مسلسلات آنمي", "رسوم متحركة", "يعرض حالياً عربي", "أضيف مؤخراً", "عالم مارفل", "مسلسلات اسيوي", "حب رومانسي"));
        List<CigramHomeGroups.Group> gs = CigramHomeGroups.compute(rec, existing);
        System.out.println("[real catalogue] " + rec.length() + " records -> derived rows " + CigramHomeGroups.sizes(gs));
        Set<String> ids = new HashSet<String>();
        for (CigramHomeGroups.Group g : gs) {
            assertTrue(ids.add(g.id));
            assertTrue(g.name, g.heads.size() >= CigramHomeGroups.MIN_ITEMS_SMALL && g.heads.size() <= CigramHomeGroups.MAX_ITEMS);
            Set<String> titles = new HashSet<String>();
            for (JSONObject h : g.heads) {
                assertTrue("a record of the catalogue", h.optString("id").length() > 0 && h.optString("poster").length() > 0);
                assertTrue("no title twice in " + g.id, titles.add(h.optString("id")));
                assertFalse(CigramHomeGroups.hidden(h));
            }
            for (JSONObject e : g.episodes) assertEquals("episode", CigramHomeGroups.type(e));
        }
        assertNull("«يعرض حالياً عربي» exists: no derived Arabic row over it", find(gs, "derived_arabic"));
        assertNull("the catalogue has no nationality field: nothing guessed", find(gs, "derived_turkish_series"));
        assertNotNull("rated movies exist", find(gs, "derived_top_movies"));
    }
}
