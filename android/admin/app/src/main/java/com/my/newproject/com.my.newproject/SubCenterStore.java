package com.my.newproject;

import android.content.Context;
import android.content.SharedPreferences;

import com.Cigram.subhub.SubQueue;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Arabic Subtitle Center — persistent state on this phone: the translation queue (one JSON checkpoint, written
 * atomically after every change), the subtitle texts of each job, the publication history and the settings.
 *
 * Private app storage only (allowBackup=false). Nothing here talks to the network.
 */
final class SubCenterStore {

    private SubCenterStore() {}

    private static final String PREFS = "cigram_subcenter";
    private static SubQueue queue;
    private static File root;

    static synchronized SubQueue queue(Context c) {
        if (queue == null) {
            root = new File(c.getApplicationContext().getFilesDir(), "subhub");
            if (!root.isDirectory()) root.mkdirs();
            JSONObject o = null;
            try {
                byte[] b = read(new File(root, "queue.json"));
                if (b != null) o = new JSONObject(new String(b, "UTF-8"));
            } catch (Throwable ignored) { }
            queue = SubQueue.fromJson(o);
            SharedPreferences p = prefs(c);
            queue.setQuota("subdl", p.getInt("quota_subdl", 0));
            queue.setQuota("subsource", p.getInt("quota_subsource", 0));
            queue.setQuota("opensubtitles", p.getInt("quota_opensubtitles", 20));
        }
        return queue;
    }

    /** Atomic checkpoint: write to a temp file, then rename over the old one. */
    static synchronized void save(Context c) {
        if (queue == null) return;
        try {
            byte[] data = queue.toJson().toString().getBytes("UTF-8");
            File tmp = new File(root, "queue.json.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try { out.write(data); out.getFD().sync(); } finally { out.close(); }
            File dst = new File(root, "queue.json");
            if (!tmp.renameTo(dst)) {
                dst.delete();
                tmp.renameTo(dst);
            }
        } catch (Throwable e) {
            android.util.Log.w("CigramSubCenter", "checkpoint failed: " + e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ texts

    private static String safeName(String id) {
        return id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /** Stores a subtitle file of a job; returns the stored name. kind = "orig" | "ar". */
    static String putText(Context c, String contentId, String kind, String ext, byte[] data) {
        queue(c);
        File dir = new File(root, "texts");
        if (!dir.isDirectory()) dir.mkdirs();
        String name = safeName(contentId) + "." + kind + "." + ext;
        try {
            FileOutputStream out = new FileOutputStream(new File(dir, name));
            try { out.write(data); } finally { out.close(); }
            return name;
        } catch (Throwable e) {
            return "";
        }
    }

    static byte[] getText(Context c, String name) {
        if (name == null || name.length() == 0) return null;
        queue(c);
        return read(new File(new File(root, "texts"), name));
    }

    private static byte[] read(File f) {
        if (!f.isFile()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(f.length(), 8 * 1024 * 1024));
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return out.toByteArray();
            } finally {
                in.close();
            }
        } catch (Throwable e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ publication history

    static synchronized void addHistory(Context c, JSONObject entry) {
        try {
            JSONArray a = history(c);
            JSONArray out = new JSONArray();
            out.put(entry.put("at_iso", nowIso()));
            for (int i = 0; i < a.length() && i < 499; i++) out.put(a.get(i));
            FileOutputStream o = new FileOutputStream(new File(root, "history.json"));
            try { o.write(out.toString().getBytes("UTF-8")); } finally { o.close(); }
        } catch (Throwable ignored) { }
    }

    static synchronized JSONArray history(Context c) {
        queue(c);
        try {
            byte[] b = read(new File(root, "history.json"));
            return b == null ? new JSONArray() : new JSONArray(new String(b, "UTF-8"));
        } catch (Throwable e) {
            return new JSONArray();
        }
    }

    // ------------------------------------------------------------------ settings

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int concurrency(Context c) {
        return Math.max(1, Math.min(4, prefs(c).getInt("concurrency", 2)));
    }

    /** Price the admin entered from the provider's contract (per 1 000 characters). 0 = unknown. */
    static float pricePer1000(Context c) {
        return prefs(c).getFloat("price_per_1000", 0f);
    }

    static String currency(Context c) {
        return prefs(c).getString("currency", "USD");
    }

    static String nowIso() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    static String today() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    // ------------------------------------------------------------------ catalogue → jobs

    /**
     * Every film and episode of the live catalogue that has no working subtitle becomes (or stays) a job. Counts come
     * from the catalogue the Worker returned — nothing is invented. Returns the number of titles without subtitles.
     */
    static int syncFromCatalogue(Context c, CgData d) {
        SubQueue q = queue(c);
        long now = System.currentTimeMillis();
        int missing = 0;
        List<String> present = new ArrayList<String>();
        for (int i = 0; i < d.items.size(); i++) {
            JSONObject it = d.items.get(i);
            int k = CgData.kind(it);
            if (k == CgData.K_SERIES) continue;
            String id = CgData.s(it, "id");
            if (id.length() == 0) continue;
            present.add(id);
            int st = d.subState(it);
            SubQueue.Job existing = q.get(id);
            if (st == CgData.SUB_OK && existing == null) continue;     // has a subtitle and never entered the queue
            if (st != CgData.SUB_OK) missing++;
            SubQueue.Job j = new SubQueue.Job();
            j.contentId = id;
            j.kind = k == CgData.K_EPISODE ? "episode" : "movie";
            j.title = CgData.s(it, "title");
            JSONObject h = k == CgData.K_EPISODE ? d.headerOf(it) : null;
            j.seriesId = k == CgData.K_EPISODE ? CgData.s(it, "series_id") : "";
            j.seriesTitle = h != null ? CgData.s(h, "title") : "";
            JSONObject idSrc = h != null ? h : it;
            j.tmdbId = firstNonEmpty(CgData.s(idSrc, "tmdb_id"), tmdbFrom(CgData.s(idSrc, "tmdb")));
            j.imdbId = imdbFrom(firstNonEmpty(CgData.s(idSrc, "imdb_id"), CgData.s(idSrc, "imdb")));
            if (j.tmdbId.length() == 0) {
                java.util.regex.Matcher sm = java.util.regex.Pattern.compile("^series_(\\d+)$").matcher(CgData.s(idSrc, "series_id"));
                if (sm.find()) j.tmdbId = sm.group(1);
            }
            j.originalLanguage = CgData.s(idSrc, "original_language");
            j.season = CgData.n(it, "season");
            j.episode = CgData.n(it, "episode");
            q.upsert(j, now);
        }
        save(c);
        return missing;
    }

    private static String imdbFrom(String v) {
        if (v == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(tt\\d{5,12})").matcher(v);
        return m.find() ? m.group(1).toLowerCase(Locale.US) : "";
    }

    private static String tmdbFrom(String v) {
        if (v == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,9})").matcher(v);
        return m.find() ? m.group(1) : "";
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && a.length() > 0 ? a : (b == null ? "" : b);
    }
}
