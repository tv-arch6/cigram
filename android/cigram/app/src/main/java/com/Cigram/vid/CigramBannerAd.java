package com.Cigram.vid;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;

/**
 * The AdMob banner at the bottom of a page (first used by «قائمة المشاهدة»).
 *
 * An anchored adaptive banner in its own slot under the page's scrolling content. The slot has no height until an ad
 * has really loaded, so a page never shows an empty box or an error text; Cigram Plus and ad-free accounts get no slot
 * at all. The unit id comes from the remote ad config (key {@code banner_unit}) — see {@link CigramAdConfig#bannerUnit}.
 */
final class CigramBannerAd {

    static final String TAG = "cigram_bottom_banner";

    private CigramBannerAd() { }

    /** Adds the banner as the LAST child of {@code column} (a vertical page root whose content has weight). */
    static void installBottom(final Activity a, LinearLayout column) {
        if (a == null || column == null || a.isFinishing()) return;
        try {
            if (column.findViewWithTag(TAG) != null) return;
            if (CigramTvMode.isTv(a) || !CigramAdConfig.adsEnabled(a) || CigramAdConfig.adFreeUser(a)) return;
            String unit = CigramAdConfig.bannerUnit(a);
            if (unit.length() == 0) return;
            if (!CigramAdManager.isInitialized()) CigramAdManager.initialize(a.getApplicationContext());

            final FrameLayout slot = new FrameLayout(a);
            slot.setTag(TAG);
            slot.setVisibility(View.GONE);
            slot.setBackgroundColor(CigramPalette.of(a).page);
            column.addView(slot, new LinearLayout.LayoutParams(-1, -2));

            final AdView ad = new AdView(a);
            ad.setAdUnitId(unit);
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            int widthDp = Math.max(280, (int) (dm.widthPixels / dm.density));
            ad.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(a, widthDp));
            ad.setAdListener(new AdListener() {
                @Override public void onAdLoaded() { slot.setVisibility(View.VISIBLE); }
                @Override public void onAdFailedToLoad(LoadAdError error) { slot.setVisibility(View.GONE); }
                @Override public void onAdImpression() { CigramAdManager.report(a.getApplicationContext(), "ad_shown", "watchlist_banner", ""); }
            });
            slot.addView(ad, new FrameLayout.LayoutParams(-1, -2));
            slot.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) { }
                @Override public void onViewDetachedFromWindow(View v) {
                    try { ad.destroy(); } catch (Throwable ignored) { }
                }
            });
            ad.loadAd(new AdRequest.Builder().build());
        } catch (Throwable error) {
            android.util.Log.w("CigramBannerAd", "banner skipped", error);
            View slot = column.findViewWithTag(TAG);
            if (slot != null && slot.getParent() instanceof ViewGroup) ((ViewGroup) slot.getParent()).removeView(slot);
        }
    }
}
