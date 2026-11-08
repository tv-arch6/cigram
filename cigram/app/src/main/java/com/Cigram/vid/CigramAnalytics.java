package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Central, privacy-safe analytics for Cigram.
 *
 * - Never throws into callers. Every public method is wrapped.
 * - Never records tokens / passwords / e-mails / phone numbers (sanitised).
 * - Events are queued in memory + a bounded persisted ring, and posted in
 *   batches from a background thread with exponential back-off.
 * - Upload goes to ANALYTICS_ENDPOINT (see policy below). Empty endpoint => local ring only,
 *   no network. ANALYTICS_ENABLED=false => nothing recorded.
 *
 * Usage:  CigramAnalytics.track(ctx, "details_loaded", "type", "movie");
 */
public final class CigramAnalytics {
    /**
     * ANALYTICS POLICY (v3.1) — single place to change it.
     * ANALYTICS_ENABLED=false  => nothing is recorded, nothing is sent.
     * ANALYTICS_ENDPOINT empty => events are recorded locally only (bounded ring), no network.
     * ANALYTICS_ENDPOINT set   => batched upload. STATUS: the Worker below was deployed by the
     *                             project owner and received events from a real device on
     *                             2026-10-05; it is a public endpoint owned by the project.
     * Uploads are bounded: exponential back-off, and after 6 consecutive failures uploads are
     * paused for 6 h (persisted) so a dead endpoint costs no battery or data.
     */
    public static final boolean ANALYTICS_ENABLED = true;
    /** v5.28: events go to the admin Worker (cigram-admin-api POST /telemetry/events), which the
     *  admin app's "فحص الإعلانات" screen reads. The old cigram-analytics Worker is no longer used. */
    public static final String ANALYTICS_ENDPOINT = "https://cigram-admin-api.wwq-mixtv.workers.dev/telemetry/events";

    /**
     * NOT A SECRET. Anything inside an APK can be extracted. This value is only a basic abuse
     * filter for the Worker (it keeps random scanners out). Real protection is server-side:
     * payload validation, size limit, rate limiting. Never put real secrets in the app.
     */
    private static final String INGEST_KEY = "8Eq4QpLVE3Y6YMGJfwM05ylJFv_ESHWx";
    private static final String PREFS = "cigram_analytics_v1";
    private static final String KEY_ANON = "anon_id";
    private static final String KEY_QUEUE = "queue";
    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_ENGAGED_DAY = "engaged_day";
    private static final String KEY_PAUSED_UNTIL = "paused_until";
    private static final int BREAKER_FAILURES = 6;
    private static final long BREAKER_PAUSE_MS = 6L * 60L * 60L * 1000L;
    private static final int MAX_QUEUE = 300;
    private static final int BATCH_SIZE = 25;
    private static final int FLUSH_AT = 12;
    private static final long FLUSH_INTERVAL_MS = 30000L;
    private static final long SESSION_GAP_MS = 30L * 60L * 1000L;
    private static final long MAX_BACKOFF_MS = 15L * 60L * 1000L;
    private static final int MAX_VALUE_LEN = 120;
    private static final int MAX_PARAMS = 16;
    private static final int THROTTLE_MAX = 3;
    private static final long THROTTLE_WINDOW_MS = 60000L;

    private static final Object LOCK = new Object();
    private static final ArrayList<JSONObject> QUEUE = new ArrayList<JSONObject>();
    private static final HashMap<String, Long> TIMERS = new HashMap<String, Long>();
    private static final HashMap<String, long[]> THROTTLE = new HashMap<String, long[]>();
    private static final HashSet<String> MILESTONES = new HashSet<String>();
    private static final HashSet<String> ONCE = new HashSet<String>();

    private static Context app;
    private static String anonId = "";
    private static String sessionId = "";
    private static String endpoint = ANALYTICS_ENDPOINT;
    private static int seq = 0;
    private static int started = 0;
    private static long backgroundAt = 0L;
    private static long lastFlush = 0L;
    private static long nextAttemptAt = 0L;
    private static int failures = 0;
    private static boolean flushing = false;
    private static long pausedUntil = 0L;
    private static String versionName = "";
    private static ExecutorService executor;

    private CigramAnalytics() {
    }

    // ------------------------------------------------------------------ init

    public static void init(Context context) {
        try {
            if (context == null || !ANALYTICS_ENABLED) return;
            synchronized (LOCK) {
                if (app != null) return;
                app = context.getApplicationContext() != null
                        ? context.getApplicationContext() : context;
                SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                anonId = p.getString(KEY_ANON, "");
                if (anonId == null || anonId.length() < 8) {
                    anonId = UUID.randomUUID().toString();
                    p.edit().putString(KEY_ANON, anonId).apply();
                }
                String stored = p.getString(KEY_ENDPOINT, "");
                if ((endpoint == null || endpoint.length() == 0)
                        && stored != null && stored.startsWith("https://")) {
                    endpoint = stored;
                }
                sessionId = UUID.randomUUID().toString();
                pausedUntil = p.getLong(KEY_PAUSED_UNTIL, 0L);
                loadQueueLocked(p);
                try {
                    versionName = app.getPackageManager()
                            .getPackageInfo(app.getPackageName(), 0).versionName;
                    if (versionName == null) versionName = "";
                } catch (Throwable ignored) {
                    versionName = "";
                }
            }
            track(app, "app_open");
            track(app, "session_start");
        } catch (Throwable ignored) {
            // analytics must never break the app
        }
    }

    /** Central switch used by every call site. */
    public static boolean isAnalyticsEnabled() {
        return ANALYTICS_ENABLED;
    }

    public static void setEndpoint(Context context, String url) {
        try {
            init(context);
            if (url == null || !url.startsWith("https://")) return;
            synchronized (LOCK) {
                endpoint = url;
                if (app != null) {
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putString(KEY_ENDPOINT, url).apply();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static String sessionId() {
        synchronized (LOCK) {
            return sessionId;
        }
    }

    // -------------------------------------------------------------- lifecycle

    public static void onForeground(Activity activity) {
        try {
            init(activity);
            boolean rotated = false;
            synchronized (LOCK) {
                started++;
                if (backgroundAt > 0L && System.currentTimeMillis() - backgroundAt > SESSION_GAP_MS) {
                    sessionId = UUID.randomUUID().toString();
                    seq = 0;
                    MILESTONES.clear();
                    rotated = true;
                }
                backgroundAt = 0L;
            }
            if (rotated) track(activity, "session_start");
        } catch (Throwable ignored) {
        }
    }

    public static void onBackground(Activity activity) {
        try {
            boolean last = false;
            synchronized (LOCK) {
                if (started > 0) started--;
                if (started == 0) {
                    backgroundAt = System.currentTimeMillis();
                    last = true;
                }
            }
            if (last) {
                track(activity, "app_background");
                PERSIST_HANDLER.removeCallbacks(PERSIST_TASK);
                PERSIST_TASK.run();   // save the queue now: the process may be killed in the background
                flushAsync(true);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void screen(Activity activity) {
        try {
            if (activity == null) return;
            String name = activity.getClass().getSimpleName();
            track(activity, "screen_view", "screen", name);
        } catch (Throwable ignored) {
        }
    }

    // ----------------------------------------------------------------- timers

    public static void timerStart(String name) {
        try {
            if (name == null) return;
            synchronized (LOCK) {
                TIMERS.put(name, Long.valueOf(SystemClock.elapsedRealtime()));
            }
        } catch (Throwable ignored) {
        }
    }

    /** Stops a timer once and reports it as a perf metric. Returns ms or -1. */
    public static long timerStop(Context context, String name, String... kv) {
        // Home reports here when it has finished rendering: that ends the launch screen
        if ("home_load_ms".equals(name)) CigramSplash.markHomeReady();
        try {
            if (name == null) return -1L;
            Long t0;
            synchronized (LOCK) {
                t0 = TIMERS.remove(name);
            }
            if (t0 == null) return -1L;
            long ms = SystemClock.elapsedRealtime() - t0.longValue();
            if (ms < 0L || ms > 10L * 60L * 1000L) return -1L;
            String[] all = new String[(kv == null ? 0 : kv.length) + 4];
            all[0] = "metric";
            all[1] = name;
            all[2] = "ms";
            all[3] = String.valueOf(ms);
            if (kv != null) System.arraycopy(kv, 0, all, 4, kv.length);
            track(context, "perf", all);
            return ms;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    // ------------------------------------------------------------------ track

    /** kv = alternating key, value strings. */
    public static void track(Context context, String event, String... kv) {
        try {
            if (!ANALYTICS_ENABLED || event == null || event.length() == 0) return;
            if (app == null) init(context);
            if (app == null) return;
            if (throttled(event, kv)) return;

            JSONObject e = new JSONObject();
            e.put("e", event);
            e.put("t", System.currentTimeMillis());
            JSONObject p = new JSONObject();
            if (kv != null) {
                int n = 0;
                for (int i = 0; i + 1 < kv.length && n < MAX_PARAMS; i += 2) {
                    String k = kv[i];
                    String v = kv[i + 1];
                    if (k == null || v == null || isSensitiveKey(k) || looksSensitive(v)) continue;
                    if (v.length() > MAX_VALUE_LEN) v = v.substring(0, MAX_VALUE_LEN);
                    p.put(k, v);
                    n++;
                }
            }
            e.put("p", p);
            boolean flushNow;
            synchronized (LOCK) {
                e.put("s", sessionId);
                e.put("n", ++seq);
                QUEUE.add(e);
                while (QUEUE.size() > MAX_QUEUE) QUEUE.remove(0);
                persistLocked();
                flushNow = QUEUE.size() >= FLUSH_AT
                        || SystemClock.elapsedRealtime() - lastFlush > FLUSH_INTERVAL_MS;
            }
            if (flushNow) flushAsync(false);
        } catch (Throwable ignored) {
        }
    }

    /** Playback milestones: play_started, watch_30s, watch_2m, watch_25/50/75, watch_complete. */
    public static void playProgress(Context context, String contentKey, String type,
                                    long positionMs, long durationMs, boolean finished) {
        playProgress(context, contentKey, type, positionMs, durationMs, finished, "", 0, 0);
    }

    /** Series items carry series_id + season + episode (the real episode key), movies leave them empty. */
    public static void playProgress(Context context, String contentKey, String type,
                                    long positionMs, long durationMs, boolean finished,
                                    String seriesId, int season, int episode) {
        try {
            if (contentKey == null || contentKey.length() == 0) return;
            String k = contentKey.length() > 80 ? contentKey.substring(0, 80) : contentKey;
            String[] ids = new String[]{"content_id", k,
                    "series_id", seriesId == null ? "" : seriesId,
                    "season", String.valueOf(season), "episode", String.valueOf(episode)};
            if (positionMs > 1000L) milestone(context, k, "play_started", type, positionMs, durationMs, ids);
            if (positionMs >= 30000L) milestone(context, k, "watch_30s", type, positionMs, durationMs, ids);
            if (positionMs >= 120000L) {
                if (milestone(context, k, "watch_2m", type, positionMs, durationMs, ids)) engagedDay(context);
            }
            if (durationMs > 0L) {
                long pct = positionMs * 100L / durationMs;
                if (pct >= 25L) milestone(context, k, "watch_25", type, positionMs, durationMs, ids);
                if (pct >= 50L) milestone(context, k, "watch_50", type, positionMs, durationMs, ids);
                if (pct >= 75L) milestone(context, k, "watch_75", type, positionMs, durationMs, ids);
            }
            if (finished || (durationMs > 0L && positionMs * 100L >= durationMs * 92L)) {
                milestone(context, k, "watch_complete", type, positionMs, durationMs, ids);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean milestone(Context c, String key, String name, String type,
                                     long pos, long dur, String[] ids) {
        boolean fresh;
        synchronized (LOCK) {
            fresh = MILESTONES.add(name + "|" + key);
        }
        if (fresh) {
            String[] kv = new String[6 + ids.length];
            kv[0] = "type";
            kv[1] = type == null ? "" : type;
            kv[2] = "pos_s";
            kv[3] = String.valueOf(pos / 1000L);
            kv[4] = "dur_s";
            kv[5] = String.valueOf(dur / 1000L);
            System.arraycopy(ids, 0, kv, 6, ids.length);
            track(c, name, kv);
        }
        return fresh;
    }

    /**
     * North-Star readiness (Weekly Engaged Viewers): one event per anonymous
     * user per calendar day when they reach 2 minutes of playback. The server
     * counts distinct anon ids with this event per ISO week.
     */
    private static void engagedDay(Context c) {
        try {
            if (app == null) return;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            String day = cal.get(java.util.Calendar.YEAR) + "-"
                    + cal.get(java.util.Calendar.DAY_OF_YEAR);
            SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (day.equals(p.getString(KEY_ENGAGED_DAY, ""))) return;
            p.edit().putString(KEY_ENGAGED_DAY, day).apply();
            track(c, "engaged_viewer_day", "year", String.valueOf(cal.get(java.util.Calendar.YEAR)),
                    "week", String.valueOf(cal.get(java.util.Calendar.WEEK_OF_YEAR)));
        } catch (Throwable ignored) {
        }
    }

    /** Fires a given event only once per process for the given id. */
    public static void trackOnce(Context context, String event, String id, String... kv) {
        try {
            boolean fresh;
            synchronized (LOCK) {
                fresh = ONCE.add(event + "|" + id);
            }
            if (fresh) track(context, event, kv);
        } catch (Throwable ignored) {
        }
    }

    /** Emits at most one event per key per minMs (e.g. search while typing). */
    public static void trackRateLimited(Context context, String key, long minMs,
                                        String event, String... kv) {
        try {
            long now = SystemClock.elapsedRealtime();
            synchronized (LOCK) {
                long[] w = THROTTLE.get("rl|" + key);
                if (w != null && now - w[0] < minMs) return;
                THROTTLE.put("rl|" + key, new long[]{now, 1L});
            }
            track(context, event, kv);
        } catch (Throwable ignored) {
        }
    }

    public static void error(Context context, String where, Throwable t) {
        try {
            track(context, "app_error", "where", where == null ? "" : where,
                    "type", t == null ? "" : t.getClass().getSimpleName());
        } catch (Throwable ignored) {
        }
    }

    // --------------------------------------------------------------- privacy

    private static boolean isSensitiveKey(String k) {
        String s = k.toLowerCase(Locale.ROOT);
        return s.contains("token") || s.contains("pass") || s.contains("mail")
                || s.contains("secret") || s.contains("auth") || s.contains("phone")
                || s.contains("cookie") || s.contains("session_key") || s.contains("api_key");
    }

    private static boolean looksSensitive(String v) {
        if (v.indexOf('@') > 0 && v.indexOf('.') > v.indexOf('@')) return true;
        if (v.startsWith("Bearer ") || v.startsWith("eyJ")) return true;
        return false;
    }

    private static boolean throttled(String event, String[] kv) {
        boolean noisy = event.endsWith("_error") || event.endsWith("_failed")
                || event.equals("api_error") || event.equals("image_load_failed")
                || event.equals("app_error");
        if (!noisy) return false;
        String sig = event + "|" + (kv != null && kv.length > 1 ? kv[1] : "");
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            long[] w = THROTTLE.get(sig);
            if (w == null || now - w[0] > THROTTLE_WINDOW_MS) {
                THROTTLE.put(sig, new long[]{now, 1L});
                if (THROTTLE.size() > 200) THROTTLE.clear();
                return false;
            }
            w[1]++;
            return w[1] > THROTTLE_MAX;
        }
    }

    // ------------------------------------------------------------ persistence

    private static void loadQueueLocked(SharedPreferences p) {
        try {
            String raw = p.getString(KEY_QUEUE, "");
            if (raw == null || raw.length() == 0) return;
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length() && i < MAX_QUEUE; i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) QUEUE.add(o);
            }
        } catch (Throwable ignored) {
            try {
                p.edit().remove(KEY_QUEUE).apply();
            } catch (Throwable ignored2) {
            }
        }
    }

    // Perf: the queue used to be serialised and written on EVERY event, on the calling (often main)
    // thread, O(queue) each time. Writes are now coalesced: one background write ~2 s after the
    // first change, and an immediate one when the app goes to the background.
    private static boolean persistScheduled;
    private static final android.os.Handler PERSIST_HANDLER =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable PERSIST_TASK = new Runnable() {
        @Override public void run() {
            try {
                executor().execute(new Runnable() {
                    @Override public void run() { persistNow(); }
                });
            } catch (Throwable ignored) {
            }
        }
    };

    /** Called with LOCK held after the queue changed: schedules one coalesced write. */
    private static void persistLocked() {
        if (persistScheduled) return;
        persistScheduled = true;
        PERSIST_HANDLER.postDelayed(PERSIST_TASK, 2000L);
    }

    private static void persistNow() {
        try {
            String json;
            Context ctx;
            synchronized (LOCK) {
                persistScheduled = false;
                if (app == null) return;
                ctx = app;
                JSONArray a = new JSONArray();
                for (int i = 0; i < QUEUE.size(); i++) a.put(QUEUE.get(i));
                json = a.toString();
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_QUEUE, json).apply();
        } catch (Throwable ignored) {
        }
    }

    // ----------------------------------------------------------------- upload

    private static void flushAsync(final boolean force) {
        try {
            final String url;
            synchronized (LOCK) {
                url = endpoint;
                if (url == null || !url.startsWith("https://") || flushing || QUEUE.isEmpty()) return;
                if (System.currentTimeMillis() < pausedUntil) return;
                if (!force && SystemClock.elapsedRealtime() < nextAttemptAt) return;
                if (force && SystemClock.elapsedRealtime() < nextAttemptAt && failures > 0) return;
                flushing = true;
                lastFlush = SystemClock.elapsedRealtime();
            }
            executor().execute(new Runnable() {
                @Override public void run() {
                    doFlush(url);
                }
            });
        } catch (Throwable ignored) {
            synchronized (LOCK) {
                flushing = false;
            }
        }
    }

    private static ExecutorService executor() {
        synchronized (LOCK) {
            if (executor == null) {
                executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                    @Override public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "cigram-analytics");
                        t.setDaemon(true);
                        t.setPriority(Thread.MIN_PRIORITY);
                        return t;
                    }
                });
            }
            return executor;
        }
    }

    private static void doFlush(String url) {
        ArrayList<JSONObject> batch = new ArrayList<JSONObject>();
        try {
            synchronized (LOCK) {
                for (int i = 0; i < QUEUE.size() && i < BATCH_SIZE; i++) batch.add(QUEUE.get(i));
            }
            if (batch.isEmpty()) return;
            JSONObject body = new JSONObject();
            body.put("app", "com.Cigram.vid");
            body.put("v", versionName);
            body.put("anon", anonId);
            body.put("os", android.os.Build.VERSION.SDK_INT);
            JSONArray ev = new JSONArray();
            for (int i = 0; i < batch.size(); i++) ev.put(batch.get(i));
            body.put("events", ev);
            byte[] data = body.toString().getBytes("UTF-8");

            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-Cigram-Key", INGEST_KEY);
            c.setFixedLengthStreamingMode(data.length);
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
            int code = c.getResponseCode();
            c.disconnect();
            boolean ok = code >= 200 && code < 300;
            // 401/403/404 mean "key or URL wrong": keep the data, back off, let the breaker pause us.
            boolean drop = code >= 400 && code < 500 && code != 408 && code != 429
                    && code != 401 && code != 403 && code != 404;
            synchronized (LOCK) {
                if (ok || drop) {
                    for (int i = 0; i < batch.size(); i++) QUEUE.remove(batch.get(i));
                    persistLocked();
                    failures = 0;
                    nextAttemptAt = 0L;
                    pausedUntil = 0L;
                } else {
                    registerFailureLocked();
                }
            }
        } catch (Throwable t) {
            synchronized (LOCK) {
                registerFailureLocked();
            }
        } finally {
            synchronized (LOCK) {
                flushing = false;
            }
        }
    }

    private static void registerFailureLocked() {
        failures++;
        if (failures >= BREAKER_FAILURES) {
            pausedUntil = System.currentTimeMillis() + BREAKER_PAUSE_MS;
            try {
                if (app != null) {
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit().putLong(KEY_PAUSED_UNTIL, pausedUntil).apply();
                }
            } catch (Throwable ignored) {
            }
        }
        long back = 5000L << Math.min(failures, 8);
        if (back > MAX_BACKOFF_MS) back = MAX_BACKOFF_MS;
        nextAttemptAt = SystemClock.elapsedRealtime() + back;
    }
}
