package com.my.newproject;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** تخطيط يلتفّ على أسطر (للرقاقات والأزرار) بفواصل ثابتة. */
final class AdFlow extends ViewGroup {

    private final int gap;

    AdFlow(Context c) {
        super(c);
        gap = CgUi.dp(c, 6);
    }

    void add(View v) {
        addView(v);
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int maxW = View.MeasureSpec.getSize(wSpec);
        int x = 0, y = 0, rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            c.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            if (x > 0 && x + c.getMeasuredWidth() > maxW) { x = 0; y += rowH + gap; rowH = 0; }
            x += c.getMeasuredWidth() + gap;
            rowH = Math.max(rowH, c.getMeasuredHeight());
        }
        setMeasuredDimension(maxW, y + rowH);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int x = 0, y = 0, rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            int cw = c.getMeasuredWidth(), ch = c.getMeasuredHeight();
            if (x > 0 && x + cw > w) { x = 0; y += rowH + gap; rowH = 0; }
            int left = rtl ? w - x - cw : x;
            c.layout(left, y, left + cw, y + ch);
            x += cw + gap;
            rowH = Math.max(rowH, ch);
        }
    }
}
