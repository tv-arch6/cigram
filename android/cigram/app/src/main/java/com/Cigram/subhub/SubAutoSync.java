package com.Cigram.subhub;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * CigramSubtitleHub — Auto Sync.
 *
 * Finds the correction {@code t' = t × factor + offset} that puts a subtitle on the film, in two independent ways:
 *
 * <ol>
 *   <li><b>Against a reference subtitle</b> ({@link #alignToReference}) — any file that IS in sync with this release
 *   (the original-language track, another translation). Cue starts of the two files are matched: a vote over all
 *   pairs finds the offset for every standard frame-rate ratio, then a robust least-squares fit over the matched
 *   pairs gives the exact factor and offset. Works across languages because translators keep the cue boundaries.</li>
 *   <li><b>Against the film's own sound</b> ({@link #alignToActivity}) — no second file needed. Decoded audio is
 *   reduced to "how much speech-band energy was there in each 50 ms" ({@link ActivityBuilder}; nothing is recorded
 *   or stored, the microphone is never used), and the offset whose subtitle on/off pattern correlates best with
 *   that energy wins. Several windows of the film ({@link #fitWindows}) also reveal a progressive drift.</li>
 * </ol>
 *
 * Both return an {@link Alignment} with a confidence; nothing is applied by this class. The viewer applies an
 * accepted offset as his personal delay (display time only); the admin applies it as an approved correction
 * through {@link SubSync#stretch}. A low-confidence result is reported as such and never applied silently.
 *
 * Pure Java, shared byte-identical between Cigram and Cigram Admin.
 */
public final class SubAutoSync {

    private SubAutoSync() {}

    // ------------------------------------------------------------------ result

    public static final class Alignment {
        /** 1.0 = no stretch. */
        public final double factor;
        /** Milliseconds added after the stretch; positive = the text is shown later. */
        public final long offsetMs;
        /** Evidence: matched cue pairs (reference mode) or analysed speech frames (sound mode). */
        public final int matched;
        public final int targetCues;
        /** 0..1. Below {@link #RELIABLE} the result is a suggestion for a human, not a correction. */
        public final double confidence;
        /** Typical remaining error after the correction (median absolute residual / frame size). */
        public final long residualMs;
        /** "sound" or "reference". */
        public final String method;
        /** Why a result is weak or missing (Arabic), "" when fine. */
        public final String note;

        Alignment(double factor, long offsetMs, int matched, int targetCues, double confidence, long residualMs, String method, String note) {
            this.factor = factor;
            this.offsetMs = offsetMs;
            this.matched = matched;
            this.targetCues = targetCues;
            this.confidence = Math.max(0, Math.min(1, confidence));
            this.residualMs = residualMs;
            this.method = method;
            this.note = note == null ? "" : note;
        }

        public boolean reliable() {
            return confidence >= RELIABLE;
        }

        public boolean hasDrift() {
            return Math.abs(factor - 1.0) > 0.00015;
        }

        /** True when the file is already in sync (nothing worth changing). */
        public boolean alreadyInSync() {
            return reliable() && !hasDrift() && Math.abs(offsetMs) < 120;
        }

        /** Milliseconds gained or lost per hour of film by the drift. */
        public long driftPerHourMs() {
            return Math.round((factor - 1.0) * 3600000.0);
        }

        /** "25 → 23.976" when the factor is a standard frame-rate ratio, "" otherwise. */
        public String fpsLabel() {
            return SubTools.fpsLabelForFactor(factor);
        }

        /** The corrected document. Only call for an accepted alignment. */
        public SubSync.ShiftResult apply(SubDocument target) {
            if (!hasDrift()) return SubSync.shift(target, offsetMs);
            return SubSync.stretch(target, factor, offsetMs);
        }

        public String describe() {
            if (matched == 0) return note.length() > 0 ? note : "لم يُعثر على تطابق.";
            StringBuilder b = new StringBuilder();
            if (alreadyInSync()) b.append("الترجمة متزامنة");
            else {
                b.append("الإزاحة ").append(SubSync.delayLabel(offsetMs));
                if (hasDrift()) {
                    String f = fpsLabel();
                    b.append(" · انحراف ").append(SubSync.delayLabel(driftPerHourMs())).append(" لكل ساعة");
                    if (f.length() > 0) b.append(" (").append(f).append(" fps)");
                }
            }
            b.append(" · الثقة ").append(Math.round(confidence * 100)).append("%");
            if (!reliable()) b.append(" — غير كافية للتطبيق التلقائي");
            return b.toString();
        }
    }

    public static final double RELIABLE = 0.55;

    private static Alignment none(String method, int targetCues, String note) {
        return new Alignment(1.0, 0, 0, targetCues, 0, 0, method, note);
    }

    // ------------------------------------------------------------------ 1. reference subtitle

    private static final double NTSC_FILM = 24000.0 / 1001.0;   // "23.976"
    private static final double NTSC = 30000.0 / 1001.0;        // "29.97"

    /** Standard frame-rate ratios a subtitle can be off by (source fps / film fps), exact values. */
    static final double[] FACTORS = {
            1.0,
            25.0 / NTSC_FILM, NTSC_FILM / 25.0,
            25.0 / 24.0, 24.0 / 25.0,
            24.0 / NTSC_FILM, NTSC_FILM / 24.0,
            30.0 / NTSC, NTSC / 30.0
    };

    public static Alignment alignToReference(SubDocument target, SubDocument reference) {
        return alignToReference(target, reference, 10 * 60000L);
    }

    /**
     * @param maxOffsetMs the largest shift searched in either direction (10 minutes covers a missing recap or an
     *                    extended cut's extra scene at the start)
     */
    public static Alignment alignToReference(SubDocument target, SubDocument reference, long maxOffsetMs) {
        long[] t = starts(target), r = starts(reference);
        if (t.length < 5 || r.length < 5) return none("reference", t.length, "الملفان يحتاجان خمسة مقاطع على الأقل للمطابقة.");
        final int bin = 200;
        int bins = (int) (2 * maxOffsetMs / bin) + 3;
        int[] hist = new int[bins];
        double bestF = 1.0;
        long bestOff = 0;
        int bestPeak = -1;
        for (double f : FACTORS) {
            Arrays.fill(hist, 0);
            int lo = 0;
            for (long tv : t) {
                double x = tv * f;
                while (lo < r.length && r[lo] < x - maxOffsetMs) lo++;
                for (int j = lo; j < r.length; j++) {
                    double d = r[j] - x;
                    if (d > maxOffsetMs) break;
                    hist[(int) ((d + maxOffsetMs) / bin) + 1]++;
                }
            }
            for (int b = 1; b < bins - 1; b++) {
                int p = hist[b - 1] + hist[b] + hist[b + 1];
                // prefer "no stretch" and small offsets on ties (the simplest explanation)
                if (p > bestPeak) {
                    bestPeak = p;
                    bestF = f;
                    bestOff = (long) (b - 1) * bin - maxOffsetMs + bin / 2;
                }
            }
        }
        int floor = Math.min(t.length, r.length);
        if (bestPeak < Math.max(4, floor * 0.12)) {
            return none("reference", t.length, "لا يوجد تطابق كافٍ بين توقيت الملفين (قد يكونان لنسختين مختلفتي المونتاج).");
        }

        // matched pairs under the coarse transform, one reference cue per target cue
        double a = bestF;
        double b = bestOff;
        double[] xs = new double[t.length], ys = new double[t.length];
        int n = 0;
        long mad = 0;
        for (int round = 0; round < 3; round++) {
            long window = round == 0 ? 500 : Math.max(160, Math.min(500, mad * 4));
            n = 0;
            int j = 0;
            for (long tv : t) {
                double x = tv * a + b;
                while (j + 1 < r.length && Math.abs(r[j + 1] - x) <= Math.abs(r[j] - x)) j++;
                if (Math.abs(r[j] - x) <= window) { xs[n] = tv; ys[n] = r[j]; n++; }
            }
            if (n < 5) break;
            double[] fit = leastSquares(xs, ys, n);
            double span = xs[n - 1] - xs[0];
            // a slope is only trusted when the pairs cover enough film to measure one
            if (span >= 8 * 60000.0 && fit[0] > 0.9 && fit[0] < 1.1) { a = fit[0]; b = fit[1]; }
            else b = median(xs, ys, n, a);
            mad = medianAbsResidual(xs, ys, n, a, b);
        }
        if (n < 5) return none("reference", t.length, "عدد المقاطع المتطابقة قليل جداً.");

        // snap to the exact ratio when the fit agrees with it (no floating-point drift in the saved file)
        double snapped = a;
        for (double f : FACTORS) if (Math.abs(a - f) < 0.00025) { snapped = f; break; }
        if (snapped != a) {
            double bb = median(xs, ys, n, snapped);
            long madSnap = medianAbsResidual(xs, ys, n, snapped, bb);
            if (madSnap <= mad + 25) { a = snapped; b = bb; mad = madSnap; }
        }
        double coverage = n / (double) floor;
        double conf = Math.min(1.0, coverage / 0.6) * Math.max(0.0, 1.0 - mad / 450.0);
        return new Alignment(a, Math.round(b), n, t.length, conf, mad, "reference",
                coverage < 0.35 ? "تطابق جزء من المقاطع فقط: راجع النتيجة بالمعاينة." : "");
    }

    private static long[] starts(SubDocument d) {
        int n = 0;
        for (SubCue c : d.cues) if (c.endMs > c.startMs && !c.isBlank()) n++;
        long[] out = new long[n];
        int k = 0;
        for (SubCue c : d.cues) if (c.endMs > c.startMs && !c.isBlank()) out[k++] = c.startMs;
        Arrays.sort(out);
        return out;
    }

    private static double[] leastSquares(double[] x, double[] y, int n) {
        double sx = 0, sy = 0;
        for (int i = 0; i < n; i++) { sx += x[i]; sy += y[i]; }
        double mx = sx / n, my = sy / n, sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) { double dx = x[i] - mx; sxx += dx * dx; sxy += dx * (y[i] - my); }
        if (sxx < 1e-6) return new double[]{1.0, my - mx};
        double a = sxy / sxx;
        return new double[]{a, my - a * mx};
    }

    private static double median(double[] x, double[] y, int n, double a) {
        double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = y[i] - a * x[i];
        Arrays.sort(d);
        return d[n / 2];
    }

    private static long medianAbsResidual(double[] x, double[] y, int n, double a, double b) {
        double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = Math.abs(y[i] - (a * x[i] + b));
        Arrays.sort(d);
        return Math.round(d[n / 2]);
    }

    // ------------------------------------------------------------------ 2. the film's own sound

    /** Speech-band loudness of a stretch of film: one value per frame, -1 where nothing was heard yet. */
    public static final class Activity {
        /** Media time of {@code level[0]}. */
        public final long startMs;
        public final int frameMs;
        /** 0..1 (quiet..loud relative to this film), or -1 for "no data". */
        public final float[] level;

        public Activity(long startMs, int frameMs, float[] level) {
            this.startMs = startMs;
            this.frameMs = frameMs;
            this.level = level;
        }

        public long endMs() {
            return startMs + (long) level.length * frameMs;
        }

        public int validFrames() {
            int n = 0;
            for (float v : level) if (v >= 0) n++;
            return n;
        }

        public long coveredMs() {
            return (long) validFrames() * frameMs;
        }
    }

    /**
     * Turns decoded PCM into an {@link Activity}. Feed it what the audio decoder outputs together with the media
     * time of each buffer; frames are keyed by MEDIA time, so seeking, pausing and playback speed do not matter.
     * Only two numbers per 50 ms are kept (sum of squares, sample count): the sound itself is not retained.
     * Not thread-safe: call {@link #write} from one thread and {@link #snapshot} under the same lock (see callers).
     */
    public static final class ActivityBuilder {
        private final int frameMs;
        private final int maxFrames;
        private double[] sum = new double[4096];
        private int[] count = new int[4096];
        private int used;
        private int sampleRate = 48000, channels = 2;
        private boolean floatPcm;
        // one-pole high-pass (300 Hz) then one-pole low-pass (3 kHz): the band where speech carries its energy
        private double hpA, lpB, hpPrevIn, hpPrevOut, lpPrev;
        private long lastEndUs = Long.MIN_VALUE;

        public ActivityBuilder() {
            this(50, 6L * 3600000L);
        }

        public ActivityBuilder(int frameMs, long maxMediaMs) {
            this.frameMs = Math.max(10, frameMs);
            this.maxFrames = (int) Math.min(Integer.MAX_VALUE / 2, maxMediaMs / this.frameMs);
            configure(48000, 2, false);
        }

        /** Call when the decoder's output format is known or changes. */
        public synchronized void configure(int sampleRate, int channels, boolean floatPcm) {
            this.sampleRate = Math.max(8000, sampleRate);
            this.channels = Math.max(1, channels);
            this.floatPcm = floatPcm;
            double dt = 1.0 / this.sampleRate;
            double rcHp = 1.0 / (2 * Math.PI * 300.0), rcLp = 1.0 / (2 * Math.PI * 3000.0);
            hpA = rcHp / (rcHp + dt);
            lpB = dt / (rcLp + dt);
            hpPrevIn = hpPrevOut = lpPrev = 0;
        }

        /**
         * @param presentationTimeUs media time of the first sample in {@code pcm}
         * @param pcm                16-bit little-endian (or 32-bit float) interleaved samples; position / limit are not changed
         */
        public synchronized void write(long presentationTimeUs, ByteBuffer pcm) {
            if (pcm == null || presentationTimeUs < 0) return;
            ByteBuffer b = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            int bytesPerSample = floatPcm ? 4 : 2;
            int frames = b.remaining() / (bytesPerSample * channels);
            if (frames <= 0) return;
            // a jump in media time (seek) resets the filter memory so the click of the jump is not counted
            if (lastEndUs != Long.MIN_VALUE && Math.abs(presentationTimeUs - lastEndUs) > 200000) hpPrevIn = hpPrevOut = lpPrev = 0;
            double usPerSample = 1000000.0 / sampleRate;
            double frameUs = frameMs * 1000.0;
            int idx = (int) (presentationTimeUs / frameUs);
            double nextBoundaryUs = (idx + 1) * frameUs;
            double tUs = presentationTimeUs;
            double acc = 0;
            int accN = 0;
            for (int i = 0; i < frames; i++) {
                double v = 0;
                for (int ch = 0; ch < channels; ch++) v += floatPcm ? b.getFloat() : b.getShort() / 32768.0;
                v /= channels;
                double hp = hpA * (hpPrevOut + v - hpPrevIn);
                hpPrevIn = v;
                hpPrevOut = hp;
                lpPrev += lpB * (hp - lpPrev);
                acc += lpPrev * lpPrev;
                accN++;
                tUs += usPerSample;
                if (tUs >= nextBoundaryUs) {
                    add(idx, acc, accN);
                    acc = 0;
                    accN = 0;
                    idx++;
                    nextBoundaryUs += frameUs;
                }
            }
            if (accN > 0) add(idx, acc, accN);
            lastEndUs = (long) tUs;
        }

        private void add(int idx, double s, int n) {
            if (idx < 0 || idx >= maxFrames) return;
            if (idx >= sum.length) {
                int cap = Math.min(maxFrames, Math.max(idx + 1, sum.length * 2));
                sum = Arrays.copyOf(sum, cap);
                count = Arrays.copyOf(count, cap);
            }
            sum[idx] += s;
            count[idx] += n;
            if (idx >= used) used = idx + 1;
        }

        /** Media time heard so far (frames with sound data × frame length). */
        public synchronized long coveredMs() {
            long n = 0;
            for (int i = 0; i < used; i++) if (count[i] > 0) n++;
            return n * frameMs;
        }

        public synchronized void clear() {
            Arrays.fill(sum, 0);
            Arrays.fill(count, 0);
            used = 0;
            lastEndUs = Long.MIN_VALUE;
        }

        /** Everything heard so far. */
        public synchronized Activity snapshot() {
            return snapshot(0, (long) used * frameMs);
        }

        /** The part between two media times, levels normalised to this stretch (10th percentile = 0, 95th = 1). */
        public synchronized Activity snapshot(long fromMs, long toMs) {
            int a = (int) Math.max(0, fromMs / frameMs), z = (int) Math.min(used, toMs / frameMs + 1);
            if (z <= a) return new Activity(Math.max(0, fromMs), frameMs, new float[0]);
            float[] db = new float[z - a];
            float[] valid = new float[z - a];
            int nv = 0;
            int minCount = Math.max(1, sampleRate * frameMs / 1000 / 4);   // a frame needs a quarter of its samples
            for (int i = a; i < z; i++) {
                if (count[i] >= minCount) {
                    float v = (float) (10.0 * Math.log10(sum[i] / count[i] + 1e-10));
                    db[i - a] = v;
                    valid[nv++] = v;
                } else {
                    db[i - a] = Float.NaN;
                }
            }
            float[] level = new float[z - a];
            if (nv < 20) {
                Arrays.fill(level, -1f);
                return new Activity((long) a * frameMs, frameMs, level);
            }
            float[] sorted = Arrays.copyOf(valid, nv);
            Arrays.sort(sorted);
            float lo = sorted[(int) (nv * 0.10)], hi = sorted[(int) Math.min(nv - 1, nv * 0.95)];
            float span = Math.max(3f, hi - lo);
            for (int i = 0; i < level.length; i++) {
                if (Float.isNaN(db[i])) level[i] = -1f;
                else level[i] = Math.max(0f, Math.min(1f, (db[i] - lo) / span));
            }
            return new Activity((long) a * frameMs, frameMs, level);
        }
    }

    public static Alignment alignToActivity(SubDocument target, Activity sound) {
        return alignToActivity(target, sound, 30000L);
    }

    /**
     * The delay (positive = later) at which the subtitle's "a line is on screen" pattern matches the sound best.
     *
     * @param maxOffsetMs largest delay tried in either direction
     */
    public static Alignment alignToActivity(SubDocument target, Activity sound, long maxOffsetMs) {
        int cueCount = 0;
        for (SubCue c : target.cues) if (c.endMs > c.startMs && !c.isBlank()) cueCount++;
        if (sound == null || sound.level.length == 0) return none("sound", cueCount, "لا توجد بيانات صوت بعد.");
        final int fm = sound.frameMs;
        int n = sound.level.length;
        int valid = sound.validFrames();
        if (valid * (long) fm < 60000) return none("sound", cueCount, "يلزم سماع دقيقة واحدة على الأقل من الفيلم.");

        // subtitle occupancy on a grid that extends maxOffset beyond the heard part on both sides
        int pad = (int) (maxOffsetMs / fm) + 2;
        byte[] occ = new byte[n + 2 * pad];
        long gridStart = sound.startMs - (long) pad * fm;
        int cuesInside = 0;
        for (SubCue c : target.cues) {
            if (c.endMs <= c.startMs || c.isBlank()) continue;
            long s = c.startMs - gridStart, e = c.endMs - gridStart;
            int from = (int) Math.max(0, s / fm), to = (int) Math.min(occ.length, (e + fm - 1) / fm);
            if (to <= from) continue;
            if (c.endMs > sound.startMs && c.startMs < sound.endMs()) cuesInside++;
            for (int i = from; i < to; i++) occ[i] = 1;
        }
        if (cuesInside < 6) return none("sound", cueCount, "عدد أسطر الترجمة في الجزء المسموع قليل جداً للمطابقة.");

        // z-normalise the sound over its valid frames
        double mean = 0;
        for (float v : sound.level) if (v >= 0) mean += v;
        mean /= valid;
        double var = 0;
        for (float v : sound.level) if (v >= 0) var += (v - mean) * (v - mean);
        double sd = Math.sqrt(var / valid);
        if (sd < 0.02) return none("sound", cueCount, "الصوت في هذا الجزء شبه ثابت (موسيقى أو صمت): تابع المشاهدة وأعد المحاولة.");
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = sound.level[i] < 0 ? Float.NaN : (float) ((sound.level[i] - mean) / sd);

        int lags = 2 * pad + 1;
        double[] score = new double[lags];
        for (int li = 0; li < lags; li++) {
            // delay = (li - pad) frames: the cue that starts at s is on screen at s + delay,
            // so the sound at frame i is compared with occupancy at i - delay
            int shift = pad - (li - pad);
            double sxy = 0;
            int ones = 0;
            for (int i = 0; i < n; i++) {
                if (x[i] != x[i]) continue;   // NaN
                if (occ[i + shift] != 0) { sxy += x[i]; ones++; }
            }
            // Pearson correlation of a z-scored signal with a 0/1 signal
            double p = ones / (double) valid;
            score[li] = (p <= 0.01 || p >= 0.99) ? 0 : sxy / valid / Math.sqrt(p * (1 - p));
        }
        double[] st = peakStats(score, fm);
        int best = (int) st[0];
        double z = st[2], uniqueness = st[3];
        // parabolic interpolation around the peak: better than one frame
        double frac = 0;
        if (best > 0 && best < lags - 1) {
            double l = score[best - 1], c = score[best], r = score[best + 1];
            double den = l - 2 * c + r;
            if (Math.abs(den) > 1e-12) frac = Math.max(-0.5, Math.min(0.5, 0.5 * (l - r) / den));
        }
        long offset = Math.round((best - pad + frac) * fm);
        boolean edge = best <= 1 || best >= lags - 2;
        double conf = confidence(score[best], z, uniqueness, valid * (long) fm, edge);
        String note = edge ? "أفضل تطابق عند حدّ نطاق البحث: الإزاحة الحقيقية قد تكون أكبر." : conf < RELIABLE ? "التطابق مع الصوت ضعيف (موسيقى / مؤثرات كثيرة): تابع المشاهدة لدقائق ثم أعد المحاولة." : "";
        return new Alignment(1.0, offset, valid, cueCount, conf, fm / 2, "sound", note);
    }

    /**
     * {best index, best score, z, uniqueness}. z compares the peak with the scores FAR from it (more than 3 s away):
     * near the peak the curve is high by construction (a line is seconds long), so those lags say nothing about chance.
     */
    static double[] peakStats(double[] score, int frameMs) {
        int lags = score.length;
        int best = 0;
        for (int li = 1; li < lags; li++) if (score[li] > score[best]) best = li;
        int near = Math.max(1, 3000 / frameMs);
        double m = 0;
        int nf = 0;
        for (int li = 0; li < lags; li++) if (Math.abs(li - best) > near) { m += score[li]; nf++; }
        if (nf < 10) return new double[]{best, score[best], 0, 0};
        m /= nf;
        double v2 = 0;
        for (int li = 0; li < lags; li++) if (Math.abs(li - best) > near) v2 += (score[li] - m) * (score[li] - m);
        double sd = Math.sqrt(v2 / nf);
        double z = sd < 1e-9 ? 0 : (score[best] - m) / sd;
        // a second, separate peak almost as high means the answer is ambiguous
        double second = Double.NEGATIVE_INFINITY;
        for (int li = 0; li < lags; li++) if (Math.abs(li - best) > near && score[li] > second) second = score[li];
        double uniqueness = score[best] <= 0 ? 0 : Math.max(0, 1.0 - Math.max(0, second) / score[best]);
        return new double[]{best, score[best], z, uniqueness};
    }

    /**
     * 0..1 from the independent signs of a real match: the peak stands far above what chance produces at other
     * delays (z), no other delay comes close (uniqueness), and the correlation itself is not negligible.
     *
     * Calibrated on SYNTHETIC film sound only (SubToolsTest / the numbers below); real films have not been measured,
     * which is why callers present the result as a suggestion with a preview and never apply it by themselves.
     * True delay: z 4.3 (4 min heard) … 10 (12 min), uniqueness 0.50 … 0.61. Unrelated sound: z ≤ 2.8, uniqueness ≤ 0.30.
     */
    static double confidence(double best, double z, double uniqueness, long heardMs, boolean edge) {
        double cz = Math.max(0, Math.min(1, (z - 3.2) / 1.6));
        double cu = Math.max(0, Math.min(1, (uniqueness - 0.30) / 0.15));
        double cb = Math.max(0, Math.min(1, best / 0.06));
        double conf = Math.min(cz, cu) * cb;
        if (edge) conf *= 0.3;
        // more film heard = more trust (full trust from four minutes)
        conf *= Math.min(1.0, heardMs / 240000.0 * 0.5 + 0.5);
        return conf;
    }

    /** One window of the film and the delay found there. */
    public static final class WindowResult {
        public final long centreMs;
        public final Alignment alignment;

        public WindowResult(long centreMs, Alignment alignment) {
            this.centreMs = centreMs;
            this.alignment = alignment;
        }
    }

    /**
     * Combines the delays found in several windows of the film. One reliable window gives an offset; two or more
     * reliable windows far enough apart give a factor as well (a delay that grows through the film = wrong frame
     * rate). Windows that disagree with the fit are a sign of a different cut and lower the confidence.
     */
    public static Alignment fitWindows(List<WindowResult> windows, int targetCues) {
        List<WindowResult> ok = new ArrayList<WindowResult>();
        if (windows != null) for (WindowResult w : windows) if (w != null && w.alignment != null && w.alignment.reliable()) ok.add(w);
        if (ok.isEmpty()) return none("sound", targetCues, "لم يُعثر على تطابق موثوق مع صوت الفيلم.");
        Collections.sort(ok, new java.util.Comparator<WindowResult>() {
            @Override public int compare(WindowResult a, WindowResult b) {
                return a.centreMs < b.centreMs ? -1 : a.centreMs > b.centreMs ? 1 : 0;
            }
        });
        int frames = 0;
        double conf = 1;
        for (WindowResult w : ok) { frames += w.alignment.matched; conf = Math.min(conf, w.alignment.confidence); }
        long span = ok.get(ok.size() - 1).centreMs - ok.get(0).centreMs;
        if (ok.size() == 1 || span < 10 * 60000L) {
            long[] offs = new long[ok.size()];
            for (int i = 0; i < offs.length; i++) offs[i] = ok.get(i).alignment.offsetMs;
            Arrays.sort(offs);
            long spread = offs[offs.length - 1] - offs[0];
            return new Alignment(1.0, offs[offs.length / 2], frames, targetCues, spread > 400 ? conf * 0.5 : conf, 25, "sound",
                    spread > 400 ? "النوافذ القريبة أعطت إزاحات مختلفة: راجع بالمعاينة." : "");
        }
        // subtitle time s is heard at film time s + offset(s): film = s × a + b
        double[] xs = new double[ok.size()], ys = new double[ok.size()];
        for (int i = 0; i < ok.size(); i++) {
            long delay = ok.get(i).alignment.offsetMs;
            xs[i] = ok.get(i).centreMs - delay;      // subtitle timeline
            ys[i] = ok.get(i).centreMs;              // film timeline
        }
        double[] fit = leastSquares(xs, ys, ok.size());
        double a = fit[0], b = fit[1];
        long worst = 0;
        for (int i = 0; i < ok.size(); i++) worst = Math.max(worst, Math.round(Math.abs(ys[i] - (a * xs[i] + b))));
        if (Math.abs(a - 1.0) < 0.00015) {
            a = 1.0;
            double[] d = new double[ok.size()];
            for (int i = 0; i < d.length; i++) d[i] = ys[i] - xs[i];
            Arrays.sort(d);
            b = d[d.length / 2];
        } else {
            for (double f : FACTORS) {
                if (Math.abs(a - f) < 0.0004) {
                    double sum = 0;
                    for (int i = 0; i < ok.size(); i++) sum += ys[i] - f * xs[i];
                    a = f;
                    b = sum / ok.size();
                    break;
                }
            }
        }
        if (!(a > 0.9 && a < 1.1)) return none("sound", targetCues, "النوافذ غير متسقة: الملف لمونتاج مختلف على الأرجح.");
        String note = worst > 600 ? "بعض النوافذ لا توافق التصحيح الخطي (مشهد محذوف أو مضاف؟): راجع بالمعاينة." : "";
        return new Alignment(a, Math.round(b), frames, targetCues, worst > 600 ? conf * 0.5 : conf, Math.max(25, worst / 2), "sound", note);
    }
}
