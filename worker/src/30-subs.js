
// ----------------------------------------------------------------------------- subtitles (search, download, validate)
// Arabic goes through the Worker's existing, field-tested pipeline (SubDL -> SubSource -> OpenSubtitles). Other
// languages use a small generic search (SubDL + OpenSubtitles). A subtitle exists for the import only after it was
// downloaded, decoded, cleaned and validated; it is then parked in the admin bucket behind an unguessable token.

function wnSubLang(value, allowAuto) {
  const l = lc(value);
  if (allowAuto && (l === "auto" || l === "")) return "auto";
  if (!/^[a-z]{2}$/.test(l)) throw new AdminError("invalid_language", "رمز اللغة غير صالح (حرفان مثل ar أو en).", 400);
  return l;
}

function wnSubTarget(meta, season, episode) {
  return {
    type: meta.kind === "series" ? "tv" : "movie",
    title: meta.title,
    original: meta.original_title,
    year: meta.year,
    imdb_id: meta.imdb_id,
    tmdb_id: String(meta.tmdb_id),
    season: numOf(season),
    episode: numOf(episode),
    release: "",
    item: null,
    header: null,
    slug: "",
  };
}

function wnSubCandidate(pick) {
  const source = lc(pick && pick.source);
  const id = cleanString(pick && pick.id);
  if (!["subdl", "subsource", "opensubtitles"].includes(source)) throw new AdminError("invalid_pick", "مصدر الترجمة غير معروف.", 400);
  if (!/^[A-Za-z0-9_./:%-]{1,220}$/.test(id) || id.includes("..")) throw new AdminError("invalid_pick", "معرّف الترجمة غير صالح.", 400);
  return {
    source,
    id,
    name: cleanString(pick.name).slice(0, 160),
    release: cleanString(pick.release).slice(0, 160),
    season: numOf(pick.season),
    episode: numOf(pick.episode),
    full_season: truthy(pick.full_season),
    dl:
      source === "opensubtitles" ? "opensubtitles:" + id
      : source === "subdl" ? "https://dl.subdl.com" + (id.startsWith("/") ? "" : "/") + id
      : "https://api.subsource.net/api/v1/subtitles/" + encodeURIComponent(id) + "/download",
  };
}

function wnLangMatches(value, lang) {
  const s = lc(value);
  return s === lang || s.startsWith(lang + "-") || s.startsWith(lang + "_") || (lang === "en" && s.startsWith("english")) || (lang === "fr" && s.startsWith("french")) || (lang === "tr" && s.startsWith("turk"));
}

/** Generic (non-Arabic) search: SubDL + OpenSubtitles. */
async function wnSubsSearchLang(env, t, lang) {
  const keys = subsKeys(env);
  if (!keys.subdl && !keys.opensubtitles) {
    throw new AdminError("subs_no_keys", "لم يُضبط أي مصدر ترجمة. أضف SUBDL_API_KEY أو OPENSUBTITLES_API_KEY في Cloudflare ← Settings ← Variables and Secrets.", 400);
  }
  const items = [];
  const status = {};
  if (keys.subdl) {
    try {
      const u = new URL("https://api.subdl.com/api/v1/subtitles");
      const q = { api_key: keys.subdl, languages: lang.toUpperCase(), subs_per_page: "30", releases: "1", type: t.type };
      if (t.imdb_id) q.imdb_id = t.imdb_id; else if (t.tmdb_id) q.tmdb_id = t.tmdb_id; else q.film_name = t.original || t.title;
      if (t.type === "tv" && t.season > 0) {
        q.season_number = String(t.season);
        if (t.episode) q.episode_number = String(t.episode); else q.full_season = "1";
      }
      for (const [k, v] of Object.entries(q)) u.searchParams.set(k, v);
      const res = await subsFetch(u.toString(), { headers: { accept: "application/json", "user-agent": SUBS_UA } });
      const data = await res.json().catch(() => null);
      if (!res.ok || !data || data.status === false) status.subdl = "error: " + ((data && (data.error || data.message)) || "SubDL " + res.status);
      else {
        for (const s of Array.isArray(data.subtitles) ? data.subtitles : []) {
          if (!s.url || !wnLangMatches(s.language || s.lang, lang)) continue;
          items.push({
            source: "subdl", id: cleanString(s.url), name: cleanString(s.name || s.release_name), release: cleanString(s.release_name || s.name),
            author: cleanString(s.author), downloads: 0, rating: 0, hi: Boolean(s.hi), season: numOf(s.season), episode: numOf(s.episode),
            full_season: Boolean(s.full_season) || (t.type === "tv" && !numOf(s.episode)),
            dl: "https://dl.subdl.com" + (String(s.url).startsWith("/") ? "" : "/") + s.url,
          });
        }
        status.subdl = items.length + " " + lang;
      }
    } catch (e) {
      status.subdl = "error: تعذر الاتصال بـ SubDL";
    }
  } else status.subdl = "no_key";
  if (keys.opensubtitles && !items.length) {
    const r = await (async () => {
      try {
        const u = new URL("https://api.opensubtitles.com/api/v1/subtitles");
        u.searchParams.set("languages", lang);
        u.searchParams.set("order_by", "download_count");
        const num = (v) => cleanString(v).replace(/^tt/i, "");
        if (t.type === "tv") {
          if (t.imdb_id) u.searchParams.set("parent_imdb_id", num(t.imdb_id)); else if (t.tmdb_id) u.searchParams.set("parent_tmdb_id", t.tmdb_id); else u.searchParams.set("query", t.original || t.title);
          if (t.season > 0) u.searchParams.set("season_number", String(t.season));
          if (t.episode > 0) u.searchParams.set("episode_number", String(t.episode));
          u.searchParams.set("type", "episode");
        } else {
          if (t.imdb_id) u.searchParams.set("imdb_id", num(t.imdb_id)); else if (t.tmdb_id) u.searchParams.set("tmdb_id", t.tmdb_id); else u.searchParams.set("query", t.original || t.title);
          u.searchParams.set("type", "movie");
        }
        const res = await subsFetch(u.toString(), { headers: { accept: "application/json", "api-key": keys.opensubtitles, "user-agent": SUBS_UA } });
        const data = await res.json().catch(() => null);
        if (!res.ok || !data) return { error: "OpenSubtitles " + res.status, rows: [] };
        const rows = [];
        for (const row of Array.isArray(data.data) ? data.data : []) {
          const a = row.attributes || {};
          const file = Array.isArray(a.files) && a.files[0];
          if (!file || !file.file_id || (a.language && !wnLangMatches(a.language, lang))) continue;
          const fd = a.feature_details || {};
          rows.push({
            source: "opensubtitles", id: String(file.file_id), name: cleanString(file.file_name || a.release), release: cleanString(a.release || file.file_name),
            author: cleanString(a.uploader && a.uploader.name), downloads: numOf(a.download_count), rating: Number(a.ratings) || 0, hi: Boolean(a.hearing_impaired),
            season: numOf(fd.season_number), episode: numOf(fd.episode_number), full_season: false, dl: "opensubtitles:" + file.file_id, trusted: Boolean(a.from_trusted),
          });
        }
        return { rows };
      } catch {
        return { error: "تعذر الاتصال بـ OpenSubtitles", rows: [] };
      }
    })();
    status.opensubtitles = r.error ? "error: " + r.error : r.rows.length + " " + lang;
    items.push(...r.rows);
  } else status.opensubtitles = keys.opensubtitles ? "not_needed" : "no_key";
  for (const c of items) c.score = subsScore(c, t);
  items.sort((a, b) => b.score - a.score);
  return { items, status };
}

async function wnSubsSearch(env, t, lang) {
  const found = lang === "ar" ? await subsSearchAll(env, t, { all: false }) : await wnSubsSearchLang(env, t, lang);
  return { items: found.items.map((c) => Object.assign(subsPublic(c), { language: lang })), status: found.status };
}

function wnLangShare(text, lang) {
  const letters = (text.match(/\p{L}/gu) || []).length;
  if (!letters) return 0;
  if (lang === "ar") return (text.match(subs_ARABIC) || []).length / letters;
  if (lang === "en" || lang === "fr" || lang === "es" || lang === "de" || lang === "it" || lang === "tr") return (text.match(/[A-Za-zÀ-ÿĞğİıŞş]/g) || []).length / letters;
  return -1; // unknown script: skip the check
}

// ----------------------------------------------------------------------------- SRT parsing + validation

function wnParseSrt(srt) {
  const cues = [];
  const toMs = (h, m, s, f) => Number(h) * 3600000 + Number(m) * 60000 + Number(s) * 1000 + Number(String(f).padEnd(3, "0").slice(0, 3));
  for (const block of String(srt).replace(/\r\n?/g, "\n").split(/\n{2,}/)) {
    const lines = block.split("\n");
    const ti = lines.findIndex((l) => l.includes("-->"));
    if (ti < 0) continue;
    const m = lines[ti].match(/(\d+):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d+):(\d{2}):(\d{2})[,.](\d{1,3})/);
    if (!m) continue;
    cues.push({ start: toMs(m[1], m[2], m[3], m[4]), end: toMs(m[5], m[6], m[7], m[8]), text: lines.slice(ti + 1).join("\n").trim() });
  }
  return cues;
}

/**
 * Structural validation of a CLEANED srt. issues[] block the import; warnings[] are shown but do not.
 * opts: { lang, runtimeMin, kind }
 */
function wnValidateSrt(srt, opts) {
  const o = opts || {};
  const issues = [];
  const warnings = [];
  const text = cleanString(srt);
  if (!text) {
    return { ok: false, cues: 0, issues: [{ code: "empty", message: "الملف فارغ." }], warnings };
  }
  if (text.length > WN_MAX_SRT_BYTES) issues.push({ code: "too_large", message: "الملف أكبر من الحد المسموح (1.5MB)." });
  const cues = wnParseSrt(text);
  if (cues.length < 5) issues.push({ code: "too_few_cues", message: "عدد الأسطر (" + cues.length + ") قليل جداً — الملف تالف أو غير مكتمل." });
  else if (o.kind !== "episode" && cues.length < 100) warnings.push({ code: "few_cues", message: "عدد أسطر الترجمة قليل (" + cues.length + ")." });
  const badTiming = cues.filter((c) => c.end <= c.start).length;
  if (cues.length && badTiming / cues.length > 0.02) issues.push({ code: "bad_timing", message: badTiming + " سطراً توقيته غير منطقي." });
  else if (badTiming) warnings.push({ code: "bad_timing", message: badTiming + " سطر/أسطر بتوقيت غير منطقي." });
  let outOfOrder = 0;
  for (let i = 1; i < cues.length; i++) if (cues[i].start < cues[i - 1].start - 1000) outOfOrder++;
  if (cues.length && outOfOrder / cues.length > 0.03) issues.push({ code: "out_of_order", message: "ترتيب التوقيت مضطرب (" + outOfOrder + " موضعاً)." });
  const long = cues.filter((c) => c.end - c.start > 30000).length;
  if (long) warnings.push({ code: "long_cues", message: long + " سطراً مدته أطول من 30 ثانية." });
  const last = cues.reduce((m, c) => Math.max(m, c.end), 0);
  const first = cues.length ? cues.reduce((m, c) => Math.min(m, c.start), Infinity) : 0;
  const rt = Number(o.runtimeMin) > 0 ? Number(o.runtimeMin) * 60000 : 0;
  if (rt && cues.length >= 5) {
    if (last > rt * 1.3 + 600000) warnings.push({ code: "longer_than_runtime", message: "الترجمة أطول بكثير من مدة العمل — قد تكون لنسخة أخرى." });
    else if (last < rt * 0.4) warnings.push({ code: "shorter_than_runtime", message: "الترجمة أقصر بكثير من مدة العمل — قد تكون ناقصة." });
  }
  const share = wnLangShare(text, o.lang || "");
  if (share >= 0 && o.lang && cues.length >= 5) {
    if (share < (o.lang === "ar" ? 0.3 : 0.6)) issues.push({ code: "wrong_language", message: "لغة الملف لا تطابق «" + o.lang + "»." });
  }
  if (/[ØÙÃ][\u0080-¿]/.test(text) && wnLangShare(text, "ar") < 0.05 && o.lang === "ar") {
    issues.push({ code: "bad_encoding", message: "ترميز الملف تالف (حروف مشوّهة)." });
  }
  return {
    ok: issues.length === 0,
    cues: cues.length,
    first_ms: Number.isFinite(first) ? first : 0,
    last_ms: last,
    issues,
    warnings,
    language_share: share >= 0 ? Math.round(share * 100) / 100 : null,
  };
}

// ----------------------------------------------------------------------------- download (generic languages)

async function wnSubsDownload(env, c, t, lang) {
  if (lang === "ar") return subsDownload(env, c, t); // the Worker's own Arabic path (also checks the script share)
  let bytes;
  if (c.dl.startsWith("opensubtitles:")) {
    const token = await subsOpenSubsToken(env);
    if (!token) throw new AdminError("subs_login_missing", "التحميل من OpenSubtitles يحتاج OPENSUBTITLES_USERNAME و OPENSUBTITLES_PASSWORD.", 400);
    const r = await subsFetch("https://api.opensubtitles.com/api/v1/download", {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json", "api-key": cleanString(env.OPENSUBTITLES_API_KEY), authorization: "Bearer " + token, "user-agent": SUBS_UA },
      body: JSON.stringify({ file_id: numOf(c.id), sub_format: "srt" }),
    });
    const d = await r.json().catch(() => null);
    if (!r.ok || !d || !d.link) throw new AdminError("subs_download_failed", (d && d.message) || "OpenSubtitles " + r.status, 502);
    const f = await subsFetch(d.link, { headers: { "user-agent": SUBS_UA } });
    if (!f.ok) throw new AdminError("subs_download_failed", "OpenSubtitles " + f.status, 502);
    bytes = new Uint8Array(await f.arrayBuffer());
  } else {
    const headers = { "user-agent": SUBS_UA };
    if (c.source === "subsource") headers["x-api-key"] = subsKeys(env).subsource;
    const f = await subsFetch(c.dl, { headers }, 20000);
    if (!f.ok) throw new AdminError("subs_download_failed", c.source + " " + f.status, 502);
    bytes = new Uint8Array(await f.arrayBuffer());
  }
  if (bytes.length > SUBS_MAX_ZIP) throw new AdminError("subs_too_big", "الملف كبير جداً.", 502);
  let files;
  if (bytes[0] === 0x50 && bytes[1] === 0x4b) files = await subsUnzip(bytes, /\.(srt|vtt)$/i);
  else if (bytes[0] === 0x1f && bytes[1] === 0x8b) {
    const ds = new DecompressionStream("gzip");
    files = [{ name: "subtitle.srt", data: new Uint8Array(await new Response(new Blob([bytes]).stream().pipeThrough(ds)).arrayBuffer()) }];
  } else files = [{ name: "subtitle.srt", data: bytes }];
  if (!files.length) throw new AdminError("subs_no_srt", "لا يوجد ملف SRT داخل الأرشيف.", 502);
  let chosen = files[0];
  if (files.length > 1 || c.full_season) {
    let best = null;
    for (const f of files) {
      const e = subsEpisodeOf(f.name);
      if (e && t.episode && e.episode === t.episode && (!e.season || !t.season || e.season === t.season)) { best = f; break; }
    }
    if (!best && files.length > 1) throw new AdminError("subs_episode_not_in_pack", "لم أجد الحلقة المطلوبة داخل حزمة الموسم.", 502);
    if (best) chosen = best;
  }
  const dec = subs_decodeSubtitleBytes(chosen.data);
  const parsed = subsToSrt(dec.text);
  if (parsed.cues < 5) throw new AdminError("subs_empty", "الملف لا يحتوي ترجمة صالحة.", 502);
  return { srt: parsed.srt, cues: parsed.cues, encoding: dec.encoding, file: chosen.name };
}

// ----------------------------------------------------------------------------- parking a validated subtitle

async function wnParkSubtitle(env, srt, info) {
  const token = wnRandomHex(16);
  const bucket = wnBucket(env);
  await bucket.put(adminKey(env, WN_SUBS_PREFIX + token + ".srt"), srt, { httpMetadata: { contentType: "application/x-subrip; charset=utf-8" } });
  await bucket.put(adminKey(env, WN_SUBS_PREFIX + token + ".json"), JSON.stringify(Object.assign({ at: Date.now() }, info)), {
    httpMetadata: { contentType: "application/json; charset=utf-8" },
  });
  return token;
}

async function wnReadParkedSubtitle(env, token) {
  const t = cleanString(token);
  if (!/^[0-9a-f]{32}$/.test(t)) return null;
  const bucket = wnBucket(env);
  const [srtObj, infoObj] = await Promise.all([bucket.get(adminKey(env, WN_SUBS_PREFIX + t + ".srt")), bucket.get(adminKey(env, WN_SUBS_PREFIX + t + ".json"))]);
  if (!srtObj || !infoObj) return null;
  let info = null;
  try { info = JSON.parse(await infoObj.text()); } catch { info = null; }
  if (!info) return null;
  return { token: t, srt: await srtObj.text(), info };
}

async function wnDropParkedSubtitle(env, token) {
  const t = cleanString(token);
  if (!/^[0-9a-f]{32}$/.test(t)) return;
  try {
    await wnBucket(env).delete([adminKey(env, WN_SUBS_PREFIX + t + ".srt"), adminKey(env, WN_SUBS_PREFIX + t + ".json")]);
  } catch { /* best effort */ }
}

async function wnCleanParkedSubtitles(env) {
  try {
    const all = await listAll(wnBucket(env), adminKey(env, WN_SUBS_PREFIX));
    const doomed = all.filter((o) => o.uploaded && Date.now() - new Date(o.uploaded).getTime() > WN_SUB_TMP_TTL_MS).map((o) => o.key);
    for (let i = 0; i < doomed.length; i += 100) await wnBucket(env).delete(doomed.slice(i, i + 100));
    return doomed.length;
  } catch {
    return 0;
  }
}

function wnSubPreview(srt, n) {
  return srt.split("\n\n").slice(0, n || 6).join("\n\n");
}

// ----------------------------------------------------------------------------- routes

async function wnTargetFromInput(env, input) {
  const kind = wnKind(input.type);
  const tmdb = wnTmdbId(input.tmdb_id);
  const meta = await wnFetchMeta(env, kind, tmdb);
  const season = numOf(input.season);
  const episode = numOf(input.episode);
  if (kind === "series" && !season) throw new AdminError("missing_numbers", "رقم الموسم مطلوب لترجمة حلقات المسلسل.", 400);
  return { meta, t: wnSubTarget(meta, season, episode), kind, tmdb, season, episode };
}

async function handleWnSubtitles(request, env) {
  wnRate("subs-search", 60);
  const input = await readRequestData(request);
  const lang = wnSubLang(input.lang, true);
  const { meta, t } = await wnTargetFromInput(env, input);
  const order = lang === "auto" ? ["ar", "en"] : [lang];
  let used = order[0];
  let found = { items: [], status: {} };
  for (const l of order) {
    used = l;
    found = await wnSubsSearch(env, t, l);
    if (found.items.length) break;
  }
  return okResponse(
    {
      target: { type: meta.kind, tmdb_id: meta.tmdb_id, title: meta.title, season: t.season, episode: t.episode, runtime_minutes: meta.runtime_minutes },
      lang: used,
      results: found.items.slice(0, 30),
      best: found.items[0] || null,
      status: found.status,
    },
    found.items.length ? "وُجدت " + found.items.length + " ترجمة (" + used + ")." : "لم أجد ترجمة لهذا العنوان في المصادر المتاحة."
  );
}

async function handleWnSubtitlesFetch(request, env) {
  wnRate("subs-fetch", 40);
  const input = await readRequestData(request);
  const lang = wnSubLang(input.lang, false);
  const { meta, t, kind, tmdb, season, episode } = await wnTargetFromInput(env, input);
  const cand = wnSubCandidate(input.pick || {});
  const got = await wnSubsDownload(env, cand, t, lang);
  const validation = wnValidateSrt(got.srt, { lang, runtimeMin: meta.runtime_minutes, kind: kind === "series" ? "episode" : "movie" });
  // matching: a candidate that names another episode is rejected even if the file itself is fine
  const mismatch = kind === "series" && cand.episode && episode && cand.episode !== episode && !cand.full_season;
  if (mismatch) validation.issues.push({ code: "episode_mismatch", message: "الترجمة للحلقة " + cand.episode + " وليست " + episode + "." });
  validation.ok = validation.issues.length === 0;
  const out = {
    lang, source: cand.source, release: cand.release, file: got.file, encoding: got.encoding, bytes: got.srt.length,
    cues: validation.cues, validation, preview: wnSubPreview(got.srt), token: "",
    season, episode, format: "srt", provider: cand.source,
  };
  if (validation.ok) {
    out.token = await wnParkSubtitle(env, got.srt, { lang, source: cand.source, release: cand.release, tmdb: tmdb, kind, season, episode, cues: validation.cues, encoding: got.encoding });
  }
  return okResponse(out, validation.ok ? "تم تنزيل الترجمة والتحقق منها." : "تم تنزيل الملف لكنه فشل في التحقق.");
}

async function handleWnSubtitlesValidate(request, env) {
  wnRate("subs-validate", 60);
  const input = await readRequestData(request);
  const lang = wnSubLang(input.lang, false);
  const { meta, kind, tmdb, season, episode } = await wnTargetFromInput(env, input);
  let text = "";
  if (cleanString(input.base64)) {
    let bin;
    try { bin = atob(cleanString(input.base64)); } catch { throw new AdminError("invalid_file", "الملف المرسل غير صالح (base64).", 400); }
    if (bin.length > WN_MAX_SRT_BYTES * 2) throw new AdminError("too_large", "الملف أكبر من الحد المسموح.", 413);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    text = subs_decodeSubtitleBytes(bytes).text;
  } else {
    text = String(input.text || "");
    if (text.length > WN_MAX_SRT_BYTES * 2) throw new AdminError("too_large", "الملف أكبر من الحد المسموح.", 413);
  }
  const parsed = subsToSrt(text);
  const validation = wnValidateSrt(parsed.srt, { lang, runtimeMin: meta.runtime_minutes, kind: kind === "series" ? "episode" : "movie" });
  const out = { lang, source: "manual", release: cleanString(input.name).slice(0, 120), cues: validation.cues, validation, preview: wnSubPreview(parsed.srt), token: "", season, episode, format: "srt", provider: "manual" };
  if (validation.ok) out.token = await wnParkSubtitle(env, parsed.srt, { lang, source: "manual", release: out.release, tmdb, kind, season, episode, cues: validation.cues });
  return okResponse(out, validation.ok ? "الملف صالح." : "الملف غير صالح.");
}
