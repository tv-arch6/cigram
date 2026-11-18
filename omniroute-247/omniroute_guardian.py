#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# ============================================================================
#  حارس OmniRoute  (OmniRoute Guardian)
#
#  شنو يسوي:
#    • يشغّل OmniRoute لحاله (بدون ما تفتح Termux وتكتب omniroute).
#    • إذا OmniRoute وقع / تعلّق / انقتل من أندرويد: يرجّعه خلال ثواني (backoff ذكي بدون حلقة جنون).
#    • يتبنّى (adopt) نسخة شغّالة من قبل بدل ما يشغّل نسخة ثانية، وقفل ملف يمنع حارسين يتصارعون.
#    • OmniRoute يشتغل بجلسة مستقلة: لو البوت انعاد تشغيله OmniRoute ما يتأثر.
#    • يمسك wake-lock تبع Termux كي المعالج ما ينام، ويحدّ ذاكرة Node كي أندرويد ما يقتل Termux كله.
#    • يكتب سجل بملف مع تدوير، ويطلع سبب العطل بالعراقي إذا تكرر (مكتبة ناقصة، ذاكرة، منفذ مشغول...).
#    • الطلبات ما تفشل وقت الرجوع: البوت يستنى الحارس عبر wait_ready() بدل ما يرمي خطأ.
#
#  داخل البوت (يستدعيه prompt.py تلقائياً):
#        g = get_guardian(OMNIROUTE_BASE_URL, on_event=cb);  await g.start();  await g.wait_ready(40)
#
#  لحاله من الطرفية (مثلاً كخدمة runit/Termux:Boot):
#        python omniroute_guardian.py run        # حارس بالواجهة، يبقى شغّال للأبد
#        python omniroute_guardian.py status     # الحالة
#        python omniroute_guardian.py logs -n 80 # آخر سطور السجل
#        python omniroute_guardian.py restart    # إعادة تشغيل OmniRoute
#        python omniroute_guardian.py stop       # إيقاف يدوي (يوقف الحراسة لين تكتب resume)
#        python omniroute_guardian.py resume
#        python omniroute_guardian.py check      # فحص شامل للجهاز
#
#  الإعدادات (متغيرات بيئة، كلها اختيارية):
#    OMNIROUTE_BASE_URL      عنوان OmniRoute (الافتراضي http://localhost:20128/v1). إذا مو محلي الحارس يراقب بس.
#    OMNIROUTE_AUTOSTART     1/0  تشغيل تلقائي (الافتراضي 1)
#    OMNIROUTE_CMD           أمر التشغيل إذا تريد غير الافتراضي (مثال: "node /path/to/server.js")
#    OMNIROUTE_ARGS          أعلام التشغيل (الافتراضي --no-open). إذا ما يعرفها OmniRoute يجرّب بدونها لحاله.
#    OMNIROUTE_CWD           مجلد التشغيل (الافتراضي مجلد البيت ~ مثل لما تكتبه يدوي)
#    OMNIROUTE_HEAP_MB       سقف ذاكرة Node بالميغا (الافتراضي 1536، 0 = بلا سقف)
#    OMNIROUTE_MAX_RSS_MB    إذا أكل OmniRoute أكثر من هذا يُعاد تشغيله بهدوء (الافتراضي 2200، 0 = تعطيل)
#    OMNIROUTE_AUTO_INSTALL  1/0  ينصّبه بـ npm إذا مو موجود (الافتراضي 1)
#    OMNIROUTE_EXTRA_ENV     "KEY=VAL,KEY2=VAL2" متغيرات إضافية لـ OmniRoute
#    OMNIROUTE_GUARD_DIR     مجلد الحالة والسجل (الافتراضي ~/.omniroute-guardian)
#    OMNIROUTE_STOP_WITH_BOT 1 = يوقف OmniRoute إذا انطفى البوت نظيف (الافتراضي 0: يبقى شغّال)
#    OMNIROUTE_AUTO_NODE     1/0  ينزّل Node (لينكس) إذا ناقص أو أقدم من 22، ويثبّت OmniRoute بمجلد الحارس بدون root (الافتراضي 1)
#    OMNIROUTE_NODE_MAJOR    رقم Node المطلوب (الافتراضي 22) | OMNIROUTE_NODE_MIRROR  مصدر التنزيل (الافتراضي https://nodejs.org/dist)
#    OMNIROUTE_BIND          عنوان استماع OmniRoute (الافتراضي 127.0.0.1 للأمان؛ مثلاً 0.0.0.0 لفتح اللوحة عبر منفذ الاستضافة)
#    OMNIROUTE_DATA_ARCHIVE  أرشيف بيانات OmniRoute من جهاز ثاني (الافتراضي omniroute-data.tgz بجنب البوت): يُفكّ مرة وحدة ببيت المستخدم
# ============================================================================
from __future__ import annotations

import argparse
import asyncio
import collections
import json
import logging
import os
import random
import re
import hashlib
import platform
import shlex
import shutil
import signal
import subprocess
import sys
import tarfile
import time
import urllib.request
from urllib.parse import urlparse

try:                                     # قفل الملفات (لينكس/أندرويد). على ويندوز نشتغل بدون قفل.
    import fcntl
except ImportError:                      # pragma: no cover
    fcntl = None

log = logging.getLogger("omniroute.guardian")

DEFAULT_PORT = 20128
_EXTRA_BIN_DIRS = ("/usr/local/bin",)          # مجلدات تنضاف لـ PATH إذا كان PATH الاستضافة فقيراً (الاختبارات تفرّغها)
LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1", "0.0.0.0", ""}
TERMUX_PREFIX = os.environ.get("PREFIX") or "/data/data/com.termux/files/usr"


# ----------------------------------------------------------------------------
#  أدوات صغيرة
# ----------------------------------------------------------------------------
def _env(name, default, cast=str):
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return cast(raw.strip())
    except (TypeError, ValueError):
        return default


def _flag(name, default=True):
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() not in ("0", "false", "no", "off", "n")


def _first_local_base(base_url):
    """أول عنوان محلي من القائمة (مفصولة بفاصلة). يرجع (host, port, is_local)."""
    first = None
    for b in re.split(r"[,\s]+", (base_url or "").strip()):
        if not b:
            continue
        u = urlparse(b if "://" in b else "http://" + b)
        host = u.hostname or ""
        port = u.port or (443 if u.scheme == "https" else 80)
        if first is None:
            first = (host, port, host in LOCAL_HOSTS)
        if host in LOCAL_HOSTS:
            return host or "127.0.0.1", port, True
    if first is None:
        return "127.0.0.1", DEFAULT_PORT, True
    return first


def _read_int(path):
    try:
        with open(path) as f:
            raw = f.read().strip()
        return None if raw in ("", "max") else int(raw)
    except (OSError, ValueError):
        return None


_CGROUP_LIMIT_FILES = ("/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory/memory.limit_in_bytes")


def mem_limit_mb():
    """سقف ذاكرة الحاوية (cgroup v2/v1) أو ذاكرة الجهاز، بالميغا. None إذا ما كدرنا نعرف."""
    best = None
    for p in _CGROUP_LIMIT_FILES:
        v = _read_int(p)
        if v and v < (1 << 50):                            # القيم الضخمة جداً تعني «بلا حد»
            best = v // 1048576 if best is None else min(best, v // 1048576)
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemTotal:"):
                    total = int(line.split()[1]) // 1024
                    best = total if best is None else min(best, total)
                    break
    except (OSError, ValueError, IndexError):
        pass
    return best


def _auto_limits(limit_mb):
    """(سقف ذاكرة Node، حد إعادة التشغيل الهادئ) بالميغا حسب الذاكرة المتاحة: الحاوية الصغيرة تاخذ نسبة منها."""
    if not limit_mb:
        return 1536, 2200
    return max(384, min(1536, int(limit_mb * 0.40))), max(900, min(2200, int(limit_mb * 0.65)))


def _default_state_dir():
    """Termux: بالبيت. الاستضافات: بجنب الملف (مجلد البوت مضمون الدوام)، وإلا البيت، وإلا tmp."""
    home = os.path.expanduser("~")
    if os.path.isdir(TERMUX_PREFIX):
        return os.path.join(home, ".omniroute-guardian")
    here = os.path.dirname(os.path.abspath(globals().get("__file__") or "."))
    for base in (here, home, os.environ.get("TMPDIR", "/tmp")):
        d = os.path.join(base, ".omniroute-guardian")
        try:
            os.makedirs(d, exist_ok=True)
            if os.access(d, os.W_OK):
                return d
        except OSError:
            continue
    return os.path.join("/tmp", ".omniroute-guardian")


def _is_musl():
    import glob
    return bool(glob.glob("/lib/ld-musl-*") or glob.glob("/usr/lib/ld-musl-*"))


def _safe_extract(tar, dest, strip=0):
    """استخراج آمن: يرفض المسارات المطلقة و .. والروابط اللي تطلع خارج الوجهة، ويتجاوز hardlink وملفات الأجهزة."""
    dest_real = os.path.realpath(dest)
    os.makedirs(dest_real, exist_ok=True)
    kw = {"filter": "tar"} if hasattr(tarfile, "tar_filter") else {}

    def inside(path):
        return path == dest_real or path.startswith(dest_real + os.sep)
    for m in tar.getmembers():
        parts = [p for p in m.name.split("/") if p not in ("", ".")][strip:]
        if not parts:
            continue
        if ".." in parts:
            raise ValueError(f"مسار مرفوض داخل الأرشيف: {m.name}")
        name = "/".join(parts)
        if not inside(os.path.realpath(os.path.join(dest_real, name))):
            raise ValueError(f"مسار مرفوض داخل الأرشيف: {m.name}")
        if m.islnk() or m.isdev() or m.isfifo():
            continue
        if m.issym() and not inside(os.path.realpath(os.path.join(dest_real, os.path.dirname(name), m.linkname))):
            raise ValueError(f"رابط مرفوض داخل الأرشيف: {m.name}")
        m.name = name
        tar.extract(m, dest_real, **kw)


def _tail(path, lines=20, max_bytes=24000):
    try:
        size = os.path.getsize(path)
        with open(path, "rb") as f:
            f.seek(max(0, size - max_bytes))
            data = f.read().decode("utf-8", "replace")
    except OSError:
        return ""
    return "\n".join(data.splitlines()[-lines:])


def _alive(pid):
    """هل العملية حيّة (والزومبي لا يُحسب)."""
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    try:
        with open(f"/proc/{pid}/stat", "rb") as f:
            raw = f.read().decode("utf-8", "replace")
        state = raw[raw.rindex(")") + 2:][:1]
        return state != "Z"
    except (OSError, ValueError):
        return True


def _argv_of(pid):
    try:
        with open(f"/proc/{pid}/cmdline", "rb") as f:
            return [a.decode("utf-8", "replace") for a in f.read().split(b"\0") if a]
    except OSError:
        return []


_OMNI_ARG_RE = re.compile(r"(^|/)omniroute(/|$|\.|-)", re.I)


def _is_omniroute_argv(argv):
    if not argv:
        return False
    base = os.path.basename(argv[0])
    if base == "omniroute" or base.startswith("omniroute ("):      # الخادم الابن يغيّر عنوان عمليته إلى  omniroute (v16.4.2)
        return True
    if base.startswith("node") or base in ("npm", "npx"):
        return any(_OMNI_ARG_RE.search(a) for a in argv[1:])
    return False


def _ppid_of(pid):
    try:
        with open(f"/proc/{pid}/stat", "rb") as f:
            raw = f.read().decode("utf-8", "replace")
        return int(raw[raw.rindex(")") + 2:].split()[1])
    except (OSError, ValueError, IndexError):
        return 0


def scan_omniroute_pids(exclude=()):
    """كل عمليات OmniRoute من /proc (المشغّل + الخادم الابن + أحفادهم). يشتغل لنفس المستخدم (وكل عمليات Termux/الحاوية نفس المستخدم)."""
    if not os.path.isdir("/proc"):
        return []
    skip = set(exclude) | {os.getpid(), os.getppid()}
    try:
        pids = [int(n) for n in os.listdir("/proc") if n.isdigit()]
    except OSError:
        return []
    out = {p for p in pids if p not in skip and _is_omniroute_argv(_argv_of(p)) and _alive(p)}
    if out:
        kids = {}
        for p in pids:
            kids.setdefault(_ppid_of(p), []).append(p)
        stack = list(out)
        while stack:
            for c in kids.get(stack.pop(), []):
                if c not in out and c not in skip and _alive(c):
                    out.add(c)
                    stack.append(c)
    return sorted(out)


def _rss_mb(pids):
    total = 0
    for pid in pids:
        try:
            with open(f"/proc/{pid}/status", "r", errors="replace") as f:
                for line in f:
                    if line.startswith("VmRSS:"):
                        total += int(line.split()[1]) // 1024
                        break
        except (OSError, ValueError, IndexError):
            pass
    return total


async def probe(host, port, timeout=8.0, path="/v1/models"):
    """يفحص OmniRoute: 'up' (رد بأي كود HTTP حتى 401) | 'hung' (فتح الاتصال وما رد) | 'down' (الاتصال مرفوض)."""
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout)
    except (OSError, asyncio.TimeoutError):
        return "down"
    try:
        req = (f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUser-Agent: omniroute-guardian\r\n"
               "Accept: */*\r\nConnection: close\r\n\r\n")
        writer.write(req.encode())
        await writer.drain()
        line = await asyncio.wait_for(reader.readline(), timeout)
        return "up" if line.startswith(b"HTTP/") else "hung"
    except (OSError, asyncio.TimeoutError):
        return "hung"
    finally:
        try:
            writer.close()
            await asyncio.wait_for(writer.wait_closed(), 2.0)
        except Exception:                      # noqa: BLE001
            pass


class _FileLock:
    """قفل بين العمليات: حارس واحد فقط يشغّل OmniRoute. يتحرر تلقائياً إذا العملية ماتت أو سوّت exec."""

    def __init__(self, path):
        self.path, self.fd, self.held = path, None, False

    def try_acquire(self):
        if self.held:
            return True
        if fcntl is None:
            self.held = True
            return True
        try:
            fd = os.open(self.path, os.O_RDWR | os.O_CREAT, 0o644)
        except OSError:
            self.held = True                   # ما نكدر نقفل: نكمل بدون قفل بدل ما نوقف الخدمة
            return True
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            os.close(fd)
            return False
        try:
            os.ftruncate(fd, 0)
            os.write(fd, str(os.getpid()).encode())
        except OSError:
            pass
        self.fd, self.held = fd, True
        return True

    def release(self):
        if self.fd is not None:
            try:
                os.close(self.fd)
            except OSError:
                pass
        self.fd, self.held = None, False


# أسباب التعطل المعروفة ← شرح بالعراقي
_HINTS = [
    (re.compile(r"EADDRINUSE|address already in use", re.I),
     "المنفذ مشغول بعملية ثانية. الحارس يحاول يسكّر النسخة القديمة."),
    (re.compile(r"better-sqlite3|ERR_DLOPEN_FAILED|NODE_MODULE_VERSION|invalid ELF|cannot open shared object|bindings", re.I),
     "مكتبة أصلية (better-sqlite3) ما تشتغل: نفّذ  pkg install python build-essential  ثم  npm rebuild -g omniroute  (أو أعد تنصيبه)."),
    (re.compile(r"heap out of memory|ENOMEM|JavaScript heap", re.I),
     "الذاكرة خلصت: ارفع OMNIROUTE_HEAP_MB أو سكّر تطبيقات ثانية."),
    (re.compile(r"Cannot find module|MODULE_NOT_FOUND", re.I),
     "ملفات OmniRoute ناقصة أو تالفة: أعد تنصيبه  npm i -g omniroute"),
    (re.compile(r"ENOSPC|no space left", re.I), "مساحة التخزين خلصت."),
    (re.compile(r"EACCES|EPERM", re.I), "مشكلة صلاحيات بمجلد بيانات OmniRoute."),
    (re.compile(r"requires node|unsupported engine|SyntaxError: Unexpected token|ERR_REQUIRE_ESM", re.I),
     "نسخة Node قديمة: OmniRoute يريد Node 22 أو أحدث  (pkg upgrade nodejs)."),
]
_BAD_FLAG_RE = re.compile(r"unknown (option|argument|flag)|unrecognized|invalid option|bad option|unexpected argument|Usage:", re.I)


class Config:
    """كل الإعدادات من متغيرات البيئة، وأي واحد ينعوّض من الكود (للاختبار)."""

    def __init__(self, base_url=None, **ov):
        self.base_url = base_url or _env("OMNIROUTE_BASE_URL", f"http://localhost:{DEFAULT_PORT}/v1")
        self.host, self.port, self.local = _first_local_base(self.base_url)
        g = lambda k, env, default, cast=str: ov.get(k, _env(env, default, cast))   # noqa: E731
        self.autostart = ov.get("autostart", _flag("OMNIROUTE_AUTOSTART", True))
        self.cmd = g("cmd", "OMNIROUTE_CMD", "")
        self.args = ov.get("args", os.environ.get("OMNIROUTE_ARGS"))      # None = الافتراضي
        self.cwd = g("cwd", "OMNIROUTE_CWD", "")
        self.extra_env = g("extra_env", "OMNIROUTE_EXTRA_ENV", "")
        self.auto_install = ov.get("auto_install", _flag("OMNIROUTE_AUTO_INSTALL", True))
        self.stop_with_bot = ov.get("stop_with_bot", _flag("OMNIROUTE_STOP_WITH_BOT", False))
        self.mem_limit_mb = mem_limit_mb()
        auto_heap, auto_rss = _auto_limits(self.mem_limit_mb)
        self.heap_mb = g("heap_mb", "OMNIROUTE_HEAP_MB", auto_heap, int)
        self.max_rss_mb = g("max_rss_mb", "OMNIROUTE_MAX_RSS_MB", auto_rss, int)
        self.state_dir = g("state_dir", "OMNIROUTE_GUARD_DIR", _default_state_dir())
        self.auto_node = ov.get("auto_node", _flag("OMNIROUTE_AUTO_NODE", True))      # ينزّل Node بنفسه إذا ناقص (لينكس فقط)
        self.node_major = g("node_major", "OMNIROUTE_NODE_MAJOR", 22, int)
        self.node_mirror = g("node_mirror", "OMNIROUTE_NODE_MIRROR", "https://nodejs.org/dist")
        self.bind = g("bind", "OMNIROUTE_BIND", "")                                   # عنوان استماع OmniRoute (الافتراضي: المحلي فقط داخل الحاويات)
        here = os.path.dirname(os.path.abspath(globals().get("__file__") or "."))
        cands = [os.path.join(here, "omniroute-data.tgz"), os.path.join(os.getcwd(), "omniroute-data.tgz")]
        self.data_archive = g("data_archive", "OMNIROUTE_DATA_ARCHIVE", next((c for c in cands if os.path.isfile(c)), cands[0]))
        # توقيتات (بالثواني)
        self.interval = g("interval", "GUARD_INTERVAL", 15.0, float)          # فحص دوري وهو سليم
        self.fast_interval = g("fast_interval", "GUARD_FAST_INTERVAL", 2.0, float)   # فحص سريع وهو واقع
        self.probe_timeout = g("probe_timeout", "GUARD_PROBE_TIMEOUT", 8.0, float)
        self.hung_strikes = g("hung_strikes", "GUARD_HUNG_STRIKES", 4, int)
        self.start_grace = g("start_grace", "GUARD_START_GRACE", 120.0, float)   # الجوال يبطّي بأول إقلاع
        self.backoff_max = g("backoff_max", "GUARD_BACKOFF_MAX", 60.0, float)
        self.backoff_initial = g("backoff_initial", "GUARD_BACKOFF_INITIAL", 2.0, float)
        self.stable_after = g("stable_after", "GUARD_STABLE_AFTER", 120.0, float)
        self.crashloop_n = g("crashloop_n", "GUARD_CRASHLOOP_N", 6, int)
        self.crashloop_window = g("crashloop_window", "GUARD_CRASHLOOP_WINDOW", 600.0, float)
        self.crashloop_backoff = g("crashloop_backoff", "GUARD_CRASHLOOP_BACKOFF", 300.0, float)
        self.stop_grace = g("stop_grace", "GUARD_STOP_GRACE", 8.0, float)
        self.log_max_mb = g("log_max_mb", "GUARD_LOG_MAX_MB", 8, int)


class OmniRouteGuardian:
    def __init__(self, base_url=None, on_event=None, **overrides):
        self.cfg = Config(base_url, **overrides)
        self.on_event = on_event
        self.host, self.port = self.cfg.host, self.cfg.port
        self.managed = bool(self.cfg.autostart and self.cfg.local)
        self.state_dir = self.cfg.state_dir
        try:
            os.makedirs(self.state_dir, exist_ok=True)
        except OSError:
            self.state_dir = os.path.join(os.environ.get("TMPDIR", "/tmp"), ".omniroute-guardian")
            os.makedirs(self.state_dir, exist_ok=True)
        self.log_path = os.path.join(self.state_dir, "omniroute.log")
        self.pid_path = os.path.join(self.state_dir, "omniroute.pid")
        self.pause_path = os.path.join(self.state_dir, "paused")
        self.lock = _FileLock(os.path.join(self.state_dir, "guardian.lock"))
        self.npm_prefix = os.path.join(self.state_dir, "npm")

        self.proc = None
        self.state = "init"
        self.task = None
        self._loop = None
        self.up_event = None
        self._kick = None
        self._stopping = False
        self._expected_exit = False
        self._hung = 0
        self._spawned_at = 0.0
        self._next_spawn = 0.0
        self._backoff = 0.0
        self._crash_times = collections.deque(maxlen=64)
        self._variants = self._build_variants()
        self._variant_idx = 0
        self._down_since = None
        self._ever_up = False
        self._had_outage = False
        self._install_task = None
        self._install_at = 0.0
        self._installing = False
        self._missing_since = None
        self._rss_strikes = 0
        self._last_rss_restart = 0.0
        self._last_wake = 0.0
        self._ticks = 0
        self._last_event_at = {}
        self.last_ok = 0.0
        self.spawns = 0
        self.restarts = 0
        self.last_exit = None
        self.last_hint = ""
        self.events = collections.deque(maxlen=30)

    # ------------------------------------------------------------------ واجهة عامة
    @property
    def paused(self):
        return os.path.exists(self.pause_path)

    def is_up(self):
        return self.state == "up"

    def kick(self):
        """نبّه الحارس يفحص هسه بدل ما ينتظر الدورة الجاية."""
        if self._kick is not None:
            try:
                self._loop.call_soon_threadsafe(self._kick.set)
            except RuntimeError:                       # الحلقة انغلقت
                pass

    async def start(self):
        """يبدأ المراقبة بالخلفية. آمن لو انندعى مرتين أو بعد إعادة تشغيل حلقة البوت."""
        if not self.managed:
            log.info("guardian: مراقبة فقط (التشغيل التلقائي معطّل أو العنوان مو محلي).")
            return False
        loop = asyncio.get_running_loop()
        if self.task is not None and not self.task.done() and self._loop is loop:
            return True
        if self.task is not None and not self.task.done():
            try:
                self.task.cancel()                     # مهمة من حلقة قديمة
            except Exception:                          # noqa: BLE001
                pass
        self._loop = loop
        self._kick = asyncio.Event()
        self.up_event = asyncio.Event()
        self.state = "init"                            # الحدث جديد وغير مضبوط: أول فحص يعيد ضبطه حسب الواقع (تبنّي بعد إعادة إقلاع البوت)
        self._stopping = False
        self.proc = None if (self.proc is not None and self.proc.poll() is not None) else self.proc
        self.task = loop.create_task(self._run(), name="omniroute-guardian")
        return True

    async def stop(self, kill=None):
        """يوقف المراقبة. OmniRoute يبقى شغّال إلا إذا kill=True (أو OMNIROUTE_STOP_WITH_BOT=1)."""
        kill = self.cfg.stop_with_bot if kill is None else kill
        self._stopping = True
        if self.task is not None and not self.task.done():
            self.task.cancel()
            try:
                await self.task
            except (asyncio.CancelledError, Exception):    # noqa: BLE001
                pass
        if kill:
            await self._kill_all("stop", foreign=True)
        self.lock.release()

    async def wait_ready(self, timeout=40.0, recheck=False):
        """ينتظر OmniRoute يصير شغّال. recheck=True: فحص فوري أولاً (استُعمل بعد فشل اتصال مفاجئ)."""
        if not self.managed or self.up_event is None:
            return (await probe(self.host, self.port, min(timeout, self.cfg.probe_timeout))) == "up"
        if self.up_event.is_set() and not recheck:
            return True
        if recheck:
            h = await probe(self.host, self.port, min(5.0, self.cfg.probe_timeout))
            if h == "up":
                return True
            self._set_health(h)
        self.kick()
        try:
            await asyncio.wait_for(self.up_event.wait(), timeout)
            return True
        except asyncio.TimeoutError:
            return False

    async def restart(self, wait=70.0):
        """إعادة تشغيل فورية (زر الأدمن). يرجع True إذا قام OmniRoute خلال المهلة."""
        try:
            os.remove(self.pause_path)
        except OSError:
            pass
        self._emit("restart", "إعادة تشغيل OmniRoute بطلب من الأدمن.", {})
        await self._kill_all("manual", foreign=True)
        self._next_spawn, self._backoff, self._hung = 0.0, 0.0, 0
        if self.up_event is not None:
            self.up_event.clear()
        self.state = "starting"
        self.kick()
        return await self.wait_ready(wait)

    def snapshot(self):
        child = self.proc if (self.proc is not None and self.proc.poll() is None) else None
        return {
            "state": "paused" if self.paused else self.state,
            "managed": self.managed,
            "owner": self.lock.held,
            "port": self.port,
            "pid": child.pid if child else None,
            "uptime": (time.monotonic() - self._spawned_at) if child else None,
            "spawns": self.spawns,
            "restarts": self.restarts,
            "last_exit": self.last_exit,
            "hint": self.last_hint,
            "log": self.log_path,
        }

    def status_lines(self):
        s = self.snapshot()
        names = {"up": "شغّال", "starting": "دا يقوم", "down": "واقف، دا أرجّعه", "hung": "معلّق، دا أرجّعه",
                 "installing": "دا ينصّب OmniRoute", "crashloop": "يتعطّل بالتكرار (الحارس يخفّف المحاولات)",
                 "init": "دا يبدأ", "paused": "موقوف يدوياً (resume لتشغيله)", "stopped": "الحراسة متوقفة"}
        lines = [f"حارس OmniRoute: {names.get(s['state'], s['state'])} | المنفذ {s['port']}"]
        if not self.managed:
            lines.append("الوضع: مراقبة فقط (العنوان مو محلي أو OMNIROUTE_AUTOSTART=0)")
            return lines
        lines.append("الدور: " + ("يدير OmniRoute" if s["owner"] else "احتياطي (حارس ثاني يديره)"))
        if s["pid"]:
            up = int(s["uptime"] or 0)
            lines.append(f"العملية: PID {s['pid']} | شغّالة من {up // 3600}س {up % 3600 // 60}د")
        lines.append(f"مرات التشغيل: {s['spawns']} | إعادات بعد عطل: {s['restarts']}")
        lines.append("بيانات OmniRoute: " + (os.environ.get("DATA_DIR") or os.path.join(self._home(), ".omniroute")))
        if self.cfg.mem_limit_mb:
            lines.append(f"الذاكرة: حد الجهاز/الحاوية {self.cfg.mem_limit_mb} ميغا | سقف Node {self.cfg.heap_mb} ميغا")
        if s["last_exit"]:
            le = s["last_exit"]
            lines.append(f"آخر وقوع: رمز {le['rc']} بعد {int(le['uptime'])} ثانية")
        if s["hint"]:
            lines.append("السبب المحتمل: " + s["hint"])
        return lines

    # ------------------------------------------------------------------ الحلقة الرئيسية
    async def _run(self):
        loop = asyncio.get_running_loop()
        await loop.run_in_executor(None, self._wake_lock)
        if self._ensure_owner() and self.cfg.data_archive and os.path.isfile(self.cfg.data_archive):
            if await probe(self.host, self.port, 3.0) == "down" and not scan_omniroute_pids():
                res = await loop.run_in_executor(None, self._restore_data)
                if res and res.startswith("ok:"):
                    self._emit("restored", "رجّعت بيانات OmniRoute (المزوّدات والمفاتيح) من الأرشيف: " + res[3:], {})
                elif res:
                    self._emit("restore_failed", "فشل استرجاع بيانات OmniRoute: " + res[5:], {}, every=3600)
        while not self._stopping:
            try:
                await self._tick()
            except asyncio.CancelledError:
                raise
            except Exception:                              # noqa: BLE001  الحارس نفسه ما يموت أبداً
                log.exception("guardian tick failed")
            interval = self.cfg.interval if self.state == "up" else self.cfg.fast_interval
            try:
                await asyncio.wait_for(self._kick.wait(), interval)
            except asyncio.TimeoutError:
                pass
            self._kick.clear()

    async def _tick(self):
        now = time.monotonic()
        self._ticks += 1
        self._reap_exit()
        health = await probe(self.host, self.port, self.cfg.probe_timeout)
        self._set_health(health)

        if health == "up":
            self._hung = 0
            await self._maintenance(now)
            return

        if self.paused:
            self.state = "stopped"
            return
        if not self._ensure_owner():
            return                                         # حارس ثاني يدير الموضوع، نراقب بس
        if self._installing:
            return

        if health == "hung":
            self._hung += 1
            if self._hung < self.cfg.hung_strikes:
                return
            self._emit("hung", "OmniRoute علّق (يفتح الاتصال وما يرد). دا أعيد تشغيله.", {})
            await self._kill_all("hung", foreign=True)
            self._hung = 0
            self._next_spawn = 0.0
            self.restarts += 1
        else:
            self._hung = 0

        child = self.proc if (self.proc is not None and self.proc.poll() is None) else None
        if child is not None:
            if now - self._spawned_at > self.cfg.start_grace:
                log.warning("guardian: OmniRoute شغّال كعملية بس ما يرد بعد %ss، أعيد تشغيله", int(self.cfg.start_grace))
                await self._kill_all("no-response", foreign=True)
                self._next_spawn = 0.0
                self.restarts += 1
            return
        if now >= self._next_spawn:
            await self._spawn()

    async def _maintenance(self, now):
        """شغل دوري وهو سليم: تصفير الـ backoff، مراقبة الذاكرة، تدوير السجل، تجديد wake-lock."""
        child = self.proc if (self.proc is not None and self.proc.poll() is None) else None
        if child is not None and now - self._spawned_at >= self.cfg.stable_after:
            self._backoff = 0.0
            self._crash_times.clear()
            self.last_hint = ""
        if self.cfg.max_rss_mb > 0 and self._ticks % 4 == 0:
            pids = scan_omniroute_pids()
            rss = _rss_mb(pids)
            self._rss_strikes = self._rss_strikes + 1 if rss > self.cfg.max_rss_mb else 0
            if self._rss_strikes >= 3 and now - self._last_rss_restart > 1800:
                self._last_rss_restart = now
                self._rss_strikes = 0
                self._emit("memory", f"OmniRoute أكل {rss} ميغا ذاكرة (الحد {self.cfg.max_rss_mb}). أعيد تشغيله بهدوء.", {})
                await self._kill_all("memory", foreign=True)
                self._next_spawn = 0.0
                self.restarts += 1
                self.kick()
        if self._ticks % 20 == 0:
            self._trim_log()
        if time.time() - self._last_wake > 1200:
            await asyncio.get_running_loop().run_in_executor(None, self._wake_lock)

    # ------------------------------------------------------------------ الحالة والأحداث
    def _set_health(self, h):
        prev = self.state
        if h == "up":
            if prev != "up":
                self.state = "up"
                self.up_event.set()
                if not self._ever_up:
                    self._emit("started", "OmniRoute شغّال.", {})
                elif self._had_outage and self._down_since is not None:
                    down = time.time() - self._down_since
                    self._emit("recovered", f"OmniRoute رجع يشتغل بعد {int(down)} ثانية (رجّعه الحارس تلقائياً).",
                               {"downtime": down, "restarts": self.restarts})
                self._ever_up, self._had_outage, self._down_since = True, False, None
            self.last_ok = time.monotonic()
            return
        # مو شغّال
        if prev == "up":
            self._had_outage = True
            self._down_since = time.time()
            self.up_event.clear()
            self._emit("down", "OmniRoute وقف أو انقطع. الحارس دا يرجّعه.", {})
        elif self._down_since is None:
            self._down_since = time.time()
        child_alive = self.proc is not None and self.proc.poll() is None
        if self._installing:
            self.state = "installing"
        elif child_alive and time.monotonic() - self._spawned_at < self.cfg.start_grace:
            self.state = "starting"
        elif self._in_crashloop():
            self.state = "crashloop"
        else:
            self.state = h

    def _emit(self, kind, text, info=None, every=0.0):
        """يسجّل الحدث ويبلّغ البوت. every: لا نكرر نفس النوع قبل هذه المدة."""
        now = time.time()
        if every and now - self._last_event_at.get(kind, 0.0) < every:
            return
        self._last_event_at[kind] = now
        log.info("guardian[%s] %s", kind, text)
        self.events.append((now, kind, text))
        cb = self.on_event
        if cb is None:
            return
        try:
            r = cb(kind, text, dict(info or {}))
            if asyncio.iscoroutine(r):
                asyncio.ensure_future(r)
        except Exception:                                  # noqa: BLE001
            log.exception("guardian on_event callback failed")

    def _in_crashloop(self):
        cut = time.monotonic() - self.cfg.crashloop_window
        return sum(1 for t in self._crash_times if t >= cut) >= self.cfg.crashloop_n

    def _ensure_owner(self):
        return self.lock.try_acquire()

    # ------------------------------------------------------------------ التشغيل
    def _build_variants(self):
        port_flags = ["--port", str(self.port)] if self.port != DEFAULT_PORT else []
        base = shlex.split(self.cfg.args) if self.cfg.args is not None else ["--no-open"]
        out = []
        for v in (base + port_flags, port_flags, []):
            if v not in out:
                out.append(v)
        return out

    def _home(self):
        h = os.environ.get("HOME") or os.path.expanduser("~")
        if h and os.path.isdir(h) and os.access(h, os.W_OK):
            return h
        d = os.path.join(self.state_dir, "home")
        os.makedirs(d, exist_ok=True)
        return d

    def _path(self):
        home = self._home()
        local = [os.path.join(self.state_dir, "node", "bin"), os.path.join(self.npm_prefix, "bin")]   # Node/OmniRoute اللي نزّلهم الحارس
        parts = local + [os.path.join(TERMUX_PREFIX, "bin"), os.path.join(home, ".npm-global", "bin"),
                         os.path.join(home, ".local", "bin")] + list(_EXTRA_BIN_DIRS)
        cur = os.environ.get("PATH", "")
        have = set(cur.split(os.pathsep))
        pre = [p for p in parts if os.path.isdir(p) and (p in local or p not in have)]
        return os.pathsep.join(pre + ([cur] if cur else []))

    def _env(self):
        env = os.environ.copy()
        env["PATH"] = self._path()
        env["HOME"] = self._home()
        if self.cfg.heap_mb > 0 and "max-old-space-size" not in env.get("NODE_OPTIONS", ""):
            env["NODE_OPTIONS"] = (env.get("NODE_OPTIONS", "") + f" --max-old-space-size={self.cfg.heap_mb}").strip()
        env.setdefault("NO_COLOR", "1")
        env.setdefault("FORCE_COLOR", "0")
        env.setdefault("BROWSER", "none")                  # لا يحاول يفتح متصفح
        env.setdefault("NEXT_TELEMETRY_DISABLED", "1")
        env["PORT"] = str(self.port)                       # الاستضافات تثبّت PORT لشي ثاني (نقطة صحة البوت مثلاً)، وOmniRoute لازم يسمع على منفذه
        # OmniRoute يسمع افتراضياً على 0.0.0.0 وبدون إلزام مفتاح API: نربطه بالمحلي فقط (البوت يوصله عبر localhost).
        # HOSTNAME نفسه داخل الحاويات = معرّف الحاوية، وخوادم Next تسمع عليه بدل localhost فيفشل الاتصال.
        # إذا حددت OMNIROUTE_BIND أو OMNIROUTE_SERVER_HOST بنفسك يُحترم.
        bind = self.cfg.bind or os.environ.get("OMNIROUTE_SERVER_HOST") or "127.0.0.1"
        env["OMNIROUTE_SERVER_HOST"] = env["HOSTNAME"] = bind
        for pair in re.split(r"[,;]", self.cfg.extra_env or ""):
            if "=" in pair:
                k, v = pair.split("=", 1)
                env[k.strip()] = v.strip()
        return env

    def _which(self, name):
        return shutil.which(name, path=self._path())

    def _node_major(self, node):
        try:
            out = subprocess.run([node, "-v"], capture_output=True, text=True, timeout=20, env=self._env()).stdout.strip()
            m = re.match(r"v(\d+)", out)
            return int(m.group(1)) if m else 0
        except Exception:                                  # noqa: BLE001
            return 0

    async def _resolve_cmd(self):
        if self.cfg.cmd:
            return shlex.split(self.cfg.cmd)
        p = self._which("omniroute")
        if p:
            self._missing_since = None
            return [p]
        if self._installing:
            return None
        if self.cfg.auto_install and time.time() - self._install_at > 1800:
            self._install_at = time.time()
            self._installing = True
            self.state = "installing"
            self._emit("installing", "OmniRoute مو منصّب. دا أجهّزه تلقائياً (Node + OmniRoute، ياخذ كم دقيقة أول مرة)...", {})
            self._install_task = asyncio.get_running_loop().create_task(self._do_install())
            return None
        npx = self._which("npx")
        if npx:
            return [npx, "-y", "omniroute"]
        self._missing_since = self._missing_since or time.time()
        self._emit("missing", "OmniRoute مو منصّب وما كدرت أنصّبه تلقائياً. " + (self.last_hint or "نصّب Node 22 ثم  npm i -g omniroute"), {}, every=21600)
        return None

    # ---------------- تجهيز Node و OmniRoute (يشتغل بخيط منفصل) ----------------
    def _http_get(self, url, timeout=60):
        req = urllib.request.Request(url, headers={"User-Agent": "omniroute-guardian"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read()

    def _bootstrap_node(self):
        """ينزّل Node الرسمي (tar.gz) من nodejs.org بعد التحقق من SHA256، ويفكّه بمجلد الحارس. يرجع (ok, رسالة)."""
        if sys.platform != "linux":
            return False, "تنزيل Node التلقائي يعمل على لينكس فقط."
        if _is_musl():
            return False, "النظام musl (Alpine) وNode الرسمي ما يشتغل عليه: اختر صورة Debian/Ubuntu أو نصّب Node بنفسك."
        arch = {"x86_64": "x64", "amd64": "x64", "aarch64": "arm64", "arm64": "arm64", "armv7l": "armv7l"}.get(platform.machine().lower())
        if not arch:
            return False, f"معمارية غير مدعومة: {platform.machine()}"
        base = f"{self.cfg.node_mirror.rstrip('/')}/latest-v{self.cfg.node_major}.x"
        try:
            sums = self._http_get(base + "/SHASUMS256.txt").decode("utf-8", "replace")
        except Exception as e:                             # noqa: BLE001
            return False, f"ما كدرت أوصل لـ {self.cfg.node_mirror} ({type(e).__name__}): الاستضافة تمنع الاتصال الخارجي؟"
        want = None
        for line in sums.splitlines():
            parts = line.split()
            if len(parts) == 2 and parts[1].endswith(f"-linux-{arch}.tar.gz"):
                want = (parts[0], parts[1])
        if not want:
            return False, f"ما لقيت Node {self.cfg.node_major} لمعمارية {arch}."
        sha, fname = want
        tgz = os.path.join(self.state_dir, "node-download.tgz")
        tmp = os.path.join(self.state_dir, "node-tmp")
        final = os.path.join(self.state_dir, "node")
        try:
            log.info("guardian: تنزيل %s ...", fname)
            h = hashlib.sha256()
            req = urllib.request.Request(f"{base}/{fname}", headers={"User-Agent": "omniroute-guardian"})
            done = last = 0
            with urllib.request.urlopen(req, timeout=60) as r, open(tgz, "wb") as f:
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk:
                        break
                    f.write(chunk)
                    h.update(chunk)
                    done += len(chunk)
                    if done - last >= (10 << 20):
                        last = done
                        log.info("guardian: نزّلت %d ميغا من Node", done >> 20)
            if h.hexdigest().lower() != sha.lower():
                return False, "بصمة SHA256 للتنزيل ما تطابق، ألغيت التثبيت."
            shutil.rmtree(tmp, ignore_errors=True)
            with tarfile.open(tgz, "r:gz") as tar:
                _safe_extract(tar, tmp, strip=1)
            shutil.rmtree(final, ignore_errors=True)
            os.rename(tmp, final)
        except OSError as e:
            return False, f"فشل تجهيز Node ({e}). المساحة ممتلئة؟"
        except Exception as e:                             # noqa: BLE001
            return False, f"فشل تجهيز Node ({type(e).__name__}: {e})"
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
            try:
                os.remove(tgz)
            except OSError:
                pass
        major = self._node_major(os.path.join(final, "bin", "node"))
        if major < self.cfg.node_major:
            return False, "Node اللي نزّلته ما اشتغل على هذا النظام (غالباً مكتبات النظام ناقصة)."
        log.info("guardian: Node %s جاهز", major)
        return True, "ok"

    def _install_chain(self):
        """Node إن ناقص أو قديم ثم  npm install -g --prefix  لـ OmniRoute داخل مجلد الحارس (بدون صلاحيات خاصة). يرجع (ok, رسالة)."""
        node = self._which("node")
        if not node or self._node_major(node) < self.cfg.node_major:
            if os.path.isdir(TERMUX_PREFIX):
                return False, f"Node ناقص أو أقدم من {self.cfg.node_major}: نفّذ  pkg upgrade nodejs"
            if not self.cfg.auto_node:
                return False, f"Node {self.cfg.node_major}+ مو موجود (OMNIROUTE_AUTO_NODE=0)."
            ok, msg = self._bootstrap_node()
            if not ok:
                return False, msg
        npm = self._which("npm")
        if not npm:
            return False, "npm مو موجود."
        try:
            free_mb = shutil.disk_usage(self.state_dir).free >> 20
            if free_mb < 1500:
                return False, f"المساحة المتاحة {free_mb} ميغا فقط، وOmniRoute يحتاج حوالي 3 غيغا (و4 أثناء التنصيب)."
            if free_mb < 4500:
                log.warning("guardian: المساحة المتاحة %d ميغا؛ OmniRoute يحتاج ~3 غيغا (و~4 أثناء التنصيب)", free_mb)
        except OSError:
            pass
        env = self._env()
        orig = os.environ.get("NODE_OPTIONS")
        env.pop("NODE_OPTIONS", None)                      # سقف الذاكرة المخصّص لـ OmniRoute مو لعملية التنصيب
        if orig:
            env["NODE_OPTIONS"] = orig
        env.update({"npm_config_cache": os.path.join(self.state_dir, "npm-cache"), "npm_config_update_notifier": "false",
                    "npm_config_fund": "false", "npm_config_audit": "false"})
        os.makedirs(self.npm_prefix, exist_ok=True)
        try:
            with open(self.log_path, "ab") as lf:
                lf.write(f"\n===== [{time.strftime('%Y-%m-%d %H:%M:%S')}] guardian: npm install -g --prefix {self.npm_prefix} omniroute =====\n".encode())
                rc = subprocess.run([npm, "install", "-g", "--prefix", self.npm_prefix, "omniroute"], stdout=lf, stderr=subprocess.STDOUT,
                                    stdin=subprocess.DEVNULL, env=env, timeout=2400).returncode
        finally:
            shutil.rmtree(env["npm_config_cache"], ignore_errors=True)     # كاش npm يضاعف حجم التنصيب، وعلى الاستضافة كل ميغا يهم
        if rc != 0:
            tail = _tail(self.log_path, 40)
            why = next((h for rx, h in _HINTS if rx.search(tail)), "")
            return False, f"فشل npm install (رمز {rc}). " + (why + " " if why else "") + f"شوف السجل: {self.log_path}"
        if not self._which("omniroute"):
            return False, "اكتمل التنصيب لكن أمر omniroute ما ظهر."
        return True, "ok"

    async def _do_install(self):
        loop = asyncio.get_running_loop()
        try:
            ok, msg = await loop.run_in_executor(None, self._install_chain)
        except Exception as e:                             # noqa: BLE001
            ok, msg = False, f"{type(e).__name__}: {e}"
        self._installing = False
        if ok:
            self.last_hint = ""
            self._emit("installed", "تم تجهيز OmniRoute وسيشتغل هسه.", {})
            self._next_spawn = 0.0
        else:
            self.last_hint = "فشل التجهيز التلقائي: " + msg
            self._emit("missing", "OmniRoute مو منصّب وفشل تجهيزه تلقائياً. " + msg, {}, every=21600)
        self.kick()

    def _restore_data(self):
        """إذا لقى omniroute-data.tgz بجنب البوت يفكّه ببيت المستخدم (مرة وحدة) كي ينتقل إعداد OmniRoute من جهاز ثاني. يرجع 'ok:...' أو 'fail:...'."""
        arch = self.cfg.data_archive
        if not arch or not os.path.isfile(arch):
            return None
        home = self._home()
        stamp = time.strftime("%Y%m%d-%H%M%S")
        try:
            with tarfile.open(arch, "r:*") as tar:
                tops = set()
                for m in tar.getmembers():
                    parts = [p for p in m.name.split("/") if p not in ("", ".")]
                    if not parts:
                        continue
                    if not re.match(r"^\.?omniroute", parts[0], re.I):
                        raise ValueError(f"الأرشيف لازم يحتوي مجلد .omniroute فقط (لقيت: {parts[0]})")
                    tops.add(parts[0])
                for t in tops:
                    cur = os.path.join(home, t)
                    if os.path.lexists(cur):
                        os.rename(cur, f"{cur}.bak-{stamp}")          # لا نمسح شي: نسخة قديمة تبقى بجنبه
                _safe_extract(tar, home)
            os.rename(arch, f"{arch}.restored-{stamp}")
            return "ok:" + ",".join(sorted(tops))
        except Exception as e:                             # noqa: BLE001
            return f"fail:{e}"

    async def _spawn(self):
        cmd = await self._resolve_cmd()
        if not cmd:
            return
        argv = cmd + self._variants[self._variant_idx]
        self._rotate_if_big()
        cwd = self.cfg.cwd or os.path.expanduser("~")
        if not os.path.isdir(cwd):
            cwd = None
        try:
            lf = open(self.log_path, "ab", buffering=0)
            lf.write(f"\n===== [{time.strftime('%Y-%m-%d %H:%M:%S')}] guardian: starting {' '.join(argv)} =====\n".encode())
            self.proc = subprocess.Popen(argv, stdin=subprocess.DEVNULL, stdout=lf, stderr=subprocess.STDOUT,
                                         env=self._env(), cwd=cwd, start_new_session=True, close_fds=True)
            lf.close()
        except OSError as e:
            log.warning("guardian: تعذّر تشغيل %s: %s", argv[0], e)
            self.last_hint = f"تعذّر تشغيل الأمر: {e}"
            self._bump_backoff(0.0)
            return
        self._spawned_at = time.monotonic()
        self.spawns += 1
        self.state = "starting"
        try:
            with open(self.pid_path, "w") as f:
                json.dump({"pid": self.proc.pid, "started": time.time(), "argv": argv, "guardian": os.getpid()}, f)
        except OSError:
            pass
        log.info("guardian: شغّلت OmniRoute (PID %s): %s", self.proc.pid, " ".join(argv))

    def _bump_backoff(self, uptime):
        if uptime >= self.cfg.stable_after:
            self._backoff = self.cfg.backoff_initial
        else:
            self._backoff = min(max(self._backoff * 2, self.cfg.backoff_initial), self.cfg.backoff_max)
        if self._in_crashloop():
            self._backoff = max(self._backoff, self.cfg.crashloop_backoff)
        self._next_spawn = time.monotonic() + self._backoff * (0.8 + 0.4 * random.random())

    def _reap_exit(self):
        """يلتقط خروج الابن، يحلّل السبب، ويحدد متى نعيد المحاولة."""
        if self.proc is None:
            return
        rc = self.proc.poll()
        if rc is None:
            return
        uptime = time.monotonic() - self._spawned_at
        self.proc = None
        if self._expected_exit:
            self._expected_exit = False
            return
        tail = _tail(self.log_path, 40)
        self.last_exit = {"rc": rc, "uptime": uptime, "at": time.time()}
        # أعلام غير معروفة؟ جرّب بدونها فوراً
        if uptime < 20 and rc != 0 and self._variant_idx < len(self._variants) - 1 and _BAD_FLAG_RE.search(tail):
            self._variant_idx += 1
            self._next_spawn = 0.0
            log.warning("guardian: OmniRoute ما قبل الأعلام، أجرّب بدونها: %s", self._variants[self._variant_idx])
            return
        self.restarts += 1
        self._crash_times.append(time.monotonic())
        self.last_hint = next((h for rx, h in _HINTS if rx.search(tail)), "")
        self._bump_backoff(uptime)
        log.warning("guardian: OmniRoute خرج (rc=%s) بعد %ss، إعادة بعد %.0fs. %s", rc, int(uptime),
                    max(self._next_spawn - time.monotonic(), 0), self.last_hint)
        if self._in_crashloop():
            lines = "\n".join(tail.splitlines()[-4:])
            self._emit("crashloop", f"OmniRoute يتعطّل بالتكرار ({self.cfg.crashloop_n}+ مرات خلال "
                                    f"{int(self.cfg.crashloop_window // 60)} دقايق). " + (self.last_hint or "شوف السجل.") +
                                    ("\n" + lines if lines else ""), {"rc": rc}, every=1800)

    # ------------------------------------------------------------------ الإيقاف
    async def _terminate(self, pid, group, alive):
        send = (lambda s: os.killpg(pid, s)) if group else (lambda s: os.kill(pid, s))
        try:
            send(signal.SIGTERM)
        except ProcessLookupError:
            return True
        except PermissionError:
            return False
        t0 = time.monotonic()
        while time.monotonic() - t0 < self.cfg.stop_grace:
            if not alive():
                return True
            await asyncio.sleep(0.25)
        try:
            send(signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass
        for _ in range(20):
            if not alive():
                return True
            await asyncio.sleep(0.25)
        return not alive()

    async def _kill_all(self, reason, foreign=True):
        """يسكّر ابننا (مجموعته كاملة) وأي نسخة OmniRoute غريبة/يتيمة تمسك المنفذ."""
        log.info("guardian: إيقاف OmniRoute (%s)", reason)
        proc = self.proc
        if proc is not None and proc.poll() is None:
            self._expected_exit = True
            await self._terminate(proc.pid, True, lambda: proc.poll() is not None)
            try:
                proc.wait(timeout=0.1)
            except Exception:                              # noqa: BLE001
                pass
        self.proc = None
        if foreign:
            for pid in scan_omniroute_pids():
                await self._terminate(pid, False, lambda p=pid: not _alive(p))
        try:
            os.remove(self.pid_path)
        except OSError:
            pass
        self._expected_exit = False

    # ------------------------------------------------------------------ سجل و wake-lock
    def _rotate_if_big(self):
        self._trim_log()

    def _trim_log(self):
        try:
            size = os.path.getsize(self.log_path)
        except OSError:
            return
        if size <= self.cfg.log_max_mb * 1048576:
            return
        try:
            with open(self.log_path, "rb") as f:
                f.seek(max(0, size - 1_000_000))
                tail = f.read()
            with open(self.log_path + ".1", "wb") as g:
                g.write(tail)
            os.truncate(self.log_path, 0)                  # الابن يكتب بـ O_APPEND فيكمل بسلام
        except OSError:
            pass

    def _wake_lock(self):
        self._last_wake = time.time()
        exe = shutil.which("termux-wake-lock", path=self._path())
        if not exe:
            return
        try:
            subprocess.run([exe], timeout=15, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL)
        except Exception:                                  # noqa: BLE001
            pass


# ----------------------------------------------------------------------------
#  نسخة واحدة لكل عملية (البوت يعيد بناء حلقته عند الأعطال فنبقي نفس الحارس والقفل)
# ----------------------------------------------------------------------------
_SINGLETON = None


def get_guardian(base_url=None, on_event=None, **kw):
    global _SINGLETON
    if _SINGLETON is None:
        _SINGLETON = OmniRouteGuardian(base_url, on_event, **kw)
    elif on_event is not None:
        _SINGLETON.on_event = on_event
    return _SINGLETON


# ----------------------------------------------------------------------------
#  سطر الأوامر
# ----------------------------------------------------------------------------
def _cli_probe(g):
    return asyncio.run(probe(g.host, g.port, 6.0))


def _cli_status(g):
    h = _cli_probe(g)
    print(f"المنفذ {g.port}: {h}")
    pids = scan_omniroute_pids()
    print("عمليات OmniRoute:", pids or "ماكو", (f"| ذاكرة ≈ {_rss_mb(pids)} ميغا" if pids else ""))
    try:
        with open(g.pid_path) as f:
            print("pidfile:", f.read().strip())
    except OSError:
        pass
    print("موقوف يدوياً:" , "نعم" if g.paused else "لا")
    print("آخر السجل:\n" + (_tail(g.log_path, 8) or "(فاضي)"))
    return 0 if h == "up" else 1


def _cli_stop(g):
    open(g.pause_path, "w").close()                        # أي حارس شغّال يتوقف عن إرجاعه
    pids = scan_omniroute_pids()
    for p in pids:
        try:
            os.kill(p, signal.SIGTERM)
        except OSError:
            pass
    time.sleep(min(g.cfg.stop_grace, 3))
    for p in pids:
        if _alive(p):
            try:
                os.kill(p, signal.SIGKILL)
            except OSError:
                pass
    print(f"أوقفت {len(pids)} عملية. الحراسة موقوفة؛ نفّذ resume لتشغيله من جديد.")
    return 0


def _cli_resume(g):
    try:
        os.remove(g.pause_path)
    except OSError:
        pass
    print("رُفع الإيقاف اليدوي. الحارس (إن كان شغّال) يرجّع OmniRoute خلال ثواني.")
    return 0


def _cli_restart(g):
    _cli_resume(g)
    pids = scan_omniroute_pids()
    for p in pids:
        try:
            os.kill(p, signal.SIGTERM)
        except OSError:
            pass
    print(f"قتلت {len(pids)} عملية؛ الحارس سيرجّعه. إذا ما اشتغل تأكد أن الحارس يعمل (python omniroute_guardian.py run أو البوت).")
    return 0


def _cli_logs(g, n, follow):
    print(_tail(g.log_path, n) or "(السجل فاضي)")
    if not follow:
        return 0
    try:
        with open(g.log_path, "rb") as f:
            f.seek(0, os.SEEK_END)
            while True:
                chunk = f.read()
                if chunk:
                    sys.stdout.write(chunk.decode("utf-8", "replace"))
                    sys.stdout.flush()
                else:
                    time.sleep(0.5)
    except (KeyboardInterrupt, OSError):
        return 0


def _cli_check(g):
    ok = True

    def row(good, label, detail=""):
        nonlocal ok
        ok = ok and good
        print(("[ OK ] " if good else "[خطأ] ") + label + (f"  → {detail}" if detail else ""))
    node = g._which("node")
    ver = ""
    if node:
        try:
            ver = subprocess.run([node, "-v"], capture_output=True, text=True, timeout=10).stdout.strip()
        except Exception:                                  # noqa: BLE001
            pass
    major = int(re.match(r"v(\d+)", ver).group(1)) if re.match(r"v(\d+)", ver) else 0
    row(bool(node) and major >= 22, "Node.js (يلزم 22+)", ver or "مو موجود: pkg install nodejs")
    cmd = g.cfg.cmd or g._which("omniroute")
    row(bool(cmd), "أمر omniroute", cmd or "مو منصّب: npm i -g omniroute")
    row(bool(shutil.which("termux-wake-lock", path=g._path())) or not os.path.isdir(TERMUX_PREFIX),
        "termux-wake-lock", "موجود" if shutil.which("termux-wake-lock", path=g._path()) else "غير موجود (pkg install termux-tools)")
    row(os.access(g.state_dir, os.W_OK), "مجلد الحالة قابل للكتابة", g.state_dir)
    row(g.cfg.local, "العنوان محلي (الحارس يقدر يشغّله)", f"{g.host}:{g.port}")
    h = _cli_probe(g)
    row(h == "up", "OmniRoute يرد على المنفذ", h)
    print("\nالنتيجة:", "كل شي تمام" if ok else "في نقاط تحتاج إصلاح (فوق).")
    return 0 if ok else 1


def main(argv=None):
    ap = argparse.ArgumentParser(description="حارس OmniRoute")
    ap.add_argument("cmd", choices=["run", "status", "stop", "resume", "restart", "logs", "check"])
    ap.add_argument("-n", type=int, default=40, help="عدد سطور السجل")
    ap.add_argument("-f", action="store_true", help="تتبّع السجل")
    ap.add_argument("--url", default=None, help="OMNIROUTE_BASE_URL")
    a = ap.parse_args(argv)
    logging.basicConfig(format="%(asctime)s | %(levelname)s | %(message)s", level=logging.INFO)
    g = OmniRouteGuardian(a.url, on_event=None)
    if a.cmd == "status":
        return _cli_status(g)
    if a.cmd == "stop":
        return _cli_stop(g)
    if a.cmd == "resume":
        return _cli_resume(g)
    if a.cmd == "restart":
        return _cli_restart(g)
    if a.cmd == "logs":
        return _cli_logs(g, a.n, a.f)
    if a.cmd == "check":
        return _cli_check(g)

    async def run():
        if not await g.start():
            print("الحارس غير مفعّل لهذا العنوان (غير محلي أو OMNIROUTE_AUTOSTART=0).")
            return
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for s in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(s, stop.set)
            except NotImplementedError:                    # ويندوز
                pass
        await stop.wait()
        await g.stop(kill=True)                            # كخدمة مستقلة: انطفاؤها يطفّي OmniRoute معها
    asyncio.run(run())
    return 0


if __name__ == "__main__":
    sys.exit(main())
