package com.Cigram.vid;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The one header (and the one back button) of every sub-page.
 *
 * The app does not declare android:supportsRtl, so Android ignores layout / text direction attributes; rows are therefore built in
 * PHYSICAL order. Arabic reads from the right, so the back button sits at the RIGHT edge and its arrow points RIGHT ("back" in an
 * Arabic page; "forward" points left). The title fills the rest and is right-aligned, so it starts next to the back button.
 *
 *   physical, left → right:   [ title ................................ ][ → ]
 *
 * The button is a 48 dp circle (touch target), has a ripple, a content description ("رجوع") and goes through {@link CigramNav#back}
 * so it behaves exactly like the system back button / gesture (one level up, no double pop).
 */
final class CigramTopBar {

    static final String TAG_BAR = "cigram_top_bar";
    static final String TAG_BACK = "cigram_back_button";
    static final int HEIGHT_DP = 56;

    private CigramTopBar() { }

    /** A full header row: [title][→]. {@code activity} is the screen the back button leaves. */
    static LinearLayout create(Activity activity, CharSequence title) {
        boolean light = CigramTheme.isLight(activity);
        int ink = light ? 0xFF111418 : 0xFFFFFFFF;
        LinearLayout bar = new LinearLayout(activity);
        bar.setTag(TAG_BAR);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);      // physical order, see the class comment
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(light ? 0xFFFFFFFF : 0xFF1D1D21);
        int pad = CigramUI.dp(activity, 6);
        bar.setPadding(pad, 0, pad, 0);

        TextView text = new TextView(activity);
        text.setTag("cigram_top_bar_title");
        text.setText(CigramPageKit.rtl(title == null ? "" : title.toString()));
        text.setTextColor(ink);
        text.setTextSize(19f);
        text.setTypeface(CigramUI.font(activity), Typeface.BOLD);
        text.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        text.setTextDirection(View.TEXT_DIRECTION_RTL);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setPadding(CigramUI.dp(activity, 8), 0, CigramUI.dp(activity, 6), 0);
        bar.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bar.addView(backButton(activity, ink),
                new LinearLayout.LayoutParams(CigramUI.dp(activity, 48), CigramUI.dp(activity, 48)));
        bar.setMinimumHeight(CigramUI.dp(activity, HEIGHT_DP));
        return bar;
    }

    /** The back button alone, for pages that keep their own header layout. Arrow points right; tap = one level up. */
    static View backButton(final Activity activity, int tint) {
        FrameLayout box = new FrameLayout(activity);
        box.setTag(TAG_BACK);
        box.setContentDescription("رجوع");
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        int ripple = (tint & 0x00FFFFFF) | 0x33000000;
        box.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), null, mask));
        AdsIcons.Icon arrow = new AdsIcons.Icon(activity, AdsIcons.BACK, tint);
        arrow.setScaleX(-1f);   // AdsIcons.BACK points left; mirrored it points right = «رجوع» in an Arabic page
        box.addView(arrow, new FrameLayout.LayoutParams(CigramUI.dp(activity, 22), CigramUI.dp(activity, 22), Gravity.CENTER));
        CigramNav.bind(activity, box);
        return box;
    }

    /**
     * The shared back button for a screen that has no header of its own to put it in (the player, a hub, a form): a translucent round
     * button floating at the top-right, below the status bar, above everything else. Idempotent.
     */
    static void floatingBack(final Activity activity, ViewGroup content) {
        if (content.findViewWithTag(TAG_BACK) != null) return;
        // ONE view: the shared button itself (tag, description, click, ripple), with a translucent disc behind its ripple
        View button = backButton(activity, 0xFFFFFFFF);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(0x66000000);
        button.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{disc, button.getBackground()}));
        button.setElevation(CigramUI.dp(activity, 6));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(CigramUI.dp(activity, 44), CigramUI.dp(activity, 44),
                Gravity.TOP | Gravity.RIGHT);
        final int margin = CigramUI.dp(activity, 8);
        lp.setMargins(margin, margin, margin, 0);
        content.addView(button, lp);
        button.bringToFront();
        // a screen that draws edge to edge (the player) lets the status bar overlap the top of its content: sit below it. A screen whose
        // window already starts under the status bar gets a zero inset and keeps the plain margin.
        button.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                int wanted = margin + Math.max(0, insets.getSystemWindowInsetTop());
                ViewGroup.LayoutParams current = v.getLayoutParams();
                if (current instanceof FrameLayout.LayoutParams && ((FrameLayout.LayoutParams) current).topMargin != wanted) {
                    ((FrameLayout.LayoutParams) current).topMargin = wanted;
                    v.setLayoutParams(current);
                }
                return insets;
            }
        });
        button.requestApplyInsets();
    }

    /** Finds the back button of a bar built here (or any view tagged as one). */
    static View findBack(View root) {
        return root == null ? null : root.findViewWithTag(TAG_BACK);
    }
}
