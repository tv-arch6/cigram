package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyStore;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * يحافظ على جلسة الحساب حيّة.
 *
 * المشكلة: السيرفر يصدر رمزاً (token) له تاريخ انتهاء (expires_at) أو قد يُبطله، بينما التطبيق كان
 * يحفظ logged_in=true للأبد ولا يجدد الرمز أبداً؛ فتبقى الواجهة «مسجّل دخول» والسيرفر يرد 401
 * عند الإعجاب وغيره.
 *
 * الحل: عند تسجيل الدخول/إنشاء الحساب تُحفظ بيانات الدخول مشفّرة بمفتاح من Android Keystore
 * (لا يمكن استخراجه من الجهاز)، وعند انتهاء الرمز أو رد 401 يُجدَّد الرمز بصمت بإعادة تسجيل الدخول.
 * تُمسح البيانات المشفرة عند تسجيل الخروج أو حذف الحساب.
 */
public final class CigramSession {

    private static final String LOGIN_URL = "https://cigram-auth-api.wwq-mixtv.workers.dev/login";
    private static final String AUTH_PREFS = "auth_session";
    private static final String VAULT_PREFS = "cigram_session_vault";
    private static final String KEY_ALIAS = "cigram_session_vault_v1";
    /** جدّد قبل الانتهاء بيوم حتى لا يصل المستخدم لحظة الرفض. */
    private static final long RENEW_BEFORE_MS = 24L * 60L * 60L * 1000L;
    /** لا تكرر محاولة فاشلة قبل هذه المدة (حماية من إغراق السيرفر). */
    private static final long FAILED_RETRY_MS = 60L * 1000L;

    private static final Object LOCK = new Object();
    /** قفل منفصل للتجديد الشبكي حتى لا يتعطل خيط الواجهة في pending()/commit(). */
    private static final Object RENEW_LOCK = new Object();
    private static String pendingEmail = "";
    private static String pendingPassword = "";
    private static volatile long lastFailureAt;

    private CigramSession() { }

    // --------------------------------------------------------------- login hooks

    /** يُستدعى عند الضغط على «دخول»/«إنشاء حساب» قبل إرسال الطلب. */
    public static void pending(String email, String password) {
        synchronized (LOCK) {
            pendingEmail = email == null ? "" : email.trim().toLowerCase(Locale.US);
            pendingPassword = password == null ? "" : password;
        }
    }

    /** يُستدعى بعد نجاح الدخول وحفظ auth_session. يحفظ بيانات الدخول مشفّرة لهذا الحساب. */
    public static void commit(Context context, String confirmedEmail) {
        String email;
        String password;
        synchronized (LOCK) {
            email = pendingEmail;
            password = pendingPassword;
            pendingEmail = "";
            pendingPassword = "";
            lastFailureAt = 0L;
        }
        if (context == null || password.length() == 0) return;
        String confirmed = confirmedEmail == null ? "" : confirmedEmail.trim().toLowerCase(Locale.US);
        if (confirmed.length() > 0) email = confirmed;
        if (email.length() == 0) return;
        try {
            String sealed = seal(password);
            if (sealed == null) return;
            vault(context).edit()
                    .putString("email", email)
                    .putString("secret", sealed)
                    .apply();
        } catch (Throwable error) {
            android.util.Log.w("CigramSession", "unable to store session vault", error);
        }
    }

    /** يُستدعى عند تسجيل الخروج أو حذف الحساب. */
    public static void forget(Context context) {
        synchronized (LOCK) {
            pendingEmail = "";
            pendingPassword = "";
        }
        if (context == null) return;
        try {
            vault(context).edit().clear().apply();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------- state

    public static String token(Context context) {
        if (context == null) return "";
        try {
            String t = auth(context).getString("token", "");
            return t == null ? "" : t.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** هل يمكن تجديد الجلسة بصمت لهذا الحساب؟ */
    public static boolean canRenew(Context context) {
        if (context == null) return false;
        try {
            String authEmail = clean(auth(context).getString("email", "")).toLowerCase(Locale.US);
            SharedPreferences v = vault(context);
            String vaultEmail = clean(v.getString("email", ""));
            return authEmail.length() > 0
                    && authEmail.equals(vaultEmail)
                    && clean(v.getString("secret", "")).length() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** true إن كان تاريخ الانتهاء معروفاً وقريباً/فائتاً. */
    public static boolean expiresSoon(Context context) {
        long at = expiresAtMs(context);
        return at > 0L && at - System.currentTimeMillis() < RENEW_BEFORE_MS;
    }

    /**
     * يُستدعى عند بدء التطبيق (في خيط خلفي): يمسح البيانات اليتيمة بعد الخروج،
     * ويجدد الرمز مسبقاً إن كان سينتهي قريباً.
     */
    public static void warmUp(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String email = clean(auth(app).getString("email", ""));
                    if (email.length() == 0 && token(app).length() == 0) {
                        forget(app);
                        return;
                    }
                    if (expiresSoon(app)) renewBlocking(app);
                } catch (Throwable ignored) { }
            }
        }, "cigram-session-warmup").start();
    }

    // ----------------------------------------------------------------- renewal

    /**
     * يعيد تسجيل الدخول بصمت ويحفظ الرمز الجديد. يعمل في خيط خلفي فقط.
     * @return الرمز الجديد، أو "" إن تعذر التجديد.
     */
    public static String renewBlocking(Context context) {
        if (context == null) return "";
        final Context app = context.getApplicationContext();
        synchronized (RENEW_LOCK) {
            if (!canRenew(app)) return "";
            long now = System.currentTimeMillis();
            if (lastFailureAt > 0L && now - lastFailureAt < FAILED_RETRY_MS) return "";

            SharedPreferences v = vault(app);
            String email = clean(v.getString("email", ""));
            String password = open(clean(v.getString("secret", "")));
            if (password == null || password.length() == 0) {
                forget(app);
                return "";
            }

            Result result = login(email, password, false);
            if (!result.ok && result.status != 401 && result.status != 403) {
                // ربما السيرفر يقبل JSON فقط.
                result = login(email, password, true);
            }
            if (!result.ok) {
                lastFailureAt = now;
                // كلمة المرور تغيرت أو الحساب حُذف: لا فائدة من الاحتفاظ بها.
                if (result.status == 401 || result.status == 403) forget(app);
                return "";
            }

            SharedPreferences.Editor editor = auth(app).edit()
                    .putBoolean("logged_in", true)
                    .putString("token", result.token)
                    .putString("expires_at", result.expiresAt);
            JSONObject user = result.user;
            if (user != null) {
                String id = clean(user.optString("id", ""));
                String name = clean(user.optString("name", ""));
                if (id.length() > 0) editor.putString("user_id", id);
                if (name.length() > 0) editor.putString("name", name);
            }
            editor.commit();
            lastFailureAt = 0L;
            return result.token;
        }
    }

    private static final class Result {
        boolean ok;
        int status;
        String token = "";
        String expiresAt = "";
        JSONObject user;
    }

    private static Result login(String email, String password, boolean asJson) {
        Result out = new Result();
        HttpURLConnection c = null;
        try {
            byte[] body;
            String type;
            if (asJson) {
                JSONObject json = new JSONObject();
                json.put("email", email);
                json.put("password", password);
                body = json.toString().getBytes("UTF-8");
                type = "application/json; charset=utf-8";
            } else {
                body = ("email=" + URLEncoder.encode(email, "UTF-8")
                        + "&password=" + URLEncoder.encode(password, "UTF-8")).getBytes("UTF-8");
                type = "application/x-www-form-urlencoded; charset=utf-8";
            }
            c = (HttpURLConnection) new URL(LOGIN_URL).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setUseCaches(false);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", type);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "Cigram-Android/1.0");
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            try { os.write(body); os.flush(); } finally { os.close(); }

            out.status = c.getResponseCode();
            InputStream in = out.status >= 200 && out.status < 300 ? c.getInputStream() : c.getErrorStream();
            String text = "";
            if (in != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                try {
                    while ((n = in.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                        if (bos.size() > 256 * 1024) break;
                    }
                } finally { in.close(); }
                text = bos.toString("UTF-8");
            }
            if (out.status < 200 || out.status >= 300 || text.trim().length() == 0) return out;
            JSONObject root = new JSONObject(text);
            if (!(root.optBoolean("success", false) || root.optBoolean("ok", false))) return out;
            out.token = clean(root.optString("token", ""));
            out.expiresAt = clean(root.optString("expires_at", ""));
            out.user = root.optJSONObject("user");
            out.ok = out.token.length() > 0;
        } catch (Throwable error) {
            android.util.Log.w("CigramSession", "silent login failed", error);
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Throwable ignored) { } }
        }
        return out;
    }

    // ----------------------------------------------------------------- helpers

    private static long expiresAtMs(Context context) {
        if (context == null) return 0L;
        try {
            Object raw = auth(context).getAll().get("expires_at");
            String s = raw == null ? "" : String.valueOf(raw).trim();
            if (s.length() == 0) return 0L;
            if (s.matches("\\d{9,13}")) {
                long n = Long.parseLong(s);
                return n < 100000000000L ? n * 1000L : n;
            }
            String[] patterns = {
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                    "yyyy-MM-dd'T'HH:mm:ssXXX",
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "yyyy-MM-dd HH:mm:ss"
            };
            for (String p : patterns) {
                try {
                    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(p, Locale.US);
                    if (p.endsWith("'Z'") || !p.contains("X")) f.setTimeZone(TimeZone.getTimeZone("UTC"));
                    java.util.Date d = f.parse(s);
                    if (d != null) return d.getTime();
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return 0L;
    }

    private static SharedPreferences auth(Context context) {
        return context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences vault(Context context) {
        return context.getSharedPreferences(VAULT_PREFS, Context.MODE_PRIVATE);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.Entry entry = store.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String seal(String plain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] data = cipher.doFinal(plain.getBytes("UTF-8"));
            return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP);
        } catch (Throwable error) {
            android.util.Log.w("CigramSession", "seal failed", error);
            return null;
        }
    }

    private static String open(String sealed) {
        try {
            int split = sealed.indexOf(':');
            if (split <= 0) return null;
            byte[] iv = Base64.decode(sealed.substring(0, split), Base64.NO_WRAP);
            byte[] data = Base64.decode(sealed.substring(split + 1), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(data), "UTF-8");
        } catch (Throwable error) {
            android.util.Log.w("CigramSession", "open failed", error);
            return null;
        }
    }
}
