package com.Cigram.vid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.util.WeakHashMap;

/**
 * One channel of the live page (CIGRAM LIVE): a COMPLETELY TRANSPARENT card. The page shows through; the logo is
 * the only visual weight, with a small title under it. One View, no children, no solid background.
 *
 *  - the logo keeps its proportions: a picture that has no transparency and a shape close to the tile is
 *    cropped to fill it (CENTER_CROP); anything else (square or transparent logos) is fitted inside (FIT_CENTER).
 *    Corners are rounded in both cases (a bitmap shader, not a clip, so it stays cheap);
 *  - a logo that would vanish on the page (a white logo on the light theme, a black one on the dark theme) gets a
 *    faint translucent plate behind it, chosen from the logo's own brightness and the active palette. Pictures
 *    that already fill the tile never get one;
 *  - state is drawn, not styled: a thin brand ring and a play mark for the channel that is playing, a heart for a
 *    favourite, a gold "Plus" mark, and a dimmed picture with «غير متوفرة حالياً» for an entry the catalogue
 *    lists but that cannot be played right now.
 *
 * The same class draws the small logo in the "now playing" bar ({@link #setLogoOnly}).
 */
final class CigramChannelCard extends View implements CigramLogos.Sink {

    interface Listener {
        void onOpen(CigramChannelCard card);
        void onFavourite(CigramChannelCard card);
    }

    /** Tile shape: 3 : 2. */
    static final float TILE_RATIO = 2f / 3f;

    private static final WeakHashMap<Bitmap, float[]> LOOK = new WeakHashMap<Bitmap, float[]>();

    final CigramChannelStore.Ch ch;
    private final CigramPalette pal;
    private final Listener listener;
    private final float dp;

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pic = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pillPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tile = new RectF();
    private final RectF art = new RectF();
    private final RectF heartBox = new RectF();
    private final Matrix matrix = new Matrix();

    private Bitmap bitmap;
    private BitmapShader shader;
    private boolean crop;
    private int plate;            // 0 none, otherwise the plate colour
    private boolean requested;
    private boolean active;
    private boolean fav;
    private boolean logoOnly;
    private StaticLayout title;
    private int titleWidth = -1;
    private float downX, downY;

    CigramChannelCard(Context activity, CigramChannelStore.Ch channel, Listener l) {
        super(activity);
        ch = channel;
        listener = l;
        pal = CigramPalette.of(activity);
        dp = activity.getResources().getDisplayMetrics().density;
        titlePaint.setTypeface(CigramUI.font(activity));
        titlePaint.setTextSize(11.5f * dp);
        pillPaint.setTypeface(CigramUI.font(activity));
        pillPaint.setTextSize(9f * dp);
        linePaint.setStyle(Paint.Style.STROKE);
        setClickable(true);
        setFocusable(true);
        setContentDescription(channel.name);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        if (l != null) {
            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) {
                    if (fav && !logoOnly && heartBox.contains(downX, downY)) listener.onFavourite(CigramChannelCard.this);
                    else listener.onOpen(CigramChannelCard.this);
                }
            });
            setOnLongClickListener(new OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (logoOnly) return false;
                    listener.onFavourite(CigramChannelCard.this);
                    return true;
                }
            });
        }
    }

    // ------------------------------------------------------------------ state

    void setLogoOnly(boolean only) {
        logoOnly = only;
        setClickable(!only);
        setFocusable(!only);
    }

    void setActive(boolean on) {
        if (active == on) return;
        active = on;
        invalidate();
    }

    void setFav(boolean on) {
        if (fav == on) return;
        fav = on;
        invalidate();
    }

    boolean logoRequested() {
        return requested || ch.logoKey().length() == 0;
    }

    /** Asks for the logo (memory first, then the shared disk / network cache). Safe to call again. */
    void ensureLogo() {
        String key = ch.logoKey();
        if (key.length() == 0) {
            requested = true;
            return;
        }
        Bitmap hit = CigramLogos.peek(key);
        if (hit != null) {
            requested = true;
            adopt(hit);
        } else if (!requested) {
            requested = true;
            CigramLogos.request(getContext(), key, this);
        }
    }

    @Override public void onLogo(String key, Bitmap b) {
        if (b == null || !key.equals(ch.logoKey())) return;
        adopt(b);
    }

    private void adopt(Bitmap b) {
        if (b == bitmap) return;
        bitmap = b;
        shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float[] look = LOOK.get(b);
        if (look == null) {
            look = measure(b);
            LOOK.put(b, look);
        }
        // look[0] = brightness of the visible pixels, look[1] = share of transparent pixels
        boolean seeThrough = look[1] > 0.04f || b.hasAlpha() && look[1] > 0.01f;
        plate = 0;
        if (seeThrough) {
            if (pal.light && look[0] > 0.62f) plate = pal.plateForLight;
            else if (!pal.light && look[0] < 0.38f) plate = pal.plateForDark;
        }
        place();
        invalidate();
    }

    /** Brightness (0..1) of the visible pixels and the share of transparent ones, from a 12 x 12 sample. */
    private static float[] measure(Bitmap b) {
        int n = 12;
        double lum = 0;
        int seen = 0, clear = 0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int px = b.getPixel(Math.min(b.getWidth() - 1, (x * 2 + 1) * b.getWidth() / (n * 2)),
                        Math.min(b.getHeight() - 1, (y * 2 + 1) * b.getHeight() / (n * 2)));
                int a = (px >>> 24) & 0xFF;
                if (a < 40) {
                    clear++;
                    continue;
                }
                int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, bl = px & 0xFF;
                lum += (0.2126 * r + 0.7152 * g + 0.0722 * bl) / 255.0;
                seen++;
            }
        }
        return new float[]{seen == 0 ? 0.5f : (float) (lum / seen), clear / (float) (n * n)};
    }

    // ------------------------------------------------------------------ geometry

    /** Height of a card of the given width (the grid uses it so every cell is the same size). */
    static int heightFor(Context c, int width) {
        float d = c.getResources().getDisplayMetrics().density;
        return Math.round(width * TILE_RATIO + 6 * d + 32 * d);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(heightSpec) : heightFor(getContext(), w);
        setMeasuredDimension(w, h);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (logoOnly) {
            tile.set(0f, 0f, w, h);
        } else {
            float th = Math.min(h, w * TILE_RATIO);
            tile.set(dp, dp, w - dp, th - dp);
        }
        float s = 22 * dp;
        heartBox.set(tile.left + 4 * dp, tile.top + 4 * dp, tile.left + 4 * dp + s, tile.top + 4 * dp + s);
        titleWidth = -1;
        place();
    }

    /** Decides crop or fit for the logo and sets the matrix of its shader. */
    private void place() {
        if (bitmap == null || shader == null || tile.width() <= 0f) return;
        float bw = bitmap.getWidth(), bh = bitmap.getHeight();
        float tw = tile.width(), th = tile.height();
        float tileAspect = tw / th, picAspect = bw / bh;
        crop = !bitmap.hasAlpha() && picAspect > tileAspect * 0.72f && picAspect < tileAspect * 1.45f;
        if (crop) {
            float scale = Math.max(tw / bw, th / bh);
            float dw = bw * scale, dh = bh * scale;
            matrix.setScale(scale, scale);
            matrix.postTranslate(tile.left + (tw - dw) / 2f, tile.top + (th - dh) / 2f);
            art.set(tile);
        } else {
            float inset = logoOnly ? 0.06f : 0.10f;
            float aw = tw * (1f - 2f * inset), ah = th * (1f - 2f * inset);
            float scale = Math.min(aw / bw, ah / bh);
            float dw = bw * scale, dh = bh * scale;
            float l = tile.left + (tw - dw) / 2f, t = tile.top + (th - dh) / 2f;
            matrix.setScale(scale, scale);
            matrix.postTranslate(l, t);
            art.set(l, t, l + dw, t + dh);
        }
        shader.setLocalMatrix(matrix);
        pic.setShader(shader);
    }

    // ------------------------------------------------------------------ touch

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            downY = e.getY();
        }
        return super.onTouchEvent(e);
    }

    @Override protected void drawableStateChanged() {
        super.drawableStateChanged();
        invalidate();
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas c) {
        if (tile.width() <= 0f) return;
        float radius = (logoOnly ? 10 : 14) * dp;
        boolean playable = ch.available;
        boolean pressed = isPressed() && !logoOnly;
        int saved = c.save();
        if (pressed) c.scale(0.97f, 0.97f, getWidth() / 2f, tile.height() / 2f);

        // the faint plate: only for a logo that would not show on this page
        if (plate != 0 && bitmap != null) {
            fillPaint.setColor(plate);
            c.drawRoundRect(tile, radius, radius, fillPaint);
        }

        if (bitmap != null && shader != null) {
            pic.setAlpha(playable ? 255 : 110);
            c.drawRoundRect(art, radius, radius, pic);
        } else {
            // no picture (yet): the page shows through, the first letter on a hairline outline
            linePaint.setStrokeWidth(Math.max(1f, dp));
            linePaint.setColor(pal.hairline);
            c.drawRoundRect(tile, radius, radius, linePaint);
            String letter = ch.name.length() == 0 ? "" : ch.name.substring(0, 1);
            ink.setTypeface(CigramUI.font(getContext()));
            ink.setTextAlign(Paint.Align.CENTER);
            ink.setColor(pal.text3);
            ink.setTextSize(Math.min(tile.width(), tile.height()) * 0.40f);
            float y = tile.centerY() - (ink.descent() + ink.ascent()) / 2f;
            c.drawText(letter, tile.centerX(), y, ink);
        }

        // definition for pictures that fill the tile: a barely visible edge, never a heavy border
        if (bitmap != null && crop) {
            linePaint.setStrokeWidth(Math.max(1f, dp * 0.75f));
            linePaint.setColor(pal.hairline);
            c.drawRoundRect(tile, radius, radius, linePaint);
        }

        if (!logoOnly) {
            if (active) {
                linePaint.setStrokeWidth(2f * dp);
                linePaint.setColor(pal.brand);
                float g = 1.5f * dp;
                tile.inset(-g + dp, -g + dp);
                c.drawRoundRect(tile, radius + g, radius + g, linePaint);
                tile.inset(g - dp, g - dp);
                drawPlayMark(c);
            }
            if (ch.plus) drawPill(c, "Plus", tile.right - 6 * dp, tile.top + 6 * dp, true, pal.gold, pal.onGold);
            if (fav) drawHeart(c);
            if (!playable) {
                drawPill(c, "غير متوفرة حالياً", tile.centerX(), tile.bottom - 12 * dp, false, 0xCC000000, 0xFFFFFFFF);
            }
            drawTitle(c, playable);
        }
        c.restoreToCount(saved);
    }

    private void drawTitle(Canvas c, boolean playable) {
        int w = getWidth() - (int) (4 * dp);
        if (w <= 0) return;
        if (title == null || titleWidth != w) {
            titleWidth = w;
            titlePaint.setTextSize(11.5f * dp);
            CharSequence name = TextUtils.ellipsize(ch.name, titlePaint, w * 2f - 12 * dp, TextUtils.TruncateAt.END);
            if (Build.VERSION.SDK_INT >= 23) {
                title = StaticLayout.Builder.obtain(ch.name, 0, ch.name.length(), titlePaint, w)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setMaxLines(2)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .setIncludePad(false)
                        .build();
            } else {
                title = new StaticLayout(name, titlePaint, w, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false);
            }
        }
        titlePaint.setColor(!playable ? pal.text3 : active ? pal.brand : pal.text2);
        titlePaint.setFakeBoldText(active);
        int saved = c.save();
        c.translate(2 * dp, tile.bottom + 5 * dp);
        title.draw(c);
        c.restoreToCount(saved);
    }

    private void drawPill(Canvas c, String text, float anchorX, float y, boolean anchorRight, int fill, int inkColor) {
        pillPaint.setColor(inkColor);
        pillPaint.setFakeBoldText(true);
        float tw = pillPaint.measureText(text);
        float padX = 6 * dp, h = 15 * dp;
        float left = anchorRight ? anchorX - tw - 2 * padX : anchorX - (tw + 2 * padX) / 2f;
        float top = anchorRight ? y : y - h / 2f;
        RectF r = new RectF(left, top, left + tw + 2 * padX, top + h);
        fillPaint.setColor(fill);
        c.drawRoundRect(r, h / 2f, h / 2f, fillPaint);
        float base = r.centerY() - (pillPaint.descent() + pillPaint.ascent()) / 2f;
        c.drawText(text, r.left + padX, base, pillPaint);
    }

    private void drawPlayMark(Canvas c) {
        float s = 22 * dp;
        float cx = tile.right - 6 * dp - s / 2f, cy = tile.bottom - 6 * dp - s / 2f;
        fillPaint.setColor(pal.brand);
        c.drawCircle(cx, cy, s / 2f, fillPaint);
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(cx - 3 * dp, cy - 5 * dp);
        p.lineTo(cx + 5 * dp, cy);
        p.lineTo(cx - 3 * dp, cy + 5 * dp);
        p.close();
        fillPaint.setColor(0xFFFFFFFF);
        c.drawPath(p, fillPaint);
    }

    private void drawHeart(Canvas c) {
        float cx = heartBox.centerX(), cy = heartBox.centerY(), r = heartBox.width() / 2f;
        fillPaint.setColor(0x99000000);
        c.drawCircle(cx, cy, r, fillPaint);
        android.graphics.Path p = new android.graphics.Path();
        float u = r * 0.075f;
        p.moveTo(cx, cy + 6.5f * u);
        p.cubicTo(cx - 12f * u, cy - 1f * u, cx - 6.5f * u, cy - 8.5f * u, cx, cy - 3f * u);
        p.cubicTo(cx + 6.5f * u, cy - 8.5f * u, cx + 12f * u, cy - 1f * u, cx, cy + 6.5f * u);
        p.close();
        fillPaint.setColor(pal.brand);
        c.drawPath(p, fillPaint);
    }
}
