package com.my.newproject;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.Cigram.subhub.SubCodec;
import com.Cigram.subhub.SubCue;
import com.Cigram.subhub.SubDocument;
import com.Cigram.subhub.SubQa;
import com.Cigram.subhub.SubQueue;
import com.Cigram.subhub.SubSync;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One title of the Arabic Subtitle Center: ids, state and history, the quality report, a cue-by-cue preview
 * (times + flags), a preview against the real video, timing-neutral fixes, an approved timing correction, and the
 * explicit approve / reject / publish actions.
 */
public class SubJobActivity extends CgBase {

    private static final int REQ_AR = 7801, REQ_ORIG = 7802;

    private SubQueue.Job job;
    private LinearLayout page;
    private int cueLimit = 120;

    @Override
    protected String screenTitle() {
        return "ترجمة العنوان";
    }

    @Override
    protected void onBuild() {
        job = SubCenterStore.queue(this).get(getIntent().getStringExtra("id") == null ? "" : getIntent().getStringExtra("id"));
        ScrollView sv = new ScrollView(this);
        page = CgUi.vbox(this);
        page.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 4), CgUi.dp(this, 12), CgUi.dp(this, 80));
        sv.addView(page);
        body.addView(sv, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));
        ensureData(new Runnable() { @Override public void run() { render(); } });
    }

    private SubDocument arabic() {
        byte[] b = SubCenterStore.getText(this, job.translatedFile);
        if (b == null) return null;
        try { return SubCodec.read(b); } catch (Throwable e) { return null; }
    }

    private SubDocument original() {
        byte[] b = SubCenterStore.getText(this, job.originalFile);
        if (b == null) return null;
        try { return SubCodec.read(b); } catch (Throwable e) { return null; }
    }

    private void render() {
        page.removeAllViews();
        if (job == null) {
            page.addView(CgUi.empty(this, "العنوان غير موجود في الطابور", ""));
            return;
        }
        bar.subtitle(SubQueue.label(job.state));
        LinearLayout info = CgUi.card(this);
        info.addView(CgUi.text(this, job.displayTitle(), 17, CgCfg.TEXT, true));
        LinearLayout b = CgUi.hbox(this);
        b.addView(CgUi.badge(this, SubQueue.label(job.state), SubCenterActivity.colorOf(job.state)));
        b.addView(CgUi.badge(this, job.isEpisode() ? "حلقة" : "فيلم", CgCfg.GRAY), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
        b.addView(CgUi.badge(this, "الهدف: العربية", CgCfg.ACCENT), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
        info.addView(b, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 6));
        info.addView(kv("معرّف Cigram", job.contentId, true));
        info.addView(kv("TMDB", job.tmdbId.length() > 0 ? job.tmdbId : "—", true));
        info.addView(kv("IMDb", job.imdbId.length() > 0 ? job.imdbId : "—", true));
        if (job.isEpisode()) info.addView(kv("الموسم / الحلقة", job.season + " / " + job.episode, false));
        info.addView(kv("اللغة الأصلية", job.originalLanguage.length() > 0 ? job.originalLanguage : "غير محددة", false));
        info.addView(kv("مصدر الترجمة", job.sourceProvider.length() > 0 ? job.sourceProvider + (job.sourceRelease.length() > 0 ? " · " + job.sourceRelease : "") : "—", false));
        if (job.translationProvider.length() > 0) info.addView(kv("مزوّد الترجمة / رقم المهمة", job.translationProvider + " / " + job.providerJobId, true));
        info.addView(kv("الجودة", job.qualitySummary.length() > 0 ? job.qualitySummary : "لم يُفحص", false));
        if (job.approvedOffsetMs != 0) info.addView(kv("تصحيح توقيت معتمد", SubSync.delayLabel(job.approvedOffsetMs), false));
        if (job.publishedUrl.length() > 0) info.addView(kv("منشور في", job.publishedUrl, true));
        if (job.lastError.length() > 0) info.addView(CgUi.text(this, job.lastError, 13, CgCfg.WARN, false));
        page.addView(info, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 8));

        actions();

        SubDocument ar = arabic();
        if (ar != null) {
            SubQa.Report rep = SubQa.analyze(ar, original(), new SubQa.Options());
            qaCard(rep);
            cueList(ar, rep);
        } else {
            page.addView(CgUi.empty(this, "لا يوجد ملف عربي بعد", "ابحث عن ترجمة موجودة، أو ارفع ملفاً عربياً، أو اطلب ترجمة آلية."));
        }
        historyCard();
    }

    /** One right-aligned line «label: value»; Latin ids are isolated (LRI…PDI) so they never reorder the line. */
    private View kv(String k, String v, boolean ltr) {
        String value = ltr ? "\u2066" + v + "\u2069" : v;
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder();
        b.append("\u200F").append(k).append(": ");
        b.setSpan(new android.text.style.ForegroundColorSpan(CgCfg.MUTED), 0, b.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append(value);
        TextView t = CgUi.text(this, b, 13.5f, CgCfg.TEXT, false);
        t.setGravity(android.view.Gravity.RIGHT);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        t.setTextIsSelectable(true);
        t.setPadding(0, CgUi.dp(this, 2), 0, CgUi.dp(this, 2));
        return t;
    }

    // ------------------------------------------------------------------ actions

    private void actions() {
        LinearLayout box = CgUi.vbox(this);
        final SubQueue q = SubCenterStore.queue(this);
        boolean hasAr = job.translatedFile.length() > 0;
        if (job.state == SubQueue.State.QUALITY_REVIEW && hasAr) {
            add(box, "اعتماد هذا الملف", 0, new Runnable() { @Override public void run() { approve(); } });
            add(box, "رفض (يحتاج تصحيحاً)", 2, new Runnable() {
                @Override public void run() {
                    CgUi.input(SubJobActivity.this, "سبب الرفض", "مثال: التوقيت متقدم، ترجمة ناقصة", "", false, new CgUi.Callback<String>() {
                        @Override public void run(String v) {
                            q.reject(job, v.trim().length() == 0 ? "رفض يدوي" : v.trim(), System.currentTimeMillis());
                            SubCenterStore.save(SubJobActivity.this);
                            render();
                        }
                    });
                }
            });
        }
        if (job.state == SubQueue.State.APPROVED) {
            add(box, "نشر في التطبيق", 0, new Runnable() { @Override public void run() { publish(); } });
            add(box, "إلغاء الاعتماد", 1, new Runnable() {
                @Override public void run() {
                    q.move(job, SubQueue.State.QUALITY_REVIEW, "إلغاء الاعتماد", System.currentTimeMillis());
                    job.approval = "";
                    SubCenterStore.save(SubJobActivity.this);
                    render();
                }
            });
        }
        if (hasAr) {
            add(box, "معاينة مع الفيديو", 1, new Runnable() { @Override public void run() { previewWithVideo(); } });
            add(box, "إصلاحات لا تمس التوقيت (الترقيم العربي واتجاه الأسطر)", 1, new Runnable() { @Override public void run() { fixes(); } });
            add(box, "تصحيح توقيت معتمد (إزاحة كاملة أو فرق الإطارات)", 1, new Runnable() { @Override public void run() { timingCorrection(); } });
        }
        if (job.state != SubQueue.State.SEARCHING && job.state != SubQueue.State.TRANSLATING) {
            add(box, "بحث عن ترجمة عربية موجودة", 1, new Runnable() {
                @Override public void run() {
                    showLoading("جارٍ البحث...");
                    SubCenterRunner.get(SubJobActivity.this).runOne(job, new Runnable() {
                        @Override public void run() { if (alive()) { hideLoading(); render(); } }
                    });
                }
            });
            add(box, "كل النتائج واختيار إصدار آخر", 1, new Runnable() { @Override public void run() { allResults(); } });
        }
        add(box, "رفع ملف عربي يدوياً (SRT / VTT)", 1, new Runnable() { @Override public void run() { pick(REQ_AR); } });
        add(box, "رفع الملف الأصلي (لغة أخرى) كمصدر للترجمة", 1, new Runnable() { @Override public void run() { pick(REQ_ORIG); } });
        if (job.originalFile.length() > 0 && !"ar".equals(job.sourceLanguage) && SubQueue.canMove(job.state, SubQueue.State.TRANSLATION_QUEUED)) {
            add(box, "طلب ترجمة آلية لهذا العنوان", 1, new Runnable() { @Override public void run() { requestTranslation(); } });
        }
        page.addView(box, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
    }

    private void add(LinearLayout box, String label, int style, final Runnable r) {
        TextView t = CgUi.button(this, label, style);
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { r.run(); } });
        box.addView(t, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
    }

    private void approve() {
        SubDocument ar = arabic();
        if (ar == null) return;
        SubQa.Report rep = SubQa.analyze(ar, original(), new SubQa.Options());
        if (rep.verdict() == SubQa.Verdict.FAIL) {
            CgUi.info(this, "لا يمكن الاعتماد", "الفحص وجد " + rep.errors + " خطأ. صحّح الملف أو اختر إصداراً آخر.");
            return;
        }
        String msg = rep.summary() + "\n\nهل راجعت المقاطع وتوقيتها مع الفيديو؟ الاعتماد لا ينشر الملف — النشر خطوة منفصلة.";
        CgUi.confirm(this, "اعتماد الترجمة", msg, "اعتماد", false, new Runnable() {
            @Override public void run() {
                try {
                    SubCenterStore.queue(SubJobActivity.this).approve(job, SubCenterStore.nowIso(), System.currentTimeMillis());
                    SubCenterStore.save(SubJobActivity.this);
                    SubCenterStore.addHistory(SubJobActivity.this, historyEntry("اعتماد"));
                } catch (IllegalStateException e) {
                    CgUi.info(SubJobActivity.this, "تعذر الاعتماد", e.getMessage());
                }
                render();
            }
        });
    }

    private JSONObject historyEntry(String action) {
        JSONObject o = new JSONObject();
        try { o.put("content_id", job.contentId).put("title", job.displayTitle()).put("action", action); } catch (Throwable ignored) { }
        return o;
    }

    private void publish() {
        showLoading("جارٍ الرفع والربط...");
        SubPublish.publishOne(this, job, false, "", new SubPublish.Done() {
            @Override public void done(SubPublish.Outcome o) {
                hideLoading();
                if (o.error.length() > 0) CgUi.info(SubJobActivity.this, "تعذر النشر", o.error);
                else {
                    CgUi.toast(SubJobActivity.this, "نُشرت الترجمة وظهرت في التطبيق.");
                    if (o.undoId.length() > 0) showUndo("نشر الترجمة", o.undoId, new Runnable() { @Override public void run() { render(); } });
                }
                render();
            }
        });
        // the dialogs of publishOne (replace / rename) appear above the loading overlay
        ui().postDelayed(new Runnable() { @Override public void run() { if (alive()) hideLoading(); } }, 1500);
    }

    private void previewWithVideo() {
        CgData d = CgData.cur();
        JSONObject it = d == null ? null : d.byId(job.contentId);
        Intent in = new Intent(this, CgPlayerActivity.class);
        if (it != null) in.putExtra("video_url", CgData.s(it, "video").length() > 0 ? CgData.s(it, "video") : CgData.s(it, "link"));
        in.putExtra("title", job.displayTitle());
        in.putExtra("subtitle_file", job.translatedFile);
        in.putExtra("subtitle_url", "");
        startActivity(in);
    }

    private void saveNewVersion(SubDocument d, String note) {
        byte[] bytes = SubCodec.writeBytes(d, d.format);
        SubCenterRunner.checkAndPark(this, SubCenterStore.queue(this), job, bytes, d.format == SubDocument.Format.VTT ? "vtt" : "srt", "ar", note);
        job.approval = "";
        SubCenterStore.save(this);
        render();
    }

    private void fixes() {
        final SubDocument ar = arabic();
        if (ar == null) return;
        SubDocument fixed = SubQa.fixRtlLines(SubQa.fixArabicPunctuation(ar));
        SubSync.TimingReport t = SubSync.compareTiming(ar, fixed, 0);
        if (fixed == ar) { CgUi.toast(this, "لا يوجد ما يُصلح."); return; }
        final SubDocument f2 = fixed;
        CgUi.confirm(this, "تطبيق الإصلاحات؟", "استبدال «,» «?» «;» بعد الحروف العربية بـ «،» «؟» «؛»، وإضافة علامة RLM لأسطر عربية تبدأ بكلمة لاتينية.\nالتوقيت: " + (t.preserved() ? "لم يتغير أي توقيت." : "تغيّر!") + "\nسيعود الملف إلى «مراجعة الجودة».",
                "تطبيق", false, new Runnable() { @Override public void run() { saveNewVersion(f2, "إصلاحات الترقيم/الاتجاه"); } });
    }

    private void timingCorrection() {
        final SubDocument ar = arabic();
        if (ar == null) return;
        CgUi.input(this, "تصحيح توقيت معتمد", "إزاحة بالملّي ثانية (+ تأخير، − تقديم)، واختيارياً فرق الإطارات بعد مسافة: مثال  +1200  أو  0 25/23.976", "", false, new CgUi.Callback<String>() {
            @Override public void run(String v) {
                String[] parts = v.trim().split("\\s+");
                long off;
                double factor = 1.0;
                try {
                    off = Long.parseLong(parts[0].replace("+", "").replace("−", "-"));
                    if (parts.length > 1 && parts[1].contains("/")) {
                        String[] fr = parts[1].split("/");
                        factor = Double.parseDouble(fr[0]) / Double.parseDouble(fr[1]);
                    }
                } catch (Exception e) {
                    CgUi.toast(SubJobActivity.this, "صيغة غير صحيحة.");
                    return;
                }
                if (off == 0 && factor == 1.0) return;
                final SubSync.ShiftResult r = factor == 1.0 ? SubSync.shift(ar, off) : SubSync.stretch(ar, factor, off);
                final long fo = off;
                SubCue first = r.document.isEmpty() ? null : r.document.cues.get(0);
                String msg = "سيتغير توقيت كل المقاطع (" + ar.size() + ")" + (factor != 1.0 ? String.format(Locale.US, " بمعامل %.5f", factor) : "") + (off != 0 ? " بإزاحة " + SubSync.delayLabel(off) : "") + ".\n"
                        + (r.dropped > 0 ? r.dropped + " مقطعاً سيُحذف لأنه يصبح قبل البداية.\n" : "")
                        + (first != null ? "أول مقطع سيبدأ عند " + SubCodec.stamp(first.startMs, ',') + ".\n" : "")
                        + "هذا تصحيح دائم في الملف ويحتاج اعتماداً جديداً.";
                CgUi.confirm(SubJobActivity.this, "تأكيد تصحيح التوقيت", msg, "تطبيق التصحيح", true, new Runnable() {
                    @Override public void run() {
                        job.approvedOffsetMs += fo;
                        saveNewVersion(r.document, "تصحيح توقيت معتمد " + SubSync.delayLabel(fo));
                        SubCenterStore.addHistory(SubJobActivity.this, historyEntry("تصحيح توقيت " + SubSync.delayLabel(fo)));
                    }
                });
            }
        });
    }

    private void allResults() {
        showLoading("جارٍ البحث في كل المصادر...");
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                JSONArray rows = null;
                String err = null;
                try {
                    JSONObject r = CxApi.post("/admin/subs/search", new JSONObject().put("item_id", job.contentId).put("all", true));
                    String mm = SubCenterRunner.idMismatch(job, r.optJSONObject("target"));
                    if (mm != null) err = mm; else rows = r.optJSONArray("results");
                } catch (CxApi.Failure f) {
                    err = f.getMessage();
                } catch (Throwable t) {
                    err = "تعذر البحث.";
                }
                final JSONArray fr = rows;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive()) return;
                        hideLoading();
                        if (fe != null) { CgUi.info(SubJobActivity.this, "البحث", fe); return; }
                        final List<JSONObject> ok = new ArrayList<JSONObject>();
                        List<CharSequence> labels = new ArrayList<CharSequence>();
                        for (int i = 0; fr != null && i < fr.length(); i++) {
                            JSONObject c = fr.optJSONObject(i);
                            if (c == null) continue;
                            if (job.isEpisode()) {
                                int s = c.optInt("season"), e = c.optInt("episode");
                                if ((s > 0 && s != job.season) || (e > 0 && e != job.episode)) continue;   // another episode: never offered
                            }
                            ok.add(c);
                            labels.add(c.optString("source") + " · " + c.optInt("score") + "% · " + c.optString("release", c.optString("name"))
                                    + (c.optBoolean("full_season") ? " · حزمة موسم" : "") + (c.optBoolean("hi") ? " · للصم" : ""));
                        }
                        if (ok.isEmpty()) { CgUi.info(SubJobActivity.this, "البحث", "لا توجد ترجمة عربية لهذا العنوان في المصادر."); return; }
                        CgUi.choose(SubJobActivity.this, ok.size() + " ترجمة عربية", labels.toArray(new CharSequence[0]), new CgUi.IntCb() {
                            @Override public void run(int w) { fetchPick(ok.get(w)); }
                        });
                    }
                });
            }
        });
    }

    private void fetchPick(final JSONObject pick) {
        showLoading("جارٍ جلب الملف...");
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    SubQueue q = SubCenterStore.queue(SubJobActivity.this);
                    JSONObject f = CxApi.post("/admin/subs/fetch", new JSONObject().put("item_id", job.contentId).put("mode", "text").put("pick", pick));
                    q.useQuota(pick.optString("source"), SubCenterStore.today());
                    job.sourceProvider = pick.optString("source");
                    job.sourceRelease = pick.optString("release", pick.optString("name"));
                    job.sourceLanguage = "ar";
                    job.sourcePick = pick;
                    SubCenterRunner.checkAndPark(SubJobActivity.this, q, job, f.optString("srt", "").getBytes("UTF-8"), "srt", "ar", "اختيار يدوي: " + job.sourceProvider);
                    SubCenterStore.save(SubJobActivity.this);
                } catch (CxApi.Failure e) {
                    err = e.getMessage();
                } catch (Throwable t) {
                    err = "تعذر جلب الملف: " + t.getClass().getSimpleName();
                }
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive()) return;
                        hideLoading();
                        if (fe != null) CgUi.info(SubJobActivity.this, "تعذر الجلب", fe);
                        render();
                    }
                });
            }
        });
    }

    private void requestTranslation() {
        showLoading("جارٍ إرسال الطلب...");
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                String err = null;
                JSONObject r = null;
                try {
                    r = CxApi.post("/admin/subhub/translate", new JSONObject().put("content_id", job.contentId).put("target", "ar").put("provider", "opensubtitles_ai")
                            .put("source_file", new String(SubCenterStore.getText(SubJobActivity.this, job.originalFile), "UTF-8")));
                } catch (CxApi.Failure f) {
                    err = "ROUTE_MISSING".equals(f.code) ? "الخادم الحالي لا يحتوي مسار الترجمة الآلية (/admin/subhub/translate). لم يُرسل شيء ولم تتغير الحالة. العقد المطلوب موثّق في docs/backend." : f.getMessage();
                } catch (Throwable t) {
                    err = "تعذر الإرسال.";
                }
                final String fe = err;
                final JSONObject fr = r;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive()) return;
                        hideLoading();
                        if (fe != null) { CgUi.info(SubJobActivity.this, "لم تبدأ الترجمة", fe); return; }
                        SubQueue q = SubCenterStore.queue(SubJobActivity.this);
                        q.move(job, SubQueue.State.TRANSLATION_QUEUED, "قبله الخادم", System.currentTimeMillis());
                        job.translationProvider = fr.optString("provider", "opensubtitles_ai");
                        job.providerJobId = fr.optString("job_id");
                        SubCenterStore.save(SubJobActivity.this);
                        render();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ files

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, req); } catch (Exception e) { CgUi.toast(this, "تعذر فتح منتقي الملفات."); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null || job == null) return;
        final Uri uri = data.getData();
        final boolean ar = req == REQ_AR;
        if (req != REQ_AR && req != REQ_ORIG) return;
        showLoading("جارٍ فحص الملف...");
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    String name = CgSubtitleUpload.displayName(SubJobActivity.this, uri);
                    byte[] raw = CgSubtitleUpload.readRaw(SubJobActivity.this, uri);
                    SubDocument d = SubCodec.read(raw);
                    if (d.isEmpty()) throw new Exception("الملف لا يحتوي مقاطع صالحة.");
                    SubQueue q = SubCenterStore.queue(SubJobActivity.this);
                    if (ar) {
                        if (SubCodec.arabicShare(joined(d)) < 0.3) throw new Exception("الملف ليس بالعربية.");
                        job.sourceProvider = job.sourceProvider.length() > 0 ? job.sourceProvider : "manual";
                        job.sourceLanguage = job.originalFile.length() > 0 ? job.sourceLanguage : "ar";
                        SubCenterRunner.checkAndPark(SubJobActivity.this, q, job, SubCodec.writeBytes(d, d.format), d.format == SubDocument.Format.VTT ? "vtt" : "srt", "ar", "رفع يدوي: " + name);
                    } else {
                        String file = SubCenterStore.putText(SubJobActivity.this, job.contentId, "orig", d.format == SubDocument.Format.VTT ? "vtt" : "srt", SubCodec.writeBytes(d, d.format));
                        job.originalFile = file;
                        job.sourceLanguage = "und";
                        job.chars = 0;
                        for (SubCue c : d.cues) job.chars += c.plainText().length();
                        if (job.state == SubQueue.State.NOT_TRANSLATED) q.move(job, SubQueue.State.SOURCE_FOUND, "ملف أصلي يدوي: " + name, System.currentTimeMillis());
                    }
                    SubCenterStore.save(SubJobActivity.this);
                } catch (Throwable t) {
                    err = t.getMessage() == null ? "تعذر قراءة الملف." : t.getMessage();
                }
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive()) return;
                        hideLoading();
                        if (fe != null) CgUi.info(SubJobActivity.this, "الملف", fe);
                        render();
                    }
                });
            }
        });
    }

    private static String joined(SubDocument d) {
        StringBuilder b = new StringBuilder();
        for (SubCue c : d.cues) b.append(c.plainText()).append('\n');
        return b.toString();
    }

    // ------------------------------------------------------------------ report + preview

    private void qaCard(SubQa.Report rep) {
        LinearLayout c = CgUi.card(this);
        SubQa.Verdict v = rep.verdict();
        c.addView(CgUi.text(this, "فحص الجودة: " + rep.summary(), 15, v == SubQa.Verdict.PASS ? CgCfg.GOOD : v == SubQa.Verdict.REVIEW ? CgCfg.WARN : CgCfg.BAD, true));
        c.addView(CgUi.muted(this, String.format(Locale.US, "العربية %.0f%% · المدة %s", rep.arabicShare * 100, SubCodec.stamp(rep.durationMs, ','))));
        if (job.providerQuality.length() > 0) c.addView(CgUi.muted(this, "تقرير المزوّد (للاستئناس فقط): " + job.providerQuality));
        int shown = 0;
        for (SubQa.Issue i : rep.issues) {
            if (i.severity == SubQa.Severity.INFO && shown > 6) continue;
            if (shown++ >= 25) break;
            int color = i.severity == SubQa.Severity.ERROR ? CgCfg.BAD : i.severity == SubQa.Severity.WARNING ? CgCfg.WARN : CgCfg.MUTED;
            c.addView(CgUi.text(this, (i.cue > 0 ? "#" + i.cue + "  " : "") + i.message, 12.5f, color, false));
        }
        if (rep.issues.size() > shown) c.addView(CgUi.muted(this, "… و" + (rep.issues.size() - shown) + " ملاحظة أخرى"));
        page.addView(c, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
    }

    private void cueList(SubDocument ar, SubQa.Report rep) {
        Map<Integer, String> flags = new HashMap<Integer, String>();
        for (SubQa.Issue i : rep.issues) if (i.cue > 0 && i.severity != SubQa.Severity.INFO && !flags.containsKey(i.cue)) flags.put(i.cue, i.message);
        SubDocument orig = original();
        LinearLayout c = CgUi.card(this);
        c.addView(CgUi.text(this, "معاينة المقاطع (" + ar.size() + ")" + (orig != null ? " مع الأصل" : ""), 15, CgCfg.TEXT, true));
        int n = Math.min(cueLimit, ar.size());
        for (int k = 0; k < n; k++) {
            SubCue q = ar.cues.get(k);
            LinearLayout r = CgUi.vbox(this);
            r.setPadding(0, CgUi.dp(this, 6), 0, CgUi.dp(this, 6));
            TextView time = CgUi.text(this, "#" + q.index + "  " + SubCodec.stamp(q.startMs, ',') + " → " + SubCodec.stamp(q.endMs, ','), 11.5f, CgCfg.FAINT, false);
            time.setTypeface(Typeface.MONOSPACE);
            CgUi.ltr(time);
            r.addView(time);
            TextView t = CgUi.text(this, q.plainText(), 15, CgCfg.TEXT, false);
            t.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
            r.addView(t);
            if (orig != null && k < orig.size()) {
                TextView o = CgUi.text(this, orig.cues.get(k).plainText(), 12.5f, CgCfg.MUTED, false);
                o.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
                r.addView(o);
            }
            String f = flags.get(q.index);
            if (f != null) r.addView(CgUi.text(this, "⚠ " + f, 12, CgCfg.WARN, false));
            c.addView(r);
            c.addView(CgUi.divider(this));
        }
        if (n < ar.size()) {
            TextView more = CgUi.smallButton(this, "عرض المزيد", 1);
            more.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { cueLimit += 200; render(); }
            });
            c.addView(more);
        }
        page.addView(c, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
    }

    private void historyCard() {
        LinearLayout c = CgUi.card(this);
        c.addView(CgUi.text(this, "سجل الحالة", 15, CgCfg.TEXT, true));
        for (int i = job.history.size() - 1; i >= 0 && i >= job.history.size() - 20; i--) {
            JSONObject h = job.history.get(i);
            String from = h.optString("from"), to = h.optString("to");
            String line;
            try {
                line = SubQueue.label(SubQueue.State.valueOf(from)) + " ← " + SubQueue.label(SubQueue.State.valueOf(to));
            } catch (Exception e) {
                line = from + " → " + to;
            }
            c.addView(CgUi.text(this, line + (h.optString("note").length() > 0 ? " · " + h.optString("note") : ""), 12.5f, CgCfg.MUTED, false));
            c.addView(CgUi.text(this, CgUi.localText(CgUi.toIso(h.optLong("at"))), 11, CgCfg.FAINT, false));
        }
        page.addView(c, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
    }
}
