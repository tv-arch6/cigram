package com.my.newproject;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * What the admin keeps in the Studio on this phone: the tools pinned as shortcuts and the tools opened last.
 * The list logic is pure (checked on the JVM); only {@link #pins}, {@link #recents} and friends touch preferences.
 */
final class StudioPrefs {

    private StudioPrefs() { }

    static final int MAX_PINS = 8;
    static final int MAX_RECENT = 6;

    /** Shown until the admin pins something of their own. */
    static final String[] DEFAULT_PINS = {"quick_add", "bulk", "requests", "inbox", "content", "notify"};

    private static final String FILE = "cigram_studio_v4";
    private static final String K_PINS = "pins";
    private static final String K_RECENT = "recent";

    // ------------------------------------------------------------------ pure list logic

    static List<String> parse(String csv) {
        ArrayList<String> out = new ArrayList<String>();
        if (csv == null) return out;
        String[] parts = csv.split(",");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.length() > 0 && !out.contains(p)) out.add(p);
        }
        return out;
    }

    static String join(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    /** Keeps only ids that are still tools of the map (a tool removed in an update must not leave a dead shortcut). */
    static List<String> sanitize(List<String> ids) {
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (StudioMap.byId(id) != null && !out.contains(id)) out.add(id);
        }
        return out;
    }

    /** Pin when absent (a full list drops its oldest pin), unpin when present. Returns a new list. */
    static List<String> togglePin(List<String> pins, String id, int max) {
        ArrayList<String> out = new ArrayList<String>(pins);
        if (out.remove(id)) return out;
        out.add(id);
        while (out.size() > max && out.size() > 0) out.remove(0);
        return out;
    }

    /** Most recent first, no duplicates, at most {@code max}. Returns a new list. */
    static List<String> pushRecent(List<String> recent, String id, int max) {
        ArrayList<String> out = new ArrayList<String>(recent);
        out.remove(id);
        out.add(0, id);
        while (out.size() > max) out.remove(out.size() - 1);
        return out;
    }

    // ------------------------------------------------------------------ storage

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** The admin's pins; the defaults while the admin has never changed them. */
    static List<String> pins(Context c) {
        try {
            SharedPreferences p = sp(c);
            if (!p.contains(K_PINS)) return sanitize(java.util.Arrays.asList(DEFAULT_PINS));
            return sanitize(parse(p.getString(K_PINS, "")));
        } catch (Throwable t) {
            return sanitize(java.util.Arrays.asList(DEFAULT_PINS));
        }
    }

    static void setPins(Context c, List<String> pins) {
        try { sp(c).edit().putString(K_PINS, join(pins)).apply(); } catch (Throwable ignored) { }
    }

    /** Toggles one pin and returns true when the tool is pinned afterwards. */
    static boolean togglePin(Context c, String id) {
        List<String> next = togglePin(pins(c), id, MAX_PINS);
        setPins(c, next);
        return next.contains(id);
    }

    static List<String> recents(Context c) {
        try { return sanitize(parse(sp(c).getString(K_RECENT, ""))); } catch (Throwable t) { return new ArrayList<String>(); }
    }

    static void noteOpened(Context c, String id) {
        try { sp(c).edit().putString(K_RECENT, join(pushRecent(recents(c), id, MAX_RECENT))).apply(); } catch (Throwable ignored) { }
    }
}
