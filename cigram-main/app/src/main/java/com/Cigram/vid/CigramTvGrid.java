package com.Cigram.vid;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * CIGRAM TV EDITION — the Movies and Series panels: a genre chip row (remote-navigable) above a
 * wrapping poster grid whose columns adapt to the screen width. Same catalogue, no new data source.
 */
final class CigramTvGrid {

    private CigramTvGrid() { }

    static View movies(CigramTvActivity a) { return build(a, "الأفلام", false); }
    static View series(CigramTvActivity a) { return build(a, "المسلسلات", true); }

    private static View build(final CigramTvActivity a, String heading, final boolean seriesOnly) {
        final List<JSONObject> base = new ArrayList<JSONObject>();
        for (JSONObject o : a.catalog()) {
            if (CigramTvCards.isSeries(o) == seriesOnly) base.add(o);
        }

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);

        // header
        TextView head = new TextView(a);
        head.setText(heading + "   (" + base.size() + ")");
        head.setTextColor(CigramDesign.TEXT);
        head.setTextSize(26f);
        head.setTypeface(a.font(), Typeface.BOLD);
        head.setGravity(Gravity.RIGHT);
        head.setPadding(a.dp(40), a.dp(24), a.dp(28), a.dp(8));
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        // genre chips
        final List<String> genres = genresOf(base);
        final FrameLayout gridHolder = new FrameLayout(a);

        HorizontalScrollView chipScroll = new HorizontalScrollView(a);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        chipScroll.setPadding(a.dp(28), 0, a.dp(28), a.dp(6));
        final LinearLayout chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        chips.setGravity(Gravity.RIGHT);

        final String[] active = {"الكل"};
        List<String> all = new ArrayList<String>();
        all.add("الكل");
        all.addAll(genres);
        for (int i = all.size() - 1; i >= 0; i--) {   // RTL: first chip at far right
            final String genre = all.get(i);
            TextView chip = chip(a, genre, genre.equals(active[0]));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    active[0] = genre;
                    for (int c = 0; c < chips.getChildCount(); c++) {
                        TextView t = (TextView) chips.getChildAt(c);
                        boolean on = t.getText().toString().equals(genre);
                        styleChip(a, t, on);
                    }
                    fill(a, gridHolder, filter(base, genre));
                }
            });
            chips.addView(chip);
        }
        chipScroll.addView(chips, new HorizontalScrollView.LayoutParams(-2, -2));
        root.addView(chipScroll, new LinearLayout.LayoutParams(-1, -2));

        root.addView(gridHolder, new LinearLayout.LayoutParams(-1, 0, 1f));
        fill(a, gridHolder, base);
        return root;
    }

    private static void fill(CigramTvActivity a, FrameLayout holder, List<JSONObject> items) {
        holder.removeAllViews();
        if (items.isEmpty()) {
            TextView empty = new TextView(a);
            empty.setText("لا يوجد محتوى هنا");
            empty.setTextColor(CigramDesign.TEXT_2);
            empty.setTextSize(16f);
            empty.setGravity(Gravity.CENTER);
            holder.addView(empty, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        holder.addView(CigramTvCards.grid(a, items), new FrameLayout.LayoutParams(-1, -1));
    }

    private static List<JSONObject> filter(List<JSONObject> base, String genre) {
        if ("الكل".equals(genre)) return base;
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (JSONObject o : base) {
            String cat = CigramTvCards.str(o, "category", "genre", "genres");
            if (cat.contains(genre)) out.add(o);
        }
        return out;
    }

    private static List<String> genresOf(List<JSONObject> items) {
        Set<String> set = new LinkedHashSet<String>();
        for (JSONObject o : items) {
            String cat = CigramTvCards.str(o, "category", "genre", "genres");
            if (cat.length() == 0) continue;
            for (String part : cat.split("[,،]")) {
                String g = part.trim();
                if (g.length() > 0) set.add(g);
            }
        }
        List<String> list = new ArrayList<String>(set);
        if (list.size() > 14) list = list.subList(0, 14);
        return list;
    }

    private static TextView chip(CigramTvActivity a, String text, boolean active) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(14f);
        t.setTypeface(a.font(), Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(a.dp(18), a.dp(9), a.dp(18), a.dp(9));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = a.dp(8);
        t.setLayoutParams(lp);
        styleChip(a, t, active);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(110L).start();
            }
        });
        return t;
    }

    private static void styleChip(CigramTvActivity a, TextView t, boolean active) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(a.dp(20));
        g.setColor(active ? CigramDesign.ACCENT : 0x18FFFFFF);
        t.setBackground(g);
        t.setTextColor(active ? CigramDesign.TEXT : CigramDesign.TEXT_2);
    }
}
