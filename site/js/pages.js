// صفحتا «أرسل طلبك أو مشكلتك» و«الدعم والتواصل» — نفس ما في تطبيق سيكرام، بهوية iOS أبيض/أسود.
import { CONFIG } from './config.js';
import { esc } from './util.js';
import * as auth from './auth.js';
import * as requests from './requests.js';
import * as support from './support.js';
import * as ads from './ads.js';

// أيقونات خطية (24، خط 1.9، أطراف دائرية) بلون النص: لا إيموجي ولا صور.
const P = {
  film: '<rect x="3.5" y="5" width="17" height="14" rx="2.5"/><path d="M8 5v14M16 5v14M3.5 9.5H8M3.5 14.5H8M16 9.5h4.5M16 14.5h4.5"/>',
  series: '<rect x="3" y="6" width="18" height="12" rx="2.5"/><path d="M8 21h8M10 10.2v3.6l3.6-1.8z"/>',
  channel: '<path d="M4.5 9a10.5 10.5 0 0 1 15 0M7.5 12a6.3 6.3 0 0 1 9 0"/><circle cx="12" cy="16" r="1.6"/>',
  alert: '<path d="M12 4 21 19.5H3z"/><path d="M12 10v4.2M12 17v.2"/>',
  tag: '<path d="M3.5 12.5V4.5h8l9 9-8 8z"/><circle cx="8" cy="9" r="1.4"/>',
  crown: '<path d="M4 17.5 3 8l5 3.6L12 5l4 6.6L21 8l-1 9.5z"/><path d="M5 20.5h14"/>',
  wallet: '<rect x="3" y="6" width="18" height="13" rx="2.5"/><path d="M3 10h18M16 14.5h1.5"/>',
  user: '<circle cx="12" cy="8.5" r="3.6"/><path d="M4.5 20c.8-3.6 3.8-5.5 7.5-5.5s6.7 1.9 7.5 5.5"/>',
  send: '<path d="M20.500 3.500 3.500 10.500l6.500 2.500 2.500 6.500z"/><path d="m10 13 4.500-4.500"/>',
  lock: '<rect x="5" y="10.500" width="14" height="10" rx="2.500"/><path d="M8 10.500V8a4 4 0 0 1 8 0v2.500"/>',
  mic: '<rect x="9" y="3.500" width="6" height="10.500" rx="3"/><path d="M5.500 11.500a6.500 6.500 0 0 0 13 0M12 18v3"/>',
  chev: '<path d="m14.500 5-7 7 7 7"/>',
  clip: '<path d="m20.500 11.500-8.600 8.600a5.200 5.200 0 0 1-7.400-7.400l8.800-8.800a3.500 3.500 0 0 1 5 5l-8.900 8.900a1.800 1.800 0 0 1-2.500-2.500l8.200-8.200"/>',
  retry: '<path d="M4 12a8 8 0 0 1 14-5.300M20 4v4.500h-4.500M20 12a8 8 0 0 1-14 5.300M4 20v-4.500h4.500"/>',
  close: '<path d="m6.500 6.500 11 11M17.500 6.500l-11 11"/>',
};
const ic = (n) => `<svg class="ln" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${P[n] || ''}</svg>`;

// ------------------------------------------------------------------ أرسل طلبك أو مشكلتك
const PROBLEMS = ['الفيلم أو المسلسل لا يعمل', 'الفيديو يتقطع أو بطيء', 'مشكلة في الصوت أو الترجمة', 'القناة المباشرة لا تعمل', 'مشكلة في تسجيل الدخول أو الحساب'];

function mineHtml() {
  const items = requests.list();
  if (!items.length) return '';
  return `<h2 class="ios-cap">طلباتي</h2><div class="ios-group">${items.slice(0, 20).map((r) => {
    const s = requests.statusLine(r);
    const line = s.link ? `${esc(s.text)}<a class="rq-go" href="${esc(s.href)}">${esc(s.link)}</a>` : esc(s.text);
    return `<div class="ios-row rq" data-rq="${esc(r.local_id)}">${ic(r.kind === 'series' ? 'series' : r.kind === 'channel' ? 'channel' : 'film')}
      <div class="ios-col"><strong>${esc(r.title)}</strong><span>${line}</span></div>
      <button type="button" class="rq-x" data-rq-del="${esc(r.local_id)}" aria-label="حذف الطلب">×</button></div>`;
  }).join('')}</div>`;
}

export function renderRequests(el, cat) {
  document.title = `أرسل طلبك أو مشكلتك - ${CONFIG.siteName}`;
  requests.matchCatalog(cat);
  el.innerHTML = `
    <div class="page narrow ios-page">
      <h1 class="ios-title">أرسل طلبك أو مشكلتك</h1>
      <p class="ios-sub">اطلب فيلماً أو مسلسلاً أو قناة وتابع ردّ المدير هنا، أو اكتب لنا مشكلتك.</p>
      <div class="rq-tiles">
        ${['movie', 'series', 'channel'].map((k) => `<button type="button" class="rq-tile" data-kind="${k}"><i>${ic(k === 'movie' ? 'film' : k)}</i><span>طلب ${requests.kindLabel(k)}</span></button>`).join('')}
      </div>
      <form class="rq-form" hidden>
        <input type="text" name="title" maxlength="120" autocomplete="off" placeholder="الاسم (عربي أو إنجليزي)" aria-label="اسم العنوان المطلوب">
        <button type="submit" class="ios-btn">إرسال الطلب</button>
      </form>
      <div data-mine>${mineHtml()}</div>
      <h2 class="ios-cap">حل مشكلة</h2>
      <div class="ios-group">${PROBLEMS.map((p) => `<a class="ios-row" href="#/support?p=${encodeURIComponent(p)}">${ic('alert')}<div class="ios-col"><strong>${esc(p)}</strong></div>${ic('chev')}</a>`).join('')}</div>
      <div data-ad></div>
    </div>`;
  const form = el.querySelector('.rq-form');
  const mine = el.querySelector('[data-mine]');
  let kind = 'movie';
  const repaint = () => { mine.innerHTML = mineHtml(); };
  const onClick = (e) => {
    const tile = e.target.closest('.rq-tile');
    if (tile) {
      kind = tile.dataset.kind;
      el.querySelectorAll('.rq-tile').forEach((t) => t.classList.toggle('on', t === tile));
      form.hidden = false;
      form.title.placeholder = kind === 'channel' ? 'اسم القناة' : `اسم ال${requests.kindLabel(kind)} (عربي أو إنجليزي)`;
      form.title.focus();
      return;
    }
    const del = e.target.closest('[data-rq-del]');
    if (del) { requests.remove(del.dataset.rqDel); repaint(); }
  };
  const onSubmit = (e) => {
    e.preventDefault();
    if (!requests.add(kind, form.title.value)) { form.title.focus(); return; }
    form.reset();
    form.hidden = true;
    repaint();
    requests.sync().then((c) => { if (c && el.isConnected) repaint(); });
  };
  el.addEventListener('click', onClick);
  form.addEventListener('submit', onSubmit);
  requests.sync().then((c) => { if (c && el.isConnected) repaint(); });
  let offAd = () => {};
  ads.mountBottom(el.querySelector('[data-ad]')).then((off) => { offAd = off; });
  return { cleanup() { el.removeEventListener('click', onClick); offAd(); } };
}

// ------------------------------------------------------------------ الدعم والتواصل
const clock = (ts) => new Date(ts).toLocaleTimeString('ar-IQ', { hour: '2-digit', minute: '2-digit', hour12: false });

function bubble(m) {
  const sub = m.mine && m.name ? `<bdi>${esc(m.name)}</bdi>${m.size ? ' · <bdi dir="ltr">' + support.sizeLabel(m.size) + '</bdi>' : ''}` : 'يُفتح داخل تطبيق سيكرام';
  const body = m.kind
    ? `<span class="ch-file">${ic('lock')}<b>${esc(m.kind)}</b><em>${sub}</em></span>`
    : esc(m.text).replace(/\n/g, '<br>');
  return `<div class="ch-msg${m.mine ? ' mine' : ''}" data-seq="${m.seq}"><p>${body}</p><time>${clock(m.ts)}</time></div>`;
}

export function renderSupport(el, ctx) {
  document.title = `الدعم والتواصل - ${CONFIG.siteName}`;
  if (!auth.isLoggedIn()) {
    el.innerHTML = `<div class="page narrow ios-page"><h1 class="ios-title">الدعم والتواصل</h1>
      <p class="ios-sub">سجّل الدخول لتبدأ المحادثة مع فريق سيكرام عن أسعار Cigram Plus ومزاياه وأي مشكلة.</p>
      <a class="ios-btn" href="#/account">تسجيل الدخول</a></div>`;
    return {};
  }
  const preset = ctx && ctx.query ? ctx.query.get('p') || '' : '';
  el.innerHTML = `
    <div class="page narrow ios-page chat-page">
      <h1 class="ios-title">الدعم والتواصل</h1>
      <p class="ios-sub">أسعار Cigram Plus · المزايا · المشاكل</p>
      <div class="ch-list" aria-live="polite"><div class="boot"><div class="spinner"></div></div></div>
      <div class="ch-topics">${support.TOPICS.map((t, i) => `<button type="button" class="ch-chip" data-topic="${i}">${ic(t.icon)}<span>${esc(t.label)}</span></button>`).join('')}</div>
      <form class="ch-form">
        <span class="ch-mic" role="img" aria-label="الرسائل الصوتية متوقفة" title="الرسائل الصوتية متوقفة">${ic('mic')}<i></i></span>
        <button type="button" class="ch-attach" aria-label="إرفاق صورة أو فيديو أو ملف" title="إرفاق">${ic('clip')}</button>
        <input type="file" class="ch-file-input" accept="${support.ACCEPT}" hidden>
        <textarea name="text" rows="1" maxlength="2000" placeholder="اكتب رسالتك هنا" aria-label="رسالتك"></textarea>
        <button type="submit" class="ch-send" aria-label="إرسال">${ic('send')}</button>
      </form>
    </div>`;
  const listEl = el.querySelector('.ch-list');
  const form = el.querySelector('.ch-form');
  let last = 0, alive = true, timer = 0, opened = false, uploading = null;
  if (preset) form.text.value = `أواجه مشكلة: ${preset} — `;

  const append = (msgs) => {
    const fresh = msgs.filter((m) => m.seq > last || !m.seq);
    if (!fresh.length) return;
    if (!opened) { listEl.innerHTML = ''; opened = true; }
    listEl.insertAdjacentHTML('beforeend', fresh.map(bubble).join(''));
    last = Math.max(last, ...fresh.map((m) => m.seq));
    listEl.scrollTop = listEl.scrollHeight;
    support.read(last);
  };
  const poll = async () => {
    if (!alive) return;
    if (!document.hidden && opened) append(await support.after(last).catch(() => []));
    timer = setTimeout(poll, 5000);
  };
  const start = async () => {
    const r = await support.open().catch(() => ({ ok: false, messages: [] }));
    if (!alive) return;
    if (!r.ok) { timer = setTimeout(start, 4000); return; }        // بلا نص خطأ: يحاول من جديد بهدوء
    opened = true;
    if (!uploading) attachBtn.disabled = false;
    listEl.innerHTML = r.messages.length ? '' : '<p class="ch-empty">اختر موضوعاً من الأسفل أو اكتب رسالتك.</p>';
    if (r.messages.length) { opened = false; append(r.messages); }
    if (r.banned) form.hidden = true;
    poll();
  };
  const onClick = (e) => {
    const chip = e.target.closest('[data-topic]');
    if (!chip) return;
    form.text.value = support.TOPICS[Number(chip.dataset.topic)].text;
    form.text.focus();
  };
  const onSubmit = async (e) => {
    e.preventDefault();
    const text = form.text.value.trim();
    if (!text) return;
    form.text.value = '';
    const empty = listEl.querySelector('.ch-empty');
    if (empty) empty.remove();
    const sent = await support.send(text).catch(() => null);
    if (!alive) return;
    if (sent) append(sent); else form.text.value = text;            // لم تُرسل: تبقى في الصندوق ليعيد المحاولة
  };
  // ---- إرفاق ملف: فقاعة مؤقتة بشريط تقدّم، ثم تتحول إلى الرسالة. عند عدم الاكتمال: «إعادة المحاولة» تكمل من حيث توقف.
  const attachBtn = form.querySelector('.ch-attach');
  const fileInput = form.querySelector('.ch-file-input');
  const NOTE = { too_large: 'الحد الأقصى للملف 25 MB.', unsupported_type: 'الأنواع المدعومة: صور وفيديو وPDF وZIP.', banned: 'المحادثة متوقفة.', cancelled: '' };
  const pendingHtml = (up, pct, note, retry) => `<p class="ch-file">${ic('lock')}<b>${esc(up.kind === 'image' ? 'صورة' : up.kind === 'video' ? 'فيديو' : 'ملف')}</b><em><bdi>${esc(up.name)}</bdi> · <bdi dir="ltr">${support.sizeLabel(up.size)}</bdi></em></p>`
    + (note || retry
      ? `<span class="ch-note">${esc(note || 'لم يكتمل الرفع')}</span><span class="ch-acts">${retry ? `<button type="button" class="ch-act" data-up="retry">${ic('retry')}<span>إعادة المحاولة</span></button>` : ''}<button type="button" class="ch-act" data-up="drop">${ic('close')}<span>حذف</span></button></span>`
      : `<span class="ch-bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${pct}"><i style="width:${pct}%"></i></span><button type="button" class="ch-act" data-up="drop">${ic('close')}<span>إلغاء</span></button>`);
  const startUpload = async (up, box) => {
    const ctl = new AbortController();
    uploading = { up, ctl };
    attachBtn.disabled = true;
    const paint = (pct, note, retry) => { if (box.isConnected) { box.innerHTML = pendingHtml(up, pct, note, retry); box.dataset.state = note || retry ? 'stopped' : 'running'; } };
    paint(0);
    const bar = () => box.querySelector('.ch-bar i');
    const r = await support.run(up, { signal: ctl.signal, onProgress: (p) => { const b = bar(); if (b) { b.style.width = p + '%'; b.parentNode.setAttribute('aria-valuenow', p); } } });
    uploading = null;
    if (alive) attachBtn.disabled = false;
    if (!alive) return;
    if (r.ok) { box.remove(); const empty = listEl.querySelector('.ch-empty'); if (empty) empty.remove(); append(r.messages); return; }
    if (r.reason === 'cancelled') { box.remove(); return; }
    const final = Object.prototype.hasOwnProperty.call(NOTE, r.reason) && r.reason !== 'cancelled';
    paint(0, final ? NOTE[r.reason] : '', !final);                 // نوع/حجم/حظر: لا فائدة من إعادة المحاولة
    box.__up = up;
  };
  const onPick = () => {
    const file = fileInput.files && fileInput.files[0];
    fileInput.value = '';                                         // يسمح باختيار الملف نفسه مرة ثانية
    if (!file || uploading) return;
    const why = support.check(file);
    if (why === 'empty') return;
    const box = document.createElement('div');
    box.className = 'ch-msg mine ch-pending';
    const empty = listEl.querySelector('.ch-empty');
    if (empty) empty.remove();
    if (!opened) { listEl.innerHTML = ''; opened = true; }
    listEl.appendChild(box);
    listEl.scrollTop = listEl.scrollHeight;
    const up = support.createUpload(file);
    box.__up = up;
    if (why) { box.innerHTML = pendingHtml(up, 0, NOTE[why], false); box.__up = up; return; }
    startUpload(up, box);
  };
  const onUpClick = (e) => {
    const b = e.target.closest('[data-up]');
    if (!b) return;
    const box = b.closest('.ch-pending');
    if (!box) return;
    if (b.dataset.up === 'drop') { if (uploading && uploading.up === box.__up) uploading.ctl.abort(); else box.remove(); return; }
    if (b.dataset.up === 'retry' && !uploading && box.__up) startUpload(box.__up, box);
  };
  attachBtn.disabled = true;                                      // يُفتح بعد أن تُفتح المحادثة على الخادم (قبلها لا يوجد ما يُرفع إليه)
  attachBtn.addEventListener('click', () => fileInput.click());
  fileInput.addEventListener('change', onPick);
  const onKey = (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); form.requestSubmit(); } };
  el.addEventListener('click', onClick);
  el.addEventListener('click', onUpClick);
  form.addEventListener('submit', onSubmit);
  form.text.addEventListener('keydown', onKey);
  start();
  return { cleanup() { alive = false; clearTimeout(timer); if (uploading) uploading.ctl.abort(); el.removeEventListener('click', onClick); el.removeEventListener('click', onUpClick); } };
}
