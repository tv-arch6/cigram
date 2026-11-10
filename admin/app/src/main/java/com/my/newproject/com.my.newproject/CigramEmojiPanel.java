package com.my.newproject;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * The emoji keyboard of the private conversation, laid out like a phone's own emoji keyboard: «المستخدمة مؤخراً» plus
 * the eight groups in the standard order, one tab each, and a delete key that removes a whole emoji per press (and
 * repeats while held).
 *
 * Built for weak phones: ONE recycling grid (only the cells on screen exist), cells are a plain custom view that
 * draws from the atlas ({@link CigramEmoji}) — no TextView, no font shaping, no per-cell bitmap.
 */
final class CigramEmojiPanel extends LinearLayout {

    interface Listener {
        void onEmoji(String emoji);
        void onBackspace();
    }

    private static final int TAB_RECENT = -1;
    /** One emoji that stands for each group on its tab. */
    private static final String[] TAB_ICONS = {"😀", "🐻", "🍔", "⚽", "🚗", "💡", "🔣", "🏁"};
    private static final String RECENT_ICON = "🕓";

    private final Listener listener;
    private final CigramEmoji.Index ix;
    private final GridView grid;
    private final TextView title;
    private final ArrayList<Tab> tabs = new ArrayList<Tab>();
    private final ArrayList<Integer> cells = new ArrayList<Integer>();
    private final CellAdapter adapter = new CellAdapter();
    private int current = 0;
    private final float d;
    private final int accent;

    CigramEmojiPanel(Context c, int background, int accentColor, Listener l) {
        super(c);
        listener = l;
        accent = accentColor;
        d = c.getResources().getDisplayMetrics().density;
        ix = CigramEmoji.index(c);
        setOrientation(VERTICAL);
        setBackgroundColor(background);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);     // emoji order is the same in every language

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), dp(4), dp(6), dp(2));
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        hs.addView(row);
        tabs.add(new Tab(c, TAB_RECENT, RECENT_ICON, "المستخدمة مؤخراً"));
        for (int g = 0; g < CigramEmoji.GROUPS.length; g++) tabs.add(new Tab(c, g, TAB_ICONS[g], CigramEmoji.GROUP_LABELS[g]));
        for (final Tab t : tabs) {
            row.addView(t, new LinearLayout.LayoutParams(dp(40), dp(38)));
            t.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { select(t.group, true); }
            });
        }
        top.addView(hs, new LinearLayout.LayoutParams(0, dp(40), 1f));
        Backspace back = new Backspace(c);
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(40)));
        addView(top, new LinearLayout.LayoutParams(-1, -2));

        title = new TextView(c);
        title.setTextSize(11.5f);
        title.setTextColor(0x99FFFFFF);
        title.setGravity(Gravity.RIGHT);
        title.setPadding(dp(14), dp(2), dp(14), dp(2));
        addView(title, new LinearLayout.LayoutParams(-1, -2));

        grid = new GridView(c);
        grid.setColumnWidth(dp(42));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(2));
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setVerticalScrollBarEnabled(false);
        grid.setPadding(dp(6), 0, dp(6), dp(6));
        grid.setClipToPadding(false);
        grid.setAdapter(adapter);
        addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        select(CigramEmoji.recents(c).isEmpty() ? 0 : TAB_RECENT, false);
    }

    private int dp(float v) {
        return (int) (v * d + 0.5f);
    }

    /** Called when the panel is shown again: «recently used» may have changed. */
    void refresh() {
        if (current == TAB_RECENT) select(TAB_RECENT, false);
    }

    private void select(int group, boolean byUser) {
        current = group;
        cells.clear();
        if (group == TAB_RECENT) {
            cells.addAll(CigramEmoji.recents(getContext()));
            title.setText(cells.isEmpty() ? "لم تستخدم أي رمز بعد" : "المستخدمة مؤخراً");
        } else {
            for (int i = ix.groupStart[group]; i < ix.groupStart[group + 1]; i++) cells.add(i);
            title.setText(CigramEmoji.GROUP_LABELS[group]);
        }
        for (Tab t : tabs) t.setActive(t.group == group);
        adapter.notifyDataSetChanged();
        grid.setSelection(0);
        if (byUser) try { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ grid

    private final class CellAdapter extends BaseAdapter {
        @Override public int getCount() { return cells.size(); }
        @Override public Object getItem(int i) { return cells.get(i); }
        @Override public long getItemId(int i) { return cells.get(i); }
        @Override public View getView(int pos, View convert, ViewGroup parent) {
            Cell v = convert instanceof Cell ? (Cell) convert : new Cell(getContext());
            v.set(cells.get(pos));
            return v;
        }
    }

    private final class Cell extends View {
        private int idx = -1;
        private final RectF dst = new RectF();
        private final Paint press = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean down;

        Cell(Context c) {
            super(c);
            setLayoutParams(new AbsListView.LayoutParams(-1, dp(44)));
            press.setColor(0x22FFFFFF);
            setClickable(true);
            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    if (idx < 0 || idx >= ix.count) return;
                    String e = ix.seq[idx];
                    CigramEmoji.remember(getContext(), e);
                    if (listener != null) listener.onEmoji(e);
                }
            });
        }

        void set(int i) {
            idx = i;
            setContentDescription(i >= 0 && i < ix.count ? ix.seq[i] : "");
            invalidate();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            boolean now = a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE;
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) now = false;
            if (now != down) { down = now; invalidate(); }
            return super.onTouchEvent(e);
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(Math.min(w, h) - dp(10), dp(34));
            if (down) canvas.drawRoundRect(new RectF(dp(2), dp(2), w - dp(2), h - dp(2)), dp(10), dp(10), press);
            dst.set((w - s) / 2f, (h - s) / 2f, (w + s) / 2f, (h + s) / 2f);
            CigramEmoji.draw(getContext(), canvas, idx, dst);
        }
    }

    // ------------------------------------------------------------------ tabs

    private final class Tab extends View {
        final int group;
        private final int idx;
        private boolean active;
        private final RectF dst = new RectF();
        private final GradientDrawable pill = new GradientDrawable();

        Tab(Context c, int group, String icon, String label) {
            super(c);
            this.group = group;
            Integer i = ix.lookup.get(icon);
            idx = i == null ? -1 : i;
            setContentDescription(label);
            setClickable(true);
            pill.setCornerRadius(dp(12));
            pill.setColor(0x24FFFFFF);
            pill.setStroke(Math.max(1, dp(1)), (accent & 0x00FFFFFF) | 0x66000000);
        }

        void setActive(boolean a) {
            if (active != a) { active = a; invalidate(); }
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (active) {
                pill.setBounds(dp(3), dp(3), (int) w - dp(3), (int) h - dp(3));
                pill.draw(canvas);
            }
            float s = dp(22);
            dst.set((w - s) / 2f, (h - s) / 2f, (w + s) / 2f, (h + s) / 2f);
            int save = canvas.saveLayerAlpha(dst, active ? 255 : 130);
            CigramEmoji.draw(getContext(), canvas, idx, dst);
            canvas.restoreToCount(save);
        }
    }

    // ------------------------------------------------------------------ delete key

    private final class Backspace extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Runnable repeat = new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onBackspace();
                postDelayed(this, 70);
            }
        };

        Backspace(Context c) {
            super(c);
            setContentDescription("حذف");
            setClickable(true);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                if (listener != null) listener.onBackspace();
                try { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); } catch (Throwable ignored) { }
                postDelayed(repeat, 420);
                setAlpha(0.6f);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                removeCallbacks(repeat);
                setAlpha(1f);
            }
            return true;
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(repeat);
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            float bw = dp(24), bh = dp(17);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.7f));
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xFFFFFFFF);
            path.reset();
            float l = cx - bw / 2f, r = cx + bw / 2f, t = cy - bh / 2f, b = cy + bh / 2f, nose = bw * 0.30f;
            path.moveTo(l, cy);
            path.lineTo(l + nose, t);
            path.lineTo(r, t);
            path.lineTo(r, b);
            path.lineTo(l + nose, b);
            path.close();
            canvas.drawPath(path, p);
            float xc = l + nose + (r - l - nose) / 2f, k = bh * 0.22f;
            canvas.drawLine(xc - k, cy - k, xc + k, cy + k, p);
            canvas.drawLine(xc + k, cy - k, xc - k, cy + k, p);
        }
    }
}
