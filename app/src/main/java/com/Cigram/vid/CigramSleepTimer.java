package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;

/**
 * «موقّت النوم» — a Cigram Plus service.
 *
 * The subscriber picks 15, 30, 45, 60 or 90 minutes (Cigram Plus page). From the moment a film or an episode starts,
 * the clock runs; when it ends the player closes itself — the position is saved as usual, so the title continues from
 * there the next day — and the phone can sleep. It is a one-night switch: once it has fired it turns itself off.
 *
 * The clock only counts while the player is on screen (a paused, backgrounded player does not use the time up).
 */
final class CigramSleepTimer {

    private static final String PREFS = "cigram_sleep_timer";
    static final int[] MINUTES = {0, 15, 30, 45, 60, 90};

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> player = new WeakReference<Activity>(null);
    private static long leftMs;
    private static long resumedAt;

    private CigramSleepTimer() { }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int minutes(Context c) {
        try { return CigramPlus.isPlus(c) ? sp(c).getInt("minutes", 0) : 0; } catch (Throwable e) { return 0; }
    }

    static String label(Context c) {
        int m = minutes(c);
        return m <= 0 ? "متوقف" : "بعد " + m + " دقيقة من بدء المشاهدة";
    }

    static void choose(final Activity a, final Runnable after) {
        if (!CigramPlusUI.requirePlus(a, "موقّت النوم")) return;
        final String[] items = new String[MINUTES.length];
        int sel = 0, now = minutes(a);
        for (int i = 0; i < MINUTES.length; i++) {
            items[i] = MINUTES[i] == 0 ? "متوقف" : MINUTES[i] + " دقيقة";
            if (MINUTES[i] == now) sel = i;
        }
        new android.app.AlertDialog.Builder(a).setTitle("موقّت النوم")
                .setSingleChoiceItems(items, sel, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        sp(a).edit().putInt("minutes", MINUTES[w]).apply();
                        leftMs = 0L;
                        d.dismiss();
                        if (after != null) after.run();
                    }
                }).setNegativeButton("إغلاق", null).show();
    }

    private static final Runnable FIRE = new Runnable() {
        @Override public void run() {
            Activity a = player.get();
            leftMs = 0L;
            if (a == null || a.isFinishing() || a.isDestroyed()) return;
            try { sp(a).edit().putInt("minutes", 0).apply(); } catch (Throwable ignored) { }
            try {
                CigramSilent.toast(a.getApplicationContext(), "انتهى موقّت النوم · تصبح على خير", android.widget.Toast.LENGTH_LONG);
                a.finish();
            } catch (Throwable ignored) { }
        }
    };

    /** The player came to the front. */
    static void playerResumed(Activity a) {
        if (a == null) return;
        int m = minutes(a);
        MAIN.removeCallbacks(FIRE);
        if (m <= 0) { leftMs = 0L; return; }
        if (player.get() != a || leftMs <= 0L) leftMs = m * 60000L;
        player = new WeakReference<Activity>(a);
        resumedAt = android.os.SystemClock.elapsedRealtime();
        MAIN.postDelayed(FIRE, leftMs);
    }

    /** The player left the front (paused / another app): the remaining time is kept. */
    static void playerPaused(Activity a) {
        if (player.get() != a || leftMs <= 0L) return;
        MAIN.removeCallbacks(FIRE);
        leftMs = Math.max(1000L, leftMs - (android.os.SystemClock.elapsedRealtime() - resumedAt));
    }

    static void playerDestroyed(Activity a) {
        if (player.get() != a) return;
        MAIN.removeCallbacks(FIRE);
        leftMs = 0L;
        player = new WeakReference<Activity>(null);
    }
}
