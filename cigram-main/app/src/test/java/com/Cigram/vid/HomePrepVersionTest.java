package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 19.1 — the snapshot of the previous app version is used, the one of a newer version is not. */
public class HomePrepVersionTest {
    @Test public void dottedVersionsCompareNumerically() {
        assertEquals(0, HomePrep.compareVersions("18.5.0", "18.5.0"));
        assertTrue(HomePrep.compareVersions("18.4.0", "18.5.0") < 0);
        assertTrue(HomePrep.compareVersions("18.10.0", "18.9.0") > 0);
        assertTrue("numeric, not alphabetical", HomePrep.compareVersions("18.9.0", "18.10.0") < 0);
        assertTrue(HomePrep.compareVersions("19.0", "18.9.9") > 0);
        assertEquals("missing parts are zero", 0, HomePrep.compareVersions("18.5", "18.5.0"));
    }

    @Test public void garbageNeverThrows() {
        assertEquals(0, HomePrep.compareVersions("", ""));
        assertEquals(0, HomePrep.compareVersions(null, null));
        assertTrue(HomePrep.compareVersions("18.5.0-beta", "18.5.0") == 0);
        assertTrue(HomePrep.compareVersions("99999999999999999999.0", "1.0") > 0);
    }
}
