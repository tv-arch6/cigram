package com.my.newproject;

import com.Cigram.subhub.EpisodeMatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bulk Upload Wizard — the PLAN (pure Java, unit-tested): what a pile of picked files is, before a single byte is
 * uploaded.
 *
 * Season: every file is classified (video / subtitle / other), its season and episode are read from its name (the
 * hub's {@link EpisodeMatcher}; a number is never guessed), then the pile is checked as a whole — the season most
 * files belong to, episodes that appear twice, holes in the run (1, 2, 4 → 3 is missing), double episodes, files of
 * another season, empty files, subtitles with no video and videos with no subtitle.
 *
 * Films: the title and the year are taken out of a release name («The.Matrix.1999.1080p.BluRay.x264.mkv» →
 * "The Matrix", 1999) so the admin can find the film in one tap; two files of the same film are flagged.
 *
 * A plan only describes. The upload itself is done by the screens that already own it (resumable multipart upload,
 * analysis, publish, undo).
 */
final class BulkPlan {

    private BulkPlan() { }

    static final class Item {
        String name = "", uri = "";
        long size = -1;
        /** video | subtitle | other */
        String kind = "other";
        int season, episode, year;
        boolean multi;
        String title = "";
        /** "" when the file is fine; otherwise why it needs the admin (Arabic). */
        String problem = "";
        /** True when the title already exists in the catalogue (films). */
        boolean exists;
    }

    static final class Plan {
        final List<Item> items = new ArrayList<Item>();
        int videos, subtitles, others, season, problems;
        long totalBytes;
        final List<Integer> missingEpisodes = new ArrayList<Integer>();
        final List<Integer> duplicateEpisodes = new ArrayList<Integer>();
        final List<String> notes = new ArrayList<String>();

        /** Nothing stands in the way of handing the files to the uploader. */
        boolean ready() {
            return videos > 0 && duplicateEpisodes.isEmpty();
        }

        String headline() {
            StringBuilder b = new StringBuilder();
            b.append(videos).append(" فيديو");
            if (subtitles > 0) b.append(" · ").append(subtitles).append(" ترجمة");
            if (others > 0) b.append(" · ").append(others).append(" ملف آخر");
            if (totalBytes > 0) b.append(" · ").append(sizeText(totalBytes));
            if (season > 0) b.append(" · الموسم ").append(season);
            return b.toString();
        }
    }

    private static final String[] VIDEO = {".mp4", ".mkv", ".m4v", ".mov", ".webm", ".ts", ".avi", ".flv", ".wmv", ".mpg", ".mpeg", ".3gp"};
    private static final String[] SUBS = {".srt", ".vtt", ".ass", ".ssa", ".sub"};

    static String kindOf(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String e : VIDEO) if (n.endsWith(e)) return "video";
        for (String e : SUBS) if (n.endsWith(e)) return "subtitle";
        return "other";
    }

    static String sizeText(long bytes) {
        if (bytes < 1024 * 1024) return Math.max(1, bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
    }

    // ------------------------------------------------------------------ a season

    static Plan season(List<Item> files) {
        Plan p = new Plan();
        TreeMap<Integer, Integer> seasonVotes = new TreeMap<Integer, Integer>();
        for (Item f : files) {
            f.kind = kindOf(f.name);
            f.problem = "";
            EpisodeMatcher.Parsed parsed = EpisodeMatcher.parse(f.name);
            f.season = parsed == null ? 0 : parsed.season;
            f.episode = parsed == null ? 0 : parsed.episode;
            f.multi = parsed != null && parsed.multi;
            if ("video".equals(f.kind) && f.season > 0) {
                Integer v = seasonVotes.get(f.season);
                seasonVotes.put(f.season, v == null ? 1 : v + 1);
            }
            p.items.add(f);
        }
        int best = 0;
        for (java.util.Map.Entry<Integer, Integer> e : seasonVotes.entrySet()) if (e.getValue() > best) { best = e.getValue(); p.season = e.getKey(); }

        TreeMap<Integer, Integer> videoOf = new TreeMap<Integer, Integer>(), subOf = new TreeMap<Integer, Integer>();
        for (Item f : p.items) {
            if ("video".equals(f.kind)) p.videos++; else if ("subtitle".equals(f.kind)) p.subtitles++; else p.others++;
            if (f.size > 0) p.totalBytes += f.size;
            if ("other".equals(f.kind)) { f.problem = "صيغة غير مدعومة: سيُتجاهل"; continue; }
            if (f.size == 0) { f.problem = "ملف فارغ"; continue; }
            if (f.episode <= 0) { f.problem = "لا يوجد رقم حلقة في الاسم: حدّده يدوياً في شاشة الرفع"; continue; }
            if (f.multi) { f.problem = "حلقة مزدوجة: تحتاج قرارك"; continue; }
            if (f.season > 0 && p.season > 0 && f.season != p.season) { f.problem = "من الموسم " + f.season + " وليس " + p.season; continue; }
            TreeMap<Integer, Integer> map = "video".equals(f.kind) ? videoOf : subOf;
            Integer seen = map.get(f.episode);
            map.put(f.episode, seen == null ? 1 : seen + 1);
        }
        for (java.util.Map.Entry<Integer, Integer> e : videoOf.entrySet()) if (e.getValue() > 1) p.duplicateEpisodes.add(e.getKey());
        for (Item f : p.items) {
            if (f.problem.length() == 0 && "video".equals(f.kind) && p.duplicateEpisodes.contains(f.episode)) f.problem = "الحلقة " + f.episode + " مكررة في الملفات";
            if (f.problem.length() == 0 && "subtitle".equals(f.kind) && !videoOf.containsKey(f.episode)) f.problem = "ترجمة بلا فيديو للحلقة " + f.episode;
            if (f.problem.length() > 0) p.problems++;
        }
        if (!videoOf.isEmpty()) {
            int first = videoOf.firstKey(), last = videoOf.lastKey();
            for (int e = first; e <= last; e++) if (!videoOf.containsKey(e)) p.missingEpisodes.add(e);
            if (first > 1 && first <= 3) for (int e = 1; e < first; e++) p.missingEpisodes.add(0, e);
            Collections.sort(p.missingEpisodes);
        }
        Collections.sort(p.items, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                int ea = a.episode <= 0 ? 100000 : a.episode, eb = b.episode <= 0 ? 100000 : b.episode;
                if (ea != eb) return ea - eb;
                int ka = "video".equals(a.kind) ? 0 : 1, kb = "video".equals(b.kind) ? 0 : 1;
                return ka != kb ? ka - kb : a.name.compareTo(b.name);
            }
        });
        if (p.videos == 0) p.notes.add("لا يوجد أي ملف فيديو بين الملفات المختارة.");
        if (!p.duplicateEpisodes.isEmpty()) p.notes.add("حلقات مكررة (أزل أحد الملفين): " + join(p.duplicateEpisodes));
        if (!p.missingEpisodes.isEmpty()) p.notes.add("حلقات ناقصة من التسلسل: " + join(p.missingEpisodes) + " — يمكن المتابعة وإضافتها لاحقاً.");
        int noSub = 0;
        for (Integer e : videoOf.keySet()) if (!subOf.containsKey(e)) noSub++;
        if (p.subtitles > 0 && noSub > 0) p.notes.add(noSub + " حلقة بلا ملف ترجمة مرفق.");
        if (seasonVotes.size() > 1) p.notes.add("الملفات من أكثر من موسم: ارفع كل موسم على حدة.");
        return p;
    }

    private static String join(List<Integer> l) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size() && i < 20; i++) { if (i > 0) b.append("، "); b.append(l.get(i)); }
        if (l.size() > 20) b.append(" …");
        return b.toString();
    }

    // ------------------------------------------------------------------ films

    private static final Pattern YEAR = Pattern.compile("(?<![0-9])(19[3-9]\\d|20[0-4]\\d)(?![0-9])");
    private static final Pattern JUNK = Pattern.compile("(?i)(?<![a-z0-9])(?:2160p|1080p|720p|480p|4k|uhd|hdr10?|hdr|dv|bluray|blu-ray|brrip|bdrip|web-?dl|webrip|hdtv|dvdrip|hdrip|x264|x265|h\\.?264|h\\.?265|hevc|aac|ac3|dts|ddp?5\\.?1|10bit|remux|extended|unrated|proper|repack|multi|dubbed|arabic|مترجم|مدبلج)(?![a-z0-9])");

    /** Title and year of a release file name. {@code yearOut[0]} = 0 when the name carries no year. */
    static String cleanTitle(String fileName, int[] yearOut) {
        String n = fileName == null ? "" : fileName;
        int dot = n.lastIndexOf('.');
        if (dot > 0 && n.length() - dot <= 6) n = n.substring(0, dot);
        n = n.replaceAll("\\[[^\\]]*\\]", " ").replace('_', ' ').replace('.', ' ');
        int cut = n.length();
        int year = 0;
        Matcher y = YEAR.matcher(n);
        int lastYearAt = -1;
        while (y.find()) { if (y.start() > 0) { lastYearAt = y.start(); year = Integer.parseInt(y.group(1)); } }
        if (lastYearAt > 0) cut = Math.min(cut, lastYearAt);
        Matcher j = JUNK.matcher(n);
        if (j.find() && j.start() > 0) cut = Math.min(cut, j.start());
        String title = n.substring(0, cut).replaceAll("[\\(\\)\\-]+\\s*$", " ").replaceAll("\\s+", " ").trim();
        if (title.length() == 0) title = n.replaceAll("\\s+", " ").trim();
        if (yearOut != null && yearOut.length > 0) yearOut[0] = year;
        return title;
    }

    static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9؀-ۿ]+", " ").trim();
    }

    /** @param catalogueTitles normalised ({@link #norm}) titles of the films already in the catalogue */
    static Plan films(List<Item> files, java.util.Set<String> catalogueTitles) {
        Plan p = new Plan();
        java.util.HashMap<String, Integer> seen = new java.util.HashMap<String, Integer>();
        for (Item f : files) {
            f.kind = kindOf(f.name);
            f.problem = "";
            int[] y = new int[1];
            f.title = cleanTitle(f.name, y);
            f.year = y[0];
            if ("video".equals(f.kind)) p.videos++; else if ("subtitle".equals(f.kind)) p.subtitles++; else p.others++;
            if (f.size > 0) p.totalBytes += f.size;
            p.items.add(f);
            if (!"video".equals(f.kind)) { if ("other".equals(f.kind)) f.problem = "صيغة غير مدعومة"; continue; }
            String key = norm(f.title) + "|" + f.year;
            Integer n = seen.get(key);
            seen.put(key, n == null ? 1 : n + 1);
            f.exists = catalogueTitles != null && catalogueTitles.contains(norm(f.title));
        }
        for (Item f : p.items) {
            if (!"video".equals(f.kind)) { if (f.problem.length() > 0) p.problems++; continue; }
            if (f.size == 0) f.problem = "ملف فارغ";
            else if (seen.get(norm(f.title) + "|" + f.year) > 1) f.problem = "ملفان لنفس الفيلم";
            else if (f.exists) f.problem = "عنوان بهذا الاسم موجود في المكتبة";
            if (f.problem.length() > 0) p.problems++;
        }
        if (p.videos == 0) p.notes.add("لا يوجد أي ملف فيديو بين الملفات المختارة.");
        return p;
    }
}
