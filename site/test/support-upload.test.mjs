// node --no-warnings test/support-upload.test.mjs
// رفع الوسائط من «الدعم والتواصل»: نفس بروتوكول التطبيق مقابل خادم وهمي يطابق قواعد cigram-ads-api
// (init ← part ← status ← complete ← send، قطع 1MB، فحص أول قطعة، حد 25MB، client_id يمنع التكرار).
const store = new Map();
globalThis.localStorage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: (k) => store.delete(k) };
globalThis.sessionStorage = globalThis.localStorage;
globalThis.location = { protocol: 'https:', hash: '', href: 'https://serviceh.online/' };
const el = () => ({ classList: { contains: () => false, add() {}, remove() {} }, addEventListener() {}, querySelector: () => null, querySelectorAll: () => [], style: {} });
globalThis.document = { documentElement: el(), addEventListener() {}, querySelector: () => null, querySelectorAll: () => [], createElement: el, body: el(), head: el(), hidden: false };
globalThis.addEventListener = () => {}; globalThis.window = globalThis; globalThis.matchMedia = () => ({ matches: false, addEventListener() {} });
Object.defineProperty(globalThis, 'navigator', { value: { userAgent: 'test' }, configurable: true });
store.set('cigram.auth.v1', JSON.stringify({ token: 'tok-1', user: { id: 'u1', name: 'علي' } }));
let pass = 0, fail = 0; const ok = (c, n) => { if (c) pass++; else { fail++; console.log('FAIL:', n); } };

const PART = 1024 * 1024;
const PNG = [137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13];
const MP4 = [0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109];
const PDF = [37, 80, 68, 70, 45, 49, 46, 52, 10, 37, 226, 227];

/** ملف بحجم محدد وأول بايتات معلومة (كائن Blob حقيقي). */
function makeFile(name, type, size, head) {
  const bytes = new Uint8Array(size);
  head.forEach((v, i) => { bytes[i] = v; });
  for (let i = head.length; i < size; i++) bytes[i] = (i * 31) & 255;
  return new File([bytes], name, { type });
}

// ------------------------------------------------------------------ خادم وهمي
const S = { media: new Map(), msgs: [], seq: 0, calls: [], failPart: new Map(), dropAfterPut: new Set(), failComplete: 0, expireSessions: false, partWire: [] };
const json = (status, body) => ({ ok: status < 400, status, json: async () => body });
globalThis.fetch = async (url, opts = {}) => {
  const u = new URL(String(url));
  const path = u.pathname, method = opts.method || 'GET';
  S.calls.push(method + ' ' + path + (u.searchParams.get('n') !== null ? '#' + u.searchParams.get('n') : ''));
  if (!/^Bearer tok-1$/.test((opts.headers || {}).Authorization || '')) return json(401, { ok: false, error: 'unauthorized' });
  if (method === 'POST' && path === '/chat/upload/init') {
    const b = JSON.parse(opts.body);
    if (!(b.size >= 1 && b.size <= 25 * 1024 * 1024)) return json(413, { ok: false, error: 'too_large' });
    const kind = ['image', 'video', 'voice', 'file'].includes(b.kind) ? b.kind : 'file';
    const id = 'fTest' + (S.media.size + 1) + 'abcdef';
    S.media.set(id, { id, kind, size: b.size, name: b.name, parts: Math.ceil(b.size / PART), mime: '', got: new Map(), complete: false, used: false, w: b.w, h: b.h, dur: b.dur });
    return json(200, { ok: true, media_id: id, part_size: PART, parts: Math.ceil(b.size / PART) });
  }
  const m = S.media.get(u.searchParams.get('id'));
  if (path.indexOf('/chat/upload/') === 0 && (!m || S.expireSessions)) return json(404, { ok: false, error: 'not_found' });
  if (method === 'PUT' && path === '/chat/upload/part') {
    const n = parseInt(u.searchParams.get('n'), 10);
    if (m.complete) return json(409, { ok: false, error: 'already_complete' });
    if (!(n >= 0 && n < m.parts)) return json(400, { ok: false, error: 'bad_part' });
    if ((opts.headers || {})['Content-Type'] !== 'application/octet-stream') return json(400, { ok: false, error: 'bad_type' });
    const left = S.failPart.get(n) || 0;
    if (left > 0) { S.failPart.set(n, left - 1); return json(503, { ok: false, error: 'busy' }); }
    const buf = new Uint8Array(await opts.body.arrayBuffer());
    const expect = n === m.parts - 1 ? m.size - n * PART : PART;
    if (buf.length !== expect) return json(400, { ok: false, error: 'bad_part_size' });
    if (n === 0) {
      const eq = (off, arr) => arr.every((v, i) => buf[off + i] === v);
      const t = eq(0, PNG.slice(0, 8)) ? 'image' : eq(4, [102, 116, 121, 112]) ? 'video' : eq(0, [37, 80, 68, 70]) ? 'file' : null;
      if (!t) return json(415, { ok: false, error: 'unsupported_type' });
      if (m.kind !== 'file' && t !== m.kind) return json(415, { ok: false, error: 'unsupported_type' });
      m.mime = t;
    }
    S.partWire.push(n);
    if (!S.dropAfterPut.has(n)) m.got.set(n, buf.length);        // «ضاعت» القطعة: الخادم قال نعم ولم يحفظها
    return json(200, { ok: true, n });
  }
  if (method === 'GET' && path === '/chat/upload/status') return json(200, { ok: true, parts: m.parts, have: [...m.got.keys()].sort((a, b) => a - b), complete: m.complete });
  if (method === 'POST' && path === '/chat/upload/complete') {
    if (S.failComplete > 0) { S.failComplete--; return json(502, { ok: false, error: 'bad_gateway' }); }
    for (let i = 0; i < m.parts; i++) if (!m.got.has(i)) return json(409, { ok: false, error: 'missing_parts', missing: i });
    m.complete = true;
    return json(200, { ok: true, media_id: m.id });
  }
  if (method === 'POST' && path === '/chat/send') {
    const b = JSON.parse(opts.body);
    const dup = S.msgs.find((x) => x.client_id === b.client_id);
    if (dup) return json(200, { ok: true, message: dup, duplicate: true });
    const mm = S.media.get(b.media_id);
    if (!mm || !mm.complete) return json(400, { ok: false, error: 'media_not_ready' });
    if (mm.used) return json(400, { ok: false, error: 'media_used' });
    mm.used = true;
    const msg = { seq: ++S.seq, from: 'user', type: b.type, client_id: b.client_id, ts: 1700000000000 + S.seq, media: { id: mm.id, kind: mm.kind, size: mm.size, name: mm.name, w: mm.w, h: mm.h, dur: mm.dur } };
    S.msgs.push(msg);
    return json(200, { ok: true, message: msg });
  }
  return json(404, { ok: false, error: 'nf' });
};

const sp = await import('../js/support.js');
const reset = () => { S.media.clear(); S.msgs.length = 0; S.seq = 0; S.calls.length = 0; S.failPart.clear(); S.dropAfterPut.clear(); S.failComplete = 0; S.expireSessions = false; S.partWire.length = 0; };

// ---- النوع والحد قبل أي اتصال
ok(sp.kindOf({ type: 'image/png' }) === 'image' && sp.kindOf({ type: 'video/mp4' }) === 'video' && sp.kindOf({ type: 'application/pdf' }) === 'file' && sp.kindOf({ type: '' }) === 'file', 'kind comes from the mime type');
ok(sp.check({ size: 0 }) === 'empty' && sp.check({ size: 25 * 1024 * 1024 + 1 }) === 'too_large' && sp.check({ size: 25 * 1024 * 1024 }) === '' && sp.check({ size: 1 }) === '', 'size limits equal the server (1 byte .. 25MB)');
ok(sp.sizeLabel(512) === '1 KB' && sp.sizeLabel(2048) === '2 KB' && sp.sizeLabel(1572864) === '1.5 MB' && sp.sizeLabel(30 * 1048576) === '30 MB', 'size label');
reset();
let r = await sp.run(sp.createUpload({ name: 'big.mp4', type: 'video/mp4', size: 26 * 1024 * 1024 }));
ok(!r.ok && r.reason === 'too_large' && S.calls.length === 0, 'an oversized file is refused locally, no request is made');
r = await sp.run(sp.createUpload({ name: 'e.png', type: 'image/png', size: 0 }));
ok(!r.ok && r.reason === 'empty' && S.calls.length === 0, 'an empty file is refused locally');

// ---- صورة صغيرة: قطعة واحدة، ترتيب الطلبات كما في التطبيق
reset();
const prog = [];
r = await sp.run(sp.createUpload(makeFile('صورة.png', 'image/png', 4096, PNG)), { onProgress: (p) => prog.push(p) });
ok(r.ok && r.messages.length === 1, 'a small image is sent');
ok(S.calls.join(',') === 'POST /chat/upload/init,PUT /chat/upload/part#0,POST /chat/upload/complete,POST /chat/send', 'init → part → complete → send');
ok(r.messages[0].mine && r.messages[0].kind === 'صورة مغلقة' && r.messages[0].name === 'صورة.png' && r.messages[0].size === 4096, 'it shows as a closed image with its name and size');
ok(prog.at(-1) === 100 && prog.every((p, i) => i === 0 || p >= prog[i - 1]), 'progress only goes up and ends at 100');

// ---- فيديو بعدة قطع: كل قطعة بحجمها الصحيح، التقدّم يتزايد
reset();
const vid = makeFile('clip.mp4', 'video/mp4', 2 * PART + 12345, MP4);
const prog2 = [];
r = await sp.run(sp.createUpload(vid), { onProgress: (p) => prog2.push(p) });
ok(r.ok && S.partWire.join(',') === '0,1,2', 'a 2MB+ video goes as 3 parts, in order');
ok(S.media.get('fTest1abcdef').got.get(2) === 12345, 'the last part carries exactly the remainder');
ok(prog2.some((p) => p > 0 && p < 100) && prog2.at(-1) === 100, 'progress moves in between');
ok(r.messages[0].kind === 'فيديو مغلق', 'a video shows as a closed video');

// ---- ملف PDF: نوع file
reset();
r = await sp.run(sp.createUpload(makeFile('عرض.pdf', 'application/pdf', 9000, PDF)));
ok(r.ok && r.messages[0].kind === 'ملف' && r.messages[0].name === 'عرض.pdf', 'a PDF is sent as a file');

// ---- قطعة تفشل مؤقتاً: تُعاد تلقائياً (3 محاولات)
reset();
S.failPart.set(1, 2);
r = await sp.run(sp.createUpload(makeFile('a.mp4', 'video/mp4', 2 * PART, MP4)));
ok(r.ok && S.calls.filter((c) => c === 'PUT /chat/upload/part#1').length === 3, 'a busy part is retried and then goes through');

// ---- قطعة تفشل دائماً: يفشل الرفع، ثم «إعادة المحاولة» تكمل القطع الناقصة فقط
reset();
S.failPart.set(2, 99);
const up = sp.createUpload(makeFile('b.mp4', 'video/mp4', 3 * PART, MP4));
r = await sp.run(up);
ok(!r.ok && r.reason === 'busy' && up.id && !up.complete, 'the upload stops with a reason and keeps its session');
ok(S.partWire.join(',') === '0,1', 'parts 0 and 1 arrived before the failure');
S.failPart.clear(); S.partWire.length = 0; S.calls.length = 0;
r = await sp.run(up);
ok(r.ok && S.partWire.join(',') === '2', 'retry sends ONLY the missing part');
ok(S.calls[0] === 'GET /chat/upload/status' && S.calls.filter((c) => c === 'POST /chat/upload/init').length === 0, 'retry asks status, never re-inits');
ok(S.msgs.length === 1, 'one message in the end');

// ---- الخادم قال نعم ولم يحفظ قطعة: complete يرجع missing_parts → تُعاد تلك القطعة مرة واحدة
reset();
S.dropAfterPut.add(1);
const lost = sp.createUpload(makeFile('c.mp4', 'video/mp4', 2 * PART, MP4));
// القطعة تُحفظ في المحاولة الثانية فقط
const origPut = S.dropAfterPut;
let seen = 0;
S.dropAfterPut = { has: (n) => (n === 1 && ++seen === 1) };
r = await sp.run(lost);
S.dropAfterPut = origPut;
ok(r.ok && S.partWire.filter((n) => n === 1).length === 2, 'a part the server lost is sent again after «missing_parts»');
ok(S.calls.filter((c) => c === 'POST /chat/upload/complete').length === 2, 'complete is asked twice (before and after)');

// ---- complete ينقطع: إعادة المحاولة لا ترفع شيئاً من جديد
reset();
S.failComplete = 1;
const upc = sp.createUpload(makeFile('d.png', 'image/png', 5000, PNG));
r = await sp.run(upc);
ok(!r.ok && !upc.complete, 'complete failing leaves the upload resumable');
S.partWire.length = 0;
r = await sp.run(upc);
ok(r.ok && S.partWire.length === 0 && S.msgs.length === 1, 'retry only completes and sends (no part re-sent)');

// ---- الإرسال نفسه يفشل بعد اكتمال الرفع: إعادة المحاولة لا تكرّر الرسالة ولا الرفع
reset();
const upd = sp.createUpload(makeFile('e.png', 'image/png', 5000, PNG));
let sendFails = 1;
const realFetch = globalThis.fetch;
globalThis.fetch = async (url, o) => (sendFails > 0 && String(url).endsWith('/chat/send') ? (sendFails--, json(500, { ok: false, error: 'boom' })) : realFetch(url, o));
r = await sp.run(upd);
ok(!r.ok && upd.complete && S.msgs.length === 0, 'a failed send keeps the finished upload');
S.partWire.length = 0;
r = await sp.run(upd);
ok(r.ok && S.msgs.length === 1 && S.partWire.length === 0, 'retry only sends the message');
const again = await sp.run(upd);
ok(again.ok && S.msgs.length === 1 && S.media.size === 1, 'sending the same upload twice never duplicates (same client_id)');
globalThis.fetch = realFetch;

// ---- جلسة الرفع انتهت على الخادم: جلسة جديدة تلقائياً
reset();
S.failPart.set(1, 99);
const upe = sp.createUpload(makeFile('f.mp4', 'video/mp4', 2 * PART, MP4));
await sp.run(upe);
const firstId = upe.id;
S.failPart.clear();
S.expireSessions = true;                       // status → 404
const realFetch2 = globalThis.fetch;
globalThis.fetch = async (url, o) => { if (new URL(String(url)).pathname === '/chat/upload/init') S.expireSessions = false; return realFetch2(url, o); };
r = await sp.run(upe);
globalThis.fetch = realFetch2;
ok(r.ok && upe.id && upe.id !== firstId, 'an expired server session starts a fresh one and still finishes');

// ---- نوع غير مدعوم (محتوى لا يطابق): سبب واضح بلا إعادة محاولة
reset();
r = await sp.run(sp.createUpload(makeFile('fake.png', 'image/png', 3000, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12])));
ok(!r.ok && r.reason === 'unsupported_type' && S.calls.filter((c) => c.startsWith('PUT')).length === 1, 'a file whose bytes are not an image is rejected once, not retried');

// ---- الإلغاء
reset();
const ac = new AbortController(); ac.abort();
r = await sp.run(sp.createUpload(makeFile('g.png', 'image/png', 3000, PNG)), { signal: ac.signal });
ok(!r.ok && r.reason === 'cancelled' && !S.calls.some((c) => c.startsWith('POST /chat/send')), 'a cancelled upload sends nothing');

// ---- إلغاء أثناء الرفع: يتوقف عند القطعة التالية ولا يُرسل شيء
reset();
const ac2 = new AbortController();
const upx = sp.createUpload(makeFile('i.mp4', 'video/mp4', 3 * PART, MP4));
r = await sp.run(upx, { signal: ac2.signal, onProgress: (p) => { if (p > 0) ac2.abort(); } });
ok(!r.ok && r.reason === 'cancelled' && S.partWire.length === 1 && S.msgs.length === 0, 'cancelling mid-upload stops before the next part and sends nothing');

// ---- بلا اتصال: لا استثناء، سبب network
reset();
const realFetch3 = globalThis.fetch;
globalThis.fetch = async () => { throw new TypeError('Failed to fetch'); };
r = await sp.run(sp.createUpload(makeFile('h.png', 'image/png', 3000, PNG)));
globalThis.fetch = realFetch3;
ok(!r.ok && r.reason !== undefined, 'no connection returns a result instead of throwing');

// ---- رسائل الخادم القديمة (نص) ما زالت تعمل
const t = sp.normalize([{ seq: 2, from: 'admin', type: 'text', text: 'أهلاً', ts: 5 }, { seq: 1, from: 'user', type: 'file', media: { name: 'x.zip', size: 99 }, ts: 4 }]);
ok(t[0].seq === 1 && t[0].kind === 'ملف' && t[0].name === 'x.zip' && t[1].text === 'أهلاً' && t[1].name === '' && !t[1].mine, 'normalize keeps text messages and adds the file name');

console.log(`site support-upload tests: passed=${pass} failed=${fail}`);
process.exit(fail ? 1 : 0);
