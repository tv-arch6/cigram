package com.Cigram.vid;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an EMBED page (vidbolt.pro, vidfast, vidnest, videm, ... any "player" web page) into a
 * DIRECT stream link (.m3u8 / .mpd / .mp4) that ExoPlayer can open.
 *
 * Why: an embed link is an HTML page, not a video. Handing it to ExoPlayer fails at once and the
 * watch-together player shows "الفيديو ما اشتغل، أعد المحاولة".
 *
 * Two stages, cheapest first:
 *  1. HTTP scrape (background thread): downloads the page (and any nested iframe, up to 3 levels),
 *     looks for stream links in plain text, JSON, escaped text and atob() base64 blobs.
 *  2. Hidden WebView sniffer (only when stage 1 finds nothing, e.g. the page builds the link with
 *     JavaScript): loads the embed page off-screen, presses play, and captures the first stream
 *     request the page makes. Pop-ups and redirects to other sites are blocked.
 *
 * The answer carries the headers the CDN expects, Kodi style, which {@link CigramStreams} already
 * understands:  {@code https://cdn/x.m3u8|Referer=https://vidbolt.pro/&Origin=https://vidbolt.pro&User-Agent=...}
 */
public final class CigramEmbedResolver {

    public interface Callback {
        /** Main thread. {@code url} is playable by ExoPlayer (may carry "|headers"). */
        void onResolved(String url);
        /** Main thread. */
        void onFailed(String reason);
    }

    private static final String TAG = "CigramEmbed";
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** Hosts known to serve player pages instead of files. */
    private static final String[] EMBED_HOSTS = {
            "vidbolt.pro", "vidfast.vc", "vidfast.pro", "vidnest.fun", "videm.xyz", "vidsrc.to", "vidsrc.me",
            "vidsrc.xyz", "vidlink.pro", "embed.su", "2embed.cc", "multiembed.mov", "autoembed.cc"
    };

    private static final Pattern MEDIA = Pattern.compile(
            "(?:https?:)?//[^\\s\"'<>\\\\)\\]}]+?\\.(?:m3u8|mpd|mp4)(?:\\?[^\\s\"'<>\\\\)\\]}]*)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IFRAME = Pattern.compile(
            "<iframe[^>]+?src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATOB = Pattern.compile(
            "atob\\(\\s*[\"']([A-Za-z0-9+/=_-]{24,})[\"']\\s*\\)");

    private static final long CACHE_MS = 8L * 60L * 1000L;
    private static final HashMap<String, String> CACHE = new HashMap<String, String>();
    private static final HashMap<String, Long> CACHE_AT = new HashMap<String, Long>();

    private CigramEmbedResolver() { }

    // ================================================================== detection

    /** True when {@code url} is a web page that has to be resolved first (not a stream/file link). */
    public static boolean isEmbed(String url) {
        String clean = CigramStreams.cleanUrl(url);
        if (clean.length() == 0) return false;
        String lower = clean.toLowerCase(Locale.US);
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false;
        if (CigramStreams.mimeOf(clean) != null) return false;   // a direct stream / file
        String host;
        String path;
        try {
            Uri u = Uri.parse(clean);
            host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.US);
            path = u.getPath() == null ? "" : u.getPath().toLowerCase(Locale.US);
        } catch (Throwable t) {
            return false;
        }
        for (String h : EMBED_HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        if (host.startsWith("embed.") || host.startsWith("player.")) return true;
        if (path.contains("/embed")) return true;
        return path.endsWith(".html") || path.endsWith(".htm") || path.endsWith(".php");
    }

    /** True when the link points at one of the known embed sites (vidbolt, vidfast, ...). */
    public static boolean isKnownHost(String url) {
        String clean = CigramStreams.cleanUrl(url);
        if (clean.length() == 0) return false;
        try {
            String host = Uri.parse(clean).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            for (String h : EMBED_HOSTS) {
                if (host.equals(h) || host.endsWith("." + h)) return CigramStreams.mimeOf(clean) == null;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    // ================================================================== public API

    /** Resolves on a worker thread (+ the WebView on main when needed); the callback runs on the main thread. */
    public static void resolveAsync(final Context context, final String embedUrl, final Callback cb) {
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        final String page = CigramStreams.cleanUrl(embedUrl);
        String cached = cached(page);
        if (cached != null) {
            final String hit = cached;
            UI.post(new Runnable() { @Override public void run() { cb.onResolved(hit); } });
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                String found = null;
                // known embed sites build their stream with JavaScript: a plain download of the page would
                // only find unrelated files (trailers, ads), so they go straight to the real browser
                if (!isKnownHost(page)) {
                    try { found = scrapeBlocking(page); } catch (Throwable error) {
                        android.util.Log.w(TAG, "scrape failed", error);
                    }
                }
                if (found != null) {
                    finish(page, found, cb);
                    return;
                }
                UI.post(new Runnable() {
                    @Override public void run() {
                        sniffWithWebView(context, page, 30000L, new Callback() {
                            @Override public void onResolved(String url) { remember(page, url); cb.onResolved(url); }
                            @Override public void onFailed(final String reason) {
                                if (!isKnownHost(page)) { cb.onFailed(reason); return; }
                                // Last resort for a known embed site whose hidden browser could not run (no
                                // WebView, or one too old, is common on TV boxes): the page itself sometimes
                                // ships the link in plain text. pickMedia() already rejects trailers, ads and samples.
                                Thread fallback = new Thread(new Runnable() {
                                    @Override public void run() {
                                        String hit = null;
                                        try { hit = scrapeBlocking(page); } catch (Throwable error) {
                                            android.util.Log.w(TAG, "fallback scrape failed", error);
                                        }
                                        if (hit != null) {
                                            log(app, "fallback scrape found a stream after: " + reason);
                                            finish(page, hit, cb);
                                        } else {
                                            UI.post(new Runnable() { @Override public void run() { cb.onFailed(reason); } });
                                        }
                                    }
                                }, "cigram-embed-fallback");
                                fallback.setDaemon(true);
                                fallback.start();
                            }
                        });
                    }
                });
            }
        }, "cigram-embed-resolve");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Blocking version for worker threads (never call it on the main thread).
     * @return a playable link, or "" when none could be found
     */
    public static String resolveBlocking(Context context, String embedUrl) {
        if (Looper.myLooper() == Looper.getMainLooper()) return "";
        final String[] out = {""};
        final CountDownLatch latch = new CountDownLatch(1);
        resolveAsync(context, embedUrl, new Callback() {
            @Override public void onResolved(String url) { out[0] = url == null ? "" : url; latch.countDown(); }
            @Override public void onFailed(String reason) { latch.countDown(); }
        });
        try { latch.await(45, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return out[0];
    }

    // ================================================================== stage 1: HTTP scrape

    /** Header-carrying result for a stream found while scraping {@code page}, or null. */
    private static String scrapeBlocking(String page) throws Exception {
        String found = scrape(page, page, 0, new HashSet<String>());
        return found == null ? null : withHeaders(found, page);
    }

    private static String scrape(String pageUrl, String referer, int depth, HashSet<String> seen) {
        if (depth > 3 || pageUrl == null || !seen.add(pageUrl)) return null;
        String body = fetch(pageUrl, referer);
        if (body == null || body.length() == 0) return null;
        String text = unescape(body);

        String direct = pickMedia(text, pageUrl);
        if (direct != null) return direct;

        // base64 blobs: atob("aHR0cHM6Ly8uLi5tM3U4")
        Matcher a = ATOB.matcher(text);
        while (a.find()) {
            try {
                String decoded = new String(Base64.decode(a.group(1), Base64.DEFAULT | Base64.URL_SAFE), "UTF-8");
                String hit = pickMedia(unescape(decoded), pageUrl);
                if (hit != null) return hit;
            } catch (Throwable ignored) { }
        }

        // nested players: follow every iframe, the first one that gives a stream wins
        Matcher f = IFRAME.matcher(text);
        int followed = 0;
        while (f.find() && followed < 4) {
            String src = f.group(1).trim();
            if (src.length() == 0 || src.startsWith("about:") || src.startsWith("javascript:")) continue;
            String abs = absolute(pageUrl, src);
            if (abs == null) continue;
            followed++;
            String hit = scrape(abs, pageUrl, depth + 1, seen);
            if (hit != null) return hit;
        }
        return null;
    }

    /** Best stream in the text: HLS first, then DASH, then mp4; ads / trailers / images are skipped. */
    private static String pickMedia(String text, String base) {
        String hls = null, dash = null, mp4 = null;
        Matcher m = MEDIA.matcher(text);
        while (m.find()) {
            String u = m.group();
            if (u.startsWith("//")) u = "https:" + u;
            String low = u.toLowerCase(Locale.US);
            if (low.contains("trailer") || low.contains("/ads/") || low.contains("preroll")
                    || low.contains("advert") || low.contains("sample") || low.contains("thumbnail")) continue;
            if (low.contains(".m3u8")) { if (hls == null) hls = u; }
            else if (low.contains(".mpd")) { if (dash == null) dash = u; }
            else if (mp4 == null) mp4 = u;
        }
        return hls != null ? hls : (dash != null ? dash : mp4);
    }

    private static String fetch(String url, String referer) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", CigramStreams.UA);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            if (referer != null && referer.length() > 0) c.setRequestProperty("Referer", origin(referer) + "/");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0 && total < 1_500_000) { out.write(buf, 0, n); total += n; }
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            if (c != null) c.disconnect();
        }
    }

    private static String unescape(String s) {
        return s.replace("\\/", "/").replace("\\u0026", "&").replace("\\u003d", "=")
                .replace("&amp;", "&").replace("\\u002F", "/").replace("\\x2F", "/");
    }

    private static String absolute(String base, String rel) {
        try {
            if (rel.startsWith("//")) return "https:" + rel;
            return new URL(new URL(base), rel).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================== stage 2: hidden WebView

    /** The browser the embed sites are tested with (same as the web player used before). */
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final java.util.concurrent.ExecutorService PROBE =
            java.util.concurrent.Executors.newFixedThreadPool(3, new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "cigram-embed-probe");
                    t.setDaemon(true);
                    return t;
                }
            });

    /** Files a page loads that can never be the video: not worth asking the server about. */
    private static boolean isStaticAsset(String low) {
        int q = low.indexOf('?');
        String path = q >= 0 ? low.substring(0, q) : low;
        String[] ext = {".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".woff", ".woff2",
                ".ttf", ".otf", ".map", ".html", ".htm", ".json", ".vtt", ".srt", ".txt", ".xml"};
        for (String e : ext) if (path.endsWith(e)) return true;
        return false;
    }

    /** What a stream candidate really is, ranked: lower = better. */
    private static final int T_MASTER = 1, T_DASH = 2, T_MEDIA = 3, T_BIG_MP4 = 4, T_UNKNOWN_STREAM = 5, T_NONE = 99;
    /** What the pieces of a playlist carry. */
    private static final int C_UNKNOWN = 0, C_MUXED = 1, C_VIDEO = 2, C_AUDIO = 3;
    private static final String[] C_NAMES = {"unknown", "video+audio", "VIDEO ONLY", "AUDIO ONLY"};

    /** One request of the page that may be the video, with what was learned about it. */
    private static final class Candidate {
        final String url;
        final Map<String, String> headers;
        volatile int type = -1;          // -1 = not classified yet
        volatile boolean audioOnly;      // a media playlist whose pieces carry only sound
        volatile int content;            // what the pieces carry: C_UNKNOWN / C_MUXED / C_VIDEO / C_AUDIO
        volatile boolean masterHasAudio; // a master playlist that brings sound (audio group, or muxed variants)
        volatile String text = "";       // the beginning of the playlist (diagnostics + audio detection)
        Candidate(String url, Map<String, String> headers) { this.url = url; this.headers = headers; }
    }

    private static HttpURLConnection open(String url, Map<String, String> requestHeaders, String range) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", DESKTOP_UA);
        if (requestHeaders != null) {
            for (Map.Entry<String, String> h : requestHeaders.entrySet()) {
                String k = h.getKey();
                if (k == null || h.getValue() == null) continue;
                String kl = k.toLowerCase(Locale.US);
                if (kl.equals("referer") || kl.equals("origin") || kl.equals("accept") || kl.equals("accept-language")
                        || kl.equals("x-requested-with") || kl.startsWith("sec-")) {
                    c.setRequestProperty(k, h.getValue());
                }
            }
        }
        try {
            String cookie = android.webkit.CookieManager.getInstance().getCookie(url);
            if (cookie != null && cookie.length() > 0) c.setRequestProperty("Cookie", cookie);
        } catch (Throwable ignored) { }
        if (range != null) c.setRequestProperty("Range", range);
        return c;
    }

    private static byte[] readUpTo(HttpURLConnection c, int max) throws Exception {
        InputStream in = c.getInputStream();
        try {
            byte[] buf = new byte[max];
            int n = 0, r;
            while (n < max && (r = in.read(buf, n, max - n)) > 0) n += r;
            return n == max ? buf : java.util.Arrays.copyOf(buf, n);
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    /**
     * Reads the beginning of the candidate with the page's own headers and says what it is. A HLS MASTER
     * playlist (it lists the qualities AND the audio tracks) is the right thing to hand to the player: a
     * single quality playlist found first plays without sound.
     */
    private static void classify(Candidate cand) {
        HttpURLConnection c = null;
        try {
            c = open(cand.url, cand.headers, "bytes=0-65535");
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) { cand.type = T_NONE; return; }
            String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.US);
            long total = -1L;
            String range = c.getHeaderField("Content-Range");
            if (range != null && range.indexOf('/') > 0) {
                try { total = Long.parseLong(range.substring(range.indexOf('/') + 1).trim()); } catch (Throwable ignored) { }
            } else {
                total = c.getContentLengthLong();
            }
            byte[] buf = readUpTo(c, 65536);
            int n = buf.length;
            if (n < 8) { cand.type = T_NONE; return; }
            String head = new String(buf, 0, Math.min(n, 64), "ISO-8859-1").trim();
            if (head.startsWith("#EXTM3U") || type.contains("mpegurl")) {
                String text = new String(buf, 0, n, "ISO-8859-1");
                cand.text = text;
                if (text.contains("#EXT-X-STREAM-INF")) {
                    cand.type = T_MASTER;
                    cand.masterHasAudio = masterBringsAudio(cand, text);
                    return;
                }
                if (text.contains("#EXTINF")) {
                    cand.type = T_MEDIA;
                    cand.content = segmentContent(cand.url, cand.headers, text);
                    cand.audioOnly = cand.content == C_AUDIO;
                    return;
                }
                cand.type = T_NONE;
                return;
            }
            if (type.contains("dash+xml") || new String(buf, 0, Math.min(n, 400), "ISO-8859-1").contains("<MPD")) {
                cand.type = T_DASH;
                return;
            }
            boolean ftyp = buf[4] == 'f' && buf[5] == 't' && buf[6] == 'y' && buf[7] == 'p';
            if (ftyp || type.startsWith("video/mp4") || type.startsWith("video/webm") || type.startsWith("video/x-matroska")) {
                // a whole film is big; the small mp4 pieces a page loads (segments, previews, ads) are not
                cand.type = total > 30L * 1024L * 1024L ? T_BIG_MP4 : T_NONE;
                return;
            }
            cand.type = T_NONE;
        } catch (Throwable t) {
            cand.type = T_NONE;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * True when a master playlist brings sound: it declares an audio group, or its first quality's
     * pieces really carry audio (checked on the bytes, not guessed from the names).
     */
    private static boolean masterBringsAudio(Candidate master, String text) {
        if (text.contains("TYPE=AUDIO")) return true;
        try {
            String variant = null;
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].trim().startsWith("#EXT-X-STREAM-INF")) {
                    for (int j = i + 1; j < lines.length; j++) {
                        String l = lines[j].trim();
                        if (l.length() > 0 && !l.startsWith("#")) { variant = l; break; }
                    }
                    break;
                }
            }
            String abs = variant == null ? null : absolute(master.url, variant);
            if (abs == null) return true;   // cannot check: trust it
            HttpURLConnection c = null;
            try {
                c = open(abs, master.headers, null);
                if (c.getResponseCode() / 100 != 2) return true;
                String media = new String(readUpTo(c, 65536), "ISO-8859-1");
                int kind = segmentContent(abs, master.headers, media);
                return kind != C_VIDEO;     // only a confirmed picture-only master lacks sound
            } finally {
                if (c != null) c.disconnect();
            }
        } catch (Throwable t) {
            return true;
        }
    }

    /** What the pieces of a media playlist carry, read from the init section or the first piece. */
    private static int segmentContent(String playlistUrl, Map<String, String> headers, String text) {
        try {
            String firstSegment = null;
            String map = null;
            for (String line : text.split("\n")) {
                line = line.trim();
                if (line.startsWith("#EXT-X-MAP:") && map == null) {
                    int i = line.indexOf("URI=\"");
                    if (i >= 0) { int j = line.indexOf('"', i + 5); if (j > i) map = line.substring(i + 5, j); }
                } else if (line.length() > 0 && !line.startsWith("#") && firstSegment == null) {
                    firstSegment = line;
                }
            }
            String probeRef = map != null ? map : firstSegment;
            if (probeRef == null) return C_UNKNOWN;
            String lower = probeRef.toLowerCase(Locale.US);
            int q = lower.indexOf('?');
            String path = q >= 0 ? lower.substring(0, q) : lower;
            if (path.endsWith(".aac") || path.endsWith(".m4a") || path.endsWith(".mp3") || path.endsWith(".ac3")
                    || path.endsWith(".ec3") || path.endsWith(".opus")) return C_AUDIO;
            String abs = absolute(playlistUrl, probeRef);
            if (abs == null) return C_UNKNOWN;
            HttpURLConnection c = null;
            try {
                c = open(abs, headers, "bytes=0-262143");
                int code = c.getResponseCode();
                if (code < 200 || code >= 400) return C_UNKNOWN;
                return analyze(readUpTo(c, 262144));
            } finally {
                if (c != null) c.disconnect();
            }
        } catch (Throwable t) {
            return C_UNKNOWN;
        }
    }

    /**
     * Reads a piece and says whether it carries picture, sound, or both. Works on pieces that are
     * disguised as pictures (a fake .png / .jpg header in front of the real data), which embed sites use:
     * the real container is searched for anywhere in the first bytes.
     */
    private static int analyze(byte[] b) {
        int n = b.length;
        if (n < 16) return C_UNKNOWN;
        // --- MPEG-TS: find three sync bytes 188 apart, anywhere
        int start = -1;
        for (int k = 0; k + 376 < n && k < 65536; k++) {
            if (b[k] == 0x47 && b[k + 188] == 0x47 && b[k + 376] == 0x47) { start = k; break; }
        }
        if (start >= 0) {
            boolean video = false, audio = false;
            for (int p = start; p + 188 <= n; p += 188) {
                if (b[p] != 0x47) break;
                boolean unitStart = (b[p + 1] & 0x40) != 0;
                int afc = (b[p + 3] >> 4) & 0x3;
                if (!unitStart || (afc & 0x1) == 0) continue;            // no payload start here
                int off = p + 4;
                if ((afc & 0x2) != 0) off += 1 + (b[p + 4] & 0xFF);      // skip the adaptation field
                if (off + 4 > p + 188) continue;
                if (b[off] == 0 && b[off + 1] == 0 && b[off + 2] == 1) {
                    int id = b[off + 3] & 0xFF;
                    if (id >= 0xE0 && id <= 0xEF) video = true;
                    else if ((id >= 0xC0 && id <= 0xDF) || id == 0xBD) audio = true;
                }
                if (video && audio) break;
            }
            return video && audio ? C_MUXED : video ? C_VIDEO : audio ? C_AUDIO : C_UNKNOWN;
        }
        // --- fragmented / plain MP4 (init section or piece)
        String s = new String(b, 0, Math.min(n, 65536), java.nio.charset.Charset.forName("ISO-8859-1"));
        if (s.contains("ftyp") || s.contains("styp") || s.contains("moov") || s.contains("moof")) {
            boolean audio = s.contains("mp4a") || s.contains("ec-3") || s.contains("ac-3") || s.contains("Opus")
                    || s.contains("soun");
            boolean video = s.contains("avc1") || s.contains("avc3") || s.contains("hvc1") || s.contains("hev1")
                    || s.contains("av01") || s.contains("vp09") || s.contains("vide");
            return video && audio ? C_MUXED : video ? C_VIDEO : audio ? C_AUDIO : C_UNKNOWN;
        }
        // --- packed audio (AAC / MP3 with an ID3 tag, or a bare ADTS frame)
        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') return C_AUDIO;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xF0) == 0xF0) return C_AUDIO;
        return C_UNKNOWN;
    }

    private static final String JS_PLAY_ALL =
            "(function(){function docs(){var out=[document];(function walk(d){try{var f=d.querySelectorAll('iframe');"
                    + "for(var i=0;i<f.length;i++){try{var cd=f[i].contentDocument;if(cd){out.push(cd);walk(cd);}}catch(e){}}}catch(e){}})(document);return out;}"
                    + "docs().forEach(function(d){try{var v=d.querySelectorAll('video');for(var i=0;i<v.length;i++){v[i].muted=true;"
                    + "var p=v[i].play&&v[i].play();if(p&&p.catch)p.catch(function(){});}"
                    + "var b=d.querySelector('.vjs-big-play-button,.jw-icon-display,.plyr__control--overlaid,#play,.play,.play-button,.play-btn,"
                    + "[class*=\"play-btn\"],[class*=\"PlayButton\"],[class*=\"play_button\"],[aria-label*=\"lay\"],button[title*=\"lay\"]');"
                    + "if(b)b.click();"
                    + "var el=d.elementFromPoint((d.documentElement.clientWidth||300)/2,(d.documentElement.clientHeight||200)/2);"
                    + "if(el&&el.click)el.click();}catch(e){}});})();";

    private static final String JS_VIDEO_SRC =
            "(function(){function docs(){var out=[document];(function walk(d){try{var f=d.querySelectorAll('iframe');"
                    + "for(var i=0;i<f.length;i++){try{var cd=f[i].contentDocument;if(cd){out.push(cd);walk(cd);}}catch(e){}}}catch(e){}})(document);return out;}"
                    + "var out='';docs().forEach(function(d){try{var v=d.querySelectorAll('video');for(var i=0;i<v.length;i++){"
                    + "var s=v[i].currentSrc||v[i].src;if(s&&s.indexOf('blob:')!==0&&s.indexOf('http')===0&&!out)out=s;}}catch(e){}});return out;})()";

    /**
     * Plays the embed page in a real WebView (attached to the screen when an Activity is available, 2x2 px,
     * so the browser really renders and plays it) and takes the first request that is the video:
     *  - a URL that looks like a stream (.m3u8 / .mpd / .mp4 / "m3u8" / playlist / master ...), or
     *  - any other request whose first bytes ARE a stream ("#EXTM3U", "&lt;MPD", "ftyp"): sites that hide
     *    the playlist behind a path without an extension are found this way, or
     *  - the plain http(s) source of the page's &lt;video&gt;.
     */
    @SuppressLint({"SetJavaScriptEnabled"})
    private static void sniffWithWebView(final Context context, final String page, final long timeoutMs, final Callback cb) {
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        APP = app;
        final boolean[] done = {false};
        final WebView[] holder = {null};
        final String pageOrigin = origin(page);
        final String[] result = {null};
        final String[] failReason = {null};   // why it failed: "timeout", "webview failed: ...", or null = "no stream found"
        final java.util.HashSet<String> probed = new java.util.HashSet<String>();
        final int[] probes = {0};
        final java.util.ArrayList<Candidate> cands = new java.util.ArrayList<Candidate>();
        final int[] qState = {0};
        final String[] qMaster = {null};
        final long[] firstAt = {android.os.SystemClock.uptimeMillis()};
        log(app, "---- resolving " + page);

        final Runnable[] finisher = new Runnable[1];
        final Runnable timeout = new Runnable() {
            @Override public void run() {
                if (result[0] == null && failReason[0] == null) failReason[0] = "timeout";
                finisher[0].run();
            }
        };
        finisher[0] = new Runnable() {
            @Override public void run() {
                if (done[0]) return;
                done[0] = true;
                UI.removeCallbacks(timeout);
                WebView w = holder[0];
                holder[0] = null;
                if (w != null) {
                    try {
                        w.stopLoading();
                        w.loadUrl("about:blank");
                        ViewGroup parent = (ViewGroup) w.getParent();
                        if (parent != null) parent.removeView(w);
                        w.removeAllViews();
                        w.destroy();
                    } catch (Throwable ignored) { }
                }
                if (result[0] != null) cb.onResolved(result[0]);
                else cb.onFailed(failReason[0] != null ? failReason[0] : "no stream found");
            }
        };

        try {
            final WebView web = new WebView(context instanceof Activity ? context : app);
            holder[0] = web;
            log(app, "webview: " + describeWebView());
            // The sniffer is invisible: it must never take the remote's focus (TV) or get a focus ring.
            web.setFocusable(false);
            web.setFocusableInTouchMode(false);
            web.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            web.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setUserAgentString(DESKTOP_UA);
            s.setLoadsImagesAutomatically(false);
            s.setSupportMultipleWindows(false);
            s.setJavaScriptCanOpenWindowsAutomatically(false);
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(web, true);

            // a 2x2 px, almost transparent view on the screen: invisible, but the page is really rendered and played
            if (context instanceof Activity) {
                try {
                    ViewGroup decor = (ViewGroup) ((Activity) context).getWindow().getDecorView();
                    web.setAlpha(0.01f);
                    decor.addView(web, new ViewGroup.LayoutParams(2, 2));
                } catch (Throwable ignored) { }
            }

            web.setWebViewClient(new WebViewClient() {
                /** A request that may be the video: classify it now, and decide soon (or at once for the best kinds). */
                private void offer(final String url, final Map<String, String> headers) {
                    final Candidate cand;
                    synchronized (cands) {
                        for (Candidate old : cands) if (old.url.equals(url)) return;
                        cand = new Candidate(url, headers);
                        cands.add(cand);
                        if (cands.size() == 1) {
                            firstAt[0] = android.os.SystemClock.uptimeMillis();
                            // first sign of a video: give the page a moment to ask for the MASTER playlist too
                            UI.postDelayed(new Runnable() { @Override public void run() { decide(); } }, 2500L);
                        }
                    }
                    PROBE.execute(new Runnable() { @Override public void run() {
                        if (done[0]) return;
                        classify(cand);
                        log(app, "candidate type=" + cand.type
                                + (cand.type == T_MEDIA ? " carries " + C_NAMES[cand.content] : "")
                                + (cand.type == T_MASTER ? (cand.masterHasAudio ? " with sound" : " WITHOUT sound") : "")
                                + "  " + url);
                        // complete answers (picture AND sound) need no more waiting
                        boolean complete = (cand.type == T_MASTER && cand.masterHasAudio) || cand.type == T_BIG_MP4
                                || cand.type == T_DASH || (cand.type == T_MEDIA && (cand.content == C_MUXED || cand.content == C_AUDIO));
                        if (complete) UI.post(new Runnable() { @Override public void run() { decide(); } });
                    } });
                }

                /**
                 * Picks what the player gets, SOUND INCLUDED:
                 *  1. a master playlist that brings sound, a DASH manifest, a playlist whose pieces carry picture
                 *     AND sound, or a whole mp4 file;
                 *  2. otherwise picture and sound found as separate playlists are joined into one master;
                 *  3. while the sound has not shown up yet, wait for it (up to ~7 s after the first candidate);
                 *  4. last resort: the best picture found (logged as "no sound found").
                 */
                private void decide() {
                    if (done[0] || result[0] != null) return;
                    Candidate master = null, masterNoSound = null, dash = null, muxed = null, mp4 = null;
                    Candidate videoOnly = null, audio = null, unknownMedia = null, loose = null;
                    boolean pending = false;
                    synchronized (cands) {
                        for (Candidate c : cands) {
                            if (c.type == -1) {
                                pending = true;
                                String low = c.url.toLowerCase(Locale.US);
                                if (loose == null && (low.contains("m3u8") || low.contains(".mpd"))) loose = c;
                            } else if (c.type == T_MASTER) {
                                if (c.masterHasAudio) { if (master == null) master = c; }
                                else if (masterNoSound == null) masterNoSound = c;
                            } else if (c.type == T_DASH) { if (dash == null) dash = c; }
                            else if (c.type == T_BIG_MP4) { if (mp4 == null) mp4 = c; }
                            else if (c.type == T_MEDIA) {
                                if (c.content == C_MUXED) { if (muxed == null) muxed = c; }
                                else if (c.content == C_AUDIO) { if (audio == null) audio = c; }
                                else if (c.content == C_VIDEO) { if (videoOnly == null) videoOnly = c; }
                                else if (unknownMedia == null) unknownMedia = c;
                            }
                        }
                    }
                    Candidate pick = master != null ? master : dash != null ? dash : muxed != null ? muxed : mp4;
                    String stream = pick == null ? null : pick.url;
                    String how = pick == null ? "" : "complete";
                    if (pick == null && audio != null) {
                        Candidate picture = masterNoSound != null ? masterNoSound : videoOnly != null ? videoOnly : unknownMedia;
                        if (picture != null && picture != masterNoSound) {
                            // v10.81: look for the other resolutions of this title (video_1080p, video_480p ...) first
                            if (qState[0] == 0) {
                                qState[0] = 1;
                                final java.util.ArrayList<String> urls = new java.util.ArrayList<String>();
                                synchronized (cands) { for (Candidate c : cands) if (c.type == T_MEDIA) urls.add(c.url); }
                                final String audioUrl = audio.url;
                                PROBE.execute(new Runnable() { @Override public void run() {
                                    qMaster[0] = qualityMaster(urls, audioUrl, page);
                                    qState[0] = 2;
                                    UI.post(new Runnable() { @Override public void run() { decide(); } });
                                } });
                                return;
                            }
                            if (qState[0] == 1) {
                                UI.postDelayed(new Runnable() { @Override public void run() { decide(); } }, 500L);
                                return;
                            }
                        }
                        if (picture != null) {
                            String joined = picture == masterNoSound ? joinedMasterFrom(picture, audio)
                                    : (qMaster[0] != null ? qMaster[0] : joinedMaster(picture.url, audio.url));
                            if (joined != null) { pick = picture; stream = joined; how = "picture + separate sound " + audio.url; }
                        }
                    }
                    if (pick == null) {
                        long waited = android.os.SystemClock.uptimeMillis() - firstAt[0];
                        if ((pending || audio == null) && waited < 7000L) {
                            // the sound playlist is usually asked for right after the picture: give it a little longer
                            UI.postDelayed(new Runnable() { @Override public void run() { decide(); } }, 1200L);
                            return;
                        }
                        pick = masterNoSound != null ? masterNoSound : videoOnly != null ? videoOnly
                                : unknownMedia != null ? unknownMedia : loose;
                        if (pick == null) return;   // nothing usable yet: the timeout ends it
                        stream = pick.url;
                        how = "NO SOUND FOUND (best picture only)";
                    }
                    Map<String, String> h = pick.headers;
                    String ref = h == null ? null : h.get("Referer");
                    final String useRef = ref != null && ref.length() > 0 ? ref : page;
                    result[0] = withHeaders(stream, useRef, DESKTOP_UA);
                    // every other usable way to play the same title, best first: if the sound turns out to be missing
                    // the player tries them one after the other (see PlayerMonitor.evaluateAudio)
                    java.util.ArrayList<String> alts = new java.util.ArrayList<String>();
                    alts.add(result[0]);
                    Candidate[] order = {master, dash, muxed, mp4, masterNoSound, videoOnly, unknownMedia};
                    for (Candidate c : order) {
                        if (c == null) continue;
                        String u = withHeaders(c.url, useRef, DESKTOP_UA);
                        if (!alts.contains(u)) alts.add(u);
                    }
                    if (audio != null) {
                        Candidate[] pictures = {masterNoSound, videoOnly, unknownMedia};
                        for (Candidate c : pictures) {
                            if (c == null) continue;
                            String joined = c == masterNoSound ? joinedMasterFrom(c, audio) : joinedMaster(c.url, audio.url);
                            if (joined == null) continue;
                            String u = withHeaders(joined, useRef, DESKTOP_UA);
                            if (!alts.contains(u)) alts.add(u);
                        }
                    }
                    setAlternatives(page, alts);
                    try { noteHost(Uri.parse(pick.url).getHost()); } catch (Throwable ignored) { }
                    log(app, "CHOSEN (" + how + ")  " + pick.url);
                    log(app, "chosen playlist begins:\n" + (pick.text.length() > 700 ? pick.text.substring(0, 700) : pick.text));
                    finisher[0].run();
                }

                @Override public WebResourceResponse shouldInterceptRequest(WebView view, final WebResourceRequest request) {
                    try {
                        if (result[0] != null || done[0]) return null;
                        final String u = request.getUrl().toString();
                        if (!(u.startsWith("http://") || u.startsWith("https://"))) return null;
                        String low = u.toLowerCase(Locale.US);
                        boolean ad = low.contains("trailer") || low.contains("/ads/") || low.contains("preroll")
                                || low.contains("advert") || low.contains(".mp4.jpg") || low.contains("doubleclick")
                                || low.contains("googlesyndication") || low.contains("adservice");
                        if (ad || !"GET".equalsIgnoreCase(request.getMethod())) return null;
                        if (!isStaticAsset(low)) noteHost(request.getUrl().getHost());
                        boolean looksLikeStream = low.contains("m3u8") || low.contains(".mpd") || low.contains(".mp4")
                                || low.contains("/playlist") || low.contains("/master") || low.contains("/manifest");
                        if (looksLikeStream) {
                            offer(u, request.getRequestHeaders());
                        } else if (!isStaticAsset(low)) {
                            // no hint in the address: look at what it really is (a few requests only)
                            boolean first;
                            synchronized (probed) { first = probes[0] < 150 && probed.add(u); if (first) probes[0]++; }
                            if (first) offer(u, request.getRequestHeaders());
                        }
                    } catch (Throwable ignored) { }
                    return null;
                }

                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    // stay on the player: pop-up ads / redirects to other sites are blocked
                    String u = request.getUrl().toString();
                    return request.isForMainFrame() && !origin(u).equalsIgnoreCase(pageOrigin) || request.hasGesture();
                }

                @Override public void onPageFinished(WebView view, String url) {
                    // press play in the page and every frame of it, several times while it loads
                    for (int i = 0; i < 12; i++) {
                        final long delay = 600L + i * 1800L;
                        UI.postDelayed(new Runnable() { @Override public void run() {
                            if (done[0] || holder[0] == null) return;
                            web.evaluateJavascript(JS_PLAY_ALL, null);
                            web.evaluateJavascript(JS_VIDEO_SRC, new android.webkit.ValueCallback<String>() {
                                @Override public void onReceiveValue(String value) {
                                    if (done[0] || result[0] != null || value == null) return;
                                    String v = value.replace("\"", "").replace("\\/", "/").trim();
                                    if (v.startsWith("http")) {
                                        String low = v.toLowerCase(Locale.US);
                                        if (!low.contains("trailer") && !low.contains("/ads/")) offer(v, null);
                                    }
                                }
                            });
                        } }, delay);
                    }
                }
            });

            // the same page the site's web player always used: the embed inside a page of its own origin
            String html = "<html><head><meta name=\"referrer\" content=\"no-referrer\"></head>"
                    + "<body style=\"margin:0;padding:0;background:black;\">"
                    + "<iframe src=\"" + page + "\" width=\"100%\" height=\"100%\" frameborder=\"0\" allowfullscreen"
                    + " allow=\"autoplay; fullscreen; encrypted-media\"></iframe></body></html>";
            web.loadDataWithBaseURL(pageOrigin, html, "text/html", "UTF-8", null);
            UI.postDelayed(timeout, timeoutMs);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "webview sniff failed", t);
            log(app, "webview sniff failed: " + t.getClass().getSimpleName() + " (" + describeWebView() + ")");
            if (failReason[0] == null) failReason[0] = "webview failed: " + t.getClass().getSimpleName();
            finisher[0].run();
        }
    }

    // ================================================================== helpers

    // ================================================================== qualities (v10.81)

    private static final int[] LADDER = {2160, 1440, 1080, 720, 480, 360, 240};
    private static final Pattern HEIGHT_IN_NAME = Pattern.compile("(?<![0-9A-Za-z])(\\d{3,4})p(?![0-9A-Za-z])");

    private static int bandwidthFor(int h) {
        if (h >= 2160) return 18000000;
        if (h >= 1440) return 10000000;
        if (h >= 1080) return 6000000;
        if (h >= 720) return 3000000;
        if (h >= 480) return 1500000;
        if (h >= 360) return 900000;
        return 500000;
    }

    /**
     * Sites such as vidbolt offer one playlist per resolution under names like {@code video_720p.m3u8}. Only the
     * one the page played is seen; the others are looked for by name (2160p ... 240p) and each one that answers
     * with a real playlist whose first piece exists is added. The result is ONE master playlist (a data: link)
     * with every resolution and the separate sound, so the player's quality menu lists «تلقائي (موصى به)» and
     * every resolution under it. Returns null when the names show no resolution or nothing else answers.
     */
    private static String qualityMaster(java.util.List<String> mediaUrls, String audioUrl, String referer) {
        try {
            String template = null;
            for (String u : mediaUrls) {
                if (u == null || u.equals(audioUrl)) continue;
                String low = u.toLowerCase(Locale.US);
                if (low.contains("subtitle") || low.contains("track_") || low.contains("audio")) continue;
                int q = u.indexOf('?');
                String path = q < 0 ? u : u.substring(0, q);
                Matcher m = HEIGHT_IN_NAME.matcher(path);
                String last = null;
                int a = -1, b = -1;
                while (m.find()) { a = m.start(1); b = m.end(1); last = m.group(1); }
                if (last == null) continue;
                template = u.substring(0, a) + "{H}" + u.substring(b);
                break;
            }
            if (template == null && audioUrl != null) {
                // the page may not have asked for them yet: the usual name next to the sound playlist (checked below)
                int q = audioUrl.indexOf('?');
                String path = q < 0 ? audioUrl : audioUrl.substring(0, q);
                int slash = path.lastIndexOf('/');
                if (slash > 8) template = path.substring(0, slash + 1) + "video_{H}p.m3u8" + (q < 0 ? "" : audioUrl.substring(q));
            }
            if (template == null) return null;

            final HashMap<String, String> headers = new HashMap<String, String>();
            if (referer != null && referer.length() > 0) {
                headers.put("Referer", origin(referer) + "/");
                headers.put("Origin", origin(referer));
            }
            final int[] verified = new int[LADDER.length];
            final String tpl = template;
            final String ref = referer;
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(LADDER.length);
            for (int i = 0; i < LADDER.length; i++) {
                final int slot = i;
                final int h = LADDER[i];
                PROBE.execute(new Runnable() { @Override public void run() {
                    try {
                        String url = tpl.replace("{H}", Integer.toString(h));
                        String text = fetch(url, ref);
                        if (text == null || !text.trim().startsWith("#EXTM3U") || !text.contains("#EXTINF")) return;
                        String first = null;
                        for (String line : text.split("\n")) {
                            String t = line.trim();
                            if (t.length() > 0 && !t.startsWith("#")) { first = t; break; }
                        }
                        if (first == null) return;
                        int status = statusOf(absolute(url, first), headers);
                        if (status >= 200 && status < 300) verified[slot] = h;
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                } });
            }
            try { latch.await(9, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }

            StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:6\n");
            if (audioUrl != null) {
                out.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"cg\",NAME=\"audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"")
                        .append(audioUrl).append("\"\n");
            }
            int found = 0;
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < LADDER.length; i++) {
                int h = verified[i];
                if (h == 0) continue;
                int w = (h * 16 / 9) & ~1;
                out.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidthFor(h)).append(",RESOLUTION=").append(w).append('x').append(h)
                        .append(audioUrl != null ? ",AUDIO=\"cg\"" : "").append('\n')
                        .append(template.replace("{H}", Integer.toString(h))).append('\n');
                found++;
                names.append(h).append("p ");
            }
            if (found == 0) return null;
            try { logNow("qualities found: " + names.toString().trim()); } catch (Throwable ignored) { }
            return "data:application/vnd.apple.mpegurl;base64,"
                    + Base64.encodeToString(out.toString().getBytes("UTF-8"), Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    /** A copy of a sound-less master (every quality kept) whose qualities all use the separate sound playlist. */
    private static String joinedMasterFrom(Candidate master, Candidate audio) {
        try {
            StringBuilder out = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:6\n");
            out.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"cg\",NAME=\"audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"")
                    .append(audio.url).append("\"\n");
            String[] lines = master.text.split("\n");
            int variants = 0;
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].trim();
                if (!l.startsWith("#EXT-X-STREAM-INF:")) continue;
                String attrs = l.replaceAll(",?AUDIO=\"[^\"]*\"", "");
                String uri = null;
                for (int j = i + 1; j < lines.length; j++) {
                    String u = lines[j].trim();
                    if (u.length() > 0 && !u.startsWith("#")) { uri = u; break; }
                }
                String abs = uri == null ? null : absolute(master.url, uri);
                if (abs == null) continue;
                attrs = attrs.replace(":,", ":");
                out.append(attrs).append(attrs.endsWith(":") ? "" : ",").append("AUDIO=\"cg\"\n").append(abs).append("\n");
                variants++;
            }
            if (variants == 0) return null;
            return "data:application/vnd.apple.mpegurl;base64,"
                    + Base64.encodeToString(out.toString().getBytes("UTF-8"), Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    /** A master playlist (as a data: link) that joins one picture playlist and one separate audio playlist. */
    private static String joinedMaster(String videoUrl, String audioUrl) {
        try {
            String text = "#EXTM3U\n#EXT-X-VERSION:6\n"
                    + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"cg\",NAME=\"audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\"" + audioUrl + "\"\n"
                    + "#EXT-X-STREAM-INF:BANDWIDTH=3000000,AUDIO=\"cg\"\n" + videoUrl + "\n";
            return "data:application/vnd.apple.mpegurl;base64,"
                    + Base64.encodeToString(text.getBytes("UTF-8"), Base64.NO_WRAP);
        } catch (Throwable t) {
            return null;
        }
    }

    private static volatile Context APP;
    private static long lastNotice;

    /** A short message on screen, at most once every 20 s (used while the server prepares a part of the film). */
    static void noticeOnce(final String text) {
        final Context c = APP;
        long now = android.os.SystemClock.uptimeMillis();
        if (c == null || now - lastNotice < 20000L) return;
        lastNotice = now;
        UI.post(new Runnable() { @Override public void run() {
            try { android.widget.Toast.makeText(c, text, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { }
        } });
    }
    private static final HashSet<String> DIAGNOSED = new HashSet<String>();

    /** For code that has no Context (the load policy). */
    static void logNow(String line) {
        Context c = APP;
        if (c != null) log(c, line);
        else android.util.Log.i(TAG, line);
    }

    /**
     * A segment address was refused (404 / 403 ...). Ask the server the same question with different headers,
     * once per address, and if another set is accepted use it from now on for that host. Everything is logged,
     * so the cause is known and not guessed. Runs on a background thread.
     */
    static void diagnose(final String url, final int code) {
        if (url == null) return;
        synchronized (DIAGNOSED) {
            if (DIAGNOSED.size() > 200 || !DIAGNOSED.add(url)) return;
        }
        PROBE.execute(new Runnable() { @Override public void run() {
            try {
                String host = Uri.parse(url).getHost();
                Map<String, String> base = new HashMap<String, String>(CigramStreams.CURRENT_HEADERS);
                StringBuilder report = new StringBuilder("REFUSED " + code + " " + url + "\n   host=" + host + "  active variant=" + CigramHttpProfile.variantOf(host));
                int accepted = -1;
                for (int v = 0; v < CigramHttpProfile.VARIANTS; v++) {
                    Map<String, String> h = new HashMap<String, String>(base);
                    CigramHttpProfile.applyVariant(h, v, host);
                    int status = statusOf(url, h);
                    report.append("\n   variant ").append(v).append(" -> HTTP ").append(status);
                    if (status >= 200 && status < 300 && accepted < 0) accepted = v;
                }
                // the same address under a new cache key: tells whether the refusal is only a cached answer
                String busted = url + (url.indexOf('?') < 0 ? "?" : "&") + "cgb=" + Long.toString(System.nanoTime(), 36);
                int bustStatus = statusOf(busted, base);
                report.append("\n   same address, new cache key -> HTTP ").append(bustStatus);
                if (accepted >= 0 && accepted != CigramHttpProfile.variantOf(host)) {
                    CigramHttpProfile.setVariant(host, accepted);
                    report.append("\n   => using variant ").append(accepted).append(" for ").append(host).append(" from now on");
                } else if (accepted == CigramHttpProfile.DEFAULT) {
                    report.append("\n   => accepted now with the normal headers: the piece was not ready yet (waiting works)");
                } else if (accepted < 0) {
                    report.append("\n   => no headers help: the address itself is refused");
                }
                logNow(report.toString());
            } catch (Throwable ignored) { }
        } });
    }

    /** The same address under a new cache key (a CDN keeps a "404" for hours, see CigramHttpProfile). */
    static String bust(String url) {
        return url + (url.indexOf('?') < 0 ? "?" : "&") + "cgb=" + Long.toString(System.nanoTime(), 36);
    }

    /** Whether the server has this piece right now (asked under a fresh cache key, with the headers the host accepts). */
    static boolean isAvailable(String url) {
        try {
            String host = Uri.parse(url).getHost();
            Map<String, String> h = new HashMap<String, String>(CigramStreams.CURRENT_HEADERS);
            CigramHttpProfile.applyVariant(h, CigramHttpProfile.variantOf(host), host);
            int st = statusOf(bust(url), h);
            return st >= 200 && st < 300;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * {@code urls[target]} is not on the server (yet). Looks for the last piece before it that IS (binary search:
     * the server prepares the film from the start, so what exists is a run of pieces from 0). Returns its index,
     * or -1 when {@code urls[target]} itself exists. Runs on a background thread (about 10 small requests).
     */
    static int findFrontier(java.util.List<String> urls, int target) {
        if (urls == null || target <= 0 || target >= urls.size()) return -1;
        if (isAvailable(urls.get(target))) return -1;
        int lo = 0, hi = target, guard = 0;
        while (hi - lo > 1 && guard++ < 16) {
            int mid = (lo + hi) >>> 1;
            if (isAvailable(urls.get(mid))) lo = mid; else hi = mid;
        }
        logNow("frontier: piece " + target + " is not ready; last ready piece = " + lo + " of " + urls.size());
        return lo;
    }

    private static int statusOf(String url, Map<String, String> headers) {
        HttpURLConnection c = null;
        try {
            c = open(url, null, null);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getValue() != null) c.setRequestProperty(e.getKey(), e.getValue());
            }
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                try { readUpTo(c, 512); } catch (Throwable ignored) { }
            }
            return code;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The last {@code max} characters of the diagnostics file (what the share button sends). */
    static String logTail(Context context, int max) {
        try {
            java.io.File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            java.io.File file = new java.io.File(dir, "cigram_embed.txt");
            if (!file.isFile()) return "";
            synchronized (LOG_LOCK) {
                java.io.RandomAccessFile f = new java.io.RandomAccessFile(file, "r");
                try {
                    long len = f.length();
                    long from = Math.max(0L, len - max);
                    f.seek(from);
                    byte[] b = new byte[(int) (len - from)];
                    f.readFully(b);
                    return new String(b, "UTF-8");
                } finally {
                    f.close();
                }
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /** Package + version of the system WebView ("none" when the device has none), for the log and the error text. */
    static String describeWebView() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.content.pm.PackageInfo info = WebView.getCurrentWebViewPackage();
                if (info == null) return "none";
                return info.packageName + " " + info.versionName;
            }
        } catch (Throwable ignored) { }
        return "unknown";
    }

    private static int webViewMajor() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.content.pm.PackageInfo info = WebView.getCurrentWebViewPackage();
                if (info != null && info.versionName != null) {
                    int dot = info.versionName.indexOf('.');
                    return Integer.parseInt(dot < 0 ? info.versionName : info.versionName.substring(0, dot));
                }
            }
        } catch (Throwable ignored) { }
        return 0;
    }

    /** "" when a WebView looks usable; otherwise a short Arabic reason (missing, or too old to run the embed pages). */
    public static String webViewProblem() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                if (WebView.getCurrentWebViewPackage() == null) return "محرك المتصفح (WebView) غير مثبّت على هذا الجهاز";
                int major = webViewMajor();
                if (major > 0 && major < 80) return "محرك المتصفح (WebView " + major + ") قديم على هذا الجهاز";
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /** Plain-language Arabic explanation of a resolver failure (the raw code is still shown beside it). */
    public static String friendly(String reason) {
        // 2026: same categories and wording as the player (CigramPlaybackError)
        return CigramPlaybackError.message(CigramPlaybackError.fromResolverReason(null, reason));
    }

    private static final Object LOG_LOCK = new Object();

    /**
     * Writes what the resolver sees to Android/data/&lt;app&gt;/files/cigram_embed.txt (kept below 120 KB), so a
     * stream that does not play can be understood from facts instead of guesses.
     */
    static void log(Context context, String line) {
        android.util.Log.i(TAG, line);
        try {
            java.io.File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            java.io.File file = new java.io.File(dir, "cigram_embed.txt");
            synchronized (LOG_LOCK) {
                if (file.length() > 120000L) file.delete();
                java.io.FileWriter w = new java.io.FileWriter(file, true);
                try {
                    w.write(new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date()) + "  " + line + "\n");
                } finally {
                    w.close();
                }
            }
        } catch (Throwable ignored) { }
    }

    /** Adds the headers the CDN of {@code stream} expects (Referer / Origin of the embed site). */
    private static String withHeaders(String stream, String refererPage) {
        return withHeaders(stream, refererPage, CigramStreams.UA);
    }

    private static String withHeaders(String stream, String refererPage, String userAgent) {
        try {
            String origin = origin(refererPage);
            if (origin.length() == 0) return stream;
            StringBuilder sb = new StringBuilder(stream);
            sb.append("|Referer=").append(URLEncoder.encode(origin + "/", "UTF-8"));
            sb.append("&Origin=").append(URLEncoder.encode(origin, "UTF-8"));
            sb.append("&User-Agent=").append(URLEncoder.encode(userAgent, "UTF-8"));
            return sb.toString();
        } catch (Throwable t) {
            return stream;
        }
    }

    private static String origin(String url) {
        try {
            URL u = new URL(url);
            return u.getProtocol() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Throwable t) {
            return "";
        }
    }

    private static void finish(String page, String found, Callback cb) {
        remember(page, found);
        final String url = found;
        final Callback c = cb;
        UI.post(new Runnable() { @Override public void run() { c.onResolved(url); } });
    }

    private static final HashSet<String> SEEN_HOSTS = new HashSet<String>();

    /** Hosts the browser talked to while finding a stream: their cookies are given to the player (CigramCookieBridge). */
    static synchronized boolean isSeenHost(String host) {
        return host != null && SEEN_HOSTS.contains(host.toLowerCase(Locale.US));
    }

    static synchronized boolean hasSeenHosts() { return !SEEN_HOSTS.isEmpty(); }

    private static synchronized void noteHost(String host) {
        if (host != null && SEEN_HOSTS.size() < 300) SEEN_HOSTS.add(host.toLowerCase(Locale.US));
    }

    private static final HashMap<String, String> STREAM_TO_PAGE = new HashMap<String, String>();

    private static synchronized void remember(String page, String url) {
        CACHE.put(page, url);
        CACHE_AT.put(page, System.currentTimeMillis());
        STREAM_TO_PAGE.put(CigramStreams.cleanUrl(url), page);   // so a failing stream can be traced back to its page
    }

    private static final HashMap<String, java.util.ArrayList<String>> ALTERNATIVES =
            new HashMap<String, java.util.ArrayList<String>>();

    private static synchronized void setAlternatives(String page, java.util.ArrayList<String> list) {
        ALTERNATIVES.put(page, list);
        for (String u : list) STREAM_TO_PAGE.put(CigramStreams.cleanUrl(u), page);
    }

    /** The next way to play {@code page} that has not been tried yet (full link with headers), or null. */
    public static synchronized String nextAlternative(String page, java.util.Set<String> triedCleanUrls) {
        java.util.ArrayList<String> list = page == null ? null : ALTERNATIVES.get(page);
        if (list == null) return null;
        for (String u : list) {
            if (!triedCleanUrls.contains(CigramStreams.cleanUrl(u))) return u;
        }
        return null;
    }

    /** The embed page this stream link was resolved from, or null when it did not come from here. */
    public static synchronized String pageFor(String streamUrl) {
        return streamUrl == null ? null : STREAM_TO_PAGE.get(CigramStreams.cleanUrl(streamUrl));
    }

    /** Forget a resolved stream (it stopped working: expired link): the next resolve starts again. */
    public static synchronized void invalidate(String page) {
        if (page == null) return;
        String old = CACHE.remove(page);
        CACHE_AT.remove(page);
        ALTERNATIVES.remove(page);
        if (old != null) STREAM_TO_PAGE.remove(CigramStreams.cleanUrl(old));
    }

    private static synchronized String cached(String page) {
        Long at = CACHE_AT.get(page);
        if (at == null || System.currentTimeMillis() - at > CACHE_MS) return null;
        return CACHE.get(page);
    }
}
