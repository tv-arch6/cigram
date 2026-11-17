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
 *
 * 18.2 (CigramSubtitleHub): the file is parsed and checked BEFORE it is sent — structure, timing, overlaps, reading
 * speed, Arabic share, encoding — and the admin sees a preview with the result. What is uploaded is the normalized
 * file: UTF-8 without BOM, three millisecond digits (ExoPlayer reads ",5" as 5 ms), same format (SRT stays SRT, VTT
 * stays VTT, detected from the content) and exactly the original cue times.
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
                final Checked chk = check(name, data);
                post(a, new Runnable() {
                    @Override
                    public void run() {
                        confirmChecked(a, chk, new Runnable() {
                            @Override
                            public void run() {
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        send(a, kind, slug, chk.fileName, season, episode, "", chk.bytes, onUrl);
                                    }
                                }).start();
                            }
                        });
                    }
                });
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

    // ---------------------------------------------------------------------- 18.2 validation

    /** A file after CigramSubtitleHub: normalized bytes, the name with the real extension, the report. */
    static final class Checked {
        byte[] bytes;
        String fileName;
        com.Cigram.subhub.SubDocument doc;
        com.Cigram.subhub.SubQa.Report report;
        boolean blocking;
        String reason = "";
    }

    static Checked check(String name, byte[] utf8) {
        Checked c = new Checked();
        try {
            com.Cigram.subhub.SubDocument d = com.Cigram.subhub.SubCodec.read(utf8);
            c.doc = d;
            c.report = com.Cigram.subhub.SubQa.analyze(d, new com.Cigram.subhub.SubQa.Options());
            String base = name.replaceAll("(?i)\\.(srt|vtt)$", "");
            String ext = d.format == com.Cigram.subhub.SubDocument.Format.VTT ? "vtt" : "srt";
            c.fileName = base + "." + ext;
            c.bytes = com.Cigram.subhub.SubCodec.writeBytes(d, d.format);
            if (d.isEmpty()) { c.blocking = true; c.reason = "الملف لا يحتوي أي مقطع ترجمة صالح."; }
            else if (c.report.count("INVALID_TIMING") > d.size() / 10) { c.blocking = true; c.reason = "أكثر من 10% من المقاطع توقيتها غير صالح."; }
        } catch (Throwable e) {
            c.blocking = true;
            c.reason = e.getMessage() == null ? "تعذر قراءة الملف." : e.getMessage();
            c.fileName = name;
            c.bytes = utf8;
        }
        return c;
    }

    /** The preview the admin sees before anything is uploaded. */
    static void confirmChecked(Activity a, Checked c, final Runnable upload) {
        if (c.blocking) {
            CgUi.info(a, "الملف مرفوض", c.reason);
            return;
        }
        StringBuilder m = new StringBuilder();
        m.append(c.report.summary()).append("\n");
        m.append("الصيغة: ").append(c.doc.format).append(" · الترميز الأصلي: ").append(c.doc.encoding).append("\n");
        m.append(String.format(Locale.US, "نسبة العربية: %.0f%%", c.report.arabicShare * 100)).append("\n\n");
        int n = 0;
        for (com.Cigram.subhub.SubQa.Issue i : c.report.issues) {
            if (i.severity == com.Cigram.subhub.SubQa.Severity.INFO) continue;
            if (n++ >= 6) break;
            m.append("• ").append(i.cue > 0 ? "#" + i.cue + " " : "").append(i.message).append("\n");
        }
        for (String w : c.doc.warnings) { if (n++ >= 9) break; m.append("• ").append(w).append("\n"); }
        m.append("\nأول المقاطع:\n");
        for (int k = 0; k < Math.min(3, c.doc.size()); k++) {
            com.Cigram.subhub.SubCue q = c.doc.cues.get(k);
            m.append(com.Cigram.subhub.SubCodec.stamp(q.startMs, ',')).append("  ").append(q.plainText().replace('\n', ' ')).append("\n");
        }
        m.append("\nيُرفع الملف بترميز UTF-8 وبنفس توقيت كل مقطع.");
        boolean notArabic = c.report.count("NOT_ARABIC") > 0;
        CgUi.confirm(a, notArabic ? "تنبيه: الملف ليس عربياً" : "معاينة ملف الترجمة", m.toString(), notArabic ? "رفع رغم ذلك" : "رفع", notArabic, upload);
    }

    /** Raw bytes of a picked file (size-limited), without any conversion. */
    static byte[] readRaw(Activity a, Uri uri) throws Exception {
        InputStream in = a.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("تعذر فتح الملف.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX) throw new Exception("الملف كبير جداً (الحد 5 ميغابايت).");
            }
        } finally {
            in.close();
        }
        if (out.size() == 0) throw new Exception("الملف فارغ.");
        return out.toByteArray();
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

    /** UTF-8 bytes of a picked subtitle; 18.2: normalized by CigramSubtitleHub when it parses (else the decoded text). */
    static byte[] readUtf8(Activity a, Uri uri) throws Exception {
        byte[] decoded = readUtf8Plain(a, uri);
        try {
            com.Cigram.subhub.SubDocument d = com.Cigram.subhub.SubCodec.read(decoded);
            if (!d.isEmpty()) return com.Cigram.subhub.SubCodec.writeBytes(d, d.format);
        } catch (Throwable ignored) { }
        return decoded;
    }

    private static byte[] readUtf8Plain(Activity a, Uri uri) throws Exception {
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
