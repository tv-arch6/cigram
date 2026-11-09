package com.Cigram.vid;

/**
 * How aggressive {@link CigramSegmentAccel} may be (pure Java, unit-tested).
 *
 * Audit finding (18.3): the accelerator always split a piece into 2-4 parallel range requests, kept a fixed 64 MB store and
 * fetched 2-4 pieces ahead, whatever the connection. That helps when a host throttles every connection, but on a weak or
 * metered line several connections only fight each other (each slow start, each a share of the same little bandwidth) and the
 * look-ahead downloads minutes of video the viewer may never watch.
 *
 * Level 0 (good connection): unchanged. Level 1 (weak): at most 2 connections, 2 pieces ahead, 32 MB. Level 2 (very weak or
 * repeated stalls): ONE connection per piece, 1 piece ahead, 16 MB. Metered networks never look further than 2 pieces
 * ahead and keep at most 32 MB; low-RAM phones halve the store.
 */
final class CigramAccelPolicy {

    final int maxParts;
    final int maxAhead;
    final long budgetBytes;

    private CigramAccelPolicy(int maxParts, int maxAhead, long budgetBytes) {
        this.maxParts = maxParts;
        this.maxAhead = maxAhead;
        this.budgetBytes = budgetBytes;
    }

    static CigramAccelPolicy decide(int level, boolean lowRam, boolean metered, int defaultParts, int defaultAhead, long defaultBudget) {
        int parts = defaultParts, ahead = defaultAhead;
        long budget = defaultBudget;
        if (level >= 2) { parts = 1; ahead = 1; budget = Math.min(budget, 16L * 1024L * 1024L); }
        else if (level == 1) { parts = Math.min(parts, 2); ahead = Math.min(ahead, 2); budget = Math.min(budget, 32L * 1024L * 1024L); }
        if (metered) { ahead = Math.min(ahead, 2); budget = Math.min(budget, 32L * 1024L * 1024L); }
        if (lowRam) budget = Math.max(8L * 1024L * 1024L, budget / 2);
        return new CigramAccelPolicy(Math.max(1, parts), Math.max(1, ahead), budget);
    }
}
