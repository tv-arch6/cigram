package com.my.newproject;

import org.json.JSONObject;

/** Admin Worker calls with the unified { success, message, data, error_code } contract. */
final class CxApi {

    private CxApi() {}

    static final class Failure extends Exception {
        final String code;
        final JSONObject json;
        final int http;

        Failure(String message, String code, JSONObject json, int http) {
            super(message);
            this.code = code == null ? "" : code;
            this.json = json == null ? new JSONObject() : json;
            this.http = http;
        }

        boolean is(String c) {
            return code.equals(c);
        }

        JSONObject existing() {
            JSONObject o = json.optJSONObject("existing");
            return o == null ? new JSONObject() : o;
        }
    }

    /** HTTP codes worth asking again (Cloudflare busy / timeout / rate limit). */
    private static boolean transientCode(int code) {
        return code == 408 || code == 429 || code == 502 || code == 503 || code == 504 || (code >= 520 && code <= 524);
    }

    /**
     * Blocking. Returns the data object (or the whole body for legacy routes). Call from a background thread.
     * v1.2: a busy server (HTTP 502 / 503 / 504 / 429, or a dropped connection) is asked again by itself up to 4 times
     * (1.2 s, 2.5 s, 5 s). The Worker's writes are safe to repeat (a season commit updates what exists), so the admin
     * does not have to press the retry button for a passing hiccup.
     */
    static JSONObject call(String method, String path, JSONObject body) throws Failure {
        long[] waits = {1200L, 2500L, 5000L};
        int attempt = 0;
        while (true) {
            try {
                return callOnce(method, path, body);
            } catch (Failure f) {
                boolean again = attempt < waits.length && ("NETWORK".equals(f.code) || transientCode(f.http));
                if (!again) throw f;
                try {
                    Thread.sleep(waits[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw f;
                }
                attempt++;
            }
        }
    }

    private static JSONObject callOnce(String method, String path, JSONObject body) throws Failure {
        CgHttp.Res r = CgHttp.api(method, path, body);
        if (r.error != null) {
            throw new Failure(CgHttp.describe(r.error), "NETWORK", null, 0);
        }
        if (!r.ok() || (r.json != null && r.json.has("success") && !r.json.optBoolean("success", true))) {
            String code = r.json == null ? "" : r.json.optString("error_code", "");
            String msg = r.message();
            if (r.code == 404 && r.json != null && "not_found".equals(r.json.optString("error")) && r.json.has("path")) {
                code = "ROUTE_MISSING";
                msg = "هذه الميزة تحتاج النسخة الجديدة من الـ Worker. ارفع الملف الجديد إلى Cloudflare ثم أعد المحاولة.";
            } else if (r.code == 401) {
                code = "UNAUTHORIZED";
                msg = "رمز الإدارة غير صحيح. تأكد من ADMIN_TOKEN في الـ Worker.";
            } else if (transientCode(r.code) && (r.json == null || !r.json.has("message"))) {
                msg = "الخادم مشغول لحظياً (HTTP " + r.code + ") وجرّبنا عدة مرات. انتظر قليلاً ثم اضغط إعادة المحاولة. لم يُحفظ أي شيء.";
            }
            throw new Failure(CgHttp.friendly(msg), code, r.json, r.code);
        }
        if (r.json == null) {
            if (r.array != null) return CxJ.put(new JSONObject(), "items", r.array);
            throw new Failure("رد غير متوقع من الخادم. أعد المحاولة.", "BAD_RESPONSE", null, r.code);
        }
        JSONObject data = r.json.optJSONObject("data");
        if (data == null) return r.json;
        // keep the message and undo id reachable next to the data
        if (!data.has("message") && r.json.has("message")) CxJ.put(data, "message", r.json.optString("message"));
        return data;
    }

    static JSONObject get(String path) throws Failure {
        return call("GET", path, null);
    }

    static JSONObject post(String path, JSONObject body) throws Failure {
        return call("POST", path, body == null ? new JSONObject() : body);
    }
}
