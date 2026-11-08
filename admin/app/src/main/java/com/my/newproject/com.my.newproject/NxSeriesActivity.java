package com.my.newproject;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * New "add series" screen, same idea as the movie screen adapted to seasons:
 *   1. type the name -> big posters -> tap -> poster + information card (and whether it is already in the library)
 *   2. the seasons from TMDB as cards: each one can take its episodes from the phone (season upload: picks many
 *      files, reads the episode numbers, uploads and publishes) or as links. The series record is created
 *      automatically before the first season is opened (or with the bottom button).
 */
public class NxSeriesActivity extends CxBase {

    private JSONObject st = new JSONObject();
    private boolean picked;
    private String seriesKey = "";   // existing / created series key ("" = not in the library yet)
    private String seriesItemId = "";
    private NxSearch search;
    private NxParts.Hero hero;

    @Override
    protected String screenTitle() {
        return "إضافة مسلسل";
    }

    @Override
    protected void onBuild() {
        initCx(null);
        try {
            bar.view.setBackgroundColor(NxUi.BG);
        } catch (Throwable ignored) { }
        ensureData(new Runnable() {
            @Override
            public void run() {
                render();
            }
        });
    }

    private boolean started;

    @Override
    protected void onResume() {
        super.onResume();
        // back from a season screen: the episode counts changed
        if (started && picked && page != null) render();
        started = true;
    }

    @Override
    public void onBackPressed() {
        if (picked && seriesKey.length() == 0) {
            picked = false;
            st = new JSONObject();
            render();
            return;
        }
        super.onBackPressed();
    }

    private int dp(float v) {
        return CgUi.dp(this, v);
    }

    // ===================================================================== render

    private void render() {
        if (search != null) search.stop();
        page.removeAllViews();
        page.addView(NxParts.step(this, 1, "اختر المسلسل", picked ? (seriesKey.length() > 0 ? "موجود في المكتبة — أضف المواسم والحلقات إليه" : "تم الاختيار") : "اكتب الاسم ثم اضغط على البوستر الصحيح", picked, true));
        if (!picked) {
            search = new NxSearch(this, "series", new CgUi.Callback<JSONObject>() {
                @Override
                public void run(JSONObject card) {
                    loadDetails(card);
                }
            });
            page.addView(search.view);
            TextView manual = CgUi.text(this, "استيراد مسلسل كامل من مجلد R2 (الشاشة الكلاسيكية)  ‹", 12.5f, NxUi.SUB, true);
            manual.setPadding(dp(6), dp(12), dp(6), dp(4));
            manual.setClickable(true);
            manual.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(NxSeriesActivity.this, SeriesImportActivity.class));
                }
            });
            page.addView(manual);
            page.addView(NxParts.step(this, 2, "المواسم والحلقات", "تُفتح بعد اختيار المسلسل", false, false));
            renderBottom();
            return;
        }
        hero = NxParts.hero(this, st, true, seriesKey.length() > 0 ? null : new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                picked = false;
                st = new JSONObject();
                render();
            }
        }, null);
        page.addView(hero.view);
        NxUi.enter(hero.view, 0);
        int[] totals = libraryTotals();
        if (seriesKey.length() > 0) {
            NxParts.status(this, hero.statusStrip, CgIcons.CHECK, NxUi.UP, "في المكتبة: " + totals[0] + " موسم • " + totals[1] + " حلقة", null);
        } else {
            NxParts.status(this, hero.statusStrip, CgIcons.INFO, NxUi.BLUE, "غير موجود في المكتبة — يُضاف تلقائياً عند فتح أول موسم", null);
            page.addView(NxParts.sections(this, st, true, new NxParts.Picked() {
                @Override
                public void run(String id, String name) {
                    CxJ.put(st, "section_id", id);
                    CxJ.put(st, "section_name", name);
                    render();
                }
            }), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 10, 0, 0));
        }

        page.addView(NxParts.step(this, 2, "المواسم والحلقات", "لكل موسم: رفع الحلقات من الجهاز أو إضافتها بروابط", false, true));
        JSONArray seasons = st.optJSONArray("seasons");
        if (seasons == null || seasons.length() == 0) {
            page.addView(seasonCard(1, "الموسم 1", 0, "", 0));
        } else {
            for (int i = 0; i < seasons.length(); i++) {
                JSONObject s = seasons.optJSONObject(i);
                if (s == null) continue;
                int n = s.optInt("season");
                View card = seasonCard(n, CxJ.firstNonEmpty(CxJ.s(s, "name"), "الموسم " + n), s.optInt("episode_count"), CxJ.s(s, "poster"), countEpisodes(n));
                page.addView(card, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, i == 0 ? 0 : 10, 0, 0));
                NxUi.enter(card, Math.min(6, i) * 50L);
            }
        }
        TextView more = CgUi.text(this, "+ موسم غير موجود في TMDB", 13, NxUi.BLUE, true);
        more.setGravity(Gravity.CENTER);
        more.setPadding(dp(10), dp(12), dp(10), dp(12));
        NxUi.pressable(more, NxUi.dashed(this, NxUi.BLUE, 16), 16);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUi.input(NxSeriesActivity.this, "رقم الموسم", "مثال: 3", "", false, new CgUi.Callback<String>() {
                    @Override
                    public void run(String value) {
                        try {
                            final int n = Integer.parseInt(value.trim());
                            if (n < 1 || n > 99) return;
                            CgUi.choose(NxSeriesActivity.this, "الموسم " + n, new CharSequence[]{"رفع الحلقات من الجهاز", "روابط VidBolt تلقائية"}, new CgUi.IntCb() {
                                @Override
                                public void run(int which) {
                                    openSeason(n, which == 0);
                                }
                            });
                        } catch (NumberFormatException ignored) {
                            snack("اكتب رقماً صحيحاً.", CxUi.WARN);
                        }
                    }
                });
            }
        });
        page.addView(more, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        renderBottom();
    }

    private View seasonCard(final int n, String name, int tmdbEpisodes, String poster, int inLibrary) {
        LinearLayout c = CgUi.hbox(this);
        c.setBackground(NxUi.surface(this, 20));
        c.setPadding(dp(12), dp(12), dp(12), dp(12));
        c.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(NxUi.flat(this, NxUi.SURF_HI, 12, NxUi.LINE));
        iv.setClipToOutline(true);
        String p = CxJ.firstNonEmpty(poster, CxJ.s(st, "poster"));
        if (p.startsWith("http")) CgImg.load(iv, p, 220);
        c.addView(iv, new LinearLayout.LayoutParams(dp(58), dp(87)));

        LinearLayout col = CgUi.vbox(this);
        col.addView(CgUi.text(this, name, 15, NxUi.TEXT, true));
        String info = (tmdbEpisodes > 0 ? tmdbEpisodes + " حلقة في TMDB" : "عدد الحلقات غير معروف");
        col.addView(CgUi.text(this, info, 11.5f, NxUi.SUB, false));
        int color = inLibrary <= 0 ? NxUi.DIM : (tmdbEpisodes > 0 && inLibrary >= tmdbEpisodes) ? NxUi.UP : NxUi.ORANGE;
        String lib = inLibrary <= 0 ? "لا توجد حلقات بعد" : inLibrary + (tmdbEpisodes > 0 ? " / " + tmdbEpisodes : "") + " في المكتبة";
        TextView lt = CgUi.text(this, lib, 11.5f, color, true);
        col.addView(lt, NxUi.lp(this, NxUi.WRAP, NxUi.WRAP, 0, 3, 0, 0));
        if (tmdbEpisodes > 0) {
            NxChart.Stack bar = new NxChart.Stack(this);
            int done = Math.min(inLibrary, tmdbEpisodes);
            bar.set(new double[]{done, tmdbEpisodes - done}, new int[]{color == NxUi.DIM ? NxUi.ORANGE : color, 0x00000000});
            col.addView(bar, NxUi.lp(this, NxUi.MATCH, dp(5), 0, 6, dp(4), 0));
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        cl.setMargins(dp(12), 0, dp(8), 0);
        c.addView(col, cl);

        LinearLayout acts = CgUi.vbox(this);
        ImageView up = NxUi.icon(this, NxIcons.icon(CgIcons.UPLOAD, 0xFFFFFFFF), 11);
        NxUi.pressable(up, NxUi.gradient(this, 0xFF4F6BFF, 0xFF6C8CFF, 14), 14);
        NxUi.hint(up, "رفع حلقات الموسم من الجهاز");
        up.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSeason(n, true);
            }
        });
        acts.addView(up, new LinearLayout.LayoutParams(dp(44), dp(44)));
        ImageView link = NxUi.icon(this, NxIcons.icon(CgIcons.LINK, NxUi.BLUE), 11);
        NxUi.pressable(link, NxUi.tint(this, NxUi.BLUE, 14), 14);
        NxUi.hint(link, "رابط VidBolt تلقائي لكل حلقات الموسم");
        link.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSeason(n, false);
            }
        });
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(44), dp(44));
        ll.setMargins(0, dp(8), 0, 0);
        acts.addView(link, ll);
        c.addView(acts);
        return c;
    }

    // ==================================================================== library

    private int countEpisodes(int season) {
        if (seriesKey.length() == 0) return 0;
        CgData d = CgData.cur();
        if (d == null) return 0;
        int n = 0;
        for (int i = 0; i < d.items.size(); i++) {
            JSONObject it = d.items.get(i);
            if (CgData.kind(it) != CgData.K_EPISODE) continue;
            if (!seriesKey.equals(CgData.s(it, "series_id"))) continue;
            if (CgData.n(it, "season") == season) n++;
        }
        return n;
    }

    /** {seasons, episodes} of this series already in the library. */
    private int[] libraryTotals() {
        if (seriesKey.length() == 0) return new int[]{0, 0};
        CgData d = CgData.cur();
        if (d == null) return new int[]{0, 0};
        java.util.HashSet<Integer> seasons = new java.util.HashSet<Integer>();
        int eps = 0;
        for (int i = 0; i < d.items.size(); i++) {
            JSONObject it = d.items.get(i);
            if (CgData.kind(it) != CgData.K_EPISODE) continue;
            if (!seriesKey.equals(CgData.s(it, "series_id"))) continue;
            eps++;
            seasons.add(CgData.n(it, "season"));
        }
        return new int[]{seasons.size(), eps};
    }

    // ===================================================================== search

    private void loadDetails(final JSONObject card) {
        final String id = String.valueOf(card.optInt("tmdb_id"));
        task("جارٍ جلب معلومات المسلسل...", new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("type", "series");
                b.put("tmdb_id", id);
                return CxApi.post("/admin/tmdb/details", b);
            }
        }, new Ok<JSONObject>() {
            @Override
            public void ok(JSONObject d) {
                st = new JSONObject();
                CxJ.put(st, "tmdb_id", String.valueOf(d.optInt("tmdb_id")));
                CxJ.put(st, "imdb_id", CxJ.s(d, "imdb_id"));
                CxJ.put(st, "title", CxJ.s(d, "title"));
                CxJ.put(st, "original_title", CxJ.s(d, "original_title"));
                CxJ.put(st, "description", CxJ.s(d, "description"));
                CxJ.put(st, "year", CxJ.s(d, "year"));
                CxJ.put(st, "category", CxJ.s(d, "category"));
                CxJ.put(st, "rating", CxJ.s(d, "rating_text"));
                CxJ.put(st, "poster", CxJ.s(d, "poster"));
                CxJ.put(st, "backdrop", CxJ.s(d, "backdrop"));
                CxJ.put(st, "slug", CxJ.s(d, "suggested_slug"));
                CxJ.put(st, "season_count", d.optInt("season_count"));
                CxJ.put(st, "seasons", d.optJSONArray("seasons") == null ? new JSONArray() : d.optJSONArray("seasons"));
                CxJ.put(st, "suggested_sections", d.optJSONArray("suggested_sections") == null ? new JSONArray() : d.optJSONArray("suggested_sections"));
                seriesKey = "";
                seriesItemId = "";
                JSONArray dups = d.optJSONArray("duplicates");
                for (int i = 0; dups != null && i < dups.length(); i++) {
                    JSONObject dup = dups.optJSONObject(i);
                    if (dup == null) continue;
                    CgData data = CgData.cur();
                    JSONObject item = data == null ? null : data.byId(CxJ.s(dup, "id"));
                    if (item != null && CgData.kind(item) == CgData.K_SERIES) {
                        seriesKey = CgData.seriesKey(item);
                        seriesItemId = CgData.s(item, "id");
                        break;
                    }
                }
                picked = true;
                render();
                scrollToTop();
            }
        }, new Runnable() {
            @Override
            public void run() {
                loadDetails(card);
            }
        });
    }

    // =================================================================== seasons

    private void openSeason(final int season, final boolean upload) {
        if (seriesKey.length() > 0) {
            if (upload) CxNav.seasonUpload(this, seriesKey, season);
            else CxNav.addSeasonVidbolt(this, seriesKey, season);
            return;
        }
        create(new Runnable() {
            @Override
            public void run() {
                if (upload) CxNav.seasonUpload(NxSeriesActivity.this, seriesKey, season);
                else CxNav.addSeasonVidbolt(NxSeriesActivity.this, seriesKey, season);
            }
        });
    }

    /** Creates the series record once (TMDB based, same route as the classic screen), then runs next. */
    private void create(final Runnable next) {
        final CxStages sg = CxStages.show(this, "إضافة المسلسل للمكتبة", new String[]{"إنشاء السجل", "اكتمل"});
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                sg.stage(0, CxJ.s(st, "title"));
                JSONObject b = new JSONObject();
                b.put("tmdb_id", CxJ.s(st, "tmdb_id"));
                String[] keys = {"title", "section_id", "section_name", "slug", "poster", "backdrop", "description", "category",
                        "year", "original_title", "imdb_id"};
                for (String k : keys) if (CxJ.s(st, k).length() > 0) b.put(k, CxJ.s(st, k));
                JSONObject saved = CxApi.post("/admin/series/create", b);
                JSONObject item = saved.optJSONObject("item");
                if (item == null) throw new CxApi.Failure("لم يؤكد الخادم إنشاء المسلسل. أعد المحاولة.", "NO_ITEM", null, 0);
                sg.stage(1, "");
                return item;
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject item) {
                sg.dismiss();
                CgData d = CgData.cur();
                if (d != null) d.replaceItem(item);
                seriesKey = CgData.seriesKey(item);
                seriesItemId = CxJ.s(item, "id");
                CxRecent.push(NxSeriesActivity.this, "series", CxJ.s(item, "title"), CxJ.s(item, "poster"), CxJ.s(item, "year"));
                snack("أُضيف المسلسل للمكتبة.", CxUi.GOOD);
                render();
                if (next != null) next.run();
            }

            @Override
            public void fail(CxApi.Failure f) {
                if (f.is("DUPLICATE_SERIES") || f.is("DUPLICATE_CONTENT")) {
                    sg.dismiss();
                    JSONObject ex = f.existing();
                    String id = CxJ.s(ex, "id");
                    CgData d = CgData.cur();
                    JSONObject item = d == null ? null : d.byId(id);
                    seriesKey = item != null ? CgData.seriesKey(item) : CxJ.firstNonEmpty(CxJ.s(ex, "series_id"), id);
                    seriesItemId = id;
                    snack("المسلسل موجود مسبقاً — ستُضاف الحلقات إليه.", CxUi.INFO);
                    render();
                    if (next != null && seriesKey.length() > 0) next.run();
                } else {
                    sg.fail(f.getMessage(), new Runnable() {
                        @Override
                        public void run() {
                            create(next);
                        }
                    });
                }
            }
        });
    }

    private void renderBottom() {
        bottom.removeAllViews();
        bottom.setVisibility(View.VISIBLE);
        TextView b;
        if (!picked) {
            b = NxUi.primary(this, "اختر المسلسل أولاً");
            NxUi.enable(b, false);
        } else if (seriesKey.length() == 0) {
            b = NxUi.primary(this, "إضافة المسلسل للمكتبة");
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    create(null);
                }
            });
        } else {
            b = NxUi.primary(this, "فتح صفحة المسلسل");
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CxNav.openItem(NxSeriesActivity.this, seriesItemId.length() > 0 ? seriesItemId : seriesKey);
                }
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        bottom.addView(b, lp);
    }
}
