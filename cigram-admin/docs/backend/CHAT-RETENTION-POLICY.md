# Cigram support conversations — retention policy (19.0)

**Rule: a support conversation is permanent.** No administrator action, no app, no timer and no account removal deletes a conversation
or a message. This is enforced in the Worker (`cigram-ads-api`, `apply_patch_19.py`), not only hidden in the apps:

| Old behaviour (removed) | What happens now |
|---|---|
| `POST /admin/chat/purge` deleted a thread, all its messages and its uploaded files | `409 history_is_permanent`, nothing touched (owner token included). The hub operation itself also refuses. |
| `POST /admin/chat/delete-message` blanked a message and deleted its file | `409 history_is_permanent` |
| `CHAT_RETENTION_DAYS` + the 6-hourly alarm purged archived threads | The variable is ignored. The alarm only (a) retries pushes, (b) removes deduplication markers older than 14 days, (c) runs the backup, (d) removes UPLOADS THAT WERE NEVER SENT after 24 h (an upload that was attached to a message is never touched) |
| Advertiser deletion (`/admin/advertisers/delete`) purged the user's thread | Erases the thread's **identity** only (below) |
| `POST /chat/delete-all` (user) purged the thread | Erases the identity and empties the user's own view; history stays |
| «حذف المحادثة نهائياً» in the admin app | Removed; the menu explains the policy |
| «حذف الرسالة للجميع» in the admin app | Removed |

## Archiving
`archived` is a flag. An archived conversation keeps every message, unread counters and attachments; it leaves «الكل», stays under
«المؤرشفة», is always included in a search (`archived=all`), and can be restored (`archived: false`) at any time.

## Identity erasure instead of deletion (privacy / account removal)
When a person removes their account, is removed as an advertiser, or asks to be forgotten (`/chat/delete-all`), the Worker runs
`erase_identity` on their thread:
* `name`, `email`, `uid` (the account id) are removed from the thread record — the name becomes «مستخدم محذوف»;
* the user's own view starts empty (`user_floor` = the last sequence number) — a returning person sees only new messages;
* the thread is archived, its messages, attachments and sequence numbers are untouched, the owner can still read and search it;
* the thread key is a one-way hash of the account id (`u_` + SHA-256 prefix): it identifies nobody without the account database.

This separates *who the person is* (removed on request) from *the support history* (kept). Message text may itself contain personal
data the person wrote; erasing **content** is deliberately not an API: it is a manual, owner-only legal procedure (decide the
request, export what must be kept, then act in the Cloudflare dashboard on `cigram-ads-api`'s Durable Object storage and on the R2
prefixes `ads/chat/<media id>/` and `chat-backup/v1/msgs/<thread>/`). That keeps "no ordinary action can destroy history" true.

## Backups (what is protected, and how far it has been verified)
* `POST /admin/chat/backup` (owner; also run by the alarm every 6 h) copies every message that is not yet backed up into **immutable
  segments** `chat-backup/v1/msgs/<thread>/<first>-<last>.json` and writes an **encrypted** thread/attachment snapshot
  `chat-backup/v1/_threads/…`. A segment is never overwritten. The messages inside are the stored (already encrypted) records, so
  restoring needs the same `CHAT_KEY` secret — **keep `CHAT_KEY` safe: without it neither the live store nor the backups can be read.**
* The bucket must be PRIVATE: `CHAT_BACKUP_BUCKET` or `ADS_BUCKET` (`cigram-ads`). The `movies` bucket is public and is refused.
* `GET /admin/chat/backup` = status (threads behind, unsaved messages, last success). `GET /admin/chat/backup/verify` = every thread's
  segments are contiguous up to its watermark. `POST /admin/chat/restore` (owner) puts back any record the live store lacks and never
  overwrites one that exists.
* Attachments live in R2 (`ads/chat/<id>/<part>`, encrypted per part). Nothing deletes them any more; protect them with the bucket's
  own versioning / replication if you need a second copy (not configured by this delivery).
* **Verified here:** the logic (segments, verification, restore, idempotence, privacy of the snapshot) against in-memory storage and
  R2 in `tests/ads_chat19_test.mjs`. **Not verified:** a backup run against the real bucket — run `scripts/post_deploy_smoke.sh`
  after deploying and treat the history as backed up only when `backup run` and `backup/verify` both pass.
