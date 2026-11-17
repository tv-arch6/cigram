package com.Cigram.vid;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Single place that decides where Cigram keeps its downloaded media (poster cache, ...).
 *
 * Everything lives under the app cache directory, so Android can reclaim it and the user can
 * clear it from the system settings. {@link #init(Context)} also trims each folder once per
 * process (old files first, then the oldest until the folder fits its size budget), so the
 * cache can never grow without limit.
 */
public final class CigramMediaStore {
    /**
     * Poster images. The folder name is the one earlier builds already used, so an upgrade keeps
     * the posters that were downloaded before.
     */
    public static final String POSTERS = "cigram_poster_cache";

    private static final long MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final long MAX_BYTES = 120L * 1024L * 1024L;

    private static final Object LOCK = new Object();
    private static boolean trimStarted;

    private CigramMediaStore() {
    }

    /** Safe to call more than once. Never blocks: the trim runs on a background thread. */
    public static void init(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        synchronized (LOCK) {
            if (trimStarted) return;
            trimStarted = true;
        }
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    trim(dir(app, POSTERS));
                } catch (Throwable ignored) {
                    // a failed clean-up must never affect the app
                }
            }
        }, "cigram-media-trim");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    /** The folder for one kind of media; created on demand and never null. */
    public static File dir(Context context, String kind) {
        String safe = sanitize(kind);
        File base = null;
        try {
            if (context != null) base = context.getCacheDir();
        } catch (Throwable ignored) {
            base = null;
        }
        File dir = new File(base, safe);
        try {
            if (!dir.exists()) dir.mkdirs();
        } catch (Throwable ignored) {
            // the caller handles a missing folder when it tries to write
        }
        return dir;
    }

    private static String sanitize(String kind) {
        if (kind == null) return "cigram_media";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < kind.length(); i++) {
            char c = kind.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (ok) out.append(c);
        }
        return out.length() == 0 ? "cigram_media" : out.toString();
    }

    private static void trim(File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null || files.length == 0) return;

        long now = System.currentTimeMillis();
        ArrayList<File> kept = new ArrayList<File>();
        long total = 0L;
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            long age = now - f.lastModified();
            if (age > MAX_AGE_MS) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } else {
                kept.add(f);
                total += f.length();
            }
        }
        if (total <= MAX_BYTES) return;

        File[] byAge = kept.toArray(new File[0]);
        Arrays.sort(byAge, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                long x = a.lastModified();
                long y = b.lastModified();
                return x < y ? -1 : (x == y ? 0 : 1);
            }
        });
        for (File f : byAge) {
            if (total <= MAX_BYTES) break;
            long size = f.length();
            if (f.delete()) total -= size;
        }
    }
}
