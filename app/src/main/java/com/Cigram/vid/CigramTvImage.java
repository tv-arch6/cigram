package com.Cigram.vid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

/**
 * CIGRAM TV EDITION — poster / backdrop loader for the television UI.
 *
 * Self-contained so the TV layer never blocks the main thread: the decode and the network run on a
 * small background pool, and the finished bitmap is posted back to the ImageView only if it is still
 * the one that asked for it (so a recycled TV card never shows the wrong poster). It REUSES the app's
 * shared in-memory bitmap cache ({@link CigramStage1InitProvider#getSharedDetailsBitmap}) so a poster
 * already fetched by the phone pipeline (or an earlier TV card) is shown instantly with no new work.
 *
 * No new image library is added; nothing here runs on a phone unless the TV UI is shown.
 */
final class CigramTvImage {

    private CigramTvImage() { }

    private static final java.util.concurrent.ExecutorService POOL =
            java.util.concurrent.Executors.newFixedThreadPool(3, new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "cigram-tv-img");
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY + 1);
                    return t;
                }
            });

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** tmdb thumbnails: ask for a width that suits a TV card instead of the multi-MB "original". */
    private static String sized(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.startsWith("//")) url = "https:" + url;
        if (url.contains("image.tmdb.org/t/p/")) {
            url = url.replaceAll("/t/p/(w[0-9]+|h[0-9]+|original)/", "/t/p/w500/");
        }
        return url;
    }

    /** Loads {@code rawUrl} into {@code view}. {@code decodeTo} is the longest edge to keep (px). */
    static void load(final ImageView view, final String rawUrl, final int decodeTo) {
        if (view == null) return;
        final String url = sized(rawUrl);
        final String token = url + "#" + System.identityHashCode(view);
        view.setTag(token);

        if (url.isEmpty()) { view.setImageDrawable(null); return; }

        Bitmap cached = CigramStage1InitProvider.getSharedDetailsBitmap(url);
        if (cached != null && !cached.isRecycled()) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);

        POOL.execute(new Runnable() {
            @Override public void run() {
                // the card may have scrolled away (and been reused for another title) while this waited in
                // the queue: do no network or decode work for a picture nobody is waiting for any more
                if (!token.equals(view.getTag())) return;
                final Bitmap bitmap = fetch(view.getContext(), url, decodeTo <= 0 ? 720 : decodeTo);
                if (bitmap == null) return;
                CigramStage1InitProvider.putSharedDetailsBitmap(url, bitmap);
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        Object now = view.getTag();
                        if (token.equals(now)) view.setImageBitmap(bitmap);
                    }
                });
            }
        });
    }

    private static Bitmap fetch(Context context, String url, int maxEdge) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return null;
        java.net.HttpURLConnection connection = null;
        java.io.InputStream input = null;
        try {
            connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "image/*");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return null;

            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(64 * 1024);
            input = connection.getInputStream();
            byte[] chunk = new byte[16 * 1024];
            int read;
            long total = 0L;
            while ((read = input.read(chunk)) != -1) {
                total += read;
                if (total > 20L * 1024 * 1024) return null;   // a poster is never this big
                buffer.write(chunk, 0, read);
            }
            byte[] bytes = buffer.toByteArray();

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (largest / sample > maxEdge) sample *= 2;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;   // TV posters: half the memory, no visible loss
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Throwable ignored) {
            return null;
        } finally {
            try { if (input != null) input.close(); } catch (Throwable ignored) { }
            if (connection != null) connection.disconnect();
        }
    }
}
