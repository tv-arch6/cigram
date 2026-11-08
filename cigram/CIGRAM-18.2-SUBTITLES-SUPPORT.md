# Cigram 18.2 — subtitles, Private Support (main app)

Version stays **18.2.0 (89)**; package `com.Cigram.vid`, flavors `play` / `direct` unchanged.

## 1. Why subtitles appeared before the speech (root cause, verified)
Disassembly of the ExoPlayer **2.19.1** classes this app ships (`SubripDecoder.parseTimecode`,
`WebvttParserUtil.parseTimestampUs`) shows that the digits after `,` / `.` are **added as raw milliseconds**:

| Written in the file | ExoPlayer showed it at | Correct |
|---|---|---|
| `00:00:10,5` | 10.005 s | 10.500 s (495 ms early) |
| `00:00:10,50` | 10.050 s | 10.500 s (450 ms early) |
| `00:00:10.500` (SRT with a dot) | cue **dropped** | 10.500 s |

Such files come from ASS→SRT converters (centiseconds) and some translation tools. In addition ExoPlayer assumes UTF-8
without a BOM (Windows-1256 Arabic became garbage), picked SRT/VTT from the URL text only, and ignores
`X-TIMESTAMP-MAP`. A second, data-level cause is a subtitle made for a different release (other intro / frame rate):
that needs the viewer's delay or an approved correction in the admin — the app never shifts a file by itself.

## 2. CigramSubtitleHub + CigramSubtitleSyncEngine
- `com.Cigram.subhub` (pure Java, identical copy in the admin app): `SubCodec` (bytes → text: BOM / UTF-16 / strict UTF-8 /
  Windows-1256 / mojibake repair; tolerant SRT+VTT parser; writers with exactly three millisecond digits), `SubSync`
  (timeline, approved shift / frame-rate stretch, translation keeps the source timing), `SubQa` (quality checks).
- `CigramSubtitleSyncEngine`: the player downloads the subtitle once (memory + 12 h disk cache, 8 MB limit), parses it
  with the hub and draws it with a second `SubtitleView` over ExoPlayer's own (same style, size, position, padding,
  visibility). Cues on screen = `activeAt(player.getCurrentPosition() − delay)`, checked every frame while playing.
- **Viewer delay** (`توقيت الترجمة` in the gear sheet): presets ±0.25/0.5/1/2 s, «تعديل دقيق مع معاينة» panel
  (±0.1 / 0.5 / 1 s, live preview of the nearest cue against the video time, reset to 0). Positive = text later.
  Applied **once** at display time, saved per subtitle file, never written into the file.
- Quality switch / reload of the same title keeps the parsed file and the delay (no new download).
- Watch-together player: same download + parse, side-loads a normalized SRT (correct times) to ExoPlayer.
- Kill switch: `subtitle_settings` → `subtitle_engine_legacy=true` restores the pre-18.2 path.

## 3. Cigram Private Support (الدعم الخاص)
- Account row **«أريد إعلاناً»** (phone + TV) now opens the private conversation with the developer. The old
  «أعلن هنا» page content was removed; `AdvertiseActivity` remains only as a redirect so old links keep working.
  AdMob, in-house campaigns, statistics and «حملاتي» (now in the chat menu) are untouched.
- Same server thread as before (cigram-ads-api ChatHub: WebSocket + polling, encrypted storage, uploads with resume).
- New: premium black design, faint logo watermark, developer public name / picture / online / last seen,
  sender name per run of developer messages, emoji panel, voice preview (listen / delete / send), 24-hour times
  (`19:45`, device time zone; server keeps UTC ms), Arabic right-aligned / English left-aligned per message.
- Reliability: a message is shown as sent only after the server returns its `seq`; offline / 5xx / 429 failures stay
  queued and are retried with exponential backoff (2 s → 60 s) and on reconnect; `client_id` prevents duplicates.
- Notifications: developer name, preview, time, logo; tap opens the conversation; FCM `kind=chat` handled.
- With the **current** server every message needs the ads-policy consent; the app shows the server's policy text and
  asks explicitly (nothing is accepted on the user's behalf). The backend patch in `docs/backend` removes that for
  support messages.

## 4. Build fix
Release variants failed at `mergeDex` in the supplied project (`androidx.viewbinding.ViewBinding` defined twice: a stub
in `src/main/java` + the official library). The stub was removed (identical interface). Debug was not affected.

## 5. Tests
`app/src/test/java/com/Cigram/subhub/SubHubTest.java` — 24 JVM tests (`gradle testPlayDebugUnitTest`).
