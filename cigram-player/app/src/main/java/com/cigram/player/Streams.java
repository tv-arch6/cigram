package com.cigram.player;

import com.google.android.exoplayer2.util.MimeTypes;

import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Link helpers (same conventions as the Cigram app's CigramStreams):
 *  - Kodi/IPTV style headers after a pipe:  https://host/movie.m3u8|User-Agent=VLC&amp;Referer=https://site/
 *  - stream type from the extension / query hints.
 */
final class Streams {

    static final String UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    /** Tried in order when a link gives no hint (or the hinted type fails to parse). */
    static final String[] FALLBACK = {MimeTypes.APPLICATION_M3U8, MimeTypes.APPLICATION_MPD, MimeTypes.VIDEO_MP4};

    private Streams() { }

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

    /**
     * Only these schemes are ever handed to the player (the Activity is exported: input is untrusted).
     * content:// = a video picked through the system file picker / opened from a file manager (read grant from the system).
     * file:// is deliberately NOT accepted.
     */
    static boolean isAllowedScheme(String cleanUrl) {
        String u = cleanUrl == null ? "" : cleanUrl.toLowerCase(Locale.US);
        return u.startsWith("https://") || u.startsWith("http://") || u.startsWith("rtsp://")
                || u.startsWith("rtsps://") || u.startsWith("content://");
    }

    /** Remote (network) link, as opposed to a local content:// file. */
    static boolean isRemote(String cleanUrl) {
        return cleanUrl != null && !cleanUrl.toLowerCase(Locale.US).startsWith("content://");
    }

    /** The stream type the link itself tells, or null when it gives no hint. */
    static String mimeOf(String url) {
        String full = cleanUrl(url).toLowerCase(Locale.US);
        if (full.length() == 0) return null;
        if (full.startsWith("rtsp://") || full.startsWith("rtsps://")) return MimeTypes.APPLICATION_RTSP;

        String path = full;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);

        if (path.endsWith(".m3u8") || path.endsWith(".m3u") || path.contains(".m3u8/")) return MimeTypes.APPLICATION_M3U8;
        if (path.endsWith(".mpd") || path.contains(".mpd/")) return MimeTypes.APPLICATION_MPD;
        if (path.endsWith(".ism") || path.endsWith(".isml") || path.contains(".ism/") || path.contains(".isml/")) {
            if (full.contains("format=m3u8")) return MimeTypes.APPLICATION_M3U8;
            if (full.contains("format=mpd")) return MimeTypes.APPLICATION_MPD;
            return MimeTypes.APPLICATION_SS;
        }
        if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov")) return MimeTypes.VIDEO_MP4;
        if (path.endsWith(".mkv")) return MimeTypes.VIDEO_MATROSKA;
        if (path.endsWith(".webm")) return MimeTypes.VIDEO_WEBM;
        if (path.endsWith(".ts") || path.endsWith(".mts") || path.endsWith(".m2ts")) return MimeTypes.VIDEO_MP2T;
        if (path.endsWith(".flv")) return MimeTypes.VIDEO_FLV;

        if (full.contains("m3u8") || full.contains("/hls/") || full.contains("type=hls")
                || full.contains("output=hls") || full.contains("extension=m3u")) return MimeTypes.APPLICATION_M3U8;
        if (full.contains("/dash/") || full.contains("format=mpd") || full.contains("type=dash")
                || full.contains("output=mpd") || full.contains("manifest.mpd")) return MimeTypes.APPLICATION_MPD;
        return null;
    }

    /** Subtitle mime from the file name; SubRip when unknown. */
    static String subtitleMime(String url) {
        String u = cleanUrl(url).toLowerCase(Locale.US);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        if (u.endsWith(".vtt")) return MimeTypes.TEXT_VTT;
        if (u.endsWith(".ass") || u.endsWith(".ssa")) return MimeTypes.TEXT_SSA;
        if (u.endsWith(".ttml") || u.endsWith(".xml") || u.endsWith(".dfxp")) return MimeTypes.APPLICATION_TTML;
        return MimeTypes.APPLICATION_SUBRIP;
    }
}
