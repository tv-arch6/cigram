# CIGRAM TV EDITION: structure and status

The TV edition sits on top of the app's existing TV layer (`CigramTvMode`, `CigramTvFocus`, `CigramTvRail`,
`CigramTvKeys`) and reuses the shared systems: the same catalogue, the same player and resolver, the same
accounts / Plus / My List / watch-together. No second data source, no invented content.

## How it starts
- `MainActivity.onCreate`: on a television (`CigramTvActivity.shouldRunTvEdition`, based on the existing
  `CigramTvMode.isTv`) it hands off to `CigramTvActivity` and finishes. Phones and tablets are not affected.
- Switch off: `SharedPreferences("cigram_tv_edition").enabled = false` returns to the old TV layer.

## Files
| File | Role |
|---|---|
| `CigramTvActivity` | Host: sidebar (10 entries, RTL), panels, remote, BACK. Implements `CigramTvHost`. Keeps the parsed catalogue in memory for the details page. |
| `CigramTvDetailsActivity` | **New.** TV details page (design C, see below). |
| `CigramTvHost` | **New.** Small interface so every TV screen shares the same card code. |
| `CigramTvCards` | Poster card, horizontal row, and a **recycling `GridView`** for the long lists. |
| `CigramTvHome` | Hero + rows. Rows are built progressively (first two at once, then one per ~30 ms). |
| `CigramTvGrid` | Movies / Series panels with a genre chip row over the recycled grid. |
| `CigramTvSearch` | On-screen keyboard (Arabic / English), live results; titles are normalised once and cached. |
| `CigramTvImage` | Poster loader: background decode, shared LRU cache, skips requests for cards already scrolled away. |

## TV details page (CigramTvDetailsActivity)
- Hero: title, year, IMDb, genres, story, and the actions.
- **Main button reflects real saved progress** (the same history the player writes): start / continue
  ("تابع الحلقة 3", minutes left, progress bar) / next episode / watch again. "من البداية" appears when there is
  progress to ignore.
- **Readiness panel shows the real resolver result** for the source about to play: checking / ready / failed
  (with the plain-language reason and a retry button), plus a warning when the device's WebView is missing or old.
  It does not list servers: the data has no server list, so none is drawn.
- Episodes (series): season chips and an episode strip with the real state of each (watched / continue / next).
- Similar titles: same genre, from the catalogue.
- Playing uses the phone's own path: `CigramRewardUnlock.unlock` then `cigramPlay / cigramPlayTitle`.
- The item is handed over in memory (not through the Intent), so a long series cannot overflow the Binder limit.

## Playback fixes (the "تعذر التشغيل" problem)
1. **`CigramRewardUnlock`: a TV no longer waits for an ad.** Before every title the app showed a black
   "Please wait (5)" screen and then waited up to 15 s for an AdMob rewarded ad, which AdMob does not serve on
   Android TV. On a TV the title now opens at once. Phones are unchanged. (To restore the old behaviour on TV,
   delete the `CigramTvMode.isTv` block at the top of `unlock`.)
2. **`CigramEmbedResolver`:** a known embed site (vidbolt, ...) used to depend only on a hidden WebView. If the
   TV has no WebView, or one too old to run the page, it failed at once with a message blaming the internet. Now:
   - the hidden WebView can no longer take the remote's focus;
   - when it fails, a last-resort page scrape is tried (trailers / ads / samples are still rejected);
   - the failure carries a real reason (`timeout`, `webview failed: ...`, no stream) and the player's dialog shows
     a plain explanation plus that code, e.g. "محرك المتصفح (WebView) غير مثبّت على هذا الجهاز", instead of
     "تأكد من الاتصال بالإنترنت";
   - the WebView package and version are written to `cigram_embed.txt` for support.
3. `PlayerActivity` honours a new optional extra `cigram_start_over`, used by "من البداية".

## Performance
- Movies / Series / search results: recycling `GridView` instead of building (and downloading the poster of) every
  title at once. Only visible cards exist.
- Home rows capped at 18 and built progressively; long rows open at their right end.
- Poster requests for cards that scrolled away are skipped before any network or decode work.
- Phone Home: card building yields while the user scrolls; the search page no longer parses the catalogue before
  its first frame (see the earlier notes).

## How this was checked (and what that does NOT prove)
This environment cannot download the Android SDK, the Android Gradle plugin or androidx (`dl.google.com` is blocked
by the network policy), so **no APK was built and nothing was run on a device or emulator**.
What was done instead:
- Every Java file was compiled with the real `javac` against the real Android framework classes (Maven Central) with
  stubs for `AppCompatActivity` and `R`. A deliberately wrong file is correctly rejected (negative control).
- The whole project was compiled before and after the changes: the set of compiler errors of all 176 original files
  is **identical** (the remaining ones are libraries that cannot be downloaded here: ExoPlayer, Material, Firebase,
  AdMob, ViewBinding), and the 8 new TV files compile with **zero errors**.
This catches typos, wrong signatures and missing symbols. It cannot catch layout problems, focus behaviour, timing,
or anything that depends on a real device.

## Not done
- EPG / programme guide, voice search, and TV-specific rebuilds of Account / Settings / Live TV (the existing
  TV-ready screens are opened instead).
- The "auto-play next episode" switch of design C (the player has its own setting; it was not wired blind).
- A server list in the readiness panel (no such data exists).
- Anything that needs a real device: 60 FPS measurement, remote feel, 720p / 1080p / 4K layout, Safe Areas.
