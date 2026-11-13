package com.Cigram.vid;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Invisible helper screen for the account cover (v5.29):
 *   1. asks for the photo + video permission (the feature stays off until the user allows it),
 *   2. opens the system picker for one image or video,
 *   3. validates and saves it through {@link CigramBanner} off the main thread, then closes.
 * The account page refreshes its header when it comes back to the foreground.
 *
 * Declared in the manifest injection with a translucent, title-less theme.
 */
public class CigramBannerActivity extends Activity {

    private static final int REQ_PERMISSION = 7101;
    private static final int REQ_PICK = 7102;
    private static final String STATE_STARTED = "cigram_banner_started";

    private boolean started;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        started = state != null && state.getBoolean(STATE_STARTED, false);
        if (started) return; // recreated while the picker / dialog is open: just wait for the result
        started = true;
        if (!CigramUserData.isLoggedIn(this)) {
            toast("سجّل دخولك أولاً لإضافة غلاف لحسابك");
            finish();
            return;
        }
        openPicker(); // ACTION_GET_CONTENT needs no storage/media permission (Play Photo & Video policy)
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(STATE_STARTED, started);
    }

    // ------------------------------------------------------------------ permission

    private static String[] mediaPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{"android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    /** Full access, or (Android 14+) access to the photos/videos the user selected. */
    private boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= 34
                && checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                        == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        for (String p : mediaPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != REQ_PERMISSION) return;
        if (hasMediaPermission()) {
            openPicker();
            return;
        }
        boolean askAgainPossible = false;
        for (String p : mediaPermissions()) {
            if (shouldShowRequestPermissionRationale(p)) askAgainPossible = true;
        }
        if (askAgainPossible) {
            toast("لن تعمل ميزة الغلاف بدون السماح بالوصول إلى الصور والفيديو");
        } else {
            // "don't ask again": the only way back is the app's system settings
            toast("فعّل إذن الصور والفيديو من إعدادات التطبيق لاستخدام الغلاف");
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            } catch (Throwable ignored) { }
        }
        finish();
    }

    // ------------------------------------------------------------------ picker

    private void openPicker() {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        try {
            startActivityForResult(Intent.createChooser(pick, "اختر صورة أو فيديو بالعرض"), REQ_PICK);
        } catch (Throwable error) {
            toast("لا يوجد تطبيق لاختيار الصور على هذا الجهاز");
            finish();
        }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_PICK) return;
        final Uri uri = result == RESULT_OK && data != null ? data.getData() : null;
        if (uri == null) {
            finish();
            return;
        }
        toast("جاري تجهيز الغلاف…");
        final android.content.Context app = getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                final CigramBanner.Result r = CigramBanner.store(app, uri);
                main.post(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(app, r.message, r.ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                        if (!isFinishing()) finish();
                    }
                });
            }
        }, "cigram-banner-store").start();
    }

    private void toast(String text) {
        CigramSilent.toast(getApplicationContext(), text, android.widget.Toast.LENGTH_LONG);
    }
}
