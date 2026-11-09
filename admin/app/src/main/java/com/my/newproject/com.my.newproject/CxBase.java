package com.my.newproject;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** Base of the content-management screens: step bar, scrolling page, bottom action bar, snackbar, tasks with retry. */
public abstract class CxBase extends CgBase {

    protected CxUi.StepBar steps;
    protected LinearLayout page;
    protected LinearLayout bottom;
    private ScrollView pageScroll;
    private View snackView;
    private Runnable snackHide;

    interface Job<T> {
        T run() throws Exception;
    }

    interface Cb<T> {
        void ok(T result);

        void fail(CxApi.Failure failure);
    }

    interface Ok<T> {
        void ok(T result);
    }

    /** Lays out [step bar][scrolling page][bottom bar]. Call first thing in onBuild(). */
    /** After a failed data load the base removed our views; put them back so retry callbacks have a visible page. */
    @Override
    protected void ensureData(final Runnable ready) {
        super.ensureData(new Runnable() {
            @Override
            public void run() {
                restoreLayout();
                ready.run();
            }
        });
    }

    private void restoreLayout() {
        if (pageScroll == null || pageScroll.getParent() != null) return;
        if (steps != null && steps.getParent() == null) body.addView(steps, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        body.addView(pageScroll, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));
    }

    protected void initCx(String[] stepNames) {
        body.removeAllViews();
        if (stepNames != null) {
            steps = new CxUi.StepBar(this, stepNames);
            body.addView(steps, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }
        pageScroll = new ScrollView(this);
        pageScroll.setVerticalScrollBarEnabled(false);
        pageScroll.setFillViewport(false);
        page = CgUi.vbox(this);
        int p = CgUi.dp(this, 12);
        page.setPadding(p, p, p, CgUi.dp(this, 24));
        pageScroll.addView(page, new ScrollView.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        body.addView(pageScroll, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));
        if (bottom == null) {
            bottom = CxUi.bottomBar(this);
            column.addView(bottom, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }
    }

    /** Clears the page for the next step and scrolls to the top. */
    protected void newPage(int stepIndex) {
        if (steps != null) steps.set(stepIndex);
        page.removeAllViews();
        pageScroll.scrollTo(0, 0);
        bottom.removeAllViews();
        bottom.setVisibility(View.VISIBLE);
        // soft fade + rise between steps
        page.setAlpha(0f);
        page.setTranslationY(CgUi.dp(this, 10));
        page.animate().alpha(1f).translationY(0f).setDuration(200).start();
    }

    protected void setBottomVisible(boolean v) {
        if (bottom != null) bottom.setVisibility(v ? View.VISIBLE : View.GONE);
    }

    protected TextView bottomButton(String label, int style, float weight, View.OnClickListener l) {
        return CxUi.barButton(this, bottom, label, style, weight, l);
    }

    protected ScrollView scroller() {
        return pageScroll;
    }

    protected void scrollToTop() {
        if (pageScroll != null) pageScroll.scrollTo(0, 0);
    }

    protected void scrollTo(final View v) {
        if (pageScroll == null || v == null) return;
        pageScroll.post(new Runnable() {
            @Override
            public void run() {
                int y = 0;
                View cur = v;
                while (cur != null && cur != page) {
                    y += cur.getTop();
                    android.view.ViewParent par = cur.getParent();
                    cur = par instanceof View ? (View) par : null;
                }
                pageScroll.smoothScrollTo(0, Math.max(0, y - CgUi.dp(CxBase.this, 8)));
            }
        });
    }

    // ------------------------------------------------------------------ background jobs

    protected <T> void exec(final Job<T> job, final Cb<T> cb) {
        CgHttp.bg(new Runnable() {
            @Override
            public void run() {
                T r = null;
                CxApi.Failure f = null;
                try {
                    r = job.run();
                } catch (CxApi.Failure e) {
                    f = e;
                } catch (Throwable e) {
                    f = new CxApi.Failure(CgHttp.friendly(e.getMessage()), "ERROR", null, 0);
                }
                final T fr = r;
                final CxApi.Failure ff = f;
                if (isFinishing()) return;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!alive()) return;
                        if (ff != null) cb.fail(ff);
                        else cb.ok(fr);
                    }
                });
            }
        });
    }

    /** Job with the loading overlay; failures show a friendly dialog with a retry button. */
    protected <T> void task(final String loading, final Job<T> job, final Ok<T> ok, final Runnable retry) {
        if (loading != null) showLoading(loading);
        exec(job, new Cb<T>() {
            @Override
            public void ok(T result) {
                if (loading != null) hideLoading();
                ok.ok(result);
            }

            @Override
            public void fail(CxApi.Failure failure) {
                if (loading != null) hideLoading();
                failDialog(failure.getMessage(), retry);
            }
        });
    }

    protected void failDialog(String message, final Runnable retry) {
        if (!alive()) return;
        AlertDialog.Builder b = CgUi.dialog(this).setTitle("تعذر إكمال العملية").setMessage(message)
                .setNegativeButton("إغلاق", null);
        if (retry != null) {
            b.setPositiveButton("إعادة المحاولة", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    retry.run();
                }
            });
        }
        b.show();
    }

    // ------------------------------------------------------------------------ snackbar

    protected void snack(String msg, int level) {
        if (!alive()) return;
        if (snackView != null && snackView.getParent() != null) frame.removeView(snackView);
        TextView t = CgUi.text(this, msg, 13.5f, CgCfg.TEXT, false);
        t.setBackground(CgUi.bg(this, CgCfg.CARD2, 14, CxUi.levelColor(level)));
        t.setPadding(CgUi.dp(this, 14), CgUi.dp(this, 10), CgUi.dp(this, 14), CgUi.dp(this, 10));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP, Gravity.BOTTOM);
        int m = CgUi.dp(this, 12);
        lp.setMargins(m, 0, m, CgUi.dp(this, 84));
        frame.addView(t, lp);
        snackView = t;
        if (snackHide != null) ui().removeCallbacks(snackHide);
        snackHide = new Runnable() {
            @Override
            public void run() {
                if (snackView != null && snackView.getParent() != null) frame.removeView(snackView);
                snackView = null;
            }
        };
        ui().postDelayed(snackHide, 3500);
    }

    // ------------------------------------------------------------------- draft banner

    /** "لديك مسودة غير مكتملة  [متابعة] [تجاهل]" */
    protected View draftBanner(JSONObject draft, final Runnable resume, final Runnable discard) {
        LinearLayout box = CgUi.vbox(this);
        box.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.WARN, 0x18), 14, CgCfg.WARN));
        box.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 10), CgUi.dp(this, 12), CgUi.dp(this, 10));
        String title = draft.optString("title", "");
        box.addView(CgUi.text(this, "لديك مسودة غير مكتملة" + (title.length() > 0 ? ": «" + title + "»" : ""), 13.5f, CgCfg.TEXT, true));
        box.addView(CgUi.muted(this, "آخر تعديل " + CxUi.agoMs(draft.optLong("updated"))));
        LinearLayout row = CgUi.hbox(this);
        TextView go = CgUi.smallButton(this, "متابعة", 0);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resume.run();
            }
        });
        row.addView(go, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        TextView drop = CgUi.smallButton(this, "تجاهل", 1);
        drop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                discard.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        lp.setMargins(CgUi.dp(this, 8), 0, 0, 0);
        row.addView(drop, lp);
        box.addView(row, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 0, 0));
        box.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        return box;
    }

    protected View sectionLabel(String text) {
        return CgUi.sectionTitle(this, text);
    }
}
