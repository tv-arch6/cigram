package com.my.newproject;

import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 19.6 — the link helpers of the main app's CigramStreams, without ExoPlayer (the admin only VERIFIES streams).
 * Kodi-style headers after a pipe: {@code https://host/live.m3u8|Referer=https://vidbolt.pro/&User-Agent=...}
 */
final class CgStreams {
    static final String UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    static final String M3U8 = "application/x-mpegURL";
    static final String MPD = "application/dash+xml";
    static final String MP4 = "video/mp4";
    static final String OTHER = "video/other";

    static volatile Map<String, String> CURRENT_HEADERS = new HashMap<String, String>();

    private CgStreams() { }

    static String cleanUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        int bar = u.indexOf('|');
        return bar >= 0 ? u.substring(0, bar).trim() : u;
    }

    static Map<String, String> headersOf(String url) {
        HashMap<String, String> out = new HashMap<String, String>();
        if (url == null) return out;
        int bar = url.indexOf('|');
        if (bar < 0) return out;
        for (String part : url.substring(bar + 1).split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            try { v = URLDecoder.decode(v, "UTF-8"); } catch (Throwable ignored) { }
            if (k.length() > 0) out.put(k, v);
        }
        return out;
    }

    /** The stream type the link itself tells (HLS / DASH / file), or null for a web page / no hint. */
    static String mimeOf(String url) {
        String full = cleanUrl(url).toLowerCase(Locale.US);
        if (full.length() == 0) return null;
        if (full.startsWith("data:application/vnd.apple.mpegurl")) return M3U8;
        String path = full;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);
        if (path.endsWith(".m3u8") || path.endsWith(".m3u") || path.contains(".m3u8/")) return M3U8;
        if (path.endsWith(".mpd") || path.contains(".mpd/")) return MPD;
        if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov")) return MP4;
        if (path.endsWith(".mkv") || path.endsWith(".webm") || path.endsWith(".ts")) return OTHER;
        if (full.contains("m3u8") || full.contains("/hls/") || full.contains("type=hls")) return M3U8;
        if (full.contains("/dash/") || full.contains("format=mpd") || full.contains("manifest.mpd")) return MPD;
        return null;
    }
}
