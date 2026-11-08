package com.my.newproject;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ما الجديد؟ — the discovery center: real TMDB feeds + the Worker's cached discovery index, shown as cinematic poster cards
 * with filters, infinite scrolling and multi-select for the bulk import. Nothing here is fabricated: every card, number
 * and status comes from a Worker answer, and a failed request is shown as a failure (with a retry), never as an empty list.
 */
public class WnActivity extends CgBase {

    private String chipId = "all";
    private final WnLogic.Filters filters = new WnLogic.Filters();
    private int page = 1;
    private int totalPages = 1;
    private int token;
    private boolean loading;
    private boolean endReached;
    private boolean selectMode;
    private int shown;
    private int cols;
    private int cardW;

    private ScrollView scroll;
    private LinearLayout stats;
    private LinearLayout noteBox;
    private LinearLayout grid;
    private LinearLayout footer;
    private LinearLayout rowBox;
    private int rowFill;
    private CgUi.ChipBar chips;
    private final List<TextView> chipViews = new ArrayList<>();
    private TextView filterChip;
    private LinearLayout selBar;
    private TextView selCount;

    private final Map<String, JSONObject> cardByKey = new HashMap<>();
    private final Map<String, FrameLayout> slotByKey = new HashMap<>();
    private final LinkedHashMap<String, JSONObject> selected = new LinkedHashMap<>();
    private JSONArray genreList;

    @Override
    protected String screenTitle() {
        return "ما الجديد؟";
    }

    @Override
    protected void onBuild() {
        cols = WnUi.columns(this);
        cardW = WnUi.cardWidthPx(this, cols);
        LinearLayout c = scrollBody();
        scroll = (ScrollView) body.getChildAt(0);

        c.addView(hero());
        stats = CgUi.vbox(this);
        c.addView(stats, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        chips = new CgUi.ChipBar(this);
        for (final String[] chip : WnLogic.CHIPS) {
            final TextView t = chips.add(chip[1], chip[0].equals(chipId));
            chipViews.add(t);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pickChip(chip[0]); }
            });
        }
        filterChip = chips.add("فلترة متقدمة", false);
        filterChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openFilters(); }
        });
        c.addView(chips.scroll, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, -12, 10, -12, 4));

        noteBox = CgUi.vbox(this);
        c.addView(noteBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        grid = CgUi.vbox(this);
        CgUi.unclip(grid);
        c.addView(grid, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        footer = CgUi.vbox(this);
        c.addView(footer, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        buildSelectionBar();
        scroll.getViewTreeObserver().addOnScrollChangedListener(new ViewTreeObserver.OnScrollChangedListener() {
            @Override public void onScrollChanged() {
                View inner = scroll.getChildAt(0);
                if (inner == null || loading || endReached || !alive()) return;
                int left = inner.getBottom() - (scroll.getHeight() + scroll.getScrollY());
                if (left < CgUi.dp(WnActivity.this, 420)) loadFeed(false, false);
            }
        });

        bar.action("السجل", new Runnable() {
            @Override public void run() { startActivity(new Intent(WnActivity.this, WnHistoryActivity.class)); }
        });
        bar.action("تحديث", new Runnable() {
            @Override public void run() { loadSummary(); loadFeed(true, true); }
        });
        loadSummary();
        loadFeed(true, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (WnUi.dirty && stats != null) {
            WnUi.dirty = false;
            selected.clear();
            selectMode = false;
            updateSelectionBar();
            loadSummary();
            loadFeed(true, false);
        }
    }

    // ------------------------------------------------------------------ hero + numbers

    private View hero() {
        LinearLayout hero = CgUi.vbox(this);
        hero.setBackground(CgUi.gradient(this, CgUi.alpha(CgCfg.ACCENT_2, 0x60), CgUi.alpha(CgCfg.CYAN, 0x18), 24));
        CgUi.elevate(hero, 6);
        int p = CgUi.dp(this, 18);
        hero.setPadding(p, p, p, p);
        TextView kicker = CgUi.text(this, "اكتشف", 12.5f, CgCfg.CYAN, true);
        hero.addView(kicker);
        hero.addView(CgUi.text(this, "ما الجديد؟", 25, CgCfg.TEXT, true));
        TextView sub = CgUi.muted(this, "أفلام ومسلسلات وحلقات جديدة من TMDB. اختر عنواناً ليبحث التطبيق عن الفيديو والترجمة ويتحقق منهما قبل الإضافة.");
        sub.setPadding(0, CgUi.dp(this, 4), 0, CgUi.dp(this, 12));
        hero.addView(sub);
        LinearLayout buttons = CgUi.hbox(this);
        TextView sync = CgUi.button(this, "مزامنة الآن", 0);
        sync.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { syncNow(); }
        });
        buttons.addView(sync, CgUi.lpWeight(this, 1f, 0, 0, 5, 0));
        TextView hist = CgUi.button(this, "السجل والإعدادات", 1);
        hist.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(WnActivity.this, WnHistoryActivity.class)); }
        });
        buttons.addView(hist, CgUi.lpWeight(this, 1f, 5, 0, 0, 0));
        hero.addView(buttons);
        hero.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 2, 2, 2, 12));
        return hero;
    }

    private void loadSummary() {
        stats.removeAllViews();
        stats.addView(CgUi.muted(this, "جارٍ قراءة الملخص…"));
        CgHttp.async(this, new CgHttp.Work<JSONObject>() {
            @Override public JSONObject run() throws Exception { return WnApi.summary(); }
        }, new CgHttp.Done<JSONObject>() {
            @Override public void ok(JSONObject s) {
                if (!alive()) return;
                WnHomeCard.fillStats(WnActivity.this, stats, s, false);
            }

            @Override public void fail(String message) {
                if (!alive()) return;
                stats.removeAllViews();
                stats.addView(CgUi.errorCard(WnActivity.this, message, new Runnable() {
                    @Override public void run() { loadSummary(); }
                }), CgUi.lp(WnActivity.this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
            }
        });
    }

    // ------------------------------------------------------------------ chips + feed

    private void pickChip(String id) {
        chipId = id;
        for (int i = 0; i < WnLogic.CHIPS.length; i++) CgUi.setChipState(chipViews.get(i), WnLogic.CHIPS[i][0].equals(id));
        loadFeed(true, false);
    }

    private void refreshFilterChip() {
        boolean on = filters.active();
        filterChip.setText(on ? "فلترة متقدمة •" : "فلترة متقدمة");
        CgUi.setChipState(filterChip, on);
    }

    private void loadFeed(final boolean reset, final boolean refresh) {
        if (loading && !reset) return;
        final int my = ++token;
        if (reset) {
            page = 1;
            totalPages = 1;
            endReached = false;
            shown = 0;
            rowBox = null;
            rowFill = 0;
            grid.removeAllViews();
            noteBox.removeAllViews();
            cardByKey.clear();
            slotByKey.clear();
            footer.removeAllViews();
            footer.addView(CxUi.skeleton(this, 3));
        } else {
            footer.removeAllViews();
            LinearLayout w = CgUi.hbox(this);
            w.setGravity(Gravity.CENTER);
            w.addView(CgUi.spinner(this), new LinearLayout.LayoutParams(CgUi.dp(this, 28), CgUi.dp(this, 28)));
            footer.addView(w, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 10));
        }
        loading = true;
        final int pg = page;
        final JSONObject req = WnLogic.feedRequest(WnLogic.chip(chipId), filters, pg, refresh);
        CgHttp.async(this, new CgHttp.Work<JSONObject>() {
            @Override public JSONObject run() throws Exception { return WnApi.feed(req); }
        }, new CgHttp.Done<JSONObject>() {
            @Override public void ok(JSONObject d) {
                if (my != token || !alive()) return;
                loading = false;
                footer.removeAllViews();
                totalPages = Math.max(1, d.optInt("total_pages", 1));
                JSONArray notes = d.optJSONArray("notes");
                for (int i = 0; notes != null && i < notes.length(); i++) {
                    TextView n = CgUi.text(WnActivity.this, "! " + notes.optString(i), 12.5f, CgCfg.WARN, false);
                    n.setPadding(CgUi.dp(WnActivity.this, 4), CgUi.dp(WnActivity.this, 4), 0, CgUi.dp(WnActivity.this, 2));
                    if (pg == 1) noteBox.addView(n);
                }
                JSONArray cards = d.optJSONArray("cards");
                for (int i = 0; cards != null && i < cards.length(); i++) addCard(cards.optJSONObject(i));
                page = pg + 1;
                endReached = page > totalPages || cards == null || cards.length() == 0;
                if (shown == 0) {
                    footer.addView(CgUi.empty(WnActivity.this, "لا توجد نتائج", filters.active()
                            ? "جرّب تخفيف الفلاتر أو اختر قائمة أخرى."
                            : "لا يوجد ما يطابق هذه القائمة الآن. للقوائم المبنية على المزامنة اضغط «مزامنة الآن»."));
                } else if (endReached) {
                    TextView end = CgUi.muted(WnActivity.this, "— انتهت القائمة (" + shown + ") —");
                    end.setGravity(Gravity.CENTER);
                    footer.addView(end, CgUi.lp(WnActivity.this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 10));
                }
                refreshFilterChip();
                // a short first page may not fill the screen, so no scroll event would ever ask for the next one
                if (!endReached && scroll.getChildAt(0) != null) {
                    scroll.post(new Runnable() {
                        @Override public void run() {
                            View inner = scroll.getChildAt(0);
                            if (alive() && inner != null && !loading && !endReached && inner.getHeight() < scroll.getHeight() * 1.4f) loadFeed(false, false);
                        }
                    });
                }
            }

            @Override public void fail(String message) {
                if (my != token || !alive()) return;
                loading = false;
                footer.removeAllViews();
                footer.addView(CgUi.errorCard(WnActivity.this, message, new Runnable() {
                    @Override public void run() { loadFeed(shown == 0, false); }
                }), CgUi.lp(WnActivity.this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 6));
            }
        });
    }

    private void addCard(JSONObject c) {
        if (c == null) return;
        String key = c.optString("key");
        if (key.length() == 0 || cardByKey.containsKey(key)) return;
        if (rowBox == null || rowFill >= cols) {
            rowBox = CgUi.hbox(this);
            rowBox.setGravity(Gravity.TOP);
            CgUi.unclip(rowBox);
            for (int i = 0; i < cols; i++) {
                FrameLayout slot = new FrameLayout(this);
                CgUi.unclip(slot);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
                lp.setMargins(CgUi.dp(this, 5), 0, CgUi.dp(this, 5), 0);
                rowBox.addView(slot, lp);
            }
            grid.addView(rowBox, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
            rowFill = 0;
        }
        FrameLayout slot = (FrameLayout) rowBox.getChildAt(rowFill++);
        cardByKey.put(key, c);
        slotByKey.put(key, slot);
        shown++;
        View v = makeCard(c);
        slot.addView(v);
        WnUi.reveal(v, shown);
    }

    private View makeCard(final JSONObject c) {
        final String key = c.optString("key");
        return WnUi.posterCard(this, c, cardW, selected.containsKey(key), selectMode, new Runnable() {
            @Override public void run() {
                if ("collection".equals(c.optString("type"))) WnCollectionActivity.open(WnActivity.this, c.optLong("tmdb_id"), c.optString("title"));
                else if (selectMode) toggle(c); else WnDetailActivity.open(WnActivity.this, c);
            }
        }, new Runnable() {
            @Override public void run() { if (!"collection".equals(c.optString("type"))) toggle(c); }
        });
    }

    private void rerender(String key) {
        FrameLayout slot = slotByKey.get(key);
        JSONObject c = cardByKey.get(key);
        if (slot == null || c == null) return;
        slot.removeAllViews();
        slot.addView(makeCard(c));
    }

    // ------------------------------------------------------------------ selection + bulk

    private void toggle(JSONObject c) {
        String key = c.optString("key");
        boolean before = selectMode;
        if (selected.containsKey(key)) selected.remove(key); else selected.put(key, c);
        selectMode = !selected.isEmpty();
        if (before != selectMode) {
            for (String k : new ArrayList<>(slotByKey.keySet())) rerender(k);
        } else {
            rerender(key);
        }
        updateSelectionBar();
    }

    private void buildSelectionBar() {
        selBar = CgUi.hbox(this);
        selBar.setBackground(CgUi.bg(this, CgCfg.CARD3, 18, CgCfg.ACCENT));
        CgUi.elevate(selBar, 10);
        selBar.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 8), CgUi.dp(this, 8), CgUi.dp(this, 8));
        selCount = CgUi.text(this, "", 14, CgCfg.TEXT, true);
        selBar.addView(selCount, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        TextView clear = CgUi.smallButton(this, "إلغاء", 3);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                selected.clear();
                selectMode = false;
                for (String k : new ArrayList<>(slotByKey.keySet())) rerender(k);
                updateSelectionBar();
            }
        });
        selBar.addView(clear, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        TextView go = CgUi.smallButton(this, "استيراد مجمّع", 0);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startBulk(); }
        });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        gl.setMarginStart(CgUi.dp(this, 6));
        selBar.addView(go, gl);
        selBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP, Gravity.BOTTOM);
        int m = CgUi.dp(this, 12);
        lp.setMargins(m, 0, m, m);
        frame.addView(selBar, lp);
    }

    private void updateSelectionBar() {
        selBar.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
        selCount.setText(selected.size() + " محدد");
    }

    private void startBulk() {
        JSONArray arr = new JSONArray();
        int skipped = 0;
        for (JSONObject c : selected.values()) {
            if (c.optBoolean("imported", false)) { skipped++; continue; }
            arr.put(c);
        }
        if (arr.length() == 0) {
            CgUi.toast(this, "كل ما حددته موجود بالفعل في المكتبة.");
            return;
        }
        if (skipped > 0) CgUi.toast(this, "استُبعد " + skipped + " عنصر موجود في المكتبة.");
        Intent in = new Intent(this, WnBulkActivity.class);
        in.putExtra(WnBulkActivity.EXTRA_CARDS, arr.toString());
        startActivity(in);
    }

    // ------------------------------------------------------------------ sync

    private void syncNow() {
        showLoading("جارٍ مزامنة المحتوى الجديد من TMDB…");
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                String err = null;
                JSONObject run = null;
                try {
                    JSONObject d = WnApi.sync(4);
                    run = d.optJSONObject("run");
                    final String runId = run == null ? "" : run.optString("id");
                    for (int i = 0; i < 6; i++) {
                        final int round = i + 1;
                        runOnUiThread(new Runnable() {
                            @Override public void run() { if (alive()) loadingMessage("فحص مصادر الفيديو والترجمة… (الدفعة " + round + ")"); }
                        });
                        JSONObject e = WnApi.enrich(4, runId);
                        if (e.optInt("remaining", 0) == 0 || e.optInt("enriched", 0) == 0) break;
                    }
                } catch (CxApi.Failure f) {
                    err = f.getMessage();
                }
                final String fe = err;
                final JSONObject fr = run;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive()) return;
                        hideLoading();
                        if (fe != null && fr == null) {
                            CgUi.info(WnActivity.this, "تعذرت المزامنة", fe);
                            return;
                        }
                        String msg = fr == null ? "" : "اكتشاف " + fr.optInt("discovered") + " · جديد " + fr.optInt("new_items")
                                + " · فيديو متاح " + fr.optInt("videos_resolved") + " · ترجمات " + fr.optInt("subtitles_available")
                                + (fr.optInt("failed") > 0 ? " · أخطاء " + fr.optInt("failed") : "");
                        if (fe != null) msg += "\n(تنبيه: " + fe + ")";
                        CgUi.info(WnActivity.this, "اكتملت المزامنة", msg);
                        loadSummary();
                        loadFeed(true, false);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ advanced filters

    private void ensureGenres(final Runnable then) {
        if (genreList != null) { then.run(); return; }
        showLoading("جارٍ تحميل التصنيفات…");
        CgHttp.async(this, new CgHttp.Work<JSONObject>() {
            @Override public JSONObject run() throws Exception { return WnApi.genres(); }
        }, new CgHttp.Done<JSONObject>() {
            @Override public void ok(JSONObject d) {
                hideLoading();
                JSONArray merged = new JSONArray();
                java.util.Set<Integer> seen = new java.util.HashSet<>();
                String[] kinds = {"movie", "series"};
                for (String k : kinds) {
                    JSONArray a = d.optJSONArray(k);
                    for (int i = 0; a != null && i < a.length(); i++) {
                        JSONObject g = a.optJSONObject(i);
                        if (g != null && seen.add(g.optInt("id"))) merged.put(g);
                    }
                }
                genreList = merged;
                then.run();
            }

            @Override public void fail(String message) {
                hideLoading();
                CgUi.info(WnActivity.this, "تعذر تحميل التصنيفات", message);
            }
        });
    }

    private static final String[][] LANGS = {{"الكل", ""}, {"العربية", "ar"}, {"English", "en"}, {"Türkçe", "tr"}, {"한국어", "ko"}, {"日本語", "ja"}, {"हिन्दी", "hi"}, {"Français", "fr"}, {"Español", "es"}};
    private static final String[][] COUNTRIES = {{"الكل", ""}, {"السعودية", "SA"}, {"مصر", "EG"}, {"الإمارات", "AE"}, {"الولايات المتحدة", "US"}, {"بريطانيا", "GB"}, {"تركيا", "TR"}, {"كوريا الجنوبية", "KR"}, {"اليابان", "JP"}, {"الهند", "IN"}};
    private static final String[][] RATINGS = {{"الكل", ""}, {"5+", "5"}, {"6+", "6"}, {"7+", "7"}, {"8+", "8"}};
    private static final String[][] SORTS = {{"الأكثر شعبية", "popularity"}, {"الأعلى تقييماً", "rating"}, {"الأحدث", "release"}};

    private static String labelOf(String[][] table, String value) {
        for (String[] r : table) if (r[1].equals(value)) return r[0];
        return table[0][0];
    }

    private void openFilters() {
        ensureGenres(new Runnable() {
            @Override public void run() { showFilterDialog(); }
        });
    }

    private void pickFrom(final String title, final String[][] table, final TextView button, final String[] holder) {
        CharSequence[] labels = new CharSequence[table.length];
        for (int i = 0; i < table.length; i++) labels[i] = table[i][0];
        CgUi.choose(this, title, labels, new CgUi.IntCb() {
            @Override public void run(int index) {
                holder[0] = table[index][1];
                button.setText(table[index][0]);
            }
        });
    }

    private void showFilterDialog() {
        final String[] genre = {filters.genre};
        final String[] lang = {filters.language};
        final String[] country = {filters.country};
        final String[] rating = {filters.minRating};
        final String[] sort = {filters.sort.length() == 0 ? "popularity" : filters.sort};
        LinearLayout box = CgUi.vbox(this);
        box.setPadding(CgUi.dp(this, 18), CgUi.dp(this, 8), CgUi.dp(this, 18), 0);

        String genreName = "الكل";
        for (int i = 0; genreList != null && i < genreList.length(); i++) {
            if (String.valueOf(genreList.optJSONObject(i).optInt("id")).equals(genre[0])) genreName = genreList.optJSONObject(i).optString("name");
        }
        final TextView genreBtn = CgUi.smallButton(this, genreName, 1);
        genreBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int n = genreList == null ? 0 : genreList.length();
                CharSequence[] labels = new CharSequence[n + 1];
                labels[0] = "الكل";
                for (int i = 0; i < n; i++) labels[i + 1] = genreList.optJSONObject(i).optString("name");
                CgUi.choose(WnActivity.this, "التصنيف", labels, new CgUi.IntCb() {
                    @Override public void run(int index) {
                        genre[0] = index == 0 ? "" : String.valueOf(genreList.optJSONObject(index - 1).optInt("id"));
                        genreBtn.setText(index == 0 ? "الكل" : genreList.optJSONObject(index - 1).optString("name"));
                    }
                });
            }
        });
        box.addView(CgUi.labeled(this, "التصنيف", genreBtn));

        final TextView langBtn = CgUi.smallButton(this, labelOf(LANGS, lang[0]), 1);
        langBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFrom("اللغة الأصلية", LANGS, langBtn, lang); }
        });
        box.addView(CgUi.labeled(this, "اللغة الأصلية", langBtn));

        final TextView countryBtn = CgUi.smallButton(this, labelOf(COUNTRIES, country[0]), 1);
        countryBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFrom("بلد الإنتاج", COUNTRIES, countryBtn, country); }
        });
        box.addView(CgUi.labeled(this, "بلد الإنتاج", countryBtn));

        final EditText year = CgUi.numberField(this, "مثال: 2025");
        year.setText(filters.year);
        box.addView(CgUi.labeled(this, "السنة", year));

        final TextView ratingBtn = CgUi.smallButton(this, labelOf(RATINGS, rating[0]), 1);
        ratingBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFrom("أدنى تقييم", RATINGS, ratingBtn, rating); }
        });
        box.addView(CgUi.labeled(this, "التقييم", ratingBtn));

        final TextView sortBtn = CgUi.smallButton(this, labelOf(SORTS, sort[0]), 1);
        sortBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFrom("الترتيب", SORTS, sortBtn, sort); }
        });
        box.addView(CgUi.labeled(this, "الترتيب", sortBtn));

        final EditText from = CgUi.field(this, "YYYY-MM-DD", false);
        from.setText(filters.dateFrom);
        CgUi.ltr(from);
        box.addView(CgUi.labeled(this, "تاريخ الإصدار من", from));
        final EditText to = CgUi.field(this, "YYYY-MM-DD", false);
        to.setText(filters.dateTo);
        CgUi.ltr(to);
        box.addView(CgUi.labeled(this, "تاريخ الإصدار إلى", to));

        ScrollView wrap = new ScrollView(this);
        wrap.addView(box);
        CgUi.dialog(this).setTitle("فلترة متقدمة").setView(wrap)
                .setNegativeButton("إلغاء", null)
                .setNeutralButton("مسح الفلاتر", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        filters.clear();
                        filters.sort = "";
                        refreshFilterChip();
                        loadFeed(true, false);
                    }
                })
                .setPositiveButton("تطبيق", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String f = from.getText().toString().trim();
                        String t = to.getText().toString().trim();
                        String y = year.getText().toString().trim();
                        String re = "\\d{4}-\\d{2}-\\d{2}";
                        if ((f.length() > 0 && !f.matches(re)) || (t.length() > 0 && !t.matches(re))) {
                            CgUi.toast(WnActivity.this, "صيغة التاريخ YYYY-MM-DD.");
                            return;
                        }
                        if (y.length() > 0 && !y.matches("(19|20)\\d{2}")) {
                            CgUi.toast(WnActivity.this, "السنة غير صالحة.");
                            return;
                        }
                        filters.genre = genre[0];
                        filters.language = lang[0];
                        filters.country = country[0];
                        filters.minRating = rating[0];
                        filters.sort = "popularity".equals(sort[0]) ? "" : sort[0];
                        filters.year = y;
                        filters.dateFrom = f;
                        filters.dateTo = t;
                        refreshFilterChip();
                        loadFeed(true, false);
                    }
                }).show();
    }
}
