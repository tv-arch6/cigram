package com.my.newproject;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Cigram Studio (admin 3.0) — the home of the admin, redesigned from an empty page.
 *
 * The old home was one long scroll (hero, live chart, eight section tiles, charts). The Studio is FIVE workspaces
 * reached from a dock at the bottom of the screen, each one a short page of its own:
 *
 * <pre>
 *   الاستوديو   what needs you now: the four daily actions, things to fix, the catalogue in numbers
 *   المحتوى     Content Studio: add · manage · subtitles (lab + centre) · what the app's home shows
 *   البث        who is watching right now, channels, Cigram Live Health
 *   التواصل     developer inbox, notifications, requests, advertising, Plus
 *   النظام      app update, maintenance, history / backups, statistics, errors, privacy, the admin token
 * </pre>
 * A workspace is built the first time it is opened (the first frame only pays for the first one), every row opens a
 * screen that already exists, and the data layer (catalogue snapshot, live socket, auto refresh, token gate) is the
 * one the previous home used — only the face is new. Components: {@link StudioUi}.
 */
public class DxHomeActivity extends CgBase implements NxLive.Listener {

    private NxLive live;
    private DxLive liveUi;
    private TextView status;
    private LinearLayout contentBox;
    private WnHomeCard whatsNew;
    private TextView lastUpdated;
    private boolean contentBuilt;
    private boolean resumed;
    // Studio
    private android.widget.FrameLayout stage;
    private final View[] panels = new View[5];
    private StudioUi.Dock dock;
    private static final String[] TAB_TITLES = {"Cigram Studio", "استوديو المحتوى", "البث المباشر", "التواصل والإعلانات", "النظام والخصوصية"};

    /** Keeps the dashboard numbers current on their own, every 2 minutes, without the admin tapping "تحديث". */
    private static final long AUTO_REFRESH_MS = 120000;
    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            if (!alive()) return;
            // 18.3: a snapshot younger than 90 s (after an edit screen, or a manual refresh) is not downloaded again, and a load
            // already running (the "تحديث" button, another screen) is joined instead of started twice
            CgData.loadIfStale(DxHomeActivity.this, 90000L, new CgHttp.Done<CgData>() {
                @Override
                public void ok(CgData d) {
                    if (alive()) showContent();
                    schedule();
                }

                @Override
                public void fail(String message) {
                    schedule(); // stay quiet: a real outage already falls back to the cache inside CgData.load
                }
            });
        }

        private void schedule() {
            // exponential back-off while the server keeps failing (2, 4, 8 minutes ... capped at 10), instead of every 2 minutes forever
            long delay = CgFlight.refreshDelayMs(AUTO_REFRESH_MS, CgData.failures(), 10L * 60000L);
            if (alive() && resumed) ui().postDelayed(autoRefresh, delay);
        }
    };

    @Override
    protected String screenTitle() {
        return "Cigram Studio";
    }

    @Override
    protected boolean showBack() {
        return false;
    }

    @Override
    protected void onBuild() {
        live = new NxLive(this);
        live.setListener(this);
        build();
        bar.action("بحث", new Runnable() {
            @Override public void run() { DxPanels.openContent(DxHomeActivity.this, null, -1); }
        });
        bar.action("تحديث", new Runnable() {
            @Override public void run() { reloadContent(); }
        });
        loadContent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        TokenGate.ensure(this, new Runnable() { @Override public void run() { reloadContent(); } });
        if (whatsNew != null) whatsNew.refresh();               // one cheap read of the cached summary
        if (live != null) live.start();
        if (contentBuilt && CgData.loaded()) showContent();     // counts may have changed after an edit
        ui().removeCallbacks(autoRefresh);
        ui().postDelayed(autoRefresh, AUTO_REFRESH_MS);
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (live != null) live.stop();
        ui().removeCallbacks(autoRefresh);
        super.onPause();
    }

    // ------------------------------------------------------------------ layout

    private void build() {
        final Activity a = this;
        body.removeAllViews();
        body.setBackgroundColor(StudioUi.INK);
        stage = new android.widget.FrameLayout(this);
        body.addView(stage, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));
        View line = new View(this);
        line.setBackgroundColor(StudioUi.LINE);
        body.addView(line, new LinearLayout.LayoutParams(CgUi.MATCH, Math.max(1, CgUi.dp(this, 0.7f))));
        dock = new StudioUi.Dock(this, new String[]{"الاستوديو", "المحتوى", "البث", "التواصل", "النظام"},
                new int[]{CgIcons.BOX, CgIcons.FILM, CgIcons.SIGNAL, CgIcons.CHAT, CgIcons.GEAR}, new StudioUi.OnTab() {
            @Override public void onTab(int index) { show(index); }
        });
        body.addView(dock.view, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        // the live board exists from the start (it is fed every second), it is only ATTACHED when «البث» is opened
        liveUi = new DxLive(this);
        dock.select(0);
        show(0);
    }

    /** A workspace page: a scroll with the Studio's margins. */
    private LinearLayout page(int index) {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(false);
        LinearLayout col = CgUi.vbox(this);
        int p = CgUi.dp(this, 14);
        col.setPadding(p, CgUi.dp(this, 6), p, CgUi.dp(this, 28));
        sv.addView(col, new android.widget.ScrollView.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        panels[index] = sv;
        stage.addView(sv, new android.widget.FrameLayout.LayoutParams(CgUi.MATCH, CgUi.MATCH));
        return col;
    }

    private void show(int index) {
        if (panels[index] == null) {
            LinearLayout col = page(index);
            if (index == 0) buildStudio(col);
            else if (index == 1) buildContent(col);
            else if (index == 2) buildLive(col);
            else if (index == 3) buildAudience(col);
            else buildSystem(col);
            CgUi.applyTree(panels[index]);
        }
        for (int i = 0; i < panels.length; i++) if (panels[i] != null) panels[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        bar.titleView.setText(TAB_TITLES[index]);
    }

    @Override public void onBackPressed() {
        if (dock != null && dock.selected() != 0) { dock.select(0); show(0); return; }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ workspace 1: الاستوديو

    private void buildStudio(LinearLayout c) {
        final Activity a = this;
        // greeting line: date on the right, connection state on the left
        LinearLayout hello = CgUi.hbox(this);
        hello.setGravity(Gravity.CENTER_VERTICAL);
        status = StudioUi.pill(this, "جارٍ الاتصال", CgCfg.GRAY);
        hello.addView(status, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        LinearLayout hl = CgUi.vbox(this);
        TextView hi = CgUi.text(this, "أهلاً بك في الاستوديو", 21, CgCfg.TEXT, true);
        hi.setGravity(Gravity.RIGHT);
        hl.addView(hi, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        TextView day = CgUi.text(this, today(), 12.5f, CgCfg.MUTED, false);
        day.setGravity(Gravity.RIGHT);
        hl.addView(day, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        hello.addView(hl, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        hello.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 4, 4, 14));
        c.addView(hello);

        // the deck: what an editor does every day
        c.addView(StudioUi.pair(this,
                StudioUi.deck(this, CgIcons.SEND, "إضافة سريعة", "الصق رابطاً وانشر بخطوة واحدة", StudioUi.RED, StudioUi.go(a, QuickAddActivity.class)),
                StudioUi.deck(this, CgIcons.UPLOAD, "معالج الرفع الجماعي", "موسم كامل أو عدة أفلام دفعة واحدة", StudioUi.ORANGE, StudioUi.go(a, BulkWizardActivity.class))));
        c.addView(StudioUi.pair(this,
                StudioUi.deck(this, CgIcons.SIGNAL, "قنوات تحتاج رابطاً", "بلاغات «بلّغ المدير» · استبدل برابط", StudioUi.MINT, StudioUi.go(a, CgChannelReportsActivity.class)),
                StudioUi.deck(this, CgIcons.SHIELD, "AdMob", "تشغيل وإيقاف · توقّعات الأرباح", StudioUi.ORANGE, StudioUi.go(a, OpsAdsActivity.class))));
        c.addView(StudioUi.pair(this,
                StudioUi.deck(this, CgIcons.FLAG, "طلبات المستخدمين", "يريد فيلماً أو مسلسلاً · انسخ الاسم", StudioUi.SKY, StudioUi.go(a, NxRequestsActivity.class)),
                StudioUi.deck(this, CgIcons.CHAT, "الدعم والتواصل", "محادثات المستخدمين: Plus والمشاكل", StudioUi.VIOLET, StudioUi.go(a, AdInboxActivity.class))));

        // «ما الجديد؟»: cached discovery numbers (never calls TMDB) + the way into the discovery center
        whatsNew = new WnHomeCard(this);
        c.addView(whatsNew.view);

        LinearLayout ovHead = CgUi.hbox(this);
        ovHead.setGravity(Gravity.CENTER_VERTICAL);
        lastUpdated = CgUi.muted(this, "");
        lastUpdated.setTextSize(11.5f);
        ovHead.addView(lastUpdated, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        TextView ovTitle = StudioUi.label(this, "المكتبة بالأرقام");
        ovHead.addView(ovTitle, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        c.addView(ovHead);
        contentBox = CgUi.vbox(this);
        contentBox.addView(CxUi.skeleton(this, 3));
        c.addView(contentBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
    }

    // ------------------------------------------------------------------ workspace 2: المحتوى (Content Studio)

    private void buildContent(LinearLayout c) {
        final Activity a = this;
        c.addView(StudioUi.label(this, "إضافة"));
        LinearLayout add = StudioUi.group(this);
        StudioUi.row(add, CgIcons.STAR, "ما الجديد؟", "اكتشف الجديد وأضفه مع الفيديو والترجمة", "جديد", StudioUi.PINK, StudioUi.go(a, WnActivity.class));
        StudioUi.row(add, CgIcons.FILM, "إضافة فيلم", "ابحث بالاسم وأضفه برابطه أو من جوالك", "", StudioUi.SKY, StudioUi.go(a, NxMovieActivity.class));
        StudioUi.row(add, CgIcons.TV, "إضافة مسلسل", "مسلسل جديد بمواسمه وحلقاته", "", StudioUi.VIOLET, StudioUi.go(a, NxSeriesActivity.class));
        StudioUi.row(add, CgIcons.PLUS, "إضافة حلقة", "حلقة جديدة لمسلسل موجود", "", StudioUi.MINT, new Runnable() {
            @Override public void run() { CxNav.addEpisode(a, "", 0, 0); }
        });
        StudioUi.row(add, CgIcons.FOLDER, "إضافة موسم كامل", "موسم دفعة واحدة بالروابط", "", StudioUi.MINT, new Runnable() {
            @Override public void run() { CxNav.addSeason(a, "", 0); }
        });
        StudioUi.row(add, CgIcons.UPLOAD, "معالج الرفع الجماعي", "اختر الملفات: يطابقها، يفحصها ثم يسلّمها للرفع", "جديد", StudioUi.ORANGE, StudioUi.go(a, BulkWizardActivity.class));
        StudioUi.row(add, CgIcons.SEND, "إضافة سريعة", "لصق رابط وإنهاء بخطوة واحدة", "", StudioUi.RED, StudioUi.go(a, QuickAddActivity.class));
        c.addView(add);

        c.addView(StudioUi.label(this, "إدارة المكتبة"));
        LinearLayout manage = StudioUi.group(this);
        StudioUi.row(manage, CgIcons.SEARCH, "كل المحتوى", "بحث وفلترة وتحرير وحذف", "", StudioUi.SKY, new Runnable() {
            @Override public void run() { DxPanels.openContent(a, null, -1); }
        });
        int drafts = 0;
        try { drafts = CxDraft.count(this); } catch (Throwable ignored) { }
        StudioUi.row(manage, CgIcons.DRAFT, "المسودات", "ما بدأت به ولم تكمله", drafts > 0 ? String.valueOf(drafts) : "", StudioUi.GOLD, new Runnable() {
            @Override public void run() { CxNav.drafts(a); }
        });
        StudioUi.row(manage, CgIcons.CLIP, "الرفع والتحميلات", "متابعة الملفات الجارية والفاشلة", "", StudioUi.ORANGE, StudioUi.go(a, CgUploadsActivity.class));
        StudioUi.row(manage, CgIcons.COPY, "دمج العناوين", "ضم المكرر في عنوان واحد", "", CgCfg.GRAY, StudioUi.go(a, CgMergeActivity.class));
        c.addView(manage);

        c.addView(StudioUi.label(this, "الترجمة"));
        LinearLayout subs = StudioUi.group(this);
        StudioUi.row(subs, CgIcons.SHIELD, "مركز الترجمة العربية", "العناوين بلا ترجمة: بحث جماعي، مراجعة، اعتماد ونشر", "", StudioUi.MINT, StudioUi.go(a, SubCenterActivity.class));
        StudioUi.row(subs, CgIcons.UPLOAD, "رفع ترجمة وربطها", "ملف لعنوان واحد", "", StudioUi.VIOLET, StudioUi.go(a, CxSubsActivity.class));
        StudioUi.row(subs, CgIcons.FOLDER, "ترجمات موسم كامل", "حزمة ZIP أو عدة ملفات تُطابق بالحلقات", "", StudioUi.VIOLET, StudioUi.go(a, CgSeasonSubsActivity.class));
        c.addView(subs);

        c.addView(StudioUi.label(this, "واجهة التطبيق"));
        LinearLayout face = StudioUi.group(this);
        StudioUi.row(face, CgIcons.IMAGE, "الكاروسيل", "الشرائح المتحركة أعلى الرئيسية", "", StudioUi.VIOLET, new Runnable() {
            @Override public void run() { CxNav.carouselManage(a); }
        });
        StudioUi.row(face, CgIcons.PLUS, "إضافة شريحة", "شريحة جديدة للكاروسيل", "", StudioUi.MINT, new Runnable() {
            @Override public void run() { CxNav.carouselAdd(a, ""); }
        });
        StudioUi.row(face, CgIcons.FOLDER, "الأقسام", "أسماء الأقسام وما يظهر منها", "", StudioUi.SKY, StudioUi.go(a, CgSectionsActivity.class));
        StudioUi.row(face, CgIcons.EDIT, "ترتيب الرئيسية", "رتّب الأقسام كما تريد", "", StudioUi.ORANGE, StudioUi.go(a, CgHomeOrderActivity.class));
        StudioUi.row(face, CgIcons.STAR, "المحتوى المميز", "ما يظهر في الواجهة أولاً", "", StudioUi.GOLD, StudioUi.go(a, CgFeaturedActivity.class));
        c.addView(face);
    }

    // ------------------------------------------------------------------ workspace 3: البث

    private void buildLive(LinearLayout c) {
        final Activity a = this;
        c.addView(liveUi.main);
        c.addView(StudioUi.label(this, "القنوات"));
        LinearLayout ch = StudioUi.group(this);
        StudioUi.row(ch, CgIcons.SIGNAL, "Cigram Live Health", "فحص كل القنوات الآن: تعمل · بطيئة · متوقفة، مع السبب", "جديد", StudioUi.MINT, StudioUi.go(a, LiveHealthActivity.class));
        StudioUi.row(ch, CgIcons.PLUS, "إضافة قناة", "رابط البث والشعار والباقة", "", StudioUi.SKY, StudioUi.go(a, NxChannelActivity.class));
        StudioUi.row(ch, CgIcons.EDIT, "إدارة القنوات", "ترتيب وإخفاء وفحص الروابط", "", StudioUi.VIOLET, StudioUi.go(a, CgChannelsActivity.class));
        c.addView(ch);
        c.addView(liveUi.more);
    }

    // ------------------------------------------------------------------ workspace 4: التواصل

    private void buildAudience(LinearLayout c) {
        final Activity a = this;
        c.addView(StudioUi.label(this, "المستخدمون"));
        LinearLayout u = StudioUi.group(this);
        StudioUi.row(u, CgIcons.CHAT, "الدعم والتواصل", "الدعم الخاص ورسائل المستخدمين والمعلنين", "", StudioUi.VIOLET, StudioUi.go(a, AdInboxActivity.class));
        StudioUi.row(u, CgIcons.SEND, "إرسال إشعار", "إشعار فوري لكل المستخدمين", "", StudioUi.ORANGE, StudioUi.go(a, NxNotifyActivity.class));
        StudioUi.row(u, CgIcons.HISTORY, "الرسائل العامة", "رسائل تظهر داخل التطبيق بجدولة", "", StudioUi.SKY, StudioUi.go(a, CgAnnouncementsActivity.class));
        StudioUi.row(u, CgIcons.BELL, "طلبات المستخدمين", "أفلام ومسلسلات وقنوات طلبها المشاهدون", "", StudioUi.PINK, StudioUi.go(a, NxRequestsActivity.class));
        StudioUi.row(u, CgIcons.STAR, "اشتراكات Plus", "أكواد التفعيل والمشتركون", "", StudioUi.GOLD, StudioUi.go(a, PlusCodesActivity.class));
        c.addView(u);

        c.addView(StudioUi.label(this, "الإعلانات"));
        LinearLayout ads = StudioUi.group(this);
        StudioUi.row(ads, CgIcons.GEAR, "إعدادات المحادثة", "سياسة التواصل وساعات العمل", "", CgCfg.GRAY, StudioUi.go(a, AdSettingsActivity.class));
        StudioUi.row(ads, CgIcons.SHIELD, "AdMob: تشغيل وإيقاف", "المفتاح الرئيسي ومواضع إعلانات AdMob", "", StudioUi.MINT, StudioUi.go(a, OpsAdsActivity.class));
        StudioUi.row(ads, CgIcons.CHART, "AdMob: الفحص", "نسبة التعبئة وأسباب الفشل", "", StudioUi.MINT, StudioUi.go(a, OpsAdCheckActivity.class));
        c.addView(ads);
    }

    // ------------------------------------------------------------------ workspace 5: النظام

    private void buildSystem(LinearLayout c) {
        final Activity a = this;
        c.addView(StudioUi.label(this, "التطبيق"));
        LinearLayout app = StudioUi.group(this);
        StudioUi.row(app, CgIcons.UPDATE, "تحديث التطبيق", "نشر إصدار جديد وجدولته", "", StudioUi.SKY, StudioUi.go(a, CgUpdateActivity.class));
        StudioUi.row(app, CgIcons.LOCK, "وضع الصيانة", "إيقاف التطبيق مؤقتاً برسالة", "", StudioUi.GOLD, StudioUi.go(a, CgMaintenanceActivity.class));
        StudioUi.row(app, CgIcons.LINK, "الروابط الخارجية", "رابط التطبيق أو قناة تليكرام بصفحة الحساب", "", StudioUi.MINT, StudioUi.go(a, ExternalLinksActivity.class));
        c.addView(app);

        c.addView(StudioUi.label(this, "المراقبة"));
        LinearLayout watch = StudioUi.group(this);
        StudioUi.row(watch, CgIcons.CHART, "الإحصائيات التفصيلية", "رسوم اليوم والساعة والإصدارات", "", StudioUi.MINT, StudioUi.go(a, OpsStatsActivity.class));
        StudioUi.row(watch, CgIcons.WARNING, "أخطاء التطبيق", "انهيارات ومشاكل التشغيل مجمّعة", "", CgCfg.BAD, StudioUi.go(a, OpsErrorsActivity.class));
        StudioUi.row(watch, CgIcons.SIGNAL, "صحة النظام", "الخوادم والملفات وزمن الاستجابة", "", StudioUi.SKY, StudioUi.go(a, CgHealthActivity.class));
        StudioUi.row(watch, CgIcons.HISTORY, "سجل العمليات والنسخ", "تراجع واستعادة النسخ الاحتياطية", "", StudioUi.VIOLET, StudioUi.go(a, CgHistoryActivity.class));
        c.addView(watch);

        c.addView(StudioUi.label(this, "الخصوصية والأمان"));
        LinearLayout sec = StudioUi.group(this);
        StudioUi.row(sec, CgIcons.SHIELD, "خصوصية ملفات الإدارة", "تشفير الرمز، قفل التطبيق، منع تصوير الشاشة، مسح الملفات المؤقتة", "جديد", StudioUi.MINT, new Runnable() {
            @Override public void run() { CgVault.showPrivacy(DxHomeActivity.this); }
        });
        StudioUi.row(sec, CgIcons.LOCK, "رمز الإدارة", "تغيير الرمز المحفوظ على هذا الجهاز", "", StudioUi.GOLD, new Runnable() {
            @Override public void run() { TokenGate.ask(DxHomeActivity.this, new Runnable() { @Override public void run() { reloadContent(); } }); }
        });
        c.addView(sec);
        TextView ver = CgUi.text(this, "Cigram Studio " + versionName(), 11.5f, CgCfg.FAINT, false);
        ver.setGravity(Gravity.CENTER);
        ver.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 18, 0, 0));
        c.addView(ver);
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable t) { return ""; }
    }

    private static String today() {
        SimpleDateFormat f = new SimpleDateFormat("EEEE d MMMM", new Locale("ar"));
        f.setTimeZone(TimeZone.getTimeZone("Asia/Baghdad"));
        return f.format(new Date());
    }

    // ------------------------------------------------------------------ content data

    private void loadContent() {
        ensureData(new Runnable() {
            @Override public void run() { showContent(); }
        });
    }

    private void reloadContent() {
        reloadData(new Runnable() {
            @Override public void run() { showContent(); }
        });
        live.retryAccounts();
    }

    private void showContent() {
        if (!alive()) return;
        contentBuilt = true;
        DxPanels.content(this, contentBox);   // removes previous children, then fills the overview
        View warn = warningCard();
        if (warn != null) contentBox.addView(warn, 0);
        CgData d = CgData.cur();
        if (lastUpdated != null && d != null) {
            lastUpdated.setText((d.fromCache ? "محفوظة · " : "آخر تحديث: ") + CgUi.ago(CgUi.toIso(d.loadedAt)));
        }
    }

    // ------------------------------------------------------------------ live

    @Override
    public void onTick(NxLive l, boolean fresh) {
        if (!alive()) return;
        liveUi.update(l, fresh);
        if (status == null) return;
        if (l.connected) {
            StudioUi.setPill(status, "● متصل", CgCfg.GOOD);
        } else if (l.routeMissing) {
            StudioUi.setPill(status, "بدون بث مباشر", CgCfg.WARN);
        } else if (l.everConnected) {
            StudioUi.setPill(status, "انقطع الاتصال", CgCfg.BAD);
        } else if (l.error != null && l.error.length() > 0) {
            StudioUi.setPill(status, "تعذر الاتصال", CgCfg.BAD);
        }
    }
}
