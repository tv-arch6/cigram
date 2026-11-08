// يحوّل بيانات تطبيق سيكرام (cigram-admin-api) إلى شكل الكتالوج الذي يفهمه الموقع.
//
// GET /movies يرجع مصفوفة صفوف (أو { movies | items | data: [...] }):
//   - فيلم:   { id, type: "movie", title, poster, backdrop, video|link, year, category, imdb, quality,
//               description, subtitle, duration "HH:MM", section_id, section_name, featured, featured_order,
//               hidden, created_at, created_at_ms, ... }
//   - مسلسل:  { id: "series_<tmdb>", type: "series", series_id, title, poster, backdrop, ... }
//   - حلقة:   { id, type: "episode", series_id, season, episode, title, poster, backdrop, video|link, subtitle, duration }
//   - صيغ قديمة: حلقة بنوع "series" مع رقم episode، أو مسلسل يحمل حلقاته داخل series_episodes_json / episodes / seasons.
// GET /home-sections يرجع { sections: [{ id, name, order, show_home, more_only }] } لترتيب أقسام الرئيسية.

const COLLECTION_KEYS = ['movies', 'items', 'data', 'results', 'content', 'catalog', 'entries', 'series'];

const R2_HOSTS = [
  'https://pub-eabe41d14a0644e5800344c99ef4d971.r2.dev',
  'https://pub-6ce52a4bbf2543ee88ad9883ae42a806.r2.dev',
];

/** رابط بديل لنفس الملف على مضيف R2 الآخر (نفس فكرة التطبيق للصور)، أو '' إن لم يوجد. */
export function alternateR2(url) {
  const u = String(url || '');
  for (let i = 0; i < R2_HOSTS.length; i++) {
    if (u.startsWith(R2_HOSTS[i])) return R2_HOSTS[(i + 1) % R2_HOSTS.length] + u.slice(R2_HOSTS[i].length);
  }
  return '';
}

// ---------------------------------------------------------------- helpers
const str = (v) => (v === undefined || v === null ? '' : String(v).trim());

function pick(o, ...keys) {
  for (const k of keys) {
    const v = str(o && o[k]);
    if (v) return v;
  }
  return '';
}

function truthy(v) {
  if (v === true) return true;
  const s = str(v).toLowerCase();
  return s === 'true' || s === '1' || s === 'yes';
}

function intOf(v) {
  const m = str(v).match(/\d+/);
  return m ? parseInt(m[0], 10) : 0;
}

function numOf(v) {
  const n = parseFloat(str(v).replace(',', '.'));
  return Number.isFinite(n) ? n : 0;
}

function parseJsonish(v) {
  if (Array.isArray(v) || (v && typeof v === 'object')) return v;
  const s = str(v);
  if (!s || (s[0] !== '[' && s[0] !== '{')) return null;
  try { return JSON.parse(s); } catch { return null; }
}

/** وقت داخل الحلقة: ثوانٍ، أو mm:ss، أو hh:mm:ss. */
function clockSeconds(v) {
  const s = str(v);
  if (!s) return null;
  if (/^\d+(\.\d+)?$/.test(s)) return Number(s);
  const parts = s.split(':').map(Number);
  if (parts.some((x) => !Number.isFinite(x))) return null;
  return parts.reduce((acc, x) => acc * 60 + x, 0);
}

function msField(o, key) {
  const sec = clockSeconds(o && o[key]);
  if (sec !== null) return sec;
  const ms = numOf(o && o[key + '_ms']);
  return ms > 0 ? ms / 1000 : null;
}

/** مدة العمل بالثواني. لوحة الإدارة تكتبها "HH:MM" (ساعات:دقائق). */
function durationSeconds(v) {
  const s = str(v);
  if (!s) return 0;
  let m = s.match(/^(\d{1,2}):(\d{1,2})$/);
  if (m) return (+m[1] * 60 + +m[2]) * 60;
  m = s.match(/^(\d{1,2}):(\d{1,2}):(\d{1,2})$/);
  if (m) return +m[1] * 3600 + +m[2] * 60 + +m[3];
  if (/^\d+(\.\d+)?$/.test(s)) {
    const n = Number(s);
    return n <= 600 ? Math.round(n * 60) : Math.round(n); // رقم صغير = دقائق
  }
  const h = s.match(/(\d+)\s*(h|hr|hour|س|ساع)/i);
  const mi = s.match(/(\d+)\s*(m|min|د|دق)/i);
  if (h || mi) return (h ? +h[1] * 3600 : 0) + (mi ? +mi[1] * 60 : 0);
  return 0;
}

function genresOf(o) {
  const raw = o.genres ?? o.category ?? o.genre;
  const list = Array.isArray(raw) ? raw : str(raw).split(/[,،/|•]/);
  return Array.from(new Set(list.map((g) => str(g)).filter(Boolean)));
}

function addedMs(o) {
  const ms = numOf(o.created_at_ms);
  if (ms > 0) return ms;
  return Date.parse(str(o.created_at || o.added || o.date)) || 0;
}

function videoOf(o) {
  return pick(o, 'video', 'link', 'video_url', 'movie_url', 'url', 'stream_url', 'play_url');
}

function subtitlesOf(o) {
  const src = pick(o, 'subtitle', 'subtitle_url');
  if (!/^https?:\/\//i.test(src)) return [];
  return [{ label: 'العربية', lang: 'ar', src, default: true }];
}

/** أول مقطع من skip_segments يعتبر المقدمة (إن لم تُحدد intro_start/intro_end). */
function introFromSegments(v) {
  const parsed = parseJsonish(v);
  let first = null;
  if (Array.isArray(parsed)) first = parsed[0];
  else if (parsed && typeof parsed === 'object') first = Array.isArray(parsed.segments) ? parsed.segments[0] : parsed;
  if (first && typeof first === 'object') {
    const a = clockSeconds(first.start ?? first.from ?? first.s);
    const b = clockSeconds(first.end ?? first.to ?? first.e);
    if (a !== null && b !== null && b > a) return { start: a, end: b };
    return null;
  }
  const m = str(v).match(/([\d:.]+)\s*[-–>]+\s*([\d:.]+)/);
  if (m) {
    const a = clockSeconds(m[1]);
    const b = clockSeconds(m[2]);
    if (a !== null && b !== null && b > a) return { start: a, end: b };
  }
  return null;
}

function introOf(o) {
  const start = msField(o, 'intro_start');
  const end = msField(o, 'intro_end');
  if (start !== null && end !== null && end > start) return { start, end };
  return introFromSegments(o.skip_segments);
}

function rowsOf(raw) {
  if (Array.isArray(raw)) return raw;
  if (raw && typeof raw === 'object') {
    for (const k of COLLECTION_KEYS) {
      const v = parseJsonish(raw[k]);
      if (Array.isArray(v)) return v;
    }
  }
  return [];
}

function isEpisodeRow(o) {
  const t = str(o.type || o.content_type || o.media_type).toLowerCase();
  return t === 'episode' || ((t === 'series' || t === 'tv') && intOf(o.episode || o.episode_number) > 0);
}

function isSeriesRow(o) {
  const t = str(o.type || o.content_type || o.media_type).toLowerCase();
  return (t === 'series' || t === 'tv') && !isEpisodeRow(o);
}

// ---------------------------------------------------------------- episodes
function episodeFrom(o, fallbackSeason) {
  const video = videoOf(o);
  if (!video) return null;
  const season = intOf(o.season ?? o.season_number) || fallbackSeason || 1;
  const number = intOf(o.episode ?? o.episode_number ?? o.ep ?? o.number);
  if (!number) return null;
  const intro = introOf(o);
  return {
    season,
    number,
    title: pick(o, 'episode_title', 'title', 'name'),
    video,
    duration: durationSeconds(o.duration ?? o.runtime),
    thumb: pick(o, 'backdrop', 'thumb', 'image', 'poster'),
    description: pick(o, 'description', 'overview'),
    intro_start: intro ? intro.start : null,
    intro_end: intro ? intro.end : null,
    credits_start: msField(o, 'credits_start'),
    subtitles: subtitlesOf(o),
    _added: addedMs(o),
    _section: str(o.section_id),
  };
}

/** حلقات مخزنة داخل سجل المسلسل نفسه (الصيغ القديمة). */
function embeddedEpisodes(o) {
  const out = [];
  for (const key of ['series_episodes_json', 'series_episodes', 'episodes', 'seasons']) {
    const parsed = parseJsonish(o[key]);
    if (!parsed) continue;
    walkEpisodes(parsed, 0, out, 0);
  }
  return out;
}

function walkEpisodes(node, season, out, depth) {
  if (!node || depth > 4) return;
  if (Array.isArray(node)) { node.forEach((n) => walkEpisodes(n, season, out, depth + 1)); return; }
  if (typeof node !== 'object') return;
  const nested = node.episodes ?? node.items;
  if (nested) {
    const s = intOf(node.season ?? node.season_number ?? node.number) || season;
    walkEpisodes(parseJsonish(nested), s, out, depth + 1);
    return;
  }
  const ep = episodeFrom(node, season);
  if (ep) out.push(ep);
}

// ---------------------------------------------------------------- main
/**
 * @param {any} rawMovies   رد GET /movies
 * @param {any} rawSections رد GET /home-sections (اختياري)
 * @returns {{ items: object[], sections: object[] }} بنفس شكل data/catalog.json
 */
export function fromCigram(rawMovies, rawSections) {
  const rows = rowsOf(rawMovies).filter((o) => o && typeof o === 'object' && !truthy(o.hidden));
  const sectionNames = new Map();
  for (const o of rows) {
    const id = str(o.section_id);
    if (id && !sectionNames.has(id) && str(o.section_name)) sectionNames.set(id, str(o.section_name));
  }

  const movies = [];
  const seriesMap = new Map(); // key -> { header, episodes: Map }

  const seriesEntry = (key) => {
    let s = seriesMap.get(key);
    if (!s) { s = { key, header: null, firstEpisodeRow: null, episodes: new Map() }; seriesMap.set(key, s); }
    return s;
  };
  const addEpisode = (s, ep) => {
    const k = ep.season + ':' + ep.number;
    const old = s.episodes.get(k);
    if (!old || ep._added > old._added) s.episodes.set(k, ep);
  };

  for (const o of rows) {
    if (isEpisodeRow(o)) {
      const key = pick(o, 'series_id') || 'title:' + pick(o, 'series_title', 'title').toLowerCase();
      const s = seriesEntry(key);
      if (!s.firstEpisodeRow) s.firstEpisodeRow = o;
      const ep = episodeFrom(o, 0);
      if (ep) addEpisode(s, ep);
    } else if (isSeriesRow(o)) {
      const key = pick(o, 'series_id', 'id');
      const s = seriesEntry(key);
      if (!s.header) s.header = o;
      for (const ep of embeddedEpisodes(o)) addEpisode(s, ep);
    } else {
      movies.push(o);
    }
  }

  const items = [];
  const used = new Set();
  const uniqueId = (id) => {
    let v = id || 'item';
    for (let i = 2; used.has(v); i++) v = `${id}-${i}`;
    used.add(v);
    return v;
  };

  for (const o of movies) {
    const title = pick(o, 'title', 'name', 'title_ar', 'title_en', 'original_title');
    if (!title) continue;
    const intro = introOf(o);
    items.push({
      id: uniqueId(pick(o, 'id', 'movie_id', 'item_id', 'content_id', 'slug') || title),
      type: 'movie',
      title,
      original_title: pick(o, 'original_title', 'title_en'),
      alt_titles: [pick(o, 'title_ar'), pick(o, 'title_en'), pick(o, 'original_title')].filter(Boolean),
      year: intOf(pick(o, 'year', 'release_year').slice(0, 4)),
      rating: numOf(pick(o, 'imdb', 'rating', 'imdb_rating', 'vote_average', 'score')),
      quality: pick(o, 'quality', 'resolution', 'video_quality'),
      duration: durationSeconds(o.duration ?? o.runtime),
      genres: genresOf(o),
      description: pick(o, 'description', 'overview', 'plot', 'synopsis'),
      poster: pick(o, 'poster', 'image', 'backdrop'),
      backdrop: pick(o, 'backdrop', 'landscape_poster', 'source_backdrop', 'poster'),
      video: videoOf(o),
      subtitles: subtitlesOf(o),
      intro_start: intro ? intro.start : null,
      intro_end: intro ? intro.end : null,
      credits_start: msField(o, 'credits_start'),
      featured: truthy(o.featured),
      featured_order: numOf(o.featured_order) || 0,
      added: addedMs(o),
      _section: str(o.section_id),
    });
  }

  for (const s of seriesMap.values()) {
    const h = s.header || s.firstEpisodeRow;
    if (!h) continue;
    const fromEpisode = !s.header;
    const title = fromEpisode
      ? pick(h, 'series_title') || (str(h.type).toLowerCase() === 'series' ? pick(h, 'title', 'name') : '') || s.key
      : pick(h, 'series_title', 'title', 'name', 'title_ar', 'title_en');
    if (!title) continue;

    const eps = Array.from(s.episodes.values());
    const bySeason = new Map();
    for (const e of eps) {
      if (!bySeason.has(e.season)) bySeason.set(e.season, []);
      bySeason.get(e.season).push(e);
    }
    const seasons = Array.from(bySeason.keys()).sort((a, b) => a - b).map((n) => ({
      number: n,
      title: `الموسم ${n}`,
      episodes: bySeason.get(n).sort((a, b) => a.number - b.number).map((e) => ({
        ...e,
        // اسم الحلقة إن كان هو نفسه اسم المسلسل لا يفيد
        title: e.title && e.title !== title ? e.title : `الحلقة ${e.number}`,
      })),
    }));

    const latest = Math.max(addedMs(h), ...eps.map((e) => e._added), 0);
    const sections = new Set([str(h.section_id), ...eps.map((e) => e._section)].filter(Boolean));
    const intro = introOf(h);

    items.push({
      id: uniqueId(s.key),
      type: 'series',
      title,
      original_title: pick(h, 'original_title', 'title_en'),
      alt_titles: [pick(h, 'title_ar'), pick(h, 'title_en'), pick(h, 'original_title')].filter(Boolean),
      year: intOf(pick(h, 'year', 'release_year').slice(0, 4)),
      rating: numOf(pick(h, 'imdb', 'rating', 'imdb_rating', 'vote_average', 'score')),
      quality: pick(h, 'quality'),
      genres: genresOf(h),
      description: fromEpisode ? '' : pick(h, 'description', 'overview', 'plot', 'synopsis'),
      poster: pick(h, 'series_poster', 'poster', 'season_poster', 'image', 'backdrop'),
      backdrop: pick(h, 'backdrop', 'landscape_poster', 'source_backdrop', 'series_poster', 'poster'),
      intro_start: intro ? intro.start : null,
      intro_end: intro ? intro.end : null,
      featured: !fromEpisode && truthy(h.featured),
      featured_order: numOf(h.featured_order) || 0,
      added: latest,
      seasons,
      _sections: sections,
    });
  }

  // الأحدث أولاً، مثل التطبيق
  items.sort((a, b) => b.added - a.added);

  return { items: items.map(stripPrivate), sections: buildSections(items, rawSections, sectionNames) };
}

function stripPrivate(it) {
  const out = { ...it, added: it.added ? new Date(it.added).toISOString() : '' };
  delete out._section;
  delete out._sections;
  if (out.seasons) {
    out.seasons = out.seasons.map((s) => ({
      ...s,
      episodes: s.episodes.map(({ _added, _section, ...e }) => e),
    }));
  }
  return out;
}

const ROW_LIMIT = 40;

/**
 * نفس ترتيب رئيسية التطبيق: «أحدث الأفلام» و«المسلسلات المميزة» أولاً (الأحدث يميناً)، ثم أقسام لوحة الإدارة
 * بترتيبها. العنوان الموجود في قسم خاص (مثل أنمي) لا يتكرر في الصفين الثابتين (CigramOrder.claims في التطبيق).
 */
function buildSections(items, rawSections, sectionNames) {
  const sectionOf = (it) => (it._sections ? Array.from(it._sections) : it._section ? [it._section] : []);
  const out = [];
  const registry = Array.isArray(rawSections?.sections) ? rawSections.sections : [];
  const ordered = registry
    .filter((s) => s && str(s.id) && s.enabled !== false && s.show_home !== false && !truthy(s.more_only))
    .sort((a, b) => (Number(a.order) || 0) - (Number(b.order) || 0));

  const custom = [];
  const known = new Set();
  for (const s of ordered) {
    const id = str(s.id);
    known.add(id);
    const ids = items.filter((it) => sectionOf(it).includes(id)).map((it) => it.id);
    if (ids.length) {
      custom.push({ id: 'sec-' + id, admin_id: id, name: str(s.name) || sectionNames.get(id) || id, ids,
        style: str(s.style || s.layout || s.display), keep_order: truthy(s.keep_order) });
    }
  }
  // أقسام موجودة في المحتوى وغير موجودة في السجل (لما يفشل /home-sections أو قسم جديد)
  if (!registry.length) {
    const names = new Map();
    for (const it of items) {
      for (const id of sectionOf(it)) {
        if (known.has(id)) continue;
        if (!names.has(id)) names.set(id, []);
        names.get(id).push(it.id);
      }
    }
    for (const [id, ids] of names) custom.push({ id: 'sec-' + id, admin_id: id, name: sectionNames.get(id) || id, ids, style: '' });
  }

  // الصفان الثابتان لا يكرران ما في الأقسام الخاصة (الأقسام القياسية مثل latest_movies لا تُعد خاصة)
  const STANDARD = /^(latest|new|movies?|series|featured|latest_movies|featured_series|all)$/i;
  const claimed = new Set();
  for (const c of custom) if (!STANDARD.test(c.admin_id)) c.ids.forEach((id) => claimed.add(id));
  const free = (i) => !claimed.has(i.id);

  const movies = items.filter((i) => i.type === 'movie' && free(i));
  const series = items.filter((i) => i.type === 'series' && free(i));
  if (movies.length) out.push({ id: 'movies', name: 'أحدث الأفلام', ids: movies.map((i) => i.id) });
  if (series.length) out.push({ id: 'series', name: 'المسلسلات المميزة', ids: series.map((i) => i.id) });
  for (const c of custom) {
    if (/^(latest_movies|featured_series)$/i.test(c.admin_id)) continue;   // هما الصفان الثابتان نفسهما
    out.push(c);
  }
  return out;
}

export { ROW_LIMIT };
