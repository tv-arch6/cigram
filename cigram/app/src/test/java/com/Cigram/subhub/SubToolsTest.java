package com.Cigram.subhub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The subtitle toolbox: Auto Sync, Subtitle Delay, Timing Drift Fix, SRT Repair, Encoding Fixer, Overlap Detector,
 * Missing Lines Detector, FPS Converter, Subtitle Cleaner, Subtitle Preview (JVM, no device needed).
 * The same file exists in the admin project; both copies must stay identical.
 */
public class SubToolsTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset W1256 = Charset.forName("windows-1256");
    private static final Charset W1252 = Charset.forName("windows-1252");

    private static SubDocument srt(String s) {
        return SubCodec.parse(s, "UTF-8");
    }

    private static String cue(int n, String from, String to, String text) {
        return n + "\n" + from + " --> " + to + "\n" + text + "\n\n";
    }

    private static final String ARABIC_FILE =
            cue(1, "00:00:01,000", "00:00:03,000", "السلام عليكم يا صديقي")
                    + cue(2, "00:00:04,000", "00:00:06,500", "ماذا تفعل هنا في هذا الوقت؟")
                    + cue(3, "00:00:07,000", "00:00:09,000", "لا شيء، كنت أنتظر أن تأتي إلى هنا")
                    + cue(4, "00:00:10,000", "00:00:12,000", "هل أنت متأكد من ذلك؟ هذا ليس ما قلته لي");

    // ================================================================ Encoding Fixer

    @Test
    public void aWindows1256FileIsFoundAmongTheCandidatesAndReadAsArabic() {
        SubEncoding.Fix f = SubEncoding.fix(ARABIC_FILE.getBytes(W1256));
        assertEquals("windows-1256", f.encoding);
        assertTrue(f.changed);
        assertTrue(f.text.contains("السلام عليكم يا صديقي"));
        assertFalse(f.candidates.isEmpty());
        assertEquals("windows-1256", f.candidates.get(0).charset);
        assertEquals(0, f.lostCharacters);
        assertEquals(4, SubCodec.parse(f.text, f.encoding).size());
    }

    @Test
    public void aSpanishWindows1252FileIsNotTurnedIntoArabicLookingNoise() {
        String es = cue(1, "00:00:01,000", "00:00:03,000", "¿Qué pasó, señor? Estás aquí.")
                + cue(2, "00:00:04,000", "00:00:06,000", "Mañana será otro día, corazón.")
                + cue(3, "00:00:07,000", "00:00:09,000", "No sé qué decirte, compañero.")
                + cue(4, "00:00:10,000", "00:00:12,000", "¡Adiós! Volveré después del río.");
        SubEncoding.Fix f = SubEncoding.fix(es.getBytes(W1252));
        assertEquals("windows-1252", f.encoding);
        assertTrue(f.text.contains("¿Qué pasó, señor?"));
        assertFalse(SubCodec.hasArabic(f.text));
    }

    @Test
    public void utf8ArabicThatWasReadAsWindows1256IsReversed() {
        // the classic «ط§ظ„ط³ظ„ط§ظ…»: UTF-8 bytes shown through Windows-1256, then saved again as UTF-8
        String damaged = new String(ARABIC_FILE.getBytes(UTF8), W1256);
        assertTrue(damaged.contains("ط§ظ„ط³ظ„ط§ظ…"));
        SubEncoding.Fix f = SubEncoding.fix(damaged.getBytes(UTF8));
        assertTrue(f.text, f.text.contains("السلام عليكم يا صديقي"));
        assertTrue(f.text.contains("ماذا تفعل هنا في هذا الوقت؟"));
        assertEquals(4, f.repairedLines);
        assertTrue(f.encoding.contains("utf8-as-1256-repaired"));
        // timing lines were never touched
        SubDocument d = SubCodec.parse(f.text, f.encoding);
        assertEquals(4, d.size());
        assertEquals(4000, d.cues.get(1).startMs);
        assertEquals(6500, d.cues.get(1).endMs);
    }

    @Test
    public void damageAppliedTwiceIsReversedTwice() {
        String once = new String(ARABIC_FILE.getBytes(UTF8), W1256);
        String twice = new String(once.getBytes(UTF8), W1256);
        SubEncoding.Fix f = SubEncoding.fix(twice.getBytes(UTF8));
        assertTrue(f.text, f.text.contains("السلام عليكم يا صديقي"));
        assertTrue(f.text.contains("هل أنت متأكد من ذلك؟"));
    }

    @Test
    public void utf8ArabicThatWasReadAsLatinIsReversedLineByLine() {
        // half of the file is damaged, the other half is fine: only the damaged lines change
        String bad = new String("مرحباً بك في البيت".getBytes(UTF8), W1252);
        String mixed = cue(1, "00:00:01,000", "00:00:03,000", bad) + cue(2, "00:00:04,000", "00:00:06,000", "هذا السطر سليم تماماً");
        String out = SubEncoding.repairText(mixed);
        assertTrue(out, out.contains("مرحباً بك في البيت"));
        assertTrue(out.contains("هذا السطر سليم تماماً"));
        assertTrue(out.contains("00:00:01,000 --> 00:00:03,000"));
    }

    @Test
    public void theLetterFaaThatWindows1252DestroysIsRestored() {
        // Windows-1252 has no byte 0x81: the second byte of «ف» (D9 81) became U+FFFD in the damaged file
        String bad = new String("في البيت فقط".getBytes(UTF8), W1252);
        assertTrue(bad.indexOf('�') >= 0);
        assertEquals("في البيت فقط", SubEncoding.repairText(bad));
    }

    @Test
    public void presentationFormsBecomeOrdinaryLetters() {
        // "السلام" written with isolated / positional presentation forms (old DOS-era files)
        String forms = "ﺍﻟﺴﻼﻡ";
        SubEncoding.Fix f = SubEncoding.fix(cue(1, "00:00:01,000", "00:00:02,000", forms).getBytes(UTF8));
        assertTrue(f.text, f.text.contains("السلام"));
        assertEquals(5, f.presentationForms);
    }

    @Test
    public void lettersThatAreGoneForGoodAreCountedNotHidden() {
        SubEncoding.Fix f = SubEncoding.fix(cue(1, "00:00:01,000", "00:00:02,000", "????? ??? ???? ��").getBytes(UTF8));
        assertEquals(5 + 3 + 4 + 2, f.lostCharacters);
        assertFalse(f.notes.isEmpty());
        // a real question is not "lost text"
        assertEquals(0, SubEncoding.countLost("What? Really?? No."));
    }

    @Test
    public void aCleanUtf8FileIsLeftExactlyAsItIs() {
        SubEncoding.Fix f = SubEncoding.fix(ARABIC_FILE.getBytes(UTF8));
        assertEquals("UTF-8", f.encoding);
        assertFalse(f.changed);
        assertEquals(ARABIC_FILE, f.text);
        assertEquals(0, f.repairedLines);
    }

    @Test
    public void theAdminCanForceACharset() {
        SubEncoding.Fix f = SubEncoding.fix(ARABIC_FILE.getBytes(W1256), "windows-1256");
        assertTrue(f.text.contains("السلام عليكم"));
        assertTrue(f.candidates.isEmpty());
    }

    // ================================================================ SRT Repair

    @Test
    public void damagedTimingLinesAreRewrittenInStandardForm() {
        String broken =
                "1\n٠٠:٠٠:٠١,٠٠٠ --> ٠٠:٠٠:٠٢,٥٠٠\nأرقام هندية\n\n"
                        + "2\n00:00:03,000 → 00:00:04,000\nسهم يونيكود\n\n"
                        + "3\n00:00:05.000 => 00:00:06.000\nسهم مزدوج\n\n"
                        + "4\n00.00.07,000 --> 00.00.08,000\nنقاط بين الأجزاء\n\n"
                        + "5\n0O:00:09,000 --> 00:00:1O,000\nحرف O بدل الصفر\n\n"
                        + "6 00:00:11,000 --> 00:00:12,000\nرقم ملتصق\n\n"
                        + "7\n00:00:13,000 --> 00:00:14,000 نص ملتصق بالتوقيت\n\n"
                        + "8\n00:15,000 --> 00:16,000\nبلا ساعات\n\n"
                        + "9\n00:00:17;000 --> 00:00:18;500\nفاصلة منقوطة\n";
        SubRepair.Report r = SubRepair.repair(broken);
        assertEquals(r.notes.toString(), 9, r.cuesAfter);
        long[][] want = {{1000, 2500}, {3000, 4000}, {5000, 6000}, {7000, 8000}, {9000, 10000}, {11000, 12000}, {13000, 14000}, {15000, 16000}, {17000, 18500}};
        for (int k = 0; k < want.length; k++) {
            assertEquals("start of cue " + (k + 1), want[k][0], r.document.cues.get(k).startMs);
            assertEquals("end of cue " + (k + 1), want[k][1], r.document.cues.get(k).endMs);
        }
        assertEquals("نص ملتصق بالتوقيت", r.document.cues.get(6).text);
        assertEquals("رقم ملتصق", r.document.cues.get(5).text);
        assertEquals(0, r.unreadable);
        assertTrue(r.timingLinesRewritten >= 7);
        // the result is a standard SRT that parses with no warning at all
        SubDocument again = srt(r.srt);
        assertEquals(9, again.size());
        assertEquals(0, again.skippedCues);
        assertEquals(0, again.nonStandardFractions);
        assertEquals(0, again.dotSeparatorsInSrt);
        assertTrue(again.warnings.isEmpty());
    }

    @Test
    public void aMissingEndTimeIsRebuiltFromReadingTimeAndNeverPassesTheNextCue() {
        String broken = "1\n00:00:01,000 -->\nسطر قصير\n\n2\n00:00:02,000 --> 00:00:04,000\nالتالي\n\n3\n00:01:00,000 -->\nسطر أخير طويل قليلاً ليأخذ وقتاً أطول في القراءة\n";
        SubRepair.Report r = SubRepair.repair(broken);
        assertEquals(3, r.cuesAfter);
        SubCue first = r.document.cues.get(0), last = r.document.cues.get(2);
        assertEquals(1000, first.startMs);
        assertTrue("ends before the next cue", first.endMs < 2000 && first.endMs > 1000);
        assertEquals(60000, last.startMs);
        assertEquals(60000 + SubRepair.readingTimeMs(last.plainText()), last.endMs);
        assertEquals(2, r.zeroDurationFixed);
    }

    @Test
    public void structureIsRepairedEndBeforeStartOrderOverlapsDuplicatesEmptyAndAbsurdLength() {
        String broken =
                cue(1, "00:00:10,000", "00:00:12,000", "الثاني زمنياً")
                        + cue(2, "00:00:01,000", "00:00:03,000", "الأول زمنياً")
                        + cue(3, "00:00:22,000", "00:00:20,000", "النهاية قبل البداية")
                        + cue(4, "00:00:30,000", "00:00:36,000", "يتداخل مع التالي")
                        + cue(5, "00:00:34,000", "00:00:38,000", "التالي")
                        + cue(6, "00:00:34,000", "00:00:38,000", "التالي")
                        + cue(7, "00:00:40,000", "00:00:42,000", "   ")
                        + cue(8, "00:00:50,000", "00:15:50,000", "مدة غير معقولة")
                        + cue(9, "00:16:00,000", "00:16:02,000", "الأخير");
        SubRepair.Report r = SubRepair.repair(broken);
        assertEquals(1, r.emptyDropped);
        assertEquals(1, r.duplicatesDropped);
        assertEquals(1, r.reordered);
        assertEquals(1, r.endBeforeStartFixed);
        assertEquals(1, r.overlapsClosed);
        assertEquals(1, r.tooLongFixed);
        assertEquals(7, r.cuesAfter);
        List<SubCue> c = r.document.cues;
        assertEquals("الأول زمنياً", c.get(0).text);
        assertEquals("الثاني زمنياً", c.get(1).text);
        assertEquals(20000, c.get(2).startMs);
        assertEquals(22000, c.get(2).endMs);
        assertEquals(34000 - 84, c.get(3).endMs);
        assertTrue("the absurd cue now ends within reading time", c.get(5).durationMs() <= 7000);
        // numbered 1..n, strictly ordered, no overlap, every cue has a duration
        for (int k = 0; k < c.size(); k++) {
            assertEquals(k + 1, c.get(k).index);
            assertTrue(c.get(k).endMs > c.get(k).startMs);
            if (k > 0) assertTrue(c.get(k).startMs >= c.get(k - 1).endMs);
        }
        assertTrue(SubRepair.overlaps(r.document, 0).isEmpty());
        SubQa.Report qa = SubQa.analyze(r.document, new SubQa.Options());
        assertEquals(0, qa.count("INVALID_TIMING"));
        assertEquals(0, qa.count("ORDER"));
        assertEquals(0, qa.count("OVERLAP"));
    }

    @Test
    public void timestampsInFramesAreNeverGuessedButConvertedWhenTheRateIsGiven() {
        String frames = "1\n00:00:20:12 --> 00:00:22:00\nتوقيت بالإطارات\n\n2\n00:00:25,000 --> 00:00:26,000\nعادي\n";
        SubRepair.Report unknown = SubRepair.repair(frames);
        assertEquals(1, unknown.cuesAfter);
        assertEquals(1, unknown.unreadable);
        SubRepair.Options o = new SubRepair.Options();
        o.framesPerSecond = 25;
        SubRepair.Report known = SubRepair.repair(frames, "UTF-8", o);
        assertEquals(2, known.cuesAfter);
        assertEquals(20480, known.document.cues.get(0).startMs);
        assertEquals(22000, known.document.cues.get(0).endMs);
    }

    @Test
    public void aHealthyFileIsNotChangedAndDialogueWithArrowsOrTimesIsNotATimingLine() {
        String ok = cue(1, "00:00:01,000", "00:00:03,000", "He went -> there at 10:30")
                + cue(2, "00:00:04,000", "00:00:06,000", "Call me at 10:30:15 -> ok?")
                + cue(3, "00:00:07,000", "00:00:09,000", "From 9 to 5, every day.")
                + cue(4, "00:00:10,000", "00:00:12,000", "<i>Italic</i> stays\nSecond line");
        SubRepair.Report r = SubRepair.repair(ok);
        assertEquals(0, r.totalFixes());
        assertFalse(r.changed());
        assertEquals(SubCodec.toSrt(srt(ok)), r.srt);
        assertEquals("He went -> there at 10:30", r.document.cues.get(0).text);
        assertEquals("Call me at 10:30:15 -> ok?", r.document.cues.get(1).text);
        assertTrue(r.summary().contains("سليم"));
    }

    @Test
    public void repairFromBytesAlsoFixesTheEncoding() {
        String damaged = new String(ARABIC_FILE.getBytes(UTF8), W1256).replace("-->", "→");
        SubRepair.Report r = SubRepair.repair(damaged.getBytes(UTF8));
        assertEquals(4, r.cuesAfter);
        assertEquals("السلام عليكم يا صديقي", r.document.cues.get(0).text);
        assertEquals(4, r.timingLinesRewritten);
    }

    // ================================================================ Overlap Detector

    @Test
    public void overlapsAreFoundAlsoWhenALongCueCoversSeveralLaterOnesAndInUnsortedFiles() {
        SubDocument d = srt(
                cue(1, "00:00:20,000", "00:00:22,000", "C")
                        + cue(2, "00:00:01,000", "00:00:10,000", "A covers B and B2")
                        + cue(3, "00:00:03,000", "00:00:04,000", "B")
                        + cue(4, "00:00:05,000", "00:00:06,000", "B2")
                        + cue(5, "00:00:21,950", "00:00:23,000", "D barely touches C"));
        List<SubRepair.Overlap> all = SubRepair.overlaps(d, 0);
        assertEquals(3, all.size());
        assertEquals(2, all.get(0).firstIndex);
        assertEquals(3, all.get(0).secondIndex);
        assertEquals(1000, all.get(0).overlapMs);
        assertEquals(4, all.get(1).secondIndex);
        assertEquals(50, all.get(2).overlapMs);
        assertEquals(2, SubRepair.overlaps(d, 100).size());   // the 50 ms touch is under the tolerance
        assertEquals(2050, SubRepair.overlappedMs(all));
    }

    @Test
    public void closingAnOverlapNeverHidesALineAndMergesRepeatedText() {
        SubDocument d = srt(
                cue(1, "00:00:01,000", "00:00:05,000", "سطر طويل")
                        + cue(2, "00:00:04,000", "00:00:06,000", "يبدأ قبل نهاية السابق")
                        + cue(3, "00:00:10,000", "00:00:12,000", "متحدثان معاً - الأول")
                        + cue(4, "00:00:10,200", "00:00:12,000", "متحدثان معاً - الثاني")
                        + cue(5, "00:00:20,000", "00:00:22,000", "نص مكرر")
                        + cue(6, "00:00:21,000", "00:00:24,000", "نص مكرر"));
        SubDocument fixed = SubRepair.fixOverlaps(d, 84, 500);
        assertEquals(5, fixed.size());
        assertEquals(4000 - 84, fixed.cues.get(0).endMs);
        // simultaneous dialogue: closing would leave 116 ms, so both lines stay as they were
        assertEquals(12000, fixed.cues.get(2).endMs);
        assertEquals(10200, fixed.cues.get(3).startMs);
        // repeated text: one cue from the first start to the last end
        assertEquals(20000, fixed.cues.get(4).startMs);
        assertEquals(24000, fixed.cues.get(4).endMs);
        assertEquals(1, SubRepair.overlaps(fixed, 0).size());
    }

    // ================================================================ Missing Lines Detector

    @Test
    public void emptyCuesNumberingHolesAndUntranslatedLinesAreListed() {
        String f = cue(1, "00:00:01,000", "00:00:03,000", "السلام عليكم يا صديقي")
                + cue(2, "00:00:04,000", "00:00:06,000", " ")
                + cue(4, "00:00:07,000", "00:00:09,000", "ماذا تفعل هنا في هذا الوقت؟")
                + cue(5, "00:00:10,000", "00:00:12,000", "I'm not translated at all")
                + cue(6, "00:00:13,000", "00:00:15,000", "<i>♪</i>")
                + cue(9, "00:00:16,000", "00:00:18,000", "هل أنت متأكد من ذلك؟ هذا ليس ما قلته لي");
        SubRepair.Missing m = SubRepair.missing(srt(f), null, 0);
        assertEquals(java.util.Arrays.asList(2), m.blankCues);
        assertEquals(java.util.Arrays.asList(3, 7, 8), m.missingNumbers);
        assertEquals(java.util.Arrays.asList(4), m.untranslated);   // source index of the English line
        assertFalse(m.clean());
        assertTrue(m.summary().contains("مقطع فارغ"));
        assertTrue(SubRepair.missing(srt(ARABIC_FILE), null, 0).clean());
    }

    @Test
    public void aLongSilentHoleAndATruncatedTailAreReportedButNormalPausesAreNot() {
        StringBuilder b = new StringBuilder();
        int n = 0;
        // a line every 6 seconds for 30 minutes, nothing between minute 30 and 45, then lines again until minute 60
        for (long t = 0; t < 30 * 60000L; t += 6000) b.append(cue(++n, SubCodec.stamp(t, ','), SubCodec.stamp(t + 2500, ','), "سطر " + n));
        for (long t = 45 * 60000L; t < 60 * 60000L; t += 6000) b.append(cue(++n, SubCodec.stamp(t, ','), SubCodec.stamp(t + 2500, ','), "سطر " + n));
        SubDocument d = srt(b.toString());
        SubRepair.Missing m = SubRepair.missing(d, null, 62 * 60000L);
        assertEquals(1, m.silentGaps.size());
        assertTrue(m.silentGaps.get(0).lengthMs() > 14 * 60000L);
        assertFalse("two minutes of credits is not a truncated file", m.truncatedTail);
        // the same file on a 2-hour film stops half way: truncated
        SubRepair.Missing half = SubRepair.missing(d, null, 120 * 60000L);
        assertTrue(half.truncatedTail);
        // regular pauses only: nothing to report
        StringBuilder reg = new StringBuilder();
        n = 0;
        for (long t = 0; t < 40 * 60000L; t += 7000) reg.append(cue(++n, SubCodec.stamp(t, ','), SubCodec.stamp(t + 3000, ','), "سطر " + n));
        assertTrue(SubRepair.missing(srt(reg.toString()), null, 43 * 60000L).silentGaps.isEmpty());
    }

    @Test
    public void linesOfTheSourceThatHaveNoTranslationAreFoundByTime() {
        SubDocument source = srt(cue(1, "00:00:01,000", "00:00:03,000", "Hello")
                + cue(2, "00:00:04,000", "00:00:06,000", "How are you?")
                + cue(3, "00:00:07,000", "00:00:09,000", "This one was skipped")
                + cue(4, "00:00:10,000", "00:00:12,000", "Bye"));
        SubDocument ar = srt(cue(1, "00:00:01,000", "00:00:03,000", "مرحباً")
                + cue(2, "00:00:04,100", "00:00:06,000", "كيف حالك؟")
                + cue(3, "00:00:10,000", "00:00:12,000", "وداعاً"));
        SubRepair.Missing m = SubRepair.missing(ar, source, 0);
        assertEquals(java.util.Arrays.asList(3), m.missingAgainstReference);
        assertEquals("This one was skipped", m.missingReferenceTexts.get(0));
    }

    // ================================================================ Subtitle Cleaner

    @Test
    public void advertsAndSiteStampsAreDroppedButDialogueThatLooksSimilarStays() {
        String f = cue(1, "00:00:01,000", "00:00:03,000", "Subtitles by SomeGroup")
                + cue(2, "00:00:04,000", "00:00:06,000", "www.opensubtitles.org")
                + cue(3, "00:00:07,000", "00:00:09,000", "Downloaded from YTS.MX")
                + cue(4, "00:00:10,000", "00:00:12,000", "I was corrected by my teacher.")
                + cue(5, "00:00:13,000", "00:00:15,000", "No.To the left, I said!")
                + cue(6, "00:00:16,000", "00:00:18,000", "@CigramSubs")
                + cue(7, "00:00:19,000", "00:00:21,000", "Advertise your product or brand here")
                + cue(8, "00:00:22,000", "00:00:24,000", "تابعونا على قناتنا للمزيد")
                + cue(9, "00:00:25,000", "00:00:27,000", "Send it to bob@example and tell him I said hi, will you?")
                + cue(10, "00:00:28,000", "00:00:30,000", "Synced and corrected by nobody");
        SubCleaner.Report r = SubCleaner.clean(srt(f));
        List<String> left = new ArrayList<String>();
        for (SubCue c : r.document.cues) left.add(c.text);
        assertEquals(java.util.Arrays.asList("I was corrected by my teacher.", "No.To the left, I said!",
                "Send it to bob@example and tell him I said hi, will you?"), left);
        assertEquals(7, r.advertsDropped);
        assertEquals(7, r.removed.size());
        assertTrue(r.removed.get(0).startsWith("#1 "));
    }

    @Test
    public void repeatedCuesAndRepeatedLinesAreFoldedAndTheEndTimeIsKept() {
        String f = cue(1, "00:00:01,000", "00:00:03,000", "نفس السطر")
                + cue(2, "00:00:03,000", "00:00:05,000", "نفس السطر")
                + cue(3, "00:00:05,100", "00:00:07,000", "<i>نفس السطر</i>")
                + cue(4, "00:00:20,000", "00:00:22,000", "نفس السطر")
                + cue(5, "00:00:30,000", "00:00:32,000", "- مرحباً\n- مرحباً\nكيف حالك؟");
        SubCleaner.Report r = SubCleaner.clean(srt(f));
        assertEquals(3, r.cuesAfter);
        assertEquals(2, r.repeatedCuesMerged);
        assertEquals(1000, r.document.cues.get(0).startMs);
        assertEquals(7000, r.document.cues.get(0).endMs);
        assertEquals("said again 13 seconds later: a real repetition, kept", 20000, r.document.cues.get(1).startMs);
        assertEquals("- مرحباً\nكيف حالك؟", r.document.cues.get(2).text);
        assertEquals(1, r.repeatedLinesDropped);
    }

    @Test
    public void invisibleJunkAndRepeatedPunctuationGoButUsefulMarksAndItalicsStay() {
        String zwnj = "می‌خواهم";   // Persian: the joiner between two letters is part of the word
        String f = cue(1, "00:00:01,000", "00:00:03,000", "﻿مرحباً​ يا­ صديقي!!!!!!")
                + cue(2, "00:00:04,000", "00:00:06,000", "<font color=\"#ffff00\"><i>ماذا؟؟؟؟؟</i></font>   انتظر......")
                + cue(3, "00:00:07,000", "00:00:09,000", zwnj + " ‏abc")
                + cue(4, "00:00:10,000", "00:00:12,000", "‫نص‬  مع   فراغات   ");
        SubCleaner.Report r = SubCleaner.clean(srt(f));
        List<SubCue> c = r.document.cues;
        assertEquals("مرحباً يا صديقي!!", c.get(0).text);
        assertEquals("<i>ماذا؟؟</i> انتظر…", c.get(1).text);
        assertEquals(zwnj + " ‏abc", c.get(2).text);
        assertEquals("نص مع فراغات", c.get(3).text);
        assertTrue(r.invisibleRemoved >= 5);
        assertTrue(r.punctuationSqueezed >= 2);
        assertEquals(1, r.tagsStripped);
        // times never move in the cleaner
        assertEquals(4000, c.get(1).startMs);
        assertEquals(6000, c.get(1).endMs);
    }

    @Test
    public void musicAndHearingImpairedTextIsOnlyRemovedInDeepMode() {
        String f = cue(1, "00:00:01,000", "00:00:03,000", "♪ la la la ♪")
                + cue(2, "00:00:04,000", "00:00:06,000", "[door slams]\nJOHN: Who is there?")
                + cue(3, "00:00:07,000", "00:00:09,000", "(laughs)")
                + cue(4, "00:00:10,000", "00:00:12,000", "{\\an8}Top line");
        SubCleaner.Report normal = SubCleaner.clean(srt(f));
        assertEquals(4, normal.cuesAfter);
        assertEquals("[door slams]\nJOHN: Who is there?", normal.document.cues.get(1).text);
        SubCleaner.Report deep = SubCleaner.clean(srt(f), SubCleaner.Options.deep());
        assertEquals(3, deep.cuesAfter);
        assertEquals("la la la", deep.document.cues.get(0).text);
        assertEquals("Who is there?", deep.document.cues.get(1).text);
        assertEquals("Top line", deep.document.cues.get(2).text);
        assertEquals(1, deep.emptiedDropped);
    }

    @Test
    public void aCleanFileStaysByteIdentical() {
        SubDocument d = srt(ARABIC_FILE);
        SubCleaner.Report r = SubCleaner.clean(d);
        assertFalse(r.changed());
        assertEquals(SubCodec.toSrt(d), SubCodec.toSrt(r.document));
    }

    // ================================================================ Subtitle Delay / Drift Fix / FPS Converter

    @Test
    public void delayMovesTheWholeFileAndDriftFixInterpolatesBetweenTwoHandSyncedLines() {
        SubDocument d = srt(cue(1, "00:01:00,000", "00:01:02,000", "أول") + cue(2, "00:31:00,000", "00:31:02,000", "وسط") + cue(3, "01:01:00,000", "01:01:02,000", "آخر"));
        assertEquals(61500, SubTools.delay(d, 1500).document.cues.get(0).startMs);
        // the first line is really spoken at 1:02.0, the last one at 1:03:38.0
        SubTools.Drift f = SubTools.driftFix(d, 60000, 62000, 3660000, 3818000);
        assertEquals(62000, f.result.document.cues.get(0).startMs);
        assertEquals(3818000, f.result.document.cues.get(2).startMs);
        long mid = f.result.document.cues.get(1).startMs;
        assertEquals((62000 + 3818000) / 2, mid);
        assertTrue(f.factor > 1.04 && f.factor < 1.05);
        try {
            SubTools.driftFix(d, 60000, 62000, 70000, 73000);
            fail("two points ten seconds apart cannot measure a drift");
        } catch (IllegalArgumentException expected) { }
        try {
            SubTools.driftFix(d, 60000, 62000, 3660000, 9000000);
            fail("an impossible factor must be refused");
        } catch (IllegalArgumentException expected) { }
    }

    @Test
    public void fpsConversionUsesTheExactRatesAndRoundTrips() {
        SubDocument d = srt(cue(1, "01:00:00,000", "01:00:02,000", "ساعة"));
        SubDocument pal = SubTools.convertFps(d, 25, 23.976).document;
        assertEquals(3753750, pal.cues.get(0).startMs);     // 3 600 000 × 25 × 1001 / 24000
        SubDocument back = SubTools.convertFps(pal, 23.976, 25).document;
        assertEquals(3600000, back.cues.get(0).startMs);
        assertEquals(3600000, SubTools.convertFps(d, 25, 25).document.cues.get(0).startMs);
        assertEquals("25 → 23.976", SubTools.fpsLabelForFactor(25.0 * 1001 / 24000));
        assertEquals("", SubTools.fpsLabelForFactor(1.0));
        assertEquals("23.976", SubTools.fpsName(23.976));
        // the last line of the file is at 1:00:00 but in the film it is spoken at 1:02:33.7 → 25 → 23.976
        double[] g = SubTools.guessFps(d, 3753700);
        assertNotNull(g);
        assertEquals(25.0, g[0], 0.001);
        assertEquals(23.976, g[1], 0.001);
        assertNull("already matching: no conversion", SubTools.guessFps(d, 3600200));
    }

    // ================================================================ Subtitle Preview

    @Test
    public void previewShowsWhatIsOnScreenTheNextLineAndTheTimeUntilTheNextChange() {
        SubDocument d = srt(cue(1, "00:00:01,000", "00:00:03,000", "<i>الأول</i>") + cue(2, "00:00:05,000", "00:00:07,000", "الثاني") + cue(3, "00:00:06,000", "00:00:08,000", "الثالث"));
        SubSync.Timeline tl = new SubSync.Timeline(d);
        SubTools.Preview a = SubTools.preview(tl, 1500, 0);
        assertEquals("الأول", a.text);
        assertEquals(1500, a.untilChangeMs);
        SubTools.Preview gap = SubTools.preview(tl, 4000, 0);
        assertEquals("", gap.text);
        assertNull(gap.current);
        assertEquals("الثاني", gap.next.text);
        assertEquals(1000, gap.untilChangeMs);
        SubTools.Preview two = SubTools.preview(tl, 6500, 0);
        assertEquals("الثاني\nالثالث", two.text);
        assertEquals(2, two.activeCount);
        // the viewer's delay (+2 s = later) moves what is seen, not the file
        assertEquals("الأول", SubTools.preview(tl, 3500, 2000).text);
        assertEquals("", SubTools.preview(tl, 1500, 2000).text);
        assertEquals(-1, SubTools.preview(tl, 60000, 0).untilChangeMs);
        List<SubTools.StripRow> rows = SubTools.strip(d, 0, 10000, 500, 10);
        assertEquals(3, rows.size());
        assertEquals(1500, rows.get(0).showMs);
        assertFalse(rows.get(1).overlapsPrevious);
        assertTrue(rows.get(2).overlapsPrevious);
        assertTrue(rows.get(0).cps > 0);
        assertEquals(1, SubTools.strip(d, 5200, 5900, 0, 10).size());
    }

    // ================================================================ Auto Sync — reference subtitle

    /** A two-hour film: a line every 2.5-9 s. Returns the start/end pairs. */
    private static long[][] schedule(long seed, long lengthMs) {
        Random rnd = new Random(seed);
        List<long[]> out = new ArrayList<long[]>();
        long t = 20000;
        while (t < lengthMs) {
            long dur = 1200 + rnd.nextInt(3200);
            out.add(new long[]{t, t + dur});
            t += dur + 300 + rnd.nextInt(5500);
            if (rnd.nextInt(25) == 0) t += 20000 + rnd.nextInt(60000);   // a scene without dialogue
        }
        return out.toArray(new long[0][]);
    }

    private static SubDocument doc(long[][] times, String prefix) {
        List<SubCue> cues = new ArrayList<SubCue>();
        for (int k = 0; k < times.length; k++) cues.add(new SubCue(k + 1, String.valueOf(k + 1), times[k][0], times[k][1], prefix + " " + (k + 1), ""));
        return new SubDocument(SubDocument.Format.SRT, cues, "", "UTF-8", "", null, 0, 0, 0);
    }

    @Test
    public void aShiftedTranslationIsAlignedToTheReferenceWithinAFewMilliseconds() {
        long[][] ref = schedule(7, 2 * 3600000L);
        long[][] ar = new long[ref.length][];
        Random rnd = new Random(3);
        for (int k = 0; k < ref.length; k++) ar[k] = new long[]{ref[k][0] - 2300 + rnd.nextInt(81) - 40, ref[k][1] - 2300};
        SubAutoSync.Alignment a = SubAutoSync.alignToReference(doc(ar, "عربي"), doc(ref, "english"));
        assertTrue(a.describe(), a.reliable());
        assertFalse(a.hasDrift());
        assertEquals(2300, a.offsetMs, 30);
        assertEquals("reference", a.method);
        SubDocument fixed = a.apply(doc(ar, "عربي")).document;
        assertEquals(ref[100][0], fixed.cues.get(100).startMs, 80);
    }

    @Test
    public void aTranslationMadeForA25FpsReleaseIsRecognisedAndStretchedExactly() {
        long[][] ref = schedule(11, 2 * 3600000L);
        double f = 25.0 * 1001 / 24000;
        long[][] ar = new long[ref.length][];
        for (int k = 0; k < ref.length; k++) ar[k] = new long[]{Math.round((ref[k][0] - 1500) / f), Math.round((ref[k][1] - 1500) / f)};
        SubAutoSync.Alignment a = SubAutoSync.alignToReference(doc(ar, "عربي"), doc(ref, "english"));
        assertTrue(a.describe(), a.reliable());
        assertTrue(a.hasDrift());
        assertEquals(f, a.factor, 1e-9);
        assertEquals("25 → 23.976", a.fpsLabel());
        assertEquals(1500, a.offsetMs, 40);
        assertEquals(Math.round((f - 1) * 3600000), a.driftPerHourMs());
        SubDocument fixed = a.apply(doc(ar, "عربي")).document;
        int last = ref.length - 1;
        assertEquals(ref[last][0], fixed.cues.get(last).startMs, 60);
        assertEquals(ref[0][0], fixed.cues.get(0).startMs, 60);
        assertNotNull(SubTools.detectDrift(doc(ar, "عربي"), doc(ref, "english")));
    }

    @Test
    public void alignmentSurvivesMissingExtraAndSplitLines() {
        long[][] ref = schedule(21, 100 * 60000L);
        Random rnd = new Random(5);
        List<long[]> ar = new ArrayList<long[]>();
        for (long[] c : ref) {
            if (rnd.nextInt(10) < 3) continue;                         // 30% of the lines were not translated
            ar.add(new long[]{c[0] + 4100 + rnd.nextInt(121) - 60, c[1] + 4100});
            if (rnd.nextInt(12) == 0) ar.add(new long[]{c[0] + 4100 + 900, c[1] + 4100 + 600});   // a split line
        }
        SubAutoSync.Alignment a = SubAutoSync.alignToReference(doc(ar.toArray(new long[0][]), "عربي"), doc(ref, "english"));
        assertTrue(a.describe(), a.reliable());
        assertEquals(-4100, a.offsetMs, 50);
        assertFalse(a.hasDrift());
    }

    @Test
    public void anAlreadySyncedFileIsReportedAsSuchAndUnrelatedFilesAreRefused() {
        long[][] ref = schedule(31, 90 * 60000L);
        SubAutoSync.Alignment same = SubAutoSync.alignToReference(doc(ref, "عربي"), doc(ref, "english"));
        assertTrue(same.alreadyInSync());
        assertEquals(0, same.offsetMs, 20);
        assertTrue(same.describe().contains("متزامنة"));
        SubAutoSync.Alignment other = SubAutoSync.alignToReference(doc(schedule(99, 90 * 60000L), "فيلم آخر"), doc(ref, "english"));
        assertFalse(other.describe(), other.reliable());
        assertNull(SubTools.detectDrift(doc(schedule(99, 90 * 60000L), "فيلم آخر"), doc(ref, "english")));
        SubAutoSync.Alignment tiny = SubAutoSync.alignToReference(srt(ARABIC_FILE), doc(ref, "english"));
        assertFalse(tiny.reliable());
        assertTrue(tiny.note.length() > 0);
    }

    // ================================================================ Auto Sync — the film's own sound

    /**
     * Synthetic film sound, 16-bit stereo: speech = a 700 Hz + 1.4 kHz tone with a 5 Hz syllable rhythm; between the
     * lines a quiet hiss, and in a third of the pauses low-frequency "music" (120 Hz) that is LOUDER than the speech
     * but outside the speech band.
     */
    private static void feed(SubAutoSync.ActivityBuilder ab, long[][] speech, long fromMs, long toMs, int rate, long seed) {
        Random rnd = new Random(seed);
        ab.configure(rate, 2, false);
        int chunkFrames = rate / 10;
        ByteBuffer buf = ByteBuffer.allocate(chunkFrames * 4).order(ByteOrder.LITTLE_ENDIAN);
        int si = 0;
        boolean music = false;
        long musicUntil = 0;
        for (long t = fromMs; t < toMs; t += 100) {
            buf.clear();
            for (int i = 0; i < chunkFrames; i++) {
                double ms = t + i * 1000.0 / rate;
                while (si < speech.length && speech[si][1] <= ms) {
                    si++;
                    music = rnd.nextInt(3) == 0;
                    musicUntil = si < speech.length ? speech[si][0] : toMs;
                }
                boolean talking = si < speech.length && ms >= speech[si][0];
                double sec = ms / 1000.0;
                double v = (rnd.nextDouble() - 0.5) * 0.004;
                if (talking) {
                    double syll = 0.55 + 0.45 * Math.sin(2 * Math.PI * 5 * sec);
                    v += syll * (0.20 * Math.sin(2 * Math.PI * 700 * sec) + 0.12 * Math.sin(2 * Math.PI * 1400 * sec));
                } else if (music && ms < musicUntil) {
                    v += 0.45 * Math.sin(2 * Math.PI * 120 * sec);
                }
                short s = (short) Math.max(-32768, Math.min(32767, Math.round(v * 32767)));
                buf.putShort(s);
                buf.putShort(s);
            }
            buf.flip();
            ab.write(t * 1000L, buf);
        }
    }

    @Test
    public void theDelayIsFoundFromTheFilmSoundAloneWithoutAnyReferenceFile() {
        long[][] speech = schedule(41, 6 * 60000L);
        SubAutoSync.ActivityBuilder ab = new SubAutoSync.ActivityBuilder();
        feed(ab, speech, 0, 6 * 60000L, 16000, 1);
        assertTrue(ab.coveredMs() > 5 * 60000L);
        // the subtitle shows every line 1.7 s too early → it needs a delay of +1.7 s
        long[][] early = new long[speech.length][];
        for (int k = 0; k < speech.length; k++) early[k] = new long[]{speech[k][0] - 1700, speech[k][1] - 1700};
        SubAutoSync.Alignment a = SubAutoSync.alignToActivity(doc(early, "عربي"), ab.snapshot());
        assertTrue(a.describe(), a.reliable());
        assertEquals(1700, a.offsetMs, 90);
        assertEquals("sound", a.method);
        // and too late by 3.2 s → −3.2 s
        long[][] late = new long[speech.length][];
        for (int k = 0; k < speech.length; k++) late[k] = new long[]{speech[k][0] + 3200, speech[k][1] + 3200};
        SubAutoSync.Alignment b = SubAutoSync.alignToActivity(doc(late, "عربي"), ab.snapshot());
        assertTrue(b.describe(), b.reliable());
        assertEquals(-3200, b.offsetMs, 90);
        // a file that is in sync is recognised as in sync
        SubAutoSync.Alignment c = SubAutoSync.alignToActivity(doc(speech, "عربي"), ab.snapshot());
        assertTrue(c.describe(), c.reliable());
        assertEquals(0, c.offsetMs, 90);
    }

    @Test
    public void soundIsKeyedByMediaTimeSoSeekingAndPartialListeningWork() {
        long[][] speech = schedule(43, 40 * 60000L);
        SubAutoSync.ActivityBuilder ab = new SubAutoSync.ActivityBuilder();
        // the viewer watched minutes 20-24, jumped back to minutes 3-6 (44.1 kHz then 48 kHz: a quality switch)
        feed(ab, speech, 20 * 60000L, 24 * 60000L, 44100, 2);
        feed(ab, speech, 3 * 60000L, 6 * 60000L, 48000, 3);
        assertEquals(7 * 60000L, ab.coveredMs(), 2000);
        long[][] early = new long[speech.length][];
        for (int k = 0; k < speech.length; k++) early[k] = new long[]{speech[k][0] - 900, speech[k][1] - 900};
        SubAutoSync.Alignment a = SubAutoSync.alignToActivity(doc(early, "عربي"), ab.snapshot(20 * 60000L, 24 * 60000L));
        assertTrue(a.describe(), a.reliable());
        assertEquals(900, a.offsetMs, 90);
        SubAutoSync.Alignment whole = SubAutoSync.alignToActivity(doc(early, "عربي"), ab.snapshot());
        assertEquals(900, whole.offsetMs, 90);
        ab.clear();
        assertEquals(0, ab.coveredMs());
    }

    @Test
    public void tooLittleOrMeaninglessSoundIsNeverTurnedIntoACorrection() {
        long[][] speech = schedule(45, 10 * 60000L);
        SubAutoSync.ActivityBuilder shortOne = new SubAutoSync.ActivityBuilder();
        feed(shortOne, speech, 0, 25000, 16000, 4);
        SubAutoSync.Alignment a = SubAutoSync.alignToActivity(doc(speech, "عربي"), shortOne.snapshot());
        assertFalse(a.reliable());
        assertTrue(a.note.length() > 0);
        // five minutes of sound that has nothing to do with this subtitle
        SubAutoSync.ActivityBuilder other = new SubAutoSync.ActivityBuilder();
        feed(other, schedule(777, 5 * 60000L), 0, 5 * 60000L, 16000, 5);
        SubAutoSync.Alignment b = SubAutoSync.alignToActivity(doc(speech, "عربي"), other.snapshot());
        assertFalse(b.describe(), b.reliable());
        assertFalse(SubAutoSync.alignToActivity(doc(speech, "عربي"), null).reliable());
    }

    @Test
    public void severalWindowsOfTheFilmRevealAFrameRateDrift() {
        // a 25 fps subtitle on a 23.976 film: the delay needed grows through the film
        double f = 25.0 * 1001 / 24000;
        List<SubAutoSync.WindowResult> w = new ArrayList<SubAutoSync.WindowResult>();
        long[] centres = {5 * 60000L, 50 * 60000L, 95 * 60000L};
        for (long c : centres) {
            long subtitleTime = Math.round((c - 800) / f);                 // film = subtitle × f + 800
            w.add(new SubAutoSync.WindowResult(c, new SubAutoSync.Alignment(1.0, c - subtitleTime, 4000, 900, 0.9, 25, "sound", "")));
        }
        SubAutoSync.Alignment a = SubAutoSync.fitWindows(w, 900);
        assertTrue(a.describe(), a.reliable());
        assertEquals(f, a.factor, 1e-9);
        assertEquals(800, a.offsetMs, 30);
        assertEquals("25 → 23.976", a.fpsLabel());
        // one window: an offset, no factor
        SubAutoSync.Alignment one = SubAutoSync.fitWindows(w.subList(0, 1), 900);
        assertFalse(one.hasDrift());
        assertEquals(w.get(0).alignment.offsetMs, one.offsetMs);
        // unreliable windows are ignored
        List<SubAutoSync.WindowResult> weak = new ArrayList<SubAutoSync.WindowResult>();
        weak.add(new SubAutoSync.WindowResult(60000, new SubAutoSync.Alignment(1.0, 500, 100, 900, 0.2, 25, "sound", "")));
        assertFalse(SubAutoSync.fitWindows(weak, 900).reliable());
    }

    // ================================================================ doctor (Auto Repair for a subtitle file)

    @Test
    public void theDoctorTurnsABadlyDamagedFileIntoAStandardArabicSrtAndSaysWhatItDid() {
        String body = "1\n00:00:05,000 => 00:00:07,000\nwww.example-subs.com\n\n"
                + "3\n00:00:20,000 --> 00:00:18,000\nماذا تفعل هنا في هذا الوقت؟\n\n"
                + "2\n0O:00:10.000 -> 00:00:12.5\nالسلام عليكم يا صديقي!!!!!!\n\n"
                + "4\n00:00:30,000 --> 00:00:33,000\nلا شيء، كنت أنتظر أن تأتي إلى هنا\n\n"
                + "5\n00:00:33,000 --> 00:00:35,000\nلا شيء، كنت أنتظر أن تأتي إلى هنا\n\n"
                + "6\n00:00:40,000 --> 00:00:42,000\n\n\n"
                + "7\n00:00:50,000 --> 00:00:52,000\nهل أنت متأكد من ذلك؟ هذا ليس ما قلته لي\n";
        byte[] raw = body.getBytes(W1256);     // saved by an old Windows tool
        SubTools.DoctorReport r = SubTools.doctor(raw, null, 0);
        assertTrue(r.changed());
        assertEquals("windows-1256", r.encoding.encoding);
        List<SubCue> c = r.document.cues;
        assertEquals(r.steps.toString(), 4, c.size());
        assertEquals("السلام عليكم يا صديقي!!", c.get(0).text);
        assertEquals(10000, c.get(0).startMs);
        assertEquals(18000, c.get(1).startMs);
        assertEquals(20000, c.get(1).endMs);
        assertEquals(30000, c.get(2).startMs);
        assertEquals(35000, c.get(2).endMs);
        for (int k = 0; k < c.size(); k++) assertEquals(k + 1, c.get(k).index);
        assertEquals(5, r.steps.size());
        assertTrue(r.headline().contains("أُصلح"));
        SubDocument again = SubCodec.read(r.srt.getBytes(UTF8));
        assertEquals(4, again.size());
        assertTrue(again.warnings.isEmpty());
        assertEquals(0, r.qa.count("INVALID_TIMING"));
        assertEquals(0, r.qa.count("ENCODING_DAMAGE"));
        // a healthy file goes through untouched
        SubTools.DoctorReport ok = SubTools.doctor(ARABIC_FILE.getBytes(UTF8), null, 0);
        assertFalse(ok.changed());
        assertEquals(SubCodec.toSrt(srt(ARABIC_FILE)), ok.srt);
        assertTrue(ok.headline().contains("سليم"));
    }
}
