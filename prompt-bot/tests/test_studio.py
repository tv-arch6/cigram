#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
اختبار الاستوديو الذكي بلا شبكة: بوت تيليجرام وهمي + نموذج OmniRoute وهمي، ويمرّ بـ serve() الحقيقي كاملاً.

    pip install "python-telegram-bot>=22" httpx pillow
    python3 tests/test_studio.py  prompt.py  [مجلد حفظ صورة البطاقة]
"""
import asyncio
import html.parser
import importlib.util
import json
import os
import sys
import tempfile
import traceback
from types import SimpleNamespace

TARGET = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "prompt.py")
OUT_DIR = sys.argv[2] if len(sys.argv) > 2 else None
_tmp = tempfile.mkdtemp()
os.environ.update({"BOT_TOKEN": "123456:TEST", "BOT_DB": os.path.join(_tmp, "t.db"), "STUDIO_FIRST_DELAY": "0.05",
                   "STUDIO_EDIT_GAP": "0.05", "STUDIO_CLARIFY_WAIT": "5", "STUDIO_TRY_COOLDOWN": "0", "FAST_LIBS": "0"})
spec = importlib.util.spec_from_file_location("prompt_studio", TARGET)
M = importlib.util.module_from_spec(spec)
sys.modules["prompt_studio"] = M
spec.loader.exec_module(M)

PASS, FAIL = [], []


def check(name, cond, extra=""):
    (PASS if cond else FAIL).append(name)
    print(("  ok   " if cond else "  FAIL ") + name + (f"  <- {extra}" if (extra and not cond) else ""))


# ---------------------------------------------------------------- بوت وهمي يسجّل كل شي ---------------------------------
class _Tags(html.parser.HTMLParser):
    OK = {"b", "i", "u", "s", "code", "pre", "blockquote", "a", "tg-spoiler"}

    def __init__(self):
        super().__init__()
        self.stack, self.bad = [], []

    def handle_starttag(self, tag, attrs):
        if tag not in self.OK:
            self.bad.append("tag:" + tag)
        self.stack.append(tag)

    def handle_endtag(self, tag):
        if not self.stack or self.stack[-1] != tag:
            self.bad.append("close:" + tag)
        else:
            self.stack.pop()


def html_ok(text):
    p = _Tags()
    p.feed(text)
    p.close()
    return not p.bad and not p.stack and len(M.strip_tags(text)) <= 4096


class FakeBot:
    def __init__(self):
        self.log, self.n = [], 1000
        self.bad_html = []

    def _rec(self, kind, **kw):
        self.log.append((kind, kw))
        txt = kw.get("text")
        if txt and kw.get("parse_mode") and not html_ok(txt):
            self.bad_html.append((kind, txt[:120]))

    async def send_message(self, chat_id, text, parse_mode=None, reply_markup=None, **kw):
        self.n += 1
        self._rec("send", chat_id=chat_id, text=text, parse_mode=parse_mode, markup=reply_markup, mid=self.n)
        return SimpleNamespace(message_id=self.n, chat_id=chat_id, photo=None)

    async def edit_message_text(self, text, chat_id=None, message_id=None, parse_mode=None, reply_markup=None, **kw):
        self._rec("edit", chat_id=chat_id, mid=message_id, text=text, parse_mode=parse_mode, markup=reply_markup)
        return True

    async def delete_message(self, chat_id, message_id):
        self._rec("delete", chat_id=chat_id, mid=message_id)
        return True

    async def send_photo(self, chat_id, photo, caption=None, parse_mode=None, reply_markup=None, **kw):
        self.n += 1
        self._rec("photo", chat_id=chat_id, size=len(photo), caption=caption)
        self.last_photo = photo
        return SimpleNamespace(message_id=self.n, chat_id=chat_id, photo=[1])

    async def send_chat_action(self, chat_id, action):
        return True

    async def set_message_reaction(self, chat_id, message_id, reaction=None, **kw):
        self._rec("react", mid=message_id, emoji=[getattr(r, "emoji", "") for r in (reaction or [])])
        return True

    def of(self, kind):
        return [kw for k, kw in self.log if k == kind]


def make_ctx(bot=None):
    return SimpleNamespace(bot=bot or FakeBot(), user_data={}, application=SimpleNamespace(bot_data={}))


def make_user(uid):
    return SimpleNamespace(id=uid, full_name=f"User {uid}", first_name="User", username=f"u{uid}")


_mid = [5000]


def make_update(user, text):
    _mid[0] += 1
    return SimpleNamespace(effective_user=user, message=SimpleNamespace(chat_id=user.id, message_id=_mid[0], text=text, entities=[]))


class FakeQ:
    def __init__(self, user, bot, data=""):
        self.from_user, self.data = user, data
        self.message = SimpleNamespace(chat_id=user.id, message_id=777, photo=None)
        self.markups = []

    async def answer(self, *a, **k):
        return True

    async def edit_message_reply_markup(self, reply_markup=None):
        self.markups.append(reply_markup)


# ---------------------------------------------------------------- نموذج وهمي ------------------------------------------------
class FakeLLM:
    """يحاكي OmniRoute: يميّز الموجّه والمدقق والكاتب من تعليمات النظام."""

    def __init__(self):
        self.calls, self.router, self.critic, self.writer = [], None, [90], None
        self.delay, self.fail_writer = 0.25, False

    async def __call__(self, messages, vision=False, prefer=None):
        sysmsg = messages[0]["content"] if messages and messages[0]["role"] == "system" else ""
        kind = "router" if sysmsg.startswith(M.ROUTER_SYSTEM[:30]) else "critic" if sysmsg.startswith(M.CRITIC_SYSTEM[:30]) else "writer"
        self.calls.append((kind, messages))
        await asyncio.sleep(self.delay)
        if kind == "router":
            if self.router == "FAIL":
                raise M.OmniRouteError("router down")
            return json.dumps(self.router) if self.router is not None else "{}"
        if kind == "critic":
            sc = self.critic.pop(0) if len(self.critic) > 1 else self.critic[0]
            if sc is None:
                return "ليس json"
            return json.dumps({"score": sc, "gaps": ["ناقص معيار قبول"], "fixes": "أضف معايير قبول"})
        if self.fail_writer:
            raise M.OmniRouteError("writer down")
        if callable(self.writer):
            return self.writer(messages)
        return self.writer or ("# الدور\nتصرّف بصفتك مهندساً.\n\n# الهدف\nابنِ بوتاً كاملاً.\n\n# المتطلبات\n1. أولاً\n2. ثانياً")

    def n(self, kind):
        return sum(1 for k, _ in self.calls if k == kind)


LLM = FakeLLM()
M.omni_chat = LLM
FAILS = []


async def _noop(*a, **k):
    return None


async def _record_fail(ctx, user, stage, err, what="", extra=""):
    FAILS.append((stage, str(err)))

M.ensure_welcomed = _noop
M.fail_request = _record_fail
M.studio_register(None)
D = M.D()

BRIEF = {"domain": "bot", "goal": "بوت تيليجرام لإدارة طلبات المطاعم", "target": "Telegram Bot API", "language": "ar",
         "complexity": 4, "ambiguity": 0.2, "followup": False, "missing": [], "constraints": ["بايثون", "SQLite"],
         "questions": [], "title": "بوت إدارة المطاعم"}


def fresh(uid, **prefs):
    D.upsert_user(make_user(uid))
    if prefs:
        M.set_sprefs(uid, **prefs)
    LLM.__init__()
    LLM.router = dict(BRIEF)
    FAILS.clear()
    return make_user(uid), make_ctx()


async def run_serve(user, ctx, text, wait=True):
    upd = make_update(user, text)
    coro = M.serve(upd, ctx, text, [], [])
    return asyncio.ensure_future(coro) if not wait else await coro


# ---------------------------------------------------------------- الاختبارات -------------------------------------------------
def t_units():
    print("وحدات صغيرة")
    check("plural خطوات", (M.ar_steps(1), M.ar_steps(2), M.ar_steps(5), M.ar_steps(12)) == ("خطوة وحدة", "خطوتين", "5 خطوات", "12 خطوة"))
    check("ثواني", (M.ar_secs(1), M.ar_secs(7), M.ar_secs(27), M.ar_secs(75)) == ("ثانية وحدة", "7 ثواني", "27 ثانية", "1 دقيقة و15 ثانية"))
    check("parse_json_loose: ``` وفاصلة زائدة", M.parse_json_loose('```json\n{"a": 1, "b": [1,2,],}\n```') == {"a": 1, "b": [1, 2]})
    check("parse_json_loose: نص حول JSON", M.parse_json_loose('تفضل: {"score": 80} شكراً') == {"score": 80})
    check("parse_json_loose: فاشل", M.parse_json_loose("بلا json") is None)
    hb = M.heuristic_brief("اريد برومبت لبوت تيليجرام يدير طلبات المطعم")
    check("heuristic: bot", hb["domain"] == "bot", hb["domain"])
    check("heuristic: صورة", M.heuristic_brief("اريد برومبت لصورة غروب بميدجورني")["domain"] == "image")
    check("heuristic: فيديو", M.heuristic_brief("سيناريو فيديو ريلز")["domain"] == "video")
    check("heuristic: قصير = غامض", M.heuristic_brief("بوت")["ambiguity"] >= 0.8)
    check("smalltalk: تحية بلا موجّه، وطلب قصير يمر", M.is_smalltalk("هلا!") and M.is_smalltalk("شكراً") and not M.is_smalltalk("بوت") and not M.is_smalltalk("اريد برومبت"))
    check("heuristic: مفصّل = واضح", M.heuristic_brief("اكتب كود بايثون يقرأ ملف CSV فيه 5 أعمدة ويحسب المتوسط ويرسم رسماً بيانياً بـ matplotlib")["ambiguity"] <= 0.3)
    check("looks_like_edit", M.looks_like_edit("خليه أقصر") and M.looks_like_edit("اجعل النبرة رسمية") and not M.looks_like_edit("اريد برومبت لبوت"))
    cb = M.clean_brief({"domain": "xxx", "ambiguity": "9", "questions": [{"q": "سؤال؟", "options": ["أ", "ب", "أ", "ج", "د", "هـ"]}, {"q": "", "options": ["x", "y"]},
                                                              {"q": "واحد", "options": ["فقط"]}], "constraints": ["بايثون"]}, M.heuristic_brief("اختبار بسيط جداً هنا"))
    check("clean_brief: يعقّم", cb["domain"] in M.DOMAIN_LABEL and cb["ambiguity"] == 1.0 and len(cb["questions"]) == 1 and cb["questions"][0]["options"] == ["أ", "ب", "ج", "د"], str(cb))
    fr = M.ascii_frames("اشتغلت 27 ثانية", 3, 7)
    check("ascii_frames: 3 مراحل بنفس الطول", len(fr) == 3 and all(len(f) == len("اشتغلت 27 ثانية") for f in fr) and fr[0] != "اشتغلت 27 ثانية")
    block = M.build_studio_block(BRIEF, None, "")
    check("build_studio_block: وصفة + Telegram", "وصفة التخصص: بوتات" in block and "core.telegram.org/bots/api-changelog" in block and "بايثون | SQLite" in block)
    check("كل المجالات لها وصفة", all(d in M.RECIPES for d in M.DOMAIN_LABEL))
    check("طول الوصفات معقول", all(len(M.build_studio_block({"domain": d}, None)) < 3000 for d in M.DOMAIN_LABEL))


async def t_run_model():
    print("نموذج المسار")
    run = M.AgentRun(1, 1)
    run.begin("a", "أ")
    run.begin("b", "ب", "تفصيل")
    check("begin يغلق السابق", run.get("a").status == "done" and run.get("b").status == "running")
    run.skip("c", "ج", "ليش")
    run.fail("b", "x")
    check("fail/skip", run.get("b").status == "failed" and run.get("c").status == "skipped")
    for i in range(12):
        run.begin(f"s{i}", f"خطوة طويلة جداً رقم {i}")
    txt = M.render_live(run, tick=1)
    check("render_live: يطوي الزائد", "+" in txt and txt.count("\n") <= 12 and html_ok(txt))
    run.t1 = M.time.time()
    check("render_fold: expandable", M.render_fold(run).startswith("<blockquote expandable>") and html_ok(M.render_fold(run)))

    async def slow():
        await asyncio.sleep(5)
    r2 = M.AgentRun(1, 1)
    t = asyncio.ensure_future(r2.guard(slow()))
    await asyncio.sleep(0.05)
    r2.stop()
    try:
        await t
        ok = False
    except M.RunStopped:
        ok = True
    check("guard: stop يلغي ويرمي RunStopped", ok)
    r3 = M.AgentRun(1, 1)
    r3.stop()
    try:
        await r3.guard(slow())
        ok = False
    except M.RunStopped:
        ok = True
    check("guard: بعد الإيقاف يرفض فوراً", ok)


async def t_happy():
    print("المسار الكامل (موجّه ← كتابة ← مراجعة ← بطاقة ← أجزاء)")
    user, ctx = fresh(2001, ascii=1)
    LLM.critic = [91]
    long_prompt = "# قسم\n" + "\n".join(f"- بند رقم {i} فيه تفاصيل كثيرة لتحقيق الطول المطلوب هنا" for i in range(160))
    LLM.writer = long_prompt
    await run_serve(user, ctx, "اريد برومبت لبوت تيليجرام يدير طلبات المطاعم بالبايثون وSQLite")
    bot = ctx.bot
    check("موجّه 1 + كاتب 1 + مدقق 1", (LLM.n("router"), LLM.n("writer"), LLM.n("critic")) == (1, 1, 1), str([k for k, _ in LLM.calls]))
    writer_sys = next(m for k, m in LLM.calls if k == "writer")[0]["content"]
    check("وصفة المجال دخلت تعليمات الكاتب", "وصفة التخصص: بوتات" in writer_sys and "بايثون | SQLite" in writer_sys)
    sends, edits = bot.of("send"), bot.of("edit")
    check("رسالة حيّة أُرسلت وتحدّثت", any("دا أشتغل" in (s["text"] or "") or "دا أكتب" in (s["text"] or "") for s in sends) and len(edits) >= 2)
    live_mid = next(s["mid"] for s in sends if "دا أ" in (s["text"] or ""))
    check("زر إيقاف بالرسالة الحيّة", any(s["markup"] is not None and "u:st:" in M._markup_sig(s["markup"]) for s in sends if s["mid"] == live_mid))
    fold = [e for e in edits if e["mid"] == live_mid][-1]
    check("آخر تعديل = ملخص قابل للطي بالجودة", fold["text"].startswith("<blockquote expandable>") and "الجودة 91/100" in fold["text"] and fold["markup"] is None, fold["text"][:150])
    check("فيه مراحل ASCII قبل الملخص", sum(1 for e in edits if e["mid"] == live_mid and ("░" in e["text"] or "▒" in e["text"] or "▓" in e["text"] or "▌" in e["text"] or "▐" in e["text"] or "▄" in e["text"] or "▀" in e["text"])) >= 2)
    photos = bot.of("photo")
    check("بطاقة النتيجة صورة", len(photos) == 1 and photos[0]["size"] > 5000)
    if OUT_DIR and photos:
        os.makedirs(OUT_DIR, exist_ok=True)
        with open(os.path.join(OUT_DIR, "result_card.jpg"), "wb") as fh:
            fh.write(bot.last_photo)
    parts = [s for s in sends if "<pre>" in (s["text"] or "")]
    check("البرومبت انقسم لأجزاء متوازنة", 3 <= len(parts) <= 4, str(len(parts)))
    check("الأزرار على آخر جزء فقط", [p["markup"] is not None for p in parts] == [False] * (len(parts) - 1) + [True])
    sig = M._markup_sig(parts[-1]["markup"])
    check("أزرار الإجراءات الستة + تقييم", all(f".{op}" in sig for op in "sdvetkgb"), sig)
    row = D.one("SELECT * FROM studio_results WHERE uid=?", (2001,))
    check("النتيجة انحفظت بالمجال والدرجة", row and row["domain"] == "bot" and row["score"] == 91 and row["parts"] == len(parts) and row["router"] == "llm", str(dict(row)) if row else "")
    await asyncio.sleep(0.15)                                  # التفاعلات تُرسل بمهام خلفية
    emojis = [e["emoji"] for e in bot.of("react")]
    check("تفاعلات: ✍ ثم 👌", ["✍"] in emojis and emojis[-1] == ["👌"], str(emojis))
    check("كل HTML صالح", not bot.bad_html, str(bot.bad_html))
    check("ما فيه رسالة خطأ", not FAILS, str(FAILS))
    check("run انشال من السجل", not any(r.uid == 2001 for r in M._RUNS.values()))
    return row["id"]


async def t_refine():
    print("المراجعة: تحسين عند الضعف")
    user, ctx = fresh(2002, ascii=0, card=0)
    LLM.critic = [60, 88]
    drafts = []

    def writer(messages):
        drafts.append(messages[-1]["content"])
        return "# النسخة المحسّنة\n" + "تفصيل " * 60 if "[ملاحظة للنظام: راجع مدقق الجودة" in messages[-1]["content"] else "# المسودة الأولى\n" + "تفصيل " * 60
    LLM.writer = writer
    await run_serve(user, ctx, "اريد برومبت لبوت تيليجرام يدير طلبات المطاعم")
    check("كاتب مرتين + مدقق مرتين", (LLM.n("writer"), LLM.n("critic")) == (2, 2))
    row = D.one("SELECT * FROM studio_results WHERE uid=?", (2002,))
    check("المحسّنة هي المسلَّمة والدرجة 88", row["refined"] == 1 and row["score"] == 88 and "النسخة المحسّنة" in row["prompt"], str(dict(row)))
    check("ملاحظة المدقق دخلت طلب التحسين", "ناقص معيار قبول" in drafts[1] and "مسودتك السابقة" in drafts[1])

    user, ctx = fresh(2003, ascii=0, card=0)
    LLM.critic = [70, 55]
    LLM.writer = lambda m: ("# محسّنة\n" if "راجع مدقق" in m[-1]["content"] else "# أصلية\n") + "تفصيل " * 60
    await run_serve(user, ctx, "اريد برومبت لبوت تيليجرام يدير طلبات المطاعم")
    row = D.one("SELECT * FROM studio_results WHERE uid=?", (2003,))
    check("إن ساءت بعد التحسين نبقي الأولى (بصدق)", "أصلية" in row["prompt"] and row["refined"] == 0 and row["score"] == 70, str(dict(row)))

    user, ctx = fresh(2004, ascii=0, card=0)
    LLM.critic = [None]
    await run_serve(user, ctx, "اريد برومبت لبوت تيليجرام يدير طلبات المطاعم")
    row = D.one("SELECT * FROM studio_results WHERE uid=?", (2004,))
    check("فشل المدقق: يسلّم بلا درجة ولا خطأ", row and row["score"] == -1 and not FAILS)


async def t_modes_and_off():
    print("الأوضاع والإيقاف الكلي")
    user, ctx = fresh(2005, mode="fast", ascii=0, card=0)
    await run_serve(user, ctx, "اريد برومبت لبوت تيليجرام يدير طلبات المطاعم")
    check("الوضع السريع: كاتب فقط", (LLM.n("router"), LLM.n("writer"), LLM.n("critic")) == (0, 1, 0))
    row = D.one("SELECT * FROM studio_results WHERE uid=?", (2005,))
    check("الوضع السريع: احتياطي الكلمات يحدد المجال", row["domain"] == "bot" and row["router"] == "heur")

    user, ctx = fresh(2006, ascii=0, card=0)
    LLM.router = "FAIL"
    await run_serve(user, ctx, "اريد برومبت لبوت تيليجرام يدير طلبات المطاعم")
    row = D.one("SELECT * FROM studio_results WHERE uid=?", (2006,))
    check("فشل الموجّه: يكمل بالاحتياطي بدون خطأ", row and row["router"] == "heur" and row["domain"] == "bot" and not FAILS)

    D.set("st_on", "0")
    user, ctx = fresh(2007)
    LLM.critic = [99]
    await run_serve(user, ctx, "اريد برومبت لبوت")
    sends = ctx.bot.of("send")
    check("STUDIO متوقف: سلوك البوت القديم (بلا مسار ولا أزرار)", LLM.n("router") == 0 and LLM.n("critic") == 0 and not ctx.bot.of("photo")
          and sends and all(s["markup"] is None for s in sends) and D.one("SELECT 1 FROM studio_results WHERE uid=2007") is None)
    D.set("st_on", "1")


async def t_failure():
    print("عطل الكاتب")
    user, ctx = fresh(2008, ascii=0)
    LLM.fail_writer = True
    await run_serve(user, ctx, "اريد برومبت لبوت تيليجرام يدير طلبات المطاعم")
    check("أُبلغ عن العطل بالمسار الأصلي", FAILS and FAILS[0][0] == "omni", str(FAILS))
    sends = ctx.bot.of("send")
    live = [s["mid"] for s in sends if "دا أ" in (s["text"] or "")]
    check("الرسالة الحيّة انحذفت", live and any(d["mid"] == live[0] for d in ctx.bot.of("delete")))
    check("ما انرسل برومبت", not any("<pre>" in (s["text"] or "") for s in sends))


async def t_clarify():
    print("سؤال التوضيح (طلب إذن)")
    user, ctx = fresh(2009, ascii=0, card=0)
    LLM.router = {**BRIEF, "domain": "bot", "ambiguity": 0.95, "questions": [
        {"q": "على أي منصة؟", "options": ["تيليجرام", "ديسكورد", "واتساب"]}, {"q": "وين تشغّله؟", "options": ["VPS", "جوال", "Render"]}]}
    task = await run_serve(user, ctx, "بوت", wait=False)
    for _ in range(100):
        run = ctx.user_data.get("studio_run")
        if run is not None and run.asking:
            break
        await asyncio.sleep(0.05)
    check("دخل حالة الانتظار", run is not None and run.asking and run.status == "waiting")
    await asyncio.sleep(0.2)
    ask_msgs = [e["text"] for e in ctx.bot.of("send") + ctx.bot.of("edit") if "سؤال سريع" in (e["text"] or "")]
    check("بطاقة السؤال ظهرت بأزرار الخيارات", ask_msgs and any("1/2" in t for t in ask_msgs))
    last_markup = [e["markup"] for e in ctx.bot.of("send") + ctx.bot.of("edit") if e["markup"] is not None][-1]
    sig = M._markup_sig(last_markup)
    check("أزرار: خيارات + ابنِ مباشرة + إيقاف", f"u:ca:{run.id}.0.1" in sig and f"u:cs:{run.id}" in sig and f"u:st:{run.id}" in sig, sig)
    await M.u_ask_answer(ctx, FakeQ(user, ctx.bot), f"{run.id}.0.1")
    check("الجواب الأول يقدّم للسؤال الثاني", run.ask_idx == 1 and run.asking)
    await M.u_ask_answer(ctx, FakeQ(user, ctx.bot), f"{run.id}.0.2")                   # جواب متأخر لسؤال قديم: يتجاهل
    check("جواب قديم يتجاهل", run.ask_idx == 1)
    consumed = await M.studio_on_text(make_update(user, "ع Render"), ctx, "ع Render مع Docker")
    check("نص المستخدم يُحسب جواباً", consumed is True and run.free_text.startswith("ع Render"))
    await task
    writer_msgs = next(m for k, m in LLM.calls if k == "writer")
    user_msg = writer_msgs[-1]["content"]
    check("الأجوبة دخلت طلب الكاتب", "ديسكورد" in user_msg and "ع Render مع Docker" in user_msg and "تأكيدات المستخدم" in user_msg, user_msg[:300])
    check("غير مستهلَك بعد الانتهاء", await M.studio_on_text(make_update(user, "x"), ctx, "x") is False)

    user, ctx = fresh(2010, ascii=0, card=0)
    LLM.router = {**BRIEF, "ambiguity": 0.95, "questions": [{"q": "وين؟", "options": ["أ", "ب"]}]}
    task = await run_serve(user, ctx, "بوت", wait=False)
    for _ in range(100):
        run = ctx.user_data.get("studio_run")
        if run is not None and run.asking:
            break
        await asyncio.sleep(0.05)
    await M.u_ask_skip(ctx, FakeQ(user, ctx.bot), str(run.id))
    await task
    check("تخطّي: يكمل بافتراضات بدون تأكيدات", "تأكيدات المستخدم" not in next(m for k, m in LLM.calls if k == "writer")[-1]["content"])

    user, ctx = fresh(2011, ascii=0, card=0)
    LLM.router = {**BRIEF, "ambiguity": 0.95, "questions": [{"q": "وين؟", "options": ["أ", "ب"]}]}
    M.STUDIO_CLARIFY_WAIT = 0.4
    await run_serve(user, ctx, "بوت")
    M.STUDIO_CLARIFY_WAIT = 5.0
    check("مهلة السؤال: يكمل لحاله", D.one("SELECT 1 FROM studio_results WHERE uid=2011") is not None)

    user, ctx = fresh(2012, ascii=0, card=0)
    LLM.router = {**BRIEF, "ambiguity": 0.95, "questions": [{"q": "وين؟", "options": ["أ", "ب"]}]}
    await run_serve(user, ctx, "بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت بوت")
    check("الطلب الطويل ما يُسأل عنه", not any("سؤال سريع" in (e["text"] or "") for e in ctx.bot.of("send") + ctx.bot.of("edit")))


async def t_stop():
    print("زر الإيقاف")
    user, ctx = fresh(2013, ascii=0, card=0)
    LLM.delay = 3.0
    task = await run_serve(user, ctx, "اريد برومبت لبوت تيليجرام يدير طلبات المطاعم", wait=False)
    for _ in range(100):
        run = ctx.user_data.get("studio_run")
        if run is not None and ctx.bot.of("send"):
            break
        await asyncio.sleep(0.05)
    await asyncio.sleep(0.3)
    t0 = M.time.time()
    await M.u_stop(ctx, FakeQ(user, ctx.bot), str(run.id))
    await asyncio.wait_for(task, 3)
    check("الإيقاف فوري (أقل من ثانية)", M.time.time() - t0 < 1.0)
    sends = ctx.bot.of("send") + ctx.bot.of("edit")
    check("ملخص «أوقفتها بعد»", any("أوقفتها بعد" in (e["text"] or "") for e in sends))
    check("ما انرسل برومبت ولا خطأ", not any("<pre>" in (e["text"] or "") for e in sends) and not FAILS and D.one("SELECT 1 FROM studio_results WHERE uid=2013") is None)
    check("ما عاد بالسجل", run.id not in M._RUNS)
    other = make_user(2999)
    run2 = M.AgentRun(2013, 2013)
    M._RUNS[run2.id] = run2
    await M.u_stop(make_ctx(), FakeQ(other, None), str(run2.id))
    check("ما يكدر أحد يوقف طلب غيره", run2.stopped is False)
    M._RUNS.pop(run2.id, None)
    LLM.delay = 0.25


async def t_queue():
    print("الطابور والتفاعلات")
    user, ctx = fresh(2014, ascii=0, card=0)
    LLM.delay = 0.6
    t1 = await run_serve(user, ctx, "اريد برومبت لبوت تيليجرام يدير طلبات المطاعم", wait=False)
    await asyncio.sleep(0.25)
    upd2 = make_update(user, "اريد برومبت لصورة غروب بحري")
    t2 = asyncio.ensure_future(M.serve(upd2, ctx, "اريد برومبت لصورة غروب بحري", [], []))
    await asyncio.sleep(0.5)
    run = ctx.user_data.get("studio_run")
    check("طابور = 1 ظاهر بالمسار", run is not None and run.queue == 1 and any("بالانتظار" in (e["text"] or "") for e in ctx.bot.of("edit") + ctx.bot.of("send")))
    await asyncio.gather(t1, t2)
    await asyncio.sleep(0.15)
    reacts = [(e["mid"], e["emoji"]) for e in ctx.bot.of("react")]
    check("الطلب الثاني أخذ 👀 ثم ✍ ثم 👌", [e for m, e in reacts if m == upd2.message.message_id] == [["👀"], ["✍"], ["👌"]], str(reacts))
    check("الطلبان انحفظا بترتيبهما", [r["domain"] for r in D.q("SELECT domain FROM studio_results WHERE uid=2014 ORDER BY id")] == ["bot", "bot"] or len(D.q("SELECT 1 FROM studio_results WHERE uid=2014")) == 2)
    check("الطابور رجع صفر", ctx.user_data.get("studio_queue") == 0)
    LLM.delay = 0.25


async def t_actions(rid_from_happy):
    print("أزرار تحت البرومبت: تحويل، حفظ، مكتبة، تقييم، تجربة")
    user = make_user(2001)
    ctx = make_ctx()
    LLM.__init__()
    LLM.router = dict(BRIEF)
    LLM.critic = [95]
    seen = {}

    def writer(messages):
        seen["sys"], seen["hist"], seen["user"] = messages[0]["content"], messages[1:-1], messages[-1]["content"]
        return "# نسخة أقصر\nتفصيل " * 8
    LLM.writer = writer
    M.set_sprefs(2001, ascii=0, card=0)
    q = FakeQ(user, ctx.bot)
    await M.u_result(ctx, q, f"{rid_from_happy}.s")
    check("«أقصر»: التاريخ فيه الطلب والبرومبت السابق", len(seen["hist"]) == 2 and "بوت تيليجرام" in seen["hist"][0]["content"] and "بند رقم" in seen["hist"][1]["content"])
    check("«أقصر»: تجاوز التفصيل لموجز + نفس المجال (بوتات)", "نحو 1500 حرف" in seen["sys"] and "وصفة التخصص: بوتات" in seen["sys"])
    check("«أقصر»: ما استُدعي الموجّه (تعديل)", LLM.n("router") == 0)
    await M.u_result(ctx, q, f"{rid_from_happy}.e")
    check("«English»: لغة البرومبت إنجليزي", "كله بالإنجليزية" in seen["sys"])
    check("التجاوز لمرة وحدة فقط", "studio_pref_ov" not in ctx.user_data)
    await M.u_result(ctx, q, f"{rid_from_happy}.v")
    check("«نسخة مختلفة»", "مختلفة جذرياً" in seen["user"])
    n_before = len(D.q("SELECT 1 FROM studio_results WHERE uid=2001"))
    check("كل تحويل أنتج نتيجة جديدة", n_before >= 4)

    await M.u_result(ctx, q, f"{rid_from_happy}.k")
    check("حفظ بالمكتبة + الزر صار «إزالة»", D.scalar("SELECT saved FROM studio_results WHERE id=?", (rid_from_happy,)) == 1 and "إزالة" in q.markups[-1].inline_keyboard[1][2].text)
    text, markup = M.lib_screen(2001)
    check("شاشة المكتبة", "مكتبتي (1)" in text and f"u:lv:{rid_from_happy}" in M._markup_sig(markup) and html_ok(text))
    await M.u_lib_view(ctx, q, str(rid_from_happy))
    view_txt = ctx.bot.of("edit")[-1]["text"]
    check("عرض عنصر المكتبة", "المجال: بوتات" in view_txt and "<blockquote expandable>" in view_txt and html_ok(view_txt), view_txt[:200])
    before = len(ctx.bot.of("send"))
    await M.u_lib_open(ctx, q, str(rid_from_happy))
    check("إرسال المحفوظ مع الأزرار", len(ctx.bot.of("send")) > before and ctx.bot.of("send")[-1]["markup"] is not None)
    await M.u_result(ctx, q, f"{rid_from_happy}.g")
    check("تقييم ممتاز يخفي صف التقييم", D.scalar("SELECT feedback FROM studio_results WHERE id=?", (rid_from_happy,)) == 1 and len(q.markups[-1].inline_keyboard) == 2)
    await M.u_lib_del(ctx, q, str(rid_from_happy))
    check("حذف من المكتبة", D.scalar("SELECT saved FROM studio_results WHERE id=?", (rid_from_happy,)) == 0)

    LLM.writer = "ناتج التجربة السريعة بالتفصيل"
    await M.u_result(ctx, q, f"{rid_from_happy}.t")
    sends = ctx.bot.of("send")
    check("تجربة سريعة: ناتج داخل اقتباس قابل للطي", any("نتيجة التجربة السريعة" in (s["text"] or "") and "expandable" in s["text"] for s in sends) and html_ok(sends[-1]["text"]))
    await M.u_result(ctx, q, "999999.s")
    check("نتيجة غير موجودة: رسالة هادئة", "ما عاد بالذاكرة" in ctx.bot.of("send")[-1]["text"])
    other_q = FakeQ(make_user(3000), ctx.bot)
    await M.u_result(make_ctx(ctx.bot), other_q, f"{rid_from_happy}.k")
    check("ما يكدر مستخدم يفتح نتيجة غيره", D.scalar("SELECT saved FROM studio_results WHERE id=?", (rid_from_happy,)) == 0)


async def t_screens():
    print("الشاشات: إعدادات، قائمة، أدمن، مساعدة، مسح بيانات")
    user = make_user(2020)
    D.upsert_user(user)
    ctx = make_ctx()
    text, markup = M.settings_screen(2020)
    sig = M._markup_sig(markup)
    check("الإعدادات: قسم الاستوديو + 6 أزرار", "الاستوديو الذكي" in text and all(f"u:ss:{k}" in sig for k in ("mode", "path", "critic", "clarify", "ascii", "card")) and html_ok(text), text[-200:])
    q = FakeQ(user, ctx.bot)
    await M.u_studio_set(ctx, q, "mode")
    check("زر الوضع يدوّر (ذكي ← عميق)", M.sprefs(2020)["mode"] == "deep")
    await M.u_studio_set(ctx, q, "mode")
    await M.u_studio_set(ctx, q, "mode")
    check("... ثم سريع ثم ذكي", M.sprefs(2020)["mode"] == "smart")
    await M.u_studio_set(ctx, q, "critic")
    check("زر المراجعة يطفي", M.sprefs(2020)["critic"] == 0)
    menu = M.main_menu(False, 2020)
    check("القائمة الرئيسية: زر مكتبتي", "u:lib" in M._markup_sig(menu))
    check("المساعدة ≤ 4096 وصالحة", len(M.strip_tags(M.T_HELP + M.STUDIO_HELP)) <= 4096 and html_ok(M.T_HELP + M.STUDIO_HELP))
    panel = M.admin_panel_text()
    check("لوحة الأدمن ≤ 4096 وفيها الاستوديو", len(M.strip_tags(panel)) <= 4096 and "الاستوديو الذكي" in panel, str(len(M.strip_tags(panel))))
    check("لوحة الأدمن: زر الاستوديو", "a:studio" in M._markup_sig(M.admin_panel_kb()))
    await M.a_studio(ctx, q, [], "studio")
    adm = ctx.bot.of("edit")[-1]
    check("شاشة الأدمن بالإحصائيات", "الاستوديو الذكي" in adm["text"] and "متوسط الجودة" in adm["text"] and html_ok(adm["text"]), adm["text"][:200])
    await M.a_studio(ctx, q, ["m+"], "studio")
    check("رفع حد الجودة", M.SST("st_min") == "83")
    await M.a_studio(ctx, q, ["r"], "studio")
    check("إيقاف الموجّه عالمياً", M.SST("st_router") == "0")
    D.set("st_router", "1")
    D.set("st_min", "78")
    check("الأمر /library مسجّل", ("library", "مكتبتي") in M.COMMANDS and "lib" in M.USER_ACTIONS)
    app = M.build_app()
    cmds = {c for h in app.handlers[0] if h.__class__.__name__ == "CommandHandler" for c in h.commands}
    check("build_app (PTB حقيقي): فيه /library وبقية الأوامر", {"library", "start", "live", "news"} <= cmds, str(cmds))
    check("نص الإعدادات بصيغة صحيحة (شغّالة/شغّال)", "المراجعة الذاتية: شغّالة" in M.studio_settings_text(2020) or "المراجعة الذاتية: مطفية" in M.studio_settings_text(2020))
    M.studio_forget(2001)
    check("مسح بياناتي يمسح الاستوديو", D.one("SELECT 1 FROM studio_results WHERE uid=2001") is None and D.one("SELECT 1 FROM studio_prefs WHERE uid=2001") is None)


async def t_profile_and_prune():
    print("بصمة المستخدم والتقليم")
    uid = 2030
    for i in range(70):
        M.studio_save_result(uid, domain="code" if i % 2 else "image", request=f"r{i}", prompt="p", title=f"t{i}")
    check("التقليم: يبقي آخر 60", D.scalar("SELECT COUNT(*) FROM studio_results WHERE uid=?", (uid,)) == M.STUDIO_KEEP_RESULTS)
    rid = D.scalar("SELECT MAX(id) FROM studio_results WHERE uid=?", (uid,))
    D.x("UPDATE studio_results SET feedback=-1 WHERE uid=? AND domain='code' AND feedback=0", (uid,))
    line = M.studio_profile_line(uid)
    check("البصمة: مجالات + تنبيه ضعف", "برمجة" in line and "ضعيفة" in line, line)
    check("بصمة مستخدم جديد فارغة", M.studio_profile_line(99999) == "")
    check("حفظ طويل جداً ما ينفجر", M.studio_save_result(uid, prompt="x" * 100000, request="y" * 100000, title="z" * 500) > 0)


def t_card():
    print("بطاقة النتيجة (صورة)")
    if not M.card_supported():
        print("  (Pillow أو الخط غير متوفرين: تخطّي)")
        return
    from io import BytesIO
    from PIL import Image
    cases = [("بوت إدارة المطاعم", "بوتات", "Telegram Bot API", 91, False, 27.4, 6, 3, 8200),
             ("عنوان طويل جداً لبرومبت يصف بناء منصة متكاملة للتجارة الإلكترونية", "برمجة", "", None, True, 75, 9, 4, 11000),
             ("Logo", "صور", "Midjourney", 64, False, 4, 3, 1, 900)]
    for i, args in enumerate(cases):
        data = M.make_result_card(*args, seed=i)
        im = Image.open(BytesIO(data))
        check(f"البطاقة {i + 1}: صورة 1080×640", im.size == (1080, 640) and len(data) > 8000)
        if OUT_DIR:
            os.makedirs(OUT_DIR, exist_ok=True)
            with open(os.path.join(OUT_DIR, f"card_case{i + 1}.jpg"), "wb") as fh:
                fh.write(data)


async def t_path_off_and_env():
    print("المسار مطفّي + قارئ .env + أسرار")
    user, ctx = fresh(2040, path=0, ascii=0, card=0)
    await run_serve(user, ctx, "اريد برومبت لبوت تيليجرام يدير طلبات المطاعم")
    sends = ctx.bot.of("send")
    check("المسار مطفّي: ما فيه رسالة حيّة", not any("دا أ" in (s["text"] or "") for s in sends) and any("<pre>" in (s["text"] or "") for s in sends))

    user, ctx = fresh(2041, path=0, ascii=0, card=0)
    LLM.router = {**BRIEF, "ambiguity": 0.95, "questions": [{"q": "وين؟", "options": ["أ", "ب"]}]}
    task = await run_serve(user, ctx, "بوت", wait=False)
    for _ in range(100):
        run = ctx.user_data.get("studio_run")
        if run is not None and run.asking:
            break
        await asyncio.sleep(0.05)
    check("المسار مطفّي لكن السؤال يظهر", any("سؤال سريع" in (e["text"] or "") for e in ctx.bot.of("send") + ctx.bot.of("edit")))
    await M.u_ask_skip(ctx, FakeQ(user, ctx.bot), str(run.id))
    await task
    live = [s["mid"] for s in ctx.bot.of("send") if "سؤال سريع" in (s["text"] or "")]
    check("بطاقة السؤال تنحذف بعد الجواب (بلا أثر)", live and any(d["mid"] == live[0] for d in ctx.bot.of("delete")))

    check("عنوان البطاقة: يقصّ ويشيل الإيموجي", M._card_title("بوت 🚀 رائع") == "بوت رائع" and len(M._card_title("ك" * 200)) == 70 and M._card_title("") == "برومبت")

    import subprocess
    d = tempfile.mkdtemp()
    with open(TARGET, encoding="utf-8") as fh, open(os.path.join(d, "p.py"), "w", encoding="utf-8") as out:
        out.write(fh.read())
    with open(os.path.join(d, ".env"), "w", encoding="utf-8") as fh:
        fh.write('# تعليق\nBOT_TOKEN="999:ABC"\nexport OMNIROUTE_API_KEY=\'sk-test\'\nADMIN_IDS=1,2\nEMPTY=\n')
    env = {k: v for k, v in os.environ.items() if k not in ("BOT_TOKEN", "OMNIROUTE_API_KEY", "ADMIN_IDS")}
    env["BOT_DB"] = os.path.join(d, "x.db")
    code = "import p, os; print(p.BOT_TOKEN, p.OMNIROUTE_API_KEY, sorted(p.ADMIN_IDS)[:2])"
    res = subprocess.run([sys.executable, "-c", code], cwd=d, env=env, capture_output=True, text=True, timeout=60)
    check(".env يُقرأ (مع علامات اقتباس و export)", res.stdout.strip().startswith("999:ABC sk-test"), res.stdout + res.stderr[-300:])
    env2 = dict(env)
    env2["BOT_TOKEN"] = "from-env"
    os.remove(os.path.join(d, ".env"))
    res = subprocess.run([sys.executable, "-c", "import p; print(repr(p.BOT_TOKEN), repr(p.OMNIROUTE_API_KEY))"], cwd=d, env=env2, capture_output=True, text=True, timeout=60)
    check("بلا .env: متغير البيئة يُحترم والأسرار لا تُملأ تلقائياً", res.stdout.strip() == "'from-env' ''", res.stdout + res.stderr[-300:])
    src = open(TARGET, encoding="utf-8").read()
    import re as _re
    check("لا أسرار مكتوبة بالملف", not _re.search(r"\d{8,10}:[A-Za-z0-9_-]{30,}|sk-[A-Za-z0-9-]{12,}", src))


async def main():
    t_units()
    await t_run_model()
    rid = await t_happy()
    await t_refine()
    await t_modes_and_off()
    await t_failure()
    await t_clarify()
    await t_stop()
    await t_queue()
    await t_actions(rid)
    await t_screens()
    await t_profile_and_prune()
    await t_path_off_and_env()
    t_card()


try:
    asyncio.run(main())
except Exception:
    traceback.print_exc()
    FAIL.append("EXCEPTION")
print(f"\n{len(PASS)} نجح، {len(FAIL)} فشل")
if FAIL:
    print("الفاشلة:", *FAIL, sep="\n  - ")
sys.exit(1 if FAIL else 0)
