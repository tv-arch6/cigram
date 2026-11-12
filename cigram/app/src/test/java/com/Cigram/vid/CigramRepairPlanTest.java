package com.Cigram.vid;

import static com.Cigram.vid.CigramRepairPlan.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Cigram Auto Repair: the ladder of decisions (JVM, no device). */
public class CigramRepairPlanTest {

    @Test
    public void exoPlayerErrorCodesAreSortedIntoTheRightKindOfFailure() {
        assertEquals(LIVE_WINDOW, kindOf(1002));
        assertEquals(NETWORK, kindOf(2001));          // connection failed
        assertEquals(NETWORK, kindOf(2002));          // connection time-out
        assertEquals(NETWORK, kindOf(1003));          // player time-out
        assertEquals(HTTP_STATUS, kindOf(2004));
        assertEquals(PARSING, kindOf(3001));
        assertEquals(PARSING, kindOf(3004));
        assertEquals(DECODER, kindOf(4001));
        assertEquals(DECODER, kindOf(4005));
        assertEquals(AUDIO, kindOf(5001));
        assertEquals(OTHER, kindOf(1000));
        assertEquals(OTHER, kindOf(6004));            // DRM: not something a reopen fixes, handled as "other"
    }

    @Test
    public void withoutAnyNetworkNothingIsTriedAndNoAttemptIsUsedUp() {
        for (int attempt = 0; attempt < 6; attempt++) {
            assertEquals(WAIT_NETWORK, next(NETWORK, attempt, false, 0, true, true));
            assertEquals(WAIT_NETWORK, next(STALL, attempt, false, 0, false, false));
            assertEquals(WAIT_NETWORK, next(HTTP_STATUS, attempt, false, 403, true, true));
        }
        // a decoder problem does not need the network to be diagnosed
        assertEquals(REOPEN, next(DECODER, 0, false, 0, false, true));
    }

    @Test
    public void aNetworkCutIsReopenedThenTheLinkIsRenewedThenTheUserIsTold() {
        assertEquals(REOPEN, next(NETWORK, 0, true, 0, true, false));
        assertEquals(REOPEN, next(NETWORK, 1, true, 0, true, false));
        assertEquals(REOPEN_SOURCE, next(NETWORK, 2, true, 0, true, false));
        assertEquals(REOPEN, next(NETWORK, 3, true, 0, true, false));
        assertEquals(GIVE_UP, next(NETWORK, 4, true, 0, true, false));
        // a direct file has no other link to ask: it is simply reopened
        assertEquals(REOPEN, next(NETWORK, 2, true, 0, false, false));
    }

    @Test
    public void anExpiredOrMovedLinkIsRenewedAtOnceAndABusyServerGetsTime() {
        for (int status : new int[]{401, 403, 404, 410}) {
            assertEquals(REOPEN_SOURCE, next(HTTP_STATUS, 0, true, status, true, false));
            assertEquals(REOPEN_SOURCE, next(HTTP_STATUS, 1, true, status, true, false));
            assertEquals(GIVE_UP, next(HTTP_STATUS, 2, true, status, true, false));
        }
        assertEquals(REOPEN, next(HTTP_STATUS, 0, true, 503, true, false));
        assertEquals(REOPEN, next(HTTP_STATUS, 1, true, 429, true, false));
        assertEquals(REOPEN_SOURCE, next(HTTP_STATUS, 2, true, 503, true, false));
        assertEquals(GIVE_UP, next(HTTP_STATUS, 3, true, 503, true, false));
        assertTrue(delayMs(HTTP_STATUS, 0, 503) > delayMs(HTTP_STATUS, 0, 403));
        assertTrue(delayMs(HTTP_STATUS, 2, 429) >= 20000);
    }

    @Test
    public void aDecoderFailureLowersTheQualityStepByStepAndStopsWhenThereIsNoneLeft() {
        assertEquals(REOPEN, next(DECODER, 0, true, 0, false, true));
        assertEquals(LOWER_QUALITY_REOPEN, next(DECODER, 1, true, 0, false, true));
        assertEquals(LOWER_QUALITY_REOPEN, next(DECODER, 2, true, 0, false, true));
        assertEquals(GIVE_UP, next(DECODER, 3, true, 0, false, true));
        assertEquals(GIVE_UP, next(DECODER, 1, true, 0, false, false));
        assertEquals(1080, lowerCeiling(0));
        assertEquals(1080, lowerCeiling(2160));
        assertEquals(720, lowerCeiling(1080));
        assertEquals(480, lowerCeiling(720));
        assertEquals(0, lowerCeiling(480));
    }

    @Test
    public void aHungStreamIsReopenedRenewedThenLoweredAndABrokenFileGetsOneRenewal() {
        assertEquals(REOPEN, next(STALL, 0, true, 0, true, true));
        assertEquals(REOPEN_SOURCE, next(STALL, 1, true, 0, true, true));
        assertEquals(LOWER_QUALITY_REOPEN, next(STALL, 2, true, 0, true, true));
        assertEquals(REOPEN, next(STALL, 2, true, 0, true, false));
        assertEquals(GIVE_UP, next(STALL, 3, true, 0, true, true));
        assertEquals(REOPEN_SOURCE, next(PARSING, 0, true, 0, true, false));
        assertEquals(GIVE_UP, next(PARSING, 1, true, 0, true, false));
        assertEquals(LIVE_EDGE, next(LIVE_WINDOW, 0, true, 0, false, false));
        assertEquals(REOPEN, next(AUDIO, 1, true, 0, false, false));
        assertEquals(GIVE_UP, next(AUDIO, 2, true, 0, false, false));
    }

    @Test
    public void everyLadderEndsAndEveryEndHasAReason() {
        for (int kind = NETWORK; kind <= OTHER; kind++) {
            boolean ended = false;
            for (int attempt = 0; attempt < 12; attempt++) {
                int a = next(kind, attempt, true, kind == HTTP_STATUS ? 403 : 0, true, true);
                if (a == GIVE_UP) { ended = true; break; }
                assertTrue(statusText(a, attempt).length() > 5);
                assertTrue(delayMs(kind, attempt, 0) >= 300 && delayMs(kind, attempt, 0) <= 30000);
            }
            assertTrue("kind " + kind + " never gives up", ended);
            assertTrue(giveUpText(kind, 404).length() > 10);
        }
    }
}
