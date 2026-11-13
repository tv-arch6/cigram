package com.my.newproject;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Cigram Studio 4 — one room of the Studio: a header, the numbers that belong to the room, then every tool of the room
 * as tiles under its group title. Tap opens a tool; long-press pins it as a shortcut on the home.
 *
 * The «البث» room also carries the live board (who is watching right now), fed by its own one-second poll that runs only
 * while the room is on screen.
 */
public class StudioRoomActivity extends CgBase implements NxLive.Listener {

    static final String EXTRA_ROOM = "room";

    private StudioMap.Room room;
    private NxLive live;
    private DxLive liveUi;
    private boolean built;
    private boolean resumedOnce;

    private int roomIndex() {
        int r = getIntent() == null ? 0 : getIntent().getIntExtra(EXTRA_ROOM, 0);
        return r < 0 || r >= StudioMap.ROOMS.length ? 0 : r;
    }

    @Override
    protected String screenTitle() {
        return StudioMap.ROOMS[roomIndex()].title;
    }

    @Override
    protected void onBuild() {
        room = StudioMap.ROOMS[roomIndex()];
        if (room.index == StudioMap.LIVE) {
            live = new NxLive(this);
            live.setListener(this);
            liveUi = new DxLive(this);
        }
        bar.action("بحث", new Runnable() {
            @Override public void run() { StudioGo.openSearch(StudioRoomActivity.this); }
        });
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (live != null) live.start();
        if (resumedOnce && built && room.index != StudioMap.LIVE) build();   // badges (drafts, missing subtitles) may have changed
        resumedOnce = true;
        if (!CgData.loaded()) {
            CgData.loadIfStale(this, 90000L, new CgHttp.Done<CgData>() {
                @Override public void ok(CgData d) { if (alive() && room.index != StudioMap.LIVE) build(); }
                @Override public void fail(String message) { }
            });
        }
    }

    @Override
    protected void onPause() {
        if (live != null) live.stop();
        super.onPause();
    }

    @Override
    public void onTick(NxLive l, boolean fresh) {
        if (alive() && liveUi != null) liveUi.update(l, fresh);
    }

    // ------------------------------------------------------------------ layout

    private static void detach(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private void build() {
        int keepY = 0;
        if (body.getChildCount() > 0 && body.getChildAt(0) instanceof ScrollView) keepY = ((ScrollView) body.getChildAt(0)).getScrollY();
        if (liveUi != null) { detach(liveUi.main); detach(liveUi.more); }
        body.setBackgroundColor(StudioUi.INK);
        LinearLayout c = scrollBody();
        c.addView(header());
        View stats = stats();
        if (stats != null) c.addView(stats);
        if (room.index == StudioMap.LIVE && liveUi != null) c.addView(liveUi.main);

        List<String> pins = StudioPrefs.pins(this);
        for (int g = 0; g < room.groups.length; g++) {
            String group = room.groups[g];
            c.addView(StudioUi.label(this, group));
            List<StudioMap.Tool> tools = StudioMap.inGroup(room.index, group);
            ArrayList<View> tiles = new ArrayList<View>();
            for (int i = 0; i < tools.size(); i++) tiles.add(tile(tools.get(i), pins.contains(tools.get(i).id)));
            c.addView(StudioKit.grid(this, tiles, 10));
        }
        if (room.index == StudioMap.LIVE && liveUi != null) c.addView(liveUi.more);
        built = true;
        final int y = keepY;
        if (y > 0 && body.getChildCount() > 0) {
            final View sv = body.getChildAt(0);
            sv.post(new Runnable() { @Override public void run() { sv.scrollTo(0, y); } });
        }
    }

    private View tile(final StudioMap.Tool t, boolean pinned) {
        return StudioKit.toolTile(this, t, StudioGo.badge(this, t), pinned, StudioGo.opener(this, t), new Runnable() {
            @Override public void run() {
                boolean now = StudioPrefs.togglePin(StudioRoomActivity.this, t.id);
                CgUi.toast(StudioRoomActivity.this, now ? "ثُبّتت في اختصاراتك: " + t.title : "أُزيلت من الاختصارات: " + t.title);
                build();
            }
        });
    }

    private View header() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(StudioUi.panel(this, StudioUi.PANEL, 22));
        int p = CgUi.dp(this, 16);
        box.setPadding(p, p, p, p);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = CgUi.text(this, room.title, 21, CgCfg.TEXT, true);
        t.setGravity(Gravity.RIGHT);
        text.addView(t, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        TextView s = CgUi.text(this, room.sub, 12.5f, CgCfg.MUTED, false);
        s.setGravity(Gravity.RIGHT);
        text.addView(s, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        tl.setMargins(0, 0, CgUi.dp(this, 14), 0);
        box.addView(text, tl);
        box.addView(StudioUi.iconChip(this, room.icon, CgCfg.TEXT, 52));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP);
        bl.bottomMargin = CgUi.dp(this, 4);
        box.setLayoutParams(bl);
        return box;
    }

    /** Numbers that belong to the room, read from the catalogue already in memory (nothing is requested here). */
    private View stats() {
        CgData d = CgData.cur();
        if (d == null) return null;
        int[] n = d.counts();                                   // movies, series, episodes, errors, no subtitle, featured, hidden
        if (room.index == StudioMap.LIBRARY) {
            LinearLayout line = StudioUi.line(this,
                    StudioUi.stat(this, "أفلام", StudioKit.fmt(n[0]), CgCfg.TEXT),
                    StudioUi.stat(this, "مسلسلات", StudioKit.fmt(n[1]), CgCfg.TEXT),
                    StudioUi.stat(this, "حلقات", StudioKit.fmt(n[2]), CgCfg.TEXT));
            line.setPadding(0, CgUi.dp(this, 10), 0, 0);
            return line;
        }
        if (room.index == StudioMap.SUBS) {
            LinearLayout line = StudioUi.line(this,
                    StudioUi.stat(this, "بدون ترجمة", StudioKit.fmt(n[4]), n[4] > 0 ? CgCfg.WARN : CgCfg.GOOD),
                    StudioUi.stat(this, "عناوين فيها أخطاء", StudioKit.fmt(n[3]), n[3] > 0 ? CgCfg.BAD : CgCfg.GOOD));
            line.setPadding(0, CgUi.dp(this, 10), 0, 0);
            return line;
        }
        return null;
    }
}
