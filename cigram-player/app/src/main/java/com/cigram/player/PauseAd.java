package com.cigram.player;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Small sponsored card shown while the video is PAUSED ("sticky" slot). It sits at the top-end corner,
 * away from the seek bar and buttons, disappears the moment playback resumes, and can be closed.
 */
final class PauseAd extends LinearLayout {

    private final Activity act;
    private final AdsClient.Ad ad;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean counted, gone;

    static PauseAd show(Activity a, ViewGroup host, AdsClient.Ad ad) {
        PauseAd v = new PauseAd(a, ad);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Ui.dp(a, 320), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        lp.topMargin = Ui.dp(a, 64);
        lp.rightMargin = Ui.dp(a, 24);
        host.addView(v, lp);
        v.start();
        return v;
    }

    private PauseAd(Activity a, AdsClient.Ad ad) {
        super(a);
        this.act = a;
        this.ad = ad;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackground(Ui.pill(a, 0xE6101820, 12));
        setPadding(Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10), Ui.dp(a, 10));

        final ImageView thumb = new ImageView(a);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (!ad.isText() && ad.posterUrl().length() > 0) {
            addView(thumb, new LinearLayout.LayoutParams(Ui.dp(a, 72), Ui.dp(a, 72)));
            ImageLoader.load(ad.posterUrl(), 400, new ImageLoader.Cb() {
                @Override public void onBitmap(Bitmap b) {
                    if (b != null && !gone) thumb.setImageBitmap(b);
                }
            });
        }

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(VERTICAL);
        col.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 6), 0);
        TextView tag = Ui.text(a, "إعلان", 10f, 0xFFFFFFFF, true);
        col.addView(tag);
        if (ad.title.length() > 0) {
            TextView t = Ui.text(a, ad.title, 14f, 0xFFFFFFFF, true);
            t.setMaxLines(2);
            col.addView(t);
        }
        if (ad.body.length() > 0) {
            TextView b = Ui.text(a, ad.body, 12f, 0xCCFFFFFF, false);
            b.setMaxLines(2);
            col.addView(b);
        }
        if (ad.clickable()) {
            TextView cta = Ui.text(a, ad.cta.length() > 0 ? ad.cta : "اعرف المزيد", 12f, 0xFF0B2530, true);
            cta.setPadding(Ui.dp(a, 14), Ui.dp(a, 6), Ui.dp(a, 14), Ui.dp(a, 6));
            cta.setBackground(Ui.pill(a, 0xFFFFFFFF, 14));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.topMargin = Ui.dp(a, 6);
            col.addView(cta, cl);
            cta.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { AdsClient.perform(act, PauseAd.this.ad); }
            });
        }
        addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = Ui.text(a, "✕", 16f, 0xFFFFFFFF, true);
        close.setGravity(Gravity.CENTER);
        close.setMinWidth(Ui.dp(a, 40));
        close.setMinHeight(Ui.dp(a, 40));
        close.setContentDescription("إغلاق الإعلان");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); }
        });
        addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void start() {
        // count the view after 1 s on screen, once
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (!gone && !counted) {
                    counted = true;
                    AdsClient.impression(act, ad);
                }
            }
        }, 1000L);
    }

    void dismiss() {
        if (gone) return;
        gone = true;
        ui.removeCallbacksAndMessages(null);
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) parent.removeView(this);
        AdsClient.flush(act);
    }
}
