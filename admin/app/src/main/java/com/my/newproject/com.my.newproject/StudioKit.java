package com.my.newproject;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Cigram Studio 4 — the building blocks of the new home and room pages (built on the colours and hairline panels of
 * {@link StudioUi}). Where Studio 3 was rows in a scroll behind a bottom dock, Studio 4 is:
 *
 * <pre>
 *   searchPill  one field that finds any tool or title
 *   Pulse       the live numbers in one strip
 *   lane        a sideways strip (what needs you · pinned shortcuts)
 *   roomCard    a themed area of the app, as a card of a bento grid
 *   toolTile    one tool as a tile (two per line); long-press pins it as a shortcut
 * </pre>
 * Physical right-to-left order is built by hand (the app does not switch Android's RTL mode on): the FIRST view of a
 * list is the right-most one.
 */
final class StudioKit {

    private StudioKit() { }

    private static int dp(Context c, float v) { return CgUi.dp(c, v); }

    private static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    static String fmt(long v) {
        return v < 0 ? "—" : NumberFormat.getIntegerInstance(Locale.US).format(v);
    }

    // ------------------------------------------------------------------ search

    static View searchPill(Context c, String hint, final Runnable action) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(StudioUi.panel(c, StudioUi.PANEL, 26));
        row.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        row.setMinimumHeight(dp(c, 52));
        TextView t = CgUi.text(c, hint, 14, CgCfg.FAINT, false);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setMaxLines(1);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.setMargins(0, 0, dp(c, 10), 0);
        row.addView(t, tl);
        ImageView ic = new ImageView(c);
        ic.setImageDrawable(CgIcons.icon(CgIcons.SEARCH, CgCfg.MUTED));
        row.addView(ic, lp(dp(c, 20), dp(c, 20)));
        row.setClickable(true);
        row.setContentDescription(hint);
        StudioUi.press(row);
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { action.run(); } });
        return row;
    }

    // ------------------------------------------------------------------ pulse

    /** The live numbers: active now · watching now · users today, with the connection state. */
    static final class Pulse {
        final LinearLayout view;
        private final TextView active;
        private final TextView watching;
        private final TextView today;
        private final TextView state;

        Pulse(Context c) {
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setBackground(StudioUi.panel(c, StudioUi.PANEL, 22));
            view.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 14));

            LinearLayout head = new LinearLayout(c);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            state = StudioUi.pill(c, "جارٍ الاتصال", CgCfg.GRAY);
            head.addView(state, lp(-2, -2));
            TextView t = CgUi.text(c, "الآن في التطبيق", 13, CgCfg.MUTED, true);
            t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            view.addView(head, lp(-1, -2));

            active = number(c);
            watching = number(c);
            today = number(c);
            LinearLayout cols = new LinearLayout(c);
            cols.setOrientation(LinearLayout.HORIZONTAL);
            // physical right → left: active, watching, today
            cols.addView(cell(c, today, "مستخدمو اليوم"), new LinearLayout.LayoutParams(0, -2, 1f));
            cols.addView(rule(c));
            cols.addView(cell(c, watching, "يشاهدون الآن"), new LinearLayout.LayoutParams(0, -2, 1f));
            cols.addView(rule(c));
            cols.addView(cell(c, active, "نشطون الآن"), new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams cl = lp(-1, -2);
            cl.topMargin = dp(c, 12);
            view.addView(cols, cl);
        }

        private static TextView number(Context c) {
            TextView v = CgUi.text(c, "—", 27, CgCfg.TEXT, true);
            v.setGravity(Gravity.CENTER);
            v.setMaxLines(1);
            return v;
        }

        private static View cell(Context c, TextView value, String label) {
            LinearLayout k = new LinearLayout(c);
            k.setOrientation(LinearLayout.VERTICAL);
            k.addView(value, lp(-1, -2));
            TextView l = CgUi.text(c, label, 12, CgCfg.MUTED, false);
            l.setGravity(Gravity.CENTER);
            k.addView(l, lp(-1, -2));
            return k;
        }

        private static View rule(Context c) {
            View v = new View(c);
            v.setBackgroundColor(StudioUi.LINE);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Math.max(1, dp(c, 0.7f)), dp(c, 36));
            p.gravity = Gravity.CENTER_VERTICAL;
            v.setLayoutParams(p);
            return v;
        }

        void update(NxLive l) {
            active.setText(fmt(l.active));
            watching.setText(fmt(l.watching));
            today.setText(fmt(l.usersToday));
        }

        void status(String text, int color) {
            StudioUi.setPill(state, text, color);
        }
    }

    // ------------------------------------------------------------------ lane

    /** A sideways strip. {@code items} are in reading order: the first one sits at the right edge. */
    static HorizontalScrollView lane(Context c, List<View> items, int gapDp) {
        final HorizontalScrollView sv = new HorizontalScrollView(c);
        sv.setHorizontalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setFillViewport(true);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        for (int i = items.size() - 1; i >= 0; i--) {
            LinearLayout.LayoutParams p = lp(-2, -2);
            p.leftMargin = dp(c, gapDp);
            row.addView(items.get(i), p);
        }
        sv.addView(row, new HorizontalScrollView.LayoutParams(-1, -2));
        sv.post(new Runnable() {
            @Override public void run() { sv.fullScroll(View.FOCUS_RIGHT); }   // the first item is the right-most one
        });
        return sv;
    }

    /** A chip for the "needs you" strip: [count] [title] [icon]. A null action makes it a plain status. */
    static View attentionChip(Context c, int icon, String title, String count, int color, final Runnable action) {
        LinearLayout chip = new LinearLayout(c);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CgUi.alpha(color, 0x14));
        g.setCornerRadius(dp(c, 18));
        g.setStroke(Math.max(1, dp(c, 1)), CgUi.alpha(color, 0x55));
        chip.setBackground(g);
        chip.setPadding(dp(c, 14), dp(c, 8), dp(c, 10), dp(c, 8));
        chip.setMinimumHeight(dp(c, 58));
        if (count != null && count.length() > 0) {
            TextView n = CgUi.text(c, count, 21, color, true);
            n.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nl = lp(-2, -2);
            nl.rightMargin = dp(c, 12);
            chip.addView(n, nl);
        }
        TextView t = CgUi.text(c, title, 13.5f, CgCfg.TEXT, true);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setMaxLines(2);
        LinearLayout.LayoutParams tl = lp(-2, -2);
        tl.rightMargin = dp(c, 10);
        chip.addView(t, tl);
        chip.addView(StudioUi.iconChip(c, icon, color, 36));
        if (action != null) {
            chip.setClickable(true);
            chip.setContentDescription(title + (count == null ? "" : " " + count));
            StudioUi.press(chip);
            chip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { action.run(); } });
        }
        return chip;
    }

    /** A pinned shortcut: the tool's icon over its name. Long-press unpins. */
    static View shortcut(Context c, StudioMap.Tool t, final Runnable open, final Runnable unpin) {
        LinearLayout k = new LinearLayout(c);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setGravity(Gravity.CENTER_HORIZONTAL);
        k.setBackground(StudioUi.panel(c, StudioUi.PANEL, 18));
        k.setPadding(dp(c, 8), dp(c, 12), dp(c, 8), dp(c, 10));
        k.setMinimumWidth(dp(c, 92));
        k.addView(StudioUi.iconChip(c, t.icon, CgCfg.TEXT, 46));
        TextView n = CgUi.text(c, t.title, 11.5f, CgCfg.TEXT, true);
        n.setGravity(Gravity.CENTER);
        n.setMinLines(2);                                       // every shortcut is the same height, whatever its name's length
        n.setMaxLines(2);
        LinearLayout.LayoutParams nl = lp(dp(c, 84), -2);
        nl.topMargin = dp(c, 8);
        k.addView(n, nl);
        k.setClickable(true);
        k.setContentDescription(t.title + " — اضغط مطولاً لإزالة الاختصار");
        StudioUi.press(k);
        k.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { open.run(); } });
        k.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { unpin.run(); return true; }
        });
        return k;
    }

    // ------------------------------------------------------------------ bento (rooms)

    /**
     * A room as a card. {@code extra} (optional) is a view under the description (the library shows its numbers there);
     * the little note pill at the bottom is set later with {@link #setRoomNote}.
     */
    static View roomCard(Context c, StudioMap.Room r, int toolCount, boolean wide, View extra, final Runnable open) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TR_BL,
                new int[]{CgUi.alpha(0xFFFFFFFF, 0x26), CgUi.alpha(0xFFFFFFFF, 0x0B), StudioUi.PANEL});
        g.setCornerRadius(dp(c, 22));
        g.setStroke(Math.max(1, dp(c, 1)), StudioUi.LINE);
        card.setBackground(g);
        card.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 14));
        card.setMinimumHeight(dp(c, wide ? 132 : 178));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView count = StudioUi.pill(c, toolCount + " أداة", CgCfg.MUTED);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        LinearLayout leftBox = new LinearLayout(c);
        leftBox.setOrientation(LinearLayout.HORIZONTAL);
        leftBox.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        leftBox.addView(count, lp(-2, -2));
        top.addView(leftBox, cl);
        top.addView(StudioUi.iconChip(c, r.icon, CgCfg.TEXT, 46));
        card.addView(top, lp(-1, -2));

        TextView title = CgUi.text(c, r.title, 18, CgCfg.TEXT, true);
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tl = lp(-1, -2);
        tl.topMargin = dp(c, 12);
        card.addView(title, tl);
        TextView sub = CgUi.text(c, r.sub, 12, CgCfg.MUTED, false);
        sub.setGravity(Gravity.RIGHT);
        sub.setMaxLines(wide ? 2 : 3);
        card.addView(sub, lp(-1, -2));
        if (extra != null) {
            LinearLayout.LayoutParams el = lp(-1, -2);
            el.topMargin = dp(c, 8);
            card.addView(extra, el);
        }

        View grow = new View(c);
        card.addView(grow, new LinearLayout.LayoutParams(-1, 0, 1f));
        TextView note = StudioUi.pill(c, " ", CgCfg.GRAY);
        note.setVisibility(View.GONE);
        LinearLayout.LayoutParams nl = lp(-2, -2);
        nl.gravity = Gravity.RIGHT;
        nl.topMargin = dp(c, 10);
        card.addView(note, nl);
        card.setTag(note);

        card.setClickable(true);
        card.setContentDescription(r.title + " — " + r.sub);
        StudioUi.press(card);
        card.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { open.run(); } });
        return card;
    }

    /** Sets (or hides, for an empty text) the note pill of a card made by {@link #roomCard}. */
    static void setRoomNote(View card, String text, int color) {
        Object o = card == null ? null : card.getTag();
        if (!(o instanceof TextView)) return;
        TextView note = (TextView) o;
        if (text == null || text.length() == 0) {
            note.setVisibility(View.GONE);
            return;
        }
        StudioUi.setPill(note, text, color);
        note.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ tiles

    /** One tool as a tile. Tap opens it; long-press pins / unpins it. */
    static View toolTile(Context c, StudioMap.Tool t, String badge, boolean pinned, final Runnable open, final Runnable togglePin) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(StudioUi.panel(c, StudioUi.PANEL, 18));
        card.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        card.setMinimumHeight(dp(c, 120));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout marks = new LinearLayout(c);
        marks.setOrientation(LinearLayout.HORIZONTAL);
        marks.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        if (badge != null && badge.length() > 0) {
            boolean number = Character.isDigit(badge.charAt(0));
            marks.addView(StudioUi.pill(c, badge, number ? CgCfg.WARN : StudioUi.GOLD), lp(-2, -2));
        }
        if (pinned) {
            ImageView pin = new ImageView(c);
            pin.setImageDrawable(CgIcons.icon(CgIcons.STAR, StudioUi.GOLD));
            LinearLayout.LayoutParams pl = lp(dp(c, 15), dp(c, 15));
            pl.leftMargin = dp(c, 6);
            marks.addView(pin, pl);
        }
        top.addView(marks, new LinearLayout.LayoutParams(0, -2, 1f));
        top.addView(StudioUi.iconChip(c, t.icon, CgCfg.TEXT, 40));
        card.addView(top, lp(-1, -2));

        TextView title = CgUi.text(c, t.title, 14.5f, CgCfg.TEXT, true);
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        title.setMaxLines(2);
        LinearLayout.LayoutParams tl = lp(-1, -2);
        tl.topMargin = dp(c, 10);
        card.addView(title, tl);
        TextView sub = CgUi.text(c, t.sub, 11.5f, CgCfg.MUTED, false);
        sub.setGravity(Gravity.RIGHT);
        sub.setMaxLines(3);
        card.addView(sub, lp(-1, -2));

        card.setClickable(true);
        card.setContentDescription(t.title + " — " + t.sub + (pinned ? " (مثبّت)" : "") + " — اضغط مطولاً للتثبيت");
        StudioUi.press(card);
        card.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { open.run(); } });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { togglePin.run(); return true; }
        });
        return card;
    }

    /** Two cards per line, the first one on the right; an odd last card leaves its left half empty. */
    static LinearLayout grid(Context c, List<View> cells, int gapDp) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int half = dp(c, gapDp) / 2;
        for (int i = 0; i < cells.size(); i += 2) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBaselineAligned(false);
            View right = cells.get(i);
            View left = i + 1 < cells.size() ? cells.get(i + 1) : new View(c);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, -1, 1f);
            ll.setMargins(0, 0, half, 0);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, -1, 1f);
            rl.setMargins(half, 0, 0, 0);
            row.addView(left, ll);
            row.addView(right, rl);
            LinearLayout.LayoutParams rowLp = lp(-1, -2);
            rowLp.bottomMargin = dp(c, gapDp);
            col.addView(row, rowLp);
        }
        return col;
    }
}
