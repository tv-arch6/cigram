package com.my.newproject;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * «قنوات تحتاج رابطاً» — what the viewers reported with «بلّغ المدير».
 *
 * One card per channel: its name, how many viewers reported it, the link that stopped working. The only action is
 * «استبدل برابط»: the admin pastes the new link and presses «حفظ». Saving
 *   1. writes the new link into the channels catalogue (the same {@code /channels/update} as the channels page), and
 *   2. closes the report ({@code POST /admin/channel-reports/resolve}).
 * Nothing else is needed for the viewer: his app sees the new link in the catalogue and tells him «تم تفعيل القناة».
 */
public class CgChannelReportsActivity extends OpsBase {

    @Override
    protected String screenTitle() {
        return "قنوات تحتاج رابطاً";
    }

    @Override
    protected void onBuild() {
        bar.action("تحديث", new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
        load();
    }

    private void load() {
        getJson("/admin/channel-reports", new JsonDone() {
            @Override
            public void ok(JSONObject d, JSONObject root) {
                render(d.optJSONArray("items"));
            }
        });
    }

    private void render(JSONArray items) {
        if (!alive()) return;
        LinearLayout c = scrollBody();
        if (items == null || items.length() == 0) {
            c.addView(CgUi.empty(this, "كل القنوات تعمل", "لا توجد قناة مبلَّغ عنها الآن."));
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            final JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            LinearLayout card = CgUi.card(this);
            card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
            card.addView(CgUi.text(this, it.optString("name"), 16, CgCfg.TEXT, true));
            String meta = "بلّغ عنها " + it.optInt("count", 1) + " · آخر بلاغ " + it.optString("ago");
            if (it.optString("section").length() > 0) meta = it.optString("section") + " · " + meta;
            card.addView(CgUi.muted(this, meta));
            TextView old = CgUi.muted(this, it.optString("url"));
            old.setTextDirection(View.TEXT_DIRECTION_LTR);
            old.setGravity(Gravity.LEFT);
            old.setMaxLines(2);
            old.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            card.addView(old, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
            TextView replace = CgUi.button(this, "استبدل برابط", 0);
            replace.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ask(it);
                }
            });
            card.addView(replace, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
            c.addView(card);
        }
    }

    private void ask(final JSONObject it) {
        ask(this, it, new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
    }

    /**
     * 19.5 — shared by this screen and the «قنوات تطلب استبدال رابط» block at the top of «إدارة القنوات».
     * «استبدل برابط» → the admin pastes the new link → {@link #save}. {@code done} runs after a successful save.
     */
    static void ask(final CgBase a, final JSONObject it, final Runnable done) {
        CgUi.input(a, "استبدل برابط — " + it.optString("name"), "https://…/stream.m3u8", "", false, new CgUi.Callback<String>() {
            @Override
            public void run(String value) {
                final String url = value == null ? "" : value.trim();
                if (!(url.startsWith("http://") || url.startsWith("https://") || url.startsWith("rtmp") || url.startsWith("rtsp"))) {
                    CgUi.toast(a, "الصق رابط البث كاملاً.");
                    return;
                }
                if (url.equals(it.optString("url").trim())) {
                    CgUi.toast(a, "هذا نفس الرابط المعطّل، الصق رابطاً جديداً.");
                    return;
                }
                save(a, it, url, done);
            }
        });
    }

    /** «حفظ»: the new link goes into the catalogue, then the report is closed. */
    static void save(final CgBase a, final JSONObject it, final String url, final Runnable done) {
        a.showLoading("جارٍ استبدال الرابط...");
        CgHttp.async(a, new CgHttp.Work<String>() {
            @Override
            public String run() throws Exception {
                CgHttp.Res list = CgHttp.call("GET", CgCfg.CHANNELS_API + "/channels", null, false, 20000, null);
                if (!list.ok() || list.json == null) throw new Exception("لم تُقرأ قائمة القنوات: " + list.message());
                JSONObject ref = find(list.json, it.optString("name"), it.optString("url"));
                if (ref == null) throw new Exception("القناة «" + it.optString("name") + "» غير موجودة في القائمة (ربما حُذفت أو تغيّر اسمها).");
                ref.put("url", url);
                CgHttp.Res up = CgHttp.call("POST", CgCfg.CHANNELS_API + "/channels/update", ref, false, 30000,
                        new String[][]{{"X-Admin-Token", CgCfg.TOKEN}});
                if (up.error != null) throw new Exception(CgHttp.describe(up.error));
                if (!up.ok()) throw new Exception(up.message());
                CgHttp.require(CgHttp.apiPost("/admin/channel-reports/resolve", new JSONObject()
                        .put("key", it.optString("key")).put("name", it.optString("name")).put("url", url)));
                return it.optString("name");
            }
        }, new CgHttp.Done<String>() {
            @Override
            public void ok(String name) {
                a.hideLoading();
                CgUi.toast(a, "تم التفعيل · سيصل لمن بلّغ: «تم تفعيل القناة #" + name.trim().replaceAll("\\s+", "_") + "»");
                if (done != null) done.run();
            }

            @Override
            public void fail(String message) {
                a.hideLoading();
                CgUi.info(a, "لم يُحفظ الرابط", message);
            }
        });
    }

    /**
     * 19.5 — the reports as a block for «إدارة القنوات»: a red header with the count, then one card per channel with
     * «استبدل برابط». Returns null when nothing is reported.
     */
    static View block(final CgBase a, JSONArray items, final Runnable reload) {
        if (items == null || items.length() == 0) return null;
        LinearLayout box = CgUi.vbox(a);
        box.setPadding(CgUi.dp(a, 12), CgUi.dp(a, 12), CgUi.dp(a, 12), CgUi.dp(a, 4));
        box.setBackground(CgUi.bg(a, CgUi.alpha(CgCfg.ACCENT_D, 0x14), 18, CgUi.alpha(CgCfg.ACCENT_D, 0x88)));
        LinearLayout head = CgUi.hbox(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = CgUi.text(a, "قنوات تطلب استبدال رابط", 15, CgCfg.TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        head.addView(CgUi.badge(a, String.valueOf(items.length()), CgCfg.ACCENT_G1));
        box.addView(head);
        box.addView(CgUi.muted(a, "بلاغات «بلّغ المدير» من المشاهدين: الرابط معطّل. استبدله وسيصل لهم «تم تفعيل القناة»."),
                CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 10));
        for (int i = 0; i < items.length(); i++) {
            final JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            LinearLayout card = CgUi.card(a);
            LinearLayout row = CgUi.hbox(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(CgUi.text(a, it.optString("name"), 15, CgCfg.TEXT, true), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            row.addView(CgUi.badge(a, "معطّلة", CgCfg.BAD));
            card.addView(row);
            String meta = "بلّغ عنها " + it.optInt("count", 1) + " · آخر بلاغ " + it.optString("ago");
            if (it.optString("section").length() > 0) meta = it.optString("section") + " · " + meta;
            card.addView(CgUi.muted(a, meta), CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
            TextView old = CgUi.muted(a, it.optString("url"));
            old.setTextDirection(View.TEXT_DIRECTION_LTR);
            old.setGravity(Gravity.LEFT);
            old.setMaxLines(1);
            old.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            card.addView(old, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
            TextView replace = CgUi.smallButton(a, "استبدل برابط", 0);
            replace.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ask(a, it, reload);
                }
            });
            card.addView(replace, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
            box.addView(card, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
        return box;
    }

    /** The catalogue entry of the reported channel: same name (and, when several share it, the same old link). */
    static JSONObject find(JSONObject catalogue, String name, String oldUrl) throws Exception {
        JSONArray groups = catalogue.optJSONArray("categories");
        JSONObject byName = null;
        String want = name == null ? "" : name.trim();
        for (int g = 0; groups != null && g < groups.length(); g++) {
            JSONObject group = groups.optJSONObject(g);
            JSONArray chans = group == null ? null : group.optJSONArray("channels");
            for (int i = 0; chans != null && i < chans.length(); i++) {
                JSONObject ch = chans.optJSONObject(i);
                if (ch == null || !want.equalsIgnoreCase(ch.optString("name").trim())) continue;
                JSONObject ref = new JSONObject();
                ref.put("id", ch.optString("id"));
                ref.put("category", group.optString("name"));
                ref.put("name", ch.optString("name"));
                ref.put("old_url", ch.optString("url", ch.optString("link")));
                if (oldUrl != null && oldUrl.equals(ref.optString("old_url"))) return ref;
                if (byName == null) byName = ref;
            }
        }
        return byName;
    }
}
