// رابط خارجي في آخر صفحة «حسابي» (مثل التطبيق): يتحكم به تطبيق الإدارة (قسم «إدارة الروابط الخارجية»).
// GET {plusApi}/app/external-link -> { enabled, url, kind: 'app'|'channel', icon }
// بطاقة بزوايا 20px، شبكة بوسترات مائلة «/» بينها فواصل نصف شفافة، والنقر يفتح الرابط.
import { CONFIG } from './config.js';
import { esc } from './util.js';

const KEY = 'cigram_ext_link';
const TTL = 10 * 60 * 1000;

function cached() {
  try { const s = JSON.parse(sessionStorage.getItem(KEY) || 'null'); return s && Date.now() - s.at < TTL ? s.v : null; } catch { return null; }
}

export async function fetchLink() {
  const hit = cached();
  if (hit) return hit;
  const base = String(CONFIG.plusApi || '').replace(/\/+$/, '');
  if (!base) return null;
  try {
    const res = await fetch(base + '/app/external-link', { credentials: 'omit', headers: { Accept: 'application/json' } });
    const v = await res.json();
    try { sessionStorage.setItem(KEY, JSON.stringify({ at: Date.now(), v })); } catch { /* ignore */ }
    return v;
  } catch { return null; }
}

/** رابط https فقط (لا javascript: ولا http). */
export const safeUrl = (u) => (/^https:\/\//i.test(String(u || '')) ? String(u) : '');

export function linkHtml(link, posters) {
  const url = link && link.enabled ? safeUrl(link.url) : '';
  if (!url) return '';
  const title = link.kind === 'channel' ? 'قناتنا على تيليجرام' : 'حمّل تطبيق سيكرام';
  const sub = link.kind === 'channel' ? 'تابع كل جديد' : 'التطبيق الكامل لأفضل تجربة';
  const cells = posters.slice(0, 12).map((p) => `<i style="background-image:url('${esc(String(p).replace(/'/g, '%27'))}')"></i>`).join('');
  const icon = safeUrl(link.icon);
  return `<a class="ext-link" href="${esc(url)}" target="_blank" rel="noopener noreferrer" data-ext-link>
    <span class="ext-grid" aria-hidden="true">${cells}</span><span class="ext-scrim"></span>
    <span class="ext-body">${icon ? `<img class="ext-ico" src="${esc(icon)}" alt="" referrerpolicy="no-referrer">` : ''}<strong>${title}</strong><span>${sub}</span></span>
  </a>`;
}

export async function mountExternalLink(host, items) {
  if (!host) return;
  const link = await fetchLink();
  if (!host.isConnected) return;
  const posters = (items || []).map((i) => i.poster || i.backdrop).filter(Boolean);
  const html = linkHtml(link, posters);
  host.innerHTML = html;
  host.hidden = !html;
}
