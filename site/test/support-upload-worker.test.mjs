// node --no-warnings test/support-upload-worker.test.mjs
// رفع الوسائط من الموقع مقابل كود cigram-ads-api الحقيقي (لا خادماً وهمياً): ChatHub بذاكرة، R2 بذاكرة، JWT حقيقي.
// يتحقق أن بروتوكول الموقع (init ← part ← complete ← send) يطابق الخادم، وأن البايتات تعود كما رُفعت، وأن المتصفح مسموح له بـ PUT.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { webcrypto } from 'node:crypto';

const store = new Map();
globalThis.localStorage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: (k) => store.delete(k) };
globalThis.sessionStorage = globalThis.localStorage;
globalThis.location = { protocol: 'https:', hash: '', href: 'https://serviceh.online/' };
const el = () => ({ classList: { contains: () => false, add() {}, remove() {} }, addEventListener() {}, querySelector: () => null, querySelectorAll: () => [], style: {} });
globalThis.document = { documentElement: el(), addEventListener() {}, querySelector: () => null, querySelectorAll: () => [], createElement: el, body: el(), head: el(), hidden: false };
globalThis.addEventListener = () => {}; globalThis.window = globalThis; globalThis.matchMedia = () => ({ matches: false, addEventListener() {} });
Object.defineProperty(globalThis, 'navigator', { value: { userAgent: 'test' }, configurable: true });

// ------------------------------------------------------------------ الخادم الحقيقي في الذاكرة
const src = fs.readFileSync(new URL('../../admin/docs/backend/deploy/cigram-ads-api/worker.js', import.meta.url), 'utf8');
const mod = await import('data:text/javascript;base64,' + Buffer.from(src).toString('base64'));
const worker = mod.default;

class MemStorage {
  constructor() { this.m = new Map(); this.alarm = null; }
  async get(k) { return this.m.has(k) ? structuredClone(this.m.get(k)) : undefined; }
  async put(k, v) { await Promise.resolve(); this.m.set(k, structuredClone(v)); }
  async delete(k) { if (Array.isArray(k)) { for (const x of k) this.m.delete(x); return; } this.m.delete(k); }
  async list(o = {}) {
    let keys = [...this.m.keys()].filter((k) => !o.prefix || k.startsWith(o.prefix)).sort();
    if (o.start) keys = keys.filter((k) => k >= o.start);
    if (o.end) keys = keys.filter((k) => k < o.end);
    if (o.reverse) keys.reverse();
    if (o.limit) keys = keys.slice(0, o.limit);
    return new Map(keys.map((k) => [k, structuredClone(this.m.get(k))]));
  }
  async getAlarm() { return this.alarm; }
  async setAlarm(t) { this.alarm = t; }
}
/** R2 يحفظ البايتات كما هي (لا نصوصاً). */
function makeR2() {
  const o = new Map();
  return {
    async head(k) { return o.has(k) ? { size: o.get(k).length } : null; },
    async put(k, v) { o.set(k, new Uint8Array(v instanceof ArrayBuffer ? v : v.buffer.slice(v.byteOffset, v.byteOffset + v.byteLength))); },
    async get(k) { return o.has(k) ? { arrayBuffer: async () => o.get(k).slice().buffer } : null; },
    async delete(k) { o.delete(k); },
    async list() { return { objects: [...o.keys()].map((key) => ({ key })), truncated: false }; },
  };
}
const env = {
  CHAT_KEY: Buffer.from(webcrypto.getRandomValues(new Uint8Array(32))).toString('base64'),
  ADMIN_TOKEN: 'owner-token-123', DEV_NAME: 'المطوّر', JWT_SECRET: 'jwt-test-secret', ADS_BUCKET: makeR2(),
};
const hubObj = new mod.ChatHub({ storage: new MemStorage(), getWebSockets: () => [], acceptWebSocket() {}, waitUntil() {} }, env);
env.CHAT_HUB = { idFromName: () => 'hub', get: () => ({ fetch: (u, i) => hubObj.fetch(new Request(u, i)) }) };
env.ADS_CORE = { idFromName: () => 'core', get: () => ({ fetch: async (u, i) => {
  const body = JSON.parse(await new Request(u, i).text());
  const ok = (x) => new Response(JSON.stringify(Object.assign({ ok: true }, x)));
  if (body.op === 'settings_public') return ok({ settings: { hours: { enabled: false } } });
  if (body.op === 'adv_ensure_user') return ok({ advertiser: { id: 'adv_1' } });
  if (body.op === 'authfail') return ok({ blocked: false });
  return ok({});
} }) };

async function jwt(claims) {
  const enc = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const head = enc({ alg: 'HS256', typ: 'JWT' }), body = enc(claims);
  const key = await webcrypto.subtle.importKey('raw', new TextEncoder().encode(env.JWT_SECRET), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const sig = Buffer.from(await webcrypto.subtle.sign('HMAC', key, new TextEncoder().encode(head + '.' + body))).toString('base64url');
  return head + '.' + body + '.' + sig;
}
const token = await jwt({ sub: 'auth-user-42', email: 'alice@example.invalid', name: 'Alice', exp: Math.floor(Date.now() / 1000) + 3600 });
store.set('cigram.auth.v1', JSON.stringify({ token, user: { id: 'u1', name: 'Alice' } }));

// المتصفح ← الخادم: كل fetch للموقع يمر على worker.fetch نفسه (كما يفعل Cloudflare)
const { CONFIG } = await import('../js/config.js');
const wire = [];
globalThis.fetch = async (url, opts = {}) => {
  const u = new URL(String(url));
  assert.equal(u.origin, new URL(CONFIG.supportApi).origin, 'the site only talks to the support API');
  wire.push((opts.method || 'GET') + ' ' + u.pathname);
  return worker.fetch(new Request('https://ads.test' + u.pathname + u.search, { method: opts.method || 'GET', headers: opts.headers, body: opts.body }), env, { waitUntil() {} });
};

let passed = 0;
const test = async (name, fn) => { await fn(); passed++; console.log('ok -', name); };
const PART = 1024 * 1024;
const PNG = [137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13];
const MP4 = [0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109];
const makeBytes = (size, head) => { const b = new Uint8Array(size); for (let i = 0; i < size; i++) b[i] = (i * 31 + 7) & 255; head.forEach((v, i) => { b[i] = v; }); return b; };
const sp = await import('../js/support.js');
const opened = await sp.open();                 // الصفحة تفتح المحادثة أولاً (thread_open)؛ الرفع قبلها يرجع not_found
assert.equal(opened.ok, true, 'the chat opens against the real worker');

await test('the browser preflight for the part upload allows PUT with Authorization + Content-Type', async () => {
  const r = await worker.fetch(new Request('https://ads.test/chat/upload/part?id=x&n=0', { method: 'OPTIONS', headers: { origin: 'https://serviceh.online', 'access-control-request-method': 'PUT', 'access-control-request-headers': 'authorization,content-type' } }), env, { waitUntil() {} });
  assert.equal(r.status, 204);
  const methods = r.headers.get('access-control-allow-methods').split(',').map((x) => x.trim());
  const heads = r.headers.get('access-control-allow-headers').toLowerCase();
  assert.ok(methods.includes('PUT') && methods.includes('POST') && methods.includes('GET'));
  assert.ok(heads.includes('authorization') && heads.includes('content-type'));
  assert.equal(r.headers.get('access-control-allow-origin'), '*');
});

await test('a 2.5 MB image goes through init → 3 parts → complete → send and comes back byte-for-byte', async () => {
  const data = makeBytes(2 * PART + 524288, PNG);
  const up = sp.createUpload(new File([data], 'لقطة الشاشة.png', { type: 'image/png' }));
  const pct = [];
  const r = await sp.run(up, { onProgress: (p) => pct.push(p) });
  assert.equal(r.ok, true, JSON.stringify(r));
  assert.equal(r.messages.length >= 1, true);
  const msg = r.messages.find((m) => m.mine);
  assert.equal(msg.kind, 'صورة مغلقة');
  assert.equal(msg.name, 'لقطة الشاشة.png');
  assert.equal(msg.size, data.length);
  assert.deepEqual(wire.filter((w) => /upload/.test(w)), ['POST /chat/upload/init', 'PUT /chat/upload/part', 'PUT /chat/upload/part', 'PUT /chat/upload/part', 'POST /chat/upload/complete']);
  assert.equal(pct.at(-1), 100);
  // الخادم يحفظها مشفّرة؛ التنزيل الموقّع يفك التشفير ويعيد نفس البايتات
  const list = await worker.fetch(new Request('https://ads.test/chat/messages?limit=40', { headers: { authorization: 'Bearer ' + token } }), env, { waitUntil() {} });
  const media = (await list.json()).messages.find((m) => m.media).media;
  assert.equal(media.kind, 'image'); assert.equal(media.mime, 'image/png'); assert.equal(media.size, data.length);
  const dl = await worker.fetch(new Request(media.url), env, { waitUntil() {} });
  assert.equal(dl.status, 200);
  const got = new Uint8Array(await dl.arrayBuffer());
  assert.equal(got.length, data.length);
  assert.ok(Buffer.compare(Buffer.from(got), Buffer.from(data)) === 0, 'the downloaded bytes equal the uploaded bytes');
});

await test('a video is accepted as «فيديو» and a PDF-less zip as «ملف»', async () => {
  const v = await sp.run(sp.createUpload(new File([makeBytes(300000, MP4)], 'clip.mp4', { type: 'video/mp4' })));
  assert.equal(v.ok, true, JSON.stringify(v));
  assert.equal(v.messages.find((m) => m.mine).kind, 'فيديو مغلق');
  const zip = await sp.run(sp.createUpload(new File([makeBytes(5000, [80, 75, 3, 4])], 'a.zip', { type: 'application/zip' })));
  assert.equal(zip.ok, true, JSON.stringify(zip));
  assert.equal(zip.messages.find((m) => m.mine).kind, 'ملف');
});

await test('bytes that are not an image are refused by the server for an «image» upload (one try, clear reason)', async () => {
  wire.length = 0;
  const r = await sp.run(sp.createUpload(new File([makeBytes(5000, [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12])], 'fake.png', { type: 'image/png' })));
  assert.equal(r.ok, false);
  assert.equal(r.reason, 'unsupported_type');
  assert.equal(wire.filter((w) => w === 'PUT /chat/upload/part').length, 1, 'not retried');
});

await test('a failed upload resumes against the real server and sends only what is missing', async () => {
  const data = makeBytes(3 * PART, MP4);
  const up = sp.createUpload(new File([data], 'long.mp4', { type: 'video/mp4' }));
  const real = globalThis.fetch;
  let broken = true;
  globalThis.fetch = async (url, opts = {}) => (broken && String(url).includes('/part') && String(url).includes('n=2') ? new Response(JSON.stringify({ ok: false, error: 'busy' }), { status: 503 }) : real(url, opts));
  const first = await sp.run(up);
  assert.equal(first.ok, false);
  assert.ok(up.id && !up.complete);
  broken = false; wire.length = 0;
  const again = await sp.run(up);
  globalThis.fetch = real;
  assert.equal(again.ok, true, JSON.stringify(again));
  assert.deepEqual(wire.filter((w) => /upload/.test(w)), ['GET /chat/upload/status', 'PUT /chat/upload/part', 'POST /chat/upload/complete']);
});

await test('the server limit (25 MB) is the one the site enforces', async () => {
  const r = await worker.fetch(new Request('https://ads.test/chat/upload/init', { method: 'POST', headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json' }, body: JSON.stringify({ name: 'x.mp4', kind: 'video', size: sp.MAX_BYTES + 1 }) }), env, { waitUntil() {} });
  assert.equal(r.status, 413);
  const ok = await worker.fetch(new Request('https://ads.test/chat/upload/init', { method: 'POST', headers: { authorization: 'Bearer ' + token, 'content-type': 'application/json' }, body: JSON.stringify({ name: 'x.mp4', kind: 'video', size: sp.MAX_BYTES }) }), env, { waitUntil() {} });
  assert.equal(ok.status, 200);
});

console.log(`site support-upload-worker tests: passed=${passed}`);
process.exit(0);
