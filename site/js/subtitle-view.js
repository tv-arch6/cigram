// عرض الترجمة فوق الفيديو بشكل تطبيق سيكرام، ولوحة إعداداتها.
import {
  SUB_FONTS, SUB_BACKGROUNDS, SUB_COLORS, SUB_SIZES,
  getSubSettings, setSubSettings, onSubSettings, applySubStyle, preloadSubFont, loadSubtitle,
} from './subtitles.js';
import { esc, toast } from './util.js';

// ---------------------------------------------------------------- لوحة الإعدادات (المشغل وصفحة حسابي)
const fmtOffset = (v) => `\u2066${v > 0 ? '+' : v < 0 ? '−' : ''}${Math.abs(v).toFixed(1)}\u2069 ث`;

function optionButtons(list, current, key, render) {
  return list.map((o) => {
    const on = o.id === current;
    return `<button type="button" class="${render.cls}${on ? ' on' : ''}" data-sub-set="${key}" data-value="${esc(o.id)}" aria-pressed="${on}" ${render.attrs ? render.attrs(o) : ''}>${render.inner(o)}</button>`;
  }).join('');
}

/** HTML لوحة الإعدادات. withSync: يظهر ضبط توقيت الترجمة (في المشغل فقط). */
export function subSettingsHtml({ withSync = false, withToggle = true } = {}) {
  const s = getSubSettings();
  const chip = { cls: 'sp-chip', inner: (o) => esc(o.name) };
  const fontChip = {
    cls: 'sp-chip sp-font',
    attrs: (o) => `style="font-family:${esc(o.family)}, 'Tajawal', sans-serif;font-weight:${o.weight || 400}"`,
    inner: (o) => esc(o.name),
  };
  const swatch = {
    cls: 'sp-swatch',
    attrs: (o) => `title="${esc(o.name)}" aria-label="${esc(o.name)}" style="--sw:${o.color}"`,
    inner: (o) => (o.id === 'none' ? '<i class="sp-none" aria-hidden="true"></i>' : '<i aria-hidden="true"></i>'),
  };
  return `
    ${withToggle ? `<label class="switch sp-toggle">
      <input type="checkbox" data-sub-enabled ${s.enabled ? 'checked' : ''}>
      <span class="track-sw" aria-hidden="true"></span>
      <span>إظهار الترجمة</span>
    </label>` : ''}
    <div class="sp-group" role="group" aria-label="حجم الترجمة">
      <span class="sp-label">الحجم</span>
      <div class="sp-opts">${optionButtons(SUB_SIZES, s.size, 'size', chip)}</div>
    </div>
    <div class="sp-group" role="group" aria-label="خط الترجمة">
      <span class="sp-label">الخط</span>
      <div class="sp-opts">${optionButtons(SUB_FONTS, s.font, 'font', fontChip)}</div>
    </div>
    <div class="sp-group" role="group" aria-label="خلفية الترجمة">
      <span class="sp-label">الخلفية</span>
      <div class="sp-opts">${optionButtons(SUB_BACKGROUNDS, s.bg, 'bg', swatch)}</div>
    </div>
    <div class="sp-group" role="group" aria-label="لون نص الترجمة">
      <span class="sp-label">لون النص</span>
      <div class="sp-opts">${optionButtons(SUB_COLORS, s.color, 'color', swatch)}</div>
    </div>
    ${withSync ? `<div class="sp-group" role="group" aria-label="توقيت الترجمة">
      <span class="sp-label">التوقيت</span>
      <div class="sp-sync">
        <button type="button" class="sp-chip" data-sub-sync="-0.5" aria-label="تقديم الترجمة نصف ثانية" dir="ltr">−0.5</button>
        <output class="sp-offset" aria-live="polite">${fmtOffset(0)}</output>
        <button type="button" class="sp-chip" data-sub-sync="0.5" aria-label="تأخير الترجمة نصف ثانية" dir="ltr">+0.5</button>
        <button type="button" class="sp-chip sp-reset" data-sub-sync="0">ضبط</button>
      </div>
      <p class="sp-hint">إذا ظهرت الترجمة قبل الكلام اضغط +، وإذا تأخرت عنه اضغط −.</p>
    </div>` : ''}`;
}

/** يربط أزرار اللوحة بالإعدادات. يرجع دالة لإلغاء الربط. */
export function bindSubSettings(box, { onSync } = {}) {
  const onClick = (e) => {
    const set = e.target.closest('[data-sub-set]');
    if (set && box.contains(set)) { setSubSettings({ [set.dataset.subSet]: set.dataset.value }); return; }
    const sync = e.target.closest('[data-sub-sync]');
    if (sync && box.contains(sync) && onSync) onSync(Number(sync.dataset.subSync));
  };
  const onChange = (e) => {
    if (e.target.matches('[data-sub-enabled]')) setSubSettings({ enabled: e.target.checked });
  };
  const paint = (s) => {
    box.querySelectorAll('[data-sub-set]').forEach((b) => {
      const on = s[b.dataset.subSet] === b.dataset.value;
      b.classList.toggle('on', on);
      b.setAttribute('aria-pressed', String(on));
    });
    const t = box.querySelector('[data-sub-enabled]');
    if (t) t.checked = s.enabled;
    box.classList.toggle('subs-off', !s.enabled);
  };
  box.addEventListener('click', onClick);
  box.addEventListener('change', onChange);
  const off = onSubSettings(paint);
  paint(getSubSettings());
  return () => { off(); box.removeEventListener('click', onClick); box.removeEventListener('change', onChange); };
}

/** معاينة حية لشكل الترجمة (صفحة حسابي). */
export function mountSubPreview(el) {
  const apply = (s) => { applySubStyle(el, s); preloadSubFont(s); el.classList.toggle('subs-off', !s.enabled); };
  apply(getSubSettings());
  return onSubSettings(apply);
}

// ---------------------------------------------------------------- الترجمة داخل المشغل
const isIOS = () => /iphone|ipod|ipad/i.test(navigator.userAgent)
  || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

/**
 * يعرض الترجمة فوق الفيديو، متزامنة مع الوقت، وبنفس شكل التطبيق.
 * عندما يفتح المتصفح الفيديو نفسه بملء الشاشة (الآيفون، فايرفوكس، صورة داخل صورة) لا تظهر الطبقة فوقه،
 * فننقل نفس الأسطر إلى مسار ترجمة أصلي في المتصفح.
 */
export function attachSubtitles({ root, video, overlay, button, panel, tracks }) {
  const track = (tracks || []).find((t) => t && t.src);
  const noop = { destroy() {}, toggle() {}, openPanel() {}, closePanel() {}, isPanelOpen: () => false, relayout() {}, has: false };
  if (!track) { button.hidden = true; return noop; }

  let s = getSubSettings();
  let cues = [];
  let state = 'loading';
  let offset = 0;
  let lastKey = null;
  let raf = 0;
  let nativeTrack = null;
  let nativeCues = [];
  let nativeFs = false;
  let expectedMode = 'hidden';
  let destroyed = false;
  let unbindPanel = null;

  applySubStyle(root, s);
  preloadSubFont(s);
  button.hidden = false;

  // -------------------------------------------- التحميل
  loadSubtitle(track.src).then((res) => {
    if (destroyed) return;
    cues = res.cues;
    state = 'ready';
    if (isIOS()) ensureNative();
    layout();
    render(true);
    paintButton();
  }).catch((err) => {
    if (destroyed) return;
    state = 'error';
    paintButton();
    console.warn('[سيكرام] تعذر تحميل ملف الترجمة:', track.src, err && err.details ? err.details : err);
    toast('تعذّر تحميل الترجمة لهذا العنوان، الفيديو يعمل بدونها.');
  });

  // -------------------------------------------- المكان والحجم (نسبة إلى صورة الفيديو، مثل التطبيق)
  function layout() {
    const W = root.clientWidth;
    const H = root.clientHeight;
    if (!W || !H) return;
    const vw = video.videoWidth || 16;
    const vh = video.videoHeight || 9;
    const k = Math.min(W / vw, H / vh);
    const cw = vw * k;
    const ch = vh * k;
    const gap = (H - ch) / 2;
    const base = Math.min(64, Math.max(15, ch * 0.05, Math.min(W, H) * 0.042));
    const controls = root.classList.contains('active') || video.paused;
    let bottom = gap + ch * 0.1;
    let top = gap + ch * 0.06;
    if (controls) { bottom = Math.max(bottom, 78); top = Math.max(top, 86); }
    overlay.style.setProperty('--sub-px', `${base.toFixed(1)}px`);
    overlay.style.setProperty('--sub-maxw', `${Math.round(Math.max(cw * 0.9, 240))}px`);
    overlay.style.setProperty('--sub-bottom', `${Math.round(bottom)}px`);
    overlay.style.setProperty('--sub-top', `${Math.round(top)}px`);
  }

  // -------------------------------------------- الرسم
  const block = (list, where) => (list.length
    ? `<div class="sub-block ${where}">${list.map((c) => c.lines.map((l) => `<div class="sub-row"><span class="sub-ln">${l}</span></div>`).join('')).join('')}</div>`
    : '');

  function render(force) {
    const visible = s.enabled && state === 'ready' && !nativeFs;
    if (!visible) {
      if (lastKey !== '') { overlay.innerHTML = ''; lastKey = ''; }
      return;
    }
    const t = video.currentTime - offset;
    let lo = 0;
    let hi = cues.length;
    while (lo < hi) { const mid = (lo + hi) >> 1; if (cues[mid].start <= t) lo = mid + 1; else hi = mid; }
    const active = [];
    for (let i = lo - 1; i >= 0 && i >= lo - 16; i--) if (cues[i].end > t) active.unshift(i);
    const key = active.join(',');
    if (!force && key === lastKey) return;
    lastKey = key;
    const list = active.map((i) => cues[i]);
    overlay.innerHTML = block(list.filter((c) => !c.top), 'bottom') + block(list.filter((c) => c.top), 'top');
  }

  function loop() {
    raf = 0;
    render(false);
    if (!video.paused && !video.ended && !destroyed) raf = requestAnimationFrame(loop);
  }
  const startLoop = () => { if (!raf) raf = requestAnimationFrame(loop); };
  const onTime = () => render(false); // احتياط عندما يوقف المتصفح requestAnimationFrame (تبويب في الخلفية)
  const onSeek = () => render(true);
  const onPausePlay = () => { layout(); startLoop(); };

  video.addEventListener('play', onPausePlay);
  video.addEventListener('pause', onPausePlay);
  video.addEventListener('timeupdate', onTime);
  video.addEventListener('seeked', onSeek);
  video.addEventListener('loadedmetadata', layout);

  const ro = typeof ResizeObserver === 'function' ? new ResizeObserver(layout) : null;
  if (ro) ro.observe(root); else window.addEventListener('resize', layout);
  // ظهور أزرار التحكم يرفع الترجمة قليلاً حتى لا تغطيها
  const mo = new MutationObserver(layout);
  mo.observe(root, { attributes: true, attributeFilter: ['class'] });

  // -------------------------------------------- مسار الترجمة الأصلي (ملء الشاشة الأصلي)
  function ensureNative() {
    if (nativeTrack || state !== 'ready' || typeof video.addTextTrack !== 'function') return;
    try {
      const Cue = window.VTTCue || window.TextTrackCue;
      nativeTrack = video.addTextTrack('subtitles', track.label || 'العربية', track.lang || 'ar');
      expectedMode = 'hidden';
      nativeTrack.mode = 'hidden';
      for (const c of cues) {
        const cue = new Cue(c.start + offset, c.end + offset, c.lines.join('\n'));
        if (c.top) { try { cue.line = 0; } catch { /* ignore */ } }
        nativeTrack.addCue(cue);
        nativeCues.push({ cue, c });
      }
      video.textTracks.addEventListener('change', onNativeChange);
    } catch {
      nativeTrack = null;
    }
  }

  function syncNative() {
    if (!nativeTrack) return;
    const want = nativeFs && s.enabled ? 'showing' : 'hidden';
    expectedMode = want;
    if (nativeTrack.mode !== want) nativeTrack.mode = want;
  }

  // المشاهد شغّل الترجمة من زر CC في أزرار المتصفح (والفيديو ليس بملء الشاشة): نعتبرها نفس زر الترجمة.
  // أثناء ملء الشاشة الأصلي نترك المتصفح يتصرف (الآيفون قد يغيّر المسارات تلقائياً) ولا نحفظ شيئاً.
  function onNativeChange() {
    if (!nativeTrack || nativeFs || nativeTrack.mode === expectedMode) return;
    if (nativeTrack.mode === 'showing') setSubSettings({ enabled: true });
    else if (nativeTrack.mode === 'disabled') setSubSettings({ enabled: false });
    syncNative();
  }

  function setNativeFs(on) {
    nativeFs = !!on;
    if (nativeFs) ensureNative();
    syncNative();
    render(true);
    layout();
  }
  const onFsChange = () => {
    const el = document.fullscreenElement || document.webkitFullscreenElement;
    setNativeFs(el === video);
  };
  const onBeginFs = () => setNativeFs(true);
  const onEndFs = () => setNativeFs(false);
  const onPresentation = () => setNativeFs(video.webkitPresentationMode && video.webkitPresentationMode !== 'inline');
  document.addEventListener('fullscreenchange', onFsChange);
  document.addEventListener('webkitfullscreenchange', onFsChange);
  video.addEventListener('webkitbeginfullscreen', onBeginFs);
  video.addEventListener('webkitendfullscreen', onEndFs);
  video.addEventListener('enterpictureinpicture', onBeginFs);
  video.addEventListener('leavepictureinpicture', onEndFs);
  video.addEventListener('webkitpresentationmodechanged', onPresentation);

  // -------------------------------------------- الإعدادات
  const offSettings = onSubSettings((next) => {
    const fontChanged = next.font !== s.font;
    s = next;
    applySubStyle(root, s);
    if (fontChanged) preloadSubFont(s);
    syncNative();
    render(true);
    paintButton();
  });

  function setOffset(v) {
    offset = v === 0 ? 0 : Math.max(-30, Math.min(30, Math.round((offset + v) * 10) / 10));
    for (const { cue, c } of nativeCues) { cue.startTime = c.start + offset; cue.endTime = c.end + offset; }
    const out = panel.querySelector('.sp-offset');
    if (out) out.textContent = fmtOffset(offset);
    render(true);
  }

  function paintButton() {
    const on = s.enabled && state === 'ready';
    button.classList.toggle('on', on);
    button.classList.toggle('loading', state === 'loading');
    button.classList.toggle('failed', state === 'error');
    button.setAttribute('aria-pressed', String(on));
    button.title = state === 'error' ? 'تعذّر تحميل الترجمة' : state === 'loading' ? 'جار تحميل الترجمة' : on ? 'الترجمة ظاهرة' : 'الترجمة مخفية';
    const status = panel.querySelector('.sp-status');
    if (status) status.textContent = statusText();
  }

  function statusText() {
    if (state === 'loading') return 'جار تحميل الترجمة…';
    if (state === 'error') return 'تعذّر تحميل ملف الترجمة لهذا العنوان.';
    return `${track.label || 'العربية'} · ${cues.length} سطر`;
  }

  // -------------------------------------------- اللوحة
  function openPanel() {
    if (!panel.hidden) return;
    panel.innerHTML = `
      <div class="sp-head">
        <strong>الترجمة</strong>
        <button type="button" class="icon-btn" data-sub-close aria-label="إغلاق">
          <svg viewBox="0 0 24 24" aria-hidden="true" class="stroke"><path d="M6 6l12 12M18 6 6 18"/></svg>
        </button>
      </div>
      <p class="sp-status">${esc(statusText())}</p>
      <div class="sp-body">${subSettingsHtml({ withSync: true })}</div>`;
    panel.querySelector('.sp-offset').textContent = fmtOffset(offset);
    unbindPanel = bindSubSettings(panel, { onSync: setOffset });
    panel.hidden = false;
    root.classList.add('menu-open');
    button.setAttribute('aria-expanded', 'true');
    const first = panel.querySelector('[data-sub-enabled]');
    if (first) first.focus({ preventScroll: true });
  }

  function closePanel() {
    if (panel.hidden) return;
    panel.hidden = true;
    if (unbindPanel) { unbindPanel(); unbindPanel = null; }
    panel.innerHTML = '';
    root.classList.remove('menu-open');
    button.setAttribute('aria-expanded', 'false');
  }

  const onPanelClick = (e) => { if (e.target.closest('[data-sub-close]')) { closePanel(); button.focus({ preventScroll: true }); } };
  panel.addEventListener('click', onPanelClick);
  const onDocDown = (e) => {
    if (panel.hidden) return;
    if (panel.contains(e.target) || button.contains(e.target)) return;
    closePanel();
  };
  document.addEventListener('pointerdown', onDocDown, true);

  paintButton();
  layout();

  return {
    has: true,
    toggle() {
      if (state === 'error') { openPanel(); return; }
      setSubSettings({ enabled: !s.enabled });
      toast(s.enabled ? 'الترجمة ظاهرة' : 'الترجمة مخفية', { ms: 1500 });
    },
    openPanel,
    closePanel,
    isPanelOpen: () => !panel.hidden,
    relayout: layout,
    destroy() {
      destroyed = true;
      if (raf) cancelAnimationFrame(raf);
      closePanel();
      offSettings();
      mo.disconnect();
      if (ro) ro.disconnect(); else window.removeEventListener('resize', layout);
      video.removeEventListener('play', onPausePlay);
      video.removeEventListener('pause', onPausePlay);
      video.removeEventListener('timeupdate', onTime);
      video.removeEventListener('seeked', onSeek);
      video.removeEventListener('loadedmetadata', layout);
      document.removeEventListener('fullscreenchange', onFsChange);
      document.removeEventListener('webkitfullscreenchange', onFsChange);
      video.removeEventListener('webkitbeginfullscreen', onBeginFs);
      video.removeEventListener('webkitendfullscreen', onEndFs);
      video.removeEventListener('enterpictureinpicture', onBeginFs);
      video.removeEventListener('leavepictureinpicture', onEndFs);
      video.removeEventListener('webkitpresentationmodechanged', onPresentation);
      document.removeEventListener('pointerdown', onDocDown, true);
      panel.removeEventListener('click', onPanelClick);
      if (nativeTrack) {
        try { video.textTracks.removeEventListener('change', onNativeChange); } catch { /* ignore */ }
        try { nativeTrack.mode = 'disabled'; } catch { /* ignore */ }
      }
      overlay.innerHTML = '';
    },
  };
}
