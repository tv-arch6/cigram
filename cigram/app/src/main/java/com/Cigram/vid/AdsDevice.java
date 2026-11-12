package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 19.0 — tells the chat Worker which phone (FCM token) belongs to the signed-in account, so an administrator's reply can be
 * pushed to THIS user while Cigram is closed (the old flow only had topic pushes and a 15-minute poll).
 *
 * The token is sent over the authenticated chat API ({@code POST /chat/device}); the Worker stores it under the account's
 * thread and moves it away from any other account that used the same phone. Nothing but the token and the app version
 * is sent. Registration is idempotent and throttled: it runs when the token changes, when the account changes, and at most
 * once a day otherwise. A failure is silent and retried at the next app start (the 15-minute poll still covers the gap).
 */
final class AdsDevice {

    private static final String PREFS = "cigram_ads_device";
    private static final long RECHECK_MS = 24L * 60L * 60L * 1000L;
    private static final long RETRY_MS = 15L * 60L * 1000L;
    private static final ExecutorService POOL = CigramPool.fixed(1, "ads-device");
    private static volatile boolean running;

    private AdsDevice() { }

    /** Pure decision (JVM-tested): should the token be (re)sent now? */
    static boolean shouldRegister(String savedToken, String token, String savedOwner, String owner, long savedAt, long lastTryAt, long now, boolean force) {
        if (token == null || token.length() == 0 || owner == null || owner.length() == 0) return false;
        if (force) return true;
        if (!token.equals(savedToken) || !owner.equals(savedOwner)) return now - lastTryAt >= 30_000L;   // changed: send now (30 s guard against a loop)
        return now - savedAt >= RECHECK_MS && now - lastTryAt >= RETRY_MS;
    }

    /** From anywhere (main thread included): registers in the background when the account is signed in. Never throws. */
    static void ensureRegistered(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (running) return;
        running = true;
        try {
            POOL.execute(new Runnable() {
                @Override public void run() {
                    try { registerNow(app, null, false); } catch (Throwable error) { android.util.Log.w("AdsDevice", "register failed", error); }
                    finally { running = false; }
                }
            });
        } catch (Throwable rejected) {
            running = false;
        }
    }

    /** FirebaseMessagingService.onNewToken. */
    static void onNewToken(final Context context, final String token) {
        if (context == null || token == null || token.length() == 0) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            POOL.execute(new Runnable() {
                @Override public void run() {
                    try { registerNow(app, token, true); } catch (Throwable error) { android.util.Log.w("AdsDevice", "token refresh failed", error); }
                }
            });
        } catch (Throwable ignored) { }
    }

    private static void registerNow(Context app, String knownToken, boolean force) throws Exception {
        if (!CigramAds.isLoggedIn(app)) return;
        String owner = CigramUserData.authUserId(app);
        if (owner == null || owner.length() == 0) owner = CigramUserData.email(app);
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String token = knownToken;
        if (token == null || token.length() == 0) {
            if (!CigramFcm.ensureApp(app)) return;
            token = Tasks.await(FirebaseMessaging.getInstance().getToken(), 20, TimeUnit.SECONDS);
        }
        long now = System.currentTimeMillis();
        if (!shouldRegister(p.getString("token", ""), token, p.getString("owner", ""), owner == null ? "" : owner,
                p.getLong("at", 0L), p.getLong("try_at", 0L), now, force)) return;
        p.edit().putLong("try_at", now).apply();
        JSONObject body = new JSONObject();
        body.put("token", token);
        body.put("app", "cigram");
        try { body.put("v", Integer.parseInt(CigramAds.versionCode(app))); } catch (Throwable ignored) { }
        CigramAds.Result r = CigramAds.request("POST", "/chat/device", body.toString(), CigramUserData.token(app));
        if (r.ok) {
            p.edit().putString("token", token).putString("owner", owner == null ? "" : owner).putLong("at", System.currentTimeMillis()).apply();
        } else {
            android.util.Log.i("AdsDevice", "chat device not registered: code=" + r.code + " error=" + r.error);
        }
    }
}
