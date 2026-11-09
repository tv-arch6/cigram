package com.my.newproject;

import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** قائمة المعلنين مع بحث وأرشيف وإضافة معلن يدوياً (مثلاً من تواصل خارج التطبيق). */
public class AdAdvertisersActivity extends CgBase {

    private String query = "";
    private boolean archived;
    private LinearLayout box;
    private int reqId;

    @Override protected String screenTitle() { return "المعلنون"; }

    @Override protected void onBuild() {
        bar.action("＋ معلن", new Runnable() {
            @Override public void run() { startActivity(new Intent(AdAdvertisersActivity.this, AdAdvertiserActivity.class)); }
        });
        final EditText s = CgUi.field(this, "بحث بالاسم...", false);
        s.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        s.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, android.view.KeyEvent e) { query = v.getText().toString().trim(); load(); return true; }
        });
        body.addView(s, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 12, 8, 12, 4));
        CgUi.ChipBar cb = new CgUi.ChipBar(this);
        final TextView a = cb.add("النشطون", true);
        final TextView b = cb.add("المؤرشفون", false);
        a.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { archived = false; CgUi.setChipState(a, true); CgUi.setChipState(b, false); load(); } });
        b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { archived = true; CgUi.setChipState(a, false); CgUi.setChipState(b, true); load(); } });
        body.addView(cb.scroll, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        box = CgUi.vbox(this);
        box.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 8), CgUi.dp(this, 12), CgUi.dp(this, 80));
        sv.addView(box);
        body.addView(sv, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));
    }

    @Override protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        final int my = ++reqId;
        if (box.getChildCount() == 0) box.addView(CxUi.skeleton(this, 4));
        AdApi.get(this, "/admin/advertisers?q=" + AdApi.enc(query) + (archived ? "&archived=1" : ""), new AdApi.Cb() {
            @Override public void on(AdApi.Result r) {
                if (!alive() || my != reqId) return;
                box.removeAllViews();
                if (!r.ok) { box.addView(CgUi.errorCard(AdAdvertisersActivity.this, r.message, new Runnable() { @Override public void run() { load(); } })); return; }
                JSONArray a = r.json.optJSONArray("advertisers");
                if (a == null || a.length() == 0) { box.addView(CgUi.empty(AdAdvertisersActivity.this, "لا يوجد معلنون", "يُنشأ ملف المعلن تلقائياً عند أول رسالة له، أو أضفه يدوياً.")); return; }
                for (int i = 0; i < a.length(); i++) {
                    final JSONObject o = a.optJSONObject(i);
                    LinearLayout c = CgUi.card(AdAdvertisersActivity.this);
                    LinearLayout head = CgUi.hbox(AdAdvertisersActivity.this);
                    head.addView(CgUi.text(AdAdvertisersActivity.this, o.optString("name"), 15.5f, CgCfg.TEXT, true), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
                    if (o.optBoolean("banned")) head.addView(CgUi.badge(AdAdvertisersActivity.this, "محظور", CgCfg.BAD));
                    if (o.optInt("running") > 0) head.addView(CgUi.badge(AdAdvertisersActivity.this, o.optInt("running") + " تعمل", CgCfg.GOOD), CgUi.lp(AdAdvertisersActivity.this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
                    c.addView(head);
                    c.addView(CgUi.muted(AdAdvertisersActivity.this, o.optInt("campaigns") + " حملة • مدفوع " + AdApi.money(o.optDouble("paid_total"), "")));
                    c.setClickable(true);
                    c.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { Intent in = new Intent(AdAdvertisersActivity.this, AdAdvertiserActivity.class); in.putExtra("id", o.optString("id")); startActivity(in); }
                    });
                    box.addView(c, CgUi.lp(AdAdvertisersActivity.this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
                }
            }
        });
    }
}
