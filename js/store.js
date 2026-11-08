// تخزين بيانات المشاهد داخل المتصفح نفسه (localStorage).
// القائمة وسجل المشاهدة والاسم والإعدادات. لا يُرسل شيء لأي سيرفر.
const KEY = 'sp.v1';
const MAX_HISTORY = 200;

const defaults = () => ({
  profile: { name: '' },
  mylist: [],
  progress: {},
  settings: { autoplayNext: true },
  // القنوات: المفضلة (الأحدث أولاً) وآخر 10 قنوات شوهدت، بمفاتيح أسماء القنوات كما في التطبيق
  channels: { fav: [], recent: [] },
});

let state = defaults();
let memoryOnly = false;
const listeners = new Set();

function load() {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      state = { ...defaults(), ...parsed };
      state.profile = { ...defaults().profile, ...(parsed.profile || {}) };
      state.settings = { ...defaults().settings, ...(parsed.settings || {}) };
      if (!Array.isArray(state.mylist)) state.mylist = [];
      if (!state.progress || typeof state.progress !== 'object') state.progress = {};
      state.channels = cleanChannels(parsed.channels);
    }
  } catch {
    memoryOnly = true;
  }
}

let saveTimer = null;
function save(now = false) {
  const write = () => {
    saveTimer = null;
    try { localStorage.setItem(KEY, JSON.stringify(state)); } catch { memoryOnly = true; }
  };
  if (now) { clearTimeout(saveTimer); write(); return; }
  if (!saveTimer) saveTimer = setTimeout(write, 800);
}
window.addEventListener('pagehide', () => save(true));
document.addEventListener('visibilitychange', () => { if (document.hidden) save(true); });

function emit() { listeners.forEach((fn) => { try { fn(); } catch { /* ignore */ } }); }
export const onChange = (fn) => { listeners.add(fn); return () => listeners.delete(fn); };

load();

// ---------------------------------------------------------------- profile & settings
export const isMemoryOnly = () => memoryOnly;
export const getName = () => state.profile.name || '';
export function setName(name) { state.profile.name = String(name || '').trim().slice(0, 40); save(true); emit(); }
export const getSetting = (k) => state.settings[k];
export function setSetting(k, v) { state.settings[k] = v; save(true); emit(); }

// ---------------------------------------------------------------- my list
export const inList = (id) => state.mylist.includes(String(id));
export const getList = () => state.mylist.slice();
export function toggleList(id) {
  id = String(id);
  const i = state.mylist.indexOf(id);
  if (i >= 0) state.mylist.splice(i, 1); else state.mylist.unshift(id);
  save(true); emit();
  return i < 0;
}

// ---------------------------------------------------------------- progress / history
export const progressKey = (id, season, ep) => (season ? `${id}:${season}:${ep}` : String(id));
export const getProgress = (key) => state.progress[key] || null;

export function setProgress(key, data) {
  const prev = state.progress[key] || {};
  const dur = Number(data.dur) || prev.dur || 0;
  const pos = Math.max(0, Number(data.pos) || 0);
  const done = dur > 0 && (dur - pos <= Math.min(45, dur * 0.04));
  state.progress[key] = {
    id: String(data.id),
    season: data.season || 0,
    ep: data.ep || 0,
    pos: done ? 0 : Math.floor(pos),
    dur: Math.floor(dur),
    done: done || (prev.done && pos < 5),
    t: Date.now(),
  };
  trim();
  save();
}

function trim() {
  const keys = Object.keys(state.progress);
  if (keys.length <= MAX_HISTORY) return;
  keys.sort((a, b) => state.progress[a].t - state.progress[b].t);
  keys.slice(0, keys.length - MAX_HISTORY).forEach((k) => delete state.progress[k]);
}

/** كل الإدخالات من الأحدث للأقدم. */
export function allProgress() {
  return Object.entries(state.progress)
    .map(([key, v]) => ({ key, ...v }))
    .sort((a, b) => b.t - a.t);
}

/** آخر إدخال لكل عنوان (فيلم أو مسلسل). */
export function latestPerTitle() {
  const seen = new Set();
  const out = [];
  for (const e of allProgress()) {
    if (seen.has(e.id)) continue;
    seen.add(e.id);
    out.push(e);
  }
  return out;
}

export function removeTitleHistory(id) {
  id = String(id);
  for (const k of Object.keys(state.progress)) if (state.progress[k].id === id) delete state.progress[k];
  save(true); emit();
}
export function clearHistory() { state.progress = {}; save(true); emit(); }

export function totalWatchedSeconds() {
  return Object.values(state.progress).reduce((sum, e) => sum + (e.done ? e.dur : e.pos), 0);
}

// ---------------------------------------------------------------- channels (favourites / recent)
function cleanChannels(c) {
  const list = (v, max) => (Array.isArray(v) ? v.map(String).filter(Boolean).slice(0, max) : []);
  return { fav: list(c && c.fav, 500), recent: list(c && c.recent, 10) };
}
export const channelFavs = () => state.channels.fav.slice();
export const channelRecent = () => state.channels.recent.slice();
export const isChannelFav = (key) => state.channels.fav.includes(key);
/** يرجع true إن صارت القناة في المفضلة بعد الضغط. */
export function toggleChannelFav(key) {
  const f = state.channels.fav;
  const i = f.indexOf(key);
  if (i >= 0) f.splice(i, 1); else f.unshift(key);
  save(true); emit();
  return i < 0;
}
export function pushChannelRecent(key) {
  if (!key) return;
  const r = state.channels.recent.filter((k) => k !== key);
  r.unshift(key);
  state.channels.recent = r.slice(0, 10);
  save(true);
}

// ---------------------------------------------------------------- backup
export function exportData() {
  return JSON.stringify({ app: 'streamsite', version: 1, exportedAt: new Date().toISOString(), data: state }, null, 2);
}
export function importData(text) {
  const parsed = JSON.parse(text);
  const d = parsed && parsed.data;
  if (!d || typeof d !== 'object') throw new Error('bad file');
  const next = defaults();
  if (d.profile && typeof d.profile.name === 'string') next.profile.name = d.profile.name.slice(0, 40);
  if (Array.isArray(d.mylist)) next.mylist = d.mylist.map(String).slice(0, 500);
  if (d.progress && typeof d.progress === 'object') {
    for (const [k, v] of Object.entries(d.progress)) {
      if (v && typeof v === 'object' && v.id != null) {
        next.progress[k] = {
          id: String(v.id), season: +v.season || 0, ep: +v.ep || 0,
          pos: +v.pos || 0, dur: +v.dur || 0, done: !!v.done, t: +v.t || Date.now(),
        };
      }
    }
  }
  if (d.settings && typeof d.settings === 'object') next.settings = { ...next.settings, autoplayNext: d.settings.autoplayNext !== false };
  next.channels = cleanChannels(d.channels);
  state = next;
  save(true); emit();
}
