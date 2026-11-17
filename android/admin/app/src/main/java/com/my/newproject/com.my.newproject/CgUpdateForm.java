package com.my.newproject;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * The "new update" form: four tabs (info / APK / publish / notifications), client-side validation
 * that mirrors the Worker, APK upload to R2 with progress, link check, draft / copy loading.
 * It never talks to the publish endpoints itself; the screen asks it for JSON and for validation.
 */
final class CgUpdateForm {

    interface Host {
        Activity activity();

        void pickApk();

        CgUpdateModel.State state();

        void onFormChanged();
    }

    static final String MODE_NOW = "now";
    static final String MODE_SCHEDULE = "schedule";
    static final String MODE_DRAFT = "draft";

    /** loadFromRelease: open a draft, edit a scheduled / failed / stopped release, or copy an old release as a new one. */
    static final int LOAD_DRAFT = 0;
    static final int LOAD_EDIT = 1;
    static final int LOAD_COPY = 2;

    static final int TAB_INFO = 0;
    static final int TAB_APK = 1;
    static final int TAB_PUBLISH = 2;
    static final int TAB_PUSH = 3;

    private static final int UP_NONE = 0;
    private static final int UP_RUNNING = 1;
    private static final int UP_FAILED = 2;
    private static final int UP_DONE = 3;

    private final Host host;
    private final Activity a;
    private final LinearLayout root;
    private final LinearLayout banner;
    private final TextView bannerText;
    private final CgUpdateUi.Tabs tabs;
    private final LinearLayout[] panes = new LinearLayout[4];

    // ---- info tab
    private CgUpdateUi.Field fName;
    private CgUpdateUi.Field fCode;
    private CgUpdateUi.Field fMin;
    private CgUpdateUi.Field fTitle;
    private CgUpdateUi.Field fDesc;
    private TextView codeHelp;
    private final ArrayList<String> changelog = new ArrayList<String>();
    private LinearLayout changelogBox;
    private TextView changelogError;

    // ---- apk tab
    private boolean external;
    private TextView chipUpload;
    private TextView chipExternal;
    private LinearLayout uploadWrap;
    private LinearLayout externalWrap;
    private TextView apkError;
    private CgUpdateUi.Field fUrl;
    private CgUpdateUi.Btn checkBtn;
    private LinearLayout extResult;
    private LinearLayout apkInfoBox;
    private String checkedUrl = "";

    private LinearLayout emptyBox;
    private LinearLayout fileBox;
    private TextView fileName;
    private TextView fileSizeText;
    private CgUpdateUi.Bar upBar;
    private TextView upPercent;
    private TextView upStatus;
    private LinearLayout upButtons;
    private CgUpdateUi.Btn cancelBtn;
    private CgUpdateUi.Btn retryBtn;
    private CgUpdateUi.Btn changeBtn;
    private LinearLayout linkBox;
    private TextView linkText;

    private CgUpdateUpload upload;
    private int upUi = UP_NONE;
    private boolean upFatal;
    private String upMessage = "";
    private String pickedName = "";
    private long pickedSize;

    private String apkUrl = "";
    private long apkSize;
    private String apkSha = "";
    private String apkPkg = "";
    private long apkVc;
    private String apkVn = "";

    // ---- publish tab
    private String type = CgUpdateModel.OPTIONAL;
    private final LinearLayout[] typeCards = new LinearLayout[4];
    private final View[] typeRadios = new View[4];
    private LinearLayout forceNote;
    private String mode = MODE_NOW;
    private final TextView[] modeChips = new TextView[3];
    private LinearLayout scheduleBox;
    private TextView scheduleText;
    private TextView scheduleError;
    private String scheduledIso = "";
    private TextView maintStatus;
    private CgUi.Toggle tDuring;
    private CgUi.Toggle tAfter;
    private TextView maintError;

    // ---- notifications tab
    private CgUi.Toggle tPush;
    private View pushWarn;
    private CgUpdateUi.Field fPushTitle;
    private CgUpdateUi.Field fPushBody;

    // ---- editing
    private String editingId = "";
    private String editingStatus = "";

    // ---- validation bookkeeping
    private int vFirstTab = -1;
    private CgUpdateUi.Field vFocus;

    CgUpdateForm(Host h) {
        host = h;
        a = h.activity();
        root = CgUi.vbox(a);

        banner = CgUi.hbox(a);
        banner.setBackground(CgUi.bg(a, CgUi.alpha(CgCfg.ACCENT, 0x1A), 14, CgUi.alpha(CgCfg.ACCENT, 0x88)));
        int bp = CgUi.dp(a, 10);
        banner.setPadding(bp, bp, bp, bp);
        banner.addView(CgUpdateUi.icon(a, CgIcons.EDIT, CgCfg.ACCENT, 18),
                new LinearLayout.LayoutParams(CgUi.dp(a, 18), CgUi.dp(a, 18)));
        bannerText = CgUi.text(a, "", 12.5f, CgCfg.TEXT, false);
        LinearLayout.LayoutParams btp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        btp.setMargins(CgUi.dp(a, 8), 0, CgUi.dp(a, 8), 0);
        banner.addView(bannerText, btp);
        TextView bx = CgUi.smallButton(a, "إلغاء", 3);
        bx.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reset();
                host.onFormChanged();
            }
        });
        banner.addView(bx, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        banner.setVisibility(View.GONE);
        root.addView(banner, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        tabs = new CgUpdateUi.Tabs(a, new String[]{"المعلومات", "APK", "النشر", "الإشعارات"}, new CgUi.IntCb() {
            @Override
            public void run(int index) {
                showPane(index);
            }
        });
        root.addView(tabs.view, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));
        for (int i = 0; i < panes.length; i++) {
            panes[i] = CgUi.vbox(a);
            root.addView(panes[i], new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }
        buildInfo();
        buildApk();
        buildPublish();
        buildPush();
        tabs.select(0, false);
        showPane(0);
        refreshAll();
    }

    View view() {
        return root;
    }

    String mode() {
        return mode;
    }

    String type() {
        return type;
    }

    String editingId() {
        return editingId;
    }

    String editingStatus() {
        return editingStatus;
    }

    long apkSize() {
        return apkSize;
    }

    void selectMode(String m) {
        setMode(m);
    }

    private CgUpdateModel.State st() {
        return host.state();
    }

    private void changed() {
        host.onFormChanged();
    }

    private final Runnable changedCb = new Runnable() {
        @Override
        public void run() {
            refreshApkInfo();
            refreshForceNote();
            changed();
        }
    };

    // ================================================================= tabs

    void showTab(int i) {
        tabs.select(i, false);
        showPane(i);
    }

    private void showPane(int index) {
        for (int k = 0; k < panes.length; k++) {
            panes[k].setVisibility(k == index ? View.VISIBLE : View.GONE);
        }
        CgUpdateUi.fadeIn(panes[index]);
    }

    // ================================================================= info tab

    private void buildInfo() {
        LinearLayout p = panes[TAB_INFO];

        fName = CgUpdateUi.field(a, "اسم الإصدار (Version Name)", "مثال: 2.5.0", CgUpdateUi.K_NAME, 40);
        fName.onChange(changedCb);
        p.addView(fName.view);

        LinearLayout two = CgUi.vbox(a);
        two.setOrientation(LinearLayout.HORIZONTAL);
        fCode = CgUpdateUi.field(a, "رقم الإصدار (Version Code)", "مثال: 25", CgUpdateUi.K_NUMBER, 10);
        fMin = CgUpdateUi.field(a, "الحد الأدنى المدعوم", "0 = بدون حد أدنى", CgUpdateUi.K_NUMBER, 10);
        fCode.onChange(changedCb);
        fMin.onChange(changedCb);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        l1.setMargins(CgUi.dp(a, 6), 0, 0, 0);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        l2.setMargins(0, 0, CgUi.dp(a, 6), 0);
        // first child sits on the right in RTL
        two.addView(fCode.view, l1);
        two.addView(fMin.view, l2);
        p.addView(two, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        codeHelp = CgUi.text(a, "", 11.5f, CgCfg.MUTED, false);
        codeHelp.setPadding(CgUi.dp(a, 4), 0, CgUi.dp(a, 4), CgUi.dp(a, 12));
        p.addView(codeHelp, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        fTitle = CgUpdateUi.field(a, "عنوان التحديث", "مثال: تحديث جديد لسيكرام", CgUpdateUi.K_TEXT, 120);
        fTitle.onChange(changedCb);
        p.addView(fTitle.view);

        fDesc = CgUpdateUi.field(a, "وصف مختصر", "اكتب وصفاً قصيراً يفهمه المستخدم", CgUpdateUi.K_MULTI, 600);
        fDesc.onChange(changedCb);
        p.addView(fDesc.view);

        // ---- changelog
        TextView lbl = CgUi.text(a, "ما الجديد في هذا الإصدار", 12.5f, CgCfg.MUTED, false);
        lbl.setPadding(CgUi.dp(a, 4), 0, CgUi.dp(a, 4), CgUi.dp(a, 6));
        p.addView(lbl, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        changelogBox = CgUi.vbox(a);
        p.addView(changelogBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        changelogError = CgUi.text(a, "", 12, CgCfg.BAD, false);
        changelogError.setPadding(CgUi.dp(a, 4), CgUi.dp(a, 4), CgUi.dp(a, 4), 0);
        changelogError.setVisibility(View.GONE);
        p.addView(changelogError, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        CgUpdateUi.Btn add = CgUpdateUi.btn(a, "إضافة تغيير", CgIcons.PLUS, 1);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editChangelog(-1);
            }
        });
        p.addView(add, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 4));
        renderChangelog();
    }

    private void renderChangelog() {
        changelogBox.removeAllViews();
        if (changelog.isEmpty()) {
            TextView t = CgUi.muted(a, "لم تُضف أي تغييرات بعد. أضف بنداً لكل تحسين أو إصلاح ليظهر في نافذة التحديث.");
            t.setPadding(CgUi.dp(a, 4), 0, CgUi.dp(a, 4), CgUi.dp(a, 4));
            changelogBox.addView(t);
            return;
        }
        for (int i = 0; i < changelog.size(); i++) {
            final int idx = i;
            LinearLayout row = CgUi.hbox(a);
            row.setBackground(CgUi.bg(a, CgCfg.CARD2, 12, CgCfg.STROKE));
            row.setPadding(CgUi.dp(a, 12), CgUi.dp(a, 4), CgUi.dp(a, 4), CgUi.dp(a, 4));
            TextView num = CgUi.text(a, String.valueOf(i + 1), 12, CgCfg.ACCENT, true);
            num.setGravity(Gravity.CENTER);
            row.addView(num, new LinearLayout.LayoutParams(CgUi.dp(a, 22), CgUi.WRAP));
            TextView t = CgUi.text(a, changelog.get(i), 13.5f, CgCfg.TEXT, false);
            t.setPadding(CgUi.dp(a, 6), CgUi.dp(a, 8), CgUi.dp(a, 6), CgUi.dp(a, 8));
            row.addView(t, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            row.addView(iconAction(CgIcons.EDIT, CgCfg.MUTED, new Runnable() {
                @Override
                public void run() {
                    editChangelog(idx);
                }
            }));
            row.addView(iconAction(CgIcons.TRASH, CgCfg.BAD, new Runnable() {
                @Override
                public void run() {
                    if (idx < changelog.size()) changelog.remove(idx);
                    renderChangelog();
                    changed();
                }
            }));
            changelogBox.addView(row, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));
        }
    }

    private View iconAction(int kind, int color, final Runnable r) {
        FrameLayout f = new FrameLayout(a);
        f.setClickable(true);
        f.addView(CgUpdateUi.icon(a, kind, color, 18), new FrameLayout.LayoutParams(CgUi.dp(a, 18), CgUi.dp(a, 18), Gravity.CENTER));
        f.setLayoutParams(new LinearLayout.LayoutParams(CgUi.dp(a, 40), CgUi.dp(a, 40)));
        f.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
        return f;
    }

    /** index -1 adds a new item. */
    private void editChangelog(final int index) {
        if (index < 0 && changelog.size() >= 30) {
            CgUi.toast(a, "الحد الأقصى 30 بنداً.");
            return;
        }
        String initial = index >= 0 && index < changelog.size() ? changelog.get(index) : "";
        CgUi.input(a, index >= 0 ? "تعديل البند" : "إضافة تغيير", "مثال: إصلاح مشكلة تشغيل الفيديو", initial, false,
                new CgUi.Callback<String>() {
                    @Override
                    public void run(String value) {
                        String v = value == null ? "" : value.trim();
                        if (v.length() == 0) return;
                        if (v.length() > 200) {
                            CgUpdateDialogs.error(a, "البند طويل", "البند الواحد لا يتجاوز 200 حرف. اختصره أو قسّمه إلى بندين.");
                            return;
                        }
                        changelogError.setVisibility(View.GONE);
                        if (index >= 0 && index < changelog.size()) changelog.set(index, v);
                        else changelog.add(v);
                        renderChangelog();
                        changed();
                    }
                });
    }

    // ================================================================= apk tab

    private void buildApk() {
        LinearLayout p = panes[TAB_APK];

        TextView lbl = CgUi.text(a, "مصدر ملف APK", 12.5f, CgCfg.MUTED, false);
        lbl.setPadding(CgUi.dp(a, 4), 0, CgUi.dp(a, 4), CgUi.dp(a, 6));
        p.addView(lbl, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        LinearLayout chips = CgUi.hbox(a);
        chipUpload = CgUi.chip(a, "رفع ملف APK", true);
        chipExternal = CgUi.chip(a, "رابط خارجي", false);
        LinearLayout.LayoutParams c1 = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        c1.setMargins(CgUi.dp(a, 4), 0, CgUi.dp(a, 4), 0);
        LinearLayout.LayoutParams c2 = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        c2.setMargins(CgUi.dp(a, 4), 0, CgUi.dp(a, 4), 0);
        chips.addView(chipUpload, c1);
        chips.addView(chipExternal, c2);
        chipUpload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSource(false);
            }
        });
        chipExternal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setSource(true);
            }
        });
        p.addView(chips, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        apkError = CgUi.text(a, "", 12.5f, CgCfg.BAD, false);
        apkError.setPadding(CgUi.dp(a, 4), 0, CgUi.dp(a, 4), CgUi.dp(a, 8));
        apkError.setVisibility(View.GONE);
        p.addView(apkError, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        // ---------- upload
        uploadWrap = CgUi.vbox(a);
        LinearLayout card = CgUi.vbox(a);
        card.setBackground(CgUi.bg(a, CgCfg.CARD2, 16, CgCfg.STROKE));
        int pad = CgUi.dp(a, 14);
        card.setPadding(pad, pad, pad, pad);

        emptyBox = CgUi.vbox(a);
        emptyBox.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyBox.addView(CgUpdateUi.iconCircle(a, CgIcons.UPLOAD, CgCfg.ACCENT, 54, 28));
        TextView et = CgUi.text(a, "ارفع ملف APK الجديد", 15, CgCfg.TEXT, true);
        et.setGravity(Gravity.CENTER);
        et.setPadding(0, CgUi.dp(a, 10), 0, 0);
        emptyBox.addView(et, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        TextView es = CgUi.muted(a, "يُرفع إلى R2 على دفعات مع شريط تقدّم، ثم يفحصه الـ Worker ويقرأ رقم الإصدار من داخله.");
        es.setGravity(Gravity.CENTER);
        es.setPadding(0, CgUi.dp(a, 4), 0, CgUi.dp(a, 12));
        emptyBox.addView(es, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        CgUpdateUi.Btn pick = CgUpdateUi.btn(a, "اختيار ملف APK", CgIcons.FILE, 0);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.pickApk();
            }
        });
        emptyBox.addView(pick, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        card.addView(emptyBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        fileBox = CgUi.vbox(a);
        LinearLayout nameRow = CgUi.hbox(a);
        nameRow.addView(CgUpdateUi.icon(a, CgIcons.FILE, CgCfg.ACCENT, 22), new LinearLayout.LayoutParams(CgUi.dp(a, 22), CgUi.dp(a, 22)));
        LinearLayout names = CgUi.vbox(a);
        fileName = CgUi.text(a, "", 14, CgCfg.TEXT, true);
        CgUi.ltr(fileName);
        fileName.setSingleLine(true);
        fileName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        names.addView(fileName, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        fileSizeText = CgUi.muted(a, "");
        names.addView(fileSizeText, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        np.setMargins(CgUi.dp(a, 10), 0, 0, 0);
        nameRow.addView(names, np);
        fileBox.addView(nameRow, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        upBar = new CgUpdateUi.Bar(a);
        fileBox.addView(upBar, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 6));
        LinearLayout st = CgUi.hbox(a);
        upStatus = CgUi.text(a, "", 12.5f, CgCfg.MUTED, false);
        st.addView(upStatus, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        upPercent = CgUi.text(a, "", 13, CgCfg.TEXT, true);
        CgUi.ltr(upPercent);
        st.addView(upPercent, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        fileBox.addView(st, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        upButtons = CgUi.hbox(a);
        cancelBtn = CgUpdateUi.btn(a, "إلغاء الرفع", CgIcons.CLOSE, 2);
        retryBtn = CgUpdateUi.btn(a, "إعادة المحاولة", CgIcons.REFRESH, 0);
        changeBtn = CgUpdateUi.btn(a, "تغيير الملف", CgIcons.UPLOAD, 1);
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelUpload();
                refreshUpload();
                changed();
            }
        });
        retryBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                retryUpload();
            }
        });
        changeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelUpload();
                host.pickApk();
            }
        });
        upButtons.addView(retryBtn, weight(4));
        upButtons.addView(cancelBtn, weight(4));
        upButtons.addView(changeBtn, weight(4));
        fileBox.addView(upButtons, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 0));
        card.addView(fileBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        linkBox = CgUi.vbox(a);
        linkBox.setPadding(0, CgUi.dp(a, 12), 0, 0);
        TextView ll = CgUi.text(a, "الرابط النهائي", 12, CgCfg.MUTED, true);
        linkBox.addView(ll, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        linkText = CgUi.text(a, "", 12, CgCfg.ACCENT, false);
        CgUi.ltr(linkText);
        linkText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        linkText.setPadding(0, CgUi.dp(a, 4), 0, CgUi.dp(a, 8));
        linkBox.addView(linkText, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        CgUpdateUi.Btn copy = CgUpdateUi.btn(a, "نسخ الرابط", CgIcons.COPY, 1);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyText(apkUrl);
            }
        });
        linkBox.addView(copy, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        card.addView(linkBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        uploadWrap.addView(card, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        p.addView(uploadWrap, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        // ---------- external link
        externalWrap = CgUi.vbox(a);
        fUrl = CgUpdateUi.field(a, "رابط تحميل APK", "https://example.com/cigram-2.5.0.apk", CgUpdateUi.K_URL, 1000);
        fUrl.help("رابط مباشر لملف APK يبدأ بـ https://. للتحديث الإجباري يُفضَّل الرفع إلى R2 لضمان بقاء الملف متاحاً.");
        fUrl.onChange(new Runnable() {
            @Override
            public void run() {
                if (!fUrl.text().equals(checkedUrl)) {
                    checkedUrl = "";
                    extResult.removeAllViews();
                }
                apkError.setVisibility(View.GONE);
                refreshApkInfo();
                changed();
            }
        });
        externalWrap.addView(fUrl.view);
        checkBtn = CgUpdateUi.btn(a, "فحص الرابط", CgIcons.LINK, 1);
        checkBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkExternal();
            }
        });
        externalWrap.addView(checkBtn, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        extResult = CgUi.vbox(a);
        externalWrap.addView(extResult, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        externalWrap.setVisibility(View.GONE);
        p.addView(externalWrap, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        // ---------- what we know about the file
        apkInfoBox = CgUi.vbox(a);
        apkInfoBox.setBackground(CgUi.bg(a, CgCfg.CARD, 14, CgCfg.STROKE));
        apkInfoBox.setPadding(CgUi.dp(a, 12), CgUi.dp(a, 8), CgUi.dp(a, 12), CgUi.dp(a, 8));
        apkInfoBox.setVisibility(View.GONE);
        p.addView(apkInfoBox, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

        refreshUpload();
    }

    private LinearLayout.LayoutParams weight(float marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        lp.setMargins(CgUi.dp(a, marginDp), 0, CgUi.dp(a, marginDp), 0);
        return lp;
    }

    private void setSource(boolean ext) {
        external = ext;
        CgUi.setChipState(chipUpload, !ext);
        CgUi.setChipState(chipExternal, ext);
        uploadWrap.setVisibility(ext ? View.GONE : View.VISIBLE);
        externalWrap.setVisibility(ext ? View.VISIBLE : View.GONE);
        apkError.setVisibility(View.GONE);
        refreshApkInfo();
        changed();
    }

    /** Shows the card state that matches the upload: empty / running / failed / done. */
    private void refreshUpload() {
        boolean hasFile = upUi != UP_NONE;
        emptyBox.setVisibility(hasFile ? View.GONE : View.VISIBLE);
        fileBox.setVisibility(hasFile ? View.VISIBLE : View.GONE);
        linkBox.setVisibility(upUi == UP_DONE && apkUrl.length() > 0 ? View.VISIBLE : View.GONE);
        if (!hasFile) {
            refreshApkInfo();
            return;
        }
        String name = pickedName.length() > 0 ? pickedName : (apkUrl.length() > 0 ? lastSegment(apkUrl) : "");
        fileName.setText(name);
        long size = pickedSize > 0 ? pickedSize : apkSize;
        fileSizeText.setText(size > 0 ? CgUi.sizeText(size) : "");
        retryBtn.setVisibility(upUi == UP_FAILED && !upFatal ? View.VISIBLE : View.GONE);
        cancelBtn.setVisibility(upUi == UP_RUNNING ? View.VISIBLE : View.GONE);
        changeBtn.setVisibility(upUi == UP_RUNNING ? View.GONE : View.VISIBLE);
        if (upUi == UP_DONE) {
            upBar.color(CgCfg.GOOD);
            upBar.set(1f);
            upPercent.setText("100%");
            upPercent.setTextColor(CgCfg.GOOD);
            upStatus.setText("تم رفع الملف وفحصه بنجاح");
            upStatus.setTextColor(CgCfg.GOOD);
            linkText.setText(apkUrl);
        } else if (upUi == UP_FAILED) {
            upBar.color(CgCfg.BAD);
            upStatus.setText(upMessage.length() > 0 ? upMessage : "فشل رفع الملف.");
            upStatus.setTextColor(CgCfg.BAD);
            upPercent.setTextColor(CgCfg.BAD);
        } else {
            upBar.color(CgCfg.ACCENT);
            upStatus.setTextColor(CgCfg.MUTED);
            upPercent.setTextColor(CgCfg.TEXT);
        }
        refreshApkInfo();
    }

    private static String lastSegment(String url) {
        int i = url.lastIndexOf('/');
        return i >= 0 && i < url.length() - 1 ? url.substring(i + 1) : url;
    }

    boolean uploadRunning() {
        return upload != null && upload.running();
    }

    /** Stops a running upload (the Worker aborts the multipart upload) and clears the card. */
    void cancelUpload() {
        if (upload != null) {
            upload.cancel();
            upload = null;
        }
        if (upUi == UP_RUNNING || upUi == UP_FAILED) {
            upUi = UP_NONE;
            pickedName = "";
            pickedSize = 0;
            upMessage = "";
        }
    }

    private void retryUpload() {
        if (upload == null || upload.running()) return;
        upUi = UP_RUNNING;
        upMessage = "";
        apkError.setVisibility(View.GONE);
        refreshUpload();
        upload.start();
        changed();
    }

    /** Called by the screen with the file the user picked. */
    void onApkPicked(Uri uri) {
        if (uri == null) return;
        String name = "app.apk";
        long size = -1;
        Cursor c = null;
        try {
            c = a.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) {
            // some providers refuse the query; the size is read below
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) { }
            }
        }
        if (size <= 0) {
            AssetFileDescriptor afd = null;
            try {
                afd = a.getContentResolver().openAssetFileDescriptor(uri, "r");
                if (afd != null) size = afd.getLength();
            } catch (Exception ignored) {
                size = -1;
            } finally {
                if (afd != null) {
                    try {
                        afd.close();
                    } catch (Exception ignored) { }
                }
            }
        }
        CgUpdateModel.State s = st();
        long max = s == null ? 400L * 1024L * 1024L : s.maxBytes;
        long part = s == null ? 6L * 1024L * 1024L : s.partSize;
        showTab(TAB_APK);
        setSource(false);
        if (size == 0) {
            apkError.setText("الملف المختار فارغ.");
            apkError.setVisibility(View.VISIBLE);
            return;
        }
        if (size > max) {
            apkError.setText("حجم الملف (" + CgUi.sizeText(size) + ") أكبر من الحد المسموح (" + CgUi.sizeText(max) + ").");
            apkError.setVisibility(View.VISIBLE);
            return;
        }
        apkError.setVisibility(View.GONE);
        if (upload != null) {
            upload.cancel();
            upload = null;
        }
        pickedName = name;
        pickedSize = size > 0 ? size : 0;
        apkUrl = "";
        apkSize = 0;
        apkSha = "";
        apkPkg = "";
        apkVc = 0;
        apkVn = "";
        upFatal = false;
        upMessage = "";
        upUi = UP_RUNNING;
        upBar.set(0f);
        upPercent.setText("0%");
        upStatus.setText("جارٍ التجهيز...");
        upload = new CgUpdateUpload(a, uri, name, pickedSize, normName(), part, new CgUpdateUpload.Listener() {
            @Override
            public void onProgress(long sent, long total) {
                float f = total > 0 ? Math.min(1f, (float) sent / (float) total) : 0f;
                if (total > 0 && sent > total) sent = total;
                upBar.set(f);
                upPercent.setText(total > 0 ? ((int) (f * 100f)) + "%" : "");
                upStatus.setText(total > 0 && sent >= total
                        ? "اكتمل الرفع، جارٍ فحص الملف على الخادم..."
                        : total > 0
                        ? "تم رفع " + CgUi.sizeText(sent) + " من " + CgUi.sizeText(total)
                        : "تم رفع " + CgUi.sizeText(sent));
            }

            @Override
            public void onState(int state, String message, boolean fatal) {
                if (state == CgUpdateUpload.FAILED) {
                    upUi = UP_FAILED;
                    upFatal = fatal;
                    upMessage = message == null ? "" : message;
                    refreshUpload();
                    changed();
                } else if (state == CgUpdateUpload.UPLOADING) {
                    upUi = UP_RUNNING;
                    upStatus.setText(message);
                    refreshUpload();
                }
            }

            @Override
            public void onDone(JSONObject r) {
                apkUrl = r.optString("url", "");
                apkSize = r.optLong("size", 0);
                apkSha = r.optString("sha256", "");
                JSONObject info = r.optJSONObject("apkInfo");
                if (info != null) {
                    apkPkg = info.optString("packageName", "");
                    apkVc = info.optLong("versionCode", 0);
                    apkVn = info.optString("versionName", "");
                }
                upUi = UP_DONE;
                upload = null;
                if (fCode.text().length() == 0 && apkVc > 0) fCode.set(String.valueOf(apkVc));
                if (fName.text().length() == 0 && apkVn.length() > 0) fName.set(apkVn);
                refreshUpload();
                changed();
            }
        });
        refreshUpload();
        upload.start();
        changed();
    }

    private String normName() {
        return CgUpdateModel.normalizeDigits(fName.text());
    }

    // ----------------------------------------------------- external link check

    private void checkExternal() {
        final String url = fUrl.text();
        apkError.setVisibility(View.GONE);
        if (url.length() == 0) {
            fUrl.error("أدخل رابط APK أولاً.");
            return;
        }
        if (!url.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            fUrl.error("يجب أن يبدأ الرابط بـ https://");
            return;
        }
        final long code = CgUpdateModel.parseLong(CgUpdateModel.normalizeDigits(fCode.text()));
        final String name = normName();
        checkBtn.setBusy(true, "جارٍ فحص الرابط...");
        extResult.removeAllViews();
        CgUpdateApi.async(a, new CgUpdateApi.Work<JSONObject>() {
            @Override
            public JSONObject run() throws CxApi.Failure {
                return CgUpdateApi.validate(url, code, name);
            }
        }, new CgUpdateApi.Done<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                checkBtn.setBusy(false, "فحص الرابط");
                if (!url.equals(fUrl.text())) return;
                boolean good = r.optBoolean("reachable", false) && r.optBoolean("isApk", false);
                String msg = r.optString("message", good ? "الرابط يعمل والملف APK صالح." : "تعذر التحقق من الرابط.");
                extResult.removeAllViews();
                extResult.addView(CgUpdateUi.note(a, good ? CgIcons.CHECK : CgIcons.WARNING, msg, good ? CgCfg.GOOD : CgCfg.BAD));
                JSONArray w = r.optJSONArray("warnings");
                for (int i = 0; w != null && i < w.length(); i++) {
                    String t = w.optString(i, "").trim();
                    if (t.length() > 0) extResult.addView(CgUpdateUi.note(a, CgIcons.WARNING, t, CgCfg.WARN));
                }
                if (good) {
                    checkedUrl = url;
                    apkSize = r.optLong("size", 0);
                    JSONObject info = r.optJSONObject("apkInfo");
                    apkPkg = info == null ? "" : info.optString("packageName", "");
                    apkVc = info == null ? 0 : info.optLong("versionCode", 0);
                    apkVn = info == null ? "" : info.optString("versionName", "");
                    apkSha = "";
                    if (fCode.text().length() == 0 && apkVc > 0) fCode.set(String.valueOf(apkVc));
                    if (fName.text().length() == 0 && apkVn.length() > 0) fName.set(apkVn);
                } else {
                    checkedUrl = "";
                }
                refreshApkInfo();
                changed();
            }

            @Override
            public void fail(CxApi.Failure f) {
                checkBtn.setBusy(false, "فحص الرابط");
                extResult.removeAllViews();
                extResult.addView(CgUpdateUi.note(a, CgIcons.WARNING, f.getMessage(), CgCfg.BAD));
            }
        });
    }

    // ------------------------------------------------------------- apk info

    private boolean infoApplies() {
        if (external) return checkedUrl.length() > 0 && checkedUrl.equals(fUrl.text());
        return upUi == UP_DONE && apkUrl.length() > 0;
    }

    private void refreshApkInfo() {
        if (apkInfoBox == null) return;
        apkInfoBox.removeAllViews();
        boolean known = apkVc > 0 || apkPkg.length() > 0 || apkSize > 0;
        if (!infoApplies() || !known) {
            apkInfoBox.setVisibility(View.GONE);
            return;
        }
        apkInfoBox.setVisibility(View.VISIBLE);
        TextView h = CgUi.text(a, "بيانات الملف", 13, CgCfg.ACCENT, true);
        h.setPadding(0, 0, 0, CgUi.dp(a, 4));
        apkInfoBox.addView(h);
        if (apkSize > 0) apkInfoBox.addView(CgUpdateUi.kv(a, "الحجم", CgUi.sizeText(apkSize), CgCfg.TEXT, true));
        if (apkPkg.length() > 0) apkInfoBox.addView(CgUpdateUi.kv(a, "الحزمة", apkPkg, CgCfg.TEXT, true));
        if (apkVc > 0) apkInfoBox.addView(CgUpdateUi.kv(a, "Version Code داخل APK", String.valueOf(apkVc), CgCfg.TEXT, true));
        if (apkVn.length() > 0) apkInfoBox.addView(CgUpdateUi.kv(a, "Version Name داخل APK", apkVn, CgCfg.TEXT, true));
        if (apkSha.length() >= 16) apkInfoBox.addView(CgUpdateUi.kv(a, "SHA-256", apkSha.substring(0, 16) + "…", CgCfg.TEXT, true));

        long typed = CgUpdateModel.parseLong(CgUpdateModel.normalizeDigits(fCode.text()));
        if (apkVc > 0 && typed > 0 && typed != apkVc) {
            final long fix = apkVc;
            apkInfoBox.addView(CgUpdateUi.note(a, CgIcons.WARNING,
                    "رقم الإصدار داخل APK (" + apkVc + ") لا يطابق Version Code المكتوب (" + typed + "). لن يُقبل النشر قبل التطابق.",
                    CgCfg.BAD));
            CgUpdateUi.Btn use = CgUpdateUi.btn(a, "استخدام الرقم " + fix + " من الملف", CgIcons.CHECK, 1);
            use.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    fCode.set(String.valueOf(fix));
                    refreshApkInfo();
                    changed();
                }
            });
            apkInfoBox.addView(use, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        } else if (apkVc > 0 && typed == apkVc) {
            apkInfoBox.addView(CgUpdateUi.note(a, CgIcons.CHECK, "رقم الإصدار داخل APK يطابق ما كتبته.", CgCfg.GOOD));
        }
        String nm = normName();
        if (apkVn.length() > 0 && nm.length() > 0 && !apkVn.equals(nm)) {
            apkInfoBox.addView(CgUpdateUi.note(a, CgIcons.INFO,
                    "اسم الإصدار داخل APK (" + apkVn + ") يختلف عن المكتوب (" + nm + "). هذا تنبيه فقط ولا يمنع النشر.", CgCfg.WARN));
        }
    }

    private String currentUrl() {
        return external ? fUrl.text() : apkUrl;
    }

    private void copyText(String text) {
        if (text == null || text.length() == 0) return;
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("APK", text));
            CgUi.toast(a, "تم نسخ الرابط.");
        } catch (Exception e) {
            CgUi.toast(a, "تعذر النسخ إلى الحافظة.");
        }
    }

    // ============================================================== publish tab

    private void buildPublish() {
        LinearLayout p = panes[TAB_PUBLISH];

        p.addView(CgUi.sectionTitle(a, "نوع التحديث"));
        for (int i = 0; i < CgUpdateModel.TYPES.length; i++) {
            final String t = CgUpdateModel.TYPES[i];
            LinearLayout card = CgUi.vbox(a);
            card.setClickable(true);
            int pad = CgUi.dp(a, 12);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout head = CgUi.hbox(a);
            View radio = new View(a);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(CgUi.dp(a, 18), CgUi.dp(a, 18));
            rp.setMargins(0, 0, CgUi.dp(a, 10), 0);
            head.addView(radio, rp);
            head.addView(CgUi.text(a, CgUpdateModel.typeLabel(t), 14.5f, CgCfg.TEXT, true), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            head.addView(CgUi.badge(a, CgUpdateModel.typeShort(t), CgUpdateModel.typeColor(t)), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            card.addView(head, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            TextView d = CgUi.muted(a, CgUpdateModel.typeDescription(t));
            d.setPadding(0, CgUi.dp(a, 6), 0, 0);
            card.addView(d, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setType(t);
                }
            });
            CgUpdateUi.press(card);
            typeCards[i] = card;
            typeRadios[i] = radio;
            p.addView(card, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
        forceNote = CgUi.vbox(a);
        forceNote.addView(CgUpdateUi.note(a, CgIcons.WARNING,
                "التحديث الإجباري يحتاج «الحد الأدنى المدعوم» أكبر من صفر (تبويب المعلومات)، وإلا فلن يُجبَر أي مستخدم على التحديث.", CgCfg.WARN));
        CgUpdateUi.Btn setMin = CgUpdateUi.btn(a, "اجعل الحد الأدنى = رقم هذا الإصدار", CgIcons.CHECK, 1);
        setMin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long code = CgUpdateModel.parseLong(CgUpdateModel.normalizeDigits(fCode.text()));
                if (code < 1) {
                    CgUi.toast(a, "اكتب رقم الإصدار أولاً في تبويب المعلومات.");
                    return;
                }
                fMin.set(String.valueOf(code));
                refreshForceNote();
                changed();
            }
        });
        forceNote.addView(setMin, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        forceNote.setVisibility(View.GONE);
        p.addView(forceNote, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 4));

        p.addView(CgUi.sectionTitle(a, "طريقة النشر"));
        LinearLayout chips = CgUi.hbox(a);
        String[] names = {"نشر الآن", "جدولة", "مسودة"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView chip = CgUi.chip(a, names[i], i == 0);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setMode(idx == 0 ? MODE_NOW : (idx == 1 ? MODE_SCHEDULE : MODE_DRAFT));
                }
            });
            modeChips[i] = chip;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
            lp.setMargins(CgUi.dp(a, 4), 0, CgUi.dp(a, 4), 0);
            chips.addView(chip, lp);
        }
        p.addView(chips, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));

        scheduleBox = CgUi.vbox(a);
        scheduleBox.setBackground(CgUi.bg(a, CgCfg.CARD2, 14, CgCfg.STROKE));
        int sp = CgUi.dp(a, 12);
        scheduleBox.setPadding(sp, sp, sp, sp);
        scheduleText = CgUi.text(a, "", 14, CgCfg.TEXT, true);
        scheduleBox.addView(scheduleText, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        CgUpdateUi.Btn pickTime = CgUpdateUi.btn(a, "اختيار التاريخ والوقت", CgIcons.CALENDAR, 1);
        pickTime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUi.pickDateTime(a, scheduledIso, new CgUi.Callback<String>() {
                    @Override
                    public void run(String iso) {
                        scheduledIso = iso;
                        scheduleError.setVisibility(View.GONE);
                        refreshSchedule();
                        changed();
                    }
                });
            }
        });
        scheduleBox.addView(pickTime, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        scheduleBox.addView(CgUpdateUi.note(a, CgIcons.INFO,
                "يُنشر التحديث تلقائياً في الموعد عبر Cron Trigger في الـ Worker. الوقت بتوقيت جهازك.", CgCfg.ACCENT));
        scheduleError = CgUi.text(a, "", 12, CgCfg.BAD, false);
        scheduleError.setVisibility(View.GONE);
        scheduleBox.addView(scheduleError, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        scheduleBox.setVisibility(View.GONE);
        p.addView(scheduleBox, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 4));

        p.addView(CgUi.sectionTitle(a, "وضع الصيانة (اختياري)"));
        LinearLayout maint = CgUi.vbox(a);
        maint.setBackground(CgUi.bg(a, CgCfg.CARD2, 14, CgCfg.STROKE));
        int mp = CgUi.dp(a, 12);
        maint.setPadding(mp, mp, mp, mp);
        maintStatus = CgUi.muted(a, "");
        maintStatus.setPadding(0, 0, 0, CgUi.dp(a, 6));
        maint.addView(maintStatus, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        tDuring = CgUi.toggle(a, "تفعيل وضع الصيانة أثناء التحديث", false);
        tAfter = CgUi.toggle(a, "إلغاء وضع الصيانة بعد النشر", false);
        tDuring.onChange(new Runnable() {
            @Override
            public void run() {
                if (tDuring.on && tAfter.on) tAfter.silent(false);
                maintError.setVisibility(View.GONE);
                changed();
            }
        });
        tAfter.onChange(new Runnable() {
            @Override
            public void run() {
                if (tAfter.on && tDuring.on) tDuring.silent(false);
                maintError.setVisibility(View.GONE);
                changed();
            }
        });
        maint.addView(tDuring);
        maint.addView(tAfter);
        TextView mh = CgUi.muted(a, "يُفعَّل قبل نشر الملف فقط عند «نشر الآن»، ويستخدم نفس نظام الصيانة الحالي دون تغيير إعداداته.");
        mh.setPadding(0, CgUi.dp(a, 4), 0, 0);
        maint.addView(mh, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        maintError = CgUi.text(a, "", 12, CgCfg.BAD, false);
        maintError.setVisibility(View.GONE);
        maint.addView(maintError, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        p.addView(maint, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 4));
        refreshTypes();
    }

    private void setType(String t) {
        type = t;
        refreshTypes();
        refreshForceNote();
        changed();
    }

    private void refreshTypes() {
        for (int i = 0; i < CgUpdateModel.TYPES.length; i++) {
            String t = CgUpdateModel.TYPES[i];
            boolean on = t.equals(type);
            int color = CgUpdateModel.typeColor(t);
            typeCards[i].setBackground(on
                    ? CgUi.bg(a, CgUi.alpha(color, 0x1F), 14, color)
                    : CgUi.bg(a, CgCfg.CARD2, 14, CgCfg.STROKE));
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(on ? color : 0);
            g.setStroke(CgUi.dp(a, 2), on ? color : CgCfg.MUTED);
            typeRadios[i].setBackground(g);
        }
    }

    private void refreshForceNote() {
        if (forceNote == null) return;
        long min = CgUpdateModel.parseLong(CgUpdateModel.normalizeDigits(fMin.text()));
        boolean show = CgUpdateModel.FORCE.equals(type) && min < 1;
        forceNote.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void setMode(String m) {
        mode = m;
        String[] keys = {MODE_NOW, MODE_SCHEDULE, MODE_DRAFT};
        for (int i = 0; i < keys.length; i++) CgUi.setChipState(modeChips[i], keys[i].equals(m));
        scheduleBox.setVisibility(MODE_SCHEDULE.equals(m) ? View.VISIBLE : View.GONE);
        scheduleError.setVisibility(View.GONE);
        refreshSchedule();
        changed();
    }

    private void refreshSchedule() {
        long ms = CgUi.parseIso(scheduledIso);
        if (ms < 0) {
            scheduleText.setText("لم يُحدَّد موعد النشر بعد");
            scheduleText.setTextColor(CgCfg.MUTED);
        } else {
            scheduleText.setText(CgUpdateModel.arabicDateTime(ms) + "  (" + CgUpdateModel.untilText(ms) + ")");
            scheduleText.setTextColor(CgCfg.TEXT);
        }
    }

    // ========================================================= notifications tab

    private void buildPush() {
        LinearLayout p = panes[TAB_PUSH];
        LinearLayout box = CgUi.vbox(a);
        box.setBackground(CgUi.bg(a, CgCfg.CARD2, 14, CgCfg.STROKE));
        int pad = CgUi.dp(a, 12);
        box.setPadding(pad, pad, pad, pad);
        tPush = CgUi.toggle(a, "إرسال إشعار للمستخدمين عند النشر", false);
        tPush.onChange(new Runnable() {
            @Override
            public void run() {
                refreshPush();
                changed();
            }
        });
        box.addView(tPush);
        TextView h = CgUi.muted(a, "يُرسَل الإشعار بعد نجاح النشر فقط، وعند الجدولة يُرسَل لحظة النشر التلقائي.");
        h.setPadding(0, CgUi.dp(a, 4), 0, 0);
        box.addView(h, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        p.addView(box, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));

        pushWarn = CgUpdateUi.note(a, CgIcons.WARNING,
                "مزوّد الإشعارات غير مُعدّ في الـ Worker. أضف في Cloudflare Secret اسمه FCM_SERVICE_ACCOUNT (ملف حساب خدمة Firebase) أو PUSH_WEBHOOK_URL. سيُنشر التحديث بنجاح لكن الإشعار لن يصل حتى تضيفه.",
                CgCfg.WARN);
        p.addView(pushWarn);

        fPushTitle = CgUpdateUi.field(a, "عنوان الإشعار", "يُستخدم عنوان التحديث إن تُرك فارغاً", CgUpdateUi.K_TEXT, 100);
        fPushBody = CgUpdateUi.field(a, "نص الإشعار", "الإصدار الجديد أصبح متوفراً الآن", CgUpdateUi.K_MULTI, 300);
        fPushTitle.onChange(changedCb);
        fPushBody.onChange(changedCb);
        p.addView(fPushTitle.view);
        p.addView(fPushBody.view);
        refreshPush();
    }

    private void refreshPush() {
        boolean on = tPush.on;
        fPushTitle.setActive(on);
        fPushBody.setActive(on);
        CgUpdateModel.State s = st();
        pushWarn.setVisibility(on && s != null && !s.pushConfigured ? View.VISIBLE : View.GONE);
        String t = fTitle.text();
        fPushTitle.edit.setHint(t.length() > 0 ? t : "تحديث جديد لسيكرام");
        String vn = normName();
        fPushBody.edit.setHint("الإصدار " + (vn.length() > 0 ? vn : "الجديد") + " أصبح متوفراً الآن.");
    }

    // ================================================================ refresh

    /** Hints that depend on server state (called after every history load). */
    void onStateChanged() {
        refreshAll();
    }

    private void refreshAll() {
        CgUpdateModel.State s = st();
        if (s == null) {
            codeHelp.setText("الحد الأدنى المدعوم: أقل Version Code يُسمح له بمتابعة الاستخدام.");
        } else if (s.maxVersionCode > 0) {
            codeHelp.setText("أعلى Version Code منشور حالياً: " + s.maxVersionCode
                    + ". يمكنك نشر أي رقم مختلف عنه (حتى الأقل). تنبيه: الهواتف المثبّت عليها رقم أعلى لن يُعرض عليها هذا التحديث، ولا يمكن تثبيته فوقها بدون حذف التطبيق. الحد الأدنى المدعوم: من يملك إصداراً أقل منه يُجبَر على التحديث.");
        } else {
            codeHelp.setText("لا يوجد إصدار منشور بعد. الحد الأدنى المدعوم: من يملك إصداراً أقل منه يُجبَر على التحديث.");
        }
        boolean maint = s != null && s.maintenanceEnabled;
        maintStatus.setText("وضع الصيانة الآن: " + (maint ? "مفعّل" : "غير مفعّل"));
        maintStatus.setTextColor(maint ? CgCfg.WARN : CgCfg.MUTED);
        refreshPush();
        refreshForceNote();
        refreshSchedule();
        refreshApkInfo();
    }

    // ============================================================ load / reset

    boolean hasContent() {
        return fName.text().length() > 0 || fCode.text().length() > 0 || fTitle.text().length() > 0
                || fDesc.text().length() > 0 || !changelog.isEmpty() || currentUrl().length() > 0
                || upUi != UP_NONE || editingId.length() > 0;
    }

    /** Clears every field and goes back to a blank "new update" form. */
    void reset() {
        cancelUpload();
        clearAllErrors();
        fName.set("");
        fCode.set("");
        fMin.set("");
        fTitle.set("");
        fDesc.set("");
        changelog.clear();
        renderChangelog();
        fUrl.set("");
        checkedUrl = "";
        extResult.removeAllViews();
        apkUrl = "";
        apkSize = 0;
        apkSha = "";
        apkPkg = "";
        apkVc = 0;
        apkVn = "";
        pickedName = "";
        pickedSize = 0;
        upUi = UP_NONE;
        upMessage = "";
        upFatal = false;
        type = CgUpdateModel.OPTIONAL;
        scheduledIso = "";
        tDuring.silent(false);
        tAfter.silent(false);
        tPush.silent(false);
        fPushTitle.set("");
        fPushBody.set("");
        editingId = "";
        editingStatus = "";
        banner.setVisibility(View.GONE);
        setSource(false);
        setMode(MODE_NOW);
        refreshTypes();
        refreshUpload();
        refreshAll();
        showTab(TAB_INFO);
    }

    /** After a draft was saved the form keeps editing that draft. */
    void markDraft(String id) {
        editingId = id == null ? "" : id;
        editingStatus = editingId.length() > 0 ? "draft" : "";
        showBanner("تعديل مسودة محفوظة. التعديلات لا تُنشر قبل أن تختار «نشر الآن» أو «جدولة».");
    }

    private void showBanner(String text) {
        bannerText.setText(text);
        banner.setVisibility(View.VISIBLE);
    }

    void loadFromRelease(JSONObject r, int how) {
        reset();
        CgUpdateModel.State s = st();
        long code = r.optLong("versionCode", 0);
        if (how == LOAD_COPY) {
            long next = Math.max(s == null ? 0 : s.maxVersionCode, code) + 1;
            code = next;
        }
        fName.set(r.optString("versionName", ""));
        fCode.set(code > 0 ? String.valueOf(code) : "");
        long min = r.optLong("minVersionCode", 0);
        if (min > code && code > 0) min = code;
        fMin.set(min > 0 ? String.valueOf(min) : "");
        fTitle.set(r.optString("title", ""));
        fDesc.set(r.optString("description", ""));
        changelog.addAll(CgUpdateModel.changelogOf(r));
        renderChangelog();
        String t = r.optString("updateType", CgUpdateModel.OPTIONAL);
        type = CgUpdateModel.isType(t) ? t : CgUpdateModel.OPTIONAL;
        tPush.silent(r.optBoolean("sendPush", false));
        fPushTitle.set(r.optString("pushTitle", ""));
        fPushBody.set(r.optString("pushBody", ""));
        tDuring.silent(r.optBoolean("maintenanceDuring", false));
        tAfter.silent(r.optBoolean("maintenanceAfter", false) && !r.optBoolean("maintenanceDuring", false));

        if (how != LOAD_COPY) {
            String url = r.optString("downloadUrl", "");
            if (url.length() > 0) {
                String src = r.optString("apkSource", "");
                boolean isR2 = "r2".equals(src) || (src.length() == 0 && url.startsWith(CgCfg.R2));
                if (isR2) {
                    apkUrl = url;
                    apkSize = r.optLong("fileSize", 0);
                    apkSha = r.optString("sha256", "");
                    apkPkg = r.optString("packageName", "");
                    apkVc = r.optLong("apkVersionCode", 0);
                    apkVn = r.optString("apkVersionName", "");
                    pickedName = lastSegment(url);
                    upUi = UP_DONE;
                    setSource(false);
                } else {
                    fUrl.set(url);
                    apkSize = r.optLong("fileSize", 0);
                    setSource(true);
                }
            }
        }
        String status = r.optString("status", "");
        if (how == LOAD_DRAFT || how == LOAD_EDIT) {
            editingId = r.optString("id", "");
            editingStatus = status;
        }
        if (how == LOAD_DRAFT) {
            setMode(MODE_DRAFT);
            showBanner("تعديل مسودة. التعديلات لا تُنشر قبل أن تختار «نشر الآن» أو «جدولة».");
        } else if (how == LOAD_EDIT) {
            if ("scheduled".equals(status)) {
                String iso = r.optString("scheduledAt", "");
                long ms = CgUi.parseIso(iso);
                if (ms > System.currentTimeMillis() + 60000L) {
                    scheduledIso = iso;
                    setMode(MODE_SCHEDULE);
                } else {
                    setMode(MODE_NOW);
                }
                showBanner("تعديل تحديث مجدول. عند الحفظ يُستبدل الموعد السابق.");
            } else if ("stopped".equals(status)) {
                setMode(MODE_NOW);
                showBanner("إعادة نشر إصدار متوقف بنفس الرقم والملف. يمكنك تعديل البيانات قبل النشر.");
            } else {
                setMode(MODE_NOW);
                showBanner("إعادة محاولة تحديث لم يكتمل نشره. راجع البيانات ثم انشر.");
            }
        } else {
            setMode(MODE_NOW);
            showBanner("تم تحميل بيانات الإصدار " + r.optString("versionName", "") + " كنسخة جديدة. رقم الإصدار رُفع تلقائياً؛ ارفع APK الجديد وحدّث اسم الإصدار.");
        }
        refreshTypes();
        refreshUpload();
        refreshAll();
        showTab(TAB_INFO);
        changed();
    }

    // ========================================================== validation

    private void clearAllErrors() {
        fName.clearError();
        fCode.clearError();
        fMin.clearError();
        fTitle.clearError();
        fDesc.clearError();
        fUrl.clearError();
        changelogError.setVisibility(View.GONE);
        apkError.setVisibility(View.GONE);
        scheduleError.setVisibility(View.GONE);
        maintError.setVisibility(View.GONE);
        tabs.clearFlags();
        vFirstTab = -1;
        vFocus = null;
    }

    private void mark(int tab, CgUpdateUi.Field f, TextView plain, String msg) {
        if (f != null) f.error(msg);
        if (plain != null) {
            plain.setText(msg);
            plain.setVisibility(View.VISIBLE);
        }
        tabs.flag(tab, true);
        if (vFirstTab < 0 || tab < vFirstTab) {
            vFirstTab = tab;
            vFocus = f;
        }
    }

    /** Version Code rules that mirror the Worker (the Worker still re-checks everything). */
    private String codeProblem(long code) {
        CgUpdateModel.State s = st();
        if (s == null) return null;
        long max = s.maxVersionCode;
        long liveCode = s.liveCode();
        boolean reactivation = "stopped".equals(editingStatus) && s.live != null && !s.livePublished() && code == liveCode;
        if (reactivation) return null;
        // أي رقم إصدار مقبول (حتى الأقل من المنشور): الأدمن هو المسؤول. الشيء الوحيد الممنوع هو تكرار نفس الرقم المنشور.
        if (code == max) return "رقم الإصدار (" + code + ") منشور بالفعل. استخدم رقماً أكبر من " + max + ".";
        return null;
    }

    /**
     * Marks every problem in red and opens the first tab that has one.
     * @param m MODE_NOW, MODE_SCHEDULE or MODE_DRAFT
     */
    boolean validate(String m) {
        clearAllErrors();
        boolean draft = MODE_DRAFT.equals(m);
        String name = normName();
        String codeText = CgUpdateModel.normalizeDigits(fCode.text());
        String minText = CgUpdateModel.normalizeDigits(fMin.text());
        long code = codeText.length() == 0 ? 0 : CgUpdateModel.parseLong(codeText);
        long min = minText.length() == 0 ? 0 : CgUpdateModel.parseLong(minText);

        // ---- info
        if (name.length() == 0) {
            if (!draft) mark(TAB_INFO, fName, null, "اسم الإصدار مطلوب (مثال: 2.5.0).");
        } else if (!CgUpdateModel.validVersionName(name)) {
            mark(TAB_INFO, fName, null, "اسم الإصدار غير صالح. استخدم أرقاماً وحروفاً ونقاطاً فقط (مثال: 2.5.0).");
        }
        if (codeText.length() == 0) {
            if (!draft) mark(TAB_INFO, fCode, null, "رقم الإصدار (Version Code) مطلوب.");
        } else if (code < 0) {
            mark(TAB_INFO, fCode, null, "رقم الإصدار (Version Code) غير صالح. أدخل رقماً صحيحاً أكبر من صفر.");
        } else if (code == 0) {
            mark(TAB_INFO, fCode, null, "رقم الإصدار (Version Code) لا يمكن أن يكون صفراً.");
        } else if (code > 2100000000L) {
            mark(TAB_INFO, fCode, null, "رقم الإصدار (Version Code) خارج النطاق المسموح.");
        } else if (!draft) {
            String problem = codeProblem(code);
            if (problem != null) mark(TAB_INFO, fCode, null, problem);
        }
        if (minText.length() > 0 && min < 0) {
            mark(TAB_INFO, fMin, null, "الحد الأدنى المدعوم غير صالح. أدخل رقماً صحيحاً أو اتركه 0.");
        } else if (!draft && min > code && code > 0) {
            mark(TAB_INFO, fMin, null, "الحد الأدنى المدعوم (" + min + ") لا يمكن أن يكون أعلى من رقم الإصدار الجديد (" + code + ").");
        }
        if (!draft) {
            if (fTitle.text().length() == 0) mark(TAB_INFO, fTitle, null, "عنوان التحديث مطلوب.");
            if (fDesc.text().length() == 0) mark(TAB_INFO, fDesc, null, "الوصف المختصر مطلوب.");
        }

        // ---- apk
        String url = currentUrl();
        if (uploadRunning()) {
            mark(TAB_APK, null, apkError, "انتظر انتهاء رفع الملف أو ألغِه قبل المتابعة.");
        } else if (!external && upUi == UP_FAILED) {
            mark(TAB_APK, null, apkError, "فشل رفع الملف. أعد المحاولة أو اختر ملفاً آخر.");
        } else if (url.length() == 0) {
            if (!draft) {
                if (external) mark(TAB_APK, fUrl, null, "أدخل رابط ملف APK.");
                else mark(TAB_APK, null, apkError, "ارفع ملف APK أو اختر «رابط خارجي». لا يمكن النشر بدون APK.");
            }
        } else if (external && !url.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            mark(TAB_APK, fUrl, null, "رابط التحميل غير صالح. يجب أن يبدأ بـ https:// ويشير إلى ملف APK.");
        } else if (!draft && infoApplies() && apkVc > 0 && code > 0 && apkVc != code) {
            mark(TAB_INFO, fCode, null, "رقم الإصدار داخل ملف APK (" + apkVc + ") لا يطابق Version Code المكتوب (" + code
                    + "). عدّل الرقم أو ارفع الملف الصحيح.");
        }

        // ---- publish
        if (!draft && CgUpdateModel.FORCE.equals(type)) {
            if (url.length() == 0 && !uploadRunning()) {
                if (external) mark(TAB_APK, fUrl, null, "التحديث الإجباري يتطلب رابط APK صالحاً.");
                else mark(TAB_APK, null, apkError, "التحديث الإجباري يتطلب ملف APK صالحاً.");
            }
            if (min < 1) {
                mark(TAB_INFO, fMin, null, "التحديث الإجباري يحتاج حدّاً أدنى للإصدار (minVersionCode) أكبر من صفر، وإلا فلن يُجبَر أي مستخدم.");
            }
        }
        if (MODE_SCHEDULE.equals(m)) {
            long ms = CgUi.parseIso(scheduledIso);
            CgUpdateModel.State s = st();
            if (ms < 0) {
                mark(TAB_PUBLISH, null, scheduleError, "حدّد تاريخ ووقت النشر المجدول.");
            } else if (ms < System.currentTimeMillis() + 60000L) {
                mark(TAB_PUBLISH, null, scheduleError, "موعد الجدولة يجب أن يكون في المستقبل (بعد دقيقة على الأقل).");
            } else if (s != null && s.scheduled != null && !s.scheduled.optString("id").equals(editingId)) {
                mark(TAB_PUBLISH, null, scheduleError, "يوجد تحديث مجدول بالفعل (" + s.scheduled.optString("versionName")
                        + "). ألغِ جدولته أو عدّله قبل جدولة تحديث آخر.");
            }
        }
        if (tDuring.on && tAfter.on) {
            mark(TAB_PUBLISH, null, maintError, "لا يمكن تفعيل الصيانة وإلغاؤها في نفس عملية النشر. اختر أحد الخيارين.");
        }
        if (draft && !hasContent()) {
            mark(TAB_INFO, fName, null, "المسودة فارغة. اكتب معلومة واحدة على الأقل قبل الحفظ.");
        }

        if (vFirstTab >= 0) {
            showTab(vFirstTab);
            if (vFocus != null) vFocus.edit.requestFocus();
            return false;
        }
        return true;
    }

    /**
     * Shows a Worker error next to the field it belongs to.
     * @return false when the error has no field here (the screen then shows a dialog)
     */
    boolean applyError(String field, String msg) {
        if (field == null || field.length() == 0) return false;
        clearAllErrors();
        if ("versionName".equals(field)) {
            mark(TAB_INFO, fName, null, msg);
        } else if ("versionCode".equals(field)) {
            mark(TAB_INFO, fCode, null, msg);
        } else if ("minVersionCode".equals(field)) {
            mark(TAB_INFO, fMin, null, msg);
        } else if ("title".equals(field)) {
            mark(TAB_INFO, fTitle, null, msg);
        } else if ("description".equals(field)) {
            mark(TAB_INFO, fDesc, null, msg);
        } else if ("changelog".equals(field)) {
            mark(TAB_INFO, null, changelogError, msg);
        } else if ("downloadUrl".equals(field)) {
            if (external) mark(TAB_APK, fUrl, null, msg);
            else mark(TAB_APK, null, apkError, msg);
        } else if ("scheduledAt".equals(field)) {
            mark(TAB_PUBLISH, null, scheduleError, msg);
        } else if ("maintenance".equals(field)) {
            mark(TAB_PUBLISH, null, maintError, msg);
        } else if ("updateType".equals(field)) {
            mark(TAB_PUBLISH, null, scheduleError, msg);
        } else {
            return false;
        }
        showTab(vFirstTab);
        if (vFocus != null) vFocus.edit.requestFocus();
        return true;
    }

    // ================================================================ output

    /** Body for POST /publish-update (mode now / schedule) or /update/draft. Nothing sensitive in it. */
    JSONObject toJson(String m) {
        JSONObject o = new JSONObject();
        if (!MODE_DRAFT.equals(m)) CxJ.put(o, "mode", m);
        String id = editingId;
        boolean useId = id.length() > 0 && (!MODE_DRAFT.equals(m) || "draft".equals(editingStatus));
        if (useId) CxJ.put(o, "id", id);
        String name = normName();
        if (name.length() > 0) CxJ.put(o, "versionName", name);
        long code = CgUpdateModel.parseLong(CgUpdateModel.normalizeDigits(fCode.text()));
        if (code > 0) CxJ.put(o, "versionCode", Long.valueOf(code));
        long min = CgUpdateModel.parseLong(CgUpdateModel.normalizeDigits(fMin.text()));
        CxJ.put(o, "minVersionCode", Long.valueOf(min < 0 ? 0 : min));
        CxJ.put(o, "updateType", type);
        CxJ.put(o, "title", fTitle.text());
        CxJ.put(o, "description", fDesc.text());
        JSONArray log = new JSONArray();
        for (int i = 0; i < changelog.size(); i++) log.put(changelog.get(i));
        CxJ.put(o, "changelog", log);
        String url = currentUrl();
        if (url.length() > 0) {
            CxJ.put(o, "downloadUrl", url);
            if (infoApplies() || (!external && apkUrl.length() > 0)) {
                if (apkSize > 0) CxJ.put(o, "fileSize", Long.valueOf(apkSize));
                if (apkSha.length() > 0) CxJ.put(o, "sha256", apkSha);
                if (apkPkg.length() > 0) CxJ.put(o, "packageName", apkPkg);
                if (apkVc > 0) CxJ.put(o, "apkVersionCode", Long.valueOf(apkVc));
                if (apkVn.length() > 0) CxJ.put(o, "apkVersionName", apkVn);
            }
        }
        CxJ.put(o, "sendPush", Boolean.valueOf(tPush.on));
        if (tPush.on) {
            CxJ.put(o, "pushTitle", fPushTitle.text());
            CxJ.put(o, "pushBody", fPushBody.text());
        }
        CxJ.put(o, "maintenanceDuring", Boolean.valueOf(tDuring.on));
        CxJ.put(o, "maintenanceAfter", Boolean.valueOf(tAfter.on));
        if (MODE_SCHEDULE.equals(m) && scheduledIso.length() > 0) CxJ.put(o, "scheduledAt", scheduledIso);
        return o;
    }

    /** Rows for the final confirmation dialog: {label, value[, "ltr"]}. */
    String[][] summaryRows(String m) {
        ArrayList<String[]> rows = new ArrayList<String[]>();
        String name = normName();
        rows.add(new String[]{"الإصدار", name.length() > 0 ? name : "—", "ltr"});
        rows.add(new String[]{"Version Code", fCode.text().length() > 0 ? CgUpdateModel.normalizeDigits(fCode.text()) : "—", "ltr"});
        long min = CgUpdateModel.parseLong(CgUpdateModel.normalizeDigits(fMin.text()));
        rows.add(new String[]{"الحد الأدنى المدعوم", String.valueOf(min < 0 ? 0 : min), "ltr"});
        rows.add(new String[]{"نوع التحديث", CgUpdateModel.typeLabel(type)});
        rows.add(new String[]{"حجم APK", apkSize > 0 ? CgUi.sizeText(apkSize) : "يقرؤه الـ Worker عند الفحص"});
        rows.add(new String[]{"عدد بنود التغييرات", String.valueOf(changelog.size())});
        rows.add(new String[]{"إشعار للمستخدمين", tPush.on ? "نعم" : "لا"});
        if (MODE_SCHEDULE.equals(m)) {
            long ms = CgUi.parseIso(scheduledIso);
            rows.add(new String[]{"موعد النشر", ms > 0 ? CgUpdateModel.arabicDateTime(ms) : "—"});
        } else {
            rows.add(new String[]{"طريقة النشر", "فوري"});
        }
        if (tDuring.on) rows.add(new String[]{"وضع الصيانة", "يُفعَّل أثناء النشر"});
        if (tAfter.on) rows.add(new String[]{"وضع الصيانة", "يُلغى بعد النشر"});
        return rows.toArray(new String[rows.size()][]);
    }
}
