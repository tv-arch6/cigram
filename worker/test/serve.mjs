// Local test server: the REAL Worker code (worker/dist) on an in-memory R2, with mocked TMDB / VidBolt / subtitle providers.
// Used by the Android client tests (admin/app/src/test) so the Java pipeline talks to the same HTTP contract the app uses.
//   node worker/test/serve.mjs            -> prints "PORT=<n>" and serves until killed
// Test-only control routes (not part of the Worker):  GET /__library   POST /__scenario {..}   POST /__reset
import http from "node:http";
import { loadWorker, makeEnv, createWorld, pending } from "./helpers.mjs";

let worker = await loadWorker();
let env;
let world;

async function reset(seed = {}) {
  worker = await loadWorker(); // fresh isolate state (rate limits, TMDB / probe caches)
  env = makeEnv();
  env.MOVIES_BUCKET.seedJson("cigram_home_sections.json", { version: 1, sections: [
    { id: "action", slug: "action", name: "أكشن", type: "movie", order: 1, enabled: true, show_home: true },
    { id: "drama", slug: "drama", name: "دراما", type: "series", order: 2, enabled: true, show_home: true },
  ] });
  world = createWorld(seed);
  world.install();
}
await reset();

const server = http.createServer(async (req, res) => {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const body = Buffer.concat(chunks);
  const url = new URL(req.url, "http://127.0.0.1");
  const send = (status, text, type = "application/json") => { res.writeHead(status, { "content-type": type }); res.end(text); };
  try {
    if (url.pathname === "/__library") return send(200, env.MOVIES_BUCKET.text("movies.json") || "[]");
    if (url.pathname === "/__reset") { await reset(); return send(200, "{}"); }
    if (url.pathname === "/__scenario") {
      const s = JSON.parse(body.toString() || "{}");
      const missing = new Set(s.vidbolt_404 || []);
      const blocked = new Set(s.vidbolt_403 || []);
      if (missing.size || blocked.size) world.state.vidbolt = (u) => (missing.has(u.pathname) ? { status: 404 } : blocked.has(u.pathname) ? { status: 403 } : { status: 200 });
      if (s.subdl) world.state.subdl = s.subdl;
      return send(200, "{}");
    }
    const headers = {};
    for (const [k, v] of Object.entries(req.headers)) if (typeof v === "string") headers[k] = v;
    const p = pending();
    const r = await worker.fetch(new Request("https://worker.test" + req.url, { method: req.method, headers, body: ["GET", "HEAD"].includes(req.method) ? undefined : body }), env, p.ctx);
    await p.flush();
    const text = await r.text();
    res.writeHead(r.status, { "content-type": r.headers.get("content-type") || "application/json" });
    res.end(text);
  } catch (error) {
    send(500, JSON.stringify({ ok: false, error: "test_server_error", message: String(error && error.stack) }));
  }
});
server.listen(0, "127.0.0.1", () => console.log("PORT=" + server.address().port));
