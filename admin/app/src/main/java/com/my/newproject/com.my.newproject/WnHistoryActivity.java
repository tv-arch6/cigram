package com.my.newproject;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * «ما الجديد؟» — synchronisation history + settings: execution time, discovered items, resolved videos, available
 * subtitles, imported items, failures with their error details; the automatic-sync switch and interval; and which
 * providers are configured on the Worker (no secret is ever shown or sent).
 */
public class WnHistoryActivity extends CgBase {

    private LinearLayout settings;
    private LinearLayout runs;
    private JSONObject config;
    private JSONObject providers;

    // editable copy
    private boolean auto;
    private boolean tg = true;
    private JSONObject cron;
    private int interval = 360;
    private int enrich = 8;

    @Override
    protected String screenTitle() {
        return "سجل المزامنة والإعدادات";
    }

    @Override
    protected void onBuild() {
        LinearLayout c = scrollBody();
        settings = CgUi.vbox(this);
        c.addView(settings);
        c.addView(DxUi.heading(this, "سجل المزامنة"));
        runs = CgUi.vbox(this);
        c.addView(runs);
        bar.action("تحديث", new Runnable() {
            @Override public void run() { load(); }
        });
        load();
    }

    private void load() {
        settings.removeAllViews();
        runs.removeAllViews();
        runs.addView(CxUi.skeleton(this, 3));
        CgHttp.async(this, new CgHttp.Work<JSONObject[]>() {
            @Override public JSONObject[] run() throws Exception {
                return new JSONObject[]{WnApi.config(), WnApi.history()};
            }
        }, new CgHttp.Done<JSONObject[]>() {
            @Override public void ok(JSONObject[] r) {
                if (!alive()) return;
                config = r[0].optJSONObject("config");
                providers = r[0].optJSONObject("providers");
                cron = r[0].optJSONObject("cron");
                if (config != null) {
                    auto = config.optBoolean("auto_sync");
                    tg = config.optBoolean("notify_telegram", true);
                    interval = config.optInt("interval_minutes", 360);
                    enrich = config.optInt("enrich_limit", 8);
                }
                buildSettings();
                buildRuns(r[1]);
            }

            @Override public void fail(String message) {
                if (!alive()) return;
                runs.removeAllViews();
                runs.addView(CgUi.errorCard(WnHistoryActivity.this, message, new Runnable() {
                    @Override public void run() { load(); }
                }));
            }
        });
    }

    // ------------------------------------------------------------------ settings

    private static final int[] INTERVALS = {30, 60, 180, 360, 720, 1440};
    private static final int[] ENRICH = {0, 4, 8, 12, 16};

    private static String intervalLabel(int m) {
        return m < 60 ? m + " د" : m % 60 == 0 ? (m / 60) + " س" : m + " د";
    }

    private void buildSettings() {
        settings.removeAllViews();
        LinearLayout box = WnUi.glass(this);
        box.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        box.addView(CgUi.text(this, "المزامنة التلقائية", 16, CgCfg.TEXT, true));
        box.addView(CgUi.muted(this, "تعمل عبر Cron Trigger الموجود في الـ Worker (كل دقيقة يفحص هل حان الموعد). لوحة التحكم لا تستدعي TMDB أبداً — الأرقام تُقرأ من آخر مزامنة."));
        if (cron != null) {
            boolean ok = cron.optBoolean("healthy", false);
            TextView cs = CgUi.text(this, ok ? "✓ المؤشر الزمني يعمل (آخر نبضة قبل " + cron.optInt("age_seconds") + " ث)"
                    : "✕ المؤشر الزمني (Cron) متوقف — فعّله من Cloudflare ← Settings ← Triggers ← * * * * *", 12.5f, ok ? CgCfg.GOOD : CgCfg.BAD, true);
            cs.setPadding(0, CgUi.dp(this, 6), 0, CgUi.dp(this, 4));
            box.addView(cs);
        }
        CgUi.Toggle t = CgUi.toggle(this, "مزامنة تلقائية", auto);
        t.onChange(new Runnable() { @Override public void run() { auto = !auto; } });
        box.addView(t);
        CgUi.Toggle tgt = CgUi.toggle(this, "تنبيه تليكرام عند ظهور عناوين جديدة (بدون فتح التطبيق)", tg);
        tgt.onChange(new Runnable() { @Override public void run() { tg = !tg; } });
        box.addView(tgt);
        TextView l1 = CgUi.muted(this, "الفاصل بين المزامنات");
        l1.setPadding(0, CgUi.dp(this, 6), 0, CgUi.dp(this, 4));
        box.addView(l1);
        CgUi.ChipBar iv = new CgUi.ChipBar(this);
        final List<TextView> ivChips = new ArrayList<>();
        for (final int m : INTERVALS) {
            final TextView c = iv.add(intervalLabel(m), m == interval);
            ivChips.add(c);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    interval = m;
                    for (int i = 0; i < INTERVALS.length; i++) CgUi.setChipState(ivChips.get(i), INTERVALS[i] == interval);
                }
            });
        }
        box.addView(iv.scroll, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, -12, 0, -12, 0));
        TextView l2 = CgUi.muted(this, "عدد العناوين التي يُفحص فيديوها وترجمتها في كل مزامنة (يحدّ استهلاك الطلبات الخارجية)");
        l2.setPadding(0, CgUi.dp(this, 8), 0, CgUi.dp(this, 4));
        box.addView(l2);
        CgUi.ChipBar en = new CgUi.ChipBar(this);
        final List<TextView> enChips = new ArrayList<>();
        for (final int n : ENRICH) {
            final TextView c = en.add(String.valueOf(n), n == enrich);
            enChips.add(c);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    enrich = n;
                    for (int i = 0; i < ENRICH.length; i++) CgUi.setChipState(enChips.get(i), ENRICH[i] == enrich);
                }
            });
        }
        box.addView(en.scroll, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, -12, 0, -12, 0));
        TextView save = CgUi.button(this, "حفظ الإعدادات", 0);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveSettings(); }
        });
        box.addView(save, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        settings.addView(box);

        LinearLayout p = WnUi.glass(this);
        p.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));
        p.addView(CgUi.text(this, "المصادر المضبوطة في الـ Worker", 15, CgCfg.TEXT, true));
        if (providers != null) {
            line(p, "TMDB (اكتشاف البيانات)", providers.optBoolean("tmdb"), "أضف TMDB_API_KEY أو TMDB_READ_TOKEN");
            line(p, "SubDL (ترجمة)", providers.optBoolean("subdl"), "أضف SUBDL_API_KEY");
            line(p, "SubSource (ترجمة احتياطية)", providers.optBoolean("subsource"), "أضف SUBSOURCE_API_KEY");
            line(p, "OpenSubtitles (ترجمة إضافية)", providers.optBoolean("opensubtitles"), "أضف OPENSUBTITLES_API_KEY (+ USERNAME / PASSWORD للتنزيل)");
            JSONArray vp = providers.optJSONArray("video_providers");
            for (int i = 0; vp != null && i < vp.length(); i++) {
                JSONObject v = vp.optJSONObject(i);
                if (v == null) continue;
                line(p, "فيديو: " + v.optString("name"), v.optBoolean("enabled"), "معطّل");
                if (v.optString("note").length() > 0) {
                    TextView n = CgUi.text(this, "   ℹ " + v.optString("note"), 11.5f, CgCfg.MUTED, false);
                    p.addView(n);
                }
            }
        }
        settings.addView(p);
    }

    private void line(LinearLayout box, String name, boolean ok, String missingHint) {
        TextView t = CgUi.text(this, (ok ? "✓  " : "✕  ") + name + (ok ? "" : " — " + missingHint), 12.5f, ok ? CgCfg.GOOD : CgCfg.WARN, false);
        t.setPadding(0, CgUi.dp(this, 4), 0, 0);
        box.addView(t);
    }

    private void saveSettings() {
        final JSONObject patch = new JSONObject();
        CxJ.put(patch, "auto_sync", auto);
        CxJ.put(patch, "notify_telegram", tg);
        CxJ.put(patch, "interval_minutes", interval);
        CxJ.put(patch, "enrich_limit", enrich);
        showLoading("جارٍ الحفظ…");
        CgHttp.async(this, new CgHttp.Work<JSONObject>() {
            @Override public JSONObject run() throws Exception { return WnApi.saveConfig(patch); }
        }, new CgHttp.Done<JSONObject>() {
            @Override public void ok(JSONObject d) {
                hideLoading();
                if (!alive()) return;
                WnUi.dirty = true;
                CgUi.toast(WnHistoryActivity.this, "تم حفظ الإعدادات.");
                load();
            }

            @Override public void fail(String message) {
                hideLoading();
                if (alive()) CgUi.info(WnHistoryActivity.this, "تعذر الحفظ", message);
            }
        });
    }

    // ------------------------------------------------------------------ runs

    private void buildRuns(JSONObject h) {
        runs.removeAllViews();
        JSONArray arr = h.optJSONArray("runs");
        String last = h.optString("last_success_at");
        TextView head = CgUi.text(this, last.length() > 0 ? "آخر مزامنة ناجحة: " + CgUi.localText(last) + " (" + CgUi.ago(last) + ")" : "لم تنجح أي مزامنة بعد.", 13, last.length() > 0 ? CgCfg.GOOD : CgCfg.WARN, true);
        head.setPadding(CgUi.dp(this, 4), 0, 0, CgUi.dp(this, 4));
        runs.addView(head);
        TextView imp = CgUi.muted(this, "إجمالي ما استُورد عبر «ما الجديد؟»: " + h.optInt("total_imports"));
        imp.setPadding(CgUi.dp(this, 4), 0, 0, CgUi.dp(this, 8));
        runs.addView(imp);
        if (arr == null || arr.length() == 0) {
            runs.addView(CgUi.empty(this, "لا يوجد سجل بعد", "اضغط «مزامنة الآن» من شاشة «ما الجديد؟»."));
        }
        for (int i = 0; arr != null && i < arr.length(); i++) runs.addView(runCard(arr.optJSONObject(i)));
        JSONArray ri = h.optJSONArray("recent_imports");
        if (ri != null && ri.length() > 0) {
            runs.addView(DxUi.heading(this, "آخر عمليات الاستيراد"));
            for (int i = 0; i < ri.length(); i++) {
                JSONObject it = ri.optJSONObject(i);
                if (it == null) continue;
                LinearLayout r = WnUi.glass(this);
                r.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));
                r.addView(CgUi.text(this, it.optString("title") + " · " + WnLogic.typeLabel(it.optString("type")), 14, CgCfg.TEXT, true));
                String verdicts = "";
                JSONArray vs = it.optJSONArray("videos");
                if (vs != null && vs.length() > 0) verdicts = WnLogic.verdictLabel(vs.optJSONObject(0).optString("verdict"));
                r.addView(CgUi.muted(this, CgUi.ago(it.optString("at")) + " · " + it.optInt("created") + " عنصر" + (verdicts.length() > 0 ? " · " + verdicts : "") + (it.optBoolean("ack_unverified") ? " · بإقرار" : "")));
                runs.addView(r);
            }
        }
    }

    private View runCard(JSONObject run) {
        LinearLayout box = WnUi.glass(this);
        box.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        String status = run.optString("status");
        int color = "ok".equals(status) ? CgCfg.GOOD : "partial".equals(status) ? CgCfg.WARN : CgCfg.BAD;
        LinearLayout head = CgUi.hbox(this);
        LinearLayout t = CgUi.vbox(this);
        t.addView(CgUi.text(this, CgUi.localText(run.optString("at")), 14, CgCfg.TEXT, true));
        t.addView(CgUi.muted(this, ("cron".equals(run.optString("trigger")) ? "تلقائية" : "يدوية") + " · " + (run.optLong("ms") / 1000.0) + " ث"));
        head.addView(t, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        head.addView(WnUi.badge(this, "ok".equals(status) ? "نجحت" : "partial".equals(status) ? "جزئية" : "فشلت", color), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        box.addView(head);
        box.addView(DxUi.row(this,
                DxUi.kpi(this, "اكتُشف", DxUi.num(run.optInt("discovered")), CgCfg.TEXT),
                DxUi.kpi(this, "جديد", DxUi.num(run.optInt("new_items")), CgCfg.CYAN),
                DxUi.kpi(this, "استُورد", DxUi.num(run.optInt("imported")), CgCfg.GOOD)));
        box.addView(DxUi.row(this,
                DxUi.kpi(this, "فيديو متاح", DxUi.num(run.optInt("videos_resolved")), CgCfg.GOOD),
                DxUi.kpi(this, "ترجمات", DxUi.num(run.optInt("subtitles_available")), CgCfg.GOOD),
                DxUi.kpi(this, "أخطاء", DxUi.num(run.optInt("failed")), run.optInt("failed") > 0 ? CgCfg.BAD : CgCfg.MUTED)));
        JSONArray errs = run.optJSONArray("errors");
        for (int i = 0; errs != null && i < errs.length() && i < 6; i++) {
            JSONObject e = errs.optJSONObject(i);
            if (e == null) continue;
            TextView et = CgUi.text(this, "• " + e.optString("op") + ": " + e.optString("message"), 11.5f, CgCfg.BAD, false);
            et.setPadding(0, CgUi.dp(this, 3), 0, 0);
            box.addView(et);
        }
        return box;
    }
}
