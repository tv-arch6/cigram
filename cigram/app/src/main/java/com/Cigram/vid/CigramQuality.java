package com.Cigram.vid;

import android.os.Handler;
import android.os.Looper;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v10.68: video quality names and the other resolutions of one film.
 *
 * Labels: 2160 → «4K», 1440 → «2K», 1080 → «1080p», 720 → «720p», …
 *
 * A single MP4/MKV file holds ONE video resolution; the phone cannot re-encode a 4K film into
 * 1080p / 2K / 720p while it plays. The lower resolutions must exist on the server next to the
 * film (the delivered make_qualities.sh creates them from the same file with ffmpeg), or the film
 * must be an HLS / DASH stream, whose resolutions the player already lists.
 *
 * {@link #probe} looks for those copies in the background, with the names make_qualities.sh writes
 * and the usual naming styles:
 *
 *   movie.mp4          → movie_1440p.mp4, movie_1080p.mp4, movie_720p.mp4, movie_480p.mp4
 *   Movie.2160p.mp4    → Movie.1440p.mp4, Movie.1080p.mp4, Movie.720p.mp4 …
 *   movie-4k.mp4       → movie-2k.mp4, movie-1080p.mp4 …
 *   /2160/movie.mp4    → /1440/movie.mp4, /1080/movie.mp4 …
 *
 * Only copies that answer with a video (not an HTML error page) are offered. Results are cached
 * per link for the whole run, so a film is checked once.
 */
public final class CigramQuality {

    /** Heights offered in the app settings («الجودة المفضلة»); 0 = automatic / original. */
    public static final int[] PRESETS = {0, 2160, 1440, 1080, 720, 480};

    private static final int[] LADDER = {2160, 1440, 1080, 720, 480};
    private static final int TIMEOUT_MS = 4000;

    /** One playable copy of the film. */
    public static final class Variant {
        public final int height;
        public final String url;

        Variant(int height, String url) {
            this.height = height;
            this.url = url;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    /** origin link → copies found (null while the check runs). */
    private static final HashMap<String, List<Variant>> FOUND = new HashMap<String, List<Variant>>();
    private static final HashMap<String, Boolean> RUNNING = new HashMap<String, Boolean>();
    /** copy link → origin link, so switching quality never loses the original film. */
    private static final HashMap<String, String> ORIGIN = new HashMap<String, String>();

    private static final Pattern TOKEN = Pattern.compile(
            "(?i)(?<=[._\\-/ \\[(])(2160p?|4k|uhd|1440p?|2k|qhd|1080p?|fhd|720p?|480p?)(?=[._\\-/ \\])])");

    private CigramQuality() { }

    // ------------------------------------------------------------------ labels

    /** «4K», «2K», «1080p» … from a track size (width helps for wide films, e.g. 3840x1608). */
    public static String label(int height, int width) {
        int h = classify(height, width);
        if (h >= 2160) return "4K";
        if (h >= 1440) return "2K";
        if (h > 0) return h + "p";
        return "الأصلية";
    }

    public static String label(int height) {
        return label(height, 0);
    }

    /** Short tag shown next to the label: UHD / QHD / Full HD / HD / SD. */
    public static String badge(int height, int width) {
        int h = classify(height, width);
        if (h >= 2160) return "Ultra HD";
        if (h >= 1440) return "Quad HD";
        if (h >= 1080) return "Full HD";
        if (h >= 720) return "HD";
        return h > 0 ? "SD" : "";
    }

    /** «4K · Ultra HD» */
    public static String fullLabel(int height, int width) {
        String b = badge(height, width);
        return label(height, width) + (b.length() == 0 ? "" : " · " + b);
    }

    /** Settings page wording for a preferred height. */
    public static String presetLabel(int height) {
        return height <= 0 ? "تلقائي (أفضل جودة متاحة)" : fullLabel(height, 0);
    }

    /** Standard height of a track: a 3840x1600 scope film is «4K», not «1600p». */
    public static int classify(int height, int width) {
        int h = Math.max(0, height);
        if (width >= 3800 || h >= 2000) return 2160;
        if (width >= 2500 || h >= 1400) return 1440;
        if (width >= 1900 || h >= 1000) return 1080;
        if (width >= 1270 || h >= 700) return 720;
        if (width >= 850 || h >= 470) return 480;
        if (h >= 350) return 360;
        return h;
    }

    // ------------------------------------------------------------------ copies

    /** The original film link for a copy chosen earlier (or the link itself). */
    public static String originOf(String url) {
        if (url == null) return "";
        synchronized (ORIGIN) {
            String o = ORIGIN.get(url.trim());
            return o == null ? url.trim() : o;
        }
    }

    public static boolean isCopy(String url) {
        if (url == null) return false;
        synchronized (ORIGIN) {
            return ORIGIN.containsKey(url.trim());
        }
    }

    /** Copies found for the film; null while the check is still running or was never started. */
    public static List<Variant> variants(String url) {
        String origin = originOf(url);
        synchronized (FOUND) {
            List<Variant> v = FOUND.get(origin);
            return v == null ? null : new ArrayList<Variant>(v);
        }
    }

    public static boolean probing(String url) {
        synchronized (FOUND) {
            return RUNNING.containsKey(originOf(url));
        }
    }

    /**
     * Starts the background check for the film's other resolutions (once per link). Streams
     * (HLS / DASH / RTSP) are skipped: their resolutions come from the stream itself.
     */
    public static void probe(String url, final Runnable done) {
        final String origin = originOf(url);
        if (origin.length() == 0 || !worthProbing(origin)) {
            synchronized (FOUND) {
                if (!FOUND.containsKey(origin)) FOUND.put(origin, new ArrayList<Variant>());
            }
            post(done);
            return;
        }
        synchronized (FOUND) {
            if (FOUND.containsKey(origin) || RUNNING.containsKey(origin)) {
                if (!RUNNING.containsKey(origin)) post(done);
                return;
            }
            RUNNING.put(origin, Boolean.TRUE);
        }
        final LinkedHashMap<String, Integer> candidates = candidates(origin);
        final ArrayList<Variant> hits = new ArrayList<Variant>();
        if (candidates.isEmpty()) {
            finish(origin, hits, done);
            return;
        }
        final AtomicInteger left = new AtomicInteger(candidates.size());
        for (final Map.Entry<String, Integer> c : candidates.entrySet()) {
            POOL.execute(new Runnable() {
                @Override public void run() {
                    try {
                        if (exists(c.getKey())) {
                            synchronized (hits) {
                                boolean dup = false;
                                for (Variant v : hits) if (v.height == c.getValue()) dup = true;
                                if (!dup) hits.add(new Variant(c.getValue(), c.getKey()));
                            }
                        }
                    } catch (Throwable ignored) { }
                    if (left.decrementAndGet() == 0) finish(origin, hits, done);
                }
            });
        }
    }

    private static void finish(String origin, ArrayList<Variant> hits, Runnable done) {
        Collections.sort(hits, new Comparator<Variant>() {
            @Override public int compare(Variant a, Variant b) { return b.height - a.height; }
        });
        synchronized (ORIGIN) {
            for (Variant v : hits) ORIGIN.put(v.url, origin);
        }
        synchronized (FOUND) {
            FOUND.put(origin, hits);
            RUNNING.remove(origin);
        }
        post(done);
    }

    private static void post(Runnable r) {
        if (r != null) MAIN.post(r);
    }

    private static boolean worthProbing(String url) {
        String u = CigramStreams.cleanUrl(url).toLowerCase(Locale.US);
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false;
        return !(u.contains(".m3u8") || u.contains(".mpd") || u.contains("/manifest")
                || u.contains("format=m3u8") || u.contains("format=mpd"));
    }

    /** Candidate copy links → their height, highest first. */
    static LinkedHashMap<String, Integer> candidates(String link) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<String, Integer>();
        String clean = CigramStreams.cleanUrl(link);
        String headers = link.length() > clean.length() ? link.substring(clean.length()) : "";
        int q = clean.indexOf('?');
        String path = q >= 0 ? clean.substring(0, q) : clean;
        String query = q >= 0 ? clean.substring(q) : "";

        // 1) the link names its resolution: swap the token, keep its style
        // only the path after the host is read: a host such as cdn.4k.example is not a resolution
        int scheme = path.indexOf("://");
        int hostEnd = path.indexOf('/', scheme < 0 ? 0 : scheme + 3);
        Matcher m = TOKEN.matcher(path);
        int start = -1, end = -1;
        String token = null;
        while (hostEnd >= 0 && m.find(start < 0 ? hostEnd : end)) {
            start = m.start(); end = m.end(); token = m.group(1);
        }
        if (token != null) {
            int own = tokenHeight(token);
            for (int h : LADDER) {
                if (h == own) continue;
                String swapped = path.substring(0, start) + styled(token, h) + path.substring(end);
                out.put(swapped + query + headers, h);
            }
            return out;
        }

        // 2) plain name: the copies written by make_qualities.sh (name_1080p.ext)
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash) return out;
        String base = path.substring(0, dot);
        String ext = path.substring(dot);
        for (int h : LADDER) out.put(base + "_" + h + "p" + ext + query + headers, h);
        return out;
    }

    private static int tokenHeight(String t) {
        String s = t.toLowerCase(Locale.US);
        if (s.startsWith("2160") || s.equals("4k") || s.equals("uhd")) return 2160;
        if (s.startsWith("1440") || s.equals("2k") || s.equals("qhd")) return 1440;
        if (s.startsWith("1080") || s.equals("fhd")) return 1080;
        if (s.startsWith("720")) return 720;
        if (s.startsWith("480")) return 480;
        return 0;
    }

    /** Writes height h in the style of the found token (2160p → 1080p, 2160 → 1080, 4K → 2K …). */
    private static String styled(String token, int h) {
        boolean upper = !token.equals(token.toLowerCase(Locale.US));
        String s = token.toLowerCase(Locale.US);
        String out;
        if (s.equals("4k") || s.equals("2k") || s.equals("uhd") || s.equals("qhd") || s.equals("fhd")) {
            out = h == 2160 ? "4k" : h == 1440 ? "2k" : h + "p";
        } else if (s.endsWith("p")) {
            out = h + "p";
        } else {
            out = String.valueOf(h);
        }
        return upper ? out.toUpperCase(Locale.US) : out;
    }

    /** True when the link answers with a playable file (first byte only, never the whole film). */
    private static boolean exists(String link) {
        HttpURLConnection c = null;
        try {
            String clean = CigramStreams.cleanUrl(link);
            c = (HttpURLConnection) new URL(clean).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", CigramStreams.UA);
            c.setRequestProperty("Range", "bytes=0-0");
            for (Map.Entry<String, String> h : CigramStreams.headersOf(link).entrySet()) {
                c.setRequestProperty(h.getKey(), h.getValue());
            }
            int code = c.getResponseCode();
            if (code != 200 && code != 206) return false;
            String type = c.getContentType();
            if (type != null) {
                String t = type.toLowerCase(Locale.US);
                if (t.startsWith("text/") || t.contains("html") || t.contains("json")) return false;
            }
            return true;
        } catch (Throwable error) {
            return false;
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }
}
