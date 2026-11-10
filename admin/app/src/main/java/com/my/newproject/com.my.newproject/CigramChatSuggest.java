package com.my.newproject;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
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
 * A chip is a ready sentence: one tap writes it into the field (the user can still edit it before sending — nothing is
 * sent on his behalf). The row follows the conversation: an empty conversation offers the opening questions about
 * advertising, a price offer from the developer offers answers to it, and so on ({@link #forUser}). It hides itself
 * while the user is typing or recording.
 *
 * Look: dark glass pills with a thin red-to-gold ring (the two Cigram accents), an emoji from the app's own atlas and
 * a soft press animation. One recycled row, no images, nothing loaded from the network.
 */
final class CigramChatSuggest extends HorizontalScrollView {

    interface Listener { void onPick(String text); }

    private final LinearLayout row;
    private final float d;
    private Listener listener;
    private String signature = "";

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
     * @param items rows of {emoji, label, sentence}; the sentence is what is written into the field (label when empty)
     */
    void setItems(String[][] items) {
        StringBuilder sig = new StringBuilder();
        for (String[] it : items) sig.append(it[1]).append('|');
        if (sig.toString().equals(signature)) return;      // same chips: nothing is rebuilt (no flicker on new messages)
        signature = sig.toString();
        row.removeAllViews();
        // the app builds RTL screens by hand: the first suggestion sits on the right
        setLayoutDirection(LAYOUT_DIRECTION_RTL);
        row.setLayoutDirection(LAYOUT_DIRECTION_RTL);
        for (int i = 0; i < items.length; i++) {
            final String[] it = items[i];
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38));
            lp.setMargins(dp(4), 0, dp(4), 0);
            row.addView(chip(it[0], it[1], it.length > 2 && it[2] != null && it[2].length() > 0 ? it[2] : it[1]), lp);
        }
        post(new Runnable() { @Override public void run() { fullScroll(FOCUS_RIGHT); } });
    }

    private View chip(final String emoji, String label, final String sentence) {
        final LinearLayout chip = new LinearLayout(getContext());
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setLayoutDirection(LAYOUT_DIRECTION_RTL);
        chip.setPadding(dp(13), 0, dp(13), 0);
        chip.setBackground(new Ring(d));
        chip.setContentDescription(label);
        if (emoji != null && emoji.length() > 0) {
            EmojiDot dot = new EmojiDot(getContext(), emoji);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(19), dp(19));
            dl.setMargins(dp(7), 0, 0, 0);
            chip.addView(dot, dl);
        }
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(13.5f);
        t.setMaxLines(1);
        t.setIncludeFontPadding(false);
        chip.addView(t, new LinearLayout.LayoutParams(-2, -2));
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

    /** The viewer's side («أريد إعلاناً»). */
    static String[][] forUser(boolean emptyConversation, String lastDeveloperType, boolean lastIsMine) {
        if ("quote".equals(lastDeveloperType) && !lastIsMine) {
            return new String[][]{
                    {"✅", "موافق على العرض", "موافق على العرض، كيف أكمل الدفع؟"},
                    {"✏️", "أريد تعديل العرض", "أريد تعديلاً على العرض: "},
                    {"💳", "طرق الدفع", "ما طرق الدفع المتاحة؟"},
                    {"📅", "متى يبدأ الإعلان؟", "متى يبدأ ظهور الإعلان بعد الدفع؟"},
            };
        }
        if (emptyConversation) {
            return new String[][]{
                    {"📣", "أريد إعلاناً", "مرحباً، أريد إعلاناً داخل Cigram. ما الخطوات؟"},
                    {"💰", "الأسعار والباقات", "ما أسعار الإعلانات والباقات المتوفرة؟"},
                    {"📍", "أماكن ظهور الإعلان", "أين يظهر الإعلان داخل التطبيق؟ وما الفرق بين الأماكن؟"},
                    {"📅", "مدة الإعلان", "ما المدد المتاحة للإعلان (يوم / أسبوع / شهر)؟"},
                    {"🎨", "أحتاج تصميماً", "ليس لدي تصميم جاهز، هل يمكنكم تصميم الإعلان؟"},
                    {"💳", "طرق الدفع", "ما طرق الدفع المتاحة؟"},
                    {"🛠️", "مشكلة في التطبيق", "أواجه مشكلة في التطبيق وأحتاج مساعدة: "},
            };
        }
        return new String[][]{
                {"💰", "الأسعار", "ما أسعار الإعلانات والباقات المتوفرة؟"},
                {"📊", "نتائج إعلاني", "أريد معرفة نتائج وإحصائيات إعلاني الحالي."},
                {"🔁", "تجديد الإعلان", "أريد تجديد إعلاني لمدة إضافية."},
                {"🎨", "تغيير التصميم", "أريد تغيير تصميم إعلاني الحالي."},
                {"👍", "شكراً", "شكراً لكم 🌹"},
        };
    }

    /** The developer's side (Cigram Admin): the usual answers. Saved templates of the admin are appended by the caller. */
    static String[][] forDeveloper(boolean emptyConversation, String lastUserType) {
        if (emptyConversation) {
            return new String[][]{
                    {"👋", "ترحيب", "أهلاً بك في Cigram 👋 كيف أستطيع مساعدتك؟"},
                    {"📣", "بدء طلب إعلان", "يسعدنا إعلانك معنا. أرسل لي: مكان الظهور المطلوب، المدة، وتصميم الإعلان إن وُجد."},
            };
        }
        if ("image".equals(lastUserType) || "video".equals(lastUserType) || "file".equals(lastUserType)) {
            return new String[][]{
                    {"✅", "تم الاستلام", "تم استلام الملف ✅ سأراجعه وأعود إليك."},
                    {"📐", "المقاس غير مناسب", "المقاس غير مناسب لمكان الإعلان. أرسل التصميم بالمقاس المطلوب من فضلك."},
                    {"🔗", "أرسل الرابط", "أرسل لي الرابط الذي يفتحه الإعلان عند الضغط."},
                    {"💰", "سأرسل عرض السعر", "ممتاز، سأرسل لك عرض السعر الآن."},
            };
        }
        return new String[][]{
                {"👋", "ترحيب", "أهلاً بك 👋 كيف أستطيع مساعدتك؟"},
                {"💰", "عن الأسعار", "السعر يعتمد على مكان الظهور والمدة. أخبرني بما تحتاجه وسأجهّز لك عرضاً."},
                {"🎨", "اطلب التصميم", "أرسل لي تصميم الإعلان (صورة أو فيديو) والرابط المطلوب."},
                {"🚀", "تم التفعيل", "تم تفعيل إعلانك 🚀 يمكنك متابعة نتائجه من «حملاتي الإعلانية»."},
                {"⏳", "قيد المراجعة", "طلبك قيد المراجعة وسأعود إليك قريباً."},
                {"🌟", "شكراً", "شكراً لتواصلك مع Cigram 🌟"},
        };
    }

    // ------------------------------------------------------------------ drawing

    /** Dark glass pill with a 1.2 dp ring that runs from Cigram red to Cigram gold. */
    private static final class Ring extends Drawable {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float d;

        Ring(float density) {
            d = density;
            fill.setColor(0xFF151517);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(1.2f * d);
        }

        @Override protected void onBoundsChange(Rect b) {
            ring.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                    new int[]{0xFFE21B14, 0xFFFF6A5E, 0xFFF2C14E}, null, Shader.TileMode.CLAMP));
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

    private static final class EmojiDot extends View {
        private final int idx;
        private final RectF dst = new RectF();

        EmojiDot(Context c, String emoji) {
            super(c);
            Integer i = CigramEmoji.index(c).lookup.get(emoji);
            if (i == null) i = CigramEmoji.index(c).lookup.get(emoji.replace("️", ""));
            idx = i == null ? -1 : i;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            dst.set(0, 0, getWidth(), getHeight());
            CigramEmoji.draw(getContext(), canvas, idx, dst);
        }
    }
}
