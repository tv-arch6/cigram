package com.my.newproject;

import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Add or edit one carousel slide: pick existing content (searchable), choose the image (original backdrop, poster or
 * a gallery image), preview the slide as it looks in the app, optional start/end schedule, position, duplicate guard.
 */
public class CxCarouselEditActivity extends CxBase {

    private static final int REQ_IMAGE = 7701;

    private JSONObject st = new JSONObject();
    private boolean edit;
    private boolean touched;
    private JSONObject existingSlide;
    private JSONArray carousel = new JSONArray();
    private boolean carouselKnown;
    private Bitmap customBitmap;
    private String customUrl = "";
    private String uploadedUrl = "";
    private Bitmap uploadedFor;
    private CxDraft.Auto auto;

    private CxPreview.Slide slide;
    private LinearLayout contentBox;
    private LinearLayout dupBox;
    private LinearLayout imageBox;
    private LinearLayout scheduleBox;
    private LinearLayout positionBox;
    private TextView ratioNote;
    private TextView scheduleMsg;

    @Override
    protected String screenTitle() {
        return "شريحة الكاروسيل";
    }

    @Override
    protected void onBuild() {
        initCx(null);
        page.addView(CxUi.skeleton(this, 3));
        setBottomVisible(false);
        ensureData(new Runnable() {
            @Override
            public void run() {
                loadCarousel();
            }
        });
    }

    private void loadCarousel() {
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                return CxApi.get("/admin/carousel");
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                carousel = CxJ.arr(r, "items");
                carouselKnown = true;
                start();
            }

            @Override
            public void fail(CxApi.Failure f) {
                carouselKnown = false;
                start();
            }
        });
    }

    private JSONObject slideOf(String contentId) {
        for (int i = 0; i < carousel.length(); i++) {
            JSONObject o = CxJ.at(carousel, i);
            if (contentId.equals(CxJ.firstNonEmpty(CxJ.s(o, "content_id"), CxJ.s(o, "id")))) return CxJ.put(o, "_pos", i + 1);
        }
        return null;
    }

    private void start() {
        edit = getIntent().getBooleanExtra("edit", false);
        String draftId = getIntent().getStringExtra("draft_id");
        String cid = getIntent().getStringExtra("content_id");
        if (draftId != null && draftId.length() > 0) {
            JSONObject d = CxDraft.load(this, draftId);
            if (d != null && d.optJSONObject("data") != null) st = d.optJSONObject("data");
        }
        if (cid != null && cid.length() > 0) CxJ.put(st, "content_id", cid);
        if (CxJ.s(st, "position").length() == 0) CxJ.put(st, "position", edit ? "keep" : "first");
        if (edit) {
            JSONObject ex = slideOf(CxJ.s(st, "content_id"));
            if (ex != null) {
                existingSlide = ex;
                prefillFromSlide(ex);
            } else {
                edit = false;
            }
        }
        if (CxJ.s(st, "image_mode").length() == 0) CxJ.put(st, "image_mode", "backdrop");
        auto = new CxDraft.Auto(this, "carousel", draftId, new CxDraft.Source() {
            @Override
            public boolean worthSaving() {
                return !edit && touched && CxJ.s(st, "content_id").length() > 0;
            }

            @Override
            public JSONObject collect() {
                return st;
            }

            @Override
            public String title() {
                JSONObject it = item();
                return it == null ? "شريحة كاروسيل" : "كاروسيل: " + CgData.s(it, "title");
            }

            @Override
            public int step() {
                return 0;
            }
        });
        build();
    }

    private void prefillFromSlide(JSONObject ex) {
        CxJ.put(st, "start_at", CxJ.s(ex, "start_at"));
        CxJ.put(st, "end_at", CxJ.s(ex, "end_at"));
        String img = CxJ.s(ex, "landscape_poster");
        JSONObject it = item();
        String bd = it == null ? "" : CxJ.s(it, "backdrop");
        String pst = it == null ? "" : CxOps2.posterOf(it);
        if (ex.optBoolean("custom_image", false) || (img.length() > 0 && !img.equals(bd) && !img.equals(pst))) {
            CxJ.put(st, "image_mode", "custom");
            customUrl = img;
        } else if (img.equals(bd) && bd.length() > 0) {
            CxJ.put(st, "image_mode", "backdrop");
        } else {
            CxJ.put(st, "image_mode", "poster");
        }
    }

    private JSONObject item() {
        CgData d = CgData.cur();
        String id = CxJ.s(st, "content_id");
        if (d == null || id.length() == 0) return null;
        JSONObject it = d.byId(id);
        if (it == null && edit && existingSlide != null) {
            // the content was deleted after the slide was created: keep the slide editable from its own data
            JSONObject ghost = new JSONObject();
            CxJ.put(ghost, "id", id);
            CxJ.put(ghost, "title", CxJ.s(existingSlide, "title"));
            CxJ.put(ghost, "type", "series".equals(CxJ.s(existingSlide, "type")) ? "series" : "movie");
            CxJ.put(ghost, "poster", CxJ.s(existingSlide, "source_poster"));
            CxJ.put(ghost, "backdrop", CxJ.s(existingSlide, "source_backdrop"));
            return ghost;
        }
        return it;
    }

    private void touch() {
        touched = true;
        if (auto != null) auto.touch();
    }

    @Override
    protected void onPause() {
        if (auto != null) auto.flush();
        super.onPause();
    }

    // ------------------------------------------------------------------------ layout

    private void build() {
        page.removeAllViews();
        bottom.removeAllViews();
        View warn = warningCard();
        if (warn != null) page.addView(warn);
        if (!carouselKnown) {
            page.addView(CxUi.notice(this, CxUi.WARN, "تعذر قراءة الكاروسيل الحالي، لذلك سيتم فحص التكرار عند الحفظ فقط."));
        }
        if (!edit) {
            final JSONObject latest = CxDraft.latest(this, "carousel");
            if (latest != null && !latest.optString("id").equals(auto.id()) && CxJ.s(st, "content_id").length() == 0) {
                page.addView(draftBanner(latest, new Runnable() {
                    @Override
                    public void run() {
                        CxNav.resumeDraft(CxCarouselEditActivity.this, latest);
                        finish();
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        CxDraft.delete(CxCarouselEditActivity.this, latest.optString("id"));
                        build();
                    }
                }));
            }
        }

        page.addView(sectionLabel("١) المحتوى"));
        contentBox = CgUi.vbox(this);
        page.addView(contentBox);
        dupBox = CgUi.vbox(this);
        page.addView(dupBox);

        page.addView(sectionLabel("٢) الصورة"));
        imageBox = CgUi.vbox(this);
        page.addView(imageBox);

        page.addView(sectionLabel("٣) معاينة الشريحة"));
        slide = new CxPreview.Slide(this);
        page.addView(slide.view);
        ratioNote = CgUi.text(this, "", 12, CgCfg.WARN, false);
        ratioNote.setVisibility(View.GONE);
        page.addView(ratioNote);
        slide.onHint(new CxUi.RatioHint() {
            @Override
            public void onHint(String warning) {
                if (warning == null || warning.length() == 0) {
                    ratioNote.setVisibility(View.GONE);
                } else {
                    CgUi.set(ratioNote, "⚠ " + warning);
                    ratioNote.setVisibility(View.VISIBLE);
                }
            }
        });

        page.addView(sectionLabel("٤) الجدولة (اختياري)"));
        scheduleBox = CgUi.vbox(this);
        page.addView(scheduleBox);

        page.addView(sectionLabel("٥) الموضع"));
        positionBox = CgUi.vbox(this);
        page.addView(positionBox);

        renderContent();
        renderImage();
        renderSchedule();
        renderPosition();
        refreshPreview();

        bottomButton("إلغاء", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (auto != null) auto.flush();
                finish();
            }
        });
        bottomButton(edit ? "حفظ التعديل" : "إضافة إلى الكاروسيل", 0, 2f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(false);
            }
        });
        setBottomVisible(true);
    }

    // ------------------------------------------------------------------------ content

    private void renderContent() {
        contentBox.removeAllViews();
        final JSONObject it = item();
        LinearLayout card = CgUi.hbox(this);
        card.setBackground(CgUi.bg(this, CgCfg.CARD, 16, it == null ? CgCfg.STROKE : CgCfg.ACCENT));
        card.setPadding(CgUi.dp(this, 10), CgUi.dp(this, 10), CgUi.dp(this, 10), CgUi.dp(this, 10));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(CgUi.bg(this, CgCfg.CARD2, 8, 0));
        card.addView(iv, new LinearLayout.LayoutParams(CgUi.dp(this, 54), CgUi.dp(this, 78)));
        LinearLayout col = CgUi.vbox(this);
        if (it == null) {
            col.addView(CgUi.text(this, "لم تختر محتوى بعد", 15, CgCfg.TEXT, true));
            col.addView(CgUi.muted(this, "اضغط لاختيار فيلم أو مسلسل بالبحث."));
        } else {
            String p = CxOps2.posterOf(it);
            if (CxJ.isUrl(p)) CgImg.load(iv, p, 200);
            col.addView(CgUi.text(this, CgData.s(it, "title"), 15.5f, CgCfg.TEXT, true));
            int k = CgData.kind(it);
            String year = CgData.s(it, "year");
            col.addView(CgUi.muted(this, CgData.kindLabel(k) + (year.length() > 0 ? " • " + year : "")));
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        clp.setMargins(CgUi.dp(this, 10), 0, 0, 0);
        card.addView(col, clp);
        if (!edit) {
            card.addView(CgUi.smallButton(this, it == null ? "اختيار" : "تغيير", 0), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            card.setClickable(true);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    chooseContent();
                }
            });
        }
        contentBox.addView(card, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        renderDup();
    }

    private void renderDup() {
        dupBox.removeAllViews();
        if (edit) return;
        String id = CxJ.s(st, "content_id");
        if (id.length() == 0) return;
        JSONObject ex = slideOf(id);
        if (ex != null) {
            dupBox.addView(CxUi.notice(this, CxUi.WARN, "هذا المحتوى موجود بالفعل في الكاروسيل (الموضع " + ex.optInt("_pos") + "). عند الحفظ سيُطلب منك تأكيد الاستبدال."));
        }
    }

    private void chooseContent() {
        CgData d = CgData.cur();
        if (d == null) return;
        ArrayList<CxUi.Option> opts = new ArrayList<CxUi.Option>();
        for (int i = 0; i < d.items.size(); i++) {
            JSONObject o = d.items.get(i);
            int k = CgData.kind(o);
            if (k == CgData.K_EPISODE) continue;
            String year = CgData.s(o, "year");
            opts.add(new CxUi.Option(CgData.s(o, "id"), CgData.s(o, "title"),
                    CgData.kindLabel(k) + (year.length() > 0 ? " • " + year : ""), CxOps2.posterOf(o)));
        }
        CxUi.pick(this, "اختر المحتوى", opts, CxJ.s(st, "content_id"), null, new CgUi.Callback<CxUi.Option>() {
            @Override
            public void run(CxUi.Option o) {
                CxJ.put(st, "content_id", o.id);
                JSONObject it = item();
                boolean hasBackdrop = it != null && CxJ.isUrl(CxJ.s(it, "backdrop"));
                CxJ.put(st, "image_mode", hasBackdrop ? "backdrop" : "poster");
                customBitmap = null;
                customUrl = "";
                touch();
                renderContent();
                renderImage();
                refreshPreview();
            }
        }, null);
    }

    // ------------------------------------------------------------------------- image

    private String mode() {
        return CxJ.s(st, "image_mode");
    }

    private void renderImage() {
        imageBox.removeAllViews();
        JSONObject it = item();
        if (it == null) {
            imageBox.addView(CgUi.muted(this, "اختر المحتوى أولاً لتظهر خيارات الصورة."));
            return;
        }
        boolean hasBackdrop = CxJ.isUrl(CxJ.s(it, "backdrop"));
        boolean hasPoster = CxJ.isUrl(CxOps2.posterOf(it));
        CxUi.Flow flow = new CxUi.Flow(this);
        flow.addView(CxUi.chip(this, "الخلفية الأصلية" + (hasBackdrop ? "" : " (غير متوفرة)"), "backdrop".equals(mode()), new Runnable() {
            @Override
            public void run() {
                setMode("backdrop");
            }
        }));
        flow.addView(CxUi.chip(this, "الملصق" + (hasPoster ? "" : " (غير متوفر)"), "poster".equals(mode()), new Runnable() {
            @Override
            public void run() {
                setMode("poster");
            }
        }));
        flow.addView(CxUi.chip(this, "صورة من المعرض", "custom".equals(mode()), new Runnable() {
            @Override
            public void run() {
                setMode("custom");
            }
        }));
        imageBox.addView(flow);
        if ("backdrop".equals(mode()) && !hasBackdrop) {
            imageBox.addView(CxUi.notice(this, CxUi.WARN, "لا توجد خلفية أصلية لهذا المحتوى. اختر الملصق أو صورة من المعرض."));
        }
        if ("poster".equals(mode())) {
            imageBox.addView(CxUi.notice(this, CxUi.INFO, "الملصق طولي وقد يظهر مقصوصاً في الشريحة العريضة. الأفضل خلفية أو صورة عريضة 16:9."));
        }
        if ("custom".equals(mode())) {
            TextView pick = CgUi.button(this, customBitmap != null || customUrl.length() > 0 ? "تغيير الصورة" : "اختيار صورة من المعرض", 1);
            pick.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CxMedia.pickImage(CxCarouselEditActivity.this, REQ_IMAGE);
                }
            });
            imageBox.addView(pick, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
            if (customBitmap != null) {
                imageBox.addView(CgUi.muted(this, "الحجم بعد الضغط: " + CxMedia.sizeInfo(customBitmap) + " — ستُرفع الصورة عند الحفظ."));
            }
        }
    }

    private void setMode(String m) {
        CxJ.put(st, "image_mode", m);
        touch();
        renderImage();
        refreshPreview();
    }

    @Override
    protected void onActivityResult(int req, int res, final Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_IMAGE || res != RESULT_OK || data == null || data.getData() == null) return;
        showLoading("جارٍ تجهيز الصورة...");
        final android.net.Uri uri = data.getData();
        CgHttp.bg(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = CxMedia.decode(CxCarouselEditActivity.this, uri, 1600);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!alive()) return;
                        hideLoading();
                        if (b == null) {
                            snack("تعذر قراءة الصورة. اختر صورة أخرى.", CxUi.ERROR);
                            return;
                        }
                        customBitmap = b;
                        customUrl = "";
                        CxJ.put(st, "image_mode", "custom");
                        renderImage();
                        refreshPreview();
                    }
                });
            }
        });
    }

    private String chosenImageUrl() {
        JSONObject it = item();
        if (it == null) return "";
        if ("backdrop".equals(mode())) return CxJ.s(it, "backdrop");
        if ("poster".equals(mode())) return CxOps2.posterOf(it);
        return customUrl;
    }

    private void refreshPreview() {
        if (slide == null) return;
        JSONObject it = item();
        slide.setTitle(it == null ? "" : CgData.s(it, "title"),
                it == null ? "" : CgData.kindLabel(CgData.kind(it)));
        if ("custom".equals(mode()) && customBitmap != null) slide.setBitmap(customBitmap);
        else slide.setImageUrl(chosenImageUrl());
        String s = CxJ.s(st, "start_at");
        String e = CxJ.s(st, "end_at");
        StringBuilder sb = new StringBuilder();
        if (s.length() > 0) sb.append("من ").append(CgUi.localText(s));
        if (e.length() > 0) sb.append(sb.length() > 0 ? "  " : "").append("إلى ").append(CgUi.localText(e));
        slide.setDates(sb.toString());
    }

    // ---------------------------------------------------------------------- schedule

    private void renderSchedule() {
        scheduleBox.removeAllViews();
        scheduleBox.addView(CgUi.muted(this, "اتركها فارغة لتظهر الشريحة فوراً وبدون نهاية."));
        scheduleBox.addView(dateRow("تبدأ", "start_at"));
        scheduleBox.addView(dateRow("تنتهي", "end_at"));
        scheduleMsg = CgUi.text(this, "", 12, CgCfg.BAD, false);
        scheduleMsg.setVisibility(View.GONE);
        scheduleBox.addView(scheduleMsg);
        checkSchedule();
    }

    private View dateRow(String label, final String key) {
        LinearLayout row = CgUi.hbox(this);
        row.setBackground(CgUi.bg(this, CgCfg.CARD, 14, CgCfg.STROKE));
        row.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 8), CgUi.dp(this, 8), CgUi.dp(this, 8));
        String v = CxJ.s(st, key);
        TextView t = CgUi.text(this, label + ": " + (v.length() > 0 ? CgUi.localText(v) : "—"), 13.5f, CgCfg.TEXT, false);
        row.addView(t, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        TextView set = CgUi.smallButton(this, v.length() > 0 ? "تغيير" : "تحديد", 0);
        set.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                CgUi.pickDateTime(CxCarouselEditActivity.this, CxJ.s(st, key), new CgUi.Callback<String>() {
                    @Override
                    public void run(String iso) {
                        CxJ.put(st, key, iso);
                        touch();
                        renderSchedule();
                        refreshPreview();
                    }
                });
            }
        });
        row.addView(set, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        if (v.length() > 0) {
            TextView clr = CgUi.smallButton(this, "مسح", 1);
            clr.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View x) {
                    CxJ.put(st, key, "");
                    touch();
                    renderSchedule();
                    refreshPreview();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            lp.setMargins(CgUi.dp(this, 6), 0, 0, 0);
            row.addView(clr, lp);
        }
        row.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
        return row;
    }

    /** Returns true when the range is valid. */
    private boolean checkSchedule() {
        long s = CgUi.parseIso(CxJ.s(st, "start_at"));
        long e = CgUi.parseIso(CxJ.s(st, "end_at"));
        String msg = "";
        int level = CxUi.ERROR;
        if (s > 0 && e > 0 && e <= s) msg = "تاريخ النهاية يجب أن يكون بعد تاريخ البداية.";
        else if (e > 0 && e < System.currentTimeMillis()) {
            msg = "تاريخ النهاية في الماضي — ستكون الشريحة منتهية فوراً.";
            level = CxUi.WARN;
        }
        if (scheduleMsg != null) {
            if (msg.length() == 0) {
                scheduleMsg.setVisibility(View.GONE);
            } else {
                CgUi.set(scheduleMsg, (level == CxUi.ERROR ? "✖ " : "⚠ ") + msg);
                scheduleMsg.setTextColor(CxUi.levelColor(level));
                scheduleMsg.setVisibility(View.VISIBLE);
            }
        }
        return !(s > 0 && e > 0 && e <= s);
    }

    // ---------------------------------------------------------------------- position

    private void renderPosition() {
        positionBox.removeAllViews();
        CxUi.Flow flow = new CxUi.Flow(this);
        if (edit) {
            flow.addView(CxUi.chip(this, "كما هو", "keep".equals(CxJ.s(st, "position")), new Runnable() {
                @Override
                public void run() {
                    setPosition("keep");
                }
            }));
        }
        flow.addView(CxUi.chip(this, "في البداية", "first".equals(CxJ.s(st, "position")), new Runnable() {
            @Override
            public void run() {
                setPosition("first");
            }
        }));
        flow.addView(CxUi.chip(this, "في النهاية", "last".equals(CxJ.s(st, "position")), new Runnable() {
            @Override
            public void run() {
                setPosition("last");
            }
        }));
        positionBox.addView(flow);
        positionBox.addView(CgUi.muted(this, "يمكنك إعادة الترتيب بالسحب لاحقاً من شاشة إدارة الكاروسيل."));
    }

    private void setPosition(String p) {
        CxJ.put(st, "position", p);
        touch();
        renderPosition();
    }

    // -------------------------------------------------------------------------- save

    private void save(final boolean force) {
        final JSONObject it = item();
        if (it == null) {
            snack("اختر المحتوى أولاً.", CxUi.ERROR);
            scrollTo(contentBox);
            return;
        }
        if (!checkSchedule()) {
            scrollTo(scheduleBox);
            return;
        }
        final String mode = mode();
        final String url = chosenImageUrl();
        final boolean needUpload = "custom".equals(mode) && customBitmap != null;
        if (!needUpload && !CxJ.isUrl(url)) {
            snack("لا توجد صورة صالحة. اختر صورة أخرى.", CxUi.ERROR);
            scrollTo(imageBox);
            return;
        }
        if (!edit && !force) {
            JSONObject ex = slideOf(CxJ.s(st, "content_id"));
            if (ex != null) {
                askReplace(ex.optInt("_pos"));
                return;
            }
        }
        final CxStages sg = CxStages.show(this, edit ? "حفظ التعديل" : "إضافة الشريحة",
                new String[]{"رفع الصورة", "الحفظ في الكاروسيل", "اكتمل"});
        final Bitmap bmp = customBitmap;
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                String finalUrl = url;
                sg.stage(0, needUpload ? "رفع الصورة المخصصة" : "لا حاجة للرفع");
                if (needUpload) {
                    if (uploadedUrl.length() > 0 && uploadedFor == bmp) {
                        finalUrl = uploadedUrl;
                    } else {
                        byte[] data = CxMedia.jpeg(bmp, 88);
                        CgHttp.Res r = CgHttp.postBytes(CgCfg.API + "/upload-carousel-image", data, "image/jpeg", true, 90000);
                        if (r.error != null) throw new CxApi.Failure(CgHttp.describe(r.error), "NETWORK", null, 0);
                        if (!r.ok() || r.json == null || !CxJ.isUrl(r.json.optString("url"))) {
                            throw new CxApi.Failure(CgHttp.friendly(r.message()), "UPLOAD", r.json, r.code);
                        }
                        finalUrl = r.json.optString("url");
                        uploadedUrl = finalUrl;
                        uploadedFor = bmp;
                    }
                }
                sg.stage(1, "");
                JSONObject b = new JSONObject();
                b.put("content_id", CxJ.s(st, "content_id"));
                b.put("title", CgData.s(it, "title"));
                b.put("type", CgData.kind(it) == CgData.K_SERIES ? "series" : "movie");
                b.put("landscape_poster", finalUrl);
                b.put("source_poster", CxOps2.posterOf(it));
                b.put("source_backdrop", CxJ.s(it, "backdrop"));
                b.put("custom_image", "custom".equals(mode));
                b.put("start_at", CxJ.s(st, "start_at"));
                b.put("end_at", CxJ.s(st, "end_at"));
                String pos = CxJ.s(st, "position");
                if ("first".equals(pos) || "last".equals(pos)) b.put("position", pos);
                if (force) b.put("force", true);
                CgData cd = CgData.cur();
                if (cd == null || cd.byId(CxJ.s(st, "content_id")) == null) b.put("allow_missing", true);
                if (edit) b.put("edit", true);
                JSONObject r = CxApi.post("/admin/carousel/save", b);
                CxJ.put(r, "_image", finalUrl);
                return r;
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                sg.stage(2, "");
                sg.dismiss();
                if (auto != null) auto.finish();
                int pos = r.optInt("position", 0);
                CxNav.success(CxCarouselEditActivity.this, "carousel", CxJ.s(st, "content_id"), CgData.s(it, "title"),
                        CxJ.s(r, "_image"), pos > 0 ? "الموضع " + pos + " في الكاروسيل" : "", r.optString("undo_id"), "", 0, 0);
                finish();
            }

            @Override
            public void fail(CxApi.Failure f) {
                if (f.is("DUPLICATE_CAROUSEL")) {
                    sg.dismiss();
                    askReplace(f.json.optInt("position", 0));
                    return;
                }
                sg.fail(f.getMessage(), new Runnable() {
                    @Override
                    public void run() {
                        save(force);
                    }
                });
            }
        });
    }

    private void askReplace(int pos) {
        CgUi.confirm(this, "موجود في الكاروسيل", "هذا المحتوى موجود بالفعل" + (pos > 0 ? " في الموضع " + pos : "")
                + ". هل تريد استبدال الشريحة الحالية بهذه الإعدادات؟ (يمكنك التراجع بعد ذلك)", "استبدال", false, new Runnable() {
                    @Override
                    public void run() {
                        edit = true;
                        save(true);
                    }
                });
    }

    @Override
    public void onBackPressed() {
        if (auto != null) auto.flush();
        super.onBackPressed();
    }
}
