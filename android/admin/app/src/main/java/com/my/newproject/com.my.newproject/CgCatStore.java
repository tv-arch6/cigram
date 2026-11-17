package com.my.newproject;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * Durable, crash-safe home of the saved catalogue (18.4). Pure Java (java.io + org.json), unit-tested on the JVM.
 *
 * Why it exists: the catalogue (5.7 MB of JSON) and its search index used to be ONE SharedPreferences string each. Every save rewrote
 * a ~10 MB XML file, every cold start parsed it, a power cut in the middle could leave a half written file, and nothing checked that
 * what came back was what had been saved.
 *
 * Layout (all inside {@code dir}):
 * <pre>
 *   CURRENT            "7"            name of the generation to read (replaced atomically: write CURRENT.tmp, rename)
 *   g7/display.json    the catalogue the screens read
 *   g7/index.json      the search index
 *   g7/props.json      {"gen":7,"saved_at":..,"display_len":..,"display_sha":..,"index_len":..,"index_sha":..,"s":{..},"l":{..}}
 *   g6/ ...            the PREVIOUS valid generation (kept so a damaged newest one can be recovered)
 *   aux/<name>         small side files of the sync manager (resumable sync state)
 * </pre>
 * Commit = write a temporary directory, fsync every file, verify it by reading it back (length + SHA-256), rename it to g{n+1}, flip
 * CURRENT, delete everything older than the previous generation. A crash at any step leaves the old generation readable.
 * Open = read CURRENT, verify the generation; when it is missing / damaged fall back to the newest generation that verifies.
 *
 * Guards (last line of defence, independent of the caller): an empty or syntactically unusable catalogue is never committed,
 * and a snapshot whose {@code records} property is below 1 is refused.
 */
final class CgCatStore {

    static final Charset UTF8 = Charset.forName("UTF-8");
    static final int SCHEMA = 2;

    /** What a generation holds. Immutable after construction; the maps are private copies. */
    static final class Snapshot {
        final long gen;
        final String display;
        final String index;
        final Map<String, String> strings;
        final Map<String, Long> longs;

        Snapshot(long gen, String display, String index, Map<String, String> strings, Map<String, Long> longs) {
            this.gen = gen;
            this.display = display == null ? "" : display;
            this.index = index == null ? "" : index;
            this.strings = strings == null ? new HashMap<String, String>() : new HashMap<String, String>(strings);
            this.longs = longs == null ? new HashMap<String, Long>() : new HashMap<String, Long>(longs);
        }

        String str(String key) { String v = strings.get(key); return v == null ? "" : v; }
        long num(String key) { Long v = longs.get(key); return v == null ? 0L : v.longValue(); }
    }

    static final class Result {
        final boolean ok;
        final String reason;
        final long gen;
        Result(boolean ok, String reason, long gen) { this.ok = ok; this.reason = reason; this.gen = gen; }
        static Result ok(long gen) { return new Result(true, "", gen); }
        static Result fail(String reason) { return new Result(false, reason, -1L); }
    }

    private final File dir;
    private Snapshot loaded;
    private boolean opened;
    /** Diagnostics for the report / tests. */
    int recoveredFromOlderGeneration;
    int damagedGenerationsSeen;
    int interruptedWritesCleaned;

    CgCatStore(File dir) {
        this.dir = dir;
    }

    File dir() { return dir; }

    // ------------------------------------------------------------------------------------------------ read

    /** The newest valid generation (recovering from damage when needed), or null when nothing usable is saved. Cached after the first call. */
    synchronized Snapshot load() {
        if (opened) return loaded;
        opened = true;
        loaded = openFromDisk();
        return loaded;
    }

    /** True when a usable snapshot exists. Cheap after the first load. */
    synchronized boolean hasSnapshot() {
        Snapshot s = load();
        return s != null && s.display.length() > 0;
    }

    private Snapshot openFromDisk() {
        if (!dir.isDirectory()) return null;
        cleanInterrupted();
        long current = readCurrent();
        if (current > 0) {
            Snapshot s = readGeneration(current);
            if (s != null) return s;
            damagedGenerationsSeen++;
        }
        // CURRENT missing or its generation damaged: newest generation that verifies
        long[] gens = generationsDescending();
        for (long g : gens) {
            if (g == current) continue;
            Snapshot s = readGeneration(g);
            if (s != null) {
                recoveredFromOlderGeneration++;
                try { writeAtomic(new File(dir, "CURRENT"), Long.toString(g).getBytes(UTF8)); } catch (IOException ignored) { }
                return s;
            }
            damagedGenerationsSeen++;
        }
        return null;
    }

    private long readCurrent() {
        File f = new File(dir, "CURRENT");
        if (!f.isFile() || f.length() > 32) return -1L;
        try {
            return Long.parseLong(new String(readAll(f), UTF8).trim());
        } catch (Throwable e) {
            return -1L;
        }
    }

    private long[] generationsDescending() {
        String[] names = dir.list();
        java.util.ArrayList<Long> out = new java.util.ArrayList<Long>();
        if (names != null) {
            for (String n : names) {
                if (n.length() > 1 && n.charAt(0) == 'g') {
                    try { out.add(Long.valueOf(Long.parseLong(n.substring(1)))); } catch (NumberFormatException ignored) { }
                }
            }
        }
        java.util.Collections.sort(out, java.util.Collections.reverseOrder());
        long[] r = new long[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i).longValue();
        return r;
    }

    /** Reads and VERIFIES one generation: lengths and SHA-256 of both blobs must match props.json. Null when anything is off. */
    private Snapshot readGeneration(long gen) {
        File g = new File(dir, "g" + gen);
        File props = new File(g, "props.json");
        File display = new File(g, "display.json");
        File index = new File(g, "index.json");
        try {
            if (!props.isFile() || !display.isFile()) return null;
            JSONObject p = new JSONObject(new String(readAll(props), UTF8));
            if (p.optInt("schema", 0) != SCHEMA || p.optLong("gen", -1L) != gen) return null;
            byte[] d = readAll(display);
            if (d.length != p.optLong("display_len", -1L) || !sha256(d).equals(p.optString("display_sha", ""))) return null;
            byte[] ix = index.isFile() ? readAll(index) : new byte[0];
            if (ix.length != p.optLong("index_len", -1L) || !sha256(ix).equals(p.optString("index_sha", ""))) return null;
            String text = new String(d, UTF8);
            if (!looksLikeJson(text)) return null;
            Map<String, String> s = new HashMap<String, String>();
            Map<String, Long> l = new HashMap<String, Long>();
            JSONObject so = p.optJSONObject("s");
            if (so != null) { java.util.Iterator<String> it = so.keys(); while (it.hasNext()) { String k = it.next(); s.put(k, so.optString(k, "")); } }
            JSONObject lo = p.optJSONObject("l");
            if (lo != null) { java.util.Iterator<String> it = lo.keys(); while (it.hasNext()) { String k = it.next(); l.put(k, Long.valueOf(lo.optLong(k, 0L))); } }
            return new Snapshot(gen, text, new String(ix, UTF8), s, l);
        } catch (Throwable e) {
            return null;
        }
    }

    private void cleanInterrupted() {
        String[] names = dir.list();
        if (names == null) return;
        for (String n : names) {
            if (n.startsWith("t") || n.endsWith(".tmp")) {
                deleteRecursively(new File(dir, n));
                interruptedWritesCleaned++;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ write

    /**
     * Writes a new generation atomically. Refuses an empty / unusable catalogue (the previous snapshot stays) and a snapshot whose
     * {@code records} long property is below 1.
     */
    synchronized Result commit(String display, String index, Map<String, String> strings, Map<String, Long> longs) {
        if (display == null || display.trim().length() < 2 || !looksLikeJson(display)) return Result.fail("empty_or_unusable");
        if (longs != null && longs.containsKey("records") && longs.get("records").longValue() < 1L) return Result.fail("no_records");
        Snapshot before = load();
        long next = Math.max(before == null ? 0L : before.gen, highestGeneration()) + 1L;
        File tmp = new File(dir, "t" + next);
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return Result.fail("mkdir");
            deleteRecursively(tmp);
            if (!tmp.mkdirs()) return Result.fail("mkdir");
            byte[] d = display.getBytes(UTF8);
            byte[] ix = (index == null ? "" : index).getBytes(UTF8);
            writeSynced(new File(tmp, "display.json"), d);
            writeSynced(new File(tmp, "index.json"), ix);
            JSONObject p = propsJson(next, d, ix, strings, longs);
            writeSynced(new File(tmp, "props.json"), p.toString().getBytes(UTF8));
            // verify by reading it back before it can ever become CURRENT
            if (readAll(new File(tmp, "display.json")).length != d.length || !sha256(readAll(new File(tmp, "display.json"))).equals(sha256(d))) {
                deleteRecursively(tmp);
                return Result.fail("verify");
            }
            File target = new File(dir, "g" + next);
            if (!tmp.renameTo(target)) { deleteRecursively(tmp); return Result.fail("rename"); }
            writeAtomic(new File(dir, "CURRENT"), Long.toString(next).getBytes(UTF8));
            Snapshot now = new Snapshot(next, display, index, strings, longs);
            loaded = now;
            opened = true;
            prune(next, before == null ? -1L : before.gen);
            return Result.ok(next);
        } catch (Throwable e) {
            deleteRecursively(tmp);
            return Result.fail("io:" + e.getClass().getSimpleName());
        }
    }

    /** Replaces only the small properties of the CURRENT generation (e.g. "confirmed at" after a 304): no blob is rewritten. */
    synchronized Result updateProps(Map<String, String> strings, Map<String, Long> longs) {
        Snapshot cur = load();
        if (cur == null) return Result.fail("no_snapshot");
        try {
            File g = new File(dir, "g" + cur.gen);
            JSONObject old = new JSONObject(new String(readAll(new File(g, "props.json")), UTF8));
            JSONObject p = new JSONObject();
            p.put("schema", SCHEMA);
            p.put("gen", cur.gen);
            p.put("saved_at", old.optLong("saved_at", 0L));
            p.put("display_len", old.optLong("display_len", 0L));
            p.put("display_sha", old.optString("display_sha", ""));
            p.put("index_len", old.optLong("index_len", 0L));
            p.put("index_sha", old.optString("index_sha", ""));
            p.put("s", mapJson(strings));
            p.put("l", mapJsonL(longs));
            writeAtomic(new File(g, "props.json"), p.toString().getBytes(UTF8));
            loaded = new Snapshot(cur.gen, cur.display, cur.index, strings, longs);
            return Result.ok(cur.gen);
        } catch (Throwable e) {
            return Result.fail("io:" + e.getClass().getSimpleName());
        }
    }

    /** Forgets nothing on disk; the next load() reads again (tests, and after an external change). */
    synchronized void forgetMemory() {
        loaded = null;
        opened = false;
    }

    private JSONObject propsJson(long gen, byte[] d, byte[] ix, Map<String, String> strings, Map<String, Long> longs) throws Exception {
        JSONObject p = new JSONObject();
        p.put("schema", SCHEMA);
        p.put("gen", gen);
        p.put("saved_at", System.currentTimeMillis());
        p.put("display_len", d.length);
        p.put("display_sha", sha256(d));
        p.put("index_len", ix.length);
        p.put("index_sha", sha256(ix));
        p.put("s", mapJson(strings));
        p.put("l", mapJsonL(longs));
        return p;
    }

    private static JSONObject mapJson(Map<String, String> m) throws Exception {
        JSONObject o = new JSONObject();
        if (m != null) for (Map.Entry<String, String> e : m.entrySet()) o.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
        return o;
    }

    private static JSONObject mapJsonL(Map<String, Long> m) throws Exception {
        JSONObject o = new JSONObject();
        if (m != null) for (Map.Entry<String, Long> e : m.entrySet()) o.put(e.getKey(), e.getValue() == null ? 0L : e.getValue().longValue());
        return o;
    }

    private long highestGeneration() {
        long[] g = generationsDescending();
        return g.length == 0 ? 0L : g[0];
    }

    /** Keeps the new generation and the previous VALID one; removes everything older (and unreadable leftovers). */
    private void prune(long keepNew, long keepPrev) {
        for (long g : generationsDescending()) {
            if (g == keepNew || g == keepPrev) continue;
            deleteRecursively(new File(dir, "g" + g));
        }
    }

    // ------------------------------------------------------------------------------------------------ aux (resumable sync state)

    synchronized byte[] readAux(String name) {
        try {
            File f = new File(new File(dir, "aux"), name);
            return f.isFile() ? readAll(f) : null;
        } catch (Throwable e) {
            return null;
        }
    }

    synchronized boolean writeAux(String name, byte[] data) {
        try {
            File a = new File(dir, "aux");
            if (!a.isDirectory() && !a.mkdirs()) return false;
            writeAtomic(new File(a, name), data);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    synchronized void deleteAux(String name) {
        new File(new File(dir, "aux"), name).delete();
    }

    // ------------------------------------------------------------------------------------------------ file helpers

    static boolean looksLikeJson(String s) {
        if (s == null) return false;
        int a = 0, b = s.length() - 1;
        while (a <= b && (s.charAt(a) <= ' ' || s.charAt(a) == '﻿')) a++;
        while (b >= a && s.charAt(b) <= ' ') b--;
        if (b - a < 1) return false;
        char f = s.charAt(a), l = s.charAt(b);
        return (f == '{' && l == '}') || (f == '[' && l == ']');
    }

    private static void writeSynced(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    /** tmp file + fsync + rename over the target. */
    static void writeAtomic(File target, byte[] data) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        writeSynced(tmp, data);
        if (!tmp.renameTo(target)) {
            // some file systems refuse to rename over an existing file
            target.delete();
            if (!tmp.renameTo(target)) { tmp.delete(); throw new IOException("rename failed: " + target); }
        }
    }

    static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(16, Math.min(f.length(), 64L * 1024L * 1024L)));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    static String sha256(byte[] data) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursively(k);
        f.delete();
    }
}
