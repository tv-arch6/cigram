package com.Cigram.vid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * 19.0 — the scheduling core of poster / backdrop loading. Plain Java (no Android classes): it decides WHAT is fetched, WHEN and
 * HOW OFTEN; {@link CigramImages} supplies the real downloader. The old loader gave every card its own task on a shared pool, which
 * meant: the same poster downloaded several times at once (and written through the same temp file), no limit on parallel requests,
 * work for cards that were already gone, and a broken link retried every time a card was built.
 *
 * Rules:
 *  - single flight: one fetch per URL, however many cards ask; every waiter is answered by it
 *  - at most {@code threads} fetches run at once (no burst of dozens of simultaneous connections)
 *  - order: priority class first (VISIBLE before NEARBY before PRELOAD), then first come first served inside a class
 *    (the rows are built top to bottom, so the first screen is fetched first); a later request can RAISE a queued URL
 *  - a waiter that is no longer wanted (card re-bound / screen closed) is skipped; a URL nobody wants any more is never fetched
 *  - a failed URL is not retried for a growing delay (30 s, 60 s, ... max 10 min); requests during the delay are answered
 *    at once (as failed) without touching the network; {@link #resetBackoff()} (network came back) clears it
 *  - a fetch that throws is a failure of that URL only; the workers keep running
 */
final class CigramImageQueue {

    static final int VISIBLE = 0;
    static final int NEARBY = 1;
    static final int PRELOAD = 2;

    /** Blocking downloader / cache check: returns true when the image is now available locally. */
    interface Fetcher {
        boolean fetch(String url) throws Exception;
    }

    interface Callback {
        /** False when the requester is gone (view re-bound, activity closed): the answer is not needed. */
        boolean wanted();
        /** Called once, on a worker thread (or on the caller's thread for an instant answer). */
        void done(String url, boolean ok);
    }

    interface Clock {
        long now();
    }

    /** Handle to withdraw a request. */
    final class Ticket {
        private final Task task;
        private final Waiter waiter;
        private Ticket(Task t, Waiter w) { task = t; waiter = w; }
        void cancel() {
            synchronized (lock) {
                if (waiter == null) return;
                waiter.cancelled = true;
                if (task != null && !task.started && task.allGone()) {
                    queue.remove(task);
                    byUrl.remove(task.url);
                }
            }
        }
    }

    private static final class Waiter {
        final Callback cb;
        volatile boolean cancelled;
        Waiter(Callback cb) { this.cb = cb; }
        boolean live() { return !cancelled && safeWanted(cb); }
    }

    private static final class Task {
        final String url;
        int priority;
        final long seq;
        boolean started;
        final List<Waiter> waiters = new ArrayList<Waiter>();
        Task(String url, int priority, long seq) { this.url = url; this.priority = priority; this.seq = seq; }
        boolean allGone() {
            for (Waiter w : waiters) if (w.live()) return false;
            return true;
        }
    }

    private static final class Failure {
        int count;
        long until;
    }

    private static boolean safeWanted(Callback cb) {
        try { return cb.wanted(); } catch (Throwable t) { return true; }
    }

    private final Object lock = new Object();
    private final ArrayList<Task> queue = new ArrayList<Task>();
    private final HashMap<String, Task> byUrl = new HashMap<String, Task>();
    private final HashMap<String, Failure> failures = new HashMap<String, Failure>();
    private final Fetcher fetcher;
    private final Clock clock;
    private final List<Thread> workers = new ArrayList<Thread>();
    private long seq;
    private volatile boolean shutdown;
    // counters for diagnostics / tests
    private int fetches;
    private int skippedUnwanted;
    private int instantFailures;

    CigramImageQueue(int threads, Fetcher fetcher, Clock clock, String name) {
        this.fetcher = fetcher;
        this.clock = clock == null ? new Clock() { @Override public long now() { return System.currentTimeMillis(); } } : clock;
        for (int i = 0; i < Math.max(1, threads); i++) {
            Thread t = new Thread(new Runnable() { @Override public void run() { loop(); } }, name + "-" + i);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
    }

    /** Asks for {@code url}; {@code cb} is answered once. Never blocks, never throws. */
    Ticket request(String url, int priority, Callback cb) {
        if (url == null || url.length() == 0 || cb == null) return new Ticket(null, null);
        Waiter w = new Waiter(cb);
        long retryAt = 0L;
        synchronized (lock) {
            Failure f = failures.get(url);
            if (f != null && clock.now() < f.until) {
                instantFailures++;
                retryAt = f.until;
            } else {
                Task t = byUrl.get(url);
                if (t == null) {
                    t = new Task(url, priority, seq++);
                    byUrl.put(url, t);
                    queue.add(t);
                } else if (!t.started && priority < t.priority) {
                    t.priority = priority;                         // raised: the card is on screen now
                }
                t.waiters.add(w);
                lock.notifyAll();
                return new Ticket(t, w);
            }
        }
        // inside the back-off window: answer at once, no network
        try { cb.done(url, false); } catch (Throwable ignored) { }
        return new Ticket(null, w);
    }

    /** Raises a queued URL (e.g. its card scrolled into view). No effect when it is already running or unknown. */
    void raise(String url, int priority) {
        synchronized (lock) {
            Task t = byUrl.get(url);
            if (t != null && !t.started && priority < t.priority) t.priority = priority;
        }
    }

    /** The network is back: failed URLs may be tried again now. */
    void resetBackoff() {
        synchronized (lock) { failures.clear(); }
    }

    int queued() { synchronized (lock) { return queue.size(); } }
    int fetchCount() { synchronized (lock) { return fetches; } }
    int skippedUnwanted() { synchronized (lock) { return skippedUnwanted; } }
    int instantFailures() { synchronized (lock) { return instantFailures; } }

    void shutdown() {
        shutdown = true;
        synchronized (lock) { lock.notifyAll(); }
    }

    private Task take() throws InterruptedException {
        synchronized (lock) {
            while (true) {
                if (shutdown) return null;
                Task best = null;
                for (Iterator<Task> it = queue.iterator(); it.hasNext(); ) {
                    Task t = it.next();
                    if (t.allGone()) {                              // nobody wants it any more: never fetched
                        it.remove();
                        byUrl.remove(t.url);
                        skippedUnwanted++;
                        continue;
                    }
                    if (best == null || t.priority < best.priority || (t.priority == best.priority && t.seq < best.seq)) best = t;
                }
                if (best != null) {
                    queue.remove(best);
                    best.started = true;
                    return best;
                }
                lock.wait();
            }
        }
    }

    private void loop() {
        while (!shutdown) {
            Task t;
            try {
                t = take();
            } catch (InterruptedException e) {
                return;
            }
            if (t == null) return;
            boolean ok = false;
            try {
                synchronized (lock) { fetches++; }
                ok = fetcher.fetch(t.url);
            } catch (Throwable error) {
                ok = false;
            }
            List<Waiter> answer;
            synchronized (lock) {
                byUrl.remove(t.url);
                if (ok) failures.remove(t.url);
                else {
                    Failure f = failures.get(t.url);
                    if (f == null) { f = new Failure(); failures.put(t.url, f); }
                    f.count++;
                    f.until = clock.now() + Math.min(600_000L, 30_000L << Math.min(f.count - 1, 5));
                }
                answer = new ArrayList<Waiter>(t.waiters);
            }
            for (Waiter w : answer) {
                if (w.cancelled) continue;
                try { w.cb.done(t.url, ok); } catch (Throwable ignored) { }
            }
        }
    }
}
