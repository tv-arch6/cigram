package com.my.newproject;

import org.json.JSONObject;

/**
 * "What's New?" Worker calls (/admin/discover/*). Same unified contract as CxApi ({ success, message, data, error_code }),
 * with a per-call timeout (a sync or a video probe can take longer than the 30 s default) and automatic, safe retries:
 * every POST here is either read-only, idempotent, or carries a request_id the Worker replays.
 *
 * All methods block. Call them from a background thread (CgHttp.bg / CgHttp.async).
 */
final class WnApi {

    private WnApi() { }

    /** The transport. Swappable so the pipeline can be tested against a local Worker or a fake. */
    interface Backend {
        /** Returns the "data" object (or the whole body for legacy answers); throws CxApi.Failure otherwise. */
        JSONObject call(String method, String path, JSONObject body, int timeoutMs) throws CxApi.Failure;
    }

    private static volatile Backend backend = new HttpBackend(null, null);

    static void use(Backend b) {
        backend = b == null ? new HttpBackend(null, null) : b;
    }

    static final class HttpBackend implements Backend {
        private final String base;
        private final String token;

        /** null = the production Worker URL / the build-time admin token. */
        HttpBackend(String baseUrl, String adminToken) {
            this.base = baseUrl == null ? CgCfg.API : baseUrl;
            this.token = adminToken;
        }

        @Override
        public JSONObject call(String method, String path, JSONObject body, int timeoutMs) throws CxApi.Failure {
            long[] waits = {1200L, 2500L};
            int attempt = 0;
            while (true) {
                try {
                    return once(method, path, body, timeoutMs);
                } catch (CxApi.Failure f) {
                    boolean again = attempt < waits.length && ("NETWORK".equals(f.code) || transientHttp(f.http));
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

        private static boolean transientHttp(int code) {
            return code == 408 || code == 429 || code == 502 || code == 503 || code == 504 || (code >= 520 && code <= 524);
        }

        private JSONObject once(String method, String path, JSONObject body, int timeoutMs) throws CxApi.Failure {
            String[][] headers = null;
            if (token != null) headers = new String[][]{{"Authorization", "Bearer " + token}, {"X-Admin-Token", token}};
            CgHttp.Res r = CgHttp.call(method, base + path, body, token == null, timeoutMs, headers);
            if (r.error != null) throw new CxApi.Failure(CgHttp.describe(r.error), "NETWORK", null, 0);
            boolean softFail = r.json != null && r.json.has("success") && !r.json.optBoolean("success", true);
            if (!r.ok() || softFail) {
                String code = r.json == null ? "" : r.json.optString("error_code", "");
                String msg = r.message();
                if (r.code == 404 && r.json != null && "not_found".equals(r.json.optString("error")) && r.json.has("path")) {
                    code = "ROUTE_MISSING";
                    msg = "ميزة «ما الجديد؟» تحتاج النسخة الجديدة من الـ Worker. ارفع الملف الجديد إلى Cloudflare ثم أعد المحاولة.";
                } else if (r.code == 401) {
                    code = "UNAUTHORIZED";
                    msg = "رمز الإدارة غير صحيح. تأكد من ADMIN_TOKEN في الـ Worker ومن الرمز المبني داخل التطبيق.";
                } else if (r.code == 429 && (r.json == null || !r.json.has("message"))) {
                    msg = "طلبات كثيرة جداً. انتظر لحظات ثم أعد المحاولة.";
                }
                throw new CxApi.Failure(CgHttp.friendly(msg), code, r.json, r.code);
            }
            if (r.json == null) throw new CxApi.Failure("رد غير متوقع من الخادم. أعد المحاولة.", "BAD_RESPONSE", null, r.code);
            JSONObject data = r.json.optJSONObject("data");
            return data == null ? r.json : data;
        }
    }

    // ------------------------------------------------------------------ typed calls

    private static JSONObject get(String path, int timeoutMs) throws CxApi.Failure {
        return backend.call("GET", path, null, timeoutMs);
    }

    private static JSONObject post(String path, JSONObject body, int timeoutMs) throws CxApi.Failure {
        return backend.call("POST", path, body == null ? new JSONObject() : body, timeoutMs);
    }

    static JSONObject summary() throws CxApi.Failure { return get("/admin/discover/summary", 20000); }

    static JSONObject config() throws CxApi.Failure { return get("/admin/discover/config", 20000); }

    static JSONObject saveConfig(JSONObject patch) throws CxApi.Failure { return post("/admin/discover/config", patch, 20000); }

    static JSONObject feed(JSONObject req) throws CxApi.Failure { return post("/admin/discover/feed", req, 45000); }

    static JSONObject genres() throws CxApi.Failure { return get("/admin/discover/genres", 20000); }

    static JSONObject details(String type, long tmdb) throws CxApi.Failure {
        JSONObject b = new JSONObject();
        CxJ.put(b, "type", type);
        CxJ.put(b, "tmdb_id", tmdb);
        return post("/admin/discover/details", b, 45000);
    }

    static JSONObject season(long tmdb, int season) throws CxApi.Failure {
        JSONObject b = new JSONObject();
        CxJ.put(b, "tmdb_id", tmdb);
        CxJ.put(b, "season", season);
        return post("/admin/discover/season", b, 45000);
    }

    /** type "movie" | "series"; season/episodes for series (episodes may be null = every episode of the season). */
    static JSONObject video(JSONObject req) throws CxApi.Failure { return post("/admin/discover/video", req, 60000); }

    static JSONObject subtitles(JSONObject req) throws CxApi.Failure { return post("/admin/discover/subtitles", req, 60000); }

    static JSONObject subtitleFetch(JSONObject req) throws CxApi.Failure { return post("/admin/discover/subtitles/fetch", req, 60000); }

    static JSONObject subtitleValidate(JSONObject req) throws CxApi.Failure { return post("/admin/discover/subtitles/validate", req, 45000); }

    static JSONObject validate(JSONObject plan) throws CxApi.Failure { return post("/admin/discover/validate", plan, 60000); }

    static JSONObject importPlan(JSONObject plan) throws CxApi.Failure { return post("/admin/discover/import", plan, 90000); }

    static JSONObject sync(int enrich) throws CxApi.Failure {
        JSONObject b = new JSONObject();
        CxJ.put(b, "enrich", enrich);
        return post("/admin/discover/sync", b, 120000);
    }

    static JSONObject enrich(int limit, String runId) throws CxApi.Failure {
        JSONObject b = new JSONObject();
        CxJ.put(b, "limit", limit);
        if (runId != null && runId.length() > 0) CxJ.put(b, "run_id", runId);
        return post("/admin/discover/enrich", b, 90000);
    }

    static JSONObject collection(long id) throws CxApi.Failure {
        JSONObject b = new JSONObject();
        CxJ.put(b, "id", id);
        return post("/admin/discover/collection", b, 45000);
    }

    static JSONObject history() throws CxApi.Failure { return get("/admin/discover/history?limit=40", 20000); }
}
