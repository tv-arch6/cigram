// الشريط السفلي والعلوي بأيقونات التطبيق (v10.83): Material Symbols، الصفحة المفتوحة ممتلئة والباقي شفافة.
// عند تسجيل الدخول يظهر صورة الحساب بدل أيقونته (مثل التطبيق).
import { sym, searchSym } from './icons.js';
import { esc } from './util.js';
import * as plus from './plus.js';

function iconHtml(name, filled) {
  return name === 'manage_search' ? searchSym(filled) : sym(name, filled);
}

let avatarUrl = '';
let lastNav = 'home';

/** يرسم الأيقونات حسب الصفحة المفتوحة (nav = home | channels | search | account). */
export function paintNav(nav) {
  lastNav = nav;
  const framed = plus.isPlus();
  document.querySelectorAll('[data-nav]').forEach((a) => {
    const on = a.dataset.nav === nav;
    const slot = a.querySelector('.nv-ic');
    if (!slot) return;
    const name = slot.dataset.icon;
    const want = (name === 'account_circle' && avatarUrl ? (framed ? 'photo+|' : 'photo|') : '') + name + (on ? '|on' : '|off');
    if (slot.dataset.drawn === want) return;
    slot.dataset.drawn = want;
    if (name === 'account_circle' && avatarUrl) {
      slot.innerHTML = `<span class="nav-avatar-wrap"><img class="nv-photo${on ? ' on' : ''}" src="${esc(avatarUrl)}" alt="" referrerpolicy="no-referrer">${framed ? '<i class="vbadge" aria-hidden="true"></i>' : ''}</span>`;
    } else {
      slot.innerHTML = iconHtml(name, on);
    }
  });
}

/** صورة الحساب (أو '' للأيقونة). */
export function setAvatar(url, nav) {
  const u = /^https?:\/\//i.test(String(url || '')) ? String(url) : '';
  if (u === avatarUrl) return;
  avatarUrl = u;
  document.querySelectorAll('[data-icon="account_circle"]').forEach((s) => { s.dataset.drawn = ''; });
  paintNav(nav);
}

// انتهاء Plus أو تفعيله: نعيد رسم أيقونة الحساب (الشارة تظهر وتختفي وحدها)
plus.onChange(() => { paintNav(lastNav); });
