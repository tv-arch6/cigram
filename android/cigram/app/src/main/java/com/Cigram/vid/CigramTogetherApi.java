package com.Cigram.vid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * اتصال HTTP بخدمة "شاهد مع صديقك" (cigram-together-api).
 * - كل الشبكة خارج الخيط الرئيسي والنتيجة تعود عليه. لا استثناءات تصل للمستدعي.
 * - يرسل رمز الحساب الحالي فقط في ترويسة Authorization (لا يُسجَّل ولا يُطبع).
 * - يميّز 401 (رمز مرفوض فعلاً) عن 503/انقطاع الشبكة: لا شيء هنا يمسح جلسة المستخدم أبداً.
 */
public final class CigramTogetherApi {

    /** عنوان خدمة الغرف بعد نشرها (انظر together-worker/DEPLOY.md). فارغ = الميزة غير مفعّلة. */
    public static final String BASE = "https://cigram-together-api.wwq-mixtv.workers.dev";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = CigramPool.fixed(2, "together");
    private static final int MAX_BODY = 256 * 1024;

    private CigramTogetherApi() { }

    public interface Callback { void onResult(Result r); }

    public static final class Result {
        public boolean ok;
        public int code;
        public JSONObject json = new JSONObject();
        public String error = "";
        public String message = "";
        public boolean offline;
        /** الرمز مرفوض فعلاً (401/403 من السيرفر) — الوحيد الذي يعني "أعد تسجيل الدخول". */
        public boolean authRejected() { return code == 401 && ("invalid_token".equals(error) || "login_required".equals(error)); }
    }

    public static boolean enabled() { return BASE.startsWith("https://"); }

    public static void get(Context c, String path, Callback cb) { run(c, "GET", path, null, null, cb); }

    public static void post(Context c, String path, JSONObject body, String idemKey, Callback cb) {
        run(c, "POST", path, body == null ? new JSONObject() : body, idemKey, cb);
    }

    private static void run(Context c, final String method, final String path, final JSONObject body,
                            final String idem, final Callback cb) {
        final String token = CigramUserData.token(c.getApplicationContext());
        POOL.execute(new Runnable() {
            @Override public void run() {
                final Result r = request(method, path, body == null ? null : body.toString(), token, idem);
                if (cb == null) return;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        try { cb.onResult(r); } catch (Throwable t) { android.util.Log.w("CigramTogetherApi", "callback failed", t); }
                    }
                });
            }
        });
    }

    static Result request(String method, String path, String json, String bearer, String idem) {
        Result r = new Result();
        HttpURLConnection conn = null;
        try {
            if (!enabled()) { r.offline = true; r.message = "ميزة المشاهدة مع صديق غير مفعّلة حالياً."; return r; }
            conn = (HttpURLConnection) new URL(BASE + path).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json");
            if (bearer != null && bearer.length() > 0) conn.setRequestProperty("Authorization", "Bearer " + bearer);
            if (idem != null && idem.length() > 0) conn.setRequestProperty("Idempotency-Key", idem);
            if (json != null) {
                byte[] data = json.getBytes("UTF-8");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setFixedLengthStreamingMode(data.length);
                OutputStream os = conn.getOutputStream();
                os.write(data);
                os.flush();
                os.close();
            }
            r.code = conn.getResponseCode();
            InputStream in = r.code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in == null ? "" : readAll(in);
            try { r.json = text.length() == 0 ? new JSONObject() : new JSONObject(text); } catch (Throwable parse) { r.json = new JSONObject(); }
            r.ok = r.code >= 200 && r.code < 300 && r.json.optBoolean("ok", true);
            r.error = r.json.optString("error", "");
            r.message = r.json.optString("message", "");
            if (!r.ok && r.message.length() == 0) r.message = friendly(r.code, r.error);
        } catch (UnknownHostException e) {
            r.offline = true;
            r.message = "لا يوجد اتصال بالإنترنت.";
        } catch (SocketTimeoutException e) {
            r.offline = true;
            r.message = "انتهت مهلة الاتصال. حاول مرة أخرى.";
        } catch (Throwable e) {
            r.offline = true;
            r.message = "تعذر الاتصال بخدمة المشاهدة المشتركة.";
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) { } }
        }
        return r;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY) break;
        }
        in.close();
        return out.toString("UTF-8");
    }

    static String friendly(int code, String error) {
        if ("room_full".equals(error)) return "الغرفة مكتملة.";
        if ("banned".equals(error)) return "لا يمكنك الانضمام لهذه الغرفة.";
        if ("code_expired".equals(error) || "bad_code".equals(error)) return "رمز الدعوة غير صحيح أو منتهٍ.";
        if ("room_closed".equals(error)) return "أُغلقت الغرفة.";
        if (code == 401) return "انتهت الجلسة. سجّل الدخول من جديد.";
        if (code == 429) return "محاولات كثيرة. انتظر قليلاً.";
        if (code == 503) return "الخدمة غير متاحة حالياً. حاول بعد قليل.";
        if (code >= 500) return "الخادم مشغول. حاول بعد قليل.";
        return "تعذر إكمال الطلب.";
    }
}
