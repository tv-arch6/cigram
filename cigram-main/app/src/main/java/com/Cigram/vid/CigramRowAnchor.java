package com.Cigram.vid;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.HorizontalScrollView;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * v10.80: keeps a horizontal card row where the user expects it while its cards are still being added.
 *
 * Home's rows put the newest card at the far RIGHT and add older cards on the LEFT in small batches. The old
 * code compensated every batch with an estimated width, so the row drifted (the video showed rows ending
 * up on older titles after loading). Here the compensation is measured from the real laid-out width:
 *
 *  - until the user touches the row it stays pinned to the right end (the newest card is always visible);
 *  - after the user has scrolled it, what they look at stays exactly where it is.
 */
final class CigramRowAnchor {

    static final class Mark {
        final int width;
        Mark(int width) { this.width = width; }
    }

    private static final Set<View> TOUCHED = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static final Set<View> WATCHED = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private CigramRowAnchor() { }

    /** A new set of cards replaces the old ones: the row is pinned to the right again. */
    static void reset(HorizontalScrollView s) {
        if (s == null) return;
        TOUCHED.remove(s);
        watch(s);
    }

    private static void watch(final HorizontalScrollView s) {
        if (!WATCHED.add(s)) return;
        s.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) TOUCHED.add(v);
                return false;
            }
        });
    }

    /** 19.1: true once the user has put a finger on this row (it is then completed quickly instead of lazily). */
    static boolean touched(HorizontalScrollView s) {
        return s != null && TOUCHED.contains(s);
    }

    /** Call BEFORE cards are added. */
    static Mark mark(HorizontalScrollView s) {
        if (s == null) return null;
        watch(s);
        View child = s.getChildAt(0);
        return new Mark(child == null ? 0 : child.getWidth());
    }

    /** Call AFTER cards were added (or with a null mark for the first build): fixes the position once laid out. */
    static void restore(final HorizontalScrollView s, final Mark mark) {
        if (s == null) return;
        watch(s);
        s.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                ViewTreeObserver o = s.getViewTreeObserver();
                if (o.isAlive()) o.removeOnPreDrawListener(this);
                try {
                    View child = s.getChildAt(0);
                    if (child == null) return true;
                    int now = child.getWidth();
                    if (!TOUCHED.contains(s)) {
                        s.scrollTo(Math.max(0, now - s.getWidth() + s.getPaddingLeft() + s.getPaddingRight()), 0);
                    } else if (mark != null && now > mark.width) {
                        s.scrollBy(now - mark.width, 0);
                    }
                } catch (Throwable ignored) { }
                return true;
            }
        });
        s.requestLayout();
    }
}
