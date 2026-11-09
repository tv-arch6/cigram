package com.Cigram.vid;

/**
 * Authentication policy of the private chat (cigram-ads-api) calls.
 *
 * Root cause of the false «انتهت الجلسة»: the chat layer used the raw token stored at login and never asked
 * {@link CigramSession} to renew it (the rest of the app does), while the Worker answered 401 both for an expired
 * token and for a failed / slow check of that token. Rules enforced here:
 *  1. A token that expires within a day is renewed BEFORE the request.
 *  2. A 401 is answered by exactly one silent renewal (the sealed credentials of CigramSession) and exactly one
 *     retry of the SAME request body (a message keeps its client_id, so the Worker cannot store it twice).
 *  3. Several requests failing together share one renewal: the first one logs in, the others pick up the new token.
 *  4. The session is reported as expired ONLY when the server still answers 401 after a renewal, or when no
 *     credentials are stored to renew with. A renewal that failed for any other reason is reported as a temporary
 *     problem, never as a logout. Timeouts, offline, 5xx and malformed answers are not authentication results at all.
 *  5. Nothing here ever clears the stored session.
 *  6. (19.0) Before a 401 that could not be solved by a renewal is called an expired session, the token is checked directly
 *     against the auth service from the phone (the same call the rest of the app uses). Valid there = the chat Worker could
 *     not verify it (binding / network problem on its side): that is a temporary condition, never «sign in again», the
 *     queue is kept and retried. Only «invalid there as well» (or no answer at all from a signed-out account) is an expiry.
 */
final class AdsAuthGateway {

    private AdsAuthGateway() { }

    /** The token / renewal source (the real one wraps CigramUserData + CigramSession; tests use a fake). */
    interface Auth {
        /** The token currently stored for the account ("" when none). */
        String current();
        /** Silent re-login with the sealed credentials. Blocking. Returns the new token or "". */
        String renew();
        /** True when the token's known expiry is within the renewal window. */
        boolean expiresSoon();
        /** True when sealed credentials exist, i.e. a renewal can work at all. */
        boolean canRenew();
        /** 19.0: asks the auth service itself (not the chat Worker) whether {@code token} is a live session. Blocking. */
        default Probe probe(String token) { return Probe.UNKNOWN; }
    }

    /** The auth service's verdict about a token. UNKNOWN = it could not be asked (offline, timeout, 5xx). */
    enum Probe { VALID, INVALID, UNKNOWN }

    /** One HTTP attempt with a given bearer. */
    interface Call {
        CigramAds.Result run(String bearer);
    }

    private static final Object RENEW = new Object();

    static CigramAds.Result run(Call call, Auth auth, String bearer) {
        String use = bearer == null ? "" : bearer;
        if (use.length() == 0 || auth == null) return call.run(use);    // public (no-login) request
        try {
            if (auth.expiresSoon()) {
                String fresh = freshToken(auth, use);
                if (fresh.length() > 0) use = fresh;
            }
        } catch (Throwable ignored) { /* a failed pre-emptive renewal is not an error: the request decides */ }

        CigramAds.Result r = call.run(use);
        if (!r.authRejected()) return r;

        String fresh = "";
        try {
            fresh = freshToken(auth, use);
        } catch (Throwable ignored) { }
        if (fresh.length() > 0 && !fresh.equals(use)) {
            CigramAds.Result r2 = call.run(fresh);
            r2.renewed = true;
            if (r2.authRejected()) classify(r2, auth, fresh, true, true);
            return r2;
        }
        boolean can;
        try { can = auth.canRenew(); } catch (Throwable t) { can = false; }
        classify(r, auth, use, false, can);
        return r;
    }

    /**
     * The chat Worker refused the token and a renewal did not help. Ask the auth service itself before deciding:
     *  VALID   -> the account is signed in; the chat Worker cannot verify it right now (temporary, retried, never a logout)
     *  otherwise the pre-19.0 rule: a token that was just renewed and is STILL refused = expired; no renewal was possible
     *  = expired when there are no stored credentials, temporary when there are (the silent login failed for a passing reason).
     */
    static void classify(CigramAds.Result r, Auth auth, String token, boolean renewedAndStillRefused, boolean canRenew) {
        Probe p;
        try { p = auth.probe(token); } catch (Throwable t) { p = Probe.UNKNOWN; }
        if (p == null) p = Probe.UNKNOWN;
        if (p == Probe.VALID) {
            r.authTransient = true;
            r.serverCannotVerify = true;
            r.message = "خدمة الدعم لا تستطيع التحقق من حسابك الآن (المشكلة من الخادم وليست من حسابك). رسائلك محفوظة وسنعيد المحاولة تلقائياً.";
            return;
        }
        if (canRenew && !renewedAndStillRefused) {
            // credentials exist but the silent login could not be completed right now (network, throttle, server)
            r.authTransient = true;
            r.message = "تعذر تجديد الجلسة الآن. حاول بعد قليل — حسابك ما زال مسجّلاً.";
            return;
        }
        markExpired(r);
    }

    private static void markExpired(CigramAds.Result r) {
        r.authExpired = true;
        r.message = "انتهت جلسة حسابك ولا يمكن تجديدها تلقائياً. سجّل الدخول من جديد لمتابعة المحادثة؛ رسائلك المحفوظة باقية.";
    }

    /** The token to use instead of {@code used}: one already renewed by another request, or a new one from a single renewal. */
    static String freshToken(Auth auth, String used) {
        synchronized (RENEW) {
            String cur = auth.current();
            if (cur != null && cur.length() > 0 && !cur.equals(used)) return cur;
            String t = auth.renew();
            return t == null ? "" : t;
        }
    }
}
