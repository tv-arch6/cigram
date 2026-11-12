package com.Cigram.vid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

/** أدوات رسم مشتركة لكل صيغ الإعلان: الوسائط (صورة/فيديو صامت)، وسم «إعلان» وقائمته (إخفاء/إبلاغ)، الألوان. */
final class AdsRender {

    private AdsRender() { }

    static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    static int parseColor(String hex, int def) {
        try {
            if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') return def;
            return 0xFF000000 | (int) Long.parseLong(hex.substring(1), 16);
        } catch (Throwable t) {
            return def;
        }
    }

    /** لون خلفية الإعلان النصّي/احتياطي الصورة. */
    static int bgOf(AdsServe.Ad ad) {
        return parseColor(ad.bgColor, 0xFF164B5C);
    }

    static int textOf(AdsServe.Ad ad) {
        return parseColor(ad.textColor, 0xFFFFFFFF);
    }

    static GradientDrawable scrim() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xB3000000});
        return g;
    }

    /**
     * عرض الوسائط. الصورة تُحمَّل عبر AdsMedia (ذاكرة + قرص). الفيديو: صورة مصغّرة ثم تشغيل صامت متكرر حين يكون play=true
     * ويتوقف ويُحرَّر عند إزالة العنصر من النافذة.
     */
    static View media(final Context c, final AdsServe.Ad ad, final boolean play, int maxPx, ImageView.ScaleType scale) {
        final FrameLayout box = new FrameLayout(c) {
            private VideoView vv;

            @Override protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                if (!play || !ad.isVideo() || ad.mediaUrl.length() == 0) return;
                try {
                    final VideoView v = new VideoView(getContext());
                    vv = v;
                    v.setVideoPath(ad.mediaUrl);
                    v.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override public void onPrepared(MediaPlayer mp) {
                            try { mp.setVolume(0f, 0f); mp.setLooping(true); } catch (Throwable ignored) { }
                            v.start();
                        }
                    });
                    v.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        @Override public boolean onError(MediaPlayer mp, int w, int e) {
                            try { removeView(v); } catch (Throwable ignored) { }
                            return true;
                        }
                    });
                    addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
                } catch (Throwable ignored) { }
            }

            @Override protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                try {
                    if (vv != null) { vv.stopPlayback(); removeView(vv); vv = null; }
                } catch (Throwable ignored) { }
            }
        };
        box.setBackgroundColor(bgOf(ad));
        if (!ad.isText()) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(scale);
            iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            box.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            AdsMedia.loadInto(c, iv, ad.posterUrl(), maxPx, ad.posterIsVideoFrame());
        }
        return box;
    }

    /** وسم «إعلان» + ⋮ بمساحة لمس 48dp. يفتح قائمة الإخفاء/الإبلاغ. */
    static View label(final Context c, final AdsServe.Ad ad, final Runnable onHidden) {
        FrameLayout hit = new FrameLayout(c);
        hit.setMinimumHeight(AdsUi.dp(c, 48));
        hit.setMinimumWidth(AdsUi.dp(c, 48));
        hit.setClickable(true);
        hit.setFocusable(true);
        hit.setContentDescription("إعلان. خيارات الإخفاء والإبلاغ");
        TextView t = AdsUi.text(c, "إعلان  ⋮", 11f, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(AdsUi.dp(c, 8), AdsUi.dp(c, 3), AdsUi.dp(c, 8), AdsUi.dp(c, 3));
        t.setBackground(AdsUi.pill(c, 0x99000000, 8));
        hit.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        hit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { menu(c, ad, onHidden); }
        });
        return hit;
    }

    private static AlertDialog.Builder builder(Activity a) {
        return new AlertDialog.Builder(a, CigramGlass.isLight(a) ? AlertDialog.THEME_DEVICE_DEFAULT_LIGHT : AlertDialog.THEME_DEVICE_DEFAULT_DARK);
    }

    static void menu(Context c, final AdsServe.Ad ad, final Runnable onHidden) {
        final Activity a = activityOf(c);
        if (a == null || a.isFinishing()) return;
        try {
            builder(a).setTitle("هذا إعلان")
                    .setItems(new CharSequence[]{"إخفاء هذا الإعلان", "الإبلاغ عن الإعلان"}, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) {
                            if (which == 0) {
                                AdsServe.hide(a, ad.campaignId);
                                AdsUi.toast(a, "تم إخفاء الإعلان.");
                                if (onHidden != null) onHidden.run();
                            } else {
                                reportDialog(a, ad, onHidden);
                            }
                        }
                    }).show();
        } catch (Throwable ignored) { }
    }

    private static void reportDialog(final Activity a, final AdsServe.Ad ad, final Runnable onHidden) {
        final String[] codes = {"misleading", "inappropriate", "annoying", "other"};
        try {
            builder(a).setTitle("سبب الإبلاغ")
                    .setItems(new CharSequence[]{"مضلل أو كاذب", "غير لائق أو مخالف", "مزعج أو متكرر", "سبب آخر"}, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int which) {
                            AdsServe.hide(a, ad.campaignId);
                            AdsServe.report(a, ad, codes[Math.max(0, Math.min(which, 3))], null);
                            AdsUi.toast(a, "شكراً، وصل بلاغك وتم إخفاء الإعلان.");
                            if (onHidden != null) onHidden.run();
                        }
                    }).setNegativeButton("إلغاء", null).show();
        } catch (Throwable ignored) { }
    }

    /** زر دعوة لاتخاذ إجراء بحجم لمس 48dp. */
    static TextView cta(Context c, String label, int fill, int textColor) {
        TextView b = AdsUi.text(c, label, 14f, textColor, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(AdsUi.dp(c, 48));
        b.setPadding(AdsUi.dp(c, 18), AdsUi.dp(c, 8), AdsUi.dp(c, 18), AdsUi.dp(c, 8));
        b.setBackground(AdsUi.pill(c, fill, 14));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
