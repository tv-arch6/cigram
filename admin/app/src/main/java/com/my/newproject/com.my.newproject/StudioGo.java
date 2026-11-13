package com.my.newproject;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

/** Opens a {@link StudioMap.Tool} (the one place that knows how), and says what its little badge should show. */
final class StudioGo {

    private StudioGo() { }

    static void open(final Activity a, StudioMap.Tool t) {
        if (a == null || t == null) return;
        StudioPrefs.noteOpened(a, t.id);
        switch (t.action) {
            case StudioMap.A_OPEN:
                if (t.target != null) a.startActivity(new Intent(a, t.target));
                break;
            case StudioMap.A_ADD_EPISODE:
                CxNav.addEpisode(a, "", 0, 0);
                break;
            case StudioMap.A_ADD_SEASON:
                CxNav.addSeason(a, "", 0);
                break;
            case StudioMap.A_ALL_CONTENT:
                DxPanels.openContent(a, null, -1);
                break;
            case StudioMap.A_DRAFTS:
                CxNav.drafts(a);
                break;
            case StudioMap.A_CAROUSEL:
                CxNav.carouselManage(a);
                break;
            case StudioMap.A_CAROUSEL_ADD:
                CxNav.carouselAdd(a, "");
                break;
            case StudioMap.A_PRIVACY:
                CgVault.showPrivacy(a);
                break;
            case StudioMap.A_TOKEN:
                TokenGate.ask(a, new Runnable() {
                    @Override public void run() {
                        CgData.load(a, new CgHttp.Done<CgData>() {
                            @Override public void ok(CgData d) { }
                            @Override public void fail(String message) { }
                        });
                    }
                });
                break;
            default:
                break;
        }
    }

    static Runnable opener(final Activity a, final StudioMap.Tool t) {
        return new Runnable() {
            @Override public void run() { open(a, t); }
        };
    }

    static void openRoom(Activity a, int room) {
        Intent in = new Intent(a, StudioRoomActivity.class);
        in.putExtra(StudioRoomActivity.EXTRA_ROOM, room);
        a.startActivity(in);
    }

    static void openSearch(Activity a) {
        a.startActivity(new Intent(a, StudioSearchActivity.class));
    }

    /** "جديد" for new tools; for a few tools a live count taken from data already in memory (never a request). */
    static String badge(Context c, StudioMap.Tool t) {
        if (t.badge.length() > 0) return t.badge;
        try {
            if ("drafts".equals(t.id)) {
                int n = CxDraft.count(c);
                return n > 0 ? String.valueOf(n) : "";
            }
            CgData d = CgData.cur();
            if (d != null && "sub_center".equals(t.id)) {
                int n = d.counts()[4];
                return n > 0 ? String.valueOf(n) : "";
            }
        } catch (Throwable ignored) { }
        return "";
    }
}
