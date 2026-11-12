// node --no-warnings test/promo-site.test.mjs  (يعمل بتوقيت الجهاز؛ التواريخ هنا محلية)
const store = new Map();
globalThis.localStorage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: (k) => store.delete(k) };
globalThis.sessionStorage = globalThis.localStorage;
globalThis.location = { protocol: 'https:', hash: '', href: 'https://serviceh.online/' };
const el = () => ({ classList: { contains: () => false, add() {}, remove() {} }, addEventListener() {}, querySelector: () => null, querySelectorAll: () => [], style: {} });
globalThis.document = { documentElement: el(), addEventListener() {}, querySelector: () => null, querySelectorAll: () => [], createElement: el, body: el(), hidden: false };
globalThis.addEventListener = () => {}; globalThis.window = globalThis; globalThis.matchMedia = () => ({ matches: false, addEventListener() {} });
Object.defineProperty(globalThis, 'navigator', { value: { userAgent: 'test' }, configurable: true });
let pass = 0, fail = 0; const ok = (c, n) => { if (c) pass++; else { fail++; console.log('FAIL:', n); } };
const { promoShouldShow, promoRecord, redDigits, PROMO_DAYS } = await import('../js/plus-view.js');
const at = (y, m, d, h = 10) => new Date(y, m - 1, d, h).getTime();
const sun = at(2026, 11, 1), mon = at(2026, 11, 2), tue = at(2026, 11, 3), wed = at(2026, 11, 4), thu = at(2026, 11, 5), fri = at(2026, 11, 6), sat = at(2026, 11, 7);
ok(PROMO_DAYS.join() === '0,4,5', 'Sunday, Thursday, Friday');
ok(promoShouldShow([], sun) && promoShouldShow([], thu) && promoShouldShow(null, fri), 'Sun/Thu/Fri show');
ok(![mon, tue, wed, sat].some((t) => promoShouldShow([], t)), 'other days never');
ok(!promoShouldShow([at(2026, 11, 5, 1)], thu), 'once per Thursday');
ok(promoShouldShow([sun], thu) && promoShouldShow([sun, thu], fri), 'each day has its own slot');
ok(!promoShouldShow([sun + 9 * 86400000], sun), 'clock backwards -> quiet');
let h = []; let shown = 0;
for (let d = 0; d < 28; d++) { const t = sun + d * 86400000; if (promoShouldShow(h, t)) { h = promoRecord(h, t); shown++; } }
ok(shown === 12, '4 weeks -> 12 impressions: ' + shown);
let n = 0; let g = []; for (let i = 0; i < 6; i++) { const t = thu + i * 3600000; if (promoShouldShow(g, t)) { g = promoRecord(g, t); n++; } }
ok(n === 1, 'six launches on one Thursday -> one pop-up');
const r = redDigits('1,500 د.ع لأول 3 أشهر ثم 10,000 <b>');
ok(r.includes('<span class="pp-n">1,500</span>') && r.includes('<span class="pp-n">3</span>') && r.includes('<span class="pp-n">10,000</span>') && !r.includes('<b>'), 'numbers red, html escaped');
console.log(`site promo tests: passed=${pass} failed=${fail}`);
process.exit(fail ? 1 : 0);
