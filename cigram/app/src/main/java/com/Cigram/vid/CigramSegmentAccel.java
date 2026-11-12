package com.Cigram.vid;

import android.net.Uri;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v10.79: faster pieces for streams that came from an embed site (vidbolt and the like).
 *
 * Their segment servers are slow PER CONNECTION, so one request per piece (what the player does) never uses
 * the speed of the user's line. Here every piece is fetched with several parallel range requests (up to
 * {@link #PARTS}), the player reads it while it arrives (it does not wait for the whole piece), and the next
 * pieces are fetched ahead, one after the other. The faster the line, the faster everything goes: a fast
 * connection fills the pieces in a fraction of their play time and the look-ahead grows.
 *
 * A seek moves the "focus": pieces fetched for the old place are dropped at once, so the whole line works for
 * the new place. The seek-bar preview ({@link CigramSeekPreview}) uses the same store, so the piece under the
 * finger is already downloading when the finger is lifted.
 *
 * Anything that is not a numbered piece of a seen embed host (playlists, keys, normal links, progressive
 * files, byte-range requests) goes to the normal data source untouched; so does any piece this store could
 * not get (404, server without range support for a big file, ...), so the retry policy keeps working as before.
 */
final class CigramSegmentAccel {

    private static final String TAG = "CigramAccel";

    /** Parallel connections for one piece. */
    static final int PARTS = 4;
    /** Pieces smaller than this are fetched with one connection. */
    private static final int MIN_SPLIT = 384 * 1024;
    /** A piece above this is not a segment (a whole film): left to the normal source. */
    private static final long MAX_ENTRY = 40L * 1024L * 1024L;
    /** Memory for pieces kept in the store. */
    private static final long BUDGET = 64L * 1024L * 1024L;
    private static final long KEEP_MS = 4L * 60L * 1000L;

    // 18.3: how aggressive the accelerator may be follows the connection (see CigramAccelPolicy); fed by the player's load control
    private static volatile CigramAccelPolicy policy = CigramAccelPolicy.decide(0, false, false, PARTS, 4, BUDGET);

    /** Called (about once a second) by the adaptive load control: level 0 good .. 2 very weak, device class, metered. */
    static void applyLevel(int level, boolean lowRam, boolean metered) {
        policy = CigramAccelPolicy.decide(level, lowRam, metered, PARTS, 4, BUDGET);
    }

    private static long budget() {
        return policy.budgetBytes;
    }

    private static final Pattern NUMBERED = Pattern.compile("^(.*?)(\\d+)(\\.[A-Za-z0-9]{1,5})?$");

    private static final ExecutorService POOL = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "cigram-accel");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    });

    /** key (address without our cache-key parameter) -> piece. Access order = least recently used first. */
    private static final LinkedHashMap<String, Entry> ENTRIES = new LinkedHashMap<String, Entry>(32, 0.75f, true);
    /** Pieces this store could not get recently: they go straight to the normal source for a moment. */
    private static final HashMap<String, Long> FAILED = new HashMap<String, Long>();
    /** Addresses that are whole files, not pieces. */
    private static final HashSet<String> BIG = new HashSet<String>();
    /** Folder of a numbered series of pieces -> the piece the player (or the preview) is at. */
    private static final HashMap<String, Integer> FOCUS = new HashMap<String, Integer>();

    /** Last time any byte of a piece arrived (elapsedRealtime): a slow server that still sends is not "stuck". */
    static volatile long lastBytesAt = 0L;

    /** Measured speed of the line for these servers (bytes per second, smoothed). */
    private static volatile double speedBps = 0d;

    private CigramSegmentAccel() { }

    // ================================================================== addresses

    static final class Parts {
        String dir;      // folder + name pattern, e.g. https://host/media/x/tiles/|.png
        String prefix;   // "" in 00032.png
        String ext;      // ".png"
        int index;       // 32
        int width;       // 5
        String base;     // https://host/media/x/tiles/
        String query;    // "" or "?a=b"
    }

    static String keyOf(String url) {
        if (url == null) return "";
        int q = url.indexOf('?');
        if (q < 0) return url;
        Uri u = Uri.parse(url);
        Uri.Builder b = u.buildUpon().clearQuery();
        boolean any = false;
        for (String name : u.getQueryParameterNames()) {
            if ("cgb".equals(name)) continue;
            for (String v : u.getQueryParameters(name)) {
                b.appendQueryParameter(name, v);
                any = true;
            }
        }
        return any ? b.build().toString() : url.substring(0, q);
    }

    static Parts partsOf(String url) {
        try {
            String clean = keyOf(url);
            int q = clean.indexOf('?');
            String path = q < 0 ? clean : clean.substring(0, q);
            String query = q < 0 ? "" : clean.substring(q);
            int slash = path.lastIndexOf('/');
            if (slash < 0) return null;
            Matcher m = NUMBERED.matcher(path.substring(slash + 1));
            if (!m.matches()) return null;
            Parts p = new Parts();
            p.base = path.substring(0, slash + 1);
            p.prefix = m.group(1);
            p.width = m.group(2).length();
            p.index = Integer.parseInt(m.group(2));
            p.ext = m.group(3) == null ? "" : m.group(3);
            p.query = query;
            p.dir = p.base + "|" + p.prefix + "|" + p.ext;
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    static String urlOf(Parts p, int index) {
        String n = Integer.toString(index);
        StringBuilder sb = new StringBuilder(p.base).append(p.prefix);
        for (int i = n.length(); i < p.width; i++) sb.append('0');
        return sb.append(n).append(p.ext).append(p.query).toString();
    }

    private static boolean isPlaylistLike(String path) {
        String p = path.toLowerCase(Locale.US);
        return p.endsWith(".m3u8") || p.endsWith(".m3u") || p.endsWith(".mpd") || p.endsWith(".vtt")
                || p.endsWith(".webvtt") || p.endsWith(".srt") || p.endsWith(".json") || p.endsWith(".key")
                || p.endsWith(".ass") || p.endsWith(".xml") || p.endsWith(".html") || p.endsWith(".php");
    }

    /** Whether a request may be served by this store. */
    static boolean eligible(DataSpec spec) {
        try {
            if (spec == null || spec.uri == null || spec.length != C.LENGTH_UNSET) return false;
            String scheme = spec.uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
            if (!CigramEmbedResolver.isSeenHost(spec.uri.getHost())) return false;
            String path = spec.uri.getPath() == null ? "" : spec.uri.getPath();
            if (isPlaylistLike(path)) return false;
            String url = spec.uri.toString();
            if (partsOf(url) == null) return false;   // only numbered pieces of a series
            String key = keyOf(url);
            synchronized (CigramSegmentAccel.class) {
                if (BIG.contains(key)) return false;
                Long f = FAILED.get(key);
                if (f != null) {
                    if (System.currentTimeMillis() - f.longValue() < 4000L) return false;
                    FAILED.remove(key);
                }
                if (spec.position > 0 && !ENTRIES.containsKey(key)) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================== one piece

    static final class Entry {
        final String key;
        final String url;
        final Parts parts;
        final long createdAt = System.currentTimeMillis();

        /** 0 = waiting for the server; 2xx = ok; other = refused / failed. */
        int status;
        boolean failed, complete, cancelled, tooBig;
        long total = -1L;           // known size, or -1 (then data grows)
        byte[] data = new byte[0];
        int partCount = 1;
        int partSize;
        int[] filled = new int[1];  // bytes received from the start of each part
        int readers;
        long lastUse = System.currentTimeMillis();
        long started, finished;
        final List<HttpURLConnection> connections = Collections.synchronizedList(new ArrayList<HttpURLConnection>());

        Entry(String key, String url, Parts parts) {
            this.key = key;
            this.url = url;
            this.parts = parts;
        }

        synchronized boolean ok() { return status >= 200 && status < 300 && !failed && !cancelled; }

        synchronized boolean downloading() { return !complete && !failed && !cancelled; }

        /** Bytes that can be read from {@code pos} right now; -1 = end; 0 = not yet. */
        synchronized long available(long pos) {
            if (total >= 0 && pos >= total) return -1L;
            if (partCount <= 1) {
                long n = filled[0] - pos;
                if (n > 0) return n;
                return complete ? -1L : 0L;
            }
            int i = (int) Math.min(partCount - 1, pos / partSize);
            long inPart = pos - (long) i * partSize;
            long n = filled[i] - inPart;
            return n > 0 ? n : 0L;
        }

        /** The first contiguous bytes received (for the preview picture). */
        synchronized byte[] head(int max) {
            int n = Math.min(max, filled[0]);
            if (partCount > 1 && filled[0] >= partSize) {
                // part 0 is complete; the following parts continue the same bytes
                long contiguous = partSize;
                for (int i = 1; i < partCount && contiguous < max; i++) {
                    contiguous += filled[i];
                    if (filled[i] < Math.min(partSize, total - (long) i * partSize)) break;
                }
                n = (int) Math.min(max, contiguous);
            }
            byte[] out = new byte[Math.max(0, n)];
            System.arraycopy(data, 0, out, 0, out.length);
            return out;
        }

        synchronized long received() {
            long s = 0;
            for (int f : filled) s += f;
            return s;
        }

        void cancel() {
            synchronized (this) {
                cancelled = true;
                notifyAll();
            }
            synchronized (connections) {
                for (HttpURLConnection c : connections) {
                    try { c.disconnect(); } catch (Throwable ignored) { }
                }
            }
        }

        synchronized void fail(int code) {
            if (status == 0 || (status >= 200 && status < 300)) status = code;
            failed = true;
            notifyAll();
        }
    }

    // ================================================================== store

    /** The piece for this address: kept, being fetched, or started now. */
    static Entry obtain(String url) {
        String key = keyOf(url);
        Entry e;
        boolean start = false;
        synchronized (CigramSegmentAccel.class) {
            e = ENTRIES.get(key);
            if (e != null && (e.failed || e.cancelled)) {
                ENTRIES.remove(key);
                e = null;
            }
            if (e == null) {
                e = new Entry(key, key, partsOf(url));
                ENTRIES.put(key, e);
                start = true;
            }
            e.lastUse = System.currentTimeMillis();
        }
        if (start) startDownload(e);
        return e;
    }

    /** The preview / a seek says where the user is going: the old place's downloads stop. */
    static void focus(String url) {
        Parts p = partsOf(url);
        if (p == null) return;
        ArrayList<Entry> drop = new ArrayList<Entry>();
        synchronized (CigramSegmentAccel.class) {
            FOCUS.put(p.dir, Integer.valueOf(p.index));
            int ahead = lookAhead();
            for (Iterator<Entry> it = ENTRIES.values().iterator(); it.hasNext(); ) {
                Entry e = it.next();
                if (e.parts == null || !p.dir.equals(e.parts.dir)) continue;
                int i = e.parts.index;
                boolean inWindow = i >= p.index - 1 && i <= p.index + ahead + 1;
                if (!inWindow && e.readers == 0 && e.downloading()) {
                    it.remove();
                    drop.add(e);
                }
            }
        }
        for (Entry e : drop) e.cancel();
    }

    /** True when this piece is already in the store (fully, or with enough bytes to start). */
    static synchronized boolean has(String url) {
        Entry e = ENTRIES.get(keyOf(url));
        return e != null && e.ok() && (e.complete || e.received() > 64 * 1024);
    }

    /** Starts fetching the piece at this address (preview of the seek bar). Returns it. */
    static Entry prefetch(String url) {
        focus(url);
        return obtain(url);
    }

    /** How many pieces to fetch ahead: more on a fast line. */
    private static int lookAhead() {
        double s = speedBps;
        int n = s > 4_000_000d ? 4 : s > 1_500_000d ? 3 : 2;
        return Math.min(n, policy.maxAhead);
    }

    /** Starts the next missing piece after the focus (one at a time per series, so the current one is never starved). */
    private static void ensureAhead(String dir) {
        Entry next = null;
        synchronized (CigramSegmentAccel.class) {
            Integer f = FOCUS.get(dir);
            if (f == null) return;
            Parts p = null;
            for (Entry e : ENTRIES.values()) {
                if (e.parts == null || !dir.equals(e.parts.dir)) continue;
                if (e.downloading()) return;          // one at a time per series
                if (p == null) p = e.parts;
            }
            if (p == null) return;
            if (bytesInStore() > budget() * 3 / 4) trim();
            if (bytesInStore() > budget() * 3 / 4) return;
            int ahead = lookAhead();
            for (int i = f.intValue(); i <= f.intValue() + ahead; i++) {
                Parts q = new Parts();
                q.dir = p.dir; q.prefix = p.prefix; q.ext = p.ext; q.width = p.width; q.base = p.base; q.query = p.query;
                q.index = i;
                String url = urlOf(q, i);
                Entry e = ENTRIES.get(url);
                if (e != null) {
                    if (e.failed && e.status >= 400) return;   // the server does not have it (yet): stop here
                    continue;
                }
                Long failedAt = FAILED.get(url);
                if (failedAt != null && System.currentTimeMillis() - failedAt.longValue() < 4000L) return;
                next = new Entry(url, url, q);
                ENTRIES.put(url, next);
                break;
            }
        }
        if (next != null) startDownload(next);
    }

    private static long bytesInStore() {
        long s = 0;
        for (Entry e : ENTRIES.values()) s += e.data.length;
        return s;
    }

    /** Drops pieces nobody reads, oldest first, until the store fits in its budget. */
    private static void trim() {
        long now = System.currentTimeMillis();
        long size = bytesInStore();
        for (Iterator<Entry> it = ENTRIES.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            boolean idle = e.readers == 0 && !e.downloading();
            if (idle && (size > budget() / 2 || now - e.lastUse > KEEP_MS)) {
                size -= e.data.length;
                it.remove();
            }
        }
        if (FAILED.size() > 400) FAILED.clear();
    }

    private static void opened(Entry e) {
        if (e.parts == null) return;
        synchronized (CigramSegmentAccel.class) {
            FOCUS.put(e.parts.dir, Integer.valueOf(e.parts.index));
        }
        focus(e.url);
        ensureAhead(e.parts.dir);
    }

    private static void finished(Entry e) {
        try {
            synchronized (CigramSegmentAccel.class) {
                if (e.failed || e.cancelled) {
                    if (ENTRIES.get(e.key) == e) ENTRIES.remove(e.key);
                    if (e.tooBig) BIG.add(e.key);
                    else if (!e.cancelled) FAILED.put(e.key, Long.valueOf(System.currentTimeMillis()));
                }
                trim();
            }
            if (e.complete && e.finished > e.started && e.total > 256 * 1024) {
                double bps = e.total * 1000d / (e.finished - e.started);
                speedBps = speedBps <= 0d ? bps : speedBps * 0.6d + bps * 0.4d;
            }
            if (e.parts != null && !e.cancelled) ensureAhead(e.parts.dir);
        } catch (Throwable ignored) { }
    }

    // ================================================================== network

    private static HttpURLConnection connect(Entry e, String range) throws IOException {
        String target = e.url;
        if (CigramHttpProfile.isRefused(target)) target = CigramEmbedResolver.bust(target);   // the CDN keeps a 404: new cache key
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        String host = Uri.parse(e.url).getHost();
        HashMap<String, String> h = new HashMap<String, String>(CigramStreams.CURRENT_HEADERS);
        CigramHttpProfile.applyVariant(h, CigramHttpProfile.variantOf(host), host);
        for (Map.Entry<String, String> kv : h.entrySet()) {
            String k = kv.getKey();
            if (k == null || kv.getValue() == null || "range".equalsIgnoreCase(k)) continue;
            if (kv.getValue().length() == 0 && ("referer".equalsIgnoreCase(k) || "origin".equalsIgnoreCase(k))) continue;
            c.setRequestProperty(k, kv.getValue());
        }
        c.setRequestProperty("Accept-Encoding", "identity");
        if (range != null) c.setRequestProperty("Range", range);
        e.connections.add(c);
        return c;
    }

    private static long totalFromContentRange(String cr) {
        if (cr == null) return -1L;
        int slash = cr.lastIndexOf('/');
        if (slash < 0) return -1L;
        try { return Long.parseLong(cr.substring(slash + 1).trim()); } catch (Throwable t) { return -1L; }
    }

    private static void startDownload(final Entry e) {
        POOL.execute(new Runnable() { @Override public void run() { firstPart(e); } });
    }

    private static void firstPart(final Entry e) {
        HttpURLConnection c = null;
        try {
            synchronized (e) { e.started = System.currentTimeMillis(); }
            c = connect(e, "bytes=0-");
            int code = c.getResponseCode();
            if (code == 404 || code == 403 || code == 410) CigramHttpProfile.markRefused(e.url);
            if (code != 200 && code != 206) {
                e.fail(code);
                return;
            }
            long total = -1L;
            if (code == 206) total = totalFromContentRange(c.getHeaderField("Content-Range"));
            if (total < 0) {
                String cl = c.getHeaderField("Content-Length");
                try { if (cl != null) total = Long.parseLong(cl.trim()); } catch (Throwable ignored) { }
            }
            if (total > MAX_ENTRY) {
                synchronized (e) { e.tooBig = true; }
                e.fail(-2);
                return;
            }
            int parts = 1;
            if (code == 206 && total >= MIN_SPLIT) {
                int cap = policy.maxParts;       // 18.3: one connection per piece on a very weak link
                parts = cap <= 1 ? 1 : (int) Math.max(2, Math.min(cap, total / (256 * 1024)));
            }
            synchronized (e) {
                e.total = total;
                e.data = new byte[total >= 0 ? (int) total : 512 * 1024];
                e.partCount = parts;
                e.partSize = total >= 0 ? (int) ((total + parts - 1) / parts) : Integer.MAX_VALUE;
                e.filled = new int[parts];
                e.status = code;
                e.notifyAll();
            }
            for (int i = 1; i < parts; i++) {
                final int part = i;
                POOL.execute(new Runnable() { @Override public void run() { otherPart(e, part, 0); } });
            }
            readInto(e, 0, c);
            partDone(e);
        } catch (Throwable t) {
            if (!e.cancelled) {
                // part 0 broke after the server answered: try the rest of it once more with its own request
                boolean answered;
                synchronized (e) { answered = e.status >= 200 && e.status < 300 && e.total >= 0; }
                if (answered) {
                    otherPart(e, 0, 1);
                } else {
                    e.fail(-1);
                }
            }
        } finally {
            if (c != null) {
                e.connections.remove(c);
                try { c.disconnect(); } catch (Throwable ignored) { }
            }
            if (e.failed || e.cancelled) finished(e);
        }
    }

    private static void otherPart(Entry e, int part, int attempt) {
        HttpURLConnection c = null;
        try {
            long from, to;
            synchronized (e) {
                if (e.cancelled || e.failed) return;
                from = (long) part * e.partSize + e.filled[part];
                to = Math.min(e.total, (long) (part + 1) * e.partSize) - 1L;
            }
            if (from > to) { partDone(e); return; }
            c = connect(e, "bytes=" + from + "-" + to);
            int code = c.getResponseCode();
            if (code != 206) throw new IOException("range refused: HTTP " + code);
            readInto(e, part, c);
            partDone(e);
        } catch (Throwable t) {
            if (e.cancelled) return;
            if (attempt < 3) {
                try { Thread.sleep(300L * (attempt + 1)); } catch (InterruptedException ignored) { }
                otherPart(e, part, attempt + 1);
            } else {
                e.fail(-1);
                finished(e);
            }
        } finally {
            if (c != null) {
                e.connections.remove(c);
                try { c.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Copies the answer into the piece's buffer at the part's place; readers are woken for every block. */
    private static void readInto(Entry e, int part, HttpURLConnection c) throws IOException {
        InputStream in = c.getInputStream();
        byte[] buf = new byte[64 * 1024];
        try {
            while (true) {
                int want;
                synchronized (e) {
                    if (e.cancelled || e.failed) throw new InterruptedIOException("cancelled");
                    if (e.total >= 0) {
                        long end = Math.min(e.total, (long) (part + 1) * e.partSize);
                        long at = (long) part * e.partSize + e.filled[part];
                        want = (int) Math.min(buf.length, end - at);
                        if (want <= 0) return;
                    } else {
                        want = buf.length;
                    }
                }
                int n = in.read(buf, 0, want);
                if (n < 0) {
                    synchronized (e) {
                        if (e.total < 0) { e.total = e.filled[0]; e.notifyAll(); return; }
                    }
                    throw new IOException("closed early");
                }
                synchronized (e) {
                    if (e.cancelled) throw new InterruptedIOException("cancelled");
                    long at = (long) part * (e.total >= 0 ? e.partSize : 0) + e.filled[part];
                    if (e.total < 0 && at + n > e.data.length) {
                        if (at + n > MAX_ENTRY) { e.tooBig = true; throw new IOException("too big"); }
                        byte[] grown = new byte[(int) Math.min(MAX_ENTRY, Math.max(e.data.length * 2L, at + n))];
                        System.arraycopy(e.data, 0, grown, 0, (int) at);
                        e.data = grown;
                    }
                    System.arraycopy(buf, 0, e.data, (int) at, n);
                    e.filled[part] += n;
                    lastBytesAt = android.os.SystemClock.elapsedRealtime();
                    e.notifyAll();
                }
            }
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static void partDone(Entry e) {
        boolean all;
        synchronized (e) {
            if (e.total < 0) {
                all = true;
            } else {
                all = true;
                for (int i = 0; i < e.partCount; i++) {
                    long size = Math.min(e.partSize, e.total - (long) i * e.partSize);
                    if (e.filled[i] < size) { all = false; break; }
                }
            }
            if (all && !e.complete) {
                e.complete = true;
                e.finished = System.currentTimeMillis();
                if (e.total >= 0 && e.data.length != e.total && e.partCount == 1) {
                    byte[] exact = new byte[(int) e.total];
                    System.arraycopy(e.data, 0, exact, 0, exact.length);
                    e.data = exact;
                }
                e.notifyAll();
            } else {
                all = false;
            }
        }
        if (all) finished(e);
    }

    // ================================================================== player side

    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstream;
        Factory(DataSource.Factory upstream) { this.upstream = upstream; }
        @Override public DataSource createDataSource() { return new Source(upstream.createDataSource()); }
    }

    static final class Source implements DataSource {
        private final DataSource upstream;
        private final ArrayList<TransferListener> listeners = new ArrayList<TransferListener>();
        private boolean viaUpstream;
        private Entry entry;
        private DataSpec spec;
        private long position;
        private Uri uri;

        Source(DataSource upstream) { this.upstream = upstream; }

        @Override public void addTransferListener(TransferListener transferListener) {
            upstream.addTransferListener(transferListener);
            listeners.add(transferListener);
        }

        @Override public long open(DataSpec dataSpec) throws IOException {
            spec = dataSpec;
            uri = dataSpec.uri;
            entry = null;
            viaUpstream = false;
            if (eligible(dataSpec)) {
                Entry e = obtain(dataSpec.uri.toString());
                synchronized (e) { e.readers++; }
                boolean usable = awaitAnswer(e);
                if (usable) {
                    long total;
                    synchronized (e) { total = e.total; }
                    if (total < 0 || dataSpec.position <= total) {
                        entry = e;
                        position = dataSpec.position;
                        for (TransferListener l : listeners) {
                            l.onTransferInitializing(this, dataSpec, true);
                            l.onTransferStart(this, dataSpec, true);
                        }
                        opened(e);
                        return total >= 0 ? total - position : C.LENGTH_UNSET;
                    }
                }
                synchronized (e) { e.readers--; }
            }
            viaUpstream = true;
            return upstream.open(dataSpec);
        }

        /** Waits until the server answered for this piece. False = let the normal source handle it. */
        private boolean awaitAnswer(Entry e) throws IOException {
            long deadline = System.currentTimeMillis() + 20000L;
            synchronized (e) {
                while (e.status == 0 && !e.failed && !e.cancelled) {
                    if (Thread.currentThread().isInterrupted()) {
                        e.readers--;
                        throw new InterruptedIOException();
                    }
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) return false;
                    try {
                        e.wait(Math.min(left, 250L));
                    } catch (InterruptedException ie) {
                        e.readers--;
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException();
                    }
                }
                return e.status >= 200 && e.status < 300 && !e.failed && !e.cancelled;
            }
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (viaUpstream) return upstream.read(buffer, offset, length);
            if (length == 0) return 0;
            Entry e = entry;
            if (e == null) return C.RESULT_END_OF_INPUT;
            long lastProgress = System.currentTimeMillis();
            long seen = e.received();
            synchronized (e) {
                while (true) {
                    long n = e.available(position);
                    if (n < 0) return C.RESULT_END_OF_INPUT;
                    if (n > 0) {
                        int count = (int) Math.min(n, length);
                        System.arraycopy(e.data, (int) position, buffer, offset, count);
                        position += count;
                        e.lastUse = System.currentTimeMillis();
                        for (TransferListener l : listeners) l.onBytesTransferred(this, spec, true, count);
                        return count;
                    }
                    if (e.failed || e.cancelled) throw new IOException("piece failed: " + e.status);
                    long now = System.currentTimeMillis();
                    long got = 0;
                    for (int f : e.filled) got += f;
                    if (got != seen) { seen = got; lastProgress = now; }
                    if (now - lastProgress > 20000L) throw new IOException("piece stalled");
                    try {
                        e.wait(200L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException();
                    }
                }
            }
        }

        @Override public Uri getUri() { return viaUpstream ? upstream.getUri() : uri; }

        @Override public Map<String, List<String>> getResponseHeaders() {
            return viaUpstream ? upstream.getResponseHeaders() : Collections.<String, List<String>>emptyMap();
        }

        @Override public void close() throws IOException {
            if (viaUpstream) {
                viaUpstream = false;
                upstream.close();
                return;
            }
            Entry e = entry;
            entry = null;
            if (e != null) {
                synchronized (e) { e.readers = Math.max(0, e.readers - 1); }
                for (TransferListener l : listeners) l.onTransferEnd(this, spec, true);
            }
        }
    }
}
