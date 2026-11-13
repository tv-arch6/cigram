package com.my.newproject;

import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * Channel management. The channel list comes from the channels Worker, which also changes a
 * channel's stream link (/channels/update) and deletes channels (/channels/delete).
 * The admin extras (order, hide, online/offline, notes, last check) are stored through the admin
 * Worker overlay (/admin/channel-meta). Tests run on demand from the device.
 */
public class CgChannelsActivity extends CgBase {

    private final ArrayList<JSONObject> channels = new ArrayList<JSONObject>();
    private JSONObject overlay = new JSONObject();
    /** 19.5: open «بلّغ المدير» reports, shown at the top of this page («قنوات تطلب استبدال رابط»). */
    private JSONArray reports = new JSONArray();
    private String query = "";
    private String categoryFilter = "";
    private LinearLayout list;

    @Override
    protected String screenTitle() {
        return "إدارة القنوات";
    }

    @Override
    protected void onBuild() {
        bar.action("＋ قناة", new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(CgChannelsActivity.this, NxChannelActivity.class));
            }
        });
        bar.action("تحديث", new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
        load();
    }

    /** 18.3: one load at a time; a request made while one runs is repeated once afterwards (it may follow an edit). */
    private boolean loading;
    private boolean loadAgain;
    private long loadedAt;
    private String lastSignature = "";

    @Override
    protected void onResume() {
        super.onResume();
        // 18.3: coming back from another screen used to repeat the whole load behind a blocking dialog. Now: nothing within 30 s,
        // otherwise a silent refresh that redraws only when the list really changed.
        if (!channels.isEmpty() && list != null && System.currentTimeMillis() - loadedAt > 30000L) load(true);
    }

    private void load() {
        load(false);
    }

    private void load(final boolean silent) {
        if (loading) {
            if (!silent) loadAgain = true;
            return;
        }
        loading = true;
        if (!silent) showLoading("جارٍ تحميل القنوات...");
        CgHttp.async(this, new CgHttp.Work<Object[]>() {
            @Override
            public Object[] run() throws Exception {
                CgHttp.Res a = CgHttp.call("GET", CgCfg.CHANNELS_API + "/channels", null, false, 20000, null);
                if (!a.ok()) throw new Exception("تعذر تحميل القنوات: " + a.message());
                CgHttp.Res b = CgHttp.apiGet("/admin/channel-meta");
                JSONArray open = null;
                try {
                    CgHttp.Res c = CgHttp.apiGet("/admin/channel-reports");
                    if (c.ok() && c.json != null) {
                        JSONObject d = c.json.optJSONObject("data");
                        open = (d != null ? d : c.json).optJSONArray("items");
                    }
                } catch (Throwable ignored) { }   // the reports block is extra: the channel list never waits on it
                return new Object[]{a.json, b.ok() ? b.json : null, open == null ? new JSONArray() : open};
            }
        }, new CgHttp.Done<Object[]>() {
            @Override
            public void ok(Object[] r) {
                loading = false;
                loadedAt = System.currentTimeMillis();
                if (!silent) hideLoading();
                String signature = String.valueOf(r[0]) + "|" + String.valueOf(r[1]) + "|" + String.valueOf(r[2]);
                boolean again = loadAgain;
                loadAgain = false;
                if (!(silent && signature.equals(lastSignature))) {
                    lastSignature = signature;
                    parse((JSONObject) r[0]);
                    JSONObject m = (JSONObject) r[1];
                    overlay = m == null || m.optJSONObject("channels") == null ? new JSONObject() : m.optJSONObject("channels");
                    reports = (JSONArray) r[2];
                    render();
                }
                if (again) load(false);
            }

            @Override
            public void fail(String message) {
                loading = false;
                if (silent) return;                // a failed background refresh keeps what is on screen
                hideLoading();
                body.removeAllViews();
                LinearLayout box = CgUi.vbox(CgChannelsActivity.this);
                box.setPadding(CgUi.dp(CgChannelsActivity.this, 12), CgUi.dp(CgChannelsActivity.this, 12), CgUi.dp(CgChannelsActivity.this, 12), 0);
                box.addView(CgUi.errorCard(CgChannelsActivity.this, message, new Runnable() {
                    @Override
                    public void run() {
                        load();
                    }
                }));
                body.addView(box);
            }
        });
    }

    private static JSONArray firstArray(JSONObject o, String... keys) {
        for (int i = 0; i < keys.length; i++) {
            JSONArray a = o.optJSONArray(keys[i]);
            if (a != null) return a;
        }
        return null;
    }

    private void addChannel(JSONObject ch, String category) {
        try {
            JSONObject c = new JSONObject();
            String name = ch.optString("name", "");
            String id = ch.optString("id", "");
            c.put("id", id);
            c.put("name", name);
            c.put("url", ch.optString("url", ch.optString("stream", "")));
            String img = ch.optString("image", ch.optString("logo", ch.optString("image_url", ch.optString("icon", ""))));
            c.put("image", img);
            String cat = category.length() > 0 ? category : ch.optString("category", "");
            c.put("category", cat);
            c.put("key", id.length() > 0 ? id : cat + "|" + name);
            channels.add(c);
        } catch (Exception ignored) { }
    }

    private void parse(JSONObject root) {
        channels.clear();
        if (root == null) return;
        JSONArray cats = firstArray(root, "categories", "sections");
        for (int i = 0; cats != null && i < cats.length(); i++) {
            JSONObject cat = cats.optJSONObject(i);
            if (cat == null) continue;
            String name = cat.optString("name", "");
            JSONArray arr = firstArray(cat, "channels", "items");
            for (int k = 0; arr != null && k < arr.length(); k++) {
                JSONObject ch = arr.optJSONObject(k);
                if (ch != null) addChannel(ch, name);
            }
        }
        if (channels.isEmpty()) {
            JSONArray flat = firstArray(root, "channels", "items");
            for (int k = 0; flat != null && k < flat.length(); k++) {
                JSONObject ch = flat.optJSONObject(k);
                if (ch != null) addChannel(ch, "");
            }
        }
    }

    private JSONObject ov(String key) {
        JSONObject o = overlay.optJSONObject(key);
        return o == null ? new JSONObject() : o;
    }

    private void render() {
        body.removeAllViews();
        LinearLayout head = CgUi.vbox(this);
        head.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 10), CgUi.dp(this, 12), 0);
        // 19.5: what the viewers reported with «بلّغ المدير» comes first: replace the link, the viewer is told «تم تفعيل القناة»
        View reported = CgChannelReportsActivity.block(this, reports, new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
        if (reported != null) head.addView(reported, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        View warn = warningCard();
        if (warn != null) head.addView(warn);
        // القنوات المكتوبة داخل تطبيق سيكرام (الشرقية و beIN SPORTS 3) ولم تُضف للإدارة بعد
        final ArrayList<String[]> builtin = CgBuiltinChannels.missing(channels);
        if (!builtin.isEmpty()) {
            head.addView(CgBuiltinChannels.card(this, builtin, channels, new Runnable() {
                @Override
                public void run() {
                    importBuiltins(builtin);
                }
            }), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        }
        final EditText search = CgUi.field(this, "بحث بالاسم أو الرابط أو الملاحظة", false);
        search.setText(query);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                query = e.toString().trim();
                fillList();
            }
        });
        head.addView(search, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        CgUi.ChipBar chips = new CgUi.ChipBar(this);
        TextView all = chips.add("كل الأقسام", categoryFilter.length() == 0);
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                categoryFilter = "";
                render();
            }
        });
        ArrayList<String> cats = new ArrayList<String>();
        for (int i = 0; i < channels.size(); i++) {
            String c = channels.get(i).optString("category");
            if (!cats.contains(c)) cats.add(c);
        }
        for (int i = 0; i < cats.size(); i++) {
            final String c = cats.get(i);
            TextView t = chips.add(c.length() == 0 ? "بدون قسم" : c, c.equals(categoryFilter));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    categoryFilter = c;
                    render();
                }
            });
        }
        head.addView(chips.scroll, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        body.addView(head);

        ScrollView sv = new ScrollView(this);
        list = CgUi.vbox(this);
        list.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 8), CgUi.dp(this, 12), CgUi.dp(this, 90));
        sv.addView(list, new ScrollView.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        body.addView(sv, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));
        fillList();
    }

    private ArrayList<JSONObject> visible() {
        ArrayList<JSONObject> out = new ArrayList<JSONObject>();
        String q = CgData.normalize(query);
        for (int i = 0; i < channels.size(); i++) {
            JSONObject c = channels.get(i);
            if (categoryFilter.length() > 0 && !categoryFilter.equals(c.optString("category"))) continue;
            if (q.length() > 0) {
                String hay = CgData.normalize(c.optString("name") + " " + c.optString("url") + " " + ov(c.optString("key")).optString("note"));
                if (!hay.contains(q)) continue;
            }
            out.add(c);
        }
        // admin order overlay first, original order kept as tie-breaker
        final ArrayList<JSONObject> orig = new ArrayList<JSONObject>(channels);
        Collections.sort(out, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                int x = ov(a.optString("key")).has("order") ? ov(a.optString("key")).optInt("order") : 100000 + orig.indexOf(a);
                int y = ov(b.optString("key")).has("order") ? ov(b.optString("key")).optInt("order") : 100000 + orig.indexOf(b);
                return x < y ? -1 : x > y ? 1 : 0;
            }
        });
        return out;
    }

    private void fillList() {
        if (list == null) return;
        list.removeAllViews();
        final ArrayList<JSONObject> vis = visible();
        if (categoryFilter.length() > 0 && vis.size() > 0) {
            TextView testAll = CgUi.button(this, "فحص كل قنوات هذا القسم (" + Math.min(vis.size(), 30) + ")", 1);
            testAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    batchTest(vis);
                }
            });
            list.addView(testAll, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
        if (vis.isEmpty()) {
            list.addView(CgUi.empty(this, "لا توجد قنوات", "غيّر البحث أو القسم، أو أضف قناة جديدة."));
            return;
        }
        for (int i = 0; i < vis.size(); i++) {
            final JSONObject ch = vis.get(i);
            final int index = i;
            JSONObject o = ov(ch.optString("key"));
            LinearLayout row = CgUi.hbox(this);
            row.setBackground(CgUi.bg(this, CgCfg.CARD, 14, CgCfg.STROKE));
            int p = CgUi.dp(this, 10);
            row.setPadding(p, p, p, p);
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(CgUi.dp(this, 52), CgUi.dp(this, 52));
            ilp.setMargins(0, 0, CgUi.dp(this, 10), 0);
            row.addView(img, ilp);
            CgImg.load(img, ch.optString("image"), CgUi.dp(this, 104));
            LinearLayout col = CgUi.vbox(this);
            col.addView(CgUi.text(this, ch.optString("name"), 14.5f, o.optBoolean("hidden", false) ? CgCfg.GRAY : CgCfg.TEXT, true));
            col.addView(CgUi.muted(this, ch.optString("category").length() == 0 ? "بدون قسم" : ch.optString("category")));
            LinearLayout tags = CgUi.hbox(this);
            if (o.has("online") && !o.isNull("online")) {
                boolean on = o.optBoolean("online");
                tags.addView(CgUi.badge(this, on ? "يعمل" : "لا يعمل", on ? CgCfg.GOOD : CgCfg.BAD), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 4, 6, 0));
            } else {
                tags.addView(CgUi.badge(this, "لم يُفحص", CgCfg.GRAY), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 4, 6, 0));
            }
            if (o.optBoolean("hidden", false)) tags.addView(CgUi.badge(this, "مخفية", CgCfg.GRAY), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 4, 6, 0));
            if (o.optString("note").length() > 0) tags.addView(CgUi.badge(this, "ملاحظة", CgCfg.WARN), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 4, 6, 0));
            col.addView(tags);
            if (o.optString("last_check_at").length() > 0) {
                col.addView(CgUi.muted(this, "آخر فحص: " + CgUi.ago(o.optString("last_check_at")) + " — " + CgData.checkLabel(o.optString("last_check_status"))));
            }
            row.addView(col, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    detail(ch, vis, index);
                }
            });
            list.addView(row, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
    }

    // ------------------------------------------------------------- detail

    private void detail(final JSONObject ch, final ArrayList<JSONObject> vis, final int index) {
        final String key = ch.optString("key");
        final String oldUrl = ch.optString("url");
        final JSONObject o = ov(key);
        // last on-device test of the edited link: {url, status, time}
        final String[] tested = new String[3];
        LinearLayout box = CgUi.vbox(this);
        box.setPadding(CgUi.dp(this, 16), CgUi.dp(this, 8), CgUi.dp(this, 16), CgUi.dp(this, 8));
        final EditText url = CgUi.urlField(this, "https://.../playlist.m3u8");
        url.setText(oldUrl);
        box.addView(CgUi.labeled(this, "رابط البث", url));
        final TextView result = CgUi.text(this, "", 13, CgCfg.MUTED, false);

        LinearLayout tests = CgUi.hbox(this);
        TextView t1 = CgUi.smallButton(this, "اختبر الرابط", 1);
        t1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String u = url.getText().toString().trim();
                // the saved link's result is stored right away; an edited link's result waits for "حفظ"
                testOne(u, result, key, u.equals(oldUrl), tested);
            }
        });
        tests.addView(t1, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
        TextView reset = CgUi.smallButton(this, "استرجاع الرابط الحالي", 3);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                url.setText(oldUrl);
                result.setText("");
            }
        });
        tests.addView(reset, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
        box.addView(tests);
        box.addView(result, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 10));

        final EditText note = CgUi.field(this, "ملاحظة للأدمن فقط", true);
        note.setText(o.optString("note"));
        box.addView(CgUi.labeled(this, "ملاحظة", note));
        final CgUi.Toggle hidden = CgUi.toggle(this, "إخفاء القناة عن المستخدمين", o.optBoolean("hidden", false));
        box.addView(hidden);

        LinearLayout order = CgUi.hbox(this);
        TextView up = CgUi.smallButton(this, "▲ تقديم", 1);
        up.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reorder(vis, index, -1);
            }
        });
        order.addView(up, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 6, 0));
        TextView down = CgUi.smallButton(this, "▼ تأخير", 1);
        down.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reorder(vis, index, 1);
            }
        });
        order.addView(down, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 6, 0));
        box.addView(order);

        TextView del = CgUi.button(this, "حذف القناة", 2);
        box.addView(del, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 18, 0, 4));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        final AlertDialog dlg = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK).setTitle(ch.optString("name")).setView(sv)
                .setNegativeButton("إغلاق", null)
                .setNeutralButton("نسخ كقناة جديدة", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        android.content.Intent in = new android.content.Intent(CgChannelsActivity.this, CxChannelActivity.class);
                        in.putExtra("copy_key", key);
                        startActivity(in);
                    }
                })
                .setPositiveButton("حفظ", null)
                .create();
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete(ch, dlg);
            }
        });
        dlg.show();
        // validate before closing: a bad link keeps the dialog open with the message under the field
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String u = url.getText().toString().trim();
                if (u.length() == 0) {
                    CgUi.set(result, "✖ رابط البث مطلوب.");
                    result.setTextColor(CgCfg.BAD);
                    return;
                }
                if (!CxJ.isUrl(u)) {
                    CgUi.set(result, "✖ الرابط يجب أن يبدأ بـ http:// أو https://");
                    result.setTextColor(CgCfg.BAD);
                    return;
                }
                dlg.dismiss();
                saveChannel(ch, o, u, note.getText().toString().trim(), hidden.on, tested);
            }
        });
    }

    /** Body that identifies a channel for the channels Worker (id, or category + name + url when there is no id). */
    private static JSONObject channelRef(JSONObject ch) throws Exception {
        JSONObject b = new JSONObject();
        b.put("id", ch.optString("id"));
        b.put("category", ch.optString("category"));
        b.put("name", ch.optString("name"));
        b.put("old_url", ch.optString("url"));
        return b;
    }

    private static CgHttp.Res channelsPost(String path, JSONObject body) throws Exception {
        CgHttp.Res r = CgHttp.call("POST", CgCfg.CHANNELS_API + path, body, false, 30000,
                new String[][]{{"X-Admin-Token", CgCfg.TOKEN}});
        if (r.error != null) throw new Exception(CgHttp.describe(r.error));
        if (r.code == 404 && r.json != null && "Not found".equals(r.json.optString("error"))) {
            throw new Exception("Worker القنوات لا يدعم التعديل والحذف بعد. انسخ ملف channels-system/worker.js إلى sigram-channels-api وانشره.");
        }
        if (r.code == 401 || r.code == 403) throw new Exception("رمز الإدارة غير مقبول في Worker القنوات.");
        if (!r.ok()) throw new Exception(r.message());
        return r;
    }

    private void saveChannel(final JSONObject ch, final JSONObject o, final String newUrl, final String noteText,
                             final boolean hide, final String[] tested) {
        final String key = ch.optString("key");
        final boolean urlChanged = !newUrl.equals(ch.optString("url"));
        showLoading(urlChanged ? "جارٍ تغيير الرابط..." : "جارٍ الحفظ...");
        CgHttp.async(this, new CgHttp.Work<String>() {
            @Override
            public String run() throws Exception {
                if (urlChanged) {
                    JSONObject b = channelRef(ch);
                    b.put("url", newUrl);
                    channelsPost("/channels/update", b);
                }
                JSONObject f = new JSONObject();
                f.put("note", noteText);
                f.put("hidden", hide);
                // the optional backup link is no longer used: clear any old one so the app plays only the main link
                if (o.optString("backup_url").length() > 0) f.put("backup_url", "");
                if (urlChanged) {
                    // the old link's check result no longer applies; keep a test of the new link if one was run
                    boolean known = tested[1] != null && newUrl.equals(tested[0]);
                    f.put("online", known ? (Object) Boolean.valueOf("ok".equals(tested[1])) : JSONObject.NULL);
                    f.put("last_check_at", known ? tested[2] : "");
                    f.put("last_check_status", known ? tested[1] : "");
                }
                JSONObject b = new JSONObject();
                b.put("key", key);
                b.put("fields", f);
                CgHttp.Res m = CgHttp.apiPost("/admin/channel-meta/save", b);
                if (!m.ok()) {
                    if (!urlChanged) throw new Exception(m.message());
                    return "تم تغيير الرابط، لكن تعذر حفظ الملاحظة/الإخفاء: " + m.message();
                }
                return "";
            }
        }, new CgHttp.Done<String>() {
            @Override
            public void ok(String warn) {
                hideLoading();
                if (warn.length() > 0) CgUi.info(CgChannelsActivity.this, "تم جزئياً", warn);
                else CgUi.toast(CgChannelsActivity.this, urlChanged ? "تم تغيير رابط القناة" : "تم الحفظ");
                load();
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(CgChannelsActivity.this, "لم يتم الحفظ", message);
            }
        });
    }

    /** Adds the app's built-in channels to the channels Worker, one after the other, then reloads the list. */
    private void importBuiltins(final ArrayList<String[]> missing) {
        final ArrayList<JSONObject> current = new ArrayList<JSONObject>(channels);
        showLoading("جارٍ إضافة القنوات للإدارة...");
        CgHttp.async(this, new CgHttp.Work<String>() {
            @Override
            public String run() throws Exception {
                int done = 0;
                StringBuilder failed = new StringBuilder();
                for (int i = 0; i < missing.size(); i++) {
                    final String[] ch = missing.get(i);
                    final int n = i + 1;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            loadingMessage("جارٍ إضافة «" + ch[0] + "» (" + n + "/" + missing.size() + ")...");
                        }
                    });
                    try {
                        CgBuiltinChannels.add(ch, CgBuiltinChannels.categoryFor(ch, current));
                        done++;
                    } catch (Exception e) {
                        if (failed.length() > 0) failed.append("\n");
                        failed.append(e.getMessage());
                    }
                }
                if (done == 0) throw new Exception(failed.toString());
                return failed.length() == 0 ? "" : "أُضيفت " + done + " من " + missing.size() + ".\n" + failed;
            }
        }, new CgHttp.Done<String>() {
            @Override
            public void ok(String warn) {
                hideLoading();
                if (warn.length() > 0) CgUi.info(CgChannelsActivity.this, "تم جزئياً", warn);
                else CgUi.toast(CgChannelsActivity.this, missing.size() == 1 ? "أُضيفت القناة للإدارة" : "أُضيفت القنوات للإدارة");
                load();
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(CgChannelsActivity.this, "لم تتم الإضافة", message);
            }
        });
    }

    private void confirmDelete(final JSONObject ch, final AlertDialog parent) {
        final String name = ch.optString("name");
        CgUi.confirm(this, "حذف القناة", "سيتم حذف «" + name + "» نهائياً من التطبيق مع شعارها.\nلا يمكن التراجع عن هذا.",
                "حذف", true, new Runnable() {
                    @Override
                    public void run() {
                        parent.dismiss();
                        deleteChannel(ch);
                    }
                });
    }

    private void deleteChannel(final JSONObject ch) {
        final String name = ch.optString("name");
        showLoading("جارٍ حذف القناة...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                return channelsPost("/channels/delete", channelRef(ch));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                CgUi.toast(CgChannelsActivity.this, "تم حذف القناة «" + name + "»");
                load();
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(CgChannelsActivity.this, "لم يتم الحذف", message);
            }
        });
    }

    private void reorder(ArrayList<JSONObject> vis, int index, int delta) {
        int to = index + delta;
        if (to < 0 || to >= vis.size()) return;
        ArrayList<JSONObject> copy = new ArrayList<JSONObject>(vis);
        JSONObject x = copy.remove(index);
        copy.add(to, x);
        try {
            JSONArray entries = new JSONArray();
            for (int i = 0; i < copy.size(); i++) {
                JSONObject e = new JSONObject();
                e.put("key", copy.get(i).optString("key"));
                JSONObject f = new JSONObject();
                f.put("order", i + 1);
                e.put("fields", f);
                entries.put(e);
            }
            JSONObject b = new JSONObject();
            b.put("key", copy.get(0).optString("key"));
            b.put("entries", entries);
            postMeta(b, "تغيير ترتيب القنوات");
        } catch (Exception ignored) { }
    }

    private void saveFields(String key, JSONObject fields, String label) {
        try {
            JSONObject b = new JSONObject();
            b.put("key", key);
            b.put("fields", fields);
            postMeta(b, label);
        } catch (Exception ignored) { }
    }

    private void postMeta(final JSONObject b, final String label) {
        showLoading("جارٍ الحفظ...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgHttp.apiPost("/admin/channel-meta/save", b));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                load();
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(CgChannelsActivity.this, "لم يتم الحفظ", message);
            }
        });
    }

    // -------------------------------------------------------------- tests

    /** Lightweight on-device stream test: reads at most 2 KB and disconnects (live streams never end). */
    static String[] probe(String url) {
        if (url == null || !url.startsWith("http")) return new String[]{"bad_url", "0"};
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Range", "bytes=0-2047");
            c.setRequestProperty("User-Agent", "CigramAdmin/2");
            int code = c.getResponseCode();
            if (code == 401) return new String[]{"unauthorized", "401"};
            if (code == 403) return new String[]{"forbidden", "403"};
            if (code == 404 || code == 410) return new String[]{"not_found", String.valueOf(code)};
            if (code == 429) return new String[]{"rate_limited", "429"};
            if (code >= 500) return new String[]{"server_error", String.valueOf(code)};
            if (code < 200 || code >= 300) return new String[]{"http_error", String.valueOf(code)};
            String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(java.util.Locale.ROOT);
            InputStream in = c.getInputStream();
            byte[] buf = new byte[2048];
            int n = in.read(buf);
            try { in.close(); } catch (Exception ignored) { }
            if (n <= 0) return new String[]{"empty", String.valueOf(code)};
            if (url.toLowerCase(java.util.Locale.ROOT).contains(".m3u8") || type.contains("mpegurl")) {
                String head = new String(buf, 0, n, "UTF-8");
                if (!head.trim().startsWith("#EXTM3U")) return new String[]{"not_video", String.valueOf(code)};
            } else if (type.contains("text/html") || type.contains("application/json")) {
                return new String[]{"not_video", String.valueOf(code)};
            }
            return new String[]{"ok", String.valueOf(code)};
        } catch (SocketTimeoutException e) {
            return new String[]{"timeout", "0"};
        } catch (Exception e) {
            return new String[]{"unreachable", "0"};
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void testOne(final String url, final TextView out, final String key, final boolean store, final String[] tested) {
        if (url.length() == 0) {
            CgUi.set(out, "✖ لا يوجد رابط.");
            out.setTextColor(CgCfg.BAD);
            return;
        }
        out.setText("جارٍ الاختبار...");
        out.setTextColor(CgCfg.MUTED);
        CgHttp.async(this, new CgHttp.Work<String[]>() {
            @Override
            public String[] run() {
                return probe(url);
            }
        }, new CgHttp.Done<String[]>() {
            @Override
            public void ok(String[] r) {
                boolean good = "ok".equals(r[0]);
                CgUi.set(out, (good ? "✔ " : "✖ ") + CgData.checkLabel(r[0]) + ("0".equals(r[1]) ? "" : " (HTTP " + r[1] + ")"));
                out.setTextColor(good ? CgCfg.GOOD : CgCfg.BAD);
                if (tested != null) {
                    tested[0] = url;
                    tested[1] = r[0];
                    tested[2] = CgUi.toIso(System.currentTimeMillis());
                }
                if (store) storeResult(key, r[0]);
            }

            @Override
            public void fail(String message) {
                CgUi.set(out, "✖ " + message);
                out.setTextColor(CgCfg.BAD);
            }
        });
    }

    private void storeResult(final String key, final String status) {
        try {
            JSONObject f = new JSONObject();
            f.put("online", "ok".equals(status));
            f.put("last_check_at", CgUi.toIso(System.currentTimeMillis()));
            f.put("last_check_status", status);
            JSONObject b = new JSONObject();
            b.put("key", key);
            b.put("fields", f);
            JSONObject local = ov(key);
            local.put("online", "ok".equals(status));
            local.put("last_check_at", CgUi.toIso(System.currentTimeMillis()));
            local.put("last_check_status", status);
            overlay.put(key, local);
            final JSONObject body2 = b;
            CgHttp.bg(new Runnable() {
                @Override
                public void run() {
                    CgHttp.apiPost("/admin/channel-meta/save", body2);
                }
            });
            fillList();
        } catch (Exception ignored) { }
    }

    private void batchTest(final ArrayList<JSONObject> vis) {
        final int total = Math.min(vis.size(), 30);
        CgUi.confirm(this, "فحص عند الطلب", "سيتم فحص " + total + " قناة واحدة بعد الأخرى (حتى 30). لا يُنفَّذ تلقائياً.", "ابدأ", false, new Runnable() {
            @Override
            public void run() {
                showLoading("جارٍ فحص القنوات 0/" + total + "...");
                CgHttp.async(CgChannelsActivity.this, new CgHttp.Work<JSONArray>() {
                    @Override
                    public JSONArray run() throws Exception {
                        JSONArray entries = new JSONArray();
                        for (int i = 0; i < total; i++) {
                            final int done = i;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    loadingMessage("جارٍ فحص القنوات " + done + "/" + total + "...");
                                }
                            });
                            JSONObject ch = vis.get(i);
                            String[] r = probe(ch.optString("url"));
                            JSONObject f = new JSONObject();
                            f.put("online", "ok".equals(r[0]));
                            f.put("last_check_at", CgUi.toIso(System.currentTimeMillis()));
                            f.put("last_check_status", r[0]);
                            JSONObject e = new JSONObject();
                            e.put("key", ch.optString("key"));
                            e.put("fields", f);
                            entries.put(e);
                        }
                        JSONObject b = new JSONObject();
                        b.put("key", vis.get(0).optString("key"));
                        b.put("entries", entries);
                        CgHttp.apiPost("/admin/channel-meta/save", b);
                        return entries;
                    }
                }, new CgHttp.Done<JSONArray>() {
                    @Override
                    public void ok(JSONArray entries) {
                        hideLoading();
                        int bad = 0;
                        for (int i = 0; i < entries.length(); i++) {
                            JSONObject f = entries.optJSONObject(i).optJSONObject("fields");
                            if (f != null && !f.optBoolean("online")) bad++;
                        }
                        CgUi.toast(CgChannelsActivity.this, "تم فحص " + entries.length() + " قناة • لا تعمل: " + bad);
                        load();
                    }

                    @Override
                    public void fail(String message) {
                        hideLoading();
                        CgUi.info(CgChannelsActivity.this, "تعذر الفحص", message);
                    }
                });
            }
        });
    }
}
