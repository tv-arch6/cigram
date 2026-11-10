package com.Cigram.vid;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;

/**
 * 19.2 — the player's centre buttons in Cigram's own identity («Cigram Signature»).
 *
 * <pre>
 *   ⏮   ↺10   ( ▶ )   10↻   ⏭
 * </pre>
 * <ul>
 *   <li><b>Play / pause</b>: a round Cigram-red button (the brand colour, lit from the top left) inside a thin
 *   red-to-gold ring — the two accents of the app. It is the only filled control, so the eye lands on it.</li>
 *   <li><b>Back / forward</b>: an open ring that ends in an arrow head, with the seek step (5 / 10 / 15 / 30) inside
 *   and a small red bead where the ring starts; drawn on a faint dark disc so it stays readable over a bright scene.</li>
 *   <li><b>Previous / next episode</b>: two rounded chevrons against a bar, no background.</li>
 * </ul>
 * Everything is drawn with paths (no image files), crisp at any size and density, and cheap: one Drawable per button,
 * no allocation while drawing. The button sizes, positions and click logic of the player are untouched.
 */
final class CigramPlayerButtons {

    private CigramPlayerButtons() { }

    static final int ICON_PREVIOUS = 0, ICON_REWIND = 1, ICON_PLAY = 2, ICON_PAUSE = 3, ICON_FORWARD = 4, ICON_NEXT = 5;

    private static final int RED = 0xFFE21B14, RED_LIGHT = 0xFFFF5A4C, RED_DEEP = 0xFF9E0D09, GOLD = 0xFFF2C14E;

    /** kind: 2 = play / pause, 1 = back / forward, 0 = previous / next. */
    static Drawable background(int kind, float dp) {
        Drawable shape = kind == 2 ? new MainDisc(dp) : kind == 1 ? new GlassDisc(dp) : new GradientDrawable();
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(kind == 2 ? 0x66FFFFFF : 0x33FFFFFF), shape, mask);
    }

    /** Red disc + highlight + red-to-gold ring. */
    private static final class MainDisc extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float dp;
        private float cx, cy, r;

        MainDisc(float dp) {
            this.dp = dp;
            ring.setStyle(Paint.Style.STROKE);
        }

        @Override protected void onBoundsChange(Rect b) {
            cx = b.exactCenterX();
            cy = b.exactCenterY();
            r = Math.min(b.width(), b.height()) / 2f;
            float ringW = Math.max(1.5f, 2.2f * dp);
            float inner = r - ringW - 2.4f * dp;
            fill.setShader(new LinearGradient(cx - inner, cy - inner, cx + inner, cy + inner,
                    new int[]{RED_LIGHT, RED, RED_DEEP}, new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
            shine.setShader(new RadialGradient(cx - inner * 0.35f, cy - inner * 0.45f, inner * 0.95f,
                    new int[]{0x55FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            ring.setStrokeWidth(ringW);
            ring.setShader(new SweepGradient(cx, cy, new int[]{GOLD, RED_LIGHT, RED, GOLD}, new float[]{0f, 0.35f, 0.7f, 1f}));
        }

        @Override public void draw(Canvas c) {
            float ringW = ring.getStrokeWidth();
            float inner = r - ringW - 2.4f * dp;
            c.drawCircle(cx, cy, inner, fill);
            c.drawCircle(cx, cy, inner, shine);
            c.drawCircle(cx, cy, r - ringW / 2f - 0.5f, ring);
        }

        @Override public void setAlpha(int a) { fill.setAlpha(a); shine.setAlpha(a); ring.setAlpha(a); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Faint dark disc with a hairline: keeps the white seek icons readable on a bright picture. */
    private static final class GlassDisc extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlassDisc(float dp) {
            fill.setColor(0x47000000);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(1f, 1f * dp));
            line.setColor(0x26FFFFFF);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float r = Math.min(b.width(), b.height()) / 2f;
            c.drawCircle(b.exactCenterX(), b.exactCenterY(), r - 1f, fill);
            c.drawCircle(b.exactCenterX(), b.exactCenterY(), r - 1f, line);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * Draws one icon into {@code b}. {@code paint} carries the caller's alpha and colour filter (the player tints
     * the icons white); its other settings are overwritten. {@code accent} is a second paint for the red bead.
     */
    static void drawIcon(Canvas canvas, Rect b, int type, Paint paint, Paint accent, Path path, RectF arc, long seekStepMs) {
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float u = Math.min(b.width(), b.height());
        ColorFilter keep = paint.getColorFilter();
        int alpha = paint.getAlpha();
        paint.reset();
        paint.setAntiAlias(true);
        paint.setColorFilter(keep);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha(alpha);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        if (type == ICON_PLAY) {
            paint.setStyle(Paint.Style.FILL);
            paint.setPathEffect(new CornerPathEffect(u * 0.10f));
            float w = u * 0.44f, h = u * 0.50f, x = cx - w * 0.36f;
            path.moveTo(x, cy - h / 2f);
            path.lineTo(x + w, cy);
            path.lineTo(x, cy + h / 2f);
            path.close();
            canvas.drawPath(path, paint);
            return;
        }
        if (type == ICON_PAUSE) {
            paint.setStyle(Paint.Style.FILL);
            float bw = u * 0.14f, bh = u * 0.46f, gap = u * 0.085f, r = bw * 0.5f;
            arc.set(cx - gap - bw, cy - bh / 2f, cx - gap, cy + bh / 2f);
            canvas.drawRoundRect(arc, r, r, paint);
            arc.set(cx + gap, cy - bh / 2f, cx + gap + bw, cy + bh / 2f);
            canvas.drawRoundRect(arc, r, r, paint);
            return;
        }
        if (type == ICON_PREVIOUS || type == ICON_NEXT) {
            // two rounded chevrons running into a bar
            float dir = type == ICON_NEXT ? 1f : -1f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(u * 0.085f);
            float h = u * 0.20f, w = u * 0.16f;
            float x1 = cx - dir * u * 0.20f, x2 = cx - dir * u * 0.01f;
            path.moveTo(x1, cy - h); path.lineTo(x1 + dir * w, cy); path.lineTo(x1, cy + h);
            path.moveTo(x2, cy - h); path.lineTo(x2 + dir * w, cy); path.lineTo(x2, cy + h);
            canvas.drawPath(path, paint);
            float bar = cx + dir * u * 0.27f;
            canvas.drawLine(bar, cy - h, bar, cy + h, paint);
            return;
        }
        // back / forward: open ring + arrow head + seconds + red bead
        boolean forward = type == ICON_FORWARD;
        float d = forward ? 1f : -1f;
        float r = u * 0.34f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(u * 0.062f);
        arc.set(cx - r, cy - r, cx + r, cy + r);
        // the gap is at the top; the ring runs away from the arrow head (clockwise for forward)
        canvas.drawArc(arc, forward ? -52f : -128f, forward ? 292f : -292f, false, paint);
        paint.setStyle(Paint.Style.FILL);
        float a = u * 0.135f, hy = cy - r, hx = cx + d * u * 0.02f;
        path.moveTo(hx + d * a, hy);
        path.lineTo(hx - d * a * 0.55f, hy - a * 0.9f);
        path.lineTo(hx - d * a * 0.55f, hy + a * 0.9f);
        path.close();
        canvas.drawPath(path, paint);
        long step = seekStepMs;
        if (step != 5000L && step != 10000L && step != 15000L && step != 25000L && step != 30000L) step = 10000L;
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        paint.setTextSize(u * 0.27f);
        Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.drawText(String.valueOf(step / 1000L), cx, cy - (fm.ascent + fm.descent) / 2f + u * 0.025f, paint);
        // the bead: where the ring starts, on the side away from the arrow
        double ang = Math.toRadians(forward ? 240 : -60);
        accent.reset();
        accent.setAntiAlias(true);
        accent.setStyle(Paint.Style.FILL);
        accent.setColor(RED);
        accent.setAlpha(alpha);
        canvas.drawCircle(cx + (float) Math.cos(ang) * r, cy + (float) Math.sin(ang) * r, u * 0.052f, accent);
    }

    /** The icon as a Drawable (used by the player's buttons). */
    static final class Icon extends Drawable {
        private final int type;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF arc = new RectF();
        private final android.content.SharedPreferences prefs;

        Icon(int type, android.content.SharedPreferences playerPrefs) {
            this.type = type;
            this.prefs = playerPrefs;
        }

        @Override public void draw(Canvas canvas) {
            long step = 10000L;
            try { if (prefs != null) step = prefs.getLong("seek_step", 10000L); } catch (Throwable ignored) { }
            drawIcon(canvas, getBounds(), type, paint, accent, path, arc, step);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter f) { /* icons are white by design; the red bead must stay red */ }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
