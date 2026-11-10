package com.Cigram.vid;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.LruCache;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * وسائط المحادثة: تجهيز الملف (ضغط الصور، نسخ آمن مع حد 25MB)، رفع على قطع 1MB قابل للاستئناف
 * (الخادم يشفّر كل قطعة AES-GCM قبل R2)، وتحميل/تخزين مؤقت للصور مع LruCache.
 * كل العمل في خيوط خلفية؛ النتائج تعود على الخيط الرئيسي.
 */
public final class AdsMedia {
    static void trimMemory(int severity) { CigramStability.shrink(MEM, severity); }


    public static final long MAX_BYTES = 25L * 1024L * 1024L;
    /**
     * 2026: videos up to 1 GB. They are NOT copied into the cache: the picked file is streamed part by part straight
     * from its content URI (memory use = one part), and a failed upload resumes from the parts the server already has.
     * The server must accept the same limit (init answers 413 / an error message otherwise, shown as is).
     */
    public static final long MAX_VIDEO_BYTES = 1024L * 1024L * 1024L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = CigramPool.fixed(3, "ads-io");
    private static final ExecutorService IMG = CigramPool.fixed(2, "ads-img");

    private AdsMedia() { }

    public interface Progress { void onProgress(int percent); }

    public interface Done { void onDone(boolean ok, String mediaId, String message); }

    public interface Prepare { void onReady(Prepared p, String error); }

    public static final class Prepared {
        public File file;
        /** Large video: read directly from here instead of a cached copy (file is then null). */
        public Uri source;
        /** Upload session on the server, kept after a failure so «إعادة المحاولة» resumes instead of restarting. */
        public String uploadId;
        public int partSize;
        public int parts;
        public String name = "ملف";
        public String kind = "file";
        public int w;
        public int h;
        public long size;
        public int durMs;
    }

    // ------------------------------------------------------------------ تجهيز الملف

    public static String displayName(Context c, Uri uri) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null && n.length() > 0) return n;
            }
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) try { cur.close(); } catch (Throwable ignored) { }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "ملف" : last;
    }

    public static long displaySize(Context c, Uri uri) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (Throwable ignored) {
        } finally {
            if (cur != null) try { cur.close(); } catch (Throwable ignored) { }
        }
        return -1L;
    }

    /** يجهّز ملفاً مختاراً: صورة → ضغط JPEG، غير ذلك → نسخة في الكاش بحد 25MB. */
    public static void prepare(final Context c, final Uri uri, final String kindHint, final Prepare cb) {
        final Context app = c.getApplicationContext();
        IO.execute(new Runnable() {
            @Override public void run() {
                Prepared p = null;
                String err = null;
                try {
                    p = prepareSync(app, uri, kindHint);
                } catch (IllegalStateException e) {
                    err = e.getMessage();
                } catch (Throwable t) {
                    err = "تعذر تجهيز الملف.";
                }
                final Prepared fp = p;
                final String fe = err;
                MAIN.post(new Runnable() {
                    @Override public void run() { cb.onReady(fp, fe); }
                });
            }
        });
    }

    private static File upDir(Context c) {
        File d = new File(c.getCacheDir(), "ads_up");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static void cleanOld(Context c) {
        try {
            File[] list = upDir(c).listFiles();
            if (list == null) return;
            long limit = System.currentTimeMillis() - 24L * 3600000L;
            for (File f : list) if (f.lastModified() < limit) f.delete();
        } catch (Throwable ignored) { }
    }

    static Prepared prepareSync(Context c, Uri uri, String kindHint) throws Exception {
        ContentResolver cr = c.getContentResolver();
        String mime = null;
        try { mime = cr.getType(uri); } catch (Throwable ignored) { }
        String name = displayName(c, uri);
        String kind = kindHint;
        if (kind == null || kind.length() == 0) {
            if (mime != null && mime.startsWith("image/")) kind = "image";
            else if (mime != null && mime.startsWith("video/")) kind = "video";
            else kind = "file";
        }
        Prepared p = new Prepared();
        p.name = name;
        p.kind = kind;
        if ("image".equals(kind) && (mime == null || !mime.equals("image/gif"))) {
            Prepared full = fullQualityImage(c, uri, name);              // 19.2: original resolution and quality
            if (full != null) return full;
            File out = new File(upDir(c), "u_" + UUID.randomUUID().toString().replace("-", "") + ".jpg");
            int[] wh = compressImage(c, uri, out);
            p.file = out;
            p.w = wh[0];
            p.h = wh[1];
            p.size = out.length();
            if (!name.toLowerCase().endsWith(".jpg") && !name.toLowerCase().endsWith(".jpeg")) p.name = stripExt(name) + ".jpg";
            return p;
        }
        long declared = displaySize(c, uri);
        if ("video".equals(kind)) {
            if (declared > MAX_VIDEO_BYTES) throw new IllegalStateException("حجم الفيديو أكبر من 1 غيغابايت.");
            if (declared > 0) {
                // stream it from where it is: no 1 GB copy in the cache, no 1 GB in memory
                try {
                    c.getContentResolver().takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) { }   // not every provider allows it; the upload still works while the app runs
                p.source = uri;
                p.size = declared;
                try {
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    r.setDataSource(c, uri);
                    String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                    String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                    String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    p.w = w == null ? 0 : Integer.parseInt(w);
                    p.h = h == null ? 0 : Integer.parseInt(h);
                    p.durMs = d == null ? 0 : Integer.parseInt(d);
                    r.release();
                } catch (Throwable ignored) { }
                return p;
            }
        } else if (declared > MAX_BYTES) {
            throw new IllegalStateException("الملف أكبر من 25MB.");
        }
        final long limit = "video".equals(kind) ? MAX_VIDEO_BYTES : MAX_BYTES;
        final String tooBig = "video".equals(kind) ? "حجم الفيديو أكبر من 1 غيغابايت." : "الملف أكبر من 25MB.";
        File out = new File(upDir(c), "u_" + UUID.randomUUID().toString().replace("-", ""));
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IllegalStateException("تعذر قراءة الملف.");
        long total = 0;
        try {
            OutputStream os = new FileOutputStream(out);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > limit) { os.close(); out.delete(); throw new IllegalStateException(tooBig); }
                    os.write(buf, 0, n);
                }
            } finally {
                try { os.close(); } catch (Throwable ignored) { }
            }
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
        if (total == 0) { out.delete(); throw new IllegalStateException("الملف فارغ."); }
        p.file = out;
        p.size = total;
        if ("video".equals(kind)) {
            try {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                r.setDataSource(out.getAbsolutePath());
                String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                p.w = w == null ? 0 : Integer.parseInt(w);
                p.h = h == null ? 0 : Integer.parseInt(h);
                p.durMs = d == null ? 0 : Integer.parseInt(d);
                r.release();
            } catch (Throwable ignored) { }
        }
        return p;
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    /**
     * 19.2 «الدقة الكاملة»: a photo is no longer shrunk to 1600 px before it is sent.
     * <ul>
     *   <li>JPEG / PNG / WebP that is already upright: the file is uploaded byte for byte (no decoding, no memory, no
     *   quality loss) — what the other side saves is exactly the original;</li>
     *   <li>a photo stored sideways (EXIF rotation) or in a format the server does not take (HEIC …): re-encoded ONCE at
     *   its full resolution, upright, JPEG quality 96;</li>
     *   <li>only when that does not fit (more than 25 MB, or more pixels than this phone's memory can hold) the caller
     *   falls back to the previous 1600 px path, so sending never fails because of quality.</li>
     * </ul>
     * Returns null for "use the fallback".
     */
    static Prepared fullQualityImage(Context c, Uri uri, String name) {
        File out = null;
        try {
            ContentResolver cr = c.getContentResolver();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            InputStream in = cr.openInputStream(uri);
            if (in == null) return null;
            try { BitmapFactory.decodeStream(in, null, o); } finally { try { in.close(); } catch (Throwable ignored) { } }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            String type = o.outMimeType == null ? "" : o.outMimeType;
            int rotation = 0;
            boolean mirrored = false;
            try {
                InputStream ei = cr.openInputStream(uri);
                if (ei != null) {
                    try {
                        int ori = new android.media.ExifInterface(ei).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                        if (ori == 6) rotation = 90;
                        else if (ori == 3) rotation = 180;
                        else if (ori == 8) rotation = 270;
                        else if (ori == 2 || ori == 4 || ori == 5 || ori == 7) mirrored = true;
                    } finally { try { ei.close(); } catch (Throwable ignored) { } }
                }
            } catch (Throwable ignored) { }
            if (mirrored) return null;                                   // rare: the tested path handles it
            String ext = "image/jpeg".equals(type) ? ".jpg" : "image/png".equals(type) ? ".png" : "image/webp".equals(type) ? ".webp" : null;
            Prepared p = new Prepared();
            p.kind = "image";
            String id = UUID.randomUUID().toString().replace("-", "");
            if (ext != null && rotation == 0) {
                out = new File(upDir(c), "u_" + id + ext);
                in = cr.openInputStream(uri);
                if (in == null) return null;
                long total = 0;
                OutputStream os = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_BYTES) break;
                        os.write(buf, 0, n);
                    }
                } finally {
                    try { os.close(); } catch (Throwable ignored) { }
                    try { in.close(); } catch (Throwable ignored) { }
                }
                if (total == 0 || total > MAX_BYTES) { out.delete(); return null; }
                p.file = out;
                p.w = o.outWidth;
                p.h = o.outHeight;
                p.size = total;
                p.name = stripExt(name) + ext;
                return p;
            }
            // decoded bitmap (+ a rotated copy) must fit comfortably in this app's memory
            long need = (long) o.outWidth * o.outHeight * 4L * (rotation != 0 ? 2 : 1);
            if (need > Runtime.getRuntime().maxMemory() / 3) return null;
            in = cr.openInputStream(uri);
            if (in == null) return null;
            Bitmap bmp;
            try { bmp = BitmapFactory.decodeStream(in); } finally { try { in.close(); } catch (Throwable ignored) { } }
            if (bmp == null) return null;
            if (rotation != 0) {
                Matrix m = new Matrix();
                m.postRotate(rotation);
                Bitmap nb = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (nb != bmp) { bmp.recycle(); bmp = nb; }
            }
            out = new File(upDir(c), "u_" + id + ".jpg");
            OutputStream os = new FileOutputStream(out);
            try { bmp.compress(Bitmap.CompressFormat.JPEG, 96, os); } finally { try { os.close(); } catch (Throwable ignored) { } }
            p.w = bmp.getWidth();
            p.h = bmp.getHeight();
            bmp.recycle();
            if (out.length() == 0 || out.length() > MAX_BYTES) { out.delete(); return null; }
            p.file = out;
            p.size = out.length();
            p.name = stripExt(name) + ".jpg";
            return p;
        } catch (Throwable t) {                                          // includes OutOfMemoryError
            if (out != null) try { out.delete(); } catch (Throwable ignored) { }
            return null;
        }
    }

    /** يصغّر الصورة إلى 1600px كحد أقصى ويصحح الدوران ويحفظها JPEG جودة 82. */
    static int[] compressImage(Context c, Uri uri, File out) throws Exception {
        ContentResolver cr = c.getContentResolver();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IllegalStateException("تعذر قراءة الصورة.");
        try { BitmapFactory.decodeStream(in, null, o); } finally { try { in.close(); } catch (Throwable ignored) { } }
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IllegalStateException("الصورة غير صالحة.");
        int sample = 1;
        while ((o.outWidth / sample) > 3200 || (o.outHeight / sample) > 3200) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        in = cr.openInputStream(uri);
        Bitmap bmp;
        try { bmp = BitmapFactory.decodeStream(in, null, o2); } finally { try { in.close(); } catch (Throwable ignored) { } }
        if (bmp == null) throw new IllegalStateException("الصورة غير صالحة.");
        int rotation = 0;
        try {
            InputStream ei = cr.openInputStream(uri);
            if (ei != null) {
                try {
                    int ori = new ExifInterface(ei).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    if (ori == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
                    else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
                    else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
                } finally { try { ei.close(); } catch (Throwable ignored) { } }
            }
        } catch (Throwable ignored) { }
        float scale = 1f;
        int big = Math.max(bmp.getWidth(), bmp.getHeight());
        if (big > 1600) scale = 1600f / big;
        if (scale != 1f || rotation != 0) {
            Matrix m = new Matrix();
            if (scale != 1f) m.postScale(scale, scale);
            if (rotation != 0) m.postRotate(rotation);
            Bitmap nb = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (nb != bmp) { bmp.recycle(); bmp = nb; }
        }
        OutputStream os = new FileOutputStream(out);
        try { bmp.compress(Bitmap.CompressFormat.JPEG, 82, os); } finally { try { os.close(); } catch (Throwable ignored) { } }
        int[] wh = new int[]{bmp.getWidth(), bmp.getHeight()};
        bmp.recycle();
        return wh;
    }

    // ------------------------------------------------------------------ الرفع (قطع 1MB، استئناف، إلغاء)

    /**
     * @param basePath "/chat/upload" للمستخدم
     * @param query    لاحقة إضافية للاستعلام (للأدمن: "thread=...") أو null
     */
    public static void upload(final Context c, final Prepared p, final int durMs, final int[] wave, final String basePath, final String query,
                              final String bearer, final Progress progress, final Done done, final AtomicBoolean cancel) {
        IO.execute(new Runnable() {
            @Override public void run() {
                String err = null;
                String id = null;
                try {
                    id = uploadSync(c.getApplicationContext(), p, durMs, wave, basePath, query, bearer, progress, cancel);
                } catch (IllegalStateException e) {
                    err = e.getMessage();
                } catch (Throwable t) {
                    err = "تعذر رفع الملف. تحقق من اتصالك وحاول مجدداً.";
                }
                final String fid = id;
                final String fe = err;
                MAIN.post(new Runnable() {
                    @Override public void run() { done.onDone(fid != null, fid, fe == null ? "" : fe); }
                });
            }
        });
    }

    private static String q(String query, String base) {
        return (query == null || query.length() == 0) ? base : (base.contains("?") ? base + "&" + query : base + "?" + query);
    }

    /** The bytes of a prepared file: the cached copy, or (large video) the picked content itself. */
    private static InputStream openSource(Context c, Prepared p) throws Exception {
        if (p.file != null) return new FileInputStream(p.file);
        if (p.source != null && c != null) {
            InputStream in = c.getContentResolver().openInputStream(p.source);
            if (in != null) return in;
        }
        throw new IllegalStateException("تعذر قراءة الملف. أعد اختياره.");
    }

    private static void skipFully(InputStream in, long n) throws Exception {
        long left = n;
        byte[] scratch = null;
        while (left > 0) {
            long k = in.skip(left);
            if (k <= 0) {
                // some content streams cannot skip: read and drop
                if (scratch == null) scratch = new byte[64 * 1024];
                int r = in.read(scratch, 0, (int) Math.min(scratch.length, left));
                if (r < 0) throw new IllegalStateException("الملف تغيّر أثناء الرفع.");
                k = r;
            }
            left -= k;
        }
    }

    /** Sends every part the server does not have yet, reading the source once, in order (memory = one part). */
    private static void sendParts(Context c, Prepared p, boolean[] have, String basePath, String query, String bearer,
                                  Progress progress, AtomicBoolean cancel) throws Exception {
        int done = 0;
        for (boolean h : have) if (h) done++;
        InputStream in = openSource(c, p);
        try {
            long position = 0;
            for (int n = 0; n < p.parts; n++) {
                if (cancel != null && cancel.get()) throw new IllegalStateException("أُلغي الرفع.");
                if (have[n]) continue;
                long offset = (long) n * p.partSize;
                if (offset > position) { skipFully(in, offset - position); position = offset; }
                long len = Math.min((long) p.partSize, p.size - offset);
                byte[] buf = new byte[(int) len];
                int off = 0;
                while (off < buf.length) {
                    int k = in.read(buf, off, buf.length - off);
                    if (k < 0) throw new IllegalStateException("الملف تغيّر أثناء الرفع.");
                    off += k;
                }
                position += len;
                putPartWithRetry(basePath, query, p.uploadId, n, buf, bearer, cancel);
                have[n] = true;
                done++;
                if (progress != null) postProgress(progress, (int) ((done * 100L) / Math.max(1, p.parts)));
            }
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }

    private static boolean[] serverHas(String basePath, String query, Prepared p, String bearer) {
        boolean[] got = new boolean[p.parts];
        try {
            CigramAds.Result st = CigramAds.request("GET", q(query, basePath + "/status?id=" + p.uploadId), null, bearer);
            if (!st.ok) return null;
            JSONArray ha = st.json.optJSONArray("have");
            if (ha != null) for (int i = 0; i < ha.length(); i++) { int x = ha.optInt(i, -1); if (x >= 0 && x < p.parts) got[x] = true; }
        } catch (Throwable t) {
            return null;
        }
        return got;
    }

    private static String uploadSync(Context c, Prepared p, int durMs, int[] wave, String basePath, String query, String bearer, Progress progress, AtomicBoolean cancel) throws Exception {
        boolean[] have = null;
        if (p.uploadId != null && p.parts > 0) {
            // «إعادة المحاولة»: continue the same session; only what the server is missing is sent again
            have = serverHas(basePath, query, p, bearer);
            if (have == null) { p.uploadId = null; }   // the session expired on the server: start a new one
        }
        if (p.uploadId == null) {
            JSONObject init = new JSONObject();
            init.put("name", p.name);
            init.put("kind", p.kind);
            init.put("size", p.size);
            init.put("dur", durMs > 0 ? durMs : p.durMs);
            init.put("w", p.w);
            init.put("h", p.h);
            if (wave != null) {
                JSONArray w = new JSONArray();
                for (int v : wave) w.put(v);
                init.put("wave", w);
            }
            CigramAds.Result r = CigramAds.request("POST", q(query, basePath + "/init"), init.toString(), bearer);
            if (!r.ok) {
                if (r.code == 413) throw new IllegalStateException("خادم المحادثة لا يقبل حالياً ملفاً بهذا الحجم.");
                throw new IllegalStateException(r.message.length() > 0 ? r.message : "تعذر بدء الرفع.");
            }
            p.uploadId = r.json.optString("media_id");
            p.partSize = Math.max(64 * 1024, r.json.optInt("part_size", 1024 * 1024));
            p.parts = Math.max(1, r.json.optInt("parts", (int) ((p.size + p.partSize - 1) / p.partSize)));
            have = new boolean[p.parts];
        }
        sendParts(c, p, have, basePath, query, bearer, progress, cancel);
        CigramAds.Result done = CigramAds.request("POST", q(query, basePath + "/complete?id=" + p.uploadId), "{}", bearer);
        if (!done.ok) {
            // missing parts on the server: ask which, send them once more, complete again
            boolean[] got = serverHas(basePath, query, p, bearer);
            if (got != null) {
                sendParts(c, p, got, basePath, query, bearer, progress, cancel);
                done = CigramAds.request("POST", q(query, basePath + "/complete?id=" + p.uploadId), "{}", bearer);
            }
            if (!done.ok) throw new IllegalStateException(done.message.length() > 0 ? done.message : "تعذر إكمال الرفع.");
        }
        String id = p.uploadId;
        p.uploadId = null;   // finished: a later send of the same Prepared would be a new upload
        return id;
    }

    private static void postProgress(final Progress p, final int pct) {
        MAIN.post(new Runnable() {
            @Override public void run() { p.onProgress(pct); }
        });
    }

    private static void putPartWithRetry(String basePath, String query, String id, int n, byte[] data, String bearer, AtomicBoolean cancel) throws Exception {
        Exception last = null;
        String useBearer = CigramAds.freshest(bearer);       // 18.3: a long upload keeps working after the session was renewed
        boolean renewedOnce = false;
        for (int attempt = 0; attempt < 4; attempt++) {
            if (cancel != null && cancel.get()) throw new IllegalStateException("أُلغي الرفع.");
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(CigramAds.apiBase + q(query, basePath + "/part?id=" + id + "&n=" + n)).openConnection();
                conn.setRequestMethod("PUT");
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(40000);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(data.length);
                conn.setRequestProperty("Authorization", "Bearer " + useBearer);
                conn.setRequestProperty("Content-Type", "application/octet-stream");
                OutputStream os = conn.getOutputStream();
                os.write(data);
                os.flush();
                os.close();
                int code = conn.getResponseCode();
                if (code >= 200 && code < 300) return;
                if (code == 401 && !renewedOnce) {          // one shared silent renewal, then the same part again
                    renewedOnce = true;
                    String t = CigramAds.refreshAfter401(useBearer);
                    if (t.length() > 0) { useBearer = t; attempt--; continue; }
                }
                if (code == 415 || code == 400 || code == 403 || code == 404 || code == 413) {
                    String msg = "الملف غير مقبول.";
                    if (code == 415) msg = "نوع الملف غير مدعوم.";
                    throw new IllegalStateException(msg);
                }
                last = new Exception("http " + code);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                last = e;
            } finally {
                if (conn != null) conn.disconnect();
            }
            try { Thread.sleep(600L * (attempt + 1)); } catch (InterruptedException ie) { throw ie; }
        }
        throw last == null ? new Exception("upload failed") : last;
    }

    // ------------------------------------------------------------------ تحميل الصور

    private static final LruCache<String, Bitmap> MEM = new LruCache<String, Bitmap>((int) Math.max(4 * 1024 * 1024, Math.min(32 * 1024 * 1024, Runtime.getRuntime().maxMemory() / 12))) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private static final Map<ImageView, String> WANT = new HashMap<ImageView, String>();

    /** مفتاح ثابت للصورة بغضّ النظر عن توقيع الرابط المتغير. */
    public static String cacheKey(String url) {
        int q = url.indexOf('?');
        String base = q > 0 ? url.substring(0, q) : url;
        return base;
    }

    public interface BitmapCb { void onBitmap(Bitmap b); }

    /** True when the picture of {@code url} at {@code maxPx} is already decoded in memory (shows without waiting). */
    public static boolean isReady(String url, int maxPx, boolean videoFrame) {
        if (url == null || url.length() == 0) return true;   // nothing to load (text ad)
        return MEM.get(cacheKey(url) + "@" + maxPx + (videoFrame ? "v" : "")) != null;
    }

    /**
     * Decodes the picture into the memory cache in the background and runs {@code done} on the main thread with
     * true (ready) or false (failed). Used so a full-screen ad never opens as an empty black screen.
     */
    public static void prefetch(final Context c, final String url, final int maxPx, final boolean videoFrame,
                                final Callback done) {
        if (isReady(url, maxPx, videoFrame)) { if (done != null) done.onResult(true); return; }
        final String key = cacheKey(url) + "@" + maxPx + (videoFrame ? "v" : "");
        final Context app = c.getApplicationContext();
        IMG.execute(new Runnable() {
            @Override public void run() {
                Bitmap b = null;
                try {
                    b = videoFrame ? videoFrameOf(url, maxPx) : download(app, url, maxPx);
                } catch (Throwable ignored) { }
                if (b != null) MEM.put(key, b);
                final boolean ok = b != null;
                if (done != null) MAIN.post(new Runnable() { @Override public void run() { done.onResult(ok); } });
            }
        });
    }

    public interface Callback { void onResult(boolean ok); }

    public static void loadInto(final Context c, final ImageView iv, final String url, final int maxPx, final boolean videoFrame) {
        if (url == null || url.length() == 0) return;
        final String key = cacheKey(url) + "@" + maxPx + (videoFrame ? "v" : "");
        Bitmap hit = MEM.get(key);
        iv.setTag(key);
        if (hit != null) { iv.setImageBitmap(hit); return; }
        iv.setImageBitmap(null);
        final Context app = c.getApplicationContext();
        IMG.execute(new Runnable() {
            @Override public void run() {
                Bitmap b = null;
                try {
                    b = videoFrame ? videoFrameOf(url, maxPx) : download(app, url, maxPx);
                } catch (Throwable ignored) { }
                if (b == null) return;
                MEM.put(key, b);
                final Bitmap fb = b;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        Object t = iv.getTag();
                        if (t != null && key.equals(t)) iv.setImageBitmap(fb);
                    }
                });
            }
        });
    }

    private static Bitmap videoFrameOf(String url, int maxPx) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(url, new HashMap<String, String>());
            Bitmap b = r.getFrameAtTime(500000L);
            if (b == null) return null;
            int big = Math.max(b.getWidth(), b.getHeight());
            if (big > maxPx) {
                float s = (float) maxPx / big;
                Bitmap sc = Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * s)), Math.max(1, (int) (b.getHeight() * s)), true);
                if (sc != b) b.recycle();
                return sc;
            }
            return b;
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    private static Bitmap download(Context c, String url, int maxPx) throws Exception {
        File dir = new File(c.getCacheDir(), "ads_media");
        if (!dir.exists()) dir.mkdirs();
        String name = Integer.toHexString(cacheKey(url).hashCode()) + ".bin";
        File f = new File(dir, name);
        if (!f.exists() || f.length() == 0) {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            try {
                if (conn.getResponseCode() != 200) return null;
                InputStream in = conn.getInputStream();
                File tmp = new File(dir, name + ".tmp");
                OutputStream os = new FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > 30L * 1024 * 1024) throw new Exception("too big");
                        os.write(buf, 0, n);
                    }
                } finally {
                    try { os.close(); } catch (Throwable ignored) { }
                    try { in.close(); } catch (Throwable ignored) { }
                }
                tmp.renameTo(f);
            } finally {
                conn.disconnect();
            }
            trimCache(dir);
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        while (o.outWidth / sample > maxPx * 2 || o.outHeight / sample > maxPx * 2) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
    }

    private static void trimCache(File dir) {
        try {
            File[] list = dir.listFiles();
            if (list == null) return;
            long total = 0;
            for (File f : list) total += f.length();
            if (total < 120L * 1024 * 1024) return;
            java.util.Arrays.sort(list, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
            });
            for (File f : list) {
                if (total < 80L * 1024 * 1024) break;
                total -= f.length();
                f.delete();
            }
        } catch (Throwable ignored) { }
    }

    /** يمسح الكاش المحلي للوسائط (عند تسجيل الخروج أو حذف المحادثة). */
    public static void clearCaches(Context c) {
        try {
            MEM.evictAll();
            File[] a = new File(c.getCacheDir(), "ads_media").listFiles();
            if (a != null) for (File f : a) f.delete();
            File[] b = upDir(c).listFiles();
            if (b != null) for (File f : b) f.delete();
        } catch (Throwable ignored) { }
    }

    public static String sizeLabel(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        double mb = bytes / 1048576.0;
        return (Math.round(mb * 10) / 10.0) + " MB";
    }

    public static String durLabel(int ms) {
        int s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
