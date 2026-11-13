package com.my.newproject;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;

/** Reusable dashboard blocks: the content overview (numbers, things that need attention, two charts). */
final class DxPanels {

    private DxPanels() { }

    static void openContent(Activity a, String preset, int kind) {
        Intent in = new Intent(a, CgContentActivity.class);
        if (preset != null) in.putExtra("preset", preset);
        if (kind >= 0) in.putExtra("kind", kind);
        a.startActivity(in);
    }

    /** Fills {@code box} with the content overview of the loaded catalogue. */
    static void content(final Activity a, LinearLayout box) {
        box.removeAllViews();
        CgData d = CgData.cur();
        if (d == null) return;
        int[] n = d.counts();                       // movies, series, episodes, errors, no subtitle, featured, hidden

        box.addView(DxUi.row(a,
                clickable(a, DxUi.kpi(a, "أفلام", DxUi.num(n[0]), CgCfg.TEXT), "", CgData.K_MOVIE),
                clickable(a, DxUi.kpi(a, "مسلسلات", DxUi.num(n[1]), CgCfg.TEXT), "", CgData.K_SERIES),
                clickable(a, DxUi.kpi(a, "حلقات", DxUi.num(n[2]), CgCfg.TEXT), "", CgData.K_EPISODE)));

        int drafts = 0;
        try { drafts = CxDraft.count(a); } catch (Throwable ignored) { }
        if (n[3] > 0 || n[4] > 0 || drafts > 0) {
            LinearLayout attention = DxUi.card(a);
            attention.addView(CgUi.text(a, "يحتاج انتباهك", 15, CgCfg.TEXT, true));
            LinearLayout.LayoutParams gap = CgUi.lp(a, DxUi.MATCH, DxUi.WRAP, 0, 8, 0, 0);
            if (n[3] > 0) attention.addView(DxUi.item(a, CgIcons.WARNING, "عناوين فيها أخطاء", "روابط أو بيانات ناقصة", String.valueOf(n[3]), CgCfg.BAD,
                    open(a, "errors")), gap);
            if (n[4] > 0) attention.addView(DxUi.item(a, CgIcons.FILE, "بدون ترجمة", "أفلام وحلقات لا ترجمة لها", String.valueOf(n[4]), CgCfg.WARN,
                    open(a, "no_subtitle")), gap);
            if (drafts > 0) {
                final Activity act = a;
                attention.addView(DxUi.item(a, CgIcons.DRAFT, "مسودات غير مكتملة", "أكمل ما بدأته", String.valueOf(drafts), CgCfg.ACCENT, new Runnable() {
                    @Override public void run() { CxNav.drafts(act); }
                }), gap);
            }
            attention.setLayoutParams(CgUi.lp(a, DxUi.MATCH, DxUi.WRAP, 0, 0, 0, 10));
            box.addView(attention);
        }

        long now = System.currentTimeMillis();
        double[] per = DxStats.addedPerDay(d.items, 14, now);
        DxUi.BarChart bars = new DxUi.BarChart(a);
        bars.setData(per, DxStats.dayLabels(14, now), CgCfg.ACCENT_D);
        double last7 = 0, prev7 = 0;
        for (int i = 0; i < 14; i++) { if (i >= 7) last7 += per[i]; else prev7 += per[i]; }
        String t = DxStats.trend(last7, prev7);
        box.addView(DxUi.chartCard(a, "المضاف آخر 14 يوماً",
                DxUi.num(DxStats.sum(per)) + " عنوان" + (t.length() > 0 ? "  ·  آخر 7 أيام " + t : ""), bars, 150));

        DxUi.Donut donut = new DxUi.Donut(a);
        double total = n[0] + n[1] + n[2];
        donut.setData(new double[]{n[0], n[1], n[2]}, new int[]{NxUi.BLUE, NxUi.VIOLET, NxUi.TEAL}, DxUi.num(total), "عنوان");
        LinearLayout split = DxUi.card(a);
        split.addView(CgUi.text(a, "توزيع المحتوى", 15, CgCfg.TEXT, true));
        LinearLayout row = CgUi.hbox(a);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(donut, new LinearLayout.LayoutParams(DxUi.dp(a, 150), DxUi.dp(a, 150)));
        LinearLayout legend = CgUi.vbox(a);
        legend.addView(DxUi.legend(a, NxUi.BLUE, "أفلام", DxUi.num(n[0])));
        legend.addView(DxUi.legend(a, NxUi.VIOLET, "مسلسلات", DxUi.num(n[1])));
        legend.addView(DxUi.legend(a, NxUi.TEAL, "حلقات", DxUi.num(n[2])));
        if (n[5] > 0) legend.addView(DxUi.legend(a, CgCfg.WARN, "مميّز", DxUi.num(n[5])));
        if (n[6] > 0) legend.addView(DxUi.legend(a, CgCfg.GRAY, "مخفي", DxUi.num(n[6])));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, DxUi.WRAP, 1f);
        ll.setMarginStart(DxUi.dp(a, 14));
        row.addView(legend, ll);
        split.addView(row, CgUi.lp(a, DxUi.MATCH, DxUi.WRAP, 0, 10, 0, 0));
        split.setLayoutParams(CgUi.lp(a, DxUi.MATCH, DxUi.WRAP, 0, 0, 0, 10));
        box.addView(split);
    }

    private static Runnable open(final Activity a, final String preset) {
        return new Runnable() {
            @Override public void run() { openContent(a, preset, -1); }
        };
    }

    private static View clickable(final Activity a, View kpi, String preset, final int kind) {
        kpi.setClickable(true);
        kpi.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openContent(a, null, kind); }
        });
        return kpi;
    }
}
