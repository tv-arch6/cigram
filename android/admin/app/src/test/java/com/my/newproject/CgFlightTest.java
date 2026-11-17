package com.my.newproject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/** 18.3 — the admin app loads one dataset at a time, keeps edits, and does not reload a young snapshot. */
public class CgFlightTest {

    @Test public void concurrentCallersShareOneNetworkCall() throws Exception {
        final CgFlight<String> flight = new CgFlight<String>();
        final AtomicInteger networkCalls = new AtomicInteger();
        final AtomicInteger answered = new AtomicInteger();
        final int n = 12;
        final CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final String who = "screen" + i;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        go.await();
                        if (flight.joinOrStart(who)) {                 // the first one performs the request set
                            networkCalls.incrementAndGet();
                            Thread.sleep(80);                          // the 5 MB download
                            List<String> all = flight.finish();
                            answered.addAndGet(all.size());
                        }
                    } catch (InterruptedException ignored) { } finally { done.countDown(); }
                }
            }).start();
        }
        go.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        // callers that arrive after the flight finished start a new one (that is correct); the rest were answered by the first
        assertTrue("far fewer request sets than callers: " + networkCalls.get(), networkCalls.get() < n);
        assertEquals("everybody is answered exactly once", n, answered.get());
    }

    @Test public void aCallerJoiningAFinishedFlightStartsANewOne() {
        CgFlight<String> f = new CgFlight<String>();
        assertTrue(f.joinOrStart("a"));
        assertFalse(f.joinOrStart("b"));
        assertEquals(2, f.finish().size());
        assertFalse(f.running());
        assertTrue(f.joinOrStart("c"));
        assertTrue(f.finish().contains("c"));
        assertTrue("finish() with nothing running is harmless", f.finish().isEmpty());
    }

    @Test public void aResponseRequestedBeforeAnEditNeverOverwritesIt() {
        assertTrue("an edit happened during the load: keep the edited snapshot", CgFlight.staleAfterEdit(3, 4, true, false));
        assertFalse("no edit: install the server's answer", CgFlight.staleAfterEdit(4, 4, true, false));
        assertFalse("nothing to protect yet", CgFlight.staleAfterEdit(3, 4, false, false));
        assertFalse("an offline cache answer is not a competing newer state", CgFlight.staleAfterEdit(3, 4, true, true));
    }

    @Test public void aYoungServerSnapshotIsNotDownloadedAgain() {
        long now = 1_000_000L;
        assertTrue(CgFlight.isFresh(true, false, now - 30_000L, now, 90_000L));
        assertFalse("older than the limit", CgFlight.isFresh(true, false, now - 120_000L, now, 90_000L));
        assertFalse("the on-device cache is never 'fresh'", CgFlight.isFresh(true, true, now - 1_000L, now, 90_000L));
        assertFalse("no snapshot yet", CgFlight.isFresh(false, false, 0L, now, 90_000L));
        assertFalse("a clock that moved backwards never counts as fresh", CgFlight.isFresh(true, false, now + 5_000L, now, 90_000L));
    }

    @Test public void backgroundRefreshBacksOffWhileTheServerKeepsFailing() {
        long base = 120_000L, cap = 600_000L;
        assertEquals(120_000L, CgFlight.refreshDelayMs(base, 0, cap));
        assertEquals(240_000L, CgFlight.refreshDelayMs(base, 1, cap));
        assertEquals(480_000L, CgFlight.refreshDelayMs(base, 2, cap));
        assertEquals("capped at 10 minutes", 600_000L, CgFlight.refreshDelayMs(base, 3, cap));
        assertEquals(600_000L, CgFlight.refreshDelayMs(base, 99, cap));
    }
}
