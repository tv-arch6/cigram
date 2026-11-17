package com.my.newproject;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * Android 15+ edge-to-edge readiness (18.2).
 *
 * Today the admin app targets API 34, where the platform does not force edge-to-edge. The day the owner raises
 * targetSdk to 35 / 36, every page built by {@link CgBase} would otherwise slide under the status bar, the navigation bar
 * and the keyboard. This helper keeps the classic layout: it pads the page by the system bars, the display cutout and the
 * keyboard (IME) and consumes the insets so nothing pads twice. It does nothing below Android 15 or while the app still
 * targets API 34, so behaviour today is unchanged.
 */
final class CgSystemBars {

    private CgSystemBars() { }

    static void fit(final Activity a) {
        if (a == null || Build.VERSION.SDK_INT < 35) return;
        try {
            if (a.getApplicationInfo().targetSdkVersion < 35) return;
            final View content = a.findViewById(android.R.id.content);
            View decor = a.getWindow() == null ? null : a.getWindow().getDecorView();
            if (content == null || decor == null) return;
            View host = content;
            while (host.getParent() instanceof View && host.getParent() != decor) host = (View) host.getParent();
            host.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                    content.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                    return WindowInsets.CONSUMED;
                }
            });
            host.requestApplyInsets();
        } catch (Throwable error) {
            android.util.Log.w("CgSystemBars", "fit failed", error);
        }
    }
}
