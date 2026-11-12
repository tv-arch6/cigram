export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
export const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ESC[c]);

/** يوحّد النص العربي للبحث: يحذف التشكيل ويوحّد الألف والياء والتاء المربوطة. */
export function norm(s) {
  return String(s ?? '')
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[ً-ٰٟـ]/g, '')
    .replace(/[أإآٱ]/g, 'ا')
    .replace(/ى/g, 'ي')
    .replace(/ة/g, 'ه')
    .replace(/ؤ/g, 'و')
    .replace(/ئ/g, 'ي')
    .replace(/[^\p{L}\p{N}\s]/gu, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

export function fmtClock(sec) {
  sec = Math.max(0, Math.floor(Number(sec) || 0));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  const mm = String(m).padStart(h ? 2 : 1, '0');
  const ss = String(s).padStart(2, '0');
  return h ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

export function fmtDuration(sec) {
  sec = Math.round(Number(sec) || 0);
  if (!sec) return '';
  if (sec < 60) return `${sec} ثانية`;
  const h = Math.floor(sec / 3600);
  const m = Math.round((sec % 3600) / 60);
  if (!h) return `${m} دقيقة`;
  return m ? `${h} ساعة و${m} دقيقة` : `${h} ساعة`;
}

export function debounce(fn, ms = 200) {
  let t;
  return (...args) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...args), ms);
  };
}

export function toast(message, { action, onAction, ms = 4200 } = {}) {
  const host = document.getElementById('toastHost');
  if (!host) return;
  // v2 (مثل التطبيق): لا رسائل فوق الفيلم أثناء المشاهدة؛ الأخطاء الحقيقية تظهر داخل المشغل نفسه
  if (document.body.classList.contains('watching')) return;
  const el = document.createElement('div');
  el.className = 'toast';
  const text = document.createElement('span');
  text.textContent = message;
  el.appendChild(text);
  if (action) {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = action;
    b.addEventListener('click', () => {
      try { onAction && onAction(); } finally { el.remove(); }
    });
    el.appendChild(b);
  }
  host.appendChild(el);
  setTimeout(() => el.remove(), ms);
}

export function loadScript(url) {
  return new Promise((resolve, reject) => {
    const existing = document.querySelector(`script[data-src="${url}"]`);
    if (existing) {
      if (existing.dataset.loaded) return resolve();
      existing.addEventListener('load', () => resolve());
      existing.addEventListener('error', () => reject(new Error('script failed')));
      return;
    }
    const s = document.createElement('script');
    s.src = url;
    s.async = true;
    s.dataset.src = url;
    s.onload = () => { s.dataset.loaded = '1'; resolve(); };
    s.onerror = () => reject(new Error('script failed'));
    document.head.appendChild(s);
  });
}

export const ICONS = {
  play: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5.5v13l11-6.5z"/></svg>',
  plus: '<svg viewBox="0 0 24 24" aria-hidden="true" class="stroke"><path d="M12 5v14M5 12h14"/></svg>',
  check: '<svg viewBox="0 0 24 24" aria-hidden="true" class="stroke"><path d="m5 12.5 4.5 4.5L19 7.5"/></svg>',
  back: '<svg viewBox="0 0 24 24" aria-hidden="true" class="stroke"><path d="m9 5 7 7-7 7"/></svg>',
  chevL: '<svg viewBox="0 0 24 24" aria-hidden="true" class="stroke"><path d="m15 5-7 7 7 7"/></svg>',
  chevR: '<svg viewBox="0 0 24 24" aria-hidden="true" class="stroke"><path d="m9 5 7 7-7 7"/></svg>',
  star: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3.5 2.6 5.4 5.9.8-4.3 4.1 1 5.9L12 16.9l-5.2 2.8 1-5.9L3.5 9.7l5.9-.8z"/></svg>',
  close: '<svg viewBox="0 0 24 24" aria-hidden="true" class="stroke"><path d="M6 6l12 12M18 6 6 18"/></svg>',
  skip: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 6v12l8-6zM14 6v12l8-6z"/></svg>',
  cc: '<svg viewBox="0 0 24 24" aria-hidden="true" class="stroke"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="M10.5 10.2a2.4 2.4 0 1 0 0 3.6M17 10.2a2.4 2.4 0 1 0 0 3.6"/></svg>',
  fullscreen: '<svg viewBox="0 0 24 24" aria-hidden="true" class="stroke"><path d="M4 9V5h4M20 9V5h-4M4 15v4h4M20 15v4h-4"/></svg>',
  fullscreenExit: '<svg viewBox="0 0 24 24" aria-hidden="true" class="stroke"><path d="M8 4v4H4M16 4v4h4M8 20v-4H4M16 20v-4h4"/></svg>',
};
