package com.my.newproject;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;

/**
 * 19.6 — «الفحص الكامل» of a watch link, done ON THE PHONE (the Worker cannot: an iframe answering 200 proves nothing).
 *
 *  1. a VidBolt (or any embed) page is turned into its real stream by {@link CgEmbedResolver} — the same resolver the
 *     Cigram app uses to play it (HTTP scrape, then a hidden browser that presses play and captures the stream);
 *  2. the stream itself is downloaded: an HLS master must list a variant, the variant must list segments and the first
 *     segment must answer with bytes; a DASH manifest must be an MPD; a file must answer a ranged request with bytes.
 *
 * Only a link that passes all of it is «شغال ومفحوص». Results are cached (memory + preferences) for 30 minutes so a
 * sync of 60 titles and the import that follows do not resolve the same page twice.
 */
final class CgStreamProbe {

    static final class Result {
        boolean ok;
        String url = "";
        String stream = "";
        String kind = "";
        String reason = "";
        int variants;
        int segments;
        long ms;
        long checkedAt;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("ok", ok).put("url", url).put("stream", stream).put("kind", kind).put("reason", reason)
                        .put("variants", variants).put("segments", segments).put("ms", ms).put("at", checkedAt);
            } catch (Throwable ignored) { }
            return o;
        }

        static Result fromJson(JSONObject o) {
            Result r = new Result();
            if (o == null) return r;
            r.ok = o.optBoolean("ok");
            r.url = o.optString("url");
            r.stream = o.optString("stream");
            r.kind = o.optString("kind");
            r.reason = o.optString("reason");
            r.variants = o.optInt("variants");
            r.segments = o.optInt("segments");
            r.ms = o.optLong("ms");
            r.checkedAt = o.optLong("at");
            return r;
        }

        /** One line for the admin: «شغال ✓ HLS · 4 جودات» or the reason in Arabic. */
        String label() {
            if (ok) {
                String k = "hls".equals(kind) ? "HLS" : ("dash".equals(kind) ? "DASH" : ("file".equals(kind) ? "ملف" : kind));
                return "شغال ومفحوص ✓ " + k + (variants > 1 ? " · " + variants + " جودات" : "");
            }
            return friendly(reason);
        }
    }

    private static final String PREFS = "cg_stream_probe";
    private static final long FRESH_MS = 30L * 60L * 1000L;
    private static final long FAIL_FRESH_MS = 5L * 60L * 1000L;
    private static final int MAX_TEXT = 768 * 1024;

    private CgStreamProbe() { }

    /** A cached verdict that is still fresh, or null. */
    static Result cached(Context c, String url) {
        String key = CgStreams.cleanUrl(url);
        if (key.length() == 0 || c == null) return null;
        try {
            String raw = prefs(c).getString(key, null);
            if (raw == null) return null;
            Result r = Result.fromJson(new JSONObject(raw));
            long age = System.currentTimeMillis() - r.checkedAt;
            if (age < 0 || age > (r.ok ? FRESH_MS : FAIL_FRESH_MS)) return null;
            return r;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Blocking full check. Never call it on the main thread (it waits for the resolver's hidden browser). */
    static Result verify(Context c, String url, boolean useCache) {
        Result r = new Result();
        r.url = url == null ? "" : url.trim();
        long t0 = System.currentTimeMillis();
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.reason = "main_thread";
            return r;
        }
        if (r.url.length() == 0) { r.reason = "no_video"; return r; }
        if (useCache) {
            Result hit = cached(c, r.url);
            if (hit != null) return hit;
        }
        try {
            String stream = r.url;
            if (CgStreams.mimeOf(stream) == null) {
                if (!(stream.startsWith("http://") || stream.startsWith("https://"))) {
                    r.reason = "bad_url";
                } else {
                    // an embed page (VidBolt and friends): find the real stream exactly like the player does
                    stream = CgEmbedResolver.resolveBlocking(c, CgVidbolt.embed(stream));
                    if (stream == null || stream.length() == 0) r.reason = "no_stream";
                }
            }
            if (r.reason.length() == 0) {
                r.stream = stream;
                checkStream(stream, r);
            }
        } catch (Throwable error) {
            r.ok = false;
            if (r.reason.length() == 0) r.reason = "error:" + error.getClass().getSimpleName();
        }
        r.ms = System.currentTimeMillis() - t0;
        r.checkedAt = System.currentTimeMillis();
        try { prefs(c).edit().putString(CgStreams.cleanUrl(r.url), r.toJson().toString()).apply(); } catch (Throwable ignored) { }
        return r;
    }

    // ------------------------------------------------------------------ the stream itself

    private static void checkStream(String stream, Result r) throws Exception {
        String mime = CgStreams.mimeOf(stream);
        Map<String, String> headers = CgStreams.headersOf(stream);
        String clean = CgStreams.cleanUrl(stream);
        if (clean.startsWith("data:")) {
            // a master the resolver wrote itself (separate picture + sound playlists joined)
            String text = dataText(clean);
            r.kind = "hls";
            checkHlsText(text, "", headers, r);
            return;
        }
        if (CgStreams.MPD.equals(mime)) {
            r.kind = "dash";
            String text = getText(clean, headers);
            r.ok = text != null && text.contains("<MPD");
            if (!r.ok) r.reason = "bad_manifest";
            return;
        }
        if (CgStreams.M3U8.equals(mime) || mime == null) {
            String text = getText(clean, headers);
            if (text != null && text.trim().startsWith("#EXTM3U")) {
                r.kind = "hls";
                checkHlsText(text, clean, headers, r);
                return;
            }
            if (CgStreams.M3U8.equals(mime)) { r.reason = "bad_manifest"; return; }
        }
        // a progressive file: a ranged request must answer with bytes
        r.kind = "file";
        int got = firstBytes(clean, headers);
        r.ok = got > 0;
        if (!r.ok) r.reason = got == -404 ? "http_404" : (got < 0 ? "http_" + (-got) : "empty");
    }

    private static void checkHlsText(String text, String base, Map<String, String> headers, Result r) throws Exception {
        if (text == null || !text.trim().startsWith("#EXTM3U")) { r.reason = "bad_manifest"; return; }
        String media = text;
        String mediaBase = base;
        if (text.contains("#EXT-X-STREAM-INF")) {
            String firstVariant = null;
            String[] lines = text.split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
                r.variants++;
                for (int j = i + 1; j < lines.length; j++) {
                    String l = lines[j].trim();
                    if (l.length() == 0 || l.startsWith("#")) continue;
                    if (firstVariant == null) firstVariant = absolute(base, l);
                    break;
                }
            }
            if (firstVariant == null) { r.reason = "no_variant"; return; }
            Map<String, String> vh = headers;
            if (firstVariant.indexOf('|') >= 0) { vh = CgStreams.headersOf(firstVariant); firstVariant = CgStreams.cleanUrl(firstVariant); }
            media = getText(firstVariant, vh);
            mediaBase = firstVariant;
            headers = vh;
            if (media == null || !media.trim().startsWith("#EXTM3U")) { r.reason = "bad_variant"; return; }
        }
        String firstSegment = null;
        String[] lines = media.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("#EXTINF")) continue;
            r.segments++;
            if (firstSegment == null) {
                for (int j = i + 1; j < lines.length; j++) {
                    String l = lines[j].trim();
                    if (l.length() == 0 || l.startsWith("#")) continue;
                    firstSegment = absolute(mediaBase, l);
                    break;
                }
            }
        }
        if (firstSegment == null || r.segments == 0) { r.reason = "no_segments"; return; }
        int got = firstBytes(CgStreams.cleanUrl(firstSegment), headers);
        r.ok = got > 0;
        if (!r.ok) r.reason = got < 0 ? "segment_http_" + (-got) : "segment_empty";
    }

    // ------------------------------------------------------------------ http

    private static HttpURLConnection open(String url, Map<String, String> headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", CgStreams.UA);
        c.setRequestProperty("Accept", "*/*");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) c.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        return c;
    }

    private static String getText(String url, Map<String, String> headers) {
        HttpURLConnection c = null;
        try {
            c = open(url, headers);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > MAX_TEXT) break;
            }
            return out.toString("UTF-8");
        } catch (Throwable error) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Bytes read from the start of {@code url} (ranged), or the negative HTTP status. */
    private static int firstBytes(String url, Map<String, String> headers) {
        HttpURLConnection c = null;
        try {
            c = open(url, headers);
            c.setRequestProperty("Range", "bytes=0-2047");
            int code = c.getResponseCode();
            if (code != 200 && code != 206) return -Math.max(1, code);
            InputStream in = c.getInputStream();
            byte[] buf = new byte[2048];
            int total = 0, n;
            while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) total += n;
            return total;
        } catch (Throwable error) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String dataText(String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            if (comma < 0) return "";
            String meta = dataUrl.substring(0, comma);
            String body = dataUrl.substring(comma + 1);
            if (meta.contains(";base64")) return new String(Base64.decode(body, Base64.DEFAULT), "UTF-8");
            return java.net.URLDecoder.decode(body, "UTF-8");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String absolute(String base, String rel) {
        if (rel == null) return "";
        String r = rel.trim();
        if (r.startsWith("http://") || r.startsWith("https://")) return r;
        if (base == null || base.length() == 0) return r;
        try {
            return new URL(new URL(CgStreams.cleanUrl(base)), r).toString();
        } catch (Throwable ignored) {
            return r;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Arabic for every reason this class or the resolver gives. */
    static String friendly(String reason) {
        String r = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (r.length() == 0) return "لم يُفحص بعد";
        if (r.equals("no_video")) return "لا يوجد رابط مشاهدة";
        if (r.equals("bad_url")) return "رابط المشاهدة غير صالح";
        if (r.equals("no_stream") || r.contains("timeout") || r.contains("no media")) return "المشغل لم يُخرج فيديو (غير متوفر بعد على VidBolt)";
        if (r.startsWith("http_404") || r.contains("404")) return "الفيديو غير موجود على الخادم (404)";
        if (r.startsWith("segment")) return "الفيديو يبدأ ولا يكمل (أجزاء البث لا تعمل)";
        if (r.equals("bad_manifest") || r.equals("bad_variant") || r.equals("no_variant")) return "ملف البث تالف أو فارغ";
        if (r.equals("no_segments")) return "البث فارغ (بدون أجزاء)";
        if (r.contains("webview")) return "متصفح النظام (WebView) لا يعمل على هذا الهاتف — حدّثه من المتجر";
        if (r.startsWith("http_")) return "الخادم رفض الطلب (" + r.substring(5) + ")";
        if (r.equals("main_thread")) return "خطأ داخلي في الفحص";
        return "تعذر التشغيل (" + reason + ")";
    }
}
