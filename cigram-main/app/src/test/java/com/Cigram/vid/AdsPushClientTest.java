package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** 19.0 — the pure decisions of the chat push client: when to (re)send the FCM token, and the message-id deduplication. */
public class AdsPushClientTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;

    @Test public void aNewTokenIsRegisteredAtOnce() {
        assertTrue(AdsDevice.shouldRegister("", "tok", "", "user-1", 0, 0, 1_000_000L, false));
        assertTrue(AdsDevice.shouldRegister("old", "new", "user-1", "user-1", 5_000L, 0, 9_000_000L, false));
    }

    @Test public void anAccountSwitchOnTheSamePhoneIsRegisteredAtOnce() {
        assertTrue(AdsDevice.shouldRegister("tok", "tok", "user-1", "user-2", 5_000L, 0, 9_000_000L, false));
    }

    @Test public void aRegisteredPhoneIsLeftAloneForADay() {
        long t0 = 10_000_000L;
        assertFalse(AdsDevice.shouldRegister("tok", "tok", "u", "u", t0, 0, t0 + DAY - 1, false));
        assertTrue(AdsDevice.shouldRegister("tok", "tok", "u", "u", t0, 0, t0 + DAY + 1, false));
    }

    @Test public void aFailedAttemptIsNotRepeatedInATightLoop() {
        long now = 50_000_000L;
        assertFalse("30 s guard after a try with a changed token", AdsDevice.shouldRegister("", "tok", "", "u", 0, now - 10_000L, now, false));
        assertFalse("15 min guard for the daily refresh", AdsDevice.shouldRegister("tok", "tok", "u", "u", now - 2 * DAY, now - 60_000L, now, false));
        assertTrue(AdsDevice.shouldRegister("tok", "tok", "u", "u", now - 2 * DAY, now - 16 * 60_000L, now, false));
    }

    @Test public void nothingIsSentWithoutATokenOrAnAccount() {
        assertFalse(AdsDevice.shouldRegister("", "", "", "u", 0, 0, 1L << 40, true));
        assertFalse(AdsDevice.shouldRegister("", "tok", "", "", 0, 0, 1L << 40, true));
        assertFalse(AdsDevice.shouldRegister("", null, "", "u", 0, 0, 1L << 40, true));
    }

    @Test public void aForcedRefreshAlwaysGoesOut() {
        assertTrue(AdsDevice.shouldRegister("tok", "tok", "u", "u", 1L << 40, 1L << 40, 1L << 40, true));
    }

    @Test public void theSameMessageIdIsNotifiedOnlyOnce() {
        List<String> seen = new ArrayList<String>();
        List<String> a = AdsNotifier.remember(seen, "m_1", 40);
        assertNotNull(a);
        assertEquals(Arrays.asList("m_1"), a);
        assertNull("a redelivery of the same message", AdsNotifier.remember(a, "m_1", 40));
        List<String> b = AdsNotifier.remember(a, "m_2", 40);
        assertEquals(Arrays.asList("m_1", "m_2"), b);
    }

    @Test public void theMemoryIsBounded() {
        List<String> seen = new ArrayList<String>();
        for (int i = 0; i < 100; i++) seen = AdsNotifier.remember(seen, "m_" + i, 40);
        assertEquals(40, seen.size());
        assertEquals("m_60", seen.get(0));
        assertNotNull("an old id that fell out can notify again (bounded memory)", AdsNotifier.remember(seen, "m_0", 40));
    }

    @Test public void aMessageWithoutAnIdIsNeverBlocked() {
        List<String> seen = new ArrayList<String>(Arrays.asList("m_1"));
        List<String> r = AdsNotifier.remember(seen, "", 40);
        assertNotNull(r);
        assertEquals(seen, r);
    }
}
