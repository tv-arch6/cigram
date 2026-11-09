package com.Cigram.subhub;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * CigramSubtitleHub (admin) — the Arabic translation queue: one job per catalogue title (film or episode), its state
 * machine, retries with exponential backoff, the batch checkpoint, provider quotas and the cost estimate.
 *
 * Pure Java + org.json, persisted as one JSON document after every change, so a restart (or a killed process) resumes
 * exactly where it stopped. Nothing here approves or publishes by itself: APPROVED and PUBLISHED are only reachable
 * through {@link #approve} / {@link #markPublished}, which the screen calls on an explicit admin action.
 */
public final class SubQueue {

    public enum State {
        NOT_TRANSLATED, SEARCHING, SOURCE_FOUND, TRANSLATION_QUEUED, TRANSLATING, TRANSLATION_COMPLETED,
        QUALITY_REVIEW, NEEDS_CORRECTION, APPROVED, PUBLISHED, FAILED
    }

    public static String label(State s) {
        switch (s) {
            case NOT_TRANSLATED: return "غير مترجم";
            case SEARCHING: return "جارٍ البحث";
            case SOURCE_FOUND: return "وُجد مصدر";
            case TRANSLATION_QUEUED: return "في طابور الترجمة";
            case TRANSLATING: return "جارٍ الترجمة";
            case TRANSLATION_COMPLETED: return "اكتملت الترجمة";
            case QUALITY_REVIEW: return "مراجعة الجودة";
            case NEEDS_CORRECTION: return "يحتاج تصحيحاً";
            case APPROVED: return "معتمد";
            case PUBLISHED: return "منشور";
            default: return "فشل";
        }
    }

    private static final Map<State, Set<State>> ALLOWED = new EnumMap<State, Set<State>>(State.class);

    private static void allow(State from, State... to) {
        Set<State> s = new HashSet<State>();
        Collections.addAll(s, to);
        ALLOWED.put(from, s);
    }

    static {
        allow(State.NOT_TRANSLATED, State.SEARCHING, State.QUALITY_REVIEW, State.TRANSLATION_QUEUED, State.SOURCE_FOUND);
        allow(State.SEARCHING, State.SOURCE_FOUND, State.NOT_TRANSLATED, State.FAILED, State.QUALITY_REVIEW, State.NEEDS_CORRECTION);
        allow(State.SOURCE_FOUND, State.QUALITY_REVIEW, State.TRANSLATION_QUEUED, State.NEEDS_CORRECTION, State.FAILED, State.SEARCHING, State.NOT_TRANSLATED);
        allow(State.TRANSLATION_QUEUED, State.TRANSLATING, State.FAILED, State.NOT_TRANSLATED, State.SOURCE_FOUND);
        allow(State.TRANSLATING, State.TRANSLATION_COMPLETED, State.FAILED);
        allow(State.TRANSLATION_COMPLETED, State.QUALITY_REVIEW, State.NEEDS_CORRECTION);
        allow(State.QUALITY_REVIEW, State.APPROVED, State.NEEDS_CORRECTION, State.QUALITY_REVIEW, State.SEARCHING, State.NOT_TRANSLATED);
        allow(State.NEEDS_CORRECTION, State.QUALITY_REVIEW, State.SEARCHING, State.NOT_TRANSLATED, State.TRANSLATION_QUEUED);
        allow(State.APPROVED, State.PUBLISHED, State.QUALITY_REVIEW, State.NEEDS_CORRECTION);
        allow(State.PUBLISHED, State.QUALITY_REVIEW, State.SEARCHING);
        allow(State.FAILED, State.SEARCHING, State.NOT_TRANSLATED, State.QUALITY_REVIEW, State.TRANSLATION_QUEUED);
    }

    public static boolean canMove(State from, State to) {
        Set<State> s = ALLOWED.get(from);
        return s != null && s.contains(to);
    }

    // ------------------------------------------------------------------ job

    public static final class Job {
        /** Cigram content id (film id or episode id) — also the queue key: one job per title, never two. */
        public String contentId = "";
        public String kind = "movie";           // movie | episode
        public String title = "";
        public String seriesId = "";
        public String seriesTitle = "";
        public String tmdbId = "";
        public String imdbId = "";
        public int season;
        public int episode;
        public String originalLanguage = "";
        public final String targetLanguage = "ar";
        public String sourceProvider = "";
        public String sourceLanguage = "";
        public String sourceRelease = "";
        /** The provider's own candidate object (what the Worker needs to download this exact file again). */
        public JSONObject sourcePick;
        /** Local file names (in the app's private folder) of the original and of the Arabic file. */
        public String originalFile = "";
        public String translatedFile = "";
        public String translationProvider = "";
        public String providerJobId = "";
        public State state = State.NOT_TRANSLATED;
        /** PASS | REVIEW | FAIL | "" (not checked). */
        public String quality = "";
        public String qualitySummary = "";
        public String providerQuality = "";
        /** "" | approved | rejected. */
        public String approval = "";
        public String approvedAt = "";
        public int cues;
        public long chars;
        /** Approved whole-file correction baked into the Arabic file (ms, positive = later). */
        public long approvedOffsetMs;
        public int attempts;
        public long nextAttemptAt;
        public long searchedAt;
        public String lastError = "";
        public String publishedUrl = "";
        public String publishedAt = "";
        public String previousSubtitle = "";
        public long createdAt;
        public long updatedAt;
        public final List<JSONObject> history = new ArrayList<JSONObject>();

        public boolean isEpisode() {
            return "episode".equals(kind);
        }

        public String displayTitle() {
            if (!isEpisode()) return title;
            return (seriesTitle.length() > 0 ? seriesTitle : title) + String.format(Locale.US, " · S%02dE%02d", season, episode);
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("content_id", contentId).put("kind", kind).put("title", title).put("series_id", seriesId).put("series_title", seriesTitle)
                    .put("tmdb_id", tmdbId).put("imdb_id", imdbId).put("season", season).put("episode", episode)
                    .put("original_language", originalLanguage).put("target_language", targetLanguage)
                    .put("source_provider", sourceProvider).put("source_language", sourceLanguage).put("source_release", sourceRelease)
                    .put("original_file", originalFile).put("translated_file", translatedFile).put("translation_provider", translationProvider)
                    .put("provider_job_id", providerJobId).put("state", state.name()).put("quality", quality).put("quality_summary", qualitySummary)
                    .put("provider_quality", providerQuality).put("approval", approval).put("approved_at", approvedAt)
                    .put("cues", cues).put("chars", chars).put("approved_offset_ms", approvedOffsetMs).put("attempts", attempts)
                    .put("next_attempt_at", nextAttemptAt).put("searched_at", searchedAt).put("last_error", lastError)
                    .put("published_url", publishedUrl).put("published_at", publishedAt).put("previous_subtitle", previousSubtitle)
                    .put("created_at", createdAt).put("updated_at", updatedAt);
            if (sourcePick != null) o.put("source_pick", sourcePick);
            JSONArray h = new JSONArray();
            for (JSONObject x : history) h.put(x);
            o.put("history", h);
            return o;
        }

        static Job fromJson(JSONObject o) {
            Job j = new Job();
            j.contentId = o.optString("content_id");
            j.kind = o.optString("kind", "movie");
            j.title = o.optString("title");
            j.seriesId = o.optString("series_id");
            j.seriesTitle = o.optString("series_title");
            j.tmdbId = o.optString("tmdb_id");
            j.imdbId = o.optString("imdb_id");
            j.season = o.optInt("season");
            j.episode = o.optInt("episode");
            j.originalLanguage = o.optString("original_language");
            j.sourceProvider = o.optString("source_provider");
            j.sourceLanguage = o.optString("source_language");
            j.sourceRelease = o.optString("source_release");
            j.sourcePick = o.optJSONObject("source_pick");
            j.originalFile = o.optString("original_file");
            j.translatedFile = o.optString("translated_file");
            j.translationProvider = o.optString("translation_provider");
            j.providerJobId = o.optString("provider_job_id");
            try { j.state = State.valueOf(o.optString("state", "NOT_TRANSLATED")); } catch (Exception e) { j.state = State.NOT_TRANSLATED; }
            j.quality = o.optString("quality");
            j.qualitySummary = o.optString("quality_summary");
            j.providerQuality = o.optString("provider_quality");
            j.approval = o.optString("approval");
            j.approvedAt = o.optString("approved_at");
            j.cues = o.optInt("cues");
            j.chars = o.optLong("chars");
            j.approvedOffsetMs = o.optLong("approved_offset_ms");
            j.attempts = o.optInt("attempts");
            j.nextAttemptAt = o.optLong("next_attempt_at");
            j.searchedAt = o.optLong("searched_at");
            j.lastError = o.optString("last_error");
            j.publishedUrl = o.optString("published_url");
            j.publishedAt = o.optString("published_at");
            j.previousSubtitle = o.optString("previous_subtitle");
            j.createdAt = o.optLong("created_at");
            j.updatedAt = o.optLong("updated_at");
            JSONArray h = o.optJSONArray("history");
            for (int i = 0; h != null && i < h.length(); i++) if (h.optJSONObject(i) != null) j.history.add(h.optJSONObject(i));
            // a job that was running when the process died is resumed, not lost
            if (j.state == State.SEARCHING) j.state = j.searchedAt > 0 ? State.FAILED : State.NOT_TRANSLATED;
            return j;
        }
    }

    // ------------------------------------------------------------------ batch checkpoint

    public static final class Batch {
        public String id = "";
        /** search | translate */
        public String type = "search";
        public String provider = "";
        public int size;
        public int done;
        public int failed;
        public int concurrency = 2;
        public double budget;
        public double estimatedCost;
        public String approvedAt = "";
        public boolean cancelled;
        public boolean paused;
        public long pausedUntil;
        public long startedAt;
        public long finishedAt;
        /** Content ids of this batch, in processing order (pagination = position in this list). */
        public final List<String> ids = new ArrayList<String>();
        public int cursor;

        public boolean active() {
            return startedAt > 0 && finishedAt == 0 && !cancelled;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject().put("id", id).put("type", type).put("provider", provider).put("size", size).put("done", done)
                    .put("failed", failed).put("concurrency", concurrency).put("budget", budget).put("estimated_cost", estimatedCost)
                    .put("approved_at", approvedAt).put("cancelled", cancelled).put("paused", paused).put("paused_until", pausedUntil)
                    .put("started_at", startedAt).put("finished_at", finishedAt).put("cursor", cursor);
            JSONArray a = new JSONArray();
            for (String s : ids) a.put(s);
            return o.put("ids", a);
        }

        static Batch fromJson(JSONObject o) {
            Batch b = new Batch();
            if (o == null) return b;
            b.id = o.optString("id");
            b.type = o.optString("type", "search");
            b.provider = o.optString("provider");
            b.size = o.optInt("size");
            b.done = o.optInt("done");
            b.failed = o.optInt("failed");
            b.concurrency = Math.max(1, Math.min(4, o.optInt("concurrency", 2)));
            b.budget = o.optDouble("budget", 0);
            b.estimatedCost = o.optDouble("estimated_cost", 0);
            b.approvedAt = o.optString("approved_at");
            b.cancelled = o.optBoolean("cancelled");
            b.paused = o.optBoolean("paused");
            b.pausedUntil = o.optLong("paused_until");
            b.startedAt = o.optLong("started_at");
            b.finishedAt = o.optLong("finished_at");
            b.cursor = o.optInt("cursor");
            JSONArray a = o.optJSONArray("ids");
            for (int i = 0; a != null && i < a.length(); i++) b.ids.add(a.optString(i));
            return b;
        }
    }

    // ------------------------------------------------------------------ queue

    public static final int MAX_ATTEMPTS = 6;
    public static final long BACKOFF_BASE_MS = 30_000L;
    public static final long BACKOFF_MAX_MS = 6L * 3600_000L;

    private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<String, Job>();
    private final Set<String> inFlight = new HashSet<String>();
    public Batch batch = new Batch();
    /** provider -> {day, used, limit} */
    private final Map<String, int[]> quota = new LinkedHashMap<String, int[]>();
    private final Map<String, String> quotaDay = new LinkedHashMap<String, String>();
    private final Random random = new Random();

    public synchronized int size() {
        return jobs.size();
    }

    public synchronized Job get(String id) {
        return jobs.get(id);
    }

    public synchronized List<Job> all() {
        return new ArrayList<Job>(jobs.values());
    }

    /**
     * Adds a catalogue title (or refreshes its ids / titles). Never creates a second job for the same content id —
     * duplicate prevention is by Cigram content id.
     */
    public synchronized Job upsert(Job candidate, long now) {
        Job j = jobs.get(candidate.contentId);
        if (j == null) {
            candidate.createdAt = now;
            candidate.updatedAt = now;
            jobs.put(candidate.contentId, candidate);
            return candidate;
        }
        j.title = candidate.title;
        j.seriesTitle = candidate.seriesTitle;
        j.seriesId = candidate.seriesId;
        if (candidate.tmdbId.length() > 0) j.tmdbId = candidate.tmdbId;
        if (candidate.imdbId.length() > 0) j.imdbId = candidate.imdbId;
        j.season = candidate.season;
        j.episode = candidate.episode;
        j.kind = candidate.kind;
        return j;
    }

    public synchronized void remove(String id) {
        jobs.remove(id);
        inFlight.remove(id);
    }

    /** Moves a job; throws IllegalStateException for a move the state machine does not allow. */
    public synchronized void move(Job j, State to, String note, long now) {
        if (j.state != to && !canMove(j.state, to)) {
            throw new IllegalStateException("انتقال غير مسموح: " + label(j.state) + " ← " + label(to));
        }
        State from = j.state;
        j.state = to;
        j.updatedAt = now;
        try {
            j.history.add(new JSONObject().put("at", now).put("from", from.name()).put("to", to.name()).put("note", note == null ? "" : note));
        } catch (JSONException ignored) { }
        while (j.history.size() > 60) j.history.remove(0);
    }

    /** Explicit admin approval. Only a checked file in review can be approved. */
    public synchronized void approve(Job j, String atIso, long now) {
        if (j.state != State.QUALITY_REVIEW) throw new IllegalStateException("يمكن اعتماد ملف في «مراجعة الجودة» فقط.");
        if ("FAIL".equals(j.quality)) throw new IllegalStateException("الفحص وجد أخطاء: صحّحها أو ارفع ملفاً آخر قبل الاعتماد.");
        if (j.translatedFile.length() == 0) throw new IllegalStateException("لا يوجد ملف عربي لاعتماده.");
        move(j, State.APPROVED, "اعتماد يدوي", now);
        j.approval = "approved";
        j.approvedAt = atIso;
    }

    public synchronized void reject(Job j, String reason, long now) {
        move(j, State.NEEDS_CORRECTION, reason, now);
        j.approval = "rejected";
    }

    public synchronized void markPublished(Job j, String url, String atIso, String previous, long now) {
        if (j.state != State.APPROVED) throw new IllegalStateException("النشر يتطلب ملفاً معتمداً.");
        move(j, State.PUBLISHED, url, now);
        j.publishedUrl = url;
        j.publishedAt = atIso;
        j.previousSubtitle = previous == null ? "" : previous;
        j.lastError = "";
    }

    /** A failed attempt: FAILED with the next attempt delayed by 30 s × 2^(n-1) (±20 % jitter, max 6 h). */
    public synchronized void fail(Job j, String error, long now) {
        j.attempts++;
        j.lastError = error == null ? "" : error;
        j.nextAttemptAt = now + backoff(j.attempts);
        if (j.state != State.FAILED) move(j, State.FAILED, error, now);
    }

    public long backoff(int attempt) {
        long d = BACKOFF_BASE_MS << Math.min(20, Math.max(0, attempt - 1));
        d = Math.min(d, BACKOFF_MAX_MS);
        double jitter = 0.8 + 0.4 * random.nextDouble();
        return Math.min(BACKOFF_MAX_MS, Math.round(d * jitter));
    }

    public static long backoffNoJitter(int attempt) {
        long d = BACKOFF_BASE_MS << Math.min(20, Math.max(0, attempt - 1));
        return Math.min(d, BACKOFF_MAX_MS);
    }

    /** A job of the running batch that still has to be searched (or retried after a failure). */
    private boolean pending(Job j) {
        if (inFlight.contains(j.contentId)) return true;
        if (j.state == State.NOT_TRANSLATED) return j.searchedAt < batch.startedAt;
        return j.state == State.FAILED && j.attempts < MAX_ATTEMPTS;
    }

    /** True when the search runner may take this job now. */
    public synchronized boolean searchable(Job j, long now) {
        if (inFlight.contains(j.contentId)) return false;
        if (j.state == State.NOT_TRANSLATED) return j.searchedAt < batch.startedAt || j.searchedAt == 0;
        return j.state == State.FAILED && j.attempts < MAX_ATTEMPTS && now >= j.nextAttemptAt;
    }

    /**
     * Starts a batch over {@code ids} (in this order). Jobs that failed before get a fresh set of attempts.
     * Nothing is started without the caller having the admin's explicit confirmation.
     */
    public synchronized void startBatch(String id, String type, List<String> ids, int concurrency, long now) {
        batch = new Batch();
        batch.id = id;
        batch.type = type;
        batch.concurrency = Math.max(1, Math.min(4, concurrency));
        batch.startedAt = now;
        for (String c : ids) {
            Job j = jobs.get(c);
            if (j == null) continue;
            if (j.state == State.FAILED) { j.attempts = 0; j.nextAttemptAt = 0; }
            batch.ids.add(c);
        }
        batch.size = batch.ids.size();
    }

    /**
     * The next jobs of the running batch, in order, from the persistent cursor (pagination). Locks them (in flight)
     * so two workers never process the same title. A failed job waiting for its backoff keeps the cursor in front of
     * it, so it is retried inside the same batch. Returns an empty list when the batch is paused, cancelled or done.
     */
    public synchronized List<Job> claim(int max, long now) {
        List<Job> out = new ArrayList<Job>();
        if (!batch.active()) return out;
        if (batch.paused) {
            if (batch.pausedUntil == 0 || now < batch.pausedUntil) return out;
            batch.paused = false;
            batch.pausedUntil = 0;
        }
        boolean prefixSettled = true;
        for (int scan = batch.cursor; scan < batch.ids.size() && out.size() < max; scan++) {
            Job j = jobs.get(batch.ids.get(scan));
            if (j == null || !pending(j)) {
                if (prefixSettled) batch.cursor = scan + 1;
                continue;
            }
            prefixSettled = false;
            if (searchable(j, now)) {
                inFlight.add(j.contentId);
                out.add(j);
            }
        }
        return out;
    }

    /** When the next retry of the running batch is due (Long.MAX_VALUE when nothing waits). */
    public synchronized long nextWakeAt() {
        long t = Long.MAX_VALUE;
        if (!batch.active()) return t;
        if (batch.paused && batch.pausedUntil > 0) return batch.pausedUntil;
        for (int k = batch.cursor; k < batch.ids.size(); k++) {
            Job j = jobs.get(batch.ids.get(k));
            if (j != null && j.state == State.FAILED && j.attempts < MAX_ATTEMPTS && !inFlight.contains(j.contentId)) t = Math.min(t, j.nextAttemptAt);
        }
        return t;
    }

    /** Moves the cursor over settled jobs (after workers finished) and tells whether the batch is complete. */
    public synchronized boolean batchFinished() {
        if (!batch.active()) return false;
        while (batch.cursor < batch.ids.size()) {
            Job j = jobs.get(batch.ids.get(batch.cursor));
            if (j != null && pending(j)) return false;
            batch.cursor++;
        }
        return inFlight.isEmpty();
    }

    public synchronized void release(Job j) {
        inFlight.remove(j.contentId);
    }

    public synchronized int inFlightCount() {
        return inFlight.size();
    }

    /** Pauses the whole batch after an HTTP 429 (rate limit): every worker waits, then the batch continues by itself. */
    public synchronized void rateLimited(long now, long retryAfterMs) {
        batch.paused = true;
        batch.pausedUntil = now + Math.max(60_000L, retryAfterMs);
    }

    // ------------------------------------------------------------------ quotas

    /** Sets the daily limit of a provider (downloads). 0 = no limit known. */
    public synchronized void setQuota(String provider, int perDay) {
        int[] q = quota.get(provider);
        if (q == null) { q = new int[]{0, 0}; quota.put(provider, q); }
        q[1] = Math.max(0, perDay);
    }

    public synchronized boolean quotaLeft(String provider, String today) {
        int[] q = quota.get(provider);
        if (q == null || q[1] == 0) return true;
        if (!today.equals(quotaDay.get(provider))) return true;
        return q[0] < q[1];
    }

    public synchronized void useQuota(String provider, String today) {
        int[] q = quota.get(provider);
        if (q == null) { q = new int[]{0, 0}; quota.put(provider, q); }
        if (!today.equals(quotaDay.get(provider))) { quotaDay.put(provider, today); q[0] = 0; }
        q[0]++;
    }

    public synchronized int quotaUsed(String provider, String today) {
        int[] q = quota.get(provider);
        return q == null || !today.equals(quotaDay.get(provider)) ? 0 : q[0];
    }

    public synchronized int quotaLimit(String provider) {
        int[] q = quota.get(provider);
        return q == null ? 0 : q[1];
    }

    // ------------------------------------------------------------------ numbers

    public synchronized Map<State, Integer> counts() {
        Map<State, Integer> m = new EnumMap<State, Integer>(State.class);
        for (State s : State.values()) m.put(s, 0);
        for (Job j : jobs.values()) m.put(j.state, m.get(j.state) + 1);
        return m;
    }

    /** Cost estimate for translating {@code list}: exact for jobs whose source text is known, "unknown" for the rest. */
    public static final class Estimate {
        public int jobs;
        public int known;
        public int unknown;
        public long chars;
        public double cost;
        public double pricePer1000Chars;
    }

    public static Estimate estimate(Collection<Job> list, double pricePer1000Chars) {
        Estimate e = new Estimate();
        e.pricePer1000Chars = pricePer1000Chars;
        for (Job j : list) {
            e.jobs++;
            if (j.chars > 0) { e.known++; e.chars += j.chars; }
            else e.unknown++;
        }
        e.cost = pricePer1000Chars > 0 ? e.chars / 1000.0 * pricePer1000Chars : 0;
        return e;
    }

    // ------------------------------------------------------------------ persistence

    public synchronized JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("version", 1);
        JSONArray a = new JSONArray();
        for (Job j : jobs.values()) a.put(j.toJson());
        o.put("jobs", a);
        o.put("batch", batch.toJson());
        JSONObject q = new JSONObject();
        for (Map.Entry<String, int[]> e : quota.entrySet()) {
            q.put(e.getKey(), new JSONObject().put("used", e.getValue()[0]).put("limit", e.getValue()[1]).put("day", quotaDay.get(e.getKey()) == null ? "" : quotaDay.get(e.getKey())));
        }
        o.put("quota", q);
        return o;
    }

    public static SubQueue fromJson(JSONObject o) {
        SubQueue q = new SubQueue();
        if (o == null) return q;
        JSONArray a = o.optJSONArray("jobs");
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            Job j = Job.fromJson(x);
            if (j.contentId.length() > 0 && !q.jobs.containsKey(j.contentId)) q.jobs.put(j.contentId, j);
        }
        q.batch = Batch.fromJson(o.optJSONObject("batch"));
        JSONObject qq = o.optJSONObject("quota");
        if (qq != null) {
            JSONArray names = qq.names();
            for (int i = 0; names != null && i < names.length(); i++) {
                String p = names.optString(i);
                JSONObject v = qq.optJSONObject(p);
                if (v == null) continue;
                q.quota.put(p, new int[]{v.optInt("used"), v.optInt("limit")});
                q.quotaDay.put(p, v.optString("day"));
            }
        }
        return q;
    }
}
