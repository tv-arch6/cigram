package com.Cigram.vid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Pictures of the widget: downloaded once, drawn as a card (rounded corners, 16:9) and kept on disk, so the 2-minute refresh only
 * downloads what is new. A portrait poster is shown whole in the middle over a blurred, darkened copy of itself.
 */
final class CigramWidgetImages {

    static final int W = 480;
    static final int H = 270;
    private static final float RADIUS = 26f;
    private static final int KEEP_FILES = 40;

    private CigramWidgetImages() { }

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "cigram_widget_cards");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File fileFor(Context c, String url) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(("v2|" + url).getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte x : h) sb.append(String.format(Locale.US, "%02x", x & 255));
        return new File(dir(c), sb.substring(0, 32) + ".png");
    }

    /** The finished card from disk, or null when it was never made. No network. */
    static Bitmap cached(Context c, String url) {
        try {
            File f = fileFor(c, url);
            if (f.isFile() && f.length() > 0) return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Throwable ignored) { }
        return null;
    }

    /** Disk first, then the network (blocking; call off the main thread). Returns null when it cannot be had. */
    static Bitmap card(Context c, String url) {
        Bitmap hit = cached(c, url);
        if (hit != null) return hit;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "image/*");
            conn.setRequestProperty("User-Agent", "Cigram/1.0");
            if (conn.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > 12 * 1024 * 1024) return null;
                out.write(buf, 0, n);
            }
            in.close();
            byte[] data = out.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= W && bounds.outHeight / (sample * 2) >= H) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap raw = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (raw == null) return null;
            Bitmap made = compose(raw);
            if (made != raw) raw.recycle();
            File f = fileFor(c, url);
            FileOutputStream fo = new FileOutputStream(f);
            made.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.close();
            trim(c);
            return made;
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) { } }
        }
    }

    /** Landscape: cover. Portrait: whole poster centred over a blurred copy. Always W x H with rounded corners. */
    static Bitmap compose(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Path clip = new Path();
        clip.addRoundRect(new RectF(0, 0, W, H), RADIUS, RADIUS, Path.Direction.CW);
        canvas.clipPath(clip);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        float ratio = (float) src.getWidth() / Math.max(1, src.getHeight());
        if (ratio >= 1.25f) {
            drawCover(canvas, src, p);
        } else {
            // blurred backdrop: shrink hard, then stretch (cheap, no RenderScript), then darken
            Bitmap tiny = Bitmap.createScaledBitmap(src, 24, 14, true);
            canvas.drawBitmap(tiny, null, new RectF(0, 0, W, H), p);
            tiny.recycle();
            canvas.drawColor(Color.argb(120, 0, 0, 0));
            float ph = H - 18f;
            float pw = ph * ratio;
            float left = (W - pw) / 2f;
            Path pc = new Path();
            pc.addRoundRect(new RectF(left, 9f, left + pw, 9f + ph), 14f, 14f, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(pc);
            canvas.drawBitmap(src, null, new RectF(left, 9f, left + pw, 9f + ph), p);
            canvas.restore();
        }
        return out;
    }

    private static void drawCover(Canvas canvas, Bitmap src, Paint p) {
        float scale = Math.max((float) W / src.getWidth(), (float) H / src.getHeight());
        float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
        canvas.drawBitmap(src, null, new RectF((W - dw) / 2f, (H - dh) / 2f, (W + dw) / 2f, (H + dh) / 2f), p);
    }

    /** Keeps the newest cards only (the widget never needs more than a few dozen). */
    private static void trim(Context c) {
        try {
            File[] files = dir(c).listFiles();
            if (files == null || files.length <= KEEP_FILES) return;
            java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
            });
            for (int i = KEEP_FILES; i < files.length; i++) files[i].delete();
        } catch (Throwable ignored) { }
    }
}
