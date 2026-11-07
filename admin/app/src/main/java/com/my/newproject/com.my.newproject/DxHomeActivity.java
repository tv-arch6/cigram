package com.my.newproject;

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
 * The one home screen of the admin (v2.0): who is online right now, a live chart, the eight sections as big tiles
 * (each opens its own page), and the content overview with charts underneath. Nothing else is needed to start working.
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

    /** Keeps the dashboard numbers current on their own, every 2 minutes, without the admin tapping "تحديث". */
    private static final long AUTO_REFRESH_MS = 120000;
    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            if (!alive()) return;
            CgData.load(DxHomeActivity.this, new CgHttp.Done<CgData>() {
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
            if (alive() && resumed) ui().postDelayed(autoRefresh, AUTO_REFRESH_MS);
        }
    };

    @Override
    protected String screenTitle() {
        return "لوحة الإدارة";
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
        LinearLayout c = scrollBody();

        // hero header: brand, greeting, date and the live connection pill, on a soft accent-washed card
        LinearLayout hero = CgUi.vbox(this);
        hero.setBackground(CgUi.gradient(this, CgUi.alpha(CgCfg.ACCENT_D, 0x3A), CgCfg.CARD, 22));
        CgUi.elevate(hero, 5);
        int hp = CgUi.dp(this, 18);
        hero.setPadding(hp, CgUi.dp(this, 16), hp, CgUi.dp(this, 16));
        LinearLayout hello = CgUi.hbox(this);
        hello.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout hl = CgUi.vbox(this);
        TextView brand = CgUi.text(this, "CIGRAM", 12, CgCfg.ACCENT, true);
        CgUi.ltr(brand);
        brand.setLetterSpacing(0.18f);
        hl.addView(brand);
        hl.addView(CgUi.text(this, "أهلاً بك", 23, CgCfg.TEXT, true));
        hl.addView(CgUi.muted(this, today()));
        hello.addView(hl, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        status = CgUi.badge(this, "جارٍ الاتصال", CgCfg.GRAY);
        hello.addView(status, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        hero.addView(hello, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        hero.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 2, 2, 2, 14));
        c.addView(hero);

        // «ما الجديد؟»: cached discovery numbers (never calls TMDB) + the way into the discovery center
        whatsNew = new WnHomeCard(this);
        c.addView(whatsNew.view);

        // live board: active / watching / registered ticking every second + the sliding chart
        liveUi = new DxLive(this);
        c.addView(liveUi.main);

        // sections
        c.addView(DxUi.heading(this, "الأقسام"));
        c.addView(DxUi.row(this,
                DxUi.tile(this, CgIcons.FILM, "المحتوى", "أفلام ومسلسلات وحلقات", NxUi.BLUE, section("content")),
                DxUi.tile(this, CgIcons.SIGNAL, "القنوات", "البث المباشر والباقات", NxUi.TEAL, section("channels"))));
        c.addView(DxUi.row(this,
                DxUi.tile(this, CgIcons.IMAGE, "الرئيسية والواجهة", "الأقسام والكاروسيل والمميز", NxUi.VIOLET, section("home")),
                DxUi.tile(this, CgIcons.BELL, "الإشعارات والطلبات", "رسائل المستخدمين والتنبيهات", NxUi.ORANGE, section("notify"))));
        c.addView(DxUi.row(this,
                DxUi.tile(this, CgIcons.MEGAPHONE, "الإعلانات", "المعلنون والحملات والأرباح", NxUi.PINK, section("ads")),
                DxUi.tile(this, CgIcons.CHART, "الإحصائيات", "المستخدمون والأخطاء والصحة", NxUi.UP, section("stats"))));
        c.addView(DxUi.row(this,
                DxUi.tile(this, CgIcons.STAR, "اشتراكات Plus", "أكواد التفعيل والمشتركون", 0xFFFFD166, new Runnable() {
                    @Override public void run() { startActivity(new Intent(DxHomeActivity.this, PlusCodesActivity.class)); }
                }),
                DxUi.tile(this, CgIcons.GEAR, "التحديث والصيانة", "إصدار جديد وإيقاف مؤقت", CgCfg.GRAY, section("system"))));

        c.addView(DxUi.row(this,
                DxUi.tile(this, CgIcons.LINK, "الروابط الخارجية", "رابط التطبيق أو قناة تليكرام بصفحة الحساب", NxUi.TEAL, new Runnable() {
                    @Override public void run() { startActivity(new Intent(DxHomeActivity.this, ExternalLinksActivity.class)); }
                }),
                new View(this)));

        // live details: what is being watched, accounts, today, last hour
        c.addView(liveUi.more);

        // content overview (filled when the catalogue is loaded)
        LinearLayout ovHead = CgUi.hbox(this);
        ovHead.setGravity(Gravity.CENTER_VERTICAL);
        ovHead.addView(DxUi.heading(this, "نظرة على المحتوى"), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        lastUpdated = CgUi.muted(this, "");
        lastUpdated.setTextSize(11.5f);
        ovHead.addView(lastUpdated, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        c.addView(ovHead);
        contentBox = CgUi.vbox(this);
        contentBox.addView(CxUi.skeleton(this, 3));
        c.addView(contentBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
    }

    private Runnable section(final String key) {
        return new Runnable() {
            @Override public void run() { DxSectionActivity.open(DxHomeActivity.this, key); }
        };
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
        if (l.connected) {
            status.setText("متصل");
            status.setTextColor(CgCfg.GOOD);
            status.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.GOOD, 0x22), 10, 0));
        } else if (l.routeMissing) {
            status.setText("بدون بث مباشر");
            status.setTextColor(CgCfg.WARN);
            status.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.WARN, 0x22), 10, 0));
        } else if (l.everConnected) {
            status.setText("انقطع الاتصال");
            status.setTextColor(CgCfg.BAD);
            status.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.BAD, 0x22), 10, 0));
        } else if (l.error != null && l.error.length() > 0) {
            status.setText("تعذر الاتصال");
            status.setTextColor(CgCfg.BAD);
            status.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.BAD, 0x22), 10, 0));
        }
    }
}
