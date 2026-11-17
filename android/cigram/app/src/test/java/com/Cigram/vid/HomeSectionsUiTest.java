package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Home on the real Android view system: «أحدث الأفلام» is drawn by the same section builder as every other row (a header with «المزيد» and a
 * horizontal row of posters, inside the one section block), the old XML rows are gone, and a section that cannot be built does not
 * take Home down.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeSectionsUiTest {

    private static final long T0 = 1_790_000_000_000L;

    private static String catalogue() throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 1; i <= 10; i++) {
            a.put(new JSONObject().put("id", "m" + i).put("type", "movie").put("title", "فيلم " + i).put("year", "2020")
                    .put("poster", "").put("video", "https://x/v" + i + ".mp4").put("created_at_ms", T0 + i * 1000L).put("imdb", "7.0"));
        }
        for (int i = 1; i <= 8; i++) {
            a.put(new JSONObject().put("id", "c" + i).put("type", "movie").put("title", "قسم مخصص " + i).put("poster", "")
                    .put("video", "https://x/c" + i + ".mp4").put("section_id", "anime").put("section_name", "أنمي")
                    .put("created_at_ms", T0 + i * 1000L));
        }
        for (int i = 1; i <= 3; i++) {
            a.put(new JSONObject().put("id", "s" + i).put("series_id", "s" + i).put("type", "series").put("title", "مسلسل " + i).put("poster", ""));
            a.put(new JSONObject().put("id", "s" + i + "e1").put("type", "episode").put("series_id", "s" + i).put("season", "1")
                    .put("episode", "1").put("title", "مسلسل " + i).put("video", "https://x/e" + i + ".mp4").put("created_at_ms", T0 + i));
        }
        return a.toString();
    }

    private static void all(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) all(((ViewGroup) v).getChildAt(i), out);
    }

    private static List<String> headers(View root) {
        List<View> views = new ArrayList<View>();
        all(root, views);
        List<String> out = new ArrayList<String>();
        for (View v : views) if (v instanceof TextView) out.add(String.valueOf(((TextView) v).getText()).trim());
        return out;
    }

    @Test
    public void theTwoClassicRowsAreNoLongerPartOfTheLayout() throws Exception {
        // Home's real layout, inflated in a bare activity: MainActivity itself starts the catalogue refresh (a real download), which can
        // land in a later test and publish the live catalogue's sections into the shared store
        android.app.Activity a = homePage();
        for (String gone : new String[]{"linear_latest_movies_header", "hscroll1", "linear_movies", "linear8", "hscroll_series", "linear_series",
                "textview_more_latest_movies", "textview_more_latest_series"}) {
            assertEquals("main.xml no longer has " + gone, 0, a.getResources().getIdentifier(gone, "id", a.getPackageName()));
        }
        assertNotNull("the page keeps its scroll container and its root", a.findViewById(R.id.vscroll1));
        assertNotNull(a.findViewById(R.id.linear1));
    }

    /** Home's real layout in a bare activity: the section pipeline is what is under test, not the hero carousel or the ads of MainActivity. */
    private static android.app.Activity homePage() {
        android.app.Activity a = Robolectric.buildActivity(android.app.Activity.class).create().get();
        a.setTheme(R.style.CigramDarkTheme);
        a.setContentView(R.layout.main);
        return a;
    }

    private static void drain(int rounds) {
        for (int i = 0; i < rounds; i++) {                   // the background grouping (IO thread), then the staggered section build
            ShadowLooper.idleMainLooper();
            try { Thread.sleep(40); } catch (InterruptedException ignored) { }
        }
    }

    private static void receive(android.app.Activity a, String json) throws Exception {
        Method receive = Class.forName("com.Cigram.vid.CigramStage1InitProvider")
                .getDeclaredMethod("homeCatalogReceived", android.app.Activity.class, String.class);
        receive.setAccessible(true);
        receive.invoke(null, a, json);
    }

    @Test
    public void latestMoviesIsDrawnByTheSameBuilderAsEverySection() throws Exception {
        android.app.Activity a = homePage();
        receive(a, catalogue());
        drain(80);
        View home = a.findViewById(R.id.linear1);
        assertNotNull(home);
        List<String> texts = headers(home);
        assertTrue("أحدث الأفلام is on Home: " + texts, texts.contains("أحدث الأفلام"));
        assertTrue("المسلسلات المميزة is on Home", texts.contains("المسلسلات المميزة"));
        assertTrue("the custom section is on Home", texts.contains("أنمي"));
        int more = 0;
        for (String t : texts) if (t.equals("المزيد")) more++;
        assertTrue("every section, the two classic ones included, has its «المزيد»: " + more, more >= 3);

        // every section is a vertical block [header][horizontal row]; no horizontal row sits inside another horizontal scroller
        List<View> views = new ArrayList<View>();
        all(home, views);
        int rows = 0;
        for (View v : views) {
            if (!(v instanceof HorizontalScrollView)) continue;
            rows++;
            for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) {
                assertFalse("no nested same-direction scrollers", p instanceof HorizontalScrollView);
            }
        }
        assertTrue("the rows were built", rows >= 3);
        assertNotNull("all sections live in the ONE section block", home.findViewWithTag("cigram_stage2_dynamic_sections"));
    }

    @Test
    public void theSectionsArePublishedForTheMorePageAndItOpensWithThem() throws Exception {
        CigramSectionStore.clear();
        android.app.Activity a = homePage();
        receive(a, catalogue());
        drain(80);
        CigramSectionStore.Page latest = CigramSectionStore.get("latest_movies", "");
        assertNotNull("Home published أحدث الأفلام for its «المزيد» page", latest);
        StringBuilder titles = new StringBuilder();
        for (int i = 0; i < Math.min(latest.items.size(), 80); i++) titles.append(latest.items.get(i).optString("title")).append(" | ");
        assertEquals("ten films, the custom-section ones are in their own section: " + titles, 10, latest.items.size());
        assertEquals("newest first, exactly as the row shows them", "فيلم 10", latest.items.get(0).optString("title"));
        assertNotNull(CigramSectionStore.get("", "أنمي"));
        assertNotNull(CigramSectionStore.get("featured_series", ""));
    }

    @Test
    public void aCatalogueThatCannotBeGroupedDoesNotTakeHomeDown() throws Exception {
        android.app.Activity a = homePage();
        receive(a, "[]");
        receive(a, "not a catalogue at all");
        drain(20);
        assertFalse(a.isFinishing());
        assertNotNull(a.findViewById(R.id.linear1));
    }
}
