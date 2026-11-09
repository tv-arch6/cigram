package com.Cigram.vid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 19.0 — Android side of poster / backdrop loading (the decisions live in {@link CigramImageQueue}).
 *
 *  - {@link #queue(Context)}: the one shared queue (3 parallel fetches) of the whole app
 *  - {@link #ensureOnDisk}: the downloader. Writes the same poster cache files as the start-up preload and the dynamic sections
 *    (so everything already cached is reused), through a temp file that is UNIQUE per download (the old code shared one
 *    {@code .part} name per URL, so two simultaneous downloads of the same poster could corrupt each other), verifies that the
 *    bytes are a decodable image before the rename, and never deletes a good cached file because of a network error
 *  - {@link #bitmaps()}: a byte-bounded memory cache shared by every Home render (the old one held 18 bitmaps and was thrown away
 *    on every render)
 *  - {@link #fadeIn}: the soft appearance of a decoded poster (no hard pop, no flicker)
 */
final class CigramImages {

    private static final int MAX_POSTER_BYTES = 24 * 1024 * 1024;

    private CigramImages() { }

    // ------------------------------------------------------------------ memory cache

    static final class BitmapCache extends android.util.LruCache<String, Bitmap> {
        BitmapCache(int maxKb) { super(maxKb); }
        @Override protected int sizeOf(String key, Bitmap value) {
            return value == null ? 1 : Math.max(1, value.getByteCount() / 1024);
        }
    }

    private static volatile BitmapCache CACHE;

    static BitmapCache bitmaps() {
        BitmapCache c = CACHE;
        if (c == null) {
            synchronized (CigramImages.class) {
                if (CACHE == null) {
                    long heapKb = Runtime.getRuntime().maxMemory() / 1024L;
                    CACHE = new BitmapCache((int) Math.max(8 * 1024L, Math.min(48 * 1024L, heapKb / 10L)));
                }
                c = CACHE;
            }
        }
        return c;
    }

    // ------------------------------------------------------------------ queue

    private static volatile CigramImageQueue QUEUE;

    static CigramImageQueue queue(final Context context) {
        CigramImageQueue q = QUEUE;
        if (q != null) return q;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        synchronized (CigramImages.class) {
            if (QUEUE == null) {
                QUEUE = new CigramImageQueue(3, new CigramImageQueue.Fetcher() {
                    @Override public boolean fetch(String url) throws Exception { return ensureOnDisk(app, url); }
                }, null, "cigram-img");
                watchNetwork(app, QUEUE);
            }
            return QUEUE;
        }
    }

    /** When a network becomes available, the images that failed in the meantime are tried again at once. */
    private static void watchNetwork(Context app, final CigramImageQueue q) {
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { q.resetBackoff(); }
            });
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ disk

    /** True when a decodable image for {@code url} is in the poster cache after this call. Blocking; never throws. */
    static boolean ensureOnDisk(Context app, String url) {
        try {
            if (url == null || url.length() == 0) return false;
            File target = CigramStage1InitProvider.posterCacheFile(app, url);
            if (target.isFile() && target.length() > 0) return true;
            if (url.startsWith("file://")) {
                File f = new File(android.net.Uri.parse(url).getPath());
                return f.isFile() && f.length() > 0;
            }
            if (!(url.startsWith("http://") || url.startsWith("https://"))) return false;
            File dir = target.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            File temp = new File(dir, target.getName() + "." + Thread.currentThread().getId() + "." + System.nanoTime() + ".part");
            HttpURLConnection c = null;
            InputStream in = null;
            FileOutputStream out = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("Accept", "image/*");
                int status = c.getResponseCode();
                if (status < 200 || status >= 300) {
                    android.util.Log.e("CigramDiag", "POSTER http=" + status + " url=" + url);
                    return false;
                }
                in = c.getInputStream();
                out = new FileOutputStream(temp, false);
                byte[] buf = new byte[16384];
                long total = 0L;
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_POSTER_BYTES) throw new java.io.IOException("poster too large");
                    out.write(buf, 0, n);
                }
                out.flush();
                out.close();
                out = null;
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(temp.getAbsolutePath(), bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new java.io.IOException("not an image");
                if (target.isFile() && target.length() > 0) return true;               // another path stored it meanwhile: keep that file
                if (!temp.renameTo(target)) {
                    java.io.FileInputStream src = new java.io.FileInputStream(temp);
                    try {
                        FileOutputStream dst = new FileOutputStream(target, false);
                        try {
                            int k;
                            while ((k = src.read(buf)) != -1) dst.write(buf, 0, k);
                        } finally { dst.close(); }
                    } finally { src.close(); }
                }
                return target.isFile() && target.length() > 0;
            } catch (Exception error) {
                android.util.Log.e("CigramDiag", "POSTER FAIL " + error.getClass().getSimpleName() + ": " + error.getMessage() + " url=" + url);
                return false;
            } finally {
                try { if (in != null) in.close(); } catch (Exception ignored) { }
                try { if (out != null) out.close(); } catch (Exception ignored) { }
                if (c != null) c.disconnect();
                if (temp.exists()) temp.delete();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ appearance

    /** A freshly decoded poster fades in over 140 ms instead of popping in; a poster already in memory is set at once by the caller. */
    static void fadeIn(ImageView view) {
        try {
            view.setAlpha(0f);
            view.animate().alpha(1f).setDuration(140L).start();
        } catch (Throwable ignored) {
            try { view.setAlpha(1f); } catch (Throwable ignored2) { }
        }
    }
}
