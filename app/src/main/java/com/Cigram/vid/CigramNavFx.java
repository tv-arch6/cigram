package com.Cigram.vid;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.ViewTreeObserver;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.AbsListView;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * مؤثرات الشريط السفلي (الرئيسية / القنوات / البحث / الحساب):
 *  - Glass: زجاج سائل فائق الحواف (حافة عاكسة، توهج داخلي، حواف منشورية، لمعة تتبع المؤشر).
 *  - slide: حركة مؤشر سائلة (تمدد + ارتداد خفيف) تتزامن مع تلوين الأيقونات والنصوص.
 *  - attachPress / tick: استجابة فورية عند اللمس مع اهتزاز خفيف.
 *  - reveal: ظهور ناعم لمحتوى الصفحة الجديدة بينما يبقى الشريط ثابتاً.
 *  - scrollToTop: النقر على التبويب الحالي يعيد الصفحة للأعلى.
 */
public final class CigramNavFx {
    /** Live glass effect of the old bar (page snapshot + blur while scrolling). Off since v10.42. */
    public static final boolean GLASS_FX = false;


    /** المدة قبل فتح الشاشة الجديدة لتظهر حركة المؤشر كاملة في النافذة الحالية. */
    public static final long LAUNCH_DELAY_MS = 80L;
    /** مدة حركة المؤشر عند النقر. */
    public static final long SLIDE_MS = 300L;

    /** v4.4: minimum time between two backdrop snapshots while the page scrolls (~25 per second). */
    private static final long CAPTURE_GAP_MS = 40L;
    /**
     * Perf: a capture draws the WHOLE page into a software bitmap and blurs it. Doing that in every
     * frame of a scroll (v5.1 frame-sync) was the main cause of scroll stutter. Now at most ~9 per
     * second while the page moves, plus one trailing capture so the glass settles on the final position.
     */
    private static final long SYNC_GAP_MS = 110L;
    /** v4.4: refresh of the backdrop while nothing scrolls (carousel / images loading). */
    private static final long IDLE_CAPTURE_MS = 1000L;

    private static final int LABEL_OFF = 0xFFADBBC2;
    private static final int LABEL_ON = 0xFFFFFFFF;
    /** لون الحد الرصاصي للشريط السفلي وللكبسولة المحددة. */
    private static final int RIM_GRAY = 0xFF8D99A0;

    private CigramNavFx() { }

    /** يُستدعى من الشريط لبدء حركة المؤشر من تبويب إلى آخر. */
    public interface Go {
        void run(int from, int to);
    }

    public static Glass barGlass(Context c) {
        return new Glass(c, 0xE2071A24, 0xE8020C12, 1f);
    }

    /**
     * v5.29: the selected tab no longer shows a framed capsule: the selection is the white-filled
     * icon and the bright label only. The capsule view (and its slide animation) stays in place
     * for the page code that positions it, but draws nothing.
     */
    public static Glass capsuleGlass(Context c) {
        Glass g = new Glass(c, 0xCC0E1820, 0xCC060C12, 0.70f);
        g.setHidden(true);
        return g;
    }

    // ------------------------------------------------------------------
    // الزجاج السائل
    // ------------------------------------------------------------------

    /**
     * Live, blurred snapshot of the page that sits BEHIND the bar. The glass of the bar and of
     * the selected-tab capsule draw it refracted, so what shows inside the buttons is the real
     * content moving under them while the user scrolls.
     */
    public static final class Backdrop {
        // v5.1: finer snapshot (was 1/3) and lighter blur so the glass reads clearly
        private static final int DIV = 2;
        private final View content;
        private final View bar;
        private Bitmap bmp;
        private Canvas cv;
        private int[] px;
        private int[] tmp;
        private boolean broken;

        Backdrop(View content, View bar) {
            this.content = content;
            this.bar = bar;
        }

        boolean ready() { return !broken && bmp != null; }

        boolean isBroken() { return broken; }

        /** @return true when the snapshot was refreshed. */
        boolean capture() {
            if (broken) return false;
            try {
                int w = bar.getWidth(), h = bar.getHeight();
                if (w <= 0 || h <= 0 || content.getWidth() <= 0) return false;
                int bw = Math.max(12, w / DIV), bh = Math.max(8, h / DIV);
                if (bmp == null || bmp.getWidth() != bw || bmp.getHeight() != bh) {
                    bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    cv = new Canvas(bmp);
                    px = new int[bw * bh];
                    tmp = new int[bw * bh];
                }
                bmp.eraseColor(0xFF00121A);
                int save = cv.save();
                cv.scale(bw / (float) w, bh / (float) h);
                cv.translate(-bar.getLeft(), -bar.getTop());
                // the page may be mid-reveal (fade + slide): draw it exactly as the screen shows it
                cv.translate(content.getLeft() + content.getTranslationX(),
                        content.getTop() + content.getTranslationY());
                float pageAlpha = content.getAlpha();
                if (pageAlpha < 0.999f) {
                    cv.saveLayerAlpha(0f, 0f, content.getWidth(), content.getHeight(),
                            Math.round(pageAlpha * 255f));
                }
                content.draw(cv);
                cv.restoreToCount(save);
                bmp.getPixels(px, 0, bw, 0, 0, bw, bh);
                boxBlur(px, tmp, bw, bh, 2, true);
                boxBlur(tmp, px, bw, bh, 2, true);
                boxBlur(px, tmp, bw, bh, 1, false);
                bmp.setPixels(tmp, 0, bw, 0, 0, bw, bh);
                return true;
            } catch (Throwable error) {
                // e.g. a hardware bitmap that cannot be drawn on a software canvas:
                // fall back to the plain tinted glass instead of crashing
                broken = true;
                android.util.Log.w("CigramNavFx", "backdrop disabled", error);
                return false;
            }
        }

        /** Region of the bar snapshot covered by view v (a child of the bar), in bitmap pixels. */
        boolean regionOf(View v, float[] out) {
            if (!ready() || v == null || bar.getWidth() <= 0 || bar.getHeight() <= 0) return false;
            float sx = bmp.getWidth() / (float) bar.getWidth();
            float sy = bmp.getHeight() / (float) bar.getHeight();
            float x0 = v.getLeft() + v.getTranslationX();
            out[0] = x0 * sx;
            out[1] = v.getTop() * sy;
            out[2] = (x0 + v.getWidth()) * sx;
            out[3] = (v.getTop() + v.getHeight()) * sy;
            return true;
        }

        /** Running-sum box blur of radius r along one axis (edge pixels are repeated). */
        private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
            int len = horizontal ? w : h;
            int lines = horizontal ? h : w;
            int step = horizontal ? 1 : w;
            int lineStep = horizontal ? w : 1;
            int div = r * 2 + 1;
            for (int line = 0; line < lines; line++) {
                int base = line * lineStep;
                int a = 0, rr = 0, g = 0, b = 0;
                for (int k = -r; k <= r; k++) {
                    int c = in[base + Math.max(0, Math.min(len - 1, k)) * step];
                    a += c >>> 24; rr += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255;
                }
                for (int i = 0; i < len; i++) {
                    out[base + i * step] = ((a / div) << 24) | ((rr / div) << 16) | ((g / div) << 8) | (b / div);
                    int cOut = in[base + Math.max(0, i - r) * step];
                    int cIn = in[base + Math.min(len - 1, i + r + 1) * step];
                    a += (cIn >>> 24) - (cOut >>> 24);
                    rr += ((cIn >> 16) & 255) - ((cOut >> 16) & 255);
                    g += ((cIn >> 8) & 255) - ((cOut >> 8) & 255);
                    b += (cIn & 255) - (cOut & 255);
                }
            }
        }
    }

    public static final class Glass extends Drawable {
        private static final int INNER_BANDS = 6;

        private final float d;
        private final int fillTop;
        private final int fillBottom;
        private final float rim;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final RectF tmp = new RectF();
        private final Path clip = new Path();
        private final Shader glintShader = new RadialGradient(0f, 0f, 1f,
                new int[]{0x78FFFFFF, 0x22FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);

        private Shader fillShader;
        private Shader sheenShader;
        private Shader topShader;
        private Shader rimV;
        private Shader rimD;
        private Shader cyanShader;
        private Shader warmShader;

        private float glintX = Float.NaN;
        private int opacity = 255;

        // ---- real backdrop: blurred + refracted live content behind the bar ----
        private static final int MESH_COLS = 24;
        private static final int MESH_ROWS = 4;
        private final float[] mesh = new float[(MESH_COLS + 1) * (MESH_ROWS + 1) * 2];
        private final float[] srcBox = new float[4];
        private final Paint backdropPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private Backdrop backdrop;
        private boolean capsuleLens;

        /** lens = true for the selected-tab capsule (stronger magnification + refraction). */
        public void setBackdrop(Backdrop b, boolean lens) {
            backdrop = b;
            capsuleLens = lens;
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(lens ? 1.35f : 1.15f);
            float k = lens ? 1.18f : 1.05f; // slight brightening so the glass reads as light
            // colours of the real content stay true: no brightening, no white veil
            backdropPaint.setColorFilter(new ColorMatrixColorFilter(cm));
            invalidateSelf();
        }

        private Bitmap lensBmp;
        private Canvas lensCanvas;
        private final Rect lensSrc = new Rect();
        private final Rect lensDst = new Rect();
        private final Paint lensCopy = new Paint(Paint.FILTER_BITMAP_FLAG);

        private boolean backdropOn = true;
        private boolean hidden;

        /** Hidden = draws nothing at all (used for the selected-tab capsule). */
        public void setHidden(boolean value) {
            hidden = value;
            invalidateSelf();
        }

        /** Off = plain solid glass: nothing of the page shows through (used on the Account tab). */
        public void setBackdropEnabled(boolean on) {
            backdropOn = on;
            invalidateSelf();
        }

        private boolean drawBackdrop(Canvas canvas) {
            Backdrop b = backdrop;
            if (!backdropOn || b == null || !b.ready()) return false;
            Bitmap bm = b.bmp;
            if (capsuleLens) {
                // the capsule shows only the part of the snapshot that is under it
                Callback cb = getCallback();
                if (!(cb instanceof View) || !b.regionOf((View) cb, srcBox)) return false;
                int l = Math.max(0, Math.round(srcBox[0]));
                int t = Math.max(0, Math.round(srcBox[1]));
                int r = Math.min(bm.getWidth(), Math.round(srcBox[2]));
                int bt = Math.min(bm.getHeight(), Math.round(srcBox[3]));
                if (r - l < 2 || bt - t < 2) return false;
                int lw = r - l, lh = bt - t;
                if (lensBmp == null || lensBmp.getWidth() != lw || lensBmp.getHeight() != lh) {
                    lensBmp = Bitmap.createBitmap(lw, lh, Bitmap.Config.ARGB_8888);
                    lensCanvas = new Canvas(lensBmp);
                }
                lensSrc.set(l, t, r, bt);
                lensDst.set(0, 0, lw, lh);
                // v4.4: one Canvas per lens bitmap instead of a new one on every frame of the slide
                lensCanvas.drawBitmap(bm, lensSrc, lensDst, lensCopy);
                bm = lensBmp;
            }

            // Refraction as a mesh warp: a >= 1 magnifies the middle of the glass and
            // compresses the content near its rim (d = x * (a + (1 - a) * x^2), d(1) = 1).
            // The lens gets stronger while the page scrolls (flow > 0).
            float ax = capsuleLens ? 1.30f + 0.14f * flow : 1.10f + 0.06f * flow;
            float ay = 1f + (ax - 1f) * 0.6f;
            float cx = rect.centerX(), cy = rect.centerY();
            float hw = rect.width() / 2f, hh = rect.height() / 2f;
            int i = 0;
            for (int row = 0; row <= MESH_ROWS; row++) {
                float y = 2f * row / (float) MESH_ROWS - 1f;
                float dy = y * (ay + (1f - ay) * y * y);
                for (int col = 0; col <= MESH_COLS; col++) {
                    float x = 2f * col / (float) MESH_COLS - 1f;
                    float dx = x * (ax + (1f - ax) * x * x);
                    mesh[i++] = cx + dx * hw;
                    mesh[i++] = cy + dy * hh;
                }
            }
            backdropPaint.setAlpha(opacity);
            canvas.drawBitmapMesh(bm, MESH_COLS, MESH_ROWS, mesh, 0, null, 0, backdropPaint);
            return true;
        }

        // ---- scroll-driven liquid shimmer inside the glass ----
        private final Matrix flowM = new Matrix();
        private Shader bandWhite;
        private Shader bandCyan;
        private Shader bandWarm;
        private float flow;          // 0..1 energy, decays on its own
        private float flowPhase;     // px the bands have travelled
        private long flowLast;

        /**
         * Feeds the shimmer with the scroll movement of the page behind the bar.
         * dx/dy > 0 means the content moved LEFT / UP (the finger swiped left / up): that is
         * the direction the glass light flows. The opposite direction flows back, softer.
         */
        public void pushFlow(float dx, float dy) {
            float move = dx + dy;
            if (move == 0f) return;
            float gain = move > 0f ? 1f : 0.6f;
            flowPhase += move * 0.9f * gain;
            flow = Math.min(1f, flow + Math.min(70f * d, Math.abs(move)) * gain / (45f * d));
            if (flowLast == 0L) flowLast = SystemClock.uptimeMillis();
            invalidateSelf();
        }

        Glass(Context c, int fillTop, int fillBottom, float rim) {
            this.d = c.getResources().getDisplayMetrics().density;
            this.fillTop = fillTop;
            this.fillBottom = fillBottom;
            this.rim = rim;
        }

        /** مكان اللمعة على الحافة (بإحداثيات الشريط). */
        public void setGlintX(float x) {
            if (Float.isNaN(glintX) || Math.abs(glintX - x) > 0.5f) {
                glintX = x;
                invalidateSelf();
            }
        }

        @Override protected void onBoundsChange(Rect b) {
            super.onBoundsChange(b);
            if (b.width() <= 0 || b.height() <= 0) return;
            float l = b.left, t = b.top, r = b.right, bt = b.bottom;
            fillShader = new LinearGradient(0f, t, 0f, bt, fillTop, fillBottom, Shader.TileMode.CLAMP);
            sheenShader = new LinearGradient(l, t, r, bt,
                    new int[]{0x3EFFFFFF, 0x0EFFFFFF, 0x00FFFFFF, 0x0EFFFFFF, 0x32FFFFFF},
                    new float[]{0f, 0.22f, 0.5f, 0.78f, 1f}, Shader.TileMode.CLAMP);
            topShader = new LinearGradient(0f, t, 0f, bt,
                    new int[]{0x32FFFFFF, 0x09FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.38f, 0.62f}, Shader.TileMode.CLAMP);
            rimV = new LinearGradient(0f, t, 0f, bt,
                    new int[]{0xCCB9C3C9, 0x4AB9C3C9, 0x40B9C3C9, 0xA0B9C3C9},
                    new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP);
            rimD = new LinearGradient(l, t, r, bt,
                    new int[]{0xFFC5CED3, 0x40B9C3C9, 0x24B9C3C9, 0x40B9C3C9, 0xF0C5CED3},
                    new float[]{0f, 0.18f, 0.5f, 0.82f, 1f}, Shader.TileMode.CLAMP);
            cyanShader = new LinearGradient(l, t, r, bt,
                    new int[]{0xCCD2DADE, 0x00D2DADE, 0x00D2DADE},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            warmShader = new LinearGradient(l, t, r, bt,
                    new int[]{0x00A9B4BA, 0x00A9B4BA, 0xB0A9B4BA},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);

            // repeating soft light bands used by the scroll shimmer (period set per width)
            float period = Math.max(46f * d, (r - l) * 0.5f);
            float[] pos = {0f, 0.34f, 0.5f, 0.66f, 1f};
            bandWhite = new LinearGradient(0f, 0f, period, 0f,
                    new int[]{0x00FFFFFF, 0x00FFFFFF, 0x8CFFFFFF, 0x00FFFFFF, 0x00FFFFFF},
                    pos, Shader.TileMode.REPEAT);
            bandCyan = new LinearGradient(0f, 0f, period * 1.25f, 0f,
                    new int[]{0x004FE3FF, 0x004FE3FF, 0x664FE3FF, 0x004FE3FF, 0x004FE3FF},
                    pos, Shader.TileMode.REPEAT);
            bandWarm = new LinearGradient(0f, 0f, period * 0.8f, 0f,
                    new int[]{0x00FF8AE0, 0x00FF8AE0, 0x55FF8AE0, 0x00FF8AE0, 0x00FF8AE0},
                    pos, Shader.TileMode.REPEAT);
        }

        /** The lens strength follows the scroll energy and relaxes back by itself. */
        private void decayFlow() {
            if (flow <= 0f) return;
            long now = SystemClock.uptimeMillis();
            long dt = flowLast == 0L ? 16L : Math.min(50L, Math.max(0L, now - flowLast));
            flowLast = now;
            flow *= (float) Math.pow(0.5, dt / 180.0);
            if (flow < 0.015f) { flow = 0f; flowLast = 0L; }
            invalidateSelf();
        }

        private void paint(Shader s, int alpha) {
            p.setShader(s);
            int a = alpha * opacity / 255;
            p.setAlpha(a < 0 ? 0 : (a > 255 ? 255 : a));
        }

        @Override public void draw(Canvas canvas) {
            if (hidden) return;
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0 || fillShader == null) return;
            rect.set(b);
            decayFlow();
            float r = Math.min(rect.width(), rect.height()) / 2f;
            clip.reset();
            clip.addRoundRect(rect, r, r, Path.Direction.CW);

            int save = canvas.save();
            canvas.clipPath(clip);

            // الزجاج الحقيقي: المحتوى الحي خلف الشريط، مموّهاً ومكسوراً كعدسة
            boolean real = false;
            try { real = drawBackdrop(canvas); } catch (Throwable ignored) { real = false; }

            // جسم الزجاج (شفاف جزئياً عند وجود المحتوى الحقيقي خلفه)
            p.setStyle(Paint.Style.FILL);
            paint(fillShader, real ? (capsuleLens ? 135 : 110) : 255);
            canvas.drawRect(rect, p);


            // توهج الحافة الداخلي (طبقات متدرجة نحو الداخل)
            p.setStyle(Paint.Style.STROKE);
            float w = 1.4f * d;
            p.setStrokeWidth(w);
            for (int i = 0; i < INNER_BANDS; i++) {
                float inset = w * (i + 0.5f);
                float k = 1f - i / (float) INNER_BANDS;
                float k2 = k * k;
                tmp.set(rect);
                tmp.inset(inset, inset);
                float rr = Math.max(0f, r - inset);
                paint(rimD, (int) (60f * rim * k2));
                canvas.drawRoundRect(tmp, rr, rr, p);
                paint(rimV, (int) (45f * rim * k2));
                canvas.drawRoundRect(tmp, rr, rr, p);
            }

            // لمعة سائلة تتبع المؤشر على الحافة العليا والسفلى
            if (!Float.isNaN(glintX)) {
                p.setStyle(Paint.Style.FILL);
                p.setShader(glintShader);
                float gw = rect.height() * 1.5f;
                float gh = rect.height() * 0.55f;
                p.setAlpha(Math.min(255, (int) (70f * rim) * opacity / 255));
                canvas.save();
                canvas.translate(glintX, rect.top);
                canvas.scale(gw, gh);
                canvas.drawCircle(0f, 0f, 1f, p);
                canvas.restore();
                p.setAlpha(Math.min(255, (int) (40f * rim) * opacity / 255));
                canvas.save();
                canvas.translate(glintX, rect.bottom);
                canvas.scale(gw * 0.9f, gh * 0.8f);
                canvas.drawCircle(0f, 0f, 1f, p);
                canvas.restore();
            }

            // حواف منشورية (سماوي من أعلى اليسار، دافئ من أسفل اليمين)
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f * d);
            tmp.set(rect);
            tmp.inset(d / 2f, d / 2f);
            float rr = Math.max(0f, r - d / 2f);
            canvas.save();
            canvas.translate(0.8f * d, 0.8f * d);
            paint(cyanShader, (int) (255f * rim));
            canvas.drawRoundRect(tmp, rr, rr, p);
            canvas.restore();
            canvas.save();
            canvas.translate(-0.8f * d, -0.8f * d);
            paint(warmShader, (int) (255f * rim));
            canvas.drawRoundRect(tmp, rr, rr, p);
            canvas.restore();
            canvas.restoreToCount(save);

            // الحد الرصاصي الموحّد: يحيط بالشريط وبالكبسولة المحددة من كل الجهات (بما فيها السفلية)
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f * d);
            p.setShader(null);
            p.setColor(RIM_GRAY);
            int ringA = (int) (235f * (0.6f + 0.4f * rim)) * opacity / 255;
            p.setAlpha(ringA < 0 ? 0 : (ringA > 255 ? 255 : ringA));
            canvas.drawRoundRect(tmp, rr, rr, p);

            // الحافة العاكسة الخارجية الرفيعة
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(d);
            paint(rimD, (int) (255f * rim));
            canvas.drawRoundRect(tmp, rr, rr, p);
            paint(rimV, (int) (200f * rim));
            canvas.drawRoundRect(tmp, rr, rr, p);

            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
        }

        @Override public void setAlpha(int a) {
            opacity = a;
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter filter) { }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

        @Override public void getOutline(Outline outline) {
            Rect b = getBounds();
            outline.setRoundRect(b, Math.min(b.width(), b.height()) / 2f);
            outline.setAlpha(0.55f);
        }
    }

    // ------------------------------------------------------------------
    // حالة التبويبات (لون النص/الأيقونة) أثناء الحركة
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // تعبئة فراغ الأيقونة المحددة (تلقائياً لأي أيقونة PNG مفرغة)
    // ------------------------------------------------------------------

    /** لون تعبئة فراغ الأيقونة المحددة (أبيض). غيّره هنا فقط. */
    private static final int ICON_FILL = 0xFFFFFFFF;
    /** لون خطوط التفاصيل الواقعة داخل التعبئة (عدسة البحث، مثلث التشغيل ...). */
    private static final int ICON_DETAIL = 0xFF04161D;

    /**
     * يكتشف الفراغات المغلقة داخل أي أيقونة: كل بكسل شفاف لا يمكن الوصول إليه من حافة الصورة
     * من دون عبور خط الأيقونة. لا يعتمد على شكل أيقونة بعينها، فأي أيقونة تضعها مستقبلاً
     * تمتلئ فراغاتها تلقائياً. الفجوات الصغيرة جداً في الخط (أقل من ~1.2% من حجم الصورة)
     * تُغلَق افتراضياً حتى لا يتسرّب "الخارج" إلى الداخل.
     */
    static final class HoleMask {
        private HoleMask() { }

        static final byte FILL = 1;     // فراغ داخلي يُملأ
        static final byte DETAIL = 2;   // خط تفصيلي داخل منطقة مملوءة (يُرسم داكناً كي يبقى مقروءاً)

        /** @return علامات لكل بكسل (FILL / DETAIL)، أو null إن لم توجد فراغات مغلقة. */
        static byte[] find(int[] argb, int w, int h) {
            int n = w * h;
            boolean[] solid = new boolean[n];
            int solidCount = 0;
            for (int i = 0; i < n; i++) {
                if ((argb[i] >>> 24) > 40) { solid[i] = true; solidCount++; }
            }
            // أيقونة ممتلئة أصلاً أو صورة (صورة شخصية، شخصية صلبة): ليست أيقونة خطية، لا تعبئة
            if (solidCount == 0 || solidCount > 0.45f * n) return null;

            int r = Math.max(1, Math.round(Math.min(w, h) * 0.012f));
            boolean[] blocked = dilate(solid, w, h, r);
            int[] depth = nesting(blocked, w, h);

            // عمق 0 = خارج الأيقونة، 1 = الخط، 2 = فراغ داخل الخط، 3 = خط داخل الفراغ، 4 = فراغ داخله ...
            // نملأ الفراغات ذات العمق 2 و 6 و 10 فقط. الفراغ داخل جزيرة (مثل مثلث التشغيل داخل
            // الشاشة، أو عدسة البحث) يبقى فارغاً، والخط الذي يقع داخل منطقة مملوءة (عمق 3 و 7 ...)
            // يُرسم داكناً، فتبقى تفاصيل الأيقونة ظاهرة فوق التعبئة البيضاء.
            boolean[] core = new boolean[n];
            int count = 0;
            for (int i = 0; i < n; i++) {
                if (!blocked[i] && depth[i] >= 2 && depth[i] % 4 == 2) { core[i] = true; count++; }
            }
            if (count < 0.002f * n) return null;
            // يرجع بالقدر الذي قلّصه إغلاق الفجوات، ويتسع بكسلاً أسفل خط الأيقونة كي لا يظهر
            // خيط فاصل عند حواف التنعيم
            boolean[] fill = dilate(core, w, h, r + 1);
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                byte f = 0;
                if (fill[i]) f |= FILL;
                if (blocked[i] && depth[i] >= 3 && depth[i] % 4 == 3) f |= DETAIL;
                out[i] = f;
            }
            return out;
        }

        /**
         * عمق التداخل لكل بكسل: عدد مرات عبور حدّ (خط/فراغ) من حافة الصورة إليه.
         * البحث بالطبقات: كل طبقة تغمر نوعاً واحداً من البكسلات.
         */
        static int[] nesting(boolean[] blocked, int w, int h) {
            int n = w * h;
            int[] depth = new int[n];
            java.util.Arrays.fill(depth, -1);
            int[] q = new int[n];
            int[] pend = new int[n];
            int qh = 0, qt = 0, level = 0;
            for (int x = 0; x < w; x++) {
                int top = x, bot = (h - 1) * w + x;
                if (!blocked[top] && depth[top] == -1) { depth[top] = 0; q[qt++] = top; }
                if (!blocked[bot] && depth[bot] == -1) { depth[bot] = 0; q[qt++] = bot; }
            }
            for (int y = 0; y < h; y++) {
                int left = y * w, right = y * w + w - 1;
                if (!blocked[left] && depth[left] == -1) { depth[left] = 0; q[qt++] = left; }
                if (!blocked[right] && depth[right] == -1) { depth[right] = 0; q[qt++] = right; }
            }
            while (qh < qt) {
                int pc = 0;
                while (qh < qt) {
                    int p = q[qh++];
                    int x = p % w, y = p / w;
                    for (int k = 0; k < 4; k++) {
                        int nb;
                        if (k == 0) { if (x == 0) continue; nb = p - 1; }
                        else if (k == 1) { if (x == w - 1) continue; nb = p + 1; }
                        else if (k == 2) { if (y == 0) continue; nb = p - w; }
                        else { if (y == h - 1) continue; nb = p + w; }
                        if (depth[nb] != -1) continue;
                        if (blocked[nb] == blocked[p]) {
                            depth[nb] = level;
                            q[qt++] = nb;
                        } else {
                            depth[nb] = -2;          // ينتظر الطبقة التالية
                            pend[pc++] = nb;
                        }
                    }
                }
                level++;
                for (int i = 0; i < pc; i++) {
                    depth[pend[i]] = level;
                    q[qt++] = pend[i];
                }
            }
            return depth;
        }

        /** تمدد مربع بنصف قطر r (مرحلتان أفقية ثم رأسية، O(n)). */
        static boolean[] dilate(boolean[] src, int w, int h, int r) {
            boolean[] tmp = new boolean[w * h];
            boolean[] out = new boolean[w * h];
            int[] pre = new int[Math.max(w, h) + 1];
            for (int y = 0; y < h; y++) {
                int base = y * w;
                for (int x = 0; x < w; x++) pre[x + 1] = pre[x] + (src[base + x] ? 1 : 0);
                for (int x = 0; x < w; x++) {
                    int a = Math.max(0, x - r), b = Math.min(w, x + r + 1);
                    tmp[base + x] = pre[b] - pre[a] > 0;
                }
            }
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) pre[y + 1] = pre[y] + (tmp[y * w + x] ? 1 : 0);
                for (int y = 0; y < h; y++) {
                    int a = Math.max(0, y - r), b = Math.min(h, y + r + 1);
                    out[y * w + x] = pre[b] - pre[a] > 0;
                }
            }
            return out;
        }
    }

    /**
     * يغلّف أيقونة الـ ImageView: يرسم تعبئة الفراغ الداخلي تحت خط الأيقونة بشفافية = درجة التحديد،
     * والأيقونة الأصلية كما هي فوقها (الفلاتر والتلوين تمر للأيقونة الأصلية فقط).
     */
    static final class FillIcon extends Drawable {
        private static final java.util.WeakHashMap<Drawable, Boolean> REJECTED =
                new java.util.WeakHashMap<Drawable, Boolean>();

        private final Drawable base;
        final boolean light;
        private final Bitmap fill;
        private final Bitmap detail;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private float amount;

        private FillIcon(Drawable base, Bitmap fill, Bitmap detail, boolean light) {
            this.base = base;
            this.fill = fill;
            this.detail = detail;
            this.light = light;
        }

        Drawable base() { return base; }

        /** null إذا لم تكن الأيقونة صالحة للتعبئة (ليست PNG مفرغة أو بلا فراغات مغلقة). */
        static FillIcon wrap(Drawable d, boolean light) {
            if (!(d instanceof android.graphics.drawable.BitmapDrawable)) return null;
            if (REJECTED.containsKey(d)) return null;
            try {
                Bitmap bm = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                if (bm == null || bm.isRecycled()) return null;
                int w = bm.getWidth(), h = bm.getHeight();
                int[] px = new int[w * h];
                bm.getPixels(px, 0, w, 0, 0, w, h);
                byte[] kinds = HoleMask.find(px, w, h);
                if (kinds == null) { REJECTED.put(d, Boolean.TRUE); return null; }
                int[] fillPx = new int[px.length];
                int[] detailPx = new int[px.length];
                for (int i = 0; i < px.length; i++) {
                    if ((kinds[i] & HoleMask.FILL) != 0) fillPx[i] = light ? 0xFF111418 : ICON_FILL;
                    if ((kinds[i] & HoleMask.DETAIL) != 0) {
                        // نفس شفافية خط الأيقونة بلون داكن، فتحتفظ بحوافها المنعّمة
                        detailPx[i] = (px[i] & 0xFF000000) | ((light ? 0xFFFFFFFF : ICON_DETAIL) & 0x00FFFFFF);
                    }
                }
                Bitmap fill = Bitmap.createBitmap(fillPx, w, h, Bitmap.Config.ARGB_8888);
                Bitmap detail = Bitmap.createBitmap(detailPx, w, h, Bitmap.Config.ARGB_8888);
                if (light) {   // Light mode: the outline itself is black (the white fill above stays inside it)
                    d.mutate();
                    d.setColorFilter(0xFF111418, android.graphics.PorterDuff.Mode.SRC_IN);
                }
                return new FillIcon(d, fill, detail, light);
            } catch (Throwable t) {
                REJECTED.put(d, Boolean.TRUE);
                return null;
            }
        }

        void setAmount(float a) {
            a = a < 0f ? 0f : (a > 1f ? 1f : a);
            if (Math.abs(a - amount) > 0.004f) {
                amount = a;
                invalidateSelf();
            }
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (amount > 0f) {
                paint.setAlpha(Math.round(255f * amount));
                canvas.drawBitmap(fill, null, b, paint);
            }
            base.setBounds(b);
            base.draw(canvas);
            if (amount > 0f) {
                // خطوط التفاصيل داخل المنطقة المملوءة تُرسم داكنة فوق الأيقونة
                canvas.drawBitmap(detail, null, b, paint);
            }
        }

        @Override protected void onBoundsChange(Rect b) { base.setBounds(b); }
        @Override public int getIntrinsicWidth() { return base.getIntrinsicWidth(); }
        @Override public int getIntrinsicHeight() { return base.getIntrinsicHeight(); }
        @Override public void setAlpha(int a) { base.setAlpha(a); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter f) { base.setColorFilter(f); invalidateSelf(); }
        @Override public void setTintList(android.content.res.ColorStateList t) { base.setTintList(t); invalidateSelf(); }
        @Override public void setTintMode(android.graphics.PorterDuff.Mode m) { base.setTintMode(m); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    public static final class Visual {
        private final ImageView[] icons;
        private final TextView[] labels;
        private final int skipAlphaIndex;

        public Visual(ImageView[] icons, TextView[] labels, int skipAlphaIndex) {
            this.icons = icons;
            this.labels = labels;
            this.skipAlphaIndex = skipAlphaIndex;
        }

        // v10.83: الحساب / البحث / القنوات = Material Symbols: outlined when closed, filled when the page is open
        private static final int[] OUTLINE = {R.drawable.nav_account, R.drawable.nav_search, R.drawable.nav_channels};
        private static final int[] FILLED = {R.drawable.nav_account_fill, R.drawable.nav_search_fill, R.drawable.nav_channels_fill};
        /** True when tab {@code i} is drawn with its symbol pair (not the user's photo). */
        private boolean symbol(ImageView icon, int i, float sel) {
            if (i < 0 || i >= OUTLINE.length) return false;
            if (CigramAvatarFrame.isPhoto(icon)) {   // the round user photo stays, never tinted
                if (icon.getTag(R.id.cg_nav_symbol) != null) {
                    icon.clearColorFilter();
                    icon.setTag(R.id.cg_nav_symbol, null);
                }
                return false;
            }
            try {
                Drawable cur = icon.getDrawable();
                // the account tab showing the user's photo (a picture, not one of our symbols): leave it alone
                if (i == 0 && cur instanceof android.graphics.drawable.BitmapDrawable) {
                    icon.clearColorFilter();              // a photo is never tinted
                    icon.setTag(R.id.cg_nav_symbol, null);
                    return true;
                }
                int want = sel >= 0.5f ? FILLED[i] : OUTLINE[i];
                Object had = icon.getTag(R.id.cg_nav_symbol);
                if (!(had instanceof Integer) || ((Integer) had).intValue() != want) {
                    icon.setImageResource(want);
                    icon.setTag(R.id.cg_nav_symbol, Integer.valueOf(want));
                }
                boolean light = CigramTheme.isLight(icon.getContext());
                icon.setColorFilter(light ? 0xFF111418 : 0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        /** يملأ فراغ الأيقونة بدرجة التحديد sel (0 = غير محددة، 1 = محددة). */
        private void fillInside(ImageView icon, float sel) {
            try {
                if (CigramAvatarFrame.isPhoto(icon)) return;   // v10.67: the round user photo is never filled / scanned
                Drawable cur = icon.getDrawable();
                boolean light = CigramTheme.isLight(icon.getContext());
                FillIcon fi;
                if (cur instanceof FillIcon && ((FillIcon) cur).light == light) {
                    fi = (FillIcon) cur;
                } else {
                    Drawable base = cur instanceof FillIcon ? ((FillIcon) cur).base() : cur;
                    fi = FillIcon.wrap(base, light);
                    if (fi == null) {
                        // not a fillable picture: in Light mode at least make it black
                        if (light) icon.setColorFilter(0xFF111418, android.graphics.PorterDuff.Mode.SRC_IN);
                        return;
                    }
                    icon.setImageDrawable(fi);
                }
                fi.setAmount(sel);
            } catch (Throwable ignored) { }
        }

        /** t من 0 إلى 1: ينتقل التحديد من التبويب from إلى التبويب to. */
        public void apply(int from, int to, float t) {
            float e = t < 0f ? 0f : (t > 1f ? 1f : t);
            for (int i = 0; i < icons.length; i++) {
                float sel;
                if (from == to) {
                    sel = (i == to) ? 1f : 0f;
                } else if (i == to) {
                    sel = e;
                } else if (i == from) {
                    sel = 1f - e;
                } else {
                    sel = 0f;
                }
                float s = 1f;   // Meta's bars do not scale or pop the icons: the open one is only filled and brighter
                ImageView icon = icons[i];
                if (icon != null) {
                    icon.setScaleX(s);
                    icon.setScaleY(s);
                    if (i != skipAlphaIndex) icon.setAlpha(0.65f + 0.35f * sel);
                    else if (!CigramAvatarFrame.isPhoto(icon)) icon.setAlpha(0.65f + 0.35f * sel);   // the account symbol (no photo)
                    if (!symbol(icon, i, sel)) fillInside(icon, sel);
                }
                TextView label = labels[i];
                if (label != null) label.setTextColor(mix(LABEL_OFF, LABEL_ON, sel));
            }
        }
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // ------------------------------------------------------------------
    // حركة المؤشر
    // ------------------------------------------------------------------

    private static float easeOutCubic(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static float easeOutBack(float t) {
        final float c1 = 1.25f;
        final float c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    public static ValueAnimator slide(final View capsule, final Glass glass, final Visual visual,
                                      final int from, final int to,
                                      final float startX, final float targetX,
                                      final float cell, long duration) {
        final float dist = Math.abs(targetX - startX);
        final float stretch = 0.10f + 0.05f * Math.min(2f, dist / Math.max(1f, cell));
        final boolean[] cancelled = {false};
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(duration);
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator va) {
                float t = va.getAnimatedFraction();
                // v5.29: no overshoot and no sideways stretch: the capsule used to swing past the
                // last tab and poke out of the bar's frame. It now stays between the two tabs.
                float e = easeOutCubic(t);
                float x = startX + (targetX - startX) * e;
                capsule.setTranslationX(x);
                capsule.invalidate(); // its glass shows the page under the capsule: redraw as it moves
                capsule.setScaleX(1f);
                capsule.setScaleY(1f);
                if (glass != null) glass.setGlintX(x + capsule.getWidth() * 0.5f);
                if (visual != null) visual.apply(from, to, t);
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationCancel(Animator animation) {
                cancelled[0] = true;
            }
            @Override public void onAnimationEnd(Animator animation) {
                if (cancelled[0]) return;
                capsule.setTranslationX(targetX);
                capsule.setScaleX(1f);
                capsule.setScaleY(1f);
                if (glass != null) glass.setGlintX(targetX + capsule.getWidth() * 0.5f);
                if (visual != null) visual.apply(to, to, 1f);
            }
        });
        a.start();
        return a;
    }

    // ------------------------------------------------------------------
    // لمس / اهتزاز / ظهور المحتوى / العودة للأعلى
    // ------------------------------------------------------------------

    public static void attachPress(View tab) {
        if (tab == null) return;
        tab.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                // v5.29: a tab never changes size on (long) press, only a light dim
                if (a == MotionEvent.ACTION_DOWN) {
                    v.setAlpha(0.8f);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    v.setAlpha(1f);
                }
                return false;
            }
        });
    }

    public static void tick(View v) {
        if (v == null) return;
        try {
            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        } catch (Throwable ignored) { }
    }

    /** ظهور ناعم للصفحة الجديدة (الشريط نفسه لا يتحرك). */
    public static void reveal(final View content) {
        if (content == null) return;
        try {
            float dy = 10f * content.getResources().getDisplayMetrics().density;
            content.setAlpha(0f);
            content.setTranslationY(dy);
            content.animate().alpha(1f).translationY(0f).setDuration(230L)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            // شبكة أمان: لا تبقى الصفحة شفافة مهما حدث
            content.postDelayed(new Runnable() {
                @Override public void run() {
                    content.setAlpha(1f);
                    content.setTranslationY(0f);
                }
            }, 900L);
        } catch (Throwable ignored) {
            content.setAlpha(1f);
            content.setTranslationY(0f);
        }
    }

    // ------------------------------------------------------------------
    // تأثير الزجاج المتحرك أثناء تمرير الصفحة (للأعلى / لليسار)
    // ------------------------------------------------------------------

    private static final java.util.HashMap<Class<?>, java.lang.reflect.Method[]> OFFSET_METHODS =
            new java.util.HashMap<Class<?>, java.lang.reflect.Method[]>();

    /**
     * Makes the glass of the bar and of the selected-tab capsule shimmer while the page behind
     * them scrolls. Works for ScrollView, HorizontalScrollView, ListView and RecyclerView and
     * needs no change in the page itself.
     */
    public static void bindScrollFx(final View shell, final View content,
                                    final Drawable barGlass, final Drawable capsuleGlass) {
        // v10.42: the bar is a solid pill now, so the live "page behind the glass" snapshot (the whole
        // page drawn into a bitmap and blurred on every scroll frame) was pure cost. Off.
        if (!GLASS_FX) return;
        final Glass bar = barGlass instanceof Glass ? (Glass) barGlass : null;
        final Glass cap = capsuleGlass instanceof Glass ? (Glass) capsuleGlass : null;
        if (shell == null || content == null || (bar == null && cap == null)) return;

        // live backdrop: snapshot of the page under the bar, refreshed while it changes
        Drawable.Callback barCallback = barGlass == null ? null : barGlass.getCallback();
        final Backdrop backdrop = barCallback instanceof View ? new Backdrop(content, (View) barCallback) : null;
        if (backdrop != null) {
            if (bar != null) bar.setBackdrop(backdrop, false);
            if (cap != null) cap.setBackdrop(backdrop, true);
        }
        final Handler handler = new Handler(Looper.getMainLooper());
        final long[] lastCapture = {0L};
        final Runnable capture = new Runnable() {
            @Override public void run() {
                if (backdrop == null || !shell.isAttachedToWindow()) return;
                lastCapture[0] = SystemClock.uptimeMillis();
                backdrop.capture();
                if (bar != null) bar.invalidateSelf();
                if (cap != null) cap.invalidateSelf();
            }
        };
        // v4.4: every capture draws the whole page into a software bitmap and blurs it. It used to
        // run on EVERY scroll frame (the "throttled" comment below was never implemented) and
        // every 400 ms while idle. Now: at most one capture per CAPTURE_GAP_MS while scrolling,
        // plus one trailing capture so the glass still settles on the final position, and an
        // idle refresh once per second that is skipped when a capture just happened.
        final long[] trailingPending = {0L};
        final Runnable trailingCapture = new Runnable() {
            @Override public void run() {
                trailingPending[0] = 0L;
                capture.run();
            }
        };
        final Runnable throttledCapture = new Runnable() {
            @Override public void run() {
                long since = SystemClock.uptimeMillis() - lastCapture[0];
                if (since >= CAPTURE_GAP_MS) {
                    capture.run();
                } else if (trailingPending[0] == 0L) {
                    trailingPending[0] = 1L;
                    handler.postDelayed(trailingCapture, CAPTURE_GAP_MS - since);
                }
            }
        };
        // v5.1: the glass used to show a snapshot that was 1-3 frames (or up to a second) old, so
        // while the page moved the bar showed content that no longer matched what was around it.
        // Now the snapshot is refreshed in onPreDraw, i.e. in the very frame it is shown in.
        final long[] activeUntil = {0L};
        final ViewTreeObserver.OnPreDrawListener frameSync = new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                try {
                    if (backdrop != null && !backdrop.isBroken()) {
                        long now = SystemClock.uptimeMillis();
                        boolean revealing = content.getAlpha() < 0.999f
                                || content.getTranslationY() != 0f || content.getTranslationX() != 0f;
                        if (!backdrop.ready() || revealing || now < activeUntil[0]) {
                            long since = now - lastCapture[0];
                            if (!backdrop.ready() || since >= SYNC_GAP_MS) {
                                if (backdrop.capture()) {
                                    lastCapture[0] = now;
                                    if (bar != null) bar.invalidateSelf();
                                    if (cap != null) cap.invalidateSelf();
                                }
                            } else if (trailingPending[0] == 0L) {
                                trailingPending[0] = 1L;
                                handler.postDelayed(trailingCapture, SYNC_GAP_MS - since + 20L);
                            }
                        }
                    }
                } catch (Throwable ignored) { }
                return true;
            }
        };
        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!shell.isAttachedToWindow()) return;
                // images / carousels change without scrolling
                if (SystemClock.uptimeMillis() - lastCapture[0] >= IDLE_CAPTURE_MS / 2) capture.run();
                handler.postDelayed(this, IDLE_CAPTURE_MS);
            }
        };

        final java.util.ArrayList<View> views = new java.util.ArrayList<View>();
        final java.util.WeakHashMap<View, int[]> last = new java.util.WeakHashMap<View, int[]>();
        final long[] collectedAt = {0L};
        final long bornAt = SystemClock.uptimeMillis();
        final float dens = shell.getResources().getDisplayMetrics().density;

        final ViewTreeObserver.OnScrollChangedListener listener = new ViewTreeObserver.OnScrollChangedListener() {
            @Override public void onScrollChanged() {
                try {
                    long now = SystemClock.uptimeMillis();
                    activeUntil[0] = now + 200L;   // keep the snapshot in step with every frame of the scroll
                    if (views.isEmpty() || now - collectedAt[0] > 800L) {
                        views.clear();
                        collectScrollables(content, views, 0);
                        collectedAt[0] = now;
                    }
                    float dx = 0f, dy = 0f;
                    for (int i = 0; i < views.size(); i++) {
                        View v = views.get(i);
                        if (!v.isShown()) continue;
                        int[] cur = scrollOffsets(v);
                        int[] prev = last.get(v);
                        last.put(v, cur);
                        if (prev == null) continue;
                        dx += cur[0] - prev[0];
                        dy += cur[1] - prev[1];
                    }
                    // the page is still being laid out right after creation: ignore that
                    if (now - bornAt < 700L) return;
                    float cap120 = 120f * dens;
                    dx = Math.max(-cap120, Math.min(cap120, dx));
                    dy = Math.max(-cap120, Math.min(cap120, dy));
                    if (dx == 0f && dy == 0f) return;
                    if (bar != null) bar.pushFlow(dx, dy);
                    if (cap != null) cap.pushFlow(dx, dy);
                } catch (Throwable ignored) { }
            }
        };

        final View.OnAttachStateChangeListener attach = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                try { v.getViewTreeObserver().addOnScrollChangedListener(listener); } catch (Throwable ignored) { }
                try { v.getViewTreeObserver().addOnPreDrawListener(frameSync); } catch (Throwable ignored) { }
                trailingPending[0] = 0L;      // a pending trailing capture was dropped on detach
                handler.removeCallbacks(tick);
                handler.postDelayed(tick, 120L);
            }
            @Override public void onViewDetachedFromWindow(View v) {
                handler.removeCallbacksAndMessages(null);
                try {
                    ViewTreeObserver o = v.getViewTreeObserver();
                    if (o.isAlive()) {
                        o.removeOnScrollChangedListener(listener);
                        o.removeOnPreDrawListener(frameSync);
                    }
                } catch (Throwable ignored) { }
            }
        };
        shell.addOnAttachStateChangeListener(attach);
        if (shell.isAttachedToWindow()) attach.onViewAttachedToWindow(shell);
    }

    /** Turns the live page-behind-the-glass effect on/off for the bar and its capsule. */
    public static void setBackdropEnabled(Drawable glass, boolean on) {
        if (glass instanceof Glass) ((Glass) glass).setBackdropEnabled(on);
    }

    private static void collectScrollables(View v, java.util.ArrayList<View> out, int depth) {
        if (v == null || out.size() >= 32 || depth > 14) return;
        String n = v.getClass().getName();
        if (v instanceof ScrollView || v instanceof android.widget.HorizontalScrollView
                || v instanceof AbsListView || n.contains("NestedScrollView") || n.contains("RecyclerView")) {
            out.add(v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collectScrollables(g.getChildAt(i), out, depth + 1);
        }
    }

    /** {x, y} where growing x = content moving left and growing y = content moving up. */
    private static int[] scrollOffsets(View v) {
        try {
            if (v instanceof android.widget.HorizontalScrollView) {
                int sign = v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1;
                return new int[]{v.getScrollX() * sign, 0};
            }
            if (v instanceof ScrollView || v.getClass().getName().contains("NestedScrollView")) {
                return new int[]{0, v.getScrollY()};
            }
            if (v instanceof AbsListView) {
                AbsListView list = (AbsListView) v;
                View first = list.getChildAt(0);
                if (first == null) return new int[]{0, 0};
                return new int[]{0, list.getFirstVisiblePosition() * Math.max(1, first.getHeight()) - first.getTop()};
            }
            java.lang.reflect.Method[] m = OFFSET_METHODS.get(v.getClass());
            if (m == null) {
                m = new java.lang.reflect.Method[2];
                try { m[0] = v.getClass().getMethod("computeHorizontalScrollOffset"); } catch (Throwable ignored) { }
                try { m[1] = v.getClass().getMethod("computeVerticalScrollOffset"); } catch (Throwable ignored) { }
                OFFSET_METHODS.put(v.getClass(), m);
            }
            int x = m[0] == null ? 0 : ((Integer) m[0].invoke(v)).intValue();
            int y = m[1] == null ? 0 : ((Integer) m[1].invoke(v)).intValue();
            return new int[]{x, y};
        } catch (Throwable ignored) {
            return new int[]{0, 0};
        }
    }

    /** النقر على التبويب الحالي: تمرير سلس لأعلى الصفحة. */
    public static boolean scrollToTop(View root, View hapticView) {
        tick(hapticView);
        View target = findScrollable(root);
        if (target == null) return false;
        try {
            if (target instanceof ScrollView) {
                if (target.getScrollY() <= 0) return false;
                ((ScrollView) target).smoothScrollTo(0, 0);
                return true;
            }
            if (target instanceof AbsListView) {
                ((AbsListView) target).smoothScrollToPosition(0);
                return true;
            }
            if (!target.canScrollVertically(-1)) return false;
            try {
                target.getClass().getMethod("smoothScrollToPosition", int.class).invoke(target, 0);
            } catch (Throwable first) {
                target.getClass().getMethod("smoothScrollTo", int.class, int.class).invoke(target, 0, 0);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static View findScrollable(View v) {
        if (v == null) return null;
        if (v instanceof ScrollView || v instanceof AbsListView) return v;
        String n = v.getClass().getName();
        if (n.contains("NestedScrollView") || n.contains("RecyclerView")) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findScrollable(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
