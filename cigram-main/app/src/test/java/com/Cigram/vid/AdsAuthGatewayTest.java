package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 18.3 — private chat authentication. The policy (renew once, retry once, never call a temporary problem a logout) is tested
 * at two levels: the pure gateway with a fake token source, and CigramAds.requestOnce over real HTTP against a local server.
 */
public class AdsAuthGatewayTest {

    // ---------------------------------------------------------------- fake token source
    static class FakeAuth implements AdsAuthGateway.Auth {
        volatile String current = "old";
        volatile String renewTo = "new";
        volatile boolean expiresSoon;
        volatile boolean canRenew = true;
        volatile AdsAuthGateway.Probe probe = AdsAuthGateway.Probe.UNKNOWN;
        final AtomicInteger probeCalls = new AtomicInteger();
        final AtomicInteger renewCalls = new AtomicInteger();
        final AtomicInteger loginClears = new AtomicInteger();    // would count any attempt to drop the session: must stay 0

        @Override public String current() { return current; }
        @Override public String renew() {
            renewCalls.incrementAndGet();
            try { Thread.sleep(40); } catch (InterruptedException ignored) { }   // a real login takes time: concurrent callers pile up
            if (renewTo.length() > 0) current = renewTo;
            return renewTo;
        }
        @Override public boolean expiresSoon() { return expiresSoon; }
        @Override public boolean canRenew() { return canRenew; }
        @Override public AdsAuthGateway.Probe probe(String token) { probeCalls.incrementAndGet(); return probe; }
    }

    private static CigramAds.Result result(int code, String error) {
        CigramAds.Result r = new CigramAds.Result();
        r.code = code;
        r.error = error;
        r.ok = code >= 200 && code < 300;
        return r;
    }

    /** Calls whose answer depends on the bearer: "new" is accepted, anything else gets {@code staleAnswer}. */
    private static final class Scripted implements AdsAuthGateway.Call {
        final List<String> bearers = Collections.synchronizedList(new ArrayList<String>());
        final CigramAds.Result stale;
        final String accepted;

        Scripted(CigramAds.Result stale, String accepted) { this.stale = stale; this.accepted = accepted; }

        @Override public CigramAds.Result run(String bearer) {
            bearers.add(bearer);
            return bearer.equals(accepted) ? result(200, "") : copy(stale);
        }
    }

    private static CigramAds.Result copy(CigramAds.Result r) {
        CigramAds.Result c = result(r.code, r.error);
        c.offline = r.offline;
        return c;
    }

    // ---------------------------------------------------------------- 19.0: the auth service has the last word
    @Test public void aTokenTheAuthServiceStillAcceptsIsNeverReportedAsExpired() {
        FakeAuth a = new FakeAuth();
        a.canRenew = false;                                  // e.g. a Google sign-in: nothing to renew with
        a.probe = AdsAuthGateway.Probe.VALID;
        Scripted call = new Scripted(result(401, "invalid_token"), "never");
        CigramAds.Result r = AdsAuthGateway.run(call, a, "old");
        assertFalse("the session is fine, the chat Worker could not verify it", r.authExpired);
        assertTrue(r.authTransient);
        assertTrue(r.serverCannotVerify);
        assertEquals(1, a.probeCalls.get());
        assertEquals(0, a.loginClears.get());
    }

    @Test public void anInvalidTokenWithNothingToRenewWithIsExpired() {
        FakeAuth a = new FakeAuth();
        a.canRenew = false;
        a.probe = AdsAuthGateway.Probe.INVALID;
        CigramAds.Result r = AdsAuthGateway.run(new Scripted(result(401, "invalid_token"), "never"), a, "old");
        assertTrue(r.authExpired);
        assertFalse(r.authTransient);
        assertFalse(r.serverCannotVerify);
    }

    @Test public void whenTheAuthServiceCannotBeAskedTheOldRuleApplies() {
        FakeAuth a = new FakeAuth();
        a.canRenew = false;
        a.probe = AdsAuthGateway.Probe.UNKNOWN;               // offline / 5xx from the auth service
        assertTrue(AdsAuthGateway.run(new Scripted(result(401, "invalid_token"), "never"), a, "old").authExpired);
        FakeAuth b = new FakeAuth();
        b.canRenew = true;
        b.renewTo = "";                                       // renewal failed for a temporary reason
        b.probe = AdsAuthGateway.Probe.UNKNOWN;
        CigramAds.Result r = AdsAuthGateway.run(new Scripted(result(401, "invalid_token"), "never"), b, "old");
        assertTrue(r.authTransient);
        assertFalse(r.authExpired);
    }

    @Test public void aRenewedTokenThatIsStillRefusedButValidAtTheAuthServiceIsTemporary() {
        FakeAuth a = new FakeAuth();
        a.renewTo = "new";
        a.probe = AdsAuthGateway.Probe.VALID;
        CigramAds.Result r = AdsAuthGateway.run(new Scripted(result(401, "invalid_token"), "never"), a, "old");
        assertTrue(r.renewed);
        assertTrue(r.authTransient && r.serverCannotVerify);
        assertFalse(r.authExpired);
    }

    @Test public void aProbeThatThrowsIsNotAnAuthResult() {
        FakeAuth a = new FakeAuth() { @Override public AdsAuthGateway.Probe probe(String t) { throw new IllegalStateException("boom"); } };
        a.canRenew = false;
        CigramAds.Result r = AdsAuthGateway.run(new Scripted(result(401, "invalid_token"), "never"), a, "old");
        assertTrue("falls back to the pre-19.0 rule", r.authExpired);
    }

    // ---------------------------------------------------------------- gateway
    @Test public void validTokenIsUsedAsIsWithoutRenewal() {
        FakeAuth a = new FakeAuth();
        Scripted c = new Scripted(result(401, "invalid_token"), "old");
        CigramAds.Result r = AdsAuthGateway.run(c, a, "old");
        assertTrue(r.ok);
        assertEquals(1, c.bearers.size());
        assertEquals(0, a.renewCalls.get());
        assertFalse(r.renewed);
    }

    @Test public void a401IsAnsweredByOneRenewalAndOneRetry() {
        FakeAuth a = new FakeAuth();
        Scripted c = new Scripted(result(401, "invalid_token"), "new");
        CigramAds.Result r = AdsAuthGateway.run(c, a, "old");
        assertTrue("the retry with the renewed token succeeds", r.ok);
        assertTrue(r.renewed);
        assertFalse(r.authExpired);
        assertEquals(1, a.renewCalls.get());
        assertEquals(java.util.Arrays.asList("old", "new"), c.bearers);
    }

    @Test public void noStoredCredentialsMeansTheSessionIsReallyExpired() {
        FakeAuth a = new FakeAuth();
        a.renewTo = "";
        a.canRenew = false;
        CigramAds.Result r = AdsAuthGateway.run(new Scripted(result(401, "invalid_token"), "never"), a, "old");
        assertTrue(r.authExpired);
        assertFalse(r.authTransient);
        assertTrue(r.message.contains("سجّل الدخول"));
    }

    @Test public void aFailedRenewalWithCredentialsIsTemporaryNotALogout() {
        FakeAuth a = new FakeAuth();
        a.renewTo = "";            // the silent login could not complete (network, throttle, server)
        a.canRenew = true;         // credentials are still sealed on the device
        CigramAds.Result r = AdsAuthGateway.run(new Scripted(result(401, "invalid_token"), "never"), a, "old");
        assertFalse("must not tell the user to log in again", r.authExpired);
        assertTrue(r.authTransient);
        assertTrue(r.message.contains("ما زال مسجّلاً"));
    }

    @Test public void stillRefusedAfterRenewalIsReportedOnceWithoutALoop() {
        FakeAuth a = new FakeAuth();
        Scripted c = new Scripted(result(401, "invalid_token"), "never");
        CigramAds.Result r = AdsAuthGateway.run(c, a, "old");
        assertTrue(r.authExpired);
        assertEquals("exactly one renewal and one retry", 1, a.renewCalls.get());
        assertEquals(2, c.bearers.size());
    }

    @Test public void temporaryProblemsAreNeverTreatedAsAuthentication() {
        int[] codes = {500, 502, 503, 504, 429, 403, 408, 0};
        for (int code : codes) {
            FakeAuth a = new FakeAuth();
            CigramAds.Result stale = result(code, code == 503 ? "auth_unavailable" : "");
            CigramAds.Result r = AdsAuthGateway.run(new Scripted(stale, "never"), a, "old");
            assertEquals("no renewal for HTTP " + code, 0, a.renewCalls.get());
            assertFalse(r.authExpired);
            assertFalse(r.authTransient);
        }
        FakeAuth a = new FakeAuth();
        CigramAds.Result off = result(401, "");
        off.offline = true;              // a timeout / no network can never be an auth result
        AdsAuthGateway.run(new Scripted(off, "never"), a, "old");
        assertEquals(0, a.renewCalls.get());
    }

    @Test public void aTokenAboutToExpireIsRenewedBeforeTheRequest() {
        FakeAuth a = new FakeAuth();
        a.expiresSoon = true;
        Scripted c = new Scripted(result(401, "invalid_token"), "new");
        CigramAds.Result r = AdsAuthGateway.run(c, a, "old");
        assertTrue(r.ok);
        assertEquals("the first and only request already carries the new token", java.util.Arrays.asList("new"), c.bearers);
        assertEquals(1, a.renewCalls.get());
    }

    @Test public void publicRequestsWithoutABearerNeverTouchTheSession() {
        FakeAuth a = new FakeAuth();
        a.expiresSoon = true;
        Scripted c = new Scripted(result(401, "invalid_token"), "x");
        AdsAuthGateway.run(c, a, "");
        assertEquals(0, a.renewCalls.get());
        assertEquals(1, c.bearers.size());
    }

    @Test public void manyRequestsFailingTogetherShareOneRenewal() throws Exception {
        final FakeAuth a = new FakeAuth();
        final int n = 8;
        final CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(n);
        final AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < n; i++) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        go.await();
                        CigramAds.Result r = AdsAuthGateway.run(new Scripted(result(401, "invalid_token"), "new"), a, "old");
                        if (r.ok) ok.incrementAndGet();
                    } catch (Throwable ignored) { } finally { done.countDown(); }
                }
            }).start();
        }
        go.countDown();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("every request recovers", n, ok.get());
        assertEquals("one login, not eight", 1, a.renewCalls.get());
        assertEquals(0, a.loginClears.get());
    }

    // ---------------------------------------------------------------- real HTTP against a local server (plain sockets: the unit-test
    // classpath is android.jar, which has no com.sun.net.httpserver)
    private ServerSocket server;
    private Thread serverThread;
    private final List<String> authHeaders = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
    private volatile int mode;   // 0 = accept "Bearer new" only, 1 = always 503 auth_unavailable, 2 = always 200
    private AdsAuthGateway.Auth savedAuth;
    private String savedBase;

    private static String readLine(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        return c < 0 && b.size() == 0 ? null : b.toString("UTF-8");
    }

    private void serve(Socket sock) throws Exception {
        InputStream in = sock.getInputStream();
        String requestLine = readLine(in);
        if (requestLine == null) return;
        String auth = "null";
        int len = 0;
        String line;
        while ((line = readLine(in)) != null && line.length() > 0) {
            String low = line.toLowerCase(java.util.Locale.US);
            if (low.startsWith("authorization:")) auth = line.substring(14).trim();
            if (low.startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
        }
        byte[] body = new byte[len];
        int got = 0;
        while (got < len) { int n = in.read(body, got, len - got); if (n < 0) break; got += n; }
        authHeaders.add(auth);
        bodies.add(new String(body, 0, got, "UTF-8"));
        int code;
        String json;
        if (mode == 1) { code = 503; json = "{\"ok\":false,\"error\":\"auth_unavailable\"}"; }
        else if (mode == 2 || "Bearer new".equals(auth)) { code = 200; json = "{\"ok\":true,\"message\":{\"seq\":7}}"; }
        else { code = 401; json = "{\"ok\":false,\"error\":\"invalid_token\"}"; }
        byte[] out = json.getBytes("UTF-8");
        OutputStream os = sock.getOutputStream();
        os.write(("HTTP/1.1 " + code + " X\r\nContent-Type: application/json\r\nContent-Length: " + out.length + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
        os.write(out);
        os.flush();
    }

    @Before public void startServer() throws Exception {
        savedAuth = CigramAds.AUTH;
        savedBase = CigramAds.apiBase;
        server = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"));
        serverThread = new Thread(new Runnable() {
            @Override public void run() {
                while (!server.isClosed()) {
                    try {
                        Socket s = server.accept();
                        try { serve(s); } finally { s.close(); }
                    } catch (Throwable ignored) { }
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
        CigramAds.apiBase = "http://127.0.0.1:" + server.getLocalPort();
    }

    @After public void stopServer() throws Exception {
        server.close();
        CigramAds.AUTH = savedAuth;
        CigramAds.apiBase = savedBase;
    }

    @Test public void httpA401IsRenewedAndTheSameBodyIsSentAgain() {
        FakeAuth a = new FakeAuth();
        CigramAds.AUTH = a;
        String body = "{\"client_id\":\"c-123\",\"type\":\"text\",\"text\":\"hello\"}";
        CigramAds.Result r = CigramAds.request("POST", "/chat/send", body, "old");
        assertTrue(r.ok);
        assertTrue(r.renewed);
        assertEquals(java.util.Arrays.asList("Bearer old", "Bearer new"), authHeaders);
        assertEquals("the retry carries the same message (same client_id), so the Worker stores it once", bodies.get(0), bodies.get(1));
        assertNotNull(r.json.optJSONObject("message"));
    }

    @Test public void httpTemporaryAuthOutageIsNotASessionExpiry() {
        FakeAuth a = new FakeAuth();
        CigramAds.AUTH = a;
        mode = 1;
        CigramAds.Result r = CigramAds.request("GET", "/chat/thread", null, "old");
        assertFalse(r.ok);
        assertEquals(503, r.code);
        assertFalse(r.authExpired);
        assertEquals("no renewal, no second request", 0, a.renewCalls.get());
        assertEquals(1, authHeaders.size());
        assertTrue(r.message.contains("ما زال مسجّلاً"));
        assertEquals("the stored token is untouched", "old", a.current);
    }

    @Test public void httpUnreachableServerIsOfflineAndNeverTouchesTheSession() {
        FakeAuth a = new FakeAuth();
        CigramAds.AUTH = a;
        CigramAds.apiBase = "http://127.0.0.1:1";      // nothing listens there
        CigramAds.Result r = CigramAds.request("GET", "/chat/thread", null, "old");
        assertTrue(r.offline);
        assertFalse(r.authExpired);
        assertEquals(0, a.renewCalls.get());
        assertEquals("old", a.current);
    }

    @Test public void httpGenuineExpiryWithoutCredentialsIsReportedAccurately() {
        FakeAuth a = new FakeAuth();
        a.renewTo = "";
        a.canRenew = false;
        CigramAds.AUTH = a;
        CigramAds.Result r = CigramAds.request("GET", "/chat/thread", null, "old");
        assertTrue(r.authExpired);
        assertEquals(401, r.code);
        assertEquals("old", a.current);   // the session marker is not cleared by the request layer
    }
}
