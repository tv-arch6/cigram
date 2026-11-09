package com.Cigram.vid;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

/**
 * CIGRAM TV EDITION: shared, remote-friendly building blocks (poster card, horizontal row, grid),
 * used by the home, the movies / series panels, the search and the details page, so the whole TV
 * UI has one card look.
 *
 * Performance notes:
 *  - The long lists (movies, series, search results) use a recycling {@link GridView}: only the
 *    cards on screen exist, a poster is requested when its card is bound and dropped when the card
 *    scrolls away. The old version built one card, and requested one poster, for every title.
 *  - The short rows keep a handful of real cards (the caller caps them).
 */
final class CigramTvCards {

    private CigramTvCards() { }

    static final int CARD_W_DP = 150;
    static final int CARD_H_DP = 222;   // 2:3 poster

    static String str(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && v.trim().length() > 0 && !"null".equals(v)) return v.trim();
        }
        return "";
    }

    static boolean isSeries(JSONObject o) { return "series".equalsIgnoreCase(str(o, "type")); }
    static boolean isMovie(JSONObject o)  { return !isSeries(o); }

    /** The small grey line under a title: "2019  ·  IMDb 8.3". */
    static String metaOf(JSONObject item) {
        String year = str(item, "year", "release_year");
        String imdb = str(item, "imdb", "rating", "vote_average");
        StringBuilder meta = new StringBuilder();
        if (year.length() > 0) meta.append(year);
        if (imdb.length() > 0) meta.append(meta.length() > 0 ? "  ·  " : "").append("IMDb ").append(imdb);
        return meta.toString();
    }

    // ------------------------------------------------------------------ one card

    /** The three views a card needs to be (re)bound; kept in the card's tag. */
    private static final class Cell {
        ImageView poster;
        TextView title;
        TextView sub;
    }

    private static View newCell(CigramTvHost h, boolean focusable) {
        Activity a = h.activity();
        final int w = h.dp(CARD_W_DP);
        final int posterH = h.dp(CARD_H_DP);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setPadding(h.dp(6), h.dp(6), h.dp(6), h.dp(6));
        if (focusable) {
            card.setFocusable(true);
            card.setClickable(true);
        }

        FrameLayout posterBox = new FrameLayout(a);
        GradientDrawable placeholder = new GradientDrawable();
        placeholder.setColor(0xFF12222C);
        placeholder.setCornerRadius(h.dp(12));
        posterBox.setBackground(placeholder);
        posterBox.setClipToOutline(true);

        Cell cell = new Cell();
        cell.poster = new ImageView(a);
        cell.poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        posterBox.addView(cell.poster, new FrameLayout.LayoutParams(-1, -1));
        card.addView(posterBox, new LinearLayout.LayoutParams(w, posterH));

        cell.title = new TextView(a);
        cell.title.setTextColor(CigramDesign.TEXT);
        cell.title.setTextSize(13.5f);
        cell.title.setTypeface(h.font(), Typeface.BOLD);
        cell.title.setMaxLines(1);
        cell.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cell.title.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(w, -2);
        tlp.topMargin = h.dp(8);
        card.addView(cell.title, tlp);

        cell.sub = new TextView(a);
        cell.sub.setTextColor(CigramDesign.TEXT_3);
        cell.sub.setTextSize(11.5f);
        cell.sub.setTypeface(h.font());
        cell.sub.setMaxLines(1);
        cell.sub.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(w, -2);
        slp.topMargin = h.dp(2);
        card.addView(cell.sub, slp);

        card.setTag(cell);
        return card;
    }

    private static void bindCell(View card, JSONObject item) {
        Cell cell = (Cell) card.getTag();
        cell.title.setText(str(item, "title", "name", "series_title"));
        String meta = metaOf(item);
        cell.sub.setText(meta);
        cell.sub.setVisibility(meta.length() == 0 ? View.GONE : View.VISIBLE);
        CigramTvImage.load(cell.poster, str(item, "poster", "image", "backdrop", "series_poster"), 480);
    }

    private static void lift(View v, boolean on, CigramTvHost h) {
        if (v == null) return;
        v.animate().cancel();
        v.animate().scaleX(on ? 1.08f : 1f).scaleY(on ? 1.08f : 1f).setDuration(140L).start();
        v.setElevation(on ? h.dp(8) : 0);
    }

    /** A focusable poster card (for the short rows). Click opens the details page. */
    static View posterCard(final CigramTvHost h, final JSONObject item) {
        final View card = newCell(h, true);
        bindCell(card, item);
        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) { lift(v, has, h); }
        });
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { h.openDetails(item, v); }
        });
        return card;
    }

    // ------------------------------------------------------------------ a titled row

    /** A titled horizontal row of poster cards (the first item sits at the right edge, RTL). */
    static View row(final CigramTvHost h, String heading, List<JSONObject> items) {
        Activity a = h.activity();
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);

        TextView head = new TextView(a);
        head.setText(heading);
        head.setTextColor(CigramDesign.TEXT);
        head.setTextSize(CigramDesign.SECTION_SP);
        head.setTypeface(h.font(), Typeface.BOLD);
        head.setGravity(Gravity.RIGHT);
        head.setPadding(h.dp(6), h.dp(14), h.dp(6), h.dp(6));
        col.addView(head, new LinearLayout.LayoutParams(-1, -2));

        col.addView(strip(h, items), new LinearLayout.LayoutParams(-1, -2));
        return col;
    }

    /** Just the horizontal strip of cards (no heading). */
    static View strip(final CigramTvHost h, List<JSONObject> items) {
        Activity a = h.activity();
        final HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        scroll.setFillViewport(true);   // a short row still sits at the right edge (gravity below)
        scroll.setPadding(0, h.dp(8), 0, h.dp(10));   // room for the lifted card

        LinearLayout rail = new LinearLayout(a);
        rail.setOrientation(LinearLayout.HORIZONTAL);
        rail.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        rail.setGravity(Gravity.RIGHT);
        rail.setClipChildren(false);
        for (int i = 0; i < items.size(); i++) {
            rail.addView(posterCard(h, items.get(i)), 0);   // prepend: the first item ends at the far right
        }
        scroll.addView(rail, new HorizontalScrollView.LayoutParams(-2, -2));

        // a long row opens at its RIGHT end, so the newest card is the one in view
        final boolean[] placed = {false};
        scroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int oldL, int oldT, int oldR, int oldB) {
                if (placed[0]) return;
                View child = scroll.getChildAt(0);
                int viewport = r - l;
                if (child == null || child.getWidth() <= 0 || viewport <= 0) return;
                placed[0] = true;
                scroll.scrollTo(Math.max(0, child.getWidth() - viewport), 0);
            }
        });
        return scroll;
    }

    // ------------------------------------------------------------------ the recycled grid

    /**
     * A recycling grid of poster cards. Columns fit the width it is given (so it works full width
     * on the movies page and in the narrower search results), and the first title is at the right.
     */
    static View grid(final CigramTvHost h, final List<JSONObject> items) {
        Activity a = h.activity();
        final int cellW = h.dp(CARD_W_DP + 12);
        final int cellH = h.dp(CARD_H_DP + 12) + h.dp(46);

        final GridView grid = new GridView(a);
        grid.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(cellW);
        grid.setStretchMode(GridView.STRETCH_SPACING);
        grid.setVerticalSpacing(h.dp(8));
        grid.setSelector(new ColorDrawable(0x00000000));   // the app's focus glow outlines the selected card
        grid.setDrawSelectorOnTop(false);
        grid.setVerticalScrollBarEnabled(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setPadding(h.dp(28), h.dp(12), h.dp(28), h.dp(28));
        grid.setFocusable(true);
        grid.setFocusableInTouchMode(false);

        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int position) { return items.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View card = convertView;
                if (card == null) {
                    card = newCell(h, false);   // the grid owns selection: cards are not focus targets themselves
                    card.setLayoutParams(new AbsListView.LayoutParams(cellW, cellH));
                } else {
                    card.animate().cancel();
                    card.setScaleX(1f);
                    card.setScaleY(1f);
                    card.setElevation(0);
                }
                bindCell(card, items.get(position));
                return card;
            }
        });

        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < items.size()) h.openDetails(items.get(position), view);
            }
        });

        // the selected card lifts while the grid holds the remote's focus
        final View[] lifted = {null};
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (lifted[0] != null && lifted[0] != view) lift(lifted[0], false, h);
                lifted[0] = view;
                if (view != null && grid.hasFocus()) lift(view, true, h);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {
                if (lifted[0] != null) lift(lifted[0], false, h);
                lifted[0] = null;
            }
        });
        grid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                if (lifted[0] != null) lift(lifted[0], has, h);
                else if (has) {
                    View selected = grid.getSelectedView();
                    lifted[0] = selected;
                    if (selected != null) lift(selected, true, h);
                }
            }
        });
        return grid;
    }
}
