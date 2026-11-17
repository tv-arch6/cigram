package com.my.newproject;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Dialogs of the update center: preview, final publish confirmation, success, release details,
 * emergency double confirmation. All are custom dark sheets (RTL, airo font, no emoji).
 */
final class CgUpdateDialogs {

    private CgUpdateDialogs() {}

    private static final ArrayList<Dialog> OPEN = new ArrayList<Dialog>();

    /** Called from onDestroy so no dialog window is leaked. */
    static void closeAll() {
        ArrayList<Dialog> copy = new ArrayList<Dialog>(OPEN);
        OPEN.clear();
        for (int i = 0; i < copy.size(); i++) {
            try {
                copy.get(i).dismiss();
            } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------- sheet

    static final class Sheet {
        final Activity a;
        final Dialog dialog;
        final LinearLayout body;
        private final LinearLayout card;
        private final TextView titleView;

        Sheet(Activity act, String title, int iconKind, int color, boolean bottom) {
            a = act;
            dialog = new Dialog(a);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            FrameLayout wrap = new FrameLayout(a);
            int side = CgUi.dp(a, 12);
            wrap.setPadding(side, CgUi.dp(a, 28), side, side);

            card = CgUi.vbox(a);
            card.setBackground(CgUi.bg(a, CgCfg.CARD, 22, CgCfg.STROKE));
            int pad = CgUi.dp(a, 16);
            card.setPadding(pad, pad, pad, pad);

            LinearLayout head = CgUi.hbox(a);
            FrameLayout ic = CgUpdateUi.iconCircle(a, iconKind, color, 38, 21);
            LinearLayout.LayoutParams icp = new LinearLayout.LayoutParams(CgUi.dp(a, 38), CgUi.dp(a, 38));
            icp.setMargins(0, 0, CgUi.dp(a, 10), 0);
            head.addView(ic, icp);
            titleView = CgUi.text(a, title, 16.5f, CgCfg.TEXT, true);
            head.addView(titleView, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            FrameLayout close = new FrameLayout(a);
            close.setClickable(true);
            close.addView(CgUpdateUi.icon(a, CgIcons.CLOSE, CgCfg.MUTED, 20),
                    new FrameLayout.LayoutParams(CgUi.dp(a, 20), CgUi.dp(a, 20), Gravity.CENTER));
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                }
            });
            head.addView(close, new LinearLayout.LayoutParams(CgUi.dp(a, 40), CgUi.dp(a, 40)));
            card.addView(head, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

            int max = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.6f);
            CgUpdateUi.MaxHeightScroll sv = new CgUpdateUi.MaxHeightScroll(a, max);
            body = CgUi.vbox(a);
            body.setPadding(0, CgUi.dp(a, 12), 0, CgUi.dp(a, 4));
            sv.addView(body, new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            card.addView(sv, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

            wrap.addView(card, new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP, bottom ? Gravity.BOTTOM : Gravity.CENTER));
            dialog.setContentView(wrap);
            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.setLayout(CgUi.MATCH, CgUi.WRAP);
                w.setGravity(bottom ? Gravity.BOTTOM : Gravity.CENTER);
                w.setDimAmount(0.68f);
                try {
                    w.getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                } catch (Exception ignored) { }
            }
            dialog.setCanceledOnTouchOutside(true);
            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
                    OPEN.remove(dialog);
                }
            });
        }

        /** Adds a row of buttons (or a stack) under the content and returns it. */
        LinearLayout footer(boolean vertical) {
            LinearLayout row = vertical ? CgUi.vbox(a) : CgUi.hbox(a);
            row.setPadding(0, CgUi.dp(a, 8), 0, 0);
            card.addView(row, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            return row;
        }

        /** closes=true dismisses the sheet before running the action. */
        CgUpdateUi.Btn add(LinearLayout row, boolean vertical, String label, int iconKind, int style,
                           final boolean closes, final Runnable action) {
            CgUpdateUi.Btn b = CgUpdateUi.btn(a, label, iconKind, style);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (closes) dismiss();
                    if (action != null) action.run();
                }
            });
            LinearLayout.LayoutParams lp;
            if (vertical) {
                lp = new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP);
                lp.setMargins(0, CgUi.dp(a, 6), 0, 0);
            } else {
                lp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
                lp.setMargins(CgUi.dp(a, 4), 0, CgUi.dp(a, 4), 0);
            }
            row.addView(b, lp);
            return b;
        }

        void show() {
            if (a.isFinishing()) return;
            OPEN.add(dialog);
            try {
                dialog.show();
            } catch (Exception e) {
                OPEN.remove(dialog);
            }
        }

        void dismiss() {
            try {
                dialog.dismiss();
            } catch (Exception ignored) { }
        }
    }

    // ----------------------------------------------------------------- generic

    static TextView message(Activity a, String text) {
        TextView t = CgUi.text(a, text, 13.5f, CgCfg.MUTED, false);
        t.setLineSpacing(0f, 1.15f);
        t.setPadding(0, 0, 0, CgUi.dp(a, 8));
        return t;
    }

    /**
     * Confirmation with optional detail rows ({label, value}). The confirm button comes first
     * (right side in RTL), cancel second.
     */
    static void confirm(Activity a, String title, int iconKind, int color, String msg, String[][] rows,
                        String yes, int yesStyle, final Runnable onYes) {
        Sheet s = new Sheet(a, title, iconKind, color, false);
        if (msg != null && msg.length() > 0) s.body.addView(message(a, msg));
        if (rows != null) {
            LinearLayout box = CgUi.vbox(a);
            box.setBackground(CgUi.bg(a, CgCfg.CARD2, 14, CgCfg.STROKE));
            box.setPadding(CgUi.dp(a, 12), CgUi.dp(a, 6), CgUi.dp(a, 12), CgUi.dp(a, 6));
            for (int i = 0; i < rows.length; i++) {
                boolean ltr = rows[i].length > 2 && "ltr".equals(rows[i][2]);
                box.addView(CgUpdateUi.kv(a, rows[i][0], rows[i][1], CgCfg.TEXT, ltr));
                if (i < rows.length - 1) {
                    View d = new View(a);
                    d.setBackgroundColor(CgUi.alpha(CgCfg.STROKE, 0x88));
                    box.addView(d, new LinearLayout.LayoutParams(CgUi.MATCH, 1));
                }
            }
            s.body.addView(box, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }
        LinearLayout f = s.footer(false);
        s.add(f, false, yes, -1, yesStyle, true, onYes);
        s.add(f, false, "إلغاء", -1, 1, true, null);
        s.show();
    }

    /** Two chained confirmations for dangerous emergency actions. */
    static void doubleConfirm(final Activity a, String title, String msg, final String finalMsg, final String yes,
                              final Runnable onYes) {
        final String t = title;
        confirm(a, title, CgIcons.WARNING, CgCfg.BAD, msg, null, "متابعة", 2, new Runnable() {
            @Override
            public void run() {
                confirm(a, "تأكيد نهائي", CgIcons.STOP, CgCfg.BAD,
                        finalMsg + "\n\nهذا هو التأكيد الأخير على: " + t + ".", null, yes, 2, onYes);
            }
        });
    }

    static void error(Activity a, String title, String msg) {
        Sheet s = new Sheet(a, title, CgIcons.WARNING, CgCfg.BAD, false);
        s.body.addView(message(a, msg));
        LinearLayout f = s.footer(false);
        s.add(f, false, "حسناً", -1, 1, true, null);
        s.show();
    }

    static void info(Activity a, String title, int iconKind, int color, String msg) {
        Sheet s = new Sheet(a, title, iconKind, color, false);
        s.body.addView(message(a, msg));
        LinearLayout f = s.footer(false);
        s.add(f, false, "حسناً", -1, 1, true, null);
        s.show();
    }

    /** Asks for a positive integer (e.g. a Version Code). Errors are shown inline and keep the sheet open. */
    static void numberPrompt(final Activity a, String title, int iconKind, int color, String msg, String label,
                             String hint, final CgUi.Callback<Long> cb) {
        final Sheet s = new Sheet(a, title, iconKind, color, false);
        if (msg != null && msg.length() > 0) s.body.addView(message(a, msg));
        final CgUpdateUi.Field f = CgUpdateUi.field(a, label, hint, CgUpdateUi.K_NUMBER, 10);
        s.body.addView(f.view);
        LinearLayout row = s.footer(false);
        s.add(row, false, "متابعة", -1, 0, false, new Runnable() {
            @Override
            public void run() {
                long v = CgUpdateModel.parseLong(CgUpdateModel.normalizeDigits(f.text()));
                if (v < 1) {
                    f.error("أدخل رقماً صحيحاً أكبر من صفر.");
                    return;
                }
                s.dismiss();
                cb.run(Long.valueOf(v));
            }
        });
        s.add(row, false, "إلغاء", -1, 1, true, null);
        s.show();
    }

    // ----------------------------------------------------------------- preview

    private static String str(JSONObject o, String key) {
        return o == null ? "" : o.optString(key, "").trim();
    }

    /** What the user will see in the app, built from the real form data. Buttons do nothing here. */
    static void preview(final Activity a, JSONObject form) {
        final String type = CgUpdateModel.isType(str(form, "updateType")) ? str(form, "updateType") : CgUpdateModel.OPTIONAL;
        int color = CgUpdateModel.typeColor(type);
        Sheet s = new Sheet(a, "معاينة التحديث", CgIcons.EYE, CgCfg.ACCENT, true);

        TextView cap = CgUi.muted(a, "هكذا ستظهر نافذة التحديث للمستخدم بالبيانات التي كتبتها الآن:");
        cap.setPadding(0, 0, 0, CgUi.dp(a, 8));
        s.body.addView(cap);

        // --- the app dialog
        LinearLayout mock = CgUi.vbox(a);
        mock.setBackground(CgUi.bg(a, CgCfg.PAGE, 18, CgUi.alpha(color, 0xAA)));
        int p = CgUi.dp(a, 14);
        mock.setPadding(p, p, p, p);

        LinearLayout top = CgUi.hbox(a);
        FrameLayout ic = CgUpdateUi.iconCircle(a, CgIcons.UPDATE, color, 40, 22);
        LinearLayout.LayoutParams icp = new LinearLayout.LayoutParams(CgUi.dp(a, 40), CgUi.dp(a, 40));
        icp.setMargins(0, 0, CgUi.dp(a, 10), 0);
        top.addView(ic, icp);
        LinearLayout titles = CgUi.vbox(a);
        String title = str(form, "title");
        TextView tv = CgUi.text(a, title.length() > 0 ? title : "(لم تكتب عنواناً بعد)", 15.5f,
                title.length() > 0 ? CgCfg.TEXT : CgCfg.MUTED, true);
        titles.addView(tv, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        String vn = str(form, "versionName");
        TextView ver = CgUi.text(a, "الإصدار " + (vn.length() > 0 ? vn : "—"), 12.5f, CgCfg.MUTED, false);
        titles.addView(ver, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        top.addView(titles, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        top.addView(CgUi.badge(a, CgUpdateModel.typeShort(type), color), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        mock.addView(top, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        String desc = str(form, "description");
        TextView d = CgUi.text(a, desc.length() > 0 ? desc : "(لم تكتب وصفاً بعد)", 13.5f,
                desc.length() > 0 ? CgCfg.TEXT : CgCfg.MUTED, false);
        d.setLineSpacing(0f, 1.15f);
        d.setPadding(0, CgUi.dp(a, 12), 0, 0);
        mock.addView(d, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        JSONArray log = form.optJSONArray("changelog");
        if (log != null && log.length() > 0) {
            TextView h = CgUi.text(a, "ما الجديد", 13, CgCfg.ACCENT, true);
            h.setPadding(0, CgUi.dp(a, 12), 0, CgUi.dp(a, 4));
            mock.addView(h, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            for (int i = 0; i < log.length(); i++) {
                String line = log.optString(i, "").trim();
                if (line.length() == 0) continue;
                LinearLayout r = CgUi.hbox(a);
                r.setGravity(Gravity.TOP);
                View dot = CgUpdateUi.dot(a, color, 6);
                LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(CgUi.dp(a, 6), CgUi.dp(a, 6));
                dp.setMargins(0, CgUi.dp(a, 8), CgUi.dp(a, 8), 0);
                r.addView(dot, dp);
                r.addView(CgUi.text(a, line, 13, CgCfg.TEXT, false), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
                mock.addView(r, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            }
        }

        long size = form.optLong("fileSize", 0);
        if (size > 0) {
            TextView sz = CgUi.muted(a, "حجم التحديث: " + CgUi.sizeText(size));
            sz.setPadding(0, CgUi.dp(a, 10), 0, 0);
            mock.addView(sz, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }

        final boolean force = CgUpdateModel.FORCE.equals(type);
        LinearLayout actions = CgUi.hbox(a);
        actions.setPadding(0, CgUi.dp(a, 14), 0, 0);
        Runnable noop = new Runnable() {
            @Override
            public void run() {
                CgUi.toast(a, "هذه معاينة فقط، الأزرار لا تنفّذ شيئاً.");
            }
        };
        CgUpdateUi.Btn now = CgUpdateUi.btn(a, force ? "تحديث التطبيق" : "تحديث الآن", CgIcons.UPDATE, 0);
        LinearLayout.LayoutParams nowLp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        nowLp.setMargins(CgUi.dp(a, 4), 0, CgUi.dp(a, 4), 0);
        actions.addView(now, nowLp);
        bindPreview(now, noop);
        if (!force) {
            CgUpdateUi.Btn later = CgUpdateUi.btn(a, "لاحقاً", -1, 1);
            LinearLayout.LayoutParams laterLp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
            laterLp.setMargins(CgUi.dp(a, 4), 0, CgUi.dp(a, 4), 0);
            actions.addView(later, laterLp);
            bindPreview(later, noop);
        }
        mock.addView(actions, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        if (force) {
            TextView n = CgUi.text(a, "تحديث إجباري: لا يظهر زر «لاحقاً» ولا يمكن إغلاق النافذة.", 12, CgCfg.BAD, false);
            n.setPadding(0, CgUi.dp(a, 8), 0, 0);
            mock.addView(n, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }
        s.body.addView(mock, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        // --- the push notification
        if (form.optBoolean("sendPush", false)) {
            TextView h2 = CgUi.text(a, "الإشعار الذي سيصل للمستخدمين", 13, CgCfg.MUTED, true);
            h2.setPadding(0, CgUi.dp(a, 14), 0, CgUi.dp(a, 6));
            s.body.addView(h2);
            LinearLayout n = CgUi.hbox(a);
            n.setBackground(CgUi.bg(a, CgCfg.CARD2, 14, CgCfg.STROKE));
            int np = CgUi.dp(a, 12);
            n.setPadding(np, np, np, np);
            FrameLayout nic = CgUpdateUi.iconCircle(a, CgIcons.BELL, CgCfg.ACCENT, 34, 19);
            LinearLayout.LayoutParams nicp = new LinearLayout.LayoutParams(CgUi.dp(a, 34), CgUi.dp(a, 34));
            nicp.setMargins(0, 0, CgUi.dp(a, 10), 0);
            n.addView(nic, nicp);
            LinearLayout nt = CgUi.vbox(a);
            String pt = str(form, "pushTitle");
            String pb = str(form, "pushBody");
            nt.addView(CgUi.text(a, pt.length() > 0 ? pt : (title.length() > 0 ? title : "تحديث جديد لسيكرام"), 14, CgCfg.TEXT, true));
            nt.addView(CgUi.text(a, pb.length() > 0 ? pb : "الإصدار " + (vn.length() > 0 ? vn : "الجديد") + " أصبح متوفراً الآن.",
                    12.5f, CgCfg.MUTED, false));
            n.addView(nt, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            s.body.addView(n, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }

        LinearLayout f = s.footer(false);
        s.add(f, false, "إغلاق المعاينة", -1, 1, true, null);
        s.show();
    }

    private static void bindPreview(CgUpdateUi.Btn b, final Runnable r) {
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.run();
            }
        });
    }

    // ----------------------------------------------------------------- success

    /** data = the Worker's publish response data. */
    static void success(Activity a, JSONObject data, final Runnable onClose, final Runnable onResendPush) {
        JSONObject rel = data.optJSONObject("release");
        if (rel == null) rel = new JSONObject();
        boolean scheduled = "schedule".equals(data.optString("mode"));
        final Sheet s = new Sheet(a, scheduled ? "تمت جدولة التحديث" : "تم نشر التحديث بنجاح", CgIcons.CHECK, CgCfg.GOOD, false);
        s.dialog.setCancelable(false);
        s.dialog.setCanceledOnTouchOutside(false);

        String type = rel.optString("updateType", CgUpdateModel.OPTIONAL);
        LinearLayout box = CgUi.vbox(a);
        box.setBackground(CgUi.bg(a, CgCfg.CARD2, 14, CgCfg.STROKE));
        box.setPadding(CgUi.dp(a, 12), CgUi.dp(a, 6), CgUi.dp(a, 12), CgUi.dp(a, 6));
        box.addView(CgUpdateUi.kv(a, "الإصدار", rel.optString("versionName", "—"), CgCfg.TEXT, true));
        box.addView(CgUpdateUi.kv(a, "Version Code", String.valueOf(rel.optLong("versionCode", 0)), CgCfg.TEXT, true));
        box.addView(CgUpdateUi.kv(a, "النوع", CgUpdateModel.typeLabel(type), CgUpdateModel.typeColor(type), false));
        if (scheduled) {
            box.addView(CgUpdateUi.kv(a, "موعد النشر", CgUpdateModel.dateTimeOfIso(rel.optString("scheduledAt")), CgCfg.TEXT, false));
        } else {
            box.addView(CgUpdateUi.kv(a, "تاريخ النشر", CgUpdateModel.dateTimeOfIso(rel.optString("publishedAt")), CgCfg.TEXT, false));
        }
        long size = rel.optLong("fileSize", 0);
        box.addView(CgUpdateUi.kv(a, "حجم APK", CgUpdateModel.sizeText(size), CgCfg.TEXT, true));
        boolean wantsPush = rel.optBoolean("sendPush", false);
        if (scheduled) {
            box.addView(CgUpdateUi.kv(a, "الإشعار", wantsPush ? "سيُرسل عند النشر" : "غير مطلوب", wantsPush ? CgCfg.ACCENT : CgCfg.MUTED, false));
        } else {
            box.addView(CgUpdateUi.kv(a, "الإشعار", CgUpdateModel.pushLabel(rel), CgUpdateModel.pushColor(rel), false));
        }
        s.body.addView(box, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        JSONArray warnings = data.optJSONArray("warnings");
        for (int i = 0; warnings != null && i < warnings.length(); i++) {
            String w = warnings.optString(i, "").trim();
            if (w.length() > 0) s.body.addView(CgUpdateUi.note(a, CgIcons.WARNING, w, CgCfg.WARN));
        }
        if (scheduled) {
            s.body.addView(CgUpdateUi.note(a, CgIcons.INFO,
                    "سيُنشر التحديث تلقائياً في الموعد المحدد عبر Cron Trigger في الـ Worker (يعمل كل دقيقة).", CgCfg.ACCENT));
        }

        LinearLayout f = s.footer(true);
        String ps = CgUpdateModel.pushStatus(rel);
        if (!scheduled && wantsPush && ("failed".equals(ps) || "no_provider".equals(ps)) && onResendPush != null) {
            s.add(f, true, "إعادة إرسال الإشعار", CgIcons.BELL, 1, true, onResendPush);
        }
        s.add(f, true, "تم", CgIcons.CHECK, 0, true, onClose);
        s.show();
    }

    // ------------------------------------------------------------------ details

    interface DetailActions {
        void useData(JSONObject release);

        void copyUrl(String url);

        void cancelSchedule(JSONObject release);

        void editDraft(JSONObject release);

        /** Opens a scheduled / failed / stopped release in the form to change it or publish it again. */
        void edit(JSONObject release);

        void deleteDraft(JSONObject release);

        void resendPush(JSONObject release);
    }

    static void releaseDetail(Activity a, final JSONObject r, boolean isLive, final DetailActions act) {
        final String status = r.optString("status", "");
        String type = r.optString("updateType", CgUpdateModel.OPTIONAL);
        int color = CgUpdateModel.releaseStatusColor(status);
        String vn = r.optString("versionName", "");
        Sheet s = new Sheet(a, "الإصدار " + (vn.length() > 0 ? vn : "بدون اسم"), CgIcons.HISTORY, color, true);

        LinearLayout badges = CgUi.hbox(a);
        badges.addView(CgUi.badge(a, CgUpdateModel.releaseStatusLabel(status), color), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        bp.setMargins(CgUi.dp(a, 6), 0, 0, 0);
        badges.addView(CgUi.badge(a, CgUpdateModel.typeShort(type), CgUpdateModel.typeColor(type)), bp);
        if (isLive) {
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            lp2.setMargins(CgUi.dp(a, 6), 0, 0, 0);
            badges.addView(CgUi.badge(a, "الحالي", CgCfg.GOOD), lp2);
        }
        badges.setPadding(0, 0, 0, CgUi.dp(a, 8));
        s.body.addView(badges, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        LinearLayout box = CgUi.vbox(a);
        box.setBackground(CgUi.bg(a, CgCfg.CARD2, 14, CgCfg.STROKE));
        box.setPadding(CgUi.dp(a, 12), CgUi.dp(a, 6), CgUi.dp(a, 12), CgUi.dp(a, 6));
        box.addView(CgUpdateUi.kv(a, "Version Code", String.valueOf(r.optLong("versionCode", 0)), CgCfg.TEXT, true));
        box.addView(CgUpdateUi.kv(a, "الحد الأدنى المسموح", String.valueOf(r.optLong("minVersionCode", 0)), CgCfg.TEXT, true));
        String title = r.optString("title", "");
        if (title.length() > 0) box.addView(CgUpdateUi.kv(a, "العنوان", title, CgCfg.TEXT, false));
        box.addView(CgUpdateUi.kv(a, "حجم APK", CgUpdateModel.sizeText(r.optLong("fileSize", 0)), CgCfg.TEXT, true));
        String pkg = r.optString("packageName", "");
        if (pkg.length() > 0) box.addView(CgUpdateUi.kv(a, "الحزمة", pkg, CgCfg.TEXT, true));
        String sha = r.optString("sha256", "");
        if (sha.length() >= 16) box.addView(CgUpdateUi.kv(a, "SHA-256", sha.substring(0, 16) + "…", CgCfg.TEXT, true));
        String src = r.optString("apkSource", "");
        if (src.length() > 0) box.addView(CgUpdateUi.kv(a, "مصدر APK", "r2".equals(src) ? "مرفوع إلى R2" : "رابط خارجي", CgCfg.TEXT, false));
        String published = r.optString("publishedAt", "");
        if (published.length() > 0) box.addView(CgUpdateUi.kv(a, "تاريخ النشر", CgUpdateModel.dateTimeOfIso(published), CgCfg.TEXT, false));
        String scheduledAt = r.optString("scheduledAt", "");
        if ("scheduled".equals(status) && scheduledAt.length() > 0) {
            box.addView(CgUpdateUi.kv(a, "موعد النشر", CgUpdateModel.dateTimeOfIso(scheduledAt), CgUpdateModel.BLUE, false));
        }
        String stopped = r.optString("stoppedAt", "");
        if (stopped.length() > 0 && "stopped".equals(status)) {
            box.addView(CgUpdateUi.kv(a, "تاريخ الإيقاف", CgUpdateModel.dateTimeOfIso(stopped), CgCfg.TEXT, false));
        }
        box.addView(CgUpdateUi.kv(a, "الإشعار", CgUpdateModel.pushLabel(r), CgUpdateModel.pushColor(r), false));
        if (r.optBoolean("maintenanceDuring", false)) box.addView(CgUpdateUi.kv(a, "وضع الصيانة", "يُفعّل أثناء التحديث", CgCfg.WARN, false));
        if (r.optBoolean("maintenanceAfter", false)) box.addView(CgUpdateUi.kv(a, "وضع الصيانة", "يُلغى بعد النشر", CgCfg.WARN, false));
        s.body.addView(box, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        String desc = r.optString("description", "");
        if (desc.length() > 0) {
            TextView h = CgUi.text(a, "الوصف", 12.5f, CgCfg.MUTED, true);
            h.setPadding(CgUi.dp(a, 4), CgUi.dp(a, 12), 0, CgUi.dp(a, 4));
            s.body.addView(h);
            s.body.addView(CgUi.text(a, desc, 13.5f, CgCfg.TEXT, false));
        }
        ArrayList<String> log = CgUpdateModel.changelogOf(r);
        if (!log.isEmpty()) {
            TextView h = CgUi.text(a, "ما الجديد (" + log.size() + ")", 12.5f, CgCfg.MUTED, true);
            h.setPadding(CgUi.dp(a, 4), CgUi.dp(a, 12), 0, CgUi.dp(a, 4));
            s.body.addView(h);
            for (int i = 0; i < log.size(); i++) {
                TextView line = CgUi.text(a, "•  " + log.get(i), 13, CgCfg.TEXT, false);
                line.setPadding(0, CgUi.dp(a, 2), 0, CgUi.dp(a, 2));
                s.body.addView(line);
            }
        }
        final String url = r.optString("downloadUrl", "");
        if (url.length() > 0) {
            TextView h = CgUi.text(a, "رابط APK", 12.5f, CgCfg.MUTED, true);
            h.setPadding(CgUi.dp(a, 4), CgUi.dp(a, 12), 0, CgUi.dp(a, 4));
            s.body.addView(h);
            TextView u = CgUi.text(a, url, 12, CgCfg.ACCENT, false);
            CgUi.ltr(u);
            u.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            s.body.addView(u);
        }

        LinearLayout f = s.footer(true);
        if ("draft".equals(status)) {
            s.add(f, true, "فتح للتعديل", CgIcons.EDIT, 0, true, new Runnable() {
                @Override
                public void run() {
                    act.editDraft(r);
                }
            });
            s.add(f, true, "حذف المسودة", CgIcons.TRASH, 2, true, new Runnable() {
                @Override
                public void run() {
                    act.deleteDraft(r);
                }
            });
        } else {
            s.add(f, true, "استخدام هذه البيانات", CgIcons.COPY, 0, true, new Runnable() {
                @Override
                public void run() {
                    act.useData(r);
                }
            });
            if ("scheduled".equals(status)) {
                s.add(f, true, "تعديل الجدولة", CgIcons.EDIT, 1, true, new Runnable() {
                    @Override
                    public void run() {
                        act.edit(r);
                    }
                });
                s.add(f, true, "إلغاء الجدولة", CgIcons.CLOSE, 2, true, new Runnable() {
                    @Override
                    public void run() {
                        act.cancelSchedule(r);
                    }
                });
            } else if ("failed".equals(status)) {
                s.add(f, true, "فتح لإعادة المحاولة", CgIcons.REFRESH, 1, true, new Runnable() {
                    @Override
                    public void run() {
                        act.edit(r);
                    }
                });
                s.add(f, true, "تحويله إلى مسودة", CgIcons.DRAFT, 2, true, new Runnable() {
                    @Override
                    public void run() {
                        act.cancelSchedule(r);
                    }
                });
            } else if ("stopped".equals(status) && isLive) {
                s.add(f, true, "إعادة نشر هذا الإصدار", CgIcons.PUBLISH, 1, true, new Runnable() {
                    @Override
                    public void run() {
                        act.edit(r);
                    }
                });
            }
            String ps = CgUpdateModel.pushStatus(r);
            if ("published".equals(status) && r.optBoolean("sendPush", false) && ("failed".equals(ps) || "no_provider".equals(ps))) {
                s.add(f, true, "إعادة إرسال الإشعار", CgIcons.BELL, 1, true, new Runnable() {
                    @Override
                    public void run() {
                        act.resendPush(r);
                    }
                });
            }
        }
        if (url.length() > 0) {
            s.add(f, true, "نسخ رابط APK", CgIcons.LINK, 1, false, new Runnable() {
                @Override
                public void run() {
                    act.copyUrl(url);
                }
            });
        }
        s.add(f, true, "إغلاق", -1, 3, true, null);
        s.show();
    }
}
