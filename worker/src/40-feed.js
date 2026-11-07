
// ----------------------------------------------------------------------------- discovery feeds (TMDB) + cached-index views

async function wnMapLimit(items, limit, fn) {
  const out = new Array(items.length);
  let next = 0;
  const workers = [];
  for (let w = 0; w < Math.min(limit, items.length); w++) {
    workers.push((async () => {
      while (next < items.length) {
        const i = next++;
        out[i] = await fn(items[i], i);
      }
    })());
  }
  await Promise.all(workers);
  return out;
}

function wnFilters(input) {
  const f = {};
  const g = cleanString(input.genre);
  if (g) {
    if (!/^\d{1,6}([|,]\d{1,6})*$/.test(g)) throw new AdminError("invalid_filter", "فلتر التصنيف غير صالح.", 400);
    f.genre = g.replace(/,/g, "|");
  }
  const lang = cleanString(input.language);
  if (lang) {
    if (!/^[a-z]{2,3}$/.test(lang)) throw new AdminError("invalid_filter", "فلتر اللغة غير صالح.", 400);
    f.language = lang;
  }
  const country = cleanString(input.country).toUpperCase();
  if (country) {
    if (!/^[A-Z]{2}$/.test(country)) throw new AdminError("invalid_filter", "فلتر الدولة غير صالح.", 400);
    f.country = country;
  }
  const year = cleanString(input.year);
  if (year) {
    if (!/^(19|20)\d{2}$/.test(year)) throw new AdminError("invalid_filter", "فلتر السنة غير صالح.", 400);
    f.year = year;
  }
  const rating = cleanString(input.min_rating);
  if (rating) {
    const r = Number(rating);
    if (!Number.isFinite(r) || r < 0 || r > 10) throw new AdminError("invalid_filter", "فلتر التقييم غير صالح.", 400);
    f.min_rating = r;
  }
  for (const k of ["date_from", "date_to"]) {
    const v = cleanString(input[k]);
    if (v) {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(v) || !Number.isFinite(Date.parse(v))) throw new AdminError("invalid_filter", "التاريخ غير صالح (YYYY-MM-DD).", 400);
      f[k] = v;
    }
  }
  const sort = lc(input.sort);
  if (sort) {
    if (!WN_SORTS[sort]) throw new AdminError("invalid_filter", "ترتيب غير معروف.", 400);
    f.sort = sort;
  }
  f.page = Math.min(500, Math.max(1, numOf(input.page) || 1));
  f.fresh = truthy(input.refresh); // the admin pressed Refresh: skip the short-lived cache
  const type = lc(input.type);
  f.type = type === "movie" ? "movie" : type === "series" || type === "tv" ? "series" : "any";
  return f;
}

function wnDiscoverParams(kind, feed, f, cfg) {
  const tv = kind === "series";
  const p = { language: cfg.language || "ar-SA", include_adult: "false", page: f.page };
  const dateKey = tv ? "first_air_date" : "primary_release_date";
  p.sort_by = ({ popularity: "popularity.desc", rating: "vote_average.desc", release: tv ? "first_air_date.desc" : "primary_release_date.desc" })[f.sort || "popularity"];
  if (feed === "new_releases") {
    p[dateKey + ".gte"] = f.date_from || wnToday(-45);
    p[dateKey + ".lte"] = f.date_to || wnToday();
  } else if (feed === "upcoming") {
    p[dateKey + ".gte"] = f.date_from || wnToday(1);
    p[dateKey + ".lte"] = f.date_to || wnToday(180);
  } else if (feed === "recent_episodes" || feed === "new_seasons") {
    p["air_date.gte"] = f.date_from || wnToday(feed === "new_seasons" ? -30 : -14);
    p["air_date.lte"] = f.date_to || wnToday();
  } else if (feed === "popular") {
    p["vote_count.gte"] = 50;
    if (f.date_from) p[dateKey + ".gte"] = f.date_from;
    p[dateKey + ".lte"] = f.date_to || wnToday();
  }
  if (!tv && cfg.region) p.region = cfg.region;
  if (f.genre) p.with_genres = f.genre;
  if (f.language) p.with_original_language = f.language;
  if (f.country) p.with_origin_country = f.country;
  if (f.year) {
    // an explicit year replaces the rolling window of the feed
    delete p[dateKey + ".gte"];
    delete p[dateKey + ".lte"];
    if (tv) p.first_air_date_year = f.year; else p.primary_release_year = f.year;
  }
  if (f.min_rating) {
    p["vote_average.gte"] = f.min_rating;
    p["vote_count.gte"] = Math.max(p["vote_count.gte"] || 0, 10);
  }
  return p;
}

function wnLocalFilter(row, kind, f) {
  const date = cleanString(kind === "series" ? row.first_air_date : row.release_date);
  const ids = Array.isArray(row.genre_ids) ? row.genre_ids.map(String) : [];
  if (f.genre && !f.genre.split("|").some((g) => ids.includes(g))) return false;
  if (f.language && cleanString(row.original_language) !== f.language) return false;
  if (f.country && kind === "series" && !(Array.isArray(row.origin_country) ? row.origin_country : []).includes(f.country)) return false;
  if (f.year && yearOfText(date) !== f.year) return false;
  if (f.min_rating && Number(row.vote_average || 0) < f.min_rating) return false;
  if (f.date_from && date < f.date_from) return false;
  if (f.date_to && date > f.date_to) return false;
  return true;
}

function wnVideoBrief(entry) {
  const v = entry && entry.enrich && entry.enrich.video;
  if (!v || !v.verdict) return { state: "unknown" };
  const state = v.verdict === "playable" || v.verdict === "embed_reachable" ? "ok"
    : v.verdict === "unverified" ? "unverified"
    : v.verdict === "not_released" ? "not_released" : "missing";
  return { state, verdict: v.verdict, provider: v.provider || "", checked_at: v.checked_at || "" };
}

function wnSubBrief(entry) {
  const s = entry && entry.enrich && entry.enrich.subtitle;
  if (!s || !s.checked_at) return { state: "unknown" };
  if (s.status === "unconfigured") return { state: "unconfigured" };
  if (s.status === "error") return { state: "unknown", error: s.error || "" };
  if (s.ar > 0) return { state: "ar", ar: s.ar, en: s.en || 0 };
  if (s.en > 0) return { state: "en", ar: 0, en: s.en };
  return { state: "none" };
}

function wnCard(row, kind, genreMap, lib, idx, extra) {
  const tv = kind === "series";
  const id = Number(row.id) || 0;
  const date = cleanString(tv ? row.first_air_date : row.release_date);
  const key = kind + ":" + id;
  const entry = idx && idx.items ? idx.items[key] : null;
  const card = {
    key,
    type: kind,
    tmdb_id: id,
    title: cleanString(tv ? row.name : row.title) || cleanString(tv ? row.original_name : row.original_title),
    original_title: cleanString(tv ? row.original_name : row.original_title),
    year: yearOfText(date),
    release_date: date,
    released: Boolean(date) && date <= wnToday(),
    poster: tmdbImage(row.poster_path, "w342"),
    backdrop: tmdbImage(row.backdrop_path, "w780"),
    overview: cleanString(row.overview).slice(0, 400),
    rating: Number(Number(row.vote_average || 0).toFixed(1)),
    vote_count: Number(row.vote_count) || 0,
    popularity: Math.round(Number(row.popularity) || 0),
    genre_ids: Array.isArray(row.genre_ids) ? row.genre_ids : [],
    genres: (Array.isArray(row.genre_ids) ? row.genre_ids : []).map((g) => genreMap.get(Number(g))).filter(Boolean),
    language: cleanString(row.original_language),
    countries: Array.isArray(row.origin_country) ? row.origin_country : [],
    imported: lib.has(key),
    existing: lib.get(key) || null,
    video: wnVideoBrief(entry),
    subtitle: wnSubBrief(entry),
    first_seen_at: entry ? entry.first_seen_at : "",
  };
  if (tv) {
    const e = entry && entry.enrich ? entry.enrich : {};
    card.seasons = (extra && extra.seasons) || e.seasons || 0;
    card.episodes = (extra && extra.episodes) || e.episodes || 0;
    card.latest_episode = (extra && extra.latest_episode) || e.latest_episode || null;
    card.new_season = Boolean(extra && extra.new_season);
    card.episodes_pending = e.episodes_pending || 0;
  }
  return card;
}

async function wnTvLight(env, id) {
  return wnTmdb(env, "/tv/" + id, { language: "ar-SA" }, { ttl: 3600, nullOn404: true });
}

async function wnLiveRows(env, cfg, kind, feed, f) {
  const tmdbKind = kind === "series" ? "tv" : "movie";
  if (feed === "trending") {
    const data = await wnTmdb(env, "/trending/" + tmdbKind + "/week", { language: cfg.language || "ar-SA", page: f.page }, { ttl: f.fresh ? 0 : 600 });
    let rows = (Array.isArray(data.results) ? data.results : []).filter((r) => wnLocalFilter(r, kind, f));
    if (f.sort === "rating") rows = rows.slice().sort((a, b) => Number(b.vote_average) - Number(a.vote_average));
    if (f.sort === "release") rows = rows.slice().sort((a, b) => cleanString(b.release_date || b.first_air_date).localeCompare(cleanString(a.release_date || a.first_air_date)));
    return { rows: rows.map((row) => ({ row, kind })), total_pages: Number(data.total_pages) || 1, total_results: Number(data.total_results) || rows.length };
  }
  const data = await wnTmdb(env, "/discover/" + tmdbKind, wnDiscoverParams(kind, feed, f, cfg), { ttl: f.fresh ? 0 : 600 });
  let rows = (Array.isArray(data.results) ? data.results : []).map((row) => ({ row, kind }));
  const totalPages = Math.min(500, Number(data.total_pages) || 1);
  if (feed === "new_seasons") {
    // a "new season" needs details: keep the shows whose latest aired episode is episode 1 of a season (<= 45 days old)
    const picked = rows.slice(0, 12);
    const details = await wnMapLimit(picked, 4, async (r) => {
      try { return await wnTvLight(env, r.row.id); } catch { return null; }
    });
    const out = [];
    picked.forEach((r, i) => {
      const d = details[i];
      const last = d && d.last_episode_to_air;
      if (!last) return;
      const age = last.air_date ? (Date.now() - Date.parse(last.air_date)) / 86400000 : 9999;
      if (Number(last.episode_number) === 1 && age <= 45) {
        r.extra = {
          seasons: Number(d.number_of_seasons) || 0,
          episodes: Number(d.number_of_episodes) || 0,
          latest_episode: { season: Number(last.season_number) || 0, episode: 1, air_date: cleanString(last.air_date), name: cleanString(last.name) },
          new_season: true,
        };
        out.push(r);
      }
    });
    rows = out;
  }
  return { rows, total_pages: totalPages, total_results: Number(data.total_results) || rows.length };
}

function wnViewEntries(view, idx, lib, f) {
  const items = Object.values((idx && idx.items) || {});
  const typeOk = (e) => f.type === "any" || e.kind === f.type;
  const cutoff = Date.now() - 14 * 86400000;
  const state = (e) => wnVideoBrief(e).state;
  let rows = [];
  if (view === "imported") {
    // everything that is already in the library under a TMDB id (not just what this feature imported)
    rows = Array.from(lib.entries()).map(([key, brief]) => ({ kind: brief.type === "series" ? "series" : "movie", tmdb_id: Number(key.split(":")[1]), brief, key }));
    return rows.filter(typeOk);
  }
  for (const e of items) {
    if (!typeOk(e)) continue;
    const key = e.kind + ":" + e.tmdb_id;
    const imported = lib.has(key);
    if (view === "recently_added" && !imported && Date.parse(e.first_seen_at) >= cutoff) rows.push(e);
    else if (view === "ready" && !imported && state(e) === "ok" && e.released !== false) rows.push(e);
    else if (view === "unverified" && !imported && state(e) === "unverified") rows.push(e);
    else if (view === "missing_video" && !imported && state(e) === "missing") rows.push(e);
    else if (view === "missing_subtitle" && !imported && wnSubBrief(e).state === "none") rows.push(e);
  }
  if (view === "recently_added") rows.sort((a, b) => Date.parse(b.first_seen_at) - Date.parse(a.first_seen_at));
  else rows.sort((a, b) => (b.popularity || 0) - (a.popularity || 0));
  return rows;
}

function wnCardFromEntry(e, lib) {
  const key = e.kind + ":" + e.tmdb_id;
  return {
    key, type: e.kind, tmdb_id: e.tmdb_id, title: e.title, original_title: e.original_title, year: e.year, release_date: e.release_date,
    released: e.released !== false, poster: e.poster, backdrop: e.backdrop || "", overview: e.overview || "", rating: e.rating || 0, vote_count: e.vote_count || 0,
    popularity: e.popularity || 0, genres: e.genres || [], genre_ids: [], language: e.language || "", countries: e.countries || [],
    imported: lib.has(key), existing: lib.get(key) || null, video: wnVideoBrief(e), subtitle: wnSubBrief(e), first_seen_at: e.first_seen_at,
    seasons: e.enrich && e.enrich.seasons || 0, episodes: e.enrich && e.enrich.episodes || 0, latest_episode: e.enrich && e.enrich.latest_episode || null,
    new_season: Boolean(e.enrich && e.enrich.new_season), episodes_pending: e.enrich && e.enrich.episodes_pending || 0,
  };
}

async function handleWnFeed(request, env) {
  wnRate("feed", 120);
  const input = await readRequestData(request);
  const feed = cleanString(input.feed) || "all:new_releases";
  const f = wnFilters(input);
  const list = await readMoviesList(env);
  const lib = wnLibraryIndex(list);
  const idx = await wnRead(env, WN_INDEX_FILE, { items: {} });

  if (feed === "collections") {
    const groups = new Map();
    for (const e of Object.values(idx.items || {})) {
      const c = e.enrich && e.enrich.collection;
      if (!c || !c.id) continue;
      const g = groups.get(c.id) || { id: c.id, name: c.name, poster: c.poster, parts: 0, latest: "" };
      g.parts++;
      if (cleanString(e.release_date) > g.latest) g.latest = cleanString(e.release_date);
      groups.set(c.id, g);
    }
    const all = Array.from(groups.values()).sort((a, b) => (a.latest < b.latest ? 1 : -1));
    const slice = all.slice((f.page - 1) * WN_PAGE_SIZE, f.page * WN_PAGE_SIZE);
    const cards = slice.map((g) => ({
      key: "collection:" + g.id, type: "collection", tmdb_id: g.id, title: g.name, original_title: "", year: g.latest.slice(0, 4), release_date: g.latest, released: true,
      poster: g.poster, backdrop: "", overview: g.parts + " جزء مكتشف من هذه السلسلة", rating: 0, vote_count: 0, popularity: 0, genres: [], genre_ids: [], language: "", countries: [],
      imported: false, existing: null, video: { state: "unknown" }, subtitle: { state: "unknown" }, first_seen_at: "", parts_known: g.parts,
    }));
    const notes = all.length ? [] : ["لا توجد سلاسل بعد: تظهر السلاسل بعد المزامنة (تُكتشف من تفاصيل الأفلام المفحوصة)."];
    return okResponse({ feed, page: f.page, total_pages: Math.max(1, Math.ceil(all.length / WN_PAGE_SIZE)), total_results: all.length, cards, source: "index", notes });
  }
  if (WN_VIEW_KEYS.includes(feed)) {
    const rows = wnViewEntries(feed, idx, lib, f);
    const start = (f.page - 1) * WN_PAGE_SIZE;
    const slice = rows.slice(start, start + WN_PAGE_SIZE);
    const cards = slice.map((e) => {
      if (feed === "imported") {
        const b = e.brief;
        return {
          key: e.key, type: e.kind, tmdb_id: e.tmdb_id, title: b.title, original_title: "", year: b.year, release_date: "", released: true, poster: b.poster, backdrop: "",
          overview: "", rating: 0, vote_count: 0, popularity: 0, genres: [], genre_ids: [], language: "", countries: [], imported: true, existing: b,
          video: { state: "unknown" }, subtitle: { state: "unknown" }, first_seen_at: "",
        };
      }
      return wnCardFromEntry(e, lib);
    });
    const notes = [];
    if (!Object.keys(idx.items || {}).length && feed !== "imported") notes.push("لم تتم المزامنة بعد: اضغط «تحديث/مزامنة» لملء هذه القائمة.");
    return okResponse({ feed, page: f.page, total_pages: Math.max(1, Math.ceil(rows.length / WN_PAGE_SIZE)), total_results: rows.length, cards, source: "index", notes });
  }
  if (!WN_FEED_KEYS.includes(feed)) throw new AdminError("invalid_feed", "قائمة اكتشاف غير معروفة.", 400);

  const cfg = await wnConfig(env);
  const [scope, name] = feed.split(":");
  const kinds = scope === "all" ? ["movie", "series"] : [scope === "tv" ? "series" : "movie"];
  const wanted = f.type === "any" ? kinds : kinds.filter((k) => k === f.type);
  const notes = [];
  if (name === "trending" && (f.country || f.sort === "popularity")) {
    if (f.country) notes.push("TMDB لا يدعم فلتر الدولة في «الأكثر رواجاً» للأفلام.");
  }
  if (!wanted.length) return okResponse({ feed, page: f.page, total_pages: 1, total_results: 0, cards: [], source: "tmdb", notes: ["هذه القائمة لا تحتوي هذا النوع."] });

  const [movieGenres, tvGenres] = await Promise.all([
    wanted.includes("movie") ? wnGenres(env, "movie") : Promise.resolve(new Map()),
    wanted.includes("series") ? wnGenres(env, "tv") : Promise.resolve(new Map()),
  ]);
  const results = await Promise.all(wanted.map(async (kind) => {
    try {
      const live = await wnLiveRows(env, cfg, kind, name, f);
      return { kind, ok: true, live };
    } catch (error) {
      return { kind, ok: false, error };
    }
  }));
  if (results.every((r) => !r.ok)) throw results[0].error; // every source failed -> a real error, not an empty page
  const cards = [];
  let totalPages = 1;
  let totalResults = 0;
  for (const r of results) {
    if (!r.ok) {
      notes.push("تعذر تحميل " + (r.kind === "series" ? "المسلسلات" : "الأفلام") + ": " + cleanString(r.error && r.error.message).slice(0, 120));
      continue;
    }
    totalPages = Math.max(totalPages, r.live.total_pages);
    totalResults += r.live.total_results;
    const gm = r.kind === "series" ? tvGenres : movieGenres;
    for (const item of r.live.rows) cards.push(wnCard(item.row, r.kind, gm, lib, idx, item.extra));
  }
  if (wanted.length > 1) cards.sort((a, b) => b.popularity - a.popularity);
  return okResponse({ feed, page: f.page, total_pages: totalPages, total_results: totalResults, cards, source: "tmdb", notes });
}

async function handleWnGenres(request, env) {
  const [movie, tv] = await Promise.all([wnGenres(env, "movie"), wnGenres(env, "tv")]);
  const list = (m) => Array.from(m.entries()).map(([id, name]) => ({ id, name })).filter((g) => g.name).sort((a, b) => (a.name < b.name ? -1 : 1));
  return okResponse({ movie: list(movie), series: list(tv) });
}

// ----------------------------------------------------------------------------- summary / config

function wnSummaryFrom(idx, lib, cfg) {
  const items = Object.values((idx && idx.items) || {});
  const week = Date.now() - 7 * 86400000;
  const s = { new_movies: 0, new_series: 0, recent_episodes: 0, ready: 0, unverified: 0, missing_video: 0, missing_subtitle: 0, tracked: items.length };
  const fresh = [];
  for (const e of items) {
    const key = e.kind + ":" + e.tmdb_id;
    const imported = lib.has(key);
    const vs = wnVideoBrief(e).state;
    if (!imported) {
      if (Date.parse(e.first_seen_at) >= week) {
        if (e.kind === "movie") s.new_movies++; else s.new_series++;
        fresh.push(e);
      }
      if (vs === "ok" && e.released !== false) s.ready++;
      else if (vs === "unverified") s.unverified++;
      else if (vs === "missing") s.missing_video++;
      if (wnSubBrief(e).state === "none") s.missing_subtitle++;
    } else if (e.kind === "series" && e.enrich && e.enrich.episodes_pending > 0) {
      s.recent_episodes += e.enrich.episodes_pending;
    }
  }
  fresh.sort((a, b) => Date.parse(b.first_seen_at) - Date.parse(a.first_seen_at));
  return {
    counts: s,
    available: s.ready + s.unverified,
    latest: fresh.slice(0, 6).map((e) => ({ key: e.kind + ":" + e.tmdb_id, type: e.kind, title: e.title, poster: e.poster, year: e.year })),
    last_success_at: cfg.last_success_at || "",
    last_sync_at: cfg.last_sync_at || "",
    auto_sync: Boolean(cfg.auto_sync),
    interval_minutes: cfg.interval_minutes,
    synced: Boolean(cfg.last_success_at),
  };
}

async function handleWnSummary(request, env) {
  const [cfg, idx, list, cron] = await Promise.all([wnConfig(env), wnRead(env, WN_INDEX_FILE, { items: {} }), readMoviesList(env), wnCronStatus(env)]);
  return okResponse(Object.assign(wnSummaryFrom(idx, wnLibraryIndex(list), cfg), { cron }));
}

async function handleWnCron(request, env) {
  return okResponse(await wnCronStatus(env));
}

/** A movie collection (saga): every part, with what is already in the library, ready for a bulk import. */
async function handleWnCollection(request, env) {
  wnRate("collection", 60);
  const input = await readRequestData(request);
  const id = wnTmdbId(input.id || input.collection_id);
  const [ar, en] = await Promise.all([
    wnTmdb(env, "/collection/" + id, { language: "ar-SA" }, { ttl: 1800, nullOn404: true }),
    wnTmdb(env, "/collection/" + id, { language: "en-US" }, { ttl: 1800, nullOn404: true }),
  ]);
  if (!ar && !en) throw new AdminError("collection_not_found", "السلسلة غير موجودة في TMDB.", 404);
  const [list, idx, genres] = [await readMoviesList(env), await wnRead(env, WN_INDEX_FILE, { items: {} }), await wnGenres(env, "movie")];
  const lib = wnLibraryIndex(list);
  const rows = (Array.isArray(ar && ar.parts) && ar.parts.length ? ar.parts : Array.isArray(en && en.parts) ? en.parts : []).slice().sort((a, b) => cleanString(a.release_date).localeCompare(cleanString(b.release_date)));
  const enBy = new Map((Array.isArray(en && en.parts) ? en.parts : []).map((p) => [Number(p.id), p]));
  const cards = rows.map((row) => wnCard(Object.assign({}, enBy.get(Number(row.id)) || {}, row, { title: cleanString(row.title) || cleanString((enBy.get(Number(row.id)) || {}).title) }), "movie", genres, lib, idx));
  return okResponse({
    id: Number(id), name: pickText(ar, en, "name"), overview: pickText(ar, en, "overview"), poster: tmdbImage(pickText(ar, en, "poster_path"), "w500"),
    backdrop: tmdbImage(pickText(ar, en, "backdrop_path"), "w780"), parts: cards, total: cards.length,
    released: cards.filter((c) => c.released).length, in_library: cards.filter((c) => c.imported).length,
  });
}

function wnProviderStatus(env, cfg) {
  const keys = subsKeys(env);
  let tmdb = false;
  try { tmdbCredentials(env); tmdb = true; } catch { tmdb = false; }
  return {
    tmdb,
    subdl: Boolean(keys.subdl),
    subsource: Boolean(keys.subsource),
    opensubtitles: Boolean(keys.opensubtitles),
    opensubtitles_download: Boolean(keys.opensubtitles_login),
    subtitles_any: Boolean(keys.subdl || keys.subsource || keys.opensubtitles),
    video_providers: (cfg.video_providers || []).map((p) => ({
      id: p.id, name: p.name, kind: p.kind, enabled: p.enabled !== false,
      can_prove_playback: p.kind === "hls" || p.kind === "file",
      note: p.kind === "embed" ? "مشغّل مضمَّن (iframe): يمكن فحص أن الصفحة تعمل فقط، لا إثبات التشغيل ولا واجهة بحث رسمية." : "",
    })),
  };
}

async function handleWnConfigGet(request, env) {
  const cfg = await wnConfig(env);
  return okResponse({ config: cfg, providers: wnProviderStatus(env, cfg), feeds: WN_FEED_KEYS, views: WN_VIEW_KEYS, cron: await wnCronStatus(env) });
}

async function handleWnConfigSave(request, env) {
  const input = await readRequestData(request);
  const patch = {};
  if (input.auto_sync !== undefined) patch.auto_sync = truthy(input.auto_sync);
  if (input.interval_minutes !== undefined) {
    const n = numOf(input.interval_minutes);
    if (n < 30 || n > 10080) throw new AdminError("invalid_interval", "الفاصل الزمني بين 30 دقيقة و 7 أيام.", 400);
    patch.interval_minutes = n;
  }
  if (input.enrich_limit !== undefined) {
    const n = numOf(input.enrich_limit);
    if (n < 0 || n > 20) throw new AdminError("invalid_enrich_limit", "عدد الفحوصات لكل مزامنة بين 0 و 20.", 400);
    patch.enrich_limit = n;
  }
  if (input.max_pages !== undefined) {
    const n = numOf(input.max_pages);
    if (n < 1 || n > 3) throw new AdminError("invalid_max_pages", "عدد الصفحات بين 1 و 3.", 400);
    patch.max_pages = n;
  }
  if (input.feeds !== undefined) {
    if (!Array.isArray(input.feeds) || input.feeds.some((x) => !WN_FEED_KEYS.includes(x))) throw new AdminError("invalid_feeds", "قائمة المصادر تحتوي اسماً غير معروف.", 400);
    patch.feeds = input.feeds.slice(0, 8);
  }
  if (input.language !== undefined) {
    if (!["ar-SA", "en-US"].includes(cleanString(input.language))) throw new AdminError("invalid_language", "اللغة يجب أن تكون ar-SA أو en-US.", 400);
    patch.language = cleanString(input.language);
  }
  if (input.region !== undefined) {
    const r = cleanString(input.region).toUpperCase();
    if (r && !/^[A-Z]{2}$/.test(r)) throw new AdminError("invalid_region", "رمز الدولة حرفان (مثل SA).", 400);
    patch.region = r;
  }
  if (input.subtitle_languages !== undefined) {
    if (!Array.isArray(input.subtitle_languages) || !input.subtitle_languages.length) throw new AdminError("invalid_language", "اختر لغة ترجمة واحدة على الأقل.", 400);
    patch.subtitle_languages = input.subtitle_languages.map((l) => wnSubLang(l, false)).slice(0, 5);
  }
  if (input.subtitle_required !== undefined) patch.subtitle_required = truthy(input.subtitle_required);
  if (input.notify_telegram !== undefined) patch.notify_telegram = truthy(input.notify_telegram);
  if (input.video_providers !== undefined) {
    if (!Array.isArray(input.video_providers) || !input.video_providers.length || input.video_providers.length > 5) {
      throw new AdminError("invalid_provider", "أضف من 1 إلى 5 مزوّدين.", 400);
    }
    const seen = new Set();
    patch.video_providers = input.video_providers.map((p, i) => {
      const v = wnValidateProvider(p || {}, i);
      if (seen.has(v.id)) throw new AdminError("invalid_provider", "معرّف مزوّد مكرر: " + v.id, 400);
      seen.add(v.id);
      return v;
    });
  }
  const res = await wnMutate(env, WN_CONFIG_FILE, null, (doc) => {
    const merged = wnMergeConfig(Object.assign({}, doc || {}, patch));
    return { doc: merged, result: merged };
  });
  return okResponse({ config: res.result, providers: wnProviderStatus(env, res.result) }, "تم حفظ الإعدادات.");
}

// ----------------------------------------------------------------------------- details / season / video routes

function wnFindHeader(list, tmdb) {
  return findById(list, "series_" + tmdb) || list.find((x) => x && isSeriesHeader(x) && tmdbIdOf(x) === tmdb) || null;
}

async function handleWnDetails(request, env) {
  wnRate("details", 120);
  const input = await readRequestData(request);
  const kind = wnKind(input.type);
  const tmdb = wnTmdbId(input.tmdb_id);
  const meta = await wnFetchMeta(env, kind, tmdb);
  const [list, cfg] = [await readMoviesList(env), await wnConfig(env)];
  const registry = await readSectionsRegistry(env, list);
  const lib = wnLibraryIndex(list);
  const existing = lib.get(kind + ":" + tmdb) || null;
  const dups = findDuplicates(list, { type: kind, tmdb, imdb: meta.imdb_id, title: meta.title, year: meta.year });
  const out = Object.assign({}, meta, {
    import: { state: existing ? "imported" : dups.length ? "duplicate" : "new", existing, duplicates: dups },
    sections: registry.sections.map((s) => ({ id: s.id, name: s.name, type: s.type, enabled: s.enabled })),
    suggested_sections: suggestSections(registry, meta.genres.map((name) => ({ name, en: "" })), kind),
    existing_categories: wnExistingCategories(list),
    suggested_slug: suggestSlug(list, meta.original_title, meta.title, meta.year, tmdb, kind),
    collection: meta.collection || null,
    providers: wnProviderStatus(env, cfg),
  });
  if (kind === "series") {
    const header = wnFindHeader(list, tmdb);
    const lib2 = wnLibraryEpisodes(list, header);
    out.library_episodes = Object.fromEntries(Array.from(lib2.entries()).map(([s, set]) => [String(s), Array.from(set).sort((a, b) => a - b)]));
    out.library_header = header ? briefOf(header) : null;
    const le = meta.latest_episode;
    out.latest_in_library = Boolean(le && lib2.get(le.season) && lib2.get(le.season).has(le.episode));
  }
  return okResponse(out);
}

async function handleWnSeason(request, env) {
  wnRate("season", 120);
  const input = await readRequestData(request);
  const tmdb = wnTmdbId(input.tmdb_id);
  const season = numOf(input.season);
  if (season < 1 || season > 200) throw new AdminError("missing_numbers", "رقم الموسم غير صالح.", 400);
  const data = await wnSeasonEpisodes(env, tmdb, season);
  const list = await readMoviesList(env);
  const have = wnLibraryEpisodes(list, wnFindHeader(list, tmdb)).get(season) || new Set();
  data.episodes = data.episodes.map((e) => Object.assign({}, e, { in_library: have.has(e.episode) }));
  data.aired_count = data.episodes.filter((e) => e.aired).length;
  return okResponse(data);
}

async function handleWnVideo(request, env) {
  wnRate("video", 60);
  const input = await readRequestData(request);
  const kind = wnKind(input.type);
  const tmdb = wnTmdbId(input.tmdb_id);
  const cfg = await wnConfig(env);
  const customUrl = cleanString(input.custom_url);
  const meta = await wnFetchMeta(env, kind, tmdb);
  let targets = [];
  let total = 1;
  let nextOffset = 0;
  if (kind === "movie") {
    targets = [{ kind: "movie", tmdb, season: 0, episode: 0, released: meta.released }];
  } else {
    const season = numOf(input.season);
    if (!season) throw new AdminError("missing_numbers", "رقم الموسم مطلوب.", 400);
    const data = await wnSeasonEpisodes(env, tmdb, season);
    let want = Array.isArray(input.episodes) && input.episodes.length ? input.episodes.map((n) => numOf(n)).filter((n) => n > 0) : data.episodes.map((e) => e.episode);
    want = Array.from(new Set(want));
    total = want.length;
    const offset = Math.max(0, numOf(input.offset));
    const slice = want.slice(offset, offset + WN_MAX_PROBE_TARGETS);
    nextOffset = offset + slice.length >= total ? 0 : offset + slice.length;
    const byNum = new Map(data.episodes.map((e) => [e.episode, e]));
    targets = slice.map((n) => {
      const ep = byNum.get(n);
      // an episode number TMDB does not list for this season can never be resolved to a URL (no guessing)
      return { kind: "episode", tmdb, season, episode: n, released: ep ? ep.aired : false, unknown: !ep, name: ep ? ep.name : "" };
    });
    if (customUrl && targets.length !== 1) throw new AdminError("custom_needs_one", "الرابط اليدوي يُفحص لحلقة واحدة فقط.", 400);
  }
  const resolved = await wnMapLimit(targets, 4, async (t) => {
    if (t.unknown) {
      const base = { provider: "n/a", name: "—", kind: "embed", url: "" };
      return { kind: "episode", season: t.season, episode: t.episode, tmdb_id: String(tmdb), sources: [wnFinishSource(base, "unavailable", [wnCheck("tmdb", "الحلقة في TMDB", false, "الحلقة غير موجودة في هذا الموسم")])], best: "", fallbacks: [], status: "unavailable", name: "" };
    }
    const r = await wnResolveTarget(env, cfg, t, { customUrl: customUrl || "", fresh: truthy(input.refresh) });
    r.name = t.name || "";
    return r;
  });
  const embeds = (cfg.video_providers || []).filter((p) => p.enabled !== false && p.kind === "embed").map((p) => p.name);
  return okResponse(
    {
      type: kind, tmdb_id: Number(tmdb), season: kind === "series" ? numOf(input.season) : 0, targets: resolved, total, next_offset: nextOffset,
      limitations: embeds.length ? [embeds.join("، ") + ": مشغّل مضمَّن بلا واجهة بحث رسمية — أعلى مستوى ممكن «الصفحة تعمل (reachable)». لا يمكن إثبات التشغيل الفعلي من الخادم، لذلك يتطلب الاستيراد إقراراً صريحاً."] : [],
    },
    "تم فحص " + resolved.length + " عنصر."
  );
}
