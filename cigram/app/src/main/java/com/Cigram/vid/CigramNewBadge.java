package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The «جديد» badge.
 *
 * A section or a button that was added or changed in this release carries the badge next to its name ONCE: the first
 * time the account in use sees the row, the badge is drawn and remembered as shown; from the next visit on it is gone
 * for good. The memory is local and per account (a second account on the same phone gets its own first look).
 *
 * Look: heavy black letters with a white glow inside them, on a 50 % black pill that carries the same white glow on
 * its rim. It reads on the dark page and on the light page.
 */
final class CigramNewBadge {

    static final String TAG = "cigram_new_badge";
    private static final String PREFS = "cigram_new_badges_v1";

    private CigramNewBadge() { }

    private static String key(Context c, String feature) {
        String owner = "guest";
        try { owner = CigramUserData.owner(c); } catch (Throwable ignored) { }
        return owner + "|" + feature;
    }

    /** True when this account has not been shown the badge of {@code feature} yet. */
    static boolean pending(Context c, String feature) {
        try {
            return !c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key(c, feature), false);
        } catch (Throwable e) {
            return false;
        }
    }

    private static void remember(Context c, String feature) {
        try {
            SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().putBoolean(key(c, feature), true).apply();
        } catch (Throwable ignored) { }
    }

    static TextView make(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        TextView b = new TextView(c);
        b.setTag(TAG);
        b.setText("جديد");
        b.setTextSize(11.5f);
        b.setTextColor(0xFF000000);
        Typeface bold = null;
        try { bold = CigramTypefaces.bold(); } catch (Throwable ignored) { }
        b.setTypeface(bold != null ? bold : Typeface.DEFAULT_BOLD, Typeface.BOLD);
        b.getPaint().setFakeBoldText(true);
        b.setShadowLayer(5f * d, 0f, 0f, 0xFFFFFFFF);          // the white glow inside the letters
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        b.setSingleLine(true);
        b.setPadding((int) (9 * d), (int) (4 * d), (int) (9 * d), (int) (4 * d));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x80000000, 0x80000000});
        bg.setCornerRadius(11 * d);
        bg.setStroke(Math.max(1, (int) (1.2f * d)), 0x8CFFFFFF);   // the white glow on the pill's rim
        b.setBackground(bg);
        b.setLayerType(View.LAYER_TYPE_SOFTWARE, null);            // shadow glow on text needs it on some GPUs
        b.setContentDescription("جديد");
        b.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return b;
    }

    /**
     * Puts the badge into {@code row} (a horizontal row built left → right: chevron first, name last) when this account
     * has not seen it yet. Safe to call on every refresh of the page: a badge that is already there stays until the
     * page is closed, a seen one is never added again.
     */
    static void attach(View row, String feature) {
        if (!(row instanceof LinearLayout) || feature == null) return;
        LinearLayout r = (LinearLayout) row;
        if (r.findViewWithTag(TAG) != null) return;
        Context c = r.getContext();
        if (!pending(c, feature)) return;
        try {
            TextView b = make(c);
            float d = c.getResources().getDisplayMetrics().density;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = (int) (8 * d);
            lp.gravity = Gravity.CENTER_VERTICAL;
            r.addView(b, Math.min(1, r.getChildCount()), lp);
            b.setAlpha(0f);
            b.setScaleX(0.7f);
            b.setScaleY(0.7f);
            b.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260L).setStartDelay(180L).start();
            remember(c, feature);
        } catch (Throwable ignored) { }
    }

    /** The same badge floating on the top-left corner of any box (a card, a tile, a button). */
    static void attachCorner(ViewGroup box, String feature) {
        if (!(box instanceof FrameLayout) || feature == null || box.findViewWithTag(TAG) != null) return;
        Context c = box.getContext();
        if (!pending(c, feature)) return;
        try {
            float d = c.getResources().getDisplayMetrics().density;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
            lp.setMargins((int) (10 * d), (int) (10 * d), 0, 0);
            box.addView(make(c), lp);
            remember(c, feature);
        } catch (Throwable ignored) { }
    }
}
