package com.my.newproject;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The one design kit of the new admin (v2.0 "Clean"): headings, number cards, big section tiles, list rows and three flat charts
 * (bars, donut, line). Every colour comes from CgCfg; shapes come from CgUi.bg (flat, rounded, no borders, no gradients).
 */
final class DxUi {

    private DxUi() { }

    static final int MATCH = CgUi.MATCH;
    static final int WRAP = CgUi.WRAP;

    static int dp(Context c, float v) {
        return CgUi.dp(c, v);
    }

    // ------------------------------------------------------------------ text

    static TextView heading(Context c, String text) {
        TextView t = CgUi.text(c, text, 17, CgCfg.TEXT, true);
        android.graphics.drawable.GradientDrawable bar = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{CgCfg.ACCENT_G1, CgCfg.ACCENT_G2});
        bar.setCornerRadius(dp(c, 2));
        bar.setSize(dp(c, 4), dp(c, 19));
        t.setCompoundDrawablesRelative(bar, null, null, null);
        t.setCompoundDrawablePadding(dp(c, 9));
        t.setLayoutParams(CgUi.lp(c, MATCH, WRAP, 4, 22, 4, 10));
        return t;
    }

    static String num(double v) {
        if (v < 0) return "—";
        return String.format(Locale.US, "%,d", Math.round(v));
    }

    // ------------------------------------------------------------------ cards

    /** Softly-lifted rounded card with comfortable padding. */
    static LinearLayout card(Context c) {
        LinearLayout l = CgUi.vbox(c);
        l.setBackground(CgUi.bg(c, CgCfg.CARD, 20, 0));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        CgUi.elevate(l, 3);
        return l;
    }

    /** A number with its label: big coloured value on top, small grey label below, on a faint colour-washed card. */
    static LinearLayout kpi(Context c, String label, String value, int color) {
        LinearLayout k = card(c);
        // faint vertical wash of the metric colour so each number reads as its own thing
        k.setBackground(CgUi.gradient(c, CgUi.alpha(color, 0x24), CgCfg.CARD, 18));
        CgUi.elevate(k, 3);
        k.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 12));
        TextView v = CgUi.text(c, value, 25, color, true);
        v.setTag("v");
        v.setSingleLine(true);
        k.addView(v, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView l = CgUi.muted(c, label);
        l.setSingleLine(true);
        k.addView(l, new LinearLayout.LayoutParams(MATCH, WRAP));
        return k;
    }

    static void setKpi(View kpi, String value) {
        View v = kpi.findViewWithTag("v");
        if (v instanceof TextView) ((TextView) v).setText(value);
    }

    /** Equal-width row of cards with small gaps (no matter how many). */
    static LinearLayout row(Context c, View... items) {
        LinearLayout r = CgUi.hbox(c);
        CgUi.unclip(r);
        for (int i = 0; i < items.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, WRAP, 1f);
            p.setMargins(i == 0 ? 0 : dp(c, 5), 0, i == items.length - 1 ? 0 : dp(c, 5), 0);
            r.addView(items[i], p);
        }
        r.setLayoutParams(CgUi.lp(c, MATCH, WRAP, 0, 0, 0, 10));
        return r;
    }

    private static ImageView iconBubble(Context c, int icon, int color, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(CgIcons.icon(icon, color));
        int p = dp(c, sizeDp * 0.26f);
        iv.setPadding(p, p, p, p);
        // soft tinted gradient bubble (brighter top-start, fading out) instead of a flat wash
        iv.setBackground(CgUi.gradient(c, CgUi.alpha(color, 0x55), CgUi.alpha(color, 0x12), sizeDp / 2f));
        return iv;
    }

    private static void press(final View v, final Runnable r) {
        v.setClickable(true);
        v.setFocusable(true);
        CgUi.pressScale(v);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                try { r.run(); } catch (Throwable t) { android.util.Log.w("DxUi", "tap failed", t); }
            }
        });
    }

    /** Big section tile of the home screen: round icon, title, one short line. Put two per row with {@link #row}. */
    static LinearLayout tile(Context c, int icon, String title, String sub, int color, Runnable open) {
        LinearLayout t = card(c);
        t.setGravity(Gravity.START);
        t.setMinimumHeight(dp(c, 140));
        // a hint of the section colour washed into the top of the tile
        t.setBackground(CgUi.gradient(c, CgUi.alpha(color, 0x1C), CgCfg.CARD, 20));
        CgUi.elevate(t, 3);
        t.addView(iconBubble(c, icon, color, 48), new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        TextView ti = CgUi.text(c, title, 16.5f, CgCfg.TEXT, true);
        ti.setPadding(0, dp(c, 13), 0, 0);
        t.addView(ti, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView su = CgUi.muted(c, sub);
        su.setPadding(0, dp(c, 3), 0, 0);
        t.addView(su, new LinearLayout.LayoutParams(MATCH, WRAP));
        press(t, open);
        return t;
    }

    /** List row of a section page: icon, title, small line, optional badge and a chevron. */
    static LinearLayout item(Context c, int icon, String title, String sub, String badge, int color, Runnable open) {
        LinearLayout r = CgUi.hbox(c);
        r.setBackground(CgUi.bg(c, CgCfg.CARD, 18, 0));
        CgUi.elevate(r, 2);
        r.setPadding(dp(c, 14), dp(c, 13), dp(c, 14), dp(c, 13));
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, 74));
        r.addView(iconBubble(c, icon, color, 44), new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        LinearLayout mid = CgUi.vbox(c);
        mid.addView(CgUi.text(c, title, 15.5f, CgCfg.TEXT, true));
        if (sub != null && sub.length() > 0) mid.addView(CgUi.muted(c, sub));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, WRAP, 1f);
        ml.setMargins(dp(c, 12), 0, dp(c, 8), 0);
        r.addView(mid, ml);
        if (badge != null && badge.length() > 0) {
            TextView b = CgUi.badge(c, badge, color);
            r.addView(b, new LinearLayout.LayoutParams(WRAP, WRAP));
        }
        ImageView chev = new ImageView(c);
        chev.setImageDrawable(CgIcons.icon(CgIcons.CHEVRON, CgCfg.MUTED));
        chev.setRotation(90f);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20));
        cl.setMarginStart(dp(c, 6));
        r.addView(chev, cl);
        press(r, open);
        r.setLayoutParams(CgUi.lp(c, MATCH, WRAP, 0, 0, 0, 8));
        return r;
    }

    /** Titled card that holds a chart. */
    static LinearLayout chartCard(Context c, String title, String sub, View chart, int heightDp) {
        LinearLayout k = card(c);
        k.addView(CgUi.text(c, title, 15, CgCfg.TEXT, true));
        if (sub != null && sub.length() > 0) k.addView(CgUi.muted(c, sub));
        k.addView(chart, CgUi.lp(c, MATCH, heightDp, 0, 12, 0, 0));
        k.setLayoutParams(CgUi.lp(c, MATCH, WRAP, 0, 0, 0, 10));
        return k;
    }

    /** One legend line: coloured dot, name, value. */
    static View legend(Context c, int color, String name, String value) {
        LinearLayout r = CgUi.hbox(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(c);
        dot.setBackground(CgUi.bg(c, color, 5, 0));
        r.addView(dot, new LinearLayout.LayoutParams(dp(c, 10), dp(c, 10)));
        TextView n = CgUi.text(c, name, 13, CgCfg.MUTED, false);
        n.setPadding(dp(c, 8), 0, 0, 0);
        r.addView(n, new LinearLayout.LayoutParams(0, WRAP, 1f));
        r.addView(CgUi.text(c, value, 13, CgCfg.TEXT, true), new LinearLayout.LayoutParams(WRAP, WRAP));
        r.setLayoutParams(CgUi.lp(c, MATCH, WRAP, 0, 4, 0, 4));
        return r;
    }

    // ================================================================== charts

    private abstract static class Chart extends View {
        protected final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        protected float progress = 1f;
        private ValueAnimator anim;

        Chart(Context c) {
            super(c);
            setLayoutDirection(LAYOUT_DIRECTION_LTR);            // time runs left to right
            if (CgUi.font(c) != null) p.setTypeface(CgUi.font(c));
        }

        /** Grow-in animation after new data. */
        protected void replay() {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(450);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    progress = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        }

        @Override protected void onDetachedFromWindow() {
            if (anim != null) anim.cancel();
            super.onDetachedFromWindow();
        }
    }

    /** Vertical bars with rounded tops; the last bar is solid, the others softer; the biggest value is labelled. */
    static final class BarChart extends Chart {
        private double[] v = new double[0];
        private String[] labels = new String[0];
        private int color = CgCfg.ACCENT_D;

        BarChart(Context c) {
            super(c);
        }

        void setData(double[] values, String[] xLabels, int barColor) {
            v = values == null ? new double[0] : values;
            labels = xLabels == null ? new String[0] : xLabels;
            color = barColor;
            replay();
        }

        @Override protected void onDraw(Canvas c) {
            int n = v.length;
            float w = getWidth(), h = getHeight();
            if (n == 0 || w <= 0) return;
            float labelH = dp(getContext(), 18);
            float top = dp(getContext(), 16);
            float base = h - labelH;
            double max = 1;
            int maxAt = 0;
            for (int i = 0; i < n; i++) if (v[i] > max) { max = v[i]; maxAt = i; }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, dp(getContext(), 1)));
            p.setColor(CgUi.alpha(CgCfg.STROKE, 0xAA));
            for (int g = 0; g <= 2; g++) {                       // three faint guide lines
                float y = top + (base - top) * g / 2f;
                c.drawLine(0, y, w, y, p);
            }
            float slot = w / n;
            float bw = Math.min(slot * 0.62f, dp(getContext(), 26));
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(getContext(), 10.5f));
            int step = n > 10 ? 2 : 1;
            for (int i = 0; i < n; i++) {
                float cx = slot * i + slot / 2f;
                float bh = (float) (v[i] / max) * (base - top) * progress;
                if (v[i] > 0 && bh < dp(getContext(), 4)) bh = dp(getContext(), 4);
                int topCol = i == n - 1 ? color : CgUi.alpha(color, 0xC8);
                int botCol = CgUi.alpha(color, i == n - 1 ? 0x44 : 0x1E);
                p.setShader(new android.graphics.LinearGradient(0, base - bh, 0, base, topCol, botCol, android.graphics.Shader.TileMode.CLAMP));
                float r = Math.min(bw / 2f, dp(getContext(), 8));
                c.drawRoundRect(new RectF(cx - bw / 2f, base - bh, cx + bw / 2f, base), r, r, p);
                p.setShader(null);
                if (i == maxAt && v[i] > 0 && progress > 0.9f) {
                    p.setColor(CgCfg.TEXT);
                    c.drawText(num(v[i]), cx, Math.max(dp(getContext(), 11), base - bh - dp(getContext(), 4)), p);
                }
                if (i < labels.length && labels[i] != null && (n - 1 - i) % step == 0) {
                    p.setColor(CgCfg.MUTED);
                    c.drawText(labels[i], cx, h - dp(getContext(), 3), p);
                }
            }
        }
    }

    /** Ring split into coloured parts with the total in the middle. */
    static final class Donut extends Chart {
        private double[] v = new double[0];
        private int[] colors = new int[0];
        private String center = "";
        private String sub = "";

        Donut(Context c) {
            super(c);
        }

        void setData(double[] values, int[] partColors, String centerText, String subText) {
            v = values == null ? new double[0] : values;
            colors = partColors == null ? new int[0] : partColors;
            center = centerText == null ? "" : centerText;
            sub = subText == null ? "" : subText;
            replay();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float size = Math.min(w, h);
            float stroke = dp(getContext(), 20);
            RectF r = new RectF((w - size) / 2f + stroke / 2f, (h - size) / 2f + stroke / 2f, (w + size) / 2f - stroke / 2f, (h + size) / 2f - stroke / 2f);
            double total = 0;
            for (double x : v) total += x;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setStrokeCap(Paint.Cap.BUTT);
            if (total <= 0) {
                p.setColor(CgCfg.CARD2);
                c.drawArc(r, 0, 360, false, p);
            } else {
                float start = -90f;
                int parts = 0;
                for (double x : v) if (x > 0) parts++;
                float gap = parts > 1 ? 3f : 0f;
                for (int i = 0; i < v.length; i++) {
                    if (v[i] <= 0) continue;
                    float sweep = (float) (v[i] / total * 360f) * progress;
                    p.setColor(i < colors.length ? colors[i] : CgCfg.GRAY);
                    c.drawArc(r, start + gap / 2f, Math.max(0.5f, sweep - gap), false, p);
                    start += (float) (v[i] / total * 360f) * progress;
                }
            }
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(CgCfg.TEXT);
            p.setTextSize(dp(getContext(), 26));
            c.drawText(center, w / 2f, h / 2f + dp(getContext(), 4), p);
            p.setColor(CgCfg.MUTED);
            p.setTextSize(dp(getContext(), 11.5f));
            c.drawText(sub, w / 2f, h / 2f + dp(getContext(), 22), p);
        }
    }

    /** One or two smooth lines with a soft fill under the first (live numbers, daily trends). */
    static final class LineChart extends Chart {
        private double[][] series = new double[0][];
        private int[] colors = new int[0];
        private final Path path = new Path();

        LineChart(Context c) {
            super(c);
        }

        void setData(double[][] data, int[] lineColors, boolean animate) {
            series = data == null ? new double[0][] : data;
            colors = lineColors == null ? new int[0] : lineColors;
            if (animate) replay(); else { progress = 1f; invalidate(); }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float pad = dp(getContext(), 6);
            double max = 1;
            int len = 0;
            for (double[] s : series) {
                if (s == null) continue;
                len = Math.max(len, s.length);
                for (double x : s) max = Math.max(max, x);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, dp(getContext(), 1)));
            p.setColor(CgUi.alpha(CgCfg.STROKE, 0xAA));
            for (int g = 0; g <= 2; g++) {
                float y = pad + (h - 2 * pad) * g / 2f;
                c.drawLine(0, y, w, y, p);
            }
            if (len < 2) return;
            for (int si = 0; si < series.length; si++) {
                double[] s = series[si];
                if (s == null || s.length < 2) continue;
                int col = si < colors.length ? colors[si] : CgCfg.ACCENT;
                path.reset();
                float lastX = 0, lastY = 0;
                for (int i = 0; i < s.length; i++) {
                    float x = (w - 2 * pad) * i / (len - 1) + pad;
                    float y = (float) (h - pad - (s[i] / max) * (h - 2 * pad) * progress);
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                    lastX = x;
                    lastY = y;
                }
                if (si == 0) {
                    Path area = new Path(path);
                    area.lineTo(lastX, h - pad);
                    area.lineTo(pad, h - pad);
                    area.close();
                    p.setStyle(Paint.Style.FILL);
                    p.setShader(new android.graphics.LinearGradient(0, pad, 0, h - pad,
                            CgUi.alpha(col, 0x5A), CgUi.alpha(col, 0x00), android.graphics.Shader.TileMode.CLAMP));
                    c.drawPath(area, p);
                    p.setShader(null);
                }
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(getContext(), 2.4f));
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setColor(col);
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(lastX, lastY, dp(getContext(), 4), p);
            }
        }
    }
}
