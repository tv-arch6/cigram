package com.Cigram.vid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** 19.6 — see {@link CigramKeepAlive}. A silent foreground service that keeps Cigram alive and its catalogue fresh. */
public class CigramKeepAliveService extends Service {

    static final String CHANNEL = "cigram_background";
    static final int NOTIFICATION_ID = 7462;
    static final String ACTION_STOP = "com.Cigram.vid.KEEPALIVE_STOP";
    private static final long BACKGROUND_PROBE_MS = 60000L;

    static volatile boolean running;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable beat = new Runnable() {
        @Override public void run() {
            handler.removeCallbacks(this);
            if (!running) return;
            try { CigramCatalogPulse.probe(getApplicationContext(), "background"); } catch (Throwable ignored) { }
            handler.postDelayed(this, Math.max(BACKGROUND_PROBE_MS, CigramPerfConfig.pulseMs(getApplicationContext(), 20000L) * 3));
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences(CigramKeepAlive.PREFS, MODE_PRIVATE).edit().putBoolean(CigramKeepAlive.KEY_ENABLED, false).apply();
            running = false;
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Notification n = build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            running = true;
            handler.removeCallbacks(beat);
            handler.postDelayed(beat, BACKGROUND_PROBE_MS);
        } catch (Throwable error) {
            android.util.Log.w("CigramKeepAlive", "foreground refused", error);
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // swiped away from the recent apps: the service (and with it the process) stays, as the viewer asked
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacks(beat);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Throwable ignored) { }
    }

    private Notification build() {
        Context c = getApplicationContext();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "التشغيل في الخلفية", NotificationManager.IMPORTANCE_MIN);
                ch.setDescription("يبقي سيكرام جاهزاً ويوصلك المحتوى الجديد فوراً");
                ch.setShowBadge(false);
                ch.enableVibration(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent open = null;
        try {
            Intent launch = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                open = PendingIntent.getActivity(c, 7462, launch, piFlags);
            }
        } catch (Throwable ignored) { }
        PendingIntent stop = PendingIntent.getService(c, 7463,
                new Intent(c, CigramKeepAliveService.class).setAction(ACTION_STOP), piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        int icon = CigramStage1InitProvider.notificationSmallIcon(c);
        if (icon == 0) icon = android.R.drawable.stat_notify_sync_noanim;
        b.setSmallIcon(icon)
                .setContentTitle("سيكرام يعمل في الخلفية")
                .setContentText("المحتوى الجديد يصلك فوراً وترجع للتطبيق بدون انتظار")
                .setOngoing(true)
                .setShowWhen(false)
                .setColor(0xFFE21B14);
        if (open != null) b.setContentIntent(open);
        if (Build.VERSION.SDK_INT >= 20) {
            b.addAction(new Notification.Action.Builder(0, "إيقاف", stop).build());
        }
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MIN);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED);
        return b.build();
    }
}
