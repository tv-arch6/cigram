package com.my.newproject;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One movie / series from «ما الجديد؟»: the real TMDB details, an automatic video + subtitle search with a visible, honest
 * validation level for each, category / section choice, a pre-import validation report, and the «إضافة إلى Cigram» button.
 * Series: pick seasons and episodes, each episode is resolved on its own and can never be attached to another episode's URL.
 */
public class WnDetailActivity extends CgBase {

    static final String EXTRA_CARD = "card";
    private static final int REQ_SUBTITLE_FILE = 4412;

    static void open(Activity a, JSONObject card) {
        Intent in = new Intent(a, WnDetailActivity.class);
        in.putExtra(EXTRA_CARD, card.toString());
        a.startActivity(in);
    }

    private JSONObject card;
    private JSONObject det;
    private String type = "movie";
    private long tmdb;
    private boolean series;

    private LinearLayout root;
    private LinearLayout videoBox;
    private LinearLayout subBox;
    private LinearLayout catBox;
    private LinearLayout seasonBox;
    private LinearLayout reportBox;
    private LinearLayout actionBox;
    private TextView importBtn;

    // movie
    private JSONObject movieTarget;
    private int movieSrc = -1;
    private String limitations = "";
    private String customUrl = "";
    private JSONArray subResults;
    private String subLang = "auto";
    private String subToken = "";
    private JSONObject subInfo;
    private String subNote = "";

    // placement + options
    private String sectionId = "";
    private String newSection = "";
    private String newSectionType = "";
    private final LinkedHashSet<String> cats = new LinkedHashSet<>();
    private boolean catsTouched;
    private boolean ack;
    private boolean notify = true;
    private boolean subRequired;
    private String quality = "";

    // series
    private final TreeMap<Integer, JSONObject> seasonData = new TreeMap<>();
    private final TreeMap<Integer, TreeSet<Integer>> picked = new TreeMap<>();
    private final HashMap<String, JSONObject> epTarget = new HashMap<>();
    private final HashMap<String, String> epSub = new HashMap<>();
    private final TreeSet<Integer> expanded = new TreeSet<>();
    private String seriesBusy = "";

    private JSONObject report;
    private boolean validating;
    private boolean importing;
    private String importNonce;

    @Override
    protected String screenTitle() {
        String raw = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_CARD);
        JSONObject c = CxJ.parse(raw);
        String t = c.optString("title");
        return t.length() == 0 ? "التفاصيل" : t;
    }

    @Override
    protected void onBuild() {
        card = CxJ.parse(getIntent().getStringExtra(EXTRA_CARD));
        type = "series".equals(card.optString("type")) ? "series" : "movie";
        series = "series".equals(type);
        tmdb = card.optLong("tmdb_id");
        bar.subtitle(WnLogic.typeLabel(type) + " · TMDB " + tmdb);
        root = scrollBody();
        loadDetails();
    }

    // ------------------------------------------------------------------ details

    private String[] pendingUndo;

    private void loadDetails() {
        showLoading("جارٍ تحميل التفاصيل من TMDB…");
        CgHttp.async(this, new CgHttp.Work<JSONObject>() {
            @Override public JSONObject run() throws Exception { return WnApi.details(type, tmdb); }
        }, new CgHttp.Done<JSONObject>() {
            @Override public void ok(JSONObject d) {
                hideLoading();
                if (!alive()) return;
                det = d;
                render();
                if (pendingUndo != null) {
                    String[] u = pendingUndo;
                    pendingUndo = null;
                    showUndo(u[0], u[1], new Runnable() {
                        @Override public void run() { WnUi.dirty = true; loadDetails(); }
                    });
                }
            }

            @Override public void fail(String message) {
                hideLoading();
                if (!alive()) return;
                root.removeAllViews();
                root.addView(CgUi.errorCard(WnDetailActivity.this, message, new Runnable() {
                    @Override public void run() { loadDetails(); }
                }));
            }
        });
    }

    private void render() {
        root.removeAllViews();
        root.addView(header());
        JSONObject imp = det.optJSONObject("import");
        String st = imp == null ? "new" : imp.optString("state", "new");
        if ("imported".equals(st)) {
            JSONObject ex = imp.optJSONObject("existing");
            LinearLayout b = WnUi.glass(this);
            b.addView(CgUi.text(this, "موجود في مكتبة Cigram" + (ex == null ? "" : ": «" + ex.optString("title") + "»"), 14, CgCfg.GOOD, true));
            b.addView(CgUi.muted(this, series ? "يمكنك إضافة حلقات أو مواسم جديدة إليه أدناه." : "لا حاجة لاستيراده مرة أخرى."));
            b.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
            root.addView(b);
            if (!series) return;
        } else if ("duplicate".equals(st)) {
            JSONArray m = imp.optJSONArray("duplicates");
            JSONObject first = m == null ? null : m.optJSONObject(0);
            LinearLayout b = WnUi.glass(this);
            b.addView(CgUi.text(this, "قد يكون مكرراً" + (first == null ? "" : ": «" + first.optString("title") + "» (" + first.optString("reason_text", "تطابق") + ")"), 13.5f, CgCfg.WARN, true));
            b.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
            root.addView(b);
        }
        if (!det.optBoolean("released", true) && !series) {
            TextView t = CgUi.text(this, "لم يصدر بعد (" + det.optString("release_date") + ") — لا يمكن استيراده الآن.", 13.5f, CgCfg.WARN, true);
            t.setPadding(CgUi.dp(this, 4), 0, 0, CgUi.dp(this, 10));
            root.addView(t);
            return;
        }

        JSONObject col = det.optJSONObject("collection");
        if (col != null && col.optLong("id") > 0 && !series) {
            final long colId = col.optLong("id");
            final String colName = col.optString("name");
            LinearLayout cb = WnUi.glass(this);
            cb.addView(CgUi.text(this, "ينتمي إلى سلسلة: " + colName, 14, CgCfg.ORANGE, true));
            cb.addView(CgUi.muted(this, "اعرض كل أجزاء السلسلة وأضفها دفعة واحدة."));
            TextView open = CgUi.smallButton(this, "عرض السلسلة وإضافتها كاملة", 1);
            open.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { WnCollectionActivity.open(WnDetailActivity.this, colId, colName); }
            });
            cb.addView(open, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 0, 0));
            cb.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
            root.addView(cb);
        }
        videoBox = CgUi.vbox(this);
        subBox = CgUi.vbox(this);
        catBox = CgUi.vbox(this);
        seasonBox = CgUi.vbox(this);
        reportBox = CgUi.vbox(this);
        actionBox = CgUi.vbox(this);
        if (series) {
            root.addView(seasonBox);
            root.addView(catBox);
            root.addView(reportBox);
            root.addView(actionBox);
        } else {
            root.addView(videoBox);
            root.addView(subBox);
            root.addView(catBox);
            root.addView(reportBox);
            root.addView(actionBox);
        }
        cats.clear();
        JSONArray g = det.optJSONArray("genres");
        for (int i = 0; g != null && i < g.length(); i++) cats.add(g.optString(i));
        catsTouched = false;
        buildCategories();
        buildActions();
        renderReport();
        if (series) {
            buildSeasons();
            autoPickLatestSeason();
        } else {
            findVideo(false);
        }
    }

    private View header() {
        LinearLayout box = CgUi.vbox(this);
        FrameLayout hero = new FrameLayout(this);
        hero.setBackground(CgUi.bg(this, CgCfg.CARD2, 22, 0));
        CgUi.elevate(hero, 5);
        ImageView bd = new ImageView(this);
        bd.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String back = det.optString("backdrop").length() > 0 ? det.optString("backdrop") : det.optString("poster");
        CgImg.load(bd, back, CgUi.dp(this, 700));
        hero.addView(bd, new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.MATCH));
        View scrim = new View(this);
        scrim.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x33080A12, 0xF2080A12}));
        hero.addView(scrim, new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.MATCH));
        LinearLayout row = CgUi.hbox(this);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(CgUi.dp(this, 14), CgUi.dp(this, 14), CgUi.dp(this, 14), CgUi.dp(this, 14));
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(CgUi.bg(this, CgCfg.CARD3, 12, 0));
        CgUi.elevate(poster, 6);
        CgImg.load(poster, det.optString("poster"), CgUi.dp(this, 200));
        row.addView(poster, new LinearLayout.LayoutParams(CgUi.dp(this, 96), CgUi.dp(this, 142)));
        LinearLayout t = CgUi.vbox(this);
        t.setPadding(CgUi.dp(this, 12), 0, 0, 0);
        t.addView(CgUi.text(this, det.optString("title"), 19, CgCfg.TEXT, true));
        String orig = det.optString("original_title");
        if (orig.length() > 0 && !orig.equals(det.optString("title"))) {
            TextView o = CgUi.text(this, orig, 13, CgCfg.MUTED, false);
            CgUi.ltr(o);
            o.setGravity(Gravity.START);
            t.addView(o);
        }
        LinearLayout badges = CgUi.hbox(this);
        badges.setPadding(0, CgUi.dp(this, 6), 0, 0);
        badges.addView(WnUi.badge(this, WnLogic.typeLabel(type), series ? CgCfg.ACCENT_2 : CgCfg.ACCENT), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        l2.setMarginStart(CgUi.dp(this, 5));
        badges.addView(WnUi.imdbRating(this, det.optDouble("rating", 0), 13), l2);
        if (det.optString("year").length() > 0) {
            LinearLayout.LayoutParams l3 = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            l3.setMarginStart(CgUi.dp(this, 5));
            badges.addView(WnUi.badge(this, det.optString("year"), CgCfg.GRAY), l3);
        }
        t.addView(badges);
        row.addView(t, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        hero.addView(row, new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP, Gravity.BOTTOM));
        box.addView(hero, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.dp(this, 230)));

        LinearLayout facts = WnUi.glass(this);
        String ov = det.optString("description");
        if (ov.length() > 0) {
            TextView o = CgUi.text(this, ov, 13.5f, CgCfg.TEXT, false);
            o.setLineSpacing(0, 1.2f);
            facts.addView(o);
        }
        fact(facts, "تاريخ الإصدار", det.optString("release_date"), true);
        fact(facts, "التصنيفات", WnUi.joinFirst(det.optJSONArray("genres"), 6), false);
        fact(facts, "اللغة الأصلية", det.optString("original_language"), true);
        fact(facts, "الدول", WnUi.joinFirst(det.optJSONArray("countries"), 4), true);
        fact(facts, "التقييم", WnLogic.ratingText(det.optDouble("rating", 0)) + " (" + det.optInt("vote_count") + " صوت)", false);
        fact(facts, "المدة", det.optString("duration") + (det.optInt("runtime_minutes") > 0 ? " (" + det.optInt("runtime_minutes") + " د)" : ""), true);
        fact(facts, "معرّف TMDB", String.valueOf(det.optLong("tmdb_id")), true);
        if (det.optJSONObject("collection") != null) fact(facts, "السلسلة", det.optJSONObject("collection").optString("name"), false);
        fact(facts, "معرّف IMDb", det.optString("imdb_id"), true);
        fact(facts, "الحالة", det.optString("status"), true);
        if (series) {
            fact(facts, "المواسم / الحلقات", det.optInt("season_count") + " موسم · " + det.optInt("episode_count") + " حلقة", false);
            JSONObject le = det.optJSONObject("latest_episode");
            if (le != null && le.optInt("episode") > 0) {
                fact(facts, "آخر حلقة عُرضت", WnLogic.epCode(le.optInt("season"), le.optInt("episode")) + " · " + le.optString("air_date") + (det.optBoolean("latest_in_library") ? " (في المكتبة)" : " (غير مضافة)"), true);
            }
            JSONObject ne = det.optJSONObject("next_episode");
            if (ne != null && ne.optInt("episode") > 0) fact(facts, "الحلقة القادمة", WnLogic.epCode(ne.optInt("season"), ne.optInt("episode")) + " · " + ne.optString("air_date"), true);
        }
        facts.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 10));
        box.addView(facts);
        return box;
    }

    private void fact(LinearLayout box, String label, String value, boolean ltr) {
        if (value == null || value.trim().length() == 0) return;
        LinearLayout r = CgUi.hbox(this);
        r.setPadding(0, CgUi.dp(this, 5), 0, 0);
        r.addView(CgUi.text(this, label, 12.5f, CgCfg.MUTED, false), new LinearLayout.LayoutParams(CgUi.dp(this, 110), CgUi.WRAP));
        TextView v = CgUi.text(this, value, 12.5f, CgCfg.TEXT, true);
        if (ltr) { CgUi.ltr(v); v.setGravity(Gravity.START); }
        r.addView(v, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        box.addView(r);
    }

    // ------------------------------------------------------------------ movie: video

    private void findVideo(final boolean refresh) {
        videoBox.removeAllViews();
        videoBox.addView(WnUi.sectionHeader(this, "مصدر الفيديو", "بحث تلقائي ثم فحص حقيقي لكل مصدر — لن يُعلَّم أي مصدر «صالح للتشغيل» دون التحقق منه فعلاً."));
        LinearLayout w = CgUi.hbox(this);
        w.addView(CgUi.spinner(this), new LinearLayout.LayoutParams(CgUi.dp(this, 26), CgUi.dp(this, 26)));
        TextView t = CgUi.muted(this, "  جارٍ البحث والتحقق من مصادر الفيديو…");
        w.addView(t);
        videoBox.addView(w);
        final JSONObject req = WnPipeline.videoRequest("movie", tmdb, 0, null);
        if (refresh) CxJ.put(req, "refresh", true);
        if (customUrl.length() > 0) CxJ.put(req, "custom_url", customUrl);
        CgHttp.async(this, new CgHttp.Work<JSONObject>() {
            @Override public JSONObject run() throws Exception { return WnApi.video(req); }
        }, new CgHttp.Done<JSONObject>() {
            @Override public void ok(JSONObject d) {
                if (!alive()) return;
                movieTarget = WnLogic.at(d.optJSONArray("targets"), 0);
                JSONArray lim = d.optJSONArray("limitations");
                limitations = lim != null && lim.length() > 0 ? lim.optString(0) : "";
                JSONObject best = WnLogic.bestSource(movieTarget);
                movieSrc = indexOfSource(movieTarget, best);
                renderVideo();
                if (subResults == null) findSubs(false);
                invalidate();
            }

            @Override public void fail(String message) {
                if (!alive()) return;
                videoBox.removeAllViews();
                videoBox.addView(WnUi.sectionHeader(WnDetailActivity.this, "مصدر الفيديو", ""));
                videoBox.addView(CgUi.errorCard(WnDetailActivity.this, message, new Runnable() {
                    @Override public void run() { findVideo(true); }
                }));
            }
        });
    }

    private int indexOfSource(JSONObject target, JSONObject src) {
        JSONArray a = target == null ? null : target.optJSONArray("sources");
        for (int i = 0; a != null && i < a.length(); i++) if (a.optJSONObject(i) == src) return i;
        return -1;
    }

    private void renderVideo() {
        videoBox.removeAllViews();
        videoBox.addView(WnUi.sectionHeader(this, "مصدر الفيديو", "اضغط على مصدر صالح لاختياره. المصادر الأخرى تُحفَظ كبدائل في سجل الاستيراد."));
        JSONArray sources = movieTarget == null ? null : movieTarget.optJSONArray("sources");
        for (int i = 0; sources != null && i < sources.length(); i++) {
            final int idx = i;
            videoBox.addView(WnUi.sourceRow(this, sources.optJSONObject(i), i == movieSrc, new Runnable() {
                @Override public void run() { movieSrc = idx; renderVideo(); invalidate(); }
            }));
        }
        if (WnLogic.bestSource(movieTarget) == null) {
            TextView none = CgUi.text(this, "لا يوجد مصدر فيديو صالح للاستيراد الآن. يمكنك إدخال رابط مصدر مرخّص يدوياً أو إعادة الفحص لاحقاً.", 13, CgCfg.BAD, true);
            none.setPadding(CgUi.dp(this, 4), 0, 0, CgUi.dp(this, 8));
            videoBox.addView(none);
        }
        if (limitations.length() > 0) {
            TextView l = CgUi.text(this, "ℹ " + limitations, 12, CgCfg.MUTED, false);
            l.setPadding(CgUi.dp(this, 4), 0, 0, CgUi.dp(this, 8));
            videoBox.addView(l);
        }
        final EditText url = CgUi.urlField(this, "رابط مصدر مرخّص (mp4 / m3u8 / صفحة مشغّل)");
        url.setText(customUrl);
        videoBox.addView(CgUi.labeled(this, "رابط يدوي (اختياري)", url));
        LinearLayout btns = CgUi.hbox(this);
        TextView check = CgUi.smallButton(this, "فحص الرابط", 1);
        check.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String u = url.getText().toString().trim();
                if (u.length() > 0 && !CxJ.isUrl(u)) { CgUi.toast(WnDetailActivity.this, "يجب أن يبدأ الرابط بـ https://"); return; }
                customUrl = u;
                findVideo(true);
            }
        });
        btns.addView(check, CgUi.lpWeight(this, 1f, 0, 0, 4, 0));
        TextView again = CgUi.smallButton(this, "إعادة الفحص", 1);
        again.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { findVideo(true); }
        });
        btns.addView(again, CgUi.lpWeight(this, 1f, 4, 0, 0, 0));
        videoBox.addView(btns);
    }

    // ------------------------------------------------------------------ movie: subtitle

    private void findSubs(final boolean userAsked) {
        subBox.removeAllViews();
        subBox.addView(WnUi.sectionHeader(this, "الترجمة", "العربية أولاً ثم لغات أخرى. تُنزَّل وتُفحص (SRT، التوقيت، اللغة) قبل أي ربط."));
        LinearLayout w = CgUi.hbox(this);
        w.addView(CgUi.spinner(this), new LinearLayout.LayoutParams(CgUi.dp(this, 26), CgUi.dp(this, 26)));
        w.addView(CgUi.muted(this, "  جارٍ البحث عن ترجمة…"));
        subBox.addView(w);
        final JSONObject req = new JSONObject();
        CxJ.put(req, "type", type);
        CxJ.put(req, "tmdb_id", tmdb);
        CxJ.put(req, "lang", subLang);
        CgHttp.async(this, new CgHttp.Work<JSONObject>() {
            @Override public JSONObject run() throws Exception { return WnApi.subtitles(req); }
        }, new CgHttp.Done<JSONObject>() {
            @Override public void ok(JSONObject d) {
                if (!alive()) return;
                subResults = d.optJSONArray("results");
                if (subResults == null) subResults = new JSONArray();
                String used = d.optString("lang", "ar");
                subNote = subResults.length() == 0 ? "لا توجد ترجمة متاحة (" + used + ")." : "";
                renderSubs(used);
                if (subResults.length() > 0 && subToken.length() == 0 && !userAsked) fetchSub(subResults.optJSONObject(0), used, true);
            }

            @Override public void fail(String message) {
                if (!alive()) return;
                subResults = new JSONArray();
                subNote = message;
                renderSubs(subLang);
            }
        });
    }

    private void renderSubs(final String usedLang) {
        subBox.removeAllViews();
        subBox.addView(WnUi.sectionHeader(this, "الترجمة", "اختيارية. الملف يُفحص ويُخزَّن في R2 مع المحتوى."));
        if (subInfo != null && subToken.length() > 0) {
            LinearLayout sel = WnUi.glass(this);
            sel.addView(CgUi.text(this, "✓ ترجمة مُتحقَّق منها (" + subInfo.optString("lang") + ") · " + subInfo.optInt("cues") + " سطر · " + subInfo.optString("source"), 13.5f, CgCfg.GOOD, true));
            if (subInfo.optString("release").length() > 0) sel.addView(CgUi.muted(this, subInfo.optString("release")));
            JSONArray warns = subInfo.optJSONObject("validation") == null ? null : subInfo.optJSONObject("validation").optJSONArray("warnings");
            for (int i = 0; warns != null && i < warns.length(); i++) {
                TextView wt = CgUi.text(this, "! " + warns.optJSONObject(i).optString("message"), 12, CgCfg.WARN, false);
                sel.addView(wt);
            }
            LinearLayout b = CgUi.hbox(this);
            TextView prev = CgUi.smallButton(this, "معاينة", 1);
            prev.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { CgUi.info(WnDetailActivity.this, "معاينة الترجمة", subInfo.optString("preview")); }
            });
            b.addView(prev, CgUi.lpWeight(this, 1f, 0, 8, 4, 0));
            TextView none = CgUi.smallButton(this, "بدون ترجمة", 2);
            none.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { subToken = ""; subInfo = null; renderSubs(usedLang); invalidate(); }
            });
            b.addView(none, CgUi.lpWeight(this, 1f, 4, 8, 0, 0));
            sel.addView(b);
            sel.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
            subBox.addView(sel);
        } else if (subNote.length() > 0) {
            TextView n = CgUi.text(this, subNote, 13, CgCfg.WARN, false);
            n.setPadding(CgUi.dp(this, 4), 0, 0, CgUi.dp(this, 8));
            subBox.addView(n);
        }
        for (int i = 0; subResults != null && i < subResults.length() && i < 6; i++) {
            final JSONObject c = subResults.optJSONObject(i);
            if (c == null) continue;
            LinearLayout r = CgUi.hbox(this);
            r.setBackground(CgUi.bg(this, CgCfg.CARD2, 12, 0));
            r.setPadding(CgUi.dp(this, 10), CgUi.dp(this, 8), CgUi.dp(this, 10), CgUi.dp(this, 8));
            LinearLayout m = CgUi.vbox(this);
            TextView rel = CgUi.text(this, c.optString("release", c.optString("name")), 12.5f, CgCfg.TEXT, false);
            rel.setSingleLine(true);
            rel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            CgUi.ltr(rel);
            rel.setGravity(Gravity.START);
            m.addView(rel);
            m.addView(CgUi.muted(this, c.optString("source") + " · " + usedLang + " · مطابقة " + c.optInt("score") + "%"));
            r.addView(m, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            TextView use = CgUi.smallButton(this, "تنزيل وفحص", 1);
            use.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { fetchSub(c, usedLang, false); }
            });
            r.addView(use, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            r.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));
            subBox.addView(r);
        }
        LinearLayout langs = CgUi.hbox(this);
        String[][] opts = {{"تلقائي", "auto"}, {"العربية", "ar"}, {"English", "en"}};
        for (final String[] o : opts) {
            TextView t = CgUi.chip(this, o[0], o[1].equals(subLang));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { subLang = o[1]; subToken = ""; subInfo = null; findSubs(true); invalidate(); }
            });
            langs.addView(t, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 4, 6, 4));
        }
        subBox.addView(langs);
        LinearLayout btns = CgUi.hbox(this);
        TextView manual = CgUi.smallButton(this, "استبدال يدوي (لصق/ملف)", 1);
        manual.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { manualSubtitle(); }
        });
        btns.addView(manual, CgUi.lpWeight(this, 1f, 0, 4, 4, 0));
        TextView again = CgUi.smallButton(this, "إعادة البحث", 1);
        again.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { findSubs(true); }
        });
        btns.addView(again, CgUi.lpWeight(this, 1f, 4, 4, 0, 0));
        subBox.addView(btns);
    }

    private void fetchSub(final JSONObject pick, final String lang, final boolean auto) {
        if (!auto) showLoading("جارٍ تنزيل الترجمة وفحصها…");
        final JSONObject req = new JSONObject();
        CxJ.put(req, "type", type);
        CxJ.put(req, "tmdb_id", tmdb);
        CxJ.put(req, "lang", lang);
        try { req.put("pick", pick); } catch (Exception ignored) { }
        CgHttp.async(this, new CgHttp.Work<JSONObject>() {
            @Override public JSONObject run() throws Exception { return WnApi.subtitleFetch(req); }
        }, new CgHttp.Done<JSONObject>() {
            @Override public void ok(JSONObject d) {
                if (!auto) hideLoading();
                if (!alive()) return;
                acceptSubtitle(d, auto);
            }

            @Override public void fail(String message) {
                if (!auto) hideLoading();
                if (!alive()) return;
                subNote = message;
                renderSubs(lang);
                if (!auto) CgUi.info(WnDetailActivity.this, "تعذر جلب الترجمة", message);
            }
        });
    }

    private void acceptSubtitle(JSONObject d, boolean auto) {
        JSONObject val = d.optJSONObject("validation");
        String token = d.optString("token", "");
        if (val != null && val.optBoolean("ok", false) && token.length() > 0) {
            subToken = token;
            subInfo = d;
            subNote = "";
        } else {
            subToken = "";
            subInfo = null;
            JSONArray issues = val == null ? null : val.optJSONArray("issues");
            subNote = "الملف لم يجتز الفحص: " + (issues != null && issues.length() > 0 ? issues.optJSONObject(0).optString("message") : "غير صالح");
            if (!auto) CgUi.info(this, "ترجمة غير صالحة", subNote);
        }
        renderSubs(d.optString("lang", "ar"));
        invalidate();
    }

    private void manualSubtitle() {
        CgUi.choose(this, "استبدال الترجمة", new CharSequence[]{"لصق نص SRT", "اختيار ملف (.srt / .vtt)"}, new CgUi.IntCb() {
            @Override public void run(int index) {
                if (index == 0) {
                    CgUi.input(WnDetailActivity.this, "الصق محتوى ملف SRT", "1\n00:00:01,000 --> 00:00:03,000\n...", "", true, new CgUi.Callback<String>() {
                        @Override public void run(String text) { validateManual(text, null); }
                    });
                } else {
                    Intent in = new Intent(Intent.ACTION_GET_CONTENT);
                    in.setType("*/*");
                    in.addCategory(Intent.CATEGORY_OPENABLE);
                    try {
                        startActivityForResult(Intent.createChooser(in, "اختر ملف الترجمة"), REQ_SUBTITLE_FILE);
                    } catch (Exception e) {
                        CgUi.toast(WnDetailActivity.this, "لا يوجد تطبيق لاختيار الملفات.");
                    }
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SUBTITLE_FILE || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("تعذر فتح الملف");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > 1500000) { in.close(); CgUi.toast(this, "الملف أكبر من 1.5MB."); return; }
                out.write(buf, 0, n);
            }
            in.close();
            validateManual(null, android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP));
        } catch (Exception e) {
            CgUi.toast(this, "تعذر قراءة الملف: " + CgHttp.friendly(e.getMessage()));
        }
    }

    private void validateManual(String text, String base64) {
        final JSONObject req = new JSONObject();
        CxJ.put(req, "type", type);
        CxJ.put(req, "tmdb_id", tmdb);
        CxJ.put(req, "lang", "auto".equals(subLang) ? "ar" : subLang);
        if (text != null) CxJ.put(req, "text", text);
        if (base64 != null) CxJ.put(req, "base64", base64);
        showLoading("جارٍ فحص الملف…");
        CgHttp.async(this, new CgHttp.Work<JSONObject>() {
            @Override public JSONObject run() throws Exception { return WnApi.subtitleValidate(req); }
        }, new CgHttp.Done<JSONObject>() {
            @Override public void ok(JSONObject d) {
                hideLoading();
                if (alive()) acceptSubtitle(d, false);
            }

            @Override public void fail(String message) {
                hideLoading();
                if (alive()) CgUi.info(WnDetailActivity.this, "تعذر فحص الملف", message);
            }
        });
    }

    // ------------------------------------------------------------------ categories

    private void buildCategories() {
        catBox.removeAllViews();
        catBox.addView(WnUi.sectionHeader(this, "القسم والتصنيف", "القسم هو ما يظهر في الرئيسية. التصنيفات نصوص التصنيف المرتبطة بالعنوان."));
        TextView lab = CgUi.muted(this, "القسم");
        lab.setPadding(CgUi.dp(this, 4), 0, 0, CgUi.dp(this, 4));
        catBox.addView(lab);
        CgUi.ChipBar sections = new CgUi.ChipBar(this);
        TextView none = sections.add("بدون قسم", sectionId.length() == 0 && newSection.length() == 0);
        none.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sectionId = ""; newSection = ""; buildCategories(); invalidate(); }
        });
        JSONArray secs = det.optJSONArray("sections");
        JSONArray sug = det.optJSONArray("suggested_sections");
        for (int i = 0; secs != null && i < secs.length(); i++) {
            final JSONObject s = secs.optJSONObject(i);
            if (s == null) continue;
            boolean suggested = false;
            for (int k = 0; sug != null && k < sug.length(); k++) if (s.optString("id").equals(sug.optJSONObject(k).optString("id"))) suggested = true;
            TextView t = sections.add((suggested ? "★ " : "") + s.optString("name"), s.optString("id").equals(sectionId));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { sectionId = s.optString("id"); newSection = ""; buildCategories(); invalidate(); }
            });
        }
        String newLabel = newSection.length() > 0 ? "＋ " + newSection : "＋ قسم جديد";
        TextView add = sections.add(newLabel, newSection.length() > 0);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { createSection(); }
        });
        catBox.addView(sections.scroll, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, -12, 0, -12, 6));
        if (sug != null && sug.length() > 0) {
            TextView h = CgUi.text(this, "★ مقترح حسب التصنيفات: " + sug.optJSONObject(0).optString("name"), 11.5f, CgCfg.ACCENT, false);
            h.setPadding(CgUi.dp(this, 4), 0, 0, CgUi.dp(this, 6));
            catBox.addView(h);
        }
        TextView lab2 = CgUi.muted(this, "التصنيفات (يمكن اختيار أكثر من واحد)" + (catsTouched ? "" : " — الافتراضي: تصنيفات TMDB"));
        lab2.setPadding(CgUi.dp(this, 4), CgUi.dp(this, 4), 0, CgUi.dp(this, 4));
        catBox.addView(lab2);
        CgUi.ChipBar tags = new CgUi.ChipBar(this);
        LinkedHashSet<String> all = new LinkedHashSet<>(cats);
        JSONArray g = det.optJSONArray("genres");
        for (int i = 0; g != null && i < g.length(); i++) all.add(g.optString(i));
        JSONArray ex = det.optJSONArray("existing_categories");
        for (int i = 0; ex != null && i < ex.length() && all.size() < 40; i++) all.add(ex.optJSONObject(i).optString("name"));
        for (final String name : all) {
            final TextView t = tags.add(name, cats.contains(name));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    catsTouched = true;
                    if (cats.contains(name)) cats.remove(name); else cats.add(name);
                    CgUi.setChipState(t, cats.contains(name));
                    invalidate();
                }
            });
        }
        TextView addTag = tags.add("＋ تصنيف جديد", false);
        addTag.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CgUi.input(WnDetailActivity.this, "تصنيف جديد", "اسم التصنيف", "", false, new CgUi.Callback<String>() {
                    @Override public void run(String value) {
                        if (value.length() == 0) return;
                        catsTouched = true;
                        cats.add(value.length() > 40 ? value.substring(0, 40) : value);
                        buildCategories();
                        invalidate();
                    }
                });
            }
        });
        catBox.addView(tags.scroll, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, -12, 0, -12, 6));
    }

    private void createSection() {
        CgUi.input(this, "قسم جديد", "اسم القسم (مثال: أفلام العيد)", newSection, false, new CgUi.Callback<String>() {
            @Override public void run(final String name) {
                if (name.length() == 0) return;
                CgUi.choose(WnDetailActivity.this, "نوع القسم", new CharSequence[]{series ? "مسلسلات" : "أفلام", "مختلط"}, new CgUi.IntCb() {
                    @Override public void run(int index) {
                        newSection = name;
                        newSectionType = index == 1 ? "mixed" : series ? "series" : "movie";
                        sectionId = "";
                        buildCategories();
                        invalidate();
                    }
                });
            }
        });
    }

    private WnLogic.Placement placement() {
        WnLogic.Placement p = new WnLogic.Placement();
        p.sectionId = sectionId;
        p.newSection = newSection;
        p.newSectionType = newSectionType;
        p.categories = catsTouched ? new ArrayList<>(cats) : null;
        return p;
    }

    private WnLogic.Flags flags() {
        WnLogic.Flags f = new WnLogic.Flags();
        f.ackUnverified = ack;
        f.notify = notify;
        f.subtitleRequired = subRequired;
        f.quality = quality;
        return f;
    }

    // ------------------------------------------------------------------ series: seasons and episodes

    private static String key(int season, int episode) {
        return season + ":" + episode;
    }

    private void buildSeasons() {
        seasonBox.removeAllViews();
        seasonBox.addView(WnUi.sectionHeader(this, "المواسم والحلقات", "اختر مواسم أو حلقات. كل حلقة تُفحص مستقلة ولا يُربط رابطها بحلقة أخرى."));
        LinearLayout top = CgUi.hbox(this);
        TextView all = CgUi.smallButton(this, "المسلسل كاملاً (كل المواسم)", 1);
        all.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectAllSeasons(); }
        });
        top.addView(all, CgUi.lpWeight(this, 1f, 0, 0, 4, 6));
        TextView clear = CgUi.smallButton(this, "إلغاء التحديد", 1);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { picked.clear(); buildSeasons(); invalidate(); }
        });
        top.addView(clear, CgUi.lpWeight(this, 1f, 4, 0, 0, 6));
        seasonBox.addView(top);
        if (seriesBusy.length() > 0) {
            LinearLayout w = CgUi.hbox(this);
            w.addView(CgUi.spinner(this), new LinearLayout.LayoutParams(CgUi.dp(this, 22), CgUi.dp(this, 22)));
            w.addView(CgUi.text(this, "  " + seriesBusy, 12.5f, CgCfg.CYAN, false));
            w.setPadding(0, 0, 0, CgUi.dp(this, 8));
            seasonBox.addView(w);
        }
        JSONArray ss = det.optJSONArray("seasons");
        for (int i = 0; ss != null && i < ss.length(); i++) seasonBox.addView(seasonRow(ss.optJSONObject(i)));
        int n = pickedCount();
        TextView sum = CgUi.text(this, n + " حلقة محددة", 13, CgCfg.ACCENT, true);
        sum.setPadding(CgUi.dp(this, 4), CgUi.dp(this, 4), 0, 0);
        seasonBox.addView(sum);
        TextView find = CgUi.button(this, "فحص الفيديو للحلقات المحددة", 1);
        find.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { resolvePicked(true); }
        });
        LinearLayout.LayoutParams fl = CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0);
        seasonBox.addView(find, fl);
        TextView subs = CgUi.button(this, "البحث عن ترجمات الحلقات المحددة", 1);
        subs.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { fetchEpisodeSubtitles(); }
        });
        seasonBox.addView(subs, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
    }

    private int pickedCount() {
        int n = 0;
        for (TreeSet<Integer> s : picked.values()) n += s.size();
        return n;
    }

    private View seasonRow(final JSONObject s) {
        final int season = s.optInt("season");
        LinearLayout box = WnUi.glass(this);
        box.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        LinearLayout head = CgUi.hbox(this);
        LinearLayout t = CgUi.vbox(this);
        t.addView(CgUi.text(this, "الموسم " + season + (s.optString("name").length() > 0 && !s.optString("name").contains(String.valueOf(season)) ? " · " + s.optString("name") : ""), 14.5f, CgCfg.TEXT, true));
        TextView meta = CgUi.muted(this, s.optInt("episode_count") + " حلقة" + (s.optString("air_date").length() > 0 ? " · " + s.optString("air_date") : ""));
        t.addView(meta);
        head.addView(t, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        int sel = picked.containsKey(season) ? picked.get(season).size() : 0;
        if (!s.optBoolean("aired", true)) head.addView(WnUi.badge(this, "لم يبدأ", CgCfg.GRAY), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        else if (sel > 0) head.addView(WnUi.badge(this, sel + " محدد", CgCfg.ACCENT), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        box.addView(head);
        LinearLayout btns = CgUi.hbox(this);
        TextView pick = CgUi.smallButton(this, "تحديد الموسم", 1);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ensureSeason(season, new Runnable() {
                    @Override public void run() { selectSeason(season); buildSeasons(); invalidate(); resolvePicked(false); }
                });
            }
        });
        btns.addView(pick, CgUi.lpWeight(this, 1f, 0, 8, 4, 0));
        TextView show = CgUi.smallButton(this, expanded.contains(season) ? "إخفاء الحلقات" : "عرض الحلقات", 1);
        show.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (expanded.contains(season)) { expanded.remove(season); buildSeasons(); return; }
                ensureSeason(season, new Runnable() {
                    @Override public void run() { expanded.add(season); buildSeasons(); }
                });
            }
        });
        btns.addView(show, CgUi.lpWeight(this, 1f, 4, 8, 0, 0));
        box.addView(btns);
        if (expanded.contains(season) && seasonData.containsKey(season)) {
            JSONArray eps = seasonData.get(season).optJSONArray("episodes");
            for (int i = 0; eps != null && i < eps.length(); i++) box.addView(episodeRow(season, eps.optJSONObject(i)));
        }
        return box;
    }

    private View episodeRow(final int season, final JSONObject e) {
        final int n = e.optInt("episode");
        boolean inLib = e.optBoolean("in_library", false);
        boolean aired = e.optBoolean("aired", false);
        boolean sel = picked.containsKey(season) && picked.get(season).contains(n);
        LinearLayout r = CgUi.hbox(this);
        r.setPadding(0, CgUi.dp(this, 8), 0, 0);
        TextView box = CgUi.text(this, inLib ? "✓" : !aired ? "–" : sel ? "☑" : "☐", 18, inLib ? CgCfg.GOOD : !aired ? CgCfg.FAINT : CgCfg.ACCENT, true);
        r.addView(box, new LinearLayout.LayoutParams(CgUi.dp(this, 30), CgUi.WRAP));
        LinearLayout m = CgUi.vbox(this);
        TextView nm = CgUi.text(this, WnLogic.epCode(season, n) + " — " + (e.optString("name").length() > 0 ? e.optString("name") : "الحلقة " + n), 13, CgCfg.TEXT, false);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.END);
        m.addView(nm);
        String info = (e.optString("air_date").length() > 0 ? e.optString("air_date") : "بلا تاريخ") + (inLib ? " · في المكتبة" : !aired ? " · لم تُعرض بعد" : "");
        TextView mi = CgUi.text(this, info, 11.5f, CgCfg.MUTED, false);
        CgUi.ltr(mi);
        mi.setGravity(Gravity.START);
        m.addView(mi);
        r.addView(m, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        JSONObject tg = epTarget.get(key(season, n));
        if (tg != null) {
            String v = tg.optString("status");
            r.addView(WnUi.badge(this, WnLogic.verdictLabel(v).length() > 14 ? WnLogic.verdictLabel(v).substring(0, 14) + "…" : WnLogic.verdictLabel(v), WnLogic.verdictColor(v)), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        }
        if (epSub.containsKey(key(season, n)) && epSub.get(key(season, n)).length() > 0) {
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            sl.setMarginStart(CgUi.dp(this, 4));
            r.addView(WnUi.badge(this, "ترجمة", CgCfg.GOOD), sl);
        }
        if (!inLib && aired) {
            r.setClickable(true);
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    TreeSet<Integer> set = picked.get(season);
                    if (set == null) { set = new TreeSet<>(); picked.put(season, set); }
                    if (set.contains(n)) set.remove(n); else set.add(n);
                    if (set.isEmpty()) picked.remove(season);
                    buildSeasons();
                    invalidate();
                }
            });
        }
        return r;
    }

    private void ensureSeason(final int season, final Runnable then) {
        if (seasonData.containsKey(season)) { then.run(); return; }
        showLoading("جارٍ قراءة حلقات الموسم " + season + "…");
        CgHttp.async(this, new CgHttp.Work<JSONObject>() {
            @Override public JSONObject run() throws Exception { return WnApi.season(tmdb, season); }
        }, new CgHttp.Done<JSONObject>() {
            @Override public void ok(JSONObject d) {
                hideLoading();
                if (!alive()) return;
                seasonData.put(season, d);
                then.run();
            }

            @Override public void fail(String message) {
                hideLoading();
                if (alive()) CgUi.info(WnDetailActivity.this, "تعذر قراءة الموسم " + season, message);
            }
        });
    }

    private void selectSeason(int season) {
        JSONObject sd = seasonData.get(season);
        if (sd == null) return;
        TreeSet<Integer> set = new TreeSet<>();
        JSONArray eps = sd.optJSONArray("episodes");
        for (int i = 0; eps != null && i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e != null && e.optBoolean("aired", false) && !e.optBoolean("in_library", false)) set.add(e.optInt("episode"));
        }
        if (set.isEmpty()) picked.remove(season); else picked.put(season, set);
    }

    private void selectAllSeasons() {
        final JSONArray ss = det.optJSONArray("seasons");
        final List<Integer> todo = new ArrayList<>();
        for (int i = 0; ss != null && i < ss.length(); i++) {
            JSONObject s = ss.optJSONObject(i);
            if (s != null && s.optBoolean("aired", true)) todo.add(s.optInt("season"));
        }
        showLoading("جارٍ قراءة كل المواسم…");
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                String err = null;
                for (int season : todo) {
                    if (seasonData.containsKey(season)) continue;
                    try {
                        JSONObject d = WnApi.season(tmdb, season);
                        synchronized (seasonData) { seasonData.put(season, d); }
                    } catch (CxApi.Failure f) {
                        err = f.getMessage();
                    }
                }
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        hideLoading();
                        if (!alive()) return;
                        for (int season : todo) selectSeason(season);
                        buildSeasons();
                        invalidate();
                        if (fe != null) CgUi.toast(WnDetailActivity.this, "تعذر قراءة بعض المواسم: " + fe);
                        resolvePicked(false);
                    }
                });
            }
        });
    }

    private void autoPickLatestSeason() {
        JSONObject le = det.optJSONObject("latest_episode");
        int season = le == null ? 0 : le.optInt("season");
        if (season <= 0) {
            JSONArray ss = det.optJSONArray("seasons");
            for (int i = 0; ss != null && i < ss.length(); i++) {
                JSONObject s = ss.optJSONObject(i);
                if (s != null && s.optBoolean("aired", true)) season = Math.max(season, s.optInt("season"));
            }
        }
        if (season <= 0) return;
        final int target = season;
        ensureSeason(target, new Runnable() {
            @Override public void run() {
                selectSeason(target);
                expanded.add(target);
                buildSeasons();
                invalidate();
                resolvePicked(false);
            }
        });
    }

    /** Finds + validates the video of every picked episode that has no result yet (or all, when the admin asks). */
    private void resolvePicked(final boolean force) {
        if (!seriesBusy.isEmpty()) return;
        final TreeMap<Integer, List<Integer>> todo = new TreeMap<>();
        for (Map.Entry<Integer, TreeSet<Integer>> e : picked.entrySet()) {
            List<Integer> list = new ArrayList<>();
            for (int n : e.getValue()) if (force || !epTarget.containsKey(key(e.getKey(), n))) list.add(n);
            if (!list.isEmpty()) todo.put(e.getKey(), list);
        }
        if (todo.isEmpty()) { invalidate(); return; }
        seriesBusy = "جارٍ البحث عن الفيديو والتحقق منه…";
        buildSeasons();
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                String err = null;
                try {
                    for (final Map.Entry<Integer, List<Integer>> e : todo.entrySet()) {
                        final int season = e.getKey();
                        int done = 0;
                        for (List<Integer> chunk : WnLogic.chunk(e.getValue(), WnLogic.VIDEO_CHUNK)) {
                            Map<Integer, JSONObject> got = WnPipeline.resolveEpisodes(tmdb, season, chunk, null, "series:" + tmdb);
                            synchronized (epTarget) {
                                for (Map.Entry<Integer, JSONObject> g : got.entrySet()) epTarget.put(key(season, g.getKey()), g.getValue());
                            }
                            done += chunk.size();
                            final int fd = done;
                            final int total = e.getValue().size();
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    if (!alive()) return;
                                    seriesBusy = "الموسم " + season + " — فحص الفيديو " + fd + "/" + total;
                                    buildSeasons();
                                }
                            });
                        }
                    }
                } catch (CxApi.Failure f) {
                    err = f.getMessage();
                }
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!alive()) return;
                        seriesBusy = "";
                        buildSeasons();
                        if (fe != null) CgUi.toast(WnDetailActivity.this, "تعذر إكمال فحص الفيديو: " + fe);
                        invalidate();
                    }
                });
            }
        });
    }

    private void fetchEpisodeSubtitles() {
        if (pickedCount() == 0) { CgUi.toast(this, "حدّد حلقات أولاً."); return; }
        final TreeMap<Integer, List<Integer>> work = new TreeMap<>();
        for (Map.Entry<Integer, TreeSet<Integer>> e : picked.entrySet()) work.put(e.getKey(), new ArrayList<>(e.getValue()));
        final String lang = "auto".equals(subLang) ? "auto" : subLang;
        showLoading("جارٍ البحث عن ترجمات الحلقات…");
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                int found = 0;
                int total = 0;
                int i = 0;
                for (Map.Entry<Integer, List<Integer>> e : work.entrySet()) total += e.getValue().size();
                for (Map.Entry<Integer, List<Integer>> e : work.entrySet()) {
                    for (int n : e.getValue()) {
                        i++;
                        final int fi = i;
                        final int ft = total;
                        final String code = WnLogic.epCode(e.getKey(), n);
                        runOnUiThread(new Runnable() {
                            @Override public void run() { if (alive()) loadingMessage("ترجمة " + code + " (" + fi + "/" + ft + ")"); }
                        });
                        String[] r = WnPipeline.fetchBestSubtitle("series", tmdb, e.getKey(), n, lang);
                        synchronized (epSub) { epSub.put(key(e.getKey(), n), r[0]); }
                        if (r[0].length() > 0) found++;
                    }
                }
                final int ff = found;
                final int ftotal = total;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        hideLoading();
                        if (!alive()) return;
                        buildSeasons();
                        invalidate();
                        CgUi.info(WnDetailActivity.this, "الترجمات", "وُجدت وفُحصت ترجمة لـ " + ff + " من " + ftotal + " حلقة. الباقي سيُستورد بدون ترجمة (اختياري).");
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ validation

    /** Something changed: the old report no longer applies. Re-validate (debounced) once the inputs are ready. */
    private void invalidate() {
        report = null;
        ui().removeCallbacks(validateRunner);
        ui().postDelayed(validateRunner, 450);
        renderReport();
    }

    private final Runnable validateRunner = new Runnable() {
        @Override public void run() { if (alive()) runValidate(); }
    };

    /** The picked source (movie) — the admin may have chosen a non-default one. */
    private JSONObject chosenMovieSource() {
        JSONArray a = movieTarget == null ? null : movieTarget.optJSONArray("sources");
        if (a != null && movieSrc >= 0 && movieSrc < a.length()) {
            JSONObject s = a.optJSONObject(movieSrc);
            if (s != null && s.optBoolean("importable", false) && s.optString("token").length() > 0) return s;
        }
        return WnLogic.bestSource(movieTarget);
    }

    private JSONObject moviePlanChosen(String id) {
        JSONObject src = chosenMovieSource();
        JSONObject plan = WnLogic.basePlan("movie", tmdb, placement(), flags(), id);
        WnLogic.withMovie(plan, src == null ? "" : src.optString("token"), src == null ? null : WnLogic.fallbackTokens(movieTarget, src), subToken);
        return plan;
    }

    /** series: season -> chunks of episode entries (video token of each episode's OWN target). */
    private TreeMap<Integer, List<List<JSONObject>>> seriesChunks() {
        TreeMap<Integer, List<List<JSONObject>>> out = new TreeMap<>();
        for (Map.Entry<Integer, TreeSet<Integer>> e : picked.entrySet()) {
            List<JSONObject> entries = new ArrayList<>();
            for (int n : e.getValue()) {
                JSONObject t = epTarget.get(key(e.getKey(), n));
                JSONObject best = WnLogic.bestSource(t);
                String sub = epSub.get(key(e.getKey(), n));
                entries.add(WnLogic.episodeEntry(n, best == null ? "" : best.optString("token"), best == null ? null : WnLogic.fallbackTokens(t, best), sub == null ? "" : sub));
            }
            out.put(e.getKey(), WnLogic.chunk(entries, WnLogic.IMPORT_CHUNK));
        }
        return out;
    }

    private void runValidate() {
        if (validating) { invalidate(); return; }
        if (series && pickedCount() == 0) { report = null; renderReport(); return; }
        if (!series && movieTarget == null) return;
        if (series && !seriesBusy.isEmpty()) return;
        validating = true;
        renderReport();
        final TreeMap<Integer, List<List<JSONObject>>> chunks = series ? seriesChunks() : null;
        final String id = WnLogic.newRequestId("v");
        final JSONObject mp = series ? null : moviePlanChosen(id);
        final WnLogic.Placement pl = placement();
        final WnLogic.Flags fl = flags();
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                JSONObject merged = null;
                String err = null;
                try {
                    List<JSONObject> reports = new ArrayList<>();
                    if (!series) {
                        reports.add(WnApi.validate(mp).optJSONObject("report"));
                    } else {
                        for (Map.Entry<Integer, List<List<JSONObject>>> e : chunks.entrySet()) {
                            for (List<JSONObject> chunk : e.getValue()) {
                                JSONObject plan = WnLogic.basePlan("series", tmdb, pl, fl, id);
                                WnLogic.withSeason(plan, e.getKey(), chunk);
                                reports.add(WnApi.validate(plan).optJSONObject("report"));
                            }
                        }
                    }
                    reports.removeAll(java.util.Collections.singleton((JSONObject) null));
                    merged = WnLogic.mergeReports(reports);
                } catch (CxApi.Failure f) {
                    err = f.getMessage();
                }
                final JSONObject fm = merged;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        validating = false;
                        if (!alive()) return;
                        report = fm;
                        if (fe != null) CgUi.toast(WnDetailActivity.this, "تعذر التحقق: " + fe);
                        renderReport();
                    }
                });
            }
        });
    }

    private void renderReport() {
        if (reportBox == null) return;
        reportBox.removeAllViews();
        reportBox.addView(WnUi.sectionHeader(this, "التحقق قبل الاستيراد", "يفحص الخادم البيانات والبوستر والتكرار والفيديو والترجمة والقسم والحلقات."));
        if (validating) {
            LinearLayout w = CgUi.hbox(this);
            w.addView(CgUi.spinner(this), new LinearLayout.LayoutParams(CgUi.dp(this, 24), CgUi.dp(this, 24)));
            w.addView(CgUi.muted(this, "  جارٍ التحقق…"));
            reportBox.addView(w);
        } else if (report == null) {
            reportBox.addView(CgUi.muted(this, series && pickedCount() == 0 ? "حدّد حلقات للتحقق منها." : "بانتظار نتائج البحث عن الفيديو…"));
        } else {
            reportBox.addView(WnUi.reportView(this, report));
            if (report.optBoolean("requires_ack", false)) {
                LinearLayout a = WnUi.glass(this);
                a.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.WARN, 0x18), 16, CgCfg.WARN));
                a.addView(CgUi.text(this, "إقرار مطلوب", 14, CgCfg.WARN, true));
                a.addView(CgUi.muted(this, "مصدر الفيديو مشغّل مضمَّن (iframe) أو مزوّد يمنع الفحص الآلي: لا يمكن للخادم إثبات أنه سيُشغَّل فعلاً. بالموافقة تتحمل مسؤولية إضافته، وسيُسجَّل في السجل أنه غير مُتحقَّق."));
                final CgUi.Toggle t = CgUi.toggle(this, "أقرّ وأريد الاستيراد", ack);
                t.onChange(new Runnable() {
                    @Override public void run() { ack = t.on; updateImportButton(); }
                });
                a.addView(t);
                a.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
                reportBox.addView(a);
            }
        }
        updateImportButton();
    }

    // ------------------------------------------------------------------ the import

    private void buildActions() {
        actionBox.removeAllViews();
        LinearLayout opts = WnUi.glass(this);
        CgUi.Toggle n = CgUi.toggle(this, "إشعار المستخدمين على الهاتف بالمحتوى الجديد", notify);
        n.onChange(new Runnable() { @Override public void run() { notify = !notify; } });
        opts.addView(n);
        CgUi.Toggle s = CgUi.toggle(this, "اشتراط وجود ترجمة (وإلا لا يُستورد)", subRequired);
        s.onChange(new Runnable() { @Override public void run() { subRequired = !subRequired; invalidate(); } });
        opts.addView(s);
        TextView ql = CgUi.muted(this, "الجودة (تُسجَّل كما تعلنها أنت)");
        ql.setPadding(0, CgUi.dp(this, 6), 0, CgUi.dp(this, 4));
        opts.addView(ql);
        CgUi.ChipBar qb = new CgUi.ChipBar(this);
        final String[][] qs = {{"بدون", ""}, {"720p", "720p"}, {"1080p", "1080p"}, {"4K", "4K"}};
        final List<TextView> qChips = new ArrayList<>();
        for (final String[] q : qs) {
            TextView t = qb.add(q[0], q[1].equals(quality));
            qChips.add(t);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    quality = q[1];
                    for (int i = 0; i < qs.length; i++) CgUi.setChipState(qChips.get(i), qs[i][1].equals(quality));
                    invalidate();
                }
            });
        }
        opts.addView(qb.scroll, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, -12, 0, -12, 0));
        opts.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        actionBox.addView(opts);
        importBtn = CgUi.button(this, "إضافة إلى Cigram", 0);
        importBtn.setTextSize(16);
        importBtn.setMinHeight(CgUi.dp(this, 58));
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onImportClicked(); }
        });
        actionBox.addView(importBtn, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        actionBox.addView(CgUi.space(this, 20));
        updateImportButton();
    }

    private boolean importable() {
        return !importing && !validating && WnLogic.canImport(report, ack);
    }

    private void updateImportButton() {
        if (importBtn == null) return;
        importBtn.setAlpha(importable() ? 1f : 0.45f);
        importBtn.setText(importing ? "جارٍ الإضافة…" : "إضافة إلى Cigram");
    }

    private void onImportClicked() {
        if (importing) return;
        if (validating || report == null) { CgUi.toast(this, "انتظر انتهاء التحقق أولاً."); return; }
        if (!WnLogic.reportOk(report)) { CgUi.info(this, WnLogic.stateLabel(report.optString("state")), WnLogic.firstProblem(report)); return; }
        if (!WnLogic.canImport(report, ack)) { CgUi.info(this, "إقرار مطلوب", "فعّل «أقرّ وأريد الاستيراد» في بطاقة الإقرار أعلاه لأن مصدر الفيديو لا يمكن إثبات تشغيله من الخادم."); return; }
        String what = series ? pickedCount() + " حلقة من «" + det.optString("title") + "»" : "«" + det.optString("title") + "»";
        String where = sectionId.length() > 0 ? "قسم موجود" : newSection.length() > 0 ? "قسم جديد «" + newSection + "»" : "بدون قسم";
        CgUi.confirm(this, "إضافة إلى Cigram", "سيُضاف " + what + " (" + where + "). يمكن التراجع من «سجل العمليات».", "أضف الآن", false, new Runnable() {
            @Override public void run() { startImport(); }
        });
    }

    private void startImport() {
        importing = true;
        updateImportButton();
        if (importNonce == null) importNonce = WnLogic.newRequestId("i");
        final String nonce = importNonce;
        final TreeMap<Integer, List<List<JSONObject>>> chunks = series ? seriesChunks() : null;
        final JSONObject mp = series ? null : moviePlanChosen("m" + tmdb + "-" + nonce);
        final WnLogic.Placement pl = placement();
        final WnLogic.Flags fl = flags();
        int total = 0;
        if (series) for (List<List<JSONObject>> c : chunks.values()) for (List<JSONObject> l : c) total += l.size(); else total = 1;
        final int ftotal = total;
        showLoading("جارٍ الإضافة إلى Cigram…");
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                final List<String> undo = new ArrayList<>();
                final List<String> problems = new ArrayList<>();
                int created = 0;
                int skipped = 0;
                int done = 0;
                boolean definitive = true;
                String failState = "";
                if (!series) {
                    try {
                        JSONObject r = WnApi.importPlan(mp);
                        created = 1;
                        if (r.optString("undo_id").length() > 0) undo.add(r.optString("undo_id"));
                    } catch (CxApi.Failure f) {
                        WnPipeline.Result res = new WnPipeline.Result("movie:" + tmdb, det.optString("title"));
                        WnPipeline.fromFailure(res, f);
                        problems.add(res.message);
                        failState = res.state;
                        definitive = !res.retryable;
                    }
                } else {
                    for (Map.Entry<Integer, List<List<JSONObject>>> e : chunks.entrySet()) {
                        int idx = 0;
                        for (List<JSONObject> chunk : e.getValue()) {
                            final int fd = done;
                            runOnUiThread(new Runnable() {
                                @Override public void run() { if (alive()) loadingMessage("جارٍ الإضافة… " + fd + "/" + ftotal + " حلقة"); }
                            });
                            JSONObject plan = WnLogic.basePlan("series", tmdb, pl, fl, "s" + tmdb + "-" + nonce + "-s" + e.getKey() + "c" + idx);
                            WnLogic.withSeason(plan, e.getKey(), chunk);
                            idx++;
                            try {
                                JSONObject r = WnApi.importPlan(plan);
                                JSONArray cr = r.optJSONArray("created");
                                for (int i = 0; cr != null && i < cr.length(); i++) if ("episode".equals(cr.optJSONObject(i).optString("type"))) created++;
                                JSONArray sk = r.optJSONArray("skipped");
                                skipped += sk == null ? 0 : sk.length();
                                if (r.optString("undo_id").length() > 0) undo.add(r.optString("undo_id"));
                            } catch (CxApi.Failure f) {
                                WnPipeline.Result res = new WnPipeline.Result("series:" + tmdb, det.optString("title"));
                                WnPipeline.fromFailure(res, f);
                                problems.add("الموسم " + e.getKey() + ": " + res.message);
                                if (res.retryable) definitive = false;
                            }
                            done += chunk.size();
                        }
                    }
                }
                final int fc = created;
                final int fs = skipped;
                final boolean fdef = definitive;
                final String fstate = failState;
                runOnUiThread(new Runnable() {
                    @Override public void run() { finishImport(fc, fs, undo, problems, fdef, fstate); }
                });
            }
        });
    }

    private void finishImport(int created, int skipped, List<String> undo, List<String> problems, boolean definitive, String failState) {
        importing = false;
        hideLoading();
        if (definitive) importNonce = null; // a retry after a transient failure keeps the id, so the Worker replays instead of duplicating
        if (!alive()) return;
        if (created > 0) {
            WnUi.dirty = true;
            CgData.load(this, new CgHttp.Done<CgData>() {
                @Override public void ok(CgData d) { }
                @Override public void fail(String message) { }
            });
        }
        StringBuilder sb = new StringBuilder();
        if (created > 0) sb.append(series ? "أُضيفت " + created + " حلقة." : "أُضيف الفيلم إلى Cigram وأصبح متاحاً في التطبيق.");
        if (skipped > 0) sb.append("\nتخطّي ").append(skipped).append(" حلقة موجودة.");
        for (String p : problems) sb.append("\n• ").append(p);
        if (created == 0 && problems.isEmpty()) sb.append("لم تتم إضافة شيء.");
        String title = created > 0 ? (problems.isEmpty() ? WnLogic.stateLabel("imported") : WnLogic.stateLabel("partial")) : failState.length() > 0 ? WnLogic.stateLabel(failState) : WnLogic.stateLabel("import_failed");
        CgUi.info(this, title, sb.toString());
        if (created > 0 && undo.size() == 1) pendingUndo = new String[]{"إضافة «" + det.optString("title") + "»", undo.get(0)};
        updateImportButton();
        if (created > 0) {
            picked.clear();
            epTarget.clear();
            epSub.clear();
            report = null;
            loadDetails();
        }
    }
}
