// قائمة القنوات: نفس بيانات صفحة «القنوات» في تطبيق سيكرام (CigramChannelStore + CigramBuiltinChannels).
//
// - القناتان المدمجتان في التطبيق نفسه (beIN SPORTS 3 والشرقية) تأتيان أولاً، بشعارهما من icons/channels/.
// - بعدها قنوات الخادم: GET CONFIG.channelsApi يرجع { ok, categories: [{ name, channels: [{ name, url|link,
//   image|poster|logo|image_url|poster_url, available }] }] }.
// - الباقات: الرياضة ثم الأخبارية ثم الباقي بترتيب الخادم. القناة المكررة بنفس الاسم تظهر مرة واحدة.
// - آخر قائمة ناجحة تُحفظ في المتصفح، فتفتح الصفحة فوراً، ويُسأل الخادم مرة كل 3 دقائق على الأكثر.
import { CONFIG } from './config.js';
import * as store from './store.js';
import { qualityOf, PLUS_MIN_HEIGHT } from './plus.js';

const CACHE_KEY = 'cigram_channels_cache_v1';
const FRESH_MS = 3 * 60 * 1000;

/** { name, link, package, logo } مثل CigramBuiltinChannels.LIST في التطبيق. */
const BUILTIN = [
  ['beIN SPORTS 3', 'http://194.76.0.163/8zpo3GsVY7/beneficial2concern/652335?token=ShJdY2ZmQQNHCmMZCDZXUh9GSHAWGFMD.ZDsGQVN.WGBFNX013GR9YV1QbGBp0QE9SWmpcXlQXXlUHWlcbRxFACmcDY1tXEVkbVAoAAQJUFxUbRFldAxdeUAdaVAFcUwcHAhwWQlpXQQMLTFhUG0FQQU1VQl4HWTsFVBQLVABGCVxEXFgeEVwNZgFcWVlZBxcDGwESHERcFxETWAxCCQgfEFNZQEBSRwYbX1dBVFtPF1pWRV5EFExGWxMmJxVJRlZKRVVaQVpcDRtfG0BLFU8XUEpvQlUVQRYEUA8HRUdeEQITHBZfUks8WgpXWl1UF1xWV0MSCkQERk0TDw1ZDBBcQG5AXVYRCQ1MCVVJ', 'الرياضة', 'icons/channels/bein-sports-3.png'],
  ['الشرقية', 'https://5d94523502c2d.streamlock.net/home/mystream/chunklist_w1496386509.m3u8', 'الأخبارية', 'icons/channels/alsharqiya.png'],
];

/** مثل norm في التطبيق: يوحّد الألف والتاء المربوطة والياء ويحذف المسافات، فالمفاتيح هي نفسها في التطبيق. */
export function norm(v) {
  return String(v ?? '').trim().toLowerCase()
    .replace(/[أإآ]/g, 'ا').replace(/ة/g, 'ه').replace(/ى/g, 'ي')
    .replace(/[\s_-]+/g, '');
}

function sectionName(raw) {
  const k = norm(raw);
  if (k.includes('رياض') || k.includes('sport')) return 'الرياضة';
  if (k.includes('اخبار') || k.includes('خبر') || k.includes('news')) return 'الأخبارية';
  const t = String(raw ?? '').trim();
  return t || 'القنوات';
}

const str = (v) => (v === undefined || v === null ? '' : String(v).trim());
const pick = (o, ...keys) => { for (const k of keys) { const v = str(o[k]); if (v) return v; } return ''; };
const availableFlag = (v) => !(v === false || v === 0 || /^(false|0|no)$/i.test(str(v)));

function makeChannel(name, url, section, image) {
  const key = norm(name);
  const sec = sectionName(section);
  return { key, name, url, image, section: sec, search: key, sectionKey: norm(sec), available: !!url, quality: 0, plus: false };
}

/** يبني الكتالوج من نص JSON الخادم (أو '' للقنوات المدمجة فقط). */
function build(raw) {
  const list = [];
  const seen = new Set();
  for (const [name, url, section, logo] of BUILTIN) {
    const ch = makeChannel(name, url, section, logo);
    if (!ch.key || seen.has(ch.key)) continue;
    seen.add(ch.key);
    list.push(ch);
  }
  try {
    const groups = raw ? JSON.parse(raw).categories : null;
    for (const group of Array.isArray(groups) ? groups : []) {
      if (!group || typeof group !== 'object') continue;
      const items = Array.isArray(group.channels) ? group.channels : [];
      for (const o of items) {
        if (!o || typeof o !== 'object') continue;
        const name = str(o.name);
        const key = norm(name);
        if (!key || seen.has(key)) continue;
        seen.add(key);
        const ch = makeChannel(name, pick(o, 'url', 'link'), group.name, pick(o, 'image', 'poster', 'logo', 'image_url', 'poster_url'));
        ch.available = availableFlag(o.available) && !!ch.url;
        // قناة Plus: جودتها 1080p–4K (أو plus:true، أو باقة كاملة HD/4K)
        ch.quality = qualityOf(o) || qualityOf(group);
        ch.plus = o.plus === true || (o.plus === undefined && group.plus === true) || ch.quality >= PLUS_MIN_HEIGHT;
        list.push(ch);
      }
    }
  } catch (e) {
    console.warn('[سيكرام] تعذر قراءة قائمة القنوات', e);
  }

  // الباقات: الرياضة، الأخبارية، ثم البقية بترتيب الكتالوج
  const order = ['الرياضة', 'الأخبارية'];
  for (const ch of list) if (!order.includes(ch.section)) order.push(ch.section);
  const catalog = { all: [], packages: [], byKey: new Map() };
  for (const name of order) {
    const channels = list.filter((c) => c.section === name);
    if (!channels.length) continue;
    catalog.packages.push({ name, channels, cover: channels.find((c) => c.image) || channels[0] });
    for (const c of channels) { catalog.all.push(c); catalog.byKey.set(c.key, c); }
  }
  return catalog;
}

// ---------------------------------------------------------------- state
let current = null;     // الكتالوج الجاهز
let lastRaw = null;     // نص آخر قائمة بُني منها الكتالوج
let fetching = null;    // طلب جارٍ (Promise)
let failed = false;     // آخر محاولة فشلت ولا توجد نسخة محفوظة
const listeners = new Set();

function readCache() {
  try { const c = JSON.parse(localStorage.getItem(CACHE_KEY) || 'null'); return c && typeof c.json === 'string' ? c : null; }
  catch { return null; }
}
function writeCache(json) {
  try { localStorage.setItem(CACHE_KEY, JSON.stringify({ json, at: Date.now() })); } catch { /* تصفح خاص */ }
}

function emit(changed) { listeners.forEach((fn) => { try { fn(current, changed); } catch (e) { console.warn(e); } }); }

/** الكتالوج الآن (من النسخة المحفوظة أو القنوات المدمجة)، ويُحدَّث من الخادم في الخلفية عند الحاجة. */
export function getCatalog() {
  if (!current) {
    const cached = readCache();
    lastRaw = cached ? cached.json : '';
    current = build(lastRaw);
  }
  return current;
}

/** هل فشل آخر تحديث ولا توجد نسخة محفوظة من الخادم؟ */
export const loadFailed = () => failed && !lastRaw;
/** هل يُحمَّل الكتالوج لأول مرة (لا توجد نسخة محفوظة بعد)؟ */
export const isLoading = () => !!fetching && !lastRaw;

/** يستمع لتغير القائمة. يرجع دالة لإلغاء الاستماع. */
export function listen(fn) { listeners.add(fn); return () => listeners.delete(fn); }

/** يسأل الخادم إن كانت النسخة المحفوظة أقدم من 3 دقائق (أو دائماً مع force). */
export function refresh(force = false) {
  getCatalog();
  if (fetching) return fetching;
  const cached = readCache();
  if (!force && cached && Date.now() - cached.at < FRESH_MS && cached.at <= Date.now()) return Promise.resolve(current);
  fetching = (async () => {
    try {
      const res = await fetch(CONFIG.channelsApi, { cache: 'no-cache', headers: { Accept: 'application/json' } });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const text = await res.text();
      const root = JSON.parse(text);
      if (!root || root.ok === false || !Array.isArray(root.categories)) throw new Error('Invalid channels catalog');
      const json = JSON.stringify(root);
      writeCache(json);
      failed = false;
      if (json !== lastRaw) {
        lastRaw = json;
        current = build(json);
        emit(true);
      } else {
        emit(false);
      }
    } catch (e) {
      console.warn('[سيكرام] تعذر تحديث القنوات؛ آخر نسخة محفوظة باقية', e);
      failed = true;
      emit(false);
    } finally {
      fetching = null;
    }
    return current;
  })();
  return fetching;
}

// ---------------------------------------------------------------- favourites / recent (محفوظة مع بيانات المشاهد في store.js)
export const isFav = (key) => store.isChannelFav(key);
export const toggleFav = (key) => store.toggleChannelFav(key);
export const pushRecent = (key) => store.pushChannelRecent(key);

/** القنوات المفضلة أو المشاهدة مؤخراً الموجودة في الكتالوج، بالترتيب المحفوظ. */
export function channelsByKeys(keys) {
  const cat = getCatalog();
  return keys.map((k) => cat.byKey.get(k)).filter(Boolean);
}
export const favourites = () => channelsByKeys(store.channelFavs());
export const recent = () => channelsByKeys(store.channelRecent());
