package com.Cigram.vid;

import android.animation.ValueAnimator;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

/**
 * The «سيكرام» wordmark of the Home header.
 *
 * Heavy letters filled with a two-way gradient: from top to bottom the ink goes from a cool grey to almost white, and
 * across the word a soft band of light drifts from the right to the left, very slowly. Only the colours move — the
 * letters themselves never do (no scale, no translation, no alpha), so the header stays perfectly still.
 *
 * In the Light theme the same drawing is made with dark inks, otherwise the word would vanish on a white bar.
 * The animation runs only while the word is attached and visible, and stops for good when animations are off.
 */
final class CigramWordmark {

    private static final String TAG_KEY = "cigram_wordmark";
    /** One full pass of the light across the word. Slow on purpose. */
    private static final long LOOP_MS = 14000L;

    private CigramWordmark() { }

    static void install(final TextView word) {
        if (word == null || TAG_KEY.equals(word.getTag())) return;
        word.setTag(TAG_KEY);
        try {
            word.setText("سيكرام");
            word.setIncludeFontPadding(false);
            word.setTextSize(23f);
            Typeface bold = CigramTypefaces.bold();
            word.setTypeface(bold != null ? bold : Typeface.DEFAULT_BOLD, Typeface.BOLD);
            word.getPaint().setFakeBoldText(true);
            word.setLetterSpacing(0f);
        } catch (Throwable ignored) { }

        final Matrix shift = new Matrix();
        final float[] phase = {0f};
        final Runnable paint = new Runnable() {
            @Override public void run() {
                int w = word.getWidth(), h = word.getHeight();
                if (w <= 0 || h <= 0) return;
                boolean light = false;
                try { light = CigramTheme.isLight(word.getContext()); } catch (Throwable ignored) { }
                int top = light ? 0xFF7A7A80 : 0xFF8E8E96, bottom = light ? 0xFF0B0B0C : 0xFFFFFFFF;
                int dim = light ? 0xFFFFFFFF : 0xFFB9B9C0, lit = light ? 0xFFB4B4BA : 0xFFFFFFFF;
                Shader vertical = new LinearGradient(0, h * 0.12f, 0, h * 0.9f, top, bottom, Shader.TileMode.CLAMP);
                // twice the word's width, mirrored: the band re-enters from the right as it leaves on the left
                LinearGradient band = new LinearGradient(0, 0, w * 2f, 0,
                        new int[]{dim, lit, dim, lit, dim}, new float[]{0f, 0.25f, 0.5f, 0.75f, 1f}, Shader.TileMode.REPEAT);
                shift.setTranslate(-phase[0] * w * 2f, 0f);     // right → left
                band.setLocalMatrix(shift);
                word.getPaint().setShader(new ComposeShader(vertical, band, PorterDuff.Mode.MULTIPLY));
                word.invalidate();
            }
        };
        word.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) { paint.run(); }
        });
        word.post(paint);

        boolean animate = true;
        try { animate = AdsUi.animationsOn(word.getContext()); } catch (Throwable ignored) { }
        if (!animate) return;
        final ValueAnimator drift = ValueAnimator.ofFloat(0f, 1f);
        drift.setDuration(LOOP_MS);
        drift.setRepeatCount(ValueAnimator.INFINITE);
        drift.setInterpolator(new LinearInterpolator());
        final long[] lastFrame = {0L};
        drift.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastFrame[0] < 40L) return;            // 25 fps is plenty for a drift this slow
                lastFrame[0] = now;
                if (!word.isShown()) return;
                phase[0] = ((Float) a.getAnimatedValue()).floatValue();
                paint.run();
            }
        });
        word.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { if (!drift.isStarted()) drift.start(); }
            @Override public void onViewDetachedFromWindow(View v) { drift.cancel(); }
        });
        if (word.isAttachedToWindow()) drift.start();
    }
}
