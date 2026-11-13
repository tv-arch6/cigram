# Cigram Admin 18.2 — Arabic Subtitle Center, Discovery approvals, Developer Inbox

Version stays **2.0 (7)**; package `com.my.newproject` unchanged.

## مركز الترجمة العربية — Arabic Subtitle Center (`SubCenterActivity`)
Entry: «المحتوى» → «مركز الترجمة العربية».
- One job per film / episode of the **live catalogue** without a working subtitle (count shown from the catalogue, not estimated).
- States: NOT_TRANSLATED, SEARCHING, SOURCE_FOUND, TRANSLATION_QUEUED, TRANSLATING, TRANSLATION_COMPLETED, QUALITY_REVIEW,
  NEEDS_CORRECTION, APPROVED, PUBLISHED, FAILED — with an enforced state machine (no shortcut to APPROVED / PUBLISHED).
- Each job: content id, TMDB, IMDb, type, season, episode, original language, target `ar`, source provider / release,
  original + Arabic files, translation provider + job id, quality status, approval, history.
- Search, filters (type / state), progress, KPIs, «عرض المزيد» paging.
- Bulk search for **existing Arabic** subtitles (`/admin/subs/search` + `/admin/subs/fetch` mode=text — routes of the live
  Worker): persistent cursor (pagination), 1–4 concurrent titles, HTTP 429 pauses the batch, network / 5xx retry with
  exponential backoff (30 s × 2ⁿ, max 6 h, 6 attempts), checkpoint written after every title (survives restart), pause /
  resume / cancel, OpenSubtitles daily download quota, foreground service with progress notification.
- A title with no TMDB / IMDb id is **never** searched by its name; the ids the Worker resolved must match the title;
  an episode never takes another episode's file.
- Found files are checked (CigramSubtitleHub QA) and parked in «مراجعة الجودة»; nothing is approved or published automatically.
- Translation campaign: cost estimate first (exact characters for known sources; unknown ones are listed, never guessed),
  then provider + price (entered from the provider's contract) + budget + batch confirmed in one dialog. It is sent to the
  18.2 Worker contract `/admin/subhub/translate/batch`; **no provider adapter is implemented** (official endpoints not
  verified), so today the admin sees the reason and no state changes.
- Publication history (this phone) + the Worker's own undo history for every link change.

## Job screen (`SubJobActivity`)
ids, state history, QA report, cue-by-cue preview (with the original when known), preview against the real video
(`CgPlayerActivity`, same timeline as the app), timing-neutral fixes (Arabic punctuation, RLM for lines that start in
Latin), **approved timing correction** (whole-file offset and/or frame-rate factor, explicit confirmation, re-approval
required), other releases, manual Arabic upload, manual original upload, approve / reject / publish (upload to R2 via
`/media/upload?action=put` + `/admin/item/update`), replacement only after confirmation.

## Uploads
- `CgSubtitleUpload` (existing, extended): parse + QA preview before anything is sent; the uploaded file is normalized
  (UTF-8 without BOM, three-digit milliseconds, same SRT/VTT format, identical cue times); existing-object handling unchanged.
- `CgSeasonSubsActivity` («ترجمات موسم كامل»): one / many files or a season ZIP (extracted in memory: only .srt/.vtt,
  zip-slip / bomb limits), matched by the S/E number in the file name only; unmatched / ambiguous / other-season /
  double-episode files listed apart; manual correction; conflict check; explicit replace confirmation.

## ما الجديد؟ — Cigram Discovery
Existing discovery center extended: popular movies / TV, daily trending, top rated movies / TV, currently airing
(the last four need the 18.2 Worker section), «غير المضاف فقط», «قائمة المراجعة» / «معتمد» / «مرفوض», per-title decision,
import gated by explicit approval, bulk approval with explicit confirmation, imported decision recorded. Titles without a
playable video cannot be imported (server validation) and stay «معتمد» as drafts until a video is linked.

## صندوق المطوّر — Cigram Developer Inbox
`AdInboxActivity` / `AdChatActivity`: internal thread id (and account id once the backend patch is deployed) shown only here,
search by internal id, last seen, history search across older pages, identity dialog with copy, «تقييد الحساب» wording,
24-hour times. Voice, photo, video, read / delivery states, archive and restrict were already supported.

## Removed
«أعلن هنا» page editors in «الأسعار والإعدادات» (rules, FAQ, contact buttons, page header) — no longer shown or saved;
the stored values stay untouched on the server. Prices, policies and work hours remain.

## Tests
`app/src/test/java/com/Cigram/subhub/` — SubHubTest (24, shared) + AdminSubHubTest (12); WnLogicTest updated (15);
`gradle testDebugUnitTest`.
