
// ----------------------------------------------------------------------------- video sources (adapter + honest validation)
//
// Four levels, never conflated:
//   generated  a URL was BUILT from the TMDB id (no network proof of anything)
//   resolved   the provider itself confirmed it has the title (needs a provider lookup API — VidBolt has none)
//   reachable  the provider's page answered 200 with HTML that is not an error page (an embed: playback unproven)
//   playable   we fetched a media file / HLS playlist and parsed it (container magic, playlist, first segment)

const WN_VERDICTS = {
  playable: { level: "playable", importable: true, ack: false, rank: 4, label: "فيديو صالح للتشغيل (تم التحقق منه)" },
  embed_reachable: { level: "reachable", importable: true, ack: true, rank: 3, label: "صفحة المشغّل تعمل — لا يمكن إثبات التشغيل من الخادم" },
  unverified: { level: "generated", importable: true, ack: true, rank: 2, label: "المزوّد يمنع الفحص الآلي — الرابط غير مُتحقَّق منه" },
  unavailable: { level: "failed", importable: false, ack: false, rank: 0, label: "المزوّد لا يملك هذا العنوان" },
  unreachable: { level: "failed", importable: false, ack: false, rank: 0, label: "تعذر الوصول إلى الرابط" },
  invalid: { level: "failed", importable: false, ack: false, rank: 0, label: "الرابط لا يشير إلى فيديو صالح" },
  drm: { level: "failed", importable: false, ack: false, rank: 0, label: "المحتوى محمي بـ DRM ولا يمكن التحقق منه" },
  mismatch: { level: "failed", importable: false, ack: false, rank: 0, label: "الرابط لا يطابق الموسم/الحلقة المطلوبة" },
  not_released: { level: "generated", importable: false, ack: false, rank: 0, label: "لم يصدر بعد" },
};

function wnCheck(id, label, pass, detail) {
  return { id, label, pass, detail: detail === undefined ? "" : String(detail).slice(0, 200) };
}

/** Fetch with a timeout and MANUAL redirects: every hop is re-validated, so a redirect cannot reach an internal host. */
async function wnProbeFetch(rawUrl, init, timeoutMs) {
  let url = wnSafeUrl(rawUrl, { allowHttp: true }).toString();
  const started = Date.now();
  for (let hop = 0; hop < 4; hop++) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs || WN_PROBE_TIMEOUT_MS);
    let res;
    try {
      res = await fetch(url, Object.assign({}, init, { signal: controller.signal, redirect: "manual" }));
    } finally {
      clearTimeout(timer);
    }
    if (res.status >= 300 && res.status < 400 && res.headers.get("location")) {
      const next = new URL(res.headers.get("location"), url).toString();
      try { await res.body?.cancel(); } catch { /* ignore */ }
      url = wnSafeUrl(next, { allowHttp: true }).toString();
      continue;
    }
    return { res, url, ms: Date.now() - started };
  }
  throw new AdminError("too_many_redirects", "الرابط يعيد التوجيه مرات كثيرة.", 400);
}

function wnErrorPage(html) {
  const title = ((html.match(/<title[^>]*>([^<]{0,200})/i) || [])[1] || "").trim();
  if (/(^|\b)(404|403|410|not found|page not found|no results|unavailable|error|forbidden)(\b|$)|غير موجود|غير متاح/i.test(title)) return title || "error";
  if (/<meta[^>]+http-equiv=["']?refresh/i.test(html)) return "meta refresh";
  return "";
}

function wnHintOfUrl(url) {
  const name = decodeURIComponent(cleanString(url).split("?")[0].split("/").pop() || "");
  const m = name.match(/s(\d{1,2})[\s._-]*e(\d{1,3})/i) || name.match(/(?:^|[^0-9])(\d{1,2})x(\d{1,3})(?:[^0-9]|$)/i);
  return m ? { season: numOf(m[1]), episode: numOf(m[2]) } : null;
}

function wnDetectKind(url, fallback) {
  const path = cleanString(url).split("?")[0].toLowerCase();
  if (/\.m3u8$/.test(path)) return "hls";
  if (/\.(mp4|m4v|webm|mkv|mov)$/.test(path)) return "file";
  return fallback === "hls" || fallback === "file" ? fallback : "embed";
}

function wnFinishSource(base, verdict, checks, extra) {
  const meta = WN_VERDICTS[verdict];
  return Object.assign(
    {
      provider: base.provider,
      name: base.name,
      kind: base.kind,
      url: base.url,
      verdict,
      level: meta.level,
      importable: meta.importable,
      needs_ack: meta.ack,
      label: meta.label,
      checks,
      validated_at: new Date().toISOString(),
    },
    extra || {}
  );
}

async function wnProbeEmbed(base, checks) {
  let probe;
  try {
    probe = await wnProbeFetch(base.url, { method: "GET", headers: { accept: "text/html,*/*;q=0.5", range: "bytes=0-65535", "user-agent": WN_UA } });
  } catch (error) {
    const aborted = error && (error.name === "AbortError" || /abort/i.test(cleanString(error.message)));
    checks.push(wnCheck("http", "الاتصال بالمزوّد", false, aborted ? "انتهت المهلة" : cleanString(error && error.message).slice(0, 100)));
    return wnFinishSource(base, "unreachable", checks, { http: 0 });
  }
  const { res, ms } = probe;
  const type = cleanString(res.headers.get("content-type")).split(";")[0].toLowerCase();
  const extra = { http: res.status, ms, content_type: type };
  try {
    if (res.status === 404 || res.status === 410) {
      checks.push(wnCheck("http", "استجابة المزوّد", false, "HTTP " + res.status));
      return wnFinishSource(base, "unavailable", checks, extra);
    }
    if (res.status === 401 || res.status === 403 || res.status === 429) {
      checks.push(wnCheck("http", "استجابة المزوّد", null, "HTTP " + res.status + " — يمنع الفحص الآلي"));
      return wnFinishSource(base, "unverified", checks, extra);
    }
    if (res.status >= 400) {
      checks.push(wnCheck("http", "استجابة المزوّد", false, "HTTP " + res.status));
      return wnFinishSource(base, "unreachable", checks, extra);
    }
    checks.push(wnCheck("http", "استجابة المزوّد", true, "HTTP " + res.status + " · " + ms + "ms"));
    if (!/text\/html|application\/xhtml/.test(type)) {
      checks.push(wnCheck("content_type", "نوع المحتوى", false, type || "غير معروف"));
      return wnFinishSource(base, "invalid", checks, extra);
    }
    const bytes = await readUpTo(res, 65536);
    const html = new TextDecoder("utf-8").decode(bytes);
    if (bytes.length < 120 || !/<[a-z!]/i.test(html)) {
      checks.push(wnCheck("body", "محتوى الصفحة", false, "الصفحة فارغة"));
      return wnFinishSource(base, "invalid", checks, extra);
    }
    const why = wnErrorPage(html);
    if (why) {
      checks.push(wnCheck("body", "محتوى الصفحة", false, "تبدو صفحة خطأ: " + why));
      return wnFinishSource(base, "unavailable", checks, extra);
    }
    checks.push(wnCheck("body", "محتوى الصفحة", true, "صفحة مشغّل (HTML)"));
    checks.push(wnCheck("playback", "إثبات التشغيل", null, "المشغّل مضمَّن (iframe) — لا يمكن للخادم تشغيله أو فحص البث"));
    return wnFinishSource(base, "embed_reachable", checks, extra);
  } finally {
    try { await res.body?.cancel(); } catch { /* ignore */ }
  }
}

async function wnProbeFile(base, checks) {
  let probe;
  try {
    probe = await wnProbeFetch(base.url, { method: "GET", headers: { range: "bytes=0-2047", "user-agent": WN_UA } });
  } catch (error) {
    checks.push(wnCheck("http", "الاتصال", false, cleanString(error && error.message).slice(0, 100)));
    return wnFinishSource(base, "unreachable", checks, { http: 0 });
  }
  const { res, ms } = probe;
  const type = cleanString(res.headers.get("content-type")).split(";")[0].toLowerCase();
  const extra = { http: res.status, ms, content_type: type };
  try {
    if (res.status === 404 || res.status === 410) {
      checks.push(wnCheck("http", "الاتصال", false, "HTTP " + res.status));
      return wnFinishSource(base, "unavailable", checks, extra);
    }
    if (res.status === 401 || res.status === 403 || res.status === 429) {
      checks.push(wnCheck("http", "الاتصال", false, "HTTP " + res.status + " — غير مصرّح"));
      return wnFinishSource(base, "unreachable", checks, extra);
    }
    if (res.status !== 200 && res.status !== 206) {
      checks.push(wnCheck("http", "الاتصال", false, "HTTP " + res.status));
      return wnFinishSource(base, "unreachable", checks, extra);
    }
    checks.push(wnCheck("http", "الاتصال", true, "HTTP " + res.status + " · " + ms + "ms"));
    if (/text\/html|application\/json|text\/plain/.test(type)) {
      checks.push(wnCheck("content_type", "نوع المحتوى", false, type));
      return wnFinishSource(base, "invalid", checks, extra);
    }
    const head = await readUpTo(res, 2048);
    const mp4 = head.length >= 12 && head[4] === 0x66 && head[5] === 0x74 && head[6] === 0x79 && head[7] === 0x70; // "ftyp"
    const ebml = head.length >= 4 && head[0] === 0x1a && head[1] === 0x45 && head[2] === 0xdf && head[3] === 0xa3; // webm / mkv
    if (!mp4 && !ebml) {
      checks.push(wnCheck("container", "صيغة الملف", false, "لا يبدأ بترويسة MP4 أو WebM/MKV"));
      return wnFinishSource(base, "invalid", checks, extra);
    }
    const range = cleanString(res.headers.get("content-range")).match(/\/(\d+)$/);
    const size = range ? Number(range[1]) : Number(res.headers.get("content-length")) || 0;
    extra.size = size;
    if (size && size < 100000) {
      checks.push(wnCheck("size", "حجم الملف", false, size + " bytes"));
      return wnFinishSource(base, "invalid", checks, extra);
    }
    checks.push(wnCheck("container", "صيغة الملف", true, mp4 ? "MP4" : "WebM/MKV"));
    return wnFinishSource(base, "playable", checks, extra);
  } finally {
    try { await res.body?.cancel(); } catch { /* ignore */ }
  }
}

async function wnProbeHls(base, checks) {
  const fetchText = async (u, max) => {
    const p = await wnProbeFetch(u, { method: "GET", headers: { accept: "application/vnd.apple.mpegurl,*/*", "user-agent": WN_UA } });
    try {
      if (p.res.status < 200 || p.res.status >= 300) return { status: p.res.status, text: "", url: p.url, ms: p.ms, type: "" };
      const bytes = await readUpTo(p.res, max);
      return { status: p.res.status, text: new TextDecoder("utf-8").decode(bytes), url: p.url, ms: p.ms, type: cleanString(p.res.headers.get("content-type")).split(";")[0].toLowerCase() };
    } finally {
      try { await p.res.body?.cancel(); } catch { /* ignore */ }
    }
  };
  let top;
  try {
    top = await fetchText(base.url, 262144);
  } catch (error) {
    checks.push(wnCheck("http", "قائمة التشغيل", false, cleanString(error && error.message).slice(0, 100)));
    return wnFinishSource(base, "unreachable", checks, { http: 0 });
  }
  const extra = { http: top.status, ms: top.ms, content_type: top.type };
  if (top.status === 404 || top.status === 410) {
    checks.push(wnCheck("http", "قائمة التشغيل", false, "HTTP " + top.status));
    return wnFinishSource(base, "unavailable", checks, extra);
  }
  if (top.status < 200 || top.status >= 300) {
    checks.push(wnCheck("http", "قائمة التشغيل", false, "HTTP " + top.status));
    return wnFinishSource(base, "unreachable", checks, extra);
  }
  checks.push(wnCheck("http", "قائمة التشغيل", true, "HTTP " + top.status));
  if (!/^\s*#EXTM3U/.test(top.text)) {
    checks.push(wnCheck("playlist", "ملف m3u8", false, "لا يبدأ بـ #EXTM3U"));
    return wnFinishSource(base, "invalid", checks, extra);
  }
  if (/#EXT-X-(SESSION-)?KEY:[^\n]*(SAMPLE-AES|streamingkeydelivery|playready|edef8ba9|widevine)/i.test(top.text)) {
    checks.push(wnCheck("drm", "الحماية", false, "DRM — لن نتجاوزه ولا يمكن فحصه"));
    return wnFinishSource(base, "drm", checks, extra);
  }
  let media = top;
  if (/#EXT-X-STREAM-INF/.test(top.text)) {
    const lines = top.text.split(/\r?\n/);
    const idx = lines.findIndex((l) => l.startsWith("#EXT-X-STREAM-INF"));
    const uri = lines.slice(idx + 1).find((l) => l.trim() && !l.startsWith("#"));
    if (!uri) {
      checks.push(wnCheck("playlist", "القائمة الرئيسية", false, "لا توجد جودات"));
      return wnFinishSource(base, "invalid", checks, extra);
    }
    try {
      media = await fetchText(new URL(uri.trim(), top.url).toString(), 262144);
    } catch (error) {
      checks.push(wnCheck("variant", "قائمة الجودة", false, cleanString(error && error.message).slice(0, 100)));
      return wnFinishSource(base, "unreachable", checks, extra);
    }
    if (media.status < 200 || media.status >= 300 || !/^\s*#EXTM3U/.test(media.text)) {
      checks.push(wnCheck("variant", "قائمة الجودة", false, "HTTP " + media.status));
      return wnFinishSource(base, "invalid", checks, extra);
    }
    checks.push(wnCheck("variant", "قائمة الجودة", true, "تم تحميلها"));
  }
  const segLines = media.text.split(/\r?\n/);
  const segIdx = segLines.findIndex((l) => l.startsWith("#EXTINF"));
  const segUri = segIdx >= 0 ? segLines.slice(segIdx + 1).find((l) => l.trim() && !l.startsWith("#")) : "";
  if (!segUri) {
    checks.push(wnCheck("segments", "المقاطع", false, "لا توجد مقاطع #EXTINF"));
    return wnFinishSource(base, "invalid", checks, extra);
  }
  try {
    const seg = await wnProbeFetch(new URL(segUri.trim(), media.url).toString(), { method: "GET", headers: { range: "bytes=0-1023", "user-agent": WN_UA } });
    try {
      const st = seg.res.status;
      const segType = cleanString(seg.res.headers.get("content-type")).split(";")[0].toLowerCase();
      if ((st !== 200 && st !== 206) || /text\/html|application\/json/.test(segType)) {
        checks.push(wnCheck("segments", "أول مقطع", false, "HTTP " + st + " " + segType));
        return wnFinishSource(base, "unreachable", checks, extra);
      }
    } finally {
      try { await seg.res.body?.cancel(); } catch { /* ignore */ }
    }
  } catch (error) {
    checks.push(wnCheck("segments", "أول مقطع", false, cleanString(error && error.message).slice(0, 100)));
    return wnFinishSource(base, "unreachable", checks, extra);
  }
  checks.push(wnCheck("segments", "أول مقطع", true, "تم تنزيل أول بايتات المقطع"));
  return wnFinishSource(base, "playable", checks, extra);
}

const WN_PROBE_CACHE = new Map();

/** Probes ONE url for ONE target and returns a source record (signed token only when the result is importable). */
async function wnProbeSource(env, provider, url, target, hintKind, fresh) {
  const checks = [];
  const base = { provider: provider.id, name: provider.name, kind: wnDetectKind(url, hintKind || provider.kind), url };
  try {
    wnSafeUrl(url, { allowHttp: provider.id === "custom" });
  } catch (error) {
    checks.push(wnCheck("url_safe", "سلامة الرابط", false, error.message));
    return wnFinishSource(base, "invalid", checks);
  }
  checks.push(wnCheck("url_safe", "سلامة الرابط", true, new URL(url).hostname));

  // the URL must belong to THIS season/episode (custom links carry S01E03-style hints; generated ones are rebuilt)
  if (target.kind === "episode") {
    const hint = provider.id === "custom" ? wnHintOfUrl(url) : null;
    if (hint && (hint.season !== target.season || hint.episode !== target.episode)) {
      checks.push(wnCheck("episode_match", "مطابقة الموسم/الحلقة", false, "الرابط يخص S" + wnPad2(hint.season) + "E" + wnPad2(hint.episode)));
      return wnFinishSource(base, "mismatch", checks);
    }
    checks.push(wnCheck("episode_match", "مطابقة الموسم/الحلقة", true, "S" + wnPad2(target.season) + "E" + wnPad2(target.episode)));
  }

  const cached = fresh ? null : WN_PROBE_CACHE.get(url); // "re-check" skips the 5-minute probe cache
  let source;
  if (cached && cached.exp > Date.now()) {
    source = Object.assign({}, cached.source, { checks: checks.concat(cached.source.checks.filter((c) => !["url_safe", "episode_match"].includes(c.id))), cached: true });
  } else {
    const probeChecks = [];
    if (base.kind === "hls") source = await wnProbeHls(base, probeChecks);
    else if (base.kind === "file") source = await wnProbeFile(base, probeChecks);
    else source = await wnProbeEmbed(base, probeChecks);
    source.checks = checks.concat(source.checks);
    if (WN_PROBE_CACHE.size > 300) WN_PROBE_CACHE.delete(WN_PROBE_CACHE.keys().next().value);
    WN_PROBE_CACHE.set(url, { exp: Date.now() + 5 * 60000, source: clone(source) });
  }
  if (source.importable) {
    source.token = await wnSignToken(env, {
      u: url, v: source.verdict, l: source.level, k: target.kind, t: String(target.tmdb), s: target.season || 0, e: target.episode || 0,
      p: provider.id, n: source.kind, x: Date.now() + WN_TOKEN_TTL_MS,
    });
  }
  return source;
}

function wnProviderUrl(provider, target) {
  const tpl = target.kind === "movie" ? provider.movie : provider.tv;
  if (!tpl) return "";
  return tpl
    .replace("{tmdb}", String(target.tmdb))
    .replace("{season}", String(target.season || ""))
    .replace("{episode}", String(target.episode || ""));
}

/** Finds + validates sources for one movie / one episode across every enabled provider; ranks them. */
async function wnResolveTarget(env, cfg, target, opts) {
  const o = opts || {};
  const out = { kind: target.kind, season: target.season || 0, episode: target.episode || 0, tmdb_id: String(target.tmdb), sources: [] };
  if (target.released === false) {
    const base = { provider: "n/a", name: "—", kind: "embed", url: "" };
    out.sources.push(wnFinishSource(base, "not_released", [wnCheck("released", "تاريخ الإصدار", false, "لم يصدر بعد")]));
  } else {
    const providers = (cfg.video_providers || []).filter((p) => p && p.enabled !== false);
    const jobs = [];
    for (const p of providers) {
      const url = wnProviderUrl(p, target);
      if (url) jobs.push(wnProbeSource(env, p, url, target, p.kind, o.fresh));
    }
    if (o.customUrl) {
      jobs.push(wnProbeSource(env, { id: "custom", name: "رابط يدوي", kind: "embed" }, cleanString(o.customUrl), target, "", o.fresh));
    }
    out.sources = await Promise.all(jobs);
    if (!out.sources.length) {
      const base = { provider: "n/a", name: "—", kind: "embed", url: "" };
      out.sources.push(wnFinishSource(base, "unavailable", [wnCheck("providers", "المزوّدون", false, "لا يوجد مزوّد مُفعّل لهذا النوع")]));
    }
  }
  const order = new Map((cfg.video_providers || []).map((p, i) => [p.id, i]));
  out.sources.sort((a, b) => {
    const ra = WN_VERDICTS[a.verdict].rank;
    const rb = WN_VERDICTS[b.verdict].rank;
    if (ra !== rb) return rb - ra;
    const pa = order.has(a.provider) ? order.get(a.provider) : 99;
    const pb = order.has(b.provider) ? order.get(b.provider) : 99;
    if (pa !== pb) return pa - pb;
    return (a.ms || 0) - (b.ms || 0);
  });
  const best = out.sources.find((s) => s.importable) || null;
  out.best = best ? best.provider : "";
  out.fallbacks = out.sources.filter((s) => s.importable && s !== best).map((s) => ({ provider: s.provider, url: s.url, verdict: s.verdict }));
  out.status = best ? best.verdict : out.sources[0] ? out.sources[0].verdict : "unavailable";
  return out;
}
