package com.Cigram.vid;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * اكتشف: filter the catalog that is already in memory. No network.
 * Filters are combined (AND): type + genre + year + IMDb + sort.
 * Opened from the search page (idle state); it is not part of the bottom navigation.
 */
public class CigramDiscoverActivity extends Activity {

    private static final int PAGE = 30;
    private static final int COLUMNS = 3;
    private static final int BG = 0xFF05141F;
    private static final int CHIP_OFF = 0xFF0B2733;
    private static final int CHIP_ON = 0xFFE21B14;

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout filters;
    private LinearLayout results;
    private TextView countView;
    private int retries = 0;

    // filter state
    private String type = "all";          // all | movie | series
    private String genre = "";            // normalized genre token, "" = all
    private int yearMode = 0;             // 0 all, 1 current year, 2 earlier years
    private double minRating = 0d;
    private boolean sortByRating = false;
    private int shown = PAGE;

    private ArrayList<JSONObject> universe = new ArrayList<JSONObject>();
    private final LinkedHashMapLike genres = new LinkedHashMapLike();

    /** Tiny ordered label map (normalized -> display label). */
    private static final class LinkedHashMapLike {
        final ArrayList<String> keys = new ArrayList<String>();
        final HashMap<String, String> labels = new HashMap<String, String>();
        final HashMap<String, Integer> counts = new HashMap<String, Integer>();
    }

    /** Row shown on the search page. */
    public static View entryView(final Activity activity) {
        if (activity == null) return null;
        TextView row = new TextView(activity);
        row.setText("اكتشف  ›  تصفح بالنوع والتصنيف والسنة والتقييم");
        row.setTextColor(Color.WHITE);
        row.setTextSize(14f);
        row.setTypeface(CigramStage1InitProvider.cigramFont(activity), Typeface.BOLD);
        row.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        int pad = CigramStage1InitProvider.cigramDp(activity, 16);
        row.setPadding(pad, pad, pad, pad);
        row.setBackground(CigramStage1InitProvider.cigramRounded(0xFF0B2733,
                CigramStage1InitProvider.cigramDp(activity, 14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, CigramStage1InitProvider.cigramDp(activity, 12));
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    activity.startActivity(new Intent(activity, CigramDiscoverActivity.class));
                } catch (Throwable ignored) { }
            }
        });
        return row;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(BG);
        } catch (Throwable ignored) { }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setFitsSystemWindows(true);

        // header: title on the right, back on the left side of the screen
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(16);
        header.setPadding(p, dp(8), p, dp(8));
        TextView title = label("اكتشف", 24f, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView back = label("رجوع", 14f, 0xFF8B9AA3, true);
        back.setPadding(dp(10), dp(8), dp(10), dp(8));
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                finish();
            }
        });
        header.addView(back, new LinearLayout.LayoutParams(-2, -2));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        body.setPadding(dp(8), 0, dp(8), dp(24));

        filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.VERTICAL);
        filters.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        body.addView(filters, new LinearLayout.LayoutParams(-1, -2));

        countView = label("", 13f, 0xFF8B9AA3, false);
        countView.setPadding(dp(8), dp(10), dp(8), dp(6));
        body.addView(countView, new LinearLayout.LayoutParams(-1, -2));

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        body.addView(results, new LinearLayout.LayoutParams(-1, -2));

        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        load();
    }

    // ------------------------------------------------------------------ data

    private void load() {
        ArrayList<JSONObject> all = CigramContent.catalog();
        if (all.isEmpty()) {
            countView.setText("جارٍ تحميل الكتالوج…");
            if (retries++ < 8) {
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!isFinishing()) load();
                    }
                }, 1200L);
            } else {
                countView.setText("تعذر تحميل المحتوى، افتح الرئيسية ثم عد إلى اكتشف");
            }
            return;
        }
        HashSet<String> seen = new HashSet<String>();
        universe = new ArrayList<JSONObject>();
        genres.keys.clear();
        genres.labels.clear();
        genres.counts.clear();
        for (int i = 0; i < all.size(); i++) {
            JSONObject item = all.get(i);
            String key = CigramContent.itemKey(item);
            if (key.length() == 0 || !seen.add(key)) continue;
            universe.add(item);
            List<String> norm = CigramContent.genres(item);
            List<String> labels = CigramContent.genreLabels(item);
            for (int g = 0; g < norm.size(); g++) {
                String n = norm.get(g);
                Integer c = genres.counts.get(n);
                genres.counts.put(n, Integer.valueOf(c == null ? 1 : c.intValue() + 1));
                if (!genres.labels.containsKey(n)) {
                    genres.labels.put(n, g < labels.size() ? labels.get(g) : n);
                    genres.keys.add(n);
                }
            }
        }
        Collections.sort(genres.keys, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                return genres.counts.get(b).intValue() - genres.counts.get(a).intValue();
            }
        });
        buildFilters();
        render();
    }

    // --------------------------------------------------------------- filters

    private void buildFilters() {
        filters.removeAllViews();
        addRow(new String[]{"الكل", "أفلام", "مسلسلات"},
                type.equals("all") ? 0 : (type.equals("movie") ? 1 : 2),
                new Choice() {
                    @Override public void pick(int index) {
                        type = index == 0 ? "all" : (index == 1 ? "movie" : "series");
                        changed();
                    }
                });

        int max = Math.min(genres.keys.size(), 24);
        String[] gl = new String[max + 1];
        gl[0] = "كل التصنيفات";
        int selectedGenre = 0;
        for (int i = 0; i < max; i++) {
            gl[i + 1] = genres.labels.get(genres.keys.get(i));
            if (genres.keys.get(i).equals(genre)) selectedGenre = i + 1;
        }
        if (max > 0) {
            addRow(gl, selectedGenre, new Choice() {
                @Override public void pick(int index) {
                    genre = index == 0 ? "" : genres.keys.get(index - 1);
                    changed();
                }
            });
        }

        addRow(new String[]{"كل السنوات", "السنة الحالية", "سنوات سابقة"}, yearMode, new Choice() {
            @Override public void pick(int index) {
                yearMode = index;
                changed();
            }
        });

        double[] steps = new double[]{0d, 5d, 6d, 7d, 8d};
        int selRating = 0;
        for (int i = 0; i < steps.length; i++) if (steps[i] == minRating) selRating = i;
        addRow(new String[]{"كل التقييمات", "IMDb 5+", "IMDb 6+", "IMDb 7+", "IMDb 8+"}, selRating,
                new Choice() {
                    @Override public void pick(int index) {
                        minRating = new double[]{0d, 5d, 6d, 7d, 8d}[index];
                        changed();
                    }
                });

        addRow(new String[]{"الأحدث", "الأعلى تقييماً"}, sortByRating ? 1 : 0, new Choice() {
            @Override public void pick(int index) {
                sortByRating = index == 1;
                changed();
            }
        });
    }

    private interface Choice {
        void pick(int index);
    }

    private void addRow(final String[] labels, int selected, final Choice choice) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setGravity(Gravity.RIGHT);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView chip = label(labels[i], 13f, Color.WHITE, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            chip.setBackground(CigramStage1InitProvider.cigramRounded(
                    i == selected ? CHIP_ON : CHIP_OFF, dp(18)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            chip.setClickable(true);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    for (int n = 0; n < row.getChildCount(); n++) {
                        row.getChildAt(n).setBackground(CigramStage1InitProvider.cigramRounded(
                                n == index ? CHIP_ON : CHIP_OFF, dp(18)));
                    }
                    choice.pick(index);
                }
            });
            row.addView(chip, lp);
        }
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
        filters.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    }

    private void changed() {
        shown = PAGE;
        render();
    }

    // --------------------------------------------------------------- results

    private ArrayList<JSONObject> filtered() {
        int thisYear = Calendar.getInstance().get(Calendar.YEAR);
        ArrayList<JSONObject> out = new ArrayList<JSONObject>();
        for (int i = 0; i < universe.size(); i++) {
            JSONObject item = universe.get(i);
            if (!"all".equals(type) && !type.equals(CigramContent.typeOf(item))) continue;
            if (genre.length() > 0 && !CigramContent.genres(item).contains(genre)) continue;
            int year = CigramContent.year(item);
            if (yearMode == 1 && year != thisYear) continue;
            if (yearMode == 2 && (year <= 0 || year >= thisYear)) continue;
            if (minRating > 0d && CigramContent.rating(item) < minRating) continue;
            out.add(item);
        }
        final boolean byRating = sortByRating;
        final HashMap<String, long[]> recency = new HashMap<String, long[]>();
        for (int i = 0; i < out.size(); i++) {
            JSONObject it = out.get(i);
            long added = CigramContent.addedAtMs(it);
            long id = 0L;
            try { id = Long.parseLong(CigramContent.text(it, "id").replaceAll("[^0-9]", "")); }
            catch (Throwable ignored) { }
            recency.put(CigramContent.itemKey(it), new long[]{added, CigramContent.year(it), id});
        }
        Collections.sort(out, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                if (byRating) {
                    int c = Double.compare(CigramContent.rating(b), CigramContent.rating(a));
                    if (c != 0) return c;
                }
                long[] x = recency.get(CigramContent.itemKey(a));
                long[] y = recency.get(CigramContent.itemKey(b));
                for (int k = 0; k < 3; k++) {
                    if (x[k] != y[k]) return x[k] < y[k] ? 1 : -1;      // newest first
                }
                return 0;
            }
        });
        return out;
    }

    private void render() {
        results.removeAllViews();
        final ArrayList<JSONObject> list = filtered();
        countView.setText(list.size() + " نتيجة");
        if (list.isEmpty()) {
            TextView empty = label("لا توجد نتائج مطابقة للفلاتر المختارة", 15f, 0xFFD7E1E6, true);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(40), dp(16), dp(12));
            results.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            TextView reset = label("مسح الفلاتر", 14f, Color.WHITE, true);
            reset.setGravity(Gravity.CENTER);
            reset.setBackground(CigramStage1InitProvider.cigramRounded(CHIP_ON, dp(22)));
            reset.setClickable(true);
            reset.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    type = "all";
                    genre = "";
                    yearMode = 0;
                    minRating = 0d;
                    sortByRating = false;
                    shown = PAGE;
                    buildFilters();
                    render();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), dp(42));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            results.addView(reset, lp);
            return;
        }
        int end = Math.min(shown, list.size());
        for (int start = 0; start < end; start += COLUMNS) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);   // first (newest) item on the right
            for (int c = 0; c < COLUMNS; c++) {
                int index = start + c;
                View cell = null;
                if (index < end) {
                    try { cell = CigramStage1InitProvider.createSectionMovieCard(this, list.get(index)); }
                    catch (Throwable ignored) { }
                }
                if (cell == null) {
                    cell = new View(this);
                    cell.setVisibility(View.INVISIBLE);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.setMargins(dp(4), dp(4), dp(4), dp(10));
                row.addView(cell, lp);
            }
            results.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        if (end < list.size()) {
            TextView more = label("عرض المزيد", 14f, Color.WHITE, true);
            more.setGravity(Gravity.CENTER);
            more.setBackground(CigramStage1InitProvider.cigramRounded(CHIP_OFF, dp(22)));
            more.setClickable(true);
            more.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    shown += PAGE;
                    render();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(150), dp(42));
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp(8);
            results.addView(more, lp);
        }
    }

    // ---------------------------------------------------------------- helpers

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setTypeface(CigramStage1InitProvider.cigramFont(this), bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private int dp(float v) {
        return CigramStage1InitProvider.cigramDp(this, v);
    }
}
