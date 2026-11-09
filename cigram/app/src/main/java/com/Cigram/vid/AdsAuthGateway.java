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
    }

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
            if (r2.authRejected()) markExpired(r2);
            return r2;
        }
        boolean can;
        try { can = auth.canRenew(); } catch (Throwable t) { can = false; }
        if (can) {
            // credentials exist but the silent login could not be completed right now (network, throttle, server)
            r.authTransient = true;
            r.message = "تعذر تجديد الجلسة الآن. حاول بعد قليل — حسابك ما زال مسجّلاً.";
        } else {
            markExpired(r);
        }
        return r;
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
