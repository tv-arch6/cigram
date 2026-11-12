package com.cigram.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

/**
 * Full-screen ad shown over the player (pre-roll / mid-roll).
 *  - countdown of SECONDS, "skip" appears after SKIP_AFTER seconds, the ad closes by itself at the end
 *  - the impression is counted after 1 s of the creative being really on screen (never for a creative that failed to load)
 *  - only the labelled button opens the advertiser's destination (touching the picture does nothing)
 *  - the "إعلان" label opens: hide this ad / report this ad
 *  - never overlaps video controls: while it is up the player is paused and its controller hidden
 */
final class AdView extends FrameLayout {

    interface Listener { void onClosed(boolean skipped); }

    static final int SECONDS = 5;
    static final int SKIP_AFTER = 2;
    private static final long LOAD_TIMEOUT_MS = 4000L;

    private final Activity act;
    private final AdsClient.Ad ad;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView skip;
    private VideoView video;
    private ImageView image;
    private int elapsed;
    private boolean closed, ready;

    private final Runnable loadTimeout = new Runnable() {
        @Override public void run() { if (!ready) dismiss(true, false); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            elapsed++;
            if (elapsed == 1) AdsClient.impression(act, ad);
            updateSkip();
            if (elapsed >= SECONDS) { dismiss(false, true); return; }
            ui.postDelayed(this, 1000L);
        }
    };

    /** Adds the ad on top of {@code host} and starts it. Returns the view (use {@link #skipIfAllowed()} for Back). */
    static AdView show(Activity a, ViewGroup host, AdsClient.Ad ad, Listener l) {
        AdView v = new AdView(a, ad, l);
        host.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        v.begin();
        return v;
    }

    private AdView(Activity a, AdsClient.Ad ad, Listener l) {
        super(a);
        this.act = a;
        this.ad = ad;
        this.listener = l;
        setBackgroundColor(ad.isText() ? Ui.parseColor(ad.bgColor, 0xFF0B2530) : 0xFF000000);
        setClickable(true);      // swallow touches: nothing under the ad may react (and nothing is triggered by them)
        setFocusable(true);
        buildMedia();
        buildOverlay();
    }

    // ------------------------------------------------------------------ build

    private void buildMedia() {
        if (ad.isText()) return;
        if (ad.isVideo() && ad.mediaUrl.toLowerCase(java.util.Locale.US).startsWith("https://")) {
            video = new VideoView(act);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
            addView(video, lp);
        }
        image = new ImageView(act);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (video != null) image.setVisibility(View.GONE);   // shown only as a poster until the video starts
        // Google Play ads policy: ads must not be designed to be clicked by accident. Only the clearly labelled
        // button below opens the advertiser; touching the picture does nothing.
    }

    private void buildOverlay() {
        int fg = ad.isText() ? Ui.parseColor(ad.textColor, 0xFFFFFFFF) : 0xFFFFFFFF;

        View scrim = new View(act);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xCC000000});
        scrim.setBackground(g);
        addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 220), Gravity.BOTTOM));

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(act, 32), 0, Ui.dp(act, 32), Ui.dp(act, 24));
        if (ad.title.length() > 0) {
            TextView t = Ui.text(act, ad.title, 20f, fg, true);
            t.setMaxLines(2);
            box.addView(t);
        }
        if (ad.body.length() > 0) {
            TextView b = Ui.text(act, ad.body, 14f, fg, false);
            b.setMaxLines(2);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.topMargin = Ui.dp(act, 4);
            box.addView(b, bl);
        }
        if (ad.clickable()) {
            TextView cta = Ui.text(act, ad.cta.length() > 0 ? ad.cta : "اعرف المزيد", 15f, 0xFF0B2530, true);
            cta.setGravity(Gravity.CENTER);
            cta.setPadding(Ui.dp(act, 24), Ui.dp(act, 10), Ui.dp(act, 24), Ui.dp(act, 10));
            cta.setBackground(Ui.pill(act, 0xFFFFFFFF, 24));
            cta.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { AdsClient.perform(act, ad); }
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.topMargin = Ui.dp(act, 12);
            box.addView(cta, cl);
        }
        addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));

        TextView label = Ui.text(act, "إعلان", 12f, 0xFFFFFFFF, true);
        label.setPadding(Ui.dp(act, 12), Ui.dp(act, 6), Ui.dp(act, 12), Ui.dp(act, 6));
        label.setBackground(Ui.pill(act, 0x99000000, 14));
        label.setMinHeight(Ui.dp(act, 32));
        label.setGravity(Gravity.CENTER);
        label.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMenu(); }
        });
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        ll.topMargin = Ui.dp(act, 16);
        ll.rightMargin = Ui.dp(act, 24);
        addView(label, ll);

        skip = Ui.text(act, "", 14f, 0xFFFFFFFF, true);
        skip.setGravity(Gravity.CENTER);
        skip.setMinHeight(Ui.dp(act, 48));
        skip.setMinWidth(Ui.dp(act, 96));
        skip.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 8));
        skip.setBackground(Ui.pill(act, 0x99000000, 24));
        skip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { skipIfAllowed(); }
        });
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        sl.topMargin = Ui.dp(act, 12);
        sl.leftMargin = Ui.dp(act, 24);
        addView(skip, sl);
        updateSkip();
    }

    // ------------------------------------------------------------------ life

    private void begin() {
        ui.postDelayed(loadTimeout, LOAD_TIMEOUT_MS);
        if (ad.isText()) { markReady(); return; }

        if (video != null) {
            final String poster = ad.posterUrl();
            if (poster.length() > 0) {
                ImageLoader.load(poster, 1600, new ImageLoader.Cb() {
                    @Override public void onBitmap(Bitmap b) {
                        if (b != null && !closed && image != null && !ready) {
                            image.setImageBitmap(b);
                            image.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
            video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer mp) {
                    try { mp.setVolume(0f, 0f); mp.setLooping(true); } catch (Throwable ignored) { }
                    if (image != null) image.setVisibility(View.GONE);
                    video.start();
                    markReady();
                }
            });
            video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                    dismiss(true, false);
                    return true;
                }
            });
            video.setVideoURI(Uri.parse(ad.mediaUrl));
            return;
        }

        ImageLoader.load(ad.posterUrl(), 1600, new ImageLoader.Cb() {
            @Override public void onBitmap(Bitmap b) {
                if (closed) return;
                if (b == null) { dismiss(true, false); return; }
                image.setImageBitmap(b);
                markReady();
            }
        });
    }

    private void markReady() {
        if (closed || ready) return;
        ready = true;
        ui.removeCallbacks(loadTimeout);
        updateSkip();
        ui.postDelayed(tick, 1000L);
    }

    private void updateSkip() {
        if (skip == null) return;
        if (elapsed >= SKIP_AFTER) {
            skip.setText("تخطي ✕");
            skip.setEnabled(true);
        } else {
            skip.setText("تخطي بعد " + Math.max(1, SKIP_AFTER - elapsed));
            skip.setEnabled(false);
        }
    }

    /** Back key / skip button. Does nothing during the first seconds. */
    boolean skipIfAllowed() {
        if (closed) return true;
        if (elapsed >= SKIP_AFTER) { dismiss(true, true); return true; }
        return false;
    }

    private void showMenu() {
        try {
            final CharSequence[] items = {"إخفاء هذا الإعلان", "الإبلاغ عن الإعلان"};
            new AlertDialog.Builder(act)
                    .setItems(items, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) {
                            if (which == 0) {
                                AdsClient.hide(act, ad.campaignId);
                                Ui.toast(act, "لن يظهر هذا الإعلان لمدة 7 أيام.");
                            } else {
                                AdsClient.report(act, ad, "inappropriate");
                                Ui.toast(act, "تم إرسال البلاغ، شكراً.");
                            }
                        }
                    })
                    .show();
        } catch (Throwable ignored) { }
    }

    private void dismiss(boolean skipped, boolean wasShown) {
        if (closed) return;
        closed = true;
        ui.removeCallbacksAndMessages(null);
        try { if (video != null) video.stopPlayback(); } catch (Throwable ignored) { }
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) parent.removeView(this);
        AdsClient.flush(act);
        if (listener != null) listener.onClosed(skipped);
    }

    /** Called when the Activity stops: close without waiting for the countdown. */
    void closeQuietly() {
        dismiss(true, false);
    }
}
