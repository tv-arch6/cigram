package com.Cigram.vid;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * «حول Cigram» (19.5) — replaces the old «عن التطبيق» pop-up of the settings page.
 *
 * A page of its own, built on {@link CigramPageKit} (same header as the other pages: title on the right, the back arrow on
 * the right edge pointing right, tap = {@link CigramNav#back}). Content, top to bottom:
 *   1. questions and answers (tap a question to open / close its answer; the first one is open);
 *   2. «كيف أشترك في Cigram Plus؟» — numbered steps, then the two ways to subscribe as real buttons:
 *      the in-app «الدعم والتواصل» conversation and the official WhatsApp account (the message is written for the viewer);
 *   3. the developer's picture (حسن سعيد), aligned to the RIGHT, small; the picture has 20 % round corners and is tilted one
 *      way, and behind it a square of the same size with a black border is tilted the other way.
 *
 * The picture is the drawable {@code about_hassan_saeed} (put the file in res/drawable-nodpi/about_hassan_saeed.png or .jpg).
 * Until it is there, the same frame shows the initials «ح س», so the page never has a hole.
 *
 * Rows are built in PHYSICAL order (the app does not declare supportsRtl), see {@link CigramPageKit}.
 */
public class CigramAboutActivity extends Activity {

    static final String TITLE = "حول Cigram";
    private static final String PHOTO = "about_hassan_saeed";
    private static final String DEV_NAME = "حسن سعيد";

    private CigramPageKit kit;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        kit = CigramPageKit.attach(this, TITLE);
        build();
    }

    private int dp(float v) { return kit.dp(v); }

    private void build() {
        kit.paragraph("كل ما تحتاج تعرفه عن سيكرام في مكان واحد: ما هو، وكيف يعمل، ومن يقف خلفه، وكيف تشترك في Cigram Plus.");

        // ------------------------------------------------------------ 1. questions
        kit.heading("أسئلة وأجوبة");
        LinearLayout faq = kit.card();
        String[][] qa = {
                {"من هو سيكرام؟",
                        "سيكرام (Cigram) تطبيق عربي لمشاهدة الأفلام والمسلسلات والقنوات المباشرة من مكان واحد، "
                                + "بواجهة عربية من اليمين لليسار، وترجمات عربية، وأقسام يرتّبها فريق التطبيق ويحدّثها باستمرار."},
                {"كيف يعمل التطبيق؟",
                        "تفتح الرئيسية فتجد أحدث الأفلام والمسلسلات والأقسام المختارة. اضغط على أي عنوان لتفتح صفحته، "
                                + "ثم «مشاهدة» ليبدأ المشغّل، ويتذكّر التطبيق أين توقفت لتكمل لاحقاً. "
                                + "تبويب «القنوات» للبث المباشر، و«البحث» للوصول لأي عنوان بسرعة، و«الحساب» لقائمتك وإعداداتك واشتراكك."},
                {"من الذي يعمل على سيكرام؟",
                        "سيكرام مشروع مستقل يطوّره ويديره " + DEV_NAME + "، من التصميم والبرمجة إلى إضافة المحتوى ومتابعة القنوات "
                                + "والرد على رسائلكم في «الدعم والتواصل»."},
                {"قناة لا تعمل، ماذا أفعل؟",
                        "إذا كان الإنترنت لديك سليماً والقناة متوقفة يظهر زر «بلّغ المدير» على شاشة البث. اضغطه، "
                                + "ويستبدل المدير الرابط، ويصلك إشعار «تم تفعيل القناة» باسمها حين تعود."},
                {"هل التطبيق مجاني؟",
                        "نعم، المشاهدة مجانية مع إعلانات. واشتراك Cigram Plus يزيل الإعلانات ويفتح الجودة العالية حتى 4K وميزات إضافية."},
        };
        for (int i = 0; i < qa.length; i++) question(faq, qa[i][0], qa[i][1], i == 0, i == qa.length - 1);

        // ------------------------------------------------------------ 2. Cigram Plus
        kit.heading("كيف أشترك في Cigram Plus؟");
        kit.paragraph("Cigram Plus: بدون إعلانات · جودة حتى 4K · صورة داخل صورة · قنوات HD و4K.");
        LinearLayout steps = kit.card();
        boolean sales = CigramPlus.salesEnabled();
        String price = CigramPlus.priceLine(this);
        String[][] list = sales ? new String[][]{
                {"سجّل دخولك", "الاشتراك يرتبط بحسابك: من «الحساب» سجّل الدخول أو أنشئ حساباً أولاً."},
                {"اختر الباقة",
                        "أول اشتراك: " + CigramPlus.INTRO_MONTHS + " أشهر بـ " + CigramPlus.iqd(CigramPlus.INTRO_PRICE_IQD) + " (مرة واحدة لكل حساب). "
                                + "بعدها: " + CigramPlus.STANDARD_MONTHS + " أشهر بـ " + CigramPlus.iqd(CigramPlus.STANDARD_PRICE_IQD) + "."},
                {"راسلنا بإحدى الطريقتين",
                        "محادثة «الدعم والتواصل» داخل التطبيق، أو حساب واتساب الرسمي لسيكرام. اكتب: «اريد الاشتراك في Plus Cigram» واذكر طريقة الدفع."},
                {"ادفع", "بإحدى الطرق: " + CigramPlus.METHOD_LABELS[0] + " · " + CigramPlus.METHOD_LABELS[1] + " · " + CigramPlus.METHOD_LABELS[2] + "."},
                {"استلم كود التفعيل", "يصلك الكود في نفس المحادثة بعد تأكيد الدفع."},
                {"فعّل الاشتراك", "الحساب ← Cigram Plus ← «ضع كود التفعيل» ← الصق الكود ← «تفعيل الخدمة». يتفعّل فوراً وتظهر مدته في حسابك."},
        } : new String[][]{
                {"سجّل دخولك", "الاشتراك يرتبط بحسابك: من «الحساب» سجّل الدخول أو أنشئ حساباً أولاً."},
                {"راسلنا", "من محادثة «الدعم والتواصل» داخل التطبيق أو حساب واتساب الرسمي لسيكرام، واطلب كود Cigram Plus."},
                {"فعّل الاشتراك", "الحساب ← Cigram Plus ← «ضع كود التفعيل» ← الصق الكود ← «تفعيل الخدمة»."},
        };
        for (int i = 0; i < list.length; i++) step(steps, i + 1, list[i][0], list[i][1], i == list.length - 1);
        if (price != null) {
            TextView p = CigramUI.label(this, CigramPageKit.rtl("السعر الحالي لحسابك: " + price), 13f, kit.pal.text3, false);
            p.setGravity(Gravity.RIGHT);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
            pl.topMargin = dp(8);
            kit.column.addView(p, pl);
        }

        // the two ways, as real buttons
        kit.column.addView(button("محادثة الدعم والتواصل", AdsIcons.CHAT, kit.pal.brand, 0xFFFFFFFF, new Runnable() {
            @Override public void run() { CigramSupport.open(CigramAboutActivity.this); }
        }), buttonLp(14));
        kit.column.addView(button("واتساب سيكرام الرسمي", AdsIcons.WHATSAPP, 0xFF1FA855, 0xFFFFFFFF, new Runnable() {
            @Override public void run() {
                CigramPlus.openWhatsAppMessage(CigramAboutActivity.this, "اريد الاشتراك في Plus Cigram"
                        + (CigramPlus.salesEnabled() ? "\nطريقة الدفع: " : ""));
            }
        }), buttonLp(10));
        kit.column.addView(button(CigramPlus.isPlus(this) ? "اشتراكي في Cigram Plus" : "عندي كود · ضع كود التفعيل",
                AdsIcons.CROWN, kit.pal.surfaceHi, kit.pal.text, new Runnable() {
                    @Override public void run() { CigramPlusUI.openPage(CigramAboutActivity.this, "code"); }
                }), buttonLp(10));
        TextView wa = CigramUI.label(this, CigramPageKit.rtl("رقم واتساب الرسمي: ") + "‎+" + CigramPlus.WHATSAPP_NUMBER, 12.5f, kit.pal.text3, false);
        wa.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-1, -2);
        wl.topMargin = dp(8);
        kit.column.addView(wa, wl);

        // ------------------------------------------------------------ 3. the developer
        kit.heading("صُنع بواسطة");
        developer();

        String v = CigramUpdateChecker.installedName(this);
        kit.footnote("سيكرام" + (v.length() == 0 ? "" : " · الإصدار " + v));
    }

    // ------------------------------------------------------------------ pieces

    /** One question: tap to open / close its answer. Physical order: [question, right-aligned][chevron]. */
    private void question(LinearLayout card, String q, String answer, boolean open, boolean last) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        final LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, dp(14), 0, dp(14));
        head.setClickable(true);
        head.setFocusable(true);
        head.setBackground(new RippleDrawable(ColorStateList.valueOf(kit.pal.ripple), null, null));

        final AdsIcons.Icon chevron = new AdsIcons.Icon(this, AdsIcons.CHEVRON_DOWN, kit.pal.text3);
        chevron.setRotation(open ? 180f : 0f);
        head.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));

        TextView t = CigramUI.label(this, CigramPageKit.rtl(q), 15.5f, kit.pal.text, true);
        t.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = dp(10);
        head.addView(t, tl);
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));

        final TextView a = CigramUI.label(this, CigramPageKit.rtl(answer), 14f, kit.pal.text2, false);
        a.setGravity(Gravity.RIGHT);
        a.setLineSpacing(dp(4), 1f);
        a.setPadding(0, 0, 0, dp(14));
        a.setVisibility(open ? View.VISIBLE : View.GONE);
        box.addView(a, new LinearLayout.LayoutParams(-1, -2));

        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean show = a.getVisibility() != View.VISIBLE;
                a.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show) {
                    a.setAlpha(0f);
                    a.animate().alpha(1f).setDuration(180L).start();
                }
                chevron.animate().rotation(show ? 180f : 0f).setDuration(180L).start();
            }
        });
        card.addView(box, new LinearLayout.LayoutParams(-1, -2));
        if (!last) hairline(card);
    }

    /** A numbered step. Physical order: [title / text, right-aligned][number disc]. */
    private void step(LinearLayout card, int n, String title, String text, boolean last) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setPadding(0, dp(12), 0, dp(12));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = CigramUI.label(this, CigramPageKit.rtl(title), 15f, kit.pal.text, true);
        t.setGravity(Gravity.RIGHT);
        col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView s = CigramUI.label(this, CigramPageKit.rtl(text), 13.5f, kit.pal.text2, false);
        s.setGravity(Gravity.RIGHT);
        s.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(3);
        col.addView(s, sl);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.rightMargin = dp(12);
        row.addView(col, cl);

        TextView num = CigramUI.label(this, String.valueOf(n), 13f, 0xFFFFFFFF, true);
        num.setGravity(Gravity.CENTER);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(kit.pal.brand);
        num.setBackground(disc);
        num.setIncludeFontPadding(false);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(dp(26), dp(26));
        nl.topMargin = dp(1);
        row.addView(num, nl);

        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        if (!last) hairline(card);
    }

    private void hairline(LinearLayout card) {
        View line = new View(this);
        line.setBackgroundColor(kit.pal.hairline);
        card.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2 + 1)));
    }

    /** A full-width button: [label][icon], centred, physical order. */
    private View button(String label, int icon, int fill, int ink, final Runnable action) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        b.setGravity(Gravity.CENTER);
        b.setMinimumHeight(dp(50));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription(label);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(14));
        if (fill == kit.pal.surfaceHi) bg.setStroke(Math.max(1, dp(1)), kit.pal.stroke);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null));
        TextView t = CigramUI.label(this, CigramPageKit.rtl(label), 15f, ink, true);
        b.addView(t, CigramPageKit.wrap());
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(20), dp(20));
        il.leftMargin = dp(10);
        b.addView(new AdsIcons.Icon(this, icon, ink), il);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { action.run(); } catch (Throwable error) { android.util.Log.w("CigramAbout", "action failed", error); }
            }
        });
        return b;
    }

    private LinearLayout.LayoutParams buttonLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(topDp);
        return lp;
    }

    /**
     * The picture at the end of the page, on the RIGHT, small. Behind it: a square of the same size with a black border,
     * tilted the opposite way. Picture corners = 20 % of its side. Under it: the name.
     */
    private void developer() {
        final int side = dp(128);
        final int room = side + dp(28);            // a tilted square needs a little more room than its side
        final float radius = side * 0.20f;

        FrameLayout stage = new FrameLayout(this);

        // the square behind (black border, tilted the other way). On the dark page a black line alone would vanish,
        // so the square is filled with the raised surface colour and the border stays black.
        View frame = new View(this);
        GradientDrawable sq = new GradientDrawable();
        sq.setColor(kit.pal.light ? 0x00000000 : kit.pal.surfaceHi);
        sq.setCornerRadius(radius);
        sq.setStroke(dp(3), 0xFF000000);
        frame.setBackground(sq);
        frame.setRotation(-7f);
        stage.addView(frame, new FrameLayout.LayoutParams(side, side, Gravity.CENTER));

        // the picture (or the initials until the picture is added)
        View photo;
        int id = getResources().getIdentifier(PHOTO, "drawable", getPackageName());
        if (id != 0) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageResource(id);
            img.setContentDescription(DEV_NAME);
            photo = img;
        } else {
            TextView initials = CigramUI.label(this, "ح س", 34f, 0xFFFFFFFF, true);
            initials.setGravity(Gravity.CENTER);
            initials.setBackgroundColor(kit.pal.brand);
            initials.setContentDescription(DEV_NAME);
            photo = initials;
        }
        photo.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        photo.setClipToOutline(true);
        photo.setElevation(dp(6));
        photo.setRotation(5f);
        stage.addView(photo, new FrameLayout.LayoutParams(side, side, Gravity.CENTER));

        // right-aligned block: the picture, then the name under it
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.RIGHT);
        block.addView(stage, new LinearLayout.LayoutParams(room, room));

        TextView name = CigramUI.label(this, CigramPageKit.rtl(DEV_NAME), 17f, kit.pal.text, true);
        name.setGravity(Gravity.CENTER);
        block.addView(name, new LinearLayout.LayoutParams(room, -2));
        TextView role = CigramUI.label(this, CigramPageKit.rtl("مطوّر ومؤسس Cigram"), 13f, kit.pal.text3, false);
        role.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(room, -2);
        rl.topMargin = dp(2);
        block.addView(role, rl);

        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = dp(6);
        kit.column.addView(block, bl);
    }
}
