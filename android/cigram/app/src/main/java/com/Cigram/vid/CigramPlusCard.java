package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The Cigram Plus membership card (inside the Cigram Plus page — nothing about the subscription is shown outside it).
 *
 *   ┌──────────────────────────────────────────────┐
 *   │ [ مفعّل ]                       Cigram Plus ♛ │   the subscriber's own banner fills the card
 *   │                                              │   (no banner: a midnight-blue sheen, never the old gold)
 *   │                                   اسم الحساب │
 *   │                             name@mail · C48213│
 *   │ من 2026/11/02 إلى 2027/05/02     متبقٍّ 171 يوم │
 *   │ ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▭▭▭▭▭▭▭▭▭▭▭▭▭▭▭▭▭▭▭▭▭▭▭▭▭▭▭▭▭▭ │
 *   └──────────────────────────────────────────────┘
 *
 * A bank-card shape (1.586 : 1) with soft 24 dp corners. Everything on it is white ink; a scrim under the text keeps
 * it readable on any banner. Without Plus the same card is drawn in graphite and says what is missing.
 */
final class CigramPlusCard {

    static final String TAG = "cigram_plus_card";

    private CigramPlusCard() { }

    static View build(final Activity a) {
        final float d = a.getResources().getDisplayMetrics().density;
        final boolean plus = CigramPlus.isPlus(a);
        final int radius = (int) (24 * d);

        FrameLayout card = new FrameLayout(a) {
            @Override protected void onMeasure(int w, int h) {
                int width = MeasureSpec.getSize(w);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(Math.round(width / 1.586f), MeasureSpec.EXACTLY));
            }
        };
        card.setTag(TAG);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
        card.setClipToOutline(true);
        card.setElevation(6 * d);

        // ---- the face of the card
        GradientDrawable face = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                plus ? new int[]{0xFF101B3A, 0xFF0A1024, 0xFF05060C}      // subscriber without a banner: midnight blue
                        : new int[]{0xFF2A2A2E, 0xFF161618, 0xFF0B0B0C}); // no Plus: graphite
        card.setBackground(face);
        boolean hasBanner = false;
        if (plus) {
            Bitmap banner = bannerBitmap(a);
            if (banner != null) {
                ImageView bg = new ImageView(a);
                bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
                bg.setImageBitmap(banner);
                card.addView(bg, new FrameLayout.LayoutParams(-1, -1));
                hasBanner = true;
            }
        }
        // sheen (top-left light) + scrim (bottom shadow for the text)
        View sheen = new View(a);
        GradientDrawable sh = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{hasBanner ? 0x26FFFFFF : 0x33FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        sheen.setBackground(sh);
        card.addView(sheen, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(a);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{hasBanner ? 0x66000000 : 0x00000000, hasBanner ? 0x33000000 : 0x00000000, hasBanner ? 0xD9000000 : 0x66000000}));
        card.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        View rim = new View(a);
        GradientDrawable rm = new GradientDrawable();
        rm.setCornerRadius(radius);
        rm.setColor(0);
        rm.setStroke(Math.max(1, (int) d), 0x40FFFFFF);
        rim.setBackground(rm);
        card.addView(rim, new FrameLayout.LayoutParams(-1, -1));

        Typeface bold = CigramTypefaces.bold() != null ? CigramTypefaces.bold() : Typeface.DEFAULT_BOLD;
        int pad = (int) (18 * d);

        // ---- top row: brand (right) + state (left)
        LinearLayout brand = new LinearLayout(a);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView word = text(a, "Cigram Plus", 16f, 0xFFFFFFFF, bold);
        brand.addView(word);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams((int) (20 * d), (int) (20 * d));
        il.leftMargin = (int) (8 * d);
        brand.addView(new AdsIcons.Icon(a, AdsIcons.CROWN, 0xFFFFFFFF), il);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        bl.setMargins(0, pad, pad, 0);
        card.addView(brand, bl);

        TextView state = text(a, plus ? "مفعّل" : (CigramPlus.expiredAt(a) > 0L ? "منتهٍ" : "غير مشترك"), 11.5f,
                plus ? 0xFF000000 : 0xFFFFFFFF, bold);
        state.setPadding((int) (11 * d), (int) (4 * d), (int) (11 * d), (int) (4 * d));
        GradientDrawable sb = new GradientDrawable();
        sb.setCornerRadius(12 * d);
        if (plus) sb.setColor(0xFFFFFFFF);
        else { sb.setColor(0x26FFFFFF); sb.setStroke(Math.max(1, (int) d), 0x59FFFFFF); }
        state.setBackground(sb);
        FrameLayout.LayoutParams stl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        stl.setMargins(pad, pad, 0, 0);
        card.addView(state, stl);

        // ---- bottom block: the account + the period
        LinearLayout bottom = new LinearLayout(a);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.RIGHT);
        bottom.setPadding(pad, 0, pad, pad);

        String name = "", mail = "", id = "";
        try { name = CigramUserData.displayName(a); } catch (Throwable ignored) { }
        try { mail = CigramUserData.email(a); } catch (Throwable ignored) { }
        try { id = CigramPublicId.get(a); } catch (Throwable ignored) { }
        TextView nameView = text(a, name == null || name.trim().length() == 0 ? "حسابي" : name.trim(), 19f, 0xFFFFFFFF, bold);
        nameView.setGravity(Gravity.RIGHT);
        nameView.setSingleLine(true);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bottom.addView(nameView, new LinearLayout.LayoutParams(-1, -2));
        StringBuilder who = new StringBuilder();
        if (mail != null && mail.trim().length() > 0) who.append(mail.trim());
        if (id != null && id.trim().length() > 0) who.append(who.length() > 0 ? "  ·  " : "").append(id.trim());
        TextView whoView = text(a, who.toString(), 12f, 0xCCFFFFFF, CigramUI.font(a));
        whoView.setGravity(Gravity.RIGHT);
        whoView.setSingleLine(true);
        whoView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        whoView.setTextDirection(View.TEXT_DIRECTION_LTR);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
        wl.topMargin = (int) (3 * d);
        bottom.addView(whoView, wl);

        LinearLayout line = new LinearLayout(a);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        line.setGravity(Gravity.CENTER_VERTICAL);
        String leftText, rightText;
        if (plus) {
            leftText = "متبقٍّ " + CigramPlus.daysLeft(a) + " يوم";
            rightText = CigramPlus.periodText(a);
        } else if (CigramPlus.expiredAt(a) > 0L) {
            leftText = "";
            rightText = "انتهى في " + CigramPlus.formatDate(CigramPlus.expiredAt(a));
        } else {
            leftText = "";
            rightText = "بلا إعلانات · حتى 4K · صورة داخل صورة";
        }
        TextView left = text(a, leftText, 12f, 0xFFFFFFFF, bold);
        line.addView(left);
        TextView right = text(a, rightText, 12f, 0xE6FFFFFF, CigramUI.font(a));
        right.setGravity(Gravity.RIGHT);
        right.setSingleLine(true);
        line.addView(right, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = (int) (12 * d);
        bottom.addView(line, ll);

        long from = CigramPlus.activatedAt(a), to = CigramPlus.until(a);
        if (plus && from > 0L && to > from) {
            final float f = Math.max(0f, Math.min(1f, (System.currentTimeMillis() - from) / (float) (to - from)));
            final FrameLayout track = new FrameLayout(a);
            GradientDrawable tb = new GradientDrawable();
            tb.setCornerRadius(2 * d);
            tb.setColor(0x40FFFFFF);
            track.setBackground(tb);
            final View fill = new View(a);
            GradientDrawable fb = new GradientDrawable();
            fb.setCornerRadius(2 * d);
            fb.setColor(0xFFFFFFFF);
            fill.setBackground(fb);
            track.addView(fill, new FrameLayout.LayoutParams(0, -1, Gravity.RIGHT));
            track.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    int want = Math.max((int) (6 * d), (int) ((r - l) * f));
                    if (fill.getLayoutParams().width != want) {
                        fill.getLayoutParams().width = want;
                        fill.post(new Runnable() { @Override public void run() { fill.requestLayout(); } });
                    }
                }
            });
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, (int) (4 * d));
            tl.topMargin = (int) (8 * d);
            bottom.addView(track, tl);
        }
        card.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        card.setContentDescription(plus ? "بطاقة Cigram Plus، " + rightText + "، " + leftText : "بطاقة Cigram Plus، " + rightText);
        return card;
    }

    private static TextView text(Context c, String s, float sp, int color, Typeface face) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setShadowLayer(4f * c.getResources().getDisplayMetrics().density, 0f, 1f, 0x80000000);
        return t;
    }

    /** The subscriber's own cover, decoded small (a card is ~360 dp wide); null = none or a video that gives no frame. */
    private static Bitmap bannerBitmap(Context c) {
        try {
            CigramBanner.Banner b = CigramBanner.current(c);
            if (b == null || b.file == null) return null;
            if (b.isVideo()) {
                android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                try {
                    r.setDataSource(b.file.getAbsolutePath());
                    return r.getFrameAtTime(1000000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                } finally {
                    try { r.release(); } catch (Throwable ignored) { }
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(b.file.getAbsolutePath(), o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 1400) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(b.file.getAbsolutePath(), o2);
        } catch (Throwable e) {
            return null;
        }
    }
}
