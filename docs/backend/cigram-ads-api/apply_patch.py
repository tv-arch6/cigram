#!/usr/bin/env python3
"""18.2 Private Support — additive patch for the LIVE cigram-ads-api worker.js (ChatHub).

Usage:  python3 apply_patch.py <live worker.js> <output worker.js>
Every anchor must match exactly once, otherwise the script stops and changes nothing.
Not deployed by the delivery: run it, review the diff, test with `wrangler dev`, then deploy yourself.
"""
import sys

src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding="utf-8").read()


def rep(old, new):
    global s
    n = s.count(old)
    if n != 1:
        raise SystemExit("anchor found %d times (expected 1): %r" % (n, old[:90]))
    s = s.replace(old, new)


# 1) developer accounts + roles. ADMIN_TOKEN stays the owner account (backwards compatible).
rep('''async function requireAdmin(request, env) {
  const ip = clientIp(request);
  const tok = bearerOf(request);
  if (!env.ADMIN_TOKEN) fail(503, "admin_not_configured",''', '''// 18.2 — developer accounts (optional secret DEVELOPERS = JSON array):
//   [{"id":"dev_1","name":"حسن","avatar":"https://…","role":"owner"|"support","token_sha256":"<hex sha256 of that developer's token>"}]
// ADMIN_TOKEN keeps working as the owner account (DEV_NAME / DEV_AVATAR give its public name / picture).
function devPublic(dev) {
  return dev ? { name: cleanText(dev.name, 40), avatar: /^https:\\/\\//.test(String(dev.avatar || "")) ? String(dev.avatar).slice(0, 400) : "" } : null;
}
__name(devPublic, "devPublic");
async function developerOf(request, env) {
  const tok = bearerOf(request);
  if (!tok) return null;
  if (env.ADMIN_TOKEN && timingSafeEqual(tok, env.ADMIN_TOKEN)) {
    return { id: "owner", name: cleanText(env.DEV_NAME, 40) || "\\u0645\\u0637\\u0648\\u0651\\u0631 Cigram", avatar: String(env.DEV_AVATAR || ""), role: "owner" };
  }
  if (!env.DEVELOPERS) return null;
  let list = [];
  try { list = JSON.parse(env.DEVELOPERS); } catch (e) { return null; }
  const h = await sha256Hex(tok);
  for (const d of Array.isArray(list) ? list : []) {
    if (d && d.token_sha256 && timingSafeEqual(h, String(d.token_sha256).toLowerCase())) {
      return { id: cleanText(d.id, 40), name: cleanText(d.name, 40), avatar: String(d.avatar || ""), role: d.role === "owner" ? "owner" : "support" };
    }
  }
  return null;
}
__name(developerOf, "developerOf");
function requireRole(dev, role) {
  if (role === "owner" && (!dev || dev.role !== "owner")) fail(403, "forbidden_role", "\\u0647\\u0630\\u0627 \\u0627\\u0644\\u0625\\u062C\\u0631\\u0627\\u0621 \\u0644\\u0644\\u0645\\u0627\\u0644\\u0643 \\u0641\\u0642\\u0637.");
}
__name(requireRole, "requireRole");
async function requireAdmin(request, env) {
  const ip = clientIp(request);
  const tok = bearerOf(request);
  const dev18 = await developerOf(request, env);
  if (dev18) return dev18;
  if (!env.ADMIN_TOKEN && !env.DEVELOPERS) fail(503, "admin_not_configured",''')

# 2) presence: the developer's public identity + last time a developer was connected
rep('''    if (!env.CHAT_HUB) return reply({ ok: true, state: "offline" });
    return reply(await hub(env, "presence", { settings: st && { hours: st.hours } }));''', '''    if (!env.CHAT_HUB) return reply({ ok: true, state: "offline" });
    const pr = await hub(env, "presence", { settings: st && { hours: st.hours } });
    pr.developer = { name: cleanText(env.DEV_NAME, 40) || "\\u0645\\u0637\\u0648\\u0651\\u0631 Cigram", avatar: /^https:\\/\\//.test(String(env.DEV_AVATAR || "")) ? String(env.DEV_AVATAR) : "" };
    return reply(pr);''')

# 3) user routes: the auth id is stored on the thread (admins only see it); consent only for advertising orders
rep('''    if (method === "GET" && path === "/chat/thread") return reply(await hub(env, "thread_open", { user_key: thread, name: userNameOf(user), email: user.email }));''',
    '''    if (method === "GET" && path === "/chat/thread") return reply(await hub(env, "thread_open", { user_key: thread, name: userNameOf(user), email: user.email, uid: user.id }));''')
rep('''    if (method === "POST" && path === "/chat/send") {
      await requireConsent(env, thread);
      const body = await readJson(request, 12e3);''', '''    if (method === "POST" && path === "/chat/send") {
      const body = await readJson(request, 12e3);
      // 18.2: private support messages do not need the advertising consent; an advertising ORDER still does
      if (body && body.type === "order") await requireConsent(env, thread);''')
rep('''    if (path.indexOf("/chat/upload/") === 0) {
      await requireConsent(env, thread);
      const r = await chatUploadRoutes(''', '''    if (path.indexOf("/chat/upload/") === 0) {
      const r = await chatUploadRoutes(''')

# 4) admin routes: who is answering + roles
rep('''  if (path.indexOf("/admin/chat/") === 0) {
    await requireAdmin(request, env);
    const sub = path.slice("/admin/chat".length);''', '''  if (path.indexOf("/admin/chat/") === 0) {
    const dev = await requireAdmin(request, env);
    const sub = path.slice("/admin/chat".length);
    // 18.2 roles: "support" developers read / answer / archive; destructive or account-level actions are owner-only
    if (["/purge", "/delete-message", "/convert-order", "/export"].indexOf(sub) >= 0 || (sub === "/templates" && method === "POST")) requireRole(dev, "owner");''')
rep('''      return reply(await hub(env, "send", Object.assign({}, body, { side: "admin", origin })));''',
    '''      return reply(await hub(env, "send", Object.assign({}, body, { side: "admin", origin, dev: devPublic(dev), dev_id: dev && dev.id || "" })));''')
rep('''    if (method === "POST" && sub === "/thread") return reply(await hub(env, "thread_update", await readJson(request, 8e3)));''',
    '''    if (method === "POST" && sub === "/thread") {
      const tb = await readJson(request, 8e3);
      if (tb && tb.banned !== void 0) requireRole(dev, "owner");
      return reply(await hub(env, "thread_update", tb));
    }''')

# 5) ChatHub: thread keeps the auth id; the user never receives internal ids; admin view gets them
rep('''        name: cleanText(info && info.name, 100) || "\\u0645\\u0639\\u0644\\u0646",
        email: cleanText(info && info.email, 120),''', '''        name: cleanText(info && info.name, 100) || "\\u0645\\u0639\\u0644\\u0646",
        email: cleanText(info && info.email, 120),
        uid: cleanText(info && info.uid, 120),''')
rep('''      user_seen: t.user_seen,
      online: this.userOnline(t.id)
    };''', '''      user_seen: t.user_seen,
      user_id: t.uid || "",
      online: this.userOnline(t.id)
    };''')
rep('''  userThreadView(t) {
    return { id: t.id, status: t.status, last_seq: t.last_seq, unread: t.unread_user, banned: !!t.banned, created: t.created };
  }''', '''  userThreadView(t) {
    // 18.2: no internal id in anything the user receives
    return { status: t.status, last_seq: t.last_seq, unread: t.unread_user, banned: !!t.banned, created: t.created };
  }''')
rep('''    const t = await this.getThread(id, true, p);
    if (p.name && t.name !== cleanText(p.name, 100) && t.name === "\\u0645\\u0639\\u0644\\u0646") {''', '''    const t = await this.getThread(id, true, p);
    if (p.uid && !t.uid) {
      t.uid = cleanText(p.uid, 120);
      await this.s.put("t:" + id, t);
    }
    if (p.name && t.name !== cleanText(p.name, 100) && t.name === "\\u0645\\u0639\\u0644\\u0646") {''')
rep('''      if (q && (t.name || "").toLowerCase().indexOf(q) < 0 && (t.email || "").toLowerCase().indexOf(q) < 0 && (t.tags || []).join(" ").toLowerCase().indexOf(q) < 0) continue;''',
    '''      if (q && (t.name || "").toLowerCase().indexOf(q) < 0 && (t.email || "").toLowerCase().indexOf(q) < 0 && (t.tags || []).join(" ").toLowerCase().indexOf(q) < 0
        && String(t.id || "").toLowerCase().indexOf(q) < 0 && String(t.uid || "").toLowerCase().indexOf(q) < 0) continue;''')

# 6) messages carry the answering developer's PUBLIC identity; the internal developer id only for admins
rep('''    v.text = d.text || "";
    if (d.reply) v.reply = d.reply;''', '''    v.text = d.text || "";
    if (d.dev) v.dev = { name: cleanText(d.dev.name, 40), avatar: String(d.dev.avatar || "") };
    if (d.reply) v.reply = d.reply;''')
rep('''    const out = [];
    for (const m of rows) out.push(await this.viewMsg(m, p.origin));
    return { messages: out, thread: side === "admin" ? await this.threadView(t) : this.userThreadView(t), admin_online: this.adminOnline() };''', '''    const out = [];
    for (const m of rows) {
      const v = await this.viewMsg(m, p.origin);
      if (side === "admin" && m.dev_id) v.dev_id = m.dev_id;
      out.push(v);
    }
    return { messages: out, thread: side === "admin" ? await this.threadView(t) : this.userThreadView(t), admin_online: this.adminOnline() };''')
rep('''    const text = cleanText(p.text, CHAT_MAX_TEXT);
    const payload = {};
    if (text) payload.text = text;''', '''    const text = cleanText(p.text, CHAT_MAX_TEXT);
    const payload = {};
    if (text) payload.text = text;
    if (side === "admin" && p.dev && typeof p.dev === "object") payload.dev = { name: cleanText(p.dev.name, 40), avatar: String(p.dev.avatar || "").slice(0, 400) };''')
rep('''      deleted: false,
      qstate: type === "quote" ? "pending" : void 0
    };''', '''      deleted: false,
      qstate: type === "quote" ? "pending" : void 0,
      dev_id: side === "admin" ? cleanText(p.dev_id, 40) : void 0
    };''')
rep('''    if (side === "admin" && !this.userOnline(thread)) await this.notify("user", thread, "\\u0631\\u0633\\u0627\\u0644\\u0629 \\u0645\\u0646 \\u0625\\u062F\\u0627\\u0631\\u0629 Cigram", previewOf(type, payload.text));''',
    '''    if (side === "admin" && !this.userOnline(thread)) await this.notify("user", thread, payload.dev && payload.dev.name || "Cigram", previewOf(type, payload.text), { dev_name: payload.dev && payload.dev.name || "", ts: String(now) });''')
rep('''  async notify(side, thread, title, body) {
    const url = this.env.PUSH_WEBHOOK_URL;''', '''  async notify(side, thread, title, body, extra) {
    const url = this.env.PUSH_WEBHOOK_URL;''')
rep('''body: JSON.stringify({ kind: "chat", to: side, user_key: side === "user" ? thread : void 0, thread, title, body }) }''',
    '''body: JSON.stringify(Object.assign({ kind: "chat", to: side, user_key: side === "user" ? thread : void 0, thread, title, body }, extra || {})) }''')

# 7) last time a developer was connected (shown to the user as «آخر ظهور»)
rep('''  async op_presence(p) {
    const st = p.settings || null;
    const now = Date.now();
    let state = "offline";
    if (this.adminOnline()) state = "online";
    else if (st && !inWorkHours(st, now)) state = "off_hours";
    return { state };
  }''', '''  async op_presence(p) {
    const st = p.settings || null;
    const now = Date.now();
    let state = "offline";
    if (this.adminOnline()) state = "online";
    else if (st && !inWorkHours(st, now)) state = "off_hours";
    const last = this.adminOnline() ? now : (await this.s.get("admin_last_active")) || 0;
    return { state, last_active: last };
  }''')
rep('''      const others = this.socketsOf("a").filter((w) => w !== ws);
      if (!others.length) {''', '''      const others = this.socketsOf("a").filter((w) => w !== ws);
      try { await this.s.put("admin_last_active", Date.now()); } catch (e) { }
      if (!others.length) {''')

open(dst, "w", encoding="utf-8").write(s)
print("patched:", dst)
