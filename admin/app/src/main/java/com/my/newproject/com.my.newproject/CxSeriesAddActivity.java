package com.my.newproject;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Creates ONLY the base series record (once). Episodes and seasons are added later and link to it through
 * series_id + season + episode, so a series is never duplicated per season.
 */
public class CxSeriesAddActivity extends CxBase {

    private static final String[] STEPS = {"البحث", "المعلومات", "النشر"};

    private JSONObject st = new JSONObject();
    private int step;
    private CxDraft.Auto auto;
    private CxSearchBox search;
    private CxContentForm form;

    @Override
    protected String screenTitle() {
        return "إضافة مسلسل";
    }

    @Override
    protected void onBuild() {
        initCx(STEPS);
        steps.onStep(new CgUi.IntCb() {
            @Override
            public void run(int index) {
                showStep(index);
            }
        });
        ensureData(new Runnable() {
            @Override
            public void run() {
                start();
            }
        });
    }

    private void start() {
        String draftId = getIntent().getStringExtra("draft_id");
        int startStep = 0;
        if (draftId != null && draftId.length() > 0) {
            JSONObject d = CxDraft.load(this, draftId);
            if (d != null) {
                st = d.optJSONObject("data") == null ? new JSONObject() : d.optJSONObject("data");
                startStep = Math.min(1, d.optInt("step", 0));
            }
        }
        auto = new CxDraft.Auto(this, "series", draftId, new CxDraft.Source() {
            @Override
            public boolean worthSaving() {
                return CxJ.s(st, "title").length() > 0 || CxJ.s(st, "tmdb_id").length() > 0;
            }

            @Override
            public JSONObject collect() {
                if (form != null && step == 1) form.pull();
                return st;
            }

            @Override
            public String title() {
                String t = CxJ.s(st, "title");
                return t.length() > 0 ? t : "مسلسل بدون اسم";
            }

            @Override
            public int step() {
                return step;
            }
        });
        showStep(startStep);
    }

    private void touch() {
        if (auto != null) auto.touch();
    }

    @Override
    protected void onPause() {
        if (auto != null) auto.flush();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (step > 0) {
            showStep(step - 1);
            return;
        }
        if (auto != null) auto.flush();
        super.onBackPressed();
    }

    private void showStep(int i) {
        if (form != null && step == 1) form.pull();
        step = i;
        if (search != null && i != 0) search.stop();
        newPage(i);
        if (i == 0) stepSearch();
        else if (i == 1) stepInfo();
        else stepPublish();
    }

    private void stepSearch() {
        final JSONObject latest = CxDraft.latest(this, "series");
        if (latest != null && !latest.optString("id").equals(auto.id())) {
            page.addView(draftBanner(latest, new Runnable() {
                @Override
                public void run() {
                    CxNav.resumeDraft(CxSeriesAddActivity.this, latest);
                    finish();
                }
            }, new Runnable() {
                @Override
                public void run() {
                    CxDraft.delete(CxSeriesAddActivity.this, latest.optString("id"));
                    showStep(0);
                }
            }));
        }
        page.addView(CxUi.notice(this, CxUi.INFO, "هذه الصفحة تنشئ سجل المسلسل الأساسي مرة واحدة فقط. بعدها أضف الحلقات أو المواسم من «إضافة حلقة» و«إضافة موسم كامل»."));
        page.addView(CgUi.sectionTitle(this, "ابحث عن المسلسل في TMDB"));
        search = new CxSearchBox(this, "series", new CgUi.Callback<JSONObject>() {
            @Override
            public void run(JSONObject card) {
                loadDetails(card);
            }
        });
        page.addView(search.view);
        TextView manual = CgUi.button(this, "إدخال يدوي بدون TMDB", 1);
        manual.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                st = new JSONObject();
                showStep(1);
            }
        });
        page.addView(manual, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        setBottomVisible(false);
    }

    private void loadDetails(final JSONObject card) {
        final String id = String.valueOf(card.optInt("tmdb_id"));
        task("جارٍ جلب بيانات المسلسل...", new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("type", "series");
                b.put("tmdb_id", id);
                return CxApi.post("/admin/tmdb/details", b);
            }
        }, new Ok<JSONObject>() {
            @Override
            public void ok(JSONObject d) {
                st = new JSONObject();
                CxJ.put(st, "tmdb_id", String.valueOf(d.optInt("tmdb_id")));
                CxJ.put(st, "imdb_id", CxJ.s(d, "imdb_id"));
                CxJ.put(st, "title", CxJ.s(d, "title"));
                CxJ.put(st, "original_title", CxJ.s(d, "original_title"));
                CxJ.put(st, "description", CxJ.s(d, "description"));
                CxJ.put(st, "year", CxJ.s(d, "year"));
                CxJ.put(st, "category", CxJ.s(d, "category"));
                CxJ.put(st, "rating", CxJ.s(d, "rating_text"));
                CxJ.put(st, "poster", CxJ.s(d, "poster"));
                CxJ.put(st, "backdrop", CxJ.s(d, "backdrop"));
                CxJ.put(st, "slug", CxJ.s(d, "suggested_slug"));
                CxJ.put(st, "genres", d.optJSONArray("genres") == null ? new JSONArray() : d.optJSONArray("genres"));
                CxJ.put(st, "suggested_sections", d.optJSONArray("suggested_sections") == null ? new JSONArray() : d.optJSONArray("suggested_sections"));
                CxJ.put(st, "season_count", d.optInt("season_count"));
                JSONArray dups = d.optJSONArray("duplicates");
                if (dups != null && dups.length() > 0) showExisting(dups.optJSONObject(0), true);
                else showStep(1);
            }
        }, new Runnable() {
            @Override
            public void run() {
                loadDetails(card);
            }
        });
    }

    private void showExisting(final JSONObject dup, final boolean fromSearch) {
        if (dup == null) return;
        final String id = CxJ.s(dup, "id");
        final JSONObject header = CgData.cur() == null ? null : CgData.cur().byId(id);
        CxDup.show(this, "هذا المسلسل موجود بالفعل", dup, new String[]{"فتح", "إضافة حلقات إليه", "إلغاء"}, new Runnable[]{
                new Runnable() {
                    @Override
                    public void run() {
                        CxNav.openItem(CxSeriesAddActivity.this, id);
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        if (auto != null) auto.finish();
                        String key = header == null ? id : CgData.seriesKey(header);
                        CxNav.addEpisode(CxSeriesAddActivity.this, key, 0, 0);
                        finish();
                    }
                },
                new Runnable() {
                    @Override
                    public void run() {
                        if (fromSearch) {
                            st = new JSONObject();
                            showStep(0);
                        }
                    }
                }});
    }

    private void stepInfo() {
        form = new CxContentForm(this, st, true, "", new Runnable() {
            @Override
            public void run() {
                touch();
            }
        });
        form.build(page);
        bottomButton("→ السابق", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStep(0);
            }
        });
        bottomButton("حفظ مسودة", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                auto.flush();
                CgUi.toast(CxSeriesAddActivity.this, "تم حفظ المسودة.");
                finish();
            }
        });
        bottomButton("التالي ←", 0, 1.4f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (form.validate()) showStep(2);
            }
        });
    }

    private void stepPublish() {
        form = null;
        page.addView(CgUi.sectionTitle(this, "هكذا سيظهر المسلسل"));
        page.addView(CxPreview.contentPage(this, st, true, null));
        LinearLayout c = CgUi.card(this);
        c.addView(CgUi.text(this, "ما الذي سيحدث؟", 14.5f, CgCfg.TEXT, true));
        c.addView(CgUi.muted(this, "• يُنشأ سجل مسلسل واحد فقط: " + (CxJ.s(st, "tmdb_id").length() > 0 ? "series_" + CxJ.s(st, "tmdb_id") : "بمعرّف مبني على الـ Slug")
                + "\n• الحلقات تُضاف لاحقاً وترتبط به عبر series_id + الموسم + الحلقة\n• لن يُنشأ نسخة منه لكل موسم"));
        c.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 10));
        page.addView(c);
        if (CxJ.s(st, "section_id").length() == 0) page.addView(CxUi.notice(this, CxUi.WARN, "لم يُختر قسم: ستحتاج لتحديده لاحقاً حتى يظهر المسلسل في الصفحة الرئيسية."));
        if (CxJ.s(st, "slug").length() == 0) page.addView(CxUi.notice(this, CxUi.INFO, "بدون Slug لن تستطيع ربط مجلد R2 بسهولة لإضافة موسم كامل."));
        bottomButton("→ السابق", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStep(1);
            }
        });
        bottomButton("إنشاء المسلسل", 0, 1.6f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                publish();
            }
        });
    }

    private void publish() {
        final CxStages sg = CxStages.show(this, "إنشاء المسلسل", new String[]{"فحص التكرار", "إنشاء السجل", "التحقق", "اكتمل"});
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                sg.stage(0, "");
                JSONObject b = new JSONObject();
                boolean tmdb = CxJ.s(st, "tmdb_id").length() > 0;
                JSONObject saved;
                sg.stage(1, "");
                if (tmdb) {
                    b.put("tmdb_id", CxJ.s(st, "tmdb_id"));
                    String[] keys = {"title", "section_id", "section_name", "slug", "poster", "backdrop", "description", "category", "quality",
                            "year", "original_title", "imdb_id"};
                    for (int i = 0; i < keys.length; i++) {
                        if (CxJ.s(st, keys[i]).length() > 0) b.put(keys[i], CxJ.s(st, keys[i]));
                    }
                    saved = CxApi.post("/admin/series/create", b);
                } else {
                    JSONObject f = new JSONObject();
                    String slugText = CxJ.s(st, "slug");
                    String idPart = slugText.length() > 0 ? slugText : CgData.slugify(CxJ.s(st, "title"));
                    if (idPart == null || idPart.length() == 0) idPart = Long.toString(System.currentTimeMillis(), 36);
                    String baseId = "series_" + idPart;
                    f.put("id", baseId);
                    f.put("series_id", baseId);
                    String[] keys = {"title", "original_title", "description", "year", "category", "poster", "backdrop", "quality", "slug",
                            "section_id", "section_name", "imdb_id"};
                    for (int i = 0; i < keys.length; i++) {
                        if (CxJ.s(st, keys[i]).length() > 0) f.put(keys[i], CxJ.s(st, keys[i]));
                    }
                    if (CxJ.s(st, "poster").length() > 0) f.put("series_poster", CxJ.s(st, "poster"));
                    b.put("type", "series");
                    b.put("fields", f);
                    saved = CxApi.post("/admin/item/create", b);
                }
                JSONObject item = saved.optJSONObject("item");
                if (item == null) throw new CxApi.Failure("لم يؤكد الخادم إنشاء المسلسل. أعد المحاولة.", "NO_ITEM", null, 0);
                sg.stage(2, "");
                sg.stage(3, "");
                JSONObject out = new JSONObject();
                out.put("item", item);
                out.put("undo_id", saved.optString("undo_id", ""));
                return out;
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                sg.dismiss();
                JSONObject item = r.optJSONObject("item");
                if (auto != null) auto.finish();
                CgData d = CgData.cur();
                if (d != null && item != null) d.replaceItem(item);
                CxNav.success(CxSeriesAddActivity.this, "series", CxJ.s(item, "id"), CxJ.s(item, "title"), CxJ.s(item, "poster"),
                        "created", r.optString("undo_id"), CgData.seriesKey(item), 0, 0);
                finish();
            }

            @Override
            public void fail(CxApi.Failure f) {
                if (f.is("DUPLICATE_SERIES") || f.is("DUPLICATE_CONTENT")) {
                    sg.dismiss();
                    showExisting(f.existing(), false);
                } else if (f.is("DUPLICATE_SLUG")) {
                    sg.dismiss();
                    snack(f.getMessage(), CxUi.ERROR);
                    showStep(1);
                } else {
                    sg.fail(f.getMessage(), new Runnable() {
                        @Override
                        public void run() {
                            publish();
                        }
                    });
                }
            }
        });
    }
}
