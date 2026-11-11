#!/bin/bash
# Run BEFORE deploying 19.0 (and any time you want an offline copy). Read-only: it only GETs.
#   usage: ADMIN_TOKEN=<owner token> ./scripts/preflight_backup.sh [out_dir]
# 1) the public catalogue (GET /movies) -> movies.json.gz + sha256 (record / type / duplicate-id summary printed)
# 2) every chat conversation, archived ones included (GET /admin/chat/threads?archived=all + GET /admin/chat/export) -> chat/<thread>.json
#    + a manifest with the message counts and sha256 of every file. Needs the OWNER token (export is owner-only).
# The token is read from the environment only; it is never written to a file or echoed.
set -euo pipefail
OUT=${1:-backup-$(date -u +%Y%m%dT%H%M%SZ)}
ADMIN_API=${ADMIN_API:-https://cigram-admin-api.wwq-mixtv.workers.dev}
ADS_API=${ADS_API:-https://cigram-ads-api.wwq-mixtv.workers.dev}
mkdir -p "$OUT/chat"
echo "== catalogue"
curl -fsS --compressed "$ADMIN_API/movies" -o "$OUT/movies.json"
python3 - "$OUT/movies.json" <<'PY'
import json, sys, collections
d = json.load(open(sys.argv[1], encoding="utf-8")); rows = d["movies"]
ids = [r["id"] for r in rows]
print("records", len(rows), dict(collections.Counter(r["type"] for r in rows)), "duplicate ids", len(ids) - len(set(ids)))
if not rows: sys.exit("EMPTY catalogue answer: NOT a valid backup")
PY
gzip -9 -f "$OUT/movies.json"
( cd "$OUT" && sha256sum movies.json.gz > SHA256SUMS )
if [ -z "${ADMIN_TOKEN:-}" ]; then echo "ADMIN_TOKEN not set: chat export skipped"; exit 0; fi
echo "== chat"
H="Authorization: Bearer $ADMIN_TOKEN"
curl -fsS -H "$H" "$ADS_API/admin/chat/threads?archived=all&limit=300" -o "$OUT/threads.json"
python3 - "$OUT" "$ADS_API" <<'PY'
import json, os, subprocess, sys, hashlib
out, api = sys.argv[1], sys.argv[2]
tok = os.environ["ADMIN_TOKEN"]
threads = json.load(open(os.path.join(out, "threads.json"), encoding="utf-8")).get("threads", [])
manifest = []
for t in threads:
    r = subprocess.run(["curl", "-fsS", "-H", "Authorization: Bearer " + tok, api + "/admin/chat/export?thread=" + t["id"]], capture_output=True)
    if r.returncode != 0:
        manifest.append({"thread": t["id"], "error": "export failed (owner token needed)"}); continue
    path = os.path.join(out, "chat", t["id"] + ".json"); open(path, "wb").write(r.stdout)
    n = len(json.loads(r.stdout).get("messages", []))
    manifest.append({"thread": t["id"], "messages": n, "last_seq": t.get("last_seq"), "sha256": hashlib.sha256(r.stdout).hexdigest()})
json.dump(manifest, open(os.path.join(out, "chat-manifest.json"), "w"), indent=1)
bad = [m for m in manifest if "error" in m or m["messages"] < (m.get("last_seq") or 0) - 0]
print("threads", len(threads), "exported", sum(1 for m in manifest if "error" not in m), "problems", len(bad))
PY
echo "backup written to $OUT (keep it somewhere private: chat files contain customer messages)"
