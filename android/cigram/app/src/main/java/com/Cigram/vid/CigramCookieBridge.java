package com.Cigram.vid;

import android.webkit.CookieManager;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Lets ExoPlayer's HTTP requests carry the cookies the embed site gave to the WebView that found the
 * stream. The playlist, the audio track and every segment of such streams are often tied to a cookie:
 * without it the CDN answers 403 / 404 (ERROR_CODE_IO_BAD_HTTP_STATUS) after the first part, or the
 * separate audio track never loads (video without sound).
 *
 * Only hosts the embed resolver saw while it was finding the stream are served; everything else (the
 * app's own API, live channels, ...) behaves exactly as before. Cookies are never written back.
 */
final class CigramCookieBridge extends CookieHandler {

    private static boolean installed;

    private CigramCookieBridge() { }

    /** Safe to call any number of times. Leaves a handler the app (or a library) already installed alone. */
    static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            if (CookieHandler.getDefault() == null) CookieHandler.setDefault(new CigramCookieBridge());
        } catch (Throwable ignored) { }
    }

    @Override public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) throws IOException {
        try {
            if (uri == null || !CigramEmbedResolver.isSeenHost(uri.getHost())) return Collections.emptyMap();
            String cookie = CookieManager.getInstance().getCookie(uri.toString());
            if (cookie == null || cookie.length() == 0) return Collections.emptyMap();
            return Collections.singletonMap("Cookie", Collections.singletonList(cookie));
        } catch (Throwable t) {
            return Collections.emptyMap();
        }
    }

    @Override public void put(URI uri, Map<String, List<String>> responseHeaders) throws IOException {
        // the WebView's jar stays the only writer
    }
}
