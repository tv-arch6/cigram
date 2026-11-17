package com.my.newproject;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Operation history with undo, and server-side movies.json backups with restore. */
public class CgHistoryActivity extends CgBase {

    private JSONArray history = new JSONArray();
    private JSONArray backups = new JSONArray();

    @Override
    protected String screenTitle() {
        return "سجل العمليات والنسخ الاحتياطية";
    }

    @Override
    protected void onBuild() {
        load();
    }

    private void load() {
        showLoading("جارٍ التحميل...");
        CgHttp.async(this, new CgHttp.Work<JSONObject[]>() {
            @Override
            public JSONObject[] run() throws Exception {
                CgHttp.Res a = CgHttp.require(CgHttp.apiGet("/admin/history"));
                CgHttp.Res b = CgHttp.apiGet("/admin/backups");
                return new JSONObject[]{a.json, b.json};
            }
        }, new CgHttp.Done<JSONObject[]>() {
            @Override
            public void ok(JSONObject[] r) {
                hideLoading();
                history = r[0].optJSONArray("entries");
                backups = r[1] == null ? null : r[1].optJSONArray("entries");
                if (history == null) history = new JSONArray();
                if (backups == null) backups = new JSONArray();
                render();
            }

            @Override
            public void fail(String message) {
                hideLoading();
                body.removeAllViews();
                LinearLayout box = CgUi.vbox(CgHistoryActivity.this);
                box.setPadding(CgUi.dp(CgHistoryActivity.this, 12), CgUi.dp(CgHistoryActivity.this, 12), CgUi.dp(CgHistoryActivity.this, 12), 0);
                box.addView(CgUi.errorCard(CgHistoryActivity.this, message, new Runnable() {
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
        c.addView(CgUi.sectionTitle(this, "آخر العمليات (للتراجع)"));
        if (history.length() == 0) c.addView(CgUi.empty(this, "لا توجد عمليات مسجّلة", ""));
        for (int i = 0; i < history.length(); i++) {
            final JSONObject e = history.optJSONObject(i);
            if (e == null) continue;
            LinearLayout card = CgUi.card(this);
            String label = e.optString("label");
            boolean undone = label.startsWith("(تم التراجع)");
            card.addView(CgUi.text(this, label, 14, undone ? CgCfg.GRAY : CgCfg.TEXT, true));
            card.addView(CgUi.muted(this, CgUi.ago(e.optString("at")) + " • " + e.optString("op") + (e.optInt("count") > 0 ? " • " + e.optInt("count") + " عنصر" : "")));
            if (!undone) {
                TextView u = CgUi.smallButton(this, "تراجع عن هذه العملية", 1);
                u.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        CgUi.confirm(CgHistoryActivity.this, "تراجع", "التراجع عن: " + e.optString("label") + "\nأي تعديل حدث بعدها على نفس العناصر سيُستبدل.", "تراجع", true, new Runnable() {
                            @Override
                            public void run() {
                                doUndo(e.optString("id"), new Runnable() {
                                    @Override
                                    public void run() {
                                        load();
                                    }
                                });
                            }
                        });
                    }
                });
                card.addView(u, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 6, 0, 0));
            }
            card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
            c.addView(card);
        }
        c.addView(CgUi.sectionTitle(this, "النسخ الاحتياطية لـ movies.json"));
        if (backups.length() == 0) c.addView(CgUi.muted(this, "لا توجد نسخ بعد. تُنشأ تلقائياً قبل العمليات الكبيرة."));
        for (int i = 0; i < backups.length(); i++) {
            final JSONObject b = backups.optJSONObject(i);
            if (b == null) continue;
            LinearLayout card = CgUi.card(this);
            card.addView(CgUi.text(this, b.optString("label").length() > 0 ? b.optString("label") : b.optString("key"), 13.5f, CgCfg.TEXT, true));
            card.addView(CgUi.muted(this, CgUi.ago(b.optString("at")) + " • " + CgUi.sizeText(b.optLong("size"))));
            TextView r = CgUi.smallButton(this, "استعادة هذه النسخة", 2);
            r.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CgUi.confirm(CgHistoryActivity.this, "استعادة نسخة",
                            "سيُستبدل movies.json بالكامل بهذه النسخة. تُحفظ نسخة أمان من الحالة الحالية قبل الاستعادة.", "استعد", true, new Runnable() {
                                @Override
                                public void run() {
                                    restore(b.optString("key"));
                                }
                            });
                }
            });
            card.addView(r, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 6, 0, 0));
            card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
            c.addView(card);
        }
    }

    private void restore(final String key) {
        showLoading("جارٍ الاستعادة...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("key", key);
                return CgHttp.require(CgHttp.apiPost("/admin/backups/restore", b));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                CgUi.info(CgHistoryActivity.this, "تمت الاستعادة", "عدد العناصر المستعادة: " + r.json.optInt("restored_count"));
                reloadData(new Runnable() {
                    @Override
                    public void run() {
                        load();
                    }
                });
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(CgHistoryActivity.this, "تعذرت الاستعادة", message);
            }
        });
    }
}
