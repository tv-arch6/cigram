package com.my.newproject;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * «النبض والأرباح» — one page, two things that never stop.
 *
 *  1. THE LIVE CHART. Active now / watching now / viewers today / new accounts, drawn from the samples that
 *     {@link CgPulse} records every 15 minutes in the background (24/7) plus one every 20 seconds while this page is
 *     open. Windows: 6 hours, 24 hours, 7 days. Each line is scaled to its own peak so a small number is still readable.
 *
 *  2. THE ADMOB EARNINGS FORECAST. Real impressions counted by the apps (the same events «AdMob: الفحص» reads:
 *     {@code /admin/ops/ads-check}) multiplied by the eCPM of each ad format. The eCPM values are the admin's own
 *     (tap «eCPM» to set them from the AdMob console); the impressions are real. Today, the last 7 and 30 days, the
 *     projected month, and an arrow for each: ▲ when the pace is above the longer average, ▼ when it is below.
 *     It is an estimate — the paid figure is the one in AdMob.
 */
public class CgPulseActivity extends OpsBase {

    private static final String PREFS = "cigram_pulse_ecpm";
    private static final String[][] FORMATS = {
            {"rewarded", "مكافأة (Rewarded)", "4.0"},
            {"interstitial", "بيني (Interstitial)", "1.5"},
            {"app_open", "فتح التطبيق (App Open)", "2.0"},
            {"banner", "بانر (Banner)", "0.3"},
    };
    private static final long[] WINDOWS = {6L * 3600000L, 24L * 3600000L, 7L * 86400000L};
    private static final String[] WINDOW_NAMES = {"6 ساعات", "24 ساعة", "7 أيام"};

    private int window = 1;
    private Chart chart;
    private TextView[] now = new TextView[4];
    private LinearLayout earnBox;
    private boolean open;
    private final JSONObject[] ads = new JSONObject[3];        // 1, 7, 30 days

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!open) return;
            new Thread(new Runnable() {
                @Override public void run() {
                    final long[] s = CgPulse.sample(getApplicationContext());
                    ui().post(new Runnable() { @Override public void run() { if (alive()) refreshChart(s); } });
                }
            }, "cigram-pulse-live").start();
            ui().postDelayed(this, 20000L);
        }
    };

    @Override protected String screenTitle() { return "النبض والأرباح"; }

    @Override protected void onBuild() {
        CgPulse.ensureScheduled(this);
        LinearLayout c = scrollBody();

        // ---- live chart
        LinearLayout card = CgUi.card(this);
        card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        card.addView(CgUi.text(this, "البث الحي · يُسجَّل 24/7", 16, CgCfg.TEXT, true));
        LinearLayout chips = CgUi.hbox(this);
        final TextView[] cs = new TextView[WINDOWS.length];
        for (int i = 0; i < WINDOWS.length; i++) {
            final int idx = i;
            cs[i] = CgUi.chip(this, WINDOW_NAMES[i], i == window);
            cs[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    window = idx;
                    for (int k = 0; k < cs.length; k++) cs[k].setSelected(k == idx);
                    for (int k = 0; k < cs.length; k++) cs[k].setAlpha(k == idx ? 1f : 0.55f);
                    refreshChart(null);
                }
            });
            cs[i].setAlpha(i == window ? 1f : 0.55f);
            chips.addView(cs[i], CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, i == 0 ? 0 : 6, 0, 0, 0));
        }
        card.addView(chips, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 8));
        chart = new Chart(this);
        card.addView(chart, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.dp(this, 190)));
        String[] names = {"النشطون الآن", "يشاهدون الآن", "مشاهدو اليوم", "مسجّلون جدد (24س)"};
        LinearLayout legend = CgUi.hbox(this);
        for (int i = 0; i < 4; i++) {
            LinearLayout cell = CgUi.vbox(this);
            cell.setGravity(Gravity.CENTER);
            now[i] = CgUi.text(this, "—", 18, CgCfg.TEXT, true);
            now[i].setGravity(Gravity.CENTER);
            cell.addView(now[i]);
            TextView n = CgUi.text(this, Chart.MARKS[i] + " " + names[i], 10.5f, CgCfg.MUTED, false);
            n.setGravity(Gravity.CENTER);
            cell.addView(n);
            legend.addView(cell, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        }
        card.addView(legend, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        c.addView(card);

        // ---- earnings
        earnBox = CgUi.card(this);
        earnBox.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        c.addView(earnBox);
        bar.action("eCPM", new Runnable() { @Override public void run() { editEcpm(); } });

        refreshChart(null);
        loadAds();
    }

    @Override protected void onResume() {
        super.onResume();
        open = true;
        ui().removeCallbacks(tick);
        ui().post(tick);
    }

    @Override protected void onPause() {
        open = false;
        ui().removeCallbacks(tick);
        super.onPause();
    }

    private void refreshChart(long[] latest) {
        long[][] h = CgPulse.history(this, WINDOWS[window]);
        chart.set(h, WINDOWS[window]);
        long[] last = latest != null ? latest : h.length > 0 ? h[h.length - 1] : null;
        if (last == null) return;
        int[] idx = {CgPulse.ACTIVE, CgPulse.WATCHING, CgPulse.USERS, CgPulse.NEW_ACCOUNTS};
        for (int i = 0; i < 4; i++) now[i].setText(NxChart.compact(last[idx[i]]));
    }

    // ------------------------------------------------------------------ earnings forecast

    private void loadAds() {
        final int[] days = {1, 7, 30};
        CgHttp.async(this, new CgHttp.Work<JSONObject[]>() {
            @Override public JSONObject[] run() throws Exception {
                JSONObject[] out = new JSONObject[3];
                for (int i = 0; i < 3; i++) {
                    CgHttp.Res r = CgHttp.apiGet("/admin/ops/ads-check?days=" + days[i]);
                    out[i] = r.ok() && r.json != null ? r.json.optJSONObject("data") : null;
                }
                return out;
            }
        }, new CgHttp.Done<JSONObject[]>() {
            @Override public void ok(JSONObject[] r) {
                System.arraycopy(r, 0, ads, 0, 3);
                renderEarnings();
            }
            @Override public void fail(String message) { renderEarnings(); }
        });
    }

    private double ecpm(String format) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        for (String[] f : FORMATS) if (f[0].equals(format)) return p.getFloat(format, Float.parseFloat(f[2]));
        return 1.0;
    }

    /** The ad format of a placement name the apps report ("player", "reward_unlock", "app_open", "watchlist_banner" …). */
    static String formatOf(String placement) {
        String n = placement == null ? "" : placement.toLowerCase(java.util.Locale.US);
        if (n.contains("banner")) return "banner";
        if (n.contains("open")) return "app_open";
        if (n.contains("reward") || n.contains("unlock")) return "rewarded";
        return "interstitial";
    }

    /** {impressions, dollars} of one ads-check answer. */
    private double[] money(JSONObject d) {
        double shows = 0, usd = 0;
        JSONArray pl = d == null ? null : d.optJSONArray("placements");
        for (int i = 0; pl != null && i < pl.length(); i++) {
            JSONObject o = pl.optJSONObject(i);
            if (o == null) continue;
            double s = o.optDouble("show", 0);
            shows += s;
            usd += s / 1000.0 * ecpm(formatOf(o.optString("name")));
        }
        return new double[]{shows, usd};
    }

    private void renderEarnings() {
        if (!alive()) return;
        earnBox.removeAllViews();
        earnBox.addView(CgUi.text(this, "توقّعات أرباح AdMob", 16, CgCfg.TEXT, true));
        earnBox.addView(CgUi.muted(this, "مشاهدات إعلانات فعلية من التطبيق والموقع × eCPM الذي تحدده. الرقم المدفوع هو ما يظهر في AdMob."));
        if (ads[0] == null && ads[1] == null && ads[2] == null) {
            earnBox.addView(CgUi.muted(this, "بانتظار بيانات الإعلانات…"), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
            ui().postDelayed(new Runnable() { @Override public void run() { if (alive() && open) loadAds(); } }, 30000L);
            return;
        }
        double[] d1 = money(ads[0]), d7 = money(ads[1]), d30 = money(ads[2]);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        double dayPart = Math.max(0.05, (cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)) / 1440.0);
        double todayPace = d1[1] / dayPart;                 // what today becomes at this pace
        double avg7 = d7[1] / 7.0, avg30 = d30[1] / 30.0;
        earnBox.addView(line("اليوم حتى الآن", d1[1], (long) d1[0], todayPace - avg7), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 0));
        earnBox.addView(line("آخر 7 أيام", d7[1], (long) d7[0], avg7 - avg30), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        earnBox.addView(line("آخر 30 يوماً", d30[1], (long) d30[0], 0), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        double month = (avg7 > 0 ? avg7 : avg30) * 30.0;
        earnBox.addView(line("المتوقع لهذا الشهر", month, -1, avg7 - avg30), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        ui().postDelayed(new Runnable() { @Override public void run() { if (alive() && open) loadAds(); } }, 5L * 60000L);
    }

    private View line(String label, double usd, long shows, double trend) {
        LinearLayout row = CgUi.hbox(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = CgUi.vbox(this);
        col.addView(CgUi.text(this, label, 13.5f, CgCfg.TEXT, false));
        if (shows >= 0) col.addView(CgUi.muted(this, NxChart.compact(shows) + " مشاهدة إعلان"));
        row.addView(col, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        boolean up = trend > 0.0001, down = trend < -0.0001;
        TextView arrow = CgUi.text(this, up ? "▲" : down ? "▼" : "•", 15, up ? CgCfg.GOOD : down ? CgCfg.BAD : CgCfg.MUTED, true);
        row.addView(arrow, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 8, 0));
        TextView v = CgUi.text(this, String.format(java.util.Locale.US, "$%.2f", usd), 18, CgCfg.TEXT, true);
        v.setTextDirection(View.TEXT_DIRECTION_LTR);
        row.addView(v);
        return row;
    }

    private void editEcpm() {
        final CharSequence[] names = new CharSequence[FORMATS.length];
        for (int i = 0; i < FORMATS.length; i++) names[i] = FORMATS[i][1] + " — $" + String.format(java.util.Locale.US, "%.2f", ecpm(FORMATS[i][0]));
        CgUi.choose(this, "eCPM لكل نوع إعلان (دولار لكل 1000 مشاهدة)", names, new CgUi.IntCb() {
            @Override public void run(final int i) {
                CgUi.input(CgPulseActivity.this, FORMATS[i][1], "مثال: 1.50", String.format(java.util.Locale.US, "%.2f", ecpm(FORMATS[i][0])), false, new CgUi.Callback<String>() {
                    @Override public void run(String v) {
                        try {
                            float f = Float.parseFloat(v.trim().replace(',', '.').replace('٫', '.'));
                            if (f < 0 || f > 500) return;
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(FORMATS[i][0], f).apply();
                            renderEarnings();
                        } catch (Throwable ignored) { }
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ the chart

    /** Four white lines told apart by their stroke (solid, dashed, dotted, thin): the admin's black-and-white look. */
    static final class Chart extends View {
        static final String[] MARKS = {"━", "╍", "┅", "─"};
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private long[][] data = new long[0][];
        private long windowMs = 1;
        private final float d;

        Chart(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            grid.setColor(0x1FFFFFFF);
            grid.setStrokeWidth(Math.max(1f, d));
            text.setColor(0x99FFFFFF);
            text.setTextSize(10 * d);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        void set(long[][] rows, long window) { data = rows == null ? new long[0][] : rows; windowMs = Math.max(1, window); invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), pad = 6 * d, bottom = h - 14 * d;
            for (int i = 0; i <= 3; i++) { float y = pad + (bottom - pad) * i / 3f; c.drawLine(0, y, w, y, grid); }
            if (data.length < 2) {
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText("يتكوّن الرسم مع أول عيّنتين (عيّنة كل 15 دقيقة في الخلفية)", w / 2f, h / 2f, text);
                return;
            }
            long end = System.currentTimeMillis(), start = end - windowMs;
            int[] idx = {CgPulse.ACTIVE, CgPulse.WATCHING, CgPulse.USERS, CgPulse.NEW_ACCOUNTS};
            int[] ink = {0xFFFFFFFF, 0xFFFFFFFF, 0xB3FFFFFF, 0x80FFFFFF};
            float[] width = {2.6f, 2.2f, 2f, 1.4f};
            for (int s = 3; s >= 0; s--) {
                long max = 1;
                for (long[] r : data) max = Math.max(max, r[idx[s]]);
                path.reset();
                boolean first = true;
                for (long[] r : data) {
                    float x = w * (r[CgPulse.T] - start) / (float) windowMs;
                    float y = bottom - (bottom - pad) * (r[idx[s]] / (float) max);
                    if (first) { path.moveTo(x, y); first = false; } else path.lineTo(x, y);
                }
                p.setColor(ink[s]);
                p.setStrokeWidth(width[s] * d);
                p.setPathEffect(s == 1 ? new DashPathEffect(new float[]{10 * d, 6 * d}, 0) : s == 2 ? new DashPathEffect(new float[]{2 * d, 6 * d}, 0) : null);
                c.drawPath(path, p);
            }
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(new java.text.SimpleDateFormat(windowMs > 86400000L ? "MM/dd" : "HH:mm", java.util.Locale.US).format(new java.util.Date(start)), 0, h - 2 * d, text);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText("الآن", w, h - 2 * d, text);
        }
    }
}
