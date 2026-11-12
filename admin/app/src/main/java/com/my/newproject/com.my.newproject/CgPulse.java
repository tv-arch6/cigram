package com.my.newproject;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * The pulse of Cigram, recorded around the clock.
 *
 * Every 15 minutes (Android's shortest periodic job) — whether the Admin app is open or not — one sample of the live
 * numbers is read from {@code GET /admin/live} and appended to a file on this phone: active now, watching now,
 * viewers today, new accounts (24 h) and AdMob impressions today. The file keeps the last {@link #MAX} samples
 * (about three weeks), so the chart of {@link CgPulseActivity} never starts empty and never stops: while its page is
 * open it adds a sample every 20 seconds on top of the background ones.
 */
final class CgPulse {

    static final int MAX = 2000;
    static final int JOB_ID = 77031;
    /** Index of each number inside a sample (index 0 is the time in ms). */
    static final int T = 0, ACTIVE = 1, WATCHING = 2, USERS = 3, NEW_ACCOUNTS = 4, AD_SHOWS = 5;
    private static final String FILE = "cigram_pulse.json";
    private static final Object LOCK = new Object();

    private CgPulse() { }

    static void ensureScheduled(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, Job.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15L * 60L * 1000L)
                    .setPersisted(false)
                    .build());
        } catch (Throwable ignored) { }
    }

    /** Reads the live numbers and stores one sample. Blocking; null when the server could not be read. */
    static long[] sample(Context c) {
        try {
            CgCfg.loadToken(c);
            if (CgCfg.TOKEN.length() == 0) return null;
            CgHttp.Res r = CgHttp.apiGet("/admin/live");
            if (!r.ok() || r.json == null) return null;
            JSONObject d = r.json.optJSONObject("data");
            if (d == null) d = r.json;
            JSONObject acc = d.optJSONObject("accounts");
            long[] s = new long[]{System.currentTimeMillis(), d.optLong("active_now", 0), d.optLong("watching_now", 0),
                    d.optLong("users_today", 0), acc == null ? 0 : Math.max(0, acc.optLong("new_24h", 0)), d.optLong("ad_shows_today", 0)};
            append(c, s);
            return s;
        } catch (Throwable error) {
            return null;
        }
    }

    static void append(Context c, long[] s) {
        synchronized (LOCK) {
            try {
                JSONArray all = readRaw(c);
                JSONArray row = new JSONArray();
                for (long v : s) row.put(v);
                all.put(row);
                JSONArray keep = new JSONArray();
                for (int i = Math.max(0, all.length() - MAX); i < all.length(); i++) keep.put(all.opt(i));
                FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), FILE));
                try { out.write(keep.toString().getBytes("UTF-8")); } finally { out.close(); }
            } catch (Throwable ignored) { }
        }
    }

    private static JSONArray readRaw(Context c) {
        try {
            File f = new File(c.getFilesDir(), FILE);
            if (!f.isFile()) return new JSONArray();
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try { int off = 0, n; while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n; } finally { in.close(); }
            return new JSONArray(new String(b, "UTF-8"));
        } catch (Throwable error) {
            return new JSONArray();
        }
    }

    /** Samples not older than {@code windowMs}, oldest first. */
    static long[][] history(Context c, long windowMs) {
        synchronized (LOCK) {
            JSONArray all = readRaw(c);
            long from = System.currentTimeMillis() - windowMs;
            java.util.ArrayList<long[]> out = new java.util.ArrayList<long[]>();
            for (int i = 0; i < all.length(); i++) {
                JSONArray row = all.optJSONArray(i);
                if (row == null || row.optLong(0) < from) continue;
                long[] s = new long[6];
                for (int k = 0; k < 6; k++) s[k] = row.optLong(k, 0);
                out.add(s);
            }
            return out.toArray(new long[out.size()][]);
        }
    }

    /** The periodic job: one sample, then done. */
    public static final class Job extends JobService {
        @Override public boolean onStartJob(final JobParameters params) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try { sample(getApplicationContext()); } catch (Throwable ignored) { }
                    jobFinished(params, false);
                }
            }, "cigram-pulse").start();
            return true;
        }

        @Override public boolean onStopJob(JobParameters params) { return true; }
    }
}
