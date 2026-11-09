  // ================================================================================================================
  // 19.0 — inserted into class ChatHub by apply_patch_19.py
  //   * per-thread serialisation (no lost / duplicated sequence numbers when two sends race)
  //   * permanent history: archive / restore only, identity erasure instead of deletion
  //   * targeted push with outbox, retry, deduplication
  //   * conversation backup (immutable segments in a PRIVATE bucket) and restore of missing records
  // ================================================================================================================
  async withThread(thread, fn) {
    if (!this._locks) this._locks = /* @__PURE__ */ new Map();
    const prev = this._locks.get(thread) || Promise.resolve();
    let release;
    const mine = new Promise((r) => { release = r; });
    const tail = prev.then(() => mine);
    this._locks.set(thread, tail);
    await prev;
    try {
      return await fn();
    } finally {
      release();
      if (this._locks.get(thread) === tail) this._locks.delete(thread);
    }
  }
  // a socket is "active" unless the app told us it went to the background ({"t":"fg","on":false})
  activeSockets(tag) {
    return this.socketsOf(tag).filter((w) => {
      try {
        const a = w.deserializeAttachment ? w.deserializeAttachment() : null;
        return !(a && a.fg === false);
      } catch (e) {
        return true;
      }
    });
  }
  adminActive() {
    return this.activeSockets("a").length > 0;
  }
  userActive(thread) {
    return this.activeSockets("u:" + thread).length > 0;
  }
  // ------------------------------------------------------------ devices (push tokens)
  async op_device_register(p) {
    const side = p.side === "admin" ? "admin" : "user";
    const owner = side === "admin" ? cleanText(p.owner, 40) || "owner" : cleanText(p.thread, 120);
    const token = String(p.token || "").trim();
    if (!owner) fail(400, "invalid_owner", "مستخدم غير صالح.");
    if (token.length < 20 || token.length > 4096 || /\s/.test(token)) fail(400, "invalid_token", "رمز الجهاز غير صالح.");
    const h = (await sha256Hex("fcm|" + token)).slice(0, 24);
    const idx = "devx:" + side + ":" + h;
    const prevOwner = await this.s.get(idx);
    if (prevOwner && prevOwner !== owner) await this.s.delete("dev:" + side + ":" + prevOwner + ":" + h);   // the phone changed account: the old account stops receiving its pushes
    const key = "dev:" + side + ":" + owner + ":" + h;
    const prev = await this.s.get(key);
    const now = Date.now();
    await this.s.put(key, { side, owner, h, token, app: cleanText(p.app, 40), v: int(p.v, 0, 1e9, 0), created: prev ? prev.created : now, seen: now });
    await this.s.put(idx, owner);
    const list = await this.s.list({ prefix: "dev:" + side + ":" + owner + ":" });
    if (list.size > 8) {
      const old = [...list.entries()].sort((a, b) => a[1].seen - b[1].seen).slice(0, list.size - 8);
      await this.s.delete(old.map((x) => x[0]));
      for (const [, d] of old) await this.s.delete("devx:" + side + ":" + d.h);
    }
    return { registered: true, devices: Math.min(list.size, 8), push: chat19Features(this.env).push_fcm };
  }
  async op_device_remove(p) {
    const side = p.side === "admin" ? "admin" : "user";
    const owner = side === "admin" ? cleanText(p.owner, 40) || "owner" : cleanText(p.thread, 120);
    const h = (await sha256Hex("fcm|" + String(p.token || "").trim())).slice(0, 24);
    await this.s.delete("dev:" + side + ":" + owner + ":" + h);
    const idx = "devx:" + side + ":" + h;
    if ((await this.s.get(idx)) === owner) await this.s.delete(idx);
    return { removed: true };
  }
  async devicesFor(side, owner) {
    const list = await this.s.list({ prefix: side === "admin" ? "dev:admin:" : "dev:user:" + owner + ":" });
    return [...list.values()];
  }
  async op_push_status() {
    const f = chat19Features(this.env);
    const adm = await this.s.list({ prefix: "dev:admin:" });
    const usr = await this.s.list({ prefix: "dev:user:" });
    const ob = await this.s.list({ prefix: "ob:" });
    const st = (await this.s.get("push_stats")) || {};
    const missing = [];
    if (!f.push_fcm && !f.push_webhook) missing.push("FCM_SERVICE_ACCOUNT (secret, the Firebase service-account JSON) or PUSH_WEBHOOK_URL");
    if (f.push_fcm && !String(this.env.FCM_PROJECT_ID || (fcmAccount(this.env) || {}).project_id || "")) missing.push("FCM_PROJECT_ID");
    return { features: f, devices: { admin: adm.size, user: usr.size }, queued: ob.size, stats: st, missing };
  }
  async bumpPush(kind, code) {
    const st = (await this.s.get("push_stats")) || { sent: 0, failed: 0, dead: 0, given_up: 0, no_devices: 0, no_channel: 0 };
    st[kind] = (st[kind] || 0) + 1;
    if (kind === "sent") st.last_ok = Date.now();
    if (code) {
      st.last_error = String(code).slice(0, 40);
      st.last_error_at = Date.now();
    }
    await this.s.put("push_stats", st);
  }
  // Pushes one chat message to the right side. Deduplicated by message id (a repeated call is a no-op), retried by the alarm.
  async notify(side, thread, m, title, body, extra) {
    if (!m || !m.id) return;
    const key = "ob:" + m.id + ":" + side;
    if (await this.s.get(key) || await this.s.get("pd:" + m.id + ":" + side)) return;
    const t = await this.s.get("t:" + thread);
    const ev = { kind: "chat", to: side, msg_id: m.id, seq: String(m.seq), ts: String(m.ts || Date.now()), title: cleanText(title, 80), body: cleanText(body, 140), collapse: side + ":" + thread };
    if (side === "admin") {
      if (t && t.name && ev.body.indexOf(t.name + ": ") === 0) ev.body = ev.body.slice(t.name.length + 2);   // the name travels in its own field
      ev.thread = thread;
      ev.unread = String(t ? t.unread_admin || 0 : 0);
      ev.name = cleanText(t && t.name, 60);
    } else {
      ev.unread = String(t ? t.unread_user || 0 : 0);
      if (extra && extra.dev_name) ev.dev_name = cleanText(extra.dev_name, 40);
    }
    const rec = { id: m.id, side, thread, ev, tries: 0, next: Date.now(), created: Date.now(), sent: [] };
    await this.s.put(key, rec);
    let timer;
    await Promise.race([this.deliverOutbox(key, rec), new Promise((r) => { timer = setTimeout(r, 2500); })]).catch(() => {
    }).finally(() => clearTimeout(timer));
    await this.scheduleAlarm(Date.now() + 15e3);
  }
  async deliverOutbox(key, rec) {
    const fresh = await this.s.get(key);
    if (!fresh) return;                              // delivered by the other path in the meantime
    rec = fresh;
    const owner = rec.side === "admin" ? "" : rec.thread;
    const devs = await this.devicesFor(rec.side, owner);
    const fcm = !!fcmAccount(this.env);
    const hook = this.env.PUSH_WEBHOOK_URL;
    let retry = false;
    let delivered = rec.sent.length > 0;
    if (!fcm && !hook) {
      await this.s.delete(key);
      await this.s.put("pd:" + rec.id + ":" + rec.side, { at: Date.now(), result: "no_channel" });
      await this.bumpPush("no_channel", "no_channel");
      return;
    }
    if (fcm) {
      if (!devs.length) await this.bumpPush("no_devices");
      for (const d of devs) {
        if (rec.sent.indexOf(d.h) >= 0) continue;
        const r = await fcmSend(this.env, d.token, rec.ev, rec.ev.collapse);
        if (r.ok) {
          rec.sent.push(d.h);
          delivered = true;
          await this.bumpPush("sent");
        } else if (r.dead) {
          await this.s.delete("dev:" + d.side + ":" + d.owner + ":" + d.h);
          await this.s.delete("devx:" + d.side + ":" + d.h);
          await this.bumpPush("dead", r.code);
        } else {
          if (r.retry) retry = true;
          await this.bumpPush("failed", r.code || r.skip);
        }
      }
    }
    if (hook && !rec.hook) {
      try {
        const h = { "content-type": "application/json" };
        if (this.env.PUSH_WEBHOOK_KEY) h.authorization = "Bearer " + this.env.PUSH_WEBHOOK_KEY;
        const r = await fetch(hook, { method: "POST", headers: h, body: JSON.stringify(Object.assign({ to: rec.side, user_key: rec.side === "user" ? rec.thread : void 0 }, rec.ev)) });
        if (r.ok) {
          rec.hook = true;
          delivered = true;
        } else if (r.status >= 500 || r.status === 429) retry = true;
      } catch (e) {
        retry = true;
      }
    }
    if (retry && rec.tries < PUSH_RETRY_MS.length && Date.now() - rec.created < PUSH_TTL_S * 1e3) {
      rec.next = Date.now() + PUSH_RETRY_MS[rec.tries];
      rec.tries++;
      await this.s.put(key, rec);
      await this.scheduleAlarm(rec.next);
      return;
    }
    await this.s.delete(key);
    await this.s.put("pd:" + rec.id + ":" + rec.side, { at: Date.now(), result: delivered ? "sent" : retry ? "given_up" : "no_devices" });
    if (retry) await this.bumpPush("given_up");
  }
  async scheduleAlarm(at) {
    try {
      const cur = await this.s.getAlarm();
      if (!cur || cur > at) await this.s.setAlarm(Math.max(at, Date.now() + 1e3));
    } catch (e) {
    }
  }
  // ------------------------------------------------------------ identity erasure (NOT history deletion)
  // "Forget me" / account removal: the conversation stays (archived, searchable by the owner); the person's name, e-mail and
  // account id are removed from the thread record and the user's own view starts empty (their floor moves to the last seq).
  async op_erase_identity(p) {
    const thread = cleanText(p.thread, 120);
    return this.withThread(thread, async () => {
      const t = await this.s.get("t:" + thread);
      if (!t) return { erased: false };
      const now = Date.now();
      t.name = "مستخدم محذوف";
      t.email = "";
      t.uid = "";
      t.user_floor = t.last_seq;
      t.unread_user = 0;
      t.erased_at = now;
      t.archived = true;
      await this.s.put("t:" + thread, t);
      for (const w of this.socketsOf("u:" + thread)) {
        try {
          w.close(1e3, "identity_erased");
        } catch (e) {
        }
      }
      this.push("a", { t: "thread", thread, status: t.status, banned: !!t.banned });
      return { erased: true, history_retained: true, thread_id_kept: true };
    });
  }
  // ------------------------------------------------------------ search over the message history
  async op_search(p) {
    const q = cleanText(p.q, 60).toLowerCase();
    if (q.length < 2) return { hits: [], scanned: 0, complete: true };
    const cap = int(p.scan, 100, 3e4, 8e3);
    const threads = [...(await this.s.list({ prefix: "t:" })).values()].sort((a, b) => b.last_ts - a.last_ts);
    const hits = [];
    let scanned = 0;
    let complete = true;
    outer: for (const t of threads) {
      if (p.thread && t.id !== p.thread) continue;
      const rows = await this.s.list({ prefix: "m:" + t.id + ":", reverse: true, limit: 2e3 });
      for (const m of rows.values()) {
        if (m.deleted) continue;
        if (scanned >= cap) {
          complete = false;
          break outer;
        }
        scanned++;
        const d = await decJson(this.env, t.id, m.enc);
        const txt = String(d.text || "");
        const at = txt.toLowerCase().indexOf(q);
        if (at < 0) continue;
        hits.push({ thread: t.id, name: t.name, seq: m.seq, from: m.from, ts: m.ts, archived: !!t.archived, snippet: txt.slice(Math.max(0, at - 30), at + 90).replace(/\s+/g, " ") });
        if (hits.length >= 60) {
          complete = false;
          break outer;
        }
      }
    }
    return { hits, scanned, complete };
  }
  // ------------------------------------------------------------ backups (immutable segments, private bucket only)
  async op_backup_run(p) {
    const b = backupBucket(this.env);
    if (!b) fail(503, "backup_not_configured", "أضف ربط R2 خاصاً باسم ADS_BUCKET أو CHAT_BACKUP_BUCKET (ليس الحاوية العامة movies).");
    const started = Date.now();
    const budget = int(p.max_messages, 100, 5e4, 6e3);
    const threads = [...(await this.s.list({ prefix: "t:" })).values()];
    let segs = 0, msgs = 0, lagging = 0, failed = 0;
    for (const t of threads) {
      if (p.thread && p.thread !== t.id) continue;
      let wm = await this.s.get("bk:" + t.id) || 0;
      while (wm < t.last_seq) {
        if (msgs >= budget) {
          lagging++;
          break;
        }
        const rows = await this.s.list({ prefix: "m:" + t.id + ":", start: "m:" + t.id + ":" + padSeq(wm + 1), limit: 500 });
        if (!rows.size) break;
        const arr = [...rows.values()];
        const first = arr[0].seq, last = arr[arr.length - 1].seq;
        const key = BACKUP_PREFIX + "msgs/" + t.id + "/" + padSeq(first) + "-" + padSeq(last) + ".json";
        const body = JSON.stringify({ v: 1, thread: t.id, from: first, to: last, messages: arr });
        try {
          let head = await b.head(key);
          if (!head) {                                         // immutable: an existing segment is never overwritten
            await b.put(key, body, { httpMetadata: { contentType: "application/json" } });
            head = await b.head(key);
          }
          if (!head || !(head.size > 0)) throw new Error("verify_failed");
        } catch (e) {
          failed++;
          break;
        }
        wm = last;
        await this.s.put("bk:" + t.id, wm);
        segs++;
        msgs += arr.length;
      }
    }
    // thread + attachment metadata snapshot (encrypted: the thread records hold names / e-mails), written only when it changed
    let snapshot = "";
    try {
      const media = [...(await this.s.list({ prefix: "media:" })).values()];
      const blob = await encJson(this.env, "_backup_meta", { v: 1, at: started, threads, media });
      const hash = (await sha256Hex(JSON.stringify(threads) + "|" + JSON.stringify(media))).slice(0, 12);
      const st0 = await this.s.get("bk_state") || {};
      if (st0.snapshot_hash !== hash) {
        const d = new Date(started).toISOString().replace(/[-:T]/g, "").slice(0, 10 + 2);
        snapshot = BACKUP_PREFIX + "_threads/" + d + "-" + hash + ".json";
        if (!await b.head(snapshot)) await b.put(snapshot, JSON.stringify({ v: 1, enc: blob }), { httpMetadata: { contentType: "application/json" } });
        const chk = await b.head(snapshot);
        if (!chk || !(chk.size > 0)) throw new Error("snapshot_verify_failed");
        st0.snapshot_hash = hash;
        st0.snapshot_key = snapshot;
      }
      st0.last_run = started;
      if (failed === 0) st0.last_ok = Date.now();
      st0.last_segments = segs;
      st0.last_messages = msgs;
      st0.lagging_threads = lagging;
      st0.failed = failed;
      st0.error = "";
      await this.s.put("bk_state", st0);
    } catch (e) {
      const st1 = await this.s.get("bk_state") || {};
      st1.last_run = started;
      st1.error = String(e && e.message || e).slice(0, 80);
      await this.s.put("bk_state", st1);
      failed++;
    }
    return { segments: segs, messages: msgs, lagging_threads: lagging, failed, snapshot: snapshot || void 0, ms: Date.now() - started };
  }
  async op_backup_status() {
    const st = await this.s.get("bk_state") || {};
    const threads = [...(await this.s.list({ prefix: "t:" })).values()];
    let behind = 0, unsaved = 0;
    for (const t of threads) {
      const wm = await this.s.get("bk:" + t.id) || 0;
      if (wm < t.last_seq) {
        behind++;
        unsaved += t.last_seq - wm;
      }
    }
    const b = backupBucket(this.env);
    return { configured: !!b, threads: threads.length, threads_behind: behind, messages_unsaved: unsaved, state: st };
  }
  // Verifies the backup layout against the live records: for every thread the saved ranges must be contiguous from 1 to the watermark.
  async op_backup_verify() {
    const b = backupBucket(this.env);
    if (!b) fail(503, "backup_not_configured", "لا يوجد R2 خاص للنسخ الاحتياطي.");
    const threads = [...(await this.s.list({ prefix: "t:" })).values()];
    const problems = [];
    let ok = 0;
    for (const t of threads) {
      const wm = await this.s.get("bk:" + t.id) || 0;
      if (!wm) {
        if (t.last_seq > 0) problems.push({ thread: t.id, issue: "never_backed_up" });
        continue;
      }
      const ranges = [];
      let cursor;
      do {
        const o = await b.list({ prefix: BACKUP_PREFIX + "msgs/" + t.id + "/", cursor });
        for (const x of o.objects || []) {
          const m = /\/(\d{10})-(\d{10})\.json$/.exec(x.key);
          if (m) ranges.push([parseInt(m[1], 10), parseInt(m[2], 10)]);
        }
        cursor = o.truncated ? o.cursor : void 0;
      } while (cursor);
      ranges.sort((a, c) => a[0] - c[0]);
      let next = 1, gap = 0;
      for (const [a, c] of ranges) {
        if (a > next) gap = next;
        next = Math.max(next, c + 1);
      }
      if (gap) problems.push({ thread: t.id, issue: "gap", at_seq: gap });
      else if (next - 1 < wm) problems.push({ thread: t.id, issue: "short", covered: next - 1, watermark: wm });
      else ok++;
    }
    return { ok: problems.length === 0, threads: threads.length, verified: ok, problems: problems.slice(0, 50) };
  }
  // Restores what the live store lacks (a lost / corrupted record), from the saved segments. NEVER overwrites an existing record.
  async op_backup_restore(p) {
    const b = backupBucket(this.env);
    if (!b) fail(503, "backup_not_configured", "لا يوجد R2 خاص للنسخ الاحتياطي.");
    let restoredMsgs = 0, restoredThreads = 0, skipped = 0;
    // 1) the newest thread snapshot restores threads that vanished
    const snaps = [];
    let cursor;
    do {
      const o = await b.list({ prefix: BACKUP_PREFIX + "_threads/", cursor });
      for (const x of o.objects || []) snaps.push(x.key);
      cursor = o.truncated ? o.cursor : void 0;
    } while (cursor);
    snaps.sort();
    const latest = snaps[snaps.length - 1];
    if (latest) {
      const obj = await b.get(latest);
      const j = obj ? JSON.parse(await obj.text()) : null;
      const meta = j && j.enc ? await decJson(this.env, "_backup_meta", j.enc) : null;
      for (const t of meta && meta.threads || []) {
        if (p.thread && p.thread !== t.id) continue;
        if (!await this.s.get("t:" + t.id)) {
          await this.s.put("t:" + t.id, t);
          restoredThreads++;
        }
      }
      for (const md of meta && meta.media || []) if (!await this.s.get("media:" + md.id) && (!p.thread || md.thread === p.thread)) await this.s.put("media:" + md.id, md);
    }
    // 2) every saved message the live store lacks
    const prefix = BACKUP_PREFIX + "msgs/" + (p.thread ? p.thread + "/" : "");
    cursor = void 0;
    do {
      const o = await b.list({ prefix, cursor });
      for (const x of o.objects || []) {
        const obj = await b.get(x.key);
        if (!obj) continue;
        const seg = JSON.parse(await obj.text());
        for (const m of seg.messages || []) {
          const k = "m:" + m.thread + ":" + padSeq(m.seq);
          if (await this.s.get(k)) {
            skipped++;
            continue;
          }
          await this.s.put(k, m);
          restoredMsgs++;
          if (m.client_id) await this.s.put("cid:" + m.thread + ":" + m.client_id, m.seq);
          const t = await this.s.get("t:" + m.thread);
          if (t && t.last_seq < m.seq) {
            t.last_seq = m.seq;
            await this.s.put("t:" + m.thread, t);
          }
        }
      }
      cursor = o.truncated ? o.cursor : void 0;
    } while (cursor);
    return { restored_messages: restoredMsgs, restored_threads: restoredThreads, already_present: skipped };
  }
  // ------------------------------------------------------------ housekeeping run by the alarm (never touches a conversation)
  async housekeeping19(now) {
    // 1) push outbox: retry what is due
    const ob = await this.s.list({ prefix: "ob:" });
    for (const [k, rec] of ob) {
      if (rec.next <= now) {
        try {
          await this.deliverOutbox(k, rec);
        } catch (e) {
        }
      }
    }
    // 2) deduplication markers older than 14 days (markers only: no message, no thread)
    const pd = await this.s.list({ prefix: "pd:", limit: 500 });
    const old = [];
    for (const [k, v] of pd) if (now - (v.at || 0) > 14 * DAY_MS) old.push(k);
    if (old.length) await this.s.delete(old.slice(0, 100));
    // 3) the backup, at most every 6 hours, only when a private bucket is bound
    if (backupBucket(this.env)) {
      const st = await this.s.get("bk_state") || {};
      if (now - (st.last_run || 0) > 6 * 36e5 || st.lagging_threads) {
        try {
          await this.op_backup_run({});
        } catch (e) {
        }
      }
    }
  }
  async rescheduleAlarm19(now) {
    let next = now + 6 * 36e5;
    try {
      const ob = await this.s.list({ prefix: "ob:" });
      for (const rec of ob.values()) if (rec.next < next) next = Math.max(rec.next, now + 1e4);
      const st = await this.s.get("bk_state");
      if (st && st.lagging_threads) next = Math.min(next, now + 6e4);
      await this.s.setAlarm(next);
    } catch (e) {
    }
  }
