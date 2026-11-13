package com.my.newproject;

import android.app.AlertDialog;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Add / update a movie as a 5 step flow: search (TMDB) -> information -> media -> preview -> publish.
 * Drafts are saved automatically, duplicates are caught before anything is written, and an existing
 * movie turns the same flow into an update.
 */
public class CxMovieActivity extends CxBase {

    private static final String[] STEPS = {"البحث", "المعلومات", "الوسائط", "المعاينة", "النشر"};

    private JSONObject st = new JSONObject();
    private JSONObject orig;
    private String editId = "";
    private int step;
    private CxDraft.Auto auto;
    private CxSearchBox search;
    private CxContentForm form;
    private CxUi.Field video;
    private CxUi.Field subtitle;
    private boolean videoChecked;
    private boolean skipVideoCheck;

    @Override
    protected String screenTitle() {
        return "إضافة فيلم";
    }

    @Override
    protected void onBuild() {
        initCx(STEPS);
        steps.onStep(new CgUi.IntCb() {
            @Override
            public void run(int index) {
                if (index == 0 && editId.length() > 0) return;
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
        editId = getIntent().getStringExtra("edit_id") == null ? "" : getIntent().getStringExtra("edit_id");
        String draftId = getIntent().getStringExtra("draft_id");
        int startStep = 0;
        if (editId.length() > 0) {
            orig = CgData.cur().byId(editId);
            if (orig == null) {
                page.addView(CgUi.empty(this, "الفيلم غير موجود", "ربما حُذف. ارجع وحاول مرة أخرى."));
                return;
            }
            bar.titleView.setText("تعديل فيلم");
            fillFromItem(orig);
            startStep = 1;
        } else if (draftId != null && draftId.length() > 0) {
            JSONObject d = CxDraft.load(this, draftId);
            if (d != null) {
                st = d.optJSONObject("data") == null ? new JSONObject() : d.optJSONObject("data");
                startStep = Math.max(0, Math.min(4, d.optInt("step", 0)));
                if (startStep >= 4) startStep = 3;
            }
        }
        video = null;
        auto = new CxDraft.Auto(this, "movie", draftId, new CxDraft.Source() {
            @Override
            public boolean worthSaving() {
                return editId.length() == 0 && (CxJ.s(st, "title").length() > 0 || CxJ.s(st, "tmdb_id").length() > 0 || CxJ.s(st, "video").length() > 0);
            }

            @Override
            public JSONObject collect() {
                if (form != null && step == 1) form.pull();
                return st;
            }

            @Override
            public String title() {
                String t = CxJ.s(st, "title");
                return t.length() > 0 ? t : "فيلم بدون اسم";
            }

            @Override
            public int step() {
                return step;
            }
        });
        showStep(startStep);
    }

    private void fillFromItem(JSONObject it) {
        CxJ.put(st, "title", CxJ.s(it, "title"));
        CxJ.put(st, "original_title", CxJ.s(it, "original_title"));
        CxJ.put(st, "description", CxJ.s(it, "description"));
        CxJ.put(st, "year", CxJ.s(it, "year"));
        CxJ.put(st, "category", CxJ.s(it, "category"));
        CxJ.put(st, "poster", CxJ.s(it, "poster"));
        CxJ.put(st, "backdrop", CxJ.s(it, "backdrop"));
        CxJ.put(st, "quality", CxJ.s(it, "quality"));
        CxJ.put(st, "slug", CxJ.s(it, "slug"));
        CxJ.put(st, "section_id", CxJ.s(it, "section_id"));
        CxJ.put(st, "section_name", CxJ.s(it, "section_name"));
        CxJ.put(st, "video", CxOps2.videoOf(it));
        CxJ.put(st, "subtitle", CxJ.s(it, "subtitle"));
        CxJ.put(st, "duration", CxJ.s(it, "duration"));
        CxJ.put(st, "tmdb_id", CxJ.s(it, "tmdb"));
        String imdb = CxJ.s(it, "imdb");
        if (imdb.toLowerCase(java.util.Locale.ROOT).matches("tt\\d{5,12}")) CxJ.put(st, "imdb_id", imdb);
        else {
            CxJ.put(st, "rating", imdb);
            CxJ.put(st, "imdb_id", CxJ.s(it, "imdb_id"));
        }
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
        int min = editId.length() > 0 ? 1 : 0;
        if (step > min) {
            showStep(step - 1);
            return;
        }
        if (auto != null) auto.flush();
        super.onBackPressed();
    }

    // ------------------------------------------------------------------------ steps

    private void showStep(int i) {
        if (form != null && step == 1) form.pull();
        step = i;
        if (search != null && i != 0) search.stop();
        newPage(i);
        if (i == 0) stepSearch();
        else if (i == 1) stepInfo();
        else if (i == 2) stepMedia();
        else if (i == 3) stepPreview();
        else stepPublish();
    }

    private void saveDraftButton() {
        if (editId.length() > 0) return;
        bottomButton("حفظ مسودة", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                auto.flush();
                CgUi.toast(CxMovieActivity.this, "تم حفظ المسودة. تجدها في «المسودات».");
                finish();
            }
        });
    }

    // step 0 ------------------------------------------------------------------------

    private void stepSearch() {
        if (editId.length() == 0) {
            final JSONObject latest = CxDraft.latest(this, "movie");
            if (latest != null && !latest.optString("id").equals(auto.id())) {
                page.addView(draftBanner(latest, new Runnable() {
                    @Override
                    public void run() {
                        CxNav.resumeDraft(CxMovieActivity.this, latest);
                        finish();
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        CxDraft.delete(CxMovieActivity.this, latest.optString("id"));
                        showStep(0);
                    }
                }));
            }
        }
        page.addView(CgUi.sectionTitle(this, "ابحث عن الفيلم في TMDB"));
        search = new CxSearchBox(this, "movie", new CgUi.Callback<JSONObject>() {
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
        task("جارٍ جلب بيانات الفيلم...", new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("type", "movie");
                b.put("tmdb_id", id);
                return CxApi.post("/admin/tmdb/details", b);
            }
        }, new Ok<JSONObject>() {
            @Override
            public void ok(JSONObject d) {
                applyDetails(d);
                JSONArray dups = d.optJSONArray("duplicates");
                if (dups != null && dups.length() > 0) showDuplicate(dups.optJSONObject(0), true);
                else showStep(1);
            }
        }, new Runnable() {
            @Override
            public void run() {
                loadDetails(card);
            }
        });
    }

    private void applyDetails(JSONObject d) {
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
        CxJ.put(st, "duration", CxJ.s(d, "duration"));
        CxJ.put(st, "slug", CxJ.s(d, "suggested_slug"));
        CxJ.put(st, "genres", d.optJSONArray("genres") == null ? new JSONArray() : d.optJSONArray("genres"));
        CxJ.put(st, "suggested_sections", d.optJSONArray("suggested_sections") == null ? new JSONArray() : d.optJSONArray("suggested_sections"));
        // VidBolt: build the player link from the TMDB id so the admin does not have to press anything
        String vb = CgVidbolt.movie(CxJ.s(st, "tmdb_id"));
        if (vb.length() > 0) {
            CxJ.put(st, "video", vb);
            videoChecked = true;
        }
    }

    /** Existing item found: [فتح] [تعديل الموجود] [إلغاء]. */
    private void showDuplicate(final JSONObject dup, final boolean fromSearch) {
        if (dup == null) return;
        final String id = CxJ.s(dup, "id");
        LinearLayout box = CgUi.vbox(this);
        box.setPadding(CgUi.dp(this, 16), CgUi.dp(this, 8), CgUi.dp(this, 16), CgUi.dp(this, 4));
        LinearLayout row = CgUi.hbox(this);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        CgImg.load(iv, CxJ.s(dup, "poster"), 200);
        row.addView(iv, new LinearLayout.LayoutParams(CgUi.dp(this, 64), CgUi.dp(this, 96)));
        LinearLayout col = CgUi.vbox(this);
        col.addView(CgUi.text(this, CxJ.s(dup, "title"), 15, CgCfg.TEXT, true));
        col.addView(CgUi.muted(this, "السنة: " + (CxJ.s(dup, "year").length() > 0 ? CxJ.s(dup, "year") : "—")));
        TextView idv = CgUi.muted(this, "ID: " + id);
        CgUi.ltr(idv);
        col.addView(idv);
        JSONArray reasons = dup.optJSONArray("reasons");
        StringBuilder rs = new StringBuilder();
        for (int i = 0; reasons != null && i < reasons.length(); i++) {
            if (rs.length() > 0) rs.append(" + ");
            rs.append(reasonText(reasons.optString(i)));
        }
        if (rs.length() > 0) col.addView(CgUi.text(this, rs.toString(), 12, CgCfg.WARN, true));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        clp.setMargins(CgUi.dp(this, 12), 0, 0, 0);
        row.addView(col, clp);
        box.addView(row);
        final AlertDialog dlg = CgUi.dialog(this).setTitle("هذا الفيلم موجود بالفعل").setView(box).create();
        LinearLayout actions = CgUi.hbox(this);
        TextView open = CgUi.smallButton(this, "فتح", 1);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                CxNav.openItem(CxMovieActivity.this, id);
            }
        });
        TextView edit = CgUi.smallButton(this, "تعديل الموجود", 0);
        edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                if (auto != null) auto.finish();
                CxNav.editMovie(CxMovieActivity.this, id);
                finish();
            }
        });
        TextView cancel = CgUi.smallButton(this, "إلغاء", 3);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
                if (!fromSearch) return;
                st = new JSONObject();
                showStep(0);
            }
        });
        actions.addView(open, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        LinearLayout.LayoutParams m = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1.3f);
        m.setMargins(CgUi.dp(this, 6), 0, CgUi.dp(this, 6), 0);
        actions.addView(edit, m);
        actions.addView(cancel, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        box.addView(actions, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 14, 0, 4));
        dlg.show();
    }

    private static String reasonText(String r) {
        if ("tmdb".equals(r)) return "نفس TMDB";
        if ("imdb".equals(r)) return "نفس IMDb";
        if ("slug".equals(r)) return "نفس الـ Slug";
        if ("title_year".equals(r)) return "نفس الاسم والسنة";
        return r;
    }

    // step 1 ------------------------------------------------------------------------

    private void stepInfo() {
        if (editId.length() > 0) {
            page.addView(CxUi.notice(this, CxUi.WARN, "وضع التحديث: ستعدّل الفيلم الموجود «" + CxJ.s(orig, "title") + "» (ID: " + editId + "). لن يُنشأ فيلم جديد."));
        }
        form = new CxContentForm(this, st, false, editId, new Runnable() {
            @Override
            public void run() {
                touch();
            }
        });
        form.build(page);
        if (editId.length() == 0) saveDraftButton();
        bottomButton("التالي: الوسائط ←", 0, 1.6f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (form.validate()) showStep(2);
            }
        });
    }

    // step 2 ------------------------------------------------------------------------

    private void stepMedia() {
        form = null;
        CxUi.Section sec = new CxUi.Section(this, "الفيديو", "رابط ملف الفيلم (R2 أو أي رابط مباشر)", false, true);
        video = new CxUi.Field(this, "رابط الفيديو", "https://.../movie.mp4", CxUi.T_URL, true);
        video.set(CxJ.s(st, "video"));
        video.onChange(new Runnable() {
            @Override
            public void run() {
                CxJ.put(st, "video", video.text());
                videoChecked = false;
                touch();
            }
        });
        sec.add(video.view);
        LinearLayout upBox = CgUi.vbox(this);
        CgMediaHook.addVideoButton(this, upBox, video, mediaTarget());
        sec.add(upBox);
        LinearLayout vbrow = CgUi.hbox(this);
        TextView vbt = CgUi.smallButton(this, "🎬  رابط VidBolt تلقائي", 1);
        vbt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String tmdb = CxJ.s(st, "tmdb_id");
                if (tmdb.length() == 0) {
                    video.error("أدخل TMDB ID في خطوة المعلومات أولاً (أو ابحث بالاسم).");
                    return;
                }
                String url = CgVidbolt.movie(tmdb);
                if (url.length() == 0) {
                    video.error("TMDB ID غير صالح.");
                    return;
                }
                video.set(url);
                CxJ.put(st, "video", url);
                videoChecked = true;
                video.ok("تم إنشاء رابط VidBolt للمشغل.");
                touch();
            }
        });
        vbrow.addView(vbt, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        sec.add(vbrow);
        LinearLayout vrow = CgUi.hbox(this);
        TextView vt = CgUi.smallButton(this, "اختبار الفيديو", 1);
        vt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CxMedia.test(CxMovieActivity.this, "video", video.text(), video, new CxMedia.Result() {
                    @Override
                    public void done(boolean ok, JSONObject r) {
                        videoChecked = ok;
                    }
                });
            }
        });
        vrow.addView(vt, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        sec.add(vrow);
        page.addView(sec);

        CxUi.Section sub = new CxUi.Section(this, "الترجمة", "اختياري — غياب الترجمة ليس خطأً", false, true);
        subtitle = new CxUi.Field(this, "رابط الترجمة (.srt / .vtt)", "https://.../movie.srt", CxUi.T_URL, false);
        subtitle.set(CxJ.s(st, "subtitle"));
        subtitle.onChange(new Runnable() {
            @Override
            public void run() {
                CxJ.put(st, "subtitle", subtitle.text());
                touch();
            }
        });
        sub.add(subtitle.view);
        if (subtitle.text().length() == 0) sub.add(CgUi.badge(this, "بدون ترجمة", CgCfg.GRAY));
        LinearLayout srow = CgUi.hbox(this);
        TextView stb = CgUi.smallButton(this, "اختبار الترجمة", 1);
        stb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CxMedia.test(CxMovieActivity.this, "subtitle", subtitle.text(), subtitle, null);
            }
        });
        srow.addView(stb, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        CgMediaHook.addSubtitleButton(this, srow, subtitle, mediaTarget());
        CxSubsActivity.addButton(this, srow, subtitle, new CxSubsActivity.Provider() {
            @Override
            public JSONObject target() {
                JSONObject t = new JSONObject();
                CxJ.put(t, "type", "movie");
                CxJ.put(t, "title", CxJ.s(st, "title"));
                CxJ.put(t, "tmdb_id", CxJ.s(st, "tmdb_id"));
                CxJ.put(t, "imdb_id", CxJ.s(st, "imdb_id"));
                CxJ.put(t, "year", CxJ.s(st, "year"));
                CxJ.put(t, "slug", mediaTarget().slug());
                CxJ.put(t, "release", CxJ.s(st, "quality"));
                return t;
            }
        });
        sub.add(srow);
        page.addView(sub);

        TextView player = CgUi.button(this, "▶  معاينة بالمشغل (فيديو + ترجمة)", 1);
        player.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (video.text().length() == 0) {
                    video.error("أدخل رابط الفيديو أولاً.");
                    return;
                }
                CxMedia.openPlayer(CxMovieActivity.this, CxJ.s(st, "title"), video.text(), subtitle.text());
            }
        });
        page.addView(player, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));

        bottomButton("→ السابق", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collectMedia();
                showStep(1);
            }
        });
        if (editId.length() == 0) saveDraftButton();
        bottomButton("التالي: المعاينة ←", 0, 1.6f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collectMedia();
                String vu = video.text();
                if (vu.length() == 0) {
                    video.error("رابط الفيديو مطلوب للنشر. يمكنك حفظ مسودة والعودة لاحقاً.");
                    return;
                }
                if (!CxJ.isUrl(vu)) {
                    video.error("الرابط يجب أن يبدأ بـ http:// أو https://");
                    return;
                }
                String su = subtitle.text();
                if (su.length() > 0 && !CxJ.isUrl(su)) {
                    subtitle.error("رابط الترجمة يجب أن يبدأ بـ http:// أو https://");
                    return;
                }
                showStep(3);
            }
        });
    }

    /** What the direct-upload screen needs to know about this movie (read when the button is pressed). */
    private CgMediaHook.Target mediaTarget() {
        return new CgMediaHook.Target() {
            @Override
            public String kind() {
                return "movie";
            }

            @Override
            public String slug() {
                return CxJ.s(st, "slug");
            }

            @Override
            public String title() {
                return CxJ.s(st, "title");
            }

            @Override
            public String poster() {
                return CxJ.s(st, "poster");
            }

            @Override
            public int season() {
                return 0;
            }

            @Override
            public int episode() {
                return 0;
            }
        };
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        CgMediaHook.onResult(this, req, res, data);
    }

    private void collectMedia() {
        if (video != null) CxJ.put(st, "video", video.text());
        if (subtitle != null) CxJ.put(st, "subtitle", subtitle.text());
    }

    // step 3 ------------------------------------------------------------------------

    private void stepPreview() {
        page.addView(CgUi.sectionTitle(this, "فحص الاكتمال"));
        page.addView(CxUi.notice(this, CxUi.GOOD, "رابط الفيديو موجود."));
        if (CxJ.s(st, "subtitle").length() == 0) page.addView(CxUi.notice(this, CxUi.INFO, "بدون ترجمة — يمكن إضافتها لاحقاً (ليس خطأً)."));
        else page.addView(CxUi.notice(this, CxUi.GOOD, "الترجمة مرتبطة."));
        if (CxJ.s(st, "section_id").length() == 0) page.addView(CxUi.notice(this, CxUi.WARN, "لم يُختر قسم: لن يظهر الفيلم في الصفحة الرئيسية."));
        if (CxJ.s(st, "poster").length() == 0) page.addView(CxUi.notice(this, CxUi.WARN, "لا يوجد بوستر."));
        if (CxJ.s(st, "backdrop").length() == 0) page.addView(CxUi.notice(this, CxUi.INFO, "لا توجد خلفية — لن تظهر شريحة الكاروسيل بصورة الخلفية الأصلية."));
        if (CxJ.s(st, "description").length() == 0) page.addView(CxUi.notice(this, CxUi.WARN, "لا يوجد وصف."));
        if (CxJ.s(st, "slug").length() == 0) page.addView(CxUi.notice(this, CxUi.INFO, "بدون Slug."));
        bottomButton("→ السابق", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStep(2);
            }
        });
        bottomButton("متابعة للنشر ←", 0, 1.6f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStep(4);
            }
        });
    }

    // step 4 ------------------------------------------------------------------------

    private void stepPublish() {
        final boolean update = editId.length() > 0;
        LinearLayout c = CgUi.card(this);
        c.addView(CgUi.text(this, update ? "جاهز لحفظ التحديث" : "جاهز للنشر", 16, CgCfg.TEXT, true));
        c.addView(CgUi.muted(this, CxJ.s(st, "title") + (CxJ.s(st, "year").length() > 0 ? " (" + CxJ.s(st, "year") + ")" : "")));
        c.addView(CgUi.space(this, 8));
        c.addView(CgUi.muted(this, "المراحل: فحص التكرار ← التحقق من الروابط ← الحفظ ← التحقق من الحفظ. لا يُكتب شيء قبل نجاح الفحص، ويمكنك التراجع بعد النشر."));
        if (update) {
            JSONObject ch = changes();
            c.addView(CgUi.space(this, 8));
            c.addView(CgUi.text(this, ch.length() == 0 ? "لا توجد تغييرات للحفظ." : "عدد الحقول المعدّلة: " + ch.length(), 13, ch.length() == 0 ? CgCfg.WARN : CgCfg.ACCENT, true));
        }
        c.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        page.addView(c);
        bottomButton("→ السابق", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStep(3);
            }
        });
        bottomButton(update ? "حفظ التحديث" : "نشر الفيلم", 0, 1.6f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                skipVideoCheck = false;
                publish();
            }
        });
    }

    private String[] mapKeys() {
        return new String[]{"title", "original_title", "description", "year", "category", "poster", "backdrop", "quality", "slug",
                "section_id", "section_name", "imdb_id", "duration", "subtitle", "video"};
    }

    private String stateValue(String key) {
        return CxJ.s(st, key);
    }

    private String itemValue(String key) {
        if ("video".equals(key)) return CxOps2.videoOf(orig);
        return CxJ.s(orig, key);
    }

    /** Fields that differ from the stored movie (update mode). */
    private JSONObject changes() {
        JSONObject ch = new JSONObject();
        if (orig == null) return ch;
        String[] keys = mapKeys();
        for (int i = 0; i < keys.length; i++) {
            String k = keys[i];
            String nv = stateValue(k);
            String ov = itemValue(k);
            if (!nv.equals(ov)) CxJ.put(ch, k, nv);
        }
        String tm = stateValue("tmdb_id");
        if (tm.length() > 0 && !tm.equals(CxJ.s(orig, "tmdb"))) CxJ.put(ch, "tmdb", tm);
        String rating = stateValue("rating");
        String oi = CxJ.s(orig, "imdb");
        if (rating.length() > 0 && !oi.toLowerCase(java.util.Locale.ROOT).matches("tt\\d{5,12}") && !rating.equals(oi)) CxJ.put(ch, "imdb", rating);
        return ch;
    }

    private JSONObject createFields() {
        JSONObject f = new JSONObject();
        String[] keys = mapKeys();
        for (int i = 0; i < keys.length; i++) {
            String v = stateValue(keys[i]);
            if (v.length() > 0) CxJ.put(f, keys[i], v);
        }
        if (stateValue("tmdb_id").length() > 0) CxJ.put(f, "tmdb", stateValue("tmdb_id"));
        if (stateValue("rating").length() > 0) CxJ.put(f, "imdb", stateValue("rating"));
        if (stateValue("video").length() > 0) CxJ.put(f, "link", stateValue("video"));
        return f;
    }

    private void publish() {
        collectMedia();
        final boolean update = editId.length() > 0;
        final boolean skip = skipVideoCheck;
        final CxStages sg = CxStages.show(this, update ? "تحديث الفيلم" : "نشر الفيلم",
                new String[]{"فحص التكرار", "التحقق من الروابط", "الحفظ في قاعدة البيانات", "التحقق من الحفظ", "اكتمل"});
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                // 1) duplicates (new movies only; the server checks again inside the write)
                sg.stage(0, "");
                if (!update) {
                    JSONObject q = new JSONObject();
                    q.put("type", "movie");
                    q.put("tmdb", stateValue("tmdb_id"));
                    q.put("imdb", stateValue("imdb_id"));
                    q.put("slug", stateValue("slug"));
                    q.put("title", stateValue("title"));
                    q.put("year", stateValue("year"));
                    JSONObject r = CxApi.post("/admin/duplicates/check", q);
                    JSONArray matches = r.optJSONArray("matches");
                    for (int mi = 0; matches != null && mi < matches.length(); mi++) {
                        JSONObject mm = matches.getJSONObject(mi);
                        JSONArray why = mm.optJSONArray("reasons");
                        boolean real = false;
                        for (int wi = 0; why != null && wi < why.length(); wi++) {
                            if (!"slug".equals(why.optString(wi))) real = true;
                        }
                        if (real) {
                            JSONObject extra = new JSONObject();
                            extra.put("existing", mm);
                            throw new CxApi.Failure("هذا الفيلم موجود بالفعل.", "DUPLICATE_CONTENT", extra, 409);
                        }
                    }
                }
                // 2) links (VidBolt embeds need no HTTP file check)
                sg.stage(1, stateValue("video"));
                if (!skip && !CgVidbolt.is(stateValue("video"))) {
                    JSONObject b = new JSONObject();
                    JSONArray arr = new JSONArray();
                    arr.put(new JSONObject().put("id", "v").put("kind", "video").put("url", stateValue("video")));
                    b.put("items", arr);
                    JSONObject d = CxApi.post("/admin/check-urls", b);
                    JSONArray res = d.optJSONArray("results");
                    if (res != null && res.length() > 0 && !"ok".equals(res.getJSONObject(0).optString("status"))) {
                        throw new CxApi.Failure("رابط الفيديو لا يعمل: " + CgData.checkLabel(res.getJSONObject(0).optString("status")), "VIDEO_BAD", null, 0);
                    }
                }
                // 3) write
                sg.stage(2, "");
                JSONObject saved;
                if (update) {
                    JSONObject ch = changes();
                    if (ch.length() == 0) throw new CxApi.Failure("لا توجد تغييرات للحفظ.", "NO_CHANGES", null, 0);
                    JSONObject b = new JSONObject();
                    b.put("id", editId);
                    b.put("changes", ch);
                    saved = CxApi.post("/admin/item/update", b);
                } else {
                    JSONObject b = new JSONObject();
                    b.put("type", "movie");
                    b.put("fields", createFields());
                    saved = CxApi.post("/admin/item/create", b);
                }
                JSONObject item = saved.optJSONObject("item");
                if (item == null) throw new CxApi.Failure("لم يؤكد الخادم الحفظ. أعد المحاولة.", "NO_ITEM", null, 0);
                // 4) verification (informational; the write itself is atomic with an ETag check)
                sg.stage(3, "");
                String id = CxJ.s(item, "id");
                boolean verified = false;
                try {
                    JSONObject q = new JSONObject();
                    q.put("type", "movie");
                    q.put("title", CxJ.s(item, "title"));
                    q.put("year", CxJ.s(item, "year"));
                    q.put("tmdb", CxJ.s(item, "tmdb"));
                    q.put("slug", CxJ.s(item, "slug"));
                    JSONArray m = CxApi.post("/admin/duplicates/check", q).optJSONArray("matches");
                    for (int i = 0; m != null && i < m.length(); i++) {
                        if (id.equals(m.getJSONObject(i).optString("id"))) verified = true;
                    }
                } catch (Exception ignored) { }
                sg.stage(4, "");
                JSONObject out = new JSONObject();
                out.put("item", item);
                out.put("undo_id", saved.optString("undo_id", ""));
                out.put("verified", verified);
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
                CxNav.success(CxMovieActivity.this, "movie", CxJ.s(item, "id"), CxJ.s(item, "title"), CxJ.s(item, "poster"),
                        update ? "updated" : "created", r.optString("undo_id"), "", 0, 0);
                finish();
            }

            @Override
            public void fail(CxApi.Failure f) {
                handlePublishFailure(sg, f);
            }
        });
    }

    private void handlePublishFailure(final CxStages sg, CxApi.Failure f) {
        if (f.is("VIDEO_BAD")) {
            sg.dismiss();
            CgUi.confirm(this, "الفيديو لا يعمل", f.getMessage() + "\nهل تريد النشر رغم ذلك؟", "نشر رغم ذلك", true, new Runnable() {
                @Override
                public void run() {
                    skipVideoCheck = true;
                    publish();
                }
            });
        } else if (f.is("DUPLICATE_CONTENT")) {
            sg.dismiss();
            showDuplicate(f.existing(), false);
        } else if (f.is("DUPLICATE_SLUG")) {
            sg.dismiss();
            snack(f.getMessage(), CxUi.ERROR);
            showStep(1);
        } else if (f.is("NO_CHANGES")) {
            sg.dismiss();
            snack(f.getMessage(), CxUi.WARN);
        } else {
            sg.fail(f.getMessage(), new Runnable() {
                @Override
                public void run() {
                    publish();
                }
            });
        }
    }
}
