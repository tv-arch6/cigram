package com.Cigram.vid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/** 19.6.1 «حماية التطبيق»: the tamper guard and the HTTPS-only network rules. */
public class IntegrityTest {

    @Test public void hexIsUpperCaseAndPadded() {
        assertEquals("000FA0FF", CigramIntegrity.hex(new byte[] { 0, 15, (byte) 0xA0, (byte) 0xFF }));
    }

    @Test public void cleanProcessHasNoHookingTool() {
        assertNull(CigramIntegrity.hookingTool());
    }

    @Test public void debugBuildIsNeverBlocked() {
        assertTrue(BuildConfig.DEBUG);
        assertTrue(CigramIntegrity.genuine());
    }

    @Test public void ownBackEndsAreHttpsOnlyWithSystemCertificates() throws Exception {
        File f = new File("src/main/res/xml/network_security_config.xml");
        if (!f.isFile()) f = new File("app/src/main/res/xml/network_security_config.xml");
        String xml = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        int domains = xml.indexOf("<domain-config cleartextTrafficPermitted=\"false\">");
        assertTrue("own domains must refuse plain http", domains > 0);
        String own = xml.substring(domains);
        assertTrue(own.contains("workers.dev"));
        assertTrue(own.contains("r2.dev"));
        assertTrue(own.contains("<certificates src=\"system\" />"));
        assertFalse("user-installed CAs must not be trusted", xml.contains("src=\"user\""));
    }

    @Test public void gradlePinsTheReleaseSigner() throws Exception {
        File f = new File("build.gradle.kts");
        if (!f.isFile()) f = new File("app/build.gradle.kts");
        String g = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue(g.contains("resValue(\"string\", \"cigram_signer_sha256\", releaseSignerSha256())"));
        // Play re-signs with Google's key: the play flavor must never pin the upload key
        assertTrue(g.contains("System.getenv(\"CIGRAM_PLAY_SIGNER_SHA256\")"));
        assertTrue(g.contains("versionCode = 101"));
        assertTrue(g.contains("versionName = \"19.6.1\""));
    }

    @Test public void guardIsInstalledBeforeTheFirstActivity() throws Exception {
        File f = new File("src/main/java/com/Cigram/vid/CigramStage1InitProvider.java");
        if (!f.isFile()) f = new File("app/src/main/java/com/Cigram/vid/CigramStage1InitProvider.java");
        String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue(s.contains("CigramIntegrity.install((Application) appContext)"));
    }
}
