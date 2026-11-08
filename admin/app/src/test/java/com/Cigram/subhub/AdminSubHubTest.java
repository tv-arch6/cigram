package com.Cigram.subhub;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Admin-only parts of CigramSubtitleHub: episode matching, safe ZIP extraction, the translation queue. */
public class AdminSubHubTest {

    // ---------------------------------------------------------------- episode numbers from file names

    @Test
    public void parsesCommonEpisodeNames() {
        assertEp("Show.Name.S01E02.720p.WEB.srt", 1, 2);
        assertEp("show_s1.e10.ar.vtt", 1, 10);
        assertEp("Show 2x05 HDTV.srt", 2, 5);
        assertEp("Show Season 3 Episode 7.srt", 3, 7);
        assertEp("المسلسل الموسم 2 الحلقة 4.srt", 2, 4);
        assertEp("المسلسل الحلقة ١٢.srt", 0, 12);
        assertEp("Show.E08.1080p.x265.srt", 0, 8);
        assertEp("05.srt", 0, 5);
        assertNull(EpisodeMatcher.parse("Show.Name.2019.1080p.BluRay.srt"));
        assertNull(EpisodeMatcher.parse("Arabic.srt"));
        assertTrue(EpisodeMatcher.parse("Show.S01E01E02.srt").multi);
        assertTrue(EpisodeMatcher.parse("Show.S01E01-E02.srt").multi);
    }

    private static void assertEp(String name, int s, int e) {
        EpisodeMatcher.Parsed p = EpisodeMatcher.parse(name);
        assertNotNull(name, p);
        assertEquals(name, s, p.season);
        assertEquals(name, e, p.episode);
    }

    @Test
    public void matchesOnlyByNumberAndFlagsEverythingElse() {
        List<EpisodeMatcher.Episode> eps = Arrays.asList(
                new EpisodeMatcher.Episode("a", 1, 1, "ح1", false),
                new EpisodeMatcher.Episode("b", 1, 2, "ح2", true),
                new EpisodeMatcher.Episode("c", 1, 3, "ح3", false),
                new EpisodeMatcher.Episode("x", 2, 1, "م2ح1", false));
        List<String> files = Arrays.asList("S01E01.srt", "S01E02.srt", "Show.S01E02.v2.srt", "S02E01.srt", "S01E09.srt", "readme-ar.srt", "S01E03E04.srt");
        List<EpisodeMatcher.Match> m = EpisodeMatcher.match(files, eps, 1);
        assertEquals(EpisodeMatcher.State.MATCHED, m.get(0).state);
        assertEquals("a", m.get(0).target.id);
        assertEquals(EpisodeMatcher.State.AMBIGUOUS, m.get(1).state);
        assertEquals(EpisodeMatcher.State.AMBIGUOUS, m.get(2).state);
        assertNull(m.get(1).target);
        assertEquals(EpisodeMatcher.State.WRONG_SEASON, m.get(3).state);
        assertEquals(EpisodeMatcher.State.NO_SUCH_EPISODE, m.get(4).state);
        assertEquals(EpisodeMatcher.State.UNMATCHED, m.get(5).state);
        assertEquals(EpisodeMatcher.State.MULTI_EPISODE, m.get(6).state);
        // manual correction, then a conflict is detected before saving
        EpisodeMatcher.assign(m.get(5), eps.get(0));
        assertTrue(m.get(5).manual);
        assertEquals(Arrays.asList("S01E01"), EpisodeMatcher.conflicts(m));
        EpisodeMatcher.assign(m.get(5), eps.get(2));
        assertTrue(EpisodeMatcher.conflicts(m).isEmpty());
    }

    // ---------------------------------------------------------------- safe zip

    private static byte[] zip(String[][] entries) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(b);
        for (String[] e : entries) {
            z.putNextEntry(new ZipEntry(e[0]));
            z.write(e[1].getBytes("UTF-8"));
            z.closeEntry();
        }
        z.close();
        return b.toByteArray();
    }

    @Test
    public void zipKeepsOnlySafeSubtitles() throws IOException {
        byte[] z = zip(new String[][]{
                {"Season 1/S01E01.srt", "1\n00:00:01,000 --> 00:00:02,000\nأ\n"},
                {"S01E02.vtt", "WEBVTT\n\n00:01.000 --> 00:02.000\nب\n"},
                {"../../evil.srt", "x"},
                {"__MACOSX/._S01E01.srt", "x"},
                {"notes.txt", "x"},
                {"inner.zip", "x"}});
        assertTrue(SafeZip.isZip(z));
        SafeZip.Result r = SafeZip.extract(new ByteArrayInputStream(z), z.length);
        assertEquals(2, r.files.size());
        assertEquals("S01E01.srt", r.files.get(0).name);
        assertEquals(4, r.skipped.size());
        SubDocument d = SubCodec.read(r.files.get(1).data);
        assertEquals(SubDocument.Format.VTT, d.format);
    }

    @Test
    public void zipBombIsRefused() throws IOException {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 9 * 1024 * 1024; i++) big.append('a');
        byte[] z = zip(new String[][]{{"S01E01.srt", big.toString()}});
        try {
            SafeZip.extract(new ByteArrayInputStream(z), z.length);
            fail("a 9 MB entry must be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().length() > 0);
        }
    }

    // ---------------------------------------------------------------- queue

    private static SubQueue.Job job(String id) {
        SubQueue.Job j = new SubQueue.Job();
        j.contentId = id;
        j.title = "عنوان " + id;
        return j;
    }

    @Test
    public void stateMachineRefusesShortcutsToPublication() {
        SubQueue q = new SubQueue();
        SubQueue.Job j = q.upsert(job("m1"), 1);
        try {
            q.move(j, SubQueue.State.PUBLISHED, "", 2);
            fail("NOT_TRANSLATED → PUBLISHED must be refused");
        } catch (IllegalStateException expected) { }
        try {
            q.approve(j, "", 2);
            fail("approval needs QUALITY_REVIEW");
        } catch (IllegalStateException expected) { }
        q.move(j, SubQueue.State.SEARCHING, "", 3);
        q.move(j, SubQueue.State.SOURCE_FOUND, "", 4);
        q.move(j, SubQueue.State.QUALITY_REVIEW, "", 5);
        j.quality = "FAIL";
        j.translatedFile = "m1.ar.srt";
        try {
            q.approve(j, "", 6);
            fail("a failing file cannot be approved");
        } catch (IllegalStateException expected) { }
        j.quality = "REVIEW";
        q.approve(j, "2026-11-08T10:00:00Z", 7);
        assertEquals(SubQueue.State.APPROVED, j.state);
        q.markPublished(j, "https://r2/x.ar.srt", "2026-11-08T10:01:00Z", "", 8);
        assertEquals(SubQueue.State.PUBLISHED, j.state);
        assertEquals(5, j.history.size());
    }

    @Test
    public void duplicatesArePrevented() {
        SubQueue q = new SubQueue();
        q.upsert(job("e1"), 1);
        SubQueue.Job again = job("e1");
        again.tmdbId = "1396";
        q.upsert(again, 2);
        assertEquals(1, q.size());
        assertEquals("1396", q.get("e1").tmdbId);
    }

    @Test
    public void batchPaginationRetriesAndResume() throws Exception {
        SubQueue q = new SubQueue();
        List<String> ids = new ArrayList<String>();
        for (int i = 0; i < 5; i++) { q.upsert(job("t" + i), 1); ids.add("t" + i); }
        long now = 1000;
        q.startBatch("b1", "search", ids, 2, now);
        List<SubQueue.Job> first = q.claim(2, now);
        assertEquals(2, first.size());
        assertEquals("t0", first.get(0).contentId);
        // the same titles are never handed out twice while in flight
        List<SubQueue.Job> second = q.claim(2, now);
        assertEquals("t2", second.get(0).contentId);
        // t0: found Arabic → review; t1: failure → backoff
        q.move(first.get(0), SubQueue.State.SEARCHING, "", now);
        q.move(first.get(0), SubQueue.State.QUALITY_REVIEW, "", now);
        q.release(first.get(0));
        q.move(first.get(1), SubQueue.State.SEARCHING, "", now);
        q.fail(first.get(1), "HTTP 502", now);
        q.release(first.get(1));
        assertTrue(first.get(1).nextAttemptAt > now);
        // process restart: everything comes back from the JSON checkpoint
        SubQueue r = SubQueue.fromJson(new JSONObject(q.toJson().toString()));
        assertEquals(5, r.size());
        assertEquals(SubQueue.State.FAILED, r.get("t1").state);
        // t2/t3 were in flight (SEARCHING never set) → still pending after the restart
        List<SubQueue.Job> resumed = r.claim(4, now);
        assertEquals(Arrays.asList("t2", "t3", "t4"), Arrays.asList(resumed.get(0).contentId, resumed.get(1).contentId, resumed.get(2).contentId));
        for (SubQueue.Job j : resumed) {
            r.move(j, SubQueue.State.SEARCHING, "", now);
            j.searchedAt = now;
            r.move(j, SubQueue.State.NOT_TRANSLATED, "no arabic", now);
            r.release(j);
        }
        assertFalse(r.batchFinished());                         // t1 still waits for its retry
        assertEquals(r.get("t1").nextAttemptAt, r.nextWakeAt());
        List<SubQueue.Job> retry = r.claim(4, r.get("t1").nextAttemptAt);
        assertEquals(1, retry.size());
        assertEquals("t1", retry.get(0).contentId);
        r.move(retry.get(0), SubQueue.State.SEARCHING, "", now);
        r.move(retry.get(0), SubQueue.State.QUALITY_REVIEW, "", now);
        r.release(retry.get(0));
        assertTrue(r.batchFinished());
    }

    @Test
    public void rateLimitPausesTheWholeBatch() {
        SubQueue q = new SubQueue();
        q.upsert(job("a"), 1);
        q.startBatch("b", "search", Arrays.asList("a"), 1, 10);
        q.rateLimited(10, 0);
        assertTrue(q.claim(1, 20).isEmpty());
        assertEquals(1, q.claim(1, 10 + 60_000).size());
    }

    @Test
    public void cancelledBatchHandsOutNothing() {
        SubQueue q = new SubQueue();
        q.upsert(job("a"), 1);
        q.startBatch("b", "search", Arrays.asList("a"), 1, 10);
        q.batch.cancelled = true;
        assertTrue(q.claim(1, 20).isEmpty());
        assertFalse(q.batchFinished());
    }

    @Test
    public void backoffIsExponentialAndCapped() {
        assertEquals(30_000L, SubQueue.backoffNoJitter(1));
        assertEquals(60_000L, SubQueue.backoffNoJitter(2));
        assertEquals(240_000L, SubQueue.backoffNoJitter(4));
        assertEquals(SubQueue.BACKOFF_MAX_MS, SubQueue.backoffNoJitter(30));
        SubQueue q = new SubQueue();
        for (int i = 1; i < 10; i++) {
            long b = q.backoff(i);
            assertTrue(b >= SubQueue.backoffNoJitter(i) * 0.8 - 1 || b == SubQueue.BACKOFF_MAX_MS);
            assertTrue(b <= SubQueue.BACKOFF_MAX_MS);
        }
    }

    @Test
    public void quotaPerProviderPerDay() {
        SubQueue q = new SubQueue();
        q.setQuota("opensubtitles", 2);
        assertTrue(q.quotaLeft("opensubtitles", "2026-11-08"));
        q.useQuota("opensubtitles", "2026-11-08");
        q.useQuota("opensubtitles", "2026-11-08");
        assertFalse(q.quotaLeft("opensubtitles", "2026-11-08"));
        assertTrue(q.quotaLeft("opensubtitles", "2026-11-09"));
        assertTrue(q.quotaLeft("subdl", "2026-11-08"));
    }

    @Test
    public void estimateUsesOnlyKnownSources() {
        SubQueue.Job a = job("a"); a.chars = 40_000;
        SubQueue.Job b = job("b"); b.chars = 10_000;
        SubQueue.Job c = job("c");
        SubQueue.Estimate e = SubQueue.estimate(Arrays.asList(a, b, c), 0.02);
        assertEquals(3, e.jobs);
        assertEquals(2, e.known);
        assertEquals(1, e.unknown);
        assertEquals(50_000, e.chars);
        assertEquals(1.0, e.cost, 1e-9);
        assertEquals(0.0, SubQueue.estimate(Arrays.asList(a), 0).cost, 1e-9);   // no price entered → no invented cost
    }
}
