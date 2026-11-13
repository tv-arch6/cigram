package com.Cigram.vid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.security.SecureRandom;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * (نسخة من AdsWs مع عنوان أساسي قابل للتحديد لخدمة المشاهدة المشتركة) عميل WebSocket (RFC 6455) خفيف فوق SSLSocket — بلا أي مكتبة خارجية.
 * - مصادقة عبر ترويسة Authorization في المصافحة (لا يوضع الرمز في الرابط حتى لا يظهر في السجلات).
 * - يتحقق من اسم الخادم (Hostname verification) يدوياً لأن SSLSocket لا يفعل ذلك وحده.
 * - إعادة اتصال تلقائية بتأخير متزايد، ونبض كل 25 ثانية ("ping" نصي يردّ عليه Cloudflare دون إيقاظ الكائن).
 * - كل الأحداث تصل إلى المستمع على الخيط الرئيسي.
 */
public final class CigramTogetherWs {

    public interface Listener {
        void onOpen();
        void onMessage(JSONObject message);
        void onClosed(boolean authFailed);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final SecureRandom RNG = new SecureRandom();
    private static final int MAX_FRAME = 256 * 1024;

    private final Context app;
    private final String base;
    private final String path;
    private final Listener listener;
    private volatile boolean stopped = true;
    private volatile boolean open = false;
    private volatile Thread worker;
    private volatile SSLSocket socket;
    private OutputStream out;
    private final Object writeLock = new Object();
    private volatile long generation = 0;
    // v5.23: الكتابة على السوكت خارج الخيط الرئيسي. أندرويد يمنع أي عملية شبكة على الخيط الرئيسي
    // (NetworkOnMainThreadException)، وكانت كل الأوامر (ready/chat/play...) تُرسل من الواجهة فتفشل بصمت،
    // فيبقى الخادم في حالة loading للأبد ويظهر «صديقك لم يجهز بعد» عند الطرفين.
    private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor(
            new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "tg-ws-write");
                    t.setDaemon(true);
                    return t;
                }
            });

    public CigramTogetherWs(Context c, String base, String path, Listener l) {
        this.base = base;
        this.app = c.getApplicationContext();
        this.path = path;
        this.listener = l;
    }

    public boolean isOpen() {
        return open;
    }

    public synchronized void start() {
        if (!stopped) return;
        stopped = false;
        final long gen = ++generation;
        Thread t = new Thread(new Runnable() {
            @Override public void run() { loop(gen); }
        }, "tg-ws");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    public synchronized void stop() {
        stopped = true;
        generation++;
        open = false;
        closeQuietly();
    }

    private void closeQuietly() {
        SSLSocket s = socket;
        socket = null;
        if (s != null) {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    /**
     * يضع الرسالة في طابور الكتابة (خيط خلفي واحد يحفظ الترتيب). آمن للاستدعاء من الخيط الرئيسي.
     * @return false إذا لم يكن الاتصال مفتوحاً؛ فشل الكتابة الفعلي يغلق السوكت فتعيد حلقة القراءة الاتصال،
     *         والأوامر التي تنتظر ack تنتهي بمهلتها في CigramTogetherSession.
     */
    public boolean sendText(final String text) {
        if (!open || text == null) return false;
        final long gen = generation;
        try {
            writer.execute(new Runnable() {
                @Override public void run() {
                    if (!open || gen != generation) return;
                    try {
                        writeFrame(0x1, text.getBytes("UTF-8"));
                    } catch (Throwable t) {
                        open = false;
                        closeQuietly();
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ الحلقة الرئيسية

    private boolean alive(long gen) {
        return !stopped && gen == generation;
    }

    private void loop(long gen) {
        int attempt = 0;
        while (alive(gen)) {
            boolean authFailed = false;
            boolean everOpen = false;
            try {
                String token = CigramUserData.token(app);
                if (token == null || token.length() == 0) { authFailed = true; throw new IOException("no token"); }
                connect(token);
                everOpen = true;
                attempt = 0;
                open = true;
                post(gen, 0, null, false);
                readLoop(gen);
            } catch (AuthException e) {
                authFailed = true;
            } catch (Throwable ignored) {
                // أي فشل شبكة: نعيد المحاولة
            } finally {
                boolean wasOpen = open;
                open = false;
                closeQuietly();
                if (wasOpen || everOpen || authFailed) post(gen, 2, null, authFailed);
            }
            if (!alive(gen)) break;
            attempt++;
            long delay = authFailed ? 60000L : Math.min(30000L, 1000L << Math.min(attempt, 5)) + RNG.nextInt(700);
            long until = System.currentTimeMillis() + delay;
            while (alive(gen) && System.currentTimeMillis() < until) {
                try { Thread.sleep(250); } catch (InterruptedException e) { return; }
            }
        }
    }

    private void post(final long gen, final int kind, final JSONObject m, final boolean flag) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (gen != generation) return;
                try {
                    if (kind == 0) listener.onOpen();
                    else if (kind == 1) listener.onMessage(m);
                    else listener.onClosed(flag);
                } catch (Throwable t) {
                    android.util.Log.w("CigramTogetherWs", "listener failed", t);
                }
            }
        });
    }

    private static final class AuthException extends IOException {
        AuthException(String m) { super(m); }
    }

    // ------------------------------------------------------------------ المصافحة

    private void connect(String token) throws IOException {
        URL u = new URL(base + path);
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 443;
        SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket s = (SSLSocket) f.createSocket(host, port);
        socket = s;
        s.setSoTimeout(15000);
        s.startHandshake();
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) {
            throw new IOException("hostname mismatch");
        }
        byte[] kb = new byte[16];
        RNG.nextBytes(kb);
        String key = android.util.Base64.encodeToString(kb, android.util.Base64.NO_WRAP);
        String file = u.getFile();
        String req = "GET " + file + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Authorization: Bearer " + token + "\r\n"
                + "User-Agent: CigramTogether/1\r\n\r\n";
        out = s.getOutputStream();
        out.write(req.getBytes("UTF-8"));
        out.flush();
        InputStream in = s.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4 && head.size() < 8192) {
            int b = in.read();
            if (b < 0) throw new IOException("eof in handshake");
            head.write(b);
            if ((state % 2 == 0 && b == '\r') || (state % 2 == 1 && b == '\n')) state++;
            else state = (b == '\r') ? 1 : 0;
        }
        String h = head.toString("UTF-8");
        String first = h.indexOf("\r\n") > 0 ? h.substring(0, h.indexOf("\r\n")) : h;
        if (first.contains(" 401") || first.contains(" 403")) throw new AuthException(first);
        if (!first.contains(" 101")) throw new IOException("handshake failed: " + first);
        s.setSoTimeout(70000);
    }

    // ------------------------------------------------------------------ القراءة

    private void readLoop(long gen) throws IOException {
        SSLSocket s = socket;
        if (s == null) return;
        final InputStream in = s.getInputStream();
        long lastPing = System.currentTimeMillis();
        ByteArrayOutputStream frag = new ByteArrayOutputStream();
        int fragOp = 0;
        // خيط نبض صغير
        final long myGen = gen;
        Thread pinger = new Thread(new Runnable() {
            @Override public void run() {
                while (alive(myGen) && open) {
                    try { Thread.sleep(25000); } catch (InterruptedException e) { return; }
                    if (alive(myGen) && open) sendText("ping");
                }
            }
        }, "tg-ws-ping");
        pinger.setDaemon(true);
        pinger.start();
        while (alive(gen)) {
            int b0 = in.read();
            if (b0 < 0) return;
            int b1 = in.read();
            if (b1 < 0) return;
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) len = ((long) readByte(in) << 8) | readByte(in);
            else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
            }
            if (len < 0 || len > MAX_FRAME) throw new IOException("frame too large");
            byte[] mask = null;
            if (masked) { mask = new byte[4]; readFully(in, mask, 4); }
            byte[] data = new byte[(int) len];
            readFully(in, data, data.length);
            if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
            if (op == 0x8) return;
            if (op == 0x9) { writeFrame(0xA, data); continue; }
            if (op == 0xA) continue;
            if (op == 0x1 || op == 0x2) { frag.reset(); fragOp = op; }
            if (op == 0x0 || op == 0x1 || op == 0x2) {
                if (frag.size() + data.length > MAX_FRAME) throw new IOException("message too large");
                frag.write(data, 0, data.length);
                if (fin && fragOp == 0x1) {
                    String text = frag.toString("UTF-8");
                    frag.reset();
                    if ("pong".equals(text)) continue;
                    try {
                        post(gen, 1, new JSONObject(text), false);
                    } catch (Throwable ignored) { /* إطار غير JSON */ }
                }
            }
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("eof");
        return b;
    }

    private static void readFully(InputStream in, byte[] buf, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new IOException("eof");
            off += r;
        }
    }

    // ------------------------------------------------------------------ الكتابة (إطارات العميل مقنَّعة)

    private void writeFrame(int op, byte[] payload) throws IOException {
        synchronized (writeLock) {
            OutputStream o = out;
            if (o == null) throw new IOException("closed");
            ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
            f.write(0x80 | op);
            if (payload.length < 126) f.write(0x80 | payload.length);
            else if (payload.length < 65536) { f.write(0x80 | 126); f.write(payload.length >> 8); f.write(payload.length & 0xFF); }
            else {
                f.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) f.write((int) (((long) payload.length >> (8 * i)) & 0xFF));
            }
            byte[] mask = new byte[4];
            RNG.nextBytes(mask);
            f.write(mask, 0, 4);
            for (int i = 0; i < payload.length; i++) f.write(payload[i] ^ mask[i & 3]);
            o.write(f.toByteArray());
            o.flush();
        }
    }

    @Override public String toString() {
        return String.format(Locale.US, "CigramTogetherWs[%s open=%b]", path, open);
    }
}
