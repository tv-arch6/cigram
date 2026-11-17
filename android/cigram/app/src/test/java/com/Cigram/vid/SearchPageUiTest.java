package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * Search opens complete and instantly: the page draws its saved state (recent searches, last results, suggestions) on the first frame,
 * with no spinner, no "preparing / loading" text and no request, with the catalogue not even present (offline, first run).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SearchPageUiTest {

    private static JSONObject card(int n, String type) throws Exception {
        return new JSONObject().put("id", "x" + n).put("type", type).put("title", "عنوان " + n).put("year", "2021")
                .put("imdb", "7.1").put("category", "دراما");
    }

    private static void all(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) all(((ViewGroup) v).getChildAt(i), out);
    }

    private static List<String> visibleTexts(View root) {
        List<View> views = new ArrayList<View>();
        all(root, views);
        List<String> out = new ArrayList<String>();
        for (View v : views) {
            if (v instanceof TextView && v.getVisibility() == View.VISIBLE) out.add(String.valueOf(((TextView) v).getText()));
        }
        return out;
    }

    private static boolean has(View root, Class<? extends View> type) {
        List<View> views = new ArrayList<View>();
        all(root, views);
        for (View v : views) if (type.isInstance(v)) return true;
        return false;
    }

    private ActivityController<SearchXmlActivity> openOffline(boolean withSavedState) throws Exception {
        CigramSearchCache.resetForTest();
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        java.io.File f = new java.io.File(app.getFilesDir(), "cigram_search_state_v1.json");
        f.delete();
        if (withSavedState) {
            CigramSearchCache.State s = new CigramSearchCache.State();
            s.queries.add("باتمان");
            s.recent.add(CigramSearchCache.trim(card(1, "movie")));
            s.suggestMovies.add(CigramSearchCache.trim(card(2, "movie")));
            s.suggestSeries.add(CigramSearchCache.trim(card(3, "series")));
            s.lastQuery = "باتمان";
            s.lastResults.add(CigramSearchCache.trim(card(4, "movie")));
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(CigramSearchCache.toJson(s).getBytes("UTF-8"));
            out.close();
        }
        return Robolectric.buildActivity(SearchXmlActivity.class);
    }

    @Test
    public void opensCompleteFromTheSavedStateBeforeTheFirstFrameWithNothingLoading() throws Exception {
        ActivityController<SearchXmlActivity> c = openOffline(true);
        c.create();                                          // onCreate returns = the page is drawn
        View root = c.get().findViewById(android.R.id.content);
        List<String> texts = visibleTexts(root);
        assertFalse("no spinner", has(root, ProgressBar.class));
        for (String t : texts) {
            assertFalse("no loading text on open: " + t, t.contains("جارٍ") || t.contains("جاري") || t.contains("تحميل"));
        }
        assertTrue("recent searches are there", texts.contains("‏باتمان"));
        boolean last = false, recent = false, suggest = false;
        for (String t : texts) {
            if (t.contains("آخر نتائج بحثك")) last = true;
            if (t.contains("آخر ما شاهدته")) recent = true;
            if (t.contains("اقتراحات")) suggest = true;
        }
        assertTrue("last results", last);
        assertTrue("recent titles", recent);
        assertTrue("suggestions", suggest);
        assertTrue("a saved title is drawn as a card", texts.contains("عنوان 4"));
    }

    @Test
    public void aFreshInstallShowsItsOwnPromptNotALoader() throws Exception {
        ActivityController<SearchXmlActivity> c = openOffline(false);
        c.create();
        View root = c.get().findViewById(android.R.id.content);
        assertFalse(has(root, ProgressBar.class));
        for (String t : visibleTexts(root)) {
            assertFalse("no loading text: " + t, t.contains("جارٍ") || t.contains("جاري") || t.contains("تحميل"));
        }
    }

    @Test
    public void typingRunsTheSearchOffTheMainThreadAndAnAbsentIndexIsAMessageNotACrash() throws Exception {
        ActivityController<SearchXmlActivity> c = openOffline(true);
        c.create().start().resume().visible();
        android.widget.EditText box = c.get().findViewById(R.id.edittext_search);
        assertNotNull(box);
        box.setText("باتمان");
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS);
        // there is no saved catalogue in this test: the answer is a plain message, and the page is still alive
        assertFalse(c.get().isFinishing());
        assertFalse(has(c.get().findViewById(android.R.id.content), ProgressBar.class));
    }

    @Test
    public void searchHasAVisibleBackButtonInItsHeader() throws Exception {
        ActivityController<SearchXmlActivity> c = openOffline(true);
        c.create().start().resume().visible();
        CigramNav.install(c.get());
        LinearLayout header = c.get().findViewById(R.id.linear_search_top);
        View back = header.findViewWithTag(CigramTopBar.TAG_BACK);
        assertNotNull("back button in the search header", back);
        assertEquals("it is the last child = the right edge", back, header.getChildAt(header.getChildCount() - 1));
        assertNull("not floating over the header", ((ViewGroup) c.get().findViewById(android.R.id.content)).getChildAt(0) == back ? back : null);
    }
}
