package com.my.newproject;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;

/**
 * Whole season from the phone in one go:
 *  1. choose the series (existing one), the R2 folder and the default season;
 *  2. pick MANY video + subtitle files at once. Every file name is analysed (S01E04, 1x04, episode-04, الحلقة 4,
 *     "Show - 04", ...) to find the season and the episode; the table can be corrected by hand;
 *  3. every video is uploaded one after the other (multipart, resumable, verified, in the background) as
 *     {slug}/season-01/S01E04.ext, subtitles next to them;
 *  4. when the uploads are done the season is published in one safe write (season/commit), with the episode
 *     titles, pictures and descriptions from TMDB.
 * It never creates a series and never touches the single-episode screen.
 */
public class CxSeasonUploadActivity extends CxBase implements CgUploadManager.Listener {

    private static final String[] STEPS = {"المسلسل", "الملفات", "الرفع", "النشر"};
    private static final int REQ_FILES = 7731;

    /** One picked file. */
    private static final class Row {
        String uri = "";
        String name = "";
        long size;
        boolean subtitle;
        int season;
        int episode;
        boolean guessed;      // number came from a stand-alone number in the name, not from SxxExx
        boolean include = true;
        boolean userIncluded;       // the admin toggled it by hand: do not change it automatically
        boolean seasonFromDefault;  // the name has no season, the default season was used
        String problem = "";  // "" | "duplicate" | "unknown"
        boolean inLibrary;    // the episode already exists in the series

        // upload (written by background threads, read by the screen)
        String sessionId = "";
        volatile String state = "";    // "" | queued | running | done | reused | failed
        volatile String url = "";
        volatile String error = "";
        volatile float fraction;
    }

    private final ArrayList<Row> rows = new ArrayList<Row>();
    private final HashMap<String, Row> bySession = new HashMap<String, Row>();

    private String seriesKey = "";
    private int step;
    private CxUi.Field folder;
    private CxUi.Field defaultSeason;
    private boolean smart;
    private boolean uploading;
    private boolean started;
    private boolean listening;
    private CgUploadManager mgr;
    private LinearLayout listBox;
    private TextView summaryView;
    private boolean refreshQueued;
    private String slug = "";
    private int defSeason = 1;

    @Override
    protected String screenTitle() {
        return "رفع موسم كامل من الهاتف";
    }

    @Override
    protected void onBuild() {
        mgr = CgUploadManager.get(this);
        initCx(STEPS);
        steps.onStep(new CgUi.IntCb() {
            @Override
            public void run(int index) {
                if (started || index >= 2) return;
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
        int startStep = 0;
        String sid = getIntent().getStringExtra("series_id");
        if (sid != null && sid.length() > 0) {
            seriesKey = sid;
            if (header() != null) {
                int s = getIntent().getIntExtra("season", 0);
                if (s > 0) defSeason = s;
                startStep = 1;
            } else {
                seriesKey = "";
            }
        }
        showStep(startStep);
        // 3.0 Bulk Upload Wizard: the files were picked (and checked) in the wizard; they arrive here already loaded
        try {
            ArrayList<Uri> fromWizard = getIntent().getParcelableArrayListExtra("wizard_uris");
            if (fromWizard != null && !fromWizard.isEmpty()) {
                int ws = getIntent().getIntExtra("wizard_season", 0);
                if (ws > 0 && getIntent().getIntExtra("season", 0) <= 0) defSeason = ws;
                int ignored = addFiles(fromWizard);
                if (step == 1) showStep(1);
                snack(fromWizard.size() - ignored + " ملف من معالج الرفع الجماعي" + (startStep == 0 ? " — اختر المسلسل للمتابعة." : "."), CxUi.GOOD);
            }
        } catch (Throwable wizardError) {
            Log.w(CgMediaAnalyzer.TAG, "wizard files not loaded", wizardError);
        }
    }

    @Override
    protected void onDestroy() {
        if (listening && mgr != null) mgr.removeListener(this);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (started && step == 2 && uploading) {
            CgUi.confirm(this, "الرفع جارٍ", "يمكنك الخروج: الرفع يستمر في الخلفية ويظهر إشعار بالتقدم. لنشر الحلقات بعد انتهائه افتح «إضافة موسم كامل» وحلّل المجلد، أو أعد فتح هذه الشاشة بعد الرفع.", "خروج", false, new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
            return;
        }
        if (step == 1 && !started) {
            showStep(0);
            return;
        }
        super.onBackPressed();
    }

    private JSONObject header() {
        CgData d = CgData.cur();
        if (d == null || seriesKey.length() == 0) return null;
        JSONObject h = d.byId(seriesKey);
        if (h != null && CgData.kind(h) == CgData.K_SERIES) return h;
        ArrayList<JSONObject> all = d.seriesHeaders();
        for (int i = 0; i < all.size(); i++) {
            if (CgData.seriesKey(all.get(i)).equals(seriesKey)) return all.get(i);
        }
        return null;
    }

    private void showStep(int i) {
        collectForm();
        step = i;
        newPage(i);
        if (i == 0) stepSeries();
        else if (i == 1) stepFiles();
        else if (i == 2) stepUpload();
        else stepPublish();
    }

    private void collectForm() {
        if (step == 1 && folder != null && defaultSeason != null) {
            slug = folder.text().trim();
            int v = parseInt(defaultSeason.text());
            if (v > 0) defSeason = v;
        }
    }

    private static int parseInt(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ step 0: series

    private String summary(CgData d, JSONObject h) {
        ArrayList<JSONObject> eps = d.episodesOf(h);
        return d.seasonsOf(h).size() + " مواسم • " + eps.size() + " حلقة";
    }

    private void stepSeries() {
        final CgData d = CgData.cur();
        page.addView(CxUi.notice(this, CxUi.INFO, "اختر المسلسل، ثم اختر ملفات الموسم كلها دفعة واحدة من الهاتف. يُعرَف رقم الموسم والحلقة من أسماء الملفات ثم تُرفع بالترتيب وتُنشر معاً."));
        page.addView(CgUi.sectionTitle(this, "اختر المسلسل"));
        final JSONObject h = header();
        LinearLayout card = CgUi.hbox(this);
        card.setBackground(CgUi.bg(this, CgCfg.CARD, 16, h == null ? CgCfg.STROKE : CgCfg.ACCENT));
        card.setPadding(CgUi.dp(this, 10), CgUi.dp(this, 10), CgUi.dp(this, 10), CgUi.dp(this, 10));
        card.setClickable(true);
        CxUi.PosterBox pb = new CxUi.PosterBox(this, 64, 1.5f, "2:3");
        pb.set(h == null ? "" : CxOps2.posterOf(h));
        card.addView(pb);
        LinearLayout col = CgUi.vbox(this);
        col.addView(CgUi.text(this, h == null ? "اضغط لاختيار مسلسل" : CgData.s(h, "title"), 16, CgCfg.TEXT, true));
        col.addView(CgUi.muted(this, h == null ? "قائمة قابلة للبحث" : summary(d, h)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        clp.setMargins(CgUi.dp(this, 12), 0, CgUi.dp(this, 4), 0);
        card.addView(col, clp);
        card.addView(CgUi.text(this, "▾", 18, CgCfg.MUTED, true));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickSeries();
            }
        });
        page.addView(card, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        TextView newSeries = CgUi.button(this, "المسلسل غير موجود؟ أضِفه أولاً", 1);
        newSeries.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CxNav.addSeries(CxSeasonUploadActivity.this);
            }
        });
        page.addView(newSeries);
        bottomButton("التالي ←", 0, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (header() == null) {
                    snack("اختر المسلسل أولاً.", CxUi.WARN);
                    return;
                }
                showStep(1);
            }
        });
    }

    private void pickSeries() {
        CgData d = CgData.cur();
        ArrayList<CxUi.Option> opts = new ArrayList<CxUi.Option>();
        ArrayList<JSONObject> all = d.seriesHeaders();
        for (int i = 0; i < all.size(); i++) {
            JSONObject h = all.get(i);
            opts.add(new CxUi.Option(CgData.seriesKey(h), CgData.s(h, "title"), summary(d, h), CxOps2.posterOf(h)));
        }
        CxUi.pick(this, "اختر المسلسل", opts, seriesKey, null, new CgUi.Callback<CxUi.Option>() {
            @Override
            public void run(CxUi.Option o) {
                seriesKey = o.id;
                slug = "";
                rows.clear();
                showStep(0);
            }
        }, null);
    }

    // ------------------------------------------------------------------ step 1: files

    private void stepFiles() {
        final CgData d = CgData.cur();
        final JSONObject h = header();
        if (h == null) {
            showStep(0);
            return;
        }
        LinearLayout head = CgUi.hbox(this);
        head.setBackground(CgUi.bg(this, CgCfg.CARD, 14, CgCfg.STROKE));
        head.setPadding(CgUi.dp(this, 8), CgUi.dp(this, 8), CgUi.dp(this, 8), CgUi.dp(this, 8));
        CxUi.PosterBox hp = new CxUi.PosterBox(this, 38, 1.5f, "2:3");
        hp.set(CxOps2.posterOf(h));
        head.addView(hp);
        LinearLayout hc = CgUi.vbox(this);
        hc.addView(CgUi.text(this, CgData.s(h, "title"), 14.5f, CgCfg.TEXT, true));
        hc.addView(CgUi.muted(this, summary(d, h)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        hl.setMargins(CgUi.dp(this, 10), 0, 0, 0);
        head.addView(hc, hl);
        TextView change = CgUi.smallButton(this, "تغيير", 3);
        change.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStep(0);
            }
        });
        head.addView(change);
        page.addView(head, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        CxUi.Section sec = new CxUi.Section(this, "المجلد والموسم", "تُرفع الملفات إلى {المجلد}/season-XX/SxxEyy", false, true);
        folder = new CxUi.Field(this, "مجلد R2 (Slug المسلسل)", "my-series", CxUi.T_LTR, true);
        if (slug.length() == 0) {
            slug = d.slugOf(h);
            if (slug.length() == 0) slug = CgData.slugify(CgData.s(h, "title"));
        }
        folder.set(slug);
        folder.hint("المجلد الذي توجد فيه ملفات هذا المسلسل في R2. إن لم يوجد يُنشأ عند الرفع.");
        sec.add(folder.view);
        defaultSeason = new CxUi.Field(this, "رقم الموسم الافتراضي", "1", CxUi.T_NUMBER, true);
        if (getIntent().getIntExtra("season", 0) <= 0 && rows.isEmpty() && defSeason == 1) {
            ArrayList<Integer> seasons = d.seasonsOf(h);
            int max = 0;
            for (int i = 0; i < seasons.size(); i++) max = Math.max(max, seasons.get(i).intValue());
            defSeason = Math.max(1, max + 1);
        }
        defaultSeason.set(String.valueOf(defSeason));
        defaultSeason.hint("للملفات التي لا يحمل اسمها رقم الموسم (مثل Show - 05.mkv).");
        defaultSeason.onChange(new Runnable() {
            @Override
            public void run() {
                int v = parseInt(defaultSeason.text());
                if (v > 0 && v != defSeason) {
                    defSeason = v;
                    applyDefaultSeason();
                    renderRows();
                }
            }
        });
        sec.add(defaultSeason.view);
        page.addView(sec);

        TextView pick = CgUi.button(this, "📁  اختيار ملفات الحلقات (فيديو + ترجمة)", 0);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFiles();
            }
        });
        page.addView(pick, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 6));
        page.addView(CgUi.muted(this, "اختر كل الملفات مرة واحدة (اضغط مطولاً لتحديد عدة ملفات). يمكنك الإضافة مرة أخرى لاحقاً. صيغ الفيديو: mp4 / mkv / mov / webm، والترجمة: srt / vtt."));

        final com.my.newproject.CgUi.Toggle tg = CgUi.toggle(this, "ضغط ذكي (H.265) قبل الرفع — أبطأ ويوفّر حجماً", smart);
        tg.onChange(new Runnable() {
            @Override
            public void run() {
                smart = tg.on;
            }
        });
        page.addView(tg, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 6));

        summaryView = CgUi.text(this, "", 13, CgCfg.ACCENT, true);
        page.addView(summaryView, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 8, 4, 6));
        listBox = CgUi.vbox(this);
        page.addView(listBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        renderRows();

        bottomButton("→ السابق", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStep(0);
            }
        });
        bottomButton("ابدأ الرفع ←", 0, 1.6f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                beginUpload();
            }
        });
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "application/x-matroska", "application/x-subrip", "text/vtt", "text/plain", "application/octet-stream"});
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_FILES);
        } catch (Exception e) {
            Log.e(CgMediaAnalyzer.TAG, "picker failed", e);
            CgUi.info(this, "تعذر فتح منتقي الملفات", "لا يوجد تطبيق لاختيار الملفات على هذا الهاتف.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILES || res != Activity.RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<Uri>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        int ignored = addFiles(uris);
        if (step == 1) showStep(1);
        if (ignored > 0) snack(ignored + " ملف تم تجاهله (صيغة غير مدعومة أو مضاف سابقاً).", CxUi.WARN);
    }

    /** Returns how many picked files were ignored. */
    private int addFiles(ArrayList<Uri> uris) {
        int ignored = 0;
        for (int i = 0; i < uris.size(); i++) {
            Uri u = uris.get(i);
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception e) {
                Log.w(CgMediaAnalyzer.TAG, "persistable permission not granted: " + e.getMessage());
            }
            boolean dup = false;
            for (int k = 0; k < rows.size(); k++) {
                if (rows.get(k).uri.equals(u.toString())) dup = true;
            }
            CgMediaInfo tmp = new CgMediaInfo();
            CgMediaAnalyzer.queryNameAndSize(this, u, tmp);
            boolean video = CgMediaAnalyzer.supportedName(tmp.fileName);
            boolean sub = CxNames.isSubtitle(tmp.fileName);
            if (dup || (!video && !sub) || tmp.size <= 0) {
                ignored++;
                continue;
            }
            Row r = new Row();
            r.uri = u.toString();
            r.name = tmp.fileName;
            r.size = tmp.size;
            r.subtitle = sub;
            int[] f = CxNames.parse(tmp.fileName);
            if (f == null) {
                f = CxNames.parseLoose(tmp.fileName);
                r.guessed = f != null;
            }
            if (f != null) {
                r.season = f[0];
                r.episode = f[1];
            }
            rows.add(r);
        }
        applyDefaultSeason();
        return ignored;
    }

    /** Files whose name has no season take the default season; then problems (duplicates / unknown) are recomputed. */
    private void applyDefaultSeason() {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.episode > 0 && (r.season <= 0 || r.seasonFromDefault)) {
                r.season = defSeason;
                r.seasonFromDefault = true;
            }
        }
        markProblems();
    }

    private void markProblems() {
        CgData d = CgData.cur();
        JSONObject h = header();
        ArrayList<JSONObject> existing = d == null || h == null ? new ArrayList<JSONObject>() : d.episodesOf(h);
        sortRows();
        HashMap<String, Boolean> seenVideo = new HashMap<String, Boolean>();
        HashMap<String, Boolean> seenSub = new HashMap<String, Boolean>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            r.problem = "";
            r.inLibrary = false;
            if (r.season <= 0 || r.episode <= 0) {
                r.problem = "unknown";
                continue;
            }
            for (int k = 0; k < existing.size(); k++) {
                if (CgData.n(existing.get(k), "season") == r.season && CgData.n(existing.get(k), "episode") == r.episode) r.inLibrary = true;
            }
            String key = r.season + ":" + r.episode;
            HashMap<String, Boolean> seen = r.subtitle ? seenSub : seenVideo;
            if (seen.containsKey(key)) {
                r.problem = "duplicate";
                if (r.include && !r.userIncluded) r.include = false;
            } else {
                seen.put(key, Boolean.TRUE);
                if (r.problem.length() == 0 && !r.userIncluded) r.include = true;
            }
        }
    }

    private void sortRows() {
        Collections.sort(rows, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                int sa = a.season <= 0 ? Integer.MAX_VALUE : a.season;
                int sb = b.season <= 0 ? Integer.MAX_VALUE : b.season;
                if (sa != sb) return sa < sb ? -1 : 1;
                int ea = a.episode <= 0 ? Integer.MAX_VALUE : a.episode;
                int eb = b.episode <= 0 ? Integer.MAX_VALUE : b.episode;
                if (ea != eb) return ea < eb ? -1 : 1;
                if (a.subtitle != b.subtitle) return a.subtitle ? 1 : -1;
                if (a.subtitle) {
                    // several subtitle files for one episode: the Arabic one comes first and is the one kept
                    int ka = arabicScore(a.name);
                    int kb = arabicScore(b.name);
                    if (ka != kb) return kb - ka;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    private static int arabicScore(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        if (n.contains("عرب") || n.matches(".*(^|[._\\-\\s\\[(])(ar|ara|arb|arabic|ar-sa)([._\\-\\s\\])]|$).*")) return 1;
        return 0;
    }

    private void renderRows() {
        if (listBox == null) return;
        listBox.removeAllViews();
        int videos = 0;
        int subs = 0;
        int unknown = 0;
        int dups = 0;
        long bytes = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if ("unknown".equals(r.problem)) {
                if (r.include) unknown++;
                continue;
            }
            if (!r.include) {
                if ("duplicate".equals(r.problem)) dups++;
                continue;
            }
            if (r.subtitle) subs++;
            else {
                videos++;
                bytes += r.size;
            }
        }
        if (summaryView != null) {
            if (rows.isEmpty()) summaryView.setText("لم تُختر ملفات بعد.");
            else summaryView.setText(videos + " فيديو • " + subs + " ترجمة • " + CgUi.sizeText(bytes)
                    + (unknown > 0 ? " • " + unknown + " غير معروف" : "") + (dups > 0 ? " • " + dups + " مكرر مُستبعد" : ""));
        }
        if (rows.isEmpty()) {
            listBox.addView(CgUi.empty(this, "لا توجد ملفات", "اضغط «اختيار ملفات الحلقات» لإضافة فيديوهات الموسم وترجماتها."));
            return;
        }
        if (unknown > 0) {
            listBox.addView(CxUi.notice(this, CxUi.WARN, unknown + " ملف لم يُعرف رقم حلقته. اضغط على الملف وحدّد الموسم والحلقة، أو استبعده."),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
        for (int i = 0; i < rows.size(); i++) listBox.addView(fileRow(rows.get(i)));
    }

    private View fileRow(final Row r) {
        LinearLayout box = CgUi.hbox(this);
        boolean bad = "unknown".equals(r.problem);
        boolean dup = "duplicate".equals(r.problem);
        int color = bad ? CgCfg.WARN : dup ? CgCfg.GRAY : r.include ? CgCfg.ACCENT : CgCfg.STROKE;
        box.setBackground(CgUi.bg(this, bad ? CgUi.alpha(CgCfg.WARN, 0x14) : r.include ? CgUi.alpha(CgCfg.ACCENT, 0x14) : CgCfg.CARD, 12, color));
        box.setPadding(CgUi.dp(this, 10), CgUi.dp(this, 9), CgUi.dp(this, 10), CgUi.dp(this, 9));
        box.setClickable(true);
        TextView code = CgUi.text(this, bad ? "؟؟؟" : CxNames.code(r.season, r.episode), 13.5f, bad ? CgCfg.WARN : CgCfg.ACCENT, true);
        box.addView(code, new LinearLayout.LayoutParams(CgUi.dp(this, 74), CgUi.WRAP));
        LinearLayout col = CgUi.vbox(this);
        TextView n = CgUi.text(this, r.name, 12.5f, CgCfg.TEXT, false);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        CgUi.ltr(n);
        col.addView(n);
        LinearLayout badges = CgUi.hbox(this);
        badges.addView(CgUi.badge(this, r.subtitle ? "ترجمة" : "فيديو", r.subtitle ? CgCfg.GRAY : CgCfg.GOOD));
        badges.addView(CgUi.muted(this, "  " + CgUi.sizeText(r.size)));
        if (r.guessed && !bad) badges.addView(CgUi.text(this, "  ⚠ تخمين من الاسم", 11, CgCfg.WARN, false));
        if (r.inLibrary && !bad) badges.addView(CgUi.text(this, "  موجودة — ستُحدَّث", 11, CgCfg.ACCENT, false));
        if (dup) badges.addView(CgUi.text(this, "  مكررة", 11, CgCfg.WARN, false));
        if (!r.include && !dup) badges.addView(CgUi.text(this, "  مستبعدة", 11, CgCfg.MUTED, false));
        col.addView(badges, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 3, 0, 0));
        box.addView(col, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rowMenu(r);
            }
        });
        box.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));
        return box;
    }

    private void rowMenu(final Row r) {
        CharSequence[] labels = {"تعديل الموسم والحلقة", r.include ? "استبعاد من الرفع" : "تضمين في الرفع", "إزالة من القائمة"};
        CgUi.choose(this, r.name, labels, new CgUi.IntCb() {
            @Override
            public void run(int index) {
                if (index == 0) {
                    editNumbers(r);
                } else if (index == 1) {
                    r.include = !r.include;
                    r.userIncluded = true;
                    markProblems();
                    renderRows();
                } else {
                    rows.remove(r);
                    markProblems();
                    renderRows();
                }
            }
        });
    }

    private void editNumbers(final Row r) {
        String initial = r.season > 0 && r.episode > 0 ? CxNames.code(r.season, r.episode) : "";
        CgUi.input(this, "الموسم والحلقة", "مثال: S01E05 أو 1x5 أو 5", initial, false, new CgUi.Callback<String>() {
            @Override
            public void run(String text) {
                int[] f = CxNames.parse(text);
                if (f == null) f = CxNames.parse("S" + text.replaceAll("\\s+", "E"));
                if (f == null) {
                    String t = text.trim();
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,3})\\D+(\\d{1,4})$").matcher(t);
                    if (m.find()) f = new int[]{parseInt(m.group(1)), parseInt(m.group(2))};
                    else if (t.matches("\\d{1,4}")) f = new int[]{0, parseInt(t)};
                }
                if (f == null || f[1] <= 0) {
                    snack("صيغة غير مفهومة. اكتب مثل S01E05.", CxUi.WARN);
                    return;
                }
                int ns = f[0] > 0 ? f[0] : (r.season > 0 ? r.season : defSeason);
                if (ns != r.season || f[1] != r.episode) {
                    r.state = "";   // a file that was already uploaded under other numbers must go up again
                    r.url = "";
                    r.sessionId = "";
                }
                r.season = ns;
                r.episode = f[1];
                r.guessed = false;
                r.seasonFromDefault = false;
                r.userIncluded = false;
                r.include = true;
                markProblems();
                renderRows();
            }
        });
    }

    // ------------------------------------------------------------------ step 2: upload

    private void beginUpload() {
        collectForm();
        if (!slug.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")) {
            folder.error("اسم المجلد: حروف إنجليزية وأرقام و . _ - فقط.");
            scrollTo(folder.view);
            return;
        }
        String lower = slug.toLowerCase(Locale.US);
        if (lower.equals("carousel") || lower.equals("backups") || lower.equals("undo") || lower.equals("admin")
                || lower.equals("_admin_private") || lower.equals("api") || lower.equals("movies") || lower.equals("update")) {
            folder.error("هذا الاسم محجوز لمسارات النظام. اختر اسماً آخر.");
            return;
        }
        int videos = 0;
        int unknown = 0;
        long bytes = 0;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (!r.include) continue;
            if ("unknown".equals(r.problem)) {
                unknown++;
                continue;
            }
            if (!r.subtitle) {
                videos++;
                bytes += r.size;
            }
        }
        if (unknown > 0) {
            snack("حدّد رقم الحلقة للملفات المعلّمة أو استبعدها أولاً.", CxUi.WARN);
            return;
        }
        boolean anyUsable = false;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.include && !"unknown".equals(r.problem)) anyUsable = true;
        }
        if (!anyUsable) {
            snack("اختر ملفاً واحداً على الأقل.", CxUi.WARN);
            return;
        }
        if (mgr.busy()) {
            CgUi.info(this, "رفع جارٍ", "هناك عملية رفع أخرى جارية الآن. انتظر انتهاءها أو ألغِها من «عمليات الرفع» ثم أعد المحاولة.");
            return;
        }
        final String msg = videos + " فيديو (" + CgUi.sizeText(bytes) + ") ستُرفع إلى المجلد «" + slug + "» بالأسماء SxxEyy بالترتيب."
                + (mgr.isMetered() ? "\nأنت تستخدم بيانات الجوال — الاستهلاك كبير." : "");
        CgUi.confirm(this, "بدء رفع الموسم", msg, "ابدأ", false, new Runnable() {
            @Override
            public void run() {
                startUpload();
            }
        });
    }

    private void startUpload() {
        started = true;
        uploading = true;
        if (!listening) {
            mgr.addListener(this);
            listening = true;
        }
        askNotificationPermission();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (!r.include || "unknown".equals(r.problem)) {
                r.state = "";
                continue;
            }
            if ("done".equals(r.state) || "reused".equals(r.state)) continue;   // already uploaded: not again
            r.state = "queued";
            r.error = "";
            r.fraction = 0f;
        }
        showStep(2);
        final ArrayList<Row> work = new ArrayList<Row>();
        for (int i = 0; i < rows.size(); i++) {
            if ("queued".equals(rows.get(i).state)) work.add(rows.get(i));
        }
        final String fslug = slug;
        final boolean fsmart = smart;
        final JSONObject h = header();
        final String title = h == null ? "" : CgData.s(h, "title");
        final String poster = h == null ? "" : CxOps2.posterOf(h);
        new Thread(new Runnable() {
            @Override
            public void run() {
                // videos first (queued in order), then the small subtitle files while they upload
                for (int i = 0; i < work.size(); i++) {
                    Row r = work.get(i);
                    if (!r.subtitle) prepareVideo(r, fslug, title, poster, fsmart);
                }
                for (int i = 0; i < work.size(); i++) {
                    Row r = work.get(i);
                    if (r.subtitle) uploadSubtitle(r, fslug);
                }
                ui().post(new Runnable() {
                    @Override
                    public void run() {
                        checkAllDone();
                        scheduleRefresh();
                    }
                });
            }
        }).start();
    }

    /** Analyses the file, checks R2, creates the upload session and puts it in the queue. */
    private void prepareVideo(final Row r, String fslug, String title, String poster, boolean fsmart) {
        try {
            Uri uri = Uri.parse(r.uri);
            CgMediaInfo info = new CgMediaInfo();
            info.uri = r.uri;
            info.fileName = r.name;
            info.size = r.size;
            info.container = "";
            CgSmartPlan plan = null;
            if (fsmart) {
                try {
                    CgMediaInfo full = CgMediaAnalyzer.analyze(this, uri);
                    if (full.size > 0) {
                        CgSmartPlan[] plans = CgSmartPlan.plans(full, CgUploadManager.tempDir(this));
                        for (int i = 0; i < plans.length; i++) {
                            if (plans[i].recommended && plans[i].available()) plan = plans[i];
                        }
                        info = full;
                    }
                } catch (Throwable t) {
                    Log.w(CgMediaAnalyzer.TAG, "smart plan skipped for " + r.name + ": " + t.getMessage());
                }
            }
            if (plan == null) {
                plan = new CgSmartPlan();
                plan.mode = CgSmartPlan.MODE_ORIGINAL;
                plan.estimatedBytes = info.size;
                plan.sourceBytes = info.size;
            }
            // this file IS the episode the admin chose: a different file under the same name is replaced
            String onExists = "overwrite";
            try {
                JSONObject ex = CgMediaApi.exists("episode", fslug, r.name, r.season, r.episode, false);
                if (ex.optBoolean("exists", false)) {
                    JSONObject old = ex.optJSONObject("existing");
                    long oldSize = old == null ? 0 : old.optLong("size");
                    String oldUrl = old == null ? "" : old.optString("url", "");
                    if (plan.mode == CgSmartPlan.MODE_ORIGINAL && oldSize == r.size && oldUrl.length() > 0) {
                        // the very same file is already on R2: nothing to upload
                        r.url = oldUrl;
                        r.state = "reused";
                        r.fraction = 1f;
                        post();
                        return;
                    }
                }
            } catch (CxApi.Failure f) {
                Log.w(CgMediaAnalyzer.TAG, "exists check failed: " + f.getMessage());
                if (f.is("ROUTE_MISSING") || f.is("UNAUTHORIZED")) {
                    r.state = "failed";
                    r.error = f.getMessage();
                    post();
                    return;
                }
            }
            String t = title + " — " + CxNames.code(r.season, r.episode);
            CgUploadSession s = mgr.create(info, plan, "episode", t, poster, fslug, r.season, r.episode, onExists);
            r.sessionId = s.id;
            synchronized (bySession) {
                bySession.put(s.id, r);
            }
            String err = mgr.enqueue(s.id);
            if (err.length() > 0) {
                r.state = "failed";
                r.error = err;
            }
            post();
        } catch (Throwable t) {
            Log.e(CgMediaAnalyzer.TAG, "prepare failed", t);
            r.state = "failed";
            r.error = t.getMessage() == null ? "تعذر تجهيز الملف." : t.getMessage();
            post();
        }
    }

    private void uploadSubtitle(Row r, String fslug) {
        try {
            r.state = "running";
            post();
            byte[] data = CgSubtitleUpload.readUtf8(this, Uri.parse(r.uri));
            JSONObject res = CgMediaApi.putSubtitle("episode", fslug, r.name, r.season, r.episode, "overwrite", data);
            String url = res.optString("publicUrl", "");
            if (url.length() == 0) url = res.optString("url", "");
            if (url.length() == 0) throw new CxApi.Failure("لم يصل رابط الترجمة من الخادم.", "BAD_RESPONSE", null, 0);
            r.url = url;
            r.state = "done";
            r.fraction = 1f;
        } catch (CxApi.Failure f) {
            r.state = "failed";
            r.error = f.getMessage();
        } catch (Throwable t) {
            r.state = "failed";
            r.error = t.getMessage() == null ? "تعذر رفع الترجمة." : t.getMessage();
        }
        post();
    }

    private void post() {
        ui().post(new Runnable() {
            @Override
            public void run() {
                scheduleRefresh();
            }
        });
    }

    private void askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            CgUploadManager.askBatteryExemption(this);
            return;
        }
        try {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7732);
                return;
            }
            CgUploadManager.askBatteryExemption(this);
        } catch (Throwable t) {
            Log.w(CgMediaAnalyzer.TAG, "notification permission request failed: " + t.getMessage());
        }
    }

    @Override
    public void onSession(CgUploadSession s) {
        if (!alive()) return;
        Row r;
        synchronized (bySession) {
            r = bySession.get(s.id);
        }
        if (r == null) return;
        if (s.needsDecision) {
            // never stall the batch: same file already there -> use it, otherwise replace it
            String how = s.existingSize > 0 && s.existingSize == s.srcSize && s.mode == 0 ? "use" : "overwrite";
            String err = mgr.decide(s.id, how);
            if (err.length() > 0) {
                r.state = "failed";
                r.error = err;
            }
            scheduleRefresh();
            return;
        }
        if (CgUploadSession.COMPLETED.equals(s.state)) {
            r.state = "done";
            r.url = s.publicUrl;
            r.fraction = 1f;
            r.error = s.warning == null ? "" : s.warning;
        } else if (s.queued) {
            r.state = "queued";
        } else if (s.isActive()) {
            r.state = "running";
            r.fraction = s.stageFraction;
            r.error = "";
        } else if (CgUploadSession.PAUSED.equals(s.state) && s.waitingNetwork && s.autoResume) {
            // connection lost: the manager continues by itself when it is back, the batch keeps waiting
            r.state = "running";
            r.fraction = s.uploadSize > 0 ? (float) s.partsBytes() / (float) s.uploadSize : 0f;
            r.error = "بانتظار عودة الإنترنت — سيُستكمل تلقائياً.";
        } else if (CgUploadSession.FAILED.equals(s.state) || CgUploadSession.PAUSED.equals(s.state) || CgUploadSession.CANCELLED.equals(s.state)) {
            r.state = "failed";
            r.error = s.message == null || s.message.length() == 0 ? CgUploadSession.stateLabel(s.state) : s.message;
            r.fraction = s.uploadSize > 0 ? (float) s.partsBytes() / (float) s.uploadSize : 0f;
        }
        scheduleRefresh();
        if (!s.queued && !s.isActive() && !s.waitingNetwork) checkAllDone();
    }

    private void scheduleRefresh() {
        if (refreshQueued || step != 2) return;
        refreshQueued = true;
        ui().postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshQueued = false;
                if (alive() && step == 2) renderUpload();
            }
        }, 400);
    }

    private void checkAllDone() {
        boolean pending = false;
        for (int i = 0; i < rows.size(); i++) {
            String st = rows.get(i).state;
            if ("queued".equals(st) || "running".equals(st)) pending = true;
        }
        boolean was = uploading;
        uploading = pending;
        if (was && !pending && step == 2) {
            int ok = 0;
            int bad = 0;
            for (int i = 0; i < rows.size(); i++) {
                String st = rows.get(i).state;
                if ("done".equals(st) || "reused".equals(st)) ok++;
                else if ("failed".equals(st)) bad++;
            }
            snack(bad == 0 ? "اكتمل رفع كل الملفات (" + ok + ")." : ok + " ملف اكتمل و " + bad + " فشل.", bad == 0 ? CxUi.GOOD : CxUi.WARN);
            showStep(2);
        }
    }

    private void stepUpload() {
        page.addView(CxUi.notice(this, CxUi.INFO, "الرفع يعمل في الخلفية ويستمر حتى لو خرجت من الشاشة، ويُستكمل تلقائياً عند عودة الإنترنت. تُرفع الملفات واحداً بعد الآخر."));
        summaryView = CgUi.text(this, "", 13, CgCfg.ACCENT, true);
        page.addView(summaryView, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 10, 4, 6));
        listBox = CgUi.vbox(this);
        page.addView(listBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        renderUpload();
        if (uploading) {
            bottomButton("إلغاء الكل", 2, 1f, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CgUi.confirm(CxSeasonUploadActivity.this, "إلغاء الرفع", "سيتم إيقاف كل العمليات الجارية والمنتظرة وحذف الأجزاء المرفوعة جزئياً. الملفات على هاتفك لا تتأثر.", "إلغاء الكل", true, new Runnable() {
                        @Override
                        public void run() {
                            cancelAll();
                        }
                    });
                }
            });
            return;
        }
        int bad = 0;
        int ok = 0;
        for (int i = 0; i < rows.size(); i++) {
            String st = rows.get(i).state;
            if ("failed".equals(st)) bad++;
            if ("done".equals(st) || "reused".equals(st)) ok++;
        }
        if (bad > 0) {
            bottomButton("إعادة محاولة الفاشلة", 1, 1f, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    retryFailed();
                }
            });
        }
        bottomButton("→ تعديل الملفات", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                started = false;
                showStep(1);
            }
        });
        if (ok > 0) {
            bottomButton(bad > 0 ? "نشر الناجحة ←" : "متابعة للنشر ←", 0, 1.5f, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showStep(3);
                }
            });
        }
    }

    private void cancelAll() {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.sessionId.length() > 0 && ("queued".equals(r.state) || "running".equals(r.state))) mgr.cancel(r.sessionId);
            if ("queued".equals(r.state) || "running".equals(r.state)) {
                r.state = "failed";
                r.error = "أُلغي.";
            }
        }
    }

    private void retryFailed() {
        uploading = true;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (!"failed".equals(r.state)) continue;
            r.error = "";
            if (r.sessionId.length() > 0) {
                CgUploadSession s = mgr.snapshot(r.sessionId);
                if (s != null && !s.isFinished()) {
                    r.state = "queued";
                    String err = mgr.enqueue(r.sessionId);
                    if (err.length() > 0) {
                        r.state = "failed";
                        r.error = err;
                    }
                    continue;
                }
            }
            // no usable session (cancelled / never created): prepare it again
            r.state = "queued";
            final Row fr = r;
            final String fslug = slug;
            final JSONObject h = header();
            final String title = h == null ? "" : CgData.s(h, "title");
            final String poster = h == null ? "" : CxOps2.posterOf(h);
            final boolean fsmart = smart;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    if (fr.subtitle) uploadSubtitle(fr, fslug);
                    else prepareVideo(fr, fslug, title, poster, fsmart);
                }
            }).start();
        }
        showStep(2);
    }

    private void renderUpload() {
        if (listBox == null) return;
        listBox.removeAllViews();
        int total = 0;
        int done = 0;
        int failed = 0;
        float sum = 0f;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.state.length() == 0) continue;
            total++;
            if ("done".equals(r.state) || "reused".equals(r.state)) {
                done++;
                sum += 1f;
            } else {
                if ("failed".equals(r.state)) failed++;
                sum += r.fraction;
            }
        }
        if (summaryView != null) {
            int pct = total == 0 ? 0 : Math.round(100f * sum / total);
            summaryView.setText("المكتمل " + done + " من " + total + " (" + pct + "%)" + (failed > 0 ? " • فشل " + failed : ""));
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.state.length() == 0) continue;
            listBox.addView(uploadRow(r));
        }
    }

    private View uploadRow(Row r) {
        LinearLayout box = CgUi.hbox(this);
        int color = "failed".equals(r.state) ? CgCfg.BAD : ("done".equals(r.state) || "reused".equals(r.state)) ? CgCfg.GOOD : "running".equals(r.state) ? CgCfg.ACCENT : CgCfg.STROKE;
        box.setBackground(CgUi.bg(this, CgCfg.CARD, 12, color));
        box.setPadding(CgUi.dp(this, 10), CgUi.dp(this, 9), CgUi.dp(this, 10), CgUi.dp(this, 9));
        box.addView(CgUi.text(this, CxNames.code(r.season, r.episode), 13.5f, CgCfg.ACCENT, true), new LinearLayout.LayoutParams(CgUi.dp(this, 74), CgUi.WRAP));
        LinearLayout col = CgUi.vbox(this);
        TextView n = CgUi.text(this, r.name, 12.5f, CgCfg.TEXT, false);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        CgUi.ltr(n);
        col.addView(n);
        String label;
        if ("queued".equals(r.state)) label = "في الانتظار";
        else if ("running".equals(r.state)) label = (r.subtitle ? "جاري رفع الترجمة" : "جاري الرفع") + (r.subtitle ? "" : " " + Math.round(r.fraction * 100f) + "%");
        else if ("done".equals(r.state)) label = r.subtitle ? "تم رفع الترجمة ✔" : "تم الرفع والتحقق ✔";
        else if ("reused".equals(r.state)) label = "موجود في R2 بنفس الحجم ✔ (لم يُرفع من جديد)";
        else label = "فشل";
        col.addView(CgUi.text(this, label, 12, color == CgCfg.STROKE ? CgCfg.MUTED : color, false));
        if ("running".equals(r.state) && !r.subtitle) {
            LinearLayout bar = CgUi.hbox(this);
            bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            bar.setBackground(CgUi.bg(this, CgCfg.CARD2, 4, 0));
            View fill = new View(this);
            fill.setBackground(CgUi.bg(this, CgCfg.ACCENT, 4, 0));
            View rest = new View(this);
            float f = Math.max(0.001f, Math.min(1f, r.fraction));
            bar.addView(fill, new LinearLayout.LayoutParams(0, CgUi.dp(this, 6), f));
            bar.addView(rest, new LinearLayout.LayoutParams(0, CgUi.dp(this, 6), Math.max(0.001f, 1f - f)));
            col.addView(bar, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 5, 0, 0));
        }
        if (r.error.length() > 0) {
            TextView e = CgUi.text(this, r.error, 11.5f, "failed".equals(r.state) ? CgCfg.BAD : CgCfg.WARN, false);
            col.addView(e, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 3, 0, 0));
        }
        box.addView(col, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        box.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));
        return box;
    }

    // ------------------------------------------------------------------ step 3: publish

    /** Season -> (episode -> {video, subtitle}) of everything that was uploaded successfully. */
    private HashMap<Integer, HashMap<Integer, String[]>> uploaded() {
        HashMap<Integer, HashMap<Integer, String[]>> out = new HashMap<Integer, HashMap<Integer, String[]>>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (!("done".equals(r.state) || "reused".equals(r.state)) || r.url.length() == 0) continue;
            HashMap<Integer, String[]> eps = out.get(Integer.valueOf(r.season));
            if (eps == null) {
                eps = new HashMap<Integer, String[]>();
                out.put(Integer.valueOf(r.season), eps);
            }
            String[] pair = eps.get(Integer.valueOf(r.episode));
            if (pair == null) {
                pair = new String[]{"", ""};
                eps.put(Integer.valueOf(r.episode), pair);
            }
            if (r.subtitle) pair[1] = r.url;
            else pair[0] = r.url;
        }
        return out;
    }

    private void stepPublish() {
        JSONObject h = header();
        HashMap<Integer, HashMap<Integer, String[]>> up = uploaded();
        int videos = 0;
        int subs = 0;
        ArrayList<Integer> seasons = new ArrayList<Integer>(up.keySet());
        Collections.sort(seasons);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seasons.size(); i++) {
            HashMap<Integer, String[]> eps = up.get(seasons.get(i));
            int sv = 0;
            for (String[] p : eps.values()) {
                if (p[0].length() > 0) {
                    sv++;
                    videos++;
                }
                if (p[1].length() > 0) subs++;
            }
            if (sb.length() > 0) sb.append(" • ");
            sb.append("الموسم ").append(seasons.get(i)).append(": ").append(sv).append(" حلقة");
        }
        LinearLayout c = CgUi.card(this);
        c.addView(CgUi.text(this, "جاهز لنشر الحلقات المرفوعة", 16, CgCfg.TEXT, true));
        c.addView(CgUi.muted(this, (h == null ? "" : CgData.s(h, "title")) + " — " + sb));
        c.addView(CgUi.space(this, 6));
        c.addView(CgUi.muted(this, videos + " فيديو و " + subs + " ترجمة. تُجلب أسماء الحلقات وصورها ووصفها من TMDB، وتُكتب كل الحلقات في عملية واحدة آمنة مع نسخة احتياطية قبلها، ويمكنك التراجع بعد النشر."));
        c.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        page.addView(c);
        bottomButton("→ السابق", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStep(2);
            }
        });
        bottomButton("نشر الحلقات", 0, 1.6f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                publish();
            }
        });
    }

    private boolean hasSeasonPoster(int season) {
        JSONObject h = header();
        if (h == null) return false;
        ArrayList<JSONObject> eps = CgData.cur().episodesOf(h);
        for (int i = 0; i < eps.size(); i++) {
            if (CgData.n(eps.get(i), "season") == season && CgData.s(eps.get(i), "season_poster").length() > 0) return true;
        }
        return false;
    }

    private void publish() {
        final HashMap<Integer, HashMap<Integer, String[]>> up = uploaded();
        if (up.isEmpty()) {
            snack("لا توجد حلقات مرفوعة للنشر.", CxUi.WARN);
            return;
        }
        final String fslug = slug;
        final JSONObject h = header();
        final ArrayList<Integer> seasons = new ArrayList<Integer>(up.keySet());
        Collections.sort(seasons);
        final HashMap<Integer, Boolean> needPoster = new HashMap<Integer, Boolean>();
        for (int i = 0; i < seasons.size(); i++) needPoster.put(seasons.get(i), Boolean.valueOf(!hasSeasonPoster(seasons.get(i).intValue())));
        final CxStages sg = CxStages.show(this, "نشر الموسم", new String[]{"التعرف على الحلقات", "جلب بيانات TMDB", "الحفظ في قاعدة البيانات", "التحقق", "اكتمل"});
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                int added = 0;
                int updated = 0;
                String undo = "";
                int lastSeason = 0;
                for (int si = 0; si < seasons.size(); si++) {
                    int season = seasons.get(si).intValue();
                    lastSeason = season;
                    HashMap<Integer, String[]> eps = up.get(Integer.valueOf(season));
                    sg.stage(0, "الموسم " + season + " — " + eps.size() + " حلقة");
                    // titles / pictures / descriptions of the episodes (the files are in R2 now, so the preview sees them too)
                    sg.stage(1, "الموسم " + season);
                    HashMap<Integer, JSONObject> meta = new HashMap<Integer, JSONObject>();
                    String seasonPoster = "";
                    try {
                        JSONObject pb = new JSONObject();
                        pb.put("series_id", seriesKey);
                        pb.put("season", season);
                        pb.put("slug", fslug);
                        JSONObject pv = CxApi.post("/admin/season/preview", pb);
                        JSONArray prow = pv.optJSONArray("rows");
                        for (int i = 0; prow != null && i < prow.length(); i++) {
                            JSONObject o = prow.optJSONObject(i);
                            if (o != null) meta.put(Integer.valueOf(o.optInt("ep")), o);
                        }
                        if (needPoster.get(Integer.valueOf(season)).booleanValue()) seasonPoster = CxJ.s(pv, "season_poster");
                    } catch (CxApi.Failure f) {
                        Log.w(CgMediaAnalyzer.TAG, "season preview failed (generic titles will be used): " + f.getMessage());
                    }
                    JSONArray list = new JSONArray();
                    ArrayList<Integer> nums = new ArrayList<Integer>(eps.keySet());
                    Collections.sort(nums);
                    for (int i = 0; i < nums.size(); i++) {
                        int ep = nums.get(i).intValue();
                        String[] pair = eps.get(Integer.valueOf(ep));
                        JSONObject m = meta.get(Integer.valueOf(ep));
                        JSONObject o = new JSONObject();
                        CxJ.put(o, "ep", Integer.valueOf(ep));
                        CxJ.put(o, "title", m == null ? "" : CxJ.s(m, "title"));
                        CxJ.put(o, "video", pair[0]);
                        CxJ.put(o, "subtitle", pair[1]);
                        CxJ.put(o, "thumb", m == null ? "" : CxJ.s(m, "thumb"));
                        CxJ.put(o, "overview", m == null ? "" : CxJ.s(m, "overview"));
                        CxJ.put(o, "duration", m == null ? "" : CxJ.s(m, "duration"));
                        CxJ.put(o, "year", m == null ? "" : CxJ.s(m, "year"));
                        list.put(o);
                    }
                    sg.stage(2, "الموسم " + season + " — نسخة احتياطية ثم كتابة واحدة");
                    JSONObject b = new JSONObject();
                    b.put("series_id", seriesKey);
                    b.put("season", season);
                    b.put("episodes", list);
                    b.put("update_existing", true);
                    if (seasonPoster.length() > 0) b.put("season_poster", seasonPoster);
                    JSONObject r = CxApi.post("/admin/season/commit", b);
                    added += r.optInt("added");
                    updated += r.optInt("updated");
                    if (r.optString("undo_id", "").length() > 0) undo = r.optString("undo_id");
                }
                sg.stage(3, "");
                sg.stage(4, "");
                JSONObject out = new JSONObject();
                out.put("added", added);
                out.put("updated", updated);
                out.put("undo_id", undo);
                out.put("season", lastSeason);
                return out;
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                sg.dismiss();
                CgData.load(CxSeasonUploadActivity.this, new CgHttp.Done<CgData>() {
                    @Override
                    public void ok(CgData d) { }

                    @Override
                    public void fail(String message) { }
                });
                String note = r.optInt("added") + " جديدة، " + r.optInt("updated") + " محدّثة";
                CxNav.success(CxSeasonUploadActivity.this, "season", h == null ? "" : CgData.s(h, "id"), h == null ? "" : CgData.s(h, "title"),
                        h == null ? "" : CxOps2.posterOf(h), note, r.optString("undo_id"), seriesKey, r.optInt("season"), 0);
                finish();
            }

            @Override
            public void fail(CxApi.Failure f) {
                sg.fail(f.getMessage(), new Runnable() {
                    @Override
                    public void run() {
                        publish();
                    }
                });
            }
        });
    }
}
