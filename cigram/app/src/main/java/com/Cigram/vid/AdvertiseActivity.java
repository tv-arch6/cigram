package com.Cigram.vid;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/**
 * صفحة «أعلن هنا»: تمرير عمودي واحد.
 * هيرو ← المساحات ← حاسبة التكلفة ← المقبول/المرفوض ← السياسات ← الأسئلة الشائعة ← التواصل.
 * كل الأرقام والنصوص والأسعار تأتي من الباك إند (GET /ads/config) وتُخزَّن محلياً للعمل دون اتصال.
 * الواجهة مبنية بالكود (لا Layout XML) فلا تحتاج أي موارد إضافية.
 */
public class AdvertiseActivity extends Activity {

    private static final String[] SLOT_ORDER = {"splash", "hero", "inline", "popup", "sticky", "sponsor"};

    private FrameLayout shell;
    private LinearLayout page;
    private ScrollView scroll;
    private LinearLayout stateHost;
    private AdsUi.Reveal reveal;
    private JSONObject cfg;
    private boolean cfgFromCache;
    private boolean destroyed;

    // شريط التواصل السفلي
    private LinearLayout ctaBar;
    private LinearLayout ctaButton;
    private TextView ctaTitle;
    private TextView ctaSub;
    private boolean reachedEnd;
    private boolean consentChecked;
    private boolean consentStoredAtStart;
    private int policiesVersion = 1;
    private View consentSection;
    private AdsIconBox consentBox;

    // حالة الحاسبة
    private String selSlot = "hero";
    private int selDays = 7;
    private boolean customDays;
    private final HashSet<String> selCities = new HashSet<String>();
    private final HashSet<String> selLangs = new HashSet<String>();
    private boolean hoursOn;
    private int hFrom = 18;
    private int hTo = 23;
    private boolean addExclusive;
    private boolean addVideo;
    private boolean addDesign;
    private double lastTotal = 0;

    // مراجع واجهة الحاسبة
    private View slotsAnchor;
    private final ArrayList<TextView> slotChips = new ArrayList<TextView>();
    private final ArrayList<String> slotChipKeys = new ArrayList<String>();
    private final ArrayList<TextView> durationChips = new ArrayList<TextView>();
    private final ArrayList<Integer> durationValues = new ArrayList<Integer>();
    private TextView customChip;
    private EditText customInput;
    private View customRow;
    private TextView slotDescView;
    private LinearLayout priceCard;
    private TextView hoursFromBtn;
    private TextView hoursToBtn;
    private View hoursRow;
    private View calcAnchor;

    // ------------------------------------------------------------------ دورة الحياة

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            getWindow().setStatusBarColor(CigramGlass.bg(this));
            getWindow().setNavigationBarColor(CigramGlass.bg(this));
        } catch (Throwable ignored) { }
        buildShell();
        showSkeleton();
        loadConfig(false);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshTopBar();
    }

    // ------------------------------------------------------------------ الهيكل: شريط علوي + تمرير + شريط التواصل

    private LinearLayout topBar;
    private View myAdsBtn;

    private void buildShell() {
        shell = new FrameLayout(this);
        shell.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        shell.setBackgroundColor(CigramGlass.bg(this));

        LinearLayout col = AdsUi.vbox(this);
        shell.addView(col, new FrameLayout.LayoutParams(-1, -1));

        topBar = AdsUi.hbox(this);
        topBar.setPadding(AdsUi.dp(this, 8), AdsUi.dp(this, 6), AdsUi.dp(this, 8), AdsUi.dp(this, 6));
        FrameLayout back = iconButton(AdsIcons.BACK, "رجوع", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        topBar.addView(back, new LinearLayout.LayoutParams(AdsUi.dp(this, 48), AdsUi.dp(this, 48)));
        TextView title = AdsUi.text(this, "أعلن هنا", 20f, CigramGlass.text(this), true);
        topBar.addView(title, AdsUi.lp(0, -2, AdsUi.dp(this, 4), 0, 0, 0));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).weight = 1f;
        myAdsBtn = iconButton(AdsIcons.CHART, "إعلاناتي", new View.OnClickListener() {
            @Override public void onClick(View v) { launch("com.Cigram.vid.MyAdsActivity", null); }
        });
        myAdsBtn.setVisibility(View.GONE);
        topBar.addView(myAdsBtn, new LinearLayout.LayoutParams(AdsUi.dp(this, 48), AdsUi.dp(this, 48)));
        col.addView(topBar, new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        page = AdsUi.vbox(this);
        page.setPadding(AdsUi.dp(this, 16), AdsUi.dp(this, 8), AdsUi.dp(this, 16), AdsUi.dp(this, 24));
        scroll.addView(page, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0);
        sl.weight = 1f;
        col.addView(scroll, sl);

        reveal = new AdsUi.Reveal(scroll);
        scroll.getViewTreeObserver().addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
            @Override public void onScrollChanged() { checkReachedEnd(); }
        });

        buildCtaBar();
        col.addView(ctaBar, new LinearLayout.LayoutParams(-1, -2));
        ctaBar.setVisibility(View.GONE);

        setContentView(shell);
    }

    private FrameLayout iconButton(int icon, String desc, View.OnClickListener click) {
        FrameLayout f = new FrameLayout(this);
        f.setClickable(true);
        f.setFocusable(true);
        f.setContentDescription(desc);
        f.setBackground(CigramGlass.ripple(this, new GradientDrawable(), 24));
        f.addView(new AdsIcons.Icon(this, icon, CigramGlass.text(this)), new FrameLayout.LayoutParams(AdsUi.dp(this, 24), AdsUi.dp(this, 24), Gravity.CENTER));
        f.setOnClickListener(click);
        return f;
    }

    private void refreshTopBar() {
        if (myAdsBtn != null) myAdsBtn.setVisibility(CigramAds.isLoggedIn(this) ? View.VISIBLE : View.GONE);
    }

    private void buildCtaBar() {
        ctaBar = AdsUi.vbox(this);
        ctaBar.setPadding(AdsUi.dp(this, 16), AdsUi.dp(this, 10), AdsUi.dp(this, 16), AdsUi.dp(this, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CigramGlass.card(this));
        bg.setStroke(AdsUi.dp(this, 1), CigramGlass.stroke(this));
        ctaBar.setBackground(bg);
        ctaButton = AdsUi.vbox(this);
        ctaButton.setGravity(Gravity.CENTER);
        ctaButton.setClickable(true);
        ctaButton.setFocusable(true);
        ctaButton.setMinimumHeight(AdsUi.dp(this, 56));
        ctaTitle = AdsUi.text(this, "تواصل معنا", 17f, 0xFF111113, true);
        ctaTitle.setGravity(Gravity.CENTER);
        ctaSub = AdsUi.text(this, "اقرأ السياسات أولاً", 12f, 0xFFB8C4CA, false);
        ctaSub.setGravity(Gravity.CENTER);
        ctaButton.addView(ctaTitle, new LinearLayout.LayoutParams(-2, -2));
        ctaButton.addView(ctaSub, AdsUi.lp(-2, -2, 0, AdsUi.dp(this, 2), 0, 0));
        ctaBar.addView(ctaButton, new LinearLayout.LayoutParams(-1, -2));
        ctaButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onCtaClick(); }
        });
        CigramGlass.press(ctaButton);
        updateCta();
    }

    private boolean ctaReady() {
        return consentChecked && (reachedEnd || consentStoredAtStart);
    }

    private void updateCta() {
        boolean ready = ctaReady();
        if (ready) {
            ctaButton.setBackground(CigramGlass.ripple(this, CigramUI.round(CigramUI.TEAL, 16, this), 16));
            ctaTitle.setTextColor(0xFF111113);
            ctaSub.setVisibility(View.GONE);
        } else {
            ctaButton.setBackground(CigramUI.round(0xFF3A4E58, 16, this));
            ctaTitle.setTextColor(0xFFD3DCE0);
            ctaSub.setVisibility(View.VISIBLE);
            ctaSub.setText(!reachedEnd && !consentChecked ? "اقرأ السياسات أولاً" : (!consentChecked ? "وافق على السياسات لتفعيل الزر" : "مرّر إلى نهاية الصفحة أولاً"));
        }
        ctaButton.setContentDescription(ready ? "تواصل معنا" : "تواصل معنا، معطّل. " + ctaSub.getText());
    }

    private void checkReachedEnd() {
        if (reachedEnd || page == null || scroll == null) return;
        int bottom = scroll.getScrollY() + scroll.getHeight();
        if (page.getHeight() > 0 && bottom >= page.getHeight() - AdsUi.dp(this, 120)) {
            reachedEnd = true;
            updateCta();
        }
    }

    // ------------------------------------------------------------------ تحميل الإعدادات + الحالات

    private void loadConfig(boolean force) {
        CigramAds.loadConfig(this, force, new CigramAds.Callback() {
            @Override public void onResult(CigramAds.Result r) {
                if (destroyed) return;
                if (r.ok && r.json.optJSONObject("settings") != null) {
                    cfg = r.json.optJSONObject("settings");
                    cfgFromCache = r.cached;
                    boolean wasOffline = r.cached && r.offline;
                    buildPage(wasOffline);
                } else {
                    showError(r.message.length() > 0 ? r.message : "تعذر تحميل البيانات.");
                }
            }
        });
    }

    private void clearPage() {
        page.removeAllViews();
        stateHost = null;
    }

    private void showSkeleton() {
        clearPage();
        ctaBar.setVisibility(View.GONE);
        int[] heights = {220, 36, 250, 36, 320, 120, 120};
        for (int h : heights) {
            View v = new View(this);
            CigramSkeleton.apply(v, 22);
            page.addView(v, AdsUi.lp(-1, AdsUi.dp(this, h), 0, AdsUi.dp(this, 8), 0, AdsUi.dp(this, 8)));
        }
    }

    private void showError(String message) {
        clearPage();
        ctaBar.setVisibility(View.GONE);
        LinearLayout box = AdsUi.card(this, 26, 24);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(CigramUI.round(0x26FF5252, 22, this));
        badge.addView(new AdsIcons.Icon(this, AdsIcons.ALERT, CigramUI.DANGER), new FrameLayout.LayoutParams(AdsUi.dp(this, 32), AdsUi.dp(this, 32), Gravity.CENTER));
        box.addView(badge, new LinearLayout.LayoutParams(AdsUi.dp(this, 64), AdsUi.dp(this, 64)));
        TextView t = AdsUi.text(this, "تعذر فتح الصفحة", 20f, CigramGlass.text(this), true);
        t.setGravity(Gravity.CENTER);
        box.addView(t, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 14), 0, 0));
        TextView m = AdsUi.text(this, message, 14.5f, CigramGlass.muted(this), false);
        m.setGravity(Gravity.CENTER);
        m.setLineSpacing(0f, 1.2f);
        box.addView(m, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 8), 0, AdsUi.dp(this, 18)));
        TextView retry = AdsUi.button(this, "إعادة المحاولة", CigramUI.TEAL, 0xFF111113);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSkeleton();
                loadConfig(true);
            }
        });
        box.addView(retry, new LinearLayout.LayoutParams(-1, -2));
        page.addView(box, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 40), 0, 0));
    }

    // ------------------------------------------------------------------ بناء الصفحة

    private void buildPage(boolean offlineBanner) {
        clearPage();
        slotChips.clear();
        slotChipKeys.clear();
        durationChips.clear();
        durationValues.clear();
        policiesVersion = Math.max(1, cfg.optInt("policies_version", 1));
        consentChecked = CigramAds.hasLocalConsent(this, policiesVersion);
        consentStoredAtStart = consentChecked;
        reachedEnd = false;
        // اختيار مساحة افتراضية مفعّلة
        JSONObject slots = cfg.optJSONObject("slots");
        if (slots == null || slots.optJSONObject(selSlot) == null || !slots.optJSONObject(selSlot).optBoolean("enabled", true)) {
            for (String k : SLOT_ORDER) {
                JSONObject s = slots == null ? null : slots.optJSONObject(k);
                if (s != null && s.optBoolean("enabled", true)) { selSlot = k; break; }
            }
        }
        JSONArray durs = cfg.optJSONArray("durations");
        if (durs != null && durs.length() > 0 && !customDays) {
            boolean found = false;
            for (int i = 0; i < durs.length(); i++) if (durs.optInt(i) == selDays) found = true;
            if (!found) selDays = durs.optInt(Math.min(2, durs.length() - 1), 7);
        }

        if (offlineBanner || cfgFromCache) addOfflineBanner(offlineBanner);
        addSection(buildHero());
        addSection(buildSteps());
        slotsAnchor = buildSlots();
        addSection(slotsAnchor);
        calcAnchor = buildCalculator();
        addSection(calcAnchor);
        addSection(buildRules());
        addSection(buildPolicies());
        addSection(buildFaq());
        addSection(buildMyRequestsEntry());
        consentSection = buildContact();
        addSection(consentSection);
        ctaBar.setVisibility(View.VISIBLE);
        updateCta();
        updateCalc(false);
        scroll.post(new Runnable() {
            @Override public void run() {
                reveal.check();
                // صفحة قصيرة جداً (لا تحتاج تمريراً)
                if (page.getHeight() <= scroll.getHeight()) { reachedEnd = true; updateCta(); }
            }
        });
    }

    private void addSection(View v) {
        page.addView(v, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 10), 0, AdsUi.dp(this, 10)));
        reveal.add(v);
    }

    private void addOfflineBanner(boolean offline) {
        LinearLayout row = AdsUi.hbox(this);
        row.setPadding(AdsUi.dp(this, 14), AdsUi.dp(this, 10), AdsUi.dp(this, 14), AdsUi.dp(this, 10));
        row.setBackground(CigramUI.round(0x33E9A23B, 0x66E9A23B, 14, this));
        row.addView(new AdsIcons.Icon(this, AdsIcons.INFO, CigramUI.AMBER), new LinearLayout.LayoutParams(AdsUi.dp(this, 20), AdsUi.dp(this, 20)));
        TextView t = AdsUi.text(this, offline ? "لا يوجد اتصال، نعرض آخر نسخة محفوظة من الأسعار والسياسات." : "نعرض نسخة محفوظة، وسنحدّثها عند توفر الاتصال.", 13f, CigramGlass.text(this), false);
        row.addView(t, AdsUi.lp(0, -2, 0, 0, AdsUi.dp(this, 10), 0));
        ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1f;
        TextView retry = AdsUi.text(this, "تحديث", 13f, CigramUI.AMBER, true);
        retry.setPadding(AdsUi.dp(this, 10), AdsUi.dp(this, 8), AdsUi.dp(this, 4), AdsUi.dp(this, 8));
        retry.setClickable(true);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSkeleton();
                loadConfig(true);
            }
        });
        row.addView(retry);
        page.addView(row, AdsUi.lp(-1, -2, 0, 0, 0, AdsUi.dp(this, 6)));
    }

    // ---------------------------------------------------------------- 1) الغلاف

    private View buildHero() {
        // v4.3: ترويسة مختصرة. لا أرقام مستخدمين/مشاهدات في الواجهة (كانت تأتي من إعدادات الأدمن ولا يوجد ما يثبتها).
        JSONObject hero = cfg.optJSONObject("hero");
        String sub = hero == null ? "" : hero.optString("subtitle", "");
        if (sub.length() == 0) sub = "قدّم طلب إعلانك داخل التطبيق، وتابع حالته مع الإدارة خطوة بخطوة.";
        LinearLayout box = AdsUi.vbox(this);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box.setPadding(AdsUi.dp(this, 20), AdsUi.dp(this, 18), AdsUi.dp(this, 20), AdsUi.dp(this, 18));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0F4A5C, 0xFF1B1B1F});
        g.setCornerRadius(AdsUi.dp(this, 22));
        g.setStroke(AdsUi.dp(this, 1), 0x3326A5B5);
        box.setBackground(g);

        LinearLayout head = AdsUi.hbox(this);
        head.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        head.addView(new AdsIcons.Icon(this, AdsIcons.MEGAPHONE, CigramUI.TEAL), new LinearLayout.LayoutParams(AdsUi.dp(this, 26), AdsUi.dp(this, 26)));
        TextView h = AdsUi.text(this, "أعلن على سيكرام", 24f, 0xFFFFFFFF, true);
        head.addView(h, AdsUi.lp(0, -2, 0, 0, AdsUi.dp(this, 10), 0));
        ((LinearLayout.LayoutParams) h.getLayoutParams()).weight = 1f;
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView st = AdsUi.text(this, sub, 14.5f, 0xFFB9CDD5, false);
        st.setLineSpacing(0f, 1.25f);
        box.addView(st, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 8), 0, 0));
        return box;
    }

    /** ثلاث خطوات مرئية: اختر المكان ← حدّد الحملة ← أرسل للمراجعة (كل بطاقة تنقلك للقسم المقابل). */
    private View buildSteps() {
        LinearLayout row = AdsUi.hbox(this);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        String[] titles = {"اختر المكان", "حدّد الحملة", "أرسل للمراجعة"};
        String[] descs = {"من أماكن الإعلان المتاحة", "المدة والاستهداف والسعر", "وتابع الحالة من «إعلاناتي»"};
        for (int i = 0; i < 3; i++) {
            LinearLayout c = AdsUi.vbox(this);
            c.setGravity(Gravity.CENTER_HORIZONTAL);
            c.setPadding(AdsUi.dp(this, 8), AdsUi.dp(this, 12), AdsUi.dp(this, 8), AdsUi.dp(this, 12));
            c.setBackground(CigramUI.round(0xFF1B1B1F, 0x22FFFFFF, 16, this));
            TextView n = AdsUi.text(this, String.valueOf(i + 1), 15f, 0xFF111113, true);
            n.setGravity(Gravity.CENTER);
            n.setBackground(CigramUI.round(CigramUI.TEAL, 20, this));
            c.addView(n, new LinearLayout.LayoutParams(AdsUi.dp(this, 28), AdsUi.dp(this, 28)));
            TextView t = AdsUi.text(this, titles[i], 13.5f, 0xFFFFFFFF, true);
            t.setGravity(Gravity.CENTER);
            c.addView(t, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 8), 0, 0));
            TextView d = AdsUi.text(this, descs[i], 11f, 0xFFA3A3A8, false);
            d.setGravity(Gravity.CENTER);
            c.addView(d, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 2), 0, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i < 2) lp.setMarginStart(AdsUi.dp(this, 8));
            final int step = i;
            c.setClickable(true);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    View target = step == 0 ? slotsAnchor : calcAnchor;
                    if (target != null) AdsUi.scrollTo(scroll, target);
                }
            });
            row.addView(c, lp);
        }
        return row;
    }

    /** اختصار واضح إلى طلبات المستخدم وحالتها الحقيقية (من الخادم، عبر شاشة «إعلاناتي»). */
    private View buildMyRequestsEntry() {
        LinearLayout row = AdsUi.hbox(this);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AdsUi.dp(this, 16), AdsUi.dp(this, 14), AdsUi.dp(this, 16), AdsUi.dp(this, 14));
        row.setBackground(CigramUI.round(0xFF1B1B1F, 0x33E9A23B, 16, this));
        TextView t = AdsUi.text(this, "طلباتي وحالتها", 15f, 0xFFFFFFFF, true);
        row.addView(t, AdsUi.lp(0, -2, 0, 0, 0, 0));
        ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1f;
        row.addView(AdsUi.text(this, "عرض ‹", 13f, CigramUI.AMBER, true));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { launch("com.Cigram.vid.MyAdsActivity", null); }
        });
        return row;
    }

    private LinearLayout.LayoutParams tileLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.setMarginEnd(AdsUi.dp(this, 8));
        return lp;
    }

    private View statTile(int icon, long value, String label) {
        LinearLayout t = AdsUi.vbox(this);
        t.setPadding(AdsUi.dp(this, 14), AdsUi.dp(this, 14), AdsUi.dp(this, 14), AdsUi.dp(this, 14));
        t.setBackground(CigramUI.round(0x33000000, 0x22FFFFFF, 18, this));
        t.addView(new AdsIcons.Icon(this, icon, CigramUI.TEAL), new LinearLayout.LayoutParams(AdsUi.dp(this, 20), AdsUi.dp(this, 20)));
        TextView n = AdsUi.text(this, "0", 24f, 0xFFFFFFFF, true);
        n.setTextDirection(View.TEXT_DIRECTION_LTR);
        n.setGravity(Gravity.RIGHT);
        t.addView(n, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 8), 0, 0));
        AdsUi.countUp(n, value, new AdsUi.Fmt() {
            @Override public String format(double v) { return CigramAds.count(Math.round(v)); }
        }, 1100L);
        t.addView(AdsUi.text(this, label, 12f, 0xFFA3A3A8, false), AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 2), 0, 0));
        t.setContentDescription(label + ": " + CigramAds.count(value));
        return t;
    }

    // ---------------------------------------------------------------- 2) أماكن الإعلان

    private View buildSlots() {
        LinearLayout box = AdsUi.vbox(this);
        box.addView(AdsUi.sectionHeader(this, AdsIcons.MEGAPHONE, CigramUI.TEAL, "أماكن الإعلان", "اختر المساحة الأنسب لهدفك"));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        hs.setClipToPadding(false);
        LinearLayout row = AdsUi.hbox(this);
        row.setGravity(Gravity.TOP);
        JSONObject slots = cfg.optJSONObject("slots");
        for (final String key : SLOT_ORDER) {
            final JSONObject s = slots == null ? null : slots.optJSONObject(key);
            if (s == null || !s.optBoolean("enabled", true)) continue;
            LinearLayout card = AdsUi.card(this, 24, 14);
            SlotMockView mock = new SlotMockView(this);
            mock.setSlot(key);
            mock.setContentDescription("محاكاة شكل مساحة " + s.optString("label"));
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(AdsUi.dp(this, 96), -2);
            mp.gravity = Gravity.CENTER_HORIZONTAL;
            card.addView(mock, mp);
            card.addView(AdsUi.text(this, s.optString("label"), 16f, CigramGlass.text(this), true), AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 12), 0, 0));
            TextView d = AdsUi.text(this, s.optString("desc"), 13f, CigramGlass.muted(this), false);
            d.setLineSpacing(0f, 1.2f);
            card.addView(d, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 6), 0, 0));
            LinearLayout suited = AdsUi.hbox(this);
            suited.setPadding(AdsUi.dp(this, 10), AdsUi.dp(this, 6), AdsUi.dp(this, 10), AdsUi.dp(this, 6));
            suited.setBackground(CigramUI.round(0x2226A5B5, 12, this));
            suited.addView(new AdsIcons.Icon(this, AdsIcons.STAR, CigramUI.TEAL), new LinearLayout.LayoutParams(AdsUi.dp(this, 14), AdsUi.dp(this, 14)));
            TextView st = AdsUi.text(this, s.optString("suited"), 12f, CigramGlass.text(this), false);
            suited.addView(st, AdsUi.lp(0, -2, 0, 0, AdsUi.dp(this, 6), 0));
            ((LinearLayout.LayoutParams) st.getLayoutParams()).weight = 1f;
            card.addView(suited, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 10), 0, 0));
            TextView price = AdsUi.text(this, "يبدأ من " + CigramAds.money(cfg, s.optDouble("price_per_day", 0)) + " / يوم", 14f, CigramUI.GREEN, true);
            card.addView(price, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 12), 0, 0));
            TextView pick = AdsUi.button(this, "اختر هذه المساحة", CigramGlass.soft(this), CigramGlass.text(this));
            pick.setTextSize(14f);
            pick.setMinHeight(AdsUi.dp(this, 48));
            pick.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selSlot = key;
                    refreshSlotChips();
                    updateCalc(true);
                    AdsUi.scrollTo(scroll, calcAnchor);
                }
            });
            card.addView(pick, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 12), 0, 0));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(AdsUi.dp(this, 236), -2);
            cp.setMarginEnd(AdsUi.dp(this, 10));
            row.addView(card, cp);
        }
        if (row.getChildCount() == 0) {
            LinearLayout empty = AdsUi.card(this, 22, 18);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.addView(new AdsIcons.Icon(this, AdsIcons.INFO, CigramGlass.muted(this)), new LinearLayout.LayoutParams(AdsUi.dp(this, 32), AdsUi.dp(this, 32)));
            TextView et = AdsUi.text(this, "لا توجد مساحات إعلانية متاحة حالياً. تواصل معنا وسنوفّر لك الخيار الأنسب.", 14f, CigramGlass.muted(this), false);
            et.setGravity(Gravity.CENTER);
            empty.addView(et, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 10), 0, 0));
            box.addView(empty, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 14), 0, 0));
            return box;
        }
        hs.addView(row, new ViewGroup.LayoutParams(-2, -2));
        box.addView(hs, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 14), 0, 0));
        return box;
    }

    // ---------------------------------------------------------------- 3) حاسبة التكلفة

    private View buildCalculator() {
        LinearLayout box = AdsUi.vbox(this);
        box.addView(AdsUi.sectionHeader(this, AdsIcons.CALC, CigramUI.AMBER, "احسب تكلفة إعلانك", "السعر يتغير فوراً مع كل اختيار"));
        LinearLayout card = AdsUi.card(this, 26, 16);

        // المساحة
        card.addView(label("المساحة الإعلانية"));
        AdsUi.Flow slotFlow = new AdsUi.Flow(this, 8, 8);
        JSONObject slots = cfg.optJSONObject("slots");
        for (final String key : SLOT_ORDER) {
            JSONObject s = slots == null ? null : slots.optJSONObject(key);
            if (s == null || !s.optBoolean("enabled", true)) continue;
            String name = s.optString("label");
            int paren = name.indexOf(" (");
            if (paren > 0) name = name.substring(0, paren);
            TextView c = AdsUi.chip(this, name, key.equals(selSlot), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selSlot = key;
                    refreshSlotChips();
                    updateCalc(true);
                }
            });
            slotChips.add(c);
            slotChipKeys.add(key);
            slotFlow.addView(c);
        }
        card.addView(slotFlow, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 8), 0, 0));
        slotDescView = AdsUi.text(this, "", 13f, CigramGlass.muted(this), false);
        slotDescView.setLineSpacing(0f, 1.2f);
        card.addView(slotDescView, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 10), 0, 0));

        // المدة
        card.addView(label("مدة الحملة"), AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 20), 0, 0));
        AdsUi.Flow durFlow = new AdsUi.Flow(this, 8, 8);
        JSONArray durs = cfg.optJSONArray("durations");
        if (durs != null) {
            for (int i = 0; i < durs.length(); i++) {
                final int dd = durs.optInt(i);
                if (dd <= 0) continue;
                TextView c = AdsUi.chip(this, CigramAds.durationLabel(dd), !customDays && dd == selDays, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        customDays = false;
                        selDays = dd;
                        refreshDurationChips();
                        updateCalc(true);
                    }
                });
                durationChips.add(c);
                durationValues.add(dd);
                durFlow.addView(c);
            }
        }
        customChip = AdsUi.chip(this, "مخصص", customDays, new View.OnClickListener() {
            @Override public void onClick(View v) {
                customDays = true;
                refreshDurationChips();
                customRow.setVisibility(View.VISIBLE);
                customInput.requestFocus();
                updateCalc(true);
            }
        });
        durFlow.addView(customChip);
        card.addView(durFlow, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 8), 0, 0));

        customRow = buildCustomDays();
        customRow.setVisibility(customDays ? View.VISIBLE : View.GONE);
        card.addView(customRow, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 10), 0, 0));

        // الاستهداف
        card.addView(label("الاستهداف (اختياري)"), AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 22), 0, 0));
        card.addView(small("المدن / المحافظات — بدون اختيار = كل المدن"), AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 2), 0, 0));
        AdsUi.Flow cityFlow = new AdsUi.Flow(this, 8, 8);
        JSONObject t = cfg.optJSONObject("targeting");
        JSONArray cities = t == null ? null : t.optJSONArray("cities");
        if (cities != null) {
            for (int i = 0; i < cities.length(); i++) {
                JSONObject c = cities.optJSONObject(i);
                if (c == null) continue;
                final String id = c.optString("id");
                TextView chip = AdsUi.chip(this, c.optString("ar"), selCities.contains(id), null);
                chip.setOnClickListener(multiToggle(selCities, id, chip));
                cityFlow.addView(chip);
            }
        }
        card.addView(cityFlow, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 8), 0, 0));
        card.addView(small("لغة المستخدم — بدون اختيار = كل اللغات"), AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 14), 0, 0));
        AdsUi.Flow langFlow = new AdsUi.Flow(this, 8, 8);
        JSONArray langs = t == null ? null : t.optJSONArray("langs");
        if (langs != null) {
            for (int i = 0; i < langs.length(); i++) {
                JSONObject l = langs.optJSONObject(i);
                if (l == null) continue;
                final String id = l.optString("id");
                TextView chip = AdsUi.chip(this, l.optString("label"), selLangs.contains(id), null);
                chip.setOnClickListener(multiToggle(selLangs, id, chip));
                langFlow.addView(chip);
            }
        }
        card.addView(langFlow, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 8), 0, 0));

        // ساعات الظهور
        LinearLayout hoursHead = switchRow("ساعات ظهور محددة", "يظهر إعلانك في ساعات معينة من اليوم فقط", hoursOn, new AdsUi.OnToggle() {
            @Override public void onChanged(boolean on) {
                hoursOn = on;
                hoursRow.setVisibility(on ? View.VISIBLE : View.GONE);
                updateCalc(true);
            }
        });
        card.addView(hoursHead, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 14), 0, 0));
        hoursRow = buildHoursRow();
        hoursRow.setVisibility(hoursOn ? View.VISIBLE : View.GONE);
        card.addView(hoursRow, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 6), 0, 0));

        // الإضافات
        card.addView(label("إضافات"), AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 22), 0, 0));
        JSONObject ad = cfg.optJSONObject("addons");
        if (ad != null) {
            card.addView(addonRow(ad, "exclusive"), AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 6), 0, 0));
            card.addView(addonRow(ad, "video"), AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 2), 0, 0));
            card.addView(addonRow(ad, "design"), AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 2), 0, 0));
        }
        box.addView(card, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 14), 0, 0));

        // بطاقة السعر
        priceCard = AdsUi.vbox(this);
        priceCard.setPadding(AdsUi.dp(this, 18), AdsUi.dp(this, 18), AdsUi.dp(this, 18), AdsUi.dp(this, 18));
        GradientDrawable pg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0F3B4A, 0xFF1B1B1F});
        pg.setCornerRadius(AdsUi.dp(this, 26));
        pg.setStroke(AdsUi.dp(this, 1), 0x6626A5B5);
        priceCard.setBackground(pg);
        box.addView(priceCard, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 12), 0, 0));

        TextView send = AdsUi.button(this, "أرسل هذا الطلب", CigramUI.TEAL, 0xFF111113);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendOrder(); }
        });
        box.addView(send, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 12), 0, 0));
        return box;
    }

    private View buildCustomDays() {
        LinearLayout row = AdsUi.hbox(this);
        JSONObject cd = cfg.optJSONObject("custom_days");
        final int min = cd == null ? 1 : cd.optInt("min", 1);
        final int max = cd == null ? 730 : cd.optInt("max", 730);
        row.addView(AdsUi.text(this, "عدد الأيام", 14f, CigramGlass.text(this), false));
        customInput = new EditText(this);
        CigramUI.styleInput(customInput, "مثال: 45");
        customInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        customInput.setGravity(Gravity.CENTER);
        customInput.setSingleLine(true);
        customInput.setMinHeight(AdsUi.dp(this, 48));
        customInput.setText(String.valueOf(selDays));
        customInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                int v = 0;
                try { v = Integer.parseInt(e.toString().trim()); } catch (Throwable ignored) { }
                if (v < min) v = min;
                if (v > max) v = max;
                if (e.length() > 0) {
                    selDays = v;
                    updateCalc(true);
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(AdsUi.dp(this, 120), -2);
        lp.setMarginStart(AdsUi.dp(this, 12));
        row.addView(customInput, lp);
        row.addView(AdsUi.text(this, "(من " + min + " إلى " + max + ")", 12f, CigramGlass.muted(this), false), AdsUi.lp(-2, -2, 0, 0, AdsUi.dp(this, 10), 0));
        return row;
    }

    private View buildHoursRow() {
        LinearLayout row = AdsUi.hbox(this);
        hoursFromBtn = AdsUi.chip(this, "", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickHour("يبدأ الظهور من الساعة", hFrom, new HourPicked() {
                    @Override public void picked(int h) { hFrom = h; updateHoursLabels(); updateCalc(true); }
                });
            }
        });
        hoursToBtn = AdsUi.chip(this, "", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickHour("ينتهي الظهور عند الساعة", hTo, new HourPicked() {
                    @Override public void picked(int h) { hTo = h; updateHoursLabels(); updateCalc(true); }
                });
            }
        });
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -2, 1f);
        a.setMarginEnd(AdsUi.dp(this, 8));
        row.addView(hoursFromBtn, a);
        row.addView(hoursToBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        updateHoursLabels();
        return row;
    }

    private interface HourPicked {
        void picked(int hour);
    }

    private static String hh(int h) {
        return String.format(Locale.US, "%02d:00", h);
    }

    private void updateHoursLabels() {
        if (hoursFromBtn != null) hoursFromBtn.setText("من " + hh(hFrom));
        if (hoursToBtn != null) hoursToBtn.setText("إلى " + hh(hTo));
    }

    private void pickHour(String title, int current, final HourPicked cb) {
        final CigramUI.Sheet sheet = CigramUI.sheet(this).icon(CigramUI.ICON_INFO, CigramUI.TEAL).title(title);
        AdsUi.Flow flow = new AdsUi.Flow(this, 8, 8);
        for (int i = 0; i < 24; i++) {
            final int h = i;
            TextView c = AdsUi.chip(this, hh(i), i == current, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    cb.picked(h);
                    sheet.dismiss();
                }
            });
            flow.addView(c);
        }
        sheet.view(flow);
        sheet.secondary("إغلاق", null);
        sheet.show();
    }

    private View.OnClickListener multiToggle(final HashSet<String> set, final String id, final TextView chip) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (set.contains(id)) set.remove(id); else set.add(id);
                AdsUi.styleChip(chip, set.contains(id));
                updateCalc(true);
            }
        };
    }

    private LinearLayout switchRow(String title, String sub, boolean on, AdsUi.OnToggle l) {
        LinearLayout row = AdsUi.hbox(this);
        row.setMinimumHeight(AdsUi.dp(this, 56));
        LinearLayout col = AdsUi.vbox(this);
        col.addView(AdsUi.text(this, title, 15f, CigramGlass.text(this), true));
        if (sub != null && sub.length() > 0) {
            TextView s = AdsUi.text(this, sub, 12.5f, CigramGlass.muted(this), false);
            s.setLineSpacing(0f, 1.15f);
            col.addView(s, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 2), 0, 0));
        }
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        AdsUi.Toggle tg = new AdsUi.Toggle(this, on);
        tg.setListener(l);
        tg.setContentDescription(title);
        row.addView(tg, AdsUi.lp(-2, -2, AdsUi.dp(this, 8), 0, 0, 0));
        return row;
    }

    private View addonRow(JSONObject addons, final String key) {
        JSONObject a = addons.optJSONObject(key);
        if (a == null) return new View(this);
        String price = "pct".equals(a.optString("type")) ? "+" + (int) a.optDouble("value", 0) + "%" : "+" + CigramAds.money(cfg, a.optDouble("value", 0));
        boolean on = "exclusive".equals(key) ? addExclusive : ("video".equals(key) ? addVideo : addDesign);
        return switchRow(a.optString("label", key) + "  (" + price + ")", a.optString("desc"), on, new AdsUi.OnToggle() {
            @Override public void onChanged(boolean v) {
                if ("exclusive".equals(key)) addExclusive = v; else if ("video".equals(key)) addVideo = v; else addDesign = v;
                updateCalc(true);
            }
        });
    }

    private TextView label(String s) {
        return AdsUi.text(this, s, 16f, CigramGlass.text(this), true);
    }

    private TextView small(String s) {
        return AdsUi.text(this, s, 12.5f, CigramGlass.muted(this), false);
    }

    private void refreshSlotChips() {
        for (int i = 0; i < slotChips.size(); i++) AdsUi.styleChip(slotChips.get(i), slotChipKeys.get(i).equals(selSlot));
    }

    private void refreshDurationChips() {
        for (int i = 0; i < durationChips.size(); i++) AdsUi.styleChip(durationChips.get(i), !customDays && durationValues.get(i) == selDays);
        if (customChip != null) AdsUi.styleChip(customChip, customDays);
        if (customRow != null) customRow.setVisibility(customDays ? View.VISIBLE : View.GONE);
    }

    private CigramAds.Quote currentQuote() {
        return CigramAds.quote(cfg, selSlot, selDays, selCities.size(), selLangs.size(), hoursOn, addExclusive, addVideo, addDesign);
    }

    /** يعيد رسم بطاقة السعر والوصف. */
    private void updateCalc(boolean animate) {
        if (cfg == null || priceCard == null) return;
        JSONObject s = cfg.optJSONObject("slots") == null ? null : cfg.optJSONObject("slots").optJSONObject(selSlot);
        if (slotDescView != null && s != null) slotDescView.setText(s.optString("desc"));
        refreshDurationChips();
        CigramAds.Quote q = currentQuote();
        priceCard.removeAllViews();
        if (!q.valid) {
            priceCard.addView(AdsUi.text(this, "هذه المساحة غير متاحة حالياً.", 15f, 0xFFFFFFFF, false));
            return;
        }
        priceCard.addView(AdsUi.text(this, "تفصيل التكلفة", 15f, 0xFFA3A3A8, true));
        addPriceLine("السعر الأساسي (" + CigramAds.durationLabel(q.days) + ")", CigramAds.money(cfg, q.base), 0xFFFFFFFF);
        if (q.discount > 0) addPriceLine("خصم المدة الطويلة (" + (int) q.discountPct + "%)", "−" + CigramAds.money(cfg, q.discount), CigramUI.GREEN);
        for (CigramAds.Line l : q.targetingLines) addPriceLine(l.label, "+" + CigramAds.money(cfg, l.amount), 0xFFFFFFFF);
        for (CigramAds.Line l : q.addonLines) addPriceLine(l.label, "+" + CigramAds.money(cfg, l.amount), 0xFFFFFFFF);
        View div = new View(this);
        div.setBackgroundColor(0x33FFFFFF);
        priceCard.addView(div, AdsUi.lp(-1, 1, 0, AdsUi.dp(this, 14), 0, AdsUi.dp(this, 12)));
        LinearLayout tot = AdsUi.hbox(this);
        tot.addView(AdsUi.text(this, "الإجمالي", 17f, 0xFFFFFFFF, true), new LinearLayout.LayoutParams(0, -2, 1f));
        final TextView totalView = AdsUi.text(this, CigramAds.money(cfg, q.total), 30f, CigramUI.GREEN, true);
        totalView.setTextDirection(View.TEXT_DIRECTION_LTR);
        tot.addView(totalView);
        priceCard.addView(tot);
        if (animate && AdsUi.animationsOn(this) && Math.abs(q.total - lastTotal) > 0.001) {
            final double from = lastTotal, to = q.total;
            ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(320L);
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator an) {
                    float f = (Float) an.getAnimatedValue();
                    totalView.setText(CigramAds.money(cfg, from + (to - from) * f));
                }
            });
            va.start();
        }
        lastTotal = q.total;
        LinearLayout views = AdsUi.hbox(this);
        views.setPadding(AdsUi.dp(this, 12), AdsUi.dp(this, 10), AdsUi.dp(this, 12), AdsUi.dp(this, 10));
        views.setBackground(CigramUI.round(0x2226A5B5, 14, this));
        views.addView(new AdsIcons.Icon(this, AdsIcons.EYE, CigramUI.TEAL), new LinearLayout.LayoutParams(AdsUi.dp(this, 20), AdsUi.dp(this, 20)));
        TextView vt = AdsUi.text(this, "المشاهدات المتوقعة: " + CigramAds.count(q.viewsMin) + " – " + CigramAds.count(q.viewsMax), 13.5f, 0xFFFFFFFF, false);
        views.addView(vt, AdsUi.lp(0, -2, 0, 0, AdsUi.dp(this, 10), 0));
        ((LinearLayout.LayoutParams) vt.getLayoutParams()).weight = 1f;
        priceCard.addView(views, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 14), 0, 0));
        TextView note = AdsUi.text(this, "تقدير استرشادي مبني على أرقام التطبيق الفعلية. يُثبَّت السعر النهائي في عرض السعر داخل المحادثة.", 11.5f, 0xFF8E8E94, false);
        note.setLineSpacing(0f, 1.15f);
        priceCard.addView(note, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 10), 0, 0));
    }

    private void addPriceLine(String label, String value, int valueColor) {
        LinearLayout r = AdsUi.hbox(this);
        r.addView(AdsUi.text(this, label, 14f, 0xFFC9D6DC, false), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = AdsUi.text(this, value, 14.5f, valueColor, true);
        v.setTextDirection(View.TEXT_DIRECTION_LTR);
        r.addView(v);
        priceCard.addView(r, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 10), 0, 0));
    }

    // ---------------------------------------------------------------- 4) المقبول والمرفوض

    private View buildRules() {
        LinearLayout box = AdsUi.vbox(this);
        box.addView(AdsUi.sectionHeader(this, AdsIcons.SHIELD, CigramUI.GREEN, "الإعلان المناسب والمرفوض", "قواعد واضحة لحماية المستخدمين وعلامتك"));
        JSONObject rules = cfg.optJSONObject("rules");
        box.addView(rulesCard(rules == null ? null : rules.optJSONArray("accepted"), true), AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 14), 0, 0));
        box.addView(rulesCard(rules == null ? null : rules.optJSONArray("rejected"), false), AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 10), 0, 0));
        return box;
    }

    private View rulesCard(JSONArray items, boolean ok) {
        int accent = ok ? CigramUI.GREEN : CigramUI.DANGER;
        LinearLayout card = AdsUi.vbox(this);
        card.setPadding(AdsUi.dp(this, 16), AdsUi.dp(this, 16), AdsUi.dp(this, 16), AdsUi.dp(this, 8));
        GradientDrawable g = new GradientDrawable();
        g.setColor((accent & 0x00FFFFFF) | 0x14000000);
        g.setCornerRadius(AdsUi.dp(this, 24));
        g.setStroke(AdsUi.dp(this, 1), (accent & 0x00FFFFFF) | 0x55000000);
        card.setBackground(g);
        LinearLayout head = AdsUi.hbox(this);
        head.addView(new AdsIcons.Icon(this, ok ? AdsIcons.CHECK_CIRCLE : AdsIcons.CROSS_CIRCLE, accent), new LinearLayout.LayoutParams(AdsUi.dp(this, 26), AdsUi.dp(this, 26)));
        head.addView(AdsUi.text(this, ok ? "مقبول" : "مرفوض", 18f, accent, true), AdsUi.lp(-2, -2, 0, 0, AdsUi.dp(this, 10), 0));
        card.addView(head);
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                LinearLayout row = AdsUi.hbox(this);
                row.setGravity(Gravity.TOP);
                row.setPadding(0, AdsUi.dp(this, 12), 0, AdsUi.dp(this, 4));
                FrameLayout dot = new FrameLayout(this);
                dot.setBackground(CigramUI.round((accent & 0x00FFFFFF) | 0x26000000, 10, this));
                dot.addView(new AdsIcons.Icon(this, ok ? AdsIcons.CHECK : AdsIcons.CLOSE, accent), new FrameLayout.LayoutParams(AdsUi.dp(this, 14), AdsUi.dp(this, 14), Gravity.CENTER));
                row.addView(dot, new LinearLayout.LayoutParams(AdsUi.dp(this, 26), AdsUi.dp(this, 26)));
                LinearLayout col = AdsUi.vbox(this);
                col.addView(AdsUi.text(this, it.optString("title"), 15f, CigramGlass.text(this), true));
                String txt = it.optString("text");
                if (txt.length() > 0) {
                    TextView tv = AdsUi.text(this, txt, 13f, CigramGlass.muted(this), false);
                    tv.setLineSpacing(0f, 1.2f);
                    col.addView(tv, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 2), 0, 0));
                }
                row.addView(col, AdsUi.lp(0, -2, 0, 0, AdsUi.dp(this, 12), 0));
                ((LinearLayout.LayoutParams) col.getLayoutParams()).weight = 1f;
                card.addView(row);
            }
        }
        return card;
    }

    // ---------------------------------------------------------------- 5) السياسات و 6) الأسئلة الشائعة

    private View buildPolicies() {
        LinearLayout box = AdsUi.vbox(this);
        box.addView(AdsUi.sectionHeader(this, AdsIcons.FILE, CigramUI.TEAL, "السياسات", "اقرأها بعناية قبل التواصل"));
        JSONArray pol = cfg.optJSONArray("policies");
        int[] icons = {AdsIcons.MEGAPHONE, AdsIcons.LOCK, AdsIcons.FILE, AdsIcons.WALLET, AdsIcons.SHIELD};
        if (pol != null) {
            for (int i = 0; i < pol.length(); i++) {
                JSONObject p = pol.optJSONObject(i);
                if (p == null) continue;
                box.addView(accordion(icons[i % icons.length], p.optString("title"), p.optString("body")), AdsUi.lp(-1, -2, 0, AdsUi.dp(this, i == 0 ? 14 : 8), 0, 0));
            }
        }
        long at = cfg.optLong("policies_updated_at", 0);
        String when = at > 0 ? new java.text.SimpleDateFormat("yyyy/MM/dd", Locale.US).format(new java.util.Date(at)) : "";
        TextView ver = AdsUi.text(this, "نسخة السياسات " + policiesVersion + (when.length() > 0 ? " · آخر تحديث " + when : ""), 12f, CigramGlass.muted(this), false);
        box.addView(ver, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 10), 0, 0));
        return box;
    }

    private View buildFaq() {
        LinearLayout box = AdsUi.vbox(this);
        box.addView(AdsUi.sectionHeader(this, AdsIcons.HELP, CigramUI.AMBER, "أسئلة شائعة", null));
        JSONArray faq = cfg.optJSONArray("faq");
        if (faq != null) {
            for (int i = 0; i < faq.length(); i++) {
                JSONObject f = faq.optJSONObject(i);
                if (f == null) continue;
                box.addView(accordion(AdsIcons.HELP, f.optString("q"), f.optString("a")), AdsUi.lp(-1, -2, 0, AdsUi.dp(this, i == 0 ? 14 : 8), 0, 0));
            }
        }
        return box;
    }

    private View accordion(int icon, String title, String body) {
        final LinearLayout card = AdsUi.vbox(this);
        CigramGlass.bind(card, 20, true);
        LinearLayout head = AdsUi.hbox(this);
        head.setMinimumHeight(AdsUi.dp(this, 56));
        head.setPadding(AdsUi.dp(this, 14), 0, AdsUi.dp(this, 14), 0);
        head.setClickable(true);
        head.setFocusable(true);
        head.setBackground(CigramGlass.ripple(this, new GradientDrawable(), 20));
        head.addView(new AdsIcons.Icon(this, icon, CigramUI.TEAL), new LinearLayout.LayoutParams(AdsUi.dp(this, 22), AdsUi.dp(this, 22)));
        TextView t = AdsUi.text(this, title, 15.5f, CigramGlass.text(this), true);
        t.setPadding(0, AdsUi.dp(this, 12), 0, AdsUi.dp(this, 12));
        head.addView(t, AdsUi.lp(0, -2, 0, 0, AdsUi.dp(this, 12), 0));
        ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1f;
        final AdsIcons.Icon chev = new AdsIcons.Icon(this, AdsIcons.CHEVRON_DOWN, CigramGlass.muted(this));
        head.addView(chev, new LinearLayout.LayoutParams(AdsUi.dp(this, 22), AdsUi.dp(this, 22)));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));
        final TextView b = AdsUi.text(this, body, 14f, CigramGlass.muted(this), false);
        b.setLineSpacing(0f, 1.3f);
        b.setPadding(AdsUi.dp(this, 16), 0, AdsUi.dp(this, 16), AdsUi.dp(this, 16));
        b.setVisibility(View.GONE);
        card.addView(b, new LinearLayout.LayoutParams(-1, -2));
        head.setContentDescription(title);
        head.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { AdsUi.toggleExpand(b, chev); }
        });
        return card;
    }

    // ---------------------------------------------------------------- 7) التواصل

    private final class AdsIconBox {
        final FrameLayout view;
        final AdsIcons.Icon tick;

        AdsIconBox() {
            view = new FrameLayout(AdvertiseActivity.this);
            tick = new AdsIcons.Icon(AdvertiseActivity.this, AdsIcons.CHECK, 0xFF111113);
            view.addView(tick, new FrameLayout.LayoutParams(AdsUi.dp(AdvertiseActivity.this, 18), AdsUi.dp(AdvertiseActivity.this, 18), Gravity.CENTER));
            render(false);
        }

        void render(boolean on) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(AdsUi.dp(AdvertiseActivity.this, 8));
            g.setColor(on ? CigramUI.TEAL : 0x00000000);
            g.setStroke(AdsUi.dp(AdvertiseActivity.this, 2), on ? CigramUI.TEAL : CigramGlass.muted(AdvertiseActivity.this));
            view.setBackground(g);
            tick.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private View buildContact() {
        LinearLayout box = AdsUi.vbox(this);
        box.addView(AdsUi.sectionHeader(this, AdsIcons.CHAT, CigramUI.TEAL, "جاهز؟ تواصل معنا", "نرد عليك بعرض سعر واضح خلال وقت قصير"));
        LinearLayout card = AdsUi.card(this, 24, 16);
        TextView info = AdsUi.text(this, "للتواصل: مرّر حتى هذه النقطة، ثم وافق على السياسات والشروط ليتفعّل زر «تواصل معنا» أسفل الشاشة.", 13.5f, CigramGlass.muted(this), false);
        info.setLineSpacing(0f, 1.25f);
        card.addView(info);
        final LinearLayout row = AdsUi.hbox(this);
        row.setMinimumHeight(AdsUi.dp(this, 56));
        row.setClickable(true);
        row.setFocusable(true);
        row.setPadding(0, AdsUi.dp(this, 6), 0, AdsUi.dp(this, 6));
        consentBox = new AdsIconBox();
        consentBox.render(consentChecked);
        row.addView(consentBox.view, new LinearLayout.LayoutParams(AdsUi.dp(this, 28), AdsUi.dp(this, 28)));
        TextView t = AdsUi.text(this, "قرأتُ وأوافق على السياسات والشروط (النسخة " + policiesVersion + ")", 14.5f, CigramGlass.text(this), true);
        row.addView(t, AdsUi.lp(0, -2, 0, 0, AdsUi.dp(this, 12), 0));
        ((LinearLayout.LayoutParams) t.getLayoutParams()).weight = 1f;
        row.setContentDescription("قرأت وأوافق على السياسات والشروط");
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                checkReachedEnd();
                if (!consentChecked && !reachedEnd && !consentStoredAtStart) {
                    AdsUi.toast(AdvertiseActivity.this, "مرّر إلى نهاية الصفحة لقراءة السياسات أولاً.");
                    return;
                }
                consentChecked = !consentChecked;
                consentBox.render(consentChecked);
                if (consentChecked) {
                    CigramAds.saveLocalConsent(AdvertiseActivity.this, policiesVersion);
                    if (CigramAds.isLoggedIn(AdvertiseActivity.this)) CigramAds.pushConsent(AdvertiseActivity.this, policiesVersion, null);
                } else {
                    getSharedPreferences("cigram_ads_v1", MODE_PRIVATE).edit().remove("consent_version").apply();
                }
                updateCta();
            }
        });
        card.addView(row, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 14), 0, 0));
        box.addView(card, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 14), 0, AdsUi.dp(this, 60)));
        return box;
    }

    private void onCtaClick() {
        if (!ctaReady()) {
            AdsUi.toast(this, !consentChecked ? "اقرأ السياسات ثم وافق عليها أولاً." : "مرّر إلى نهاية الصفحة أولاً.");
            if (consentSection != null) AdsUi.scrollTo(scroll, consentSection);
            return;
        }
        if (!requireAccount()) return;
        showContactSheet(null);
    }

    /** المحادثة والتواصل مرتبطان بحساب (البريد الإلكتروني) — بدون حساب نعرض شاشة تسجيل الدخول. */
    private boolean requireAccount() {
        if (CigramAds.isLoggedIn(this)) return true;
        CigramUI.sheet(this).icon(CigramUI.ICON_USER, CigramUI.TEAL).title("سجّل الدخول أولاً")
                .message("التواصل مرتبط بحسابك (بريدك الإلكتروني) لنحفظ طلبك ونتابعه معك. سجّل الدخول ثم عد إلى هنا — اختياراتك ستبقى كما هي.")
                .primary("تسجيل الدخول", CigramUI.TEAL, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) {
                        openLogin(false);
                        return false;
                    }
                }).secondary("لاحقاً", null).show();
        return false;
    }

    /** يفتح صفحة الحساب (فيها نموذج الدخول). withReset: ينهي الجلسة المنتهية محلياً أولاً. */
    private void openLogin(boolean withReset) {
        try {
            if (withReset) {
                getSharedPreferences("auth_session", MODE_PRIVATE).edit().clear().apply();
            }
            Intent i = new Intent(this, Class.forName("com.Cigram.vid.AccountActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        } catch (Throwable e) {
            AdsUi.toast(this, "افتح صفحة الحساب وسجّل الدخول.");
        }
    }

    private boolean consentOkForOrder() {
        return consentChecked && CigramAds.hasLocalConsent(this, policiesVersion);
    }

    private void sendOrder() {
        CigramAds.Quote q = currentQuote();
        if (!q.valid) {
            AdsUi.toast(this, "اختر مساحة ومدة صحيحة أولاً.");
            return;
        }
        if (!consentOkForOrder()) {
            AdsUi.toast(this, "اقرأ السياسات ووافق عليها قبل إرسال الطلب.");
            if (consentSection != null) AdsUi.scrollTo(scroll, consentSection);
            return;
        }
        openChat(buildOrderJson(q));
    }

    private String buildOrderJson(CigramAds.Quote q) {
        try {
            JSONObject o = new JSONObject();
            o.put("slot", q.slot);
            JSONObject s = cfg.optJSONObject("slots") == null ? null : cfg.optJSONObject("slots").optJSONObject(q.slot);
            o.put("slot_label", s == null ? q.slot : s.optString("label"));
            o.put("days", q.days);
            JSONArray cities = new JSONArray();
            JSONArray cityNames = new JSONArray();
            JSONObject t = cfg.optJSONObject("targeting");
            JSONArray all = t == null ? null : t.optJSONArray("cities");
            for (String id : selCities) {
                cities.put(id);
                if (all != null) for (int i = 0; i < all.length(); i++) if (id.equals(all.optJSONObject(i).optString("id"))) cityNames.put(all.optJSONObject(i).optString("ar"));
            }
            o.put("cities", cities);
            o.put("city_names", cityNames);
            JSONArray langs = new JSONArray();
            for (String id : selLangs) langs.put(id);
            o.put("langs", langs);
            if (hoursOn) {
                JSONObject hrs = new JSONObject();
                hrs.put("from", hFrom);
                hrs.put("to", hTo);
                o.put("hours", hrs);
            }
            JSONObject addons = new JSONObject();
            addons.put("exclusive", addExclusive);
            addons.put("video", addVideo);
            addons.put("design", addDesign);
            o.put("addons", addons);
            o.put("total", q.total);
            o.put("base", q.base);
            o.put("discount", q.discount);
            o.put("currency", cfg.optJSONObject("currency") == null ? "USD" : cfg.optJSONObject("currency").optString("code", "USD"));
            o.put("views_min", q.viewsMin);
            o.put("views_max", q.viewsMax);
            return o.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private String orderSummaryText() {
        CigramAds.Quote q = currentQuote();
        JSONObject contact = cfg.optJSONObject("contact");
        StringBuilder sb = new StringBuilder();
        String base = contact == null ? "" : contact.optString("whatsapp_message", "");
        sb.append(base.length() > 0 ? base : "مرحباً، أرغب بالإعلان في التطبيق.");
        if (q.valid) {
            JSONObject s = cfg.optJSONObject("slots") == null ? null : cfg.optJSONObject("slots").optJSONObject(q.slot);
            sb.append("\n\nملخص الطلب:");
            sb.append("\n• المساحة: ").append(s == null ? q.slot : s.optString("label"));
            sb.append("\n• المدة: ").append(CigramAds.durationLabel(q.days));
            if (!selCities.isEmpty()) sb.append("\n• المدن: ").append(selCities.size());
            if (hoursOn) sb.append("\n• الساعات: ").append(hh(hFrom)).append(" - ").append(hh(hTo));
            if (addExclusive) sb.append("\n• حصرية المساحة");
            if (addVideo) sb.append("\n• فيديو");
            if (addDesign) sb.append("\n• تصميم من فريقكم");
            sb.append("\n• التكلفة التقديرية: ").append(CigramAds.money(cfg, q.total));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ ورقة التواصل (Bottom Sheet)

    private void showContactSheet(final String orderJson) {
        if (!AdsUi.alive(this)) return;
        final CigramUI.Sheet sheet = CigramUI.sheet(this).icon(CigramUI.ICON_MAIL, CigramUI.TEAL).title("كيف تحب أن نتواصل؟")
                .message("اختر الطريقة الأنسب لك.");
        final TextView[] statusRef = new TextView[1];
        sheet.view(contactOption(AdsIcons.CHAT, CigramUI.TEAL, "المحادثة داخل التطبيق", "اتصال مباشر مع فريق الإعلانات", "موصى بها", statusRef, new View.OnClickListener() {
            @Override public void onClick(View v) {
                sheet.dismiss();
                openChat(orderJson);
            }
        }));
        // واتساب وX (تويتر): غير متوفرين حالياً — يظهران معطّلين بعبارة «غير متوفر».
        sheet.view(unavailableOption(AdsIcons.WHATSAPP, CigramUI.GREEN, "واتساب"));
        sheet.view(unavailableOption(AdsIcons.X_LOGO, 0xFFFFFFFF, "حساب X (تويتر)"));
        sheet.secondary("إغلاق", null);
        sheet.show();
        loadPresence(statusRef[0]);
    }

    private View unavailableOption(int icon, int accent, String title) {
        View row = contactOption(icon, accent, title, "غير متوفر", null, null, new View.OnClickListener() {
            @Override public void onClick(View v) {
                AdsUi.toast(AdvertiseActivity.this, "هذه الوسيلة غير متوفرة حالياً.");
            }
        });
        row.setAlpha(0.5f);
        row.setContentDescription(title + "، غير متوفر");
        return row;
    }

    private View contactOption(int icon, int accent, String title, String sub, String badge, TextView[] statusOut, View.OnClickListener click) {
        LinearLayout row = AdsUi.hbox(this);
        row.setMinimumHeight(AdsUi.dp(this, 72));
        row.setPadding(AdsUi.dp(this, 14), AdsUi.dp(this, 10), AdsUi.dp(this, 14), AdsUi.dp(this, 10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(CigramGlass.ripple(this, CigramUI.round(CigramGlass.soft(this), badge != null ? accent : CigramGlass.stroke(this), 18, this), 18));
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(CigramUI.round((accent & 0x00FFFFFF) | 0x26000000, 14, this));
        ic.addView(new AdsIcons.Icon(this, icon, accent), new FrameLayout.LayoutParams(AdsUi.dp(this, 24), AdsUi.dp(this, 24), Gravity.CENTER));
        row.addView(ic, new LinearLayout.LayoutParams(AdsUi.dp(this, 48), AdsUi.dp(this, 48)));
        LinearLayout col = AdsUi.vbox(this);
        LinearLayout tl = AdsUi.hbox(this);
        tl.addView(AdsUi.text(this, title, 15.5f, CigramGlass.text(this), true));
        if (badge != null) {
            TextView bd = AdsUi.text(this, badge, 11f, 0xFF111113, true);
            bd.setPadding(AdsUi.dp(this, 8), AdsUi.dp(this, 3), AdsUi.dp(this, 8), AdsUi.dp(this, 3));
            bd.setBackground(CigramUI.round(accent, 8, this));
            tl.addView(bd, AdsUi.lp(-2, -2, 0, 0, AdsUi.dp(this, 8), 0));
        }
        col.addView(tl);
        TextView s = AdsUi.text(this, sub, 12.5f, CigramGlass.muted(this), false);
        col.addView(s, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 2), 0, 0));
        if (statusOut != null) {
            TextView st = AdsUi.text(this, "", 12f, CigramUI.GREEN, true);
            st.setVisibility(View.GONE);
            col.addView(st, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 3), 0, 0));
            statusOut[0] = st;
        }
        row.addView(col, AdsUi.lp(0, -2, 0, 0, AdsUi.dp(this, 12), 0));
        ((LinearLayout.LayoutParams) col.getLayoutParams()).weight = 1f;
        row.addView(new AdsIcons.Icon(this, AdsIcons.CHEVRON_LEFT, CigramGlass.muted(this)), new LinearLayout.LayoutParams(AdsUi.dp(this, 20), AdsUi.dp(this, 20)));
        row.setOnClickListener(click);
        CigramGlass.press(row);
        return row;
    }

    /** حالة اتصال الإدارة (متصل/غير متصل/خارج الدوام) — فشلها لا يمنع شيئاً. */
    private void loadPresence(final TextView out) {
        if (out == null) return;
        CigramAds.get(this, "/chat/presence", false, new CigramAds.Callback() {
            @Override public void onResult(CigramAds.Result r) {
                if (destroyed || !r.ok) return;
                String state = r.json.optString("state", "");
                if ("online".equals(state)) {
                    out.setText("● متصل الآن");
                    out.setTextColor(CigramUI.GREEN);
                } else if ("off_hours".equals(state)) {
                    out.setText("خارج ساعات العمل — سنرد عند عودتنا");
                    out.setTextColor(CigramUI.AMBER);
                } else if ("offline".equals(state)) {
                    out.setText("غير متصل حالياً — اترك رسالتك وسنرد");
                    out.setTextColor(CigramGlass.muted(AdvertiseActivity.this));
                } else return;
                out.setVisibility(View.VISIBLE);
            }
        });
    }

    // ------------------------------------------------------------------ فتح المحادثة

    private void openChat(final String orderJson) {
        if (!CigramAds.isLoggedIn(this)) {
            CigramUI.sheet(this).icon(CigramUI.ICON_USER, CigramUI.TEAL).title("سجّل الدخول أولاً")
                    .message("المحادثة مرتبطة بحسابك لنحفظ طلبك ونتابعه معك. سجّل الدخول من صفحة الحساب ثم عد إلى هنا — اختياراتك ستبقى كما هي.")
                    .primary("الذهاب لتسجيل الدخول", CigramUI.TEAL, new CigramUI.Click() {
                        @Override public boolean onClick(CigramUI.Sheet s) {
                            finish();
                            return false;
                        }
                    }).secondary("لاحقاً", null).show();
            return;
        }
        final CigramUI.Sheet wait = CigramUI.sheet(this).spinner("جارٍ تجهيز المحادثة...").cancelable(false);
        wait.show();
        CigramAds.pushConsent(this, policiesVersion, new CigramAds.Callback() {
            @Override public void onResult(CigramAds.Result r) {
                wait.dismiss();
                if (destroyed) return;
                if (r.ok) {
                    Bundle extras = new Bundle();
                    if (orderJson != null && orderJson.length() > 0) extras.putString("order_json", orderJson);
                    if (!launch("com.Cigram.vid.AdsChatActivity", extras)) {
                        AdsUi.toast(AdvertiseActivity.this, "شاشة المحادثة غير مفعّلة في هذه النسخة بعد.");
                    }
                } else if ("policies_changed".equals(r.error)) {
                    consentChecked = false;
                    CigramAds.saveLocalConsent(AdvertiseActivity.this, 0);
                    showSkeleton();
                    loadConfig(true);
                    AdsUi.toast(AdvertiseActivity.this, "تحدّثت السياسات. اقرأها ووافق عليها من جديد.");
                } else if (r.code == 401 || "login_required".equals(r.error) || "invalid_token".equals(r.error)) {
                    CigramUI.sheet(AdvertiseActivity.this).icon(CigramUI.ICON_LOCK, CigramUI.AMBER)
                            .title("انتهت جلسة الحساب")
                            .message("سجّل الدخول ببريدك الإلكتروني من جديد لنفتح لك المحادثة. اختياراتك في صفحة الإعلان محفوظة.")
                            .primary("تسجيل الدخول من جديد", CigramUI.AMBER, new CigramUI.Click() {
                                @Override public boolean onClick(CigramUI.Sheet s) {
                                    openLogin(true);
                                    return false;
                                }
                            }).secondary("لاحقاً", null).show();
                } else {
                    CigramUI.info(AdvertiseActivity.this, CigramUI.ICON_WARN, CigramUI.AMBER, "تعذر فتح المحادثة", r.message, "حسناً").show();
                }
            }
        });
    }

    /** يشغّل Activity بالاسم (حتى لا يتعطل الترجمة إذا لم تُضَف شاشة المحادثة بعد). */
    private boolean launch(String className, Bundle extras) {
        try {
            Intent i = new Intent(this, Class.forName(className));
            if (extras != null) i.putExtras(extras);
            startActivity(i);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
