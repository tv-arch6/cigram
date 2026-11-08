package com.Cigram.vid;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;

import java.util.Locale;

/**
 * One place that decides WHY a title does not play, so every screen (phone player, TV details, live channels) tells
 * the user the real reason in Arabic. Before 2026 the player said «انقطع الاتصال بالإنترنت» whenever the phone
 * looked offline, and «تعذر تشغيل الفيديو» for everything else, even for a stream the device simply cannot decode.
 *
 * Rules:
 * <ul>
 *   <li>"no internet" is only said for a NETWORK error while the device really has no network;</li>
 *   <li>a decoder / container error is "unsupported format" whatever the network state;</li>
 *   <li>HTTP 401/403 on a stream = the (signed) source expired or is refused; 404/410 = the file is gone;
 *       5xx = the video server failed; an HTML answer instead of media = not a video link;</li>
 *   <li>resolver codes (timeout, webview ..., no stream) map to timeout / WebView / missing stream.</li>
 * </ul>
 */
public final class CigramPlaybackError {

    public static final int UNKNOWN = 0, INVALID_URL = 1, UNSUPPORTED_FORMAT = 2, MISSING_STREAM = 3,
            EXPIRED_SOURCE = 4, WEBVIEW = 5, TIMEOUT = 6, OFFLINE = 7, SERVER = 8, UNREACHABLE = 9;

    private CigramPlaybackError() { }

    /** Arabic sentence for a category (no technical code). */
    public static String message(int kind) {
        switch (kind) {
            case INVALID_URL: return "رابط الفيديو غير صالح أو لا يشير إلى ملف فيديو.";
            case UNSUPPORTED_FORMAT: return "صيغة هذا الفيديو غير مدعومة على هذا الجهاز.";
            case MISSING_STREAM: return "ملف الفيديو غير موجود في المصدر حالياً.";
            case EXPIRED_SOURCE: return "انتهت صلاحية رابط المصدر أو رفض الخادم الوصول إليه.";
            case WEBVIEW: {
                String p = CigramEmbedResolver.webViewProblem();
                return p.length() > 0 ? p + "، وهذا المصدر يحتاجه لاستخراج الفيديو."
                        : "تعذّر تشغيل محرك المتصفح (WebView) اللازم لاستخراج الفيديو.";
            }
            case TIMEOUT: return "تأخر خادم الفيديو في الرد. حاول مرة أخرى بعد قليل.";
            case OFFLINE: return "لا يوجد اتصال بالإنترنت على هذا الجهاز.";
            case SERVER: return "خادم الفيديو يواجه مشكلة حالياً.";
            case UNREACHABLE: return "تعذّر الوصول إلى خادم الفيديو.";
            default: return "تعذّر تشغيل الفيديو.";
        }
    }

    /** True when the device has a network that claims internet access (not a proof the server is reachable). */
    public static boolean hasNetwork(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;   // cannot tell: never blame the connection
            if (Build.VERSION.SDK_INT >= 23) {
                Network n = cm.getActiveNetwork();
                if (n == null) return false;
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Checks a link before any player is opened. Returns UNKNOWN when it may be playable. Only links that can never
     * play are refused: empty / "null" values, no scheme at all, script-like schemes, or a network scheme without a
     * host. Every scheme the stream layer understands (http(s), rtsp(s), rtmp(s), udp, srt, data: playlists,
     * content:, file:) passes.
     */
    public static int validateUrl(String raw) {
        if (raw == null) return INVALID_URL;
        String url = raw.trim();
        String low = url.toLowerCase(Locale.ROOT);
        if (url.length() < 6 || low.equals("null") || low.equals("undefined") || low.equals("none")) return INVALID_URL;
        try {
            Uri u = Uri.parse(url);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (scheme.length() == 0) return INVALID_URL;
            if (scheme.equals("javascript") || scheme.equals("about") || scheme.equals("mailto") || scheme.equals("tel")) {
                return INVALID_URL;
            }
            boolean network = scheme.equals("http") || scheme.equals("https") || scheme.startsWith("rtsp")
                    || scheme.startsWith("rtmp") || scheme.equals("udp") || scheme.equals("srt");
            if (network && (u.getHost() == null || u.getHost().length() == 0)) return INVALID_URL;
        } catch (Throwable t) {
            return INVALID_URL;
        }
        return UNKNOWN;
    }

    /** Category of a stream-resolver failure code (CigramEmbedResolver: "timeout", "webview ...", "no stream" ...). */
    public static int fromResolverReason(Context c, String reason) {
        String r = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (CigramEmbedResolver.webViewProblem().length() > 0 || r.startsWith("webview")) return WEBVIEW;
        if (r.contains("timeout") || r.contains("timed out")) return c != null && !hasNetwork(c) ? OFFLINE : TIMEOUT;
        if (r.contains("unknownhost") || r.contains("resolve host")) return c != null && !hasNetwork(c) ? OFFLINE : UNREACHABLE;
        if (r.contains("403") || r.contains("401") || r.contains("expired")) return EXPIRED_SOURCE;
        if (r.contains("404") || r.contains("410") || r.contains("no stream") || r.contains("not found")) return MISSING_STREAM;
        if (r.matches(".*\\b5\\d\\d\\b.*")) return SERVER;
        return MISSING_STREAM;   // the resolver answered but found nothing playable
    }

    /** Category of a player error (ExoPlayer PlaybackException or any Throwable). */
    public static int classify(Context c, Object error) {
        Throwable t = error instanceof Throwable ? (Throwable) error : null;
        int code = -1;
        int http = -1;
        try {
            if (error instanceof com.google.android.exoplayer2.PlaybackException) {
                code = ((com.google.android.exoplayer2.PlaybackException) error).errorCode;
            }
            for (Throwable x = t; x != null; x = x.getCause()) {
                if (x instanceof com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException) {
                    http = ((com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException) x).responseCode;
                    break;
                }
                if (x.getCause() == x) break;
            }
        } catch (Throwable ignored) { }

        // decoding / container problems are the device or the file, never the connection
        if (code >= 3000 && code < 5000) return UNSUPPORTED_FORMAT;
        if (code >= 6000 && code < 7000) return UNSUPPORTED_FORMAT;   // DRM the device cannot play
        if (http == 401 || http == 403) return EXPIRED_SOURCE;
        if (http == 404 || http == 410) return MISSING_STREAM;
        if (http >= 500 && http < 600) return SERVER;
        if (code == 2003) return INVALID_URL;                // a web page came back instead of media
        if (code == 2005) return MISSING_STREAM;             // file not found
        if (code == 2007) return UNSUPPORTED_FORMAT;         // cleartext refused by the system
        boolean online = c == null || hasNetwork(c);
        if (code == 2002 || code == 1003) return online ? TIMEOUT : OFFLINE;
        if (code == 2001) {
            if (!online) return OFFLINE;
            return hasCause(t, "UnknownHostException") ? UNREACHABLE : UNREACHABLE;
        }
        String all = (String.valueOf(error) + " " + (t != null && t.getCause() != null ? t.getCause() : "")).toLowerCase(Locale.ROOT);
        if (all.contains("timeout") || all.contains("timed out")) return online ? TIMEOUT : OFFLINE;
        if (all.contains("unknownhost") || all.contains("unable to resolve host")) return online ? UNREACHABLE : OFFLINE;
        if (all.contains("unrecognizedinputformat") || all.contains("mediacodec") || all.contains("decoder")) return UNSUPPORTED_FORMAT;
        if (code >= 2000 && code < 3000) return online ? UNREACHABLE : OFFLINE;
        return UNKNOWN;
    }

    private static boolean hasCause(Throwable t, String simpleName) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x.getClass().getSimpleName().equals(simpleName)) return true;
            if (x.getCause() == x) break;
        }
        return false;
    }

    /** Full sentence for the error panel: the reason in Arabic, then the technical code in brackets. */
    public static String describe(Context c, Object error) {
        int kind = classify(c, error);
        StringBuilder sb = new StringBuilder(message(kind));
        try {
            if (error instanceof com.google.android.exoplayer2.PlaybackException) {
                com.google.android.exoplayer2.PlaybackException pe = (com.google.android.exoplayer2.PlaybackException) error;
                sb.append("\n(").append(pe.getErrorCodeName());
                Throwable x = pe.getCause();
                if (x instanceof com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException) {
                    com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException h =
                            (com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException) x;
                    sb.append(" • HTTP ").append(h.responseCode);
                    try { sb.append(" • ").append(h.dataSpec.uri.getHost()); } catch (Throwable ignored) { }
                }
                sb.append(")");
            }
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    /** True for the categories where trying the source again (re-resolving, another server) can help. */
    public static boolean retryable(int kind) {
        return kind == EXPIRED_SOURCE || kind == TIMEOUT || kind == SERVER || kind == UNREACHABLE || kind == UNKNOWN;
    }
}
