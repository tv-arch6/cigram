package com.Cigram.vid;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * v10.67 — every push from the admin app reaches the phone, also while Cigram is closed.
 *
 *   kind new_title    -> «فيلم / مسلسل جديد» (existing stage-6 code: alert + notifications page)
 *   kind new_episode  -> checks the subscribed series right now and alerts their viewers
 *   kind announcement -> the admin's message: phone notification + a row on the notifications page
 *   type app_update   -> «تحديث جديد» notification
 *
 * The Worker sends new_title / new_episode as HIGH-priority data messages (the app builds the
 * notification) and announcements / updates as notification + data (Android shows it by itself
 * while the app is closed; the notifications page then picks it up from /announcements).
 */
public final class CigramPush {

    public static final String CHANNEL_GENERAL = "cigram_general";
    private static final String ANN_URL = "https://cigram-admin-api.wwq-mixtv.workers.dev/announcements";
    private static final String PREF = "cigram_push";
    private static final String KEY_ANN_SYNC_AT = "ann_sync_at";
    private static final String KEY_SHOWN = "shown_ids";
    private static final long ANN_SYNC_MIN_MS = 10L * 60L * 1000L;
    private static final int ACCENT = 0xFFE21B14;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean SYNC_RUNNING;

    private CigramPush() {
    }

    // ------------------------------------------------------------------ channels

    /** Channels with sound + pop-up (HIGH). Safe to call any number of times. */
    public static void createChannels(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_GENERAL) == null) {
                NotificationChannel c = new NotificationChannel(CHANNEL_GENERAL, "إشعارات سيكرام",
                        NotificationManager.IMPORTANCE_HIGH);
                c.setDescription("رسائل إدارة سيكرام والتحديثات");
                c.enableVibration(true);
                c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(c);
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramPush", "channel failed", error);
        }
    }

    // ------------------------------------------------------------------ incoming push

    /**
     * Called by CigramFcmService on its background thread. Blocks (at most ~20 s) until the work is
     * done, so Android does not stop the process while the app is closed.
     */
    public static void onMessage(Context context, Map<String, String> data, String nTitle, String nBody) {
        if (context == null) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        createChannels(app);
        String kind = data == null ? "" : clean(data.get("kind"));
        String type = data == null ? "" : clean(data.get("type"));

        if (kind.startsWith("new_episode")) {
            checkSubscriptionsAndWait(app, 20);
            return;
        }
        if (kind.startsWith("new_title")) {
            CigramStage1InitProvider.stage6OnPush(app, data);
            if ("series".equals(data.get("content_type"))) checkSubscriptionsAndWait(app, 15);
            try { CigramRequests.refresh(app, true, null); } catch (Throwable ignored) { }
            return;
        }
        if ("chat".equals(kind)) {
            // 18.2: a developer message in the viewer's private conversation (no internal id is shown)
            AdsNotifier.showPush(app, data, nTitle, nBody);
            return;
        }
        if ("app_update".equals(type) || "app_update".equals(kind)) {
            showUpdate(app, data, nTitle, nBody);
            return;
        }
        // announcement / general message (also anything unknown that carries a title or body)
        showAnnouncement(app, data, nTitle, nBody);
    }

    private static void checkSubscriptionsAndWait(Context app, int seconds) {
        final CountDownLatch done = new CountDownLatch(1);
        try {
            CigramStage1InitProvider.runStage6UserRefresh(app, new Runnable() {
                @Override public void run() {
                    done.countDown();
                }
            });
            done.await(seconds, TimeUnit.SECONDS);
        } catch (Throwable error) {
            android.util.Log.w("CigramPush", "subscription check failed", error);
        }
    }

    private static void showAnnouncement(Context app, Map<String, String> data, String nTitle, String nBody) {
        String title = first(data == null ? null : data.get("title"), nTitle);
        String body = first(data == null ? null : data.get("body"), nBody);
        if (title.length() == 0 && body.length() == 0) return;
        if (title.length() == 0) title = "سيكرام";
        String id = data == null ? "" : first(data.get("announcement_id"), data.get("id"));
        if (id.length() == 0) id = "push_" + Math.abs((title + "|" + body).hashCode());
        String level = data == null ? "info" : first(data.get("level"), "info");
        boolean test = data != null && "1".equals(data.get("test"));

        if (!test) {
            CigramStage1InitProvider.recordInboxAnnouncement(app, id, title, body, level, System.currentTimeMillis());
        }
        if (wasShown(app, id)) return;
        remember(app, id);
        notify(app, Math.abs(("ann|" + id).hashCode()) % 100000 + 671000, title, body, notificationsPageIntent(app));
    }

    private static void showUpdate(Context app, Map<String, String> data, String nTitle, String nBody) {
        String title = first(data == null ? null : data.get("title"), nTitle);
        String body = first(data == null ? null : data.get("body"), nBody);
        if (title.length() == 0) title = "تحديث جديد لسيكرام";
        if (body.length() == 0) body = "إصدار جديد من التطبيق أصبح متوفراً الآن.";
        String id = data == null ? "" : first(data.get("id"), "upd_" + clean(data.get("versionCode")));
        if (id.length() == 0) id = "upd";
        if (wasShown(app, id)) return;
        remember(app, id);
        PendingIntent pi = null;
        String url = data == null ? "" : clean(data.get("url"));
        try {
            if (url.startsWith("https://") || url.startsWith("http://")) {
                Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                pi = PendingIntent.getActivity(app, 671999, view, piFlags());
            }
        } catch (Throwable ignored) { }
        if (pi == null) pi = launcherIntent(app, 671998, false);
        notify(app, 671999, title, body, pi);
    }

    private static void notify(Context app, int id, String title, String body, PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && app.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return;
            }
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(app, CHANNEL_GENERAL)
                    : new Notification.Builder(app);
            b.setSmallIcon(CigramStage1InitProvider.notificationSmallIcon(app))
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setColor(ACCENT)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true);
            if (pi != null) b.setContentIntent(pi);
            if (Build.VERSION.SDK_INT < 26) {
                b.setDefaults(Notification.DEFAULT_ALL);
                b.setPriority(Notification.PRIORITY_HIGH);
            }
            if (Build.VERSION.SDK_INT >= 21) b.setVisibility(Notification.VISIBILITY_PUBLIC);
            nm.notify(id, b.build());
        } catch (Throwable error) {
            android.util.Log.w("CigramPush", "notify failed", error);
        }
    }

    // ------------------------------------------------------------------ tap targets

    private static int piFlags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }

    private static PendingIntent launcherIntent(Context app, int code, boolean openNotifications) {
        try {
            Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            if (launch == null) return null;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (openNotifications) launch.putExtra("cg_open", "notifications");
            return PendingIntent.getActivity(app, code, launch, piFlags());
        } catch (Throwable error) {
            return null;
        }
    }

    /** Home underneath, the notifications page on top: «رجوع» lands on Home. */
    private static PendingIntent notificationsPageIntent(Context app) {
        try {
            Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
            Class<?> page = Class.forName(app.getPackageName() + ".NotificationsActivity");
            if (launch == null) return launcherIntent(app, 671997, true);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent open = new Intent(app, page);
            open.putExtra("open_from_notification", "1");
            return PendingIntent.getActivities(app, 671996, new Intent[]{launch, open}, piFlags());
        } catch (Throwable error) {
            return launcherIntent(app, 671997, true);
        }
    }

    /**
     * Home opened by tapping a notification that Android showed by itself (app was closed):
     * the push data arrive as extras. An admin message opens the notifications page.
     */
    public static void handleLaunchIntent(final Activity activity) {
        if (activity == null) return;
        try {
            Intent intent = activity.getIntent();
            if (intent == null || intent.getBooleanExtra("cg_push_handled", false)) return;
            String open = clean(intent.getStringExtra("cg_open"));
            String kind = clean(intent.getStringExtra("kind"));
            String type = clean(intent.getStringExtra("type"));
            boolean fromPush = intent.hasExtra("google.message_id") || intent.hasExtra("from");
            boolean wantsPage = "notifications".equals(open)
                    || (fromPush && ("announcement".equals(kind) || "announcement".equals(type)));
            if (!wantsPage) return;
            intent.putExtra("cg_push_handled", true);
            if (fromPush) {
                // record it on the notifications page right away (the app was closed when it arrived)
                String id = first(intent.getStringExtra("announcement_id"), intent.getStringExtra("id"));
                String title = clean(intent.getStringExtra("title"));
                String body = clean(intent.getStringExtra("body"));
                if (!"1".equals(intent.getStringExtra("test")) && (title.length() > 0 || body.length() > 0)) {
                    if (id.length() == 0) id = "push_" + Math.abs((title + "|" + body).hashCode());
                    CigramStage1InitProvider.recordInboxAnnouncement(activity, id,
                            title.length() > 0 ? title : "سيكرام", body,
                            first(intent.getStringExtra("level"), "info"), System.currentTimeMillis());
                }
            }
            MAIN.postDelayed(new Runnable() {
                @Override public void run() {
                    try {
                        if (activity.isFinishing()) return;
                        Class<?> page = Class.forName(activity.getPackageName() + ".NotificationsActivity");
                        activity.startActivity(new Intent(activity, page));
                        activity.overridePendingTransition(0, 0);
                    } catch (Throwable error) {
                        android.util.Log.w("CigramPush", "open notifications failed", error);
                    }
                }
            }, 700L);
        } catch (Throwable error) {
            android.util.Log.w("CigramPush", "launch intent failed", error);
        }
    }

    // ------------------------------------------------------------------ admin messages -> notifications page

    /**
     * Copies the admin's active messages (/announcements) into the notifications page, so a message
     * shows there even when its push was displayed by Android while the app was closed (or missed).
     * done (optional) runs on the main thread with true when something new was added.
     */
    public static void syncAnnouncements(Context context, boolean force, final ResultCallback done) {
        if (context == null) {
            finish(done, false);
            return;
        }
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final SharedPreferences prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!force && now - prefs.getLong(KEY_ANN_SYNC_AT, 0L) < ANN_SYNC_MIN_MS) {
            finish(done, false);
            return;
        }
        if (SYNC_RUNNING) {
            finish(done, false);
            return;
        }
        SYNC_RUNNING = true;
        new Thread(new Runnable() {
            @Override public void run() {
                boolean added = false;
                try {
                    String raw = httpGet(ANN_URL);
                    if (raw != null) {
                        JSONObject root = new JSONObject(raw);
                        JSONArray items = root.optJSONArray("items");
                        if (items == null) {
                            JSONObject data = root.optJSONObject("data");
                            if (data != null) items = data.optJSONArray("items");
                        }
                        for (int i = 0; items != null && i < items.length(); i++) {
                            JSONObject a = items.optJSONObject(i);
                            if (a == null || a.optBoolean("enabled", true) == false) continue;
                            String id = clean(a.optString("id", ""));
                            String title = clean(a.optString("title", ""));
                            String body = clean(a.optString("body", ""));
                            if (id.length() == 0 || (title.length() == 0 && body.length() == 0)) continue;
                            long at = parseIso(a.optString("created_at", ""));
                            if (at <= 0L) at = System.currentTimeMillis();
                            if (CigramStage1InitProvider.recordInboxAnnouncement(app, id,
                                    title.length() > 0 ? title : "سيكرام", body,
                                    a.optString("kind", "info"), at)) {
                                added = true;
                            }
                        }
                        prefs.edit().putLong(KEY_ANN_SYNC_AT, System.currentTimeMillis()).apply();
                    }
                } catch (Throwable error) {
                    android.util.Log.w("CigramPush", "announcement sync failed", error);
                } finally {
                    SYNC_RUNNING = false;
                    finish(done, added);
                }
            }
        }, "cigram-ann-sync").start();
    }

    public interface ResultCallback {
        void done(boolean changed);
    }

    private static void finish(final ResultCallback done, final boolean changed) {
        if (done == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                try { done.done(changed); } catch (Throwable ignored) { }
            }
        });
    }

    private static String httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(12000);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "Cigram-Android/1.0");
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > 512 * 1024) break;
                out.write(buf, 0, n);
            }
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable error) {
            return null;
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    private static long parseIso(String iso) {
        if (iso == null || iso.length() < 19) return 0L;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Date d = f.parse(iso.substring(0, 19));
            return d == null ? 0L : d.getTime();
        } catch (Throwable error) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ duplicate guard

    private static synchronized boolean wasShown(Context app, String id) {
        try {
            JSONArray arr = new JSONArray(app.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_SHOWN, "[]"));
            for (int i = 0; i < arr.length(); i++) if (id.equals(arr.optString(i))) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    private static synchronized void remember(Context app, String id) {
        try {
            SharedPreferences p = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray old = new JSONArray(p.getString(KEY_SHOWN, "[]"));
            JSONArray out = new JSONArray();
            out.put(id);
            for (int i = 0; i < old.length() && out.length() < 80; i++) {
                String v = old.optString(i);
                if (v.length() > 0 && !v.equals(id)) out.put(v);
            }
            p.edit().putString(KEY_SHOWN, out.toString()).apply();
        } catch (Throwable ignored) { }
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private static String first(String a, String b) {
        String x = clean(a);
        return x.length() > 0 ? x : clean(b);
    }
}
