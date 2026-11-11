package com.Cigram.vid;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CIGRAM TV EDITION — the Home panel: a cinematic hero banner over the featured title, then the
 * content rows (newest, movies, series, and the catalogue's own categories). Built from the SAME
 * catalogue data the phone uses; empty rows are never shown.
 */
final class CigramTvHome {

    private CigramTvHome() { }

    static View build(final CigramTvActivity a) {
        ScrollView scroll = a.verticalScroll();
        scroll.setPadding(0, 0, 0, a.dp(28));   // hero is edge-to-edge; rows keep their own insets

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);

        List<JSONObject> all = a.catalog();
        List<JSONObject> movies = new ArrayList<JSONObject>();
        List<JSONObject> series = new ArrayList<JSONObject>();
        for (JSONObject o : all) {
            if (CigramTvCards.isSeries(o)) series.add(o); else movies.add(o);
        }

        // ---- 19.2 Cinema Spotlight (TV only): up to six titles in turn on full-bleed backdrops ----
        List<JSONObject> spotlight = CigramTvSpotlight.pick(all, 6);
        if (!spotlight.isEmpty()) col.addView(CigramTvSpotlight.build(a, spotlight));
        else {
            JSONObject featured = pickFeatured(all);
            if (featured != null) col.addView(hero(a, featured), new LinearLayout.LayoutParams(-1, -2));
        }

        // ---- rows (inset) ----
        LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(a.dp(40), a.dp(14), a.dp(28), 0);   // safe-area insets for the rows

        final ArrayList<Runnable> jobs = new ArrayList<Runnable>();
        later(jobs, a, rows, "أضيف حديثاً", take(all, 18));
        later(jobs, a, rows, "أحدث الأفلام", take(movies, 18));
        later(jobs, a, rows, "أحدث المسلسلات", take(series, 18));

        // the catalogue's own categories, biggest first (up to 6 rows)
        Map<String, List<JSONObject>> byCategory = groupByCategory(all);
        int added = 0;
        for (Map.Entry<String, List<JSONObject>> e : byCategory.entrySet()) {
            if (added >= 6) break;
            if (e.getValue().size() < 4) continue;
            later(jobs, a, rows, e.getKey(), take(e.getValue(), 18));
            added++;
        }

        col.addView(rows, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(col, new FrameLayout.LayoutParams(-1, -2));
        runRows(a, scroll, jobs);
        return scroll;
    }

    private static void later(ArrayList<Runnable> jobs, final CigramTvActivity a, final LinearLayout parent,
                              final String title, final List<JSONObject> items) {
        jobs.add(new Runnable() { @Override public void run() { addRow(a, parent, title, items); } });
    }

    /**
     * The home used to build every row (about 200 cards) in one frame. Now the first two rows appear at
     * once and the others follow one per ~30 ms, so the page is on screen and answers the remote at
     * once instead of freezing while it builds.
     */
    private static void runRows(final CigramTvActivity a, final View anchor, final ArrayList<Runnable> jobs) {
        final int[] next = {0};
        while (next[0] < 2 && next[0] < jobs.size()) jobs.get(next[0]++).run();
        if (next[0] >= jobs.size()) return;
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (a.isFinishing() || !anchor.isAttachedToWindow()) return;   // the panel was replaced: stop building it
                if (next[0] >= jobs.size()) return;
                jobs.get(next[0]++).run();
                if (next[0] < jobs.size()) handler.postDelayed(this, 30L);
            }
        }, 30L);
    }

    private static void addRow(CigramTvActivity a, LinearLayout parent, String title, List<JSONObject> items) {
        if (items == null || items.isEmpty()) return;   // never show an empty section
        parent.addView(CigramTvCards.row(a, title, items), new LinearLayout.LayoutParams(-1, -2));
    }

    // ------------------------------------------------------------------ hero banner

    private static View hero(final CigramTvActivity a, final JSONObject item) {
        FrameLayout hero = new FrameLayout(a);
        int height = Math.min(a.dp(380), (int) (a.getResources().getDisplayMetrics().heightPixels * 0.62f));

        ImageView backdrop = new ImageView(a);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(backdrop, new FrameLayout.LayoutParams(-1, height));
        CigramTvImage.load(backdrop,
                CigramTvCards.str(item, "backdrop", "landscape_poster", "source_backdrop", "image", "poster"), 1080);

        // left-to-dark + bottom-to-dark gradients for text legibility
        View shade = new View(a);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT,
                new int[]{0x00000000, 0xAA0E0E10, 0xF20E0E10});
        shade.setBackground(g);
        hero.addView(shade, new FrameLayout.LayoutParams(-1, height));
        View bottom = new View(a);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x000E0E10, 0xF20E0E10}));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, a.dp(120), Gravity.BOTTOM);
        hero.addView(bottom, blp);

        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.RIGHT | Gravity.BOTTOM);
        info.setPadding(a.dp(40), a.dp(40), a.dp(44), a.dp(30));

        TextView name = new TextView(a);
        name.setText(CigramTvCards.str(item, "title", "name", "series_title"));
        name.setTextColor(CigramDesign.TEXT);
        name.setTextSize(30f);
        name.setTypeface(a.font(), Typeface.BOLD);
        name.setGravity(Gravity.RIGHT);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        int infoWidth = Math.min(a.dp(640), (int) (a.getResources().getDisplayMetrics().widthPixels * 0.56f));
        info.addView(name, new LinearLayout.LayoutParams(infoWidth, -2));

        String year = CigramTvCards.str(item, "year", "release_year");
        String imdb = CigramTvCards.str(item, "imdb", "rating", "vote_average");
        String cat = CigramTvCards.str(item, "category", "genre", "genres");
        StringBuilder meta = new StringBuilder();
        if (year.length() > 0) meta.append(year);
        if (imdb.length() > 0) meta.append(meta.length() > 0 ? "   ·   " : "").append("IMDb ").append(imdb);
        if (cat.length() > 0) meta.append(meta.length() > 0 ? "   ·   " : "").append(cat);
        if (meta.length() > 0) {
            TextView m = new TextView(a);
            m.setText(meta.toString());
            m.setTextColor(CigramDesign.TEXT_2);
            m.setTextSize(15f);
            m.setTypeface(a.font(), Typeface.BOLD);
            m.setGravity(Gravity.RIGHT);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-2, -2);
            mlp.topMargin = a.dp(8);
            info.addView(m, mlp);
        }

        String desc = CigramTvCards.str(item, "description", "overview", "story", "plot");
        if (desc.length() > 0) {
            TextView d = new TextView(a);
            d.setText(desc);
            d.setTextColor(CigramDesign.TEXT_2);
            d.setTextSize(14f);
            d.setTypeface(a.font());
            d.setMaxLines(2);
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            d.setGravity(Gravity.RIGHT);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Math.min(a.dp(560),
                    (int) (a.getResources().getDisplayMetrics().widthPixels * 0.5f)), -2);
            dlp.topMargin = a.dp(10);
            info.addView(d, dlp);
        }

        // buttons (RTL): شاهد الآن · التفاصيل · + قائمتي — all open the shared details page where
        // play / add-to-list / info live (reusing the existing systems instead of duplicating them).
        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        buttons.setGravity(Gravity.RIGHT);
        buttons.addView(heroButton(a, item, "قائمتي", false), buttonLp(a));
        buttons.addView(heroButton(a, item, "التفاصيل", false), buttonLp(a));
        View watch = heroButton(a, item, "شاهد الآن", true);
        buttons.addView(watch, buttonLp(a));
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(-2, -2);
        blp2.topMargin = a.dp(18);
        info.addView(buttons, blp2);

        hero.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        hero.post(new Runnable() { @Override public void run() { watchFocus(hero); } });
        return hero;
    }

    private static void watchFocus(View hero) {
        try {
            View v = hero.findViewWithTag("cigram_tv_hero_primary");
            if (v != null) v.requestFocus();
        } catch (Throwable ignored) { }
    }

    private static LinearLayout.LayoutParams buttonLp(CigramTvActivity a) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = a.dp(10);
        return lp;
    }

    private static View heroButton(final CigramTvActivity a, final JSONObject item, String text, boolean primary) {
        final TextView b = new TextView(a);
        b.setText(text);
        b.setTextColor(primary ? 0xFF0E0E10 : CigramDesign.TEXT);
        b.setTextSize(16f);
        b.setTypeface(a.font(), Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(a.dp(26), a.dp(13), a.dp(26), a.dp(13));
        b.setBackground(primary ? CigramDesign.primaryPill(a, 26) : CigramDesign.ghostPill(a, 26));
        if (primary) b.setTag("cigram_tv_hero_primary");
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(120L).start();
            }
        });
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { a.openDetails(item, v); }
        });
        return b;
    }

    // ------------------------------------------------------------------ data helpers

    private static JSONObject pickFeatured(List<JSONObject> all) {
        for (JSONObject o : all) {
            if (CigramTvCards.str(o, "backdrop", "landscape_poster", "source_backdrop").length() > 0) return o;
        }
        return all.isEmpty() ? null : all.get(0);
    }

    private static List<JSONObject> take(List<JSONObject> src, int n) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (int i = 0; i < src.size() && i < n; i++) out.add(src.get(i));
        return out;
    }

    private static Map<String, List<JSONObject>> groupByCategory(List<JSONObject> all) {
        // sort categories by how many titles they hold (biggest rows first)
        Map<String, List<JSONObject>> map = new LinkedHashMap<String, List<JSONObject>>();
        for (JSONObject o : all) {
            String cat = CigramTvCards.str(o, "category", "genre", "genres");
            if (cat.length() == 0) continue;
            // the field may be "أكشن، دراما" — take the first genre as the row name
            String first = cat.split("[,،]")[0].trim();
            if (first.length() == 0) continue;
            List<JSONObject> list = map.get(first);
            if (list == null) { list = new ArrayList<JSONObject>(); map.put(first, list); }
            list.add(o);
        }
        List<Map.Entry<String, List<JSONObject>>> entries =
                new ArrayList<Map.Entry<String, List<JSONObject>>>(map.entrySet());
        java.util.Collections.sort(entries, new java.util.Comparator<Map.Entry<String, List<JSONObject>>>() {
            @Override public int compare(Map.Entry<String, List<JSONObject>> x, Map.Entry<String, List<JSONObject>> y) {
                return y.getValue().size() - x.getValue().size();
            }
        });
        Map<String, List<JSONObject>> sorted = new LinkedHashMap<String, List<JSONObject>>();
        for (Map.Entry<String, List<JSONObject>> e : entries) sorted.put(e.getKey(), e.getValue());
        return sorted;
    }
}
