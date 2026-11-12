package com.my.newproject;

import android.os.StatFs;

import java.io.File;
import java.util.Locale;

/**
 * Decides, from the real properties of the file, what each upload mode would do:
 * original / smart compression (H.265 first) / higher compatibility (H.264).
 * Quality always wins: the resolution is never reduced and an already efficient file is not re-encoded
 * unless the admin explicitly asks for it.
 */
final class CgSmartPlan {

    static final int MODE_ORIGINAL = 0;
    static final int MODE_SMART = 1;
    static final int MODE_COMPAT = 2;

    int mode;
    String title = "";
    String description = "";
    /** Why this mode cannot run (empty when it can). */
    String blocked = "";
    /** Soft notes shown under the card (already efficient, audio conversion, software encoder ...). */
    String note = "";
    boolean recommended;
    boolean alreadyEfficient;

    String videoMime = "";
    long targetBitrate;
    long estimatedBytes;
    long sourceBytes;

    boolean available() {
        return blocked.length() == 0;
    }

    long savedBytes() {
        return Math.max(0L, sourceBytes - estimatedBytes);
    }

    int savedPercent() {
        return sourceBytes <= 0 ? 0 : (int) Math.round(100.0 * savedBytes() / sourceBytes);
    }

    // ------------------------------------------------------------------------------ planning

    static CgSmartPlan[] plans(CgMediaInfo info, File tempDir) {
        CgSmartPlan original = new CgSmartPlan();
        original.mode = MODE_ORIGINAL;
        original.title = "الجودة الأصلية";
        original.description = "رفع الملف كما هو بدون ضغط أو تحويل. لا يتغير الفيديو إطلاقاً (مطابق 100% للأصل).";
        original.estimatedBytes = info.size;
        original.sourceBytes = info.size;

        CgSmartPlan smart = new CgSmartPlan();
        smart.mode = MODE_SMART;
        smart.title = "ضغط ذكي - موصى به";
        smart.description = "تقليل الحجم مع الحفاظ على الدقة (" + info.resolutionText() + ") والجودة البصرية العالية. H.265 عند توفره.";
        smart.sourceBytes = info.size;

        CgSmartPlan compat = new CgSmartPlan();
        compat.mode = MODE_COMPAT;
        compat.title = "توافق أعلى";
        compat.description = "H.264 لدعم أكبر عدد ممكن من الأجهزة. الحجم أكبر قليلاً من الضغط الذكي.";
        compat.sourceBytes = info.size;

        String why = commonBlock(info);
        long free = freeBytes(tempDir);

        plan(info, smart, true, why, free);
        plan(info, compat, false, why, free);

        if (smart.available() && !smart.alreadyEfficient) smart.recommended = true;
        else if (!smart.available() && compat.available() && !compat.alreadyEfficient) compat.recommended = true;
        else original.recommended = true;
        return new CgSmartPlan[]{original, smart, compat};
    }

    private static String commonBlock(CgMediaInfo info) {
        if (!info.hasVideo || info.width <= 0 || info.height <= 0) return "تعذر قراءة أبعاد الفيديو.";
        if (info.durationMs <= 0) return "تعذر قراءة مدة الفيديو.";
        if (info.hdr) return "الفيديو بتقنية HDR / Dolby Vision. الضغط على الهاتف قد يفسد الألوان، لذلك يُنصح برفع النسخة الأصلية.";
        if (info.tenBit) return "الفيديو بعمق ألوان 10-bit. الضغط على الهاتف يحوّله إلى 8-bit وقد تتأثر الجودة، لذلك يُنصح برفع النسخة الأصلية.";
        if (!CgMediaCompressor.audioSupported(info)) {
            return "صيغة الصوت (" + info.audioLabel + ") لا يمكن تحويلها على هذا الهاتف. ارفع النسخة الأصلية.";
        }
        return "";
    }

    private static void plan(CgMediaInfo info, CgSmartPlan p, boolean preferHevc, String commonBlock, long free) {
        if (commonBlock.length() > 0) {
            p.blocked = commonBlock;
            return;
        }
        int w = info.width & ~1;
        int h = info.height & ~1;
        double fps = info.fps > 0 ? info.fps : 30;

        String mime = "video/avc";
        CgMediaCompressor.EncoderChoice choice = null;
        if (preferHevc) {
            choice = CgMediaCompressor.findEncoder("video/hevc", w, h, fps, false);
            if (choice != null) mime = "video/hevc";
        }
        if (choice == null) {
            choice = CgMediaCompressor.findEncoder("video/avc", w, h, fps, true);
            if (choice == null) {
                p.blocked = "هذا الهاتف لا يدعم ترميز الفيديو بدقة " + info.resolutionText() + ". ارفع النسخة الأصلية.";
                return;
            }
            if (preferHevc) p.note = "هذا الهاتف لا يدعم ترميز H.265 بهذه الدقة، لذلك سيُستخدم H.264. ";
            if (!choice.hardware) p.note += "الترميز برمجي وقد يكون أبطأ بكثير. ";
        }
        p.videoMime = mime;
        long target = targetBitrate(w, h, fps, "video/hevc".equals(mime));
        long source = info.videoBitrate;
        if (source > 0) {
            double cap = "video/hevc".equals(mime) ? 0.75 : 0.9;
            target = Math.min(target, (long) (source * cap));
        }
        target = Math.max(target, "video/hevc".equals(mime) ? 600000L : 900000L);
        target = Math.min(target, (long) choice.maxBitrate);
        p.targetBitrate = target;

        long audioBps = info.hasAudio ? (info.audioIsAac() && info.audioBitrate > 0 ? info.audioBitrate : 192000L) : 0L;
        p.estimatedBytes = (long) ((target + audioBps) / 8.0 * (info.durationMs / 1000.0) * 1.03);

        // already efficient: re-encoding would gain very little and costs quality
        boolean efficientCodec = info.isHevc() || "video/av01".equals(info.videoMime) || "video/x-vnd.on2.vp9".equals(info.videoMime);
        if (source > 0 && info.size > 0) {
            boolean smallGain = p.estimatedBytes > info.size * 0.85;
            boolean lowBitrate = efficientCodec && source <= target * 1.25;
            if (smallGain || lowBitrate) {
                p.alreadyEfficient = true;
                p.note += "الفيديو مضغوط مسبقاً بشكل جيد، وإعادة الترميز تقلل الجودة بتوفير بسيط. ";
            }
        }
        if (info.audioMime.length() > 0 && !info.audioIsAac()) {
            p.note += "الصوت (" + info.audioLabel + ") سيُحوَّل إلى AAC" + (info.audioChannels > 2 ? " ستيريو (فقدان الصوت المحيطي)" : "") + ". ";
        }
        if (info.audioTracks > 1) p.note += "يُحفظ مسار الصوت الأول فقط (" + info.audioTracks + " مسارات في الملف). ";
        if (info.subtitleTracks > 0) p.note += "الترجمات المدمجة في الملف لن تُنقل (ارفع ملف SRT منفصلاً). ";

        long need = (long) (p.estimatedBytes * 1.1) + 300L * 1024L * 1024L;
        if (free > 0 && free < need) {
            p.blocked = "المساحة غير كافية لمعالجة الفيديو (يلزم نحو " + CgUi.sizeText(need) + " والمتاح " + CgUi.sizeText(free) + "). ارفع النسخة الأصلية أو أفرغ مساحة.";
        }
        p.note = p.note.trim();
    }

    /**
     * Bits per pixel per frame (24 fps equivalent) used for "visually transparent for movies" targets
     * with hardware encoders. Smaller pictures need more bits per pixel; H.264 needs ~1.5x H.265.
     */
    static long targetBitrate(int w, int h, double fps, boolean hevc) {
        double px = (double) w * (double) h;
        double bpp;
        if (px >= 7.0e6) bpp = 0.085;
        else if (px >= 3.0e6) bpp = 0.10;
        else if (px >= 1.8e6) bpp = 0.14;
        else if (px >= 0.9e6) bpp = 0.16;
        else bpp = 0.20;
        if (!hevc) bpp *= 1.5;
        double f = fps > 0 ? fps : 24;
        double effective = 24.0 * Math.pow(f / 24.0, 0.75);
        return (long) (px * bpp * effective);
    }

    static long freeBytes(File dir) {
        try {
            File d = dir;
            while (d != null && !d.exists()) d = d.getParentFile();
            if (d == null) return 0;
            StatFs fs = new StatFs(d.getAbsolutePath());
            return fs.getAvailableBytes();
        } catch (Exception e) {
            return 0;
        }
    }

    String summary() {
        return String.format(Locale.US, "%s • %s • ≈ %s", title, CgMediaInfo.bitrateText(targetBitrate, false), CgUi.sizeText(estimatedBytes));
    }
}
