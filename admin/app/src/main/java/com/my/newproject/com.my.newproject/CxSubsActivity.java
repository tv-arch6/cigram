package com.my.newproject;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Arabic subtitles in one press. Looks the film / episode up in SubDL (main), SubSource (backup) and OpenSubtitles.com
 * (extra) through the Worker, picks the best Arabic SRT (language, rating, downloads, release match), unzips and fixes the
 * encoding, then shows it as text, uploads it next to the video in R2 and links it to the item (app + website).
 *
 * Opened two ways:
 *  - with "item_id": a film / episode / series of the library (the result is linked to it automatically);
 *  - from the film / episode editor (CxSubsActivity.addButton): the result link is returned into the subtitle field.
 */
public class CxSubsActivity extends CxBase {

    static final int REQ = 7711;

    /** Supplies the target of the editor that opened this screen (read when the button is pressed). */
    interface Provider {
        JSONObject target();
    }

    private static CxUi.Field pendingField;

    private JSONObject target = new JSONObject();
    private String itemId = "";
    private boolean returnMode;
    private boolean isSeries;
    private CxUi.Field query;
    private CxUi.Field seasonF;
    private CxUi.Field episodeF;
    private String type = "movie";
    private LinearLayout resultBox;
    private TextView statusLine;

    @Override
    protected String screenTitle() {
        return "الترجمة العربية";
    }

    // ------------------------------------------------------------------ entry points

    /** Opens the screen for a library item (film, episode or series header). */
    static void open(Activity a, String itemId) {
        Intent in = new Intent(a, CxSubsActivity.class);
        in.putExtra("item_id", itemId);
        a.startActivity(in);
    }

    /** Adds «جلب العربية تلقائياً» next to the subtitle field of an editor. The URL comes back into that field. */
    static void addButton(final Activity a, LinearLayout row, final CxUi.Field field, final Provider provider) {
        TextView b = CgUi.smallButton(a, "🔤  جلب العربية تلقائياً", 0);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JSONObject t = provider.target();
                if (t == null || (CxJ.s(t, "title").length() == 0 && CxJ.s(t, "tmdb_id").length() == 0 && CxJ.s(t, "imdb_id").length() == 0)) {
                    CgUi.info(a, "حدد العنوان أولاً", "أدخل اسم الفيلم أو المسلسل (أو معرّف TMDB / IMDb) ثم اضغط الزر.");
                    return;
                }
                pendingField = field;
                Intent in = new Intent(a, CxSubsActivity.class);
                in.putExtra("target", t.toString());
                in.putExtra("return", true);
                a.startActivityForResult(in, REQ);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        lp.setMargins(CgUi.dp(a, 8), 0, 0, 0);
        row.addView(b, lp);
    }

    /** Call from the editor's onActivityResult. True when the result was ours. */
    static boolean onResult(Activity a, int req, int res, Intent data) {
        if (req != REQ) return false;
        if (res == Activity.RESULT_OK && data != null && pendingField != null) {
            String url = data.getStringExtra("subtitle_url");
            if (url != null && url.length() > 0) {
                pendingField.input.setText(url);
                CgUi.toast(a, "وُضع رابط الترجمة العربية في الحقل.");
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ screen

    @Override
    protected void onBuild() {
        initCx(null);
        returnMode = getIntent().getBooleanExtra("return", false);
        itemId = getIntent().getStringExtra("item_id") == null ? "" : getIntent().getStringExtra("item_id");
        String t = getIntent().getStringExtra("target");
        if (t != null) target = CxJ.parse(t);
        if (itemId.length() > 0) {
            ensureData(new Runnable() {
                @Override
                public void run() {
                    build();
                }
            });
        } else {
            build();
        }
    }

    private void build() {
        CgData d = CgData.cur();
        JSONObject item = itemId.length() > 0 && d != null ? d.byId(itemId) : null;
        isSeries = item != null && CgData.kind(item) == CgData.K_SERIES;
        page.removeAllViews();
        bottom.setVisibility(View.GONE);

        statusLine = CgUi.muted(this, "جارٍ فحص مصادر الترجمة...");
        page.addView(statusLine, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 0, 4, 8));
        loadStatus();

        // ---- what we look for
        CxUi.Section sec = new CxUi.Section(this, item != null ? "العنوان" : "ما الذي تريد ترجمته؟",
                item != null ? "يؤخذ كل شيء من المكتبة: المعرّفات والموسم والحلقة والجودة." : "اسم الفيلم أو المسلسل، أو رابط / معرّف IMDb أو TMDB.", false, true);
        if (item != null) {
            String line = CgData.s(item, "title");
            if (CgData.kind(item) == CgData.K_EPISODE) {
                JSONObject h = d.byId(CgData.s(item, "series_id"));
                line = (h != null ? CgData.s(h, "title") : line) + "  ·  " + CxNames.code(CgData.n(item, "season"), CgData.n(item, "episode"));
            }
            sec.add(CgUi.text(this, line, 16, CgCfg.TEXT, true));
            String sub = CgData.s(item, "subtitle");
            sec.add(CgUi.muted(this, sub.length() > 0 ? "لها ترجمة حالياً — سيُستبدل الرابط بالجديد." : "بدون ترجمة حالياً."));
        } else {
            query = new CxUi.Field(this, "الاسم أو IMDb أو TMDB", "Breaking Bad  /  tt0903747  /  themoviedb.org/tv/1396", CxUi.T_LTR, false);
            query.set(CxJ.firstNonEmpty(CxJ.s(target, "imdb_id"), CxJ.firstNonEmpty(CxJ.s(target, "tmdb_id").length() > 0 ? "https://themoviedb.org/" + (CxJ.s(target, "type").equals("tv") ? "tv/" : "movie/") + CxJ.s(target, "tmdb_id") : "", CxJ.s(target, "title"))));
            sec.add(query.view);
            type = "tv".equals(CxJ.s(target, "type")) ? "tv" : "movie";
            final LinearLayout chips = CgUi.hbox(this);
            final TextView[] cs = new TextView[2];
            final String[] labels = {"فيلم", "مسلسل"};
            for (int i = 0; i < 2; i++) {
                final int idx = i;
                cs[i] = CxUi.chip(this, labels[i], (i == 1) == "tv".equals(type), new Runnable() {
                    @Override
                    public void run() {
                        type = idx == 1 ? "tv" : "movie";
                        CgUi.setChipState(cs[0], idx == 0);
                        CgUi.setChipState(cs[1], idx == 1);
                        seasonF.view.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
                        episodeF.view.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
                lp.setMargins(0, CgUi.dp(this, 4), CgUi.dp(this, 8), CgUi.dp(this, 8));
                chips.addView(cs[i], lp);
            }
            sec.add(chips);
            seasonF = new CxUi.Field(this, "رقم الموسم", "1", CxUi.T_NUMBER, false);
            episodeF = new CxUi.Field(this, "رقم الحلقة", "1", CxUi.T_NUMBER, false);
            if (CxJ.n(target, "season") > 0) seasonF.set(String.valueOf(CxJ.n(target, "season")));
            if (CxJ.n(target, "episode") > 0) episodeF.set(String.valueOf(CxJ.n(target, "episode")));
            sec.add(seasonF.view);
            sec.add(episodeF.view);
            boolean tv = "tv".equals(type);
            seasonF.view.setVisibility(tv ? View.VISIBLE : View.GONE);
            episodeF.view.setVisibility(tv ? View.VISIBLE : View.GONE);
        }
        page.addView(sec);

        // ---- actions
        TextView auto = CgUi.button(this, returnMode ? "🔤  جلب ورفع الترجمة العربية (ضغطة واحدة)" : itemId.length() > 0 ? "🔤  جلب وربط الترجمة العربية (ضغطة واحدة)" : "🔤  جلب ورفع الترجمة العربية", 0);
        auto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runFetch(null);
            }
        });
        page.addView(auto, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 8));

        LinearLayout row = CgUi.hbox(this);
        TextView text = CgUi.button(this, "📄  عرض كنص", 1);
        text.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runText(null);
            }
        });
        TextView list = CgUi.button(this, "🔍  كل النتائج", 1);
        list.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runSearch();
            }
        });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        l1.setMargins(0, 0, CgUi.dp(this, 4), 0);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        l2.setMargins(CgUi.dp(this, 4), 0, 0, 0);
        row.addView(text, l1);
        row.addView(list, l2);
        page.addView(row, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));

        if (itemId.length() > 0 && item != null && (isSeries || CgData.kind(item) == CgData.K_EPISODE)) {
            TextView season = CgUi.button(this, "🗂  كل حلقات هذا الموسم (التي بلا ترجمة)", 1);
            season.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    runSeason();
                }
            });
            page.addView(season, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
        resultBox = CgUi.vbox(this);
        page.addView(resultBox, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
    }

    private void loadStatus() {
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                return CxApi.get("/admin/subs/status");
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                boolean a = r.optBoolean("subdl"), b = r.optBoolean("subsource"), c = r.optBoolean("opensubtitles");
                if (!a && !b && !c) {
                    statusLine.setText("⚠ لم يُضبط أي مصدر ترجمة بعد. أضف SUBDL_API_KEY في Cloudflare ← Settings ← Variables and Secrets.");
                    statusLine.setTextColor(CgCfg.WARN);
                } else {
                    statusLine.setText("المصادر:  SubDL " + (a ? "✔" : "—") + "   SubSource " + (b ? "✔" : "—") + "   OpenSubtitles " + (c ? "✔" : "—"));
                    statusLine.setTextColor(CgCfg.MUTED);
                }
            }

            @Override
            public void fail(CxApi.Failure f) {
                statusLine.setText("تعذر فحص المصادر الآن.");
            }
        });
    }

    // ------------------------------------------------------------------ requests

    private JSONObject body() throws Exception {
        JSONObject b = new JSONObject();
        if (itemId.length() > 0) {
            b.put("item_id", itemId);
            return b;
        }
        if (query != null) {
            b.put("query", query.text());
            b.put("type", type);
            b.put("season", seasonF == null ? 0 : CxJ.n(CxJ.put(new JSONObject(), "n", seasonF.text()), "n"));
            b.put("episode", episodeF == null ? 0 : CxJ.n(CxJ.put(new JSONObject(), "n", episodeF.text()), "n"));
            if (CxJ.s(target, "slug").length() > 0) b.put("slug", CxJ.s(target, "slug"));
            if (CxJ.s(target, "year").length() > 0) b.put("year", CxJ.s(target, "year"));
            if (CxJ.s(target, "release").length() > 0) b.put("release", CxJ.s(target, "release"));
            // exact ids from the editor beat the typed text
            if (CxJ.s(target, "tmdb_id").length() > 0 && !query.text().matches(".*(tt\\d{5,}|themoviedb).*")) b.put("tmdb_id", CxJ.s(target, "tmdb_id"));
            if (CxJ.s(target, "imdb_id").length() > 0 && !query.text().matches(".*tt\\d{5,}.*")) b.put("imdb_id", CxJ.s(target, "imdb_id"));
        }
        return b;
    }

    private boolean valid() {
        if (itemId.length() == 0 && (query == null || query.text().trim().length() == 0)) {
            if (query != null) query.error("اكتب الاسم أو معرّف IMDb / TMDB.");
            return false;
        }
        return true;
    }

    private void runFetch(final JSONObject pick) {
        if (!valid()) return;
        showLoading("جارٍ البحث وتحميل أفضل ترجمة عربية...");
        final boolean attach = itemId.length() > 0;
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = body();
                b.put("mode", "upload");
                if (attach) b.put("attach", true);
                if (pick != null) b.put("pick", pick);
                return CxApi.post("/admin/subs/fetch", b);
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                hideLoading();
                showDone(r);
                if (returnMode) {
                    Intent out = new Intent();
                    out.putExtra("subtitle_url", CxJ.s(r, "url"));
                    setResult(RESULT_OK, out);
                }
                CgData.load(CxSubsActivity.this, new CgHttp.Done<CgData>() {
                    @Override
                    public void ok(CgData d) { }

                    @Override
                    public void fail(String message) { }
                });
            }

            @Override
            public void fail(CxApi.Failure f) {
                hideLoading();
                showError(f.getMessage());
            }
        });
    }

    private void showDone(final JSONObject r) {
        resultBox.removeAllViews();
        LinearLayout c = CgUi.card(this);
        c.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.GOOD, 0x14), 16, CgCfg.GOOD));
        c.addView(CgUi.text(this, r.optBoolean("attached") ? "✔ جُلبت الترجمة وربطت بالعنصر" : "✔ جُلبت الترجمة ورُفعت", 16, CgCfg.GOOD, true));
        LinearLayout badges = CgUi.hbox(this);
        badges.addView(CgUi.badge(this, sourceName(CxJ.s(r, "source")), CgCfg.ACCENT));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        bl.setMargins(CgUi.dp(this, 6), 0, 0, 0);
        badges.addView(CgUi.badge(this, r.optInt("cues") + " سطراً", CgCfg.GRAY), bl);
        badges.addView(CgUi.badge(this, "تطابق " + r.optInt("score") + "%", CgCfg.GOOD), bl);
        c.addView(badges, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 0, 6));
        if (CxJ.s(r, "release").length() > 0) c.addView(CgUi.muted(this, "الإصدار: " + CxJ.s(r, "release")));
        final String url = CxJ.s(r, "url");
        if (url.length() > 0) {
            TextView u = CgUi.text(this, url, 12, CgCfg.TEXT, false);
            CgUi.ltr(u);
            u.setTextIsSelectable(true);
            c.addView(u, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 8));
            LinearLayout row = CgUi.hbox(this);
            TextView copy = CgUi.smallButton(this, "نسخ الرابط", 1);
            copy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("subtitle", url));
                    snack("نُسخ الرابط.", CxUi.GOOD);
                }
            });
            row.addView(copy);
            TextView show = CgUi.smallButton(this, "عرض النص", 1);
            show.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    runText(null);
                }
            });
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            sl.setMargins(CgUi.dp(this, 8), 0, 0, 0);
            row.addView(show, sl);
            c.addView(row);
        }
        if (r.optString("undo_id").length() > 0) showUndo("ربط الترجمة", r.optString("undo_id"), null);
        resultBox.addView(c);
        scrollTo(resultBox);
    }

    private void showError(String message) {
        resultBox.removeAllViews();
        resultBox.addView(CxUi.notice(this, CxUi.ERROR, message));
        scrollTo(resultBox);
    }

    private static String sourceName(String s) {
        if ("subdl".equals(s)) return "SubDL";
        if ("subsource".equals(s)) return "SubSource";
        if ("opensubtitles".equals(s)) return "OpenSubtitles";
        return s;
    }

    private void runText(final JSONObject pick) {
        if (!valid()) return;
        showLoading("جارٍ جلب نص الترجمة...");
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = body();
                b.put("mode", "text");
                if (pick != null) b.put("pick", pick);
                return CxApi.post("/admin/subs/fetch", b);
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                hideLoading();
                final String srt = CxJ.s(r, "srt");
                ScrollView sv = new ScrollView(CxSubsActivity.this);
                TextView t = CgUi.text(CxSubsActivity.this, srt.length() > 40000 ? srt.substring(0, 40000) + "\n…" : srt, 13, CgCfg.TEXT, false);
                t.setTypeface(Typeface.MONOSPACE);
                t.setTextIsSelectable(true);
                t.setLineSpacing(0, 1.2f);
                t.setPadding(CgUi.dp(CxSubsActivity.this, 18), CgUi.dp(CxSubsActivity.this, 6), CgUi.dp(CxSubsActivity.this, 18), CgUi.dp(CxSubsActivity.this, 6));
                sv.addView(t);
                CgUi.dialog(CxSubsActivity.this).setTitle(sourceName(CxJ.s(r, "source")) + " · " + r.optInt("cues") + " سطراً").setView(sv)
                        .setNegativeButton("إغلاق", null)
                        .setPositiveButton("نسخ النص", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("srt", srt));
                                snack("نُسخ النص.", CxUi.GOOD);
                            }
                        }).show();
            }

            @Override
            public void fail(CxApi.Failure f) {
                hideLoading();
                showError(f.getMessage());
            }
        });
    }

    private void runSearch() {
        if (!valid()) return;
        showLoading("جارٍ البحث في كل المصادر...");
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = body();
                b.put("all", true);
                return CxApi.post("/admin/subs/search", b);
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                hideLoading();
                resultBox.removeAllViews();
                JSONArray rows = r.optJSONArray("results");
                if (rows == null || rows.length() == 0) {
                    showError("لم أجد ترجمة عربية لهذا العنوان. جرّب معرّف IMDb أو TMDB، أو تأكد من رقم الموسم والحلقة.");
                    return;
                }
                resultBox.addView(CgUi.sectionTitle(CxSubsActivity.this, rows.length() + " ترجمة عربية"));
                for (int i = 0; i < rows.length(); i++) {
                    final JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    resultBox.addView(resultRow(row, i == 0));
                }
                scrollTo(resultBox);
            }

            @Override
            public void fail(CxApi.Failure f) {
                hideLoading();
                showError(f.getMessage());
            }
        });
    }

    private View resultRow(final JSONObject row, boolean best) {
        LinearLayout box = CgUi.vbox(this);
        box.setBackground(CgUi.bg(this, CgCfg.CARD, 14, best ? CgCfg.GOOD : CgCfg.STROKE));
        box.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 10), CgUi.dp(this, 12), CgUi.dp(this, 10));
        box.setClickable(true);
        CgUi.pressScale(box);
        TextView name = CgUi.text(this, CxJ.firstNonEmpty(CxJ.s(row, "release"), CxJ.s(row, "name")), 13.5f, CgCfg.TEXT, true);
        CgUi.ltr(name);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(name);
        LinearLayout b = CgUi.hbox(this);
        b.addView(CgUi.badge(this, sourceName(CxJ.s(row, "source")), CgCfg.ACCENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        lp.setMargins(CgUi.dp(this, 6), 0, 0, 0);
        b.addView(CgUi.badge(this, row.optInt("score") + "%", best ? CgCfg.GOOD : CgCfg.GRAY), lp);
        if (row.optInt("downloads") > 0) b.addView(CgUi.badge(this, "⬇ " + row.optInt("downloads"), CgCfg.GRAY), lp);
        if (row.optBoolean("hi")) b.addView(CgUi.badge(this, "للصم", CgCfg.WARN), lp);
        if (row.optBoolean("full_season")) b.addView(CgUi.badge(this, "حزمة موسم", CgCfg.WARN), lp);
        if (best) b.addView(CgUi.badge(this, "الأفضل", CgCfg.GOOD), lp);
        box.addView(b, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 6, 0, 0));
        box.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUi.choose(CxSubsActivity.this, "هذه الترجمة", new CharSequence[]{itemId.length() > 0 ? "رفع وربط بالعنصر" : "رفع وأخذ الرابط", "عرض نصها"}, new CgUi.IntCb() {
                    @Override
                    public void run(int index) {
                        if (index == 0) runFetch(row);
                        else runText(row);
                    }
                });
            }
        });
        return box;
    }

    // ------------------------------------------------------------------ a whole season

    private void runSeason() {
        final CgData d = CgData.cur();
        JSONObject item = d == null ? null : d.byId(itemId);
        if (item == null) return;
        final String seriesKey;
        final int season;
        if (CgData.kind(item) == CgData.K_EPISODE) {
            seriesKey = CgData.s(item, "series_id");
            season = CgData.n(item, "season");
        } else {
            seriesKey = CgData.seriesKey(item);
            season = 1;
        }
        final String title = CgData.kind(item) == CgData.K_SERIES ? "" : "";
        if (isSeries) {
            CgUi.input(this, "أي موسم؟", "رقم الموسم", "1", false, new CgUi.Callback<String>() {
                @Override
                public void run(String v) {
                    int s = 0;
                    try { s = Integer.parseInt(v.trim()); } catch (Exception ignored) { }
                    if (s > 0) seasonLoop(seriesKey, s);
                }
            });
            return;
        }
        seasonLoop(seriesKey, season);
    }

    private void seasonLoop(final String seriesKey, final int season) {
        resultBox.removeAllViews();
        final LinearLayout box = CgUi.card(this);
        final TextView head = CgUi.text(this, "الموسم " + season + ": جارٍ جلب الترجمات...", 15, CgCfg.TEXT, true);
        box.addView(head);
        final LinearLayout list = CgUi.vbox(this);
        box.addView(list, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        resultBox.addView(box);
        step(seriesKey, season, 0, 0, 0, head, list);
    }

    private void step(final String seriesKey, final int season, final int offset, final int okCount, final int badCount, final TextView head, final LinearLayout list) {
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("series_id", seriesKey);
                b.put("season", season);
                b.put("offset", offset);
                b.put("batch", 3);
                return CxApi.post("/admin/subs/season", b);
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                JSONArray rows = r.optJSONArray("results");
                int good = okCount, bad = badCount;
                for (int i = 0; rows != null && i < rows.length(); i++) {
                    JSONObject o = rows.optJSONObject(i);
                    if (o == null) continue;
                    boolean okRow = o.optBoolean("ok");
                    if (okRow) good++; else bad++;
                    TextView t = CgUi.text(CxSubsActivity.this, (okRow ? "✔ " : "✖ ") + CxNames.code(season, o.optInt("episode"))
                            + (okRow ? "  ·  " + sourceName(CxJ.s(o, "source")) + "  ·  " + o.optInt("cues") + " سطراً" : "  ·  " + CxJ.s(o, "error")), 12.5f, okRow ? CgCfg.TEXT : CgCfg.WARN, false);
                    list.addView(t);
                }
                int total = r.optInt("total");
                if (total == 0) {
                    head.setText("كل حلقات الموسم " + season + " لها ترجمة بالفعل.");
                    return;
                }
                if (!r.optBoolean("done")) {
                    head.setText("الموسم " + season + ": متبقي " + r.optInt("remaining") + " من " + total + " حلقة...");
                    step(seriesKey, season, r.optInt("next_offset"), good, bad, head, list);
                } else {
                    head.setText("الموسم " + season + ": رُبطت " + good + " ترجمة" + (bad > 0 ? "، وتعذرت " + bad : "") + ".");
                    head.setTextColor(bad == 0 ? CgCfg.GOOD : CgCfg.WARN);
                    CgData.load(CxSubsActivity.this, new CgHttp.Done<CgData>() {
                        @Override
                        public void ok(CgData d) { }

                        @Override
                        public void fail(String message) { }
                    });
                }
            }

            @Override
            public void fail(CxApi.Failure f) {
                head.setText("توقف: " + f.getMessage());
                head.setTextColor(CgCfg.BAD);
            }
        });
    }
}
