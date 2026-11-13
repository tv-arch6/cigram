package com.my.newproject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Studio 4 pins and recents: the list logic (JVM, no device). */
public class StudioPrefsTest {

    @Test
    public void parseAndJoinRoundTrip() {
        List<String> p = StudioPrefs.parse(" a, b ,,a,c ");
        assertEquals(Arrays.asList("a", "b", "c"), p);
        assertEquals("a,b,c", StudioPrefs.join(p));
        assertEquals(p, StudioPrefs.parse(StudioPrefs.join(p)));
        assertTrue(StudioPrefs.parse(null).isEmpty());
        assertTrue(StudioPrefs.parse("").isEmpty());
        assertEquals("", StudioPrefs.join(new ArrayList<String>()));
    }

    @Test
    public void sanitizeDropsUnknownToolsAndDuplicates() {
        List<String> s = StudioPrefs.sanitize(Arrays.asList("quick_add", "removed_tool", "quick_add", "bulk"));
        assertEquals(Arrays.asList("quick_add", "bulk"), s);
    }

    @Test
    public void everyDefaultPinIsARealTool() {
        List<String> defaults = Arrays.asList(StudioPrefs.DEFAULT_PINS);
        assertEquals("a default pin names a tool that does not exist", defaults.size(), StudioPrefs.sanitize(defaults).size());
        assertTrue(defaults.size() <= StudioPrefs.MAX_PINS);
    }

    @Test
    public void togglePinAddsThenRemoves() {
        List<String> none = new ArrayList<String>();
        List<String> one = StudioPrefs.togglePin(none, "bulk", 3);
        assertEquals(Arrays.asList("bulk"), one);
        assertTrue("the input list must not be changed", none.isEmpty());
        List<String> back = StudioPrefs.togglePin(one, "bulk", 3);
        assertTrue(back.isEmpty());
    }

    @Test
    public void aFullPinListDropsItsOldestPin() {
        List<String> p = Arrays.asList("a", "b", "c");
        List<String> next = StudioPrefs.togglePin(p, "d", 3);
        assertEquals(Arrays.asList("b", "c", "d"), next);
        assertFalse(next.contains("a"));
    }

    @Test
    public void recentsAreMostRecentFirstWithoutDuplicatesAndCapped() {
        List<String> r = new ArrayList<String>();
        r = StudioPrefs.pushRecent(r, "a", 3);
        r = StudioPrefs.pushRecent(r, "b", 3);
        r = StudioPrefs.pushRecent(r, "c", 3);
        assertEquals(Arrays.asList("c", "b", "a"), r);
        r = StudioPrefs.pushRecent(r, "a", 3);                       // opened again: moves to the front
        assertEquals(Arrays.asList("a", "c", "b"), r);
        r = StudioPrefs.pushRecent(r, "d", 3);                       // cap
        assertEquals(Arrays.asList("d", "a", "c"), r);
    }
}
