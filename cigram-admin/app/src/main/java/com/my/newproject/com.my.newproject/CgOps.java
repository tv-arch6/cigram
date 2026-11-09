package com.my.newproject;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Shared operations used by several admin screens: item update, notes, URL checks. */
final class CgOps {

    private CgOps() {}

    /** Updates one item through the Worker, refreshes the local snapshot and offers undo. */
    static void updateItem(final CgBase a, final String id, final JSONObject changes, final String label,
                           final Runnable onDone) {
        a.showLoading("جارٍ الحفظ...");
        CgHttp.async(a, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("id", id);
                b.put("changes", changes);
                return CgHttp.require(CgHttp.apiPost("/admin/item/update", b));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                a.hideLoading();
                CgData d = CgData.cur();
                JSONObject it = r.json == null ? null : r.json.optJSONObject("item");
                if (d != null && it != null) d.replaceItem(it);
                if (onDone != null) onDone.run();
                a.showUndo(label, r.json == null ? "" : r.json.optString("undo_id", ""), onDone);
            }

            @Override
            public void fail(String message) {
                a.hideLoading();
                CgUi.info(a, "لم يتم الحفظ", message);
            }
        });
    }

    /** Saves an admin note (never visible to users). scope: item | season | channel | section. */
    static void saveNote(final CgBase a, final String scope, final String key, String current, String title,
                         final Runnable onDone) {
        CgUi.input(a, title, "ملاحظة داخلية للأدمن فقط (لا تظهر للمستخدمين)", current, true, new CgUi.Callback<String>() {
            @Override
            public void run(final String text) {
                a.showLoading("جارٍ حفظ الملاحظة...");
                CgHttp.async(a, new CgHttp.Work<CgHttp.Res>() {
                    @Override
                    public CgHttp.Res run() throws Exception {
                        JSONObject b = new JSONObject();
                        b.put("scope", scope);
                        b.put("key", key);
                        b.put("note", text);
                        return CgHttp.require(CgHttp.apiPost("/admin/note", b));
                    }
                }, new CgHttp.Done<CgHttp.Res>() {
                    @Override
                    public void ok(CgHttp.Res r) {
                        a.hideLoading();
                        CgData d = CgData.cur();
                        if (d != null) d.setNoteLocal(scope, key, text);
                        CgUi.toast(a, text.length() == 0 ? "تم حذف الملاحظة." : "تم حفظ الملاحظة.");
                        if (onDone != null) onDone.run();
                    }

                    @Override
                    public void fail(String message) {
                        a.hideLoading();
                        CgUi.info(a, "تعذر حفظ الملاحظة", message);
                    }
                });
            }
        });
    }

    /** One URL to test. kind: video | subtitle | image */
    static final class CheckReq {
        final String id;
        final String kind;
        final String url;

        CheckReq(String id, String kind, String url) {
            this.id = id;
            this.kind = kind;
            this.url = url;
        }
    }

    static String videoUrl(JSONObject it) {
        String v = CgData.s(it, "video");
        return v.length() > 0 ? v : CgData.s(it, "link");
    }

    /** Builds check requests for items (video and/or subtitle). Items without a URL are skipped for subtitles. */
    static ArrayList<CheckReq> requestsFor(List<JSONObject> items, boolean video, boolean subtitle) {
        ArrayList<CheckReq> out = new ArrayList<CheckReq>();
        for (int i = 0; i < items.size(); i++) {
            JSONObject it = items.get(i);
            String id = CgData.s(it, "id");
            if (CgData.kind(it) == CgData.K_SERIES) continue;
            if (video) {
                String v = videoUrl(it);
                if (!CgVidbolt.is(v)) out.add(new CheckReq(id, "video", v));
            }
            if (subtitle && CgData.s(it, "subtitle").length() > 0) out.add(new CheckReq(id, "subtitle", CgData.s(it, "subtitle")));
        }
        return out;
    }

    interface CheckDone {
        void done(int checked, int bad);
    }

    /**
     * On-demand batch check. Sends batches of 20 (Worker limit), stores the results server side
     * (admin meta) and in the local snapshot. Never runs automatically.
     */
    static void runChecks(final CgBase a, final ArrayList<CheckReq> reqs, final CheckDone done) {
        if (reqs.isEmpty()) {
            CgUi.toast(a, "لا توجد روابط للفحص.");
            return;
        }
        a.showLoading("جارٍ الفحص 0/" + reqs.size() + "...");
        CgHttp.async(a, new CgHttp.Work<int[]>() {
            @Override
            public int[] run() throws Exception {
                int checked = 0;
                int bad = 0;
                final int batch = 20;
                for (int from = 0; from < reqs.size(); from += batch) {
                    int to = Math.min(reqs.size(), from + batch);
                    JSONArray items = new JSONArray();
                    for (int i = from; i < to; i++) {
                        CheckReq q = reqs.get(i);
                        JSONObject o = new JSONObject();
                        o.put("id", q.id);
                        o.put("kind", q.kind);
                        o.put("url", q.url);
                        items.put(o);
                    }
                    JSONObject body = new JSONObject();
                    body.put("items", items);
                    CgHttp.Res r = CgHttp.require(CgHttp.apiPost("/admin/check-urls", body));
                    JSONArray results = r.json == null ? null : r.json.optJSONArray("results");
                    if (results == null) continue;
                    JSONObject save = new JSONObject();
                    save.put("results", results);
                    CgHttp.apiPost("/admin/save-checks", save);
                    final CgData d = CgData.cur();
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject res = results.optJSONObject(i);
                        if (res == null) continue;
                        checked++;
                        if (!"ok".equals(res.optString("status"))) bad++;
                        if (d != null) d.setCheckLocal(res.optString("id"), res.optString("kind", "video"), res);
                    }
                }
                return new int[]{checked, bad};
            }
        }, new CgHttp.Done<int[]>() {
            @Override
            public void ok(int[] r) {
                a.hideLoading();
                if (done != null) done.done(r[0], r[1]);
            }

            @Override
            public void fail(String message) {
                a.hideLoading();
                CgUi.info(a, "تعذر الفحص", message);
            }
        });
    }
}
