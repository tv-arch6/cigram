package com.my.newproject;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 2026: the admin learns about a new advertising request / support message even when the app is closed.
 *
 * A periodic job (every 15 minutes, any network, not persisted across reboots so no boot permission is needed; the
 * app schedules it again whenever it is opened) reads the unread threads of the ads worker
 * ({@code GET /admin/chat/threads?unread=1}) and posts one notification per thread whose newest message from the
 * customer is newer than the last one already notified. The first run only records the current state (no flood of
 * old messages). A thread is an advertising request when its preview / tags / last type say so (the order card that
 * «أريد إعلاناً» in the main app sends is previewed as «طلب إعلان»).
 */
final class AdInboxNotifier {

    static final String CHANNEL = "cigram_ads_inbox";
    private static final String PREFS = "cigram_ads_inbox_notify";
    private static final int JOB_ID = 7301;

    private AdInboxNotifier() { }

    static boolean isAdRequest(JSONObject t) {
        if (t == null) return false;
        if ("order".equals(t.optString("last_type")) || t.optBoolean("has_order") || t.optBoolean("ad_request")) return true;
        String p = t.optString("preview", "");
        if (p.contains("طلب إعلان") || p.contains("📝")) return true;
        JSONArray tags = t.optJSONArray("tags");
        for (int i = 0; tags != null && i < tags.length(); i++) {
            String tag = tags.optString(i).toLowerCase(Locale.ROOT);
            if (tag.contains("إعلان") || tag.contains("ads") || tag.contains("order")) return true;
        }
        return false;
    }

    /** Idempotent: schedules the poll job if it is not scheduled yet. */
    static void ensureScheduled(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
            JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(c, Job.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15L * 60L * 1000L)
                    .setPersisted(false)
                    .build();
            js.schedule(info);
        } catch (Throwable ignored) { }
    }

    /** Asks once (Android 13+) for the notification permission, from the inbox screen, i.e. when it is useful. */
    static void askPermissionOnce(Activity a) {
        try {
            if (Build.VERSION.SDK_INT < 33) return;
            if (a.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) return;
            SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (p.getBoolean("asked", false)) return;
            p.edit().putBoolean("asked", true).apply();
            a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7302);
        } catch (Throwable ignored) { }
    }

    /** Pure helper (JVM-tested): the remembered ids after seeing {@code id}; {@code null} when it was already seen. */
    static java.util.List<String> remember(java.util.List<String> seen, String id, int cap) {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>(seen == null ? java.util.Collections.<String>emptyList() : seen);
        if (id == null || id.length() == 0) return out;
        if (out.contains(id)) return null;
        out.add(id);
        while (out.size() > cap) out.remove(0);
        return out;
    }

    private static boolean firstTime(Context c, String id) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS + "_ids", Context.MODE_PRIVATE);
            java.util.ArrayList<String> seen = new java.util.ArrayList<String>();
            for (String x : p.getString("ids", "").split(",")) if (x.length() > 0) seen.add(x);
            java.util.List<String> next = remember(seen, id, 60);
            if (next == null) return false;
            StringBuilder sb = new StringBuilder();
            for (String x : next) { if (sb.length() > 0) sb.append(','); sb.append(x); }
            p.edit().putString("ids", sb.toString()).apply();
        } catch (Throwable ignored) { }
        return true;
    }

    private static long num(String v) {
        try { return v == null ? 0L : Long.parseLong(v.trim()); } catch (Throwable ignored) { return 0L; }
    }

    /**
     * 19.0: a push (FCM data message) for a new user message, shown even when the app is closed: the user's name, a preview of
     * the message, the unread count and the message time; a tap opens that conversation. Deduplicated by message id, and by the
     * notification id (one per conversation) against the 15-minute poll. Silent while that very conversation is on screen.
     */
    static void showPush(Context c, java.util.Map<String, String> d) {
        try {
            if (d == null) return;
            String thread = d.get("thread");
            if (thread == null || thread.length() == 0) return;
            if (!firstTime(c, d.get("msg_id"))) return;
            if (AdChatActivity.isOpen(thread)) return;
            JSONObject t = new JSONObject();
            t.put("id", thread);
            t.put("name", d.get("name") == null || d.get("name").length() == 0 ? "عميل" : d.get("name"));
            t.put("preview", d.get("body") == null ? "" : d.get("body"));
            t.put("unread_admin", (int) Math.max(1L, num(d.get("unread"))));
            long ts = num(d.get("ts"));
            t.put("last_ts", ts > 0 ? ts : System.currentTimeMillis());
            notify(c, t);
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (t.optLong("last_ts") > p.getLong("last_ts", 0L)) p.edit().putLong("last_ts", t.optLong("last_ts")).apply();
        } catch (Throwable ignored) { }
    }

    /** One check (background thread only). */
    static void check(Context c) {
        CgCfg.loadToken(c);
        if (CgCfg.TOKEN.length() == 0) return;
        AdApi.Result r = AdApi.request("GET", "/admin/chat/threads?limit=30&unread=1", null, null);
        if (!r.ok) return;
        JSONArray a = r.json.optJSONArray("threads");
        if (a == null) return;
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long seen = p.getLong("last_ts", 0L);
        long newest = seen;
        boolean firstRun = seen == 0L;
        for (int i = 0; i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t == null) continue;
            long ts = t.optLong("last_ts");
            if (ts > newest) newest = ts;
            if (firstRun || ts <= seen) continue;
            if (t.optInt("unread_admin") <= 0 || !"user".equals(t.optString("last_from", "user"))) continue;
            notify(c, t);
        }
        if (newest != seen) p.edit().putLong("last_ts", newest).apply();
    }

    private static void notify(Context c, JSONObject t) {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "رسائل الإعلانات والدعم", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("طلبات الإعلان الجديدة ورسائل العملاء");
                nm.createNotificationChannel(ch);
            }
            boolean ad = isAdRequest(t);
            String name = t.optString("name", "عميل");
            String title = ad ? "طلب إعلان جديد · Cigram" : "رسالة جديدة · Cigram";
            String text = name + ": " + t.optString("preview", "");
            Intent open = new Intent(c, AdChatActivity.class);
            open.putExtra("thread", t.optString("id"));
            open.putExtra("name", name);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getActivity(c, t.optString("id").hashCode(), open, flags);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
            int icon = c.getResources().getIdentifier("ic_stat_cigram", "drawable", c.getPackageName());
            b.setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_notify_chat)
                    .setColor(0xFFE21B14)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setWhen(t.optLong("last_ts") > 0 ? t.optLong("last_ts") : System.currentTimeMillis())
                    .setShowWhen(true)
                    .setCategory(Notification.CATEGORY_MESSAGE);
            if (t.optInt("unread_admin") > 1) b.setSubText(t.optInt("unread_admin") + " رسائل غير مقروءة");
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
            nm.notify("ads_inbox", t.optString("id").hashCode(), b.build());
        } catch (Throwable ignored) { }
    }

    /** The periodic job itself. */
    public static final class Job extends JobService {
        @Override public boolean onStartJob(final JobParameters params) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try { check(getApplicationContext()); } catch (Throwable ignored) { }
                    jobFinished(params, false);
                }
            }, "ads-inbox-poll").start();
            return true;
        }

        @Override public boolean onStopJob(JobParameters params) { return true; }
    }
}
