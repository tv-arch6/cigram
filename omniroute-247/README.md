# OmniRoute 24/7 (Termux)

Files (keep them in one folder on the phone):

- `prompt.py` – the Telegram bot, patched to start and watch OmniRoute through the guardian.
- `omniroute_guardian.py` – starts OmniRoute, adopts a running instance, restarts it after crash / hang / memory blowup. Also usable alone: `python omniroute_guardian.py run|status|logs|restart|stop|resume|check`.
- `install.sh` – Termux installer: runit service `prompt-bot`, Termux:Boot script, wake-lock. `bash install.sh [--check|--uninstall]`.

## Secrets

`BOT_TOKEN` and `OMNIROUTE_API_KEY` are **not** stored in this copy of `prompt.py`.
Create `bot.env` next to the bot (it is git-ignored and sourced by the service):

```sh
export BOT_TOKEN="123456:ABC..."
export OMNIROUTE_API_KEY="sk-..."
```

## Android side (manual)

Termux + Termux:Boot: battery "Unrestricted", allow auto-start, lock in recents,
and on Android 12+ disable child-process restrictions (developer options or the `adb` command printed by `install.sh`).
