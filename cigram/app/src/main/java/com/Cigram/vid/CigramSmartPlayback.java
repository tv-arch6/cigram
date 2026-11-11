package com.Cigram.vid;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

/**
 * Smart Playback Intelligence — the player adapts to the conditions of the moment, and remembers what each series
 * needs, so the viewer does not have to.
 *
 * <b>Conditions</b> ({@link #decide}): on a metered connection with the phone's data saver on, on battery saver or a
 * battery under 15 % that is not charging, or on a hot phone, the automatic quality is capped (720p / 480p) and the
 * reason is shown once. A quality the viewer picks by hand always wins, and the account's own plan ceiling still
 * applies on top.
 *
 * <b>Memory per series</b>: the playback speed chosen in a series is used again for its next episodes (a viewer who
 * watches an anime at 1.25× does not want every film at 1.25×); the speed of other titles stays the global one.
 *
 * Decisions are pure and unit-tested; reading the phone is wrapped so a failure means "no cap".
 */
final class CigramSmartPlayback {

    private CigramSmartPlayback() { }

    static final class Conditions {
        boolean metered, dataSaver, powerSave, charging;
        int batteryPct = 100;
        /** PowerManager thermal status 0..6. */
        int thermal;
    }

    static final class Decision {
        /** 0 = no cap from the conditions. */
        final int maxHeight;
        final String reason;

        Decision(int maxHeight, String reason) {
            this.maxHeight = maxHeight;
            this.reason = reason == null ? "" : reason;
        }
    }

    static Decision decide(Conditions c) {
        if (c.thermal >= 4) return new Decision(480, "الجهاز ساخن جداً: الجودة 480p لحمايته");
        if (c.thermal == 3) return new Decision(720, "الجهاز ساخن: الجودة 720p حتى يبرد");
        if (c.metered && c.dataSaver) return new Decision(720, "توفير البيانات مفعّل: الجودة 720p");
        if (c.powerSave) return new Decision(720, "وضع توفير البطارية: الجودة 720p");
        if (!c.charging && c.batteryPct > 0 && c.batteryPct < 15) return new Decision(720, "البطارية منخفضة: الجودة 720p");
        return new Decision(0, "");
    }

    static Conditions read(Context ctx) {
        Conditions c = new Conditions();
        Context app = ctx.getApplicationContext();
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                c.metered = cm.isActiveNetworkMetered();
                if (Build.VERSION.SDK_INT >= 24) c.dataSaver = cm.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
            }
        } catch (Throwable ignored) { }
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                c.powerSave = pm.isPowerSaveMode();
                if (Build.VERSION.SDK_INT >= 29) c.thermal = pm.getCurrentThermalStatus();
            }
        } catch (Throwable ignored) { }
        try {
            Intent b = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) c.batteryPct = level * 100 / scale;
                int st = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                c.charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL;
            }
        } catch (Throwable ignored) { }
        return c;
    }

    // ------------------------------------------------------------------ per-series memory

    static final String PREFS = "cigram_smart_playback";

    /** The key of a series ("" for a film: films use the global speed). */
    static String seriesKey(boolean isSeries, String seriesId, String seriesTitle) {
        if (!isSeries) return "";
        String k = seriesId != null && seriesId.trim().length() > 0 ? seriesId.trim() : seriesTitle == null ? "" : seriesTitle.trim().toLowerCase(java.util.Locale.ROOT);
        return k.length() == 0 ? "" : "speed::" + k;
    }

    /** The speed remembered for this series, or {@code fallback}. Only sane values (0.5 .. 2) are used. */
    static float speedFor(Context ctx, String key, float fallback) {
        if (key == null || key.length() == 0) return fallback;
        try {
            float v = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(key, -1f);
            return v >= 0.5f && v <= 2f ? v : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    static void rememberSpeed(Context ctx, String key, float speed) {
        if (key == null || key.length() == 0 || !(speed >= 0.5f && speed <= 2f)) return;
        try {
            SharedPreferences p = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (Math.abs(speed - 1f) < 0.01f) p.edit().remove(key).apply(); else p.edit().putFloat(key, speed).apply();
        } catch (Throwable ignored) { }
    }
}
