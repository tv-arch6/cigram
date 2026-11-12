package com.Cigram.vid;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * «قائمة المشاهدة» (the page that used to be called «محتوياتي»), rebuilt.
 *
 *   قائمة المشاهدة                           large title, count of saved titles under it
 *   ─ أكمل المشاهدة ──────────────  [ المزيد · 14 ‹ ]
 *   ▢ ▢ ▢ ▢ …                                  at most {@link #ROW_LIMIT} posters per row
 *   ─ المفضلة ─────────────────────
 *   ─ المشاهدة لاحقاً · المسلسلات المشترك بها · الإعجابات · سجل المشاهدة
 *   [ AdMob banner ]                           pinned under the list
 *
 * A row never grows past ten posters: the rest is one tap away behind «المزيد», which opens the full list of that
 * row as a page ({@link SectionActivity}, the same page as «المزيد» on Home). Nothing is loaded from the network for
 * it; the lists are the ones the page already has.
 */
final class CigramWatchlist {

    static final int ROW_LIMIT = 10;
    private static final String TAG_HEAD = "cigram_watchlist_head_";
    private static final String TAG_HERO = "cigram_watchlist_hero";

    private CigramWatchlist() { }

    private static View byId(Activity a, String name) {
        int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
        return id == 0 ? null : a.findViewById(id);
    }

    static List<JSONObject> list(JSONArray array) {
        ArrayList<JSONObject> out = new ArrayList<JSONObject>();
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o != null) out.add(o);
        }
        return out;
    }

    /** Called after the page (and {@link CigramMyContent}) filled its rows; safe on every resume. */
    static void polish(final Activity a) {
        if (a == null || a.isFinishing()) return;
        try {
            final CigramPalette pal = CigramPalette.of(a);
            View root = byId(a, "linear_my_content_root");
            if (root != null) root.setBackgroundColor(pal.page);
            hero(a, pal);

            strip(a, pal, "continue", "textview_continue_title", "linear_continue_watching", "أكمل المشاهدة",
                    new Source() { @Override public List<JSONObject> all() { return list(CigramUserData.continueItems(a)); } });
            strip(a, pal, "favorites", "textview_favorites_title", "linear_favorites", "المفضلة",
                    new Source() { @Override public List<JSONObject> all() { return list(CigramStage1InitProvider.cigramListRead(a, "favorites")); } });
            strip(a, pal, "my_list", "textview_my_list_title", "linear_my_list", null, null);

            if (root instanceof LinearLayout) CigramBannerAd.installBottom(a, (LinearLayout) root);
        } catch (Throwable error) {
            android.util.Log.w("CigramWatchlist", "polish failed", error);
        }
    }

    interface Source { List<JSONObject> all(); }

    // ------------------------------------------------------------------ page header

    private static void hero(Activity a, CigramPalette pal) {
        View header = byId(a, "linear_my_content_header");
        View title = byId(a, "textview_my_content_title");
        if (header != null) header.setBackgroundColor(pal.page);
        if (title instanceof TextView) {
            TextView t = (TextView) title;
            t.setText("قائمة المشاهدة");
            t.setTextSize(26f);
            t.setTextColor(pal.text);
            Typeface bold = CigramTypefaces.bold();
            t.setTypeface(bold != null ? bold : Typeface.DEFAULT_BOLD, Typeface.BOLD);
            t.setIncludeFontPadding(false);
        }
        View body = byId(a, "linear_my_content_body");
        if (!(body instanceof LinearLayout)) return;
        LinearLayout host = (LinearLayout) body;
        int saved = 0;
        try {
            saved = CigramStage1InitProvider.cigramListRead(a, "favorites").length()
                    + CigramStage1InitProvider.cigramListRead(a, "watch_later").length();
        } catch (Throwable ignored) { }
        String line = saved == 0 ? "ما تحفظه وما تشاهده يجتمع هنا" : saved + " عنواناً محفوظاً في قائمتك";
        View old = host.findViewWithTag(TAG_HERO);
        if (old instanceof TextView) {
            ((TextView) old).setText(line);
            return;
        }
        TextView sub = CigramUI.label(a, line, 13f, pal.text3, false);
        sub.setTag(TAG_HERO);
        sub.setGravity(Gravity.RIGHT);
        sub.setPadding(CigramUI.dp(a, 20), 0, CigramUI.dp(a, 20), CigramUI.dp(a, 10));
        host.addView(sub, 0, new LinearLayout.LayoutParams(-1, -2));
    }

    // ------------------------------------------------------------------ one row of the page's own layout

    private static void strip(final Activity a, final CigramPalette pal, final String kind, String titleId, String stripId,
                              final String name, final Source source) {
        View titleView = byId(a, titleId);
        View stripView = byId(a, stripId);
        if (!(stripView instanceof LinearLayout)) return;
        final LinearLayout strip = (LinearLayout) stripView;
        if (titleView instanceof TextView) {
            TextView t = (TextView) titleView;
            t.setTextSize(18f);
            t.setTextColor(pal.text);
            Typeface bold = CigramTypefaces.bold();
            t.setTypeface(bold != null ? bold : Typeface.DEFAULT_BOLD, Typeface.BOLD);
        }
        cap(a, pal, kind, titleView, strip, name, source);
        // the page fills its rows after reading its stores: the limit is applied again whenever a poster is added
        if (strip.getTag(R.id.cg_row_subtitle) == null) {
            strip.setTag(R.id.cg_row_subtitle, Boolean.TRUE);
            final View tv = titleView;
            strip.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) {
                    strip.removeCallbacks(again);
                    strip.post(again);
                }
                @Override public void onChildViewRemoved(View parent, View child) { }
                final Runnable again = new Runnable() {
                    @Override public void run() { if (!a.isFinishing()) cap(a, pal, kind, tv, strip, name, source); }
                };
            });
        }
    }

    private static void cap(final Activity a, CigramPalette pal, String kind, View titleView, LinearLayout strip,
                            final String name, final Source source) {
        int total = strip.getChildCount();
        for (int i = 0; i < total; i++) {
            View child = strip.getChildAt(i);
            // rows are filled right → left or left → right depending on the page: the FIRST ten added stay
            child.setVisibility(i < ROW_LIMIT ? View.VISIBLE : View.GONE);
        }
        if (!(titleView instanceof TextView) || !(titleView.getParent() instanceof LinearLayout) || source == null) return;
        LinearLayout body = (LinearLayout) titleView.getParent();
        View more = body.findViewWithTag(TAG_HEAD + kind);
        boolean needed = total > ROW_LIMIT && titleView.getVisibility() == View.VISIBLE;
        if (!needed) {
            if (more != null) more.setVisibility(View.GONE);
            return;
        }
        if (more == null) {
            more = moreButton(a, pal, new View.OnClickListener() {
                @Override public void onClick(View v) { openAll(a, name, source.all()); }
            });
            more.setTag(TAG_HEAD + kind);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, CigramUI.dp(a, 34));
            lp.gravity = Gravity.LEFT;
            lp.setMargins(CigramUI.dp(a, 16), CigramUI.dp(a, 2), 0, CigramUI.dp(a, 6));
            body.addView(more, body.indexOfChild(titleView) + 1, lp);
        }
        more.setVisibility(View.VISIBLE);
        setCount(more, total);
    }

    // ------------------------------------------------------------------ «المزيد»

    /** White-on-black iOS pill: «المزيد · N ‹». */
    static View moreButton(Activity a, CigramPalette pal, View.OnClickListener click) {
        LinearLayout pill = new LinearLayout(a);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(CigramUI.dp(a, 10), 0, CigramUI.dp(a, 14), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(CigramUI.dp(a, 17));
        bg.setColor(pal.light ? 0xFF0B0B0C : 0xFFFFFFFF);
        pill.setBackground(bg);
        int ink = pal.light ? 0xFFFFFFFF : 0xFF000000;
        pill.addView(new AdsIcons.Icon(a, AdsIcons.CHEVRON_LEFT, ink), new LinearLayout.LayoutParams(CigramUI.dp(a, 15), CigramUI.dp(a, 15)));
        TextView t = CigramUI.label(a, "المزيد", 13f, ink, true);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = CigramUI.dp(a, 4);
        pill.addView(t, tl);
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setContentDescription("المزيد");
        pill.setOnClickListener(click);
        return pill;
    }

    static void setCount(View pill, int total) {
        if (!(pill instanceof LinearLayout)) return;
        View t = ((LinearLayout) pill).getChildAt(1);
        if (t instanceof TextView) ((TextView) t).setText("المزيد · " + total);
        pill.setContentDescription("المزيد، " + total + " عنواناً");
    }

    /** The whole list of one row, as a page. */
    static void openAll(Activity a, String title, List<JSONObject> items) {
        if (a == null || items == null || items.isEmpty()) return;
        try {
            String key = "watchlist:" + title;
            CigramSectionStore.put(key, new CigramSectionStore.Page("__related__", title, "", new ArrayList<JSONObject>(items), true));
            Intent i = new Intent(a, SectionActivity.class);
            i.putExtra(SectionActivity.EXTRA_KEY, key);
            i.putExtra("section_id", "__related__");
            i.putExtra("section_title", title);
            a.startActivity(i);
        } catch (Throwable error) {
            android.util.Log.w("CigramWatchlist", "more failed", error);
        }
    }

    /**
     * A section built by {@link CigramMyContent}: at most ten posters, and «المزيد» under its title when there are more.
     */
    static View section(final Activity a, final String title, final List<JSONObject> items) {
        if (items == null || items.isEmpty()) return null;
        List<JSONObject> shown = items.size() > ROW_LIMIT ? new ArrayList<JSONObject>(items.subList(0, ROW_LIMIT)) : items;
        View section = CigramStage1InitProvider.cigramSectionView(a, title, shown);
        if (section == null || items.size() <= ROW_LIMIT) return section;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(section, new LinearLayout.LayoutParams(-1, -2));
        View more = moreButton(a, CigramPalette.of(a), new View.OnClickListener() {
            @Override public void onClick(View v) { openAll(a, title, items); }
        });
        setCount(more, items.size());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, CigramUI.dp(a, 34));
        lp.gravity = Gravity.LEFT;
        lp.setMargins(CigramUI.dp(a, 16), CigramUI.dp(a, 4), 0, CigramUI.dp(a, 8));
        box.addView(more, lp);
        return box;
    }
}
