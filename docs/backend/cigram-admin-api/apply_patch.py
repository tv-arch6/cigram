#!/usr/bin/env python3
"""18.2 — additive patch for the LIVE cigram-admin-api worker.js.

Adds: Cigram Discovery feeds (daily trending, top rated, on the air), management decisions storage,
the CigramSubtitleHub translation contract (provider adapters NOT implemented: they answer 501 until an adapter is
written from the provider's official documentation and tested with real credentials), TMDB-first subtitle search,
and refuses to match a LIBRARY title to subtitles by its name alone.

Usage:  python3 apply_patch.py <live worker.js> <output worker.js>   (every anchor must match exactly once)
Not deployed by the delivery: review the diff, run the tests in ../tests, then deploy yourself.
"""
import sys

src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding="utf-8").read()


def rep(old, new, count=1):
    global s
    n = s.count(old)
    if count is None:
        count = n if n >= 1 else 1
    if n != count:
        raise SystemExit("anchor found %d times (expected %d): %r" % (n, count, old[:90]))
    s = s.replace(old, new)


# ---------------------------------------------------------------- discovery feeds
rep('''const WN_FEED_KEYS = [
  "movie:new_releases", "movie:upcoming", "movie:popular", "movie:trending",
  "tv:new_releases", "tv:recent_episodes", "tv:new_seasons", "tv:popular", "tv:trending",
  "all:new_releases", "all:trending",
];''', '''const WN_FEED_KEYS = [
  "movie:new_releases", "movie:upcoming", "movie:popular", "movie:trending",
  "tv:new_releases", "tv:recent_episodes", "tv:new_seasons", "tv:popular", "tv:trending",
  "all:new_releases", "all:trending",
  // 18.2 Cigram Discovery: TMDB v3 lists /trending/{type}/day, /movie/top_rated, /tv/top_rated, /tv/on_the_air
  "movie:trending_day", "tv:trending_day", "all:trending_day", "movie:top_rated", "tv:top_rated", "tv:on_the_air",
];''')
rep('''  const data = await wnTmdb(env, "/discover/" + tmdbKind, wnDiscoverParams(kind, feed, f, cfg), { ttl: f.fresh ? 0 : 600 });
  let rows = (Array.isArray(data.results) ? data.results : []).map((row) => ({ row, kind }));''', '''  if (feed === "trending_day" || feed === "top_rated" || feed === "on_the_air") {
    // 18.2: plain TMDB lists (no /discover parameters). on_the_air exists for TV only.
    if (feed === "on_the_air" && tmdbKind !== "tv") return { rows: [], total_pages: 1, total_results: 0 };
    const listPath = feed === "trending_day" ? "/trending/" + tmdbKind + "/day" : "/" + tmdbKind + "/" + feed;
    const ld = await wnTmdb(env, listPath, { language: cfg.language || "ar-SA", page: f.page }, { ttl: f.fresh ? 0 : 600 });
    let lr = (Array.isArray(ld.results) ? ld.results : []).filter((r) => wnLocalFilter(r, kind, f));
    if (f.sort === "rating") lr = lr.slice().sort((a, b) => Number(b.vote_average) - Number(a.vote_average));
    if (f.sort === "release") lr = lr.slice().sort((a, b) => cleanString(b.release_date || b.first_air_date).localeCompare(cleanString(a.release_date || a.first_air_date)));
    return { rows: lr.map((row) => ({ row, kind })), total_pages: Math.min(500, Number(ld.total_pages) || 1), total_results: Number(ld.total_results) || lr.length };
  }
  const data = await wnTmdb(env, "/discover/" + tmdbKind, wnDiscoverParams(kind, feed, f, cfg), { ttl: f.fresh ? 0 : 600 });
  let rows = (Array.isArray(data.results) ? data.results : []).map((row) => ({ row, kind }));''')

# ---------------------------------------------------------------- decisions + subtitle hub contract
rep('''// ----------------------------------------------------------------------------- route table
''', '''// ----------------------------------------------------------------------------- 18.2 management decisions

const WN_DECISIONS_FILE = "whatsnew/decisions.json";
const WN_DECISION_VALUES = ["", "review", "approved", "rejected", "imported"];

async function handleWnDecision(request, env) {
  wnRate("decision", 240);
  const input = await readRequestData(request);
  const key = cleanString(input.key).slice(0, 60);
  if (!/^(movie|series|tv|collection):\\d{1,10}$/.test(key)) throw new AdminError("invalid_key", "مفتاح العنوان غير صالح.", 400);
  const decision = cleanString(input.decision);
  if (!WN_DECISION_VALUES.includes(decision)) throw new AdminError("invalid_decision", "قرار غير معروف.", 400);
  const note = cleanString(input.note).slice(0, 300);
  await wnMutate(env, WN_DECISIONS_FILE, { items: {} }, (doc) => {
    doc.items = doc.items && typeof doc.items === "object" ? doc.items : {};
    if (!decision) delete doc.items[key];
    else doc.items[key] = { decision, note, at: new Date().toISOString() };
    return { doc };
  });
  return okResponse({ key, decision }, "حُفظ القرار.");
}

async function handleWnDecisions(request, env) {
  const doc = await wnRead(env, WN_DECISIONS_FILE, { items: {} });
  return okResponse({ items: doc.items || {} });
}

// ----------------------------------------------------------------------------- 18.2 CigramSubtitleHub (translation)
// Provider adapters. None is implemented in this delivery: the official translation endpoints could not be verified
// from this environment, and endpoints are never guessed. A provider answers 501 until its adapter is written from
// the provider's documentation and tested with real credentials (secrets stay in Cloudflare, never in an APK).

const SUBHUB_PROVIDERS = {
  opensubtitles_ai: {
    label: "OpenSubtitles AI",
    docs: "https://ai.opensubtitles.com/api-docs",
    implemented: false,
    secrets: ["OPENSUBTITLES_AI_API_KEY"],
    reason: "مسارات الترجمة في OpenSubtitles AI لم تُتحقق من الوثائق الرسمية بعد؛ لم يُكتب الموصل ولن يُخمَّن.",
  },
  subdl_translate: {
    label: "SubDL translation",
    docs: "https://subdl.com/api-doc",
    implemented: false,
    secrets: ["SUBDL_API_KEY"],
    reason: "الترجمة عبر SubDL تتطلب اشتراكاً وتأكيداً أن العربية لغة هدف مدعومة.",
  },
};
const SUBHUB_MAX_SOURCE = 2 * 1024 * 1024;

function subhubProvider(name) {
  const p = SUBHUB_PROVIDERS[cleanString(name)];
  if (!p) throw new AdminError("unknown_provider", "مزوّد ترجمة غير معروف.", 400);
  if (!p.implemented) {
    throw new AdminError("provider_not_implemented", p.label + ": " + p.reason + " لم يُرسل أي شيء ولم تُحجز أي تكلفة.", 501, { provider: cleanString(name), docs: p.docs });
  }
  return p;
}

async function handleSubhubProviders(request, env) {
  const out = Object.entries(SUBHUB_PROVIDERS).map(([id, p]) => ({
    id, label: p.label, docs: p.docs, implemented: p.implemented, configured: p.secrets.every((k) => Boolean(cleanString(env[k]))), reason: p.implemented ? "" : p.reason,
  }));
  return okResponse({ providers: out, target_languages: ["ar"] });
}

async function handleSubhubTranslate(request, env) {
  wnRate("subhub_translate", 60);
  const input = await readRequestData(request);
  const id = cleanString(input.content_id);
  if (!id) throw new AdminError("missing_id", "معرّف المحتوى مطلوب.", 400);
  if (cleanString(input.target || "ar") !== "ar") throw new AdminError("invalid_target", "اللغة الهدف المدعومة: العربية فقط.", 400);
  const src = String(input.source_file || "");
  if (!src || src.length > SUBHUB_MAX_SOURCE) throw new AdminError("invalid_source", "ملف المصدر مفقود أو أكبر من 2 ميغابايت.", 400);
  const parsed = subsToSrt(src);
  if (parsed.cues < 1) throw new AdminError("invalid_source", "ملف المصدر لا يحتوي مقاطع صالحة.", 400);
  const list = await readMoviesList(env);
  if (!findById(list, id)) throw new AdminError("item_not_found", "العنوان غير موجود في المكتبة.", 404);
  subhubProvider(input.provider);   // 501 while no adapter is implemented — nothing below runs
  throw new AdminError("provider_not_implemented", "لا يوجد موصل ترجمة.", 501);
}

async function handleSubhubBatch(request, env) {
  wnRate("subhub_batch", 10);
  const input = await readRequestData(request);
  const ids = Array.isArray(input.ids) ? input.ids.map(cleanString).filter(Boolean) : [];
  if (!ids.length || ids.length > 5000) throw new AdminError("invalid_batch", "الدفعة فارغة أو أكبر من 5000 عنوان.", 400);
  if (new Set(ids).size !== ids.length) throw new AdminError("duplicate_ids", "الدفعة تحتوي عناوين مكررة.", 400);
  const budget = Number(input.budget), estimate = Number(input.estimate);
  if (!(budget > 0) || !(estimate >= 0)) throw new AdminError("budget_required", "الميزانية المعتمدة مطلوبة.", 400);
  if (budget < estimate) throw new AdminError("budget_too_low", "الميزانية أقل من التكلفة المقدّرة.", 400);
  if (!cleanString(input.approved_at)) throw new AdminError("approval_required", "موافقة الإدارة على الدفعة مطلوبة.", 400);
  if (cleanString(input.target || "ar") !== "ar") throw new AdminError("invalid_target", "اللغة الهدف المدعومة: العربية فقط.", 400);
  subhubProvider(input.provider);
  throw new AdminError("provider_not_implemented", "لا يوجد موصل ترجمة.", 501);
}

const SUBHUB_ROUTES = (() => {
  const A = adminRoute;
  return {
    "GET /admin/subhub/providers": A(handleSubhubProviders),
    "POST /admin/subhub/translate": A(handleSubhubTranslate),
    "POST /admin/subhub/translate/batch": A(handleSubhubBatch),
  };
})();

// ----------------------------------------------------------------------------- route table
''')
rep('''    "POST /admin/discover/collection": A(handleWnCollection),''', '''    "POST /admin/discover/collection": A(handleWnCollection),
    "POST /admin/discover/decision": A(handleWnDecision),
    "GET /admin/discover/decisions": A(handleWnDecisions),''')
rep('''      const wnHandler = WN_ROUTES[request.method + " " + path];
      if (wnHandler) return wnHandler(request, env, url, ctx);''', '''      const wnHandler = WN_ROUTES[request.method + " " + path];
      if (wnHandler) return wnHandler(request, env, url, ctx);

      const subhubHandler = SUBHUB_ROUTES[request.method + " " + path];
      if (subhubHandler) return subhubHandler(request, env, url, ctx);''')
rep('''            .concat(Object.keys(WN_ROUTES)),''', '''            .concat(Object.keys(WN_ROUTES))
            .concat(Object.keys(SUBHUB_ROUTES)),''')

# ---------------------------------------------------------------- subtitle search: TMDB id first, never by name for library titles
rep('''  if (t.imdb_id) base.imdb_id = t.imdb_id; else if (t.tmdb_id) base.tmdb_id = t.tmdb_id; else base.film_name = t.original || t.title;''',
    '''  if (t.tmdb_id) base.tmdb_id = t.tmdb_id; else if (t.imdb_id) base.imdb_id = t.imdb_id; else base.film_name = t.original || t.title; // 18.2: TMDB first''', count=None)
rep('''    if (t.imdb_id) u.searchParams.set("parent_imdb_id", num(t.imdb_id)); else if (t.tmdb_id) u.searchParams.set("parent_tmdb_id", t.tmdb_id); else u.searchParams.set("query", t.original || t.title);''',
    '''    if (t.tmdb_id) u.searchParams.set("parent_tmdb_id", t.tmdb_id); else if (t.imdb_id) u.searchParams.set("parent_imdb_id", num(t.imdb_id)); else u.searchParams.set("query", t.original || t.title);''', count=None)
rep('''    if (t.imdb_id) u.searchParams.set("imdb_id", num(t.imdb_id)); else if (t.tmdb_id) u.searchParams.set("tmdb_id", t.tmdb_id); else u.searchParams.set("query", t.original || t.title);''',
    '''    if (t.tmdb_id) u.searchParams.set("tmdb_id", t.tmdb_id); else if (t.imdb_id) u.searchParams.set("imdb_id", num(t.imdb_id)); else u.searchParams.set("query", t.original || t.title);''', count=None)
rep('''    } else if (!t.imdb_id && !t.tmdb_id && t.title) {
      const r = await tmdbGetOrNull(env, "/search/" + t.type,''', '''    } else if (!t.imdb_id && !t.tmdb_id && t.title && !t.item) {
      const r = await tmdbGetOrNull(env, "/search/" + t.type,''')
rep('''  if (!t.imdb_id && !t.tmdb_id && !t.title && !t.original) throw new AdminError("subs_missing_target",''', '''  // 18.2: a library title is never matched to subtitles by its name alone
  if (t.item && !t.imdb_id && !t.tmdb_id) throw new AdminError("subs_ids_required", "هذا العنوان بلا معرّف TMDB أو IMDb. أضف المعرّف أولاً: لا تُربط الترجمة بالاسم وحده.", 400);
  if (!t.imdb_id && !t.tmdb_id && !t.title && !t.original) throw new AdminError("subs_missing_target",''')

open(dst, "w", encoding="utf-8").write(s)
print("patched:", dst)
