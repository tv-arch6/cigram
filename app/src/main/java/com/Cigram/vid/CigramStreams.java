package com.Cigram.vid;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.util.MimeTypes;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One place that turns any channel / video link into something ExoPlayer can open.
 *
 * - Type detection: HLS (.m3u8), DASH (.mpd), SmoothStreaming (.ism/Manifest), RTSP (rtsp://)
 *   and progressive files (mp4, mkv, ts, webm, mp3...), from the extension, the path, the query,
 *   or (for links without any hint) the server's Content-Type and first bytes ({@link #sniff}).
 * - Request headers written Kodi/IPTV style after a pipe:
 *   {@code https://host/live.m3u8|User-Agent=VLC&Referer=https://site/}
 * - Redirects between http and https are followed (many live CDNs do this).
 *
 * The HLS, DASH, SmoothStreaming and RTSP parts of ExoPlayer are separate libraries. A type whose
 * library is not in the build is reported by {@link #missingModule(String)} instead of failing
 * with a generic error.
 */
public final class CigramStreams {
    public static final String UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    /** Order tried when a link gives no hint at all and the previous type failed to parse. */
    public static final String[] FALLBACK = {
            MimeTypes.APPLICATION_M3U8, MimeTypes.APPLICATION_MPD, MimeTypes.VIDEO_MP4
    };

    private static final Handler UI = new Handler(Looper.getMainLooper());
    /** Shared by every player built through {@link #sourceFactory(Context)}; headers follow the last link. */
    private static final DefaultHttpDataSource.Factory HTTP = new DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000);
    /** Types learned by sniffing, so a channel is detected once per run. */
    private static final HashMap<String, String> LEARNED = new HashMap<String, String>();

    /** The headers of the stream that is playing now (used to test a failing address the way the player sent it). */
    static volatile Map<String, String> CURRENT_HEADERS = new HashMap<String, String>();

    private CigramStreams() { }

    // ================================================================== link parts

    /** The link without the "|headers" part. */
    public static String cleanUrl(String url) {
        if (url == null) return "";
        String u = url.trim();
        int bar = u.indexOf('|');
        return bar >= 0 ? u.substring(0, bar).trim() : u;
    }

    /** Headers written after a pipe: "|User-Agent=x&Referer=y" (values may be URL-encoded). */
    public static Map<String, String> headersOf(String url) {
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

    // ================================================================== type detection

    /** The stream type the link itself tells, or null when it gives no hint. */
    public static String mimeOf(String url) {
        String full = cleanUrl(url).toLowerCase(Locale.US);
        if (full.length() == 0) return null;
        synchronized (LEARNED) {
            String l = LEARNED.get(full);
            if (l != null) return l;
        }
        if (full.startsWith("rtsp://") || full.startsWith("rtsps://")) return MimeTypes.APPLICATION_RTSP;
        // a master playlist written by the app itself (separate picture + sound playlists joined)
        if (full.startsWith("data:application/vnd.apple.mpegurl")) return MimeTypes.APPLICATION_M3U8;

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
        if (path.endsWith(".mp3")) return MimeTypes.AUDIO_MPEG;
        if (path.endsWith(".aac")) return MimeTypes.AUDIO_AAC;

        // No extension: hints in the query or the path (Xtream, Wowza, Akamai, AWS MediaPackage...)
        if (full.contains("m3u8") || full.contains("/hls/") || full.contains("type=hls")
                || full.contains("output=hls") || full.contains("extension=m3u")) return MimeTypes.APPLICATION_M3U8;
        if (full.contains("/dash/") || full.contains("format=mpd") || full.contains("type=dash")
                || full.contains("output=mpd") || full.contains("manifest.mpd")) return MimeTypes.APPLICATION_MPD;
        if (full.contains("output=ts") || full.contains("extension=ts")) return MimeTypes.VIDEO_MP2T;
        return null;
    }

    /**
     * For links without any hint: asks the server (Content-Type, then the first bytes) on a
     * background thread and answers on the main thread. Answers null when it cannot tell.
     */
    public interface MimeCallback { void onMime(String mime); }

    public static void sniff(final String url, final MimeCallback done) {
        final String known = mimeOf(url);
        if (known != null) { done.onMime(known); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                final String found = sniffNow(url);
                if (found != null) {
                    synchronized (LEARNED) { LEARNED.put(cleanUrl(url).toLowerCase(Locale.US), found); }
                }
                UI.post(new Runnable() { @Override public void run() { done.onMime(found); } });
            }
        }, "cigram-sniff").start();
    }

    private static String sniffNow(String url) {
        String clean = cleanUrl(url);
        if (!clean.startsWith("http")) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(clean).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Range", "bytes=0-2047");
            for (Map.Entry<String, String> e : headersOf(url).entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            int code = c.getResponseCode();
            if (code >= 400) return null;

            String finalUrl = c.getURL() == null ? "" : c.getURL().toString();
            String byFinal = finalUrl.equals(clean) ? null : mimeOf(finalUrl);
            if (byFinal != null) return byFinal;

            String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.US);
            if (type.contains("mpegurl")) return MimeTypes.APPLICATION_M3U8;
            if (type.contains("dash+xml")) return MimeTypes.APPLICATION_MPD;
            if (type.contains("vnd.ms-sstr")) return MimeTypes.APPLICATION_SS;

            byte[] buf = new byte[2048];
            int n = 0;
            InputStream in = c.getInputStream();
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r <= 0) break;
                n += r;
            }
            String head = new String(buf, 0, n, "ISO-8859-1");
            if (head.contains("#EXTM3U") || head.contains("#EXT-X-")) return MimeTypes.APPLICATION_M3U8;
            if (head.contains("<MPD")) return MimeTypes.APPLICATION_MPD;
            if (head.contains("<SmoothStreamingMedia")) return MimeTypes.APPLICATION_SS;
            if (type.startsWith("video/") || type.startsWith("audio/") || type.contains("octet-stream")) return MimeTypes.VIDEO_MP4;
            return null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    // ================================================================== ExoPlayer side

    /** The ExoPlayer library a type needs, when it is not in this build ("" when everything is there). */
    public static String missingModule(String mime) {
        if (MimeTypes.APPLICATION_MPD.equals(mime)
                && !hasClass("com.google.android.exoplayer2.source.dash.DashMediaSource$Factory")) return "exoplayer-dash";
        if (MimeTypes.APPLICATION_M3U8.equals(mime)
                && !hasClass("com.google.android.exoplayer2.source.hls.HlsMediaSource$Factory")) return "exoplayer-hls";
        if (MimeTypes.APPLICATION_SS.equals(mime)
                && !hasClass("com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource$Factory")) return "exoplayer-smoothstreaming";
        if (MimeTypes.APPLICATION_RTSP.equals(mime)
                && !hasClass("com.google.android.exoplayer2.source.rtsp.RtspMediaSource$Factory")) return "exoplayer-rtsp";
        return "";
    }

    private static boolean hasClass(String name) {
        try { Class.forName(name); return true; } catch (Throwable t) { return false; }
    }

    /** Media source factory for ExoPlayer.Builder: browser user agent, http<->https redirects, link headers. */
    public static DefaultMediaSourceFactory sourceFactory(Context c) {
        CigramCookieBridge.install();
        // v10.79: pieces of embed streams go through CigramSegmentAccel (parallel + look-ahead); all else as before
        return new DefaultMediaSourceFactory(new DefaultDataSource.Factory(c.getApplicationContext(),
                new CigramSegmentAccel.Factory(
                        new com.google.android.exoplayer2.upstream.ResolvingDataSource.Factory(HTTP, new CigramHttpProfile()))))
                .setLoadErrorHandlingPolicy(new CigramLoadPolicy());
    }

    /** Sets the clean link, its type (when known) and its headers on the item about to be played. */
    public static MediaItem.Builder apply(MediaItem.Builder b, String url) {
        return apply(b, url, mimeOf(url));
    }

    public static MediaItem.Builder apply(MediaItem.Builder b, String url, String mime) {
        HashMap<String, String> headers = new HashMap<String, String>(headersOf(url));
        if (!headers.containsKey("User-Agent")) headers.put("User-Agent", UA);
        HTTP.setDefaultRequestProperties(headers);
        CURRENT_HEADERS = new HashMap<String, String>(headers);
        b.setUri(Uri.parse(cleanUrl(url)));
        b.setMimeType(mime);
        return b;
    }

    /** True for errors that mean "this is not the type we told the player": try another type. */
    public static boolean isTypeError(PlaybackException e) {
        if (e == null) return false;
        int c = e.errorCode;
        return c == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || c == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || c == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                || c == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
    }

    /** True when the stream is DRM protected (Widevine / PlayReady) and needs the owner's license. */
    public static boolean isDrmError(PlaybackException e) {
        return e != null && e.errorCode >= 6000 && e.errorCode < 7000;
    }

    /** User-facing text for a type whose library is missing. */
    public static String missingText(String module) {
        return "هذا النوع من الروابط يحتاج مكتبة " + module + " (أضفها من Local Library بنفس إصدار ExoPlayer 2.19.1)";
    }
}
