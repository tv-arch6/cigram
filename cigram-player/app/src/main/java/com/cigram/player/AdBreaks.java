package com.cigram.player;

import java.util.ArrayList;

/**
 * Where the mid-roll ads go inside a film / episode (same rules as the Cigram app's CigramAdBreaks):
 * under 8 min none, under 60 min two, otherwise four; spread evenly, moved a little per title (same title →
 * same minutes every time), never in the first 5 minutes or the last 3, at least 4 minutes apart.
 */
final class AdBreaks {

    static final int MAX_BREAKS = 4;
    private static final long MIN = 60000L;
    private static final long NOT_BEFORE_MS = 5L * MIN;
    private static final long NOT_AFTER_END_MS = 3L * MIN;
    private static final long MIN_GAP_MS = 4L * MIN;

    private AdBreaks() { }

    static int countFor(long durationMs) {
        if (durationMs < 8L * MIN) return 0;
        if (durationMs < 60L * MIN) return 2;
        return MAX_BREAKS;
    }

    /** Break positions in ms, sorted. {@code key} identifies the title. */
    static long[] forItem(String key, long durationMs) {
        int n = countFor(durationMs);
        if (n <= 0) return new long[0];
        long slot = durationMs / (n + 1);
        long lo = NOT_BEFORE_MS;
        long hi = durationMs - NOT_AFTER_END_MS;
        ArrayList<Long> out = new ArrayList<Long>();
        long prev = -MIN_GAP_MS;
        for (int i = 1; i <= n; i++) {
            double r = unit(key, i);
            long jitter = (long) ((r - 0.5) * 0.5 * slot);
            long t = slot * i + jitter;
            t = Math.max(lo, Math.min(hi, t));
            if (t - prev < MIN_GAP_MS) t = prev + MIN_GAP_MS;
            if (t > hi) break;
            t = (t / 1000L) * 1000L;
            out.add(Long.valueOf(t));
            prev = t;
        }
        long[] a = new long[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i).longValue();
        return a;
    }

    /** Stable pseudo-random number in [0,1) from the title key and the break index. */
    private static double unit(String key, int i) {
        long h = 1125899906842597L;
        String s = (key == null ? "" : key) + "#" + i;
        for (int k = 0; k < s.length(); k++) h = 31L * h + s.charAt(k);
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        return (h >>> 11) / (double) (1L << 53);
    }
}
