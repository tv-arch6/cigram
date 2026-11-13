package com.Cigram.vid;

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
public class DiagnosticsTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final Executor NOW = new Executor() { @Override public void execute(Runnable r) { r.run(); } };

    private static final class Clock implements CigramDiag.Clock {
        long t = 1_700_000_000_000L;
        @Override public long now() { return t; }
    }

    private CigramDiag.Store store(File f, Clock c) {
        return new CigramDiag.Store(f, NOW, c, "19.3.0", 34);
    }

    // ------------------------------------------------------------ privacy

    @Test
    public void secretsNeverReachTheBufferTheFileOrTheExport() throws Exception {
        File f = new File(tmp.getRoot(), "diag.jsonl");
        Clock c = new Clock();
        CigramDiag.Store s = store(f, c);
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        s.record(CigramDiag.Severity.ERROR, "net", "login", "AUTH",
                "POST https://cigram-auth-api.example.workers.dev/login?token=abc123SECRET&x=1 failed, Authorization: Bearer " + jwt
                        + " user someone@example.com {\"password\":\"hunter2hunter2\",\"token\":\"zzzzTOKENzzzz\"} sig=deadbeef",
                "cid1", 0, null);
        String export = s.export();
        String disk = new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
        for (String secret : new String[]{"abc123SECRET", jwt, "someone@example", "hunter2hunter2", "zzzzTOKENzzzz", "deadbeef"}) {
            assertFalse("export leaks " + secret, export.contains(secret));
            assertFalse("file leaks " + secret, disk.contains(secret));
            for (CigramDiag.Event e : s.snapshot()) assertFalse("buffer leaks " + secret, e.message.contains(secret));
        }
        assertTrue("the useful part is kept", export.contains("login"));
        assertTrue(export.contains("workers.dev/login"));
    }

    @Test
    public void redactionIsIdempotentBoundedAndNeverNull() {
        assertEquals("", CigramDiag.redact(null));
        String once = CigramDiag.redact("a token=SECRETVALUE b");
        assertEquals(once, CigramDiag.redact(once));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 5000; i++) big.append("x ");
        assertTrue(CigramDiag.redact(big.toString()).length() <= CigramDiag.Store.MAX_MESSAGE);
        assertFalse("a log line stays one line", CigramDiag.redact("a\nb\r\nc").contains("\n"));
    }

    @Test
    public void aStackTraceKeepsItsLinesButLosesItsSecrets() {
        String trace = "java.io.IOException: GET https://x.example/a?token=SECRET123 failed\nat com.Cigram.vid.A.b(A.java:10)\nat com.Cigram.vid.C.d(C.java:20)";
        String clean = CigramDiag.redactLines(trace);
        assertEquals(3, clean.split("\n").length);
        assertFalse(clean.contains("SECRET123"));
        assertTrue(clean.contains("A.java:10"));
    }

    // ------------------------------------------------------------ grouping + bounds

    @Test
    public void theSameFailureRepeatedIsOneRowWithACount() {
        CigramDiag.Store s = store(null, new Clock());
        for (int i = 0; i < 500; i++) s.record(CigramDiag.Severity.WARN, "player", "load", "HTTP", "HTTP " + (500 + i % 4) + " at 12:0" + (i % 9), "c", 0, null);
        s.record(CigramDiag.Severity.WARN, "player", "load", "DNS", "no host", "c", 0, null);
        List<CigramDiag.Event> rows = s.snapshot();
        assertEquals("one row per distinct failure", 2, rows.size());
        int total = 0;
        for (CigramDiag.Event e : rows) total += e.count;
        assertEquals(501, total);
        assertEquals(501L, s.totalRecorded());
    }

    @Test
    public void memoryStaysBoundedWhateverTheVariety() {
        CigramDiag.Store s = store(null, new Clock());
        for (int i = 0; i < 2000; i++) s.record(CigramDiag.Severity.INFO, "c" + i, "op", "OTHER", "m", "c", 0, null);
        assertEquals(CigramDiag.Store.MAX_GROUPS, s.snapshot().size());
    }

    @Test
    public void aRetryStormWritesTheDiskOncePerThirtySecondsNotOncePerRetry() throws Exception {
        File f = new File(tmp.getRoot(), "storm.jsonl");
        Clock c = new Clock();
        CigramDiag.Store s = store(f, c);
        for (int i = 0; i < 1000; i++) { c.t += 10; s.record(CigramDiag.Severity.ERROR, "net", "poll", "TIMEOUT", "timeout", "c", 0, null); }   // 10 s in total
        assertEquals(1, Files.readAllLines(f.toPath(), Charset.forName("UTF-8")).size());
        c.t += CigramDiag.Store.WRITE_EVERY_MS + 1;
        s.record(CigramDiag.Severity.ERROR, "net", "poll", "TIMEOUT", "timeout", "c", 0, null);
        assertEquals(2, Files.readAllLines(f.toPath(), Charset.forName("UTF-8")).size());
        assertEquals(1001, s.snapshot().get(0).count);
    }

    // ------------------------------------------------------------ retention + damage

    @Test
    public void oldHistoryIsDroppedAndDamagedLinesAreSkippedOnLoad() throws Exception {
        File f = new File(tmp.getRoot(), "old.jsonl");
        Clock c = new Clock();
        CigramDiag.Store first = store(f, c);
        first.record(CigramDiag.Severity.ERROR, "old", "x", "OTHER", "old failure", "c", 0, null);
        c.t += CigramDiag.Store.MAX_AGE_MS / 2;
        first.record(CigramDiag.Severity.ERROR, "mid", "x", "OTHER", "recent failure", "c", 0, null);
        FileOutputStream out = new FileOutputStream(f, true);
        out.write("{this is not json\n\n\u0000\u0000\n".getBytes("UTF-8"));
        out.close();
        c.t += CigramDiag.Store.MAX_AGE_MS / 2 + 1000L;                 // "old" is now older than 14 days, "mid" is not
        CigramDiag.Store reopened = store(f, c);
        List<CigramDiag.Event> rows = reopened.snapshot();
        assertEquals(1, rows.size());
        assertEquals("mid", rows.get(0).component);
    }

    @Test
    public void theFileNeverGrowsPastItsLimit() throws Exception {
        File f = new File(tmp.getRoot(), "big.jsonl");
        Clock c = new Clock();
        CigramDiag.Store s = store(f, c);
        for (int i = 0; i < 3000; i++) {
            c.t += CigramDiag.Store.WRITE_EVERY_MS + 1;
            s.record(CigramDiag.Severity.ERROR, "comp" + i, "op", "OTHER", "failure number " + i + " padding padding padding padding padding padding", "c", 0, null);
        }
        assertTrue("file " + f.length(), f.length() <= CigramDiag.Store.MAX_BYTES + 2048);
    }

    // ------------------------------------------------------------ classification

    @Test
    public void exceptionsAreSortedByKindNotByMessage() {
        assertEquals("DNS", CigramDiag.categoryOf(new java.net.UnknownHostException("x")));
        assertEquals("TIMEOUT", CigramDiag.categoryOf(new java.net.SocketTimeoutException()));
        assertEquals("TLS", CigramDiag.categoryOf(new javax.net.ssl.SSLException("x")));
        assertEquals("NETWORK", CigramDiag.categoryOf(new java.net.ConnectException()));
        assertEquals("PARSE", CigramDiag.categoryOf(new org.json.JSONException("x")));
        assertEquals("STORAGE", CigramDiag.categoryOf(new java.io.IOException("write failed: ENOSPC (No space left on device)")));
        assertEquals("MEMORY", CigramDiag.categoryOf(new OutOfMemoryError()));
        assertEquals("NETWORK", CigramDiag.categoryOf(new RuntimeException("wrapper", new java.net.SocketException("reset"))));
        assertEquals("OTHER", CigramDiag.categoryOf(new IllegalStateException()));
        assertEquals("AUTH", CigramDiag.categoryOfHttp(401));
        assertEquals("AUTH", CigramDiag.categoryOfHttp(403));
        assertEquals("RATE_LIMIT", CigramDiag.categoryOfHttp(429));
        assertEquals("SERVER", CigramDiag.categoryOfHttp(503));
        assertEquals("NOT_FOUND", CigramDiag.categoryOfHttp(404));
    }

    @Test
    public void recordingNeverThrowsIntoTheCaller() {
        CigramDiag.record(null, null, null, null, null, null, -5, null);
        CigramDiag.warn(null, null, null, null);
        CigramDiag.error("c", "o", null, new RuntimeException((String) null));
        CigramDiag.recovery("c", "o", "X", null, null, 0, null);
        assertNotNull(CigramDiag.export());
        assertNotNull(CigramDiag.events());
        assertEquals(8, CigramDiag.newCorrelationId().length());
    }

    // ------------------------------------------------------------ the update package check

    @Test
    public void everyBranchOfTheUpdateDecisionIsCovered() {
        String own = "com.Cigram.vid";
        String sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        java.util.Set<String> mine = new java.util.HashSet<String>(java.util.Arrays.asList("aa11", "bb22"));
        java.util.Set<String> none = java.util.Collections.<String>emptySet();
        // 19.6 fail-closed: every decision needs a fingerprint in the published manifest
        assertEquals(CigramUpdateVerify.Verdict.EMPTY, CigramUpdateVerify.decide(0, sha, sha, own, 96, own, 95, mine, mine));
        assertEquals(CigramUpdateVerify.Verdict.NO_HASH, CigramUpdateVerify.decide(10, "", "", own, 96, own, 95, mine, mine));
        assertEquals(CigramUpdateVerify.Verdict.NO_HASH, CigramUpdateVerify.decide(10, "anything", "not-a-hash", own, 96, own, 95, mine, mine));
        assertEquals(CigramUpdateVerify.Verdict.NO_HASH, CigramUpdateVerify.decide(10, sha, null, own, 96, own, 95, mine, mine));
        assertEquals(CigramUpdateVerify.Verdict.OK, CigramUpdateVerify.decide(10, sha, sha, own, 96, own, 95, mine, mine));
        assertEquals(CigramUpdateVerify.Verdict.OK, CigramUpdateVerify.decide(10, sha, sha.toUpperCase(), own, 96, own, 95, mine, mine));
        assertEquals(CigramUpdateVerify.Verdict.OK, CigramUpdateVerify.decide(10, sha, "sha256:" + sha, own, 96, own, 95, mine, mine));
        assertEquals(CigramUpdateVerify.Verdict.HASH_MISMATCH, CigramUpdateVerify.decide(10, "00" + sha.substring(2), sha, own, 96, own, 95, mine, mine));
        assertEquals(CigramUpdateVerify.Verdict.HASH_MISMATCH, CigramUpdateVerify.decide(10, "", sha, own, 96, own, 95, mine, mine));
        assertEquals(CigramUpdateVerify.Verdict.WRONG_PACKAGE, CigramUpdateVerify.decide(10, sha, sha, "com.evil.app", 999, own, 95, mine, mine));
        assertEquals(CigramUpdateVerify.Verdict.NOT_NEWER, CigramUpdateVerify.decide(10, sha, sha, own, 95, own, 95, mine, mine));
        assertEquals(CigramUpdateVerify.Verdict.NOT_NEWER, CigramUpdateVerify.decide(10, sha, sha, own, 90, own, 95, mine, mine));
        // a package this process cannot parse is accepted only because its SHA-256 matched the published one
        assertEquals(CigramUpdateVerify.Verdict.OK, CigramUpdateVerify.decide(10, sha, sha, null, 0, own, 95, none, none));
        assertEquals(CigramUpdateVerify.Verdict.OK, CigramUpdateVerify.decide(10, sha, sha, null, 0, own, 95, null, null));
        // signer pinning: a different certificate is refused, a shared one (or a rotation lineage containing it) passes
        java.util.Set<String> stranger = new java.util.HashSet<String>(java.util.Arrays.asList("ee99"));
        java.util.Set<String> rotated = new java.util.HashSet<String>(java.util.Arrays.asList("ff00", "bb22"));
        assertEquals(CigramUpdateVerify.Verdict.BAD_SIGNER, CigramUpdateVerify.decide(10, sha, sha, own, 96, own, 95, stranger, mine));
        assertEquals(CigramUpdateVerify.Verdict.OK, CigramUpdateVerify.decide(10, sha, sha, own, 96, own, 95, rotated, mine));
        // certificates that could not be read on either side skip the pin (the hash already matched)
        assertEquals(CigramUpdateVerify.Verdict.OK, CigramUpdateVerify.decide(10, sha, sha, own, 96, own, 95, none, mine));
        assertEquals(CigramUpdateVerify.Verdict.OK, CigramUpdateVerify.decide(10, sha, sha, own, 96, own, 95, stranger, none));
        // the order matters: a swapped file is a HASH problem before it is a signer problem
        assertEquals(CigramUpdateVerify.Verdict.HASH_MISMATCH, CigramUpdateVerify.decide(10, "00" + sha.substring(2), sha, own, 96, own, 95, stranger, mine));
        assertEquals("", CigramUpdateVerify.normalizeHash(null));
        assertEquals("", CigramUpdateVerify.normalizeHash("zz" + sha.substring(2)));
        assertEquals(sha, CigramUpdateVerify.normalizeHash("  " + sha.toUpperCase() + " "));
    }

    @Test
    public void onlyHttpsPackageLinksWithARealHostAreOffered() {
        assertTrue(CigramUpdateVerify.isSecureUrl("https://pub-abc.r2.dev/cigram-18.6.0.apk"));
        assertTrue(CigramUpdateVerify.isSecureUrl("  HTTPS://downloads.example.org/app.apk  "));
        assertFalse(CigramUpdateVerify.isSecureUrl("http://pub-abc.r2.dev/cigram.apk"));          // downgrade
        assertFalse(CigramUpdateVerify.isSecureUrl("ftp://example.org/cigram.apk"));
        assertFalse(CigramUpdateVerify.isSecureUrl("file:///sdcard/cigram.apk"));
        assertFalse(CigramUpdateVerify.isSecureUrl("//example.org/cigram.apk"));
        assertFalse(CigramUpdateVerify.isSecureUrl("https://"));
        assertFalse(CigramUpdateVerify.isSecureUrl("https:///cigram.apk"));                         // no host
        assertFalse(CigramUpdateVerify.isSecureUrl("https://trusted.example@evil.example/a.apk"));  // user-info host trick
        assertFalse(CigramUpdateVerify.isSecureUrl("https://exa mple.org/a.apk"));                  // not a valid URI
        assertFalse(CigramUpdateVerify.isSecureUrl(""));
        assertFalse(CigramUpdateVerify.isSecureUrl(null));
    }

    @Test
    public void everyRefusalHasAnExplanationForTheViewer() {
        for (CigramUpdateVerify.Verdict v : CigramUpdateVerify.Verdict.values()) {
            String msg = CigramUpdateVerify.messageFor(v);
            if (v == CigramUpdateVerify.Verdict.OK) assertEquals("", msg);
            else assertTrue("no message for " + v, msg.length() > 0);
        }
    }

    @Test
    public void certificateDigestsAreStableSha256() {
        // SHA-256 of the three bytes "abc" – the same reference value the file hash is checked against
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CigramUpdateVerify.sha256Hex("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    public void theHashOfARealFileIsTheKnownSha256() throws Exception {
        File f = tmp.newFile("abc.bin");
        FileOutputStream o = new FileOutputStream(f);
        o.write("abc".getBytes("UTF-8"));
        o.close();
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", CigramUpdateVerify.sha256Hex(f));
        // a file bigger than the read buffer hashes the same as the reference digest
        File big = tmp.newFile("big.bin");
        byte[] chunk = new byte[1000];
        java.util.Arrays.fill(chunk, (byte) 7);
        FileOutputStream bo = new FileOutputStream(big);
        for (int i = 0; i < 300; i++) bo.write(chunk);
        bo.close();
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder want = new StringBuilder();
        for (byte b : md.digest(Files.readAllBytes(big.toPath()))) want.append(String.format("%02x", b & 0xff));
        assertEquals(want.toString(), CigramUpdateVerify.sha256Hex(big));
    }
}
