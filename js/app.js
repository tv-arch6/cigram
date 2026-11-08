import { CONFIG } from './config.js';
import * as store from './store.js';
import { loadCatalog, loadCachedCatalog, findEpisode, nextEpisode, allEpisodes } from './catalog.js';
import { renderSearch, renderTitle, renderAccount } from './views.js';
import { renderHome, renderSection } from './home-view.js';
import { mountPlayer, isEmbedUrl } from './player.js';
import { paintNav, setAvatar } from './nav.js';
import * as layout from './layout.js';
import { paintMeta } from './theme.js';
import { sym } from './icons.js';
import { $, $$, esc, toast, fmtClock } from './util.js';
import { alternateR2 } from './cigram.js';
import { renderTerms } from './terms.js';
import { renderChannels } from './channels-view.js';
import * as auth from './auth.js';
import * as plus from './plus.js';
import { renderPlus, schedulePromo } from './plus-view.js';
import './landing.js';

const view = document.getElementById('view');
let cat = null;
let current = { cleanup: null };
let firstRender = true;
let deferredInstall = null;

document.getElementById('brandName').textContent = CONFIG.siteName;
const appleTitle = document.querySelector('meta[name="apple-mobile-web-app-title"]');
if (appleTitle) appleTitle.setAttribute('content', CONFIG.siteName);

// ------------------------------------------------------------------ routing
function parseHash() {
  const raw = location.hash.replace(/^#\/?/, '');
  const [path, qs = ''] = raw.split('?');
  const parts = path.split('/').filter(Boolean).map(decodeURIComponent);
  return { name: parts[0] || 'home', params: parts.slice(1), query: new URLSearchParams(qs) };
}

const NAV_OF = { home: 'home', title: 'home', section: 'home', channels: 'channels', search: 'search', account: 'account', terms: 'account', plus: 'account' };
const TITLES = { channels: 'القنوات', search: 'البحث', account: 'حسابي', terms: 'الشروط والخصوصية', plus: 'Cigram Plus' };
// v2: صفحات تبقى حيّة عند التنقل (مثل صفحات التطبيق): الرجوع إليها فوري وبنفس مكان التمرير، بلا إعادة رسم.
const KEEP = new Set(['home', 'search']);
const kept = new Map();   // name -> { el, result, sig, y, hash }
let tempEl = null;
let currentNav = '';
// صفحات لا تحتاج كتالوج الأفلام: تفتح حتى لو تأخر تحميله أو فشل
const STANDALONE = new Set(['channels', 'terms', 'plus']);

/** الوعاء الذي تُرسم فيه صفحة: صفحات KEEP لها وعاؤها الدائم، والباقي وعاء مؤقت واحد. */
function pageEl(name) {
  if (KEEP.has(name)) {
    let k = kept.get(name);
    if (!k) { k = { el: document.createElement('div'), result: null, sig: '', y: 0 }; k.el.className = 'pg pg-' + name; view.appendChild(k.el); kept.set(name, k); }
    return k.el;
  }
  if (!tempEl || !tempEl.isConnected) { tempEl = document.createElement('div'); tempEl.className = 'pg pg-temp'; view.appendChild(tempEl); }
  return tempEl;
}

function hideOthers(name) {
  for (const [n, k] of kept) {
    if (n === name) continue;
    if (!k.el.hidden) { k.y = window.scrollY; k.el.hidden = true; if (k.result && k.result.pause) k.result.pause(); }
  }
  if (tempEl && (KEEP.has(name) || !name)) { tempEl.hidden = true; tempEl.innerHTML = ''; }
}

/** keepScroll: إعادة رسم الصفحة نفسها بعد تحديث المحتوى بدون أن تقفز للأعلى. */
function route({ keepScroll = false, force = false } = {}) {
  const y = window.scrollY;
  const ctx = parseHash();
  const nav = NAV_OF[ctx.name] || '';
  $$('[data-nav]').forEach((a) => {
    if (a.dataset.nav === nav) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  });
  currentNav = nav;
  paintNav(nav);
  document.title = TITLES[ctx.name] ? `${TITLES[ctx.name]} - ${CONFIG.siteName}` : CONFIG.siteName;
  document.documentElement.dataset.page = ctx.name;

  // نفس الصفحة برابط آخر (مثل #/channels → #/channels/all): تتحدث في مكانها، فلا تتوقف القناة المعروضة
  if (current.name === ctx.name && current.update) {
    try { current.update(ctx); return; } catch (e) { console.warn(e); }
  }

  const fromName = current.name;
  if (current.cleanup && !KEEP.has(current.name)) { try { current.cleanup(); } catch { /* ignore */ } }
  current = { cleanup: null };
  document.body.classList.remove('watching');
  paintMeta();

  if (!cat && !STANDALONE.has(ctx.name)) {
    hideOthers('');
    const el = pageEl('_boot');
    el.hidden = false;
    el.innerHTML = bootFailed ? BOOT_ERROR : '<div class="boot" aria-label="جار التحميل"><div class="spinner"></div></div>';
    return;
  }

  // صفحة محفوظة حيّة: تظهر كما تركها المشاهد (لا إعادة رسم، لا تقطيع)
  if (KEEP.has(ctx.name) && !force) {
    const k = kept.get(ctx.name);
    const sameSearch = ctx.name !== 'search' || !ctx.query.toString() || k && k.hash === location.hash;
    if (k && k.result && k.sig === (cat && cat.signature) && sameSearch) {
      hideOthers(ctx.name);
      if (tempEl) tempEl.innerHTML = '';
      k.el.hidden = false;
      fadeIn(k.el, 'tab');
      if (k.result.resume) k.result.resume();
      current = { name: ctx.name, cleanup: null };
      window.scrollTo(0, keepScroll ? y : k.y);
      return;
    }
  }

  hideOthers(ctx.name);
  const el = pageEl(ctx.name);
  el.hidden = false;
  if (!KEEP.has(ctx.name)) el.innerHTML = '';
  let result = {};
  switch (ctx.name) {
    case 'home': result = renderHome(el, cat, ctx); break;
    case 'channels': result = renderChannels(el, ctx); break;
    case 'search': result = renderSearch(el, cat, ctx); break;
    case 'title': result = renderTitle(el, cat, ctx); break;
    case 'section': result = renderSection(el, cat, ctx); break;
    case 'list': location.replace('#/section/mylist'); return;
    case 'account': result = renderAccount(el, cat, ctx); revealInstall(); break;
    case 'terms': result = renderTerms(el, ctx); break;
    case 'plus': result = renderPlus(el, ctx); break;
    case 'watch': result = renderWatch(el, cat, ctx); break;
    default:
      el.innerHTML = `<div class="empty"><h2>الصفحة غير موجودة</h2><p>الرابط غير صحيح.</p><a class="btn btn-primary" href="#/">العودة للرئيسية</a></div>`;
  }
  result = result || {};
  if (KEEP.has(ctx.name)) {
    const k = kept.get(ctx.name);
    if (k.result && k.result.cleanup && k.result !== result) { try { k.result.cleanup(); } catch { /* ignore */ } }
    k.result = result;
    k.sig = cat ? cat.signature : '';
    k.hash = location.hash;
    current = { name: ctx.name, cleanup: null, update: result.update };
  } else {
    current = result;
    current.name = ctx.name;
  }
  // الحركة: صفحة الفيلم ترتفع قليلاً وتظهر (مثل سينمانا والتطبيق)، والتبويبات تتلاشى بنعومة
  if (!keepScroll && !firstRender) fadeIn(el, ctx.name === 'title' || ctx.name === 'section' ? 'rise' : 'tab');
  if (keepScroll) { window.scrollTo(0, y); return; }
  if (ctx.name !== 'watch') window.scrollTo(0, 0);
  if (!firstRender) view.focus({ preventScroll: true });
  firstRender = false;
  void fromName;
}

function fadeIn(el, kind) {
  el.classList.remove('enter-rise', 'enter-tab');
  void el.offsetWidth;
  el.classList.add(kind === 'rise' ? 'enter-rise' : 'enter-tab');
}

// ------------------------------------------------------------------ watch view
function renderWatch(el, catalog, ctx) {
  const [id, s, e] = ctx.params;
  const item = catalog.get(id);
  const back = () => location.replace(`#/title/${encodeURIComponent(id)}`);
  if (!item) {
    el.innerHTML = `<div class="empty"><h2>العنوان غير موجود</h2><a class="btn btn-primary" href="#/">العودة للرئيسية</a></div>`;
    return {};
  }

  let prev = null;
  let src, subtitle = '', epObj = null, next = null, intro = null, creditsAt = null, subtitles = item.subtitles, thumb = item.backdrop;
  if (item.type === 'series') {
    epObj = (s && e) ? findEpisode(item, s, e) : allEpisodes(item)[0];
    if (!epObj) { back(); return {}; }
    src = epObj.video;
    subtitle = `الموسم ${epObj.season} الحلقة ${epObj.number}: ${epObj.title}`;
    const n = nextEpisode(item, epObj.season, epObj.number);
    if (n) next = { label: `الحلقة ${n.number}: ${n.title}`, auto: store.getSetting('autoplayNext'), target: n };
    const list = allEpisodes(item);
    const at = list.findIndex((x) => x.season === epObj.season && x.number === epObj.number);
    if (at > 0) prev = { label: `الحلقة ${list[at - 1].number}`, target: list[at - 1] };
    if (epObj.intro_start != null && epObj.intro_end != null) intro = { start: epObj.intro_start, end: epObj.intro_end };
    else if (item.intro_start != null && item.intro_end != null) intro = { start: item.intro_start, end: item.intro_end };
    creditsAt = epObj.credits_start ?? item.credits_start;
    if (epObj.subtitles.length) subtitles = epObj.subtitles;
    thumb = epObj.thumb || item.backdrop;
  } else {
    src = item.video;
    if (item.intro_start != null && item.intro_end != null) intro = { start: item.intro_start, end: item.intro_end };
    creditsAt = item.credits_start;
  }

  const key = store.progressKey(item.id, epObj ? epObj.season : 0, epObj ? epObj.number : 0);
  const saved = store.getProgress(key);
  const startAt = saved && !saved.done ? saved.pos : 0;

  document.body.classList.add('watching');
  document.title = `${item.title} - ${CONFIG.siteName}`;

  const player = mountPlayer(el, {
    embed: isEmbedUrl(src),
    title: item.title,
    subtitle,
    src,
    poster: thumb,
    startAt,
    intro,
    creditsAt,
    subtitles,
    next: next && { label: next.label, auto: next.auto },
    onBack: back,
    onNext: () => next && location.replace(`#/watch/${encodeURIComponent(item.id)}/${next.target.season}/${next.target.number}`),
    prev: prev && { label: prev.label },
    onPrev: () => prev && location.replace(`#/watch/${encodeURIComponent(item.id)}/${prev.target.season}/${prev.target.number}`),
    onProgress: (pos, dur) => store.setProgress(key, {
      id: item.id, season: epObj ? epObj.season : 0, ep: epObj ? epObj.number : 0, pos, dur,
    }),
  });

  // v2: لا رسائل أثناء المشاهدة (مثل التطبيق): المشغل يكمل من حيث توقف المشاهد بصمت
  paintMeta();
  return { cleanup: () => player.destroy() };
}

// ------------------------------------------------------------------ actions
document.addEventListener('click', (ev) => {
  const scrollBtn = ev.target.closest('[data-scroll]');
  if (scrollBtn) {
    const track = scrollBtn.parentElement.querySelector('.track');
    const dir = getComputedStyle(track).direction === 'rtl' ? -1 : 1;
    track.scrollBy({ left: dir * Number(scrollBtn.dataset.scroll) * track.clientWidth * 0.85, behavior: 'smooth' });
    return;
  }
  if (ev.target.closest('[data-back]')) {
    if (history.length > 1 && sessionStorage.getItem('cigram_nav_depth') !== '0') history.back(); else location.hash = '#/';
    return;
  }
  const ml = ev.target.closest('[data-mylist]');
  if (ml) {
    const added = store.toggleList(ml.dataset.mylist);
    document.querySelectorAll(`[data-mylist="${CSS.escape(ml.dataset.mylist)}"]`).forEach((x) => {
      x.setAttribute('aria-pressed', String(added));
      const svg = x.querySelector('svg');
      if (svg) svg.outerHTML = sym(added ? 'check' : 'add');
    });
    const k = kept.get('home'); if (k) k.sig = '';   // «قائمتي» في الرئيسية تتحدث عند الرجوع
    return;
  }
  const sh = ev.target.closest('[data-share]');
  if (sh) { shareTitle(sh.dataset.share); return; }
  const b = ev.target.closest('[data-action]');
  if (!b) return;
  const a = b.dataset.action;

  if (a === 'export') {
    const blob = new Blob([store.exportData()], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${CONFIG.siteName}-backup.json`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 2000);
  } else if (a === 'clear-history') {
    if (b.dataset.armed) {
      store.clearHistory();
      toast('تم مسح سجل المشاهدة');
      route();
    } else {
      b.dataset.armed = '1';
      b.textContent = 'اضغط مرة أخرى للتأكيد';
      setTimeout(() => { if (b.isConnected) { delete b.dataset.armed; b.textContent = 'مسح سجل المشاهدة'; } }, 4000);
    }
  } else if (a === 'install' && deferredInstall) {
    deferredInstall.prompt();
    deferredInstall.userChoice.finally(() => { deferredInstall = null; b.hidden = true; });
  } else if (a === 'reload') {
    boot();
  }
});

document.addEventListener('submit', (ev) => {
  if (ev.target.id === 'nameForm') {
    ev.preventDefault();
    store.setName($('#nameInput').value);
    toast('تم حفظ الاسم');
  }
});

document.addEventListener('change', (ev) => {
  const t = ev.target;
  if (t.id === 'autoNext') store.setSetting('autoplayNext', t.checked);
  if (t.id === 'importFile' && t.files && t.files[0]) {
    const reader = new FileReader();
    reader.onload = () => {
      try { store.importData(String(reader.result)); toast('تم استيراد النسخة الاحتياطية'); route(); }
      catch { toast('الملف غير صالح. اختر ملفاً صدّرته من هذا الموقع.'); }
    };
    reader.readAsText(t.files[0]);
    t.value = '';
  }
});

// الصور المكسورة: نستبدلها بخلفية بسيطة بدل أيقونة المتصفح
document.addEventListener('error', (ev) => {
  const t = ev.target;
  if (t && t.tagName === 'IMG') {
    // صور سيكرام على R2 لها مضيفان: نجرب الآخر مرة واحدة قبل الاستسلام (مثل التطبيق)
    const alt = !t.dataset.r2retry && alternateR2(t.currentSrc || t.src);
    if (alt) { t.dataset.r2retry = '1'; t.src = alt; return; }
    const f = document.createElement('div');
    f.className = `img-fallback ${t.className || ''}`;
    f.setAttribute('aria-hidden', 'true');
    t.replaceWith(f);
  }
}, true);

async function shareTitle(id) {
  const item = cat && cat.get(id);
  const url = location.href.split('#')[0] + '#/title/' + encodeURIComponent(id);
  const data = { title: item ? item.title : CONFIG.siteName, text: item ? `شاهد «${item.title}» على ${CONFIG.siteName}` : '', url };
  try {
    if (navigator.share) { await navigator.share(data); return; }
  } catch (e) { if (e && e.name === 'AbortError') return; }
  try { await navigator.clipboard.writeText(url); toast('تم نسخ الرابط'); } catch { prompt('انسخ الرابط:', url); }
}

// عمق التنقل داخل الموقع (زر الرجوع يرجع للصفحة السابقة فقط إن كانت من الموقع)
let navDepth = 0;
try { sessionStorage.setItem('cigram_nav_depth', '0'); } catch { /* ignore */ }
window.addEventListener('hashchange', () => { navDepth++; try { sessionStorage.setItem('cigram_nav_depth', String(navDepth)); } catch { /* ignore */ } });

// الشريط العلوي يصير صلباً بعد التمرير (فوق البانر يكون شفافاً)
let scrolled = false;
window.addEventListener('scroll', () => {
  const s = window.scrollY > 24;
  if (s !== scrolled) { scrolled = s; document.documentElement.classList.toggle('scrolled', s); }
}, { passive: true });

// ------------------------------------------------------------------ install & offline
window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); deferredInstall = e; revealInstall(); });
function revealInstall() {
  const btn = $('[data-action="install"]');
  if (btn && deferredInstall) btn.hidden = false;
}

if ('serviceWorker' in navigator && /^https?:$/.test(location.protocol)) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('sw.js', { updateViaCache: 'none' }).then((reg) => {
      // نفحص التحديث عند كل فتح وعند العودة للصفحة، والنسخة الجديدة تُفعَّل فوراً
      const check = () => reg.update().catch(() => {});
      document.addEventListener('visibilitychange', () => { if (!document.hidden) check(); });
      setInterval(check, 30 * 60 * 1000);
      reg.addEventListener('updatefound', () => {
        const w = reg.installing;
        if (w) w.addEventListener('statechange', () => { if (w.state === 'installed' && navigator.serviceWorker.controller) w.postMessage('skipWaiting'); });
      });
    }).catch(() => {});
    // سكربت جديد تسلّم الصفحة: نعيد تحميلها مرة واحدة ليأخذ المستخدم الملفات الجديدة بدون مسح الكاش
    let reloaded = false;
    if (navigator.serviceWorker.controller) {
      navigator.serviceWorker.addEventListener('controllerchange', () => {
        if (reloaded) return;
        reloaded = true;
        const watching = document.querySelector('video') && !document.querySelector('video').paused;
        if (!watching) location.reload();   // لا نقطع مشاهدة جارية: التحديث يصل في الفتح التالي
      });
    }
  });
}

// ------------------------------------------------------------------ boot
const BOOT_ERROR = `<div class="empty"><h2>تعذر تحميل المحتوى</h2><p>تأكد من اتصالك بالإنترنت ثم حاول مرة أخرى.</p><button type="button" class="btn btn-primary" data-action="reload">إعادة المحاولة</button></div>`;
let bootFailed = false;

async function boot() {
  bootFailed = false;
  // صفحة القنوات (والشروط) لا تنتظر كتالوج الأفلام
  if (STANDALONE.has(parseHash().name)) { if (current.name !== parseHash().name) route(); }
  else if (!cat) route();
  // v2: آخر محتوى محفوظ يُرسم فوراً (أقل من ثانية)، ثم يُسأل الخادم ويتحدث المحتوى في مكانه إن تغيّر
  if (!cat) {
    const cached = await loadCachedCatalog();
    if (cached && !cat) {
      cat = cached;
      if (!STANDALONE.has(current.name)) route();
    }
  }
  let fresh = null;
  try {
    fresh = await loadCatalog();
  } catch (e) {
    console.warn('[سيكرام] تعذر تحميل المحتوى من الخادم:', e && e.message ? e.message : e);
    if (!cat) {
      bootFailed = true;
      if (!STANDALONE.has(parseHash().name)) { current = { cleanup: null }; route(); }
      setTimeout(() => { if (!cat) boot(); }, 15000);   // يحاول مرة أخرى وحده
      return;
    }
  }
  if (fresh && (!cat || fresh.signature !== cat.signature)) {
    const had = !!cat;
    cat = fresh;
    const name = parseHash().name;
    // أول محتوى: تُرسم الصفحة المفتوحة مهما كانت (حتى رابط مشاهدة مباشر). تحديث لاحق: لا نقاطع من يشاهد
    if (!STANDALONE.has(name) && (!had || name !== 'watch')) route({ keepScroll: had, force: true });
  }
  startAutoRefresh();
  layout.refresh();
  plus.refresh();
  schedulePromo(() => parseHash().name);   // نافذة Plus: 3 مرات بالأسبوع للمجانيين، الرئيسية فقط
}

// ------------------------------------------------------------------ تحديث تلقائي
// أي إضافة أو تعديل أو إخفاء في تطبيق الإدارة يظهر في الموقع وهو مفتوح، بدون إعادة تحميل:
// نسأل الخادم كل دقيقة ونصف (والصفحة ظاهرة)، وعند الرجوع للصفحة أو عودة الإنترنت.
const REFRESH_MS = 90 * 1000;
let refreshTimer = null;
let checking = false;
let lastCheck = 0;

async function checkForUpdates() {
  if (checking || !cat || document.visibilityState !== 'visible') return;
  checking = true;
  lastCheck = Date.now();
  try {
    const fresh = await loadCatalog();
    if (fresh.signature && fresh.signature === cat.signature) return;
    const added = fresh.items.filter((i) => !cat.get(i.id)).length;
    cat = fresh;
    const name = parseHash().name;
    const typing = document.activeElement && /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement.tagName);
    // أثناء المشاهدة أو الكتابة لا نعيد رسم الصفحة؛ المحتوى الجديد يظهر عند الانتقال التالي.
    // صفحة القنوات لا تعتمد على كتالوج الأفلام، فلا تُعاد (حتى لا تتوقف القناة).
    if (name !== 'watch' && !STANDALONE.has(name) && !typing) route({ keepScroll: true, force: true });
    if (added > 0 && name !== 'watch' && name !== 'channels') toast(added === 1 ? 'أضيف عنوان جديد' : `أضيفت ${added} عناوين جديدة`);
  } catch { /* نحاول في المرة القادمة */ } finally {
    checking = false;
  }
}

function startAutoRefresh() {
  if (refreshTimer) return;
  refreshTimer = setInterval(checkForUpdates, REFRESH_MS);
  const soon = () => { if (Date.now() - lastCheck > 15000) checkForUpdates(); };
  document.addEventListener('visibilitychange', soon);
  window.addEventListener('online', soon);
  // شكل الرئيسية من تطبيق الإدارة: يُسأل مع كل تحديث، وإذا تغيّر تُعاد الرئيسية في مكانها
  layout.onChange(() => { const k = kept.get('home'); if (k) k.sig = ''; if (parseHash().name === 'home') route({ keepScroll: true, force: true }); });
  setInterval(() => layout.refresh(), REFRESH_MS);
}

window.addEventListener('hashchange', () => route());

// ------------------------------------------------------------------ الحساب
// دخول أو خروج أو تحديث بيانات الحساب: نعيد رسم صفحة «حسابي» إن كانت مفتوحة
const authSig = (u) => (u ? [plus.isPlus() ? 'plus' : 'free', u.id, u.public_id, u.name, u.email, u.avatar_url, u.has_password, u.terms_version, u.google_linked].join('|') : '');
let lastAuthSig = authSig(auth.currentUser());
setAvatar((auth.currentUser() || {}).avatar_url, currentNav);
auth.onAuth((user) => {
  setAvatar(user && user.avatar_url, currentNav);
  const sig = authSig(user);
  if (sig === lastAuthSig) return;
  lastAuthSig = sig;
  if (cat && parseHash().name === 'account') route({ keepScroll: true });
});
let lastPlusOn = plus.isPlus();
plus.onChange(() => {
  const on = plus.isPlus();
  if (on === lastPlusOn) return;
  lastPlusOn = on;
  lastAuthSig = authSig(auth.currentUser());
  if (cat && parseHash().name === 'account') route({ keepScroll: true });
});
auth.refresh(); // نتأكد أن الجلسة المحفوظة ما زالت صالحة
store.onChange(() => { /* الحالة تُقرأ عند كل رسم، لا حاجة لشيء هنا */ });
boot();
