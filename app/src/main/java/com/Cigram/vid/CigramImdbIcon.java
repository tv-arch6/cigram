package com.Cigram.vid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.ViewGroup;
import android.widget.ImageView;

/**
 * v10.39: the IMDb logo (drawable "imdb") with rounded corners, used instead of the word "IMDb"
 * on the film / series page (the meta line) and on the poster cards of the home rows.
 * Falls back to the "ic_imdb" vector, and to the plain word when neither exists.
 */
final class CigramImdbIcon {
    /** Corner radius as a share of the logo height. */
    private static final float RADIUS = 0.24f;
    private static final java.util.HashMap<String, Bitmap> BITMAPS = new java.util.HashMap<String, Bitmap>();

    private CigramImdbIcon() { }

    /** Drops the decoded pictures (they are decoded again on the next use). */
    static void trim() { BITMAPS.clear(); }

    private static int resId(Context c, String name) {
        return c.getResources().getIdentifier(name, "drawable", c.getPackageName());
    }

    /** Rounded logo, or null when the "imdb" picture is not in the app. */
    static Drawable rounded(Context c) {
        return roundedNamed(c, "imdb", RADIUS);
    }

    /** Any drawable picture of the app (png) drawn with rounded corners; null when it is missing. */
    static Drawable roundedNamed(Context c, String name, float radiusShare) {
        try {
            Bitmap bmp = BITMAPS.get(name);
            if (bmp == null || bmp.isRecycled()) {
                int id = resId(c, name);
                if (id == 0) return null;
                bmp = BitmapFactory.decodeResource(c.getResources(), id);
                if (bmp == null) return null;
                BITMAPS.put(name, bmp);
            }
            return new Rounded(bmp, radiusShare);
        } catch (Throwable error) {
            return null;
        }
    }

    private static Drawable any(Context c) {
        Drawable d = rounded(c);
        if (d != null) return d;
        try {
            int id = resId(c, "ic_imdb");
            return id == 0 ? null : c.getResources().getDrawable(id).mutate();
        } catch (Throwable error) {
            return null;
        }
    }

    private static int widthFor(Drawable d, int heightPx) {
        int iw = d.getIntrinsicWidth();
        int ih = d.getIntrinsicHeight();
        return iw > 0 && ih > 0 ? Math.round(heightPx * (iw / (float) ih)) : Math.round(heightPx * 2.2f);
    }

    /** Puts the logo into {@code view} at the given height (width follows the picture). */
    static void apply(ImageView view, int heightPx) {
        if (view == null) return;
        Drawable d = any(view.getContext());
        if (d == null) return;
        view.setImageDrawable(d);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setAdjustViewBounds(false);
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            lp.height = heightPx;
            lp.width = widthFor(d, heightPx);
            view.setLayoutParams(lp);
        }
    }

    /** "[logo] 8.1" for a TextView: the logo sits on the text's middle line. */
    static CharSequence text(Context c, String rating, float textSp) {
        try {
            Drawable d = any(c);
            if (d != null) {
                int h = Math.round(textSp * c.getResources().getDisplayMetrics().scaledDensity * 1.45f);
                d.setBounds(0, 0, widthFor(d, h), h);
                SpannableStringBuilder sb = new SpannableStringBuilder("￼ ");
                sb.setSpan(new Centered(d), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(rating);
                return sb;
            }
        } catch (Throwable ignored) { }
        return "IMDb " + rating;
    }

    private static final class Centered extends ImageSpan {
        Centered(Drawable d) { super(d, ImageSpan.ALIGN_BASELINE); }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                                   int top, int y, int bottom, Paint paint) {
            Drawable d = getDrawable();
            Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            int middle = y + (fm.ascent + fm.descent) / 2;
            canvas.save();
            canvas.translate(x, middle - d.getBounds().height() / 2f);
            d.draw(canvas);
            canvas.restore();
        }
    }

    private static final class Rounded extends Drawable {
        private final Bitmap bitmap;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final BitmapShader shader;
        private final Matrix matrix = new Matrix();
        private final float radiusShare;

        Rounded(Bitmap b, float radiusShare) {
            bitmap = b;
            this.radiusShare = radiusShare;
            shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            paint.setShader(shader);
        }

        @Override public void draw(Canvas canvas) {
            Rect r = getBounds();
            if (r.width() <= 0 || r.height() <= 0) return;
            matrix.setScale(r.width() / (float) bitmap.getWidth(), r.height() / (float) bitmap.getHeight());
            matrix.postTranslate(r.left, r.top);
            shader.setLocalMatrix(matrix);
            float radius = r.height() * radiusShare;
            canvas.drawRoundRect(new RectF(r), radius, radius, paint);
        }

        @Override public int getIntrinsicWidth() { return bitmap.getWidth(); }
        @Override public int getIntrinsicHeight() { return bitmap.getHeight(); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
