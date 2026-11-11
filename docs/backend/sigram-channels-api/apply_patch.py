#!/usr/bin/env python3
"""18.3 — additive patch for the LIVE sigram-channels-api worker.js: HTTP conditional GET for the public channel list.

Before: GET /channels read the R2 object, parsed it, re-serialised it and answered 200 with `cache-control: no-store` and NO
validator, so every Channels tab switch downloaded and re-parsed the full list.
After: the R2 object's ETag is sent as `ETag`; a request carrying `If-None-Match` with the current ETag is answered 304 without a
body (R2's own `onlyIf` evaluation), anything else gets the full list exactly as before. `cache-control: no-cache` makes
clients revalidate every time, so a channel added in the admin app is visible at once (no stale window).
The admin routes (add / update / delete) are untouched.

Usage:  python3 apply_patch.py <live worker.js> <output worker.js>   (every anchor must match exactly once)
Not deployed by the delivery: see ../DEPLOY.md.
"""
import sys

src, dst = sys.argv[1], sys.argv[2]
s = open(src, encoding="utf-8").read()


def rep(old, new):
    global s
    n = s.count(old)
    if n != 1:
        raise SystemExit("anchor found %d times (expected 1): %r" % (n, old[:90]))
    s = s.replace(old, new)


rep('''// ------------------------------------------------------------------- router
''', '''// 18.3 — conditional GET for the public list (see apply_patch.py)
async function handleList(request, env) {
  const object = await env.CHANNELS_BUCKET.get(JSON_KEY, { onlyIf: request.headers });
  if (!object) return json(await readDatabase(env));   // nothing saved yet: the same defaults as before
  const etag = object.httpEtag || "";
  const headers = Object.assign(
    { "content-type": "application/json; charset=UTF-8", "cache-control": "no-cache", "access-control-expose-headers": "ETag" },
    etag ? { etag } : {},
    CORS
  );
  if (object.body === undefined) return new Response(null, { status: 304, headers });   // If-None-Match matched: not modified
  let parsed;
  try {
    parsed = JSON.parse(await object.text());
  } catch {
    parsed = { categories: [] };
  }
  if (!Array.isArray(parsed.categories)) parsed.categories = [];
  parsed.ok = true;
  return new Response(JSON.stringify(parsed, null, 2), { status: 200, headers });
}

// ------------------------------------------------------------------- router
''')
rep('''      if (request.method === "GET" && url.pathname === "/channels") return json(await readDatabase(env));''',
    '''      if (request.method === "GET" && url.pathname === "/channels") return await handleList(request, env);''')
open(dst, "w", encoding="utf-8").write(s)
print("patched:", dst)
