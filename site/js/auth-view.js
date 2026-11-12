// واجهة الحساب في صفحة «حسابي»: تسجيل الدخول، إنشاء حساب (بالبريد أو بحساب Google)، الموافقة على الشروط.
import * as auth from './auth.js';
import * as plus from './plus.js';
import { esc, toast } from './util.js';

const TERMS_LINKS = 'أوافق على <a href="#/terms" target="_blank" rel="noopener">شروط الاستخدام</a> و<a href="#/terms?s=privacy" target="_blank" rel="noopener">سياسة الخصوصية</a>';
const EYE = '<svg viewBox="0 0 24 24" aria-hidden="true" class="stroke"><path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="3"/></svg>';
const isEmail = (v) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v);

function passwordField(name, label, autocomplete, hint = '') {
  return `<label class="field"><span>${label}</span>
    <span class="pw">
      <input type="password" name="${name}" autocomplete="${autocomplete}" dir="ltr" minlength="8" maxlength="128" required>
      <button type="button" class="pw-toggle" data-pw-toggle aria-label="إظهار كلمة المرور" aria-pressed="false">${EYE}</button>
    </span>${hint ? `<small>${hint}</small>` : ''}</label>`;
}

// ---------------------------------------------------------------- قبل الدخول
export function authPanelHtml() {
  const google = auth.googleEnabled();
  return `
    <section class="panel auth-panel" id="auth" data-mode="login">
      <h1 class="auth-title">أهلاً بك في سيكرام</h1>
      <p class="auth-lead">سجّل دخولك أو أنشئ حساباً، وهو نفس حسابك في تطبيق سيكرام.</p>

      ${google ? `
      <div class="google-block">
        <div class="google-wrap"><div class="google-slot" aria-label="المتابعة بحساب Google"></div></div>
        <p class="form-error" role="alert" data-google-error hidden></p>
        <div class="auth-terms-confirm" hidden>
          <p>هذا أول دخول بحساب Google هذا، وسنُنشئ لك حساباً جديداً في سيكرام.</p>
          <label class="check"><input type="checkbox" data-gterms> <span>${TERMS_LINKS}</span></label>
          <p class="form-error" role="alert" hidden></p>
          <div class="row-actions">
            <button type="button" class="btn btn-primary btn-sm" data-act="google-continue" disabled>إنشاء الحساب</button>
            <button type="button" class="btn btn-ghost btn-sm" data-act="google-cancel">إلغاء</button>
          </div>
        </div>
      </div>
      <div class="auth-or"><span>أو بالبريد الإلكتروني</span></div>` : ''}

      <div class="auth-tabs" role="tablist" aria-label="الحساب">
        <button type="button" role="tab" class="on" aria-selected="true" data-auth-tab="login">تسجيل الدخول</button>
        <button type="button" role="tab" aria-selected="false" data-auth-tab="register">إنشاء حساب</button>
      </div>

      <form class="auth-form" data-form="login" novalidate>
        <label class="field"><span>البريد الإلكتروني</span>
          <input type="email" name="email" autocomplete="email" inputmode="email" dir="ltr" required></label>
        ${passwordField('password', 'كلمة المرور', 'current-password')}
        <p class="form-error" role="alert" hidden></p>
        <button class="btn btn-primary btn-block" type="submit">تسجيل الدخول</button>
      </form>

      <form class="auth-form" data-form="register" novalidate hidden>
        <label class="field"><span>الاسم</span>
          <input type="text" name="name" autocomplete="name" minlength="2" maxlength="60" required></label>
        <label class="field"><span>البريد الإلكتروني</span>
          <input type="email" name="email" autocomplete="email" inputmode="email" dir="ltr" required></label>
        ${passwordField('password', 'كلمة المرور', 'new-password', '8 أحرف على الأقل')}
        <label class="field"><span>الجنس <em>(اختياري)</em></span>
          <select name="gender"><option value="unspecified">بدون تحديد</option><option value="male">ذكر</option><option value="female">أنثى</option></select></label>
        <label class="check"><input type="checkbox" name="terms" data-terms-check> <span>${TERMS_LINKS}</span></label>
        <p class="form-error" role="alert" hidden></p>
        <button class="btn btn-primary btn-block" type="submit" disabled>إنشاء حساب</button>
      </form>
    </section>`;
}

function setBusy(btn, busy, text) {
  if (!btn) return;
  if (busy) { btn.dataset.label = btn.textContent; btn.textContent = text || 'لحظة…'; btn.disabled = true; btn.setAttribute('aria-busy', 'true'); }
  else { btn.textContent = btn.dataset.label || btn.textContent; btn.removeAttribute('aria-busy'); }
}

function showError(box, message) {
  const p = box.querySelector('.form-error');
  if (!p) return;
  p.textContent = message || '';
  p.hidden = !message;
}

function welcome(user, created) {
  const first = String(user && user.name || '').split(/\s+/)[0];
  toast(created ? `تم إنشاء حسابك، أهلاً ${first}` : `أهلاً ${first}`);
}

export function bindAuthPanel(root) {
  const panel = root.querySelector('#auth');
  if (!panel) return () => {};
  const forms = {
    login: panel.querySelector('[data-form="login"]'),
    register: panel.querySelector('[data-form="register"]'),
  };
  const regTerms = forms.register.querySelector('[data-terms-check]');
  const regSubmit = forms.register.querySelector('[type="submit"]');
  const slot = panel.querySelector('.google-slot');
  const googleError = panel.querySelector('[data-google-error]');
  const confirmBox = panel.querySelector('.auth-terms-confirm');
  let mode = 'login';
  let pendingCredential = '';
  let alive = true;

  function paintGoogle() {
    if (!slot) return;
    auth.renderGoogleButton(slot, { text: 'continue_with', onCredential })
      .catch(() => {
        // Google محجوب أو بدون إنترنت: نخفي الخيار بدل زر لا يعمل
        if (!alive) return;
        panel.querySelectorAll('.auth-or, .google-block').forEach((n) => { n.hidden = true; });
      });
  }

  function setMode(next) {
    mode = next;
    panel.dataset.mode = next;
    panel.querySelectorAll('[data-auth-tab]').forEach((t) => {
      const on = t.dataset.authTab === next;
      t.classList.toggle('on', on);
      t.setAttribute('aria-selected', String(on));
    });
    forms.login.hidden = next !== 'login';
    forms.register.hidden = next !== 'register';
    if (confirmBox) confirmBox.hidden = true;
    showError(forms.login, '');
    showError(forms.register, '');
    if (googleError) showError(googleError.parentElement, '');
  }

  async function onCredential(credential) {
    if (!alive) return;
    const acceptNow = mode === 'register' && regTerms.checked;
    try {
      const { user, created } = await auth.googleSignIn(credential, acceptNow);
      welcome(user, created);
    } catch (err) {
      if (err.code === 'terms_required') {
        // حساب جديد من تبويب الدخول: نطلب الموافقة ثم نكمل بنفس الرمز
        pendingCredential = credential;
        confirmBox.hidden = false;
        showError(confirmBox, '');
        confirmBox.querySelector('[data-gterms]').focus();
        return;
      }
      showError(googleError.parentElement, err.message);
    }
  }

  async function submitLogin(e) {
    e.preventDefault();
    const f = forms.login;
    const email = f.email.value.trim();
    const password = f.password.value;
    if (!isEmail(email)) return showError(f, 'اكتب بريداً إلكترونياً صحيحاً.');
    if (!password) return showError(f, 'اكتب كلمة المرور.');
    showError(f, '');
    const btn = f.querySelector('[type="submit"]');
    setBusy(btn, true, 'جارٍ الدخول…');
    try {
      const user = await auth.login(email, password);
      welcome(user, false);
    } catch (err) {
      showError(f, err.message);
    } finally {
      setBusy(btn, false);
      btn.disabled = false;
    }
  }

  async function submitRegister(e) {
    e.preventDefault();
    const f = forms.register;
    const name = f.name.value.trim();
    const email = f.email.value.trim();
    const password = f.password.value;
    if (name.length < 2 || name.length > 60) return showError(f, 'الاسم من 2 إلى 60 حرفاً.');
    if (!isEmail(email)) return showError(f, 'اكتب بريداً إلكترونياً صحيحاً.');
    if (password.length < 8) return showError(f, 'كلمة المرور 8 أحرف على الأقل.');
    if (!regTerms.checked) return showError(f, 'وافق على الشروط لإنشاء الحساب.');
    showError(f, '');
    setBusy(regSubmit, true, 'جارٍ إنشاء الحساب…');
    try {
      const user = await auth.register({ name, email, password, gender: f.gender.value });
      welcome(user, true);
    } catch (err) {
      showError(f, err.message);
    } finally {
      setBusy(regSubmit, false);
      regSubmit.disabled = !regTerms.checked;
    }
  }

  const onClick = async (e) => {
    const tab = e.target.closest('[data-auth-tab]');
    if (tab) { setMode(tab.dataset.authTab); return; }
    const eye = e.target.closest('[data-pw-toggle]');
    if (eye) {
      const input = eye.parentElement.querySelector('input');
      const show = input.type === 'password';
      input.type = show ? 'text' : 'password';
      eye.setAttribute('aria-pressed', String(show));
      eye.setAttribute('aria-label', show ? 'إخفاء كلمة المرور' : 'إظهار كلمة المرور');
      return;
    }
    const act = e.target.closest('[data-act]');
    if (!act) return;
    if (act.dataset.act === 'google-cancel') { pendingCredential = ''; confirmBox.hidden = true; return; }
    if (act.dataset.act === 'google-continue' && pendingCredential) {
      setBusy(act, true, 'جارٍ إنشاء الحساب…');
      try {
        const { user, created } = await auth.googleSignIn(pendingCredential, true);
        pendingCredential = '';
        welcome(user, created);
      } catch (err) {
        showError(confirmBox, err.message);
        setBusy(act, false);
        act.disabled = false;
      }
    }
  };

  const onChange = (e) => {
    if (e.target === regTerms) {
      regSubmit.disabled = !regTerms.checked;
      if (regTerms.checked) showError(forms.register, '');
    }
    if (e.target.matches('[data-gterms]')) {
      confirmBox.querySelector('[data-act="google-continue"]').disabled = !e.target.checked;
    }
  };

  panel.addEventListener('click', onClick);
  panel.addEventListener('change', onChange);
  forms.login.addEventListener('submit', submitLogin);
  forms.register.addEventListener('submit', submitRegister);
  paintGoogle();

  return () => {
    alive = false;
    panel.removeEventListener('click', onClick);
    panel.removeEventListener('change', onChange);
  };
}

// ---------------------------------------------------------------- بعد الدخول
function fmtDate(iso) {
  const d = new Date(iso);
  if (!iso || Number.isNaN(d.getTime())) return '';
  try { return d.toLocaleDateString('ar', { year: 'numeric', month: 'long', day: 'numeric' }); } catch { return d.toISOString().slice(0, 10); }
}

export function accountCardHtml(user, statsHtml) {
  const initial = esc(String(user.name || '؟').trim().charAt(0) || '؟');
  const avatar = user.avatar_url
    ? `<img class="acc-avatar" src="${esc(user.avatar_url)}" alt="" referrerpolicy="no-referrer" loading="lazy">`
    : `<span class="acc-avatar acc-initial" aria-hidden="true">${initial}</span>`;
  const verified = plus.isPlus();   // Plus فعّال: شارة التوثيق، وتختفي وحدها عند انتهاء الاشتراك
  const seal = verified ? '<i class="vbadge" role="img" aria-label="حساب موثّق"></i>' : '';
  const badges = [];
  if (user.google_linked) badges.push('<span class="acc-badge">Google</span>');
  if (user.has_password) badges.push('<span class="acc-badge">البريد وكلمة المرور</span>');
  const stale = user.terms_version && auth.needsTerms(user);

  const terms = auth.needsTerms(user) ? `
    <div class="notice warn" id="termsNotice">
      <strong>${stale ? 'تحدّثت شروط الاستخدام' : 'الموافقة على الشروط'}</strong>
      <p>${stale ? 'راجع النسخة الجديدة من الشروط وسياسة الخصوصية ووافق عليها لمتابعة استخدام حسابك.' : 'لم توافق بعد على شروط الاستخدام وسياسة الخصوصية. وافق عليها لمتابعة استخدام حسابك.'}</p>
      <label class="check"><input type="checkbox" data-accept-check> <span>${TERMS_LINKS}</span></label>
      <p class="form-error" role="alert" hidden></p>
      <button type="button" class="btn btn-primary btn-sm" data-act="accept-terms" disabled>موافقة</button>
    </div>` : `
    <p class="terms-ok">
      <svg viewBox="0 0 24 24" aria-hidden="true" class="stroke"><path d="m5 12.5 4.5 4.5L19 7.5"/></svg>
      <span>وافقت على <a href="#/terms">الشروط وسياسة الخصوصية</a>${fmtDate(user.terms_accepted_at) ? ` في ${esc(fmtDate(user.terms_accepted_at))}` : ''}</span>
    </p>`;

  const setPw = user.has_password ? '' : `
    <form class="notice" data-form="set-password" novalidate>
      <strong>ادخل من التطبيق أيضاً</strong>
      <p>حسابك مسجّل بـ Google. عيّن كلمة مرور لتدخل بنفس البريد (${esc(user.email)}) من تطبيق سيكرام.</p>
      ${passwordField('password', 'كلمة المرور الجديدة', 'new-password', '8 أحرف على الأقل')}
      <p class="form-error" role="alert" hidden></p>
      <button class="btn btn-ghost btn-sm" type="submit">تعيين كلمة المرور</button>
    </form>`;

  return `
    <section class="panel account-card" id="account">
      <div class="acc-head">
        <span class="acc-avatar-wrap">${avatar}</span>
        <div class="acc-id">
          <strong>${esc(user.name)}${seal}</strong>
          <span dir="ltr">${esc(user.email)}</span>
          ${user.public_id ? `<button type="button" class="acc-pid" data-act="copy-id" data-id="${esc(user.public_id)}" aria-label="نسخ معرّف الحساب">معرّف حسابك: <b dir="ltr">${esc(user.public_id)}</b> · نسخ</button>` : ''}
          <div class="acc-badges">${badges.join('')}</div>
        </div>
      </div>
      ${terms}
      ${statsHtml || ''}
      ${setPw}
      <div class="actions">
        <button type="button" class="btn btn-ghost btn-sm" data-act="logout">تسجيل الخروج</button>
      </div>
      <details class="danger-zone">
        <summary>حذف الحساب</summary>
        <form data-form="delete" novalidate>
          <p>يحذف حسابك وإعجاباتك نهائياً من التطبيق والموقع، ولا يمكن التراجع عنه.</p>
          ${user.has_password
            ? passwordField('password', 'كلمة المرور للتأكيد', 'current-password')
            : '<label class="field"><span>اكتب كلمة «حذف» للتأكيد</span><input type="text" name="confirm" autocomplete="off" required></label>'}
          <p class="form-error" role="alert" hidden></p>
          <button class="btn btn-danger btn-sm" type="submit">حذف حسابي نهائياً</button>
        </form>
      </details>
    </section>`;
}

export function bindAccountCard(root) {
  const card = root.querySelector('#account');
  if (!card) return () => {};

  const onClick = async (e) => {
    const eye = e.target.closest('[data-pw-toggle]');
    if (eye) {
      const input = eye.parentElement.querySelector('input');
      const show = input.type === 'password';
      input.type = show ? 'text' : 'password';
      eye.setAttribute('aria-pressed', String(show));
      return;
    }
    const act = e.target.closest('[data-act]');
    if (!act) return;
    if (act.dataset.act === 'copy-id') {
      try { await navigator.clipboard.writeText(act.dataset.id); toast('تم نسخ المعرّف ' + act.dataset.id); }
      catch { toast('المعرّف: ' + act.dataset.id); }
    } else if (act.dataset.act === 'logout') {
      await auth.logout();
      toast('تم تسجيل الخروج');
    } else if (act.dataset.act === 'accept-terms') {
      const box = card.querySelector('#termsNotice');
      setBusy(act, true);
      try {
        await auth.acceptTerms();
        toast('شكراً، تم تسجيل موافقتك');
      } catch (err) {
        showError(box, err.message);
        setBusy(act, false);
        act.disabled = false;
      }
    }
  };

  const onChange = (e) => {
    if (e.target.matches('[data-accept-check]')) {
      card.querySelector('[data-act="accept-terms"]').disabled = !e.target.checked;
    }
  };

  const onSubmit = async (e) => {
    const form = e.target;
    e.preventDefault();
    const btn = form.querySelector('[type="submit"]');
    if (form.dataset.form === 'set-password') {
      const pw = form.password.value;
      if (pw.length < 8) return showError(form, 'كلمة المرور 8 أحرف على الأقل.');
      setBusy(btn, true);
      try {
        await auth.setPassword(pw);
        toast('تم تعيين كلمة المرور، تقدر تدخل بها من التطبيق');
      } catch (err) {
        showError(form, err.message);
        setBusy(btn, false);
        btn.disabled = false;
      }
    } else if (form.dataset.form === 'delete') {
      const pwInput = form.querySelector('[name="password"]');
      const confirmInput = form.querySelector('[name="confirm"]');
      if (pwInput && !pwInput.value) return showError(form, 'اكتب كلمة المرور للتأكيد.');
      if (confirmInput && confirmInput.value.trim() !== 'حذف') return showError(form, 'اكتب كلمة «حذف» كما هي للتأكيد.');
      setBusy(btn, true, 'جارٍ الحذف…');
      try {
        await auth.deleteAccount(pwInput ? { password: pwInput.value } : { confirm: 'DELETE' });
        toast('تم حذف حسابك');
      } catch (err) {
        showError(form, err.message);
        setBusy(btn, false);
        btn.disabled = false;
      }
    }
  };

  card.addEventListener('click', onClick);
  card.addEventListener('change', onChange);
  card.addEventListener('submit', onSubmit);
  return () => {
    card.removeEventListener('click', onClick);
    card.removeEventListener('change', onChange);
    card.removeEventListener('submit', onSubmit);
  };
}
