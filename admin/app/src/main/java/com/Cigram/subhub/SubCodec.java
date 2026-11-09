package com.Cigram.subhub;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CigramSubtitleHub — reading and writing SRT / WebVTT.
 *
 * <p>Why this exists (root cause of "the text appears before the speech", confirmed in the ExoPlayer 2.19.1 bytecode
 * this app ships): ExoPlayer's {@code SubripDecoder.parseTimecode} and {@code WebvttParserUtil.parseTimestampUs} ADD
 * the digits after the comma / dot as raw milliseconds. A file written with centiseconds ({@code 00:00:10,5} or
 * {@code 00:00:10,50}, typical of ASS→SRT conversions and some translation tools) is therefore shown at 10.005 s /
 * 10.050 s instead of 10.500 s — up to 0.9 s EARLY, and it ends early too. ExoPlayer's SRT pattern also only accepts
 * ',' so an SRT written with '.' loses those cues entirely, and without a BOM it always assumes UTF-8 (Windows-1256
 * Arabic turns into garbage).</p>
 *
 * <p>Here a fraction is read as a fraction of a second whatever its width, both separators are accepted, the
 * encoding is detected (BOM, UTF-16 without BOM, strict UTF-8, Windows-1256, double-decoded "mojibake"), and the
 * file is written back with exactly three millisecond digits so every player reads the intended time.
 * Timestamps are NEVER changed by parsing or writing: only {@link SubSync#shift} moves time, and only when asked.</p>
 */
public final class SubCodec {

    private SubCodec() {}

    /** Hard limit for a subtitle file (a feature film is ~100-300 KB). */
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    // ------------------------------------------------------------------ decoding bytes

    public static final class Decoded {
        public final String text;
        public final String encoding;
        public final boolean hadBom;
        public final boolean repaired;

        Decoded(String text, String encoding, boolean hadBom, boolean repaired) {
            this.text = text;
            this.encoding = encoding;
            this.hadBom = hadBom;
            this.repaired = repaired;
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

    /** Bytes → text. Never throws for content; throws IllegalArgumentException for empty / oversized input. */
    public static Decoded decode(byte[] raw) {
        if (raw == null || raw.length == 0) throw new IllegalArgumentException("ملف الترجمة فارغ.");
        if (raw.length > MAX_BYTES) throw new IllegalArgumentException("ملف الترجمة كبير جداً (الحد 8 ميغابايت).");
        int b0 = raw[0] & 0xFF, b1 = raw.length > 1 ? raw[1] & 0xFF : -1, b2 = raw.length > 2 ? raw[2] & 0xFF : -1;
        if (b0 == 0xEF && b1 == 0xBB && b2 == 0xBF) {
            return finish(new String(raw, 3, raw.length - 3, UTF8), "UTF-8", true);
        }
        if (b0 == 0xFF && b1 == 0xFE) return finish(new String(raw, 2, raw.length - 2, cs("UTF-16LE")), "UTF-16LE", true);
        if (b0 == 0xFE && b1 == 0xFF) return finish(new String(raw, 2, raw.length - 2, cs("UTF-16BE")), "UTF-16BE", true);
        String utf16 = sniffUtf16(raw);
        if (utf16 != null) return finish(new String(raw, cs(utf16)), utf16, false);
        try {
            String s = UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw)).toString();
            return finish(s, "UTF-8", false);
        } catch (CharacterCodingException notUtf8) {
            Charset w1256 = cs("windows-1256");
            if (w1256 != null) return finish(new String(raw, w1256), "windows-1256", false);
            return finish(new String(raw, UTF8), "UTF-8(lossy)", false);
        }
    }

    /** UTF-16 without a BOM: ASCII timestamps / newlines leave a 0x00 in every other byte. */
    private static String sniffUtf16(byte[] raw) {
        int n = Math.min(raw.length, 4096) & ~1;
        if (n < 16) return null;
        int zeroEven = 0, zeroOdd = 0;
        for (int i = 0; i < n; i++) if (raw[i] == 0) { if ((i & 1) == 0) zeroEven++; else zeroOdd++; }
        int half = n / 2;
        if (zeroOdd > half * 0.2 && zeroEven < half * 0.02) return "UTF-16LE";
        if (zeroEven > half * 0.2 && zeroOdd < half * 0.02) return "UTF-16BE";
        return null;
    }

    private static Decoded finish(String s, String encName, boolean bom) {
        String enc = bom ? encName + "+BOM" : encName;
        String t = s;
        while (t.length() > 0 && t.charAt(0) == '﻿') t = t.substring(1);
        t = t.replace("\u0000", "");
        String repaired = repairMojibake(t);
        if (repaired != null) return new Decoded(repaired, enc + "+mojibake-repaired", bom, true);
        return new Decoded(t, enc, bom, false);
    }

    // ------------------------------------------------------------------ Arabic helpers

    public static boolean isArabic(int cp) {
        return (cp >= 0x0600 && cp <= 0x06FF) || (cp >= 0x0750 && cp <= 0x077F) || (cp >= 0x08A0 && cp <= 0x08FF)
                || (cp >= 0xFB50 && cp <= 0xFDFF) || (cp >= 0xFE70 && cp <= 0xFEFF);
    }

    public static boolean isArabicLetter(int cp) {
        return isArabic(cp) && Character.isLetter(cp);
    }

    /** Share of Arabic letters among all letters (0 when there are no letters). */
    public static double arabicShare(String s) {
        int letters = 0, ar = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            letters++;
            if (isArabic(cp)) ar++;
        }
        return letters == 0 ? 0 : (double) ar / letters;
    }

    public static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (isArabicLetter(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static final String CP1252_HIGH = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F"
            + "\u0090‘’“”•–—˜™š›œ\u009DžŸ";

    /** Text → the single bytes it came from if it was decoded as Windows-1252 / Latin-1, or null. */
    private static byte[] asLatinBytes(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80 || (c >= 0xA0 && c <= 0xFF)) { out[i] = (byte) c; continue; }
            int k = CP1252_HIGH.indexOf(c);
            if (k >= 0) { out[i] = (byte) (0x80 + k); continue; }
            if (c >= 0x80 && c <= 0x9F) { out[i] = (byte) c; continue; }
            return null;
        }
        return out;
    }

    /**
     * Arabic that went through a wrong decoder once: UTF-8 bytes read as Windows-1252 ("Ø§Ù„Ø³Ù„Ø§Ù…") or
     * Windows-1256 bytes read as Windows-1252 ("ÇáÓáÇã"). Returns the repaired text, or null when nothing to repair.
     */
    static String repairMojibake(String s) {
        int latinHigh = 0, letters = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) letters++;
            if (c >= 0xC0 && c <= 0xFF) latinHigh++;
        }
        if (letters == 0 || latinHigh < 20 || latinHigh < letters * 0.25 || arabicShare(s) > 0.3) return null;
        byte[] b = asLatinBytes(s);
        if (b == null) return null;
        double before = arabicShare(s);
        String best = null;
        double bestShare = before;
        try {
            String u = UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b)).toString();
            double sh = arabicShare(u);
            if (sh > bestShare) { best = u; bestShare = sh; }
        } catch (CharacterCodingException ignored) { }
        Charset w1256 = cs("windows-1256");
        if (w1256 != null) {
            String a = new String(b, w1256);
            double sh = arabicShare(a);
            if (sh > bestShare) { best = a; bestShare = sh; }
        }
        return best != null && bestShare >= 0.5 && bestShare > before + 0.3 ? best : null;
    }

    // ------------------------------------------------------------------ parsing

    private static final String STAMP = "(?:(\\d{1,3}):)?(\\d{1,2}):(\\d{1,2})(?:([.,:])(\\d{1,9}))?";
    static final Pattern TIMING = Pattern.compile("^\\s*" + STAMP + "\\s*-{1,2}>\\s*" + STAMP + "(.*)$");
    private static final Pattern NUMBER = Pattern.compile("^\\s*\\d{1,7}\\s*$");

    /** Result of reading one timestamp: ms, or -1 when invalid. */
    private static final class Stamp {
        long ms = -1;
        boolean nonStandard;
        boolean dot;
    }

    private static Stamp readStamp(Matcher m, int g) {
        Stamp st = new Stamp();
        String h = m.group(g), mi = m.group(g + 1), se = m.group(g + 2), sep = m.group(g + 3), fr = m.group(g + 4);
        long hours = h == null ? 0 : Long.parseLong(h);
        long minutes = Long.parseLong(mi);
        long seconds = Long.parseLong(se);
        if (seconds >= 60 || (h != null && minutes >= 60)) return st;
        long ms = 0;
        if (fr != null) {
            if (":".equals(sep) && fr.length() != 3) return st; // "00:00:01:12" = frames, fps unknown: not guessed
            int len = fr.length();
            if (len <= 3) {
                long v = Long.parseLong(fr);
                for (int i = len; i < 3; i++) v *= 10;
                ms = v;
            } else {
                double v = Double.parseDouble("0." + fr);
                ms = Math.round(v * 1000.0);
                if (ms >= 1000) ms = 999;
            }
            st.nonStandard = len != 3;
            st.dot = ".".equals(sep);
        }
        st.ms = ((hours * 60 + minutes) * 60 + seconds) * 1000 + ms;
        return st;
    }

    /** Bytes → document (encoding detected). */
    public static SubDocument read(byte[] raw) {
        Decoded d = decode(raw);
        return parse(d.text, d.encoding);
    }

    /** Text → document. Tolerant: malformed blocks are skipped and counted, never invented. */
    public static SubDocument parse(String input, String encoding) {
        String text = input == null ? "" : input;
        while (text.length() > 0 && text.charAt(0) == '﻿') text = text.substring(1);
        text = text.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "");
        String[] lines = text.split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].trim().length() == 0) i++;
        boolean vtt = i < lines.length && lines[i].trim().startsWith("WEBVTT");
        StringBuilder header = new StringBuilder();
        String tsMap = "";
        List<String> warnings = new ArrayList<String>();
        if (vtt) {
            // header: the WEBVTT line's tail and the lines up to the first blank line
            String tail = lines[i].trim().substring(6).trim();
            if (tail.length() > 0) header.append(tail).append('\n');
            i++;
            while (i < lines.length && lines[i].trim().length() > 0 && !TIMING.matcher(lines[i]).matches()) {
                String l = lines[i].trim();
                if (l.startsWith("X-TIMESTAMP-MAP")) tsMap = l;
                header.append(l).append('\n');
                i++;
            }
        }
        List<SubCue> cues = new ArrayList<SubCue>();
        int skipped = 0, nonStd = 0, dotSrt = 0, index = 0;
        boolean inCue = false;
        boolean inSkipBlock = false;   // NOTE block (dropped) / STYLE or REGION block (kept in header)
        boolean keepBlock = false;
        String pendingId = "";
        long curStart = 0, curEnd = 0;
        String curId = "", curSettings = "";
        StringBuilder curText = null;
        for (; i < lines.length; i++) {
            String line = lines[i];
            String tr = line.trim();
            if (tr.length() == 0) {
                if (inCue) {
                    cues.add(new SubCue(++index, curId, curStart, curEnd, trimLines(curText), curSettings));
                    inCue = false;
                }
                if (inSkipBlock && keepBlock) header.append('\n');
                inSkipBlock = false;
                keepBlock = false;
                pendingId = "";
                continue;
            }
            if (inSkipBlock) {
                if (keepBlock) header.append(line).append('\n');
                continue;
            }
            Matcher m = TIMING.matcher(line);
            if (m.matches()) {
                if (inCue) cues.add(new SubCue(++index, curId, curStart, curEnd, trimLines(curText), curSettings));
                Stamp a = readStamp(m, 1);
                Stamp b = readStamp(m, 6);
                if (a.ms < 0 || b.ms < 0) {
                    skipped++;
                    if (warnings.size() < 30) warnings.add("سطر توقيت غير صالح تم تجاهله: " + tr);
                    inCue = false;
                    // the text of a skipped cue is ignored until the next blank line
                    curText = null;
                    inSkipBlock = true;
                    keepBlock = false;
                    pendingId = "";
                    continue;
                }
                if (a.nonStandard || b.nonStandard) nonStd++;
                if (!vtt && (a.dot || b.dot)) dotSrt++;
                curStart = a.ms;
                curEnd = b.ms;
                curId = pendingId;
                curSettings = vtt ? m.group(11).trim() : "";
                curText = new StringBuilder();
                inCue = true;
                pendingId = "";
                continue;
            }
            // a cue number / identifier directly followed by its timing line (also when the blank line is missing)
            boolean nextIsTiming = i + 1 < lines.length && TIMING.matcher(lines[i + 1]).matches();
            if (nextIsTiming && (!inCue || NUMBER.matcher(tr).matches())) {
                if (inCue) {
                    cues.add(new SubCue(++index, curId, curStart, curEnd, trimLines(curText), curSettings));
                    inCue = false;
                }
                pendingId = tr;
                continue;
            }
            if (inCue) {
                if (curText.length() > 0) curText.append('\n');
                curText.append(stripEnd(line));
                continue;
            }
            if (vtt && (tr.startsWith("NOTE") || tr.equals("STYLE") || tr.startsWith("STYLE ") || tr.equals("REGION") || tr.startsWith("REGION "))) {
                inSkipBlock = true;
                keepBlock = !tr.startsWith("NOTE") && cues.isEmpty();
                if (keepBlock) header.append('\n').append(tr).append('\n');
                continue;
            }
            if (!NUMBER.matcher(tr).matches()) {
                skipped++;
                if (warnings.size() < 30) warnings.add("نص خارج أي مقطع تم تجاهله: " + (tr.length() > 60 ? tr.substring(0, 60) + "…" : tr));
                inSkipBlock = true;
                keepBlock = false;
            }
        }
        if (inCue) cues.add(new SubCue(++index, curId, curStart, curEnd, trimLines(curText), curSettings));
        if (tsMap.length() > 0) warnings.add("الملف يحمل X-TIMESTAMP-MAP (مقطع من بث HLS): يُحفظ كما هو ولا يُزاح تلقائياً — راجع التوقيت مع الفيديو.");
        if (nonStd > 0) warnings.add(nonStd + " مقطعاً كُتب جزء الثانية فيه بغير 3 أرقام (مثل ,5 أو ,50) — مشغّل ExoPlayer يقرؤها مبكرة؛ تُكتب الآن بثلاثة أرقام.");
        if (dotSrt > 0) warnings.add(dotSrt + " مقطعاً في ملف SRT استخدم «.» بدل «,» — كان ExoPlayer يُسقطها؛ تُكتب الآن بالصيغة الصحيحة.");
        return new SubDocument(vtt ? SubDocument.Format.VTT : SubDocument.Format.SRT, cues, header.toString().trim(), encoding,
                tsMap, warnings, skipped, nonStd, dotSrt);
    }

    private static String stripEnd(String s) {
        int e = s.length();
        while (e > 0 && (s.charAt(e - 1) == ' ' || s.charAt(e - 1) == '\t')) e--;
        return s.substring(0, e);
    }

    private static String trimLines(StringBuilder b) {
        if (b == null) return "";
        String s = b.toString();
        while (s.endsWith("\n")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // ------------------------------------------------------------------ writing

    /** 00:01:02,500 (sep ',') or 00:01:02.500 (sep '.'). Hours have at least 2 digits. Negative values clamp to 0. */
    public static String stamp(long ms, char sep) {
        long t = Math.max(0, ms);
        long h = t / 3600000, m = (t / 60000) % 60, s = (t / 1000) % 60, f = t % 1000;
        StringBuilder b = new StringBuilder(12);
        if (h < 10) b.append('0');
        b.append(h).append(':');
        if (m < 10) b.append('0');
        b.append(m).append(':');
        if (s < 10) b.append('0');
        b.append(s).append(sep);
        if (f < 100) b.append('0');
        if (f < 10) b.append('0');
        b.append(f);
        return b.toString();
    }

    private static final Pattern AN_TAG = Pattern.compile("\\{\\\\an([1-9])\\}");

    /** UTF-8 SRT, numbered 1..n, three millisecond digits. Blank cues are left out (they show nothing). */
    public static String toSrt(SubDocument d) {
        StringBuilder out = new StringBuilder(d.size() * 64);
        int n = 0;
        for (SubCue c : d.cues) {
            if (c.isBlank()) continue;
            String body = d.format == SubDocument.Format.VTT ? vttTextToSrt(c) : c.text;
            body = body.replace("-->", "→");
            if (out.length() > 0) out.append('\n');
            out.append(++n).append('\n')
                    .append(stamp(c.startMs, ',')).append(" --> ").append(stamp(c.endMs, ',')).append('\n')
                    .append(body).append('\n');
        }
        return out.toString();
    }

    /** UTF-8 WebVTT. A VTT source keeps its header (STYLE / REGION / X-TIMESTAMP-MAP), identifiers and cue settings. */
    public static String toVtt(SubDocument d) {
        StringBuilder out = new StringBuilder(d.size() * 64 + 32);
        out.append("WEBVTT\n");
        if (d.format == SubDocument.Format.VTT && d.vttHeader.length() > 0) out.append(d.vttHeader).append('\n');
        for (SubCue c : d.cues) {
            if (c.isBlank()) continue;
            out.append('\n');
            String settings = c.settings;
            String body = c.text;
            if (d.format == SubDocument.Format.SRT) {
                Matcher an = AN_TAG.matcher(body);
                if (an.find()) {
                    int pos = Integer.parseInt(an.group(1));
                    if (pos >= 7) settings = "line:10%";
                    else if (pos >= 4) settings = "line:50%";
                }
                body = srtTextToVtt(body);
            } else if (c.id.length() > 0 && !c.id.contains("-->")) {
                out.append(c.id).append('\n');
            }
            body = body.replace("-->", "→");
            out.append(stamp(c.startMs, '.')).append(" --> ").append(stamp(c.endMs, '.'));
            if (settings.length() > 0) out.append(' ').append(settings);
            out.append('\n').append(body).append('\n');
        }
        return out.toString();
    }

    public static String write(SubDocument d, SubDocument.Format f) {
        return f == SubDocument.Format.VTT ? toVtt(d) : toSrt(d);
    }

    public static byte[] writeBytes(SubDocument d, SubDocument.Format f) {
        return write(d, f).getBytes(UTF8);
    }

    private static String vttTextToSrt(SubCue c) {
        String t = c.text;
        t = t.replaceAll("<\\d{1,3}:\\d{2}(?::\\d{2})?[.,]\\d{1,3}>", "");
        t = t.replaceAll("</?(c|v|lang|ruby|rt)(\\.[^\\s>]*)?(\\s[^>]*)?>", "");
        String s = c.settings.toLowerCase(Locale.US);
        Matcher lm = Pattern.compile("line:(-?\\d+(?:\\.\\d+)?)(%?)").matcher(s);
        if (lm.find()) {
            double v = Double.parseDouble(lm.group(1));
            boolean pct = "%".equals(lm.group(2));
            boolean top = pct ? v <= 40 : v >= 0 && v <= 3;
            if (top) t = "{\\an8}" + t;
        }
        return t;
    }

    private static String srtTextToVtt(String text) {
        String t = text.replaceAll("\\{\\\\[^}]*\\}", "");
        t = t.replaceAll("</?font[^>]*>", "");
        StringBuilder b = new StringBuilder(t.length() + 8);
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '&') {
                int semi = t.indexOf(';', i);
                boolean entity = semi > i && semi - i <= 8 && t.substring(i + 1, semi).matches("#?[A-Za-z0-9]+");
                b.append(entity ? "&" : "&amp;");
            } else if (ch == '<') {
                String rest = t.substring(i);
                boolean tag = rest.matches("(?s)^</?(i|b|u)>.*");
                b.append(tag ? "<" : "&lt;");
            } else {
                b.append(ch);
            }
        }
        return b.toString();
    }

    /** Detects the format of a file by its content (never by its URL). */
    public static SubDocument.Format sniff(String text) {
        String t = text == null ? "" : text.trim();
        while (t.length() > 0 && t.charAt(0) == '﻿') t = t.substring(1);
        return t.startsWith("WEBVTT") ? SubDocument.Format.VTT : SubDocument.Format.SRT;
    }
}
