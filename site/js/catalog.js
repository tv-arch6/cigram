import { CONFIG } from './config.js';
import { norm } from './util.js';
import { fromCigram } from './cigram.js';

/*
  شكل ملف الكتالوج (data/catalog.json):
  {
    "items": [
      { "id": "film-1", "type": "movie", "title": "...", "original_title": "...", "year": 2020, "rating": 7.8,
        "quality": "HD", "duration": 5400, "genres": ["دراما"], "description": "...",
        "poster": "https://...", "backdrop": "https://...", "video": "https://.../movie.mp4 أو .m3u8",
        "badges": ["new"], "featured": true, "added": "2026-01-15",
        "subtitles": [{ "label": "العربية", "lang": "ar", "src": "https://.../ar.vtt" }] },
      { "id": "show-1", "type": "series", "title": "...", ...،
        "seasons": [ { "number": 1, "title": "الموسم الأول",
          "episodes": [ { "number": 1, "title": "...", "video": "https://...", "duration": 2700,
                          "thumb": "https://...", "intro_start": 5, "intro_end": 60 } ] } ] }
    ],
    "sections": [ { "id": "new", "name": "أضيف حديثاً", "ids": ["film-1", "show-1"] } ]   // اختياري
  }
*/

export class Catalog {
  constructor(raw) {
    const items = Array.isArray(raw) ? raw : Array.isArray(raw?.items) ? raw.items : [];
    this.items = items.map(normalizeItem).filter(Boolean);
    this.byId = new Map(this.items.map((i) => [i.id, i]));
    this.rawSections = Array.isArray(raw?.sections) ? raw.sections : [];
  }

  get(id) { return this.byId.get(String(id)) || null; }

  featured(limit = 5) {
    const flagged = this.items
      .filter((i) => i.featured && (i.backdrop || i.poster))
      .sort((a, b) => (a.featuredOrder || 1e9) - (b.featuredOrder || 1e9));
    const pool = flagged.length ? flagged : this.items.filter((i) => i.backdrop || i.poster);
    return pool.slice(0, limit);
  }

  genres() {
    const set = new Map();
    for (const i of this.items) for (const g of i.genres) set.set(norm(g), g);
    return Array.from(set.values());
  }

  sections() {
    if (this.rawSections.length) {
      return this.rawSections
        .map((s) => ({
          id: String(s.id || s.name), adminId: String(s.admin_id || ''), name: s.name, style: String(s.style || ''),
          items: (s.ids || []).map((id) => this.get(id)).filter(Boolean),
        }))
        .filter((s) => s.items.length);
    }
    const sorted = [...this.items].sort((a, b) => b.addedMs - a.addedMs || b.year - a.year);
    const out = [{ id: 'latest', name: 'أضيف حديثاً', items: sorted.slice(0, 20) }];
    const movies = this.items.filter((i) => i.type === 'movie');
    const series = this.items.filter((i) => i.type === 'series');
    if (movies.length) out.push({ id: 'movies', name: 'أفلام', items: movies });
    if (series.length) out.push({ id: 'series', name: 'مسلسلات', items: series });
    for (const g of this.genres()) {
      const list = this.items.filter((i) => i.genres.some((x) => norm(x) === norm(g)));
      if (list.length >= 2) out.push({ id: 'g-' + norm(g), name: g, items: list });
    }
    return out;
  }

  related(item, limit = 12) {
    const mine = new Set(item.genres.map(norm));
    return this.items
      .filter((i) => i.id !== item.id)
      .map((i) => ({ i, score: i.genres.filter((g) => mine.has(norm(g))).length }))
      .filter((x) => x.score > 0)
      .sort((a, b) => b.score - a.score)
      .slice(0, limit)
      .map((x) => x.i);
  }

  search(query, { genre = '', type = '' } = {}) {
    const q = norm(query);
    const words = q ? q.split(' ') : [];
    const g = genre ? norm(genre) : '';
    return this.items.filter((i) => {
      if (type && i.type !== type) return false;
      if (g && !i.genres.some((x) => norm(x) === g)) return false;
      return words.every((w) => i._search.includes(w));
    });
  }
}

function normalizeItem(o) {
  if (!o || typeof o !== 'object') return null;
  const title = String(o.title || o.name || '').trim();
  const id = String(o.id ?? '').trim();
  if (!title || !id) return null;

  const seasons = (Array.isArray(o.seasons) ? o.seasons : [])
    .map((s, si) => ({
      number: Number(s.number) || si + 1,
      title: s.title || `الموسم ${Number(s.number) || si + 1}`,
      episodes: (Array.isArray(s.episodes) ? s.episodes : [])
        .map((e, ei) => ({
          number: Number(e.number) || ei + 1,
          title: e.title || `الحلقة ${Number(e.number) || ei + 1}`,
          video: String(e.video || e.url || e.link || ''),
          duration: Number(e.duration) || 0,
          thumb: e.thumb || e.image || '',
          description: e.description || '',
          intro_start: numOrNull(e.intro_start),
          intro_end: numOrNull(e.intro_end),
          credits_start: numOrNull(e.credits_start),
          subtitles: Array.isArray(e.subtitles) ? e.subtitles : [],
        }))
        .filter((e) => e.video)
        .sort((a, b) => a.number - b.number),
    }))
    .filter((s) => s.episodes.length)
    .sort((a, b) => a.number - b.number);

  const type = o.type === 'series' || seasons.length ? 'series' : 'movie';
  const genres = (Array.isArray(o.genres) ? o.genres : String(o.genres || '').split(/[,،]/))
    .map((g) => String(g).trim()).filter(Boolean);
  const addedMs = Date.parse(o.added || '') || 0;

  return {
    id, type, title,
    original: String(o.original_title || ''),
    year: Number(o.year) || 0,
    rating: Number(o.rating) || 0,
    quality: String(o.quality || ''),
    duration: Number(o.duration) || 0,
    genres,
    description: String(o.description || ''),
    poster: String(o.poster || o.backdrop || ''),
    backdrop: String(o.backdrop || o.poster || ''),
    video: String(o.video || ''),
    badges: Array.isArray(o.badges) ? o.badges.map(String) : [],
    featured: !!o.featured,
    featuredOrder: Number(o.featured_order) || 0,
    subtitles: Array.isArray(o.subtitles) ? o.subtitles : [],
    intro_start: numOrNull(o.intro_start),
    intro_end: numOrNull(o.intro_end),
    credits_start: numOrNull(o.credits_start),
    seasons, addedMs,
    _search: norm([title, o.original_title, ...(o.alt_titles || []), ...genres, ...(o.cast || [])].join(' ')),
  };
}

const numOrNull = (v) => (v === undefined || v === null || v === '' || isNaN(Number(v)) ? null : Number(v));

// ---------------------------------------------------------------- episodes
export function allEpisodes(item) {
  const list = [];
  for (const s of item.seasons) for (const e of s.episodes) list.push({ season: s.number, ...e });
  return list;
}
export function findEpisode(item, season, ep) {
  return allEpisodes(item).find((e) => e.season === Number(season) && e.number === Number(ep)) || null;
}
export function nextEpisode(item, season, ep) {
  const list = allEpisodes(item);
  const i = list.findIndex((e) => e.season === Number(season) && e.number === Number(ep));
  return i >= 0 && i + 1 < list.length ? list[i + 1] : null;
}

// ---------------------------------------------------------------- loading (v2)
// 1) آخر نسخة ناجحة تُحفظ في المتصفح (Cache API: يتسع لكتالوج بعدة ميغابايت)، فتُرسم الرئيسية فوراً عند الفتح.
// 2) الشبكة تُسأل بعدها، مع مهلة وإعادة محاولة، وإذا تغيّر شيء يتحدث المحتوى في مكانه.
const SNAP = 'cigram-catalog-snap-v1';
const SNAP_KEYS = { movies: '/__cigram_snap/movies', sections: '/__cigram_snap/sections', carousel: '/__cigram_snap/carousel' };
const TIMEOUT_MS = 25000;

async function getText(url, { timeout = TIMEOUT_MS } = {}) {
  const ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
  const timer = ctrl ? setTimeout(() => ctrl.abort(), timeout) : 0;
  try {
    const res = await fetch(url, { cache: 'no-cache', headers: { Accept: 'application/json' }, signal: ctrl ? ctrl.signal : undefined });
    if (!res.ok) throw new Error(url + ' ' + res.status);
    return await res.text();
  } finally {
    clearTimeout(timer);
  }
}

async function getTextRetry(url, tries = 3) {
  let last;
  for (let i = 0; i < tries; i++) {
    try { return await getText(url); } catch (e) {
      last = e;
      console.warn('[سيكرام] تعذر تحميل', url, e && e.message ? e.message : e);
      if (i < tries - 1) await new Promise((r) => setTimeout(r, 1200 * (i + 1)));
    }
  }
  throw last;
}

/** بصمة قصيرة للنص (FNV-1a) لنعرف هل تغيّر المحتوى منذ آخر تحميل. */
function fingerprint(text) {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) { h ^= text.charCodeAt(i); h = Math.imul(h, 0x01000193); }
  return (h >>> 0).toString(36) + ':' + text.length;
}

function carouselOf(text) {
  try {
    const raw = text ? JSON.parse(text) : null;
    const doc = Array.isArray(raw) ? { enabled: raw.length > 0, items: raw } : raw;
    if (!doc || doc.enabled === false || !Array.isArray(doc.items)) return [];
    return doc.items.filter((x) => x && typeof x === 'object');
  } catch { return []; }
}

function build(movies, sections, carousel) {
  let sectionsJson = null;
  try { sectionsJson = sections ? JSON.parse(sections) : null; } catch { sectionsJson = null; }
  const cat = new Catalog(fromCigram(JSON.parse(movies), sectionsJson));
  if (!cat.items.length) throw new Error('empty catalog');
  cat.carousel = carouselOf(carousel);
  cat.signature = fingerprint(movies + '\n' + sections + '\n' + (carousel || ''));
  return cat;
}

async function saveSnapshot(texts) {
  try {
    if (!('caches' in window)) return;
    const c = await caches.open(SNAP);
    await Promise.all(Object.keys(SNAP_KEYS).map((k) => c.put(SNAP_KEYS[k], new Response(texts[k] || '', { headers: { 'Content-Type': 'application/json' } }))));
  } catch { /* تصفح خاص / مساحة ممتلئة: لا مشكلة */ }
}

/** آخر كتالوج ناجح محفوظ في هذا المتصفح (يُرسم فوراً)، أو null. */
export async function loadCachedCatalog() {
  if (CONFIG.source !== 'cigram') return null;
  try {
    if (!('caches' in window)) return null;
    const c = await caches.open(SNAP);
    const [m, s, k] = await Promise.all(Object.keys(SNAP_KEYS).map((key) => c.match(SNAP_KEYS[key])));
    if (!m) return null;
    const movies = await m.text();
    if (!movies) return null;
    return build(movies, s ? await s.text() : '', k ? await k.text() : '');
  } catch { return null; }
}

export async function loadCatalog() {
  if (CONFIG.source !== 'cigram') {
    const text = await getTextRetry(CONFIG.catalogUrl);
    const cat = new Catalog(JSON.parse(text));
    cat.carousel = [];
    cat.signature = fingerprint(text);
    return cat;
  }

  const base = CONFIG.apiBase.replace(/\/+$/, '');
  // ترتيب الأقسام والبانر (/carousel، نفس شرائح أعلى الرئيسية في التطبيق) اختياريان: إن فشلا نكمل بالمحتوى وحده
  const [movies, sections, carousel] = await Promise.all([
    getTextRetry(base + '/movies'),
    getText(base + '/home-sections').catch(() => ''),
    getText(base + '/carousel').catch(() => ''),
  ]);
  const cat = build(movies, sections, carousel);
  saveSnapshot({ movies, sections, carousel });
  return cat;
}
