package com.my.newproject;

import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;

/** بلاغات المستخدمين على الإعلانات، مجمّعة حسب الحملة، مع فتح الحملة أو إيقافها أو مسح البلاغات. */
public class AdReportsActivity extends CgBase {

    private int reqId;

    @Override protected String screenTitle() { return "بلاغات الإعلانات"; }

    @Override protected void onBuild() {
        bar.action("تحديث", new Runnable() { @Override public void run() { load(); } });
        load();
    }

    private static String reasonLabel(String r) {
        if ("misleading".equals(r)) return "مضلل";
        if ("inappropriate".equals(r)) return "غير لائق";
        if ("annoying".equals(r)) return "مزعج";
        if ("broken".equals(r)) return "لا يعمل";
        if ("other".equals(r)) return "آخر";
        return r;
    }

    private void load() {
        final int my = ++reqId;
        final LinearLayout c = scrollBody();
        c.addView(CxUi.skeleton(this, 3));
        AdApi.get(this, "/admin/ad-reports", new AdApi.Cb() {
            @Override public void on(final AdApi.Result r) {
                if (!alive() || my != reqId) return;
                if (!r.ok) { c.removeAllViews(); c.addView(CgUi.errorCard(AdReportsActivity.this, r.message, new Runnable() { @Override public void run() { load(); } })); return; }
                // أسماء الحملات بدل المعرّفات الخام (فشل هذا الطلب لا يمنع العرض)
                AdApi.get(AdReportsActivity.this, "/admin/campaigns?limit=300", new AdApi.Cb() {
                    @Override public void on(AdApi.Result cr) {
                        if (!alive() || my != reqId) return;
                        HashMap<String, String> names = new HashMap<String, String>();
                        JSONArray ca = cr.ok ? cr.json.optJSONArray("campaigns") : null;
                        for (int i = 0; ca != null && i < ca.length(); i++) {
                            JSONObject o = ca.optJSONObject(i);
                            if (o != null) names.put(o.optString("id"), o.optString("name"));
                        }
                        c.removeAllViews();
                        render(c, r.json.optJSONArray("reports"), names);
                    }
                });
            }
        });
    }

    private void render(LinearLayout c, JSONArray a, HashMap<String, String> names) {
        {
            {
                if (a == null || a.length() == 0) { c.addView(CgUi.empty(AdReportsActivity.this, "لا توجد بلاغات", "")); return; }
                HashMap<String, java.util.ArrayList<JSONObject>> by = new HashMap<String, java.util.ArrayList<JSONObject>>();
                java.util.ArrayList<String> order = new java.util.ArrayList<String>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    String id = o.optString("campaign_id");
                    if (!by.containsKey(id)) { by.put(id, new java.util.ArrayList<JSONObject>()); order.add(id); }
                    by.get(id).add(o);
                }
                for (final String id : order) {
                    java.util.ArrayList<JSONObject> list = by.get(id);
                    LinearLayout card = CgUi.card(AdReportsActivity.this);
                    String cname = names.get(id);
                    card.addView(CgUi.text(AdReportsActivity.this, (cname != null && cname.length() > 0 ? cname : "حملة") + " — " + list.size() + " بلاغ", 14.5f, CgCfg.TEXT, true));
                    HashMap<String, Integer> counts = new HashMap<String, Integer>();
                    for (JSONObject o : list) { String k = reasonLabel(o.optString("reason")); counts.put(k, counts.containsKey(k) ? counts.get(k) + 1 : 1); }
                    StringBuilder sb = new StringBuilder();
                    for (String k : counts.keySet()) sb.append(k).append(" (").append(counts.get(k)).append(")  ");
                    card.addView(CgUi.muted(AdReportsActivity.this, sb.toString()));
                    for (int i = 0; i < list.size() && i < 3; i++) if (list.get(i).optString("note").length() > 0) card.addView(CgUi.text(AdReportsActivity.this, "• " + list.get(i).optString("note"), 12.5f, CgCfg.MUTED, false));
                    LinearLayout row = CgUi.hbox(AdReportsActivity.this);
                    android.widget.TextView open = CgUi.smallButton(AdReportsActivity.this, "فتح الحملة", 0);
                    open.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { Intent in = new Intent(AdReportsActivity.this, AdCampaignActivity.class); in.putExtra("id", id); startActivity(in); } });
                    android.widget.TextView clear = CgUi.smallButton(AdReportsActivity.this, "مسح البلاغات", 1);
                    clear.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            CgUi.confirm(AdReportsActivity.this, "مسح البلاغات", "ستُحذف كل بلاغات هذه الحملة نهائياً ويعود عدّاد البلاغات إلى صفر. لا يؤثر هذا على الحملة نفسها.", "مسح", true, new Runnable() {
                                @Override public void run() {
                                    try {
                                        AdApi.post(AdReportsActivity.this, "/admin/ad-reports/clear", new JSONObject().put("campaign_id", id), new AdApi.Cb() {
                                            @Override public void on(AdApi.Result r) {
                                                if (!r.ok) { CgUi.toast(AdReportsActivity.this, r.message); return; }
                                                CgUi.toast(AdReportsActivity.this, "تم مسح البلاغات");
                                                load();
                                            }
                                        });
                                    } catch (Throwable ignored) { }
                                }
                            });
                        }
                    });
                    row.addView(open, CgUi.lp(AdReportsActivity.this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
                    row.addView(clear);
                    card.addView(row, CgUi.lp(AdReportsActivity.this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
                    c.addView(card, CgUi.lp(AdReportsActivity.this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
                }
            }
        }
    }
}
