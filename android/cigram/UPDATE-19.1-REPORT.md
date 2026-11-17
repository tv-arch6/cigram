# CIGRAM 19.1 — duplicate chat bubbles, Home sections that appear at once, smoother Home (main 18.5.1 / code 93, admin 2.2.1 / code 11)

Written after the 18.5.0 test on a phone (two screenshots, 2026-11-10 00:05–00:06). Same text in both projects.

## 0. Read this first
| Statement | Status |
|---|---|
| Chat works end to end with the live Worker after adding the `AUTH_API` service binding (user sent, developer received, developer replied, user received, double ticks) | **OBSERVED by you** (screenshots) |
| Both apps build from clean; JVM tests: main **320** (160 per flavour), admin **99**, Worker suites **103**; 0 failed, 0 skipped | **VERIFIED** (numbers in §5) |
| The sections pipeline produces **byte-identical** results after the optimisation (canonical SHA-256 of every section and every card field on the live 5,686-record catalogue: `dca43ff7…` before and after) | **VERIFIED** |
| Timings below | **desktop JVM only**; a phone is several times slower. Nothing was measured on a phone (no device here) |
| The two Home changes that matter most (stale-first sections, lazy rows) are Activity code | **Compile-verified + their pure parts unit tested; NOT seen running on a phone.** Please test and tell me what you see (§6) |
| The Worker change (`client_id` in message views) | Tests pass; **not deployed** (the apps' fix does not need it: it works against the live Worker) |

## 1. Problem 1 — the same message twice (clock icon) in both apps
**Cause (found in both `applyServer` / `apply`).** When you send, the server answers the HTTP request AND pushes the new message over the WebSocket. The WebSocket copy usually arrives first and has no way to say "I am the message you are sending": it was added as a new bubble. When the HTTP answer arrived, the code found the bubble by sequence number, replaced it and `continue`d, **never removing the local pending copy**. The pending copy then stayed (clock icon / «…»), was saved with the unsent messages and was restored and re-sent at every start; the server answered "duplicate" each time and the leftover stayed.
**Fix.**
* Both apps: a pending copy is removed as soon as ANY copy of the same message arrives (by `client_id`, before the sequence lookup); the confirmed copy remembers its `client_id` (also saved), and at start a pending copy whose confirmed copy is already stored is dropped.
* Your existing stuck bubbles disappear by themselves: at the next open they are re-sent, the server answers "duplicate" with the original message, and the pending copy is removed.
* Worker (`apply_patch_19.py`, bundle `75bad53…`): every message view now carries its `client_id`, so even the WebSocket copy matches instantly. Test: `ads_chat19_test` (27).

## 2. Problem 2 — Home sections take 15–23 s (and again after leaving and re-entering)
**What takes the time** (stack sampler over the real catalogue, 8 runs): ~85% of `collectDynamicSectionContent` is `org.json` `toString()`/parse — every record (5,686, 97% of them episodes) was cloned through a JSON string 3–4 times. On top of that:
* the saved sections snapshot only counted when the catalogue text was **identical**; the administrator changes the catalogue many times a day, so most starts rebuilt all sections from the 5.7 MB text;
* the sections were rebuilt again every time the Home screen was recreated in the same process (leaving and re-entering the app);
* every cold start re-prepared (re-parsed, re-sorted, re-indexed) the saved catalogue although it already was the prepared output;
* **my own 19.0 additions made it worse**: the discovery rows copied thousands of episode rows (+1.4 s on a desktop) and the "unchanged Home" signature hashed **12 MB** of episode lists **on the main thread** (0.3 s on a desktop = seconds on a phone).

**Fix.**
| Change | Effect (desktop, real catalogue) |
|---|---|
| Shallow copies instead of JSON round trips (identical output, digest check) | grouping **1,103 → 295 ms** |
| Discovery rows reuse the cards that already exist in the administrator's sections | **1,423 → ~110 ms** |
| Render signature over card fields only (no episode lists) | no longer 12 MB on the main thread |
| `customSectionClaims` benefits from the same copies | 1,068 → 338 ms |
| **Stale-while-revalidate:** the last snapshot is drawn at once even when the catalogue changed since (`snapStale`), then the real sections are computed at background priority and drawn only if something visible differs | Home sections appear in the first moments, like the carousel |
| Per-process cache of the sections (≤80 trimmed cards per section, as the snapshot) | a second render of the same revision **0 ms** (test) |
| A saved, already-prepared catalogue is not prepared again at cold start (hash + saved index prove it) | removes ~2 s desktop / many seconds of one phone core at every start |
| Fixed rows (`HomePrep`) snapshot of the previous app version is accepted | no waiting after an app update |
Only a very first start with no snapshot at all still computes the sections once (now ~4× faster; a few seconds on a phone, not 15–23).

## 3. Smooth scrolling / browsing
* The sections beyond the first screen (4) are built 1–2 per frame instead of all in the first frame (one long stall when Home appeared).
* A row far below the screen no longer adds cards every 75 ms in the background (continuous layout passes while you scroll); it finishes when it comes near the screen, or at once (6 cards per 30 ms) when you put a finger on that row.
* A re-render over a visible Home no longer fades the block in again (that was the "blink").
* Kept from before: the image queue (3 parallel downloads, single flight), yielding to scrolling, anchoring of rows.
Not done: replacing the rows by RecyclerView (virtualisation) — the right long-term fix for hundreds of live cards, but a large change I could not test without a device.

## 4. Files
Main: `AdsChatActivity`, `CigramStage1InitProvider`, `HomePrep`, `CigramRowAnchor`, `CigramHomeGroups`; admin: `AdChatActivity`; Worker: `apply_patch_19.py`; tests: `HomeGroupingDigestTest`, `HomeSectionsSnapshotTest`, `HomePrepVersionTest`, `HomePipelineProfileTest` + `HomeSamplerTest` (measurement only), `ads_chat19_test` (+3).

## 5. Tests and builds
Main `testPlayDebugUnitTest testDirectDebugUnitTest assemble…Release bundlePlayRelease` (clean) exit 0, **320 passed / 0 failed / 0 skipped** with `CIGRAM_CATALOGUE` = the live catalogue; admin `testDebugUnitTest assembleRelease bundleRelease` exit 0, **99 / 0 / 0**; Worker: admin_subhub 31, catalog_feed 16, ads_support 23, ads_chat19 27, channels 6 = **103**.

## 6. What I need from you (please test and tell me)
1. Open the chat on both apps: the old clock-icon bubbles should vanish by themselves and new messages must not double.
2. Open Cigram after a few minutes: the sections should appear right after the carousel. Then close it completely and reopen.
3. Scroll the Home up and down quickly and sideways in a row: is it smooth now?
If something is still slow, send a screen recording and the Home trace (the on-screen diagnosis dialog lists the stages, e.g. `snapStale7`, `catalog.warmup.fast`) and I will continue from the real numbers.
