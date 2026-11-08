// Cigram Plus (الموقع): نفس منطق التطبيق. الاشتراك بكود تفعيل فقط، والحساب واحد في التطبيق والموقع.
//  - الخادم (plus-worker/worker.js) هو الذي يقرر الخطة والمدة؛ الأسعار هنا نصوص للعرض فقط.
//  - أول اشتراك للحساب: 1,500 د.ع = 3 أشهر (مرة واحدة)، بعدها 10,000 د.ع = 6 أشهر.
//  - مجاني: جودة حتى 720p، بدون صورة داخل صورة، بدون قنوات 1080p–4K. Plus: كل ذلك بدون قيود.
import { CONFIG } from './config.js';
import * as auth from './auth.js';

export const PLANS = {
  intro: { months: 3, price: 1500 },
  standard: { months: 6, price: 10000 },
};
export const FREE_MAX_HEIGHT = 720;
export const PLUS_MIN_HEIGHT = 1080;
export const METHODS = [
  { key: 'mastercard', label: 'ماستر كارد' },
  { key: 'zaincash', label: 'زين كاش' },
  { key: 'asiacell', label: 'اسياسيل' },
];

const KEY = 'cigram.plus.v1';
const GAP_MS = 10 * 60 * 1000;
const listeners = new Set();
let lastAt = 0;
let busy = false;

export const salesEnabled = () => CONFIG.plusSales !== false;
export const onChange = (fn) => { listeners.add(fn); return () => listeners.delete(fn); };
const emit = () => listeners.forEach((fn) => { try { fn(); } catch { /* ignore */ } });

function owner() { const u = auth.currentUser(); return u ? String(u.id || u.email || '') : ''; }
function read() {
  try {
    const s = JSON.parse(localStorage.getItem(KEY) || 'null');
    return s && s.owner === owner() ? s : null;    // كاش حساب آخر لا يُستخدم
  } catch { return null; }
}
function write(patch) {
  const next = { ...(read() || { owner: owner(), until: 0, intro: true }), ...patch, owner: owner() };
  try { localStorage.setItem(KEY, JSON.stringify(next)); } catch { /* ignore */ }
  emit();
}

/** Plus فعّال الآن؟ التاريخ يُفحص محلياً كل مرة، فينتهي في موعده حتى بدون إنترنت. */
export function isPlus() {
  if (!auth.isLoggedIn()) return false;
  const s = read();
  return !!s && Number(s.until) > Date.now();
}
export const until = () => (isPlus() ? Number(read().until) : 0);
export const activatedAt = () => (isPlus() ? Number(read().from) || 0 : 0);
export function expiredAt() {
  if (!auth.isLoggedIn()) return 0;
  const s = read();
  return s && Number(s.until) > 0 && Number(s.until) <= Date.now() ? Number(s.until) : 0;
}
export function daysLeft() { return isPlus() ? Math.max(1, Math.ceil((until() - Date.now()) / 86400000)) : 0; }
/** يحق لهذا الحساب عرض الـ3 أشهر؟ (غير معروف = نعم؛ الخادم له الكلمة الأخيرة) */
export function introEligible() { const s = read(); return !s || s.intro !== false; }

/** «من 2026/11/02 إلى 2027/05/02» (أو «حتى …» إن لم يُعرف تاريخ التفعيل). */
export function periodText() {
  const to = until();
  if (!to) return '';
  const from = activatedAt();
  return from ? `من ${formatDate(from)} إلى ${formatDate(to)}` : `حتى ${formatDate(to)}`;
}

export const maxHeight = () => (isPlus() ? Infinity : FREE_MAX_HEIGHT);

export function formatDate(ms) {
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}/${p(d.getMonth() + 1)}/${p(d.getDate())}`;
}
export const iqd = (n) => `${Number(n).toLocaleString('en-US')} د.ع`;

/** سطر السعر في بطاقة Plus. */
export function priceLine() {
  if (!salesEnabled()) return '';
  const { intro, standard } = PLANS;
  return introEligible()
    ? `${iqd(intro.price)} لأول ${intro.months} أشهر · ثم ${iqd(standard.price)} كل ${standard.months} أشهر`
    : `${iqd(standard.price)} كل ${standard.months} أشهر`;
}

export function cleanCode(raw) {
  const s = String(raw || '').trim().toUpperCase().replace(/\s+/g, '');
  return /^[A-Z0-9-]{6,32}$/.test(s) ? s : '';
}

export function whatsappUrl(methodLabel) {
  const std = PLANS.standard, intro = PLANS.intro;
  const plan = introEligible()
    ? `${intro.months} أشهر - ${iqd(intro.price)} (عرض أول اشتراك)`
    : `${std.months} أشهر - ${iqd(std.price)}`;
  const text = `اريد الاشتراك في Plus Cigram عن طريق «${methodLabel}»\nالباقة: ${plan}`;
  return `https://wa.me/${CONFIG.whatsapp}?text=${encodeURIComponent(text)}`;
}

const MESSAGES = {
  invalid: 'الكود غير صحيح. تأكد منه وحاول مرة ثانية.',
  used: 'هذا الكود مستخدم من قبل.',
  expired: 'انتهت صلاحية هذا الكود. تواصل معنا للحصول على كود جديد.',
  rate: 'محاولات كثيرة. انتظر قليلاً ثم حاول.',
  login: 'سجّل الدخول أولاً ثم فعّل الكود.',
  na: 'خدمة التفعيل غير متاحة بعد. تواصل معنا على واتساب.',
  intro: 'هذا كود عرض الـ3 أشهر وهو لأول اشتراك فقط، وحسابك استخدم عرضاً سابقاً. تواصل معنا لكود 6 أشهر.',
  network: 'تعذّر الاتصال. تحقق من الإنترنت وحاول مرة ثانية.',
};
export const messageFor = (k) => MESSAGES[k] || MESSAGES.network;

async function call(method, path, body) {
  const base = String(CONFIG.plusApi || '').replace(/\/+$/, '');
  if (!base) return { status: 404, json: {} };
  const headers = { Accept: 'application/json', Authorization: `Bearer ${auth.token()}` };
  if (body) headers['Content-Type'] = 'application/json';
  const res = await fetch(base + path, { method, headers, body: body ? JSON.stringify(body) : undefined, credentials: 'omit' });
  let json = {};
  try { json = await res.json(); } catch { json = {}; }
  return { status: res.status, json };
}

/** يسأل الخادم عن الخطة (متقطع؛ عند الفشل يبقى آخر جواب محفوظ). */
export async function refresh(force = false) {
  if (!auth.isLoggedIn() || busy || (!force && Date.now() - lastAt < GAP_MS)) return;
  busy = true;
  try {
    const r = await call('GET', '/plus/status');
    if (r.status === 200 && r.json.ok) {
      lastAt = Date.now();
      const isP = r.json.plan === 'plus';
      write({ until: isP ? Number(r.json.plus_until) || 0 : 0, from: isP ? Number(r.json.activated_at) || 0 : 0, intro: r.json.intro_eligible !== false });
    }
  } catch { /* بدون إنترنت: يبقى المحفوظ */ } finally { busy = false; }
}

/** يرجع { ok:true, until, months } أو { ok:false, error: مفتاح رسالة }. */
export async function redeem(raw) {
  const code = cleanCode(raw);
  if (!code) return { ok: false, error: 'invalid' };
  if (!auth.isLoggedIn()) return { ok: false, error: 'login' };
  try {
    const r = await call('POST', '/plus/redeem', { code });
    const j = r.json || {};
    if (r.status === 200 && j.ok && Number(j.plus_until) > Date.now()) {
      lastAt = Date.now();
      write({ until: Number(j.plus_until), from: Number(j.activated_at) || Date.now(), intro: false });
      return { ok: true, until: Number(j.plus_until), months: Number(j.months) || PLANS.standard.months };
    }
    if (r.status === 404 || r.status === 501) return { ok: false, error: 'na' };
    if (r.status === 401 || r.status === 403) return { ok: false, error: 'login' };
    if (r.status === 429 || j.error === 'rate_limited') return { ok: false, error: 'rate' };
    if (j.error === 'code_used') return { ok: false, error: 'used' };
    if (j.error === 'code_expired') return { ok: false, error: 'expired' };
    if (j.error === 'intro_not_eligible') { write({ intro: false }); return { ok: false, error: 'intro' }; }
    if (r.status >= 400 && r.status < 500) return { ok: false, error: 'invalid' };
  } catch { /* الشبكة */ }
  return { ok: false, error: 'network' };
}

/** ارتفاع القناة من الكتالوج: رقم (1080) أو نص ("4K", "UHD", "FHD") — نفس منطق التطبيق (qualityOf). */
export function qualityOf(o) {
  let v = o.quality ?? o.height ?? o.resolution ?? o.res;
  if (typeof v === 'number') return v;
  if (typeof v === 'string') {
    const s = v.trim().toLowerCase();
    if (/4k|uhd|2160/.test(s)) return 2160;
    if (/2k|1440/.test(s)) return 1440;
    if (/1080|fhd/.test(s)) return 1080;
    if (/720/.test(s)) return 720;
    if (/480/.test(s)) return 480;
  }
  return 0;
}

// تغيّر الحساب: نعيد السؤال عن الخطة، ونُعلم المشاهدين
auth.onAuth(() => { lastAt = 0; emit(); if (auth.isLoggedIn()) refresh(true); });

/** مجاني: لا يُختار مستوى أعلى من 720p. أقرب مستوى ضمن الحد (أو الأدنى إن كانت كلها أعلى). Plus: بلا حد. */
export function levelCap(levels, maxH) {
  if (!levels || !levels.length || !isFinite(maxH)) return -1;
  let pick = -1, low = 0;
  levels.forEach((l, i) => {
    const h = l.height || 0;
    if (h < (levels[low].height || 0)) low = i;
    if (h <= maxH && (pick < 0 || h > (levels[pick].height || 0))) pick = i;
  });
  return pick >= 0 ? pick : low;
}
