package com.my.newproject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "What's New?" — the automatic workflow for one title: find + validate the video, find + validate a subtitle, build the
 * import plan, let the Worker validate it again, then import in bounded chunks. Used by the bulk screen (and by the
 * detail screen for its building blocks). Blocking: call from a background thread; the Listener is called from that thread.
 *
 * It never invents a result: every state in {@link Result#state} comes from a Worker answer, and a failure of one
 * title / season / chunk never stops the others.
 */
final class WnPipeline {

    private WnPipeline() { }

    interface Listener {
        /** A human line for one title ("S01 · فحص الفيديو 8/24"). */
        void step(String key, String text);

        /** Episodes / steps done out of total (total may grow as seasons are discovered). */
        void progress(String key, int done, int total);

        boolean cancelled();
    }

    static final Listener SILENT = new Listener() {
        @Override public void step(String key, String text) { }
        @Override public void progress(String key, int done, int total) { }
        @Override public boolean cancelled() { return false; }
    };

    static final class Options {
        boolean subtitles = true;
        String lang = "auto";
        WnLogic.Placement placement = new WnLogic.Placement();
        WnLogic.Flags flags = new WnLogic.Flags();
        /** series: null = every aired season; otherwise only these seasons */
        List<Integer> seasons;
        /** series: only the newest aired season (ignored when seasons / episodes are given) */
        boolean latestSeasonOnly;
        /** series: null = every aired episode not yet in the library; otherwise exactly these (season -> episodes) */
        Map<Integer, List<Integer>> episodes;
        /** one per user action: a retry of the same action reuses it, so the Worker replays instead of duplicating */
        String nonce = WnLogic.newRequestId("a");
    }

    static final class Result {
        final String key;
        final String title;
        String state = "failed";           // imported | partial | duplicate | needs_ack | missing_video | ... | failed | cancelled
        String message = "";
        int created;                       // episodes (or 1 movie) written
        int skippedExisting;
        int missingVideo;
        int failedChunks;
        boolean retryable;
        String stage = "";
        JSONObject report;
        final List<String> undoIds = new ArrayList<>();
        final List<String> notes = new ArrayList<>();

        Result(String key, String title) {
            this.key = key;
            this.title = title;
        }

        boolean ok() {
            return "imported".equals(state) || "partial".equals(state);
        }
    }

    // ------------------------------------------------------------------ building blocks (also used by the detail screen)

    static JSONObject videoRequest(String type, long tmdb, int season, List<Integer> episodes) {
        JSONObject r = new JSONObject();
        CxJ.put(r, "type", type);
        CxJ.put(r, "tmdb_id", tmdb);
        if ("series".equals(type)) {
            CxJ.put(r, "season", season);
            JSONArray eps = new JSONArray();
            if (episodes != null) for (Integer e : episodes) eps.put(e.intValue());
            try { r.put("episodes", eps); } catch (Exception ignored) { }
        }
        return r;
    }

    /**
     * Resolves the video of the given episodes in bounded batches. episode -> target JSON (sources, best, status...).
     * An episode the Worker did not answer for is simply absent.
     */
    static Map<Integer, JSONObject> resolveEpisodes(long tmdb, int season, List<Integer> episodes, Listener l, String key) throws CxApi.Failure {
        Map<Integer, JSONObject> out = new LinkedHashMap<>();
        int done = 0;
        for (List<Integer> chunk : WnLogic.chunk(episodes, WnLogic.VIDEO_CHUNK)) {
            if (l != null && l.cancelled()) break;
            JSONObject v = WnApi.video(videoRequest("series", tmdb, season, chunk));
            JSONArray targets = v.optJSONArray("targets");
            for (int i = 0; targets != null && i < targets.length(); i++) {
                JSONObject t = targets.optJSONObject(i);
                if (t != null) out.put(t.optInt("episode"), t);
            }
            done += chunk.size();
            if (l != null) l.step(key, "فحص الفيديو — الموسم " + season + " (" + done + "/" + episodes.size() + ")");
        }
        return out;
    }

    /** {token, note}: the first candidate that downloads AND validates (up to 3 tried). Never throws for provider trouble. */
    static String[] fetchBestSubtitle(String type, long tmdb, int season, int episode, String lang) {
        String note = "";
        try {
            JSONObject req = new JSONObject();
            CxJ.put(req, "type", type);
            CxJ.put(req, "tmdb_id", tmdb);
            CxJ.put(req, "lang", lang == null || lang.length() == 0 ? "auto" : lang);
            if ("series".equals(type)) {
                CxJ.put(req, "season", season);
                CxJ.put(req, "episode", episode);
            }
            JSONObject s = WnApi.subtitles(req);
            JSONArray results = s.optJSONArray("results");
            if (results == null || results.length() == 0) return new String[]{"", "لا توجد ترجمة متاحة"};
            String used = s.optString("lang", "ar");
            for (int i = 0; i < results.length() && i < 3; i++) {
                JSONObject pick = results.optJSONObject(i);
                if (pick == null) continue;
                JSONObject fr = new JSONObject();
                CxJ.put(fr, "type", type);
                CxJ.put(fr, "tmdb_id", tmdb);
                CxJ.put(fr, "lang", used);
                if ("series".equals(type)) {
                    CxJ.put(fr, "season", season);
                    CxJ.put(fr, "episode", episode);
                }
                try { fr.put("pick", pick); } catch (Exception ignored) { }
                try {
                    JSONObject f = WnApi.subtitleFetch(fr);
                    JSONObject val = f.optJSONObject("validation");
                    String token = f.optString("token", "");
                    if (val != null && val.optBoolean("ok", false) && token.length() > 0) return new String[]{token, used};
                    JSONArray issues = val == null ? null : val.optJSONArray("issues");
                    note = issues != null && issues.length() > 0 ? issues.optJSONObject(0).optString("message") : "ملف الترجمة غير صالح";
                } catch (CxApi.Failure f) {
                    note = f.getMessage();
                }
            }
        } catch (CxApi.Failure f) {
            note = "subs_no_keys".equals(WnLogic.errorKey(f)) ? "مصدر الترجمة غير مضبوط في الـ Worker" : f.getMessage();
        }
        return new String[]{"", note};
    }

    // ------------------------------------------------------------------ a movie

    static Result runMovie(JSONObject card, Options o, Listener l) {
        long tmdb = card.optLong("tmdb_id");
        String key = "movie:" + tmdb;
        Result r = new Result(key, card.optString("title"));
        try {
            l.step(key, "البحث عن مصدر الفيديو…");
            r.stage = "video";
            JSONObject v = WnApi.video(videoRequest("movie", tmdb, 0, null));
            JSONObject target = WnLogic.at(v.optJSONArray("targets"), 0);
            JSONObject best = WnLogic.bestSource(target);
            if (best == null) {
                r.state = "missing_video";
                r.message = WnLogic.verdictLabel(target.optString("status", "unavailable"));
                return r;
            }
            if (l.cancelled()) return cancelled(r);

            String subToken = "";
            if (o.subtitles) {
                l.step(key, "البحث عن الترجمة…");
                r.stage = "subtitle";
                String[] sub = fetchBestSubtitle("movie", tmdb, 0, 0, o.lang);
                subToken = sub[0];
                if (subToken.length() == 0) r.notes.add("بدون ترجمة: " + sub[1]);
            }
            if (l.cancelled()) return cancelled(r);

            JSONObject plan = WnLogic.basePlan("movie", tmdb, o.placement, o.flags, "m" + tmdb + "-" + o.nonce);
            WnLogic.withMovie(plan, best.optString("token"), WnLogic.fallbackTokens(target, best), subToken);
            l.step(key, "التحقق من كل شيء…");
            r.stage = "validate";
            JSONObject report = WnApi.validate(plan).optJSONObject("report");
            r.report = report;
            if (!WnLogic.reportOk(report)) {
                r.state = report == null ? "failed" : report.optString("state", "failed");
                r.message = WnLogic.firstProblem(report);
                return r;
            }
            if (report.optBoolean("requires_ack", false) && !o.flags.ackUnverified) {
                r.state = "needs_ack";
                r.message = "مصدر الفيديو لا يمكن إثبات تشغيله من الخادم (" + WnLogic.verdictLabel(best.optString("verdict")) + ").";
                return r;
            }
            l.step(key, "الإضافة إلى Cigram…");
            r.stage = "import";
            JSONObject imp = WnApi.importPlan(plan);
            r.state = "imported";
            r.created = 1;
            String undo = imp.optString("undo_id", "");
            if (undo.length() > 0) r.undoIds.add(undo);
            l.progress(key, 1, 1);
        } catch (CxApi.Failure f) {
            fromFailure(r, f);
        }
        return r;
    }

    // ------------------------------------------------------------------ a series (all / chosen seasons and episodes)

    static Result runSeries(JSONObject card, Options o, Listener l) {
        long tmdb = card.optLong("tmdb_id");
        String key = "series:" + tmdb;
        Result r = new Result(key, card.optString("title"));
        try {
            l.step(key, "قراءة المواسم…");
            r.stage = "details";
            JSONObject d = WnApi.details("series", tmdb);
            JSONObject have = d.optJSONObject("library_episodes");
            List<Integer> seasons = new ArrayList<>();
            if (o.episodes != null) {
                seasons.addAll(o.episodes.keySet());
            } else if (o.seasons != null) {
                seasons.addAll(o.seasons);
            } else {
                JSONArray all = d.optJSONArray("seasons");
                for (int i = 0; all != null && i < all.length(); i++) {
                    JSONObject s = all.optJSONObject(i);
                    if (s != null && s.optBoolean("aired", true)) seasons.add(s.optInt("season"));
                }
            }
            java.util.Collections.sort(seasons);
            if (o.latestSeasonOnly && o.episodes == null && o.seasons == null && seasons.size() > 1) {
                int last = seasons.get(seasons.size() - 1);
                seasons.clear();
                seasons.add(last);
            }
            if (seasons.isEmpty()) {
                r.state = "missing_metadata";
                r.message = "لا توجد مواسم صدرت لهذا المسلسل.";
                return r;
            }

            int total = 0;
            int done = 0;
            boolean anyNeedsAck = false;
            boolean allDuplicate = true;
            for (int season : seasons) {
                if (l.cancelled()) { r.state = r.created > 0 ? "partial" : "cancelled"; r.message = "أُلغي قبل اكتمال كل المواسم."; return r; }
                l.step(key, "الموسم " + season + " — قراءة الحلقات…");
                r.stage = "season";
                JSONObject sd;
                try {
                    sd = WnApi.season(tmdb, season);
                } catch (CxApi.Failure f) {
                    r.failedChunks++;
                    r.notes.add("الموسم " + season + ": " + f.getMessage());
                    continue;
                }
                JSONArray eps = sd.optJSONArray("episodes");
                List<Integer> asked = o.episodes == null ? null : o.episodes.get(season);
                List<Integer> wanted = new ArrayList<>();
                for (int i = 0; eps != null && i < eps.length(); i++) {
                    JSONObject e = eps.optJSONObject(i);
                    if (e == null) continue;
                    int n = e.optInt("episode");
                    if (asked != null && !asked.contains(n)) continue;
                    if (!e.optBoolean("aired", false)) { r.notes.add(WnLogic.epCode(season, n) + ": لم تُعرض بعد"); continue; }
                    if (e.optBoolean("in_library", false)) { r.skippedExisting++; continue; }
                    wanted.add(n);
                }
                if (wanted.isEmpty()) continue;
                allDuplicate = false;
                total += wanted.size();
                l.progress(key, done, total);

                r.stage = "video";
                Map<Integer, JSONObject> targets = resolveEpisodes(tmdb, season, wanted, l, key);
                List<JSONObject> entries = new ArrayList<>();
                for (int n : wanted) {
                    if (l.cancelled()) break;
                    JSONObject t = targets.get(n);
                    JSONObject best = WnLogic.bestSource(t);
                    if (best == null) {
                        r.missingVideo++;
                        r.notes.add(WnLogic.epCode(season, n) + ": " + WnLogic.verdictLabel(t == null ? "unavailable" : t.optString("status", "unavailable")));
                        done++;
                        continue;
                    }
                    String sub = "";
                    if (o.subtitles) {
                        l.step(key, WnLogic.epCode(season, n) + " — البحث عن الترجمة…");
                        sub = fetchBestSubtitle("series", tmdb, season, n, o.lang)[0];
                    }
                    entries.add(WnLogic.episodeEntry(n, best.optString("token"), WnLogic.fallbackTokens(t, best), sub));
                }
                if (entries.isEmpty()) continue;

                r.stage = "import";
                int idx = 0;
                for (List<JSONObject> chunk : WnLogic.chunk(entries, WnLogic.IMPORT_CHUNK)) {
                    if (l.cancelled()) break;
                    l.step(key, "الموسم " + season + " — الإضافة (" + Math.min(done + chunk.size(), total) + "/" + total + ")");
                    JSONObject plan = WnLogic.basePlan("series", tmdb, o.placement, o.flags, "s" + tmdb + "-" + o.nonce + "-s" + season + "c" + idx);
                    WnLogic.withSeason(plan, season, chunk);
                    idx++;
                    try {
                        JSONObject imp = WnApi.importPlan(plan);
                        JSONArray created = imp.optJSONArray("created");
                        int n = 0;
                        for (int i = 0; created != null && i < created.length(); i++) {
                            if ("episode".equals(created.optJSONObject(i).optString("type"))) n++;
                        }
                        r.created += n;
                        JSONArray skipped = imp.optJSONArray("skipped");
                        r.skippedExisting += skipped == null ? 0 : skipped.length();
                        String undo = imp.optString("undo_id", "");
                        if (undo.length() > 0) r.undoIds.add(undo);
                    } catch (CxApi.Failure f) {
                        String k = WnLogic.errorKey(f);
                        if ("ack_required".equals(k)) {
                            anyNeedsAck = true;
                        } else if ("duplicate_content".equals(k)) {
                            r.skippedExisting += chunk.size();
                        } else {
                            r.failedChunks++;
                            r.retryable = r.retryable || "NETWORK".equals(f.code);
                            r.notes.add("الموسم " + season + ": " + f.getMessage());
                        }
                    }
                    done += chunk.size();
                    l.progress(key, done, total);
                }
            }
            if (r.created > 0) {
                r.state = r.failedChunks == 0 && r.missingVideo == 0 && !anyNeedsAck ? "imported" : "partial";
            } else if (anyNeedsAck) {
                r.state = "needs_ack";
                r.message = "مصادر الفيديو مشغّلات مضمَّنة لا يمكن إثبات تشغيلها من الخادم. أكّد الإقرار لإكمال الاستيراد.";
            } else if (allDuplicate && r.failedChunks == 0) {
                r.state = "duplicate";
                r.message = "كل الحلقات المطلوبة موجودة بالفعل.";
            } else if (r.missingVideo > 0) {
                r.state = "missing_video";
                r.message = "لم يتوفر مصدر فيديو لأي حلقة مطلوبة.";
            } else {
                r.state = "failed";
                r.message = r.notes.isEmpty() ? "لم تتم إضافة أي حلقة." : r.notes.get(0);
            }
        } catch (CxApi.Failure f) {
            fromFailure(r, f);
        }
        return r;
    }

    static Result run(JSONObject card, Options o, Listener l) {
        return "series".equals(card.optString("type")) ? runSeries(card, o, l) : runMovie(card, o, l);
    }

    // ------------------------------------------------------------------ helpers

    private static Result cancelled(Result r) {
        r.state = "cancelled";
        r.message = "أُلغي.";
        return r;
    }

    /** Maps a Worker failure to one of the visible states (never to "imported"). */
    static void fromFailure(Result r, CxApi.Failure f) {
        String k = WnLogic.errorKey(f);
        JSONObject rep = f.json == null ? null : f.json.optJSONObject("report");
        if (rep != null) r.report = rep;
        if ("duplicate_content".equals(k)) {
            r.state = "duplicate";
            r.message = f.getMessage();
        } else if ("ack_required".equals(k)) {
            r.state = "needs_ack";
            r.message = f.getMessage();
        } else if ("validation_failed".equals(k) && rep != null) {
            r.state = rep.optString("state", "import_failed");
            r.message = WnLogic.firstProblem(rep);
        } else {
            r.state = "failed";
            r.message = f.getMessage();
            r.retryable = "NETWORK".equals(f.code) || f.http == 502 || f.http == 503 || f.http == 504 || f.http == 429;
        }
    }
}
