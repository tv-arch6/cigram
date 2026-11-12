package com.my.newproject;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * Design layer of the new admin screens (home, add movie / series / channel, notifications).
 * Same night palette as the rest of the admin, with deeper surfaces, soft gradients and one color per metric.
 */
final class NxUi {

    private NxUi() {}

    // ------------------------------------------------------------------ tokens
    static final int BG = CgCfg.PAGE;            // page (same as every other admin screen)
    static final int SURF_TOP = CgCfg.CARD;      // (flat now: top = bottom)
    static final int SURF_BOT = CgCfg.CARD;
    static final int SURF_FLAT = CgCfg.CARD;
    static final int SURF_HI = CgCfg.CARD2;      // pressed / raised
    static final int LINE = CgCfg.STROKE;        // dividers
    static final int LINE_SOFT = 0x66263042;
    static final int TEXT = CgCfg.TEXT;
    static final int SUB = CgCfg.MUTED;
    static final int DIM = 0xFF6E6E78;

    static final int BLUE = 0xFFFFFFFF;          // active users
    static final int VIOLET = 0xFFD1D1D6;        // watching now
    static final int ORANGE = 0xFFE5E5EA;        // ads
    static final int TEAL = 0xFFFFFFFF;          // accounts / channels
    static final int PINK = 0xFFC7C7CC;
    static final int UP = 0xFF3DDC97;
    static final int DOWN = 0xFFFF6B7D;
    static final int LIVE = 0xFFFF6B7D;

    static final int MATCH = CgUi.MATCH;
    static final int WRAP = CgUi.WRAP;

    static int dp(Context c, float v) {
        return CgUi.dp(c, v);
    }

    static int alpha(int color, int a) {
        return CgUi.alpha(color, a);
    }

    // --------------------------------------------------------------- surfaces

    /** Card surface: flat, no border. */
    static GradientDrawable surface(Context c, float radiusDp) {
        return CgUi.bg(c, SURF_FLAT, radiusDp, 0);
    }

    static GradientDrawable flat(Context c, int fill, float radiusDp, int stroke) {
        return CgUi.bg(c, fill, radiusDp, stroke);
    }

    /** Two-colour diagonal gradient (restored in v3 for accent fills, headings and segmented controls). */
    static GradientDrawable gradient(Context c, int from, int to, float radiusDp) {
        return CgUi.gradient(c, from, to == 0 ? from : to, radiusDp);
    }

    /** Soft tinted tile: color at low alpha with a matching border. */
    static GradientDrawable tint(Context c, int color, float radiusDp) {
        return CgUi.bg(c, alpha(color, 0x26), radiusDp, 0);
    }

    static GradientDrawable dashed(Context c, int color, float radiusDp) {
        return CgUi.bg(c, alpha(color, 0x1A), radiusDp, 0);       // no dashed borders any more
    }

    /** Ripple touch feedback over any background, clipped to the same radius. */
    static void pressable(View v, Drawable content, float radiusDp) {
        Context c = v.getContext();
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(c, radiusDp));
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask));
        v.setClickable(true);
        v.setFocusable(true);
    }

    // ------------------------------------------------------------------ views

    static LinearLayout card(Context c) {
        LinearLayout l = CgUi.vbox(c);
        l.setBackground(surface(c, 20));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        CgUi.elevate(l, 3);
        return l;
    }

    static TextView text(Context c, CharSequence t, float sp, int color, boolean bold) {
        return CgUi.text(c, t, sp, color, bold);
    }

    static ImageView icon(Context c, Drawable d, int padDp) {
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(d);
        int p = dp(c, padDp);
        iv.setPadding(p, p, p, p);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return iv;
    }

    /** Rounded square holding a line icon on a tinted / gradient fill. */
    static ImageView iconTile(Context c, Drawable d, Drawable bg, int padDp) {
        ImageView iv = icon(c, d, padDp);
        iv.setBackground(bg);
        return iv;
    }

    static View dot(Context c, int color, float sizeDp) {
        View v = new View(c);
        v.setBackground(CgUi.bg(c, color, sizeDp / 2f, 0));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    static TextView pill(Context c, CharSequence label, int color) {
        TextView t = CgUi.text(c, label, 11.5f, color, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(CgUi.bg(c, alpha(color, 0x22), 20, 0));
        t.setPadding(dp(c, 10), dp(c, 3), dp(c, 10), dp(c, 3));
        return t;
    }

    static LinearLayout.LayoutParams lp(Context c, int w, int h, float l, float t, float r, float b) {
        return CgUi.lp(c, w, h, l, t, r, b);
    }

    /** Big primary action with a gradient fill. */
    static TextView primary(Context c, CharSequence label) {
        TextView b = CgUi.text(c, label, 15, 0xFFFFFFFF, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13));
        pressable(b, gradient(c, CgCfg.ACCENT_G1, CgCfg.ACCENT_G2, 16), 16);
        CgUi.elevate(b, 4);
        return b;
    }

    static TextView secondary(Context c, CharSequence label) {
        TextView b = CgUi.text(c, label, 14, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
        pressable(b, flat(c, SURF_HI, 14, 0), 14);
        return b;
    }

    static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.42f);
    }

    /** Shows the name of an icon-only button on long press (the rows on the home screen have no labels). */
    static void hint(final View v, final String name) {
        v.setContentDescription(name);
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                try {
                    Toast t = Toast.makeText(view.getContext(), name, Toast.LENGTH_SHORT);
                    t.show();
                } catch (Throwable ignored) { }
                return true;
            }
        });
    }

    // ----------------------------------------------------------------- numbers

    static String num(double v) {
        if (v < 0) return "—";
        long r = Math.round(v);
        return String.format(Locale.US, "%,d", r);
    }

    static String compact(double v) {
        if (v < 0) return "—";
        if (v >= 1000000) return String.format(Locale.US, "%.1fM", v / 1000000.0);
        if (v >= 10000) return String.format(Locale.US, "%.1fK", v / 1000.0);
        return num(v);
    }

    /** Counts a number up / down smoothly (600 ms) instead of jumping. */
    static void animateNumber(final TextView tv, double from, final double to) {
        try {
            Object old = tv.getTag();
            if (old instanceof ValueAnimator) ((ValueAnimator) old).cancel();
            if (from < 0 || to < 0 || from == to) {
                tv.setText(num(to));
                return;
            }
            ValueAnimator va = ValueAnimator.ofFloat((float) from, (float) to);
            va.setDuration(600);
            va.setInterpolator(new DecelerateInterpolator());
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    tv.setText(num((Float) a.getAnimatedValue()));
                }
            });
            tv.setTag(va);
            va.start();
        } catch (Throwable t) {
            tv.setText(num(to));
        }
    }

    /** Endless soft pulse (live dot). */
    static void pulse(final View v) {
        try {
            ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(1000);
            va.setRepeatCount(ValueAnimator.INFINITE);
            va.setRepeatMode(ValueAnimator.REVERSE);
            va.setInterpolator(new AccelerateDecelerateInterpolator());
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    float f = (Float) a.getAnimatedValue();
                    v.setAlpha(0.35f + 0.65f * f);
                    v.setScaleX(0.8f + 0.25f * f);
                    v.setScaleY(0.8f + 0.25f * f);
                }
            });
            va.start();
            v.setTag(va);
        } catch (Throwable ignored) { }
    }

    /** Short "pop" when a value changes. */
    static void bump(View v) {
        try {
            v.animate().cancel();
            v.setScaleX(1.06f);
            v.setScaleY(1.06f);
            v.animate().scaleX(1f).scaleY(1f).setDuration(260).start();
        } catch (Throwable ignored) { }
    }

    /** Fades a freshly added view in. */
    static void enter(View v, long delayMs) {
        try {
            v.setAlpha(0f);
            v.setTranslationY(dp(v.getContext(), 10));
            v.animate().alpha(1f).translationY(0).setStartDelay(delayMs).setDuration(320)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } catch (Throwable ignored) {
            v.setAlpha(1f);
        }
    }

    // ------------------------------------------------------------ segmented

    interface SegCb {
        void run(int index);
    }

    /** Two or three equal options in one rounded track; returns the track. */
    static LinearLayout segmented(final Context c, final String[] labels, final int[] icons, int selected, final SegCb cb) {
        final LinearLayout track = CgUi.hbox(c);
        track.setBackground(flat(c, 0xFF0E0E10, 16, LINE));
        int p = dp(c, 4);
        track.setPadding(p, p, p, p);
        final TextView[] items = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView t = CgUi.text(c, labels[i], 13.5f, SUB, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(c, 8), dp(c, 10), dp(c, 8), dp(c, 10));
            if (icons != null && i < icons.length && icons[i] >= 0) {
                Drawable d = NxIcons.icon(icons[i], SUB);
                d.setBounds(0, 0, dp(c, 18), dp(c, 18));
                t.setCompoundDrawablesRelative(d, null, null, null);
                t.setCompoundDrawablePadding(dp(c, 6));
            }
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    paintSeg(c, items, icons, idx);
                    cb.run(idx);
                }
            });
            items[i] = t;
            track.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
        paintSeg(c, items, icons, selected);
        return track;
    }

    private static void paintSeg(Context c, TextView[] items, int[] icons, int sel) {
        for (int i = 0; i < items.length; i++) {
            boolean on = i == sel;
            items[i].setTextColor(on ? 0xFFFFFFFF : SUB);
            items[i].setBackground(on ? gradient(c, CgCfg.ACCENT_G1, CgCfg.ACCENT_G2, 12) : null);
            if (icons != null && i < icons.length && icons[i] >= 0) {
                Drawable d = NxIcons.icon(icons[i], on ? 0xFFFFFFFF : SUB);
                d.setBounds(0, 0, dp(c, 18), dp(c, 18));
                items[i].setCompoundDrawablesRelative(d, null, null, null);
            }
        }
    }

    /** Small section heading with an accent bar. */
    static LinearLayout heading(Context c, String title, String sub) {
        LinearLayout row = CgUi.hbox(c);
        View bar = new View(c);
        bar.setBackground(gradient(c, CgCfg.ACCENT_G1, CgCfg.ACCENT_G2, 2));
        row.addView(bar, new LinearLayout.LayoutParams(dp(c, 4), dp(c, 18)));
        LinearLayout col = CgUi.vbox(c);
        col.addView(CgUi.text(c, title, 16, TEXT, true));
        if (sub != null && sub.length() > 0) col.addView(CgUi.text(c, sub, 11.5f, SUB, false));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, WRAP, 1f);
        l.setMargins(dp(c, 8), 0, 0, 0);
        row.addView(col, l);
        return row;
    }
}
