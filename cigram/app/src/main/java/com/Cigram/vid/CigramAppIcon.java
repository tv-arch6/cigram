package com.Cigram.vid;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

/**
 * Launcher icon choice (Cigram Plus perk). Each icon is an {@code <activity-alias>} in the manifest; exactly one is enabled.
 * Switching = enable the chosen alias, disable the others, DONT_KILL_APP (no restart). Some launchers redraw the
 * icon a few seconds later; that is the launcher, not the app.
 *
 * Rules: "classic" is free for everyone; the others need active Plus. When Plus ends {@link #enforce} puts classic back.
 * The choice is kept locally and (while Plus is active) on the account server via {@link CigramPlus#syncAppIcon}.
 */
public final class CigramAppIcon {

    public static final class Variant {
        public final String id, name, alias, drawable;
        public final boolean plus;
        Variant(String id, String name, String alias, String drawable, boolean plus) {
            this.id = id; this.name = name; this.alias = alias; this.drawable = drawable; this.plus = plus;
        }
    }

    public static final Variant[] ALL = {
        new Variant("classic", "Cigram الكلاسيكي", ".IconClassic", "cigram_logo", false),
        new Variant("gold", "Cigram VIP الذهبي", ".IconGold", "ic_launcher_gold_preview", true),
        new Variant("neon", "نيون سايبربنك", ".IconNeon", "ic_launcher_neon_preview", true),
        new Variant("minimal", "أبيض بسيط", ".IconMinimal", "ic_launcher_minimal_preview", true),
        new Variant("cinema", "سينما كلاسيكية", ".IconCinema", "ic_launcher_cinema_preview", true),
        new Variant("glass", "زجاج شفاف", ".IconGlass", "ic_launcher_glass_preview", true),
    };

    private static final String PREFS = "cigram_app_icon";

    private CigramAppIcon() { }

    public static Variant find(String id) {
        if (id != null) for (Variant v : ALL) if (v.id.equals(id)) return v;
        return ALL[0];
    }

    /** The icon the user chose on this device ("classic" by default). */
    public static String saved(Context c) {
        try {
            return find(c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("id", "classic")).id;
        } catch (Throwable t) {
            return "classic";
        }
    }

    public static boolean canUse(Context c, Variant v) {
        return v != null && (!v.plus || CigramPlus.isPlus(c));
    }

    /** The active icon according to the package manager (falls back to the saved choice). */
    public static String active(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            for (Variant v : ALL) {
                int st = pm.getComponentEnabledSetting(comp(c, v));
                if (st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return v.id;
            }
        } catch (Throwable ignored) { }
        return "classic".equals(saved(c)) ? "classic" : saved(c);
    }

    /** Switches the launcher icon now. false = not allowed (Plus needed) or the system refused. */
    public static boolean apply(Context c, String id) {
        Variant target = find(id);
        if (!canUse(c, target)) return false;
        try {
            PackageManager pm = c.getPackageManager();
            // enable the new one first so there is never a moment without a launcher entry
            pm.setComponentEnabledSetting(comp(c, target), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            for (Variant v : ALL) {
                if (v == target) continue;
                pm.setComponentEnabledSetting(comp(c, v), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
            SharedPreferences.Editor e = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            e.putString("id", target.id).apply();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static ComponentName comp(Context c, Variant v) {
        return new ComponentName(c.getPackageName(), "com.Cigram.vid" + v.alias);
    }

    /** Plus ended (or the account changed) while a Plus icon is on the home screen: back to classic. Safe to call often. */
    public static void enforce(Context c) {
        try {
            if (c == null) return;
            if (saved(c).equals("classic") && active(c).equals("classic")) return;
            Variant cur = find(active(c));
            if (cur.plus && !CigramPlus.isPlus(c)) apply(c, "classic");
        } catch (Throwable ignored) { }
    }

    /** Server told us which icon the account uses (other device): follow it while Plus is active. */
    public static void onServerChoice(Context c, String id) {
        try {
            if (id == null || id.length() == 0) return;
            Variant v = find(id);
            if (!v.id.equals(id) || !CigramPlus.isPlus(c)) return;
            if (!v.id.equals(active(c))) apply(c, v.id);
        } catch (Throwable ignored) { }
    }
}
