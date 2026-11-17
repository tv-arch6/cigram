package com.my.newproject;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/** All app errors grouped by signature, with counts, versions, share/copy report and "resolved". */
public class OpsErrorsActivity extends OpsBase {

    private boolean showAll = false;
    private JSONArray items = new JSONArray();
    private int hidden = 0;

    @Override
    protected String screenTitle() {
        return "أخطاء التطبيق";
    }

    @Override
    protected View header() {
        return daysBar(new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
    }

    @Override
    protected void onBuild() {
        bar.action("التقرير", new Runnable() {
            @Override
            public void run() {
                reportMenu();
            }
        });
        load();
    }

    private void load() {
        getJson("/admin/ops/errors?days=" + days + (showAll ? "&all=1" : ""), new JsonDone() {
            @Override
            public void ok(JSONObject d, JSONObject root) {
                items = d.optJSONArray("items");
                if (items == null) items = new JSONArray();
                hidden = d.optInt("hidden", 0);
                render(d);
            }
        });
    }

    private void reportMenu() {
        CgUi.choose(this, "التقرير", new CharSequence[]{"مشاركة التقرير", "نسخ التقرير", "تم حل الكل"}, new CgUi.IntCb() {
            @Override
            public void run(int index) {
                if (index == 0) shareText("تقرير أخطاء Cigram", report());
                else if (index == 1) copyText("تقرير أخطاء Cigram", report());
                else resolveAll();
            }
        });
    }

    private void resolveAll() {
        CgUi.confirm(this, "إخفاء كل الأخطاء الحالية؟",
                "تُعلَّم كلها كمحلولة وتختفي حتى يظهر الخطأ نفسه مرة أخرى.", "تم حل الكل", false, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            JSONObject b = new JSONObject();
                            b.put("all", true);
                            sendResolve(b);
                        } catch (Exception ignored) { }
                    }
                });
    }

    private void sendResolve(JSONObject body) {
        postJson("/admin/ops/errors/resolve", body, "جارٍ التحديث...", new JsonDone() {
            @Override
            public void ok(JSONObject d, JSONObject root) {
                String m = root.optString("message", "");
                CgUi.toast(OpsErrorsActivity.this, m.length() > 0 ? m : "تم.");
                load();
            }
        });
    }

    private String kindLabel(String event) {
        if ("app_crash".equals(event)) return "انهيار";
        if ("player_error".equals(event) || event.startsWith("play_")) return "مشغّل";
        if (event.contains("ad_") || event.startsWith("ad")) return "إعلان";
        if (event.contains("network") || event.contains("http")) return "شبكة";
        return "خطأ";
    }

    private int kindColor(String event) {
        return "app_crash".equals(event) ? CgCfg.BAD : CgCfg.WARN;
    }

    private String titleOf(JSONObject it) {
        StringBuilder sb = new StringBuilder();
        String[] parts = new String[]{it.optString("where", ""), it.optString("type", ""), it.optString("detail", "")};
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() == 0) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(parts[i]);
        }
        if (sb.length() == 0) sb.append(it.optString("event", "خطأ"));
        return sb.toString();
    }

    private String topList(JSONArray a) {
        if (a == null || a.length() == 0) return "—";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (sb.length() > 0) sb.append("، ");
            sb.append(o.optString("key", "?")).append(" (").append(o.optInt("count", 0)).append(")");
        }
        return sb.toString();
    }

    private void render(JSONObject d) {
        if (!alive()) return;
        LinearLayout c = scrollBody();
        c.addView(header());
        bar.subtitle(null);

        LinearLayout chips = CgUi.hbox(this);
        final TextView toggle = CgUi.chip(this, "إظهار المحلولة", showAll);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAll = !showAll;
                load();
            }
        });
        chips.addView(toggle, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        c.addView(chips, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));

        long total = d.optLong("total", 0);
        String line = items.length() + " نوع خطأ · " + n(total) + " مرة";
        if (hidden > 0) line += " · " + hidden + " مخفي (محلول)";
        c.addView(CgUi.muted(this, line), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 0, 4, 8));

        if (items.length() == 0) {
            c.addView(CgUi.empty(this, "لا توجد أخطاء", "لم تُسجَّل أخطاء في هذه الفترة" + (hidden > 0 ? " (يوجد " + hidden + " مخفي كمحلول)" : "") + "."));
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null) c.addView(card(it));
        }
    }

    private View card(final JSONObject it) {
        String event = it.optString("event", "");
        final boolean resolved = it.optBoolean("resolved", false);
        LinearLayout card = CgUi.card(this);
        card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));

        LinearLayout top = CgUi.hbox(this);
        top.addView(CgUi.badge(this, kindLabel(event), kindColor(event)), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        View gap = new View(this);
        top.addView(gap, new LinearLayout.LayoutParams(CgUi.dp(this, 6), 1));
        top.addView(CgUi.badge(this, "× " + n(it.optDouble("count", 0)), CgCfg.TEXT), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        if (resolved) {
            View gap2 = new View(this);
            top.addView(gap2, new LinearLayout.LayoutParams(CgUi.dp(this, 6), 1));
            top.addView(CgUi.badge(this, "محلول", CgCfg.GOOD), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        }
        card.addView(top, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        TextView t = CgUi.text(this, titleOf(it), 14.5f, CgCfg.TEXT, true);
        t.setPadding(0, CgUi.dp(this, 6), 0, 0);
        card.addView(t, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        card.addView(CgUi.muted(this, event));

        card.addView(CgUi.muted(this, "آخر ظهور: " + CgUi.ago(it.optString("last", "")) + " · أول ظهور: " + CgUi.localText(it.optString("first", ""))));
        card.addView(CgUi.muted(this, "الإصدارات: " + topList(it.optJSONArray("versions"))));
        card.addView(CgUi.muted(this, "أندرويد: " + topList(it.optJSONArray("os"))));

        final TextView sample = CgUi.text(this, sampleText(it), 12, CgCfg.MUTED, false);
        CgUi.ltr(sample);
        sample.setVisibility(View.GONE);
        sample.setPadding(0, CgUi.dp(this, 6), 0, 0);
        sample.setTextIsSelectable(true);
        card.addView(sample, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        LinearLayout btns = CgUi.hbox(this);
        TextView more = CgUi.smallButton(this, "تفاصيل", 1);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sample.setVisibility(sample.getVisibility() == View.GONE ? View.VISIBLE : View.GONE);
            }
        });
        btns.addView(more, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        if (!resolved) {
            View g = new View(this);
            btns.addView(g, new LinearLayout.LayoutParams(CgUi.dp(this, 8), 1));
            TextView done = CgUi.smallButton(this, "تم الحل", 0);
            done.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        JSONObject b = new JSONObject();
                        b.put("sig", it.optString("sig", ""));
                        sendResolve(b);
                    } catch (Exception ignored) { }
                }
            });
            btns.addView(done, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        }
        card.addView(btns, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 0, 0));
        return card;
    }

    private String sampleText(JSONObject it) {
        JSONObject s = it.optJSONObject("sample");
        if (s == null || s.length() == 0) return "(لا توجد تفاصيل إضافية)";
        StringBuilder sb = new StringBuilder();
        Iterator<String> keys = s.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (sb.length() > 0) sb.append("\n");
            sb.append(k).append(": ").append(s.optString(k, ""));
        }
        return sb.toString();
    }

    private String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("تقرير أخطاء Cigram — آخر ").append(days).append(days == 1 ? " يوم" : " يوماً").append("\n");
        sb.append("عدد الأنواع: ").append(items.length()).append("\n\n");
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            sb.append(i + 1).append(". [").append(kindLabel(it.optString("event", ""))).append("] ")
                    .append(titleOf(it)).append("\n");
            sb.append("   الحدث: ").append(it.optString("event", "")).append(" · العدد: ").append(it.optInt("count", 0)).append("\n");
            sb.append("   الإصدارات: ").append(topList(it.optJSONArray("versions"))).append("\n");
            sb.append("   أندرويد: ").append(topList(it.optJSONArray("os"))).append("\n");
            sb.append("   أول ظهور: ").append(CgUi.localText(it.optString("first", "")))
                    .append(" · آخر ظهور: ").append(CgUi.localText(it.optString("last", ""))).append("\n");
            JSONObject s = it.optJSONObject("sample");
            if (s != null && s.length() > 0) {
                Iterator<String> keys = s.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    sb.append("   ").append(k).append(": ").append(s.optString(k, "")).append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
