package com.cigram.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal image loader for ad creatives: background download, down-sampled decode, in-memory LRU. */
final class ImageLoader {

    interface Cb { void onBitmap(Bitmap bitmap); }   // null = failed; always on the main thread

    private static final int MAX_BYTES = 6 * 1024 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>((int) Math.max(4L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 16)) {
                @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
            };

    private ImageLoader() { }

    static void load(final String url, final int maxPx, final Cb cb) {
        if (url == null || !url.toLowerCase(java.util.Locale.US).startsWith("https://")) {
            MAIN.post(new Runnable() { @Override public void run() { cb.onBitmap(null); } });
            return;
        }
        final String key = url + "@" + maxPx;
        Bitmap hit = CACHE.get(key);
        if (hit != null) { cb.onBitmap(hit); return; }
        POOL.execute(new Runnable() {
            @Override public void run() {
                final Bitmap b = fetch(url, maxPx);
                if (b != null) CACHE.put(key, b);
                MAIN.post(new Runnable() { @Override public void run() { cb.onBitmap(b); } });
            }
        });
    }

    /** Warm the cache for the next ad. */
    static void prefetch(String url, int maxPx) {
        if (url == null || url.length() == 0) return;
        load(url, maxPx, new Cb() { @Override public void onBitmap(Bitmap bitmap) { } });
    }

    private static Bitmap fetch(String url, int maxPx) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) return null;
            }
            in.close();
            byte[] data = out.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxPx || o.outHeight / (sample * 2) >= maxPx) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, d);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Throwable ignored) { }
            }
        }
    }
}
