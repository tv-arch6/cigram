package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The «المزيد» page opens from memory: whatever Home published is there, in Home's order, under the section's id AND its title,
 * and a top-up only ever appends.
 */
public class CigramSectionStoreTest {

    @Before public void clean() { CigramSectionStore.clear(); }

    private static JSONObject film(int n) throws Exception {
        return new JSONObject().put("id", "m" + n).put("type", "movie").put("title", "Film " + n);
    }

    private static JSONObject series(int n) throws Exception {
        return new JSONObject().put("id", "s" + n).put("series_id", "s" + n).put("type", "series").put("title", "Series " + n);
    }

    @Test
    public void aPublishedSectionIsFoundByIdAndByTitleInTheOrderHomeGaveIt() throws Exception {
        LinkedHashMap<String, JSONObject> items = new LinkedHashMap<String, JSONObject>();
        for (int i = 1; i <= 5; i++) items.put("k" + i, film(i));
        CigramSectionStore.publish("latest_movies", "أحدث الأفلام", "movie", items.values(), true);

        CigramSectionStore.Page byId = CigramSectionStore.get("latest_movies", "");
        CigramSectionStore.Page byTitle = CigramSectionStore.get("", "أحدث الأفلام");
        assertNotNull(byId);
        assertNotNull(byTitle);
        assertEquals(5, byId.items.size());
        for (int i = 0; i < 5; i++) assertEquals("Film " + (i + 1), byId.items.get(i).optString("title"));
        assertEquals("movie", byId.type);
    }

    @Test
    public void unknownSectionsAreNullNotAnException() {
        assertNull(CigramSectionStore.get("nothing", "هذا القسم غير موجود"));
        assertNull(CigramSectionStore.getByKey("related:x"));
        assertNull(CigramSectionStore.getByKey(null));
    }

    @Test
    public void aPageIsASnapshot_laterChangesOfHomesMapDoNotShiftTheRanks() throws Exception {
        LinkedHashMap<String, JSONObject> items = new LinkedHashMap<String, JSONObject>();
        items.put("a", film(1));
        items.put("b", film(2));
        CigramSectionStore.publish("x", "قسم", "movie", items.values(), true);
        CigramSectionStore.Page first = CigramSectionStore.get("x", "");
        items.put("c", film(3));
        assertEquals(2, first.items.size());
        assertSame("the same page is handed out again", first, CigramSectionStore.get("x", ""));
    }

    @Test
    public void emptySectionsAreNotPublished() {
        CigramSectionStore.publish("e", "فارغ", "movie", new ArrayList<JSONObject>(), true);
        assertNull(CigramSectionStore.get("e", ""));
    }

    @Test
    public void relatedPagesLiveUnderTheirOwnKey() throws Exception {
        List<JSONObject> list = Arrays.asList(film(1), film(2));
        CigramSectionStore.put("related:movie|m9", new CigramSectionStore.Page("__related__", "ذات صلة", "movie", list, true));
        CigramSectionStore.Page page = CigramSectionStore.getByKey("related:movie|m9");
        assertNotNull(page);
        assertTrue(page.complete);
        assertEquals(2, page.items.size());
    }

    @Test
    public void aTopUpAppendsOnlyWhatIsNewAndNeverReorders() throws Exception {
        List<JSONObject> shown = new ArrayList<JSONObject>(Arrays.asList(film(1), film(2), series(1)));
        CigramSectionStore.Page page = new CigramSectionStore.Page("s", "قسم", "", shown, false);
        List<JSONObject> full = Arrays.asList(film(2), film(1), series(1), film(3), series(2));   // same titles, another order, two new
        List<JSONObject> added = CigramSectionStore.topUp(page, full);
        assertEquals(2, added.size());
        assertEquals("Film 3", added.get(0).optString("title"));
        assertEquals("Series 2", added.get(1).optString("title"));
        assertEquals("the visible list itself is untouched", 3, shown.size());
    }

    @Test
    public void aTopUpWithNothingNewIsEmpty() throws Exception {
        List<JSONObject> shown = new ArrayList<JSONObject>(Arrays.asList(film(1)));
        CigramSectionStore.Page page = new CigramSectionStore.Page("s", "قسم", "", shown, false);
        assertTrue(CigramSectionStore.topUp(page, Arrays.asList(film(1))).isEmpty());
        assertTrue(CigramSectionStore.topUp(page, null).isEmpty());
        assertTrue(CigramSectionStore.topUp(null, Arrays.asList(film(1))).isEmpty());
    }

    @Test
    public void aSeriesAndAFilmWithTheSameIdAreDifferentTitles() throws Exception {
        JSONObject f = new JSONObject().put("id", "7").put("type", "movie").put("title", "A");
        JSONObject s = new JSONObject().put("id", "7").put("series_id", "7").put("type", "series").put("title", "A");
        assertFalse(CigramSectionStore.identity(f).equals(CigramSectionStore.identity(s)));
    }

    @Test
    public void rankNumbersAreOneBasedAndFollowTheAppsDigitConvention() {
        assertEquals("1", CigramRankView.format(1));
        assertEquals("12", CigramRankView.format(12));
        assertEquals("a rank is never zero or negative", "1", CigramRankView.format(0));
        assertEquals("1", CigramRankView.format(-4));
    }
}
