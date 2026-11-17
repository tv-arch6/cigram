package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.ContextWrapper;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;

import org.junit.Test;

/**
 * 19.1 — the sections of Home on the REAL catalogue: a snapshot saved for one catalogue revision is still drawn (stale) for the next
 * revision, only an exact match is "fresh"; the per-process cache answers a second render without touching the catalogue text again.
 */
public class HomeSectionsSnapshotTest {

    private static final Class<?> P;
    static {
        try { P = Class.forName("com.Cigram.vid.CigramStage1InitProvider"); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Context ctx(final File dir) {
        return new ContextWrapper(null) {
            @Override public File getFilesDir() { return dir; }
            @Override public Context getApplicationContext() { return this; }
        };
    }

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = P.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static String real() throws Exception {
        String path = System.getenv("CIGRAM_CATALOGUE");
        assumeTrue("set CIGRAM_CATALOGUE", path != null && new File(path).isFile());
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, Object> grouped(Object snap) throws Exception {
        Field f = snap.getClass().getDeclaredField("grouped");
        f.setAccessible(true);
        return (LinkedHashMap<String, Object>) f.get(snap);
    }

    private static boolean stale(Object snap) throws Exception {
        Field f = snap.getClass().getDeclaredField("stale");
        f.setAccessible(true);
        return f.getBoolean(snap);
    }

    @Test public void aSnapshotOfAnOlderRevisionIsDrawnAsStaleAndNeverAsFresh() throws Exception {
        String json = real();
        File dir = Files.createTempDirectory("cigram-snap").toFile();
        Context c = ctx(dir);
        Object grouped = call("groupedFor", new Class<?>[]{Context.class, String.class}, c, json);
        int fullSections = ((LinkedHashMap<?, ?>) grouped).size();
        assertTrue("the real catalogue gives sections (administrator's + derived rows)", fullSections >= 7);
        String hash = (String) call("catalogHash", new Class<?>[]{String.class}, json);

        Object exact = call("loadSectionsSnapshot", new Class<?>[]{Context.class, String.class}, c, hash);
        assertNotNull("the exact revision is found", exact);
        assertFalse(stale(exact));
        assertEquals(fullSections, grouped(exact).size());

        String nextRevision = json.replace("\"count\":88", "\"count\":89");
        String otherHash = (String) call("catalogHash", new Class<?>[]{String.class}, nextRevision);
        assertFalse("a different revision has a different hash", otherHash.equals(hash));
        assertNull("without allowStale an old snapshot is refused (the old behaviour)",
                call("loadSectionsSnapshot", new Class<?>[]{Context.class, String.class}, c, otherHash));
        Object old = call("loadSectionsSnapshot", new Class<?>[]{Context.class, String.class, boolean.class}, c, otherHash, true);
        assertNotNull("the stale one is better than nothing: Home appears at once", old);
        assertTrue(stale(old));
        assertEquals(fullSections, grouped(old).size());
        Object noHash = call("loadSectionsSnapshot", new Class<?>[]{Context.class, String.class, boolean.class}, c, "", true);
        assertNotNull("even with no hash at all (first start after an update)", noHash);
    }

    @Test public void theSecondRenderOfTheSameRevisionIsServedFromTheProcessCache() throws Exception {
        String json = real();
        File dir = Files.createTempDirectory("cigram-snap2").toFile();
        Context c = ctx(dir);
        long t0 = System.nanoTime();
        Object first = call("groupedFor", new Class<?>[]{Context.class, String.class}, c, json);
        long coldMs = (System.nanoTime() - t0) / 1000000L;
        t0 = System.nanoTime();
        Object second = call("groupedFor", new Class<?>[]{Context.class, String.class}, c, json);
        long warmMs = (System.nanoTime() - t0) / 1000000L;
        System.out.println("[sections] cold " + coldMs + " ms, re-render from the cache " + warmMs + " ms, sections " + ((LinkedHashMap<?, ?>) first).size());
        assertEquals(((LinkedHashMap<?, ?>) first).keySet(), ((LinkedHashMap<?, ?>) second).keySet());
        assertTrue("a cached render must not redo the grouping (" + warmMs + " ms vs " + coldMs + " ms)", warmMs * 3 < coldMs || warmMs < 60);
        // a render mutates its sections: the cache hands out its own shells every time
        assertTrue(first != second);
        Object third = call("groupedFor", new Class<?>[]{Context.class, String.class}, c, json);
        assertTrue(second != third);
    }

    @Test public void aChangedCatalogueTextIsNotServedFromTheCache() throws Exception {
        String json = real();
        File dir = Files.createTempDirectory("cigram-snap3").toFile();
        Context c = ctx(dir);
        call("groupedFor", new Class<?>[]{Context.class, String.class}, c, json);
        long t0 = System.nanoTime();
        call("groupedFor", new Class<?>[]{Context.class, String.class}, c, json.replace("\"count\":88", "\"count\":90"));
        long ms = (System.nanoTime() - t0) / 1000000L;
        assertTrue("a new revision is grouped again (" + ms + " ms)", ms >= 50);
    }
}
