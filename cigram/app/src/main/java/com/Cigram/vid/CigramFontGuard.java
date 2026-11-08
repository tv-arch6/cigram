package com.Cigram.vid;

import android.app.Activity;
import android.app.Application;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

/**
 * Makes sure every piece of text in every screen is drawn in the app font (Beiruti), including the
 * labels that come straight from XML or from library widgets (player controls, dialogs' inner views, ...)
 * and never had a typeface assigned.
 *
 * Cost control (scrolling must stay untouched):
 *  - nothing runs while the user scrolls: the guard looks at a screen only when it is resumed and during the
 *    first few layout passes that follow (the screen is still building itself then), at most
 *    {@link #MAX_PASSES} passes within {@link #WINDOW_MS};
 *  - a pass only touches a TextView whose typeface is not already the app font, and a view that was
 *    marked with {@link R.id#cg_keep_font} (monospace readouts) is never touched;
 *  - views created later by the screens' own code already ask {@link CigramTypefaces} for the font.
 */
final class CigramFontGuard {

    private static final int MAX_PASSES = 6;
    private static final long WINDOW_MS = 4000L;
    private static boolean installed;

    private CigramFontGuard() { }

    static synchronized void install(Application application) {
        if (application == null || installed) return;
        installed = true;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
            @Override public void onActivityDestroyed(Activity activity) { }

            @Override public void onActivityResumed(Activity activity) {
                watch(activity);
            }
        });
    }

    /** Marks a TextView that must keep its own typeface (for example a monospace readout). */
    static void keepFont(View view) {
        if (view != null) view.setTag(R.id.cg_keep_font, Boolean.TRUE);
    }

    private static void watch(final Activity activity) {
        if (activity == null || activity.isFinishing() || activity.getWindow() == null) return;
        final View decor = activity.getWindow().getDecorView();
        if (decor == null || CigramTypefaces.app() == Typeface.DEFAULT) {
            // The font is still loading on its background thread: try again shortly instead of skipping the screen.
            if (decor != null) decor.postDelayed(new Runnable() {
                @Override public void run() { if (!activity.isFinishing() && CigramTypefaces.app() != Typeface.DEFAULT) watch(activity); }
            }, 250L);
            return;
        }
        decor.post(new Runnable() {
            @Override public void run() { if (!activity.isFinishing()) apply(decor); }
        });
        final ViewTreeObserver observer = decor.getViewTreeObserver();
        if (!observer.isAlive()) return;
        final long started = SystemClock.uptimeMillis();
        observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            private int passes;

            @Override public void onGlobalLayout() {
                boolean done = ++passes > MAX_PASSES || SystemClock.uptimeMillis() - started > WINDOW_MS
                        || activity.isFinishing();
                if (!done) apply(decor);
                if (done) {
                    ViewTreeObserver vto = decor.getViewTreeObserver();
                    if (vto.isAlive()) vto.removeOnGlobalLayoutListener(this);
                }
            }
        });
    }

    /** Walks the tree once and puts the app font on every text view that still has a system typeface. */
    static void apply(View root) {
        if (root == null) return;
        if (root instanceof TextView) fix((TextView) root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0, n = group.getChildCount(); i < n; i++) apply(group.getChildAt(i));
        }
    }

    private static void fix(TextView view) {
        if (view.getTag(R.id.cg_keep_font) != null) return;
        Typeface current = view.getTypeface();
        if (CigramTypefaces.isAppFace(current)) return;
        boolean bold = view.getPaint().isFakeBoldText() || (current != null && (current.getStyle() & Typeface.BOLD) != 0);
        boolean italic = current != null && (current.getStyle() & Typeface.ITALIC) != 0;
        Typeface face = bold ? CigramTypefaces.bold() : CigramTypefaces.app();
        if (face == current) return;
        view.setTypeface(face, italic ? Typeface.ITALIC : Typeface.NORMAL);
    }
}
