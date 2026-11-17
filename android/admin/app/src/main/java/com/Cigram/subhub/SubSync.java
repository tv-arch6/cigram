package com.Cigram.subhub;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * CigramSubtitleSyncEngine core — every rule about TIME lives here and nowhere else.
 *
 * <ul>
 *   <li>Parsing and writing never move a cue ({@link SubCodec}). The only function that changes times is
 *   {@link #shift}, and the admin only calls it for an explicitly approved correction.</li>
 *   <li>The viewer's personal delay is NOT written into any file: it is applied once, at display time, by
 *   {@link Timeline#activeAt(long, long)}. A file with a correction baked in plus a personal delay therefore adds
 *   the two exactly once each — never the same delay twice.</li>
 *   <li>Sign convention everywhere: a POSITIVE delay shows the text LATER (use it when the text comes before the
 *   speech), a negative delay shows it earlier.</li>
 *   <li>A translation keeps the source timing: {@link #applyTranslation} copies texts onto the original cues by
 *   index; {@link #compareTiming} proves it (drift per cue).</li>
 * </ul>
 */
public final class SubSync {

    private SubSync() {}

    /** The viewer's delay is limited to ±10 minutes (enough for any release mismatch, catches nonsense values). */
    public static final long MAX_DELAY_MS = 10L * 60L * 1000L;

    public static long clampDelay(long ms) {
        return Math.max(-MAX_DELAY_MS, Math.min(MAX_DELAY_MS, ms));
    }

    // ------------------------------------------------------------------ approved corrections

    /** Result of a whole-file shift. */
    public static final class ShiftResult {
        public final SubDocument document;
        /** Cues that ended before 0 after the shift and were removed. */
        public final int dropped;
        /** Cues whose start was clamped to 0. */
        public final int clamped;

        ShiftResult(SubDocument d, int dropped, int clamped) {
            this.document = d;
            this.dropped = dropped;
            this.clamped = clamped;
        }
    }

    /**
     * Moves every cue by {@code offsetMs} (positive = later). Order and indices are kept; a cue pushed entirely
     * before 0 is dropped, one partly before 0 starts at 0. Only for an APPROVED correction — never automatic.
     */
    public static ShiftResult shift(SubDocument d, long offsetMs) {
        if (offsetMs == 0) return new ShiftResult(d, 0, 0);
        List<SubCue> out = new ArrayList<SubCue>(d.size());
        int dropped = 0, clamped = 0;
        for (SubCue c : d.cues) {
            long s = c.startMs + offsetMs, e = c.endMs + offsetMs;
            if (e <= 0) { dropped++; continue; }
            if (s < 0) { s = 0; clamped++; }
            out.add(c.withTimes(s, e));
        }
        return new ShiftResult(d.withCues(out), dropped, clamped);
    }

    /**
     * Linear correction for a frame-rate mismatch (e.g. 25 → 23.976 fps): t' = t * factor + offset. Approved use only.
     * factor = sourceFps / targetFps.
     */
    public static ShiftResult stretch(SubDocument d, double factor, long offsetMs) {
        if (!(factor > 0.5 && factor < 2.0)) throw new IllegalArgumentException("معامل غير صالح");
        List<SubCue> out = new ArrayList<SubCue>(d.size());
        int dropped = 0, clamped = 0;
        for (SubCue c : d.cues) {
            long s = Math.round(c.startMs * factor) + offsetMs, e = Math.round(c.endMs * factor) + offsetMs;
            if (e <= 0) { dropped++; continue; }
            if (s < 0) { s = 0; clamped++; }
            out.add(c.withTimes(s, e));
        }
        return new ShiftResult(d.withCues(out), dropped, clamped);
    }

    // ------------------------------------------------------------------ translation keeps the timing

    /**
     * The translated document: the SOURCE cues (times, ids, settings, order) with the translated texts.
     * {@code texts.get(k)} is the translation of {@code source.cues.get(k)}. Sizes must match.
     */
    public static SubDocument applyTranslation(SubDocument source, List<String> texts) {
        if (texts == null || texts.size() != source.size()) {
            throw new IllegalArgumentException("عدد المقاطع المترجمة (" + (texts == null ? 0 : texts.size()) + ") لا يطابق الأصل (" + source.size() + ").");
        }
        List<SubCue> out = new ArrayList<SubCue>(source.size());
        for (int k = 0; k < source.size(); k++) out.add(source.cues.get(k).withText(texts.get(k) == null ? "" : texts.get(k)));
        return source.withCues(out);
    }

    /** Per-cue comparison of a translated file against its source. */
    public static final class TimingReport {
        public final int sourceCues;
        public final int translatedCues;
        /** Cues compared (min of both counts). */
        public final int compared;
        /** Cues whose start or end differs by more than the tolerance. */
        public final int drifted;
        public final long maxDriftMs;
        /** First drifted cue (1-based index in the translation), 0 when none. */
        public final int firstDriftIndex;

        TimingReport(int s, int t, int compared, int drifted, long max, int first) {
            this.sourceCues = s;
            this.translatedCues = t;
            this.compared = compared;
            this.drifted = drifted;
            this.maxDriftMs = max;
            this.firstDriftIndex = first;
        }

        public boolean countMatches() {
            return sourceCues == translatedCues;
        }

        public boolean preserved() {
            return countMatches() && drifted == 0;
        }
    }

    public static TimingReport compareTiming(SubDocument source, SubDocument translated, long toleranceMs) {
        int n = Math.min(source.size(), translated.size());
        int drifted = 0, first = 0;
        long max = 0;
        for (int k = 0; k < n; k++) {
            SubCue a = source.cues.get(k), b = translated.cues.get(k);
            long d = Math.max(Math.abs(a.startMs - b.startMs), Math.abs(a.endMs - b.endMs));
            if (d > toleranceMs) {
                drifted++;
                if (first == 0) first = k + 1;
            }
            max = Math.max(max, d);
        }
        return new TimingReport(source.size(), translated.size(), n, drifted, max, first);
    }

    /**
     * A provider returned a translation whose timing moved but whose cue count is the same: put the translated texts
     * back on the source timing (rule 1/2: preserve original cue timestamps, never regenerate them). Returns null when
     * the counts differ — that file needs a human (NEEDS_CORRECTION), it is not "repaired" by guessing.
     */
    public static SubDocument restoreSourceTiming(SubDocument source, SubDocument translated) {
        if (source.size() != translated.size()) return null;
        List<String> texts = new ArrayList<String>(translated.size());
        for (SubCue c : translated.cues) texts.add(c.text);
        return applyTranslation(source, texts);
    }

    // ------------------------------------------------------------------ display timeline

    /** Fast "which cues are on screen at t" over a document. Invalid cues (end ≤ start) are never shown. */
    public static final class Timeline {
        private final SubCue[] byStart;
        private final long[] starts;
        private final long maxDuration;

        public Timeline(SubDocument d) {
            List<SubCue> ok = new ArrayList<SubCue>(d.size());
            for (SubCue c : d.cues) if (c.endMs > c.startMs && !c.isBlank()) ok.add(c);
            Collections.sort(ok, new Comparator<SubCue>() {
                @Override public int compare(SubCue a, SubCue b) {
                    if (a.startMs != b.startMs) return a.startMs < b.startMs ? -1 : 1;
                    return a.index - b.index;   // stable: source order for equal starts
                }
            });
            byStart = ok.toArray(new SubCue[0]);
            starts = new long[byStart.length];
            long maxD = 0;
            for (int k = 0; k < byStart.length; k++) {
                starts[k] = byStart[k].startMs;
                maxD = Math.max(maxD, byStart[k].durationMs());
            }
            maxDuration = maxD;
        }

        public int size() {
            return byStart.length;
        }

        /**
         * Cues visible at media position {@code positionMs} with the viewer's {@code delayMs} (positive = later):
         * a cue is shown while {@code start + delay <= position < end + delay}. Returned in start order.
         */
        public List<SubCue> activeAt(long positionMs, long delayMs) {
            long t = positionMs - delayMs;
            int hi = upperBound(t) - 1;          // last cue with start <= t
            if (hi < 0) return Collections.emptyList();
            List<SubCue> out = null;
            for (int k = hi; k >= 0; k--) {
                SubCue c = byStart[k];
                if (t - c.startMs >= maxDuration) break;   // no earlier cue can still be on screen
                if (t < c.endMs) {
                    if (out == null) out = new ArrayList<SubCue>(2);
                    out.add(0, c);
                }
            }
            return out == null ? Collections.<SubCue>emptyList() : out;
        }

        /** Next media position (with the same delay) at which the visible set can change; Long.MAX_VALUE if none. */
        public long nextChangeAfter(long positionMs, long delayMs) {
            long t = positionMs - delayMs;
            long next = Long.MAX_VALUE;
            int k = upperBound(t);
            if (k < byStart.length) next = byStart[k].startMs;
            for (SubCue c : activeAt(positionMs, delayMs)) next = Math.min(next, c.endMs);
            return next == Long.MAX_VALUE ? next : next + delayMs;
        }

        /** The cue nearest to t (for the delay preview): the active one, else the next one, else the previous one. */
        public SubCue nearest(long positionMs, long delayMs) {
            List<SubCue> a = activeAt(positionMs, delayMs);
            if (!a.isEmpty()) return a.get(0);
            long t = positionMs - delayMs;
            int k = upperBound(t);
            if (k < byStart.length) return byStart[k];
            return byStart.length > 0 ? byStart[byStart.length - 1] : null;
        }

        private int upperBound(long t) {
            int lo = 0, hi = starts.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (starts[mid] <= t) lo = mid + 1; else hi = mid;
            }
            return lo;
        }

        SubCue[] cuesForTest() {
            return Arrays.copyOf(byStart, byStart.length);
        }
    }

    /** "+0.50 ث" / "−1.20 ث" / "0" for labels. */
    public static String delayLabel(long ms) {
        if (ms == 0) return "0";
        long a = Math.abs(ms);
        String v = (a / 1000) + "." + (a % 1000 / 100) + (a % 100 / 10);
        return (ms > 0 ? "+" : "−") + v + " ث";
    }
}
