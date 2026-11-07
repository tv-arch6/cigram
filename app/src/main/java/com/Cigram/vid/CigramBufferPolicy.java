package com.Cigram.vid;

import android.app.ActivityManager;
import android.content.Context;

/**
 * Buffer sizes of the built-in player by device class, so weak phones are not pushed into out-of-memory crashes:
 *   low-RAM / small heap  -> 10 s min, 30 s max, 15 s kept behind
 *   normal                -> 20 s min, 75 s max, 60 s behind   (the values the player always used)
 */
final class CigramBufferPolicy {

    final int minMs;
    final int maxMs;
    final int backMs;

    private CigramBufferPolicy(int minMs, int maxMs, int backMs) {
        this.minMs = minMs;
        this.maxMs = maxMs;
        this.backMs = backMs;
    }

    static CigramBufferPolicy forDevice(boolean lowRamDevice, int memoryClassMb) {
        if (lowRamDevice || memoryClassMb <= 128) return new CigramBufferPolicy(10000, 30000, 15000);
        if (memoryClassMb <= 192) return new CigramBufferPolicy(15000, 50000, 30000);
        return new CigramBufferPolicy(20000, 75000, 60000);
    }

    static CigramBufferPolicy of(Context c) {
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) return forDevice(am.isLowRamDevice(), am.getMemoryClass());
        } catch (Throwable ignored) { }
        return forDevice(false, 256);
    }
}
