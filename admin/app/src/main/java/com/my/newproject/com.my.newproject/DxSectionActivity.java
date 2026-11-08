package com.my.newproject;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One page per section of the home screen. Every page has the same shape: a short line, the numbers and charts of that
 * section (loaded from the server), then big rows for what you can do there. Pick the section with {@link #open}.
 */
public class DxSectionActivity extends CgBase {

    static final String EXTRA_KEY = "key";

    private String key = "content";
    private LinearLayout stats;

    static void open(Activity a, String key) {
        Intent in = new Intent(a, DxSectionActivity.class);
        in.putExtra(EXTRA_KEY, key);
        a.startActivity(in);
    }

    private String key() {
        String k = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_KEY);
        return k == null ? "content" : k;
    }

    @Override
    protected String screenTitle() {
        String k = key();
        if ("channels".equals(k)) return "القنوات";
        if ("home".equals(k)) return "الرئيسية والواجهة";
        if ("notify".equals(k)) return "الإشعارات والطلبات";
        if ("ads".equals(k)) return "الإعلانات";
        if ("stats".equals(k)) return "الإحصائيات";
        if ("system".equals(k)) return "التحديث والصيانة";
        return "المحتوى";
    }

    @Override
    protected void onBuild() {
        key = key();
        LinearLayout c = scrollBody();
        c.addView(sectionHero());
        stats = CgUi.vbox(this);
        c.addView(stats, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        LinearLayout actions = CgUi.vbox(this);
        c.addView(DxUi.heading(this, "ماذا تريد أن تفعل؟"));
        c.addView(actions, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        fillActions(actions);
        bar.action("تحديث", new Runnable() {
            @Override public void run() { loadStats(true); }
        });
        loadStats(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if ("content".equals(key) && CgData.loaded() && stats != null && stats.getChildCount() > 0) DxPanels.content(this, stats);
    }

    // ------------------------------------------------------------------ section hero

    /** A coloured header card at the top of each section page: gradient icon, the section name and one line. */
    private View sectionHero() {
        int icon;
        int color;
        String sub;
        if ("channels".equals(key)) { icon = CgIcons.SIGNAL; color = NxUi.TEAL; sub = "البث المباشر والباقات"; }
        else if ("home".equals(key)) { icon = CgIcons.IMAGE; color = NxUi.VIOLET; sub = "الأقسام والكاروسيل والمميّز"; }
        else if ("notify".equals(key)) { icon = CgIcons.BELL; color = NxUi.ORANGE; sub = "رسائل المستخدمين والتنبيهات"; }
        else if ("ads".equals(key)) { icon = CgIcons.MEGAPHONE; color = NxUi.PINK; sub = "المعلنون والحملات والأرباح"; }
        else if ("stats".equals(key)) { icon = CgIcons.CHART; color = NxUi.UP; sub = "المستخدمون والأخطاء والصحة"; }
        else if ("system".equals(key)) { icon = CgIcons.GEAR; color = CgCfg.GRAY; sub = "إصدار جديد وإيقاف مؤقت"; }
        else { icon = CgIcons.FILM; color = NxUi.BLUE; sub = "أفلام ومسلسلات وحلقات"; }

        LinearLayout hero = CgUi.hbox(this);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setBackground(CgUi.gradient(this, CgUi.alpha(color, 0x33), CgCfg.CARD, 20));
        CgUi.elevate(hero, 4);
        int p = CgUi.dp(this, 16);
        hero.setPadding(p, p, p, p);

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(CgIcons.icon(icon, color));
        int ip = CgUi.dp(this, 13);
        iv.setPadding(ip, ip, ip, ip);
        iv.setBackground(CgUi.gradient(this, CgUi.alpha(color, 0x55), CgUi.alpha(color, 0x12), 26));
        hero.addView(iv, new LinearLayout.LayoutParams(CgUi.dp(this, 52), CgUi.dp(this, 52)));

        LinearLayout col = CgUi.vbox(this);
        col.addView(CgUi.text(this, screenTitle(), 19, CgCfg.TEXT, true));
        col.addView(CgUi.muted(this, sub));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        cl.setMarginStart(CgUi.dp(this, 14));
        hero.addView(col, cl);

        hero.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 2, 2, 2, 14));
        return hero;
    }

    // ------------------------------------------------------------------ the rows (what you can do)

    private Runnable go(final Class<?> cls) {
        return new Runnable() {
            @Override public void run() { startActivity(new Intent(DxSectionActivity.this, cls)); }
        };
    }

    private void fillActions(LinearLayout box) {
        final Activity a = this;
        if ("content".equals(key)) {
            box.addView(DxUi.item(this, CgIcons.STAR, "ما الجديد؟", "اكتشف الجديد وأضفه تلقائياً مع الفيديو والترجمة", "جديد", NxUi.PINK, go(WnActivity.class)));
            box.addView(DxUi.item(this, CgIcons.FILM, "إضافة فيلم", "ابحث بالاسم وأضف الفيلم برابطه أو من جوالك", "", NxUi.BLUE, go(NxMovieActivity.class)));
            box.addView(DxUi.item(this, CgIcons.TV, "إضافة مسلسل", "مسلسل جديد بمواسمه وحلقاته", "", NxUi.VIOLET, go(NxSeriesActivity.class)));
            box.addView(DxUi.item(this, CgIcons.PLUS, "إضافة حلقة", "حلقة جديدة لمسلسل موجود", "", NxUi.TEAL, new Runnable() {
                @Override public void run() { CxNav.addEpisode(a, "", 0, 0); }
            }));
            box.addView(DxUi.item(this, CgIcons.FOLDER, "إضافة موسم كامل", "موسم دفعة واحدة بالروابط", "", NxUi.TEAL, new Runnable() {
                @Override public void run() { CxNav.addSeason(a, "", 0); }
            }));
            box.addView(DxUi.item(this, CgIcons.UPLOAD, "رفع موسم من الجوال", "اختر الملفات وترفع وتنشر تلقائياً", "", NxUi.ORANGE, new Runnable() {
                @Override public void run() { CxNav.seasonUpload(a, "", 0); }
            }));
            box.addView(DxUi.item(this, CgIcons.SEND, "إضافة سريعة", "لصق رابط وإنهاء بخطوة واحدة", "", NxUi.PINK, go(QuickAddActivity.class)));
            box.addView(DxUi.item(this, CgIcons.SEARCH, "كل المحتوى", "بحث وفلترة وتحرير وحذف", "", CgCfg.ACCENT, new Runnable() {
                @Override public void run() { DxPanels.openContent(a, null, -1); }
            }));
            int drafts = 0;
            try { drafts = CxDraft.count(this); } catch (Throwable ignored) { }
            box.addView(DxUi.item(this, CgIcons.DRAFT, "المسودات", "ما بدأت به ولم تكمله", drafts > 0 ? String.valueOf(drafts) : "", CgCfg.WARN, new Runnable() {
                @Override public void run() { CxNav.drafts(a); }
            }));
            box.addView(DxUi.item(this, CgIcons.FILE, "الترجمة", "رفع ملفات الترجمة وربطها", "", CgCfg.ACCENT, go(CxSubsActivity.class)));
            box.addView(DxUi.item(this, CgIcons.CLIP, "الرفع والتحميلات", "متابعة الملفات الجارية والفاشلة", "", CgCfg.ACCENT, go(CgUploadsActivity.class)));
            box.addView(DxUi.item(this, CgIcons.COPY, "دمج العناوين", "ضم المكرر في عنوان واحد", "", CgCfg.GRAY, go(CgMergeActivity.class)));
        } else if ("channels".equals(key)) {
            box.addView(DxUi.item(this, CgIcons.PLUS, "إضافة قناة", "رابط البث والشعار والباقة", "", NxUi.TEAL, go(NxChannelActivity.class)));
            box.addView(DxUi.item(this, CgIcons.SIGNAL, "إدارة القنوات", "ترتيب وإخفاء وفحص الروابط", "", NxUi.BLUE, go(CgChannelsActivity.class)));
        } else if ("home".equals(key)) {
            box.addView(DxUi.item(this, CgIcons.IMAGE, "الكاروسيل", "الشرائح المتحركة أعلى الرئيسية", "", NxUi.VIOLET, new Runnable() {
                @Override public void run() { CxNav.carouselManage(a); }
            }));
            box.addView(DxUi.item(this, CgIcons.PLUS, "إضافة شريحة", "شريحة جديدة للكاروسيل", "", NxUi.TEAL, new Runnable() {
                @Override public void run() { CxNav.carouselAdd(a, ""); }
            }));
            box.addView(DxUi.item(this, CgIcons.FOLDER, "الأقسام", "أسماء الأقسام وما يظهر منها", "", NxUi.BLUE, go(CgSectionsActivity.class)));
            box.addView(DxUi.item(this, CgIcons.FILE, "ترتيب الرئيسية", "رتّب الأقسام كما تريد", "", NxUi.ORANGE, go(CgHomeOrderActivity.class)));
            box.addView(DxUi.item(this, CgIcons.STAR, "المحتوى المميز", "ما يظهر في الواجهة أولاً", "", CgCfg.WARN, go(CgFeaturedActivity.class)));
        } else if ("notify".equals(key)) {
            box.addView(DxUi.item(this, CgIcons.SEND, "إرسال إشعار", "إشعار فوري لكل المستخدمين", "", NxUi.ORANGE, go(NxNotifyActivity.class)));
            box.addView(DxUi.item(this, CgIcons.HISTORY, "الرسائل العامة", "رسائل تظهر داخل التطبيق بجدولة", "", NxUi.BLUE, go(CgAnnouncementsActivity.class)));
            box.addView(DxUi.item(this, CgIcons.CHAT, "طلبات المستخدمين", "أفلام ومسلسلات وقنوات طلبها المشاهدون", "", NxUi.PINK, go(NxRequestsActivity.class)));
        } else if ("ads".equals(key)) {
            box.addView(DxUi.item(this, CgIcons.CHAT, "صندوق المعلنين", "رسائل الشركات ومحادثاتها", "", NxUi.PINK, go(AdInboxActivity.class)));
            box.addView(DxUi.item(this, CgIcons.MEGAPHONE, "الحملات", "إنشاء ومراجعة وموافقة وتقارير", "", NxUi.ORANGE, go(AdCampaignsActivity.class)));
            box.addView(DxUi.item(this, CgIcons.USERS, "المعلنون", "ملفات الشركات وسجل حملاتها", "", NxUi.BLUE, go(AdAdvertisersActivity.class)));
            box.addView(DxUi.item(this, CgIcons.GEAR, "الأسعار والإعدادات", "المساحات والأسعار والسياسات", "", CgCfg.GRAY, go(AdSettingsActivity.class)));
            box.addView(DxUi.item(this, CgIcons.FLAG, "بلاغات الإعلانات", "حملات أبلغ عنها المستخدمون", "", CgCfg.BAD, go(AdReportsActivity.class)));
            box.addView(DxUi.item(this, CgIcons.SHIELD, "تفعيل وإيقاف الإعلانات", "المفتاح الرئيسي لكل موضع", "", NxUi.TEAL, go(OpsAdsActivity.class)));
            box.addView(DxUi.item(this, CgIcons.CHART, "فحص الإعلانات", "نسبة التعبئة وأسباب الفشل", "", NxUi.UP, go(OpsAdCheckActivity.class)));
            box.addView(DxUi.item(this, CgIcons.EYE, "خريطة الإعلانات", "أين ومتى يظهر كل إعلان", "", NxUi.VIOLET, go(OpsAdMapActivity.class)));
        } else if ("stats".equals(key)) {
            box.addView(DxUi.item(this, CgIcons.CHART, "الإحصائيات التفصيلية", "رسوم اليوم والساعة والإصدارات", "", NxUi.UP, go(OpsStatsActivity.class)));
            box.addView(DxUi.item(this, CgIcons.WARNING, "أخطاء التطبيق", "انهيارات ومشاكل التشغيل مجمّعة", "", CgCfg.BAD, go(OpsErrorsActivity.class)));
            box.addView(DxUi.item(this, CgIcons.SIGNAL, "صحة النظام", "الخوادم والملفات وزمن الاستجابة", "", NxUi.TEAL, go(CgHealthActivity.class)));
        } else {
            box.addView(DxUi.item(this, CgIcons.UPDATE, "تحديث التطبيق", "نشر إصدار جديد وجدولته", "", NxUi.BLUE, go(CgUpdateActivity.class)));
            box.addView(DxUi.item(this, CgIcons.LOCK, "وضع الصيانة", "إيقاف التطبيق مؤقتاً برسالة", "", CgCfg.WARN, go(CgMaintenanceActivity.class)));
            box.addView(DxUi.item(this, CgIcons.HISTORY, "سجل العمليات والنسخ", "تراجع واستعادة النسخ الاحتياطية", "", NxUi.VIOLET, go(CgHistoryActivity.class)));
            box.addView(DxUi.item(this, CgIcons.SIGNAL, "صحة النظام", "الخوادم والملفات وزمن الاستجابة", "", NxUi.TEAL, go(CgHealthActivity.class)));
        }
    }

    // ------------------------------------------------------------------ the numbers and charts (loaded)

    private void loadStats(boolean force) {
        stats.removeAllViews();
        if ("content".equals(key)) {
            stats.addView(CxUi.skeleton(this, 2));
            if (force) reloadData(new Runnable() { @Override public void run() { if (alive()) DxPanels.content(DxSectionActivity.this, stats); } });
            else ensureData(new Runnable() { @Override public void run() { if (alive()) DxPanels.content(DxSectionActivity.this, stats); } });
        } else if ("channels".equals(key)) {
            loadChannels();
        } else if ("home".equals(key)) {
            stats.addView(CxUi.skeleton(this, 1));
            Runnable r = new Runnable() { @Override public void run() { if (alive()) homeNumbers(); } };
            if (force) reloadData(r); else ensureData(r);
        } else if ("ads".equals(key)) {
            loadAds();
        } else if ("stats".equals(key)) {
            loadOps();
        } else {
            stats.addView(CgUi.muted(this, "إدارة نسخة التطبيق وحالة الخدمة."));
        }
    }

    private void fail(String message, final boolean force) {
        if (!alive()) return;
        stats.removeAllViews();
        stats.addView(CgUi.errorCard(this, message, new Runnable() {
            @Override public void run() { loadStats(true); }
        }), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
    }

    private void homeNumbers() {
        stats.removeAllViews();
        CgData d = CgData.cur();
        if (d == null) return;
        int[] n = d.counts();
        stats.addView(DxUi.row(this,
                DxUi.kpi(this, "أقسام", DxUi.num(d.sections.size()), CgCfg.TEXT),
                DxUi.kpi(this, "مميّز", DxUi.num(n[5]), CgCfg.WARN),
                DxUi.kpi(this, "مخفي", DxUi.num(n[6]), CgCfg.GRAY)));
    }

    private void loadChannels() {
        stats.addView(CxUi.skeleton(this, 2));
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgHttp.call("GET", CgCfg.CHANNELS_API + "/channels", null, false, 20000, null));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res r) {
                if (!alive()) return;
                stats.removeAllViews();
                JSONArray cats = r.json == null ? null : r.json.optJSONArray("categories");
                int total = 0, off = 0;
                int n = cats == null ? 0 : cats.length();
                double[] per = new double[Math.min(n, 8)];
                String[] names = new String[per.length];
                for (int i = 0; cats != null && i < n; i++) {
                    JSONObject cat = cats.optJSONObject(i);
                    JSONArray chs = cat == null ? null : cat.optJSONArray("channels");
                    int cnt = chs == null ? 0 : chs.length();
                    total += cnt;
                    for (int j = 0; chs != null && j < chs.length(); j++) {
                        JSONObject ch = chs.optJSONObject(j);
                        if (ch != null && !ch.optBoolean("available", true)) off++;
                    }
                    if (i < per.length) {
                        per[i] = cnt;
                        String nm = cat == null ? "" : cat.optString("name", "");
                        names[i] = nm.length() > 7 ? nm.substring(0, 7) : nm;
                    }
                }
                stats.addView(DxUi.row(DxSectionActivity.this,
                        DxUi.kpi(DxSectionActivity.this, "قنوات", DxUi.num(total), CgCfg.TEXT),
                        DxUi.kpi(DxSectionActivity.this, "باقات", DxUi.num(n), CgCfg.TEXT),
                        DxUi.kpi(DxSectionActivity.this, "غير متاحة", DxUi.num(off), off > 0 ? CgCfg.BAD : CgCfg.GOOD)));
                if (per.length > 1) {
                    DxUi.BarChart bars = new DxUi.BarChart(DxSectionActivity.this);
                    bars.setData(per, names, NxUi.TEAL);
                    stats.addView(DxUi.chartCard(DxSectionActivity.this, "القنوات حسب الباقة", "", bars, 150));
                }
            }

            @Override public void fail(String message) {
                DxSectionActivity.this.fail(message, false);
            }
        });
    }

    private void loadAds() {
        stats.addView(CxUi.skeleton(this, 2));
        AdApi.get(this, "/admin/overview", new AdApi.Cb() {
            @Override public void on(AdApi.Result ov) {
                if (!alive()) return;
                if (!ov.ok) { fail(ov.message, false); return; }
                stats.removeAllViews();
                JSONObject o = ov.json;
                JSONObject counts = o.optJSONObject("counts");
                int review = counts == null ? 0 : counts.optInt("received") + counts.optInt("review");
                int flagged = o.optInt("flagged");
                stats.addView(DxUi.row(DxSectionActivity.this,
                        DxUi.kpi(DxSectionActivity.this, "تعمل الآن", DxUi.num(o.optInt("running")), CgCfg.GOOD),
                        DxUi.kpi(DxSectionActivity.this, "معلنون", DxUi.num(o.optInt("advertisers")), CgCfg.TEXT),
                        DxUi.kpi(DxSectionActivity.this, "للمراجعة", DxUi.num(review), review > 0 ? CgCfg.WARN : CgCfg.MUTED)));
                stats.addView(DxUi.row(DxSectionActivity.this,
                        DxUi.kpi(DxSectionActivity.this, "الإيراد المدفوع", AdApi.money(o.optDouble("revenue", 0), ""), CgCfg.GOOD),
                        DxUi.kpi(DxSectionActivity.this, "مستحق", AdApi.money(o.optDouble("outstanding", 0), ""), CgCfg.WARN),
                        DxUi.kpi(DxSectionActivity.this, "بلاغات", DxUi.num(flagged), flagged > 0 ? CgCfg.BAD : CgCfg.MUTED)));
            }
        });
    }

    private void loadOps() {
        stats.addView(CxUi.skeleton(this, 2));
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgHttp.apiGet("/admin/ops/summary?days=7"));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res r) {
                if (!alive()) return;
                stats.removeAllViews();
                JSONObject d = r.json == null ? null : r.json.optJSONObject("data");
                if (d == null || !d.optBoolean("hasData", false)) {
                    stats.addView(CgUi.muted(DxSectionActivity.this, "لا توجد بيانات بعد. تأكد من نشر الـ Worker ومن أن المستخدمين على نسخة ترسل الأحداث."));
                    return;
                }
                long errors = d.optLong("errors", 0);
                long crashes = d.optLong("crashes", 0);
                stats.addView(DxUi.row(DxSectionActivity.this,
                        DxUi.kpi(DxSectionActivity.this, "مستخدمون (تقدير)", "~" + DxUi.num(d.optDouble("users", 0)), CgCfg.TEXT),
                        DxUi.kpi(DxSectionActivity.this, "جلسات", DxUi.num(d.optDouble("sessions", 0)), CgCfg.TEXT),
                        DxUi.kpi(DxSectionActivity.this, "أخطاء", DxUi.num(errors + crashes), errors + crashes > 0 ? CgCfg.BAD : CgCfg.GOOD)));
                JSONArray daily = d.optJSONArray("daily");
                if (daily != null && daily.length() > 0) {
                    int n = daily.length();
                    double[] sessions = new double[n];
                    double[] users = new double[n];
                    String[] labels = new String[n];
                    for (int i = 0; i < n; i++) {
                        JSONObject row = daily.optJSONObject(i);
                        if (row == null) continue;
                        sessions[i] = row.optDouble("sessions", 0);
                        users[i] = row.optDouble("users", 0);
                        String day = row.optString("day", "");
                        labels[i] = day.length() >= 10 ? day.substring(8, 10).replaceFirst("^0", "") : day;
                    }
                    DxUi.BarChart bars = new DxUi.BarChart(DxSectionActivity.this);
                    bars.setData(sessions, labels, NxUi.BLUE);
                    stats.addView(DxUi.chartCard(DxSectionActivity.this, "الجلسات آخر " + n + " أيام", DxUi.num(DxStats.sum(sessions)) + " جلسة", bars, 150));
                    DxUi.LineChart line = new DxUi.LineChart(DxSectionActivity.this);
                    line.setData(new double[][]{users}, new int[]{NxUi.TEAL}, true);
                    stats.addView(DxUi.chartCard(DxSectionActivity.this, "المستخدمون (تقدير)", "حسب اليوم", line, 110));
                }
            }

            @Override public void fail(String message) {
                DxSectionActivity.this.fail(message, false);
            }
        });
    }
}
