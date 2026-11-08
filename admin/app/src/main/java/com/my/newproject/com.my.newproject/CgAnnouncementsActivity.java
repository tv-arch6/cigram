package com.my.newproject;

import android.app.AlertDialog;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** In-app announcements: title, text, schedule, show once/always, dismissible, priority. */
public class CgAnnouncementsActivity extends CgBase {

    private final ArrayList<JSONObject> items = new ArrayList<JSONObject>();
    private int archiveCount;

    @Override
    protected String screenTitle() {
        return "الرسائل العامة";
    }

    @Override
    protected void onBuild() {
        bar.action("＋ رسالة", new Runnable() {
            @Override
            public void run() {
                edit(null);
            }
        });
        load();
    }

    private void load() {
        showLoading("جارٍ التحميل...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgHttp.apiGet("/admin/announcements"));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                items.clear();
                JSONArray a = r.json.optJSONArray("items");
                for (int i = 0; a != null && i < a.length(); i++) if (a.optJSONObject(i) != null) items.add(a.optJSONObject(i));
                JSONArray ar = r.json.optJSONArray("archive");
                archiveCount = ar == null ? 0 : ar.length();
                render();
            }

            @Override
            public void fail(String message) {
                hideLoading();
                body.removeAllViews();
                LinearLayout box = CgUi.vbox(CgAnnouncementsActivity.this);
                box.setPadding(CgUi.dp(CgAnnouncementsActivity.this, 12), CgUi.dp(CgAnnouncementsActivity.this, 12), CgUi.dp(CgAnnouncementsActivity.this, 12), 0);
                box.addView(CgUi.errorCard(CgAnnouncementsActivity.this, message, new Runnable() {
                    @Override
                    public void run() {
                        load();
                    }
                }));
                body.addView(box);
            }
        });
    }

    private void render() {
        LinearLayout c = scrollBody();
        if (items.isEmpty()) {
            c.addView(CgUi.empty(this, "لا توجد رسائل", "أنشئ رسالة تظهر للمستخدمين داخل التطبيق."));
        }
        for (int i = 0; i < items.size(); i++) {
            final JSONObject a = items.get(i);
            boolean active = a.optBoolean("active", false);
            boolean enabled = a.optBoolean("enabled", true);
            LinearLayout card = CgUi.card(this);
            card.addView(CgUi.text(this, a.optString("title").length() > 0 ? a.optString("title") : "(بدون عنوان)", 15, CgCfg.TEXT, true));
            TextView bodyText = CgUi.muted(this, a.optString("body"));
            bodyText.setMaxLines(3);
            card.addView(bodyText);
            LinearLayout tags = CgUi.hbox(this);
            if (!enabled) tags.addView(CgUi.badge(this, "متوقفة", CgCfg.GRAY), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 6, 6, 0));
            else if (active) tags.addView(CgUi.badge(this, "نشطة الآن", CgCfg.GOOD), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 6, 6, 0));
            else tags.addView(CgUi.badge(this, "خارج الفترة", CgCfg.WARN), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 6, 6, 0));
            tags.addView(CgUi.badge(this, "once".equals(a.optString("show_mode")) ? "مرة واحدة" : "كل مرة", CgCfg.ACCENT), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 6, 6, 0));
            tags.addView(CgUi.badge(this, "أولوية " + a.optInt("priority"), CgCfg.ACCENT), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 6, 6, 0));
            if (!a.optBoolean("dismissible", true)) tags.addView(CgUi.badge(this, "غير قابلة للإغلاق", CgCfg.WARN), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 6, 6, 0));
            card.addView(tags);
            String range = (a.optString("start_at").length() > 0 ? "من " + CgUi.localText(a.optString("start_at")) : "")
                    + (a.optString("end_at").length() > 0 ? "  إلى " + CgUi.localText(a.optString("end_at")) : "");
            if (range.length() > 0) card.addView(CgUi.muted(this, range));
            LinearLayout row = CgUi.hbox(this);
            TextView ed = CgUi.smallButton(this, "تعديل", 0);
            ed.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    edit(a);
                }
            });
            row.addView(ed, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
            TextView tg = CgUi.smallButton(this, enabled ? "إيقاف" : "تفعيل", 1);
            tg.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        JSONObject b = new JSONObject(a.toString());
                        b.put("enabled", !a.optBoolean("enabled", true));
                        send("/admin/announcements/save", wrap(b), "حفظ الرسالة");
                    } catch (Exception ignored) { }
                }
            });
            row.addView(tg, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
            TextView del = CgUi.smallButton(this, "حذف", 2);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CgUi.confirm(CgAnnouncementsActivity.this, "حذف الرسالة", "ستنتقل الرسالة إلى الأرشيف ولن تظهر للمستخدمين.", "احذف", true, new Runnable() {
                        @Override
                        public void run() {
                            try {
                                JSONObject b = new JSONObject();
                                b.put("id", a.optString("id"));
                                send("/admin/announcements/delete", b, "حذف الرسالة");
                            } catch (Exception ignored) { }
                        }
                    });
                }
            });
            row.addView(del, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            card.addView(row, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
            card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
            c.addView(card);
        }
        if (archiveCount > 0) c.addView(CgUi.muted(this, "في الأرشيف: " + archiveCount + " رسالة محذوفة (آخر 30)."));
    }

    private JSONObject wrap(JSONObject a) throws Exception {
        JSONObject b = new JSONObject();
        b.put("announcement", a);
        return b;
    }

    private void send(final String path, final JSONObject b, final String label) {
        showLoading("جارٍ الحفظ...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgHttp.apiPost(path, b));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                load();
                showUndo(label, r.json == null ? "" : r.json.optString("undo_id", ""), new Runnable() {
                    @Override
                    public void run() {
                        load();
                    }
                });
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(CgAnnouncementsActivity.this, "لم يتم الحفظ", message);
            }
        });
    }

    private String startIso = "";
    private String endIso = "";
    private String kindChosen = "info";

    private void edit(final JSONObject existing) {
        final boolean isNew = existing == null;
        final EditText title = CgUi.field(this, "عنوان الرسالة", false);
        final EditText text = CgUi.field(this, "نص الرسالة", true);
        final EditText priority = CgUi.numberField(this, "0");
        title.setText(isNew ? "" : existing.optString("title"));
        text.setText(isNew ? "" : existing.optString("body"));
        priority.setText(isNew ? "0" : String.valueOf(existing.optInt("priority")));
        startIso = isNew ? "" : existing.optString("start_at");
        endIso = isNew ? "" : existing.optString("end_at");
        kindChosen = isNew ? "info" : existing.optString("kind", "info");
        final CgUi.Toggle once = CgUi.toggle(this, "تظهر مرة واحدة فقط لكل مستخدم", isNew || "once".equals(existing.optString("show_mode", "once")));
        final CgUi.Toggle dismiss = CgUi.toggle(this, "يمكن للمستخدم إغلاقها", isNew || existing.optBoolean("dismissible", true));
        final CgUi.Toggle enabled = CgUi.toggle(this, "مفعّلة", isNew || existing.optBoolean("enabled", true));

        LinearLayout box = CgUi.vbox(this);
        box.setPadding(CgUi.dp(this, 16), CgUi.dp(this, 8), CgUi.dp(this, 16), CgUi.dp(this, 8));
        box.addView(CgUi.labeled(this, "العنوان", title));
        box.addView(CgUi.labeled(this, "النص", text));

        final String[] kinds = {"info", "warning", "success"};
        final String[] names = {"معلومة", "تنبيه", "نجاح"};
        final TextView[] chips = new TextView[3];
        CgUi.ChipBar cb = new CgUi.ChipBar(this);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            chips[i] = cb.add(names[i], kinds[i].equals(kindChosen));
            chips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    kindChosen = kinds[idx];
                    for (int k = 0; k < 3; k++) CgUi.setChipState(chips[k], k == idx);
                }
            });
        }
        box.addView(CgUi.labeled(this, "النوع", cb.scroll));

        final TextView startBtn = CgUi.button(this, startIso.length() == 0 ? "بداية العرض: فوراً" : "البداية: " + CgUi.localText(startIso), 1);
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUi.pickDateTime(CgAnnouncementsActivity.this, startIso, new CgUi.Callback<String>() {
                    @Override
                    public void run(String iso) {
                        startIso = iso;
                        startBtn.setText("البداية: " + CgUi.localText(iso));
                    }
                });
            }
        });
        startBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                startIso = "";
                startBtn.setText("بداية العرض: فوراً");
                return true;
            }
        });
        box.addView(CgUi.labeled(this, "بداية العرض (اضغط مطولاً للمسح)", startBtn));
        final TextView endBtn = CgUi.button(this, endIso.length() == 0 ? "نهاية العرض: بلا نهاية" : "النهاية: " + CgUi.localText(endIso), 1);
        endBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUi.pickDateTime(CgAnnouncementsActivity.this, endIso, new CgUi.Callback<String>() {
                    @Override
                    public void run(String iso) {
                        endIso = iso;
                        endBtn.setText("النهاية: " + CgUi.localText(iso));
                    }
                });
            }
        });
        endBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                endIso = "";
                endBtn.setText("نهاية العرض: بلا نهاية");
                return true;
            }
        });
        box.addView(CgUi.labeled(this, "نهاية العرض (اضغط مطولاً للمسح)", endBtn));
        box.addView(CgUi.labeled(this, "الأولوية (الأعلى تظهر أولاً)", priority));
        box.addView(once);
        box.addView(dismiss);
        box.addView(enabled);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        final AlertDialog dlg = CgUi.dialog(this).setTitle(isNew ? "رسالة جديدة" : "تعديل الرسالة").setView(sv)
                .setNegativeButton("إلغاء", null).setPositiveButton("حفظ", null).create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (title.getText().toString().trim().length() == 0 && text.getText().toString().trim().length() == 0) {
                    CgUi.toast(CgAnnouncementsActivity.this, "اكتب عنواناً أو نصاً.");
                    return;
                }
                dlg.dismiss();
                try {
                    JSONObject a = new JSONObject();
                    if (!isNew) a.put("id", existing.optString("id"));
                    a.put("title", title.getText().toString().trim());
                    a.put("body", text.getText().toString().trim());
                    a.put("kind", kindChosen);
                    a.put("start_at", startIso);
                    a.put("end_at", endIso);
                    a.put("show_mode", once.on ? "once" : "always");
                    a.put("dismissible", dismiss.on);
                    a.put("enabled", enabled.on);
                    int pr = 0;
                    try { pr = Integer.parseInt(priority.getText().toString().trim()); } catch (Exception ignored) { }
                    a.put("priority", pr);
                    send("/admin/announcements/save", wrap(a), "حفظ الرسالة");
                } catch (Exception e) {
                    CgUi.toast(CgAnnouncementsActivity.this, "خطأ: " + e.getMessage());
                }
            }
        });
    }
}
