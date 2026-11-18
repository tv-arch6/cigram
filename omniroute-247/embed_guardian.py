#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""يضمّن omniroute_guardian.py داخل prompt.py (بين علامتين) كي يشتغل البوت كملف واحد على أي استضافة.

الاستعمال (من نفس المجلد):
    python embed_guardian.py                 # يحدّث prompt.py
    python embed_guardian.py path/to/bot.py  # أو ملف بوت ثاني فيه نفس العلامات

شغّله مرة ثانية بعد أي تعديل على omniroute_guardian.py. الكتلة المضمَّنة تُولَّد تلقائياً: لا تعدّلها بيدك.
"""
import base64
import os
import re
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
BEGIN = "# >>> OMNIROUTE-GUARDIAN-EMBED >>>"
END = "# <<< OMNIROUTE-GUARDIAN-EMBED <<<"

LOADER = '''def _load_guardian():
    """الحارس مضمَّن بالملف نفسه: يكفي رفع prompt.py وحده على أي استضافة (لا ملفات ثانية)."""
    try:
        import types as _types
        mod = _types.ModuleType("omniroute_guardian")
        mod.__file__ = os.path.abspath(globals().get("__file__") or "prompt.py")
        src = zlib.decompress(base64.b64decode("".join(_OG_B64))).decode("utf-8")
        exec(compile(src, "omniroute_guardian.py", "exec"), mod.__dict__)
        sys.modules["omniroute_guardian"] = mod
        return mod
    except Exception as _e:                                            # noqa: BLE001
        logger.warning("تعذّر تحميل حارس OmniRoute المضمَّن (%s): OmniRoute ما راح يشتغل تلقائياً.", _e)
        return None


_og = _load_guardian()'''


def build_block(guardian_path):
    with open(guardian_path, "rb") as f:
        raw = f.read()
    b64 = base64.b64encode(zlib.compress(raw, 9)).decode("ascii")
    lines = [b64[i:i + 100] for i in range(0, len(b64), 100)]
    body = "\n".join(f'    "{ln}",' for ln in lines)
    return (f"{BEGIN}\n"
            f"# (كتلة مولَّدة تلقائياً من omniroute_guardian.py بالأمر  python embed_guardian.py  : لا تعدّلها بيدك)\n"
            f"_OG_B64 = (\n{body}\n)\n\n{LOADER}\n{END}")


def main():
    bot = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "prompt.py")
    guardian = os.path.join(HERE, "omniroute_guardian.py")
    with open(bot, encoding="utf-8") as f:
        text = f.read()
    block = build_block(guardian)
    if BEGIN in text and END in text:
        a, b = text.index(BEGIN), text.index(END) + len(END)
        new = text[:a] + block + text[b:]
    else:                                                  # أول مرة: نستبدل كتلة الاستيراد القديمة
        m = re.search(r"try:\n    import omniroute_guardian as _og\nexcept Exception as _og_err:.*?_og_err\)\n", text, re.S)
        if not m:
            sys.exit("ما لقيت مكان التضمين بالملف (لا علامات ولا كتلة الاستيراد القديمة).")
        new = text[:m.start()] + block + "\n" + text[m.end():]
    with open(bot, "w", encoding="utf-8") as f:
        f.write(new)
    print(f"تم تضمين الحارس ({os.path.getsize(guardian)} بايت) داخل {bot}")


if __name__ == "__main__":
    main()
