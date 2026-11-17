package com.my.newproject;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Extra line icons for the new screens (same 24x24 grid and stroke as CgIcons).
 * Kinds below 100 are CgIcons kinds and are drawn by CgIcons.
 */
final class NxIcons {

    private NxIcons() {}

    static final int ARROW_UP = 100;
    static final int ARROW_DOWN = 101;
    static final int LAYERS = 102;
    static final int GRID = 103;
    static final int IDCARD = 104;
    static final int PASTE = 105;
    static final int DOTS = 106;
    static final int ANTENNA = 107;
    static final int PHONE = 108;
    static final int FLAT = 109;
    static final int LIST = 110;
    static final int SPARK = 111;

    static Drawable icon(int kind, int color) {
        if (kind < 100) return CgIcons.icon(kind, color);
        return new D(kind, color);
    }

    private static final class D extends Drawable {
        private final int kind;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();
        private int alpha = 255;

        D(int kind, int color) {
            this.kind = kind;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeWidth(2f);
            stroke.setColor(color);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(color);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            float s = Math.min(b.width(), b.height()) / 24f;
            stroke.setAlpha(alpha);
            fill.setAlpha(alpha);
            c.save();
            c.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f);
            c.scale(s, s);
            path.reset();
            switch (kind) {
                case ARROW_UP:
                    path.moveTo(12, 5);
                    path.lineTo(19, 14);
                    path.lineTo(5, 14);
                    path.close();
                    c.drawPath(path, fill);
                    c.drawRect(10, 13, 14, 19, fill);
                    break;
                case ARROW_DOWN:
                    path.moveTo(12, 19);
                    path.lineTo(19, 10);
                    path.lineTo(5, 10);
                    path.close();
                    c.drawPath(path, fill);
                    c.drawRect(10, 5, 14, 11, fill);
                    break;
                case FLAT:
                    r.set(5, 10.5f, 19, 13.5f);
                    c.drawRoundRect(r, 1.5f, 1.5f, fill);
                    break;
                case LAYERS:
                    path.moveTo(12, 3.5f);
                    path.lineTo(21, 8.2f);
                    path.lineTo(12, 13);
                    path.lineTo(3, 8.2f);
                    path.close();
                    c.drawPath(path, stroke);
                    path.reset();
                    path.moveTo(3, 12.2f);
                    path.lineTo(12, 17);
                    path.lineTo(21, 12.2f);
                    c.drawPath(path, stroke);
                    path.reset();
                    path.moveTo(3, 16.2f);
                    path.lineTo(12, 21);
                    path.lineTo(21, 16.2f);
                    c.drawPath(path, stroke);
                    break;
                case GRID:
                    for (int i = 0; i < 2; i++) {
                        for (int k = 0; k < 2; k++) {
                            r.set(4 + i * 9, 4 + k * 9, 11 + i * 9, 11 + k * 9);
                            c.drawRoundRect(r, 2, 2, stroke);
                        }
                    }
                    break;
                case IDCARD:
                    r.set(3, 5, 21, 19);
                    c.drawRoundRect(r, 2.5f, 2.5f, stroke);
                    c.drawCircle(8.5f, 10.5f, 2, stroke);
                    path.moveTo(5.5f, 16);
                    path.quadTo(8.5f, 12.8f, 11.5f, 16);
                    c.drawPath(path, stroke);
                    c.drawLine(14, 10, 18, 10, stroke);
                    c.drawLine(14, 13.5f, 18, 13.5f, stroke);
                    break;
                case PASTE:
                    r.set(5, 5, 19, 21);
                    c.drawRoundRect(r, 2, 2, stroke);
                    r.set(9, 3, 15, 7);
                    c.drawRoundRect(r, 1.5f, 1.5f, stroke);
                    c.drawLine(9, 12, 15, 12, stroke);
                    c.drawLine(9, 16, 13.5f, 16, stroke);
                    break;
                case DOTS:
                    c.drawCircle(6, 12, 1.7f, fill);
                    c.drawCircle(12, 12, 1.7f, fill);
                    c.drawCircle(18, 12, 1.7f, fill);
                    break;
                case ANTENNA:
                    c.drawCircle(12, 10, 1.8f, fill);
                    c.drawLine(12, 12, 8.5f, 21, stroke);
                    c.drawLine(12, 12, 15.5f, 21, stroke);
                    c.drawLine(9.6f, 18, 14.4f, 18, stroke);
                    r.set(7, 5, 17, 15);
                    c.drawArc(r, 200, 140, false, stroke);
                    r.set(3.5f, 1.5f, 20.5f, 18.5f);
                    c.drawArc(r, 205, 130, false, stroke);
                    break;
                case PHONE:
                    r.set(6.5f, 2.5f, 17.5f, 21.5f);
                    c.drawRoundRect(r, 2.5f, 2.5f, stroke);
                    c.drawLine(10.5f, 18.5f, 13.5f, 18.5f, stroke);
                    break;
                case LIST:
                    for (int i = 0; i < 3; i++) {
                        float y = 6.5f + i * 5.5f;
                        c.drawCircle(5, y, 1.3f, fill);
                        c.drawLine(9, y, 20, y, stroke);
                    }
                    break;
                case SPARK:
                    path.moveTo(3, 15);
                    path.lineTo(7.5f, 15);
                    path.lineTo(10, 7);
                    path.lineTo(14, 19);
                    path.lineTo(16.5f, 12);
                    path.lineTo(21, 12);
                    c.drawPath(path, stroke);
                    break;
                default:
                    break;
            }
            c.restore();
        }

        @Override
        public void setAlpha(int a) {
            alpha = a;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            stroke.setColorFilter(cf);
            fill.setColorFilter(cf);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
