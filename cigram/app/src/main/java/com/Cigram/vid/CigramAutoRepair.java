package com.Cigram.vid;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;

/**
 * Cigram Auto Repair + Broken Stream Repair — keeps a film playing through what used to end it.
 *
 * Before 19.2 a network cut, an expired link or a decoder that gave up in the middle of a film left a frozen picture
 * (the player only said «تعذر تشغيل الفيديو» once, during the first load). This watchdog looks at the player once a
 * second — by polling, the player screen deliberately has no Player.Listener — and repairs three situations:
 *
 * <ol>
 *   <li><b>an error</b> (the player stopped with a PlaybackException): classified and repaired by the ladder of
 *   {@link CigramRepairPlan};</li>
 *   <li><b>a broken stream</b>: «buffering» for 40 s while NOTHING arrives (the buffered amount does not grow). A slow
 *   but living connection is left alone — it only gets a «الاتصال بطيء» note.</li>
 * </ol>
 *
 * 19.2.1 — the watchdog never stops a film by itself any more:
 * <ul>
 *   <li>the «frozen picture» guess (playing, but the clock stood still for 8 s) is gone: it also fired on a healthy
 *   film (an audio clock that pauses for a moment, a seek being prepared) and reopened it again and again until the
 *   «تعذّر إصلاح البث» dialog closed the player — the stops the viewers reported;</li>
 *   <li>a waiting stream is waited for patiently (40 s with nothing arriving, nothing while a seek is prepared) and is
 *   only reopened, never given up: when its ladder ends it simply starts over after the next 40 s;</li>
 *   <li>only a real player error can end in the dialog, and that dialog no longer closes the player.</li>
 * </ul>
 *
 * Every repair goes through the screen's own loader (the same code that opened the title), at the second where
 * playback stopped minus two seconds, so no line of dialogue is lost. What a repair learned is remembered for the
 * title ({@link #PREFS}): a title whose 4K could not be decoded on this phone opens below that ceiling next time.
 * The user sees one small status line while it works and, only when every rung failed, one dialog with the real reason.
 */
final class CigramAutoRepair {

    interface Host {
        /** The screen is still there. */
        boolean alive();
        com.google.android.exoplayer2.ExoPlayer player();
        /** Reopen the stream that is playing now at {@code positionMs} (through the screen's loader). */
        void reopen(long positionMs);
        /** Reopen from the link the title was opened with (renews an expired stream address). */
        void reopenFromSource(long positionMs);
        /** True when {@link #reopenFromSource} would ask a different address than {@link #reopen}. */
        boolean hasSeparateSource();
        /** Caps the video height (0 = remove the cap). Returns false when the stream has no lower quality. */
        boolean setMaxVideoHeight(int height);
        /** Shows (text) or hides (null) the status line. */
        void status(String text);
        /** Every rung failed. {@code retry} starts over from the top. */
        void failed(String message, Runnable retry);
        /** A stable id of the title (for what was learned about it), "" when unknown. */
        String titleKey();
    }

    static final String PREFS = "cigram_auto_repair";
    private static final long TICK_MS = 1000, HUNG_MS = 40000, HEALTHY_MS = 8000, LOAD_GRACE_MS = 20000;

    private final Host host;
    private final Handler ui;
    private final Context app;

    private boolean running, waitingNetwork, gaveUp, repairScheduled;
    private int incidentKind, incidentHttp, attempt, ceiling;
    private long lastPosition = -1, lastMoveAt, lastBuffered = -1, lastBufferGrowAt, loadStartedAt, healthySince, resumeAt;
    private long slowNoteAt;
    private int repairsDone;

    private CigramAutoRepair(Context c, Host h, Handler ui) {
        this.app = c.getApplicationContext();
        this.host = h;
        this.ui = ui;
    }

    static CigramAutoRepair attach(Context c, Host h, Handler ui) {
        CigramAutoRepair r = new CigramAutoRepair(c, h, ui);
        r.start();
        return r;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                if (host.alive()) look();
            } catch (Throwable t) {
                android.util.Log.w("CigramAutoRepair", "tick failed", t);
            }
            if (running) ui.postDelayed(this, TICK_MS);
        }
    };

    void start() {
        if (running) return;
        running = true;
        long now = SystemClock.elapsedRealtime();
        lastMoveAt = lastBufferGrowAt = now;
        ui.postDelayed(tick, TICK_MS);
    }

    void stop() {
        running = false;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(doRepair);
    }

    /** How many repairs this screen carried out (for the diagnostics line of the player). */
    int repairsDone() { return repairsDone; }

    /** The loader calls this when it starts (or restarts) a stream: the watchdog gives it time to become ready. */
    void onLoadStarted() {
        long now = SystemClock.elapsedRealtime();
        gaveUp = false;                                        // a new load (another episode, a retry) is watched again
        loadStartedAt = now;
        lastMoveAt = lastBufferGrowAt = now;
        lastPosition = -1;
        lastBuffered = -1;
    }

    /**
     * The loader saw an error before the first frame. Returns true when the repair takes it from here (the loader
     * then shows nothing itself).
     */
    boolean onLoadFailed() {
        if (!running || gaveUp) return false;
        com.google.android.exoplayer2.PlaybackException e = error();
        if (e == null) return false;
        beginOrContinue(CigramRepairPlan.kindOf(e.errorCode), httpStatus(e));
        return true;
    }

    /** The title learned to need a lower ceiling on this phone: applied before the first load. */
    void applyLearnedCeiling() {
        try {
            String key = host.titleKey();
            if (key.length() == 0) return;
            int saved = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("ceiling::" + key, 0);
            if (saved > 0 && host.setMaxVideoHeight(saved)) ceiling = saved;
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ watching

    private com.google.android.exoplayer2.PlaybackException error() {
        try { return host.player().getPlayerError(); } catch (Throwable t) { return null; }
    }

    private static int httpStatus(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException) {
                return ((com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException) t).responseCode;
            }
            if (t.getCause() == t) break;
        }
        return 0;
    }

    private void look() {
        if (gaveUp) return;
        long now = SystemClock.elapsedRealtime();
        com.google.android.exoplayer2.ExoPlayer p = host.player();
        if (p == null) return;

        if (waitingNetwork) {
            if (CigramPlaybackError.hasNetwork(app)) {
                waitingNetwork = false;
                schedule(600);
            }
            return;
        }
        if (repairScheduled) return;

        com.google.android.exoplayer2.PlaybackException e = error();
        if (e != null) {
            if (now - loadStartedAt < 1500) return;            // the loader has just restarted the player: its poll reports first
            rememberPosition(p);
            beginOrContinue(CigramRepairPlan.kindOf(e.errorCode), httpStatus(e));
            return;
        }

        int state = p.getPlaybackState();
        boolean wants = p.getPlayWhenReady();
        long pos = p.getCurrentPosition();
        long buffered = p.getTotalBufferedDuration();
        if (pos != lastPosition) { lastPosition = pos; lastMoveAt = now; }
        if (buffered > lastBuffered + 200) lastBufferGrowAt = now;
        lastBuffered = buffered;

        if (!wants || state == com.google.android.exoplayer2.Player.STATE_ENDED || state == com.google.android.exoplayer2.Player.STATE_IDLE
                || CigramSeekGuard.pendingTarget() >= 0L) {
            lastMoveAt = lastBufferGrowAt = now;               // paused / finished / not started / a seek is prepared: nothing to repair
            return;
        }
        boolean inGrace = now - loadStartedAt < LOAD_GRACE_MS;

        if (state == com.google.android.exoplayer2.Player.STATE_READY) {
            lastBufferGrowAt = now;                            // ready = the film can go on: never a "broken stream"
            if (attempt > 0 || incidentKind != 0) {
                if (healthySince == 0) healthySince = now;
                if (now - healthySince >= HEALTHY_MS) incidentOver();
            }
            return;
        }
        healthySince = 0;

        if (state == com.google.android.exoplayer2.Player.STATE_BUFFERING && !inGrace) {
            long hung = now - Math.max(lastBufferGrowAt, lastMoveAt);
            if (hung >= HUNG_MS) {
                rememberPosition(p);
                beginOrContinue(CigramRepairPlan.STALL, 0);
            } else if (now - lastMoveAt >= 10000 && now - slowNoteAt > 30000 && incidentKind == 0) {
                slowNoteAt = now;
                host.status(CigramPlaybackError.hasNetwork(app) ? "الاتصال بطيء — يتم التحميل…" : "لا يوجد اتصال بالإنترنت");
                ui.postDelayed(clearSlowNote, 5000);
            }
        }
    }

    private final Runnable clearSlowNote = new Runnable() {
        @Override public void run() { if (running && incidentKind == 0 && !gaveUp) host.status(null); }
    };

    private void rememberPosition(com.google.android.exoplayer2.ExoPlayer p) {
        try {
            long pos = p.getCurrentPosition();
            if (pos > 0) resumeAt = Math.max(0, pos - 2000);   // two seconds back: no line of dialogue is lost
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ repairing

    private void beginOrContinue(int kind, int http) {
        if (incidentKind == 0) attempt = 0;
        else if (kind != incidentKind && kind != CigramRepairPlan.STALL) attempt = Math.min(attempt, 1);   // a new kind of failure restarts near the top
        incidentKind = kind;
        incidentHttp = http;
        healthySince = 0;
        ui.removeCallbacks(clearSlowNote);
        boolean online = CigramPlaybackError.hasNetwork(app);
        int action = CigramRepairPlan.next(kind, attempt, online, http, host.hasSeparateSource(), CigramRepairPlan.lowerCeiling(ceiling) > 0);
        if (action == CigramRepairPlan.WAIT_NETWORK) {
            waitingNetwork = true;
            host.status(CigramRepairPlan.statusText(action, attempt));
            return;
        }
        if (action == CigramRepairPlan.GIVE_UP && kind == CigramRepairPlan.STALL) {
            // a stream that waits is not a broken film: no dialog, the ladder starts over after the next long wait
            incidentKind = 0;
            attempt = 0;
            long now = SystemClock.elapsedRealtime();
            lastMoveAt = lastBufferGrowAt = now;
            slowNoteAt = now;
            host.status(CigramPlaybackError.hasNetwork(app) ? "الاتصال بطيء — يتم التحميل…" : "لا يوجد اتصال بالإنترنت");
            ui.postDelayed(clearSlowNote, 5000);
            return;
        }
        if (action == CigramRepairPlan.GIVE_UP) {
            gaveUp = true;
            host.status(null);
            String reason = CigramRepairPlan.giveUpText(kind, http);
            try {
                com.google.android.exoplayer2.PlaybackException e = error();
                if (e != null) reason = reason + "\n\n(" + e.getErrorCodeName() + ")";
            } catch (Throwable ignored) { }
            host.failed(reason, new Runnable() {
                @Override public void run() { retryFromTop(); }
            });
            return;
        }
        pendingAction = action;
        host.status(CigramRepairPlan.statusText(action, attempt));
        schedule(CigramRepairPlan.delayMs(kind, attempt, http));
    }

    private int pendingAction = CigramRepairPlan.REOPEN;

    private void schedule(long delay) {
        repairScheduled = true;
        ui.removeCallbacks(doRepair);
        ui.postDelayed(doRepair, delay);
    }

    private final Runnable doRepair = new Runnable() {
        @Override public void run() {
            repairScheduled = false;
            if (!running || !host.alive() || gaveUp) return;
            if (!CigramPlaybackError.hasNetwork(app) && incidentKind != CigramRepairPlan.DECODER && incidentKind != CigramRepairPlan.AUDIO
                    && incidentKind != CigramRepairPlan.PARSING) {
                waitingNetwork = true;
                host.status(CigramRepairPlan.statusText(CigramRepairPlan.WAIT_NETWORK, attempt));
                return;
            }
            int action = pendingAction;
            attempt++;
            repairsDone++;
            try {
                com.google.android.exoplayer2.ExoPlayer p = host.player();
                if (action == CigramRepairPlan.LIVE_EDGE) {
                    onLoadStarted();
                    p.seekToDefaultPosition();
                    p.prepare();
                    return;
                }
                if (action == CigramRepairPlan.LOWER_QUALITY_REOPEN) {
                    int lower = CigramRepairPlan.lowerCeiling(ceiling);
                    if (lower > 0 && host.setMaxVideoHeight(lower)) {
                        ceiling = lower;
                        learnCeiling(lower);
                    }
                }
                if (action == CigramRepairPlan.REOPEN_SOURCE) host.reopenFromSource(resumeAt);
                else host.reopen(resumeAt);
            } catch (Throwable t) {
                android.util.Log.w("CigramAutoRepair", "repair step failed", t);
            }
        }
    };

    private void incidentOver() {
        boolean repaired = attempt > 0;
        incidentKind = 0;
        incidentHttp = 0;
        attempt = 0;
        healthySince = 0;
        if (repaired) {
            host.status("تم إصلاح البث ✓");
            ui.postDelayed(new Runnable() {
                @Override public void run() { if (running && incidentKind == 0 && !gaveUp) host.status(null); }
            }, 2500);
        } else {
            host.status(null);
        }
    }

    private void retryFromTop() {
        gaveUp = false;
        waitingNetwork = false;
        incidentKind = 0;
        attempt = 0;
        onLoadStarted();
        try {
            if (host.hasSeparateSource()) host.reopenFromSource(resumeAt); else host.reopen(resumeAt);
        } catch (Throwable ignored) { }
    }

    private void learnCeiling(int height) {
        try {
            String key = host.titleKey();
            if (key.length() > 0) app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("ceiling::" + key, height).apply();
        } catch (Throwable ignored) { }
    }
}
