package com.my.newproject;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Direct upload from the phone: pick a video -> real analysis -> choose a mode -> compress (optional)
 * -> multipart upload -> verified link. The work itself runs in CgUploadManager (background service),
 * this screen only shows it, so it can be closed and reopened without stopping anything.
 * Result: RESULT_OK with extra "video_url" = the public URL of the verified file.
 */
public class CgMediaUploadActivity extends CgBase implements CgUploadManager.Listener {

    private static final int REQ_PICK = 7711;
    private static final int REQ_NOTIF = 7712;

    private CgUploadManager mgr;
    private String kind = "movie";
    private String slug = "";
    private String title = "";
    private String poster = "";
    private int season;
    private int episode;
    private String sessionId = "";
    private boolean relink;

    private CgMediaInfo info;
    private CgSmartPlan[] plans;
    private int chosen = -1;
    private LinearLayout[] modeCards;

    private String viewKey = "";
    private TextView pctView;
    private TextView stageView;
    private TextView statLine1;
    private TextView statLine2;
    private TextView statLine3;
    private TextView msgView;
    private View barFill;
    private View barRest;
    private boolean decisionShown;

    @Override
    protected String screenTitle() {
        return "رفع فيديو من الجهاز";
    }

    @Override
    protected void onBuild() {
        mgr = CgUploadManager.get(this);
        Intent in = getIntent();
        if (in.getStringExtra("kind") != null) kind = in.getStringExtra("kind");
        if (in.getStringExtra("slug") != null) slug = in.getStringExtra("slug");
        if (in.getStringExtra("title") != null) title = in.getStringExtra("title");
        if (in.getStringExtra("poster") != null) poster = in.getStringExtra("poster");
        season = in.getIntExtra("season", 0);
        episode = in.getIntExtra("episode", 0);
        if (in.getStringExtra("session_id") != null) sessionId = in.getStringExtra("session_id");
        relink = in.getBooleanExtra("relink", false);
        mgr.addListener(this);
        // The notification permission is asked when the upload starts (begin), never together with the file
        // picker: two system screens opened at once could close the picker and look like "nothing happened".

        if (sessionId.length() > 0) {
            CgUploadSession s = mgr.snapshot(sessionId);
            if (s == null) {
                CgUi.toast(this, "عملية الرفع غير موجودة.");
                finish();
                return;
            }
            if (relink) {
                pickFile();
            } else {
                showProgress(s);
            }
        } else {
            pickFile();
        }
    }

    @Override
    protected void onDestroy() {
        if (mgr != null) mgr.removeListener(this);
        super.onDestroy();
    }

    // ------------------------------------------------------------------- notification permission

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            CgUploadManager.askBatteryExemption(this);
            return;
        }
        try {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
                return;
            }
            CgUploadManager.askBatteryExemption(this);
        } catch (Throwable t) {
            Log.w(CgMediaAnalyzer.TAG, "notification permission request failed: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------------------ pick + analyze

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "application/x-matroska", "application/octet-stream"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Log.e(CgMediaAnalyzer.TAG, "picker failed", e);
            CgUi.info(this, "تعذر فتح منتقي الملفات", "لا يوجد تطبيق لاختيار الملفات على هذا الهاتف.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK) return;
        if (res != RESULT_OK || data == null || data.getData() == null) {
            if (sessionId.length() == 0 || relink) {
                if (info == null && (sessionId.length() == 0 || relink)) {
                    setResult(RESULT_CANCELED);
                    finish();
                }
            }
            return;
        }
        final Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            Log.w(CgMediaAnalyzer.TAG, "persistable permission not granted: " + e.getMessage());
        }
        if (relink) {
            relinkSession(uri);
            return;
        }
        analyze(uri);
    }

    private void relinkSession(Uri uri) {
        CgMediaInfo tmp = new CgMediaInfo();
        CgMediaAnalyzer.queryNameAndSize(this, uri, tmp);
        String msg = mgr.relink(sessionId, uri, tmp.size, tmp.fileName);
        if (msg.length() > 0) {
            CgUi.info(this, "ملف غير مطابق", msg);
            finish();
            return;
        }
        String err = mgr.start(sessionId);
        if (err.length() > 0) CgUi.info(this, "الرفع", err);
        relink = false;
        CgUploadSession s = mgr.snapshot(sessionId);
        if (s != null) showProgress(s);
    }

    private void analyze(final Uri uri) {
        showLoading("جاري تحليل الفيديو...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                CgMediaInfo result = null;
                String error = null;
                try {
                    result = CgMediaAnalyzer.analyze(CgMediaUploadActivity.this, uri);
                    if (!CgMediaAnalyzer.supportedName(result.fileName)) {
                        error = "الصيغة غير مدعومة. الصيغ المسموحة: mp4 / mkv / mov / webm.";
                    } else if (result.size <= 0) {
                        error = "تعذر معرفة حجم الملف.";
                    }
                } catch (Exception e) {
                    Log.e(CgMediaAnalyzer.TAG, "analysis failed", e);
                    error = e.getMessage() == null ? "تعذر تحليل الفيديو." : e.getMessage();
                }
                final CgMediaInfo fi = result;
                final String fe = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!alive()) return;
                        hideLoading();
                        if (fe != null) {
                            CgUi.info(CgMediaUploadActivity.this, "تعذر استخدام الملف", fe);
                            setResult(RESULT_CANCELED);
                            finish();
                            return;
                        }
                        info = fi;
                        afterAnalysis();
                    }
                });
            }
        }).start();
    }

    private void afterAnalysis() {
        CgUploadSession dup = mgr.findDuplicate(info.uri, kind, slug, season, episode);
        if (dup != null) {
            final String dupId = dup.id;
            CgUi.dialog(this).setTitle("لديك عملية رفع غير مكتملة لهذا الملف")
                    .setMessage("هذا الفيديو نفسه له عملية رفع سابقة لم تكتمل. هل تريد متابعتها بدل البدء من جديد؟")
                    .setCancelable(false)
                    .setPositiveButton("متابعة السابقة", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            sessionId = dupId;
                            CgUploadSession s = mgr.snapshot(dupId);
                            if (s != null && s.isResumable() && !s.needsDecision) {
                                String err = mgr.start(dupId);
                                if (err.length() > 0) CgUi.toast(CgMediaUploadActivity.this, err);
                            }
                            s = mgr.snapshot(dupId);
                            if (s != null) showProgress(s);
                        }
                    })
                    .setNegativeButton("بدء جديد", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int w) {
                            mgr.cancel(dupId);
                            showChoice();
                        }
                    }).show();
            return;
        }
        showChoice();
    }

    // ---------------------------------------------------------------------------- choice screen

    private void showChoice() {
        viewKey = "choice";
        plans = CgSmartPlan.plans(info, CgUploadManager.tempDir(this));
        chosen = -1;
        for (int i = 0; i < plans.length; i++) {
            if (plans[i].recommended && plans[i].available()) chosen = i;
        }
        if (chosen < 0) chosen = 0;
        LinearLayout p = scrollBody();

        LinearLayout fileCard = CgUi.card(this);
        fileCard.addView(CgUi.text(this, info.fileName, 15, CgCfg.TEXT, true));
        fileCard.addView(CgUi.space(this, 6));
        fileCard.addView(row("الحجم", CgUi.sizeText(info.size)));
        fileCard.addView(row("الصيغة", info.container));
        fileCard.addView(row("المدة", CgUi.durationText(info.durationMs)));
        fileCard.addView(row("الدقة", info.resolutionText() + (info.hdr ? "  (HDR)" : "")));
        fileCard.addView(row("الإطارات", info.fpsText() + " fps"));
        fileCard.addView(row("الفيديو", info.videoLabel + " • " + CgMediaInfo.bitrateText(info.videoBitrate, info.videoBitrateEstimated)));
        fileCard.addView(row("الصوت", info.hasAudio ? (info.audioLabel + (info.audioChannels > 0 ? " • " + info.audioChannels + " قناة" : "")) : "لا يوجد"));
        if (info.audioTracks > 1) fileCard.addView(row("مسارات الصوت", String.valueOf(info.audioTracks)));
        if (info.subtitleTracks > 0) fileCard.addView(row("ترجمة مدمجة", info.subtitleTracks + " (تبقى فقط في «الجودة الأصلية»)"));
        p.addView(fileCard, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        if ("episode".equals(kind)) addNumberHint(p);

        if (!mgr.isOnline()) p.addView(warn("لا يوجد اتصال بالإنترنت الآن. يمكنك البدء وسيُستكمل الرفع عند عودة الاتصال."));
        else if (mgr.isMetered()) p.addView(warn("أنت تستخدم بيانات الجوال. رفع الأفلام يستهلك حجماً كبيراً من الباقة."));

        p.addView(CgUi.sectionTitle(this, "اختر طريقة الرفع"));
        modeCards = new LinearLayout[plans.length];
        for (int i = 0; i < plans.length; i++) {
            final int idx = i;
            CgSmartPlan pl = plans[i];
            LinearLayout c = CgUi.card(this);
            c.addView(CgUi.text(this, (pl.recommended ? "★ " : "") + pl.title, 15, pl.available() ? CgCfg.TEXT : CgCfg.GRAY, true));
            c.addView(CgUi.muted(this, pl.description));
            if (pl.available()) {
                String est;
                if (pl.mode == CgSmartPlan.MODE_ORIGINAL) est = "الحجم: " + CgUi.sizeText(info.size) + " (كما هو)";
                else est = "الحجم المتوقع: ≈ " + CgUi.sizeText(pl.estimatedBytes) + (pl.savedPercent() > 0 ? "  •  توفير ≈ " + pl.savedPercent() + "%" : "");
                c.addView(CgUi.text(this, est, 12.5f, CgCfg.ACCENT, false));
            } else {
                c.addView(CgUi.text(this, "غير متاح: " + pl.blocked, 12.5f, CgCfg.BAD, false));
            }
            if (pl.note.length() > 0) c.addView(CgUi.text(this, pl.note, 12, CgCfg.WARN, false));
            if (pl.available()) {
                c.setClickable(true);
                c.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        chosen = idx;
                        paintModes();
                    }
                });
            }
            modeCards[i] = c;
            p.addView(c, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
        paintModes();

        TextView note = CgUi.muted(this, "الفيديو الأصلي على هاتفك لا يُعدَّل ولا يُحذف أبداً. الضغط يتم على نسخة مؤقتة تُحذف بعد التحقق من الرفع. لا يتم تقليل الدقة تلقائياً.");
        p.addView(note, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 10));

        TextView go = CgUi.button(this, "ابدأ الرفع", 0);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onStartClicked();
            }
        });
        p.addView(go, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        TextView other = CgUi.button(this, "اختيار ملف آخر", 1);
        other.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile();
            }
        });
        p.addView(other, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 0));
    }

    /** The file name usually says which episode it is: warn when the form says something else, and offer the fix. */
    private void addNumberHint(LinearLayout p) {
        final int[] f = CxNames.parse(info.fileName);
        if (f == null) {
            p.addView(warn("لم يُعرف رقم الموسم والحلقة من اسم الملف، سيُحفظ في R2 باسم " + CxNames.code(season, episode) + " كما في النموذج."));
            return;
        }
        final int fs = f[0] > 0 ? f[0] : season;
        final int fe = f[1];
        if (fs == season && fe == episode) {
            p.addView(CgUi.text(this, "✔ اسم الملف يطابق " + CxNames.code(season, episode), 12.5f, CgCfg.GOOD, false),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 0, 4, 10));
            return;
        }
        LinearLayout c = CgUi.card(this);
        c.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.WARN, 0x1A), 14, CgCfg.WARN));
        c.addView(CgUi.text(this, "اسم الملف يدل على " + CxNames.code(fs, fe) + " بينما الحلقة المحددة في النموذج " + CxNames.code(season, episode)
                + ". سيُرفع الملف باسم الحلقة المحددة، فتأكد أنها الصحيحة.", 13, CgCfg.TEXT, false));
        TextView use = CgUi.smallButton(this, "استخدام " + CxNames.code(fs, fe), 0);
        use.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                title = title.replace(CxNames.code(season, episode), CxNames.code(fs, fe));
                season = fs;
                episode = fe;
                showChoice();
            }
        });
        c.addView(use, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 0, 0));
        p.addView(c, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
    }

    private void paintModes() {
        if (modeCards == null) return;
        for (int i = 0; i < modeCards.length; i++) {
            boolean sel = i == chosen;
            modeCards[i].setBackground(CgUi.bg(this, sel ? CgUi.alpha(CgCfg.ACCENT, 0x24) : CgCfg.CARD, 14, sel ? CgCfg.ACCENT : CgCfg.STROKE));
        }
    }

    private void onStartClicked() {
        if (chosen < 0 || !plans[chosen].available()) {
            CgUi.toast(this, "اختر طريقة متاحة.");
            return;
        }
        final CgSmartPlan plan = plans[chosen];
        if (mgr.busy()) {
            CgUi.info(this, "رفع جارٍ", "هناك عملية رفع أخرى جارية الآن. انتظر انتهاءها أو ألغِها من «عمليات الرفع».");
            return;
        }
        if (mgr.isMetered()) {
            CgUi.confirm(this, "بيانات الجوال", "ستستهلك بيانات الجوال لرفع ≈ " + CgUi.sizeText(plan.estimatedBytes) + ". هل تريد المتابعة؟", "متابعة", false, new Runnable() {
                @Override
                public void run() {
                    begin(plan);
                }
            });
            return;
        }
        begin(plan);
    }

    private void begin(CgSmartPlan plan) {
        askNotificationPermission();
        CgUploadSession s = mgr.create(info, plan, kind, title, poster, slug, season, episode, "");
        sessionId = s.id;
        String err = mgr.start(s.id);
        if (err.length() > 0) CgUi.info(this, "الرفع", err);
        CgUploadSession now = mgr.snapshot(s.id);
        showProgress(now == null ? s : now);
    }

    // -------------------------------------------------------------------------- progress screen

    private void showProgress(final CgUploadSession s) {
        viewKey = s.state + "|" + s.needsDecision + "|" + s.waitingNetwork;
        LinearLayout p = scrollBody();
        decisionShown = false;

        if (CgUploadSession.COMPLETED.equals(s.state)) {
            buildDone(p, s);
            return;
        }

        LinearLayout head = CgUi.card(this);
        String what = s.title.length() > 0 ? s.title : s.srcName;
        head.addView(CgUi.text(this, what, 16, CgCfg.TEXT, true));
        head.addView(CgUi.muted(this, (s.isEpisode() ? "حلقة" : "فيلم") + " • " + s.srcName + " • " + CgUi.sizeText(s.srcSize)));
        stageView = CgUi.text(this, "", 13.5f, CgCfg.ACCENT, true);
        head.addView(stageView, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        p.addView(head, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        LinearLayout pc = CgUi.card(this);
        pctView = CgUi.text(this, "0%", 30, CgCfg.TEXT, true);
        pctView.setGravity(Gravity.CENTER);
        pc.addView(pctView, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        LinearLayout bar = CgUi.hbox(this);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        bar.setBackground(CgUi.bg(this, CgCfg.CARD2, 6, 0));
        barFill = new View(this);
        barFill.setBackground(CgUi.bg(this, CgCfg.ACCENT, 6, 0));
        barRest = new View(this);
        bar.addView(barFill, new LinearLayout.LayoutParams(0, CgUi.dp(this, 10), 0.001f));
        bar.addView(barRest, new LinearLayout.LayoutParams(0, CgUi.dp(this, 10), 0.999f));
        pc.addView(bar, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 10));
        statLine1 = CgUi.text(this, "", 13, CgCfg.TEXT, false);
        statLine2 = CgUi.text(this, "", 13, CgCfg.MUTED, false);
        statLine3 = CgUi.text(this, "", 13, CgCfg.GOOD, false);
        pc.addView(statLine1);
        pc.addView(statLine2);
        pc.addView(statLine3);
        p.addView(pc, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        msgView = CgUi.text(this, "", 13, CgCfg.WARN, false);
        p.addView(msgView, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 0, 4, 10));

        LinearLayout actions = CgUi.vbox(this);
        buildActions(actions, s);
        p.addView(actions, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        if (s.isActive()) {
            p.addView(CgUi.muted(this, "يمكنك إغلاق هذه الشاشة أو الخروج من التطبيق؛ الرفع يستمر في الخلفية ويظهر إشعار بالتقدم."),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 12, 4, 0));
        }
        TextView list = CgUi.button(this, "عمليات الرفع", 3);
        list.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(CgMediaUploadActivity.this, CgUploadsActivity.class));
            }
        });
        p.addView(list, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        update(s);
        if (s.needsDecision) askDecision(s);
    }

    private void buildActions(LinearLayout box, final CgUploadSession s) {
        final String id = s.id;
        if (s.needsDecision) {
            box.addView(actionButton("الملف موجود في R2 — اختر الإجراء", 0, new Runnable() {
                @Override
                public void run() {
                    CgUploadSession c = mgr.snapshot(id);
                    if (c != null) askDecision(c);
                }
            }));
        } else if (CgUploadSession.UPLOADING.equals(s.state)) {
            box.addView(actionButton("إيقاف مؤقت", 1, new Runnable() {
                @Override
                public void run() {
                    mgr.pause(id);
                }
            }));
        } else if (s.isResumable()) {
            box.addView(actionButton(CgUploadSession.FAILED.equals(s.state) ? "إعادة المحاولة / استكمال" : "استكمال", 0, new Runnable() {
                @Override
                public void run() {
                    String err = mgr.start(id);
                    if (err.length() > 0) CgUi.info(CgMediaUploadActivity.this, "الرفع", err);
                }
            }));
            if (CgUploadSession.FAILED.equals(s.state)) {
                box.addView(actionButton("اختيار الملف من جديد", 1, new Runnable() {
                    @Override
                    public void run() {
                        relink = true;
                        pickFile();
                    }
                }));
            }
            if (s.mode != 0 && !s.tempReady) {
                box.addView(actionButton("رفع النسخة الأصلية بدون ضغط", 1, new Runnable() {
                    @Override
                    public void run() {
                        String err = mgr.switchToOriginal(id);
                        if (err.length() > 0) CgUi.info(CgMediaUploadActivity.this, "الرفع", err);
                    }
                }));
            }
        }
        if (!s.isFinished()) {
            box.addView(actionButton("إلغاء العملية", 2, new Runnable() {
                @Override
                public void run() {
                    confirmCancel(id);
                }
            }));
        } else {
            box.addView(actionButton("إغلاق", 1, new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            }));
        }
    }

    private TextView actionButton(String label, int style, final Runnable r) {
        TextView b = CgUi.button(this, label, style);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        b.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        return b;
    }

    private void confirmCancel(final String id) {
        CgUi.confirm(this, "إلغاء الرفع", "سيتم إيقاف العملية وحذف الأجزاء المرفوعة من R2 والنسخة المؤقتة. الفيديو الأصلي على هاتفك لن يُمس. متابعة الإلغاء؟", "إلغاء الرفع", true, new Runnable() {
            @Override
            public void run() {
                mgr.cancel(id);
            }
        });
    }

    private void askDecision(final CgUploadSession s) {
        if (decisionShown) return;
        decisionShown = true;
        String msg = "يوجد ملف بنفس الاسم في R2" + (s.existingSize > 0 ? " (" + CgUi.sizeText(s.existingSize) + ")" : "") + ".\nماذا تريد؟";
        CharSequence[] labels = {"استخدام الملف الموجود (بدون رفع)", "استبدال الملف الموجود", "رفع باسم جديد"};
        CgUi.dialog(this).setTitle("الملف موجود مسبقاً").setMessage(msg)
                .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        String decision = which == 0 ? "use" : which == 1 ? "overwrite" : "rename";
                        String err = mgr.decide(s.id, decision);
                        if (err.length() > 0) CgUi.info(CgMediaUploadActivity.this, "الرفع", err);
                        decisionShown = false;
                    }
                })
                .setNegativeButton("لاحقاً", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        decisionShown = false;
                    }
                }).setCancelable(false).show();
    }

    private void buildDone(LinearLayout p, final CgUploadSession s) {
        LinearLayout c = CgUi.card(this);
        c.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.GOOD, 0x18), 14, CgCfg.GOOD));
        c.addView(CgUi.text(this, "✔ تم الرفع والتحقق بنجاح", 17, CgCfg.GOOD, true));
        c.addView(CgUi.space(this, 6));
        if (s.message.length() > 0) c.addView(CgUi.text(this, s.message, 13, CgCfg.TEXT, false));
        if (s.finalSize > 0) c.addView(row("الحجم النهائي", CgUi.sizeText(s.finalSize)));
        if (s.srcSize > 0 && s.finalSize > 0 && s.finalSize < s.srcSize) {
            long saved = s.srcSize - s.finalSize;
            c.addView(row("التوفير", CgUi.sizeText(saved) + " (" + Math.round(100.0 * saved / s.srcSize) + "%)"));
        }
        if (s.warning.length() > 0) c.addView(CgUi.text(this, s.warning, 12.5f, CgCfg.WARN, false));
        TextView url = CgUi.text(this, s.publicUrl, 12.5f, CgCfg.ACCENT, false);
        CgUi.ltr(url);
        url.setTextIsSelectable(true);
        url.setPadding(0, CgUi.dp(this, 8), 0, 0);
        c.addView(url);
        p.addView(c, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        final String link = s.publicUrl;
        boolean forResult = getCallingActivity() != null;
        if (forResult) {
            TextView use = CgUi.button(this, "استخدام الرابط في حقل الفيديو", 0);
            use.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent r = new Intent();
                    r.putExtra("video_url", link);
                    if (s.isEpisode() && s.season > 0 && s.episode > 0) {
                        r.putExtra("season", s.season);
                        r.putExtra("episode", s.episode);
                    }
                    setResult(Activity.RESULT_OK, r);
                    finish();
                }
            });
            p.addView(use, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
        TextView copy = CgUi.button(this, "نسخ الرابط", 1);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("video", link));
                    CgUi.toast(CgMediaUploadActivity.this, "تم نسخ الرابط.");
                } catch (Exception e) {
                    Log.w(CgMediaAnalyzer.TAG, "copy failed: " + e.getMessage());
                }
            }
        });
        p.addView(copy, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        TextView close = CgUi.button(this, "إغلاق", 3);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        p.addView(close, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 0));
    }

    // --------------------------------------------------------------------------------- live update

    @Override
    public void onSession(CgUploadSession s) {
        if (!alive() || !s.id.equals(sessionId)) return;
        if ("REMOVED".equals(s.state)) {
            finish();
            return;
        }
        String key = s.state + "|" + s.needsDecision + "|" + s.waitingNetwork;
        if (!key.equals(viewKey) || pctView == null) {
            showProgress(s);
        } else {
            update(s);
        }
    }

    private void update(CgUploadSession s) {
        if (pctView == null) return;
        boolean compress = CgUploadSession.COMPRESSING.equals(s.state);
        boolean upload = CgUploadSession.UPLOADING.equals(s.state);
        float f = s.stageFraction;
        if (CgUploadSession.PAUSED.equals(s.state) || CgUploadSession.FAILED.equals(s.state)) {
            f = s.uploadSize > 0 ? (float) s.partsBytes() / (float) s.uploadSize : s.stageFraction;
        }
        f = Math.max(0f, Math.min(1f, f));
        pctView.setText(Math.round(f * 100f) + "%");
        setBar(f);

        StringBuilder stage = new StringBuilder();
        if (s.mode != 0) stage.append(compress ? "المرحلة 1 من 2 — " : (CgUploadSession.PREPARING_UPLOAD.equals(s.state) && !s.tempReady ? "" : "المرحلة 2 من 2 — "));
        stage.append(CgUploadSession.stateLabel(s.state));
        if (s.waitingNetwork) stage.append(" (بانتظار الإنترنت)");
        stageView.setText(stage.toString());

        long now = System.currentTimeMillis();
        long elapsed = s.startedAt > 0 ? (now - s.startedAt) / 1000L : 0;
        String l1 = "";
        String l2 = "";
        String l3 = "";
        if (compress) {
            l1 = "تمت معالجة " + Math.round(f * 100f) + "% من الفيديو";
            long stageElapsed = s.stageStartedAt > 0 ? (now - s.stageStartedAt) / 1000L : 0;
            String eta = f > 0.02f ? clock((long) (stageElapsed * (1f - f) / f)) : "—";
            l2 = "المنقضي: " + clock(stageElapsed) + "  •  المتبقي ≈ " + eta;
            if (s.outBytes > 0 && f > 0.03f) {
                long projected = (long) (s.outBytes / f);
                l3 = "الحجم الحالي: " + CgUi.sizeText(s.outBytes) + "  •  المتوقع ≈ " + CgUi.sizeText(projected)
                        + (s.srcSize > projected ? "  •  توفير ≈ " + Math.round(100.0 * (s.srcSize - projected) / s.srcSize) + "%" : "");
            }
        } else if (upload || CgUploadSession.PAUSED.equals(s.state) || CgUploadSession.FAILED.equals(s.state)) {
            long done = upload ? s.bytesDone : s.partsBytes();
            long total = s.uploadSize;
            l1 = CgUi.sizeText(done) + " / " + CgUi.sizeText(total) + (s.totalParts > 0 ? "  •  الجزء " + Math.max(1, Math.min(s.totalParts, upload ? s.currentPart : s.partsDone() + 1)) + " / " + s.totalParts : "");
            String speed = s.speedBps > 1 ? CgUi.sizeText((long) s.speedBps) + "/ث" : "—";
            String eta = s.speedBps > 1 && total > done ? clock((long) ((total - done) / s.speedBps)) : "—";
            l2 = "السرعة: " + speed + "  •  المتبقي ≈ " + eta + "  •  المنقضي: " + clock(elapsed);
            if (s.mode != 0 && s.srcSize > total && total > 0) {
                l3 = "الأصلي " + CgUi.sizeText(s.srcSize) + " ← " + CgUi.sizeText(total) + "  •  وُفّر " + CgUi.sizeText(s.srcSize - total)
                        + " (" + Math.round(100.0 * (s.srcSize - total) / s.srcSize) + "%)";
            }
        } else if (CgUploadSession.VERIFYING.equals(s.state)) {
            l1 = "جاري التحقق من الملف على R2 قبل اعتماد الرابط...";
        } else {
            l1 = "جاري التجهيز...";
        }
        statLine1.setText(l1);
        statLine2.setText(l2);
        statLine3.setText(l3);
        statLine3.setVisibility(l3.length() == 0 ? View.GONE : View.VISIBLE);
        String m = s.message == null ? "" : s.message;
        if (m.startsWith("جاري")) m = "";   // stage captions are already shown above
        msgView.setText(m);
        msgView.setTextColor(CgUploadSession.FAILED.equals(s.state) ? CgCfg.BAD : CgCfg.WARN);
        msgView.setVisibility(m.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void setBar(float f) {
        if (barFill == null || barRest == null) return;
        LinearLayout.LayoutParams a = (LinearLayout.LayoutParams) barFill.getLayoutParams();
        LinearLayout.LayoutParams b = (LinearLayout.LayoutParams) barRest.getLayoutParams();
        a.weight = Math.max(0.001f, f);
        b.weight = Math.max(0.001f, 1f - f);
        barFill.setLayoutParams(a);
        barRest.setLayoutParams(b);
    }

    // ------------------------------------------------------------------------------------ small UI

    private View row(String label, String value) {
        LinearLayout r = CgUi.hbox(this);
        TextView l = CgUi.muted(this, label);
        r.addView(l, new LinearLayout.LayoutParams(CgUi.dp(this, 110), CgUi.WRAP));
        TextView v = CgUi.text(this, value, 13, CgCfg.TEXT, false);
        r.addView(v, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        r.setPadding(0, CgUi.dp(this, 2), 0, CgUi.dp(this, 2));
        return r;
    }

    private View warn(String text) {
        LinearLayout c = CgUi.card(this);
        c.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.WARN, 0x1A), 14, CgCfg.WARN));
        c.addView(CgUi.text(this, text, 13, CgCfg.WARN, false));
        c.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        return c;
    }

    static String clock(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }
}
