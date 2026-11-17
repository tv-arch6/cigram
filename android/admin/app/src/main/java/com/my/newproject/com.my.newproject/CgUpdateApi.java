package com.my.newproject;

import android.app.Activity;
import android.util.Log;

import org.json.JSONObject;

import java.net.URLEncoder;

/**
 * Blocking calls to the update endpoints of the admin Worker. Always call from a background
 * thread (CgHttp.async). Every method returns the `data` object or throws CxApi.Failure with an
 * Arabic message that is safe to show. Nothing sensitive is logged.
 */
final class CgUpdateApi {

    private CgUpdateApi() {}

    static final String TAG = "CigramUpdate";

    static void log(String msg) {
        try {
            Log.d(TAG, msg);
        } catch (Throwable ignored) { }
    }

    static JSONObject history() throws CxApi.Failure {
        log("load history");
        return CxApi.get("/update/history");
    }

    static JSONObject stats() throws CxApi.Failure {
        return CxApi.get("/update/stats");
    }

    /** POST /publish-update — mode "now" publishes, mode "schedule" schedules. */
    static JSONObject publish(JSONObject body) throws CxApi.Failure {
        log("publish mode=" + body.optString("mode") + " code=" + body.optString("versionCode"));
        return CxApi.post("/publish-update", body);
    }

    static JSONObject saveDraft(JSONObject body) throws CxApi.Failure {
        log("save draft");
        return CxApi.post("/update/draft", CxJ.put(body, "op", "save"));
    }

    static JSONObject deleteDraft(String id) throws CxApi.Failure {
        log("delete draft");
        JSONObject b = new JSONObject();
        CxJ.put(b, "op", "delete");
        CxJ.put(b, "id", id);
        return CxApi.post("/update/draft", b);
    }

    static JSONObject stop(String reason) throws CxApi.Failure {
        log("stop update");
        JSONObject b = new JSONObject();
        CxJ.put(b, "reason", reason);
        return CxApi.post("/update/stop", b);
    }

    static JSONObject cancelSchedule(String id) throws CxApi.Failure {
        log("cancel schedule");
        JSONObject b = new JSONObject();
        CxJ.put(b, "id", id);
        return CxApi.post("/update/schedule/cancel", b);
    }

    static JSONObject emergency(String action, JSONObject extra) throws CxApi.Failure {
        log("emergency " + action);
        JSONObject b = extra == null ? new JSONObject() : extra;
        CxJ.put(b, "action", action);
        CxJ.put(b, "confirm", Boolean.TRUE);
        return CxApi.post("/update/emergency", b);
    }

    static JSONObject resendPush(String id) throws CxApi.Failure {
        log("resend push");
        JSONObject b = new JSONObject();
        CxJ.put(b, "id", id);
        return CxApi.post("/update/push", b);
    }

    static JSONObject validate(String url, long versionCode, String versionName) throws CxApi.Failure {
        log("validate link");
        JSONObject b = new JSONObject();
        CxJ.put(b, "url", url);
        if (versionCode > 0) CxJ.put(b, "versionCode", Long.valueOf(versionCode));
        if (versionName != null && versionName.length() > 0) CxJ.put(b, "versionName", versionName);
        return CxApi.post("/update/validate", b);
    }

    static JSONObject uploadInit(String fileName, long size, String versionName) throws CxApi.Failure {
        log("upload init");
        JSONObject b = new JSONObject();
        CxJ.put(b, "fileName", fileName);
        CxJ.put(b, "size", Long.valueOf(size));
        CxJ.put(b, "versionName", versionName == null ? "" : versionName);
        return CxApi.post("/update/upload?action=init", b);
    }

    static JSONObject uploadComplete(JSONObject body) throws CxApi.Failure {
        log("upload complete");
        return CxApi.post("/update/upload?action=complete", body);
    }

    static void uploadAbort(String key, String uploadId) {
        try {
            JSONObject b = new JSONObject();
            CxJ.put(b, "key", key);
            CxJ.put(b, "uploadId", uploadId);
            CxApi.post("/update/upload?action=abort", b);
        } catch (Exception ignored) { }
    }

    static String partUrl(String key, String uploadId, int partNumber) {
        try {
            return CgCfg.API + "/update/upload?action=part&key=" + URLEncoder.encode(key, "UTF-8")
                    + "&uploadId=" + URLEncoder.encode(uploadId, "UTF-8") + "&partNumber=" + partNumber;
        } catch (Exception e) {
            return CgCfg.API + "/update/upload?action=part&key=" + key + "&uploadId=" + uploadId + "&partNumber=" + partNumber;
        }
    }

    // ---------------------------------------------------------------- async

    interface Work<T> {
        T run() throws CxApi.Failure;
    }

    interface Done<T> {
        void ok(T result);

        void fail(CxApi.Failure failure);
    }

    /**
     * Runs a blocking call off the UI thread and delivers the result (or the Failure, with its
     * server `field` / `error_code`) back on the UI thread. Results are dropped if the screen is gone.
     */
    static <T> void async(final Activity a, final Work<T> work, final Done<T> done) {
        CgHttp.bg(new Runnable() {
            @Override
            public void run() {
                T result = null;
                CxApi.Failure failure = null;
                try {
                    result = work.run();
                } catch (CxApi.Failure f) {
                    failure = f;
                } catch (Throwable t) {
                    failure = new CxApi.Failure("تعذر إكمال الطلب. أعد المحاولة.", "UNKNOWN", null, 0);
                }
                final T fr = result;
                final CxApi.Failure ff = failure;
                if (a.isFinishing()) return;
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (a.isFinishing()) return;
                        if (ff != null) done.fail(ff);
                        else done.ok(fr);
                    }
                });
            }
        });
    }
}
