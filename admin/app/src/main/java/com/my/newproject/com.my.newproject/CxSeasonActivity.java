package com.my.newproject;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Adds a whole season to an EXISTING series: choose series, season and R2 folder, analyze the file names,
 * preview every episode (video / subtitle / status), see missing episodes as warnings and publish in one
 * safe write. Never creates a new series.
 */
public class CxSeasonActivity extends CxBase {

    private static final String[] STEPS = {"المسلسل", "الموسم", "المعاينة", "النشر"};

    private JSONObject st = new JSONObject();
    private JSONObject preview;
    private final ArrayList<Boolean> selected = new ArrayList<Boolean>();
    private final ArrayList<String> checkState = new ArrayList<String>();
    private int step;
    private CxDraft.Auto auto;
    private CxUi.Field season;
    private CxUi.Field folder;
    private CxUi.Field posterUrl;
    private String posterText = "";
    private boolean useTmdbPoster;
    private TextView countView;

    @Override
    protected String screenTitle() {
        return "إضافة موسم كامل";
    }

    @Override
    protected void onBuild() {
        initCx(STEPS);
        steps.onStep(new CgUi.IntCb() {
            @Override
            public void run(int index) {
                if (index == 3) return;
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

    private JSONObject header() {
        CgData d = CgData.cur();
        String key = CxJ.s(st, "series_key");
        if (d == null || key.length() == 0) return null;
        JSONObject h = d.byId(key);
        if (h != null && CgData.kind(h) == CgData.K_SERIES) return h;
        ArrayList<JSONObject> all = d.seriesHeaders();
        for (int i = 0; i < all.size(); i++) {
            if (CgData.seriesKey(all.get(i)).equals(key)) return all.get(i);
        }
        return null;
    }

    private void start() {
        String draftId = getIntent().getStringExtra("draft_id");
        int startStep = 0;
        if (draftId != null && draftId.length() > 0) {
            JSONObject dr = CxDraft.load(this, draftId);
            if (dr != null) {
                st = dr.optJSONObject("data") == null ? new JSONObject() : dr.optJSONObject("data");
                startStep = Math.min(1, dr.optInt("step", 0));
            }
        } else {
            String sid = getIntent().getStringExtra("series_id");
            if (sid != null && sid.length() > 0) {
                CxJ.put(st, "series_key", sid);
                if (header() != null) {
                    int s = getIntent().getIntExtra("season", 0);
                    if (s > 0) CxJ.put(st, "season", String.valueOf(s));
                    startStep = 1;
                }
            }
        }
        auto = new CxDraft.Auto(this, "season", draftId, new CxDraft.Source() {
            @Override
            public boolean worthSaving() {
                return CxJ.s(st, "series_key").length() > 0 && step <= 1;
            }

            @Override
            public JSONObject collect() {
                collect2();
                return st;
            }

            @Override
            public String title() {
                JSONObject h = header();
                return (h == null ? "موسم" : CgData.s(h, "title")) + " — الموسم " + CxJ.s(st, "season");
            }

            @Override
            public int step() {
                return step;
            }
        });
        showStep(startStep);
        // opened from the "links" button of a season card: build the VidBolt links straight away
        if (startStep == 1 && getIntent().getBooleanExtra("vidbolt", false) && CxJ.n(st, "season") > 0) {
            vidboltSeason();
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
        if (step > 0) {
            showStep(step - 1);
            return;
        }
        if (auto != null) auto.flush();
        super.onBackPressed();
    }

    private void collect2() {
        if (step == 1 && season != null) {
            CxJ.put(st, "season", season.text());
            CxJ.put(st, "slug", folder.text());
        }
    }

    private void showStep(int i) {
        collect2();
        step = i;
        newPage(i);
        if (i == 0) stepSeries();
        else if (i == 1) stepSeason();
        else if (i == 2) stepPreview();
        else stepPublish();
    }

    // ---------------------------------------------------------------- step 0

    private String summary(CgData d, JSONObject h) {
        ArrayList<JSONObject> eps = d.episodesOf(h);
        return d.seasonsOf(h).size() + " مواسم • " + eps.size() + " حلقة";
    }

    private void stepSeries() {
        final CgData d = CgData.cur();
        final JSONObject latest = CxDraft.latest(this, "season");
        if (latest != null && !latest.optString("id").equals(auto.id())) {
            page.addView(draftBanner(latest, new Runnable() {
                @Override
                public void run() {
                    CxNav.resumeDraft(CxSeasonActivity.this, latest);
                    finish();
                }
            }, new Runnable() {
                @Override
                public void run() {
                    CxDraft.delete(CxSeasonActivity.this, latest.optString("id"));
                    showStep(0);
                }
            }));
        }
        page.addView(CxUi.notice(this, CxUi.INFO, "الموسم يُضاف إلى مسلسل موجود ويرتبط به بـ series_id — لا يُنشأ مسلسل جديد لكل موسم."));
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
                CxNav.addSeries(CxSeasonActivity.this);
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
        CxUi.pick(this, "اختر المسلسل", opts, CxJ.s(st, "series_key"), null, new CgUi.Callback<CxUi.Option>() {
            @Override
            public void run(CxUi.Option o) {
                CxJ.put(st, "series_key", o.id);
                st.remove("season");
                st.remove("slug");
                preview = null;
                touch();
                showStep(0);
            }
        }, null);
    }

    // ---------------------------------------------------------------- step 1

    private void stepSeason() {
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
        page.addView(head, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        CxUi.Section sec = new CxUi.Section(this, "الموسم والمجلد", "يُفحص مجلد R2 ويُحلَّل أسماء الفيديوهات والترجمات", false, true);
        season = new CxUi.Field(this, "رقم الموسم", "1", CxUi.T_NUMBER, true);
        ArrayList<Integer> seasons = d.seasonsOf(h);
        int maxSeason = 0;
        for (int i = 0; i < seasons.size(); i++) maxSeason = Math.max(maxSeason, seasons.get(i).intValue());
        final int newSeason = maxSeason + 1;
        season.set(CxJ.s(st, "season").length() > 0 ? CxJ.s(st, "season") : String.valueOf(newSeason));
        season.onChange(new Runnable() {
            @Override
            public void run() {
                preview = null;
                touch();
            }
        });
        sec.add(season.view);
        CxUi.Flow chips = new CxUi.Flow(this);
        chips.addView(CxUi.chip(this, "موسم جديد " + newSeason, false, new Runnable() {
            @Override
            public void run() {
                season.set(String.valueOf(newSeason));
                preview = null;
            }
        }));
        for (int i = 0; i < seasons.size() && i < 10; i++) {
            final int sn = seasons.get(i).intValue();
            chips.addView(CxUi.chip(this, "إكمال الموسم " + sn, false, new Runnable() {
                @Override
                public void run() {
                    season.set(String.valueOf(sn));
                    preview = null;
                }
            }));
        }
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP);
        fl.bottomMargin = CgUi.dp(this, 12);
        chips.setLayoutParams(fl);
        sec.add(chips);
        folder = new CxUi.Field(this, "مجلد R2 (Slug المسلسل)", "my-series", CxUi.T_LTR, true);
        String slug = CxJ.s(st, "slug").length() > 0 ? CxJ.s(st, "slug") : d.slugOf(h);
        folder.set(slug);
        folder.hint(slug.length() == 0 ? "لا يوجد Slug محفوظ لهذا المسلسل — أدخل اسم المجلد في R2." : "المجلد الذي رُفعت إليه ملفات المسلسل.");
        folder.onChange(new Runnable() {
            @Override
            public void run() {
                preview = null;
                touch();
            }
        });
        sec.add(folder.view);
        page.addView(sec);
        page.addView(CxUi.notice(this, CxUi.INFO, "إن كانت الفيديوهات في R2 يُحلَّل المجلد هنا: S01E01 / s01e01 / 1x01 / episode-01 / الحلقة 1، والترجمات .srt تُربط فقط عند التطابق الواضح. وإن كانت على هاتفك فارفعها كلها دفعة واحدة من الزر التالي."));
        TextView phone = CgUi.button(this, "⬆  رفع ملفات الموسم من الهاتف (الكل دفعة واحدة)", 1);
        phone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collect2();
                CxNav.seasonUpload(CxSeasonActivity.this, CxJ.s(st, "series_key"), CxJ.n(st, "season"));
                finish();
            }
        });
        page.addView(phone, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 6));
        TextView vb = CgUi.button(this, "🎬  رابط VidBolt لكل حلقات الموسم (بدون ملفات)", 1);
        vb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                vidboltSeason();
            }
        });
        page.addView(vb, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));
        bottomButton("→ السابق", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStep(0);
            }
        });
        bottomButton("تحليل المجلد ←", 0, 1.6f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                analyze();
            }
        });
    }

    private void analyze() {
        collect2();
        int s = CxJ.n(st, "season");
        String slug = CxJ.s(st, "slug");
        if (s <= 0) {
            season.error("رقم الموسم مطلوب (1 أو أكثر).");
            return;
        }
        if (slug.length() == 0) {
            if (seriesTmdb().length() > 0) {
                // no R2 folder: this series lives on VidBolt, build the season from TMDB
                vidboltSeason();
                return;
            }
            folder.error("اسم مجلد R2 مطلوب.");
            return;
        }
        final String seriesKey = CxJ.s(st, "series_key");
        final int sn = s;
        final String fslug = slug;
        task("جارٍ تحليل مجلد R2 وجلب بيانات الموسم...", new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("series_id", seriesKey);
                b.put("season", sn);
                b.put("slug", fslug);
                if (seriesTmdb().length() > 0) b.put("tmdb", seriesTmdb());
                return CxApi.post("/admin/season/preview", b);
            }
        }, new Ok<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                JSONArray rows = r.optJSONArray("rows");
                boolean noFiles = !r.optBoolean("folder_exists", true) || rows == null || rows.length() == 0;
                if (noFiles && seriesTmdb().length() > 0) {
                    // no files in R2: the whole season comes from VidBolt instead
                    vidboltSeason();
                    return;
                }
                if (noFiles) {
                    folder.warn("لم أجد ملفات في هذا المجلد. تأكد من الاسم.");
                    snack("لا توجد ملفات مطابقة في المجلد.", CxUi.WARN);
                    return;
                }
                fillVidbolt(r);
                usePreview(r);
            }
        }, new Runnable() {
            @Override
            public void run() {
                analyze();
            }
        });
    }

    private String seriesTmdb() {
        JSONObject h = header();
        return CgVidbolt.tmdbOf(h);
    }

    /** Selects what is publishable and opens the preview step. */
    private void usePreview(JSONObject r) {
        preview = r;
        selected.clear();
        checkState.clear();
        JSONArray rows = r.optJSONArray("rows");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            String state = CxJ.s(row, "state");
            boolean on = "new".equals(state) || ("exists".equals(state) && row.optBoolean("changed", false));
            selected.add(Boolean.valueOf(on));
            checkState.add("");
        }
        useTmdbPoster = CxJ.s(r, "season_poster").length() > 0 && !hasSeasonPoster();
        showStep(2);
    }

    /**
     * VidBolt: every NEW or MISSING episode that has no file of its own gets the player link
     * https://vidbolt.pro/tv/{tmdb}/{season}/{episode}; a missing episode becomes a new, selectable one.
     * Episodes that already exist and have no change are left alone. Returns how many were filled.
     */
    private int fillVidbolt(JSONObject r) {
        String tmdb = seriesTmdb();
        int sn = CxJ.n(st, "season");
        JSONArray rows = r.optJSONArray("rows");
        if (tmdb.length() == 0 || sn < 1 || rows == null) return 0;
        int filled = 0;
        int fromMissing = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String state = CxJ.s(row, "state");
            boolean missing = "missing".equals(state);
            if (!missing && !"new".equals(state)) continue;
            if (CxJ.s(row, "video").length() > 0) continue;
            String url = CgVidbolt.tv(tmdb, sn, row.optInt("ep"));
            if (url.length() == 0) continue;
            CxJ.put(row, "video", url);
            if (missing) {
                CxJ.put(row, "state", "new");
                fromMissing++;
            }
            filled++;
        }
        if (fromMissing > 0) {
            JSONObject counts = r.optJSONObject("counts");
            if (counts == null) {
                counts = new JSONObject();
                CxJ.put(r, "counts", counts);
            }
            CxJ.put(counts, "new", counts.optInt("new") + fromMissing);
            CxJ.put(counts, "missing", Math.max(0, counts.optInt("missing") - fromMissing));
        }
        return filled;
    }

    /**
     * Builds the whole season from VidBolt with no R2 folder: episode titles / pictures come from the season
     * preview when the server can give them, otherwise the episode count of the season comes from TMDB.
     */
    private void vidboltSeason() {
        collect2();
        final String tmdb = seriesTmdb();
        final int sn = CxJ.n(st, "season");
        if (sn <= 0) {
            season.error("رقم الموسم مطلوب (1 أو أكثر).");
            return;
        }
        if (tmdb.length() == 0) {
            snack("المسلسل لا يملك TMDB ID. افتح بيانات المسلسل وأضفه أولاً.", CxUi.WARN);
            return;
        }
        final String seriesKey = CxJ.s(st, "series_key");
        final String slug = CxJ.s(st, "slug");
        task("جارٍ إنشاء روابط VidBolt للموسم " + sn + "...", new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject pv = null;
                try {
                    JSONObject b = new JSONObject();
                    b.put("series_id", seriesKey);
                    b.put("season", sn);
                    b.put("slug", slug);
                    b.put("vidbolt", true);
                    b.put("tmdb", tmdb);
                    pv = CxApi.post("/admin/season/preview", b);
                } catch (CxApi.Failure f) {
                    // an old Worker (route without the VidBolt mode) or a hiccup: fall back to the TMDB season count below
                    if (f.http == 401 || f.is("UNAUTHORIZED")) throw f;
                    pv = null;
                }
                JSONArray rows = pv == null ? null : pv.optJSONArray("rows");
                if (pv != null && rows != null && rows.length() > 0) return pv;
                // generic rows from the TMDB episode count of this season
                JSONObject b = new JSONObject();
                b.put("type", "series");
                b.put("tmdb_id", tmdb);
                JSONObject d = CxApi.post("/admin/tmdb/details", b);
                int count = 0;
                String poster = "";
                JSONArray seasons = d.optJSONArray("seasons");
                for (int i = 0; seasons != null && i < seasons.length(); i++) {
                    JSONObject so = seasons.optJSONObject(i);
                    if (so != null && so.optInt("season") == sn) {
                        count = so.optInt("episode_count");
                        poster = so.optString("poster", "");
                    }
                }
                if (count <= 0) throw new CxApi.Failure("لم أجد حلقات لهذا الموسم في TMDB.", "NO_EPISODES", null, 0);
                JSONArray out = new JSONArray();
                for (int e = 1; e <= count; e++) {
                    JSONObject o = new JSONObject();
                    o.put("ep", e);
                    o.put("title", "الحلقة " + e);
                    o.put("state", "new");
                    out.put(o);
                }
                JSONObject res = new JSONObject();
                res.put("rows", out);
                res.put("season_poster", poster);
                res.put("folder_exists", true);
                JSONObject counts = new JSONObject();
                counts.put("new", count);
                res.put("counts", counts);
                return res;
            }
        }, new Ok<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                int n = fillVidbolt(r);
                JSONArray rr = r.optJSONArray("rows");
                for (int i = 0; n == 0 && rr != null && i < rr.length(); i++) {
                    JSONObject o = rr.optJSONObject(i);
                    if (o != null && "new".equals(CxJ.s(o, "state")) && CgVidbolt.is(CxJ.s(o, "video"))) n++;   // the server already built them
                }
                if (n == 0) {
                    snack("كل حلقات هذا الموسم موجودة بروابطها، لا جديد لإضافته.", CxUi.INFO);
                }
                usePreview(r);
                if (n > 0) snack("تم إنشاء " + n + " رابط VidBolt للموسم.", CxUi.GOOD);
            }
        }, new Runnable() {
            @Override
            public void run() {
                vidboltSeason();
            }
        });
    }

    private boolean hasSeasonPoster() {
        JSONObject h = header();
        if (h == null) return false;
        ArrayList<JSONObject> eps = CgData.cur().episodesOf(h);
        int s = CxJ.n(st, "season");
        for (int i = 0; i < eps.size(); i++) {
            if (CgData.n(eps.get(i), "season") == s && CgData.s(eps.get(i), "season_poster").length() > 0) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- step 2

    private int selectedCount() {
        int n = 0;
        for (int i = 0; i < selected.size(); i++) {
            if (selected.get(i).booleanValue()) n++;
        }
        return n;
    }

    private void updateCount() {
        if (countView != null) countView.setText("المحدد للنشر: " + selectedCount());
    }

    private void stepPreview() {
        if (preview == null) {
            showStep(1);
            return;
        }
        JSONObject counts = preview.optJSONObject("counts") == null ? new JSONObject() : preview.optJSONObject("counts");
        LinearLayout stats = CgUi.hbox(this);
        stats.addView(stat("جديدة", counts.optInt("new"), CgCfg.GOOD), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        stats.addView(stat("موجودة", counts.optInt("update"), CgCfg.ACCENT), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        stats.addView(stat("ناقصة", counts.optInt("missing"), CgCfg.WARN), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        stats.addView(stat("بدون ترجمة", counts.optInt("no_subtitle"), CgCfg.GRAY), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        page.addView(stats, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        JSONArray warnings = preview.optJSONArray("warnings");
        for (int i = 0; warnings != null && i < warnings.length(); i++) {
            page.addView(CxUi.notice(this, CxUi.WARN, warnings.optString(i)));
        }
        if (warnings != null && warnings.length() > 0) {
            page.addView(CgUi.muted(this, "هذه تنبيهات فقط — يمكنك المتابعة والنشر."));
        }
        countView = CgUi.text(this, "", 13, CgCfg.ACCENT, true);
        page.addView(countView, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 8, 4, 6));
        updateCount();

        JSONArray rows = preview.optJSONArray("rows");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            final int index = i;
            final JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            page.addView(rowView(index, row));
        }

        // season poster
        CxUi.Section ps = new CxUi.Section(this, "بوستر الموسم", "يُطبَّق على حلقات هذا الموسم فقط ولا يُنشئ مسلسلاً", true, false);
        String tm = CxJ.s(preview, "season_poster");
        final CxUi.PosterBox pb = new CxUi.PosterBox(this, 110, 1.5f, "2:3");
        pb.set(tm);
        ps.add(pb);
        final com.my.newproject.CgUi.Toggle tg = CgUi.toggle(this, "استخدم بوستر الموسم من TMDB", useTmdbPoster);
        tg.onChange(new Runnable() {
            @Override
            public void run() {
                useTmdbPoster = tg.on;
            }
        });
        if (tm.length() > 0) ps.add(tg);
        posterUrl = new CxUi.Field(this, "أو رابط بوستر مخصص", "https://...", CxUi.T_URL, false);
        posterUrl.set(posterText);
        if (posterText.length() > 0) pb.set(posterText);
        posterUrl.onChange(new Runnable() {
            @Override
            public void run() {
                posterText = posterUrl.text();
                pb.set(posterUrl.text().length() > 0 ? posterUrl.text() : CxJ.s(preview, "season_poster"));
            }
        });
        ps.add(CgUi.space(this, 8));
        ps.add(posterUrl.view);
        page.addView(ps);

        TextView check = CgUi.button(this, "🔍  فحص روابط الحلقات المحددة (اختياري)", 1);
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkLinks();
            }
        });
        page.addView(check, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));

        bottomButton("→ السابق", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStep(1);
            }
        });
        bottomButton("متابعة للنشر ←", 0, 1.6f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedCount() == 0) {
                    snack("حدد حلقة واحدة على الأقل للنشر.", CxUi.WARN);
                    return;
                }
                if (posterUrl != null && posterUrl.text().length() > 0 && !CxJ.isUrl(posterUrl.text())) {
                    posterUrl.error("رابط البوستر يجب أن يبدأ بـ http:// أو https://");
                    return;
                }
                showStep(3);
            }
        });
    }

    private View stat(String label, int value, int color) {
        LinearLayout c = CgUi.vbox(this);
        c.setBackground(CgUi.bg(this, CgCfg.CARD, 12, CgCfg.STROKE));
        c.setGravity(android.view.Gravity.CENTER);
        c.setPadding(CgUi.dp(this, 4), CgUi.dp(this, 8), CgUi.dp(this, 4), CgUi.dp(this, 8));
        TextView v = CgUi.text(this, String.valueOf(value), 20, color, true);
        v.setGravity(android.view.Gravity.CENTER);
        c.addView(v, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        TextView l = CgUi.muted(this, label);
        l.setGravity(android.view.Gravity.CENTER);
        l.setTextSize(11);
        c.addView(l, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP);
        lp.setMargins(CgUi.dp(this, 3), 0, CgUi.dp(this, 3), 0);
        c.setLayoutParams(lp);
        return c;
    }

    private View rowView(final int index, JSONObject row) {
        final String state = CxJ.s(row, "state");
        final boolean missing = "missing".equals(state);
        final LinearLayout box = CgUi.hbox(this);
        box.setPadding(CgUi.dp(this, 10), CgUi.dp(this, 9), CgUi.dp(this, 10), CgUi.dp(this, 9));
        box.setClickable(!missing);
        final TextView mark = CgUi.text(this, "", 16, CgCfg.ACCENT, true);
        mark.setGravity(android.view.Gravity.CENTER);
        box.addView(mark, new LinearLayout.LayoutParams(CgUi.dp(this, 28), CgUi.WRAP));
        box.addView(CgUi.text(this, String.format(java.util.Locale.US, "E%02d", row.optInt("ep")), 13.5f, CgCfg.ACCENT, true), new LinearLayout.LayoutParams(CgUi.dp(this, 44), CgUi.WRAP));
        LinearLayout col = CgUi.vbox(this);
        TextView t = CgUi.text(this, CxJ.s(row, "title"), 13.5f, CgCfg.TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);
        LinearLayout badges = CgUi.hbox(this);
        boolean hasVideo = CxJ.s(row, "video").length() > 0;
        boolean hasSub = CxJ.s(row, "subtitle").length() > 0;
        if (missing) {
            badges.addView(CgUi.badge(this, "الفيديو ✖ غائب", CgCfg.WARN));
        } else {
            badges.addView(CgUi.badge(this, hasVideo ? "فيديو ✔" : "فيديو (محفوظ)", hasVideo ? CgCfg.GOOD : CgCfg.GRAY));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            lp.setMargins(CgUi.dp(this, 5), 0, 0, 0);
            boolean subOk = hasSub || CxJ.s(row, "existing_subtitle").length() > 0;
            badges.addView(CgUi.badge(this, subOk ? "ترجمة ✔" : "ترجمة —", subOk ? CgCfg.GOOD : CgCfg.GRAY), lp);
        }
        final TextView chk = CgUi.text(this, "", 11, CgCfg.MUTED, false);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        cl.setMargins(CgUi.dp(this, 6), 0, 0, 0);
        badges.addView(chk, cl);
        col.addView(badges, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 4, 0, 0));
        box.addView(col, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        String label = missing ? "ناقصة" : "new".equals(state) ? "جديدة" : row.optBoolean("changed", false) ? "تحديث" : "موجودة";
        int color = missing ? CgCfg.WARN : "new".equals(state) ? CgCfg.GOOD : row.optBoolean("changed", false) ? CgCfg.ACCENT : CgCfg.GRAY;
        box.addView(CgUi.badge(this, label, color));
        final Runnable refresh = new Runnable() {
            @Override
            public void run() {
                boolean on = !missing && selected.get(index).booleanValue();
                CgUi.set(mark, missing ? "⚠" : on ? "☑" : "☐");
                mark.setTextColor(missing ? CgCfg.WARN : on ? CgCfg.ACCENT : CgCfg.MUTED);
                box.setBackground(CgUi.bg(CxSeasonActivity.this, missing ? CgUi.alpha(CgCfg.WARN, 0x14) : on ? CgUi.alpha(CgCfg.ACCENT, 0x18) : CgCfg.CARD, 12, missing ? CgCfg.WARN : on ? CgCfg.ACCENT : CgCfg.STROKE));
                String cs = checkState.get(index);
                CgUi.set(chk, "ok".equals(cs) ? "✔ يعمل" : cs.length() > 0 ? "✖ " + CgData.checkLabel(cs) : "");
                chk.setTextColor("ok".equals(cs) ? CgCfg.GOOD : CgCfg.BAD);
            }
        };
        refresh.run();
        box.setTag(refresh);
        if (!missing) {
            box.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selected.set(index, Boolean.valueOf(!selected.get(index).booleanValue()));
                    refresh.run();
                    updateCount();
                }
            });
        }
        box.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));
        return box;
    }

    private void checkLinks() {
        final JSONArray rows = preview.optJSONArray("rows");
        if (rows == null) return;
        final ArrayList<Integer> idx = new ArrayList<Integer>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r != null && selected.get(i).booleanValue() && CxJ.s(r, "video").length() > 0 && !CgVidbolt.is(CxJ.s(r, "video"))) idx.add(Integer.valueOf(i));
        }
        if (idx.isEmpty()) {
            snack("لا توجد روابط فيديو جديدة لفحصها.", CxUi.INFO);
            return;
        }
        showLoading("جارٍ فحص " + idx.size() + " رابطاً...");
        exec(new Job<ArrayList<String>>() {
            @Override
            public ArrayList<String> run() throws Exception {
                ArrayList<String> out = new ArrayList<String>();
                for (int i = 0; i < rows.length(); i++) out.add("");
                int batch = 20;
                for (int from = 0; from < idx.size(); from += batch) {
                    JSONArray items = new JSONArray();
                    int to = Math.min(idx.size(), from + batch);
                    for (int k = from; k < to; k++) {
                        int ri = idx.get(k).intValue();
                        JSONObject o = new JSONObject();
                        o.put("id", String.valueOf(ri));
                        o.put("kind", "video");
                        o.put("url", rows.getJSONObject(ri).optString("video"));
                        items.put(o);
                    }
                    JSONObject b = new JSONObject();
                    b.put("items", items);
                    JSONObject d = CxApi.post("/admin/check-urls", b);
                    JSONArray res = d.optJSONArray("results");
                    for (int k = 0; res != null && k < res.length(); k++) {
                        JSONObject r = res.getJSONObject(k);
                        try {
                            out.set(Integer.parseInt(r.optString("id")), r.optString("status", ""));
                        } catch (Exception ignored) { }
                    }
                }
                return out;
            }
        }, new Cb<ArrayList<String>>() {
            @Override
            public void ok(ArrayList<String> r) {
                hideLoading();
                int bad = 0;
                for (int i = 0; i < r.size() && i < checkState.size(); i++) {
                    checkState.set(i, r.get(i));
                    if (r.get(i).length() > 0 && !"ok".equals(r.get(i))) bad++;
                }
                showStep(2);
                snack(bad == 0 ? "كل الروابط المفحوصة تعمل." : bad + " رابط لا يعمل — راجع الحلقات المعلّمة.", bad == 0 ? CxUi.GOOD : CxUi.WARN);
            }

            @Override
            public void fail(CxApi.Failure f) {
                hideLoading();
                failDialog(f.getMessage(), new Runnable() {
                    @Override
                    public void run() {
                        checkLinks();
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- step 3

    private void stepPublish() {
        JSONObject h = header();
        LinearLayout c = CgUi.card(this);
        c.addView(CgUi.text(this, "جاهز لنشر الموسم " + CxJ.s(st, "season"), 16, CgCfg.TEXT, true));
        c.addView(CgUi.muted(this, (h == null ? "" : CgData.s(h, "title")) + " — " + selectedCount() + " حلقة محددة"));
        c.addView(CgUi.space(this, 6));
        c.addView(CgUi.muted(this, "تُكتب كل الحلقات في عملية واحدة آمنة مع نسخة احتياطية قبلها، ويمكنك التراجع بعد النشر. الحلقات الناقصة لا تمنع النشر."));
        c.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        page.addView(c);
        bottomButton("→ السابق", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStep(2);
            }
        });
        bottomButton("نشر الموسم", 0, 1.6f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                publish();
            }
        });
    }

    private void publish() {
        final JSONArray rows = preview.optJSONArray("rows");
        final JSONArray eps = new JSONArray();
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null || !selected.get(i).booleanValue()) continue;
            JSONObject o = new JSONObject();
            CxJ.put(o, "ep", r.optInt("ep"));
            CxJ.put(o, "title", CxJ.s(r, "title"));
            CxJ.put(o, "video", CxJ.s(r, "video"));
            CxJ.put(o, "subtitle", CxJ.s(r, "subtitle"));
            CxJ.put(o, "thumb", CxJ.s(r, "thumb"));
            CxJ.put(o, "overview", CxJ.s(r, "overview"));
            CxJ.put(o, "duration", CxJ.s(r, "duration"));
            CxJ.put(o, "year", CxJ.s(r, "year"));
            eps.put(o);
        }
        final String seriesKey = CxJ.s(st, "series_key");
        final int s = CxJ.n(st, "season");
        final String poster = posterUrl != null && posterUrl.text().length() > 0 ? posterUrl.text() : (useTmdbPoster ? CxJ.s(preview, "season_poster") : "");
        final JSONObject h = header();
        final CxStages sg = CxStages.show(this, "نشر الموسم " + s, new String[]{"تحليل الملفات", "مطابقة الحلقات", "الحفظ في قاعدة البيانات", "التحقق", "اكتمل"});
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                sg.stage(0, "");
                sg.stage(1, eps.length() + " حلقة");
                sg.stage(2, "نسخة احتياطية ثم كتابة واحدة");
                JSONObject b = new JSONObject();
                b.put("series_id", seriesKey);
                b.put("season", s);
                b.put("episodes", eps);
                b.put("update_existing", true);
                if (seriesTmdb().length() > 0) b.put("tmdb", seriesTmdb());
                if (poster.length() > 0) b.put("season_poster", poster);
                JSONObject r = CxApi.post("/admin/season/commit", b);
                sg.stage(3, "");
                sg.stage(4, "");
                return r;
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                sg.dismiss();
                if (auto != null) auto.finish();
                String note = r.optInt("added") + " جديدة، " + r.optInt("updated") + " محدّثة";
                CgData.load(CxSeasonActivity.this, new CgHttp.Done<CgData>() {
                    @Override
                    public void ok(CgData d) { }

                    @Override
                    public void fail(String message) { }
                });
                CxNav.success(CxSeasonActivity.this, "season", h == null ? "" : CgData.s(h, "id"), h == null ? "" : CgData.s(h, "title"),
                        h == null ? "" : CxOps2.posterOf(h), note, r.optString("undo_id"), seriesKey, s, 0);
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
