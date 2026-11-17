package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * «أحدث الأفلام» and «المسلسلات المميزة» are ordinary Home sections: they come out of the SAME grouping as the administrator's sections
 * (so they are built by the same row builder, found by the same «المزيد» page and ordered by the same admin plan), newest first, with a
 * title shown in one section only.
 */
public class BuiltInSectionsTest {

    private static final long T0 = 1_790_000_000_000L;

    private static JSONObject movie(int n, String title) throws Exception {
        return new JSONObject().put("id", "m" + n).put("type", "movie").put("title", title).put("year", "2020")
                .put("poster", "https://x/p" + n + ".jpg").put("video", "https://x/v" + n + ".mp4").put("created_at_ms", T0 + n * 1000L);
    }

    private static JSONObject series(int n) throws Exception {
        return new JSONObject().put("id", "s" + n).put("series_id", "s" + n).put("type", "series").put("title", "Series " + n)
                .put("poster", "https://x/sp" + n + ".jpg").put("created_at_ms", T0 + n * 1000L);
    }

    private static JSONObject episode(int s, int e) throws Exception {
        return new JSONObject().put("id", "s" + s + "e" + e).put("type", "episode").put("series_id", "s" + s).put("season", "1")
                .put("episode", String.valueOf(e)).put("title", "Series " + s).put("poster", "https://x/sp" + s + ".jpg")
                .put("video", "https://x/e" + s + "_" + e + ".mp4").put("created_at_ms", T0 + s * 1000L + e);
    }

    private static String catalogue() throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 1; i <= 6; i++) a.put(movie(i, "Film " + i));
        a.put(movie(7, "Film 3"));                                       // the same title again: one card
        a.put(movie(8, "Anime Film").put("section_id", "anime").put("section_name", "أنمي"));   // lives in a custom section
        for (int i = 1; i <= 3; i++) {
            a.put(series(i));
            for (int e = 1; e <= 2; e++) a.put(episode(i, e));
        }
        return a.toString();
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, Object> group(String json) throws Exception {
        Method m = Class.forName("com.Cigram.vid.CigramStage1InitProvider").getDeclaredMethod("groupCatalog", String.class);
        m.setAccessible(true);
        return (LinkedHashMap<String, Object>) m.invoke(null, json);
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static Object sectionById(LinkedHashMap<String, Object> grouped, String id) throws Exception {
        for (Object s : grouped.values()) if (id.equals(field(s, "id"))) return s;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> titles(Object section) throws Exception {
        List<String> out = new ArrayList<String>();
        for (JSONObject item : ((LinkedHashMap<String, JSONObject>) field(section, "items")).values()) {
            out.add(item.optString("series_title", item.optString("title")));
        }
        return out;
    }

    @Test
    public void latestMoviesIsJustAnotherSectionOfTheSameGrouping() throws Exception {
        LinkedHashMap<String, Object> grouped = group(catalogue());
        Object latest = sectionById(grouped, "latest_movies");
        assertNotNull("the section exists in the one list Home draws", latest);
        assertEquals("أحدث الأفلام", field(latest, "name"));
        assertFalse("it has a «المزيد» page like every other section", (Boolean) field(latest, "noMore"));
        assertTrue("the grouping hands it over already newest first", (Boolean) field(latest, "keepOrder"));
        assertEquals("one key per section, in the same map as the administrator's", "id:" + CigramStage1InitProvider.cigramNormalize("latest_movies"),
                field(latest, "key"));
    }

    @Test
    public void itIsNewestFirstWithOneCardPerTitleAndNothingFromACustomSection() throws Exception {
        List<String> titles = titles(sectionById(group(catalogue()), "latest_movies"));
        assertEquals("newest first, the duplicate title once, the anime film not here", 6, titles.size());
        assertEquals("Film 3", titles.get(0));      // m7 is the newest copy of "Film 3"
        assertFalse(titles.contains("Anime Film"));
        int seen = 0;
        for (String t : titles) if (t.equals("Film 3")) seen++;
        assertEquals(1, seen);
    }

    @Test
    public void theCustomSectionStillHasItsFilm() throws Exception {
        Object anime = sectionById(group(catalogue()), "anime");
        assertNotNull(anime);
        assertTrue(titles(anime).contains("Anime Film"));
    }

    @Test
    public void featuredSeriesIsTheSameKindOfSectionWithItsSeries() throws Exception {
        Object featured = sectionById(group(catalogue()), "featured_series");
        assertNotNull(featured);
        assertEquals("المسلسلات المميزة", field(featured, "name"));
        List<String> titles = titles(featured);
        assertEquals(3, titles.size());
        assertEquals("Series 3", titles.get(0));
    }

    @Test
    public void aCatalogueWithoutFilmsOrSeriesDoesNotInventEmptySections() throws Exception {
        JSONArray a = new JSONArray();
        a.put(movie(1, "Only Film"));
        LinkedHashMap<String, Object> grouped = group(a.toString());
        assertNotNull(sectionById(grouped, "latest_movies"));
        assertEquals("no series in the data = no series section", null, sectionById(grouped, "featured_series"));
    }

    @Test
    public void groupingSurvivesRubbish() throws Exception {
        try {
            group("[]");
            group("[{\"nothing\":1}]");
        } catch (java.lang.reflect.InvocationTargetException e) {
            // an unreadable catalogue may throw, but never from inside the built-in section code (that has its own catch)
            assertFalse(String.valueOf(e.getCause()), e.getCause() instanceof NullPointerException);
        }
    }
}
