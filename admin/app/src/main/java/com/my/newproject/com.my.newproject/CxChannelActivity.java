package com.my.newproject;

import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Professional "add channel" form: Basic (name, logo, section) / Streaming (stream URL, test) /
 * Display (position, visibility) / Admin (notes), with a live preview card, duplicate detection and copy mode.
 * Creation goes through the existing channels Worker; extra settings are stored in the admin overlay.
 */
public class CxChannelActivity extends CxBase {

    private static final int REQ_LOGO = 7702;

    private final ArrayList<JSONObject> channels = new ArrayList<JSONObject>();
    private JSONObject overlay = new JSONObject();
    private boolean listKnown;
    private JSONObject st = new JSONObject();
    private CxDraft.Auto auto;

    private CxUi.Field name;
    private CxUi.Field url;
    private CxUi.Field note;
    private CxUi.PosterBox logoBox;
    private TextView categoryButton;
    private TextView positionText;
    private CgUi.Toggle visibleToggle;
    private CxPreview.ChannelCard card;
    private LinearLayout positionBox;
    private LinearLayout logoNote;

    private Bitmap logo;
    private String mainStatus = "";   // "", "ok" or a failure code from probe()
    private boolean copyMode;

    @Override
    protected String screenTitle() {
        return "إضافة قناة";
    }

    @Override
    protected void onBuild() {
        initCx(null);
        setBottomVisible(false);
        load();
    }

    private void load() {
        page.removeAllViews();
        page.addView(CxUi.skeleton(this, 3));
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                CgHttp.Res a = CgHttp.call("GET", CgCfg.CHANNELS_API + "/channels", null, false, 20000, null);
                if (a.error != null) throw new CxApi.Failure(CgHttp.describe(a.error), "NETWORK", null, 0);
                if (!a.ok() || a.json == null) throw new CxApi.Failure(CgHttp.friendly(a.message()), "CHANNELS", null, a.code);
                JSONObject out = new JSONObject();
                out.put("list", flatten(a.json));
                try {
                    CgHttp.Res b = CgHttp.apiGet("/admin/channel-meta");
                    if (b.ok() && b.json != null && b.json.optJSONObject("channels") != null) {
                        out.put("overlay", b.json.optJSONObject("channels"));
                    }
                } catch (Exception ignored) { }
                return out;
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                channels.clear();
                JSONArray arr = CxJ.arr(r, "list");
                for (int i = 0; i < arr.length(); i++) channels.add(CxJ.at(arr, i));
                overlay = r.optJSONObject("overlay") == null ? new JSONObject() : r.optJSONObject("overlay");
                listKnown = true;
                start();
            }

            @Override
            public void fail(CxApi.Failure f) {
                page.removeAllViews();
                page.addView(CgUi.errorCard(CxChannelActivity.this, f.getMessage(), new Runnable() {
                    @Override
                    public void run() {
                        load();
                    }
                }));
                TextView offline = CgUi.button(CxChannelActivity.this, "المتابعة بدون فحص التكرار", 1);
                offline.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        listKnown = false;
                        start();
                    }
                });
                page.addView(offline, CgUi.lp(CxChannelActivity.this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
            }
        });
    }

    private static JSONArray flatten(JSONObject root) throws Exception {
        JSONArray out = new JSONArray();
        JSONArray cats = root.optJSONArray("categories");
        if (cats == null) cats = root.optJSONArray("sections");
        for (int i = 0; cats != null && i < cats.length(); i++) {
            JSONObject cat = cats.optJSONObject(i);
            if (cat == null) continue;
            String cname = cat.optString("name", "");
            JSONArray arr = cat.optJSONArray("channels");
            if (arr == null) arr = cat.optJSONArray("items");
            for (int k = 0; arr != null && k < arr.length(); k++) {
                JSONObject ch = arr.optJSONObject(k);
                if (ch != null) out.put(one(ch, cname));
            }
        }
        if (out.length() == 0) {
            JSONArray flat = root.optJSONArray("channels");
            if (flat == null) flat = root.optJSONArray("items");
            for (int k = 0; flat != null && k < flat.length(); k++) {
                JSONObject ch = flat.optJSONObject(k);
                if (ch != null) out.put(one(ch, ch.optString("category", "")));
            }
        }
        return out;
    }

    private static JSONObject one(JSONObject ch, String category) throws Exception {
        JSONObject c = new JSONObject();
        String id = ch.optString("id", "");
        String nm = ch.optString("name", "");
        String cat = category.length() > 0 ? category : ch.optString("category", "");
        c.put("id", id);
        c.put("name", nm);
        c.put("url", ch.optString("url", ch.optString("stream", "")));
        c.put("image", ch.optString("image", ch.optString("logo", ch.optString("image_url", ch.optString("icon", "")))));
        c.put("category", cat);
        c.put("key", id.length() > 0 ? id : cat + "|" + nm);
        return c;
    }

    // ------------------------------------------------------------------------- start

    private void start() {
        String draftId = getIntent().getStringExtra("draft_id");
        String copyKey = getIntent().getStringExtra("copy_key");
        if (draftId != null && draftId.length() > 0) {
            JSONObject d = CxDraft.load(this, draftId);
            if (d != null && d.optJSONObject("data") != null) st = d.optJSONObject("data");
        } else if (copyKey != null && copyKey.length() > 0) {
            copyFrom(copyKey);
        }
        if (CxJ.s(st, "position").length() == 0) CxJ.put(st, "position", "last");
        if (!st.has("visible")) CxJ.put(st, "visible", Boolean.TRUE);
        auto = new CxDraft.Auto(this, "channel", draftId, new CxDraft.Source() {
            @Override
            public boolean worthSaving() {
                pull();
                return CxJ.s(st, "name").length() > 0 || CxJ.s(st, "url").length() > 0;
            }

            @Override
            public JSONObject collect() {
                pull();
                return st;
            }

            @Override
            public String title() {
                String n = CxJ.s(st, "name");
                return n.length() > 0 ? n : "قناة بدون اسم";
            }

            @Override
            public int step() {
                return 0;
            }
        });
        build();
        if (copyMode) downloadCopyLogo();
    }

    private void copyFrom(String key) {
        for (int i = 0; i < channels.size(); i++) {
            JSONObject c = channels.get(i);
            if (!key.equals(CxJ.s(c, "key"))) continue;
            copyMode = true;
            JSONObject ov = overlay.optJSONObject(key);
            if (ov == null) ov = new JSONObject();
            CxJ.put(st, "name", CxJ.s(c, "name") + " (نسخة)");
            CxJ.put(st, "url", CxJ.s(c, "url"));
            CxJ.put(st, "category", CxJ.s(c, "category"));
            CxJ.put(st, "note", CxJ.s(ov, "note"));
            CxJ.put(st, "logo_url", CxJ.s(c, "image"));
            CxJ.put(st, "visible", Boolean.valueOf(!ov.optBoolean("hidden", false)));
            return;
        }
    }

    private void downloadCopyLogo() {
        final String u = CxJ.s(st, "logo_url");
        if (!CxJ.isUrl(u)) return;
        exec(new Job<Bitmap>() {
            @Override
            public Bitmap run() throws Exception {
                return CxMedia.download(u, 600);
            }
        }, new Cb<Bitmap>() {
            @Override
            public void ok(Bitmap b) {
                if (b == null) {
                    snack("تعذر تحميل شعار القناة الأصلية، اختر شعاراً جديداً.", CxUi.WARN);
                    return;
                }
                if (logo == null) {
                    logo = b;
                    logoBox.setBitmap(b);
                    card.setLogoBitmap(b);
                }
            }

            @Override
            public void fail(CxApi.Failure f) {
                snack("تعذر تحميل شعار القناة الأصلية، اختر شعاراً جديداً.", CxUi.WARN);
            }
        });
    }

    private void pull() {
        if (name == null) return;
        CxJ.put(st, "name", name.text());
        CxJ.put(st, "url", url.text());
        CxJ.put(st, "note", note.text());
        CxJ.put(st, "visible", Boolean.valueOf(visibleToggle.on));
    }

    private void touch() {
        if (auto != null) auto.touch();
    }

    @Override
    protected void onPause() {
        if (auto != null) auto.flush();
        super.onPause();
    }

    // ------------------------------------------------------------------------ layout

    private void build() {
        page.removeAllViews();
        bottom.removeAllViews();
        View warn = warningCard();
        if (warn != null) page.addView(warn);
        if (!listKnown) page.addView(CxUi.notice(this, CxUi.WARN, "تعذر تحميل القنوات الحالية، لذلك لن يعمل فحص التكرار ولا قائمة الأقسام."));
        if (copyMode) page.addView(CxUi.notice(this, CxUi.INFO, "أنت تنشئ نسخة جديدة من قناة موجودة. عدّل الاسم والرابط بما يناسبك."));

        if (!copyMode) {
            final JSONObject latest = CxDraft.latest(this, "channel");
            if (latest != null && !latest.optString("id").equals(auto.id()) && CxJ.s(st, "name").length() == 0) {
                page.addView(draftBanner(latest, new Runnable() {
                    @Override
                    public void run() {
                        CxNav.resumeDraft(CxChannelActivity.this, latest);
                        finish();
                    }
                }, new Runnable() {
                    @Override
                    public void run() {
                        CxDraft.delete(CxChannelActivity.this, latest.optString("id"));
                        build();
                    }
                }));
            }
        }

        page.addView(sectionLabel("معاينة"));
        card = new CxPreview.ChannelCard(this);
        card.view.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        page.addView(card.view);

        // ---- basic
        CxUi.Section basic = new CxUi.Section(this, "الأساسيات", "اسم القناة والشعار والقسم", false, true);
        name = new CxUi.Field(this, "اسم القناة", "مثال: MBC 1", CxUi.T_TEXT, true);
        name.set(CxJ.s(st, "name"));
        name.onChange(new Runnable() {
            @Override
            public void run() {
                checkDuplicates();
                refreshPreview();
                touch();
            }
        });
        basic.add(name.view);

        basic.add(CgUi.text(this, "الشعار *", 12, CgCfg.MUTED, false));
        LinearLayout logoRow = CgUi.hbox(this);
        logoBox = new CxUi.PosterBox(this, 92, 1f, "مربع 1:1");
        logoBox.onHint(new CxUi.RatioHint() {
            @Override
            public void onHint(String warning) {
                if (logoNote != null) {
                    logoNote.removeAllViews();
                    if (warning != null && warning.length() > 0) logoNote.addView(CxUi.notice(CxChannelActivity.this, CxUi.WARN, warning));
                }
            }
        });
        if (logo != null) logoBox.setBitmap(logo);
        logoRow.addView(logoBox);
        LinearLayout lcol = CgUi.vbox(this);
        TextView pickLogo = CgUi.button(this, logo == null ? "اختيار الشعار" : "تغيير الشعار", 1);
        pickLogo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CxMedia.pickImage(CxChannelActivity.this, REQ_LOGO);
            }
        });
        lcol.addView(pickLogo, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        lcol.addView(CgUi.muted(this, "يفضَّل شعار مربع بخلفية شفافة أو داكنة. يُرفع عند الحفظ."));
        LinearLayout.LayoutParams lclp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        lclp.setMargins(CgUi.dp(this, 12), 0, 0, 0);
        logoRow.addView(lcol, lclp);
        basic.add(logoRow);
        logoNote = CgUi.vbox(this);
        basic.add(logoNote);
        basic.add(CgUi.space(this, 10));

        categoryButton = CgUi.button(this, "", 1);
        categoryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseCategory();
            }
        });
        basic.add(CgUi.text(this, "القسم *", 12, CgCfg.MUTED, false));
        basic.add(categoryButton);
        page.addView(basic);

        // ---- streaming
        CxUi.Section stream = new CxUi.Section(this, "البث", "رابط البث واختباره", false, true);
        url = new CxUi.Field(this, "رابط البث", "https://.../playlist.m3u8", CxUi.T_LTR, true);
        url.set(CxJ.s(st, "url"));
        url.onChange(new Runnable() {
            @Override
            public void run() {
                mainStatus = "";
                checkDuplicates();
                refreshPreview();
                touch();
            }
        });
        stream.add(url.view);
        TextView testMain = CgUi.smallButton(this, "اختبار البث", 0);
        testMain.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testStream();
            }
        });
        stream.add(testMain);
        page.addView(stream);

        // ---- display
        CxUi.Section display = new CxUi.Section(this, "العرض", "مكان القناة داخل القسم وظهورها", false, true);
        positionBox = CgUi.vbox(this);
        display.add(positionBox);
        visibleToggle = CgUi.toggle(this, "القناة ظاهرة في التطبيق", CgData.b(st, "visible"));
        visibleToggle.onChange(new Runnable() {
            @Override
            public void run() {
                touch();
            }
        });
        display.add(visibleToggle);
        page.addView(display);

        // ---- admin
        CxUi.Section admin = new CxUi.Section(this, "ملاحظات الإدارة", "لا تظهر للمستخدمين", true, !CxJ.s(st, "note").isEmpty());
        note = new CxUi.Field(this, "ملاحظات", "مثال: مصدر الرابط، موعد انتهاء الاشتراك...", CxUi.T_MULTI, false);
        note.set(CxJ.s(st, "note"));
        note.onChange(new Runnable() {
            @Override
            public void run() {
                touch();
            }
        });
        admin.add(note.view);
        page.addView(admin);

        renderCategory();
        renderPosition();
        refreshPreview();
        checkDuplicates();

        bottomButton("إلغاء", 1, 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (auto != null) auto.flush();
                finish();
            }
        });
        bottomButton("إضافة القناة", 0, 2f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(false);
            }
        });
        setBottomVisible(true);
    }

    // --------------------------------------------------------------------- category

    private ArrayList<String> categories() {
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < channels.size(); i++) {
            String c = CxJ.s(channels.get(i), "category");
            if (c.length() > 0 && !out.contains(c)) out.add(c);
        }
        return out;
    }

    private void renderCategory() {
        String c = CxJ.s(st, "category");
        categoryButton.setText(c.length() == 0 ? "اختيار القسم ▾" : c + "  ▾");
    }

    private void chooseCategory() {
        ArrayList<CxUi.Option> opts = new ArrayList<CxUi.Option>();
        ArrayList<String> cats = categories();
        for (int i = 0; i < cats.size(); i++) {
            int n = 0;
            for (int k = 0; k < channels.size(); k++) if (cats.get(i).equals(CxJ.s(channels.get(k), "category"))) n++;
            opts.add(new CxUi.Option(cats.get(i), cats.get(i), n + " قناة", null));
        }
        CxUi.pick(this, "اختر القسم", opts, CxJ.s(st, "category"), "إنشاء قسم جديد", new CgUi.Callback<CxUi.Option>() {
            @Override
            public void run(CxUi.Option o) {
                setCategory(o.id);
            }
        }, new CgUi.Callback<String>() {
            @Override
            public void run(String text) {
                setCategory(text.trim());
            }
        });
    }

    private void setCategory(String c) {
        CxJ.put(st, "category", c);
        CxJ.put(st, "position", "last");
        CxJ.put(st, "after_key", "");
        CxJ.put(st, "after_name", "");
        renderCategory();
        renderPosition();
        refreshPreview();
        checkDuplicates();
        touch();
    }

    // --------------------------------------------------------------------- position

    private void renderPosition() {
        positionBox.removeAllViews();
        positionBox.addView(CgUi.text(this, "موضع القناة داخل القسم", 12, CgCfg.MUTED, false));
        CxUi.Flow flow = new CxUi.Flow(this);
        final String p = CxJ.s(st, "position");
        flow.addView(CxUi.chip(this, "في آخر القسم", "last".equals(p), new Runnable() {
            @Override
            public void run() {
                CxJ.put(st, "position", "last");
                renderPosition();
                touch();
            }
        }));
        flow.addView(CxUi.chip(this, "في أول القسم", "first".equals(p), new Runnable() {
            @Override
            public void run() {
                CxJ.put(st, "position", "first");
                renderPosition();
                touch();
            }
        }));
        flow.addView(CxUi.chip(this, "بعد قناة معينة", "after".equals(p), new Runnable() {
            @Override
            public void run() {
                chooseAfter();
            }
        }));
        positionBox.addView(flow);
        String hint;
        if ("first".equals(p)) hint = "ستظهر القناة أولاً في قسمها.";
        else if ("after".equals(p)) hint = "ستظهر بعد: " + CxJ.s(st, "after_name");
        else hint = "ستُضاف في نهاية القسم (دون تغيير ترتيب القنوات الأخرى).";
        positionText = CgUi.muted(this, hint);
        positionBox.addView(positionText, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 10));
    }

    private void chooseAfter() {
        String cat = CxJ.s(st, "category");
        ArrayList<CxUi.Option> opts = new ArrayList<CxUi.Option>();
        for (int i = 0; i < channels.size(); i++) {
            JSONObject c = channels.get(i);
            if (!cat.equals(CxJ.s(c, "category"))) continue;
            opts.add(new CxUi.Option(CxJ.s(c, "key"), CxJ.s(c, "name"), "", CxJ.s(c, "image")));
        }
        if (opts.isEmpty()) {
            snack(cat.length() == 0 ? "اختر القسم أولاً." : "هذا القسم لا يحتوي قنوات بعد. ستكون القناة الأولى.", CxUi.INFO);
            return;
        }
        CxUi.pick(this, "بعد أي قناة؟", opts, CxJ.s(st, "after_key"), null, new CgUi.Callback<CxUi.Option>() {
            @Override
            public void run(CxUi.Option o) {
                CxJ.put(st, "position", "after");
                CxJ.put(st, "after_key", o.id);
                CxJ.put(st, "after_name", o.title);
                renderPosition();
                touch();
            }
        }, null);
    }

    // ---------------------------------------------------------------------- preview

    private void refreshPreview() {
        if (card == null) return;
        String text;
        int color;
        if ("ok".equals(mainStatus)) {
            text = "ONLINE";
            color = CgCfg.GOOD;
        } else if (mainStatus.length() > 0) {
            text = "OFFLINE";
            color = CgCfg.BAD;
        } else {
            text = "UNKNOWN";
            color = CgCfg.GRAY;
        }
        card.set(name == null ? "" : name.text(), CxJ.s(st, "category"), text, color);
        if (logo != null) card.setLogoBitmap(logo);
    }

    // ------------------------------------------------------------------ duplicates

    private static String normUrl(String u) {
        String t = u == null ? "" : u.trim().toLowerCase();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private JSONObject findByUrl(String u) {
        String n = normUrl(u);
        if (n.length() == 0) return null;
        for (int i = 0; i < channels.size(); i++) {
            JSONObject c = channels.get(i);
            if (n.equals(normUrl(CxJ.s(c, "url")))) return c;
        }
        return null;
    }

    private JSONObject findByName(String nm, String cat) {
        String n = CgData.normalize(nm);
        if (n.length() == 0) return null;
        for (int i = 0; i < channels.size(); i++) {
            JSONObject c = channels.get(i);
            if (n.equals(CgData.normalize(CxJ.s(c, "name"))) && (cat.length() == 0 || cat.equals(CxJ.s(c, "category")))) return c;
        }
        return null;
    }

    private void checkDuplicates() {
        if (!listKnown || name == null) return;
        JSONObject byName = findByName(name.text(), CxJ.s(st, "category"));
        if (byName != null && name.text().length() > 0) name.warn("توجد قناة بنفس الاسم في هذا القسم: «" + CxJ.s(byName, "name") + "».");
        JSONObject byUrl = findByUrl(url.text());
        if (byUrl != null) url.warn("هذا الرابط مستخدم في قناة «" + CxJ.s(byUrl, "name") + "» (" + CxJ.s(byUrl, "category") + ").");
    }

    // ------------------------------------------------------------------------- test

    private void testStream() {
        final CxUi.Field f = url;
        final String u = f.text();
        if (u.length() == 0) {
            f.error("أدخل رابط البث أولاً.");
            return;
        }
        if (!CxJ.isUrl(u)) {
            f.error("الرابط يجب أن يبدأ بـ http:// أو https://");
            return;
        }
        f.message(CxUi.INFO, "جارٍ اختبار البث...");
        exec(new Job<String[]>() {
            @Override
            public String[] run() throws Exception {
                return CgChannelsActivity.probe(u);
            }
        }, new Cb<String[]>() {
            @Override
            public void ok(String[] r) {
                boolean good = "ok".equals(r[0]);
                mainStatus = r[0];
                String text = (good ? "يعمل (ONLINE)" : "لا يعمل (OFFLINE): " + CgData.checkLabel(r[0])) + ("0".equals(r[1]) ? "" : " • HTTP " + r[1]);
                if (good) f.ok(text);
                else f.warn(text + " — يمكنك الحفظ رغم ذلك.");
                refreshPreview();
            }

            @Override
            public void fail(CxApi.Failure fl) {
                f.warn("تعذر الاختبار الآن: " + fl.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------------- logo

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_LOGO || res != RESULT_OK || data == null || data.getData() == null) return;
        showLoading("جارٍ تجهيز الشعار...");
        final android.net.Uri uri = data.getData();
        CgHttp.bg(new Runnable() {
            @Override
            public void run() {
                final Bitmap b = CxMedia.decode(CxChannelActivity.this, uri, 600);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!alive()) return;
                        hideLoading();
                        if (b == null) {
                            snack("تعذر قراءة الصورة. اختر صورة أخرى.", CxUi.ERROR);
                            return;
                        }
                        logo = b;
                        logoBox.setBitmap(b);
                        card.setLogoBitmap(b);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------------- save

    private boolean validate() {
        pull();
        View first = null;
        if (name.text().length() == 0) {
            name.error("اسم القناة مطلوب.");
            first = name.view;
        }
        if (logo == null) {
            snack("الشعار مطلوب. اختر صورة للقناة.", CxUi.ERROR);
            if (first == null) first = logoBox;
        }
        if (CxJ.s(st, "category").length() == 0) {
            snack("اختر القسم أو أنشئ قسماً جديداً.", CxUi.ERROR);
            if (first == null) first = categoryButton;
        }
        if (url.text().length() == 0) {
            url.error("رابط البث مطلوب.");
            if (first == null) first = url.view;
        } else if (!CxJ.isUrl(url.text())) {
            url.error("الرابط يجب أن يبدأ بـ http:// أو https://");
            if (first == null) first = url.view;
        }
        if (first != null) {
            scrollTo(first);
            return false;
        }
        return true;
    }

    private void save(boolean confirmedDup) {
        if (!validate()) return;
        if (!confirmedDup && listKnown) {
            JSONObject byName = findByName(name.text(), CxJ.s(st, "category"));
            JSONObject byUrl = findByUrl(url.text());
            if (byName != null || byUrl != null) {
                JSONObject d = byUrl != null ? byUrl : byName;
                String why = byUrl != null ? "رابط البث نفسه" : "نفس الاسم في القسم";
                CgUi.confirm(this, "قناة مشابهة موجودة", "توجد قناة «" + CxJ.s(d, "name") + "» (" + why + ").\nهل تريد إضافة هذه القناة رغم ذلك؟",
                        "إضافة رغم ذلك", false, new Runnable() {
                            @Override
                            public void run() {
                                save(true);
                            }
                        });
                return;
            }
        }
        final String nm = name.text();
        final String cat = CxJ.s(st, "category");
        final String mainUrl = url.text();
        final String noteText = note.text();
        final boolean visible = visibleToggle.on;
        final String position = CxJ.s(st, "position");
        final String afterKey = CxJ.s(st, "after_key");
        final String mStatus = mainStatus;
        final Bitmap logoBmp = logo;
        final CxStages sg = CxStages.show(this, "إضافة القناة", new String[]{"تجهيز الشعار", "إنشاء القناة", "الإعدادات الإضافية", "اكتمل"});
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                sg.stage(0, nm);
                byte[] jpg = CxMedia.jpegFlat(logoBmp, CgCfg.CARD, 90);
                JSONObject body = new JSONObject();
                body.put("category", cat);
                body.put("name", nm);
                body.put("url", mainUrl);
                body.put("image_base64", CxMedia.base64(jpg));
                body.put("image_mime", "image/jpeg");
                sg.stage(1, CgUi.sizeText(jpg.length));
                CgHttp.Res r = CgHttp.call("POST", CgCfg.CHANNELS_API + "/channels/add", body, false, 60000,
                        new String[][]{{"X-Admin-Token", CgCfg.TOKEN}});
                if (r.error != null) throw new CxApi.Failure(CgHttp.describe(r.error), "NETWORK", null, 0);
                if (!r.ok() || r.json == null) {
                    String m = r.code == 401 || r.code == 403 ? "رمز الإدارة غير مقبول في Worker القنوات." : CgHttp.friendly(r.message());
                    throw new CxApi.Failure(m, "CHANNEL_ADD", r.json, r.code);
                }
                String newId = r.json.optString("id", "");
                JSONObject chObj = r.json.optJSONObject("channel");
                if (newId.length() == 0 && chObj != null) newId = chObj.optString("id", "");
                String logoUrl = r.json.optString("image", r.json.optString("image_url", ""));
                if (logoUrl.length() == 0 && chObj != null) logoUrl = chObj.optString("image", chObj.optString("image_url", ""));

                JSONObject result = new JSONObject();
                result.put("logo", logoUrl);
                String warn = "";
                sg.stage(2, "");
                try {
                    // fresh list: find the new channel's key and the order of its siblings
                    CgHttp.Res a = CgHttp.call("GET", CgCfg.CHANNELS_API + "/channels", null, false, 20000, null);
                    JSONArray fresh = a.ok() && a.json != null ? flatten(a.json) : new JSONArray();
                    String key = "";
                    int newIndex = -1;
                    for (int i = fresh.length() - 1; i >= 0; i--) {
                        JSONObject c = fresh.getJSONObject(i);
                        boolean same = newId.length() > 0 ? newId.equals(c.optString("id")) : nm.equals(c.optString("name")) && cat.equals(c.optString("category"));
                        if (same) {
                            key = c.optString("key");
                            newIndex = i;
                            break;
                        }
                    }
                    if (key.length() == 0) key = newId.length() > 0 ? newId : cat + "|" + nm;
                    if (logoUrl.length() == 0 && newIndex >= 0) logoUrl = fresh.getJSONObject(newIndex).optString("image");
                    result.put("logo", logoUrl);
                    result.put("key", key);

                    JSONObject fields = new JSONObject();
                    if (noteText.length() > 0) fields.put("note", noteText);
                    if (!visible) fields.put("hidden", true);
                    if (mStatus.length() > 0) {
                        fields.put("online", "ok".equals(mStatus));
                        fields.put("last_check_at", CgUi.toIso(System.currentTimeMillis()));
                        fields.put("last_check_status", mStatus);
                    }
                    if (fields.length() > 0) {
                        JSONObject b = new JSONObject();
                        b.put("key", key);
                        b.put("fields", fields);
                        CgHttp.Res m = CgHttp.apiPost("/admin/channel-meta/save", b);
                        if (!m.ok()) warn = "تمت إضافة القناة، لكن تعذر حفظ بعض الإعدادات الإضافية.";
                    }
                    if (("first".equals(position) || "after".equals(position)) && newIndex >= 0) {
                        ArrayList<String> keys = new ArrayList<String>();
                        for (int i = 0; i < fresh.length(); i++) if (i != newIndex) keys.add(fresh.getJSONObject(i).optString("key"));
                        int at = keys.size();
                        if ("first".equals(position)) {
                            for (int i = 0; i < keys.size(); i++) {
                                if (cat.equals(categoryOfKey(fresh, keys.get(i)))) {
                                    at = i;
                                    break;
                                }
                            }
                        } else {
                            int idx = keys.indexOf(afterKey);
                            if (idx >= 0) at = idx + 1;
                        }
                        keys.add(Math.min(at, keys.size()), key);
                        JSONArray entries = new JSONArray();
                        for (int i = 0; i < keys.size(); i++) {
                            JSONObject e = new JSONObject();
                            e.put("key", keys.get(i));
                            e.put("fields", new JSONObject().put("order", i + 1));
                            entries.put(e);
                        }
                        JSONObject b = new JSONObject();
                        b.put("key", key);
                        b.put("entries", entries);
                        CgHttp.Res m = CgHttp.apiPost("/admin/channel-meta/save", b);
                        if (!m.ok()) warn = "تمت إضافة القناة، لكن تعذر ضبط موضعها.";
                    }
                } catch (Exception e) {
                    warn = "تمت إضافة القناة، لكن تعذر حفظ بعض الإعدادات الإضافية.";
                }
                result.put("warn", warn);
                return result;
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                sg.stage(3, "");
                sg.dismiss();
                if (auto != null) auto.finish();
                String logoUrl = CxJ.s(r, "logo");
                CxRecent.push(CxChannelActivity.this, "channel", nm, logoUrl, cat);
                String warn = CxJ.s(r, "warn");
                CxNav.success(CxChannelActivity.this, "channel", CxJ.s(r, "key"), nm, logoUrl,
                        warn.length() > 0 ? warn : "القسم: " + cat, "", "", 0, 0);
                finish();
            }

            @Override
            public void fail(CxApi.Failure f) {
                sg.fail(f.getMessage(), new Runnable() {
                    @Override
                    public void run() {
                        save(true);
                    }
                });
            }
        });
    }

    private static String categoryOfKey(JSONArray list, String key) {
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = list.optJSONObject(i);
            if (c != null && key.equals(c.optString("key"))) return c.optString("category");
        }
        return "";
    }

    @Override
    public void onBackPressed() {
        if (auto != null) auto.flush();
        super.onBackPressed();
    }
}
