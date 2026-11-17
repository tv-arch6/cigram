package com.Cigram.subhub;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CigramSubtitleHub — quality checks before a subtitle is approved.
 *
 * Checks: cue count, empty / missing translations, invalid start/end, cue order, unintended overlaps, very short
 * display times, reading speed (characters per second), line length and line count, Arabic punctuation, RTL lines
 * that start with a left-to-right run, encoding damage (U+FFFD, leftover mojibake), BOM / Windows-1256 conversions,
 * X-TIMESTAMP-MAP and, for a translation, cue-count and timestamp drift against the source.
 *
 * A provider's own quality report can be attached as extra evidence ({@link Report#providerNote}); it never turns a
 * failing local check into a pass.
 */
public final class SubQa {

    private SubQa() {}

    public enum Severity { INFO, WARNING, ERROR }

    public static final class Issue {
        public final Severity severity;
        public final String code;
        /** 1-based cue index, 0 for file-level issues. */
        public final int cue;
        public final String message;

        Issue(Severity s, String code, int cue, String message) {
            this.severity = s;
            this.code = code;
            this.cue = cue;
            this.message = message;
        }

        @Override
        public String toString() {
            return severity + " " + code + (cue > 0 ? " #" + cue : "") + " " + message;
        }
    }

    /** Thresholds (defaults follow common Arabic subtitling practice; all adjustable). */
    public static final class Options {
        public double maxCps = 20.0;
        public long minDurationMs = 700;
        public long maxDurationMs = 10000;
        public int maxLineLength = 42;
        public int maxLines = 2;
        public long overlapToleranceMs = 100;
        /** The file is expected to be Arabic (Arabic letters must dominate). */
        public boolean expectArabic = true;
        /** Per-code cap so one systematic problem does not flood the report (the totals stay exact). */
        public int perCodeLimit = 40;
    }

    public enum Verdict { PASS, REVIEW, FAIL }

    public static final class Report {
        public final List<Issue> issues;
        public final int cues;
        public final int errors;
        public final int warnings;
        /** Exact count per code even when the list was capped. */
        public final java.util.Map<String, Integer> counts;
        public final double arabicShare;
        public final long durationMs;
        public String providerNote = "";

        Report(List<Issue> issues, java.util.Map<String, Integer> counts, int cues, double arabicShare, long durationMs) {
            this.issues = Collections.unmodifiableList(issues);
            this.counts = Collections.unmodifiableMap(counts);
            this.cues = cues;
            int e = 0, w = 0;
            for (java.util.Map.Entry<String, Integer> en : counts.entrySet()) {
                Severity s = SEVERITY_OF.get(en.getKey());
                if (s == Severity.ERROR) e += en.getValue();
                else if (s == Severity.WARNING) w += en.getValue();
            }
            this.errors = e;
            this.warnings = w;
            this.arabicShare = arabicShare;
            this.durationMs = durationMs;
        }

        public Verdict verdict() {
            if (errors > 0) return Verdict.FAIL;
            if (warnings > Math.max(3, cues / 20)) return Verdict.REVIEW;   // more than 5% of the cues need a look
            if (count("OVERLAP") > 0 || count("TIMESTAMP_MAP") > 0 || count("TIMESTAMP_DRIFT") > 0) return Verdict.REVIEW;
            return Verdict.PASS;
        }

        /** True when a person must look at the file before it can be approved. */
        public boolean needsManualReview() {
            return verdict() != Verdict.PASS;
        }

        public int count(String code) {
            Integer n = counts.get(code);
            return n == null ? 0 : n;
        }

        /** Short Arabic summary for lists. */
        public String summary() {
            Verdict v = verdict();
            String head = v == Verdict.PASS ? "سليم" : v == Verdict.REVIEW ? "يحتاج مراجعة" : "به أخطاء";
            return head + " · " + cues + " مقطعاً · " + errors + " خطأ · " + warnings + " تنبيه";
        }
    }

    private static final java.util.Map<String, Severity> SEVERITY_OF = new java.util.HashMap<String, Severity>();

    static {
        String[][] map = {
                {"EMPTY_FILE", "ERROR"}, {"INVALID_TIMING", "ERROR"}, {"SKIPPED_TIMING", "ERROR"}, {"ENCODING_DAMAGE", "ERROR"},
                {"NOT_ARABIC", "ERROR"}, {"CUE_COUNT_MISMATCH", "ERROR"}, {"MISSING_TRANSLATION", "ERROR"}, {"TIMESTAMP_DRIFT", "ERROR"},
                {"ORDER", "WARNING"}, {"OVERLAP", "WARNING"}, {"SHORT_DURATION", "WARNING"}, {"HIGH_CPS", "WARNING"},
                {"LINE_TOO_LONG", "WARNING"}, {"TOO_MANY_LINES", "WARNING"}, {"ARABIC_PUNCTUATION", "WARNING"},
                {"RTL_LEADING_LTR", "WARNING"}, {"UNTRANSLATED", "WARNING"}, {"TIMESTAMP_MAP", "WARNING"}, {"EMPTY_CUE", "WARNING"},
                {"LONG_DURATION", "INFO"}, {"BIDI_CONTROLS", "INFO"}, {"DUPLICATE", "INFO"}, {"CONVERTED_ENCODING", "INFO"},
                {"BOM_REMOVED", "INFO"}, {"NONSTANDARD_FRACTION", "INFO"}, {"SMALL_OVERLAP", "INFO"},
        };
        for (String[] m : map) SEVERITY_OF.put(m[0], Severity.valueOf(m[1]));
    }

    private static final class Sink {
        final List<Issue> list = new ArrayList<Issue>();
        final java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<String, Integer>();
        final int limit;

        Sink(int limit) {
            this.limit = limit;
        }

        void add(String code, int cue, String msg) {
            Integer n = counts.get(code);
            int now = n == null ? 1 : n + 1;
            counts.put(code, now);
            if (now <= limit) list.add(new Issue(SEVERITY_OF.get(code), code, cue, msg));
        }
    }

    public static Report analyze(SubDocument d, Options o) {
        return analyze(d, null, o);
    }

    /** {@code source}: the original-language file when {@code d} is its translation (enables count / drift / untranslated checks). */
    public static Report analyze(SubDocument d, SubDocument source, Options o) {
        Options opt = o == null ? new Options() : o;
        Sink s = new Sink(opt.perCodeLimit);
        if (d.isEmpty()) s.add("EMPTY_FILE", 0, "لا يحتوي الملف أي مقطع ترجمة صالح.");
        if (d.skippedCues > 0) s.add("SKIPPED_TIMING", 0, d.skippedCues + " سطر توقيت غير صالح لم يُقرأ.");
        if (d.hasTimestampMap) s.add("TIMESTAMP_MAP", 0, "الملف مقطع من بث HLS (X-TIMESTAMP-MAP): راجع التوقيت مع الفيديو قبل الاعتماد.");
        if (d.nonStandardFractions > 0) s.add("NONSTANDARD_FRACTION", 0, d.nonStandardFractions + " مقطعاً بجزء ثانية غير ثلاثي (يُصحَّح عند الحفظ).");
        if (d.encoding.contains("+BOM")) s.add("BOM_REMOVED", 0, "أُزيلت علامة BOM من بداية الملف.");
        if (d.encoding.startsWith("windows-1256") || d.encoding.contains("UTF-16") || d.encoding.contains("mojibake")) {
            s.add("CONVERTED_ENCODING", 0, "حُوّل الترميز من " + d.encoding + " إلى UTF-8.");
        }
        StringBuilder all = new StringBuilder();
        SubCue prev = null;
        long dur = 0;
        for (SubCue c : d.cues) {
            String plain = c.plainText();
            all.append(plain).append('\n');
            dur = Math.max(dur, c.endMs);
            if (c.endMs <= c.startMs) s.add("INVALID_TIMING", c.index, "النهاية (" + SubCodec.stamp(c.endMs, ',') + ") ليست بعد البداية (" + SubCodec.stamp(c.startMs, ',') + ").");
            if (c.isBlank()) s.add("EMPTY_CUE", c.index, "مقطع بلا نص.");
            if (plain.indexOf('�') >= 0) s.add("ENCODING_DAMAGE", c.index, "حروف تالفة (�) — الترميز الأصلي غير صحيح.");
            else if (looksMojibake(plain)) s.add("ENCODING_DAMAGE", c.index, "نص مشوّه الترميز (مثل «Ø§Ù„»).");
            if (prev != null) {
                if (c.startMs < prev.startMs) s.add("ORDER", c.index, "يبدأ قبل المقطع السابق — ترتيب المقاطع غير صحيح.");
                long overlap = prev.endMs - c.startMs;
                boolean intended = c.startMs == prev.startMs && c.endMs == prev.endMs || (c.settings.length() > 0 && !c.settings.equals(prev.settings));
                if (overlap > opt.overlapToleranceMs && !intended) s.add("OVERLAP", c.index, "يتداخل مع المقطع السابق بمقدار " + overlap + " ملّي ثانية.");
                else if (overlap > 0 && !intended) s.add("SMALL_OVERLAP", c.index, "تداخل بسيط " + overlap + " ملّي ثانية.");
                if (plain.trim().length() > 0 && plain.trim().equals(prev.plainText().trim()) && c.startMs - prev.endMs < 200) s.add("DUPLICATE", c.index, "نص مكرر من المقطع السابق.");
            }
            long d0 = c.durationMs();
            if (d0 > 0 && d0 < opt.minDurationMs) s.add("SHORT_DURATION", c.index, "مدة العرض " + d0 + " ملّي ثانية فقط — قصيرة جداً للقراءة.");
            if (d0 > opt.maxDurationMs) s.add("LONG_DURATION", c.index, "مدة العرض " + (d0 / 1000) + " ثانية.");
            int chars = readingChars(plain);
            if (d0 > 0 && chars > 0) {
                double cps = chars * 1000.0 / d0;
                if (cps > opt.maxCps) s.add("HIGH_CPS", c.index, String.format(java.util.Locale.US, "سرعة القراءة %.1f حرف/ثانية (الحد %.0f).", cps, opt.maxCps));
            }
            String[] lines = c.plainLines();
            if (lines.length > opt.maxLines) s.add("TOO_MANY_LINES", c.index, lines.length + " أسطر (الحد " + opt.maxLines + ").");
            for (String l : lines) {
                int len = l.codePointCount(0, l.length());
                if (len > opt.maxLineLength) { s.add("LINE_TOO_LONG", c.index, "سطر من " + len + " حرفاً (الحد " + opt.maxLineLength + ")."); break; }
            }
            if (SubCodec.hasArabic(plain)) {
                if (latinPunctuationInArabic(plain)) s.add("ARABIC_PUNCTUATION", c.index, "علامات ترقيم لاتينية في نص عربي: استخدم «،» و«؟» و«؛».");
                for (String l : lines) {
                    if (SubCodec.arabicShare(l) >= 0.5 && firstStrongIsLtr(l)) { s.add("RTL_LEADING_LTR", c.index, "سطر عربي يبدأ بكلمة لاتينية/رقم — قد يُعرض مقلوباً؛ أضف علامة RLM في أوله."); break; }
                }
            }
            if (unbalancedBidi(c.text)) s.add("BIDI_CONTROLS", c.index, "علامات اتجاه (LRE/RLE/PDF) غير متوازنة.");
            prev = c;
        }
        double share = SubCodec.arabicShare(all.toString());
        if (opt.expectArabic && !d.isEmpty() && share < 0.3) s.add("NOT_ARABIC", 0, String.format(java.util.Locale.US, "نسبة الحروف العربية %.0f%% فقط — الملف ليس عربياً.", share * 100));

        if (source != null) {
            if (source.size() != d.size()) {
                s.add("CUE_COUNT_MISMATCH", 0, "عدد المقاطع " + d.size() + " بينما الأصل " + source.size() + " — لا يمكن ضمان التوقيت.");
            }
            SubSync.TimingReport t = SubSync.compareTiming(source, d, 1);
            if (t.drifted > 0) s.add("TIMESTAMP_DRIFT", t.firstDriftIndex, t.drifted + " مقطعاً تغيّر توقيته عن الأصل (أقصى فرق " + t.maxDriftMs + " ملّي ثانية).");
            int n = Math.min(source.size(), d.size());
            for (int k = 0; k < n; k++) {
                SubCue a = source.cues.get(k), b = d.cues.get(k);
                String pa = a.plainText().trim(), pb = b.plainText().trim();
                if (pa.length() > 0 && pb.length() == 0) s.add("MISSING_TRANSLATION", b.index, "المقطع غير مترجم (فارغ).");
                else if (pa.length() > 3 && pa.equalsIgnoreCase(pb) && !SubCodec.hasArabic(pa)) s.add("UNTRANSLATED", b.index, "النص مطابق للأصل — لم يُترجم.");
                else if (opt.expectArabic && hasLetters(pa) && hasLetters(pb) && !SubCodec.hasArabic(pb)) s.add("UNTRANSLATED", b.index, "لا يحتوي حروفاً عربية.");
            }
        }
        return new Report(s.list, s.counts, d.size(), share, dur);
    }

    // ------------------------------------------------------------------ helpers

    /** Characters that cost reading time: letters, digits, punctuation (spaces and markup excluded). */
    static int readingChars(String plain) {
        int n = 0;
        for (int i = 0; i < plain.length(); ) {
            int cp = plain.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp)) continue;
            int t = Character.getType(cp);
            if (t == Character.NON_SPACING_MARK || t == Character.FORMAT) continue;   // harakat, RLM/LRM
            n++;
        }
        return n;
    }

    static boolean hasLetters(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) return true;
        return false;
    }

    static boolean latinPunctuationInArabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ',' && c != '?' && c != ';') continue;
            // only when it sits next to Arabic text (not inside numbers like 1,5 or an English phrase)
            int j = i - 1;
            while (j >= 0 && (s.charAt(j) == ' ' || s.charAt(j) == '"' || s.charAt(j) == '\'' || s.charAt(j) == ')')) j--;
            if (j >= 0 && SubCodec.isArabicLetter(s.charAt(j))) return true;
        }
        return false;
    }

    static boolean firstStrongIsLtr(String l) {
        for (int i = 0; i < l.length(); ) {
            int cp = l.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0x200F || cp == 0x061C) return false;          // RLM / ALM already present
            byte dir = Character.getDirectionality(cp);
            if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return false;
            if (dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return true;
            if (dir == Character.DIRECTIONALITY_EUROPEAN_NUMBER || dir == Character.DIRECTIONALITY_ARABIC_NUMBER) return true;
        }
        return false;
    }

    static boolean unbalancedBidi(String s) {
        int open = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '‪' || c == '‫' || c == '‭' || c == '‮') open++;
            else if (c == '‬') open--;
            if (open < 0) return true;
        }
        return open != 0;
    }

    static boolean looksMojibake(String s) {
        int hits = 0;
        for (int i = 0; i + 1 < s.length(); i++) {
            char a = s.charAt(i), b = s.charAt(i + 1);
            if ((a == 'Ø' || a == 'Ù') && b >= 0x0080 && b <= 0x00FF) hits++;
        }
        return hits >= 3;
    }

    /** Adds an RLM to the start of Arabic lines that would otherwise start left-to-right (a safe, timing-neutral fix). */
    public static SubDocument fixRtlLines(SubDocument d) {
        List<SubCue> out = new ArrayList<SubCue>(d.size());
        boolean changed = false;
        for (SubCue c : d.cues) {
            String[] lines = c.text.split("\n", -1);
            boolean ch = false;
            for (int k = 0; k < lines.length; k++) {
                String p = SubCue.plain(lines[k]);
                if (SubCodec.arabicShare(p) >= 0.5 && firstStrongIsLtr(p)) {
                    lines[k] = "‏" + lines[k];
                    ch = true;
                }
            }
            if (ch) {
                StringBuilder b = new StringBuilder();
                for (int k = 0; k < lines.length; k++) { if (k > 0) b.append('\n'); b.append(lines[k]); }
                out.add(c.withText(b.toString()));
                changed = true;
            } else {
                out.add(c);
            }
        }
        return changed ? d.withCues(out) : d;
    }

    /** Replaces Latin , ? ; that follow Arabic letters with ، ؟ ؛ (timing-neutral). */
    public static SubDocument fixArabicPunctuation(SubDocument d) {
        List<SubCue> out = new ArrayList<SubCue>(d.size());
        boolean changed = false;
        for (SubCue c : d.cues) {
            if (!SubCodec.hasArabic(c.text)) { out.add(c); continue; }
            StringBuilder b = new StringBuilder(c.text);
            boolean ch = false;
            for (int i = 0; i < b.length(); i++) {
                char x = b.charAt(i);
                if (x != ',' && x != '?' && x != ';') continue;
                int j = i - 1;
                while (j >= 0 && b.charAt(j) == ' ') j--;
                if (j >= 0 && SubCodec.isArabicLetter(b.charAt(j))) {
                    b.setCharAt(i, x == ',' ? '،' : x == '?' ? '؟' : '؛');
                    ch = true;
                }
            }
            if (ch) { out.add(c.withText(b.toString())); changed = true; } else out.add(c);
        }
        return changed ? d.withCues(out) : d;
    }
}
