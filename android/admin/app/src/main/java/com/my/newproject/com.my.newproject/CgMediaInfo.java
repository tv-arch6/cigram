package com.my.newproject;

import java.util.Locale;

/** What the analyzer read from the real video file. Nothing here is a fixed / fake value. */
final class CgMediaInfo {

    String uri = "";
    String fileName = "";
    long size;
    String container = "";
    long durationMs;

    boolean hasVideo;
    String videoMime = "";
    String videoLabel = "";
    int width;
    int height;
    int rotation;
    double fps;
    long videoBitrate;
    boolean videoBitrateEstimated;
    boolean hdr;
    boolean tenBit;

    boolean hasAudio;
    String audioMime = "";
    String audioLabel = "";
    long audioBitrate;
    int audioChannels;
    int audioSampleRate;
    int audioTracks;

    int subtitleTracks;

    boolean isHevc() {
        return "video/hevc".equals(videoMime);
    }

    boolean isAvc() {
        return "video/avc".equals(videoMime);
    }

    boolean audioIsAac() {
        return "audio/mp4a-latm".equals(audioMime);
    }

    String resolutionText() {
        return width > 0 && height > 0 ? width + "x" + height : "—";
    }

    String fpsText() {
        if (fps <= 0) return "—";
        if (Math.abs(fps - Math.rint(fps)) < 0.005) return String.valueOf((long) Math.rint(fps));
        return String.format(Locale.US, "%.3f", fps);
    }

    static String bitrateText(long bps, boolean estimated) {
        if (bps <= 0) return "—";
        String v;
        if (bps >= 1000000L) v = String.format(Locale.US, "%.1f Mbps", bps / 1000000.0);
        else v = String.format(Locale.US, "%d Kbps", bps / 1000L);
        return estimated ? "≈ " + v : v;
    }
}
