#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
يركّب «الاستوديو الذكي» داخل ملف البوت (prompt.py) ويطلع ملفاً واحداً جاهزاً للرفع.

    python3 apply_studio.py  prompt.py  prompt_studio.py

- كل تعديل له «مرساة» نصية يجب أن تُوجد مرة واحدة بالضبط بالملف الأصلي، وإلا يتوقف ويقول أي مرساة فشلت
  (يعني الملف اختلف عن النسخة المتوقعة: عدّل المرساة هنا وكمّل).
- يشيل الأسرار المكتوبة داخل الكود (BOT_TOKEN و OMNIROUTE_API_KEY) ويقرأها من متغيرات البيئة أو من ملف .env بجنب الملف.
- آمن للتكرار: ما يركّب فوق ملف مركّب أصلاً.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PARTS = os.path.join(HERE, "parts")
MARK_BEGIN, MARK_END = "# >>> AGENT-STUDIO >>>", "# <<< AGENT-STUDIO <<<"

DOTENV = '''def _load_dotenv():
    """يقرأ ملف .env الذي بجنب هذا الملف (KEY=VALUE) دون ما يغلب متغيرات البيئة. الأسرار (BOT_TOKEN وغيره) مكانها هنا لا داخل الكود."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env")
    try:
        with open(path, encoding="utf-8") as fh:
            for raw in fh:
                line = raw.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, val = line.split("=", 1)
                key = key.strip()
                if key.startswith("export "):
                    key = key[7:].strip()
                val = val.strip()
                if len(val) >= 2 and val[0] == val[-1] and val[0] in "\\"'":
                    val = val[1:-1]
                if key and key not in os.environ:
                    os.environ[key] = val
    except OSError:
        pass


_load_dotenv()

'''

# (الاسم، المرساة النصية، البديل)
EDITS = [
    ("رأس الملف: وصف الاستوديو",
     "#    • OmniRoute لا يتوقف: عناوين احتياطية، سباق بين نموذجين عند البطء، تجاهل مؤقت للنموذج الفاشل، مراقب، وإعادة تشغيل ذاتية.\n",
     "#    • OmniRoute لا يتوقف: عناوين احتياطية، سباق بين نموذجين عند البطء، تجاهل مؤقت للنموذج الفاشل، مراقب، وإعادة تشغيل ذاتية.\n"
     "#    • الاستوديو الذكي: مسار عمل حيّ (تحليل ← كتابة ← مراجعة) بزر إيقاف، موجّه يختار وصفة التخصص، أسئلة توضيح بأزرار،\n"
     "#      مراجعة ذاتية بدرجة من 100، بطاقة نتيجة، أزرار (أقصر/أعمق/English/تجربة) تحت كل برومبت، ومكتبة محفوظات /library.\n"
     "#      الأسرار تُقرأ من متغيرات البيئة أو من ملف .env بجنب الملف (انظر .env.example). STUDIO_ON=0 يوقف الاستوديو.\n"),

    ("قارئ .env قبل قراءة الأسرار",
     "try:\n    import httpx\n    from telegram import (BotCommand, CopyTextButton,",
     DOTENV + "try:\n    import httpx\n    from telegram import (BotCommand, CopyTextButton,"),

    ("إدخال تعليمات الاستوديو للنموذج (build_system)",
     "def build_system(prefs=None):\n",
     "def build_system(prefs=None, extra_block=\"\"):\n"),
    ("إلحاق كتلة الاستوديو بتعليمات النظام",
     "        parts.append(prefs_block(prefs))\n    return \"\\n\\n\".join(parts)\n",
     "        parts.append(prefs_block(prefs))\n    if extra_block:\n        parts.append(extra_block)\n    return \"\\n\\n\".join(parts)\n"),
    ("generate_prompt: وسيط الكتلة",
     "async def generate_prompt(history, user_text, images=None, prefs=None, prefer=None):\n",
     "async def generate_prompt(history, user_text, images=None, prefs=None, prefer=None, extra_block=\"\"):\n"),
    ("generate_prompt: تمرير الكتلة",
     "messages = [{\"role\": \"system\", \"content\": build_system(prefs)}, *history, {\"role\": \"user\", \"content\": content}]",
     "messages = [{\"role\": \"system\", \"content\": build_system(prefs, extra_block)}, *history, {\"role\": \"user\", \"content\": content}]"),

    ("send_part: أزرار تحت آخر جزء",
     "async def send_part(bot, chat_id, body, i, n):\n",
     "async def send_part(bot, chat_id, body, i, n, markup=None):\n"),
    ("send_part: إرسال مع الأزرار",
     "    try:\n        await tg_call(bot.send_message, chat_id, html_msg, parse_mode=ParseMode.HTML)\n"
     "    except BadRequest as e:\n        logger.warning(\"HTML rejected (%s); sending plain text\", e)\n"
     "        await tg_call(bot.send_message, chat_id, ((title + \"\\n\\n\") if n > 1 else \"\") + text[:4000])\n",
     "    kw = {\"reply_markup\": markup} if markup is not None else {}\n"
     "    try:\n        await send_text(bot, chat_id, html_msg, parse_mode=ParseMode.HTML, **kw)\n"
     "    except BadRequest as e:\n        logger.warning(\"HTML rejected (%s); sending plain text\", e)\n"
     "        await send_text(bot, chat_id, ((title + \"\\n\\n\") if n > 1 else \"\") + text[:4000], **kw)\n"),
    ("send_prompt: تمرير الأزرار لآخر جزء",
     "async def send_prompt(bot, chat_id, prompt):\n    parts = split_parts(prompt)\n    for i, body in enumerate(parts, 1):\n"
     "        await send_part(bot, chat_id, body, i, len(parts))\n",
     "async def send_prompt(bot, chat_id, prompt, markup=None):\n    parts = split_parts(prompt)\n    for i, body in enumerate(parts, 1):\n"
     "        await send_part(bot, chat_id, body, i, len(parts), markup if i == len(parts) else None)\n"),

    ("serve: علامة الطابور",
     "    async with ud.setdefault(\"lock\", asyncio.Lock()):                # طلبات المستخدم الواحد تُعالَج بالتسلسل\n",
     "    queued = studio_queue_mark(update, ctx)                          # طلب بالانتظار؟ نعلّم رسالته\n"
     "    async with ud.setdefault(\"lock\", asyncio.Lock()):                # طلبات المستخدم الواحد تُعالَج بالتسلسل\n"),
    ("serve: بدء الاستوديو",
     "        status, stage, what, extra = Status(bot, user.id), \"other\", \"\", \"\"\n",
     "        status, stage, what, extra = Status(bot, user.id), \"other\", \"\", \"\"\n"
     "        studio = studio_begin(ctx, update, user, media_msgs, queued)     # الاستوديو الذكي (None = متوقف)\n"
     "        if studio:\n            status = studio.status\n"),
    ("serve: التحليل قبل الكتابة",
     "            prefs = d.prefs(user.id)\n            history = ud.get(\"history\", []) if prefs[\"memory\"] else []\n            n = max(len(atts), 1)\n",
     "            prefs = studio_override_prefs(d.prefs(user.id), ud)\n"
     "            force_hist = ud.pop(\"studio_force_hist\", False)\n"
     "            history = ud.get(\"history\", []) if (prefs[\"memory\"] or force_hist) else []\n"
     "            n = max(len(atts), 1)\n"
     "            ans_text, sx = \"\", \"\"\n"
     "            if studio:\n"
     "                ans_text = await studio.analyze(base, atts, history, prefs)      # تحليل الطلب (+ سؤال توضيح عند الحاجة)\n"
     "                sx = studio.system_block\n"),
    ("serve: أجوبة التوضيح داخل النص",
     "                return (base + \"\\n\\n\" + \"\\n\\n\".join(blocks)) if blocks else base\n",
     "                body = base + ans_text\n"
     "                return (body + \"\\n\\n\" + \"\\n\\n\".join(blocks)) if blocks else body\n"),
    ("serve: الكتابة عبر الاستوديو",
     "                return await generate_prompt(history, text_ + extra_note, imgs or None, prefs=prefs, prefer=prefer)\n",
     "                job = generate_prompt(history, text_ + extra_note, imgs or None, prefs=prefs, prefer=prefer, extra_block=sx)\n"
     "                return await (studio.write(job) if studio else job)\n"),
    ("serve: المراجعة الذاتية",
     "            names = \", \".join(a[\"name\"] for a in atts)\n            if prefs[\"memory\"]:\n",
     "            if studio:\n"
     "                reply = await studio.review(reply, lambda note: gen(user_text, note + (REPEAT_NOTE if prev else \"\"), images), base)\n"
     "            names = \", \".join(a[\"name\"] for a in atts)\n            if prefs[\"memory\"]:\n"),
    ("serve: التسليم والإيقاف والإغلاق",
     "            stage = \"send\"\n            await status.done()\n            await send_prompt(bot, user.id, reply)\n"
     "        except Exception as e:\n            logger.exception(\"request failed at stage %s\", stage)\n"
     "            await status.done()\n            await fail_request(ctx, user, stage, e, what, extra)\n"
     "        finally:\n            typing.cancel()\n            await status.done()\n",
     "            stage = \"send\"\n"
     "            if studio:\n                await studio.deliver(reply, base, atts)             # طيّ المسار + بطاقة + أجزاء + أزرار\n"
     "            else:\n                await status.done()\n                await send_prompt(bot, user.id, reply)\n"
     "        except RunStopped:\n            await studio.on_stopped()\n"
     "        except Exception as e:\n            logger.exception(\"request failed at stage %s\", stage)\n"
     "            await status.done()\n            await fail_request(ctx, user, stage, e, what, extra)\n"
     "        finally:\n            typing.cancel()\n            await status.done()\n"
     "            if studio:\n                studio.close()\n"),

    ("on_text: جواب سؤال التوضيح",
     "    aw = ud.pop(\"uawait\", None)\n    if aw and await handle_user_await(ctx, user, aw, text):\n",
     "    if await studio_on_text(update, ctx, text):                  # ينتظر البوت جواب سؤال توضيح؟\n        return\n"
     "    aw = ud.pop(\"uawait\", None)\n    if aw and await handle_user_await(ctx, user, aw, text):\n"),

    ("القائمة الرئيسية: شارة مكتبتي",
     "    keys = [\"m:set\"] + ([\"m:news\"] if news else [])",
     "    keys = [\"m:set\", \"m:lib\"] + ([\"m:news\"] if news else [])"),
    ("القائمة الرئيسية: زر مكتبتي",
     "        [btn(\"عن المطور\", \"u:dev\", \"success\")],\n    ]\n",
     "        [btn(new_label(\"مكتبتي\", \"m:lib\" in fresh), \"u:lib\", \"primary\"), btn(\"عن المطور\", \"u:dev\", \"success\")],\n    ]\n"),

    ("إعدادات المستخدم: نص الاستوديو",
     "    rows = [[btn(f\"لغة البرومبت: {DIALECT_LABEL.get(p['dialect'], 'فصحى')}\", \"u:sd\", \"primary\"),",
     "    text += \"\\n\" + studio_settings_text(uid)\n"
     "    rows = [[btn(f\"لغة البرومبت: {DIALECT_LABEL.get(p['dialect'], 'فصحى')}\", \"u:sd\", \"primary\"),"),
    ("إعدادات المستخدم: أزرار الاستوديو",
     "    rows.append([btn(\"مسح بياناتي\", \"u:sx\", \"danger\")])\n",
     "    rows.extend(studio_settings_rows(uid))\n    rows.append([btn(\"مسح بياناتي\", \"u:sx\", \"danger\")])\n"),
    ("زر التعليمات: قسم الاستوديو",
     "S(\"help_text\") or T_HELP,",
     "S(\"help_text\") or (T_HELP + STUDIO_HELP),"),
    ("مسح بياناتي: يمسح بيانات الاستوديو أيضاً",
     "        D().reset_user_data(q.from_user.id)\n",
     "        D().reset_user_data(q.from_user.id)\n        studio_forget(q.from_user.id)\n"),

    ("تسجيل الاستوديو بالتطبيق",
     "    app.add_handler(CommandHandler(\"news\", cmd_news, filters=only_msg))\n",
     "    app.add_handler(CommandHandler(\"news\", cmd_news, filters=only_msg))\n    studio_register(app)\n"),
    ("تهيئة الاستوديو عند الإقلاع",
     "    _APP = app\n    apply_runtime_settings()\n",
     "    _APP = app\n    studio_boot(app)\n    apply_runtime_settings()\n"),
]

BLOCK_ANCHOR = ("# ============================================================================\n"
                "#  تنفيذ الطلب: نص و/أو ملفات ← فحص ← OmniRoute ← البرومبت\n"
                "# ============================================================================\n"
                "async def serve(update, ctx, text, entities, media_msgs):\n")

SECRET_PATTERNS = [
    (re.compile(r'os\.environ\.get\("BOT_TOKEN",\s*"[^"]*"\)'), 'os.environ.get("BOT_TOKEN", "")'),
    (re.compile(r'os\.environ\.get\("OMNIROUTE_API_KEY",\s*"[^"]*"\)'), 'os.environ.get("OMNIROUTE_API_KEY", "")'),
]


def load_block():
    names = sorted(n for n in os.listdir(PARTS) if n.endswith(".py"))
    chunks = []
    for n in names:
        with open(os.path.join(PARTS, n), encoding="utf-8") as fh:
            chunks.append(fh.read().rstrip() + "\n")
    return "\n\n".join(chunks)


def apply(src):
    if MARK_BEGIN in src:
        sys.exit("الملف مركّب عليه الاستوديو أصلاً.")
    out = src
    for name, old, new in EDITS:
        n = out.count(old)
        if n != 1:
            sys.exit(f"فشل التعديل «{name}»: المرساة وُجدت {n} مرة (المطلوب مرة وحدة). الملف يختلف عن النسخة المتوقعة.")
        out = out.replace(old, new, 1)
    if out.count(BLOCK_ANCHOR) != 1:
        sys.exit("فشل إدخال كتلة الاستوديو: مرساة قسم «تنفيذ الطلب» غير موجودة مرة وحدة.")
    block = f"{MARK_BEGIN}\n{load_block()}{MARK_END}\n\n\n"
    out = out.replace(BLOCK_ANCHOR, block + BLOCK_ANCHOR, 1)
    stripped = []
    for rx, repl in SECRET_PATTERNS:
        out, k = rx.subn(repl, out)
        stripped.append(k)
    return out, stripped


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    with open(sys.argv[1], encoding="utf-8") as fh:
        src = fh.read()
    out, stripped = apply(src)
    with open(sys.argv[2], "w", encoding="utf-8") as fh:
        fh.write(out)
    print(f"تم: {len(EDITS)} تعديلاً + كتلة الاستوديو ({len(load_block().splitlines())} سطر). "
          f"أسرار انشالت من الكود: BOT_TOKEN×{stripped[0]}، OMNIROUTE_API_KEY×{stripped[1]}. الناتج: {sys.argv[2]}")


if __name__ == "__main__":
    main()
