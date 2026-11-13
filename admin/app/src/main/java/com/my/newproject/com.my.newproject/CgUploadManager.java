package com.my.newproject;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the whole life of an upload: (compress) -> create / continue the multipart upload -> upload the
 * parts -> complete -> verify -> publish the link. One job at a time, state kept on disk, progress
 * pushed to whichever screen is listening, long work kept alive by CgUploadService.
 * The old APK uploader (CgUpdateUpload) is not used or changed by any of this.
 */
final class CgUploadManager {

    interface Listener {
        /** Called on the main thread with a private copy of the session. */
        void onSession(CgUploadSession s);
    }

    private static final String TAG = CgMediaAnalyzer.TAG;
    static final String CHANNEL = "cigram_upload";
    static final int NOTIF_PROGRESS = 7741;
    static final int NOTIF_RESULT = 7742;

    private static CgUploadManager instance;

    static synchronized CgUploadManager get(Context c) {
        if (instance == null) instance = new CgUploadManager(c.getApplicationContext());
        return instance;
    }

    /** What stops a running job early. */
    private static final class JobStop extends Exception {
        final boolean cancel;

        JobStop(boolean cancel) {
            super(cancel ? "cancelled" : "paused");
            this.cancel = cancel;
        }
    }

    /** A decision is needed from the admin (the object already exists on R2). */
    private static final class NeedsDecision extends Exception {
        NeedsDecision() {
            super("needs decision");
        }
    }

    /** The job is finished on its own (e.g. "use the existing file"). */
    private static final class AlreadyDone extends Exception {
        AlreadyDone() {
            super("done");
        }
    }

    private final Context app;
    private final CgUploadStorage store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private volatile String runningId = "";
    /** Uploads waiting for the running one (season bulk upload). Not persisted: after a restart they show as paused. */
    private final ArrayList<String> queued = new ArrayList<String>();
    private volatile boolean pauseRequested;
    private volatile boolean cancelRequested;
    private CgMediaCompressor compressor;
    private CgMultipartUpload engine;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private long lastPublish;
    private long lastNotify;
    private boolean netWatching;
    private boolean serviceUp;

    private CgUploadManager(Context app) {
        this.app = app;
        this.store = new CgUploadStorage(app);
        recoverOnLaunch();
        cleanOrphans();
    }

    // ================================================================== public API

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Newest first. Private copies. */
    ArrayList<CgUploadSession> list() {
        ArrayList<CgUploadSession> out = new ArrayList<CgUploadSession>();
        ArrayList<CgUploadSession> all = store.all();
        for (int i = 0; i < all.size(); i++) out.add(all.get(i).copy());
        Collections.sort(out, new Comparator<CgUploadSession>() {
            @Override
            public int compare(CgUploadSession a, CgUploadSession b) {
                return a.createdAt < b.createdAt ? 1 : a.createdAt > b.createdAt ? -1 : 0;
            }
        });
        return out;
    }

    CgUploadSession snapshot(String id) {
        CgUploadSession s = store.get(id);
        return s == null ? null : s.copy();
    }

    /** First upload that is neither finished nor cancelled (for the "unfinished upload" banner). */
    CgUploadSession firstUnfinished() {
        ArrayList<CgUploadSession> all = list();
        for (int i = 0; i < all.size(); i++) {
            if (!all.get(i).isFinished()) return all.get(i);
        }
        return null;
    }

    boolean busy() {
        return runningId.length() > 0;
    }

    /**
     * Starts the session now when nothing runs, otherwise puts it at the end of the queue (the next one starts by
     * itself when the current one ends, whatever its result). Returns "" when started / queued, else a message.
     */
    synchronized String enqueue(String id) {
        CgUploadSession s = store.get(id);
        if (s == null) return "عملية الرفع غير موجودة.";
        if (s.isFinished()) return "هذه العملية انتهت.";
        if (id.equals(runningId)) return "";
        if (runningId.length() == 0) return start(id);
        if (!queued.contains(id)) queued.add(id);
        s.message = "في الانتظار — ستبدأ تلقائياً بعد الملف الحالي.";
        s.queued = true;
        store.put(s);
        publish(s, true);
        return "";
    }

    int queueSize() {
        synchronized (this) {
            return queued.size();
        }
    }

    /** Starts the next waiting upload (skips the ones that were cancelled / finished meanwhile). */
    private synchronized void startNext() {
        if (runningId.length() > 0) return;
        while (!queued.isEmpty()) {
            if (!isOnline()) {
                startNetWatch();
                return;
            }
            String next = queued.remove(0);
            CgUploadSession s = store.get(next);
            if (s == null || s.isFinished() || s.needsDecision || !s.isResumable()) continue;
            if (start(next).length() == 0) return;
        }
    }

    /** Temporary folder for compressed copies: app-specific storage (no permission needed), the roomier volume. */
    static File tempDir(Context c) {
        File internal = new File(c.getApplicationContext().getFilesDir(), "cigram_upload");
        File external = null;
        try {
            File ext = c.getApplicationContext().getExternalFilesDir(null);
            if (ext != null) external = new File(ext, "cigram_upload");
        } catch (Throwable ignored) { }
        if (external != null && CgSmartPlan.freeBytes(external) >= CgSmartPlan.freeBytes(internal)) return external;
        return internal;
    }

    /** Existing unfinished session for exactly the same file and target (stops a second copy of the same upload). */
    CgUploadSession findDuplicate(String srcUri, String kind, String slug, int season, int episode) {
        ArrayList<CgUploadSession> all = list();
        for (int i = 0; i < all.size(); i++) {
            CgUploadSession s = all.get(i);
            if (s.isFinished()) continue;
            if (s.srcUri.equals(srcUri) && s.kind.equals(kind) && s.slug.equals(slug) && s.season == season && s.episode == episode) return s;
        }
        return null;
    }

    CgUploadSession create(CgMediaInfo info, CgSmartPlan plan, String kind, String title, String poster, String slug, int season, int episode, String onExists) {
        CgUploadSession s = new CgUploadSession();
        s.id = UUID.randomUUID().toString();
        s.kind = kind;
        s.title = title == null ? "" : title;
        s.poster = poster == null ? "" : poster;
        s.slug = slug;
        s.season = season;
        s.episode = episode;
        s.srcUri = info.uri;
        s.srcName = info.fileName;
        s.srcSize = info.size;
        s.srcDurationMs = info.durationMs;
        s.srcResolution = info.resolutionText();
        s.srcContainer = info.container;
        s.srcCodec = info.videoLabel;
        s.mode = plan.mode;
        s.videoMime = plan.videoMime;
        s.targetBitrate = plan.targetBitrate;
        s.uploadSize = info.size;
        s.uploadName = info.fileName;
        s.onExists = onExists == null ? "" : onExists;
        s.state = CgUploadSession.PAUSED;
        s.createdAt = System.currentTimeMillis();
        s.updatedAt = s.createdAt;
        store.put(s);
        return s.copy();
    }

    /** Starts (or continues) a session. Returns "" when started, otherwise a message for the admin. */
    synchronized String start(String id) {
        CgUploadSession s = store.get(id);
        if (s == null) return "عملية الرفع غير موجودة.";
        if (runningId.length() > 0) {
            return runningId.equals(id) ? "" : "هناك عملية رفع أخرى جارية الآن. انتظر انتهاءها أو ألغِها أولاً.";
        }
        if (s.isFinished()) return "هذه العملية انتهت.";
        if (s.needsDecision) return "الملف موجود مسبقاً في R2. اختر: استخدام الموجود أو الاستبدال أو اسم جديد.";
        runningId = id;
        queued.remove(id);
        s.queued = false;
        pauseRequested = false;
        cancelRequested = false;
        s.autoResume = true;
        s.waitingNetwork = false;
        s.message = "";
        s.state = s.mode != 0 && !s.tempReady ? CgUploadSession.COMPRESSING : CgUploadSession.PREPARING_UPLOAD;
        s.stageStartedAt = System.currentTimeMillis();
        if (s.startedAt == 0) s.startedAt = s.stageStartedAt;
        store.put(s);
        publish(s, true);
        ensureService();
        final String jobId = id;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                runJob(jobId);
            }
        });
        return "";
    }

    void pause(String id) {
        if (!id.equals(runningId)) return;
        pauseRequested = true;
        CgMultipartUpload e = engine;
        if (e != null) e.pause();
    }

    /** Cancels: stops the job, aborts the multipart upload on R2, deletes the temporary file. The original video is never touched. */
    void cancel(final String id) {
        synchronized (this) {
            queued.remove(id);
        }
        if (id.equals(runningId)) {
            cancelRequested = true;
            CgMultipartUpload e = engine;
            if (e != null) e.cancel();
            CgMediaCompressor c = compressor;
            if (c != null) c.cancel();
            return;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                CgUploadSession s = store.get(id);
                if (s != null && !s.isFinished()) finalizeCancel(s);
            }
        });
    }

    /** Removes a finished / cancelled record from the list. */
    void remove(String id) {
        CgUploadSession s = store.get(id);
        if (s == null || s.isActive() || id.equals(runningId)) return;
        deleteTemp(s);
        store.remove(id);
        publishRemoved(s);
    }

    /** Admin's answer when the object already exists: "use" | "overwrite" | "rename". */
    String decide(String id, String decision) {
        CgUploadSession s = store.get(id);
        if (s == null) return "عملية الرفع غير موجودة.";
        if ("use".equals(decision)) {
            if (s.existingUrl.length() == 0) return "رابط الملف الموجود غير معروف.";
            s.publicUrl = s.existingUrl;
            s.finalSize = s.existingSize;
            s.needsDecision = false;
            s.state = CgUploadSession.COMPLETED;
            s.message = "تم استخدام الملف الموجود مسبقاً في R2.";
            s.autoResume = false;
            deleteTemp(s);
            store.put(s);
            publish(s, true);
            return "";
        }
        s.onExists = "rename".equals(decision) ? "rename" : "overwrite";
        s.needsDecision = false;
        store.put(s);
        return enqueue(id);   // starts now when idle, otherwise waits its turn behind the running upload
    }

    /** Switches an upload that cannot be compressed to the untouched original. */
    String switchToOriginal(String id) {
        CgUploadSession s = store.get(id);
        if (s == null) return "عملية الرفع غير موجودة.";
        if (id.equals(runningId)) return "العملية جارية.";
        deleteTemp(s);
        s.mode = 0;
        s.uploadSize = s.srcSize;
        s.uploadName = s.srcName;
        s.resetRemote();
        store.put(s);
        return start(id);
    }

    /** The admin picked the same file again after the old permission expired. Returns "" when it is the same file. */
    String relink(String id, Uri newUri, long newSize, String newName) {
        CgUploadSession s = store.get(id);
        if (s == null) return "عملية الرفع غير موجودة.";
        if (newSize != s.srcSize) return "الملف المختار يختلف في الحجم عن الملف الأصلي لهذه العملية.";
        s.srcUri = newUri.toString();
        if (newName != null && newName.length() > 0) s.srcName = newName;
        store.put(s);
        return "";
    }

    // ============================================================= recovery

    /** After the app / phone restarted nothing is running: show stopped uploads as stopped, never as "running". */
    private void recoverOnLaunch() {
        ArrayList<CgUploadSession> all = store.all();
        for (int i = 0; i < all.size(); i++) {
            CgUploadSession s = all.get(i);
            if (s.isActive()) {
                s.state = CgUploadSession.PAUSED;
                s.message = "انقطعت العملية لأن التطبيق أُغلق. اضغط «متابعة» لإكمالها من حيث توقفت.";
                store.put(s);
            }
        }
    }

    /** Sticky restart of the service after the system killed the process: continue what was running. */
    void resumeInterrupted() {
        ArrayList<CgUploadSession> all = store.all();
        for (int i = 0; i < all.size(); i++) {
            CgUploadSession s = all.get(i);
            if (s.isResumable() && s.autoResume && !s.needsDecision) {
                if (start(s.id).length() == 0) return;
            }
        }
        maybeStopService();
    }

    private void cleanOrphans() {
        try {
            File dir = tempDir(app);
            File[] files = dir.listFiles();
            if (files == null) return;
            ArrayList<CgUploadSession> all = store.all();
            long now = System.currentTimeMillis();
            for (int i = 0; i < files.length; i++) {
                boolean used = false;
                for (int k = 0; k < all.size(); k++) {
                    if (files[i].getAbsolutePath().equals(all.get(k).uploadPath) && !all.get(k).isFinished()) used = true;
                    if (files[i].getName().startsWith(all.get(k).id) && !all.get(k).isFinished()) used = true;
                }
                if (!used && now - files[i].lastModified() > 6L * 3600L * 1000L) {
                    //noinspection ResultOfMethodCallIgnored
                    files[i].delete();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "cleanOrphans failed: " + t.getMessage());
        }
    }

    // ================================================================== the job

    private void runJob(String id) {
        CgUploadSession s = store.get(id);
        if (s == null) {
            finishRun();
            return;
        }
        acquireLocks();
        try {
            if (s.mode != 0 && !(s.tempReady && tempFileOk(s))) {
                s.tempReady = false;
                compressStage(s);
            }
            try {
                prepareStage(s);
            } catch (AlreadyDone d) {
                completeWithUrl(s);
                return;
            }
            uploadStage(s);
            finishStage(s);
        } catch (JobStop stop) {
            if (stop.cancel) {
                finalizeCancel(s);
            } else {
                s.state = CgUploadSession.PAUSED;
                s.message = "تم إيقاف الرفع مؤقتاً. اضغط «استكمال» لإكماله من حيث توقف.";
                s.autoResume = false;
                s.speedBps = 0;
                store.put(s);
                publish(s, true);
            }
        } catch (NeedsDecision nd) {
            s.state = CgUploadSession.PAUSED;
            s.needsDecision = true;
            s.autoResume = false;
            s.message = "الملف موجود مسبقاً في R2. اختر ما تريد فعله.";
            store.put(s);
            publish(s, true);
        } catch (CgMultipartUpload.NetworkDown nd) {
            CgDiag.init(app);
            CgDiag.recovery("upload", "network-down", "NETWORK", "waiting for the network, uploaded parts are kept", s.id, 0, "waiting");
            s.state = CgUploadSession.PAUSED;
            s.waitingNetwork = true;
            s.message = "انقطع الاتصال بالإنترنت. سيُستكمل الرفع تلقائياً عند عودته (الأجزاء المرفوعة محفوظة).";
            s.speedBps = 0;
            store.put(s);
            publish(s, true);
            startNetWatch();
        } catch (CgMultipartUpload.Fatal f) {
            fail(s, f.getMessage(), f.code);
        } catch (CgMediaCompressor.Cancelled c) {
            finalizeCancel(s);
        } catch (CgMediaCompressor.Failed f) {
            Log.e(TAG, "compress failed: " + f.getMessage());
            s.tempReady = false;
            deleteTemp(s);
            fail(s, f.getMessage(), "COMPRESS");
        } catch (Throwable t) {
            Log.e(TAG, "job crashed", t);
            fail(s, "حدث خطأ غير متوقع أثناء الرفع. أعد المحاولة.", "UNEXPECTED");
        } finally {
            releaseLocks();
            finishRun();
        }
    }

    private void finishRun() {
        runningId = "";
        engine = null;
        compressor = null;
        try {
            startNext();
        } catch (Throwable t) {
            Log.e(TAG, "startNext failed", t);
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                maybeStopService();
            }
        });
    }

    private void fail(CgUploadSession s, String message, String code) {
        // 19.3: a failed upload leaves a trace (code + message, never the file name or the link) that explains it later
        CgDiag.init(app);
        CgDiag.recovery("upload", "failed", code == null || code.length() == 0 ? "OTHER" : code, message == null ? "" : message, s.id, 0, "failed:" + (code == null ? "" : code));
        s.state = CgUploadSession.FAILED;
        s.message = message;
        s.autoResume = false;
        s.speedBps = 0;
        if ("SIZE_CHANGED".equals(code)) s.resetRemote();
        store.put(s);
        publish(s, true);
        notifyResult(s, false);
    }

    // ------------------------------------------------------------------ stage: compress

    private void compressStage(final CgUploadSession s) throws CgMediaCompressor.Failed, CgMediaCompressor.Cancelled, JobStop {
        setStage(s, CgUploadSession.COMPRESSING, "جاري ضغط الفيديو...");
        Uri src = Uri.parse(s.srcUri);
        CgMediaInfo info;
        try {
            info = CgMediaAnalyzer.analyze(app, src);
        } catch (SecurityException e) {
            throw new CgMediaCompressor.Failed("انتهت صلاحية الوصول إلى الفيديو. اختره من جديد ثم استكمل.");
        } catch (Exception e) {
            throw new CgMediaCompressor.Failed(e.getMessage() == null ? "تعذر قراءة الفيديو." : e.getMessage());
        }
        if (s.srcSize > 0 && info.size > 0 && info.size != s.srcSize) {
            throw new CgMediaCompressor.Failed("حجم الفيديو تغيّر منذ اختياره. اختر الملف من جديد.");
        }
        File dir = tempDir(app);
        File out = new File(dir, s.id + ".mp4");
        s.uploadPath = out.getAbsolutePath();
        s.tempReady = false;
        s.bytesTotal = s.srcSize;
        store.put(s);
        compressor = new CgMediaCompressor(app, src, info, out, s.videoMime, s.targetBitrate, new CgMediaCompressor.Progress() {
            @Override
            public void onProgress(float fraction, long outBytes) {
                s.stageFraction = fraction;
                s.outBytes = outBytes;
                publish(s, false);
                notifyProgress(s, false);
            }
        });
        if (cancelRequested) throw new JobStop(true);
        compressor.run();
        long outSize = out.length();
        if (outSize <= 0) throw new CgMediaCompressor.Failed("الملف المضغوط فارغ.");
        if (s.srcSize > 0 && outSize >= (long) (s.srcSize * 0.97)) {
            // no real saving: quality first -> keep the untouched original
            deleteTemp(s);
            s.mode = 0;
            s.uploadSize = s.srcSize;
            s.uploadName = s.srcName;
            s.message = "الضغط لم يوفّر حجماً يُذكر، لذلك سيُرفع الملف الأصلي كما هو.";
            store.put(s);
            return;
        }
        s.tempReady = true;
        s.uploadSize = outSize;
        s.uploadName = baseName(s.srcName) + ".mp4";
        s.message = "تم الضغط: وُفّر " + CgUi.sizeText(s.srcSize - outSize) + " (" + Math.round(100.0 * (s.srcSize - outSize) / s.srcSize) + "%). تم الحفاظ على الدقة.";
        store.put(s);
    }

    private boolean tempFileOk(CgUploadSession s) {
        if (s.uploadPath.length() == 0) return false;
        File f = new File(s.uploadPath);
        return f.exists() && f.length() == s.uploadSize && s.uploadSize > 0;
    }

    // ----------------------------------------------------------------- stage: prepare

    private void prepareStage(CgUploadSession s) throws JobStop, NeedsDecision, AlreadyDone, CgMultipartUpload.NetworkDown, CgMultipartUpload.Fatal {
        setStage(s, CgUploadSession.PREPARING_UPLOAD, "جاري تجهيز الرفع...");
        if (cancelRequested) throw new JobStop(true);
        if (s.mode == 0) {
            s.uploadSize = s.srcSize;
            s.uploadName = s.srcName;
            if (s.uploadSize <= 0) throw new CgMultipartUpload.Fatal("تعذر معرفة حجم الملف.", "SOURCE");
            long now = sourceSize(s);
            if (now > 0 && s.srcSize > 0 && now != s.srcSize) throw new CgMultipartUpload.Fatal("حجم الملف تغيّر منذ اختياره. اختره من جديد.", "SIZE_CHANGED");
        } else if (!tempFileOk(s)) {
            throw new CgMultipartUpload.Fatal("الملف المضغوط المؤقت لم يعد موجوداً. سيُعاد الضغط عند الاستكمال.", "TEMP_MISSING");
        }
        s.bytesTotal = s.uploadSize;

        if (s.uploadId.length() > 0) {
            if (reconcile(s)) return;
        }
        if (s.uploadId.length() == 0) initRemote(s);
    }

    /** Returns true when the remote session is alive and in sync. */
    private boolean reconcile(CgUploadSession s) throws AlreadyDone, CgMultipartUpload.NetworkDown, CgMultipartUpload.Fatal {
        JSONObject st;
        try {
            st = CgMediaApi.status(s.objectKey, s.uploadId);
        } catch (CxApi.Failure f) {
            throw mapFailure(f);
        }
        boolean alive = st.optBoolean("alive", false);
        if (alive && st.optInt("partSize", s.partSize) == s.partSize) {
            JSONArray sp = st.optJSONArray("parts");
            // parts R2 confirmed to us (with an ETag) stay ours even if the Worker's ledger missed them
            // (parallel parts can race on that ledger); the server list only adds what we did not record.
            JSONArray mine = s.toJson().optJSONArray("parts");
            s.clearParts();
            if (sp != null) {
                for (int i = 0; i < sp.length(); i++) {
                    JSONObject p = sp.optJSONObject(i);
                    if (p != null) s.addPart(p.optInt("partNumber"), p.optString("etag", ""), p.optLong("size"));
                }
            }
            for (int i = 0; mine != null && i < mine.length(); i++) {
                JSONObject p = mine.optJSONObject(i);
                if (p != null && p.optString("etag", "").length() > 0 && !s.hasPart(p.optInt("n"))) {
                    s.addPart(p.optInt("n"), p.optString("etag", ""), p.optLong("size"));
                }
            }
            store.put(s);
            CgMediaApi.log("resume: " + s.partsDone() + "/" + s.totalParts + " parts already on R2");
            return true;
        }
        // The Worker forgot the upload. Maybe it finished but the answer never reached us.
        if (s.totalParts > 0 && s.partsDone() >= s.totalParts) {
            try {
                JSONObject v = CgMediaApi.verify(s.objectKey, s.uploadSize);
                if (v.optBoolean("exists") && v.optBoolean("matches")) {
                    s.publicUrl = v.optString("url", "");
                    s.finalSize = v.optLong("size");
                    if (s.publicUrl.length() > 0) throw new AlreadyDone();
                }
            } catch (CxApi.Failure f) {
                throw mapFailure(f);
            }
        }
        final String k = s.objectKey;
        final String u = s.uploadId;
        s.resetRemote();
        store.put(s);
        CgMediaApi.abort(k, u);
        return false;
    }

    private void initRemote(CgUploadSession s) throws NeedsDecision, CgMultipartUpload.NetworkDown, CgMultipartUpload.Fatal {
        try {
            JSONObject d = CgMediaApi.init(s.kind, s.slug, s.uploadName, s.uploadSize, s.season, s.episode, s.onExists);
            s.objectKey = d.optString("key", "");
            s.uploadId = d.optString("uploadId", "");
            s.partSize = d.optInt("partSize", 0);
            s.totalParts = d.optInt("parts", 0);
            if (s.objectKey.length() == 0 || s.uploadId.length() == 0 || s.partSize <= 0) {
                throw new CgMultipartUpload.Fatal("رد غير متوقع من الخادم عند بدء الرفع. أعد المحاولة.", "BAD_RESPONSE");
            }
            if (s.totalParts <= 0) s.totalParts = (int) ((s.uploadSize + s.partSize - 1) / s.partSize);
            s.clearParts();
            store.put(s);
        } catch (CxApi.Failure f) {
            if (f.is("OBJECT_EXISTS")) {
                JSONObject ex = f.existing();
                s.existingUrl = ex.optString("url", "");
                s.existingSize = ex.optLong("size");
                throw new NeedsDecision();
            }
            throw mapFailure(f);
        }
    }

    // ------------------------------------------------------------------ stage: upload

    private void uploadStage(final CgUploadSession s) throws JobStop, CgMultipartUpload.Fatal, CgMultipartUpload.NetworkDown, NeedsDecision {
        setStage(s, CgUploadSession.UPLOADING, "جاري الرفع إلى CIGRAM Cloud");
        for (int round = 0; round < 3; round++) {
            if (cancelRequested) throw new JobStop(true);
            if (pauseRequested) throw new JobStop(false);
            CgMultipartUpload.Source source = s.mode == 0 ? uriSource(Uri.parse(s.srcUri)) : fileSource(new File(s.uploadPath));
            engine = new CgMultipartUpload(source, s.uploadSize, s, new CgMultipartUpload.Listener() {
                @Override
                public void onProgress(long uploadedBytes, int currentPart, double speedBps) {
                    s.bytesDone = uploadedBytes;
                    s.bytesTotal = s.uploadSize;
                    s.currentPart = currentPart;
                    s.speedBps = speedBps;
                    s.stageFraction = s.uploadSize > 0 ? (float) ((double) uploadedBytes / (double) s.uploadSize) : 0f;
                    publish(s, false);
                    notifyProgress(s, false);
                }

                @Override
                public void onPartDone(int partNumber, String etag, long size) {
                    store.put(s);
                }
            });
            // several parts at once: 4 on Wi-Fi / unmetered, 3 on mobile data
            engine.setParallel(isMetered() ? 3 : 4);
            engine.setNetGate(new CgMultipartUpload.NetGate() {
                @Override
                public boolean online() {
                    return isOnline();
                }

                @Override
                public void waiting(boolean w) {
                    s.waitingNetwork = w;
                    s.message = w ? "انقطع الإنترنت — سيُستكمل الرفع تلقائياً فور عودته (الأجزاء المرفوعة محفوظة)." : "";
                    if (w) s.speedBps = 0;
                    publish(s, true);
                    notifyProgress(s, true);
                }
            });
            if (cancelRequested) engine.cancel();
            if (pauseRequested) engine.pause();
            try {
                engine.uploadRemaining();
                return;
            } catch (CgMultipartUpload.Stopped st) {
                throw new JobStop(!st.pause);
            } catch (CgMultipartUpload.SessionLost lost) {
                CgMediaApi.log("remote session lost, starting a new one");
                String k = s.objectKey;
                String u = s.uploadId;
                s.resetRemote();
                store.put(s);
                CgMediaApi.abort(k, u);
                initRemote(s);
            }
        }
        throw new CgMultipartUpload.Fatal("تعذر الاحتفاظ بجلسة الرفع على الخادم. أعد المحاولة لاحقاً.", "SESSION");
    }

    // ------------------------------------------------------------- stage: complete + verify

    private void finishStage(CgUploadSession s) throws JobStop, CgMultipartUpload.NetworkDown, CgMultipartUpload.Fatal {
        setStage(s, CgUploadSession.VERIFYING, "جاري إنهاء الرفع والتحقق من الملف...");
        if (cancelRequested) throw new JobStop(true);
        JSONArray list = new JSONArray();
        JSONArray parts = s.toJson().optJSONArray("parts");
        ArrayList<JSONObject> sorted = new ArrayList<JSONObject>();
        for (int i = 0; parts != null && i < parts.length(); i++) sorted.add(parts.optJSONObject(i));
        Collections.sort(sorted, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return a.optInt("n") - b.optInt("n");
            }
        });
        for (int i = 0; i < sorted.size(); i++) {
            JSONObject p = new JSONObject();
            CxJ.put(p, "partNumber", Integer.valueOf(sorted.get(i).optInt("n")));
            CxJ.put(p, "etag", sorted.get(i).optString("etag", ""));
            list.put(p);
        }
        if (list.length() != s.totalParts) {
            throw new CgMultipartUpload.Fatal("بعض الأجزاء غير مرفوعة بعد (" + list.length() + " من " + s.totalParts + "). استكمل الرفع.", "PARTS");
        }
        JSONObject body = new JSONObject();
        CxJ.put(body, "key", s.objectKey);
        CxJ.put(body, "uploadId", s.uploadId);
        CxJ.put(body, "parts", list);
        CxJ.put(body, "size", Long.valueOf(s.uploadSize));

        JSONObject result = null;
        CgMultipartUpload.NetworkDown netErr = null;
        for (int attempt = 1; attempt <= 3 && result == null; attempt++) {
            if (cancelRequested) throw new JobStop(true);
            try {
                result = CgMediaApi.complete(body);
            } catch (CxApi.Failure f) {
                if (f.is("UPLOAD_NOT_FOUND")) {
                    // already completed earlier (answer lost)? check the object itself
                    try {
                        JSONObject v = CgMediaApi.verify(s.objectKey, s.uploadSize);
                        if (v.optBoolean("exists") && v.optBoolean("matches")) {
                            result = v;
                            break;
                        }
                    } catch (CxApi.Failure vf) {
                        Log.w(TAG, "verify after lost session failed: " + vf.getMessage());
                    }
                    s.resetRemote();
                    store.put(s);
                    throw new CgMultipartUpload.Fatal("جلسة الرفع انتهت على الخادم قبل الإكمال. اضغط «استكمال» لإعادة رفع الملف.", "SESSION");
                }
                if (f.is("UPLOAD_INCOMPLETE")) {
                    s.resetRemote();
                    store.put(s);
                    throw new CgMultipartUpload.Fatal(f.getMessage(), "INCOMPLETE");
                }
                if (!isNetwork(f)) throw new CgMultipartUpload.Fatal(f.getMessage(), f.code);
                netErr = new CgMultipartUpload.NetworkDown(f.getMessage());
                sleepQuiet(attempt * 2000L);
            }
        }
        if (result == null) throw netErr != null ? netErr : new CgMultipartUpload.NetworkDown("تعذر إنهاء الرفع الآن.");

        // verify the object on R2 (exists, size > 0, same size we uploaded)
        JSONObject v;
        try {
            v = CgMediaApi.verify(s.objectKey, s.uploadSize);
        } catch (CxApi.Failure f) {
            throw mapFailure(f);
        }
        if (!v.optBoolean("exists") || v.optLong("size") <= 0 || !v.optBoolean("matches")) {
            throw new CgMultipartUpload.Fatal("فشل التحقق من الملف في R2 (غير موجود أو حجمه لا يطابق). لن يُنشر الرابط.", "VERIFY");
        }
        String url = v.optString("url", "");
        if (url.length() == 0) url = result.optString("publicUrl", result.optString("url", ""));
        if (url.length() == 0) throw new CgMultipartUpload.Fatal("لم يرجع الخادم رابط الملف.", "VERIFY");
        s.publicUrl = url;
        s.finalSize = v.optLong("size");
        s.warning = checkPublic(url, s.finalSize);
        completeWithUrl(s);
    }

    private void completeWithUrl(CgUploadSession s) {
        s.state = CgUploadSession.COMPLETED;
        s.stageFraction = 1f;
        s.bytesDone = s.bytesTotal;
        s.autoResume = false;
        s.waitingNetwork = false;
        s.needsDecision = false;
        s.speedBps = 0;
        s.message = s.message.length() > 0 && s.message.startsWith("تم الضغط") ? s.message : "تم رفع الملف والتحقق منه بنجاح.";
        // only now, after the upload is verified, the temporary compressed copy can go
        deleteTemp(s);
        store.put(s);
        publish(s, true);
        notifyResult(s, true);
    }

    /** Public URL reachable? Only a warning when it is not (r2.dev can lag for a moment). */
    private String checkPublic(String url, long size) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return "الرابط العام لا يستجيب بعد (HTTP " + code + "). اختبره قبل النشر.";
            long len = c.getContentLength();
            if (len > 0 && size > 0 && len != size) return "حجم الملف عبر الرابط العام لا يطابق المرفوع. اختبره قبل النشر.";
            return "";
        } catch (Exception e) {
            return "تعذر اختبار الرابط العام الآن. اختبره بزر «اختبار الفيديو» قبل النشر.";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------------- cancel / cleanup

    private void finalizeCancel(CgUploadSession s) {
        s.queued = false;
        final String k = s.objectKey;
        final String u = s.uploadId;
        if (u.length() > 0 && k.length() > 0) CgMediaApi.abort(k, u);
        deleteTemp(s);
        s.resetRemote();
        s.state = CgUploadSession.CANCELLED;
        s.message = "تم إلغاء الرفع. الفيديو الأصلي على هاتفك لم يتأثر.";
        s.autoResume = false;
        s.waitingNetwork = false;
        s.needsDecision = false;
        s.speedBps = 0;
        store.put(s);
        publish(s, true);
        clearProgressNotification();
    }

    private void deleteTemp(CgUploadSession s) {
        if (s.uploadPath.length() > 0) {
            try {
                File f = new File(s.uploadPath);
                if (f.exists() && !f.delete()) Log.w(TAG, "temp file could not be deleted");
                File a = new File(s.uploadPath + ".audio.m4a");
                if (a.exists()) //noinspection ResultOfMethodCallIgnored
                    a.delete();
            } catch (Throwable t) {
                Log.w(TAG, "deleteTemp failed: " + t.getMessage());
            }
        }
        s.uploadPath = "";
        s.tempReady = false;
    }

    // ============================================================ helpers: errors

    /** Connection problems / 5xx are resumable (NetworkDown); everything else is final (Fatal). */
    private static boolean isNetwork(CxApi.Failure f) {
        return "NETWORK".equals(f.code) || f.http >= 500 || f.http == 0 || f.http == 429 || f.http == 408;
    }

    /** Always throws. Written as "throw mapFailure(f)" so the compiler sees the end of the path. */
    private CgMultipartUpload.NetworkDown mapFailure(CxApi.Failure f) throws CgMultipartUpload.Fatal, CgMultipartUpload.NetworkDown {
        if (isNetwork(f)) throw new CgMultipartUpload.NetworkDown(f.getMessage());
        throw new CgMultipartUpload.Fatal(f.getMessage(), f.code);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) { }
    }

    private static String baseName(String name) {
        String n = name == null ? "video" : name;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.length() == 0 ? "video" : n;
    }

    private long sourceSize(CgUploadSession s) {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = app.getContentResolver().openFileDescriptor(Uri.parse(s.srcUri), "r");
            return pfd == null ? 0 : pfd.getStatSize();
        } catch (SecurityException e) {
            return 0;
        } catch (Exception e) {
            return 0;
        } finally {
            try {
                if (pfd != null) pfd.close();
            } catch (Exception ignored) { }
        }
    }

    // ============================================================ sources

    private CgMultipartUpload.Source uriSource(final Uri uri) {
        return new CgMultipartUpload.Source() {
            @Override
            public InputStream open(long offset) throws IOException {
                ParcelFileDescriptor pfd = null;
                try {
                    pfd = app.getContentResolver().openFileDescriptor(uri, "r");
                } catch (SecurityException e) {
                    throw e;
                } catch (Exception e) {
                    pfd = null;
                }
                if (pfd != null) {
                    ParcelFileDescriptor.AutoCloseInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                    try {
                        fis.getChannel().position(offset);
                        return fis;
                    } catch (IOException notSeekable) {
                        try {
                            fis.close();
                        } catch (IOException ignored) { }
                    }
                }
                InputStream in = app.getContentResolver().openInputStream(uri);
                if (in == null) throw new IOException("cannot open");
                skipFully(in, offset);
                return in;
            }
        };
    }

    private CgMultipartUpload.Source fileSource(final File f) {
        return new CgMultipartUpload.Source() {
            @Override
            public InputStream open(long offset) throws IOException {
                FileInputStream fis = new FileInputStream(f);
                try {
                    fis.getChannel().position(offset);
                } catch (IOException e) {
                    fis.close();
                    throw e;
                }
                return fis;
            }
        };
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        byte[] sink = null;
        while (left > 0) {
            long k = in.skip(left);
            if (k <= 0) {
                if (sink == null) sink = new byte[64 * 1024];
                int r = in.read(sink, 0, (int) Math.min(sink.length, left));
                if (r < 0) throw new IOException("unexpected end of file");
                k = r;
            }
            left -= k;
        }
    }

    // ============================================================ publish / UI

    private void setStage(CgUploadSession s, String state, String message) {
        s.state = state;
        s.stageFraction = 0f;
        s.stageStartedAt = System.currentTimeMillis();
        s.speedBps = 0;
        if (!CgUploadSession.COMPRESSING.equals(state)) s.outBytes = 0;
        s.waitingNetwork = false;
        // keep the "compressed, saved X" line visible during the upload stages
        boolean keep = s.message.startsWith("تم الضغط") || s.message.startsWith("الضغط لم يوفّر");
        if (!keep) s.message = message;
        store.put(s);
        publish(s, true);
        notifyProgress(s, true);
    }

    private void publish(CgUploadSession s, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastPublish < 250) return;
        lastPublish = now;
        s.updatedAt = now;
        final CgUploadSession copy = s.copy();
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onSession(copy);
            }
        });
    }

    private void publishRemoved(CgUploadSession s) {
        final CgUploadSession copy = s.copy();
        copy.state = "REMOVED";
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onSession(copy);
            }
        });
    }

    // ============================================================ service / locks / network

    private void ensureService() {
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!canRunForeground()) {
                        // Without these manifest permissions startForeground() would throw and Android would
                        // crash the app ("did not call startForeground in time"). Run without the service instead.
                        Log.w(TAG, "foreground service permissions missing: upload runs only while the app is open");
                        return;
                    }
                    Intent i = new Intent(app, CgUploadService.class);
                    if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i);
                    else app.startService(i);
                    serviceUp = true;
                } catch (Throwable t) {
                    Log.w(TAG, "foreground service could not start (the upload still runs while the app is open): " + t.getMessage());
                }
            }
        });
    }

    private boolean hasPermission(String perm) {
        try {
            return app.getPackageManager().checkPermission(perm, app.getPackageName()) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True only when the manifest really declares what a dataSync foreground service needs. */
    private boolean canRunForeground() {
        if (Build.VERSION.SDK_INT < 26) return true;
        if (Build.VERSION.SDK_INT >= 28 && !hasPermission("android.permission.FOREGROUND_SERVICE")) return false;
        if (Build.VERSION.SDK_INT >= 34 && !hasPermission("android.permission.FOREGROUND_SERVICE_DATA_SYNC")) return false;
        return true;
    }

    private void maybeStopService() {
        if (runningId.length() > 0) return;
        if (queueSize() > 0) return;
        ArrayList<CgUploadSession> all = store.all();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).waitingNetwork && all.get(i).autoResume && all.get(i).isResumable()) return;
        }
        try {
            app.stopService(new Intent(app, CgUploadService.class));
        } catch (Throwable ignored) { }
        serviceUp = false;
    }

    private void startNetWatch() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (netWatching) return;
                netWatching = true;
                main.postDelayed(netTick, 12000);
            }
        });
    }

    private final Runnable netTick = new Runnable() {
        @Override
        public void run() {
            CgUploadSession waiting = null;
            ArrayList<CgUploadSession> all = store.all();
            for (int i = 0; i < all.size(); i++) {
                CgUploadSession s = all.get(i);
                if (s.waitingNetwork && s.autoResume && s.isResumable()) {
                    waiting = s;
                    break;
                }
            }
            if (waiting == null) {
                if (queueSize() == 0) {
                    netWatching = false;
                    return;
                }
                if (runningId.length() == 0 && isOnline()) startNext();
                main.postDelayed(this, 12000);
                return;
            }
            if (runningId.length() == 0 && isOnline()) start(waiting.id);
            main.postDelayed(this, 12000);
        }
    };

    boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }

    boolean isMetered() {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.isActiveNetworkMetered();
        } catch (Throwable t) {
            return false;
        }
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cigram:media-upload");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(12L * 3600L * 1000L);
            }
        } catch (Throwable t) {
            Log.w(TAG, "wake lock unavailable: " + t.getMessage());
        }
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cigram:media-upload");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable t) {
            Log.w(TAG, "wifi lock unavailable: " + t.getMessage());
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) { }
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) { }
        wakeLock = null;
        wifiLock = null;
    }

    // ============================================================ notifications

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "رفع الفيديو", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("تقدم ضغط ورفع الأفلام والحلقات");
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable t) {
            Log.w(TAG, "channel failed: " + t.getMessage());
        }
    }

    private PendingIntent openUploads() {
        Intent i = new Intent(app, CgUploadsActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(app, 0, i, flags);
    }

    private Notification.Builder builder() {
        ensureChannel();
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(app, CHANNEL) : new Notification.Builder(app);
    }

    /** The ongoing notification (also what the foreground service starts with). */
    Notification progressNotification(CgUploadSession s) {
        Notification.Builder b = builder();
        b.setSmallIcon(android.R.drawable.stat_sys_upload);
        b.setContentTitle("CIGRAM Admin");
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        b.setContentIntent(openUploads());
        if (s == null) {
            b.setContentText("جاري التحضير...");
            b.setProgress(0, 0, true);
        } else {
            int pct = Math.max(0, Math.min(100, Math.round(s.stageFraction * 100f)));
            String what = s.title.length() > 0 ? s.title : s.srcName;
            boolean uploading = CgUploadSession.UPLOADING.equals(s.state);
            String title;
            String text;
            if (uploading && s.waitingNetwork) {
                title = "بانتظار عودة الإنترنت — " + pct + "%";
                text = what + " • سيُستكمل تلقائياً";
            } else if (CgUploadSession.COMPRESSING.equals(s.state)) {
                title = "جاري تجهيز الفيديو — " + pct + "%";
                text = what;
            } else if (uploading) {
                title = "جاري الرفع — " + pct + "%";
                StringBuilder sb = new StringBuilder(what);
                if (s.speedBps > 1024) {
                    sb.append(" • ").append(speedText(s.speedBps));
                    long left = s.bytesTotal - s.bytesDone;
                    if (left > 0) sb.append(" • متبقٍ ").append(etaText((long) (left / s.speedBps)));
                }
                text = sb.toString();
            } else if (CgUploadSession.VERIFYING.equals(s.state)) {
                title = "جاري التحقق من الملف";
                text = what;
            } else {
                title = "جاري التحضير";
                text = what;
            }
            int waiting = queueSize();
            b.setContentTitle(title);
            b.setContentText(text);
            if (waiting > 0) b.setSubText(waiting + " ملف في الانتظار");
            if (Build.VERSION.SDK_INT >= 16) b.setStyle(new Notification.BigTextStyle().bigText(text));
            if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
            boolean indeterminate = CgUploadSession.PREPARING_UPLOAD.equals(s.state) || CgUploadSession.VERIFYING.equals(s.state);
            b.setProgress(100, pct, indeterminate);
        }
        return b.build();
    }

    static String speedText(double bps) {
        double mb = bps / (1024.0 * 1024.0);
        if (mb >= 1) return String.format(java.util.Locale.US, "%.1f MB/s", mb);
        return String.format(java.util.Locale.US, "%.0f KB/s", bps / 1024.0);
    }

    static String etaText(long sec) {
        if (sec < 60) return sec + " ث";
        if (sec < 3600) return (sec / 60) + " د";
        return (sec / 3600) + " س " + ((sec % 3600) / 60) + " د";
    }

    /**
     * Some phones (Xiaomi, Huawei, Samsung "sleeping apps"...) stop a background upload even with the
     * foreground service unless the app is excluded from battery optimization. Asks once, with an explanation.
     */
    static void askBatteryExemption(final android.app.Activity a) {
        if (Build.VERSION.SDK_INT < 23 || a == null) return;
        try {
            PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
            if (pm == null || pm.isIgnoringBatteryOptimizations(a.getPackageName())) return;
            final android.content.SharedPreferences sp = a.getSharedPreferences(CgCfg.PREFS, Context.MODE_PRIVATE);
            if (sp.getBoolean("battery_asked", false)) return;
            sp.edit().putBoolean("battery_asked", true).apply();
            CgUi.confirm(a, "استمرار الرفع في الخلفية",
                    "حتى لا يوقف النظام الرفع عند الخروج من التطبيق أو إطفاء الشاشة، اسمح للتطبيق بالعمل دون قيود البطارية.",
                    "السماح", false, new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                i.setData(Uri.parse("package:" + a.getPackageName()));
                                a.startActivity(i);
                            } catch (Throwable t) {
                                try {
                                    a.startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                                } catch (Throwable ignored) { }
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "battery exemption prompt failed: " + t.getMessage());
        }
    }

    Notification currentNotification() {
        String id = runningId;
        CgUploadSession s = id.length() == 0 ? null : store.get(id);
        return progressNotification(s);
    }

    private void notifyProgress(CgUploadSession s, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotify < 1000) return;
        lastNotify = now;
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_PROGRESS, progressNotification(s));
        } catch (Throwable t) {
            Log.w(TAG, "notify failed: " + t.getMessage());
        }
    }

    private void clearProgressNotification() {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_PROGRESS);
        } catch (Throwable ignored) { }
    }

    private void notifyResult(CgUploadSession s, boolean ok) {
        // more files waiting (season upload): keep the progress bar in the shade, the next file takes it over
        if (queueSize() == 0) clearProgressNotification();
        try {
            Notification.Builder b = builder();
            b.setSmallIcon(ok ? android.R.drawable.stat_sys_upload_done : android.R.drawable.stat_notify_error);
            b.setContentTitle("CIGRAM Admin");
            String what = s.title.length() > 0 ? s.title : s.srcName;
            if (ok) b.setContentText(s.isEpisode() ? "تم رفع الحلقة بنجاح: " + what : "تم رفع الفيلم بنجاح: " + what);
            else b.setContentText("تعذر إكمال الرفع: " + what);
            b.setAutoCancel(true);
            b.setContentIntent(openUploads());
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_RESULT, b.build());
        } catch (Throwable t) {
            Log.w(TAG, "result notification failed: " + t.getMessage());
        }
    }
}
