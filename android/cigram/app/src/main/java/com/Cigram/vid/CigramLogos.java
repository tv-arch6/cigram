package com.Cigram.vid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * v10.63 — channel logos, built for long lists.
 *
 *  - memory: small bitmaps (at most {@link #MAX_DIM} px) in a byte-bounded LruCache;
 *  - disk: the original bytes of every logo in cache/chlogos, so the second visit (and the
 *    first one after the home page pre-warmed them) never waits for the network;
 *  - one download per link however many rows ask for it at once;
 *  - rows that were recycled while the logo was loading are simply ignored (weak sinks).
 *
 * A logo "key" is either the picture link or "res:name" for a drawable that ships in the app.
 */
final class CigramLogos {

    /** A view that wants a logo. It checks the key itself: recycled rows ask for another one. */
    interface Sink {
        void onLogo(String key, Bitmap bitmap);
    }

    /** Longest side kept in memory. Rows show logos at 44-48 dp, i.e. about 150 px on a 3x screen. */
    private static final int MAX_DIM = 160;
    private static final long FAIL_MS = 45000L;
    private static final long DISK_MAX_AGE_MS = 21L * 24L * 60L * 60L * 1000L;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = CigramPool.fixed(3, "channels-logo");
    private static final ExecutorService PREFETCH = CigramPool.fixed(1, "channels-prefetch");

    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(
            (int) Math.max(3L * 1024L * 1024L,
                    Math.min(14L * 1024L * 1024L, Runtime.getRuntime().maxMemory() / 16L))) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value == null ? 1 : Math.max(1, value.getByteCount());
        }
    };

    /** key -> the views waiting for it (main thread only). */
    private static final HashMap<String, ArrayList<WeakReference<Sink>>> WAITING =
            new HashMap<String, ArrayList<WeakReference<Sink>>>();
    /** key -> when it failed, so a broken link is not retried on every scroll. */
    private static final HashMap<String, Long> FAILED = new HashMap<String, Long>();
    private static boolean trimmed;

    private CigramLogos() { }

    // ------------------------------------------------------------------ public

    /** The logo if it is already in memory (no work at all), else null. */
    static Bitmap peek(String key) {
        if (key == null || key.length() == 0) return null;
        Bitmap b = MEMORY.get(key);
        return b != null && !b.isRecycled() ? b : null;
    }

    /** Main thread. Calls the sink at once when the logo is in memory, later otherwise. */
    static void request(Context context, String key, Sink sink) {
        if (key == null || key.length() == 0 || sink == null) return;
        Bitmap hit = peek(key);
        if (hit != null) {
            sink.onLogo(key, hit);
            return;
        }
        Long failedAt = FAILED.get(key);
        if (failedAt != null && android.os.SystemClock.uptimeMillis() - failedAt.longValue() < FAIL_MS) return;

        ArrayList<WeakReference<Sink>> list = WAITING.get(key);
        boolean first = list == null;
        if (first) {
            list = new ArrayList<WeakReference<Sink>>();
            WAITING.put(key, list);
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).get() == sink) return;
        }
        list.add(new WeakReference<Sink>(sink));
        if (!first) return;

        final Context app = context.getApplicationContext();
        final String wanted = key;
        POOL.execute(new Runnable() {
            @Override public void run() {
                final Bitmap result = load(app, wanted);
                UI.post(new Runnable() {
                    @Override public void run() {
                        finish(wanted, result);
                    }
                });
            }
        });
    }

    /** Downloads the pictures of the first channels in the background (disk only, no decoding). */
    static void prefetch(final Context context, final List<String> keys) {
        if (context == null || keys == null || keys.isEmpty()) return;
        final Context app = context.getApplicationContext();
        final ArrayList<String> copy = new ArrayList<String>(keys);
        PREFETCH.execute(new Runnable() {
            @Override public void run() {
                try {
                    trimDisk(app);
                    for (int i = 0; i < copy.size() && i < 80; i++) {
                        String key = copy.get(i);
                        if (key == null || !key.startsWith("http")) continue;
                        File f = fileFor(app, key);
                        if (f.isFile() && f.length() > 0) continue;
                        byte[] data = download(key);
                        if (data == null) {
                            String alt = alternate(key);
                            if (alt.length() > 0) data = download(alt);
                        }
                        if (data != null) write(f, data);
                        Thread.sleep(25L);
                    }
                } catch (Throwable ignored) { }
            }
        });
    }

    /** Memory pressure: drop part of the cache (severity as in CigramStability). */
    static void trim(int severity) {
        try {
            CigramStability.shrink(MEMORY, severity);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ main thread

    private static void finish(String key, Bitmap bitmap) {
        if (bitmap != null) {
            MEMORY.put(key, bitmap);
            FAILED.remove(key);
        } else {
            FAILED.put(key, Long.valueOf(android.os.SystemClock.uptimeMillis()));
        }
        ArrayList<WeakReference<Sink>> list = WAITING.remove(key);
        if (list == null) return;
        for (int i = 0; i < list.size(); i++) {
            Sink s = list.get(i).get();
            if (s != null) s.onLogo(key, bitmap);
        }
    }

    // ------------------------------------------------------------------ worker threads

    private static Bitmap load(Context app, String key) {
        try {
            if (key.startsWith("res:")) {
                int id = app.getResources().getIdentifier(key.substring(4), "drawable", app.getPackageName());
                if (id == 0) return null;
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeResource(app.getResources(), id, bounds);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight);
                return BitmapFactory.decodeResource(app.getResources(), id, o);
            }
            File f = fileFor(app, key);
            if (f.isFile() && f.length() > 0) {
                Bitmap fromDisk = decodeFile(f);
                if (fromDisk != null) return fromDisk;
                f.delete();   // damaged file: download it again
            }
            byte[] data = download(key);
            if (data == null) {
                String alt = alternate(key);
                if (alt.length() > 0) data = download(alt);
            }
            if (data == null) return null;
            write(f, data);
            return decodeBytes(data);
        } catch (Throwable error) {
            return null;
        }
    }

    private static int sampleFor(int w, int h) {
        int side = Math.max(1, Math.min(w, h));
        int sample = 1;
        while (side / (sample * 2) >= MAX_DIM) sample *= 2;
        return sample;
    }

    private static Bitmap decodeBytes(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        BitmapFactory.Options real = new BitmapFactory.Options();
        real.inSampleSize = sampleFor(o.outWidth, o.outHeight);
        return BitmapFactory.decodeByteArray(data, 0, data.length, real);
    }

    private static Bitmap decodeFile(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        BitmapFactory.Options real = new BitmapFactory.Options();
        real.inSampleSize = sampleFor(o.outWidth, o.outHeight);
        return BitmapFactory.decodeFile(f.getAbsolutePath(), real);
    }

    private static byte[] download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "image/*");
            if (c.getResponseCode() / 100 != 2) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 3000000) return null;   // not a logo
            }
            return out.size() > 0 ? out.toByteArray() : null;
        } catch (Throwable error) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The channels used to live on another bucket host: a link that fails is tried on the other one. */
    private static String alternate(String url) {
        final String oldHost = "https://pub-eabe41d14a0644e5800344c99ef4d971.r2.dev";
        final String newHost = "https://pub-6ce52a4bbf2543ee88ad9883ae42a806.r2.dev";
        if (url.startsWith(oldHost)) return newHost + url.substring(oldHost.length());
        if (url.startsWith(newHost)) return oldHost + url.substring(newHost.length());
        return "";
    }

    // ------------------------------------------------------------------ disk

    private static File dir(Context app) {
        File d = new File(app.getCacheDir(), "chlogos");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private static File fileFor(Context app, String key) {
        String name;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (int i = 0; i < h.length; i++) {
                sb.append(Character.forDigit((h[i] >> 4) & 15, 16)).append(Character.forDigit(h[i] & 15, 16));
            }
            name = sb.toString();
        } catch (Throwable error) {
            name = Integer.toHexString(key.hashCode()) + "_" + key.length();
        }
        return new File(dir(app), name);
    }

    private static void write(File target, byte[] data) {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            out.write(data);
            out.close();
            out = null;
            if (!tmp.renameTo(target)) tmp.delete();
        } catch (Throwable error) {
            tmp.delete();
        } finally {
            if (out != null) {
                try { out.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Once per run: files nobody used for three weeks go. */
    private static synchronized void trimDisk(Context app) {
        if (trimmed) return;
        trimmed = true;
        try {
            File[] files = dir(app).listFiles();
            if (files == null) return;
            long limit = System.currentTimeMillis() - DISK_MAX_AGE_MS;
            for (int i = 0; i < files.length; i++) {
                if (files[i].lastModified() < limit) files[i].delete();
            }
        } catch (Throwable ignored) { }
    }
}
