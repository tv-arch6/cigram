package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v4.4 — one visual language for every screen (home, details, search, sections, account, settings).
 *
 * RTL rule used everywhere in this class: rows are laid out with an EXPLICIT left-to-right
 * direction and their children are added in PHYSICAL order (left → right). The project has no
 * android:supportsRtl, so setLayoutDirection(RTL) is most likely ignored at runtime; a physical
 * order gives the same, correct Arabic result whether RTL is honoured or not.
 *
 * Nothing here touches data, click handlers, ids or ordering of content.
 */
public final class CigramDesign {

    private CigramDesign() { }

    // ------------------------------------------------------------------ tokens

    public static final int BG_APP = 0xFF0E0E10;
    public static final int SURFACE = 0xFF1A1A1D;
    public static final int SURFACE_HI = 0xFF222226;
    public static final int HAIRLINE = 0x1FFFFFFF;
    public static final int GHOST_FILL = 0x14FFFFFF;
    public static final int GHOST_STROKE = 0x2EFFFFFF;
    public static final int ACCENT = CigramUI.RED;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_2 = 0xFFC7C7CC;
    public static final int TEXT_3 = 0xFF8796A0;
    public static final int IMDB = 0xFFF5C518;

    /** Type scale (sp). */
    public static final float SECTION_SP = 22f;
    public static final float CARD_TITLE_SP = 13.5f;
    public static final float CARD_META_SP = 11.5f;

    public static final float POSTER_RADIUS_DP = 14f;

    // ------------------------------------------------------------------ basics

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static Typeface font(Context c) {
        return CigramStage1InitProvider.cigramFont(c);
    }

    public static GradientDrawable round(int fill, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(CigramTheme.adapt(c, fill));   // v10.72: light surfaces in Light mode
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static GradientDrawable round(int fill, int stroke, float radiusDp, Context c) {
        GradientDrawable g = round(fill, radiusDp, c);
        g.setStroke(Math.max(1, dp(c, 1)), CigramTheme.adapt(c, stroke));
        return g;
    }

    /** Filled red pill: the one primary action of a screen. */
    public static Drawable primaryPill(Context c, float radiusDp) {
        return CigramGlass.ripple(c, round(ACCENT, radiusDp, c), radiusDp);
    }

    /** Quiet translucent pill with a hairline: every secondary action. */
    public static Drawable ghostPill(Context c, float radiusDp) {
        return CigramGlass.ripple(c, round(GHOST_FILL, GHOST_STROKE, radiusDp, c), radiusDp);
    }

    /**
     * Thin light frame drawn ABOVE a poster image (foreground), so it never hides the picture. 2026: posters (home,
     * movies, series, search) also carry the small, transparent Cigram watermark in the same foreground layer.
     */
    public static void posterFrame(View poster, float radiusDp) {
        posterFrame(poster, radiusDp, true);
    }

    /** Same frame; {@code watermark} false for artwork that belongs to someone else (channel logos). */
    public static void posterFrame(View poster, float radiusDp, boolean watermark) {
        if (poster == null || Build.VERSION.SDK_INT < 23) return;
        try {
            GradientDrawable frame = new GradientDrawable();
            frame.setColor(Color.TRANSPARENT);
            frame.setCornerRadius(dp(poster.getContext(), radiusDp));
            frame.setStroke(Math.max(1, dp(poster.getContext(), 1)), HAIRLINE);
            poster.setForeground(watermark ? CigramBrand.withWatermark(poster.getContext(), frame) : frame);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------- poster cards

    /**
     * Width of a poster card in the horizontal home rows: about three and a bit cards per screen,
     * so the next card always peeks in (tells the user the row scrolls). 108–140 dp.
     */
    public static int cardWidth(Context c) {
        float density = c.getResources().getDisplayMetrics().density;
        float screenDp = c.getResources().getDisplayMetrics().widthPixels / density;
        float w = (screenDp - 24f) / 3.15f - 8f;
        if (w < 108f) w = 108f;
        if (w > 140f) w = 140f;
        return Math.round(w * density);
    }

    private static View byName(View root, String name) {
        if (root == null) return null;
        try {
            Context c = root.getContext();
            int id = c.getResources().getIdentifier(name, "id", c.getPackageName());
            return id == 0 ? null : root.findViewById(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean meaningfulRating(CharSequence value) {
        String text = value == null ? "" : value.toString().trim();
        if (text.length() == 0 || "--".equals(text)) return false;
        try { return Double.parseDouble(text.replace(',', '.')) > 0.0001d; }
        catch (Throwable ignored) { return true; }
    }

    /**
     * Same card look for every movie_item (home rows built by MainActivity and the dynamic
     * sections built by CigramStage1InitProvider): framed poster, IMDb score in IMDb yellow,
     * one-line bold title and a quiet meta line, all aligned to the right.
     * Only styling: ids, texts, poster loading and click handlers are untouched.
     */
    public static void styleMovieCard(View cell) {
        if (cell == null) return;
        try {
            Context c = cell.getContext();
            Typeface font = font(c);

            View poster = byName(cell, "imageview_poster");
            posterFrame(poster, POSTER_RADIUS_DP);

            View ratingRow = byName(cell, "linear_rating");
            TextView imdb = (TextView) asText(byName(cell, "textview_item_imdb"));
            if (ratingRow instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) ratingRow;
                row.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
                ViewGroup.LayoutParams lp = row.getLayoutParams();
                if (lp != null) {
                    lp.height = dp(c, 24);
                    if (lp instanceof ViewGroup.MarginLayoutParams) {
                        ((ViewGroup.MarginLayoutParams) lp).topMargin = dp(c, 6);
                    }
                    row.setLayoutParams(lp);
                }
                row.setPadding(dp(c, 2), 0, dp(c, 2), 0);
                // No score: keep the row's height (titles stay aligned across the row), hide it.
                if (imdb != null) {
                    row.setVisibility(meaningfulRating(imdb.getText()) ? View.VISIBLE : View.INVISIBLE);
                }
            }
            // v10.39: the IMDb logo is the rounded picture, not the old vector lettering
            View logo = byName(cell, "textview_imdb_logo");
            if (logo instanceof ImageView) {
                CigramImdbIcon.apply((ImageView) logo, dp(c, 17));
                ViewGroup.LayoutParams llp = logo.getLayoutParams();
                if (llp instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) llp).leftMargin = dp(c, 6);
                    ((ViewGroup.MarginLayoutParams) llp).rightMargin = 0;
                    logo.setLayoutParams(llp);
                }
            }
            if (imdb != null) {
                imdb.setTextColor(IMDB);
                imdb.setTextSize(12.5f);
                imdb.setTypeface(font, Typeface.BOLD);
                imdb.setIncludeFontPadding(false);
            }

            TextView title = (TextView) asText(byName(cell, "textview_title"));
            if (title != null) {
                title.setTextColor(TEXT);
                title.setTextSize(CARD_TITLE_SP);
                title.setTypeface(font, Typeface.BOLD);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
                title.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
                title.setPadding(dp(c, 3), 0, dp(c, 3), 0);
                ViewGroup.LayoutParams lp = title.getLayoutParams();
                if (lp != null) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    if (lp instanceof ViewGroup.MarginLayoutParams) {
                        ((ViewGroup.MarginLayoutParams) lp).topMargin = dp(c, 3);
                    }
                    title.setLayoutParams(lp);
                }
            }

            TextView meta = (TextView) asText(byName(cell, "textview_item_year_category"));
            if (meta != null) {
                meta.setTextColor(TEXT_3);
                meta.setTextSize(CARD_META_SP);
                meta.setTypeface(font, Typeface.NORMAL);
                meta.setSingleLine(true);
                meta.setEllipsize(TextUtils.TruncateAt.END);
                meta.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
                meta.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
                meta.setPadding(dp(c, 3), dp(c, 1), dp(c, 3), 0);
                ViewGroup.LayoutParams lp = meta.getLayoutParams();
                if (lp != null) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    meta.setLayoutParams(lp);
                }
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramDesign", "card style failed", error);
        }
    }

    private static View asText(View v) {
        return v instanceof TextView ? v : null;
    }

    // --------------------------------------------------------- section headers

    /**
     * 2026 redesign: section titles no longer carry the red accent bar (owner's request: "remove the red lines beside
     * the home section titles"). The view is kept (GONE, zero size) because existing code looks it up by its tag and
     * re-parents it; removing it would change their layout logic.
     */
    public static View accentBar(Context c) {
        View bar = new View(c);
        bar.setTag("cigram_section_accent");
        bar.setVisibility(View.GONE);
        return bar;
    }

    public static LinearLayout.LayoutParams accentBarParams(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 0);
        lp.gravity = Gravity.CENTER_VERTICAL;
        return lp;
    }

    /** «المزيد» as plain text (no background). 2026: the "‹" chevron next to the word is removed; the word stays. */
    public static void styleMore(TextView more) {
        if (more == null) return;
        Context c = more.getContext();
        CharSequence current = more.getText();
        String label = current == null ? "" : current.toString().replace("‹", "").trim();
        if (label.length() == 0) label = "المزيد";
        more.setText(label);
        more.setTextColor(TEXT_2);
        more.setTextSize(13f);
        more.setTypeface(font(c), Typeface.BOLD);
        more.setGravity(Gravity.CENTER);
        more.setSingleLine(true);
        more.setPadding(dp(c, 6), dp(c, 5), dp(c, 6), dp(c, 5));
        more.setBackground(null);   // v10.34: plain text, no pill background
        more.setMinHeight(dp(c, 32));
    }

    /**
     * The two built-in home headers of main.xml ("أحدث الأفلام", "المسلسلات المميزة"): same title
     * size as the dynamic sections, red accent bar at the far right, «المزيد» pill at the left.
     * The header rows themselves are LTR in practice: [more][title] → [more][title][bar].
     */
    public static void styleHomeHeaders(Activity a) {
        if (a == null) return;
        String[][] headers = {
                {"linear_latest_movies_header", "textview_latest_movies_title", "textview_more_latest_movies"},
                {"linear8", "textview6", "textview_more_latest_series"},
        };
        View root = a.getWindow() == null ? null : a.getWindow().getDecorView();
        for (String[] h : headers) {
            try {
                View rowView = byName(root, h[0]);
                if (!(rowView instanceof LinearLayout)) continue;
                LinearLayout row = (LinearLayout) rowView;
                row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(a, 14), 0, dp(a, 16), 0);
                TextView title = (TextView) asText(byName(root, h[1]));
                if (title != null) {
                    title.setTextColor(TEXT);
                    title.setTextSize(SECTION_SP);
                    title.setTypeface(font(a), Typeface.BOLD);
                    title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
                    title.setSingleLine(true);
                    title.setEllipsize(TextUtils.TruncateAt.END);
                    title.setPadding(dp(a, 8), 0, dp(a, 10), 0);
                }
                TextView more = (TextView) asText(byName(root, h[2]));
                if (more != null) {
                    styleMore(more);
                    ViewGroup.LayoutParams lp = more.getLayoutParams();
                    if (lp instanceof LinearLayout.LayoutParams) {
                        LinearLayout.LayoutParams ml = (LinearLayout.LayoutParams) lp;
                        ml.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                        ml.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                        ml.weight = 0f;
                        ml.gravity = Gravity.CENTER_VERTICAL;
                        more.setLayoutParams(ml);
                    }
                }
                if (title != null) {
                    // the title takes the free space so the bar sits at the far right
                    ViewGroup.LayoutParams lp = title.getLayoutParams();
                    if (lp instanceof LinearLayout.LayoutParams) {
                        LinearLayout.LayoutParams tl = (LinearLayout.LayoutParams) lp;
                        tl.width = 0;
                        tl.weight = 1f;
                        tl.height = ViewGroup.LayoutParams.MATCH_PARENT;
                        title.setLayoutParams(tl);
                    }
                }
                // physical order, left → right: [المزيد][title][bar]
                View bar = row.findViewWithTag("cigram_section_accent");
                if (bar == null) bar = accentBar(a);
                detach(bar);
                if (title != null && title.getParent() == row) {
                    if (more != null && more.getParent() == row && row.indexOfChild(more) > row.indexOfChild(title)) {
                        ViewGroup.LayoutParams keep = more.getLayoutParams();
                        row.removeView(more);
                        row.addView(more, row.indexOfChild(title), keep);
                    }
                    row.addView(bar, row.indexOfChild(title) + 1, accentBarParams(a));
                } else {
                    row.addView(bar, accentBarParams(a));
                }
            } catch (Throwable error) {
                android.util.Log.w("CigramDesign", "home header style failed", error);
            }
        }
    }

    /**
     * A ready header row for injected sections: [more?][title][bar] in physical LTR order.
     * Returns the row; the caller adds it where the old header was.
     */
    public static LinearLayout sectionHeader(Context c, String text, TextView more) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 14), 0, dp(c, 16), 0);
        if (more != null) {
            styleMore(more);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
            ml.gravity = Gravity.CENTER_VERTICAL;
            row.addView(more, ml);
        }
        TextView title = new TextView(c);
        title.setTag("cigram_section_title");
        title.setText(text == null ? "" : text);
        title.setTextColor(TEXT);
        title.setTextSize(SECTION_SP);
        title.setTypeface(font(c), Typeface.BOLD);
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        title.setPadding(dp(c, 8), 0, dp(c, 10), 0);
        row.addView(title, new LinearLayout.LayoutParams(0, -1, 1f));
        row.addView(accentBar(c), accentBarParams(c));
        return row;
    }

    /** Puts the accent bar to the right of an existing title TextView (details «ذات صلة»). */
    public static void decorateTitle(TextView title) {
        if (title == null) return;
        try {
            Context c = title.getContext();
            title.setTextColor(TEXT);
            title.setTextSize(20f);
            title.setTypeface(font(c), Typeface.BOLD);
            title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            // 2026: no red accent bar beside section titles any more
            title.setCompoundDrawables(null, null, null, null);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------- grid

    /**
     * Adds up to {@code cards.length} views to a 3-slot grid row so that cards[0] is at the far
     * RIGHT, cards[1] in the middle and cards[2] at the left — in every RTL configuration.
     * Empty slots must be passed as invisible placeholders by the caller.
     */
    public static void fillGridRow(LinearLayout row, View[] cards, int gapPx) {
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.TOP);
        row.setWeightSum(cards.length);
        for (int k = cards.length - 1; k >= 0; k--) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = gapPx;
            lp.rightMargin = gapPx;
            row.addView(cards[k], lp);
        }
    }

    // ------------------------------------------------------------- list rows

    /** Removes v from its parent (if any) so it can be re-added in a new physical position. */
    public static void detach(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    /** Small IMDb score pill used by list rows (search results). */
    public static TextView imdbPill(Context c, String score) {
        TextView t = new TextView(c);
        t.setText("IMDb  " + score);
        t.setTextColor(0xFF1A1400);
        t.setTextSize(11.5f);
        t.setTypeface(font(c), Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setTextDirection(View.TEXT_DIRECTION_LTR);
        t.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2));
        t.setBackground(round(IMDB, 8, c));
        return t;
    }

    /** Wraps a view so it hugs the right edge inside a vertical column. */
    public static FrameLayout alignRight(Context c, View v) {
        FrameLayout f = new FrameLayout(c);
        f.addView(v, new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        return f;
    }
}
