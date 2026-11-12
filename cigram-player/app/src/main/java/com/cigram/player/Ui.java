package com.cigram.player;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;
import android.widget.Toast;

/** Tiny programmatic-UI helpers (the player has no XML layouts). */
final class Ui {

    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        return t;
    }

    static GradientDrawable pill(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** "#RRGGBB" only; anything else returns {@code def}. */
    static int parseColor(String hex, int def) {
        try {
            if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') return def;
            return 0xFF000000 | (int) Long.parseLong(hex.substring(1), 16);
        } catch (Throwable t) {
            return def;
        }
    }

    static boolean alive(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    static void toast(Context c, String message) {
        try {
            Toast.makeText(c, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }

    /** Rounded button-like TextView (min touch target 48dp). */
    static TextView button(Context c, CharSequence label, int bg, int fg) {
        TextView b = text(c, label, 15f, fg, true);
        b.setGravity(android.view.Gravity.CENTER);
        b.setMinHeight(dp(c, 48));
        b.setPadding(dp(c, 20), dp(c, 10), dp(c, 20), dp(c, 10));
        b.setBackground(pill(c, bg, 24));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static String time(long ms) {
        long s = Math.max(0L, ms / 1000L);
        long h = s / 3600L, m = (s % 3600L) / 60L, sec = s % 60L;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(java.util.Locale.US, "%d:%02d", m, sec);
    }

    static int white(int alpha) {
        return Color.argb(alpha, 255, 255, 255);
    }
}
