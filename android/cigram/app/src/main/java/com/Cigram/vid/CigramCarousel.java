package com.Cigram.vid;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * كاروسيل سيكرام (v3.8):
 *  - Pager: FrameLayout يعترض السحب الأفقي (حتى والصفحات قابلة للنقر) فيتيح سحباً حراً يتبع الإصبع،
 *    مع استقرار ناعم إلى أقرب صفحة عند الإفلات، وبقاء النقر يعمل للفتح.
 *  - Indicator: نقاط بالأسفل مع مؤشر «دودة» يتمدّد بين نقطتين أثناء التمرير (يظهر مزدوجاً) ثم ينكمش.
 */
public final class CigramCarousel {

    private CigramCarousel() { }

    // =====================================================================
    //  محوّل يربط الكاروسيل بصفحاته (يُنفَّذ في شاشة الرئيسية)
    // =====================================================================
    public interface Adapter {
        int count();
        int current();
        View pageAt(int index);
        void onSettle(int newIndex);   // بعد استقرار الصفحة الجديدة: يُحدِّث الفهرس ويطبّع الحالة
        Indicator indicator();         // قد يكون null
    }

    // v5.0: إشعار الضغط المطوّل (يوقف شريط القصة مؤقتاً) والإفلات
    public interface HoldListener {
        void onHold();
        void onRelease();
    }

    // =====================================================================
    //  Pager — v10.41 «عمق ثلاثي الأبعاد» (coverflow)
    //  The middle card is big; its neighbours sit behind it, smaller, darker and turned.
    //  Cards are laid out right to left: the NEXT card is on the LEFT. Drag a card (a
    //  right swipe brings the next one in), tap a side card to bring it to the middle.
    // =====================================================================
    public static final class Pager extends FrameLayout {
        private static final float STEP = 0.64f;      // distance between card centres, in card widths
        private static final float SIDE_SCALE = 0.20f; // how much a neighbour shrinks
        private static final float SIDE_TURN = 28f;    // degrees a neighbour is turned
        private static final float SIDE_FADE = 0.25f;  // how much a neighbour darkens

        private HoldListener holdListener;
        private boolean held;
        private Adapter adapter;
        private final int touchSlop;
        private final float density;
        private float downX, downY;
        private boolean dragging;
        private float flowPos;        // 0 .. count; the card in the middle (fractional while moving)
        private float dragBase;
        private ValueAnimator flowAnim;
        /** v10.67 (TV only): one wide card that fills the page; the next one fades in over it. */
        private boolean tvBillboard;

        public Pager(Context c) {
            super(c);
            touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
            density = c.getResources().getDisplayMetrics().density;
            setClipChildren(false);
        }

        public void setAdapter(Adapter a) { this.adapter = a; }

        public void setHoldListener(HoldListener l) { this.holdListener = l; }

        public boolean isDragging() { return dragging; }

        private void fireHold() {
            if (!held) {
                held = true;
                if (holdListener != null) holdListener.onHold();
            }
        }

        private void fireRelease() {
            if (held) {
                held = false;
                if (holdListener != null) holdListener.onRelease();
            }
        }

        private void trackHold(MotionEvent ev) {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) fireHold();
            else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) fireRelease();
        }

        @Override protected void onDetachedFromWindow() {
            fireRelease();
            cancelFlow();
            super.onDetachedFromWindow();
        }

        @Override protected void onWindowVisibilityChanged(int visibility) {
            // v10.49: pages stay alive under the tab on top. Hidden = pause the auto-advance (it used to
            // keep ticking 60 times a second under a page nobody sees); visible again = resume.
            if (visibility != VISIBLE) fireHold();
            else fireRelease();
            super.onWindowVisibilityChanged(visibility);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            applyFlow();
        }

        // ---------------------------------------------------------------- flow

        private int count() { return adapter == null ? 0 : adapter.count(); }

        private static float wrap(float x, int n) {
            return n <= 0 ? x : x - n * Math.round(x / n);
        }

        private float cardWidth() {
            View p = adapter == null ? null : adapter.pageAt(0);
            if (p == null) return getWidth() * 0.6f;
            if (p.getWidth() > 0) return p.getWidth();
            return p.getLayoutParams() != null && p.getLayoutParams().width > 0
                    ? p.getLayoutParams().width : getWidth() * 0.6f;
        }

        /** v10.67: TV billboard on / off (the phone keeps the coverflow). */
        public void setTvBillboard(boolean on) {
            if (tvBillboard == on) return;
            tvBillboard = on;
            applyFlow();
        }

        /** Puts every card where {@code flowPos} says. */
        private void applyFlow() {
            int n = count();
            if (n == 0) return;
            if (tvBillboard) {
                float shift = getWidth() * 0.12f;
                for (int i = 0; i < n; i++) {
                    View page = adapter.pageAt(i);
                    if (page == null) continue;
                    float r = wrap(i - flowPos, n);
                    float alpha = Math.max(0f, 1f - Math.abs(r));
                    page.setScaleX(1f);
                    page.setScaleY(1f);
                    page.setRotationY(0f);
                    page.setTranslationZ(0f);
                    page.setTranslationX(-r * shift);
                    page.setAlpha(alpha);
                    page.setVisibility(alpha > 0.01f ? VISIBLE : INVISIBLE);
                }
                return;
            }
            float step = cardWidth() * STEP;
            for (int i = 0; i < n; i++) {
                View page = adapter.pageAt(i);
                if (page == null) continue;
                float r = wrap(i - flowPos, n);       // > 0: the card is on the left (comes next)
                float a = Math.abs(r);
                float near = Math.min(a, 1f);
                float far = Math.max(0f, a - 1f);
                float scale = 1f - SIDE_SCALE * near;
                float alpha = a <= 1f ? 1f - SIDE_FADE * a : (1f - SIDE_FADE) * Math.max(0f, 2f - a);
                page.setCameraDistance(9000f * density);
                page.setScaleX(scale);
                page.setScaleY(scale);
                page.setRotationY((r > 0 ? SIDE_TURN : -SIDE_TURN) * near);
                page.setTranslationX(-Math.signum(r) * step * (near + far * 0.35f));
                page.setTranslationZ(density * 10f * (1f - Math.min(a, 2f) / 2f));
                page.setAlpha(alpha);
                page.setVisibility(alpha > 0.01f ? VISIBLE : INVISIBLE);
            }
        }

        private void cancelFlow() {
            if (flowAnim != null) {
                flowAnim.removeAllListeners();
                flowAnim.removeAllUpdateListeners();
                flowAnim.cancel();
                flowAnim = null;
            }
        }

        /** Brings card {@code index} to the middle (the short way round). */
        public void flowTo(final int index, boolean animate) {
            final int n = count();
            if (n == 0) return;
            cancelFlow();
            final float from = flowPos;
            final float delta = wrap(index - from, n);
            if (!animate || Math.abs(delta) < 0.001f) {
                flowPos = index;
                applyFlow();
                return;
            }
            animateFlow(from, from + delta, index, 460L, null);
        }

        private void animateFlow(float from, float to, final int finalIndex, long ms, final Runnable done) {
            flowAnim = ValueAnimator.ofFloat(from, to);
            flowAnim.setDuration(ms);
            flowAnim.setInterpolator(new DecelerateInterpolator(1.6f));
            flowAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    flowPos = (Float) a.getAnimatedValue();
                    applyFlow();
                }
            });
            flowAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    if (cancelled) return;
                    flowPos = finalIndex;
                    applyFlow();
                    flowAnim = null;
                    if (done != null) done.run();
                }
            });
            flowAnim.start();
        }

        // ---------------------------------------------------------------- TV remote (v10.66)

        /** The card in the middle now (what OK opens on a TV), or null when there is none. */
        public View tvMiddlePage() {
            int n = count();
            if (n == 0 || adapter == null) return null;
            int i = Math.round(flowPos) % n;
            if (i < 0) i += n;
            return adapter.pageAt(i);
        }

        /** TV arrows: brings the next (+1, the card on the left) or previous card to the middle. */
        public boolean tvStep(int delta) {
            final int n = count();
            if (n < 2 || adapter == null || dragging) return false;
            int cur = Math.round(flowPos) % n;
            if (cur < 0) cur += n;
            int next = ((cur + delta) % n + n) % n;
            cancelFlow();
            final Adapter ad = adapter;
            final int idx = next;
            float from = flowPos;
            animateFlow(from, from + wrap(next - from, n), next, 320L, new Runnable() {
                @Override public void run() { ad.onSettle(idx); }
            });
            return true;
        }

        // ---------------------------------------------------------------- touch

        private boolean canSwipe() {
            return adapter != null && adapter.count() > 1 && getWidth() > 0;
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent ev) {
            trackHold(ev);
            if (!canSwipe()) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    dragging = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getRawX() - downX;
                    float dy = ev.getRawY() - downY;
                    if (!dragging && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                        dragging = true;
                        cancelFlow();
                        dragBase = flowPos;
                        downX = ev.getRawX();
                        return true;
                    }
                    break;
            }
            return false;
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            trackHold(ev);
            if (!canSwipe()) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - downX;
                    if (!dragging) {
                        if (Math.abs(dx) > touchSlop) {
                            dragging = true;
                            cancelFlow();
                            dragBase = flowPos;
                            downX = ev.getRawX();
                        }
                        return true;
                    }
                    float step = Math.max(1f, cardWidth() * STEP);
                    float moved = Math.max(-1f, Math.min(1f, dx / step));   // right swipe: next card comes in
                    flowPos = dragBase + moved;
                    applyFlow();
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!dragging) return false;
                    dragging = false;
                    float moved = flowPos - dragBase;
                    float base = Math.round(dragBase);
                    float target = Math.abs(moved) > 0.3f ? base + Math.signum(moved) : base;
                    final int n = count();
                    int idx = Math.round(target) % n;
                    if (idx < 0) idx += n;
                    final int finalIdx = idx;
                    final Adapter ad = adapter;
                    animateFlow(flowPos, target, finalIdx, 260L, new Runnable() {
                        @Override public void run() { ad.onSettle(finalIdx); }
                    });
                    return true;
                }
            }
            return false;
        }
    }

    // =====================================================================
    //  Indicator — نقاط + مؤشر دودة
    // =====================================================================
    public static final class Indicator extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF pill = new RectF();
        private final float d;
        private int count;
        private float progress;
        private int idle = 0xFF13323F;
        private int active = 0xFFE21B14;
        private ValueAnimator animator;

        public Indicator(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
        }

        public void setColors(int idleColor, int activeColor) {
            this.idle = idleColor;
            this.active = activeColor;
            invalidate();
        }

        public void setCount(int n) {
            if (n != count) {
                count = n;
                if (progress > count - 1) progress = Math.max(0, count - 1);
                requestLayout();
                invalidate();
            }
        }

        public void setCurrent(int index) {
            cancelAnim();
            progress = clampIndex(index);
            invalidate();
        }

        public void setProgress(float p) {
            cancelAnim();
            progress = Math.max(0f, Math.min(count - 1, p));
            invalidate();
        }

        public void animateTo(int index) {
            final float target = clampIndex(index);
            cancelAnim();
            animator = ValueAnimator.ofFloat(progress, target);
            animator.setDuration(300L);
            animator.setInterpolator(new DecelerateInterpolator(1.8f));
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    progress = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            animator.start();
        }

        private void cancelAnim() {
            if (animator != null) { animator.cancel(); animator = null; }
        }

        private float clampIndex(int index) {
            if (count <= 0) return 0f;
            if (index < 0) index = 0;
            if (index > count - 1) index = count - 1;
            return index;
        }

        private float gap() { return 15f * d; }
        private float dotR() { return 3.2f * d; }
        private float pillR() { return 4.0f * d; }

        @Override protected void onMeasure(int w, int h) {
            int width = Math.max(1, (int) (Math.max(1, count) * gap()));
            int height = (int) (14f * d);
            setMeasuredDimension(resolveSize(width, w), resolveSize(height, h));
        }

        private float centerX(int i) {
            float total = count * gap();
            float startX = (getWidth() - total) / 2f + gap() / 2f;
            return startX + i * gap();
        }

        private static float smooth(float t) {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            return t * t * (3f - 2f * t);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (count <= 0) return;
            float cy = getHeight() / 2f;

            // النقاط الخاملة
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(idle);
            for (int i = 0; i < count; i++) {
                canvas.drawCircle(centerX(i), cy, dotR(), paint);
            }

            // مؤشر الدودة: يتمدّد بين النقطتين أثناء الانتقال
            int a = (int) Math.floor(progress);
            if (a < 0) a = 0;
            if (a > count - 1) a = count - 1;
            int b = Math.min(a + 1, count - 1);
            float frac = progress - a;

            float xa = centerX(a);
            float xb = centerX(b);
            float lead = xa + (xb - xa) * smooth(Math.min(1f, frac / 0.75f));
            float trail = xa + (xb - xa) * smooth(Math.max(0f, (frac - 0.25f) / 0.75f));
            float left = Math.min(lead, trail) - pillR();
            float right = Math.max(lead, trail) + pillR();

            paint.setColor(active);
            pill.set(left, cy - pillR(), right, cy + pillR());
            canvas.drawRoundRect(pill, pillR(), pillR(), paint);
        }
    }

    // =====================================================================
    //  StoryBars (v5.0) — أشرطة تقدم بأسلوب القصص، تمتلي من اليمين لليسار (RTL)
    // =====================================================================
    public static final class StoryBars extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF seg = new RectF();
        private final float d;
        private int count;
        private int active;
        private float fraction;
        private boolean rtl = true;
        private int trackColor = 0x59FFFFFF;
        private int doneColor = 0xFFF1EFE8;
        private int fillColor = 0xFFE21B14;

        public StoryBars(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
        }

        public void setRtl(boolean r) { this.rtl = r; invalidate(); }

        public void setColors(int track, int done, int fill) {
            trackColor = track;
            doneColor = done;
            fillColor = fill;
            invalidate();
        }

        public void setCount(int n) {
            if (n != count) {
                count = Math.max(0, n);
                if (active > count - 1) active = Math.max(0, count - 1);
                invalidate();
            }
        }

        public void setActive(int index) {
            if (count <= 0) { active = 0; }
            else active = Math.max(0, Math.min(count - 1, index));
            fraction = 0f;
            invalidate();
        }

        public void setFraction(float f) {
            fraction = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(resolveSize((int) (120f * d), w), resolveSize((int) (3f * d), h));
        }

        @Override protected void onDraw(Canvas canvas) {
            if (count <= 0) return;
            float w = getWidth();
            float h = getHeight();
            float gap = 4f * d;
            float segW = (w - gap * (count - 1)) / count;
            if (segW <= 0f) return;
            float rad = h / 2f;
            paint.setStyle(Paint.Style.FILL);
            for (int s = 0; s < count; s++) {
                float x = rtl ? (w - (s + 1) * segW - s * gap) : (s * (segW + gap));
                paint.setColor(trackColor);
                seg.set(x, 0f, x + segW, h);
                canvas.drawRoundRect(seg, rad, rad, paint);
                float f = s < active ? 1f : (s == active ? fraction : 0f);
                if (f > 0f) {
                    paint.setColor(s < active ? doneColor : fillColor);
                    float fw = segW * f;
                    if (rtl) seg.set(x + segW - fw, 0f, x + segW, h);
                    else seg.set(x, 0f, x + fw, h);
                    canvas.drawRoundRect(seg, rad, rad, paint);
                }
            }
        }
    }
}
