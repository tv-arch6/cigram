package com.my.newproject;

import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Season / episode detection from file names or URLs (S03E04, s03e04, 3x04, episode-04, الحلقة 5 ...). */
final class CxNames {

    private CxNames() {}

    private static final Pattern[] FULL = new Pattern[]{
            Pattern.compile("(?:^|[/_.\\-\\s\\[(])s(?:eason)?[\\s._-]*0*(\\d{1,3})[\\s._-]*e(?:pisode)?[\\s._-]*0*(\\d{1,4})(?=$|[/_.\\-\\s\\])])", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:^|[/_.\\-\\s\\[(])0*(\\d{1,2})x0*(\\d{1,4})(?=$|[/_.\\-\\s\\])])", Pattern.CASE_INSENSITIVE),
    };
    private static final Pattern EP_ONLY = Pattern.compile(
            "(?:^|[/_.\\-\\s\\[(])(?:episode|ep|e|الحلقة|حلقة)[\\s._-]*0*(\\d{1,4})(?=$|[/_.\\-\\s\\])])", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEASON_ONLY = Pattern.compile(
            "(?:^|[/_.\\-\\s\\[(])(?:season|الموسم|موسم)[\\s._-]*0*(\\d{1,3})(?=$|[/_.\\-\\s\\])])", Pattern.CASE_INSENSITIVE);

    static String decode(String s) {
        if (s == null) return "";
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    static String fileName(String urlOrPath) {
        String s = decode(urlOrPath == null ? "" : urlOrPath.trim());
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    static boolean isSubtitle(String name) {
        String n = name == null ? "" : name.toLowerCase();
        int q = n.indexOf('?');
        if (q > 0) n = n.substring(0, q);
        return n.endsWith(".srt") || n.endsWith(".vtt");
    }

    /**
     * @return {season, episode} where season is 0 when only the episode is known, or null if nothing was detected.
     * The file name wins over folders.
     */
    static int[] parse(String urlOrName) {
        String name = fileName(urlOrName);
        int[] r = parseText(name);
        if (r != null && r[0] > 0) return r;
        String full = decode(urlOrName == null ? "" : urlOrName);
        int[] r2 = parseText(full);
        if (r != null && r2 != null && r2[0] > 0 && r2[1] == r[1]) return r2;
        return r != null ? r : r2;
    }

    private static int[] parseText(String text) {
        if (text == null || text.length() == 0) return null;
        for (int i = 0; i < FULL.length; i++) {
            Matcher m = FULL[i].matcher(text);
            if (m.find()) {
                int s = toInt(m.group(1));
                int e = toInt(m.group(2));
                if (s > 0 && e > 0) return new int[]{s, e};
            }
        }
        Matcher m = EP_ONLY.matcher(text);
        if (m.find()) {
            int e = toInt(m.group(1));
            if (e > 0) {
                Matcher sm = SEASON_ONLY.matcher(text);
                int s = sm.find() ? toInt(sm.group(1)) : 0;
                return new int[]{s, e};
            }
        }
        return null;
    }

    private static final Pattern TAGS = Pattern.compile("\\[[^\\]]*\\]|\\([^)]*\\)|\\{[^}]*\\}");
    private static final Pattern NOISE = Pattern.compile(
            "(?i)\\b(?:2160|1440|1080|720|576|480|360)[pi]\\b|\\b[xh]\\.?26[45]\\b|\\bhevc\\b|\\bav1\\b|\\b(?:8|10)[ _.-]?bit\\b|\\b\\d{3,4}x\\d{3,4}\\b|\\b(?:19|20)\\d{2}\\b|\\b\\d+(?:\\.\\d+)?\\s?(?:kbps|mbps|fps|gb|mb)\\b");
    private static final Pattern LONE_NUMBER = Pattern.compile("(?<![\\p{L}\\d])0*(\\d{1,3})(?![\\p{L}\\d])");

    /**
     * Last resort for names like "Show - 05.mkv" or "05.mp4": a single stand-alone number is taken as the episode.
     * Quality / codec / year / [group] tags are ignored first. Returns {0, episode} or null when there is no
     * number or more than one candidate (the admin then fixes it by hand). The result is a guess, show it as such.
     */
    static int[] parseLoose(String urlOrName) {
        String n = fileName(urlOrName);
        int dot = n.lastIndexOf('.');
        if (dot > 0 && n.length() - dot <= 6) n = n.substring(0, dot);
        n = TAGS.matcher(n).replaceAll(" ");
        n = NOISE.matcher(n).replaceAll(" ");
        Matcher m = LONE_NUMBER.matcher(n);
        int found = 0;
        int count = 0;
        while (m.find()) {
            int v = toInt(m.group(1));
            if (v <= 0) continue;
            if (count == 0 || v != found) count++;
            found = v;
        }
        return count == 1 ? new int[]{0, found} : null;
    }

    private static int toInt(String v) {
        try {
            return Integer.parseInt(v);
        } catch (Exception e) {
            return 0;
        }
    }

    static String code(int season, int episode) {
        return String.format(java.util.Locale.US, "S%02dE%02d", season, episode);
    }
}
