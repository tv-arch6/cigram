package com.my.newproject;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Cigram Studio (admin 3.0) — the building blocks of the new look, designed from an empty page:
 *
 * <pre>
 *   Dock      five workspaces at the bottom of the screen (thumb reach), the active one carries a red pill
 *   Deck      large action cards with a diagonal colour wash: the four things an editor does every day
 *   Group     a titled panel of flat rows (icon chip · title · one line of explanation · counter · chevron)
 *   Stat      a number with its label on a hairline tile
 *   Pill      a small coloured state label
 * </pre>
 * Rules of the design: graphite surfaces separated by one hairline (no drop shadows — they cost frames on weak
 * phones and blur on OLED), one accent (Cigram red) for the primary action of a screen, gold only for "new / needs
 * you", 16–22 dp radii, 48 dp minimum touch targets, and physical right-to-left order built by hand (the app does not
 * switch Android's RTL mode on).
 */
final class StudioUi {

    private StudioUi() { }

    static final int INK = 0xFF000000;          // page
    static final int PANEL = 0xFF111113;        // panels / rows
    static final int PANEL_HI = 0xFF1C1C1F;     // pressed / raised
    static final int LINE = 0xFF2C2C2F;         // hairline
    static final int RED = 0xFFFFFFFF;
    static final int GOLD = 0xFFE5E5EA;
    static final int SKY = 0xFFFFFFFF;
    static final int MINT = 0xFFE5E5EA;
    static final int VIOLET = 0xFFD1D1D6;
    static final int ORANGE = 0xFFFFFFFF;
    static final int PINK = 0xFFC7C7CC;

    static int dp(Context c, float v) { return CgUi.dp(c, v); }

    static void press(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.975f).scaleY(0.975f).alpha(0.86f).setDuration(70).start();
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start();
                return false;
            }
        });
    }

    static GradientDrawable panel(Context c, int fill, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radius));
        g.setStroke(Math.max(1, dp(c, 1)), LINE);
        return g;
    }

    // ------------------------------------------------------------------ small parts

    static TextView pill(Context c, String text, int color) {
        TextView t = CgUi.text(c, text, 11.5f, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 9), dp(c, 3), dp(c, 9), dp(c, 3));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CgUi.alpha(color, 0x26));
        g.setCornerRadius(dp(c, 10));
        t.setBackground(g);
        return t;
    }

    static void setPill(TextView t, String text, int color) {
        t.setText(text);
        t.setTextColor(color);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CgUi.alpha(color, 0x26));
        g.setCornerRadius(dp(t.getContext(), 10));
        t.setBackground(g);
    }

    /** Rounded square holding an icon, tinted with the row's colour. */
    static View iconChip(Context c, int icon, int color, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(CgIcons.icon(icon, color));
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p = dp(c, sizeDp * 0.24f);
        iv.setPadding(p, p, p, p);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CgUi.alpha(color, 0x22));
        g.setCornerRadius(dp(c, sizeDp * 0.32f));
        iv.setBackground(g);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return iv;
    }

    static TextView label(Context c, String text) {
        TextView t = CgUi.text(c, text, 12.5f, CgCfg.FAINT, true);
        t.setGravity(Gravity.RIGHT);
        t.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(c, 6), dp(c, 20), dp(c, 6), dp(c, 8));
        t.setLayoutParams(lp);
        return t;
    }

    // ------------------------------------------------------------------ deck (primary actions)

    /** A large action card: icon chip on the right, title and one line under it, colour wash from the right edge. */
    static View deck(Context c, int icon, String title, String sub, int color, final Runnable action) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.RIGHT);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TR_BL, new int[]{CgUi.alpha(color, 0x4D), CgUi.alpha(color, 0x14), PANEL});
        g.setCornerRadius(dp(c, 20));
        g.setStroke(Math.max(1, dp(c, 1)), CgUi.alpha(color, 0x55));
        card.setBackground(g);
        card.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
        card.setMinimumHeight(dp(c, 118));
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(CgIcons.icon(icon, 0xFFFFFFFF));
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p = dp(c, 9);
        iv.setPadding(p, p, p, p);
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(color);
        ib.setCornerRadius(dp(c, 13));
        iv.setBackground(ib);
        card.addView(iv, new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
        TextView t = CgUi.text(c, title, 16, CgCfg.TEXT, true);
        t.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = dp(c, 12);
        card.addView(t, tl);
        TextView s = CgUi.text(c, sub, 12, CgCfg.MUTED, false);
        s.setGravity(Gravity.RIGHT);
        s.setMaxLines(2);
        card.addView(s, new LinearLayout.LayoutParams(-1, -2));
        card.setClickable(true);
        card.setContentDescription(title + " — " + sub);
        press(card);
        card.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { action.run(); } });
        return card;
    }

    /** Two cards side by side with equal width (physical order: the first one on the RIGHT). */
    static LinearLayout pair(Context c, View right, View left) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -1, 1f);
        a.setMargins(0, 0, dp(c, 5), 0);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, -1, 1f);
        b.setMargins(dp(c, 5), 0, 0, 0);
        row.addView(left, a);
        row.addView(right, b);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.bottomMargin = dp(c, 10);
        row.setLayoutParams(rl);
        return row;
    }

    // ------------------------------------------------------------------ groups of rows

    /** A panel that holds rows; add them with {@link #row}. */
    static LinearLayout group(Context c) {
        LinearLayout g = new LinearLayout(c);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(panel(c, PANEL, 18));
        g.setPadding(0, dp(c, 4), 0, dp(c, 4));
        return g;
    }

    /**
     * A flat row inside a {@link #group}: [chevron] [badge] [title / sub ...........] [icon chip]  (right-to-left).
     * A hairline is drawn above every row but the first one.
     */
    static View row(LinearLayout group, int icon, String title, String sub, String badge, int color, final Runnable action) {
        Context c = group.getContext();
        if (group.getChildCount() > 0) {
            View line = new View(c);
            line.setBackgroundColor(LINE);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 0.7f)));
            ll.setMargins(dp(c, 14), 0, dp(c, 62), 0);
            group.addView(line, ll);
        }
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        row.setMinimumHeight(dp(c, 62));
        TextView chev = CgUi.text(c, "‹", 20, CgCfg.FAINT, false);
        chev.setGravity(Gravity.CENTER);
        row.addView(chev, new LinearLayout.LayoutParams(dp(c, 18), -2));
        if (badge != null && badge.length() > 0) {
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
            bl.setMargins(dp(c, 6), 0, 0, 0);
            row.addView(pill(c, badge, "جديد".equals(badge) ? GOLD : color), bl);
        }
        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView t = CgUi.text(c, title, 15, CgCfg.TEXT, true);
        t.setGravity(Gravity.RIGHT);
        t.setMaxLines(1);
        mid.addView(t, new LinearLayout.LayoutParams(-1, -2));
        if (sub != null && sub.length() > 0) {
            TextView s = CgUi.text(c, sub, 12, CgCfg.MUTED, false);
            s.setGravity(Gravity.RIGHT);
            s.setMaxLines(2);
            mid.addView(s, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, -2, 1f);
        ml.setMargins(dp(c, 8), 0, dp(c, 12), 0);
        row.addView(mid, ml);
        row.addView(iconChip(c, icon, color, 40));
        row.setClickable(true);
        row.setContentDescription(title + (sub == null ? "" : " — " + sub));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(PANEL_HI);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, pressed);
        sl.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
        row.setBackground(sl);
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { action.run(); } });
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    static Runnable go(final Activity a, final Class<?> cls) {
        return new Runnable() {
            @Override public void run() { a.startActivity(new android.content.Intent(a, cls)); }
        };
    }

    // ------------------------------------------------------------------ stats

    static LinearLayout stat(Context c, String label, String value, int color) {
        LinearLayout k = new LinearLayout(c);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setBackground(panel(c, PANEL, 16));
        k.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 10));
        TextView v = CgUi.text(c, value, 22, color, true);
        v.setTag("v");
        v.setGravity(Gravity.RIGHT);
        v.setMaxLines(1);                                   // not setSingleLine: with a right gravity it scrolls the text out of view
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        k.addView(v, new LinearLayout.LayoutParams(-1, -2));
        TextView l = CgUi.text(c, label, 12, CgCfg.MUTED, false);
        l.setGravity(Gravity.RIGHT);
        k.addView(l, new LinearLayout.LayoutParams(-1, -2));
        return k;
    }

    /** Equal-width tiles in one line, the first one on the right. */
    static LinearLayout line(Context c, View... views) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = views.length - 1; i >= 0; i--) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            lp.setMargins(i == views.length - 1 ? 0 : dp(c, 4), 0, i == 0 ? 0 : dp(c, 4), 0);
            row.addView(views[i], lp);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.bottomMargin = dp(c, 10);
        row.setLayoutParams(rl);
        return row;
    }

    // ------------------------------------------------------------------ dock (workspaces)

    interface OnTab { void onTab(int index); }

    static final class Dock {
        final LinearLayout view;
        private final Item[] items;
        private int selected = -1;

        Dock(Context c, String[] labels, int[] icons, final OnTab listener) {
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setBackgroundColor(0xFF0D0F13);
            view.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 8));
            items = new Item[labels.length];
            // physical order right → left: the first workspace is the right-most
            for (int i = labels.length - 1; i >= 0; i--) {
                final int index = i;
                items[i] = new Item(c, labels[i], icons[i]);
                items[i].setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { select(index); listener.onTab(index); }
                });
                view.addView(items[i], new LinearLayout.LayoutParams(0, dp(c, 56), 1f));
            }
        }

        void select(int index) {
            if (selected == index) return;
            selected = index;
            for (int i = 0; i < items.length; i++) items[i].setActive(i == index);
        }

        int selected() { return selected; }
    }

    private static final class Item extends View {
        private final String label;
        private final int icon;
        private boolean active;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private android.graphics.drawable.Drawable on, off;

        Item(Context c, String label, int icon) {
            super(c);
            this.label = label;
            this.icon = icon;
            setClickable(true);
            setContentDescription(label);
            on = CgIcons.icon(icon, 0xFFFFFFFF);
            off = CgIcons.icon(icon, CgCfg.FAINT);
            p.setTextAlign(Paint.Align.CENTER);
            try { p.setTypeface(CgUi.fontBold(c)); } catch (Throwable ignored) { }
        }

        void setActive(boolean a) {
            if (active != a) { active = a; invalidate(); }
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
            float cx = w / 2f;
            float pillW = 56 * d, pillH = 30 * d, top = 4 * d;
            if (active) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(RED);
                r.set(cx - pillW / 2f, top, cx + pillW / 2f, top + pillH);
                canvas.drawRoundRect(r, pillH / 2f, pillH / 2f, p);
            }
            int s = (int) (20 * d);
            android.graphics.drawable.Drawable dr = active ? on : off;
            int l = (int) (cx - s / 2f), t = (int) (top + (pillH - s) / 2f);
            dr.setBounds(l, t, l + s, t + s);
            dr.draw(canvas);
            p.setStyle(Paint.Style.FILL);
            p.setColor(active ? CgCfg.TEXT : CgCfg.FAINT);
            p.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText(label, cx, h - 6 * d, p);
        }
    }
}
