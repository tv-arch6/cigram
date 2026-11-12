package com.Cigram.vid;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.MotionEvent;
import android.view.View;

/**
 * نفس سطح «Liquid Glass» المستخدم في صفحة الحساب (CigramGlass داخل Logic) لكن ككلاس Java قابل لإعادة الاستخدام.
 * كل شاشات الإعلانات الجديدة تستخدمه حتى يتطابق الشكل مع بقية التطبيق.
 */
public final class CigramGlass {

    private CigramGlass() { }

    /** لوحة ألوان: التطبيق داكن فقط حالياً. اجعل FOLLOW_SYSTEM = true لتفعيل الوضع الفاتح للشاشات الجديدة. */
    public static final boolean FOLLOW_SYSTEM = true;   // v10.70: follows the phone's Light / Dark setting

    public static boolean isLight(Context c) {
        if (!FOLLOW_SYSTEM || c == null) return false;
        int night = c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return night != android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    public static int bg(Context c) { return isLight(c) ? 0xFFF1F6F8 : CigramUI.BG_DEEP; }
    public static int card(Context c) { return isLight(c) ? 0xFFFFFFFF : CigramUI.BG; }
    public static int soft(Context c) { return isLight(c) ? 0xFFE6EEF2 : CigramUI.BG_SOFT; }
    public static int stroke(Context c) { return isLight(c) ? 0xFFCBD9DF : CigramUI.STROKE; }
    public static int text(Context c) { return isLight(c) ? 0xFF10222B : CigramUI.TEXT; }
    public static int muted(Context c) { return isLight(c) ? 0xFF5A6C75 : CigramUI.MUTED; }

    public static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    public static Drawable surface(Context c, float radiusDp, boolean outlined) {
        return new Skin(c, radiusDp, outlined, false);
    }

    public static Drawable surfaceGreen(Context c, float radiusDp, boolean outlined) {
        return new Skin(c, radiusDp, outlined, true);
    }

    public static void bind(View view, float radiusDp, boolean outlined) {
        if (view == null) return;
        Context c = view.getContext();
        view.setBackgroundTintList(null);
        view.setBackground(isLight(c) ? flat(c, radiusDp) : surface(c, radiusDp, outlined));
        view.setElevation(0f);
        view.setStateListAnimator(null);
        view.setClipToOutline(true);
    }

    /** سطح مسطح (للوضع الفاتح). */
    public static GradientDrawable flat(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(card(c));
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(Math.max(1, Math.round(dp(c, 1))), stroke(c));
        return g;
    }

    /** يضيف تأثير Ripple فوق خلفية موجودة. */
    public static Drawable ripple(Context c, Drawable content, float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(c, radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask);
    }

    /**
     * v5.29: press feedback never changes the size of a section (a long press used to shrink it
     * and it grew back on release). Only a light dim; the event is not consumed.
     */
    public static void press(View view) {
        if (view == null) return;
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) v.setAlpha(0.85f);
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) v.setAlpha(1f);
                return false;
            }
        });
    }

    static final class Skin extends Drawable {
        private final float radius;
        private final float density;
        private final boolean outlined;
        private final boolean green;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path clip = new Path();
        private final Path wave = new Path();
        private int opacity = 255;

        Skin(Context c, float radiusDp, boolean outlined, boolean green) {
            this.density = c.getResources().getDisplayMetrics().density;
            this.radius = radiusDp * density;
            this.outlined = outlined;
            this.green = green;
        }

        // v4.4: the two gradients only depend on the bounds, so they are built when the bounds
        // change instead of twice on every frame (this surface is drawn on many cards/buttons).
        private Shader sheen;
        private Shader outline;

        @Override protected void onBoundsChange(Rect b) {
            super.onBoundsChange(b);
            sheen = null;
            outline = null;
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            rect.set(b);
            rect.inset(density / 2f, density / 2f);
            if (sheen == null) {
                sheen = new LinearGradient(0, rect.top, rect.width(), rect.bottom,
                        new int[]{0x34FFFFFF, 0x09FFFFFF, 0x00111113, 0x16FFFFFF},
                        new float[]{0f, 0.28f, 0.65f, 1f}, Shader.TileMode.CLAMP);
                outline = new LinearGradient(0, rect.top, 0, rect.bottom,
                        new int[]{0x90FFFFFF, 0x15FFFFFF, 0x42FFFFFF}, null, Shader.TileMode.CLAMP);
            }
            // An offscreen layer is only needed to fade the whole surface; at full opacity the
            // result is identical without it.
            int saved = opacity < 255 ? canvas.saveLayerAlpha(rect, opacity) : canvas.save();
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(null);
            paint.setColor(green ? 0x6639DB91 : 0xB0111113);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(sheen);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setShader(null);
            paint.setColor(green ? 0x344AEDA1 : 0x0CFFFFFF);
            wave.reset();
            wave.moveTo(rect.left, rect.top);
            wave.lineTo(rect.right, rect.top);
            wave.lineTo(rect.right, rect.top + rect.height() * 0.26f);
            wave.cubicTo(rect.right * 0.6f, rect.top + rect.height() * 0.11f,
                    rect.left + rect.width() * 0.25f, rect.top + rect.height() * 0.58f,
                    rect.left, rect.top + rect.height() * 0.32f);
            wave.close();
            canvas.drawPath(wave, paint);
            if (outlined) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(density);
                paint.setShader(outline);
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            canvas.restoreToCount(saved);
        }

        @Override public void setAlpha(int a) { opacity = a; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public void getOutline(Outline outline) { outline.setRoundRect(getBounds(), radius); }
    }
}
