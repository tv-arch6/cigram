package com.Cigram.vid;

import com.google.android.exoplayer2.ExoPlayer;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v10.81: warms up the seek bar of an embed stream WITHOUT getting in the way.
 *
 * (v10.80 held the video behind a "preparing" cover until the server had answered for pieces spread over
 * the film. That page is gone: the film now starts at once.) In the background, and only for a numbered-piece
 * HLS stream of an embed host, the server is asked for a few pieces spread over the running time (new cache
 * key each time). A server that prepares pieces only on request is thereby woken for those places while the
 * user is already watching, so the first use of the seek bar finds them ready. Nothing is shown, nothing
 * waits, and the playback is never paused.
 */
final class CigramSeekGate {

    /** How long the background warm-up keeps asking for a piece that is not there yet. */
    private static final long WARM_MS = 40000L;
    /** Pieces warmed: spread over the film. */
    private static final float[] SAMPLES = {0.12f, 0.30f, 0.48f, 0.66f, 0.82f, 0.94f};

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, new java.util.concurrent.ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "cigram-seek-warm");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }
    });

    private CigramSeekGate() { }

    private static ArrayList<String> piecesOf(ExoPlayer exo) {
        try {
            Object manifest = exo.getCurrentManifest();
            if (!(manifest instanceof com.google.android.exoplayer2.source.hls.HlsManifest)) return null;
            com.google.android.exoplayer2.source.hls.playlist.HlsMediaPlaylist mp =
                    ((com.google.android.exoplayer2.source.hls.HlsManifest) manifest).mediaPlaylist;
            if (mp.segments.size() < 8 || !mp.hasEndTag) return null;
            ArrayList<String> urls = new ArrayList<String>(mp.segments.size());
            for (int i = 0; i < mp.segments.size(); i++) {
                urls.add(com.google.android.exoplayer2.util.UriUtil.resolve(mp.baseUri, mp.segments.get(i).url));
            }
            if (!CigramEmbedResolver.isSeenHost(android.net.Uri.parse(urls.get(0)).getHost())) return null;
            return urls;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Starts the warm-up (main thread, right after the player became ready). Never blocks, never shows anything. */
    static void warm(final ExoPlayer exo) {
        final ArrayList<String> urls;
        try {
            urls = piecesOf(exo);
        } catch (Throwable t) {
            return;
        }
        if (urls == null) return;
        final long startedAt = System.currentTimeMillis();
        for (int s = 0; s < SAMPLES.length; s++) {
            final int index = Math.min(urls.size() - 1, Math.max(1, Math.round(urls.size() * SAMPLES[s])));
            POOL.execute(new Runnable() {
                @Override public void run() {
                    int tries = 0;
                    while (System.currentTimeMillis() - startedAt < WARM_MS) {
                        if (CigramEmbedResolver.isAvailable(urls.get(index))) return;
                        tries++;
                        try { Thread.sleep(Math.min(3000L, 800L + tries * 400L)); } catch (InterruptedException ie) { return; }
                    }
                }
            });
        }
    }
}
