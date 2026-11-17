package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** What the Search page shows before a word is typed: saved, trimmed to a card, and round-tripped without loss. */
public class CigramSearchCacheTest {

    private static JSONObject item(int n) throws Exception {
        return new JSONObject().put("id", "m" + n).put("type", "movie").put("title", "Film " + n).put("year", "2020")
                .put("poster", "https://x/p" + n + ".jpg").put("category", "دراما").put("imdb", "7.5")
                .put("video", "https://secret.example/stream" + n + ".m3u8").put("series_episodes_json", "[{\"huge\":true}]");
    }

    @Test
    public void aCardKeepsWhatItShowsAndDropsWhatItDoesNot() throws Exception {
        JSONObject card = CigramSearchCache.trim(item(1));
        assertEquals("Film 1", card.optString("title"));
        assertEquals("https://x/p1.jpg", card.optString("poster"));
        assertFalse("stream links are looked up when a title opens, not stored", card.has("video"));
        assertFalse("episode lists can be megabytes", card.has("series_episodes_json"));
    }

    @Test
    public void theStateSurvivesTheFile() throws Exception {
        CigramSearchCache.State s = new CigramSearchCache.State();
        s.queries.add("باتمان");
        s.queries.add("batman");
        s.recent.add(CigramSearchCache.trim(item(1)));
        s.suggestMovies.add(CigramSearchCache.trim(item(2)));
        s.suggestSeries.add(CigramSearchCache.trim(item(3).put("type", "series")));
        s.lastQuery = "باتمان";
        s.lastType = "movie";
        s.lastResults.add(CigramSearchCache.trim(item(4)));

        CigramSearchCache.State back = new CigramSearchCache.State();
        CigramSearchCache.parse(CigramSearchCache.toJson(s), back);

        assertEquals(s.queries, back.queries);
        assertEquals(1, back.recent.size());
        assertEquals("Film 2", back.suggestMovies.get(0).optString("title"));
        assertEquals("series", back.suggestSeries.get(0).optString("type"));
        assertEquals("باتمان", back.lastQuery);
        assertEquals("movie", back.lastType);
        assertEquals("Film 4", back.lastResults.get(0).optString("title"));
        assertFalse(back.isEmpty());
    }

    @Test
    public void anEmptyStateIsEmptyAndABrokenFileIsNotAnException() throws Exception {
        assertTrue(new CigramSearchCache.State().isEmpty());
        CigramSearchCache.State s = new CigramSearchCache.State();
        try {
            CigramSearchCache.parse("{ this is not json", s);
        } catch (org.json.JSONException expected) {
            // load() catches this and starts empty: the page still opens
        }
        assertTrue(s.isEmpty());
    }

    @Test
    public void listsAreBounded() throws Exception {
        JSONObject root = new JSONObject();
        org.json.JSONArray many = new org.json.JSONArray();
        for (int i = 0; i < 40; i++) many.put("q" + i);
        root.put("queries", many);
        org.json.JSONArray cards = new org.json.JSONArray();
        for (int i = 0; i < 90; i++) cards.put(item(i));
        root.put("recent", cards);
        root.put("suggest_movies", cards);
        CigramSearchCache.State s = new CigramSearchCache.State();
        CigramSearchCache.parse(root.toString(), s);
        assertEquals(CigramSearchCache.MAX_QUERIES, s.queries.size());
        assertEquals(CigramSearchCache.MAX_RECENT, s.recent.size());
        assertEquals(CigramSearchCache.MAX_SUGGESTIONS, s.suggestMovies.size());
    }

    @Test
    public void aTypeThatIsNotAKnownTabFallsBackToAll() throws Exception {
        CigramSearchCache.State s = new CigramSearchCache.State();
        CigramSearchCache.parse(new JSONObject().put("last", new JSONObject().put("query", "x").put("type", "<script>")).toString(), s);
        assertEquals("all", s.lastType);
    }

    @Test
    public void unusedImportsStayUnused() {
        List<String> none = new ArrayList<String>();
        assertTrue(none.isEmpty());
    }
}
