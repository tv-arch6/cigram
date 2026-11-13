// «الدعم والتواصل»: نفس محادثة التطبيق (cigram-ads-api: محادثة واحدة لكل حساب).
// المحادثة لثلاثة مواضيع فقط: أسعار Cigram Plus، مزاياه، والمشاكل. النص والصور والفيديو والملفات تُرفع من الموقع بنفس بروتوكول التطبيق؛
// وتظهر الوسائط في الموقع كملف مغلق (تُفتح من التطبيق). الرسائل الصوتية متوقفة.
import { CONFIG } from './config.js';
import * as auth from './auth.js';

/** اختصارات فوق صندوق الكتابة: أيقونة خطية + عنوان + الجملة التي تُكتب (لا يُرسل شيء بدون ضغط المستخدم). */
export const TOPICS = [
  { icon: 'tag', label: 'أسعار Cigram Plus', text: 'مرحباً، ما أسعار اشتراك Cigram Plus والمدد المتوفرة؟' },
  { icon: 'crown', label: 'مزايا Cigram Plus', text: 'ما المزايا التي أحصل عليها مع Cigram Plus؟' },
  { icon: 'wallet', label: 'طرق الدفع', text: 'ما طرق الدفع المتاحة لاشتراك Cigram Plus؟' },
  { icon: 'alert', label: 'مشكلة في التشغيل', text: 'أواجه مشكلة في تشغيل هذا العنوان: ' },
  { icon: 'user', label: 'مشكلة في الحساب', text: 'أواجه مشكلة في حسابي: ' },
];

const base = () => String(CONFIG.supportApi || '').replace(/\/+$/, '');

async function api(path, { method = 'GET', body, raw } = {}) {
  const headers = { Accept: 'application/json', Authorization: 'Bearer ' + auth.token() };
  if (raw) headers['Content-Type'] = 'application/octet-stream';
  else if (body) headers['Content-Type'] = 'application/json; charset=utf-8';
  let res;
  try {
    res = await fetch(base() + path, { method, headers, body: raw || (body ? JSON.stringify(body) : undefined), cache: 'no-store' });
  } catch (e) {
    return { ok: false, status: 0, data: {}, error: 'network' };            // لا اتصال: يُعاد المحاولة بهدوء بلا نص خطأ
  }
  const data = await res.json().catch(() => ({}));
  return { ok: res.ok && data.ok !== false, status: res.status, data, error: data.error || '' };
}

/** يفتح المحادثة: يوافق على سياسة التواصل مرة واحدة إن طلبها الخادم، ثم يرجع آخر الرسائل. */
export async function open() {
  let t = await api('/chat/thread');
  if (!t.ok && t.error === 'consent_required') {
    const cfg = await api('/ads/config').catch(() => null);
    const version = Math.max(1, Number(cfg && cfg.data && cfg.data.settings && cfg.data.settings.policies_version) || 1);
    await api('/ads/consent', { method: 'POST', body: { version } });
    t = await api('/chat/thread');
  }
  if (!t.ok) return { ok: false, status: t.status, messages: [] };
  const m = await api('/chat/messages?limit=40');
  return { ok: m.ok, banned: !!(t.data.thread && t.data.thread.banned), messages: normalize(m.data.messages) };
}

export async function after(seq) {
  const m = await api('/chat/messages?after=' + (Number(seq) || 0) + '&limit=50');
  return m.ok ? normalize(m.data.messages) : [];
}

export async function send(text) {
  const clean = String(text || '').trim().slice(0, 2000);
  if (!clean) return null;
  const client_id = 'w' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
  const r = await api('/chat/send', { method: 'POST', body: { client_id, type: 'text', text: clean } });
  if (!r.ok) return null;
  return normalize([r.data.message, r.data.auto].filter(Boolean));
}

export function read(seq) { return api('/chat/read', { method: 'POST', body: { upto: Number(seq) || 0 } }).catch(() => null); }

/** رسالة الخادم ← { seq, mine, text, ts, kind }. الصورة والفيديو والصوت تظهر كملف مغلق (kind) بلا محتوى. */
export function normalize(list) {
  const out = [];
  for (const o of Array.isArray(list) ? list : []) {
    if (!o || o.deleted) continue;
    const type = String(o.type || 'text');
    out.push({
      seq: Number(o.seq) || 0,
      mine: String(o.from || 'user') === 'user',
      text: String(o.text || ''),
      ts: Number(o.ts) || Date.now(),
      name: o.media && o.media.name ? String(o.media.name) : '',
      size: o.media && Number(o.media.size) > 0 ? Number(o.media.size) : 0,
      kind: type === 'text' ? '' : type === 'image' ? 'صورة مغلقة' : type === 'video' ? 'فيديو مغلق' : type === 'voice' || type === 'audio' ? 'رسالة صوتية' : 'ملف',
    });
  }
  return out.sort((a, b) => a.seq - b.seq);
}

// ------------------------------------------------------------------ رفع الوسائط (نفس بروتوكول التطبيق)
// init ← part (قطع 1MB) ← complete ← send. الحالة تُحفظ في كائن الرفع نفسه: «إعادة المحاولة» تكمل من حيث توقف.

export const MAX_BYTES = 25 * 1024 * 1024;                     // حد الخادم (CHAT_MAX_FILE)
export const ACCEPT = 'image/jpeg,image/png,image/gif,image/webp,video/mp4,video/webm,application/pdf,application/zip,.doc';
const PART_RETRIES = 3;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** نوع الرسالة: صورة / فيديو / ملف (الخادم يتحقق من محتوى الملف نفسه لا من اسمه). */
export function kindOf(file) {
  const t = String((file && file.type) || '').toLowerCase();
  return t.indexOf('image/') === 0 ? 'image' : t.indexOf('video/') === 0 ? 'video' : 'file';
}

/** '' = صالح؛ وإلا سبب الرفض قبل أي اتصال: empty | too_large. */
export function check(file) {
  const n = Number(file && file.size) || 0;
  return n < 1 ? 'empty' : n > MAX_BYTES ? 'too_large' : '';
}

export function sizeLabel(bytes) {
  const n = Number(bytes) || 0;
  return n >= 1048576 ? (n / 1048576).toFixed(n >= 10485760 ? 0 : 1) + ' MB' : Math.max(1, Math.round(n / 1024)) + ' KB';
}

/** كائن رفع جديد لملف. يُمرَّر لـ run() ويُعاد تمريره نفسه عند إعادة المحاولة. */
export function createUpload(file) {
  return {
    file, kind: kindOf(file), name: String(file.name || 'ملف').slice(0, 80), size: Number(file.size) || 0,
    client_id: 'w' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7),
    id: '', partSize: 0, parts: 0, complete: false, w: 0, h: 0, dur: 0, probed: false,
  };
}

/** أبعاد الصورة ومدة الفيديو (للعرض فقط): من المتصفح إن أمكن، وبلا أي أثر إن لم يمكن. */
async function probe(up) {
  if (up.probed) return;
  up.probed = true;
  try {
    if (up.kind === 'image' && typeof createImageBitmap === 'function') {
      const bmp = await createImageBitmap(up.file);
      up.w = bmp.width; up.h = bmp.height;
      if (bmp.close) bmp.close();
    } else if (up.kind === 'video' && typeof document !== 'undefined' && typeof URL !== 'undefined' && URL.createObjectURL) {
      await new Promise((resolve) => {
        const v = document.createElement('video');
        const url = URL.createObjectURL(up.file);
        const done = () => { try { URL.revokeObjectURL(url); } catch (e) { /* لا شيء */ } resolve(); };
        v.preload = 'metadata';
        v.onloadedmetadata = () => { up.w = v.videoWidth || 0; up.h = v.videoHeight || 0; up.dur = Math.round((v.duration || 0) * 1000); done(); };
        v.onerror = done;
        setTimeout(done, 2500);
        v.src = url;
      });
    }
  } catch (e) { /* العرض فقط */ }
}

const upPath = (up, tail) => '/chat/upload/' + tail + (tail === 'init' ? '' : '?id=' + encodeURIComponent(up.id));

async function serverHas(up) {
  const st = await api(upPath(up, 'status'));
  if (!st.ok) return null;
  const have = new Set();
  for (const n of Array.isArray(st.data.have) ? st.data.have : []) if (Number.isInteger(n) && n >= 0 && n < up.parts) have.add(n);
  return have;
}

async function putPart(up, n) {
  const start = n * up.partSize;
  const blob = up.file.slice(start, Math.min(up.size, start + up.partSize));
  let last = { ok: false, error: 'network', status: 0 };
  for (let attempt = 0; attempt < PART_RETRIES; attempt++) {
    last = await api(upPath(up, 'part') + '&n=' + n, { method: 'PUT', raw: blob });
    if (last.ok || last.error === 'already_complete') return { ok: true, bytes: blob.size };
    const retryable = last.status === 0 || last.status === 408 || last.status === 429 || last.status >= 500;
    if (!retryable) break;                                       // نوع غير مدعوم/حجم خاطئ: إعادة الإرسال لن تغيّر شيئاً
    await sleep(350 * (attempt + 1));
  }
  return { ok: false, reason: last.error || 'network', status: last.status };
}

async function sendMissing(up, have, report, cancelled) {
  let doneBytes = 0;
  for (let n = 0; n < up.parts; n++) if (have.has(n)) doneBytes += Math.min(up.partSize, up.size - n * up.partSize);
  report(doneBytes);
  for (let n = 0; n < up.parts; n++) {
    if (have.has(n)) continue;
    if (cancelled && cancelled()) return { ok: false, reason: 'cancelled' };       // «إلغاء» أثناء الرفع يوقف عند القطعة التالية
    const r = await putPart(up, n);
    if (!r.ok) return r;
    have.add(n);
    doneBytes += r.bytes;
    report(doneBytes);
  }
  return { ok: true };
}

/**
 * يرفع الملف ثم يرسله رسالةً. يعيد { ok:true, messages } أو { ok:false, reason, status } — لا يرمي أبداً.
 * onProgress(0..100) · signal: AbortSignal للإلغاء. عند الفشل يبقى `up` صالحاً لإعادة المحاولة (يكمل القطع الناقصة فقط).
 */
export async function run(up, { onProgress, signal } = {}) {
  const report = (bytes) => { if (onProgress) onProgress(Math.min(99, Math.floor((bytes / Math.max(1, up.size)) * 100))); };
  const cancelled = () => !!(signal && signal.aborted);
  const bad = (reason, status) => ({ ok: false, reason: reason || 'network', status: status || 0 });
  const why = check(up.file);
  if (why) return bad(why);

  if (!up.complete) {
    let have = null;
    if (up.id) {                                                  // إعادة محاولة: ما الذي وصل الخادم فعلاً؟
      have = await serverHas(up);
      if (!have) { up.id = ''; up.parts = 0; }                    // انتهت الجلسة على الخادم: جلسة جديدة
    }
    if (!up.id) {
      await probe(up);
      const init = await api('/chat/upload/init', { method: 'POST', body: { name: up.name, kind: up.kind, size: up.size, dur: up.dur, w: up.w, h: up.h } });
      if (!init.ok || !init.data.media_id) return bad(init.status === 413 ? 'too_large' : init.error || 'init', init.status);
      up.id = String(init.data.media_id);
      up.partSize = Math.max(64 * 1024, Number(init.data.part_size) || 1024 * 1024);
      up.parts = Math.max(1, Number(init.data.parts) || Math.ceil(up.size / up.partSize));
      have = new Set();
    }
    if (cancelled()) return bad('cancelled');
    let r = await sendMissing(up, have, report, cancelled);
    if (!r.ok) return bad(r.reason, r.status);
    if (cancelled()) return bad('cancelled');
    let done = await api(upPath(up, 'complete'), { method: 'POST', body: {} });
    if (!done.ok && done.error === 'missing_parts') {             // قطعة ضاعت: اسأل الخادم أيها ناقصة وأعدها مرة واحدة
      const got = await serverHas(up);
      if (got) {
        r = await sendMissing(up, got, report, cancelled);
        if (!r.ok) return bad(r.reason, r.status);
        done = await api(upPath(up, 'complete'), { method: 'POST', body: {} });
      }
    }
    if (!done.ok) return bad(done.error || 'complete', done.status);
    up.complete = true;
  }
  if (cancelled()) return bad('cancelled');
  // client_id ثابت: لو وصل الإرسال للخادم وضاع الرد، إعادة المحاولة لا تكرّر الرسالة
  const sent = await api('/chat/send', { method: 'POST', body: { client_id: up.client_id, type: up.kind, media_id: up.id } });
  if (!sent.ok) return bad(sent.error || 'send', sent.status);
  if (onProgress) onProgress(100);
  return { ok: true, messages: normalize([sent.data.message, sent.data.auto].filter(Boolean)) };
}
