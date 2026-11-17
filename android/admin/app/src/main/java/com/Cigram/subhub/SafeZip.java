package com.Cigram.subhub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * CigramSubtitleHub (admin) — safe extraction of a season subtitle package (.zip).
 *
 * Everything stays in memory (nothing is written to disk, so a "../" path can never escape a folder), only .srt / .vtt
 * entries are kept, and hard limits stop zip bombs: entry count, size per file, total uncompressed size and
 * compression ratio. Nested archives, macOS resource forks and hidden files are ignored.
 */
public final class SafeZip {

    private SafeZip() {}

    public static final int MAX_ENTRIES = 400;
    public static final int MAX_FILES = 200;
    public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 80L * 1024 * 1024;
    public static final int MAX_RATIO = 200;

    public static final class Entry {
        public final String name;
        public final byte[] data;

        Entry(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    public static final class Result {
        public final List<Entry> files = new ArrayList<Entry>();
        /** Entries that were not subtitles (or unsafe) and were skipped, with the reason. */
        public final List<String> skipped = new ArrayList<String>();
    }

    public static boolean isZip(byte[] head) {
        return head != null && head.length >= 4 && head[0] == 'P' && head[1] == 'K' && (head[2] == 3 || head[2] == 5 || head[2] == 7);
    }

    public static boolean isSubtitleName(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".srt") || n.endsWith(".vtt");
    }

    /** @param compressedSize size of the archive (for the ratio check). */
    public static Result extract(InputStream in, long compressedSize) throws IOException {
        Result r = new Result();
        ZipInputStream z = new ZipInputStream(in);
        long total = 0;
        int entries = 0;
        try {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = z.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("الأرشيف يحتوي ملفات كثيرة جداً (الحد " + MAX_ENTRIES + ").");
                String raw = e.getName() == null ? "" : e.getName().replace('\\', '/');
                String base = raw.substring(raw.lastIndexOf('/') + 1);
                if (e.isDirectory()) continue;
                if (raw.startsWith("__MACOSX/") || base.startsWith("._") || base.startsWith(".")) { r.skipped.add(raw + " (ملف نظام)"); continue; }
                if (raw.contains("..") || raw.startsWith("/") || raw.indexOf(':') >= 0) { r.skipped.add(raw + " (مسار غير آمن)"); continue; }
                if (!isSubtitleName(base)) { r.skipped.add(raw + " (ليس SRT/VTT)"); continue; }
                if (r.files.size() >= MAX_FILES) throw new IOException("ملفات الترجمة كثيرة جداً (الحد " + MAX_FILES + ").");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int n;
                while ((n = z.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    total += n;
                    if (out.size() > MAX_FILE_BYTES) throw new IOException("ملف داخل الأرشيف أكبر من 8 ميغابايت: " + base);
                    if (total > MAX_TOTAL_BYTES) throw new IOException("حجم المحتوى بعد فك الضغط كبير جداً.");
                    if (compressedSize > 0 && total > compressedSize * MAX_RATIO && total > 2L * 1024 * 1024) {
                        throw new IOException("نسبة الضغط غير طبيعية — الأرشيف مرفوض لأسباب أمنية.");
                    }
                }
                r.files.add(new Entry(base, out.toByteArray()));
            }
        } finally {
            try { z.close(); } catch (IOException ignored) { }
        }
        return r;
    }
}
