package com.Cigram.vid;

import android.os.Handler;
import android.os.Looper;

import com.google.android.exoplayer2.ExoPlayer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v10.82: a seek on an embed stream never stops or rewinds the film.
 *
 * Their servers prepare a part of the film only when it is asked for, so a seek to a place that is not ready
 * used to leave the picture frozen (and, in 10.78+, jump back to the start). Now the film KEEPS PLAYING where it
 * is while the target is asked for, again and again (a new cache key each time, which also makes the server
 * prepare it); the moment the target is there the player moves to it. Nothing is shown on screen meanwhile.
 * A target that is already buffered, a normal file or a channel are sought at once, exactly as before.
 */
final class CigramSeekGuard {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "cigram-seek-guard");
            t.setDaemon(true);
            return t;
        }
    });
    private static final AtomicInteger GENERATION = new AtomicInteger();
    /** How long the film keeps playing while the target is prepared; then the seek is made anyway. */
    private static final long GIVE_UP_MS = 150000L;

    private CigramSeekGuard() { }

    /** A newer request (or leaving the screen) cancels the one waiting. */
    static void cancel() {
        GENERATION.incrementAndGet();
    }

    private static long indexTime(com.google.android.exoplayer2.source.hls.playlist.HlsMediaPlaylist mp, int i) {
        return mp.segments.get(i).relativeStartTimeUs / 1000L;
    }

    static void seek(final ExoPlayer exo, final long target) {
        final int gen = GENERATION.incrementAndGet();
        try {
            Object manifest = exo.getCurrentManifest();
            if (!(manifest instanceof com.google.android.exoplayer2.source.hls.HlsManifest)) { exo.seekTo(target); return; }
            final com.google.android.exoplayer2.source.hls.playlist.HlsMediaPlaylist mp =
                    ((com.google.android.exoplayer2.source.hls.HlsManifest) manifest).mediaPlaylist;
            int n = mp.segments.size();
            if (n < 4 || !CigramEmbedResolver.hasSeenHosts()) { exo.seekTo(target); return; }
            int at = 0;
            for (int i = 0; i < n; i++) { if (indexTime(mp, i) <= target) at = i; else break; }
            final int index = at;
            final String url = com.google.android.exoplayer2.util.UriUtil.resolve(mp.baseUri, mp.segments.get(at).url);
            final String next = com.google.android.exoplayer2.util.UriUtil.resolve(mp.baseUri, mp.segments.get(Math.min(n - 1, at + 1)).url);
            if (!CigramEmbedResolver.isSeenHost(android.net.Uri.parse(url).getHost())) { exo.seekTo(target); return; }

            long current = exo.getCurrentPosition();
            // already loaded (or just behind us, in the back buffer): the player moves there by itself
            if (target >= current - 20000L && target <= exo.getBufferedPosition()) { exo.seekTo(target); return; }
            if (CigramSegmentAccel.has(url) && CigramSegmentAccel.has(next)) { exo.seekTo(target); return; }

            final long startedAt = android.os.SystemClock.elapsedRealtime();
            POOL.execute(new Runnable() {
                @Override public void run() {
                    while (gen == GENERATION.get()) {
                        boolean ready = CigramSegmentAccel.has(url) || CigramEmbedResolver.isAvailable(url);
                        if (ready && !CigramSegmentAccel.has(next)) ready = CigramEmbedResolver.isAvailable(next);
                        boolean late = android.os.SystemClock.elapsedRealtime() - startedAt > GIVE_UP_MS;
                        if (ready || late) {
                            MAIN.post(new Runnable() {
                                @Override public void run() {
                                    if (gen != GENERATION.get()) return;   // the user sought somewhere else meanwhile
                                    try { exo.seekTo(target); } catch (Throwable ignored) { }
                                }
                            });
                            return;
                        }
                        try { Thread.sleep(700L); } catch (InterruptedException ie) { return; }
                    }
                }
            });
            // start preparing it now (the piece itself is fetched by the download store as well)
            try { CigramSegmentAccel.prefetch(url); } catch (Throwable ignored) { }
        } catch (Throwable t) {
            try { exo.seekTo(target); } catch (Throwable ignored) { }
        }
    }
}
