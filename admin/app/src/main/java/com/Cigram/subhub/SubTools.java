package com.Cigram.subhub;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CigramSubtitleHub — the subtitle toolbox, one entry point per tool:
 *
 * <pre>
 *   Auto Sync               {@link SubAutoSync#alignToReference} / {@link SubAutoSync#alignToActivity}
 *   Subtitle Delay          {@link #delay}
 *   Timing Drift Fix        {@link #driftFix} (two points the editor synced by hand) / {@link #detectDrift}
 *   SRT Repair              {@link SubRepair#repair}
 *   Encoding Fixer          {@link SubEncoding#fix}
 *   Overlap Detector        {@link SubRepair#overlaps} / {@link SubRepair#fixOverlaps}
 *   Missing Lines Detector  {@link SubRepair#missing}
 *   FPS Converter           {@link #convertFps}
 *   Subtitle Cleaner        {@link SubCleaner#clean}
 *   Subtitle Preview        {@link #preview} / {@link #strip}
 * </pre>
 *
 * and {@link #doctor}, which runs the safe ones in the right order and returns one report («Cigram Auto Repair» for
 * a subtitle file). The rule of the hub still holds: a tool returns a NEW document and a report; saving, approving
 * and publishing stay explicit steps of the caller.
 *
 * Pure Java, shared byte-identical between Cigram and Cigram Admin.
 */
public final class SubTools {

    private SubTools() {}

    // ------------------------------------------------------------------ Subtitle Delay

    /** Moves the whole file. Positive = the text is shown later. */
    public static SubSync.ShiftResult delay(SubDocument d, long offsetMs) {
        return SubSync.shift(d, offsetMs);
    }

    // ------------------------------------------------------------------ Timing Drift Fix

    public static final class Drift {
        public final double factor;
        public final long offsetMs;
        public final SubSync.ShiftResult result;

        Drift(double factor, long offsetMs, SubSync.ShiftResult result) {
            this.factor = factor;
            this.offsetMs = offsetMs;
            this.result = result;
        }

        public long driftPerHourMs() {
            return Math.round((factor - 1.0) * 3600000.0);
        }

        public String describe() {
            String f = fpsLabelForFactor(factor);
            return "معامل " + String.format(java.util.Locale.US, "%.6f", factor) + (f.length() > 0 ? " (" + f + " fps)" : "")
                    + " · إزاحة " + SubSync.delayLabel(offsetMs) + " · انحراف " + SubSync.delayLabel(driftPerHourMs()) + " لكل ساعة";
        }
    }

    /**
     * Linear correction from two lines the editor placed by hand: the line that the file shows at
     * {@code firstFileMs} is really spoken at {@code firstFilmMs}, and the same for a line near the end.
     * Every other cue is interpolated between (and extrapolated beyond) the two.
     *
     * @throws IllegalArgumentException when the two points are closer than one minute or give an impossible factor
     */
    public static Drift driftFix(SubDocument d, long firstFileMs, long firstFilmMs, long lastFileMs, long lastFilmMs) {
        long spanFile = lastFileMs - firstFileMs, spanFilm = lastFilmMs - firstFilmMs;
        if (spanFile < 60000 || spanFilm < 60000) throw new IllegalArgumentException("اختر نقطتين تفصل بينهما دقيقة على الأقل (الأفضل: أول الفيلم وآخره).");
        double factor = spanFilm / (double) spanFile;
        if (!(factor > 0.8 && factor < 1.25)) throw new IllegalArgumentException("النقطتان تعطيان معاملاً غير معقول (" + String.format(java.util.Locale.US, "%.3f", factor) + "): تحقق من التوقيتين.");
        long offset = Math.round(firstFilmMs - firstFileMs * factor);
        return new Drift(factor, offset, SubSync.stretch(d, factor, offset));
    }

    /** Drift of {@code target} against a reference that is in sync, or null when no reliable match exists. */
    public static SubAutoSync.Alignment detectDrift(SubDocument target, SubDocument reference) {
        SubAutoSync.Alignment a = SubAutoSync.alignToReference(target, reference);
        return a.reliable() ? a : null;
    }

    // ------------------------------------------------------------------ FPS Converter

    /** Frame rates offered by the converter. */
    public static final double[] FPS = {23.976, 24.0, 25.0, 29.97, 30.0, 50.0, 59.94, 60.0};

    public static String fpsName(double fps) {
        if (Math.abs(fps - 23.976) < 0.001) return "23.976";
        if (Math.abs(fps - 29.97) < 0.001) return "29.97";
        if (Math.abs(fps - 59.94) < 0.001) return "59.94";
        return fps == Math.floor(fps) ? String.valueOf((long) fps) : String.valueOf(fps);
    }

    /** Exact value of a nominal rate (23.976 = 24000/1001, 29.97 = 30000/1001, 59.94 = 60000/1001). */
    public static double exactFps(double fps) {
        if (Math.abs(fps - 23.976) < 0.002) return 24000.0 / 1001.0;
        if (Math.abs(fps - 29.97) < 0.002) return 30000.0 / 1001.0;
        if (Math.abs(fps - 59.94) < 0.002) return 60000.0 / 1001.0;
        return fps;
    }

    /**
     * A file timed for a {@code fromFps} release, converted for a {@code toFps} release of the same film:
     * every time is multiplied by from / to (a 25 fps PAL release runs 4% faster than the 23.976 one, so its
     * subtitle must be stretched by 25 / 23.976 to fit).
     */
    public static SubSync.ShiftResult convertFps(SubDocument d, double fromFps, double toFps) {
        if (!(fromFps > 1 && toFps > 1)) throw new IllegalArgumentException("معدل إطارات غير صالح.");
        double factor = exactFps(fromFps) / exactFps(toFps);
        if (Math.abs(factor - 1.0) < 1e-9) return SubSync.shift(d, 0);
        return SubSync.stretch(d, factor, 0);
    }

    /** "25 → 23.976" when {@code factor} equals from/to for two standard rates (the most common pair first), else "". */
    public static String fpsLabelForFactor(double factor) {
        if (Math.abs(factor - 1.0) < 0.00015) return "";
        double[][] pairs = {{25, 23.976}, {23.976, 25}, {25, 24}, {24, 25}, {24, 23.976}, {23.976, 24}, {30, 29.97}, {29.97, 30},
                {30, 25}, {25, 30}, {29.97, 25}, {25, 29.97}, {29.97, 23.976}, {23.976, 29.97}};
        for (double[] p : pairs) {
            if (Math.abs(exactFps(p[0]) / exactFps(p[1]) - factor) < 0.0003) return fpsName(p[0]) + " → " + fpsName(p[1]);
        }
        return "";
    }

    /**
     * Which conversion makes the file end where the film's dialogue ends. {@code filmLastLineMs} is the time of the
     * last spoken line in the film (not the end of the credits). Returns {from, to} or null when no standard pair
     * explains the difference within one second.
     */
    public static double[] guessFps(SubDocument d, long filmLastLineMs) {
        long last = 0;
        for (SubCue c : d.cues) if (!c.isBlank()) last = Math.max(last, c.startMs);
        if (last < 10 * 60000L || filmLastLineMs < 10 * 60000L) return null;
        double want = filmLastLineMs / (double) last;
        double[] best = null;
        double bestErr = 1000;
        double[][] pairs = {{25, 23.976}, {23.976, 25}, {25, 24}, {24, 25}, {24, 23.976}, {23.976, 24}};
        for (double[] p : pairs) {
            double err = Math.abs(last * (exactFps(p[0]) / exactFps(p[1])) - filmLastLineMs);
            if (err < bestErr) { bestErr = err; best = p; }
        }
        if (Math.abs(want - 1.0) * last < bestErr) return null;   // "no conversion" explains it better
        return bestErr <= 1000 ? best : null;
    }

    // ------------------------------------------------------------------ Subtitle Preview

    public static final class Preview {
        /** Lines on screen now (markup removed), "" when nothing is shown. */
        public final String text;
        /** The cue on screen (the first one when several), or null. */
        public final SubCue current;
        /** The next cue to appear, or null at the end. */
        public final SubCue next;
        /** Milliseconds until the next change on screen (a cue appearing or disappearing); -1 when none. */
        public final long untilChangeMs;
        public final int activeCount;

        Preview(String text, SubCue current, SubCue next, long untilChangeMs, int activeCount) {
            this.text = text;
            this.current = current;
            this.next = next;
            this.untilChangeMs = untilChangeMs;
            this.activeCount = activeCount;
        }
    }

    /** What the viewer sees at {@code positionMs} with a personal {@code delayMs} (positive = later). */
    public static Preview preview(SubSync.Timeline tl, long positionMs, long delayMs) {
        List<SubCue> on = tl.activeAt(positionMs, delayMs);
        StringBuilder b = new StringBuilder();
        for (SubCue c : on) {
            if (b.length() > 0) b.append('\n');
            b.append(c.plainText().trim());
        }
        long change = tl.nextChangeAfter(positionMs, delayMs);
        SubCue next = null;
        if (change != Long.MAX_VALUE) {
            // the next cue to appear: the nearest one that is not already on screen
            SubCue n = tl.nearest(change, delayMs);
            if (n != null && !on.contains(n) && n.startMs + delayMs >= positionMs) next = n;
            else {
                long probe = change;
                for (int guard = 0; guard < 8 && probe != Long.MAX_VALUE; guard++) {
                    List<SubCue> at = tl.activeAt(probe, delayMs);
                    for (SubCue c : at) if (!on.contains(c)) { next = c; break; }
                    if (next != null) break;
                    probe = tl.nextChangeAfter(probe, delayMs);
                }
            }
        }
        return new Preview(b.toString(), on.isEmpty() ? null : on.get(0), next,
                change == Long.MAX_VALUE ? -1 : Math.max(0, change - positionMs), on.size());
    }

    /** One row of the preview strip. */
    public static final class StripRow {
        public final SubCue cue;
        /** Display times with the delay applied. */
        public final long showMs, hideMs;
        public final boolean overlapsPrevious;
        /** Characters per second of reading (0 when the cue has no duration). */
        public final double cps;

        StripRow(SubCue cue, long showMs, long hideMs, boolean overlapsPrevious, double cps) {
            this.cue = cue;
            this.showMs = showMs;
            this.hideMs = hideMs;
            this.overlapsPrevious = overlapsPrevious;
            this.cps = cps;
        }
    }

    /** The cues shown between two media times, in display order, at most {@code max} rows (for the scrubber list). */
    public static List<StripRow> strip(SubDocument d, long fromMs, long toMs, long delayMs, int max) {
        List<SubCue> sorted = new ArrayList<SubCue>(d.size());
        for (SubCue c : d.cues) if (c.endMs > c.startMs && !c.isBlank()) sorted.add(c);
        Collections.sort(sorted, new java.util.Comparator<SubCue>() {
            @Override public int compare(SubCue a, SubCue b) {
                if (a.startMs != b.startMs) return a.startMs < b.startMs ? -1 : 1;
                return a.index - b.index;
            }
        });
        List<StripRow> out = new ArrayList<StripRow>();
        long prevEnd = Long.MIN_VALUE;
        for (SubCue c : sorted) {
            long show = c.startMs + delayMs, hide = c.endMs + delayMs;
            boolean overlap = c.startMs < prevEnd;
            prevEnd = Math.max(prevEnd, c.endMs);
            if (hide <= fromMs) continue;
            if (show >= toMs) break;
            int chars = 0;
            String p = c.plainText();
            for (int i = 0; i < p.length(); i++) if (!Character.isWhitespace(p.charAt(i))) chars++;
            out.add(new StripRow(c, show, hide, overlap, chars * 1000.0 / Math.max(1, c.durationMs())));
            if (out.size() >= max) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ doctor (one tap)

    public static final class DoctorReport {
        public final SubDocument document;
        /** Standard UTF-8 SRT of the result. */
        public final String srt;
        public final SubEncoding.Fix encoding;
        public final SubRepair.Report repair;
        public final SubCleaner.Report clean;
        public final SubRepair.Missing missing;
        public final SubQa.Report qa;
        /** Human-readable steps in the order they ran (Arabic). */
        public final List<String> steps;

        DoctorReport(SubDocument d, String srt, SubEncoding.Fix e, SubRepair.Report r, SubCleaner.Report c, SubRepair.Missing m,
                     SubQa.Report qa, List<String> steps) {
            this.document = d;
            this.srt = srt;
            this.encoding = e;
            this.repair = r;
            this.clean = c;
            this.missing = m;
            this.qa = qa;
            this.steps = Collections.unmodifiableList(steps);
        }

        /** True when the doctor changed anything compared with a plain reading of the file. */
        public boolean changed() {
            return encoding.changed || repair.changed() || clean.changed();
        }

        public String headline() {
            if (document.isEmpty()) return "تعذّر استخراج أي مقطع صالح من الملف.";
            int fixes = repair.totalFixes() + clean.total() + encoding.repairedLines;
            return (fixes == 0 ? "الملف سليم" : "أُصلح " + fixes + " موضعاً") + " · " + document.size() + " مقطعاً · " + qa.summary();
        }
    }

    /**
     * Encoding Fixer → SRT Repair → Subtitle Cleaner → Missing Lines Detector → quality checks, on raw bytes.
     * Whole-file timing (delay, drift, frame rate) is deliberately NOT part of it: that needs the film
     * ({@link SubAutoSync}) or a decision by the editor.
     */
    public static DoctorReport doctor(byte[] raw, SubCleaner.Options cleanOptions, long mediaDurationMs) {
        List<String> steps = new ArrayList<String>();
        SubEncoding.Fix enc = SubEncoding.fix(raw);
        steps.add("الترميز: " + enc.encoding + (enc.notes.isEmpty() ? "" : " — " + enc.notes.get(enc.notes.size() - 1)));
        SubRepair.Report rep = SubRepair.repair(enc.text, enc.encoding, new SubRepair.Options());
        steps.add("الإصلاح: " + rep.summary());
        SubCleaner.Report cl = SubCleaner.clean(rep.document, cleanOptions == null ? new SubCleaner.Options() : cleanOptions);
        steps.add("التنظيف: " + cl.summary());
        // renumber after the cleaner removed cues
        List<SubCue> numbered = new ArrayList<SubCue>(cl.document.size());
        for (int k = 0; k < cl.document.size(); k++) {
            SubCue c = cl.document.cues.get(k);
            numbered.add(new SubCue(k + 1, String.valueOf(k + 1), c.startMs, c.endMs, c.text, c.settings));
        }
        SubDocument doc = cl.document.withCues(numbered);
        SubRepair.Missing miss = SubRepair.missing(doc, null, mediaDurationMs);
        steps.add("الأسطر الناقصة: " + miss.summary());
        SubQa.Options qo = new SubQa.Options();
        qo.expectArabic = SubCodec.arabicShare(sample(doc)) >= 0.5;
        SubQa.Report qa = SubQa.analyze(doc, qo);
        steps.add("الفحص: " + qa.summary());
        return new DoctorReport(doc, SubCodec.toSrt(doc), enc, rep, cl, miss, qa, steps);
    }

    private static String sample(SubDocument d) {
        StringBuilder b = new StringBuilder();
        for (SubCue c : d.cues) {
            b.append(c.plainText()).append('\n');
            if (b.length() > 20000) break;
        }
        return b.toString();
    }
}
