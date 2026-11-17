package com.Cigram.vid;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.MediaPeriodId;
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import com.google.android.exoplayer2.util.Util;

/**
 * DefaultLoadControl whose START and RE-BUFFER thresholds follow the connection (see {@link CigramBufferTuner}).
 *
 * What changes compared with the fixed 0.7 s / 1 s of the movie player and 1.5 s / 3 s of the live player:
 *  - good connection (the level the old values were written for): 0.8 s / 1.5 s, i.e. start almost at once;
 *  - weak connection or after repeated stalls: 2.5 s .. 4 s to start and 5 s .. 8 s to resume, with a larger look-ahead
 *    where the RAM class allows it, so a stall is followed by enough video to survive the next dip;
 *  - nothing is restarted, recreated or re-prepared: only the "may I start now?" answer changes, so the playback position
 *    and the loaded source are untouched.
 * The device-class limits of {@link CigramBufferPolicy} are the ceiling.
 */
final class CigramAdaptiveLoadControl extends DefaultLoadControl {

    /** Source of the network facts (a fake in tests). */
    interface NetSource { CigramBufferTuner.Net read(); }

    /** Clock (a fake in tests). */
    interface Clock { long now(); }

    private final CigramBufferTuner tuner = new CigramBufferTuner();
    private final NetSource netSource;
    private final Clock clock;
    private final CigramBufferPolicy base;
    private final boolean live;
    private volatile CigramBufferTuner.Profile profile;
    private volatile long profileAt;

    static CigramAdaptiveLoadControl create(final Context context, CigramBufferPolicy base, boolean live) {
        final Context app = context.getApplicationContext();
        NetSource net = new NetSource() {
            @Override public CigramBufferTuner.Net read() { return readNet(app); }
        };
        Clock clock = new Clock() {
            @Override public long now() { return SystemClock.elapsedRealtime(); }
        };
        return create(net, clock, base, live);
    }

    static CigramAdaptiveLoadControl create(NetSource netSource, Clock clock, CigramBufferPolicy base, boolean live) {
        CigramBufferTuner t0 = new CigramBufferTuner();
        CigramBufferTuner.Profile first = t0.profile(clock.now(), netSource.read(), base, live);
        return new CigramAdaptiveLoadControl(netSource, clock, base, live, first);
    }

    private CigramAdaptiveLoadControl(NetSource netSource, Clock clock, CigramBufferPolicy base, boolean live, CigramBufferTuner.Profile first) {
        super(new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE), first.minMs, first.maxMs, first.startMs, first.rebufferMs,
                DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES, true, base.backMs, true);
        this.netSource = netSource;
        this.clock = clock;
        this.base = base;
        this.live = live;
        this.profile = first;
        this.profileAt = clock.now();
        publish(first, netSource.read());
    }

    /** The segment accelerator (embed hosts) follows the same verdict: fewer connections / less look-ahead on a weak link. */
    private void publish(CigramBufferTuner.Profile p, CigramBufferTuner.Net net) {
        try { CigramSegmentAccel.applyLevel(p.level, base.maxMs <= 30000, net != null && net.metered); } catch (Throwable ignored) { }
    }

    /** The current profile, re-evaluated at most once a second (this is called from the playback thread every few ms). */
    CigramBufferTuner.Profile currentProfile() {
        long now = clock.now();
        CigramBufferTuner.Profile p = profile;
        if (p == null || now - profileAt >= 1000L) {
            CigramBufferTuner.Net net = netSource.read();
            p = tuner.profile(now, net, base, live);
            profile = p;
            profileAt = now;
            publish(p, net);
        }
        return p;
    }

    /** Bitrate ceiling for multi-quality sources at the moment (0 = none). Single-file MP4 ignores it. */
    int maxVideoBitrate() {
        return currentProfile().maxVideoBitrate;
    }

    @Override
    public boolean shouldStartPlayback(Timeline timeline, MediaPeriodId mediaPeriodId, long bufferedDurationUs,
                                       float playbackSpeed, boolean rebuffering, long targetLiveOffsetUs) {
        CigramBufferTuner.Profile p = currentProfile();
        long thresholdUs = (rebuffering ? p.rebufferMs : p.startMs) * 1000L;
        if (playbackSpeed != 1f) {
            bufferedDurationUs = Util.getMediaDurationForPlayoutDuration(bufferedDurationUs, playbackSpeed);
        }
        if (targetLiveOffsetUs != C.TIME_UNSET) thresholdUs = Math.min(targetLiveOffsetUs / 2, thresholdUs);
        boolean start = thresholdUs <= 0L || bufferedDurationUs >= thresholdUs;
        long now = clock.now();
        if (start) tuner.onStarted(now, rebuffering);
        else tuner.onWaiting(now);
        return start;
    }

    // ------------------------------------------------------------------ network facts

    private static volatile CigramBufferTuner.Net lastNet;
    private static volatile long lastNetAt;

    static CigramBufferTuner.Net readNet(Context app) {
        long now = SystemClock.elapsedRealtime();
        CigramBufferTuner.Net cached = lastNet;
        if (cached != null && now - lastNetAt < 5000L) return cached;
        CigramBufferTuner.Net n = new CigramBufferTuner.Net(CigramBufferTuner.NET_UNKNOWN, false, 0);
        try {
            ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
            if (caps == null) {
                n = new CigramBufferTuner.Net(CigramBufferTuner.NET_NONE, false, 0);
            } else {
                int type = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? CigramBufferTuner.NET_WIFI
                        : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? CigramBufferTuner.NET_ETHERNET
                        : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? CigramBufferTuner.NET_CELLULAR
                        : CigramBufferTuner.NET_UNKNOWN;
                boolean metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                n = new CigramBufferTuner.Net(type, metered, caps.getLinkDownstreamBandwidthKbps());
            }
        } catch (Throwable ignored) { }
        lastNet = n;
        lastNetAt = now;
        return n;
    }
}
