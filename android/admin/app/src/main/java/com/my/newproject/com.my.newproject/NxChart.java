package com.my.newproject;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.Locale;

/** Charts of the new home: a live chart that scrolls one point per second, sparklines and a stacked bar. */
final class NxChart {

    private NxChart() {}

    // ======================================================================= live

    /** Multi-series line chart of the last {@code CAP} seconds; each push() slides the lines left smoothly. */
    static final class Live extends View {
        static final int CAP = 60;

        private final ArrayList<double[]> points = new ArrayList<double[]>();
        private final int[] colors;
        private final boolean[] visible;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float phase = 1f;
        private double maxFrom = 1;
        private double maxTo = 1;
        private ValueAnimator anim;

        Live(Context c, int[] colors) {
            super(c);
            this.colors = colors;
            this.visible = new boolean[colors.length];
            for (int i = 0; i < visible.length; i++) visible[i] = true;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeWidth(CgUi.dp(c, 2.4f));
            fill.setStyle(Paint.Style.FILL);
            grid.setColor(NxUi.LINE_SOFT);
            grid.setStrokeWidth(Math.max(1, CgUi.dp(c, 1)));
            label.setColor(NxUi.DIM);
            label.setTextSize(CgUi.dp(c, 10));
            if (CgUi.font(c) != null) label.setTypeface(CgUi.font(c));
            head.setStyle(Paint.Style.FILL);
            setLayoutDirection(LAYOUT_DIRECTION_LTR);
        }

        void setVisible(int series, boolean on) {
            if (series >= 0 && series < visible.length) visible[series] = on;
            maxTo = maxFor();
            maxFrom = maxTo;
            invalidate();
        }

        boolean isVisible(int series) {
            return series >= 0 && series < visible.length && visible[series];
        }

        /** Seeds the chart (no animation), oldest first. */
        void seed(ArrayList<double[]> history) {
            points.clear();
            for (int i = Math.max(0, history.size() - (CAP + 1)); i < history.size(); i++) points.add(history.get(i));
            maxTo = maxFor();
            maxFrom = maxTo;
            phase = 1f;
            invalidate();
        }

        void push(double[] values) {
            points.add(values.clone());
            while (points.size() > CAP + 2) points.remove(0);
            double before = maxFrom + (maxTo - maxFrom) * phase;
            maxFrom = before;
            maxTo = maxFor();
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(900);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    phase = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            phase = 0f;
            anim.start();
        }

        private double maxFor() {
            double m = 0;
            for (double[] p : points) {
                for (int s = 0; s < p.length && s < visible.length; s++) if (visible[s]) m = Math.max(m, p[s]);
            }
            if (m <= 0) return 4;
            // round up to a "nice" ceiling so the grid labels stay readable
            double raw = m * 1.25;
            double mag = Math.pow(10, Math.floor(Math.log10(raw)));
            double n = raw / mag;
            double nice = n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10;
            return Math.max(4, nice * mag);
        }

        @Override
        protected void onDetachedFromWindow() {
            if (anim != null) anim.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Context c = getContext();
            float w = getWidth();
            float h = getHeight();
            float left = CgUi.dp(c, 30);
            float right = w - CgUi.dp(c, 10);
            float top = CgUi.dp(c, 10);
            float bottom = h - CgUi.dp(c, 20);
            double max = maxFrom + (maxTo - maxFrom) * phase;
            if (max <= 0) max = 1;

            label.setTextAlign(Paint.Align.LEFT);
            for (int i = 0; i <= 3; i++) {
                float y = bottom - (bottom - top) * i / 3f;
                canvas.drawLine(left, y, right, y, grid);
                canvas.drawText(compact(max * i / 3.0), 0, y + CgUi.dp(c, 3.5f), label);
            }
            label.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("60ث", left, h - CgUi.dp(c, 4), label);
            label.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("30ث", (left + right) / 2f, h - CgUi.dp(c, 4), label);
            label.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("الآن", right, h - CgUi.dp(c, 4), label);

            int n = points.size();
            if (n < 2) return;
            float step = (right - left) / CAP;
            canvas.save();
            canvas.clipRect(left, 0, right + CgUi.dp(c, 8), h);
            for (int s = colors.length - 1; s >= 0; s--) {
                if (!visible[s]) continue;
                path.reset();
                float px = 0;
                float py = 0;
                boolean started = false;
                for (int i = 0; i < n; i++) {
                    double[] p = points.get(i);
                    double v = s < p.length ? p[s] : 0;
                    if (v < 0) v = 0;
                    float x = right - (n - 1 - i) * step + (1f - phase) * step;
                    float y = bottom - (float) (v / max) * (bottom - top);
                    if (!started) {
                        path.moveTo(x, y);
                        started = true;
                    } else {
                        float cx = (px + x) / 2f;
                        path.cubicTo(cx, py, cx, y, x, y);
                    }
                    px = x;
                    py = y;
                }
                Path area = new Path(path);
                area.lineTo(px, bottom);
                area.lineTo(right - (n - 1) * step + (1f - phase) * step, bottom);
                area.close();
                fill.setShader(new LinearGradient(0, top, 0, bottom, NxUi.alpha(colors[s], 0x55), NxUi.alpha(colors[s], 0x00), Shader.TileMode.CLAMP));
                canvas.drawPath(area, fill);
                line.setColor(colors[s]);
                canvas.drawPath(path, line);
                // glowing head on the newest value
                head.setColor(NxUi.alpha(colors[s], 0x40));
                canvas.drawCircle(px, py, CgUi.dp(c, 5 + 4 * (1f - phase)), head);
                head.setColor(colors[s]);
                canvas.drawCircle(px, py, CgUi.dp(c, 3.2f), head);
            }
            canvas.restore();
        }
    }

    static String compact(double v) {
        if (v >= 1000000) return String.format(Locale.US, "%.1fM", v / 1000000.0);
        if (v >= 1000) return String.format(Locale.US, "%.1fK", v / 1000.0);
        if (v == Math.floor(v)) return String.valueOf((long) v);
        return String.format(Locale.US, "%.1f", v);
    }

    // ================================================================== sparkline

    static final class Spark extends View {
        private double[] data = new double[0];
        private final int color;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Spark(Context c, int color) {
            super(c);
            this.color = color;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            line.setStrokeWidth(CgUi.dp(c, 1.8f));
            line.setColor(color);
            fill.setStyle(Paint.Style.FILL);
            dot.setColor(color);
            setLayoutDirection(LAYOUT_DIRECTION_LTR);
        }

        void set(double[] d) {
            data = d == null ? new double[0] : d;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int n = data.length;
            float w = getWidth();
            float h = getHeight();
            float pad = CgUi.dp(getContext(), 3);
            if (n < 2) {
                line.setAlpha(70);
                canvas.drawLine(0, h - pad, w, h - pad, line);
                line.setAlpha(255);
                return;
            }
            double max = 0;
            double min = Double.MAX_VALUE;
            for (double v : data) {
                max = Math.max(max, v);
                min = Math.min(min, v);
            }
            if (min < 0) min = 0;
            if (max - min < 1) max = min + 1;
            float step = (w - pad * 2) / (n - 1);
            path.reset();
            float px = 0;
            float py = 0;
            for (int i = 0; i < n; i++) {
                float x = pad + step * i;
                float y = h - pad - (float) ((Math.max(0, data[i]) - min) / (max - min)) * (h - pad * 2);
                if (i == 0) path.moveTo(x, y);
                else {
                    float cx = (px + x) / 2f;
                    path.cubicTo(cx, py, cx, y, x, y);
                }
                px = x;
                py = y;
            }
            Path area = new Path(path);
            area.lineTo(px, h);
            area.lineTo(pad, h);
            area.close();
            fill.setShader(new LinearGradient(0, 0, 0, h, NxUi.alpha(color, 0x45), NxUi.alpha(color, 0x00), Shader.TileMode.CLAMP));
            canvas.drawPath(area, fill);
            canvas.drawPath(path, line);
            canvas.drawCircle(px, py, CgUi.dp(getContext(), 2.6f), dot);
        }
    }

    // ================================================================== stack bar

    static final class Stack extends View {
        private double[] values = new double[0];
        private int[] colors = new int[0];
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Stack(Context c) {
            super(c);
            setLayoutDirection(LAYOUT_DIRECTION_RTL);
        }

        void set(double[] v, int[] c) {
            values = v == null ? new double[0] : v;
            colors = c == null ? new int[0] : c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float rad = h / 2f;
            p.setColor(0xFF0C1322);
            r.set(0, 0, w, h);
            canvas.drawRoundRect(r, rad, rad, p);
            double total = 0;
            for (double v : values) total += Math.max(0, v);
            if (total <= 0) return;
            float gap = CgUi.dp(getContext(), 2);
            float x = w; // right to left (RTL reading order)
            for (int i = 0; i < values.length; i++) {
                double v = Math.max(0, values[i]);
                if (v <= 0) continue;
                float seg = (float) (v / total) * w;
                p.setColor(i < colors.length ? colors[i] : NxUi.SUB);
                r.set(Math.max(0, x - seg + gap / 2f), 0, x - gap / 2f, h);
                if (r.width() > 0) canvas.drawRoundRect(r, rad, rad, p);
                x -= seg;
            }
        }
    }
}
