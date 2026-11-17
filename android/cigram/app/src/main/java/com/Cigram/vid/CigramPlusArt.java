package com.Cigram.vid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/** Hand-drawn vector icons and backgrounds of the Plus page (no bitmaps, no XML). */
final class CigramPlusArt {

    static final int NO_ADS = 0, QUALITY = 1, PIP = 2, TV = 3, SUPPORT = 4, BOLT = 5;

    private CigramPlusArt() { }

    /** Dark teal card with a warm gold glow in the corner: the hero of the page. */
    static GradientDrawable hero(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF2B2410, 0xFF123340, 0xFF071B24});
        g.setCornerRadius(CigramUI.dp(c, radiusDp));
        g.setStroke(Math.max(1, CigramUI.dp(c, 1)), 0x66F2B63D);
        return g;
    }

    static GradientDrawable glow(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.85f, 0.0f);
        g.setGradientRadius(CigramUI.dp(c, 240));
        g.setColors(new int[]{0x66F2B63D, 0x22F2B63D, 0x00F2B63D});
        g.setCornerRadius(CigramUI.dp(c, radiusDp));
        return g;
    }

    static GradientDrawable surface(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(CigramPlusUI.SURFACE);
        g.setCornerRadius(CigramUI.dp(c, radiusDp));
        return g;
    }

    static GradientDrawable ring(Context c, float radiusDp, int fill, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(CigramUI.dp(c, radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, CigramUI.dp(c, strokeDp)), stroke);
        return g;
    }

    // ------------------------------------------------------------------ icons

    static final class Icon extends Drawable {
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Icon(int kind, int color) {
            this.kind = kind;
            p.setColor(color);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height();
            c.save();
            c.translate(getBounds().left, getBounds().top);
            float s = Math.min(w, h);
            float sw = s * 0.085f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(sw);
            switch (kind) {
                case NO_ADS: {
                    c.drawCircle(s * 0.5f, s * 0.5f, s * 0.38f, p);
                    c.drawLine(s * 0.24f, s * 0.76f, s * 0.76f, s * 0.24f, p);
                    p.setStyle(Paint.Style.FILL);
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setFakeBoldText(true);
                    p.setTextSize(s * 0.24f);
                    c.drawText("AD", s * 0.5f, s * 0.58f, p);
                    p.setFakeBoldText(false);
                    break;
                }
                case QUALITY: {
                    c.drawRoundRect(new RectF(s * 0.1f, s * 0.22f, s * 0.9f, s * 0.78f), s * 0.14f, s * 0.14f, p);
                    p.setStyle(Paint.Style.FILL);
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setFakeBoldText(true);
                    p.setTextSize(s * 0.30f);
                    c.drawText("4K", s * 0.5f, s * 0.60f, p);
                    p.setFakeBoldText(false);
                    break;
                }
                case PIP: {
                    c.drawRoundRect(new RectF(s * 0.08f, s * 0.2f, s * 0.92f, s * 0.8f), s * 0.12f, s * 0.12f, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawRoundRect(new RectF(s * 0.5f, s * 0.46f, s * 0.82f, s * 0.7f), s * 0.06f, s * 0.06f, p);
                    break;
                }
                case TV: {
                    c.drawRoundRect(new RectF(s * 0.1f, s * 0.3f, s * 0.9f, s * 0.82f), s * 0.12f, s * 0.12f, p);
                    c.drawLine(s * 0.5f, s * 0.3f, s * 0.32f, s * 0.12f, p);
                    c.drawLine(s * 0.5f, s * 0.3f, s * 0.68f, s * 0.12f, p);
                    p.setStyle(Paint.Style.FILL);
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setFakeBoldText(true);
                    p.setTextSize(s * 0.26f);
                    c.drawText("HD", s * 0.5f, s * 0.67f, p);
                    p.setFakeBoldText(false);
                    break;
                }
                case SUPPORT: {
                    path.reset();
                    path.addArc(new RectF(s * 0.16f, s * 0.14f, s * 0.84f, s * 0.82f), 180f, 180f);
                    c.drawPath(path, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawRoundRect(new RectF(s * 0.1f, s * 0.46f, s * 0.28f, s * 0.74f), s * 0.07f, s * 0.07f, p);
                    c.drawRoundRect(new RectF(s * 0.72f, s * 0.46f, s * 0.9f, s * 0.74f), s * 0.07f, s * 0.07f, p);
                    p.setStyle(Paint.Style.STROKE);
                    path.reset();
                    path.moveTo(s * 0.82f, s * 0.72f);
                    path.quadTo(s * 0.8f, s * 0.9f, s * 0.54f, s * 0.9f);
                    c.drawPath(path, p);
                    break;
                }
                default: {   // BOLT
                    p.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(s * 0.56f, s * 0.06f);
                    path.lineTo(s * 0.22f, s * 0.54f);
                    path.lineTo(s * 0.47f, s * 0.54f);
                    path.lineTo(s * 0.40f, s * 0.94f);
                    path.lineTo(s * 0.78f, s * 0.42f);
                    path.lineTo(s * 0.53f, s * 0.42f);
                    path.close();
                    c.drawPath(path, p);
                }
            }
            c.restore();
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
