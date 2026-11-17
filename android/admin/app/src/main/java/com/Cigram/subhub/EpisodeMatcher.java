package com.Cigram.subhub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CigramSubtitleHub (admin) — which episode does a subtitle file belong to?
 *
 * Reads the season / episode from the FILE NAME only (S01E02, s1.e2, 1x02, "Season 1 Episode 2", "الموسم 1 الحلقة 2",
 * "E02" / "Ep 2" / "الحلقة 2" with the season known from the upload). A name that only resembles the title is never
 * enough: a file without a readable episode number stays UNMATCHED, a number that fits two files is AMBIGUOUS, and the
 * admin decides. Nothing here guesses from text similarity.
 */
public final class EpisodeMatcher {

    private EpisodeMatcher() {}

    public static final class Parsed {
        /** 0 = not stated in the name. */
        public final int season;
        public final int episode;
        /** True for a range ("E01-E02", "S01E01E02"): a double episode is never attached automatically. */
        public final boolean multi;

        Parsed(int season, int episode, boolean multi) {
            this.season = season;
            this.episode = episode;
            this.multi = multi;
        }
    }

    private static final Pattern[] FULL = {
            Pattern.compile("(?i)(?<![a-z0-9])s(\\d{1,2})[ ._-]*e(\\d{1,3})(?:[ ._-]*(?:-|e)[ ._-]*e?(\\d{1,3}))?(?![0-9])"),
            Pattern.compile("(?i)(?<![a-z0-9])(\\d{1,2})x(\\d{2,3})(?![0-9])"),
            Pattern.compile("(?i)season[ ._-]*(\\d{1,2})[ ._-]*(?:episode|ep)[ ._-]*(\\d{1,3})"),
            Pattern.compile("الموسم[ ._-]*(\\d{1,2})[ ._-]*الحلقة[ ._-]*(\\d{1,3})"),
    };
    private static final Pattern[] EP_ONLY = {
            Pattern.compile("(?i)(?<![a-z0-9])(?:episode|ep|e)[ ._-]*(\\d{1,3})(?![0-9])"),
            Pattern.compile("الحلقة[ ._-]*(\\d{1,3})"),
            Pattern.compile("(?i)^\\s*(\\d{1,3})\\s*\\.(?:srt|vtt)$"),
    };

    /** Season / episode written in a file name, or null when the name does not state an episode. */
    public static Parsed parse(String fileName) {
        if (fileName == null) return null;
        String n = fileName;
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        n = toAsciiDigits(n);
        for (Pattern p : FULL) {
            Matcher m = p.matcher(n);
            if (m.find()) {
                int s = Integer.parseInt(m.group(1));
                int e = Integer.parseInt(m.group(2));
                boolean multi = m.groupCount() >= 3 && m.group(3) != null && Integer.parseInt(m.group(3)) != e;
                return new Parsed(s, e, multi);
            }
        }
        // drop resolution / codec / year tokens before looking for a lone episode number
        String clean = n.replaceAll("(?i)(\\d{3,4}p|x26[45]|h\\.?26[45]|(19|20)\\d{2}|\\b\\d+bit\\b)", " ");
        for (Pattern p : EP_ONLY) {
            Matcher m = p.matcher(clean);
            if (m.find()) return new Parsed(0, Integer.parseInt(m.group(1)), false);
        }
        return null;
    }

    static String toAsciiDigits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '٠' && c <= '٩') b.append((char) ('0' + (c - '٠')));
            else if (c >= '۰' && c <= '۹') b.append((char) ('0' + (c - '۰')));
            else b.append(c);
        }
        return b.toString();
    }

    /** One catalogue episode the files can be attached to. */
    public static final class Episode {
        public final String id;
        public final int season;
        public final int episode;
        public final String title;
        public final boolean hasSubtitle;

        public Episode(String id, int season, int episode, String title, boolean hasSubtitle) {
            this.id = id;
            this.season = season;
            this.episode = episode;
            this.title = title == null ? "" : title;
            this.hasSubtitle = hasSubtitle;
        }
    }

    public enum State { MATCHED, UNMATCHED, AMBIGUOUS, WRONG_SEASON, NO_SUCH_EPISODE, MULTI_EPISODE }

    public static final class Match {
        public final String file;
        public final Parsed parsed;
        public State state;
        /** The episode the file goes to (null unless MATCHED, or set by the admin's manual correction). */
        public Episode target;
        public boolean manual;

        Match(String file, Parsed parsed) {
            this.file = file;
            this.parsed = parsed;
        }
    }

    /**
     * Matches files to the episodes of ONE season ({@code season} = the season the admin is uploading).
     * Each episode receives at most one file; a second file claiming the same episode makes both AMBIGUOUS.
     */
    public static List<Match> match(List<String> files, List<Episode> episodes, int season) {
        Map<Integer, Episode> byNumber = new HashMap<Integer, Episode>();
        for (Episode e : episodes) if (e.season == season) byNumber.put(e.episode, e);
        List<Match> out = new ArrayList<Match>(files.size());
        Map<Integer, List<Match>> claims = new HashMap<Integer, List<Match>>();
        for (String f : files) {
            Parsed p = parse(f);
            Match m = new Match(f, p);
            if (p == null) {
                m.state = State.UNMATCHED;
            } else if (p.multi) {
                m.state = State.MULTI_EPISODE;
            } else if (p.season > 0 && p.season != season) {
                m.state = State.WRONG_SEASON;
            } else {
                Episode e = byNumber.get(p.episode);
                if (e == null) {
                    m.state = State.NO_SUCH_EPISODE;
                } else {
                    m.state = State.MATCHED;
                    m.target = e;
                    List<Match> l = claims.get(p.episode);
                    if (l == null) { l = new ArrayList<Match>(2); claims.put(p.episode, l); }
                    l.add(m);
                }
            }
            out.add(m);
        }
        for (List<Match> l : claims.values()) {
            if (l.size() < 2) continue;
            for (Match m : l) { m.state = State.AMBIGUOUS; m.target = null; }
        }
        return out;
    }

    /** The admin's manual correction (or clearing it with null). */
    public static void assign(Match m, Episode e) {
        m.target = e;
        m.manual = e != null;
        m.state = e == null ? State.UNMATCHED : State.MATCHED;
    }

    /** True when two matches point to the same episode (checked again before saving). */
    public static List<String> conflicts(List<Match> matches) {
        Map<String, String> seen = new HashMap<String, String>();
        List<String> out = new ArrayList<String>();
        for (Match m : matches) {
            if (m.state != State.MATCHED || m.target == null) continue;
            String prev = seen.put(m.target.id, m.file);
            if (prev != null) out.add(String.format(Locale.US, "S%02dE%02d", m.target.season, m.target.episode));
        }
        return out;
    }

    public static String label(State s) {
        switch (s) {
            case MATCHED: return "مطابق";
            case UNMATCHED: return "بلا رقم حلقة";
            case AMBIGUOUS: return "أكثر من ملف لنفس الحلقة";
            case WRONG_SEASON: return "موسم آخر";
            case NO_SUCH_EPISODE: return "الحلقة غير موجودة";
            default: return "حلقة مزدوجة";
        }
    }
}
