package com.my.newproject;

import android.content.Context;

import org.json.JSONObject;

/** Calls to the cigram-plus-api worker with the admin token (it still serves the external link; there is no subscription any more). */
final class PlusApi {

    static final String BASE = "https://cigram-plus-api.wwq-mixtv.workers.dev";

    private PlusApi() { }

    static String key(Context c) {
        return CgCfg.TOKEN;
    }

    static CgHttp.Res call(Context c, String method, String path, JSONObject body) {
        return CgHttp.call(method, BASE + path, body, false, 30000, new String[][]{{"X-Admin-Key", key(c)}});
    }

    /** An understandable Arabic reason for a failed call. */
    static String explain(CgHttp.Res r) {
        if (r.error != null) return r.message();
        if (r.code == 401) return "مفتاح الإدارة غير صحيح.";
        if (r.code == 429) return "محاولات كثيرة بمفتاح خاطئ. انتظر 10 دقائق.";
        if (r.code == 501) return "الـ worker غير مهيأ بعد (ADMIN_KEY ناقص).";
        if (r.code == 404) return "الـ worker cigram-plus-api غير منشور بعد، أو ينقصه التحديث الجديد (مسار /admin/app/external-link).";
        String e = r.json == null ? "" : r.json.optString("error", "");
        if ("link_required".equals(e)) return "أضف رابط تحميل أو رابط قناة قبل التفعيل.";
        if ("bad_app_url".equals(e)) return "رابط التحميل يجب أن يبدأ بـ https://";
        if ("bad_channel_url".equals(e)) return "رابط القناة يجب أن يبدأ بـ https://";
        if ("bad_icon_url".equals(e)) return "رابط الأيقونة يجب أن يبدأ بـ https://";
        return r.message();
    }
}
