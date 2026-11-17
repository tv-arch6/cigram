package com.my.newproject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Executor;

/** Cigram Diagnostics + the update package check (JVM, no device): privacy, grouping, bounds, retention, decisions. */
public class CgDiagTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final Executor NOW = new Executor() { @Override public void execute(Runnable r) { r.run(); } };

    private static final class Clock implements CgDiag.Clock {
        long t = 1_700_000_000_000L;
        @Override public long now() { return t; }
    }

    private CgDiag.Store store(File f, Clock c) {
        return new CgDiag.Store(f, NOW, c, "19.3.0", 34);
    }

    // ------------------------------------------------------------ privacy

    @Test
    public void secretsNeverReachTheBufferTheFileOrTheExport() throws Exception {
        File f = new File(tmp.getRoot(), "diag.jsonl");
        Clock c = new Clock();
        CgDiag.Store s = store(f, c);
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        s.record(CgDiag.Severity.ERROR, "net", "login", "AUTH",
                "POST https://cigram-auth-api.example.workers.dev/login?token=abc123SECRET&x=1 failed, Authorization: Bearer " + jwt
                        + " user someone@example.com {\"password\":\"hunter2hunter2\",\"token\":\"zzzzTOKENzzzz\"} sig=deadbeef",
                "cid1", 0, null);
        String export = s.export();
        String disk = new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
        for (String secret : new String[]{"abc123SECRET", jwt, "someone@example", "hunter2hunter2", "zzzzTOKENzzzz", "deadbeef"}) {
            assertFalse("export leaks " + secret, export.contains(secret));
            assertFalse("file leaks " + secret, disk.contains(secret));
            for (CgDiag.Event e : s.snapshot()) assertFalse("buffer leaks " + secret, e.message.contains(secret));
        }
        assertTrue("the useful part is kept", export.contains("login"));
        assertTrue(export.contains("workers.dev/login"));
    }

    @Test
    public void redactionIsIdempotentBoundedAndNeverNull() {
        assertEquals("", CgDiag.redact(null));
        String once = CgDiag.redact("a token=SECRETVALUE b");
        assertEquals(once, CgDiag.redact(once));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append("x ");
        assertTrue(CgDiag.redact(big.toString()).length() <= CgDiag.Store.MAX_MESSAGE);
        assertFalse("a log line stays one line", CgDiag.redact("a\nb\r\nc").contains("\n"));
    }

    @Test
    public void aStackTraceKeepsItsLinesButLosesItsSecrets() {
        String trace = "java.io.IOException: GET https://x.example/a?token=SECRET123 failed\nat com.my.newproject.A.b(A.java:10)\nat com.my.newproject.C.d(C.java:20)";
        String clean = CgDiag.redactLines(trace);
        assertEquals(3, clean.split("\n").length);
        assertFalse(clean.contains("SECRET123"));
        assertTrue(clean.contains("A.java:10"));
    }

    // ------------------------------------------------------------ grouping + bounds

    @Test
    public void theSameFailureRepeatedIsOneRowWithACount() {
        CgDiag.Store s = store(null, new Clock());
        for (int i = 0; i < 500; i++) s.record(CgDiag.Severity.WARN, "player", "load", "HTTP", "HTTP " + (500 + i % 4) + " at 12:0" + (i % 9), "c", 0, null);
        s.record(CgDiag.Severity.WARN, "player", "load", "DNS", "no host", "c", 0, null);
        List<CgDiag.Event> rows = s.snapshot();
        assertEquals("one row per distinct failure", 2, rows.size());
        int total = 0;
        for (CgDiag.Event e : rows) total += e.count;
        assertEquals(501, total);
        assertEquals(501L, s.totalRecorded());
    }

    @Test
    public void memoryStaysBoundedWhateverTheVariety() {
        CgDiag.Store s = store(null, new Clock());
        for (int i = 0; i < 2000; i++) s.record(CgDiag.Severity.INFO, "c" + i, "op", "OTHER", "m", "c", 0, null);
        assertEquals(CgDiag.Store.MAX_GROUPS, s.snapshot().size());
    }

    @Test
    public void aRetryStormWritesTheDiskOncePerThirtySecondsNotOncePerRetry() throws Exception {
        File f = new File(tmp.getRoot(), "storm.jsonl");
        Clock c = new Clock();
        CgDiag.Store s = store(f, c);
        for (int i = 0; i < 1000; i++) { c.t += 10; s.record(CgDiag.Severity.ERROR, "net", "poll", "TIMEOUT", "timeout", "c", 0, null); }   // 10 s in total
        assertEquals(1, Files.readAllLines(f.toPath(), Charset.forName("UTF-8")).size());
        c.t += CgDiag.Store.WRITE_EVERY_MS + 1;
        s.record(CgDiag.Severity.ERROR, "net", "poll", "TIMEOUT", "timeout", "c", 0, null);
        assertEquals(2, Files.readAllLines(f.toPath(), Charset.forName("UTF-8")).size());
        assertEquals(1001, s.snapshot().get(0).count);
    }

    // ------------------------------------------------------------ retention + damage

    @Test
    public void oldHistoryIsDroppedAndDamagedLinesAreSkippedOnLoad() throws Exception {
        File f = new File(tmp.getRoot(), "old.jsonl");
        Clock c = new Clock();
        CgDiag.Store first = store(f, c);
        first.record(CgDiag.Severity.ERROR, "old", "x", "OTHER", "old failure", "c", 0, null);
        c.t += CgDiag.Store.MAX_AGE_MS / 2;
        first.record(CgDiag.Severity.ERROR, "mid", "x", "OTHER", "recent failure", "c", 0, null);
        FileOutputStream out = new FileOutputStream(f, true);
        out.write("{this is not json\n\n\u0000\u0000\n".getBytes("UTF-8"));
        out.close();
        c.t += CgDiag.Store.MAX_AGE_MS / 2 + 1000L;                 // "old" is now older than 14 days, "mid" is not
        CgDiag.Store reopened = store(f, c);
        List<CgDiag.Event> rows = reopened.snapshot();
        assertEquals(1, rows.size());
        assertEquals("mid", rows.get(0).component);
    }

    @Test
    public void theFileNeverGrowsPastItsLimit() throws Exception {
        File f = new File(tmp.getRoot(), "big.jsonl");
        Clock c = new Clock();
        CgDiag.Store s = store(f, c);
        for (int i = 0; i < 3000; i++) {
            c.t += CgDiag.Store.WRITE_EVERY_MS + 1;
            s.record(CgDiag.Severity.ERROR, "comp" + i, "op", "OTHER", "failure number " + i + " padding padding padding padding padding padding", "c", 0, null);
        }
        assertTrue("file " + f.length(), f.length() <= CgDiag.Store.MAX_BYTES + 2048);
    }

    // ------------------------------------------------------------ classification

    @Test
    public void exceptionsAreSortedByKindNotByMessage() {
        assertEquals("DNS", CgDiag.categoryOf(new java.net.UnknownHostException("x")));
        assertEquals("TIMEOUT", CgDiag.categoryOf(new java.net.SocketTimeoutException()));
        assertEquals("TLS", CgDiag.categoryOf(new javax.net.ssl.SSLException("x")));
        assertEquals("NETWORK", CgDiag.categoryOf(new java.net.ConnectException()));
        assertEquals("PARSE", CgDiag.categoryOf(new org.json.JSONException("x")));
        assertEquals("STORAGE", CgDiag.categoryOf(new java.io.IOException("write failed: ENOSPC (No space left on device)")));
        assertEquals("MEMORY", CgDiag.categoryOf(new OutOfMemoryError()));
        assertEquals("NETWORK", CgDiag.categoryOf(new RuntimeException("wrapper", new java.net.SocketException("reset"))));
        assertEquals("OTHER", CgDiag.categoryOf(new IllegalStateException()));
        assertEquals("AUTH", CgDiag.categoryOfHttp(401));
        assertEquals("AUTH", CgDiag.categoryOfHttp(403));
        assertEquals("RATE_LIMIT", CgDiag.categoryOfHttp(429));
        assertEquals("SERVER", CgDiag.categoryOfHttp(503));
        assertEquals("NOT_FOUND", CgDiag.categoryOfHttp(404));
    }

    @Test
    public void recordingNeverThrowsIntoTheCaller() {
        CgDiag.record(null, null, null, null, null, null, -5, null);
        CgDiag.warn(null, null, null, null);
        CgDiag.error("c", "o", null, new RuntimeException((String) null));
        CgDiag.recovery("c", "o", "X", null, null, 0, null);
        assertNotNull(CgDiag.export());
        assertNotNull(CgDiag.events());
        assertEquals(8, CgDiag.newCorrelationId().length());
    }
}
