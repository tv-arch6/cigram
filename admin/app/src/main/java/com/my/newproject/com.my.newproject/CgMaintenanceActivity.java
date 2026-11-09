package com.my.newproject;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Maintenance mode controlled dynamically through the Worker (cigram_maintenance.json). */
public class CgMaintenanceActivity extends CgBase {

    private CgUi.Toggle enabled;
    private CgUi.Toggle allowUsers;
    private CgUi.Toggle allowAdmins;
    private EditText title;
    private EditText message;
    private EditText emails;
    private String eta = "";
    private TextView etaButton;

    @Override
    protected String screenTitle() {
        return "وضع الصيانة";
    }

    @Override
    protected void onBuild() {
        load();
    }

    private void load() {
        showLoading("جارٍ التحميل...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgHttp.apiGet("/maintenance"));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                render(r.json);
            }

            @Override
            public void fail(String message) {
                hideLoading();
                body.removeAllViews();
                LinearLayout box = CgUi.vbox(CgMaintenanceActivity.this);
                box.setPadding(CgUi.dp(CgMaintenanceActivity.this, 12), CgUi.dp(CgMaintenanceActivity.this, 12), CgUi.dp(CgMaintenanceActivity.this, 12), 0);
                box.addView(CgUi.errorCard(CgMaintenanceActivity.this, message, new Runnable() {
                    @Override
                    public void run() {
                        load();
                    }
                }));
                body.addView(box);
            }
        });
    }

    private void render(JSONObject m) {
        LinearLayout c = scrollBody();
        boolean on = m.optBoolean("enabled", false);
        LinearLayout st = CgUi.card(this);
        int color = on ? CgCfg.BAD : CgCfg.GOOD;
        st.setBackground(CgUi.bg(this, CgUi.alpha(color, 0x1A), 14, color));
        st.addView(CgUi.text(this, on ? "● التطبيق الآن في وضع الصيانة" : "● التطبيق يعمل بشكل طبيعي", 15, color, true));
        String upd = m.optString("updated_at", "");
        if (upd.length() > 0) st.addView(CgUi.muted(this, "آخر تعديل: " + CgUi.ago(upd)));
        st.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        c.addView(st);

        LinearLayout card = CgUi.card(this);
        enabled = CgUi.toggle(this, "تفعيل وضع الصيانة", on);
        card.addView(enabled);
        allowAdmins = CgUi.toggle(this, "السماح للأدمن بالدخول أثناء الصيانة", m.optBoolean("allow_admins", true));
        card.addView(allowAdmins);
        allowUsers = CgUi.toggle(this, "السماح للمستخدمين العاديين (تجاوز الصيانة)", m.optBoolean("allow_users", false));
        card.addView(allowUsers);
        title = CgUi.field(this, "عنوان الرسالة", false);
        title.setText(m.optString("title", ""));
        card.addView(CgUi.labeled(this, "العنوان", title));
        message = CgUi.field(this, "نص الرسالة الظاهرة للمستخدم", true);
        message.setText(m.optString("message", ""));
        card.addView(CgUi.labeled(this, "الرسالة", message));
        eta = m.optString("eta", "");
        etaButton = CgUi.button(this, etaText(), 1);
        etaButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUi.pickDateTime(CgMaintenanceActivity.this, eta, new CgUi.Callback<String>() {
                    @Override
                    public void run(String iso) {
                        eta = iso;
                        etaButton.setText(etaText());
                    }
                });
            }
        });
        card.addView(CgUi.labeled(this, "الوقت المتوقع للعودة (اختياري)", etaButton));
        TextView clear = CgUi.smallButton(this, "مسح الوقت المتوقع", 3);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                eta = "";
                etaButton.setText(etaText());
            }
        });
        card.addView(clear, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        emails = CgUi.field(this, "بريد1@example.com, بريد2@example.com", false);
        JSONArray ae = m.optJSONArray("admin_emails");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; ae != null && i < ae.length(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(ae.optString(i));
        }
        emails.setText(sb.toString());
        card.addView(CgUi.labeled(this, "إيميلات الأدمن المسموح لهم (اختياري)", emails));
        card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        c.addView(card);

        TextView save = CgUi.button(this, "حفظ", 0);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (enabled.on && !allowUsers.on) {
                    CgUi.confirm(CgMaintenanceActivity.this, "تفعيل الصيانة",
                            "سيُمنع المستخدمون من استخدام التطبيق إلى أن توقف الصيانة. متابعة؟", "فعّل الصيانة", true, new Runnable() {
                                @Override
                                public void run() {
                                    save();
                                }
                            });
                } else {
                    save();
                }
            }
        });
        c.addView(save, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        TextView note = CgUi.muted(this, "يقرأ تطبيق المستخدم الحالة من /maintenance ويطبّقها عند الفتح. لا حاجة لتحديث التطبيق عند تغيير الحالة من هنا.");
        note.setPadding(CgUi.dp(this, 4), CgUi.dp(this, 10), CgUi.dp(this, 4), 0);
        c.addView(note);
    }

    private String etaText() {
        return eta.length() == 0 ? "اختيار التاريخ والوقت" : CgUi.localText(eta);
    }

    private void save() {
        showLoading("جارٍ الحفظ...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("enabled", enabled.on);
                b.put("allow_admins", allowAdmins.on);
                b.put("allow_users", allowUsers.on);
                b.put("title", title.getText().toString().trim());
                b.put("message", message.getText().toString().trim());
                b.put("eta", eta);
                JSONArray arr = new JSONArray();
                String[] parts = emails.getText().toString().split("[,،;\\s]+");
                for (int i = 0; i < parts.length; i++) if (parts[i].trim().length() > 0) arr.put(parts[i].trim());
                b.put("admin_emails", arr);
                return CgHttp.require(CgHttp.apiPost("/admin/maintenance", b));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                render(r.json);
                showUndo(r.json.optBoolean("enabled") ? "تم تفعيل الصيانة" : "تم الحفظ", r.json.optString("undo_id", ""), new Runnable() {
                    @Override
                    public void run() {
                        load();
                    }
                });
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(CgMaintenanceActivity.this, "لم يتم الحفظ", message);
            }
        });
    }
}
