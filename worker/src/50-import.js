
// ----------------------------------------------------------------------------- pre-import validation + idempotent import

const WN_STATES = {
  ready: { ar: "جاهز للاستيراد", en: "Ready to Import" },
  missing_video: { ar: "الفيديو مفقود", en: "Missing Video" },
  missing_metadata: { ar: "بيانات ناقصة", en: "Missing Metadata" },
  subtitle_missing: { ar: "الترجمة مفقودة", en: "Subtitle Missing" },
  invalid_subtitle: { ar: "ترجمة غير صالحة", en: "Invalid Subtitle" },
  duplicate: { ar: "محتوى مكرر", en: "Duplicate Content" },
  import_failed: { ar: "فشل الاستيراد", en: "Import Failed" },
  imported: { ar: "تم الاستيراد بنجاح", en: "Successfully Imported" },
};

function wnStateObj(state) {
  const s = WN_STATES[state] || WN_STATES.import_failed;
  return { state, state_label: s.ar, state_label_en: s.en };
}

function wnNormalizePlan(input) {
  const kind = wnKind(input.type);
  const tmdb = wnTmdbId(input.tmdb_id);
  const sec = input.section && typeof input.section === "object" ? input.section : {};
  const opt = input.options && typeof input.options === "object" ? input.options : {};
  const plan = {
    kind,
    tmdb,
    title: cleanString(input.title).slice(0, 200),
    section_id: cleanString(sec.id || input.section_id).slice(0, 80),
    new_section: cleanString(sec.new_name).slice(0, 60),
    new_section_type: SECTION_TYPES.has(lc(sec.new_type)) ? lc(sec.new_type) : kind === "series" ? "series" : "movie",
    categories: Array.isArray(input.categories) ? input.categories.map((c) => cleanString(c).slice(0, 40)).filter(Boolean) : null,
    ack: truthy(input.ack_unverified),
    options: {
      hidden: truthy(opt.hidden),
      quality: cleanString(opt.quality).slice(0, 12),
      notify: opt.notify !== false,
      subtitle_required: truthy(opt.subtitle_required),
      header_only: truthy(opt.header_only),
    },
    movie: null,
    episodes: [],
  };
  if (kind === "movie") {
    const m = input.movie && typeof input.movie === "object" ? input.movie : {};
    plan.movie = {
      video: cleanString(m.video_token || (m.video && m.video.token)),
      subtitle: cleanString(m.subtitle_token || (m.subtitle && m.subtitle.token)),
      fallbacks: (Array.isArray(m.fallback_tokens) ? m.fallback_tokens : []).map(cleanString).filter(Boolean).slice(0, 4),
    };
  } else {
    const seen = new Set();
    for (const s of Array.isArray(input.seasons) ? input.seasons : []) {
      const season = numOf(s && s.season);
      if (season < 1 || season > 200) throw new AdminError("invalid_season", "رقم موسم غير صالح.", 400);
      for (const e of Array.isArray(s.episodes) ? s.episodes : []) {
        const episode = numOf(e && e.episode);
        if (episode < 1 || episode > 9999) throw new AdminError("invalid_episode", "رقم حلقة غير صالح في الموسم " + season + ".", 400);
        const key = season + ":" + episode;
        if (seen.has(key)) throw new AdminError("duplicate_episode_in_plan", "الحلقة " + episode + " مكررة في الموسم " + season + " داخل الطلب.", 400);
        seen.add(key);
        plan.episodes.push({
          season, episode,
          video: cleanString(e.video_token || (e.video && e.video.token)),
          subtitle: cleanString(e.subtitle_token || (e.subtitle && e.subtitle.token)),
          fallbacks: (Array.isArray(e.fallback_tokens) ? e.fallback_tokens : []).map(cleanString).filter(Boolean).slice(0, 4),
        });
      }
    }
    if (plan.episodes.length > WN_MAX_EPISODES_PER_CALL) {
      throw new AdminError("too_many_episodes", "الحد " + WN_MAX_EPISODES_PER_CALL + " حلقة في الطلب الواحد؛ قسّم الاستيراد إلى دفعات.", 400);
    }
    plan.episodes.sort((a, b) => a.season - b.season || a.episode - b.episode);
  }
  return plan;
}

function wnPlanKey(plan) {
  return plan.kind === "movie" ? "movie:" + plan.tmdb : "series:" + plan.tmdb;
}

function wnEpLabel(season, episode) {
  return "S" + wnPad2(season) + "E" + wnPad2(episode);
}

const WN_IMG_CACHE = new Map();

/** true = image reachable, false = definitely broken (404/not an image), null = could not verify. */
async function wnCheckImage(url) {
  const u = cleanString(url);
  if (!u) return { pass: false, detail: "لا يوجد رابط بوستر." };
  const hit = WN_IMG_CACHE.get(u);
  if (hit && hit.exp > Date.now()) return hit.v;
  let v;
  try {
    const p = await wnProbeFetch(u, { method: "GET", headers: { range: "bytes=0-1023", "user-agent": WN_UA } }, 6000);
    const type = cleanString(p.res.headers.get("content-type")).split(";")[0].toLowerCase();
    const st = p.res.status;
    try { await p.res.body?.cancel(); } catch { /* ignore */ }
    if ((st === 200 || st === 206) && type.startsWith("image/")) v = { pass: true, detail: type };
    else if (st === 404 || st === 410 || ((st === 200 || st === 206) && type && !type.startsWith("image/"))) v = { pass: false, detail: "HTTP " + st + " " + type };
    else v = { pass: null, detail: "HTTP " + st };
  } catch (error) {
    v = { pass: null, detail: "تعذر التحقق: " + cleanString(error && error.message).slice(0, 80) };
  }
  if (WN_IMG_CACHE.size > 300) WN_IMG_CACHE.delete(WN_IMG_CACHE.keys().next().value);
  WN_IMG_CACHE.set(u, { exp: Date.now() + 3600000, v });
  return v;
}

async function wnCheckVideoToken(env, token, target) {
  if (!cleanString(token)) return { ok: false, code: "no_video", message: "لم يتم اختيار مصدر فيديو." };
  const v = await wnVerifyToken(env, token);
  if (!v) return { ok: false, code: "bad_token", message: "رمز التحقق من الفيديو غير صالح (لم يصدر من هذا الخادم)." };
  if (v.expired) return { ok: false, code: "expired", message: "انتهت صلاحية فحص المصدر (30 دقيقة). أعد البحث عن الفيديو." };
  const p = v.payload;
  if (p.k !== target.kind || String(p.t) !== String(target.tmdb) || numOf(p.s) !== numOf(target.season) || numOf(p.e) !== numOf(target.episode)) {
    return { ok: false, code: "mismatch", message: "المصدر المُتحقَّق منه لا يخص هذا الموسم/الحلقة — لن يُربط برابط خاطئ." };
  }
  const meta = WN_VERDICTS[p.v];
  if (!meta || !meta.importable) return { ok: false, code: "not_importable", message: (meta && meta.label) || "مصدر غير صالح." };
  return { ok: true, url: p.u, provider: p.p, kind: p.n, verdict: p.v, level: p.l, needs_ack: meta.ack, label: meta.label };
}

async function wnCheckSubtitleToken(env, token, target, lang) {
  if (!cleanString(token)) return { present: false };
  const parked = await wnReadParkedSubtitle(env, token);
  if (!parked) return { present: true, ok: false, message: "ملف الترجمة المحفوظ غير موجود أو انتهت صلاحيته. أعد جلب الترجمة." };
  const info = parked.info;
  if (String(info.tmdb) !== String(target.tmdb) || (info.kind === "series") !== (target.kind !== "movie") || numOf(info.season) !== numOf(target.season) || numOf(info.episode) !== numOf(target.episode)) {
    return { present: true, ok: false, message: "هذه الترجمة تخص عنواناً/حلقة أخرى — لن تُربط بالخطأ." };
  }
  const val = wnValidateSrt(parked.srt, { lang: info.lang, kind: target.kind === "movie" ? "movie" : "episode" });
  if (!val.ok) return { present: true, ok: false, message: val.issues.map((i) => i.message).join(" ") };
  return { present: true, ok: true, parked, lang: info.lang, cues: val.cues, source: info.source, release: info.release || "", warnings: val.warnings };
}

/** Builds the validation report for a plan. Nothing is written. */
async function wnValidate(env, plan) {
  const checks = [];
  const add = (id, label, status, detail) => { checks.push({ id, label, status, detail: detail === undefined ? "" : String(detail) }); };
  const items = [];
  let meta = null;
  try {
    meta = await wnFetchMeta(env, plan.kind, plan.tmdb);
  } catch (error) {
    if (!(error instanceof AdminError) || error.code === "missing_tmdb_key" || error.code === "bad_tmdb_key") throw error;
    add("tmdb", "معرّف TMDB والنوع", "fail", error.message);
  }
  const list = await readMoviesList(env);
  const registry = await readSectionsRegistry(env, list);
  const lib = wnLibraryIndex(list);
  const ctx = { meta, list, registry, lib, plan, subs: new Map(), videos: new Map(), seasonData: new Map(), header: null };

  if (meta) {
    add("tmdb", "معرّف TMDB والنوع", "pass", (plan.kind === "movie" ? "فيلم" : "مسلسل") + " #" + plan.tmdb);
    const title = plan.title || meta.title;
    if (!title) add("metadata", "البيانات الأساسية", "fail", "العنوان مفقود.");
    else if (!meta.poster) add("metadata", "البيانات الأساسية", "fail", "لا يوجد بوستر لهذا العنوان في TMDB.");
    else {
      const warn = [];
      if (!meta.description) warn.push("بلا وصف");
      if (!meta.year) warn.push("بلا سنة");
      add("metadata", "البيانات الأساسية", warn.length ? "warn" : "pass", warn.join("، "));
    }
    const img = await wnCheckImage(meta.poster);
    add("poster_url", "رابط البوستر", img.pass === true ? "pass" : img.pass === false ? "fail" : "warn", img.detail);
    const other = lib.get((plan.kind === "movie" ? "series" : "movie") + ":" + plan.tmdb);
    if (other) add("type_conflict", "اختلاف النوع", "warn", "المعرّف نفسه موجود في المكتبة كنوع آخر («" + other.title + "»).");
  } else {
    add("metadata", "البيانات الأساسية", "fail", "تعذر تحميل بيانات TMDB.");
  }

  // category / section
  if (plan.section_id) {
    const s = registry.sections.find((x) => x.id === plan.section_id);
    add("category", "القسم", s ? (s.enabled === false ? "warn" : "pass") : "fail", s ? s.name + (s.enabled === false ? " (معطّل)" : "") : "القسم المحدد غير موجود.");
  } else if (plan.new_section) {
    const hit = registry.sections.find((x) => normalizeTitleForMatch(x.name) === normalizeTitleForMatch(plan.new_section));
    add("category", "القسم", "pass", hit ? "سيُستخدم القسم الموجود «" + hit.name + "»." : "سيُنشأ قسم جديد «" + plan.new_section + "».");
  } else {
    add("category", "القسم", "pass", "بدون قسم (مسموح).");
  }
  if (plan.categories && (plan.categories.length > 8 || plan.categories.some((c) => /[\u0000-\u001f]/.test(c)))) add("categories", "التصنيفات", "fail", "حد أقصى 8 تصنيفات بلا رموز تحكم.");

  const dupFail = (msg) => add("duplicate", "التكرار", "fail", msg);
  let allEpisodesExist = false;
  if (plan.kind === "movie") {
    const existing = lib.get("movie:" + plan.tmdb);
    const dups = meta ? findDuplicates(list, { type: "movie", tmdb: plan.tmdb, imdb: meta.imdb_id, title: plan.title || meta.title, year: meta.year }).filter((d) => d.reasons.some((r) => r !== "slug")) : [];
    if (existing || dups.length) dupFail("«" + ((existing || dups[0]).title) + "» موجود بالفعل في المكتبة.");
    else add("duplicate", "التكرار", "pass", "لا يوجد تكرار.");
    // video
    const target = { kind: "movie", tmdb: plan.tmdb, season: 0, episode: 0 };
    const v = await wnCheckVideoToken(env, plan.movie.video, target);
    const sub = await wnCheckSubtitleToken(env, plan.movie.subtitle, target);
    items.push(wnItemReport("movie", "الفيلم", v, sub, plan, ctx));
  } else {
    const header = wnFindHeader(list, plan.tmdb);
    ctx.header = header;
    const have = wnLibraryEpisodes(list, header);
    if (!plan.episodes.length && !plan.options.header_only) add("episodes", "الحلقات", "fail", "لم تُحدَّد أي حلقة للاستيراد.");
    // season/episode consistency against TMDB (one call per season, cached)
    const seasons = Array.from(new Set(plan.episodes.map((e) => e.season)));
    for (const s of seasons) {
      try { ctx.seasonData.set(s, await wnSeasonEpisodes(env, plan.tmdb, s)); } catch (error) {
        if (!(error instanceof AdminError) || error.code === "missing_tmdb_key" || error.code === "bad_tmdb_key") throw error;
        add("seasons", "الموسم " + s, "fail", error.message);
      }
    }
    let existCount = 0;
    for (const e of plan.episodes) {
      const sd = ctx.seasonData.get(e.season);
      const epMeta = sd ? sd.episodes.find((x) => x.episode === e.episode) : null;
      e.meta = epMeta || null;
      const label = wnEpLabel(e.season, e.episode);
      const exists = Boolean(have.get(e.season) && have.get(e.season).has(e.episode));
      if (exists) existCount++;
      const v = exists ? { ok: true, skip: true } : await wnCheckVideoToken(env, e.video, { kind: "episode", tmdb: plan.tmdb, season: e.season, episode: e.episode });
      const sub = exists ? { present: false } : await wnCheckSubtitleToken(env, e.subtitle, { kind: "episode", tmdb: plan.tmdb, season: e.season, episode: e.episode });
      const item = wnItemReport(label, label + (epMeta && epMeta.name ? " — " + epMeta.name : ""), v, sub, plan, ctx);
      if (sd && !epMeta) {
        item.checks.unshift({ id: "tmdb_episode", label: "الحلقة في TMDB", status: "fail", detail: "الحلقة غير موجودة في الموسم " + e.season });
        item.state = "missing_metadata";
      } else if (epMeta && !epMeta.aired && !exists) {
        item.checks.unshift({ id: "aired", label: "تاريخ العرض", status: "fail", detail: "لم تُعرض بعد (" + (epMeta.air_date || "بلا تاريخ") + ")" });
        item.state = "missing_video";
      }
      if (exists) { item.state = "duplicate"; item.skipped = true; item.checks.push({ id: "duplicate", label: "التكرار", status: "warn", detail: "موجودة في المكتبة — ستُتخطى." }); }
      items.push(item);
    }
    allEpisodesExist = plan.episodes.length > 0 && existCount === plan.episodes.length;
    if (allEpisodesExist) dupFail("كل الحلقات المحددة موجودة بالفعل.");
    else add("duplicate", "التكرار", existCount ? "warn" : "pass", existCount ? existCount + " حلقة موجودة وستُتخطى." : header ? "المسلسل موجود — ستُضاف الحلقات إليه." : "لا يوجد تكرار.");
  }

  const requiresAck = items.some((i) => i.requires_ack) && !items.every((i) => i.skipped);
  const fails = checks.filter((c) => c.status === "fail");
  const importable = items.filter((i) => !i.skipped);
  let state = "ready";
  const dupChk = checks.find((c) => c.id === "duplicate" && c.status === "fail");
  if (dupChk) state = "duplicate";
  else if (checks.some((c) => c.status === "fail" && ["tmdb", "metadata", "poster_url", "category", "categories", "episodes", "seasons"].includes(c.id))) state = "missing_metadata";
  else if (importable.some((i) => i.state === "missing_metadata")) state = "missing_metadata";
  else if (importable.some((i) => i.state === "missing_video")) state = "missing_video";
  else if (importable.some((i) => i.state === "invalid_subtitle")) state = "invalid_subtitle";
  else if (importable.some((i) => i.state === "subtitle_missing")) state = "subtitle_missing";
  else if (fails.length) state = "missing_metadata";
  const ok = state === "ready";
  return {
    report: Object.assign(wnStateObj(state), {
      ok,
      requires_ack: ok && requiresAck,
      can_import: ok && (!requiresAck || plan.ack),
      checks,
      items,
      warnings: checks.filter((c) => c.status === "warn").map((c) => c.detail).concat(importable.flatMap((i) => i.warnings || [])),
    }),
    ctx,
  };
}

function wnItemReport(id, label, v, sub, plan, ctx) {
  const checks = [];
  let state = "ready";
  const warnings = [];
  let requiresAck = false;
  if (v.skip) {
    return { id, label, state: "ready", checks, skipped: false, video: null, subtitle: null };
  }
  if (!v.ok) {
    checks.push({ id: "video", label: "الفيديو", status: "fail", detail: v.message });
    state = "missing_video";
  } else {
    checks.push({ id: "video", label: "الفيديو", status: v.needs_ack ? "warn" : "pass", detail: v.label + " · " + v.provider });
    if (v.needs_ack) { requiresAck = true; warnings.push(label + ": " + v.label); }
  }
  if (sub.present && !sub.ok) {
    checks.push({ id: "subtitle", label: "الترجمة", status: "fail", detail: sub.message });
    if (state === "ready") state = "invalid_subtitle";
  } else if (sub.present) {
    checks.push({ id: "subtitle", label: "الترجمة", status: "pass", detail: sub.lang + " · " + sub.cues + " سطر · " + sub.source });
    for (const w of sub.warnings || []) warnings.push(label + ": " + w.message);
  } else if (plan.options.subtitle_required) {
    checks.push({ id: "subtitle", label: "الترجمة", status: "fail", detail: "الترجمة مطلوبة ولم تُحدَّد." });
    if (state === "ready") state = "subtitle_missing";
  } else {
    checks.push({ id: "subtitle", label: "الترجمة", status: "skip", detail: "اختيارية — بدون ترجمة." });
  }
  return {
    id, label, state, checks, warnings, requires_ack: requiresAck,
    video: v.ok ? { url: v.url, provider: v.provider, verdict: v.verdict, level: v.level, kind: v.kind } : null,
    subtitle: sub.present && sub.ok ? { lang: sub.lang, cues: sub.cues, source: sub.source, release: sub.release } : null,
  };
}

async function handleWnValidate(request, env) {
  wnRate("validate", 90);
  const plan = wnNormalizePlan(await readRequestData(request));
  const { report } = await wnValidate(env, plan);
  return okResponse({ report }, report.state_label);
}

// ----------------------------------------------------------------------------- bookkeeping (ledger / index)

async function wnLedgerRecords(env) {
  const doc = await wnRead(env, WN_LEDGER_FILE, { records: [] });
  return Array.isArray(doc.records) ? doc.records : [];
}

async function wnLedgerAdd(env, record) {
  await wnMutate(env, WN_LEDGER_FILE, { records: [] }, (doc) => {
    if (!Array.isArray(doc.records)) doc.records = [];
    doc.records.unshift(record);
    if (doc.records.length > WN_LEDGER_MAX) doc.records.length = WN_LEDGER_MAX;
    return { doc };
  });
}

async function wnIndexMarkImported(env, key) {
  await wnMutate(env, WN_INDEX_FILE, { items: {} }, (doc) => {
    if (doc.items && doc.items[key]) doc.items[key].imported = true;
    return { doc };
  });
}

function wnSubKey(slug, season, episode, lang) {
  const s = cleanString(slug);
  if (!season) return s + "/" + s + "." + lang + ".srt";
  return s + "/season-" + wnPad2(season) + "/S" + wnPad2(season) + "E" + wnPad2(episode) + "." + lang + ".srt";
}

async function wnStoreSubtitle(env, key, srt) {
  await env.MOVIES_BUCKET.put(key, srt, {
    httpMetadata: { contentType: "application/x-subrip; charset=utf-8", cacheControl: "public, max-age=300" },
    customMetadata: { source: "whatsnew-v1", at: new Date().toISOString() },
  });
  return r2PublicUrl(key);
}

async function wnEnsureSection(env, plan, ctx) {
  if (plan.section_id) {
    const s = ctx.registry.sections.find((x) => x.id === plan.section_id);
    return { id: s.id, name: s.name, created: false };
  }
  if (!plan.new_section) return { id: "", name: "", created: false };
  const find = (registry) => registry.sections.find((x) => normalizeTitleForMatch(x.name) === normalizeTitleForMatch(plan.new_section));
  const hit = find(ctx.registry);
  if (hit) return { id: hit.id, name: hit.name, created: false, reused: true };
  for (let attempt = 0; attempt < 2; attempt++) {
    const registry = await readSectionsRegistry(env);
    const again = find(registry);
    if (again) return { id: again.id, name: again.name, created: false, reused: true };
    let base = slugifyLatin(plan.new_section);
    if (!base) {
      let h = 0;
      for (const ch of plan.new_section) h = (h * 31 + ch.codePointAt(0)) >>> 0;
      base = "sec-" + h.toString(36);
    }
    const slug = nextFreeSlug(base, (c) => registry.sections.some((s) => lc(s.slug) === c || s.id === c.replace(/-/g, "_")));
    const req = new Request("https://internal.invalid/admin/sections/create", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ name: plan.new_section, type: plan.new_section_type, slug, id: slug.replace(/-/g, "_") }),
    });
    try {
      const res = await handleSectionsCreate(req, env);
      const body = await res.json();
      if (body && body.section) return { id: body.section.id, name: body.section.name, created: true, undo_id: body.undo_id || "" };
    } catch (error) {
      if (!(error instanceof AdminError) || !["slug_in_use", "id_in_use", "write_conflict"].includes(error.code)) throw error;
    }
  }
  throw new AdminError("category_create_failed", "تعذر إنشاء القسم الجديد. أعد المحاولة.", 409);
}

// ----------------------------------------------------------------------------- the import

async function handleWnImport(request, env) {
  wnRate("import", 80);
  const input = await readRequestData(request);
  const plan = wnNormalizePlan(input);
  const requestId = cleanString(input.request_id).slice(0, 80);
  const started = Date.now();

  // 1) a retried request (network retry / double tap) gets the first answer back — nothing is written twice
  if (requestId) {
    const prev = (await wnLedgerRecords(env)).find((r) => r.request_id === requestId);
    if (prev) return okResponse(Object.assign({}, prev.result, { replayed: true }), "تم تنفيذ هذا الطلب سابقاً — أُعيدت نتيجته دون تكرار.");
  }

  // 2) validate again, server-side (never trust the client's own report)
  const { report, ctx } = await wnValidate(env, plan);
  if (!report.ok) throw new AdminError(report.state === "duplicate" ? "duplicate_content" : "validation_failed", "لا يمكن الاستيراد: " + report.state_label + ".", report.state === "duplicate" ? 409 : 422, { report });
  if (report.requires_ack && !plan.ack) {
    throw new AdminError("ack_required", "بعض مصادر الفيديو غير قابلة للإثبات من الخادم (مشغّل مضمَّن/المزوّد يمنع الفحص). أكّد الإقرار ثم أعد الاستيراد.", 409, { report });
  }
  const meta = ctx.meta;
  const title = plan.title || meta.title;
  const category = plan.categories ? plan.categories.join("، ") : meta.category;

  // 3) section (existing, or created once)
  const section = await wnEnsureSection(env, plan, ctx);

  // 4) subtitles + items, committed to movies.json in ONE compare-and-swap write
  const movieTarget = { kind: "movie", tmdb: plan.tmdb, season: 0, episode: 0 };
  let moviePack = null;
  const epPacks = new Map();
  if (plan.kind === "movie") {
    moviePack = { video: await wnCheckVideoToken(env, plan.movie.video, movieTarget), sub: await wnCheckSubtitleToken(env, plan.movie.subtitle, movieTarget) };
  } else {
    for (const e of plan.episodes) {
      const t = { kind: "episode", tmdb: plan.tmdb, season: e.season, episode: e.episode };
      epPacks.set(e.season + ":" + e.episode, { video: await wnCheckVideoToken(env, e.video, t), sub: await wnCheckSubtitleToken(env, e.subtitle, t) });
    }
  }

  let outcome = null;
  const usedTokens = [];
  const op = plan.options.notify ? (plan.kind === "movie" ? "create" : "season-commit") : "whatsnew-import";
  const res = await mutateMovies(env, async (list, holder, mctx) => {
    outcome = { created: [], skipped: [], header_created: false, slug: "", subtitles: 0 };
    usedTokens.length = 0;
    const nowMs = Date.now();
    if (plan.kind === "movie") {
      const dups = findDuplicates(list, { type: "movie", tmdb: plan.tmdb, imdb: meta.imdb_id, title, year: meta.year }).filter((d) => d.reasons.some((r) => r !== "slug"));
      if (dups.length) throw new AdminError("duplicate_content", "«" + title + "» موجود بالفعل في المكتبة.", 409, { existing: dups[0], matches: dups });
      const slug = suggestSlug(list, meta.original_title, title, meta.year, plan.tmdb, "movie");
      outcome.slug = slug;
      let subUrl = "";
      if (moviePack.sub.present && moviePack.sub.ok) {
        subUrl = await wnStoreSubtitle(env, wnSubKey(slug, 0, 0, moviePack.sub.lang), moviePack.sub.parked.srt);
        outcome.subtitles++;
        usedTokens.push(moviePack.sub.parked.token);
      }
      const item = {
        id: makeContentId({}, list),
        type: "movie",
        created_at: new Date(nowMs).toISOString(),
        created_at_ms: nowMs,
        title,
        original_title: meta.original_title,
        description: meta.description,
        year: meta.year,
        category,
        poster: meta.poster,
        backdrop: meta.backdrop,
        slug,
        section_id: section.id,
        section_name: section.name,
        imdb_id: meta.imdb_id,
        duration: meta.duration,
        tmdb: String(plan.tmdb),
        imdb: meta.rating_text,
        video: moviePack.video.url,
        link: moviePack.video.url,
        subtitle: subUrl,
        quality: plan.options.quality,
        series_id: "",
        season: "",
        episode: "",
      };
      for (const k of ["original_title", "imdb_id", "duration", "imdb", "description", "backdrop"]) if (!cleanString(item[k])) item[k] = "";
      if (plan.options.hidden) item.hidden = true;
      list.unshift(item);
      mctx.tracker.add(item);
      sortNewestFirst(list);
      outcome.created.push({ id: item.id, type: "movie", season: 0, episode: 0, title });
      return { expectDelta: 1, label: "استيراد فيلم (What's New): " + title };
    }

    // series: header once, then only the chosen episodes
    let header = wnFindHeader(list, plan.tmdb);
    if (!header) {
      const seriesId = "series_" + plan.tmdb;
      if (findById(list, seriesId)) throw new AdminError("id_in_use", "المعرّف " + seriesId + " مستخدم بعنصر ليس مسلسلاً.", 409);
      const slug = suggestSlug(list, meta.original_title, title, meta.year, plan.tmdb, "series");
      header = {
        id: seriesId, type: "series", created_at: new Date(nowMs).toISOString(), created_at_ms: nowMs,
        section_id: section.id, section_name: section.name, series_id: seriesId, season: "", episode: "", title,
        poster: meta.poster, series_poster: meta.poster, season_poster: "", backdrop: meta.backdrop, video: "", link: "",
        year: meta.year, category, imdb: meta.rating_text, description: meta.description, duration: "", slug, tmdb: String(plan.tmdb),
      };
      if (meta.imdb_id) header.imdb_id = meta.imdb_id;
      if (meta.original_title) header.original_title = meta.original_title;
      if (plan.options.quality) header.quality = plan.options.quality;
      if (plan.options.hidden) header.hidden = true;
      list.unshift(header);
      mctx.tracker.add(header);
      outcome.header_created = true;
      outcome.created.push({ id: header.id, type: "series", season: 0, episode: 0, title });
    }
    const sid = seriesKeyOf(header);
    const numeric = sid.match(/^series_(\d+)$/);
    const idBase = numeric ? numeric[1] : sid;
    const slug = slugForSeries(list, header, "") || slugifyLatin(meta.original_title || title) || "series-" + plan.tmdb;
    outcome.slug = slug;
    for (const e of plan.episodes) {
      const pack = epPacks.get(e.season + ":" + e.episode);
      const clash = episodeCollision(list, sid, e.season, e.episode, "");
      if (clash) { outcome.skipped.push({ season: e.season, episode: e.episode, reason: "exists", id: cleanString(clash.id) }); continue; }
      const inherit = episodeInheritance(list, header, e.season);
      const seasonMeta = (meta.seasons || []).find((s) => s.season === e.season);
      const effPoster = (seasonMeta && seasonMeta.poster) || inherit.season_poster || cleanString(header.poster);
      if (effPoster) { inherit.season_poster = effPoster; inherit.poster = effPoster; }
      const em = e.meta || {};
      const item = {
        id: idBase + "_s" + e.season + "e" + e.episode,
        type: "episode",
        created_at: new Date(nowMs).toISOString(),
        created_at_ms: nowMs,
        series_id: sid,
        season: String(e.season),
        episode: String(e.episode),
        title: cleanString(em.name) || "الحلقة " + e.episode,
        video: pack.video.url,
        link: pack.video.url,
        description: cleanString(em.overview).slice(0, 1500),
        duration: cleanString(em.duration),
      };
      if (pack.sub.present && pack.sub.ok) {
        item.subtitle = await wnStoreSubtitle(env, wnSubKey(slug, e.season, e.episode, pack.sub.lang), pack.sub.parked.srt);
        outcome.subtitles++;
        usedTokens.push(pack.sub.parked.token);
      }
      for (const [k, v] of Object.entries(inherit)) if (v !== undefined && cleanString(v) !== "") item[k] = cleanString(v);
      if (cleanString(em.still)) { item.backdrop = cleanString(em.still); item.thumb = cleanString(em.still); }
      if (cleanString(header.imdb_id)) item.imdb_id = cleanString(header.imdb_id);
      if (yearOfText(em.air_date)) item.year = yearOfText(em.air_date);
      if (findById(list, item.id)) item.id = item.id + "_" + randomId();
      list.unshift(item);
      mctx.tracker.add(item);
      outcome.created.push({ id: item.id, type: "episode", season: e.season, episode: e.episode, title: item.title });
    }
    if (!outcome.created.length && !outcome.header_created) {
      throw new AdminError("duplicate_content", "كل الحلقات المحددة موجودة بالفعل.", 409);
    }
    sortNewestFirst(list);
    return {
      expectDelta: outcome.created.length,
      label: "استيراد مسلسل (What's New): " + title + " — " + outcome.created.filter((c) => c.type === "episode").length + " حلقة",
    };
  }, { op, backup: plan.kind === "series" });

  // 5) bookkeeping — best effort: the content is already saved, a ledger hiccup must not turn that into a failure
  for (const t of usedTokens) await wnDropParkedSubtitle(env, t);
  const videos = plan.kind === "movie"
    ? [{ id: "movie", provider: moviePack.video.provider, verdict: moviePack.video.verdict, level: moviePack.video.level }]
    : plan.episodes.filter((e) => outcome.created.some((c) => c.season === e.season && c.episode === e.episode)).map((e) => {
      const p = epPacks.get(e.season + ":" + e.episode).video;
      return { id: wnEpLabel(e.season, e.episode), provider: p.provider, verdict: p.verdict, level: p.level };
    });
  const result = Object.assign(wnStateObj("imported"), {
    type: plan.kind, tmdb_id: Number(plan.tmdb), title, request_id: requestId, created: outcome.created, skipped: outcome.skipped,
    header_created: outcome.header_created, slug: outcome.slug, subtitles_attached: outcome.subtitles,
    section, undo_id: res.undo_id, backup: res.backup, videos, warnings: report.warnings, ms: Date.now() - started, at: new Date().toISOString(),
  });
  let bookkeeping = "";
  try {
    const fallbacks = {};
    const keep = async (label, tokens, target) => {
      const ok = [];
      for (const tk of tokens) {
        const v = await wnCheckVideoToken(env, tk, target);
        if (v.ok) ok.push({ provider: v.provider, url: v.url, verdict: v.verdict });
      }
      if (ok.length) fallbacks[label] = ok;
    };
    if (plan.kind === "movie") await keep("movie", plan.movie.fallbacks, movieTarget);
    else for (const e of plan.episodes) {
      if (outcome.created.some((c) => c.season === e.season && c.episode === e.episode)) {
        await keep(wnEpLabel(e.season, e.episode), e.fallbacks, { kind: "episode", tmdb: plan.tmdb, season: e.season, episode: e.episode });
      }
    }
    await wnLedgerAdd(env, {
      id: "imp_" + String(Date.now()).padStart(13, "0") + "_" + randomId(), request_id: requestId, key: wnPlanKey(plan), type: plan.kind, tmdb_id: Number(plan.tmdb),
      title, poster: meta.poster, at: result.at, undo_id: res.undo_id, ack_unverified: plan.ack, videos, fallbacks, result: Object.assign({}, result, { report: undefined }),
    });
    await wnIndexMarkImported(env, wnPlanKey(plan));
  } catch (error) {
    bookkeeping = "تم الحفظ لكن تعذر تسجيله في سجل الاستيراد: " + cleanString(error && error.message).slice(0, 100);
  }
  if (bookkeeping) result.bookkeeping_warning = bookkeeping;
  return okResponse(result, plan.kind === "movie" ? "تمت إضافة الفيلم إلى Cigram." : "تمت إضافة " + outcome.created.filter((c) => c.type === "episode").length + " حلقة إلى Cigram.");
}
