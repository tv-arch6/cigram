package com.Cigram.vid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

/**
 * إشعارات رسائل المحادثة بدون Firebase: مهمة JobScheduler دورية (كل 15 دقيقة، الحد الأدنى في أندرويد)
 * تسأل الخادم عن عدد غير المقروء. هذا ليس push لحظياً — للإشعار الفوري فعّل PUSH_WEBHOOK_URL مع FCM (انظر docs).
 */
public final class AdsNotifier {

    public static final int JOB_ID = 7311;
    private static final String CHANNEL = "cigram_ads_chat";
    private static final int NOTIF_ID = 7312;

    private AdsNotifier() { }

    public static void schedule(Context c) {
        try {
            if (!CigramAds.isLoggedIn(c)) return;
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
            JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(c, AdsPollJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15L * 60L * 1000L)
                    .build();
            js.schedule(info);
        } catch (Throwable ignored) { }
    }

    public static void cancelAll(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
            cancelChatNotification(c);
        } catch (Throwable ignored) { }
    }

    public static void cancelChatNotification(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Throwable ignored) { }
    }

    /** استطلاع متزامن (يُستدعى من خيط خلفي). */
    public static void pollNow(Context c) {
        try {
            if (!CigramAds.isLoggedIn(c)) return;
            CigramAds.Result r = CigramAds.request("GET", "/chat/unread", null, CigramUserData.token(c));
            if (!r.ok) return;
            int unread = r.json.optInt("unread");
            long seq = r.json.optLong("last_seq");
            if (unread > 0 && seq > AdsChatStore.lastNotified(c)) {
                AdsChatStore.setLastNotified(c, seq);
                show(c, unread);
            }
        } catch (Throwable ignored) { }
    }

    private static void show(Context c, int unread) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "رسائل الإعلانات", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent i = new Intent(c, Class.forName("com.Cigram.vid.AdsChatActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = Build.VERSION.SDK_INT >= 23 ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE) : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getActivity(c, 0, i, flags);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
            b.setContentTitle("رسالة جديدة من إدارة Cigram")
                    .setContentText(unread == 1 ? "لديك رسالة جديدة في محادثة الإعلانات" : "لديك " + unread + " رسائل جديدة في محادثة الإعلانات")
                    .setSmallIcon(CigramStage1InitProvider.notificationSmallIcon(c))
                    .setColor(0xFFE21B14)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(NOTIF_ID, b.build());
        } catch (Throwable ignored) { }
    }
}
