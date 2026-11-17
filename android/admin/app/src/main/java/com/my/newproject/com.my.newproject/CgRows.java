package com.my.newproject;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** Row/card builders shared by list screens. Color is always paired with a text reason. */
final class CgRows {

    private CgRows() {}

    static TextView statusBadge(Context c, int status) {
        return CgUi.badge(c, CgData.statusLabel(status), CgUi.levelColor(status));
    }

    static String displayTitle(CgData d, JSONObject it) {
        int k = CgData.kind(it);
        String title = CgData.s(it, "title");
        if (k == CgData.K_EPISODE) {
            JSONObject h = d.headerOf(it);
            String st = h == null ? "" : CgData.s(h, "title");
            String code = CgData.epCode(it);
            if (st.length() == 0) st = "حلقة غير مرتبطة";
            return st + " — " + code + (title.length() > 0 ? " · " + title : "");
        }
        return title.length() > 0 ? title : "(بدون عنوان)";
    }

    static String posterOf(JSONObject it) {
        String p = CgData.s(it, "poster");
        if (p.length() == 0) p = CgData.s(it, "series_poster");
        if (p.length() == 0) p = CgData.s(it, "season_poster");
        return p;
    }

    /** One list row for any movie/series/episode. */
    static View itemRow(CgBase a, CgData d, JSONObject it, boolean selecting, boolean selected,
                        View.OnClickListener click, View.OnLongClickListener longClick) {
        LinearLayout row = CgUi.hbox(a);
        row.setBackground(CgUi.bg(a, selected ? CgUi.alpha(CgCfg.ACCENT, 0x33) : CgCfg.CARD, 14,
                selected ? CgCfg.ACCENT : CgCfg.STROKE));
        int p = CgUi.dp(a, 10);
        row.setPadding(p, p, p, p);
        row.setClickable(true);
        if (click != null) row.setOnClickListener(click);
        if (longClick != null) row.setOnLongClickListener(longClick);

        if (selecting) {
            TextView box = CgUi.text(a, selected ? "☑" : "☐", 22, selected ? CgCfg.ACCENT : CgCfg.MUTED, true);
            box.setGravity(Gravity.CENTER);
            row.addView(box, new LinearLayout.LayoutParams(CgUi.dp(a, 30), CgUi.WRAP));
        }

        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(CgUi.dp(a, 46), CgUi.dp(a, 64));
        ilp.setMargins(0, 0, CgUi.dp(a, 10), 0);
        row.addView(img, ilp);
        CgImg.load(img, posterOf(it), CgUi.dp(a, 96));

        LinearLayout col = CgUi.vbox(a);
        TextView title = CgUi.text(a, displayTitle(d, it), 14.5f, CgCfg.TEXT, true);
        title.setMaxLines(2);
        col.addView(title, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        int k = CgData.kind(it);
        StringBuilder meta = new StringBuilder(CgData.kindLabel(k));
        String section = CgData.s(it, "section_name");
        if (section.length() > 0) meta.append(" • ").append(section);
        if (k == CgData.K_SERIES) meta.append(" • ").append(d.episodesOf(it).size()).append(" حلقة");
        TextView m = CgUi.muted(a, meta.toString());
        m.setMaxLines(1);
        col.addView(m, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        int st = d.status(it);
        String reason = d.statusReason(it);
        TextView r = CgUi.text(a, "● " + reason, 12, CgUi.levelColor(st), false);
        r.setMaxLines(2);
        col.addView(r, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        LinearLayout tags = CgUi.hbox(a);
        boolean any = false;
        if (CgData.b(it, "featured")) {
            tags.addView(CgUi.badge(a, "★ مميز", CgCfg.WARN), tagLp(a));
            any = true;
        }
        if (CgData.b(it, "hidden")) {
            tags.addView(CgUi.badge(a, "مخفي", CgCfg.GRAY), tagLp(a));
            any = true;
        }
        if (d.noteOf(it).length() > 0) {
            tags.addView(CgUi.badge(a, "ملاحظة", CgCfg.ACCENT), tagLp(a));
            any = true;
        }
        if (k != CgData.K_SERIES) {
            int sub = d.subState(it);
            if (sub != CgData.SUB_OK) {
                tags.addView(CgUi.badge(a, CgData.subLabel(sub), CgData.subColor(sub)), tagLp(a));
                any = true;
            }
        }
        if (any) col.addView(tags, CgUi.lp(a, CgUi.WRAP, CgUi.WRAP, 0, 4, 0, 0));
        row.addView(col, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));

        row.addView(statusBadge(a, st), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        return row;
    }

    private static LinearLayout.LayoutParams tagLp(Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        p.setMargins(0, 0, CgUi.dp(c, 6), 0);
        return p;
    }
}
