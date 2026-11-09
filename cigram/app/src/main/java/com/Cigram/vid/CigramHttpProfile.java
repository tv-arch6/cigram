package com.Cigram.vid;

import android.net.Uri;

import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.ResolvingDataSource;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-host request headers for streams that came from an embed site, decided by what the server
 * ACCEPTS (tested on the failing address itself, see {@link CigramLoadPolicy}) instead of guessed.
 *
 * Variant 0 (default) sends the headers the stream was found with. When a segment host rejects them
 * (404 / 403 after a seek) and another set of headers is accepted, that variant is remembered for the
 * host and used for every following request, including the retry of the failed one.
 */
final class CigramHttpProfile implements ResolvingDataSource.Resolver {

    static final int DEFAULT = 0, SAME_ORIGIN = 1, WITH_RANGE = 2, NO_REFERER = 3;
    static final int VARIANTS = 4;

    private static final HashMap<String, Integer> VARIANT = new HashMap<String, Integer>();

    static synchronized int variantOf(String host) {
        Integer v = host == null ? null : VARIANT.get(host);
        return v == null ? DEFAULT : v.intValue();
    }

    static synchronized void setVariant(String host, int variant) {
        if (host != null) VARIANT.put(host, Integer.valueOf(variant));
    }

    /** Applies a variant to a header map ({@code host} = the server the request goes to). */
    static void applyVariant(Map<String, String> h, int variant, String host) {
        switch (variant) {
            case SAME_ORIGIN:
                h.put("Referer", "https://" + host + "/");
                h.put("Origin", "https://" + host);
                break;
            case WITH_RANGE:
                if (!h.containsKey("Range")) h.put("Range", "bytes=0-");
                break;
            case NO_REFERER:
                h.put("Referer", "");
                h.put("Origin", "");
                break;
            default:
                break;
        }
    }

    // ---- addresses the server refused. Cloudflare keeps a "404 Not Found" for HOURS (max-age=14400), so asking
    // for the same address again only returns the same cached refusal even after the server made the piece.
    // Such an address is asked again with a different query (?cgb=...): a new cache key, so the request reaches
    // the server itself.
    private static final HashMap<String, Long> REFUSED = new HashMap<String, Long>();
    private static final long REFUSED_MS = 5L * 60L * 1000L;

    private static String withoutQuery(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    static synchronized void markRefused(String url) {
        if (url == null) return;
        if (REFUSED.size() > 600) REFUSED.clear();
        REFUSED.put(withoutQuery(url), Long.valueOf(System.currentTimeMillis()));
    }

    static synchronized boolean isRefused(String url) {
        Long t = REFUSED.get(withoutQuery(url));
        if (t == null) return false;
        if (System.currentTimeMillis() - t.longValue() > REFUSED_MS) {
            REFUSED.remove(withoutQuery(url));
            return false;
        }
        return true;
    }

    @Override public DataSpec resolveDataSpec(DataSpec dataSpec) throws IOException {
        try {
            Uri uri = dataSpec.uri;
            String host = uri == null ? null : uri.getHost();
            if (host == null || !CigramEmbedResolver.isSeenHost(host)) return dataSpec;
            DataSpec spec = dataSpec;
            int v = variantOf(host);
            if (v != DEFAULT) {
                HashMap<String, String> h = new HashMap<String, String>(dataSpec.httpRequestHeaders);
                applyVariant(h, v, host);
                spec = spec.withRequestHeaders(h);
            }
            if (isRefused(uri.toString())) {
                Uri fresh = uri.buildUpon().appendQueryParameter("cgb", Long.toString(System.nanoTime(), 36)).build();
                spec = spec.buildUpon().setUri(fresh).build();
            }
            return spec;
        } catch (Throwable t) {
            return dataSpec;
        }
    }
}
