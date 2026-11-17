package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 18.3 — one refresh policy for the catalogue / channel list: TTL, single flight, back-off, navigation without downloads. */
public class CigramRefreshPolicyTest {

    private static final long MIN = 60000L;
    private static final CigramRefreshPolicy.Reason TAB = CigramRefreshPolicy.Reason.TAB_SHOWN;

    private static CigramRefreshPolicy.State state(long now, long confirmedAgo, long attemptAgo) {
        CigramRefreshPolicy.State s = new CigramRefreshPolicy.State();
        s.now = now;
        s.snapshotConfirmedAt = confirmedAgo < 0 ? 0L : now - confirmedAgo;
        s.lastAttemptAt = attemptAgo < 0 ? 0L : now - attemptAgo;
        s.jitterSeed = 7;
        return s;
    }

    @Test public void onlyOneRefreshRunsAtATime() {
        CigramRefreshPolicy.State s = state(10 * MIN, 99 * MIN, 99 * MIN);
        s.running = true;
        for (CigramRefreshPolicy.Reason r : CigramRefreshPolicy.Reason.values()) assertFalse(r.name(), CigramRefreshPolicy.shouldRefresh(r, s));
    }

    @Test public void aFreshSnapshotIsNotDownloadedAgainWhenAPageIsOpened() {
        CigramRefreshPolicy.State s = state(100 * MIN, 30000L, 30000L);       // confirmed 30 s ago
        assertFalse(CigramRefreshPolicy.shouldRefresh(TAB, s));
        s = state(100 * MIN, 4 * MIN, 4 * MIN);
        assertFalse("4 minutes is inside the 5-minute TTL", CigramRefreshPolicy.shouldRefresh(TAB, s));
        s = state(100 * MIN, 5 * MIN + 1000, 5 * MIN + 1000);
        assertTrue(CigramRefreshPolicy.shouldRefresh(TAB, s));
    }

    @Test public void coldStartDoesNotRedownloadWhatWasConfirmedMomentsAgo() {
        assertFalse(CigramRefreshPolicy.shouldRefresh(CigramRefreshPolicy.Reason.COLD_START, state(100 * MIN, 90000L, 90000L)));
        assertTrue(CigramRefreshPolicy.shouldRefresh(CigramRefreshPolicy.Reason.COLD_START, state(100 * MIN, 3 * MIN, 3 * MIN)));
    }

    @Test public void theUserCanAlwaysForceARefresh() {
        assertTrue(CigramRefreshPolicy.shouldRefresh(CigramRefreshPolicy.Reason.FORCED, state(100 * MIN, 1000L, 1000L)));
    }

    @Test public void withoutASnapshotTheFirstAskIsAllowedButNotMoreOftenThanEvery15Seconds() {
        assertTrue(CigramRefreshPolicy.shouldRefresh(TAB, state(100 * MIN, -1, -1)));
        assertFalse(CigramRefreshPolicy.shouldRefresh(TAB, state(100 * MIN, -1, 5000L)));
        assertTrue(CigramRefreshPolicy.shouldRefresh(TAB, state(100 * MIN, -1, 16000L)));
    }

    @Test public void failuresBackOffExponentiallyWithJitterAndACap() {
        long b1 = CigramRefreshPolicy.backoffMs(1, false, 1), b2 = CigramRefreshPolicy.backoffMs(2, false, 1), b3 = CigramRefreshPolicy.backoffMs(3, false, 1);
        assertTrue("jitter stays within +-25 % of 30 s", b1 >= 22500L && b1 <= 37500L);
        assertTrue(b2 > b1 && b3 > b2);
        for (long seed = 0; seed < 200; seed++) {
            long b = CigramRefreshPolicy.backoffMs(12, false, seed);
            assertTrue("capped at 15 minutes", b <= CigramRefreshPolicy.BACKOFF_CAP_MS && b >= 1000L);
        }
        assertEquals(0L, CigramRefreshPolicy.backoffMs(0, false, 1));
        long permanent = CigramRefreshPolicy.backoffMs(1, true, 1);
        assertTrue("a permanent 4xx is not retried quickly", permanent >= 11 * MIN);
    }

    @Test public void aFailingServerIsNotHammeredOnEveryTabSwitch() {
        // the saved snapshot is 10 minutes old (stale), 3 refreshes failed, the last attempt was 10 s ago
        CigramRefreshPolicy.State s = state(100 * MIN, 10 * MIN, 10000L);
        s.consecutiveFailures = 3;
        assertFalse("inside the back-off", CigramRefreshPolicy.shouldRefresh(TAB, s));
        s = state(100 * MIN, 20 * MIN, 5 * MIN);
        s.consecutiveFailures = 3;
        assertTrue("after the back-off it may try again", CigramRefreshPolicy.shouldRefresh(TAB, s));
        s = state(100 * MIN, 20 * MIN, 20000L);
        s.consecutiveFailures = 1;
        s.lastFailurePermanent = true;
        assertFalse(CigramRefreshPolicy.shouldRefresh(TAB, s));
    }

    @Test public void meteredAndLowRamNetworksUseALongerTtlForBackgroundRefreshes() {
        CigramRefreshPolicy.State s = state(100 * MIN, 8 * MIN, 8 * MIN);
        assertTrue(CigramRefreshPolicy.shouldRefresh(TAB, s));
        s.metered = true;
        assertFalse("15-minute TTL on a metered network", CigramRefreshPolicy.shouldRefresh(TAB, s));
        s = state(100 * MIN, 8 * MIN, 8 * MIN);
        s.lowRam = true;
        assertFalse(CigramRefreshPolicy.shouldRefresh(TAB, s));
        s = state(100 * MIN, 16 * MIN, 16 * MIN);
        s.metered = true;
        assertTrue(CigramRefreshPolicy.shouldRefresh(TAB, s));
    }

    @Test public void theChannelListKeepsItsOwnThreeMinuteTtl() {
        long ttl = 3 * MIN;
        assertFalse(CigramRefreshPolicy.shouldRefresh(TAB, state(100 * MIN, 2 * MIN, 2 * MIN), ttl));
        assertTrue(CigramRefreshPolicy.shouldRefresh(TAB, state(100 * MIN, 3 * MIN + 1000, 3 * MIN + 1000), ttl));
    }

    @Test public void aClockThatMovedBackwardsDoesNotBlockRefreshesForever() {
        CigramRefreshPolicy.State s = state(100 * MIN, 0L, 0L);
        s.snapshotConfirmedAt = 100 * MIN + 2 * 60 * MIN;
        s.lastAttemptAt = s.snapshotConfirmedAt;
        assertTrue(CigramRefreshPolicy.shouldRefresh(TAB, s));
    }

    // ---------------------------------------------------------------- before / after: a viewer moving between Home, Search and Channels
    /** The 18.2 behaviour: every tab switch (at most once per 20 s per tab) forced a download unless one had succeeded < 120 s ago. */
    private static int oldDownloads(int switches, long gapMs) {
        int downloads = 0;
        long lastSuccess = -1_000_000L;
        long lastPerTab[] = {-1_000_000L, -1_000_000L, -1_000_000L};
        for (int i = 0; i < switches; i++) {
            long now = i * gapMs;
            int tab = i % 3;
            if (now - lastPerTab[tab] < 20000L) continue;
            lastPerTab[tab] = now;
            if (now - lastSuccess >= 120000L) { downloads++; lastSuccess = now; }
        }
        return downloads;
    }

    private static int newDownloads(int switches, long gapMs, boolean metered) {
        int downloads = 0;
        final long epoch = 1_000_000L;
        long confirmed = epoch, attempt = epoch;       // a snapshot was confirmed at the cold start
        for (int i = 0; i < switches; i++) {
            long now = epoch + i * gapMs;
            CigramRefreshPolicy.State s = new CigramRefreshPolicy.State();
            s.now = now; s.snapshotConfirmedAt = confirmed; s.lastAttemptAt = attempt; s.metered = metered; s.jitterSeed = i;
            if (CigramRefreshPolicy.shouldRefresh(TAB, s)) { downloads++; confirmed = now; attempt = now; }
        }
        return downloads;
    }

    @Test public void navigatingBetweenPagesForHalfAnHourDownloadsFarLess() {
        int switches = 60;                 // one page change every 30 s for 30 minutes
        int before = oldDownloads(switches, 30000L);
        int after = newDownloads(switches, 30000L, false);
        int afterMetered = newDownloads(switches, 30000L, true);
        System.out.println("catalogue downloads in 30 min of navigation: before=" + before + " after=" + after + " after(metered)=" + afterMetered);
        assertTrue("before: a download every ~2 minutes", before >= 12);
        assertTrue("after: one per 5-minute TTL at most", after <= 6);
        assertTrue("metered: one per 15 minutes at most", afterMetered <= 2);
        assertTrue(after * 2 <= before);
    }

    @Test public void tenQuickPageChangesInAMinuteDownloadNothing() {
        assertEquals(0, newDownloads(10, 6000L, false));
    }
}
