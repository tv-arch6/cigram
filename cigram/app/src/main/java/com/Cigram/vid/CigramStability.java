package com.Cigram.vid;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.util.Log;

import java.io.File;

/**
 * v10.49: keeps the app steady.
 *  - Memory: when Android asks for memory back, the image caches shrink (half, or all when the app
 *    is in the background and the system is short). The pages are kept alive for instant tab
 *    switches, so this is what stops the app from being killed in the background.
 *  - Crash record: the last crash is saved (never swallowed, the normal handler still runs), and
 *    two crashes within 90 s clear the rebuildable caches on the next start (a bad cache is the
 *    usual cause of a crash loop). Accounts, history and settings are never touched.
 */
final class CigramStability {
    private static final String TAG = "CigramStability";
    private static final String PREFS = "cigram_stability";
    private static final long LOOP_WINDOW_MS = 90000L;
    private static boolean started;

    private CigramStability() { }

    static void init(final Context context) {
        if (context == null || started) return;
        started = true;
        final Context app = context.getApplicationContext();
        try {
            recoverFromCrashLoop(app);
        } catch (Throwable error) {
            Log.w(TAG, "recovery check failed", error);
        }
        try {
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override public void uncaughtException(Thread thread, Throwable error) {
                    try {
                        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                        long now = System.currentTimeMillis();
                        long last = p.getLong("last_crash_at", 0L);
                        int count = now - last < LOOP_WINDOW_MS ? p.getInt("recent_crashes", 0) + 1 : 1;
                        String text = String.valueOf(error);
                        if (text.length() > 400) text = text.substring(0, 400);
                        p.edit().putLong("last_crash_at", now).putInt("recent_crashes", count)
                                .putString("last_crash", text)
                                .putString("last_crash_trace", crashTrace(error))   // v10.65: shown once on a TV
                                .putBoolean("trace_pending", true)
                                .commit();   // the process is about to end
                    } catch (Throwable ignored) { }
                    if (previous != null) previous.uncaughtException(thread, error);
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "crash record failed", error);
        }
        try {
            if (app instanceof Application) {
                ((Application) app).registerComponentCallbacks(new ComponentCallbacks2() {
                    @Override public void onTrimMemory(int level) { trim(level); }
                    @Override public void onConfigurationChanged(Configuration newConfig) { }
                    @Override public void onLowMemory() { trim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE); }
                });
            }
        } catch (Throwable error) {
            Log.w(TAG, "memory callbacks failed", error);
        }
    }

    /** v10.65: the exception and its first frames (and the cause), short enough for one screen. */
    private static String crashTrace(Throwable error) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(error.getClass().getName()).append(": ").append(String.valueOf(error.getMessage())).append('\n');
            StackTraceElement[] st = error.getStackTrace();
            for (int i = 0; i < st.length && i < 8; i++) sb.append("at ").append(st[i]).append('\n');
            Throwable cause = error.getCause();
            if (cause != null && cause != error) {
                sb.append("Caused by: ").append(cause.getClass().getName()).append(": ")
                        .append(String.valueOf(cause.getMessage())).append('\n');
                StackTraceElement[] cs = cause.getStackTrace();
                for (int i = 0; i < cs.length && i < 5; i++) sb.append("at ").append(cs[i]).append('\n');
            }
            String s = sb.toString();
            return s.length() > 1500 ? s.substring(0, 1500) : s;
        } catch (Throwable ignored) {
            return String.valueOf(error);
        }
    }

    /** 0 = leave it, 1 = keep about three quarters, 2 = keep about half, 3 = drop everything. */
    private static int severity(int level) {
        if (level >= 40) return 3;                                   // background and the system is short
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) return 2;   // 15 .. 39 (incl. UI hidden)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return 1;        // 10 .. 14
        return 0;
    }

    static void trim(int level) {
        int s = severity(level);
        if (s == 0) return;
        try { CigramStage1InitProvider.trimMemory(s); } catch (Throwable ignored) { }
        try { AdsMedia.trimMemory(s); } catch (Throwable ignored) { }
        try { CigramChannels.trimMemory(s); } catch (Throwable ignored) { }
        try { CigramLogos.trim(s); } catch (Throwable ignored) { }
        try { CigramSketchwareNotifications.trimMemory(s); } catch (Throwable ignored) { }
        if (s >= 3) {
            try { CigramImdbIcon.trim(); } catch (Throwable ignored) { }
        }
    }

    /** Applies severity to an LruCache: keep 3/4, 1/2 or nothing. */
    static void shrink(android.util.LruCache<?, ?> cache, int severity) {
        if (cache == null) return;
        if (severity >= 3) cache.evictAll();
        else cache.trimToSize(Math.max(0, cache.size() * (severity == 2 ? 1 : 3) / (severity == 2 ? 2 : 4)));
    }

    private static void recoverFromCrashLoop(Context app) {
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = p.getLong("last_crash_at", 0L);
        int count = p.getInt("recent_crashes", 0);
        boolean loop = count >= 2 && System.currentTimeMillis() - last < LOOP_WINDOW_MS;
        if (!loop) return;
        Log.w(TAG, "crash loop: clearing rebuildable caches");
        File hero = new File(app.getCacheDir(), "cigram_hero_cache");
        deleteTree(hero);
        for (String name : new String[]{"cigram_carousel_cache", "cigram_home_sections"}) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply();
        }
        p.edit().putInt("recent_crashes", 0).apply();
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
