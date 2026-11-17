package com.Cigram.vid;

/** 19.6 — runs {@link CigramReadyGate}'s waiting list: verified alerts are shown, the others wait for their next try. */
public final class CigramReadyJobService extends android.app.job.JobService {

    @Override
    public boolean onStartJob(final android.app.job.JobParameters params) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                long next = -1L;
                try {
                    next = CigramReadyGate.runQueue(getApplicationContext());
                } catch (Throwable error) {
                    android.util.Log.w("CigramReadyGate", "queue run failed", error);
                } finally {
                    try { jobFinished(params, false); } catch (Throwable ignored) { }
                    if (next >= 0L) CigramReadyGate.schedule(getApplicationContext(), next);
                }
            }
        }, "cigram-ready-gate");
        t.start();
        return true;
    }

    @Override
    public boolean onStopJob(android.app.job.JobParameters params) {
        // stopped by the system (no network / time budget): the queue is persisted, run it again later
        CigramReadyGate.schedule(getApplicationContext(), 60000L);
        return false;
    }
}
