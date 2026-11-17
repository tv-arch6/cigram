#!/usr/bin/env python3
"""3.0.2 — minimal patch: adds ONLY the «بلّغ المدير» routes to the LIVE cigram-admin-api worker.js.

Why: the admin app's «قنوات تحتاج رابطاً» asks  GET /admin/channel-reports  and the live Worker answers
     404 {"ok":false,"error":"not_found"}  because the route is not deployed (probe of 2026-11-14).

Adds (nothing else is touched):
  POST /channel-reports                 public  — a viewer's «بلّغ المدير» (counted once per phone, Telegram note on the first report)
  GET  /admin/channel-reports           admin   — the open reports, most reported first
  POST /admin/channel-reports/resolve   admin   — closes a report after the link was replaced

Usage:  python3 apply_patch.py <live worker.js> <output worker.js>
Every anchor must match exactly once; otherwise it stops and changes nothing (the live code moved: use the full bundle or ask the maintainer).
Nothing is deployed by this script: review the diff, run  node ../tests/channel_reports_test.mjs <output worker.js>, then deploy yourself.
"""
import os
import sys

src, dst = sys.argv[1], sys.argv[2]
BLOCK = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "channel_reports_block.js"), encoding="utf-8").read()
s = open(src, encoding="utf-8").read()


def rep(old, new):
    global s
    n = s.count(old)
    if n != 1:
        raise SystemExit("anchor found %d times (expected 1): %r" % (n, old[:90]))
    s = s.replace(old, new)


if "CHANNEL_REPORT_ROUTES" in s:
    raise SystemExit("already patched: this worker.js already contains CHANNEL_REPORT_ROUTES")

# 1. the handlers + route table, placed just before the PUSH section
rep('''// =============================================================================
// PUSH (v9)''', BLOCK + '''
// =============================================================================
// PUSH (v9)''')

# 2. the router: same position as in the full bundle (before the requests routes)
rep('''      const requestsHandler = REQUESTS_ROUTES[request.method + " " + path];''',
    '''      const channelReportHandler = CHANNEL_REPORT_ROUTES[request.method + " " + path];
      if (channelReportHandler) return channelReportHandler(request, env, url);
      const requestsHandler = REQUESTS_ROUTES[request.method + " " + path];''')

# 3. the route list answered by the index
rep('''            .concat(Object.keys(REQUESTS_ROUTES))''',
    '''            .concat(Object.keys(REQUESTS_ROUTES))
            .concat(Object.keys(CHANNEL_REPORT_ROUTES))''')

open(dst, "w", encoding="utf-8").write(s)
print("patched:", dst, "(%d bytes)" % len(s))
