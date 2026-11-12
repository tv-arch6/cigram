package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.util.DisplayMetrics;

/**
 * «وضع التلفاز» (v10.64): نفس ملف التطبيق يُثبَّت على التلفاز (كود Downloader)، وواجهاته مبنية للجوال وكانت
 * مقفولة على الطول. هذا الصف يجعل التطبيق على التلفاز يعمل بالعرض وبحجم واضح من بعيد.
 *
 *  - التلفاز يُكتشف تلقائياً (Leanback / UI_MODE_TYPE_TELEVISION / جهاز بلا شاشة لمس)، ويمكن للمستخدم فرضه أو
 *    إيقافه من الإعدادات («وضع التلفاز»: تلقائي / مفعّل / متوقف) — مفيد لأجهزة التلفاز التي لا تعرّف نفسها كتلفاز.
 *  - على التلفاز: كل شاشات التطبيق بالعرض، وتُكبَّر الواجهة كلها بحيث يصير عرض الشاشة ≈ TARGET_WIDTH_DP
 *    (الأحجام في التطبيق كلها dp من resources السياق، فتتكبّر معاً: النصوص والبطاقات والأزرار).
 *  - على الجوال: لا شيء يتغير. الشاشات التي كانت مقفولة على الطول في المانيفست صار قفلها هنا (نفس الأثر)
 *    حتى لا يبقى القفل على التلفاز.
 *
 * كل الدوال تبتلع الأخطاء: أي فشل هنا يعني أن الواجهة تبقى كما هي بدل أن ينهار التطبيق.
 */
public final class CigramTvMode {

    public static final int AUTO = 0, ON = 1, OFF = 2;

    private static final String PREFS = "cigram_tv_mode";
    private static final String KEY_MODE = "mode";

    /** عرض الشاشة بالـ dp بعد التكبير على التلفاز (≈ جوال كبير بالعرض). كبّره لواجهة أصغر، صغّره لواجهة أكبر. */
    private static final float TARGET_WIDTH_DP = 960f;   // v10.67: Android TV's standard (was 720)

    /** الشاشات التي كانت «Portrait» في إعدادات المشروع (قفل الطول يُطبَّق الآن من هنا على غير التلفاز). */
    private static final String[] PHONE_PORTRAIT = {
            "MainActivity", "DetailsActivity", "SettingsActivity",
            "MyContentActivity", "SectionActivity", "NotificationsActivity"
    };

    private static final String APP_PACKAGE_PREFIX = "com.Cigram.vid.";

    private static volatile boolean densityOverridden;   // هل غيّرنا الكثافة في هذه العملية؟

    private CigramTvMode() { }

    // ------------------------------------------------------------------ setting

    public static int mode(Context c) {
        try {
            int m = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, AUTO);
            return m < AUTO || m > OFF ? AUTO : m;
        } catch (Throwable ignored) {
            return AUTO;
        }
    }

    public static void setMode(Context c, int mode) {
        try {
            SharedPreferences.Editor e = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            e.putInt(KEY_MODE, mode).apply();
        } catch (Throwable ignored) { }
    }

    /** النص الظاهر تحت صف «وضع التلفاز» في الإعدادات. */
    public static String label(Context c) {
        int m = mode(c);
        if (m == ON) return "مفعّل دائماً" + perfSuffix(c) + " · اضغط للتغيير";
        if (m == OFF) return "متوقف" + perfSuffix(c) + " · اضغط للتغيير";
        return (isTv(c) ? "تلقائي (تم اكتشاف تلفاز)" : "تلقائي (جوال)") + perfSuffix(c) + " · اضغط للتغيير";
    }

    private static String perfSuffix(Context c) {
        try {
            return " · أداء الجهاز: " + CigramDevice.tierName(c);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean isTv(Context c) {
        int m = mode(c);
        if (m == ON) return true;
        if (m == OFF) return false;
        return detectTelevision(c);
    }

    private static boolean detectTelevision(Context c) {
        // v10.65: one shared, cached detector (also catches TV boxes that do not announce themselves).
        try {
            return CigramDevice.looksLikeTv(c);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------ per activity

    /** يُستدعى من دورة حياة الأنشطة قبل رسم أول إطار. */
    static void apply(Activity a) {
        try {
            if (a == null || !a.getClass().getName().startsWith(APP_PACKAGE_PREFIX)) return;
            if (isTv(a)) {
                int cur = a.getRequestedOrientation();
                if (cur != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        && cur != ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        && cur != ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                        && cur != ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE) {
                    a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                }
                applyDensity(a);
            } else {
                restoreDensity(a);
                if (isPhonePortraitScreen(a)) {
                    a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                }
            }
        } catch (Throwable ignored) { }
    }

    /** خفيف: يعيد تثبيت الكثافة فقط (قد يستبدل النظام Resources بعد تغيّر الإعدادات). */
    static void refresh(Activity a) {
        try {
            if (a == null || !a.getClass().getName().startsWith(APP_PACKAGE_PREFIX)) return;
            if (isTv(a)) {
                applyDensity(a);
                CigramTvUi.resumed(a);   // v10.66: remote focus + side rail (TV only)
            }
        } catch (Throwable ignored) { }
    }

    /** v10.66: the page left the screen (TV only: stops the focus guard of that page). */
    static void paused(Activity a) {
        try {
            if (a == null || !a.getClass().getName().startsWith(APP_PACKAGE_PREFIX)) return;
            if (isTv(a)) CigramTvUi.paused(a);
        } catch (Throwable ignored) { }
    }

    /** v10.66: the page finished binding its features (TV only: remote focus, page layouts). */
    static void bound(Activity a) {
        try {
            if (a == null || !a.getClass().getName().startsWith(APP_PACKAGE_PREFIX)) return;
            if (isTv(a)) CigramTvUi.bound(a);
        } catch (Throwable ignored) { }
    }

    private static boolean isPhonePortraitScreen(Activity a) {
        String name = a.getClass().getSimpleName();
        for (String s : PHONE_PORTRAIT) {
            if (s.equals(name)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ crash notice (TV only)

    /** Set to false to stop showing the notice. */
    private static final boolean SHOW_CRASH_NOTICE = true;
    private static final String STABILITY_PREFS = "cigram_stability";
    private static volatile boolean noticeChecked;

    /**
     * v10.65: if the app stopped with an error on the last run and this is a TV, show once what the
     * error was (a photo of this box is all support needs). Nothing is shown on phones, and the
     * pending flag is cleared BEFORE the dialog opens so it can never repeat or loop.
     */
    static void showCrashNotice(final Activity a) {
        try {
            if (!SHOW_CRASH_NOTICE || noticeChecked || a == null) return;
            if (!a.getClass().getName().startsWith(APP_PACKAGE_PREFIX)) return;
            noticeChecked = true;
            if (!isTv(a)) return;
            SharedPreferences p = a.getApplicationContext().getSharedPreferences(STABILITY_PREFS, Context.MODE_PRIVATE);
            if (!p.getBoolean("trace_pending", false)) return;
            String trace = p.getString("last_crash_trace", "");
            p.edit().putBoolean("trace_pending", false).commit();
            if (trace == null || trace.length() == 0 || a.isFinishing()) return;

            StringBuilder msg = new StringBuilder();
            msg.append("توقف التطبيق في المرة السابقة بسبب خطأ.\nصوّر هذه الشاشة وأرسلها للدعم حتى نصلحه.\n\n");
            String[] lines = trace.split("\n");
            for (int i = 0; i < lines.length && i < 10; i++) {
                String l = lines[i];
                msg.append(l.length() > 120 ? l.substring(0, 120) : l).append('\n');
            }
            msg.append('\n').append(CigramDevice.summary(a));
            try {
                android.content.pm.PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
                msg.append("\nv").append(pi.versionName).append(" (").append(pi.versionCode).append(')');
            } catch (Throwable ignored) { }
            new android.app.AlertDialog.Builder(a)
                    .setTitle("Cigram")
                    .setMessage(msg.toString())
                    .setCancelable(true)
                    .setPositiveButton("تمام", null)
                    .show();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ scaling

    private static void applyDensity(Activity a) {
        Resources res = a.getResources();
        DisplayMetrics dm = res.getDisplayMetrics();
        // مقاس الشاشة الحقيقي (لا النافذة): في صورة داخل صورة أو النوافذ المتعددة يبقى التكبير ثابتاً
        int longSide = Math.max(dm.widthPixels, dm.heightPixels);
        try {
            DisplayMetrics real = new DisplayMetrics();
            a.getWindowManager().getDefaultDisplay().getRealMetrics(real);
            longSide = Math.max(real.widthPixels, real.heightPixels);
        } catch (Throwable ignored) { }
        if (longSide <= 0) return;
        float density = longSide / TARGET_WIDTH_DP;
        setMetrics(dm, density, res.getConfiguration().fontScale);
        try {
            DisplayMetrics app = a.getApplicationContext().getResources().getDisplayMetrics();
            if (app != dm) setMetrics(app, density, res.getConfiguration().fontScale);
        } catch (Throwable ignored) { }
        densityOverridden = true;
    }

    /** تعيد كثافة النظام فقط إذا كنا قد غيّرناها (مثلاً بعد إيقاف «وضع التلفاز» من الإعدادات). */
    private static void restoreDensity(Activity a) {
        if (!densityOverridden) return;
        DisplayMetrics sys = Resources.getSystem().getDisplayMetrics();
        float fontScale = a.getResources().getConfiguration().fontScale;
        setMetrics(a.getResources().getDisplayMetrics(), sys.density, fontScale);
        try {
            setMetrics(a.getApplicationContext().getResources().getDisplayMetrics(), sys.density, fontScale);
        } catch (Throwable ignored) { }
        densityOverridden = false;
    }

    private static void setMetrics(DisplayMetrics dm, float density, float fontScale) {
        dm.density = density;
        dm.densityDpi = Math.round(density * 160f);
        dm.scaledDensity = density * (fontScale > 0f ? fontScale : 1f);
    }
}
