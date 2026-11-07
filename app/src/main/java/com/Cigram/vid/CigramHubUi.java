package com.Cigram.vid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;

/**
 * v10.63 — the few custom views of the channels page. Drawn in code (no image files), light enough
 * to sit in every row of a list: one View per icon, one View per logo.
 */
final class CigramHubUi {

    static final int HEART = 1, HEART_FILL = 2, PLAY = 3, PAUSE = 4, SEARCH = 5, CLOSE = 6, EXPAND = 7,
            SPEAKER = 8, MUTE = 9, TV = 10, GRID = 11, CLOCK = 12, CHEV_LEFT = 13, CHEV_RIGHT = 14,
            RETRY = 15, NEXT = 16, PREV = 17, SHRINK = 18;

    private CigramHubUi() { }

    // ================================================================== icons

    /** Line icon on a 24-unit grid; optionally with a soft shadow so it reads on top of a picture. */
    static final class Icon extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private int kind;
        private int color;
        private boolean shadow;

        Icon(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            this.color = color;
            setup(line, fill);
        }

        void set(int kind, int color) {
            if (this.kind == kind && this.color == color) return;
            this.kind = kind;
            this.color = color;
            invalidate();
        }

        void setShadow(boolean on) {
            shadow = on;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h);
            if (s <= 0f) return;
            float u = s / 24f;
            c.save();
            c.translate((w - s) / 2f, (h - s) / 2f);
            c.scale(u, u);
            if (shadow) {
                c.save();
                c.translate(0f, 0.6f);
                paint(c, 0x24000000, 3.4f);
                paint(c, 0x47000000, 1.6f);
                c.restore();
            }
            paint(c, color, 0f);
            c.restore();
        }

        private void paint(Canvas c, int col, float grow) {
            line.setColor(col);
            fill.setColor(col);
            glyph(c, kind, line, fill, path, rect, grow);
        }
    }

    static void setup(Paint line, Paint fill) {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        fill.setStrokeCap(Paint.Cap.ROUND);
        fill.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Draws icon `kind` on a 24x24 grid with the colours already set on the two paints. */
    static void glyph(Canvas c, int kind, Paint line, Paint fill, Path path, RectF r, float grow) {
        float sw = 2f + grow;
        line.setStrokeWidth(sw);
        path.reset();
        switch (kind) {
            case HEART:
            case HEART_FILL:
                path.moveTo(12f, 20.2f);
                path.cubicTo(5.2f, 15.2f, 3f, 11.9f, 3f, 8.9f);
                path.cubicTo(3f, 6.3f, 5f, 4.5f, 7.4f, 4.5f);
                path.cubicTo(9.2f, 4.5f, 10.9f, 5.5f, 12f, 7.2f);
                path.cubicTo(13.1f, 5.5f, 14.8f, 4.5f, 16.6f, 4.5f);
                path.cubicTo(19f, 4.5f, 21f, 6.3f, 21f, 8.9f);
                path.cubicTo(21f, 11.9f, 18.8f, 15.2f, 12f, 20.2f);
                path.close();
                if (kind == HEART_FILL) {
                    fill.setStyle(Paint.Style.FILL_AND_STROKE);
                    fill.setStrokeWidth(1.4f + grow);
                    c.drawPath(path, fill);
                    fill.setStyle(Paint.Style.FILL);
                } else {
                    c.drawPath(path, line);
                }
                break;
            case PLAY:
                path.moveTo(8f, 5.4f);
                path.lineTo(19f, 12f);
                path.lineTo(8f, 18.6f);
                path.close();
                fill.setStyle(Paint.Style.FILL_AND_STROKE);
                fill.setStrokeWidth(2f + grow);
                c.drawPath(path, fill);
                fill.setStyle(Paint.Style.FILL);
                break;
            case PAUSE:
                fill.setStyle(Paint.Style.FILL_AND_STROKE);
                fill.setStrokeWidth(1.4f + grow);
                r.set(7.2f, 5.6f, 10.2f, 18.4f);
                c.drawRoundRect(r, 0.8f, 0.8f, fill);
                r.set(13.8f, 5.6f, 16.8f, 18.4f);
                c.drawRoundRect(r, 0.8f, 0.8f, fill);
                fill.setStyle(Paint.Style.FILL);
                break;
            case SEARCH:
                c.drawCircle(10.5f, 10.5f, 6.3f, line);
                c.drawLine(15.2f, 15.2f, 20f, 20f, line);
                break;
            case CLOSE:
                c.drawLine(6f, 6f, 18f, 18f, line);
                c.drawLine(18f, 6f, 6f, 18f, line);
                break;
            case EXPAND:
                path.moveTo(4f, 9f); path.lineTo(4f, 5.8f); path.quadTo(4f, 4f, 5.8f, 4f); path.lineTo(9f, 4f);
                path.moveTo(15f, 4f); path.lineTo(18.2f, 4f); path.quadTo(20f, 4f, 20f, 5.8f); path.lineTo(20f, 9f);
                path.moveTo(20f, 15f); path.lineTo(20f, 18.2f); path.quadTo(20f, 20f, 18.2f, 20f); path.lineTo(15f, 20f);
                path.moveTo(9f, 20f); path.lineTo(5.8f, 20f); path.quadTo(4f, 20f, 4f, 18.2f); path.lineTo(4f, 15f);
                c.drawPath(path, line);
                break;
            case SHRINK:
                path.moveTo(9f, 4f); path.lineTo(9f, 7.2f); path.quadTo(9f, 9f, 7.2f, 9f); path.lineTo(4f, 9f);
                path.moveTo(20f, 9f); path.lineTo(16.8f, 9f); path.quadTo(15f, 9f, 15f, 7.2f); path.lineTo(15f, 4f);
                path.moveTo(15f, 20f); path.lineTo(15f, 16.8f); path.quadTo(15f, 15f, 16.8f, 15f); path.lineTo(20f, 15f);
                path.moveTo(4f, 15f); path.lineTo(7.2f, 15f); path.quadTo(9f, 15f, 9f, 16.8f); path.lineTo(9f, 20f);
                c.drawPath(path, line);
                break;
            case SPEAKER:
            case MUTE:
                speakerBody(c, line, fill, path, grow);
                if (kind == SPEAKER) {
                    r.set(7.5f, 7.5f, 16.5f, 16.5f);
                    c.drawArc(r, -42f, 84f, false, line);
                    r.set(4f, 4f, 20f, 20f);
                    c.drawArc(r, -42f, 84f, false, line);
                } else {
                    c.drawLine(16f, 9.4f, 21f, 14.6f, line);
                    c.drawLine(21f, 9.4f, 16f, 14.6f, line);
                }
                break;
            case TV:
                r.set(3f, 6.6f, 21f, 18.4f);
                c.drawRoundRect(r, 2.6f, 2.6f, line);
                c.drawLine(8.6f, 3.2f, 12f, 6.6f, line);
                c.drawLine(15.4f, 3.2f, 12f, 6.6f, line);
                c.drawLine(8f, 21.2f, 16f, 21.2f, line);
                break;
            case GRID:
                r.set(4f, 4f, 10.4f, 10.4f); c.drawRoundRect(r, 1.8f, 1.8f, line);
                r.set(13.6f, 4f, 20f, 10.4f); c.drawRoundRect(r, 1.8f, 1.8f, line);
                r.set(4f, 13.6f, 10.4f, 20f); c.drawRoundRect(r, 1.8f, 1.8f, line);
                r.set(13.6f, 13.6f, 20f, 20f); c.drawRoundRect(r, 1.8f, 1.8f, line);
                break;
            case CLOCK:
                c.drawCircle(12f, 12f, 8.4f, line);
                path.moveTo(12f, 7.4f); path.lineTo(12f, 12f); path.lineTo(15.4f, 14.2f);
                c.drawPath(path, line);
                break;
            case CHEV_LEFT:
                path.moveTo(14.8f, 5.8f); path.lineTo(8.6f, 12f); path.lineTo(14.8f, 18.2f);
                c.drawPath(path, line);
                break;
            case CHEV_RIGHT:
                path.moveTo(9.2f, 5.8f); path.lineTo(15.4f, 12f); path.lineTo(9.2f, 18.2f);
                c.drawPath(path, line);
                break;
            case NEXT:
                path.moveTo(17f, 5.8f); path.lineTo(10.8f, 12f); path.lineTo(17f, 18.2f);
                c.drawPath(path, line);
                c.drawLine(6.6f, 5.8f, 6.6f, 18.2f, line);
                break;
            case PREV:
                path.moveTo(7f, 5.8f); path.lineTo(13.2f, 12f); path.lineTo(7f, 18.2f);
                c.drawPath(path, line);
                c.drawLine(17.4f, 5.8f, 17.4f, 18.2f, line);
                break;
            case RETRY:
                r.set(4.5f, 4.5f, 19.5f, 19.5f);
                c.drawArc(r, 60f, 280f, false, line);
                path.moveTo(15.6f, 3.6f); path.lineTo(19.6f, 4.9f); path.lineTo(18.2f, 8.9f);
                c.drawPath(path, line);
                break;
            default:
                break;
        }
    }

    private static void speakerBody(Canvas c, Paint line, Paint fill, Path path, float grow) {
        path.reset();
        path.moveTo(4f, 9.6f);
        path.lineTo(7.6f, 9.6f);
        path.lineTo(12f, 5.8f);
        path.lineTo(12f, 18.2f);
        path.lineTo(7.6f, 14.4f);
        path.lineTo(4f, 14.4f);
        path.close();
        c.drawPath(path, line);
        path.reset();
    }

    // ================================================================== logo

    /** A channel / package picture on a dark tile, or a letter, or an icon. One View, no children. */
    static final class LogoView extends View implements CigramLogos.Sink {
        private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pic = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF box = new RectF();
        private final RectF dst = new RectF();
        private final RectF tmp = new RectF();
        private final Rect src = new Rect();
        private String want = "";
        private Bitmap bitmap;
        private String letter = "";
        private int glyph;
        private int glyphColor = 0xFFFFFFFF;
        private boolean circle;
        private float radius;
        private float inset;

        LogoView(Context c, boolean circle, float radiusPx) {
            super(c);
            this.circle = circle;
            this.radius = radiusPx;
            tile.setColor(CigramTheme.adapt(c, 0xFF102F3B));
            text.setColor(CigramTheme.isLight(c) ? 0xFF5F6B73 : 0xFF8FA1AA);
            text.setTextAlign(Paint.Align.CENTER);
            try { text.setTypeface(CigramUI.font(c)); } catch (Throwable ignored) { }
            setup(line, fill);
        }

        /** Tile colour behind the picture. */
        void setTile(int color) {
            tile.setColor(CigramTheme.adapt(getContext(), color));
            invalidate();
        }

        /** A fixed icon instead of a picture (the "favourites" and "recent" packages). */
        void showGlyph(int kind, int color) {
            want = "";
            bitmap = null;
            glyph = kind;
            glyphColor = CigramTheme.adaptText(getContext(), color);
            invalidate();
        }

        /** Shows the logo for `key` (memory only when `load` is false), else the first letter of `name`. */
        void show(String key, String name, boolean load) {
            glyph = 0;
            String k = key == null ? "" : key;
            letter = name == null || name.trim().length() == 0 ? "" : name.trim().substring(0, 1);
            if (!k.equals(want)) {
                want = k;
                bitmap = null;
            }
            if (k.length() > 0) {
                Bitmap hit = CigramLogos.peek(k);
                if (hit != null) {
                    bitmap = hit;
                } else if (load) {
                    CigramLogos.request(getContext(), k, this);
                }
            }
            place();
            invalidate();
        }

        @Override public void onLogo(String key, Bitmap b) {
            if (!key.equals(want)) return;
            bitmap = b;
            place();
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            box.set(0f, 0f, w, h);
            inset = circle ? Math.min(w, h) * 0.2f : Math.min(w, h) * 0.12f;
            text.setTextSize(Math.min(w, h) * 0.42f);
            place();
        }

        private void place() {
            if (bitmap == null || box.width() <= 0f) return;
            float bw = bitmap.getWidth(), bh = bitmap.getHeight();
            float aw = box.width() - 2f * inset, ah = box.height() - 2f * inset;
            if (bw <= 0f || bh <= 0f || aw <= 0f || ah <= 0f) return;
            float scale = Math.min(aw / bw, ah / bh);
            float dw = bw * scale, dh = bh * scale;
            dst.set((box.width() - dw) / 2f, (box.height() - dh) / 2f,
                    (box.width() + dw) / 2f, (box.height() + dh) / 2f);
            src.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        }

        @Override protected void onDraw(Canvas c) {
            if (box.width() <= 0f) return;
            if (circle) {
                c.drawCircle(box.centerX(), box.centerY(), Math.min(box.width(), box.height()) / 2f, tile);
            } else {
                c.drawRoundRect(box, radius, radius, tile);
            }
            if (bitmap != null) {
                c.drawBitmap(bitmap, src, dst, pic);
            } else if (glyph != 0) {
                float s = Math.min(box.width(), box.height()) * 0.56f;
                float u = s / 24f;
                line.setColor(glyphColor);
                fill.setColor(glyphColor);
                c.save();
                c.translate(box.centerX() - s / 2f, box.centerY() - s / 2f);
                c.scale(u, u);
                glyph(c, glyph, line, fill, path, tmp, 0f);
                c.restore();
            } else if (letter.length() > 0) {
                float y = box.centerY() - (text.descent() + text.ascent()) / 2f;
                c.drawText(letter, box.centerX(), y, text);
            }
        }
    }

    // ================================================================== two panes

    /**
     * Two panes side by side: the first child on the RIGHT (the channels), the second on the LEFT
     * (the packages), like the Arabic reading order. The share of the right pane is adjustable.
     */
    static final class Split2 extends ViewGroup {
        private float rightShare = 0.58f;
        private final int gap;

        Split2(Context c, int gapPx) {
            super(c);
            gap = gapPx;
        }

        void setRightShare(float share) {
            rightShare = share;
            requestLayout();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            int h = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(w, h);
            int rw = Math.round(w * rightShare) - gap / 2;
            int lw = Math.max(0, w - rw - gap);
            if (getChildCount() > 0) {
                getChildAt(0).measure(MeasureSpec.makeMeasureSpec(Math.max(0, rw), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            }
            if (getChildCount() > 1) {
                getChildAt(1).measure(MeasureSpec.makeMeasureSpec(lw, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            }
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l, h = b - t;
            int rw = Math.round(w * rightShare) - gap / 2;
            int lw = Math.max(0, w - rw - gap);
            if (getChildCount() > 0) getChildAt(0).layout(w - Math.max(0, rw), 0, w, h);
            if (getChildCount() > 1) getChildAt(1).layout(0, 0, lw, h);
        }
    }
}
