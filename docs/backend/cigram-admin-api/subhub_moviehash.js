// ----------------------------------------------------------------------------- 19.4 OpenSubtitles moviehash (byte-exact release match)
// A name / id search finds subtitles for the right TITLE, not for the right RELEASE: every encode (WEB-DL, BluRay, cuts,
// intros) shifts the cues by a different offset. OpenSubtitles identifies the exact release with the "moviehash"
// (OSDb hash), computed from the video bytes themselves:
//     hash = (file size + Σ uint64_le(first 64 KiB) + Σ uint64_le(last 64 KiB))  mod 2^64   -> 16 lowercase hex chars
// The video lives in R2 (MOVIES_BUCKET). Only TWO ranged reads of 64 KiB + one HEAD are needed, never the whole file,
// and nothing is uploaded anywhere: the hash is sent as the `moviehash` search parameter of GET /subtitles, and the
// rows whose attributes.moviehash_match is true are ranked first (their timing was produced on the same bytes).
// The result is cached per R2 key + etag (memory + admin bucket), so a replaced video is hashed again automatically.
// Never fatal: a video outside R2, a small file or an R2 error only means "search without the hash" (state is reported).

const MH_CHUNK = 65536;
const MH_MIN_SIZE = MH_CHUNK * 2;
const MH_MASK = (1n << 64n) - 1n;
const MH_FILE = "subhub/moviehash.json";
const MH_MAX_ENTRIES = 20000;
const MH_MEM = new Map();                 // r2 key -> { etag, size, hash }

/** Σ of little-endian uint64 words (mod 2^64). The chunk is always a multiple of 8 bytes. */
function mhSum(bytes, acc) {
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  for (let i = 0; i + 8 <= bytes.byteLength; i += 8) acc = (acc + dv.getBigUint64(i, true)) & MH_MASK;
  return acc;
}

/** The OSDb hash from the size and the two 64 KiB edges (exported for the tests through the module scope). */
function mhCompute(size, head, tail) {
  let h = BigInt(size) & MH_MASK;
  h = mhSum(head, h);
  h = mhSum(tail, h);
  return h.toString(16).padStart(16, "0");
}

/** Hosts that serve MOVIES_BUCKET objects: the r2.dev base + optional custom domains (var MOVIES_PUBLIC_HOSTS, comma list). */
function mhPublicHosts(env) {
  const out = new Set();
  try { out.add(new URL(R2_PUBLIC_BASE).hostname.toLowerCase()); } catch { /* constant */ }
  for (const h of cleanString(env && env.MOVIES_PUBLIC_HOSTS).split(",")) if (h.trim()) out.add(h.trim().toLowerCase());
  return out;
}

/** R2 object key of a library item's video, or "" when the video is hosted elsewhere (vidbolt, embeds...). */
function mhKeyOfItem(env, item) {
  if (!item || typeof item !== "object") return "";
  const hosts = mhPublicHosts(env);
  for (const raw of [item.video, item.link]) {
    const v = cleanString(raw);
    if (!v) continue;
    let u;
    try { u = new URL(v); } catch { continue; }
    if (u.protocol !== "https:" || !hosts.has(u.hostname.toLowerCase())) continue;
    const parts = u.pathname.replace(/^\/+/, "").split("/").filter(Boolean);
    if (!parts.length) continue;
    try {
      const key = parts.map(decodeURIComponent).join("/");
      if (key && !key.includes("..") && key.length <= 1024) return key;
    } catch { /* malformed escape */ }
  }
  return "";
}

async function mhReadRange(bucket, key, offset) {
  const obj = await bucket.get(key, { range: { offset, length: MH_CHUNK } });
  if (!obj || typeof obj.arrayBuffer !== "function") throw new Error("range_missing");
  const bytes = new Uint8Array(await obj.arrayBuffer());
  if (bytes.byteLength !== MH_CHUNK) throw new Error("range_length");   // never hash a partial / whole-object answer
  return bytes;
}

async function mhCacheRead(env, key, etag) {
  const m = MH_MEM.get(key);
  if (m && m.etag === etag) return m;
  try {
    const doc = await wnRead(env, MH_FILE, { items: {} });
    const rec = doc && doc.items && doc.items[key];
    if (rec && rec.etag === etag && /^[0-9a-f]{16}$/.test(rec.hash)) { MH_MEM.set(key, rec); return rec; }
  } catch { /* cache is optional */ }
  return null;
}

async function mhCacheWrite(env, key, rec) {
  MH_MEM.set(key, rec);
  try {
    await wnMutate(env, MH_FILE, { items: {} }, (doc) => {
      doc.items = doc.items && typeof doc.items === "object" ? doc.items : {};
      doc.items[key] = rec;
      const keys = Object.keys(doc.items);
      if (keys.length > MH_MAX_ENTRIES) {
        keys.sort((a, b) => cleanString(doc.items[a].at).localeCompare(cleanString(doc.items[b].at)));
        for (const k of keys.slice(0, keys.length - MH_MAX_ENTRIES)) delete doc.items[k];
      }
      return { doc };
    });
  } catch { /* the memory copy still serves this isolate */ }
}

/**
 * Moviehash of one library item (movie or episode).
 * -> { state: ok | not_r2 | missing | too_small | error, key, size, moviehash, cached }
 */
async function subsMovieHashOfItem(env, item, { fresh = false } = {}) {
  const key = mhKeyOfItem(env, item);
  if (!key) return { state: "not_r2", key: "", size: 0, moviehash: "", cached: false };
  const bucket = env.MOVIES_BUCKET;
  try {
    const head = await bucket.head(key);
    if (!head) return { state: "missing", key, size: 0, moviehash: "", cached: false };
    const size = Number(head.size) || 0;
    const etag = cleanString(head.etag || head.httpEtag);
    if (size < MH_MIN_SIZE) return { state: "too_small", key, size, moviehash: "", cached: false };
    if (!fresh && etag) {
      const hit = await mhCacheRead(env, key, etag);
      if (hit && Number(hit.size) === size) return { state: "ok", key, size, moviehash: hit.hash, cached: true };
    }
    const [first, last] = await Promise.all([mhReadRange(bucket, key, 0), mhReadRange(bucket, key, size - MH_CHUNK)]);
    const hash = mhCompute(size, first, last);
    if (etag) await mhCacheWrite(env, key, { etag, size, hash, at: new Date().toISOString() });
    return { state: "ok", key, size, moviehash: hash, cached: false };
  } catch {
    return { state: "error", key, size: 0, moviehash: "", cached: false };
  }
}

/** Fills t.moviehash / t.moviebytesize / t.moviehash_state for a library target. Never throws, runs once per target. */
async function subsAttachMovieHash(env, t) {
  if (!t || t.moviehash_state) return t;
  const item = t.item;
  // a series header has no single video: only movies and episodes are hashed
  if (!item || (typeof isSeriesHeader === "function" && isSeriesHeader(item))) { t.moviehash_state = "no_video"; return t; }
  const r = await subsMovieHashOfItem(env, item);
  t.moviehash = r.moviehash;
  t.moviebytesize = r.size;
  t.moviehash_state = r.state;
  return t;
}

/** GET /admin/subhub/moviehash?item_id=…[&fresh=1] — diagnostics: what the search will send for this title. */
async function handleSubhubMovieHash(request, env, url) {
  wnRate("subhub_moviehash", 120);
  const id = cleanString(url.searchParams.get("item_id") || url.searchParams.get("id"));
  if (!id) throw new AdminError("item_id_required", "أرسل item_id.", 400);
  const list = await readMoviesList(env);
  const item = findById(list, id);
  if (!item) throw new AdminError("item_not_found", "العنصر غير موجود في المكتبة.", 404);
  if (isSeriesHeader(item)) throw new AdminError("no_single_video", "رأس المسلسل بلا فيديو واحد: احسب البصمة لكل حلقة.", 400);
  const r = await subsMovieHashOfItem(env, item, { fresh: truthy(url.searchParams.get("fresh")) });
  const why = {
    ok: "البصمة جاهزة: ستُرسل مع بحث OpenSubtitles وتُقدَّم الترجمات المطابقة بالبايت.",
    not_r2: "الفيديو ليس في R2: البحث يبقى بالمعرّف والإصدار فقط.",
    missing: "ملف الفيديو غير موجود في R2.",
    too_small: "الملف أصغر من 128KB: لا تُحسب البصمة.",
    error: "تعذرت قراءة R2 الآن: البحث يعمل بدون البصمة.",
  }[r.state];
  return okResponse({ item_id: id, state: r.state, key: r.key, size: r.size, moviehash: r.moviehash, cached: r.cached }, why);
}
