package com.Cigram.vid;

import android.app.ActivityManager;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Surface;

/**
 * v10.65: what kind of device is this, and how strong is it?
 *
 *  - {@link #looksLikeTv}: true for TVs, TV boxes and projectors. A real Android TV says so
 *    (UI mode / Leanback / no touch screen). Many cheap boxes do NOT say so, so more rules catch
 *    them (v10.66): no touch panel actually connected (Configuration.touchscreen = NOTOUCH), a
 *    D-pad remote on a screen that is landscape by nature, firmware labelled "tv"/"mbx", or a
 *    landscape screen with no battery and no phone radio. A phone or tablet matches none of them.
 *  - {@link #tier}: LOW / MEDIUM / HIGH from RAM class, CPU cores and Android version. It is only
 *    read (diagnostics, and a switch for lighter behaviour); nothing here changes how a phone works.
 *
 * Every read is wrapped: a failure means "not a TV" / "medium", never a crash. Results are cached.
 */
final class CigramDevice {

    static final int LOW = 0, MEDIUM = 1, HIGH = 2;

    private static volatile int tvCached = -1;      // -1 not computed, 0 no, 1 yes
    private static volatile int tierCached = -1;

    private CigramDevice() { }

    /** TV, TV box or projector (cached for the life of the process). */
    static boolean looksLikeTv(Context c) {
        int cached = tvCached;
        if (cached >= 0) return cached == 1;
        boolean tv = false;
        try {
            UiModeManager um = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
            if (um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) tv = true;
        } catch (Throwable ignored) { }
        PackageManager pm = null;
        try {
            pm = c.getPackageManager();
            if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                    || pm.hasSystemFeature("android.hardware.type.television")
                    || !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
                tv = true;
            }
        } catch (Throwable ignored) { }
        if (!tv) {
            try {
                // v10.66: what the device really has plugged in, not what it declares. Android sets
                // TOUCHSCREEN_FINGER only when a real touch panel is connected, so a box driven by a
                // remote / air mouse reports NOTOUCH even when its firmware claims a touch screen.
                // Every phone and tablet has a touch panel, so they can never match this rule.
                Configuration cfg = c.getResources().getConfiguration();
                if (cfg.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH) tv = true;
                // a D-pad remote on a screen that is landscape by nature (not a phone)
                else if (cfg.navigation == Configuration.NAVIGATION_DPAD && naturalLandscape(c)
                        && !hasTouchPanel()) tv = true;
            } catch (Throwable ignored) { }
        }
        if (!tv) {
            try {
                // firmware that labels itself as a TV / box (ro.build.characteristics: "tv", "mbx", ...)
                String ch = systemProperty("ro.build.characteristics").toLowerCase(java.util.Locale.ROOT);
                if (ch.contains("tv") || ch.contains("mbx") || ch.contains("stb") || ch.contains("box")) tv = true;
            } catch (Throwable ignored) { }
        }
        if (!tv) {
            try {
                // A box that does not announce itself: landscape by nature, no battery, no phone radio.
                boolean noTelephony = pm != null && !pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
                if (noTelephony && naturalLandscape(c) && !hasBattery(c)) tv = true;
            } catch (Throwable ignored) { }
        }
        tvCached = tv ? 1 : 0;
        return tv;
    }

    /** True when a real (non-virtual) touch screen is connected right now. */
    private static boolean hasTouchPanel() {
        try {
            int[] ids = android.view.InputDevice.getDeviceIds();
            for (int id : ids) {
                android.view.InputDevice d = android.view.InputDevice.getDevice(id);
                if (d == null || d.isVirtual()) continue;
                if ((d.getSources() & android.view.InputDevice.SOURCE_TOUCHSCREEN)
                        == android.view.InputDevice.SOURCE_TOUCHSCREEN) return true;
            }
        } catch (Throwable ignored) {
            return true;   // unknown: behave like a normal device
        }
        return false;
    }

    private static String systemProperty(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class).invoke(null, key);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean hasBattery(Context c) {
        try {
            Intent i = c.getApplicationContext().registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return false;
            return i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true);
        } catch (Throwable ignored) {
            return true;   // unknown: treat as a normal device
        }
    }

    /** True when the display's natural (rotation 0) shape is wider than tall. */
    private static boolean naturalLandscape(Context c) {
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null) return false;
            DisplayMetrics real = new DisplayMetrics();
            d.getRealMetrics(real);
            int r = d.getRotation();
            boolean wide = real.widthPixels > real.heightPixels;
            return (r == Surface.ROTATION_0 || r == Surface.ROTATION_180) == wide;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** LOW / MEDIUM / HIGH. */
    static int tier(Context c) {
        int cached = tierCached;
        if (cached >= 0) return cached;
        int score = 0;
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                if (am.isLowRamDevice()) score += 3;
                int mem = am.getMemoryClass();   // MB of heap per app
                if (mem <= 64) score += 3;
                else if (mem <= 96) score += 2;
                else if (mem <= 128) score += 1;
            }
        } catch (Throwable ignored) { }
        try {
            int cores = Runtime.getRuntime().availableProcessors();
            if (cores <= 2) score += 2;
            else if (cores <= 4) score += 1;
            if (Build.VERSION.SDK_INT <= 25) score += 1;
        } catch (Throwable ignored) { }
        int t = score >= 3 ? LOW : score >= 1 ? MEDIUM : HIGH;
        tierCached = t;
        return t;
    }

    static String tierName(Context c) {
        int t = tier(c);
        return t == LOW ? "ضعيف" : t == HIGH ? "قوي" : "متوسط";
    }

    /** One line for support: model, Android, heap class, cores. */
    static String summary(Context c) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(" · Android ").append(Build.VERSION.RELEASE)
                    .append(" (API ").append(Build.VERSION.SDK_INT).append(")");
        } catch (Throwable ignored) { }
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) sb.append(" · heap ").append(am.getMemoryClass()).append("MB");
            sb.append(" · ").append(Runtime.getRuntime().availableProcessors()).append(" cores");
        } catch (Throwable ignored) { }
        try {
            sb.append(" · ").append(looksLikeTv(c) ? "TV" : "phone/tablet")
                    .append(" · tier ").append(tierName(c));
        } catch (Throwable ignored) { }
        return sb.toString();
    }
}
