package com.Cigram.vid;

/**
 * One refresh policy for the public datasets (catalogue, channel list) — pure Java, unit-tested.
 *
 * Why it exists (audit, 18.3): {@code CigramTabRefresh} asked for the catalogue and the channel list every time a tab came
 * to the front and passed {@code force = true}, which skipped the freshness checks the two stores already had
 * ({@code CATALOG_RESUME_REFRESH_MS}, {@code FRESH_MS}). The catalogue is 5.3 MB; every return to Home or Search more than
 * two minutes after the last download downloaded, parsed, indexed and re-saved all of it, and a failure was simply tried
 * again on the next tab switch.
 *
 * Rules:
 *  1. At most one refresh runs at a time (single flight).
 *  2. {@link Reason#FORCED} (the user pulled to refresh / tapped retry) always goes through.
 *  3. With no usable snapshot a refresh is allowed (15 s minimum gap so a broken server is not hammered).
 *  4. Otherwise the snapshot must be older than the TTL of the reason; metered networks and low-RAM devices use a
 *     longer TTL for background reasons.
 *  5. After consecutive failures the gap grows exponentially (30 s, 1 min, 2 min … capped at 15 min) with ±25 % jitter;
 *     a permanent 4xx failure uses the cap at once. A success resets it.
 */
final class CigramRefreshPolicy {

    enum Reason { COLD_START, TAB_SHOWN, RESUME, FORCED }

    static final long COLD_TTL_MS = 2L * 60L * 1000L;
    static final long TAB_TTL_MS = 5L * 60L * 1000L;
    static final long RESUME_TTL_MS = 5L * 60L * 1000L;
    static final long NO_SNAPSHOT_GAP_MS = 15L * 1000L;
    static final long BACKOFF_BASE_MS = 30L * 1000L;
    static final long BACKOFF_CAP_MS = 15L * 60L * 1000L;
    static final int SLOW_NETWORK_FACTOR = 3;

    private CigramRefreshPolicy() { }

    /** Everything the decision needs; a plain value object so tests do not need Android. */
    static final class State {
        long now;
        /** Last time a request was started in this process (0 = never). */
        long lastAttemptAt;
        /** When the saved snapshot was last confirmed by the server (full download or HTTP 304), persisted. 0 = none. */
        long snapshotConfirmedAt;
        boolean running;
        int consecutiveFailures;
        boolean lastFailurePermanent;
        boolean metered;
        boolean lowRam;
        /** Seed for the jitter, so tests are deterministic (the app passes the clock). */
        long jitterSeed;
    }

    static long ttlFor(Reason reason, boolean slowNetwork) {
        long base;
        switch (reason) {
            case COLD_START: base = COLD_TTL_MS; break;
            case RESUME: base = RESUME_TTL_MS; break;
            default: base = TAB_TTL_MS; break;
        }
        return slowNetwork && reason != Reason.COLD_START ? base * SLOW_NETWORK_FACTOR : base;
    }

    /** Exponential backoff for {@code failures} consecutive failures, with ±25 % jitter derived from {@code seed}. */
    static long backoffMs(int failures, boolean permanent, long seed) {
        if (failures <= 0) return 0L;
        long raw = permanent ? BACKOFF_CAP_MS : Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS << Math.min(failures - 1, 10));
        long h = seed * 6364136223846793005L + 1442695040888963407L;          // cheap deterministic mix
        double unit = ((h >>> 11) & 0xFFFFFFFFL) / (double) 0xFFFFFFFFL;      // 0..1
        double factor = 0.75 + 0.5 * unit;                                     // 0.75..1.25
        return Math.min(BACKOFF_CAP_MS, Math.max(1000L, Math.round(raw * factor)));
    }

    static boolean shouldRefresh(Reason reason, State s) {
        return shouldRefresh(reason, s, ttlFor(reason, s.metered || s.lowRam));
    }

    /** Same decision with the dataset's own TTL (the channel list changes more often than the catalogue). */
    static boolean shouldRefresh(Reason reason, State s, long ttlMs) {
        if (s.running) return false;                       // single flight
        if (reason == Reason.FORCED) return true;
        long anchor = Math.max(s.lastAttemptAt, s.snapshotConfirmedAt);
        if (s.snapshotConfirmedAt <= 0L) {
            // nothing usable saved: ask, but not more often than every 15 s (and not faster than the backoff)
            long gap = Math.max(NO_SNAPSHOT_GAP_MS, backoffMs(s.consecutiveFailures, s.lastFailurePermanent, s.jitterSeed));
            return s.lastAttemptAt <= 0L || s.now - s.lastAttemptAt >= gap;
        }
        long need = ttlMs;
        if (s.consecutiveFailures > 0) need = Math.max(need, backoffMs(s.consecutiveFailures, s.lastFailurePermanent, s.jitterSeed));
        if (s.now < anchor) return true;                   // the clock moved back: do not get stuck
        return s.now - anchor >= need;
    }
}
