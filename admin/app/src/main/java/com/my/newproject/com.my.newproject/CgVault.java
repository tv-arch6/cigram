package com.my.newproject;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Cigram Studio — privacy of the admin's files («خصوصية ملفات الإدارة»).
 *
 * <ul>
 *   <li><b>The admin token is encrypted at rest.</b> It used to sit as plain text in the app's preferences file. Now it
 *   is sealed with AES-256-GCM under a key that lives in the Android Keystore (hardware-backed where the phone has
 *   it): the key cannot be exported, so a copy of the app's data — a backup, a phone-to-phone transfer, a rooted file
 *   manager — holds only ciphertext. An existing plain token is sealed on the first start and the plain copy removed.
 *   Android 5.x has no such key store: there the token stays as before.</li>
 *   <li><b>Nothing of the app is backed up or transferred</b> (manifest: allowBackup=false + data-extraction rules for
 *   Android 12+, where allowBackup alone no longer stops a device-to-device transfer).</li>
 *   <li><b>App lock</b> (off by default): after a minute in the background the phone's own screen lock (PIN, pattern,
 *   fingerprint) is asked before any admin screen is shown.</li>
 *   <li><b>Screen guard</b> (off by default): admin screens cannot be captured, recorded or shown in the recent-apps
 *   preview.</li>
 *   <li><b>Temporary files</b> (previews, prepared uploads, exports in the cache) can be wiped on demand.</li>
 * </ul>
 * What this class cannot do is protect files on the SERVER: see the report (the private admin objects of the Worker
 * need a private bucket binding).
 */
final class CgVault {

    private CgVault() { }

    private static final String ALIAS = "cigram_admin_vault_v1";
    private static final String PREFS = "cigram_admin_vault";
    private static final String PREFIX = "v1:";
    static final int REQ_UNLOCK = 9917;
    private static final long RELOCK_MS = 60000L;

    // ------------------------------------------------------------------ sealing

    static boolean available() {
        return Build.VERSION.SDK_INT >= 23;
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        java.security.Key k = ks.getKey(ALIAS, null);
        if (k instanceof SecretKey) return (SecretKey) k;
        KeyGenerator g = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        g.init(new android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    /** Ciphertext for the preferences file, or null when sealing is not possible on this phone. */
    static String seal(String plain) {
        if (!available() || plain == null) return null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            return PREFIX + Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP);
        } catch (Throwable t) {
            android.util.Log.w("CgVault", "seal failed", t);
            return null;
        }
    }

    /** The plain text of {@link #seal}'s output, or null (wrong format, key gone — e.g. the data came from another phone). */
    static String open(String sealed) {
        if (!available() || sealed == null || !sealed.startsWith(PREFIX)) return null;
        try {
            String[] p = sealed.substring(PREFIX.length()).split(":");
            if (p.length != 2) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(p[0], Base64.NO_WRAP)));
            return new String(c.doFinal(Base64.decode(p[1], Base64.NO_WRAP)), "UTF-8");
        } catch (Throwable t) {
            android.util.Log.w("CgVault", "open failed", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ switches

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean screenGuard(Context c) { return prefs(c).getBoolean("screen_guard", false); }

    static boolean appLock(Context c) { return prefs(c).getBoolean("app_lock", false); }

    /** Called by every admin screen when it is created. */
    static void apply(Activity a) {
        try {
            if (screenGuard(a)) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ app lock

    private static long leftAt;          // elapsedRealtime when the last admin screen went to the background
    private static boolean unlocked;     // this process has been unlocked and not been away for a minute since
    private static boolean prompting;

    static void onPause() {
        leftAt = SystemClock.elapsedRealtime();
    }

    static void onResume(Activity a) {
        if (!appLock(a)) { unlocked = true; return; }
        if (prompting) return;
        boolean away = leftAt > 0 && SystemClock.elapsedRealtime() - leftAt > RELOCK_MS;
        if (unlocked && !away) return;
        try {
            KeyguardManager km = (KeyguardManager) a.getSystemService(Context.KEYGUARD_SERVICE);
            boolean secure = km != null && (Build.VERSION.SDK_INT >= 23 ? km.isDeviceSecure() : km.isKeyguardSecure());
            if (!secure) { unlocked = true; return; }             // the phone has no screen lock (any more): nothing to ask with
            Intent i = km.createConfirmDeviceCredentialIntent("Cigram Studio", "أكّد هويتك لفتح لوحة الإدارة");
            if (i == null) { unlocked = true; return; }
            prompting = true;
            unlocked = false;
            a.startActivityForResult(i, REQ_UNLOCK);
        } catch (Throwable t) {
            prompting = false;
            unlocked = true;                                         // never lock the admin out because of a platform quirk
        }
    }

    static void onResult(Activity a, int request, int result) {
        if (request != REQ_UNLOCK) return;
        prompting = false;
        if (result == Activity.RESULT_OK) {
            unlocked = true;
            leftAt = 0;
        } else {
            unlocked = false;
            try { a.finishAffinity(); } catch (Throwable ignored) { a.finish(); }
        }
    }

    // ------------------------------------------------------------------ temporary files

    private static long size(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        File[] list = f.listFiles();
        if (list != null) for (File x : list) n += size(x);
        return n;
    }

    private static void delete(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] list = f.listFiles();
            if (list != null) for (File x : list) delete(x);
        }
        f.delete();
    }

    static long tempBytes(Context c) {
        long n = size(c.getCacheDir());
        try { n += size(c.getExternalCacheDir()); } catch (Throwable ignored) { }
        return n;
    }

    /** Deletes the app's caches (previews, prepared uploads, exports). The catalogue copy, drafts and settings are not caches and stay. */
    static long wipeTemp(Context c) {
        long before = tempBytes(c);
        File[] list = c.getCacheDir().listFiles();
        if (list != null) for (File f : list) delete(f);
        try {
            File ext = c.getExternalCacheDir();
            File[] el = ext == null ? null : ext.listFiles();
            if (el != null) for (File f : el) delete(f);
        } catch (Throwable ignored) { }
        try { CigramEmoji.trimMemory(); } catch (Throwable ignored) { }
        return Math.max(0, before - tempBytes(c));
    }

    // ------------------------------------------------------------------ the privacy sheet

    static void showPrivacy(final Activity a) {
        final LinearLayout box = CgUi.vbox(a);
        int p = CgUi.dp(a, 4);
        box.setPadding(p, p, p, p);

        boolean sealed = false;
        try { sealed = a.getSharedPreferences(CgCfg.PREFS, Context.MODE_PRIVATE).getString("admin_token_sealed", "").startsWith(PREFIX); } catch (Throwable ignored) { }
        box.addView(line(a, CgCfg.hasToken() ? (sealed ? "رمز الإدارة مشفّر على هذا الجهاز (AES-256 داخل مخزن مفاتيح أندرويد)." : available()
                ? "رمز الإدارة سيُشفَّر عند حفظه التالي." : "هذا الإصدار من أندرويد لا يدعم مخزن المفاتيح: الرمز محفوظ في ملفات التطبيق الخاصة فقط.")
                : "لم يُدخل رمز الإدارة بعد.", sealed ? CgCfg.GOOD : CgCfg.WARN));
        box.addView(line(a, "النسخ الاحتياطي ونقل البيانات إلى جهاز آخر معطّلان لهذا التطبيق.", CgCfg.GOOD));

        final CgUi.Toggle lock = CgUi.toggle(a, "قفل التطبيق بقفل الجهاز (بعد دقيقة في الخلفية)", appLock(a));
        box.addView(lock, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        final CgUi.Toggle guard = CgUi.toggle(a, "منع تصوير الشاشة وإخفاء المحتوى من قائمة التطبيقات الأخيرة", screenGuard(a));
        box.addView(guard, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));

        final TextView temp = CgUi.muted(a, "الملفات المؤقتة: " + CgUi.sizeText(tempBytes(a)));
        temp.setGravity(Gravity.RIGHT);
        box.addView(temp, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 14, 0, 4));
        TextView wipe = CgUi.button(a, "مسح الملفات المؤقتة الآن", 1);
        wipe.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                long freed = wipeTemp(a);
                temp.setText("الملفات المؤقتة: " + CgUi.sizeText(tempBytes(a)) + " (حُذف " + CgUi.sizeText(freed) + ")");
            }
        });
        box.addView(wipe, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 0));
        box.addView(line(a, "ملفات الخادم الخاصة (‎_admin_private‎) تبقى قابلة للقراءة العامة إلى أن يُربط حاوية خاصة ADMIN_BUCKET في Cloudflare — راجع التقرير.", CgCfg.WARN));

        CgUi.dialog(a).setTitle("خصوصية ملفات الإدارة").setView(box)
                .setPositiveButton("حفظ", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        boolean wantLock = lock.on;
                        if (wantLock) {
                            KeyguardManager km = (KeyguardManager) a.getSystemService(Context.KEYGUARD_SERVICE);
                            boolean secure = km != null && (Build.VERSION.SDK_INT >= 23 ? km.isDeviceSecure() : km.isKeyguardSecure());
                            if (!secure) {
                                wantLock = false;
                                CgUi.toast(a, "فعّل قفل الشاشة (رمز أو بصمة) في إعدادات الجهاز أولاً.");
                            }
                        }
                        prefs(a).edit().putBoolean("app_lock", wantLock).putBoolean("screen_guard", guard.on).apply();
                        unlocked = true;                       // the admin is in front of the phone right now
                        apply(a);
                        CgUi.toast(a, "حُفظت إعدادات الخصوصية.");
                    }
                })
                .setNegativeButton("إغلاق", null).show();
    }

    private static View line(Context c, String text, int color) {
        LinearLayout row = CgUi.hbox(c);
        row.setGravity(Gravity.TOP);
        TextView t = CgUi.text(c, text, 13, CgCfg.TEXT, false);
        t.setGravity(Gravity.RIGHT);
        row.addView(t, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        TextView dot = CgUi.text(c, "●", 12, color, true);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        dl.setMargins(CgUi.dp(c, 8), CgUi.dp(c, 2), 0, 0);
        row.addView(dot, dl);
        row.setLayoutParams(CgUi.lp(c, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 4));
        return row;
    }
}
