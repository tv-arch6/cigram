// واجهة Cigram Plus في الموقع: صفحة الاشتراك (#/plus)، صندوق «حسابي»، نافذة «ميزة Plus»، هدية التفعيل، والنافذة المنبثقة (3 مرات بالأسبوع للمجانيين).
import { CONFIG } from './config.js';
import * as auth from './auth.js';
import * as plus from './plus.js';
import { esc, toast } from './util.js';

const ROWS = [
  ['الإعلانات', 'بدون إعلانات في التطبيق', true],
  ['جودة الأفلام والمسلسلات', 'حتى 720p', 'من 1080p حتى 4K'],
  ['صورة داخل صورة (PiP)', 'غير متاحة', 'متاحة'],
  ['قنوات HD و4K (مثل beIN SPORTS)', 'غير متاحة', 'متاحة'],
];

// ---------------------------------------------------------------- البطاقتان
function cardsHtml() {
  const on = plus.isPlus();
  const price = plus.priceLine();
  const free = ['حتى 720p', 'بدون صورة داخل صورة', 'بدون قنوات 1080p–4K', 'تظهر إعلانات في التطبيق'];
  const gold = ['بدون أي إعلانات', 'جودة 1080p وحتى 4K', 'صورة داخل صورة (PiP)', 'قنوات HD و4K مثل باقة beIN SPORTS'];
  return `<div class="pl-cards">
    <article class="pl-card pl-free"><h3>عادي</h3><ul>${free.map((t) => `<li><i class="x">✕</i>${esc(t)}</li>`).join('')}</ul>
      <p class="pl-state">${on ? '&nbsp;' : 'خطتك الحالية'}</p></article>
    <article class="pl-card pl-gold"><h3>Cigram Plus</h3><ul>${gold.map((t) => `<li><i class="v">✓</i>${esc(t)}</li>`).join('')}</ul>
      ${!on && price ? `<p class="pl-price">${esc(price)}</p>` : ''}
      ${on ? `<p class="pl-state">Plus مفعّل ✓ · ${plus.periodText()}</p>`
        : plus.salesEnabled() ? '<a class="pl-btn" href="#/plus">اشترك الان في Plus</a>' : '<p class="pl-state">يُفعَّل بكود التفعيل</p>'}</article>
  </div>`;
}

function bannerHtml() {
  if (plus.isPlus()) return `<p class="pl-banner on">Plus مفعّل ✓<br>${plus.periodText()} · متبقٍّ ${plus.daysLeft()} يوم</p>`;
  if (plus.expiredAt()) return `<p class="pl-banner off">انتهى اشتراك Plus في ${plus.formatDate(plus.expiredAt())} · فعّل كوداً جديداً للتجديد</p>`;
  if (plus.salesEnabled() && plus.introEligible()) return `<p class="pl-banner offer">عرض أول اشتراك: ${plus.iqd(plus.PLANS.intro.price)} لمدة ${plus.PLANS.intro.months} أشهر</p>`;
  return '';
}

// ---------------------------------------------------------------- صفحة #/plus
export function renderPlus(el) {
  const logged = auth.isLoggedIn();
  const chips = plus.salesEnabled() ? `
    <section class="pl-pay" id="plPay">
      <h2>الدفع موثوق</h2>
      <p class="pl-sub">اختر طريقة الدفع ثم اضغط الزر الأصفر: نفتح لك واتساب برسالة جاهزة، وبعد الدفع يصلك الكود.</p>
      <div class="pl-chips" role="radiogroup" aria-label="طريقة الدفع">
        ${plus.METHODS.map((m, i) => `<button type="button" class="pl-chip${i === 0 ? ' on' : ''}" role="radio" aria-checked="${i === 0}" data-m="${i}">${esc(m.label)}</button>`).join('')}
      </div>
      <a class="pl-wa" id="plWa" href="${esc(plus.whatsappUrl(plus.METHODS[0].label))}" target="_blank" rel="noopener">تفعيل واستلام الكود</a>
    </section>` : '';
  el.innerHTML = `<div class="page narrow pl-page">
    <h1 class="page-title">Cigram Plus</h1>
    <div id="plBanner">${bannerHtml()}</div>
    <section class="pl-code" id="plCode">
      <h2>ضع كود التفعيل</h2>
      ${logged ? `<form id="plForm" novalidate>
        <input id="plInput" dir="ltr" autocomplete="off" autocapitalize="characters" spellcheck="false" placeholder="XXXX-XXXX-XXXX" maxlength="40" aria-label="كود التفعيل">
        <button class="pl-act" type="submit">تفعيل</button></form>
        <p class="pl-msg" id="plMsg" role="alert" hidden></p>`
        : '<p class="pl-sub">سجّل دخولك أولاً لتفعيل الكود.</p><a class="pl-act" href="#/account">تسجيل الدخول</a>'}
    </section>
    <div id="plCards">${cardsHtml()}</div>
    <p class="pl-trust">الكود يُفعَّل على حسابك ويعمل في التطبيق والموقع. ينتهي في موعده تلقائياً، والتجديد بكود جديد، ولا يوجد خصم تلقائي.</p>
    ${chips}
    <p class="pl-fine">ملاحظة: في هذا الموقع تُطبَّق حدود الجودة وPiP وقنوات 1080p–4K داخل المتصفح.</p>
  </div>`;
  document.title = `Cigram Plus - ${CONFIG.siteName}`;

  const $ = (s) => el.querySelector(s);
  const msg = $('#plMsg');
  const repaint = () => { const b = $('#plBanner'), c = $('#plCards'); if (b) b.innerHTML = bannerHtml(); if (c) c.innerHTML = cardsHtml(); };
  const offChange = plus.onChange(repaint);
  plus.refresh(true);

  const form = $('#plForm');
  if (form) form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = form.querySelector('button');
    if (!plus.cleanCode($('#plInput').value)) { msg.textContent = plus.messageFor('invalid'); msg.hidden = false; return; }
    btn.disabled = true; msg.hidden = true;
    const r = await plus.redeem($('#plInput').value);
    btn.disabled = false;
    if (r.ok) { $('#plInput').value = ''; repaint(); showGift(r.until, r.months); }
    else { msg.textContent = plus.messageFor(r.error); msg.hidden = false; }
  });
  el.addEventListener('click', (e) => {
    const chip = e.target.closest('.pl-chip');
    if (!chip) return;
    el.querySelectorAll('.pl-chip').forEach((c) => { const on = c === chip; c.classList.toggle('on', on); c.setAttribute('aria-checked', String(on)); });
    $('#plWa').href = plus.whatsappUrl(plus.METHODS[Number(chip.dataset.m)].label);
  });
  if (location.hash.includes('pay')) setTimeout(() => $('#plPay')?.scrollIntoView({ behavior: 'smooth' }), 100);
  return { cleanup: offChange };
}

// ---------------------------------------------------------------- صندوق «حسابي»
export function accountBoxHtml() {
  const on = plus.isPlus();
  const price = plus.priceLine();
  return `<section class="panel pl-box" id="plBox">
    <div class="pl-box-head"><strong>Cigram Plus</strong><span class="pl-badge${on ? ' on' : ''}">${on ? 'مفعّل' : 'غير مفعّل'}</span></div>
    ${on ? `<p>${plus.periodText()} (متبقٍّ ${plus.daysLeft()} يوم)</p>`
      : `<p>بدون إعلانات · جودة حتى 4K · صورة داخل صورة · قنوات HD</p>${price ? `<p class="pl-price-sm">${esc(price)}</p>` : ''}`}
    <a class="pl-act" href="#/plus">${on ? 'تفاصيل الاشتراك' : plus.salesEnabled() ? 'اشترك أو ضع كود التفعيل' : 'ضع كود التفعيل'}</a>
  </section>`;
}

// ---------------------------------------------------------------- نافذة «ميزة Plus»
let dialog = null;
function closeDialog() { if (dialog) { dialog.remove(); dialog = null; } }
function modal(html, cls = '') {
  closeDialog();
  dialog = document.createElement('div');
  dialog.className = 'pl-modal ' + cls;
  dialog.setAttribute('role', 'dialog');
  dialog.setAttribute('aria-modal', 'true');
  dialog.innerHTML = `<div class="pl-modal-card">${html}</div>`;
  dialog.addEventListener('click', (e) => { if (e.target === dialog || e.target.closest('[data-close]')) closeDialog(); });
  document.body.appendChild(dialog);
  const f = dialog.querySelector('[data-focus]'); if (f) f.focus();
  return dialog;
}
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeDialog(); });

/** يُرجع true إن كان Plus فعّالاً، وإلا يفتح نافذة تشرح أن الميزة ضمن Plus ويرجع false. */
export function requirePlus(feature) {
  if (plus.isPlus()) return true;
  upsell(feature);
  return false;
}
export function upsell(feature) {
  const logged = auth.isLoggedIn();
  const label = logged ? (plus.salesEnabled() ? 'عرض Plus' : 'ضع كود التفعيل') : 'تسجيل الدخول';
  modal(`<h3>ميزة Plus</h3>
    <p>${esc(feature)} ضمن اشتراك Cigram Plus.${logged ? '' : ' سجّل دخولك أولاً ثم فعّل الاشتراك.'}</p>
    <a class="pl-act" data-close data-focus href="${logged ? '#/plus' : '#/account'}">${label}</a>
    <button type="button" class="pl-later" data-close>لاحقاً</button>`, 'gold');
}

// ---------------------------------------------------------------- هدية التفعيل
export function showGift(until, months) {
  const m = dialog = modal(`<div class="pl-gift">🎁</div><h3>مبروك! هدية Plus لك</h3>
    <p>تم تفعيل اشتراكك لمدة ${months} أشهر<br>${plus.periodText()}<br>بدون إعلانات · جودة حتى 4K · صورة داخل صورة · قنوات HD</p>
    <button type="button" class="pl-act" data-close data-focus>رائع</button>`, 'gold gift');
  const colors = ['#FFD21F', '#E21B14', '#2BB673', '#3B82F6', '#fff'];
  for (let i = 0; i < 36; i++) {
    const c = document.createElement('i');
    c.className = 'pl-conf';
    c.style.cssText = `left:${Math.random() * 100}%;background:${colors[i % 5]};animation-delay:${(Math.random() * 0.6).toFixed(2)}s;animation-duration:${(1.8 + Math.random() * 1.4).toFixed(2)}s;transform:rotate(${Math.floor(Math.random() * 360)}deg)`;
    m.appendChild(c);
  }
  toast('تم تفعيل Plus');
}

// ---------------------------------------------------------------- النافذة المنبثقة: الأحد والخميس والجمعة، مرة في كل يوم، للمجانيين
export const PROMO_WEEK = 7 * 24 * 3600 * 1000;
const PROMO_KEY = 'cigram.plus.promo.v2';
/** أيام الظهور (getDay): الأحد 0، الخميس 4، الجمعة 5. */
export const PROMO_DAYS = [0, 4, 5];

const sameDay = (a, b) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();

/** history = أوقات الظهور السابقة. نفس منطق التطبيق (CigramPlusPromo.shouldShow): الأحد والخميس والجمعة فقط، ومرة واحدة باليوم. */
export function promoShouldShow(history, now) {
  const today = new Date(now);
  if (!PROMO_DAYS.includes(today.getDay())) return false;
  for (const t of history || []) {
    if (t > now) return false;
    if (sameDay(new Date(t), today)) return false;
  }
  return true;
}
export const promoRecord = (history, now) => [...(history || []).filter((t) => now - t < PROMO_WEEK && t <= now), now];
function promoLoad() { try { const a = JSON.parse(localStorage.getItem(PROMO_KEY) || '[]'); return Array.isArray(a) ? a.filter(Number.isFinite) : []; } catch { return []; } }

/** النص بعد التهريب مع كل رقم (لاتيني أو هندي، مع , أو . داخل الرقم) باللون الأحمر. */
export function redDigits(text) {
  return esc(text).replace(/[0-9\u0660-\u0669\u06F0-\u06F9]+(?:[,.][0-9\u0660-\u0669\u06F0-\u06F9]+)*/g, '<span class="pp-n">$&</span>');
}

let promoDone = false;
/** بعد ثانيتين ونصف على الرئيسية فقط، مرة بالجلسة، ليس لـPlus، وليس أثناء المشاهدة. */
export function schedulePromo(getPage) {
  if (promoDone) return;
  promoDone = true;
  setTimeout(() => {
    try {
      if (getPage() !== 'home' || document.hidden || plus.isPlus() || dialog) return;
      if (document.documentElement.classList.contains('landing-on')) return;
      const now = Date.now();
      const h = promoLoad();
      if (!promoShouldShow(h, now)) return;
      try { localStorage.setItem(PROMO_KEY, JSON.stringify(promoRecord(h, now))); } catch { /* ignore */ }
      showPromo();
    } catch { /* لا شيء */ }
  }, 2500);
}

export function showPromo() {
  const sales = plus.salesEnabled();
  const logged = auth.isLoggedIn();
  const feats = ['بدون أي إعلانات', 'جودة 1080p وحتى 4K للأفلام والمسلسلات', 'صورة داخل صورة (PiP)', 'قنوات HD و4K مثل باقة beIN SPORTS', 'أيقونات حصرية للتطبيق وشارة توثيق'];
  const intro = plus.introEligible();
  const price = !sales ? '<div class="pp-price"><p class="pp-note">يُفعَّل بكود التفعيل</p></div>'
    : intro ? `<div class="pp-price"><p class="pp-big">${redDigits(`${plus.iqd(plus.PLANS.intro.price)} لأول ${plus.PLANS.intro.months} أشهر`)}</p><p class="pp-then">${redDigits(`ثم ${plus.iqd(plus.PLANS.standard.price)} كل ${plus.PLANS.standard.months} أشهر`)}</p></div>`
    : `<div class="pp-price"><p class="pp-big">${redDigits(`${plus.iqd(plus.PLANS.standard.price)} كل ${plus.PLANS.standard.months} أشهر`)}</p></div>`;
  const label = sales ? 'اشترك بـ Plus' : 'ضع كود التفعيل';
  const crown = '<svg class="pp-crown" viewBox="0 0 72 72" aria-hidden="true"><circle cx="36" cy="36" r="36" fill="rgba(255,180,0,.2)"/><path d="M17 49 L15.6 22 L27 33 L36 19 L45 33 L56.4 22 L55 49Z M17 52 H55 V56 H17Z" fill="#ffb400"/></svg>';
  modal(`<div class="pp-card">
      <button type="button" class="pp-x" data-close aria-label="إغلاق"><svg viewBox="0 0 44 44" aria-hidden="true"><circle cx="22" cy="22" r="17.6" fill="#fff"/><path d="M15.8 15.8 L28.2 28.2 M28.2 15.8 L15.8 28.2" stroke="#111" stroke-width="3.1" stroke-linecap="round"/></svg></button>
      ${crown}
      <h3 class="pp-title">Cigram Plus</h3>
      <p class="pp-sub">مشاهدة بلا حدود، بدون إعلانات</p>
      <ul class="pp-list">${feats.map((f) => `<li><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="12" fill="rgba(226,27,20,.1)"/><path d="M6.7 12.5 L10.6 16.1 L17.5 8.4" fill="none" stroke="#e21b14" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg><span>${redDigits(f)}</span></li>`).join('')}</ul>
      ${price}
      <a class="pp-go" data-close data-focus href="${logged ? '#/plus' : '#/account'}">${label}</a>
    </div>`, 'promo');
}
