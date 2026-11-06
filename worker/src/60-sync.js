
// ----------------------------------------------------------------------------- synchronisation (manual + cron) + history

function wnEntryFromRow(row, kind, genreMap, feedKey, nowIso) {
  const tv = kind === "series";
  const date = cleanString(tv ? row.first_air_date : row.release_date);
  return {
    kind,
    tmdb_id: Number(row.id) || 0,
    title: cleanString(tv ? row.name : row.title) || cleanString(tv ? row.original_name : row.original_title),
    original_title: cleanString(tv ? row.original_name : row.original_title),
    year: yearOfText(date),
    release_date: date,
    released: Boolean(date) && date <= wnToday(),
    poster: tmdbImage(row.poster_path, "w342"),
    backdrop: tmdbImage(row.backdrop_path, "w780"),
    overview: cleanString(row.overview).slice(0, 240),
    rating: Number(Number(row.vote_average || 0).toFixed(1)),
    vote_count: Number(row.vote_count) || 0,
    popularity: Math.round(Number(row.popularity) || 0),
    genres: (Array.isArray(row.genre_ids) ? row.genre_ids : []).map((g) => genreMap.get(Number(g))).filter(Boolean),
    language: cleanString(row.original_language),
    countries: Array.isArray(row.origin_country) ? row.origin_country : [],
    feeds: [feedKey],
    first_seen_at: nowIso,
    last_seen_at: nowIso,
    imported: false,
    enrich: {},
  };
}

async function wnRunFeeds(env, cfg) {
  const nowIso = new Date().toISOString();
  const [movieGenres, tvGenres] = await Promise.all([wnGenres(env, "movie").catch(() => new Map()), wnGenres(env, "tv").catch(() => new Map())]);
  const collected = new Map();
  const errors = [];
  const report = [];
  for (const feed of cfg.feeds) {
    const [scope, name] = feed.split(":");
    const kinds = scope === "all" ? ["movie", "series"] : [scope === "tv" ? "series" : "movie"];
    for (const kind of kinds) {
      for (let page = 1; page <= cfg.max_pages; page++) {
        try {
          const live = await wnLiveRows(env, cfg, kind, name, wnFilters({ page, refresh: true }));
          for (const r of live.rows) {
            const e = wnEntryFromRow(r.row, kind, kind === "series" ? tvGenres : movieGenres, feed, nowIso);
            if (!e.tmdb_id) continue;
            const key = kind + ":" + e.tmdb_id;
            const prev = collected.get(key);
            if (prev) { if (!prev.feeds.includes(feed)) prev.feeds.push(feed); } else collected.set(key, e);
          }
          report.push({ feed, kind, page, ok: true, count: live.rows.length });
          if (page >= live.total_pages) break;
        } catch (error) {
          const message = cleanString(error && error.message).slice(0, 160);
          errors.push({ op: "feed:" + feed + ":" + kind, message });
          report.push({ feed, kind, page, ok: false, error: message });
          break;
        }
      }
    }
  }
  return { collected, errors, report };
}

async function wnMergeIndex(env, collected, lib) {
  const nowIso = new Date().toISOString();
  let newKeys = [];
  await wnMutate(env, WN_INDEX_FILE, { items: {} }, (doc) => {
    newKeys = [];
    if (!doc.items || typeof doc.items !== "object") doc.items = {};
    for (const [key, e] of collected) {
      const old = doc.items[key];
      if (old) {
        const feeds = Array.from(new Set((old.feeds || []).concat(e.feeds))).slice(0, 8);
        doc.items[key] = Object.assign({}, old, e, { feeds, first_seen_at: old.first_seen_at, enrich: old.enrich || {}, last_seen_at: nowIso });
      } else {
        doc.items[key] = e;
        newKeys.push(key);
      }
    }
    for (const [key, entry] of Object.entries(doc.items)) entry.imported = lib.has(key);
    const keys = Object.keys(doc.items);
    if (keys.length > WN_INDEX_MAX) {
      keys.sort((a, b) => Date.parse(doc.items[b].last_seen_at) - Date.parse(doc.items[a].last_seen_at));
      for (const k of keys.slice(WN_INDEX_MAX)) delete doc.items[k];
    }
    doc.updated_at = nowIso;
    return { doc };
  });
  return newKeys;
}

async function wnEnrichOne(env, cfg, entry, lib, list) {
  const key = entry.kind + ":" + entry.tmdb_id;
  const tmdb = String(entry.tmdb_id);
  const imported = lib.has(key);
  const nowIso = new Date().toISOString();
  const meta = await wnFetchMeta(env, entry.kind, tmdb);
  const enrich = { checked_at: nowIso };
  let target = { kind: "movie", tmdb, season: 0, episode: 0, released: meta.released };
  if (entry.kind === "series") {
    const le = meta.latest_episode;
    enrich.seasons = meta.season_count;
    enrich.episodes = meta.episode_count;
    enrich.latest_episode = le;
    enrich.new_season = meta.new_season;
    if (imported) {
      const have = wnLibraryEpisodes(list, wnFindHeader(list, tmdb));
      let pending = 0;
      if (le) {
        const mine = Array.from(have.get(le.season) || []).filter((n) => n <= le.episode).length;
        pending = Math.max(0, le.episode - mine);
      }
      enrich.episodes_pending = pending;
    }
    target = le ? { kind: "episode", tmdb, season: le.season, episode: le.episode, released: true } : { kind: "episode", tmdb, season: 1, episode: 1, released: false };
  }
  const out = { enrich, video: null, subtitle: null, error: "" };
  if (!imported) {
    const r = await wnResolveTarget(env, cfg, target);
    const best = r.sources.find((s) => s.importable) || r.sources[0] || null;
    enrich.video = { verdict: r.status, provider: best ? best.provider : "", url: best && best.importable ? best.url : "", season: target.season, episode: target.episode, checked_at: nowIso };
    out.video = r.status;
    const keys = subsKeys(env);
    if (!keys.subdl && !keys.subsource && !keys.opensubtitles) {
      enrich.subtitle = { status: "unconfigured", checked_at: nowIso };
    } else if (target.released === false) {
      enrich.subtitle = { status: "ok", ar: 0, en: 0, checked_at: nowIso, note: "not_released" };
    } else {
      try {
        const t = wnSubTarget(meta, target.season, target.episode);
        const ar = await wnSubsSearch(env, t, "ar");
        let en = 0;
        if (!ar.items.length && cfg.subtitle_languages.includes("en")) en = (await wnSubsSearch(env, t, "en")).items.length;
        enrich.subtitle = { status: "ok", ar: ar.items.length, en, checked_at: nowIso };
      } catch (error) {
        enrich.subtitle = { status: "error", error: cleanString(error && error.message).slice(0, 120), checked_at: nowIso };
      }
    }
    out.subtitle = enrich.subtitle;
  }
  return Object.assign(out, { key });
}

async function wnEnrichBatch(env, cfg, limit) {
  const [list, idx] = [await readMoviesList(env), await wnRead(env, WN_INDEX_FILE, { items: {} })];
  const lib = wnLibraryIndex(list);
  const staleMs = 12 * 3600 * 1000;
  const pool = Object.values(idx.items || {}).filter((e) => {
    const imported = lib.has(e.kind + ":" + e.tmdb_id);
    const checked = e.enrich && e.enrich.checked_at ? Date.parse(e.enrich.checked_at) : 0;
    if (imported && e.kind !== "series") return false;
    return !checked || Date.now() - checked > staleMs;
  });
  pool.sort((a, b) => {
    const ua = a.enrich && a.enrich.checked_at ? 1 : 0;
    const ub = b.enrich && b.enrich.checked_at ? 1 : 0;
    if (ua !== ub) return ua - ub;
    return Date.parse(b.first_seen_at) - Date.parse(a.first_seen_at);
  });
  const batch = pool.slice(0, Math.max(0, limit));
  const counters = { enriched: 0, videos_resolved: 0, videos_unverified: 0, videos_missing: 0, subtitles_available: 0, failed: 0 };
  const errors = [];
  const results = await wnMapLimit(batch, 2, async (entry) => {
    try {
      return await wnEnrichOne(env, cfg, entry, lib, list);
    } catch (error) {
      errors.push({ op: "enrich:" + entry.kind + ":" + entry.tmdb_id, message: cleanString(error && error.message).slice(0, 160) });
      counters.failed++;
      return null;
    }
  });
  const done = results.filter(Boolean);
  for (const r of done) {
    counters.enriched++;
    if (r.video === "playable" || r.video === "embed_reachable") counters.videos_resolved++;
    else if (r.video === "unverified") counters.videos_unverified++;
    else if (r.video && r.video !== "not_released") counters.videos_missing++;
    if (r.subtitle && (r.subtitle.ar > 0 || r.subtitle.en > 0)) counters.subtitles_available++;
  }
  if (done.length) {
    await wnMutate(env, WN_INDEX_FILE, { items: {} }, (doc) => {
      for (const r of done) if (doc.items && doc.items[r.key]) doc.items[r.key].enrich = Object.assign({}, doc.items[r.key].enrich || {}, r.enrich);
      return { doc };
    });
  }
  return Object.assign(counters, { remaining: Math.max(0, pool.length - batch.length), errors });
}

async function wnHistorySave(env, run) {
  await wnMutate(env, WN_HISTORY_FILE, { runs: [] }, (doc) => {
    if (!Array.isArray(doc.runs)) doc.runs = [];
    const i = doc.runs.findIndex((r) => r.id === run.id);
    if (i >= 0) doc.runs[i] = run; else doc.runs.unshift(run);
    if (doc.runs.length > WN_HISTORY_MAX) doc.runs.length = WN_HISTORY_MAX;
    return { doc };
  });
}

function wnFoldCounters(run, c) {
  for (const k of ["enriched", "videos_resolved", "videos_unverified", "videos_missing", "subtitles_available", "failed"]) run[k] = (run[k] || 0) + (c[k] || 0);
  for (const e of c.errors || []) if (run.errors.length < 30) run.errors.push(e);
}

async function wnSync(env, trigger, enrichLimit) {
  const started = Date.now();
  const at = new Date().toISOString();
  let cfg = null;
  await wnMutate(env, WN_CONFIG_FILE, null, (doc) => {
    const c = wnMergeConfig(doc);
    if (c.lease_until && c.lease_until > Date.now()) throw new AdminError("sync_running", "هناك مزامنة جارية الآن. انتظر انتهاءها ثم أعد المحاولة.", 409);
    c.lease_until = Date.now() + 4 * 60000;
    c.last_started_at = at;
    cfg = c;
    return { doc: c };
  });
  const run = {
    id: "run_" + String(Date.now()).padStart(13, "0") + "_" + randomId(), at, trigger, status: "running", feeds: [], discovered: 0, new_items: 0, new_movies: 0, new_series: 0,
    recent_episodes: 0, enriched: 0, videos_resolved: 0, videos_unverified: 0, videos_missing: 0, subtitles_available: 0, failed: 0, errors: [], ms: 0,
  };
  let success = false;
  try {
    try { tmdbCredentials(env); } catch { throw new AdminError("missing_tmdb_key", "لم يتم إعداد TMDB_API_KEY أو TMDB_READ_TOKEN في إعدادات الـ Worker.", 503); }
    const list = await readMoviesList(env);
    const lib = wnLibraryIndex(list);
    const { collected, errors, report } = await wnRunFeeds(env, cfg);
    run.feeds = report;
    run.discovered = collected.size;
    run.failed += errors.length;
    for (const e of errors) run.errors.push(e);
    const newKeys = await wnMergeIndex(env, collected, lib);
    run.new_items = newKeys.length;
    run.new_movies = newKeys.filter((k) => k.startsWith("movie:")).length;
    run.new_series = newKeys.filter((k) => k.startsWith("series:")).length;
    run.recent_episodes = Array.from(collected.values()).filter((e) => e.feeds.includes("tv:recent_episodes")).length;
    const limit = Math.min(20, Math.max(0, numOf(enrichLimit)));
    if (limit > 0 && collected.size) wnFoldCounters(run, await wnEnrichBatch(env, cfg, limit));
    await wnCleanParkedSubtitles(env);
    success = collected.size > 0 || errors.length === 0;
    run.status = errors.length && !collected.size ? "failed" : errors.length || run.errors.length ? "partial" : "ok";
  } catch (error) {
    run.status = "failed";
    run.failed++;
    run.errors.push({ op: "sync", message: cleanString(error && error.message).slice(0, 200), code: error && error.code ? error.code : "" });
    if (error instanceof AdminError && error.code === "missing_tmdb_key") {
      run.ms = Date.now() - started;
      await wnHistorySave(env, run).catch(() => {});
      await wnMutate(env, WN_CONFIG_FILE, null, (doc) => { const c = wnMergeConfig(doc); c.lease_until = 0; c.last_sync_at = at; return { doc: c }; }).catch(() => {});
      throw error;
    }
  }
  run.ms = Date.now() - started;
  await wnHistorySave(env, run).catch(() => {});
  await wnMutate(env, WN_CONFIG_FILE, null, (doc) => {
    const c = wnMergeConfig(doc);
    c.lease_until = 0;
    c.last_sync_at = at;
    if (success) c.last_success_at = at;
    return { doc: c };
  }).catch(() => {});
  return run;
}

/** Called by the Worker's every-minute cron. Cheap when auto-sync is off (one small R2 read). */
async function wnCronTick(env) {
  const cfg = await wnConfig(env);
  if (!cfg.auto_sync) return;
  if (Date.now() - (Date.parse(cfg.last_started_at) || 0) < cfg.interval_minutes * 60000) return;
  if (cfg.lease_until && cfg.lease_until > Date.now()) return;
  try { tmdbCredentials(env); } catch { return; }
  await wnSync(env, "cron", cfg.enrich_limit);
}

async function handleWnSync(request, env) {
  wnRate("sync", 10, 120000);
  const input = await readRequestData(request);
  const run = await wnSync(env, "manual", input.enrich === undefined ? 4 : numOf(input.enrich));
  const [cfg, idx, list] = await Promise.all([wnConfig(env), wnRead(env, WN_INDEX_FILE, { items: {} }), readMoviesList(env)]);
  return okResponse({ run, summary: wnSummaryFrom(idx, wnLibraryIndex(list), cfg) }, run.status === "failed" ? "فشلت المزامنة." : "اكتملت المزامنة.");
}

async function handleWnEnrich(request, env) {
  wnRate("enrich", 40, 120000);
  const input = await readRequestData(request);
  const cfg = await wnConfig(env);
  const limit = Math.min(8, Math.max(1, numOf(input.limit) || 4));
  const counters = await wnEnrichBatch(env, cfg, limit);
  const runId = cleanString(input.run_id);
  if (runId) {
    await wnMutate(env, WN_HISTORY_FILE, { runs: [] }, (doc) => {
      const run = (doc.runs || []).find((r) => r.id === runId);
      if (run) wnFoldCounters(run, counters);
      return { doc };
    });
  }
  return okResponse(counters, counters.enriched ? "تم فحص " + counters.enriched + " عنصر." : "لا يوجد ما يُفحص الآن.");
}

async function handleWnHistory(request, env, url) {
  const limit = Math.min(100, Math.max(1, numOf(url.searchParams.get("limit")) || 30));
  const [doc, ledger, cfg] = await Promise.all([wnRead(env, WN_HISTORY_FILE, { runs: [] }), wnLedgerRecords(env), wnConfig(env)]);
  const runs = (Array.isArray(doc.runs) ? doc.runs : []).slice(0, limit);
  const all = Array.isArray(doc.runs) ? doc.runs : [];
  const out = runs.map((r) => {
    const i = all.findIndex((x) => x.id === r.id);
    const newer = i > 0 ? Date.parse(all[i - 1].at) : Infinity;
    const from = Date.parse(r.at);
    const imported = ledger.filter((l) => Date.parse(l.at) >= from && Date.parse(l.at) < newer).reduce((n, l) => n + ((l.result && l.result.created) ? l.result.created.length : 0), 0);
    return Object.assign({}, r, { imported });
  });
  return okResponse({
    runs: out,
    total_imports: ledger.length,
    recent_imports: ledger.slice(0, 20).map((l) => ({ id: l.id, at: l.at, title: l.title, type: l.type, tmdb_id: l.tmdb_id, created: l.result && l.result.created ? l.result.created.length : 0, ack_unverified: Boolean(l.ack_unverified), videos: l.videos || [] })),
    last_success_at: cfg.last_success_at || "",
    last_sync_at: cfg.last_sync_at || "",
    auto_sync: cfg.auto_sync,
    interval_minutes: cfg.interval_minutes,
  });
}

// ----------------------------------------------------------------------------- route table

const WN_ROUTES = (() => {
  const A = adminRoute;
  return {
    "GET /admin/discover/summary": A(handleWnSummary),
    "GET /admin/discover/config": A(handleWnConfigGet),
    "POST /admin/discover/config": A(handleWnConfigSave),
    "POST /admin/discover/feed": A(handleWnFeed),
    "GET /admin/discover/genres": A(handleWnGenres),
    "POST /admin/discover/details": A(handleWnDetails),
    "POST /admin/discover/season": A(handleWnSeason),
    "POST /admin/discover/video": A(handleWnVideo),
    "POST /admin/discover/subtitles": A(handleWnSubtitles),
    "POST /admin/discover/subtitles/fetch": A(handleWnSubtitlesFetch),
    "POST /admin/discover/subtitles/validate": A(handleWnSubtitlesValidate),
    "POST /admin/discover/validate": A(handleWnValidate),
    "POST /admin/discover/import": A(handleWnImport),
    "POST /admin/discover/sync": A(handleWnSync),
    "POST /admin/discover/enrich": A(handleWnEnrich),
    "GET /admin/discover/history": A(handleWnHistory),
  };
})();
