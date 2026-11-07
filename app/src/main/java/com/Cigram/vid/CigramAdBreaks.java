package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * v6.2: where the ads go inside a film / episode.
 *
 * - Count by length (v10.34, owner's request): under 8 min none, under 60 min 2, else 4.
 * - The points are spread evenly and moved a little per title (same title -> same minutes every
 *   time, another title -> other minutes), never in the first 5 minutes or the last 3.
 * - Breaks set by the admin on the item ("ad_breaks") still win; this is only the default.
 * The player's mid-roll (CigramStage1InitProvider stage5Tick) shows them, and CigramSeekMarks
 * draws them in yellow on the time bar.
 */
public final class CigramAdBreaks {

    public static final int MAX_BREAKS = 4;
    private static final long MIN = 60000L;
    private static final long NOT_BEFORE_MS = 5L * MIN;
    private static final long NOT_AFTER_END_MS = 3L * MIN;
    private static final long MIN_GAP_MS = 4L * MIN;

    /** Breaks of what each player is showing now (read by the time bar). */
    private static final Map<Context, long[]> CURRENT = new WeakHashMap<Context, long[]>();

    private CigramAdBreaks() {}

    public static int countFor(long durationMs) {
        if (durationMs < 8L * MIN) return 0;
        if (durationMs < 60L * MIN) return 2;     // episode / short film: 2 ads
        return MAX_BREAKS;                        // film of an hour or more: 4 ads
    }

    /** Break positions in ms, sorted. `key` identifies the title (its media url or id). */
    public static long[] forItem(String key, long durationMs) {
        int n = countFor(durationMs);
        if (n <= 0) return new long[0];
        long slot = durationMs / (n + 1);
        long lo = NOT_BEFORE_MS;
        long hi = durationMs - NOT_AFTER_END_MS;
        ArrayList<Long> out = new ArrayList<Long>();
        long prev = -MIN_GAP_MS;
        for (int i = 1; i <= n; i++) {
            double r = unit(key, i);                  // 0..1, fixed for this title and break
            long jitter = (long) ((r - 0.5) * 0.5 * slot); // up to a quarter slot earlier or later
            long t = slot * i + jitter;
            t = Math.max(lo, Math.min(hi, t));
            if (t - prev < MIN_GAP_MS) t = prev + MIN_GAP_MS;
            if (t > hi) break;
            t = (t / 1000L) * 1000L;                  // whole seconds
            out.add(Long.valueOf(t));
            prev = t;
        }
        long[] a = new long[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i).longValue();
        return a;
    }

    public static ArrayList<Long> asList(long[] breaks) {
        ArrayList<Long> l = new ArrayList<Long>();
        if (breaks != null) for (long b : breaks) l.add(Long.valueOf(b));
        return l;
    }

    public static void setCurrent(Activity activity, ArrayList<Long> breaks) {
        if (activity == null) return;
        long[] a = new long[breaks == null ? 0 : breaks.size()];
        for (int i = 0; i < a.length; i++) a[i] = breaks.get(i) == null ? -1L : breaks.get(i).longValue();
        synchronized (CURRENT) {
            CURRENT.put(activity, a);
        }
    }

    public static long[] current(Context activity) {
        synchronized (CURRENT) {
            long[] a = activity == null ? null : CURRENT.get(activity);
            return a == null ? new long[0] : a;
        }
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
