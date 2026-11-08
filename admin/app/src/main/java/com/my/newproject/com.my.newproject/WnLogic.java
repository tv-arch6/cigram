package com.my.newproject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * "What's New?" — pure logic (no views): filter chips, request / import-plan builders, wording and colours of every
 * state, chunking and request ids. Kept free of Android UI so it is unit-tested on the JVM (WnLogicTest).
 */
final class WnLogic {

    private WnLogic() { }

    /** Episodes per validation / import request (the Worker accepts at most 12 per import call). */
    static final int IMPORT_CHUNK = 12;
    /** Episodes per video-discovery request (the Worker probes at most 8 per call). */
    static final int VIDEO_CHUNK = 8;

    // ------------------------------------------------------------------ filter chips

    /** id, label, feed, type, sort */
    static final String[][] CHIPS = {
            {"all", "الكل", "all:new_releases", "any", ""},
            {"movies", "أفلام", "movie:new_releases", "movie", ""},
            {"series", "مسلسلات", "tv:recent_episodes", "series", ""},
            {"new", "إصدارات جديدة", "all:new_releases", "any", "release"},
            {"trending", "الأكثر رواجاً", "all:trending", "any", ""},
            {"upcoming", "قريباً", "movie:upcoming", "movie", ""},
            {"seasons", "مواسم جديدة", "tv:new_seasons", "series", ""},
            {"collections", "سلاسل أفلام", "collections", "movie", ""},
            {"recent", "أُضيف حديثاً", "recently_added", "any", ""},
            {"ready", "متاح للاستيراد", "ready", "any", ""},
            {"unverified", "فيديو غير مُتحقَّق", "unverified", "any", ""},
            {"novideo", "بلا فيديو", "missing_video", "any", ""},
            {"nosub", "بلا ترجمة", "missing_subtitle", "any", ""},
            {"imported", "مستورد مسبقاً", "imported", "any", ""},
    };

    static String[] chip(String id) {
        for (String[] c : CHIPS) if (c[0].equals(id)) return c;
        return CHIPS[0];
    }

    /** Advanced filters (TMDB discover). Empty string = not set. */
    static final class Filters {
        String genre = "";
        String language = "";
        String country = "";
        String year = "";
        String minRating = "";
        String sort = "";
        String dateFrom = "";
        String dateTo = "";

        boolean active() {
            return genre.length() + language.length() + country.length() + year.length() + minRating.length() + dateFrom.length() + dateTo.length() > 0;
        }

        void clear() {
            genre = language = country = year = minRating = dateFrom = dateTo = "";
        }

        void apply(JSONObject req) {
            put(req, "genre", genre);
            put(req, "language", language);
            put(req, "country", country);
            put(req, "year", year);
            put(req, "min_rating", minRating);
            put(req, "date_from", dateFrom);
            put(req, "date_to", dateTo);
            if (sort.length() > 0) put(req, "sort", sort);
        }

        String describe(String genreName) {
            List<String> p = new ArrayList<>();
            if (genre.length() > 0) p.add(genreName == null || genreName.length() == 0 ? "تصنيف" : genreName);
            if (language.length() > 0) p.add("لغة " + language);
            if (country.length() > 0) p.add("دولة " + country);
            if (year.length() > 0) p.add("سنة " + year);
            if (minRating.length() > 0) p.add("تقييم ≥ " + minRating);
            if (dateFrom.length() > 0) p.add("من " + dateFrom);
            if (dateTo.length() > 0) p.add("إلى " + dateTo);
            StringBuilder sb = new StringBuilder();
            for (String s : p) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(s);
            }
            return sb.toString();
        }
    }

    static JSONObject feedRequest(String[] chip, Filters f, int page, boolean refresh) {
        JSONObject r = new JSONObject();
        put(r, "feed", chip[2]);
        put(r, "type", chip[3]);
        if (chip[4].length() > 0) put(r, "sort", chip[4]);
        if (f != null) f.apply(r);
        try {
            r.put("page", Math.max(1, page));
            if (refresh) r.put("refresh", true);
        } catch (Exception ignored) { }
        return r;
    }

    // ------------------------------------------------------------------ wording + colours

    static String verdictLabel(String v) {
        if ("playable".equals(v)) return "صالح للتشغيل — تم التحقق";
        if ("embed_reachable".equals(v)) return "المشغّل يعمل — التشغيل غير مُثبت";
        if ("unverified".equals(v)) return "غير مُتحقَّق — المزوّد يمنع الفحص";
        if ("unavailable".equals(v)) return "غير متوفر عند المزوّد";
        if ("unreachable".equals(v)) return "تعذر الوصول للرابط";
        if ("invalid".equals(v)) return "رابط غير صالح";
        if ("drm".equals(v)) return "محمي بـ DRM";
        if ("mismatch".equals(v)) return "لا يطابق الموسم/الحلقة";
        if ("not_released".equals(v)) return "لم يصدر بعد";
        return v == null || v.length() == 0 ? "لم يُفحص" : v;
    }

    static int verdictColor(String v) {
        if ("playable".equals(v)) return CgCfg.GOOD;
        if ("embed_reachable".equals(v)) return CgCfg.CYAN;
        if ("unverified".equals(v)) return CgCfg.WARN;
        if ("not_released".equals(v)) return CgCfg.GRAY;
        if (v == null || v.length() == 0) return CgCfg.GRAY;
        return CgCfg.BAD;
    }

    static boolean verdictImportable(String v) {
        return "playable".equals(v) || "embed_reachable".equals(v) || "unverified".equals(v);
    }

    static boolean verdictNeedsAck(String v) {
        return "embed_reachable".equals(v) || "unverified".equals(v);
    }

    static String levelLabel(String level) {
        if ("playable".equals(level)) return "تشغيل مُتحقَّق";
        if ("reachable".equals(level)) return "صفحة تعمل";
        if ("resolved".equals(level)) return "مؤكَّد من المزوّد";
        if ("generated".equals(level)) return "رابط مُولَّد";
        return "فشل";
    }

    static String cardVideoLabel(String state) {
        if ("ok".equals(state)) return "فيديو متاح";
        if ("unverified".equals(state)) return "فيديو غير مُتحقَّق";
        if ("missing".equals(state)) return "بلا فيديو";
        if ("not_released".equals(state)) return "لم يصدر";
        return "فيديو؟";
    }

    static int cardVideoColor(String state) {
        if ("ok".equals(state)) return CgCfg.GOOD;
        if ("unverified".equals(state)) return CgCfg.WARN;
        if ("missing".equals(state)) return CgCfg.BAD;
        return CgCfg.GRAY;
    }

    static String cardSubLabel(String state) {
        if ("ar".equals(state)) return "ترجمة عربية";
        if ("en".equals(state)) return "ترجمة إنجليزية";
        if ("none".equals(state)) return "بلا ترجمة";
        if ("unconfigured".equals(state)) return "مصدر الترجمة غير مضبوط";
        return "ترجمة؟";
    }

    static int cardSubColor(String state) {
        if ("ar".equals(state)) return CgCfg.GOOD;
        if ("en".equals(state)) return CgCfg.CYAN;
        if ("none".equals(state)) return CgCfg.WARN;
        return CgCfg.GRAY;
    }

    static String stateLabel(String state) {
        if ("ready".equals(state)) return "جاهز للاستيراد";
        if ("missing_video".equals(state)) return "الفيديو مفقود";
        if ("missing_metadata".equals(state)) return "بيانات ناقصة";
        if ("subtitle_missing".equals(state)) return "الترجمة مفقودة";
        if ("invalid_subtitle".equals(state)) return "ترجمة غير صالحة";
        if ("duplicate".equals(state)) return "محتوى مكرر";
        if ("import_failed".equals(state)) return "فشل الاستيراد";
        if ("imported".equals(state)) return "تم الاستيراد بنجاح";
        if ("needs_ack".equals(state)) return "بانتظار إقرارك";
        if ("partial".equals(state)) return "اكتمل جزئياً";
        if ("cancelled".equals(state)) return "أُلغي";
        return state == null ? "" : state;
    }

    static int stateColor(String state) {
        if ("ready".equals(state) || "imported".equals(state)) return CgCfg.GOOD;
        if ("needs_ack".equals(state) || "partial".equals(state) || "subtitle_missing".equals(state)) return CgCfg.WARN;
        if ("cancelled".equals(state)) return CgCfg.GRAY;
        return CgCfg.BAD;
    }

    static String checkMark(String status) {
        if ("pass".equals(status)) return "✓";
        if ("warn".equals(status)) return "!";
        if ("fail".equals(status)) return "✕";
        return "–";
    }

    static int checkColor(String status) {
        if ("pass".equals(status)) return CgCfg.GOOD;
        if ("warn".equals(status)) return CgCfg.WARN;
        if ("fail".equals(status)) return CgCfg.BAD;
        return CgCfg.GRAY;
    }

    static String typeLabel(String type) {
        return "series".equals(type) ? "مسلسل" : "collection".equals(type) ? "سلسلة" : "فيلم";
    }

    static String ratingText(double r) {
        return r > 0 ? String.format(Locale.US, "%.1f", r) : "—";
    }

    /** "S01E03" */
    static String epCode(int season, int episode) {
        return String.format(Locale.US, "S%02dE%02d", season, episode);
    }

    // ------------------------------------------------------------------ ids + chunking

    private static final Random RND = new Random();

    /** One id per user action: a network retry of the same action reuses it and the Worker replays the first answer. */
    static String newRequestId(String key) {
        String k = key == null ? "x" : key.replaceAll("[^A-Za-z0-9:_-]", "");
        String id = k + "-" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString(RND.nextInt(1296), 36);
        return id.length() > 80 ? id.substring(id.length() - 80) : id;
    }

    static <T> List<List<T>> chunk(List<T> all, int size) {
        List<List<T>> out = new ArrayList<>();
        int n = Math.max(1, size);
        for (int i = 0; i < all.size(); i += n) out.add(new ArrayList<>(all.subList(i, Math.min(all.size(), i + n))));
        return out;
    }

    // ------------------------------------------------------------------ import plans

    /** Where the title goes: an existing section id, or a new section name (created once by the Worker), or none. */
    static final class Placement {
        String sectionId = "";
        String newSection = "";
        String newSectionType = "";
        /** null = keep the TMDB genres; empty = no categories; otherwise the chosen names */
        List<String> categories;
    }

    static final class Flags {
        boolean ackUnverified;
        boolean notify = true;
        boolean subtitleRequired;
        boolean hidden;
        /** declared by the admin (e.g. 720p / 1080p / 4K); empty = leave blank. Never guessed. */
        String quality = "";
    }

    static JSONObject basePlan(String type, long tmdb, Placement p, Flags f, String requestId) {
        JSONObject plan = new JSONObject();
        try {
            plan.put("type", type);
            plan.put("tmdb_id", tmdb);
            plan.put("request_id", requestId);
            plan.put("ack_unverified", f != null && f.ackUnverified);
            JSONObject section = new JSONObject();
            if (p != null && p.sectionId.length() > 0) section.put("id", p.sectionId);
            else if (p != null && p.newSection.length() > 0) {
                section.put("new_name", p.newSection);
                if (p.newSectionType.length() > 0) section.put("new_type", p.newSectionType);
            }
            plan.put("section", section);
            if (p != null && p.categories != null) {
                JSONArray cats = new JSONArray();
                for (String c : p.categories) cats.put(c);
                plan.put("categories", cats);
            }
            JSONObject opt = new JSONObject();
            opt.put("notify", f == null || f.notify);
            opt.put("subtitle_required", f != null && f.subtitleRequired);
            opt.put("hidden", f != null && f.hidden);
            if (f != null && f.quality != null && f.quality.length() > 0) opt.put("quality", f.quality);
            plan.put("options", opt);
        } catch (Exception ignored) { }
        return plan;
    }

    static JSONObject withMovie(JSONObject plan, String videoToken, List<String> fallbackTokens, String subtitleToken) {
        try {
            JSONObject m = new JSONObject();
            m.put("video_token", videoToken == null ? "" : videoToken);
            if (subtitleToken != null && subtitleToken.length() > 0) m.put("subtitle_token", subtitleToken);
            if (fallbackTokens != null && !fallbackTokens.isEmpty()) {
                JSONArray fb = new JSONArray();
                for (String t : fallbackTokens) fb.put(t);
                m.put("fallback_tokens", fb);
            }
            plan.put("movie", m);
        } catch (Exception ignored) { }
        return plan;
    }

    /** One entry of seasons[].episodes[]. */
    static JSONObject episodeEntry(int episode, String videoToken, List<String> fallbackTokens, String subtitleToken) {
        JSONObject e = new JSONObject();
        try {
            e.put("episode", episode);
            e.put("video_token", videoToken == null ? "" : videoToken);
            if (subtitleToken != null && subtitleToken.length() > 0) e.put("subtitle_token", subtitleToken);
            if (fallbackTokens != null && !fallbackTokens.isEmpty()) {
                JSONArray fb = new JSONArray();
                for (String t : fallbackTokens) fb.put(t);
                e.put("fallback_tokens", fb);
            }
        } catch (Exception ignored) { }
        return e;
    }

    static JSONObject withSeason(JSONObject plan, int season, List<JSONObject> episodes) {
        try {
            JSONArray eps = new JSONArray();
            for (JSONObject e : episodes) eps.put(e);
            JSONObject s = new JSONObject();
            s.put("season", season);
            s.put("episodes", eps);
            JSONArray seasons = new JSONArray();
            seasons.put(s);
            plan.put("seasons", seasons);
        } catch (Exception ignored) { }
        return plan;
    }

    // ------------------------------------------------------------------ reading Worker answers

    static boolean reportOk(JSONObject report) {
        return report != null && report.optBoolean("ok", false);
    }

    /** Importable now: the report is ready, and an unprovable (embed) source has been acknowledged. */
    static boolean canImport(JSONObject report, boolean ack) {
        return reportOk(report) && (!report.optBoolean("requires_ack", false) || ack);
    }

    /** The first failing line of a report, for toasts / item rows. */
    static String firstProblem(JSONObject report) {
        if (report == null) return "";
        JSONArray checks = report.optJSONArray("checks");
        for (int i = 0; checks != null && i < checks.length(); i++) {
            JSONObject c = checks.optJSONObject(i);
            if (c != null && "fail".equals(c.optString("status"))) return c.optString("label") + ": " + c.optString("detail");
        }
        JSONArray items = report.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            JSONArray ic = it == null ? null : it.optJSONArray("checks");
            for (int j = 0; ic != null && j < ic.length(); j++) {
                JSONObject c = ic.optJSONObject(j);
                if (c != null && "fail".equals(c.optString("status"))) return it.optString("label") + " — " + c.optString("label") + ": " + c.optString("detail");
            }
        }
        return stateLabel(report.optString("state"));
    }

    /** The Worker's machine key of a failure ("ack_required", "duplicate_content", "sync_running"...). */
    static String errorKey(CxApi.Failure f) {
        if (f == null) return "";
        String k = f.json == null ? "" : f.json.optString("error", "");
        return k.length() > 0 ? k : f.code;
    }

    /** First source of a video target that can be imported ("" token when none). */
    static JSONObject bestSource(JSONObject target) {
        if (target == null) return null;
        JSONArray sources = target.optJSONArray("sources");
        String best = target.optString("best", "");
        JSONObject first = null;
        for (int i = 0; sources != null && i < sources.length(); i++) {
            JSONObject s = sources.optJSONObject(i);
            if (s == null || !s.optBoolean("importable", false) || s.optString("token", "").length() == 0) continue;
            if (first == null) first = s;
            if (best.length() > 0 && best.equals(s.optString("provider"))) return s;
        }
        return first;
    }

    static List<String> fallbackTokens(JSONObject target, JSONObject chosen) {
        List<String> out = new ArrayList<>();
        JSONArray sources = target == null ? null : target.optJSONArray("sources");
        for (int i = 0; sources != null && i < sources.length(); i++) {
            JSONObject s = sources.optJSONObject(i);
            if (s == null || s == chosen || !s.optBoolean("importable", false)) continue;
            if (chosen != null && s.optString("token").equals(chosen.optString("token"))) continue;
            if (s.optString("token", "").length() > 0 && out.size() < 4) out.add(s.optString("token"));
        }
        return out;
    }


    /** Several chunk reports of ONE plan (episodes are validated 12 at a time) as a single report. */
    static JSONObject mergeReports(List<JSONObject> reports) {
        JSONObject out = new JSONObject();
        try {
            if (reports == null || reports.isEmpty()) {
                out.put("ok", false);
                out.put("state", "import_failed");
                return out;
            }
            JSONObject first = reports.get(0);
            boolean ok = true;
            boolean ack = false;
            String state = "ready";
            JSONArray items = new JSONArray();
            JSONArray warnings = new JSONArray();
            for (JSONObject r : reports) {
                ok = ok && r.optBoolean("ok", false);
                ack = ack || r.optBoolean("requires_ack", false);
                if ("ready".equals(state) && !"ready".equals(r.optString("state", "ready"))) state = r.optString("state");
                JSONArray it = r.optJSONArray("items");
                for (int i = 0; it != null && i < it.length(); i++) items.put(it.opt(i));
                JSONArray w = r.optJSONArray("warnings");
                for (int i = 0; w != null && i < w.length(); i++) warnings.put(w.opt(i));
            }
            out.put("ok", ok);
            out.put("requires_ack", ok && ack);
            out.put("state", state);
            out.put("checks", first.optJSONArray("checks") == null ? new JSONArray() : first.optJSONArray("checks"));
            out.put("items", items);
            out.put("warnings", warnings);
        } catch (Exception ignored) { }
        return out;
    }

    static JSONObject at(JSONArray a, int i) {
        JSONObject o = a == null ? null : a.optJSONObject(i);
        return o == null ? new JSONObject() : o;
    }

    private static void put(JSONObject o, String k, String v) {
        if (v == null || v.length() == 0) return;
        try { o.put(k, v); } catch (Exception ignored) { }
    }
}
