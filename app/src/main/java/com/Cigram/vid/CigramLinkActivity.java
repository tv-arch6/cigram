package com.Cigram.vid;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * v10.62: نشاط شفاف يستقبل روابط cigram://title و cigram://requests (من صفحة المشاركة أو من إشعار)
 * ثم يفتح الشاشة المناسبة ويُغلق نفسه. ينتظر الكتالوج حتى ثانيتين إذا فُتح التطبيق للتو من الرابط.
 */
public class CigramLinkActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private int attempts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Uri uri = getIntent() == null ? null : getIntent().getData();
        if (uri == null) {
            openHome();
            return;
        }
        waitForCatalog(uri);
    }

    private void waitForCatalog(final Uri uri) {
        boolean ready;
        try {
            ready = !CigramContent.catalog().isEmpty();
        } catch (Throwable ignored) {
            ready = true;
        }
        if (!ready && "title".equals(uri.getHost()) && attempts++ < 13) {
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!isFinishing()) waitForCatalog(uri);
                }
            }, 150L);
            return;
        }
        boolean handled = false;
        try {
            handled = CigramLinks.open(this, uri);
        } catch (Throwable ignored) { }
        if (!handled) {
            openHome();
            return;
        }
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);   // the catalog-wait retries must not outlive the page
        super.onDestroy();
    }

    private void openHome() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (i != null) startActivity(i);
        } catch (Throwable ignored) { }
        finish();
    }
}
