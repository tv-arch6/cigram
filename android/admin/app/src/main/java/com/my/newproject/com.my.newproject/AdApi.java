package com.my.newproject;

import android.app.Activity;

import org.json.JSONObject;

import java.util.Locale;

/**
 * اتصال تطبيق الأدمن بـ Worker الإعلانات (cigram-ads-api). يستخدم نفس رمز الأدمن CgCfg.TOKEN
 * (يجب أن يكون السر ADMIN_TOKEN في Worker الإعلانات مساوياً له). كل الشبكة في خيوط خلفية.
 */
final class AdApi {

    private AdApi() {}

    /** عنوان Worker الإعلانات. غيّره إذا استخدمت اسماً مختلفاً عند النشر (انظر docs/DEPLOY.md). */
    static final String BASE = "https://cigram-ads-api.wwq-mixtv.workers.dev";

    static final class Result {
        boolean ok;
        int code;
        JSONObject json = new JSONObject();
        String error = "";
        String message = "";
        boolean offline;
    }

    interface Cb {
        void on(Result r);
    }

    static Result request(String method, String path, String json, String ignoredBearer) {
        Result r = new Result();
        try {
            JSONObject body = json == null ? null : new JSONObject(json);
            CgHttp.Res res = CgHttp.call(method, BASE + path, body, true, 30000, null);
            r.code = res.code;
            r.ok = res.ok();
            if (res.json != null) r.json = res.json;
            r.error = res.errorCode();
            r.offline = res.error != null;
            r.message = r.ok ? "" : arabicError(r.code, r.error, res.message(), r.offline);
        } catch (Exception e) {
            r.offline = true;
            r.message = CgHttp.friendly(e.getMessage());
        }
        return r;
    }

    /** رسالة عربية مقروءة لكل حالة خطأ؛ لا تظهر null/undefined/أسماء استثناءات للمدير. */
    static String arabicError(int code, String error, String raw, boolean offline) {
        String m = raw == null ? "" : raw.trim();
        String low = m.toLowerCase(Locale.US);
        if (low.equals("null") || low.equals("undefined") || low.contains("exception") || low.contains("java.")) m = "";
        if (offline) return m.length() > 0 ? CgHttp.friendly(m) : "لا يوجد اتصال بخادم الإعلانات. تحقق من الإنترنت ثم أعد المحاولة.";
        if (code == 400) return m.length() > 0 ? m : "بيانات غير صالحة. راجع الحقول ثم أعد المحاولة.";
        if (code == 401) return "رفض خادم الإعلانات التفويض (401). تأكد أن ADMIN_TOKEN في Worker الإعلانات يساوي رمز الأدمن.";
        if (code == 403) return "غير مسموح بهذا الإجراء (403).";
        if (code == 404) return m.length() > 0 ? m : "العنصر غير موجود أو حُذف.";
        if (code == 408) return "انتهت مهلة الطلب. أعد المحاولة.";
        if (code == 409) return m.length() > 0 ? m : "تعارض: تغيّر العنصر على الخادم. حدّث الصفحة ثم أعد المحاولة.";
        if (code == 429) return "طلبات كثيرة خلال وقت قصير. انتظر قليلاً ثم أعد المحاولة.";
        if (code >= 500) return "الخادم مشغول أو واجه خطأ (" + code + "). أعد المحاولة بعد قليل.";
        return m.length() > 0 ? m : "تعذر إكمال الطلب.";
    }

    static void run(final Activity a, final String method, final String path, final JSONObject body, final Cb cb) {
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                final Result r = request(method, path, body == null ? null : body.toString(), null);
                if (a == null || a.isFinishing() || cb == null) return;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!a.isFinishing()) cb.on(r);
                    }
                });
            }
        });
    }

    static void get(Activity a, String path, Cb cb) {
        run(a, "GET", path, null, cb);
    }

    static void post(Activity a, String path, JSONObject body, Cb cb) {
        run(a, "POST", path, body == null ? new JSONObject() : body, cb);
    }

    static String enc(String s) {
        try { return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    // ---------------------------------------------------------------- تسميات

    static String stageLabel(String s) {
        if ("received".equals(s)) return "مستلم";
        if ("review".equals(s)) return "قيد المراجعة";
        if ("quoted".equals(s)) return "عرض سعر";
        if ("awaiting_payment".equals(s)) return "بانتظار الدفع";
        if ("scheduled".equals(s)) return "مجدول";
        if ("running".equals(s)) return "يعمل";
        if ("ended".equals(s)) return "منتهي";
        if ("rejected".equals(s)) return "مرفوض";
        if ("paused".equals(s)) return "متوقف";
        return s == null ? "" : s;
    }

    static int stageColor(String s) {
        if ("running".equals(s)) return CgCfg.GOOD;
        if ("rejected".equals(s)) return CgCfg.BAD;
        if ("paused".equals(s) || "awaiting_payment".equals(s) || "quoted".equals(s)) return CgCfg.WARN;
        if ("ended".equals(s)) return CgCfg.GRAY;
        return CgCfg.ACCENT;
    }

    static String threadStatusLabel(String s) {
        if ("open".equals(s)) return "مفتوحة";
        if ("pending".equals(s)) return "بانتظار المعلن";
        if ("quoted".equals(s)) return "عرض سعر";
        if ("won".equals(s)) return "تم الاتفاق";
        if ("closed".equals(s)) return "مغلقة";
        return s == null ? "" : s;
    }

    static String slotLabel(JSONObject settings, String slot) {
        try {
            JSONObject s = settings == null ? null : settings.optJSONObject("slots");
            JSONObject o = s == null ? null : s.optJSONObject(slot);
            if (o != null && o.optString("label").length() > 0) return o.optString("label");
        } catch (Throwable ignored) { }
        return slot;
    }

    static String money(double v, String currency) {
        String n = (v == Math.floor(v)) ? String.valueOf((long) v) : String.format(Locale.US, "%.2f", v);
        return n + " " + (currency == null || currency.length() == 0 ? "USD" : currency);
    }

    static String date(long ms) {
        if (ms <= 0) return "—";
        return new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(new java.util.Date(ms));
    }
}
