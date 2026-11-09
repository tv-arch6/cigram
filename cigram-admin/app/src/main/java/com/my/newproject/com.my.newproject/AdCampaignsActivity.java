package com.my.newproject;

import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** قائمة الحملات مع فلاتر المرحلة والمساحة والقريبة من الانتهاء وبحث. */
public class AdCampaignsActivity extends CgBase {

    private String stage = "";
    private boolean expiring;
    private String query = "";
    private LinearLayout box;
    private JSONObject settings;
    private int reqId;

    @Override protected String screenTitle() { return "الحملات"; }

    @Override protected void onBuild() {
        bar.action("＋ حملة", new Runnable() { @Override public void run() { startActivity(new Intent(AdCampaignsActivity.this, AdCampaignActivity.class)); } });
        final EditText s = CgUi.field(this, "بحث باسم الحملة أو المعلن...", false);
        s.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        s.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, android.view.KeyEvent e) { query = v.getText().toString().trim(); load(); return true; }
        });
        body.addView(s, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 12, 8, 12, 4));
        final String[][] chips = {{"", "الكل"}, {"received", "مستلم"}, {"review", "قيد المراجعة"}, {"quoted", "عرض سعر"}, {"awaiting_payment", "بانتظار الدفع"}, {"scheduled", "مجدول"}, {"running", "يعمل"}, {"paused", "متوقف"}, {"ended", "منتهي"}, {"rejected", "مرفوض"}, {"expiring", "ينتهي قريباً"}};
        CgUi.ChipBar cb = new CgUi.ChipBar(this);
        final java.util.ArrayList<TextView> cv = new java.util.ArrayList<TextView>();
        for (final String[] c : chips) {
            final TextView t = cb.add(c[1], c[0].equals("") && !expiring);
            cv.add(t);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    expiring = "expiring".equals(c[0]);
                    stage = expiring ? "" : c[0];
                    for (int i = 0; i < cv.size(); i++) CgUi.setChipState(cv.get(i), chips[i][0].equals(expiring ? "expiring" : stage));
                    load();
                }
            });
        }
        body.addView(cb.scroll, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        box = CgUi.vbox(this);
        box.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 8), CgUi.dp(this, 12), CgUi.dp(this, 80));
        sv.addView(box);
        body.addView(sv, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));
        AdApi.get(this, "/ads/config", new AdApi.Cb() { @Override public void on(AdApi.Result r) { if (r.ok) { settings = r.json.optJSONObject("settings"); } } });
    }

    @Override protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        final int my = ++reqId; // ردّ قديم لفلتر سابق لا يرسم فوق الأحدث
        if (box.getChildCount() == 0) box.addView(CxUi.skeleton(this, 4));
        AdApi.get(this, "/admin/campaigns?limit=300&q=" + AdApi.enc(query) + (stage.length() > 0 ? "&stage=" + stage : "") + (expiring ? "&expiring=1" : ""), new AdApi.Cb() {
            @Override public void on(AdApi.Result r) {
                if (!alive() || my != reqId) return;
                box.removeAllViews();
                if (!r.ok) { box.addView(CgUi.errorCard(AdCampaignsActivity.this, r.message, new Runnable() { @Override public void run() { load(); } })); return; }
                JSONArray a = r.json.optJSONArray("campaigns");
                if (a == null || a.length() == 0) { box.addView(CgUi.empty(AdCampaignsActivity.this, "لا توجد حملات", "أنشئ حملة جديدة أو حوّل طلباً من المحادثات.")); return; }
                bar.subtitle(a.length() + " حملة");
                for (int i = 0; i < a.length(); i++) {
                    final JSONObject o = a.optJSONObject(i);
                    LinearLayout c = CgUi.card(AdCampaignsActivity.this);
                    LinearLayout h = CgUi.hbox(AdCampaignsActivity.this);
                    String cname = o.optString("name", "").trim();
                    h.addView(CgUi.text(AdCampaignsActivity.this, cname.length() > 0 ? cname : "حملة بلا اسم — افتحها لتسميتها", 15, cname.length() > 0 ? CgCfg.TEXT : CgCfg.WARN, true), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
                    String st = o.optString("stage");
                    h.addView(CgUi.badge(AdCampaignsActivity.this, AdApi.stageLabel(st), AdApi.stageColor(st)));
                    c.addView(h);
                    c.addView(CgUi.muted(AdCampaignsActivity.this, o.optString("advertiser_name") + " • " + AdApi.slotLabel(settings, o.optString("slot"))));
                    c.addView(CgUi.muted(AdCampaignsActivity.this, AdApi.date(o.optLong("start_at")) + " ← " + AdApi.date(o.optLong("end_at"))));
                    JSONObject tot = o.optJSONObject("totals");
                    String line = AdApi.money(o.optDouble("price"), o.optString("currency")) + " • " + ("paid".equals(o.optString("paid_status")) ? "مدفوع" : "partial".equals(o.optString("paid_status")) ? "جزئي" : "غير مدفوع");
                    if (tot != null) line += " • مشاهدات " + tot.optLong("impressions") + " • نقرات " + tot.optLong("clicks");
                    c.addView(CgUi.text(AdCampaignsActivity.this, line, 12.5f, CgCfg.MUTED, false), CgUi.lp(AdCampaignsActivity.this, CgUi.WRAP, CgUi.WRAP, 0, 4, 0, 0));
                    if (o.optBoolean("expiring_soon")) c.addView(CgUi.badge(AdCampaignsActivity.this, "تنتهي قريباً", CgCfg.WARN), CgUi.lp(AdCampaignsActivity.this, CgUi.WRAP, CgUi.WRAP, 0, 6, 0, 0));
                    if (o.optInt("reports") > 0) c.addView(CgUi.badge(AdCampaignsActivity.this, "" + o.optInt("reports") + " بلاغ", CgCfg.BAD), CgUi.lp(AdCampaignsActivity.this, CgUi.WRAP, CgUi.WRAP, 0, 6, 0, 0));
                    if (o.optInt("creatives_count") == 0) c.addView(CgUi.badge(AdCampaignsActivity.this, "بلا مادة إعلانية", CgCfg.GRAY), CgUi.lp(AdCampaignsActivity.this, CgUi.WRAP, CgUi.WRAP, 0, 6, 0, 0));
                    c.setClickable(true);
                    c.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { Intent in = new Intent(AdCampaignsActivity.this, AdCampaignActivity.class); in.putExtra("id", o.optString("id")); startActivity(in); }
                    });
                    box.addView(c, CgUi.lp(AdCampaignsActivity.this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
                }
            }
        });
    }
}
