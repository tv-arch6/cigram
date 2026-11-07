package com.Cigram.vid;

import com.google.android.exoplayer2.upstream.DefaultLoadErrorHandlingPolicy;
import com.google.android.exoplayer2.upstream.HttpDataSource;

/**
 * (v10.77) The log of a real failure showed what happens: the segment server answers 404 for a part of the
 * film it has not prepared yet, and Cloudflare then keeps that 404 for four hours, so every retry got the
 * cached refusal. Now each refused address is asked again with a new query (a new cache key) every second
 * for ~100 s, so the retry reaches the server and succeeds as soon as the part is ready.
 *
 * Patience for streams that came from an embed site. Their segment servers (for example the
 * "img.xxx" host of a playlist) often answer 404 / 403 for a piece that is not ready yet or for the first
 * request after a seek, and serve it a moment later. ExoPlayer's default gives up after about 3 tries and
 * shows "ERROR_CODE_IO_BAD_HTTP_STATUS"; for those hosts only, this keeps trying for ~25 seconds.
 * Every other stream behaves exactly as before.
 */
final class CigramLoadPolicy extends DefaultLoadErrorHandlingPolicy {

    /** About 100 s: the server may need that long to prepare a part of the film nobody has asked for yet. */
    private static final int EMBED_RETRIES = 100;

    @Override public long getRetryDelayMsFor(LoadErrorInfo info) {
        try {
            if (info.exception instanceof HttpDataSource.InvalidResponseCodeException) {
                HttpDataSource.InvalidResponseCodeException h = (HttpDataSource.InvalidResponseCodeException) info.exception;
                int code = h.responseCode;
                String host = h.dataSpec == null || h.dataSpec.uri == null ? null : h.dataSpec.uri.getHost();
                if ((code == 404 || code == 403 || code == 410 || code == 416)
                        && CigramEmbedResolver.isSeenHost(host) && info.errorCount <= EMBED_RETRIES) {
                    if (h.dataSpec != null && h.dataSpec.uri != null) {
                        // the refusal is cached by the CDN: ask again under a new address (see CigramHttpProfile)
                        CigramHttpProfile.markRefused(h.dataSpec.uri.toString());
                        if (info.errorCount == 1) CigramEmbedResolver.diagnose(h.dataSpec.uri.toString(), code);   // find out WHY, once
                    }
                    return info.errorCount < 6 ? 400L : 1000L;
                }
            }
        } catch (Throwable ignored) { }
        return super.getRetryDelayMsFor(info);
    }

    @Override public int getMinimumLoadableRetryCount(int dataType) {
        return CigramEmbedResolver.hasSeenHosts() ? EMBED_RETRIES : super.getMinimumLoadableRetryCount(dataType);
    }
}
