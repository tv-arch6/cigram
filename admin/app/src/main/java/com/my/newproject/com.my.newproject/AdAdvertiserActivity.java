package com.my.newproject;

import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** ملف معلن: بيانات التواصل، ملاحظات داخلية، وسوم، حظر/أرشفة، حملاته، فتح محادثته، وحذفه. Extras: id (فارغ = جديد). */
public class AdAdvertiserActivity extends CgBase {

    private String id = "";
    private JSONObject adv = new JSONObject();
    private EditText fName, fPerson, fPhone, fWa, fEmail, fWeb, fAddr, fTags, fNotes;
    private boolean banned, archived;

    @Override protected String screenTitle() { return "ملف المعلن"; }

    @Override protected void onBuild() {
        String i = getIntent().getStringExtra("id");
        id = i == null ? "" : i;
        bar.action("حفظ", new Runnable() { @Override public void run() { save(); } });
        if (id.length() == 0) { render(new JSONArray()); return; }
        AdApi.get(this, "/admin/advertisers/get?id=" + AdApi.enc(id), new AdApi.Cb() {
            @Override public void on(AdApi.Result r) {
                if (!alive()) return;
                if (!r.ok) { body.removeAllViews(); body.addView(CgUi.errorCard(AdAdvertiserActivity.this, r.message, new Runnable() { @Override public void run() { finish(); } })); return; }
                adv = r.json.optJSONObject("advertiser");
                render(r.json.optJSONArray("campaigns") == null ? new JSONArray() : r.json.optJSONArray("campaigns"));
            }
        });
    }

    private EditText row(LinearLayout c, String label, String val, boolean multi) {
        EditText e = CgUi.field(this, label, multi);
        e.setText(val);
        c.addView(CgUi.labeled(this, label, e));
        return e;
    }

    private void render(JSONArray camps) {
        LinearLayout c = scrollBody();
        JSONObject ct = adv.optJSONObject("contact") == null ? new JSONObject() : adv.optJSONObject("contact");
        banned = adv.optBoolean("banned");
        archived = adv.optBoolean("archived");
        fName = row(c, "اسم الشركة", adv.optString("name"), false);
        fPerson = row(c, "الشخص المسؤول", ct.optString("person"), false);
        fPhone = row(c, "الهاتف", ct.optString("phone"), false);
        fWa = row(c, "واتساب (أرقام فقط مع رمز الدولة)", ct.optString("whatsapp"), false);
        fEmail = row(c, "البريد", ct.optString("email"), false);
        fWeb = row(c, "الموقع", ct.optString("website"), false);
        fAddr = row(c, "العنوان", ct.optString("address"), false);
        StringBuilder sb = new StringBuilder();
        JSONArray tg = adv.optJSONArray("tags");
        for (int k = 0; tg != null && k < tg.length(); k++) sb.append(k > 0 ? "، " : "").append(tg.optString(k));
        fTags = row(c, "الوسوم (افصل بفاصلة)", sb.toString(), false);
        fNotes = row(c, "ملاحظات داخلية (لا يراها المعلن)", adv.optString("notes"), true);
        final CgUi.Toggle tb = CgUi.toggle(this, "محظور من المحادثة وطلب الإعلانات", banned);
        final CgUi.Toggle ta = CgUi.toggle(this, "مؤرشف", archived);
        c.addView(tb);
        c.addView(ta);
        tb.onChange(new Runnable() { @Override public void run() { banned = tb.on; } });
        ta.onChange(new Runnable() { @Override public void run() { archived = ta.on; } });
        if (id.length() > 0) {
            String uk = adv.optString("user_key");
            if (uk.length() > 0) {
                TextView chat = CgUi.button(this, "فتح المحادثة", 0);
                final String ukf = uk;
                chat.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { Intent in = new Intent(AdAdvertiserActivity.this, AdChatActivity.class); in.putExtra("thread", ukf); in.putExtra("name", adv.optString("name")); startActivity(in); }
                });
                c.addView(chat, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 6));
            }
            TextView add = CgUi.button(this, "＋ حملة جديدة لهذا المعلن", 1);
            add.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { Intent in = new Intent(AdAdvertiserActivity.this, AdCampaignActivity.class); in.putExtra("advertiser_id", id); startActivity(in); }
            });
            c.addView(add, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));
            c.addView(CgUi.sectionTitle(this, "الحملات (" + camps.length() + ")"));
            for (int k = 0; k < camps.length(); k++) {
                final JSONObject o = camps.optJSONObject(k);
                LinearLayout card = CgUi.card(this);
                LinearLayout h = CgUi.hbox(this);
                h.addView(CgUi.text(this, o.optString("name"), 14.5f, CgCfg.TEXT, true), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
                h.addView(CgUi.badge(this, AdApi.stageLabel(o.optString("stage")), AdApi.stageColor(o.optString("stage"))));
                card.addView(h);
                card.addView(CgUi.muted(this, AdApi.date(o.optLong("start_at")) + " ← " + AdApi.date(o.optLong("end_at")) + " • " + AdApi.money(o.optDouble("price"), "")));
                card.setClickable(true);
                card.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { Intent in = new Intent(AdAdvertiserActivity.this, AdCampaignActivity.class); in.putExtra("id", o.optString("id")); startActivity(in); }
                });
                c.addView(card, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
            }
            TextView del = CgUi.button(this, "حذف المعلن نهائياً", 2);
            del.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { confirmDelete(); } });
            c.addView(del, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 14, 0, 0));
        }
    }

    private void save() {
        String name = fName.getText().toString().trim();
        if (name.length() == 0) { CgUi.toast(this, "أدخل اسم الشركة."); return; }
        try {
            JSONObject b = new JSONObject();
            if (id.length() > 0) b.put("id", id);
            b.put("name", name);
            b.put("contact", new JSONObject().put("person", fPerson.getText().toString().trim()).put("phone", fPhone.getText().toString().trim())
                    .put("whatsapp", fWa.getText().toString().trim()).put("email", fEmail.getText().toString().trim())
                    .put("website", fWeb.getText().toString().trim()).put("address", fAddr.getText().toString().trim()));
            b.put("notes", fNotes.getText().toString().trim());
            JSONArray tags = new JSONArray();
            for (String s : fTags.getText().toString().split("[,،]")) if (s.trim().length() > 0) tags.put(s.trim());
            b.put("tags", tags);
            b.put("banned", banned);
            b.put("archived", archived);
            showLoading("جارٍ الحفظ...");
            AdApi.post(this, "/admin/advertisers/save", b, new AdApi.Cb() {
                @Override public void on(AdApi.Result r) {
                    hideLoading();
                    if (!alive()) return;
                    if (r.ok) { CgUi.toast(AdAdvertiserActivity.this, "تم الحفظ."); if (id.length() == 0) finish(); }
                    else CgUi.toast(AdAdvertiserActivity.this, r.message);
                }
            });
        } catch (Throwable t) { hideLoading(); }
    }

    private void confirmDelete() {
        String advName = fName == null ? "" : fName.getText().toString().trim();
        if (advName.length() == 0) advName = adv.optString("name", "بلا اسم");
        CgUi.confirm(this, "حذف «" + advName + "»", "سيُحذف المعلن «" + advName + "» مع كل حملاته ومواده ومحادثته نهائياً. لا يمكن التراجع.", "حذف", true, new Runnable() {
            @Override public void run() {
                try {
                    showLoading("جارٍ الحذف...");
                    AdApi.post(AdAdvertiserActivity.this, "/admin/advertisers/delete", new JSONObject().put("id", id).put("force", true), new AdApi.Cb() {
                        @Override public void on(AdApi.Result r) { hideLoading(); if (r.ok) finish(); else CgUi.toast(AdAdvertiserActivity.this, r.message); }
                    });
                } catch (Throwable ignored) { hideLoading(); }
            }
        });
    }
}
