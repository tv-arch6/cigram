package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;

import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;
import org.junit.Before;
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
 * «المزيد» on the real Android view system: the ranked list is there on the FIRST frame (no spinner, no skeleton, no request), ranks run
 * 1, 2, 3 ..., every card keeps the same column geometry, the back button is the shared RTL one. Pictures: build/ui-shots.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SectionPageUiTest {

    private static final int N = 30;

    @Before public void clean() { CigramSectionStore.clear(); }

    private static List<JSONObject> items() throws Exception {
        List<JSONObject> list = new ArrayList<JSONObject>();
        String[] genres = {"أكشن، دراما", "كوميدي", "رعب، إثارة", "خيال علمي، مغامرة"};
        for (int i = 1; i <= N; i++) {
            boolean series = i % 4 == 0;
            JSONObject o = new JSONObject().put("id", (series ? "s" : "m") + i).put("type", series ? "series" : "movie")
                    .put("title", (series ? "مسلسل رقم " : "فيلم رقم ") + i).put("year", String.valueOf(2000 + i % 25))
                    .put("imdb", String.valueOf(5 + (i % 5) + 0.3)).put("category", genres[i % genres.length])
                    .put("description", "قصة طويلة جداً عن بطل يواجه العالم وحيداً في رحلة مليئة بالمفاجآت والتحولات التي لا تنتهي، "
                            + "وتتشابك فيها مصائر الشخصيات حتى النهاية المؤثرة التي ينتظرها الجميع. رقم " + i)
                    .put("quality", i % 3 == 0 ? "4K" : "HD");
            if (series) o.put("series_id", "s" + i);
            else o.put("duration", String.valueOf(90 + i));
            list.add(o);
        }
        return list;
    }

    private static <T extends View> T first(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                T hit = first(g.getChildAt(i), type);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static void collect(View root, Class<? extends View> type, List<View> out) {
        if (type.isInstance(root)) out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), type, out);
        }
    }

    private ActivityController<SectionActivity> open() throws Exception {
        CigramSectionStore.publish("latest_movies", "أحدث الأفلام", "movie", items(), true);
        Intent intent = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), SectionActivity.class)
                .putExtra("section_id", "latest_movies").putExtra("section_title", "أحدث الأفلام").putExtra("section_type", "movie");
        return Robolectric.buildActivity(SectionActivity.class, intent);
    }

    @Test
    public void theWholeListIsThereBeforeTheFirstFrameAndNothingIsLoading() throws Exception {
        ActivityController<SectionActivity> c = open();
        c.create();                                        // onCreate only: no start, no resume, no layout pass yet
        View root = c.get().findViewById(android.R.id.content);
        RecyclerView list = first(root, RecyclerView.class);
        assertNotNull(list);
        assertEquals("every title is in the adapter when onCreate returns", N, list.getAdapter().getItemCount());
        assertNull("no spinner", first(root, ProgressBar.class));
        for (View v : all(root)) {
            if (v instanceof android.widget.TextView) {
                String t = String.valueOf(((android.widget.TextView) v).getText());
                assertTrue("no loading text: " + t, !t.contains("جاري") && !t.contains("جارٍ") && !t.contains("تحميل"));
            }
        }
    }

    private static List<View> all(View root) {
        List<View> out = new ArrayList<View>();
        collect(root, View.class, out);
        return out;
    }

    @Test
    public void ranksRunOneTwoThreeAndEveryCardKeepsTheSameColumns() throws Exception {
        ActivityController<SectionActivity> c = open();
        c.create().start().resume().visible();
        ShadowLooper.idleMainLooper();
        View root = c.get().findViewById(android.R.id.content);
        RecyclerView list = first(root, RecyclerView.class);
        list.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1300, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 720, 1300);
        assertTrue("the visible cards are laid out", list.getChildCount() >= 3);

        int posterLeft = -1;
        for (int i = 0; i < list.getChildCount(); i++) {
            View card = list.getChildAt(i);
            CigramRankView rank = first(card, CigramRankView.class);
            assertNotNull(rank);
            assertEquals("rank follows position", i + 1, rank.rank());
            ImageView poster = first(card, ImageView.class);
            int left = ((View) poster).getLeft() + ((View) poster.getParent()).getLeft();
            if (posterLeft < 0) posterLeft = left;
            assertEquals("the poster column is at the same x on every card", posterLeft, left);
        }
        // pictures for a person: posters get a colour so the overlap of the #1 numeral is visible
        for (int i = 0; i < list.getChildCount(); i++) {
            ImageView poster = first(list.getChildAt(i), ImageView.class);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF000000 | (0x203060 + i * 0x101810), 0xFFC0A060});
            g.setCornerRadius(24f);
            poster.setImageDrawable(g);
        }
        Bitmap shot = ChatKitUiTest.shoot(root, 720, 1520, "more_page_ranked");
        assertTrue("the page drew something", ChatKitUiTest.inked(shot, 0xFF000000) > 0.3);
    }

    @Test
    @Config(sdk = 34, qualifiers = "w360dp-h760dp-night-xhdpi")
    public void theDarkPageDrawsTheWatermarkOverThePosterEdge() throws Exception {
        ActivityController<SectionActivity> c = open();
        c.create().start().resume().visible();
        ShadowLooper.idleMainLooper();
        View root = c.get().findViewById(android.R.id.content);
        RecyclerView list = first(root, RecyclerView.class);
        list.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1300, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 720, 1300);
        for (int i = 0; i < list.getChildCount(); i++) {
            ImageView poster = first(list.getChildAt(i), ImageView.class);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFF000000 | (0x703020 + i * 0x101810), 0xFF30A0C0});
            g.setCornerRadius(24f);
            poster.setImageDrawable(g);
        }
        // the #1 numeral reaches over the poster: its box starts left of the poster's right edge
        View first = list.getChildAt(0);
        CigramRankView rank = first(first, CigramRankView.class);
        ImageView poster = first(first, ImageView.class);
        int posterRight = ((View) poster.getParent()).getLeft() + poster.getRight();
        assertTrue("the rank box reaches over the poster", rank.getLeft() < posterRight);
        Bitmap shot = ChatKitUiTest.shoot(root, 720, 1520, "more_page_ranked_dark");
        assertTrue(ChatKitUiTest.inked(shot, 0xFF000000) > 0.2);
    }

    @Test
    public void theBackButtonIsTheSharedRtlOneAndPopsExactlyOnce() throws Exception {
        ActivityController<SectionActivity> c = open();
        c.create().start().resume().visible();
        ShadowLooper.idleMainLooper();
        SectionActivity a = c.get();
        View root = a.findViewById(android.R.id.content);
        View back = CigramTopBar.findBack(root);
        assertNotNull("a visible back button", back);
        assertEquals("رجوع", String.valueOf(back.getContentDescription()));
        CigramAnimatedArrow.assertPointsRight(back);
        assertTrue("48 dp touch target", back.getLayoutParams().width >= 96 - 2);
        back.performClick();
        assertTrue(a.isFinishing());
        back.performClick();                               // a second tap in the same moment must not pop a second level
        assertTrue(!CigramNav.back(a));
    }

    @Test
    public void aSectionNobodyDrewIsAnEmptyMessageNotASpinner() throws Exception {
        Intent intent = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), SectionActivity.class)
                .putExtra("section_id", "never_published").putExtra("section_title", "قسم غير موجود");
        ActivityController<SectionActivity> c = Robolectric.buildActivity(SectionActivity.class, intent);
        c.create();
        View root = c.get().findViewById(android.R.id.content);
        assertNull(first(root, ProgressBar.class));
        RecyclerView list = first(root, RecyclerView.class);
        assertEquals(0, list.getAdapter().getItemCount());
        boolean message = false;
        for (View v : all(root)) {
            if (v instanceof android.widget.TextView && String.valueOf(((android.widget.TextView) v).getText()).contains("لا توجد عناصر")
                    && v.getVisibility() == View.VISIBLE) message = true;
        }
        assertTrue(message);
    }

    /** The arrow of the shared back button is mirrored: AdsIcons.BACK points left, an Arabic «back» points right. */
    static final class CigramAnimatedArrow {
        static void assertPointsRight(View back) {
            AdsIcons.Icon arrow = first(back, AdsIcons.Icon.class);
            assertNotNull(arrow);
            assertEquals(-1f, arrow.getScaleX(), 0.0001f);
        }
    }
}
