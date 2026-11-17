package com.Cigram.vid;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;

/**
 * The 2026 Cigram identity in code: the white Cigram mark used as a poster watermark and as the faint placeholder of
 * channel artwork that has not loaded yet. All pictures come from res/drawable-nodpi (generated from the official
 * logo, see branding/make_assets.py); nothing is drawn by hand here.
 */
final class CigramBrand {

    /** Opacity of the poster watermark (0-255): visible on close look, never competes with the poster. */
    static final int WATERMARK_ALPHA = 92;
    /** Size and margin of the poster watermark. */
    static final int WATERMARK_DP = 13;
    static final int WATERMARK_MARGIN_DP = 7;
    /** Opacity of the placeholder mark shown while a channel picture loads. */
    static final int PLACEHOLDER_ALPHA = 46;

    private CigramBrand() { }

    private static Drawable named(Context c, String name) {
        if (c == null) return null;
        try {
            int id = c.getResources().getIdentifier(name, "drawable", c.getPackageName());
            if (id == 0) return null;
            Drawable d = Build.VERSION.SDK_INT >= 21 ? c.getResources().getDrawable(id, null) : c.getResources().getDrawable(id);
            return d == null ? null : d.mutate();
        } catch (Throwable error) {
            return null;
        }
    }

    /** Small white Cigram silhouette for the corner of a poster (alpha applied). */
    static Drawable watermark(Context c) {
        Drawable d = named(c, "cigram_watermark");
        if (d != null) d.setAlpha(WATERMARK_ALPHA);
        return d;
    }

    /** The white Cigram mark at placeholder opacity (channel artwork that has not loaded yet). */
    static Drawable placeholder(Context c) {
        Drawable d = named(c, "cigram_mark");
        if (d != null) d.setAlpha(PLACEHOLDER_ALPHA);
        return d;
    }

    /**
     * Adds the watermark as an extra layer of a poster's foreground (top-left corner: the corner that carries no
     * badge, heart or rank on any Cigram card). Returns the layered drawable, or {@code frame} unchanged before API 23.
     */
    static Drawable withWatermark(Context c, Drawable frame) {
        if (Build.VERSION.SDK_INT < 23 || c == null) return frame;
        Drawable mark = watermark(c);
        if (mark == null) return frame;
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                frame == null ? new Drawable[]{mark} : new Drawable[]{frame, mark});
        int idx = frame == null ? 0 : 1;
        int size = CigramUI.dp(c, WATERMARK_DP);
        int margin = CigramUI.dp(c, WATERMARK_MARGIN_DP);
        layers.setLayerGravity(idx, Gravity.TOP | Gravity.LEFT);
        layers.setLayerSize(idx, size, size);
        layers.setLayerInset(idx, margin, margin, 0, 0);
        return layers;
    }
}
