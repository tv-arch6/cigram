package com.Cigram.vid;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** «إعلاناتي»: حملات المعلن الحالية وحالتها وإحصاءاتها مع مشاركة رابط التقرير وطلب التجديد. */
public class MyAdsActivity extends Activity {

    private boolean destroyed;
    private LinearLayout page;
    private JSONObject cfg;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            getWindow().setStatusBarColor(CigramGlass.bg(this));
            getWindow().setNavigationBarColor(CigramGlass.bg(this));
        } catch (Throwable ignored) { }
        FrameLayout shell = new FrameLayout(this);
        shell.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        shell.setBackgroundColor(CigramGlass.bg(this));
        LinearLayout col = AdsUi.vbox(this);
        shell.addView(col, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout top = AdsUi.hbox(this);
        top.setPadding(AdsUi.dp(this, 8), AdsUi.dp(this, 6), AdsUi.dp(this, 8), AdsUi.dp(this, 6));
        top.addView(icon(AdsIcons.BACK, "رجوع", new View.OnClickListener() { @Override public void onClick(View v) { finish(); } }), new LinearLayout.LayoutParams(AdsUi.dp(this, 48), AdsUi.dp(this, 48)));
        TextView title = AdsUi.text(this, "إعلاناتي", 20f, CigramGlass.text(this), true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2);
        tl.weight = 1f;
        tl.setMargins(AdsUi.dp(this, 4), 0, 0, 0);
        top.addView(title, tl);
        top.addView(icon(AdsIcons.REFRESH, "تحديث", new View.OnClickListener() { @Override public void onClick(View v) { load(); } }), new LinearLayout.LayoutParams(AdsUi.dp(this, 48), AdsUi.dp(this, 48)));
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        page = AdsUi.vbox(this);
        page.setPadding(AdsUi.dp(this, 16), AdsUi.dp(this, 8), AdsUi.dp(this, 16), AdsUi.dp(this, 24));
        sv.addView(page, new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0);
        sl.weight = 1f;
        col.addView(sv, sl);
        TextView chat = AdsUi.button(this, "تواصل مع الإدارة", CigramUI.TEAL, 0xFFFFFFFF);
        chat.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openChat(null); } });
        LinearLayout.LayoutParams cl = AdsUi.lp(-1, -2, AdsUi.dp(this, 16), AdsUi.dp(this, 8), AdsUi.dp(this, 16), AdsUi.dp(this, 12));
        col.addView(chat, cl);
        setContentView(shell);
        if (!CigramAds.isLoggedIn(this)) {
            AdsUi.toast(this, "سجّل الدخول أولاً.");
            finish();
            return;
        }
        cfg = CigramAds.cachedConfig(this);
        load();
    }

    @Override protected void onDestroy() { destroyed = true; super.onDestroy(); }

    private View icon(int ic, String desc, View.OnClickListener click) {
        FrameLayout f = new FrameLayout(this);
        f.setClickable(true);
        f.setFocusable(true);
        f.setContentDescription(desc);
        f.setBackground(CigramGlass.ripple(this, new android.graphics.drawable.GradientDrawable(), 24));
        f.addView(new AdsIcons.Icon(this, ic, CigramGlass.text(this)), new FrameLayout.LayoutParams(AdsUi.dp(this, 24), AdsUi.dp(this, 24), Gravity.CENTER));
        f.setOnClickListener(click);
        return f;
    }

    private void openChat(String orderJson) {
        try {
            Intent i = new Intent(this, Class.forName("com.Cigram.vid.AdsChatActivity"));
            if (orderJson != null) i.putExtra("order_json", orderJson);
            startActivity(i);
        } catch (Throwable t) {
            AdsUi.toast(this, "شاشة المحادثة غير مفعّلة في هذه النسخة.");
        }
    }

    private void load() {
        page.removeAllViews();
        for (int i = 0; i < 3; i++) {
            View sk = new View(this);
            sk.setBackground(CigramUI.round(CigramGlass.soft(this), 16, this));
            page.addView(sk, AdsUi.lp(-1, AdsUi.dp(this, 120), 0, 0, 0, AdsUi.dp(this, 12)));
        }
        CigramAds.get(this, "/me/campaigns", true, new CigramAds.Callback() {
            @Override public void onResult(CigramAds.Result r) {
                if (destroyed) return;
                page.removeAllViews();
                if (!r.ok) { showError(r); return; }
                JSONArray arr = r.json.optJSONArray("campaigns");
                if (arr == null || arr.length() == 0) { showEmpty(); return; }
                for (int i = 0; i < arr.length(); i++) page.addView(card(arr.optJSONObject(i)), AdsUi.lp(-1, -2, 0, 0, 0, AdsUi.dp(MyAdsActivity.this, 12)));
            }
        });
    }

    private void showError(final CigramAds.Result r) {
        TextView t = AdsUi.text(this, r.offline ? "لا يوجد اتصال بالإنترنت." : (r.message.length() > 0 ? r.message : "تعذر تحميل إعلاناتك."), 15f, CigramGlass.muted(this), false);
        t.setGravity(Gravity.CENTER);
        page.addView(t, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 40), 0, AdsUi.dp(this, 16)));
        TextView retry = AdsUi.button(this, "إعادة المحاولة", CigramUI.TEAL, 0xFFFFFFFF);
        retry.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { load(); } });
        page.addView(retry, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showEmpty() {
        page.addView(new AdsIcons.Icon(this, AdsIcons.MEGAPHONE, CigramUI.TEAL), AdsUi.lp(AdsUi.dp(this, 56), AdsUi.dp(this, 56), 0, AdsUi.dp(this, 48), 0, AdsUi.dp(this, 12)));
        ((LinearLayout.LayoutParams) page.getChildAt(0).getLayoutParams()).gravity = Gravity.CENTER_HORIZONTAL;
        TextView t = AdsUi.text(this, "لا توجد حملات بعد.\nابدأ من «أعلن هنا» ثم تواصل مع الإدارة لتأكيد طلبك.", 15f, CigramGlass.muted(this), false);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0f, 1.2f);
        page.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    private static String stageLabel(String s) {
        if ("received".equals(s)) return "مستلم";
        if ("review".equals(s)) return "قيد المراجعة";
        if ("needs_info".equals(s)) return "يحتاج معلومات";
        if ("quoted".equals(s)) return "عرض سعر";
        if ("awaiting_payment".equals(s)) return "بانتظار الدفع";
        if ("scheduled".equals(s)) return "مجدول";
        if ("running".equals(s)) return "يعمل الآن";
        if ("ended".equals(s)) return "منتهي";
        if ("rejected".equals(s)) return "مرفوض";
        if ("paused".equals(s)) return "متوقف";
        return s;
    }

    private static int stageColor(String s) {
        if ("running".equals(s)) return CigramUI.GREEN;
        if ("rejected".equals(s)) return CigramUI.DANGER;
        if ("paused".equals(s) || "awaiting_payment".equals(s) || "needs_info".equals(s)) return CigramUI.AMBER;
        if ("ended".equals(s)) return 0xFF8E8E94;
        return CigramUI.TEAL;
    }

    private View card(final JSONObject c) {
        LinearLayout card = AdsUi.card(this, 16f, 14f);
        String stage = c.optString("stage");
        LinearLayout head = AdsUi.hbox(this);
        TextView name = AdsUi.text(this, c.optString("name"), 16f, CigramGlass.text(this), true);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2);
        nl.weight = 1f;
        head.addView(name, nl);
        TextView chip = AdsUi.text(this, stageLabel(stage), 12f, stageColor(stage), true);
        chip.setBackground(CigramUI.round((stageColor(stage) & 0x00FFFFFF) | 0x26000000, 12, this));
        chip.setPadding(AdsUi.dp(this, 10), AdsUi.dp(this, 4), AdsUi.dp(this, 10), AdsUi.dp(this, 4));
        head.addView(chip);
        card.addView(head);
        SimpleDateFormat f = new SimpleDateFormat("d MMM yyyy", new Locale("ar"));
        card.addView(AdsUi.text(this, c.optString("slot_label") + " • " + f.format(new Date(c.optLong("start_at"))) + " ← " + f.format(new Date(c.optLong("end_at"))), 13f, CigramGlass.muted(this), false), AdsUi.lp(-2, -2, 0, AdsUi.dp(this, 6), 0, 0));
        String money = cfg != null ? CigramAds.money(cfg, c.optDouble("price")) : c.optString("price") + " " + c.optString("currency");
        String paid = "paid".equals(c.optString("paid_status")) ? "مدفوع" : "partial".equals(c.optString("paid_status")) ? "مدفوع جزئياً" : "غير مدفوع";
        card.addView(AdsUi.text(this, "السعر: " + money + " • " + paid, 13f, CigramGlass.text(this), false), AdsUi.lp(-2, -2, 0, AdsUi.dp(this, 4), 0, 0));
        if (c.optString("reject_reason").length() > 0) card.addView(AdsUi.text(this, "سبب الرفض: " + c.optString("reject_reason"), 13f, CigramUI.DANGER, false), AdsUi.lp(-2, -2, 0, AdsUi.dp(this, 4), 0, 0));
        JSONObject st = c.optJSONObject("stats");
        if (st != null && ("running".equals(stage) || "ended".equals(stage) || "paused".equals(stage))) {
            LinearLayout nums = AdsUi.hbox(this);
            nums.addView(stat("المشاهدات", CigramAds.count(st.optLong("impressions"))), weight());
            nums.addView(stat("النقرات", CigramAds.count(st.optLong("clicks"))), weight());
            nums.addView(stat("نسبة النقر", st.optString("ctr") + "%"), weight());
            card.addView(nums, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 10), 0, 0));
            JSONArray daily = st.optJSONArray("daily");
            if (daily != null && daily.length() > 1) card.addView(new Spark(this, daily), AdsUi.lp(-1, AdsUi.dp(this, 44), 0, AdsUi.dp(this, 8), 0, 0));
        }
        LinearLayout btns = AdsUi.hbox(this);
        final String url = c.optString("report_url");
        if (url.length() > 0) {
            TextView share = AdsUi.button(this, "مشاركة التقرير", CigramGlass.soft(this), CigramGlass.text(this));
            share.setMinHeight(AdsUi.dp(this, 48));
            share.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_TEXT, "تقرير حملة «" + c.optString("name") + "»: " + url);
                        startActivity(Intent.createChooser(i, "مشاركة التقرير"));
                    } catch (Throwable ignored) { }
                }
            });
            btns.addView(share, AdsUi.lp(0, -2, 0, 0, AdsUi.dp(this, 8), 0));
            ((LinearLayout.LayoutParams) share.getLayoutParams()).weight = 1f;
        }
        if (c.optBoolean("renewable")) {
            TextView renew = AdsUi.button(this, "تجديد", CigramUI.TEAL, 0xFFFFFFFF);
            renew.setMinHeight(AdsUi.dp(this, 48));
            renew.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        long days = Math.max(1, Math.round((c.optLong("end_at") - c.optLong("start_at")) / 86400000.0));
                        JSONObject o = new JSONObject();
                        o.put("slot", c.optString("slot"));
                        o.put("slot_label", c.optString("slot_label"));
                        o.put("days", days);
                        o.put("note", "تجديد حملة: " + c.optString("name"));
                        openChat(o.toString());
                    } catch (Throwable ignored) { }
                }
            });
            btns.addView(renew, new LinearLayout.LayoutParams(0, -2));
            ((LinearLayout.LayoutParams) renew.getLayoutParams()).weight = 1f;
        }
        if (btns.getChildCount() > 0) card.addView(btns, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 12), 0, 0));
        return card;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2);
        l.weight = 1f;
        return l;
    }

    private View stat(String label, String value) {
        LinearLayout l = AdsUi.vbox(this);
        l.addView(AdsUi.text(this, value, 17f, CigramGlass.text(this), true));
        l.addView(AdsUi.text(this, label, 12f, CigramGlass.muted(this), false), AdsUi.lp(-2, -2, 0, AdsUi.dp(this, 2), 0, 0));
        return l;
    }

    private static final class Spark extends View {
        private final int[] v;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        Spark(android.content.Context c, JSONArray a) {
            super(c);
            v = new int[a.length()];
            for (int i = 0; i < v.length; i++) v[i] = a.optJSONObject(i) == null ? 0 : a.optJSONObject(i).optInt("i");
            setContentDescription("مخطط المشاهدات اليومية");
        }
        @Override protected void onDraw(Canvas cv) {
            int max = 1;
            for (int x : v) max = Math.max(max, x);
            float slot = getWidth() / (float) v.length;
            p.setColor(CigramUI.TEAL);
            for (int i = 0; i < v.length; i++) {
                float h = Math.max(2f, getHeight() * v[i] / max);
                r.set(i * slot + slot * 0.15f, getHeight() - h, (i + 1) * slot - slot * 0.15f, getHeight());
                cv.drawRoundRect(r, 3f, 3f, p);
            }
        }
    }
}
