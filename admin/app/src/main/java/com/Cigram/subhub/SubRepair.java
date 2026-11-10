package com.Cigram.subhub;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CigramSubtitleHub — SRT Repair, Overlap Detector and Missing Lines Detector.
 *
 * <b>SRT Repair</b> («إصلاح ملفات SRT التالفة») works in two passes:
 * <ol>
 *   <li>a TEXT pass ({@link #salvage}) that rewrites timing lines the tolerant parser would have to skip: Arabic-Indic
 *   digits, exotic arrows («→», «=>», «—>»), «;» or «.» between hours / minutes / seconds, the letter O for a zero, a
 *   missing end time, a cue number or the cue text glued to the timing line, HTML line breaks;</li>
 *   <li>a STRUCTURE pass on the parsed cues: end before start, zero-length cues, cues out of order, unintended
 *   overlaps, absurdly long cues, empty cues, exact duplicates — then the file is renumbered 1..n.</li>
 * </ol>
 * Every change is counted in the {@link Report}. A repair never invents dialogue and never shifts the whole file:
 * only the single broken value of a broken cue is rebuilt (from its neighbours and its own reading time).
 *
 * <b>Overlap Detector</b> ({@link #overlaps}) and <b>Missing Lines Detector</b> ({@link #missing}) only LOOK; their
 * fixes ({@link #fixOverlaps}) are separate, explicit calls.
 *
 * Pure Java, shared byte-identical between Cigram and Cigram Admin.
 */
public final class SubRepair {

    private SubRepair() {}

    // ------------------------------------------------------------------ options / report

    public static final class Options {
        /** A repaired cue is shown at least this long. */
        public long minDurationMs = 500;
        /** A cue longer than this is treated as a broken end time. */
        public long maxDurationMs = 20000;
        /** Gap left between two cues when an overlap is closed (two frames at 24 fps). */
        public long gapMs = 84;
        public boolean sort = true;
        public boolean closeOverlaps = true;
        public boolean dropEmpty = true;
        public boolean dropExactDuplicates = true;
        /**
         * Frame rate for timestamps written as frames («00:01:02:12»). 0 = unknown: such cues stay skipped and are
         * reported (the rate is never guessed).
         */
        public double framesPerSecond = 0;
    }

    public static final class Report {
        public final SubDocument document;
        /** The repaired file as a standard UTF-8 SRT (three millisecond digits, numbered 1..n). */
        public final String srt;
        public final String encoding;
        public final int cuesBefore;
        public final int cuesAfter;
        /** Timing lines rewritten by the text pass. */
        public final int timingLinesRewritten;
        /** Cues the parser still could not read (they are not in the result). */
        public final int unreadable;
        public final int endBeforeStartFixed;
        public final int zeroDurationFixed;
        public final int tooLongFixed;
        public final int reordered;
        public final int overlapsClosed;
        public final int overlapsMerged;
        public final int overlapsLeft;
        public final int emptyDropped;
        public final int duplicatesDropped;
        /** Arabic lines for the admin's report. */
        public final List<String> notes;

        Report(SubDocument d, String srt, String enc, int before, int rewritten, int unreadable, int ebs, int zero,
               int tooLong, int reordered, int closed, int merged, int left, int empty, int dups, List<String> notes) {
            this.document = d;
            this.srt = srt;
            this.encoding = enc;
            this.cuesBefore = before;
            this.cuesAfter = d.size();
            this.timingLinesRewritten = rewritten;
            this.unreadable = unreadable;
            this.endBeforeStartFixed = ebs;
            this.zeroDurationFixed = zero;
            this.tooLongFixed = tooLong;
            this.reordered = reordered;
            this.overlapsClosed = closed;
            this.overlapsMerged = merged;
            this.overlapsLeft = left;
            this.emptyDropped = empty;
            this.duplicatesDropped = dups;
            this.notes = Collections.unmodifiableList(notes);
        }

        public int totalFixes() {
            return timingLinesRewritten + endBeforeStartFixed + zeroDurationFixed + tooLongFixed + reordered
                    + overlapsClosed + overlapsMerged + emptyDropped + duplicatesDropped;
        }

        public boolean changed() {
            return totalFixes() > 0;
        }

        public String summary() {
            if (cuesAfter == 0) return "لم يُعثر على أي مقطع صالح في الملف.";
            if (!changed() && unreadable == 0) return "الملف سليم · " + cuesAfter + " مقطعاً · لا يحتاج إصلاحاً";
            return "أُصلح " + totalFixes() + " موضعاً · " + cuesAfter + " مقطعاً" + (unreadable > 0 ? " · " + unreadable + " مقطعاً تعذّرت قراءته" : "");
        }
    }

    // ------------------------------------------------------------------ SRT Repair

    public static Report repair(byte[] raw) {
        return repair(raw, new Options());
    }

    public static Report repair(byte[] raw, Options o) {
        SubEncoding.Fix f = SubEncoding.fix(raw);
        Report r = repair(f.text, f.encoding, o);
        if (f.notes.isEmpty()) return r;
        List<String> notes = new ArrayList<String>(f.notes);
        notes.addAll(r.notes);
        return new Report(r.document, r.srt, r.encoding, r.cuesBefore, r.timingLinesRewritten, r.unreadable, r.endBeforeStartFixed,
                r.zeroDurationFixed, r.tooLongFixed, r.reordered, r.overlapsClosed, r.overlapsMerged, r.overlapsLeft,
                r.emptyDropped, r.duplicatesDropped, notes);
    }

    public static Report repair(String text) {
        return repair(text, "UTF-8", new Options());
    }

    public static Report repair(String text, String encoding, Options opt) {
        Options o = opt == null ? new Options() : opt;
        List<String> notes = new ArrayList<String>();
        int[] rewritten = new int[1];
        String salvaged = salvage(text == null ? "" : text, o.framesPerSecond, rewritten);
        SubDocument parsed = SubCodec.parse(salvaged, encoding);
        int before = parsed.size();
        if (rewritten[0] > 0) notes.add("أُعيدت كتابة " + rewritten[0] + " سطر توقيت بصيغة SRT القياسية.");
        if (parsed.skippedCues > 0) notes.add(parsed.skippedCues + " مقطعاً لم يمكن قراءة توقيته (لم يُخمَّن)" + (o.framesPerSecond <= 0 ? " — إن كان التوقيت مكتوباً بالإطارات فحدّد معدل الإطارات." : "."));

        List<SubCue> cues = new ArrayList<SubCue>(parsed.cues);

        // 1. empty cues and exact duplicates
        int empty = 0, dups = 0;
        if (o.dropEmpty || o.dropExactDuplicates) {
            List<SubCue> keep = new ArrayList<SubCue>(cues.size());
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            for (SubCue c : cues) {
                if (o.dropEmpty && c.isBlank()) { empty++; continue; }
                if (o.dropExactDuplicates) {
                    String key = c.startMs + "|" + c.endMs + "|" + c.text;
                    if (!seen.add(key)) { dups++; continue; }
                }
                keep.add(c);
            }
            cues = keep;
        }

        // 2. order (stable: equal starts keep the source order)
        int reordered = 0;
        if (o.sort) {
            long max = Long.MIN_VALUE;
            for (SubCue c : cues) {
                if (c.startMs < max) reordered++;
                max = Math.max(max, c.startMs);
            }
            if (reordered > 0) {
                Collections.sort(cues, new Comparator<SubCue>() {
                    @Override public int compare(SubCue a, SubCue b) {
                        if (a.startMs != b.startMs) return a.startMs < b.startMs ? -1 : 1;
                        return a.index - b.index;
                    }
                });
            }
        }

        // 3. per-cue timing: end before start, zero length, absurd length
        int ebs = 0, zero = 0, tooLong = 0;
        for (int k = 0; k < cues.size(); k++) {
            SubCue c = cues.get(k);
            long nextStart = nextStartAfter(cues, k);
            long s = c.startMs, e = c.endMs;
            if (e < s) {
                // swapped stamps: accept the swap when it gives a sane cue, otherwise rebuild the end
                if (s - e <= o.maxDurationMs && (k == 0 || e >= cues.get(k - 1).startMs)) { long t = s; s = e; e = t; }
                else e = rebuiltEnd(c, s, nextStart, o);
                ebs++;
            } else if (e == s) {
                e = rebuiltEnd(c, s, nextStart, o);
                zero++;
            } else if (e - s > o.maxDurationMs) {
                e = rebuiltEnd(c, s, nextStart, o);
                tooLong++;
            }
            if (s != c.startMs || e != c.endMs) cues.set(k, c.withTimes(s, e));
        }
        if (reordered > 0 && ebs > 0) {
            // a swap can change a start: keep the order strict
            Collections.sort(cues, new Comparator<SubCue>() {
                @Override public int compare(SubCue a, SubCue b) {
                    if (a.startMs != b.startMs) return a.startMs < b.startMs ? -1 : 1;
                    return a.index - b.index;
                }
            });
        }

        // 4. overlaps
        int closed = 0, merged = 0, left = 0;
        if (o.closeOverlaps) {
            int[] counts = new int[3];
            cues = closeOverlaps(cues, o.gapMs, o.minDurationMs, counts);
            closed = counts[0];
            merged = counts[1];
            left = counts[2];
        } else {
            left = overlaps(parsed.withCues(cues), 0).size();
        }

        if (empty > 0) notes.add("حُذف " + empty + " مقطعاً فارغاً.");
        if (dups > 0) notes.add("حُذف " + dups + " مقطعاً مكرراً حرفياً (نفس التوقيت والنص).");
        if (reordered > 0) notes.add("أُعيد ترتيب " + reordered + " مقطعاً كان خارج التسلسل الزمني.");
        if (ebs > 0) notes.add("أُصلح " + ebs + " مقطعاً كانت نهايته قبل بدايته.");
        if (zero > 0) notes.add("أُعطي " + zero + " مقطعاً بلا مدة مدةَ قراءة مناسبة.");
        if (tooLong > 0) notes.add("قُصّر " + tooLong + " مقطعاً بمدة غير معقولة (أكثر من " + (o.maxDurationMs / 1000) + " ث).");
        if (closed > 0) notes.add("أُغلق " + closed + " تداخلاً بين مقطعين متتاليين.");
        if (merged > 0) notes.add("دُمج " + merged + " مقطعاً مكرر النص مع المقطع الذي قبله.");
        if (left > 0) notes.add(left + " تداخلاً تُرك كما هو (حوار متزامن مقصود أو لا يمكن إغلاقه دون إخفاء نص).");

        // renumber: index = position in the repaired file, id = the same number
        List<SubCue> numbered = new ArrayList<SubCue>(cues.size());
        for (int k = 0; k < cues.size(); k++) {
            SubCue c = cues.get(k);
            numbered.add(new SubCue(k + 1, String.valueOf(k + 1), c.startMs, c.endMs, c.text, c.settings));
        }
        SubDocument doc = new SubDocument(SubDocument.Format.SRT, numbered, "", encoding, "", parsed.warnings, parsed.skippedCues, 0, 0);
        return new Report(doc, SubCodec.toSrt(doc), encoding, before, rewritten[0], parsed.skippedCues, ebs, zero, tooLong,
                reordered, closed, merged, left, empty, dups, notes);
    }

    private static long nextStartAfter(List<SubCue> cues, int k) {
        long s = cues.get(k).startMs;
        for (int j = k + 1; j < cues.size(); j++) if (cues.get(j).startMs > s) return cues.get(j).startMs;
        return Long.MAX_VALUE;
    }

    /** End for a cue whose end is unusable: its own reading time, never past the next cue. */
    private static long rebuiltEnd(SubCue c, long start, long nextStart, Options o) {
        long want = start + readingTimeMs(c.plainText());
        if (nextStart != Long.MAX_VALUE) want = Math.min(want, nextStart - o.gapMs);
        return Math.max(want, start + Math.min(o.minDurationMs, nextStart == Long.MAX_VALUE ? o.minDurationMs : Math.max(1, nextStart - start - 1)));
    }

    /** Comfortable reading time: 60 ms per character plus half a second, between 1 and 7 seconds. */
    public static long readingTimeMs(String plain) {
        int chars = 0;
        if (plain != null) for (int i = 0; i < plain.length(); i++) if (!Character.isWhitespace(plain.charAt(i))) chars++;
        return Math.max(1000, Math.min(7000, 500 + chars * 60L));
    }

    // ------------------------------------------------------------------ text pass

    /** h:m:s with ':' or ';' between the parts, optional fraction after ',' '.' ';' or ':'. */
    private static final Pattern STAMP_COLON = Pattern.compile("(\\d{1,3})\\s*[:;]\\s*(\\d{1,2})\\s*[:;]\\s*(\\d{1,2})(?:\\s*([,.;:])\\s*(\\d{1,9}))?");
    /** «00.01.02,500»: dots between the parts (some DVD rippers). */
    private static final Pattern STAMP_DOTS = Pattern.compile("(\\d{1,3})\\.(\\d{2})\\.(\\d{2})([,.])(\\d{1,3})(?!\\d)");
    /** «01:02,500»: no hours. */
    private static final Pattern STAMP_SHORT = Pattern.compile("(\\d{1,2})\\s*:\\s*(\\d{1,2})\\s*([,.])\\s*(\\d{1,9})");
    private static final String ARROW = "(?:-{1,3}\\s*>|=+\\s*>|→|⟶|—\\s*>|–\\s*>|\\bto\\b|>{2,})";
    private static final Pattern HAS_ARROW = Pattern.compile(ARROW);
    private static final Pattern DIGITISH = Pattern.compile("[0-9OoIl|٠-٩۰-۹]");

    /**
     * Rewrites damaged timing lines into "HH:MM:SS,mmm --> HH:MM:SS,mmm". A line is only touched when it clearly IS a
     * timing line (an arrow between two stamp-like groups, or a lone stamp followed by an arrow); dialogue is never
     * rewritten. Returns the new text; {@code count[0]} = lines rewritten.
     */
    static String salvage(String input, double fps, int[] count) {
        String text = input.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "");
        while (text.length() > 0 && text.charAt(0) == '﻿') text = text.substring(1);
        // HTML line breaks inside cue text
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 64);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String fixed = salvageLine(line, fps);
            if (fixed != null && !fixed.equals(line)) {
                count[0]++;
                out.append(fixed);
            } else {
                out.append(line);
            }
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    /** The standard form of a damaged timing line, or null when the line is not a timing line / is already fine. */
    static String salvageLine(String line, double fps) {
        if (line == null || line.length() < 8 || line.length() > 400) return null;
        if (!HAS_ARROW.matcher(line).find()) return null;
        if (!DIGITISH.matcher(line).find()) return null;
        if (SubCodec.TIMING.matcher(line).matches() && line.indexOf("-->") >= 0 && !hasFrames(line) && !hasGluedText(line)) return null;
        // split around the arrow
        Matcher am = HAS_ARROW.matcher(line);
        if (!am.find()) return null;
        String left = line.substring(0, am.start());
        String right = line.substring(am.end());
        String leftNorm = normalizeDigits(left);
        String rightNorm = normalizeDigits(right);
        long[] a = lastStamp(leftNorm, fps);
        long[] b = firstStamp(rightNorm, fps);
        if (a == null) return null;                      // no start time: not a timing line we can prove
        StringBuilder sb = new StringBuilder();
        // a cue number glued in front ("12 00:00:01,000 --> ...") goes on its own line
        String before = leftNorm.substring(0, (int) a[1]).trim();
        if (before.matches("\\d{1,7}")) sb.append(before).append('\n');
        else if (before.length() > 0) return null;       // words before the start stamp: dialogue that mentions an arrow
        sb.append(SubCodec.stamp(a[0], ',')).append(" --> ");
        String tail = "";
        if (b != null) {
            sb.append(SubCodec.stamp(b[0], ','));
            tail = rightNorm.substring((int) b[2]).trim();
        } else {
            if (rightNorm.trim().length() > 0 && !rightNorm.trim().matches("[0-9:.,;\\s]*")) return null;
            sb.append(SubCodec.stamp(a[0], ','));        // missing end: zero length, rebuilt by the structure pass
        }
        if (tail.length() > 0) {
            // text glued to the timing line; SRT has no cue settings, VTT settings look like "align:start"
            if (tail.matches("(?:[a-z]+:[^\\s]+\\s*)+")) sb.append(' ').append(tail);
            else sb.append('\n').append(right.substring(right.length() - Math.min(right.length(), tail.length())).trim());
        }
        return sb.toString();
    }

    /** A timestamp written with a frame count («00:01:02:12»): two digits after a third ':' / ';'. */
    private static boolean hasFrames(String line) {
        Matcher m = STAMP_COLON.matcher(line);
        while (m.find()) {
            String sep = m.group(4), fr = m.group(5);
            if (fr != null && fr.length() == 2 && (":".equals(sep) || ";".equals(sep))) return true;
        }
        return false;
    }

    private static boolean hasGluedText(String line) {
        Matcher m = SubCodec.TIMING.matcher(line);
        if (!m.matches()) return false;
        String rest = m.group(11) == null ? "" : m.group(11).trim();
        return rest.length() > 0 && !rest.matches("(?:[a-z]+:[^\\s]+\\s*)+");
    }

    /** Arabic-Indic / Persian digits → ASCII, and O / l / | standing between digits and colons → 0 / 1. */
    static String normalizeDigits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0660 && c <= 0x0669) c = (char) ('0' + (c - 0x0660));
            else if (c >= 0x06F0 && c <= 0x06F9) c = (char) ('0' + (c - 0x06F0));
            else if (c == '٫' || c == '،') c = ',';   // Arabic decimal separator / Arabic comma
            b.append(c);
        }
        // letter look-alikes only where a neighbour is a digit or a colon
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            if (c != 'O' && c != 'o' && c != 'l' && c != 'I' && c != '|') continue;
            char p = i > 0 ? b.charAt(i - 1) : ' ', n = i + 1 < b.length() ? b.charAt(i + 1) : ' ';
            boolean pd = Character.isDigit(p) || p == ':' || p == ',' || p == '.', nd = Character.isDigit(n) || n == ':' || n == ',' || n == '.';
            boolean edgeP = p == ' ' || p == '\t', edgeN = n == ' ' || n == '\t';
            if ((pd && nd) || (pd && edgeN) || (edgeP && nd)) b.setCharAt(i, c == 'O' || c == 'o' ? '0' : '1');
        }
        return b.toString();
    }

    /** {ms, startIndex, endIndex} of the LAST stamp in s, or null (also null when it is in frames and fps is unknown). */
    private static long[] lastStamp(String s, double fps) {
        List<long[]> all = stamps(s, fps);
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private static long[] firstStamp(String s, double fps) {
        List<long[]> all = stamps(s, fps);
        return all.isEmpty() ? null : all.get(0);
    }

    /** Stamps of one side of the arrow, left to right. The first pattern that matches at all decides the reading. */
    private static List<long[]> stamps(String s, double fps) {
        List<long[]> out = new ArrayList<long[]>(2);
        Matcher m = STAMP_COLON.matcher(s);
        boolean any = false;
        while (m.find()) {
            any = true;
            long h = Long.parseLong(m.group(1)), mi = Long.parseLong(m.group(2)), se = Long.parseLong(m.group(3));
            if (mi >= 60 || se >= 60) continue;
            String sep = m.group(4), fr = m.group(5);
            long ms = 0;
            if (fr != null) {
                boolean frames = fr.length() == 2 && (":".equals(sep) || ";".equals(sep));
                if (frames) {
                    if (!(fps > 1) || Long.parseLong(fr) >= Math.ceil(fps)) continue;   // unknown rate: never guessed
                    ms = Math.round(Long.parseLong(fr) * 1000.0 / fps);
                } else {
                    ms = fraction(fr);
                }
            }
            out.add(new long[]{((h * 60 + mi) * 60 + se) * 1000 + ms, m.start(), m.end()});
        }
        if (any) return out;
        m = STAMP_DOTS.matcher(s);
        while (m.find()) {
            long h = Long.parseLong(m.group(1)), mi = Long.parseLong(m.group(2)), se = Long.parseLong(m.group(3));
            if (mi >= 60 || se >= 60) continue;
            out.add(new long[]{((h * 60 + mi) * 60 + se) * 1000 + fraction(m.group(5)), m.start(), m.end()});
        }
        if (!out.isEmpty()) return out;
        m = STAMP_SHORT.matcher(s);
        while (m.find()) {
            long mi = Long.parseLong(m.group(1)), se = Long.parseLong(m.group(2));
            if (se >= 60) continue;
            out.add(new long[]{(mi * 60 + se) * 1000 + fraction(m.group(4)), m.start(), m.end()});
        }
        return out;
    }

    /** "5" → 500, "50" → 500, "500" → 500, "5004" → 500 (the digits are a decimal fraction of a second). */
    private static long fraction(String fr) {
        if (fr.length() <= 3) {
            long ms = Long.parseLong(fr);
            for (int i = fr.length(); i < 3; i++) ms *= 10;
            return ms;
        }
        return Math.min(999, Math.round(Double.parseDouble("0." + fr) * 1000.0));
    }

    // ------------------------------------------------------------------ Overlap Detector

    public static final class Overlap {
        /** 1-based positions (in start order) of the two cues, and their source indices. */
        public final int first, second;
        public final int firstIndex, secondIndex;
        public final long overlapMs;
        /** True when both cues carry the same text (a duplicated cue, safe to merge). */
        public final boolean sameText;
        public final String firstText, secondText;
        public final long atMs;

        Overlap(int first, int second, SubCue a, SubCue b) {
            this.first = first;
            this.second = second;
            this.firstIndex = a.index;
            this.secondIndex = b.index;
            this.overlapMs = Math.min(a.endMs, b.endMs) - b.startMs;
            this.sameText = norm(a.plainText()).equals(norm(b.plainText()));
            this.firstText = a.plainText();
            this.secondText = b.plainText();
            this.atMs = b.startMs;
        }

        @Override public String toString() {
            return "#" + firstIndex + " ↔ #" + secondIndex + " " + overlapMs + "ms @" + SubCodec.stamp(atMs, ',');
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static List<SubCue> byStart(SubDocument d) {
        List<SubCue> c = new ArrayList<SubCue>(d.size());
        for (SubCue x : d.cues) if (x.endMs > x.startMs) c.add(x);
        Collections.sort(c, new Comparator<SubCue>() {
            @Override public int compare(SubCue a, SubCue b) {
                if (a.startMs != b.startMs) return a.startMs < b.startMs ? -1 : 1;
                return a.index - b.index;
            }
        });
        return c;
    }

    /**
     * Every pair of cues that are on screen together for more than {@code toleranceMs}. Works on unsorted files (cues
     * are compared in start order) and finds a long cue that covers several later ones, not only neighbours.
     */
    public static List<Overlap> overlaps(SubDocument d, long toleranceMs) {
        List<SubCue> c = byStart(d);
        List<Overlap> out = new ArrayList<Overlap>();
        for (int i = 0; i < c.size(); i++) {
            SubCue a = c.get(i);
            for (int j = i + 1; j < c.size(); j++) {
                SubCue b = c.get(j);
                if (b.startMs >= a.endMs) break;
                if (Math.min(a.endMs, b.endMs) - b.startMs > toleranceMs) out.add(new Overlap(i + 1, j + 1, a, b));
                if (out.size() >= 5000) return out;
            }
        }
        return out;
    }

    /** Total time during which two or more cues are on screen together. */
    public static long overlappedMs(List<Overlap> list) {
        long t = 0;
        for (Overlap o : list) t += Math.max(0, o.overlapMs);
        return t;
    }

    /**
     * Closes overlaps: the earlier cue ends {@code gapMs} before the next one starts. A cue with the same text as the
     * one before it is merged into it. An overlap is LEFT when closing it would show the earlier cue for less than
     * {@code minDurationMs} (simultaneous dialogue). Order and texts are never changed.
     */
    public static SubDocument fixOverlaps(SubDocument d, long gapMs, long minDurationMs) {
        return d.withCues(closeOverlaps(byStart(d), gapMs, minDurationMs, new int[3]));
    }

    /** @param counts out: [0] closed, [1] merged, [2] left. Input must be in start order. */
    static List<SubCue> closeOverlaps(List<SubCue> sorted, long gapMs, long minDurationMs, int[] counts) {
        List<SubCue> out = new ArrayList<SubCue>(sorted.size());
        for (SubCue cur : sorted) {
            if (out.isEmpty()) { out.add(cur); continue; }
            SubCue prev = out.get(out.size() - 1);
            if (cur.startMs >= prev.endMs) { out.add(cur); continue; }
            if (norm(prev.plainText()).equals(norm(cur.plainText()))) {
                out.set(out.size() - 1, prev.withTimes(prev.startMs, Math.max(prev.endMs, cur.endMs)));
                counts[1]++;
                continue;
            }
            long newEnd = cur.startMs - gapMs;
            if (newEnd - prev.startMs >= minDurationMs) {
                out.set(out.size() - 1, prev.withTimes(prev.startMs, newEnd));
                counts[0]++;
            } else {
                counts[2]++;
            }
            out.add(cur);
        }
        return out;
    }

    // ------------------------------------------------------------------ Missing Lines Detector

    public static final class Gap {
        public final long fromMs, toMs;
        /** Source indices of the cues around the hole (0 = start / end of the film). */
        public final int afterIndex, beforeIndex;

        Gap(long from, long to, int after, int before) {
            this.fromMs = from;
            this.toMs = to;
            this.afterIndex = after;
            this.beforeIndex = before;
        }

        public long lengthMs() {
            return toMs - fromMs;
        }
    }

    public static final class Missing {
        /** Cues that exist but show nothing (source indices). */
        public final List<Integer> blankCues;
        /** Cue numbers missing from the file's own numbering («1, 2, 4» → 3). */
        public final List<Integer> missingNumbers;
        /** Timing blocks the parser had to skip. */
        public final int unreadableBlocks;
        /** Cues with letters but no Arabic letters, in a file that is otherwise Arabic (source indices). */
        public final List<Integer> untranslated;
        /** Unusually long stretches without any subtitle. */
        public final List<Gap> silentGaps;
        /** Reference cues (their source indices) with no line of this file anywhere near them. */
        public final List<Integer> missingAgainstReference;
        /** Text of those reference cues, same order (for the list on screen). */
        public final List<String> missingReferenceTexts;
        /** True when the file stops long before the film does. */
        public final boolean truncatedTail;
        public final long lastCueEndMs;
        public final long mediaDurationMs;
        public final List<String> notes;

        Missing(List<Integer> blank, List<Integer> numbers, int unreadable, List<Integer> untranslated, List<Gap> gaps,
                List<Integer> ref, List<String> refTexts, boolean truncated, long lastEnd, long media, List<String> notes) {
            this.blankCues = Collections.unmodifiableList(blank);
            this.missingNumbers = Collections.unmodifiableList(numbers);
            this.unreadableBlocks = unreadable;
            this.untranslated = Collections.unmodifiableList(untranslated);
            this.silentGaps = Collections.unmodifiableList(gaps);
            this.missingAgainstReference = Collections.unmodifiableList(ref);
            this.missingReferenceTexts = Collections.unmodifiableList(refTexts);
            this.truncatedTail = truncated;
            this.lastCueEndMs = lastEnd;
            this.mediaDurationMs = media;
            this.notes = Collections.unmodifiableList(notes);
        }

        public int total() {
            return blankCues.size() + missingNumbers.size() + unreadableBlocks + untranslated.size() + silentGaps.size()
                    + missingAgainstReference.size() + (truncatedTail ? 1 : 0);
        }

        public boolean clean() {
            return total() == 0;
        }

        public String summary() {
            if (clean()) return "لا أسطر فارغة أو ناقصة";
            StringBuilder b = new StringBuilder();
            add(b, blankCues.size(), "مقطع فارغ");
            add(b, missingNumbers.size(), "رقم ناقص من التسلسل");
            add(b, unreadableBlocks, "مقطع تالف");
            add(b, untranslated.size(), "سطر غير مترجم");
            add(b, silentGaps.size(), "فجوة صامتة طويلة");
            add(b, missingAgainstReference.size(), "سطر ناقص مقارنة بالأصل");
            if (truncatedTail) add(b, 1, "نهاية مبتورة");
            return b.toString();
        }

        private static void add(StringBuilder b, int n, String label) {
            if (n <= 0) return;
            if (b.length() > 0) b.append(" · ");
            b.append(n).append(' ').append(label);
        }
    }

    /**
     * Looks for lines that are empty or missing.
     *
     * @param reference       the original-language file with the SAME timing (the translation source), or null
     * @param mediaDurationMs length of the film, 0 when unknown (the tail check is then skipped)
     */
    public static Missing missing(SubDocument d, SubDocument reference, long mediaDurationMs) {
        List<String> notes = new ArrayList<String>();
        List<Integer> blank = new ArrayList<Integer>();
        List<Integer> untranslated = new ArrayList<Integer>();
        StringBuilder all = new StringBuilder();
        for (SubCue c : d.cues) {
            if (c.isBlank()) blank.add(c.index);
            else if (all.length() < 200000) all.append(c.plainText()).append('\n');
        }
        boolean arabicFile = SubCodec.arabicShare(all.toString()) >= 0.6;
        if (arabicFile) {
            for (SubCue c : d.cues) {
                if (c.isBlank()) continue;
                String p = c.plainText();
                if (letters(p) >= 4 && !SubCodec.hasArabic(p)) untranslated.add(c.index);
            }
        }

        // numbering holes (only when the file numbers its cues with increasing integers)
        List<Integer> numbers = new ArrayList<Integer>();
        int prevNum = -1, numbered = 0, increasing = 0;
        for (SubCue c : d.cues) {
            int n = parseNumber(c.id);
            if (n < 0) continue;
            numbered++;
            if (prevNum >= 0 && n > prevNum) increasing++;
            prevNum = n;
        }
        if (numbered >= 3 && increasing >= (numbered - 1) * 0.9) {
            prevNum = -1;
            for (SubCue c : d.cues) {
                int n = parseNumber(c.id);
                if (n < 0) continue;
                if (prevNum >= 0 && n > prevNum + 1 && n - prevNum <= 50) {
                    for (int k = prevNum + 1; k < n && numbers.size() < 500; k++) numbers.add(k);
                }
                prevNum = n;
            }
        }

        // silent stretches
        List<SubCue> sorted = byStart(d);
        List<Gap> gaps = new ArrayList<Gap>();
        long lastEnd = 0;
        if (sorted.size() >= 8) {
            List<Long> lens = new ArrayList<Long>(sorted.size());
            long end = sorted.get(0).endMs;
            for (int k = 1; k < sorted.size(); k++) {
                long g = sorted.get(k).startMs - end;
                if (g > 0) lens.add(g);
                end = Math.max(end, sorted.get(k).endMs);
            }
            Collections.sort(lens);
            long median = lens.isEmpty() ? 0 : lens.get(lens.size() / 2);
            long p95 = lens.isEmpty() ? 0 : lens.get((int) Math.min(lens.size() - 1, Math.round(lens.size() * 0.95)));
            // a hole is suspicious when it is long in absolute terms AND far beyond what this film normally has
            long threshold = Math.max(180000L, Math.max(median * 40, p95 * 4));
            end = sorted.get(0).endMs;
            int endIdx = sorted.get(0).index;
            for (int k = 1; k < sorted.size(); k++) {
                SubCue c = sorted.get(k);
                if (c.startMs - end >= threshold) gaps.add(new Gap(end, c.startMs, endIdx, c.index));
                if (c.endMs >= end) { end = c.endMs; endIdx = c.index; }
            }
            lastEnd = end;
        } else if (!sorted.isEmpty()) {
            for (SubCue c : sorted) lastEnd = Math.max(lastEnd, c.endMs);
        }
        boolean truncated = false;
        if (mediaDurationMs > 0 && lastEnd > 0) {
            long tail = mediaDurationMs - lastEnd;
            // end credits are 3-12 minutes; more than 15 minutes AND more than 15% of the film is not credits
            truncated = tail > 15 * 60000L && tail > mediaDurationMs * 0.15;
        }

        // against the reference (same timing): reference cues with no cue of this file overlapping them
        List<Integer> ref = new ArrayList<Integer>();
        List<String> refTexts = new ArrayList<String>();
        if (reference != null && !reference.isEmpty() && !sorted.isEmpty()) {
            long[] starts = new long[sorted.size()];
            long maxDur = 0;
            for (int k = 0; k < sorted.size(); k++) {
                starts[k] = sorted.get(k).startMs;
                maxDur = Math.max(maxDur, sorted.get(k).durationMs());
            }
            for (SubCue rc : reference.cues) {
                if (rc.isBlank() || rc.endMs <= rc.startMs) continue;
                if (!covered(sorted, starts, maxDur, rc.startMs - 400, rc.endMs + 400)) {
                    ref.add(rc.index);
                    if (refTexts.size() < 200) refTexts.add(rc.plainText().replace('\n', ' '));
                }
            }
        }

        if (!blank.isEmpty()) notes.add(blank.size() + " مقطعاً بلا نص: " + head(blank));
        if (!numbers.isEmpty()) notes.add("أرقام ناقصة من ترقيم الملف (قد تكون مقاطع محذوفة): " + head(numbers));
        if (d.skippedCues > 0) notes.add(d.skippedCues + " مقطعاً تالف التوقيت لم يُقرأ — جرّب «إصلاح SRT».");
        if (!untranslated.isEmpty()) notes.add(untranslated.size() + " سطراً بقي بلا ترجمة عربية: " + head(untranslated));
        for (int k = 0; k < gaps.size() && k < 6; k++) {
            Gap g = gaps.get(k);
            notes.add("لا ترجمة بين " + SubCodec.stamp(g.fromMs, ',') + " و" + SubCodec.stamp(g.toMs, ',') + " (" + (g.lengthMs() / 60000) + " دقيقة).");
        }
        if (truncated) notes.add("آخر سطر عند " + SubCodec.stamp(lastEnd, ',') + " والفيلم ينتهي عند " + SubCodec.stamp(mediaDurationMs, ',') + ": الملف مبتور غالباً.");
        if (!ref.isEmpty()) notes.add(ref.size() + " سطراً من الأصل لا يقابله سطر في الترجمة: " + head(ref));
        return new Missing(blank, numbers, d.skippedCues, untranslated, gaps, ref, refTexts, truncated, lastEnd, mediaDurationMs, notes);
    }

    private static boolean covered(List<SubCue> sorted, long[] starts, long maxDur, long from, long to) {
        // first cue starting at or after `to`
        int lo = 0, hi = starts.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] < to) lo = mid + 1; else hi = mid;
        }
        for (int k = lo - 1; k >= 0; k--) {
            SubCue c = sorted.get(k);
            if (from - c.startMs > maxDur) break;
            if (c.endMs > from && !c.isBlank()) return true;
        }
        return false;
    }

    private static int letters(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) n++;
        return n;
    }

    private static int parseNumber(String id) {
        if (id == null) return -1;
        String t = id.trim();
        if (t.length() == 0 || t.length() > 7) return -1;
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) < '0' || t.charAt(i) > '9') return -1;
        return Integer.parseInt(t);
    }

    private static String head(List<Integer> l) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size() && i < 12; i++) {
            if (i > 0) b.append("، ");
            b.append('#').append(l.get(i));
        }
        if (l.size() > 12) b.append(" …");
        return b.toString();
    }
}
