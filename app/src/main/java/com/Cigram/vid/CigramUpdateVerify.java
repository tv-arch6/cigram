package com.Cigram.vid;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 19.3 — checks the downloaded update package BEFORE the system installer is opened.
 *
 * Until now the updater opened the installer for whatever the download produced. Android itself refuses an update that is
 * signed by someone else, so this is defence in depth, not the only wall — but it closes three real gaps:
 * <ul>
 *   <li>a download that is empty or cut short (a bad connection) used to reach the installer and end in the system's
 *   «problem parsing the package»;</li>
 *   <li>a link that served a different app (or an older build of this one) opened an installer for it;</li>
 *   <li>when the update manifest carries a {@code sha256} for the file (optional field), the file must match it: a
 *   tampered or swapped file is deleted and never offered to the installer.</li>
 * </ul>
 * A package whose manifest Android cannot read here is NOT blocked (some devices cannot parse a valid file from this
 * process): the installer, which does the final signature check, decides. Nothing is rejected on a guess.
 */
final class CigramUpdateVerify {

    private CigramUpdateVerify() { }

    enum Verdict { OK, EMPTY, HASH_MISMATCH, WRONG_PACKAGE, NOT_NEWER }

    /** The pure decision (no Android types), so every branch is testable. {@code archivePackage} null = could not be read. */
    static Verdict decide(long fileLength, String actualSha256, String expectedSha256,
                          String archivePackage, long archiveVersionCode, String ownPackage, long installedVersionCode) {
        if (fileLength <= 0L) return Verdict.EMPTY;
        String want = normalizeHash(expectedSha256);
        if (want.length() > 0 && !want.equalsIgnoreCase(actualSha256 == null ? "" : actualSha256)) return Verdict.HASH_MISMATCH;
        if (archivePackage != null) {
            if (!archivePackage.equals(ownPackage)) return Verdict.WRONG_PACKAGE;
            if (installedVersionCode > 0L && archiveVersionCode <= installedVersionCode) return Verdict.NOT_NEWER;
        }
        return Verdict.OK;
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

    static String sha256Hex(File file) throws IOException {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(file);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }

    /** Reads the file and the manifest and decides. Never throws: an unexpected failure is "OK" (the installer decides). */
    static Verdict verify(Context context, File apk, JSONObject manifest, long installedVersionCode) {
        try {
            long length = apk == null ? 0L : apk.length();
            String expected = manifest == null ? "" : manifest.optString("sha256", "");
            String actual = length > 0L && normalizeHash(expected).length() > 0 ? sha256Hex(apk) : "";
            String pkg = null;
            long code = 0L;
            if (length > 0L) {
                try {
                    PackageInfo info = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                    if (info != null) {
                        pkg = info.packageName;
                        code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
                    }
                } catch (Throwable ignored) { /* unreadable here: left to the installer */ }
            }
            return decide(length, actual, expected, pkg, code, context.getPackageName(), installedVersionCode);
        } catch (Throwable t) {
            CigramDiag.warn("updater", "verify", "verify failed, installer decides", t);
            return Verdict.OK;
        }
    }

    /** The sentence shown to the viewer for a refused file. */
    static String messageFor(Verdict v) {
        switch (v) {
            case EMPTY: return "الملف الذي نزل فارغ أو ناقص. تأكد من الاتصال وحاول مرة أخرى.";
            case HASH_MISMATCH: return "الملف الذي نزل لا يطابق بصمة التحديث المنشورة، لذلك لم يُفتح للتثبيت.";
            case WRONG_PACKAGE: return "الملف الذي نزل ليس تحديثاً لسيكرام، لذلك لم يُفتح للتثبيت.";
            case NOT_NEWER: return "الملف الذي نزل ليس أحدث من نسختك الحالية، لذلك لم يُفتح للتثبيت.";
            default: return "";
        }
    }
}
