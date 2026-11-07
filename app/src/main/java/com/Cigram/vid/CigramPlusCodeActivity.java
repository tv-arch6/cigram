package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "ضع كود التفعيل": the page is never empty.
 *   1. «قم بإدخال الكود المرسل :»  ->  the field «(اكتب الكود)»  ->  the button «تفعيل الخدمة»
 *   2. Below, ONLY for Plus members: the members' area (صالة الأعضاء):
 *        member card · newly added titles · continue watching · your active perks · priority support · priority requests · renewal.
 * Without Plus the area is just a short hint, so nothing of it shows before the activation.
 */
public class CigramPlusCodeActivity extends Activity {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);

    private ScrollView scroll;
    private LinearLayout column;
    private LinearLayout lounge;
    private EditText codeInput;
    private TextView codeMsg;
    private TextView activateBtn;
    private TextView idText;
    private boolean busy;

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
            renderLounge();
        } catch (Throwable t) {
            android.util.Log.e("CigramPlus", "code page failed to build", t);
            showFailure(t);
            return;
        }
        if (!CigramPlus.isPlus(this)) {
            codeInput.postDelayed(new Runnable() {
                @Override public void run() {
                    codeInput.requestFocus();
                    InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (im != null) im.showSoftInput(codeInput, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 300L);
        }
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
            tv.setPadding(dp(16), dp(40), dp(16), dp(16));
            tv.setText("تعذّر فتح الصفحة.\nصوّر هذه الشاشة وأرسلها للمطوّر:\n\n" + trace);
            sv.addView(tv);
            setContentView(sv);
        } catch (Throwable ignored) {
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (scroll == null) return;
        CigramPlus.refresh(this, true, new Runnable() {
            @Override public void run() { if (!isFinishing() && !isDestroyed()) renderLounge(); }
        });
        CigramPublicId.ensure(this, new Runnable() {
            @Override public void run() { if (!isFinishing() && idText != null) idText.setText(idOrDots()); }
        });
    }

    // ------------------------------------------------------------------ small helpers

    private int dp(float v) {
        return CigramUI.dp(this, v);
    }

    private LinearLayout.LayoutParams lp(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        p.bottomMargin = dp(bottom);
        return p;
    }

    private String idOrDots() {
        String id = CigramPublicId.get(this);
        return id.length() == 0 ? "C·····" : id;
    }

    private String accountName() {
        try {
            String n = getSharedPreferences("auth_session", Context.MODE_PRIVATE).getString("name", "");
            return n == null ? "" : n.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------ build (once): header + activation

    private void build() {
        final FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF0B2A36, CigramPlusUI.PAGE_BG, 0xFF04121A});
        root.setBackground(bg);
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
        column.setPadding(dp(16), dp(10), dp(16), dp(36));
        scroll.addView(column, new ViewGroup.LayoutParams(-1, -2));

        // header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = CigramUI.label(this, "→", 24f, 0xFFFFFFFF, true);
        back.setGravity(Gravity.CENTER);
        back.setMinWidth(dp(48));
        back.setMinHeight(dp(48));
        back.setContentDescription("رجوع");
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        head.addView(back);
        TextView title = CigramUI.label(this, "Cigram Plus", 20f, CigramPlusUI.GOLD, true);
        title.setPadding(dp(6), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        column.addView(head);

        // activation card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(CigramPlusArt.hero(this, 26));
        card.setPadding(dp(20), dp(22), dp(20), dp(20));
        card.setElevation(dp(4));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView crown = new ImageView(this);
        crown.setImageDrawable(new CigramPlusUI.CrownDrawable(CigramPlusUI.GOLD));
        top.addView(crown, new LinearLayout.LayoutParams(dp(30), dp(25)));
        TextView lab = CigramUI.label(this, "قم بإدخال الكود المرسل :", 17f, 0xFFFFFFFF, true);
        lab.setPadding(dp(12), 0, 0, 0);
        top.addView(lab, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(top);

        codeInput = new EditText(this);
        codeInput.setHint("(اكتب الكود)");
        codeInput.setHintTextColor(0xFF5E7681);
        codeInput.setTextColor(0xFFFFFFFF);
        codeInput.setTextSize(19f);
        codeInput.setSingleLine(true);
        codeInput.setGravity(Gravity.CENTER);
        codeInput.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        codeInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        codeInput.setLetterSpacing(0.1f);
        codeInput.setBackground(CigramPlusArt.ring(this, 18, 0xFF061820, 0x66F2B63D, 1.5f));
        codeInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        codeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        codeInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        codeInput.setMinHeight(dp(58));
        card.addView(codeInput, lp(16, 0));

        TextView paste = CigramUI.label(this, "لصق من الحافظة", 13f, CigramPlusUI.GOLD_LIGHT, true);
        paste.setGravity(Gravity.CENTER);
        paste.setMinHeight(dp(44));
        paste.setBackground(CigramPlusArt.ring(this, 14, 0x1AF2B63D, 0x55F2B63D, 1f));
        paste.setClickable(true);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pasteCode(); }
        });
        card.addView(paste, lp(10, 0));

        codeMsg = CigramUI.label(this, "", 12.5f, 0xFFFF6B6B, false);
        codeMsg.setVisibility(View.GONE);
        codeMsg.setGravity(Gravity.CENTER);
        card.addView(codeMsg, lp(8, 0));

        activateBtn = CigramUI.label(this, "تفعيل الخدمة", 17f, CigramPlusUI.GOLD_INK, true);
        activateBtn.setGravity(Gravity.CENTER);
        activateBtn.setMinHeight(dp(56));
        activateBtn.setBackground(CigramPlusUI.goldBg(this, 28));
        activateBtn.setClickable(true);
        activateBtn.setFocusable(true);
        activateBtn.setElevation(dp(3));
        activateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { activate(); }
        });
        card.addView(activateBtn, lp(14, 0));

        if (CigramPlus.salesEnabled()) {
            TextView get = CigramUI.label(this, "ليس لديك كود؟ اعرف كيف تحصل عليه ‹", 12.5f, CigramPlusUI.GOLD_LIGHT, false);
            get.setGravity(Gravity.CENTER);
            get.setMinHeight(dp(40));
            get.setClickable(true);
            get.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { CigramPlusUI.openPage(CigramPlusCodeActivity.this, "pay"); }
            });
            card.addView(get, lp(6, 0));
        }
        column.addView(card, lp(10, 0));

        lounge = new LinearLayout(this);
        lounge.setOrientation(LinearLayout.VERTICAL);
        column.addView(lounge, lp(0, 0));

        setContentView(root);                 // (the Plus pages must always set their content: a missing call = a blank window)
        root.requestApplyInsets();
    }

    // ------------------------------------------------------------------ the members' area (Plus only)

    private void renderLounge() {
        lounge.removeAllViews();
        if (!CigramPlus.isPlus(this)) {
            TextView hint = CigramUI.label(this, "بعد التفعيل تظهر هنا أقسام Plus الخاصة بك", 12.5f, 0xFF7C93A0, false);
            hint.setGravity(Gravity.CENTER);
            lounge.addView(hint, lp(22, 0));
            return;
        }
        lounge.addView(loungeTitle("صالتك"), lp(26, 10));
        lounge.addView(memberCard(), lp(0, 0));

        List<CigramWidgetData.Slide> fresh = CigramWidgetData.fromCatalog(catalogJson(), 10);
        if (!fresh.isEmpty()) {
            lounge.addView(loungeTitle("وصل حديثاً"), lp(26, 4));
            lounge.addView(loungeSub("أحدث الإضافات، اضغط لتفتح العنوان"), lp(0, 10));
            lounge.addView(posterRow(fresh, null), lp(0, 0));
        }
        List<JSONObject> history = continueList();
        if (!history.isEmpty()) {
            lounge.addView(loungeTitle("تابع مشاهدتك"), lp(26, 10));
            lounge.addView(posterRow(null, history), lp(0, 0));
        }

        lounge.addView(loungeTitle("مزاياك الفعّالة"), lp(26, 10));
        lounge.addView(perksGrid(), lp(0, 0));

        lounge.addView(loungeTitle("أولوية لك"), lp(26, 10));
        lounge.addView(actionCard("أولوية في الدعم", "راسلنا كعضو Plus فنردّ عليك أولاً", "تواصل الآن", 0xFFFFC107, new Runnable() {
            @Override public void run() { CigramPlus.openSupport(CigramPlusCodeActivity.this); }
        }), lp(0, 10));
        lounge.addView(actionCard("أولوية في الطلبات", "اطلب فيلماً أو مسلسلاً أو قناة وطلبك يُنظر فيه أولاً", "اطلب الآن", CigramPlusUI.GOLD, new Runnable() {
            @Override public void run() { openByName("CommunicationActivity"); }
        }), lp(0, 10));

        lounge.addView(renewalCard(), lp(8, 0));
    }

    private TextView loungeTitle(String s) {
        TextView t = CigramUI.label(this, s, 18f, 0xFFFFFFFF, true);
        t.setCompoundDrawablePadding(dp(8));
        return t;
    }

    private TextView loungeSub(String s) {
        return CigramUI.label(this, s, 12f, 0xFF8FA1AA, false);
    }

    private String catalogJson() {
        try {
            return getSharedPreferences("cigram_catalog_cache", Context.MODE_PRIVATE).getString("json", "");
        } catch (Throwable t) {
            return "";
        }
    }

    private List<JSONObject> continueList() {
        List<JSONObject> out = new ArrayList<JSONObject>();
        try {
            List<JSONObject> all = CigramUserData.historyGrouped(this);
            for (JSONObject e : all) {
                if (out.size() >= 10) break;
                if (e.optBoolean("finished", false)) continue;
                String img = e.optString("backdrop", "");
                if (!CigramWidgetData.isHttp(img)) img = e.optString("poster", "");
                if (CigramWidgetData.isHttp(img)) out.add(e);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private void openByName(String simpleName) {
        try {
            startActivity(new Intent(this, Class.forName(getPackageName() + "." + simpleName)));
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "تعذّر فتح الصفحة", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ---- member card

    private View memberCard() {
        FrameLayout card = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFFE7A0, 0xFFF2B63D, 0xFFB7770C});
        g.setCornerRadius(dp(24));
        card.setBackground(g);
        card.setElevation(dp(8));
        card.setMinimumHeight(dp(190));
        View sheen = new View(this);
        GradientDrawable sh = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0x66FFFFFF, 0x00FFFFFF, 0x22FFFFFF});
        sh.setCornerRadius(dp(24));
        sheen.setBackground(sh);
        card.addView(sheen, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout in = new LinearLayout(this);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        in.setPadding(dp(20), dp(18), dp(20), dp(18));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = CigramUI.label(this, "CIGRAM PLUS", 14f, CigramPlusUI.GOLD_INK, true);
        brand.setLetterSpacing(0.14f);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView crown = new ImageView(this);
        crown.setImageDrawable(new CigramPlusUI.CrownDrawable(CigramPlusUI.GOLD_INK));
        top.addView(crown, new LinearLayout.LayoutParams(dp(30), dp(25)));
        in.addView(top);

        View chip = new View(this);
        GradientDrawable cg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFFF3C4, 0xFFD9A441});
        cg.setCornerRadius(dp(7));
        cg.setStroke(Math.max(1, dp(1)), 0x66000000);
        chip.setBackground(cg);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(44), dp(32));
        cp.topMargin = dp(14);
        in.addView(chip, cp);

        idText = CigramUI.label(this, idOrDots(), 24f, CigramPlusUI.GOLD_INK, true);
        idText.setLetterSpacing(0.16f);
        idText.setTextDirection(View.TEXT_DIRECTION_LTR);
        idText.setGravity(Gravity.LEFT);
        in.addView(idText, lp(10, 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.BOTTOM);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        String name = accountName();
        left.addView(CigramUI.label(this, name.length() == 0 ? "عضو Plus" : name, 16f, CigramPlusUI.GOLD_INK, true));
        left.addView(CigramUI.label(this, "متبقٍّ " + CigramPlus.daysLeft(this) + " يوم", 12f, 0xCC2B1B00, false));
        bottom.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout valid = new LinearLayout(this);
        valid.setOrientation(LinearLayout.VERTICAL);
        valid.setGravity(Gravity.LEFT);
        TextView vt = CigramUI.label(this, "VALID THRU", 9f, 0xAA2B1B00, true);
        vt.setTextDirection(View.TEXT_DIRECTION_LTR);
        valid.addView(vt);
        TextView vd = CigramUI.label(this, CigramPlus.formatDate(CigramPlus.until(this)), 15f, CigramPlusUI.GOLD_INK, true);
        vd.setTextDirection(View.TEXT_DIRECTION_LTR);
        valid.addView(vd);
        bottom.addView(valid);
        in.addView(bottom, lp(12, 0));
        card.addView(in, new FrameLayout.LayoutParams(-1, -2));
        return card;
    }

    // ---- poster rows (new titles / continue watching)

    private View posterRow(List<CigramWidgetData.Slide> slides, List<JSONObject> entries) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        hs.addView(row, new ViewGroup.LayoutParams(-2, -2));
        int n = slides != null ? slides.size() : entries.size();
        for (int i = 0; i < n; i++) {
            final JSONObject item;
            String title, img, meta;
            if (slides != null) {
                CigramWidgetData.Slide s = slides.get(i);
                title = s.title;
                img = s.imageUrl;
                meta = s.meta;
                JSONObject d;
                try { d = new JSONObject(s.details); } catch (Throwable t) { d = new JSONObject(); }
                item = d;
            } else {
                JSONObject e = entries.get(i);
                item = e;
                boolean ep = "episode".equals(e.optString("type"));
                title = ep ? e.optString("series_title", e.optString("title")) : e.optString("title");
                img = e.optString("backdrop", "");
                if (!CigramWidgetData.isHttp(img)) img = e.optString("poster", "");
                meta = ep ? "الموسم " + e.optInt("season", 1) + " · الحلقة " + e.optInt("episode", 1) : "أكمل الفيلم";
            }
            row.addView(posterCard(title, meta, img, item), posterLp(i));
        }
        return hs;
    }

    private LinearLayout.LayoutParams posterLp(int i) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(210), dp(118));
        p.setMarginEnd(dp(10));
        return p;
    }

    private View posterCard(String title, String meta, String imageUrl, final JSONObject item) {
        final FrameLayout card = new FrameLayout(this);
        GradientDrawable base = new GradientDrawable();
        base.setColor(0xFF0E2A35);
        base.setCornerRadius(dp(18));
        card.setBackground(base);
        card.setElevation(dp(3));
        card.setClickable(true);
        card.setFocusable(true);
        final ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(img, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout shade = new LinearLayout(this);
        shade.setOrientation(LinearLayout.VERTICAL);
        shade.setGravity(Gravity.BOTTOM);
        shade.setPadding(dp(12), dp(28), dp(12), dp(10));
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xDD000000, 0x00000000}));
        TextView tt = CigramUI.label(this, title, 14f, 0xFFFFFFFF, true);
        tt.setSingleLine(true);
        tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        shade.addView(tt);
        if (meta != null && meta.length() > 0) shade.addView(CigramUI.label(this, meta, 11f, 0xCCFFFFFF, false));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        final Context app = getApplicationContext();
        final String url = imageUrl;
        POOL.execute(new Runnable() {
            @Override public void run() {
                final Bitmap b = CigramWidgetImages.card(app, url);
                if (b == null) return;
                img.post(new Runnable() {
                    @Override public void run() { if (!isFinishing()) img.setImageBitmap(b); }
                });
            }
        });
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { CigramStage1InitProvider.openTitleDetails(CigramPlusCodeActivity.this, item, card); }
                catch (Throwable t) { android.util.Log.w("CigramPlus", "open title failed", t); }
            }
        });
        return card;
    }

    // ---- perks, actions, renewal

    private View perksGrid() {
        String[][] perks = {
                {"الإعلانات", "متوقفة ✓", "ad"},
                {"الجودة", "حتى 4K ✓", "q"},
                {"صورة داخل صورة", "متاحة ✓", "pip"},
                {"قنوات HD و4K", "متاحة ✓", "tv"},
        };
        int[] icons = {CigramPlusArt.NO_ADS, CigramPlusArt.QUALITY, CigramPlusArt.PIP, CigramPlusArt.TV};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            for (int k = 0; k < 2; k++) {
                final int i = r * 2 + k;
                LinearLayout t = new LinearLayout(this);
                t.setOrientation(LinearLayout.HORIZONTAL);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setBackground(CigramPlusArt.surface(this, 18));
                t.setPadding(dp(12), dp(12), dp(12), dp(12));
                ImageView ic = new ImageView(this);
                ic.setImageDrawable(new CigramPlusArt.Icon(icons[i], CigramPlusUI.GOLD));
                ic.setPadding(dp(8), dp(8), dp(8), dp(8));
                ic.setBackground(CigramPlusArt.ring(this, 14, 0x26F2B63D, 0, 0));
                t.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));
                LinearLayout txt = new LinearLayout(this);
                txt.setOrientation(LinearLayout.VERTICAL);
                txt.addView(CigramUI.label(this, perks[i][0], 12.5f, 0xFFD6E2E8, true));
                txt.addView(CigramUI.label(this, perks[i][1], 12f, 0xFF3DDC97, true));
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
                tl.setMarginStart(dp(10));
                t.addView(txt, tl);
                if (i == 3) {
                    t.setClickable(true);
                    t.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { openByName("ChannelsActivity"); }
                    });
                }
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
                p.setMargins(k == 0 ? 0 : dp(5), 0, k == 0 ? dp(5) : 0, 0);
                row.addView(t, p);
            }
            box.addView(row, lp(0, 10));
        }
        return box;
    }

    private View actionCard(String title, String sub, String button, int buttonColor, final Runnable go) {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(CigramPlusArt.surface(this, 20));
        c.setPadding(dp(16), dp(14), dp(14), dp(14));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(CigramUI.label(this, title, 15f, 0xFFFFFFFF, true));
        TextView s = CigramUI.label(this, sub, 12f, 0xFF8FA1AA, false);
        s.setLineSpacing(0f, 1.2f);
        txt.addView(s, lp(2, 0));
        c.addView(txt, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView b = CigramUI.label(this, button, 13f, buttonColor == 0xFFFFC107 ? 0xFFFFFFFF : CigramPlusUI.GOLD_INK, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setBackground(buttonColor == 0xFFFFC107 ? CigramPlusUI.pill(this, buttonColor, 20) : CigramPlusUI.goldBg(this, 20));
        if (buttonColor == 0xFFFFC107) b.setShadowLayer(dp(2), 0f, dp(1), 0x66000000);
        b.setClickable(true);
        c.addView(b);
        c.setClickable(true);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { go.run(); }
        });
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { go.run(); }
        });
        return c;
    }

    private View renewalCard() {
        int left = CigramPlus.daysLeft(this);
        boolean soon = left <= 30;
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(CigramPlusArt.ring(this, 20, soon ? 0x22FFB45D : 0x1F22B573, soon ? 0x88FFB45D : 0x6622B573, 1.2f));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.addView(CigramUI.label(this, soon ? "اشتراكك ينتهي قريباً" : "اشتراكك ساري", 15f, soon ? 0xFFFFC27A : 0xFF3DDC97, true));
        c.addView(CigramUI.label(this, CigramPlus.periodText(this) + "  ·  متبقٍّ " + left + " يوم", 12.5f, 0xFFD6E2E8, false), lp(4, 0));
        if (soon && CigramPlus.salesEnabled()) {
            TextView b = CigramUI.label(this, "جدّد الآن", 14f, CigramPlusUI.GOLD_INK, true);
            b.setGravity(Gravity.CENTER);
            b.setMinHeight(dp(46));
            b.setBackground(CigramPlusUI.goldBg(this, 16));
            b.setClickable(true);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { CigramPlusUI.openPage(CigramPlusCodeActivity.this, "pay"); }
            });
            c.addView(b, lp(12, 0));
        } else {
            c.addView(CigramUI.label(this, "التجديد بكود جديد يُضاف على مدتك الحالية.", 11.5f, 0xFF8FA1AA, false), lp(6, 0));
        }
        return c;
    }

    // ------------------------------------------------------------------ activation

    private void activate() {
        if (busy) return;
        String code = codeInput.getText().toString();
        if (CigramPlus.cleanCode(code).length() == 0) { showCodeMessage(CigramPlus.messageFor(CigramPlus.INVALID), true); return; }
        busy = true;
        activateBtn.setAlpha(0.55f);
        activateBtn.setText("…");
        codeInput.setEnabled(false);
        showCodeMessage("", false);
        CigramPlus.redeem(this, code, new CigramPlus.Result() {
            @Override public void onResult(int status, long until) {
                if (isFinishing() || isDestroyed()) return;
                busy = false;
                activateBtn.setAlpha(1f);
                activateBtn.setText("تفعيل الخدمة");
                codeInput.setEnabled(true);
                if (status == CigramPlus.OK) {
                    codeInput.setText("");
                    hideKeyboard();
                    renderLounge();                    // the members' area appears right here
                    CigramPlusUI.showGift(CigramPlusCodeActivity.this, until);
                } else {
                    showCodeMessage(CigramPlus.messageFor(status), true);
                }
            }
        });
    }

    /** One tap: takes the code from the clipboard (cleaned: capitals, no spaces) and puts it in the field. */
    private void pasteCode() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            CharSequence text = null;
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                text = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            }
            String code = CigramPlus.cleanCode(text == null ? "" : text.toString());
            if (code.length() == 0) { showCodeMessage("لا يوجد كود صالح في الحافظة. انسخ الكود ثم اضغط لصق.", true); return; }
            codeInput.setText(code);
            codeInput.setSelection(code.length());
            showCodeMessage("", false);
        } catch (Throwable t) {
            showCodeMessage("تعذّر قراءة الحافظة، اكتب الكود يدوياً.", true);
        }
    }

    private void showCodeMessage(String m, boolean error) {
        codeMsg.setText(m);
        codeMsg.setTextColor(error ? 0xFFFF6B6B : 0xFF3DDC97);
        codeMsg.setVisibility(m.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(codeInput.getWindowToken(), 0);
        } catch (Throwable ignored) { }
    }
}
