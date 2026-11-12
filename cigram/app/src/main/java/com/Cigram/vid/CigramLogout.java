package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * «تسجيل الخروج» of the Account page, in one tap.
 *
 * The viewer leaves the account straight from «إجراءات الحساب»: no Settings page in between, no question, no error
 * text. What belongs to the account stays on the phone — the profile (name, e-mail, public id, picture) is remembered
 * as "the last account" so the sign-in page can offer it again, and the lists / watch history kept per account by
 * {@link CigramUserData} are not touched. Only the session itself goes: the token and the saved sign-in secret.
 *
 * The server is told in the background (best effort): a phone that is offline still signs out at once, and the token
 * simply expires on the server.
 */
final class CigramLogout {

    private static final String LAST = "cigram_last_account";
    private static final String API = "https://cigram-auth-api.wwq-mixtv.workers.dev/logout";

    private CigramLogout() { }

    /** The profile of the account that signed out last on this phone ("" when none). */
    static String last(Context c, String key) {
        try {
            String v = c.getApplicationContext().getSharedPreferences(LAST, Context.MODE_PRIVATE).getString(key, "");
            return v == null ? "" : v;
        } catch (Throwable e) {
            return "";
        }
    }

    static void run(final Activity a) {
        if (a == null) return;
        final Context app = a.getApplicationContext();
        String token = "";
        try {
            SharedPreferences auth = app.getSharedPreferences("auth_session", Context.MODE_PRIVATE);
            token = auth.getString("token", "");
            // 1) remember the account (never the token)
            SharedPreferences.Editor keep = app.getSharedPreferences(LAST, Context.MODE_PRIVATE).edit();
            for (String k : new String[]{"name", "email", "public_id", "avatar_url", "gender", "user_id"}) {
                String v = auth.getString(k, "");
                if (v != null && v.length() > 0) keep.putString(k, v);
            }
            keep.putLong("signed_out_at", System.currentTimeMillis()).apply();
            // 2) end the session on this phone
            CigramSession.forget(app);
            auth.edit().clear().apply();
        } catch (Throwable ignored) { }
        tellServer(token);
        // 3) the Account page again, now as a visitor (the remembered account is offered on it)
        try {
            Intent i = new Intent(a, AccountActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            a.startActivity(i);
            a.finish();
            a.overridePendingTransition(0, 0);
        } catch (Throwable ignored) { }
    }

    private static void tellServer(final String token) {
        if (token == null || token.trim().length() == 0) return;
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection con = null;
                try {
                    con = (HttpURLConnection) new URL(API).openConnection();
                    con.setRequestMethod("POST");
                    con.setConnectTimeout(8000);
                    con.setReadTimeout(8000);
                    con.setDoOutput(true);
                    con.setRequestProperty("Authorization", "Bearer " + token);
                    con.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    OutputStream out = con.getOutputStream();
                    try { out.write("{}".getBytes("UTF-8")); } finally { out.close(); }
                    con.getResponseCode();
                } catch (Throwable ignored) {
                } finally {
                    if (con != null) con.disconnect();
                }
            }
        }, "cigram-logout").start();
    }
}
