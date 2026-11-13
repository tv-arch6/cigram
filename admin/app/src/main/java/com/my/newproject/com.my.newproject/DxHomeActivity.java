package com.my.newproject;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Cigram Studio 4 — the home of the admin.
 *
 * Studio 3 was five workspaces behind a bottom dock, each one a list of rows. Studio 4 drops the dock: the home is ONE
 * page that answers four questions in order, and every tool of the admin is one or two taps away.
 *
 * <pre>
 *   ابحث             one search for every tool and every title ({@link StudioSearchActivity})
 *   الآن في التطبيق   live numbers: active · watching · users today
 *   يحتاجك الآن       what is wrong or waiting (titles with errors, missing subtitles, drafts)
 *   اختصاراتي        the tools the admin pinned (long-press any tile to pin) — the daily five are there from the start
 *   غرف الاستوديو     six rooms (library · subtitles · app face · live · audience · system), a bento grid; a room page
 *                    ({@link StudioRoomActivity}) lists ALL of its tools. {@link StudioMap} is the single list of tools.
 * </pre>
 * Under the map: what's new (TMDB discovery summary) and the library in numbers. The data layer — catalogue snapshot with
 * its on-device fallback, the one-second live poll, the 2-minute quiet refresh, the token gate — is the previous home's,
 * unchanged; only the face is new.
 */
public class DxHomeActivity extends CgBase implements NxLive.Listener {

    private NxLive live;
    private StudioKit.Pulse pulse;
    private LinearLayout contentBox;
    private LinearLayout attentionBox;
    private LinearLayout pinsBox;
    private WnHomeCard whatsNew;
    private TextView lastUpdated;
    private TextView libraryLine;
    private final View[] roomCards = new View[StudioMap.ROOMS.length];
    private boolean contentBuilt;
    private boolean resumed;

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
            @Override public void run() { StudioGo.openSearch(DxHomeActivity.this); }
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
        fillPins();                                              // the admin may have pinned or unpinned in a room
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
        body.removeAllViews();
        body.setBackgroundColor(StudioUi.INK);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout c = CgUi.vbox(this);
        int p = CgUi.dp(this, 14);
        c.setPadding(p, CgUi.dp(this, 6), p, CgUi.dp(this, 28));
        sv.addView(c, new ScrollView.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        body.addView(sv, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));

        // 1. greeting + search
        LinearLayout hello = CgUi.vbox(this);
        TextView hi = CgUi.text(this, "أهلاً بك في الاستوديو", 22, CgCfg.TEXT, true);
        hi.setGravity(Gravity.RIGHT);
        hello.addView(hi, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        TextView day = CgUi.text(this, today(), 12.5f, CgCfg.MUTED, false);
        day.setGravity(Gravity.RIGHT);
        hello.addView(day, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        hello.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 4, 4, 12));
        c.addView(hello);
        View search = StudioKit.searchPill(this, "ابحث عن أي أداة أو عنوان…", new Runnable() {
            @Override public void run() { StudioGo.openSearch(DxHomeActivity.this); }
        });
        search.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));
        c.addView(search);

        // 2. live numbers
        pulse = new StudioKit.Pulse(this);
        pulse.view.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 4));
        c.addView(pulse.view);

        // 3. what needs the admin
        c.addView(StudioUi.label(this, "يحتاجك الآن"));
        attentionBox = CgUi.vbox(this);
        attentionBox.addView(CxUi.skeleton(this, 1));
        c.addView(attentionBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        // 4. pinned shortcuts
        c.addView(StudioUi.label(this, "اختصاراتي"));
        pinsBox = CgUi.vbox(this);
        c.addView(pinsBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        fillPins();

        // 5. the rooms (bento): the library as a wide card, four half cards, the system as a wide card
        c.addView(StudioUi.label(this, "غرف الاستوديو"));
        libraryLine = CgUi.text(this, "", 12, CgCfg.FAINT, false);
        libraryLine.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams wide = new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP);
        wide.bottomMargin = CgUi.dp(this, 10);
        roomCards[StudioMap.LIBRARY] = room(StudioMap.LIBRARY, true, libraryLine);
        c.addView(roomCards[StudioMap.LIBRARY], wide);
        ArrayList<View> halves = new ArrayList<View>();
        int[] middle = {StudioMap.SUBS, StudioMap.FACE, StudioMap.LIVE, StudioMap.AUDIENCE};
        for (int i = 0; i < middle.length; i++) {
            roomCards[middle[i]] = room(middle[i], false, null);
            halves.add(roomCards[middle[i]]);
        }
        c.addView(StudioKit.grid(this, halves, 10));
        LinearLayout.LayoutParams wide2 = new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP);
        wide2.bottomMargin = CgUi.dp(this, 4);
        roomCards[StudioMap.SYSTEM] = room(StudioMap.SYSTEM, true, null);
        c.addView(roomCards[StudioMap.SYSTEM], wide2);

        // 6. what's new + the library in numbers
        whatsNew = new WnHomeCard(this);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP);
        wl.topMargin = CgUi.dp(this, 12);
        c.addView(whatsNew.view, wl);

        LinearLayout ovHead = CgUi.hbox(this);
        ovHead.setGravity(Gravity.CENTER_VERTICAL);
        lastUpdated = CgUi.muted(this, "");
        lastUpdated.setTextSize(11.5f);
        ovHead.addView(lastUpdated, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        ovHead.addView(StudioUi.label(this, "المكتبة بالأرقام"), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        c.addView(ovHead);
        contentBox = CgUi.vbox(this);
        contentBox.addView(CxUi.skeleton(this, 3));
        c.addView(contentBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        TextView ver = CgUi.text(this, "Cigram Studio " + versionName(), 11.5f, CgCfg.FAINT, false);
        ver.setGravity(Gravity.CENTER);
        ver.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 18, 0, 0));
        c.addView(ver);
    }

    private View room(final int index, boolean wide, View extra) {
        return StudioKit.roomCard(this, StudioMap.ROOMS[index], StudioMap.inRoom(index).size(), wide, extra, new Runnable() {
            @Override public void run() { StudioGo.openRoom(DxHomeActivity.this, index); }
        });
    }

    /** The pinned tools as a sideways strip, or a one-line hint when nothing is pinned. */
    private void fillPins() {
        if (pinsBox == null) return;
        pinsBox.removeAllViews();
        List<String> ids = StudioPrefs.pins(this);
        ArrayList<View> items = new ArrayList<View>();
        for (int i = 0; i < ids.size(); i++) {
            final StudioMap.Tool t = StudioMap.byId(ids.get(i));
            if (t == null) continue;
            items.add(StudioKit.shortcut(this, t, StudioGo.opener(this, t), new Runnable() {
                @Override public void run() {
                    StudioPrefs.togglePin(DxHomeActivity.this, t.id);
                    CgUi.toast(DxHomeActivity.this, "أُزيلت من الاختصارات: " + t.title);
                    fillPins();
                }
            }));
        }
        if (items.isEmpty()) {
            TextView hint = CgUi.text(this, "اضغط مطولاً على أي أداة داخل الغرف لتثبيتها هنا", 12.5f, CgCfg.FAINT, false);
            hint.setGravity(Gravity.RIGHT);
            pinsBox.addView(hint, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            return;
        }
        pinsBox.addView(StudioKit.lane(this, items, 8), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
    }

    /** What is wrong or waiting, from data already in memory. A quiet "all good" chip when there is nothing. */
    private void fillAttention() {
        if (attentionBox == null) return;
        attentionBox.removeAllViews();
        CgData d = CgData.cur();
        if (d == null) {
            attentionBox.addView(CxUi.skeleton(this, 1));
            return;
        }
        final Activity a = this;
        int[] n = d.counts();                       // movies, series, episodes, errors, no subtitle, featured, hidden
        int drafts = 0;
        try { drafts = CxDraft.count(this); } catch (Throwable ignored) { }
        ArrayList<View> chips = new ArrayList<View>();
        if (n[3] > 0) chips.add(StudioKit.attentionChip(this, CgIcons.WARNING, "عناوين فيها أخطاء", String.valueOf(n[3]), CgCfg.BAD, content("errors")));
        if (n[4] > 0) chips.add(StudioKit.attentionChip(this, CgIcons.FILE, "بدون ترجمة", String.valueOf(n[4]), CgCfg.WARN, content("no_subtitle")));
        if (drafts > 0) chips.add(StudioKit.attentionChip(this, CgIcons.DRAFT, "مسودات غير مكتملة", String.valueOf(drafts), CgCfg.TEXT, new Runnable() {
            @Override public void run() { CxNav.drafts(a); }
        }));
        if (chips.isEmpty()) chips.add(StudioKit.attentionChip(this, CgIcons.CHECK, "كل شيء على ما يرام", "", CgCfg.GOOD, null));
        attentionBox.addView(StudioKit.lane(this, chips, 8), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        StudioKit.setRoomNote(roomCards[StudioMap.SUBS], n[4] > 0 ? n[4] + " بلا ترجمة" : "", CgCfg.WARN);
        StudioKit.setRoomNote(roomCards[StudioMap.LIBRARY], drafts > 0 ? drafts + " مسودة" : "", CgCfg.WARN);
        if (libraryLine != null) {
            libraryLine.setText(StudioKit.fmt(n[0]) + " فيلم  ·  " + StudioKit.fmt(n[1]) + " مسلسل  ·  " + StudioKit.fmt(n[2]) + " حلقة");
        }
    }

    private Runnable content(final String preset) {
        return new Runnable() {
            @Override public void run() { DxPanels.openContent(DxHomeActivity.this, preset, -1); }
        };
    }

    private String versionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (Throwable t) { return ""; }
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
        fillAttention();
        CgData d = CgData.cur();
        if (lastUpdated != null && d != null) {
            lastUpdated.setText((d.fromCache ? "محفوظة · " : "آخر تحديث: ") + CgUi.ago(CgUi.toIso(d.loadedAt)));
        }
    }

    // ------------------------------------------------------------------ live

    @Override
    public void onTick(NxLive l, boolean fresh) {
        if (!alive() || pulse == null) return;
        pulse.update(l);
        if (l.connected) {
            pulse.status("● متصل", CgCfg.GOOD);
        } else if (l.routeMissing) {
            pulse.status("بدون بث مباشر", CgCfg.WARN);
        } else if (l.everConnected) {
            pulse.status("انقطع الاتصال", CgCfg.BAD);
        } else if (l.error != null && l.error.length() > 0) {
            pulse.status("تعذر الاتصال", CgCfg.BAD);
        }
        StudioKit.setRoomNote(roomCards[StudioMap.LIVE], l.watching > 0 ? StudioKit.fmt(l.watching) + " يشاهدون" : "", CgCfg.GOOD);
    }
}
