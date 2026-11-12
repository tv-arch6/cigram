package com.my.newproject;

import android.content.Context;

import com.Cigram.subhub.SubQueue;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Client side of the Worker's OpenSubtitles AI translation routes (18.2, see docs/backend):
 * {@code GET /admin/subhub/translate/options}, {@code POST .../estimate}, {@code POST /admin/subhub/translate},
 * {@code GET .../translate/status}, {@code POST .../translate/batch} (a spending envelope).
 *
 * Nothing here spends money by itself: every call that can cost credits carries an explicit approval (who, when,
 * maximum credits) that the admin typed on screen, and the Worker refuses it when the estimate is higher.
 * All methods block: call them from a background thread.
 */
final class SubTranslate {

    private SubTranslate() {}

    /** One translation service of the provider's own catalogue (price in credits per character). */
    static final class Api {
        final String name;
        final String label;
        final double price;
        final boolean arabic;

        Api(String name, String label, double price, boolean arabic) {
            this.name = name;
            this.label = label;
            this.price = price;
            this.arabic = arabic;
        }

        String describe() {
            return label + " — " + String.format(Locale.US, "%.4f", price) + " رصيد/حرف" + (arabic ? "" : " — لا تدعم العربية");
        }
    }

    /** Live catalogue; only services that support Arabic are returned. */
    static List<Api> options() throws CxApi.Failure {
        JSONObject r = CxApi.get("/admin/subhub/translate/options");
        JSONArray rows = r.optJSONArray("apis");
        List<Api> out = new ArrayList<Api>();
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject a = rows.optJSONObject(i);
            if (a == null || !a.optBoolean("supports_ar")) continue;
            out.add(new Api(a.optString("name"), a.optString("label", a.optString("name")), a.optDouble("price", 0), true));
        }
        return out;
    }

    /** Exact upper bound of the credits for one source text with one service (the Worker counts the whole file). */
    static JSONObject estimate(String apiName, String sourceText) throws Exception {
        JSONObject r = CxApi.post("/admin/subhub/translate/estimate", new JSONObject().put("api", apiName).put("source_file", sourceText));
        return r.optJSONObject("estimate");
    }

    static JSONObject approval(String by, double maxCredits) throws Exception {
        return new JSONObject().put("approved_by", by).put("approved_at", SubCenterStore.nowIso()).put("max_credits", maxCredits);
    }

    /** Sends one title. Either {@code approval} (a single, explicitly approved translation) or {@code batchId} (inside an approved envelope). */
    static JSONObject submit(String contentId, String apiName, String sourceText, String sourceLang, JSONObject approval, String batchId) throws Exception {
        JSONObject body = new JSONObject().put("content_id", contentId).put("provider", "opensubtitles_ai").put("api", apiName)
                .put("target", "ar").put("source_file", sourceText);
        if (sourceLang != null && sourceLang.length() > 0 && !"und".equals(sourceLang)) body.put("source_lang", sourceLang);
        if (batchId != null && batchId.length() > 0) body.put("batch_id", batchId);
        else body.put("approval", approval);
        return CxApi.post("/admin/subhub/translate", body);
    }

    /** Opens the spending envelope of a batch. Returns its id. */
    static String beginBatch(List<String> ids, String apiName, double budget, double estimate, String by) throws Exception {
        JSONArray a = new JSONArray();
        for (String id : ids) a.put(id);
        JSONObject r = CxApi.post("/admin/subhub/translate/batch", new JSONObject().put("ids", a).put("api", apiName).put("target", "ar")
                .put("budget", budget).put("estimate", estimate).put("approved_by", by).put("approved_at", SubCenterStore.nowIso()));
        return r.optString("batch_id");
    }

    /** Result of one status poll applied to a job. */
    static final class Polled {
        final String state;     // TRANSLATING | QUALITY_REVIEW | FAILED | ERROR
        final String detail;

        Polled(String state, String detail) {
            this.state = state;
            this.detail = detail;
        }
    }

    /**
     * Polls one queued job and moves it: PENDING → «جارٍ الترجمة»; COMPLETED → the Worker's file (source timing kept,
     * checked there) goes through the same local QA as any Arabic file and waits in «مراجعة الجودة»; failure → «فشل».
     * Never approves, never publishes.
     */
    static Polled poll(Context c, SubQueue q, SubQueue.Job j) {
        if (j.providerJobId == null || j.providerJobId.length() == 0) return new Polled("ERROR", "لا توجد مهمة مرسلة لهذا العنوان.");
        JSONObject r;
        try {
            r = CxApi.get("/admin/subhub/translate/status?correlation_id=" + java.net.URLEncoder.encode(j.providerJobId, "UTF-8"));
        } catch (CxApi.Failure f) {
            return new Polled("ERROR", f.getMessage());
        } catch (Throwable t) {
            return new Polled("ERROR", "تعذر قراءة الحالة.");
        }
        String st = r.optString("state");
        long now = System.currentTimeMillis();
        try {
            if ("TRANSLATING".equals(st)) {
                if (j.state == SubQueue.State.TRANSLATION_QUEUED) q.move(j, SubQueue.State.TRANSLATING, "بدأ المزوّد الترجمة", now);
                SubCenterStore.save(c);
                return new Polled("TRANSLATING", r.optString("provider_status", "PENDING"));
            }
            if ("FAILED".equals(st)) {
                String why = reason(r.optString("reason"));
                if (j.state == SubQueue.State.TRANSLATION_QUEUED) q.move(j, SubQueue.State.TRANSLATING, "بدأ المزوّد الترجمة", now);
                q.move(j, SubQueue.State.FAILED, why, now);
                j.lastError = why;
                SubCenterStore.save(c);
                return new Polled("FAILED", why);
            }
            if ("QUALITY_REVIEW".equals(st)) {
                if (j.state == SubQueue.State.TRANSLATION_QUEUED) q.move(j, SubQueue.State.TRANSLATING, "بدأ المزوّد الترجمة", now);
                if (j.state == SubQueue.State.TRANSLATING) q.move(j, SubQueue.State.TRANSLATION_COMPLETED, "اكتملت عند المزوّد", now);
                JSONObject rep = r.optJSONObject("report");
                if (rep != null && rep.optJSONObject("quality") != null) j.providerQuality = rep.optJSONObject("quality").toString();
                byte[] srt = r.optString("srt", "").getBytes("UTF-8");
                SubCenterRunner.checkAndPark(c, q, j, srt, "srt", "ar", "ترجمة آلية: " + r.optString("api", "opensubtitles_ai")
                        + (rep != null && rep.optInt("timing_restored") > 0 ? " · أُعيد توقيت " + rep.optInt("timing_restored") + " مقطعاً من المصدر" : ""));
                SubCenterStore.save(c);
                return new Polled("QUALITY_REVIEW", "");
            }
        } catch (Throwable t) {
            return new Polled("ERROR", "تعذر تطبيق النتيجة: " + t.getClass().getSimpleName());
        }
        return new Polled("ERROR", "حالة غير متوقعة: " + st);
    }

    static String reason(String code) {
        switch (code) {
            case "cue_count_differs": return "رفضنا الترجمة: عدد المقاطع يختلف عن المصدر فلا يمكن حفظ التوقيت.";
            case "not_arabic": return "رفضنا الترجمة: النص الناتج ليس عربياً.";
            case "provider_quality_failed": return "فحص الجودة عند المزوّد فشل (يُعيد المزوّد الرصيد بحسب وثائقه).";
            case "provider_error": case "provider_timeout": return "انتهت المهلة أو أخطأ المزوّد.";
            case "empty_result": return "النتيجة فارغة.";
            default: return "فشلت الترجمة (" + code + ").";
        }
    }

    /** Tells the Worker that the admin approved (or revoked) the subtitle of a title: automatic routes then never replace it. */
    static void recordApproval(String contentId, boolean approve, String url, String by) {
        try {
            JSONObject b = new JSONObject().put("content_id", contentId).put("action", approve ? "approve" : "revoke");
            if (approve) b.put("approved_by", by).put("url", url == null ? "" : url).put("source", "subtitle-center");
            CxApi.post("/admin/subhub/approval", b);
        } catch (Throwable ignored) {
            // best effort: the app-side overwrite confirmation (SubPublish) stays the first line of defence
        }
    }
}
