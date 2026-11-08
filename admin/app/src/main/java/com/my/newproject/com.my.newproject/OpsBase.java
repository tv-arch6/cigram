package com.my.newproject;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Shared base for the Ops screens (errors, statistics, ads control / check / map).
 * Talks to /admin/ops/* and /admin/ads/* on the admin Worker. The Worker wraps results as
 * { ok, message, data: {...} }, so every screen receives the inner "data" object.
 */
public abstract class OpsBase extends CgBase {

    interface JsonDone {
        void ok(JSONObject data, JSONObject root);
    }

    /** Selected statistics window: 1, 7 or 30 days. */
    protected int days = 7;

    /** Optional header (days chips...) kept visible above an error card. */
    protected View header() {
        return null;
    }

    // ------------------------------------------------------------ network

    protected void getJson(final String path, final JsonDone done) {
        showLoading("جارٍ التحميل...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgHttp.apiGet(path));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                JSONObject root = r.json == null ? new JSONObject() : r.json;
                JSONObject d = root.optJSONObject("data");
                done.ok(d == null ? new JSONObject() : d, root);
            }

            @Override
            public void fail(String message) {
                hideLoading();
                showFailure(message, path, done);
            }
        });
    }

    protected void postJson(final String path, final JSONObject payload, final String loadingText, final JsonDone done) {
        showLoading(loadingText == null ? "جارٍ الحفظ..." : loadingText);
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgHttp.apiPost(path, payload));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                JSONObject root = r.json == null ? new JSONObject() : r.json;
                JSONObject d = root.optJSONObject("data");
                done.ok(d == null ? new JSONObject() : d, root);
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(OpsBase.this, "تعذر التنفيذ", message);
            }
        });
    }

    private void showFailure(String message, final String path, final JsonDone done) {
        if (!alive()) return;
        LinearLayout c = scrollBody();
        View h = header();
        if (h != null) c.addView(h);
        String m = message;
        if (message != null && message.contains("404")) {
            m = message + "\nيبدو أن الـ Worker المنشور لا يحتوي مسارات المراقبة (v6). انشر cigram-admin-worker-v2.js ثم أعد المحاولة.";
        }
        c.addView(CgUi.errorCard(this, m, new Runnable() {
            @Override
            public void run() {
                getJson(path, done);
            }
        }));
    }

    // ------------------------------------------------------------ helpers

    protected void go(Class<?> cls) {
        startActivity(new Intent(this, cls));
    }

    protected View tile(String title, String sub, final Class<?> target) {
        LinearLayout card = CgUi.card(this);
        card.setClickable(true);
        card.addView(CgUi.text(this, title, 15, CgCfg.TEXT, true));
        card.addView(CgUi.muted(this, sub));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                go(target);
            }
        });
        card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        return card;
    }

    /** Chips: 24 hours / 7 days / 30 days. onChange runs after `days` was updated. */
    protected View daysBar(final Runnable onChange) {
        CgUi.ChipBar cb = new CgUi.ChipBar(this);
        final int[] values = new int[]{1, 7, 30};
        String[] labels = new String[]{"آخر 24 ساعة", "آخر 7 أيام", "آخر 30 يوماً"};
        for (int i = 0; i < values.length; i++) {
            final int v = values[i];
            TextView chip = cb.add(labels[i], v == days);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (days == v) return;
                    days = v;
                    onChange.run();
                }
            });
        }
        cb.scroll.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, -12, 0, -12, 8));
        return cb.scroll;
    }

    static String n(double v) {
        return String.format(Locale.US, "%,d", Math.round(v));
    }

    static String pct(double num, double den) {
        if (den <= 0) return "—";
        return Math.round(num * 100.0 / den) + "%";
    }

    protected int levelColor(String level) {
        if ("good".equals(level)) return CgCfg.GOOD;
        if ("warn".equals(level)) return CgCfg.WARN;
        if ("bad".equals(level)) return CgCfg.BAD;
        return CgCfg.MUTED;
    }

    protected View verdictCard(String level, String title, String text) {
        int color = levelColor(level);
        LinearLayout c = CgUi.card(this);
        c.setBackground(CgUi.bg(this, CgUi.alpha(color, 0x1A), 14, color));
        c.addView(CgUi.text(this, title, 14.5f, color, true));
        if (text != null && text.length() > 0) {
            TextView t = CgUi.text(this, text, 13, CgCfg.TEXT, false);
            t.setPadding(0, CgUi.dp(this, 4), 0, 0);
            c.addView(t);
        }
        c.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        return c;
    }

    /** A big number with a caption (weight 1 so several fit in a row). */
    protected View kpi(String label, String value, int color) {
        LinearLayout card = CgUi.card(this);
        card.setPadding(CgUi.dp(this, 8), CgUi.dp(this, 12), CgUi.dp(this, 8), CgUi.dp(this, 12));
        TextView v = CgUi.text(this, value, 21, color, true);
        v.setGravity(Gravity.CENTER);
        CgUi.ltr(v);
        card.addView(v, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        TextView l = CgUi.muted(this, label);
        l.setGravity(Gravity.CENTER);
        card.addView(l, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        return card;
    }

    protected LinearLayout kpiRow(View a, View b, View c) {
        LinearLayout r = CgUi.hbox(this);
        r.addView(a, CgUi.lpWeight(this, 1f, 0, 0, 4, 0));
        if (b != null) r.addView(b, CgUi.lpWeight(this, 1f, 2, 0, 2, 0));
        if (c != null) r.addView(c, CgUi.lpWeight(this, 1f, 4, 0, 0, 0));
        r.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        return r;
    }

    /** Label on one side, value on the other. */
    protected View kv(String k, String v, int valueColor) {
        LinearLayout r = CgUi.hbox(this);
        r.setPadding(0, CgUi.dp(this, 3), 0, CgUi.dp(this, 3));
        r.addView(CgUi.text(this, k, 13.5f, CgCfg.TEXT, false), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        TextView t = CgUi.text(this, v, 13.5f, valueColor, true);
        CgUi.ltr(t);
        r.addView(t, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        return r;
    }

    /** Card with a title and a vertical bar chart. */
    protected View chartCard(String title, double[] values, String[] labels, int color) {
        LinearLayout c = CgUi.card(this);
        c.addView(CgUi.text(this, title, 14, CgCfg.TEXT, true));
        c.addView(new BarChart(this, values, labels, color), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.dp(this, 160)));
        c.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        return c;
    }

    /** Card listing items of [{key,count}] with a proportional bar behind each row. */
    protected View rankCard(String title, JSONArray arr, int limit) {
        LinearLayout c = CgUi.card(this);
        c.addView(CgUi.text(this, title, 14, CgCfg.TEXT, true));
        c.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        if (arr == null || arr.length() == 0) {
            c.addView(CgUi.muted(this, "لا توجد بيانات."));
            return c;
        }
        double max = 1;
        int count = Math.min(arr.length(), limit);
        for (int i = 0; i < count; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) max = Math.max(max, o.optDouble("count", 0));
        }
        for (int i = 0; i < count; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String key = o.optString("key", "?");
            double v = o.optDouble("count", 0);
            LinearLayout row = CgUi.vbox(this);
            row.setPadding(0, CgUi.dp(this, 5), 0, 0);
            LinearLayout top = CgUi.hbox(this);
            TextView name = CgUi.text(this, key, 13, CgCfg.TEXT, false);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(name, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            TextView num = CgUi.text(this, n(v), 13, CgCfg.TEXT, true);
            CgUi.ltr(num);
            top.addView(num, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            row.addView(top, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            float frac = (float) Math.max(0.02, Math.min(1.0, v / max));
            LinearLayout track = CgUi.hbox(this);
            track.setWeightSum(1f);
            View fill = new View(this);
            fill.setBackground(CgUi.bg(this, CgCfg.STROKE, 3, 0));
            track.addView(fill, new LinearLayout.LayoutParams(0, CgUi.dp(this, 5), frac));
            row.addView(track, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.dp(this, 5)));
            c.addView(row, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }
        return c;
    }

    protected void copyText(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            CgUi.toast(this, "تم النسخ.");
        } catch (Exception e) {
            CgUi.toast(this, "تعذر النسخ.");
        }
    }

    protected void shareText(String title, String text) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, title);
            i.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(i, title));
        } catch (Exception e) {
            CgUi.toast(this, "تعذر فتح المشاركة.");
        }
    }

    // ------------------------------------------------------------- chart

    /** Minimal vertical bar chart (no external library). */
    static final class BarChart extends View {
        private final double[] values;
        private final String[] labels;
        private final int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        BarChart(Context c, double[] values, String[] labels, int color) {
            super(c);
            this.values = values;
            this.labels = labels;
            this.color = color;
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(CgCfg.MUTED);
            text.setTextSize(CgUi.dp(c, 10));
            if (CgUi.font(c) != null) text.setTypeface(CgUi.font(c));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int n = values.length;
            if (n == 0) return;
            float w = getWidth();
            float h = getHeight();
            float labelH = CgUi.dp(getContext(), 18);
            float topPad = CgUi.dp(getContext(), 16);
            float chartH = h - labelH - topPad;
            double max = 0;
            for (int i = 0; i < n; i++) max = Math.max(max, values[i]);
            if (max <= 0) {
                text.setTextSize(CgUi.dp(getContext(), 12));
                canvas.drawText("لا توجد بيانات", w / 2f, h / 2f, text);
                text.setTextSize(CgUi.dp(getContext(), 10));
                return;
            }
            float slot = w / n;
            float barW = Math.max(CgUi.dp(getContext(), 3), slot * 0.62f);
            int step = n > 20 ? 3 : (n > 12 ? 2 : 1);
            boolean showValues = n <= 14;
            paint.setColor(color);
            for (int i = 0; i < n; i++) {
                float cx = slot * i + slot / 2f;
                float bh = (float) (values[i] / max) * chartH;
                if (values[i] > 0 && bh < CgUi.dp(getContext(), 2)) bh = CgUi.dp(getContext(), 2);
                RectF r = new RectF(cx - barW / 2f, topPad + chartH - bh, cx + barW / 2f, topPad + chartH);
                canvas.drawRoundRect(r, 4f, 4f, paint);
                if (showValues && values[i] > 0) {
                    canvas.drawText(compact(values[i]), cx, topPad + chartH - bh - CgUi.dp(getContext(), 3), text);
                }
                if (i % step == 0 && i < labels.length) {
                    canvas.drawText(labels[i], cx, h - CgUi.dp(getContext(), 4), text);
                }
            }
        }

        private static String compact(double v) {
            if (v >= 1000000) return String.format(Locale.US, "%.1fM", v / 1000000.0);
            if (v >= 10000) return String.format(Locale.US, "%.0fk", v / 1000.0);
            if (v >= 1000) return String.format(Locale.US, "%.1fk", v / 1000.0);
            return String.valueOf(Math.round(v));
        }
    }
}
