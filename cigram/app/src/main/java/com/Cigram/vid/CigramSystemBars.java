package com.Cigram.vid;

import android.app.Activity;
import android.os.Build;
import android.view.View;

/**
 * targetSdk 36 compatibility (2026).
 *
 * From Android 15, an app that targets API 35+ is drawn edge-to-edge: its content goes under the status bar and the
 * navigation bar, and the bar colours it sets are ignored. Cigram's pages were laid out for the classic window (content
 * between the bars). Instead of rewriting hundreds of hand-built layouts, every regular page asks its content view to
 * fit the system windows again, which gives exactly the target-24 layout: the window background (the theme's page
 * colour) shows behind the transparent bars, and the IME inset still shrinks the page like adjustResize.
 *
 * Full-screen pages (players, the TV front-end, transparent hand-off pages) keep their own immersive handling.
 */
final class CigramSystemBars {

    private static final String[] OWN_INSETS = {
            "PlayerActivity", "LiveActivity", "CigramTogetherPlayerActivity", "CigramWaitActivity",
            "CigramBannerActivity", "CigramLinkActivity", "CigramWidgetOpenActivity", "CigramTvActivity",
            "CigramTvDetailsActivity"
    };

    /** Marker so a page is fitted once . */
    private static final Object FITTED = new Object();

    private CigramSystemBars() { }

    static void fit(Activity a) {
        if (a == null || Build.VERSION.SDK_INT < 35) return;
        try {
            if (a.getApplicationInfo().targetSdkVersion < 35) return;
            String name = a.getClass().getName();
            if (!name.startsWith("com.Cigram.vid.")) return;   // AdMob / other library screens handle themselves
            String simple = a.getClass().getSimpleName();
            for (String s : OWN_INSETS) if (s.equals(simple)) return;
            View content = a.findViewById(android.R.id.content);
            if (content == null || content.getTag(R.id.cg_insets_fitted) == FITTED) return;
            content.setTag(R.id.cg_insets_fitted, FITTED);
            // An explicit listener (fitsSystemWindows alone is not honoured once Android 15 enforces edge-to-edge):
            // the page sits between the status bar (and cutout) and the navigation bar or the keyboard, exactly
            // like the classic window, and the insets are consumed so no inner view pads them a second time.
            content.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets) {
                    // the ROOT insets: AppCompat's sub-decor consumes the status-bar inset before it reaches the
                    // content view (seen on the Android 15 emulator), the root ones are complete
                    android.view.WindowInsets root = v.getRootWindowInsets();
                    android.view.WindowInsets src = root != null ? root : insets;
                    android.graphics.Insets bars = src.getInsets(android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
                    android.graphics.Insets ime = src.getInsets(android.view.WindowInsets.Type.ime());
                    v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
                    return android.view.WindowInsets.CONSUMED;
                }
            });
            content.requestApplyInsets();
        } catch (Throwable error) {
            android.util.Log.w("CigramSystemBars", "fit failed", error);
        }
    }
}
