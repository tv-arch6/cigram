package com.my.newproject;

import android.util.Log;

import org.json.JSONObject;

import java.net.URLEncoder;

/**
 * Blocking calls to the /media/upload routes of the admin Worker (init / part / status / complete /
 * abort / exists / verify / put). Same token and same response contract as CgUpdateApi, which is
 * what the APK uploader uses; that class is not touched.
 * Always call from a background thread.
 */
final class CgMediaApi {

    private CgMediaApi() {}

    static final String BASE = "/media/upload";

    static void log(String msg) {
        try {
            Log.d(CgMediaAnalyzer.TAG, msg);
        } catch (Throwable ignored) { }
    }

    private static String enc(String v) {
        try {
            return URLEncoder.encode(v == null ? "" : v, "UTF-8");
        } catch (Exception e) {
            return v == null ? "" : v;
        }
    }

    /** Identity part shared by init / exists / put. */
    static String identityQuery(String kind, String slug, String fileName, int season, int episode) {
        StringBuilder q = new StringBuilder();
        q.append("kind=").append(enc(kind));
        q.append("&slug=").append(enc(slug));
        q.append("&fileName=").append(enc(fileName));
        if ("episode".equals(kind)) {
            q.append("&season=").append(season);
            q.append("&episode=").append(episode);
        }
        return q.toString();
    }

    static JSONObject exists(String kind, String slug, String fileName, int season, int episode, boolean subtitle) throws CxApi.Failure {
        return CxApi.get(BASE + "?action=exists&" + identityQuery(kind, slug, fileName, season, episode) + (subtitle ? "&file=subtitle" : ""));
    }

    static JSONObject init(String kind, String slug, String fileName, long size, int season, int episode, String onExists) throws CxApi.Failure {
        JSONObject b = new JSONObject();
        CxJ.put(b, "kind", kind);
        CxJ.put(b, "slug", slug);
        CxJ.put(b, "fileName", fileName);
        CxJ.put(b, "size", Long.valueOf(size));
        if ("episode".equals(kind)) {
            CxJ.put(b, "season", Integer.valueOf(season));
            CxJ.put(b, "episode", Integer.valueOf(episode));
        }
        if ("overwrite".equals(onExists)) CxJ.put(b, "overwrite", Boolean.TRUE);
        if ("rename".equals(onExists)) CxJ.put(b, "rename", Boolean.TRUE);
        log("media init");
        return CxApi.post(BASE + "?action=init", b);
    }

    static String partUrl(String key, String uploadId, int partNumber) {
        return CgCfg.API + BASE + "?action=part&key=" + enc(key) + "&uploadId=" + enc(uploadId) + "&partNumber=" + partNumber;
    }

    static JSONObject status(String key, String uploadId) throws CxApi.Failure {
        return CxApi.get(BASE + "?action=status&key=" + enc(key) + "&uploadId=" + enc(uploadId));
    }

    static JSONObject complete(JSONObject body) throws CxApi.Failure {
        log("media complete");
        return CxApi.post(BASE + "?action=complete", body);
    }

    static void abort(String key, String uploadId) {
        try {
            JSONObject b = new JSONObject();
            CxJ.put(b, "key", key);
            CxJ.put(b, "uploadId", uploadId);
            CxApi.post(BASE + "?action=abort", b);
        } catch (Exception e) {
            log("media abort failed: " + e.getMessage());
        }
    }

    static JSONObject verify(String key, long size) throws CxApi.Failure {
        return CxApi.get(BASE + "?action=verify&key=" + enc(key) + "&size=" + size);
    }

    /** Small subtitle upload (the whole file in one request). */
    static JSONObject putSubtitle(String kind, String slug, String fileName, int season, int episode, String onExists, byte[] data) throws CxApi.Failure {
        String url = CgCfg.API + BASE + "?action=put&" + identityQuery(kind, slug, fileName, season, episode)
                + ("overwrite".equals(onExists) ? "&overwrite=1" : "") + ("rename".equals(onExists) ? "&rename=1" : "");
        CgHttp.Res r = CgHttp.postBytes(url, data, "application/octet-stream", true, 60000);
        if (r.error != null) throw new CxApi.Failure(CgHttp.describe(r.error), "NETWORK", null, 0);
        boolean bad = !r.ok() || (r.json != null && r.json.has("success") && !r.json.optBoolean("success", true));
        if (bad) {
            String code = r.json == null ? "" : r.json.optString("error_code", "");
            String msg = r.message();
            if (r.code == 401) {
                code = "UNAUTHORIZED";
                msg = "رمز الإدارة غير صحيح. تأكد من ADMIN_TOKEN في الـ Worker.";
            } else if (r.code == 404 && r.json != null && "not_found".equals(r.json.optString("error"))) {
                code = "ROUTE_MISSING";
                msg = "هذه الميزة تحتاج النسخة الجديدة من الـ Worker. ارفع الملف الجديد إلى Cloudflare ثم أعد المحاولة.";
            }
            throw new CxApi.Failure(CgHttp.friendly(msg), code, r.json, r.code);
        }
        if (r.json == null) throw new CxApi.Failure("رد غير متوقع من الخادم. أعد المحاولة.", "BAD_RESPONSE", null, r.code);
        JSONObject data2 = r.json.optJSONObject("data");
        return data2 == null ? r.json : data2;
    }
}
