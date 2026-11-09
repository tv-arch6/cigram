package com.Cigram.vid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.json.JSONObject;

/**
 * Verification badge (the blue seal with a check, res/drawable-nodpi/cigram_verified.png) for accounts whose Cigram Plus is
 * active: bottom corner of the round photo (bottom bar, account page, CigramTogether chat + room list) and next to the
 * account name. Nothing is stored: it is drawn only while {@link CigramPlus#isPlus} is true (own account) or while the server
 * marks another member as plus / vip / verified, so it disappears by itself when the subscription ends.
 */
public final class CigramAvatarFrame {

    static final String BADGE_TAG = "cigram_verified_badge";

    private CigramAvatarFrame() { }

    // ------------------------------------------------------------------ who gets the badge

    /** The signed-in account itself. */
    public static boolean selfFramed(Context c) {
        try { return c != null && CigramPlus.isPlus(c); } catch (Throwable t) { return false; }
    }

    /** Another member: only what the server says (plus / vip / verified / is_vip / is_verified); the own entry also while the local Plus cache is active. */
    public static boolean userFramed(Context c, JSONObject user) {
        if (user == null) return false;
        if (user.optBoolean("plus", false) || user.optBoolean("vip", false) || user.optBoolean("verified", false)
                || user.optBoolean("is_vip", false) || user.optBoolean("is_verified", false)) return true;
        try {
            String uid = user.optString("uid", "");
            return c != null && uid.length() > 0 && uid.equals(CigramUserData.authUserId(c)) && selfFramed(c);
        } catch (Throwable t) {
            return false;
        }
    }

    /** True when the ImageView shows the round user photo (outline-clipped, or our badge-carrying circle). The nav bar must never tint/replace it. */
    public static boolean isPhoto(ImageView v) {
        return v != null && (v.getClipToOutline() || v.getDrawable() instanceof Circle);
    }

    // ------------------------------------------------------------------ badge

    private static Drawable badgeArt(Context c) {
        try {
            int id = c.getResources().getIdentifier("cigram_verified", "drawable", c.getPackageName());
            return id == 0 ? null : c.getResources().getDrawable(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The badge as a drawable with intrinsic size = sizePx (for compound drawables next to a name). */
    public static Drawable badgeDrawable(Context c, int sizePx) {
        Drawable d = badgeArt(c);
        if (d == null) return null;
        d = d.mutate();
        d.setBounds(0, 0, sizePx, sizePx);
        return d;
    }

    private static ImageView badgeView(Context c) {
        ImageView b = new ImageView(c);
        b.setTag(BADGE_TAG);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Drawable d = badgeArt(c);
        if (d != null) b.setImageDrawable(d);
        b.setContentDescription("حساب موثّق");
        b.setElevation(CigramUI.dp(c, 9));
        return b;
    }

    private static int badgeSize(Context c, int hostPx) {
        return Math.max(CigramUI.dp(c, 12), Math.round(hostPx * 0.36f));
    }

    /**
     * Adds (framed) or removes the badge on a FrameLayout that holds the round photo (account header, chat). Idempotent.
     * {@code hostDp} = side of the photo in dp.
     */
    public static void decorate(Context c, FrameLayout host, boolean framed, int hostDp) {
        if (host == null) return;
        View old = host.findViewWithTag(BADGE_TAG);
        if (!framed) {
            if (old != null) host.removeView(old);
            return;
        }
        int size = badgeSize(c, CigramUI.dp(c, hostDp));
        if (old != null) {
            old.getLayoutParams().width = size;
            old.getLayoutParams().height = size;
            old.requestLayout();
            return;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.RIGHT);
        host.setClipChildren(false);
        host.setClipToPadding(false);
        host.addView(badgeView(c), lp);
    }

    /** Chat / room list: same-size FrameLayout with the avatar and the badge on its bottom corner (the parent must not clip children). */
    public static View wrap(Context c, View avatar, int outerPx) {
        FrameLayout f = new FrameLayout(c);
        f.setLayoutParams(new android.view.ViewGroup.LayoutParams(outerPx, outerPx));
        f.setClipChildren(false);
        f.addView(avatar, new FrameLayout.LayoutParams(outerPx, outerPx));
        int size = badgeSize(c, outerPx);
        f.addView(badgeView(c), new FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.RIGHT));
        return f;
    }

    /** Bottom bar avatar: round photo drawn by us (not outline-clipped, so the badge can sit on the corner). */
    public static void applyToImage(ImageView view, Bitmap b, boolean selected) {
        Context c = view.getContext();
        view.setClipToOutline(false);
        view.setOutlineProvider(null);
        view.setBackground(null);   // a square placeholder colour would show its corners around the round photo
        view.setPadding(0, 0, 0, 0);
        view.setScaleType(ImageView.ScaleType.FIT_XY);
        view.clearColorFilter();
        view.setImageDrawable(new Circle(b));
        view.setAlpha(selected ? 1f : 0.9f);
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            Drawable art = badgeArt(c);
            if (art != null) {
                Drawable badge = art.getConstantState() != null ? art.getConstantState().newDrawable().mutate() : art;
                view.setForeground(new Corner(badge, 0.46f));
            }
        }
        if (view.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) view.getParent()).setClipChildren(false);
    }

    /** Back to the plain look (Plus ended). */
    public static void clearImage(ImageView view) {
        if (android.os.Build.VERSION.SDK_INT >= 23) view.setForeground(null);
    }

    // ------------------------------------------------------------------ drawables

    /** Draws {@code art} at the bottom-right corner of the bounds, {@code frac} of the side, spilling slightly outside. */
    private static final class Corner extends Drawable {
        private final Drawable art;
        private final float frac;
        Corner(Drawable art, float frac) { this.art = art; this.frac = frac; }
        @Override public void draw(Canvas canvas) {
            android.graphics.Rect r = getBounds();
            int s = Math.round(Math.min(r.width(), r.height()) * frac);
            int out = s / 5;
            art.setBounds(r.right - s + out, r.bottom - s + out, r.right + out, r.bottom + out);
            art.draw(canvas);
        }
        @Override public void setAlpha(int a) { art.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { art.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class Circle extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Bitmap bmp;
        private final Matrix m = new Matrix();
        private final BitmapShader shader;

        Circle(Bitmap b) {
            bmp = b;
            shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            p.setShader(shader);
        }

        @Override protected void onBoundsChange(android.graphics.Rect r) {
            if (bmp == null || bmp.getWidth() == 0 || bmp.getHeight() == 0) return;
            float s = Math.max(r.width() / (float) bmp.getWidth(), r.height() / (float) bmp.getHeight());
            m.reset();
            m.setScale(s, s);
            m.postTranslate(r.left + (r.width() - bmp.getWidth() * s) / 2f, r.top + (r.height() - bmp.getHeight() * s) / 2f);
            shader.setLocalMatrix(m);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect r = getBounds();
            canvas.drawCircle(r.exactCenterX(), r.exactCenterY(), Math.min(r.width(), r.height()) / 2f, p);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
