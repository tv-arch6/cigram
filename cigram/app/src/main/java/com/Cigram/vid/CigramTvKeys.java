package com.Cigram.vid;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;

import java.util.WeakHashMap;

/**
 * v10.66 — the remote inside the full screen players (TV only; installed by the player itself
 * only when CigramTvMode says it is a TV).
 *
 *   controls hidden   OK / ▲ / ▼ / MENU  → show the controls (the focus goes to play/pause)
 *                     ◀ / ▶              → rewind / fast forward one step (the player's own step)
 *                     OK on a button that is still on screen (skip intro, …) presses it
 *   controls shown    arrows move between the buttons; every key keeps the controls up
 *                     Back hides the controls (while playing); the next Back leaves the player
 *   any time          ⏯ play/pause · ▶▶ / ◀◀ seek · ⏭ / ⏮ (and CH+ / CH−) next / previous
 *
 * The player hands in its own actions (the same ones its buttons run), so the screen always
 * shows what the remote did: icons, the "+10 s" bubble, the episode switch.
 */
final class CigramTvKeys {

    /** What a player can do; each method runs what the matching on-screen button runs. */
    interface PlayerHost {
        boolean controlsVisible();
        void showControls();
        void hideControls();
        boolean isPlaying();
        void playPause();
        void rewind();
        void forward();
        void next();
        void previous();
        /** The view that takes the focus when the controls come up (play / pause). */
        View focusTarget();
    }

    /** Buttons that pop up over the picture by themselves (skip intro, next episode). */
    private static final WeakHashMap<View, Boolean> PROMPTS = new WeakHashMap<View, Boolean>();

    private CigramTvKeys() { }

    /**
     * A button that pops up over the picture (skip intro, «شاهد الآن»): on a TV the remote moves to
     * it so OK presses it at once. Safe on a phone (only remembered there).
     */
    static void prompt(final View v) {
        if (v == null) return;
        synchronized (PROMPTS) { PROMPTS.put(v, Boolean.TRUE); }
        try {
            if (!CigramTvMode.isTv(v.getContext())) return;
            v.post(new Runnable() {
                @Override public void run() {
                    try {
                        if (v.isShown() && !v.isInTouchMode()) v.requestFocus();
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }
    }

    static boolean isPrompt(View v) {
        View cur = v;
        synchronized (PROMPTS) {
            while (cur != null) {
                if (PROMPTS.containsKey(cur)) return true;
                ViewParent p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
            }
        }
        return false;
    }

    static void player(final Activity a, final PlayerHost host) {
        try {
            if (a == null || host == null || !CigramTvMode.isTv(a)) return;
            CigramTvFocus.setPlayerMode(a);
            CigramTvFocus.setRingFilter(a, new CigramTvFocus.RingFilter() {
                @Override public boolean ringVisible(View target) {
                    try {
                        return host.controlsVisible() || isPrompt(target);
                    } catch (Throwable ignored) {
                        return true;
                    }
                }
            });
            CigramTvFocus.setKeyHandler(a, new CigramTvFocus.KeyHandler() {
                @Override public boolean onKey(KeyEvent e) {
                    try {
                        return handle(a, host, e);
                    } catch (Throwable ignored) {
                        return false;
                    }
                }
            });
            final View target = host.focusTarget();
            if (target != null) {
                CigramTvFocus.setDefaultFocus(a, target);
                target.post(new Runnable() {
                    @Override public void run() {
                        try {
                            if (target.isShown() && !target.isInTouchMode()) target.requestFocus();
                        } catch (Throwable ignored) { }
                    }
                });
            }
        } catch (Throwable ignored) { }
    }

    /** Uptime of the last seek made by a HELD key; see {@link #seekAllowed}. */
    private static long lastHeldSeekAt;
    /** A held arrow / rewind / forward key seeks at most this often (one step = the user's seek interval). */
    private static final long HELD_SEEK_GAP_MS = 280L;

    /**
     * 2026: a remote repeats a held key ~20 times a second; every repeat used to seek one more step, so holding →
     * for a second jumped minutes ahead. The first press always seeks once; a held key then seeks at a steady,
     * readable pace.
     */
    private static boolean seekAllowed(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        long now = android.os.SystemClock.uptimeMillis();
        if (e.getRepeatCount() == 0) { lastHeldSeekAt = now; return true; }
        if (now - lastHeldSeekAt < HELD_SEEK_GAP_MS) return false;
        lastHeldSeekAt = now;
        return true;
    }

    private static boolean handle(Activity a, PlayerHost h, KeyEvent e) {
        int c = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && e.getRepeatCount() == 0;
        boolean up = e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled();

        // ---- media keys: any time
        switch (c) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (first) { h.playPause(); h.showControls(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (first) { if (!h.isPlaying()) h.playPause(); h.showControls(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                if (first) { if (h.isPlaying()) h.playPause(); h.showControls(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (seekAllowed(e)) h.forward();
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (seekAllowed(e)) h.rewind();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (first) h.next();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (first) h.previous();
                return true;
            default:
                break;
        }

        boolean visible = h.controlsVisible();
        if (!visible) {
            if (c == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (seekAllowed(e)) h.rewind();
                return true;
            }
            if (c == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (seekAllowed(e)) h.forward();
                return true;
            }
            if (CigramTvFocus.isConfirm(c) && promptFocused(a)) return false;   // «تخطي المقدمة», «شاهد الآن»
            if (CigramTvFocus.isConfirm(c) || c == KeyEvent.KEYCODE_DPAD_UP
                    || c == KeyEvent.KEYCODE_DPAD_DOWN || c == KeyEvent.KEYCODE_MENU) {
                if (first) {
                    h.showControls();
                    View t = h.focusTarget();
                    if (t != null && t.isShown()) t.requestFocus();
                }
                return true;
            }
            return false;   // Back and the rest: the player's own handling
        }

        // ---- controls on screen
        if (c == KeyEvent.KEYCODE_BACK) {
            // while playing the first Back only hides the controls; paused, Back leaves as usual.
            // The press itself goes to the page (so a normal Back still works); only the release is
            // kept, and only when the controls really went away.
            if (!up || !h.isPlaying()) return false;
            h.hideControls();
            return !h.controlsVisible();
        }
        if (down && (isArrow(c) || CigramTvFocus.isConfirm(c))) h.showControls();   // keeps them up
        if (c == KeyEvent.KEYCODE_MENU) {
            if (first) {
                View t = h.focusTarget();
                if (t != null && t.isShown()) t.requestFocus();
            }
            return true;
        }
        return false;
    }

    private static boolean isArrow(int c) {
        return c == KeyEvent.KEYCODE_DPAD_UP || c == KeyEvent.KEYCODE_DPAD_DOWN
                || c == KeyEvent.KEYCODE_DPAD_LEFT || c == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    /** A pop-up button over the picture has the focus (OK presses it). */
    private static boolean promptFocused(Activity a) {
        try {
            View f = a.getWindow().getDecorView().findFocus();
            return f != null && f.isShown() && isPrompt(f);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
