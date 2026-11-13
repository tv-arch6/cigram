package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.LoudnessEnhancer;

/**
 * Audio boost for the built-in player: raises the volume above the normal maximum with Android's LoudnessEnhancer
 * (a gain in millibels on the player's audio session). The choice is remembered. The enhancer is re-attached whenever
 * the player's audio session changes (a new title / a new stream), and released with the player.
 * Normalising loud against quiet scenes is NOT done here (that needs a compressor effect); this is plain gain.
 */
public final class CigramAudioBoost {

    private CigramAudioBoost() { }

    /** Gain in millibels for each level (100 mB = 1 dB). */
    static final int[] GAIN_MB = {0, 300, 600, 900, 1200};
    static final String[] LABELS = {"عادي", "+3 ديسيبل", "+6 ديسيبل", "+9 ديسيبل", "+12 ديسيبل (أقصى)"};
    private static final String PREFS = "cigram_player_prefs";
    private static final String KEY = "audio_boost";

    private static LoudnessEnhancer enhancer;
    private static int sessionId = -1;

    static int clamp(int level) {
        return Math.max(0, Math.min(GAIN_MB.length - 1, level));
    }

    static int level(Context c) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return clamp(p.getInt(KEY, 0));
        } catch (Throwable t) {
            return 0;
        }
    }

    static void setLevel(Context c, int level) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, clamp(level)).apply();
        } catch (Throwable ignored) { }
    }

    static String label(Context c) {
        return LABELS[level(c)];
    }

    /**
     * Makes the effect match the saved level for the given audio session (call after the player is prepared and again when
     * the choice changes). Cheap when nothing changed. Never throws: some devices refuse audio effects, then it is a no-op.
     */
    static synchronized void sync(Context c, int audioSession) {
        try {
            int gain = GAIN_MB[level(c)];
            if (audioSession <= 0) return;                       // the player has no audio session yet
            if (enhancer != null && sessionId != audioSession) release();
            if (gain <= 0) {
                if (enhancer != null) { enhancer.setEnabled(false); }
                return;
            }
            if (enhancer == null) {
                enhancer = new LoudnessEnhancer(audioSession);
                sessionId = audioSession;
            }
            enhancer.setTargetGain(gain);
            enhancer.setEnabled(true);
        } catch (Throwable t) {
            release();                                           // an unsupported device / a dead session: forget it, try again later
        }
    }

    static synchronized void release() {
        try { if (enhancer != null) enhancer.release(); } catch (Throwable ignored) { }
        enhancer = null;
        sessionId = -1;
    }
}
