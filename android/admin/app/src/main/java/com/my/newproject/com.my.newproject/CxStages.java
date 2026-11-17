package com.my.newproject;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.PorterDuff;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Staged progress dialog: "المرحلة 2 من 5", stage name, file name, percent and size. Thread-safe. */
final class CxStages {

    private final Activity a;
    private final AlertDialog dialog;
    private final String[] names;
    private final TextView head;
    private final TextView stageName;
    private final TextView detail;
    private final TextView pct;
    private final ProgressBar bar;
    private final LinearLayout actions;
    private final LinearLayout box;
    private boolean closed;

    static CxStages show(Activity a, String title, String[] names) {
        return new CxStages(a, title, names);
    }

    private CxStages(Activity a, String title, String[] names) {
        this.a = a;
        this.names = names;
        box = CgUi.vbox(a);
        box.setPadding(CgUi.dp(a, 20), CgUi.dp(a, 8), CgUi.dp(a, 20), CgUi.dp(a, 4));
        head = CgUi.text(a, "", 12.5f, CgCfg.ACCENT, true);
        box.addView(head);
        stageName = CgUi.text(a, "", 16, CgCfg.TEXT, true);
        stageName.setPadding(0, CgUi.dp(a, 4), 0, CgUi.dp(a, 10));
        box.addView(stageName);
        bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        try {
            bar.getProgressDrawable().setColorFilter(CgCfg.ACCENT, PorterDuff.Mode.SRC_IN);
        } catch (Exception ignored) { }
        box.addView(bar, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.dp(a, 10)));
        LinearLayout row = CgUi.hbox(a);
        detail = CgUi.muted(a, "");
        detail.setSingleLine(true);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(detail, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        pct = CgUi.text(a, "0%", 12.5f, CgCfg.TEXT, true);
        row.addView(pct, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        box.addView(row, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 4));
        actions = CgUi.hbox(a);
        actions.setVisibility(View.GONE);
        box.addView(actions, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        try {
            box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        } catch (Throwable ignored) { }
        dialog = CgUi.dialog(a).setTitle(title).setView(box).setCancelable(false).create();
        dialog.show();
        stage(0, "");
    }

    /** Moves to stage i (0-based). Percent follows the stage position unless progress() refines it. */
    void stage(final int i, final String d) {
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (closed) return;
                int total = Math.max(1, names.length);
                head.setText("المرحلة " + (i + 1) + " من " + total);
                stageName.setText(i >= 0 && i < names.length ? names[i] : "");
                detail.setText(d == null ? "" : d);
                int p = Math.min(100, (i * 100) / total);
                bar.setProgress(p);
                pct.setText(p + "%");
            }
        });
    }

    /** Refines the percent inside the current stage. */
    void progress(final int percent, final String d) {
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (closed) return;
                int p = Math.max(0, Math.min(100, percent));
                bar.setProgress(p);
                pct.setText(p + "%");
                if (d != null) detail.setText(d);
            }
        });
    }

    void fail(final String message, final Runnable retry) {
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (closed) return;
                head.setText("توقفت العملية");
                head.setTextColor(CgCfg.BAD);
                stageName.setText(message);
                stageName.setTextSize(14);
                bar.setVisibility(View.GONE);
                pct.setVisibility(View.GONE);
                detail.setText("لم يتم حفظ أي شيء ناقص — البيانات الحالية سليمة.");
                actions.removeAllViews();
                actions.setVisibility(View.VISIBLE);
                if (retry != null) {
                    TextView r = CgUi.button(a, "إعادة المحاولة", 0);
                    r.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dismiss();
                            retry.run();
                        }
                    });
                    actions.addView(r, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
                }
                TextView c = CgUi.button(a, "إغلاق", 1);
                c.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dismiss();
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
                lp.setMargins(CgUi.dp(a, 8), 0, 0, 0);
                actions.addView(c, lp);
            }
        });
    }

    void dismiss() {
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                closed = true;
                try {
                    if (dialog.isShowing()) dialog.dismiss();
                } catch (Exception ignored) { }
            }
        });
    }
}
