package com.my.newproject;

import android.app.Activity;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Uploads one APK to R2 through the Worker in equal 6 MiB parts (R2 multipart rules).
 * - progress in bytes, SHA-256 computed while reading, nothing is kept in memory but one part;
 * - every part is retried 3 times; if it still fails the session stays alive and retry() resumes
 *   from the failed part (no byte is uploaded twice);
 * - the file is checked to be a zip (PK) before the server is even contacted.
 * The Worker finally opens the uploaded file and reads package / versionCode / versionName from it.
 */
final class CgUpdateUpload {

    static final int IDLE = 0;
    static final int UPLOADING = 1;
    static final int DONE = 2;
    static final int FAILED = 3;
    static final int CANCELLED = 4;

    interface Listener {
        void onProgress(long sent, long total);

        void onState(int state, String message, boolean fatal);

        void onDone(JSONObject result);
    }

    private static final class Cancelled extends Exception {
        Cancelled() {
            super("cancelled");
        }
    }

    private static final class Fatal extends Exception {
        Fatal(String m) {
            super(m);
        }
    }

    private static final class PartFailure extends Exception {
        final boolean retryable;

        PartFailure(String m, boolean retryable) {
            super(m);
            this.retryable = retryable;
        }
    }

    private final Activity a;
    private final Uri uri;
    final String fileName;
    final long total;
    private final String versionName;
    private final int partSize;
    private final Listener listener;

    private volatile int state = IDLE;
    private volatile boolean cancel;
    private volatile boolean running;

    private String key;
    private String uploadId;
    private int nextPart = 1;
    private long sent;
    private final JSONArray parts = new JSONArray();
    private byte[] buffer;
    private int bufferLen;
    private boolean bufferReady;
    private boolean eof;
    private InputStream in;
    private MessageDigest digest;
    private String sha256 = "";
    private long lastReport;

    CgUpdateUpload(Activity a, Uri uri, String fileName, long total, String versionName, long partSize, Listener listener) {
        this.a = a;
        this.uri = uri;
        this.fileName = fileName;
        this.total = total;
        this.versionName = versionName == null ? "" : versionName;
        this.partSize = (int) Math.max(5L * 1024L * 1024L, partSize);
        this.listener = listener;
    }

    int state() {
        return state;
    }

    boolean running() {
        return running;
    }

    String key() {
        return key;
    }

    /** Starts (or resumes after a failure). Safe to call twice: the second call is ignored while running. */
    synchronized void start() {
        if (running || state == DONE) return;
        cancel = false;
        running = true;
        CgHttp.bg(new Runnable() {
            @Override
            public void run() {
                work();
            }
        });
    }

    void cancel() {
        cancel = true;
        final String k = key;
        final String u = uploadId;
        if (state != DONE && k != null && u != null) {
            CgHttp.bg(new Runnable() {
                @Override
                public void run() {
                    CgUpdateApi.uploadAbort(k, u);
                }
            });
        }
        closeStream();
        buffer = null;
    }

    // ------------------------------------------------------------------ worker

    private void work() {
        try {
            post(UPLOADING, "جارٍ الرفع...", false);
            if (digest == null) digest = MessageDigest.getInstance("SHA-256");
            if (in == null) {
                in = a.getContentResolver().openInputStream(uri);
                if (in == null) throw new Fatal("تعذر فتح الملف المختار. اختره مرة أخرى.");
            }
            if (buffer == null) buffer = new byte[partSize];

            if (key == null) {
                fillBuffer();
                if (bufferLen < 4 || buffer[0] != 'P' || buffer[1] != 'K' || buffer[2] != 3 || buffer[3] != 4) {
                    throw new Fatal("الملف المختار ليس APK صالحاً (ليس أرشيف ZIP).");
                }
                JSONObject init = initWithRetry();
                key = init.optString("key", "");
                uploadId = init.optString("uploadId", "");
                if (key.length() == 0 || uploadId.length() == 0) {
                    throw new Fatal("رد غير متوقع من الخادم عند بدء الرفع. أعد المحاولة.");
                }
                if (init.optLong("partSize", partSize) != partSize) {
                    key = null;
                    uploadId = null;
                    throw new Fatal("إعدادات الرفع في الـ Worker تغيّرت. أغلق الشاشة وافتحها من جديد.");
                }
            }

            while (true) {
                if (cancel) throw new Cancelled();
                fillBuffer();
                if (bufferLen == 0) break;
                sendWithRetry();
                sent += bufferLen;
                JSONObject p = new JSONObject();
                CxJ.put(p, "partNumber", Integer.valueOf(nextPart));
                CxJ.put(p, "etag", lastEtag);
                parts.put(p);
                nextPart++;
                bufferReady = false;
                bufferLen = 0;
                report(sent, true);
            }
            if (parts.length() == 0) throw new Fatal("الملف المختار فارغ.");
            if (sha256.length() == 0) sha256 = hex(digest.digest());

            JSONObject body = new JSONObject();
            CxJ.put(body, "key", key);
            CxJ.put(body, "uploadId", uploadId);
            CxJ.put(body, "parts", parts);
            CxJ.put(body, "size", Long.valueOf(sent));
            CxJ.put(body, "sha256", sha256);
            JSONObject result;
            try {
                result = CgUpdateApi.uploadComplete(body);
            } catch (CxApi.Failure f) {
                boolean fatal = "NOT_AN_APK".equals(f.code) || "UPLOAD_INCOMPLETE".equals(f.code);
                if (fatal) {
                    key = null;
                    uploadId = null;
                    throw new Fatal(f.getMessage());
                }
                throw new PartFailure(f.getMessage(), true);
            }
            closeStream();
            buffer = null;
            running = false;
            final JSONObject done = result;
            postDone(done);
        } catch (Cancelled c) {
            running = false;
            state = CANCELLED;
        } catch (Fatal f) {
            running = false;
            closeStream();
            CgUpdateApi.log("upload fatal");
            post(FAILED, f.getMessage(), true);
        } catch (PartFailure e) {
            running = false;
            CgUpdateApi.log("upload failed (resumable)");
            post(FAILED, e.getMessage(), false);
        } catch (Exception e) {
            running = false;
            CgUpdateApi.log("upload error " + e.getClass().getSimpleName());
            post(FAILED, CgHttp.describe(e), false);
        }
    }

    private JSONObject initWithRetry() throws Exception {
        CxApi.Failure last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (cancel) throw new Cancelled();
            try {
                return CgUpdateApi.uploadInit(fileName, total > 0 ? total : sent + bufferLen, versionName);
            } catch (CxApi.Failure f) {
                last = f;
                boolean network = "NETWORK".equals(f.code) || f.http >= 500 || f.http == 0;
                if (!network) throw new Fatal(f.getMessage());
                sleep(attempt * 1500L);
            }
        }
        throw new PartFailure(last == null ? "تعذر بدء الرفع." : last.getMessage(), true);
    }

    private void fillBuffer() throws Exception {
        if (bufferReady) return;
        if (eof) {
            // the last part was already sent: nothing left. Without this the loop kept re-sending the
            // previous part forever (progress went past 100% and the upload never completed).
            bufferLen = 0;
            return;
        }
        int off = 0;
        while (off < partSize) {
            if (cancel) throw new Cancelled();
            int n = in.read(buffer, off, partSize - off);
            if (n < 0) {
                eof = true;
                break;
            }
            off += n;
        }
        bufferLen = off;
        bufferReady = off > 0;
        if (off > 0) digest.update(buffer, 0, off);
    }

    private String lastEtag = "";

    private void sendWithRetry() throws Exception {
        PartFailure last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (cancel) throw new Cancelled();
            try {
                sendPart();
                return;
            } catch (PartFailure e) {
                last = e;
                report(sent, true);
                if (!e.retryable) throw new Fatal(e.getMessage());
                CgUpdateApi.log("part " + nextPart + " attempt " + attempt + " failed");
                if (attempt < 3) sleep(attempt * 1500L);
            }
        }
        throw last == null ? new PartFailure("فشل رفع الملف.", true) : last;
    }

    private void sendPart() throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(CgUpdateApi.partUrl(key, uploadId, nextPart)).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(120000);
            c.setUseCaches(false);
            c.setDoOutput(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + CgCfg.TOKEN);
            c.setRequestProperty("X-Admin-Token", CgCfg.TOKEN);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setFixedLengthStreamingMode(bufferLen);
            OutputStream os = c.getOutputStream();
            int off = 0;
            while (off < bufferLen) {
                if (cancel) throw new Cancelled();
                int n = Math.min(32768, bufferLen - off);
                os.write(buffer, off, n);
                off += n;
                report(sent + off, false);
            }
            os.flush();
            os.close();
            int code = c.getResponseCode();
            InputStream rin = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = CgHttp.readAll(rin, 1024 * 1024).trim();
            JSONObject json = null;
            if (text.startsWith("{")) {
                try {
                    json = new JSONObject(text);
                } catch (Exception ignored) { }
            }
            boolean ok = code >= 200 && code < 300 && json != null && json.optBoolean("ok", true);
            if (!ok) {
                String msg = json != null && json.optString("message", "").length() > 0
                        ? json.optString("message") : "فشل رفع الجزء " + nextPart + " (HTTP " + code + ").";
                boolean retryable = code >= 500 || code == 429 || code == 408 || code == 0 || json == null;
                if (code == 401) {
                    msg = "رمز الإدارة غير صحيح (401).";
                    retryable = false;
                }
                throw new PartFailure(CgHttp.friendly(msg), retryable);
            }
            JSONObject data = json.optJSONObject("data");
            String etag = data == null ? "" : data.optString("etag", "");
            if (etag.length() == 0) throw new PartFailure("رد غير مكتمل من الخادم لهذا الجزء. أعد المحاولة.", true);
            lastEtag = etag;
        } catch (Cancelled e) {
            throw e;
        } catch (PartFailure e) {
            throw e;
        } catch (Exception e) {
            throw new PartFailure(CgHttp.describe(e), true);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ----------------------------------------------------------------- helpers

    private void report(long bytes, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastReport < 120) return;
        lastReport = now;
        final long b = bytes;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!a.isFinishing() && !cancel) listener.onProgress(b, total);
            }
        });
    }

    private void post(final int st, final String msg, final boolean fatal) {
        state = st;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!a.isFinishing() && !cancel) listener.onState(st, msg, fatal);
            }
        });
    }

    private void postDone(final JSONObject result) {
        state = DONE;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!a.isFinishing() && !cancel) {
                    listener.onProgress(total, total);
                    listener.onState(DONE, "تم رفع الملف بنجاح", false);
                    listener.onDone(result);
                }
            }
        });
    }

    private void closeStream() {
        try {
            if (in != null) in.close();
        } catch (Exception ignored) { }
        in = null;
    }

    private static void sleep(long ms) throws Cancelled {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new Cancelled();
        }
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0x0F];
        }
        return new String(out);
    }
}
