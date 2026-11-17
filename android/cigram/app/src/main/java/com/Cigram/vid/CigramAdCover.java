package com.Cigram.vid;

import android.app.Activity;
import android.graphics.Color;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Opaque black cover that hides the player (video frame, controls, system chrome) for the whole
 * time an ad is about to open, is on screen, and is closing. Without it the film flashes for a
 * moment between the pause and the ad window, and again when the ad closes.
 *
 * show() returns the cover (or null when it cannot be added); hide() removes it with a short
 * fade. Both are null-safe and idempotent, and a fail-safe removes the cover on its own after
 * {@link #FAILSAFE_MS}, so a missed hide() can never leave a black screen behind.
 */
public final class CigramAdCover {
    private static final long FAILSAFE_MS = 4L * 60L * 1000L;
    private static final long FADE_MS = 160L;

    private CigramAdCover() {
    }

    public static View show(Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
            if (Looper.myLooper() != Looper.getMainLooper()) return null;
            if (activity.getWindow() == null) return null;
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return null;

            final FrameLayout cover = new FrameLayout(activity);
            cover.setBackgroundColor(Color.BLACK);
            cover.setClickable(true); // swallow touches meant for the player underneath
            cover.setElevation(10000f);
            ((ViewGroup) decor).addView(cover, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            cover.postDelayed(new Runnable() {
                @Override public void run() {
                    remove(cover);
                }
            }, FAILSAFE_MS);
            return cover;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void hide(final View cover) {
        if (cover == null) return;
        try {
            if (cover.getParent() == null) return;
            cover.animate().alpha(0f).setDuration(FADE_MS).withEndAction(new Runnable() {
                @Override public void run() {
                    remove(cover);
                }
            }).start();
        } catch (Throwable ignored) {
            remove(cover);
        }
    }

    private static void remove(View cover) {
        try {
            if (cover == null) return;
            cover.animate().cancel();
            if (cover.getParent() instanceof ViewGroup) {
                ((ViewGroup) cover.getParent()).removeView(cover);
            }
        } catch (Throwable ignored) {
            // already detached
        }
    }
}
