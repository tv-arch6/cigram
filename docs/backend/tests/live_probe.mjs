// Runs the PATCHED Worker code against the REAL provider servers. No valid credentials exist here, so this proves
// transport, headers, paths and error handling only (never a successful authenticated call).
const mod = await import(process.argv[2]);
const worker = mod.default;
const m = new Map();
const bucket = { async get(k){ if(!m.has(k)) return null; const t=m.get(k); return {etag:"e",httpEtag:"e",text:async()=>t,json:async()=>JSON.parse(t)}; }, async head(k){return m.has(k)?{}:null;}, async put(k,v,o){ m.set(k,typeof v==="string"?v:new TextDecoder().decode(v)); return {etag:"e"}; }, async delete(k){m.delete(k);}, async list(){return {objects:[],truncated:false};} };
await bucket.put("movies.json", "[]");
const run = async (label, env, method, path, body) => {
  const r = await worker.fetch(new Request("https://admin.test" + path, { method, headers: { "content-type": "application/json", authorization: "Bearer adm" }, body: body ? JSON.stringify(body) : undefined }), Object.assign({ MOVIES_BUCKET: bucket, ADMIN_TOKEN: "adm" }, env), { waitUntil(){} });
  const j = await r.json(); const d = j.data || j;
  console.log(label.padEnd(46), r.status, JSON.stringify(d).slice(0, 330));
};
console.log("== REAL SERVERS, no valid credentials ==");
await run("translation options (public endpoint)", { OPENSUBTITLES_API_KEY: "not-a-real-key" }, "GET", "/admin/subhub/translate/options");
await run("verify opensubtitles, rejected key", { OPENSUBTITLES_API_KEY: "not-a-real-key" }, "POST", "/admin/subhub/providers/verify", { provider: "opensubtitles" });
await run("verify opensubtitles, no key", {}, "POST", "/admin/subhub/providers/verify", { provider: "opensubtitles" });
await run("verify subdl, rejected key", { SUBDL_API_KEY: "not-a-real-key" }, "POST", "/admin/subhub/providers/verify", { provider: "subdl" });
await run("verify tmdb, rejected key", { TMDB_API_KEY: "not-a-real-key" }, "POST", "/admin/subhub/providers/verify", { provider: "tmdb" });
await run("verify opensubtitles_ai, rejected key", { OPENSUBTITLES_API_KEY: "not-a-real-key", OPENSUBTITLES_USERNAME: "u", OPENSUBTITLES_PASSWORD: "p" }, "POST", "/admin/subhub/providers/verify", { provider: "opensubtitles_ai" });
