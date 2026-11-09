package com.my.newproject;


import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** Bulk edit flow shared by the content list and the series screen: choose -> preview count -> confirm -> undo bar. */
final class CgBulk {

    private CgBulk() {}

    private static final String[] LABELS = {
            "تغيير القسم", "جعلها مميزة (Featured)", "إلغاء التمييز", "إخفاء", "إظهار",
            "تعديل الجودة", "استبدال التصنيف", "إضافة تصنيف", "نقل الحلقات إلى موسم", "حذف"
    };
    private static final String[] ACTIONS = {
            "set_section", "feature", "unfeature", "hide", "show",
            "set_quality", "set_category", "add_category", "move_to_season", "delete"
    };

    static void show(final CgBase a, final ArrayList<String> ids, final Runnable onDone) {
        if (ids.isEmpty()) {
            CgUi.toast(a, "لم يتم تحديد أي عنصر.");
            return;
        }
        CgUi.choose(a, "إجراء جماعي على " + ids.size() + " عنصر", LABELS, new CgUi.IntCb() {
            @Override
            public void run(int index) {
                collect(a, ACTIONS[index], LABELS[index], ids, onDone);
            }
        });
    }

    private static void collect(final CgBase a, final String action, final String label,
                                final ArrayList<String> ids, final Runnable onDone) {
        final JSONObject params = new JSONObject();
        try {
            if ("set_section".equals(action)) {
                pickSection(a, new CgUi.Callback<JSONObject>() {
                    @Override
                    public void run(JSONObject sec) {
                        try {
                            params.put("section_id", CgData.s(sec, "id"));
                            params.put("section_name", CgData.s(sec, "name"));
                        } catch (Exception ignored) { }
                        preview(a, action, label, ids, params, false, onDone);
                    }
                });
            } else if ("set_quality".equals(action)) {
                CgUi.input(a, "الجودة الجديدة", "مثال: 1080p", "", false, new CgUi.Callback<String>() {
                    @Override
                    public void run(String v) {
                        try { params.put("quality", v); } catch (Exception ignored) { }
                        preview(a, action, label, ids, params, false, onDone);
                    }
                });
            } else if ("set_category".equals(action) || "add_category".equals(action)) {
                CgUi.input(a, "set_category".equals(action) ? "التصنيف الجديد" : "التصنيف المراد إضافته", "مثال: دراما", "", false, new CgUi.Callback<String>() {
                    @Override
                    public void run(String v) {
                        if (v.length() == 0) return;
                        try { params.put("category", v); } catch (Exception ignored) { }
                        preview(a, action, label, ids, params, false, onDone);
                    }
                });
            } else if ("move_to_season".equals(action)) {
                CgUi.input(a, "رقم الموسم الوجهة", "مثال: 3", "", false, new CgUi.Callback<String>() {
                    @Override
                    public void run(String v) {
                        try { params.put("season", v); } catch (Exception ignored) { }
                        preview(a, action, label, ids, params, false, onDone);
                    }
                });
            } else if ("delete".equals(action)) {
                CgUi.choose(a, "حذف: ماذا تفعل بحلقات المسلسلات المحددة؟",
                        new CharSequence[]{"حذف العناصر المحددة فقط", "حذف المسلسلات المحددة مع كل حلقاتها"},
                        new CgUi.IntCb() {
                            @Override
                            public void run(int i) {
                                try { params.put("cascade_episodes", i == 1); } catch (Exception ignored) { }
                                preview(a, action, label, ids, params, true, onDone);
                            }
                        });
            } else {
                preview(a, action, label, ids, params, false, onDone);
            }
        } catch (Exception e) {
            CgUi.toast(a, "خطأ: " + e.getMessage());
        }
    }

    static void pickSection(final CgBase a, final CgUi.Callback<JSONObject> cb) {
        CgData d = CgData.cur();
        final ArrayList<JSONObject> secs = new ArrayList<JSONObject>();
        if (d != null) secs.addAll(d.sections);
        if (secs.isEmpty()) {
            CgUi.info(a, "لا توجد أقسام", "أنشئ الأقسام أولاً من «إدارة الأقسام».");
            return;
        }
        CharSequence[] labels = new CharSequence[secs.size()];
        for (int i = 0; i < secs.size(); i++) {
            labels[i] = CgData.s(secs.get(i), "name") + "  (" + CgData.s(secs.get(i), "id") + ")";
        }
        CgUi.choose(a, "اختر القسم", labels, new CgUi.IntCb() {
            @Override
            public void run(int index) {
                cb.run(secs.get(index));
            }
        });
    }

    private static void preview(final CgBase a, final String action, final String label,
                                final ArrayList<String> ids, final JSONObject params,
                                final boolean danger, final Runnable onDone) {
        a.showLoading("جارٍ حساب العناصر المتأثرة...");
        CgHttp.async(a, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                return CgHttp.apiPost("/admin/bulk", body(action, ids, params, true, -1));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                a.hideLoading();
                if (!r.ok() || r.json == null) {
                    CgUi.info(a, "تعذرت المعاينة", r.message());
                    return;
                }
                final int affected = r.json.optInt("affected", 0);
                StringBuilder sb = new StringBuilder();
                sb.append("سيتأثر ").append(affected).append(" عنصراً");
                sb.append(" (المحدد: ").append(r.json.optInt("selected", ids.size())).append(")");
                if (r.json.optInt("missing", 0) > 0) sb.append("\nلن يُنفّذ على ").append(r.json.optInt("missing")).append(" عنصر لم يعد موجوداً.");
                appendList(sb, r.json.optJSONArray("warnings"), "تنبيهات");
                JSONArray blocking = r.json.optJSONArray("blocking");
                if (blocking != null && blocking.length() > 0) {
                    appendList(sb, blocking, "لا يمكن التنفيذ");
                    CgUi.info(a, label, sb.toString());
                    return;
                }
                if (affected == 0) {
                    sb.append("\n\nلا يوجد ما يتغير: كل العناصر المحددة بالحالة المطلوبة أصلاً.");
                    CgUi.info(a, label, sb.toString());
                    return;
                }
                sb.append("\n\nيمكنك التراجع بعد التنفيذ، وتُحفظ نسخة احتياطية تلقائياً.");
                CgUi.confirm(a, label, sb.toString(), danger ? "نعم، نفّذ الحذف" : "تنفيذ", danger, new Runnable() {
                    @Override
                    public void run() {
                        execute(a, action, label, ids, params, affected, onDone);
                    }
                });
            }

            @Override
            public void fail(String message) {
                a.hideLoading();
                CgUi.info(a, "تعذرت المعاينة", message);
            }
        });
    }

    private static void appendList(StringBuilder sb, JSONArray arr, String title) {
        if (arr == null || arr.length() == 0) return;
        sb.append("\n\n").append(title).append(":");
        for (int i = 0; i < arr.length(); i++) sb.append("\n• ").append(arr.optString(i));
    }

    private static JSONObject body(String action, ArrayList<String> ids, JSONObject params, boolean dry, int expect) {
        JSONObject b = new JSONObject();
        try {
            b.put("action", action);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < ids.size(); i++) arr.put(ids.get(i));
            b.put("ids", arr);
            b.put("params", params);
            if (dry) b.put("dry_run", true);
            else b.put("expect_count", expect);
        } catch (Exception ignored) { }
        return b;
    }

    private static void execute(final CgBase a, final String action, final String label,
                                final ArrayList<String> ids, final JSONObject params,
                                final int expect, final Runnable onDone) {
        a.showLoading("جارٍ التنفيذ...");
        CgHttp.async(a, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgHttp.apiPost("/admin/bulk", body(action, ids, params, false, expect)));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(final CgHttp.Res r) {
                a.hideLoading();
                final String undoId = r.json == null ? "" : r.json.optString("undo_id", "");
                a.reloadData(new Runnable() {
                    @Override
                    public void run() {
                        if (onDone != null) onDone.run();
                        a.showUndo(label + " (" + expect + ")", undoId, onDone);
                    }
                });
            }

            @Override
            public void fail(String message) {
                a.hideLoading();
                CgUi.info(a, "لم يتم التنفيذ", message);
            }
        });
    }
}
