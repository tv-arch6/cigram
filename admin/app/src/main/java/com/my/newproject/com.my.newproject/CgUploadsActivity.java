package com.my.newproject;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/** «عمليات الرفع»: every direct upload (running, stopped, failed, finished) with resume / cancel / copy link. */
public class CgUploadsActivity extends CgBase implements CgUploadManager.Listener {

    private CgUploadManager mgr;
    private long lastDraw;
    private boolean drawPending;

    @Override
    protected String screenTitle() {
        return "عمليات الرفع";
    }

    @Override
    protected void onBuild() {
        mgr = CgUploadManager.get(this);
        mgr.addListener(this);
        draw();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mgr != null) draw();
    }

    @Override
    protected void onDestroy() {
        if (mgr != null) mgr.removeListener(this);
        super.onDestroy();
    }

    @Override
    public void onSession(CgUploadSession s) {
        if (!alive()) return;
        // progress events are frequent: redraw at most twice a second
        long now = System.currentTimeMillis();
        if (now - lastDraw < 500 && !"REMOVED".equals(s.state) && !s.isFinished() && !CgUploadSession.PAUSED.equals(s.state) && !CgUploadSession.FAILED.equals(s.state)) {
            if (!drawPending) {
                drawPending = true;
                ui().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        drawPending = false;
                        if (alive()) draw();
                    }
                }, 500);
            }
            return;
        }
        draw();
    }

    private void draw() {
        lastDraw = System.currentTimeMillis();
        LinearLayout p = scrollBody();
        ArrayList<CgUploadSession> all = mgr.list();
        if (all.isEmpty()) {
            p.addView(CgUi.empty(this, "لا توجد عمليات رفع", "ابدأ من «رفع فيديو من الجهاز» في شاشة إضافة فيلم أو حلقة."));
            return;
        }
        for (int i = 0; i < all.size(); i++) p.addView(card(all.get(i)), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
    }

    private View card(final CgUploadSession s) {
        LinearLayout c = CgUi.card(this);
        String what = s.title.length() > 0 ? s.title : s.srcName;
        LinearLayout top = CgUi.hbox(this);
        top.addView(CgUi.text(this, what, 15, CgCfg.TEXT, true), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        int color = CgUploadSession.COMPLETED.equals(s.state) ? CgCfg.GOOD
                : CgUploadSession.FAILED.equals(s.state) ? CgCfg.BAD
                : s.isActive() ? CgCfg.ACCENT : CgCfg.GRAY;
        top.addView(CgUi.badge(this, CgUploadSession.stateLabel(s.state), color));
        c.addView(top);
        String sub = (s.isEpisode() ? "حلقة S" + two(s.season) + "E" + two(s.episode) : "فيلم") + " • " + s.srcName + " • " + CgUi.sizeText(s.srcSize);
        c.addView(CgUi.muted(this, sub));

        if (!s.isFinished()) {
            long done = s.isActive() ? s.bytesDone : s.partsBytes();
            float f = s.isActive() ? s.stageFraction : (s.uploadSize > 0 ? (float) done / (float) s.uploadSize : 0f);
            f = Math.max(0f, Math.min(1f, f));
            c.addView(CgUi.text(this, Math.round(f * 100f) + "%" + (s.isActive() && s.speedBps > 1 ? "  •  " + CgUi.sizeText((long) s.speedBps) + "/ث" : "")
                    + (s.totalParts > 0 ? "  •  " + s.partsDone() + "/" + s.totalParts + " جزء" : ""), 12.5f, CgCfg.ACCENT, false));
        }
        if (s.message != null && s.message.length() > 0) {
            c.addView(CgUi.text(this, s.message, 12.5f, CgUploadSession.FAILED.equals(s.state) ? CgCfg.BAD : CgCfg.WARN, false));
        }
        if (CgUploadSession.COMPLETED.equals(s.state) && s.publicUrl.length() > 0) {
            TextView u = CgUi.text(this, s.publicUrl, 12, CgCfg.ACCENT, false);
            CgUi.ltr(u);
            c.addView(u);
        }

        LinearLayout actions = CgUi.hbox(this);
        final String id = s.id;
        if (s.isActive() || s.isResumable()) {
            actions.addView(small(s.isActive() ? "عرض التقدم" : "استكمال / تفاصيل", 0, new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(CgUploadsActivity.this, CgMediaUploadActivity.class);
                    i.putExtra("session_id", id);
                    startActivity(i);
                }
            }));
        }
        if (!s.isFinished()) {
            actions.addView(small("إلغاء", 2, new Runnable() {
                @Override
                public void run() {
                    CgUi.confirm(CgUploadsActivity.this, "إلغاء الرفع", "سيتم إيقاف العملية وحذف الأجزاء المرفوعة والنسخة المؤقتة. الفيديو الأصلي لن يُمس.", "إلغاء الرفع", true, new Runnable() {
                        @Override
                        public void run() {
                            mgr.cancel(id);
                            ui().postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    if (alive()) draw();
                                }
                            }, 800);
                        }
                    });
                }
            }));
        } else {
            if (CgUploadSession.COMPLETED.equals(s.state) && s.publicUrl.length() > 0) {
                final String link = s.publicUrl;
                actions.addView(small("نسخ الرابط", 1, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("video", link));
                            CgUi.toast(CgUploadsActivity.this, "تم نسخ الرابط.");
                        } catch (Exception e) {
                            Log.w(CgMediaAnalyzer.TAG, "copy failed: " + e.getMessage());
                        }
                    }
                }));
            }
            actions.addView(small("إزالة من القائمة", 3, new Runnable() {
                @Override
                public void run() {
                    mgr.remove(id);
                    draw();
                }
            }));
        }
        c.addView(actions, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 0, 0));
        return c;
    }

    private TextView small(String label, int style, final Runnable r) {
        TextView b = CgUi.smallButton(this, label, style);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        lp.setMargins(0, 0, CgUi.dp(this, 8), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private static String two(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }
}
