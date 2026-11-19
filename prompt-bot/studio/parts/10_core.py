# ============================================================================
#  الاستوديو الذكي (Agent Studio)
#  مسار عمل حيّ + موجّه الطلب + وصفات التخصص + مراجعة ذاتية + بطاقة النتيجة + مكتبتي
#  فكرة «مسار العمل» مأخوذة من مفهوم Telegram AI Agent Chats: المستخدم يشوف الوكيل شنو دا يسوي بدل «typing...».
#  كل شي هنا اختياري: STUDIO_ON=0 يرجّع البوت لسلوكه القديم، وأي عطل داخله ما يوقف الطلب.
# ============================================================================
import itertools as _it

STUDIO_ON = _env("STUDIO_ON", 1, int)
STUDIO_EDIT_GAP = _env("STUDIO_EDIT_GAP", 1.2, float)            # أقل فاصل بين تعديلين للرسالة الحيّة (ثانية)
STUDIO_FIRST_DELAY = _env("STUDIO_FIRST_DELAY", 0.9, float)      # الرسالة الحيّة تظهر بعد هذي المدة فقط (لا وميض للطلبات السريعة)
STUDIO_ROUTER_TIMEOUT = _env("STUDIO_ROUTER_TIMEOUT", 20.0, float)
STUDIO_CRITIC_TIMEOUT = _env("STUDIO_CRITIC_TIMEOUT", 30.0, float)
STUDIO_BUDGET = _env("STUDIO_BUDGET", 120.0, float)              # ميزانية الوقت للطلب: بعد 60% منها نتخطى المراجعة
STUDIO_CLARIFY_WAIT = _env("STUDIO_CLARIFY_WAIT", 75.0, float)   # انتظار جواب سؤال التوضيح قبل ما نكمل بافتراضات
STUDIO_REACTIONS = _env("STUDIO_REACTIONS", 1, int)              # تفاعلات الرسائل: بالانتظار / دا أشتغل / خلصت
STUDIO_TRY_COOLDOWN = _env("STUDIO_TRY_COOLDOWN", 45.0, float)   # الفاصل بين «تجربة سريعة» للمستخدم الواحد
STUDIO_KEEP_RESULTS = 60                                         # كم نتيجة غير محفوظة نبقي لكل مستخدم
STUDIO_LIB_MAX = 100                                             # أقصى عدد محفوظات بمكتبة المستخدم

try:                                                             # PTB >= 21: تفاعلات الرسائل (Bot API 7.0)
    from telegram import ReactionTypeEmoji
except Exception:                                                # noqa: BLE001
    ReactionTypeEmoji = None

STUDIO_MODES = {"fast": "سريع", "smart": "ذكي", "deep": "عميق"}
SP_DEFAULTS = {"mode": "smart", "path": 1, "ascii": 1, "critic": 1, "clarify": 1, "card": 1}


# ----------------------------------------------------------------------------
#  قاعدة البيانات: جداول الاستوديو (تُنشأ عند أول استعمال، فلا تحتاج ترقية يدوية)
# ----------------------------------------------------------------------------
_STUDIO_SCHEMA = """
CREATE TABLE IF NOT EXISTS studio_prefs(
    uid INTEGER PRIMARY KEY, mode TEXT DEFAULT 'smart', path INTEGER DEFAULT 1, ascii INTEGER DEFAULT 1,
    critic INTEGER DEFAULT 1, clarify INTEGER DEFAULT 1, card INTEGER DEFAULT 1);
CREATE TABLE IF NOT EXISTS studio_results(
    id INTEGER PRIMARY KEY AUTOINCREMENT, uid INTEGER, ts INTEGER, domain TEXT DEFAULT 'general', target TEXT DEFAULT '',
    title TEXT DEFAULT '', request TEXT DEFAULT '', prompt TEXT DEFAULT '', score INTEGER DEFAULT -1, secs REAL DEFAULT 0,
    steps INTEGER DEFAULT 0, parts INTEGER DEFAULT 1, refined INTEGER DEFAULT 0, router TEXT DEFAULT '', mode TEXT DEFAULT '',
    saved INTEGER DEFAULT 0, feedback INTEGER DEFAULT 0);
CREATE INDEX IF NOT EXISTS studio_results_uid ON studio_results(uid, id DESC);
"""
_studio_schema_for = None


def _sdb():
    """قاعدة البيانات مع ضمان وجود جداول الاستوديو."""
    global _studio_schema_for
    d = D()
    if _studio_schema_for is not d:
        with d.lock:
            d.conn.executescript(_STUDIO_SCHEMA)
        _studio_schema_for = d
    return d


def sprefs(uid):
    d = _sdb()
    row = d.one("SELECT * FROM studio_prefs WHERE uid=?", (uid,))
    if row is None:
        d.x("INSERT OR IGNORE INTO studio_prefs(uid) VALUES(?)", (uid,))
        row = d.one("SELECT * FROM studio_prefs WHERE uid=?", (uid,))
    return dict(row)


def set_sprefs(uid, **kv):
    d = _sdb()
    sprefs(uid)
    for k, v in kv.items():
        if k not in SP_DEFAULTS:
            raise KeyError(k)
        d.x(f"UPDATE studio_prefs SET {k}=? WHERE uid=?", (v, uid))


def studio_save_result(uid, **kw):
    """يحفظ النتيجة (ليرجع لها الأزرار تحت البرومبت) ويُبقي آخر STUDIO_KEEP_RESULTS غير محفوظة فقط."""
    d = _sdb()
    cur = d.x(
        "INSERT INTO studio_results(uid, ts, domain, target, title, request, prompt, score, secs, steps, parts, refined, router, mode) "
        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (uid, now_ts(), kw.get("domain", "general"), kw.get("target", "")[:60], kw.get("title", "")[:80],
         kw.get("request", "")[:3000], kw.get("prompt", "")[:HISTORY_ITEM_CHARS * 2], int(kw.get("score", -1)),
         float(kw.get("secs", 0)), int(kw.get("steps", 0)), int(kw.get("parts", 1)), int(bool(kw.get("refined"))),
         kw.get("router", ""), kw.get("mode", "")))
    d.x("DELETE FROM studio_results WHERE uid=? AND saved=0 AND id NOT IN "
        "(SELECT id FROM studio_results WHERE uid=? ORDER BY id DESC LIMIT ?)", (uid, uid, STUDIO_KEEP_RESULTS))
    return cur.lastrowid


def studio_get_result(rid, uid):
    return _sdb().one("SELECT * FROM studio_results WHERE id=? AND uid=?", (rid, uid))


def studio_forget(uid):
    """«مسح بياناتي»: يمسح تفضيلات الاستوديو وكل نتائجه ومحفوظاته."""
    d = _sdb()
    d.x("DELETE FROM studio_results WHERE uid=?", (uid,))
    d.x("DELETE FROM studio_prefs WHERE uid=?", (uid,))


def studio_profile_line(uid):
    """بصمة المستخدم من استعماله السابق (مجالاته وتقييماته) لتوجيه النموذج. فارغة إن كان جديداً."""
    d = _sdb()
    rows = d.q("SELECT domain, COUNT(*) n, SUM(CASE WHEN feedback<0 THEN 1 ELSE 0 END) bad FROM studio_results "
               "WHERE uid=? GROUP BY domain ORDER BY n DESC LIMIT 4", (uid,))
    total = sum(r["n"] for r in rows)
    if total < 3:
        return ""
    parts = [f"{DOMAIN_LABEL.get(r['domain'], r['domain'])} ({r['n']})" for r in rows]
    line = "أكثر مجالاته استعمالاً: " + "، ".join(parts) + "."
    weak = [DOMAIN_LABEL.get(r["domain"], r["domain"]) for r in rows if r["bad"] and r["bad"] >= 2]
    if weak:
        line += " قيّم برومبتات " + "، ".join(weak) + " السابقة بأنها ضعيفة أكثر من مرة: زد الدقة والتفصيل والأمثلة فيها."
    return line


# ----------------------------------------------------------------------------
#  أدوات النص العربي
# ----------------------------------------------------------------------------
def ar_steps(n):
    return "خطوة وحدة" if n == 1 else "خطوتين" if n == 2 else f"{n} خطوات" if n <= 10 else f"{n} خطوة"


def ar_parts(n):
    return "جزء واحد" if n == 1 else "جزءين" if n == 2 else f"{n} أجزاء" if n <= 10 else f"{n} جزء"


def ar_secs(s):
    s = int(round(s))
    if s < 60:
        return "ثانية وحدة" if s <= 1 else "ثانيتين" if s == 2 else f"{s} ثواني" if s <= 10 else f"{s} ثانية"
    m, r = divmod(s, 60)
    return f"{m} دقيقة" + (f" و{r} ثانية" if r else "")


def _fmt_t(s):
    s = max(0.0, s)
    return f"{s:.1f}ث" if s < 10 else f"{int(s)}ث"


def _clock(s):
    s = int(max(0, s))
    return f"{s // 60:02d}:{s % 60:02d}"


_ASCII_NOISE = "░▒▓▌▐▄▀"


def ascii_frames(text, frames=3, seed=1):
    """مراحل «فكّ التشفير»: النص يظهر تدريجياً من رموز عشوائية (المرحلة الأخيرة الحقيقية يرسمها المستدعي)."""
    rnd = random.Random(seed)
    idx = [i for i, ch in enumerate(text) if not ch.isspace() and ch not in "<>&"]
    movable = set(idx)
    order = idx[:]
    rnd.shuffle(order)
    out = []
    for f in range(1, frames + 1):
        shown = set(order[:int(len(order) * f / (frames + 1))])
        out.append("".join(ch if (i not in movable or i in shown) else rnd.choice(_ASCII_NOISE) for i, ch in enumerate(text)))
    return out


# ----------------------------------------------------------------------------
#  نموذج المسار: خطوات الوكيل وحالتها (مثل AgentRun بمفهوم Telegram AI Agent Chats)
# ----------------------------------------------------------------------------
class RunStopped(Exception):
    """المستخدم ضغط «إيقاف»."""


class Step:
    __slots__ = ("key", "label", "status", "t0", "t1", "detail")

    def __init__(self, key, label):
        self.key, self.label, self.status, self.t0, self.t1, self.detail = key, label, "pending", 0.0, 0.0, ""

    def secs(self, now=None):
        end = self.t1 or (now or time.time())
        return max(0.0, end - self.t0) if self.t0 else 0.0


_RUN_SEQ = _it.count(random.randint(1000, 9000))
_RUNS = {}                                    # رقم الطلب ← AgentRun (للأزرار: إيقاف، جواب سؤال)


class AgentRun:
    """طلب واحد: خطوات، حالة، وقت، وأسئلة التوضيح. status: running | waiting | answering | finished | stopped."""

    def __init__(self, uid, chat_id):
        self.id = next(_RUN_SEQ)
        self.uid, self.chat_id = uid, chat_id
        self.steps = []
        self.status = "running"
        self.t0, self.t1 = time.time(), 0.0
        self.stopped = False
        self.task = None
        self.queue = 0                        # كم طلب ينتظر بعده
        self.questions, self.answers, self.ask_idx, self.asking = [], {}, 0, False
        self.free_text = ""
        self.ask_event = asyncio.Event()
        self.live = None                      # LivePath (يُربط من Studio)

    # --- الخطوات ---
    def get(self, key):
        return next((s for s in self.steps if s.key == key), None)

    def running(self):
        return next((s for s in self.steps if s.status == "running"), None)

    def begin(self, key, label=None, detail=""):
        now = time.time()
        for s in self.steps:
            if s.status == "running" and s.key != key:
                s.status, s.t1 = "done", now
        st = self.get(key)
        if st is None:
            st = Step(key, label or key)
            self.steps.append(st)
        elif label:
            st.label = label
        st.status, st.t0, st.t1 = "running", now, 0.0
        if detail:
            st.detail = detail
        return st

    def finish(self, key, detail=None):
        st = self.get(key)
        if st is not None:
            st.status, st.t1 = "done", time.time()
            if detail is not None:
                st.detail = detail

    def fail(self, key, detail=""):
        st = self.get(key)
        if st is not None:
            st.status, st.t1, st.detail = "failed", time.time(), detail

    def skip(self, key, label, detail=""):
        st = self.get(key) or Step(key, label)
        if st not in self.steps:
            self.steps.append(st)
        st.status, st.t0, st.t1, st.detail = "skipped", time.time(), time.time(), detail

    def n_steps(self):
        return sum(1 for s in self.steps if s.status in ("done", "failed", "waiting"))

    def elapsed(self, now=None):
        return max(0.0, (self.t1 or (now or time.time())) - self.t0)

    @property
    def live_now(self):
        return self.status in ("running", "waiting", "answering")

    # --- الإيقاف والانتظار ---
    def stop(self):
        self.stopped = True
        self.ask_event.set()
        if self.task is not None and not self.task.done():
            self.task.cancel()

    def release(self):
        self.ask_event.set()

    async def guard(self, coro):
        """ينفّذ عملاً طويلاً بحيث يكدر زر «إيقاف» يلغيه فوراً (يرمي RunStopped)."""
        if self.stopped:
            coro.close()
            raise RunStopped()
        self.task = asyncio.ensure_future(coro)
        try:
            return await self.task
        except asyncio.CancelledError:
            if self.stopped:
                raise RunStopped()
            if self.task is not None and not self.task.done():
                self.task.cancel()
            raise
        finally:
            self.task = None


# ----------------------------------------------------------------------------
#  عرض المسار: رسالة حيّة (HTML) ثم تنطوي لسطر واحد قابل للفتح
# ----------------------------------------------------------------------------
_SPIN = ("◐", "◓", "◑", "◒")
_MAX_ROWS = 7
_LIVE_HEADS = {"write": "دا أكتب البرومبت", "review": "دا أراجع الجودة", "refine": "دا أحسّن البرومبت", "recheck": "دا أعيد التقييم"}


def _step_line(s, now, tick=0, cursor=True):
    if s.status == "running":
        glyph = _SPIN[tick % 4]
        label = esc_html(s.label) + (" ▌" if cursor and s.key in ("write", "refine") and tick % 2 == 0 else "")
        tail = " · " + _fmt_t(now - s.t0)
    elif s.status == "waiting":
        glyph, label, tail = "◇", esc_html(s.label), ""
    elif s.status == "done":
        glyph, label, tail = "✓", esc_html(s.label), " · " + _fmt_t(s.t1 - s.t0)
    elif s.status == "failed":
        glyph, label, tail = "✕", esc_html(s.label), ""
    elif s.status == "skipped":
        glyph, label, tail = "–", esc_html(s.label), ""
    else:
        glyph, label, tail = "◦", esc_html(s.label), ""
    det = f" ({esc_html(s.detail)})" if s.detail else ""
    return f"{glyph} {label}{det}{tail}"


def render_live(run, now=None, tick=0, cursor=True):
    now = now or time.time()
    steps = [s for s in run.steps if s.status != "pending"]
    hidden = max(0, len(steps) - _MAX_ROWS)
    lines = [f"◦ +{ar_steps(hidden)} سابقة"] if hidden else []
    lines += [_step_line(s, now, tick, cursor) for s in steps[-_MAX_ROWS:]]
    if not lines:
        lines = [f"{_SPIN[tick % 4]} أجهّز الطلب"]
    cur = run.running()
    head = _LIVE_HEADS.get(cur.key, "دا أشتغل على طلبك") if cur is not None else "دا أشتغل على طلبك"
    out = f"<b>{head}</b> <code>{_clock(run.elapsed(now))}</code>\n<blockquote>" + "\n".join(lines) + "</blockquote>"
    if run.queue:
        out += "\n" + esc_html(f"بالانتظار بعد هذا الطلب: {run.queue}")
    return out


def render_fold(run, header=None):
    """ملخص المسار بعد الانتهاء: السطر الأول ظاهر، والباقي ينفتح بالضغط (اقتباس قابل للطي)."""
    secs = run.elapsed()
    head = header or f"اشتغلت {ar_secs(secs)} · {ar_steps(run.n_steps())}"
    body = "\n".join(_step_line(s, run.t1 or time.time(), 0, False) for s in run.steps if s.status != "pending")
    return f"<blockquote expandable><b>{esc_html(head)}</b>\n{body}</blockquote>"


def stop_kb(run):
    return kb([[btn("إيقاف", f"u:st:{run.id}", "danger")]])


def render_ask(run):
    qs = run.questions
    i = min(run.ask_idx, len(qs) - 1)
    prev = "\n".join(f"✓ {qs[k]['q']} ← {qs[k]['options'][a]}" for k, a in sorted(run.answers.items()) if k < len(qs))
    out = f"<b>قبل لا أبدأ، سؤال سريع ({i + 1}/{len(qs)})</b>\n<blockquote>{esc_html(qs[i]['q'])}</blockquote>"
    if prev:
        out += f"\n<blockquote>{esc_html(prev)}</blockquote>"
    return out + "\n" + esc_html("اختار بالأزرار، أو اكتب جوابك بجملة، أو دوس «ابنِ مباشرة».")


def ask_kb(run):
    i = min(run.ask_idx, len(run.questions) - 1)
    rows, row = [], []
    for oi, opt in enumerate(run.questions[i]["options"]):
        row.append(btn(opt[:32], f"u:ca:{run.id}.{i}.{oi}", "primary"))
        if len(row) == 2:
            rows.append(row)
            row = []
    if row:
        rows.append(row)
    rows.append([btn("ابنِ مباشرة بافتراضاتك", f"u:cs:{run.id}", "success"), btn("إيقاف", f"u:st:{run.id}", "danger")])
    return kb(rows)


def _markup_sig(markup):
    if markup is None:
        return ""
    return "|".join(f"{b.text}:{b.callback_data}" for row in markup.inline_keyboard for b in row)


def _active_gap():
    """تحت الضغط (طلبات كثيرة بنفس الوقت) نباعد التعديلات كي لا يحدّنا تيليجرام."""
    return min(6.0, STUDIO_EDIT_GAP + 0.25 * max(0, len(_RUNS) - 8))


class LivePath:
    """رسالة واحدة تتحدّث بمكانها (بحدّ أدنى بين التعديلات)، ثم تنطوي أو تُحذف."""

    def __init__(self, bot, chat_id, run, view, show=True):
        self.bot, self.chat_id, self.run, self.view, self.show = bot, chat_id, run, view, show
        self.msg, self._last, self._tick = None, None, 0
        self._lock = asyncio.Lock()
        self._task = None
        self._closed = False
        self._next_ok = 0.0

    def start(self):
        self._task = asyncio.ensure_future(self._loop())

    async def _loop(self):
        try:
            await asyncio.sleep(STUDIO_FIRST_DELAY)
            while not self._closed:
                await self.push()
                await asyncio.sleep(_active_gap())
        except asyncio.CancelledError:
            pass
        except Exception:                                    # noqa: BLE001
            logger.exception("live path loop failed")

    def close(self):
        self._closed = True
        if self._task is not None and not self._task.done():
            self._task.cancel()

    async def push(self, force=False):
        run = self.run
        if self._closed or not run.live_now:
            return
        if not self.show and not (run.status == "waiting" and run.asking):
            return
        async with self._lock:
            if self._closed:
                return
            self._tick += 1
            text, markup = self.view(self._tick)
            sig = text + "||" + _markup_sig(markup)
            if sig == self._last and not force:
                return
            if time.monotonic() < self._next_ok and not force:
                return
            try:
                if self.msg is None:
                    self.msg = await send_html(self.bot, self.chat_id, text, **({"reply_markup": markup} if markup else {}))
                    self._last = sig
                elif await self._edit(text, markup):
                    self._last = sig
            except Exception as e:                           # noqa: BLE001
                logger.debug("live path push failed: %s", e)

    async def _edit(self, text, markup):
        """تعديل مباشر بلا إعادة محاولة طويلة. يرجع True إن نجح (أو لم يتغير النص)."""
        kw = {"parse_mode": ParseMode.HTML}
        if markup is not None:
            kw["reply_markup"] = markup
        for _attempt in range(2):
            try:
                await self.bot.edit_message_text(text, chat_id=self.chat_id, message_id=self.msg.message_id, **kw)
                return True
            except RetryAfter as e:
                self._next_ok = time.monotonic() + _retry_secs(e) + 0.5
                return False
            except BadRequest as e:
                low = str(e).lower()
                if "not modified" in low:
                    return True
                if markup is not None and _style_rejected(e, kw):
                    kw["reply_markup"] = plain_kb(markup)
                    continue
                if "parse" in low or "entit" in low:
                    kw.pop("parse_mode", None)
                    text = strip_tags(text)[:4096]
                    continue
                if any(w in low for w in ("message to edit not found", "can't be edited", "message is not found")):
                    self.msg, self._last = None, None        # المستخدم حذفها: ننشئ غيرها بالدورة الجاية
                return False
            except (TimedOut, NetworkError):
                return False
        return False

    async def fold(self, run, header=None, animate=False):
        """يحوّل الرسالة الحيّة إلى ملخص قابل للطي. يرجع False إن ما ظهرت رسالة أصلاً."""
        self.close()
        if not self.show:                                    # المستخدم مطفّي المسار الحي: ما نخلّي أثراً (إلا سؤال التوضيح المؤقت)
            await self.vanish()
            return False
        async with self._lock:
            if self.msg is None:
                return False
            try:
                if animate and len(_RUNS) < 12:
                    head = header or f"اشتغلت {ar_secs(run.elapsed())} · {ar_steps(run.n_steps())}"
                    for frame in ascii_frames(head, 3, seed=run.id):
                        body = f"<blockquote expandable><b>{esc_html(frame)}</b>\n…</blockquote>"
                        if not await self._edit(body, None):
                            break
                        await asyncio.sleep(0.45)
                await self._edit(render_fold(run, header), None)
            except Exception as e:                           # noqa: BLE001
                logger.debug("fold failed: %s", e)
            return True

    async def vanish(self):
        self.close()
        async with self._lock:
            if self.msg is not None:
                try:
                    await self.bot.delete_message(self.chat_id, self.msg.message_id)
                except Exception:                            # noqa: BLE001
                    pass
                self.msg = None
