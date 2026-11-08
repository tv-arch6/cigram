package com.Cigram.vid;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Shared scaffold of the 2026 text pages (About, Terms): the account page's four-stage gradient, an RTL top bar with
 * the back button on the right (pointing right, i.e. "back" in Arabic), and one readable column (max 720 dp, centred
 * on tablets and TVs). Everything follows {@link CigramPalette}, so Light / Dark are both handled.
 */
final class CigramPageKit {

    final Activity a;
    final CigramPalette pal;
    final FrameLayout root;
    final ScrollView scroll;
    final LinearLayout column;
    final View back;

    private CigramPageKit(Activity a, String title) {
        this.a = a;
        this.pal = CigramPalette.of(a);
        root = new FrameLayout(a);
        root.setBackground(CigramAccountDash.pageGradient(pal));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout frame = new LinearLayout(a);
        frame.setOrientation(LinearLayout.VERTICAL);
        root.addView(frame, new FrameLayout.LayoutParams(-1, -1));

        // ---- top bar: [back →] [title] in RTL (the back button sits on the right edge)
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        FrameLayout backBox = new FrameLayout(a);
        backBox.setClickable(true);
        backBox.setFocusable(true);
        backBox.setContentDescription("رجوع");
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        backBox.setBackground(new RippleDrawable(ColorStateList.valueOf(pal.ripple), null, mask));
        AdsIcons.Icon arrow = new AdsIcons.Icon(a, AdsIcons.BACK, pal.text);
        // AdsIcons.BACK points left; mirrored it points right = "back" in a right-to-left page
        arrow.setScaleX(-1f);
        backBox.addView(arrow, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        backBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { CigramPageKit.this.a.finish(); }
        });
        bar.addView(backBox, new LinearLayout.LayoutParams(dp(48), dp(48)));
        back = backBox;
        TextView t = CigramUI.label(a, title, 18f, pal.text, true);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setPadding(dp(6), 0, dp(6), 0);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        frame.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        FrameLayout center = new FrameLayout(a);
        column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        column.setPadding(dp(20), dp(4), dp(20), dp(40));
        int max = dp(720);
        int width = Math.min(max, a.getResources().getDisplayMetrics().widthPixels);
        center.addView(column, new FrameLayout.LayoutParams(width, -2, Gravity.CENTER_HORIZONTAL));
        scroll.addView(center, new FrameLayout.LayoutParams(-1, -2));
        frame.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    static CigramPageKit attach(Activity a, String title) {
        CigramPageKit kit = new CigramPageKit(a, title);
        a.setContentView(kit.root);
        try {
            Window w = a.getWindow();
            int top = kit.pal.light ? 0xFFFFFFFF : 0xFF19191C;
            int bottom = kit.pal.light ? 0xFFECECEF : 0xFF0B0B0C;
            w.setStatusBarColor(top);
            w.setNavigationBarColor(bottom);
            if (Build.VERSION.SDK_INT >= 23) {
                int flags = w.getDecorView().getSystemUiVisibility();
                flags = kit.pal.light ? flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                w.getDecorView().setSystemUiVisibility(flags);
            }
        } catch (Throwable ignored) { }
        // remote control: the first focus lands on the back button, the page then scrolls with up / down
        if (CigramTvMode.isTv(a)) kit.back.requestFocus();
        return kit;
    }

    int dp(float v) { return CigramUI.dp(a, v); }

    TextView heading(String text) {
        TextView t = CigramUI.label(a, text, 19f, pal.text, true);
        t.setGravity(Gravity.RIGHT);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(26);
        lp.bottomMargin = dp(8);
        column.addView(t, lp);
        return t;
    }

    TextView paragraph(String text) {
        TextView t = CigramUI.label(a, text, 15f, pal.text2, false);
        t.setGravity(Gravity.RIGHT);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        t.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        column.addView(t, lp);
        return t;
    }

    /** A rounded surface (thin border) holding rows of [icon][title / text], RTL. */
    LinearLayout card() {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(CigramPalette.round(a, pal.surface, pal.stroke, 18));
        c.setPadding(dp(16), dp(6), dp(16), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        column.addView(c, lp);
        return c;
    }

    void item(LinearLayout card, int icon, String title, String text, boolean last) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setFocusable(true);   // TV: the remote walks through the list
        FrameLayout ic = new FrameLayout(a);
        ic.addView(new AdsIcons.Icon(a, icon, pal.text), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(30), dp(30));
        il.topMargin = dp(1);
        row.addView(ic, il);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = CigramUI.label(a, title, 15.5f, pal.text, true);
        t.setGravity(Gravity.RIGHT);
        col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        if (text != null && text.length() > 0) {
            TextView s = CigramUI.label(a, text, 13.5f, pal.text3, false);
            s.setGravity(Gravity.RIGHT);
            s.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
            sl.topMargin = dp(3);
            col.addView(s, sl);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.setMarginStart(dp(12));
        row.addView(col, cl);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        if (!last) {
            View line = new View(a);
            line.setBackgroundColor(pal.hairline);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2 + 1));
            ll.setMarginStart(dp(42));
            card.addView(line, ll);
        }
    }

    TextView footnote(String text) {
        TextView t = CigramUI.label(a, text, 12f, pal.text3, false);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(28);
        column.addView(t, lp);
        return t;
    }

    static Typeface bold(Activity a) {
        return Typeface.create(CigramUI.font(a), Typeface.BOLD);
    }

    static ViewGroup.LayoutParams wrap() {
        return new ViewGroup.LayoutParams(-2, -2);
    }
}
