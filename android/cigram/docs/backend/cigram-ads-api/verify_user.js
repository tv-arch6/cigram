async function verifyUser(request, env) {
  const tok = bearerOf(request);
  if (!tok || tok.length > 4e3) fail(401, "login_required", "سجّل الدخول أولاً.");
  const verifyUrl = String(env.AUTH_VERIFY_URL || "https://cigram-auth-api.wwq-mixtv.workers.dev/me").trim();
  const ck = await sha256Hex(tok);
  const t0 = Date.now();
  const hit = _authCache.get(ck);
  if (hit && hit.exp > t0) return hit.user;
  let user = null;
  let verdict = "";   // "invalid": the auth service itself said so. "unavailable": we could not find out.
  if (env.JWT_SECRET) {
    const claims = await verifyJwtHS256(tok, env.JWT_SECRET);
    if (claims) user = pickUser(claims);
  }
  if (!user && verifyUrl) {
    const ctl = typeof AbortController !== "undefined" ? new AbortController() : null;
    const timer = ctl ? setTimeout(() => ctl.abort(), 6e3) : null;
    try {
      const vreq = { headers: { authorization: "Bearer " + tok, accept: "application/json" } };
      if (ctl) vreq.signal = ctl.signal;
      const r = env.AUTH_API && typeof env.AUTH_API.fetch === "function" ? await env.AUTH_API.fetch(verifyUrl, vreq) : await fetch(verifyUrl, vreq);
      if (r.status === 401 || r.status === 403) verdict = "invalid";
      else if (r.ok) {
        user = pickUser(await r.json().catch(() => null));
        if (!user) verdict = "unavailable";   // 200 without an identity: a malformed answer, not a logout
      } else verdict = "unavailable";         // 429, 5xx, a wrong binding / URL ...
    } catch (e) {
      verdict = "unavailable";
    } finally {
      if (timer) clearTimeout(timer);
    }
  }
  if (!user) {
    const grace = verdict !== "invalid" && hit && t0 - hit.at < 30 * 6e4;
    // route-level log only: no token, no user id
    console.log(JSON.stringify({ evt: "chat_auth", outcome: grace ? "grace" : verdict === "invalid" ? "invalid" : "unavailable", ms: Date.now() - t0 }));
    if (grace) return hit.user;
    if (verdict === "invalid") {
      _authCache.delete(ck);
      fail(401, "invalid_token", "انتهت الجلسة. سجّل الدخول من جديد.");
    }
    fail(503, "auth_unavailable", "تعذر التحقق من الجلسة الآن. حاول بعد قليل — حسابك ما زال مسجّلاً.");
  }
  user.key = await userKeyOf(user.id, user.email);
  if (_authCache.size > 2e3) _authCache.clear();
  _authCache.set(ck, { user, exp: t0 + 5 * 6e4, at: t0 });
  return user;
}
__name(verifyUser, "verifyUser");