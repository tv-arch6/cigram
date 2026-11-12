package com.my.newproject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cigram Live Health — is a live channel REALLY playable right now?
 *
 * The old link check asked for the first 2 KB of the address: a playlist that still answers while its video segments
 * are gone (the usual way an IPTV source dies) passed as "ok". This check walks the stream the way a player does:
 *
 * <pre>
 *   address → (master playlist → first variant playlist) → newest media segment → first bytes of that segment
 * </pre>
 * and reports one of: UP (plays), SLOW (plays, but the first answer took more than 3 s), DOWN (with the precise
 * reason: time-out, 403 refused, 404 gone, playlist without segments, segment missing, not a video …).
 * A non-HLS address (TS / MP4 / DASH) is checked for real media bytes. Nothing is changed on the server by a check.
 *
 * The parsing half is pure Java and unit-tested; the network half uses plain HttpURLConnection.
 */
final class LiveHealth {

    private LiveHealth() { }

    static final int UP = 0, SLOW = 1, DOWN = 2;
    static final long SLOW_MS = 3000;

    static final class Report {
        int state = DOWN;
        /** Machine reason: ok, slow, timeout, refused, gone, server_error, http_error, not_video, empty_playlist, segment_missing, bad_url, unreachable. */
        String reason = "unreachable";
        int http;
        /** Time to the first useful answer. */
        long ms;
        /** playlist | master | segment | file */
        String depth = "";
        /** Number of segments the media playlist listed (HLS). */
        int segments;
        /** Variants of a master playlist. */
        int variants;

        String label() {
            switch (state) {
                case UP: return "تعمل";
                case SLOW: return "بطيئة";
                default: return "متوقفة";
            }
        }

        String detail() {
            String r;
            if ("ok".equals(reason)) r = "البث يعمل";
            else if ("slow".equals(reason)) r = "يعمل لكن الاستجابة بطيئة";
            else if ("timeout".equals(reason)) r = "انتهت المهلة دون رد";
            else if ("refused".equals(reason)) r = "الخادم يرفض الطلب (" + http + ") — حظر جغرافي أو رابط منتهٍ";
            else if ("gone".equals(reason)) r = "الرابط غير موجود (" + http + ")";
            else if ("server_error".equals(reason)) r = "خطأ في خادم البث (" + http + ")";
            else if ("http_error".equals(reason)) r = "رد غير متوقع (" + http + ")";
            else if ("not_video".equals(reason)) r = "الرابط يعيد صفحة وليس بثاً";
            else if ("empty_playlist".equals(reason)) r = "قائمة التشغيل بلا مقاطع";
            else if ("segment_missing".equals(reason)) r = "القائمة تعمل لكن مقاطع الفيديو مفقودة (" + http + ")";
            else if ("bad_url".equals(reason)) r = "رابط غير صالح";
            else r = "تعذر الوصول إلى الخادم";
            return r + (ms > 0 ? " · " + ms + " مللي ث" : "") + (variants > 1 ? " · " + variants + " جودات" : "");
        }
    }

    // ------------------------------------------------------------------ playlist parsing (pure)

    static boolean isPlaylist(String text) {
        return text != null && text.trim().startsWith("#EXTM3U");
    }

    static boolean isMaster(String playlist) {
        return playlist.contains("#EXT-X-STREAM-INF");
    }

    /** The URIs of a playlist (variant playlists of a master, or media segments), in order. */
    static List<String> uris(String playlist) {
        List<String> out = new ArrayList<String>();
        String[] lines = playlist.replace("\r", "").split("\n");
        for (String l : lines) {
            String t = l.trim();
            if (t.length() == 0 || t.startsWith("#")) continue;
            out.add(t);
        }
        return out;
    }

    /** Resolves a playlist line against the playlist's own address. */
    static String resolve(String base, String ref) {
        try {
            String header = "";
            int bar = base.indexOf('|');                    // the app's "url|Header=value" form: headers are kept apart
            if (bar > 0) { header = base.substring(bar); base = base.substring(0, bar); }
            return new URL(new URL(base), ref).toString() + header;
        } catch (Exception e) {
            return "";
        }
    }

    static Report classifyHttp(int code) {
        Report r = new Report();
        r.http = code;
        if (code == 401 || code == 403) r.reason = "refused";
        else if (code == 404 || code == 410) r.reason = "gone";
        else if (code >= 500) r.reason = "server_error";
        else if (code < 200 || code >= 300) r.reason = "http_error";
        else r.reason = "ok";
        return r;
    }

    /** MPEG-TS sync byte, fMP4 box, AAC ADTS, ID3: what a real media segment starts with. */
    static boolean looksLikeMedia(byte[] b, int n, String contentType) {
        if (n <= 0) return false;
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("text/html") || ct.contains("application/json") || ct.contains("text/plain") && n > 0 && b[0] == '<') return false;
        if ((b[0] & 0xFF) == 0x47) return true;                                              // MPEG-TS
        if (n >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return true; // MP4 / fMP4
        if (n >= 8 && b[4] == 'm' && b[5] == 'o' && b[6] == 'o' && (b[7] == 'f' || b[7] == 'v')) return true;
        if (n >= 8 && b[4] == 's' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return true;
        if (n >= 3 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') return true;                // packed audio
        if (n >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xF0) == 0xF0) return true;            // ADTS
        if (n >= 4 && (b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45) return true;            // WebM / Matroska
        if (b[0] == '<' || b[0] == '{') return false;
        return ct.startsWith("video/") || ct.startsWith("audio/") || ct.contains("octet-stream") || ct.contains("mp2t");
    }

    // ------------------------------------------------------------------ network

    private static final class Got {
        int code;
        byte[] head = new byte[0];
        int n;
        String type = "";
        long ms;
        String error = "";
    }

    private static Got get(String address, int maxBytes) {
        Got g = new Got();
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            String url = address;
            String referer = null, agent = "CigramStudio/3 (live-health)";
            int bar = address.indexOf('|');
            if (bar > 0) {
                url = address.substring(0, bar);
                for (String kv : address.substring(bar + 1).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    String k = kv.substring(0, eq).trim().toLowerCase(Locale.ROOT), v = kv.substring(eq + 1);
                    if (k.equals("referer")) referer = v; else if (k.equals("user-agent")) agent = v;
                }
            }
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(9000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", agent);
            if (referer != null) c.setRequestProperty("Referer", referer);
            if (maxBytes <= 4096) c.setRequestProperty("Range", "bytes=0-" + (maxBytes - 1));
            g.code = c.getResponseCode();
            g.type = c.getContentType() == null ? "" : c.getContentType();
            if (g.code >= 200 && g.code < 300) {
                InputStream in = c.getInputStream();
                byte[] buf = new byte[maxBytes];
                int total = 0, n;
                while (total < maxBytes && (n = in.read(buf, total, maxBytes - total)) > 0) total += n;
                try { in.close(); } catch (Exception ignored) { }
                g.head = buf;
                g.n = total;
            }
        } catch (SocketTimeoutException e) {
            g.error = "timeout";
        } catch (Exception e) {
            g.error = "unreachable";
        } finally {
            if (c != null) c.disconnect();
        }
        g.ms = System.currentTimeMillis() - t0;
        return g;
    }

    private static Report fail(Got g, String depth) {
        Report r = g.error.length() > 0 ? new Report() : classifyHttp(g.code);
        if (g.error.length() > 0) r.reason = g.error;
        r.state = DOWN;
        r.ms = g.ms;
        r.depth = depth;
        return r;
    }

    /** The whole walk for one channel address. Blocking: call from a worker thread. */
    static Report check(String address) {
        Report bad = new Report();
        if (address == null || !(address.startsWith("http://") || address.startsWith("https://"))) { bad.reason = "bad_url"; return bad; }
        Got first = get(address, 96 * 1024);
        if (first.error.length() > 0 || first.code < 200 || first.code >= 300) return fail(first, "playlist");
        String text = "";
        try { text = new String(first.head, 0, first.n, "UTF-8"); } catch (Exception ignored) { }
        Report r = new Report();
        r.http = first.code;
        r.ms = first.ms;
        if (!isPlaylist(text)) {
            // a direct stream / file
            r.depth = "file";
            if (looksLikeMedia(first.head, first.n, first.type)) { r.reason = first.ms > SLOW_MS ? "slow" : "ok"; r.state = first.ms > SLOW_MS ? SLOW : UP; }
            else r.reason = "not_video";
            return r;
        }
        String mediaAddress = address;
        String media = text;
        if (isMaster(text)) {
            List<String> variants = uris(text);
            r.variants = variants.size();
            if (variants.isEmpty()) { r.reason = "empty_playlist"; r.depth = "master"; return r; }
            mediaAddress = resolve(address, variants.get(0));
            Got v = get(mediaAddress, 96 * 1024);
            if (v.error.length() > 0 || v.code < 200 || v.code >= 300) { Report f = fail(v, "master"); f.variants = r.variants; f.ms = r.ms; return f; }
            try { media = new String(v.head, 0, v.n, "UTF-8"); } catch (Exception ignored) { media = ""; }
            if (!isPlaylist(media)) { r.reason = "not_video"; r.depth = "master"; return r; }
        }
        List<String> segments = uris(media);
        r.segments = segments.size();
        if (segments.isEmpty()) { r.reason = "empty_playlist"; r.depth = "playlist"; return r; }
        // the NEWEST segment: an abandoned stream keeps listing old ones that are already deleted
        Got seg = get(resolve(mediaAddress, segments.get(segments.size() - 1)), 2048);
        r.depth = "segment";
        if (seg.error.length() > 0) { r.reason = seg.error; return r; }
        if (seg.code < 200 || seg.code >= 300) { r.reason = "segment_missing"; r.http = seg.code; return r; }
        if (!looksLikeMedia(seg.head, seg.n, seg.type)) { r.reason = "not_video"; return r; }
        boolean slow = first.ms > SLOW_MS || seg.ms > SLOW_MS * 2;
        r.reason = slow ? "slow" : "ok";
        r.state = slow ? SLOW : UP;
        return r;
    }
}
