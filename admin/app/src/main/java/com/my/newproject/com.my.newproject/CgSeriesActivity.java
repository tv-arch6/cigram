package com.my.newproject;

import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;

/**
 * Series hub: header summary, "add next episode", season chips, subtitle filter/status,
 * auto-link .srt files, on-demand URL checks, merge suggestion, notes and bulk edit.
 */
public class CgSeriesActivity extends CgBase {

    private static final int PAGE = 50;

    private String seriesId = "";
    private int seasonFilter = -1;      // -1 = all
    private boolean onlyNoSub;
    private boolean selecting;
    private int shown = PAGE;
    private final LinkedHashSet<String> selected = new LinkedHashSet<String>();
    private LinearLayout content;

    @Override
    protected String screenTitle() {
        return "المسلسل";
    }

    @Override
    protected void onBuild() {
        seriesId = getIntent().getStringExtra("id");
        if (seriesId == null) seriesId = "";
        bar.action("كاروسيل", new Runnable() {
            @Override
            public void run() {
                CxNav.carouselAdd(CgSeriesActivity.this, seriesId);
            }
        });
        bar.action("+ حلقة", new Runnable() {
            @Override
            public void run() {
                CxNav.addEpisode(CgSeriesActivity.this, seriesId, 0, 0);
            }
        });
        bar.action("تحديد", new Runnable() {
            @Override
            public void run() {
                selecting = !selecting;
                selected.clear();
                render();
            }
        });
        ensureData(new Runnable() {
            @Override
            public void run() {
                render();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (CgData.loaded() && content != null) render();
    }

    @Override
    public void onBackPressed() {
        if (selecting) {
            selecting = false;
            selected.clear();
            render();
            return;
        }
        super.onBackPressed();
    }

    private JSONObject header() {
        CgData d = CgData.cur();
        return d == null ? null : d.byId(seriesId);
    }

    private void render() {
        final CgData d = CgData.cur();
        final JSONObject h = header();
        content = scrollBody();
        if (d == null || h == null) {
            content.addView(CgUi.empty(this, "المسلسل غير موجود", "ربما حُذف أو دُمج مع مسلسل آخر."));
            return;
        }
        bar.titleView.setText(CgData.s(h, "title"));
        View warn = warningCard();
        if (warn != null) content.addView(warn);

        content.addView(headerCard(d, h));
        View merge = mergeBanner(d, h);
        if (merge != null) content.addView(merge);
        content.addView(actionsCard(d, h));

        final ArrayList<JSONObject> eps = d.episodesOf(h);
        ArrayList<Integer> seasons = d.seasonsOf(h);
        content.addView(CgUi.sectionTitle(this, "الحلقات (" + eps.size() + ")"));

        CgUi.ChipBar chips = new CgUi.ChipBar(this);
        TextView all = chips.add("كل المواسم", seasonFilter < 0);
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seasonFilter = -1;
                shown = PAGE;
                render();
            }
        });
        for (int i = 0; i < seasons.size(); i++) {
            final int sn = seasons.get(i).intValue();
            TextView c = chips.add("الموسم " + sn, seasonFilter == sn);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    seasonFilter = sn;
                    shown = PAGE;
                    render();
                }
            });
        }
        TextView noSub = chips.add("بدون ترجمة", onlyNoSub);
        noSub.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onlyNoSub = !onlyNoSub;
                shown = PAGE;
                render();
            }
        });
        content.addView(chips.scroll, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        if (seasonFilter >= 0) {
            String sn = d.seasonNote(CgData.seriesKey(h), seasonFilter);
            if (sn.length() > 0) {
                TextView note = CgUi.text(this, "ملاحظة الموسم: " + sn, 12.5f, CgCfg.WARN, false);
                note.setPadding(CgUi.dp(this, 6), CgUi.dp(this, 6), CgUi.dp(this, 6), 0);
                content.addView(note);
            }
        }

        final ArrayList<JSONObject> visible = new ArrayList<JSONObject>();
        int noSubCount = 0;
        for (int i = 0; i < eps.size(); i++) {
            JSONObject e = eps.get(i);
            if (d.subState(e) != CgData.SUB_OK) noSubCount++;
            if (seasonFilter >= 0 && CgData.n(e, "season") != seasonFilter) continue;
            if (onlyNoSub && d.subState(e) == CgData.SUB_OK) continue;
            visible.add(e);
        }
        TextView summary = CgUi.muted(this, "المعروض " + visible.size() + " • الحلقات بدون ترجمة صالحة: " + noSubCount);
        summary.setPadding(CgUi.dp(this, 6), CgUi.dp(this, 6), CgUi.dp(this, 6), CgUi.dp(this, 6));
        content.addView(summary);

        if (selecting) {
            LinearLayout sb = CgUi.hbox(this);
            TextView selAll = CgUi.smallButton(this, "تحديد الكل", 1);
            selAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    for (int i = 0; i < visible.size(); i++) selected.add(CgData.s(visible.get(i), "id"));
                    render();
                }
            });
            sb.addView(selAll, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
            TextView bulk = CgUi.smallButton(this, "إجراء جماعي (" + selected.size() + ")", 0);
            bulk.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CgBulk.show(CgSeriesActivity.this, new ArrayList<String>(selected), new Runnable() {
                        @Override
                        public void run() {
                            selected.clear();
                            selecting = false;
                            render();
                        }
                    });
                }
            });
            sb.addView(bulk, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            content.addView(sb, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }

        if (visible.isEmpty()) {
            content.addView(CgUi.empty(this, eps.isEmpty() ? "لا توجد حلقات بعد" : "لا توجد حلقات مطابقة",
                    eps.isEmpty() ? "ابدأ بإضافة الحلقة الأولى من الزر أعلاه." : "غيّر الموسم أو أزل فلتر «بدون ترجمة»."));
            return;
        }
        int limit = Math.min(shown, visible.size());
        for (int i = 0; i < limit; i++) {
            final JSONObject e = visible.get(i);
            final String id = CgData.s(e, "id");
            View row = CgRows.itemRow(this, d, e, selecting, selected.contains(id), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (selecting) {
                        if (!selected.remove(id)) selected.add(id);
                        render();
                    } else {
                        openItem(id);
                    }
                }
            }, new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    selecting = true;
                    selected.add(id);
                    render();
                    return true;
                }
            });
            content.addView(row, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
        if (limit < visible.size()) {
            TextView more = CgUi.button(this, "عرض المزيد (" + (visible.size() - limit) + ")", 1);
            more.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    shown += PAGE;
                    render();
                }
            });
            content.addView(more, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }
    }

    private void openItem(String id) {
        Intent in = new Intent(this, CgItemActivity.class);
        in.putExtra("id", id);
        startActivity(in);
    }

    // ------------------------------------------------------------- header

    private View headerCard(final CgData d, final JSONObject h) {
        LinearLayout card = CgUi.card(this);
        LinearLayout top = CgUi.hbox(this);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(CgUi.dp(this, 80), CgUi.dp(this, 116));
        ilp.setMargins(0, 0, CgUi.dp(this, 12), 0);
        top.addView(img, ilp);
        CgImg.load(img, CgRows.posterOf(h), CgUi.dp(this, 160));

        LinearLayout col = CgUi.vbox(this);
        col.addView(CgUi.text(this, CgData.s(h, "title"), 17, CgCfg.TEXT, true));
        String section = CgData.s(h, "section_name");
        col.addView(CgUi.muted(this, "مسلسل • " + (section.length() > 0 ? section : "بدون قسم")));
        String slug = d.slugOf(h);
        TextView sl = CgUi.muted(this, "Slug: " + (slug.length() > 0 ? slug : "غير محدد"));
        CgUi.ltr(sl);
        col.addView(sl);
        int st = d.status(h);
        col.addView(CgUi.text(this, "● " + d.statusReason(h), 12.5f, CgUi.levelColor(st), false));
        LinearLayout tags = CgUi.hbox(this);
        if (CgData.b(h, "featured")) tags.addView(CgUi.badge(this, "★ مميز", CgCfg.WARN), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 4, 6, 0));
        if (CgData.b(h, "hidden")) tags.addView(CgUi.badge(this, "مخفي", CgCfg.GRAY), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 4, 6, 0));
        col.addView(tags);
        top.addView(col, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        card.addView(top);

        String note = d.noteOf(h);
        if (note.length() > 0) {
            TextView n = CgUi.text(this, "ملاحظة الأدمن: " + note, 12.5f, CgCfg.WARN, false);
            n.setPadding(0, CgUi.dp(this, 8), 0, 0);
            card.addView(n);
        }

        LinearLayout row = CgUi.hbox(this);
        TextView edit = CgUi.smallButton(this, "تعديل", 0);
        edit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openItem(seriesId);
            }
        });
        row.addView(edit, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
        final boolean feat = CgData.b(h, "featured");
        TextView ft = CgUi.smallButton(this, feat ? "إلغاء التمييز" : "★ تمييز", 1);
        ft.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    JSONObject ch = new JSONObject();
                    ch.put("featured", !feat);
                    CgOps.updateItem(CgSeriesActivity.this, seriesId, ch, feat ? "إلغاء التمييز" : "تمييز المسلسل", new Runnable() {
                        @Override
                        public void run() {
                            render();
                        }
                    });
                } catch (Exception ignored) { }
            }
        });
        row.addView(ft, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
        final boolean hid = CgData.b(h, "hidden");
        TextView hd = CgUi.smallButton(this, hid ? "إظهار" : "إخفاء", 1);
        hd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    JSONObject ch = new JSONObject();
                    ch.put("hidden", !hid);
                    CgOps.updateItem(CgSeriesActivity.this, seriesId, ch, hid ? "إظهار المسلسل" : "إخفاء المسلسل", new Runnable() {
                        @Override
                        public void run() {
                            render();
                        }
                    });
                } catch (Exception ignored) { }
            }
        });
        row.addView(hd, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
        TextView nt = CgUi.smallButton(this, "ملاحظة", 1);
        nt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgOps.saveNote(CgSeriesActivity.this, "item", seriesId, d.noteOf(h), "ملاحظة المسلسل", new Runnable() {
                    @Override
                    public void run() {
                        render();
                    }
                });
            }
        });
        row.addView(nt, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        TextView sb = CgUi.smallButton(this, "🔤 ترجمة", 1);
        sb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CxSubsActivity.open(CgSeriesActivity.this, seriesId);
            }
        });
        row.addView(sb, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 6, 0, 0, 0));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        card.addView(hs, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        return card;
    }

    /** Local duplicate hint (same normalized title or TMDB). The full detector lives on the merge screen. */
    private View mergeBanner(CgData d, JSONObject h) {
        String nt = CgData.normalize(CgData.s(h, "title")).replaceAll("\\b(season|الموسم|s)\\s*\\d+\\b", "").trim();
        String tmdb = CgVidbolt.tmdbOf(h);
        int found = 0;
        ArrayList<JSONObject> heads = d.seriesHeaders();
        for (int i = 0; i < heads.size(); i++) {
            JSONObject o = heads.get(i);
            if (CgData.s(o, "id").equals(CgData.s(h, "id"))) continue;
            String ot = CgData.normalize(CgData.s(o, "title")).replaceAll("\\b(season|الموسم|s)\\s*\\d+\\b", "").trim();
            boolean sameTmdb = tmdb.length() > 0 && tmdb.equals(CgData.s(o, "tmdb"));
            if (sameTmdb || (nt.length() >= 3 && nt.equals(ot))) found++;
        }
        if (found == 0) return null;
        LinearLayout c = CgUi.card(this);
        c.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.WARN, 0x1A), 14, CgCfg.WARN));
        c.addView(CgUi.text(this, "يبدو أن هذا موسم تابع لمسلسل موجود (" + found + " تطابق محتمل).", 13.5f, CgCfg.WARN, true));
        TextView b = CgUi.smallButton(this, "دمج مع مسلسل موجود", 0);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent in = new Intent(CgSeriesActivity.this, CgMergeActivity.class);
                in.putExtra("focus", seriesId);
                startActivity(in);
            }
        });
        c.addView(b, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 0, 0));
        c.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        return c;
    }

    // ------------------------------------------------------------ actions

    private View actionsCard(final CgData d, final JSONObject h) {
        LinearLayout card = CgUi.card(this);
        int[] next = d.nextEpisode(h);
        final int ns = seasonFilter > 0 ? seasonFilter : next[0];
        final int ne = seasonFilter > 0 ? d.nextEpisodeInSeason(h, seasonFilter) : next[1];
        TextView add = CgUi.button(this, "＋ إضافة الحلقة التالية  (" + String.format(java.util.Locale.US, "S%02dE%02d", ns, ne) + ")", 0);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent in = new Intent(CgSeriesActivity.this, CgItemActivity.class);
                in.putExtra("mode", "new_episode");
                in.putExtra("series_id", CgData.seriesKey(h));
                in.putExtra("season", ns);
                in.putExtra("episode", ne);
                startActivity(in);
            }
        });
        card.addView(add, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        LinearLayout r2 = CgUi.hbox(this);
        TextView relink = CgUi.button(this, "ربط ملفات الترجمة تلقائياً", 1);
        relink.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                relinkFlow(h);
            }
        });
        r2.addView(relink, CgUi.lpWeight(this, 1f, 0, 8, 4, 0));
        TextView check = CgUi.button(this, "فحص الروابط", 1);
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkFlow(d, h);
            }
        });
        r2.addView(check, CgUi.lpWeight(this, 1f, 4, 8, 0, 0));
        card.addView(r2, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        if (seasonFilter > 0) {
            TextView sn = CgUi.button(this, "ملاحظة الموسم " + seasonFilter, 3);
            sn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String key = CgData.seriesKey(h) + ":" + seasonFilter;
                    CgOps.saveNote(CgSeriesActivity.this, "season", key, d.seasonNote(CgData.seriesKey(h), seasonFilter),
                            "ملاحظة الموسم " + seasonFilter, new Runnable() {
                                @Override
                                public void run() {
                                    render();
                                }
                            });
                }
            });
            card.addView(sn, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        }
        card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));
        return card;
    }

    /** Dry-run first so the person sees how many files will be linked, then apply. */
    private void relinkFlow(final JSONObject h) {
        final String key = CgData.seriesKey(h);
        CgUi.choose(this, "ربط ملفات .srt من مجلد R2", new CharSequence[]{
                "ربط الحلقات التي بلا ترجمة فقط", "ربط الكل (استبدال الروابط الحالية)", "تحديد مجلد R2 يدوياً"
        }, new CgUi.IntCb() {
            @Override
            public void run(int index) {
                if (index == 2) {
                    CgUi.input(CgSeriesActivity.this, "اسم مجلد المسلسل في R2", "مثال: breaking-bad", CgData.cur().slugOf(h), false,
                            new CgUi.Callback<String>() {
                                @Override
                                public void run(String slug) {
                                    relinkRun(key, slug, false, h);
                                }
                            });
                } else {
                    relinkRun(key, "", index == 1, h);
                }
            }
        });
    }

    private JSONObject relinkBody(String key, String slug, boolean overwrite, boolean dry) throws Exception {
        JSONObject b = new JSONObject();
        b.put("series_id", key);
        if (slug.length() > 0) b.put("slug", slug);
        b.put("overwrite", overwrite);
        if (dry) b.put("dry_run", true);
        return b;
    }

    private void relinkRun(final String key, final String slug, final boolean overwrite, final JSONObject h) {
        showLoading("جارٍ مطابقة ملفات الترجمة مع الحلقات...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgHttp.apiPost("/admin/relink-subtitles", relinkBody(key, slug, overwrite, true)));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                final int linked = r.json.optInt("linked", 0);
                String msg = "مجلد R2: " + r.json.optString("slug") + "\nملفات ترجمة موجودة: " + r.json.optInt("subtitle_files")
                        + "\nسيتم ربط: " + linked + " حلقة\nمحفوظة كما هي: " + r.json.optInt("kept")
                        + "\nحلقات بلا ملف ترجمة مطابق: " + r.json.optInt("no_subtitle")
                        + "\nملفات ترجمة لم تُطابق أي حلقة: " + r.json.optInt("unmatched_subtitles");
                if (linked == 0) {
                    CgUi.info(CgSeriesActivity.this, "لا شيء للربط", msg);
                    return;
                }
                CgUi.confirm(CgSeriesActivity.this, "ربط الترجمات", msg, "نفّذ الربط", false, new Runnable() {
                    @Override
                    public void run() {
                        relinkApply(key, slug, overwrite);
                    }
                });
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(CgSeriesActivity.this, "تعذر الربط", message);
            }
        });
    }

    private void relinkApply(final String key, final String slug, final boolean overwrite) {
        showLoading("جارٍ ربط الترجمات...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgHttp.apiPost("/admin/relink-subtitles", relinkBody(key, slug, overwrite, false)));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(final CgHttp.Res r) {
                hideLoading();
                reloadData(new Runnable() {
                    @Override
                    public void run() {
                        render();
                        showUndo("ربط " + r.json.optInt("linked") + " ترجمة", r.json.optString("undo_id", ""), new Runnable() {
                            @Override
                            public void run() {
                                render();
                            }
                        });
                    }
                });
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(CgSeriesActivity.this, "تعذر الربط", message);
            }
        });
    }

    private void checkFlow(final CgData d, final JSONObject h) {
        final ArrayList<JSONObject> list = new ArrayList<JSONObject>();
        ArrayList<JSONObject> eps = d.episodesOf(h);
        for (int i = 0; i < eps.size(); i++) {
            JSONObject e = eps.get(i);
            if (seasonFilter >= 0 && CgData.n(e, "season") != seasonFilter) continue;
            list.add(e);
        }
        final String scope = seasonFilter >= 0 ? "الموسم " + seasonFilter : "كل الحلقات";
        CgUi.choose(this, "فحص روابط " + scope + " (" + list.size() + " حلقة)", new CharSequence[]{
                "فحص الفيديو والترجمة", "فحص الفيديو فقط", "فحص الترجمة فقط"
        }, new CgUi.IntCb() {
            @Override
            public void run(final int index) {
                final ArrayList<CgOps.CheckReq> reqs = CgOps.requestsFor(list, index != 2, index != 1);
                CgUi.confirm(CgSeriesActivity.this, "فحص عند الطلب", "سيتم فحص " + reqs.size()
                                + " رابطاً على دفعات (20 رابطاً لكل طلب). لا يُنفَّذ هذا تلقائياً.", "ابدأ الفحص", false,
                        new Runnable() {
                            @Override
                            public void run() {
                                CgOps.runChecks(CgSeriesActivity.this, reqs, new CgOps.CheckDone() {
                                    @Override
                                    public void done(int checked, int bad) {
                                        CgUi.toast(CgSeriesActivity.this, "تم فحص " + checked + " رابطاً • مشاكل: " + bad);
                                        render();
                                    }
                                });
                            }
                        });
            }
        });
    }
}
