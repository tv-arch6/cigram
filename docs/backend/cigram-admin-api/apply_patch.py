#!/usr/bin/env python3
"""18.2 — additive patch for the LIVE cigram-admin-api worker.js.

Adds: Cigram Discovery feeds (daily trending, top rated, on the air), management decisions storage,
the CigramSubtitleHub translation contract (provider adapters NOT implemented: they answer 501 until an adapter is
written from the provider's official documentation and tested with real credentials), TMDB-first subtitle search,
and refuses to match a LIBRARY title to subtitles by its name alone.

Usage:  python3 apply_patch.py <live worker.js> <output worker.js>   (every anchor must match exactly once)
Not deployed by the delivery: review the diff, run the tests in ../tests, then deploy yourself.
"""
import os
import sys

src, dst = sys.argv[1], sys.argv[2]
HUB_JS = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "subhub_providers.js"), encoding="utf-8").read()
s = open(src, encoding="utf-8").read()


def rep(old, new, count=1):
    global s
    n = s.count(old)
    if count is None:
        count = n if n >= 1 else 1
    if n != count:
        raise SystemExit("anchor found %d times (expected %d): %r" % (n, count, old[:90]))
    s = s.replace(old, new)


# ---------------------------------------------------------------- remove the v10 OpenSubtitles token + search (re-written in subhub_providers.js)
def drop(start, end_marker="\n}\n"):
    """Removes one top-level function (start = its first line); it must exist exactly once."""
    global s
    n = s.count(start)
    if n != 1:
        raise SystemExit("function anchor found %d times: %r" % (n, start[:80]))
    i = s.index(start)
    j = s.index(end_marker, i) + len(end_marker)
    s = s[:i] + s[j:]


rep('''let subsOsToken = { value: "", at: 0 };

''', "")
drop("async function subsOpenSubsToken(env) {")
drop("async function subsSearchOpenSubs(env, t) {")

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

''' + HUB_JS + '''
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

# ---------------------------------------------------------------- providers (stage 2): hardened OpenSubtitles, health, guards
rep("""    const token = await subsOpenSubsToken(env);
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
    bytes = new Uint8Array(await f.arrayBuffer());""", """    bytes = await osDownloadBytes(env, c.id);   // 18.2: session + base_url + quota handling (see subhub_providers.js)""", count=2)   # subsDownload + the discovery module's copy
# human subtitles first: AI-translated uploads are ranked below them (they are included by OpenSubtitles by default)
rep("  if (c.hi) s -= 6;\n", "  if (c.hi) s -= 6;\n  if (c.ai_translated) s -= 8;\n")
rep("score: c.score, dl: c.dl.startsWith(\"http\") ? \"\" : c.dl };", "score: c.score, ai_translated: Boolean(c.ai_translated), trusted: Boolean(c.trusted), dl: c.dl.startsWith(\"http\") ? \"\" : c.dl };")
# SubDL: record whether a real request worked
rep("""    if (!res.ok || !data || data.status === false) { lastError = (data && (data.error || data.message)) || "SubDL " + res.status; continue; }""",
    """    if (!res.ok || !data || data.status === false) { lastError = (data && (data.error || data.message)) || "SubDL " + res.status; await subhubNote(env, "subdl", false, lastError); continue; }
    await subhubNote(env, "subdl", true, "search");""")
# never replace an existing subtitle silently
rep("""  out.key = key;
  out.url = await subsStore(env, key, got.srt);""", """  out.key = key;
  await subsGuardOverwrite(env, t.item, key, input);   // 18.2: existing / approved subtitles are never replaced silently
  out.url = await subsStore(env, key, got.srt);""")
rep("""      const r = await subsDoFetch(env, { item_id: ep.id, mode: "upload" }, list);""", """      const r = await subsDoFetch(env, { item_id: ep.id, mode: "upload", overwrite: overwrite }, list);""")

# 18.3) GET /movies: conditional (ETag / If-None-Match -> 304), see returnR2JsonConditional in subhub_providers.js
rep("""      if (request.method === "GET" && path === "/movies") {
        return returnR2JsonObject(env, MOVIES_OBJECT, []);""","""      if (request.method === "GET" && path === "/movies") {
        return returnR2JsonConditional(request, env, MOVIES_OBJECT, []);""")

open(dst, "w", encoding="utf-8").write(s)
print("patched:", dst)
