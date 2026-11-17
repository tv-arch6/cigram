package com.Cigram.vid;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Responsive grid of {@link CigramChannelCard}s for the live page.
 *
 *  - the number of columns follows the width it is given (2 on a narrow phone, 3 on a normal one, more on a
 *    tablet), every cell has the same size, so logos never stretch;
 *  - it fills from the RIGHT edge (Arabic reading order): the first channel is the top-right one. This is done
 *    here, once, instead of relying on a layout direction the app does not enable;
 *  - children that are GONE (filtered out by the search) take no room; showing / hiding a card never rebuilds
 *    anything.
 */
final class CigramChannelGrid extends ViewGroup {

    private final float dp;
    private final int minCell;
    private final int gap;
    private final int rowGap;
    private int columns = 3;
    private int cellW;
    private int cellH;

    CigramChannelGrid(Context c) {
        super(c);
        dp = c.getResources().getDisplayMetrics().density;
        minCell = Math.round(104 * dp);
        gap = Math.round(10 * dp);
        rowGap = Math.round(6 * dp);
        setClipChildren(false);
        setClipToPadding(false);
    }

    int columns() {
        return columns;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int avail = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        columns = Math.max(2, (avail + gap) / (minCell + gap));
        cellW = Math.max(1, (avail - (columns - 1) * gap) / columns);
        cellH = CigramChannelCard.heightFor(getContext(), cellW);
        int visible = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            visible++;
            child.measure(MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY));
        }
        int rows = (visible + columns - 1) / columns;
        int height = rows == 0 ? 0 : rows * cellH + (rows - 1) * rowGap + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int right = getWidth() - getPaddingRight();
        int slot = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int row = slot / columns, col = slot % columns;
            int cr = right - col * (cellW + gap);
            int top = getPaddingTop() + row * (cellH + rowGap);
            child.layout(cr - cellW, top, cr, top + cellH);
            slot++;
        }
    }

    /** Number of cards that are currently shown. */
    int visibleCount() {
        int n = 0;
        for (int i = 0; i < getChildCount(); i++) if (getChildAt(i).getVisibility() != GONE) n++;
        return n;
    }
}
