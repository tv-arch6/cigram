package com.Cigram.vid;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 19.6.1 «حماية التطبيق». Release builds refuse to run when the APK was tampered with:
 * <ul>
 *   <li>re-signed with another key (the release key's SHA-256 is pinned at build time, see app/build.gradle.kts);</li>
 *   <li>renamed / cloned under another package name;</li>
 *   <li>rebuilt as debuggable, or a debugger is attached;</li>
 *   <li>a hooking tool (Frida, Xposed / LSPosed, Substrate) is loaded inside Cigram's own process.</li>
 * </ul>
 * A rooted phone or a TV box is NOT blocked (many Android TV boxes ship rooted): root only matters when a tool hooks
 * Cigram itself, which the last check catches. Debug builds skip everything so development stays normal.
 */
final class CigramIntegrity {

    static final String PACKAGE = "com.Cigram.vid";
    private static final String TAG = "CigramIntegrity";
    private static final long RECHECK_MS = 30_000L;

    private static volatile String verdict;          // null = clean, otherwise the reason
    private static volatile long lastHookScan;

    private CigramIntegrity() { }

    /** Called once from CigramStage1InitProvider, before the first activity. */
    static void install(final Application app) {
        if (BuildConfig.DEBUG) return;
        verdict = evaluate(app);
        if (verdict != null) Log.w(TAG, "blocked: " + verdict);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity a) {
                long now = SystemClock.elapsedRealtime();
                if (verdict == null && now - lastHookScan > RECHECK_MS) {   // a tool attached after start-up
                    lastHookScan = now;
                    String hook = hookingTool();
                    if (hook == null && Debug.isDebuggerConnected()) hook = "debugger";
                    if (hook != null) { verdict = hook; Log.w(TAG, "blocked: " + hook); }
                }
                if (verdict != null) block(a);
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }

    /** True when the running APK is the genuine one (always true in debug builds). */
    static boolean genuine() { return BuildConfig.DEBUG || verdict == null; }

    static String evaluate(Context c) {
        try {
            if (!PACKAGE.equals(c.getPackageName())) return "package";
            if ((c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) return "debuggable";
            if (Debug.isDebuggerConnected()) return "debugger";
            String pin = pinnedSigner(c);
            if (pin.length() == 64) {
                String actual = signerSha256(c);
                if (actual != null && !actual.equalsIgnoreCase(pin)) return "signature";
            }
            lastHookScan = SystemClock.elapsedRealtime();
            return hookingTool();
        } catch (Throwable t) {
            Log.w(TAG, "check failed", t);   // a check that crashes never locks a real user out
            return null;
        }
    }

    /** The SHA-256 of the release certificate, written by Gradle as a string resource (empty when unsigned / CodeAssist). */
    private static String pinnedSigner(Context c) {
        int id = c.getResources().getIdentifier("cigram_signer_sha256", "string", c.getPackageName());
        if (id == 0) return "";
        String v = c.getString(id);
        return v == null ? "" : v.replace(":", "").trim();
    }

    @SuppressWarnings("deprecation")
    static String signerSha256(Context c) throws Exception {
        PackageManager pm = c.getPackageManager();
        Signature[] sigs;
        if (Build.VERSION.SDK_INT >= 28) {
            PackageInfo pi = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (pi.signingInfo == null) return null;
            sigs = pi.signingInfo.hasMultipleSigners()
                    ? pi.signingInfo.getApkContentsSigners()
                    : pi.signingInfo.getSigningCertificateHistory();
        } else {
            sigs = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
        }
        if (sigs == null || sigs.length == 0) return null;
        // the current signer is the last entry of the rotation history
        return hex(MessageDigest.getInstance("SHA-256").digest(sigs[sigs.length - 1].toByteArray()));
    }

    /** A hooking framework loaded into THIS process (not merely installed on the device). */
    static String hookingTool() {
        for (String cls : new String[] {
                "de.robv.android.xposed.XposedBridge",
                "de.robv.android.xposed.XC_MethodHook",
                "com.saurik.substrate.MS$2" }) {
            try {
                Class.forName(cls, false, ClassLoader.getSystemClassLoader());
                return "xposed";
            } catch (Throwable ignored) { }
            try {
                Class.forName(cls, false, CigramIntegrity.class.getClassLoader());
                return "xposed";
            } catch (Throwable ignored) { }
        }
        File maps = new File("/proc/self/maps");
        if (!maps.canRead()) return null;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(maps), 16 * 1024);
            String line;
            while ((line = r.readLine()) != null) {
                String l = line.toLowerCase(Locale.ROOT);
                if (l.contains("frida") || l.contains("gum-js-loop") || l.contains("gadget.so")) return "frida";
                if (l.contains("libsubstrate") || l.contains("libxposed") || l.contains("xposedbridge")
                        || l.contains("liblspd") || l.contains("libsandhook") || l.contains("edxp")) return "xposed";
            }
        } catch (Throwable ignored) {
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) { }
        }
        return null;
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02X", x));
        return s.toString();
    }

    /** Covers the page with «هذه النسخة غير آمنة»; the only button closes Cigram. */
    private static void block(final Activity a) {
        try {
            ViewGroup root = (ViewGroup) a.getWindow().getDecorView();
            if (root.findViewWithTag("cigram-integrity") != null) return;
            float d = a.getResources().getDisplayMetrics().density;

            FrameLayout cover = new FrameLayout(a);
            cover.setTag("cigram-integrity");
            cover.setBackgroundColor(Color.rgb(10, 10, 12));
            cover.setClickable(true);
            cover.setFocusable(true);

            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = (int) (28 * d);
            box.setPadding(pad, pad, pad, pad);

            TextView icon = new TextView(a);
            icon.setText("⚠");
            icon.setTextSize(48);
            icon.setTextColor(Color.rgb(229, 9, 20));
            icon.setGravity(Gravity.CENTER);
            box.addView(icon);

            TextView title = new TextView(a);
            title.setText("هذه النسخة غير آمنة");
            title.setTextSize(22);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(Color.WHITE);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, (int) (14 * d), 0, (int) (8 * d));
            box.addView(title);

            TextView body = new TextView(a);
            body.setText("تم تعديل التطبيق أو تشغيل أداة اختراق معه.\nحمّل Cigram من المصدر الرسمي فقط لحماية حسابك.");
            body.setTextSize(15);
            body.setTextColor(Color.rgb(170, 170, 178));
            body.setGravity(Gravity.CENTER);
            body.setLineSpacing(0, 1.25f);
            box.addView(body);

            TextView close = new TextView(a);
            close.setText("إغلاق");
            close.setTextSize(16);
            close.setTypeface(Typeface.DEFAULT_BOLD);
            close.setTextColor(Color.WHITE);
            close.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.rgb(229, 9, 20));
            bg.setCornerRadius(12 * d);
            close.setBackground(bg);
            close.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (200 * d), (int) (48 * d));
            lp.topMargin = (int) (24 * d);
            box.addView(close, lp);
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    a.finishAffinity();
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            });

            cover.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            root.addView(cover, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            close.requestFocus();   // a TV remote lands on the button
        } catch (Throwable t) {
            a.finishAffinity();
        }
    }
}
