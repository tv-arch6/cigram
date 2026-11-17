package com.Cigram.vid;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Looper;
import android.os.StrictMode;
import android.util.Log;

import java.util.HashMap;

/**
 * Light performance diagnostics (18.3).
 *
 *  - {@link #slow}: a measured step took longer than its budget -> one log line (at most one per label every 30 s), counted.
 *  - {@link #request}: every network request of the data layer is registered; the same address twice within 10 s is counted as a
 *    duplicate (and logged in debug builds), so a regression of "the same dataset downloaded again" is visible in logcat.
 *  - {@link #installDebugChecks}: StrictMode (thread policy on the main thread + VM leak detection) and a main-thread frame
 *    watchdog, ONLY in debuggable builds, log-only: no dialog, no penalty, nothing in release builds.
 * Nothing here touches the network, the disk or the UI.
 */
final class CigramPerf {

    private static final String TAG = "CigramPerf";
    private static final long LOG_GAP_MS = 30000L;
    private static final long DUPLICATE_WINDOW_MS = 10000L;
    private static final HashMap<String, Long> LAST_LOGGED = new HashMap<String, Long>();
    private static final HashMap<String, Long> LAST_REQUEST = new HashMap<String, Long>();
    private static final HashMap<String, Integer> COUNTERS = new HashMap<String, Integer>();

    private CigramPerf() { }

    /** A step finished in {@code ms}; logs when it exceeded {@code budgetMs} (the main thread is called out). */
    static void slow(String label, long ms, long budgetMs) {
        if (ms < budgetMs) return;
        boolean main = Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper();
        count(main ? "slow.main." + label : "slow." + label);
        long now = System.currentTimeMillis();
        synchronized (LAST_LOGGED) {
            Long last = LAST_LOGGED.get(label);
            if (last != null && now - last < LOG_GAP_MS) return;
            LAST_LOGGED.put(label, now);
        }
        Log.w(TAG, label + " took " + ms + " ms" + (main ? " ON THE MAIN THREAD" : "") + " (budget " + budgetMs + " ms)");
    }

    /** Registers a network request of the data layer; returns true when the same key was requested in the last 10 s. */
    static boolean request(String key) {
        long now = System.currentTimeMillis();
        boolean duplicate;
        synchronized (LAST_REQUEST) {
            Long last = LAST_REQUEST.get(key);
            duplicate = last != null && now - last < DUPLICATE_WINDOW_MS;
            LAST_REQUEST.put(key, now);
        }
        count("request." + key);
        if (duplicate) {
            count("duplicate." + key);
            Log.w(TAG, "duplicate request within 10 s: " + key);
        }
        return duplicate;
    }

    static void count(String key) {
        synchronized (COUNTERS) {
            Integer n = COUNTERS.get(key);
            COUNTERS.put(key, n == null ? 1 : n + 1);
        }
    }

    static int counter(String key) {
        synchronized (COUNTERS) {
            Integer n = COUNTERS.get(key);
            return n == null ? 0 : n;
        }
    }

    /** Debug builds only: log-only StrictMode + a watchdog that reports main-thread frames longer than 700 ms. */
    static void installDebugChecks(Context context) {
        try {
            if (context == null || (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects()
                    .detectActivityLeaks().detectLeakedRegistrationObjects().penaltyLog().build());
            startWatchdog();
        } catch (Throwable ignored) { }
    }

    private static volatile long lastTick;

    private static void startWatchdog() {
        final android.os.Handler main = new android.os.Handler(Looper.getMainLooper());
        lastTick = android.os.SystemClock.uptimeMillis();
        main.post(new Runnable() {
            @Override public void run() {
                lastTick = android.os.SystemClock.uptimeMillis();
                main.postDelayed(this, 250L);
            }
        });
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    try { Thread.sleep(500L); } catch (InterruptedException e) { return; }
                    long late = android.os.SystemClock.uptimeMillis() - lastTick;
                    if (late > 700L) slow("main.thread.blocked", late, 700L);
                }
            }
        }, "cigram-perf-watchdog");
        t.setDaemon(true);
        t.start();
    }
}
