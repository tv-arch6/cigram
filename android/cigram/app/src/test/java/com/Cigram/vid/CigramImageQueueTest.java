package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Test;

/** 19.0 — poster loading rules: single flight, bounded parallelism, priority, cancellation, back-off. No Android, no network. */
public class CigramImageQueueTest {

    private CigramImageQueue q;

    @After public void stop() { if (q != null) q.shutdown(); }

    private static final class Answer implements CigramImageQueue.Callback {
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean ok;
        volatile boolean wanted = true;
        final AtomicInteger calls = new AtomicInteger();
        @Override public boolean wanted() { return wanted; }
        @Override public void done(String url, boolean ok) { this.ok = ok; calls.incrementAndGet(); done.countDown(); }
        boolean await() throws InterruptedException { return done.await(5, TimeUnit.SECONDS); }
    }

    /** A fetcher that parks every call until released, and records the order and the parallelism. */
    private static final class Gate implements CigramImageQueue.Fetcher {
        final List<String> order = Collections.synchronizedList(new ArrayList<String>());
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();
        final CountDownLatch open = new CountDownLatch(1);
        final CountDownLatch firstStarted = new CountDownLatch(1);
        volatile boolean result = true;
        volatile boolean blockFirstOnly;
        private volatile boolean first = true;
        @Override public boolean fetch(String url) throws Exception {
            order.add(url);
            int now = running.incrementAndGet();
            while (true) { int m = maxRunning.get(); if (now <= m || maxRunning.compareAndSet(m, now)) break; }
            firstStarted.countDown();
            try {
                if (!blockFirstOnly || first) { first = false; open.await(5, TimeUnit.SECONDS); }
            } finally { running.decrementAndGet(); }
            return result;
        }
    }

    @Test public void theSameUrlIsFetchedOnceForEveryCardThatAsks() throws Exception {
        Gate g = new Gate();
        q = new CigramImageQueue(2, g, null, "t");
        List<Answer> all = new ArrayList<Answer>();
        for (int i = 0; i < 10; i++) { Answer a = new Answer(); all.add(a); q.request("https://x/p.jpg", CigramImageQueue.VISIBLE, a); }
        g.open.countDown();
        for (Answer a : all) { assertTrue(a.await()); assertTrue(a.ok); assertEquals(1, a.calls.get()); }
        assertEquals(1, g.order.size());
        assertEquals(1, q.fetchCount());
    }

    @Test public void neverMoreThanTheConfiguredNumberOfFetchesRunAtOnce() throws Exception {
        Gate g = new Gate();
        q = new CigramImageQueue(3, g, null, "t");
        List<Answer> all = new ArrayList<Answer>();
        for (int i = 0; i < 12; i++) { Answer a = new Answer(); all.add(a); q.request("https://x/" + i, CigramImageQueue.VISIBLE, a); }
        Thread.sleep(150);
        assertEquals("only 3 connections at a time", 3, g.running.get());
        g.open.countDown();
        for (Answer a : all) assertTrue(a.await());
        assertTrue(g.maxRunning.get() <= 3);
        assertEquals(12, g.order.size());
    }

    @Test public void visibleBeforeNearbyBeforePreloadAndFirstComeFirstServedInsideAClass() throws Exception {
        Gate g = new Gate();
        g.blockFirstOnly = true;
        q = new CigramImageQueue(1, g, null, "t");
        Answer busy = new Answer();
        q.request("busy", CigramImageQueue.VISIBLE, busy);
        assertTrue(g.firstStarted.await(5, TimeUnit.SECONDS));
        Answer a = new Answer(), b = new Answer(), c = new Answer(), d = new Answer();
        q.request("A-nearby", CigramImageQueue.NEARBY, a);
        q.request("B-visible", CigramImageQueue.VISIBLE, b);
        q.request("C-visible", CigramImageQueue.VISIBLE, c);
        q.request("D-preload", CigramImageQueue.PRELOAD, d);
        g.open.countDown();
        for (Answer x : Arrays.asList(busy, a, b, c, d)) assertTrue(x.await());
        assertEquals(Arrays.asList("busy", "B-visible", "C-visible", "A-nearby", "D-preload"), g.order);
    }

    @Test public void aQueuedUrlCanBeRaisedWhenItsCardScrollsIntoView() throws Exception {
        Gate g = new Gate();
        g.blockFirstOnly = true;
        q = new CigramImageQueue(1, g, null, "t");
        Answer busy = new Answer();
        q.request("busy", CigramImageQueue.VISIBLE, busy);
        assertTrue(g.firstStarted.await(5, TimeUnit.SECONDS));
        Answer a = new Answer(), b = new Answer();
        q.request("far", CigramImageQueue.PRELOAD, a);
        q.request("near", CigramImageQueue.NEARBY, b);
        q.raise("far", CigramImageQueue.VISIBLE);
        g.open.countDown();
        assertTrue(a.await());
        assertTrue(b.await());
        assertEquals(Arrays.asList("busy", "far", "near"), g.order);
    }

    @Test public void aCancelledRequestIsNeverFetched() throws Exception {
        Gate g = new Gate();
        g.blockFirstOnly = true;
        q = new CigramImageQueue(1, g, null, "t");
        Answer busy = new Answer();
        q.request("busy", CigramImageQueue.VISIBLE, busy);
        assertTrue(g.firstStarted.await(5, TimeUnit.SECONDS));
        Answer gone = new Answer(), kept = new Answer();
        CigramImageQueue.Ticket t = q.request("gone", CigramImageQueue.VISIBLE, gone);
        q.request("kept", CigramImageQueue.VISIBLE, kept);
        t.cancel();
        g.open.countDown();
        assertTrue(kept.await());
        assertFalse("a cancelled card gets no answer", gone.done.await(300, TimeUnit.MILLISECONDS));
        assertEquals(Arrays.asList("busy", "kept"), g.order);
    }

    @Test public void aRequestNobodyWantsAnyMoreIsSkippedButASharedOneStillRuns() throws Exception {
        Gate g = new Gate();
        g.blockFirstOnly = true;
        q = new CigramImageQueue(1, g, null, "t");
        Answer busy = new Answer();
        q.request("busy", CigramImageQueue.VISIBLE, busy);
        assertTrue(g.firstStarted.await(5, TimeUnit.SECONDS));
        Answer w1 = new Answer(), w2 = new Answer(), solo = new Answer();
        q.request("shared", CigramImageQueue.VISIBLE, w1);
        q.request("shared", CigramImageQueue.VISIBLE, w2);
        q.request("solo", CigramImageQueue.VISIBLE, solo);
        w1.wanted = false;                                   // one of the two cards was re-bound
        solo.wanted = false;                                 // the only card of "solo" left the screen
        g.open.countDown();
        assertTrue(w2.await());
        Thread.sleep(100);
        assertTrue(g.order.contains("shared"));
        assertFalse("nobody wants it: not downloaded", g.order.contains("solo"));
        assertTrue(q.skippedUnwanted() >= 1);
    }

    @Test public void aFailedUrlIsNotHammeredAndIsRetriedAfterTheDelay() throws Exception {
        final AtomicLong now = new AtomicLong(1_000_000L);
        Gate g = new Gate();
        g.open.countDown();
        g.result = false;
        q = new CigramImageQueue(1, g, new CigramImageQueue.Clock() { @Override public long now() { return now.get(); } }, "t");
        Answer first = new Answer();
        q.request("https://x/dead.jpg", CigramImageQueue.VISIBLE, first);
        assertTrue(first.await());
        assertFalse(first.ok);
        assertEquals(1, g.order.size());
        for (int i = 0; i < 20; i++) {                       // the card is rebuilt over and over (Home redraws, scrolling back)
            Answer again = new Answer();
            q.request("https://x/dead.jpg", CigramImageQueue.VISIBLE, again);
            assertTrue("answered at once, as failed", again.await());
            assertFalse(again.ok);
        }
        assertEquals("no new network request during the back-off", 1, g.order.size());
        assertEquals(20, q.instantFailures());
        now.addAndGet(31_000L);
        g.result = true;
        Answer later = new Answer();
        q.request("https://x/dead.jpg", CigramImageQueue.VISIBLE, later);
        assertTrue(later.await());
        assertTrue("retried after the delay and it works now", later.ok);
        assertEquals(2, g.order.size());
    }

    @Test public void theDelayGrowsAndTheNetworkComingBackClearsIt() throws Exception {
        final AtomicLong now = new AtomicLong(5_000_000L);
        Gate g = new Gate();
        g.open.countDown();
        g.result = false;
        q = new CigramImageQueue(1, g, new CigramImageQueue.Clock() { @Override public long now() { return now.get(); } }, "t");
        Answer a = new Answer();
        q.request("u", CigramImageQueue.VISIBLE, a);
        assertTrue(a.await());
        now.addAndGet(31_000L);
        Answer b = new Answer();
        q.request("u", CigramImageQueue.VISIBLE, b);          // second failure: 60 s
        assertTrue(b.await());
        assertEquals(2, g.order.size());
        now.addAndGet(31_000L);
        Answer c = new Answer();
        q.request("u", CigramImageQueue.VISIBLE, c);          // only 31 s into a 60 s delay
        assertTrue(c.await());
        assertEquals("still in back-off", 2, g.order.size());
        q.resetBackoff();                                     // the connection came back
        g.result = true;
        Answer d = new Answer();
        q.request("u", CigramImageQueue.VISIBLE, d);
        assertTrue(d.await());
        assertTrue(d.ok);
        assertEquals(3, g.order.size());
    }

    @Test public void aFetcherThatThrowsFailsThatUrlOnlyAndTheWorkersKeepRunning() throws Exception {
        final AtomicInteger n = new AtomicInteger();
        q = new CigramImageQueue(1, new CigramImageQueue.Fetcher() {
            @Override public boolean fetch(String url) throws Exception {
                if (url.equals("boom")) throw new IllegalStateException("socket closed");
                n.incrementAndGet();
                return true;
            }
        }, null, "t");
        Answer bad = new Answer(), good = new Answer();
        q.request("boom", CigramImageQueue.VISIBLE, bad);
        assertTrue(bad.await());
        assertFalse(bad.ok);
        q.request("fine", CigramImageQueue.VISIBLE, good);
        assertTrue(good.await());
        assertTrue(good.ok);
        assertEquals(1, n.get());
    }

    @Test public void emptyUrlsAndNullCallbacksAreIgnored() {
        q = new CigramImageQueue(1, new CigramImageQueue.Fetcher() { @Override public boolean fetch(String u) { return true; } }, null, "t");
        q.request("", CigramImageQueue.VISIBLE, new Answer());
        q.request(null, CigramImageQueue.VISIBLE, new Answer());
        q.request("x", CigramImageQueue.VISIBLE, null);
        assertEquals(0, q.queued());
    }
}
