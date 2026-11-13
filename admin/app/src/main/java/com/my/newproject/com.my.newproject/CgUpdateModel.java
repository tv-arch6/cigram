package com.my.newproject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

/** Data, labels, colors and formatting for the update center. No Android views in here. */
final class CgUpdateModel {

    private CgUpdateModel() {}

    static final String OPTIONAL = "optional";
    static final String IMPORTANT = "important";
    static final String FORCE = "force";
    static final String BETA = "beta";

    static final int BLUE = 0xFF4D8EFF;
    static final int PURPLE = 0xFFA78BFA;
    static final int ORANGE = 0xFFFF9F43;

    static final String[] TYPES = {OPTIONAL, IMPORTANT, FORCE, BETA};

    private static final String[] MONTHS = {
        "يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
        "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"
    };

    // ------------------------------------------------------------------ state

    /** Everything GET /update/history returns, split into the lists the screen needs. */
    static final class State {
        JSONObject live;                                   // cigram_update.json (or null)
        final ArrayList<JSONObject> releases = new ArrayList<JSONObject>(); // all, newest code first
        final ArrayList<JSONObject> drafts = new ArrayList<JSONObject>();
        final ArrayList<JSONObject> history = new ArrayList<JSONObject>();  // everything except drafts
        JSONObject scheduled;                              // status == scheduled (max one) or null
        JSONObject failedSchedule;                         // most recent failed schedule or null
        JSONArray audit = new JSONArray();
        long maxVersionCode;
        boolean pushConfigured;
        boolean maintenanceEnabled;
        long partSize = 6L * 1024L * 1024L;
        long maxBytes = 400L * 1024L * 1024L;
        String now = "";

        boolean livePublished() {
            return live != null && live.optBoolean("published", true) && live.optLong("versionCode", 0) > 0;
        }

        long liveCode() {
            return live == null ? 0 : live.optLong("versionCode", 0);
        }

        JSONObject liveRelease() {
            for (int i = 0; i < releases.size(); i++) {
                if ("published".equals(releases.get(i).optString("status"))) return releases.get(i);
            }
            return null;
        }

        JSONObject findRelease(String id) {
            for (int i = 0; i < releases.size(); i++) {
                if (releases.get(i).optString("id").equals(id)) return releases.get(i);
            }
            return null;
        }
    }

    static State parseState(JSONObject data) {
        State s = new State();
        s.live = data.optJSONObject("live");
        s.maxVersionCode = data.optLong("maxVersionCode", 0);
        s.now = data.optString("now", "");
        s.audit = data.optJSONArray("audit") == null ? new JSONArray() : data.optJSONArray("audit");
        JSONObject push = data.optJSONObject("push");
        s.pushConfigured = push != null && push.optBoolean("configured", false);
        JSONObject maint = data.optJSONObject("maintenance");
        s.maintenanceEnabled = maint != null && maint.optBoolean("enabled", false);
        JSONObject up = data.optJSONObject("upload");
        if (up != null) {
            s.partSize = up.optLong("partSize", s.partSize);
            s.maxBytes = up.optLong("maxBytes", s.maxBytes);
        }
        JSONArray arr = data.optJSONArray("releases");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r == null) continue;
            s.releases.add(r);
            String st = r.optString("status");
            if ("draft".equals(st)) {
                s.drafts.add(r);
            } else {
                s.history.add(r);
                if ("scheduled".equals(st) && s.scheduled == null) s.scheduled = r;
                if ("failed".equals(st) && s.failedSchedule == null) s.failedSchedule = r;
            }
        }
        return s;
    }

    // ------------------------------------------------------------ labels

    static String typeLabel(String type) {
        if (IMPORTANT.equals(type)) return "تحديث مهم";
        if (FORCE.equals(type)) return "تحديث إجباري";
        if (BETA.equals(type)) return "تحديث تجريبي";
        return "تحديث اختياري";
    }

    static String typeShort(String type) {
        if (IMPORTANT.equals(type)) return "مهم";
        if (FORCE.equals(type)) return "إجباري";
        if (BETA.equals(type)) return "تجريبي";
        return "اختياري";
    }

    static int typeColor(String type) {
        if (IMPORTANT.equals(type)) return CgCfg.WARN;
        if (FORCE.equals(type)) return CgCfg.BAD;
        if (BETA.equals(type)) return PURPLE;
        return CgCfg.ACCENT;
    }

    static String typeDescription(String type) {
        if (IMPORTANT.equals(type)) {
            return "يظهر بشكل أوضح للمستخدم مع توصية بالتحديث، لكن يمكنه تأجيله.";
        }
        if (FORCE.equals(type)) {
            return "من كان إصداره أقل من «الحد الأدنى المسموح» لا يستطيع متابعة استخدام التطبيق قبل أن يحدّث.";
        }
        if (BETA.equals(type)) {
            return "لاختبار إصدار قبل تعميمه. يُنشر بنفس الآلية ويُعلَّم كتجريبي ليتعامل معه التطبيق وفق قرارك.";
        }
        return "المستخدم يختار «تحديث الآن» أو «لاحقاً». مناسب للتحسينات العادية.";
    }

    static String releaseStatusLabel(String status) {
        if ("published".equals(status)) return "منشور";
        if ("superseded".equals(status)) return "إصدار سابق";
        if ("stopped".equals(status)) return "متوقف";
        if ("scheduled".equals(status)) return "مجدول";
        if ("draft".equals(status)) return "مسودة";
        if ("failed".equals(status)) return "فشلت الجدولة";
        return status;
    }

    static int releaseStatusColor(String status) {
        if ("published".equals(status)) return CgCfg.GOOD;
        if ("superseded".equals(status)) return CgCfg.GRAY;
        if ("stopped".equals(status)) return ORANGE;
        if ("scheduled".equals(status)) return BLUE;
        if ("failed".equals(status)) return CgCfg.BAD;
        return CgCfg.GRAY;
    }

    /** The state shown on the dashboard card. */
    static String liveStateKey(State s) {
        if (s.livePublished()) {
            String t = s.live.optString("updateType", OPTIONAL);
            return isType(t) ? t : OPTIONAL;
        }
        if (s.scheduled != null) return "scheduled";
        if (s.live != null && s.live.optLong("versionCode", 0) > 0) return "stopped";
        if (!s.drafts.isEmpty()) return "draft";
        return "none";
    }

    static String stateLabel(String key) {
        if ("none".equals(key)) return "لا يوجد تحديث";
        if ("scheduled".equals(key)) return "مجدول";
        if ("stopped".equals(key)) return "متوقف";
        if ("draft".equals(key)) return "مسودة";
        return typeLabel(key);
    }

    static int stateColor(String key) {
        if ("none".equals(key) || "draft".equals(key)) return CgCfg.GRAY;
        if ("scheduled".equals(key)) return BLUE;
        if ("stopped".equals(key)) return ORANGE;
        return typeColor(key);
    }

    static boolean isType(String t) {
        for (int i = 0; i < TYPES.length; i++) if (TYPES[i].equals(t)) return true;
        return false;
    }

    static String pushLabel(JSONObject release) {
        if (release == null) return "—";
        JSONObject p = release.optJSONObject("push");
        String st = p == null ? "none" : p.optString("status", "none");
        if ("sent".equals(st)) return "تم الإرسال";
        if ("failed".equals(st)) return "فشل الإرسال";
        if ("no_provider".equals(st)) return "غير مُعدّ في الـ Worker";
        return release.optBoolean("sendPush", false) ? "لم يُرسل" : "غير مطلوب";
    }

    static int pushColor(JSONObject release) {
        if (release == null) return CgCfg.GRAY;
        JSONObject p = release.optJSONObject("push");
        String st = p == null ? "none" : p.optString("status", "none");
        if ("sent".equals(st)) return CgCfg.GOOD;
        if ("failed".equals(st)) return CgCfg.BAD;
        if ("no_provider".equals(st)) return CgCfg.WARN;
        return CgCfg.MUTED;
    }

    static String pushStatus(JSONObject release) {
        if (release == null) return "none";
        JSONObject p = release.optJSONObject("push");
        return p == null ? "none" : p.optString("status", "none");
    }

    static int changelogCount(JSONObject release) {
        JSONArray a = release == null ? null : release.optJSONArray("changelog");
        return a == null ? 0 : a.length();
    }

    static ArrayList<String> changelogOf(JSONObject release) {
        ArrayList<String> out = new ArrayList<String>();
        JSONArray a = release == null ? null : release.optJSONArray("changelog");
        for (int i = 0; a != null && i < a.length(); i++) {
            String s = a.optString(i, "").trim();
            if (s.length() > 0) out.add(s);
        }
        return out;
    }

    // -------------------------------------------------------- formatting

    private static String two(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    /** "4 أكتوبر 2026 - 5:30 مساءً" in the device time zone. */
    static String arabicDateTime(long ms) {
        if (ms <= 0) return "—";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        int h = c.get(Calendar.HOUR);
        if (h == 0) h = 12;
        String ap = c.get(Calendar.AM_PM) == Calendar.AM ? "صباحاً" : "مساءً";
        return c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)] + " " + c.get(Calendar.YEAR)
                + " - " + h + ":" + two(c.get(Calendar.MINUTE)) + " " + ap;
    }

    static String arabicDate(long ms) {
        if (ms <= 0) return "—";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)] + " " + c.get(Calendar.YEAR);
    }

    static String arabicTime(long ms) {
        if (ms <= 0) return "—";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        int h = c.get(Calendar.HOUR);
        if (h == 0) h = 12;
        return h + ":" + two(c.get(Calendar.MINUTE)) + " " + (c.get(Calendar.AM_PM) == Calendar.AM ? "صباحاً" : "مساءً");
    }

    /** dd/MM/yyyy */
    static String shortDate(long ms) {
        if (ms <= 0) return "—";
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return two(c.get(Calendar.DAY_OF_MONTH)) + "/" + two(c.get(Calendar.MONTH) + 1) + "/" + c.get(Calendar.YEAR);
    }

    static String dateTimeOfIso(String iso) {
        long ms = CgUi.parseIso(iso);
        return ms < 0 ? "—" : arabicDateTime(ms);
    }

    static String shortDateOfIso(String iso) {
        long ms = CgUi.parseIso(iso);
        return ms < 0 ? "—" : shortDate(ms);
    }

    /** The date a history row should show: publish date, else schedule, else creation. */
    static String releaseDateIso(JSONObject r) {
        String s = r.optString("publishedAt", "");
        if (s.length() == 0) s = r.optString("scheduledAt", "");
        if (s.length() == 0) s = r.optString("updatedAt", "");
        if (s.length() == 0) s = r.optString("createdAt", "");
        return s;
    }

    static String sizeText(long bytes) {
        return bytes <= 0 ? "غير معروف" : CgUi.sizeText(bytes);
    }

    static String untilText(long targetMs) {
        long diff = (targetMs - System.currentTimeMillis()) / 1000L;
        if (diff <= 0) return "الآن";
        if (diff < 3600L) return "بعد " + Math.max(1, diff / 60L) + " دقيقة";
        if (diff < 86400L) return "بعد " + (diff / 3600L) + " ساعة";
        return "بعد " + (diff / 86400L) + " يوم";
    }

    static long parseLong(String s) {
        if (s == null) return -1;
        String t = s.trim();
        if (t.length() == 0 || t.length() > 10) return -1;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch < '0' || ch > '9') return -1;
        }
        try {
            return Long.parseLong(t);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Version names are plain ASCII; digits typed with an Arabic keyboard are converted. */
    static String normalizeDigits(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '٠' && ch <= '٩') sb.append((char) ('0' + (ch - '٠')));
            else if (ch >= '۰' && ch <= '۹') sb.append((char) ('0' + (ch - '۰')));
            else sb.append(ch);
        }
        return sb.toString();
    }

    static boolean validVersionName(String s) {
        if (s == null || s.length() == 0 || s.length() > 40) return false;
        char first = s.charAt(0);
        if (!((first >= '0' && first <= '9') || (first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z'))) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            boolean ok = (ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || ch == '.' || ch == '_' || ch == '+' || ch == '-';
            if (!ok) return false;
        }
        return true;
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }
}
