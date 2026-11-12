// بوابة الإعلانات في الموقع: القرار نفسه الذي يأخذه التطبيق.
//  - تقرأ إعداد الإعلانات الذي تحفظه الإدارة («AdMob: تشغيل وإيقاف» ← GET /ads/config): إذا أوقفت الإدارة المفتاح
//    الرئيسي تختفي الإعلانات من الموقع خلال دقائق، بلا نشر جديد.
//  - مشترك Cigram Plus لا يرى أي إعلان: لحظة تفعيل الاشتراك تُزال المساحة من الصفحة.
// الوحدة الإعلانية نفسها من Google (AdSense للويب: adsClient/adsSlot في js/config.js). بدونها لا تُنشأ أي مساحة.
import { CONFIG } from './config.js';
import * as plus from './plus.js';

const CACHE = 'cigram_ads_cfg';
const TTL = 10 * 60 * 1000;
let cfg = null;
let loading = null;

function cached() {
  try { const c = JSON.parse(sessionStorage.getItem(CACHE) || 'null'); return c && Date.now() - c.at < TTL ? c.cfg : null; } catch { return null; }
}

/** إعداد الإدارة (أو {} إن لم يُحفظ إعداد: الإعلانات تعمل بالقيم الافتراضية). */
export function loadConfig(force = false) {
  if (!force) { cfg = cfg || cached(); if (cfg) return Promise.resolve(cfg); }
  if (loading) return loading;
  loading = fetch(String(CONFIG.apiBase || '').replace(/\/+$/, '') + '/ads/config', { cache: 'no-store' })
    .then((r) => r.json())
    .then((d) => {
      const data = (d && d.data) || d || {};
      cfg = data.config || data || {};
      try { sessionStorage.setItem(CACHE, JSON.stringify({ at: Date.now(), cfg })); } catch { /* ignore */ }
      return cfg;
    })
    .catch(() => cfg || {})
    .finally(() => { loading = null; });
  return loading;
}

/** القرار: الإدارة لم توقفها + المستخدم ليس مشتركاً + وحدة إعلانية مضبوطة. */
export function decide(config, isPlus, client) {
  if (isPlus) return false;
  if (config && config.ads_enabled === false) return false;
  return !!String(client || '').trim();
}

export const allowed = () => decide(cfg, plus.isPlus(), CONFIG.adsClient);

let script = null;
function loadScript() {
  if (script) return script;
  script = new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.async = true;
    s.crossOrigin = 'anonymous';
    s.src = 'https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=' + encodeURIComponent(CONFIG.adsClient);
    s.onload = resolve;
    s.onerror = reject;
    document.head.appendChild(s);
  });
  return script;
}

/** مساحة إعلان أسفل الصفحة (مثل البانر السفلي في «قائمة المشاهدة» بالتطبيق). لا تأخذ حيّزاً حتى يُقرَّر عرضها. */
export async function mountBottom(host) {
  if (!host) return () => {};
  await loadConfig();
  const slot = document.createElement('div');
  slot.className = 'ad-bottom';
  slot.hidden = true;
  host.appendChild(slot);
  const paint = () => {
    if (!allowed()) { slot.hidden = true; slot.innerHTML = ''; return; }
    if (slot.dataset.on) return;
    slot.dataset.on = '1';
    slot.hidden = false;
    slot.innerHTML = `<ins class="adsbygoogle" style="display:block" data-ad-client="${String(CONFIG.adsClient).replace(/"/g, '')}" data-ad-slot="${String(CONFIG.adsSlot || '').replace(/"/g, '')}" data-ad-format="auto" data-full-width-responsive="true"></ins>`;
    loadScript().then(() => { try { (window.adsbygoogle = window.adsbygoogle || []).push({}); } catch { /* ignore */ } }).catch(() => { slot.hidden = true; });
  };
  paint();
  const off = plus.onChange(() => { delete slot.dataset.on; paint(); });      // اشترك الآن؟ يختفي الإعلان فوراً
  return () => { off(); slot.remove(); };
}
