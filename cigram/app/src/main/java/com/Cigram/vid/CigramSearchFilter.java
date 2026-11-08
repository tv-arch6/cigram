package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * v10.68: the search filter, rebuilt as one clear bottom sheet (it was a list of four words that
 * each opened another list).
 *
 *   النوع        الكل · أفلام · مسلسلات
 *   التصنيف      دراما 120 · أكشن 98 · …            (from the catalogue, most used first)
 *   سنة الإصدار  الكل · 2026 · 2025 · … · 2010–2019 · 2000–2009 · قبل 2000
 *   تقييم IMDb   الكل · 5+ · 6+ · 7+ · 8+ · 9+
 *   ترتيب حسب    الأنسب · الأحدث · الأعلى تقييماً · الاسم
 *
 * Choices are a draft until «عرض النتائج (N)» is pressed; N is the live number of titles the
 * draft would show. «مسح الكل» resets everything. Values are stored in the same
 * «cigram_search_filters» preferences the search page already reads (year / category / imdb_min)
 * plus «sort». A year can now also be a decade («d:2010») or «lt:2000», read by {@link #yearMatches}.
 */
public final class CigramSearchFilter {

    public interface Host {
        /** Number of titles the search page would show with these filters (and its current text). */
        int count(String type, String year, String category, String rating);

        /** Filters were saved: switch the type tab and refresh the results. */
        void apply(String type);
    }

    private static final int CHIP = 0x1FFFFFFF;
    private static final int CHIP_ON = 0xFFFFFFFF;
    private static final int CHIP_TEXT = 0xFFE6ECEF;
    private static final int HEAD = 0xFF9FB0B8;

    private CigramSearchFilter() { }

    // ------------------------------------------------------------------ matching

    /** «all», an exact year, «d:2010» (2010-2019) or «lt:2000» (before 2000). */
    public static boolean yearMatches(String filter, String year) {
        if (filter == null || filter.length() == 0 || "all".equals(filter)) return true;
        int y = parseYear(year);
        if (filter.startsWith("d:")) {
            int d = parseYear(filter.substring(2));
            return y > 0 && d > 0 && y >= d && y <= d + 9;
        }
        if (filter.startsWith("lt:")) {
            int d = parseYear(filter.substring(3));
            return y > 0 && d > 0 && y < d;
        }
        return filter.equals(year == null ? "" : year.trim());
    }

    private static int parseYear(String s) {
        if (s == null) return 0;
        String t = s.trim();
        if (t.length() > 4) t = t.substring(0, 4);
        try {
            return Integer.parseInt(t);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Sorts the hits in place by the saved «sort» (relevance keeps the given order). */
    public static void sort(ArrayList<JSONObject> hits, SharedPreferences prefs) {
        String sort = prefs == null ? "relevance" : prefs.getString("sort", "relevance");
        if ("newest".equals(sort)) {
            Collections.sort(hits, new Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) {
                    return parseYear(text(b, "year")) - parseYear(text(a, "year"));
                }
            });
        } else if ("rating".equals(sort)) {
            Collections.sort(hits, new Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) {
                    return Float.compare(number(text(b, "imdb")), number(text(a, "imdb")));
                }
            });
        } else if ("name".equals(sort)) {
            Collections.sort(hits, new Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) {
                    return title(a).compareToIgnoreCase(title(b));
                }
            });
        }
    }

    public static boolean active(SharedPreferences p) {
        return !p.getString("year", "all").equals("all")
                || !p.getString("category", "all").equals("all")
                || !p.getString("imdb_min", "0").equals("0")
                || !p.getString("sort", "relevance").equals("relevance");
    }

    // ------------------------------------------------------------------ sheet

    public static void show(final Activity a, JSONArray items, final SharedPreferences prefs,
                            String type, final Host host) {
        if (a == null || a.isFinishing() || prefs == null || host == null) return;
        final String[] draft = {
                type == null ? "all" : type,
                prefs.getString("category", "all"),
                prefs.getString("year", "all"),
                prefs.getString("imdb_min", "0"),
                prefs.getString("sort", "relevance")
        };

        final CigramUI.Sheet sheet = CigramUI.sheet(a).title("تصفية النتائج");
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        final ArrayList<Runnable> repaint = new ArrayList<Runnable>();
        final TextView[] applyButton = {null};
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                for (Runnable r : repaint) r.run();
                if (applyButton[0] != null) {
                    int n = host.count(draft[0], draft[2], draft[1], draft[3]);
                    applyButton[0].setText(n == 0 ? "لا توجد نتائج بهذه الفلاتر" : "عرض النتائج (" + n + ")");
                    applyButton[0].setAlpha(n == 0 ? 0.55f : 1f);
                }
            }
        };

        // النوع
        group(a, body, "النوع", new String[]{"all", "movie", "series"},
                new String[]{"الكل", "أفلام", "مسلسلات"}, draft, 0, false, repaint, refresh);

        // التصنيف: from the catalogue, the most used first, with the number of titles
        final HashMap<String, Integer> cats = new HashMap<String, Integer>();
        TreeSet<String> years = new TreeSet<String>(Collections.<String>reverseOrder());
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            for (String part : text(it, "category").split("[,،/|]")) {
                String c = part.trim();
                if (c.length() == 0) continue;
                Integer n = cats.get(c);
                cats.put(c, n == null ? 1 : n + 1);
            }
            int y = parseYear(text(it, "year"));
            if (y > 1900) years.add(String.valueOf(y));
        }
        ArrayList<Map.Entry<String, Integer>> catList = new ArrayList<Map.Entry<String, Integer>>(cats.entrySet());
        Collections.sort(catList, new Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> x, Map.Entry<String, Integer> y) {
                return y.getValue() - x.getValue();
            }
        });
        String[] catValues = new String[catList.size() + 1];
        String[] catLabels = new String[catList.size() + 1];
        catValues[0] = "all";
        catLabels[0] = "الكل";
        for (int i = 0; i < catList.size(); i++) {
            catValues[i + 1] = catList.get(i).getKey();
            catLabels[i + 1] = catList.get(i).getKey() + "  " + catList.get(i).getValue();
        }
        group(a, body, "التصنيف", catValues, catLabels, draft, 1, true, repaint, refresh);

        // سنة الإصدار: the six newest years, then decades
        ArrayList<String> yv = new ArrayList<String>();
        ArrayList<String> yl = new ArrayList<String>();
        yv.add("all");
        yl.add("الكل");
        int shown = 0;
        int newest = 0;
        for (String y : years) {
            if (newest == 0) newest = parseYear(y);
            if (shown++ >= 6) break;
            yv.add(y);
            yl.add(y);
        }
        if (newest == 0) newest = Calendar.getInstance().get(Calendar.YEAR);
        int oldestSingle = newest - Math.min(6, years.size()) + 1;
        for (int d = (oldestSingle - 1) / 10 * 10; d >= 2000; d -= 10) {
            yv.add("d:" + d);
            yl.add(d + "–" + (d + 9));
        }
        yv.add("lt:2000");
        yl.add("قبل 2000");
        group(a, body, "سنة الإصدار", yv.toArray(new String[0]), yl.toArray(new String[0]), draft, 2, true,
                repaint, refresh);

        // تقييم IMDb
        group(a, body, "أقل تقييم IMDb", new String[]{"0", "5", "6", "7", "8", "9"},
                new String[]{"الكل", "5+", "6+", "7+", "8+", "9+"}, draft, 3, false, repaint, refresh);

        // ترتيب حسب
        group(a, body, "ترتيب النتائج", new String[]{"relevance", "newest", "rating", "name"},
                new String[]{"الأنسب", "الأحدث", "الأعلى تقييماً", "الاسم"}, draft, 4, true, repaint, refresh);

        sheet.view(body);
        applyButton[0] = sheet.button("عرض النتائج", 0, CigramUI.RED, new CigramUI.Click() {
            @Override public boolean onClick(CigramUI.Sheet s) {
                prefs.edit().putString("category", draft[1]).putString("year", draft[2])
                        .putString("imdb_min", draft[3]).putString("sort", draft[4]).apply();
                host.apply(draft[0]);
                return false;
            }
        });
        sheet.secondary("مسح كل الفلاتر", new CigramUI.Click() {
            @Override public boolean onClick(CigramUI.Sheet s) {
                draft[0] = "all";
                draft[1] = "all";
                draft[2] = "all";
                draft[3] = "0";
                draft[4] = "relevance";
                refresh.run();
                return true;   // the sheet stays open so the reset is visible
            }
        });
        refresh.run();
        sheet.show();
    }

    /** A titled row of chips; one choice at a time. scroll = one horizontal line, else wrapped. */
    private static void group(final Activity a, LinearLayout body, String title, final String[] values,
                              String[] labels, final String[] draft, final int slot, boolean scroll,
                              ArrayList<Runnable> repaint, final Runnable refresh) {
        TextView h = CigramUI.label(a, title, 13.5f, HEAD, true);
        h.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = CigramUI.dp(a, body.getChildCount() == 0 ? 4 : 16);
        hl.bottomMargin = CigramUI.dp(a, 8);
        body.addView(h, hl);

        final TextView[] chips = new TextView[values.length];
        ViewGroup holder;
        if (scroll) {
            // same RTL rail as the rest of the app: physical LTR row, first chip on the far right,
            // opened scrolled to the right end
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            row.setGravity(Gravity.RIGHT);
            final HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
            hs.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            hs.setFillViewport(true);
            hs.addView(row, new ViewGroup.LayoutParams(-2, -2));
            body.addView(hs, new LinearLayout.LayoutParams(-1, -2));
            hs.post(new Runnable() {
                @Override public void run() { hs.fullScroll(View.FOCUS_RIGHT); }
            });
            holder = row;
        } else {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            body.addView(row, new LinearLayout.LayoutParams(-1, -2));
            holder = row;
        }
        for (int i = 0; i < values.length; i++) {
            final int which = i;
            TextView c = chip(a, labels[i]);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    draft[slot] = values[which];
                    refresh.run();
                }
            });
            chips[i] = c;
            LinearLayout.LayoutParams lp = scroll
                    ? new LinearLayout.LayoutParams(-2, CigramUI.dp(a, 38))
                    : new LinearLayout.LayoutParams(0, CigramUI.dp(a, 38), 1f);
            if (scroll) {
                if (i > 0) lp.rightMargin = CigramUI.dp(a, 8);
                holder.addView(c, 0, lp);
            } else {
                if (i > 0) lp.setMarginStart(CigramUI.dp(a, 8));
                holder.addView(c, lp);
            }
        }
        repaint.add(new Runnable() {
            @Override public void run() {
                boolean any = false;
                for (int i = 0; i < values.length; i++) {
                    boolean on = values[i].equals(draft[slot]);
                    any |= on;
                    paint(a, chips[i], on);
                }
                if (!any) paint(a, chips[0], true);   // a saved value that no longer exists
            }
        });
    }

    private static TextView chip(Context a, String text) {
        TextView c = new TextView(a);
        c.setText(text);
        c.setTextSize(13.5f);
        c.setSingleLine(true);
        c.setGravity(Gravity.CENTER);
        c.setPadding(CigramUI.dp(a, 14), 0, CigramUI.dp(a, 14), 0);
        c.setClickable(true);
        c.setFocusable(true);
        return c;
    }

    private static void paint(Context a, TextView c, boolean on) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(on ? CHIP_ON : CHIP);
        d.setCornerRadius(CigramUI.dp(a, 19));
        c.setBackground(d);
        c.setTextColor(on ? 0xFF111113 : CHIP_TEXT);
        c.setTypeface(CigramUI.font(a), on ? Typeface.BOLD : Typeface.NORMAL);
    }

    // ------------------------------------------------------------------ summary bar

    /**
     * A line over the results that says which filters are on, with «مسح» to remove them all.
     * Returns null when nothing is filtered.
     */
    public static View summaryView(final Activity a, final SharedPreferences prefs, final Runnable cleared) {
        if (a == null || prefs == null || !active(prefs)) return null;
        ArrayList<String> parts = new ArrayList<String>();
        String cat = prefs.getString("category", "all");
        if (!"all".equals(cat)) parts.add(cat);
        String year = prefs.getString("year", "all");
        if (year.startsWith("d:")) {
            int d = parseYear(year.substring(2));
            parts.add(d + "–" + (d + 9));
        } else if (year.startsWith("lt:")) {
            parts.add("قبل " + year.substring(3));
        } else if (!"all".equals(year)) {
            parts.add(year);
        }
        String rating = prefs.getString("imdb_min", "0");
        if (!"0".equals(rating)) parts.add("IMDb " + rating + "+");
        String sort = prefs.getString("sort", "relevance");
        if ("newest".equals(sort)) parts.add("الأحدث أولاً");
        else if ("rating".equals(sort)) parts.add("الأعلى تقييماً");
        else if ("name".equals(sort)) parts.add("حسب الاسم");

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);   // physical: [مسح] ...... [الفلاتر: …]
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(CigramUI.dp(a, 12), 0, CigramUI.dp(a, 14), 0);
        row.setBackground(CigramUI.round(0x14FFFFFF, 0x1FFFFFFF, 14, a));

        TextView clear = CigramUI.label(a, "مسح", 13.5f, 0xFFFF6B63, true);
        clear.setPadding(CigramUI.dp(a, 6), CigramUI.dp(a, 6), CigramUI.dp(a, 6), CigramUI.dp(a, 6));
        clear.setClickable(true);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.edit().remove("category").remove("year").remove("imdb_min").remove("sort").apply();
                if (cleared != null) cleared.run();
            }
        });
        row.addView(clear, new LinearLayout.LayoutParams(-2, -2));

        StringBuilder sb = new StringBuilder("الفلاتر: ");
        for (int i = 0; i < parts.size(); i++) sb.append(i == 0 ? "" : " · ").append(parts.get(i));
        TextView t = CigramUI.label(a, sb.toString(), 13.5f, CigramUI.TEXT, false);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = CigramUI.dp(a, 8);
        row.addView(t, tl);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, CigramUI.dp(a, 44));
        lp.bottomMargin = CigramUI.dp(a, 10);
        row.setLayoutParams(lp);
        return row;
    }

    // ------------------------------------------------------------------ json

    private static String text(JSONObject o, String key) {
        if (o == null) return "";
        Object v = o.opt(key);
        return v == null || v == JSONObject.NULL ? "" : String.valueOf(v).trim();
    }

    private static String title(JSONObject o) {
        String t = text(o, "title");
        return t.length() > 0 ? t : text(o, "series_title");
    }

    private static float number(String s) {
        try {
            return Float.parseFloat(s.replace(',', '.'));
        } catch (Throwable e) {
            return 0f;
        }
    }
}
