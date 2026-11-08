// أدوات مشغل سيكرام المشتركة بين مشغل الأفلام (player.js) ومشغل القنوات (live-player.js)، فالسلوك واحد في الموقع كله وكما في التطبيق:
//  - attachSwipe: السحب العمودي على اللمس، يسار الشاشة = السطوع، ويمينها = الصوت (مع مؤشر).
//  - createBoost: مضخّم الصوت حتى 200% (CigramAudioBoost) عبر WebAudio.
//  - embedUrl / EMBED_SANDBOX: روابط يوتيوب ومواقع التضمين داخل إطار معزول لا يسمح بالنوافذ المنبثقة ولا بتحويل الصفحة (لا إعلانات منبثقة).
import { sym } from './icons.js';

export const BOOSTS = [1, 1.25, 1.5, 1.75, 2];

/** إطار التضمين: سكربتات وملء الشاشة فقط. بدون allow-popups وallow-top-navigation فتُحجب النوافذ المنبثقة وإعادة توجيه الصفحة. */
export const EMBED_SANDBOX = 'allow-scripts allow-same-origin allow-forms allow-presentation';

/** رابط يوتيوب → رابط تضمين. غير ذلك يرجع '' . */
export function embedUrl(raw) {
  let u;
  try { u = new URL(String(raw || '').split('|')[0].trim()); } catch { return ''; }
  const h = u.hostname.replace(/^www\.|^m\./, '').toLowerCase();
  const q = '?autoplay=1&playsinline=1&rel=0&modestbranding=1';
  if (h === 'youtu.be') { const id = u.pathname.slice(1).split('/')[0]; return /^[\w-]{6,}$/.test(id) ? `https://www.youtube.com/embed/${id}${q}` : ''; }
  if (h === 'youtube.com' || h === 'youtube-nocookie.com') {
    if (u.pathname === '/watch') { const id = u.searchParams.get('v') || ''; return /^[\w-]{6,}$/.test(id) ? `https://www.youtube.com/embed/${id}${q}` : ''; }
    if (u.pathname.startsWith('/embed/') || u.pathname.startsWith('/live/')) { const id = u.pathname.split('/')[2] || ''; return /^[\w-]{6,}$/.test(id) ? `https://www.youtube.com/embed/${id}${q}` : ''; }
    if (u.pathname === '/live_stream' && /^[\w-]+$/.test(u.searchParams.get('channel') || '')) return `https://www.youtube.com/embed/live_stream?channel=${u.searchParams.get('channel')}&autoplay=1&playsinline=1`;
    const ch = u.pathname.match(/^\/channel\/([\w-]+)\/live$/);
    if (ch) return `https://www.youtube.com/embed/live_stream?channel=${ch[1]}&autoplay=1&playsinline=1`;
  }
  return '';
}

/** يرجع { consumed(): هل كانت اللمسة سحباً (لتجاهل النقرة التالية)، destroy() }. */
export function attachSwipe(root, gestEl, video, isLocked = () => false) {
  const hud = document.createElement('div');
  hud.className = 'p-hud';
  hud.hidden = true;
  root.appendChild(hud);
  let brightness = 1;
  let swipe = null;
  let swiped = false;
  let hudT = 0;
  function show(icon, pct) {
    hud.innerHTML = `${sym(icon, true)}<b>${pct}%</b><i><u style="width:${Math.max(0, Math.min(100, pct))}%"></u></i>`;
    hud.hidden = false;
    clearTimeout(hudT);
    hudT = setTimeout(() => { hud.hidden = true; }, 900);
  }
  const down = (e) => {
    swiped = false;
    if (e.pointerType === 'mouse' || isLocked()) { swipe = null; return; }
    const r = root.getBoundingClientRect();
    swipe = { x: e.clientX, y: e.clientY, right: (e.clientX - r.left) / r.width > 0.5, v0: video.volume, b0: brightness, on: false, h: r.height };
  };
  const move = (e) => {
    if (!swipe) return;
    const dx = e.clientX - swipe.x, dy = e.clientY - swipe.y;
    if (!swipe.on) {
      if (Math.abs(dy) < 14 || Math.abs(dy) < Math.abs(dx) * 1.4) return;
      swipe.on = true;
    }
    swiped = true;
    const delta = -dy / (swipe.h * 0.7);
    if (swipe.right) {
      const v = Math.max(0, Math.min(1, swipe.v0 + delta));
      video.volume = v;
      if (v > 0 && video.muted) video.muted = false;
      show(v === 0 ? 'volume_off' : 'volume_up', Math.round(v * 100));
    } else {
      brightness = Math.max(0.35, Math.min(1.6, swipe.b0 + delta));
      video.style.filter = `brightness(${brightness})`;
      show('brightness_6', Math.round((brightness - 0.35) / 1.25 * 100));
    }
  };
  const end = () => { swipe = null; };
  gestEl.addEventListener('pointerdown', down);
  gestEl.addEventListener('pointermove', move);
  gestEl.addEventListener('pointerup', end);
  gestEl.addEventListener('pointercancel', end);
  return {
    consumed() { const s = swiped; swiped = false; return s; },
    destroy() {
      clearTimeout(hudT);
      gestEl.removeEventListener('pointerdown', down);
      gestEl.removeEventListener('pointermove', move);
      gestEl.removeEventListener('pointerup', end);
      gestEl.removeEventListener('pointercancel', end);
      hud.remove();
    },
  };
}

/**
 * مضخّم الصوت. isMse(): هل الفيديو يعمل عبر MSE (hls.js/mpegts.js)؟ ملف مباشر من موقع آخر يُكتم إذا مرّ بـ WebAudio، فلا نفعّله فيه.
 */
export function createBoost(video, isMse, initial = 1, onSave = () => {}) {
  let ctx = null;
  let gain = null;
  let value = BOOSTS.includes(Number(initial)) ? Number(initial) : 1;
  const Ctx = () => window.AudioContext || window.webkitAudioContext;
  const api = {
    get value() { return value; },
    ok: () => !!isMse() && !!Ctx(),
    set(v) {
      if (v > 1 && !api.ok()) return false;
      try {
        if (!ctx && v > 1) {
          const C = Ctx();
          ctx = new C();
          const node = ctx.createMediaElementSource(video);
          gain = ctx.createGain();
          node.connect(gain);
          gain.connect(ctx.destination);
        }
        if (gain) gain.gain.value = v;
        if (ctx && ctx.state === 'suspended') ctx.resume();
        value = v;
        onSave(v);
        return true;
      } catch { return false; }
    },
    /** يُستدعى عند بدء التشغيل: يعيد تطبيق القيمة المحفوظة. */
    apply() { if (value > 1 && !gain) api.set(value); },
  };
  return api;
}
