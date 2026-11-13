package com.Cigram.vid;

/**
 * Adaptive start / re-buffer thresholds for the players — pure Java, unit-tested.
 *
 * Audit finding (18.3): the movie player started after 0.7 s of video and resumed after a stall with only 1 s, the live
 * player after 1.5 s / 3 s, whatever the network was. On a weak connection a stall is followed by a one-second buffer, which
 * the same connection drains at once: the picture freezes again and again ("rebuffer storm"). The device-class limits of
 * {@link CigramBufferPolicy} (minimum / maximum / back buffer by RAM) are kept; this class only decides HOW MUCH must be
 * buffered before playback (re)starts and how far ahead the player may load, from:
 *   - the network (type, metered, the system's link-speed estimate),
 *   - what really happened: stalls of the last minutes (the one signal that is true on every network),
 *   - the device class (low-RAM phones never get a bigger buffer than {@link CigramBufferPolicy} allows).
 *
 * Levels: 0 = good (unchanged behaviour for good connections), 1 = weak, 2 = very weak. A level only moves up after real
 * stalls and moves down again after a quiet period, so quality of the connection decides, not a one-off measurement.
 */
final class CigramBufferTuner {

    static final int NET_UNKNOWN = 0, NET_WIFI = 1, NET_ETHERNET = 2, NET_CELLULAR = 3, NET_NONE = 4;

    /** What the Android side tells us about the connection. */
    static final class Net {
        final int type;
        final boolean metered;
        /** System estimate of the downlink in kbit/s (0 = unknown). It is an estimate: never the only reason for a decision. */
        final int downKbps;

        Net(int type, boolean metered, int downKbps) {
            this.type = type;
            this.metered = metered;
            this.downKbps = downKbps;
        }
    }

    /** The decision. */
    static final class Profile {
        final int level;
        final int startMs;
        final int rebufferMs;
        final int minMs;
        final int maxMs;
        /** 0 = no cap; otherwise the video bitrate a multi-quality (HLS / DASH) source should not exceed. Single-file MP4 is unaffected. */
        final int maxVideoBitrate;

        Profile(int level, int startMs, int rebufferMs, int minMs, int maxMs, int maxVideoBitrate) {
            this.level = level;
            this.startMs = startMs;
            this.rebufferMs = rebufferMs;
            this.minMs = minMs;
            this.maxMs = maxMs;
            this.maxVideoBitrate = maxVideoBitrate;
        }
    }

    static final long STALL_WINDOW_MS = 3L * 60L * 1000L;
    static final long QUIET_DECAY_MS = 5L * 60L * 1000L;
    /** A wait for the buffer shorter than this is a seek, not a network stall. */
    static final long MIN_STALL_MS = 1500L;
    static final long MIN_FIRST_START_MS = 4000L;

    private final long[] stalls = new long[16];
    private int stallCount;
    private long lastStallAt;
    private long waitStartedAt;

    /** The player asked "may I start?" and the answer was no (buffer too small). Call with the clock. */
    synchronized void onWaiting(long now) {
        if (waitStartedAt == 0L) waitStartedAt = now;
    }

    /**
     * The answer was yes. A wait counts as a stall when it lasted {@link #MIN_STALL_MS} after a stall / seek (re-buffering) or
     * {@link #MIN_FIRST_START_MS} at the very first start (a slow first start is the same signal).
     */
    synchronized void onStarted(long now, boolean rebuffering) {
        long need = rebuffering ? MIN_STALL_MS : MIN_FIRST_START_MS;
        if (waitStartedAt > 0L && now - waitStartedAt >= need) recordStall(now);
        waitStartedAt = 0L;
    }

    synchronized void recordStall(long now) {
        if (stallCount < stalls.length) stalls[stallCount++] = now;
        else {
            System.arraycopy(stalls, 1, stalls, 0, stalls.length - 1);
            stalls[stalls.length - 1] = now;
        }
        lastStallAt = now;
    }

    synchronized int recentStalls(long now) {
        int n = 0;
        for (int i = 0; i < stallCount; i++) if (now - stalls[i] <= STALL_WINDOW_MS) n++;
        return n;
    }

    /** Level from the network alone (before any stall is seen). */
    static int levelFromNet(Net n) {
        if (n == null) return 0;
        if (n.type == NET_NONE) return 2;
        int kbps = n.downKbps;
        if (kbps > 0 && kbps < 1500) return 2;
        if (kbps > 0 && kbps < 4000) return 1;
        if (n.type == NET_CELLULAR) return kbps >= 20000 && !n.metered ? 0 : 1;   // cellular is weak unless the system says it is fast and unmetered
        if (n.metered && kbps == 0) return 1;
        return 0;
    }

    synchronized int level(long now, Net net) {
        int level = levelFromNet(net);
        int recent = recentStalls(now);
        if (recent >= 4) level = 2;
        else if (recent >= 2) level = Math.max(level, 1);
        else if (recent == 1 && level == 0 && now - lastStallAt < 60L * 1000L) level = 1;
        // a connection that has been fine for a while earns its fast start back, but never below what the network alone says
        if (recent == 0 && lastStallAt > 0L && now - lastStallAt >= QUIET_DECAY_MS) level = levelFromNet(net);
        return level;
    }

    /**
     * @param base the device-class limits (RAM): the result never exceeds them
     * @param live true for the live player: live keeps a short window (a long buffer is behind the live edge)
     */
    synchronized Profile profile(long now, Net net, CigramBufferPolicy base, boolean live) {
        int level = level(now, net);
        int minMs = base.minMs, maxMs = base.maxMs;
        int startMs, rebufferMs, cap = 0;
        boolean ram = base.maxMs > 30000;           // false on low-RAM / small-heap phones (30 s max)
        switch (level) {
            case 2:
                startMs = live ? 3000 : 4000;
                rebufferMs = live ? 5000 : 8000;
                minMs = Math.min(maxMs, Math.max(minMs, ram ? 30000 : 15000));
                maxMs = Math.min(base.maxMs, ram ? 90000 : 30000);
                cap = netCap(net, 700, 1200);
                break;
            case 1:
                startMs = live ? 2500 : 2500;
                rebufferMs = live ? 4000 : 5000;
                minMs = Math.min(maxMs, Math.max(minMs, ram ? 20000 : 10000));
                maxMs = Math.min(base.maxMs, ram ? 60000 : 30000);
                cap = netCap(net, 1500, 2800);
                break;
            default:
                startMs = live ? 1500 : 800;        // good connection: start almost at once
                rebufferMs = live ? 3000 : 1500;
                break;
        }
        if (net != null && net.metered && level > 0) maxMs = Math.min(maxMs, 40000);   // metered: do not pre-load minutes of video
        if (live) { minMs = Math.min(minMs, 10000); maxMs = Math.min(maxMs, 30000); }
        if (minMs > maxMs) minMs = maxMs;
        // the player requires bufferForPlayback <= minBuffer
        if (startMs > minMs) startMs = minMs;
        if (rebufferMs > minMs) rebufferMs = minMs;
        return new Profile(level, startMs, rebufferMs, minMs, maxMs, cap);
    }

    /** A bitrate ceiling of ~70 % of the link estimate (floor/ceiling in kbit/s given), 0 when the estimate is unknown. */
    private static int netCap(Net net, int floorKbps, int fallbackKbps) {
        if (net == null || net.downKbps <= 0) return fallbackKbps * 1000;
        int kbps = (int) Math.max(floorKbps, net.downKbps * 0.7);
        return kbps * 1000;
    }
}
