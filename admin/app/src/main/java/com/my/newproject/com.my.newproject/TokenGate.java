package com.my.newproject;

import android.app.Activity;

/** Asks for the admin token once (and on demand). It is stored only on this phone, never in the app source. */
final class TokenGate {

    private TokenGate() { }

    private static boolean asking;

    /** Shows the prompt when no token is stored. */
    static void ensure(Activity a, Runnable after) {
        if (CgCfg.hasToken()) return;
        ask(a, after);
    }

    static void ask(final Activity a, final Runnable after) {
        if (asking) return;
        asking = true;
        CgUi.input(a, "رمز الإدارة", "الصق رمز ADMIN_TOKEN (نفس السر في Cloudflare)", "", false, new CgUi.Callback<String>() {
            @Override public void run(String value) {
                asking = false;
                if (value.length() == 0) { CgUi.toast(a, "بدون الرمز ترفض كل طلبات الإدارة (401)."); return; }
                CgCfg.saveToken(a, value);
                CgUi.toast(a, "تم حفظ الرمز على هذا الهاتف.");
                if (after != null) after.run();
            }
        });
        asking = false;
    }
}
