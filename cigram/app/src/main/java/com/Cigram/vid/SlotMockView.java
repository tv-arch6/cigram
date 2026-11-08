package com.Cigram.vid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/** محاكاة مرسومة بالكود لشكل كل مساحة إعلانية داخل التطبيق (هاتف مصغّر). */
public final class SlotMockView extends View {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private String slot = "hero";
    private final float d;

    public SlotMockView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
    }

    public void setSlot(String s) {
        slot = s == null ? "hero" : s;
        invalidate();
    }

    private void box(Canvas c, float l, float t, float rr, float b, float rad, int color) {
        r.set(l, t, rr, b);
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(color);
        c.drawRoundRect(r, rad, rad, p);
    }

    private void adBox(Canvas c, float l, float t, float rr, float b, float rad) {
        r.set(l, t, rr, b);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(l, t, rr, b, 0xFF26A5B5, 0xFF1B6E8C, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, p);
        p.setShader(null);
        // وسم «إعلان»
        float w = Math.min(rr - l - 6 * d, 30 * d);
        box(c, rr - w - 4 * d, t + 4 * d, rr - 4 * d, t + 4 * d + 10 * d, 3 * d, 0xCC000000);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(7 * d);
        p.setTypeface(CigramUI.font(getContext()));
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("إعلان", rr - 4 * d - w / 2f, t + 4 * d + 7.6f * d, p);
        // ملامح المحتوى داخل الإعلان
        box(c, l + 8 * d, b - 16 * d, l + 8 * d + (rr - l) * 0.45f, b - 11 * d, 2 * d, 0xCCFFFFFF);
        box(c, l + 8 * d, b - 9 * d, l + 8 * d + (rr - l) * 0.28f, b - 5 * d, 2 * d, 0x88FFFFFF);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        // جسم الهاتف
        box(c, 0, 0, w, h, 16 * d, 0xFF1F4553);
        float m = 3 * d;
        box(c, m, m, w - m, h - m, 13 * d, 0xFF05141F);
        float x0 = m + 7 * d, x1 = w - m - 7 * d;
        float y = m + 14 * d;
        // شريط علوي
        box(c, x0, m + 6 * d, x0 + 34 * d, m + 10 * d, 2 * d, 0xFF2C5463);
        boolean splash = "splash".equals(slot);
        boolean popup = "popup".equals(slot);
        boolean hero = "hero".equals(slot);
        if (splash) {
            adBox(c, m, m, w - m, h - m, 13 * d);
            box(c, w - m - 30 * d, m + 22 * d, w - m - 6 * d, m + 36 * d, 7 * d, 0x99000000);
            p.setColor(0xFFFFFFFF); p.setTextSize(8 * d); p.setTextAlign(Paint.Align.CENTER);
            c.drawText("تخطي", w - m - 18 * d, m + 32 * d, p);
            return;
        }
        float hh = hero ? 52 * d : 0;
        if (hero) {
            adBox(c, x0, y, x1, y + hh, 8 * d);
            for (int i = 0; i < 4; i++) box(c, w / 2f - 10 * d + i * 5 * d, y + hh - 6 * d + 0, w / 2f - 7 * d + i * 5 * d, y + hh - 3 * d, 1.5f * d, i == 0 ? 0xFFFFFFFF : 0x66FFFFFF);
            y += hh + 8 * d;
        }
        // عنوان قسم + رعاية
        box(c, x1 - 40 * d, y, x1, y + 5 * d, 2 * d, 0xFF4A6B79);
        if ("sponsor".equals(slot)) {
            box(c, x0, y - 3 * d, x0 + 44 * d, y + 9 * d, 5 * d, 0xFF26A5B5);
            p.setColor(0xFF04161D); p.setTextSize(7 * d); p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(CigramUI.font(getContext()));
            c.drawText("برعاية", x0 + 22 * d, y + 5.6f * d, p);
        }
        y += 11 * d;
        float pw = (x1 - x0 - 12 * d) / 3f, ph = 34 * d;
        for (int i = 0; i < 3; i++) box(c, x0 + i * (pw + 6 * d), y, x0 + i * (pw + 6 * d) + pw, y + ph, 5 * d, 0xFF2C5463);
        y += ph + 8 * d;
        if ("inline".equals(slot)) {
            adBox(c, x0, y, x1, y + 38 * d, 8 * d);
            y += 46 * d;
        }
        if (y + ph < h - 28 * d) {
            for (int i = 0; i < 3; i++) box(c, x0 + i * (pw + 6 * d), y, x0 + i * (pw + 6 * d) + pw, y + ph, 5 * d, 0xFF244B59);
        }
        // شريط ثابت + تنقل سفلي
        float navTop = h - m - 20 * d;
        if ("sticky".equals(slot)) {
            adBox(c, x0 - 3 * d, navTop - 24 * d, x1 + 3 * d, navTop - 4 * d, 6 * d);
        }
        box(c, m, navTop, w - m, h - m, 13 * d, 0xFF0B2733);
        for (int i = 0; i < 4; i++) box(c, x0 + 6 * d + i * ((x1 - x0 - 12 * d) / 4f), navTop + 7 * d, x0 + 18 * d + i * ((x1 - x0 - 12 * d) / 4f), navTop + 13 * d, 3 * d, i == 0 ? 0xFF26A5B5 : 0xFF3E6270);
        if (popup) {
            box(c, m, m, w - m, h - m, 13 * d, 0xAA000000);
            float pl = w * 0.14f, pr = w * 0.86f, pt = h * 0.26f, pb = h * 0.72f;
            adBox(c, pl, pt, pr, pb, 10 * d);
            box(c, pl - 5 * d, pt - 5 * d, pl + 9 * d, pt + 9 * d, 7 * d, 0xFF05141F);
            p.setColor(0xFFFFFFFF); p.setTextSize(9 * d); p.setTextAlign(Paint.Align.CENTER);
            c.drawText("×", pl + 2 * d, pt + 5 * d, p);
        }
    }

    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        int h = Math.round(w * 1.55f);
        setMeasuredDimension(w, h);
    }
}
