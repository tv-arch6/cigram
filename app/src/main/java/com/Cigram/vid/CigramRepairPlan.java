package com.Cigram.vid;

/**
 * Cigram Auto Repair — the DECISIONS (pure Java, unit-tested; {@link CigramAutoRepair} carries them out).
 *
 * One broken playback is an "incident". Each failed attempt of the same incident moves one step further down a
 * ladder that depends on WHAT broke, from the cheapest fix to the most thorough one:
 *
 * <pre>
 *   network cut / time-out   reopen at the same second → again → reopen from the title's own link → once more
 *   HTTP 401/403/404/410     (an expired or moved file) reopen from the title's own link at once → again
 *   HTTP 429 / 5xx           wait longer, then as a network cut
 *   broken / unknown file    reopen from the title's own link once
 *   decoder                  reopen (the next decoder is tried) → lower the quality ceiling → lower it again
 *   audio output             reopen, twice
 *   live window left behind  jump to the live edge
 *   hung connection          (buffering, nothing arriving) reopen → reopen from the title's own link → lower quality
 * </pre>
 * While the phone has no network at all nothing is attempted and no attempt is used up: the repair waits for the
 * connection and continues from the same second. After the last rung the user gets one honest dialog instead of an
 * endless spinner. An incident is over (the ladder starts from the top again) once playback has run for 8 seconds.
 */
final class CigramRepairPlan {

    private CigramRepairPlan() { }

    // what broke
    static final int NETWORK = 1, HTTP_STATUS = 2, PARSING = 3, DECODER = 4, AUDIO = 5, LIVE_WINDOW = 6, STALL = 7, OTHER = 8;

    // what to do
    static final int WAIT_NETWORK = 0, REOPEN = 1, REOPEN_SOURCE = 2, LOWER_QUALITY_REOPEN = 3, LIVE_EDGE = 4, GIVE_UP = 5;

    /** ExoPlayer's PlaybackException.errorCode → kind. */
    static int kindOf(int errorCode) {
        if (errorCode == 1002) return LIVE_WINDOW;
        if (errorCode == 2004) return HTTP_STATUS;
        if (errorCode == 1003 || (errorCode >= 2000 && errorCode <= 2008)) return NETWORK;
        if (errorCode >= 3001 && errorCode <= 3004) return PARSING;
        if (errorCode >= 4001 && errorCode <= 4005) return DECODER;
        if (errorCode == 5001 || errorCode == 5002) return AUDIO;
        return OTHER;
    }

    /**
     * @param attempt           0 for the first repair of this incident, 1 for the second …
     * @param httpStatus        the status of an HTTP_STATUS incident, 0 otherwise
     * @param hasSeparateSource the title was opened with a link that is not the stream being played (it can be asked again)
     * @param canLowerQuality   a lower quality ceiling than the current one exists
     */
    static int next(int kind, int attempt, boolean online, int httpStatus, boolean hasSeparateSource, boolean canLowerQuality) {
        boolean networkBound = kind == NETWORK || kind == HTTP_STATUS || kind == STALL || kind == OTHER || kind == LIVE_WINDOW;
        if (!online && networkBound) return WAIT_NETWORK;
        int again = hasSeparateSource ? REOPEN_SOURCE : REOPEN;
        switch (kind) {
            case LIVE_WINDOW:
                return attempt < 3 ? LIVE_EDGE : attempt < 5 ? REOPEN : GIVE_UP;
            case HTTP_STATUS:
                if (httpStatus == 401 || httpStatus == 403 || httpStatus == 404 || httpStatus == 410) return attempt < 2 ? again : GIVE_UP;
                return attempt < 2 ? REOPEN : attempt == 2 ? again : GIVE_UP;          // 429 / 5xx: the server, not the link
            case NETWORK:
                return attempt < 2 ? REOPEN : attempt == 2 ? again : attempt == 3 ? REOPEN : GIVE_UP;
            case PARSING:
                return attempt == 0 ? again : GIVE_UP;
            case DECODER:
                if (attempt == 0) return REOPEN;
                return attempt <= 2 && canLowerQuality ? LOWER_QUALITY_REOPEN : GIVE_UP;
            case AUDIO:
                return attempt < 2 ? REOPEN : GIVE_UP;
            case STALL:
                if (attempt == 0) return REOPEN;
                if (attempt == 1) return again;
                if (attempt == 2) return canLowerQuality ? LOWER_QUALITY_REOPEN : REOPEN;
                return GIVE_UP;
            default:
                return attempt == 0 ? REOPEN : attempt == 1 ? again : GIVE_UP;
        }
    }

    /** Pause before the attempt: quick at first, patient later (a server that answers 429 / 5xx gets more room). */
    static long delayMs(int kind, int attempt, int httpStatus) {
        if (kind == LIVE_WINDOW || kind == DECODER || kind == AUDIO || kind == PARSING) return attempt == 0 ? 300 : 1200;
        boolean serverBusy = kind == HTTP_STATUS && (httpStatus == 429 || httpStatus >= 500);
        long[] steps = serverBusy ? new long[]{3000, 8000, 20000, 30000} : new long[]{800, 3000, 6000, 12000};
        return steps[Math.min(attempt, steps.length - 1)];
    }

    /** The quality ceiling one step below {@code current} (0 = none set): 1080 → 720 → 480; 0 = cannot go lower. */
    static int lowerCeiling(int current) {
        if (current <= 0 || current > 1080) return 1080;
        if (current > 720) return 720;
        if (current > 480) return 480;
        return 0;
    }

    /** One line for the status chip while the repair runs. */
    static String statusText(int action, int attempt) {
        switch (action) {
            case WAIT_NETWORK: return "لا يوجد اتصال — يكمل التشغيل تلقائياً عند عودته";
            case LIVE_EDGE: return "العودة إلى البث المباشر…";
            case LOWER_QUALITY_REOPEN: return "خفض الجودة لتناسب جهازك…";
            case REOPEN_SOURCE: return "تجديد رابط البث… (" + (attempt + 1) + ")";
            default: return "انقطع البث — جارٍ الإصلاح… (" + (attempt + 1) + ")";
        }
    }

    /** Honest last word when the ladder is exhausted. */
    static String giveUpText(int kind, int httpStatus) {
        switch (kind) {
            case HTTP_STATUS:
                if (httpStatus == 404 || httpStatus == 410) return "ملف هذا الفيديو لم يعد موجوداً على الخادم.";
                if (httpStatus == 401 || httpStatus == 403) return "خادم الفيديو يرفض الطلب (انتهت صلاحية الرابط أو المحتوى غير متاح في منطقتك).";
                return "خادم الفيديو لا يستجيب الآن (" + httpStatus + "). حاول بعد قليل.";
            case NETWORK: return "الاتصال بالخادم ينقطع باستمرار. تحقق من الشبكة ثم أعد المحاولة.";
            case PARSING: return "ملف الفيديو تالف أو بصيغة لا يدعمها المشغل.";
            case DECODER: return "جهازك لا يستطيع فك ترميز هذا الفيديو بأي جودة متاحة.";
            case AUDIO: return "تعذر تشغيل الصوت على هذا الجهاز. أغلق التطبيقات التي تستخدم الصوت ثم أعد المحاولة.";
            case STALL: return "سرعة الاتصال لا تكفي لتشغيل هذا الفيديو الآن.";
            default: return "تعذر تشغيل الفيديو بعد عدة محاولات إصلاح.";
        }
    }
}
