package com.my.newproject;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny thumbnail loader: downsamples while decoding (never keeps full-size posters in memory),
 * LRU cached, loads only for views that are actually bound.
 */
final class CgImg {

    private CgImg() {}

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) (Runtime.getRuntime().maxMemory() / 1024 / 16)) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount() / 1024;
        }
    };

    /** Result callback: ok + decoded size (for aspect-ratio hints). Always called on the main thread. */
    interface Listener {
        void onResult(boolean ok, int width, int height);
    }

    static void load(final ImageView view, final String url, final int targetPx) {
        load(view, url, targetPx, null);
    }

    static void load(final ImageView view, final String url, final int targetPx, final Listener listener) {
        if (view == null) return;
        view.setTag(url);
        view.setImageDrawable(CgUi.bg(view.getContext(), CgCfg.CARD2, 8, CgCfg.STROKE));
        if (url == null || !url.startsWith("http")) {
            if (listener != null) listener.onResult(false, 0, 0);
            return;
        }
        final String key = url + "@" + targetPx;
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            if (listener != null) listener.onResult(true, cached.getWidth(), cached.getHeight());
            return;
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = fetch(url, targetPx);
                if (bmp != null) CACHE.put(key, bmp);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        Object tag = view.getTag();
                        if (tag == null || !tag.equals(url)) return;
                        if (bmp != null) view.setImageBitmap(bmp);
                        if (listener != null) listener.onResult(bmp != null, bmp == null ? 0 : bmp.getWidth(), bmp == null ? 0 : bmp.getHeight());
                    }
                });
            }
        });
    }

    private static Bitmap fetch(String url, int targetPx) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            if (c.getResponseCode() != 200) return null;
            in = c.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > 6 * 1024 * 1024) return null; // never pull huge originals
                out.write(buf, 0, n);
            }
            byte[] data = out.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            int max = Math.max(o.outWidth, o.outHeight);
            while (max / (sample * 2) >= targetPx) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Exception e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            if (c != null) c.disconnect();
        }
    }
}
