package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mirrors CigramUserData with the account on the server (/user/sync).
 * Local-first: every failure is swallowed, the local data is never touched on error,
 * and the next attempt is delayed (back-off). Guests never reach the network.
 */
public final class CigramSync {

    /** Deploy the Worker in /cigram-user-api under this URL (see README). */
    public static final String API_BASE = "https://cigram-user-api.wwq-mixtv.workers.dev";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final long MIN_GAP_MS = 60000L;
    private static final long DELAY_MS = 6000L;

    private static boolean scheduled = false;
    private static boolean running = false;
    private static long lastRunAt = 0L;
    private static long failures = 0L;

    private CigramSync() { }

    /** Called when the app opens (MainActivity) and after local data was flushed. */
    public static void requestSoon(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        if (!CigramUserData.isLoggedIn(app)) return;
        synchronized (CigramSync.class) {
            if (scheduled || running) return;
            long gap = MIN_GAP_MS * (1L + Math.min(failures, 5L));
            long wait = Math.max(DELAY_MS, lastRunAt + gap - System.currentTimeMillis());
            scheduled = true;
            MAIN.postDelayed(new Runnable() {
                @Override public void run() {
                    synchronized (CigramSync.class) {
                        scheduled = false;
                        running = true;
                    }
                    IO.execute(new Runnable() {
                        @Override public void run() {
                            boolean ok = false;
                            try {
                                ok = syncOnce(app);
                            } catch (Throwable ignored) {
                            } finally {
                                CigramAnalytics.track(app, ok ? "sync_success" : "sync_failed");
                                synchronized (CigramSync.class) {
                                    running = false;
                                    lastRunAt = System.currentTimeMillis();
                                    failures = ok ? 0L : failures + 1L;
                                }
                            }
                        }
                    });
                }
            }, wait);
        }
    }

    /** Shared background thread for the small network jobs of v2.3. */
    public static void runIO(Runnable job) {
        try { IO.execute(job); } catch (Throwable ignored) { }
    }

    public static void onAppOpen(Context context) {
        if (context == null) return;
        try {
            CigramUserData.adoptGuestData(context);
        } catch (Throwable ignored) { }
        requestSoon(context);
    }

    private static boolean syncOnce(final Context app) throws Exception {
        String token = CigramUserData.token(app);
        if (token.length() == 0) return false;
        String owner = CigramUserData.owner(app);
        SharedPreferences p = app.getSharedPreferences(CigramUserData.PREFS, Context.MODE_PRIVATE);
        boolean first = !p.getBoolean("first_sync::" + owner, false);

        JSONObject body = new JSONObject();
        JSONObject user = new JSONObject();
        user.put("id", CigramUserData.authUserId(app));
        user.put("email", CigramUserData.email(app));
        body.put("user", user);
        body.put("first", first);
        body.put("snapshot", CigramUserData.exportSnapshot(app));
        body.put("client_time", System.currentTimeMillis());

        String response = post(API_BASE + "/user/sync", token, body.toString());
        if (response == null) return false;
        JSONObject json = new JSONObject(response);
        if (!json.optBoolean("ok", false)) return false;
        JSONObject snapshot = json.optJSONObject("snapshot");
        if (snapshot != null) {
            final boolean changed = CigramUserData.mergeSnapshot(app, snapshot, first);
            if (changed) {
                MAIN.post(new Runnable() {
                    @Override public void run() {
                    }
                });
            }
        }
        // only a successful round-trip marks the account as synced on this device
        p.edit().putBoolean("first_sync::" + owner, true).apply();
        return true;
    }

    /** Shared tiny HTTP POST. Returns the body on 2xx, null otherwise. Never throws. */
    static String post(String url, String bearer, String json) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            if (bearer != null && bearer.length() > 0) {
                c.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            byte[] data = json.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(data.length);
            OutputStream out = c.getOutputStream();
            try { out.write(data); out.flush(); } finally { out.close(); }
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            String text = "";
            if (in != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                try {
                    while ((n = in.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                        if (bos.size() > 4 * 1024 * 1024) break;
                    }
                } finally { in.close(); }
                text = bos.toString("UTF-8");
            }
            return code >= 200 && code < 300 ? text : null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) { } }
        }
    }

    /** Shared tiny HTTP GET. Returns the body on 2xx, null otherwise. */
    static String get(String url, String bearer) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Accept", "application/json");
            if (bearer != null && bearer.length() > 0) {
                c.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 4 * 1024 * 1024) break;
                }
            } finally { in.close(); }
            return bos.toString("UTF-8");
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) { } }
        }
    }
}
