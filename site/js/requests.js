// «أرسل طلبك أو مشكلتك»: نفس نظام الطلبات في تطبيق سيكرام (CigramRequests).
// محلي أولاً: الطلب يُحفظ في المتصفح فوراً ثم يُرسل للخادم (POST /requests)، والحالة تأتي من الإدارة
// (GET /requests/mine) أو تلقائياً عند ظهور العنوان في الكتالوج.
//   pending      قيد المراجعة
//   in_progress  «المدير وافق، سيتم إضافته قريبًا، اصبر»
//   added        «تم رفعها، انقر هنا» ← يفتح الفيلم/المسلسل
//   unavailable  غير متوفر
import { CONFIG } from './config.js';
import * as auth from './auth.js';

const KEY = 'cigram_requests_v1';
const CLIENT = 'cigram_client_id';
export const KINDS = { movie: 'فيلم', series: 'مسلسل', channel: 'قناة' };

function read() { try { const a = JSON.parse(localStorage.getItem(KEY) || '[]'); return Array.isArray(a) ? a : []; } catch { return []; } }
function write(a) { try { localStorage.setItem(KEY, JSON.stringify(a.slice(0, 60))); } catch { /* التخزين ممتلئ: يبقى الطلب في هذه الجلسة */ } }

export function clientId() {
  let id = '';
  try { id = localStorage.getItem(CLIENT) || ''; } catch { /* ignore */ }
  if (!id) {
    id = 'web-' + Array.from(crypto.getRandomValues(new Uint8Array(12)), (b) => b.toString(16).padStart(2, '0')).join('');
    try { localStorage.setItem(CLIENT, id); } catch { /* ignore */ }
  }
  return id;
}

export const list = () => read();
export const kindLabel = (k) => KINDS[k] || KINDS.movie;

/** يحذف الحركات والهمزات والفواصل حتى يتطابق «الجوكر» مع «جوكر 2019». */
export function norm(s) {
  return String(s || '').toLowerCase()
    .replace(/[ً-ٰٟـ]/g, '').replace(/[أإآ]/g, 'ا').replace(/ى/g, 'ي').replace(/ة/g, 'ه')
    .replace(/^ال/, '').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
}

export function add(kind, title) {
  const t = String(title || '').trim().slice(0, 120);
  if (t.length < 2) return null;
  const a = read();
  const dup = a.find((r) => r.kind === kind && norm(r.title) === norm(t) && r.status !== 'unavailable');
  if (dup) return dup;
  const r = { local_id: 'r' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6), id: '', kind: KINDS[kind] ? kind : 'movie', title: t, status: 'pending', admin_note: '', created_at: Date.now() };
  a.unshift(r);
  write(a);
  return r;
}

export function remove(localId) { write(read().filter((r) => r.local_id !== localId)); }

/** ما يظهر تحت اسم الطلب، مع هل يفتح عنواناً. */
export function statusLine(r) {
  if (r.status === 'added' && r.match_id) return { text: 'تم رفعها، ', link: 'انقر هنا', href: `#/title/${encodeURIComponent(r.match_id)}` };
  if (r.status === 'added') return { text: 'تمت الإضافة' };
  if (r.status === 'in_progress') return { text: 'المدير وافق، سيتم إضافته قريبًا، اصبر' };
  if (r.status === 'unavailable') return { text: 'غير متوفر حالياً' + (r.admin_note ? ' · ' + r.admin_note : '') };
  return { text: 'قيد المراجعة' };
}

/** يطابق الطلبات مع الكتالوج: عنوان بنفس الاسم = «تمت الإضافة» حتى قبل أن تغيّر الإدارة الحالة. */
export function matchCatalog(cat) {
  if (!cat || !Array.isArray(cat.items)) return false;
  const a = read();
  let changed = false;
  for (const r of a) {
    if (r.kind === 'channel' || (r.status === 'added' && r.match_id)) continue;
    const want = norm(r.title);
    if (want.length < 2) continue;
    const hit = cat.items.find((i) => (r.kind === 'series' ? i.type === 'series' : i.type !== 'series') && norm(i.title) === want);
    if (hit) { r.status = 'added'; r.match_id = hit.id; r.match_title = hit.title; changed = true; }
  }
  if (changed) write(a);
  return changed;
}

async function api(path, { method = 'GET', body } = {}) {
  const headers = { Accept: 'application/json' };
  if (body) headers['Content-Type'] = 'application/json; charset=utf-8';
  const t = auth.token();
  if (t) headers.Authorization = 'Bearer ' + t;
  const res = await fetch(String(CONFIG.userApi || '').replace(/\/+$/, '') + path, { method, headers, body: body ? JSON.stringify(body) : undefined, cache: 'no-store' });
  const data = await res.json().catch(() => ({}));
  return { status: res.status, data };
}

let running = null;
/** يرسل ما لم يُرسل ويجلب حالات الطلبات. يرجع true إذا تغيّر شيء. بلا استثناءات: بدون إنترنت يبقى كل شيء محلياً. */
export function sync() {
  if (running) return running;
  running = (async () => {
    let changed = false;
    try {
      const a = read();
      const user = auth.currentUser();
      for (const r of a) {
        if (r.id) continue;
        const { data } = await api('/requests', { method: 'POST', body: { kind: r.kind, title: r.title, note: '', client_id: clientId(), app_version: 'web', user_name: user ? user.name || '' : '' } });
        const id = data && (data.id || (data.request && data.request.id));
        if (id) { r.id = String(id); changed = true; }
      }
      if (changed) write(a);
      const ids = a.filter((r) => r.id && !(r.status === 'added' && r.match_id)).map((r) => r.id);
      if (ids.length) {
        const { data } = await api('/requests/mine?ids=' + encodeURIComponent(ids.join(',')));
        for (const s of (data && data.requests) || []) {
          const r = a.find((x) => x.id === String(s.id));
          if (!r || (r.status === 'added' && s.status !== 'added')) continue;
          if (r.status !== s.status || (r.admin_note || '') !== (s.admin_note || '')) { r.status = s.status || 'pending'; r.admin_note = s.admin_note || ''; changed = true; }
        }
        if (changed) write(a);
      }
    } catch { /* بدون إنترنت */ }
    running = null;
    return changed;
  })();
  return running;
}
