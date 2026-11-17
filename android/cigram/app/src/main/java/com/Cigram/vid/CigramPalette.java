package com.Cigram.vid;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

/**
 * Semantic colours of the redesigned pages (CIGRAM ID / CIGRAM LIVE and everything built after them).
 *
 * One immutable palette per mode. A component asks {@link #of(Context)} with its ACTIVITY context (that context
 * carries the effective night mode: the system one, or the one the user / Admin app forced through
 * {@link CigramTheme#setUserMode}) and uses the named colours; it never decides "white or black" by itself and it
 * never relies on the legacy re-colouring pass of {@link CigramTheme} (roots of redesigned pages carry
 * {@link CigramTheme#SKIP_TAG}). A page is rebuilt with the right palette when the mode changes because the
 * Activity is recreated.
 *
 * The dark palette is the app's original midnight palette, the light one the app's original light palette, so a
 * redesigned page sits next to the older pages without a visible seam. The brand red is the same in both.
 */
final class CigramPalette {

    final boolean light;
    /** Page background. */
    final int page;
    /** A slightly deeper page tone (player stage, status strips). */
    final int pageDeep;
    /** Card / group surface. */
    final int surface;
    /** A raised surface on top of {@link #surface} (inputs, chips, icon tiles). */
    final int surfaceHi;
    /** Borders. */
    final int stroke;
    /** Hairline separators inside a surface. */
    final int hairline;
    final int text;
    final int text2;
    final int text3;
    /** Brand red. */
    final int brand;
    /** Brand red as a soft fill (selected chip, selected card halo). */
    final int brandSoft;
    /** Champagne accent (status chips, premium marks). */
    final int gold;
    final int goldSoft;
    /** Ink on top of {@link #gold} / {@link #brand}. */
    final int onGold;
    final int onBrand;
    /** Press ripple. */
    final int ripple;
    /** Soft plate behind a logo that would vanish on the page (see ChannelCard). */
    final int plateForLight;
    final int plateForDark;

    private CigramPalette(boolean light) {
        this.light = light;
        page = light ? 0xFFF4F5F7 : 0xFF0E0E10;
        pageDeep = light ? 0xFFECEEF1 : 0xFF0C0C0E;
        surface = light ? 0xFFFFFFFF : 0xFF1B1B1F;
        surfaceHi = light ? 0xFFF0F2F4 : 0xFF26262B;
        stroke = light ? 0xFFDADFE3 : 0xFF34343A;
        hairline = light ? 0x14000000 : 0x1FFFFFFF;
        text = light ? 0xFF111418 : 0xFFFFFFFF;
        text2 = light ? 0xFF3C474E : 0xFFC7C7CC;
        text3 = light ? 0xFF5F6B73 : 0xFF8E8E94;
        brand = 0xFFE21B14;
        brandSoft = light ? 0x1AE21B14 : 0x33E21B14;
        gold = light ? 0xFF9A7632 : 0xFFD8BE8C;
        goldSoft = light ? 0x1F9A7632 : 0x26D8BE8C;
        onGold = light ? 0xFFFFFFFF : 0xFF1B1608;
        onBrand = 0xFFFFFFFF;
        ripple = light ? 0x1F000000 : 0x26FFFFFF;
        plateForLight = 0x33000000;   // a pale logo on a light page gets a dim plate
        plateForDark = 0x26FFFFFF;    // a dark logo on a dark page gets a pale plate
    }

    private static final CigramPalette LIGHT = new CigramPalette(true);
    private static final CigramPalette DARK = new CigramPalette(false);

    static CigramPalette of(Context activityContext) {
        return CigramTheme.isLight(activityContext) ? LIGHT : DARK;
    }

    static int alpha(int color, int alpha) {
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Rounded rectangle, optional 1dp stroke (stroke = 0 for none). */
    static GradientDrawable round(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(c, 1)), stroke);
        return g;
    }
}
