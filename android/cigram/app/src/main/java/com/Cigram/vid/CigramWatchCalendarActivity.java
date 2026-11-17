package com.Cigram.vid;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * تقويم المشاهدة — Cigram Watch Calendar. A month at a glance for the profile that is watching: what was watched each
 * day (red), new episodes of the series it follows (gold), and what the library added (blue); a day opens its list,
 * a streak counts the days in a row. Data: the watching history and the saved catalogue ({@link CigramWatchCalendar}).
 */
public class CigramWatchCalendarActivity extends Activity {

    private static final int BG = 0xFF0B0B0D, CARD = 0xFF141417, LINE = 0x1FFFFFFF, TEXT = 0xFFFFFFFF, MUTED = 0x99FFFFFF, FAINT = 0x59FFFFFF;
    private static final int RED = 0xFFE21B14, GOLD = 0xFFF2C14E, SKY = 0xFF5CC8F0;

    private final TimeZone tz = TimeZone.getDefault();
    private int year, month, selected;
    private List<JSONObject> history = new ArrayList<JSONObject>(), catalogue = new ArrayList<JSONObject>();
    private Map<String, String> seriesTitles = new java.util.HashMap<String, String>();
    private CigramWatchCalendar.Month m;

    private TextView title, stats;
    private LinearLayout grid, dayList;
    private Typeface font;
    private float dp;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        try { font = CigramTypefaces.app(getAssets()); } catch (Throwable t) { font = Typeface.DEFAULT; }
        try {
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(BG);
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BG));
        } catch (Throwable ignored) { }
        Calendar now = Calendar.getInstance(tz);
        year = now.get(Calendar.YEAR);
        month = now.get(Calendar.MONTH);
        selected = now.get(Calendar.DAY_OF_MONTH);
        build();
        load();
    }

    private int px(float v) { return (int) (v * dp + 0.5f); }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(font, bold ? Typeface.BOLD : Typeface.NORMAL);
        return t;
    }

    private GradientDrawable round(int color, float r, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(r));
        if (stroke != 0) g.setStroke(Math.max(1, px(1)), stroke);
        return g;
    }

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(16), px(18), px(16), px(28));
        sv.addView(col, new ScrollView.LayoutParams(-1, -2));

        // header: [‹ next] [title] [prev ›] [close]   (physical order, the app does not switch RTL on)
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView next = text("←", 24, TEXT, true);
        next.setTextDirection(View.TEXT_DIRECTION_LTR);
        next.setGravity(Gravity.CENTER);
        next.setContentDescription("الشهر التالي");
        next.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { shift(1); } });
        head.addView(next, new LinearLayout.LayoutParams(px(44), px(44)));
        title = text("", 20, TEXT, true);
        title.setGravity(Gravity.CENTER);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView prev = text("→", 24, TEXT, true);
        prev.setTextDirection(View.TEXT_DIRECTION_LTR);
        prev.setGravity(Gravity.CENTER);
        prev.setContentDescription("الشهر السابق");
        prev.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { shift(-1); } });
        head.addView(prev, new LinearLayout.LayoutParams(px(44), px(44)));
        TextView close = text("✕", 18, MUTED, false);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("إغلاق");
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        head.addView(close, new LinearLayout.LayoutParams(px(44), px(44)));
        col.addView(head);

        TextView who = text("تقويم مشاهدة: " + CigramProfiles.active(this).name, 12.5f, FAINT, false);
        who.setGravity(Gravity.CENTER);
        col.addView(who, new LinearLayout.LayoutParams(-1, -2));

        stats = text("", 13, MUTED, false);
        stats.setGravity(Gravity.CENTER);
        stats.setBackground(round(CARD, 14, LINE));
        stats.setPadding(px(12), px(10), px(12), px(10));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.setMargins(0, px(12), 0, px(12));
        col.addView(stats, sl);

        // weekday header (Saturday on the right)
        LinearLayout wd = new LinearLayout(this);
        for (int i = 6; i >= 0; i--) {
            TextView t = text(CigramWatchCalendar.WEEKDAYS[i], 11.5f, FAINT, true);
            t.setGravity(Gravity.CENTER);
            wd.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        col.addView(wd);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        col.addView(grid, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout legend = new LinearLayout(this);
        legend.setGravity(Gravity.CENTER);
        legend.addView(dotLabel("أضيف للمكتبة", SKY));
        legend.addView(dotLabel("حلقات جديدة لك", GOLD));
        legend.addView(dotLabel("شاهدت", RED));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.setMargins(0, px(10), 0, px(6));
        col.addView(legend, ll);

        dayList = new LinearLayout(this);
        dayList.setOrientation(LinearLayout.VERTICAL);
        col.addView(dayList, new LinearLayout.LayoutParams(-1, -2));
        setContentView(sv);
    }

    private View dotLabel(String s, int color) {
        TextView t = text("● " + s, 11.5f, MUTED, false);
        t.setTextColor(MUTED);
        android.text.SpannableString sp = new android.text.SpannableString("● " + s);
        sp.setSpan(new android.text.style.ForegroundColorSpan(color), 0, 1, 0);
        t.setText(sp);
        t.setPadding(px(8), 0, px(8), 0);
        return t;
    }

    private void shift(int by) {
        month += by;
        if (month < 0) { month = 11; year--; }
        if (month > 11) { month = 0; year++; }
        Calendar now = Calendar.getInstance(tz);
        selected = (year == now.get(Calendar.YEAR) && month == now.get(Calendar.MONTH)) ? now.get(Calendar.DAY_OF_MONTH) : 1;
        render();
    }

    private void load() {
        title.setText("…");
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                final List<JSONObject> h = new ArrayList<JSONObject>();
                final List<JSONObject> cat = new ArrayList<JSONObject>();
                try {
                    if (CigramProfiles.isMainActive(app)) h.addAll(CigramUserData.history(app));
                    JSONArray cw = CigramUserData.continueItems(app);
                    for (int i = 0; i < cw.length(); i++) if (cw.optJSONObject(i) != null) h.add(cw.optJSONObject(i));
                } catch (Throwable ignored) { }
                try {
                    String raw = CigramCatalogPrefs.of(app).getString(CigramCatalogPrefs.KEY_JSON, "");
                    if (raw != null && raw.length() > 2) {
                        Object root = new org.json.JSONTokener(raw).nextValue();
                        JSONArray arr = root instanceof JSONArray ? (JSONArray) root : ((JSONObject) root).optJSONArray("movies");
                        for (int i = 0; arr != null && i < arr.length(); i++) if (arr.optJSONObject(i) != null) cat.add(arr.optJSONObject(i));
                    }
                } catch (Throwable ignored) { }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        history = h;
                        catalogue = cat;
                        seriesTitles = CigramWatchCalendar.seriesTitles(cat);
                        render();
                    }
                });
            }
        }, "cigram-calendar").start();
    }

    private void render() {
        m = CigramWatchCalendar.build(year, month, tz, history, catalogue, seriesTitles, System.currentTimeMillis());
        title.setText("\u200F" + CigramWatchCalendar.MONTHS[month] + "\u00A0\u00A0" + year);
        stats.setText("شاهدت في " + m.watchedDays + " يوماً · " + m.watchedTitles + " عنواناً"
                + (m.streak > 0 ? " · 🔥 " + m.streak + " أيام متتالية" : "")
                + "\n" + m.newEpisodes + " حلقة جديدة من مسلسلاتك · " + m.added + " عنوان أضيف للمكتبة");
        CigramEmoji.bind(stats, stats.getText());
        grid.removeAllViews();
        int cells = m.firstWeekday + m.days;
        int rows = (cells + 6) / 7;
        Calendar now = Calendar.getInstance(tz);
        boolean thisMonth = year == now.get(Calendar.YEAR) && month == now.get(Calendar.MONTH);
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 6; c >= 0; c--) {                       // Saturday is the right-most column
                int slot = r * 7 + c;
                int d = slot - m.firstWeekday + 1;
                View cell = d >= 1 && d <= m.days ? new DayCell(d, thisMonth && d == now.get(Calendar.DAY_OF_MONTH)) : new View(this);
                row.addView(cell, new LinearLayout.LayoutParams(0, px(54), 1f));
            }
            grid.addView(row);
        }
        showDay(Math.min(selected, m.days));
    }

    private void showDay(int d) {
        selected = d;
        for (int i = 0; i < grid.getChildCount(); i++) {
            LinearLayout row = (LinearLayout) grid.getChildAt(i);
            for (int k = 0; k < row.getChildCount(); k++) row.getChildAt(k).invalidate();
        }
        dayList.removeAllViews();
        CigramWatchCalendar.Day day = m.day[d];
        TextView h = text("\u200F" + d + "\u00A0" + CigramWatchCalendar.MONTHS[month], 16, TEXT, true);
        h.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.setMargins(0, px(10), 0, px(6));
        dayList.addView(h, hl);
        if (day.empty()) {
            TextView e = text("لا شيء في هذا اليوم", 13, FAINT, false);
            e.setGravity(Gravity.RIGHT);
            dayList.addView(e);
            return;
        }
        section("شاهدت", RED, day.watched);
        section("حلقات جديدة من مسلسلاتك", GOLD, day.newEpisodes);
        section("أضيف للمكتبة", SKY, day.added);
    }

    private void section(String label, int color, List<CigramWatchCalendar.Entry> list) {
        if (list.isEmpty()) return;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(CARD, 16, LINE));
        card.setPadding(px(14), px(10), px(14), px(10));
        TextView t = text(label + " (" + list.size() + ")", 13, color, true);
        t.setGravity(Gravity.RIGHT);
        card.addView(t);
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        for (int i = 0; i < list.size() && i < 30; i++) {
            CigramWatchCalendar.Entry e = list.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView time = text(hm.format(new Date(e.at)), 11.5f, FAINT, false);
            row.addView(time, new LinearLayout.LayoutParams(-2, -2));
            TextView name = text(e.title + (e.subtitle.length() > 0 ? "  ·  " + e.subtitle : ""), 14, TEXT, false);
            name.setGravity(Gravity.RIGHT);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            card.addView(row, new LinearLayout.LayoutParams(-1, px(34)));
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.setMargins(0, 0, 0, px(10));
        dayList.addView(card, cl);
    }

    /** One day: number, a ring for today, three activity dots, a filled pill when selected. */
    private final class DayCell extends View {
        private final int d;
        private final boolean today;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        DayCell(int d, boolean today) {
            super(CigramWatchCalendarActivity.this);
            this.d = d;
            this.today = today;
            setClickable(true);
            CigramWatchCalendar.Day day = m.day[d];
            setContentDescription(d + " — شاهدت " + day.watched.size() + "، جديد " + day.newEpisodes.size() + "، أضيف " + day.added.size());
            setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showDay(DayCell.this.d); } });
            p.setTypeface(font);
            p.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f;
            CigramWatchCalendar.Day day = m.day[d];
            boolean sel = d == selected;
            if (sel) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0x26FFFFFF);
                r.set(cx - px(19), px(3), cx + px(19), h - px(3));
                c.drawRoundRect(r, px(12), px(12), p);
            }
            if (today) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(px(1.5f));
                p.setColor(RED);
                r.set(cx - px(17), px(5), cx + px(17), h - px(5));
                c.drawRoundRect(r, px(11), px(11), p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(day.empty() ? MUTED : TEXT);
            p.setTextSize(px(14));
            p.setFakeBoldText(!day.empty());
            c.drawText(String.valueOf(d), cx, h * 0.52f, p);
            int[] colors = {SKY, GOLD, RED};
            boolean[] on = {!day.added.isEmpty(), !day.newEpisodes.isEmpty(), !day.watched.isEmpty()};
            int n = 0;
            for (boolean b : on) if (b) n++;
            float gap = px(7), x = cx - (n - 1) * gap / 2f;
            for (int i = 0; i < 3; i++) {
                if (!on[i]) continue;
                p.setColor(colors[i]);
                c.drawCircle(x, h - px(11), px(2.4f), p);
                x += gap;
            }
        }
    }
}
