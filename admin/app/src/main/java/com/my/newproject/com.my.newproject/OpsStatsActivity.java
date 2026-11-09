package com.my.newproject;

import android.view.View;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;

/** Full usage statistics: users, sessions, daily charts, peak hours, watch funnel, speed, versions, Android, screens. */
public class OpsStatsActivity extends OpsBase {

    @Override
    protected String screenTitle() {
        return "الإحصائيات";
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
        bar.action("تحديث", new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
        load();
    }

    private void load() {
        getJson("/admin/ops/summary?days=" + days, new JsonDone() {
            @Override
            public void ok(JSONObject d, JSONObject root) {
                render(d);
            }
        });
    }

    private String playLabel(String key) {
        if ("movie".equals(key)) return "الأفلام";
        if ("episode".equals(key)) return "الحلقات";
        if ("other".equals(key)) return "أخرى";
        return key;
    }

    private String shortDay(String day) {
        // yyyy-MM-dd -> dd/MM
        if (day != null && day.length() >= 10) return day.substring(8, 10) + "/" + day.substring(5, 7);
        return day == null ? "" : day;
    }

    private void render(JSONObject d) {
        if (!alive()) return;
        LinearLayout c = scrollBody();
        c.addView(header());

        if (!d.optBoolean("hasData", false)) {
            c.addView(CgUi.empty(this, "لا توجد بيانات بعد",
                    "لم يصل أي حدث في هذه الفترة. انشر الـ Worker وتأكد أن المستخدمين على نسخة تُرسل إلى /telemetry/events (النسخ القديمة لا تظهر هنا)."));
            return;
        }
        int pending = d.optInt("pending", 0);
        if (pending > 0 || d.optBoolean("partial", false)) {
            String note = pending > 0 ? "تتضمن " + pending + " دفعة لم تُدمج بعد (أرقام حيّة)." : "";
            if (d.optBoolean("partial", false)) note += (note.length() > 0 ? " " : "") + "الأرقام قد تكون ناقصة قليلاً لكثرة الدفعات المعلقة.";
            c.addView(verdictCard("info", "ملاحظة", note));
        }

        long errors = d.optLong("errors", 0);
        long crashes = d.optLong("crashes", 0);
        c.addView(kpiRow(
                kpi("مستخدمون (تقدير ±6%)", "~" + n(d.optDouble("users", 0)), CgCfg.TEXT),
                kpi("جلسات", n(d.optDouble("sessions", 0)), CgCfg.TEXT),
                kpi("فتح التطبيق", n(d.optDouble("appOpens", 0)), CgCfg.TEXT)));
        c.addView(kpiRow(
                kpi("أخطاء", n(errors), errors > 0 ? CgCfg.BAD : CgCfg.GOOD),
                kpi("انهيارات", n(crashes), crashes > 0 ? CgCfg.BAD : CgCfg.GOOD),
                kpi("أيام مشاهدة فعّالة", n(d.optDouble("engagedDays", 0)), CgCfg.TEXT)));

        // ---- daily charts
        JSONArray daily = d.optJSONArray("daily");
        if (daily != null && daily.length() > 0) {
            int n = daily.length();
            double[] sessions = new double[n];
            double[] users = new double[n];
            double[] plays = new double[n];
            String[] labels = new String[n];
            for (int i = 0; i < n; i++) {
                JSONObject r = daily.optJSONObject(i);
                if (r == null) continue;
                sessions[i] = r.optDouble("sessions", 0);
                users[i] = r.optDouble("users", 0);
                plays[i] = r.optDouble("plays", 0);
                labels[i] = shortDay(r.optString("day", ""));
            }
            c.addView(CgUi.sectionTitle(this, "حسب اليوم"));
            c.addView(chartCard("الجلسات", sessions, labels, CgCfg.STROKE));
            c.addView(chartCard("المستخدمون (تقدير)", users, labels, CgCfg.ACCENT));
            c.addView(chartCard("التشغيلات", plays, labels, CgCfg.GOOD));
        }

        // ---- peak hours
        JSONArray hours = d.optJSONArray("hours");
        if (hours != null && hours.length() == 24) {
            double[] hv = new double[24];
            String[] hl = new String[24];
            for (int i = 0; i < 24; i++) {
                hv[i] = hours.optDouble(i, 0);
                hl[i] = String.valueOf(i);
            }
            c.addView(CgUi.sectionTitle(this, "أوقات الذروة"));
            c.addView(chartCard("بدء الجلسات حسب الساعة (بتوقيت بغداد)", hv, hl, CgCfg.WARN));
        }

        // ---- watch funnel
        JSONObject plays = d.optJSONObject("plays");
        if (plays != null && plays.length() > 0) {
            c.addView(CgUi.sectionTitle(this, "قمع المشاهدة"));
            Iterator<String> it = plays.keys();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject row = plays.optJSONObject(key);
                if (row == null) continue;
                double started = row.optDouble("started", 0);
                LinearLayout card = CgUi.card(this);
                card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
                card.addView(CgUi.text(this, playLabel(key), 14, CgCfg.TEXT, true));
                card.addView(kv("بدأ التشغيل", n(started), CgCfg.TEXT));
                card.addView(kv("شاهد دقيقتين", n(row.optDouble("m2", 0)) + "  (" + pct(row.optDouble("m2", 0), started) + ")", CgCfg.TEXT));
                card.addView(kv("وصل إلى 50%", n(row.optDouble("half", 0)) + "  (" + pct(row.optDouble("half", 0), started) + ")", CgCfg.TEXT));
                card.addView(kv("أكمل المشاهدة", n(row.optDouble("complete", 0)) + "  (" + pct(row.optDouble("complete", 0), started) + ")", CgCfg.GOOD));
                c.addView(card);
            }
        }

        c.addView(CgUi.sectionTitle(this, "الأكثر تشغيلاً"));
        c.addView(rankCard("معرّفات المحتوى (مسلسل أو فيلم)", d.optJSONArray("topContent"), 10));

        // ---- speed
        JSONObject perf = d.optJSONObject("perf");
        if (perf != null && perf.length() > 0) {
            c.addView(CgUi.sectionTitle(this, "السرعة (متوسط)"));
            LinearLayout card = CgUi.card(this);
            card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
            ArrayList<String> keys = new ArrayList<String>();
            Iterator<String> it = perf.keys();
            while (it.hasNext()) keys.add(it.next());
            java.util.Collections.sort(keys);
            for (int i = 0; i < keys.size(); i++) {
                JSONObject m = perf.optJSONObject(keys.get(i));
                if (m == null) continue;
                long ms = m.optLong("avgMs", 0);
                int color = ms > 3000 ? CgCfg.BAD : (ms > 1500 ? CgCfg.WARN : CgCfg.GOOD);
                card.addView(kv(keys.get(i) + "  (" + m.optInt("samples", 0) + " عينة)", n(ms) + " ms", color));
            }
            c.addView(card);
        }

        c.addView(CgUi.sectionTitle(this, "الأجهزة والإصدارات"));
        c.addView(rankCard("إصدارات التطبيق", d.optJSONArray("versions"), 12));
        c.addView(rankCard("إصدارات أندرويد (API)", d.optJSONArray("os"), 12));
        c.addView(rankCard("الشاشات الأكثر زيارة", d.optJSONArray("screens"), 15));
        c.addView(rankCard("أكثر الأحداث", d.optJSONArray("topEvents"), 25));
    }
}
