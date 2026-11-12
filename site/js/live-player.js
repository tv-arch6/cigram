// المشغل الصغير أعلى صفحة القنوات (مثل CigramChannelPlayer في التطبيق).
//
// - يبدأ فارغاً: لا فيديو ولا شبكة، فقط «اختر قناة لبدء المشاهدة».
// - نوع الرابط يُعرف كما في التطبيق (CigramStreams): HLS ‏(.m3u8) و DASH ‏(.mpd) والملفات (mp4 و webm).
//   الرابط بلا امتداد يُجرَّب HLS أولاً ثم كملف عادي. ترويسات Kodi بعد «|» لا يقدر المتصفح على إرسالها فتُحذف.
// - انقطاع البث: إعادة اتصال 4 مرات (2، 4، 6، 8 ثوانٍ)، ثم رسالة مع «إعادة المحاولة».
// - روابط http داخل موقع https، أو التي ترفضها المتصفحات بسبب CORS، تمر عبر بروكسي البث (js/stream.js) فتعمل مباشرة في الموقع.
// - البث الخام .ts يُشغَّل بـ mpegts.js. وحدهما RTSP و Smooth Streaming لا يشغّلهما أي متصفح (رسالة بدون تحويل للتطبيق).
import { CONFIG } from './config.js';
import { esc, loadScript } from './util.js';
import { CH_ICONS as I, logoHtml } from './channel-ui.js';
import * as plus from './plus.js';
import { levelCap } from './plus.js';
import { viaProxy, proxyEnabled, mixedContent } from './stream.js';
import { attachSwipe, createBoost, BOOSTS, embedUrl } from './player-extras.js';
import { sym } from './icons.js';
import { requirePlus } from './plus-view.js';

/** الرابط بدون جزء «|User-Agent=…». */
export function cleanUrl(url) {
  const s = String(url ?? '').trim();
  const bar = s.indexOf('|');
  return bar >= 0 ? s.slice(0, bar).trim() : s;
}

/** نوع البث من الرابط نفسه: hls | dash | file | rtsp | ss | ts | null (لا يوجد تلميح). */
export function streamKind(url) {
  const full = cleanUrl(url).toLowerCase();
  if (!full) return null;
  if (full.startsWith('rtsp://') || full.startsWith('rtsps://') || full.startsWith('rtmp://') || full.startsWith('rtmps://')) return 'rtsp';
  if (embedUrl(url)) return 'embed';
  let path = full.split('#')[0].split('?')[0];
  if (/\.m3u8?$/.test(path) || path.includes('.m3u8/')) return 'hls';
  if (path.endsWith('.mpd') || path.includes('.mpd/')) return 'dash';
  if (/\.isml?$/.test(path) || /\.isml?\//.test(path)) {
    if (full.includes('format=m3u8')) return 'hls';
    if (full.includes('format=mpd')) return 'dash';
    return 'ss';
  }
  if (/\.(mp4|m4v|mov|webm|mkv|mp3|aac)$/.test(path)) return 'file';
  if (/\.(ts|mts|m2ts|flv)$/.test(path)) return 'ts';
  if (/m3u8|\/hls\/|type=hls|output=hls|extension=m3u/.test(full)) return 'hls';
  if (/\/dash\/|format=mpd|type=dash|output=mpd|manifest\.mpd/.test(full)) return 'dash';
  if (/output=ts|extension=ts/.test(full)) return 'ts';
  return null;
}

/** هل يمنع المتصفح هذا الرابط؟ (http داخل صفحة https) */
export const blockedByBrowser = (url) => location.protocol === 'https:' && /^http:\/\//i.test(cleanUrl(url));

const APP_ONLY_HTTP = 'رابط هذه القناة يبدأ بـ http والمتصفحات تمنع تشغيله داخل موقع آمن، ولم يتم ربط خادم البث بعد.';
const APP_ONLY_TYPE = 'هذا النوع من البث (RTSP أو Smooth Streaming) لا تشغّله المتصفحات.';
const HIDE_MS = 3200;
let mutedPref = false;   // يبقى كما هو عند التنقل بين القنوات

/**
 * يركّب المشغل داخل stage. host: { onStep(delta) } للقناة التالية / السابقة.
 * يرجع { play(ch), current(), destroy(), toggleFullscreen(), togglePlay(), toggleMute() }.
 */
export function createLivePlayer(stage, host) {
  stage.innerHTML = `
    <video class="lp-video" playsinline webkit-playsinline preload="none" hidden></video>
    <div class="lp-empty">
      ${I.tv}
      <strong>اختر قناة لبدء المشاهدة</strong>
      <span class="lp-hint-phone">ابدأ بباقة من القائمة في الأسفل</span>
      <span class="lp-hint-wide">اختر باقة من القائمة ثم قناة</span>
    </div>
    <div class="lp-wait" hidden><div class="spinner" aria-hidden="true"></div><span></span></div>
    <div class="lp-controls" aria-hidden="true">
      <div class="lp-shade-top"></div><div class="lp-shade-bottom"></div>
      <div class="lp-name"><span class="lp-logo"></span><strong></strong></div>
      <span class="lp-live"><i></i>مباشر</span>
      <div class="lp-centre">
        <button type="button" class="lp-btn" data-lp="prev" aria-label="القناة السابقة">${I.prev}</button>
        <button type="button" class="lp-btn big" data-lp="play" aria-label="تشغيل / إيقاف">${I.pause}</button>
        <button type="button" class="lp-btn" data-lp="next" aria-label="القناة التالية">${I.next}</button>
      </div>
      <div class="lp-bottom">
        <button type="button" class="lp-tap" data-lp="settings" aria-label="إعدادات المشاهدة">${sym('tune')}</button>
        <button type="button" class="lp-tap" data-lp="mute" aria-label="الصوت">${I.speaker}</button>
        <button type="button" class="lp-tap" data-lp="full" aria-label="ملء الشاشة">${I.expand}</button>
      </div>
    </div>
    <div class="lp-panel" hidden role="dialog" aria-label="إعدادات المشاهدة"></div>
    <div class="lp-error" hidden role="alert"><p></p><div class="lp-error-actions"></div></div>`;

  const video = stage.querySelector('video');
  const empty = stage.querySelector('.lp-empty');
  const wait = stage.querySelector('.lp-wait');
  const controls = stage.querySelector('.lp-controls');
  const errorBox = stage.querySelector('.lp-error');
  const playBtn = stage.querySelector('[data-lp="play"]');
  const muteBtn = stage.querySelector('[data-lp="mute"]');
  const fullBtn = stage.querySelector('[data-lp="full"]');

  let channel = null;
  let hls = null;
  let dash = null;
  let token = 0;          // يتغير مع كل تشغيل: أي رد قديم يُهمل
  let retries = 0;
  let retryTimer = null;
  let stallTimer = null;
  let hideTimer = null;
  let shown = false;
  let destroyed = false;
  let played = false;     // هل بدأ البث فعلاً (لنفرّق بين «لا يعمل» و«انقطع»)
  let mpegts = null;
  let frame = null;       // إطار التضمين (يوتيوب وأمثاله)
  let proxied = false;    // التشغيل يمر عبر بروكسي البث (بعد فشل المباشر، أو لأن الرابط http)
  let hlsFallback = null; // { url, guessed }: التشغيل المباشر لـ HLS فشل قبل أن يبدأ → hls.js

  video.muted = mutedPref;
  paintMute();

  // ------------------------------------------------------------ public
  function play(ch) {
    if (!ch || destroyed) return;
    channel = ch;
    retries = 0;
    proxied = false;
    bindChannel(ch);
    start();
  }

  function start() {
    const my = ++token;
    teardown();
    clearTimeout(retryTimer);
    played = false;
    empty.hidden = true;
    hideError();
    const url = cleanUrl(channel.url);
    if (!channel.available || !url) { fail('القناة غير متوفرة حالياً', { retry: false }); return; }
    const kind = streamKind(url);
    if (kind === 'rtsp' || kind === 'ss') { fail(APP_ONLY_TYPE, { retry: false }); return; }
    if (blockedByBrowser(url) && !proxyEnabled()) { fail(APP_ONLY_HTTP, { retry: false }); return; }
    // http داخل https لا يعمل مباشرة أبداً: عبر البروكسي من أول مرة
    const src = (proxied || blockedByBrowser(url)) && proxyEnabled() ? viaProxy(url) : url;
    if (src !== url) proxied = true;

    video.hidden = false;
    setWaiting(true, retries ? `جارٍ إعادة الاتصال… (${retries})` : 'جارٍ التحميل…');
    showControls(true);
    hlsFallback = null;
    if (kind === 'embed') { startEmbed(url); return; }
    if (kind === 'ts') startTs(src, my);
    else if (kind === 'dash') startDash(src, my);
    else if (kind === 'file') startNative(src, my);
    else if (video.canPlayType('application/vnd.apple.mpegurl')) {
      // Safari وآيفون (وبعض المتصفحات الأخرى): HLS مباشرة. إن فشل قبل أن يبدأ نجرب hls.js ثم البروكسي
      hlsFallback = { url: src, guessed: kind === null };
      startNative(src, my);
    } else startHls(src, my, kind === null);
  }

  /** يوتيوب وأمثاله داخل إطار (بدون sandbox: بعض المشغلات ترفض العمل داخله). */
  function startEmbed(url) {
    setWaiting(false);
    video.hidden = true;
    frame = document.createElement('iframe');
    frame.className = 'lp-embed';
    frame.src = embedUrl(url);
    frame.title = channel.name || 'قناة';
    frame.allow = 'autoplay; fullscreen; encrypted-media; picture-in-picture';
    frame.referrerPolicy = 'origin';
    stage.insertBefore(frame, controls);
    controls.classList.add('embed');
    showControls(true);
  }

  /** يعيد المحاولة عبر البروكسي مرة واحدة (CORS أو http). يرجع true إن بدأ ذلك. */
  function tryProxy() {
    if (proxied || !proxyEnabled() || !channel) return false;
    proxied = true;
    teardown();
    retries = 0;
    start();
    return true;
  }

  async function startTs(url, my) {
    try { await loadScript(CONFIG.mpegtsUrl); } catch { if (my === token) fail('تعذر تحميل مكتبة التشغيل. تحقق من الاتصال وحاول مرة أخرى.'); return; }
    if (my !== token || destroyed) return;
    const lib = window.mpegts;
    if (!lib || !lib.getFeatureList().mseLivePlayback) { fail('هذا المتصفح لا يدعم تشغيل البث الخام (TS).', { retry: false }); return; }
    mpegts = lib.createPlayer({ type: 'mpegts', isLive: true, url }, { enableWorker: true, liveBufferLatencyChasing: true });
    mpegts.on(lib.Events.ERROR, () => {
      if (my !== token) return;
      if (!played && tryProxy()) return;
      if (played) retry(); else fail('تعذر تشغيل القناة. قد يكون مصدر البث متوقفاً.');
    });
    mpegts.attachMediaElement(video);
    mpegts.load();
    kick();
  }

  function startNative(url, my) {
    if (my !== token) return;
    video.src = url;
    kick();
  }

  async function startHls(url, my, guessed) {
    try {
      await loadScript(CONFIG.hlsUrl);
    } catch {
      if (my === token) fail('تعذر تحميل مكتبة التشغيل. تحقق من الاتصال وحاول مرة أخرى.');
      return;
    }
    if (my !== token || destroyed) return;
    const Hls = window.Hls;
    if (!Hls || !Hls.isSupported()) {
      if (guessed) startNative(url, my); else fail('هذا المتصفح لا يدعم تشغيل البث المباشر.');
      return;
    }
    let parsed = false;
    let recovered = false;
    hls = new Hls({ enableWorker: true, lowLatencyMode: false, backBufferLength: 30, maxBufferLength: 25, manifestLoadingMaxRetry: 2, levelLoadingMaxRetry: 4 });
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      parsed = true;
      const cap = levelCap(hls.levels, plus.maxHeight());      // مجاني: حتى 720p (التلقائي فقط)
      if (cap >= 0) hls.autoLevelCapping = cap;
      kick();
    });
    hls.on(Hls.Events.ERROR, (_e, data) => {
      if (my !== token || !data.fatal) return;
      console.warn('[سيكرام] البث:', data.type, data.details);
      if (!parsed) {
        // الرابط ليس HLS (أو المصدر يمنع القراءة من المواقع): نجرب تشغيله كملف عادي إن لم يكن نوعه معروفاً
        teardownHls();
        if (tryProxy()) return;
        if (guessed) { startNative(url, my); return; }
        fail('تعذر تشغيل القناة. قد يكون مصدر البث متوقفاً.');
        return;
      }
      if (data.type === Hls.ErrorTypes.MEDIA_ERROR && !recovered) { recovered = true; hls.recoverMediaError(); return; }
      retry();
    });
    hls.loadSource(url);
    hls.attachMedia(video);
  }

  async function startDash(url, my) {
    try {
      await loadScript(CONFIG.dashUrl);
    } catch {
      if (my === token) fail('تعذر تحميل مكتبة التشغيل. تحقق من الاتصال وحاول مرة أخرى.');
      return;
    }
    if (my !== token || destroyed) return;
    const lib = window.dashjs;
    if (!lib) { fail(APP_ONLY_TYPE, { retry: false }); return; }
    dash = lib.MediaPlayer().create();
    dash.on(lib.MediaPlayer.events.ERROR, (e) => {
      if (my !== token) return;
      console.warn('[سيكرام] البث (DASH):', e && e.error);
      const code = e && e.error && e.error.code;
      if (code >= 110 && code <= 114) { fail('هذه القناة مشفّرة (DRM) ولا تعمل إلا داخل تطبيق مالكها', { retry: false }); return; }
      if (!played && tryProxy()) return;
      if (played) retry(); else fail('تعذر تشغيل القناة. قد يكون مصدر البث متوقفاً.');
    });
    dash.initialize(video, url, true);
    kick();
  }

  /** يبدأ التشغيل. إن منع المتصفح الصوت التلقائي نكمل بدون صوت، ويظهر زر الصوت. */
  function kick() {
    const p = video.play();
    if (!p || !p.catch) return;
    p.catch((err) => {
      if (destroyed || !err || err.name !== 'NotAllowedError' || video.muted) return;
      video.muted = true;
      paintMute();
      video.play().then(() => showControls(true)).catch(() => { setWaiting(false); paintPlay(); });
    });
  }

  /** إعادة الاتصال حتى 4 مرات، ثم رسالة الخطأ. */
  function retry() {
    if (destroyed || !channel) return;
    clearTimeout(retryTimer);
    clearTimeout(stallTimer);
    if (retries >= 4) { fail('تعذر تشغيل القناة حالياً'); return; }
    retries += 1;
    setWaiting(true, `جارٍ إعادة الاتصال… (${retries})`);
    const my = token;
    retryTimer = setTimeout(() => { if (my === token && !destroyed) start(); }, 2000 * retries);
  }

  function teardownHls() { if (hls) { try { hls.destroy(); } catch { /* ignore */ } hls = null; } }
  function teardown() {
    clearTimeout(stallTimer);
    teardownHls();
    if (dash) { try { dash.reset(); } catch { /* ignore */ } dash = null; }
    if (mpegts) { try { mpegts.destroy(); } catch { /* ignore */ } mpegts = null; }
    if (frame) { frame.remove(); frame = null; controls.classList.remove('embed'); }
    closePanel();
    try { video.pause(); video.removeAttribute('src'); video.load(); } catch { /* ignore */ }
  }

  // ------------------------------------------------------------ video events
  video.addEventListener('playing', () => {
    played = true;
    bst.apply();
    retries = 0;
    clearTimeout(stallTimer);
    setWaiting(false);
    hideError();
    paintPlay();
    scheduleHide();
  });
  video.addEventListener('pause', () => { paintPlay(); clearTimeout(hideTimer); });
  video.addEventListener('waiting', () => {
    if (!channel || !errorBox.hidden) return;
    setWaiting(true);
    clearTimeout(stallTimer);
    stallTimer = setTimeout(() => { if (!video.paused) retry(); }, 20000);   // البث متوقف 20 ثانية: نعيد الاتصال
  });
  video.addEventListener('canplay', () => { if (video.paused) setWaiting(false); });
  video.addEventListener('ended', () => retry());   // بث مباشر «انتهى»: نعيد الاتصال
  video.addEventListener('error', () => {
    if (hls || dash || mpegts || !channel || !video.getAttribute('src')) return;   // المكتبات تعالج أخطاءها
    if (!played && hlsFallback && !/iphone|ipad|ipod/i.test(navigator.userAgent)) {
      const { url, guessed } = hlsFallback;
      hlsFallback = null;
      try { video.removeAttribute('src'); video.load(); } catch { /* ignore */ }
      startHls(url, token, guessed);
      return;
    }
    if (!played && tryProxy()) return;
    if (played) retry();
    else fail('تعذر تشغيل القناة. قد يكون الرابط متوقفاً أو صيغته غير مدعومة.');
  });

  // ------------------------------------------------------------ السحب والمضخّم والإعدادات (نفس مشغل الأفلام)
  const sw = attachSwipe(stage, stage, video, () => !channel || !!frame);
  stage.addEventListener('click', (e) => { if (sw.consumed()) { e.stopPropagation(); e.preventDefault(); } }, true);
  const bst = createBoost(video, () => !!hls || !!mpegts, 1, () => {});
  const panel = stage.querySelector('.lp-panel');
  let fit = 'contain';
  function closePanel() { panel.hidden = true; }
  function levelName(h) { return h >= 2000 ? '4K' : h >= 1400 ? '2K' : h >= 1000 ? '1080p' : h >= 700 ? '720p' : h >= 460 ? '480p' : h >= 340 ? '360p' : h ? h + 'p' : ''; }
  function openPanel(view = 'main') {
    const row = (act, icon, label, val, extra = '') => `<button type="button" class="lp-row" data-pn="${act}" ${extra}>${sym(icon)}<span>${label}</span><em>${val}</em></button>`;
    let body;
    if (view === 'quality' && hls && hls.levels && hls.levels.length) {
      const seen = new Set();
      const items = [`<button type="button" class="lp-opt${hls.autoLevelEnabled ? ' on' : ''}" data-q="-1">تلقائي · موصى به</button>`];
      [...hls.levels.map((l, i) => ({ i, h: l.height || 0 }))].sort((a, b) => b.h - a.h).forEach((l) => {
        const n = levelName(l.h) || `جودة ${l.i + 1}`;
        if (seen.has(n)) return;
        seen.add(n);
        const locked = l.h > plus.maxHeight();
        items.push(`<button type="button" class="lp-opt${!hls.autoLevelEnabled && hls.currentLevel === l.i ? ' on' : ''}${locked ? ' locked' : ''}" data-q="${l.i}" data-h="${l.h}">${n}${locked ? ' <b>Plus</b>' : ''}</button>`);
      });
      body = `<div class="lp-ph"><button type="button" data-pn="main" aria-label="رجوع">${sym('arrow_forward')}</button><strong>جودة البث</strong></div>${items.join('')}`;
    } else if (view === 'boost') {
      body = `<div class="lp-ph"><button type="button" data-pn="main" aria-label="رجوع">${sym('arrow_forward')}</button><strong>مضخّم الصوت</strong></div>`
        + BOOSTS.map((v) => `<button type="button" class="lp-opt${bst.value === v ? ' on' : ''}" data-b="${v}">${v === 1 ? 'إيقاف (100%)' : Math.round(v * 100) + '%'}</button>`).join('')
        + (bst.ok() ? '' : '<p class="lp-note">يعمل مع بث HLS/TS في المتصفحات التي تدعم WebAudio.</p>');
    } else {
      const lv = hls && hls.levels && hls.levels.length > 1 ? (hls.autoLevelEnabled ? 'تلقائي' : levelName((hls.levels[hls.currentLevel] || {}).height)) : 'تلقائي';
      body = `<div class="lp-ph"><strong>إعدادات المشاهدة</strong><button type="button" data-pn="close" aria-label="إغلاق">${sym('close')}</button></div>`
        + (hls && hls.levels && hls.levels.length > 1 ? row('quality', 'hd', 'جودة البث', lv) : '')
        + row('boost', 'volume_up', 'مضخّم الصوت', bst.value === 1 ? 'إيقاف' : Math.round(bst.value * 100) + '%')
        + row('fit', 'aspect_ratio', 'أبعاد الشاشة', fit === 'contain' ? 'متناسق' : fit === 'cover' ? 'تكبير' : 'ملء');
    }
    panel.innerHTML = body;
    panel.hidden = false;
  }
  panel.addEventListener('click', (e) => {
    e.stopPropagation();
    const b = e.target.closest('button');
    if (!b) return;
    if (b.dataset.pn === 'close') closePanel();
    else if (b.dataset.pn === 'fit') { fit = fit === 'contain' ? 'cover' : fit === 'cover' ? 'fill' : 'contain'; video.style.objectFit = fit; openPanel(); }
    else if (b.dataset.pn) openPanel(b.dataset.pn);
    else if (b.dataset.q !== undefined && hls) {
      if (b.classList.contains('locked')) { closePanel(); requirePlus('جودة 1080p وحتى 4K'); return; }
      hls.currentLevel = Number(b.dataset.q);
      openPanel('quality');
    } else if (b.dataset.b !== undefined) { bst.set(Number(b.dataset.b)); openPanel('boost'); }
  });

  // ------------------------------------------------------------ controls
  stage.addEventListener('click', (e) => {
    const b = e.target.closest('[data-lp]');
    if (b) {
      e.stopPropagation();
      const a = b.dataset.lp;
      if (a === 'play') togglePlay();
      else if (a === 'mute') toggleMute();
      else if (a === 'full') toggleFullscreen();
      else if (a === 'settings') { panel.hidden ? openPanel() : closePanel(); }
      else if (a === 'next') { host.onStep(1); }
      else if (a === 'prev') { host.onStep(-1); }
      else if (a === 'retry') { if (channel) { retries = 0; proxied = false; start(); } }
      keepControls();
      return;
    }
    if (!channel || !errorBox.hidden || e.target.closest('.lp-error')) return;
    showControls(!shown);
  });
  stage.addEventListener('dblclick', (e) => { if (channel && !e.target.closest('button,a')) toggleFullscreen(); });
  stage.addEventListener('mousemove', () => { if (channel && errorBox.hidden && matchMedia('(hover: hover)').matches) showControls(true); });

  function togglePlay() {
    if (!channel) return;
    if (!video.getAttribute('src') && !hls && !dash) { retries = 0; start(); return; }
    if (video.paused) {
      // البث المتوقف متأخر عن اللحظة الحالية: نرجع لآخر البث
      try {
        if (hls && hls.liveSyncPosition) video.currentTime = hls.liveSyncPosition;
        else if (video.seekable && video.seekable.length) video.currentTime = video.seekable.end(video.seekable.length - 1) - 2;
      } catch { /* ignore */ }
      kick();
    } else {
      video.pause();
    }
  }

  function toggleMute() {
    video.muted = !video.muted;
    mutedPref = video.muted;
    paintMute();
  }

  function isFull() { return document.fullscreenElement === stage || document.webkitFullscreenElement === stage; }
  function toggleFullscreen() {
    if (!channel) return;
    if (document.fullscreenElement) { document.exitFullscreen().catch(() => {}); return; }
    if (document.webkitFullscreenElement && document.webkitExitFullscreen) { document.webkitExitFullscreen(); return; }
    if (stage.requestFullscreen) stage.requestFullscreen().catch(() => {});
    else if (stage.webkitRequestFullscreen) stage.webkitRequestFullscreen();
    else if (video.webkitEnterFullscreen) video.webkitEnterFullscreen();   // آيفون
  }
  const paintFull = () => {
    const on = isFull();
    fullBtn.innerHTML = on ? I.shrink : I.expand;
    fullBtn.setAttribute('aria-label', on ? 'الخروج من ملء الشاشة' : 'ملء الشاشة');
    stage.classList.toggle('is-full', on);
  };
  document.addEventListener('fullscreenchange', paintFull);
  document.addEventListener('webkitfullscreenchange', paintFull);

  function showControls(show) {
    if (show && !channel) return;
    shown = show;
    controls.classList.toggle('on', show);
    controls.setAttribute('aria-hidden', String(!show));
    stage.classList.toggle('controls-on', show);
    clearTimeout(hideTimer);
    if (show) scheduleHide();
  }
  function scheduleHide() {
    clearTimeout(hideTimer);
    if (shown && !video.paused) hideTimer = setTimeout(() => showControls(false), HIDE_MS);
  }
  function keepControls() { if (shown) scheduleHide(); }

  function paintPlay() {
    const playing = !video.paused;
    playBtn.innerHTML = playing ? I.pause : I.play;
    playBtn.setAttribute('aria-label', playing ? 'إيقاف' : 'تشغيل');
  }
  function paintMute() {
    muteBtn.innerHTML = video.muted ? I.mute : I.speaker;
    muteBtn.setAttribute('aria-label', video.muted ? 'تشغيل الصوت' : 'كتم الصوت');
    stage.classList.toggle('is-muted', video.muted);
  }

  function bindChannel(ch) {
    stage.querySelector('.lp-name strong').textContent = ch.name;
    stage.querySelector('.lp-logo').innerHTML = logoHtml(ch.image, ch.name, 'sq');
    paintPlay();
    if ('mediaSession' in navigator) {
      try {
        navigator.mediaSession.metadata = new MediaMetadata({
          title: ch.name, artist: `${ch.section} · بث مباشر`, album: CONFIG.siteName,
          artwork: ch.image ? [{ src: new URL(ch.image, location.href).href }] : [],
        });
        navigator.mediaSession.setActionHandler('nexttrack', () => host.onStep(1));
        navigator.mediaSession.setActionHandler('previoustrack', () => host.onStep(-1));
      } catch { /* ignore */ }
    }
  }

  function setWaiting(on, text = '') {
    wait.hidden = !on;
    const span = wait.querySelector('span');
    span.textContent = text;
    span.hidden = !text;
  }

  function fail(text, { retry: canRetry = true, app = false } = {}) {
    teardown();
    clearTimeout(retryTimer);
    setWaiting(false);
    video.hidden = true;
    showControls(false);
    errorBox.hidden = false;
    errorBox.querySelector('p').textContent = text;
    errorBox.querySelector('.lp-error-actions').innerHTML =
      (canRetry ? '<button type="button" class="lp-retry" data-lp="retry">إعادة المحاولة</button>' : '');
  }
  function hideError() { errorBox.hidden = true; }

  return {
    play,
    current: () => channel,
    togglePlay,
    toggleMute,
    toggleFullscreen,
    isFullscreen: isFull,
    destroy() {
      if (destroyed) return;
      destroyed = true;
      token += 1;
      clearTimeout(retryTimer);
      clearTimeout(hideTimer);
      teardown();
      if (isFull()) document.exitFullscreen?.().catch(() => {});
      document.removeEventListener('fullscreenchange', paintFull);
      document.removeEventListener('webkitfullscreenchange', paintFull);
      if ('mediaSession' in navigator) {
        try {
          navigator.mediaSession.metadata = null;
          navigator.mediaSession.setActionHandler('nexttrack', null);
          navigator.mediaSession.setActionHandler('previoustrack', null);
        } catch { /* ignore */ }
      }
      sw.destroy();
      stage.innerHTML = '';
    },
  };
}
