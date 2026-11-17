package com.my.newproject;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

/**
 * 19.6 — the owner is the only administrator and types nothing.
 *
 *  - A build made with the owner's token (GitHub secret / local.properties, see app/build.gradle.kts) is linked from the
 *    first start: this class never shows anything.
 *  - Otherwise the app is linked ONCE, on its very first start, and remembers it on this phone for good (encrypted, see
 *    CgVault). If the token is on the clipboard it is filled in already: one tap on «ربط».
 *  - No prompt on every screen any more: a refused token (401) is reported once per launch.
 */
final class TokenGate {

    private TokenGate() { }

    private static boolean asking;
    private static boolean rejectedShown;

    /** Shows the one-time link sheet when this phone has never been linked. */
    static void ensure(Activity a, Runnable after) {
        if (CgCfg.hasToken()) return;
        ask(a, after);
    }

    /** The Worker refused the stored token (401). Said once per launch, never a loop of prompts. */
    static void rejected(Activity a) {
        if (rejectedShown) return;
        rejectedShown = true;
        if (CgCfg.builtToken().length() > 0) {
            CgUi.info(a, "الخادم رفض رمز الإدارة", "الرمز المبني داخل هذه النسخة لا يطابق ADMIN_TOKEN في Cloudflare. ابنِ النسخة من جديد بالرمز الصحيح (CIGRAM_ADMIN_TOKEN).");
            return;
        }
        ask(a, null);
    }

    static void ask(final Activity a, final Runnable after) {
        if (asking || CgCfg.builtToken().length() > 0) return;
        asking = true;
        String clip = clipboardToken(a);
        CgUi.input(a, "ربط لوحة الإدارة (مرة واحدة فقط)", "رمز ADMIN_TOKEN الخاص بك — يُحفظ مشفّراً على هذا الهاتف ولن يُطلب مرة ثانية", clip, false, new CgUi.Callback<String>() {
            @Override public void run(String value) {
                asking = false;
                String v = value == null ? "" : value.trim();
                if (v.length() == 0) { CgUi.toast(a, "بدون الربط ترفض كل طلبات الإدارة (401)."); return; }
                CgCfg.saveToken(a, v);
                rejectedShown = false;
                CgUi.toast(a, "تم الربط. لن يُطلب منك شيء بعد الآن.");
                if (after != null) after.run();
            }
        });
        asking = false;
    }

    /** A token-looking text on the clipboard (long, no spaces), so the owner only taps «ربط». */
    private static String clipboardToken(Context c) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            ClipData d = cm.getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return "";
            CharSequence t = d.getItemAt(0).getText();
            String s = t == null ? "" : t.toString().trim();
            return s.length() >= 16 && s.length() <= 512 && !s.matches(".*\\s.*") ? s : "";
        } catch (Throwable ignored) {
            return "";
        }
    }
}
