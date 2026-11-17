package com.Cigram.vid;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

/**
 * «بلّغ المدير» — a channel that does not play although the phone is online.
 *
 *  1. The viewer taps «بلّغ المدير» under "the channel is paused for now". The report goes to the Admin app's inbox
 *     (the same /report endpoint as every other report, kind {@code channel_down}, with the channel's name and the
 *     link that failed) saying the channel needs a new link. It is also remembered on this phone.
 *  2. The admin replaces the link («استبدل برابط» → حفظ) in the Admin app; the channels catalogue changes.
 *  3. The next time this phone reads the catalogue (the page, or the periodic background job) it sees that the
 *     reported channel has another link: «تم تفعيل القناة». In the background that is a notification; inside the app
 *     it is a bar at the bottom of the screen, the way Google Play announces things.
 *
 * Nothing is asked of the server beyond the report itself, so it works with the catalogue as it is published today.
 */
final class CigramChannelReports {

    private static final String PREFS = "cigram_channel_reports";
    private static final String CHANNEL_ID = "cigram_channels";
    private static final int MAX = 30;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> front = new WeakReference<Activity>(null);

    private CigramChannelReports() { }

    // ------------------------------------------------------------------ connectivity

    static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo n = cm == null ? null : cm.getActiveNetworkInfo();
            return n != null && n.isConnected();
        } catch (Throwable e) {
            return true;
        }
    }

    // ------------------------------------------------------------------ the list kept on this phone

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized JSONArray read(Context c) {
        try { return new JSONArray(sp(c).getString("items", "[]")); } catch (Throwable e) { return new JSONArray(); }
    }

    private static synchronized void write(Context c, JSONArray a) {
        sp(c).edit().putString("items", a.toString()).apply();
    }

    static boolean pending(Context c) {
        return read(c).length() > 0;
    }

    static boolean reported(Context c, String key) {
        JSONArray a = read(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && key != null && key.equals(o.optString("key"))) return true;
        }
        return false;
    }

    /** Sends the report to the Admin inbox and remembers the channel. Returns at once; the network runs behind. */
    static void report(final Activity a, final CigramChannelStore.Ch ch) {
        if (a == null || ch == null || ch.key.length() == 0) return;
        final Context app = a.getApplicationContext();
        try {
            if (!reported(app, ch.key)) {
                JSONArray list = read(app);
                JSONObject o = new JSONObject();
                o.put("key", ch.key);
                o.put("name", ch.name);
                o.put("url", ch.url);
                o.put("at", System.currentTimeMillis());
                list.put(o);
                JSONArray trimmed = new JSONArray();
                for (int i = Math.max(0, list.length() - MAX); i < list.length(); i++) trimmed.put(list.opt(i));
                write(app, trimmed);
            }
            final JSONObject r = new JSONObject();
            r.put("issue_type", "channel_down");
            r.put("issue_label", "قناة متوقفة");
            r.put("content_type", "channel");
            r.put("content_id", ch.key);
            r.put("title", ch.name);
            r.put("channel_name", ch.name);
            r.put("channel_section", ch.section);
            r.put("channel_url", ch.url);
            r.put("action", "replace_channel_url");
            r.put("message", "القناة «" + ch.name + "» متوقفة وتحتاج استبدال رابط.");
            r.put("inbox", "users");
            r.put("source", "channel_report");
            r.put("timestamp", System.currentTimeMillis());
            CigramReport.submit(app, r);
            postToAdmin(app, ch);
        } catch (Throwable error) {
            android.util.Log.w("CigramChannelReports", "report failed", error);
        }
        snack(a, "وصل بلاغك للمدير · سنخبرك عند تفعيل القناة", null, null);
    }

    private static final String ADMIN_API = "https://cigram-admin-api.wwq-mixtv.workers.dev/channel-reports";

    /**
     * The Admin app's «قنوات تحتاج رابطاً» list reads its reports from the admin worker (POST /channel-reports):
     * channel key + name + the failed link, and the anonymous install id so one phone counts once. Best effort.
     */
    private static void postToAdmin(final Context app, final CigramChannelStore.Ch ch) {
        CigramSync.runIO(new Runnable() {
            @Override public void run() {
                java.net.HttpURLConnection c = null;
                try {
                    JSONObject b = new JSONObject();
                    b.put("key", ch.key).put("name", ch.name).put("url", ch.url).put("section", ch.section);
                    b.put("client_id", CigramApi.clientId(app)).put("app_version", CigramApi.appVersion(app));
                    if (CigramUserData.isLoggedIn(app)) b.put("user_name", CigramUserData.displayName(app));
                    c = (java.net.HttpURLConnection) new java.net.URL(ADMIN_API).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    java.io.OutputStream out = c.getOutputStream();
                    try { out.write(b.toString().getBytes("UTF-8")); } finally { out.close(); }
                    c.getResponseCode();
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        });
    }

    // ------------------------------------------------------------------ the catalogue changed

    /** Called with every catalogue that is read (any thread). */
    static void check(final Context context, CigramChannelStore.Catalog catalog) {
        if (context == null || catalog == null) return;
        final Context app = context.getApplicationContext();
        try {
            JSONArray list = read(app);
            if (list.length() == 0) return;
            JSONArray keep = new JSONArray();
            final java.util.ArrayList<String> fixed = new java.util.ArrayList<String>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                CigramChannelStore.Ch now = catalog.byKey.get(o.optString("key"));
                boolean replaced = now != null && now.available && now.url.length() > 0 && !now.url.equals(o.optString("url"));
                boolean stale = System.currentTimeMillis() - o.optLong("at", 0L) > 30L * 86400000L;   // a month: forget it
                if (replaced) fixed.add(now.name);
                else if (!stale) keep.put(o);
            }
            if (keep.length() != list.length()) write(app, keep);
            if (fixed.isEmpty()) return;
            MAIN.post(new Runnable() {
                @Override public void run() { announce(app, fixed); }
            });
        } catch (Throwable error) {
            android.util.Log.w("CigramChannelReports", "check failed", error);
        }
    }

    private static void announce(Context app, java.util.ArrayList<String> names) {
        // 19.5: «تم تفعيل القناة #اسم_القناة» (spaces in the name become _ so the tag reads as one word)
        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < names.size() && i < 3; i++) {
            if (i > 0) tags.append(' ');
            tags.append('#').append(String.valueOf(names.get(i)).trim().replaceAll("\\s+", "_"));
        }
        if (names.size() > 3) tags.append(" و").append(names.size() - 3).append(" غيرها");
        final String detail = tags.toString();
        final Activity a = front.get();
        if (a != null && !a.isFinishing() && !a.isDestroyed()) {
            snack(a, "تم تفعيل القناة " + detail, "مشاهدة", new Runnable() {
                @Override public void run() {
                    try { a.startActivity(new Intent(a, ChannelsActivity.class)); } catch (Throwable ignored) { }
                }
            });
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "القنوات", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("تفعيل قناة بلّغت عنها");
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(app, ChannelsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(app, 7120, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(app, CHANNEL_ID) : new Notification.Builder(app);
            int icon = app.getResources().getIdentifier("ic_stat_cigram", "drawable", app.getPackageName());
            b.setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_notify_more)
                    .setContentTitle("تم تفعيل القناة " + detail)
                    .setContentText("رجع البث برابط جديد · اضغط للمشاهدة")
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(7120, b.build());
        } catch (Throwable error) {
            android.util.Log.w("CigramChannelReports", "notify failed", error);
        }
    }

    // ------------------------------------------------------------------ which page is in front

    static void resumed(Activity a) { front = new WeakReference<Activity>(a); }

    static void paused(Activity a) { if (front.get() == a) front = new WeakReference<Activity>(null); }

    // ------------------------------------------------------------------ the bar at the bottom (Google Play style)

    private static final String TAG_SNACK = "cigram_snack";

    /** A dark rounded bar that slides up from the bottom edge, stays a few seconds and slides away. */
    static void snack(final Activity a, String text, String action, final Runnable onAction) {
        try {
            if (a == null || a.isFinishing() || a.isDestroyed()) return;
            View content = a.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return;
            final FrameLayout root = (FrameLayout) content;
            View old = root.findViewWithTag(TAG_SNACK);
            if (old != null) root.removeView(old);
            float d = a.getResources().getDisplayMetrics().density;

            final LinearLayout bar = new LinearLayout(a);
            bar.setTag(TAG_SNACK);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding((int) (16 * d), 0, (int) (16 * d), 0);
            bar.setMinimumHeight((int) (50 * d));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF2202124);
            bg.setCornerRadius(14 * d);
            bg.setStroke(Math.max(1, (int) d), 0x26FFFFFF);
            bar.setBackground(bg);
            bar.setElevation(10 * d);
            final Runnable close = new Runnable() {
                @Override public void run() {
                    bar.animate().translationY(bar.getHeight() + 60 * a.getResources().getDisplayMetrics().density).alpha(0f).setDuration(220L)
                            .withEndAction(new Runnable() {
                                @Override public void run() {
                                    if (bar.getParent() instanceof ViewGroup) ((ViewGroup) bar.getParent()).removeView(bar);
                                }
                            }).start();
                }
            };
            if (action != null && onAction != null) {
                TextView act = CigramUI.label(a, action, 14f, 0xFFFFFFFF, true);
                act.setPadding((int) (6 * d), (int) (10 * d), (int) (14 * d), (int) (10 * d));
                act.setClickable(true);
                act.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { close.run(); onAction.run(); }
                });
                bar.addView(act);
            }
            TextView t = CigramUI.label(a, text, 13.5f, 0xFFFFFFFF, false);
            t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            t.setMaxLines(2);
            bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams((int) (20 * d), (int) (20 * d));
            il.leftMargin = (int) (10 * d);
            bar.addView(new AdsIcons.Icon(a, AdsIcons.CHECK_CIRCLE, 0xFFFFFFFF), il);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            lp.setMargins((int) (14 * d), 0, (int) (14 * d), (int) (86 * d));       // above the bottom navigation bar
            root.addView(bar, lp);
            bar.setTranslationY(120 * d);
            bar.setAlpha(0f);
            bar.animate().translationY(0f).alpha(1f).setDuration(240L).start();
            bar.postDelayed(close, action != null ? 6500L : 3800L);
        } catch (Throwable error) {
            android.util.Log.w("CigramChannelReports", "snack failed", error);
        }
    }
}
