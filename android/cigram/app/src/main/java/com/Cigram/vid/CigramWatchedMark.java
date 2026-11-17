package com.Cigram.vid;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * v5.29: "watched" mark for an episode poster. A green layer covers the whole poster (its full
 * width and height) with a white check icon in the middle. Used on the series page and in the
 * player's episode list; the watched state itself always comes from the logged-in account.
 */
public final class CigramWatchedMark {

    private static final String TAG = "cigram_watched_mark";
    private static final int GREEN_LAYER = 0xB31E9E61;

    private CigramWatchedMark() { }

    /** Adds the mark over everything inside box (once; calling it again does nothing). */
    public static void cover(FrameLayout box) {
        if (box == null || box.findViewWithTag(TAG) != null) return;
        View mark = new View(box.getContext());
        mark.setTag(TAG);
        mark.setBackground(new Mark());
        mark.setContentDescription("تمت المشاهدة");
        mark.setClickable(false);
        mark.setFocusable(false);
        box.addView(mark, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Removes the mark (episode no longer counted as watched). */
    public static void clear(FrameLayout box) {
        if (box == null) return;
        View mark = box.findViewWithTag(TAG);
        if (mark != null) box.removeView(mark);
    }

    static final class Mark extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Mark() {
            fill.setColor(GREEN_LAYER);
            ring.setColor(0xFF1E9E61);
            tick.setColor(0xFFFFFFFF);
            tick.setStyle(Paint.Style.STROKE);
            tick.setStrokeCap(Paint.Cap.ROUND);
            tick.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            c.drawRect(b, fill);
            float size = Math.min(b.width(), b.height()) * 0.52f;
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float r = size / 2f;
            c.drawCircle(cx, cy, r, ring);
            tick.setStrokeWidth(Math.max(2f, size * 0.11f));
            path.reset();
            path.moveTo(cx - r * 0.45f, cy + r * 0.02f);
            path.lineTo(cx - r * 0.12f, cy + r * 0.34f);
            path.lineTo(cx + r * 0.48f, cy - r * 0.30f);
            c.drawPath(path, tick);
        }

        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
