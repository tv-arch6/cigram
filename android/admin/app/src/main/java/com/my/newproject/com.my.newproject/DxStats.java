package com.my.newproject;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Numbers behind the dashboard charts. No Android classes: tested on a plain JVM. */
final class DxStats {

    private DxStats() { }

    static final long DAY_MS = 24L * 60L * 60L * 1000L;
    /** The app and its admin work on Baghdad time (UTC+3, no daylight saving). */
    static final long BAGHDAD_OFFSET_MS = 3L * 60L * 60L * 1000L;

    /** When a title was added (ms), 0 when unknown. Understands created_at_ms, ISO / date text, and plain seconds. */
    static long addedMs(JSONObject o) {
        if (o == null) return 0L;
        Object ms = o.opt("created_at_ms");
        if (ms == null || ms == JSONObject.NULL) ms = o.opt("added_at_ms");
        if (ms instanceof Number) return norm(((Number) ms).longValue());
        try {
            if (ms != null && ms != JSONObject.NULL && String.valueOf(ms).trim().length() > 0) return norm(Long.parseLong(String.valueOf(ms).trim()));
        } catch (Exception ignored) { }
        for (String k : new String[]{"created_at", "added_at", "added", "date"}) {
            Object v = o.opt(k);
            if (v == null || v == JSONObject.NULL) continue;
            String t = String.valueOf(v).trim();
            if (t.length() == 0) continue;
            if (t.matches("\\d{9,13}")) return norm(Long.parseLong(t));
            if (t.length() >= 10) {
                try {
                    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    f.setTimeZone(TimeZone.getTimeZone("UTC"));
                    Date d = f.parse(t.substring(0, 10));
                    if (d != null) return d.getTime();
                } catch (Exception ignored) { }
            }
        }
        return 0L;
    }

    /** 10-digit values are seconds. */
    private static long norm(long v) {
        return v > 0 && v < 100000000000L ? v * 1000L : v;
    }

    /** Local (Baghdad) day number of a time. */
    static long dayOf(long ms) {
        return Math.floorDiv(ms + BAGHDAD_OFFSET_MS, DAY_MS);
    }

    /** How many items were added on each of the last {@code days} days, oldest first, today last. */
    static double[] addedPerDay(List<JSONObject> items, int days, long now) {
        double[] out = new double[days];
        if (items == null) return out;
        long today = dayOf(now);
        for (JSONObject it : items) {
            long t = addedMs(it);
            if (t <= 0) continue;
            long diff = today - dayOf(t);
            if (diff >= 0 && diff < days) out[(int) (days - 1 - diff)]++;
        }
        return out;
    }

    /** Short x-axis labels: the day of the month. */
    static String[] dayLabels(int days, long now) {
        String[] out = new String[days];
        SimpleDateFormat f = new SimpleDateFormat("d", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < days; i++) {
            long dayMs = (dayOf(now) - (days - 1 - i)) * DAY_MS;
            out[i] = f.format(new Date(dayMs));
        }
        return out;
    }

    static double sum(double[] v) {
        double s = 0;
        if (v != null) for (double x : v) s += x;
        return s;
    }

    /** "+12%" / "-5%" / "" compared with the previous period of the same length (empty when there is nothing to compare). */
    static String trend(double now, double before) {
        if (before <= 0) return "";
        long pct = Math.round((now - before) / before * 100.0);
        return (pct > 0 ? "+" : "") + pct + "%";
    }
}
