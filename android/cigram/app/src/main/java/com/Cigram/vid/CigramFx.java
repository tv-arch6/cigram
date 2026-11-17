package com.Cigram.vid;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import java.util.Random;

/**
 * The app's small motion pieces, all drawn in code (white ink, no images, no emoji):
 *
 *   {@link #celebrate}  — shapes fly in from the left and the right edge of the screen and are gone at once
 *                          (Cigram Plus has just been activated);
 *   {@link #reaction}   — a big thumb springs up in the middle of the page with the new count under it
 *                          («أعجبني» / «لا يعجبني» on the details page);
 *   {@link Thumb}       — the like / dislike icon (one drawing, flipped);
 *   {@link Heart}       — a heart that appears whole, breaks in two and sheds tears.
 *
 * Everything lives in a throw-away overlay on the window's content view and removes itself when it ends; nothing
 * here blocks touches, and nothing runs when the system animations are switched off.
 */
final class CigramFx {

    private CigramFx() { }

    private static FrameLayout root(Activity a) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return null;
        View v = a.findViewById(android.R.id.content);
        return v instanceof FrameLayout ? (FrameLayout) v : null;
    }

    private static boolean motion(Context c) {
        try { return AdsUi.animationsOn(c); } catch (Throwable e) { return true; }
    }

    private static void drop(final View v) {
        try {
            if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
        } catch (Throwable ignored) { }
    }

    // ================================================================== celebration (left + right bursts)

    static void celebrate(Activity a) {
        final FrameLayout root = root(a);
        if (root == null || !motion(a)) return;
        final Burst burst = new Burst(a);
        root.addView(burst, new FrameLayout.LayoutParams(-1, -1));
        ValueAnimator run = ValueAnimator.ofFloat(0f, 1f);
        run.setDuration(1500L);
        run.setInterpolator(new LinearInterpolator());
        run.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator v) { burst.t = ((Float) v.getAnimatedValue()).floatValue(); burst.invalidate(); }
        });
        run.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { drop(burst); }
        });
        run.start();
    }

    private static final class Burst extends View {
        private static final int COUNT = 46;
        private final float[] vx = new float[COUNT], vy = new float[COUNT], spin = new float[COUNT], size = new float[COUNT];
        private final int[] shape = new int[COUNT], ink = new int[COUNT];
        private final boolean[] fromLeft = new boolean[COUNT];
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        float t;

        Burst(Context c) {
            super(c);
            setClickable(false);
            float d = c.getResources().getDisplayMetrics().density;
            Random r = new Random();
            int[] inks = {0xFFFFFFFF, 0xFFE5E5EA, 0xFFAEAEB2, 0xFFFFFFFF, 0xFF8E8E93};
            for (int i = 0; i < COUNT; i++) {
                fromLeft[i] = i % 2 == 0;
                double angle = Math.toRadians(-20 - r.nextInt(55));          // up and inwards
                float speed = (520 + r.nextInt(620)) * d;
                vx[i] = (float) (Math.cos(angle) * speed) * (fromLeft[i] ? 1f : -1f);
                vy[i] = (float) (Math.sin(angle) * speed);
                spin[i] = (r.nextFloat() - 0.5f) * 900f;
                size[i] = (7 + r.nextInt(9)) * d;
                shape[i] = r.nextInt(5);
                ink[i] = inks[r.nextInt(inks.length)];
            }
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float d = getResources().getDisplayMetrics().density;
            float time = t * 1.5f;                                 // seconds
            float gravity = 1500f * d;
            float alpha = t < 0.72f ? 1f : Math.max(0f, 1f - (t - 0.72f) / 0.28f);
            for (int i = 0; i < COUNT; i++) {
                float x = (fromLeft[i] ? -size[i] : w + size[i]) + vx[i] * time;
                float y = h * 0.62f + vy[i] * time + 0.5f * gravity * time * time;
                if (y > h + size[i]) continue;
                p.setColor(ink[i]);
                p.setAlpha((int) (255 * alpha));
                c.save();
                c.translate(x, y);
                c.rotate(spin[i] * time);
                float s = size[i];
                switch (shape[i]) {
                    case 0:                                        // ribbon
                        p.setStyle(Paint.Style.FILL);
                        c.drawRoundRect(new RectF(-s, -s * 0.28f, s, s * 0.28f), s * 0.14f, s * 0.14f, p);
                        break;
                    case 1:                                        // ring
                        p.setStyle(Paint.Style.STROKE);
                        p.setStrokeWidth(s * 0.22f);
                        c.drawCircle(0, 0, s * 0.6f, p);
                        break;
                    case 2:                                        // triangle
                        p.setStyle(Paint.Style.FILL);
                        path.reset();
                        path.moveTo(0, -s * 0.8f); path.lineTo(s * 0.75f, s * 0.6f); path.lineTo(-s * 0.75f, s * 0.6f); path.close();
                        c.drawPath(path, p);
                        break;
                    case 3:                                        // four-point spark
                        p.setStyle(Paint.Style.FILL);
                        path.reset();
                        path.moveTo(0, -s); path.quadTo(0, 0, s, 0); path.quadTo(0, 0, 0, s); path.quadTo(0, 0, -s, 0); path.quadTo(0, 0, 0, -s);
                        c.drawPath(path, p);
                        break;
                    default:                                       // dot
                        p.setStyle(Paint.Style.FILL);
                        c.drawCircle(0, 0, s * 0.42f, p);
                }
                c.restore();
            }
        }
    }

    // ================================================================== like / dislike icon

    /** A thumb drawn with white strokes; {@code down} flips it. No background of its own. */
    static final class Thumb extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final boolean down;
        private int color = 0xFFFFFFFF;
        private boolean filled;

        Thumb(Context c, boolean down) {
            super(c);
            this.down = down;
        }

        void set(int color, boolean filled) { this.color = color; this.filled = filled; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            if (s <= 0) return;
            float u = s / 24f;
            c.save();
            c.translate((getWidth() - s) / 2f, (getHeight() - s) / 2f);
            if (down) c.rotate(180f, s / 2f, s / 2f);
            c.scale(u, u);
            p.setColor(color);
            p.setStrokeWidth(1.9f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStyle(filled ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
            // the hand
            path.reset();
            path.moveTo(8.2f, 10.4f);
            path.lineTo(11.6f, 3.6f);
            path.cubicTo(13.4f, 3.6f, 14.3f, 5f, 14f, 6.6f);
            path.lineTo(13.4f, 9.6f);
            path.lineTo(18.4f, 9.6f);
            path.cubicTo(19.9f, 9.6f, 20.9f, 11f, 20.5f, 12.4f);
            path.lineTo(18.9f, 18.4f);
            path.cubicTo(18.6f, 19.6f, 17.6f, 20.4f, 16.4f, 20.4f);
            path.lineTo(8.2f, 20.4f);
            path.close();
            c.drawPath(path, p);
            // the cuff
            c.drawRoundRect(new RectF(3.4f, 10.4f, 8.2f, 20.4f), 1.2f, 1.2f, p);
            c.restore();
        }
    }

    // ================================================================== the reaction burst of the details page

    /** A big thumb springs up in the middle of the page with the new count; quick, elastic, gone in under a second. */
    static void reaction(Activity a, boolean like, int count) {
        final FrameLayout root = root(a);
        if (root == null) return;
        float d = a.getResources().getDisplayMetrics().density;
        final FrameLayout layer = new FrameLayout(a);
        layer.setClickable(false);
        final Thumb thumb = new Thumb(a, !like);
        thumb.set(0xFFFFFFFF, true);
        int size = (int) (96 * d);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(size, size, Gravity.CENTER);
        tl.bottomMargin = (int) (26 * d);
        layer.addView(thumb, tl);
        final android.widget.TextView number = new android.widget.TextView(a);
        number.setText(String.valueOf(Math.max(0, count)));
        number.setTextColor(0xFFFFFFFF);
        number.setTextSize(26f);
        number.setTypeface(CigramTypefaces.bold() != null ? CigramTypefaces.bold() : Typeface.DEFAULT_BOLD, Typeface.BOLD);
        number.setShadowLayer(8f * d, 0f, 2f * d, 0xB3000000);
        number.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        nl.topMargin = (int) (52 * d);
        layer.addView(number, nl);
        root.addView(layer, new FrameLayout.LayoutParams(-1, -1));

        if (!motion(a)) {
            layer.postDelayed(new Runnable() { @Override public void run() { drop(layer); } }, 700L);
            return;
        }
        thumb.setScaleX(0.2f);
        thumb.setScaleY(0.2f);
        thumb.setAlpha(0f);
        thumb.setRotation(like ? -18f : 18f);
        thumb.animate().scaleX(1f).scaleY(1f).alpha(0.95f).rotation(0f).setDuration(260L)
                .setInterpolator(new OvershootInterpolator(3.2f)).start();
        number.setAlpha(0f);
        number.setTranslationY(18 * d);
        number.animate().alpha(1f).translationY(0f).setStartDelay(110L).setDuration(200L)
                .setInterpolator(new DecelerateInterpolator()).start();
        layer.postDelayed(new Runnable() {
            @Override public void run() {
                layer.animate().alpha(0f).scaleX(1.12f).scaleY(1.12f).setDuration(220L).withEndAction(new Runnable() {
                    @Override public void run() { drop(layer); }
                }).start();
            }
        }, 720L);
    }

    // ================================================================== the breaking heart

    /**
     * A white heart: whole first, then it splits along a jagged crack, the halves lean apart and tears fall.
     * {@link #play} runs it once (about 2.4 s).
     */
    static final class Heart extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tear = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path whole = new Path(), left = new Path(), right = new Path(), drop = new Path();
        private float t;      // 0..1

        Heart(Context c) {
            super(c);
            fill.setColor(0xFFFFFFFF);
            fill.setStyle(Paint.Style.FILL);
            tear.setColor(0xFFD1D1D6);
            tear.setStyle(Paint.Style.FILL);
        }

        void play(final Runnable end) {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(2400L);
            a.setInterpolator(new LinearInterpolator());
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator v) { t = ((Float) v.getAnimatedValue()).floatValue(); invalidate(); }
            });
            a.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { if (end != null) end.run(); }
            });
            a.start();
        }

        private void build(float s) {
            // heart in a 100-unit box
            whole.reset();
            whole.moveTo(50, 88);
            whole.cubicTo(18, 64, 6, 46, 6, 30);
            whole.cubicTo(6, 16, 17, 8, 28, 8);
            whole.cubicTo(37, 8, 45, 13, 50, 21);
            whole.cubicTo(55, 13, 63, 8, 72, 8);
            whole.cubicTo(83, 8, 94, 16, 94, 30);
            whole.cubicTo(94, 46, 82, 64, 50, 88);
            whole.close();
            Path crackLeft = new Path();
            crackLeft.moveTo(-10, -10); crackLeft.lineTo(50, -10); crackLeft.lineTo(50, 21); crackLeft.lineTo(42, 36);
            crackLeft.lineTo(56, 50); crackLeft.lineTo(44, 64); crackLeft.lineTo(53, 76); crackLeft.lineTo(50, 110);
            crackLeft.lineTo(-10, 110); crackLeft.close();
            left.reset(); right.reset();
            if (android.os.Build.VERSION.SDK_INT >= 19) {
                left.op(whole, crackLeft, Path.Op.INTERSECT);
                right.op(whole, crackLeft, Path.Op.DIFFERENCE);
            } else {
                left.set(whole);
            }
            drop.reset();
            drop.moveTo(0, -7); drop.cubicTo(5, 0, 5, 6, 0, 6); drop.cubicTo(-5, 6, -5, 0, 0, -7); drop.close();
        }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight() * 0.8f);
            if (s <= 0) return;
            if (whole.isEmpty()) build(s);
            float u = s / 100f;
            c.save();
            c.translate((getWidth() - s) / 2f, 0);
            c.scale(u, u);
            // 0–0.28 appear with a beat · 0.28–0.45 hold · 0.45–0.62 crack · 0.62–1 lean apart + tears
            float appear = Math.min(1f, t / 0.18f);
            float beat = t < 0.45f ? 1f + 0.06f * (float) Math.sin(t / 0.45f * Math.PI * 4) : 1f;
            float split = t < 0.45f ? 0f : Math.min(1f, (t - 0.45f) / 0.25f);
            float ease = split * split * (3f - 2f * split);
            float scale = (0.4f + 0.6f * appear) * beat;
            c.scale(scale, scale, 50, 50);
            fill.setAlpha((int) (255 * appear));
            if (split <= 0f) {
                c.drawPath(whole, fill);
            } else {
                c.save();
                c.translate(-7f * ease, 3f * ease);
                c.rotate(-11f * ease, 50, 88);
                c.drawPath(left, fill);
                c.restore();
                c.save();
                c.translate(7f * ease, 3f * ease);
                c.rotate(11f * ease, 50, 88);
                c.drawPath(right, fill);
                c.restore();
            }
            // tears: three drops, one after the other, falling from under the heart
            if (t > 0.6f) {
                float[] xs = {36f, 62f, 49f};
                float[] starts = {0.6f, 0.7f, 0.8f};
                for (int i = 0; i < 3; i++) {
                    float k = (t - starts[i]) / 0.2f;
                    if (k <= 0f || k >= 1f) continue;
                    tear.setAlpha((int) (255 * (1f - k * k)));
                    c.save();
                    c.translate(xs[i], 92f + 26f * k * k);
                    c.drawPath(drop, tear);
                    c.restore();
                }
            }
            c.restore();
        }
    }
}
