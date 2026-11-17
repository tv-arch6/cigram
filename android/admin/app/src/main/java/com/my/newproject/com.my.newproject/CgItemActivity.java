package com.my.newproject;

import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Edit screen for a movie / series / episode, and creation of the next episode.
 * Extras: id (edit) or mode=new_episode + series_id + season + episode.
 */
public class CgItemActivity extends CgBase {
    private boolean carouselButton;

    private boolean creating;
    private String itemId = "";
    private String seriesKey = "";
    private int newSeason = 1;
    private int newEpisode = 1;

    private JSONObject original;
    private int kind = CgData.K_MOVIE;
    private final LinkedHashMap<String, EditText> fields = new LinkedHashMap<String, EditText>();
    private CgUi.Toggle featured;
    private CgUi.Toggle hidden;
    private String sectionId = "";
    private String sectionName = "";
    private TextView sectionButton;
    private TextView slugStatus;
    private boolean slugOk = true;

    @Override
    protected String screenTitle() {
        return "تعديل";
    }

    @Override
    protected void onBuild() {
        Intent in = getIntent();
        creating = "new_episode".equals(in.getStringExtra("mode"));
        String id = in.getStringExtra("id");
        itemId = id == null ? "" : id;
        String sk = in.getStringExtra("series_id");
        seriesKey = sk == null ? "" : sk;
        newSeason = in.getIntExtra("season", 1);
        newEpisode = in.getIntExtra("episode", 1);
        ensureData(new Runnable() {
            @Override
            public void run() {
                render();
            }
        });
    }

    private String val(String key) {
        EditText e = fields.get(key);
        return e == null ? "" : e.getText().toString().trim();
    }

    private void render() {
        final CgData d = CgData.cur();
        LinearLayout c = scrollBody();
        fields.clear();
        if (creating) {
            JSONObject h = d.byId(seriesKey);
            if (h == null) {
                // series_id may differ from the header id
                ArrayList<JSONObject> hs = d.seriesHeaders();
                for (int i = 0; i < hs.size(); i++) {
                    if (CgData.seriesKey(hs.get(i)).equals(seriesKey)) h = hs.get(i);
                }
            }
            if (h == null) {
                c.addView(CgUi.empty(this, "المسلسل غير موجود", "لا يمكن إضافة حلقة بدون مسلسل صالح."));
                return;
            }
            kind = CgData.K_EPISODE;
            original = new JSONObject();
            try {
                ArrayList<JSONObject> eps = d.episodesOf(h);
                JSONObject last = null;
                for (int i = 0; i < eps.size(); i++) {
                    if (CgData.n(eps.get(i), "season") == newSeason) last = eps.get(i);
                }
                original.put("title", "الحلقة " + newEpisode);
                original.put("season", String.valueOf(newSeason));
                original.put("episode", String.valueOf(newEpisode));
                original.put("quality", last != null ? CgData.s(last, "quality") : CgData.s(h, "quality"));
                original.put("season_poster", last != null ? CgData.s(last, "season_poster") : "");
                original.put("section_id", CgData.s(h, "section_id"));
                original.put("section_name", CgData.s(h, "section_name"));
            } catch (Exception ignored) { }
            bar.titleView.setText("إضافة حلقة");
            bar.subtitle(CgData.s(h, "title") + " — " + String.format(java.util.Locale.US, "S%02dE%02d", newSeason, newEpisode));
        } else {
            original = d.byId(itemId);
            if (original == null) {
                c.addView(CgUi.empty(this, "العنصر غير موجود", "ربما حُذف."));
                return;
            }
            kind = CgData.kind(original);
            bar.titleView.setText("تعديل " + CgData.kindLabel(kind));
            bar.subtitle(CgData.s(original, "title"));
            if (kind == CgData.K_MOVIE && !carouselButton) {
                carouselButton = true;
                bar.action("كاروسيل", new Runnable() {
                    @Override
                    public void run() {
                        CxNav.carouselAdd(CgItemActivity.this, itemId);
                    }
                });
            }
        }
        sectionId = CgData.s(original, "section_id");
        sectionName = CgData.s(original, "section_name");

        View warn = warningCard();
        if (warn != null) c.addView(warn);
        if (!creating) c.addView(statusCard(d));

        c.addView(CgUi.sectionTitle(this, "البيانات الأساسية"));
        addField(c, "title", "العنوان", false, false);
        if (kind == CgData.K_EPISODE) {
            LinearLayout row = CgUi.hbox(this);
            EditText s = CgUi.numberField(this, "الموسم");
            s.setText(CgData.s(original, "season"));
            EditText e = CgUi.numberField(this, "الحلقة");
            e.setText(CgData.s(original, "episode"));
            fields.put("season", s);
            fields.put("episode", e);
            row.addView(CgUi.labeled(this, "الموسم", s), CgUi.lpWeight(this, 1f, 0, 0, 4, 0));
            row.addView(CgUi.labeled(this, "الحلقة", e), CgUi.lpWeight(this, 1f, 4, 0, 0, 0));
            c.addView(row, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        } else {
            addSlug(c);
        }
        addSection(c);
        if (kind != CgData.K_EPISODE) {
            addField(c, "category", "التصنيف", false, false);
            addField(c, "year", "السنة", false, false);
        }
        addField(c, "quality", "الجودة", false, false);
        if (kind != CgData.K_EPISODE) addField(c, "description", "الوصف", false, true);

        c.addView(CgUi.sectionTitle(this, "الروابط"));
        addField(c, "poster", kind == CgData.K_EPISODE ? "بوستر الحلقة" : "البوستر", true, false);
        if (kind == CgData.K_EPISODE) addField(c, "season_poster", "بوستر الموسم", true, false);
        if (kind != CgData.K_SERIES) {
            addField(c, "video", "رابط الفيديو", true, false);
            addField(c, "subtitle", "رابط الترجمة (SRT)", true, false);
        }

        c.addView(CgUi.sectionTitle(this, "الظهور"));
        LinearLayout vis = CgUi.card(this);
        hidden = CgUi.toggle(this, "مخفي عن المستخدمين", CgData.b(original, "hidden"));
        vis.addView(hidden);
        if (kind != CgData.K_EPISODE) {
            featured = CgUi.toggle(this, "★ محتوى مميز (Featured)", CgData.b(original, "featured"));
            vis.addView(featured);
        }
        vis.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        c.addView(vis);

        TextView save = CgUi.button(this, creating ? "إضافة الحلقة" : "حفظ التغييرات", 0);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        c.addView(save, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 8));

        if (!creating) {
            if (kind != CgData.K_SERIES) {
                TextView subs = CgUi.button(this, "🔤  جلب الترجمة العربية تلقائياً", 1);
                subs.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        CxSubsActivity.open(CgItemActivity.this, itemId);
                    }
                });
                c.addView(subs, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
            }
            c.addView(toolsCard(d));
        }
    }

    // ------------------------------------------------------------- pieces

    private void addField(LinearLayout c, String key, String label, boolean url, boolean multi) {
        EditText e = url ? CgUi.urlField(this, label) : CgUi.field(this, label, multi);
        e.setText(CgData.s(original, key));
        fields.put(key, e);
        c.addView(CgUi.labeled(this, label, e));
    }

    private void addSection(LinearLayout c) {
        sectionButton = CgUi.button(this, sectionText(), 1);
        sectionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgBulk.pickSection(CgItemActivity.this, new CgUi.Callback<JSONObject>() {
                    @Override
                    public void run(JSONObject sec) {
                        sectionId = CgData.s(sec, "id");
                        sectionName = CgData.s(sec, "name");
                        sectionButton.setText(sectionText());
                    }
                });
            }
        });
        c.addView(CgUi.labeled(this, "القسم" + (kind == CgData.K_SERIES ? " (يُنقل معه كل الحلقات)" : ""), sectionButton));
    }

    private String sectionText() {
        return sectionId.length() == 0 ? "اختر القسم" : (sectionName.length() > 0 ? sectionName : sectionId);
    }

    private void addSlug(LinearLayout c) {
        final CgData d = CgData.cur();
        String current = creating ? "" : d.slugOf(original);
        final EditText e = CgUi.urlField(this, "slug");
        e.setText(current);
        CgUi.ltr(e);
        fields.put("slug", e);
        LinearLayout row = CgUi.hbox(this);
        row.addView(e, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        TextView gen = CgUi.smallButton(this, "توليد", 1);
        gen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String suggestion = CgData.slugify(val("title"));
                if (suggestion.length() == 0) {
                    CgUi.toast(CgItemActivity.this, "اكتب العنوان أولاً.");
                    return;
                }
                String published = CgData.cur().slugOf(original);
                if (published.length() > 0 && !published.equals(suggestion)) {
                    CgUi.confirm(CgItemActivity.this, "تغيير Slug منشور",
                            "الـ Slug الحالي «" + published + "» قد يكون مستخدماً في روابط R2 وفي التطبيق. هل تريد استبداله بـ «" + suggestion + "»؟",
                            "استبدل", true, new Runnable() {
                                @Override
                                public void run() {
                                    e.setText(suggestion);
                                    checkSlug();
                                }
                            });
                } else {
                    e.setText(suggestion);
                    checkSlug();
                }
            }
        });
        row.addView(gen, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 6, 0, 0, 0));
        TextView chk = CgUi.smallButton(this, "فحص", 1);
        chk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkSlug();
            }
        });
        row.addView(chk, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 6, 0, 0, 0));
        c.addView(CgUi.labeled(this, "Slug (اسم المجلد/الرابط — لا يتغيّر تلقائياً)", row));
        slugStatus = CgUi.text(this, "", 12.5f, CgCfg.MUTED, false);
        slugStatus.setPadding(CgUi.dp(this, 4), 0, CgUi.dp(this, 4), CgUi.dp(this, 8));
        c.addView(slugStatus);
    }

    private void checkSlug() {
        final String slug = val("slug");
        if (slug.length() == 0) {
            slugStatus.setText("لم يُحدَّد Slug.");
            slugStatus.setTextColor(CgCfg.MUTED);
            slugOk = true;
            return;
        }
        slugStatus.setText("جارٍ الفحص...");
        slugStatus.setTextColor(CgCfg.MUTED);
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("slug", slug);
                b.put("kind", kind == CgData.K_SERIES ? "series" : "movie");
                if (!creating) b.put("exclude_id", itemId);
                return CgHttp.apiPost("/admin/check-slug", b);
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                if (r.json == null) {
                    slugStatus.setText(r.message());
                    slugStatus.setTextColor(CgCfg.BAD);
                    return;
                }
                boolean avail = r.json.optBoolean("available", false);
                slugOk = avail;
                String m = r.json.optString("message", avail ? "Slug متاح." : "Slug غير متاح.");
                String sug = r.json.optString("suggestion", "");
                if (!avail && sug.length() > 0) m += "\nاقتراح: " + sug;
                CgUi.set(slugStatus, (avail ? "✔ " : "✖ ") + m);
                slugStatus.setTextColor(avail ? CgCfg.GOOD : CgCfg.BAD);
            }

            @Override
            public void fail(String message) {
                slugStatus.setText(message);
                slugStatus.setTextColor(CgCfg.BAD);
            }
        });
    }

    private View statusCard(CgData d) {
        LinearLayout card = CgUi.card(this);
        int st = d.status(original);
        LinearLayout head = CgUi.hbox(this);
        head.addView(CgRows.statusBadge(this, st));
        head.addView(CgUi.text(this, "  حالة المحتوى", 14, CgCfg.TEXT, true));
        card.addView(head);
        ArrayList<CgData.Issue> issues = d.issues(original);
        if (issues.isEmpty() && st != 3) {
            card.addView(CgUi.text(this, "● لا توجد مشاكل", 12.5f, CgCfg.GOOD, false));
        }
        if (st == 3) card.addView(CgUi.text(this, "● مخفي / غير منشور", 12.5f, CgCfg.GRAY, false));
        for (int i = 0; i < issues.size(); i++) {
            CgData.Issue is = issues.get(i);
            card.addView(CgUi.text(this, "● " + is.text, 12.5f, CgUi.levelColor(is.level), false));
        }
        String note = d.noteOf(original);
        TextView nb = CgUi.smallButton(this, note.length() > 0 ? "ملاحظة الأدمن: " + note : "＋ إضافة ملاحظة للأدمن", 3);
        nb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgOps.saveNote(CgItemActivity.this, "item", itemId, CgData.cur().noteOf(original), "ملاحظة الأدمن", new Runnable() {
                    @Override
                    public void run() {
                        render();
                    }
                });
            }
        });
        card.addView(nb, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 6, 0, 0));
        card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        return card;
    }

    private View toolsCard(final CgData d) {
        LinearLayout card = CgUi.card(this);
        card.addView(CgUi.text(this, "أدوات", 14, CgCfg.TEXT, true));
        if (kind != CgData.K_SERIES) {
            TextView test = CgUi.button(this, "اختبار الفيديو والترجمة ومعلومات الملف", 1);
            test.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent in = new Intent(CgItemActivity.this, CgPlayerActivity.class);
                    in.putExtra("id", itemId);
                    startActivity(in);
                }
            });
            card.addView(test, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        }
        if (kind == CgData.K_EPISODE) {
            final JSONObject h = d.headerOf(original);
            if (h != null) {
                TextView up = CgUi.button(this, "فتح المسلسل: " + CgData.s(h, "title"), 1);
                up.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent in = new Intent(CgItemActivity.this, CgSeriesActivity.class);
                        in.putExtra("id", CgData.s(h, "id"));
                        startActivity(in);
                    }
                });
                card.addView(up, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
                final int season = CgData.n(original, "season");
                TextView nx = CgUi.button(this, "＋ إضافة الحلقة التالية", 1);
                nx.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent in = new Intent(CgItemActivity.this, CgItemActivity.class);
                        in.putExtra("mode", "new_episode");
                        in.putExtra("series_id", CgData.seriesKey(h));
                        in.putExtra("season", season);
                        in.putExtra("episode", CgData.cur().nextEpisodeInSeason(h, season));
                        startActivity(in);
                    }
                });
                card.addView(nx, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
            }
        }
        if (kind == CgData.K_SERIES) {
            TextView open = CgUi.button(this, "فتح صفحة الحلقات", 1);
            open.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent in = new Intent(CgItemActivity.this, CgSeriesActivity.class);
                    in.putExtra("id", itemId);
                    startActivity(in);
                }
            });
            card.addView(open, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        }
        TextView del = CgUi.button(this, "حذف " + CgData.kindLabel(kind), 2);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteFlow();
            }
        });
        card.addView(del, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 14, 0, 0));
        card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 10));
        return card;
    }

    // --------------------------------------------------------------- save

    private boolean changed(String key, String now) {
        return !CgData.s(original, key).equals(now);
    }

    private void save() {
        final JSONObject changes = new JSONObject();
        try {
            for (Map.Entry<String, EditText> en : fields.entrySet()) {
                String k = en.getKey();
                String v = en.getValue().getText().toString().trim();
                if (creating || changed(k, v)) changes.put(k, v);
            }
            if (creating) {
                changes.put("series_id", seriesKey);
                if (sectionId.length() > 0) {
                    changes.put("section_id", sectionId);
                    changes.put("section_name", sectionName);
                }
            } else if (!sectionId.equals(CgData.s(original, "section_id"))) {
                changes.put("section_id", sectionId);
                changes.put("section_name", sectionName);
            }
            if (hidden.on != CgData.b(original, "hidden") || (creating && hidden.on)) changes.put("hidden", hidden.on);
            if (featured != null && (featured.on != CgData.b(original, "featured") || (creating && featured.on))) changes.put("featured", featured.on);
        } catch (Exception e) {
            CgUi.toast(this, "خطأ: " + e.getMessage());
            return;
        }
        if (!creating && changes.length() == 0) {
            CgUi.toast(this, "لا توجد تغييرات.");
            return;
        }
        if (creating && val("video").length() == 0) {
            CgUi.confirm(this, "بدون رابط فيديو", "الحلقة بلا رابط فيديو وستظهر بحالة خطأ. المتابعة؟", "أضف رغم ذلك", false, new Runnable() {
                @Override
                public void run() {
                    send(changes);
                }
            });
            return;
        }
        if (changes.has("slug") && !creating && CgData.cur().slugOf(original).length() > 0 && CgData.s(changes, "slug").length() > 0) {
            CgUi.confirm(this, "تغيير Slug منشور", "تغيير الـ Slug قد يكسر روابط موجودة. تأكيد الحفظ؟", "احفظ", true, new Runnable() {
                @Override
                public void run() {
                    send(changes);
                }
            });
            return;
        }
        send(changes);
    }

    private void send(final JSONObject changes) {
        showLoading(creating ? "جارٍ إضافة الحلقة..." : "جارٍ الحفظ...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                JSONObject b = new JSONObject();
                if (creating) {
                    b.put("type", "episode");
                    b.put("fields", changes);
                    return CgHttp.require(CgHttp.apiPost("/admin/item/create", b));
                }
                b.put("id", itemId);
                b.put("changes", changes);
                return CgHttp.require(CgHttp.apiPost("/admin/item/update", b));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(final CgHttp.Res r) {
                hideLoading();
                final String undo = r.json == null ? "" : r.json.optString("undo_id", "");
                reloadData(new Runnable() {
                    @Override
                    public void run() {
                        if (creating) {
                            CgUi.toast(CgItemActivity.this, "تمت إضافة الحلقة.");
                            finish();
                        } else {
                            render();
                            showUndo("تم الحفظ", undo, new Runnable() {
                                @Override
                                public void run() {
                                    render();
                                }
                            });
                        }
                    }
                });
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(CgItemActivity.this, creating ? "تعذرت الإضافة" : "لم يتم الحفظ", message);
            }
        });
    }

    private void deleteFlow() {
        if (kind == CgData.K_SERIES) {
            int n = CgData.cur().episodesOf(original).size();
            CgUi.choose(this, "حذف المسلسل (" + n + " حلقة)", new CharSequence[]{
                    "حذف المسلسل فقط (تبقى الحلقات)", "حذف المسلسل مع كل حلقاته"
            }, new CgUi.IntCb() {
                @Override
                public void run(int index) {
                    confirmDelete(index == 1);
                }
            });
        } else {
            confirmDelete(false);
        }
    }

    private void confirmDelete(final boolean cascade) {
        CgUi.confirm(this, "تأكيد الحذف", "سيتم حذف «" + CgData.s(original, "title") + "»" + (cascade ? " مع كل حلقاته" : "")
                + ". تُحفظ نسخة احتياطية ويمكنك التراجع بعد التنفيذ.", "نعم، احذف", true, new Runnable() {
            @Override
            public void run() {
                showLoading("جارٍ الحذف...");
                CgHttp.async(CgItemActivity.this, new CgHttp.Work<CgHttp.Res>() {
                    @Override
                    public CgHttp.Res run() throws Exception {
                        JSONObject b = new JSONObject();
                        b.put("id", itemId);
                        b.put("cascade_episodes", cascade);
                        return CgHttp.require(CgHttp.apiPost("/admin/item/delete", b));
                    }
                }, new CgHttp.Done<CgHttp.Res>() {
                    @Override
                    public void ok(final CgHttp.Res r) {
                        hideLoading();
                        reloadData(new Runnable() {
                            @Override
                            public void run() {
                                CgUi.toast(CgItemActivity.this, "تم الحذف (" + r.json.optInt("deleted") + "). يمكن التراجع من «سجل العمليات».");
                                finish();
                            }
                        });
                    }

                    @Override
                    public void fail(String message) {
                        hideLoading();
                        CgUi.info(CgItemActivity.this, "تعذر الحذف", message);
                    }
                });
            }
        });
    }
}
