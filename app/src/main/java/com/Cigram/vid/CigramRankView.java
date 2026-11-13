package com.Cigram.vid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * The rank numeral of a «المزيد» card, drawn (not a TextView) so it can be outlined, gradient-filled and bigger than its box.
 *
 *  - Rank 1: a very large numeral in translucent white (≈28 %), drawn over the poster's inner edge like a watermark. It is meant to
 *    be placed above the poster in a parent that does not clip its children; the poster and the title stay readable through it.
 *  - Rank 2+: the same language, calmer: an outlined numeral with a soft gradient fill, the same size for every card, centred in a
 *    fixed-width column so all cards line up. Three-digit ranks shrink to fit the column.
 *
 * Digits: the app writes years, ratings and episode numbers with Latin digits everywhere, so the rank follows that convention.
 * {@link #format} is the single place to change if Arabic-Indic digits (١ ٢ ٣) are ever wanted.
 */
final class CigramRankView extends View {

    /** Width of the rank column every card reserves (dp). The #1 numeral may paint wider than this. */
    static final int COLUMN_DP = 52;
    /** How far the #1 watermark reaches over the poster (dp), measured from the column's inner edge. */
    static final int WATERMARK_OVERLAP_DP = 30;
    /** How much of the #1 numeral lies over the poster (dp): enough to read as a watermark, never enough to hide the poster. */
    private static final int VISIBLE_OVERLAP_DP = 24;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect bounds = new Rect();
    private final float density;
    private final boolean light;

    private String text = "";
    private int rank;
    private boolean sizeDirty = true;

    CigramRankView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        light = CigramTheme.isLight(context);
        Typeface face = CigramTypefaces.app(context);
        fill.setTypeface(face);
        stroke.setTypeface(face);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setFakeBoldText(true);
        stroke.setFakeBoldText(true);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);   // the card itself says «المركز N»
        setClickable(false);
        setFocusable(false);
    }

    /** The label for a rank. One place for the digit convention (Latin, as everywhere else in the app). */
    static String format(int rank) {
        return String.valueOf(Math.max(1, rank));
    }

    void setRank(int rank) {
        if (this.rank == rank && text.length() > 0) return;
        this.rank = rank;
        this.text = format(rank);
        sizeDirty = true;
        invalidate();
    }

    int rank() { return rank; }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        sizeDirty = true;
    }

    private void layoutPaints() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || text.length() == 0) return;
        boolean first = rank == 1;
        int white = light ? 0x111418 : 0xFFFFFF;                      // rgb of the ink; alpha is set per paint below

        float size;
        if (first) {
            size = h * 0.92f;                                          // very large: nearly the card's height
        } else {
            size = Math.min(h * 0.46f, 40f * density);                 // the same size for every card from 2 on
        }
        fill.setTextSize(size);
        stroke.setTextSize(size);
        float colWidth = first ? w : COLUMN_DP * density;
        // keep long numerals (100+) inside the column
        float measured = fill.measureText(text);
        float maxWidth = first ? w : colWidth - 6f * density;
        if (measured > maxWidth && measured > 0f) {
            float k = maxWidth / measured;
            fill.setTextSize(size * k);
            stroke.setTextSize(size * k);
        }

        stroke.setStrokeWidth((first ? 1.4f : 1.6f) * density);
        fill.setShader(null);
        if (first) {
            fill.setColor(0x47000000 | white);                             // ≈28 % ink: a watermark, never a cover
            stroke.setColor(0x66000000 | white);                           // ≈40 % hairline keeps the edge crisp over a bright poster
        } else {
            fill.setShader(new LinearGradient(0f, h * 0.25f, 0f, h * 0.75f,
                    new int[]{(0x38 << 24) | white, (0x08 << 24) | white}, null, Shader.TileMode.CLAMP));
            stroke.setColor((0xB8 << 24) | white);                         // outlined: ≈72 % ink
        }
        sizeDirty = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (text.length() == 0) return;
        if (sizeDirty) layoutPaints();
        int w = getWidth();
        int h = getHeight();
        fill.getTextBounds(text, 0, text.length(), bounds);
        float baseline = (h - bounds.height()) / 2f - bounds.top;
        float x;
        if (rank == 1) {
            // the watermark: its left edge sits OVERLAP_DP over the poster's inner edge (this view starts WATERMARK_OVERLAP_DP before the
            // rank column), so it straddles the poster edge; kept inside the view on the right
            float colWidth = COLUMN_DP * density;
            x = (w - colWidth) - VISIBLE_OVERLAP_DP * density - bounds.left;
            float limit = w - bounds.right - 2f * density;
            if (x > limit) x = limit;
        } else {
            float colWidth = COLUMN_DP * density;
            x = w - (colWidth + bounds.width()) / 2f - bounds.left;       // centred in the fixed column at the right edge
        }
        canvas.drawText(text, x, baseline, fill);
        canvas.drawText(text, x, baseline, stroke);
    }
}
