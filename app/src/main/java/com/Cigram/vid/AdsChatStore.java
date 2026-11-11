package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * نسخة محلية من آخر رسائل المحادثة (للعرض الفوري ودون اتصال) + طابور الرسائل التي لم تُرسَل بعد.
 * التخزين مشفّر AES-256-GCM بمفتاح داخل Android Keystore (لا يخرج من الجهاز). إن تعذّر Keystore
 * لأي سبب لا نخزّن شيئاً (نعمل بالاتصال فقط) بدل أن نكتب نصاً صريحاً.
 */
public final class AdsChatStore {

    private static final String PREFS = "cigram_ads_chat";
    private static final String ALIAS = "cigram_ads_chat_key_v1";
    private static final int KEEP = 60;

    private AdsChatStore() { }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            KeyStore.SecretKeyEntry e = (KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null);
            if (e != null) return e.getSecretKey();
        }
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    private static String seal(String plain) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes("UTF-8"));
            return Base64.encodeToString(iv, Base64.NO_WRAP) + "." + Base64.encodeToString(ct, Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String open(String sealed) {
        try {
            int dot = sealed.indexOf('.');
            if (dot < 0) return null;
            byte[] iv = Base64.decode(sealed.substring(0, dot), Base64.NO_WRAP);
            byte[] ct = Base64.decode(sealed.substring(dot + 1), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String owner(Context c) {
        try {
            String id = CigramUserData.authUserId(c);
            String e = CigramUserData.email(c);
            return (id == null ? "" : id) + "|" + (e == null ? "" : e);
        } catch (Throwable t) {
            return "";
        }
    }

    /** يقرأ { messages: [...], pending: [...] } أو كائناً فارغاً. */
    public static JSONObject load(Context c) {
        JSONObject empty = new JSONObject();
        try {
            empty.put("messages", new JSONArray());
            empty.put("pending", new JSONArray());
            SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String sealed = p.getString("data", "");
            if (sealed == null || sealed.length() == 0) return empty;
            String plain = open(sealed);
            if (plain == null) return empty;
            JSONObject o = new JSONObject(plain);
            if (!owner(c).equals(o.optString("owner"))) return empty; // حساب آخر على نفس الجهاز
            if (o.optJSONArray("messages") == null) o.put("messages", new JSONArray());
            if (o.optJSONArray("pending") == null) o.put("pending", new JSONArray());
            return o;
        } catch (Throwable t) {
            return empty;
        }
    }

    public static void save(Context c, JSONArray messages, JSONArray pending) {
        try {
            JSONArray keep = new JSONArray();
            int from = Math.max(0, messages.length() - KEEP);
            for (int i = from; i < messages.length(); i++) {
                JSONObject m = new JSONObject(messages.getJSONObject(i).toString());
                JSONObject media = m.optJSONObject("media");
                if (media != null) media.remove("url"); // الروابط الموقّعة تنتهي، نطلب جديدة عند الفتح
                keep.put(m);
            }
            JSONObject o = new JSONObject();
            o.put("owner", owner(c));
            o.put("messages", keep);
            o.put("pending", pending == null ? new JSONArray() : pending);
            String sealed = seal(o.toString());
            SharedPreferences.Editor e = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            if (sealed == null) e.remove("data"); else e.putString("data", sealed);
            e.apply();
        } catch (Throwable ignored) {
            // التخزين المحلي اختياري
        }
    }

    public static void clear(Context c) {
        try {
            c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        } catch (Throwable ignored) { }
    }

    /** آخر رقم رسالة أُشعر به المستخدم (لمنع تكرار الإشعارات). */
    public static long lastNotified(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS + "_n", Context.MODE_PRIVATE).getLong("seq", 0L);
    }

    public static void setLastNotified(Context c, long seq) {
        c.getApplicationContext().getSharedPreferences(PREFS + "_n", Context.MODE_PRIVATE).edit().putLong("seq", seq).apply();
    }

    public static boolean keystoreAvailable() {
        return Build.VERSION.SDK_INT >= 23;
    }
}
