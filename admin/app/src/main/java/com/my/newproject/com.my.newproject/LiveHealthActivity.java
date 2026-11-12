package com.my.newproject;

import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cigram Live Health (Studio 3.0) — every live channel checked the way a player opens it ({@link LiveHealth}),
 * four at a time, with a live count while it runs: تعمل · بطيئة · متوقفة. Stopped channels come first, each with the
 * exact reason, and a tap opens «إدارة القنوات» to fix the link. The result of the last run is remembered on the
 * phone, so a channel that was already down last time is marked «متوقفة منذ الفحص السابق».
 */
public class LiveHealthActivity extends CgBase {

    private static final String PREFS = "cigram_live_health";

    private static final class Row {
        String name = "", category = "", url = "", key = "";
        LiveHealth.Report report;
        boolean wasDown;
    }

    private final ArrayList<Row> rows = new ArrayList<Row>();
    private LinearLayout list, stats;
    private TextView progress, runBtn;
    private boolean running;
    private int filter = -1;                  // -1 all, else LiveHealth state
    private ExecutorService pool;
    private final AtomicInteger generation = new AtomicInteger();

    @Override protected String screenTitle() { return "Cigram Live Health"; }

    @Override protected void onBuild() {
        LinearLayout c = scrollBody();
        TextView intro = CgUi.muted(this, "يفحص كل قناة كما يفتحها المشغل: قائمة التشغيل ثم أحدث مقطع فيديو فعلي. الفحص من شبكة هذا الجهاز: قناة محجوبة في بلدك تظهر «متوقفة» هنا.");
        intro.setGravity(Gravity.RIGHT);
        c.addView(intro, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 0, 4, 10));
        stats = CgUi.vbox(this);
        c.addView(stats);
        runBtn = CgUi.button(this, "بدء الفحص", 0);
        runBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (running) stop(); else start(); } });
        c.addView(runBtn);
        progress = CgUi.muted(this, "");
        progress.setGravity(Gravity.CENTER);
        c.addView(progress, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 4));
        list = CgUi.vbox(this);
        c.addView(list);
        renderStats();
        load();
    }

    @Override protected void onDestroy() {
        stop();
        super.onDestroy();
    }

    private void load() {
        showLoading("جارٍ تحميل القنوات…");
        CgHttp.async(this, new CgHttp.Work<JSONObject>() {
            @Override public JSONObject run() throws Exception {
                CgHttp.Res a = CgHttp.call("GET", CgCfg.CHANNELS_API + "/channels", null, false, 20000, null);
                if (!a.ok()) throw new Exception("تعذر تحميل القنوات: " + a.message());
                return a.json;
            }
        }, new CgHttp.Done<JSONObject>() {
            @Override public void ok(JSONObject root) {
                hideLoading();
                rows.clear();
                collect(root, "", 0);
                SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
                for (Row r : rows) r.wasDown = p.getBoolean("down::" + r.key, false);
                progress.setText(rows.size() + " قناة · آخر فحص: " + (p.getLong("last", 0) > 0 ? CgUi.ago(CgUi.toIso(p.getLong("last", 0))) : "لم يُجرَ بعد"));
                render();
            }
            @Override public void fail(String message) {
                hideLoading();
                list.removeAllViews();
                list.addView(CgUi.errorCard(LiveHealthActivity.this, message, new Runnable() { @Override public void run() { load(); } }));
            }
        });
    }

    /** Channels are found wherever they sit in the answer (flat list, categories → channels, sections → items). */
    private void collect(Object node, String category, int depth) {
        if (node == null || depth > 6 || rows.size() >= 3000) return;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), category, depth + 1);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;
        String url = o.optString("url", o.optString("stream", ""));
        String name = o.optString("name", "");
        if (url.startsWith("http") && name.length() > 0) {
            Row r = new Row();
            r.name = name;
            r.url = url;
            r.category = category.length() > 0 ? category : o.optString("category", "");
            String id = o.optString("id", "");
            r.key = id.length() > 0 ? id : r.category + "|" + name;
            rows.add(r);
            return;
        }
        String cat = name.length() > 0 ? name : category;
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            Object v = o.opt(it.next());
            if (v instanceof JSONArray || v instanceof JSONObject) collect(v, cat, depth + 1);
        }
    }

    private void start() {
        if (rows.isEmpty()) { CgUi.toast(this, "لا توجد قنوات للفحص."); return; }
        running = true;
        final int gen = generation.incrementAndGet();
        for (Row r : rows) r.report = null;
        runBtn.setText("إيقاف الفحص");
        pool = Executors.newFixedThreadPool(4);
        final AtomicInteger done = new AtomicInteger();
        final int total = rows.size();
        for (final Row r : rows) {
            pool.execute(new Runnable() {
                @Override public void run() {
                    if (gen != generation.get()) return;
                    final LiveHealth.Report rep = LiveHealth.check(r.url);
                    ui().post(new Runnable() {
                        @Override public void run() {
                            if (!alive() || gen != generation.get()) return;
                            r.report = rep;
                            int d = done.incrementAndGet();
                            progress.setText("فُحص " + d + " من " + total);
                            if (d % 6 == 0 || d == total) { renderStats(); render(); }
                            if (d == total) finish(gen);
                        }
                    });
                }
            });
        }
    }

    private void finish(int gen) {
        if (gen != generation.get()) return;
        running = false;
        runBtn.setText("إعادة الفحص");
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().putLong("last", System.currentTimeMillis());
        for (Row r : rows) if (r.report != null && r.report.state == LiveHealth.DOWN) e.putBoolean("down::" + r.key, true);
        e.apply();
        if (pool != null) pool.shutdown();
        renderStats();
        render();
    }

    private void stop() {
        generation.incrementAndGet();
        running = false;
        if (pool != null) pool.shutdownNow();
        if (runBtn != null) runBtn.setText("بدء الفحص");
    }

    private int count(int state) {
        int n = 0;
        for (Row r : rows) if (r.report != null && r.report.state == state) n++;
        return n;
    }

    private void renderStats() {
        stats.removeAllViews();
        View up = StudioUi.stat(this, "تعمل", String.valueOf(count(LiveHealth.UP)), CgCfg.GOOD);
        View slow = StudioUi.stat(this, "بطيئة", String.valueOf(count(LiveHealth.SLOW)), CgCfg.WARN);
        View down = StudioUi.stat(this, "متوقفة", String.valueOf(count(LiveHealth.DOWN)), CgCfg.BAD);
        View[] tiles = {up, slow, down};
        final int[] states = {LiveHealth.UP, LiveHealth.SLOW, LiveHealth.DOWN};
        for (int i = 0; i < tiles.length; i++) {
            final int st = states[i];
            tiles[i].setAlpha(filter == -1 || filter == st ? 1f : 0.45f);
            tiles[i].setClickable(true);
            tiles[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { filter = filter == st ? -1 : st; renderStats(); render(); }
            });
        }
        stats.addView(StudioUi.line(this, up, slow, down));
        CgUi.applyTree(stats);
    }

    private void render() {
        list.removeAllViews();
        ArrayList<Row> show = new ArrayList<Row>();
        for (Row r : rows) if (filter == -1 || (r.report != null && r.report.state == filter)) show.add(r);
        Collections.sort(show, new Comparator<Row>() {
            @Override public int compare(Row a, Row b) {
                int sa = a.report == null ? -1 : a.report.state, sb = b.report == null ? -1 : b.report.state;
                if (sa != sb) return sb - sa;                       // stopped first, unchecked last
                return a.name.compareTo(b.name);
            }
        });
        LinearLayout group = StudioUi.group(this);
        int shown = 0;
        for (final Row r : show) {
            if (shown++ >= 250) break;
            int color = r.report == null ? CgCfg.GRAY : r.report.state == LiveHealth.UP ? CgCfg.GOOD : r.report.state == LiveHealth.SLOW ? CgCfg.WARN : CgCfg.BAD;
            String sub = r.report == null ? (r.wasDown ? "متوقفة في الفحص السابق" : (r.category.length() > 0 ? r.category : "لم تُفحص بعد"))
                    : r.report.detail() + (r.report.state == LiveHealth.DOWN && r.wasDown ? " · متوقفة منذ الفحص السابق" : "");
            StudioUi.row(group, CgIcons.SIGNAL, r.name, sub, r.report == null ? "" : r.report.label(), color, new Runnable() {
                @Override public void run() { startActivity(new android.content.Intent(LiveHealthActivity.this, CgChannelsActivity.class)); }
            });
        }
        if (show.isEmpty()) list.addView(CgUi.empty(this, rows.isEmpty() ? "لا توجد قنوات" : "لا قنوات بهذه الحالة", ""));
        else list.addView(group);
        if (show.size() > 250) list.addView(CgUi.muted(this, "تُعرض أول 250 قناة من " + show.size() + "."));
        CgUi.applyTree(list);
    }
}
