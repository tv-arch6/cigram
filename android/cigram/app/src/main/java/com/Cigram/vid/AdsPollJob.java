package com.Cigram.vid;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** مهمة خلفية دورية تستدعي AdsNotifier.pollNow. تُعلَن في المانيفست بصلاحية BIND_JOB_SERVICE. */
public class AdsPollJob extends JobService {

    @Override public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override public void run() {
                try { AdsNotifier.pollNow(getApplicationContext()); } catch (Throwable ignored) { }
                jobFinished(params, false);
            }
        }, "ads-poll").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return false;
    }
}
