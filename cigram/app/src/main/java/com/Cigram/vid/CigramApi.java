package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v10.62: اتصال صغير بخدمة cigram-user-api للميزات الجديدة (الطلبات، المقدمة، الروابط).
 * كل الدوال تعمل على خيط خلفي وتعيد null عند أي فشل — لا شيء هنا يرمي استثناءً للمستدعي.
 * إذا لم يُنشر تحديث الخادم (v2.4) بعد، فالمسارات الجديدة ترد 404 والتطبيق يكمل بالطريقة القديمة.
 */
public final class CigramApi {

    public static final String BASE = CigramSync.API_BASE;
    public static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static final String PREFS = "cigram_api_v1";
    private static final String KEY_FEATURES = "features";
    private static final String KEY_FEATURES_AT = "features_at";
    private static final long FEATURES_TTL_MS = 6L * 60L * 60L * 1000L;

    private CigramApi() { }

    /** Blocking. Call from a background thread only. */
    public static JSONObject get(String path) {
        return call("GET", path, null, null);
    }

    /** Blocking. Call from a background thread only. */
    public static JSONObject post(String path, JSONObject body, String token) {
        return call("POST", path, body, token);
    }

    private static JSONObject call(String method, String path, JSONObject body, String token) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASE + path).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Accept", "application/json");
            if (token != null && token.length() > 0) c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] bytes = body.toString().getBytes("UTF-8");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(bytes.length);
                OutputStream out = c.getOutputStream();
                out.write(bytes);
                out.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(chunk)) > 0) {
                total += n;
                if (total > 512 * 1024) break;
                buf.write(chunk, 0, n);
            }
            in.close();
            JSONObject o = new JSONObject(buf.toString("UTF-8"));
            o.put("_http", code);
            return o;
        } catch (Throwable error) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------ server capabilities

    /** True when the deployed Worker announces the feature (v2.4: requests, intro, links). Never blocks. */
    public static boolean has(Context context, String feature) {
        if (context == null || feature == null) return false;
        try {
            String raw = prefs(context).getString(KEY_FEATURES, "");
            return raw != null && ("," + raw + ",").contains("," + feature + ",");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Re-reads the Worker's feature list at most every 6 hours (background). */
    public static void refreshFeatures(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        long at = prefs(app).getLong(KEY_FEATURES_AT, 0L);
        if (System.currentTimeMillis() - at < FEATURES_TTL_MS && at > 0L) return;
        IO.execute(new Runnable() {
            @Override public void run() {
                JSONObject o = get("/");
                if (o == null || !o.optBoolean("ok", false)) return;
                StringBuilder b = new StringBuilder();
                org.json.JSONArray f = o.optJSONArray("features");
                for (int i = 0; f != null && i < f.length(); i++) {
                    if (b.length() > 0) b.append(',');
                    b.append(f.optString(i, ""));
                }
                prefs(app).edit().putString(KEY_FEATURES, b.toString())
                        .putLong(KEY_FEATURES_AT, System.currentTimeMillis()).apply();
            }
        });
    }

    /** Random id of this installation (same idea as CigramReport.clientId, separate store). */
    public static String clientId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("client_id", "");
        if (id == null || id.length() == 0) {
            id = java.util.UUID.randomUUID().toString().replace("-", "");
            p.edit().putString("client_id", id).apply();
        }
        return id;
    }

    public static String appVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static SharedPreferences prefs(Context c) {
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
