package com.Cigram.subhub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CigramSubtitleHub / CigramSubtitleSyncEngine — timing, encoding and QA tests (JVM, no device needed).
 * The same file exists in the admin project; both copies must stay identical.
 */
public class SubHubTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static SubDocument srt(String s) {
        return SubCodec.parse(s, "UTF-8");
    }

    // ---------------------------------------------------------------- the root cause

    /**
     * Re-implementation of ExoPlayer 2.19.1 SubripDecoder.parseTimecode as disassembled from the shipped AAR:
     * pattern "(?:(\d+):)?(\d+):(\d+)(?:,(\d+))?" and the fraction ADDED as raw milliseconds.
     */
    private static long exoSubripMs(String stamp) {
        Matcher m = Pattern.compile("(?:(\\d+):)?(\\d+):(\\d+)(?:,(\\d+))?").matcher(stamp);
        if (!m.matches()) return -1;   // ExoPlayer: "Skipping invalid timing" — the cue is dropped
        long t = m.group(1) != null ? Long.parseLong(m.group(1)) * 3600000L : 0;
        t += Long.parseLong(m.group(2)) * 60000L;
        t += Long.parseLong(m.group(3)) * 1000L;
        if (m.group(4) != null) t += Long.parseLong(m.group(4));
        return t;
    }

    @Test
    public void exoplayerReadsShortFractionsEarly_engineDoesNot() {
        // what the old player did with a centisecond file
        assertEquals(10005, exoSubripMs("00:00:10,5"));
        assertEquals(10050, exoSubripMs("00:00:10,50"));
        assertEquals(-1, exoSubripMs("00:00:10.500"));          // '.' in SRT: cue dropped by ExoPlayer
        // what the engine reads
        SubDocument d = srt("1\n00:00:10,5 --> 00:00:12,25\nمرحباً\n\n2\n00:00:13.500 --> 00:00:15.000\nأهلاً\n");
        assertEquals(2, d.size());
        assertEquals(10500, d.cues.get(0).startMs);
        assertEquals(12250, d.cues.get(0).endMs);
        assertEquals(13500, d.cues.get(1).startMs);
        assertEquals(1, d.nonStandardFractions);
        assertEquals(1, d.dotSeparatorsInSrt);
        // the normalized file is read correctly by ExoPlayer's own pattern
        String out = SubCodec.toSrt(d);
        assertTrue(out.contains("00:00:10,500 --> 00:00:12,250"));
        assertTrue(out.contains("00:00:13,500 --> 00:00:15,000"));
        assertEquals(10500, exoSubripMs("00:00:10,500"));
    }

    @Test
    public void fourDigitFractionAndMissingHours() {
        SubDocument d = srt("1\n01:02,1234 --> 01:03,9999\nx\n");
        assertEquals(62123, d.cues.get(0).startMs);
        assertEquals(63999, d.cues.get(0).endMs);
    }

    @Test
    public void frameStampsAreNotGuessed() {
        SubDocument d = srt("1\n00:00:01:12 --> 00:00:02:00\nframes\n\n2\n00:00:03,000 --> 00:00:04,000\nok\n");
        assertEquals(1, d.size());
        assertEquals(1, d.skippedCues);
        assertEquals("ok", d.cues.get(0).text);
    }

    @Test
    public void standardRoundTripIsByteExactOnTimes() {
        String src = "1\n00:00:01,000 --> 00:00:02,500\nسطر أول\nسطر ثانٍ\n\n2\n01:59:59,999 --> 02:00:00,001\n<i>مائل</i>\n";
        SubDocument d = srt(src);
        SubDocument again = srt(SubCodec.toSrt(d));
        assertEquals(d.size(), again.size());
        for (int k = 0; k < d.size(); k++) {
            assertEquals(d.cues.get(k).startMs, again.cues.get(k).startMs);
            assertEquals(d.cues.get(k).endMs, again.cues.get(k).endMs);
            assertEquals(d.cues.get(k).text, again.cues.get(k).text);
        }
        assertEquals(7199999, d.cues.get(1).startMs);
    }

    @Test
    public void missingBlankLinesBetweenCues() {
        SubDocument d = srt("1\n00:00:01,000 --> 00:00:02,000\nأ\n2\n00:00:03,000 --> 00:00:04,000\nب\n3\n00:00:05,000 --> 00:00:06,000\nج");
        assertEquals(3, d.size());
        assertEquals("ب", d.cues.get(1).text);
        assertEquals("2", d.cues.get(1).id);
    }

    // ---------------------------------------------------------------- WebVTT

    @Test
    public void vttKeepsHeaderSettingsAndIdsAndNeverAppliesTimestampMap() {
        String vtt = "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000\n\nSTYLE\n::cue { color: yellow }\n\nNOTE هذا تعليق\nيُهمل\n\n"
                + "intro\n00:01.000 --> 00:02.000 line:10% align:start\n<v Ali>مرحباً</v>\n\n00:00:03.5 --> 00:00:04.000\nثانٍ\n";
        SubDocument d = SubCodec.parse(vtt, "UTF-8");
        assertEquals(SubDocument.Format.VTT, d.format);
        assertEquals(2, d.size());
        assertTrue(d.hasTimestampMap);
        assertEquals(1000, d.cues.get(0).startMs);       // NOT moved by the 10 s of MPEGTS:900000
        assertEquals("intro", d.cues.get(0).id);
        assertEquals("line:10% align:start", d.cues.get(0).settings);
        assertEquals(3500, d.cues.get(1).startMs);
        String out = SubCodec.toVtt(d);
        assertTrue(out.startsWith("WEBVTT\n"));
        assertTrue(out.contains("X-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000"));
        assertTrue(out.contains("::cue { color: yellow }"));
        assertFalse(out.contains("NOTE"));
        assertTrue(out.contains("00:00:01.000 --> 00:00:02.000 line:10% align:start"));
        assertTrue(out.contains("00:00:03.500 --> 00:00:04.000"));
        // to SRT: VTT-only tags removed, top position kept as {\an8}
        String s = SubCodec.toSrt(d);
        assertTrue(s.contains("{\\an8}مرحباً"));
        assertFalse(s.contains("<v"));
        SubQa.Report r = SubQa.analyze(d, new SubQa.Options());
        assertEquals(1, r.count("TIMESTAMP_MAP"));
        assertTrue(r.needsManualReview());
    }

    @Test
    public void srtToVttEscapesAndPositions() {
        SubDocument d = srt("1\n00:00:01,000 --> 00:00:02,000\n{\\an8}A & B <i>x</i> 3<4\n");
        String v = SubCodec.toVtt(d);
        assertTrue(v.contains("00:00:01.000 --> 00:00:02.000 line:10%"));
        assertTrue(v.contains("A &amp; B <i>x</i> 3&lt;4"));
    }

    @Test
    public void formatIsDetectedFromContentNotUrl() {
        assertEquals(SubDocument.Format.VTT, SubCodec.sniff("﻿WEBVTT\n\n00:01.000 --> 00:02.000\nx"));
        assertEquals(SubDocument.Format.SRT, SubCodec.sniff("1\n00:00:01,000 --> 00:00:02,000\nx"));
    }

    // ---------------------------------------------------------------- encodings

    @Test
    public void windows1256IsDetected() {
        byte[] raw = "1\n00:00:01,000 --> 00:00:02,000\nالسلام عليكم\n".getBytes(Charset.forName("windows-1256"));
        SubDocument d = SubCodec.read(raw);
        assertEquals("windows-1256", d.encoding);
        assertEquals("السلام عليكم", d.cues.get(0).text);
    }

    @Test
    public void bomsAndUtf16() {
        String body = "1\n00:00:01,000 --> 00:00:02,000\nمرحبا\n";
        byte[] u8 = body.getBytes(UTF8);
        byte[] bom = new byte[u8.length + 3];
        bom[0] = (byte) 0xEF; bom[1] = (byte) 0xBB; bom[2] = (byte) 0xBF;
        System.arraycopy(u8, 0, bom, 3, u8.length);
        SubDocument a = SubCodec.read(bom);
        assertEquals("UTF-8+BOM", a.encoding);
        assertEquals("1", a.cues.get(0).id);
        assertEquals(1, SubQa.analyze(a, new SubQa.Options()).count("BOM_REMOVED"));
        SubDocument b = SubCodec.read(body.getBytes(Charset.forName("UTF-16")));   // with BOM (big endian)
        assertEquals("مرحبا", b.cues.get(0).text);
        SubDocument c = SubCodec.read(body.getBytes(Charset.forName("UTF-16LE"))); // no BOM
        assertEquals("UTF-16LE", c.encoding);
        assertEquals("مرحبا", c.cues.get(0).text);
    }

    @Test
    public void mojibakeIsRepaired() {
        String arabic = "1\n00:00:01,000 --> 00:00:02,000\nالسلام عليكم ورحمة الله وبركاته يا أصدقائي الأعزاء\n";
        String broken = new String(arabic.getBytes(UTF8), Charset.forName("windows-1252"));   // Ø§Ù„Ø³Ù„Ø§Ù…
        SubDocument d = SubCodec.read(broken.getBytes(UTF8));
        assertTrue(d.encoding.contains("mojibake"));
        assertEquals("السلام عليكم ورحمة الله وبركاته يا أصدقائي الأعزاء", d.cues.get(0).text);
        String broken1256 = new String(arabic.getBytes(Charset.forName("windows-1256")), Charset.forName("windows-1252")); // ÇáÓáÇã
        SubDocument e = SubCodec.read(broken1256.getBytes(UTF8));
        assertEquals("السلام عليكم ورحمة الله وبركاته يا أصدقائي الأعزاء", e.cues.get(0).text);
    }

    // ---------------------------------------------------------------- timeline + delay

    @Test
    public void delayIsAppliedOnceAndSignIsLater() {
        SubDocument d = srt("1\n00:00:10,000 --> 00:00:12,000\nأ\n");
        SubSync.Timeline t = new SubSync.Timeline(d);
        assertEquals(1, t.activeAt(10000, 0).size());
        assertEquals(0, t.activeAt(9999, 0).size());
        assertEquals(0, t.activeAt(12000, 0).size());
        // +500 ms: the text that came before the speech now appears half a second later
        assertEquals(0, t.activeAt(10400, 500).size());
        assertEquals(1, t.activeAt(10500, 500).size());
        assertEquals(1, t.activeAt(12499, 500).size());
        assertEquals(0, t.activeAt(12500, 500).size());
        // negative delay
        assertEquals(1, t.activeAt(9000, -1000).size());
        assertEquals(12500, t.nextChangeAfter(10600, 500));
        assertEquals(10500, t.nextChangeAfter(0, 500));
        // an approved correction baked into the file + a personal delay add up once each
        SubDocument corrected = SubSync.shift(d, 300).document;
        SubSync.Timeline t2 = new SubSync.Timeline(corrected);
        assertEquals(0, t2.activeAt(10799, 500).size());
        assertEquals(1, t2.activeAt(10800, 500).size());
    }

    @Test
    public void overlappingCuesAndLongCueAreAllFound() {
        SubDocument d = srt("1\n00:00:00,000 --> 00:01:00,000\nطويل\n\n2\n00:00:10,000 --> 00:00:11,000\nقصير\n\n3\n00:00:10,500 --> 00:00:12,000\nمتداخل\n");
        SubSync.Timeline t = new SubSync.Timeline(d);
        List<SubCue> on = t.activeAt(10700, 0);
        assertEquals(3, on.size());
        assertEquals(1, on.get(0).index);
        assertEquals(1, t.activeAt(59000, 0).size());
    }

    @Test
    public void invalidAndBlankCuesAreNeverShown() {
        SubDocument d = srt("1\n00:00:05,000 --> 00:00:04,000\nمعكوس\n\n2\n00:00:06,000 --> 00:00:07,000\n\n\n3\n00:00:08,000 --> 00:00:09,000\nسليم\n");
        SubSync.Timeline t = new SubSync.Timeline(d);
        assertEquals(1, t.size());
        SubQa.Report r = SubQa.analyze(d, new SubQa.Options());
        assertEquals(1, r.count("INVALID_TIMING"));
        assertEquals(SubQa.Verdict.FAIL, r.verdict());
    }

    @Test
    public void shiftClampsAndDropsButKeepsOrderAndIndex() {
        SubDocument d = srt("1\n00:00:00,500 --> 00:00:01,000\nأ\n\n2\n00:00:01,500 --> 00:00:03,000\nب\n\n3\n00:00:05,000 --> 00:00:06,000\nج\n");
        SubSync.ShiftResult r = SubSync.shift(d, -2000);
        assertEquals(1, r.dropped);
        assertEquals(1, r.clamped);
        assertEquals(2, r.document.size());
        assertEquals(0, r.document.cues.get(0).startMs);
        assertEquals(1000, r.document.cues.get(0).endMs);
        assertEquals(2, r.document.cues.get(0).index);
        assertEquals(3000, r.document.cues.get(1).startMs);
        assertEquals(d, SubSync.shift(d, 0).document);
    }

    @Test
    public void frameRateStretch() {
        SubDocument d = srt("1\n00:10:00,000 --> 00:10:02,000\nأ\n");
        SubSync.ShiftResult r = SubSync.stretch(d, 25.0 / 23.976, 0);
        assertEquals(625626, r.document.cues.get(0).startMs);
    }

    // ---------------------------------------------------------------- translation keeps the timing

    @Test
    public void translationKeepsSourceTimingAndIndices() {
        SubDocument en = srt("1\n00:00:01,100 --> 00:00:02,200\nHello\n\n2\n00:00:03,300 --> 00:00:04,400\nHow are you?\n");
        SubDocument ar = SubSync.applyTranslation(en, Arrays.asList("مرحباً", "كيف حالك؟"));
        assertTrue(SubSync.compareTiming(en, ar, 0).preserved());
        assertEquals(2, ar.cues.get(1).index);
        assertEquals(3300, ar.cues.get(1).startMs);
        // a provider that moved the times: the texts go back onto the source timing
        SubDocument moved = srt("1\n00:00:01,000 --> 00:00:02,000\nمرحباً\n\n2\n00:00:03,000 --> 00:00:04,000\nكيف حالك؟\n");
        SubSync.TimingReport tr = SubSync.compareTiming(en, moved, 1);
        assertEquals(2, tr.drifted);
        assertEquals(400, tr.maxDriftMs);
        SubDocument fixed = SubSync.restoreSourceTiming(en, moved);
        assertNotNull(fixed);
        assertTrue(SubSync.compareTiming(en, fixed, 0).preserved());
        // a merged / split translation cannot be "repaired" by guessing
        SubDocument merged = srt("1\n00:00:01,100 --> 00:00:04,400\nمرحباً كيف حالك؟\n");
        assertNull(SubSync.restoreSourceTiming(en, merged));
        SubQa.Report r = SubQa.analyze(merged, en, new SubQa.Options());
        assertEquals(1, r.count("CUE_COUNT_MISMATCH"));
        assertEquals(SubQa.Verdict.FAIL, r.verdict());
    }

    @Test(expected = IllegalArgumentException.class)
    public void translationWithWrongCountIsRefused() {
        SubDocument en = srt("1\n00:00:01,000 --> 00:00:02,000\nHello\n");
        SubSync.applyTranslation(en, new ArrayList<String>());
    }

    @Test
    public void qaFindsMissingAndUntranslatedCues() {
        SubDocument en = srt("1\n00:00:01,000 --> 00:00:03,000\nHello there\n\n2\n00:00:04,000 --> 00:00:06,000\nGood night\n\n3\n00:00:07,000 --> 00:00:09,000\nSee you\n");
        SubDocument ar = SubSync.applyTranslation(en, Arrays.asList("مرحباً", "", "See you"));
        SubQa.Report r = SubQa.analyze(ar, en, new SubQa.Options());
        assertEquals(1, r.count("MISSING_TRANSLATION"));
        assertEquals(1, r.count("UNTRANSLATED"));
        assertEquals(0, r.count("TIMESTAMP_DRIFT"));
    }

    // ---------------------------------------------------------------- QA rules

    @Test
    public void qaTimingRules() {
        SubDocument d = srt("1\n00:00:01,000 --> 00:00:03,000\nسطر عادي للقراءة\n\n"
                + "2\n00:00:02,500 --> 00:00:04,000\nمتداخل مع السابق\n\n"
                + "3\n00:00:05,000 --> 00:00:05,300\nقصير جداً\n\n"
                + "4\n00:00:06,000 --> 00:00:07,000\nهذا نص طويل جداً لا يمكن قراءته في ثانية واحدة أبداً مهما كان القارئ سريعاً\n");
        SubQa.Report r = SubQa.analyze(d, new SubQa.Options());
        assertEquals(1, r.count("OVERLAP"));
        assertEquals(1, r.count("SHORT_DURATION"));
        assertTrue(r.count("HIGH_CPS") >= 1);
        assertTrue(r.count("LINE_TOO_LONG") >= 1);
        assertEquals(SubQa.Verdict.REVIEW, r.verdict());
    }

    @Test
    public void qaArabicPunctuationAndRtl() {
        SubDocument d = srt("1\n00:00:01,000 --> 00:00:04,000\nهل أنت بخير?\n\n2\n00:00:05,000 --> 00:00:08,000\nNetflix هو اسم الشركة\n\n3\n00:00:09,000 --> 00:00:12,000\n‏Netflix هو اسم الشركة\n");
        SubQa.Report r = SubQa.analyze(d, new SubQa.Options());
        assertEquals(1, r.count("ARABIC_PUNCTUATION"));
        assertEquals(1, r.count("RTL_LEADING_LTR"));
        SubDocument fixed = SubQa.fixRtlLines(SubQa.fixArabicPunctuation(d));
        assertEquals("هل أنت بخير؟", fixed.cues.get(0).text);
        assertTrue(fixed.cues.get(1).text.startsWith("‏"));
        SubQa.Report r2 = SubQa.analyze(fixed, new SubQa.Options());
        assertEquals(0, r2.count("ARABIC_PUNCTUATION"));
        assertEquals(0, r2.count("RTL_LEADING_LTR"));
        // the fixes never move time
        assertTrue(SubSync.compareTiming(d, fixed, 0).preserved());
    }

    @Test
    public void qaNotArabicAndEncodingDamage() {
        SubDocument en = srt("1\n00:00:01,000 --> 00:00:03,000\nHello world\n");
        assertEquals(1, SubQa.analyze(en, new SubQa.Options()).count("NOT_ARABIC"));
        SubDocument bad = srt("1\n00:00:01,000 --> 00:00:03,000\nمرحبا ��\n");
        assertEquals(1, SubQa.analyze(bad, new SubQa.Options()).count("ENCODING_DAMAGE"));
    }

    @Test
    public void cleanArabicFilePasses() {
        SubDocument d = srt("1\n00:00:01,000 --> 00:00:03,500\nمرحباً بكم في Cigram\n\n2\n00:00:04,000 --> 00:00:06,500\nنتمنى لكم مشاهدة ممتعة\n");
        SubQa.Report r = SubQa.analyze(d, new SubQa.Options());
        assertEquals(r.issues.toString(), SubQa.Verdict.PASS, r.verdict());
    }

    @Test
    public void delayLabel() {
        assertEquals("0", SubSync.delayLabel(0));
        assertEquals("+0.50 ث", SubSync.delayLabel(500));
        assertEquals("−1.25 ث", SubSync.delayLabel(-1250));
        assertEquals(SubSync.MAX_DELAY_MS, SubSync.clampDelay(Long.MAX_VALUE));
    }
}
