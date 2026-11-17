package com.Cigram.vid;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;

/**
 * 19.6 — «ابقاء التطبيق شغال في الخلفية بعد الخروج منه».
 *
 * Android ends a backgrounded app whenever it wants memory (and Xiaomi / Huawei / Samsung battery managers do it within
 * minutes): coming back meant a cold start, a reloaded Home and a player that started from zero. {@link CigramKeepAliveService}
 * is a small foreground service with a silent, low-priority notification («سيكرام يعمل في الخلفية»), started while the app is
 * on screen (Android 12+ forbids starting it later) and kept when the app is swiped away. While it runs:
 *  - the process (catalogue in memory, Home, the player) survives, so returning is instant;
 *  - the catalogue change check keeps running every minute (a 300-byte HEAD), so new titles and their (verified)
 *    notifications arrive while the app is closed.
 *
 * The viewer can switch it off from the notification («إيقاف») or from the settings; the admin app sets the default
 * («سلاسة التطبيق» ← التشغيل في الخلفية). No wake lock is held: it costs no battery while nothing changes.
 */
final class CigramKeepAlive {

    static final String PREFS = "cigram_keepalive";
    static final String KEY_ENABLED = "enabled";
    private static volatile boolean installed;

    private CigramKeepAlive() { }

    static void install(Context context) {
        if (installed || context == null) return;
        installed = true;
        final Context app = context.getApplicationContext();
        if (!(app instanceof Application)) return;
        ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) {
                // started while an activity is visible: allowed on every Android version
                if (enabled(app)) start(app);
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }

    /** The viewer's choice, else the admin's default (on). */
    static boolean enabled(Context c) {
        if (c == null) return false;
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.contains(KEY_ENABLED)) return p.getBoolean(KEY_ENABLED, true);
        return CigramPerfConfig.keepAliveDefault(c);
    }

    static void setEnabled(Context c, boolean on) {
        if (c == null) return;
        Context app = c.getApplicationContext();
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply();
        if (on) start(app); else stop(app);
    }

    /** The admin changed the default: follow it unless the viewer decided. */
    static void configChanged(Context c) {
        if (c == null) return;
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.contains(KEY_ENABLED)) return;
        if (!CigramPerfConfig.keepAliveDefault(c)) stop(c.getApplicationContext());
    }

    static void start(Context app) {
        if (CigramKeepAliveService.running) return;
        try {
            Intent i = new Intent(app, CigramKeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Throwable error) {
            // Android 12+: not allowed from the background (a push woke the process). The next time the app is opened it starts.
            android.util.Log.w("CigramKeepAlive", "not started now", error);
        }
    }

    static void stop(Context app) {
        try {
            app.stopService(new Intent(app, CigramKeepAliveService.class));
        } catch (Throwable ignored) { }
    }
}
