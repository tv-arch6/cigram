// الرئيسية (v2): نفس رئيسية تطبيق سيكرام.
//  - في الأعلى شرائح البوسترات المتحركة (مثل التطبيق: بطاقة في الوسط وجيرانها ظاهرة)، وعلى الحاسوب بانر كبير بأسلوب نتفلكس.
//  - «تابع المشاهدة» ثم «قائمتي» ثم «أحدث الأفلام» و«المسلسلات المميزة» ثم أقسام لوحة الإدارة بترتيبها.
//  - كل قسم بنمطه من تطبيق الإدارة (/home-layout): row، top10 (أرقام كبيرة)، landscape (صور عريضة)، large، billboard.
//  - الأحدث في أقصى اليمين (RTL)، و«المزيد» يفتح كل عناوين القسم.
//  - الصفوف تُبنى على دفعات (الظاهر أولاً) فتظهر الرئيسية فوراً حتى على الأجهزة القديمة.
import { CONFIG } from './config.js';
import * as store from './store.js';
import * as layout from './layout.js';
import { allEpisodes, findEpisode, nextEpisode } from './catalog.js';
import { esc, fmtClock, fmtDuration } from './util.js';
import { sym } from './icons.js';

export const ROW_MAX = 40;
const TYPE_LABEL = { movie: 'فيلم', series: 'مسلسل' };

// ------------------------------------------------------------------ pieces
export function pimg(src, alt = '', cls = '', eager = false) {
  if (!src) return `<div class="img-fallback ${cls}" aria-hidden="true"></div>`;
  return `<img class="${cls}" src="${esc(src)}" alt="${esc(alt)}" ${eager ? 'fetchpriority="high"' : 'loading="lazy"'} decoding="async">`;
}

function metaLine(item) {
  const parts = [];
  if (item.year) parts.push(String(item.year));
  if (item.genres.length) parts.push(...item.genres.slice(0, 2));
  else parts.push(TYPE_LABEL[item.type]);
  return parts.map(esc).join(' | ');
}

function isNew(item) {
  return item.addedMs && Date.now() - item.addedMs < 7 * 24 * 3600 * 1000;
}

/** بطاقة البوستر (مثل بطاقات التطبيق: البوستر ثم تقييم IMDb ثم الاسم ثم السنة والتصنيف). */
export function posterCard(item, { large = false } = {}) {
  const eps = item.type === 'series' ? allEpisodes(item).length : 0;
  return `<a class="pc${large ? ' lg' : ''}" href="#/title/${encodeURIComponent(item.id)}" data-id="${esc(item.id)}">
    <div class="pc-poster">${pimg(item.poster, item.title)}
      ${isNew(item) ? '<span class="pc-new">جديد</span>' : ''}
      ${eps ? `<span class="pc-eps">${eps} ${eps === 1 ? 'حلقة' : 'حلقة'}</span>` : ''}
    </div>
    ${item.rating ? `<div class="pc-rate"><b>${item.rating.toFixed(1)}</b><i class="imdb">IMDb</i></div>` : '<div class="pc-rate empty"></div>'}
    <div class="pc-title">${esc(item.title)}</div>
    <div class="pc-meta">${metaLine(item)}</div>
  </a>`;
}

function top10Card(item, rank) {
  return `<a class="t10" href="#/title/${encodeURIComponent(item.id)}" data-id="${esc(item.id)}" aria-label="${rank}. ${esc(item.title)}">
    <span class="t10-n" aria-hidden="true">${rank}</span>
    <div class="pc-poster">${pimg(item.poster, item.title)}</div>
  </a>`;
}

function landscapeCard(item) {
  return `<a class="lc" href="#/title/${encodeURIComponent(item.id)}" data-id="${esc(item.id)}">
    <div class="lc-img">${pimg(item.backdrop || item.poster, item.title)}<div class="lc-shade"></div>
      <div class="lc-cap"><strong>${esc(item.title)}</strong><span>${metaLine(item)}</span></div>
    </div>
  </a>`;
}

function resumeCard(entry, item) {
  const ep = entry.season ? findEpisode(item, entry.season, entry.ep) : null;
  const label = ep ? `م${entry.season} ح${entry.ep}` : (item.year || '');
  const pct = entry.dur ? Math.min(100, Math.round((entry.pos / entry.dur) * 100)) : 0;
  const thumb = (ep && ep.thumb) || item.backdrop || item.poster;
  return `<a class="lc rc" href="#/watch/${encodeURIComponent(item.id)}${entry.season ? `/${entry.season}/${entry.ep}` : ''}">
    <div class="lc-img">${pimg(thumb, item.title)}<div class="lc-shade"></div>
      <span class="rc-play">${sym('play_arrow', true)}</span>
      <div class="lc-cap"><strong>${esc(item.title)}</strong><span>${esc(label)}${entry.dur ? ` · باقي ${esc(fmtDuration(Math.max(0, entry.dur - entry.pos)))}` : ''}</span></div>
      <div class="rc-bar"><i style="width:${pct}%"></i></div>
    </div>
  </a>`;
}

function billboardBlock(cat, item, cls = '') {
  const t = playTarget(cat, item);
  const inList = store.inList(item.id);
  return `<section class="bb ${cls}" aria-label="${esc(item.title)}">
    <div class="bb-bg">${pimg(item.backdrop || item.poster, '', '', true)}<div class="bb-shade"></div></div>
    <div class="bb-body">
      <div class="bb-poster">${pimg(item.poster, item.title, '', true)}</div>
      <div class="bb-info">
        <h2 class="bb-title">${esc(item.title)}</h2>
        <div class="bb-facts">${factsHtml(item)}</div>
        ${item.description ? `<p class="bb-desc">${esc(item.description)}</p>` : ''}
        <div class="bb-actions">
          ${t.label ? `<a class="btn btn-primary" href="${t.href}">${sym('play_arrow', true)}<span>${esc(t.label)}</span></a>` : ''}
          <button type="button" class="btn btn-ghost" data-mylist="${esc(item.id)}" aria-pressed="${inList}">${sym(inList ? 'check' : 'add')}<span>قائمتي</span></button>
          <a class="btn btn-ghost bb-more" href="#/title/${encodeURIComponent(item.id)}">${sym('info')}<span>التفاصيل</span></a>
        </div>
      </div>
    </div>
  </section>`;
}

export function factsHtml(item) {
  const parts = [];
  if (item.rating) parts.push(`<span class="rate"><i class="imdb">IMDb</i>${item.rating.toFixed(1)}</span>`);
  if (item.year) parts.push(`<span>${item.year}</span>`);
  if (item.quality) parts.push(`<span class="tag">${esc(item.quality)}</span>`);
  parts.push(`<span>${TYPE_LABEL[item.type]}</span>`);
  if (item.type === 'movie' && item.duration) parts.push(`<span>${esc(fmtDuration(item.duration))}</span>`);
  if (item.type === 'series') parts.push(`<span>${item.seasons.length} ${item.seasons.length === 1 ? 'موسم' : 'مواسم'}</span>`);
  return parts.join('');
}

/** زر التشغيل: { href, label } (يكمل من حيث توقف المشاهد). */
export function playTarget(cat, item) {
  const base = `#/watch/${encodeURIComponent(item.id)}`;
  if (item.type === 'movie') {
    const p = store.getProgress(store.progressKey(item.id));
    if (p && !p.done && p.pos > 5) return { href: base, label: `متابعة من ${fmtClock(p.pos)}` };
    return { href: base, label: 'مشاهدة' };
  }
  const eps = allEpisodes(item);
  const mine = store.latestPerTitle().find((e) => e.id === item.id);
  if (mine) {
    if (!mine.done && mine.pos > 5) return { href: `${base}/${mine.season}/${mine.ep}`, label: `متابعة م${mine.season} ح${mine.ep}` };
    const n = nextEpisode(item, mine.season, mine.ep);
    if (n) return { href: `${base}/${n.season}/${n.number}`, label: `مشاهدة م${n.season} ح${n.number}` };
  }
  const first = eps[0];
  return first ? { href: `${base}/${first.season}/${first.number}`, label: 'مشاهدة' } : { href: '#/', label: '' };
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

function rowHtml({ id, name, style, items, more }) {
  let cards;
  let cls = 'row-' + style;
  if (style === 'top10') cards = items.slice(0, 10).map((it, i) => top10Card(it, i + 1)).join('');
  else if (style === 'landscape') cards = items.slice(0, ROW_MAX).map(landscapeCard).join('');
  else if (style === 'large') cards = items.slice(0, ROW_MAX).map((it) => posterCard(it, { large: true })).join('');
  else cards = items.slice(0, ROW_MAX).map((it) => posterCard(it)).join('');
  return `<section class="hrow ${cls}" data-row="${esc(id)}">
    <div class="hrow-head">
      <h2>${esc(name)}</h2>
      ${more ? `<a class="hrow-more" href="${more}">المزيد${sym('chevron_left')}</a>` : ''}
    </div>
    <div class="hrow-wrap">
      <button type="button" class="arrow prev" data-scroll="-1" aria-label="السابق">${sym('chevron_right')}</button>
      <div class="track">${cards}</div>
      <button type="button" class="arrow next" data-scroll="1" aria-label="التالي">${sym('chevron_left')}</button>
    </div>
  </section>`;
}

// ------------------------------------------------------------------ the hero (app carousel / desktop billboard)
function heroItems(cat) {
  // 1) شرائح لوحة الإدارة (/carousel) كما في التطبيق، 2) العناوين المميزة، 3) أحدث العناوين
  const out = [];
  const seen = new Set();
  for (const c of cat.carousel || []) {
    const id = String(c.content_id ?? c.id ?? '').trim();
    const item = (id && cat.get(id)) || null;
    if (!item || seen.has(item.id)) continue;
    seen.add(item.id);
    out.push({ item, image: String(c.custom_image || c.poster || '').trim() });
  }
  if (!out.length) for (const item of cat.featured(8)) { if (!seen.has(item.id)) { seen.add(item.id); out.push({ item, image: '' }); } }
  return out.slice(0, 10);
}

function coverHtml(slides) {
  return `<section class="cover" aria-roledescription="carousel" aria-label="عناوين مميزة">
    <div class="cover-track" tabindex="-1">
      ${slides.map(({ item, image }, i) => `
        <a class="cover-card" href="#/title/${encodeURIComponent(item.id)}" data-i="${i}" aria-label="${esc(item.title)}">
          ${pimg(image || item.poster, item.title, '', i < 2)}
          <div class="cover-cap"><strong>${esc(item.title)}</strong><span>${TYPE_LABEL[item.type]}</span></div>
        </a>`).join('')}
    </div>
    ${slides.length > 1 ? `<div class="cover-dots" aria-hidden="true">${slides.map((_, i) => `<i class="${i === 0 ? 'on' : ''}"></i>`).join('')}</div>` : ''}
  </section>`;
}

function startCover(el) {
  const box = el.querySelector('.cover');
  if (!box) return { pause() {}, resume() {}, stop() {} };
  const track = box.querySelector('.cover-track');
  const cards = Array.from(box.querySelectorAll('.cover-card'));
  const dots = Array.from(box.querySelectorAll('.cover-dots i'));
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  let cur = 0;
  let timer = 0;
  let paused = false;
  let touching = false;

  const centerOf = (c) => c.offsetLeft + c.offsetWidth / 2;
  function nearest() {
    const mid = track.scrollLeft + track.clientWidth / 2;
    let best = 0, dist = Infinity;
    cards.forEach((c, i) => { const d = Math.abs(centerOf(c) - mid); if (d < dist) { dist = d; best = i; } });
    return best;
  }
  function mark(i) {
    cur = i;
    cards.forEach((c, n) => c.classList.toggle('on', n === i));
    dots.forEach((d, n) => d.classList.toggle('on', n === i));
  }
  function go(i, smooth = true) {
    if (!cards.length) return;
    const n = (i + cards.length) % cards.length;
    const c = cards[n];
    track.scrollTo({ left: centerOf(c) - track.clientWidth / 2, behavior: smooth && !reduce ? 'smooth' : 'auto' });
    mark(n);
  }
  function schedule() {
    clearTimeout(timer);
    if (paused || reduce || cards.length < 2) return;
    timer = setTimeout(() => { if (!touching) go(cur + 1); schedule(); }, CONFIG.heroSeconds * 1000);
  }
  let scrollT = 0;
  track.addEventListener('scroll', () => {
    clearTimeout(scrollT);
    scrollT = setTimeout(() => mark(nearest()), 80);
  }, { passive: true });
  track.addEventListener('touchstart', () => { touching = true; clearTimeout(timer); }, { passive: true });
  track.addEventListener('touchend', () => { touching = false; schedule(); }, { passive: true });
  // البطاقة الأولى في الوسط من البداية
  requestAnimationFrame(() => { go(0, false); schedule(); });
  return {
    pause() { paused = true; clearTimeout(timer); },
    resume() { paused = false; schedule(); },
    stop() { paused = true; clearTimeout(timer); },
  };
}

function startBillboard(el) {
  const slides = Array.from(el.querySelectorAll('.bbs .bb'));
  if (slides.length < 2) return { pause() {}, resume() {}, stop() {} };
  const dots = Array.from(el.querySelectorAll('.bbs-dots button'));
  let cur = 0, timer = 0, paused = false;
  const go = (i) => {
    cur = (i + slides.length) % slides.length;
    slides.forEach((s, n) => s.classList.toggle('on', n === cur));
    dots.forEach((d, n) => d.classList.toggle('on', n === cur));
  };
  const schedule = () => { clearTimeout(timer); if (!paused) timer = setTimeout(() => { go(cur + 1); schedule(); }, CONFIG.heroSeconds * 1000); };
  dots.forEach((d, n) => d.addEventListener('click', () => { go(n); schedule(); }));
  const box = el.querySelector('.bbs');
  box.addEventListener('mouseenter', () => { paused = true; clearTimeout(timer); });
  box.addEventListener('mouseleave', () => { paused = false; schedule(); });
  go(0); schedule();
  return { pause() { paused = true; clearTimeout(timer); }, resume() { paused = false; schedule(); }, stop() { paused = true; clearTimeout(timer); } };
}

// ------------------------------------------------------------------ home
export function renderHome(el, cat) {
  const slides = heroItems(cat);
  const cont = continueList(cat);
  const mylist = store.getList().map((id) => cat.get(id)).filter(Boolean);
  const chips = layout.chips();
  const bbConf = layout.billboard();

  let html = '';
  if (chips.length) {
    html += `<div class="hchips" role="navigation" aria-label="أقسام">${chips.map((c) => {
      const type = String(c.type || '').trim();
      const sid = String(c.section_id || '').trim();
      const href = sid ? `#/section/sec-${encodeURIComponent(sid)}` : type === 'series' ? '#/section/series' : type === 'movie' ? '#/section/movies' : '#/search';
      return `<a class="hchip" href="${href}">${esc(c.title)}</a>`;
    }).join('')}</div>`;
  }

  // بانر من تطبيق الإدارة (مثل التطبيق)، وإلا شرائح البوسترات
  let bbItem = null;
  if (bbConf) {
    if (bbConf.item_id) bbItem = cat.get(String(bbConf.item_id));
    if (!bbItem && bbConf.section_id) {
      const sec = cat.sections().find((s) => s.adminId === String(bbConf.section_id) || s.id === String(bbConf.section_id));
      bbItem = sec && sec.items[0];
    }
  }
  if (bbItem) {
    html += billboardBlock(cat, bbItem, 'bb-single');
  } else if (slides.length) {
    html += coverHtml(slides);                               // الهاتف والجهاز اللوحي: مثل التطبيق
    html += `<div class="bbs">${slides.slice(0, 6).map(({ item }, i) => billboardBlock(cat, item, i === 0 ? 'on' : '')).join('')}
      ${slides.length > 1 ? `<div class="bbs-dots">${slides.slice(0, 6).map((s, i) => `<button type="button" class="${i ? '' : 'on'}" aria-label="${esc(s.item.title)}"></button>`).join('')}</div>` : ''}
    </div>`;                                                  // الحاسوب: بانر نتفلكس
  }

  // الصفوف: أول ما يظهر يُرسم الآن، والباقي على دفعات
  const rows = [];
  if (cont.length) rows.push(() => `<section class="hrow row-continue" data-row="continue">
      <div class="hrow-head"><h2>تابع المشاهدة</h2></div>
      <div class="hrow-wrap"><div class="track">${cont.map(({ entry, item }) => resumeCard(entry, item)).join('')}</div></div>
    </section>`);
  if (mylist.length) rows.push(() => rowHtml({ id: 'mylist', name: 'قائمتي', style: 'row', items: mylist }));
  for (const s of cat.sections()) {
    let style = layout.styleFor(s.adminId || s.id, s.name) || layout.normStyle(s.style) || 'row';
    const name = layout.titleFor(s.adminId || s.id, s.name) || s.name;
    if (style === 'billboard') {
      if (s.items[0]) rows.push(() => billboardBlock(cat, s.items[0], 'bb-inline'));
      continue;
    }
    rows.push(() => rowHtml({ id: s.id, name, style, items: s.items, more: s.items.length > (style === 'top10' ? 10 : 8) ? `#/section/${encodeURIComponent(s.id)}` : '' }));
  }

  if (!cat.items.length) {
    el.innerHTML = '<div class="empty"><h2>لا يوجد محتوى بعد</h2><p>المحتوى الذي يُضاف في تطبيق سيكرام يظهر هنا تلقائياً.</p></div>';
    return {};
  }

  const FIRST = 3;
  html += `<div class="hrows">${rows.slice(0, FIRST).map((f) => f()).join('')}</div>`;
  el.innerHTML = `<div class="home2">${html}</div>`;
  const host = el.querySelector('.hrows');
  let i = FIRST;
  let idle = 0;
  const idleCb = window.requestIdleCallback || ((fn) => setTimeout(() => fn({ timeRemaining: () => 8 }), 16));
  const cancelIdle = window.cancelIdleCallback || clearTimeout;
  const more = () => {
    idle = 0;
    if (!host.isConnected || i >= rows.length) return;
    host.insertAdjacentHTML('beforeend', rows.slice(i, i + 2).map((f) => f()).join(''));
    i += 2;
    if (i < rows.length) idle = idleCb(more, { timeout: 120 });
  };
  if (i < rows.length) idle = idleCb(more, { timeout: 60 });

  const cover = startCover(el);
  const bbs = startBillboard(el);
  return {
    pause() { cover.pause(); bbs.pause(); },
    resume() { cover.resume(); bbs.resume(); },
    cleanup() { cover.stop(); bbs.stop(); if (idle) cancelIdle(idle); },
  };
}

// ------------------------------------------------------------------ «المزيد»: كل عناوين القسم
export function renderSection(el, cat, ctx) {
  const id = ctx.params[0] || '';
  let name = '';
  let items = [];
  if (id === 'mylist') { name = 'قائمتي'; items = store.getList().map((x) => cat.get(x)).filter(Boolean); }
  else if (id === 'continue') { name = 'تابع المشاهدة'; items = continueList(cat).map((x) => x.item); }
  else {
    const s = cat.sections().find((x) => x.id === id);
    if (s) { name = layout.titleFor(s.adminId || s.id, s.name) || s.name; items = s.items; }
    else if (id === 'movies' || id === 'series') {
      name = id === 'movies' ? 'أفلام' : 'مسلسلات';
      items = cat.items.filter((i) => i.type === (id === 'movies' ? 'movie' : 'series'));
    }
  }
  if (!name) {
    el.innerHTML = '<div class="empty"><h2>القسم غير موجود</h2><a class="btn btn-primary" href="#/">العودة للرئيسية</a></div>';
    return {};
  }
  el.innerHTML = `<div class="page sec-page">
    <div class="sec-head"><button type="button" class="icon-btn sec-back" data-back aria-label="رجوع">${sym('arrow_forward')}</button><h1>${esc(name)}</h1><span class="sec-count">${items.length}</span></div>
    <div class="pgrid">${items.map((it) => posterCard(it)).join('')}</div>
  </div>`;
  document.title = `${name} - ${CONFIG.siteName}`;
  return {};
}
