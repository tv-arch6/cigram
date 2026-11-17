package com.Cigram.vid;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * 19.6 — «الإشعار ما ينرسل للمستخدمين في الخلفية إلا والبوستر والمعلومات والفيديو كله كامل».
 *
 * Every «فيلم جديد / مسلسل جديد / حلقة جديدة» notification passes this gate first (push, the app's own catalogue check and
 * the series subscriptions alike):
 *  1. the title is in the catalogue the phone just downloaded (a push can arrive before the catalogue is readable);
 *  2. the information is complete: a name, a poster address, a watch link, and a story or year + genres;
 *  3. the poster really downloads and decodes (it is then shown big in the notification);
 *  4. the watch link really plays: an embed page (VidBolt…) is resolved to its stream by {@link CigramEmbedResolver}, the
 *     same resolver the player uses, and the stream's playlist / first bytes must answer.
 * Anything not ready yet is kept in a small persistent queue and re-checked by {@link CigramReadyJobService} with growing
 * pauses (1, 3, 8, 15, 30, 60, 120 minutes); after that the alert is dropped silently — a viewer is never sent to a page
 * without a poster or to a video that does not start.
 */
final class CigramReadyGate {

    static final class Verdict {
        boolean ready;
        String reason = "";
        Bitmap poster;
        JSONObject item;
    }

    private static final String PREFS = "cigram_ready_gate";
    private static final String KEY_QUEUE = "queue";
    static final int JOB_ID = 660905;
    private static final long[] RETRY_MIN = {1, 3, 8, 15, 30, 60, 120};
    private static final int MAX_QUEUE = 30;

    private CigramReadyGate() { }

    // ------------------------------------------------------------------ pure checks (unit-tested)

    /** "" when the information is complete, otherwise what is missing ("title", "poster", "video", "info"). */
    static String missingInfo(JSONObject item, boolean needVideo) {
        if (item == null) return "item";
        String title = text(item, "series_title", "title", "name", "title_ar", "title_en");
        if (title.length() == 0) return "title";
        String poster = text(item, "poster", "series_poster", "season_poster", "image");
        if (!isHttp(poster)) return "poster";
        if (needVideo && !isHttp(text(item, "video", "link", "video_url", "movie_url"))) return "video";
        String story = text(item, "description", "overview", "story", "plot");
        String year = text(item, "year", "release_year");
        String cat = text(item, "category", "genre", "genres");
        if (story.length() < 12 && (year.length() == 0 || cat.length() == 0)) return "info";
        return "";
    }

    static boolean isHttp(String u) {
        if (u == null) return false;
        String t = u.trim().toLowerCase(java.util.Locale.ROOT);
        return t.startsWith("https://") || t.startsWith("http://");
    }

    static String text(JSONObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            Object v = o.opt(k);
            if (v == null || v == JSONObject.NULL) continue;
            String s = String.valueOf(v).trim();
            if (s.length() > 0 && !"null".equalsIgnoreCase(s)) return s;
        }
        return "";
    }

    // ------------------------------------------------------------------ network checks (background threads only)

    /** A movie or an episode (with its series' poster / name merged in by the caller). */
    static Verdict check(Context c, JSONObject item) {
        Verdict v = new Verdict();
        v.item = item;
        if (Looper.myLooper() == Looper.getMainLooper()) { v.reason = "main_thread"; return v; }
        String miss = missingInfo(item, true);
        if (miss.length() > 0) { v.reason = miss; return v; }
        v.poster = downloadPoster(text(item, "poster", "series_poster", "season_poster", "image"));
        if (v.poster == null) { v.reason = "poster_download"; return v; }
        String video = text(item, "video", "link", "video_url", "movie_url");
        if (!videoPlays(c, video)) { v.reason = "video"; return v; }
        v.ready = true;
        return v;
    }

    /** A series header: complete itself, and its newest episode must play. */
    static Verdict checkSeries(Context c, JSONObject series) {
        Verdict v = new Verdict();
        v.item = series;
        String miss = missingInfo(series, false);
        if (miss.length() > 0) { v.reason = miss; return v; }
        JSONArray eps = CigramStage1InitProvider.cigramEpisodes(series);
        if (eps == null || eps.length() == 0) { v.reason = "no_episodes"; return v; }
        JSONObject newest = eps.optJSONObject(eps.length() - 1);
        JSONObject probe = merged(series, newest);
        Verdict e = check(c, probe);
        e.item = series;
        return e;
    }

    static JSONObject merged(JSONObject series, JSONObject episode) {
        JSONObject out = new JSONObject();
        try {
            if (series != null) {
                java.util.Iterator<String> it = series.keys();
                while (it.hasNext()) { String k = it.next(); if (!"episodes".equals(k) && !"seasons".equals(k)) out.put(k, series.opt(k)); }
            }
            if (episode != null) {
                java.util.Iterator<String> it = episode.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    Object val = episode.opt(k);
                    if (val == null || val == JSONObject.NULL || String.valueOf(val).trim().length() == 0) continue;
                    if ("poster".equals(k) && isHttp(text(series, "poster", "series_poster"))) continue;   // the series poster wins
                    out.put(k, val);
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static Bitmap downloadPoster(String raw) {
        if (!isHttp(raw)) return null;
        String url = raw.trim().replace(" ", "%20");
        if (url.contains("image.tmdb.org/t/p/")) url = url.replaceAll("/t/p/(w[0-9]+|h[0-9]+|original)/", "/t/p/w500/");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", CigramStreams.UA);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 6 * 1024 * 1024) return null;
            }
            byte[] data = out.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 1;
            while (bounds.outWidth / (o.inSampleSize * 2) >= 480) o.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable error) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The watch link plays: embed pages are resolved like the player does, then the stream must answer. */
    static boolean videoPlays(Context c, String raw) {
        if (!isHttp(raw)) return false;
        String stream = raw.trim();
        try {
            if (CigramEmbedResolver.isEmbed(stream) || CigramEmbedResolver.isKnownHost(stream)) {
                stream = CigramEmbedResolver.resolveBlocking(c, stream);
                if (stream == null || stream.length() == 0) return false;
            }
            return streamAnswers(stream);
        } catch (Throwable error) {
            return false;
        }
    }

    private static boolean streamAnswers(String stream) {
        String clean = CigramStreams.cleanUrl(stream);
        if (clean.startsWith("data:")) return clean.length() > 40;           // a master the resolver joined itself
        Map<String, String> headers = CigramStreams.headersOf(stream);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(clean).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", CigramStreams.UA);
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) c.setRequestProperty(e.getKey(), e.getValue());
            }
            c.setRequestProperty("Range", "bytes=0-4095");
            int code = c.getResponseCode();
            if (code != 200 && code != 206) return false;
            InputStream in = c.getInputStream();
            byte[] buf = new byte[4096];
            int total = 0, n;
            while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) total += n;
            if (total <= 0) return false;
            String head = new String(buf, 0, Math.min(total, 64), "UTF-8").trim();
            String lower = clean.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains(".m3u8") || head.startsWith("#EXT")) return head.startsWith("#EXTM3U");
            return !head.startsWith("<!DOCTYPE") && !head.toLowerCase(java.util.Locale.ROOT).startsWith("<html");
        } catch (Throwable error) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------------ the waiting list

    /** Keeps an alert that is not ready yet. {@code kind}: "title" (push data) or "episode". */
    static void enqueue(Context c, String kind, JSONObject payload) {
        if (c == null || payload == null) return;
        try {
            String key = kind + "|" + payload.optString("key", payload.toString().hashCode() + "");
            synchronized (CigramReadyGate.class) {
                JSONArray q = queue(c);
                JSONArray out = new JSONArray();
                boolean found = false;
                for (int i = 0; i < q.length(); i++) {
                    JSONObject e = q.optJSONObject(i);
                    if (e == null) continue;
                    if (key.equals(e.optString("key"))) found = true;
                    out.put(e);
                }
                if (!found) {
                    JSONObject e = new JSONObject();
                    e.put("key", key);
                    e.put("kind", kind);
                    e.put("payload", payload);
                    e.put("attempts", 0);
                    e.put("first_at", System.currentTimeMillis());
                    e.put("next_at", System.currentTimeMillis());
                    out.put(e);
                }
                while (out.length() > MAX_QUEUE) out.remove(0);
                save(c, out);
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramReadyGate", "enqueue failed", error);
        }
        schedule(c, 0L);
    }

    static JSONArray queue(Context c) {
        try {
            return new JSONArray(prefs(c).getString(KEY_QUEUE, "[]"));
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }

    static void save(Context c, JSONArray q) {
        prefs(c).edit().putString(KEY_QUEUE, q.toString()).apply();
    }

    /** Runs the waiting list once (the job's thread). Returns the delay before the next run, or -1 when it is empty. */
    static long runQueue(Context c) {
        JSONArray q;
        synchronized (CigramReadyGate.class) { q = queue(c); }
        if (q.length() == 0) return -1L;
        CigramStage1InitProvider.refreshCatalogBecauseChangedAndWait(c, 45);   // the newest catalogue first
        long now = System.currentTimeMillis();
        long nextDelay = Long.MAX_VALUE;
        JSONArray keep = new JSONArray();
        for (int i = 0; i < q.length(); i++) {
            JSONObject e = q.optJSONObject(i);
            if (e == null) continue;
            if (e.optLong("next_at") > now) {
                keep.put(e);
                nextDelay = Math.min(nextDelay, e.optLong("next_at") - now);
                continue;
            }
            boolean done = false;
            try {
                done = CigramStage1InitProvider.readyGateAttempt(c, e.optString("kind"), e.optJSONObject("payload"));
            } catch (Throwable error) {
                android.util.Log.w("CigramReadyGate", "attempt failed", error);
            }
            if (done) continue;
            int attempts = e.optInt("attempts") + 1;
            if (attempts > RETRY_MIN.length) {
                android.util.Log.i("CigramReadyGate", "dropped (never complete): " + e.optString("key"));
                continue;
            }
            try {
                long wait = RETRY_MIN[attempts - 1] * 60000L;
                e.put("attempts", attempts);
                e.put("next_at", now + wait);
                nextDelay = Math.min(nextDelay, wait);
            } catch (Throwable ignored) { }
            keep.put(e);
        }
        synchronized (CigramReadyGate.class) {
            // entries added while this run was busy are kept too
            JSONArray latest = queue(c);
            java.util.HashSet<String> mine = new java.util.HashSet<String>();
            for (int i = 0; i < q.length(); i++) { JSONObject e = q.optJSONObject(i); if (e != null) mine.add(e.optString("key")); }
            for (int i = 0; i < latest.length(); i++) {
                JSONObject e = latest.optJSONObject(i);
                if (e != null && !mine.contains(e.optString("key"))) { keep.put(e); nextDelay = 0L; }
            }
            save(c, keep);
        }
        return keep.length() == 0 ? -1L : Math.max(0L, nextDelay == Long.MAX_VALUE ? 60000L : nextDelay);
    }

    static void schedule(Context c, long delayMs) {
        if (c == null || Build.VERSION.SDK_INT < 21) return;
        try {
            android.app.job.JobScheduler js = (android.app.job.JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            android.app.job.JobInfo.Builder b = new android.app.job.JobInfo.Builder(JOB_ID,
                    new ComponentName(c.getApplicationContext(), CigramReadyJobService.class))
                    .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(false);
            if (delayMs > 0L) b.setMinimumLatency(delayMs);
            b.setOverrideDeadline(delayMs + 15L * 60000L);
            js.schedule(b.build());
        } catch (Throwable error) {
            android.util.Log.w("CigramReadyGate", "schedule failed", error);
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
