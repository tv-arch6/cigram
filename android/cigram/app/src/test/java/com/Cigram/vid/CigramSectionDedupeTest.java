package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 18.3 — every section and every carousel slide once, with a deterministic precedence rule and unchanged content ids. */
public class CigramSectionDedupeTest {

    private static final CigramSectionDedupe.Normalizer NORM = new CigramSectionDedupe.Normalizer() {
        @Override public String normalize(String s) { return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " "); }
    };

    private static List<CigramSectionDedupe.Sec> secs(String[]... rows) {
        List<CigramSectionDedupe.Sec> l = new ArrayList<CigramSectionDedupe.Sec>();
        for (String[] r : rows) l.add(new CigramSectionDedupe.Sec(r[0], r[1], r[2]));
        return l;
    }

    // ---------------------------------------------------------------- sections: id wins over a name
    @Test public void aNameOnlyGroupIsMergedIntoTheIdGroupOfTheSameName() {
        Map<String, String> alias = CigramSectionDedupe.aliasMap(secs(
                new String[]{"name:رمضان", "", "رمضان"},
                new String[]{"id:s_ramadan", "s_ramadan", "رمضان"},
                new String[]{"id:s_anime", "s_anime", "أنمي"}), NORM);
        assertEquals(1, alias.size());
        assertEquals("id:s_ramadan", alias.get("name:رمضان"));
    }

    @Test public void twoDifferentIdsWithTheSameTitleStayTwoSections() {
        Map<String, String> alias = CigramSectionDedupe.aliasMap(secs(
                new String[]{"id:a", "a", "عروض"}, new String[]{"id:b", "b", "عروض"}), NORM);
        assertTrue("different ids are different sections", alias.isEmpty());
    }

    @Test public void nameOnlySectionsOfDifferentNamesAreLeftAlone() {
        assertTrue(CigramSectionDedupe.aliasMap(secs(new String[]{"name:x", "", "x"}, new String[]{"id:y", "y", "y"}), NORM).isEmpty());
    }

    @Test public void whenTwoIdsShareATitleTheFirstOneAbsorbsTheNameOnlyGroup() {
        Map<String, String> alias = CigramSectionDedupe.aliasMap(secs(
                new String[]{"id:a", "a", "T"}, new String[]{"id:b", "b", "T"}, new String[]{"name:t", "", "t"}), NORM);
        assertEquals("deterministic: the first id", "id:a", alias.get("name:t"));
    }

    // ---------------------------------------------------------------- the admin registry
    @Test public void theFirstRegistryEntryKeepsItsPositionAndLaterDuplicatesAreIgnored() throws Exception {
        JSONArray list = new JSONArray("[{\"id\":\"s1\",\"name\":\"Series\",\"order\":1},{\"id\":\"s2\",\"name\":\"Movies\",\"order\":2},"
                + "{\"id\":\"s1\",\"name\":\"Series again\",\"order\":9,\"show_home\":false},"
                + "{\"id\":\"s3\",\"name\":\"Movies\",\"order\":3}]");
        Map<String, Integer> order = new HashMap<String, Integer>();
        Map<String, Boolean> hidden = new HashMap<String, Boolean>();
        CigramSectionDedupe.buildOrder(list, order, hidden);
        assertEquals(1, order.get("s1").intValue());
        assertFalse("the duplicate s1 entry (hidden) never overrides the first", hidden.get("s1").booleanValue());
        assertFalse(order.containsKey("series again"));
        assertEquals("the name 'Movies' belongs to the first section that claimed it", 2, order.get("movies").intValue());
        assertEquals(3, order.get("s3").intValue());
    }

    // ---------------------------------------------------------------- carousel
    @Test public void everyCarouselSlideAppearsOnceInTheAdminsOrder() throws Exception {
        JSONArray in = new JSONArray("[{\"id\":\"c1\",\"title\":\"A\"},{\"id\":\"c2\"},{\"id\":\"C1\",\"title\":\"A copy\"},"
                + "{\"link\":\"https://x/y\",\"image\":\"i.jpg\"},{\"link\":\"https://x/y\",\"image\":\"i.jpg\"},null,\"junk\",{}]");
        JSONArray out = CigramSectionDedupe.dedupeCarouselItems(in);
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < out.length(); i++) ids.add(out.getJSONObject(i).optString("id", "(" + out.getJSONObject(i).optString("link", "no-identity") + ")"));
        assertEquals(Arrays.asList("c1", "c2", "(https://x/y)", "(no-identity)"), ids);
        assertEquals("the first copy keeps its content", "A", out.getJSONObject(0).getString("title"));
    }

    @Test public void contentIdsAreNeverChangedOrInvented() {
        List<String> ids = CigramSectionDedupe.dedupeIds(Arrays.asList("m_1", "s_2_e1", "M_1", "s_2_e1", "x"));
        assertEquals(Arrays.asList("m_1", "s_2_e1", "x"), ids);
    }

    // ---------------------------------------------------------------- the REAL catalogue (skipped when no file is given)
    /**
     * Runs the app's own section grouping over a real movies.json (path in -Dcigram.catalogue or CIGRAM_CATALOGUE) and checks
     * that every section id is one section and every item appears once in it.
     */
    @Test public void realCatalogueGroupsEverySectionOnce() throws Exception {
        String path = System.getProperty("cigram.catalogue", System.getenv("CIGRAM_CATALOGUE"));
        Assume.assumeTrue("no real catalogue file given", path != null && path.length() > 0 && Files.exists(Paths.get(path)));
        String json = new String(Files.readAllBytes(Paths.get(path)), Charset.forName("UTF-8"));
        Class<?> c = Class.forName("com.Cigram.vid.CigramStage1InitProvider");
        Class<?> sectionClass = Class.forName("com.Cigram.vid.CigramStage1InitProvider$DynamicHomeSection");
        Method collect = c.getDeclaredMethod("collectDynamicSectionContent", Object.class, String.class, JSONObject.class, LinkedHashMap.class, int.class);
        collect.setAccessible(true);
        LinkedHashMap<String, Object> grouped = new LinkedHashMap<String, Object>();
        long t0 = System.nanoTime();
        collect.invoke(null, new org.json.JSONTokener(json).nextValue(), "", null, grouped, 0);
        long ms = (System.nanoTime() - t0) / 1000000L;
        java.lang.reflect.Field idF = sectionClass.getDeclaredField("id"), nameF = sectionClass.getDeclaredField("name"), itemsF = sectionClass.getDeclaredField("items");
        idF.setAccessible(true); nameF.setAccessible(true); itemsF.setAccessible(true);
        HashSet<String> ids = new HashSet<String>(), names = new HashSet<String>();
        int total = 0;
        for (Object sec : grouped.values()) {
            String id = String.valueOf(idF.get(sec)), name = String.valueOf(nameF.get(sec));
            assertTrue("section id used once: " + id, ids.add(id));
            assertTrue("section title shown once: " + name, names.add(name));
            Map<?, ?> items = (Map<?, ?>) itemsF.get(sec);
            HashSet<String> contentIds = new HashSet<String>();
            for (Object it : items.values()) {
                JSONObject o = (JSONObject) it;
                String key = o.optString("series_id", "").length() > 0 && "series".equals(o.optString("type", "")) ? o.optString("series_id") : o.optString("id");
                assertTrue("item once in " + name + ": " + key, contentIds.add(key));
            }
            total += items.size();
        }
        System.out.println("REAL CATALOGUE: " + grouped.size() + " dynamic sections, " + total + " cards, grouping took " + ms + " ms (JVM)");
        assertTrue(grouped.size() >= 1);
    }

    /** Baseline numbers for the report: what one catalogue read costs (JVM, desktop CPU: a phone is several times slower). */
    @Test public void realCatalogueCostsForTheBaseline() throws Exception {
        String path = System.getProperty("cigram.catalogue", System.getenv("CIGRAM_CATALOGUE"));
        Assume.assumeTrue("no real catalogue file given", path != null && path.length() > 0 && Files.exists(Paths.get(path)));
        byte[] bytes = Files.readAllBytes(Paths.get(path));
        String json = new String(bytes, Charset.forName("UTF-8"));
        long t0 = System.nanoTime();
        Object root = new org.json.JSONTokener(json).nextValue();
        long parseMs = (System.nanoTime() - t0) / 1000000L;
        t0 = System.nanoTime();
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(json.getBytes("UTF-8"));
        long hashMs = (System.nanoTime() - t0) / 1000000L;
        int records = ((JSONObject) root).getJSONArray("movies").length();
        System.out.println("BASELINE: body=" + bytes.length + " bytes, records=" + records + ", org.json parse=" + parseMs + " ms, sha256=" + hashMs + " ms (digest " + digest.length + " bytes)");
        assertTrue(records > 0);
    }
}
