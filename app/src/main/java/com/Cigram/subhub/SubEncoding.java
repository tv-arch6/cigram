package com.Cigram.subhub;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * CigramSubtitleHub — Encoding Fixer («إصلاح ظهور الحروف العربية بشكل خاطئ»).
 *
 * {@link SubCodec#decode} already reads BOMs, UTF-16 without a BOM, strict UTF-8, Windows-1256 and Arabic that was read
 * as Windows-1252 once. This class is the full repair bench on top of it:
 * <ul>
 *   <li>every plausible charset is tried and SCORED (Arabic letter share, everyday Arabic words, damage markers), so a
 *   Windows-1252 French/English file is not turned into Arabic-looking noise and an ISO-8859-6 / DOS file is found;</li>
 *   <li>UTF-8 Arabic that was read as Windows-1256 («ط§ظ„ط³ظ„ط§ظ…» — the most common damage of Arabic subtitles) is
 *   reversed line by line; damage applied twice is reversed twice;</li>
 *   <li>Arabic presentation forms (U+FB50–U+FEFC, old DOS-era files) become ordinary letters;</li>
 *   <li>what cannot be repaired is COUNTED and reported (U+FFFD, runs of «????»): it is never hidden or invented.</li>
 * </ul>
 * Nothing here touches a timestamp. Pure Java, shared byte-identical between Cigram and Cigram Admin.
 */
public final class SubEncoding {

    private SubEncoding() {}

    /** Charsets tried for a file that is not valid UTF-8 / UTF-16, in tie-break order. */
    public static final String[] CANDIDATES = {
            "windows-1256", "ISO-8859-6", "windows-1252", "IBM864", "x-MacArabic", "IBM720", "windows-1250", "windows-1251"
    };

    /** One way of reading the bytes, with its quality score (higher is better). */
    public static final class Candidate {
        public final String charset;
        public final double score;
        public final double arabicShare;
        /** First readable text line, for the chooser in the admin. */
        public final String sample;

        Candidate(String charset, double score, double arabicShare, String sample) {
            this.charset = charset;
            this.score = score;
            this.arabicShare = arabicShare;
            this.sample = sample;
        }
    }

    public static final class Fix {
        /** Repaired text (line endings normalized to '\n', no BOM, no NUL). */
        public final String text;
        /** How the bytes were read, e.g. "windows-1256", "UTF-8+BOM", "UTF-8+utf8-as-1256-repaired". */
        public final String encoding;
        /** True when the text differs from a plain UTF-8 reading of the same bytes. */
        public final boolean changed;
        /** Every reading that was scored, best first (empty when the file was clean UTF-8 / UTF-16). */
        public final List<Candidate> candidates;
        /** Lines repaired by a mojibake reversal. */
        public final int repairedLines;
        /** Characters that are lost for good: U+FFFD and «?» standing where letters were. */
        public final int lostCharacters;
        /** Arabic presentation forms converted to ordinary letters. */
        public final int presentationForms;
        /** Arabic notes for the report. */
        public final List<String> notes;

        Fix(String text, String encoding, boolean changed, List<Candidate> candidates, int repairedLines, int lost,
            int forms, List<String> notes) {
            this.text = text;
            this.encoding = encoding;
            this.changed = changed;
            this.candidates = Collections.unmodifiableList(candidates);
            this.repairedLines = repairedLines;
            this.lostCharacters = lost;
            this.presentationForms = forms;
            this.notes = Collections.unmodifiableList(notes);
        }

        public byte[] utf8() {
            return text.getBytes(UTF8);
        }
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static Charset cs(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ entry points

    /** Automatic: the best reading of the bytes, then every text-level repair. */
    public static Fix fix(byte[] raw) {
        return fix(raw, null);
    }

    /**
     * @param forceCharset a charset the admin chose by hand (null / "" = automatic). The text-level repairs still run.
     */
    public static Fix fix(byte[] raw, String forceCharset) {
        if (raw == null || raw.length == 0) throw new IllegalArgumentException("ملف الترجمة فارغ.");
        if (raw.length > SubCodec.MAX_BYTES) throw new IllegalArgumentException("ملف الترجمة كبير جداً (الحد 8 ميغابايت).");
        List<String> notes = new ArrayList<String>();
        List<Candidate> cands = new ArrayList<Candidate>();
        String text;
        String enc;
        Charset forced = forceCharset == null || forceCharset.trim().length() == 0 ? null : cs(forceCharset.trim());
        if (forced != null) {
            text = new String(raw, forced);
            enc = forced.name();
            notes.add("قُرئ الملف بالترميز الذي اختاره المشرف: " + enc + ".");
        } else {
            SubCodec.Decoded d = SubCodec.decode(raw);
            text = d.text;
            enc = d.encoding;
            boolean unicode = enc.startsWith("UTF-16") || (enc.startsWith("UTF-8") && !enc.contains("lossy"));
            if (!unicode) {
                // not valid UTF-8: score every plausible legacy charset instead of assuming Windows-1256
                String best = null, bestEnc = enc;
                double bestScore = Double.NEGATIVE_INFINITY;
                for (String name : CANDIDATES) {
                    Charset c = cs(name);
                    if (c == null) continue;
                    String t = stripNoise(new String(raw, c));
                    double sc = score(t);
                    cands.add(new Candidate(c.name(), sc, SubCodec.arabicShare(t), sampleLine(t)));
                    if (sc > bestScore + 1e-9) { bestScore = sc; best = t; bestEnc = c.name(); }
                }
                Collections.sort(cands, new Comparator<Candidate>() {
                    @Override public int compare(Candidate a, Candidate b) {
                        return Double.compare(b.score, a.score);
                    }
                });
                if (best != null) {
                    text = best;
                    enc = bestEnc;
                    notes.add("الملف ليس UTF-8: اختير الترميز " + enc + " بعد مقارنة " + cands.size() + " ترميزات.");
                }
            } else if (d.repaired) {
                notes.add("أُصلحت حروف عربية قُرئت سابقاً بترميز لاتيني (Windows-1252).");
            } else if (d.hadBom) {
                notes.add("أُزيلت علامة BOM من أول الملف.");
            }
        }
        text = stripNoise(text);

        // text-level repairs, at most three rounds (damage applied twice is reversed twice)
        int repairedLines = 0;
        for (int round = 0; round < 3; round++) {
            int[] n = new int[3];
            String next = repairLines(text, n);
            if (n[0] == 0) break;
            repairedLines += n[0];
            text = next;
            if (n[1] > 0 && !enc.contains("+utf8-as-1256-repaired")) enc = enc + "+utf8-as-1256-repaired";
            if (n[2] > 0 && !enc.contains("+mojibake-repaired")) enc = enc + "+mojibake-repaired";
        }
        if (repairedLines > 0) notes.add("أُصلح " + repairedLines + " سطراً كانت حروفه العربية تظهر رموزاً (مثل «ط§ظ„» أو «Ø§Ù„»).");

        int[] forms = new int[1];
        text = normalizePresentationForms(text, forms);
        if (forms[0] > 0) notes.add("حُوّل " + forms[0] + " حرفاً من أشكال العرض القديمة إلى الحروف العربية القياسية.");

        int lost = countLost(text);
        if (lost > 0) notes.add(lost + " حرفاً فُقد نهائياً في الملف الأصلي (� أو علامات استفهام مكان الحروف): لا يمكن استرجاعه، استخدم نسخة أخرى من الملف.");

        String plainUtf8 = stripNoise(new String(raw, UTF8));
        boolean changed = !plainUtf8.equals(text);
        return new Fix(text, enc, changed, cands, repairedLines, lost, forms[0], notes);
    }

    /** Text-only repair (the bytes are gone, e.g. a string from a provider): mojibake reversal + presentation forms. */
    public static String repairText(String s) {
        if (s == null) return "";
        String text = stripNoise(s);
        for (int round = 0; round < 3; round++) {
            int[] n = new int[3];
            String next = repairLines(text, n);
            if (n[0] == 0) break;
            text = next;
        }
        return normalizePresentationForms(text, new int[1]);
    }

    // ------------------------------------------------------------------ scoring

    /** Everyday Arabic words: a real Arabic subtitle contains many of them, a wrong decoding contains none. */
    private static final String[] COMMON = {
            "في", "من", "على", "لا", "ما", "أن", "هذا", "يا", "إلى", "كان", "هل", "أنا", "لم", "لك", "عن", "هذه", "أنت",
            "كل", "لقد", "ماذا", "نعم", "هو", "هي", "مع", "لن", "قد", "إن", "أو", "لي", "هنا", "ذلك", "لكن", "شيء", "الآن",
            "the", "and", "you", "that", "what", "this", "not", "for", "are", "have", "with", "was"
    };

    private static final java.util.HashSet<String> COMMON_SET = new java.util.HashSet<String>(java.util.Arrays.asList(COMMON));

    /**
     * Quality of a decoded text, higher = more likely the right reading. Combines: share of letters that form everyday
     * words, Arabic letter share, and penalties for the fingerprints of a wrong decoder.
     */
    public static double score(String text) {
        if (text == null || text.length() == 0) return -10;
        int n = Math.min(text.length(), 60000);
        int letters = 0, bad = 0, latinHigh = 0, taZa = 0, arabic = 0, box = 0;
        int tokens = 0, hits = 0, mixed = 0;
        boolean tokArabic = false, tokLatin = false;
        StringBuilder tok = new StringBuilder();
        for (int i = 0; i <= n; i++) {
            char c = i < n ? text.charAt(i) : ' ';
            if (Character.isLetter(c)) {
                letters++;
                tok.append(c);
                if (SubCodec.isArabicLetter(c)) {
                    arabic++;
                    tokArabic = true;
                    if (c == 'ط' || c == 'ظ') taZa++;
                } else {
                    if (c < 0x250) tokLatin = true;
                    if (c >= 0xC0 && c <= 0xFF) latinHigh++;
                }
            } else {
                if (tok.length() > 0) {
                    tokens++;
                    if (COMMON_SET.contains(tok.toString().toLowerCase(java.util.Locale.ROOT))) hits++;
                    // «pasٌ» / «seٌor»: an Arabic letter inside a Latin word only happens with the wrong charset
                    if (tokArabic && tokLatin) mixed++;
                    tokArabic = false;
                    tokLatin = false;
                    tok.setLength(0);
                }
                if (c == '�' || (c >= 0x80 && c <= 0x9F) || (c >= 0xE000 && c <= 0xF8FF)) bad++;
                else if (c >= 0x2500 && c <= 0x259F) box++;   // DOS box drawing: a DOS charset read wrongly
            }
        }
        if (letters == 0) return -5;
        double arShare = arabic / (double) letters;
        double wordScore = tokens == 0 ? 0 : hits / (double) tokens;
        double s = wordScore * 6.0 + arShare * 1.5;
        if (tokens > 0) s -= mixed * 8.0 / tokens;
        s -= (bad + box) * 8.0 / Math.max(1, n) * 100.0 / 10.0;
        // «ط» and «ظ» are rare letters (about 1.5% of Arabic text): a text full of them is UTF-8 read as Windows-1256
        if (arabic > 20 && taZa > arabic * 0.25) s -= 3.0;
        // Ã/Ø/Ù/Ç... runs: Arabic read as Latin-1
        if (latinHigh > letters * 0.3) s -= 2.0;
        return s;
    }

    private static String sampleLine(String t) {
        String[] lines = t.split("\n", 80);
        for (String l : lines) {
            String tr = l.trim();
            if (tr.length() < 3) continue;
            if (SubCodec.TIMING.matcher(tr).matches()) continue;
            boolean digitsOnly = true;
            for (int i = 0; i < tr.length(); i++) if (!Character.isDigit(tr.charAt(i))) { digitsOnly = false; break; }
            if (digitsOnly) continue;
            return tr.length() > 60 ? tr.substring(0, 60) : tr;
        }
        return "";
    }

    // ------------------------------------------------------------------ line repairs

    private static String stripNoise(String s) {
        String t = s;
        while (t.length() > 0 && t.charAt(0) == '﻿') t = t.substring(1);
        return t.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "");
    }

    /**
     * Repairs each damaged line on its own (a file is often half damaged: timing lines and Latin lines are fine).
     * A line is only replaced when the reversal is LOSSLESS (every character maps back to one byte and the bytes are
     * valid UTF-8 / plausible Windows-1256) and the result is clearly more Arabic than before.
     *
     * @param count out, three slots: [0] lines repaired, [1] of them «UTF-8 read as Windows-1256», [2] «Arabic read as Latin».
     */
    static String repairLines(String text, int[] count) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            String fixed = null;
            if (looksUtf8As1256(l)) {
                fixed = reverse(l, "windows-1256");
                if (fixed != null) count[1]++;
            }
            if (fixed == null && looksArabicAsLatin(l)) {
                fixed = reverseLatin(l);
                if (fixed != null) count[2]++;
            }
            if (fixed != null) count[0]++;
            out.append(fixed == null ? l : fixed);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    /** «ط§ظ„ط³ظ„ط§ظ…»: almost every second character is ط or ظ (the UTF-8 lead bytes D8 / D9 seen through Windows-1256). */
    static boolean looksUtf8As1256(String l) {
        int ar = 0, lead = 0;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == 'ط' || c == 'ظ' || c == 'ع' || c == 'غ') { lead++; ar++; }
            else if (SubCodec.isArabicLetter(c)) ar++;
        }
        return lead >= 2 && lead >= ar * 0.4;
    }

    static boolean looksArabicAsLatin(String l) {
        int hi = 0, letters = 0;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (Character.isLetter(c)) letters++;
            if (c >= 0xC0 && c <= 0xFF) hi++;
        }
        return hi >= 3 && hi >= letters * 0.4;
    }

    /** text → bytes in {@code wrong} (lossless or null) → strict UTF-8 text that is Arabic. */
    private static String reverse(String l, String wrong) {
        Charset w = cs(wrong);
        if (w == null) return null;
        try {
            ByteBuffer bb = w.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(l));
            String u = UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bb).toString();
            if (!SubCodec.hasArabic(u)) return null;
            if (looksUtf8As1256(u) && u.length() >= l.length()) return null;   // no progress
            return u;
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static final String CP1252_HIGH = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F"
            + "\u0090‘’“”•–—˜™š›œ\u009DžŸ";

    /**
     * Latin-looking line → the bytes it came from → UTF-8 Arabic, else Windows-1256 Arabic.
     *
     * Windows-1252 has five undefined bytes (81, 8D, 8F, 90, 9D): a tool that read UTF-8 Arabic as Windows-1252
     * replaced them by U+FFFD, so the second byte of «ف» (D9 81), of the kasra, the damma and two rarer signs is gone.
     * After a D9 the lost byte is taken to be 81: «ف» is one of the most frequent Arabic letters, the others are
     * optional vowel signs that subtitles rarely write. Anything else that was lost stays lost (and is counted).
     */
    private static String reverseLatin(String l) {
        byte[] b = new byte[l.length()];
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c < 0x80 || (c >= 0xA0 && c <= 0xFF)) { b[i] = (byte) c; continue; }
            int k = CP1252_HIGH.indexOf(c);
            if (k >= 0) { b[i] = (byte) (0x80 + k); continue; }
            if (c >= 0x80 && c <= 0x9F) { b[i] = (byte) c; continue; }
            if (c == '\uFFFD' && i > 0 && b[i - 1] == (byte) 0xD9) { b[i] = (byte) 0x81; continue; }
            return null;
        }
        try {
            String u = UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
            if (SubCodec.arabicShare(u) >= 0.5) return u;
        } catch (CharacterCodingException ignored) { }
        if (l.indexOf('\uFFFD') >= 0) return null;
        Charset w1256 = cs("windows-1256");
        if (w1256 != null) {
            String a = new String(b, w1256);
            if (SubCodec.arabicShare(a) >= 0.6 && !looksUtf8As1256(a)) return a;
        }
        return null;
    }

    // ------------------------------------------------------------------ presentation forms / loss

    /** Arabic presentation forms A/B → ordinary letters (NFKC on those characters only; Latin text is untouched). */
    static String normalizePresentationForms(String s, int[] count) {
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean form = (c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFC);
            if (form) {
                if (out == null) { out = new StringBuilder(s.length()); out.append(s, 0, i); }
                out.append(Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFKC));
                count[0]++;
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? s : out.toString();
    }

    /** U+FFFD plus «?» inside runs of three or more (letters that an earlier tool could not write). */
    static int countLost(String s) {
        int lost = 0, run = 0;
        for (int i = 0; i <= s.length(); i++) {
            char c = i < s.length() ? s.charAt(i) : ' ';
            if (c == '�') lost++;
            if (c == '?') run++;
            else {
                if (run >= 3) lost += run;
                run = 0;
            }
        }
        return lost;
    }
}
