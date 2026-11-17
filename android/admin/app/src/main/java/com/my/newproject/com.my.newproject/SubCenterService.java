package com.my.newproject;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.Cigram.subhub.SubQueue;

/**
 * Keeps the Arabic Subtitle Center batch alive while the admin uses other screens (foreground service, data sync),
 * with a progress notification and a «إيقاف مؤقت» action. Stops itself when the batch is paused / done / cancelled.
 */
public class SubCenterService extends Service {

    static final int NOTIF = 7741;
    private static final String CHANNEL = "cigram_subcenter";
    private static final String ACTION_PAUSE = "com.my.newproject.SUBCENTER_PAUSE";

    static void start(Context c) {
        Intent i = new Intent(c, SubCenterService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable e) {
            // background start not allowed: the batch still runs while the Center is open
            SubCenterRunner.get(c).start();
        }
    }

    static void refresh(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (!SubCenterRunner.get(c).isRunning()) {
                nm.cancel(NOTIF);
                c.stopService(new Intent(c, SubCenterService.class));
                return;
            }
            nm.notify(NOTIF, build(c));
        } catch (Throwable ignored) { }
    }

    private static Notification build(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "مركز الترجمة العربية", NotificationManager.IMPORTANCE_LOW));
        }
        SubQueue q = SubCenterStore.queue(c);
        int size = Math.max(1, q.batch.size), done = Math.min(q.batch.done, size);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, SubCenterActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags);
        PendingIntent pause = PendingIntent.getService(c, 1, new Intent(c, SubCenterService.class).setAction(ACTION_PAUSE), flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        int icon = c.getResources().getIdentifier("ic_stat_cigram", "drawable", c.getPackageName());
        b.setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_sys_download)
                .setContentTitle("البحث عن الترجمة العربية")
                .setContentText(done + " من " + size + " · " + SubCenterRunner.get(c).status())
                .setProgress(size, done, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(0, "إيقاف مؤقت", pause).build());
        return b.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_PAUSE.equals(intent.getAction())) {
            SubCenterRunner.get(this).pause();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, build(this), 1 /* FOREGROUND_SERVICE_TYPE_DATA_SYNC */);
            else startForeground(NOTIF, build(this));
        } catch (Throwable e) {
            stopSelf();
            SubCenterRunner.get(this).start();
            return START_NOT_STICKY;
        }
        SubCenterRunner.get(this).start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
