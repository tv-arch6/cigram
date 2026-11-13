package com.my.newproject;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Cigram Plus codes: make a ready activation code (3 months for the first subscription, or 6 months), with its own settings
 * (customer name, WhatsApp number, how many days the code stays valid), and send it to the customer on WhatsApp in one tap.
 * Also: sales / active-user numbers, and a manual grant / revoke by account id.
 *
 * Talks to the Plus worker (plus-worker/worker.js) with the header X-Admin-Key. By default the key is the same admin token this app
 * already uses (set the worker secret ADMIN_KEY to that same value); "تغيير مفتاح Plus" stores a different one on this phone.
 */
public class PlusCodesActivity extends CgBase {

    static final String PLUS_API = "https://cigram-plus-api.wwq-mixtv.workers.dev";
    private static final String KEY_PREF = "plus_admin_key";

    // plans (the worker has the same table; this is only for the screen and the message)
    private static final String[] PLAN_KEYS = {"intro", "standard"};
    private static final String[] PLAN_LABELS = {"٣ أشهر · ١٬٥٠٠ د.ع (أول اشتراك)", "٦ أشهر · ١٠٬٠٠٠ د.ع"};
    private static final int[] PLAN_MONTHS = {3, 6};
    private static final int[] PLAN_PRICE = {1500, 10000};

    private int plan = 0;
    private TextView[] planChips;
    private EditText nameField;
    private EditText phoneField;
    private EditText countField;
    private EditText daysField;
    private LinearLayout results;
    private LinearLayout statsBox;
    private TextView createBtn;

    @Override
    protected String screenTitle() {
        return "اشتراكات Plus";
    }

    @Override
    protected void onBuild() {
        LinearLayout c = scrollBody();

        c.addView(CgUi.sectionTitle(this, "إنشاء كود تفعيل"));
        LinearLayout form = CgUi.card(this);

        form.addView(CgUi.muted(this, "الباقة"));
        LinearLayout chips = CgUi.hbox(this);
        chips.setGravity(Gravity.START);
        planChips = new TextView[PLAN_LABELS.length];
        for (int i = 0; i < PLAN_LABELS.length; i++) {
            final int index = i;
            planChips[i] = CgUi.chip(this, PLAN_LABELS[i], i == plan);
            planChips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    plan = index;
                    for (int k = 0; k < planChips.length; k++) CgUi.setChipState(planChips[k], k == plan);
                }
            });
            chips.addView(planChips[i], CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 6, 8, 6));
        }
        form.addView(chips);

        nameField = CgUi.field(this, "اسم الزبون (اختياري، يظهر في الرسالة)", false);
        form.addView(CgUi.labeled(this, "اسم الزبون", nameField), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        phoneField = CgUi.numberField(this, "07xxxxxxxxx");
        phoneField.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        form.addView(CgUi.labeled(this, "رقم واتساب الزبون (اختياري، لإرسال الكود مباشرة)", phoneField), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        LinearLayout row = CgUi.hbox(this);
        countField = CgUi.numberField(this, "1");
        countField.setText("1");
        daysField = CgUi.numberField(this, "بدون");
        row.addView(CgUi.labeled(this, "عدد الأكواد", countField), CgUi.lpWeight(this, 1f, 0, 0, 6, 0));
        row.addView(CgUi.labeled(this, "صلاحية الكود (أيام)", daysField), CgUi.lpWeight(this, 1f, 6, 0, 0, 0));
        form.addView(row, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        form.addView(CgUi.muted(this, "صلاحية الكود = المدة التي يبقى فيها الكود صالحاً للتفعيل. أما مدة Plus فتبدأ من لحظة تفعيل الزبون له."),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));

        createBtn = CgUi.button(this, "إنشاء الكود", 0);
        createBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                create();
            }
        });
        form.addView(createBtn, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 14, 0, 0));
        c.addView(form, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        results = CgUi.vbox(this);
        c.addView(results, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        c.addView(CgUi.sectionTitle(this, "الأرقام"));
        statsBox = CgUi.vbox(this);
        c.addView(statsBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        c.addView(CgUi.sectionTitle(this, "أدوات يدوية"));
        LinearLayout tools = CgUi.card(this);
        tools.addView(CgUi.muted(this, "منح Plus لحساب بدون كود، أو إيقافه فوراً (استرجاع مبلغ / إساءة). يكفي معرّف الحساب الذي يظهر للمستخدم بصفحة حسابه (مثل C48213)."));
        TextView grant = CgUi.button(this, "منح اشتراك لحساب", 1);
        grant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                grantDialog();
            }
        });
        tools.addView(grant, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        TextView revoke = CgUi.button(this, "إيقاف Plus لحساب", 2);
        revoke.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                revokeDialog();
            }
        });
        tools.addView(revoke, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        TextView key = CgUi.button(this, "تغيير مفتاح Plus", 3);
        key.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                keyDialog();
            }
        });
        tools.addView(key, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        c.addView(tools, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        bar.action("تحديث", new Runnable() {
            @Override
            public void run() {
                loadStats();
            }
        });
        loadStats();
    }

    // ------------------------------------------------------------------ network

    private String adminKey() {
        try {
            String k = getSharedPreferences(CgCfg.PREFS, Context.MODE_PRIVATE).getString(KEY_PREF, "");
            if (k != null && k.length() > 0) return k;
        } catch (Exception ignored) { }
        return CgCfg.TOKEN;
    }

    private CgHttp.Res plus(String method, String path, JSONObject body) {
        return CgHttp.call(method, PLUS_API + path, body, false, 30000, new String[][]{{"X-Admin-Key", adminKey()}});
    }

    private static String explain(CgHttp.Res r) {
        if (r.error != null) return r.message();
        if (r.code == 401) return "مفتاح Plus غير صحيح. اضغط «تغيير مفتاح Plus» وادخل قيمة ADMIN_KEY من الـ worker.";
        if (r.code == 429) return "محاولات كثيرة بمفتاح خاطئ. انتظر 10 دقائق.";
        if (r.code == 501) return "الـ worker غير مهيأ بعد (ADMIN_KEY ناقص).";
        if (r.code == 404) return "الـ worker cigram-plus-api غير منشور بعد.";
        return r.message();
    }

    // ------------------------------------------------------------------ create + send

    private void create() {
        int count = 1;
        try { count = Integer.parseInt(countField.getText().toString().trim()); } catch (Exception ignored) { }
        if (count < 1 || count > 50) { CgUi.toast(this, "عدد الأكواد من 1 إلى 50"); return; }
        int days = 0;
        String d = daysField.getText().toString().trim();
        if (d.length() > 0) {
            try { days = Integer.parseInt(d); } catch (Exception ignored) { }
            if (days < 1 || days > 3650) { CgUi.toast(this, "الصلاحية من 1 إلى 3650 يوماً، أو اتركها فارغة"); return; }
        }
        final String name = nameField.getText().toString().trim();
        final String phone = phoneField.getText().toString().trim();
        final int chosen = plan;
        final JSONObject body = new JSONObject();
        try {
            body.put("plan", PLAN_KEYS[chosen]);
            body.put("count", count);
            if (name.length() > 0) body.put("note", name);
            if (days > 0) body.put("expires_in_days", days);
        } catch (Exception ignored) { }
        final int fdays = days;
        showLoading("جارٍ إنشاء الكود...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                CgHttp.Res r = plus("POST", "/admin/plus/codes", body);
                if (!r.ok()) throw new Exception(explain(r));
                return r;
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                JSONArray codes = r.json == null ? null : r.json.optJSONArray("codes");
                if (codes == null || codes.length() == 0) { CgUi.info(PlusCodesActivity.this, "تعذر الإنشاء", "الرد لا يحتوي أكواداً."); return; }
                showCodes(codes, chosen, name, phone, fdays);
                loadStats();
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(PlusCodesActivity.this, "تعذر إنشاء الكود", message);
            }
        });
    }

    private void showCodes(JSONArray codes, final int chosen, final String name, final String phone, final int validDays) {
        results.removeAllViews();
        results.addView(CgUi.sectionTitle(this, codes.length() == 1 ? "الكود جاهز" : "الأكواد جاهزة (" + codes.length() + ")"));
        final StringBuilder all = new StringBuilder();
        for (int i = 0; i < codes.length(); i++) {
            final String code = codes.optString(i);
            if (all.length() > 0) all.append('\n');
            all.append(code);
            LinearLayout card = CgUi.card(this);
            TextView big = CgUi.text(this, code, 22, 0xFFFFD21F, true);
            big.setGravity(Gravity.CENTER);
            CgUi.ltr(big);
            big.setLetterSpacing(0.08f);
            card.addView(big, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            card.addView(CgUi.muted(this, PLAN_LABELS[chosen] + (validDays > 0 ? "  ·  صالح للتفعيل " + validDays + " يوماً" : "")),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
            LinearLayout actions = CgUi.hbox(this);
            TextView wa = CgUi.button(this, phone.length() > 0 ? "إرسال واتساب" : "واتساب", 0);
            wa.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    sendWhatsApp(phone, PlusMsg.message(name, code, PLAN_MONTHS[chosen], validDays));
                }
            });
            TextView copy = CgUi.button(this, "نسخ الرسالة", 1);
            copy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    copy("رسالة الكود", PlusMsg.message(name, code, PLAN_MONTHS[chosen], validDays));
                }
            });
            TextView copyCode = CgUi.button(this, "نسخ الكود", 1);
            copyCode.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    copy("الكود", code);
                }
            });
            actions.addView(wa, CgUi.lpWeight(this, 1.2f, 0, 0, 4, 0));
            actions.addView(copy, CgUi.lpWeight(this, 1f, 4, 0, 4, 0));
            actions.addView(copyCode, CgUi.lpWeight(this, 1f, 4, 0, 0, 0));
            card.addView(actions, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
            results.addView(card, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        }
        if (codes.length() > 1) {
            TextView copyAll = CgUi.button(this, "نسخ كل الأكواد", 1);
            copyAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    copy("الأكواد", all.toString());
                }
            });
            results.addView(copyAll, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }
        results.addView(CgUi.muted(this, "احفظ الكود الآن: الـ worker يحفظ نسخة مشفّرة فقط ولا يمكنه عرض الكود مرة ثانية."),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
    }

    private void sendWhatsApp(String phone, String text) {
        String num = PlusMsg.normalizePhone(phone);
        Uri uri = Uri.parse("https://wa.me/" + num + "?text=" + Uri.encode(text));
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.setPackage("com.whatsapp");
            startActivity(i);
            return;
        } catch (Exception ignored) { }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            share(text);
        }
    }

    private void share(String text) {
        try {
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/plain");
            s.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(s, "إرسال الكود"));
        } catch (Exception e) {
            CgUi.toast(this, "تعذر الإرسال");
        }
    }

    private void copy(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            CgUi.toast(this, "تم النسخ");
        } catch (Exception e) {
            CgUi.toast(this, "تعذر النسخ");
        }
    }

    // ------------------------------------------------------------------ stats

    private void loadStats() {
        if (statsBox == null) return;
        statsBox.removeAllViews();
        statsBox.addView(CgUi.muted(this, "جارٍ التحميل..."));
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                CgHttp.Res r = plus("GET", "/admin/plus/stats", null);
                if (!r.ok()) throw new Exception(explain(r));
                return r;
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                if (!alive()) return;
                renderStats(r.json == null ? new JSONObject() : r.json);
            }

            @Override
            public void fail(String message) {
                if (!alive()) return;
                statsBox.removeAllViews();
                statsBox.addView(CgUi.errorCard(PlusCodesActivity.this, message, new Runnable() {
                    @Override
                    public void run() {
                        loadStats();
                    }
                }), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            }
        });
    }

    private void renderStats(JSONObject j) {
        statsBox.removeAllViews();
        LinearLayout card = CgUi.card(this);
        card.addView(CgUi.text(this, "مشتركون فعّالون الآن: " + j.optInt("active_users", 0), 15, CgCfg.TEXT, true));
        card.addView(CgUi.muted(this, "كل من فعّل Plus مرة: " + j.optInt("users_ever", 0)));
        JSONArray codes = j.optJSONArray("codes");
        if (codes != null) {
            for (int i = 0; i < codes.length(); i++) {
                JSONObject o = codes.optJSONObject(i);
                if (o == null) continue;
                String p = "intro".equals(o.optString("plan")) ? "٣ أشهر" : "٦ أشهر";
                card.addView(CgUi.muted(this, "أكواد " + p + ": " + o.optInt("total") + " (مستخدم " + o.optInt("used") + " · متبقٍّ " + o.optInt("unused") + ")"),
                        CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
            }
        }
        long rev = j.optLong("revenue_iqd_from_used_codes", 0L);
        card.addView(CgUi.text(this, "دخل الأكواد المستخدمة (حسب الأسعار): " + String.format(Locale.US, "%,d", rev) + " د.ع", 13, 0xFF3DDC97, true),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        JSONArray recent = j.optJSONArray("recent");
        if (recent != null && recent.length() > 0) {
            card.addView(CgUi.divider(this));
            card.addView(CgUi.text(this, "آخر التفعيلات", 13, CgCfg.TEXT, true));
            for (int i = 0; i < recent.length() && i < 8; i++) {
                JSONObject o = recent.optJSONObject(i);
                if (o == null) continue;
                card.addView(CgUi.muted(this, o.optString("user_id") + "  ·  " + o.optString("detail") + "  ·  " + CgUi.ago(CgUi.toIso(o.optLong("at")))),
                        CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 3, 0, 0));
            }
        }
        statsBox.addView(card, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
    }

    // ------------------------------------------------------------------ manual tools

    private void grantDialog() {
        CgUi.input(this, "معرّف الحساب (مثل C48213)", "C12345", "", false, new CgUi.Callback<String>() {
            @Override
            public void run(final String id) {
                if (id.length() == 0) return;
                CgUi.choose(PlusCodesActivity.this, "كم شهراً؟", new CharSequence[]{"3 أشهر", "6 أشهر", "12 شهراً"}, new CgUi.IntCb() {
                    @Override
                    public void run(int index) {
                        int months = index == 0 ? 3 : (index == 1 ? 6 : 12);
                        manual("/admin/plus/grant", id, months, "تم منح " + months + " أشهر");
                    }
                });
            }
        });
    }

    private void revokeDialog() {
        CgUi.input(this, "معرّف الحساب (مثل C48213)", "C12345", "", false, new CgUi.Callback<String>() {
            @Override
            public void run(final String id) {
                if (id.length() == 0) return;
                CgUi.confirm(PlusCodesActivity.this, "إيقاف Plus", "إيقاف Plus فوراً للحساب:\n" + id, "إيقاف", true, new Runnable() {
                    @Override
                    public void run() {
                        manual("/admin/plus/revoke", id, 0, "تم إيقاف Plus");
                    }
                });
            }
        });
    }

    private void manual(final String path, String id, int months, final String done) {
        final JSONObject body = new JSONObject();
        try {
            body.put("user_id", id);
            if (months > 0) body.put("months", months);
        } catch (Exception ignored) { }
        showLoading("جارٍ التنفيذ...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                CgHttp.Res r = plus("POST", path, body);
                if (!r.ok()) throw new Exception(explain(r));
                return r;
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                CgUi.toast(PlusCodesActivity.this, done);
                loadStats();
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(PlusCodesActivity.this, "تعذر التنفيذ", message);
            }
        });
    }

    private void keyDialog() {
        CgUi.input(this, "مفتاح Plus (ADMIN_KEY)", "اتركه فارغاً لاستخدام رمز الإدارة", "", false, new CgUi.Callback<String>() {
            @Override
            public void run(String value) {
                try {
                    SharedPreferences.Editor e = getSharedPreferences(CgCfg.PREFS, Context.MODE_PRIVATE).edit();
                    if (value.length() == 0) e.remove(KEY_PREF); else e.putString(KEY_PREF, value);
                    e.apply();
                    CgUi.toast(PlusCodesActivity.this, value.length() == 0 ? "سيُستخدم رمز الإدارة" : "تم حفظ المفتاح على هذا الجهاز");
                    loadStats();
                } catch (Exception ignored) { }
            }
        });
    }
}
