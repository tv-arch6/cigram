package com.Cigram.vid;

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

import java.util.ArrayList;

/**
 * v10.62: «طلباتي» — طلبات الأفلام والمسلسلات والقنوات وحالتها.
 *
 * محلي أولاً: كل طلب يُحفظ على الجهاز فوراً، ثم يُرسل للخادم (POST /requests) إن أمكن.
 * الحالات: pending (قيد المراجعة) · in_progress (قيد العمل) · added (تمت الإضافة) · unavailable (غير متوفر).
 * الحالة تأتي من لوحة الإدارة (GET /requests/mine)، أو تلقائياً: إذا ظهر في الكتالوج عنوان بنفس
 * الاسم والنوع نعتبره «تمت الإضافة» ونرسل إشعاراً يفتح صفحة العنوان مباشرة.
 * أي فشل في الشبكة لا يغيّر شيئاً على الجهاز.
 */
public final class CigramRequests {

    public static final String MOVIE = "movie", SERIES = "series", CHANNEL = "channel";
    public static final String PENDING = "pending", IN_PROGRESS = "in_progress", ADDED = "added", UNAVAILABLE = "unavailable";

    private static final String PREFS = "cigram_requests_v1";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_LAST_REFRESH = "last_refresh";
    private static final String CHANNEL_ID = "cigram_requests";
    private static final long MIN_REFRESH_MS = 10L * 60L * 1000L;
    private static final int MAX_ITEMS = 60;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static volatile boolean refreshing;

    private CigramRequests() { }

    // ------------------------------------------------------------------ labels

    public static String kindLabel(String kind) {
        if (SERIES.equals(kind)) return "مسلسل";
        if (CHANNEL.equals(kind)) return "قناة";
        return "فيلم";
    }

    public static String statusLabel(String status) {
        if (IN_PROGRESS.equals(status)) return "قيد العمل";
        if (ADDED.equals(status)) return "تمت الإضافة";
        if (UNAVAILABLE.equals(status)) return "غير متوفر";
        return "قيد المراجعة";
    }

    public static int statusColor(String status) {
        if (IN_PROGRESS.equals(status)) return CigramUI.TEAL;
        if (ADDED.equals(status)) return CigramUI.GREEN;
        if (UNAVAILABLE.equals(status)) return 0xFFFF7A7A;
        return CigramUI.AMBER;
    }

    // ------------------------------------------------------------------ store

    private static SharedPreferences prefs(Context c) {
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONArray read(Context c) {
        try {
            return new JSONArray(prefs(c).getString(KEY_ITEMS, "[]"));
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }

    private static void write(Context c, JSONArray items) {
        prefs(c).edit().putString(KEY_ITEMS, items.toString()).apply();
    }

    /** Newest first. */
    public static ArrayList<JSONObject> list(Context c) {
        ArrayList<JSONObject> out = new ArrayList<JSONObject>();
        synchronized (LOCK) {
            JSONArray a = read(c);
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(o);
            }
        }
        return out;
    }

    public static void remove(Context c, String localId) {
        synchronized (LOCK) {
            JSONArray a = read(c);
            JSONArray out = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && !localId.equals(o.optString("local_id"))) out.put(o);
            }
            write(c, out);
        }
    }

    /** Saves the request on the phone and sends it to the server in the background. */
    public static JSONObject add(final Context context, String kind, String title, String note) {
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        JSONObject r = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            r.put("local_id", Long.toString(now, 36) + Integer.toHexString((int) (Math.random() * 0xFFFF)));
            r.put("kind", MOVIE.equals(kind) || SERIES.equals(kind) || CHANNEL.equals(kind) ? kind : MOVIE);
            r.put("title", title == null ? "" : title.trim());
            r.put("note", note == null ? "" : note.trim());
            r.put("status", PENDING);
            r.put("created_at", now);
            r.put("updated_at", now);
            r.put("id", "");
            r.put("notified", "");
            synchronized (LOCK) {
                JSONArray a = read(app);
                // the same title twice: keep the old one, move it to the end (newest)
                JSONArray out = new JSONArray();
                String norm = CigramContent.norm(title);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    if (norm.equals(CigramContent.norm(o.optString("title"))) && r.optString("kind").equals(o.optString("kind"))
                            && !ADDED.equals(o.optString("status"))) {
                        r = o;
                        r.put("updated_at", now);
                        continue;
                    }
                    out.put(o);
                }
                out.put(r);
                while (out.length() > MAX_ITEMS) out.remove(0);
                write(app, out);
            }
            try { CigramAnalytics.track(app, "request_added", "kind", r.optString("kind")); } catch (Throwable ignored) { }
        } catch (Throwable error) {
            android.util.Log.w("CigramRequests", "add failed", error);
        }
        pushPendingAsync(app);
        return r;
    }

    // ------------------------------------------------------------------ network

    private static void pushPendingAsync(final Context app) {
        CigramApi.IO.execute(new Runnable() {
            @Override public void run() { pushPending(app); }
        });
    }

    /** Blocking (background thread). Sends every request that has no server id yet. */
    private static void pushPending(Context app) {
        ArrayList<JSONObject> todo = new ArrayList<JSONObject>();
        synchronized (LOCK) {
            JSONArray a = read(app);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && o.optString("id", "").length() == 0) todo.add(o);
            }
        }
        if (todo.isEmpty()) return;
        String token = "";
        try { if (CigramUserData.isLoggedIn(app)) token = CigramUserData.token(app); } catch (Throwable ignored) { }
        for (JSONObject o : todo) {
            try {
                JSONObject body = new JSONObject();
                body.put("kind", o.optString("kind"));
                body.put("title", o.optString("title"));
                body.put("note", o.optString("note"));
                body.put("client_id", CigramApi.clientId(app));
                body.put("app_version", CigramApi.appVersion(app));
                JSONObject res = CigramApi.post("/requests", body, token);
                if (res == null) return;                          // offline: try again later
                if (res.optInt("_http", 0) == 401 && token.length() > 0) {
                    res = CigramApi.post("/requests", body, null);  // expired token: send as guest
                    if (res == null) return;
                }
                String id = res.optString("id", "");
                if (res.optBoolean("ok", false) && id.length() > 0) setField(app, o.optString("local_id"), "id", id);
                else if (res.optInt("_http", 0) == 404) return;   // server not updated yet (v2.4)
            } catch (Throwable ignored) { }
        }
    }

    private static void setField(Context c, String localId, String key, Object value) {
        synchronized (LOCK) {
            try {
                JSONArray a = read(c);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && localId.equals(o.optString("local_id"))) {
                        o.put(key, value);
                        o.put("updated_at", System.currentTimeMillis());
                    }
                }
                write(c, a);
            } catch (Throwable ignored) { }
        }
    }

    /** Never blocks, safe from anywhere: app open, the background job, the communication page. */
    public static void refreshQuietly(Context context) {
        refresh(context, false, null);
    }

    public static void refresh(Context context, boolean force, final Runnable done) {
        if (context == null) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        long last = prefs(app).getLong(KEY_LAST_REFRESH, 0L);
        boolean any;
        synchronized (LOCK) { any = read(app).length() > 0; }
        if (!any || refreshing || (!force && System.currentTimeMillis() - last < MIN_REFRESH_MS)) {
            if (done != null) MAIN.post(done);
            return;
        }
        refreshing = true;
        CigramApi.IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    prefs(app).edit().putLong(KEY_LAST_REFRESH, System.currentTimeMillis()).apply();
                    pushPending(app);
                    pullStatuses(app);
                    matchCatalog(app);
                    notifyChanges(app);
                } catch (Throwable error) {
                    android.util.Log.w("CigramRequests", "refresh failed", error);
                } finally {
                    refreshing = false;
                    if (done != null) MAIN.post(done);
                }
            }
        });
    }

    private static void pullStatuses(Context app) {
        StringBuilder ids = new StringBuilder();
        synchronized (LOCK) {
            JSONArray a = read(app);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || o.optString("id", "").length() == 0) continue;
                if (ADDED.equals(o.optString("status")) && o.optBoolean("matched", false)) continue;
                if (ids.length() > 0) ids.append(',');
                ids.append(o.optString("id"));
            }
        }
        if (ids.length() == 0) return;
        JSONObject res = CigramApi.get("/requests/mine?ids=" + CigramApi.enc(ids.toString()));
        if (res == null || !res.optBoolean("ok", false)) return;
        JSONArray list = res.optJSONArray("requests");
        if (list == null) return;
        synchronized (LOCK) {
            try {
                JSONArray a = read(app);
                for (int j = 0; j < list.length(); j++) {
                    JSONObject s = list.optJSONObject(j);
                    if (s == null) continue;
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.optJSONObject(i);
                        if (o == null || !s.optString("id").equals(o.optString("id"))) continue;
                        String status = s.optString("status", PENDING);
                        // a local catalog match ("added") is never downgraded by the server
                        if (ADDED.equals(o.optString("status")) && !ADDED.equals(status)) continue;
                        if (!status.equals(o.optString("status")) || !s.optString("admin_note").equals(o.optString("admin_note"))) {
                            o.put("status", status);
                            o.put("admin_note", s.optString("admin_note", ""));
                            o.put("updated_at", System.currentTimeMillis());
                        }
                    }
                }
                write(app, a);
            } catch (Throwable ignored) { }
        }
    }

    /** A movie / series request becomes «تمت الإضافة» as soon as the catalog has a title with that name. */
    private static void matchCatalog(Context app) {
        ArrayList<JSONObject> catalog;
        try { catalog = CigramContent.catalog(); } catch (Throwable ignored) { return; }
        if (catalog == null || catalog.isEmpty()) return;
        synchronized (LOCK) {
            try {
                JSONArray a = read(app);
                boolean changed = false;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null || o.optBoolean("matched", false)) continue;
                    String kind = o.optString("kind");
                    if (CHANNEL.equals(kind)) continue;
                    JSONObject hit = findTitle(catalog, kind, o.optString("title"));
                    if (hit == null) continue;
                    o.put("status", ADDED);
                    o.put("matched", true);
                    o.put("match_type", SERIES.equals(kind) ? "series" : "movie");
                    o.put("match_id", CigramLinks.idOf(hit));
                    o.put("match_title", CigramLinks.titleOf(hit));
                    o.put("updated_at", System.currentTimeMillis());
                    changed = true;
                }
                if (changed) write(app, a);
            } catch (Throwable ignored) { }
        }
    }

    static JSONObject findTitle(ArrayList<JSONObject> catalog, String kind, String title) {
        String want = CigramContent.norm(title);
        if (want.length() < 2) return null;
        boolean series = SERIES.equals(kind);
        JSONObject loose = null;
        for (JSONObject item : catalog) {
            if (item == null) continue;
            boolean isSeries;
            try { isSeries = CigramContent.isSeries(item); } catch (Throwable e) { isSeries = false; }
            if (isSeries != series) continue;
            String[] names = {
                    CigramContent.text(item, "series_title", "title", "name"),
                    CigramContent.text(item, "original_title", "original_name"),
                    CigramContent.text(item, "title_ar"), CigramContent.text(item, "title_en")
            };
            for (String n : names) {
                String norm = CigramContent.norm(n);
                if (norm.length() == 0) continue;
                if (norm.equals(want)) return item;
                // «inception» vs «inception 2010»: one name starts with the other (4+ letters)
                if (loose == null && want.length() >= 4 && (norm.startsWith(want + " ") || want.startsWith(norm + " "))) loose = item;
            }
        }
        return loose;
    }

    // ------------------------------------------------------------------ notification

    private static void notifyChanges(Context app) {
        ArrayList<JSONObject> fire = new ArrayList<JSONObject>();
        synchronized (LOCK) {
            try {
                JSONArray a = read(app);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    String status = o.optString("status");
                    if (!(ADDED.equals(status) || UNAVAILABLE.equals(status) || IN_PROGRESS.equals(status))) continue;
                    if (status.equals(o.optString("notified"))) continue;
                    o.put("notified", status);
                    fire.add(new JSONObject(o.toString()));
                }
                if (!fire.isEmpty()) write(app, a);
            } catch (Throwable ignored) { }
        }
        for (JSONObject o : fire) show(app, o);
    }

    private static void show(Context app, JSONObject r) {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "طلباتي", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("عند إضافة فيلم أو مسلسل أو قناة طلبتها");
                nm.createNotificationChannel(ch);
            }
            boolean added = ADDED.equals(r.optString("status"));
            boolean approved = IN_PROGRESS.equals(r.optString("status"));
            String kind = kindLabel(r.optString("kind"));
            String title = r.optString("match_title", "");
            if (title.length() == 0) title = r.optString("title");
            // added:    «شاهد هَسّه» + the title, its description and a preview of its backdrop
            // approved: «المدير وافق، سيتم إضافته قريبًا، اصبر»
            String overview = "";
            String backdrop = "";
            if (added) {
                try {
                    JSONObject item = CigramContent.find(SERIES.equals(r.optString("match_type", r.optString("kind"))) ? "series" : "movie",
                            r.optString("match_id"), title);
                    if (item != null) {
                        overview = CigramContent.text(item, "description", "overview", "plot", "synopsis");
                        backdrop = CigramContent.text(item, "backdrop", "backdrop_url", "cover", "poster", "poster_url", "image");
                    }
                } catch (Throwable ignored) { }
            }
            String head = added ? "شاهد هَسّه · " + title : approved ? "المدير وافق" : "بخصوص طلبك";
            String body = added
                    ? (overview != null && overview.trim().length() > 0 ? overview.trim() : kind + " «" + title + "» صار متوفراً على سيكرام.")
                    : approved ? "«" + r.optString("title") + "» سيتم إضافته قريبًا، اصبر"
                    : "«" + r.optString("title") + "» غير متوفر حالياً."
                      + (r.optString("admin_note").length() > 0 ? " " + r.optString("admin_note") : "");

            Uri link;
            if (added && r.optString("match_id").length() > 0) {
                link = Uri.parse("cigram://title?type=" + r.optString("match_type", "movie")
                        + "&id=" + CigramApi.enc(r.optString("match_id")) + "&n=" + CigramApi.enc(title));
            } else {
                link = Uri.parse("cigram://requests");
            }
            Intent open = new Intent(app, CigramLinkActivity.class);
            open.setData(link);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            int code = r.optString("local_id").hashCode();
            PendingIntent pi = PendingIntent.getActivity(app, code, open, flags);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(app, CHANNEL_ID) : new Notification.Builder(app);
            b.setSmallIcon(CigramStage1InitProvider.notificationSmallIcon(app))
                    .setContentTitle(head)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(body))
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify("cigram_request", code, b.build());
            // the backdrop arrives a moment later and the same notification grows a picture
            if (added && backdrop != null && backdrop.startsWith("http")) {
                final String pictureUrl = backdrop, bigTitle = head, bigText = body;
                final Notification.Builder again = b;
                final NotificationManager manager = nm;
                final int id = code;
                new Thread(new Runnable() {
                    @Override public void run() {
                        java.net.HttpURLConnection c = null;
                        try {
                            c = (java.net.HttpURLConnection) new java.net.URL(pictureUrl).openConnection();
                            c.setConnectTimeout(8000);
                            c.setReadTimeout(10000);
                            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                            o.inSampleSize = 2;
                            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(c.getInputStream(), null, o);
                            if (bmp == null) return;
                            again.setLargeIcon(bmp).setStyle(new Notification.BigPictureStyle().bigPicture(bmp)
                                    .setBigContentTitle(bigTitle).setSummaryText(bigText));
                            manager.notify("cigram_request", id, again.build());
                        } catch (Throwable ignored) {
                        } finally {
                            if (c != null) c.disconnect();
                        }
                    }
                }, "cigram-request-picture").start();
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramRequests", "notify failed", error);
        }
    }
}
