#!/usr/bin/env bash
# ============================================================================
#  تثبيت التشغيل الدائم 24/7 على Termux (انفنكس هوت 60 برو بلس وأي جوال أندرويد)
#
#  الاستعمال (من مجلد البوت، بجنب prompt.py و omniroute_guardian.py):
#      bash install.sh              # تثبيت وتشغيل
#      bash install.sh --check      # فحص شامل لكل شي
#      bash install.sh --uninstall  # إزالة الخدمة والتشغيل عند الإقلاع
#  خيارات: --yes (بدون أسئلة) | --skip-packages (لا تثبّت حزم) | --bot-file اسم.py
#
#  النتيجة:
#    1) خدمة runit اسمها prompt-bot (termux-services): تشغّل البوت وتعيده تلقائياً لو وقع.
#    2) البوت يشغّل OmniRoute بنفسه عبر omniroute_guardian.py ويرجّعه لو وقع.
#    3) Termux:Boot يشغّل كل هذا عند فتح الجوال، مع wake-lock كي المعالج ما ينام.
# ============================================================================
set -u

ASSUME_YES=0; SKIP_PKG=0; MODE=install; BOT_FILE="prompt.py"
while [ $# -gt 0 ]; do
  case "$1" in
    --yes|-y) ASSUME_YES=1 ;;
    --skip-packages) SKIP_PKG=1 ;;
    --check) MODE=check ;;
    --uninstall) MODE=uninstall ;;
    --bot-file) shift; BOT_FILE="${1:-prompt.py}" ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    *) echo "خيار غير معروف: $1"; exit 2 ;;
  esac
  shift
done

BOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
SVC_NAME="prompt-bot"
SVC_DIR="$PREFIX/var/service/$SVC_NAME"
BOOT_DIR="$HOME_DIR/.termux/boot"
BOOT_FILE="$BOOT_DIR/10-$SVC_NAME.sh"
LOOP_FILE="$BOT_DIR/.bot-loop.sh"
MARK_BEGIN="# >>> prompt-bot autostart >>>"
MARK_END="# <<< prompt-bot autostart <<<"

say()  { printf '\033[1;32m[+]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*"; }
bad()  { printf '\033[1;31m[x]\033[0m %s\n' "$*"; }
have() { command -v "$1" >/dev/null 2>&1; }
is_termux() { [ -n "${FORCE_TERMUX:-}" ] || [ -n "${TERMUX_VERSION:-}" ] || case "$PREFIX" in *com.termux*) true ;; *) false ;; esac; }
ask() { [ "$ASSUME_YES" = 1 ] && return 0; local a; read -r -p "$1 [y/N] " a; case "$a" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac; }

PY="$(command -v python3 || command -v python || true)"

# يشغّل حلقة البوت البديلة بالخلفية (مرة وحدة فقط). لا نستعمل  A || B &  لأنها تُدخل القائمة كلها بعملية فرعية تمسك الطرفية.
start_loop() {
  pgrep -f "\.bot-loop\.sh" >/dev/null 2>&1 && return 0
  nohup sh "$LOOP_FILE" >> "$BOT_DIR/bot-loop.log" 2>&1 < /dev/null &
}
# نفس الفكرة كسطر يُكتب داخل سكربتات الإقلاع و .bashrc
LOOP_LINE="pgrep -f '\\.bot-loop\\.sh' >/dev/null 2>&1 || { nohup sh \"$LOOP_FILE\" >> \"$BOT_DIR/bot-loop.log\" 2>&1 < /dev/null & }"

# ----------------------------------------------------------------------------
strip_bashrc() {
  local rc="$HOME_DIR/.bashrc"
  [ -f "$rc" ] || return 0
  awk -v b="$MARK_BEGIN" -v e="$MARK_END" '$0==b{skip=1} !skip{print} $0==e{skip=0}' "$rc" > "$rc.tmp" && mv "$rc.tmp" "$rc"
}

# ----------------------------------------------------------------------------
do_uninstall() {
  say "إزالة الخدمة والتشغيل التلقائي..."
  have sv-disable && sv-disable "$SVC_NAME" 2>/dev/null
  have sv && sv down "$SVC_NAME" 2>/dev/null
  rm -rf "$SVC_DIR" "$BOOT_FILE" "$LOOP_FILE"
  pkill -f "\.bot-loop\.sh" 2>/dev/null
  strip_bashrc
  say "تمت الإزالة. (OmniRoute والبوت ما انحذفوا، فقط التشغيل التلقائي)"
  exit 0
}

# ----------------------------------------------------------------------------
do_check() {
  local rc=0
  echo "== فحص التشغيل الدائم =="
  is_termux && say "بيئة Termux" || warn "مو Termux (بيئة لينكس عادية)"
  [ -f "$BOT_DIR/$BOT_FILE" ] && say "ملف البوت: $BOT_DIR/$BOT_FILE" || { bad "ما لكيت $BOT_FILE"; rc=1; }
  [ -f "$BOT_DIR/omniroute_guardian.py" ] && say "ملف الحارس موجود" || { bad "omniroute_guardian.py ناقص بجنب البوت"; rc=1; }
  if have sv && [ -d "$SVC_DIR" ]; then sv status "$SVC_NAME" 2>&1 | sed 's/^/    /'; else warn "خدمة runit غير مثبتة (يمكن وضع الحلقة البديلة)"; fi
  pgrep -fa "$BOT_FILE" >/dev/null 2>&1 && say "البوت شغّال: $(pgrep -f "$BOT_FILE" | head -3 | tr '\n' ' ')" || warn "البوت مو شغّال هسه"
  [ -x "$BOOT_FILE" ] && say "سكربت Termux:Boot موجود" || warn "سكربت Termux:Boot غير موجود (التشغيل عند فتح الجوال ما راح يشتغل)"
  have termux-wake-lock && say "termux-wake-lock متوفر" || warn "termux-wake-lock مو موجود (pkg install termux-tools)"
  [ -n "$PY" ] && (cd "$BOT_DIR" && "$PY" omniroute_guardian.py check) || rc=1
  exit $rc
}

[ "$MODE" = uninstall ] && do_uninstall
[ "$MODE" = check ] && do_check

# ============================================================================
#  التثبيت
# ============================================================================
[ -f "$BOT_DIR/$BOT_FILE" ] || { bad "ما لكيت $BOT_DIR/$BOT_FILE. شغّل السكربت من مجلد البوت أو استعمل --bot-file."; exit 1; }
[ -f "$BOT_DIR/omniroute_guardian.py" ] || { bad "omniroute_guardian.py لازم يكون بنفس مجلد البوت."; exit 1; }
[ -n "$PY" ] || { bad "python مو منصّب. نفّذ: pkg install python"; exit 1; }

if is_termux && [ "$SKIP_PKG" = 0 ]; then
  say "تحديث الحزم الأساسية (قد ياخذ دقيقة)..."
  yes | pkg update -y -o Dpkg::Options::="--force-confold" >/dev/null 2>&1 || warn "pkg update ما كمل، أكمل بدونه"
  need=""
  have node || need="$need nodejs"
  have sv   || need="$need termux-services"
  have pkill || need="$need procps"
  have termux-wake-lock || need="$need termux-tools"
  if [ -n "$need" ]; then
    say "تنصيب:$need"
    pkg install -y $need || warn "فشل تنصيب بعض الحزم:$need"
  fi
fi

# --- مكتبات بايثون
if ! "$PY" -c 'import telegram, httpx' >/dev/null 2>&1; then
  say "تنصيب python-telegram-bot..."
  "$PY" -m pip install -U "python-telegram-bot>=20.7" || bad "فشل pip؛ نصّبها يدوياً: pip install -U \"python-telegram-bot>=20.7\""
fi

# --- Node و OmniRoute
if have node; then
  nv="$(node -v 2>/dev/null | sed 's/^v//; s/\..*//')"
  [ "${nv:-0}" -ge 22 ] 2>/dev/null && say "Node $(node -v)" || warn "OmniRoute يحتاج Node 22 أو أحدث (عندك $(node -v)). نفّذ: pkg upgrade nodejs"
else
  warn "Node مو موجود؛ بدونه OmniRoute ما يشتغل."
fi
if have omniroute; then
  say "OmniRoute موجود: $(command -v omniroute)"
elif have npm; then
  say "OmniRoute مو منصّب. تنصيبه (ياخذ كم دقيقة على الجوال)..."
  npm install -g omniroute || warn "فشل التنصيب. جرّب: pkg install python build-essential ثم npm install -g omniroute"
else
  warn "ما لكيت npm. الحارس راح يحاول لاحقاً."
fi

# --- لا نسمح ببوتين على نفس التوكن (تيليجرام يرفض: Conflict)
running="$(pgrep -f "python.* $BOT_FILE" 2>/dev/null | grep -v "^$$\$" || true)"
if [ -n "$running" ]; then
  warn "البوت شغّال هسه يدوياً (PID: $(echo $running | tr '\n' ' ')). تشغيل نسختين بنفس التوكن يسبب Conflict."
  if ask "أوقف النسخة اليدوية وأخلّي الخدمة تتولّى؟"; then
    kill $running 2>/dev/null; sleep 2; kill -9 $running 2>/dev/null
  else
    warn "ما أوقفتها. أوقفها بنفسك قبل تشغيل الخدمة."
  fi
fi

# --- سكربت التشغيل المشترك (يصلح لـ runit ولبديل الحلقة)
write_run_body() {
cat <<EOF
export HOME="$HOME_DIR"
export PREFIX="$PREFIX"
export PATH="$PREFIX/bin:$HOME_DIR/.npm-global/bin:\$PATH"
export TMPDIR="$PREFIX/tmp"
cd "$BOT_DIR" || exit 1
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock
[ -f "$BOT_DIR/bot.env" ] && . "$BOT_DIR/bot.env"      # اختياري: ملف فيه  export BOT_TOKEN=...  أو أي إعدادات
EOF
}

USE_RUNIT=0
if is_termux && have sv; then
  # يشغّل runsvdir إن ما كان شغّال (termux-services يسويها عند فتح جلسة)
  [ -f "$PREFIX/etc/profile.d/start-services.sh" ] && . "$PREFIX/etc/profile.d/start-services.sh" >/dev/null 2>&1
  mkdir -p "$SVC_DIR/log"
  { echo "#!$PREFIX/bin/sh"; echo "exec 2>&1"; write_run_body; echo "exec \"$PY\" \"$BOT_DIR/$BOT_FILE\""; } > "$SVC_DIR/run"
  chmod +x "$SVC_DIR/run"
  if [ -e "$PREFIX/share/termux-services/svlogger" ]; then ln -sf "$PREFIX/share/termux-services/svlogger" "$SVC_DIR/log/run"; fi
  USE_RUNIT=1
  say "خدمة runit جاهزة: $SVC_DIR"
fi

# --- بديل: حلقة بسيطة إذا termux-services مو متوفرة
if [ "$USE_RUNIT" = 0 ]; then
  { echo "#!/bin/sh"; write_run_body
    echo "while true; do \"$PY\" \"$BOT_DIR/$BOT_FILE\"; echo \"البوت وقف، إعادة بعد 5 ثواني\"; sleep 5; done"; } > "$LOOP_FILE"
  chmod +x "$LOOP_FILE"
  warn "termux-services مو متوفرة؛ استعملت حلقة بديلة: $LOOP_FILE"
fi

# --- التشغيل عند فتح الجوال (Termux:Boot) + عند فتح Termux
if is_termux; then
  mkdir -p "$BOOT_DIR"
  {
    echo "#!$PREFIX/bin/sh"
    echo "# يُنفَّذ عند إقلاع الجوال بواسطة Termux:Boot"
    echo "export PREFIX=\"$PREFIX\" HOME=\"$HOME_DIR\""
    echo "export PATH=\"$PREFIX/bin:\$PATH\""
    echo "termux-wake-lock"
    if [ "$USE_RUNIT" = 1 ]; then
      echo ". \"$PREFIX/etc/profile.d/start-services.sh\""
    else
      echo "$LOOP_LINE"
    fi
  } > "$BOOT_FILE"
  chmod +x "$BOOT_FILE"
  say "سكربت الإقلاع: $BOOT_FILE"

  strip_bashrc
  {
    echo "$MARK_BEGIN"
    echo "command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock"
    [ "$USE_RUNIT" = 0 ] && echo "$LOOP_LINE"
    echo "$MARK_END"
  } >> "$HOME_DIR/.bashrc"
fi

# --- تشغيل هسه
say "تشغيل البوت هسه..."
if [ "$USE_RUNIT" = 1 ]; then
  sleep 3                                              # runsvdir يلتقط الخدمة الجديدة
  have sv-enable && sv-enable "$SVC_NAME" 2>/dev/null
  sv up "$SVC_NAME" 2>/dev/null
  sleep 6
  sv status "$SVC_NAME" 2>&1 | sed 's/^/    /'
else
  start_loop
  sleep 3
fi

cat <<EOF

==============================================================================
 تمام. البوت يشتغل، وهو بنفسه يشغّل OmniRoute ويرجّعه لو وقع (ما تحتاج تكتب omniroute).
 أوامر مفيدة:
   bash install.sh --check                       فحص كل شي
   sv status $SVC_NAME   |  sv restart $SVC_NAME    حالة / إعادة تشغيل البوت
   python omniroute_guardian.py status           حالة OmniRoute
   python omniroute_guardian.py logs -n 80       سجل OmniRoute
   tail -f \$PREFIX/var/log/sv/$SVC_NAME/current  سجل البوت (runit)

 لازم تسويها مرة وحدة من إعدادات الجوال (هذا الجزء مو بيدي، أندرويد وانفنكس يقتلون التطبيقات):
  1) نصّب Termux:Boot (من نفس مصدر Termux: F-Droid) وافتحه مرة وحدة.
  2) الإعدادات ← التطبيقات ← Termux ← البطارية ← «بدون قيود / Unrestricted»، وسوّي نفس الشي لـ Termux:Boot.
  3) السماح بالتشغيل التلقائي (Auto-start) لـ Termux و Termux:Boot (بإدارة الهاتف / Phone Master حسب نسخة النظام).
  4) ثبّت Termux بالتطبيقات الأخيرة (قفل) ولا تسحبه للإغلاق. واترك إشعار Termux (Wake lock) ظاهر.
  5) أندرويد 12 وأحدث: خيارات المطوّر ← فعّل «Disable child process restrictions» (إن وُجد) وأعد التشغيل.
     وإذا ما لقيته: بأمر adb (تصحيح لاسلكي):
       adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
==============================================================================
EOF
