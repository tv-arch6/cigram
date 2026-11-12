package com.my.newproject;

import android.content.Intent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.Cigram.subhub.SubQueue;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * مركز الترجمة العربية — Arabic Subtitle Center.
 *
 * One row per film / episode of the live catalogue without a working subtitle (counted from the Worker's catalogue,
 * never estimated). Search, filters (type / state), progress, bulk search with pagination + concurrency + checkpoint,
 * individual processing, failed jobs with retry, preview, approval, publication and its history.
 *
 * Approval and publication are always one explicit admin action per file (or one confirmed bulk action over files
 * that were each approved). A translation campaign never starts without the provider, the price, the budget and the
 * batch being confirmed in the same dialog.
 */
public class SubCenterActivity extends CgBase implements SubCenterRunner.Listener {

    private static final int PAGE = 60;

    private LinearLayout head;
    private LinearLayout listBox;
    private TextView batchLine;
    private ProgressBar batchBar;
    private LinearLayout batchActions;
    private String filter = "all";
    private String query = "";
    private int limit = PAGE;
    private int catalogueMissing = -1;
    private final List<TextView> chipViews = new ArrayList<TextView>();

    private static final String[][] FILTERS = {
            {"all", "الكل"}, {"movie", "أفلام"}, {"episode", "حلقات"},
            {"NOT_TRANSLATED", "غير مترجم"}, {"SOURCE_FOUND", "وُجد مصدر"}, {"TRANSLATION_QUEUED", "طابور الترجمة"},
            {"QUALITY_REVIEW", "مراجعة الجودة"}, {"NEEDS_CORRECTION", "يحتاج تصحيحاً"}, {"APPROVED", "معتمد"},
            {"PUBLISHED", "منشور"}, {"FAILED", "فشل"},
    };

    @Override
    protected String screenTitle() {
        return "مركز الترجمة العربية";
    }

    @Override
    protected void onBuild() {
        bar.action("المصادر", new Runnable() { @Override public void run() { providers(); } });
        bar.action("الإعدادات", new Runnable() { @Override public void run() { settings(); } });
        bar.action("السجل", new Runnable() { @Override public void run() { history(); } });
        ScrollView sv = new ScrollView(this);
        LinearLayout page = CgUi.vbox(this);
        page.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 4), CgUi.dp(this, 12), CgUi.dp(this, 90));
        sv.addView(page);
        body.addView(sv, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));

        head = CgUi.vbox(this);
        page.addView(head);

        LinearLayout batch = CgUi.card(this);
        batch.addView(CgUi.text(this, "المعالجة الجماعية", 15.5f, CgCfg.TEXT, true));
        batchLine = CgUi.muted(this, "");
        batch.addView(batchLine, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 6));
        batchBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        batchBar.setMax(1000);
        batch.addView(batchBar, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));
        batchActions = CgUi.vbox(this);
        batch.addView(batchActions);
        page.addView(batch, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 8));

        LinearLayout tools = CgUi.hbox(this);
        TextView season = CgUi.button(this, "رفع ترجمات موسم كامل", 1);
        season.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SubCenterActivity.this, CgSeasonSubsActivity.class)); }
        });
        TextView pub = CgUi.button(this, "نشر المعتمدة", 1);
        pub.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { publishApproved(); }
        });
        tools.addView(season, CgUi.lpWeight(this, 1f, 0, 0, 4, 0));
        tools.addView(pub, CgUi.lpWeight(this, 1f, 4, 0, 0, 0));
        page.addView(tools, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));

        EditText search = CgUi.field(this, "بحث بالاسم أو معرّف المحتوى / TMDB / IMDb", false);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                limit = PAGE;
                renderList();
            }
        });
        page.addView(search, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 4));
        CgUi.ChipBar cb = new CgUi.ChipBar(this);
        for (final String[] f : FILTERS) {
            final TextView t = cb.add(f[1], f[0].equals(filter));
            chipViews.add(t);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    filter = f[0];
                    for (int i = 0; i < chipViews.size(); i++) CgUi.setChipState(chipViews.get(i), FILTERS[i][0].equals(filter));
                    limit = PAGE;
                    renderList();
                }
            });
        }
        page.addView(cb.scroll, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        listBox = CgUi.vbox(this);
        page.addView(listBox, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));

        listBox.addView(CxUi.skeleton(this, 4));
        ensureData(new Runnable() {
            @Override public void run() { syncCatalogue(); }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        SubCenterRunner.get(this).addListener(this);
    }

    @Override
    protected void onStop() {
        SubCenterRunner.get(this).removeListener(this);
        super.onStop();
    }

    @Override
    public void changed() {
        if (!alive()) return;
        renderHead();
        renderBatch();
        renderList();
    }

    private void syncCatalogue() {
        final CgData d = CgData.cur();
        if (d == null) {
            listBox.removeAllViews();
            listBox.addView(CgUi.errorCard(this, "تعذر تحميل الكتالوج من الخادم.", new Runnable() {
                @Override public void run() { reloadData(new Runnable() { @Override public void run() { syncCatalogue(); } }); }
            }));
            return;
        }
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                final int missing = SubCenterStore.syncFromCatalogue(SubCenterActivity.this, d);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive()) return;
                        catalogueMissing = missing;
                        changed();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ header (numbers + progress)

    private void renderHead() {
        head.removeAllViews();
        SubQueue q = SubCenterStore.queue(this);
        Map<SubQueue.State, Integer> c = q.counts();
        LinearLayout card = CgUi.card(this);
        String miss = catalogueMissing < 0 ? "…" : String.valueOf(catalogueMissing);
        card.addView(CgUi.text(this, "عناوين بلا ترجمة عاملة في الكتالوج: " + miss, 16, CgCfg.TEXT, true));
        card.addView(CgUi.muted(this, "العدد من الكتالوج المباشر (أفلام + حلقات)، وليس تقديراً. في الطابور: " + q.size() + " عنواناً."));
        int total = Math.max(1, q.size());
        int published = c.get(SubQueue.State.PUBLISHED), approved = c.get(SubQueue.State.APPROVED), review = c.get(SubQueue.State.QUALITY_REVIEW);
        ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(total);
        p.setProgress(published);
        p.setSecondaryProgress(published + approved + review);
        card.addView(p, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 4));
        card.addView(CgUi.muted(this, "منشور " + published + " · معتمد " + approved + " · بانتظار المراجعة " + review + " · " +
                String.format(Locale.US, "%.1f", published * 100.0 / total) + "%"));
        LinearLayout grid = CgUi.vbox(this);
        LinearLayout row = null;
        int k = 0;
        for (SubQueue.State s : SubQueue.State.values()) {
            if (k % 3 == 0) {
                row = CgUi.hbox(this);
                grid.addView(row, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
            }
            View kpi = DxUi.kpi(this, SubQueue.label(s), String.valueOf(c.get(s)), colorOf(s));
            row.addView(kpi, CgUi.lpWeight(this, 1f, 2, 0, 2, 0));
            k++;
        }
        while (k % 3 != 0) { row.addView(new View(this), CgUi.lpWeight(this, 1f, 2, 0, 2, 0)); k++; }
        card.addView(grid, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        head.addView(card, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
    }

    static int colorOf(SubQueue.State s) {
        switch (s) {
            case PUBLISHED: return CgCfg.GOOD;
            case APPROVED: return CgCfg.CYAN;
            case QUALITY_REVIEW: case TRANSLATION_COMPLETED: return CgCfg.ACCENT_2;
            case NEEDS_CORRECTION: case FAILED: return CgCfg.BAD;
            case SEARCHING: case TRANSLATING: case TRANSLATION_QUEUED: return CgCfg.ORANGE;
            case SOURCE_FOUND: return CgCfg.ACCENT;
            default: return CgCfg.GRAY;
        }
    }

    // ------------------------------------------------------------------ batch

    private void renderBatch() {
        SubQueue q = SubCenterStore.queue(this);
        SubCenterRunner r = SubCenterRunner.get(this);
        SubQueue.Batch b = q.batch;
        batchActions.removeAllViews();
        if (b.active()) {
            int size = Math.max(1, b.size);
            batchBar.setVisibility(View.VISIBLE);
            batchBar.setProgress((int) (Math.min(b.done, size) * 1000L / size));
            String st = r.isRunning() ? "يعمل" : b.paused && b.pausedUntil > System.currentTimeMillis() ? "متوقف حتى " + CgUi.localText(CgUi.toIso(b.pausedUntil)) : "متوقف";
            String extra = r.status().length() > 0 && !r.isRunning() ? " — " + r.status() : "";
            batchLine.setText("دفعة " + b.id + ": " + b.done + " من " + b.size + " · " + st + extra + " · متزامن " + b.concurrency
                    + " · حصة OpenSubtitles اليوم " + q.quotaUsed("opensubtitles", SubCenterStore.today()) + "/" + (q.quotaLimit("opensubtitles") == 0 ? "∞" : String.valueOf(q.quotaLimit("opensubtitles"))));
            if (r.isRunning()) {
                addBatchButton("إيقاف مؤقت", 1, new Runnable() { @Override public void run() { SubCenterRunner.get(SubCenterActivity.this).pause(); } });
            } else {
                addBatchButton("استئناف من آخر نقطة", 0, new Runnable() { @Override public void run() { SubCenterService.start(SubCenterActivity.this); } });
            }
            addBatchButton("إلغاء الدفعة", 2, new Runnable() {
                @Override public void run() {
                    CgUi.confirm(SubCenterActivity.this, "إلغاء الدفعة؟", "يتوقف البحث. ما وُجد يبقى في «مراجعة الجودة» ولا يُنشر شيء.", "إلغاء الدفعة", true, new Runnable() {
                        @Override public void run() { SubCenterRunner.get(SubCenterActivity.this).cancel(); }
                    });
                }
            });
        } else {
            batchBar.setVisibility(View.GONE);
            batchLine.setText(b.finishedAt > 0 ? "آخر دفعة (" + b.id + ") اكتملت: " + b.done + " من " + b.size + "." : b.cancelled ? "أُلغيت آخر دفعة." : "لا توجد دفعة جارية.");
            addBatchButton("بحث جماعي عن ترجمة عربية موجودة", 0, new Runnable() { @Override public void run() { askSearchBatch(); } });
        }
        addBatchButton("إعادة محاولة الفاشلة", 1, new Runnable() { @Override public void run() { retryFailed(); } });
    }

    private void addBatchButton(String label, int style, final Runnable r) {
        TextView t = CgUi.button(this, label, style);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { r.run(); }
        });
        batchActions.addView(t, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
    }

    private List<SubQueue.Job> filtered() {
        List<SubQueue.Job> out = new ArrayList<SubQueue.Job>();
        for (SubQueue.Job j : SubCenterStore.queue(this).all()) {
            if ("movie".equals(filter) && j.isEpisode()) continue;
            if ("episode".equals(filter) && !j.isEpisode()) continue;
            if (isState(filter) && !j.state.name().equals(filter)) continue;
            if (query.length() > 0) {
                String hay = (j.displayTitle() + " " + j.contentId + " " + j.tmdbId + " " + j.imdbId).toLowerCase(Locale.ROOT);
                if (!hay.contains(query)) continue;
            }
            out.add(j);
        }
        return out;
    }

    private static boolean isState(String f) {
        for (SubQueue.State s : SubQueue.State.values()) if (s.name().equals(f)) return true;
        return false;
    }

    private void askSearchBatch() {
        final List<String> ids = new ArrayList<String>();
        int noId = 0;
        for (SubQueue.Job j : filtered()) {
            if (j.state != SubQueue.State.NOT_TRANSLATED && j.state != SubQueue.State.FAILED) continue;
            if (j.tmdbId.length() == 0 && j.imdbId.length() == 0) { noId++; continue; }
            ids.add(j.contentId);
        }
        if (ids.isEmpty()) {
            CgUi.info(this, "لا يوجد ما يُبحث عنه", "لا توجد عناوين «غير مترجمة» أو «فاشلة» ضمن الفلتر الحالي" + (noId > 0 ? " (" + noId + " بلا معرّف TMDB/IMDb لا يُبحث عنها بالاسم وحده)." : "."));
            return;
        }
        final int conc = SubCenterStore.concurrency(this);
        String msg = "سيُبحث عن ترجمة عربية موجودة لـ " + ids.size() + " عنواناً عبر مصادر الخادم (SubDL ثم SubSource ثم OpenSubtitles)، "
                + conc + " عناوين في وقت واحد، مع حفظ نقطة الاستئناف بعد كل عنوان.\n\n"
                + "• ما يُعثر عليه يُفحص ويوضع في «مراجعة الجودة» — لا يُعتمد ولا يُنشر شيء تلقائياً.\n"
                + "• تحميل كل ملف يستهلك حصة التحميل لدى المزوّد (OpenSubtitles: " + (SubCenterStore.queue(this).quotaLimit("opensubtitles") == 0 ? "بلا حد مضبوط" : SubCenterStore.queue(this).quotaLimit("opensubtitles") + " يومياً") + ").\n"
                + (noId > 0 ? "• " + noId + " عنواناً بلا معرّف TMDB/IMDb استُبعدت.\n" : "")
                + "• لا ترجمة آلية في هذه الخطوة (لا تكلفة ترجمة).";
        CgUi.confirm(this, "بدء البحث الجماعي؟", msg, "ابدأ", false, new Runnable() {
            @Override public void run() {
                SubQueue q = SubCenterStore.queue(SubCenterActivity.this);
                synchronized (q) {
                    q.startBatch("S" + Long.toString(System.currentTimeMillis() / 1000L, 36).toUpperCase(Locale.US), "search", ids, conc, System.currentTimeMillis());
                    q.batch.approvedAt = SubCenterStore.nowIso();
                }
                SubCenterStore.save(SubCenterActivity.this);
                SubCenterService.start(SubCenterActivity.this);
                changed();
            }
        });
    }

    private void retryFailed() {
        final List<String> ids = new ArrayList<String>();
        for (SubQueue.Job j : SubCenterStore.queue(this).all()) if (j.state == SubQueue.State.FAILED) ids.add(j.contentId);
        if (ids.isEmpty()) { CgUi.toast(this, "لا توجد عناوين فاشلة."); return; }
        if (SubCenterStore.queue(this).batch.active()) { CgUi.info(this, "دفعة جارية", "أوقف الدفعة الحالية أو انتظر اكتمالها أولاً."); return; }
        CgUi.confirm(this, "إعادة محاولة " + ids.size() + " عنواناً؟", "تبدأ دفعة جديدة للعناوين الفاشلة فقط، بمحاولات جديدة وانتظار متزايد بين المحاولات.", "إعادة المحاولة", false, new Runnable() {
            @Override public void run() {
                SubQueue q = SubCenterStore.queue(SubCenterActivity.this);
                synchronized (q) { q.startBatch("R" + Long.toString(System.currentTimeMillis() / 1000L, 36).toUpperCase(Locale.US), "search", ids, SubCenterStore.concurrency(SubCenterActivity.this), System.currentTimeMillis()); }
                SubCenterStore.save(SubCenterActivity.this);
                SubCenterService.start(SubCenterActivity.this);
                changed();
            }
        });
    }

    private volatile String storageWarning = "";

    // ------------------------------------------------------------------ providers (live state, real verification)

    private static String stateLabel(String s) {
        switch (s) {
            case "connected": return "متصل ✓ (نجح طلب حقيقي)";
            case "configured_unverified": return "مضبوط — لم يُتحقق بعد";
            case "not_configured": return "غير مضبوط في Cloudflare";
            case "error": return "خطأ في آخر طلب";
            default: return s;
        }
    }

    /** Which sources the Worker really reaches. «متصل» appears only after a real request succeeded (never because a key exists). */
    private void providers() {
        showLoading("جارٍ قراءة حالة المصادر...");
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                String err = null;
                JSONArray rows = null;
                try { JSONObject resp = CxApi.get("/admin/subhub/providers"); rows = resp.optJSONArray("providers"); JSONObject st = resp.optJSONObject("storage"); storageWarning = st == null ? "" : st.optString("warning"); }
                catch (CxApi.Failure f) { err = "ROUTE_MISSING".equals(f.code) ? "الخادم الحالي لا يحتوي مسار حالة المصادر. انشر تعديلات docs/backend أولاً." : f.getMessage(); }
                catch (Throwable t) { err = "تعذر قراءة حالة المصادر."; }
                final String fe = err;
                final JSONArray fr = rows;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive()) return;
                        hideLoading();
                        if (fe != null) { CgUi.info(SubCenterActivity.this, "المصادر", fe); return; }
                        final List<JSONObject> ps = new ArrayList<JSONObject>();
                        List<CharSequence> labels = new ArrayList<CharSequence>();
                        if (storageWarning.length() > 0) { ps.add(null); labels.add("⚠ " + storageWarning); }
                        for (int i = 0; fr != null && i < fr.length(); i++) {
                            JSONObject p = fr.optJSONObject(i);
                            if (p == null) continue;
                            ps.add(p);
                            String t = p.optString("label") + " — " + stateLabel(p.optString("state"));
                            if (p.optString("verified_at").length() > 0) t += "\n   آخر نجاح: " + CgUi.localText(p.optString("verified_at"));
                            if ("error".equals(p.optString("state")) && p.optString("last_error").length() > 0) t += "\n   " + p.optString("last_error");
                            if (!p.optBoolean("docs_checked")) t += "\n   (وثائق المزوّد لم تُقرأ: لا يُعتمد إلا بطلب حقيقي)";
                            labels.add(t);
                        }
                        CgUi.choose(SubCenterActivity.this, "المصادر — اضغط مصدراً لاختبار طلب حقيقي", labels.toArray(new CharSequence[0]), new CgUi.IntCb() {
                            @Override public void run(int w) {
                                if (ps.get(w) == null) { CgUi.info(SubCenterActivity.this, "تحذير أمني", storageWarning + "\nراجع docs/backend/DEPLOY.md (خطوة تأمين التخزين)."); return; }
                                verifyProvider(ps.get(w).optString("id"), ps.get(w).optString("label"));
                            }
                        });
                    }
                });
            }
        });
    }

    private void verifyProvider(final String id, final String label) {
        showLoading("جارٍ اختبار " + label + "...");
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    JSONObject r = CxApi.post("/admin/subhub/providers/verify", new JSONObject().put("provider", id));
                    msg = (r.optBoolean("ok") ? "نجح الطلب الحقيقي.\n" : "فشل الطلب.\n") + r.optString("detail");
                } catch (CxApi.Failure f) {
                    msg = f.getMessage();
                } catch (Throwable t) {
                    msg = "تعذر الاختبار.";
                }
                final String fm = msg;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive()) return;
                        hideLoading();
                        CgUi.info(SubCenterActivity.this, label, fm);
                    }
                });
            }
        });
    }

    private void publishApproved() {
        final List<SubQueue.Job> ok = new ArrayList<SubQueue.Job>();
        for (SubQueue.Job j : SubCenterStore.queue(this).all()) if (j.state == SubQueue.State.APPROVED) ok.add(j);
        if (ok.isEmpty()) { CgUi.info(this, "لا توجد ملفات معتمدة", "اعتمد الملفات من صفحة كل عنوان بعد معاينتها أولاً."); return; }
        CgUi.confirm(this, "نشر " + ok.size() + " ملفاً معتمداً؟", "يُرفع كل ملف معتمد إلى R2 ويُربط بعنوانه في التطبيق. الملف الموجود سابقاً لعنوانٍ ما لا يُستبدل إلا بموافقتك على كل حالة.", "انشر", false, new Runnable() {
            @Override public void run() { SubPublish.publishAll(SubCenterActivity.this, ok, new Runnable() { @Override public void run() { changed(); } }); }
        });
    }

    // ------------------------------------------------------------------ list

    private void renderList() {
        listBox.removeAllViews();
        List<SubQueue.Job> all = filtered();
        listBox.addView(CgUi.muted(this, all.size() + " عنواناً"), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 0, 4, 4));
        if (all.isEmpty()) {
            listBox.addView(CgUi.empty(this, "لا توجد عناوين", catalogueMissing == 0 ? "كل الأفلام والحلقات لها ترجمة." : "غيّر الفلتر أو البحث."));
            return;
        }
        int n = Math.min(limit, all.size());
        for (int i = 0; i < n; i++) listBox.addView(row(all.get(i)), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));
        if (n < all.size()) {
            TextView more = CgUi.button(this, "عرض المزيد (" + (all.size() - n) + ")", 1);
            more.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { limit += PAGE; renderList(); }
            });
            listBox.addView(more);
        }
    }

    private View row(final SubQueue.Job j) {
        LinearLayout c = CgUi.card(this);
        c.setClickable(true);
        CgUi.pressScale(c);
        TextView t = CgUi.text(this, j.displayTitle(), 14.5f, CgCfg.TEXT, true);
        t.setMaxLines(2);
        c.addView(t);
        LinearLayout b = CgUi.hbox(this);
        b.addView(CgUi.badge(this, SubQueue.label(j.state), colorOf(j.state)));
        b.addView(CgUi.badge(this, j.isEpisode() ? "حلقة" : "فيلم", CgCfg.GRAY), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
        if (j.quality.length() > 0) b.addView(CgUi.badge(this, "PASS".equals(j.quality) ? "فحص سليم" : "REVIEW".equals(j.quality) ? "يحتاج مراجعة" : "به أخطاء",
                "PASS".equals(j.quality) ? CgCfg.GOOD : "REVIEW".equals(j.quality) ? CgCfg.WARN : CgCfg.BAD), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
        if (j.sourceProvider.length() > 0) b.addView(CgUi.badge(this, j.sourceProvider, CgCfg.ACCENT), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
        c.addView(b, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 4));
        TextView ids = CgUi.muted(this, "ID " + j.contentId + (j.tmdbId.length() > 0 ? " · TMDB " + j.tmdbId : "") + (j.imdbId.length() > 0 ? " · " + j.imdbId : ""));
        CgUi.ltr(ids);
        c.addView(ids);
        if (j.lastError.length() > 0) {
            TextView e = CgUi.text(this, j.lastError, 12.5f, CgCfg.WARN, false);
            e.setMaxLines(2);
            c.addView(e);
        }
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SubCenterActivity.this, SubJobActivity.class).putExtra("id", j.contentId));
            }
        });
        return c;
    }

    // ------------------------------------------------------------------ settings / history

    private void settings() {
        final android.content.SharedPreferences p = SubCenterStore.prefs(this);
        CharSequence[] items = {
                "عدد العناوين المتزامنة: " + SubCenterStore.concurrency(this),
                "حصة OpenSubtitles اليومية (تحميلات): " + (SubCenterStore.queue(this).quotaLimit("opensubtitles") == 0 ? "بلا حد" : SubCenterStore.queue(this).quotaLimit("opensubtitles")),
                "سعر المزوّد لكل 1000 حرف: " + (SubCenterStore.pricePer1000(this) <= 0 ? "غير مُدخل" : String.valueOf(SubCenterStore.pricePer1000(this)) + " " + SubCenterStore.currency(this)),
        };
        CgUi.choose(this, "إعدادات المركز", items, new CgUi.IntCb() {
            @Override public void run(int which) {
                if (which == 0) {
                    CgUi.choose(SubCenterActivity.this, "عدد العناوين المتزامنة", new CharSequence[]{"1", "2", "3", "4"}, new CgUi.IntCb() {
                        @Override public void run(int w) { p.edit().putInt("concurrency", w + 1).apply(); changed(); }
                    });
                } else if (which == 1) {
                    CgUi.input(SubCenterActivity.this, "حصة OpenSubtitles اليومية", "عدد التحميلات المسموح بها يومياً حسب اشتراكك (0 = بلا حد)", String.valueOf(SubCenterStore.queue(SubCenterActivity.this).quotaLimit("opensubtitles")), false, new CgUi.Callback<String>() {
                        @Override public void run(String v) {
                            int n = 0;
                            try { n = Integer.parseInt(v.trim()); } catch (Exception ignored) { }
                            p.edit().putInt("quota_opensubtitles", Math.max(0, n)).apply();
                            SubCenterStore.queue(SubCenterActivity.this).setQuota("opensubtitles", Math.max(0, n));
                            SubCenterStore.save(SubCenterActivity.this);
                            changed();
                        }
                    });
                } else {
                    CgUi.input(SubCenterActivity.this, "سعر المزوّد لكل 1000 حرف", "من عقد/لوحة المزوّد (مثال: 0.02). العملة تُكتب بعد مسافة: 0.02 USD", SubCenterStore.pricePer1000(SubCenterActivity.this) > 0 ? SubCenterStore.pricePer1000(SubCenterActivity.this) + " " + SubCenterStore.currency(SubCenterActivity.this) : "", false, new CgUi.Callback<String>() {
                        @Override public void run(String v) {
                            String[] parts = v.trim().split("\\s+");
                            float f = 0;
                            try { f = Float.parseFloat(parts[0].replace(',', '.')); } catch (Exception ignored) { }
                            android.content.SharedPreferences.Editor e = p.edit().putFloat("price_per_1000", Math.max(0f, f));
                            if (parts.length > 1) e.putString("currency", parts[1].toUpperCase(Locale.US));
                            e.apply();
                        }
                    });
                }
            }
        });
    }

    private void history() {
        JSONArray h = SubCenterStore.history(this);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < h.length() && i < 80; i++) {
            JSONObject o = h.optJSONObject(i);
            if (o == null) continue;
            b.append("• ").append(o.optString("title")).append("\n   ").append(o.optString("action")).append(" · ").append(CgUi.localText(o.optString("at_iso")))
                    .append(o.optString("url").length() > 0 ? "\n   " + o.optString("url") : "").append("\n\n");
        }
        CgUi.info(this, "سجل النشر", b.length() == 0 ? "لم يُنشر شيء بعد من هذا الجهاز." : b.toString());
    }
}
