package com.my.newproject;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.Cigram.subhub.EpisodeMatcher;
import com.Cigram.subhub.SafeZip;
import com.Cigram.subhub.SubCodec;
import com.Cigram.subhub.SubDocument;
import com.Cigram.subhub.SubQa;
import com.Cigram.subhub.SubQueue;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * رفع ترجمات موسم كامل — one or many episode subtitles, or a whole-season package (.zip), for ONE series and ONE season.
 *
 * Files are read on this phone (ZIP extracted safely in memory), every file is parsed and checked, and matched to an
 * episode ONLY by the season / episode number written in its name. Unmatched, ambiguous, other-season and double-episode
 * files are shown apart and never attached automatically; the admin can correct any row by hand. Nothing is uploaded
 * before «رفع وربط» is pressed, and an episode that already has a subtitle is only replaced when the admin confirms.
 */
public class CgSeasonSubsActivity extends CgBase {

    private static final int REQ_FILES = 7851;

    private JSONObject series;
    private int season;
    private final List<EpisodeMatcher.Episode> episodes = new ArrayList<EpisodeMatcher.Episode>();
    private final Map<String, byte[]> files = new HashMap<String, byte[]>();
    private final Map<String, SubQa.Report> reports = new HashMap<String, SubQa.Report>();
    private final List<String> skipped = new ArrayList<String>();
    private List<EpisodeMatcher.Match> matches = new ArrayList<EpisodeMatcher.Match>();
    private LinearLayout page;

    @Override
    protected String screenTitle() {
        return "ترجمات موسم كامل";
    }

    @Override
    protected void onBuild() {
        ScrollView sv = new ScrollView(this);
        page = CgUi.vbox(this);
        page.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 4), CgUi.dp(this, 12), CgUi.dp(this, 90));
        sv.addView(page);
        body.addView(sv, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));
        ensureData(new Runnable() { @Override public void run() { render(); } });
    }

    private void render() {
        page.removeAllViews();
        LinearLayout step = CgUi.card(this);
        step.addView(CgUi.text(this, "1. المسلسل والموسم", 15, CgCfg.TEXT, true));
        step.addView(CgUi.muted(this, series == null ? "لم يُحدد" : CgData.s(series, "title") + " · الموسم " + season + " · " + episodes.size() + " حلقة في الكتالوج"));
        TextView choose = CgUi.button(this, series == null ? "اختيار المسلسل" : "تغيير المسلسل / الموسم", series == null ? 0 : 1);
        choose.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { chooseSeries(); } });
        step.addView(choose, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
        page.addView(step, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 8));
        if (series == null) return;

        LinearLayout pick = CgUi.card(this);
        pick.addView(CgUi.text(this, "2. الملفات", 15, CgCfg.TEXT, true));
        pick.addView(CgUi.muted(this, "ملف أو عدة ملفات SRT / VTT، أو حزمة موسم ZIP. تُقرأ هنا ولا يُرفع شيء قبل التأكيد."));
        TextView p = CgUi.button(this, files.isEmpty() ? "اختيار الملفات" : "إضافة ملفات", files.isEmpty() ? 0 : 1);
        p.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickFiles(); } });
        pick.addView(p, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
        for (String s : skipped) pick.addView(CgUi.text(this, "تجاهل: " + s, 12, CgCfg.FAINT, false));
        page.addView(pick, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        if (matches.isEmpty()) return;

        LinearLayout ok = CgUi.card(this);
        LinearLayout bad = CgUi.card(this);
        int nOk = 0, nBad = 0;
        for (EpisodeMatcher.Match m : matches) {
            if (m.state == EpisodeMatcher.State.MATCHED) { ok.addView(row(m)); nOk++; }
            else { bad.addView(row(m)); nBad++; }
        }
        ok.addView(CgUi.muted(this, nOk + " ملفاً مطابقاً"), 0);
        ok.addView(CgUi.text(this, "3. المطابقة", 15, CgCfg.TEXT, true), 0);
        page.addView(ok, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        if (nBad > 0) {
            bad.addView(CgUi.muted(this, "لا تُربط إلا إذا حددت الحلقة يدوياً"), 0);
            bad.addView(CgUi.text(this, "ملفات غير مطابقة (" + nBad + ")", 15, CgCfg.WARN, true), 0);
            page.addView(bad, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
        TextView go = CgUi.button(this, "رفع وربط " + nOk + " ملفاً", 0);
        go.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(); } });
        page.addView(go, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
    }

    private View row(final EpisodeMatcher.Match m) {
        LinearLayout r = CgUi.vbox(this);
        r.setPadding(0, CgUi.dp(this, 8), 0, CgUi.dp(this, 8));
        r.setClickable(true);
        TextView n = CgUi.text(this, m.file, 13.5f, CgCfg.TEXT, true);
        CgUi.ltr(n);
        r.addView(n);
        String parsed = m.parsed == null ? "بلا رقم" : String.format(Locale.US, "S%02dE%02d", m.parsed.season, m.parsed.episode);
        String target = m.target == null ? "—" : String.format(Locale.US, "S%02dE%02d", m.target.season, m.target.episode) + " · " + m.target.title + (m.target.hasSubtitle ? " · لها ترجمة حالياً" : "");
        LinearLayout b = CgUi.hbox(this);
        b.addView(CgUi.badge(this, EpisodeMatcher.label(m.state) + (m.manual ? " (يدوي)" : ""), m.state == EpisodeMatcher.State.MATCHED ? CgCfg.GOOD : CgCfg.WARN));
        SubQa.Report rep = reports.get(m.file);
        if (rep != null) b.addView(CgUi.badge(this, rep.summary(), rep.verdict() == SubQa.Verdict.PASS ? CgCfg.GOOD : rep.verdict() == SubQa.Verdict.REVIEW ? CgCfg.WARN : CgCfg.BAD), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
        r.addView(b, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 2));
        r.addView(CgUi.muted(this, "في الاسم: " + parsed + "  ←  " + target));
        r.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { correct(m); } });
        return r;
    }

    private void correct(final EpisodeMatcher.Match m) {
        CharSequence[] labels = new CharSequence[episodes.size() + 1];
        labels[0] = "عدم ربط هذا الملف";
        for (int i = 0; i < episodes.size(); i++) {
            EpisodeMatcher.Episode e = episodes.get(i);
            labels[i + 1] = String.format(Locale.US, "S%02dE%02d", e.season, e.episode) + " · " + e.title + (e.hasSubtitle ? " · لها ترجمة" : "");
        }
        CgUi.choose(this, "ربط «" + m.file + "» بـ", labels, new CgUi.IntCb() {
            @Override public void run(int w) {
                EpisodeMatcher.assign(m, w == 0 ? null : episodes.get(w - 1));
                render();
            }
        });
    }

    private void chooseSeries() {
        final CgData d = CgData.cur();
        if (d == null) return;
        final List<JSONObject> hs = d.seriesHeaders();
        CharSequence[] labels = new CharSequence[hs.size()];
        for (int i = 0; i < hs.size(); i++) labels[i] = CgData.s(hs.get(i), "title");
        CgUi.choose(this, "المسلسل", labels, new CgUi.IntCb() {
            @Override public void run(int w) {
                final JSONObject h = hs.get(w);
                final List<Integer> ss = d.seasonsOf(h);
                if (ss.isEmpty()) { CgUi.info(CgSeasonSubsActivity.this, "لا مواسم", "لا توجد حلقات لهذا المسلسل في الكتالوج."); return; }
                CharSequence[] sl = new CharSequence[ss.size()];
                for (int i = 0; i < ss.size(); i++) sl[i] = "الموسم " + ss.get(i);
                CgUi.choose(CgSeasonSubsActivity.this, "الموسم", sl, new CgUi.IntCb() {
                    @Override public void run(int s) {
                        series = h;
                        season = ss.get(s);
                        episodes.clear();
                        for (JSONObject e : d.episodesOf(h)) {
                            if (CgData.n(e, "season") != season) continue;
                            episodes.add(new EpisodeMatcher.Episode(CgData.s(e, "id"), season, CgData.n(e, "episode"), CgData.s(e, "title"), CgData.s(e, "subtitle").length() > 0));
                        }
                        rematch();
                        render();
                    }
                });
            }
        });
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, REQ_FILES); } catch (Exception e) { CgUi.toast(this, "تعذر فتح منتقي الملفات."); }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILES || res != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<Uri>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        showLoading("جارٍ قراءة الملفات وفحصها...");
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                final StringBuilder err = new StringBuilder();
                for (Uri u : uris) {
                    try {
                        String name = CgSubtitleUpload.displayName(CgSeasonSubsActivity.this, u);
                        byte[] raw = readLimited(u, 40L * 1024 * 1024);
                        if (SafeZip.isZip(raw)) {
                            SafeZip.Result z = SafeZip.extract(new ByteArrayInputStream(raw), raw.length);
                            for (SafeZip.Entry e : z.files) add(e.name, e.data);
                            skipped.addAll(z.skipped);
                        } else if (SafeZip.isSubtitleName(name)) {
                            add(name, raw);
                        } else {
                            skipped.add(name + " (ليس SRT/VTT/ZIP)");
                        }
                    } catch (Throwable t) {
                        err.append(t.getMessage() == null ? "تعذر قراءة ملف." : t.getMessage()).append('\n');
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive()) return;
                        hideLoading();
                        rematch();
                        render();
                        if (err.length() > 0) CgUi.info(CgSeasonSubsActivity.this, "بعض الملفات لم تُقرأ", err.toString());
                    }
                });
            }
        });
    }

    private void add(String name, byte[] raw) {
        String unique = name;
        int k = 2;
        while (files.containsKey(unique)) unique = name.replaceAll("(\\.[A-Za-z]+)$", "-" + (k++) + "$1");
        try {
            SubDocument d = SubCodec.read(raw);
            files.put(unique, SubCodec.writeBytes(d, d.format));   // normalized, original times
            reports.put(unique, SubQa.analyze(d, new SubQa.Options()));
        } catch (Throwable t) {
            skipped.add(unique + " (" + (t.getMessage() == null ? "غير صالح" : t.getMessage()) + ")");
        }
    }

    private byte[] readLimited(Uri u, long max) throws Exception {
        InputStream in = getContentResolver().openInputStream(u);
        if (in == null) throw new Exception("تعذر فتح الملف.");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new Exception("الملف كبير جداً.");
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private void rematch() {
        Map<String, EpisodeMatcher.Match> manual = new HashMap<String, EpisodeMatcher.Match>();
        for (EpisodeMatcher.Match m : matches) if (m.manual) manual.put(m.file, m);
        matches = EpisodeMatcher.match(new ArrayList<String>(files.keySet()), episodes, season);
        for (EpisodeMatcher.Match m : matches) {
            EpisodeMatcher.Match prev = manual.get(m.file);
            if (prev != null && prev.target != null && prev.target.season == season) EpisodeMatcher.assign(m, prev.target);
        }
    }

    // ------------------------------------------------------------------ upload + link

    private void save() {
        final List<String> conflicts = EpisodeMatcher.conflicts(matches);
        if (!conflicts.isEmpty()) {
            CgUi.info(this, "تعارض", "أكثر من ملف مربوط بنفس الحلقة: " + conflicts + ". صحّح المطابقة أولاً.");
            return;
        }
        final List<EpisodeMatcher.Match> todo = new ArrayList<EpisodeMatcher.Match>();
        int replace = 0, failing = 0;
        for (EpisodeMatcher.Match m : matches) {
            if (m.state != EpisodeMatcher.State.MATCHED || m.target == null) continue;
            SubQa.Report r = reports.get(m.file);
            if (r != null && r.verdict() == SubQa.Verdict.FAIL) { failing++; continue; }
            todo.add(m);
            if (m.target.hasSubtitle) replace++;
        }
        if (todo.isEmpty()) { CgUi.info(this, "لا شيء للرفع", "لا توجد ملفات مطابقة سليمة" + (failing > 0 ? " (" + failing + " ملفاً به أخطاء استُبعد)." : ".")); return; }
        final int fReplace = replace;
        String msg = "سيُرفع " + todo.size() + " ملفاً ويُربط كل ملف بحلقته." + (failing > 0 ? "\n" + failing + " ملفاً به أخطاء لن يُرفع." : "")
                + (replace > 0 ? "\n" + replace + " حلقة لها ترجمة حالياً: ستُستبدل." : "");
        CgUi.confirm(this, "تأكيد الرفع", msg, fReplace > 0 ? "رفع واستبدال" : "رفع وربط", fReplace > 0, new Runnable() {
            @Override public void run() { upload(todo); }
        });
    }

    private void upload(final List<EpisodeMatcher.Match> todo) {
        final CgData d = CgData.cur();
        String s0 = d == null ? "" : d.slugOf(series);
        if (s0.length() == 0) {
            CgUi.info(this, "مجلد R2 غير معروف", "لا يوجد Slug محفوظ لهذا المسلسل. أضفه من صفحة المسلسل ثم أعد المحاولة.");
            return;
        }
        final String slug = s0;
        showLoading("جارٍ الرفع (0 من " + todo.size() + ")...");
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                int ok = 0;
                final StringBuilder errs = new StringBuilder();
                for (int i = 0; i < todo.size(); i++) {
                    final EpisodeMatcher.Match m = todo.get(i);
                    final int idx = i;
                    runOnUiThread(new Runnable() { @Override public void run() { if (alive()) loadingMessage("جارٍ الرفع (" + (idx + 1) + " من " + todo.size() + ")..."); } });
                    try {
                        byte[] bytes = files.get(m.file);
                        String ext = m.file.toLowerCase(Locale.US).endsWith(".vtt") ? "vtt" : "srt";
                        String name = String.format(Locale.US, "S%02dE%02d.ar.%s", m.target.season, m.target.episode, ext);
                        JSONObject r = CgMediaApi.putSubtitle("episode", slug, name, m.target.season, m.target.episode, m.target.hasSubtitle ? "overwrite" : "rename", bytes);
                        String url = r.optString("publicUrl", r.optString("url", ""));
                        if (url.length() == 0) throw new Exception("لم يصل رابط الملف.");
                        CxApi.post("/admin/item/update", new JSONObject().put("id", m.target.id).put("changes", new JSONObject().put("subtitle", url)));
                        record(m, bytes, url);
                        ok++;
                    } catch (CxApi.Failure f) {
                        errs.append("• ").append(m.file).append(": ").append(f.getMessage()).append('\n');
                    } catch (Throwable t) {
                        errs.append("• ").append(m.file).append(": ").append(t.getMessage()).append('\n');
                    }
                }
                final int fOk = ok;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive()) return;
                        hideLoading();
                        CgUi.info(CgSeasonSubsActivity.this, "انتهى الرفع", "رُبط " + fOk + " ملفاً." + (errs.length() > 0 ? "\n\nتعذّر:\n" + errs : ""));
                        reloadData(new Runnable() { @Override public void run() { chooseSeasonAgain(); } });
                    }
                });
            }
        });
    }

    private void chooseSeasonAgain() {
        CgData d = CgData.cur();
        if (d == null || series == null) return;
        JSONObject h = d.byId(CgData.s(series, "id"));
        if (h != null) series = h;
        episodes.clear();
        for (JSONObject e : d.episodesOf(series)) {
            if (CgData.n(e, "season") != season) continue;
            episodes.add(new EpisodeMatcher.Episode(CgData.s(e, "id"), season, CgData.n(e, "episode"), CgData.s(e, "title"), CgData.s(e, "subtitle").length() > 0));
        }
        rematch();
        render();
    }

    /** Publication history + the Subtitle Center queue know about the admin's upload. */
    private void record(EpisodeMatcher.Match m, byte[] bytes, String url) {
        try {
            SubQueue q = SubCenterStore.queue(this);
            SubQueue.Job j = q.get(m.target.id);
            if (j != null) {
                j.sourceProvider = "manual";
                j.sourceLanguage = "ar";
                SubCenterRunner.checkAndPark(this, q, j, bytes, m.file.toLowerCase(Locale.US).endsWith(".vtt") ? "vtt" : "srt", "ar", "رفع موسم يدوي");
                if (j.state == SubQueue.State.QUALITY_REVIEW) {
                    q.approve(j, SubCenterStore.nowIso(), System.currentTimeMillis());
                    q.markPublished(j, url, SubCenterStore.nowIso(), "", System.currentTimeMillis());
                }
                SubCenterStore.save(this);
            }
            SubCenterStore.addHistory(this, new JSONObject().put("content_id", m.target.id).put("title", CgData.s(series, "title") + String.format(Locale.US, " · S%02dE%02d", m.target.season, m.target.episode))
                    .put("action", m.target.hasSubtitle ? "رفع موسم (استبدال)" : "رفع موسم").put("url", url).put("file", m.file));
        } catch (Throwable ignored) { }
    }

    /** GET a subtitle URL as raw bytes (8 MB max) — used by the previews. */
    static byte[] download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "CigramAdmin");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > SubCodec.MAX_BYTES) throw new Exception("الملف كبير جداً.");
            }
            in.close();
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
