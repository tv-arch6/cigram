package com.my.newproject;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 19.0 — Firebase Cloud Messaging for the admin app: a new user message reaches the phone while the app is closed.
 *
 * Firebase is created from code (the project has no google-services plugin) with the PUBLIC client settings of the Android app
 * {@code com.my.newproject} registered in the Firebase project; they come from Gradle properties ADMIN_FCM_APP_ID / _API_KEY /
 * _PROJECT_ID / _SENDER_ID (BuildConfig). When they are missing nothing is faked: {@link #missing()} names exactly what is
 * absent, the status screen shows it, and the 15-minute poll of {@link AdInboxNotifier} keeps running as the fallback.
 *
 * The device token is sent to the Worker with the admin token ({@code POST /admin/chat/device}); the Worker pushes a data
 * message {kind: chat, to: admin, thread, name, body, msg_id, seq, unread, ts} — never a credential.
 */
final class AdFcm {

    private static final String PREFS = "cigram_ads_fcm";
    private static final long RECHECK_MS = 24L * 60L * 60L * 1000L;

    private AdFcm() { }

    static String appId() { return BuildConfig.FCM_APP_ID == null ? "" : BuildConfig.FCM_APP_ID.trim(); }

    static String senderId() {
        String s = BuildConfig.FCM_SENDER_ID == null ? "" : BuildConfig.FCM_SENDER_ID.trim();
        if (s.length() > 0) return s;
        String[] p = appId().split(":");           // "1:<sender>:android:<hash>"
        return p.length >= 2 ? p[1] : "";
    }

    /** What is still missing for FCM to work in this build ("" entries never appear). Pure, JVM-tested. */
    static List<String> missing(String appId, String apiKey, String projectId, String senderId) {
        ArrayList<String> m = new ArrayList<String>();
        if (appId == null || !appId.matches("\\d+:\\d+:android:[0-9a-fA-F]+")) m.add("ADMIN_FCM_APP_ID (Firebase console > Project settings > Your apps > com.my.newproject > App ID, e.g. 1:123456789:android:abcdef)");
        if (apiKey == null || apiKey.trim().length() < 20) m.add("ADMIN_FCM_API_KEY (the Android app's Web API key)");
        if (projectId == null || projectId.trim().length() == 0) m.add("ADMIN_FCM_PROJECT_ID");
        if (senderId == null || !senderId.matches("\\d+")) m.add("ADMIN_FCM_SENDER_ID (Project settings > Cloud Messaging > Sender ID)");
        return m;
    }

    static List<String> missing() {
        return missing(appId(), BuildConfig.FCM_API_KEY, BuildConfig.FCM_PROJECT_ID, senderId());
    }

    static boolean configured() { return missing().isEmpty(); }

    static boolean ensureApp(Context app) {
        if (app == null || !configured()) return false;
        try {
            if (!FirebaseApp.getApps(app).isEmpty()) return true;
            FirebaseApp.initializeApp(app, new FirebaseOptions.Builder()
                    .setApplicationId(appId())
                    .setApiKey(BuildConfig.FCM_API_KEY.trim())
                    .setProjectId(BuildConfig.FCM_PROJECT_ID.trim())
                    .setGcmSenderId(senderId())
                    .build());
            return true;
        } catch (IllegalStateException alreadyThere) {
            return true;
        } catch (Throwable error) {
            android.util.Log.w("AdFcm", "firebase init failed", error);
            return false;
        }
    }

    /** Pure decision (JVM-tested): send the token now? */
    static boolean shouldRegister(String savedToken, String token, long savedAt, long lastTryAt, long now, boolean force) {
        if (token == null || token.length() == 0) return false;
        if (force) return true;
        if (!token.equals(savedToken)) return now - lastTryAt >= 30_000L;
        return now - savedAt >= RECHECK_MS && now - lastTryAt >= 15L * 60L * 1000L;
    }

    /** Background registration of this phone's token with the Worker. Safe to call often; never throws. */
    static void ensureRegistered(final Context context) {
        if (context == null || !configured()) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                try { registerNow(app, null, false); } catch (Throwable error) { android.util.Log.w("AdFcm", "register failed", error); }
            }
        });
    }

    static void onNewToken(final Context context, final String token) {
        if (context == null || token == null || !configured()) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                try { registerNow(app, token, true); } catch (Throwable error) { android.util.Log.w("AdFcm", "token refresh failed", error); }
            }
        });
    }

    private static synchronized void registerNow(Context app, String knownToken, boolean force) throws Exception {
        CgCfg.loadToken(app);
        if (CgCfg.TOKEN.length() == 0 || !ensureApp(app)) return;
        String token = knownToken;
        if (token == null || token.length() == 0) token = Tasks.await(FirebaseMessaging.getInstance().getToken(), 20, TimeUnit.SECONDS);
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!shouldRegister(p.getString("token", ""), token, p.getLong("at", 0L), p.getLong("try_at", 0L), now, force)) return;
        p.edit().putLong("try_at", now).apply();
        JSONObject body = new JSONObject();
        body.put("token", token);
        body.put("app", "cigram-admin");
        body.put("v", 10);
        AdApi.Result r = AdApi.request("POST", "/admin/chat/device", body.toString(), null);
        if (r.ok) p.edit().putString("token", token).putLong("at", System.currentTimeMillis()).putString("error", "").apply();
        else p.edit().putString("error", "HTTP " + r.code + " " + r.error).apply();
    }

    /** Local registration state for the status screen. */
    static String localState(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!configured()) return "غير مُفعّل في هذا البناء";
        if (p.getString("token", "").length() == 0) return p.getString("error", "").length() > 0 ? "لم يُسجَّل: " + p.getString("error", "") : "لم يُسجَّل بعد";
        return "مُسجَّل منذ " + AdApi.date(p.getLong("at", 0L));
    }
}
