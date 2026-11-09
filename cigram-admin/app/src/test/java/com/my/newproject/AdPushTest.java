package com.my.newproject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** 19.0 — the pure decisions of the admin's push client: what a build still lacks, when to send the token, id deduplication. */
public class AdPushTest {

    @Test public void aBuildWithoutFirebaseSettingsNamesEveryMissingItem() {
        List<String> m = AdFcm.missing("", "", "cigram", "");
        assertEquals(3, m.size());
        assertTrue(m.get(0).startsWith("ADMIN_FCM_APP_ID"));
        assertTrue(m.get(1).startsWith("ADMIN_FCM_API_KEY"));
        assertTrue(m.get(2).startsWith("ADMIN_FCM_SENDER_ID"));
    }

    @Test public void aMalformedAppIdIsNotAccepted() {
        assertFalse(AdFcm.missing("not-an-app-id", "AIzaSyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "cigram", "288492524620").isEmpty());
        assertFalse(AdFcm.missing("1:288492524620:ios:abcdef", "AIzaSyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "cigram", "288492524620").isEmpty());
    }

    @Test public void aCompleteConfigurationIsAccepted() {
        assertTrue(AdFcm.missing("1:288492524620:android:87753e42e5d44cfa0a90e9", "AIzaSyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "cigram", "288492524620").isEmpty());
    }

    @Test public void theTokenIsSentWhenItChangesAndRefreshedDaily() {
        long day = 24L * 60 * 60 * 1000, t0 = 9_000_000_000L;
        assertTrue(AdFcm.shouldRegister("", "tok", 0, 0, t0, false));
        assertTrue(AdFcm.shouldRegister("old", "new", t0 - 5, 0, t0, false));
        assertFalse(AdFcm.shouldRegister("tok", "tok", t0 - 1000, 0, t0, false));
        assertTrue(AdFcm.shouldRegister("tok", "tok", t0 - day - 1, t0 - 16 * 60_000L, t0, false));
        assertFalse("not in a tight loop", AdFcm.shouldRegister("tok", "tok", t0 - 2 * day, t0 - 60_000L, t0, false));
        assertFalse(AdFcm.shouldRegister("tok", "", 0, 0, t0, true));
        assertTrue(AdFcm.shouldRegister("tok", "tok", t0, t0, t0, true));
    }

    @Test public void theSameMessageIsNotifiedOnce() {
        List<String> a = AdInboxNotifier.remember(new ArrayList<String>(), "m_1", 60);
        assertEquals(Arrays.asList("m_1"), a);
        assertNull(AdInboxNotifier.remember(a, "m_1", 60));
        assertNotNull(AdInboxNotifier.remember(a, "m_2", 60));
        assertNotNull("no id: never blocked", AdInboxNotifier.remember(a, "", 60));
    }

    @Test public void theIdMemoryIsBounded() {
        List<String> s = new ArrayList<String>();
        for (int i = 0; i < 200; i++) s = AdInboxNotifier.remember(s, "m_" + i, 60);
        assertEquals(60, s.size());
        assertEquals("m_140", s.get(0));
    }
}
