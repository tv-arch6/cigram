package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

/**
 * Delete account (v2.5), complete flow:
 *
 *  1. dark dialog in the app style that lists what will be deleted;
 *  2. the account password is required (the server answered "كلمة المرور غير صحيحة" to the old
 *     empty request, which is why deleting never worked) + an explicit "I understand" check;
 *  3. POST /account/delete {password, confirm}; server errors are shown inside the dialog, which
 *     stays open so the user can retry;
 *  4. on success every local trace of the account is removed (session, history, continue
 *     watching, favorites, lists, reactions, avatar, notifications state) and the user is
 *     returned to the account page as a guest.
 */
public final class CigramDeleteAccount {

    private static final String DELETE_URL = "https://cigram-auth-api.wwq-mixtv.workers.dev/account/delete";

    private CigramDeleteAccount() { }

    public static void show(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        final SharedPreferences auth = activity.getSharedPreferences("auth_session", Context.MODE_PRIVATE);
        final String token = auth.getString("token", "") == null ? "" : auth.getString("token", "").trim();
        if (token.length() == 0) {
            CigramSilent.toast(activity, "سجّل الدخول أولاً", android.widget.Toast.LENGTH_SHORT);
            return;
        }

        final CigramUI.Sheet sheet = CigramUI.sheet(activity)
                .icon(CigramUI.ICON_TRASH, CigramUI.DANGER)
                .title("حذف الحساب نهائياً")
                .message("سيتم حذف حسابك مع سجل المشاهدة والمفضلة وقائمة المشاهدة لاحقاً وكل بياناتك "
                        + "من هذا الجهاز ومن خوادمنا. لا يمكن التراجع عن هذه العملية.");

        final EditText password = new EditText(activity);
        CigramUI.styleInput(password, "أدخل كلمة المرور للتأكيد");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setSingleLine(true);
        password.setTextDirection(View.TEXT_DIRECTION_LTR);
        sheet.view(password);

        final boolean[] understood = new boolean[]{false};
        LinearLayout check = new LinearLayout(activity);
        check.setOrientation(LinearLayout.HORIZONTAL);
        check.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        check.setGravity(Gravity.CENTER_VERTICAL);
        check.setClickable(true);
        check.setFocusable(true);
        final View box = new View(activity);
        box.setBackground(CigramUI.round(Color.TRANSPARENT, 0xFF4A6573, 6, activity));
        check.addView(box, new LinearLayout.LayoutParams(CigramUI.dp(activity, 22), CigramUI.dp(activity, 22)));
        TextView checkText = CigramUI.label(activity, "أفهم أن الحذف نهائي ولا يمكن استرجاع الحساب", 13.5f,
                0xFFD7E2E7, false);
        LinearLayout.LayoutParams ct = new LinearLayout.LayoutParams(0, -2, 1f);
        ct.setMarginStart(CigramUI.dp(activity, 10));
        check.addView(checkText, ct);
        check.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                understood[0] = !understood[0];
                box.setBackground(understood[0]
                        ? CigramUI.round(CigramUI.DANGER, CigramUI.DANGER, 6, activity)
                        : CigramUI.round(Color.TRANSPARENT, 0xFF4A6573, 6, activity));
            }
        });
        sheet.view(check);

        final TextView error = CigramUI.label(activity, "", 13f, CigramUI.DANGER, true);
        error.setVisibility(View.GONE);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-1, -2);
        el.topMargin = CigramUI.dp(activity, 10);
        sheet.body.addView(error, el);

        final boolean[] busy = new boolean[]{false};
        final TextView[] deleteButton = new TextView[1];
        deleteButton[0] = sheet.button("حذف نهائي", 2, CigramUI.DANGER, new CigramUI.Click() {
            @Override public boolean onClick(CigramUI.Sheet s) {
                if (busy[0]) return true;
                String pw = password.getText().toString();
                if (pw.length() == 0) {
                    showError(error, "أدخل كلمة المرور لتأكيد الحذف");
                    password.requestFocus();
                    return true;
                }
                if (!understood[0]) {
                    showError(error, "فعّل خيار التأكيد أولاً");
                    return true;
                }
                busy[0] = true;
                error.setVisibility(View.GONE);
                deleteButton[0].setText("جارِ الحذف...");
                deleteButton[0].setAlpha(0.6f);
                send(activity, s, token, pw, error, busy, deleteButton[0]);
                return true;   // stays open until the server answers
            }
        });
        sheet.secondary("إلغاء", new CigramUI.Click() {
            @Override public boolean onClick(CigramUI.Sheet s) {
                return busy[0];   // cannot be cancelled while deleting
            }
        });
        sheet.show();
    }

    private static void showError(TextView error, String text) {
        error.setText(text);
        error.setVisibility(View.VISIBLE);
    }

    private static void send(final Activity activity, final CigramUI.Sheet sheet, final String token,
                             final String password, final TextView error, final boolean[] busy,
                             final TextView button) {
        new Thread(new Runnable() {
            @Override public void run() {
                int code = -1;
                String body = "";
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("password", password);
                    payload.put("current_password", password);
                    payload.put("confirm", true);
                    HttpURLConnection c = (HttpURLConnection) new URL(DELETE_URL).openConnection();
                    try {
                        c.setRequestMethod("POST");
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(20000);
                        c.setUseCaches(false);
                        c.setDoOutput(true);
                        c.setRequestProperty("Authorization", "Bearer " + token);
                        c.setRequestProperty("Accept", "application/json");
                        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                        byte[] data = payload.toString().getBytes("UTF-8");
                        OutputStream out = c.getOutputStream();
                        out.write(data);
                        out.flush();
                        out.close();
                        code = c.getResponseCode();
                        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                        if (in != null) {
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = in.read(buf)) > 0 && bos.size() < 256 * 1024) bos.write(buf, 0, n);
                            in.close();
                            body = bos.toString("UTF-8");
                        }
                    } finally {
                        c.disconnect();
                    }
                } catch (Throwable ignored) {
                    code = -1;
                }

                boolean ok = code >= 200 && code < 300;
                String message = "";
                try {
                    if (body.trim().length() > 0) {
                        JSONObject j = new JSONObject(body);
                        if (j.has("success") && !j.optBoolean("success", true)) ok = false;
                        message = j.optString("message", j.optString("error", "")).trim();
                    }
                } catch (Throwable ignored) { }

                final boolean fOk = ok;
                final int fCode = code;
                final String fMessage = message;
                activity.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (activity.isFinishing()) return;
                        if (fOk) {
                            sheet.dismiss();
                            wipeLocal(activity);
                            finishSuccess(activity);
                            return;
                        }
                        busy[0] = false;
                        button.setText("حذف نهائي");
                        button.setAlpha(1f);
                        String text;
                        if (fCode == -1) text = "تعذر الاتصال بالخادم. تأكد من الإنترنت وحاول مرة أخرى.";
                        else if (fCode == 401 || fCode == 403) {
                            text = fMessage.length() > 0 ? fMessage : "كلمة المرور غير صحيحة";
                        } else text = fMessage.length() > 0 ? fMessage : "تعذر حذف الحساب (" + fCode + ")";
                        showError(error, text);
                    }
                });
            }
        }).start();
    }

    private static void finishSuccess(final Activity activity) {
        CigramUI.sheet(activity)
                .icon(CigramUI.ICON_CHECK, CigramUI.GREEN)
                .title("تم حذف حسابك")
                .message("حُذف حسابك وكل بياناتك بنجاح. يمكنك إنشاء حساب جديد في أي وقت.")
                .cancelable(false)
                .primary("حسناً", CigramUI.GREEN, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) {
                        goGuest(activity);
                        return false;
                    }
                })
                .show();
    }

    private static void goGuest(Activity activity) {
        try {
            Intent i = new Intent(activity, AccountActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.startActivity(i);
            activity.finish();
        } catch (Throwable ignored) { }
    }

    /** Removes every local trace of the current account. Must run BEFORE the session is cleared. */
    public static void wipeLocal(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        try {
            SharedPreferences auth = app.getSharedPreferences("auth_session", Context.MODE_PRIVATE);
            String token = auth.getString("token", "");
            String email = CigramUserData.email(app);
            String acct = CigramUserData.accountHash(app);
            String tokenHash = Integer.toHexString((token == null ? "" : token).hashCode());
            String favHash = Integer.toHexString((email.length() > 0 ? email : (token == null ? "" : token.trim())).hashCode());

            // history / seen / seasons of this account (+ server mirror is gone with the account)
            CigramUserData.wipeAccount(app);

            // continue watching + resume positions
            SharedPreferences cp = app.getSharedPreferences("continue_watching", Context.MODE_PRIVATE);
            cp.edit().remove("items_" + acct).remove("items_" + tokenHash).apply();
            SharedPreferences pp = app.getSharedPreferences("watch_progress", Context.MODE_PRIVATE);
            SharedPreferences.Editor pe = pp.edit();
            for (String k : new ArrayList<String>(pp.getAll().keySet())) {
                if (k != null && (k.startsWith("account_" + acct + "|") || k.startsWith("account_" + tokenHash + "|"))) {
                    pe.remove(k);
                }
            }
            pe.apply();

            // favorites, watch later, subscriptions, reactions, notifications state, update snooze
            app.getSharedPreferences("favorites", Context.MODE_PRIVATE).edit().remove("items_" + favHash).apply();
            for (String name : new String[]{"cigram_watch_later", "cigram_series_subscriptions",
                    "cigram_stage3_reactions_v1", "cigram_stage6_notifications", "cigram_report_v1"}) {
                try { app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply(); } catch (Throwable ignored) { }
            }

            // avatar
            try {
                java.io.File f = new java.io.File(app.getFilesDir(), "cigram_profile_avatar.jpg");
                if (f.exists()) f.delete();
            } catch (Throwable ignored) { }
            // v10.67: the saved profile (name, gender, photo) and the account cover go with the account
            try { CigramProfile.wipe(app); } catch (Throwable ignored) { }
            try { CigramBanner.wipe(app); } catch (Throwable ignored) { }

            // pending system notifications of this account
            try {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancelAll();
            } catch (Throwable ignored) { }

            // finally the session itself
            CigramSession.forget(app);
            auth.edit().clear().apply();
        } catch (Throwable error) {
            android.util.Log.w("CigramDelete", "local wipe failed", error);
            try {
                CigramSession.forget(app);
                app.getSharedPreferences("auth_session", Context.MODE_PRIVATE).edit().clear().apply();
            } catch (Throwable ignored) { }
        }
    }
}
