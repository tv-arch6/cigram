// مشغل الأفلام والحلقات (v2): نفس واجهة مشغل تطبيق سيكرام.
//  - في الوسط: الحلقة السابقة، رجوع 10، تشغيل/إيقاف (المربع الأحمر)، تقديم 10، الحلقة التالية. القفل يساراً.
//  - في الأسفل: الوقت، شريط المشاهدة (مع علامات المقدمة)، المدة، ثم المربع الذي يكبّر الشاشة ويديرها بالعرض.
//  - السحب على الشريط يعرض معاينة الصورة والوقت (مثل يوتيوب)، والجزء الذي تحت الإصبع يبدأ تحميله.
//  - «إعدادات المشاهدة»: الجودة («تلقائي · موصى به» ثم كل الدقات من 4K إلى 360p)، السرعة، أبعاد الشاشة، الترجمة، مؤقت الإيقاف.
//  - على الهاتف: نقرتان على يمين الشاشة أو يسارها = تقديم أو رجوع 10 ثوانٍ.
//  - لا رسائل نصية فوق الفيلم؛ الأخطاء الحقيقية فقط تظهر داخل المشغل مع «إعادة المحاولة».
//  - روابط مواقع التضمين (vidbolt وأمثاله) تُعرض داخل إطار بالمشغل نفسه: المتصفح لا يسمح باستخراج الفيديو منها.
import { CONFIG } from './config.js';
import { esc, fmtClock, loadScript } from './util.js';
import { sym } from './icons.js';
import { attachSubtitles } from './subtitle-view.js';
import * as plus from './plus.js';
import { levelCap } from './plus.js';
import { upsell, requirePlus } from './plus-view.js';
import { viaProxy, proxyEnabled, mixedContent } from './stream.js';
import { attachSwipe, createBoost, BOOSTS, EMBED_SANDBOX, embedUrl } from './player-extras.js';


const isHls = (url) => /\.m3u8(\?|#|$)/i.test(url) || /\/hls\//i.test(url) || /^data:application\/(vnd\.apple\.mpegurl|x-mpegurl)/i.test(url);
const IS_IOS = document.documentElement.classList.contains('ios');
const PREFS = 'cigram_player_prefs';

// نفس قائمة التطبيق (CigramEmbedResolver.EMBED_HOSTS)
const EMBED_HOSTS = ['vidbolt.pro', 'vidfast.vc', 'vidfast.pro', 'vidnest.fun', 'videm.xyz', 'vidsrc.to', 'vidsrc.me',
  'vidsrc.xyz', 'vidlink.pro', 'embed.su', '2embed.cc', 'multiembed.mov', 'autoembed.cc'];

export function isEmbedUrl(url) {
  try {
    const u = new URL(String(url || '').split('|')[0]);
    const host = u.hostname.toLowerCase();
    if (/\.(m3u8|mpd|mp4|webm|mkv|mov)$/i.test(u.pathname)) return false;
    return EMBED_HOSTS.some((h) => host === h || host.endsWith('.' + h));
  } catch { return false; }
}

function prefs() {
  let p = {};
  try { p = JSON.parse(localStorage.getItem(PREFS) || '{}') || {}; } catch { p = {}; }
  // حجب إعلانات مواقع التضمين (vidbolt وأمثاله) مفعّل من الأساس: الإطار معزول فلا نوافذ منبثقة ولا تحويل للصفحة.
  // يتوقف فقط إذا أطفأه المشاهد بنفسه لموقع يرفض العمل داخل العزل.
  if (p.embedShield === undefined) p.embedShield = true;
  return p;
}
function setPref(k, v) { const p = prefs(); p[k] = v; try { localStorage.setItem(PREFS, JSON.stringify(p)); } catch { /* ignore */ } }

/** «4K»، «2K»، «1080p» … (العرض يساعد مع الأفلام العريضة مثل 3840×1608) */
export function qualityLabel(h, w) {
  const hh = Math.max(Number(h) || 0, Math.round((Number(w) || 0) * 9 / 16));
  if (hh >= 2000) return '4K';
  if (hh >= 1400) return '2K';
  if (hh >= 1000) return '1080p';
  if (hh >= 700) return '720p';
  if (hh >= 460) return '480p';
  if (hh >= 340) return '360p';
  return hh ? hh + 'p' : '';
}

/**
 * opts: { title, subtitle, src, poster, startAt, intro:{start,end}, creditsAt, subtitles:[], embed,
 *         next:{label,auto}, prev:{label}, onBack, onNext, onPrev, onProgress(pos,dur) }
 */
export function mountPlayer(container, opts) {
  if (opts.embed) return mountEmbed(container, opts);

  let hls = null;
  let destroyed = false;
  let lastSave = 0;
  let nextTimer = null;
  let nextDismissed = false;
  let countdown = 0;
  let locked = false;
  let sleepTimer = 0;
  let sleepAtEnd = false;
  let netRetries = 0;
  let forceProxy = false;   // الفيديو يمر عبر بروكسي البث (رابط http، أو فشل التشغيل المباشر بسبب CORS)
  let everPlayed = false;
  const p0 = prefs();

  container.innerHTML = `
    <div class="player app-ui" id="player" tabindex="-1">
      <video class="player-video" playsinline webkit-playsinline preload="auto"
             ${opts.poster ? `poster="${esc(opts.poster)}"` : ''}></video>
      <div class="p-subs" aria-hidden="true"></div>
      <div class="p-gest" aria-hidden="true"><i class="p-rip l">${sym('replay_10')}<b>10</b></i><i class="p-rip r">${sym('forward_10')}<b>10</b></i></div>
      <div class="p-ui">
        <div class="p-shade"></div>
        <div class="player-top">
          <button type="button" class="p-icon" data-p="back" aria-label="رجوع">${sym('arrow_forward')}</button>
          <div class="p-title"><strong>${esc(opts.title)}</strong>${opts.subtitle ? `<span>${esc(opts.subtitle)}</span>` : ''}</div>
          <div class="p-tools">
            <button type="button" class="p-icon p-cc" data-p="subs" aria-label="الترجمة" aria-haspopup="dialog" aria-expanded="false" hidden>${sym('subtitles')}</button>
            <button type="button" class="p-icon p-pip" data-p="pip" aria-label="صورة داخل صورة" hidden>${sym('picture_in_picture_alt')}</button>
            <button type="button" class="p-icon" data-p="settings" aria-label="إعدادات المشاهدة">${sym('tune')}</button>
          </div>
        </div>
        <button type="button" class="p-lock" data-p="lock" aria-label="قفل الشاشة">${sym('lock', true)}</button>
        <div class="p-center" dir="ltr">
          <button type="button" class="p-c p-c-sm" data-p="prev" aria-label="الحلقة السابقة" ${opts.prev ? '' : 'disabled'}>${sym('skip_previous', true)}</button>
          <button type="button" class="p-c p-c-ring" data-p="back10" aria-label="رجوع 10 ثوانٍ">${sym('replay_10')}</button>
          <button type="button" class="p-c p-play" data-p="play" aria-label="تشغيل">${sym('play_arrow', true)}</button>
          <button type="button" class="p-c p-c-ring" data-p="fwd10" aria-label="تقديم 10 ثوانٍ">${sym('forward_10')}</button>
          <button type="button" class="p-c p-c-sm" data-p="next" aria-label="الحلقة التالية" ${opts.next ? '' : 'disabled'}>${sym('skip_next', true)}</button>
        </div>
        <div class="p-bottom" dir="ltr">
          <span class="p-time p-cur">0:00</span>
          <div class="p-seek" role="slider" tabindex="0" aria-label="شريط المشاهدة" aria-valuemin="0" aria-valuemax="0" aria-valuenow="0">
            <div class="p-rail"><i class="p-buf"></i><i class="p-played"></i><span class="p-marks"></span></div>
            <i class="p-thumb"></i>
          </div>
          <span class="p-time p-dur">--:--</span>
          <button type="button" class="p-icon p-fs" data-p="fs" aria-label="تكبير الشاشة وتدويرها">${sym('fullscreen')}</button>
        </div>
      </div>
      <div class="p-preview" hidden><div class="p-pv-frame"></div><span class="p-pv-time"></span></div>
      <div class="p-wait" hidden><div class="spinner" aria-hidden="true"></div></div>
      <button type="button" class="p-unlock" data-p="unlock" hidden>${sym('lock_open')}<span>اضغط لفتح القفل</span></button>
      <div class="p-subpanel" role="dialog" aria-label="إعدادات الترجمة" hidden></div>
      <div class="p-sheet" role="dialog" aria-label="إعدادات المشاهدة" hidden></div>
      <button type="button" class="p-skip" data-p="skip" hidden>${sym('skip_next', true)}<span>تخطي المقدمة</span></button>
      <div class="p-next" hidden>
        <div class="p-next-text"><span>التالي</span><strong></strong></div>
        <div class="p-next-actions">
          <button type="button" class="btn btn-primary btn-sm" data-p="next"><span class="p-next-label">شاهد الآن</span></button>
          <button type="button" class="btn btn-ghost btn-sm" data-p="dismiss">إلغاء</button>
        </div>
      </div>
      <div class="p-status" hidden></div>
    </div>`;

  const root = container.querySelector('#player');
  const video = root.querySelector('video');
  const status = root.querySelector('.p-status');
  const wait = root.querySelector('.p-wait');
  const skipBtn = root.querySelector('.p-skip');
  const nextBox = root.querySelector('.p-next');
  const playBtn = root.querySelector('[data-p="play"]');
  const seek = root.querySelector('.p-seek');
  const rail = root.querySelector('.p-rail');
  const played = root.querySelector('.p-played');
  const buf = root.querySelector('.p-buf');
  const thumb = root.querySelector('.p-thumb');
  const marks = root.querySelector('.p-marks');
  const curEl = root.querySelector('.p-cur');
  const durEl = root.querySelector('.p-dur');
  const fsBtn = root.querySelector('[data-p="fs"]');
  const preview = root.querySelector('.p-preview');
  const pvFrame = root.querySelector('.p-pv-frame');
  const pvTime = root.querySelector('.p-pv-time');
  const sheet = root.querySelector('.p-sheet');
  const unlockBtn = root.querySelector('.p-unlock');

  // تفضيلات محفوظة (مثل التطبيق: السرعة وأبعاد الشاشة تُتذكر)
  if (p0.speed >= 0.5 && p0.speed <= 2) video.playbackRate = p0.speed;
  const FITS = [['contain', 'متناسق'], ['fill', 'ملء الشاشة'], ['cover', 'تكبير']];
  let fit = FITS.some((f) => f[0] === p0.fit) ? p0.fit : 'contain';
  video.style.objectFit = fit;

  const subs = attachSubtitles({
    root,
    video,
    overlay: root.querySelector('.p-subs'),
    button: root.querySelector('[data-p="subs"]'),
    panel: root.querySelector('.p-subpanel'),
    tracks: opts.subtitles,
  });

  // ------------------------------------------------------------ source
  async function attach() {
    clearStatus();
    const orig = opts.src;
    if (!orig) return showError('لا يوجد رابط فيديو لهذا العنوان.');
    let src = orig;
    if ((forceProxy || mixedContent(orig)) && proxyEnabled()) { src = viaProxy(orig); forceProxy = true; }
    else if (mixedContent(orig)) return showError('رابط هذا الفيديو يبدأ بـ http والمتصفح يمنع تشغيله داخل موقع آمن، ولم يتم ربط خادم البث بعد.');
    try {
      if (isHls(orig)) {
        if (video.canPlayType('application/vnd.apple.mpegurl') && !window.MediaSource) {
          video.src = src; // آيفون: المشغل الأصلي يختار الجودة بنفسه
        } else if (video.canPlayType('application/vnd.apple.mpegurl') && IS_IOS) {
          video.src = src;
        } else {
          setWaiting(true);
          await loadScript(CONFIG.hlsUrl);
          if (destroyed) return;
          const Hls = window.Hls;
          if (!Hls || !Hls.isSupported()) {
            if (video.canPlayType('application/vnd.apple.mpegurl')) { video.src = src; return; }
            return showError('هذا المتصفح لا يدعم تشغيل هذا النوع من الفيديو.');
          }
          hls = new Hls({ enableWorker: true, lowLatencyMode: false, backBufferLength: 60, maxBufferLength: 40, startLevel: -1, capLevelToPlayerSize: false });
          hls.on(Hls.Events.MANIFEST_PARSED, () => {
            // الجودة المفضلة المحفوظة (0 = تلقائي)
            const cap = levelCap(hls.levels, plus.maxHeight());          // مجاني: حتى 720p فقط
            if (cap >= 0) hls.autoLevelCapping = cap;
            const want = Math.min(Number(prefs().quality) || 0, plus.maxHeight());
            if (want > 0 && hls.levels && hls.levels.length > 1) {
              let pick = -1;
              hls.levels.forEach((l, i) => { if ((l.height || 0) <= want && (pick < 0 || l.height > hls.levels[pick].height)) pick = i; });
              if (pick >= 0) hls.currentLevel = pick;
            }
          });
          hls.on(Hls.Events.FRAG_LOADED, () => { netRetries = 0; });
          hls.on(Hls.Events.ERROR, (_e, data) => {
            if (!data.fatal) return;
            if (!everPlayed && tryProxy()) return;
            if (data.type === Hls.ErrorTypes.NETWORK_ERROR && netRetries < 8) { netRetries++; setTimeout(() => hls && hls.startLoad(), 800 * netRetries); return; }
            if (data.type === Hls.ErrorTypes.MEDIA_ERROR) { hls.recoverMediaError(); return; }
            showError('تعذر تشغيل الفيديو. حاول مرة أخرى بعد قليل.');
          });
          hls.loadSource(src);
          hls.attachMedia(video);
        }
      } else {
        video.src = src;
      }
    } catch {
      if (!destroyed) showError('تعذر تحميل مكتبة التشغيل. تحقق من الاتصال وحاول مرة أخرى.');
    }
  }

  function setWaiting(on) { wait.hidden = !on; }
  function showError(text) {
    setWaiting(false);
    status.hidden = false;
    status.className = 'p-status error';
    status.innerHTML = `<p>${esc(text)}</p><div class="p-status-actions"><button class="btn btn-primary btn-sm" data-p="retry">إعادة المحاولة</button><button class="btn btn-ghost btn-sm" data-p="back">رجوع</button></div>`;
    wakeUi(true);
  }
  function clearStatus() { status.hidden = true; status.innerHTML = ''; }

  // ------------------------------------------------------------ time & bar
  const dur = () => (isFinite(video.duration) ? video.duration : 0);
  function paintPlay() {
    const on = !video.paused && !video.ended;
    playBtn.innerHTML = sym(on ? 'pause' : 'play_arrow', true);
    playBtn.setAttribute('aria-label', on ? 'إيقاف مؤقت' : 'تشغيل');
  }
  let dragging = false;
  let dragT = 0;
  function paintBar(t) {
    const d = dur();
    const f = d ? Math.max(0, Math.min(1, t / d)) : 0;
    played.style.width = (f * 100) + '%';
    thumb.style.left = (f * 100) + '%';
    curEl.textContent = fmtClock(t);
    seek.setAttribute('aria-valuenow', String(Math.floor(t)));
  }
  function paintBuffered() {
    const d = dur();
    if (!d) return;
    const t = video.currentTime;
    let end = 0;
    for (let i = 0; i < video.buffered.length; i++) {
      if (video.buffered.start(i) <= t + 1 && video.buffered.end(i) >= t) end = Math.max(end, video.buffered.end(i));
    }
    buf.style.width = Math.min(100, (end / d) * 100) + '%';
  }
  function paintMarks() {
    const d = dur();
    marks.innerHTML = '';
    const i = opts.intro;
    if (!d || !i || i.start == null || i.end == null) return;
    marks.innerHTML = [i.start, i.end].filter((x) => x > 0 && x < d).map((x) => `<i style="left:${(x / d) * 100}%"></i>`).join('');
  }

  // ------------------------------------------------------------ events
  let resumed = false;
  video.addEventListener('loadedmetadata', () => {
    durEl.textContent = fmtClock(dur());
    seek.setAttribute('aria-valuemax', String(Math.floor(dur())));
    paintMarks();
    if (resumed) return;
    resumed = true;
    const d = dur();
    if (opts.startAt > 5 && (!d || opts.startAt < d - 3)) {
      try { video.currentTime = opts.startAt; } catch { /* ignore */ }
    }
  });
  video.addEventListener('durationchange', () => { durEl.textContent = fmtClock(dur()); paintMarks(); });
  video.addEventListener('canplay', () => { clearStatus(); setWaiting(false); });
  video.addEventListener('waiting', () => { if (!status.classList.contains('error')) setWaiting(true); });
  video.addEventListener('seeking', () => setWaiting(true));
  video.addEventListener('seeked', () => setWaiting(false));
  video.addEventListener('playing', () => { clearStatus(); setWaiting(false); paintPlay(); wakeUi(); });
  video.addEventListener('playing', () => { everPlayed = true; bst.apply(); });
  video.addEventListener('play', paintPlay);
  video.addEventListener('pause', () => { paintPlay(); save(true); wakeUi(true); });
  video.addEventListener('progress', paintBuffered);
  video.addEventListener('error', () => {
    if (hls) return; // hls.js يعالج أخطاءه بنفسه
    if (!everPlayed && tryProxy()) return;
    showError('تعذر تشغيل الفيديو. قد يكون الرابط غير صالح أو الصيغة غير مدعومة في هذا المتصفح.');
  });
  video.addEventListener('timeupdate', onTime);
  video.addEventListener('ended', () => {
    save(true, true);
    paintPlay();
    if (sleepAtEnd) return;
    if (opts.next && opts.next.auto) { opts.onNext && opts.onNext(); } else if (!opts.next) { opts.onBack && opts.onBack(); }
  });

  function save(force, ended) {
    const now = Date.now();
    if (!force && now - lastSave < 5000) return;
    lastSave = now;
    const d = dur();
    if (!d) return;
    opts.onProgress && opts.onProgress(ended ? d : video.currentTime, d);
  }

  function onTime() {
    save(false);
    const pos = video.currentTime;
    const d = dur();
    if (!dragging) paintBar(pos);
    paintBuffered();
    const i = opts.intro;
    const inIntro = i && i.start != null && i.end != null && pos >= i.start && pos < i.end - 1;
    skipBtn.hidden = !inIntro || locked;
    if (opts.next && d) {
      const trigger = opts.creditsAt != null ? opts.creditsAt : d - 30;
      const near = pos >= trigger && d - pos > 1;
      if (near && !nextDismissed && !sleepAtEnd) showNext(d - pos); else if (!near) hideNext();
    }
  }

  function showNext(remaining) {
    if (!nextBox.hidden) return;
    nextBox.hidden = false;
    nextBox.querySelector('strong').textContent = opts.next.label;
    if (opts.next.auto) {
      countdown = Math.min(10, Math.max(3, Math.floor(remaining)));
      const label = nextBox.querySelector('.p-next-label');
      const tick = () => { label.textContent = `شاهد الآن (${countdown})`; };
      tick();
      clearInterval(nextTimer);
      nextTimer = setInterval(() => {
        if (video.paused) return;
        countdown -= 1;
        if (countdown <= 0) { clearInterval(nextTimer); save(true, true); opts.onNext && opts.onNext(); return; }
        tick();
      }, 1000);
    }
  }
  function hideNext() {
    if (nextBox.hidden) return;
    nextBox.hidden = true;
    clearInterval(nextTimer);
  }

  function togglePlay() {
    if (video.paused || video.ended) { const pr = video.play(); if (pr && pr.catch) pr.catch(() => {}); } else video.pause();
  }
  function jump(delta) {
    const d = dur();
    video.currentTime = Math.max(0, Math.min(d ? d - 0.5 : Infinity, video.currentTime + delta));
    paintBar(video.currentTime);
  }

  // ------------------------------------------------------------ seek bar + preview (مثل يوتيوب)
  function timeAt(clientX) {
    const r = rail.getBoundingClientRect();
    const f = r.width ? Math.max(0, Math.min(1, (clientX - r.left) / r.width)) : 0;
    return f * dur();
  }
  let pvVideo = null;
  let pvHls = null;
  let pvTimer = 0;
  let pvFailed = false;
  function ensurePreview() {
    if (pvVideo || pvFailed || !opts.src) return;
    try {
      pvVideo = document.createElement('video');
      pvVideo.muted = true;
      pvVideo.playsInline = true;
      pvVideo.setAttribute('playsinline', '');
      pvVideo.preload = 'auto';
      pvVideo.className = 'p-pv-video';
      pvFrame.appendChild(pvVideo);
      if (hls && window.Hls) {
        // نسخة خفيفة: أقل جودة ومخزن صغير، فقط أثناء السحب
        pvHls = new window.Hls({ enableWorker: true, startLevel: 0, maxBufferLength: 4, maxMaxBufferLength: 6, backBufferLength: 0, autoStartLoad: true });
        pvHls.on(window.Hls.Events.MANIFEST_PARSED, () => { try { pvHls.autoLevelCapping = 0; pvHls.currentLevel = 0; } catch { /* ignore */ } });
        pvHls.on(window.Hls.Events.ERROR, (_e, data) => { if (data.fatal) { pvFailed = true; } });
        pvHls.loadSource(opts.src);
        pvHls.attachMedia(pvVideo);
      } else {
        pvVideo.src = video.currentSrc || opts.src;
      }
      pvVideo.addEventListener('error', () => { pvFailed = true; });
    } catch { pvFailed = true; }
  }
  function showPreview(t, clientX) {
    preview.hidden = false;
    pvTime.textContent = fmtClock(t);
    const box = root.getBoundingClientRect();
    const w = preview.offsetWidth || 180;
    const x = Math.max(8, Math.min(box.width - w - 8, clientX - box.left - w / 2));
    preview.style.left = x + 'px';
    preview.classList.toggle('no-frame', pvFailed || !pvVideo);
    clearTimeout(pvTimer);
    pvTimer = setTimeout(() => {
      if (!pvVideo || pvFailed) return;
      try { if (Math.abs(pvVideo.currentTime - t) > 0.5) pvVideo.currentTime = t; } catch { /* ignore */ }
      // الجزء الذي تحت الإصبع يبدأ تحميله في المشغل الرئيسي أيضاً عند الإفلات
    }, 90);
  }
  function hidePreview() { preview.hidden = true; clearTimeout(pvTimer); }

  seek.addEventListener('pointerdown', (e) => {
    if (locked || !dur()) return;
    e.preventDefault();
    dragging = true;
    seek.setPointerCapture && seek.setPointerCapture(e.pointerId);
    root.classList.add('scrubbing');
    ensurePreview();
    dragT = timeAt(e.clientX);
    paintBar(dragT);
    showPreview(dragT, e.clientX);
    wakeUi(true);
  });
  seek.addEventListener('pointermove', (e) => {
    if (!dragging) {
      // الحاسوب: المرور فوق الشريط يعرض المعاينة أيضاً
      if (e.pointerType === 'mouse' && dur() && !locked) { ensurePreview(); showPreview(timeAt(e.clientX), e.clientX); }
      return;
    }
    dragT = timeAt(e.clientX);
    paintBar(dragT);
    showPreview(dragT, e.clientX);
  });
  const endDrag = (e) => {
    if (!dragging) { if (e.type === 'pointerleave') hidePreview(); return; }
    dragging = false;
    root.classList.remove('scrubbing');
    hidePreview();
    if (e.type !== 'pointercancel') { video.currentTime = dragT; paintBar(dragT); }
    wakeUi();
  };
  seek.addEventListener('pointerup', endDrag);
  seek.addEventListener('pointercancel', endDrag);
  seek.addEventListener('pointerleave', endDrag);
  seek.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowRight' || e.key === 'ArrowUp') { e.preventDefault(); jump(10); }
    else if (e.key === 'ArrowLeft' || e.key === 'ArrowDown') { e.preventDefault(); jump(-10); }
  });

  // ------------------------------------------------------------ show / hide the controls
  let idleTimer;
  function wakeUi(stay) {
    if (locked) return;
    root.classList.add('active');
    clearTimeout(idleTimer);
    if (stay && video.paused) return;
    idleTimer = setTimeout(() => {
      if (!video.paused && !dragging && sheet.hidden && !root.classList.contains('menu-open') && !subs.isPanelOpen()) root.classList.remove('active');
    }, 3200);
  }
  function hideUi() { clearTimeout(idleTimer); root.classList.remove('active'); }

  // اللمس: نقرة = إظهار/إخفاء الأزرار، نقرتان على جانب = ±10 ثوانٍ. الفأرة: نقرة = تشغيل/إيقاف.
  const gest = root.querySelector('.p-gest');
  let lastTap = 0;
  let lastSide = '';
  let tapTimer = 0;
  gest.addEventListener('pointerup', (e) => {
    if (sw.consumed()) return;
    if (locked) { flashUnlock(); return; }
    if (e.pointerType === 'mouse') { togglePlay(); wakeUi(); return; }
    const r = root.getBoundingClientRect();
    const x = (e.clientX - r.left) / r.width;
    const side = x < 0.38 ? 'l' : x > 0.62 ? 'r' : 'c';
    const now = Date.now();
    if (now - lastTap < 280 && side === lastSide && side !== 'c') {
      clearTimeout(tapTimer);
      jump(side === 'r' ? 10 : -10);
      ripple(side);
      lastTap = 0;
      return;
    }
    lastTap = now;
    lastSide = side;
    clearTimeout(tapTimer);
    tapTimer = setTimeout(() => { if (root.classList.contains('active')) hideUi(); else wakeUi(); }, side === 'c' ? 0 : 260);
  });
  gest.addEventListener('dblclick', (e) => { if (!locked) { e.preventDefault(); toggleFullscreen(); } });
  root.addEventListener('mousemove', () => wakeUi(), { passive: true });
  function ripple(side) {
    const el = root.querySelector('.p-rip.' + side);
    el.classList.remove('on'); void el.offsetWidth; el.classList.add('on');
  }

  // ------------------------------------------------------------ lock
  function setLock(on) {
    locked = on;
    root.classList.toggle('locked', on);
    if (on) { hideUi(); closeSheet(); subs.closePanel(); flashUnlock(); } else { unlockBtn.hidden = true; wakeUi(); }
  }
  let unlockT = 0;
  function flashUnlock() {
    unlockBtn.hidden = false;
    clearTimeout(unlockT);
    unlockT = setTimeout(() => { unlockBtn.hidden = true; }, 2500);
  }

  // ------------------------------------------------------------ fullscreen + rotation (المربع بجانب المدة)
  const fsOn = () => !!(document.fullscreenElement || document.webkitFullscreenElement);
  function paintFs() {
    fsBtn.innerHTML = sym(fsOn() ? 'fullscreen_exit' : 'fullscreen');
    fsBtn.setAttribute('aria-label', fsOn() ? 'تصغير الشاشة' : 'تكبير الشاشة وتدويرها');
    subs.relayout && subs.relayout();
  }
  async function toggleFullscreen() {
    try {
      if (fsOn()) {
        try { screen.orientation && screen.orientation.unlock && screen.orientation.unlock(); } catch { /* ignore */ }
        if (document.exitFullscreen) await document.exitFullscreen(); else if (document.webkitExitFullscreen) document.webkitExitFullscreen();
        return;
      }
      if (root.requestFullscreen) await root.requestFullscreen({ navigationUI: 'hide' });
      else if (root.webkitRequestFullscreen) root.webkitRequestFullscreen();
      else if (video.webkitEnterFullscreen) { video.webkitEnterFullscreen(); return; }   // آيفون: يتدوّر بالعرض تلقائياً
      // أندرويد: بالعرض إذا كان الفيلم عريضاً (والهاتف يسمح)
      const wide = !video.videoWidth || video.videoWidth >= video.videoHeight;
      if (screen.orientation && screen.orientation.lock) await screen.orientation.lock(wide ? 'landscape' : 'portrait').catch(() => {});
    } catch { /* المتصفح رفض: يبقى كما هو */ }
  }
  document.addEventListener('fullscreenchange', paintFs);
  document.addEventListener('webkitfullscreenchange', paintFs);

  // ------------------------------------------------------------ «إعدادات المشاهدة»
  const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];
  const SLEEP = [[0, 'إيقاف'], [15, '15 دقيقة'], [30, '30 دقيقة'], [45, '45 دقيقة'], [60, 'ساعة'], [-1, 'نهاية الحلقة']];
  let sleepChoice = 0;

  function qualityState() {
    if (hls && hls.levels && hls.levels.length) {
      const levels = hls.levels.map((l, i) => ({ i, h: l.height || 0, w: l.width || 0, b: l.bitrate || 0 }))
        .sort((a, b) => b.h - a.h || b.b - a.b);
      const seen = new Set();
      const opts2 = [{ v: -1, label: 'تلقائي · موصى به (حسب سرعة الإنترنت)' }];
      for (const l of levels) {
        const name = qualityLabel(l.h, l.w) || `جودة ${l.i + 1}`;
        if (seen.has(name)) continue;
        seen.add(name);
        opts2.push({ v: l.i, label: name + (l.b ? ` · ${(l.b / 1e6).toFixed(1)} Mbps` : ''), locked: l.h > plus.maxHeight() });
      }
      const auto = hls.autoLevelEnabled;
      const now = hls.levels[hls.currentLevel >= 0 ? hls.currentLevel : hls.loadLevel] || {};
      const nowName = qualityLabel(video.videoHeight || now.height, video.videoWidth || now.width);
      return { list: opts2, current: auto ? -1 : hls.currentLevel, value: (auto ? 'تلقائي' : '') + (nowName ? (auto ? ' · ' : '') + nowName : '') || 'تلقائي' };
    }
    const nowName = qualityLabel(video.videoHeight, video.videoWidth);
    return { list: null, current: 0, value: nowName ? (isHls(opts.src) ? 'تلقائي · ' : '') + nowName : 'تلقائي' };
  }

  function sheetMain() {
    const q = qualityState();
    const speed = video.playbackRate === 1 ? 'عادية' : video.playbackRate + 'x';
    const fitName = (FITS.find((f) => f[0] === fit) || FITS[0])[1];
    const sl = SLEEP.find((s) => s[0] === sleepChoice) || SLEEP[0];
    sheet.innerHTML = `<div class="ps-card">
      <div class="ps-head"><strong>إعدادات المشاهدة</strong><button type="button" class="p-icon" data-ps="close" aria-label="إغلاق">${sym('close')}</button></div>
      <p class="ps-sec">الصورة</p>
      <button type="button" class="ps-item" data-ps="quality">${sym('hd')}<span>جودة الفيديو</span><em>${esc(q.value)}</em></button>
      <button type="button" class="ps-item" data-ps="fit">${sym('aspect_ratio')}<span>أبعاد الشاشة</span><em>${esc(fitName)}</em></button>
      <p class="ps-sec">التشغيل</p>
      <button type="button" class="ps-item" data-ps="speed">${sym('speed')}<span>سرعة العرض</span><em>${esc(speed)}</em></button>
      <button type="button" class="ps-item" data-ps="sleep">${sym('timer')}<span>مؤقت الإيقاف</span><em>${esc(sl[1])}</em></button>
      <p class="ps-sec">الصوت</p>
      <button type="button" class="ps-item" data-ps="boost">${sym('volume_up')}<span>مضخّم الصوت</span><em>${bst.value === 1 ? 'إيقاف' : Math.round(bst.value * 100) + '%'}</em></button>
      ${subs.has ? `<p class="ps-sec">الترجمة</p><button type="button" class="ps-item" data-ps="subs">${sym('subtitles')}<span>الترجمة</span><em>الخط واللون والحجم</em></button>` : ''}
      ${(video.videoWidth ? `<p class="ps-info">${video.videoWidth}×${video.videoHeight}</p>` : '')}
    </div>`;
  }
  function sheetList(title, items, current, onPick, hint) {
    sheet.innerHTML = `<div class="ps-card">
      <div class="ps-head"><button type="button" class="p-icon" data-ps="main" aria-label="رجوع">${sym('arrow_forward')}</button><strong>${esc(title)}</strong></div>
      <div class="ps-list">${items.map((it, n) => `<button type="button" class="ps-opt${it.v === current ? ' on' : ''}${it.locked ? ' locked' : ''}" data-opt="${n}">${it.v === current ? sym('check') : '<i class="ps-dot"></i>'}<span>${esc(it.label)}</span>${it.locked ? '<b class="ps-lock">Plus</b>' : ''}</button>`).join('')}</div>
      ${hint ? `<p class="ps-hint">${esc(hint)}</p>` : ''}
    </div>`;
    sheet.querySelectorAll('[data-opt]').forEach((b) => b.addEventListener('click', () => { onPick(items[Number(b.dataset.opt)].v); sheetMain(); }));
  }
  function openSheet() { subs.closePanel(); sheetMain(); sheet.hidden = false; root.classList.add('menu-open'); wakeUi(true); }
  function closeSheet() { sheet.hidden = true; root.classList.remove('menu-open'); wakeUi(); }

  sheet.addEventListener('click', (e) => {
    if (e.target === sheet) { closeSheet(); return; }
    const b = e.target.closest('[data-ps]');
    if (!b) return;
    const a = b.dataset.ps;
    if (a === 'close') closeSheet();
    else if (a === 'main') sheetMain();
    else if (a === 'quality') {
      const q = qualityState();
      if (!q.list) {
        sheetList('جودة الفيديو', [{ v: 0, label: q.value }], 0, () => {},
          isHls(opts.src) ? 'على هذا الجهاز يختار المتصفح الجودة تلقائياً حسب سرعة الإنترنت.' : 'هذا الفيديو متوفر بدقة واحدة.');
      } else {
        sheetList('جودة الفيديو', q.list, q.current, (v) => {
          if (!hls) return;
          const o = q.list.find((x) => x.v === v);
          if (o && o.locked) { closeSheet(); upsell('جودة 1080p وحتى 4K'); return; }
          if (v < 0) { hls.currentLevel = -1; setPref('quality', 0); }
          else { hls.currentLevel = v; setPref('quality', hls.levels[v].height || 0); }
        });
      }
    } else if (a === 'speed') {
      sheetList('سرعة العرض', SPEEDS.map((v) => ({ v, label: v === 1 ? 'عادية' : v + 'x' })), video.playbackRate, (v) => { video.playbackRate = v; setPref('speed', v); });
    } else if (a === 'fit') {
      sheetList('أبعاد الشاشة', FITS.map((f) => ({ v: f[0], label: f[1] })), fit, (v) => { fit = v; video.style.objectFit = v; setPref('fit', v); });
    } else if (a === 'sleep') {
      sheetList('مؤقت الإيقاف', SLEEP.map((s) => ({ v: s[0], label: s[1] })), sleepChoice, (v) => {
        sleepChoice = v;
        clearTimeout(sleepTimer);
        sleepAtEnd = v === -1;
        if (v > 0) sleepTimer = setTimeout(() => { video.pause(); sleepChoice = 0; }, v * 60000);
      });
    } else if (a === 'boost') {
      sheetList('مضخّم الصوت', BOOSTS.map((v) => ({ v, label: v === 1 ? 'إيقاف (100%)' : Math.round(v * 100) + '%' })), bst.value,
        (v) => { bst.set(v); },
        bst.ok() ? 'يرفع مستوى الصوت فوق الحد العادي. ارفعه تدريجياً لتجنب التشويش.' : 'المضخّم يعمل مع البث المتعدد الجودة (HLS) في المتصفحات التي تدعمه. هذا الفيديو لا يدعمه.');
    } else if (a === 'subs') { closeSheet(); subs.openPanel(); }
  });

  // ------------------------------------------------------------ buttons
  root.addEventListener('click', (e) => {
    const b = e.target.closest('[data-p]');
    if (!b) return;
    const a = b.dataset.p;
    if (a === 'back') { save(true); opts.onBack && opts.onBack(); }
    else if (a === 'play') { togglePlay(); wakeUi(); }
    else if (a === 'back10') { jump(-10); wakeUi(); }
    else if (a === 'fwd10') { jump(10); wakeUi(); }
    else if (a === 'prev') { if (opts.onPrev) { save(true); opts.onPrev(); } }
    else if (a === 'next') { if (opts.onNext) { save(true, true); opts.onNext(); } }
    else if (a === 'skip') { video.currentTime = opts.intro.end; skipBtn.hidden = true; }
    else if (a === 'dismiss') { nextDismissed = true; hideNext(); }
    else if (a === 'retry') { teardownHls(); netRetries = 0; attach(); const pr = video.play(); if (pr && pr.catch) pr.catch(() => {}); }
    else if (a === 'subs') { closeSheet(); subs.isPanelOpen() ? subs.closePanel() : subs.openPanel(); }
    else if (a === 'settings') { sheet.hidden ? openSheet() : closeSheet(); }
    else if (a === 'fs') { toggleFullscreen(); }
    else if (a === 'pip') { togglePip(); }
    else if (a === 'lock') { setLock(true); }
    else if (a === 'unlock') { setLock(false); }
  });

  // اختصارات لوحة المفاتيح
  function onKey(e) {
    if (e.target && /^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName)) return;
    const k = e.key;
    if (k === 'Escape' && !sheet.hidden) { closeSheet(); return; }
    if (k === 'Escape' && subs.isPanelOpen && subs.isPanelOpen()) { subs.closePanel(); return; }
    if (subs.isPanelOpen && subs.isPanelOpen() && e.target && e.target.closest && e.target.closest('.p-subpanel')) return;
    if (locked) return;
    if (k === ' ' || k === 'k') { if (e.target === seek) return; e.preventDefault(); togglePlay(); wakeUi(); }
    else if ((k === 'ArrowRight' || k === 'l') && e.target !== seek) { e.preventDefault(); jump(10); wakeUi(); }
    else if ((k === 'ArrowLeft' || k === 'j') && e.target !== seek) { e.preventDefault(); jump(-10); wakeUi(); }
    else if (k === 'f') { toggleFullscreen(); }
    else if (k === 'm') { video.muted = !video.muted; }
    else if (k === 'c' || k === 'C' || k === 'ؤ') { subs.toggle(); }
    else if (k === 'n' && opts.next) { save(true, true); opts.onNext && opts.onNext(); }
    else if (k === 'Escape' && !fsOn()) { save(true); opts.onBack && opts.onBack(); }
  }
  document.addEventListener('keydown', onKey);

  // أزرار الوسائط في الجهاز والإشعارات
  if ('mediaSession' in navigator) {
    try {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: opts.title,
        artist: opts.subtitle || CONFIG.siteName,
        artwork: opts.poster ? [{ src: opts.poster }] : [],
      });
      navigator.mediaSession.setActionHandler('seekbackward', () => jump(-10));
      navigator.mediaSession.setActionHandler('seekforward', () => jump(10));
      if (opts.next) navigator.mediaSession.setActionHandler('nexttrack', () => { save(true, true); opts.onNext && opts.onNext(); });
      if (opts.prev) navigator.mediaSession.setActionHandler('previoustrack', () => { save(true); opts.onPrev && opts.onPrev(); });
    } catch { /* ignore */ }
  }

  function teardownHls() { if (hls) { try { hls.destroy(); } catch { /* ignore */ } hls = null; } }
  /** مرة واحدة: يعيد التشغيل عبر بروكسي البث (CORS أو http). */
  function tryProxy() {
    if (forceProxy || !proxyEnabled() || !opts.src) return false;
    forceProxy = true;
    teardownHls();
    netRetries = 0;
    attach();
    const pr2 = video.play();
    if (pr2 && pr2.catch) pr2.catch(() => {});
    return true;
  }

  // السحب (سطوع/صوت) ومضخّم الصوت: نفس الوحدة المشتركة مع مشغل القنوات (js/player-extras.js)
  const sw = attachSwipe(root, root.querySelector('.p-gest'), video, () => locked);
  const bst = createBoost(video, () => !!hls, p0.boost, (v) => setPref('boost', v));

  // صورة داخل صورة: Plus فقط. المجاني يرى الزر عليه علامة Plus ويفتح شرح الميزة، ومتصفح المجاني لا يعرض PiP الخاص به.
  const pipBtn = root.querySelector('[data-p="pip"]');
  const pipOk = !!(document.pictureInPictureEnabled || (video.webkitSupportsPresentationMode && video.webkitSupportsPresentationMode('picture-in-picture')));
  function paintPip() {
    if (!pipBtn) return;
    pipBtn.hidden = !pipOk;
    pipBtn.classList.toggle('plus-lock', !plus.isPlus());
    video.disablePictureInPicture = !plus.isPlus();
    if (!plus.isPlus() && document.pictureInPictureElement === video) document.exitPictureInPicture().catch(() => {});
  }
  async function togglePip() {
    if (!requirePlus('صورة داخل صورة (PiP)')) return;
    try {
      if (document.pictureInPictureElement) await document.exitPictureInPicture();
      else if (video.requestPictureInPicture) await video.requestPictureInPicture();
      else if (video.webkitSetPresentationMode) video.webkitSetPresentationMode(video.webkitPresentationMode === 'picture-in-picture' ? 'inline' : 'picture-in-picture');
    } catch { /* المتصفح رفض */ }
  }
  paintPip();
  const offPlusPip = plus.onChange(paintPip);

  attach();
  paintPlay();
  wakeUi(true);
  const pr = video.play();
  if (pr && pr.catch) pr.catch(() => { paintPlay(); wakeUi(true); /* المتصفح يمنع التشغيل التلقائي: المشاهد يضغط ▶ */ });

  return {
    destroy() {
      if (destroyed) return;
      destroyed = true;
      save(true);
      clearInterval(nextTimer);
      clearTimeout(idleTimer);
      clearTimeout(sleepTimer);
      clearTimeout(unlockT);
      offPlusPip();
      document.removeEventListener('keydown', onKey);
      document.removeEventListener('fullscreenchange', paintFs);
      document.removeEventListener('webkitfullscreenchange', paintFs);
      subs.destroy();
      teardownHls();
      if (pvHls) { try { pvHls.destroy(); } catch { /* ignore */ } pvHls = null; }
      if (pvVideo) { try { pvVideo.removeAttribute('src'); pvVideo.load(); } catch { /* ignore */ } }
      try { video.pause(); video.removeAttribute('src'); video.load(); } catch { /* ignore */ }
      if (fsOn()) { try { screen.orientation && screen.orientation.unlock && screen.orientation.unlock(); } catch { /* ignore */ } (document.exitFullscreen ? document.exitFullscreen() : Promise.resolve()).catch(() => {}); }
      if ('mediaSession' in navigator) { try { navigator.mediaSession.metadata = null; } catch { /* ignore */ } }
      container.innerHTML = '';
    },
    video,
  };
}

// ------------------------------------------------------------------ مواقع التضمين (vidbolt وأمثاله)
// المتصفح لا يسمح بقراءة الفيديو من صفحات هذه المواقع (التطبيق يستخرجه بمتصفح داخلي)، فتُعرض الصفحة نفسها داخل
// إطار يملأ المشغل، مع زر الرجوع واسم العنوان. أزرار التشغيل داخل الإطار هي أزرار ذلك الموقع.
function mountEmbed(container, opts) {
  const src = String(opts.src || '').split('|')[0];
  container.innerHTML = `
    <div class="player embed-player active" id="player">
      <iframe class="p-embed" src="${esc(embedUrl(src) || src)}" title="${esc(opts.title)}" allow="autoplay; fullscreen; encrypted-media; picture-in-picture"
              ${prefs().embedShield ? `sandbox="${EMBED_SANDBOX}"` : ''} referrerpolicy="origin" loading="eager"></iframe>
      <div class="p-embed-top">
        <button type="button" class="p-icon" data-p="back" aria-label="رجوع">${sym('arrow_forward')}</button>
        <div class="p-title"><strong>${esc(opts.title)}</strong>${opts.subtitle ? `<span>${esc(opts.subtitle)}</span>` : ''}</div>
        <button type="button" class="p-icon p-shield${prefs().embedShield ? ' on' : ''}" data-p="shield" aria-pressed="${prefs().embedShield ? 'true' : 'false'}" aria-label="حظر النوافذ المنبثقة" title="حظر النوافذ المنبثقة (بعض المواقع ترفض العمل مع الحظر)">${sym('shield')}</button>
        ${opts.next ? `<button type="button" class="p-icon" data-p="next" aria-label="الحلقة التالية">${sym('skip_next', true)}</button>` : ''}
      </div>
    </div>`;
  const root = container.querySelector('#player');
  const frameEl = root.querySelector('iframe');
  // تسجيل المشاهدة: الموقع لا يخبرنا بمكان المشاهدة، فنسجل أن العنوان فُتح (يظهر في «تابع المشاهدة»)
  try { opts.onProgress && opts.onProgress(60, 0); } catch { /* ignore */ }
  const onClick = (e) => {
    const b = e.target.closest('[data-p]');
    if (!b) return;
    if (b.dataset.p === 'back') opts.onBack && opts.onBack();
    else if (b.dataset.p === 'next') opts.onNext && opts.onNext();
    else if (b.dataset.p === 'shield') {
      // يعيد تحميل الإطار مع/بدون sandbox: sandbox يحجب النوافذ المنبثقة لكن بعض مواقع التضمين ترفض العمل داخله («Playback disabled»)
      const on = !prefs().embedShield;
      setPref('embedShield', on);
      b.classList.toggle('on', on);
      b.setAttribute('aria-pressed', String(on));
      const src2 = frameEl.src;
      if (on) frameEl.setAttribute('sandbox', EMBED_SANDBOX); else frameEl.removeAttribute('sandbox');
      frameEl.src = 'about:blank';
      setTimeout(() => { frameEl.src = src2; }, 60);
    }
  };
  root.addEventListener('click', onClick);
  const onKey = (e) => { if (e.key === 'Escape' && !document.fullscreenElement) opts.onBack && opts.onBack(); };
  document.addEventListener('keydown', onKey);
  return {
    destroy() { document.removeEventListener('keydown', onKey); container.innerHTML = ''; },
    video: null,
  };
}

export { fmtClock };
