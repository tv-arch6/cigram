package com.my.newproject;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Add or update ONE episode. The series comes from a searchable list, the next episode is computed from the
 * real highest numbers (not JSON order), file names reveal season/episode, matching .srt files are linked
 * only when unambiguous, and an existing episode becomes an update instead of a duplicate.
 */
public class CxEpisodeActivity extends CxBase {

    private static final String[] STEPS = {"المسلسل", "الحلقة", "المعاينة", "النشر"};

    private JSONObject st = new JSONObject();
    private JSONObject orig;
    private String editId = "";
    private int step;
    private CxDraft.Auto auto;

    private CxUi.Field season;
    private CxUi.Field episode;
    private CxUi.Field title;
    private CxUi.Field video;
    private CxUi.Field subtitle;
    private LinearLayout dupBox;
    private LinearLayout detectBox;
    private LinearLayout r2Box;
    private boolean skipVideoCheck;
    private final HashMap<String, JSONObject> r2Cache = new HashMap<String, JSONObject>();

    @Override
    protected String screenTitle() {
        return "إضافة حلقة";
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

    private JSONObject header() {
        CgData d = CgData.cur();
        String key = CxJ.s(st, "series_key");
        if (d == null || key.length() == 0) return null;
        JSONObject h = d.byId(key);
        if (h != null && CgData.kind(h) == CgData.K_SERIES) return h;
        ArrayList<JSONObject> all = d.seriesHeaders();
        for (int i = 0; i < all.size(); i++) {
            if (CgData.seriesKey(all.get(i)).equals(key) || CgData.s(all.get(i), "id").equals(key)) return all.get(i);
        }
        return null;
    }

    private void start() {
        CgData d = CgData.cur();
        String draftId = getIntent().getStringExtra("draft_id");
        editId = getIntent().getStringExtra("edit_id") == null ? "" : getIntent().getStringExtra("edit_id");
        int startStep = 0;
        if (editId.length() > 0) {
            orig = d.byId(editId);
            if (orig == null) {
                page.addView(CgUi.empty(this, "الحلقة غير موجودة", "ربما حُذفت."));
                return;
            }
            bar.titleView.setText("تعديل حلقة");
            CxJ.put(st, "series_key", CgData.s(orig, "series_id"));
            CxJ.put(st, "season", String.valueOf(CgData.n(orig, "season")));
            CxJ.put(st, "episode", String.valueOf(CgData.n(orig, "episode")));
            CxJ.put(st, "title", CgData.s(orig, "title"));
            CxJ.put(st, "video", CxOps2.videoOf(orig));
            CxJ.put(st, "subtitle", CgData.s(orig, "subtitle"));
            startStep = 1;
        } else if (draftId != null && draftId.length() > 0) {
            JSONObject dr = CxDraft.load(this, draftId);
            if (dr != null) {
                st = dr.optJSONObject("data") == null ? new JSONObject() : dr.optJSONObject("data");
                startStep = Math.max(0, Math.min(2, dr.optInt("step", 0)));
            }
        } else {
            String sid = getIntent().getStringExtra("series_id");
            if (sid != null && sid.length() > 0) {
                CxJ.put(st, "series_key", sid);
                JSONObject h = header();
                if (h != null) {
                    int[] next = d.nextEpisode(h);
                    int s = getIntent().getIntExtra("season", 0);
                    int e = getIntent().getIntExtra("episode", 0);
                    if (s <= 0) {
                        s = next[0];
                        e = next[1];
                    } else if (e <= 0) {
                        e = d.nextEpisodeInSeason(h, s);
                    }
                    CxJ.put(st, "season", String.valueOf(s));
                    CxJ.put(st, "episode", String.valueOf(e));
                    startStep = 1;
                }
            }
        }
        auto = new CxDraft.Auto(this, "episode", draftId, new CxDraft.Source() {
            @Override
            public boolean worthSaving() {
                if (editId.length() == 0) collect2();
                return editId.length() == 0 && (CxJ.s(st, "video").length() > 0 || CxJ.s(st, "title").length() > 0);
            }

            @Override
            public JSONObject collect() {
                collect2();
                return st;
            }

            @Override
            public String title() {
                JSONObject h = header();
                String t = h == null ? "حلقة" : CgData.s(h, "title");
                return t + " — " + CxNames.code(CxJ.n(st, "season"), CxJ.n(st, "episode"));
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
        int min = editId.length() > 0 ? 1 : 0;
        if (step > min) {
            showStep(step - 1);
            return;
        }
        if (auto != null) auto.flush();
        super.onBackPressed();
    }

    private void collect2() {
        if (step != 1 || season == null) return;
        CxJ.put(st, "season", season.text());
        CxJ.put(st, "episode", episode.text());
        CxJ.put(st, "title", title.text());
        CxJ.put(st, "video", video.text());
        CxJ.put(st, "subtitle", subtitle.text());
    }

    private void showStep(int i) {
        collect2();
        step = i;
        newPage(i);
        if (i == 0) stepSeries();
        else if (i == 1) stepForm();
        else if (i == 2) stepPreview();
        else stepPublish();
    }

    // ---------------------------------------------------------------- step 0: series

    private String seriesSummary(CgData d, JSONObject h) {
        ArrayList<JSONObject> eps = d.episodesOf(h);
        ArrayList<Integer> seasons = d.seasonsOf(h);
        String last = "لا حلقات بعد";
        if (!eps.isEmpty()) {
            int[] next = d.nextEpisode(h);
            last = "آخر حلقة " + CxNames.code(next[0], Math.max(1, next[1] - 1));
        }
        return seasons.size() + " مواسم • " + eps.size() + " حلقة • " + last;
    }

    private void stepSeries() {
        final CgData d = CgData.cur();
        final JSONObject latest = CxDraft.latest(this, "episode");
        if (editId.length() == 0 && latest != null && !latest.optString("id").equals(auto.id())) {
            page.addView(draftBanner(latest, new Runnable() {
                @Override
                public void run() {
                    CxNav.resumeDraft(CxEpisodeActivity.this, latest);
                    finish();
                }
            }, new Runnable() {
                @Override
                public void run() {
                    CxDraft.delete(CxEpisodeActivity.this, latest.optString("id"));
                    showStep(0);
                }
            }));
        }
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
        col.addView(CgUi.muted(this, h == null ? "قائمة قابلة للبحث بالاسم" : seriesSummary(d, h)));
        if (h != null && CgData.s(h, "section_name").length() > 0) col.addView(CgUi.badge(this, CgData.s(h, "section_name"), CgCfg.ACCENT));
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
                if (auto != null) auto.flush();
                CxNav.addSeries(CxEpisodeActivity.this);
            }
        });
        page.addView(newSeries);
        if (d.seriesHeaders().isEmpty()) page.addView(CxUi.notice(this, CxUi.WARN, "لا توجد مسلسلات بعد. أنشئ المسلسل أولاً ثم أضف حلقاته."));
        bottomButton("التالي ←", 0, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (header() == null) {
                    snack("اختر المسلسل أولاً.", CxUi.WARN);
                    return;
                }
                prepareNumbers();
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
            opts.add(new CxUi.Option(CgData.seriesKey(h), CgData.s(h, "title"), seriesSummary(d, h), CxOps2.posterOf(h)));
        }
        CxUi.pick(this, "اختر المسلسل", opts, CxJ.s(st, "series_key"), null, new CgUi.Callback<CxUi.Option>() {
            @Override
            public void run(CxUi.Option o) {
                CxJ.put(st, "series_key", o.id);
                try {
                    st.remove("season");
                    st.remove("episode");
                } catch (Exception ignored) { }
                r2Cache.clear();
                prepareNumbers();
                touch();
                showStep(0);
            }
        }, null);
    }

    /** Fills season / episode with the real "next episode" when they are still empty. */
    private void prepareNumbers() {
        JSONObject h = header();
        if (h == null) return;
        if (CxJ.n(st, "season") > 0 && CxJ.n(st, "episode") > 0) return;
        int[] next = CgData.cur().nextEpisode(h);
        CxJ.put(st, "season", String.valueOf(next[0]));
        CxJ.put(st, "episode", String.valueOf(next[1]));
    }

    // ----------------------------------------------------------------- step 1: form

    private void stepForm() {
        final CgData d = CgData.cur();
        final JSONObject h = header();
        if (h == null) {
            showStep(0);
            return;
        }
        if (editId.length() > 0) {
            page.addView(CxUi.notice(this, CxUi.WARN, "وضع التحديث: تعدّل الحلقة الموجودة " + CxNames.code(CgData.n(orig, "season"), CgData.n(orig, "episode")) + " دون حذفها وإعادة إضافتها."));
        }
        LinearLayout head = CgUi.hbox(this);
        head.setBackground(CgUi.bg(this, CgCfg.CARD, 14, CgCfg.STROKE));
        head.setPadding(CgUi.dp(this, 8), CgUi.dp(this, 8), CgUi.dp(this, 8), CgUi.dp(this, 8));
        CxUi.PosterBox hp = new CxUi.PosterBox(this, 38, 1.5f, "2:3");
        hp.set(CxOps2.posterOf(h));
        head.addView(hp);
        LinearLayout hc = CgUi.vbox(this);
        hc.addView(CgUi.text(this, CgData.s(h, "title"), 14.5f, CgCfg.TEXT, true));
        hc.addView(CgUi.muted(this, seriesSummary(d, h)));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        hl.setMargins(CgUi.dp(this, 10), 0, 0, 0);
        head.addView(hc, hl);
        if (editId.length() == 0) {
            TextView change = CgUi.smallButton(this, "تغيير", 3);
            change.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showStep(0);
                }
            });
            head.addView(change);
        }
        page.addView(head, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        CxUi.Section num = new CxUi.Section(this, "الموسم والحلقة", "مرتبطة بالمسلسل عبر series_id", false, true);
        LinearLayout row = CgUi.hbox(this);
        row.setGravity(android.view.Gravity.TOP);
        season = new CxUi.Field(this, "الموسم", "1", CxUi.T_NUMBER, true);
        season.set(CxJ.s(st, "season"));
        episode = new CxUi.Field(this, "الحلقة", "1", CxUi.T_NUMBER, true);
        episode.set(CxJ.s(st, "episode"));
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        a1.setMargins(CgUi.dp(this, 4), 0, CgUi.dp(this, 4), 0);
        row.addView(season.view, a1);
        LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        a2.setMargins(CgUi.dp(this, 4), 0, CgUi.dp(this, 4), 0);
        row.addView(episode.view, a2);
        num.add(row);
        if (editId.length() == 0) {
            CxUi.Flow quick = new CxUi.Flow(this);
            int[] next = d.nextEpisode(h);
            final int ns = next[0];
            final int ne = next[1];
            quick.addView(CxUi.chip(this, "الحلقة التالية " + CxNames.code(ns, ne), false, new Runnable() {
                @Override
                public void run() {
                    season.set(String.valueOf(ns));
                    episode.set(String.valueOf(ne));
                    onNumbersChanged();
                }
            }));
            quick.addView(CxUi.chip(this, "موسم جديد " + CxNames.code(ns + 1, 1), false, new Runnable() {
                @Override
                public void run() {
                    season.set(String.valueOf(ns + 1));
                    episode.set("1");
                    onNumbersChanged();
                }
            }));
            ArrayList<Integer> seasons = d.seasonsOf(h);
            for (int i = 0; i < seasons.size() && i < 8; i++) {
                final int sn = seasons.get(i).intValue();
                if (sn == ns && sn == CxJ.n(st, "season")) continue;
                final int en = d.nextEpisodeInSeason(h, sn);
                quick.addView(CxUi.chip(this, "م" + sn + " ← E" + en, false, new Runnable() {
                    @Override
                    public void run() {
                        season.set(String.valueOf(sn));
                        episode.set(String.valueOf(en));
                        onNumbersChanged();
                    }
                }));
            }
            num.add(quick);
        }
        dupBox = CgUi.vbox(this);
        num.add(dupBox);
        Runnable nc = new Runnable() {
            @Override
            public void run() {
                onNumbersChanged();
            }
        };
        season.onChange(nc);
        episode.onChange(nc);
        page.addView(num);

        CxUi.Section info = new CxUi.Section(this, "بيانات الحلقة", "الاسم اختياري (الافتراضي «الحلقة N»)", false, true);
        title = new CxUi.Field(this, "عنوان الحلقة", "الحلقة " + CxJ.s(st, "episode"), CxUi.T_TEXT, false);
        title.set(CxJ.s(st, "title"));
        title.onChange(new Runnable() {
            @Override
            public void run() {
                touch();
            }
        });
        info.add(title.view);
        page.addView(info);

        CxUi.Section media = new CxUi.Section(this, "الفيديو والترجمة", "الترجمة اختيارية وغيابها ليس خطأً", false, true);
        video = new CxUi.Field(this, "رابط الفيديو", "https://.../S01E01.mp4", CxUi.T_URL, true);
        video.set(CxJ.s(st, "video"));
        video.onChange(new Runnable() {
            @Override
            public void run() {
                detectFromName();
                autoLink();
                touch();
            }
        });
        media.add(video.view);
        LinearLayout upBox = CgUi.vbox(this);
        CgMediaHook.addVideoButton(this, upBox, video, mediaTarget(), new CgMediaHook.NumbersSetter() {
            @Override
            public void set(int s, int e) {
                if (season == null || episode == null) return;
                season.set(String.valueOf(s));
                episode.set(String.valueOf(e));
                onNumbersChanged();
            }
        });
        media.add(upBox);
        LinearLayout vbrow = CgUi.hbox(this);
        TextView vbt = CgUi.smallButton(this, "🎬  رابط VidBolt تلقائي", 1);
        vbt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JSONObject h = header();
                String tmdb = CgVidbolt.tmdbOf(h);
                if (tmdb.length() == 0) {
                    video.error("المسلسل لا يملك TMDB ID. افتح بيانات المسلسل وأضفه أولاً.");
                    return;
                }
                int s = 0, e = 0;
                try { s = Integer.parseInt(season == null ? "" : season.text().trim()); } catch (Exception ignored) { }
                try { e = Integer.parseInt(episode == null ? "" : episode.text().trim()); } catch (Exception ignored) { }
                if (s < 1 || e < 1) {
                    video.error("أدخل رقم الموسم ورقم الحلقة أولاً.");
                    return;
                }
                String url = CgVidbolt.tv(tmdb, s, e);
                if (url.length() == 0) {
                    video.error("TMDB ID غير صالح.");
                    return;
                }
                lastAutoVideo = url;
                video.set(url);
                CxJ.put(st, "video", url);
                video.ok("تم إنشاء رابط VidBolt للحلقة.");
                touch();
            }
        });
        vbrow.addView(vbt, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        media.add(vbrow);
        detectBox = CgUi.vbox(this);
        media.add(detectBox);
        LinearLayout vrow = CgUi.hbox(this);
        TextView vt = CgUi.smallButton(this, "اختبار الفيديو", 1);
        vt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CxMedia.test(CxEpisodeActivity.this, "video", video.text(), video, null);
            }
        });
        vrow.addView(vt);
        media.add(vrow);
        media.add(CgUi.space(this, 10));
        subtitle = new CxUi.Field(this, "رابط الترجمة (.srt / .vtt)", "https://.../S01E01.srt", CxUi.T_URL, false);
        subtitle.set(CxJ.s(st, "subtitle"));
        subtitle.onChange(new Runnable() {
            @Override
            public void run() {
                touch();
            }
        });
        media.add(subtitle.view);
        LinearLayout srow = CgUi.hbox(this);
        TextView stt = CgUi.smallButton(this, "اختبار الترجمة", 1);
        stt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CxMedia.test(CxEpisodeActivity.this, "subtitle", subtitle.text(), subtitle, null);
            }
        });
        srow.addView(stt);
        CgMediaHook.addSubtitleButton(this, srow, subtitle, mediaTarget());
        CxSubsActivity.addButton(this, srow, subtitle, new CxSubsActivity.Provider() {
            @Override
            public JSONObject target() {
                JSONObject h = header();
                if (h == null) return null;
                JSONObject t = new JSONObject();
                CxJ.put(t, "type", "tv");
                CxJ.put(t, "title", CgData.s(h, "title"));
                CxJ.put(t, "tmdb_id", CgVidbolt.tmdbOf(h));
                CxJ.put(t, "imdb_id", CgData.s(h, "imdb_id"));
                CxJ.put(t, "year", CgData.s(h, "year"));
                CxJ.put(t, "season", season == null ? 0 : parse(season.text()));
                CxJ.put(t, "episode", episode == null ? 0 : parse(episode.text()));
                CxJ.put(t, "slug", mediaTarget().slug());
                CxJ.put(t, "release", CgData.s(h, "quality"));
                return t;
            }
        });
        TextView pick = CgUi.smallButton(this, "اختيار ترجمة من R2", 1);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickSubtitleManually();
            }
        });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        pl.setMargins(CgUi.dp(this, 8), 0, 0, 0);
        srow.addView(pick, pl);
        media.add(srow);
        page.addView(media);

        CxUi.Section r2 = new CxUi.Section(this, "ملفات جاهزة في مجلد R2", "فحص عند الطلب فقط — يربط الفيديو والترجمة تلقائياً", true, false);
        r2Box = CgUi.vbox(this);
        TextView scan = CgUi.button(this, "🔎  فحص مجلد المسلسل", 1);
        scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanR2(true);
            }
        });
        r2.add(scan);
        r2.add(r2Box);
        page.addView(r2);

        onNumbersChanged();
        detectFromName();

        bottomButton("→ السابق", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collect2();
                if (editId.length() > 0) finish();
                else showStep(0);
            }
        });
        if (editId.length() == 0) {
            bottomButton("حفظ مسودة", 1, 1f, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    collect2();
                    auto.flush();
                    CgUi.toast(CxEpisodeActivity.this, "تم حفظ المسودة.");
                    finish();
                }
            });
        }
        bottomButton("التالي ←", 0, 1.4f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (validateForm()) showStep(2);
            }
        });
    }

    private JSONObject existingEpisode() {
        JSONObject h = header();
        if (h == null || season == null) return null;
        int s = parse(season.text());
        int e = parse(episode.text());
        if (s <= 0 || e <= 0) return null;
        ArrayList<JSONObject> eps = CgData.cur().episodesOf(h);
        for (int i = 0; i < eps.size(); i++) {
            JSONObject x = eps.get(i);
            if (CgData.n(x, "season") == s && CgData.n(x, "episode") == e && !CgData.s(x, "id").equals(editId)) return x;
        }
        return null;
    }

    /** What the direct-upload screen needs to know about this episode (read when the button is pressed). */
    private CgMediaHook.Target mediaTarget() {
        return new CgMediaHook.Target() {
            @Override
            public String kind() {
                return "episode";
            }

            @Override
            public String slug() {
                JSONObject h = header();
                if (h == null) return "";
                CgData d = CgData.cur();
                // same folder the season screen and the Worker use (stored slug, else the one inferred from episode links)
                String sl = d == null ? "" : d.slugOf(h);
                return sl.length() > 0 ? sl : CgData.s(h, "slug");
            }

            @Override
            public String title() {
                JSONObject h = header();
                String t = h == null ? "" : CgData.s(h, "title");
                return t + " — " + CxNames.code(season == null ? 0 : parse(season.text()), episode == null ? 0 : parse(episode.text()));
            }

            @Override
            public String poster() {
                JSONObject h = header();
                return h == null ? "" : CgData.s(h, "poster");
            }

            @Override
            public int season() {
                return season == null ? 0 : parse(season.text());
            }

            @Override
            public int episode() {
                return episode == null ? 0 : parse(episode.text());
            }
        };
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        CgMediaHook.onResult(this, req, res, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // an upload that finished in the background while this form was closed or covered
        if (step == 1 && video != null && season != null && episode != null) CgMediaHook.offerCompleted(this, mediaTarget(), video);
    }

    private static int parse(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Duplicate banner: [تحديث الحلقة الموجودة] [إلغاء]. */
    private String lastAutoVideo = "";

    /**
     * VidBolt: a new episode gets its player link by itself from the series TMDB id + season + episode.
     * Only an empty field, or a link that this method wrote earlier, is replaced; anything typed or uploaded is kept.
     */
    private void autoVidbolt() {
        if (video == null || season == null || episode == null || editId.length() > 0) return;
        String cur = video.text().trim();
        if (cur.length() > 0 && !cur.equals(lastAutoVideo)) return;
        JSONObject h = header();
        String tmdb = CgVidbolt.tmdbOf(h);
        String url = CgVidbolt.tv(tmdb, parse(season.text()), parse(episode.text()));
        if (url.length() == 0 || url.equals(cur)) return;
        lastAutoVideo = url;
        video.set(url);
        CxJ.put(st, "video", url);
    }

    private void onNumbersChanged() {
        autoVidbolt();
        if (dupBox == null) return;
        dupBox.removeAllViews();
        title.input.setHint("الحلقة " + (parse(episode.text()) > 0 ? parse(episode.text()) : ""));
        final JSONObject ex = existingEpisode();
        if (ex == null) {
            touch();
            return;
        }
        LinearLayout box = CgUi.vbox(this);
        box.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.WARN, 0x1A), 12, CgCfg.WARN));
        box.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 10), CgUi.dp(this, 12), CgUi.dp(this, 10));
        box.addView(CgUi.text(this, "الحلقة " + CxNames.code(parse(season.text()), parse(episode.text())) + " موجودة بالفعل", 13.5f, CgCfg.WARN, true));
        box.addView(CgUi.muted(this, "«" + CgData.s(ex, "title") + "» — يمكنك تحديثها بدل إنشاء نسخة مكررة."));
        LinearLayout row = CgUi.hbox(this);
        TextView up = CgUi.smallButton(this, "تحديث الحلقة الموجودة", 0);
        up.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchToUpdate(ex);
            }
        });
        row.addView(up);
        TextView cancel = CgUi.smallButton(this, "إلغاء", 1);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JSONObject h = header();
                int[] next = CgData.cur().nextEpisode(h);
                season.set(String.valueOf(next[0]));
                episode.set(String.valueOf(next[1]));
                onNumbersChanged();
            }
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        cl.setMargins(CgUi.dp(this, 8), 0, 0, 0);
        row.addView(cancel, cl);
        box.addView(row, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 0, 0));
        dupBox.addView(box, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 4));
        touch();
    }

    /** Turns the form into an update of the existing episode, keeping what the admin typed. */
    private void switchToUpdate(JSONObject ex) {
        collect2();
        editId = CgData.s(ex, "id");
        orig = ex;
        bar.titleView.setText("تحديث حلقة موجودة");
        // keep typed video/subtitle; fall back to the stored values for empty fields
        if (CxJ.s(st, "video").length() == 0) CxJ.put(st, "video", CxOps2.videoOf(ex));
        if (CxJ.s(st, "subtitle").length() == 0) CxJ.put(st, "subtitle", CgData.s(ex, "subtitle"));
        if (CxJ.s(st, "title").length() == 0) CxJ.put(st, "title", CgData.s(ex, "title"));
        showStep(1);
        snack("تحويل إلى تحديث الحلقة الموجودة.", CxUi.INFO);
    }

    private void detectFromName() {
        if (detectBox == null || video == null) return;
        detectBox.removeAllViews();
        String url = video.text();
        if (url.length() == 0) return;
        final int[] found = CxNames.parse(url);
        if (found == null) {
            TextView t = CgUi.muted(this, "لم يُكتشف رقم الموسم/الحلقة من اسم الملف (" + CxNames.fileName(url) + ").");
            t.setTextSize(11.5f);
            detectBox.addView(t);
            return;
        }
        int cs = parse(season.text());
        int ce = parse(episode.text());
        final int fs = found[0] > 0 ? found[0] : cs;
        final int fe = found[1];
        if (fs == cs && fe == ce) {
            TextView ok = CgUi.text(this, "✔ اسم الملف يطابق " + CxNames.code(fs, fe), 12, CgCfg.GOOD, false);
            detectBox.addView(ok);
            return;
        }
        LinearLayout box = CgUi.hbox(this);
        box.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.WARN, 0x18), 12, CgCfg.WARN));
        box.setPadding(CgUi.dp(this, 10), CgUi.dp(this, 8), CgUi.dp(this, 10), CgUi.dp(this, 8));
        TextView t = CgUi.text(this, "اسم الملف يدل على " + CxNames.code(fs, fe) + " بينما المحدد " + CxNames.code(cs, ce), 12.5f, CgCfg.TEXT, false);
        box.addView(t, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        TextView apply = CgUi.smallButton(this, "تطبيق", 0);
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                season.set(String.valueOf(fs));
                episode.set(String.valueOf(fe));
                onNumbersChanged();
                detectFromName();
            }
        });
        box.addView(apply);
        detectBox.addView(box, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 6));
    }

    // ------------------------------------------------------------------------- R2

    private String r2Key() {
        return CxJ.s(st, "series_key") + "|" + parse(season == null ? CxJ.s(st, "season") : season.text());
    }

    private void scanR2(final boolean show) {
        final String key = r2Key();
        JSONObject cached = r2Cache.get(key);
        if (cached != null) {
            renderR2(cached);
            return;
        }
        final String seriesKey = CxJ.s(st, "series_key");
        final int s = parse(season.text());
        task("جارٍ فحص مجلد R2...", new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("series_id", seriesKey);
                if (s > 0) b.put("season", s);
                return CxApi.post("/admin/r2/list", b);
            }
        }, new Ok<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                r2Cache.put(key, r);
                renderR2(r);
                autoLink();
            }
        }, new Runnable() {
            @Override
            public void run() {
                scanR2(show);
            }
        });
    }

    private void renderR2(JSONObject r) {
        r2Box.removeAllViews();
        JSONArray files = r.optJSONArray("files");
        if (files == null || files.length() == 0) {
            r2Box.addView(CgUi.empty(this, "لا توجد ملفات مطابقة", "تأكد من اسم المجلد (" + CxJ.s(r, "slug") + ") وأسماء الملفات (S01E01)."));
            return;
        }
        r2Box.addView(CgUi.muted(this, "المجلد: " + CxJ.s(r, "slug") + " — اضغط على حلقة لتعبئة الفيديو والترجمة."));
        for (int i = 0; i < files.length(); i++) {
            final JSONObject f = files.optJSONObject(i);
            if (f == null) continue;
            LinearLayout row = CgUi.hbox(this);
            boolean exists = f.optBoolean("exists", false);
            row.setBackground(CgUi.bg(this, CgCfg.CARD2, 10, CgCfg.STROKE));
            row.setPadding(CgUi.dp(this, 10), CgUi.dp(this, 8), CgUi.dp(this, 10), CgUi.dp(this, 8));
            row.setClickable(true);
            row.addView(CgUi.text(this, CxNames.code(f.optInt("season"), f.optInt("episode")), 13, CgCfg.ACCENT, true), new LinearLayout.LayoutParams(CgUi.dp(this, 70), CgUi.WRAP));
            LinearLayout col = CgUi.vbox(this);
            col.addView(CgUi.text(this, CxJ.s(f, "video").length() > 0 ? CxJ.s(f, "video_name") : "— بدون فيديو —", 12, CgCfg.TEXT, false));
            col.addView(CgUi.muted(this, CxJ.s(f, "subtitle").length() > 0 ? "ترجمة: " + CxJ.s(f, "subtitle_name") : "بدون ترجمة"));
            row.addView(col, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            if (exists) row.addView(CgUi.badge(this, "مضافة", CgCfg.GRAY));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyR2Row(f);
                }
            });
            r2Box.addView(row, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));
        }
    }

    private void applyR2Row(JSONObject f) {
        season.set(String.valueOf(f.optInt("season")));
        episode.set(String.valueOf(f.optInt("episode")));
        if (CxJ.s(f, "video").length() > 0) video.set(CxJ.s(f, "video"));
        if (CxJ.s(f, "subtitle").length() > 0) subtitle.set(CxJ.s(f, "subtitle"));
        onNumbersChanged();
        detectFromName();
        touch();
        snack("تمت التعبئة من ملفات R2.", CxUi.GOOD);
    }

    /** Links the subtitle only when the folder scan holds exactly this season/episode; never guesses. */
    private void autoLink() {
        if (subtitle == null || subtitle.text().length() > 0) return;
        JSONObject r = r2Cache.get(r2Key());
        if (r == null) return;
        JSONArray files = r.optJSONArray("files");
        int s = parse(season.text());
        int e = parse(episode.text());
        for (int i = 0; files != null && i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f != null && f.optInt("season") == s && f.optInt("episode") == e && CxJ.s(f, "subtitle").length() > 0) {
                subtitle.set(CxJ.s(f, "subtitle"));
                subtitle.ok("رُبطت الترجمة تلقائياً: " + CxJ.s(f, "subtitle_name"));
                return;
            }
        }
    }

    private void pickSubtitleManually() {
        final String key = r2Key();
        JSONObject r = r2Cache.get(key);
        if (r == null) {
            snack("افحص المجلد أولاً لعرض ملفات الترجمة المتاحة.", CxUi.INFO);
            scanR2(true);
            return;
        }
        ArrayList<CxUi.Option> opts = new ArrayList<CxUi.Option>();
        JSONArray files = r.optJSONArray("files");
        for (int i = 0; files != null && i < files.length(); i++) {
            JSONObject f = files.optJSONObject(i);
            if (f != null && CxJ.s(f, "subtitle").length() > 0) {
                opts.add(new CxUi.Option(CxJ.s(f, "subtitle"), CxJ.s(f, "subtitle_name"), CxNames.code(f.optInt("season"), f.optInt("episode")), null));
            }
        }
        JSONArray un = r.optJSONArray("unmatched_subtitle_files");
        for (int i = 0; un != null && i < un.length(); i++) {
            JSONObject f = un.optJSONObject(i);
            if (f != null) opts.add(new CxUi.Option(CxJ.s(f, "url"), CxJ.s(f, "name"), "غير معروف الحلقة", null));
        }
        if (opts.isEmpty()) {
            snack("لا توجد ملفات ترجمة في المجلد.", CxUi.WARN);
            return;
        }
        CxUi.pick(this, "اختر ملف الترجمة", opts, subtitle.text(), null, new CgUi.Callback<CxUi.Option>() {
            @Override
            public void run(CxUi.Option o) {
                subtitle.set(o.id);
                touch();
            }
        }, null);
    }

    // ---------------------------------------------------------------- validation

    private boolean validateForm() {
        collect2();
        View first = null;
        int s = parse(season.text());
        int e = parse(episode.text());
        if (s <= 0) {
            season.error("رقم الموسم مطلوب (1 أو أكثر).");
            first = season.view;
        }
        if (e <= 0) {
            episode.error("رقم الحلقة مطلوب (1 أو أكثر).");
            if (first == null) first = episode.view;
        }
        if (existingEpisode() != null && first == null && editId.length() == 0) {
            episode.error("هذه الحلقة موجودة. اختر «تحديث الحلقة الموجودة» أو غيّر الرقم.");
            first = episode.view;
        }
        String v = video.text();
        if (v.length() == 0) {
            video.error("رابط الفيديو مطلوب. يمكنك حفظ مسودة والعودة لاحقاً.");
            if (first == null) first = video.view;
        } else if (!CxJ.isUrl(v)) {
            video.error("الرابط يجب أن يبدأ بـ http:// أو https://");
            if (first == null) first = video.view;
        }
        String su = subtitle.text();
        if (su.length() > 0 && !CxJ.isUrl(su)) {
            subtitle.error("رابط الترجمة يجب أن يبدأ بـ http:// أو https://");
            if (first == null) first = subtitle.view;
        }
        if (first != null) {
            scrollTo(first);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ step 2 / 3

    private void stepPreview() {
        JSONObject h = header();
        int s = CxJ.n(st, "season");
        int e = CxJ.n(st, "episode");
        String name = CxJ.s(st, "title").length() > 0 ? CxJ.s(st, "title") : "الحلقة " + e;
        page.addView(CgUi.sectionTitle(this, "هكذا ستظهر الحلقة"));
        page.addView(CxPreview.episodeCard(this, h == null ? "" : CxJ.firstNonEmpty(CgData.s(h, "backdrop"), CxOps2.posterOf(h)),
                CxNames.code(s, e) + (h == null ? "" : " • " + CgData.s(h, "title")), name,
                CxJ.s(st, "video").length() > 0, CxJ.s(st, "subtitle").length() > 0));
        page.addView(CxUi.notice(this, CxUi.GOOD, "الحلقة ستُربط بالمسلسل «" + (h == null ? "" : CgData.s(h, "title")) + "» عبر series_id — لن يُنشأ مسلسل جديد."));
        if (CxJ.s(st, "subtitle").length() == 0) page.addView(CxUi.notice(this, CxUi.INFO, "بدون ترجمة — ليس خطأً، ويمكن إضافتها لاحقاً."));
        TextView player = CgUi.button(this, "▶  معاينة بالمشغل", 1);
        player.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CxMedia.openPlayer(CxEpisodeActivity.this, CxJ.s(st, "title"), CxJ.s(st, "video"), CxJ.s(st, "subtitle"));
            }
        });
        page.addView(player, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
        bottomButton("→ السابق", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStep(1);
            }
        });
        bottomButton("متابعة للنشر ←", 0, 1.6f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStep(3);
            }
        });
    }

    private void stepPublish() {
        JSONObject h = header();
        boolean update = editId.length() > 0;
        LinearLayout c = CgUi.card(this);
        c.addView(CgUi.text(this, update ? "جاهز لتحديث الحلقة" : "جاهز لإضافة الحلقة", 16, CgCfg.TEXT, true));
        c.addView(CgUi.muted(this, (h == null ? "" : CgData.s(h, "title")) + " — " + CxNames.code(CxJ.n(st, "season"), CxJ.n(st, "episode"))));
        c.addView(CgUi.space(this, 6));
        c.addView(CgUi.muted(this, "المراحل: فحص التكرار ← التحقق من الرابط ← الحفظ ← التحقق. تُورَّث بيانات القسم والبوستر والجودة من المسلسل تلقائياً."));
        c.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        page.addView(c);
        bottomButton("→ السابق", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStep(2);
            }
        });
        bottomButton(update ? "حفظ التحديث" : "نشر الحلقة", 0, 1.6f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                skipVideoCheck = false;
                publish();
            }
        });
    }

    private JSONObject changes() {
        JSONObject ch = new JSONObject();
        if (orig == null) return ch;
        String[][] pairs = {{"title", "title"}, {"video", "video"}, {"subtitle", "subtitle"}};
        for (int i = 0; i < pairs.length; i++) {
            String nv = CxJ.s(st, pairs[i][0]);
            String ov = "video".equals(pairs[i][1]) ? CxOps2.videoOf(orig) : CgData.s(orig, pairs[i][1]);
            if (!nv.equals(ov)) CxJ.put(ch, pairs[i][1], nv);
        }
        if (CxJ.n(st, "season") != CgData.n(orig, "season")) CxJ.put(ch, "season", String.valueOf(CxJ.n(st, "season")));
        if (CxJ.n(st, "episode") != CgData.n(orig, "episode")) CxJ.put(ch, "episode", String.valueOf(CxJ.n(st, "episode")));
        return ch;
    }

    private void publish() {
        final boolean update = editId.length() > 0;
        final boolean skip = skipVideoCheck;
        final JSONObject h = header();
        final String seriesKey = CxJ.s(st, "series_key");
        final int s = CxJ.n(st, "season");
        final int e = CxJ.n(st, "episode");
        final CxStages sg = CxStages.show(this, update ? "تحديث الحلقة" : "نشر الحلقة",
                new String[]{"فحص التكرار", "التحقق من الرابط", "الحفظ في قاعدة البيانات", "التحقق من الحفظ", "اكتمل"});
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                sg.stage(0, CxNames.code(s, e));
                sg.stage(1, CxJ.s(st, "video"));
                if (!skip && !CgVidbolt.is(CxJ.s(st, "video"))) {
                    JSONObject b = new JSONObject();
                    JSONArray arr = new JSONArray();
                    arr.put(new JSONObject().put("id", "v").put("kind", "video").put("url", CxJ.s(st, "video")));
                    b.put("items", arr);
                    JSONObject d = CxApi.post("/admin/check-urls", b);
                    JSONArray res = d.optJSONArray("results");
                    if (res != null && res.length() > 0 && !"ok".equals(res.getJSONObject(0).optString("status"))) {
                        throw new CxApi.Failure("رابط الفيديو لا يعمل: " + CgData.checkLabel(res.getJSONObject(0).optString("status")), "VIDEO_BAD", null, 0);
                    }
                }
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
                    JSONObject f = new JSONObject();
                    f.put("series_id", seriesKey);
                    f.put("season", String.valueOf(s));
                    f.put("episode", String.valueOf(e));
                    f.put("title", CxJ.s(st, "title").length() > 0 ? CxJ.s(st, "title") : "الحلقة " + e);
                    f.put("video", CxJ.s(st, "video"));
                    f.put("link", CxJ.s(st, "video"));
                    if (CxJ.s(st, "subtitle").length() > 0) f.put("subtitle", CxJ.s(st, "subtitle"));
                    JSONObject b = new JSONObject();
                    b.put("type", "episode");
                    b.put("fields", f);
                    saved = CxApi.post("/admin/item/create", b);
                }
                JSONObject item = saved.optJSONObject("item");
                if (item == null) throw new CxApi.Failure("لم يؤكد الخادم الحفظ. أعد المحاولة.", "NO_ITEM", null, 0);
                sg.stage(3, "");
                sg.stage(4, "");
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
                CxNav.success(CxEpisodeActivity.this, "episode", CxJ.s(item, "id"), h == null ? "" : CgData.s(h, "title"),
                        h == null ? "" : CxOps2.posterOf(h), update ? "updated" : "created", r.optString("undo_id"), seriesKey,
                        CgData.n(item, "season"), CgData.n(item, "episode"));
                finish();
            }

            @Override
            public void fail(CxApi.Failure f) {
                handleFailure(sg, f);
            }
        });
    }

    private void handleFailure(final CxStages sg, CxApi.Failure f) {
        if (f.is("VIDEO_BAD")) {
            sg.dismiss();
            CgUi.confirm(this, "الفيديو لا يعمل", f.getMessage() + "\nهل تريد النشر رغم ذلك؟", "نشر رغم ذلك", true, new Runnable() {
                @Override
                public void run() {
                    skipVideoCheck = true;
                    publish();
                }
            });
        } else if (f.is("DUPLICATE_EPISODE")) {
            sg.dismiss();
            final JSONObject ex = f.existing();
            final String exId = CxJ.s(ex, "id");
            CgUi.confirm(this, "الحلقة موجودة بالفعل", f.getMessage() + "\nهل تريد تحديث الحلقة الموجودة بالبيانات الحالية؟", "تحديث الحلقة الموجودة", false, new Runnable() {
                @Override
                public void run() {
                    JSONObject found = CgData.cur().byId(exId);
                    if (found == null) {
                        snack("تعذر العثور على الحلقة محلياً. أعد تحميل الشاشة.", CxUi.WARN);
                        return;
                    }
                    switchToUpdate(found);
                    showStep(3);
                }
            });
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
