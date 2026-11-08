package com.Cigram.vid;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Launch screen (v10.36): OTTO-style branded intro shown on a cold start while Home loads its
 * catalogue and first posters in the background.
 *
 *   - page: near-black #0A0608 with a vertical maroon radial glow in the centre;
 *   - the app logo (launcher icon) centred;
 *   - the line «From by CIGRAM» near the bottom (CIGRAM in the brand red).
 *
 * The same custom overlay is drawn on every Android version (7+), so the look is identical
 * everywhere. It is shown for {@link #HOLD_MS} (1 s, owner's request) and then fades out; it never
 * waits longer than that, so a slow or missing network cannot keep the user waiting.
 *
 * Only the first Home of the process gets it; coming back from the background never waits.
 */
public final class CigramSplash {

    /** Fixed 2-second branded intro on a cold start, counted from the moment MainActivity starts creating. */
    static final long HOLD_MS = 2000L;
    /** Kept for the Home Logic that still reports readiness; the hold is time-based now. */
    static final long MIN_MS = HOLD_MS;
    static final long MAX_MS = HOLD_MS;

    private static final int PAGE = 0xFF0A0608;        // near-black, warm tint
    private static final int GLOW_CENTER = 0xFF7E1733;  // maroon core of the glow
    private static final int GLOW_MID = 0xFF3A0C1B;     // maroon fading to the page
    private static final int LABEL_DIM = 0xFFBDA6AC;    // «From by»
    private static final int LABEL_HOT = 0xFFE21B14;    // «CIGRAM» (brand red)
    /** v10.37: smaller logo so the icon is less dominant at the start (was 120). */
    private static final int ICON_DP = 120;
    // v10.40: the startup work now runs on a background thread (CigramStage1InitProvider), so the
    // main thread is free during the splash and these animations play smoothly (they could not
    // before, which is why the fade-in was dropped in v10.38).
    /** Exit (OTTO-style): the logo lifts a little as the whole screen dissolves into Home. */
    private static final long EXIT_MS = 200L;
    // v10.52 «حروف تتضح»: the name CIGRAM forms letter by letter under the logo, the splash fades
    // out, then Home's sections rise in one after another and the bottom bar bounces up.
    private static final String WORD = "CIGRAM";
    private static final long LETTER_STEP_MS = 110L;
    private static final long LETTER_MS = 520L;
    private static final long ENTRANCE_DELAY_MS = 120L;
    private static final long ENTRANCE_STEP_MS = 55L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean used;
    private static long startedAt;
    /** Uptime at MainActivity.onCreate start; the 2 s are counted from here, not from the first overlay frame. */
    private static volatile long createdAt;
    private static volatile boolean homeReady;

    private CigramSplash() { }

    /** Called by the Home Logic through CigramAnalytics when Home finished rendering. */
    static void markHomeReady() {
        homeReady = true;
    }

    /**
     * Call from onActivityPreCreated (before the heavy onCreate). The 2 s are measured from here, so a
     * slow inflate cannot stretch the intro: if onCreate already took 2 s the splash leaves at once.
     */
    public static void mark(Activity activity) {
        if (used || createdAt != 0L || activity == null) return;
        if (!"MainActivity".equals(activity.getClass().getSimpleName())) return;
        createdAt = SystemClock.uptimeMillis();
    }

    private static long remainingMs() {
        long from = createdAt != 0L ? createdAt : startedAt;
        return HOLD_MS - (SystemClock.uptimeMillis() - from);
    }

    /** Call from onActivityStarted (before the first frame). Does nothing after the first Home. */
    public static void begin(final Activity activity) {
        if (used || activity == null || activity.isFinishing()) return;
        if (!"MainActivity".equals(activity.getClass().getSimpleName())) return;
        used = true;
        startedAt = SystemClock.uptimeMillis();
        if (createdAt == 0L) createdAt = startedAt;
        try {
            showOverlay(activity);
        } catch (Throwable error) {
            android.util.Log.w("CigramSplash", "splash failed", error);
        }
    }

    /** Draws the branded launch screen above Home and fades it out after HOLD_MS. */
    private static void showOverlay(final Activity activity) {
        View decorView = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (!(decorView instanceof ViewGroup)) return;
        final ViewGroup decor = (ViewGroup) decorView;

        final FrameLayout overlay = new FrameLayout(activity);
        overlay.setBackground(glow(activity));
        overlay.setClickable(true);   // nothing under it can be tapped while it is up
        overlay.setFocusable(true);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        try { overlay.setElevation(CigramUI.dp(activity, 32)); } catch (Throwable ignored) { }

        // --- logo (app launcher icon), centred ---
        ImageView icon = new ImageView(activity);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Drawable appIcon = null;
        try {
            int logoRes = activity.getResources().getIdentifier("cigram_logo", "drawable", activity.getPackageName());
            if (logoRes != 0) appIcon = activity.getResources().getDrawable(logoRes);
        } catch (Throwable ignored) { }
        if (appIcon == null) {
            try {
                appIcon = activity.getPackageManager().getApplicationIcon(activity.getApplicationInfo());
            } catch (Throwable ignored) { }
        }
        if (appIcon != null) icon.setImageDrawable(appIcon);
        // rounded app-icon shape (same look as the launcher icon)
        final int radius = CigramUI.dp(activity, ICON_DP) / 5;
        try {
            icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            icon.setClipToOutline(true);
        } catch (Throwable ignored) { }
        int size = CigramUI.dp(activity, ICON_DP);
        overlay.addView(icon, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));

        // The logo and «Welcome to CIGRAM» are drawn at full opacity from the first frame, so they
        // appear TOGETHER and are always visible. A fade/scale entrance cannot play here: Home is
        // built on the main thread while the splash is up, so the thread is busy and an entrance
        // animation would not run until the very end (it left the screen empty for seconds). Only
        // the EXIT animation below runs, because by then the thread is free.
        decor.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // v10.66 (TV only): the remote stays on the launch screen until it is gone
        try { if (CigramTvMode.isTv(activity)) overlay.requestFocus(); } catch (Throwable ignored) { }

        final ImageView logo = icon;
        logo.setScaleX(0.9f);
        logo.setScaleY(0.9f);
        logo.animate().scaleX(1f).scaleY(1f).setDuration(350L)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();

        // Smooth exit: the fade runs on a hardware layer (no per-frame re-draw of the gradient).
        try { overlay.setLayerType(View.LAYER_TYPE_HARDWARE, null); } catch (Throwable ignored) { }

        // One timer for the whole hold instead of an 80 ms polling loop.
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    if (overlay.getParent() == decor) decor.removeView(overlay);
                    return;
                }
                try { playHomeEntrance(activity); } catch (Throwable ignored) { }
                overlay.animate().alpha(0f).setDuration(EXIT_MS).withEndAction(new Runnable() {
                    @Override public void run() {
                        if (overlay.getParent() == decor) decor.removeView(overlay);
                        // v10.66 (TV only): the remote starts on «الرئيسية» in the side rail
                        try { if (CigramTvMode.isTv(activity)) CigramTvRail.focusSelected(activity); }
                        catch (Throwable ignored) { }
                    }
                }).start();
            }
        }, Math.max(0L, remainingMs()));
    }

    /**
     * Home's own entrance, started as the splash fades: its blocks (carousel, sections) rise in
     * from below one after another and the bottom bar bounces up. Only the first screenful is
     * animated; scrolled-off content is left alone.
     */
    private static void playHomeEntrance(Activity activity) {
        View root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        int id = activity.getResources().getIdentifier("linear1", "id", activity.getPackageName());
        View home = id == 0 ? null : root.findViewById(id);
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        float rise = CigramUI.dp(activity, 38);
        if (home instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) home;
            int shown = 0;
            int[] at = new int[2];
            for (int i = 0; i < group.getChildCount() && shown < 7; i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getHeight() <= 0) continue;
                child.getLocationOnScreen(at);
                if (at[1] > screenH) break;
                child.setAlpha(0f);
                child.setTranslationY(rise);
                child.animate().alpha(1f).translationY(0f)
                        .setStartDelay(ENTRANCE_DELAY_MS + shown * ENTRANCE_STEP_MS).setDuration(380L)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
                shown++;
            }
        }
        View bar = root.findViewWithTag("transparent_cigram_nav");
        if (bar != null) {
            bar.setTranslationY(CigramUI.dp(activity, 90));
            bar.animate().translationY(0f).setStartDelay(ENTRANCE_DELAY_MS + 120L).setDuration(450L)
                    .setInterpolator(new OvershootInterpolator(1.2f)).start();
        }
    }

    /** "Welcome to CIGRAM" with CIGRAM in the brand red. */
    private static CharSequence brandLine() {
        String dim = "Welcome to ";
        String hot = "CIGRAM";
        SpannableString s = new SpannableString(dim + hot);
        s.setSpan(new ForegroundColorSpan(LABEL_DIM), 0, dim.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(LABEL_HOT), dim.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** Page colour with a tall maroon radial glow in the centre (OTTO-style). */
    private static GradientDrawable glow(Activity activity) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(PAGE);
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.42f);
        float radius;
        try {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            radius = Math.max(dm.widthPixels, dm.heightPixels) * 0.62f;
        } catch (Throwable t) {
            radius = 1200f;
        }
        g.setGradientRadius(radius);
        g.setColors(new int[]{GLOW_CENTER, GLOW_MID, PAGE});
        return g;
    }
}
