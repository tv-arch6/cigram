package com.my.newproject;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The information form shared by "add movie" and "add series": basics, images with previews and aspect hints,
 * section and quality, external ids and a slug that is generated, editable and duplicate-checked.
 * It reads from / writes to a JSONObject state so drafts and editing share one code path.
 */
final class CxContentForm {

    private static final Pattern SLUG = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");
    private static final String[] QUALITIES = {"HD", "FHD", "4K", "WEB-DL", "BluRay", "CAM"};

    private final CxBase host;
    private final JSONObject st;
    private final boolean series;
    private final String editId;
    private final Runnable changed;
    private final Handler h = new Handler(Looper.getMainLooper());

    private CxUi.Field title;
    private CxUi.Field original;
    private CxUi.Field year;
    private CxUi.Field category;
    private CxUi.Field description;
    private CxUi.Field poster;
    private CxUi.Field backdrop;
    private CxUi.Field slug;
    private CxUi.Field imdbId;
    private CxUi.Field tmdb;
    private CxUi.PosterBox posterBox;
    private CxUi.PosterBox backdropBox;
    private TextView sectionButton;
    private TextView sectionHint;
    private LinearLayout suggestBox;
    private CxUi.Flow qualityFlow;
    private TextView slugAction;
    private boolean slugLocked;
    private int slugState; // 0 unknown, 1 ok, 2 taken / invalid
    private String slugSuggestion = "";
    private int slugToken;
    private final Runnable slugCheck = new Runnable() {
        @Override
        public void run() {
            checkSlugNow();
        }
    };
    private final Runnable posterPreview = new Runnable() {
        @Override
        public void run() {
            posterBox.set(poster.text());
        }
    };
    private final Runnable backdropPreview = new Runnable() {
        @Override
        public void run() {
            backdropBox.set(backdrop.text());
        }
    };

    CxContentForm(CxBase host, JSONObject st, boolean series, String editId, Runnable changed) {
        this.host = host;
        this.st = st;
        this.series = series;
        this.editId = editId == null ? "" : editId;
        this.changed = changed;
    }

    private void touch() {
        if (changed != null) changed.run();
    }

    // ------------------------------------------------------------------------ build

    void build(LinearLayout parent) {
        Activity a = host;
        // ---------- basics
        CxUi.Section basics = new CxUi.Section(a, "الأساسيات", series ? "اسم المسلسل ووصفه" : "اسم الفيلم ووصفه", false, true);
        title = new CxUi.Field(a, series ? "اسم المسلسل" : "اسم الفيلم", "الاسم كما سيظهر في التطبيق", CxUi.T_TEXT, true);
        title.set(CxJ.s(st, "title"));
        title.onChange(new Runnable() {
            @Override
            public void run() {
                CxJ.put(st, "title", title.text());
                autoSlug();
                touch();
            }
        });
        basics.add(title.view);
        original = new CxUi.Field(a, "الاسم الأصلي", "Original title", CxUi.T_LTR, false);
        original.set(CxJ.s(st, "original_title"));
        original.onChange(new Runnable() {
            @Override
            public void run() {
                CxJ.put(st, "original_title", original.text());
                autoSlug();
                touch();
            }
        });
        basics.add(original.view);
        year = new CxUi.Field(a, "السنة", "2024", CxUi.T_NUMBER, false);
        year.set(CxJ.s(st, "year"));
        year.onChange(new Runnable() {
            @Override
            public void run() {
                CxJ.put(st, "year", year.text());
                autoSlug();
                touch();
            }
        });
        basics.add(year.view);
        category = new CxUi.Field(a, "التصنيفات", "أكشن، دراما", CxUi.T_TEXT, false);
        category.set(CxJ.s(st, "category"));
        category.onChange(new Runnable() {
            @Override
            public void run() {
                CxJ.put(st, "category", category.text());
                touch();
            }
        });
        basics.add(category.view);
        JSONArray genres = st.optJSONArray("genres");
        if (genres != null && genres.length() > 0) {
            CxUi.Flow flow = new CxUi.Flow(a);
            for (int i = 0; i < genres.length(); i++) {
                final String g = genres.optString(i);
                if (g.length() == 0) continue;
                flow.addView(CxUi.chip(a, "+ " + g, false, new Runnable() {
                    @Override
                    public void run() {
                        addGenre(g);
                    }
                }));
            }
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP);
            fl.bottomMargin = CgUi.dp(a, 12);
            basics.add(wrap(flow, fl));
        }
        description = new CxUi.Field(a, "الوصف", "ملخص القصة", CxUi.T_MULTI, false);
        description.set(CxJ.s(st, "description"));
        description.onChange(new Runnable() {
            @Override
            public void run() {
                CxJ.put(st, "description", description.text());
                touch();
            }
        });
        basics.add(description.view);
        parent.addView(basics);

        // ---------- images
        CxUi.Section images = new CxUi.Section(a, "الصور", "بوستر عمودي 2:3 وخلفية عريضة 16:9 — التنبيهات هنا لا تمنع النشر", false, true);
        posterBox = new CxUi.PosterBox(a, 110, 1.5f, "2:3");
        poster = new CxUi.Field(a, "رابط البوستر", "https://...", CxUi.T_URL, false);
        poster.set(CxJ.s(st, "poster"));
        poster.onChange(new Runnable() {
            @Override
            public void run() {
                CxJ.put(st, "poster", poster.text());
                h.removeCallbacks(posterPreview);
                h.postDelayed(posterPreview, 500);
                touch();
            }
        });
        posterBox.onHint(new CxUi.RatioHint() {
            @Override
            public void onHint(String w) {
                if (poster.text().length() == 0) return;
                if (w.length() > 0) poster.warn(w);
            }
        });
        LinearLayout posterRow = CgUi.hbox(a);
        posterRow.setGravity(android.view.Gravity.TOP);
        posterRow.addView(posterBox);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        plp.setMargins(CgUi.dp(a, 12), 0, 0, 0);
        posterRow.addView(poster.view, plp);
        images.add(posterRow);
        backdropBox = new CxUi.PosterBox(a, 240, 0.5625f, "16:9");
        backdrop = new CxUi.Field(a, "رابط الخلفية", "https://...", CxUi.T_URL, false);
        backdrop.set(CxJ.s(st, "backdrop"));
        backdrop.onChange(new Runnable() {
            @Override
            public void run() {
                CxJ.put(st, "backdrop", backdrop.text());
                h.removeCallbacks(backdropPreview);
                h.postDelayed(backdropPreview, 500);
                touch();
            }
        });
        backdropBox.onHint(new CxUi.RatioHint() {
            @Override
            public void onHint(String w) {
                if (backdrop.text().length() == 0) return;
                if (w.length() > 0) backdrop.warn(w);
            }
        });
        TextView bl = CgUi.muted(a, "الخلفية (تُستخدم في الكاروسيل والمعاينة)");
        bl.setPadding(0, CgUi.dp(a, 4), 0, CgUi.dp(a, 4));
        images.add(bl);
        images.add(backdropBox);
        images.add(CgUi.space(a, 8));
        images.add(backdrop.view);
        parent.addView(images);
        posterBox.set(poster.text());
        backdropBox.set(backdrop.text());

        // ---------- placement
        CxUi.Section place = new CxUi.Section(a, "القسم والجودة", "أين يظهر المحتوى في الصفحة الرئيسية", false, true);
        sectionButton = CgUi.button(a, "", 1);
        sectionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickSection();
            }
        });
        TextView sl = CgUi.text(a, "القسم", 12, CgCfg.MUTED, false);
        sl.setPadding(CgUi.dp(a, 4), 0, 0, CgUi.dp(a, 4));
        place.add(sl);
        place.add(sectionButton);
        sectionHint = CgUi.text(a, "", 11.5f, CgCfg.WARN, false);
        sectionHint.setPadding(CgUi.dp(a, 6), CgUi.dp(a, 3), 0, 0);
        place.add(sectionHint);
        suggestBox = CgUi.vbox(a);
        place.add(suggestBox);
        TextView ql = CgUi.text(a, "الجودة", 12, CgCfg.MUTED, false);
        ql.setPadding(CgUi.dp(a, 4), CgUi.dp(a, 12), 0, CgUi.dp(a, 4));
        place.add(ql);
        qualityFlow = new CxUi.Flow(a);
        place.add(qualityFlow);
        parent.addView(place);
        refreshSection();
        refreshQuality();

        // ---------- slug
        CxUi.Section slugSec = new CxUi.Section(a, "الرابط (Slug)", series ? "اسم مجلد المسلسل في R2" : "معرّف الرابط", false, true);
        slug = new CxUi.Field(a, "Slug", "my-title-2024", CxUi.T_LTR, false);
        slug.set(CxJ.s(st, "slug"));
        slugLocked = editId.length() > 0 && CxJ.s(st, "slug").length() > 0;
        slug.onChange(new Runnable() {
            @Override
            public void run() {
                CxJ.put(st, "slug", slug.text());
                try {
                    st.put("slug_edited", true);
                } catch (Exception ignored) { }
                slugState = 0;
                scheduleSlugCheck();
                touch();
            }
        });
        slugSec.add(slug.view);
        LinearLayout slugRow = CgUi.hbox(a);
        slugAction = CgUi.smallButton(a, slugLocked ? "🔒 تعديل يدوي" : "↻ توليد من الاسم", 1);
        slugAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSlugAction();
            }
        });
        slugRow.addView(slugAction, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        slugSec.add(slugRow);
        if (slugLocked) {
            slug.enabled(false);
            slug.hint("الـ Slug منشور ولا يتغيّر تلقائياً. تغييره يدوياً قد يكسر الروابط القديمة.");
        } else {
            slug.hint("يُولَّد تلقائياً ويمكنك تعديله. أحرف إنجليزية وأرقام و . _ - فقط.");
            if (slug.text().length() > 0) scheduleSlugCheck();
        }
        parent.addView(slugSec);

        // ---------- external ids
        CxUi.Section ids = new CxUi.Section(a, "المعرّفات الخارجية", "TMDB / IMDb", true, false);
        tmdb = new CxUi.Field(a, "TMDB ID", "", CxUi.T_NUMBER, false);
        tmdb.set(CxJ.s(st, "tmdb_id"));
        tmdb.onChange(new Runnable() {
            @Override
            public void run() {
                CxJ.put(st, "tmdb_id", tmdb.text());
                touch();
            }
        });
        ids.add(tmdb.view);
        imdbId = new CxUi.Field(a, "IMDb ID", "tt1234567", CxUi.T_LTR, false);
        imdbId.set(CxJ.s(st, "imdb_id"));
        imdbId.onChange(new Runnable() {
            @Override
            public void run() {
                CxJ.put(st, "imdb_id", imdbId.text());
                touch();
            }
        });
        ids.add(imdbId.view);
        String rating = CxJ.s(st, "rating");
        if (rating.length() > 0) ids.add(CgUi.muted(a, "تقييم TMDB: ★ " + rating));
        ids.summary((tmdb.text().length() > 0 ? "TMDB " + tmdb.text() : "") + (imdbId.text().length() > 0 ? "  " + imdbId.text() : ""));
        parent.addView(ids);
    }

    private View wrap(View v, LinearLayout.LayoutParams lp) {
        v.setLayoutParams(lp);
        return v;
    }

    private void addGenre(String g) {
        String cur = category.text();
        if (cur.contains(g)) return;
        category.set(cur.length() == 0 ? g : cur + "، " + g);
        CxJ.put(st, "category", category.text());
        touch();
    }

    // ------------------------------------------------------------------------ section

    private ArrayList<CxUi.Option> sectionOptions() {
        ArrayList<CxUi.Option> out = new ArrayList<CxUi.Option>();
        CgData d = CgData.cur();
        if (d == null) return out;
        for (int i = 0; i < d.sections.size(); i++) {
            JSONObject s = d.sections.get(i);
            String type = CxJ.s(s, "type");
            if (series && "movie".equals(type)) continue;
            if (!series && "series".equals(type)) continue;
            out.add(new CxUi.Option(CxJ.s(s, "id"), CxJ.s(s, "name"), s.optBoolean("enabled", true) ? "" : "(متوقف)", null));
        }
        return out;
    }

    private void pickSection() {
        CxUi.pick(host, "اختر القسم", sectionOptions(), CxJ.s(st, "section_id"), "إنشاء قسم جديد", new CgUi.Callback<CxUi.Option>() {
            @Override
            public void run(CxUi.Option o) {
                CxJ.put(st, "section_id", o.id);
                CxJ.put(st, "section_name", o.title);
                refreshSection();
                touch();
            }
        }, new CgUi.Callback<String>() {
            @Override
            public void run(String name) {
                createSection(name);
            }
        });
    }

    private void createSection(final String name) {
        host.task("جارٍ إنشاء القسم...", new CxBase.Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("name", name);
                b.put("type", series ? "series" : "movie");
                String slugText = CgData.slugify(name);
                if (slugText.length() > 0) b.put("slug", slugText);
                return CxApi.post("/admin/sections/create", b);
            }
        }, new CxBase.Ok<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                JSONObject s = r.optJSONObject("section");
                if (s == null) return;
                CgData d = CgData.cur();
                if (d != null) d.sections.add(s);
                CxJ.put(st, "section_id", CxJ.s(s, "id"));
                CxJ.put(st, "section_name", CxJ.s(s, "name"));
                refreshSection();
                touch();
                host.snack("تم إنشاء القسم «" + name + "» واختياره.", CxUi.GOOD);
            }
        }, new Runnable() {
            @Override
            public void run() {
                createSection(name);
            }
        });
    }

    private void refreshSection() {
        String name = CxJ.s(st, "section_name");
        String id = CxJ.s(st, "section_id");
        CgUi.set(sectionButton, name.length() > 0 ? "📁  " + name : id.length() > 0 ? "📁  " + id : "اختر القسم ▾");
        CgUi.set(sectionHint, id.length() == 0 ? "⚠ لم يُختر قسم — لن يظهر المحتوى في أي قسم بالصفحة الرئيسية." : "");
        suggestBox.removeAllViews();
        JSONArray sug = st.optJSONArray("suggested_sections");
        if (sug != null && sug.length() > 0 && id.length() == 0) {
            TextView t = CgUi.muted(host, "مقترح من تصنيفات TMDB (القرار لك):");
            t.setPadding(CgUi.dp(host, 4), CgUi.dp(host, 8), 0, CgUi.dp(host, 4));
            suggestBox.addView(t);
            CxUi.Flow flow = new CxUi.Flow(host);
            for (int i = 0; i < sug.length(); i++) {
                final JSONObject o = sug.optJSONObject(i);
                if (o == null) continue;
                flow.addView(CxUi.chip(host, CxJ.s(o, "name") + " ← " + CxJ.s(o, "reason"), false, new Runnable() {
                    @Override
                    public void run() {
                        CxJ.put(st, "section_id", CxJ.s(o, "id"));
                        CxJ.put(st, "section_name", CxJ.s(o, "name"));
                        refreshSection();
                        touch();
                    }
                }));
            }
            suggestBox.addView(flow);
        }
    }

    // ------------------------------------------------------------------------ quality

    private void refreshQuality() {
        qualityFlow.removeAllViews();
        String cur = CxJ.s(st, "quality");
        boolean known = false;
        for (int i = 0; i < QUALITIES.length; i++) {
            final String q = QUALITIES[i];
            boolean sel = q.equalsIgnoreCase(cur);
            if (sel) known = true;
            qualityFlow.addView(CxUi.chip(host, q, sel, new Runnable() {
                @Override
                public void run() {
                    CxJ.put(st, "quality", q.equalsIgnoreCase(CxJ.s(st, "quality")) ? "" : q);
                    refreshQuality();
                    touch();
                }
            }));
        }
        if (cur.length() > 0 && !known) {
            qualityFlow.addView(CxUi.chip(host, cur, true, new Runnable() {
                @Override
                public void run() {
                    CxJ.put(st, "quality", "");
                    refreshQuality();
                    touch();
                }
            }));
        }
        qualityFlow.addView(CxUi.chip(host, "أخرى…", false, new Runnable() {
            @Override
            public void run() {
                CgUi.input(host, "الجودة", "مثال: 1080p", CxJ.s(st, "quality"), false, new CgUi.Callback<String>() {
                    @Override
                    public void run(String v) {
                        CxJ.put(st, "quality", v);
                        refreshQuality();
                        touch();
                    }
                });
            }
        }));
    }

    // -------------------------------------------------------------------------- slug

    private String slugBase() {
        String base = CgData.slugify(CxJ.firstNonEmpty(CxJ.s(st, "original_title"), CxJ.s(st, "title")));
        if (base.length() == 0) {
            String tm = CxJ.s(st, "tmdb_id");
            base = tm.length() > 0 ? (series ? "series-" : "movie-") + tm : "";
        }
        String y = CxJ.s(st, "year");
        if (!series && base.length() > 0 && y.matches("(19|20)\\d\\d") && !base.endsWith(y)) base = base + "-" + y;
        return base;
    }

    private void autoSlug() {
        if (slugLocked || st.optBoolean("slug_edited", false) || slug == null) return;
        String g = slugBase();
        slug.set(g);
        CxJ.put(st, "slug", g);
        slugState = 0;
        scheduleSlugCheck();
    }

    private void onSlugAction() {
        if (slugLocked) {
            CgUi.confirm(host, "تعديل الـ Slug المنشور؟",
                    "الـ Slug الحالي «" + slug.text() + "» مستخدم في الروابط. تغييره قد يكسر روابط قديمة" + (series ? " ولا ينقل ملفات R2" : "") + ". هل تريد المتابعة؟",
                    "تعديل يدوي", true, new Runnable() {
                        @Override
                        public void run() {
                            slugLocked = false;
                            slug.enabled(true);
                            slug.hint("تعديل يدوي — تأكد من التحقق قبل الحفظ.");
                            CgUi.set(slugAction, "↻ توليد من الاسم");
                        }
                    });
            return;
        }
        String g = slugBase();
        if (g.length() == 0) {
            slug.error("اكتب الاسم أولاً ليُولَّد الـ Slug.");
            return;
        }
        try {
            st.put("slug_edited", false);
        } catch (Exception ignored) { }
        slug.set(g);
        CxJ.put(st, "slug", g);
        slugState = 0;
        scheduleSlugCheck();
        touch();
    }

    private void scheduleSlugCheck() {
        h.removeCallbacks(slugCheck);
        String s = slug.text();
        if (s.length() == 0) {
            slug.clear();
            return;
        }
        if (!SLUG.matcher(s).matches() || s.length() > 80) {
            slugState = 2;
            slug.message(CxUi.ERROR, "Slug غير صالح — أحرف إنجليزية وأرقام و . _ - فقط ولا يبدأ برمز.");
            return;
        }
        h.postDelayed(slugCheck, 600);
    }

    private void checkSlugNow() {
        final String s = slug.text();
        if (s.length() == 0) return;
        final int my = ++slugToken;
        CgHttp.async(host, new CgHttp.Work<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("slug", s);
                b.put("kind", series ? "series" : "movie");
                if (editId.length() > 0) b.put("exclude_id", editId);
                CgHttp.Res r = CgHttp.apiPost("/admin/check-slug", b);
                if (r.json == null) throw new Exception("تعذر فحص الـ Slug.");
                return r.json;
            }
        }, new CgHttp.Done<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                if (my != slugToken || !s.equals(slug.text())) return;
                boolean available = r.optBoolean("available", false);
                slugSuggestion = r.optString("suggestion", "");
                slugState = available ? 1 : 2;
                if (available) {
                    slug.ok(r.optString("message", "Slug متاح."));
                } else {
                    String m = r.optString("message", "هذا الـ Slug غير متاح.");
                    if (slugSuggestion.length() > 0) m += " — المقترح: " + slugSuggestion;
                    slug.error(m);
                }
            }

            @Override
            public void fail(String message) {
                if (my != slugToken) return;
                slugState = 0;
                slug.warn("تعذر فحص الـ Slug الآن (سيُفحص عند النشر).");
            }
        });
    }

    // ---------------------------------------------------------------------- validate

    /** Inline validation. Returns false (and focuses the first problem) only for real errors. */
    boolean validate() {
        pull();
        View first = null;
        if (title.text().length() == 0) {
            title.error("الاسم مطلوب.");
            first = title.view;
        }
        String y = year.text();
        if (y.length() > 0 && !y.matches("(18|19|20|21)\\d\\d")) {
            year.error("السنة يجب أن تكون 4 أرقام (مثل 2024).");
            if (first == null) first = year.view;
        }
        String p = poster.text();
        if (p.length() > 0 && !CxJ.isUrl(p)) {
            poster.error("رابط البوستر يجب أن يبدأ بـ http:// أو https://");
            if (first == null) first = poster.view;
        }
        String b = backdrop.text();
        if (b.length() > 0 && !CxJ.isUrl(b)) {
            backdrop.error("رابط الخلفية يجب أن يبدأ بـ http:// أو https://");
            if (first == null) first = backdrop.view;
        }
        String s = slug.text();
        if (s.length() > 0 && (!SLUG.matcher(s).matches() || s.length() > 80)) {
            slug.error("Slug غير صالح.");
            if (first == null) first = slug.view;
        } else if (s.length() > 0 && slugState == 2) {
            slug.error("هذا الـ Slug غير متاح." + (slugSuggestion.length() > 0 ? " المقترح: " + slugSuggestion : ""));
            if (first == null) first = slug.view;
        }
        String im = imdbId.text();
        if (im.length() > 0 && !im.matches("(?i)tt\\d{5,12}")) {
            imdbId.warn("صيغة IMDb عادةً tt1234567.");
        }
        if (first != null) {
            host.scrollTo(first);
            return false;
        }
        return true;
    }

    /** Copies widget values into the state (already kept in sync; this is a final safety pass). */
    void pull() {
        CxJ.put(st, "title", title.text());
        CxJ.put(st, "original_title", original.text());
        CxJ.put(st, "year", year.text());
        CxJ.put(st, "category", category.text());
        CxJ.put(st, "description", description.text());
        CxJ.put(st, "poster", poster.text());
        CxJ.put(st, "backdrop", backdrop.text());
        CxJ.put(st, "slug", slug.text());
        CxJ.put(st, "tmdb_id", tmdb.text());
        CxJ.put(st, "imdb_id", imdbId.text().toLowerCase(Locale.US));
    }
}
