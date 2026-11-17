package com.my.newproject;

import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/** Featured movies and series with ordering. Works for home, carousel or a standalone section on the client side. */
public class CgFeaturedActivity extends CgBase {

    private final ArrayList<JSONObject> featured = new ArrayList<JSONObject>();

    @Override
    protected String screenTitle() {
        return "المحتوى المميز";
    }

    @Override
    protected void onBuild() {
        bar.action("＋ إضافة", new Runnable() {
            @Override
            public void run() {
                addFlow();
            }
        });
        ensureData(new Runnable() {
            @Override
            public void run() {
                render();
            }
        });
    }

    private void collect() {
        featured.clear();
        CgData d = CgData.cur();
        for (int i = 0; i < d.items.size(); i++) {
            JSONObject it = d.items.get(i);
            if (CgData.kind(it) != CgData.K_EPISODE && CgData.b(it, "featured")) featured.add(it);
        }
        Collections.sort(featured, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                int x = CgData.n(a, "featured_order");
                int y = CgData.n(b, "featured_order");
                if (x == 0) x = 1000000;
                if (y == 0) y = 1000000;
                return x < y ? -1 : x > y ? 1 : 0;
            }
        });
    }

    private void render() {
        collect();
        final CgData d = CgData.cur();
        LinearLayout c = scrollBody();
        View warn = warningCard();
        if (warn != null) c.addView(warn);
        LinearLayout tip = CgUi.card(this);
        tip.addView(CgUi.text(this, "العناصر المميزة (" + featured.size() + "). الترتيب هنا هو ترتيب ظهورها في الكاروسيل/القسم المميز (الأول في أقصى اليمين). يمكن للتطبيق قراءتها من /featured.", 12.5f, CgCfg.MUTED, false));
        tip.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        c.addView(tip);
        if (featured.isEmpty()) {
            c.addView(CgUi.empty(this, "لا يوجد محتوى مميز", "أضف أفلاماً أو مسلسلات بزر «＋ إضافة»."));
            return;
        }
        for (int i = 0; i < featured.size(); i++) {
            final int index = i;
            final JSONObject it = featured.get(i);
            LinearLayout wrap = CgUi.vbox(this);
            View row = CgRows.itemRow(this, d, it, false, false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    open(it);
                }
            }, null);
            wrap.addView(row);
            LinearLayout acts = CgUi.hbox(this);
            TextView num = CgUi.text(this, "#" + (i + 1), 13, CgCfg.ACCENT, true);
            acts.addView(num, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 8, 0, 10, 0));
            TextView up = CgUi.smallButton(this, "▲", 1);
            up.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    move(index, -1);
                }
            });
            acts.addView(up, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
            TextView down = CgUi.smallButton(this, "▼", 1);
            down.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    move(index, 1);
                }
            });
            acts.addView(down, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
            TextView rm = CgUi.smallButton(this, "إزالة التمييز", 2);
            rm.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        JSONObject ch = new JSONObject();
                        ch.put("featured", false);
                        CgOps.updateItem(CgFeaturedActivity.this, CgData.s(it, "id"), ch, "إزالة التمييز", new Runnable() {
                            @Override
                            public void run() {
                                render();
                            }
                        });
                    } catch (Exception ignored) { }
                }
            });
            acts.addView(rm, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            wrap.addView(acts, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
            wrap.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));
            c.addView(wrap);
        }
    }

    private void open(JSONObject it) {
        Intent in = new Intent(this, CgData.kind(it) == CgData.K_SERIES ? CgSeriesActivity.class : CgItemActivity.class);
        in.putExtra("id", CgData.s(it, "id"));
        startActivity(in);
    }

    private void move(int index, int delta) {
        int to = index + delta;
        if (to < 0 || to >= featured.size()) return;
        JSONObject x = featured.remove(index);
        featured.add(to, x);
        final JSONArray ids = new JSONArray();
        for (int i = 0; i < featured.size(); i++) ids.put(CgData.s(featured.get(i), "id"));
        showLoading("جارٍ حفظ الترتيب...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("ids", ids);
                return CgHttp.require(CgHttp.apiPost("/admin/featured/reorder", b));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                final String undo = r.json.optString("undo_id", "");
                reloadData(new Runnable() {
                    @Override
                    public void run() {
                        render();
                        showUndo("تغيير ترتيب المميز", undo, new Runnable() {
                            @Override
                            public void run() {
                                render();
                            }
                        });
                    }
                });
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(CgFeaturedActivity.this, "تعذر حفظ الترتيب", message);
                reloadData(new Runnable() {
                    @Override
                    public void run() {
                        render();
                    }
                });
            }
        });
    }

    private void addFlow() {
        CgUi.input(this, "ابحث عن فيلم أو مسلسل", "اكتب جزءاً من الاسم", "", false, new CgUi.Callback<String>() {
            @Override
            public void run(String q) {
                CgData.Filter f = new CgData.Filter();
                ArrayList<JSONObject> all = CgData.cur().query(q, f);
                final ArrayList<JSONObject> cands = new ArrayList<JSONObject>();
                for (int i = 0; i < all.size() && cands.size() < 15; i++) {
                    JSONObject it = all.get(i);
                    if (CgData.kind(it) == CgData.K_EPISODE || CgData.b(it, "featured")) continue;
                    cands.add(it);
                }
                if (cands.isEmpty()) {
                    CgUi.info(CgFeaturedActivity.this, "لا نتائج", "لا يوجد فيلم/مسلسل غير مميّز بهذا الاسم.");
                    return;
                }
                CharSequence[] labels = new CharSequence[cands.size()];
                for (int i = 0; i < cands.size(); i++) {
                    labels[i] = CgData.kindLabel(CgData.kind(cands.get(i))) + " • " + CgData.s(cands.get(i), "title");
                }
                CgUi.choose(CgFeaturedActivity.this, "اختر ما تريد تمييزه", labels, new CgUi.IntCb() {
                    @Override
                    public void run(int index) {
                        try {
                            JSONObject ch = new JSONObject();
                            ch.put("featured", true);
                            ch.put("featured_order", featured.size() + 1);
                            CgOps.updateItem(CgFeaturedActivity.this, CgData.s(cands.get(index), "id"), ch, "إضافة للمميز", new Runnable() {
                                @Override
                                public void run() {
                                    render();
                                }
                            });
                        } catch (Exception ignored) { }
                    }
                });
            }
        });
    }
}
