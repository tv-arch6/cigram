// «الدعم والتواصل»: نفس محادثة التطبيق (cigram-ads-api: محادثة واحدة لكل حساب).
// المحادثة لثلاثة مواضيع فقط: أسعار Cigram Plus، مزاياه، والمشاكل. نص فقط في الموقع؛ الوسائط تُفتح من التطبيق.
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

async function api(path, { method = 'GET', body } = {}) {
  const headers = { Accept: 'application/json', Authorization: 'Bearer ' + auth.token() };
  if (body) headers['Content-Type'] = 'application/json; charset=utf-8';
  const res = await fetch(base() + path, { method, headers, body: body ? JSON.stringify(body) : undefined, cache: 'no-store' });
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
      kind: type === 'text' ? '' : type === 'image' ? 'صورة مغلقة' : type === 'video' ? 'فيديو مغلق' : type === 'voice' || type === 'audio' ? 'رسالة صوتية' : 'ملف',
    });
  }
  return out.sort((a, b) => a.seq - b.seq);
}
