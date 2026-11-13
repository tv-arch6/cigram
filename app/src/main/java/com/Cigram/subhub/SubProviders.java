package com.Cigram.subhub;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * CigramSubtitleHub — subtitle sources that are asked directly from the app (Arabic first, then English).
 *
 * <pre>
 *   opensubtitles   OpenSubtitles REST v1 (api.opensubtitles.com)      needs the app's Api-Key      films + episodes
 *   subdl           SubDL REST v1 (api.subdl.com)                       needs an api_key             films + episodes
 *   podnapisi       Podnapisi.NET search (XML) + zip download           no key                       films + episodes
 *   tvsubtitles     TVsubtitles.net pages (show → season → episode)     no key                       episodes only
 * </pre>
 * Every provider turns its answer into the same {@link Result}; {@link #download} returns the bytes of ONE subtitle
 * file (a zip is opened, the best .srt / .vtt inside is taken) ready for {@link SubTools#doctor} / {@link SubCodec}.
 *
 * Rules kept from the hub: a title is matched by its ids when the source understands ids (never by a guessed name
 * when an id is known), an episode never takes another episode's file ({@link #matches}), nothing is published by
 * this class, and a provider that fails is reported with its reason while the others still answer.
 *
 * Pure Java ({@link HttpURLConnection}); the transport is an interface so the parsers are unit-tested without a
 * network. Shared byte-identical between Cigram and Cigram Admin.
 *
 * VERIFICATION STATE (19.2): the OpenSubtitles and SubDL request / answer shapes follow their published REST
 * references; Podnapisi and TVsubtitles are read the way open-source subtitle tools read them. None of the four could
 * be exercised end to end from the build environment (no keys; two hosts unreachable there), so the parsers are
 * tested on recorded-shape fixtures only. A provider whose site changed fails with a clear message, it cannot
 * damage anything.
 */
public final class SubProviders {

    private SubProviders() {}

    public static final String USER_AGENT = "Cigram v19.2";
    /** Hard limits: a search answer and a downloaded archive. */
    private static final int MAX_PAGE = 3 * 1024 * 1024, MAX_ARCHIVE = 12 * 1024 * 1024;

    // ------------------------------------------------------------------ transport

    public static final class Response {
        public final int code;
        public final byte[] body;
        public final String contentType;

        public Response(int code, byte[] body, String contentType) {
            this.code = code;
            this.body = body == null ? new byte[0] : body;
            this.contentType = contentType == null ? "" : contentType;
        }

        public String text() {
            try { return new String(body, "UTF-8"); } catch (Exception e) { return ""; }
        }

        public boolean ok() { return code >= 200 && code < 300; }
    }

    public interface Http {
        /** @param body null for GET, otherwise the POST body */
        Response call(String url, Map<String, String> headers, byte[] body, String contentType, int maxBytes) throws IOException;
    }

    /** The real transport: follows redirects, bounded body, 12 s connect / 25 s read. */
    public static final Http NET = new Http() {
        @Override public Response call(String url, Map<String, String> headers, byte[] body, String contentType, int maxBytes) throws IOException {
            String current = url;
            for (int hop = 0; hop < 5; hop++) {
                HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
                try {
                    c.setConnectTimeout(12000);
                    c.setReadTimeout(25000);
                    c.setInstanceFollowRedirects(false);
                    c.setRequestProperty("User-Agent", USER_AGENT);
                    c.setRequestProperty("Accept-Language", "ar,en;q=0.8");
                    if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
                    if (body != null && hop == 0) {
                        c.setRequestMethod("POST");
                        c.setDoOutput(true);
                        if (contentType != null) c.setRequestProperty("Content-Type", contentType);
                        OutputStream os = c.getOutputStream();
                        os.write(body);
                        os.close();
                    }
                    int code = c.getResponseCode();
                    if (code >= 301 && code <= 308 && code != 304) {
                        String loc = c.getHeaderField("Location");
                        if (loc == null || loc.length() == 0) return new Response(code, null, "");
                        current = new URL(new URL(current), loc).toString();
                        if (!current.startsWith("https://") && !current.startsWith("http://")) throw new IOException("bad redirect");
                        continue;
                    }
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    byte[] data = in == null ? new byte[0] : readAll(in, maxBytes);
                    return new Response(code, data, c.getContentType());
                } finally {
                    c.disconnect();
                }
            }
            throw new IOException("too many redirects");
        }
    };

    static byte[] readAll(InputStream in, int max) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > max) throw new IOException("answer too large");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    // ------------------------------------------------------------------ query / result

    public static final class Query {
        public String title = "";
        public int year;
        /** "tt0133093" or "0133093" or "" . For an episode: the id of the SERIES. */
        public String imdbId = "";
        public String tmdbId = "";
        /** 0 = a film. */
        public int season, episode;
        /** Wanted languages in order of preference (ISO 639-1). */
        public String[] languages = {"ar", "en"};

        public boolean isEpisode() { return season > 0 && episode > 0; }

        public String imdbDigits() {
            String d = imdbId == null ? "" : imdbId.trim().replaceFirst("(?i)^tt", "").replaceFirst("^0+", "");
            return d.matches("\\d{1,12}") ? d : "";
        }

        public String imdbTt() {
            String d = imdbId == null ? "" : imdbId.trim().replaceFirst("(?i)^tt", "");
            return d.matches("\\d{1,12}") ? "tt" + (d.length() < 7 ? "0000000".substring(d.length()) + d : d) : "";
        }
    }

    public static final class Result {
        public String provider = "";
        public String providerName = "";
        /** Provider's own id of the subtitle. */
        public String id = "";
        /** ISO 639-1, lower case. */
        public String language = "";
        public String release = "";
        public String title = "";
        public int season, episode, year;
        public boolean hearingImpaired;
        public int downloads;
        public String page = "";
        /** What {@link Provider#download} needs (a file id, a path, a URL). */
        public String handle = "";
        /** Higher = better guess (language preference, id match, popularity). Filled by {@link #rank}. */
        public double score;

        public String label() {
            String lang = "ar".equals(language) ? "العربية" : "en".equals(language) ? "English" : language;
            String ep = season > 0 && episode > 0 ? String.format(Locale.US, " S%02dE%02d", season, episode) : "";
            String rel = release.length() > 0 ? release : title;
            return lang + " · " + providerName + ep + (rel.length() > 0 ? " · " + rel : "") + (hearingImpaired ? " · HI" : "");
        }
    }

    public static final class ProviderException extends Exception {
        public final String provider;
        public final int status;

        public ProviderException(String provider, String message, int status) {
            super(message);
            this.provider = provider;
            this.status = status;
        }
    }

    public interface Provider {
        String id();
        String name();
        boolean needsKey();
        /** False when this source cannot answer the query (films on an episodes-only site, no key …). */
        boolean supports(Query q);
        List<Result> search(Query q, Http http) throws Exception;
        /** One subtitle file (already taken out of its archive). */
        byte[] download(Result r, Http http) throws Exception;
    }

    // ------------------------------------------------------------------ helpers

    static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Exception e) { return ""; }
    }

    static Map<String, String> headers(String... kv) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static String unescape(String s) {
        if (s == null) return "";
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").trim();
    }

    static String stripTags(String s) {
        return unescape(s == null ? "" : s.replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " "));
    }

    /** Minimal JSON string reader for flat fields: the value of "key" as text ("" when absent). Escapes are decoded. */
    static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? jsonUnescape(m.group(1)) : "";
    }

    static long jsonNumber(String json, String key, long fallback) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : fallback;
    }

    static boolean jsonTrue(String json, String key) {
        return Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*true").matcher(json).find();
    }

    static String jsonUnescape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { b.append(c); continue; }
            char n = s.charAt(++i);
            if (n == 'n') b.append('\n');
            else if (n == 't') b.append('\t');
            else if (n == 'r') b.append('\r');
            else if (n == 'u' && i + 4 < s.length()) {
                try { b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; } catch (Exception e) { b.append('u'); }
            } else b.append(n);
        }
        return b.toString();
    }

    /** Splits a JSON array of objects (the value of "key") into its top-level objects, as raw text. */
    static List<String> jsonObjects(String json, String key) {
        List<String> out = new ArrayList<String>();
        int at = json.indexOf("\"" + key + "\"");
        if (at < 0) return out;
        int open = json.indexOf('[', at);
        if (open < 0) return out;
        int depth = 0, start = -1;
        boolean inString = false;
        for (int i = open + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') { if (depth++ == 0) start = i; }
            else if (c == '}') { if (--depth == 0 && start >= 0) { out.add(json.substring(start, i + 1)); start = -1; } }
            else if (c == ']' && depth == 0) break;
        }
        return out;
    }

    /**
     * The best subtitle file of a zip archive: .srt before .vtt, the wanted episode when the archive holds a season,
     * the largest file otherwise. Bounded (entries, sizes) and never writes to disk. Plain (non-zip) bytes come back as they are.
     */
    public static byte[] fromArchive(byte[] data, int season, int episode) throws IOException {
        if (data == null || data.length < 4) throw new IOException("empty file");
        boolean zip = data[0] == 'P' && data[1] == 'K';
        if (!zip) return data;
        ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data));
        byte[] best = null;
        int bestScore = Integer.MIN_VALUE;
        Pattern se = season > 0 && episode > 0
                ? Pattern.compile("(?i)(?:s0*" + season + "[ ._-]*e0*" + episode + "(?!\\d)|(?<!\\d)0*" + season + "x0*" + episode + "(?!\\d))") : null;
        try {
            ZipEntry e;
            int entries = 0;
            while ((e = z.getNextEntry()) != null && entries++ < 400) {
                if (e.isDirectory()) continue;
                String name = e.getName().toLowerCase(Locale.ROOT);
                boolean srt = name.endsWith(".srt"), vtt = name.endsWith(".vtt");
                if (!srt && !vtt) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                boolean tooBig = false;
                while ((n = z.read(buf)) != -1) {
                    if (out.size() + n > SubCodec.MAX_BYTES) { tooBig = true; break; }
                    out.write(buf, 0, n);
                }
                if (tooBig || out.size() == 0) continue;
                int score = (srt ? 1000 : 0) + Math.min(900, out.size() / 1024);
                if (se != null) score += se.matcher(name).find() ? 100000 : -50000;
                if (score > bestScore) { bestScore = score; best = out.toByteArray(); }
            }
        } finally {
            try { z.close(); } catch (IOException ignored) { }
        }
        if (best == null) throw new IOException("no subtitle file in the archive");
        if (se != null && bestScore < 0) throw new IOException("the archive does not contain this episode");
        return best;
    }

    /** True when a result can belong to the query (an episode result must be THE episode; a film result must not be an episode). */
    public static boolean matches(Query q, Result r) {
        if (q.isEpisode()) {
            if (r.season > 0 && r.season != q.season) return false;
            if (r.episode > 0 && r.episode != q.episode) return false;
            return true;
        }
        return r.season <= 0 && r.episode <= 0;
    }

    /** Sorts by language preference first (Arabic before English by default), then by popularity. */
    public static void rank(Query q, List<Result> results) {
        for (Result r : results) {
            int li = q.languages.length;
            for (int i = 0; i < q.languages.length; i++) if (q.languages[i].equalsIgnoreCase(r.language)) { li = i; break; }
            r.score = (q.languages.length - li) * 1000.0 + Math.min(900, Math.log10(1 + Math.max(0, r.downloads)) * 150) - (r.hearingImpaired ? 40 : 0);
        }
        Collections.sort(results, new Comparator<Result>() {
            @Override public int compare(Result a, Result b) { return Double.compare(b.score, a.score); }
        });
    }

    // ------------------------------------------------------------------ search over several providers

    public static final class Outcome {
        public final List<Result> results = new ArrayList<Result>();
        /** provider id → why it gave nothing (Arabic), only for providers that failed or were skipped. */
        public final Map<String, String> notes = new LinkedHashMap<String, String>();
    }

    /** Asks every given provider that supports the query; a failing provider becomes a note, never an exception. */
    public static Outcome searchAll(Query q, List<Provider> providers, Http http) {
        Outcome o = new Outcome();
        for (Provider p : providers) {
            if (!p.supports(q)) {
                o.notes.put(p.id(), p.name() + ": " + (p.needsKey() ? "يحتاج مفتاح API" : "لا يدعم هذا النوع من العناوين"));
                continue;
            }
            try {
                List<Result> found = p.search(q, http);
                int kept = 0;
                for (Result r : found) {
                    if (!matches(q, r)) continue;
                    boolean wanted = false;
                    for (String l : q.languages) if (l.equalsIgnoreCase(r.language)) wanted = true;
                    if (!wanted) continue;
                    o.results.add(r);
                    kept++;
                }
                if (kept == 0) o.notes.put(p.id(), p.name() + ": لا نتائج");
            } catch (ProviderException e) {
                o.notes.put(p.id(), p.name() + ": " + e.getMessage());
            } catch (IOException e) {
                o.notes.put(p.id(), p.name() + ": تعذر الاتصال");
            } catch (Exception e) {
                o.notes.put(p.id(), p.name() + ": إجابة غير مفهومة من الموقع");
            }
        }
        rank(q, o.results);
        return o;
    }

    /** The providers in the order they are asked. Keys may be "" (those providers then report "needs a key"). */
    public static List<Provider> all(String openSubtitlesKey, String subdlKey) {
        List<Provider> l = new ArrayList<Provider>();
        l.add(new OpenSubtitles(openSubtitlesKey));
        l.add(new SubDl(subdlKey));
        l.add(new Podnapisi());
        l.add(new TvSubtitles());
        return l;
    }

    // ================================================================== OpenSubtitles (REST v1)

    public static final class OpenSubtitles implements Provider {
        static final String API = "https://api.opensubtitles.com/api/v1";
        private final String key;

        public OpenSubtitles(String apiKey) { key = apiKey == null ? "" : apiKey.trim(); }

        @Override public String id() { return "opensubtitles"; }
        @Override public String name() { return "OpenSubtitles"; }
        @Override public boolean needsKey() { return true; }
        @Override public boolean supports(Query q) { return key.length() > 0 && (q.imdbDigits().length() > 0 || q.tmdbId.trim().length() > 0 || q.title.trim().length() > 0); }

        /** The documented form: parameters sorted by name, lower case, ids without leading zeroes. */
        String searchUrl(Query q) {
            java.util.TreeMap<String, String> p = new java.util.TreeMap<String, String>();
            StringBuilder langs = new StringBuilder();
            String[] sorted = q.languages.clone();
            java.util.Arrays.sort(sorted);
            for (String l : sorted) { if (langs.length() > 0) langs.append(','); langs.append(l.toLowerCase(Locale.ROOT)); }
            p.put("languages", langs.toString());
            if (q.isEpisode()) {
                p.put("episode_number", String.valueOf(q.episode));
                p.put("season_number", String.valueOf(q.season));
                if (q.imdbDigits().length() > 0) p.put("parent_imdb_id", q.imdbDigits());
                else if (q.tmdbId.trim().matches("\\d+")) p.put("parent_tmdb_id", q.tmdbId.trim());
                else p.put("query", q.title.trim().toLowerCase(Locale.ROOT));
            } else {
                if (q.imdbDigits().length() > 0) p.put("imdb_id", q.imdbDigits());
                else if (q.tmdbId.trim().matches("\\d+")) p.put("tmdb_id", q.tmdbId.trim());
                else {
                    p.put("query", q.title.trim().toLowerCase(Locale.ROOT));
                    if (q.year > 1900) p.put("year", String.valueOf(q.year));
                }
                p.put("type", "movie");
            }
            StringBuilder u = new StringBuilder(API + "/subtitles?");
            boolean first = true;
            for (Map.Entry<String, String> e : p.entrySet()) {
                if (!first) u.append('&');
                first = false;
                u.append(e.getKey()).append('=').append(enc(e.getValue()).replace("%2C", ","));
            }
            return u.toString();
        }

        @Override public List<Result> search(Query q, Http http) throws Exception {
            Response r = http.call(searchUrl(q), headers("Api-Key", key, "Accept", "application/json"), null, null, MAX_PAGE);
            if (r.code == 401 || r.code == 403) throw new ProviderException(id(), "المفتاح مرفوض (" + r.code + ")", r.code);
            if (r.code == 429) throw new ProviderException(id(), "تجاوز حد الطلبات، أعد المحاولة بعد قليل", 429);
            if (!r.ok()) throw new ProviderException(id(), "خطأ من الخادم (" + r.code + ")", r.code);
            return parse(r.text());
        }

        List<Result> parse(String json) {
            List<Result> out = new ArrayList<Result>();
            for (String o : jsonObjects(json, "data")) {
                Result x = new Result();
                x.provider = id();
                x.providerName = name();
                x.id = jsonString(o, "id");
                x.language = jsonString(o, "language").toLowerCase(Locale.ROOT);
                x.release = jsonString(o, "release");
                x.downloads = (int) jsonNumber(o, "download_count", 0);
                x.hearingImpaired = jsonTrue(o, "hearing_impaired");
                x.page = jsonString(o, "url");
                x.title = jsonString(o, "title");
                x.year = (int) jsonNumber(o, "year", 0);
                x.season = (int) jsonNumber(o, "season_number", 0);
                x.episode = (int) jsonNumber(o, "episode_number", 0);
                long file = jsonNumber(o, "file_id", -1);
                if (file < 0 || x.language.length() == 0) continue;
                x.handle = String.valueOf(file);
                out.add(x);
            }
            return out;
        }

        @Override public byte[] download(Result r, Http http) throws Exception {
            Response link = http.call(API + "/download", headers("Api-Key", key, "Accept", "application/json"),
                    ("{\"file_id\":" + Long.parseLong(r.handle) + ",\"sub_format\":\"srt\"}").getBytes("UTF-8"), "application/json", MAX_PAGE);
            if (link.code == 406) throw new ProviderException(id(), "انتهت حصة التنزيل اليومية لهذا المفتاح", 406);
            if (link.code == 401 || link.code == 403) throw new ProviderException(id(), "المفتاح مرفوض (" + link.code + ")", link.code);
            if (!link.ok()) throw new ProviderException(id(), "تعذر الحصول على رابط التنزيل (" + link.code + ")", link.code);
            String url = jsonString(link.text(), "link");
            if (!url.startsWith("https://")) throw new ProviderException(id(), "رابط تنزيل غير صالح", 0);
            Response file = http.call(url, null, null, null, SubCodec.MAX_BYTES);
            if (!file.ok()) throw new ProviderException(id(), "تعذر تنزيل الملف (" + file.code + ")", file.code);
            return fromArchive(file.body, r.season, r.episode);
        }
    }

    // ================================================================== SubDL (REST v1)

    public static final class SubDl implements Provider {
        static final String API = "https://api.subdl.com/api/v1/subtitles", FILES = "https://dl.subdl.com";
        private final String key;

        public SubDl(String apiKey) { key = apiKey == null ? "" : apiKey.trim(); }

        @Override public String id() { return "subdl"; }
        @Override public String name() { return "SubDL"; }
        @Override public boolean needsKey() { return true; }
        @Override public boolean supports(Query q) { return key.length() > 0 && (q.imdbTt().length() > 0 || q.tmdbId.trim().length() > 0 || q.title.trim().length() > 0); }

        String searchUrl(Query q) {
            StringBuilder u = new StringBuilder(API + "?api_key=" + enc(key));
            if (q.imdbTt().length() > 0) u.append("&imdb_id=").append(q.imdbTt());
            else if (q.tmdbId.trim().matches("\\d+")) u.append("&tmdb_id=").append(q.tmdbId.trim());
            else {
                u.append("&film_name=").append(enc(q.title.trim()));
                if (q.year > 1900 && !q.isEpisode()) u.append("&year=").append(q.year);
            }
            u.append("&type=").append(q.isEpisode() ? "tv" : "movie");
            if (q.isEpisode()) u.append("&season_number=").append(q.season).append("&episode_number=").append(q.episode);
            StringBuilder langs = new StringBuilder();
            for (String l : q.languages) { if (langs.length() > 0) langs.append(','); langs.append(l.toUpperCase(Locale.ROOT)); }
            u.append("&languages=").append(langs).append("&subs_per_page=30");
            return u.toString();
        }

        @Override public List<Result> search(Query q, Http http) throws Exception {
            Response r = http.call(searchUrl(q), headers("Accept", "application/json"), null, null, MAX_PAGE);
            if (r.code == 401 || r.code == 403) throw new ProviderException(id(), "المفتاح مرفوض (" + r.code + ")", r.code);
            if (r.code == 429) throw new ProviderException(id(), "تجاوز حد الطلبات، أعد المحاولة بعد قليل", 429);
            if (!r.ok()) throw new ProviderException(id(), "خطأ من الخادم (" + r.code + ")", r.code);
            String json = r.text();
            if (Pattern.compile("\"status\"\\s*:\\s*false").matcher(json).find()) {
                String why = jsonString(json, "error");
                if (why.toLowerCase(Locale.ROOT).contains("can't find") || why.toLowerCase(Locale.ROOT).contains("not found")) return new ArrayList<Result>();
                throw new ProviderException(id(), why.length() > 0 ? why : "رفض الخادم الطلب", r.code);
            }
            return parse(json, q);
        }

        List<Result> parse(String json, Query q) {
            List<Result> out = new ArrayList<Result>();
            for (String o : jsonObjects(json, "subtitles")) {
                Result x = new Result();
                x.provider = id();
                x.providerName = name();
                x.handle = jsonString(o, "url");
                x.id = x.handle;
                x.language = jsonString(o, "language").toLowerCase(Locale.ROOT);
                x.release = jsonString(o, "release_name");
                x.title = jsonString(o, "name");
                x.hearingImpaired = jsonTrue(o, "hi");
                x.season = (int) jsonNumber(o, "season", 0);
                x.episode = (int) jsonNumber(o, "episode", 0);
                // a season pack (episode 0 or a range) is accepted: the wanted episode is taken out of the archive
                long from = jsonNumber(o, "episode_from", 0), to = jsonNumber(o, "episode_end", 0);
                if (q.isEpisode() && x.episode <= 0 && from > 0 && to >= from && (q.episode < from || q.episode > to)) continue;
                if (q.isEpisode() && x.episode <= 0) { x.season = q.season; x.episode = q.episode; }
                if (x.handle.length() == 0 || x.language.length() != 2) continue;
                out.add(x);
            }
            return out;
        }

        @Override public byte[] download(Result r, Http http) throws Exception {
            if (!r.handle.startsWith("/") || r.handle.contains("..")) throw new ProviderException(id(), "مسار غير صالح", 0);
            Response file = http.call(FILES + r.handle, null, null, null, MAX_ARCHIVE);
            if (!file.ok()) throw new ProviderException(id(), "تعذر تنزيل الملف (" + file.code + ")", file.code);
            return fromArchive(file.body, r.season, r.episode);
        }
    }

    // ================================================================== Podnapisi.NET

    public static final class Podnapisi implements Provider {
        static final String SITE = "https://www.podnapisi.net/subtitles/";

        @Override public String id() { return "podnapisi"; }
        @Override public String name() { return "Podnapisi"; }
        @Override public boolean needsKey() { return false; }
        @Override public boolean supports(Query q) { return q.title.trim().length() > 0; }

        String searchUrl(Query q, String language, int page) {
            StringBuilder u = new StringBuilder(SITE + "search/old?sXML=1&sL=" + enc(language) + "&sK=" + enc(q.title.trim()));
            if (q.isEpisode()) u.append("&sTS=").append(q.season).append("&sTE=").append(q.episode);
            else if (q.year > 1900) u.append("&sY=").append(q.year);
            if (page > 1) u.append("&page=").append(page);
            return u.toString();
        }

        @Override public List<Result> search(Query q, Http http) throws Exception {
            List<Result> out = new ArrayList<Result>();
            for (String lang : q.languages) {
                Response r = http.call(searchUrl(q, lang, 1), headers("Accept", "application/xml,text/xml"), null, null, MAX_PAGE);
                if (r.code == 429) throw new ProviderException(id(), "تجاوز حد الطلبات، أعد المحاولة بعد قليل", 429);
                if (!r.ok()) throw new ProviderException(id(), "خطأ من الموقع (" + r.code + ")", r.code);
                String xml = r.text();
                if (xml.indexOf("<subtitle") < 0 && xml.indexOf("<results") < 0) throw new ProviderException(id(), "إجابة غير متوقعة من الموقع (تغيّر شكله؟)", r.code);
                out.addAll(parse(xml, lang));
            }
            return out;
        }

        private static String tag(String block, String name) {
            Matcher m = Pattern.compile("(?s)<" + name + "(?:\\s[^>]*)?>(.*?)</" + name + ">").matcher(block);
            if (!m.find()) return "";
            String v = m.group(1).trim();
            if (v.startsWith("<![CDATA[") && v.endsWith("]]>")) v = v.substring(9, v.length() - 3);
            return unescape(v);
        }

        List<Result> parse(String xml, String askedLanguage) {
            List<Result> out = new ArrayList<Result>();
            Matcher m = Pattern.compile("(?s)<subtitle>(.*?)</subtitle>").matcher(xml);
            while (m.find()) {
                String b = m.group(1);
                Result x = new Result();
                x.provider = id();
                x.providerName = name();
                x.id = tag(b, "pid");
                if (x.id.length() == 0) x.id = tag(b, "id");
                if (!x.id.matches("[A-Za-z0-9_-]{1,40}")) continue;
                String lang = tag(b, "language").toLowerCase(Locale.ROOT);
                x.language = lang.length() == 2 ? lang : askedLanguage.toLowerCase(Locale.ROOT);
                x.title = tag(b, "title");
                x.release = tag(b, "release").split("\\s+")[0];
                try { x.year = Integer.parseInt(tag(b, "year")); } catch (Exception ignored) { }
                try { x.season = Integer.parseInt(tag(b, "tvSeason")); } catch (Exception ignored) { }
                try { x.episode = Integer.parseInt(tag(b, "tvEpisode")); } catch (Exception ignored) { }
                try { x.downloads = Integer.parseInt(tag(b, "downloads")); } catch (Exception ignored) { }
                x.hearingImpaired = tag(b, "flags").indexOf('n') >= 0;
                x.page = tag(b, "url");
                x.handle = x.id;
                out.add(x);
            }
            return out;
        }

        @Override public byte[] download(Result r, Http http) throws Exception {
            if (!r.handle.matches("[A-Za-z0-9_-]{1,40}")) throw new ProviderException(id(), "معرّف غير صالح", 0);
            Response file = http.call(SITE + r.handle + "/download?container=zip", null, null, null, MAX_ARCHIVE);
            if (!file.ok()) throw new ProviderException(id(), "تعذر تنزيل الملف (" + file.code + ")", file.code);
            return fromArchive(file.body, r.season, r.episode);
        }
    }

    // ================================================================== TVsubtitles.net (episodes only)

    public static final class TvSubtitles implements Provider {
        static final String SITE = "https://www.tvsubtitles.net/";

        @Override public String id() { return "tvsubtitles"; }
        @Override public String name() { return "TVSubtitles"; }
        @Override public boolean needsKey() { return false; }
        @Override public boolean supports(Query q) { return q.isEpisode() && q.title.trim().length() > 0; }

        /** The site's flag names → ISO 639-1 where they differ. */
        static String language(String flag) {
            String f = flag.toLowerCase(Locale.ROOT);
            if (f.equals("gr")) return "el";
            if (f.equals("ua")) return "uk";
            if (f.equals("cz")) return "cs";
            if (f.equals("cn")) return "zh";
            if (f.equals("jp")) return "ja";
            if (f.equals("br")) return "pt";
            return f;
        }

        static String normalize(String s) {
            return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("\\(\\d{4}(?:-\\d{4})?\\)", " ").replaceAll("[^a-z0-9]+", " ").trim();
        }

        /** The show id whose name equals the wanted title (after normalising); 0 when the search page has none. */
        int showId(String html, String title, int year) {
            String want = normalize(title);
            int fallback = 0;
            Matcher m = Pattern.compile("(?is)<a[^>]+href=\"/?tvshow-(\\d+)\\.html\"[^>]*>(.*?)</a>").matcher(html);
            while (m.find()) {
                String name = stripTags(m.group(2));
                int id = Integer.parseInt(m.group(1));
                if (!normalize(name).equals(want)) continue;
                Matcher y = Pattern.compile("\\((\\d{4})-").matcher(name);
                if (year > 1900 && y.find() && Integer.parseInt(y.group(1)) == year) return id;
                if (fallback == 0) fallback = id;
            }
            return fallback;
        }

        /** episode number → the id of its page, from a season page. */
        Map<Integer, Integer> episodes(String html, int season) {
            Map<Integer, Integer> out = new LinkedHashMap<Integer, Integer>();
            Matcher row = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>").matcher(html);
            while (row.find()) {
                String r = row.group(1);
                Matcher num = Pattern.compile("(?<!\\d)" + season + "x0*(\\d{1,3})(?!\\d)").matcher(stripTags(r));
                Matcher link = Pattern.compile("(?i)href=\"/?episode-(\\d+)\\.html\"").matcher(r);
                if (num.find() && link.find()) out.put(Integer.parseInt(num.group(1)), Integer.parseInt(link.group(1)));
            }
            return out;
        }

        List<Result> subtitles(String html, Query q) {
            List<Result> out = new ArrayList<Result>();
            Matcher m = Pattern.compile("(?is)<a[^>]+href=\"/?subtitle-(\\d+)\\.html\"[^>]*>(.*?)</a>").matcher(html);
            while (m.find()) {
                String block = m.group(2);
                Matcher flag = Pattern.compile("(?i)flags/([a-z]{2})\\.(?:gif|png)").matcher(block);
                if (!flag.find()) continue;
                Result x = new Result();
                x.provider = id();
                x.providerName = name();
                x.id = m.group(1);
                x.handle = x.id;
                x.language = language(flag.group(1));
                Matcher rel = Pattern.compile("(?is)<h5[^>]*>(.*?)</h5>").matcher(block);
                x.release = rel.find() ? stripTags(rel.group(1)) : "";
                Matcher dl = Pattern.compile("(?is)title=\"downloaded\"[^>]*>\\s*(?:<[^>]*>\\s*)*(\\d+)").matcher(block);
                if (dl.find()) x.downloads = Integer.parseInt(dl.group(1));
                x.title = q.title;
                x.season = q.season;
                x.episode = q.episode;
                x.page = SITE + "subtitle-" + x.id + ".html";
                out.add(x);
            }
            return out;
        }

        @Override public List<Result> search(Query q, Http http) throws Exception {
            Response s = http.call(SITE + "search.php", null, ("qs=" + enc(q.title.trim())).getBytes("UTF-8"), "application/x-www-form-urlencoded", MAX_PAGE);
            if (!s.ok()) throw new ProviderException(id(), "خطأ من الموقع (" + s.code + ")", s.code);
            int show = showId(s.text(), q.title, q.year);
            if (show == 0) return new ArrayList<Result>();
            Response season = http.call(SITE + "tvshow-" + show + "-" + q.season + ".html", null, null, null, MAX_PAGE);
            if (!season.ok()) throw new ProviderException(id(), "خطأ من الموقع (" + season.code + ")", season.code);
            Integer ep = episodes(season.text(), q.season).get(q.episode);
            if (ep == null) return new ArrayList<Result>();
            Response page = http.call(SITE + "episode-" + ep + ".html", null, null, null, MAX_PAGE);
            if (!page.ok()) throw new ProviderException(id(), "خطأ من الموقع (" + page.code + ")", page.code);
            return subtitles(page.text(), q);
        }

        @Override public byte[] download(Result r, Http http) throws Exception {
            if (!r.handle.matches("\\d{1,10}")) throw new ProviderException(id(), "معرّف غير صالح", 0);
            Response file = http.call(SITE + "download-" + r.handle + ".html", null, null, null, MAX_ARCHIVE);
            if (!file.ok()) throw new ProviderException(id(), "تعذر تنزيل الملف (" + file.code + ")", file.code);
            return fromArchive(file.body, 0, 0);          // one episode per archive: its only subtitle file
        }
    }
}
