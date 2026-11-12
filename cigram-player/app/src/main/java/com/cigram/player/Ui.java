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
        // a failure sentence is handled by the screen itself (retry, stay as it was): it is never shown to the viewer
        if (message == null || message.contains("تعذ") || message.contains("خطأ") || message.contains("فشل")) return;
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

    // ------------------------------------------------------------------ iOS kit (black page, white ink)

    static final int PAGE = 0xFF000000, CARD = 0xFF1C1C1E, HAIR = 0x33FFFFFF, INK = 0xFFFFFFFF, INK2 = 0x99EBEBF5, INK3 = 0x4DEBEBF5;

    /** An inset grouped card (the iOS settings look): rows are added to it with {@link #row}. */
    static android.widget.LinearLayout group(Context c) {
        android.widget.LinearLayout g = new android.widget.LinearLayout(c);
        g.setOrientation(android.widget.LinearLayout.VERTICAL);
        g.setBackground(pill(c, CARD, 16));
        g.setClipToOutline(true);
        return g;
    }

    /** One row of a group: white line icon, title, optional detail, chevron. A hairline is drawn above every row but the first. */
    static android.view.View row(Context c, android.widget.LinearLayout group, int icon, CharSequence title, CharSequence detail,
                                 android.view.View.OnClickListener click) {
        if (group.getChildCount() > 0) {
            android.view.View line = new android.view.View(c);
            line.setBackgroundColor(HAIR);
            android.widget.LinearLayout.LayoutParams ll = new android.widget.LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 0.5f)));
            ll.setMarginStart(dp(c, 54));
            group.addView(line, ll);
        }
        android.widget.LinearLayout r = new android.widget.LinearLayout(c);
        r.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, 56));
        r.setPadding(dp(c, 16), dp(c, 8), dp(c, 14), dp(c, 8));
        r.addView(new Glyph(c, icon, INK), new android.widget.LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)));
        android.widget.LinearLayout col = new android.widget.LinearLayout(c);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        TextView t = text(c, title, 16f, INK, false);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);
        if (detail != null && detail.length() > 0) {
            TextView d = text(c, detail, 12.5f, INK2, false);
            d.setMaxLines(2);
            col.addView(d);
        }
        android.widget.LinearLayout.LayoutParams cl = new android.widget.LinearLayout.LayoutParams(0, -2, 1f);
        cl.setMarginStart(dp(c, 14));
        cl.setMarginEnd(dp(c, 8));
        r.addView(col, cl);
        if (click != null) {
            Glyph chev = new Glyph(c, Glyph.CHEVRON, INK3);
            if (c.getResources().getConfiguration().getLayoutDirection() == android.view.View.LAYOUT_DIRECTION_RTL) chev.setScaleX(-1f);
            r.addView(chev, new android.widget.LinearLayout.LayoutParams(dp(c, 14), dp(c, 14)));
            r.setClickable(true);
            r.setFocusable(true);
            r.setOnClickListener(click);
            android.util.TypedValue tv = new android.util.TypedValue();
            if (c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) r.setForeground(c.getDrawable(tv.resourceId));
        }
        group.addView(r, new android.widget.LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    /** Small caption above a group. */
    static TextView caption(Context c, CharSequence s) {
        TextView t = text(c, s, 13f, INK2, false);
        t.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        return t;
    }

    /** White line icons drawn in code (24-unit grid, 1.9 stroke, round caps): no image files, no emoji. */
    static final class Glyph extends android.view.View {
        static final int PLAY = 1, LINK = 2, FOLDER = 3, CLOCK = 4, SHIELD = 5, APP = 6, PASTE = 7, CHEVRON = 8;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final android.graphics.RectF r = new android.graphics.RectF();
        private final int kind;

        Glyph(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            p.setColor(color);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(1.9f);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            if (s <= 0) return;
            c.save();
            c.translate((getWidth() - s) / 2f, (getHeight() - s) / 2f);
            c.scale(s / 24f, s / 24f);
            path.reset();
            switch (kind) {
                case PLAY:
                    c.drawCircle(12, 12, 9, p);
                    path.moveTo(10, 8.2f); path.lineTo(16, 12); path.lineTo(10, 15.8f); path.close();
                    p.setStyle(android.graphics.Paint.Style.FILL_AND_STROKE); c.drawPath(path, p); p.setStyle(android.graphics.Paint.Style.STROKE);
                    break;
                case LINK:
                    r.set(3, 8, 13, 16); c.drawRoundRect(r, 4, 4, p);
                    r.set(11, 8, 21, 16); c.drawRoundRect(r, 4, 4, p);
                    break;
                case FOLDER:
                    path.moveTo(3, 7); path.lineTo(9, 7); path.lineTo(11, 9.4f); path.lineTo(21, 9.4f); path.lineTo(21, 19); path.lineTo(3, 19); path.close();
                    c.drawPath(path, p);
                    break;
                case CLOCK:
                    c.drawCircle(12, 12, 9, p);
                    c.drawLine(12, 7, 12, 12, p); c.drawLine(12, 12, 15.4f, 14, p);
                    break;
                case SHIELD:
                    path.moveTo(12, 3); path.lineTo(19.5f, 6); path.lineTo(19.5f, 11.5f);
                    path.cubicTo(19.5f, 16, 16.4f, 19.4f, 12, 21); path.cubicTo(7.6f, 19.4f, 4.5f, 16, 4.5f, 11.5f); path.lineTo(4.5f, 6); path.close();
                    c.drawPath(path, p);
                    break;
                case APP:
                    r.set(4, 4, 20, 20); c.drawRoundRect(r, 4.5f, 4.5f, p);
                    c.drawCircle(12, 12, 3.2f, p);
                    break;
                case PASTE:
                    r.set(5, 5, 19, 21); c.drawRoundRect(r, 2.5f, 2.5f, p);
                    r.set(9, 3, 15, 7); c.drawRoundRect(r, 1.5f, 1.5f, p);
                    c.drawLine(8.5f, 12, 15.5f, 12, p); c.drawLine(8.5f, 16, 13.5f, 16, p);
                    break;
                default:
                    path.moveTo(9, 5); path.lineTo(16, 12); path.lineTo(9, 19);
                    c.drawPath(path, p);
            }
            c.restore();
        }
    }

    static int white(int alpha) {
        return Color.argb(alpha, 255, 255, 255);
    }
}
