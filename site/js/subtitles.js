// الترجمة في الموقع: نفس ملفات SRT التي يقرأها تطبيق سيكرام (حقل subtitle لكل فيلم وحلقة)،
// ونفس إعدادات «خطوطي» في التطبيق: الخط، الخلفية، لون النص، والحجم.
import { CONFIG } from './config.js';
import { alternateR2 } from './cigram.js';
import { decodeSubtitleBytes, parseSubtitles } from './subtitle-core.js';

export { toWebVtt } from './subtitle-core.js';

// ---------------------------------------------------------------- الخيارات (مطابقة للتطبيق)
// المفاتيح id هي نفسها في التطبيق (subtitle_font)، حتى يبقى الاختيار مفهوماً للطرفين.
export const SUB_FONTS = [
  { id: 'lmohanad', name: 'مهنّد', family: "'Cigram Mohanad'" },
  { id: 'lmohanadbold', name: 'مهنّد عريض', family: "'Cigram Mohanad Bold'" },
  { id: 'lfont_com_alyamamabold', name: 'اليمامة عريض', family: "'Cigram Alyamama Bold'" },
  { id: 'lfont_com_alyamamasemibold', name: 'اليمامة نصف عريض', family: "'Cigram Alyamama SemiBold'" },
  { id: 'system', name: 'خط الموقع', family: "'Tajawal'", weight: 700 },
];

// «أسود» هو الافتراضي في التطبيق (خلفية سوداء كاملة). البقية ألوان التطبيق بشفافية 80%.
export const SUB_BACKGROUNDS = [
  { id: 'black', name: 'أسود', color: '#000000' },
  { id: 'dim', name: 'داكن شفاف', color: 'rgba(0, 0, 0, 0.8)' },
  { id: 'gray', name: 'رمادي', color: 'rgba(28, 28, 28, 0.8)' },
  { id: 'navy', name: 'كحلي', color: 'rgba(11, 37, 69, 0.8)' },
  { id: 'purple', name: 'بنفسجي', color: 'rgba(42, 26, 74, 0.8)' },
  { id: 'wine', name: 'نبيذي', color: 'rgba(58, 11, 22, 0.8)' },
  { id: 'green', name: 'أخضر', color: 'rgba(10, 47, 34, 0.8)' },
  { id: 'none', name: 'بدون', color: 'transparent' },
];

export const SUB_COLORS = [
  { id: 'white', name: 'أبيض', color: '#FFFFFF' },
  { id: 'yellow', name: 'أصفر', color: '#FFEB3B' },
  { id: 'cyan', name: 'سماوي', color: '#4FE3FF' },
  { id: 'green', name: 'أخضر', color: '#7CFF9B' },
  { id: 'pink', name: 'وردي', color: '#FF8AE0' },
  { id: 'orange', name: 'برتقالي', color: '#FFA94D' },
];

export const SUB_SIZES = [
  { id: 's', name: 'صغير', scale: 0.85 },
  { id: 'm', name: 'عادي', scale: 1 },
  { id: 'l', name: 'كبير', scale: 1.2 },
  { id: 'xl', name: 'كبير جداً', scale: 1.45 },
];

const DEFAULTS = { enabled: true, font: 'lmohanad', bg: 'black', color: 'white', size: 'm' };
const KEY = 'cigram.subtitles.v1';
const listeners = new Set();
let settings = readSettings();

function readSettings() {
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) || 'null');
    return sanitize(raw && typeof raw === 'object' ? raw : {});
  } catch {
    return { ...DEFAULTS };
  }
}

function sanitize(o) {
  const has = (list, id) => list.some((x) => x.id === id);
  return {
    enabled: o.enabled !== false,
    font: has(SUB_FONTS, o.font) ? o.font : DEFAULTS.font,
    bg: has(SUB_BACKGROUNDS, o.bg) ? o.bg : DEFAULTS.bg,
    color: has(SUB_COLORS, o.color) ? o.color : DEFAULTS.color,
    size: has(SUB_SIZES, o.size) ? o.size : DEFAULTS.size,
  };
}

export const getSubSettings = () => ({ ...settings });

export function setSubSettings(patch) {
  settings = sanitize({ ...settings, ...patch });
  try { localStorage.setItem(KEY, JSON.stringify(settings)); } catch { /* تصفح خاص: يبقى للجلسة فقط */ }
  listeners.forEach((fn) => { try { fn(getSubSettings()); } catch { /* ignore */ } });
}

export function onSubSettings(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

window.addEventListener('storage', (e) => {
  if (e.key !== KEY) return;
  settings = readSettings();
  listeners.forEach((fn) => { try { fn(getSubSettings()); } catch { /* ignore */ } });
});

/** قيم CSS لشكل الترجمة. تُوضع على المشغل أو على المعاينة. */
export function subStyleVars(s = settings) {
  const font = SUB_FONTS.find((f) => f.id === s.font) || SUB_FONTS[0];
  const bg = SUB_BACKGROUNDS.find((b) => b.id === s.bg) || SUB_BACKGROUNDS[0];
  const color = SUB_COLORS.find((c) => c.id === s.color) || SUB_COLORS[0];
  const size = SUB_SIZES.find((z) => z.id === s.size) || SUB_SIZES[1];
  return {
    '--sub-font': `${font.family}, 'Tajawal', 'Segoe UI', Tahoma, sans-serif`,
    '--sub-weight': String(font.weight || 400),
    '--sub-bg': bg.color,
    '--sub-color': color.color,
    '--sub-scale': String(size.scale),
    // بدون خلفية: حد أسود حول الحروف حتى تبقى واضحة فوق المشاهد الفاتحة
    '--sub-shadow': bg.id === 'none'
      ? '0 0 2px #000, 0 0 2px #000, 0 1px 3px #000, 1px 1px 0 #000, -1px -1px 0 #000, 1px -1px 0 #000, -1px 1px 0 #000'
      : 'none',
  };
}

export function applySubStyle(el, s = settings) {
  const vars = subStyleVars(s);
  for (const k of Object.keys(vars)) el.style.setProperty(k, vars[k]);
}

/** يبدأ تحميل خط الترجمة مبكراً حتى لا يظهر أول سطر بخط مختلف. */
export function preloadSubFont(s = settings) {
  const font = SUB_FONTS.find((f) => f.id === s.font);
  if (!font || !document.fonts || !document.fonts.load) return;
  document.fonts.load(`${font.weight || 400} 24px ${font.family}`, 'ترجمة').catch(() => {});
}

// ---------------------------------------------------------------- التحميل
const cache = new Map(); // src -> Promise
let preferProxy = false; // إذا فشل التحميل المباشر مرة (CORS) نبدأ بالـ Worker في المرات القادمة

function candidates(src) {
  const list = [];
  // الموقع يعمل بـ https، فالمتصفح يحجب أي ملف http: نجرب نسخة https من نفس الرابط
  const httpsSrc = src.replace(/^http:\/\//i, 'https://');
  list.push({ url: httpsSrc, via: 'direct' });
  if (httpsSrc !== src && location.protocol === 'http:') list.push({ url: src, via: 'direct-http' });
  const alt = alternateR2(httpsSrc);
  if (alt) list.push({ url: alt, via: 'r2-alt' });
  const proxy = String(CONFIG.subtitleProxy || '').trim().replace(/\/+$/, '');
  if (proxy) {
    const p = { url: `${proxy}/?src=${encodeURIComponent(src)}`, via: 'proxy' };
    if (preferProxy) list.unshift(p); else list.push(p);
  }
  return list;
}

async function fetchBytes(url, ms) {
  const ctrl = typeof AbortController === 'function' ? new AbortController() : null;
  const timer = ctrl ? setTimeout(() => ctrl.abort(), ms) : null;
  try {
    const res = await fetch(url, { mode: 'cors', credentials: 'omit', signal: ctrl ? ctrl.signal : undefined });
    if (!res.ok) throw Object.assign(new Error(`HTTP ${res.status}`), { status: res.status });
    const type = (res.headers.get('content-type') || '').toLowerCase();
    if (type.includes('text/html')) throw new Error('html instead of subtitle');
    const buf = await res.arrayBuffer();
    if (buf.byteLength > 8 * 1024 * 1024) throw new Error('subtitle too large');
    return new Uint8Array(buf);
  } finally {
    if (timer) clearTimeout(timer);
  }
}

/**
 * يحمّل ملف الترجمة ويقرأه. يجرب بالترتيب: الرابط نفسه، نسخة R2 الأخرى، ثم Worker الترجمة (إن وُضع في config.js).
 * @returns {Promise<{ cues: Array, encoding: string, via: string }>}
 */
export function loadSubtitle(src) {
  const key = String(src || '').trim();
  if (!/^https?:\/\//i.test(key)) return Promise.reject(new Error('no subtitle url'));
  if (!cache.has(key)) {
    const job = (async () => {
      const errors = [];
      for (const c of candidates(key)) {
        try {
          const bytes = await fetchBytes(c.url, 20000);
          const { text, encoding } = decodeSubtitleBytes(bytes);
          const cues = parseSubtitles(text);
          if (!cues.length) { errors.push(`${c.via}: no cues`); continue; }
          if (c.via === 'proxy' && errors.length) preferProxy = true;
          return { cues, encoding, via: c.via };
        } catch (err) {
          errors.push(`${c.via}: ${err && err.message ? err.message : err}`);
        }
      }
      const e = new Error('subtitle unavailable');
      e.details = errors;
      throw e;
    })();
    cache.set(key, job);
    job.catch(() => cache.delete(key)); // نسمح بإعادة المحاولة لاحقاً
  }
  return cache.get(key);
}
