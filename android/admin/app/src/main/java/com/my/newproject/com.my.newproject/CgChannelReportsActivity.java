package com.my.newproject;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * «قنوات تحتاج رابطاً» — channels that need a new link. Two sources, shown in this order:
 *
 * <ol>
 *   <li><b>بلّغ المدير</b> — what the viewers reported ({@code GET /admin/channel-reports} on the admin Worker);</li>
 *   <li><b>متوقفة في آخر فحص</b> — the channels the last «Cigram Live Health» scan found stopped (stored on this phone,
 *       see {@link LiveHealthStore}).</li>
 * </ol>
 *
 * 3.0.2 — why this screen showed «تعذر تحميل البيانات · العنصر غير موجود»: the live Worker did not have the route
 * {@code /admin/channel-reports} yet and answers {@code 404 not_found} (the route is in docs/backend/deploy/cigram-admin-api/worker.js, not
 * deployed). The screen treated that 404 as a failure. Now a 404 is understood: the viewers' list is simply unavailable until the Worker is
 * updated, a clear note says so, and the second source keeps the screen useful. A real failure (no network, wrong token, 5xx) still
 * shows the error card with «إعادة المحاولة».
 *
 * One card per channel: its name, why it is listed, the link that stopped working, and «استبدل برابط» which opens
 * {@link ChannelLinkSheet}: the current link, a field for the new one, «حفظ». Saving
 *   1. writes the new link into the channels catalogue (the same {@code /channels/update} as the channels page), and
 *   2. closes the viewers' report ({@code POST /admin/channel-reports/resolve}) when there is one.
 * Nothing else is needed for the viewer: his app sees the new link in the catalogue and tells him «تم تفعيل القناة».
 */
public class CgChannelReportsActivity extends CgBase {

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

    @Override
    protected void onResume() {
        super.onResume();
        if (loadedOnce && !loading) load(true);          // back from the health scan: the stopped list may have changed
    }

    private boolean loading;
    private boolean loadedOnce;
    private String lastSig = "";

    private void load() {
        load(false);
    }

    /** What the Worker said about the viewers' reports. */
    private static final int REPORTS_OK = 0, REPORTS_NOT_DEPLOYED = 1;

    private void load(final boolean silent) {
        if (loading) return;
        loading = true;
        if (!silent) showLoading("جارٍ التحميل...");
        CgHttp.async(this, new CgHttp.Work<Object[]>() {
            @Override
            public Object[] run() throws Exception {
                CgHttp.Res r = CgHttp.apiGet("/admin/channel-reports");
                if (r.ok()) {
                    JSONObject d = r.json == null ? null : r.json.optJSONObject("data");
                    JSONArray items = (d != null ? d : (r.json == null ? new JSONObject() : r.json)).optJSONArray("items");
                    return new Object[]{REPORTS_OK, items == null ? new JSONArray() : items};
                }
                if (r.routeMissing()) return new Object[]{REPORTS_NOT_DEPLOYED, new JSONArray()};
                throw new Exception(r.message());              // no network / wrong token / server error: a real failure
            }
        }, new CgHttp.Done<Object[]>() {
            @Override
            public void ok(Object[] r) {
                loading = false;
                loadedOnce = true;
                if (!silent) hideLoading();
                String sig = r[0] + "|" + r[1] + "|" + LiveHealthStore.down(CgChannelReportsActivity.this);
                if (silent && sig.equals(lastSig)) return;          // nothing changed since the last draw: no repaint
                lastSig = sig;
                render(((Integer) r[0]).intValue(), (JSONArray) r[1]);
            }

            @Override
            public void fail(String message) {
                loading = false;
                if (silent) return;                               // a failed background refresh keeps what is on screen
                hideLoading();
                lastSig = "";
                LinearLayout c = scrollBody();
                c.addView(CgUi.errorCard(CgChannelReportsActivity.this, message, new Runnable() {
                    @Override
                    public void run() {
                        load();
                    }
                }));
            }
        });
    }

    private void render(int reportsState, JSONArray reported) {
        if (!alive()) return;
        LinearLayout c = scrollBody();

        JSONArray all = LiveHealthStore.merge(reported, LiveHealthStore.down(this));
        int reportCount = reported == null ? 0 : reported.length();

        if (reportsState == REPORTS_NOT_DEPLOYED) c.addView(notDeployedCard(), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        if (all.length() == 0) {
            c.addView(CgUi.empty(this, "كل القنوات تعمل", reportsState == REPORTS_NOT_DEPLOYED
                    ? "لم تُفحص القنوات بعد، أو لا توجد قناة متوقفة في آخر فحص."
                    : "لا توجد قناة مبلَّغ عنها ولا متوقفة في آخر فحص."));
            c.addView(scanButton(), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
            return;
        }

        boolean headStopped = false;
        for (int i = 0; i < all.length(); i++) {
            final JSONObject it = all.optJSONObject(i);
            if (it == null) continue;
            boolean fromReport = "report".equals(it.optString("kind"));
            if (i == 0 && fromReport) c.addView(sectionLabel("بلّغ عنها المشاهدون (" + reportCount + ")"));
            if (!fromReport && !headStopped) {
                headStopped = true;
                long last = LiveHealthStore.last(this);
                c.addView(sectionLabel("متوقفة في آخر فحص" + (last > 0 ? " · " + CgUi.ago(CgUi.toIso(last)) : "")));
            }
            c.addView(card(it, fromReport));
        }
        c.addView(scanButton(), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
    }

    private TextView sectionLabel(String text) {
        TextView t = CgUi.text(this, text, 13, CgCfg.MUTED, true);
        t.setGravity(Gravity.RIGHT);
        t.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 6, 4, 8));
        return t;
    }

    private View scanButton() {
        TextView b = CgUi.button(this, "فحص القنوات الآن (Live Health)", 1);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new android.content.Intent(CgChannelReportsActivity.this, LiveHealthActivity.class));
            }
        });
        return b;
    }

    /** Shown instead of the red error: says what is missing and the one thing to do about it. */
    private View notDeployedCard() {
        LinearLayout box = CgUi.card(this);
        box.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.WARN, 0x14), 16, CgUi.alpha(CgCfg.WARN, 0x88)));
        box.addView(CgUi.text(this, "بلاغات المشاهدين غير متاحة بعد", 14.5f, CgCfg.WARN, true));
        TextView why = CgUi.muted(this, "خادم الإدارة المنشور لا يحتوي مسار بلاغات «بلّغ المدير» (HTTP 404). انشر الـ Worker المرفق ثم اضغط «تحديث». "
                + "حتى ذلك الحين تُعرض هنا القنوات المتوقفة من فحص Live Health ويمكنك استبدال روابطها من هذه الصفحة مباشرة.");
        box.addView(why, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 8));
        TextView how = CgUi.smallButton(this, "كيف أنشره؟", 1);
        how.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUi.info(CgChannelReportsActivity.this, "تفعيل بلاغات المشاهدين",
                        "1) Cloudflare ← Workers ← cigram-admin-api ← Edit code.\n"
                                + "2) الصق محتوى الملف docs/backend/deploy/cigram-admin-api/worker.js من المشروع (أو طبّق ملف الإصلاح الصغير docs/backend/channel-reports/apply_patch.py على الكود الحي) ثم Deploy.\n"
                                + "3) ارجع هنا واضغط «تحديث».\n\n"
                                + "التفاصيل الكاملة في docs/backend/channel-reports/README.md.");
            }
        });
        box.addView(how, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        return box;
    }

    private View card(final JSONObject it, boolean fromReport) {
        LinearLayout card = CgUi.card(this);
        card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        LinearLayout head = CgUi.hbox(this);
        head.addView(CgUi.text(this, it.optString("name"), 16, CgCfg.TEXT, true), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        head.addView(CgUi.badge(this, fromReport ? "بلاغ" : "متوقفة", fromReport ? CgCfg.WARN : CgCfg.BAD));
        card.addView(head);
        String meta;
        if (fromReport) {
            meta = "بلّغ عنها " + it.optInt("count", 1) + " · آخر بلاغ " + it.optString("ago");
            if (it.optString("section").length() > 0) meta = it.optString("section") + " · " + meta;
        } else {
            meta = it.optString("detail");
            if (it.optString("section").length() > 0) meta = it.optString("section") + (meta.length() > 0 ? " · " + meta : "");
        }
        if (meta.length() > 0) card.addView(CgUi.muted(this, meta));
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
        return card;
    }

    private void ask(final JSONObject it) {
        ChannelLinkSheet.show(this, it, ChannelLinkSheet.reload(new Runnable() {
            @Override
            public void run() {
                load();
            }
        }));
    }

    /**
     * 19.5 — shared by this screen and the «قنوات تطلب استبدال رابط» block at the top of «إدارة القنوات».
     * «استبدل برابط» → the admin sees the current link and pastes the new one → {@link #save}. {@code done} runs after a successful save.
     */
    static void ask(final CgBase a, final JSONObject it, final Runnable done) {
        ChannelLinkSheet.show(a, it, ChannelLinkSheet.reload(done));
    }

    /**
     * «حفظ»: the new link goes into the catalogue; then, when the channel came from a viewers' report, the report is closed.
     * Closing is best effort: by then the new link is already live for everybody, so a failure there is told, not treated as «not saved».
     */
    static void save(final CgBase a, final JSONObject it, final String url, final ChannelLinkSheet.Saved done) {
        a.showLoading("جارٍ استبدال الرابط...");
        CgHttp.async(a, new CgHttp.Work<String[]>() {
            @Override
            public String[] run() throws Exception {
                CgHttp.Res list = CgHttp.call("GET", CgCfg.CHANNELS_API + "/channels", null, false, 20000, null);
                if (!list.ok() || list.json == null) throw new Exception("لم تُقرأ قائمة القنوات: " + list.message());
                JSONObject ref = find(list.json, it.optString("id"), it.optString("name"), it.optString("url"));
                if (ref == null) throw new Exception("القناة «" + it.optString("name") + "» غير موجودة في القائمة (ربما حُذفت أو تغيّر اسمها).");
                ref.put("url", url);
                CgHttp.Res up = CgHttp.call("POST", CgCfg.CHANNELS_API + "/channels/update", ref, false, 30000,
                        new String[][]{{"X-Admin-Token", CgCfg.TOKEN}});
                if (up.error != null) throw new Exception(CgHttp.describe(up.error));
                if (!up.ok()) throw new Exception(up.message());
                String closeNote = "";
                if (it.optString("key").length() > 0) {
                    try {
                        CgHttp.require(CgHttp.apiPost("/admin/channel-reports/resolve", new JSONObject()
                                .put("key", it.optString("key")).put("name", it.optString("name")).put("url", url)));
                    } catch (Exception e) {
                        closeNote = e.getMessage() == null ? "تعذر إغلاق البلاغ" : e.getMessage();
                    }
                }
                return new String[]{it.optString("name"), closeNote};
            }
        }, new CgHttp.Done<String[]>() {
            @Override
            public void ok(String[] r) {
                a.hideLoading();
                String name = r[0].trim().replaceAll("\\s+", "_");
                if (it.optString("key").length() == 0) CgUi.toast(a, "تم استبدال رابط «" + r[0].trim() + "» وحُفظ في قائمة القنوات.");
                else if (r[1].length() == 0) CgUi.toast(a, "تم التفعيل · سيصل لمن بلّغ: «تم تفعيل القناة #" + name + "»");
                else CgUi.info(a, "حُفظ الرابط الجديد", "الرابط الجديد صار في قائمة القنوات، لكن تعذر إغلاق البلاغ: " + r[1]);
                LiveHealthStore.forget(a, r[0]);
                if (done != null) done.ok(url);
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

    /** The catalogue entry of the channel: the same id, else the same name (and, when several share it, the same old link). */
    static JSONObject find(JSONObject catalogue, String name, String oldUrl) throws Exception {
        return find(catalogue, "", name, oldUrl);
    }

    static JSONObject find(JSONObject catalogue, String id, String name, String oldUrl) throws Exception {
        JSONArray groups = catalogue.optJSONArray("categories");
        JSONObject byName = null;
        String want = name == null ? "" : name.trim();
        String wantId = id == null ? "" : id.trim();
        for (int g = 0; groups != null && g < groups.length(); g++) {
            JSONObject group = groups.optJSONObject(g);
            JSONArray chans = group == null ? null : group.optJSONArray("channels");
            for (int i = 0; chans != null && i < chans.length(); i++) {
                JSONObject ch = chans.optJSONObject(i);
                if (ch == null) continue;
                boolean sameId = wantId.length() > 0 && wantId.equals(ch.optString("id").trim());
                if (!sameId && !want.equalsIgnoreCase(ch.optString("name").trim())) continue;
                JSONObject ref = new JSONObject();
                ref.put("id", ch.optString("id"));
                ref.put("category", group.optString("name"));
                ref.put("name", ch.optString("name"));
                ref.put("old_url", ch.optString("url", ch.optString("link")));
                if (sameId) return ref;
                if (oldUrl != null && oldUrl.equals(ref.optString("old_url"))) return ref;
                if (byName == null) byName = ref;
            }
        }
        return byName;
    }
}
