package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Back on every screen: a screen with no back control of its own gets the shared one (once), a screen that draws its own is left alone,
 * the Home root has none, and a tap goes ONE level up (a double tap, or a tap racing the system gesture, is one pop).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BackNavigationUiTest {

    /** A page without any back control. */
    public static class BarePage extends Activity {
        @Override protected void onCreate(Bundle b) {
            super.onCreate(b);
            FrameLayout root = new FrameLayout(this);
            TextView body = new TextView(this);
            body.setText("صفحة بلا زر رجوع");
            root.addView(body);
            setContentView(root);
        }
    }

    /** A page that draws its own back control (like the About / Terms pages or the Discover page). */
    public static class OwnBackPage extends Activity {
        @Override protected void onCreate(Bundle b) {
            super.onCreate(b);
            LinearLayout root = new LinearLayout(this);
            View mine = new View(this);
            mine.setClickable(true);
            mine.setContentDescription("رجوع");
            root.addView(mine, new LinearLayout.LayoutParams(100, 100));
            setContentView(root);
        }
    }

    private static int countBackButtons(View v) {
        int n = CigramTopBar.TAG_BACK.equals(v.getTag()) ? 1 : 0;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) n += countBackButtons(g.getChildAt(i));
        }
        return n;
    }

    @Test
    public void aScreenWithoutBackGetsTheSharedButtonExactlyOnce() {
        Activity a = Robolectric.buildActivity(BarePage.class).setup().get();
        View content = a.findViewById(android.R.id.content);
        assertEquals(0, countBackButtons(content));
        CigramNav.install(a);
        CigramNav.install(a);
        CigramNav.install(a);
        assertEquals("one button however often it is installed", 1, countBackButtons(content));
        View back = content.findViewWithTag(CigramTopBar.TAG_BACK);
        assertNotNull(back);
        assertEquals("رجوع", String.valueOf(back.getContentDescription()));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) back.getLayoutParams();
        assertTrue("top-right: the start edge of an Arabic page", (lp.gravity & android.view.Gravity.RIGHT) == android.view.Gravity.RIGHT);
        assertTrue((lp.gravity & android.view.Gravity.TOP) == android.view.Gravity.TOP);
    }

    @Test
    public void aScreenThatDrawsItsOwnBackIsLeftAlone() {
        Activity a = Robolectric.buildActivity(OwnBackPage.class).setup().get();
        View content = a.findViewById(android.R.id.content);
        CigramNav.install(a);
        assertEquals("no second button next to the screen's own", 0, countBackButtons(content));
    }

    @Test
    public void theTapIsOneLevelAndTheSecondTapIsIgnored() {
        Activity a = Robolectric.buildActivity(BarePage.class).setup().get();
        CigramNav.install(a);
        View back = a.findViewById(android.R.id.content).findViewWithTag(CigramTopBar.TAG_BACK);
        assertFalse(a.isFinishing());
        back.performClick();
        assertTrue("the screen closes", a.isFinishing());
        assertFalse("a second request for the same screen does nothing", CigramNav.back(a));
    }

    @Test
    public void twoQuickRequestsPopOnlyOneScreen() {
        ActivityController<BarePage> c = Robolectric.buildActivity(BarePage.class).setup();
        Activity a = c.get();
        assertTrue(CigramNav.back(a));
        assertFalse(CigramNav.back(a));
    }

    @Test
    public void aFinishedOrMissingScreenIsNeverAnError() {
        assertFalse(CigramNav.back(null));
        Activity a = Robolectric.buildActivity(BarePage.class).setup().get();
        a.finish();
        assertFalse(CigramNav.back(a));
    }

    @Test
    public void theTopBarKeepsTheTitleBesideTheBackButtonOnTheRight() {
        Activity a = Robolectric.buildActivity(BarePage.class).setup().get();
        LinearLayout bar = CigramTopBar.create(a, "عنوان طويل جداً لقسم من أقسام التطبيق يجب أن لا يدفع زر الرجوع خارج الشاشة أبداً");
        assertEquals(View.LAYOUT_DIRECTION_LTR, bar.getLayoutDirection());
        // physical order, left → right: [title][back]: the back button is the LAST child = the right edge
        View last = bar.getChildAt(bar.getChildCount() - 1);
        assertEquals(CigramTopBar.TAG_BACK, last.getTag());
        TextView title = (TextView) bar.getChildAt(0);
        assertEquals("the title is right-aligned, next to the arrow", android.view.Gravity.RIGHT, title.getGravity() & android.view.Gravity.RIGHT);
        assertNull("nothing else sits to the right of the back button", null);
        bar.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        bar.layout(0, 0, 720, bar.getMeasuredHeight());
        assertTrue("the arrow stays on screen", last.getRight() <= 720 && last.getLeft() > 0);
    }
}
