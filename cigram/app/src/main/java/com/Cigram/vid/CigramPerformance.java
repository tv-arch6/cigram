package com.Cigram.vid;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;

/**
 * Cigram Performance Engine — one decision about how hard this phone may be pushed, read by every heavy part of the
 * app instead of each part guessing on its own.
 *
 * <pre>
 *   LITE      weak phones (≤ 2 GB / low-RAM flag / small heap / 2-4 slow cores), battery saver, a hot phone
 *   BALANCED  ordinary phones
 *   ULTRA     strong phones (big heap, 8+ cores, Android 10+)
 * </pre>
 * The profile sets: parallel poster downloads, the poster memory cache, how many cards a home row builds, whether
 * posters fade in and screens animate, whether rows are pre-built while the user is still on the first screen, and
 * the decode format of posters (RGB_565 halves the memory of a poster on LITE).
 *
 * The user can force a profile in «الإعدادات» (تلقائي / خفيف / متوازن / أقصى أداء); «تلقائي» is re-evaluated when
 * battery saver or the phone's thermal state changes, so a phone that gets hot during a long session is eased off
 * instead of stuttering. Decisions are pure ({@link #decide}) and unit-tested.
 */
final class CigramPerformance {

    private CigramPerformance() { }

    static final int LITE = 0, BALANCED = 1, ULTRA = 2;
    static final String[] NAMES = {"خفيف", "متوازن", "أقصى أداء"};
    static final String PREFS = "cigram_performance";

    /** What a profile allows. Immutable. */
    static final class Profile {
        final int level;
        final int imageParallel;
        final int posterCacheKbCap;
        final int rowCards;
        final boolean animations;
        final boolean prebuildRows;
        final boolean rgb565;
        final String why;

        Profile(int level, String why) {
            this.level = level;
            this.why = why == null ? "" : why;
            this.imageParallel = level == LITE ? 2 : level == ULTRA ? 4 : 3;
            this.posterCacheKbCap = level == LITE ? 16 * 1024 : level == ULTRA ? 64 * 1024 : 48 * 1024;
            this.rowCards = level == LITE ? 14 : level == ULTRA ? 30 : 22;
            this.animations = level != LITE;
            this.prebuildRows = level == ULTRA;
            this.rgb565 = level == LITE;
        }

        String name() { return NAMES[level]; }
    }

    /** Everything the decision looks at (filled from the phone, or by a test). */
    static final class Signals {
        long totalRamMb = 3072;
        int heapMb = 192;
        boolean lowRamFlag;
        int cores = 8;
        int sdk = 30;
        boolean powerSave;
        /** PowerManager thermal status: 0 none … 3 severe … 6 shutdown. */
        int thermal;
        /** -1 = automatic, else LITE / BALANCED / ULTRA chosen by the user. */
        int forced = -1;
    }

    /** The decision. */
    static Profile decide(Signals s) {
        if (s.forced >= LITE && s.forced <= ULTRA) return new Profile(s.forced, "اختيار المستخدم");
        if (s.thermal >= 3) return new Profile(LITE, "الجهاز ساخن");
        if (s.powerSave) return new Profile(LITE, "وضع توفير البطارية");
        int score = 0;
        if (s.lowRamFlag) score += 3;
        if (s.totalRamMb > 0 && s.totalRamMb <= 2048) score += 3;
        else if (s.totalRamMb <= 3072) score += 1;
        if (s.heapMb <= 96) score += 2;
        else if (s.heapMb <= 128) score += 1;
        if (s.cores <= 4) score += 1;
        if (s.sdk <= 25) score += 1;
        if (score >= 3) return new Profile(LITE, "جهاز محدود الموارد");
        boolean strong = s.totalRamMb >= 6144 && s.heapMb >= 256 && s.cores >= 8 && s.sdk >= 29;
        if (strong && s.thermal <= 1) return new Profile(ULTRA, "جهاز قوي");
        return new Profile(BALANCED, s.thermal == 2 ? "حرارة متوسطة" : "جهاز متوسط");
    }

    // ------------------------------------------------------------------ the phone

    private static volatile Profile CURRENT;
    private static volatile long decidedAt;

    static Signals read(Context c) {
        Signals s = new Signals();
        Context app = c.getApplicationContext();
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                s.totalRamMb = mi.totalMem / (1024L * 1024L);
                s.heapMb = am.getLargeMemoryClass();
                s.lowRamFlag = am.isLowRamDevice();
            }
        } catch (Throwable ignored) { }
        s.cores = Runtime.getRuntime().availableProcessors();
        s.sdk = Build.VERSION.SDK_INT;
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                s.powerSave = pm.isPowerSaveMode();
                if (Build.VERSION.SDK_INT >= 29) s.thermal = pm.getCurrentThermalStatus();
            }
        } catch (Throwable ignored) { }
        s.forced = forced(app);
        return s;
    }

    /** The profile now (re-decided at most every 30 s: battery saver and heat change during a session). */
    static Profile get(Context c) {
        Profile p = CURRENT;
        long now = android.os.SystemClock.elapsedRealtime();
        if (p != null && now - decidedAt < 30000L) return p;
        try {
            p = decide(read(c));
        } catch (Throwable t) {
            p = new Profile(BALANCED, "");
        }
        CURRENT = p;
        decidedAt = now;
        return p;
    }

    static int forced(Context c) {
        try {
            return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("forced", -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    static void setForced(Context c, int level) {
        try {
            SharedPreferences.Editor e = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            if (level < LITE || level > ULTRA) e.remove("forced"); else e.putInt("forced", level);
            e.apply();
        } catch (Throwable ignored) { }
        CURRENT = null;
    }

    /** One line for the settings row, e.g. «تلقائي · خفيف (وضع توفير البطارية)». */
    static String label(Context c) {
        Profile p = get(c);
        return (forced(c) < 0 ? "تلقائي · " : "") + p.name() + (p.why.length() > 0 && forced(c) < 0 ? " (" + p.why + ")" : "");
    }

    static void choose(final android.app.Activity a, final Runnable after) {
        final String[] items = {"تلقائي (ينصح به)", "خفيف — للأجهزة الضعيفة", "متوازن", "أقصى أداء — للأجهزة القوية"};
        int sel = forced(a) + 1;
        new android.app.AlertDialog.Builder(a).setTitle("محرك الأداء")
                .setSingleChoiceItems(items, sel, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        setForced(a, w - 1);
                        d.dismiss();
                        Profile p = get(a);
                        android.widget.Toast.makeText(a, "محرك الأداء: " + p.name() + " — يُطبَّق بالكامل عند فتح التطبيق التالي", android.widget.Toast.LENGTH_LONG).show();
                        if (after != null) after.run();
                    }
                }).setNegativeButton("إغلاق", null).show();
    }
}
