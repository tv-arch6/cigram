# OmniRoute 24/7 — hosting and Termux

The bot (`prompt.py`) now starts and watches OmniRoute by itself. The guardian is **embedded** in
`prompt.py`, so on a hosting panel you only upload that one file.

## Files

| File | What it is |
|------|------------|
| `prompt.py` | The Telegram bot, with the guardian embedded. This is the only file a hosting panel needs. |
| `omniroute_guardian.py` | Source of the guardian. Also a CLI: `python omniroute_guardian.py run\|status\|logs\|restart\|stop\|resume\|check`. |
| `embed_guardian.py` | Re-embeds the guardian into `prompt.py` after editing it: `python embed_guardian.py`. |
| `install.sh` | Termux only: runit service, Termux:Boot script, wake-lock. |

## On a hosting panel (Linux, Python only)

1. Upload `prompt.py` (replace the old one) and restart the bot.
2. First start: the guardian prepares everything inside the bot folder (`.omniroute-guardian/`):
   - downloads the official Node.js from nodejs.org (SHA-256 checked) if the host has no Node ≥ 22,
   - installs the `omniroute` npm package there (no root needed), then deletes npm's cache.
   It took ~2 minutes on a fast Linux box. Until it finishes, requests use the fallback provider.
3. To keep your providers/keys, move the OmniRoute data once (below) and upload `omniroute-data.tgz`
   next to `prompt.py`. The guardian unpacks it on first start, then renames it.
4. Admin panel `/live` → "حالة OmniRoute" shows the guardian state and has a restart button.

**Requirements** (measured with OmniRoute 3.8.54, Linux x64):

| | |
|---|---|
| OS | Linux with glibc (Debian/Ubuntu images). **Not Alpine/musl.** x64 or arm64. |
| Disk | **~3 GB** for OmniRoute, plus ~1.2 GB temporary during install (deleted afterwards). |
| RAM | **~650 MB** while idle (launcher ~120 MB + server ~530 MB), on top of the bot. |
| Network | outbound to `nodejs.org`, `registry.npmjs.org`, `github.com` |
| Node | ≥ 22.22.2 (the guardian downloads the latest 22.x if the host's Node is older/missing) |

If the plan has less disk/RAM than this, OmniRoute cannot live on it: keep OmniRoute elsewhere and set
`OMNIROUTE_BASE_URL` to its public address (the guardian then stays off and the bot behaves as before).

Handled automatically: the container memory limit is read from the cgroup and Node's heap is capped to a share of it;
`PORT`/`HOSTNAME` set by the host never reach OmniRoute; OmniRoute is bound to `127.0.0.1:20128` only
(by default it listens on `0.0.0.0` with no API-key requirement — set `OMNIROUTE_BIND` to change that on purpose).

### Move OmniRoute data from the phone

OmniRoute keeps everything in one folder (`~/.omniroute`: `storage.sqlite` with providers, and `.env` with the encryption key).
The whole folder must travel, and OmniRoute should be stopped first so the database is flushed.

```bash
# Termux
pkill -f omniroute; sleep 2                      # stop OmniRoute (flushes the database)
ls -d ~/.omniroute* 2>/dev/null                 # confirm the folder name
tar czf ~/omniroute-data.tgz -C ~ .omniroute    # use the folder name printed above
```

Upload `omniroute-data.tgz` to the bot folder. Only a top-level folder named `.omniroute*`/`omniroute*` is accepted.
An existing folder on the host is renamed to `.omniroute.bak-<time>`, never deleted. The archive holds provider
credentials: handle it like a password, and delete it from the host after the first start (it is renamed to `.restored-<time>`).
The data lives in the host's `$HOME/.omniroute` (or `$DATA_DIR`): make sure that folder survives restarts on your host.

## Admin token (for automatic key rotation)

`omniroute tokens create` does **not** work on Termux: Node reports `process.platform === "android"`, and the machine-id
library OmniRoute uses has no Android command, so the CLI disables its own auth. The bot therefore talks to OmniRoute's HTTP API instead:

`/live` → «مفتاح OmniRoute» → **«إنشاء توكن الإدارة تلقائياً»**. The bot logs in to the OmniRoute dashboard
(`POST /api/auth/login`), creates an `admin` access token (`POST /api/cli/tokens`), saves **only the token** and answers "تم حفظ التوكن ✓".
If it needs the dashboard password it asks once (the message is deleted immediately; the password is never stored).
Set `OMNIROUTE_DASH_PASSWORD` to make it a single click, and key rotation will also create the token by itself when it is rejected.
Forgot the dashboard password? OmniRoute ships `omniroute-reset-password` (not tested here).

## Settings (environment variables, all optional)

`OMNIROUTE_AUTOSTART` `OMNIROUTE_AUTO_NODE` `OMNIROUTE_AUTO_INSTALL` `OMNIROUTE_NODE_MAJOR` `OMNIROUTE_NODE_MIRROR`
`OMNIROUTE_HEAP_MB` `OMNIROUTE_MAX_RSS_MB` `OMNIROUTE_BIND` `OMNIROUTE_DATA_ARCHIVE` `OMNIROUTE_GUARD_DIR` `OMNIROUTE_CMD`
`OMNIROUTE_ARGS` `OMNIROUTE_CWD` `OMNIROUTE_EXTRA_ENV` `OMNIROUTE_DASH_PASSWORD` `OMNIROUTE_ADMIN_TOKEN` — see the header of `omniroute_guardian.py`.

## Termux (phone)

`bash install.sh` sets up a `prompt-bot` runit service and a Termux:Boot script. Android side (manual): battery
"Unrestricted", auto-start allowed, Termux locked in recents, and child-process restrictions disabled on Android 12+.

## Secrets

`BOT_TOKEN` and `OMNIROUTE_API_KEY` are **not** stored in this repo copy of `prompt.py`.
Set them as environment variables on the host, or in a `bot.env` next to the bot (git-ignored; the Termux service sources it).
