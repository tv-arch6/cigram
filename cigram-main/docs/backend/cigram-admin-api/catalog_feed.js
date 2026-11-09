// ============================================================================================================
// CATALOG FEED (18.4) — revision tracking + incremental change feed for movies.json
// ============================================================================================================
// Why: the app downloaded the whole catalogue (5.7 MB, ~5,600 records) to learn that one episode was added.
// What this adds (additive; every existing route behaves as before):
//   GET /catalog/manifest             { ok, schema, rev, records, ids_digest, etag, updated_at, window_from, max_page }
//   GET /catalog/changes?since=<rev>  { ok, from, to, more, upserts:[record], removed:[id], head:{rev,records,ids_digest,etag} }
//                                     or { ok, full:true, reason } when the client must download /movies instead
// Both answer ETag + If-None-Match (304) and `cache-control: no-cache` (clients revalidate; the answer is tiny).
//
// How it stays correct:
//   * The feed does NOT trust the admin's change tracker. After every write (and when movies.json is found changed without a feed
//     entry: a dashboard upload, another Worker) it hashes every record, compares with the hashes of the previous revision
//     (catalog/hashes.json) and logs EXACTLY the records that differ. A change that cannot be proven (missing / out-of-step hashes, a bulk
//     import of more than 300 records) becomes a RESET revision: clients older than it download /movies once.
//     A reset is never a data loss, only a lost optimisation.
//   * mutateMovies() calls catalogFeedSync() after its compare-and-swap succeeded; head + bounded log live in ONE R2 object.
//   * A feed failure never fails the admin's write (try/catch) and never blocks /movies.
//   * `ids_digest` (sha256 of the sorted ids, one per line) + `records` let a client prove, after applying a delta, that its copy has
//     exactly the server's id set. The client falls back to the full download when they differ.
//   * Nothing is deleted from movies.json or from R2 by this code.
// Storage: adminKey(env, "catalog/feed.json") in the admin bucket (ids + records that are public in movies.json anyway).
// Rollback: remove the two routes and the call in mutateMovies (the object can stay); apps then use /movies exactly as before.

const CATALOG_FEED_FILE = "catalog/feed.json";
const CATALOG_HASH_FILE = "catalog/hashes.json";
const CATALOG_FEED_SCHEMA = 1;
const CATALOG_FEED_MAX_ENTRIES = 120;
const CATALOG_FEED_MAX_BYTES = 1500000;          // serialised size budget of the log part
const CATALOG_FEED_ENTRY_MAX_RECORDS = 300;      // a bigger single change (bulk import) is stored as a reset
const CATALOG_FEED_PAGE = 300;                   // upserts + removals per /catalog/changes answer
const CATALOG_FEED_SETTLE_MS = 20000;            // a movies.json younger than this may still be followed by its own feed write

function catalogIdOf(entry) {
  return entry && typeof entry === "object" ? cleanString(entry.id) : "";
}

function catalogIdList(list) {
  const out = [];
  for (const e of list || []) {
    const id = catalogIdOf(e);
    if (id) out.push(id);
  }
  return out;
}

async function catalogIdsDigest(ids) {
  const sorted = ids.slice().sort();                                   // UTF-16 code-unit order, same as Java String.compareTo
  const bytes = new TextEncoder().encode(sorted.join("\n"));
  const hash = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function catalogFeedEmpty() {
  return { schema: CATALOG_FEED_SCHEMA, rev: 0, etag: "", records: 0, ids_digest: "", updated_at: 0, window_from: 0, entries: [] };
}

async function catalogFeedLoad(env) {
  const bucket = adminBucket(env);
  const key = adminKey(env, CATALOG_FEED_FILE);
  const obj = await bucket.get(key);
  if (!obj) return { feed: null, etag: null };
  try {
    const feed = JSON.parse(await obj.text());
    if (!feed || typeof feed !== "object" || !Array.isArray(feed.entries) || !Number.isFinite(feed.rev)) return { feed: null, etag: obj.etag || null, corrupt: true };
    return { feed, etag: obj.etag || null };
  } catch {
    return { feed: null, etag: obj.etag || null, corrupt: true };
  }
}

/** Compare-and-swap of the single feed object. `etag` null = it must not exist yet. */
async function catalogFeedSave(env, feed, etag) {
  const bucket = adminBucket(env);
  const key = adminKey(env, CATALOG_FEED_FILE);
  // keep the log inside its budget: drop the OLDEST entries first and move window_from forward
  const size = () => JSON.stringify(feed.entries).length;
  while (feed.entries.length > CATALOG_FEED_MAX_ENTRIES || (feed.entries.length > 1 && size() > CATALOG_FEED_MAX_BYTES)) {
    const dropped = feed.entries.shift();
    feed.window_from = Math.max(feed.window_from, dropped.rev);
  }
  const options = { httpMetadata: { contentType: "application/json; charset=utf-8", cacheControl: "no-store" } };
  options.onlyIf = etag ? { etagMatches: etag } : { etagDoesNotMatch: "*" };
  const result = await bucket.put(key, JSON.stringify(feed), options);
  return result !== null && result !== undefined;
}

/** 53-bit string hash (cyrb53): synchronous and fast; used only to detect that a record changed, never for security. */
function catalogHash53(str) {
  let h1 = 0xdeadbeef, h2 = 0x41c6ce57;
  for (let i = 0; i < str.length; i++) {
    const ch = str.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return (4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(36);
}

/** id -> { hash, record } for the list (the last record of a duplicated id wins; the client dedupes the same way). */
function catalogIndexList(list) {
  const map = new Map();
  for (const e of list || []) {
    const id = catalogIdOf(e);
    if (!id) continue;
    map.set(id, { hash: catalogHash53(JSON.stringify(e)), record: e });
  }
  return map;
}

async function catalogHashesLoad(env) {
  const obj = await adminBucket(env).get(adminKey(env, CATALOG_HASH_FILE));
  if (!obj) return { doc: null, etag: null };
  try {
    const doc = JSON.parse(await obj.text());
    if (!doc || typeof doc !== "object" || typeof doc.h !== "object" || !Number.isFinite(doc.rev)) return { doc: null, etag: obj.etag || null };
    return { doc, etag: obj.etag || null };
  } catch {
    return { doc: null, etag: obj.etag || null };
  }
}

async function catalogHashesSave(env, doc, etag) {
  const options = { httpMetadata: { contentType: "application/json; charset=utf-8", cacheControl: "no-store" } };
  options.onlyIf = etag ? { etagMatches: etag } : { etagDoesNotMatch: "*" };
  const result = await adminBucket(env).put(adminKey(env, CATALOG_HASH_FILE), JSON.stringify(doc), options);
  return result !== null && result !== undefined;
}

/**
 * Makes the feed describe `list` (the current content of movies.json, whose etag is `etagNow`).
 * Safe to call at any time, from any number of requests: the work is idempotent and every write is a compare-and-swap.
 * Returns the feed that is current afterwards, or null when it could not be updated (the caller then serves /movies as before).
 */
async function catalogFeedSync(env, list, etagNow, op) {
  try {
    const cur = catalogIndexList(list);
    const digest = await catalogIdsDigest(Array.from(cur.keys()));
    for (let attempt = 0; attempt < 5; attempt++) {
      const lf = await catalogFeedLoad(env);
      const lh = await catalogHashesLoad(env);
      const feed = lf.feed || catalogFeedEmpty();
      const next = {};
      for (const [id, v] of cur) next[id] = v.hash;
      const up = [], rm = [];
      let resetWhy = "";
      if (!lf.feed) resetWhy = "bootstrap";
      else if (!lh.doc || lh.doc.rev !== feed.rev) resetWhy = "hash_mismatch";
      else {
        const prev = lh.doc.h;
        for (const [id, v] of cur) if (prev[id] !== v.hash) up.push(v.record);
        for (const id of Object.keys(prev)) if (!cur.has(id)) rm.push(id);
        if (up.length + rm.length > CATALOG_FEED_ENTRY_MAX_RECORDS) resetWhy = "bulk";
      }
      if (!resetWhy && up.length + rm.length === 0) {
        if (feed.etag === etagNow) return feed;
        feed.etag = etagNow;                                      // e.g. a config-only write: head etag follows, no revision
        feed.updated_at = Date.now();
        if (await catalogFeedSave(env, feed, lf.etag)) return feed;
      } else {
        const rev = feed.rev + 1;
        if (resetWhy) {
          feed.entries.push({ rev, at: Date.now(), op: "reset", reset: true, why: resetWhy, up: [], rm: [] });
          feed.window_from = rev;                                  // nobody older than `rev` can be served a delta
        } else {
          feed.entries.push({ rev, at: Date.now(), op: cleanString(op).slice(0, 40), up, rm });
        }
        feed.rev = rev;
        feed.records = cur.size;
        feed.ids_digest = digest;
        feed.etag = etagNow;
        feed.updated_at = Date.now();
        // hashes first (CAS), then the feed (CAS): a crash between the two leaves hashes ahead of the feed -> the next sync resets
        if (await catalogHashesSave(env, { schema: 1, rev, etag: etagNow, h: next }, lh.etag) && await catalogFeedSave(env, feed, lf.etag)) return feed;
      }
      await sleep(30 * (attempt + 1) + Math.floor(Math.random() * 40));
    }
  } catch (error) {
    console.log(JSON.stringify({ evt: "catalog_feed", ok: false, where: "sync", error: String(error && error.message || error).slice(0, 160) }));
  }
  return null;
}

/** Called by mutateMovies after its compare-and-swap write succeeded. Never throws. */
async function catalogFeedAfterWrite(env, list, op) {
  try {
    const head = await env.MOVIES_BUCKET.head(MOVIES_OBJECT);
    await catalogFeedSync(env, list, (head && head.etag) || "", op);
  } catch (error) {
    console.log(JSON.stringify({ evt: "catalog_feed", ok: false, where: "after_write", error: String(error && error.message || error).slice(0, 160) }));
  }
}

/**
 * Makes sure the feed describes the CURRENT movies.json before it is read. Returns { feed, busy } where busy = a very recent
 * write whose own feed entry may still be on its way (the caller answers "try again shortly", it never declares a reset for it).
 */
async function catalogFeedEnsure(env) {
  const bucket = env.MOVIES_BUCKET;
  const loaded = await catalogFeedLoad(env);
  const head = await bucket.head(MOVIES_OBJECT);
  if (!head) return { feed: loaded.feed, missing: true };
  if (loaded.feed && loaded.feed.etag && loaded.feed.etag === head.etag) return { feed: loaded.feed };
  const age = head.uploaded ? Date.now() - new Date(head.uploaded).getTime() : CATALOG_FEED_SETTLE_MS + 1;
  if (loaded.feed && age < CATALOG_FEED_SETTLE_MS) return { feed: loaded.feed, busy: true };
  const obj = await bucket.get(MOVIES_OBJECT);
  if (!obj) return { feed: loaded.feed, missing: true };
  let doc;
  try { doc = JSON.parse(await obj.text()); } catch { return { feed: loaded.feed, unreadable: true }; }
  const feed = await catalogFeedSync(env, getWritableMoviesArray(doc).list, obj.etag || head.etag, "foreign_write");
  return feed ? { feed } : { feed: loaded.feed, unreadable: !loaded.feed };
}

function catalogFeedHeaders(extra) {
  return corsHeaders(Object.assign({ "content-type": "application/json; charset=utf-8", "cache-control": "no-cache", "access-control-expose-headers": "ETag" }, extra || {}));
}

function catalogFeedReply(request, body, tag) {
  const etag = '"' + tag + '"';
  const inm = (request.headers.get("if-none-match") || "").trim();
  if (inm && inm.split(",").map((s) => s.trim().replace(/^W\//, "")).includes(etag)) {
    return new Response(null, { status: 304, headers: catalogFeedHeaders({ etag }) });
  }
  return new Response(JSON.stringify(body), { status: 200, headers: catalogFeedHeaders({ etag }) });
}

function catalogHead(feed) {
  return { rev: feed.rev, records: feed.records, ids_digest: feed.ids_digest, etag: feed.etag };
}

async function handleCatalogManifest(request, env) {
  try {
    const { feed, busy, missing, unreadable } = await catalogFeedEnsure(env);
    if (missing || !feed) return json({ ok: false, error: "catalogue_missing" }, 404);
    if (unreadable) return json({ ok: false, error: "catalogue_unreadable" }, 503);
    const body = Object.assign({ ok: true, schema: CATALOG_FEED_SCHEMA }, catalogHead(feed), {
      updated_at: feed.updated_at, window_from: feed.window_from, max_page: CATALOG_FEED_PAGE,
    });
    if (busy) { body.busy = true; return new Response(JSON.stringify(body), { status: 200, headers: catalogFeedHeaders({ "cache-control": "no-store" }) }); }
    return catalogFeedReply(request, body, "cat-" + feed.rev + "-" + feed.records);
  } catch (error) {
    return json({ ok: false, error: "feed_error" }, 500);
  }
}

async function handleCatalogChanges(request, env, url) {
  try {
    const since = Number.parseInt(url.searchParams.get("since") || "", 10);
    if (!Number.isFinite(since) || since < 0) return json({ ok: false, error: "bad_since" }, 400);
    const page = Math.max(20, Math.min(CATALOG_FEED_PAGE, Number.parseInt(url.searchParams.get("max") || "", 10) || CATALOG_FEED_PAGE));
    const { feed, busy, missing, unreadable } = await catalogFeedEnsure(env);
    if (missing || !feed) return json({ ok: false, error: "catalogue_missing" }, 404);
    if (unreadable) return json({ ok: false, error: "catalogue_unreadable" }, 503);
    const fullReply = (reason) => catalogFeedReply(request, { ok: true, full: true, reason, head: catalogHead(feed) }, "full-" + reason + "-" + feed.rev);
    if (busy) return new Response(JSON.stringify({ ok: true, busy: true, head: catalogHead(feed) }), { status: 200, headers: catalogFeedHeaders({ "cache-control": "no-store" }) });
    if (since === feed.rev) return catalogFeedReply(request, { ok: true, from: since, to: since, more: false, upserts: [], removed: [], head: catalogHead(feed) }, "chg-" + since + "-" + since);
    if (since > feed.rev) return fullReply("ahead");
    if (since < feed.window_from) return fullReply("window");
    const take = feed.entries.filter((e) => e.rev > since).sort((a, b) => a.rev - b.rev);
    if (take.length === 0 || take[0].rev !== since + 1) return fullReply("gap");
    const latest = new Map();                      // id -> record | null (removed); later revisions win
    let to = since, count = 0;
    for (const e of take) {
      if (e.reset) return fullReply("reset");
      const size = (e.up ? e.up.length : 0) + (e.rm ? e.rm.length : 0);
      if (to > since && count + size > page) break;
      for (const r of e.up || []) latest.set(catalogIdOf(r), r);
      for (const id of e.rm || []) latest.set(String(id), null);
      count += size;
      to = e.rev;
    }
    const upserts = [], removed = [];
    for (const [id, rec] of latest) { if (rec) upserts.push(rec); else removed.push(id); }
    return catalogFeedReply(request, { ok: true, from: since, to, more: to < feed.rev, upserts, removed, head: catalogHead(feed) }, "chg-" + since + "-" + to + "-" + feed.rev);
  } catch (error) {
    return json({ ok: false, error: "feed_error" }, 500);
  }
}
