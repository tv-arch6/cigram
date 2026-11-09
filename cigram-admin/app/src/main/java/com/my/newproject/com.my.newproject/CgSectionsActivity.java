package com.my.newproject;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Section management: create / rename / reorder / enable / hide-from-home / "More"-only / type / delete.
 * In home mode (CgHomeOrderActivity) the same screen is titled as the home order editor.
 * RTL note: the first section in the list is the right-most one on the home screen.
 */
public class CgSectionsActivity extends CgBase {

    private final ArrayList<JSONObject> sections = new ArrayList<JSONObject>();
    private final ArrayList<JSONObject> orphans = new ArrayList<JSONObject>();
    private boolean registryExists;
    private LinearLayout content;

    protected boolean homeMode() {
        return false;
    }

    @Override
    protected String screenTitle() {
        return homeMode() ? "ترتيب الصفحة الرئيسية" : "إدارة الأقسام";
    }

    @Override
    protected void onBuild() {
        bar.action("＋ قسم", new Runnable() {
            @Override
            public void run() {
                editDialog(null);
            }
        });
        load();
    }

    private void load() {
        showLoading("جارٍ تحميل الأقسام...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgHttp.apiGet("/admin/sections"));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                sections.clear();
                orphans.clear();
                registryExists = "registry".equals(r.json.optString("source"));
                JSONArray a = r.json.optJSONArray("sections");
                for (int i = 0; a != null && i < a.length(); i++) if (a.optJSONObject(i) != null) sections.add(a.optJSONObject(i));
                JSONArray o = r.json.optJSONArray("orphans");
                for (int i = 0; o != null && i < o.length(); i++) if (o.optJSONObject(i) != null) orphans.add(o.optJSONObject(i));
                render();
            }

            @Override
            public void fail(String message) {
                hideLoading();
                body.removeAllViews();
                LinearLayout box = CgUi.vbox(CgSectionsActivity.this);
                box.setPadding(CgUi.dp(CgSectionsActivity.this, 12), CgUi.dp(CgSectionsActivity.this, 12), CgUi.dp(CgSectionsActivity.this, 12), 0);
                box.addView(CgUi.errorCard(CgSectionsActivity.this, message, new Runnable() {
                    @Override
                    public void run() {
                        load();
                    }
                }));
                body.addView(box);
            }
        });
    }

    private void afterChange(String label, String undoId) {
        reloadData(new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
        showUndo(label, undoId, new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
    }

    private void render() {
        content = scrollBody();
        LinearLayout tip = CgUi.card(this);
        tip.addView(CgUi.text(this, homeMode()
                ? "رتّب الأقسام بالأسهم. القسم الأول يظهر في أقصى اليمين (RTL) في الصفحة الرئيسية، والأحدث يبقى على اليمين."
                : "الأقسام تتحكم بترتيب الرئيسية وتبويبات «المزيد». الحذف ممنوع لقسم غير فارغ إلا بنقل محتواه أو بتأكيد صريح.", 12.5f, CgCfg.MUTED, false));
        tip.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        content.addView(tip);

        if (!registryExists) {
            LinearLayout w = CgUi.card(this);
            w.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.WARN, 0x1A), 14, CgCfg.WARN));
            w.addView(CgUi.text(this, "الأقسام الحالية مستخرجة من المحتوى ولم تُثبَّت بعد في ملف الأقسام. ثبّتها لتتمكن من الترتيب والتعطيل.", 13, CgCfg.WARN, false));
            TextView seed = CgUi.smallButton(this, "تثبيت الأقسام الحالية", 0);
            seed.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    post("/admin/sections/seed", new JSONObject(), "تثبيت الأقسام");
                }
            });
            w.addView(seed, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 0, 0));
            w.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
            content.addView(w);
        }

        if (sections.isEmpty()) {
            content.addView(CgUi.empty(this, "لا توجد أقسام", "أنشئ أول قسم بالزر «＋ قسم»."));
        }
        for (int i = 0; i < sections.size(); i++) content.addView(row(i));

        if (!orphans.isEmpty()) {
            content.addView(CgUi.sectionTitle(this, "أقسام موجودة في المحتوى وغير مسجّلة"));
            for (int i = 0; i < orphans.size(); i++) {
                final JSONObject o = orphans.get(i);
                LinearLayout card = CgUi.card(this);
                card.addView(CgUi.text(this, CgData.s(o, "name") + "  (" + CgData.s(o, "id") + ")", 14, CgCfg.WARN, true));
                card.addView(CgUi.muted(this, o.optInt("total") + " عنصر بلا تسجيل في الأقسام"));
                TextView reg = CgUi.smallButton(this, "تسجيل هذا القسم", 1);
                reg.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            JSONObject b = new JSONObject();
                            b.put("name", CgData.s(o, "name"));
                            b.put("id", CgData.s(o, "id"));
                            b.put("slug", CgData.slugify(CgData.s(o, "id")).length() > 0 ? CgData.slugify(CgData.s(o, "id")) : CgData.s(o, "id"));
                            post("/admin/sections/create", b, "تسجيل قسم");
                        } catch (Exception ignored) { }
                    }
                });
                card.addView(reg, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 6, 0, 0));
                card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
                content.addView(card);
            }
        }
    }

    private View row(final int index) {
        final JSONObject s = sections.get(index);
        final boolean enabled = s.optBoolean("enabled", true);
        LinearLayout card = CgUi.card(this);
        LinearLayout top = CgUi.hbox(this);
        TextView num = CgUi.text(this, String.valueOf(index + 1), 16, CgCfg.ACCENT, true);
        top.addView(num, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 10, 0));
        LinearLayout col = CgUi.vbox(this);
        col.addView(CgUi.text(this, CgData.s(s, "name"), 15, enabled ? CgCfg.TEXT : CgCfg.GRAY, true));
        JSONObject counts = s.optJSONObject("counts");
        String cs = counts == null ? "" : counts.optInt("total") + " عنصر • " + counts.optInt("movies") + " فيلم • "
                + counts.optInt("series") + " مسلسل • " + counts.optInt("episodes") + " حلقة";
        col.addView(CgUi.muted(this, cs));
        TextView sl = CgUi.muted(this, "ID: " + CgData.s(s, "id") + "  •  Slug: " + CgData.s(s, "slug"));
        CgUi.ltr(sl);
        col.addView(sl);
        LinearLayout tags = CgUi.hbox(this);
        tags.addView(CgUi.badge(this, typeLabel(CgData.s(s, "type")), CgCfg.ACCENT), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 4, 6, 0));
        if (!enabled) tags.addView(CgUi.badge(this, "معطّل", CgCfg.GRAY), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 4, 6, 0));
        if (s.optBoolean("more_only", false)) tags.addView(CgUi.badge(this, "في «المزيد» فقط", CgCfg.WARN), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 4, 6, 0));
        else if (!s.optBoolean("show_home", true)) tags.addView(CgUi.badge(this, "غير ظاهر بالرئيسية", CgCfg.WARN), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 4, 6, 0));
        col.addView(tags);
        top.addView(col, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        card.addView(top);

        LinearLayout acts = CgUi.hbox(this);
        TextView up = CgUi.smallButton(this, "▲", 1);
        up.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                move(index, -1);
            }
        });
        acts.addView(up, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
        TextView down = CgUi.smallButton(this, "▼", 1);
        down.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                move(index, 1);
            }
        });
        acts.addView(down, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
        TextView en = CgUi.smallButton(this, enabled ? "تعطيل" : "تفعيل", 1);
        en.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    JSONObject b = new JSONObject();
                    b.put("id", CgData.s(s, "id"));
                    JSONObject ch = new JSONObject();
                    ch.put("enabled", !enabled);
                    b.put("changes", ch);
                    post("/admin/sections/update", b, enabled ? "تعطيل القسم (لا يُحذف المحتوى)" : "تفعيل القسم");
                } catch (Exception ignored) { }
            }
        });
        acts.addView(en, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
        TextView ed = CgUi.smallButton(this, "تعديل", 0);
        ed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editDialog(s);
            }
        });
        acts.addView(ed, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
        TextView more = CgUi.smallButton(this, "⋯", 1);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUi.choose(CgSectionsActivity.this, CgData.s(s, "name"), new CharSequence[]{"عرض محتوى القسم", "حذف القسم"}, new CgUi.IntCb() {
                    @Override
                    public void run(int i) {
                        if (i == 0) {
                            Intent in = new Intent(CgSectionsActivity.this, CgContentActivity.class);
                            in.putExtra("section", CgData.s(s, "id"));
                            startActivity(in);
                        } else {
                            deleteFlow(s);
                        }
                    }
                });
            }
        });
        acts.addView(more, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(acts);
        card.addView(hs, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        return card;
    }

    private static String typeLabel(String t) {
        if ("movie".equals(t)) return "أفلام";
        if ("series".equals(t)) return "مسلسلات";
        return "مختلط";
    }

    private void move(int index, int delta) {
        int to = index + delta;
        if (to < 0 || to >= sections.size()) return;
        JSONObject a = sections.remove(index);
        sections.add(to, a);
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < sections.size(); i++) {
                JSONObject o = new JSONObject();
                o.put("id", CgData.s(sections.get(i), "id"));
                arr.put(o);
            }
            JSONObject b = new JSONObject();
            b.put("sections", arr);
            b.put("rename_items", false);
            post("/admin/sections/save", b, "تغيير ترتيب الأقسام");
        } catch (Exception e) {
            CgUi.toast(this, "خطأ: " + e.getMessage());
        }
    }

    private void post(final String path, final JSONObject body, final String label) {
        showLoading("جارٍ الحفظ...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                return CgHttp.apiPost(path, body);
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                if (!r.ok()) {
                    load();
                    CgUi.info(CgSectionsActivity.this, "لم يتم التنفيذ", r.message());
                    return;
                }
                afterChange(label, r.json == null ? "" : r.json.optString("undo_id", ""));
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(CgSectionsActivity.this, "لم يتم التنفيذ", message);
            }
        });
    }

    // ------------------------------------------------------------- dialog

    private String chosenType = "mixed";

    private void editDialog(final JSONObject existing) {
        final boolean isNew = existing == null;
        final EditText name = CgUi.field(this, "اسم القسم", false);
        final EditText slug = CgUi.urlField(this, "slug");
        final EditText id = CgUi.urlField(this, "ID");
        name.setText(isNew ? "" : CgData.s(existing, "name"));
        slug.setText(isNew ? "" : CgData.s(existing, "slug"));
        id.setText(isNew ? "" : CgData.s(existing, "id"));
        chosenType = isNew ? "mixed" : CgData.s(existing, "type");
        if (chosenType.length() == 0) chosenType = "mixed";

        LinearLayout box = CgUi.vbox(this);
        box.setPadding(CgUi.dp(this, 16), CgUi.dp(this, 8), CgUi.dp(this, 16), CgUi.dp(this, 8));
        box.addView(CgUi.labeled(this, "الاسم", name));
        box.addView(CgUi.labeled(this, "Slug (إنجليزي)", slug));
        box.addView(CgUi.labeled(this, isNew ? "ID (اختياري — يُولَّد من الـ Slug)" : "ID (تغييره يحدّث كل العناصر المرتبطة)", id));

        final String[] types = {"movie", "series", "mixed"};
        final String[] typeNames = {"أفلام", "مسلسلات", "مختلط"};
        final TextView[] chips = new TextView[3];
        CgUi.ChipBar cb = new CgUi.ChipBar(this);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            chips[i] = cb.add(typeNames[i], types[i].equals(chosenType));
            chips[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    chosenType = types[idx];
                    for (int k = 0; k < 3; k++) CgUi.setChipState(chips[k], k == idx);
                }
            });
        }
        box.addView(CgUi.labeled(this, "النوع", cb.scroll));

        final CgUi.Toggle enabled = CgUi.toggle(this, "مفعّل", isNew || existing.optBoolean("enabled", true));
        final CgUi.Toggle home = CgUi.toggle(this, "يظهر في الرئيسية", isNew || existing.optBoolean("show_home", true));
        final CgUi.Toggle moreOnly = CgUi.toggle(this, "في «المزيد» فقط", !isNew && existing.optBoolean("more_only", false));
        box.addView(enabled);
        box.addView(home);
        box.addView(moreOnly);
        final TextView hint = CgUi.muted(this, "");
        box.addView(hint);
        if (isNew) {
            name.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int c) { }

                @Override
                public void afterTextChanged(android.text.Editable e) {
                    if (slug.getTag() == null) slug.setText(CgData.slugify(e.toString()));
                }
            });
            slug.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean has) {
                    if (has) slug.setTag("manual");
                }
            });
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        final AlertDialog dlg = CgUi.dialog(this).setTitle(isNew ? "قسم جديد" : "تعديل القسم").setView(sv)
                .setNegativeButton("إلغاء", null).setPositiveButton("حفظ", null).create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String nm = name.getText().toString().trim();
                if (nm.length() == 0) {
                    hint.setText("اسم القسم مطلوب.");
                    hint.setTextColor(CgCfg.BAD);
                    return;
                }
                dlg.dismiss();
                try {
                    if (isNew) {
                        JSONObject b = new JSONObject();
                        b.put("name", nm);
                        b.put("slug", slug.getText().toString().trim());
                        if (id.getText().toString().trim().length() > 0) b.put("id", id.getText().toString().trim());
                        b.put("type", chosenType);
                        b.put("enabled", enabled.on);
                        b.put("show_home", home.on && !moreOnly.on);
                        b.put("more_only", moreOnly.on);
                        post("/admin/sections/create", b, "إنشاء القسم");
                    } else {
                        JSONObject ch = new JSONObject();
                        ch.put("name", nm);
                        ch.put("slug", slug.getText().toString().trim());
                        String newId = id.getText().toString().trim();
                        if (newId.length() > 0) ch.put("id", newId);
                        ch.put("type", chosenType);
                        ch.put("enabled", enabled.on);
                        ch.put("show_home", home.on && !moreOnly.on);
                        ch.put("more_only", moreOnly.on);
                        update(CgData.s(existing, "id"), ch, false);
                    }
                } catch (Exception e) {
                    CgUi.toast(CgSectionsActivity.this, "خطأ: " + e.getMessage());
                }
            }
        });
    }

    private void update(final String id, final JSONObject changes, final boolean confirmed) {
        showLoading("جارٍ الحفظ...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("id", id);
                b.put("changes", changes);
                if (confirmed) b.put("confirm_id_change", true);
                return CgHttp.apiPost("/admin/sections/update", b);
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                if (!r.ok() && "confirm_required".equals(r.errorCode())) {
                    CgUi.confirm(CgSectionsActivity.this, "تأكيد تغيير ID", r.message(), "تأكيد", true, new Runnable() {
                        @Override
                        public void run() {
                            update(id, changes, true);
                        }
                    });
                    return;
                }
                if (!r.ok()) {
                    CgUi.info(CgSectionsActivity.this, "لم يتم الحفظ", r.message());
                    return;
                }
                afterChange("تعديل القسم", r.json.optString("undo_id", ""));
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(CgSectionsActivity.this, "لم يتم الحفظ", message);
            }
        });
    }

    private void deleteFlow(final JSONObject s) {
        JSONObject counts = s.optJSONObject("counts");
        final int total = counts == null ? 0 : counts.optInt("total");
        final String id = CgData.s(s, "id");
        if (total == 0) {
            CgUi.confirm(this, "حذف القسم", "حذف القسم الفارغ «" + CgData.s(s, "name") + "»؟", "احذف", true, new Runnable() {
                @Override
                public void run() {
                    sendDelete(id, "", false);
                }
            });
            return;
        }
        CgUi.choose(this, "القسم يحتوي " + total + " عنصراً", new CharSequence[]{
                "نقل المحتوى إلى قسم آخر ثم الحذف", "حذف القسم فقط (يبقى المحتوى بلا قسم)"
        }, new CgUi.IntCb() {
            @Override
            public void run(int i) {
                if (i == 0) {
                    ArrayList<JSONObject> others = new ArrayList<JSONObject>();
                    for (int k = 0; k < sections.size(); k++) if (!CgData.s(sections.get(k), "id").equals(id)) others.add(sections.get(k));
                    if (others.isEmpty()) {
                        CgUi.info(CgSectionsActivity.this, "لا يوجد قسم وجهة", "أنشئ قسماً آخر أولاً.");
                        return;
                    }
                    final ArrayList<JSONObject> list = others;
                    CharSequence[] labels = new CharSequence[list.size()];
                    for (int k = 0; k < list.size(); k++) labels[k] = CgData.s(list.get(k), "name");
                    CgUi.choose(CgSectionsActivity.this, "انقل المحتوى إلى", labels, new CgUi.IntCb() {
                        @Override
                        public void run(int k) {
                            sendDelete(id, CgData.s(list.get(k), "id"), false);
                        }
                    });
                } else {
                    CgUi.confirm(CgSectionsActivity.this, "تأكيد صريح",
                            "سيبقى " + total + " عنصراً بلا قسم ولن يظهر في الرئيسية حتى تعيّن له قسماً. متابعة؟", "احذف القسم", true, new Runnable() {
                                @Override
                                public void run() {
                                    sendDelete(id, "", true);
                                }
                            });
                }
            }
        });
    }

    private void sendDelete(String id, String moveTo, boolean confirm) {
        try {
            JSONObject b = new JSONObject();
            b.put("id", id);
            if (moveTo.length() > 0) b.put("move_to", moveTo);
            if (confirm) b.put("confirm", true);
            post("/admin/sections/delete", b, "حذف القسم");
        } catch (Exception ignored) { }
    }
}
