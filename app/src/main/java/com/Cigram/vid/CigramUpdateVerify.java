package com.Cigram.vid;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Update package gate — runs BEFORE the system installer is opened. FAIL-CLOSED since 19.6.
 *
 * Android itself refuses an update signed by a different key, but the updater must not depend on that single wall:
 * <ul>
 *   <li><b>transport</b>: the package must come from an {@code https://} URL ({@link #isSecureUrl}); a plain-http link is a
 *   man-in-the-middle swap waiting to happen and is never offered;</li>
 *   <li><b>integrity</b>: the published manifest MUST carry a {@code sha256} and the downloaded file MUST match it. A manifest
 *   without a fingerprint is refused ({@link Verdict#NO_HASH}) instead of being installed on trust;</li>
 *   <li><b>identity</b>: when the archive can be read, it must be this very package, newer than the installed build, and
 *   signed by the same certificate as the installed app (or by a certificate whose rotation lineage contains it) —
 *   {@link Verdict#BAD_SIGNER} otherwise;</li>
 *   <li><b>no guessing</b>: an unexpected failure while checking refuses the file ({@link Verdict#ERROR}).</li>
 * </ul>
 * One deliberate tolerance remains: a package whose manifest Android cannot parse from this process (some TV boxes) is not
 * rejected for that alone — its SHA-256 already matched the published value and the installer still performs the final
 * signature check.
 */
final class CigramUpdateVerify {

    private CigramUpdateVerify() { }

    enum Verdict { OK, EMPTY, NO_HASH, HASH_MISMATCH, WRONG_PACKAGE, NOT_NEWER, BAD_SIGNER, ERROR }

    // ------------------------------------------------------------------------------------------------- pure decision

    /**
     * The pure decision (no Android types), so every branch is testable.
     * {@code archivePackage} null = the archive could not be read; empty signer collections = certificates unavailable.
     *
     * @param archiveSigners   SHA-256 digests of the archive's signing certificate(s) incl. its rotation lineage
     * @param installedSigners SHA-256 digests of the installed app's CURRENT signing certificate(s)
     */
    static Verdict decide(long fileLength, String actualSha256, String expectedSha256,
                          String archivePackage, long archiveVersionCode, String ownPackage, long installedVersionCode,
                          Collection<String> archiveSigners, Collection<String> installedSigners) {
        if (fileLength <= 0L) return Verdict.EMPTY;
        String want = normalizeHash(expectedSha256);
        if (want.length() == 0) return Verdict.NO_HASH;
        String got = actualSha256 == null ? "" : actualSha256.trim().toLowerCase(Locale.ROOT);
        if (!want.equals(got)) return Verdict.HASH_MISMATCH;
        if (archivePackage != null) {
            if (!archivePackage.equals(ownPackage)) return Verdict.WRONG_PACKAGE;
            if (installedVersionCode > 0L && archiveVersionCode <= installedVersionCode) return Verdict.NOT_NEWER;
        }
        if (archiveSigners != null && !archiveSigners.isEmpty() && installedSigners != null && !installedSigners.isEmpty()
                && !intersects(archiveSigners, installedSigners)) {
            return Verdict.BAD_SIGNER;
        }
        return Verdict.OK;
    }

    private static boolean intersects(Collection<String> a, Collection<String> b) {
        for (String s : a) {
            if (s != null && b.contains(s)) return true;
        }
        return false;
    }

    /** A usable 64-hex SHA-256, lower-case; "" when the manifest has none (or something that is not a SHA-256). */
    static String normalizeHash(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("sha256:")) s = s.substring(7).trim();
        if (s.length() != 64) return "";
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return "";
        }
        return s;
    }

    /**
     * True only for an absolute {@code https://} URL with a real host and no user-info
     * ({@code https://trusted.example@evil.example/} is refused: its host is the part after the {@code @}).
     */
    static boolean isSecureUrl(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (u.length() < 9 || !u.regionMatches(true, 0, "https://", 0, 8)) return false;
        try {
            URI uri = new URI(u);
            String host = uri.getHost();
            return host != null && host.length() > 0 && uri.getUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------- hashing

    static String sha256Hex(File file) throws IOException {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(file);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }

    static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------- Android glue

    /** Signing flag matching the platform: SigningInfo (API 28+) or the legacy signatures array. */
    @SuppressWarnings("deprecation")
    private static int signingFlags() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    /** Certificates that CURRENTLY sign the package (for the installed app). */
    @SuppressWarnings("deprecation")
    static Set<String> currentSigners(PackageInfo info) {
        Set<String> out = new HashSet<String>();
        if (info == null) return out;
        try {
            if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
                addAll(out, info.signingInfo.getApkContentsSigners());
            }
            if (out.isEmpty()) addAll(out, info.signatures);
        } catch (Throwable ignored) { /* unreadable: no certificates → the check is skipped, the hash already matched */ }
        return out;
    }

    /** Current certificates plus the rotation lineage (for the downloaded archive: a rotated key still proves the same owner). */
    static Set<String> lineageSigners(PackageInfo info) {
        Set<String> out = currentSigners(info);
        try {
            if (Build.VERSION.SDK_INT >= 28 && info != null && info.signingInfo != null) {
                addAll(out, info.signingInfo.getSigningCertificateHistory());
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static void addAll(Set<String> out, Signature[] signatures) {
        if (signatures == null) return;
        for (Signature s : signatures) {
            if (s != null) out.add(sha256Hex(s.toByteArray()));
        }
    }

    /** Reads the file and the manifest and decides. Never throws and never guesses: any unexpected failure refuses the file. */
    static Verdict verify(Context context, File apk, JSONObject manifest, long installedVersionCode) {
        try {
            long length = apk == null ? 0L : apk.length();
            String expected = manifest == null ? "" : manifest.optString("sha256", "");
            // hashing is only worth the I/O when there is something to compare against
            String actual = length > 0L && normalizeHash(expected).length() > 0 ? sha256Hex(apk) : "";
            String pkg = null;
            long code = 0L;
            Set<String> archiveSigners = new HashSet<String>();
            Set<String> installedSigners = new HashSet<String>();
            if (length > 0L) {
                try {
                    PackageManager pm = context.getPackageManager();
                    int flags = signingFlags();
                    PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
                    if (archive != null) {
                        pkg = archive.packageName;
                        code = Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() : archive.versionCode;
                        archiveSigners = lineageSigners(archive);
                        installedSigners = currentSigners(pm.getPackageInfo(context.getPackageName(), flags));
                    }
                } catch (Throwable ignored) { /* unreadable here: SHA-256 already pinned the bytes, the installer signs off */ }
            }
            return decide(length, actual, expected, pkg, code, context.getPackageName(), installedVersionCode,
                    archiveSigners, installedSigners);
        } catch (Throwable t) {
            CigramDiag.warn("updater", "verify", "verify failed, update refused", t);
            return Verdict.ERROR;
        }
    }

    /** The sentence shown to the viewer for a refused file. */
    static String messageFor(Verdict v) {
        switch (v) {
            case EMPTY: return "الملف الذي نزل فارغ أو ناقص. تأكد من الاتصال وحاول مرة أخرى.";
            case NO_HASH: return "التحديث المنشور لا يحمل بصمة SHA-256، لذلك لم يُفتح للتثبيت حمايةً لك.";
            case HASH_MISMATCH: return "الملف الذي نزل لا يطابق بصمة التحديث المنشورة، لذلك لم يُفتح للتثبيت.";
            case WRONG_PACKAGE: return "الملف الذي نزل ليس تحديثاً لسيكرام، لذلك لم يُفتح للتثبيت.";
            case NOT_NEWER: return "الملف الذي نزل ليس أحدث من نسختك الحالية، لذلك لم يُفتح للتثبيت.";
            case BAD_SIGNER: return "الملف الذي نزل موقّع بشهادة مختلفة عن شهادة تطبيقك، لذلك لم يُفتح للتثبيت.";
            case ERROR: return "تعذّر فحص ملف التحديث، لذلك لم يُفتح للتثبيت.";
            default: return "";
        }
    }
}
