package com.my.newproject;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Suggestion chips above the message field of the private conversation.
 *
 * A chip is a ready sentence: one tap writes it into the field (the admin can still edit it before sending — nothing
 * is sent on his behalf). The row follows the conversation: an empty conversation offers the greeting and the Plus
 * prices, a file from the user offers the receipt answers, and so on ({@link #forDeveloper}). It hides itself while
 * the admin is typing or recording.
 *
 * Look: black pills with a thin white ring and a white line icon from the admin's own set ({@link CgIcons}); no emoji,
 * no colours, no images, nothing loaded from the network. (The viewer's side lives in the app, not here.)
 */
final class CigramChatSuggest extends HorizontalScrollView {

    interface Listener { void onPick(String text); }

    private final LinearLayout row;
    private final float d;
    private Listener listener;
    private String signature = "";
    private boolean openAtRight;

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (openAtRight && row.getWidth() > 0) {
            openAtRight = false;
            scrollTo(Math.max(0, row.getWidth() + getPaddingLeft() + getPaddingRight() - getWidth()), 0);
        }
    }

    CigramChatSuggest(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFillViewport(false);
        setClipToPadding(false);
        setPadding(dp(10), dp(6), dp(10), dp(6));
        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, new LayoutParams(-2, -2));
    }

    private int dp(float v) { return (int) (v * d + 0.5f); }

    void setListener(Listener l) { listener = l; }

    /**
     * @param items rows of {icon (a {@link CgIcons} number, see {@link #iconOf}), label, sentence}; the sentence is
     *              what is written into the field (label when empty)
     */
    void setItems(String[][] items) {
        StringBuilder sig = new StringBuilder();
        for (String[] it : items) sig.append(it[1]).append('|');
        if (sig.toString().equals(signature)) return;      // same chips: nothing is rebuilt (no flicker on new messages)
        signature = sig.toString();
        row.removeAllViews();
        // The app has no RTL layout support switched on (the screens are mirrored by hand): the chips are added in
        // reverse so the FIRST suggestion is the right-most one, and the row opens scrolled to its right end.
        for (int i = items.length - 1; i >= 0; i--) {
            final String[] it = items[i];
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38));
            lp.setMargins(dp(4), 0, dp(4), 0);
            row.addView(chip(it[0], it[1], it.length > 2 && it[2] != null && it[2].length() > 0 ? it[2] : it[1]), lp);
        }
        openAtRight = true;
        requestLayout();
    }

    private View chip(final String iconCell, String label, final String sentence) {
        final LinearLayout chip = new LinearLayout(getContext());
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(13), 0, dp(13), 0);
        chip.setBackground(new Ring(d));
        chip.setContentDescription(label);
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(13.5f);
        t.setMaxLines(1);
        t.setIncludeFontPadding(false);
        chip.addView(t, new LinearLayout.LayoutParams(-2, -2));
        int kind = iconOf(iconCell);
        if (kind >= 0) {                                      // Arabic reads from the right: the icon leads on the right
            View dot = new View(getContext());
            dot.setBackground(CgIcons.icon(kind, 0xFFFFFFFF));
            dot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(18), dp(18));
            dl.setMargins(dp(8), 0, 0, 0);
            chip.addView(dot, dl);
        }
        chip.setOnTouchListener(new OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) v.animate().scaleX(0.95f).scaleY(0.95f).alpha(0.8f).setDuration(70).start();
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start();
                return false;
            }
        });
        chip.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                try { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); } catch (Throwable ignored) { }
                if (listener != null) listener.onPick(sentence);
            }
        });
        return chip;
    }

    // ------------------------------------------------------------------ what to offer

    /** The icon number of a chip row ({@link CgIcons}); -1 = none. */
    static int iconOf(String cell) {
        try { return cell == null ? -1 : Integer.parseInt(cell.trim()); } catch (Throwable e) { return -1; }
    }

    private static String ic(int kind) { return String.valueOf(kind); }

    /** The admin's side (Cigram Admin): the usual answers. Saved templates of the admin are appended by the caller. */
    static String[][] forDeveloper(boolean emptyConversation, String lastUserType) {
        if (emptyConversation) {
            return new String[][]{
                    {ic(CgIcons.CHAT), "ترحيب", "أهلاً بك في Cigram، كيف أستطيع مساعدتك؟"},
                    {ic(CgIcons.CHART), "أسعار Plus", "أسعار Cigram Plus الحالية تجدها داخل صفحة Cigram Plus، وأي مدة تختارها أفعّلها لك فوراً."},
            };
        }
        if ("image".equals(lastUserType) || "video".equals(lastUserType) || "file".equals(lastUserType)) {
            return new String[][]{
                    {ic(CgIcons.CHECK), "تم الاستلام", "تم استلام الملف، سأراجعه وأعود إليك."},
                    {ic(CgIcons.FILE), "وصل الدفع", "وصلني إثبات الدفع، سأفعّل اشتراكك الآن."},
                    {ic(CgIcons.INFO), "أحتاج تفاصيل", "أرسل لي اسم العنوان ورقم الحلقة التي تظهر فيها المشكلة."},
            };
        }
        return new String[][]{
                {ic(CgIcons.CHAT), "ترحيب", "أهلاً بك، كيف أستطيع مساعدتك؟"},
                {ic(CgIcons.PLUS), "مزايا Plus", "Cigram Plus: مشاهدة بلا إعلانات، جودة أعلى، موقّت النوم، وأيقونات وخطوط خاصة."},
                {ic(CgIcons.STAR), "تم التفعيل", "تم تفعيل اشتراكك في Cigram Plus، مشاهدة ممتعة."},
                {ic(CgIcons.HISTORY), "قيد المراجعة", "طلبك قيد المراجعة وسأعود إليك قريباً."},
                {ic(CgIcons.CHECK), "تم الحل", "تم حل المشكلة، جرّب الآن وأخبرني."},
        };
    }

    // ------------------------------------------------------------------ drawing

    /** Black pill with a thin white ring (the monochrome iOS look of the whole admin). */
    private static final class Ring extends Drawable {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float d;

        Ring(float density) {
            d = density;
            fill.setColor(0xFF0E0E10);
            ring.setColor(0x59FFFFFF);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(1f * d);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float in = 0.8f * d, rad = b.height() / 2f;
            r.set(b.left + in, b.top + in, b.right - in, b.bottom - in);
            c.drawRoundRect(r, rad, rad, fill);
            c.drawRoundRect(r, rad, rad, ring);
        }

        @Override public void setAlpha(int a) { ring.setAlpha(a); fill.setAlpha(a); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter f) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
