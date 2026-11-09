#!/usr/bin/env python3
"""19.0 — permanent support history, targeted push, backups, race-free sequencing: second-stage patch for cigram-ads-api.

Usage:  python3 apply_patch_19.py <worker.js produced by apply_patch.py (18.x)> <output worker.js>
Every anchor must match exactly once, otherwise the script stops and changes nothing (the code it patches moved).
Not deployed by the delivery: run it, review the diff, test with `wrangler dev`, then deploy yourself (DEPLOY.md §9).

What it changes (see docs/backend/CHAT-RETENTION-POLICY.md and DEPLOY.md §9):
  * /admin/chat/purge, /admin/chat/delete-message, the CHAT_RETENTION_DAYS alarm and the advertiser-deletion purge can no longer
    delete a conversation or a message. Archiving only flips a flag. "Forget me" / account removal erases the IDENTITY (name, e-mail,
    account id) and empties the user's own view; the history stays (owner-readable, searchable).
  * every operation on one thread is serialised (two sends racing used to be able to write the same sequence number).
  * unread counters follow `upto` exactly; the admin list has an `archived=all` mode, per-filter counts and a message search.
  * targeted push (FCM HTTP v1, optional webhook) with device registry, outbox + retry + deduplication by message id.
  * conversation backups in a PRIVATE bucket (immutable segments), verification and non-overwriting restore.
  * session check falls back to a read-only look at the auth database when the auth Worker cannot be asked (optional AUTH_DB binding).
"""
import os
import sys

src, dst = sys.argv[1], sys.argv[2]
here = os.path.dirname(os.path.abspath(__file__))
s = open(src, encoding="utf-8").read()


def rep(old, new, count=1):
    global s
    n = s.count(old)
    if n != count:
        raise SystemExit("anchor found %d times (expected %d): %r" % (n, count, old[:100]))
    s = s.replace(old, new)


def cut(start, end, new):
    """Replace everything from the line `start` up to (not including) the line `end`."""
    global s
    if s.count(start) != 1 or s.count(end) != 1:
        raise SystemExit("cut anchors: start x%d end x%d: %r .. %r" % (s.count(start), s.count(end), start[:60], end[:60]))
    a = s.index(start)
    b = s.index(end)
    if b < a:
        raise SystemExit("cut anchors out of order: %r" % start[:60])
    s = s[:a] + new + s[b:]


hub_methods = open(os.path.join(here, "chat19_hub_methods.js"), encoding="utf-8").read()
functions = open(os.path.join(here, "chat19_functions.js"), encoding="utf-8").read()

PERM_MSG = "\\u0645\\u0644\\u0641 \\u0627\\u0644\\u0645\\u062D\\u0627\\u062F\\u062B\\u0629 \\u062F\\u0627\\u0626\\u0645 \\u0648\\u0644\\u0627 \\u064A\\u064F\\u062D\\u0630\\u0641. \\u064A\\u0645\\u0643\\u0646 \\u0623\\u0631\\u0634\\u0641\\u062A\\u0647\\u0627 \\u0641\\u0642\\u0637."

# ---------------------------------------------------------------- 1) permanent history
cut("  async op_delete_msg(p) {\n", "  async op_quote_respond(p) {\n",
    '''  async op_delete_msg(p) {
    // 19.0: a message is never deleted (accidental deletion of history is not possible from any app)
    fail(409, "history_is_permanent", "%s");
  }
''' % PERM_MSG)
cut("  async op_purge(p) {\n", "  async op_presence(p) {\n", hub_methods.replace("PLACEHOLDER", "") + '''  async op_purge(p) {
    // 19.0: a conversation is never purged (retention alarm, advertiser deletion and the admin route all end here)
    fail(409, "history_is_permanent", "%s");
  }
''' % PERM_MSG)

# the retention alarm: orphan uploads (never sent) are still cleaned; conversations are not
rep('''    const days = parseInt(this.env.CHAT_RETENTION_DAYS || "0", 10);
    if (days > 0) {
      const all = await this.s.list({ prefix: "t:" });
      for (const t of all.values()) if (t.archived && now - t.last_ts > days * DAY_MS) await this.op_purge({ thread: t.id });
    }
    try {
      await this.s.setAlarm(now + 6 * 36e5);
    } catch (e) {
    }
  }
};''', '''    // 19.0: CHAT_RETENTION_DAYS is ignored on purpose: conversations are permanent (see CHAT-RETENTION-POLICY.md)
    await this.housekeeping19(now);
    await this.rescheduleAlarm19(now);
  }
};''')

rep('''async function purgeChatForUser(env, userKey) {
  if (!env.CHAT_HUB) return;
  try {
    await hub(env, "purge", { thread: userKey });
  } catch (e) {
  }
}''', '''async function purgeChatForUser(env, userKey) {
  // 19.0: removing an advertiser / account erases the IDENTITY on the thread, never the conversation
  if (!env.CHAT_HUB) return;
  try {
    await hub(env, "erase_identity", { thread: userKey, reason: "account_removed" });
  } catch (e) {
  }
}''')

rep('''      await purgeChatForUser(env, thread);
      return reply({ ok: true });''', '''      await purgeChatForUser(env, thread);
      return reply({ ok: true, identity_erased: true, history_retained: true });''')

rep('''    if (method === "POST" && sub === "/delete-message") return reply(await hub(env, "delete_msg", await readJson(request, 1e3)));
    if (method === "POST" && sub === "/purge") {
      const body = await readJson(request, 1e3);
      return reply(await hub(env, "purge", { thread: body.thread }));
    }''', '''    if (method === "POST" && sub === "/delete-message") fail(409, "history_is_permanent", "%s");
    if (method === "POST" && sub === "/purge") fail(409, "history_is_permanent", "%s");
    if (method === "GET" && sub === "/search") return reply(await hub(env, "search", { q: q("q"), thread: q("thread") }));
    if (method === "GET" && sub === "/backup") return reply(await hub(env, "backup_status", {}));
    if (method === "POST" && sub === "/backup") return reply(await hub(env, "backup_run", Object.assign({}, await readJson(request, 1e3))));
    if (method === "GET" && sub === "/backup/verify") return reply(await hub(env, "backup_verify", {}));
    if (method === "POST" && sub === "/restore") return reply(await hub(env, "backup_restore", Object.assign({}, await readJson(request, 1e3))));
    if (method === "GET" && sub === "/push-status") return reply(await hub(env, "push_status", {}));
    if (method === "POST" && sub === "/device") {
      const body = await readJson(request, 6e3);
      return reply(await hub(env, "device_register", { side: "admin", owner: dev && dev.id || "owner", token: body.token, app: body.app, v: body.v }));
    }
    if (method === "POST" && sub === "/device/remove") {
      const body = await readJson(request, 6e3);
      return reply(await hub(env, "device_remove", { side: "admin", owner: dev && dev.id || "owner", token: body.token }));
    }''' % (PERM_MSG, PERM_MSG))

rep('''    if (["/purge", "/delete-message", "/convert-order", "/export"].indexOf(sub) >= 0 || (sub === "/templates" && method === "POST")) requireRole(dev, "owner");''',
    '''    if (["/purge", "/delete-message", "/convert-order", "/export", "/restore", "/backup/verify"].indexOf(sub) >= 0 || (sub === "/backup" && method === "POST") || (sub === "/templates" && method === "POST")) requireRole(dev, "owner");''')

# user device routes
rep('''    if (path.indexOf("/chat/upload/") === 0) {
      const r = await chatUploadRoutes(''', '''    if (method === "POST" && path === "/chat/device") {
      const body = await readJson(request, 6e3);
      return reply(await hub(env, "device_register", { side: "user", thread, token: body.token, app: body.app, v: body.v }));
    }
    if (method === "POST" && path === "/chat/device/remove") {
      const body = await readJson(request, 6e3);
      return reply(await hub(env, "device_remove", { side: "user", thread, token: body.token }));
    }
    if (path.indexOf("/chat/upload/") === 0) {
      const r = await chatUploadRoutes(''')

# presence: feature flags so the apps can tell what this Worker can do
rep('''    pr.developer = { name: cleanText(env.DEV_NAME, 40)''', '''    pr.features = chat19Features(env);
    pr.developer = { name: cleanText(env.DEV_NAME, 40)''')

# ---------------------------------------------------------------- 2) per-thread serialisation
for op in ["op_send", "op_read", "op_send_system", "op_thread_update", "op_quote_respond", "op_messages"]:
    rep("  async %s(p) {\n" % op, "  async %s(p) {\n    return this.withThread(cleanText(p.thread, 120), () => this.%s_locked(p));\n  }\n  async %s_locked(p) {\n" % (op, op, op))

# op_quote_respond already holds the thread lock: it must call the unlocked body of op_send_system
rep("    const sys = await this.op_send_system({", "    const sys = await this.op_send_system_locked({")
rep("  async op_thread_open(p) {\n", "  async op_thread_open(p) {\n    return this.withThread(cleanText(p.user_key, 120), () => this.op_thread_open_locked(p));\n  }\n  async op_thread_open_locked(p) {\n")

# the user's own view starts after the floor set by an identity erasure
rep('''    const side = p.side === "admin" ? "admin" : "user";
    const limit = int(p.limit, 1, 100, 40);''', '''    const side = p.side === "admin" ? "admin" : "user";
    const floor = side === "user" ? t.user_floor || 0 : 0;
    const limit = int(p.limit, 1, 100, 40);''')
rep('''      const all = await this.s.list({ prefix: "m:" + thread + ":", start: "m:" + thread + ":" + padSeq(after + 1), limit });''',
    '''      const all = await this.s.list({ prefix: "m:" + thread + ":", start: "m:" + thread + ":" + padSeq(Math.max(after, floor) + 1), limit });''')
rep('''      const opts = { prefix: "m:" + thread + ":", reverse: true, limit };
      if (before) opts.end = "m:" + thread + ":" + padSeq(before);''', '''      const opts = { prefix: "m:" + thread + ":", reverse: true, limit };
      if (floor) opts.start = "m:" + thread + ":" + padSeq(floor + 1);
      if (before) opts.end = "m:" + thread + ":" + padSeq(before);''')

# the account id travels with every user send (an erased / new thread is linked again at once, not only by /chat/thread)
rep('''adv_id: adv && adv.id, client_id: body.client_id,''', '''adv_id: adv && adv.id, uid: user.id, client_id: body.client_id,''')
rep('''    const t = await this.getThread(thread, side === "user", p);
    if (!t) fail(404,''', '''    const t = await this.getThread(thread, side === "user", p);
    if (t && side === "user" && p.uid && !t.uid) t.uid = cleanText(p.uid, 120);
    if (!t) fail(404,''')

# ---------------------------------------------------------------- 3) send: last type, alarm, push
rep('''    t.last_ts = now;
    t.last_from = side;
    t.prev_enc = await encJson(this.env, thread, { text: previewOf(type, payload.text) });''', '''    t.last_ts = now;
    t.last_from = side;
    t.last_type = type;
    t.prev_enc = await encJson(this.env, thread, { text: previewOf(type, payload.text) });''')
rep('''    await this.s.put("t:" + thread, t);
    const tv = await this.threadView(t);''', '''    await this.s.put("t:" + thread, t);
    await this.ensureAlarm();
    const tv = await this.threadView(t);''')
rep('''if (side === "user" && !this.adminOnline()) await this.notify("admin", thread, ''', '''if (side === "user" && !this.adminActive()) await this.notify("admin", thread, m, ''')
rep('''if (side === "admin" && !this.userOnline(thread)) await this.notify("user", thread, ''', '''if (side === "admin" && !this.userActive(thread)) await this.notify("user", thread, m, ''')
# the old webhook-only notify() is replaced by the one in the inserted block
cut("  async notify(side, thread, title, body, extra) {\n", "  // ------------------------------------------------------------ threads\n", "")
rep('''    } else if (m.t === "ping") {
      try {
        ws.send(JSON.stringify({ t: "pong", server_time: Date.now() }));''', '''    } else if (m.t === "fg") {
      try {
        if (ws.serializeAttachment) ws.serializeAttachment(Object.assign({}, a, { fg: !!m.on }));
      } catch (e) {
      }
    } else if (m.t === "ping") {
      try {
        ws.send(JSON.stringify({ t: "pong", server_time: Date.now() }));''')

# ---------------------------------------------------------------- 4) read: exact unread
rep('''    if (side === "admin") {
      t.unread_admin = 0;
      t.admin_seen = now;
    } else {
      t.unread_user = 0;
      t.user_seen = now;
    }
    await this.s.put("t:" + thread, t);
    this.emit(thread, { t: "read", thread, side, upto, ts: now }, [other]);''', '''    // 19.0: the counter follows `upto` exactly (a read marker below the newest message leaves the newer ones unread)
    let remaining = 0;
    if (upto < t.last_seq) for (const m of rows.values()) if (m.from === other && !m.read && !m.deleted && m.seq > upto) remaining++;
    if (side === "admin") {
      t.unread_admin = remaining;
      t.admin_seen = now;
    } else {
      t.unread_user = remaining;
      t.user_seen = now;
    }
    await this.s.put("t:" + thread, t);
    this.emit(thread, { t: "read", thread, side, upto, ts: now, unread: remaining }, [other]);''')

# ---------------------------------------------------------------- 5) admin list: archived=all, counts, last type
rep('''      if (p.archived ? !t.archived : t.archived) continue;''', '''      if (p.archived !== "all" && (p.archived ? !t.archived : t.archived)) continue;''')
rep('''    for (const t of all.values()) if (!t.archived) totalUnread += t.unread_admin || 0;
    for (const t of out.slice(0, lim)) views.push(await this.threadView(t));
    return { threads: views, unread_total: totalUnread, admin_online: this.adminOnline() };''', '''    const counts = { all: 0, unread: 0, open: 0, pending: 0, archived: 0 };
    for (const t of all.values()) {
      if (t.archived) {
        counts.archived++;
        continue;
      }
      totalUnread += t.unread_admin || 0;
      counts.all++;
      if (t.unread_admin) counts.unread++;
      if (t.status === "open") counts.open++;
      if (t.status === "pending") counts.pending++;
    }
    for (const t of out.slice(0, lim)) views.push(await this.threadView(t));
    return { threads: views, unread_total: totalUnread, counts, admin_online: this.adminOnline() };''')
rep('''      last_from: t.last_from,
      preview: prev,''', '''      last_from: t.last_from,
      last_type: t.last_type || "",
      erased: !!t.erased_at,
      preview: prev,''')
rep('''archived: q("archived") === "1", limit: q("limit") }));''', '''archived: q("archived") === "all" ? "all" : q("archived") === "1", limit: q("limit") }));''')

# ---------------------------------------------------------------- 6) session check: read-only fallback to the auth database
rep('''  if (!user) {
    const grace = verdict !== "invalid" && hit && t0 - hit.at < 30 * 6e4;''', '''  if (!user && verdict !== "invalid" && env.AUTH_DB) {
    // 19.0: the auth Worker could not be asked (service binding / URL problem): look at the session read-only in D1
    const d1 = await verifyViaD1(env, tok);
    if (d1 && d1.id) {
      user = pickUser(d1);
      verdict = "";
    } else if (d1 && d1.invalid) verdict = "invalid";
  }
  if (!user) {
    const grace = verdict !== "invalid" && hit && t0 - hit.at < 30 * 6e4;''')

# ---------------------------------------------------------------- 7) standalone helpers before the export
rep('''export {
  AdsCore,
  ChatHub,''', functions + '''export {
  AdsCore,
  ChatHub,''')

open(dst, "w", encoding="utf-8").write(s)
print("19.0 patch applied: %d bytes" % len(s.encode("utf-8")))
