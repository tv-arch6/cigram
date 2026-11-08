package com.Cigram.vid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

/**
 * v10.67: the "raised" 3D surface of the account page. A button or a card is drawn as a solid
 * block: a dark side (the thickness), a face with a light-to-dark gradient, a glossy upper half
 * and a thin lit rim. When it is pressed the face sinks into the block, like a real key.
 *
 * Pure canvas drawing (no bitmaps), the gradients are rebuilt only when the size changes.
 */
public final class CigramDepth {

    private CigramDepth() { }

    public static int shade(int color, float factor) {
        int a = (color >>> 24) & 0xFF;
        int r = Math.min(255, Math.max(0, Math.round(((color >> 16) & 0xFF) * factor)));
        int g = Math.min(255, Math.max(0, Math.round(((color >> 8) & 0xFF) * factor)));
        int b = Math.min(255, Math.max(0, Math.round((color & 0xFF) * factor)));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int mix(int a, int b, float t) {
        float u = 1f - t;
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | (Math.round(ar * u + br * t) << 16) | (Math.round(ag * u + bg * t) << 8)
                | Math.round(ab * u + bb * t);
    }

    /** A raised block: face colours top → bottom, the dark side, corner radius and thickness in dp. */
    public static Drawable raised(Context c, int faceTop, int faceBottom, float radiusDp, float depthDp) {
        float d = c.getResources().getDisplayMetrics().density;
        return new Raised(faceTop, faceBottom, shade(faceBottom, 0.42f), radiusDp * d, depthDp * d);
    }

    /** The standard navy row / card of the account page. */
    public static Drawable card(Context c) {
        if (CigramTheme.isLight(c)) {   // Light mode: white block with a soft grey edge
            float d = c.getResources().getDisplayMetrics().density;
            return new Raised(0xFFFFFFFF, 0xFFF2F4F6, 0xFFD3D9DE, 18f * d, 3f * d);
        }
        return raised(c, 0xFF174660, 0xFF1C1C20, 18f, 4f);
    }

    /** A coloured "jewel" (icon tile) in the accent colour. */
    public static Drawable jewel(Context c, int accent) {
        return raised(c, mix(accent, 0xFFFFFFFF, 0.22f), shade(accent, 0.78f), 13f, 3f);
    }

    /**
     * Press feedback for a raised block: the block sinks a little and comes back. The event is not
     * consumed, so the click still goes through. Also lifts the shadow while it is pressed.
     */
    public static void attachPress(View view) {
        if (view == null) return;
        final float d = view.getResources().getDisplayMetrics().density;
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(0.985f).scaleY(0.985f).translationZ(-1.5f * d).setDuration(70L).start();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(140L).start();
                }
                return false;
            }
        });
    }

    static final class Raised extends Drawable {
        private final int top;
        private final int bottom;
        private final int side;
        private final float radius;
        private final float depth;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF block = new RectF();
        private final RectF face = new RectF();
        private final RectF gloss = new RectF();
        private Shader faceShader;
        private Shader glossShader;
        private Shader rimShader;
        private boolean pressed;
        private int alpha = 255;

        Raised(int top, int bottom, int side, float radius, float depth) {
            this.top = top;
            this.bottom = bottom;
            this.side = side;
            this.radius = radius;
            this.depth = depth;
        }

        @Override protected void onBoundsChange(Rect b) {
            super.onBoundsChange(b);
            faceShader = null;
            glossShader = null;
            rimShader = null;
        }

        @Override public boolean isStateful() { return true; }

        @Override protected boolean onStateChange(int[] state) {
            boolean now = false;
            for (int s : state) if (s == android.R.attr.state_pressed) now = true;
            if (now == pressed) return false;
            pressed = now;
            faceShader = null;
            glossShader = null;
            rimShader = null;
            invalidateSelf();
            return true;
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            float sink = pressed ? depth * 0.7f : 0f;
            float lift = depth - sink;
            block.set(b);
            face.set(b.left, b.top + sink, b.right, b.bottom - lift);

            // the thickness: the dark side under the face
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(side);
            paint.setAlpha(alpha);
            canvas.drawRoundRect(block, radius, radius, paint);

            if (faceShader == null) {
                faceShader = new LinearGradient(0, face.top, 0, face.bottom, top, bottom, Shader.TileMode.CLAMP);
                glossShader = new LinearGradient(0, face.top, 0, face.top + face.height() * 0.55f,
                        0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
                rimShader = new LinearGradient(0, face.top, 0, face.bottom,
                        new int[]{0x66FFFFFF, 0x10FFFFFF, 0x00FFFFFF}, new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP);
            }
            paint.setShader(faceShader);
            paint.setAlpha(alpha);
            canvas.drawRoundRect(face, radius, radius, paint);

            // glossy upper half
            gloss.set(face.left + 1f, face.top + 1f, face.right - 1f, face.top + face.height() * 0.55f);
            paint.setShader(glossShader);
            paint.setAlpha(alpha);
            canvas.drawRoundRect(gloss, radius, radius, paint);

            // thin lit rim, brightest on top
            paint.setShader(rimShader);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, depth * 0.3f));
            paint.setAlpha(alpha);
            canvas.drawRoundRect(face.left + 0.5f, face.top + 0.5f, face.right - 0.5f, face.bottom - 0.5f,
                    radius, radius, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
        }

        @Override public void getOutline(Outline outline) {
            outline.setRoundRect(getBounds(), radius);
            outline.setAlpha(0.9f);
        }

        @Override public void setAlpha(int a) {
            alpha = a;
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter f) { }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
