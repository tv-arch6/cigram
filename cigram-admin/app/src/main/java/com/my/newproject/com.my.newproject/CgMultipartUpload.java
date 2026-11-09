package com.my.newproject;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Uploads one big file to R2 through the Worker in equal parts (multipart).
 * - Streams every part straight from the file (64 KB at a time): the movie is never held in RAM.
 * - Parts already stored in the session are skipped, so the same engine does first upload and resume.
 * - Every part has its own retry (3 attempts with back-off) and its MD5 is compared with the ETag R2 returns.
 * - Works for a content:// Uri (ContentResolver) and for a temporary compressed File alike (see Source).
 * - Several parts travel at the same time (setParallel): one HTTP stream rarely fills a mobile or Wi-Fi uplink,
 *   so 3-4 concurrent parts are typically 2-4x faster. R2 accepts parts in any order; complete() sorts them.
 * - When the connection drops, the engine waits for it (NetGate) instead of giving up, then carries on.
 * It does not touch CgUpdateUpload, which keeps uploading APKs exactly as before.
 */
final class CgMultipartUpload {

    interface Source {
        /** A fresh stream positioned at `offset`. The engine closes it. */
        InputStream open(long offset) throws IOException;
    }

    interface Listener {
        void onProgress(long uploadedBytes, int currentPart, double speedBps);

        /** The part is stored on R2. The caller persists the session here. */
        void onPartDone(int partNumber, String etag, long size);
    }

    /** Lets the engine wait for the connection instead of failing, and tell the UI it is waiting. */
    interface NetGate {
        boolean online();

        void waiting(boolean waiting);
    }

    /** Another part failed for good: the remaining workers leave quietly. */
    private static final class Aborted extends RuntimeException {
        Aborted() {
            super("aborted");
        }
    }

    /** User asked to pause (pause == true) or cancel (pause == false). */
    static final class Stopped extends Exception {
        final boolean pause;

        Stopped(boolean pause) {
            super(pause ? "paused" : "cancelled");
            this.pause = pause;
        }
    }

    /** Not worth retrying: wrong token, bad request, unreadable file ... */
    static final class Fatal extends Exception {
        final String code;

        Fatal(String message, String code) {
            super(message);
            this.code = code == null ? "" : code;
        }
    }

    /** Several attempts failed because of the connection (or a 5xx). The session stays resumable. */
    static final class NetworkDown extends Exception {
        NetworkDown(String message) {
            super(message);
        }
    }

    /** The Worker no longer knows this upload id. */
    static final class SessionLost extends Exception {
        SessionLost() {
            super("session lost");
        }
    }

    private static final class PartFailure extends Exception {
        final boolean retryable;
        final boolean lost;
        final boolean network;

        PartFailure(String message, boolean retryable, boolean lost, boolean network) {
            super(message);
            this.retryable = retryable;
            this.lost = lost;
            this.network = network;
        }
    }

    private static final int MAX_ATTEMPTS = 5;
    /** Longest wait for the internet to come back inside one run (the manager's net watch takes over after). */
    private static final long MAX_OFFLINE_MS = 6L * 3600L * 1000L;

    private final Source source;
    private final long total;
    private final CgUploadSession session;
    private final Listener listener;

    private volatile int stopMode;
    private volatile boolean aborted;
    private final ArrayDeque<long[]> samples = new ArrayDeque<long[]>();
    private long lastReport;
    private double speed;

    private int parallel = 1;
    private NetGate gate;
    private final Object lock = new Object();
    private final AtomicLong doneBytes = new AtomicLong();
    private long[] inflight = new long[1];
    private int waitingWorkers;
    private Exception failure;

    CgMultipartUpload(Source source, long total, CgUploadSession session, Listener listener) {
        this.source = source;
        this.total = total;
        this.session = session;
        this.listener = listener;
    }

    /** Parts sent at the same time (1..6). */
    void setParallel(int n) {
        parallel = Math.max(1, Math.min(6, n));
    }

    void setNetGate(NetGate g) {
        gate = g;
    }

    void pause() {
        stopMode = 1;
    }

    void cancel() {
        stopMode = 2;
    }

    private void checkStop() throws Stopped {
        if (stopMode != 0) throw new Stopped(stopMode == 1);
        if (aborted) throw new Aborted();
    }

    /** Uploads every missing part, `parallel` at a time. Returns normally when all parts are on R2. */
    void uploadRemaining() throws Stopped, Fatal, NetworkDown, SessionLost {
        final int partSize = session.partSize;
        final int totalParts = session.totalParts;
        if (partSize <= 0 || totalParts <= 0 || session.uploadId.length() == 0) throw new Fatal("جلسة الرفع غير صالحة.", "BAD_SESSION");
        final ArrayList<Integer> missing = new ArrayList<Integer>();
        for (int n = 1; n <= totalParts; n++) {
            if (session.hasPart(n)) continue;
            long offset = (long) (n - 1) * (long) partSize;
            if (Math.min((long) partSize, total - offset) <= 0) throw new Fatal("حجم الملف تغيّر منذ بدء الرفع. ابدأ من جديد.", "SIZE_CHANGED");
            missing.add(Integer.valueOf(n));
        }
        doneBytes.set(session.partsBytes());
        sample(doneBytes.get());
        if (missing.isEmpty()) return;
        checkStop();

        final int workers = Math.min(parallel, missing.size());
        inflight = new long[workers];
        final AtomicInteger next = new AtomicInteger(0);
        Thread[] threads = new Thread[workers];
        for (int w = 0; w < workers; w++) {
            final int slot = w;
            threads[w] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (true) {
                            int i = next.getAndIncrement();
                            if (i >= missing.size()) return;
                            int n = missing.get(i).intValue();
                            long offset = (long) (n - 1) * (long) partSize;
                            int len = (int) Math.min((long) partSize, total - offset);
                            String etag = sendWithRetry(n, offset, len, slot);
                            session.addPart(n, etag, len);
                            doneBytes.addAndGet(len);
                            synchronized (lock) {
                                inflight[slot] = 0;
                                sample(currentBytes());
                                listener.onPartDone(n, etag, len);
                                listener.onProgress(currentBytes(), n, speed);
                            }
                        }
                    } catch (Aborted a) {
                        // another worker failed or the user stopped: leave quietly
                    } catch (Exception e) {
                        synchronized (lock) {
                            if (failure == null) failure = e;
                        }
                        aborted = true;
                    } catch (Throwable t) {
                        synchronized (lock) {
                            if (failure == null) failure = new Fatal("خطأ غير متوقع أثناء الرفع: " + t, "UNEXPECTED");
                        }
                        aborted = true;
                    }
                }
            }, "cigram-part-" + w);
            threads[w].start();
        }
        for (int w = 0; w < workers; w++) {
            while (threads[w].isAlive()) {
                try {
                    threads[w].join();
                } catch (InterruptedException e) {
                    stopMode = 2;
                }
            }
        }
        if (stopMode != 0) throw new Stopped(stopMode == 1);
        Exception f = failure;
        if (f instanceof Stopped) throw (Stopped) f;
        if (f instanceof Fatal) throw (Fatal) f;
        if (f instanceof NetworkDown) throw (NetworkDown) f;
        if (f instanceof SessionLost) throw (SessionLost) f;
        if (f != null) throw new Fatal(f.getMessage() == null ? "تعذر إكمال الرفع." : f.getMessage(), "UNEXPECTED");
        for (int n = 1; n <= totalParts; n++) {
            if (!session.hasPart(n)) throw new Fatal("بعض الأجزاء لم تُرفع. استكمل الرفع.", "PARTS");
        }
    }

    private long currentBytes() {
        long b = doneBytes.get();
        for (int i = 0; i < inflight.length; i++) b += inflight[i];
        return b;
    }

    // ------------------------------------------------------------------------ retry

    private String sendWithRetry(int n, long offset, int len, int slot) throws Stopped, Fatal, NetworkDown, SessionLost {
        PartFailure last = null;
        long offlineSince = 0;
        int attempt = 0;
        while (attempt < MAX_ATTEMPTS) {
            checkStop();
            NetGate g = gate;
            if (g != null && !g.online()) {
                // no connection at all: wait for it without spending attempts
                if (offlineSince == 0) {
                    offlineSince = System.currentTimeMillis();
                    setWaiting(true);
                }
                if (System.currentTimeMillis() - offlineSince > MAX_OFFLINE_MS) {
                    setWaiting(false);
                    throw new NetworkDown("انقطع الاتصال بالإنترنت لفترة طويلة.");
                }
                pauseBetween(2000L);
                continue;
            }
            if (offlineSince != 0) {
                offlineSince = 0;
                setWaiting(false);
            }
            attempt++;
            try {
                return sendPart(n, offset, len, slot);
            } catch (PartFailure e) {
                last = e;
                synchronized (lock) {
                    inflight[slot] = 0;
                }
                CgMediaApi.log("part " + n + " attempt " + attempt + " failed: " + e.getMessage());
                if (e.lost) throw new SessionLost();
                if (!e.retryable) throw new Fatal(e.getMessage(), "PART");
                if (e.network && g != null && !g.online()) {
                    attempt--; // the connection dropped: this one does not count, wait for it above
                    continue;
                }
                if (attempt < MAX_ATTEMPTS) pauseBetween(Math.min(15000L, 1500L * (1L << attempt)));
            }
        }
        String msg = "فشل رفع الجزء " + n + (last == null ? "." : ": " + last.getMessage());
        if (last != null && last.network) throw new NetworkDown(msg);
        throw new Fatal(msg, "PART");
    }

    private void setWaiting(boolean w) {
        NetGate g = gate;
        if (g == null) return;
        synchronized (lock) {
            int before = waitingWorkers;
            waitingWorkers = Math.max(0, waitingWorkers + (w ? 1 : -1));
            if (before == 0 && waitingWorkers > 0) g.waiting(true);
            else if (before > 0 && waitingWorkers == 0) g.waiting(false);
        }
    }

    private void pauseBetween(long ms) throws Stopped {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            checkStop();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new Stopped(false);
            }
        }
        checkStop();
    }

    // ------------------------------------------------------------------------- part

    private String sendPart(int n, long offset, int len, int slot) throws PartFailure, Stopped, Fatal {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            try {
                in = source.open(offset);
            } catch (IOException e) {
                throw new Fatal("تعذر قراءة الملف. ربما حُذف أو انتهت صلاحية الوصول إليه. اختره من جديد ثم استكمل.", "SOURCE");
            } catch (SecurityException e) {
                throw new Fatal("انتهت صلاحية الوصول إلى الملف. اختره من جديد ثم استكمل.", "SOURCE");
            }
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            c = (HttpURLConnection) new URL(CgMediaApi.partUrl(session.objectKey, session.uploadId, n)).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(180000);
            c.setUseCaches(false);
            c.setDoOutput(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + CgCfg.TOKEN);
            c.setRequestProperty("X-Admin-Token", CgCfg.TOKEN);
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setFixedLengthStreamingMode(len);
            OutputStream os = c.getOutputStream();
            byte[] buf = new byte[256 * 1024];
            int remaining = len;
            long sentInPart = 0;
            while (remaining > 0) {
                checkStop();
                int want = Math.min(buf.length, remaining);
                int r;
                try {
                    r = in.read(buf, 0, want);
                } catch (IOException e) {
                    throw new Fatal("تعذر قراءة الملف أثناء الرفع. ربما حُذف أو أُزيلت وحدة التخزين.", "SOURCE");
                }
                if (r < 0) throw new Fatal("انتهى الملف قبل أوانه. حجمه تغيّر منذ بدء الرفع.", "SIZE_CHANGED");
                os.write(buf, 0, r);
                md5.update(buf, 0, r);
                remaining -= r;
                sentInPart += r;
                report(slot, sentInPart, n);
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
                } catch (Exception e) {
                    CgMediaApi.log("part response is not JSON");
                }
            }
            boolean ok = code >= 200 && code < 300 && json != null && json.optBoolean("ok", true) && json.optBoolean("success", true);
            if (!ok) {
                String errCode = json == null ? "" : json.optString("error_code", "");
                String msg = json != null && json.optString("message", "").length() > 0 ? json.optString("message") : "فشل رفع الجزء " + n + " (HTTP " + code + ").";
                if (code == 401) throw new Fatal("رمز الإدارة غير صحيح (401).", "UNAUTHORIZED");
                if (code == 404 && "UPLOAD_NOT_FOUND".equals(errCode)) throw new PartFailure(msg, false, true, false);
                if (code == 404 && json != null && "not_found".equals(json.optString("error"))) {
                    throw new Fatal("هذه الميزة تحتاج النسخة الجديدة من الـ Worker. ارفع الملف الجديد إلى Cloudflare ثم أعد المحاولة.", "ROUTE_MISSING");
                }
                boolean retry = code >= 500 || code == 429 || code == 408 || code == 0 || json == null;
                throw new PartFailure(CgHttp.friendly(msg), retry, false, retry);
            }
            JSONObject data = json.optJSONObject("data");
            String etag = data == null ? "" : data.optString("etag", "");
            if (etag.length() == 0) throw new PartFailure("رد غير مكتمل من الخادم لهذا الجزء.", true, false, true);
            String clean = etag.replace("\"", "");
            String mine = hex(md5.digest());
            if (clean.length() == 32 && !clean.equalsIgnoreCase(mine)) {
                throw new PartFailure("لم تتطابق بصمة الجزء " + n + " بعد الرفع (تلف أثناء النقل).", true, false, false);
            }
            return clean;
        } catch (PartFailure e) {
            throw e;
        } catch (Stopped e) {
            throw e;
        } catch (Fatal e) {
            throw e;
        } catch (Aborted e) {
            throw e;
        } catch (Exception e) {
            throw new PartFailure(CgHttp.describe(e), true, false, true);
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) { }
            if (c != null) c.disconnect();
        }
    }

    // ----------------------------------------------------------------------- progress

    private void sample(long bytes) {
        long now = System.currentTimeMillis();
        samples.addLast(new long[]{now, bytes});
        while (samples.size() > 2 && now - samples.peekFirst()[0] > 6000) samples.removeFirst();
        if (samples.size() >= 2) {
            long[] a = samples.peekFirst();
            long[] b = samples.peekLast();
            long dt = b[0] - a[0];
            if (dt >= 1000) speed = (b[1] - a[1]) * 1000.0 / dt;
        }
    }

    private void report(int slot, long sentInPart, int part) {
        synchronized (lock) {
            inflight[slot] = sentInPart;
            long now = System.currentTimeMillis();
            if (now - lastReport < 300) return;
            lastReport = now;
            long bytes = currentBytes();
            sample(bytes);
            listener.onProgress(bytes, part, speed);
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
