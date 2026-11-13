package com.Cigram.vid;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import org.json.JSONObject;

/**
 * Cigram Watch Calendar — the PLAN of a month (pure Java, unit-tested; {@link CigramWatchCalendarActivity} draws it).
 *
 * For every day of the month:
 * <ul>
 *   <li><b>شاهدت</b>: what this profile watched that day (from the watching history, one entry per title per day);</li>
 *   <li><b>جديد لك</b>: new episodes of the series this profile follows (a series is "followed" when one of its
 *   episodes was watched), added to the library that day;</li>
 *   <li><b>أضيف</b>: the films and series headers added to the library that day.</li>
 * </ul>
 * A streak counts the consecutive days up to today with something watched. Everything comes from data the app
 * already has (catalogue {@code created_at_ms}, history {@code watched_at}); nothing is invented — a library
 * without dates simply shows no additions.
 */
final class CigramWatchCalendar {

    private CigramWatchCalendar() { }

    static final class Entry {
        final String title, subtitle, id, poster;
        final long at;
        /** watched | new_episode | added */
        final String kind;

        Entry(String kind, String title, String subtitle, String id, String poster, long at) {
            this.kind = kind;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.id = id == null ? "" : id;
            this.poster = poster == null ? "" : poster;
            this.at = at;
        }
    }

    static final class Day {
        final int day;
        final List<Entry> watched = new ArrayList<Entry>();
        final List<Entry> newEpisodes = new ArrayList<Entry>();
        final List<Entry> added = new ArrayList<Entry>();

        Day(int day) { this.day = day; }

        boolean empty() { return watched.isEmpty() && newEpisodes.isEmpty() && added.isEmpty(); }
    }

    static final class Month {
        final int year, month;               // month 0..11
        final int days, firstWeekday;        // firstWeekday: 0 = Saturday … 6 = Friday (Arabic week)
        final Day[] day;
        int watchedDays, watchedTitles, newEpisodes, added;
        int streak;

        Month(int year, int month, int days, int firstWeekday) {
            this.year = year;
            this.month = month;
            this.days = days;
            this.firstWeekday = firstWeekday;
            this.day = new Day[days + 1];
            for (int d = 1; d <= days; d++) day[d] = new Day(d);
        }
    }

    static final String[] MONTHS = {"يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"};
    static final String[] WEEKDAYS = {"سبت", "أحد", "إثنين", "ثلاثاء", "أربعاء", "خميس", "جمعة"};

    /** Series key of a catalogue episode / history entry. */
    static String seriesKey(JSONObject o) {
        String sid = o.optString("series_id", "");
        if (sid.length() > 0) return sid;
        String st = o.optString("series_title", "");
        return st.length() > 0 ? "t:" + st.trim().toLowerCase(java.util.Locale.ROOT) : "";
    }

    /**
     * @param history   watching history entries ({@code watched_at}, {@code title}, {@code type}, {@code series_id} / {@code series_title}, season / episode)
     * @param catalogue library records ({@code created_at_ms}, {@code type}, {@code title}, {@code series_id} …)
     * @param seriesTitles series id → series title (to name new episodes)
     * @param today      "now" in ms (for the streak)
     */
    static Month build(int year, int month, TimeZone tz, List<JSONObject> history, List<JSONObject> catalogue,
                       Map<String, String> seriesTitles, long today) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(year, month, 1, 0, 0, 0);
        long from = c.getTimeInMillis();
        int days = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        int dow = c.get(Calendar.DAY_OF_WEEK);                       // 1 = Sunday … 7 = Saturday
        int first = dow == Calendar.SATURDAY ? 0 : dow;              // Saturday-first week
        c.add(Calendar.MONTH, 1);
        long to = c.getTimeInMillis();
        Month m = new Month(year, month, days, first);

        Set<String> followed = new HashSet<String>();
        HashMap<String, Entry> latestPerDay = new HashMap<String, Entry>();
        HashSet<Integer> watchedDays = new HashSet<Integer>();
        HashSet<String> titles = new HashSet<String>();
        Set<Long> allWatchedDays = new HashSet<Long>();
        for (JSONObject h : history) {
            long at = h.optLong("watched_at", h.optLong("updated_at", 0L));
            String sk = seriesKey(h);
            boolean isEp = isEpisode(h);
            if (isEp && sk.length() > 0) followed.add(sk);
            if (at <= 0) continue;
            allWatchedDays.add(dayIndex(at, tz));
            if (at < from || at >= to) continue;
            int d = dayOf(at, tz);
            boolean ep = isEp;
            String name = ep ? h.optString("series_title", h.optString("title")) : h.optString("title");
            String key = d + "|" + (ep ? sk : h.optString("key", name));
            String sub = ep ? episodeLabel(h) : "فيلم";
            Entry e = new Entry("watched", name, sub, h.optString("id", h.optString("key")), h.optString("poster"), at);
            Entry before = latestPerDay.get(key);
            if (before != null) {                 // one line per title per day: the latest episode of that day
                if (before.at >= at) continue;
                m.day[d].watched.remove(before);
            }
            latestPerDay.put(key, e);
            m.day[d].watched.add(e);
            watchedDays.add(d);
            titles.add(ep ? sk : name);
        }
        for (JSONObject o : catalogue) {
            long at = o.optLong("created_at_ms", 0L);
            if (at < from || at >= to) continue;
            int d = dayOf(at, tz);
            String type = o.optString("type");
            if ("episode".equals(type)) {
                String sk = seriesKey(o);
                if (!followed.contains(sk)) continue;
                String st = seriesTitles == null ? null : seriesTitles.get(sk);
                m.day[d].newEpisodes.add(new Entry("new_episode", st == null || st.length() == 0 ? o.optString("title") : st, episodeLabel(o),
                        o.optString("id"), o.optString("series_poster", o.optString("poster")), at));
                m.newEpisodes++;
            } else {
                m.day[d].added.add(new Entry("added", o.optString("title"), "series".equals(type) ? "مسلسل" : "فيلم",
                        o.optString("id"), o.optString("poster"), at));
                m.added++;
            }
        }
        Comparator<Entry> byTime = new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return a.at < b.at ? 1 : a.at > b.at ? -1 : 0; }
        };
        for (int d = 1; d <= days; d++) {
            Collections.sort(m.day[d].watched, byTime);
            Collections.sort(m.day[d].newEpisodes, byTime);
            Collections.sort(m.day[d].added, byTime);
        }
        m.watchedDays = watchedDays.size();
        m.watchedTitles = titles.size();
        // streak: consecutive days with something watched, ending today (or yesterday, if today is still empty)
        long t = dayIndex(today, tz);
        if (!allWatchedDays.contains(t)) t--;
        int streak = 0;
        while (allWatchedDays.contains(t)) { streak++; t--; }
        m.streak = streak;
        return m;
    }

    /** History rows say "episode"; the player's continue-watching rows say "series" with a season / episode. */
    static boolean isEpisode(JSONObject h) {
        String t = h.optString("type");
        return "episode".equals(t) || ("series".equals(t) && h.optString("episode", "").length() > 0);
    }

    static String episodeLabel(JSONObject o) {
        String s = o.optString("season", ""), e = o.optString("episode", "");
        if (s.length() == 0 && e.length() == 0) return "حلقة";
        return "م" + (s.length() == 0 ? "?" : s) + " · ح" + (e.length() == 0 ? "?" : e);
    }

    private static int dayOf(long at, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(at);
        return c.get(Calendar.DAY_OF_MONTH);
    }

    static long dayIndex(long at, TimeZone tz) {
        return Math.floorDiv(at + tz.getOffset(at), 86400000L);
    }

    /** series id → title, from the catalogue's series headers. */
    static Map<String, String> seriesTitles(List<JSONObject> catalogue) {
        Map<String, String> m = new HashMap<String, String>();
        for (JSONObject o : catalogue) {
            if (!"series".equals(o.optString("type"))) continue;
            String id = o.optString("id");
            if (id.length() > 0) m.put(id, o.optString("title"));
        }
        return m;
    }
}
