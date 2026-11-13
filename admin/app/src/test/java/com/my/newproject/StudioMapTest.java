package com.my.newproject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Studio 4: the map of tools is complete and consistent (JVM, no device). */
public class StudioMapTest {

    @Test
    public void toolIdsAreUnique() {
        Set<String> seen = new HashSet<String>();
        for (StudioMap.Tool t : StudioMap.TOOLS) assertTrue("duplicate tool id " + t.id, seen.add(t.id));
    }

    @Test
    public void everyRoomHasToolsAndEveryToolSitsInAGroupOfItsRoom() {
        for (int r = 0; r < StudioMap.ROOMS.length; r++) {
            StudioMap.Room room = StudioMap.ROOMS[r];
            assertEquals("room index must equal its position", r, room.index);
            assertTrue(room.title + " has no tools", StudioMap.inRoom(r).size() > 0);
            int viaGroups = 0;
            for (String g : room.groups) {
                int n = StudioMap.inGroup(r, g).size();
                assertTrue("group «" + g + "» of " + room.title + " is empty", n > 0);
                viaGroups += n;
            }
            assertEquals("a tool of " + room.title + " is in no declared group", StudioMap.inRoom(r).size(), viaGroups);
        }
        for (StudioMap.Tool t : StudioMap.TOOLS) {
            assertTrue(t.id + ": room out of range", t.room >= 0 && t.room < StudioMap.ROOMS.length);
        }
    }

    @Test
    public void everyToolCanBeOpened() {
        for (StudioMap.Tool t : StudioMap.TOOLS) {
            assertTrue(t.id + ": no title", t.title.length() > 0);
            assertTrue(t.id + ": no description", t.sub.length() > 0);
            assertTrue(t.id + ": unknown action " + t.action, t.action >= StudioMap.A_OPEN && t.action <= StudioMap.A_TOKEN);
            if (t.action == StudioMap.A_OPEN) assertNotNull(t.id + ": A_OPEN needs a target screen", t.target);
            else assertNull(t.id + ": an action tool must not also name a screen", t.target);
        }
    }

    @Test
    public void lookupsWork() {
        assertEquals("bulk", StudioMap.byId("bulk").id);
        assertNull(StudioMap.byId("no_such_tool"));
        assertNull(StudioMap.byId(null));
    }

    /**
     * Every screen declared in AndroidManifest.xml is either a tool of the Studio, reached through a tool's action, a known
     * leaf page, or one of the Studio's own screens — and every class named here is really declared. A new screen added to the
     * admin without a place in the Studio fails this test.
     */
    @Test
    public void everyManifestScreenHasAPlaceInTheStudio() throws Exception {
        Set<String> declared = manifestActivities();
        Set<String> known = new TreeSet<String>();
        for (StudioMap.Tool t : StudioMap.TOOLS) if (t.target != null) known.add(t.target.getName());
        for (Class<?> c : StudioMap.VIA_ACTION) known.add(c.getName());
        for (Class<?> c : StudioMap.LEAF) known.add(c.getName());
        for (Class<?> c : StudioMap.SHELL) known.add(c.getName());

        Set<String> missingFromStudio = new TreeSet<String>(declared);
        missingFromStudio.removeAll(known);
        assertTrue("declared in the manifest but not placed in StudioMap: " + missingFromStudio, missingFromStudio.isEmpty());

        Set<String> notDeclared = new TreeSet<String>(known);
        notDeclared.removeAll(declared);
        assertTrue("named in StudioMap but not declared in the manifest (it would crash when opened): " + notDeclared, notDeclared.isEmpty());
    }

    @Test
    public void aToolIsNotAlsoALeaf() {
        Set<String> leaf = new HashSet<String>();
        for (Class<?> c : StudioMap.LEAF) leaf.add(c.getName());
        for (StudioMap.Tool t : StudioMap.TOOLS) {
            if (t.target != null) assertTrue(t.id + " is a tool and listed as a leaf page", !leaf.contains(t.target.getName()));
        }
    }

    @Test
    public void inRoomKeepsTheRegistryOrder() {
        List<StudioMap.Tool> lib = StudioMap.inRoom(StudioMap.LIBRARY);
        assertEquals("whats_new", lib.get(0).id);
    }

    // ------------------------------------------------------------------ manifest

    private static Set<String> manifestActivities() throws Exception {
        File f = null;
        String[] candidates = {"src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml", "../app/src/main/AndroidManifest.xml"};
        for (String c : candidates) {
            File x = new File(c);
            if (x.isFile()) { f = x; break; }
        }
        assertNotNull("AndroidManifest.xml not found from " + new File(".").getAbsolutePath(), f);
        String xml = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        Set<String> out = new TreeSet<String>();
        Matcher m = Pattern.compile("<activity\\b[^>]*?android:name=\"([^\"]+)\"", Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            String n = m.group(1);
            out.add(n.startsWith(".") ? "com.my.newproject" + n : n);     // the manifest may declare a class relative to the package
        }
        assertTrue("no activities parsed from the manifest", out.size() > 20);
        return out;
    }
}
