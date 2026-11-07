package com.Cigram.vid;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v10.49: the app's worker pools. Same size as the old {@code Executors.newFixedThreadPool(n)}, but
 * idle threads exit after 20 s (about 25 threads used to sit idle for the whole life of the
 * process), they are daemon threads, and they run a little below the UI thread's priority.
 */
final class CigramPool {
    private CigramPool() { }

    static ExecutorService fixed(int threads, final String name) {
        final AtomicInteger count = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 20L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {
                    @Override public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, name + "-" + count.incrementAndGet());
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY - 1);
                        return t;
                    }
                });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
}
