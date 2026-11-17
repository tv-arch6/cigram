package com.Cigram.vid;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 19.6 — «تأخر ظهور الفيلم بعد إضافته من الإدارة».
 *
 * Why titles arrived late: the app only asked the server for the catalogue when its saved copy was older than a TTL
 * (2 min on a cold start, 5 min when a tab or the app came back, ×3 on mobile data = 15 min), and the live Worker answers
 * every {@code /movies} request with the whole 7.6 MB catalogue, so asking more often was not an option.
 *
 * The fix: movies.json lives in the public R2 bucket, and R2 answers a {@code HEAD} with the object's ETag in ~300 bytes.
 * While Cigram is on screen this class asks that every {@link #FOREGROUND_EVERY_MS}, on every return to the app and on
 * every new-title push. When the ETag moved, the normal sync runs at once ({@link CigramRefreshPolicy.Reason#CHANGED},
 * which skips the TTL), the catalogue is installed and Home / «المزيد» / search redraw with the new title — seconds after
 * the administrator pressed «إضافة», not minutes. An unchanged ETag costs nothing (no download, no parse).
 *
 * The ETag is only marked "seen" after a sync committed successfully, so a failed download is retried on the next beat.
 */
final class CigramCatalogPulse {

    static final String PROBE_URL = "https://pub-eabe41d14a0644e5800344c99ef4d971.r2.dev/movies.json";
    static final long FOREGROUND_EVERY_MS = 20000L;
    private static final long MIN_GAP_MS = 6000L;
    private static final String PREFS = "cigram_catalog_pulse";
    private static final String KEY_SEEN = "seen_etag";
    private static final String KEY_LAST_CHANGE = "last_change_at";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Context APP;
    private static volatile boolean installed;
    private static volatile boolean probing;
    private static volatile long lastProbeAt;
    /** ETag seen by the last probe that asked for a sync; committed as "seen" when that sync succeeds. */
    private static volatile String pendingEtag = "";
    private static int started;

    private CigramCatalogPulse() { }

    private static final Runnable BEAT = new Runnable() {
        @Override public void run() {
            MAIN.removeCallbacks(this);
            if (started <= 0 || APP == null) return;
            probe(APP, "beat");
            // a renamed / hidden / deleted section does not touch movies.json: read the (1 KB) registry on the beat too
            try {
                CigramHomeOrder.refresh(APP, new Runnable() {
                    @Override public void run() { CigramStage1InitProvider.rerenderHomeIfOpen(); }
                });
            } catch (Throwable ignored) { }
            MAIN.postDelayed(this, CigramPerfConfig.pulseMs(APP, FOREGROUND_EVERY_MS));
        }
    };

    /** Called once from the init provider: follows the app to the foreground / background. */
    static void install(Context context) {
        if (installed || context == null) return;
        installed = true;
        APP = context.getApplicationContext();
        if (!(APP instanceof Application)) return;
        ((Application) APP).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) {
                started++;
                if (started == 1) {
                    probe(APP, "foreground");
                    CigramPerfConfig.refresh(APP);   // 19.6: the admin's «سلاسة التطبيق» settings
                    MAIN.removeCallbacks(BEAT);
                    MAIN.postDelayed(BEAT, CigramPerfConfig.pulseMs(APP, FOREGROUND_EVERY_MS));
                }
            }
            @Override public void onActivityStopped(Activity a) {
                started = Math.max(0, started - 1);
                if (started == 0) MAIN.removeCallbacks(BEAT);
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }

    /** A push said "new title / new episode": ask now, whatever the beat. */
    static void kick(Context context, String why) {
        if (context == null) return;
        lastProbeAt = 0L;
        probe(context.getApplicationContext(), why);
    }

    /** Cheap HEAD of movies.json; starts the sync when the ETag moved. Never on the calling thread. */
    static void probe(final Context context, final String why) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        long now = android.os.SystemClock.elapsedRealtime();
        if (probing || (lastProbeAt > 0L && now - lastProbeAt < MIN_GAP_MS)) return;
        if (!CigramStage1InitProvider.isOnlineNow(app)) return;
        probing = true;
        lastProbeAt = now;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String etag = headEtag();
                    if (etag.length() == 0) return;
                    String seen = prefs(app).getString(KEY_SEEN, "");
                    if (etag.equals(seen)) return;
                    pendingEtag = etag;
                    android.util.Log.i("CigramPulse", "catalogue changed (" + why + "): " + seen + " -> " + etag);
                    CigramStage1InitProvider.refreshCatalogBecauseChanged(app);
                } catch (Throwable error) {
                    android.util.Log.w("CigramPulse", "probe failed", error);
                } finally {
                    probing = false;
                }
            }
        }, "cigram-pulse").start();
    }

    /** The sync that a probe asked for committed: the ETag it saw is now the known one. */
    static void syncSucceeded(Context context) {
        String e = pendingEtag;
        if (context == null || e == null || e.length() == 0) return;
        try {
            prefs(context).edit().putString(KEY_SEEN, e).putLong(KEY_LAST_CHANGE, System.currentTimeMillis()).apply();
        } catch (Throwable ignored) { }
        pendingEtag = "";
    }

    /**
     * Runs on the sync's own network thread right before it downloads: a sync that was NOT started by a probe (cold start,
     * pull-to-refresh, tab TTL) learns the current ETag too, so the next beat does not download the same catalogue again.
     * If movies.json changes between this HEAD and the download, the older ETag is committed and the next beat syncs again.
     */
    static void beforeSync() {
        if (pendingEtag != null && pendingEtag.length() > 0) return;
        try {
            String e = headEtag();
            if (e.length() > 0) pendingEtag = e;
        } catch (Throwable ignored) { }
    }

    static String headEtag() throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(PROBE_URL).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setUseCaches(false);
            c.setRequestProperty("Cache-Control", "no-cache");
            c.setRequestProperty("User-Agent", "Cigram-Android/1.0");
            int code = c.getResponseCode();
            if (code != 200) return "";
            String etag = c.getHeaderField("ETag");
            if (etag == null || etag.trim().length() == 0) {
                String lm = c.getHeaderField("Last-Modified");
                String len = c.getHeaderField("Content-Length");
                return lm == null ? "" : ("lm:" + lm + "|" + len);
            }
            return normalize(etag);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** {@code W/"abc"} and {@code "abc"} are the same object. */
    static String normalize(String etag) {
        if (etag == null) return "";
        String e = etag.trim();
        if (e.startsWith("W/") || e.startsWith("w/")) e = e.substring(2);
        if (e.length() >= 2 && e.startsWith("\"") && e.endsWith("\"")) e = e.substring(1, e.length() - 1);
        return e.trim();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
