package com.my.newproject;

import android.app.Activity;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Locale;

/**
 * Optional subtitle upload (.srt / .vtt, small). The text is converted to UTF-8 here (BOM removed,
 * Windows-1256 Arabic files recognised) and sent in one request. Separate from the video upload.
 */
final class CgSubtitleUpload {

    private CgSubtitleUpload() {}

    private static final long MAX = 5L * 1024L * 1024L;

    static void upload(final Activity a, final Uri uri, final CgMediaHook.Target t, final CgUi.Callback<String> onUrl) {
        final String name = displayName(a, uri);
        String lower = name.toLowerCase(Locale.US);
        if (!lower.endsWith(".srt") && !lower.endsWith(".vtt")) {
            CgUi.info(a, "ملف غير مدعوم", "اختر ملف ترجمة بصيغة .srt أو .vtt.");
            return;
        }
        final String kind = t.kind();
        final String slug = CgMediaHook.resolveSlug(t);
        final int season = t.season();
        final int episode = t.episode();
        CgUi.toast(a, "جارٍ رفع الترجمة...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String error = null;
                final byte[] data;
                byte[] tmp = null;
                try {
                    tmp = readUtf8(a, uri);
                } catch (Exception e) {
                    Log.e(CgMediaAnalyzer.TAG, "subtitle read failed", e);
                    error = e.getMessage() == null ? "تعذر قراءة ملف الترجمة." : e.getMessage();
                }
                data = tmp;
                if (error != null || data == null) {
                    final String msg = error == null ? "تعذر قراءة ملف الترجمة." : error;
                    post(a, new Runnable() {
                        @Override
                        public void run() {
                            CgUi.info(a, "الترجمة", msg);
                        }
                    });
                    return;
                }
                send(a, kind, slug, name, season, episode, "", data, onUrl);
            }
        }).start();
    }

    private static void send(final Activity a, final String kind, final String slug, final String name, final int season, final int episode,
                             final String onExists, final byte[] data, final CgUi.Callback<String> onUrl) {
        try {
            JSONObject r = CgMediaApi.putSubtitle(kind, slug, name, season, episode, onExists, data);
            final String url = firstNonEmpty(r.optString("publicUrl", ""), r.optString("url", ""));
            if (url.length() == 0) throw new CxApi.Failure("لم يصل رابط الترجمة من الخادم.", "BAD_RESPONSE", null, 0);
            post(a, new Runnable() {
                @Override
                public void run() {
                    onUrl.run(url);
                    CgUi.toast(a, "تم رفع الترجمة ووضع رابطها في الحقل.");
                }
            });
        } catch (final CxApi.Failure f) {
            if (f.is("OBJECT_EXISTS")) {
                final String existingUrl = f.existing().optString("url", "");
                post(a, new Runnable() {
                    @Override
                    public void run() {
                        askExists(a, kind, slug, name, season, episode, data, existingUrl, onUrl);
                    }
                });
                return;
            }
            Log.w(CgMediaAnalyzer.TAG, "subtitle upload failed: " + f.getMessage());
            post(a, new Runnable() {
                @Override
                public void run() {
                    CgUi.info(a, "تعذر رفع الترجمة", f.getMessage());
                }
            });
        } catch (final Exception e) {
            Log.e(CgMediaAnalyzer.TAG, "subtitle upload failed", e);
            post(a, new Runnable() {
                @Override
                public void run() {
                    CgUi.info(a, "تعذر رفع الترجمة", "حدث خطأ غير متوقع. أعد المحاولة.");
                }
            });
        }
    }

    private static void askExists(final Activity a, final String kind, final String slug, final String name, final int season, final int episode,
                                  final byte[] data, final String existingUrl, final CgUi.Callback<String> onUrl) {
        CharSequence[] labels = {"استخدام الملف الموجود", "استبدال الملف الموجود", "رفع باسم جديد"};
        CgUi.choose(a, "ملف الترجمة موجود مسبقاً", labels, new CgUi.IntCb() {
            @Override
            public void run(int index) {
                if (index == 0) {
                    if (existingUrl.length() > 0) onUrl.run(existingUrl);
                    return;
                }
                final String mode = index == 1 ? "overwrite" : "rename";
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        send(a, kind, slug, name, season, episode, mode, data, onUrl);
                    }
                }).start();
            }
        });
    }

    // ---------------------------------------------------------------------- helpers

    private static void post(Activity a, Runnable r) {
        if (a.isFinishing()) return;
        a.runOnUiThread(r);
    }

    private static String firstNonEmpty(String x, String y) {
        return x != null && x.length() > 0 ? x : (y == null ? "" : y);
    }

    static String displayName(Activity a, Uri uri) {
        String name = "";
        Cursor c = null;
        try {
            c = a.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception e) {
            Log.w(CgMediaAnalyzer.TAG, "display name unavailable: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        if (name == null || name.length() == 0) {
            String p = uri.getLastPathSegment();
            name = p == null ? "subtitle.srt" : p;
        }
        return name;
    }

    static byte[] readUtf8(Activity a, Uri uri) throws Exception {
        ContentResolver cr = a.getContentResolver();
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new Exception("تعذر فتح ملف الترجمة.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX) throw new Exception("ملف الترجمة كبير جداً (الحد 5 ميغابايت).");
            }
        } finally {
            in.close();
        }
        byte[] raw = out.toByteArray();
        if (raw.length == 0) throw new Exception("ملف الترجمة فارغ.");
        int off = 0;
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) off = 3;
        if (raw.length >= 2 && ((raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE || (raw[0] & 0xFF) == 0xFE && (raw[1] & 0xFF) == 0xFF)) {
            String s = new String(raw, Charset.forName("UTF-16"));
            return s.getBytes("UTF-8");
        }
        byte[] body = new byte[raw.length - off];
        System.arraycopy(raw, off, body, 0, body.length);
        try {
            Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body));
            return body;
        } catch (CharacterCodingException notUtf8) {
            String s = new String(body, Charset.forName("windows-1256"));
            return s.getBytes("UTF-8");
        }
    }
}
