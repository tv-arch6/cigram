#!/bin/bash
# Builds the deployment-ready Worker bundles from the LIVE sources and runs every local test against them.
#   usage: ./build_deploy.sh <live cigram-admin-api worker.js> <live cigram-ads-api worker.js> <live sigram-channels-api worker.js>
# The live sources are read from the Cloudflare dashboard (Workers & Pages -> the worker -> Edit code -> copy) or with
# `wrangler` -- never from this repository: the patches refuse to apply when the live code differs from the 2026-11-08 snapshot.
set -euo pipefail
ADMIN_LIVE=${1:?live cigram-admin-api worker.js}
ADS_LIVE=${2:?live cigram-ads-api worker.js}
CHANNELS_LIVE=${3:?live sigram-channels-api worker.js}
here=$(cd "$(dirname "$0")" && pwd)
out=$here/deploy
rm -rf "$out"; mkdir -p "$out/cigram-admin-api" "$out/cigram-ads-api" "$out/sigram-channels-api"
python3 "$here/cigram-admin-api/apply_patch.py" "$ADMIN_LIVE" "$out/cigram-admin-api/worker.js"
python3 "$here/cigram-ads-api/apply_patch.py"   "$ADS_LIVE"   "$out/cigram-ads-api/worker.18.js"
# 19.0 (second stage): permanent history, targeted push, backups, race-free sequencing
python3 "$here/cigram-ads-api/apply_patch_19.py" "$out/cigram-ads-api/worker.18.js" "$out/cigram-ads-api/worker.js"
rm "$out/cigram-ads-api/worker.18.js"
python3 "$here/sigram-channels-api/apply_patch.py" "$CHANNELS_LIVE" "$out/sigram-channels-api/worker.js"
# the tests import ES modules: same bytes, .mjs extension
cp "$out/cigram-admin-api/worker.js" "$out/cigram-admin-api/worker.mjs"
cp "$out/cigram-ads-api/worker.js"   "$out/cigram-ads-api/worker.mjs"
cp "$out/sigram-channels-api/worker.js" "$out/sigram-channels-api/worker.mjs"
node --check "$out/cigram-admin-api/worker.mjs"
node --check "$out/cigram-ads-api/worker.mjs"
node --check "$out/sigram-channels-api/worker.mjs"
node "$here/tests/admin_subhub_test.mjs" "$out/cigram-admin-api/worker.mjs"
node "$here/tests/catalog_feed_test.mjs" "$out/cigram-admin-api/worker.mjs"
node "$here/tests/subhub_moviehash_test.mjs" "$out/cigram-admin-api/worker.mjs"
node "$here/tests/ads_support_test.mjs"  "$out/cigram-ads-api/worker.mjs"
node "$here/tests/ads_chat19_test.mjs"   "$out/cigram-ads-api/worker.mjs"
node "$here/tests/channels_conditional_test.mjs" "$out/sigram-channels-api/worker.mjs"
rm "$out/cigram-admin-api/worker.mjs" "$out/cigram-ads-api/worker.mjs" "$out/sigram-channels-api/worker.mjs"
diff -u "$ADMIN_LIVE" "$out/cigram-admin-api/worker.js" > "$here/cigram-admin-api/worker.diff" || true
diff -u "$ADS_LIVE"   "$out/cigram-ads-api/worker.js"   > "$here/cigram-ads-api/worker.diff"   || true
diff -u "$CHANNELS_LIVE" "$out/sigram-channels-api/worker.js" > "$here/sigram-channels-api/worker.diff" || true
( cd "$out" && sha256sum cigram-admin-api/worker.js cigram-ads-api/worker.js sigram-channels-api/worker.js > SHA256SUMS )
{ echo "live admin source:    $(sha256sum "$ADMIN_LIVE" | cut -d' ' -f1)"; echo "live ads source:      $(sha256sum "$ADS_LIVE" | cut -d' ' -f1)"; echo "live channels source: $(sha256sum "$CHANNELS_LIVE" | cut -d' ' -f1)"; } > "$out/LIVE-SOURCE-SHA256.txt"
echo "DEPLOY BUNDLES READY in $out"; cat "$out/SHA256SUMS"
