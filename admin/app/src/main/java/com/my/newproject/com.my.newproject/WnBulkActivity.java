package com.my.newproject;

import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bulk import: for every selected title the same automatic workflow runs (video -> subtitle -> validation -> import) with a
 * real per-title progress line. A title that fails never stops the others, and any failed title can be retried alone.
 * Titles run one after another so provider rate limits are respected.
 */
public class WnBulkActivity extends CgBase {

    static final String EXTRA_CARDS = "cards";

    private static final class Row {
        JSONObject card;
        String key;
        String nonce = WnLogic.newRequestId("b");
        LinearLayout view;
        TextView status;
        TextView detail;
        ProgressBar bar;
        TextView retry;
        WnPipeline.Result result;
        boolean running;
    }

    private final LinkedHashMap<String, Row> rows = new LinkedHashMap<>();
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private volatile boolean running;

    // options
    private boolean subtitles = true;
    private boolean ack;
    private boolean notify;
    private boolean latestOnly;
    private String sectionId = "";
    private String newSection = "";
    private String newSectionType = "";

    private LinearLayout optionsBox;
    private LinearLayout list;
    private TextView startBtn;
    private TextView summary;

    @Override
    protected String screenTitle() {
        return "استيراد مجمّع";
    }

    @Override
    protected void onBuild() {
        JSONArray arr;
        try {
            arr = new JSONArray(getIntent().getStringExtra(EXTRA_CARDS));
        } catch (Exception e) {
            arr = new JSONArray();
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = arr.optJSONObject(i);
            if (c == null) continue;
            Row r = new Row();
            r.card = c;
            r.key = c.optString("key");
            rows.put(r.key, r);
        }
        bar.subtitle(rows.size() + " عنوان");
        LinearLayout c = scrollBody();
        optionsBox = WnUi.glass(this);
        optionsBox.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        c.addView(optionsBox);
        buildOptions();

        startBtn = CgUi.button(this, "ابدأ الاستيراد", 0);
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onStartClicked(); }
        });
        c.addView(startBtn, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        summary = CgUi.text(this, "", 13, CgCfg.ACCENT, true);
        summary.setPadding(CgUi.dp(this, 4), 0, 0, CgUi.dp(this, 8));
        c.addView(summary);
        list = CgUi.vbox(this);
        c.addView(list);
        for (Row r : rows.values()) list.addView(buildRow(r));
        refreshSummary();
        // the section chips come from the already-loaded catalogue; load it quietly (no overlay, no error page) if needed
        if (!CgData.loaded()) {
            CgData.load(this, new CgHttp.Done<CgData>() {
                @Override public void ok(CgData d) { if (alive()) buildOptions(); }
                @Override public void fail(String message) { }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (!running) { super.onBackPressed(); return; }
        CgUi.confirm(this, "إيقاف الاستيراد؟", "ما تمت إضافته يبقى كما هو. العناوين المتبقية لن تُعالج.", "أوقف", true, new Runnable() {
            @Override public void run() { cancel.set(true); }
        });
    }

    // ------------------------------------------------------------------ options

    private void buildOptions() {
        optionsBox.removeAllViews();
        optionsBox.addView(CgUi.text(this, "إعدادات الدفعة", 15, CgCfg.TEXT, true));
        CgUi.Toggle s = CgUi.toggle(this, "البحث عن الترجمة وفحصها تلقائياً", subtitles);
        s.onChange(new Runnable() { @Override public void run() { subtitles = !subtitles; } });
        optionsBox.addView(s);
        CgUi.Toggle l = CgUi.toggle(this, "المسلسلات: آخر موسم فقط (بدل كل المواسم)", latestOnly);
        l.onChange(new Runnable() { @Override public void run() { latestOnly = !latestOnly; } });
        optionsBox.addView(l);
        CgUi.Toggle n = CgUi.toggle(this, "إشعار المستخدمين على الهاتف (قد يكون كثيراً في الدفعات)", notify);
        n.onChange(new Runnable() { @Override public void run() { notify = !notify; } });
        optionsBox.addView(n);
        CgUi.Toggle a = CgUi.toggle(this, "أقرّ باستيراد مصادر مشغّلات مضمَّنة لا يمكن إثبات تشغيلها من الخادم", ack);
        a.onChange(new Runnable() { @Override public void run() { ack = !ack; } });
        optionsBox.addView(a);
        TextView hint = CgUi.muted(this, "بدون هذا الإقرار تتوقف العناوين ذات الفيديو غير القابل للإثبات عند «بانتظار إقرارك» ولا يُكتب شيء.");
        hint.setPadding(0, 0, 0, CgUi.dp(this, 6));
        optionsBox.addView(hint);

        TextView lab = CgUi.muted(this, "القسم لكل العناوين");
        lab.setPadding(0, CgUi.dp(this, 6), 0, CgUi.dp(this, 4));
        optionsBox.addView(lab);
        CgUi.ChipBar chips = new CgUi.ChipBar(this);
        TextView none = chips.add("بدون قسم", sectionId.length() == 0 && newSection.length() == 0);
        none.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sectionId = ""; newSection = ""; buildOptions(); }
        });
        CgData d = CgData.cur();
        if (d != null) {
            for (final JSONObject sec : d.sections) {
                TextView t = chips.add(sec.optString("name"), sec.optString("id").equals(sectionId));
                t.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { sectionId = sec.optString("id"); newSection = ""; buildOptions(); }
                });
            }
        }
        TextView add = chips.add(newSection.length() > 0 ? "＋ " + newSection : "＋ قسم جديد", newSection.length() > 0);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CgUi.input(WnBulkActivity.this, "قسم جديد", "اسم القسم", newSection, false, new CgUi.Callback<String>() {
                    @Override public void run(String name) {
                        if (name.length() == 0) return;
                        newSection = name;
                        newSectionType = "mixed";
                        sectionId = "";
                        buildOptions();
                    }
                });
            }
        });
        optionsBox.addView(chips.scroll, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, -12, 0, -12, 0));
        TextView cat = CgUi.muted(this, "التصنيفات: تُستخدم تصنيفات TMDB لكل عنوان. القسم الجديد يُنشأ مرة واحدة ويُعاد استخدامه.");
        cat.setPadding(0, CgUi.dp(this, 4), 0, 0);
        optionsBox.addView(cat);
    }

    private WnPipeline.Options optionsFor(Row r) {
        WnPipeline.Options o = new WnPipeline.Options();
        o.nonce = r.nonce;
        o.subtitles = subtitles;
        o.latestSeasonOnly = latestOnly;
        o.flags.ackUnverified = ack;
        o.flags.notify = notify;
        o.placement.sectionId = sectionId;
        o.placement.newSection = newSection;
        o.placement.newSectionType = newSectionType;
        return o;
    }

    // ------------------------------------------------------------------ rows

    private View buildRow(final Row r) {
        LinearLayout box = WnUi.glass(this);
        box.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        LinearLayout top = CgUi.hbox(this);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(CgUi.bg(this, CgCfg.CARD2, 8, 0));
        CgImg.load(iv, r.card.optString("poster"), CgUi.dp(this, 100));
        top.addView(iv, new LinearLayout.LayoutParams(CgUi.dp(this, 46), CgUi.dp(this, 68)));
        LinearLayout t = CgUi.vbox(this);
        t.setPadding(CgUi.dp(this, 10), 0, CgUi.dp(this, 6), 0);
        TextView title = CgUi.text(this, r.card.optString("title"), 14, CgCfg.TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(title);
        t.addView(CgUi.muted(this, WnLogic.typeLabel(r.card.optString("type")) + (r.card.optString("year").length() > 0 ? " · " + r.card.optString("year") : "") + " · TMDB " + r.card.optLong("tmdb_id")));
        r.status = CgUi.text(this, "في الانتظار", 12.5f, CgCfg.MUTED, true);
        t.addView(r.status);
        top.addView(t, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        r.retry = CgUi.smallButton(this, "إعادة", 1);
        r.retry.setVisibility(View.GONE);
        r.retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { retryOne(r); }
        });
        top.addView(r.retry, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        box.addView(top);
        r.bar = WnUi.progress(this, 100, 0);
        r.bar.setVisibility(View.GONE);
        box.addView(r.bar, CgUi.lp(this, CgUi.MATCH, CgUi.dp(this, 8), 0, 8, 0, 0));
        r.detail = CgUi.text(this, "", 12, CgCfg.MUTED, false);
        r.detail.setVisibility(View.GONE);
        r.detail.setGravity(Gravity.START);
        box.addView(r.detail);
        r.view = box;
        return box;
    }

    private void showRow(Row r) {
        if (r.running) {
            r.status.setText("جارٍ المعالجة…");
            r.status.setTextColor(CgCfg.CYAN);
            r.retry.setVisibility(View.GONE);
            return;
        }
        WnPipeline.Result res = r.result;
        if (res == null) {
            r.status.setText("في الانتظار");
            r.status.setTextColor(CgCfg.MUTED);
            return;
        }
        String label = WnLogic.stateLabel(res.state);
        if (res.ok() && res.created > 0) label += " · " + res.created + (res.key.startsWith("series") ? " حلقة" : "");
        r.status.setText(label);
        r.status.setTextColor(WnLogic.stateColor(res.state));
        StringBuilder sb = new StringBuilder();
        if (res.message.length() > 0) sb.append(res.message);
        if (res.skippedExisting > 0) sb.append(sb.length() > 0 ? "\n" : "").append("تخطّي ").append(res.skippedExisting).append(" موجودة");
        if (res.missingVideo > 0) sb.append(sb.length() > 0 ? "\n" : "").append(res.missingVideo).append(" حلقة بلا فيديو");
        for (int i = 0; i < res.notes.size() && i < 3; i++) sb.append(sb.length() > 0 ? "\n" : "").append("• ").append(res.notes.get(i));
        r.detail.setText(sb.toString());
        r.detail.setVisibility(sb.length() > 0 ? View.VISIBLE : View.GONE);
        r.bar.setVisibility(View.GONE);
        boolean retryable = !res.ok() && !"duplicate".equals(res.state) && !"cancelled".equals(res.state) || "partial".equals(res.state) || "cancelled".equals(res.state);
        r.retry.setVisibility(retryable ? View.VISIBLE : View.GONE);
    }

    private void refreshSummary() {
        int ok = 0, fail = 0, wait = 0, ack = 0;
        for (Row r : rows.values()) {
            if (r.result == null) wait++;
            else if (r.result.ok()) ok++;
            else if ("needs_ack".equals(r.result.state)) ack++;
            else fail++;
        }
        summary.setText("نجح " + ok + " · تعذّر " + fail + " · بانتظار إقرار " + ack + " · متبقٍ " + wait);
    }

    // ------------------------------------------------------------------ running

    private void onStartClicked() {
        if (running) {
            cancel.set(true);
            startBtn.setText("جارٍ الإيقاف…");
            return;
        }
        List<Row> todo = new ArrayList<>();
        for (Row r : rows.values()) if (r.result == null || !r.result.ok() && !"duplicate".equals(r.result.state)) todo.add(r);
        if (todo.isEmpty()) { CgUi.toast(this, "لا يوجد ما يُعالَج."); return; }
        run(todo);
    }

    private void retryOne(Row r) {
        if (running) { CgUi.toast(this, "انتظر انتهاء الدفعة الحالية."); return; }
        List<Row> one = new ArrayList<>();
        one.add(r);
        run(one);
    }

    private void run(final List<Row> todo) {
        running = true;
        cancel.set(false);
        startBtn.setText("إيقاف");
        final List<WnPipeline.Options> opts = new ArrayList<>();
        for (Row r : todo) { r.running = true; r.bar.setVisibility(View.VISIBLE); r.bar.setIndeterminate(true); showRow(r); opts.add(optionsFor(r)); }
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < todo.size(); i++) {
                    final Row r = todo.get(i);
                    final WnPipeline.Options o = opts.get(i);
                    if (cancel.get()) {
                        final WnPipeline.Result c = new WnPipeline.Result(r.key, r.card.optString("title"));
                        c.state = "cancelled";
                        c.message = "أُوقف قبل المعالجة.";
                        runOnUiThread(new Runnable() { @Override public void run() { r.running = false; r.result = c; showRow(r); refreshSummary(); } });
                        continue;
                    }
                    WnPipeline.Result res;
                    try {
                        res = WnPipeline.run(r.card, o, new WnPipeline.Listener() {
                            @Override public void step(String key, final String text) {
                                runOnUiThread(new Runnable() { @Override public void run() { if (alive()) { r.detail.setText(text); r.detail.setVisibility(View.VISIBLE); } } });
                            }
                            @Override public void progress(String key, final int done, final int total) {
                                runOnUiThread(new Runnable() { @Override public void run() {
                                    if (!alive() || total <= 0) return;
                                    r.bar.setIndeterminate(false);
                                    r.bar.setMax(total);
                                    r.bar.setProgress(Math.min(done, total));
                                } });
                            }
                            @Override public boolean cancelled() { return cancel.get(); }
                        });
                    } catch (Throwable t) {
                        res = new WnPipeline.Result(r.key, r.card.optString("title"));
                        res.state = "failed";
                        res.message = CgHttp.friendly(String.valueOf(t.getMessage()));
                        res.retryable = true;
                    }
                    final WnPipeline.Result fr = res;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            r.running = false;
                            r.result = fr;
                            if (!alive()) return;
                            showRow(r);
                            refreshSummary();
                        }
                    });
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() { finished(); }
                });
            }
        });
    }

    private void finished() {
        running = false;
        if (!alive()) return;
        startBtn.setText("ابدأ الاستيراد");
        int ok = 0;
        for (Row r : rows.values()) if (r.result != null && r.result.ok()) ok++;
        refreshSummary();
        if (ok > 0) {
            WnUi.dirty = true;
            CgData.load(this, new CgHttp.Done<CgData>() {
                @Override public void ok(CgData d) { }
                @Override public void fail(String message) { }
            });
        }
        CgUi.toast(this, "انتهت الدفعة — نجح " + ok + " من " + rows.size() + ".");
    }
}
