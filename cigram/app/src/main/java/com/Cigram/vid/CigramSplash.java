package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Cold-start hand-off (2026 rewrite of the launch screen).
 *
 * <h3>What was wrong (Problem No. 1: "black / almost empty screen for ~2 s")</h3>
 * <ol>
 *   <li>Android 12+: {@code CigramLaunchTheme} set {@code windowSplashScreenAnimatedIcon} to
 *       {@code @android:color/transparent}. The system splash was therefore an EMPTY #0A0608 screen from the tap until
 *       MainActivity drew its first frame.</li>
 *   <li>Android 7-11: the launch window background ({@code cigram_launch_bg}) was a maroon glow with NO logo, so the
 *       same empty dark page showed while the process started and MainActivity.onCreate built Home on the main
 *       thread.</li>
 *   <li>The branded overlay with the logo was only added in {@code onActivityStarted}, i.e. after the whole of
 *       MainActivity.onCreate, and then held for a FIXED {@code HOLD_MS = 2000} counted from onCreate, whether Home was
 *       ready or not. The user saw: empty dark screen → logo (rest of the 2 s) → Home.</li>
 *   <li>The page colours did not follow the theme: the launch screen was always near-black, then Home switched to
 *       its own colour (#00121A hard-coded in MainActivity, or the light page in Light mode): a visible flash.</li>
 * </ol>
 *
 * <h3>Now</h3>
 * <ul>
 *   <li>The logo is in the window background itself and in the Android 12+ splash icon (same 112 dp rounded logo, same
 *       page colour from {@code @color/cg_launch_bg}, which follows Light / Dark), so it is on screen from the very
 *       first frame the system draws.</li>
 *   <li>This overlay is the same picture, drawn on MainActivity's first frame, and only bridges to Home: it leaves the
 *       moment Home reports it has rendered ({@link #markHomeReady}) and at the latest {@link #MAX_HOLD_MS} after its
 *       first frame. No fixed waiting, no loading animation once Home is there.</li>
 *   <li>Android 13+: the system splash theme is kept in step with the user's in-app Light / Dark choice
 *       ({@link #syncSystemSplashTheme}), so a forced mode never flashes the opposite colour on the next cold start.</li>
 * </ul>
 * Only the first Home of the process gets the overlay; coming back from the background never waits.
 */
public final class CigramSplash {

    private static final String TAG = "CigramSplash";

    /** Upper bound of the hand-off after the overlay's first frame (Home normally reports ready well before). */
    static final long MAX_HOLD_MS = 650L;
    /** Kept for callers that still read them: there is no fixed hold any more. */
    static final long HOLD_MS = 0L;
    static final long MIN_MS = 0L;
    static final long MAX_MS = MAX_HOLD_MS;

    /** Logo size: identical to the window background (drawable/cigram_launch_bg) and the Android 12+ splash icon. */
    private static final int ICON_DP = 112;
    private static final long EXIT_MS = 220L;
    private static final long ENTRANCE_STEP_MS = 30L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean used;
    private static volatile long createdAt;
    private static volatile boolean homeReady;
    /** The pending exit of the overlay (set while it is on screen), run early by {@link #markHomeReady}. */
    private static Runnable pendingExit;

    private CigramSplash() { }

    /** Called by the Home logic (through CigramAnalytics) when Home has rendered its content. */
    static void markHomeReady() {
        homeReady = true;
        MAIN.post(new Runnable() {
            @Override public void run() {
                Runnable exit = pendingExit;
                if (exit != null) {
                    MAIN.removeCallbacks(exit);
                    exit.run();
                }
            }
        });
    }

    /** onActivityPreCreated / onActivityCreated: remembers when the first Home started (start-up metric only). */
    public static void mark(Activity activity) {
        if (used || createdAt != 0L || activity == null) return;
        if (!"MainActivity".equals(activity.getClass().getSimpleName())) return;
        createdAt = SystemClock.uptimeMillis();
    }

    /** onActivityStarted: puts the hand-off overlay on the first Home of the process. */
    public static void begin(final Activity activity) {
        if (used || activity == null || activity.isFinishing()) return;
        if (!"MainActivity".equals(activity.getClass().getSimpleName())) return;
        used = true;
        try {
            syncSystemSplashTheme(activity);
        } catch (Throwable error) {
            Log.w(TAG, "splash theme sync failed", error);
        }
        if (homeReady) return;   // Home rendered during onCreate already: nothing to bridge
        try {
            showOverlay(activity);
        } catch (Throwable error) {
            Log.w(TAG, "splash failed", error);
        }
    }

    /** Page colour of the launch screen for this activity (follows the effective Light / Dark mode). */
    static int pageColor(Context context) {
        return CigramTheme.isLight(context) ? 0xFFF4F4F6 : 0xFF0E0E10;
    }

    private static void showOverlay(final Activity activity) {
        View decorView = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (!(decorView instanceof ViewGroup)) return;
        final ViewGroup decor = (ViewGroup) decorView;

        final FrameLayout overlay = new FrameLayout(activity);
        overlay.setTag(CigramTheme.SKIP_TAG);
        overlay.setBackgroundColor(pageColor(activity));
        overlay.setClickable(true);   // a tap during the hand-off never reaches a half-built Home
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        try { overlay.setElevation(CigramUI.dp(activity, 32)); } catch (Throwable ignored) { }

        ImageView logo = new ImageView(activity);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setImageDrawable(logoDrawable(activity));
        logo.setContentDescription("Cigram");
        int size = CigramUI.dp(activity, ICON_DP);
        overlay.addView(logo, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        decor.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        try { overlay.setLayerType(View.LAYER_TYPE_HARDWARE, null); } catch (Throwable ignored) { }

        final Runnable exit = new Runnable() {
            private boolean done;
            @Override public void run() {
                if (done) return;
                done = true;
                pendingExit = null;
                if (activity.isFinishing() || activity.isDestroyed()) {
                    if (overlay.getParent() == decor) decor.removeView(overlay);
                    return;
                }
                try { playHomeEntrance(activity); } catch (Throwable ignored) { }
                overlay.animate().alpha(0f).setDuration(EXIT_MS)
                        .setInterpolator(new DecelerateInterpolator(1.4f))
                        .withEndAction(new Runnable() {
                            @Override public void run() {
                                if (overlay.getParent() == decor) decor.removeView(overlay);
                            }
                        }).start();
            }
        };
        pendingExit = exit;
        // Start the cap only once the overlay has really been drawn (the hand-off from the system window is then
        // seamless); if Home already reported ready, leave right after that first frame.
        overlay.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                ViewTreeObserver o = overlay.getViewTreeObserver();
                if (o.isAlive()) o.removeOnPreDrawListener(this);
                MAIN.postDelayed(exit, homeReady ? 16L : MAX_HOLD_MS);
                return true;
            }
        });
        // Safety: if no frame is ever drawn (activity stopped before drawing), never leave the overlay behind.
        MAIN.postDelayed(exit, 4000L);
    }

    private static Drawable logoDrawable(Activity activity) {
        try {
            int id = activity.getResources().getIdentifier("cigram_logo", "drawable", activity.getPackageName());
            if (id != 0) return activity.getResources().getDrawable(id, activity.getTheme());
        } catch (Throwable error) {
            Log.w(TAG, "logo resource", error);
        }
        try {
            return activity.getPackageManager().getApplicationIcon(activity.getApplicationInfo());
        } catch (Throwable error) {
            return null;
        }
    }

    /**
     * Android 13+: the system splash of the NEXT cold start follows the in-app mode. With "follow the device" the
     * default theme (values / values-night) already matches, so the explicit variants are only used for a forced mode.
     */
    static void syncSystemSplashTheme(Activity activity) {
        if (Build.VERSION.SDK_INT < 33 || activity == null) return;
        String mode = CigramTheme.effectiveMode(activity);
        int theme = CigramTheme.MODE_LIGHT.equals(mode) ? R.style.CigramLaunchTheme_Light
                : CigramTheme.MODE_DARK.equals(mode) ? R.style.CigramLaunchTheme_Dark : R.style.CigramLaunchTheme;
        try {
            Object splash = Activity.class.getMethod("getSplashScreen").invoke(activity);
            Class<?> splashClass = Class.forName("android.window.SplashScreen");
            splashClass.getMethod("setSplashScreenTheme", int.class).invoke(splash, theme);
        } catch (Throwable error) {
            Log.w(TAG, "setSplashScreenTheme", error);
        }
    }

    /** Home's first screenful rises in quickly as the overlay fades (no delay before content is visible). */
    private static void playHomeEntrance(Activity activity) {
        View root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        int id = activity.getResources().getIdentifier("linear1", "id", activity.getPackageName());
        View home = id == 0 ? null : root.findViewById(id);
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        float rise = CigramUI.dp(activity, 14);
        if (!(home instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) home;
        int shown = 0;
        int[] at = new int[2];
        for (int i = 0; i < group.getChildCount() && shown < 6; i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getHeight() <= 0) continue;
            child.getLocationOnScreen(at);
            if (at[1] > screenH) break;
            child.setTranslationY(rise);
            child.animate().translationY(0f).setStartDelay(shown * ENTRANCE_STEP_MS).setDuration(260L)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
            shown++;
        }
    }
}
