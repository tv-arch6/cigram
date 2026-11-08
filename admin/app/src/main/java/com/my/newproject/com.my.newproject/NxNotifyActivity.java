package com.my.newproject;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Send a notification to the users: ① write it → ② live preview (phone notification + in-app message) → send.
 * The message is saved as an in-app announcement (what the app already reads from /announcements) and,
 * when the Worker has a push provider (FCM / webhook), also pushed to phones.
 */
public class NxNotifyActivity extends CxBase {

    private String title = "";
    private String body = "";
    private String kind = "info";
    private int showMode;      // 0 once, 1 every open
    private int days = 3;      // 0 = no end
    private boolean push = true;
    private boolean sent;
    private JSONObject result;
    private String fallbackNote = "";

    private TextView pvTitle;
    private TextView pvBody;
    private LinearLayout pvInApp;
    private TextView pvInTitle;
    private TextView pvInBody;
    private ImageView pvInIcon;
    private LinearLayout pvPhone;
    private TextView sendBtn;
    private TextView titleCount;
    private TextView bodyCount;
    private LinearLayout kindRow;
    private LinearLayout daysRow;

    // v1.1: phone-notification (FCM) status from the Worker: GET /admin/push/status
    private LinearLayout pushBox;
    private JSONObject pushStatus;
    private String pushStatusError;
    private boolean pushStatusLoading;

    @Override
    protected String screenTitle() {
        return "إرسال إشعار";
    }

    @Override
    protected void onBuild() {
        initCx(null);
        try {
            bar.view.setBackgroundColor(NxUi.BG);
        } catch (Throwable ignored) { }
        render();
        loadPushStatus();
    }

    private int dp(float v) {
        return CgUi.dp(this, v);
    }

    private void render() {
        page.removeAllViews();
        if (sent) {
            renderResult();
            return;
        }
        pushBox = CgUi.vbox(this);
        pushBox.setBackground(NxUi.surface(this, 20));
        pushBox.setPadding(dp(14), dp(14), dp(14), dp(12));
        fillPushBox();
        page.addView(pushBox, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 0, 0, 10));

        page.addView(NxParts.step(this, 1, "اكتب الإشعار", "عنوان قصير ونص واضح", false, true));
        LinearLayout form = CgUi.vbox(this);
        form.setBackground(NxUi.surface(this, 20));
        form.setPadding(dp(14), dp(14), dp(14), dp(12));

        titleCount = CgUi.text(this, "", 11, NxUi.DIM, false);
        EditText ti = field("العنوان", "مثال: حلقة جديدة من مسلسلك المفضل", false, title, 120, titleCount, new Cb2() {
            @Override
            public void run(String v) {
                title = v;
                refresh();
            }
        });
        form.addView(labeled("العنوان", titleCount));
        form.addView(ti, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 4, 0, 12));
        bodyCount = CgUi.text(this, "", 11, NxUi.DIM, false);
        EditText bi = field("النص", "اكتب ما تريد أن يعرفه المستخدمون…", true, body, 600, bodyCount, new Cb2() {
            @Override
            public void run(String v) {
                body = v;
                refresh();
            }
        });
        form.addView(labeled("النص", bodyCount));
        form.addView(bi, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 4, 0, 14));

        form.addView(CgUi.text(this, "النوع", 12, NxUi.SUB, true));
        kindRow = CgUi.hbox(this);
        form.addView(kindRow, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 6, 0, 0));
        renderKinds();
        page.addView(form);

        LinearLayout opt = CgUi.vbox(this);
        opt.setBackground(NxUi.surface(this, 20));
        opt.setPadding(dp(14), dp(14), dp(14), dp(12));
        opt.addView(CgUi.text(this, "داخل التطبيق", 12, NxUi.SUB, true));
        opt.addView(NxUi.segmented(this, new String[]{"مرة واحدة لكل مستخدم", "عند كل فتح"}, null, showMode, new NxUi.SegCb() {
            @Override
            public void run(int index) {
                showMode = index;
            }
        }), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 6, 0, 12));
        opt.addView(CgUi.text(this, "مدة الظهور", 12, NxUi.SUB, true));
        daysRow = CgUi.hbox(this);
        opt.addView(daysRow, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 6, 0, 12));
        renderDays();
        final CgUi.Toggle tg = CgUi.toggle(this, "إرسال للهواتف أيضاً (إشعار Push)", push);
        tg.onChange(new Runnable() {
            @Override
            public void run() {
                push = tg.on;
                refresh();
            }
        });
        opt.addView(tg);
        opt.addView(CgUi.text(this, "يصل للهواتف فقط إذا كان مزوّد الإشعارات (Firebase) مضبوطاً في الـ Worker. الرسالة داخل التطبيق تُحفظ دائماً.", 11, NxUi.DIM, false),
                NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 2, 0, 0));
        page.addView(opt, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 10, 0, 0));

        page.addView(NxParts.step(this, 2, "المعاينة", "كما يراها المستخدم", false, true));
        page.addView(phonePreview());
        page.addView(inAppPreview(), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 10, 0, 0));

        bottom.removeAllViews();
        bottom.setVisibility(View.VISIBLE);
        sendBtn = NxUi.primary(this, "إرسال لكل المستخدمين");
        Drawable d = NxIcons.icon(CgIcons.SEND, 0xFFFFFFFF);
        d.setBounds(0, 0, dp(20), dp(20));
        sendBtn.setCompoundDrawablesRelative(d, null, null, null);
        sendBtn.setCompoundDrawablePadding(dp(8));
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUi.confirm(NxNotifyActivity.this, "إرسال الإشعار؟", "سيظهر «" + (title.length() > 0 ? title : body) + "» لكل المستخدمين"
                        + (push ? " ويُرسل للهواتف إن أمكن." : " داخل التطبيق."), "إرسال", false, new Runnable() {
                    @Override
                    public void run() {
                        send();
                    }
                });
            }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        bl.setMargins(dp(4), dp(2), dp(4), dp(2));
        bottom.addView(sendBtn, bl);
        refresh();
    }

    interface Cb2 {
        void run(String v);
    }

    private View labeled(String label, TextView counter) {
        LinearLayout r = CgUi.hbox(this);
        r.addView(CgUi.text(this, label, 12, NxUi.SUB, true), new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f));
        CgUi.ltr(counter);
        r.addView(counter);
        return r;
    }

    private EditText field(String label, String hint, boolean multi, String value, final int max, final TextView counter, final Cb2 cb) {
        final EditText e = CgUi.field(this, hint, multi);
        e.setText(value);
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)});
        counter.setText(value.length() + "/" + max);
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                counter.setText(s.length() + "/" + max);
                cb.run(s.toString().trim());
            }
        });
        return e;
    }

    private void renderKinds() {
        kindRow.removeAllViews();
        String[] ids = {"info", "warning", "success"};
        String[] names = {"معلومة", "تنبيه", "خبر سار"};
        int[] icons = {CgIcons.INFO, CgIcons.WARNING, CgIcons.STAR};
        for (int i = 0; i < ids.length; i++) {
            final String id = ids[i];
            int c = kindColor(id);
            boolean on = id.equals(kind);
            LinearLayout t = CgUi.vbox(this);
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            NxUi.pressable(t, on ? NxUi.tint(this, c, 14) : NxUi.flat(this, NxUi.SURF_HI, 14, NxUi.LINE), 14);
            t.setPadding(dp(6), dp(10), dp(6), dp(10));
            t.addView(NxUi.icon(this, NxIcons.icon(icons[i], on ? c : NxUi.SUB), 0), new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView n = CgUi.text(this, names[i], 12.5f, on ? c : NxUi.SUB, true);
            n.setGravity(Gravity.CENTER);
            t.addView(n, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 4, 0, 0));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    kind = id;
                    renderKinds();
                    refresh();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
            lp.setMargins(i == 0 ? 0 : dp(8), 0, 0, 0);
            kindRow.addView(t, lp);
        }
    }

    private void renderDays() {
        daysRow.removeAllViews();
        final int[] vals = {1, 3, 7, 0};
        String[] names = {"يوم", "3 أيام", "أسبوع", "بدون نهاية"};
        for (int i = 0; i < vals.length; i++) {
            final int v = vals[i];
            boolean on = v == days;
            TextView t = CgUi.text(this, names[i], 12.5f, on ? 0xFFFFFFFF : NxUi.SUB, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(4), dp(8), dp(4), dp(8));
            NxUi.pressable(t, on ? NxUi.gradient(this, 0xFF4F6BFF, 0xFF6C8CFF, 12) : NxUi.flat(this, NxUi.SURF_HI, 12, NxUi.LINE), 12);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    days = v;
                    renderDays();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
            lp.setMargins(i == 0 ? 0 : dp(6), 0, 0, 0);
            daysRow.addView(t, lp);
        }
    }

    private static int kindColor(String k) {
        if ("warning".equals(k)) return NxUi.ORANGE;
        if ("success".equals(k)) return NxUi.UP;
        return NxUi.BLUE;
    }

    // ------------------------------------------------------------------ preview

    private View phonePreview() {
        LinearLayout wrap = CgUi.vbox(this);
        wrap.setBackground(NxUi.gradient(this, 0xFF2A1210, 0xFF0A0A0C, 22));
        wrap.setPadding(dp(14), dp(14), dp(14), dp(16));
        TextView clock = CgUi.text(this, new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date()), 26, 0xCCFFFFFF, true);
        clock.setGravity(Gravity.CENTER);
        CgUi.ltr(clock);
        wrap.addView(clock, new LinearLayout.LayoutParams(NxUi.MATCH, NxUi.WRAP));
        pvPhone = CgUi.vbox(this);
        pvPhone.setBackground(CgUi.bg(this, 0xE6F2F4F8, 18, 0));
        pvPhone.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout head = CgUi.hbox(this);
        ImageView app = new ImageView(this);
        try {
            app.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName()));
        } catch (Throwable t) {
            app.setImageDrawable(NxIcons.icon(CgIcons.FILM, NxUi.BLUE));
        }
        head.addView(app, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView an = CgUi.text(this, "Cigram  •  الآن", 11.5f, 0xFF5B6475, true);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(NxUi.WRAP, NxUi.WRAP);
        al.setMargins(dp(6), 0, 0, 0);
        head.addView(an, al);
        pvPhone.addView(head);
        pvTitle = CgUi.text(this, "", 14, 0xFF0A0A0C, true);
        pvTitle.setMaxLines(1);
        pvTitle.setEllipsize(TextUtils.TruncateAt.END);
        pvPhone.addView(pvTitle, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 6, 0, 0));
        pvBody = CgUi.text(this, "", 13, 0xFF374151, false);
        pvBody.setMaxLines(3);
        pvBody.setEllipsize(TextUtils.TruncateAt.END);
        pvPhone.addView(pvBody, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 2, 0, 0));
        wrap.addView(pvPhone, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        TextView cap = CgUi.text(this, "إشعار الهاتف", 11, 0x99FFFFFF, true);
        cap.setGravity(Gravity.CENTER);
        wrap.addView(cap, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 10, 0, 0));
        return wrap;
    }

    private View inAppPreview() {
        LinearLayout wrap = CgUi.vbox(this);
        wrap.setBackground(NxUi.flat(this, 0xFF050506, 22, NxUi.LINE));
        wrap.setPadding(dp(14), dp(14), dp(14), dp(14));
        pvInApp = CgUi.hbox(this);
        pvInApp.setPadding(dp(12), dp(12), dp(12), dp(12));
        pvInIcon = new ImageView(this);
        pvInApp.addView(pvInIcon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        LinearLayout col = CgUi.vbox(this);
        pvInTitle = CgUi.text(this, "", 14, NxUi.TEXT, true);
        col.addView(pvInTitle);
        pvInBody = CgUi.text(this, "", 12.5f, 0xFFD3DBE8, false);
        pvInBody.setLineSpacing(0, 1.12f);
        col.addView(pvInBody, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 3, 0, 0));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        cl.setMargins(dp(10), 0, 0, 0);
        pvInApp.addView(col, cl);
        wrap.addView(pvInApp);
        TextView cap = CgUi.text(this, "رسالة داخل التطبيق", 11, NxUi.DIM, true);
        cap.setGravity(Gravity.CENTER);
        wrap.addView(cap, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 10, 0, 0));
        return wrap;
    }

    private void refresh() {
        if (pvTitle == null) return;
        String t = title.length() > 0 ? title : "عنوان الإشعار";
        String b = body.length() > 0 ? body : "نص الإشعار يظهر هنا…";
        pvTitle.setText(t);
        pvBody.setText(b);
        pvInTitle.setText(t);
        pvInBody.setText(b);
        int c = kindColor(kind);
        pvInApp.setBackground(NxUi.tint(this, c, 16));
        pvInIcon.setImageDrawable(NxIcons.icon("warning".equals(kind) ? CgIcons.WARNING : "success".equals(kind) ? CgIcons.STAR : CgIcons.INFO, c));
        pvPhone.setAlpha(push ? 1f : 0.35f);
        if (sendBtn != null) NxUi.enable(sendBtn, title.length() > 0 || body.length() > 0);
    }

    // --------------------------------------------------------------- phone push status (v1.1)

    private void loadPushStatus() {
        pushStatusLoading = true;
        fillPushBox();
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                return CxApi.get("/admin/push/status");
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                pushStatusLoading = false;
                pushStatus = r;
                pushStatusError = null;
                fillPushBox();
            }

            @Override
            public void fail(CxApi.Failure f) {
                pushStatusLoading = false;
                pushStatus = null;
                pushStatusError = f.is("ROUTE_MISSING")
                        ? "ارفع ملف الـ Worker الجديد (cigram-admin-worker-v2.js) إلى Cloudflare حتى تعمل الإشعارات التلقائية وفحص الحالة."
                        : f.getMessage();
                fillPushBox();
            }
        });
    }

    private void fillPushBox() {
        if (pushBox == null) return;
        pushBox.removeAllViews();
        LinearLayout head = CgUi.hbox(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(NxUi.icon(this, NxIcons.icon(CgIcons.BELL, NxUi.BLUE), 0), new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView ht = CgUi.text(this, "إشعارات الهاتف", 14, NxUi.TEXT, true);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        hl.setMargins(dp(8), 0, 0, 0);
        head.addView(ht, hl);
        TextView again = CgUi.text(this, "تحديث", 12, NxUi.BLUE, true);
        again.setPadding(dp(8), dp(4), dp(8), dp(4));
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadPushStatus();
            }
        });
        head.addView(again);
        pushBox.addView(head);

        if (pushStatusLoading && pushStatus == null) {
            pushBox.addView(CgUi.text(this, "جارِ فحص إعداد الإشعارات…", 12.5f, NxUi.SUB, false),
                    NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 8, 0, 0));
            return;
        }
        if (pushStatus == null) {
            TextView e = CgUi.text(this, pushStatusError == null ? "تعذر فحص الإعداد." : pushStatusError, 12.5f, NxUi.ORANGE, false);
            pushBox.addView(e, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 8, 0, 0));
            return;
        }

        boolean ok = pushStatus.optBoolean("configured", false);
        String line = ok
                ? "مفعّلة ✓ — تصل للهواتف حتى والتطبيق مغلق" + (pushStatus.optString("project_id").length() > 0 ? " (مشروع " + pushStatus.optString("project_id") + ")" : "")
                : CxJ.firstNonEmpty(pushStatus.optString("problem"), "غير مضبوطة في الـ Worker.");
        pushBox.addView(CgUi.text(this, line, 12.5f, ok ? NxUi.UP : NxUi.ORANGE, true),
                NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 8, 0, 0));
        if (!ok) {
            pushBox.addView(CgUi.text(this,
                    "الحل: Firebase Console ← مشروع cigram ← Project settings ← Service accounts ← Generate new private key. "
                            + "ثم Cloudflare ← Workers ← cigram-admin-api ← Settings ← Variables and Secrets ← Add ← النوع Secret، الاسم FCM_SERVICE_ACCOUNT، والقيمة محتوى ملف JSON كاملاً.",
                    11.5f, NxUi.SUB, false), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 4, 0, 0));
        }

        JSONObject st = pushStatus.optJSONObject("settings");
        final CgUi.Toggle auto = CgUi.toggle(this, "إشعار تلقائي عند إضافة فيلم أو مسلسل", st == null || st.optBoolean("auto_content", true));
        auto.onChange(new Runnable() {
            @Override
            public void run() {
                savePushSetting("auto_content", auto.on);
            }
        });
        pushBox.addView(auto, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 8, 0, 0));
        final CgUi.Toggle eps = CgUi.toggle(this, "إشعار الحلقات الجديدة للمشتركين بالمسلسل", st == null || st.optBoolean("auto_episodes", true));
        eps.onChange(new Runnable() {
            @Override
            public void run() {
                savePushSetting("auto_episodes", eps.on);
            }
        });
        pushBox.addView(eps);

        TextView test = NxUi.secondary(this, "إرسال إشعار تجريبي لكل الهواتف");
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendPushTest();
            }
        });
        pushBox.addView(test, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 10, 0, 0));

        org.json.JSONArray log = pushStatus.optJSONArray("log");
        if (log != null && log.length() > 0) {
            pushBox.addView(CgUi.text(this, "آخر الإشعارات", 12, NxUi.SUB, true), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 2));
            for (int i = 0; i < Math.min(6, log.length()); i++) {
                JSONObject e = log.optJSONObject(i);
                if (e == null) continue;
                String status = e.optString("status");
                int color = "sent".equals(status) ? NxUi.UP : ("skipped".equals(status) ? NxUi.SUB : NxUi.ORANGE);
                String text = pushKindLabel(e.optString("kind")) + (e.optString("title").length() > 0 ? ": " + e.optString("title") : "")
                        + " — " + pushStatusLabel(status) + (e.optString("at").length() > 0 ? " · " + CgUi.ago(e.optString("at")) : "");
                TextView row = CgUi.text(this, text, 11.5f, color, false);
                row.setMaxLines(2);
                row.setEllipsize(TextUtils.TruncateAt.END);
                pushBox.addView(row, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 3, 0, 0));
                if (!"sent".equals(status) && e.optString("error").length() > 0) {
                    TextView er = CgUi.text(this, e.optString("error"), 10.5f, NxUi.DIM, false);
                    er.setMaxLines(3);
                    pushBox.addView(er);
                }
            }
        }
    }

    private static String pushKindLabel(String k) {
        if ("new_title".equals(k)) return "محتوى جديد";
        if ("new_episode".equals(k)) return "حلقات جديدة";
        if ("announcement".equals(k)) return "إشعار";
        if ("test".equals(k)) return "تجربة";
        if ("update".equals(k)) return "تحديث";
        return "إشعار";
    }

    private static String pushStatusLabel(String s) {
        if ("sent".equals(s)) return "أُرسل";
        if ("no_provider".equals(s)) return "غير مُعدّ";
        if ("skipped".equals(s)) return "لم يُرسل";
        return "فشل";
    }

    private void savePushSetting(final String key, final boolean value) {
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = new JSONObject();
                b.put(key, value);
                return CxApi.post("/admin/push/settings", b);
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                snack(value ? "تم التفعيل" : "تم الإيقاف", CxUi.GOOD);
                if (pushStatus != null && r != null && r.optJSONObject("settings") != null) {
                    CxJ.put(pushStatus, "settings", r.optJSONObject("settings"));
                }
            }

            @Override
            public void fail(CxApi.Failure f) {
                snack(f.getMessage(), CxUi.ERROR);
                loadPushStatus();
            }
        });
    }

    private void sendPushTest() {
        snack("جارِ الإرسال…", CxUi.INFO);
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                return CxApi.post("/admin/push/test", new JSONObject());
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                JSONObject p = r == null ? null : r.optJSONObject("push");
                boolean sentOk = p != null && "sent".equals(p.optString("status"));
                snack(sentOk ? "أُرسل ✓ — أغلق تطبيق سيكرام وانتظر الإشعار" : CxJ.firstNonEmpty(p == null ? "" : p.optString("error"), "فشل الإرسال"),
                        sentOk ? CxUi.GOOD : CxUi.ERROR);
                loadPushStatus();
            }

            @Override
            public void fail(CxApi.Failure f) {
                snack(f.getMessage(), CxUi.ERROR);
            }
        });
    }

    // --------------------------------------------------------------------- send

    private void send() {
        final JSONObject b = new JSONObject();
        CxJ.put(b, "title", title);
        CxJ.put(b, "body", body);
        CxJ.put(b, "kind", kind);
        CxJ.put(b, "show_mode", showMode == 1 ? "always" : "once");
        CxJ.put(b, "push", push);
        if (days > 0) CxJ.put(b, "end_at", CgUi.toIso(System.currentTimeMillis() + days * 86400000L));
        final CxStages sg = CxStages.show(this, "إرسال الإشعار", new String[]{"حفظ الرسالة", push ? "الإرسال للهواتف" : "تجهيز", "اكتمل"});
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                sg.stage(0, "");
                try {
                    JSONObject r = CxApi.post("/admin/notify", b);
                    sg.stage(1, "");
                    sg.stage(2, "");
                    return r;
                } catch (CxApi.Failure f) {
                    if (!f.is("ROUTE_MISSING")) throw f;
                }
                // older Worker: save the in-app message with the existing route (no push)
                JSONObject ann = new JSONObject();
                ann.put("title", title);
                ann.put("body", body);
                ann.put("kind", kind);
                ann.put("show_mode", showMode == 1 ? "always" : "once");
                if (b.has("end_at")) ann.put("end_at", b.optString("end_at"));
                ann.put("enabled", true);
                JSONObject r = CxApi.post("/admin/announcements/save", new JSONObject().put("announcement", ann));
                JSONObject out = new JSONObject();
                out.put("announcement", r.optJSONObject("announcement"));
                out.put("push", new JSONObject().put("status", push ? "worker_old" : "skipped"));
                out.put("in_app", true);
                sg.stage(2, "");
                return out;
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                sg.dismiss();
                result = r;
                sent = true;
                render();
                scrollToTop();
            }

            @Override
            public void fail(CxApi.Failure f) {
                sg.fail(f.getMessage(), new Runnable() {
                    @Override
                    public void run() {
                        send();
                    }
                });
            }
        });
    }

    private void renderResult() {
        page.addView(NxParts.step(this, 3, "تم الإرسال", title.length() > 0 ? title : body, true, true));
        LinearLayout c = CgUi.vbox(this);
        c.setBackground(NxUi.surface(this, 22));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        JSONObject ann = result == null ? null : result.optJSONObject("announcement");
        boolean inApp = ann != null;
        c.addView(line(inApp ? CgIcons.CHECK : CgIcons.CLOSE, inApp ? NxUi.UP : NxUi.DOWN, "داخل التطبيق",
                inApp ? "محفوظة وتظهر للمستخدمين" + (days > 0 ? " لمدة " + (days == 1 ? "يوم" : days == 7 ? "أسبوع" : days + " أيام") : "") : "لم تُحفظ"));
        JSONObject p = result == null ? null : result.optJSONObject("push");
        String ps = p == null ? "skipped" : p.optString("status", "skipped");
        int pc;
        String pt;
        int pi;
        if ("sent".equals(ps)) {
            pc = NxUi.UP;
            pi = CgIcons.CHECK;
            pt = "أُرسل للهواتف" + (p.optString("topic").length() > 0 ? " (" + p.optString("topic") + ")" : "");
        } else if ("skipped".equals(ps)) {
            pc = NxUi.SUB;
            pi = NxIcons.FLAT;
            pt = "لم يُطلب";
        } else if ("worker_old".equals(ps)) {
            pc = NxUi.ORANGE;
            pi = CgIcons.WARNING;
            pt = "يحتاج رفع النسخة الجديدة من الـ Worker";
        } else if ("no_provider".equals(ps)) {
            pc = NxUi.ORANGE;
            pi = CgIcons.WARNING;
            pt = "مزوّد الإشعارات غير مضبوط في الـ Worker (FCM_SERVICE_ACCOUNT)";
        } else {
            pc = NxUi.DOWN;
            pi = CgIcons.CLOSE;
            pt = p == null ? "فشل" : CxJ.firstNonEmpty(p.optString("error"), "فشل الإرسال");
        }
        c.addView(line(pi, pc, "إشعار الهاتف", pt), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 10, 0, 0));
        page.addView(c);
        NxUi.enter(c, 0);

        LinearLayout row = CgUi.hbox(this);
        TextView again = NxUi.secondary(this, "إشعار جديد");
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                title = "";
                body = "";
                sent = false;
                result = null;
                render();
            }
        });
        row.addView(again, new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f));
        TextView list = NxUi.secondary(this, "الإشعارات المرسلة");
        list.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(NxNotifyActivity.this, CgAnnouncementsActivity.class));
            }
        });
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        ll.setMargins(dp(8), 0, 0, 0);
        row.addView(list, ll);
        page.addView(row, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));

        bottom.removeAllViews();
        TextView done = NxUi.secondary(this, "تم");
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        bl.setMargins(dp(4), dp(2), dp(4), dp(2));
        bottom.addView(done, bl);
    }

    private View line(int icon, int color, String k, String v) {
        LinearLayout r = CgUi.hbox(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(NxUi.tint(this, color, 14));
        r.setPadding(dp(12), dp(10), dp(12), dp(10));
        r.addView(NxUi.icon(this, NxIcons.icon(icon, color), 0), new LinearLayout.LayoutParams(dp(20), dp(20)));
        LinearLayout col = CgUi.vbox(this);
        col.addView(CgUi.text(this, k, 13.5f, NxUi.TEXT, true));
        TextView t = CgUi.text(this, v, 12, NxUi.SUB, false);
        t.setMaxLines(3);
        col.addView(t);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        cl.setMargins(dp(10), 0, 0, 0);
        r.addView(col, cl);
        return r;
    }
}
