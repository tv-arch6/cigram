package com.my.newproject;

import android.content.Context;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

/**
 * Reads the real properties of a video file (container, codec, resolution, fps, bitrate, audio, HDR).
 * Blocking: call from a background thread. Only MediaExtractor / MediaMetadataRetriever are used,
 * so no extra library is needed.
 */
final class CgMediaAnalyzer {

    private CgMediaAnalyzer() {}

    static final String TAG = "CigramMedia";

    // MediaFormat keys as plain strings so the code does not depend on the SDK level it is compiled against.
    static final String K_MIME = "mime";
    static final String K_WIDTH = "width";
    static final String K_HEIGHT = "height";
    static final String K_DURATION = "durationUs";
    static final String K_FRAME_RATE = "frame-rate";
    static final String K_BITRATE = "bitrate";
    static final String K_ROTATION = "rotation-degrees";
    static final String K_PROFILE = "profile";
    static final String K_COLOR_TRANSFER = "color-transfer";
    static final String K_CHANNELS = "channel-count";
    static final String K_SAMPLE_RATE = "sample-rate";

    static CgMediaInfo analyze(Context c, Uri uri) throws Exception {
        CgMediaInfo info = new CgMediaInfo();
        info.uri = uri.toString();
        queryNameAndSize(c, uri, info);
        info.container = containerLabel(info.fileName, safeType(c, uri));

        MediaExtractor ex = new MediaExtractor();
        try {
            try {
                ex.setDataSource(c, uri, null);
            } catch (Exception e) {
                Log.e(TAG, "analyze: setDataSource failed", e);
                throw new Exception("تعذر قراءة الفيديو. الملف تالف أو صيغته غير مدعومة على هذا الهاتف.");
            }
            int count = ex.getTrackCount();
            int videoTrack = -1;
            long maxDurationUs = 0;
            for (int t = 0; t < count; t++) {
                MediaFormat f = ex.getTrackFormat(t);
                String mime = str(f, K_MIME);
                if (f.containsKey(K_DURATION)) maxDurationUs = Math.max(maxDurationUs, lng(f, K_DURATION));
                if (mime.startsWith("video/") && videoTrack < 0) {
                    videoTrack = t;
                    fillVideo(info, f, mime);
                } else if (mime.startsWith("audio/")) {
                    info.audioTracks++;
                    if (!info.hasAudio) fillAudio(info, f, mime);
                } else if (mime.startsWith("text/") || mime.contains("subrip") || mime.contains("subtitle") || mime.contains("vtt")) {
                    info.subtitleTracks++;
                }
            }
            if (!info.hasVideo) throw new Exception("الملف لا يحتوي على مسار فيديو.");
            info.durationMs = maxDurationUs / 1000L;
            if (info.fps <= 0) info.fps = measureFps(ex, videoTrack);
        } finally {
            try {
                ex.release();
            } catch (Exception ignored) { }
        }

        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(c, uri);
            if (info.durationMs <= 0) info.durationMs = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            long overall = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
            if (info.videoBitrate <= 0 && overall > 0) {
                long v = overall - (info.audioBitrate > 0 ? info.audioBitrate : 0);
                info.videoBitrate = Math.max(v, overall * 8 / 10);
                info.videoBitrateEstimated = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "analyze: retriever failed: " + e.getMessage());
        } finally {
            try {
                r.release();
            } catch (Exception ignored) { }
        }

        if (info.videoBitrate <= 0 && info.durationMs > 0 && info.size > 0) {
            long total = info.size * 8L * 1000L / info.durationMs;
            long audio = info.audioBitrate > 0 ? info.audioBitrate : (info.hasAudio ? 192000L : 0L);
            info.videoBitrate = Math.max(total - audio, total * 8 / 10);
            info.videoBitrateEstimated = true;
        }
        return info;
    }

    private static void fillVideo(CgMediaInfo info, MediaFormat f, String mime) {
        info.hasVideo = true;
        info.videoMime = mime;
        info.videoLabel = videoLabel(mime);
        info.width = (int) lng(f, K_WIDTH);
        info.height = (int) lng(f, K_HEIGHT);
        info.rotation = (int) lng(f, K_ROTATION);
        if (f.containsKey(K_FRAME_RATE)) info.fps = snapFps(num(f, K_FRAME_RATE));
        long br = lng(f, K_BITRATE);
        if (br > 0) info.videoBitrate = br;
        int transfer = (int) lng(f, K_COLOR_TRANSFER);
        int profile = f.containsKey(K_PROFILE) ? (int) lng(f, K_PROFILE) : -1;
        boolean dolby = "video/dolby-vision".equals(mime);
        info.hdr = transfer == 6 || transfer == 7 || dolby;
        if ("video/hevc".equals(mime)) info.tenBit = profile == 2 || profile == 4096 || profile == 8192;
        else if ("video/avc".equals(mime)) info.tenBit = profile == 16 || profile == 32 || profile == 64;
        else if ("video/av01".equals(mime)) info.tenBit = profile == 2 || profile == 4096 || profile == 8192;
        else if ("video/x-vnd.on2.vp9".equals(mime)) info.tenBit = profile == 4 || profile == 8 || profile == 4096;
        if (info.tenBit && (profile == 4096 || profile == 8192)) info.hdr = true;
    }

    private static void fillAudio(CgMediaInfo info, MediaFormat f, String mime) {
        info.hasAudio = true;
        info.audioMime = mime;
        info.audioLabel = audioLabel(mime);
        info.audioChannels = (int) lng(f, K_CHANNELS);
        info.audioSampleRate = (int) lng(f, K_SAMPLE_RATE);
        long br = lng(f, K_BITRATE);
        if (br > 0) info.audioBitrate = br;
    }

    /** Median distance between the first presentation times of the video track. */
    private static double measureFps(MediaExtractor ex, int track) {
        try {
            ex.selectTrack(track);
            ArrayList<Long> times = new ArrayList<Long>();
            for (int k = 0; k < 240; k++) {
                long t = ex.getSampleTime();
                if (t < 0) break;
                times.add(Long.valueOf(t));
                if (!ex.advance()) break;
            }
            ex.unselectTrack(track);
            if (times.size() < 12) return 0;
            Collections.sort(times);
            ArrayList<Long> deltas = new ArrayList<Long>();
            for (int i = 1; i < times.size(); i++) {
                long d = times.get(i).longValue() - times.get(i - 1).longValue();
                if (d > 0) deltas.add(Long.valueOf(d));
            }
            if (deltas.isEmpty()) return 0;
            Collections.sort(deltas);
            long median = deltas.get(deltas.size() / 2).longValue();
            return snapFps(1000000.0 / median);
        } catch (Exception e) {
            Log.w(TAG, "measureFps failed: " + e.getMessage());
            return 0;
        }
    }

    static double snapFps(double fps) {
        double[] common = {23.976, 24, 25, 29.97, 30, 48, 50, 59.94, 60, 120};
        for (int i = 0; i < common.length; i++) {
            if (Math.abs(fps - common[i]) / common[i] < 0.012) return common[i];
        }
        return Math.rint(fps * 100.0) / 100.0;
    }

    // ------------------------------------------------------------------ labels

    static String videoLabel(String mime) {
        if ("video/avc".equals(mime)) return "H.264 / AVC";
        if ("video/hevc".equals(mime)) return "H.265 / HEVC";
        if ("video/av01".equals(mime)) return "AV1";
        if ("video/x-vnd.on2.vp9".equals(mime)) return "VP9";
        if ("video/x-vnd.on2.vp8".equals(mime)) return "VP8";
        if ("video/dolby-vision".equals(mime)) return "Dolby Vision";
        if ("video/mp4v-es".equals(mime)) return "MPEG-4";
        if ("video/mpeg2".equals(mime)) return "MPEG-2";
        return mime.startsWith("video/") ? mime.substring(6).toUpperCase(Locale.US) : mime;
    }

    static String audioLabel(String mime) {
        if ("audio/mp4a-latm".equals(mime)) return "AAC";
        if ("audio/ac3".equals(mime)) return "AC-3 (Dolby Digital)";
        if ("audio/eac3".equals(mime)) return "E-AC-3 (Dolby Digital Plus)";
        if ("audio/opus".equals(mime)) return "Opus";
        if ("audio/vorbis".equals(mime)) return "Vorbis";
        if ("audio/flac".equals(mime)) return "FLAC";
        if ("audio/mpeg".equals(mime)) return "MP3";
        if ("audio/raw".equals(mime)) return "PCM";
        if (mime.contains("dts") || mime.contains("vnd.dts")) return "DTS";
        return mime.startsWith("audio/") ? mime.substring(6).toUpperCase(Locale.US) : mime;
    }

    private static String containerLabel(String fileName, String mimeType) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if (n.endsWith(".mkv") || (mimeType != null && mimeType.contains("matroska"))) return "MKV";
        if (n.endsWith(".webm") || (mimeType != null && mimeType.contains("webm"))) return "WebM";
        if (n.endsWith(".mov") || (mimeType != null && mimeType.contains("quicktime"))) return "MOV";
        if (n.endsWith(".mp4") || n.endsWith(".m4v") || (mimeType != null && mimeType.contains("mp4"))) return "MP4";
        return "—";
    }

    /** mp4 / mkv / mov / webm only. */
    static boolean supportedName(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".mov") || n.endsWith(".webm") || n.endsWith(".m4v");
    }

    // ----------------------------------------------------------------- helpers

    static void queryNameAndSize(Context c, Uri uri, CgMediaInfo info) {
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = cur.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !cur.isNull(ni)) info.fileName = cur.getString(ni);
                if (si >= 0 && !cur.isNull(si)) info.size = cur.getLong(si);
            }
        } catch (Exception e) {
            Log.w(TAG, "query name/size failed: " + e.getMessage());
        } finally {
            if (cur != null) cur.close();
        }
        if (info.fileName == null || info.fileName.length() == 0) {
            String p = uri.getLastPathSegment();
            info.fileName = p == null ? "video" : p.substring(p.lastIndexOf('/') + 1);
        }
        if (info.size <= 0) info.size = sizeFromDescriptor(c, uri);
    }

    private static long sizeFromDescriptor(Context c, Uri uri) {
        android.os.ParcelFileDescriptor pfd = null;
        try {
            pfd = c.getContentResolver().openFileDescriptor(uri, "r");
            return pfd == null ? 0 : pfd.getStatSize();
        } catch (Exception e) {
            return 0;
        } finally {
            try {
                if (pfd != null) pfd.close();
            } catch (Exception ignored) { }
        }
    }

    private static String safeType(Context c, Uri uri) {
        try {
            return c.getContentResolver().getType(uri);
        } catch (Exception e) {
            return null;
        }
    }

    static String str(MediaFormat f, String key) {
        try {
            return f.containsKey(key) ? f.getString(key) : "";
        } catch (Exception e) {
            return "";
        }
    }

    static long lng(MediaFormat f, String key) {
        try {
            return f.containsKey(key) ? f.getLong(key) : 0;
        } catch (Exception e) {
            try {
                return f.getInteger(key);
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    private static double num(MediaFormat f, String key) {
        try {
            return f.getInteger(key);
        } catch (Exception e) {
            try {
                return f.getFloat(key);
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    private static long parseLong(String v) {
        try {
            return v == null ? 0 : Long.parseLong(v.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
