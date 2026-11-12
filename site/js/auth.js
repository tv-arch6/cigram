// حساب المشاهد: نفس خادم حسابات تطبيق سيكرام (cigram-auth-api)، فالحساب واحد في التطبيق والموقع.
// الجلسة (token) تُحفظ في هذا المتصفح، وتُتحقق من الخادم عند فتح الموقع.
import { CONFIG } from './config.js';
import { TERMS_VERSION } from './terms.js';
import { loadScript } from './util.js';
import * as store from './store.js';

const KEY = 'cigram.auth.v1';
const listeners = new Set();
let session = read();

function read() {
  try {
    const s = JSON.parse(localStorage.getItem(KEY) || 'null');
    if (!s || typeof s.token !== 'string' || !s.user) return null;
    if (s.expires_at && Date.parse(s.expires_at) < Date.now()) return null;
    return s;
  } catch {
    return null;
  }
}

function write(next) {
  session = next;
  try {
    if (next) localStorage.setItem(KEY, JSON.stringify(next));
    else localStorage.removeItem(KEY);
  } catch { /* تصفح خاص: تبقى الجلسة حتى إغلاق الصفحة */ }
  listeners.forEach((fn) => { try { fn(currentUser()); } catch { /* ignore */ } });
}

export const currentUser = () => (session ? session.user : null);
export const isLoggedIn = () => !!session;
export const token = () => (session ? session.token : '');
export function onAuth(fn) { listeners.add(fn); return () => listeners.delete(fn); }

/** هل يحتاج المستخدم أن يوافق على الشروط (لم يوافق أبداً، أو وافق على نسخة أقدم)؟ */
export const needsTerms = (user = currentUser()) => !!user && user.terms_version !== TERMS_VERSION;

window.addEventListener('storage', (e) => {
  if (e.key !== KEY) return;
  session = read();
  listeners.forEach((fn) => { try { fn(currentUser()); } catch { /* ignore */ } });
});

// ---------------------------------------------------------------- الاتصال بالخادم
async function api(path, { method = 'GET', body, auth = false } = {}) {
  const headers = {};
  if (body) headers['Content-Type'] = 'application/json';
  if (auth && session) headers.Authorization = `Bearer ${session.token}`;
  let res;
  try {
    res = await fetch(CONFIG.authApi.replace(/\/+$/, '') + path, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
      credentials: 'omit',
    });
  } catch {
    throw Object.assign(new Error('تعذّر الاتصال بالخادم. تحقق من الإنترنت وحاول مرة أخرى.'), { status: 0 });
  }
  let data = {};
  try { data = await res.json(); } catch { data = {}; }
  if (!res.ok || data.success === false) {
    const err = new Error(data.message || 'حدث خطأ، حاول مرة أخرى.');
    err.status = res.status;
    err.code = data.code || '';
    if (res.status === 401 && auth) write(null); // الجلسة انتهت أو أُلغيت من جهاز آخر
    throw err;
  }
  return data;
}

function startSession(data) {
  write({ token: data.token, expires_at: data.expires_at || '', user: data.user });
  if (data.user && data.user.name) store.setName(data.user.name); // التحية في الرئيسية
  return data.user;
}

function updateUser(user) {
  if (!session || !user) return;
  write({ ...session, user: { ...session.user, ...user } });
}

// ---------------------------------------------------------------- العمليات
export async function login(email, password) {
  const data = await api('/login', { method: 'POST', body: { email, password } });
  return startSession(data);
}

export async function register({ name, email, password, gender }) {
  const data = await api('/register', {
    method: 'POST',
    body: { name, email, password, gender: gender || 'unspecified', accept_terms: true, terms_version: TERMS_VERSION },
  });
  return startSession(data);
}

/** credential: رمز Google. acceptTerms: يلزم فقط عند إنشاء حساب جديد (الخادم يرجع terms_required). */
export async function googleSignIn(credential, acceptTerms) {
  const body = { credential };
  if (acceptTerms) { body.accept_terms = true; body.terms_version = TERMS_VERSION; }
  const data = await api('/google', { method: 'POST', body });
  return { user: startSession(data), created: !!data.created };
}

export async function refresh() {
  if (!session) return null;
  try {
    const data = await api('/me', { auth: true });
    updateUser(data.user);
  } catch { /* بدون إنترنت نبقي آخر بيانات محفوظة؛ 401 يسجل الخروج تلقائياً */ }
  return currentUser();
}

export async function logout() {
  const token = session && session.token;
  write(null);
  if (!token) return;
  try {
    await fetch(CONFIG.authApi.replace(/\/+$/, '') + '/logout', { method: 'POST', headers: { Authorization: `Bearer ${token}` } });
  } catch { /* الجلسة حُذفت من هذا المتصفح على أي حال */ }
}

export async function acceptTerms() {
  const data = await api('/terms/accept', { method: 'POST', body: { terms_version: TERMS_VERSION }, auth: true });
  updateUser(data.user);
  return currentUser();
}

export async function setPassword(password) {
  const data = await api('/password/set', { method: 'POST', body: { password }, auth: true });
  updateUser(data.user);
  return currentUser();
}

export async function deleteAccount({ password, confirm }) {
  await api('/account/delete', { method: 'POST', body: password ? { password } : { confirm }, auth: true });
  write(null);
}

// ---------------------------------------------------------------- Google Identity Services
export const googleEnabled = () => !!String(CONFIG.googleClientId || '').trim();

let gisReady = null;
let gisHandler = null;

/** يجهّز مكتبة Google مرة واحدة، والرد يذهب للنموذج الظاهر حالياً. */
export function prepareGoogle() {
  if (!googleEnabled()) return Promise.reject(new Error('google disabled'));
  if (!gisReady) {
    gisReady = loadScript('https://accounts.google.com/gsi/client').then(() => {
      const id = window.google && window.google.accounts && window.google.accounts.id;
      if (!id) throw new Error('gsi missing');
      id.initialize({
        client_id: CONFIG.googleClientId.trim(),
        callback: (response) => { if (gisHandler && response && response.credential) gisHandler(response.credential); },
        ux_mode: 'popup',
        auto_select: false,
        cancel_on_tap_outside: true,
        context: 'signin',
      });
      return id;
    });
    gisReady.catch(() => { gisReady = null; });
  }
  return gisReady;
}

export async function renderGoogleButton(el, { text = 'signin_with', onCredential }) {
  const id = await prepareGoogle();
  gisHandler = onCredential;
  el.innerHTML = '';
  id.renderButton(el, {
    type: 'standard',
    theme: 'filled_black',
    size: 'large',
    shape: 'pill',
    text,
    logo_alignment: 'center',
    locale: 'ar',
    width: Math.max(220, Math.min(400, Math.round(el.clientWidth || 320))),
  });
}
