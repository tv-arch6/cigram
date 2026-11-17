package com.my.newproject;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Merge separate seasons/series into one existing series. Detection by name / TMDB / IMDb / series_id / slug,
 * preview of every effect, no episode is deleted unless a duplicate policy is chosen explicitly.
 */
public class CgMergeActivity extends CgBase {

    private final ArrayList<JSONObject> groups = new ArrayList<JSONObject>();
    private String focus = "";
    private boolean loaded;
    private LinearLayout content;

    @Override
    protected String screenTitle() {
        return "دمج المسلسلات والمواسم";
    }

    @Override
    protected void onBuild() {
        String f = getIntent().getStringExtra("focus");
        focus = f == null ? "" : f;
        bar.action("دمج يدوي", new Runnable() {
            @Override
            public void run() {
                manualFlow();
            }
        });
        content = scrollBody();
        ensureData(new Runnable() {
            @Override
            public void run() {
                detect();
            }
        });
    }

    private void detect() {
        showLoading("جارٍ البحث عن مسلسلات متشابهة...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgHttp.apiGet("/admin/merge/detect"));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                groups.clear();
                JSONArray a = r.json.optJSONArray("groups");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject g = a.optJSONObject(i);
                    if (g == null) continue;
                    if (focus.length() > 0 && !containsId(g, focus)) continue;
                    groups.add(g);
                }
                loaded = true;
                render();
            }

            @Override
            public void fail(String message) {
                hideLoading();
                content = scrollBody();
                content.addView(CgUi.errorCard(CgMergeActivity.this, message, new Runnable() {
                    @Override
                    public void run() {
                        detect();
                    }
                }));
            }
        });
    }

    private boolean containsId(JSONObject g, String id) {
        JSONArray items = g.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null && id.equals(o.optString("id"))) return true;
        }
        return false;
    }

    private void render() {
        content = scrollBody();
        LinearLayout tip = CgUi.card(this);
        tip.addView(CgUi.text(this, "الدمج ينقل حلقات المسلسلات المصدر إلى المسلسل الهدف ويوحّد series_id مع الحفاظ على أرقام المواسم والبوسترات. لا تُحذف أي حلقة، وتُحفظ نسخة احتياطية ويمكنك التراجع.", 12.5f, CgCfg.MUTED, false));
        tip.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        content.addView(tip);
        if (focus.length() > 0) {
            TextView all = CgUi.smallButton(this, "عرض كل التكرارات", 1);
            all.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    focus = "";
                    detect();
                }
            });
            content.addView(all, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 0, 10));
        }
        if (groups.isEmpty()) {
            content.addView(CgUi.empty(this, loaded ? "لا توجد تكرارات" : "", "لم يُعثر على مسلسلات متشابهة بالاسم أو TMDB أو IMDb أو Slug. يمكنك استخدام «دمج يدوي»."));
            return;
        }
        for (int i = 0; i < groups.size(); i++) {
            final JSONObject g = groups.get(i);
            LinearLayout card = CgUi.card(this);
            card.addView(CgUi.text(this, g.optString("message", "يبدو أن هذا موسم تابع لمسلسل موجود."), 14, CgCfg.WARN, true));
            JSONArray reasons = g.optJSONArray("reasons");
            StringBuilder rs = new StringBuilder();
            for (int k = 0; reasons != null && k < reasons.length(); k++) {
                if (k > 0) rs.append(" • ");
                rs.append(reasons.optString(k));
            }
            if (rs.length() > 0) card.addView(CgUi.muted(this, "سبب التطابق: " + rs));
            JSONArray items = g.optJSONArray("items");
            String suggested = g.optString("suggested_target_id");
            for (int k = 0; items != null && k < items.length(); k++) {
                JSONObject o = items.optJSONObject(k);
                if (o == null) continue;
                boolean sug = suggested.equals(o.optString("id"));
                TextView t = CgUi.text(this, (sug ? "★ " : "• ") + o.optString("title") + "  —  " + o.optInt("episodes") + " حلقة"
                        + (o.optInt("season_hint") > 0 ? " (موسم " + o.optInt("season_hint") + ")" : ""), 13, sug ? CgCfg.TEXT : CgCfg.MUTED, sug);
                t.setPadding(0, CgUi.dp(this, 5), 0, 0);
                card.addView(t);
            }
            TextView go = CgUi.button(this, "دمج مع مسلسل موجود", 0);
            go.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startGroup(g);
                }
            });
            card.addView(go, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
            card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
            content.addView(card);
        }
    }

    // --------------------------------------------------------------- flow

    private void startGroup(final JSONObject g) {
        final JSONArray items = g.optJSONArray("items");
        if (items == null || items.length() < 2) return;
        final CharSequence[] labels = new CharSequence[items.length()];
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            labels[i] = o.optString("title") + " (" + o.optInt("episodes") + " حلقة)";
        }
        CgUi.choose(this, "اختر المسلسل الذي سيبقى (الهدف)", labels, new CgUi.IntCb() {
            @Override
            public void run(int index) {
                String target = items.optJSONObject(index).optString("id");
                ArrayList<String> sources = new ArrayList<String>();
                for (int i = 0; i < items.length(); i++) {
                    String id = items.optJSONObject(i).optString("id");
                    if (!id.equals(target)) sources.add(id);
                }
                seasonStep(target, sources);
            }
        });
    }

    private void seasonStep(final String target, final ArrayList<String> sources) {
        CgUi.choose(this, "أرقام المواسم", new CharSequence[]{
                "الإبقاء على أرقام المواسم كما هي", "تحديد رقم الموسم لكل مصدر يدوياً"
        }, new CgUi.IntCb() {
            @Override
            public void run(int index) {
                final JSONObject overrides = new JSONObject();
                if (index == 0) {
                    preview(target, sources, overrides, "block");
                } else {
                    askSeason(target, sources, overrides, 0);
                }
            }
        });
    }

    private void askSeason(final String target, final ArrayList<String> sources, final JSONObject overrides, final int i) {
        if (i >= sources.size()) {
            preview(target, sources, overrides, "block");
            return;
        }
        final String sid = sources.get(i);
        CgData d = CgData.cur();
        JSONObject h = d.byId(sid);
        String title = h == null ? sid : CgData.s(h, "title");
        CgUi.input(this, "رقم الموسم لـ «" + title + "» (فارغ = كما هو)", "مثال: 2", "", false, new CgUi.Callback<String>() {
            @Override
            public void run(String v) {
                try {
                    if (v.length() > 0) overrides.put(sid, v);
                } catch (Exception ignored) { }
                askSeason(target, sources, overrides, i + 1);
            }
        });
    }

    private JSONObject body(String target, ArrayList<String> sources, JSONObject overrides, String policy) throws Exception {
        JSONObject b = new JSONObject();
        b.put("target_id", target);
        JSONArray s = new JSONArray();
        for (int i = 0; i < sources.size(); i++) s.put(sources.get(i));
        b.put("source_ids", s);
        b.put("season_overrides", overrides);
        b.put("on_conflict", policy);
        return b;
    }

    private void preview(final String target, final ArrayList<String> sources, final JSONObject overrides, final String policy) {
        showLoading("جارٍ إعداد معاينة الدمج...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                return CgHttp.apiPost("/admin/merge/preview", body(target, sources, overrides, policy));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                if (!r.ok() || r.json == null) {
                    CgUi.info(CgMergeActivity.this, "تعذرت المعاينة", r.message());
                    return;
                }
                JSONObject sum = r.json.optJSONObject("summary");
                StringBuilder sb = new StringBuilder();
                if (sum != null) {
                    JSONObject t = sum.optJSONObject("target");
                    sb.append("الهدف: ").append(t == null ? "" : t.optString("title")).append("\n");
                    JSONArray srcs = sum.optJSONArray("sources");
                    for (int i = 0; srcs != null && i < srcs.length(); i++) {
                        JSONObject s = srcs.optJSONObject(i);
                        sb.append("المصدر: ").append(s.optString("title")).append(" (").append(s.optInt("episodes")).append(" حلقة)\n");
                    }
                    sb.append("\nحلقات ستُنقل: ").append(sum.optInt("episodes_moved"));
                    sb.append("\nعناوين مسلسلات ستُزال (بعد نقل حلقاتها): ").append(sum.optInt("source_headers_removed"));
                    sb.append("\nبوسترات مواسم ستُملأ: ").append(sum.optInt("poster_fills"));
                    sb.append("\nحلقات سيتغير قسمها: ").append(sum.optInt("section_changes"));
                    if (sum.optInt("carousel_entries") > 0) sb.append("\nعناصر كاروسيل ستُحوَّل: ").append(sum.optInt("carousel_entries"));
                }
                appendList(sb, r.json.optJSONArray("warnings"), "تنبيهات");
                JSONArray blocking = r.json.optJSONArray("blocking");
                final JSONArray conflicts = sum == null ? null : sum.optJSONArray("conflicts");
                if (blocking != null && blocking.length() > 0) {
                    appendList(sb, blocking, "لا يمكن التنفيذ");
                    if (conflicts != null && conflicts.length() > 0 && "block".equals(policy)) {
                        sb.append("\n\nيمكنك اختيار طريقة معالجة التكرار.");
                        CgUi.confirm(CgMergeActivity.this, "تكرار في الحلقات", sb.toString(), "اختر المعالجة", false, new Runnable() {
                            @Override
                            public void run() {
                                conflictStep(target, sources, overrides);
                            }
                        });
                    } else {
                        CgUi.info(CgMergeActivity.this, "الدمج غير ممكن", sb.toString());
                    }
                    return;
                }
                if (sum != null && sum.optInt("dropped_source_episodes") + sum.optInt("dropped_target_episodes") > 0) {
                    sb.append("\n\n⚠ سيُحذف ").append(sum.optInt("dropped_source_episodes") + sum.optInt("dropped_target_episodes")).append(" حلقة مكررة حسب اختيارك.");
                }
                sb.append("\n\nلن تُحذف أي حلقة أخرى. تُحفظ نسخة احتياطية ويمكنك التراجع.");
                final int moved = sum == null ? 0 : sum.optInt("episodes_moved");
                CgUi.confirm(CgMergeActivity.this, "معاينة الدمج", sb.toString(), "نفّذ الدمج", true, new Runnable() {
                    @Override
                    public void run() {
                        execute(target, sources, overrides, policy, moved);
                    }
                });
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(CgMergeActivity.this, "تعذرت المعاينة", message);
            }
        });
    }

    private void conflictStep(final String target, final ArrayList<String> sources, final JSONObject overrides) {
        CgUi.choose(this, "حلقة مكررة (نفس الموسم والرقم)", new CharSequence[]{
                "الإبقاء على حلقة الهدف وحذف حلقة المصدر المكررة", "الإبقاء على حلقة المصدر وحذف حلقة الهدف المكررة"
        }, new CgUi.IntCb() {
            @Override
            public void run(int index) {
                preview(target, sources, overrides, index == 0 ? "keep_target" : "keep_source");
            }
        });
    }

    private void appendList(StringBuilder sb, JSONArray a, String title) {
        if (a == null || a.length() == 0) return;
        sb.append("\n\n").append(title).append(":");
        for (int i = 0; i < a.length(); i++) sb.append("\n• ").append(a.optString(i));
    }

    private void execute(final String target, final ArrayList<String> sources, final JSONObject overrides, final String policy, final int moved) {
        showLoading("جارٍ الدمج...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                JSONObject b = body(target, sources, overrides, policy);
                b.put("expect_moved", moved);
                return CgHttp.require(CgHttp.apiPost("/admin/merge/execute", b));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(final CgHttp.Res r) {
                hideLoading();
                focus = "";
                reloadData(new Runnable() {
                    @Override
                    public void run() {
                        detect();
                        showUndo("تم الدمج (" + r.json.optInt("episodes_moved") + " حلقة)", r.json.optString("undo_id", ""), new Runnable() {
                            @Override
                            public void run() {
                                detect();
                            }
                        });
                    }
                });
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(CgMergeActivity.this, "لم يتم الدمج", message);
            }
        });
    }

    // ------------------------------------------------------------- manual

    private void manualFlow() {
        pickSeries("اختر المسلسل المصدر (سيُدمج داخل غيره)", new CgUi.Callback<JSONObject>() {
            @Override
            public void run(final JSONObject source) {
                pickSeries("اختر المسلسل الهدف (الذي سيبقى)", new CgUi.Callback<JSONObject>() {
                    @Override
                    public void run(JSONObject target) {
                        if (CgData.s(target, "id").equals(CgData.s(source, "id"))) {
                            CgUi.toast(CgMergeActivity.this, "المصدر والهدف نفس المسلسل.");
                            return;
                        }
                        ArrayList<String> src = new ArrayList<String>();
                        src.add(CgData.s(source, "id"));
                        seasonStep(CgData.s(target, "id"), src);
                    }
                });
            }
        });
    }

    private void pickSeries(final String title, final CgUi.Callback<JSONObject> cb) {
        CgUi.input(this, title, "اكتب جزءاً من الاسم", "", false, new CgUi.Callback<String>() {
            @Override
            public void run(String q) {
                CgData.Filter f = new CgData.Filter();
                f.kind = CgData.K_SERIES;
                final ArrayList<JSONObject> res = CgData.cur().query(q, f);
                if (res.isEmpty()) {
                    CgUi.info(CgMergeActivity.this, "لا نتائج", "لا يوجد مسلسل بهذا الاسم.");
                    return;
                }
                int n = Math.min(res.size(), 20);
                CharSequence[] labels = new CharSequence[n];
                for (int i = 0; i < n; i++) {
                    labels[i] = CgData.s(res.get(i), "title") + " (" + CgData.cur().episodesOf(res.get(i)).size() + " حلقة)";
                }
                CgUi.choose(CgMergeActivity.this, title, labels, new CgUi.IntCb() {
                    @Override
                    public void run(int index) {
                        cb.run(res.get(index));
                    }
                });
            }
        });
    }
}
