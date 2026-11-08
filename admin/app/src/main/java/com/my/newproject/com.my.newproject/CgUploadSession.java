package com.my.newproject;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One movie / episode upload job. Everything needed to continue after the network drops, the app is
 * closed or the phone restarts is saved here (CgUploadStorage keeps it as JSON).
 */
final class CgUploadSession {

    // ---- states (IDLE / ANALYZING / READY only exist inside the upload screen and are not stored)
    static final String IDLE = "IDLE";
    static final String ANALYZING = "ANALYZING";
    static final String READY = "READY";
    static final String COMPRESSING = "COMPRESSING";
    static final String PREPARING_UPLOAD = "PREPARING_UPLOAD";
    static final String UPLOADING = "UPLOADING";
    static final String PAUSED = "PAUSED";
    static final String VERIFYING = "VERIFYING";
    static final String COMPLETED = "COMPLETED";
    static final String FAILED = "FAILED";
    static final String CANCELLED = "CANCELLED";

    // ---- identity
    String id = "";
    String kind = "movie";
    String title = "";
    String poster = "";
    String slug = "";
    int season;
    int episode;

    // ---- source
    String srcUri = "";
    String srcName = "";
    long srcSize;
    long srcDurationMs;
    String srcResolution = "";
    String srcContainer = "";
    String srcCodec = "";

    // ---- processing
    int mode;
    String videoMime = "";
    long targetBitrate;
    String uploadPath = "";
    boolean tempReady;
    long uploadSize;
    String uploadName = "";

    // ---- remote session
    String objectKey = "";
    String uploadId = "";
    int partSize;
    int totalParts;
    final JSONArray parts = new JSONArray();
    String onExists = "";
    boolean needsDecision;
    String existingUrl = "";
    long existingSize;

    // ---- result
    String publicUrl = "";
    long finalSize;
    String warning = "";

    // ---- state
    String state = PAUSED;
    String message = "";
    boolean autoResume;
    boolean waitingNetwork;
    long createdAt;
    long updatedAt;
    long startedAt;

    // ---- live numbers (not persisted: recomputed while the job runs)
    float stageFraction;
    long bytesDone;
    long bytesTotal;
    long outBytes;
    int currentPart;
    double speedBps;
    long stageStartedAt;
    /** Waiting in the manager's queue behind the running upload (live only, not persisted). */
    boolean queued;

    boolean isActive() {
        return COMPRESSING.equals(state) || PREPARING_UPLOAD.equals(state) || UPLOADING.equals(state) || VERIFYING.equals(state);
    }

    boolean isResumable() {
        return PAUSED.equals(state) || FAILED.equals(state);
    }

    boolean isFinished() {
        return COMPLETED.equals(state) || CANCELLED.equals(state);
    }

    boolean isEpisode() {
        return "episode".equals(kind);
    }

    synchronized int partsDone() {
        return parts.length();
    }

    synchronized boolean hasPart(int n) {
        for (int i = 0; i < parts.length(); i++) {
            if (parts.optJSONObject(i) != null && parts.optJSONObject(i).optInt("n") == n) return true;
        }
        return false;
    }

    synchronized void addPart(int n, String etag, long size) {
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p != null && p.optInt("n") == n) {
                CxJ.put(p, "etag", etag);
                CxJ.put(p, "size", Long.valueOf(size));
                return;
            }
        }
        JSONObject p = new JSONObject();
        CxJ.put(p, "n", Integer.valueOf(n));
        CxJ.put(p, "etag", etag);
        CxJ.put(p, "size", Long.valueOf(size));
        parts.put(p);
    }

    synchronized void clearParts() {
        while (parts.length() > 0) parts.remove(parts.length() - 1);
    }

    synchronized long partsBytes() {
        long total = 0;
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p != null) total += p.optLong("size");
        }
        return total;
    }

    void resetRemote() {
        objectKey = "";
        uploadId = "";
        partSize = 0;
        totalParts = 0;
        clearParts();
    }

    // -------------------------------------------------------------------------- JSON

    synchronized JSONObject toJson() {
        JSONObject o = new JSONObject();
        CxJ.put(o, "id", id);
        CxJ.put(o, "kind", kind);
        CxJ.put(o, "title", title);
        CxJ.put(o, "poster", poster);
        CxJ.put(o, "slug", slug);
        CxJ.put(o, "season", Integer.valueOf(season));
        CxJ.put(o, "episode", Integer.valueOf(episode));
        CxJ.put(o, "srcUri", srcUri);
        CxJ.put(o, "srcName", srcName);
        CxJ.put(o, "srcSize", Long.valueOf(srcSize));
        CxJ.put(o, "srcDurationMs", Long.valueOf(srcDurationMs));
        CxJ.put(o, "srcResolution", srcResolution);
        CxJ.put(o, "srcContainer", srcContainer);
        CxJ.put(o, "srcCodec", srcCodec);
        CxJ.put(o, "mode", Integer.valueOf(mode));
        CxJ.put(o, "videoMime", videoMime);
        CxJ.put(o, "targetBitrate", Long.valueOf(targetBitrate));
        CxJ.put(o, "uploadPath", uploadPath);
        CxJ.put(o, "tempReady", Boolean.valueOf(tempReady));
        CxJ.put(o, "uploadSize", Long.valueOf(uploadSize));
        CxJ.put(o, "uploadName", uploadName);
        CxJ.put(o, "objectKey", objectKey);
        CxJ.put(o, "uploadId", uploadId);
        CxJ.put(o, "partSize", Integer.valueOf(partSize));
        CxJ.put(o, "totalParts", Integer.valueOf(totalParts));
        CxJ.put(o, "parts", parts);
        CxJ.put(o, "onExists", onExists);
        CxJ.put(o, "needsDecision", Boolean.valueOf(needsDecision));
        CxJ.put(o, "existingUrl", existingUrl);
        CxJ.put(o, "existingSize", Long.valueOf(existingSize));
        CxJ.put(o, "publicUrl", publicUrl);
        CxJ.put(o, "finalSize", Long.valueOf(finalSize));
        CxJ.put(o, "warning", warning);
        CxJ.put(o, "state", state);
        CxJ.put(o, "message", message);
        CxJ.put(o, "autoResume", Boolean.valueOf(autoResume));
        CxJ.put(o, "waitingNetwork", Boolean.valueOf(waitingNetwork));
        CxJ.put(o, "createdAt", Long.valueOf(createdAt));
        CxJ.put(o, "updatedAt", Long.valueOf(updatedAt));
        CxJ.put(o, "startedAt", Long.valueOf(startedAt));
        return o;
    }

    static CgUploadSession fromJson(JSONObject o) {
        CgUploadSession s = new CgUploadSession();
        s.id = o.optString("id", "");
        s.kind = o.optString("kind", "movie");
        s.title = o.optString("title", "");
        s.poster = o.optString("poster", "");
        s.slug = o.optString("slug", "");
        s.season = o.optInt("season");
        s.episode = o.optInt("episode");
        s.srcUri = o.optString("srcUri", "");
        s.srcName = o.optString("srcName", "");
        s.srcSize = o.optLong("srcSize");
        s.srcDurationMs = o.optLong("srcDurationMs");
        s.srcResolution = o.optString("srcResolution", "");
        s.srcContainer = o.optString("srcContainer", "");
        s.srcCodec = o.optString("srcCodec", "");
        s.mode = o.optInt("mode");
        s.videoMime = o.optString("videoMime", "");
        s.targetBitrate = o.optLong("targetBitrate");
        s.uploadPath = o.optString("uploadPath", "");
        s.tempReady = o.optBoolean("tempReady");
        s.uploadSize = o.optLong("uploadSize");
        s.uploadName = o.optString("uploadName", "");
        s.objectKey = o.optString("objectKey", "");
        s.uploadId = o.optString("uploadId", "");
        s.partSize = o.optInt("partSize");
        s.totalParts = o.optInt("totalParts");
        JSONArray ps = o.optJSONArray("parts");
        if (ps != null) {
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                if (p != null) s.addPart(p.optInt("n"), p.optString("etag", ""), p.optLong("size"));
            }
        }
        s.onExists = o.optString("onExists", "");
        s.needsDecision = o.optBoolean("needsDecision");
        s.existingUrl = o.optString("existingUrl", "");
        s.existingSize = o.optLong("existingSize");
        s.publicUrl = o.optString("publicUrl", "");
        s.finalSize = o.optLong("finalSize");
        s.warning = o.optString("warning", "");
        s.state = o.optString("state", PAUSED);
        s.message = o.optString("message", "");
        s.autoResume = o.optBoolean("autoResume");
        s.waitingNetwork = o.optBoolean("waitingNetwork");
        s.createdAt = o.optLong("createdAt");
        s.updatedAt = o.optLong("updatedAt");
        s.startedAt = o.optLong("startedAt");
        return s;
    }

    /** Independent copy handed to the UI so the worker thread can keep changing the real one. */
    synchronized CgUploadSession copy() {
        CgUploadSession c = fromJson(toJson());
        c.stageFraction = stageFraction;
        c.bytesDone = bytesDone;
        c.bytesTotal = bytesTotal;
        c.outBytes = outBytes;
        c.currentPart = currentPart;
        c.speedBps = speedBps;
        c.stageStartedAt = stageStartedAt;
        c.queued = queued;
        return c;
    }

    static String stateLabel(String state) {
        if (COMPRESSING.equals(state)) return "جاري الضغط";
        if (PREPARING_UPLOAD.equals(state)) return "تجهيز الرفع";
        if (UPLOADING.equals(state)) return "جاري الرفع";
        if (VERIFYING.equals(state)) return "جاري التحقق";
        if (PAUSED.equals(state)) return "متوقف";
        if (FAILED.equals(state)) return "فشل";
        if (COMPLETED.equals(state)) return "مكتمل";
        if (CANCELLED.equals(state)) return "أُلغي";
        if (ANALYZING.equals(state)) return "جاري التحليل";
        if (READY.equals(state)) return "جاهز";
        return "—";
    }
}
