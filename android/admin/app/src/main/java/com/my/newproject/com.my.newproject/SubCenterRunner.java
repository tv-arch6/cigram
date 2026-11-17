package com.my.newproject;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.Cigram.subhub.SubCodec;
import com.Cigram.subhub.SubDocument;
import com.Cigram.subhub.SubQa;
import com.Cigram.subhub.SubQueue;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Arabic Subtitle Center — the background batch runner (search existing Arabic subtitles for many titles).
 *
 * Uses only the Worker routes that exist today: {@code POST /admin/subs/search} and {@code POST /admin/subs/fetch}
 * ({@code mode: "text"}: the file comes back as text, nothing is uploaded or linked). Every found file is checked
 * locally (CigramSubtitleHub QA) and parked in «مراجعة الجودة» — the runner NEVER approves, publishes or links.
 *
 * Pagination = the batch's persistent cursor; concurrency 1-4 workers; a 429 pauses the whole batch; network / 5xx
 * failures retry with exponential backoff; a provider whose daily download quota is used up is skipped; a title without
 * a TMDB or IMDb id is never searched by name alone. The checkpoint is written after every title.
 */
final class SubCenterRunner {

    interface Listener {
        void changed();
    }

    private static SubCenterRunner instance;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context app;
    private final Object lock = new Object();
    private Thread coordinator;
    private ExecutorService pool;
    private volatile boolean running;
    private volatile String status = "";
    private final java.util.concurrent.CopyOnWriteArrayList<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<Listener>();

    private SubCenterRunner(Context c) {
        app = c.getApplicationContext();
    }

    static synchronized SubCenterRunner get(Context c) {
        if (instance == null) instance = new SubCenterRunner(c);
        return instance;
    }

    void addListener(Listener l) { listeners.add(l); }

    void removeListener(Listener l) { listeners.remove(l); }

    boolean isRunning() { return running; }

    String status() { return status; }

    private void notifyChanged() {
        MAIN.post(new Runnable() {
            @Override public void run() {
                for (Listener l : listeners) {
                    try { l.changed(); } catch (Throwable ignored) { }
                }
                SubCenterService.refresh(app);
            }
        });
    }

    // ------------------------------------------------------------------ control

    /** Starts (or resumes) the batch stored in the queue. The caller already has the admin's confirmation. */
    synchronized void start() {
        if (running) return;
        final SubQueue q = SubCenterStore.queue(app);
        if (!q.batch.active()) return;
        q.batch.paused = false;
        q.batch.pausedUntil = 0;
        SubCenterStore.save(app);
        running = true;
        status = "يعمل";
        final int workers = q.batch.concurrency;
        pool = Executors.newFixedThreadPool(workers, new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "subcenter-worker");
                t.setDaemon(true);
                return t;
            }
        });
        coordinator = new Thread(new Runnable() {
            @Override public void run() { loop(q, workers); }
        }, "subcenter-batch");
        coordinator.setDaemon(true);
        coordinator.start();
        notifyChanged();
    }

    /** Stops after the titles in progress (they finish or return to their previous state). The batch can be resumed. */
    void pause() {
        SubQueue q = SubCenterStore.queue(app);
        synchronized (q) {
            q.batch.paused = true;
            q.batch.pausedUntil = 0;
        }
        SubCenterStore.save(app);
        running = false;
        status = "متوقف مؤقتاً";
        synchronized (lock) { lock.notifyAll(); }
        notifyChanged();
    }

    void cancel() {
        SubQueue q = SubCenterStore.queue(app);
        synchronized (q) {
            q.batch.cancelled = true;
        }
        SubCenterStore.save(app);
        running = false;
        status = "أُلغيت الدفعة";
        synchronized (lock) { lock.notifyAll(); }
        notifyChanged();
    }

    private void loop(SubQueue q, int workers) {
        try {
            while (running) {
                long now = System.currentTimeMillis();
                if (q.batchFinished()) {
                    synchronized (q) { q.batch.finishedAt = now; }
                    SubCenterStore.save(app);
                    status = "اكتملت الدفعة";
                    break;
                }
                int free = workers - q.inFlightCount();
                if (free > 0) {
                    List<SubQueue.Job> jobs = q.claim(free, now);
                    for (final SubQueue.Job j : jobs) {
                        pool.submit(new Runnable() {
                            @Override public void run() { process(q, j); }
                        });
                    }
                }
                if (q.batch.paused && q.batch.pausedUntil == 0) break;   // manual pause / configuration stop
                if (!q.batch.active()) break;                              // cancelled
                long wake = q.nextWakeAt();
                long wait = Math.max(250L, Math.min(2000L, wake == Long.MAX_VALUE ? 2000L : wake - now));
                synchronized (lock) { lock.wait(wait); }
            }
        } catch (InterruptedException ignored) {
        } finally {
            running = false;
            try { pool.shutdown(); } catch (Throwable ignored) { }
            SubCenterStore.save(app);
            notifyChanged();
        }
    }

    /** Individual processing from the job screen (same steps as the batch, outside any batch). */
    void runOne(final SubQueue.Job j, final Runnable done) {
        final SubQueue q = SubCenterStore.queue(app);
        if (j.state == SubQueue.State.SEARCHING || j.state == SubQueue.State.TRANSLATING) { MAIN.post(done); return; }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (j.state == SubQueue.State.FAILED || j.state == SubQueue.State.NEEDS_CORRECTION || j.state == SubQueue.State.PUBLISHED
                            || j.state == SubQueue.State.QUALITY_REVIEW || j.state == SubQueue.State.SOURCE_FOUND) {
                        // moving back to the start is recorded; SEARCHING is entered by process()
                        if (!SubQueue.canMove(j.state, SubQueue.State.SEARCHING)) q.move(j, SubQueue.State.NOT_TRANSLATED, "بحث يدوي", System.currentTimeMillis());
                    }
                    process(q, j);
                } catch (Throwable ignored) {
                } finally {
                    MAIN.post(done);
                }
            }
        }, "subcenter-one");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ one title

    private void process(SubQueue q, SubQueue.Job j) {
        long now = System.currentTimeMillis();
        try {
            if (j.tmdbId.length() == 0 && j.imdbId.length() == 0) {
                // never by a similar name alone: the admin adds an id first
                synchronized (q) {
                    j.searchedAt = now;
                    j.lastError = "لا يوجد معرّف TMDB أو IMDb — لا يُبحث بالاسم وحده. أضف المعرّف ثم أعد البحث.";
                    j.updatedAt = now;
                }
                return;
            }
            q.move(j, SubQueue.State.SEARCHING, q.batch.active() ? "دفعة " + q.batch.id : "بحث فردي", now);
            JSONObject req = new JSONObject().put("item_id", j.contentId).put("all", true);
            JSONObject r = CxApi.post("/admin/subs/search", req);
            String mismatch = idMismatch(j, r.optJSONObject("target"));
            if (mismatch != null) {
                q.move(j, SubQueue.State.NEEDS_CORRECTION, mismatch, System.currentTimeMillis());
                synchronized (q) { j.lastError = mismatch; j.searchedAt = now; }
                return;
            }
            JSONObject pick = choose(j, r.optJSONArray("results"));
            if (pick == null) {
                synchronized (q) {
                    j.searchedAt = System.currentTimeMillis();
                    j.lastError = "لا توجد ترجمة عربية مطابقة في المصادر — يحتاج ترجمة.";
                }
                q.move(j, SubQueue.State.NOT_TRANSLATED, "لا توجد عربية", System.currentTimeMillis());
                return;
            }
            String provider = pick.optString("source");
            synchronized (q) {
                j.sourceProvider = provider;
                j.sourceRelease = pick.optString("release", pick.optString("name"));
                j.sourceLanguage = "ar";
                j.sourcePick = pick;
                j.searchedAt = System.currentTimeMillis();
            }
            q.move(j, SubQueue.State.SOURCE_FOUND, provider + " · " + j.sourceRelease, System.currentTimeMillis());
            String today = SubCenterStore.today();
            if (!q.quotaLeft(provider, today)) {
                synchronized (q) { j.lastError = "نفدت حصة التحميل اليومية من " + provider + " — يُجلب الملف لاحقاً."; }
                return;
            }
            JSONObject f = CxApi.post("/admin/subs/fetch", new JSONObject().put("item_id", j.contentId).put("mode", "text").put("pick", pick));
            q.useQuota(provider, today);
            String srt = f.optString("srt", "");
            if (srt.length() == 0) throw new CxApi.Failure("لم يصل نص الترجمة من الخادم.", "BAD_RESPONSE", null, 0);
            byte[] bytes = srt.getBytes("UTF-8");
            checkAndPark(app, q, j, bytes, "srt", "ar", provider + (f.optString("encoding").length() > 0 ? " · " + f.optString("encoding") : ""));
        } catch (CxApi.Failure e) {
            handleFailure(q, j, e);
        } catch (Throwable e) {
            q.fail(j, "خطأ غير متوقع: " + e.getClass().getSimpleName(), System.currentTimeMillis());
        } finally {
            q.release(j);
            SubCenterStore.save(app);
            synchronized (q) { if (q.batch.active()) q.batch.done = countDone(q); }
            notifyChanged();
            synchronized (lock) { lock.notifyAll(); }
        }
    }

    private static int countDone(SubQueue q) {
        int n = 0;
        for (int i = 0; i < q.batch.ids.size(); i++) {
            SubQueue.Job j = q.get(q.batch.ids.get(i));
            if (j == null) continue;
            if (j.state != SubQueue.State.NOT_TRANSLATED || j.searchedAt >= q.batch.startedAt) {
                if (j.state != SubQueue.State.SEARCHING && !(j.state == SubQueue.State.FAILED && j.attempts < SubQueue.MAX_ATTEMPTS)) n++;
            }
        }
        return n;
    }

    /** The ids the Worker resolved must be the ids of this catalogue title. */
    static String idMismatch(SubQueue.Job j, JSONObject target) {
        if (target == null) return null;
        String tt = target.optString("tmdb_id", ""), ti = target.optString("imdb_id", "");
        if (j.tmdbId.length() > 0 && tt.length() > 0 && !j.tmdbId.equals(tt)) return "معرّف TMDB الذي استخدمه الخادم (" + tt + ") لا يطابق العنوان (" + j.tmdbId + ").";
        if (j.imdbId.length() > 0 && ti.length() > 0 && !j.imdbId.equalsIgnoreCase(ti)) return "معرّف IMDb الذي استخدمه الخادم (" + ti + ") لا يطابق العنوان (" + j.imdbId + ").";
        if (j.isEpisode() && (target.optInt("season") != j.season || target.optInt("episode") != j.episode)) return "رقم الموسم/الحلقة في البحث لا يطابق الحلقة.";
        return null;
    }

    /** Best candidate that belongs to THIS film / episode (an episode never takes another episode's file). */
    static JSONObject choose(SubQueue.Job j, JSONArray rows) {
        if (rows == null) return null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject c = rows.optJSONObject(i);
            if (c == null) continue;
            if (j.isEpisode()) {
                int s = c.optInt("season"), e = c.optInt("episode");
                if (s > 0 && s != j.season) continue;
                if (e > 0 && e != j.episode) continue;
                if (e == 0 && !c.optBoolean("full_season")) continue;
            }
            if (c.optInt("score") < 40) continue;
            return c;
        }
        return null;
    }

    private void handleFailure(SubQueue q, SubQueue.Job j, CxApi.Failure e) {
        long now = System.currentTimeMillis();
        int http = e.http;
        String code = e.code == null ? "" : e.code;
        String errCode = e.json == null ? "" : e.json.optString("error", "");
        if (http == 429) {
            q.rateLimited(now, 60_000L);
            backToStart(q, j, "حدّ الطلبات (429) — توقف مؤقت");
            status = "حدّ الطلبات — استئناف تلقائي بعد دقيقة";
            return;
        }
        if ("subs_not_found".equals(errCode) || http == 404 && !"ROUTE_MISSING".equals(code)) {
            synchronized (q) { j.searchedAt = now; j.lastError = "لا توجد ترجمة عربية في المصادر — يحتاج ترجمة."; }
            backToStart(q, j, "لا توجد عربية");
            return;
        }
        if ("UNAUTHORIZED".equals(code) || "ROUTE_MISSING".equals(code) || "subs_no_keys".equals(errCode) || "subs_login_missing".equals(errCode)) {
            // configuration problem: stop the batch (resumable) instead of burning every title
            synchronized (q) { q.batch.paused = true; q.batch.pausedUntil = 0; }
            backToStart(q, j, e.getMessage());
            status = e.getMessage();
            running = false;
            return;
        }
        q.fail(j, e.getMessage(), now);
    }

    private static void backToStart(SubQueue q, SubQueue.Job j, String note) {
        try {
            if (j.state == SubQueue.State.SEARCHING) q.move(j, SubQueue.State.NOT_TRANSLATED, note, System.currentTimeMillis());
        } catch (IllegalStateException ignored) { }
    }

    // ------------------------------------------------------------------ shared with the job screen

    /**
     * Stores an Arabic file for a job, runs the local QA (and, when the original is known, the timing comparison) and
     * moves the job to «مراجعة الجودة» (or «يحتاج تصحيحاً» when the checks fail). Never approves.
     */
    static SubQa.Report checkAndPark(Context c, SubQueue q, SubQueue.Job j, byte[] bytes, String ext, String kind, String note) {
        SubDocument d = SubCodec.read(bytes);
        SubDocument source = null;
        byte[] orig = SubCenterStore.getText(c, j.originalFile);
        if (orig != null && !"ar".equals(j.sourceLanguage)) {
            try { source = SubCodec.read(orig); } catch (Throwable ignored) { }
        }
        SubQa.Report rep = SubQa.analyze(d, source, new SubQa.Options());
        String name = SubCenterStore.putText(c, j.contentId, kind, d.format == SubDocument.Format.VTT ? "vtt" : "srt",
                SubCodec.writeBytes(d, d.format));
        long now = System.currentTimeMillis();
        synchronized (q) {
            j.translatedFile = name;
            j.cues = d.size();
            j.quality = rep.verdict().name();
            j.qualitySummary = rep.summary();
            j.lastError = "";
            j.approval = "";
        }
        SubQueue.State target = rep.verdict() == SubQa.Verdict.FAIL ? SubQueue.State.NEEDS_CORRECTION : SubQueue.State.QUALITY_REVIEW;
        if (j.state != target && !SubQueue.canMove(j.state, target)) target = SubQueue.State.QUALITY_REVIEW;
        if (j.state != target && !SubQueue.canMove(j.state, target)) {
            // e.g. a manual file while a translation was queued: the queued step is abandoned first (recorded)
            SubQueue.State via = SubQueue.canMove(j.state, SubQueue.State.NOT_TRANSLATED) ? SubQueue.State.NOT_TRANSLATED : SubQueue.State.FAILED;
            q.move(j, via, "استُبدل بملف جديد", now);
        }
        q.move(j, target, note + " · " + rep.summary(), now);
        return rep;
    }
}
