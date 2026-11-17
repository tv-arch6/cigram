package com.Cigram.vid;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * v10.62: مزوّد ملفات للقراءة فقط لبطاقات المشاركة (cache/share_cards/card_*.png).
 * لا يكشف أي مسار آخر. يُعلن في المانيفست exported=false و grantUriPermissions=true.
 */
public class CigramShareProvider extends ContentProvider {

    public static final String AUTHORITY = "com.Cigram.vid.sharecards";

    public static File dir(Context c) {
        File d = new File(c.getCacheDir(), "share_cards");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static Uri uriFor(File f) {
        return Uri.parse("content://" + AUTHORITY + "/card/" + f.getName());
    }

    /** يحذف البطاقات الأقدم من يوم. */
    public static void cleanOld(Context c) {
        try {
            File[] list = dir(c).listFiles();
            if (list == null) return;
            long limit = System.currentTimeMillis() - 24L * 3600000L;
            for (File f : list) if (f.lastModified() < limit) f.delete();
        } catch (Throwable ignored) { }
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.indexOf('/') >= 0 || name.contains("..") || !name.startsWith("card_")) {
            throw new FileNotFoundException("denied");
        }
        File base = dir(getContext());
        File f = new File(base, name);
        try {
            if (!f.getCanonicalPath().startsWith(base.getCanonicalPath() + File.separator)) throw new FileNotFoundException("denied");
        } catch (IOException e) {
            throw new FileNotFoundException("denied");
        }
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override public String getType(Uri uri) { return "image/png"; }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
