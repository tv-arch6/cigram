package com.Cigram.vid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * v5.24: keeps TV watching alive while the app is in the background.
 *
 * - Foreground service (mediaPlayback) with a "متصل بالتلفاز" notification and a "قطع الاتصال" action,
 *   so Xiaomi / Huawei / Honor / Oppo / Realme / Infinix / Samsung battery managers do not kill the
 *   process that follows the DLNA playback, records progress and ends the session.
 * - Wi-Fi lock (low latency on Android 10+, high performance before) + multicast lock: the Wi-Fi
 *   radio stays fully awake, so the TV control requests and position polling never stall.
 * - Partial wake lock: the CPU keeps running the polling thread with the screen off.
 *
 * For Chromecast the Cast SDK already has its own media notification, so only the locks are held
 * (no second notification).
 */
public class CigramCastKeepAlive extends Service {

    private static final String TAG = "CigramCastKeepAlive";
    private static final String CHANNEL = "cigram_tv_cast";
    private static final int NOTIFICATION_ID = 7461;
    private static final String ACTION_STOP = "com.Cigram.vid.CAST_KEEPALIVE_STOP";
    private static final String EXTRA_TITLE = "title";
    /** Safety net: never hold the locks longer than this without a refresh. */
    private static final long MAX_HOLD_MS = 6L * 60L * 60L * 1000L;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static WifiManager.WifiLock wifiLock;
    private static WifiManager.MulticastLock multicastLock;
    private static PowerManager.WakeLock wakeLock;

    // ------------------------------------------------------------------ public API

    /**
     * @param withService true for DLNA (the app follows the playback itself), false for Chromecast
     *                    (the Cast SDK notification already keeps the app alive)
     */
    public static void start(Context context, String title, boolean withService) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        acquireLocks(app);
        if (!withService) return;
        try {
            Intent i = new Intent(app, CigramCastKeepAlive.class);
            i.putExtra(EXTRA_TITLE, title == null ? "" : title);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
            else app.startService(i);
        } catch (Throwable error) {
            // Android 12+: not allowed from the background. The locks are still held.
            Log.w(TAG, "start service failed", error);
        }
    }

    public static void stop(Context context) {
        releaseLocks();
        if (context == null) return;
        try {
            context.getApplicationContext().stopService(
                    new Intent(context.getApplicationContext(), CigramCastKeepAlive.class));
        } catch (Throwable error) {
            Log.w(TAG, "stop service failed", error);
        }
    }

    // ------------------------------------------------------------------ locks

    @SuppressWarnings("deprecation")
    private static synchronized void acquireLocks(Context app) {
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                if (wifiLock == null) {
                    int mode = Build.VERSION.SDK_INT >= 29
                            ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                            : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                    wifiLock = wm.createWifiLock(mode, "cigram:tv-cast");
                    wifiLock.setReferenceCounted(false);
                }
                if (!wifiLock.isHeld()) wifiLock.acquire();
                if (multicastLock == null) {
                    multicastLock = wm.createMulticastLock("cigram:tv-cast-mc");
                    multicastLock.setReferenceCounted(false);
                }
                if (!multicastLock.isHeld()) multicastLock.acquire();
            }
        } catch (Throwable error) {
            Log.w(TAG, "wifi lock failed", error);
        }
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cigram:tv-cast");
                    wakeLock.setReferenceCounted(false);
                }
                wakeLock.acquire(MAX_HOLD_MS);
            }
        } catch (Throwable error) {
            Log.w(TAG, "wake lock failed", error);
        }
    }

    private static synchronized void releaseLocks() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable ignored) { }
        try { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); } catch (Throwable ignored) { }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ service

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            UI.post(new Runnable() {
                @Override public void run() { CigramCastDetails.disconnectFromNotification(); }
            });
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        String title = intent == null ? "" : intent.getStringExtra(EXTRA_TITLE);
        try {
            Notification n = build(title);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable error) {
            Log.w(TAG, "startForeground failed", error);
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        acquireLocks(getApplicationContext());
        // the process was restarted without an active TV session: nothing to keep alive
        return START_NOT_STICKY;
    }

    private void stopSelfSafely() {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Throwable ignored) { }
        try { stopSelf(); } catch (Throwable ignored) { }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // the app was swiped away from recents: keep watching on the TV, the service stays
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification build(String title) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "البث إلى التلفاز",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = null;
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launch != null) {
                launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                open = PendingIntent.getActivity(this, 1, launch, piFlags);
            }
        } catch (Throwable ignored) { }
        Intent stopIntent = new Intent(this, CigramCastKeepAlive.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 2, stopIntent, piFlags);

        int icon = getResources().getIdentifier("ic_stat_cigram", "drawable", getPackageName());
        if (icon == 0) icon = android.R.drawable.ic_media_play;

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(icon)
                .setContentTitle("متصل بالتلفاز")
                .setContentText(title != null && title.length() > 0
                        ? "يُعرض الآن: " + title
                        : "يستمر العرض على التلفاز أثناء استخدام الهاتف")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "قطع الاتصال", stop).build());
        if (open != null) b.setContentIntent(open);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }
}
