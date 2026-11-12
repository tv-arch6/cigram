import { CONFIG } from './config.js';
import * as store from './store.js';
import { allEpisodes, findEpisode, nextEpisode } from './catalog.js';
import { esc, fmtDuration, fmtClock, ICONS, debounce, norm } from './util.js';
import { subSettingsHtml, bindSubSettings, mountSubPreview } from './subtitle-view.js';
import * as auth from './auth.js';
import { authPanelHtml, bindAuthPanel, accountCardHtml, bindAccountCard } from './auth-view.js';
import { posterCard, factsHtml, playTarget as playTarget2, pimg } from './home-view.js';
import { sym } from './icons.js';
import * as plus from './plus.js';
import { accountBoxHtml } from './plus-view.js';
import { mountExternalLink } from './external-link.js';

const BADGES = { new: 'جديد', exclusive: 'حصري', featured: 'مميز', trending: 'الأكثر مشاهدة' };
const TYPE_LABEL = { movie: 'فيلم', series: 'مسلسل' };

// ------------------------------------------------------------------ shared pieces
function img(src, alt = '', cls = '') {
  if (!src) return `<div class="img-fallback ${cls}" aria-hidden="true"></div>`;
  return `<img class="${cls}" src="${esc(src)}" alt="${esc(alt)}" loading="lazy" decoding="async">`;
}

function badge(item) {
  const b = item.badges.find((x) => BADGES[x]);
  return b ? `<span class="badge">${BADGES[b]}</span>` : '';
}

function card(item) {
  const facts = [item.year || '', TYPE_LABEL[item.type]].filter(Boolean).map((x) => `<span>${esc(x)}</span>`).join('');
  return `<a class="card" href="#/title/${encodeURIComponent(item.id)}">
    <div class="poster">${img(item.poster, item.title)}${badge(item)}</div>
    <div class="c-title">${esc(item.title)}</div>
    <div class="c-meta">${facts}</div>
  </a>`;
}

function resumeCard(entry, item) {
  const ep = entry.season ? findEpisode(item, entry.season, entry.ep) : null;
  const label = ep ? `م${entry.season} ح${entry.ep}` : (item.year || '');
  const pct = entry.dur ? Math.min(100, Math.round((entry.pos / entry.dur) * 100)) : 0;
  const thumb = (ep && ep.thumb) || item.backdrop || item.poster;
  return `<a class="card wide" href="#/watch/${encodeURIComponent(item.id)}${entry.season ? `/${entry.season}/${entry.ep}` : ''}">
    <div class="poster">${img(thumb, item.title)}
      <span class="play-chip">${ICONS.play}</span>
      <div class="bar"><i style="width:${pct}%"></i></div>
    </div>
    <div class="c-title">${esc(item.title)}</div>
    <div class="c-meta"><span>${esc(label)}</span>${entry.dur ? `<span>باقي ${esc(fmtDuration(Math.max(0, entry.dur - entry.pos)))}</span>` : ''}</div>
  </a>`;
}

function row(name, itemsHtml, extraClass = '') {
  return `<section class="row ${extraClass}">
    <div class="row-head"><h2>${esc(name)}</h2></div>
    <div class="row-wrap">
      <button type="button" class="arrow prev" data-scroll="-1" aria-label="السابق">${ICONS.chevR}</button>
      <div class="track" tabindex="0">${itemsHtml}</div>
      <button type="button" class="arrow next" data-scroll="1" aria-label="التالي">${ICONS.chevL}</button>
    </div>
  </section>`;
}

function emptyState(title, text, href, action) {
  return `<div class="empty"><h2>${esc(title)}</h2><p>${esc(text)}</p>${href ? `<a class="btn btn-primary" href="${href}">${esc(action)}</a>` : ''}</div>`;
}

/** العنصر الذي يجب أن يستكمل منه المشاهد: حلقة غير منتهية أو الحلقة التالية. */
export function continueList(cat) {
  const out = [];
  for (const e of store.latestPerTitle()) {
    const item = cat.get(e.id);
    if (!item) continue;
    if (!e.done && e.pos > 5) { out.push({ entry: e, item }); continue; }
    if (item.type === 'series' && e.done) {
      const n = nextEpisode(item, e.season, e.ep);
      if (n) out.push({ entry: { id: item.id, season: n.season, ep: n.number, pos: 0, dur: 0 }, item });
    }
  }
  return out;
}

/** هل للعنوان ترجمة؟ (الفيلم نفسه، أو حلقة واحدة على الأقل من المسلسل) */
function hasSubtitles(item) {
  if (item.type === 'movie') return item.subtitles.length > 0;
  return item.seasons.some((s) => s.episodes.some((e) => e.subtitles.length > 0));
}

function playTarget(cat, item) {
  // يحدد زر التشغيل في صفحة التفاصيل: { href, label }
  const base = `#/watch/${encodeURIComponent(item.id)}`;
  if (item.type === 'movie') {
    const p = store.getProgress(store.progressKey(item.id));
    if (p && !p.done && p.pos > 5) return { href: base, label: `متابعة من ${fmtClock(p.pos)}` };
    return { href: base, label: 'شاهد الآن' };
  }
  const eps = allEpisodes(item);
  const mine = store.latestPerTitle().find((e) => e.id === item.id);
  if (mine) {
    if (!mine.done && mine.pos > 5) return { href: `${base}/${mine.season}/${mine.ep}`, label: `متابعة م${mine.season} ح${mine.ep}` };
    const n = nextEpisode(item, mine.season, mine.ep);
    if (n) return { href: `${base}/${n.season}/${n.number}`, label: `شاهد م${n.season} ح${n.number}` };
  }
  const first = eps[0];
  return first ? { href: `${base}/${first.season}/${first.number}`, label: 'ابدأ المشاهدة' } : { href: '#/', label: '' };
}

// ------------------------------------------------------------------ home
function renderHomeV1(el, cat, ctx) {
  const hero = cat.featured(5);
  const cont = continueList(cat);
  const name = store.getName();
  let html = '';

  if (hero.length) {
    html += `<section class="hero" aria-roledescription="carousel" aria-label="عناوين مميزة">
      <div class="hero-slides">
        ${hero.map((it, i) => `
          <article class="slide${i === 0 ? ' on' : ''}" data-i="${i}" ${i ? 'aria-hidden="true"' : ''}>
            ${img(it.backdrop, '', 'slide-bg')}
            <div class="slide-shade"></div>
            <div class="slide-body">
              ${name && i === 0 ? `<p class="hello">أهلاً ${esc(name)}</p>` : ''}
              <h1>${esc(it.title)}</h1>
              <div class="facts">${heroFacts(it)}</div>
              <p class="desc">${esc(it.description)}</p>
              <div class="actions">
                <a class="btn btn-primary" href="${playTarget(cat, it).href}">${ICONS.play}<span>${esc(playTarget(cat, it).label)}</span></a>
                <a class="btn btn-ghost" href="#/title/${encodeURIComponent(it.id)}">التفاصيل</a>
              </div>
            </div>
          </article>`).join('')}
      </div>
      ${hero.length > 1 ? `<div class="rail" role="tablist" aria-label="اختيار العنوان">
        ${hero.map((it, i) => `<button type="button" role="tab" class="${i === 0 ? 'on' : ''}" data-hero="${i}" aria-label="${esc(it.title)}" aria-selected="${i === 0}"><i></i></button>`).join('')}
      </div>` : ''}
    </section>`;
  } else if (name) {
    html += `<p class="hello solo">أهلاً ${esc(name)}</p>`;
  }

  html += '<div class="rows">';
  if (cont.length) {
    html += row('تابع المشاهدة', cont.map(({ entry, item }) => resumeCard(entry, item)).join(''), 'wide-row');
  }
  for (const s of cat.sections()) html += row(s.name, s.items.map(card).join(''));
  html += '</div>';

  if (!cat.items.length) html = emptyState('لا يوجد محتوى بعد', 'المحتوى الذي يُضاف في تطبيق سيكرام يظهر هنا تلقائياً.');
  el.innerHTML = html;
  const cleanup = hero.length > 1 ? startHero(el) : () => {};
  return { cleanup };
}

function heroFacts(it) {
  const parts = [];
  if (it.rating) parts.push(`<span class="rate">${ICONS.star}${it.rating.toFixed(1)}</span>`);
  if (it.year) parts.push(`<span>${it.year}</span>`);
  if (it.quality) parts.push(`<span class="tag">${esc(it.quality)}</span>`);
  parts.push(`<span>${TYPE_LABEL[it.type]}</span>`);
  if (it.type === 'movie' && it.duration) parts.push(`<span>${esc(fmtDuration(it.duration))}</span>`);
  if (it.type === 'series') parts.push(`<span>${it.seasons.length} ${it.seasons.length === 1 ? 'موسم' : 'مواسم'}</span>`);
  return parts.join('');
}

function startHero(el) {
  const slides = Array.from(el.querySelectorAll('.slide'));
  const tabs = Array.from(el.querySelectorAll('.rail button'));
  const hero = el.querySelector('.hero');
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  let cur = 0;
  let timer = null;
  hero.style.setProperty('--hero-ms', `${CONFIG.heroSeconds * 1000}ms`);

  function go(i) {
    cur = (i + slides.length) % slides.length;
    slides.forEach((s, n) => { s.classList.toggle('on', n === cur); s.setAttribute('aria-hidden', n === cur ? 'false' : 'true'); });
    tabs.forEach((t, n) => {
      t.classList.toggle('on', n === cur);
      t.setAttribute('aria-selected', String(n === cur));
      // يعيد تشغيل حركة الشريط
      if (n === cur) { t.classList.remove('run'); void t.offsetWidth; if (!reduce && !paused) t.classList.add('run'); }
      else t.classList.remove('run');
    });
  }
  let paused = reduce;
  function schedule() {
    clearTimeout(timer);
    if (paused) return;
    timer = setTimeout(() => { go(cur + 1); schedule(); }, CONFIG.heroSeconds * 1000);
  }
  const pause = () => { paused = true; clearTimeout(timer); tabs.forEach((t) => t.classList.remove('run')); };
  const resume = () => { if (reduce) return; paused = false; go(cur); schedule(); };

  tabs.forEach((t) => t.addEventListener('click', () => { go(Number(t.dataset.hero)); if (!reduce) schedule(); }));
  hero.addEventListener('mouseenter', pause);
  hero.addEventListener('mouseleave', resume);
  hero.addEventListener('focusin', pause);
  hero.addEventListener('focusout', resume);
  const vis = () => { if (document.hidden) pause(); else resume(); };
  document.addEventListener('visibilitychange', vis);

  // سحب أفقي على اللمس
  let x0 = null;
  hero.addEventListener('touchstart', (e) => { x0 = e.touches[0].clientX; }, { passive: true });
  hero.addEventListener('touchend', (e) => {
    if (x0 == null) return;
    const dx = e.changedTouches[0].clientX - x0;
    x0 = null;
    if (Math.abs(dx) > 50) { go(cur + (dx < 0 ? 1 : -1)); if (!reduce) schedule(); }
  }, { passive: true });

  go(0);
  schedule();
  return () => { clearTimeout(timer); document.removeEventListener('visibilitychange', vis); };
}

// ------------------------------------------------------------------ search / browse
export function renderSearch(el, cat, ctx) {
  const genres = cat.genres();
  const state = { q: ctx.query.get('q') || '', genre: ctx.query.get('g') || '', type: ctx.query.get('t') || '' };

  el.innerHTML = `
    <div class="page search-page">
      <form class="search-box" role="search" onsubmit="return false">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6.5"/><path d="m16 16 4.5 4.5"/></svg>
        <input id="q" type="search" inputmode="search" enterkeyhint="search" autocomplete="off" placeholder="ابحث عن فيلم أو مسلسل" value="${esc(state.q)}" aria-label="بحث">
      </form>
      <div class="chips" role="group" aria-label="تصفية">
        <button type="button" class="chip" data-type="" aria-pressed="false">الكل</button>
        <button type="button" class="chip" data-type="movie" aria-pressed="false">أفلام</button>
        <button type="button" class="chip" data-type="series" aria-pressed="false">مسلسلات</button>
        ${genres.map((g) => `<button type="button" class="chip g" data-genre="${esc(g)}" aria-pressed="false">${esc(g)}</button>`).join('')}
      </div>
      <p class="result-count" id="count" aria-live="polite"></p>
      <div class="grid" id="results"></div>
    </div>`;

  const input = el.querySelector('#q');
  const results = el.querySelector('#results');
  const count = el.querySelector('#count');

  function paint() {
    const found = cat.search(state.q, { genre: state.genre, type: state.type });
    results.innerHTML = found.length
      ? found.map((it) => posterCard(it)).join('')
      : `<div class="empty inline"><h2>لا توجد نتائج</h2><p>جرّب كلمة أقصر أو غيّر التصفية.</p></div>`;
    count.textContent = found.length ? `${found.length} ${found.length === 1 ? 'عنوان' : 'عناوين'}` : '';
    el.querySelectorAll('.chip').forEach((c) => {
      const on = c.dataset.genre !== undefined ? norm(c.dataset.genre) === norm(state.genre) && !!state.genre
        : (c.dataset.type || '') === state.type && !state.genre;
      c.classList.toggle('on', on);
      c.setAttribute('aria-pressed', String(on));
    });
    const p = new URLSearchParams();
    if (state.q) p.set('q', state.q);
    if (state.genre) p.set('g', state.genre);
    if (state.type) p.set('t', state.type);
    const qs = p.toString();
    history.replaceState(null, '', '#/search' + (qs ? '?' + qs : ''));
  }

  input.addEventListener('input', debounce(() => { state.q = input.value.trim(); paint(); }, 150));
  el.querySelector('.chips').addEventListener('click', (e) => {
    const c = e.target.closest('.chip');
    if (!c) return;
    if (c.dataset.genre !== undefined) { state.genre = state.genre === c.dataset.genre ? '' : c.dataset.genre; state.type = ''; }
    else { state.type = c.dataset.type; state.genre = ''; }
    paint();
  });
  paint();
  if (!state.q && !matchMedia('(pointer: coarse)').matches) input.focus({ preventScroll: true });
  return {};
}

// ------------------------------------------------------------------ title details (v2: مثل صفحة الفيلم في سينمانا / التطبيق)
export function renderTitle(el, cat, ctx) {
  const item = cat.get(ctx.params[0]);
  if (!item) { el.innerHTML = emptyState('العنوان غير موجود', 'ربما حُذف من الكتالوج.', '#/', 'العودة للرئيسية'); return {}; }

  const target = playTarget2(cat, item);
  const related = cat.related(item);
  const inList = store.inList(item.id);
  const cc = hasSubtitles(item);

  el.innerHTML = `
    <article class="dv">
      <div class="dv-hero">${pimg(item.backdrop || item.poster, '', 'dv-bg', true)}<div class="dv-shade"></div>
        <div class="dv-top">
          <button type="button" class="dv-round" data-back aria-label="رجوع">${sym('arrow_forward')}</button>
          <button type="button" class="dv-round" data-share="${esc(item.id)}" aria-label="مشاركة">${sym('share')}</button>
        </div>
      </div>
      <div class="dv-body">
        <div class="dv-head">
          <div class="dv-poster">${pimg(item.poster, item.title, '', true)}</div>
          <div class="dv-titles">
            <h1>${esc(item.title)}</h1>
            ${item.original && item.original !== item.title ? `<p class="dv-orig">${esc(item.original)}</p>` : ''}
            <div class="dv-facts">${factsHtml(item)}${cc ? `<span class="tag cc">${sym('subtitles')}مترجم</span>` : ''}</div>
          </div>
        </div>
        <div class="dv-actions">
          ${target.label ? `<a class="btn btn-primary dv-watch" href="${target.href}">${sym('play_arrow', true)}<span>${esc(target.label)}</span></a>` : ''}
          <button type="button" class="dv-act" data-mylist="${esc(item.id)}" aria-pressed="${inList}">${sym(inList ? 'check' : 'add')}<span>قائمتي</span></button>
          <button type="button" class="dv-act" data-share="${esc(item.id)}">${sym('share')}<span>مشاركة</span></button>
        </div>
        ${item.genres.length ? `<div class="genres">${item.genres.map((g) => `<a href="#/search?g=${encodeURIComponent(g)}">${esc(g)}</a>`).join('')}</div>` : ''}
        ${item.description ? `<p class="dv-desc">${esc(item.description)}</p>` : ''}
      </div>
      ${item.type === 'series' ? `<section class="episodes" id="episodes"></section>` : ''}
      ${related.length ? `<section class="hrow"><div class="hrow-head"><h2>قد يعجبك أيضاً</h2></div><div class="hrow-wrap"><div class="track">${related.map((it) => posterCard(it)).join('')}</div></div></section>` : ''}
    </article>`;

  const desc = el.querySelector('.dv-desc');
  if (desc) desc.addEventListener('click', () => desc.classList.toggle('open'));

  if (item.type === 'series') {
    const mine = store.latestPerTitle().find((e) => e.id === item.id);
    let season = mine ? mine.season : item.seasons[0].number;
    if (!item.seasons.some((s) => s.number === season)) season = item.seasons[0].number;
    const box = el.querySelector('#episodes');
    const paintEps = () => {
      const s = item.seasons.find((x) => x.number === season) || item.seasons[0];
      box.innerHTML = `
        ${item.seasons.length > 1 ? `<div class="seasons" role="tablist">${item.seasons.map((x) => `<button type="button" role="tab" class="${x.number === s.number ? 'on' : ''}" data-season="${x.number}" aria-selected="${x.number === s.number}">${esc(x.title)}</button>`).join('')}</div>` : `<h2 class="eps-title">${esc(s.title)}</h2>`}
        <ol class="eps">${s.episodes.map((e) => {
          const p = store.getProgress(store.progressKey(item.id, s.number, e.number));
          const pct = p && p.dur ? Math.min(100, Math.round((p.pos / p.dur) * 100)) : 0;
          return `<li><a class="ep" href="#/watch/${encodeURIComponent(item.id)}/${s.number}/${e.number}">
            <div class="ep-thumb">${pimg(e.thumb || item.backdrop, e.title)}<span class="play-chip">${sym('play_arrow', true)}</span>${pct && !p.done ? `<div class="bar"><i style="width:${pct}%"></i></div>` : ''}</div>
            <div class="ep-info"><strong><span class="ep-n">${e.number}</span>${esc(e.title)}</strong>
              ${e.description ? `<p>${esc(e.description)}</p>` : ''}
              <span class="ep-meta">${e.duration ? `<span>${esc(fmtDuration(e.duration))}</span>` : ''}${e.subtitles.length ? '<span class="cc-chip">مترجم</span>' : ''}${p && p.done ? `<span class="seen">${ICONS.check}تمت المشاهدة</span>` : ''}</span></div>
          </a></li>`;
        }).join('')}</ol>`;
    };
    paintEps();
    box.addEventListener('click', (e) => {
      const b = e.target.closest('[data-season]');
      if (!b) return;
      season = Number(b.dataset.season);
      paintEps();
    });
  }
  document.title = `${item.title} - ${CONFIG.siteName}`;
  return {};
}

// ------------------------------------------------------------------ account (local)
export function renderAccount(el, cat, ctx) {
  const user = auth.currentUser();
  if (!user) {
    // الزائر الجديد: صفحة فارغة إلا من الدخول وإنشاء الحساب. كل شيء آخر يظهر بعد الدخول.
    el.innerHTML = `<div class="page narrow auth-page">${authPanelHtml()}</div>`;
    document.title = `حسابي - ${CONFIG.siteName}`;
    const unauth = bindAuthPanel(el);
    return { cleanup: () => unauth() };
  }
  const hours = store.totalWatchedSeconds() / 3600;
  const iosSafari = /iphone|ipad|ipod/i.test(navigator.userAgent) && !window.navigator.standalone;
  const installed = window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone;
  const previewItem = cat && cat.items.find((i) => i.backdrop);
  const previewBg = previewItem ? previewItem.backdrop : '';
  const stats = `<dl class="stats">
          <div><dt>عناوين شاهدتها</dt><dd>${store.latestPerTitle().length}</dd></div>
          <div><dt>ساعات المشاهدة</dt><dd>${hours < 0.1 ? '0' : hours.toFixed(1)}</dd></div>
        </dl>`;
  el.innerHTML = `
    <div class="page narrow">
      <h1 class="page-title">حسابي</h1>
      ${accountCardHtml(user, stats)}
      ${accountBoxHtml()}

      <section class="panel">
        <h2>التشغيل</h2>
        <label class="switch">
          <input type="checkbox" id="autoNext" ${store.getSetting('autoplayNext') ? 'checked' : ''}>
          <span class="track-sw" aria-hidden="true"></span>
          <span>تشغيل الحلقة التالية تلقائياً</span>
        </label>
      </section>

      <section class="panel" id="subtitles">
        <h2>الترجمة</h2>
        <p>نفس اختيارات «خطوطي» في تطبيق سيكرام، وتُطبَّق على كل الأفلام والحلقات في هذا المتصفح.</p>
        <div class="sub-preview"${previewBg ? ` style="background-image: url('${esc(previewBg.replace(/'/g, '%27'))}'), linear-gradient(160deg, #1d3a48, #3b2b22)"` : ''}>
          <div class="sub-block bottom">
            <div class="sub-row"><span class="sub-ln">هكذا تظهر الترجمة في سيكرام</span></div>
            <div class="sub-row"><span class="sub-ln">واضحة على كل الشاشات</span></div>
          </div>
        </div>
        <div data-subsettings>${subSettingsHtml()}</div>
      </section>

      <section class="panel">
        <h2>تثبيت على الجهاز</h2>
        ${installed ? '<p>الموقع مثبت على جهازك.</p>' : `
          <p id="installHint">${iosSafari ? 'في الآيفون: اضغط زر المشاركة في Safari ثم «إضافة إلى الشاشة الرئيسية».' : 'ثبّت الموقع ليفتح بنافذة مستقلة مثل التطبيقات.'}</p>
          <button type="button" class="btn btn-primary btn-sm" data-action="install" hidden>تثبيت</button>`}
      </section>

      <section class="panel">
        <h2>بياناتك</h2>
        <p>القائمة وسجل المشاهدة محفوظان في هذا المتصفح فقط. لنقلهما إلى جهاز آخر، صدّر نسخة احتياطية ثم استوردها هناك.</p>
        ${store.isMemoryOnly() ? '<p class="warn">المتصفح يمنع حفظ البيانات (ربما تصفح خاص)، فلن تبقى بعد إغلاق الصفحة.</p>' : ''}
        <div class="actions">
          <button type="button" class="btn btn-ghost btn-sm" data-action="export">تصدير نسخة احتياطية</button>
          <label class="btn btn-ghost btn-sm file-btn">استيراد نسخة<input type="file" id="importFile" accept="application/json,.json" hidden></label>
          <button type="button" class="btn btn-danger btn-sm" data-action="clear-history">مسح سجل المشاهدة</button>
        </div>
      </section>
      <div class="ext-host" data-ext hidden></div>
    </div>`;
  document.title = `حسابي - ${CONFIG.siteName}`;
  mountExternalLink(el.querySelector('[data-ext]'), cat ? cat.items : []);
  const unbind = bindSubSettings(el.querySelector('[data-subsettings]'));
  const unpreview = mountSubPreview(el.querySelector('.sub-preview'));
  const unauth = bindAccountCard(el);
  const offPlus = plus.onChange(() => {
    const box = el.querySelector('#plBox');
    if (!box) return;
    const t = document.createElement('div');
    t.innerHTML = accountBoxHtml();
    box.replaceWith(t.firstElementChild);
  });
  plus.refresh();
  return { cleanup: () => { unbind(); unpreview(); unauth(); offPlus(); } };
}
