package com.my.newproject;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** "Already exists" dialog showing the existing item (poster, name, ID) with up to three actions. */
final class CxDup {

    private CxDup() {}

    static String reasonText(String r) {
        if ("tmdb".equals(r)) return "نفس TMDB";
        if ("imdb".equals(r)) return "نفس IMDb";
        if ("slug".equals(r)) return "نفس الـ Slug";
        if ("title_year".equals(r)) return "نفس الاسم والسنة";
        return r;
    }

    /** labels / actions: index 0 = neutral open, 1 = main (primary), 2 = cancel. Null actions are skipped. */
    static void show(final Activity a, String title, JSONObject dup, String[] labels, final Runnable[] actions) {
        LinearLayout box = CgUi.vbox(a);
        box.setPadding(CgUi.dp(a, 16), CgUi.dp(a, 8), CgUi.dp(a, 16), CgUi.dp(a, 4));
        LinearLayout row = CgUi.hbox(a);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        CgImg.load(iv, CxJ.s(dup, "poster"), 200);
        row.addView(iv, new LinearLayout.LayoutParams(CgUi.dp(a, 64), CgUi.dp(a, 96)));
        LinearLayout col = CgUi.vbox(a);
        col.addView(CgUi.text(a, CxJ.s(dup, "title"), 15, CgCfg.TEXT, true));
        if (CxJ.s(dup, "year").length() > 0) col.addView(CgUi.muted(a, "السنة: " + CxJ.s(dup, "year")));
        TextView idv = CgUi.muted(a, "ID: " + CxJ.s(dup, "id"));
        CgUi.ltr(idv);
        col.addView(idv);
        JSONArray reasons = dup.optJSONArray("reasons");
        StringBuilder rs = new StringBuilder();
        for (int i = 0; reasons != null && i < reasons.length(); i++) {
            if (rs.length() > 0) rs.append(" + ");
            rs.append(reasonText(reasons.optString(i)));
        }
        if (rs.length() > 0) col.addView(CgUi.text(a, rs.toString(), 12, CgCfg.WARN, true));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        clp.setMargins(CgUi.dp(a, 12), 0, 0, 0);
        row.addView(col, clp);
        box.addView(row);
        final AlertDialog dlg = CgUi.dialog(a).setTitle(title).setView(box).create();
        LinearLayout bar = CgUi.hbox(a);
        int[] styles = {1, 0, 3};
        float[] weights = {1f, 1.4f, 1f};
        for (int i = 0; i < 3 && i < labels.length; i++) {
            final Runnable act = i < actions.length ? actions[i] : null;
            TextView b = CgUi.smallButton(a, labels[i], styles[i]);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dlg.dismiss();
                    if (act != null) act.run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, CgUi.WRAP, weights[i]);
            lp.setMargins(CgUi.dp(a, 3), 0, CgUi.dp(a, 3), 0);
            bar.addView(b, lp);
        }
        box.addView(bar, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 14, 0, 4));
        dlg.show();
    }
}
