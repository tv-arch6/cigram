package com.my.newproject;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

/** عناصر عرض صغيرة مشتركة بين شاشات المحادثة: موجة صوتية، صورة بزوايا مدوّرة، صورة قابلة للتكبير. */
public final class AdViews {

    private AdViews() { }

    /** موجة صوتية: أعمدة بنسب 0..255 مع تقدّم التشغيل. */
    public static final class Wave extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int[] bars = new int[0];
        private float progress = 0f;
        private int onColor;
        private int offColor;

        public Wave(Context c, int on, int off) {
            super(c);
            onColor = on;
            offColor = off;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        public void setBars(int[] b) {
            bars = b == null ? new int[0] : b;
            invalidate();
        }

        public void setProgress(float f) {
            progress = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            int n = bars.length > 0 ? bars.length : 32;
            float w = getWidth();
            float h = getHeight();
            float slot = w / n;
            float bw = Math.max(2f, slot * 0.55f);
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < n; i++) {
                float v = bars.length > 0 ? bars[i] / 255f : 0.2f;
                float bh = Math.max(h * 0.12f, h * v);
                float cx = slot * i + slot / 2f;
                // الاتجاه: يسار → يمين دائماً (كتطبيقات الرسائل)
                r.set(cx - bw / 2f, (h - bh) / 2f, cx + bw / 2f, (h + bh) / 2f);
                p.setColor(((float) i / n) < progress ? onColor : offColor);
                canvas.drawRoundRect(r, bw / 2f, bw / 2f, p);
            }
        }
    }

    /** ImageView بزوايا مدوّرة (قصّ بالـ Outline). */
    public static final class Round extends ImageView {
        public Round(Context c, final float radiusPx) {
            super(c);
            setScaleType(ImageView.ScaleType.CENTER_CROP);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
                }
            });
            setClipToOutline(true);
        }
    }

    /** عارض صورة بتكبير بالقرصة وسحب وضغطتين. */
    public static final class Zoom extends ImageView {
        private final Matrix m = new Matrix();
        private final float[] v = new float[9];
        private final ScaleGestureDetector sg;
        private float minScale = 1f;
        private float lastX;
        private float lastY;
        private boolean dragging;
        private long lastTap;
        private Runnable onSingleTap;

        public Zoom(Context c) {
            super(c);
            setScaleType(ImageView.ScaleType.MATRIX);
            sg = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    float cur = scale();
                    float f = d.getScaleFactor();
                    float target = Math.max(minScale, Math.min(minScale * 5f, cur * f));
                    f = target / cur;
                    m.postScale(f, f, d.getFocusX(), d.getFocusY());
                    fix();
                    setImageMatrix(m);
                    return true;
                }
            });
        }

        public void setOnSingleTap(Runnable r) { onSingleTap = r; }

        private float scale() {
            m.getValues(v);
            return v[Matrix.MSCALE_X];
        }

        public void fit() {
            if (getDrawable() == null || getWidth() == 0 || getHeight() == 0) return;
            float dw = getDrawable().getIntrinsicWidth();
            float dh = getDrawable().getIntrinsicHeight();
            if (dw <= 0 || dh <= 0) return;
            float s = Math.min(getWidth() / dw, getHeight() / dh);
            minScale = s;
            m.reset();
            m.postScale(s, s);
            m.postTranslate((getWidth() - dw * s) / 2f, (getHeight() - dh * s) / 2f);
            setImageMatrix(m);
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            fit();
        }

        @Override public void setImageBitmap(android.graphics.Bitmap bm) {
            super.setImageBitmap(bm);
            fit();
        }

        private void fix() {
            if (getDrawable() == null) return;
            m.getValues(v);
            float s = v[Matrix.MSCALE_X];
            float dw = getDrawable().getIntrinsicWidth() * s;
            float dh = getDrawable().getIntrinsicHeight() * s;
            float tx = v[Matrix.MTRANS_X];
            float ty = v[Matrix.MTRANS_Y];
            float nx = tx;
            float ny = ty;
            if (dw <= getWidth()) nx = (getWidth() - dw) / 2f; else nx = Math.min(0f, Math.max(getWidth() - dw, tx));
            if (dh <= getHeight()) ny = (getHeight() - dh) / 2f; else ny = Math.min(0f, Math.max(getHeight() - dh, ty));
            m.postTranslate(nx - tx, ny - ty);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            sg.onTouchEvent(e);
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                lastX = e.getX();
                lastY = e.getY();
                dragging = false;
            } else if (a == MotionEvent.ACTION_MOVE && e.getPointerCount() == 1 && !sg.isInProgress()) {
                float dx = e.getX() - lastX;
                float dy = e.getY() - lastY;
                if (dragging || Math.abs(dx) + Math.abs(dy) > 12f) {
                    dragging = true;
                    m.postTranslate(dx, dy);
                    fix();
                    setImageMatrix(m);
                    lastX = e.getX();
                    lastY = e.getY();
                }
            } else if (a == MotionEvent.ACTION_UP) {
                if (!dragging && !sg.isInProgress()) {
                    long now = System.currentTimeMillis();
                    if (now - lastTap < 300) {
                        float cur = scale();
                        float target = cur > minScale * 1.2f ? minScale : minScale * 2.5f;
                        m.postScale(target / cur, target / cur, e.getX(), e.getY());
                        fix();
                        setImageMatrix(m);
                        lastTap = 0;
                    } else {
                        lastTap = now;
                        final Runnable r = onSingleTap;
                        if (r != null) postDelayed(new Runnable() {
                            @Override public void run() { if (lastTap != 0) r.run(); }
                        }, 320);
                    }
                }
            }
            return true;
        }
    }
}
