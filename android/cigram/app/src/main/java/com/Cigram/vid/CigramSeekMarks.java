package com.Cigram.vid;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.SeekBar;

import java.util.ArrayList;

/**
 * v6.2: the ad breaks of the title (CigramAdBreaks) are short YELLOW marks on the time bar, so
 * the viewer sees where an ad will come. The scenes marked for skipping are now light white bands.
 *
 * v6.0 (design 5): the scenes the admin marked for skipping are drawn on the time bar,
 * so the viewer sees where they are before «تخطي المشاهد المحددة» jumps over them.
 * Drawn in the SeekBar's overlay: the bar, its listeners and its thumb are not touched.
 */
public final class CigramSeekMarks {

    private static final int YELLOW = 0xFFFFD60A;
    private static final int SKIP = 0x8CFFFFFF;
    private static final int TAG_KEY = 0x7F0C0606;

    private CigramSeekMarks() {}

    /** Call from the progress loop; cheap when nothing changed. */
    public static void update(SeekBar bar, ArrayList<long[]> segments, long durationMs) {
        if (bar == null || android.os.Build.VERSION.SDK_INT < 18) return;
        try {
            Marks marks = null;
            Object tag = bar.getTag(TAG_KEY);
            if (tag instanceof Marks) marks = (Marks) tag;
            if (marks == null) {
                marks = new Marks(bar);
                bar.setTag(TAG_KEY, marks);
                bar.getOverlay().add(marks);
            }
            marks.set(segments, durationMs);
        } catch (Throwable error) {
            android.util.Log.w("CigramPlayer", "seek marks failed", error);
        }
    }

    private static final class Marks extends Drawable {
        private final SeekBar bar;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint adPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private long[] flat = new long[0];
        private long[] ads = new long[0];
        private long duration;
        private int lastW = -1;
        private int lastH = -1;

        Marks(SeekBar bar) {
            this.bar = bar;
            paint.setColor(SKIP);
            adPaint.setColor(YELLOW);
        }

        void set(ArrayList<long[]> segments, long durationMs) {
            int n = segments == null ? 0 : segments.size();
            long[] next = new long[n * 2];
            for (int i = 0; i < n; i++) {
                long[] s = segments.get(i);
                if (s == null || s.length < 2) continue;
                next[i * 2] = s[0];
                next[i * 2 + 1] = s[1];
            }
            long[] nextAds = CigramAdBreaks.current(bar.getContext());
            boolean sizeChanged = bar.getWidth() != lastW || bar.getHeight() != lastH;
            if (!sizeChanged && durationMs == duration && java.util.Arrays.equals(next, flat)
                    && java.util.Arrays.equals(nextAds, ads)) return;
            flat = next;
            ads = nextAds;
            duration = durationMs;
            lastW = bar.getWidth();
            lastH = bar.getHeight();
            setBounds(0, 0, lastW, lastH);
            invalidateSelf();
            bar.invalidate();
        }

        @Override public void draw(Canvas c) {
            if (duration <= 0 || (flat.length == 0 && ads.length == 0) || bar.getVisibility() != View.VISIBLE) return;
            float left = bar.getPaddingLeft();
            float right = bar.getWidth() - bar.getPaddingRight();
            float track = right - left;
            if (track <= 0) return;
            float density = bar.getResources().getDisplayMetrics().density;
            float h = Math.max(3f, 3.5f * density);
            float cy = bar.getHeight() / 2f;
            boolean rtl = bar.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            for (int i = 0; i + 1 < flat.length; i += 2) {
                long a = Math.max(0L, Math.min(duration, flat[i]));
                long b = Math.max(0L, Math.min(duration, flat[i + 1]));
                if (b <= a) continue;
                float x1 = left + track * a / (float) duration;
                float x2 = left + track * b / (float) duration;
                if (x2 - x1 < 2f * density) x2 = x1 + 2f * density; // a very short scene stays visible
                if (rtl) {
                    float m1 = left + right - x2;
                    float m2 = left + right - x1;
                    x1 = m1;
                    x2 = m2;
                }
                rect.set(x1, cy - h / 2f, x2, cy + h / 2f);
                c.drawRoundRect(rect, h / 2f, h / 2f, paint);
            }
            // ad breaks: a short yellow mark, a little taller than the track
            float w = 3.5f * density;
            float ah = Math.max(6f, 7f * density);
            for (long at : ads) {
                if (at <= 0 || at >= duration) continue;
                float x = left + track * at / (float) duration;
                if (rtl) x = left + right - x;
                rect.set(x - w / 2f, cy - ah / 2f, x + w / 2f, cy + ah / 2f);
                c.drawRoundRect(rect, w / 2f, w / 2f, adPaint);
            }
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); adPaint.setAlpha(alpha); }

        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); adPaint.setColorFilter(cf); }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
