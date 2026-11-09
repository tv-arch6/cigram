package com.Cigram.vid;

import org.json.JSONArray;

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
            String token = CigramUserData.token(c);
            CigramAds.Result r = CigramAds.request("GET", "/chat/unread", null, token);
            if (!r.ok) return;
            int unread = r.json.optInt("unread");
            long seq = r.json.optLong("last_seq");
            if (unread > 0 && seq > AdsChatStore.lastNotified(c)) {
                // 18.2: the latest developer message gives the preview, the sender's public name and the time
                JSONObject last = null;
                try {
                    CigramAds.Result m = CigramAds.request("GET", "/chat/messages?after=" + Math.max(0, seq - 5) + "&limit=5", null, token);
                    JSONArray arr = m.ok && m.json != null ? m.json.optJSONArray("messages") : null;
                    for (int i = 0; arr != null && i < arr.length(); i++) {
                        JSONObject o = arr.optJSONObject(i);
                        if (o != null && "admin".equals(o.optString("from"))) last = o;
                    }
                } catch (Throwable ignored) { }
                AdsChatStore.setLastNotified(c, seq);
                show(c, unread, last);
            }
        } catch (Throwable ignored) { }
    }

    /** A chat push (FCM, kind = chat) from the developer: same notification, opens the private conversation. */
    public static void showPush(Context c, java.util.Map<String, String> data, String title, String body) {
        try {
            JSONObject msg = new JSONObject();
            String text = body != null ? body : data == null ? "" : data.get("body");
            msg.put("text", text == null ? "" : text);
            msg.put("type", "text");
            String ts = data == null ? null : data.get("ts");
            msg.put("ts", ts == null ? System.currentTimeMillis() : Long.parseLong(ts));
            String name = data == null ? null : data.get("dev_name");
            if (name != null && name.length() > 0) msg.put("dev", new JSONObject().put("name", name));
            show(c, 1, msg);
        } catch (Throwable ignored) { }
    }

    private static String preview(JSONObject m) {
        if (m == null) return "";
        String type = m.optString("type", "text");
        if ("image".equals(type)) return "📷 صورة";
        if ("video".equals(type)) return "🎬 فيديو";
        if ("voice".equals(type)) return "🎤 رسالة صوتية";
        if ("file".equals(type)) return "📎 ملف";
        String t = m.optString("text", "").replace('\n', ' ').trim();
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }

    private static void show(Context c, int unread, JSONObject last) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "الدعم الخاص", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("رسائل مطوّر Cigram في محادثتك الخاصة");
                nm.createNotificationChannel(ch);
            }
            if (last != null && last.optJSONObject("dev") != null) CigramSupport.learn(c, last.optJSONObject("dev"));
            String who = last != null && last.optJSONObject("dev") != null && last.optJSONObject("dev").optString("name").length() > 0
                    ? last.optJSONObject("dev").optString("name") : CigramSupport.developer(c).name;
            String text = preview(last);
            if (text.length() == 0) text = unread == 1 ? "لديك رسالة جديدة" : "لديك " + unread + " رسائل جديدة";
            else if (unread > 1) text = text + "  (+" + (unread - 1) + ")";
            long when = last != null && last.optLong("ts") > 0 ? last.optLong("ts") : System.currentTimeMillis();
            Intent i = new Intent(c, Class.forName("com.Cigram.vid.AdsChatActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            i.putExtra("from_notification", true);
            int flags = Build.VERSION.SDK_INT >= 23 ? (PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE) : PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent pi = PendingIntent.getActivity(c, 0, i, flags);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
            b.setContentTitle(who)
                    .setContentText(text)
                    .setSubText(CigramSupport.TITLE + " · " + CigramSupport.time(when))
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setWhen(when)
                    .setShowWhen(true)
                    .setSmallIcon(CigramStage1InitProvider.notificationSmallIcon(c))
                    .setColor(0xFF000000)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            try {
                int logo = c.getResources().getIdentifier("cigram_logo", "drawable", c.getPackageName());
                if (logo != 0) {
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 4;
                    android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeResource(c.getResources(), logo, o);
                    if (bm != null) b.setLargeIcon(bm);
                }
            } catch (Throwable ignored) { }
            nm.notify(NOTIF_ID, b.build());
        } catch (Throwable ignored) { }
    }
}
