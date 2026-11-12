package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The Cigram Plus page (premium design):
 *   hero  ->  [your plan: from / to + progress]  ->  link to the code page  ->  what you get (6 cards)  ->  offer price
 *   ->  payment methods (the three logos side by side, pick one)  ->  what happens next for that method  ->  yellow WhatsApp button
 *   ->  trust points.
 * No payment data is typed or sent inside the app: payment happens in the WhatsApp conversation, and the message names the chosen method.
 */
public class CigramPlusActivity extends Activity {

    static final String EXTRA_FOCUS = "focus";      // "code" | "pay"

    private ScrollView scroll;
    private LinearLayout column;
    private LinearLayout planHolder;       // "your plan" card (only with Plus), rebuilt by renderState()
    private LinearLayout offerHolder;
    private View paySection;
    private LinearLayout methodsRow;
    private LinearLayout methodDetail;
    private TextView waLabel;
    private TextView waSub;
    private final int[] selected = {-1};

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            getWindow().setStatusBarColor(CigramPlusUI.PAGE_BG);
            getWindow().setNavigationBarColor(CigramPlusUI.PAGE_BG);
        } catch (Throwable ignored) { }
        try {
            build();
            renderState();
        } catch (Throwable t) {
            // never die silently: show what failed (and log it) so the cause can be fixed
            android.util.Log.e("CigramPlus", "Plus page failed to build", t);
            showFailure(t);
            return;
        }
        String focus = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_FOCUS);
        if ("pay".equals(focus)) scrollToPay();
    }

    private void showFailure(Throwable t) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            String trace = sw.toString();
            if (trace.length() > 1800) trace = trace.substring(0, 1800);
            ScrollView sv = new ScrollView(this);
            sv.setBackgroundColor(CigramPlusUI.PAGE_BG);
            TextView tv = new TextView(this);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(12f);
            tv.setTextIsSelectable(true);
            tv.setPadding(CigramUI.dp(this, 16), CigramUI.dp(this, 40), CigramUI.dp(this, 16), CigramUI.dp(this, 16));
            tv.setText("تعذّر فتح صفحة Plus.\nصوّر هذه الشاشة وأرسلها للمطوّر:\n\n" + trace);
            sv.addView(tv);
            setContentView(sv);
        } catch (Throwable ignored) {
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (scroll == null) return;      // the page failed to build: the error screen is showing
        CigramPlus.refresh(this, true, new Runnable() {
            @Override public void run() { if (!isFinishing() && !isDestroyed()) renderState(); }
        });
    }

    // ------------------------------------------------------------------ build (once)

    private int dp(float v) {
        return CigramUI.dp(this, v);
    }

    private LinearLayout.LayoutParams lp(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        p.bottomMargin = dp(bottom);
        return p;
    }

    private void build() {
        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(CigramPlusUI.PAGE_BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                return insets;
            }
        });

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), dp(10), dp(16), dp(32));
        scroll.addView(column, new ViewGroup.LayoutParams(-1, -2));

        buildHeader();
        buildHero();
        planHolder = new LinearLayout(this);
        planHolder.setOrientation(LinearLayout.VERTICAL);
        column.addView(planHolder, lp(14, 0));
        buildCodeLink();
        buildBenefits();
        offerHolder = new LinearLayout(this);
        offerHolder.setOrientation(LinearLayout.VERTICAL);
        column.addView(offerHolder, lp(8, 0));
        paySection = buildPaySection();
        column.addView(paySection, lp(8, 0));
        if (!CigramPlus.salesEnabled()) paySection.setVisibility(View.GONE);       // Google Play build: no payment section
        buildTrust();
        setContentView(root);                 // <- this line was missing: without it the page was a blank window
        root.requestApplyInsets();
    }

    private void buildHeader() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = CigramUI.label(this, "→", 24f, 0xFFFFFFFF, true);
        back.setGravity(Gravity.CENTER);
        back.setMinWidth(dp(48));
        back.setMinHeight(dp(48));
        back.setContentDescription("رجوع");
        back.setClickable(true);
        CigramNav.bind(this, back);
        head.addView(back);
        TextView title = CigramUI.label(this, "Cigram Plus", 20f, CigramPlusUI.GOLD, true);
        title.setPadding(dp(6), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(head);
    }

    private void buildHero() {
        FrameLayout hero = new FrameLayout(this);
        hero.setBackground(CigramPlusArt.hero(this, 26));
        View glow = new View(this);
        glow.setBackground(CigramPlusArt.glow(this, 26));
        hero.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout in = new LinearLayout(this);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        in.setPadding(dp(20), dp(22), dp(20), dp(20));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView crown = new ImageView(this);
        crown.setImageDrawable(new CigramPlusUI.CrownDrawable(CigramPlusUI.GOLD));
        top.addView(crown, new LinearLayout.LayoutParams(dp(34), dp(28)));
        TextView plus = CigramUI.label(this, "PLUS", 13f, CigramPlusUI.GOLD_INK, true);
        plus.setGravity(Gravity.CENTER);
        plus.setPadding(dp(12), dp(3), dp(12), dp(3));
        plus.setBackground(CigramPlusUI.goldBg(this, 10));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
        pl.setMarginStart(dp(10));
        top.addView(plus, pl);
        in.addView(top);

        TextView h1 = CigramUI.label(this, "شاهد بلا حدود", 28f, 0xFFFFFFFF, true);
        in.addView(h1, lp(14, 0));
        TextView h2 = CigramUI.label(this, "بدون إعلانات · جودة حتى 4K · صورة داخل صورة · قنوات HD و4K", 13.5f, 0xCCFFFFFF, false);
        h2.setLineSpacing(0f, 1.25f);
        in.addView(h2, lp(6, 0));

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(heroChip("4K"));
        chips.addView(heroChip("PiP"));
        chips.addView(heroChip("HD"));
        chips.addView(heroChip("بلا إعلانات"));
        in.addView(chips, lp(14, 0));
        hero.addView(in, new FrameLayout.LayoutParams(-1, -2));
        column.addView(hero, lp(4, 0));
    }

    private TextView heroChip(String t) {
        TextView c = CigramUI.label(this, t, 12f, CigramPlusUI.GOLD_LIGHT, true);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(11), dp(5), dp(11), dp(5));
        c.setBackground(CigramPlusArt.ring(this, 14, 0x22F2B63D, 0x66F2B63D, 1f));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.setMarginEnd(dp(8));
        c.setLayoutParams(p);
        return c;
    }

    /** The code itself is entered on its own page ("ضع كود التفعيل"): here only a clear way to it. */
    private void buildCodeLink() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(CigramPlusArt.surface(this, 20));
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        TextView t = CigramUI.label(this, "لديك كود تفعيل؟ ضعه هنا", 15f, 0xFFFFFFFF, true);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView go = CigramUI.label(this, "تفعيل ‹", 13f, CigramPlusUI.GOLD, true);
        row.addView(go);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { CigramPlusUI.openPage(CigramPlusActivity.this, "code"); }
        });
        column.addView(row, lp(20, 0));
    }

    private void buildBenefits() {
        column.addView(sectionTitle("ماذا تحصل عليه مع Plus"), lp(26, 10));
        String[][] b = {
                {"بدون إعلانات", "مشاهدة نظيفة بلا مقاطعة"},
                {"جودة حتى 4K", "1080p وحتى 4K للأفلام والمسلسلات"},
                {"صورة داخل صورة", "شاهد وتصفّح في نفس الوقت"},
                {"قنوات HD و4K", "beIN SPORTS وغيرها بأعلى دقة"},
                {"أولوية في الدعم", "نرد على رسالتك أولاً"},
                {"أولوية في الطلبات", "طلبك لفيلم أو مسلسل يُنظر فيه أولاً"},
                {"موقّت النوم", "يغلق المشغّل وحده بعد المدة التي تختارها"},
                {"بطاقة العضوية", "بطاقتك بغلافك الشخصي ومدة اشتراكك"},
        };
        int[] icons = {CigramPlusArt.NO_ADS, CigramPlusArt.QUALITY, CigramPlusArt.PIP, CigramPlusArt.TV, CigramPlusArt.SUPPORT, CigramPlusArt.BOLT,
                CigramPlusArt.PIP, CigramPlusArt.QUALITY};
        for (int r = 0; r < b.length / 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            for (int k = 0; k < 2; k++) {
                int i = r * 2 + k;
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackground(CigramPlusArt.surface(this, 20));
                card.setPadding(dp(14), dp(14), dp(14), dp(14));
                ImageView ic = new ImageView(this);
                ic.setImageDrawable(new CigramPlusArt.Icon(icons[i], CigramPlusUI.GOLD));
                int pad = dp(10);
                ic.setPadding(pad, pad, pad, pad);
                ic.setBackground(CigramPlusArt.ring(this, 16, 0x26F2B63D, 0, 0));
                card.addView(ic, new LinearLayout.LayoutParams(dp(46), dp(46)));
                TextView t = CigramUI.label(this, b[i][0], 14.5f, 0xFFFFFFFF, true);
                card.addView(t, lp(10, 0));
                TextView s = CigramUI.label(this, b[i][1], 11.5f, 0xFF8E8E94, false);
                s.setLineSpacing(0f, 1.2f);
                card.addView(s, lp(3, 0));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                cp.setMargins(k == 0 ? 0 : dp(5), 0, k == 0 ? dp(5) : 0, 0);
                row.addView(card, cp);
            }
            column.addView(row, lp(0, 10));
        }
    }

    // ------------------------------------------------------------------ price + payment

    /** The price card: the first-subscription offer, or the plain 6-month price once the offer was used. */
    private void renderOffer() {
        offerHolder.removeAllViews();
        if (!CigramPlus.salesEnabled()) return;
        boolean intro = CigramPlus.introEligible(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(CigramPlusUI.goldBg(this, 24));
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        if (intro) {
            TextView ribbon = CigramUI.label(this, "عرض أول اشتراك", 12f, CigramPlusUI.GOLD_LIGHT, true);
            ribbon.setGravity(Gravity.CENTER);
            ribbon.setPadding(dp(12), dp(4), dp(12), dp(4));
            ribbon.setBackground(CigramPlusArt.ring(this, 12, CigramPlusUI.GOLD_INK, 0, 0));
            card.addView(ribbon, new LinearLayout.LayoutParams(-2, -2));
        }
        TextView price = CigramUI.label(this, CigramPlus.iqd(intro ? CigramPlus.INTRO_PRICE_IQD : CigramPlus.STANDARD_PRICE_IQD), 34f, CigramPlusUI.GOLD_INK, true);
        card.addView(price, lp(intro ? 8 : 0, 0));
        TextView per = CigramUI.label(this, intro ? "لمدة " + CigramPlus.INTRO_MONTHS + " أشهر" : "لمدة " + CigramPlus.STANDARD_MONTHS + " أشهر", 15f, 0xCC2B1B00, true);
        card.addView(per, lp(0, 0));
        TextView then = CigramUI.label(this, intro ? "ثم " + CigramPlus.iqd(CigramPlus.STANDARD_PRICE_IQD) + " كل " + CigramPlus.STANDARD_MONTHS + " أشهر عند التجديد" : "التجديد بكود جديد، لا خصم تلقائي",
                12.5f, 0xB32B1B00, false);
        card.addView(then, lp(6, 0));
        offerHolder.addView(card, lp(0, 0));
    }

    private View buildPaySection() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        s.setBackground(CigramPlusArt.surface(this, 24));
        s.setPadding(dp(16), dp(16), dp(16), dp(16));

        // "طرق الدفع ✓ :" (green) then the three logos side by side
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView methods = CigramUI.label(this, "طرق الدفع ✓ :", 14.5f, 0xFF3DDC97, true);
        line.addView(methods, new LinearLayout.LayoutParams(-2, -2));
        methodsRow = new LinearLayout(this);
        methodsRow.setOrientation(LinearLayout.HORIZONTAL);
        methodsRow.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < CigramPlus.METHOD_KEYS.length; i++) methodsRow.addView(methodChip(i));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1f);
        mp.setMarginStart(dp(12));
        line.addView(methodsRow, mp);
        s.addView(line);

        TextView hint = CigramUI.label(this, "اختر طريقة الدفع لتظهر لك التفاصيل", 12f, 0xFF8E8E94, false);
        s.addView(hint, lp(10, 0));

        methodDetail = new LinearLayout(this);
        methodDetail.setOrientation(LinearLayout.VERTICAL);
        methodDetail.setVisibility(View.GONE);
        s.addView(methodDetail, lp(12, 0));

        // the yellow button: opens WhatsApp with the chosen method in the message
        LinearLayout go = new LinearLayout(this);
        go.setOrientation(LinearLayout.VERTICAL);
        go.setGravity(Gravity.CENTER);
        go.setMinimumHeight(dp(60));
        go.setBackground(CigramPlusUI.pill(this, CigramPlusUI.WA_YELLOW, 30));
        go.setElevation(dp(3));
        go.setClickable(true);
        go.setFocusable(true);
        go.setContentDescription("تفعيل واستلام الكود عبر واتساب");
        waLabel = CigramUI.label(this, "تفعيل واستلام الكود", 17f, 0xFFFFFFFF, true);
        waLabel.setGravity(Gravity.CENTER);
        waLabel.setShadowLayer(dp(2), 0f, dp(1), 0x66000000);        // white on yellow: keep it legible
        go.addView(waLabel);
        waSub = CigramUI.label(this, "", 11.5f, 0xFFFFFFFF, false);
        waSub.setGravity(Gravity.CENTER);
        waSub.setShadowLayer(dp(2), 0f, dp(1), 0x66000000);
        waSub.setVisibility(View.GONE);
        go.addView(waSub);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { contactOnWhatsApp(); }
        });
        s.addView(go, lp(16, 0));
        return s;
    }

    /** One payment logo on a small white chip. Selected = gold ring + green check. */
    private View methodChip(final int index) {
        FrameLayout chip = new FrameLayout(this);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setContentDescription(CigramPlus.METHOD_LABELS[index]);
        int res = getResources().getIdentifier("pay_" + CigramPlus.METHOD_KEYS[index], "drawable", getPackageName());
        if (res != 0) {
            ImageView logo = new ImageView(this);
            logo.setImageResource(res);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            logo.setPadding(dp(5), dp(4), dp(5), dp(4));
            chip.addView(logo, new FrameLayout.LayoutParams(-1, -1));
        } else {
            TextView t = CigramUI.label(this, CigramPlus.METHOD_LABELS[index], 10f, 0xFF1A1A1A, true);
            t.setGravity(Gravity.CENTER);
            chip.addView(t, new FrameLayout.LayoutParams(-1, -1));
        }
        TextView check = CigramUI.label(this, "✓", 10f, 0xFFFFFFFF, true);
        check.setGravity(Gravity.CENTER);
        check.setBackground(CigramPlusArt.ring(this, 10, 0xFF22B573, 0, 0));
        check.setContentDescription("check");
        check.setVisibility(View.GONE);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP | Gravity.RIGHT);
        cl.setMargins(0, -dp(5), -dp(5), 0);
        chip.addView(check, cl);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { choose(index); }
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1f);
        p.setMargins(dp(4), 0, dp(4), 0);
        chip.setLayoutParams(p);
        paintChip(chip, false);
        return chip;
    }

    private void paintChip(View chip, boolean on) {
        chip.setBackground(CigramPlusArt.ring(this, 12, 0xFFFFFFFF, on ? CigramPlusUI.GOLD : 0, 2.5f));
        chip.setAlpha(on || selected[0] < 0 ? 1f : 0.62f);
        ViewGroup g = (ViewGroup) chip;
        View check = g.getChildAt(g.getChildCount() - 1);        // the check badge is the last child
        check.setVisibility(on ? View.VISIBLE : View.GONE);
        chip.setScaleX(on ? 1.07f : 1f);
        chip.setScaleY(on ? 1.07f : 1f);
    }

    private void choose(int index) {
        selected[0] = index;
        for (int i = 0; i < methodsRow.getChildCount(); i++) paintChip(methodsRow.getChildAt(i), i == index);
        renderMethodDetail(index);
        waSub.setText("عبر " + CigramPlus.METHOD_LABELS[index]);
        waSub.setVisibility(View.VISIBLE);
    }

    /** Everything about the chosen method: what to pay and what happens next. */
    private void renderMethodDetail(int index) {
        methodDetail.removeAllViews();
        methodDetail.setVisibility(View.VISIBLE);
        String name = CigramPlus.METHOD_LABELS[index];
        boolean intro = CigramPlus.introEligible(this);
        int amount = intro ? CigramPlus.INTRO_PRICE_IQD : CigramPlus.STANDARD_PRICE_IQD;
        int months = intro ? CigramPlus.INTRO_MONTHS : CigramPlus.STANDARD_MONTHS;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(CigramPlusArt.ring(this, 18, 0x1AF2B63D, 0x55F2B63D, 1f));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = CigramUI.label(this, "الدفع عبر " + name, 15.5f, 0xFFFFFFFF, true);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView amountTv = CigramUI.label(this, CigramPlus.iqd(amount) + " · " + months + " أشهر", 12.5f, CigramPlusUI.GOLD_INK, true);
        amountTv.setPadding(dp(10), dp(4), dp(10), dp(4));
        amountTv.setBackground(CigramPlusUI.goldBg(this, 10));
        head.addView(amountTv);
        card.addView(head);

        String[] steps;
        if (index == 1) {
            steps = new String[]{"اضغط «تفعيل واستلام الكود» لتفتح محادثتنا على واتساب برسالة جاهزة",
                    "نرسل لك رقم الاستلام الخاص بزين كاش",
                    "حوّل المبلغ وأرسل لنا صورة التحويل",
                    "يصلك كود التفعيل هنا لتضعه في الأعلى"};
        } else if (index == 2) {
            steps = new String[]{"اضغط «تفعيل واستلام الكود» لتفتح محادثتنا على واتساب برسالة جاهزة",
                    "نرسل لك طريقة الدفع الخاصة بأسيا سيل",
                    "ادفع المبلغ وأرسل لنا ما يثبت العملية",
                    "يصلك كود التفعيل هنا لتضعه في الأعلى"};
        } else {
            steps = new String[]{"اضغط «تفعيل واستلام الكود» لتفتح محادثتنا على واتساب برسالة جاهزة",
                    "نرسل لك طريقة الدفع بالبطاقة (ماستر كارد)",
                    "الدفع يتم خارج التطبيق، ولا تُدخل بيانات بطاقتك فيه",
                    "يصلك كود التفعيل هنا لتضعه في الأعلى"};
        }
        for (int i = 0; i < steps.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.TOP);
            TextView n = CigramUI.label(this, String.valueOf(i + 1), 11.5f, CigramPlusUI.GOLD_INK, true);
            n.setGravity(Gravity.CENTER);
            n.setBackground(CigramPlusArt.ring(this, 11, CigramPlusUI.GOLD, 0, 0));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(dp(22), dp(22));
            np.setMarginEnd(dp(10));
            row.addView(n, np);
            TextView t = CigramUI.label(this, steps[i], 12.8f, 0xFFD6E2E8, false);
            t.setLineSpacing(0f, 1.2f);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            card.addView(row, lp(i == 0 ? 12 : 8, 0));
        }
        methodDetail.addView(card);
    }

    private void buildTrust() {
        column.addView(sectionTitle("ليش تثق بنا"), lp(24, 10));
        String[] items = {"الدفع يتم عبر محادثة واتساب مباشرة معنا", "لا نطلب بيانات بطاقتك داخل التطبيق", "الكود يعمل في التطبيق والموقع على حسابك", "ينتهي في موعده تلقائياً، ولا يوجد خصم تلقائي"};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(CigramPlusArt.surface(this, 22));
        box.setPadding(dp(16), dp(10), dp(16), dp(14));
        for (String it : items) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView ck = CigramUI.label(this, "✓", 11f, 0xFFFFFFFF, true);
            ck.setGravity(Gravity.CENTER);
            ck.setBackground(CigramPlusArt.ring(this, 10, 0xFF22B573, 0, 0));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(20), dp(20));
            cp.setMarginEnd(dp(10));
            row.addView(ck, cp);
            row.addView(CigramUI.label(this, it, 13f, 0xFFD6E2E8, false), new LinearLayout.LayoutParams(0, -2, 1f));
            box.addView(row, lp(8, 0));
        }
        column.addView(box);
    }

    private TextView sectionTitle(String s) {
        return CigramUI.label(this, s, 17f, 0xFFFFFFFF, true);
    }

    // ------------------------------------------------------------------ state

    /** Cheap: rebuilds only the plan card, the offer and the two cards. */
    private void renderState() {
        planHolder.removeAllViews();
        boolean plus = CigramPlus.isPlus(this);
        // the membership card: the account and the period live HERE, inside the Cigram Plus page
        if (CigramUserData.isLoggedIn(this)) {
            View card = CigramPlusCard.build(this);
            planHolder.addView(card, new LinearLayout.LayoutParams(-1, -2));
            if (card instanceof FrameLayout) CigramNewBadge.attachCorner((FrameLayout) card, "plus_card_2026");
        }
        if (plus) planHolder.addView(sleepRow(), lp(12, 0));
        renderOffer();
        if (methodDetail != null && selected[0] >= 0) renderMethodDetail(selected[0]);     // the amount changes after the offer is used
    }

    /** «موقّت النوم»: the Plus service that closes the player after the chosen time. */
    private View sleepRow() {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackground(CigramPlusArt.surface(this, 20));
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(CigramUI.label(this, "موقّت النوم", 15f, 0xFFFFFFFF, true));
        final TextView sub = CigramUI.label(this, CigramSleepTimer.label(this), 12f, 0xFF8E8E94, false);
        texts.addView(sub, lp(3, 0));
        row.addView(new AdsIcons.Icon(this, AdsIcons.CLOCK, 0xFFFFFFFF), new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart(dp(12));
        row.addView(texts, tl);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CigramSleepTimer.choose(CigramPlusActivity.this, new Runnable() {
                    @Override public void run() { sub.setText(CigramSleepTimer.label(CigramPlusActivity.this)); }
                });
            }
        });
        CigramNewBadge.attach(row, "plus_sleep_timer_2026");
        return row;
    }

    private void scrollToPay() {
        if (paySection.getVisibility() != View.VISIBLE) return;
        scroll.post(new Runnable() {
            @Override public void run() { scroll.smoothScrollTo(0, Math.max(0, offerHolder.getTop() - dp(12))); }
        });
    }

    // ------------------------------------------------------------------ actions

    private void contactOnWhatsApp() {
        if (selected[0] < 0) {
            android.widget.Toast.makeText(this, "اختر طريقة الدفع أولاً (ماستر كارد / زين كاش / أسيا سيل)", android.widget.Toast.LENGTH_SHORT).show();
            methodsRow.animate().translationX(dp(8)).setDuration(60L).withEndAction(new Runnable() {
                @Override public void run() { methodsRow.animate().translationX(0f).setDuration(120L).start(); }
            }).start();
            return;
        }
        CigramPlus.openWhatsApp(this, CigramPlus.METHOD_LABELS[selected[0]]);      // the message names the chosen method
    }
}
