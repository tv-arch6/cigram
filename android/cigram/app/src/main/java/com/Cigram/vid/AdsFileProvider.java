package com.Cigram.vid;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * مزوّد ملفات صغير لإخراج الكاميرا (ACTION_IMAGE_CAPTURE يحتاج Uri قابلاً للكتابة).
 * لا يكشف إلا المجلد cache/ads_capture/ ويمنع أي مسار خارجه (حماية من ../).
 * يُعلَن في المانيفست بـ android:exported="false" و android:grantUriPermissions="true".
 */
public class AdsFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.Cigram.vid.adsfiles";

    public static File dir(Context c) {
        File d = new File(c.getCacheDir(), "ads_capture");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** ينشئ ملفاً جديداً ويعيد Uri له. */
    public static Uri newCaptureUri(Context c, File[] outFile) throws IOException {
        File f = File.createTempFile("cam_", ".jpg", dir(c));
        if (outFile != null && outFile.length > 0) outFile[0] = f;
        return Uri.parse("content://" + AUTHORITY + "/capture/" + f.getName());
    }

    /** حذف ملفات الكاميرا القديمة (أكثر من ساعة). */
    public static void cleanOld(Context c) {
        try {
            File[] list = dir(c).listFiles();
            if (list == null) return;
            long limit = System.currentTimeMillis() - 3600000L;
            for (File f : list) if (f.lastModified() < limit) f.delete();
        } catch (Throwable ignored) { }
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }

    @Override public String getType(Uri uri) { return "image/jpeg"; }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.indexOf('/') >= 0 || name.indexOf("..") >= 0 || !name.startsWith("cam_")) throw new FileNotFoundException("denied");
        File f = new File(dir(getContext()), name);
        try {
            if (!f.getCanonicalPath().startsWith(dir(getContext()).getCanonicalPath() + File.separator)) throw new FileNotFoundException("denied");
        } catch (IOException e) {
            throw new FileNotFoundException("denied");
        }
        int m = ParcelFileDescriptor.MODE_READ_ONLY;
        if (mode != null && mode.indexOf('w') >= 0) m = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE;
        return ParcelFileDescriptor.open(f, m);
    }
}
