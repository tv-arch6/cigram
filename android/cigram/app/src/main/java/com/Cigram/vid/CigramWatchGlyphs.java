package com.Cigram.vid;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;

/**
 * The small white symbols scattered inside the red «شاهد هَسه» button.
 *
 * Nine line drawings — clapperboard, crescent, ring, teddy bear, sparkle, snowflake, flame, bubbles, parachute — at
 * 78 % white, without any background, spread over the left and the right thirds of the button so the label in the
 * middle stays clear. They are drawn in code (no emoji font, the same on every phone) and they do not move.
 */
final class CigramWatchGlyphs extends Drawable {

    private static final int INK = 0xC7FFFFFF;      // white at 78 %

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();
    private final float density;

    private CigramWatchGlyphs(float density) {
        this.density = density;
        p.setColor(INK);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    /** {@code base} (the red pill) with the symbols on top of it. */
    static Drawable over(Drawable base, float density) {
        if (base == null) return null;
        return new LayerDrawable(new Drawable[]{base, new CigramWatchGlyphs(density)});
    }

    // {x as a fraction of the half width measured from the edge, y fraction of the height, size dp, rotation, kind}
    private static final float[][] SPOTS = {
            {0.10f, 0.34f, 15f, -14f, 0}, {0.30f, 0.70f, 12f, 10f, 1}, {0.50f, 0.30f, 12f, -8f, 4},
            {0.18f, 0.74f, 11f, 0f, 7}, {0.42f, 0.56f, 13f, 12f, 6}, {0.64f, 0.72f, 11f, -6f, 2},
            {0.12f, 0.50f, 14f, 8f, 3}, {0.34f, 0.28f, 12f, 0f, 5}, {0.52f, 0.68f, 13f, -10f, 8},
            {0.22f, 0.30f, 10f, 14f, 4}, {0.66f, 0.36f, 10f, 0f, 7}, {0.06f, 0.76f, 10f, 0f, 5}};

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        float w = b.width(), h = b.height();
        if (w <= 0 || h <= 0) return;
        float half = w / 2f, clear = Math.min(half * 0.42f, 64f * density);      // the label's zone
        float usable = half - clear;
        for (int i = 0; i < SPOTS.length; i++) {
            float[] s = SPOTS[i];
            boolean rightSide = i >= 6;
            float fromEdge = s[0] / 0.7f * usable;
            if (fromEdge > usable - 6f * density) continue;
            float x = rightSide ? b.right - fromEdge - 8f * density : b.left + fromEdge + 8f * density;
            float y = b.top + s[1] * h;
            float size = Math.min(s[2] * density, h * 0.34f);
            c.save();
            c.translate(x, y);
            c.rotate(s[3]);
            c.scale(size / 24f, size / 24f);
            c.translate(-12f, -12f);
            p.setStrokeWidth(2f);
            glyph(c, (int) s[4]);
            c.restore();
        }
    }

    /** One symbol in a 24-unit box. */
    private void glyph(Canvas c, int kind) {
        path.reset();
        switch (kind) {
            case 0:                                                   // clapperboard
                rf.set(3.5f, 9.5f, 20.5f, 19.5f); c.drawRoundRect(rf, 1.6f, 1.6f, p);
                path.moveTo(3.5f, 9.5f); path.lineTo(19.6f, 4.6f); path.lineTo(20.4f, 7.2f); path.lineTo(4.3f, 12.1f);
                c.drawPath(path, p);
                c.drawLine(8.2f, 8f, 9.6f, 10.4f, p); c.drawLine(13.2f, 6.5f, 14.6f, 8.9f, p);
                break;
            case 1:                                                   // crescent
                path.moveTo(16.5f, 4.2f);
                path.cubicTo(9f, 4.6f, 5.4f, 11.6f, 8.4f, 16.6f);
                path.cubicTo(11f, 21f, 17.4f, 21.4f, 20.2f, 17.4f);
                path.cubicTo(14.4f, 17.6f, 11.6f, 10.4f, 16.5f, 4.2f);
                c.drawPath(path, p);
                break;
            case 2:                                                   // ring with a stone
                c.drawCircle(12f, 14.6f, 6.2f, p);
                path.moveTo(9.4f, 6.2f); path.lineTo(12f, 3.2f); path.lineTo(14.6f, 6.2f); path.lineTo(12f, 8.6f); path.close();
                c.drawPath(path, p);
                break;
            case 3:                                                   // teddy bear
                c.drawCircle(12f, 13f, 6.4f, p);
                c.drawCircle(6.4f, 7.2f, 2.3f, p); c.drawCircle(17.6f, 7.2f, 2.3f, p);
                rf.set(9.6f, 13.2f, 14.4f, 17f); c.drawOval(rf, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(9.6f, 11.4f, 0.9f, p); c.drawCircle(14.4f, 11.4f, 0.9f, p);
                p.setStyle(Paint.Style.STROKE);
                break;
            case 4:                                                   // sparkle
                path.moveTo(12f, 3f); path.quadTo(12.6f, 11.4f, 21f, 12f); path.quadTo(12.6f, 12.6f, 12f, 21f);
                path.quadTo(11.4f, 12.6f, 3f, 12f); path.quadTo(11.4f, 11.4f, 12f, 3f);
                c.drawPath(path, p);
                break;
            case 5:                                                   // snowflake
                for (int i = 0; i < 3; i++) {
                    c.save();
                    c.rotate(i * 60f, 12f, 12f);
                    c.drawLine(12f, 3.2f, 12f, 20.8f, p);
                    c.drawLine(12f, 6.4f, 9.8f, 4.4f, p); c.drawLine(12f, 6.4f, 14.2f, 4.4f, p);
                    c.drawLine(12f, 17.6f, 9.8f, 19.6f, p); c.drawLine(12f, 17.6f, 14.2f, 19.6f, p);
                    c.restore();
                }
                break;
            case 6:                                                   // flame
                path.moveTo(12f, 3f);
                path.cubicTo(13f, 7.6f, 18.4f, 9.6f, 18.4f, 14.8f);
                path.cubicTo(18.4f, 18.6f, 15.6f, 21f, 12f, 21f);
                path.cubicTo(8.4f, 21f, 5.6f, 18.6f, 5.6f, 14.8f);
                path.cubicTo(5.6f, 12.2f, 7f, 10.6f, 8.6f, 9.4f);
                path.cubicTo(9f, 11.6f, 10f, 12.4f, 11f, 12.6f);
                path.cubicTo(10.2f, 9.2f, 10.6f, 5.6f, 12f, 3f);
                c.drawPath(path, p);
                break;
            case 7:                                                   // bubbles
                c.drawCircle(9f, 14.4f, 5.2f, p);
                c.drawCircle(17.2f, 8f, 3.2f, p);
                c.drawCircle(17.6f, 17.8f, 1.9f, p);
                break;
            default:                                                  // parachute
                path.moveTo(3.4f, 11.4f);
                path.cubicTo(3.4f, 1.6f, 20.6f, 1.6f, 20.6f, 11.4f);
                path.close();
                c.drawPath(path, p);
                c.drawLine(3.4f, 11.4f, 12f, 19.4f, p); c.drawLine(20.6f, 11.4f, 12f, 19.4f, p); c.drawLine(12f, 11.4f, 12f, 19.4f, p);
                rf.set(10.4f, 19.4f, 13.6f, 21.8f); c.drawRoundRect(rf, 0.8f, 0.8f, p);
        }
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter f) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
