package com.Cigram.vid;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

/**
 * Lightweight loading placeholder ("skeleton") with a soft moving highlight.
 *
 * - One shared time base, so all skeletons on screen shimmer in sync.
 * - Stops by itself as soon as it is replaced or detached (scheduleSelf needs a callback).
 * - Falls back to a static block on low-RAM devices or when animations are turned off.
 */
public final class CigramSkeleton extends Drawable implements Runnable {

    private static final int BASE_COLOR = 0xFF222226;
    private static final int HIGHLIGHT_COLOR = 0x33FFFFFF;
    private static final long PERIOD_MS = 1300L;
    private static final long FRAME_MS = 50L;          // 20 fps is plenty for a soft shimmer
    private static final long MAX_ANIMATE_MS = 15000L;  // a placeholder that stays this long stops moving

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shimmerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final float radius;
    private final boolean animate;
    private LinearGradient gradient;
    private int gradientWidth = -1;
    private boolean running = true;
    private final long createdAt = SystemClock.uptimeMillis();

    private CigramSkeleton(float radiusPx, boolean animate, boolean light) {
        this.radius = radiusPx;
        this.animate = animate;
        basePaint.setColor(light ? 0xFFE3E7EA : BASE_COLOR);   // v10.70: light placeholder in Light mode
        highlight = light ? 0x99FFFFFF : HIGHLIGHT_COLOR;
    }

    private final int highlight;

    public static CigramSkeleton create(Context context, float radiusDp) {
        float density = 1f;
        boolean animate = true;
        try {
            density = context.getResources().getDisplayMetrics().density;
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.isLowRamDevice()) animate = false;
            float scale = android.provider.Settings.Global.getFloat(
                    context.getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            if (scale == 0f) animate = false;
        } catch (Throwable ignored) { }
        return new CigramSkeleton(radiusDp * density, animate, CigramTheme.isLight(context));
    }

    /** Gives the view an animated skeleton background (rounded, clipped). */
    public static void apply(View view, float radiusDp) {
        if (view == null) return;
        try {
            view.setBackground(create(view.getContext(), radiusDp));
            view.setClipToOutline(true);
        } catch (Throwable ignored) { }
    }

    @Override
    public void draw(Canvas canvas) {
        rect.set(getBounds());
        if (rect.width() <= 0f || rect.height() <= 0f) return;
        canvas.drawRoundRect(rect, radius, radius, basePaint);
        if (!animate) return;

        int band = Math.max(1, (int) (rect.width() * 0.6f));
        if (gradient == null || gradientWidth != band) {
            gradient = new LinearGradient(0f, 0f, band, 0f,
                    new int[]{0x00FFFFFF, highlight, 0x00FFFFFF},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            gradientWidth = band;
            shimmerPaint.setShader(gradient);
        }
        float phase = (SystemClock.uptimeMillis() % PERIOD_MS) / (float) PERIOD_MS;
        float x = rect.left - band + phase * (rect.width() + band);
        matrix.setTranslate(x, 0f);
        gradient.setLocalMatrix(matrix);
        canvas.drawRoundRect(rect, radius, radius, shimmerPaint);

        if (running && SystemClock.uptimeMillis() - createdAt < MAX_ANIMATE_MS) {
            scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_MS);
        }
    }

    @Override
    public void run() {
        invalidateSelf();
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        running = visible;
        if (visible) invalidateSelf(); else unscheduleSelf(this);
        return changed;
    }

    @Override
    public void getOutline(Outline outline) {
        outline.setRoundRect(getBounds(), radius);
    }

    @Override
    public void setAlpha(int alpha) {
        basePaint.setAlpha(alpha);
        shimmerPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        basePaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
