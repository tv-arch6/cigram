package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.concurrent.TimeUnit;

/**
 * FCM client (v10.67).
 * Firebase is initialized from code (no google-services plugin in Sketchware) and the phone listens on:
 *   "cigram_all"  -> every phone: admin messages, update notices, new episodes
 *   "new_titles"  -> phones with «أفلام ومسلسلات جديدة» switched on (default on)
 *   "ep_<series>" -> one topic per followed series: its new episodes (only the followers get them)
 * v10.67: Firebase is created synchronously when the process starts (a push that starts the closed
 * app no longer races the old background init), and a topic is only marked done after Google
 * confirmed it, so a failed subscription is retried on the next start instead of after 3 days.
 */
public final class CigramFcm {
    private static final String PROJECT_ID = "cigram";
    private static final String APP_ID = "1:288492524620:android:87753e42e5d44cfa0a90e9";
    private static final String API_KEY = "AIzaSyBrdObxpjhRR4Y215AqX-W5Eq8AHxxnAAQ";
    private static final String SENDER_ID = "288492524620";
    private static final String STORAGE_BUCKET = "cigram.firebasestorage.app";
    public static final String TOPIC = "new_titles";
    public static final String TOPIC_ALL = "cigram_all";

    private static final String PREF = "cigram_fcm";
    private static final long RESUBSCRIBE_MS = 3L * 24 * 60 * 60 * 1000;

    private CigramFcm() {
    }

    /** Synchronous Firebase init. Cheap; call it from the main thread at process start. Never throws. */
    public static void initNow(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        ensureApp(app);
    }

    /** Safe to call from anywhere, any number of times. Never throws. */
    public static void sync(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    syncNow(app);
                } catch (Throwable error) {
                    android.util.Log.w("CigramFcm", "sync failed", error);
                }
            }
        }, "cigram-fcm-sync").start();
    }

    /** Forget the saved topic state (new token / settings change): the next sync subscribes again. */
    public static void resetState(Context context) {
        if (context == null) return;
        try {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void syncNow(Context app) {
        if (!ensureApp(app)) return;
        boolean titles = CigramStage1InitProvider.fcmNewReleaseEnabled(app);
        apply(app, TOPIC_ALL, true);
        apply(app, TOPIC, titles);
        applySeries(app);
    }

    /**
     * FCM topic of one series. Must match pushSeriesTopic() in the admin Worker:
     * "ep_" + id when the id is plain ASCII, otherwise "ep_h" + FNV-1a(id) in hex.
     */
    public static String seriesTopic(String seriesId) {
        String id = seriesId == null ? "" : seriesId.trim();
        if (id.matches("[A-Za-z0-9_\\-]{1,100}")) return "ep_" + id;
        int h = 0x811c9dc5;
        for (int i = 0; i < id.length(); i++) {
            h ^= id.charAt(i);
            h *= 0x01000193;
        }
        String hex = Long.toHexString(h & 0xffffffffL);
        while (hex.length() < 8) hex = "0" + hex;
        return "ep_h" + hex;
    }

    /** Subscribes to the topics of the followed series and leaves the ones no longer followed. */
    private static void applySeries(Context app) {
        SharedPreferences prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        java.util.TreeSet<String> want = new java.util.TreeSet<String>();
        for (String id : CigramStage1InitProvider.subscribedSeriesIds(app)) want.add(seriesTopic(id));
        java.util.TreeSet<String> have = new java.util.TreeSet<String>();
        try {
            org.json.JSONArray saved = new org.json.JSONArray(prefs.getString("ep_topics", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                String t = saved.optString(i, "");
                if (t.length() > 0) have.add(t);
            }
        } catch (Throwable ignored) { }
        if (want.equals(have)) return;
        java.util.TreeSet<String> now = new java.util.TreeSet<String>(have);
        try {
            FirebaseMessaging fm = FirebaseMessaging.getInstance();
            for (String t : want) {
                if (have.contains(t)) continue;
                if (confirm(fm.subscribeToTopic(t), t)) now.add(t);
            }
            for (String t : have) {
                if (want.contains(t)) continue;
                if (confirm(fm.unsubscribeFromTopic(t), t)) now.remove(t);
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramFcm", "series topics failed", error);
        }
        org.json.JSONArray out = new org.json.JSONArray();
        for (String t : now) out.put(t);
        prefs.edit().putString("ep_topics", out.toString()).apply();
    }

    private static boolean confirm(Task<Void> task, String topic) {
        try {
            Tasks.await(task, 30, TimeUnit.SECONDS);
            return task.isSuccessful();
        } catch (Throwable error) {
            android.util.Log.w("CigramFcm", "topic " + topic + " not confirmed", error);
            return false;
        }
    }

    private static void apply(Context app, String topic, boolean want) {
        SharedPreferences prefs = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String keyState = "t_" + topic;
        String keyAt = "t_" + topic + "_at";
        String wanted = want ? "on" : "off";
        long now = System.currentTimeMillis();
        if (wanted.equals(prefs.getString(keyState, "")) && now - prefs.getLong(keyAt, 0L) < RESUBSCRIBE_MS) return;
        try {
            FirebaseMessaging fm = FirebaseMessaging.getInstance();
            Task<Void> task = want ? fm.subscribeToTopic(topic) : fm.unsubscribeFromTopic(topic);
            Tasks.await(task, 30, TimeUnit.SECONDS);
            if (task.isSuccessful()) {
                prefs.edit().putString(keyState, wanted).putLong(keyAt, now).apply();
            } else {
                android.util.Log.w("CigramFcm", "topic " + topic + " not confirmed", task.getException());
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramFcm", "topic update failed: " + topic, error);
        }
    }

    public static boolean ensureApp(Context app) {
        if (app == null) return false;
        try {
            if (!FirebaseApp.getApps(app).isEmpty()) return true;
            FirebaseOptions options = new FirebaseOptions.Builder()
                    .setProjectId(PROJECT_ID)
                    .setApplicationId(APP_ID)
                    .setApiKey(API_KEY)
                    .setGcmSenderId(SENDER_ID)
                    .setStorageBucket(STORAGE_BUCKET)
                    .build();
            FirebaseApp.initializeApp(app, options);
            return true;
        } catch (IllegalStateException alreadyThere) {
            return true;
        } catch (Throwable error) {
            android.util.Log.w("CigramFcm", "firebase init failed", error);
            return false;
        }
    }
}
