// شكل الرئيسية وثيمها من تطبيق الإدارة: نفس GET /home-layout الذي يقرأه تطبيق سيكرام (CigramLayoutConfig).
// كل شيء اختياري. بدون رد (المسار غير موجود بعد، أو بدون إنترنت) تبقى القيم الافتراضية ويعمل كل شيء.
//
// {
//   "theme":     { "mode": "system" | "light" | "dark" },
//   "chips":     [ { "title": "مسلسلات", "type": "series" }, { "title": "أنمي", "section_id": "anime" } ],
//   "billboard": { "enabled": true, "item_id": "1234" }  أو  { "section_id": "trending" },
//   "sections":  { "trending": { "style": "top10", "title": "أفضل 10" }, "new": { "style": "landscape" } }
// }
// أنماط الأقسام: row (الافتراضي)، top10، landscape، large، billboard.
import { CONFIG } from './config.js';

const KEY = 'cigram_home_layout';
const GAP_MS = 60 * 1000;
let cached = null;
let lastTry = 0;
let fetching = false;
const listeners = new Set();

export function current() {
  if (cached) return cached;
  try { cached = JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch { cached = {}; }
  return cached;
}

export function themeMode() {
  const t = current().theme;
  const m = String((t && t.mode) || 'system').trim().toLowerCase();
  return m === 'light' || m === 'dark' ? m : 'system';
}

export function normStyle(raw) {
  const s = String(raw || '').trim().toLowerCase().replace(/[-_\s]/g, '');
  if (s === 'top10' || s === 'ranked' || s === 'top') return 'top10';
  if (s === 'landscape' || s === 'wide' || s === 'backdrop') return 'landscape';
  if (s === 'large' || s === 'big' || s === 'tall') return 'large';
  if (s === 'billboard' || s === 'hero') return 'billboard';
  if (s === 'row' || s === 'poster' || s === 'default') return 'row';
  return '';
}

function sectionEntry(id, name) {
  const sections = current().sections;
  if (!sections || typeof sections !== 'object') return null;
  for (const k of [id, name]) {
    const key = String(k || '').trim();
    if (!key) continue;
    const s = sections[key] || sections[key.toLowerCase()];
    if (s && typeof s === 'object') return s;
  }
  return null;
}

/** نمط القسم الذي حدده تطبيق الإدارة ('' = لم يحدد). */
export function styleFor(id, name) {
  const s = sectionEntry(id, name);
  return s ? normStyle(s.style || s.layout || s.display) : '';
}

/** عنوان القسم من تطبيق الإدارة، أو null للعنوان الأصلي. */
export function titleFor(id, name) {
  const s = sectionEntry(id, name);
  const t = s && String(s.title || '').trim();
  return t || null;
}

/** الأزرار العلوية: لا شيء افتراضياً (مثل التطبيق)، فقط ما يرسله تطبيق الإدارة. */
export function chips() {
  const list = current().chips;
  return Array.isArray(list) ? list.filter((c) => c && String(c.title || '').trim()) : [];
}

/** البانر الكبير، أو null إذا لم يُفعَّل. */
export function billboard() {
  const b = current().billboard;
  return b && typeof b === 'object' && b.enabled !== false ? b : null;
}

export const onChange = (fn) => { listeners.add(fn); return () => listeners.delete(fn); };

/** يجلب الشكل بالخلفية (مرة في الدقيقة على الأكثر) ويبلّغ إن تغيّر. */
export async function refresh(force = false) {
  const now = Date.now();
  if (fetching || (!force && now - lastTry < GAP_MS)) return;
  fetching = true;
  lastTry = now;
  try {
    const res = await fetch(CONFIG.apiBase.replace(/\/+$/, '') + '/home-layout', { cache: 'no-cache' });
    if (!res.ok) return;
    let fresh = await res.json();
    if (fresh && typeof fresh.layout === 'object' && fresh.layout) fresh = fresh.layout;
    if (!fresh || typeof fresh !== 'object') return;
    const text = JSON.stringify(fresh);
    if (text === JSON.stringify(current())) return;
    cached = fresh;
    try { localStorage.setItem(KEY, text); } catch { /* ignore */ }
    listeners.forEach((fn) => { try { fn(); } catch { /* ignore */ } });
  } catch { /* غير منشور بعد / بدون إنترنت: القيم الافتراضية */ } finally {
    fetching = false;
  }
}
