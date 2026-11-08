package com.Cigram.vid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * Runtime permissions, the same way everywhere (2026):
 * <ol>
 *   <li>already granted → nothing is shown;</li>
 *   <li>asked only when the feature that needs it is used (never at start-up);</li>
 *   <li>a short Arabic explanation comes first, then the system dialog;</li>
 *   <li>after a permanent «don't ask again» the system dialog cannot appear any more, so the user is offered the app's
 *       settings page instead of a button that silently does nothing;</li>
 *   <li>a refusal is respected: the feature says what it cannot do, the rest of the app keeps working.</li>
 * </ol>
 * Picking photos / videos needs no permission at all: {@link #pickVisualMedia} uses the system photo picker where it
 * exists and the document picker elsewhere.
 */
final class CigramPermissions {

    private static final String PREFS = "cigram_permissions";

    private CigramPermissions() { }

    static boolean has(Context c, String permission) {
        if (c == null) return false;
        if (Build.VERSION.SDK_INT < 23) return true;
        // POST_NOTIFICATIONS exists from Android 13; below it notifications need no runtime permission
        if ("android.permission.POST_NOTIFICATIONS".equals(permission) && Build.VERSION.SDK_INT < 33) return true;
        return c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * true = granted already (go ahead). false = an explanation / the system dialog / the settings offer is on screen;
     * the result arrives in the activity's onRequestPermissionsResult with {@code requestCode}.
     */
    static boolean ensure(final Activity a, final String permission, final int requestCode, String why) {
        if (has(a, permission)) return true;
        if (a == null || a.isFinishing()) return false;
        final SharedPreferences p = prefs(a);
        boolean askedBefore = p.getBoolean("asked::" + permission, false);
        boolean rationale = Build.VERSION.SDK_INT >= 23 && a.shouldShowRequestPermissionRationale(permission);
        try {
            if (askedBefore && !rationale) {
                // permanently refused: only the settings page can change it
                new AlertDialog.Builder(a)
                        .setTitle(CigramPageKit.rtl("الإذن مطلوب"))
                        .setMessage(CigramPageKit.rtl(why + "\n\nالإذن مرفوض حالياً. يمكنك تفعيله من إعدادات التطبيق ← الأذونات."))
                        .setPositiveButton("فتح الإعدادات", (d, w) -> openAppSettings(a))
                        .setNegativeButton("ليس الآن", null)
                        .show();
                return false;
            }
            new AlertDialog.Builder(a)
                    .setTitle(CigramPageKit.rtl("نحتاج إذنك"))
                    .setMessage(CigramPageKit.rtl(why))
                    .setPositiveButton("متابعة", (d, w) -> {
                        p.edit().putBoolean("asked::" + permission, true).apply();
                        if (Build.VERSION.SDK_INT >= 23) a.requestPermissions(new String[]{permission}, requestCode);
                    })
                    .setNegativeButton("ليس الآن", null)
                    .show();
        } catch (Throwable error) {
            // no dialog possible (window gone): ask directly, never crash
            p.edit().putBoolean("asked::" + permission, true).apply();
            if (Build.VERSION.SDK_INT >= 23) a.requestPermissions(new String[]{permission}, requestCode);
        }
        return false;
    }

    static void openAppSettings(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.getPackageName(), null));
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) { }
    }

    /** True when the system photo picker is available (Android 13+, or 11-12 with the SDK extension 2+). */
    static boolean hasPhotoPicker() {
        if (Build.VERSION.SDK_INT >= 33) return true;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    /** Intent to pick ONE image ("image/*") or video ("video/*") without any storage permission. */
    static Intent pickVisualMedia(String mime) {
        if (hasPhotoPicker()) {
            Intent i = new Intent("android.provider.action.PICK_IMAGES");
            i.setType(mime);
            return i;
        }
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        return Intent.createChooser(i, "اختر");
    }
}
