// node --no-warnings test/requests-site.test.mjs
const store = new Map();
globalThis.localStorage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: (k) => store.delete(k) };
globalThis.sessionStorage = globalThis.localStorage;
globalThis.location = { protocol: 'https:', hash: '', href: 'https://serviceh.online/' };
const el = () => ({ classList: { contains: () => false, add() {}, remove() {} }, addEventListener() {}, querySelector: () => null, querySelectorAll: () => [], style: {} });
globalThis.document = { documentElement: el(), addEventListener() {}, querySelector: () => null, querySelectorAll: () => [], createElement: el, body: el(), head: el(), hidden: false };
globalThis.addEventListener = () => {}; globalThis.window = globalThis; globalThis.matchMedia = () => ({ matches: false, addEventListener() {} });
Object.defineProperty(globalThis, 'navigator', { value: { userAgent: 'test' }, configurable: true });
let pass = 0, fail = 0; const ok = (c, n) => { if (c) pass++; else { fail++; console.log('FAIL:', n); } };

// ---- requests: local first, statuses from the admin, automatic match with the catalogue
const calls = [];
let serverStatus = 'pending';
globalThis.fetch = async (url, opts = {}) => {
  calls.push({ url: String(url), method: opts.method || 'GET', body: opts.body ? JSON.parse(opts.body) : null });
  if (String(url).endsWith('/requests')) return { ok: true, status: 200, json: async () => ({ ok: true, id: 'srv1' }) };
  if (String(url).includes('/requests/mine')) return { ok: true, status: 200, json: async () => ({ ok: true, requests: [{ id: 'srv1', status: serverStatus, admin_note: '' }] }) };
  if (String(url).endsWith('/ads/config')) return { ok: true, status: 200, json: async () => ({ ok: true, data: { config: { ads_enabled: false } } }) };
  return { ok: false, status: 404, json: async () => ({}) };
};
const rq = await import('../js/requests.js');
ok(rq.add('movie', ' ') === null && rq.list().length === 0, 'an empty name is not a request');
const r = rq.add('movie', 'الجوكر');
ok(r && r.status === 'pending' && rq.list().length === 1, 'a request is saved at once');
ok(rq.add('movie', 'جوكر').local_id === r.local_id && rq.list().length === 1, 'the same title is not requested twice');
ok(rq.statusLine(r).text === 'قيد المراجعة', 'pending line');
await rq.sync();
const sent = calls.find((c) => c.method === 'POST');
ok(sent && sent.body.kind === 'movie' && sent.body.title === 'الجوكر' && sent.body.client_id.startsWith('web-'), 'sent to the same /requests endpoint as the app');
ok(rq.list()[0].id === 'srv1', 'the server id is remembered');
serverStatus = 'in_progress';
ok(await rq.sync() === true && rq.statusLine(rq.list()[0]).text === 'المدير وافق، سيتم إضافته قريبًا، اصبر', 'approved by the admin');
const cat = { items: [{ id: 'm77', type: 'movie', title: 'الجوكر' }, { id: 's1', type: 'series', title: 'الجوكر' }] };
ok(rq.matchCatalog(cat) === true, 'the catalogue now has the title');
const line = rq.statusLine(rq.list()[0]);
ok(line.text === 'تم رفعها، ' && line.link === 'انقر هنا' && line.href === '#/title/m77', '«تم رفعها، انقر هنا» opens the film (not the series of the same name)');
serverStatus = 'pending';
await rq.sync();
ok(rq.list()[0].status === 'added', 'a local match is never downgraded by the server');
rq.remove(rq.list()[0].local_id);
ok(rq.list().length === 0, 'removed');

// ---- ads gate: the admin's master switch and Cigram Plus both hide ads; no unit id = no slot at all
const ads = await import('../js/ads.js');
ok(ads.decide({}, false, 'ca-pub-1') === true, 'ads on by default when a unit is configured');
ok(ads.decide({ ads_enabled: false }, false, 'ca-pub-1') === false, 'the admin switch stops ads on the site');
ok(ads.decide({}, true, 'ca-pub-1') === false, 'a Plus subscriber sees no ads');
ok(ads.decide({}, false, '') === false, 'no unit configured -> no slot');
const cfg = await ads.loadConfig(true);
ok(cfg.ads_enabled === false && ads.allowed() === false, 'the saved admin config is read from /ads/config');

// ---- support: three topics, media arrives as a closed file
const sup = await import('../js/support.js');
ok(sup.TOPICS.length >= 3 && sup.TOPICS.every((t) => t.icon && !/\p{Extended_Pictographic}/u.test(t.label + t.text)), 'topics carry line icons, no emoji');
const n = sup.normalize([{ seq: 2, from: 'admin', type: 'image', ts: 1 }, { seq: 1, from: 'user', type: 'text', text: 'مرحبا', ts: 1 }, { seq: 3, deleted: true }]);
ok(n.length === 2 && n[0].seq === 1 && n[0].mine && n[1].kind === 'صورة مغلقة' && !n[1].mine, 'messages are ordered; a picture is a closed file');

// ---- «جديد»: once per user
const nb = await import('../js/newbadge.js');
ok(nb.badge('x').includes('جديد') && nb.badge('x') === '' && nb.badge('y').includes('جديد'), 'the badge is shown once per feature');

console.log(`${pass} passed, ${fail} failed`);
if (fail) process.exit(1);
