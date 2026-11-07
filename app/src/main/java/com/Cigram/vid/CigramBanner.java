package com.Cigram.vid;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Account cover ("banner") of the logged-in user (v5.29): one landscape picture or one short
 * landscape video, shown full-bleed behind the account header. Videos always play muted.
 *
 * Storage: one file per account in filesDir/cigram_banner/ (named by the stable account hash),
 * described in the prefs "cigram_banner_v1". Nothing here touches the UI; it is called from a
 * background thread by {@link CigramBannerActivity} and read by {@link CigramAccountHeader}.
 */
public final class CigramBanner {

    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_VIDEO = "video";

    /** Limits that keep the account page light. */
    static final int MIN_WIDTH_PX = 480;
    static final int MAX_IMAGE_WIDTH_PX = 1920;
    static final long MAX_IMAGE_BYTES = 20L * 1024L * 1024L;
    static final long MAX_VIDEO_BYTES = 60L * 1024L * 1024L;
    static final long MAX_VIDEO_MS = 60L * 1000L;

    private static final String PREFS = "cigram_banner_v1";
    private static final String DIR = "cigram_banner";

    private CigramBanner() { }

    /** Current cover of the logged-in account, or null (guest / none / file missing). */
    public static final class Banner {
        public final String type;
        public final File file;
        public final long version;

        Banner(String type, File file, long version) {
            this.type = type;
            this.file = file;
            this.version = version;
        }

        public boolean isVideo() { return TYPE_VIDEO.equals(type); }
    }

    /** Outcome of {@link #store}: ok, or a user-facing Arabic message. */
    public static final class Result {
        public final boolean ok;
        public final String message;

        private Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        static Result ok() { return new Result(true, "تم تحديث الغلاف"); }
        static Result fail(String message) { return new Result(false, message); }
    }

    private static String owner(Context c) {
        if (c == null || !CigramUserData.isLoggedIn(c)) return "";
        String hash = CigramUserData.accountHash(c);
        return hash == null ? "" : hash.trim();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File dir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static Banner current(Context c) {
        String owner = owner(c);
        if (owner.length() == 0) return null;
        SharedPreferences p = prefs(c);
        String type = p.getString("type::" + owner, "");
        String name = p.getString("file::" + owner, "");
        if (type.length() == 0 || name.length() == 0) return null;
        File f = new File(dir(c), name);
        if (!f.isFile() || f.length() == 0L) return null;
        return new Banner(type, f, p.getLong("ver::" + owner, 0L));
    }

    public static void remove(Context c) {
        String owner = owner(c);
        if (owner.length() == 0) return;
        SharedPreferences p = prefs(c);
        String name = p.getString("file::" + owner, "");
        // v10.67: the cover that was just taken off is kept as the account's "last cover"
        if (name.length() > 0 && new File(dir(c), name).isFile()) {
            keepAsLast(c, p, owner, p.getString("type::" + owner, ""), name, p.getLong("ver::" + owner, 0L));
        }
        p.edit().remove("type::" + owner).remove("file::" + owner).remove("ver::" + owner).apply();
    }

    /** The cover this account had before the current one (or before it was removed). */
    public static boolean hasLast(Context c) {
        String owner = owner(c);
        if (owner.length() == 0) return false;
        String name = prefs(c).getString("lastfile::" + owner, "");
        return name.length() > 0 && new File(dir(c), name).isFile();
    }

    /** Brings the last cover back; the cover shown now becomes the last one (so it toggles). */
    public static boolean restoreLast(Context c) {
        String owner = owner(c);
        if (owner.length() == 0) return false;
        SharedPreferences p = prefs(c);
        String lastFile = p.getString("lastfile::" + owner, "");
        String lastType = p.getString("lasttype::" + owner, "");
        if (lastFile.length() == 0 || lastType.length() == 0 || !new File(dir(c), lastFile).isFile()) return false;
        String curFile = p.getString("file::" + owner, "");
        String curType = p.getString("type::" + owner, "");
        long curVer = p.getLong("ver::" + owner, 0L);
        SharedPreferences.Editor ed = p.edit();
        ed.putString("type::" + owner, lastType).putString("file::" + owner, lastFile)
                .putLong("ver::" + owner, System.currentTimeMillis());
        if (curFile.length() > 0 && new File(dir(c), curFile).isFile()) {
            ed.putString("lasttype::" + owner, curType).putString("lastfile::" + owner, curFile)
                    .putLong("lastver::" + owner, curVer);
        } else {
            ed.remove("lasttype::" + owner).remove("lastfile::" + owner).remove("lastver::" + owner);
        }
        ed.apply();
        return true;
    }

    /** Account deleted: every cover of this account goes. */
    public static void wipe(Context c) {
        String owner = owner(c);
        if (owner.length() == 0) return;
        SharedPreferences p = prefs(c);
        for (String key : new String[]{"file::", "lastfile::"}) {
            String name = p.getString(key + owner, "");
            if (name.length() > 0) new File(dir(c), name).delete();
        }
        p.edit().remove("type::" + owner).remove("file::" + owner).remove("ver::" + owner)
                .remove("lasttype::" + owner).remove("lastfile::" + owner).remove("lastver::" + owner).apply();
    }

    private static void keepAsLast(Context c, SharedPreferences p, String owner, String type, String name, long ver) {
        if (type.length() == 0 || name.length() == 0) return;
        String oldLast = p.getString("lastfile::" + owner, "");
        if (oldLast.length() > 0 && !oldLast.equals(name)) new File(dir(c), oldLast).delete();
        p.edit().putString("lasttype::" + owner, type).putString("lastfile::" + owner, name)
                .putLong("lastver::" + owner, ver).apply();
    }

    /** Validates and saves the picked file as the account cover. Call off the main thread. */
    public static Result store(Context c, Uri uri) {
        if (c == null || uri == null) return Result.fail("لم يتم اختيار ملف");
        String owner = owner(c);
        if (owner.length() == 0) return Result.fail("سجّل دخولك أولاً لإضافة غلاف لحسابك");
        String mime = "";
        try {
            String m = c.getContentResolver().getType(uri);
            mime = m == null ? "" : m;
        } catch (Throwable ignored) { }
        try {
            if (mime.startsWith("video/")) return storeVideo(c, uri, owner);
            if (mime.startsWith("image/")) return storeImage(c, uri, owner);
            return Result.fail("اختر صورة أو فيديو فقط");
        } catch (OutOfMemoryError oom) {
            return Result.fail("الملف كبير جداً، اختر ملفاً أصغر");
        } catch (Throwable error) {
            android.util.Log.w("CigramBanner", "store failed", error);
            return Result.fail("تعذر حفظ الغلاف، حاول بملف آخر");
        }
    }

    // ------------------------------------------------------------------ image

    private static Result storeImage(Context c, Uri uri, String owner) throws Exception {
        ContentResolver cr = c.getContentResolver();
        long size = sizeOf(c, uri);
        if (size > MAX_IMAGE_BYTES) return Result.fail("حجم الصورة أكبر من 20 ميغابايت");

        int rotation = 0;
        InputStream exifIn = cr.openInputStream(uri);
        try {
            if (exifIn != null) {
                int o = new ExifInterface(exifIn).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (o == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                else if (o == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                else if (o == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
            }
        } catch (Throwable ignored) {
            rotation = 0;
        } finally {
            closeQuietly(exifIn);
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream in = cr.openInputStream(uri);
        try {
            BitmapFactory.decodeStream(in, null, bounds);
        } finally {
            closeQuietly(in);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Result.fail("تعذر قراءة الصورة");
        boolean sideways = rotation == 90 || rotation == 270;
        int w = sideways ? bounds.outHeight : bounds.outWidth;
        int h = sideways ? bounds.outWidth : bounds.outHeight;
        if (w <= h) return Result.fail("الصورة يجب أن تكون بالعرض (أفقية)، الصور الطولية غير مقبولة");
        if (w < MIN_WIDTH_PX) return Result.fail("دقة الصورة منخفضة، اختر صورة أوضح");

        BitmapFactory.Options opts = new BitmapFactory.Options();
        int sample = 1;
        while (w / (sample * 2) >= MAX_IMAGE_WIDTH_PX) sample *= 2;
        opts.inSampleSize = sample;
        in = cr.openInputStream(uri);
        Bitmap bmp;
        try {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        } finally {
            closeQuietly(in);
        }
        if (bmp == null) return Result.fail("تعذر قراءة الصورة");
        if (rotation != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != bmp) bmp.recycle();
            bmp = rotated;
        }
        if (bmp.getWidth() > MAX_IMAGE_WIDTH_PX) {
            int nh = Math.round(bmp.getHeight() * (MAX_IMAGE_WIDTH_PX / (float) bmp.getWidth()));
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, MAX_IMAGE_WIDTH_PX, nh, true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }

        File tmp = new File(dir(c), owner + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out);
        } finally {
            closeQuietly(out);
            bmp.recycle();
        }
        return commit(c, owner, tmp, TYPE_IMAGE, ".jpg");
    }

    // ------------------------------------------------------------------ video

    private static Result storeVideo(Context c, Uri uri, String owner) throws Exception {
        long size = sizeOf(c, uri);
        if (size > MAX_VIDEO_BYTES) return Result.fail("حجم الفيديو أكبر من 60 ميغابايت");

        MediaMetadataRetriever r = new MediaMetadataRetriever();
        int w, h, rotation;
        long duration;
        try {
            r.setDataSource(c, uri);
            w = toInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            h = toInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            rotation = toInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            duration = toInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
        if (w <= 0 || h <= 0) return Result.fail("تعذر قراءة الفيديو");
        if (rotation == 90 || rotation == 270) {
            int t = w;
            w = h;
            h = t;
        }
        if (w <= h) return Result.fail("الفيديو يجب أن يكون بالعرض (أفقي)، الفيديو الطولي غير مقبول");
        if (w < MIN_WIDTH_PX) return Result.fail("دقة الفيديو منخفضة، اختر فيديو أوضح");
        if (duration > MAX_VIDEO_MS) return Result.fail("مدة الفيديو أطول من 60 ثانية");

        File tmp = new File(dir(c), owner + ".tmp");
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) return Result.fail("تعذر قراءة الفيديو");
        OutputStream out = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[64 * 1024];
            long total = 0L;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_VIDEO_BYTES) {
                    closeQuietly(out);
                    tmp.delete();
                    return Result.fail("حجم الفيديو أكبر من 60 ميغابايت");
                }
                out.write(buf, 0, n);
            }
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
        return commit(c, owner, tmp, TYPE_VIDEO, ".video");
    }

    // ------------------------------------------------------------------ helpers

    /** Swaps the new file in and drops the previous cover of this account. */
    private static Result commit(Context c, String owner, File tmp, String type, String ext) {
        if (!tmp.isFile() || tmp.length() == 0L) {
            tmp.delete();
            return Result.fail("تعذر حفظ الغلاف");
        }
        long version = System.currentTimeMillis();
        String name = owner + "_" + version + ext;
        File target = new File(dir(c), name);
        if (!tmp.renameTo(target)) {
            tmp.delete();
            return Result.fail("تعذر حفظ الغلاف");
        }
        SharedPreferences p = prefs(c);
        String old = p.getString("file::" + owner, "");
        String oldType = p.getString("type::" + owner, "");
        long oldVer = p.getLong("ver::" + owner, 0L);
        p.edit().putString("type::" + owner, type).putString("file::" + owner, name)
                .putLong("ver::" + owner, version).apply();
        // v10.67: the cover that was replaced stays as the account's "last cover"
        if (old.length() > 0 && !old.equals(name) && new File(dir(c), old).isFile()) {
            keepAsLast(c, p, owner, oldType, old, oldVer);
        }
        return Result.ok();
    }

    private static long sizeOf(Context c, Uri uri) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        return -1L;
    }

    private static int toInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (Throwable ignored) { }
    }
}
