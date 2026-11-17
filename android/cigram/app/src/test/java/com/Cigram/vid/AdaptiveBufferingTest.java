package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.MediaPeriodId;

import org.junit.Test;

/** 18.3 — adaptive start / re-buffer thresholds: slow network, stalls, recovery, device classes (no Android needed). */
public class AdaptiveBufferingTest {

    private static final CigramBufferTuner.Net WIFI_FAST = new CigramBufferTuner.Net(CigramBufferTuner.NET_WIFI, false, 80000);
    private static final CigramBufferTuner.Net WIFI_UNKNOWN_SPEED = new CigramBufferTuner.Net(CigramBufferTuner.NET_WIFI, false, 0);
    private static final CigramBufferTuner.Net CELL_METERED = new CigramBufferTuner.Net(CigramBufferTuner.NET_CELLULAR, true, 6000);
    private static final CigramBufferTuner.Net SLOW_2G = new CigramBufferTuner.Net(CigramBufferTuner.NET_CELLULAR, true, 900);
    private static final CigramBufferPolicy NORMAL = CigramBufferPolicy.forDevice(false, 256);   // 20 s / 75 s
    private static final CigramBufferPolicy LOW_RAM = CigramBufferPolicy.forDevice(true, 96);    // 10 s / 30 s

    private static final class Fake implements CigramAdaptiveLoadControl.NetSource, CigramAdaptiveLoadControl.Clock {
        volatile CigramBufferTuner.Net net;
        volatile long now = 100000L;
        Fake(CigramBufferTuner.Net net) { this.net = net; }
        @Override public CigramBufferTuner.Net read() { return net; }
        @Override public long now() { return now; }
    }

    private static boolean start(CigramAdaptiveLoadControl lc, long bufferedMs, boolean rebuffering) {
        return lc.shouldStartPlayback(Timeline.EMPTY, new MediaPeriodId(new Object()), bufferedMs * 1000L, 1f, rebuffering, C.TIME_UNSET);
    }

    // ---------------------------------------------------------------- profiles
    @Test public void goodConnectionStartsAlmostAtOnce() {
        CigramBufferTuner t = new CigramBufferTuner();
        CigramBufferTuner.Profile p = t.profile(1000, WIFI_FAST, NORMAL, false);
        assertEquals(0, p.level);
        assertEquals(800, p.startMs);
        assertEquals(1500, p.rebufferMs);
        assertEquals("the device-class window is untouched on a good network", NORMAL.minMs, p.minMs);
        assertEquals(NORMAL.maxMs, p.maxMs);
        assertEquals(0, p.maxVideoBitrate);
    }

    @Test public void meteredCellularIsWeakAndWaitsForMoreVideo() {
        CigramBufferTuner.Profile p = new CigramBufferTuner().profile(1000, CELL_METERED, NORMAL, false);
        assertEquals(1, p.level);
        assertTrue("longer than the old 0.7 s start", p.startMs >= 2500);
        assertTrue("longer than the old 1 s re-buffer", p.rebufferMs >= 5000);
        assertTrue("metered: no minutes of pre-loading", p.maxMs <= 40000);
        assertTrue(p.maxVideoBitrate > 0 && p.maxVideoBitrate < 5000000);
    }

    @Test public void aVerySlowLinkGetsTheLargestProtectionAndALowerBitrateCeiling() {
        CigramBufferTuner.Profile p = new CigramBufferTuner().profile(1000, SLOW_2G, NORMAL, false);
        assertEquals(2, p.level);
        assertEquals(4000, p.startMs);
        assertEquals(8000, p.rebufferMs);
        assertTrue(p.maxVideoBitrate <= 1200000);
    }

    @Test public void lowRamPhonesNeverExceedTheirDeviceClassWindow() {
        for (CigramBufferTuner.Net n : new CigramBufferTuner.Net[]{WIFI_FAST, CELL_METERED, SLOW_2G}) {
            CigramBufferTuner.Profile p = new CigramBufferTuner().profile(1000, n, LOW_RAM, false);
            assertTrue("max <= device max", p.maxMs <= LOW_RAM.maxMs);
            assertTrue("min <= max", p.minMs <= p.maxMs);
        }
    }

    @Test public void thePlayerAlwaysGetsAConsistentWindow() {
        CigramBufferTuner.Net[] nets = {WIFI_FAST, WIFI_UNKNOWN_SPEED, CELL_METERED, SLOW_2G, new CigramBufferTuner.Net(CigramBufferTuner.NET_NONE, false, 0), null};
        for (CigramBufferPolicy base : new CigramBufferPolicy[]{NORMAL, LOW_RAM, CigramBufferPolicy.custom(30000, 120000, 0)}) {
            for (CigramBufferTuner.Net n : nets) {
                for (boolean live : new boolean[]{false, true}) {
                    CigramBufferTuner.Profile p = new CigramBufferTuner().profile(5000, n, base, live);
                    // ExoPlayer requires bufferForPlayback <= bufferForPlaybackAfterRebuffer?  no: both <= minBuffer <= maxBuffer
                    assertTrue(p.startMs <= p.minMs);
                    assertTrue(p.rebufferMs <= p.minMs);
                    assertTrue(p.minMs <= p.maxMs);
                    assertTrue(p.maxMs <= Math.max(base.maxMs, 30000));
                    if (live) assertTrue("live keeps a short window", p.maxMs <= 30000 && p.minMs <= 10000);
                }
            }
        }
    }

    // ---------------------------------------------------------------- stalls and recovery
    @Test public void repeatedStallsEscalateTheLevelAndAQuietPeriodRestoresIt() {
        CigramBufferTuner t = new CigramBufferTuner();
        long now = 1000000L;
        assertEquals(0, t.level(now, WIFI_FAST));
        t.recordStall(now);
        assertEquals("one stall: be careful for a minute", 1, t.level(now + 1000, WIFI_FAST));
        assertEquals("...and relax again", 0, t.level(now + 90000, WIFI_FAST));
        t.recordStall(now + 100000);
        t.recordStall(now + 110000);
        assertEquals(1, t.level(now + 115000, WIFI_FAST));
        t.recordStall(now + 120000);
        t.recordStall(now + 125000);
        assertEquals("four stalls in three minutes: very weak", 2, t.level(now + 130000, WIFI_FAST));
        assertEquals("five quiet minutes: back to what the network says", 0, t.level(now + 125000 + CigramBufferTuner.QUIET_DECAY_MS + CigramBufferTuner.STALL_WINDOW_MS, WIFI_FAST));
    }

    @Test public void theLoadControlRaisesItsThresholdAfterRealStallsOnly() {
        Fake f = new Fake(WIFI_FAST);
        CigramAdaptiveLoadControl lc = CigramAdaptiveLoadControl.create(f, f, NORMAL, false);
        assertFalse("0.5 s is not enough even on a good network", start(lc, 500, true));
        assertTrue("1.6 s is enough on a good network", start(lc, 1600, true));
        // a quick seek (answered within the stall threshold) is not a stall
        f.now += 300;
        start(lc, 100, true);
        f.now += 400;
        assertTrue(start(lc, 1600, true));
        assertEquals(0, lc.currentProfile().level);
        // three genuine stalls: waited > 1.5 s each before the buffer was enough
        for (int i = 0; i < 3; i++) {
            f.now += 2000;
            assertFalse(start(lc, 200, true));
            f.now += 2000;
            assertTrue("resumes once the (growing) threshold is met", start(lc, 6000, true));
            f.now += 1200;                           // the profile is re-evaluated at most once a second
        }
        assertTrue("stalls lift the level", lc.currentProfile().level >= 1);
        assertFalse("1.6 s no longer restarts playback after a stall", start(lc, 1600, true));
        assertTrue(start(lc, 5200, true));
    }

    @Test public void weakNetworkNeedsMoreBufferBeforeFirstPlayback() {
        Fake f = new Fake(CELL_METERED);
        CigramAdaptiveLoadControl lc = CigramAdaptiveLoadControl.create(f, f, NORMAL, false);
        assertFalse("the old 0.7 s start is not enough here", start(lc, 800, false));
        assertFalse(start(lc, 2000, false));
        assertTrue(start(lc, 2600, false));
    }

    @Test public void recoveryAfterTheNetworkImproves() {
        Fake f = new Fake(SLOW_2G);
        CigramAdaptiveLoadControl lc = CigramAdaptiveLoadControl.create(f, f, NORMAL, false);
        assertEquals(2, lc.currentProfile().level);
        f.net = WIFI_FAST;
        f.now += 2000;
        assertEquals("network back to good: fast start again", 0, lc.currentProfile().level);
        assertTrue(start(lc, 1000, false));
    }

    @Test public void theAdaptiveControlKeepsTheDefaultsThePlayerNeeds() {
        Fake f = new Fake(WIFI_FAST);
        CigramAdaptiveLoadControl lc = CigramAdaptiveLoadControl.create(f, f, NORMAL, false);
        assertEquals("back buffer of the device class is kept", NORMAL.backMs * 1000L, lc.getBackBufferDurationUs());
        assertTrue(lc.retainBackBufferFromKeyframe());
    }

    // ---------------------------------------------------------------- segment accelerator
    @Test public void acceleratorIsConservativeOnWeakAndMeteredNetworks() {
        long mb = 1024L * 1024L;
        CigramAccelPolicy good = CigramAccelPolicy.decide(0, false, false, 4, 4, 64 * mb);
        assertEquals(4, good.maxParts); assertEquals(4, good.maxAhead); assertEquals(64 * mb, good.budgetBytes);
        CigramAccelPolicy weak = CigramAccelPolicy.decide(1, false, false, 4, 4, 64 * mb);
        assertEquals(2, weak.maxParts); assertEquals(2, weak.maxAhead); assertEquals(32 * mb, weak.budgetBytes);
        CigramAccelPolicy veryWeak = CigramAccelPolicy.decide(2, false, false, 4, 4, 64 * mb);
        assertEquals("one connection per piece", 1, veryWeak.maxParts);
        assertEquals(1, veryWeak.maxAhead); assertEquals(16 * mb, veryWeak.budgetBytes);
        CigramAccelPolicy metered = CigramAccelPolicy.decide(0, false, true, 4, 4, 64 * mb);
        assertEquals("a fast but metered link still does not pre-load far ahead", 2, metered.maxAhead);
        assertEquals(32 * mb, metered.budgetBytes);
        CigramAccelPolicy lowRam = CigramAccelPolicy.decide(0, true, false, 4, 4, 64 * mb);
        assertEquals(32 * mb, lowRam.budgetBytes);
        CigramAccelPolicy lowRamWeak = CigramAccelPolicy.decide(2, true, true, 4, 4, 64 * mb);
        assertTrue(lowRamWeak.budgetBytes >= 8 * mb && lowRamWeak.budgetBytes <= 16 * mb);
        assertTrue(lowRamWeak.maxParts >= 1 && lowRamWeak.maxAhead >= 1);
    }

    @Test public void theLoadControlPushesItsVerdictToTheAccelerator() throws Exception {
        Fake f = new Fake(SLOW_2G);
        CigramAdaptiveLoadControl lc = CigramAdaptiveLoadControl.create(f, f, NORMAL, false);
        lc.currentProfile();
        java.lang.reflect.Field fld = CigramSegmentAccel.class.getDeclaredField("policy");
        fld.setAccessible(true);
        CigramAccelPolicy applied = (CigramAccelPolicy) fld.get(null);
        assertEquals(1, applied.maxParts);
        f.net = WIFI_FAST;
        f.now += 2000;
        lc.currentProfile();
        applied = (CigramAccelPolicy) fld.get(null);
        assertEquals("a recovered connection gets the full acceleration back", 4, applied.maxParts);
    }
}
