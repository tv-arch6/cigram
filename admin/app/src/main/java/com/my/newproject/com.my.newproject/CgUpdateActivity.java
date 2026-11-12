package com.my.newproject;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * إدارة تحديث التطبيق: status card, update form (info / APK / publish / notifications), scheduled
 * update, drafts, version history, analytics and emergency actions. Everything that changes data goes
 * through the admin Worker; nothing sensitive is stored here.
 */
public class CgUpdateActivity extends CgBase implements CgUpdateForm.Host {

    private static final int REQ_APK = 4107;

    private CgUpdateModel.State state;
    private CgUpdateForm form;
    private boolean built;
    private boolean loading;
    private boolean busy;
    private boolean leaving;
    private boolean statsLoaded;
    private boolean statsLoading;
    private int historyLimit = 8;

    private ScrollView scroll;
    private LinearLayout statusHost;
    private LinearLayout scheduledHost;
    private CgUpdateUi.Section secForm;
    private CgUpdateUi.Section secDrafts;
    private CgUpdateUi.Section secHistory;
    private CgUpdateUi.Section secStats;
    private CgUpdateUi.Section secEmergency;
    private LinearLayout draftsBox;
    private LinearLayout historyBox;
    private LinearLayout statsBox;
    private LinearLayout emergencyBox;
    private CgUpdateUi.Btn btnPublish;

    @Override
    protected String screenTitle() {
        return "إدارة تحديث التطبيق";
    }

    @Override
    protected void onBuild() {
        bar.subtitle("إدارة إصدارات سيكرام ونشر التحديثات");
        bar.action("تحديث", new Runnable() {
            @Override
            public void run() {
                if (busy) return;
                statsLoaded = false;
                reload(true);
            }
        });
        reload(true);
    }

    @Override
    protected void onDestroy() {
        CgUpdateDialogs.closeAll();
        if (form != null) form.cancelUpload();
        super.onDestroy();
    }

    // ------------------------------------------------------------ Host

    @Override
    public android.app.Activity activity() {
        return this;
    }

    @Override
    public CgUpdateModel.State state() {
        return state;
    }

    @Override
    public void onFormChanged() {
        updatePrimary();
    }

    @Override
    public void pickApk() {
        if (form != null && form.uploadRunning()) {
            CgUi.toast(this, "رفع ملف جارٍ الآن. ألغِه أولاً أو انتظر انتهاءه.");
            return;
        }
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive", "application/octet-stream", "application/zip"});
        try {
            startActivityForResult(Intent.createChooser(i, "اختر ملف APK"), REQ_APK);
        } catch (Exception e) {
            CgUi.toast(this, "لا يوجد تطبيق لاختيار الملفات على هذا الجهاز.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_APK || resultCode != RESULT_OK || data == null || form == null) return;
        Uri uri = data.getData();
        if (uri != null) form.onApkPicked(uri);
    }

    /** Leaving while an upload runs or the form has unsaved text asks first. */
    @Override
    public void finish() {
        if (!leaving && !isFinishing() && built) {
            if (busy) {
                CgUi.toast(this, "هناك عملية جارية. انتظر حتى تنتهي.");
                return;
            }
            boolean uploading = form != null && form.uploadRunning();
            boolean unsaved = form != null && form.hasContent();
            if (uploading || unsaved) {
                CgUpdateDialogs.confirm(this, "مغادرة الشاشة", CgIcons.WARNING, CgCfg.WARN,
                        uploading
                                ? "رفع ملف APK جارٍ الآن، والخروج سيلغيه. هل تريد المغادرة؟"
                                : "ما كتبته في النموذج لم يُنشر ولم يُحفظ كمسودة، وسيضيع عند المغادرة. هل تريد المغادرة؟",
                        null, "مغادرة", 2, new Runnable() {
                            @Override
                            public void run() {
                                leaving = true;
                                finish();
                            }
                        });
                return;
            }
        }
        super.finish();
    }

    // ------------------------------------------------------------ loading

    private void reload(final boolean overlay) {
        if (loading) return;
        loading = true;
        if (overlay) showLoading("جارٍ تحميل بيانات التحديث...");
        CgUpdateApi.async(this, new CgUpdateApi.Work<JSONObject>() {
            @Override
            public JSONObject run() throws CxApi.Failure {
                return CgUpdateApi.history();
            }
        }, new CgUpdateApi.Done<JSONObject>() {
            @Override
            public void ok(JSONObject d) {
                loading = false;
                if (overlay) hideLoading();
                state = CgUpdateModel.parseState(d);
                if (!built) buildScreen();
                refreshAll();
            }

            @Override
            public void fail(CxApi.Failure f) {
                loading = false;
                if (overlay) hideLoading();
                if (!built) showLoadError(f.getMessage());
                else CgUi.toast(CgUpdateActivity.this, "تعذر تحديث البيانات: " + f.getMessage());
            }
        });
    }

    private void showLoadError(String message) {
        body.removeAllViews();
        LinearLayout box = CgUi.vbox(this);
        box.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 12), CgUi.dp(this, 12), 0);
        box.addView(CgUi.errorCard(this, message, new Runnable() {
            @Override
            public void run() {
                reload(true);
            }
        }));
        body.addView(box);
    }

    // ------------------------------------------------------------ skeleton

    private void buildScreen() {
        built = true;
        form = new CgUpdateForm(this);
        LinearLayout inner = scrollBody();
        scroll = (ScrollView) inner.getParent();

        statusHost = CgUi.vbox(this);
        inner.addView(statusHost, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));
        scheduledHost = CgUi.vbox(this);
        inner.addView(scheduledHost, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        secForm = CgUpdateUi.section(this, CgIcons.UPDATE, "إصدار تحديث جديد", CgCfg.ACCENT, true);
        secForm.content.addView(form.view());
        buildActions(secForm.content);
        inner.addView(secForm, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));

        secDrafts = CgUpdateUi.section(this, CgIcons.DRAFT, "المسودات", CgCfg.ACCENT, false);
        draftsBox = CgUi.vbox(this);
        secDrafts.content.addView(draftsBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        inner.addView(secDrafts, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));

        secHistory = CgUpdateUi.section(this, CgIcons.HISTORY, "سجل الإصدارات", CgCfg.ACCENT, false);
        historyBox = CgUi.vbox(this);
        secHistory.content.addView(historyBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        inner.addView(secHistory, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));

        secStats = CgUpdateUi.section(this, CgIcons.CHART, "إحصائيات التحديث", CgCfg.ACCENT, false);
        statsBox = CgUi.vbox(this);
        secStats.content.addView(statsBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        secStats.onOpen(new Runnable() {
            @Override
            public void run() {
                if (!statsLoaded) loadStats();
            }
        });
        inner.addView(secStats, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));

        secEmergency = CgUpdateUi.section(this, CgIcons.SHIELD, "إجراءات الطوارئ", CgCfg.BAD, false);
        emergencyBox = CgUi.vbox(this);
        secEmergency.content.addView(emergencyBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        inner.addView(secEmergency, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));
    }

    private void buildActions(LinearLayout content) {
        LinearLayout row = CgUi.hbox(this);
        CgUpdateUi.Btn preview = CgUpdateUi.btn(this, "معاينة", CgIcons.EYE, 1);
        preview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUpdateDialogs.preview(CgUpdateActivity.this, form.toJson(form.mode()));
            }
        });
        CgUpdateUi.Btn clear = CgUpdateUi.btn(this, "مسح النموذج", CgIcons.TRASH, 1);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (busy) return;
                if (!form.hasContent()) {
                    CgUi.toast(CgUpdateActivity.this, "النموذج فارغ.");
                    return;
                }
                CgUpdateDialogs.confirm(CgUpdateActivity.this, "مسح النموذج", CgIcons.TRASH, CgCfg.BAD,
                        "سيُمسح كل ما كتبته ويُلغى رفع الملف الجاري إن وُجد. المسودات المحفوظة لا تتأثر.",
                        null, "مسح", 2, new Runnable() {
                            @Override
                            public void run() {
                                form.reset();
                                updatePrimary();
                            }
                        });
            }
        });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        l1.setMargins(CgUi.dp(this, 4), 0, CgUi.dp(this, 4), 0);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        l2.setMargins(CgUi.dp(this, 4), 0, CgUi.dp(this, 4), 0);
        row.addView(preview, l1);
        row.addView(clear, l2);
        content.addView(row, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 8));

        btnPublish = CgUpdateUi.btn(this, "نشر التحديث الآن", CgIcons.PUBLISH, 0);
        btnPublish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPrimary();
            }
        });
        content.addView(btnPublish, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
    }

    private void updatePrimary() {
        if (btnPublish == null || form == null || btnPublish.busy()) return;
        String m = form.mode();
        if (CgUpdateForm.MODE_SCHEDULE.equals(m)) {
            btnPublish.setLabel("جدولة التحديث");
            btnPublish.setIcon(CgIcons.CALENDAR);
        } else if (CgUpdateForm.MODE_DRAFT.equals(m)) {
            btnPublish.setLabel("حفظ المسودة");
            btnPublish.setIcon(CgIcons.DRAFT);
        } else {
            btnPublish.setLabel("نشر التحديث الآن");
            btnPublish.setIcon(CgIcons.PUBLISH);
        }
    }

    private void refreshAll() {
        renderStatus();
        renderScheduled();
        renderDrafts();
        renderHistory();
        renderEmergency();
        secDrafts.badge(state.drafts.isEmpty() ? null : String.valueOf(state.drafts.size()), CgCfg.MUTED);
        secHistory.badge(state.history.isEmpty() ? null : String.valueOf(state.history.size()), CgCfg.MUTED);
        form.onStateChanged();
        updatePrimary();
    }

    // ------------------------------------------------------------ status card

    private JSONObject currentRelease() {
        JSONObject r = state.liveRelease();
        if (r != null) return r;
        if (state.live != null) {
            String id = state.live.optString("releaseId", "");
            if (id.length() > 0) return state.findRelease(id);
            long code = state.liveCode();
            for (int i = 0; i < state.releases.size(); i++) {
                if (state.releases.get(i).optLong("versionCode", 0) == code && !"draft".equals(state.releases.get(i).optString("status"))) {
                    return state.releases.get(i);
                }
            }
        }
        return null;
    }

    private void renderStatus() {
        statusHost.removeAllViews();
        final String key = CgUpdateModel.liveStateKey(state);
        int color = CgUpdateModel.stateColor(key);
        JSONObject live = state.live;
        long code = state.liveCode();
        final JSONObject rel = currentRelease();

        LinearLayout card = CgUi.vbox(this);
        card.setBackground(CgUi.bg(this, CgUi.alpha(color, 0x14), 18, color));
        int p = CgUi.dp(this, 16);
        card.setPadding(p, p, p, p);

        LinearLayout head = CgUi.hbox(this);
        LinearLayout.LayoutParams icp = new LinearLayout.LayoutParams(CgUi.dp(this, 46), CgUi.dp(this, 46));
        icp.setMargins(0, 0, CgUi.dp(this, 12), 0);
        head.addView(CgUpdateUi.iconCircle(this, CgIcons.UPDATE, color, 46, 25), icp);
        LinearLayout t = CgUi.vbox(this);
        t.addView(CgUi.muted(this, "الإصدار الحالي"));
        String vn = live == null ? "" : live.optString("versionName", "").trim();
        TextView big = CgUi.text(this, vn.length() > 0 ? vn : (code > 0 ? "Code " + code : "—"), 22, CgCfg.TEXT, true);
        CgUi.ltr(big);
        big.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        t.addView(big, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        head.addView(t, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        head.addView(CgUi.badge(this, CgUpdateModel.stateLabel(key), color), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        card.addView(head, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        if (live == null || code <= 0) {
            TextView n = CgUi.muted(this, "لم يُنشر أي تحديث بعد. استخدم النموذج أدناه لنشر أول إصدار.");
            n.setPadding(0, CgUi.dp(this, 12), 0, 0);
            card.addView(n);
        } else {
            LinearLayout rows = CgUi.vbox(this);
            rows.setPadding(0, CgUi.dp(this, 10), 0, 0);
            rows.addView(CgUpdateUi.kv(this, "رقم الإصدار (Version Code)", String.valueOf(code), CgCfg.TEXT, true));
            rows.addView(CgUpdateUi.kv(this, "الحد الأدنى المدعوم", String.valueOf(live.optLong("minVersionCode", 0)), CgCfg.TEXT, true));
            rows.addView(CgUpdateUi.kv(this, "حالة التحديث", CgUpdateModel.stateLabel(key), color, false));
            String pub = live.optString("publishedAt", "");
            if (pub.length() == 0 && rel != null) pub = rel.optString("publishedAt", "");
            rows.addView(CgUpdateUi.kv(this, "آخر نشر", pub.length() > 0 ? CgUpdateModel.dateTimeOfIso(pub) : "غير مسجّل", CgCfg.TEXT, false));
            if (!state.livePublished()) {
                String stopped = live.optString("stoppedAt", "");
                if (stopped.length() > 0) rows.addView(CgUpdateUi.kv(this, "تاريخ الإيقاف", CgUpdateModel.dateTimeOfIso(stopped), CgCfg.TEXT, false));
            }
            long size = live.optLong("fileSize", 0);
            if (size <= 0 && rel != null) size = rel.optLong("fileSize", 0);
            rows.addView(CgUpdateUi.kv(this, "حجم APK", CgUpdateModel.sizeText(size), CgCfg.TEXT, true));
            rows.addView(CgUpdateUi.kv(this, "حالة الإشعار", CgUpdateModel.pushLabel(rel), CgUpdateModel.pushColor(rel), false));
            rows.addView(CgUpdateUi.kv(this, "التحديث نشط الآن", state.livePublished() ? "نعم" : "لا",
                    state.livePublished() ? CgCfg.GOOD : CgCfg.GRAY, false));
            JSONArray blocked = live.optJSONArray("blockedVersions");
            if (blocked != null && blocked.length() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < blocked.length(); i++) {
                    if (i > 0) sb.append("، ");
                    sb.append(blocked.optLong(i));
                }
                rows.addView(CgUpdateUi.kv(this, "إصدارات معطّلة", sb.toString(), CgCfg.BAD, true));
            }
            final String url = live.optString("downloadUrl", live.optString("url", ""));
            if (url.length() > 0) {
                TextView ul = CgUi.text(this, "رابط APK", 13, CgCfg.MUTED, false);
                ul.setPadding(0, CgUi.dp(this, 6), 0, CgUi.dp(this, 2));
                rows.addView(ul);
                TextView u = CgUi.text(this, url, 12, CgCfg.ACCENT, false);
                CgUi.ltr(u);
                u.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                rows.addView(u, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            }
            card.addView(rows, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

            LinearLayout actions = CgUi.vbox(this);
            actions.setPadding(0, CgUi.dp(this, 10), 0, 0);
            if (state.livePublished()) {
                CgUpdateUi.Btn stop = CgUpdateUi.btn(this, "إيقاف التحديث", CgIcons.STOP, 2);
                stop.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        stopUpdate();
                    }
                });
                actions.addView(stop, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            } else if (rel != null && "stopped".equals(rel.optString("status"))) {
                CgUpdateUi.Btn again = CgUpdateUi.btn(this, "إعادة نشر هذا الإصدار", CgIcons.PUBLISH, 0);
                again.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        loadIntoForm(rel, CgUpdateForm.LOAD_EDIT);
                    }
                });
                actions.addView(again, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            }
            if (url.length() > 0) {
                CgUpdateUi.Btn copy = CgUpdateUi.btn(this, "نسخ رابط APK", CgIcons.COPY, 1);
                copy.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        copyText(url);
                    }
                });
                actions.addView(copy, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
            }
            if (actions.getChildCount() > 0) card.addView(actions, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }
        statusHost.addView(card, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        CgUpdateUi.fadeIn(card);
    }

    private void stopUpdate() {
        if (busy) return;
        final String vn = state.live == null ? "" : state.live.optString("versionName", "");
        CgUpdateDialogs.confirm(this, "إيقاف التحديث", CgIcons.STOP, CgCfg.BAD,
                "سيتوقف التطبيق عن عرض هذا التحديث فوراً (published=false). يبقى الإصدار في السجل ولا يُحذف ملف APK من R2، ويمكنك إعادة نشره لاحقاً.",
                new String[][]{
                        {"الإصدار", vn.length() > 0 ? vn : "—", "ltr"},
                        {"Version Code", String.valueOf(state.liveCode()), "ltr"}
                }, "إيقاف التحديث", 2, new Runnable() {
                    @Override
                    public void run() {
                        runAction("جارٍ إيقاف التحديث...", new CgUpdateApi.Work<JSONObject>() {
                            @Override
                            public JSONObject run() throws CxApi.Failure {
                                return CgUpdateApi.stop("إيقاف يدوي من لوحة الإدارة");
                            }
                        }, "تم إيقاف التحديث. بقي في السجل.", "تعذر إيقاف التحديث");
                    }
                });
    }

    // ------------------------------------------------------------ scheduled

    private void renderScheduled() {
        scheduledHost.removeAllViews();
        final JSONObject sc = state.scheduled;
        JSONObject failed = state.failedSchedule;
        if (sc != null) {
            int color = CgUpdateModel.BLUE;
            LinearLayout card = CgUi.vbox(this);
            card.setBackground(CgUi.bg(this, CgUi.alpha(color, 0x14), 16, color));
            int p = CgUi.dp(this, 14);
            card.setPadding(p, p, p, p);
            LinearLayout head = CgUi.hbox(this);
            LinearLayout.LayoutParams icp = new LinearLayout.LayoutParams(CgUi.dp(this, 38), CgUi.dp(this, 38));
            icp.setMargins(0, 0, CgUi.dp(this, 10), 0);
            head.addView(CgUpdateUi.iconCircle(this, CgIcons.CALENDAR, color, 38, 21), icp);
            head.addView(CgUi.text(this, "تحديث مجدول", 15, CgCfg.TEXT, true), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            head.addView(CgUi.badge(this, sc.optString("versionName", "—"), color), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            card.addView(head, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            LinearLayout rows = CgUi.vbox(this);
            rows.setPadding(0, CgUi.dp(this, 8), 0, 0);
            long ms = CgUi.parseIso(sc.optString("scheduledAt", ""));
            rows.addView(CgUpdateUi.kv(this, "موعد النشر", ms > 0 ? CgUpdateModel.arabicDateTime(ms) : "—", CgCfg.TEXT, false));
            if (ms > 0) rows.addView(CgUpdateUi.kv(this, "المتبقي", CgUpdateModel.untilText(ms), color, false));
            rows.addView(CgUpdateUi.kv(this, "Version Code", String.valueOf(sc.optLong("versionCode", 0)), CgCfg.TEXT, true));
            rows.addView(CgUpdateUi.kv(this, "النوع", CgUpdateModel.typeLabel(sc.optString("updateType")),
                    CgUpdateModel.typeColor(sc.optString("updateType")), false));
            card.addView(rows);
            LinearLayout buttons = CgUi.hbox(this);
            buttons.setPadding(0, CgUi.dp(this, 10), 0, 0);
            CgUpdateUi.Btn edit = CgUpdateUi.btn(this, "تعديل", CgIcons.EDIT, 1);
            edit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadIntoForm(sc, CgUpdateForm.LOAD_EDIT);
                }
            });
            CgUpdateUi.Btn cancel = CgUpdateUi.btn(this, "إلغاء الجدولة", CgIcons.CLOSE, 2);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cancelSchedule(sc);
                }
            });
            LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
            b1.setMargins(CgUi.dp(this, 4), 0, CgUi.dp(this, 4), 0);
            LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
            b2.setMargins(CgUi.dp(this, 4), 0, CgUi.dp(this, 4), 0);
            buttons.addView(edit, b1);
            buttons.addView(cancel, b2);
            card.addView(buttons, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            scheduledHost.addView(card, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));
        } else if (failed != null && failed.optLong("versionCode", 0) > state.liveCode()) {
            final JSONObject fr = failed;
            LinearLayout card = CgUi.vbox(this);
            card.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.BAD, 0x14), 16, CgCfg.BAD));
            int p = CgUi.dp(this, 14);
            card.setPadding(p, p, p, p);
            card.addView(CgUi.text(this, "فشل النشر التلقائي للتحديث المجدول (" + fr.optString("versionName", "") + ")", 14.5f, CgCfg.BAD, true));
            String why = fr.optString("lastError", "");
            TextView w = CgUi.muted(this, why.length() > 0 ? why : "تعذر النشر في الموعد المحدد.");
            w.setPadding(0, CgUi.dp(this, 6), 0, CgUi.dp(this, 8));
            card.addView(w);
            CgUpdateUi.Btn retry = CgUpdateUi.btn(this, "فتح لإعادة المحاولة", CgIcons.REFRESH, 1);
            retry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadIntoForm(fr, CgUpdateForm.LOAD_EDIT);
                }
            });
            card.addView(retry, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            scheduledHost.addView(card, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));
        }
    }

    private void cancelSchedule(final JSONObject r) {
        if (busy) return;
        final boolean failed = "failed".equals(r.optString("status"));
        CgUpdateDialogs.confirm(this, failed ? "تحويل إلى مسودة" : "إلغاء الجدولة", CgIcons.CLOSE, CgCfg.WARN,
                "لن يُنشر هذا التحديث تلقائياً. تعود بياناته إلى المسودات فلا يضيع شيء، ويمكنك جدولته أو نشره لاحقاً.",
                new String[][]{{"الإصدار", r.optString("versionName", "—"), "ltr"}},
                failed ? "تحويل إلى مسودة" : "إلغاء الجدولة", 2, new Runnable() {
                    @Override
                    public void run() {
                        final String id = r.optString("id", "");
                        runAction("جارٍ الإلغاء...", new CgUpdateApi.Work<JSONObject>() {
                            @Override
                            public JSONObject run() throws CxApi.Failure {
                                return CgUpdateApi.cancelSchedule(id);
                            }
                        }, "تم إلغاء الجدولة وحُفظ التحديث كمسودة.", "تعذر إلغاء الجدولة");
                    }
                });
    }

    // ------------------------------------------------------------ drafts

    private void renderDrafts() {
        draftsBox.removeAllViews();
        if (state.drafts.isEmpty()) {
            draftsBox.addView(CgUpdateUi.empty(this, CgIcons.DRAFT, "لا توجد مسودات",
                    "احفظ نموذجاً غير مكتمل كمسودة (من تبويب النشر ← مسودة) لتكمله لاحقاً."));
            return;
        }
        for (int i = 0; i < state.drafts.size(); i++) {
            final JSONObject d = state.drafts.get(i);
            LinearLayout card = CgUi.vbox(this);
            card.setBackground(CgUi.bg(this, CgCfg.CARD2, 14, CgCfg.STROKE));
            int p = CgUi.dp(this, 12);
            card.setPadding(p, p, p, p);

            LinearLayout top = CgUi.hbox(this);
            String vn = d.optString("versionName", "").trim();
            TextView name = CgUi.text(this, vn.length() > 0 ? vn : "بدون اسم إصدار", 15, CgCfg.TEXT, true);
            CgUi.ltr(name);
            name.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            top.addView(name, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            top.addView(CgUi.badge(this, "مسودة", CgCfg.GRAY), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            card.addView(top, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

            String title = d.optString("title", "").trim();
            TextView tt = CgUi.text(this, title.length() > 0 ? title : "بدون عنوان", 13, title.length() > 0 ? CgCfg.TEXT : CgCfg.MUTED, false);
            tt.setPadding(0, CgUi.dp(this, 4), 0, 0);
            card.addView(tt);

            long code = d.optLong("versionCode", 0);
            String meta = "آخر تعديل: " + CgUpdateModel.shortDateOfIso(d.optString("updatedAt", ""))
                    + "  ·  " + CgUpdateModel.typeShort(d.optString("updateType"))
                    + "  ·  Code " + (code > 0 ? String.valueOf(code) : "—")
                    + "  ·  " + (d.optString("downloadUrl", "").length() > 0 ? "APK جاهز" : "بدون APK");
            TextView m = CgUi.muted(this, meta);
            m.setPadding(0, CgUi.dp(this, 4), 0, CgUi.dp(this, 8));
            card.addView(m);

            LinearLayout row = CgUi.hbox(this);
            TextView edit = CgUi.smallButton(this, "تعديل", 1);
            edit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadIntoForm(d, CgUpdateForm.LOAD_DRAFT);
                }
            });
            TextView pub = CgUi.smallButton(this, "نشر", 0);
            pub.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadIntoForm(d, CgUpdateForm.LOAD_DRAFT, CgUpdateForm.MODE_NOW);
                }
            });
            TextView del = CgUi.smallButton(this, "حذف", 2);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    deleteDraft(d);
                }
            });
            row.addView(pub, weightLp(3));
            row.addView(edit, weightLp(3));
            row.addView(del, weightLp(3));
            card.addView(row, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            draftsBox.addView(card, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
    }

    private LinearLayout.LayoutParams weightLp(float marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        lp.setMargins(CgUi.dp(this, marginDp), 0, CgUi.dp(this, marginDp), 0);
        return lp;
    }

    private void deleteDraft(final JSONObject d) {
        if (busy) return;
        CgUpdateDialogs.confirm(this, "حذف المسودة", CgIcons.TRASH, CgCfg.BAD,
                "ستُحذف هذه المسودة نهائياً. لا يتأثر أي إصدار منشور ولا ملفات R2.",
                new String[][]{{"الإصدار", d.optString("versionName", "").length() > 0 ? d.optString("versionName") : "بدون اسم", "ltr"}},
                "حذف", 2, new Runnable() {
                    @Override
                    public void run() {
                        final String id = d.optString("id", "");
                        if (id.equals(form.editingId())) form.reset();
                        runAction("جارٍ حذف المسودة...", new CgUpdateApi.Work<JSONObject>() {
                            @Override
                            public JSONObject run() throws CxApi.Failure {
                                return CgUpdateApi.deleteDraft(id);
                            }
                        }, "تم حذف المسودة.", "تعذر حذف المسودة");
                    }
                });
    }

    // ------------------------------------------------------------ history

    private final CgUpdateDialogs.DetailActions detailActions = new CgUpdateDialogs.DetailActions() {
        @Override
        public void useData(JSONObject r) {
            loadIntoForm(r, CgUpdateForm.LOAD_COPY);
        }

        @Override
        public void copyUrl(String url) {
            copyText(url);
        }

        @Override
        public void cancelSchedule(JSONObject r) {
            CgUpdateActivity.this.cancelSchedule(r);
        }

        @Override
        public void editDraft(JSONObject r) {
            loadIntoForm(r, CgUpdateForm.LOAD_DRAFT);
        }

        @Override
        public void edit(JSONObject r) {
            loadIntoForm(r, CgUpdateForm.LOAD_EDIT);
        }

        @Override
        public void deleteDraft(JSONObject r) {
            CgUpdateActivity.this.deleteDraft(r);
        }

        @Override
        public void resendPush(JSONObject r) {
            CgUpdateActivity.this.resendPush(r.optString("id", ""));
        }
    };

    private void renderHistory() {
        historyBox.removeAllViews();
        if (state.history.isEmpty()) {
            historyBox.addView(CgUpdateUi.empty(this, CgIcons.HISTORY, "لا يوجد سجل بعد",
                    "تظهر هنا كل الإصدارات التي تنشرها أو توقفها أو تجدولها."));
            return;
        }
        long liveCode = state.liveCode();
        int shown = Math.min(historyLimit, state.history.size());
        for (int i = 0; i < shown; i++) {
            final JSONObject r = state.history.get(i);
            final boolean isLive = liveCode > 0 && r.optLong("versionCode", 0) == liveCode
                    && ("published".equals(r.optString("status")) || "stopped".equals(r.optString("status")));
            LinearLayout row = CgUi.hbox(this);
            row.setClickable(true);
            row.setBackground(CgUi.bg(this, CgCfg.CARD2, 14, isLive ? CgUi.alpha(CgCfg.GOOD, 0x99) : CgCfg.STROKE));
            int p = CgUi.dp(this, 12);
            row.setPadding(p, p, p, p);
            LinearLayout left = CgUi.vbox(this);
            LinearLayout line1 = CgUi.hbox(this);
            String vn = r.optString("versionName", "").trim();
            TextView name = CgUi.text(this, vn.length() > 0 ? vn : "—", 15, CgCfg.TEXT, true);
            CgUi.ltr(name);
            line1.addView(name, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            TextView code = CgUi.text(this, "  (" + r.optLong("versionCode", 0) + ")", 12.5f, CgCfg.MUTED, false);
            CgUi.ltr(code);
            line1.addView(code, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            left.addView(line1, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            String type = r.optString("updateType", CgUpdateModel.OPTIONAL);
            TextView meta = CgUi.muted(this, CgUpdateModel.shortDateOfIso(CgUpdateModel.releaseDateIso(r)) + "  ·  "
                    + CgUpdateModel.typeShort(type) + "  ·  " + CgUpdateModel.sizeText(r.optLong("fileSize", 0)));
            meta.setPadding(0, CgUi.dp(this, 2), 0, 0);
            left.addView(meta);
            row.addView(left, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            String status = r.optString("status", "");
            row.addView(CgUi.badge(this, CgUpdateModel.releaseStatusLabel(status), CgUpdateModel.releaseStatusColor(status)),
                    new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            android.widget.ImageView chev = CgUpdateUi.icon(this, CgIcons.CHEVRON, CgCfg.MUTED, 20);
            chev.setRotation(90f);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(CgUi.dp(this, 20), CgUi.dp(this, 20));
            cp.setMargins(CgUi.dp(this, 8), 0, 0, 0);
            row.addView(chev, cp);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CgUpdateDialogs.releaseDetail(CgUpdateActivity.this, r, isLive, detailActions);
                }
            });
            CgUpdateUi.press(row);
            historyBox.addView(row, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
        if (state.history.size() > shown) {
            CgUpdateUi.Btn more = CgUpdateUi.btn(this, "عرض المزيد (" + (state.history.size() - shown) + ")", CgIcons.CHEVRON, 3);
            more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    historyLimit += 10;
                    renderHistory();
                }
            });
            historyBox.addView(more, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }
        if (state.audit != null && state.audit.length() > 0) {
            TextView h = CgUi.text(this, "آخر العمليات", 13, CgCfg.ACCENT, true);
            h.setPadding(CgUi.dp(this, 4), CgUi.dp(this, 12), 0, CgUi.dp(this, 4));
            historyBox.addView(h);
            int n = Math.min(6, state.audit.length());
            for (int i = 0; i < n; i++) {
                JSONObject a = state.audit.optJSONObject(i);
                if (a == null) continue;
                String vn = a.optString("versionName", "");
                String line = auditLabel(a.optString("action", "")) + (vn.length() > 0 ? "  ·  " + vn : "")
                        + "  ·  " + CgUpdateModel.dateTimeOfIso(a.optString("at", ""));
                TextView t = CgUi.muted(this, line);
                t.setPadding(CgUi.dp(this, 4), CgUi.dp(this, 3), 0, CgUi.dp(this, 3));
                historyBox.addView(t);
            }
        }
    }

    private static String auditLabel(String action) {
        if ("publish".equals(action)) return "نشر";
        if ("publish_scheduled".equals(action)) return "نشر مجدول";
        if ("schedule".equals(action)) return "جدولة";
        if ("schedule_cancel".equals(action)) return "إلغاء جدولة";
        if ("schedule_failed".equals(action)) return "فشل نشر مجدول";
        if ("stop".equals(action)) return "إيقاف";
        if ("draft_create".equals(action)) return "إنشاء مسودة";
        if ("draft_update".equals(action)) return "تعديل مسودة";
        if ("draft_delete".equals(action)) return "حذف مسودة";
        if ("emergency_stop".equals(action)) return "إيقاف طارئ";
        if ("emergency_force_current".equals(action)) return "تحويل إلى إجباري";
        if ("emergency_block_version".equals(action)) return "تعطيل إصدار";
        if ("emergency_unblock_version".equals(action)) return "إلغاء تعطيل إصدار";
        if ("emergency_set_min".equals(action)) return "ضبط الحد الأدنى";
        if ("push_sent".equals(action)) return "إرسال الإشعار";
        if ("push_failed".equals(action)) return "فشل إرسال الإشعار";
        if ("push_no_provider".equals(action)) return "الإشعار غير مُعدّ";
        return action;
    }

    // ------------------------------------------------------------ analytics

    private void loadStats() {
        if (statsLoading) return;
        statsLoading = true;
        statsBox.removeAllViews();
        LinearLayout l = CgUi.hbox(this);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, CgUi.dp(this, 16), 0, CgUi.dp(this, 16));
        l.addView(CgUi.spinner(this), new LinearLayout.LayoutParams(CgUi.dp(this, 28), CgUi.dp(this, 28)));
        TextView t = CgUi.muted(this, "  جارٍ تحميل الإحصائيات...");
        l.addView(t);
        statsBox.addView(l);
        CgUpdateApi.async(this, new CgUpdateApi.Work<JSONObject>() {
            @Override
            public JSONObject run() throws CxApi.Failure {
                return CgUpdateApi.stats();
            }
        }, new CgUpdateApi.Done<JSONObject>() {
            @Override
            public void ok(JSONObject d) {
                statsLoading = false;
                statsLoaded = true;
                renderStats(d);
            }

            @Override
            public void fail(CxApi.Failure f) {
                statsLoading = false;
                statsBox.removeAllViews();
                statsBox.addView(CgUi.errorCard(CgUpdateActivity.this, f.getMessage(), new Runnable() {
                    @Override
                    public void run() {
                        loadStats();
                    }
                }));
            }
        });
    }

    private void renderStats(JSONObject d) {
        statsBox.removeAllViews();
        if (!d.optBoolean("hasData", false)) {
            statsBox.addView(CgUpdateUi.empty(this, CgIcons.CHART, "لا توجد بيانات كافية حتى الآن",
                    "ستظهر نسبة المحدّثين وعدد الضغطات عندما يبدأ التطبيق بإرسال أحداث التحديث إلى الـ Worker."));
            return;
        }
        LinearLayout box = CgUi.vbox(this);
        box.setBackground(CgUi.bg(this, CgCfg.CARD2, 14, CgCfg.STROKE));
        box.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 6), CgUi.dp(this, 12), CgUi.dp(this, 6));
        box.addView(CgUpdateUi.kv(this, "إجمالي الأحداث", String.valueOf(d.optLong("total", 0)), CgCfg.TEXT, true));
        box.addView(CgUpdateUi.kv(this, "الأجهزة المعروفة", String.valueOf(d.optLong("deviceCount", 0)), CgCfg.TEXT, true));
        if (!d.isNull("adoptionPercent") && d.has("adoptionPercent")) {
            box.addView(CgUpdateUi.kv(this, "نسبة من حدّث إلى الأحدث", d.optDouble("adoptionPercent", 0) + "%", CgCfg.GOOD, true));
        }
        JSONObject latest = d.optJSONObject("latest");
        if (latest != null) {
            box.addView(CgUpdateUi.kv(this, "ظهرت لهم نافذة التحديث", String.valueOf(latest.optLong("shown", 0)), CgCfg.TEXT, true));
            box.addView(CgUpdateUi.kv(this, "ضغطوا «تحديث الآن»", String.valueOf(latest.optLong("update_now", 0)), CgCfg.TEXT, true));
            box.addView(CgUpdateUi.kv(this, "ضغطوا «لاحقاً»", String.valueOf(latest.optLong("later", 0)), CgCfg.TEXT, true));
            box.addView(CgUpdateUi.kv(this, "بدؤوا التنزيل", String.valueOf(latest.optLong("download", 0)), CgCfg.TEXT, true));
        }
        String updated = d.optString("updatedAt", "");
        if (updated.length() > 0) box.addView(CgUpdateUi.kv(this, "آخر تحديث للبيانات", CgUpdateModel.dateTimeOfIso(updated), CgCfg.MUTED, false));
        statsBox.addView(box, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        JSONObject installed = d.optJSONObject("installed");
        if (installed != null && installed.length() > 0) {
            ArrayList<long[]> list = new ArrayList<long[]>();
            long total = 0;
            Iterator<String> it = installed.keys();
            while (it.hasNext()) {
                String k = it.next();
                long code = CgUpdateModel.parseLong(k);
                long n = installed.optLong(k, 0);
                if (code < 0 || n <= 0) continue;
                list.add(new long[]{code, n});
                total += n;
            }
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    if (list.get(j)[1] > list.get(i)[1]) {
                        long[] tmp = list.get(i);
                        list.set(i, list.get(j));
                        list.set(j, tmp);
                    }
                }
            }
            TextView h = CgUi.text(this, "توزيع الإصدارات المثبّتة", 13, CgCfg.ACCENT, true);
            h.setPadding(CgUi.dp(this, 4), CgUi.dp(this, 12), 0, CgUi.dp(this, 6));
            statsBox.addView(h);
            int n = Math.min(6, list.size());
            for (int i = 0; i < n; i++) {
                long[] e = list.get(i);
                LinearLayout row = CgUi.vbox(this);
                LinearLayout top = CgUi.hbox(this);
                TextView name = CgUi.text(this, "Code " + e[0], 13, CgCfg.TEXT, false);
                CgUi.ltr(name);
                top.addView(name, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
                TextView cnt = CgUi.text(this, e[1] + " جهاز", 12.5f, CgCfg.MUTED, false);
                top.addView(cnt, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
                row.addView(top, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
                CgUpdateUi.Bar bar2 = new CgUpdateUi.Bar(this);
                bar2.set(total > 0 ? (float) e[1] / (float) total : 0f);
                row.addView(bar2, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 8));
                statsBox.addView(row, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            }
        }
    }

    // ------------------------------------------------------------ emergency

    private void renderEmergency() {
        emergencyBox.removeAllViews();
        emergencyBox.addView(CgUpdateUi.note(this, CgIcons.WARNING,
                "هذه الإجراءات تؤثر فوراً على كل مستخدمي التطبيق، وكل إجراء يطلب تأكيداً مرتين. لا يُحذف أي ملف ولا يُمسح أي سجل.", CgCfg.BAD));
        final boolean live = state.livePublished();
        final long liveCode = state.liveCode();
        if (!live) {
            emergencyBox.addView(CgUpdateUi.note(this, CgIcons.INFO,
                    "لا يوجد تحديث منشور الآن، لذلك الإجراءات التي تعدّل التحديث الحالي غير متاحة.", CgCfg.MUTED));
        }
        boolean alreadyForce = live && state.live.optBoolean("force", false)
                && state.live.optLong("minVersionCode", 0) >= liveCode;

        CgUpdateUi.Btn force = CgUpdateUi.btn(this, "تحويل التحديث الحالي إلى إجباري", CgIcons.WARNING, 2);
        force.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUpdateDialogs.doubleConfirm(CgUpdateActivity.this, "تحويل التحديث إلى إجباري",
                        "سيُجبَر كل مستخدم إصداره أقل من " + liveCode + " على التحديث، ولن يستطيع متابعة استخدام التطبيق قبل ذلك.",
                        "سيُطبَّق الإجبار على الإصدار " + liveCode + " فوراً ولا يمكن التراجع عنه إلا بنشر إصدار جديد.",
                        "تحويل إلى إجباري", new Runnable() {
                            @Override
                            public void run() {
                                runEmergency("جارٍ تحويل التحديث إلى إجباري...", "force_current", null);
                            }
                        });
            }
        });
        addEmergencyButton(force, live && !alreadyForce);

        CgUpdateUi.Btn block = CgUpdateUi.btn(this, "تعطيل إصدار معيّن", CgIcons.STOP, 2);
        block.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUpdateDialogs.numberPrompt(CgUpdateActivity.this, "تعطيل إصدار معيّن", CgIcons.STOP, CgCfg.BAD,
                        "أدخل Version Code للإصدار الذي لا يجب أن يعمل بعد الآن. سيُجبَر أصحابه على التحديث.",
                        "Version Code للإصدار المراد تعطيله", "مثال: 22", new CgUi.Callback<Long>() {
                            @Override
                            public void run(final Long code) {
                                if (code.longValue() >= liveCode) {
                                    CgUpdateDialogs.error(CgUpdateActivity.this, "لا يمكن التعطيل",
                                            "لا يمكن تعطيل أحدث إصدار منشور (" + liveCode + "). عطّل إصداراً أقدم منه.");
                                    return;
                                }
                                CgUpdateDialogs.doubleConfirm(CgUpdateActivity.this, "تعطيل الإصدار " + code,
                                        "لن يستطيع من يملك الإصدار " + code + " متابعة استخدام التطبيق قبل أن يحدّث.",
                                        "سيُعطَّل الإصدار " + code + " لكل مستخدميه فوراً.",
                                        "تعطيل الإصدار", new Runnable() {
                                            @Override
                                            public void run() {
                                                JSONObject extra = new JSONObject();
                                                CxJ.put(extra, "versionCode", code);
                                                runEmergency("جارٍ تعطيل الإصدار...", "block_version", extra);
                                            }
                                        });
                            }
                        });
            }
        });
        addEmergencyButton(block, live);

        CgUpdateUi.Btn min = CgUpdateUi.btn(this, "رفع الحد الأدنى المدعوم", CgIcons.SHIELD, 2);
        min.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUpdateDialogs.numberPrompt(CgUpdateActivity.this, "رفع الحد الأدنى المدعوم", CgIcons.SHIELD, CgCfg.BAD,
                        "كل من إصداره أقل من هذا الرقم سيُجبَر على التحديث. الرقم لا يتجاوز الإصدار المنشور (" + liveCode + ").",
                        "الحد الأدنى الجديد (Version Code)", "مثال: " + liveCode, new CgUi.Callback<Long>() {
                            @Override
                            public void run(final Long value) {
                                if (value.longValue() > liveCode) {
                                    CgUpdateDialogs.error(CgUpdateActivity.this, "رقم غير مسموح",
                                            "الحد الأدنى (" + value + ") أعلى من الإصدار المنشور (" + liveCode + ")؛ سيُحجب كل المستخدمين بلا إمكانية تحديث.");
                                    return;
                                }
                                CgUpdateDialogs.doubleConfirm(CgUpdateActivity.this, "رفع الحد الأدنى إلى " + value,
                                        "كل مستخدم إصداره أقل من " + value + " سيُجبَر على التحديث فوراً.",
                                        "سيصبح الحد الأدنى المدعوم " + value + " لكل المستخدمين.",
                                        "رفع الحد الأدنى", new Runnable() {
                                            @Override
                                            public void run() {
                                                JSONObject extra = new JSONObject();
                                                CxJ.put(extra, "minVersionCode", value);
                                                runEmergency("جارٍ ضبط الحد الأدنى...", "set_min", extra);
                                            }
                                        });
                            }
                        });
            }
        });
        addEmergencyButton(min, live);

        CgUpdateUi.Btn stop = CgUpdateUi.btn(this, "إيقاف التحديث فوراً", CgIcons.STOP, 2);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUpdateDialogs.doubleConfirm(CgUpdateActivity.this, "إيقاف التحديث فوراً",
                        "سيتوقف التطبيق عن عرض التحديث الحالي لكل المستخدمين. يبقى في السجل ويمكن إعادة نشره.",
                        "سيُوقَف الإصدار " + liveCode + " الآن.",
                        "إيقاف فوراً", new Runnable() {
                            @Override
                            public void run() {
                                runEmergency("جارٍ إيقاف التحديث...", "stop", null);
                            }
                        });
            }
        });
        addEmergencyButton(stop, live);

        JSONArray blocked = state.live == null ? null : state.live.optJSONArray("blockedVersions");
        if (blocked != null && blocked.length() > 0) {
            TextView h = CgUi.text(this, "الإصدارات المعطّلة حالياً", 13, CgCfg.ACCENT, true);
            h.setPadding(CgUi.dp(this, 4), CgUi.dp(this, 12), 0, CgUi.dp(this, 6));
            emergencyBox.addView(h);
            LinearLayout wrap = CgUi.vbox(this);
            for (int i = 0; i < blocked.length(); i++) {
                final long code = blocked.optLong(i, 0);
                if (code <= 0) continue;
                LinearLayout row = CgUi.hbox(this);
                row.setBackground(CgUi.bg(this, CgCfg.CARD2, 12, CgCfg.STROKE));
                row.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 6), CgUi.dp(this, 6), CgUi.dp(this, 6));
                TextView t = CgUi.text(this, "Code " + code, 13.5f, CgCfg.TEXT, true);
                CgUi.ltr(t);
                row.addView(t, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
                TextView un = CgUi.smallButton(this, "إلغاء التعطيل", 1);
                un.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        CgUpdateDialogs.confirm(CgUpdateActivity.this, "إلغاء تعطيل الإصدار " + code, CgIcons.CHECK, CgCfg.GOOD,
                                "سيعود الإصدار " + code + " للعمل لمن يملكه.", null, "إلغاء التعطيل", 0, new Runnable() {
                                    @Override
                                    public void run() {
                                        JSONObject extra = new JSONObject();
                                        CxJ.put(extra, "versionCode", Long.valueOf(code));
                                        runEmergency("جارٍ إلغاء التعطيل...", "unblock_version", extra);
                                    }
                                });
                    }
                });
                row.addView(un, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
                wrap.addView(row, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));
            }
            emergencyBox.addView(wrap, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }
    }

    private void addEmergencyButton(CgUpdateUi.Btn b, boolean enabled) {
        if (!enabled) {
            b.setEnabled(false);
            b.setAlpha(0.45f);
        }
        emergencyBox.addView(b, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
    }

    private void runEmergency(String loadingMsg, final String action, final JSONObject extra) {
        runAction(loadingMsg, new CgUpdateApi.Work<JSONObject>() {
            @Override
            public JSONObject run() throws CxApi.Failure {
                return CgUpdateApi.emergency(action, extra);
            }
        }, "تم تنفيذ الإجراء.", "تعذر تنفيذ الإجراء");
    }

    // ------------------------------------------------------------ publish flow

    private void onPrimary() {
        if (busy) return;
        final String mode = form.mode();
        if (!form.validate(mode)) {
            CgUi.toast(this, "راجع الحقول المحددة بالأحمر ثم أعد المحاولة.");
            return;
        }
        if (CgUpdateForm.MODE_DRAFT.equals(mode)) {
            saveDraft();
            return;
        }
        final boolean schedule = CgUpdateForm.MODE_SCHEDULE.equals(mode);
        String msg = schedule
                ? "سيُنشر هذا التحديث تلقائياً في الموعد المحدد."
                : "سيصبح هذا التحديث متاحاً لكل المستخدمين فور النشر.";
        if (CgUpdateModel.FORCE.equals(form.type())) {
            msg += "\n\nتنبيه: هذا تحديث إجباري. من إصداره أقل من الحد الأدنى لن يستطيع متابعة استخدام التطبيق قبل أن يحدّث.";
        }
        CgUpdateDialogs.confirm(this, schedule ? "تأكيد جدولة التحديث" : "تأكيد نشر التحديث",
                schedule ? CgIcons.CALENDAR : CgIcons.PUBLISH, schedule ? CgUpdateModel.BLUE : CgCfg.ACCENT,
                msg, form.summaryRows(mode), schedule ? "جدولة التحديث" : "نشر الآن", 0, new Runnable() {
                    @Override
                    public void run() {
                        doPublish(mode);
                    }
                });
    }

    private void doPublish(final String mode) {
        if (busy) return;
        busy = true;
        final boolean schedule = CgUpdateForm.MODE_SCHEDULE.equals(mode);
        btnPublish.setBusy(true, schedule ? "جارٍ الجدولة..." : "جارٍ النشر...");
        showLoading(schedule ? "جارٍ جدولة التحديث وفحص الملف..." : "جارٍ نشر التحديث وفحص الملف...");
        final JSONObject body = form.toJson(mode);
        CgUpdateApi.async(this, new CgUpdateApi.Work<JSONObject>() {
            @Override
            public JSONObject run() throws CxApi.Failure {
                return CgUpdateApi.publish(body);
            }
        }, new CgUpdateApi.Done<JSONObject>() {
            @Override
            public void ok(JSONObject data) {
                busy = false;
                hideLoading();
                btnPublish.setBusy(false, null);
                form.reset();
                updatePrimary();
                statsLoaded = false;
                reload(false);
                final JSONObject rel = data.optJSONObject("release");
                CgUpdateDialogs.success(CgUpdateActivity.this, data, new Runnable() {
                    @Override
                    public void run() {
                        if (scroll != null) scroll.smoothScrollTo(0, 0);
                    }
                }, rel == null ? null : new Runnable() {
                    @Override
                    public void run() {
                        resendPush(rel.optString("id", ""));
                    }
                });
            }

            @Override
            public void fail(CxApi.Failure f) {
                busy = false;
                hideLoading();
                btnPublish.setBusy(false, null);
                updatePrimary();
                failure(f, schedule ? "تعذر جدولة التحديث" : "تعذر نشر التحديث", true);
            }
        });
    }

    private void saveDraft() {
        if (busy) return;
        busy = true;
        btnPublish.setBusy(true, "جارٍ الحفظ...");
        showLoading("جارٍ حفظ المسودة...");
        final JSONObject body = form.toJson(CgUpdateForm.MODE_DRAFT);
        CgUpdateApi.async(this, new CgUpdateApi.Work<JSONObject>() {
            @Override
            public JSONObject run() throws CxApi.Failure {
                return CgUpdateApi.saveDraft(body);
            }
        }, new CgUpdateApi.Done<JSONObject>() {
            @Override
            public void ok(JSONObject d) {
                busy = false;
                hideLoading();
                btnPublish.setBusy(false, null);
                JSONObject rel = d.optJSONObject("release");
                if (rel != null) form.markDraft(rel.optString("id", ""));
                updatePrimary();
                CgUi.toast(CgUpdateActivity.this, "تم حفظ المسودة. تجدها في قسم «المسودات».");
                reload(false);
            }

            @Override
            public void fail(CxApi.Failure f) {
                busy = false;
                hideLoading();
                btnPublish.setBusy(false, null);
                updatePrimary();
                failure(f, "تعذر حفظ المسودة", true);
            }
        });
    }

    private void resendPush(final String id) {
        if (id.length() == 0 || busy) return;
        runAction("جارٍ إرسال الإشعار...", new CgUpdateApi.Work<JSONObject>() {
            @Override
            public JSONObject run() throws CxApi.Failure {
                return CgUpdateApi.resendPush(id);
            }
        }, "تمت معالجة طلب الإشعار.", "تعذر إرسال الإشعار");
    }

    /** One call, one overlay, one result message, then the screen reloads. Ignores taps while busy. */
    private void runAction(String loadingMsg, CgUpdateApi.Work<JSONObject> work, final String okMsg, final String errTitle) {
        if (busy) return;
        busy = true;
        showLoading(loadingMsg);
        CgUpdateApi.async(this, work, new CgUpdateApi.Done<JSONObject>() {
            @Override
            public void ok(JSONObject d) {
                busy = false;
                hideLoading();
                String m = d == null ? "" : d.optString("message", "");
                CgUi.toast(CgUpdateActivity.this, m.length() > 0 ? m : okMsg);
                statsLoaded = false;
                reload(false);
            }

            @Override
            public void fail(CxApi.Failure f) {
                busy = false;
                hideLoading();
                failure(f, errTitle, false);
            }
        });
    }

    /** Shows a Worker error. When it names a form field (publish / draft) the field itself turns red. */
    private void failure(CxApi.Failure f, String title, boolean formError) {
        String msg = f.getMessage();
        if (f.is("VERSION_TOO_OLD") || f.is("VERSION_EXISTS") || f.is("SCHEDULE_CONFLICT")) reload(false);
        boolean handled = false;
        if (formError && form != null) {
            String field = f.json == null ? "" : f.json.optString("field", "");
            handled = form.applyError(field, msg);
        }
        if (handled) CgUi.toast(this, msg);
        else CgUpdateDialogs.error(this, title, msg);
    }

    // ------------------------------------------------------------ form loading

    private void loadIntoForm(JSONObject r, int how) {
        loadIntoForm(r, how, null);
    }

    private void loadIntoForm(final JSONObject r, final int how, final String modeAfter) {
        if (busy) return;
        final Runnable go = new Runnable() {
            @Override
            public void run() {
                form.loadFromRelease(r, how);
                if (modeAfter != null) form.selectMode(modeAfter);
                updatePrimary();
                secForm.setOpen(true, true);
                scrollToForm();
                CgUi.toast(CgUpdateActivity.this, how == CgUpdateForm.LOAD_COPY
                        ? "تم تحميل البيانات في النموذج. رقم الإصدار رُفع تلقائياً."
                        : "تم فتح الإصدار في النموذج.");
            }
        };
        boolean sameDraft = how == CgUpdateForm.LOAD_DRAFT && form.editingId().equals(r.optString("id", "")) && form.editingId().length() > 0;
        if (form.hasContent() && !sameDraft) {
            CgUpdateDialogs.confirm(this, "استبدال بيانات النموذج", CgIcons.INFO, CgCfg.ACCENT,
                    "ما كتبته الآن في النموذج سيُستبدل ببيانات الإصدار المحدد، ويُلغى رفع الملف الجاري إن وُجد.",
                    null, "استبدال", 0, go);
        } else {
            go.run();
        }
    }

    private void scrollToForm() {
        if (scroll == null) return;
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.smoothScrollTo(0, Math.max(0, secForm.getTop() - CgUi.dp(CgUpdateActivity.this, 8)));
            }
        });
    }

    private void copyText(String text) {
        if (text == null || text.length() == 0) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("APK", text));
            CgUi.toast(this, "تم نسخ الرابط.");
        } catch (Exception e) {
            CgUi.toast(this, "تعذر النسخ إلى الحافظة.");
        }
    }
}
