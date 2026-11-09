package com.Cigram.vid;

import android.os.SystemClock;

/**
 * "Quiet moment" gate for mid-roll ads: lets a break wait a little for a pause in the dialogue
 * instead of cutting a sentence in half.
 *
 * It is a pure decision helper that is fed with the playback audio level (0..1) through
 * {@link #feed(float)}. While nothing feeds it, {@link #hasSignal()} is false and
 * {@link #allowBreak(long, long)} always answers yes, so the player falls back to its normal
 * break window and ads are never delayed or lost because of this class.
 *
 * Thread-safe: the audio source may call {@link #feed(float)} from any thread.
 */
public final class CigramQuiet {
    /** A level below this counts as quiet. */
    private static final float QUIET_LEVEL = 0.06f;
    /** The quiet has to last this long before a break may start. */
    private static final long QUIET_HOLD_MS = 350L;
    /** Samples older than this mean the audio source is gone. */
    private static final long SIGNAL_TTL_MS = 3000L;
    /** Never hold the break back during the last part of its window. */
    private static final long FORCE_MARGIN_MS = 4000L;

    private static final Object LOCK = new Object();
    private static long lastSampleAt;
    private static long quietSince;

    private CigramQuiet() {
    }

    /** Report the current audio level of the playing video (0 = silence, 1 = full scale). */
    public static void feed(float level) {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            lastSampleAt = now;
            if (level < QUIET_LEVEL) {
                if (quietSince == 0L) quietSince = now;
            } else {
                quietSince = 0L;
            }
        }
    }

    /** True while audio levels are being reported. */
    public static boolean hasSignal() {
        synchronized (LOCK) {
            return lastSampleAt != 0L
                    && SystemClock.elapsedRealtime() - lastSampleAt <= SIGNAL_TTL_MS;
        }
    }

    /**
     * @param sinceBreakMs how long ago the planned break point passed
     * @param windowMs     how long the break may still wait for a quiet moment
     * @return true when the ad may start now
     */
    public static boolean allowBreak(long sinceBreakMs, long windowMs) {
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            if (lastSampleAt == 0L || now - lastSampleAt > SIGNAL_TTL_MS) return true;
            if (sinceBreakMs >= windowMs - FORCE_MARGIN_MS) return true;
            return quietSince != 0L && now - quietSince >= QUIET_HOLD_MS;
        }
    }
}
