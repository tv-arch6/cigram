package com.Cigram.vid;

/**
 * CIGRAM Stage 6 periodic background checker.
 * Uses Android JobScheduler only; no external dependency is required.
 */
public final class CigramStage6JobService extends android.app.job.JobService {

    @Override
    public boolean onStartJob(final android.app.job.JobParameters params) {
        // v10.62: request statuses ride on the same periodic job (own thread, never blocks the job)
        try { CigramRequests.refreshQuietly(this); } catch (Throwable ignored) { }
        try {
            CigramStage1InitProvider.runStage6ScheduledCheck(
                    this,
                    new Runnable() {
                        @Override public void run() {
                            try {
                                jobFinished(params, false);
                            } catch (Throwable error) {
                                android.util.Log.w("CigramStage6", "jobFinished failed", error);
                            }
                        }
                    }
            );
            return true;
        } catch (Throwable error) {
            android.util.Log.w("CigramStage6", "Scheduled job failed to start", error);
            return false;
        }
    }

    @Override
    public boolean onStopJob(android.app.job.JobParameters params) {
        return true;
    }
}
