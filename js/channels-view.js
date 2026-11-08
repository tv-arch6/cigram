// صفحة «القنوات»: نفس تصميم صفحة القنوات في تطبيق سيكرام (CigramChannelHub، الإصدار 10.63 وما بعده).
//
//  ┌──────────────────────────────┐
//  │   المشغل (يبدأ فارغاً)          │
//  ├──────────────────────────────┤
//  │ (شعار) اسم القناة          ♡   │   ما يُعرض الآن وزر المفضلة
//  │ [ 🔍 ابحث عن قناة أو باقة…    ] │
//  ├──────────────────────────────┤
//  │ 1) الباقات (بعرض الصفحة)        │   اختر باقة…
//  │ 2) القنوات | الباقات            │   …فتفتح صفحتها: القنوات يميناً والباقات يساراً
//  └──────────────────────────────┘
//
// على الشاشات العريضة (مثل وضع التلفاز في التطبيق): المشغل يميناً، ثم القنوات، ثم الباقات، و«كل القنوات» مفتوحة.
// الروابط: #/channels (الباقات)، #/channels/all، #/channels/fav، #/channels/recent، #/channels/pkg/<اسم الباقة>.
import { CONFIG } from './config.js';
import { esc, debounce, toast } from './util.js';
import * as cs from './channel-store.js';
import { CH_ICONS as I, logoHtml, glyphHtml } from './channel-ui.js';
import { createLivePlayer, blockedByBrowser, streamKind } from './live-player.js';
import { requirePlus } from './plus-view.js';

const K_PKG = 0, K_FAV = 1, K_RECENT = 2, K_ALL = 3;
const WIDE = '(min-width: 860px)';
const RED = '#e21b14';

const TITLE_OF = { [K_ALL]: 'كل القنوات', [K_FAV]: 'المفضلة', [K_RECENT]: 'شاهدتها مؤخراً' };

function selFromParams(params) {
  const [a, b] = params || [];
  if (a === 'all') return { kind: K_ALL, name: TITLE_OF[K_ALL] };
  if (a === 'fav') return { kind: K_FAV, name: TITLE_OF[K_FAV] };
  if (a === 'recent') return { kind: K_RECENT, name: TITLE_OF[K_RECENT] };
  if (a === 'pkg' && b) return { kind: K_PKG, name: b };
  return { kind: -1, name: '' };
}

function hashFor(kind, name) {
  if (kind === K_ALL) return '#/channels/all';
  if (kind === K_FAV) return '#/channels/fav';
  if (kind === K_RECENT) return '#/channels/recent';
  if (kind === K_PKG) return `#/channels/pkg/${encodeURIComponent(name)}`;
  return '#/channels';
}

/** القنوات التي لا يشغّلها أي متصفح: RTSP و Smooth Streaming فقط (http و TS تعمل عبر بروكسي البث). */
function appOnly(ch) {
  if (!ch.url) return false;
  const k = streamKind(ch.url);
  return k === 'rtsp' || k === 'ss' || (blockedByBrowser(ch.url) && !CONFIG.streamProxy);
}

export function renderChannels(el, ctx) {
  const mq = window.matchMedia(WIDE);
  let wide = mq.matches;
  let catalog = cs.getCatalog();
  let { kind: selKind, name: selName } = selFromParams(ctx.params);
  let query = '';
  let activeKey = '';
  let pushed = false;     // الصفحة الثانية فُتحت من صفحة الباقات بخطوة في سجل المتصفح (زر الرجوع يعيدها)
  let shown = [];
  let pkgItems = [];
  let pkgSig = '';
  let inSecond = null;

  document.body.classList.add('ch-page');
  el.innerHTML = `
    <div class="chx">
      <div class="chx-watch">
        <div class="chx-stage" id="chxStage"></div>
        <div class="chx-info">
          <span class="chx-info-logo" hidden></span>
          <div class="chx-info-text"><strong>لم تختر قناة بعد</strong><span>اختر باقة ثم قناة لتبدأ المشاهدة</span></div>
          <button type="button" class="chx-heart" aria-label="إضافة إلى المفضلة" aria-pressed="false" disabled>${I.heart}</button>
        </div>
      </div>
      <div class="chx-browse">
        <form class="chx-search" role="search" autocomplete="off">
          <span class="chx-search-icon">${I.search}</span>
          <input type="search" enterkeyhint="search" placeholder="ابحث عن قناة أو باقة…" aria-label="ابحث عن قناة أو باقة">
          <button type="button" class="chx-clear" aria-label="مسح البحث" hidden>${I.close}</button>
        </form>
        <div class="chx-body">
          <section class="chx-page1" aria-label="الباقات" hidden>
            <h2 class="chx-h">الباقات</h2>
            <ul class="chx-pkgs" role="list"></ul>
            <div class="chx-note"></div>
          </section>
          <section class="chx-page2" hidden>
            <div class="chx-chpane">
              <div class="chx-ph">
                <div class="chx-ph-title"><h2></h2><span></span></div>
                <button type="button" class="chx-back" aria-label="كل الباقات"><span>الباقات</span>${I.grid}</button>
              </div>
              <ul class="chx-list" role="list" aria-live="polite"></ul>
              <p class="chx-empty" hidden></p>
            </div>
            <div class="chx-pkgpane">
              <h3>الباقات</h3>
              <ul class="chx-npkgs" role="list"></ul>
              <div class="chx-note"></div>
            </div>
          </section>
        </div>
      </div>
    </div>`;

  const root = el.querySelector('.chx');
  const page1 = root.querySelector('.chx-page1');
  const page2 = root.querySelector('.chx-page2');
  const wideList = root.querySelector('.chx-pkgs');
  const narrowList = root.querySelector('.chx-npkgs');
  const chList = root.querySelector('.chx-list');
  const chEmpty = root.querySelector('.chx-empty');
  const chTitle = root.querySelector('.chx-ph-title h2');
  const chCount = root.querySelector('.chx-ph-title span');
  const input = root.querySelector('.chx-search input');
  const clearBtn = root.querySelector('.chx-clear');
  const infoLogo = root.querySelector('.chx-info-logo');
  const infoName = root.querySelector('.chx-info-text strong');
  const infoSub = root.querySelector('.chx-info-text span');
  const infoHeart = root.querySelector('.chx-heart');

  const player = createLivePlayer(root.querySelector('#chxStage'), { onStep: step });

  // ------------------------------------------------------------ selection helpers
  const effKind = () => (selKind < 0 && wide ? K_ALL : selKind);
  const effName = () => (selKind < 0 && wide ? TITLE_OF[K_ALL] : selName);
  const findPackage = (name) => catalog.packages.find((p) => p.name === name) || null;

  function channelsOf(kind, name) {
    if (kind === K_ALL) return catalog.all.slice();
    if (kind === K_PKG) { const p = findPackage(name); return p ? p.channels.slice() : []; }
    if (kind === K_FAV) return cs.favourites();
    if (kind === K_RECENT) return cs.recent();
    return [];
  }

  /** الباقة المحددة اختفت (أو لم تعد فيها قنوات): نرجع لصفحة الباقات. */
  function validSelection() {
    if (selKind === K_PKG && !findPackage(selName)) return false;
    if (selKind === K_FAV && !cs.favourites().length) return false;
    if (selKind === K_RECENT && !cs.recent().length) return false;
    return true;
  }

  // ------------------------------------------------------------ packages
  function buildPkgItems() {
    const items = [];
    const favs = cs.favourites().length;
    const rec = cs.recent().length;
    if (favs) items.push({ kind: K_FAV, title: TITLE_OF[K_FAV], count: favs });
    if (rec) items.push({ kind: K_RECENT, title: TITLE_OF[K_RECENT], count: rec });
    if (catalog.all.length) items.push({ kind: K_ALL, title: TITLE_OF[K_ALL], count: catalog.all.length });
    for (const p of catalog.packages) items.push({ kind: K_PKG, title: p.name, count: p.channels.length, pkg: p });
    return items;
  }

  function pkgLogo(it) {
    if (it.kind === K_FAV) return glyphHtml(I.heartFill, RED);
    if (it.kind === K_RECENT) return glyphHtml(I.clock, '#7cc4d0');
    if (it.kind === K_ALL) return glyphHtml(I.grid, '#dce6ea');
    const cover = it.pkg && it.pkg.cover;
    return logoHtml(cover ? cover.image : '', it.title, 'circle');
  }

  function renderPackages() {
    pkgItems = buildPkgItems();
    pkgSig = pkgItems.map((i) => `${i.kind}:${i.title}:${i.count}`).join('|');
    wideList.innerHTML = pkgItems.map((it, i) => `
      <li><button type="button" class="chx-pkg" data-pkg="${i}">
        ${pkgLogo(it)}
        <span class="chx-pkg-name">${esc(it.title)}</span>
        <span class="chx-pkg-count">${it.count} قناة</span>
        <span class="chx-chev">${I.chevL}</span>
      </button></li>`).join('');
    paintNarrow();
    renderNote();
  }

  function paintNarrow() {
    const k = effKind();
    const n = effName();
    narrowList.innerHTML = pkgItems.map((it, i) => {
      const on = it.kind === k && it.title === n;
      return `<li><button type="button" class="chx-npkg${on ? ' on' : ''}" data-pkg="${i}"${on ? ' aria-current="true"' : ''}>
        ${pkgLogo(it)}<span class="chx-npkg-name">${esc(it.title)}</span></button></li>`;
    }).join('');
    const sel = narrowList.querySelector('.on');
    if (sel) sel.scrollIntoView({ block: 'nearest' });
  }

  function repaintPackagesIfChanged() {
    const sig = buildPkgItems().map((i) => `${i.kind}:${i.title}:${i.count}`).join('|');
    if (sig !== pkgSig) renderPackages();
  }

  function renderNote() {
    let html = '';
    if (cs.loadFailed()) {
      html = `<p>تعذر تحميل قائمة القنوات من الخادم.</p><button type="button" class="chx-note-btn" data-act="reload">إعادة المحاولة</button>`;
    } else if (cs.isLoading()) {
      html = '<p><span class="spinner sm" aria-hidden="true"></span>جارٍ تحميل القنوات…</p>';
    }
    root.querySelectorAll('.chx-note').forEach((n) => { n.innerHTML = html; n.hidden = !html; });
  }

  // ------------------------------------------------------------ channels
  function refreshChannels() {
    let kind = effKind();
    const name = effName();
    if (kind < 0 && query) kind = K_ALL;   // الكتابة في صفحة الباقات تبحث في كل القنوات
    shown = [];
    if (kind >= 0) {
      const nq = cs.norm(query);
      shown = channelsOf(kind, name).filter((ch) => !nq || ch.search.includes(nq) || ch.sectionKey.includes(nq));
    }
    const searching = query && (selKind < 0 || kind === K_ALL);
    chTitle.textContent = searching ? 'نتائج البحث' : (TITLE_OF[kind] || name);
    chCount.textContent = `${shown.length} قناة`;
    const showSub = kind !== K_PKG || selKind < 0;
    chList.innerHTML = shown.map((ch, i) => {
      const only = appOnly(ch);
      let sub = '';
      if (!ch.available) sub = 'غير متوفرة حالياً';
      else if (showSub) sub = only ? `${ch.section} · غير مدعومة في المتصفح` : ch.section;
      else if (only) sub = 'غير مدعومة في المتصفح';
      return `<li class="chx-ch${ch.available ? '' : ' off'}" data-i="${i}">
        <button type="button" class="chx-ch-main" data-ch="${i}">
          ${logoHtml(ch.image, ch.name, 'sq')}
          <span class="chx-ch-text"><strong>${esc(ch.name)}</strong>${sub || ch.plus ? `<small>${ch.plus ? `<span class="chx-plus">Plus${ch.quality >= 1080 ? ' · ' + (ch.quality >= 2160 ? '4K' : ch.quality + 'p') : ''}</span>` : ''}${esc(sub)}</small>` : ''}</span>
        </button>
        <span class="chx-slot"></span>
      </li>`;
    }).join('');
    paintRows();
    if (!shown.length && kind >= 0) {
      chEmpty.textContent = query ? 'لا توجد قناة بهذا الاسم' : 'لا توجد قنوات هنا بعد';
      chEmpty.hidden = false;
    } else {
      chEmpty.hidden = true;
    }
  }

  /** الصف الذي يُعرض الآن أحمر وعليه علامة التشغيل، والبقية عليها القلب. */
  function paintRows() {
    Array.from(chList.children).forEach((li, i) => {
      const ch = shown[i];
      if (!ch) return;
      const active = ch.key === activeKey;
      const fav = cs.isFav(ch.key);
      li.classList.toggle('active', active);
      const slot = li.querySelector('.chx-slot');
      const html = active
        ? `<span class="chx-fav is-play" aria-hidden="true">${I.play}</span>`
        : `<button type="button" class="chx-fav${fav ? ' on' : ''}" data-fav="${i}" aria-pressed="${fav}" aria-label="${fav ? 'إزالة من المفضلة' : 'إضافة إلى المفضلة'}">${fav ? I.heartFill : I.heart}</button>`;
      if (slot.dataset.state !== `${active}${fav}`) { slot.innerHTML = html; slot.dataset.state = `${active}${fav}`; }
      const main = li.querySelector('.chx-ch-main');
      if (active) main.setAttribute('aria-current', 'true'); else main.removeAttribute('aria-current');
    });
  }

  // ------------------------------------------------------------ pages
  function renderPages() {
    const second = wide || selKind >= 0 || query.length > 0;
    if (second === inSecond) return;
    const first = inSecond === null;
    inSecond = second;
    page1.hidden = second;
    page2.hidden = !second;
    root.classList.toggle('in-second', second);
    if (!first) {
      const pg = second ? page2 : page1;
      pg.classList.remove('slide-fwd', 'slide-back');
      void pg.offsetWidth;
      pg.classList.add(second ? 'slide-fwd' : 'slide-back');
    }
  }

  function applySel(kind, name) {
    selKind = kind;
    selName = name;
    if (!validSelection()) { selKind = -1; selName = ''; }
    refreshChannels();
    paintNarrow();
    renderPages();
    chList.scrollTop = 0;
  }

  function openPackage(it) {
    if (!it) return;
    input.blur();
    if (it.kind === effKind() && it.title === effName() && !(selKind < 0 && query)) return;
    const target = hashFor(it.kind, it.title);
    if (!wide && selKind < 0 && !query) {
      pushed = true;
      location.hash = target;   // js/app.js يستدعي update() فتُفتح الصفحة الثانية
    } else {
      history.replaceState(null, '', target);
      applySel(it.kind, it.title);
    }
  }

  function backToPackages() {
    input.blur();
    if (query) { query = ''; input.value = ''; clearBtn.hidden = true; }
    if (selKind < 0) { refreshChannels(); renderPages(); return; }
    if (pushed) { pushed = false; history.back(); }
    else location.replace('#/channels');
  }

  // ------------------------------------------------------------ playing
  function showInfo(ch) {
    infoLogo.hidden = false;
    infoLogo.innerHTML = logoHtml(ch.image, ch.name, 'sq');
    infoName.textContent = ch.name;
    infoSub.textContent = appOnly(ch) ? `${ch.section}  ·  غير مدعومة في المتصفح` : `${ch.section}  ·  بث مباشر`;
    const fav = cs.isFav(ch.key);
    infoHeart.disabled = false;
    infoHeart.innerHTML = fav ? I.heartFill : I.heart;
    infoHeart.classList.toggle('on', fav);
    infoHeart.setAttribute('aria-pressed', String(fav));
    infoHeart.setAttribute('aria-label', fav ? 'إزالة من المفضلة' : 'إضافة إلى المفضلة');
  }

  function choose(ch) {
    if (!ch) return;
    if (!ch.available) { toast('القناة غير متوفرة حالياً'); return; }
    if (ch.plus && !requirePlus('قنوات HD و4K')) return;     // قنوات 1080p–4K: Plus فقط
    if (document.activeElement === input) input.blur();
    activeKey = ch.key;
    cs.pushRecent(ch.key);
    document.body.classList.add('ch-playing');
    player.play(ch);
    showInfo(ch);
    paintRows();
    repaintPackagesIfChanged();
  }

  function toggleFavourite(ch) {
    if (!ch) return;
    const now = cs.toggleFav(ch.key);
    if (ch.key === activeKey) showInfo(ch);
    repaintPackagesIfChanged();
    if (effKind() === K_FAV && !now) {
      refreshChannels();   // خرجت من القائمة المفتوحة
      if (!shown.length) backToPackages();
    } else {
      paintRows();
    }
  }

  /** القناة التالية (+1) أو السابقة (-1) في القائمة المعروضة، مع تخطي ما لا يعمل في المتصفح. */
  function step(delta) {
    const list = shown.length ? shown : catalog.all;
    const n = list.length;
    if (!n) return;
    const at = list.findIndex((c) => c.key === activeKey);
    for (let s = 1; s <= n; s++) {
      let i = ((at < 0 ? (delta > 0 ? -1 : 0) : at) + delta * s) % n;
      if (i < 0) i += n;
      const ch = list[i];
      if (!ch.available || appOnly(ch)) continue;
      choose(ch);
      if (list === shown) {
        const row = chList.children[i];
        if (row) row.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      }
      return;
    }
  }

  // ------------------------------------------------------------ events
  root.addEventListener('click', (e) => {
    const t = e.target;
    const pkg = t.closest('[data-pkg]');
    if (pkg) { openPackage(pkgItems[Number(pkg.dataset.pkg)]); return; }
    const main = t.closest('[data-ch]');
    if (main) { choose(shown[Number(main.dataset.ch)]); return; }
    const fav = t.closest('[data-fav]');
    if (fav) { toggleFavourite(shown[Number(fav.dataset.fav)]); return; }
    if (t.closest('.chx-heart')) { toggleFavourite(player.current()); return; }
    if (t.closest('.chx-back')) { backToPackages(); return; }
    if (t.closest('[data-act="reload"]')) { cs.refresh(true); renderNote(); return; }
    if (t.closest('.chx-clear')) { input.value = ''; clearBtn.hidden = true; onQuery(); input.focus(); }
  });

  // ضغطة مطوّلة على القناة: مفضلة (مثل التطبيق)
  let pressTimer = null;
  let pressed = null;
  root.addEventListener('pointerdown', (e) => {
    const main = e.target.closest('[data-ch]');
    if (!main || e.pointerType === 'mouse') return;
    pressed = main;
    pressTimer = setTimeout(() => {
      const ch = shown[Number(main.dataset.ch)];
      pressed = null;
      if (!ch) return;
      toggleFavourite(ch);
      toast(cs.isFav(ch.key) ? 'أُضيفت إلى المفضلة' : 'أُزيلت من المفضلة');
      main.dataset.longpress = '1';
    }, 550);
  });
  const cancelPress = () => { clearTimeout(pressTimer); pressed = null; };
  root.addEventListener('pointerup', cancelPress);
  root.addEventListener('pointercancel', cancelPress);
  root.addEventListener('pointermove', (e) => { if (pressed && (Math.abs(e.movementX) > 4 || Math.abs(e.movementY) > 4)) cancelPress(); });
  root.addEventListener('click', (e) => {
    const main = e.target.closest('[data-longpress]');
    if (main) { delete main.dataset.longpress; e.stopImmediatePropagation(); }
  }, true);
  root.addEventListener('contextmenu', (e) => { if (e.target.closest('[data-ch]') && matchMedia('(pointer: coarse)').matches) e.preventDefault(); });

  // البحث
  function onQuery() {
    const q = input.value.trim();
    if (q === query) return;
    query = q;
    refreshChannels();
    renderPages();
    chList.scrollTop = 0;
  }
  const onQueryLater = debounce(onQuery, 110);
  input.addEventListener('input', () => { clearBtn.hidden = !input.value; onQueryLater(); });
  root.querySelector('.chx-search').addEventListener('submit', (e) => { e.preventDefault(); onQuery(); input.blur(); });

  // لوحة المفاتيح: مسافة تشغيل/إيقاف، M الصوت، F ملء الشاشة، PageDown/PageUp أو N/P القناة التالية/السابقة
  function onKey(e) {
    if (e.altKey || e.ctrlKey || e.metaKey) return;
    const t = e.target;
    if (t && /^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName)) return;
    if (!player.current()) return;
    const k = e.key;
    const onButton = t && t.closest && t.closest('button, a');
    if ((k === ' ' && !onButton) || k === 'k') { e.preventDefault(); player.togglePlay(); }
    else if (k === 'm' || k === 'M') player.toggleMute();
    else if (k === 'f' || k === 'F') player.toggleFullscreen();
    else if (k === 'PageDown' || k === 'n' || k === 'N' || (k === 'ArrowDown' && player.isFullscreen())) { e.preventDefault(); step(1); }
    else if (k === 'PageUp' || k === 'p' || k === 'P' || (k === 'ArrowUp' && player.isFullscreen())) { e.preventDefault(); step(-1); }
  }
  document.addEventListener('keydown', onKey);

  const onMedia = () => {
    wide = mq.matches;
    refreshChannels();
    paintNarrow();
    renderPages();
  };
  mq.addEventListener ? mq.addEventListener('change', onMedia) : mq.addListener(onMedia);

  // القائمة من الخادم: تتحدث في مكانها، والقناة المعروضة تستمر
  const unlisten = cs.listen((cat, changed) => {
    if (changed) {
      catalog = cat;
      if (!validSelection()) {
        selKind = -1; selName = ''; pushed = false;
        history.replaceState(null, '', '#/channels');
      }
      renderPackages();
      refreshChannels();
      renderPages();
    } else {
      renderNote();
    }
  });

  // ------------------------------------------------------------ first paint
  if (!validSelection()) { selKind = -1; selName = ''; history.replaceState(null, '', '#/channels'); }
  renderPackages();
  refreshChannels();
  renderPages();
  cs.refresh();
  renderNote();

  return {
    /** تغير الرابط داخل صفحة القنوات (#/channels/…): نحدّث الصفحة بدون إيقاف القناة. */
    update(next) {
      const s = selFromParams(next.params);
      if (s.kind < 0) {
        pushed = false;
        if (query) { query = ''; input.value = ''; clearBtn.hidden = true; }
      }
      if (s.kind === selKind && s.name === selName) { refreshChannels(); renderPages(); return; }
      applySel(s.kind, s.name);
      if (selKind !== s.kind) history.replaceState(null, '', '#/channels');
    },
    cleanup() {
      player.destroy();
      unlisten();
      document.removeEventListener('keydown', onKey);
      mq.removeEventListener ? mq.removeEventListener('change', onMedia) : mq.removeListener(onMedia);
      clearTimeout(pressTimer);
      document.body.classList.remove('ch-page', 'ch-playing');
    },
  };
}
