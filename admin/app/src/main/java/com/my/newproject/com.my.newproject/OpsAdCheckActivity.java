package com.my.newproject;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

/** Ad diagnostics from real user data: fill rate, AdMob failure codes, skip reasons, app-ads.txt. */
public class OpsAdCheckActivity extends OpsBase {

    private String domainText = "";

    @Override
    protected String screenTitle() {
        return "فحص الإعلانات";
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
        bar.action("فحص", new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
        load();
    }

    private void load() {
        String path = "/admin/ops/ads-check?days=" + days;
        if (domainText.length() > 0) {
            try {
                path += "&domain=" + URLEncoder.encode(domainText, "UTF-8");
            } catch (Exception ignored) { }
        }
        getJson(path, new JsonDone() {
            @Override
            public void ok(JSONObject d, JSONObject root) {
                render(d);
            }
        });
    }

    private String codeName(String code) {
        if ("0".equals(code)) return "خطأ داخلي في AdMob";
        if ("1".equals(code)) return "طلب غير صالح";
        if ("2".equals(code)) return "خطأ شبكة";
        if ("3".equals(code)) return "لا يوجد إعلان (No fill)";
        if ("8".equals(code)) return "App ID غير صالح";
        if ("9".equals(code)) return "لا توجد شبكة وسيطة";
        return "كود " + code;
    }

    private String skipName(String reason) {
        if ("cooldown".equals(reason)) return "فترة الانتظار بين إعلانين";
        if ("hourly_cap".equals(reason)) return "حد الإعلانات في الساعة";
        if ("session_cap".equals(reason)) return "حد الإعلانات في الجلسة";
        if ("episode_gap".equals(reason)) return "الفاصل بين إعلانات الحلقات";
        if ("other_ad_showing".equals(reason)) return "إعلان آخر معروض";
        if ("other_ad_loading".equals(reason)) return "إعلان آخر قيد التحميل";
        return reason;
    }

    /** Converts [{key:"placement|detail",count}] into [{key:readable,count}]. */
    private JSONArray readable(JSONArray src, boolean failures) {
        JSONArray out = new JSONArray();
        if (src == null) return out;
        for (int i = 0; i < src.length(); i++) {
            JSONObject o = src.optJSONObject(i);
            if (o == null) continue;
            String key = o.optString("key", "?");
            String[] p = key.split("\\|", 2);
            String place = p[0];
            String detail = p.length > 1 ? p[1] : "?";
            String label;
            if (failures && detail.startsWith("load:")) label = place + " — " + codeName(detail.substring(5));
            else if (failures) label = place + " — " + detail;
            else label = place + " — " + skipName(detail);
            try {
                JSONObject r = new JSONObject();
                r.put("key", label);
                r.put("count", o.optDouble("count", 0));
                out.put(r);
            } catch (Exception ignored) { }
        }
        return out;
    }

    private void render(JSONObject d) {
        if (!alive()) return;
        LinearLayout c = scrollBody();
        c.addView(header());

        // domain row
        LinearLayout dc = CgUi.card(this);
        dc.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        JSONObject cfg = d.optJSONObject("config");
        if (domainText.length() == 0 && cfg != null) domainText = cfg.optString("developer_domain", "");
        final EditText dom = CgUi.urlField(this, "example.com");
        dom.setText(domainText);
        dc.addView(CgUi.labeled(this, "نطاق موقع المطوّر (لفحص app-ads.txt)", dom));
        TextView go = CgUi.smallButton(this, "فحص", 0);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                domainText = dom.getText().toString().trim();
                load();
            }
        });
        dc.addView(go, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        c.addView(dc);

        c.addView(CgUi.muted(this, "الجلسات في الفترة: " + n(d.optDouble("sessions", 0))), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 0, 4, 8));

        // verdicts
        JSONArray verdicts = d.optJSONArray("verdicts");
        if (verdicts != null) {
            for (int i = 0; i < verdicts.length(); i++) {
                JSONObject v = verdicts.optJSONObject(i);
                if (v == null) continue;
                c.addView(verdictCard(v.optString("level", "info"), v.optString("title", ""), v.optString("text", "")));
            }
        }

        // placements
        JSONArray pl = d.optJSONArray("placements");
        c.addView(CgUi.sectionTitle(this, "الأداء حسب الموضع"));
        if (pl == null || pl.length() == 0) {
            c.addView(CgUi.muted(this, "لا توجد أحداث إعلانات في هذه الفترة."));
        } else {
            for (int i = 0; i < pl.length(); i++) {
                JSONObject p = pl.optJSONObject(i);
                if (p == null) continue;
                LinearLayout card = CgUi.card(this);
                card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
                card.addView(CgUi.text(this, p.optString("name", "?"), 14, CgCfg.TEXT, true));
                double req = p.optDouble("request", 0);
                double loaded = p.optDouble("loaded", 0);
                card.addView(kv("طلبات", n(req), CgCfg.TEXT));
                card.addView(kv("حُمّل", n(loaded) + "  (" + pct(loaded, req) + ")", CgCfg.TEXT));
                card.addView(kv("عُرض", n(p.optDouble("show", 0)) + "  (" + pct(p.optDouble("show", 0), loaded) + " من المحمّل)", CgCfg.GOOD));
                card.addView(kv("أُغلق", n(p.optDouble("closed", 0)), CgCfg.TEXT));
                double failed = p.optDouble("failed", 0);
                card.addView(kv("فشل", n(failed), failed > 0 ? CgCfg.BAD : CgCfg.TEXT));
                card.addView(kv("مُنع بالسياسة", n(p.optDouble("skipped", 0)), CgCfg.WARN));
                c.addView(card);
            }
        }

        c.addView(CgUi.sectionTitle(this, "أسباب الفشل والمنع"));
        c.addView(rankCard("أكواد فشل AdMob", readable(d.optJSONArray("failures"), true), 12));
        c.addView(rankCard("أسباب منع العرض بالسياسة", readable(d.optJSONArray("skips"), false), 12));

        JSONObject aa = d.optJSONObject("appAds");
        if (aa != null && aa.optBoolean("checked", false) && aa.optString("line", "").length() > 0) {
            LinearLayout card = CgUi.card(this);
            card.addView(CgUi.text(this, "سطر app-ads.txt الموجود", 13, CgCfg.TEXT, true));
            TextView line = CgUi.text(this, aa.optString("line", ""), 12, CgCfg.MUTED, false);
            CgUi.ltr(line);
            line.setTextIsSelectable(true);
            card.addView(line);
            c.addView(card);
        }
    }
}
