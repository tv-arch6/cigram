package com.my.newproject;

import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * «طلبات المستخدمين»: what the viewers asked for from Cigram (طلب فيلم / مسلسل / قناة, and «اطلبه»
 * in the search). The requests live in cigram-user-api; the admin Worker (v8) reads the same
 * records, so a status set here reaches the viewer's «طلباتي» and its notification.
 *
 *   [Telegram card]  setup steps, or «شغّال» + test
 *   [summary]        open / in progress / added / unavailable
 *   [الأكثر طلباً | كل الطلبات]
 *     most requested  one card per title: count, notes, «تمت الإضافة» for every viewer at once
 *     all requests    filter chips, one row per request, tap -> change status
 *
 * Worker routes: GET /admin/requests, POST /admin/requests/status, POST /admin/requests/title-status,
 * GET /admin/requests/telegram, POST /admin/requests/telegram/link, POST /admin/requests/telegram/test.
 */
public class NxRequestsActivity extends CxBase {

    private static final String[] FILTERS = {"open", "pending", "in_progress", "added", "unavailable", "all"};
    private static final String[] FILTER_NAMES = {"المفتوحة", "قيد المراجعة", "قيد العمل", "تمت الإضافة", "غير متوفر", "الكل"};
    private static final String[] STATUSES = {"pending", "in_progress", "added", "unavailable"};
    private static final String[] STATUS_NAMES = {"قيد المراجعة", "قيد العمل", "تمت الإضافة", "غير متوفر"};

    private int view;            // 0 most requested, 1 all requests
    private int filter;          // index in FILTERS
    private JSONObject data;
    private JSONObject tg;
    private String loadError = "";
    private boolean loading;
    private boolean linkWaiting;
    private boolean tgFailed;

    @Override
    protected String screenTitle() {
        return "طلبات المستخدمين";
    }

    @Override
    protected void onBuild() {
        initCx(null);
        try {
            bar.view.setBackgroundColor(NxUi.BG);
        } catch (Throwable ignored) { }
        bar.action("تحديث", new Runnable() {
            @Override
            public void run() {
                load(true);
            }
        });
        setBottomVisible(false);
        load(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // coming back from Telegram after pressing Start: finish the link by itself
        if (linkWaiting && tg != null && !tg.optBoolean("linked")) link(true);
    }

    private int dp(float v) {
        return CgUi.dp(this, v);
    }

    // ------------------------------------------------------------------ loading

    private void load(final boolean withTelegram) {
        if (loading) return;
        loading = true;
        if (data == null) {
            page.removeAllViews();
            page.addView(loadingRow("جارِ جلب الطلبات…"));
        }
        final String status = FILTERS[filter];
        exec(new Job<JSONObject[]>() {
            @Override
            public JSONObject[] run() throws Exception {
                JSONObject t = null;
                if (withTelegram || tg == null) {
                    try {
                        t = CxApi.get("/admin/requests/telegram");
                        tgFailed = false;
                    } catch (CxApi.Failure ignored) {
                        t = null;
                        tgFailed = true;
                    }
                }
                JSONObject d = CxApi.get("/admin/requests?status=" + status + "&limit=300");
                return new JSONObject[]{d, t};
            }
        }, new Cb<JSONObject[]>() {
            @Override
            public void ok(JSONObject[] r) {
                loading = false;
                loadError = "";
                data = r[0];
                if (r[1] != null) tg = r[1];
                render();
            }

            @Override
            public void fail(CxApi.Failure f) {
                loading = false;
                loadError = f.getMessage();
                if (f.is("REQUESTS_KV_MISSING")) data = null;
                render();
            }
        });
    }

    // ------------------------------------------------------------------ render

    private void render() {
        page.removeAllViews();
        page.addView(telegramCard());
        if (data == null) {
            if (loadError.length() > 0) {
                page.addView(CgUi.errorCard(this, loadError, new Runnable() {
                    @Override
                    public void run() {
                        load(true);
                    }
                }), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 10, 0, 0));
            }
            return;
        }
        if (loadError.length() > 0) snack(loadError, CxUi.WARN);
        page.addView(summary(), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 10, 0, 0));
        page.addView(NxUi.segmented(this, new String[]{"الأكثر طلباً", "كل الطلبات"}, null, view, new NxUi.SegCb() {
            @Override
            public void run(int index) {
                view = index;
                render();
            }
        }), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        if (view == 0) renderTop();
        else renderAll();
        String note = data.optString("limit_note", "");
        if (note.length() > 0) {
            TextView t = NxUi.text(this, note, 11, NxUi.DIM, false);
            t.setGravity(Gravity.CENTER);
            page.addView(t, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        }
    }

    private View loadingRow(String text) {
        LinearLayout row = CgUi.hbox(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(40), 0, dp(40));
        row.addView(CgUi.spinner(this), new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView t = NxUi.text(this, text, 13, NxUi.SUB, false);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(NxUi.WRAP, NxUi.WRAP);
        l.setMargins(dp(10), 0, 0, 0);
        row.addView(t, l);
        return row;
    }

    private View summary() {
        JSONObject s = data.optJSONObject("summary");
        if (s == null) s = new JSONObject();
        LinearLayout row = CgUi.hbox(this);
        row.addView(stat(s.optInt("pending"), "قيد المراجعة", CgCfg.WARN), new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f));
        row.addView(stat(s.optInt("in_progress"), "قيد العمل", NxUi.TEAL), CgUi.lpWeight(this, 1f, 8, 0, 0, 0));
        row.addView(stat(s.optInt("added"), "تمت الإضافة", CgCfg.GOOD), CgUi.lpWeight(this, 1f, 8, 0, 0, 0));
        row.addView(stat(s.optInt("unavailable"), "غير متوفر", CgCfg.BAD), CgUi.lpWeight(this, 1f, 8, 0, 0, 0));
        return row;
    }

    private View stat(int n, String label, int color) {
        LinearLayout box = CgUi.vbox(this);
        box.setGravity(Gravity.CENTER);
        box.setBackground(NxUi.surface(this, 16));
        box.setPadding(dp(6), dp(10), dp(6), dp(10));
        TextView v = NxUi.text(this, NxUi.num(n), 20, color, true);
        v.setGravity(Gravity.CENTER);
        box.addView(v, new LinearLayout.LayoutParams(NxUi.MATCH, NxUi.WRAP));
        TextView l = NxUi.text(this, label, 10.5f, NxUi.SUB, false);
        l.setGravity(Gravity.CENTER);
        l.setMaxLines(1);
        l.setEllipsize(TextUtils.TruncateAt.END);
        box.addView(l, new LinearLayout.LayoutParams(NxUi.MATCH, NxUi.WRAP));
        return box;
    }

    // ------------------------------------------------------------------ most requested

    private void renderTop() {
        JSONArray top = data.optJSONArray("top");
        if (top == null || top.length() == 0) {
            page.addView(CgUi.empty(this, "ماكو طلبات مفتوحة", "أول ما أحد يطلب فيلم أو مسلسل يطلع هنا."));
            return;
        }
        for (int i = 0; i < top.length(); i++) {
            JSONObject g = top.optJSONObject(i);
            if (g != null) page.addView(topCard(g, i + 1), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 10, 0, 0));
        }
    }

    private View topCard(final JSONObject g, int rank) {
        LinearLayout card = NxUi.card(this);
        LinearLayout head = CgUi.hbox(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        String kind = g.optString("kind");
        head.addView(kindTile(kind), new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout col = CgUi.vbox(this);
        TextView title = NxUi.text(this, g.optString("title"), 15.5f, NxUi.TEXT, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);
        col.addView(NxUi.text(this, g.optString("kind_text") + " • " + g.optString("status_text") + " • آخر طلب " + g.optString("last_ago"), 11.5f, NxUi.SUB, false));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        cl.setMargins(dp(10), 0, dp(8), 0);
        head.addView(col, cl);
        int color = rank == 1 ? NxUi.PINK : rank <= 3 ? NxUi.ORANGE : NxUi.BLUE;
        head.addView(NxUi.pill(this, g.optString("count_text"), color), new LinearLayout.LayoutParams(NxUi.WRAP, NxUi.WRAP));
        card.addView(head);

        JSONArray notes = g.optJSONArray("notes");
        if (notes != null && notes.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < notes.length(); i++) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(notes.optString(i));
            }
            TextView n = NxUi.text(this, "📝 " + sb, 12, NxUi.SUB, false);
            n.setMaxLines(3);
            n.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(n, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 8, 0, 0));
        }

        LinearLayout actions = CgUi.hbox(this);
        TextView added = NxUi.primary(this, "تمت الإضافة ✓");
        added.setTextSize(13.5f);
        added.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUi.confirm(NxRequestsActivity.this, "تمت إضافة «" + g.optString("title") + "»؟",
                        "كل اللي طلبوه (" + g.optString("count_text") + ") يوصلهم إشعار بالتطبيق إنه صار متوفر. تأكد إنه منشور بسيكرام قبل.",
                        "نعم، تمت", false, new Runnable() {
                            @Override
                            public void run() {
                                titleStatus(g, "added", null);
                            }
                        });
            }
        });
        TextView copyTop = NxUi.secondary(this, "انسخ الاسم");
        copyTop.setTextSize(13.5f);
        copyTop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyName(g.optString("title"));
                if ("pending".equals(g.optString("status", "pending"))) titleStatus(g, "in_progress", null);
            }
        });
        actions.addView(copyTop, new LinearLayout.LayoutParams(0, NxUi.WRAP, 0.8f));
        actions.addView(added, CgUi.lpWeight(this, 1f, 8, 0, 0, 0));
        TextView more = NxUi.secondary(this, "حالة أخرى");
        more.setTextSize(13.5f);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUi.choose(NxRequestsActivity.this, g.optString("title"), new CharSequence[]{"قيد العمل", "غير متوفر"}, new CgUi.IntCb() {
                    @Override
                    public void run(int index) {
                        if (index == 0) titleStatus(g, "in_progress", null);
                        else askNote("سبب عدم التوفر (يشوفه المستخدم، اختياري)", new CgUi.Callback<String>() {
                            @Override
                            public void run(String note) {
                                titleStatus(g, "unavailable", note);
                            }
                        });
                    }
                });
            }
        });
        actions.addView(more, CgUi.lpWeight(this, 0.8f, 8, 0, 0, 0));
        card.addView(actions, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        return card;
    }

    /** «انسخ الاسم»: the title goes to the clipboard, ready to paste into the add form or a search. */
    private void copyName(String title) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Cigram", title));
            snack("نُسخ الاسم: " + title, CxUi.GOOD);
        } catch (Throwable ignored) { }
    }

    private void titleStatus(final JSONObject g, final String status, final String note) {
        task("جارِ الحفظ…", new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("kind", g.optString("kind"));
                b.put("title_norm", g.optString("title_norm"));
                b.put("status", status);
                if (note != null) b.put("note", note);
                return CxApi.post("/admin/requests/title-status", b);
            }
        }, new Ok<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                snack(r.optString("message", "تم ✅"), CxUi.GOOD);
                load(false);
            }
        }, null);
    }

    // ------------------------------------------------------------------ all requests

    private void renderAll() {
        LinearLayout chips = CgUi.hbox(this);
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        for (int i = 0; i < FILTERS.length; i++) {
            final int idx = i;
            TextView c = CgUi.chip(this, FILTER_NAMES[i], i == filter);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (filter == idx) return;
                    filter = idx;
                    load(false);
                }
            });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(NxUi.WRAP, NxUi.WRAP);
            l.setMargins(i == 0 ? 0 : dp(6), 0, 0, 0);
            chips.addView(c, l);
        }
        hs.addView(chips);
        page.addView(hs, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 2));

        JSONArray items = data.optJSONArray("items");
        if (items == null || items.length() == 0) {
            page.addView(CgUi.empty(this, "ماكو طلبات هنا", null));
            return;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject r = items.optJSONObject(i);
            if (r != null) page.addView(itemRow(r), NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 8, 0, 0));
        }
    }

    private View itemRow(final JSONObject r) {
        LinearLayout row = CgUi.hbox(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        NxUi.pressable(row, NxUi.surface(this, 16), 16);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.addView(kindTile(r.optString("kind")), new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout col = CgUi.vbox(this);
        TextView t = NxUi.text(this, r.optString("title"), 14.5f, NxUi.TEXT, true);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t);
        String who = r.optString("user_tag", "");
        col.addView(NxUi.text(this, "يريد " + (who.length() > 0 ? who + " " : "") + r.optString("kind_text"), 12, NxUi.TEXT, false));
        String meta = r.optString("ago");
        if (r.optInt("same_title") > 1) meta += " • " + r.optInt("same_title") + " طلبوه";
        if (r.optString("app_version").length() > 0) meta += " • v" + r.optString("app_version");
        col.addView(NxUi.text(this, meta, 11, NxUi.SUB, false));
        if (r.optString("note").length() > 0) {
            TextView n = NxUi.text(this, "📝 " + r.optString("note"), 12, NxUi.SUB, false);
            n.setMaxLines(2);
            n.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(n);
        }
        if (r.optString("admin_note").length() > 0) {
            col.addView(NxUi.text(this, "ملاحظتك: " + r.optString("admin_note"), 11.5f, NxUi.DIM, false));
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        cl.setMargins(dp(10), 0, dp(8), 0);
        row.addView(col, cl);
        LinearLayout side = CgUi.vbox(this);
        side.setGravity(Gravity.CENTER);
        side.addView(NxUi.pill(this, r.optString("status_text"), statusColor(r.optString("status"))), new LinearLayout.LayoutParams(NxUi.WRAP, NxUi.WRAP));
        TextView copy = NxUi.secondary(this, "انسخ الاسم");
        copy.setTextSize(12f);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyName(r.optString("title"));
                // copying the name = the admin took the request: the viewer gets «المدير وافق، سيتم إضافته قريبًا، اصبر»
                if ("pending".equals(r.optString("status"))) oneStatus(r, "in_progress", null);
            }
        });
        LinearLayout.LayoutParams cpl = new LinearLayout.LayoutParams(NxUi.WRAP, NxUi.WRAP);
        cpl.setMargins(0, dp(6), 0, 0);
        side.addView(copy, cpl);
        row.addView(side, new LinearLayout.LayoutParams(NxUi.WRAP, NxUi.WRAP));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CgUi.choose(NxRequestsActivity.this, r.optString("title"), STATUS_NAMES, new CgUi.IntCb() {
                    @Override
                    public void run(int index) {
                        final String st = STATUSES[index];
                        if (st.equals(r.optString("status"))) return;
                        if ("unavailable".equals(st)) {
                            askNote("سبب عدم التوفر (يشوفه المستخدم، اختياري)", new CgUi.Callback<String>() {
                                @Override
                                public void run(String note) {
                                    oneStatus(r, st, note);
                                }
                            });
                        } else {
                            oneStatus(r, st, null);
                        }
                    }
                });
            }
        });
        return row;
    }

    private void oneStatus(final JSONObject r, final String status, final String note) {
        task("جارِ الحفظ…", new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("id", r.optString("id"));
                b.put("status", status);
                if (note != null) b.put("note", note);
                return CxApi.post("/admin/requests/status", b);
            }
        }, new Ok<JSONObject>() {
            @Override
            public void ok(JSONObject res) {
                snack(res.optString("message", "تم ✅"), CxUi.GOOD);
                load(false);
            }
        }, null);
    }

    private void askNote(String title, final CgUi.Callback<String> cb) {
        CgUi.input(this, title, "مثال: مو متوفر بجودة عالية حالياً", "", false, cb);
    }

    private int statusColor(String status) {
        if ("in_progress".equals(status)) return NxUi.TEAL;
        if ("added".equals(status)) return CgCfg.GOOD;
        if ("unavailable".equals(status)) return CgCfg.BAD;
        return CgCfg.WARN;
    }

    private View kindTile(String kind) {
        int icon = "series".equals(kind) ? CgIcons.TV : "channel".equals(kind) ? CgIcons.SIGNAL : CgIcons.FILM;
        int color = "series".equals(kind) ? NxUi.VIOLET : "channel".equals(kind) ? NxUi.TEAL : NxUi.BLUE;
        ImageView iv = NxUi.iconTile(this, NxIcons.icon(icon, color), NxUi.tint(this, color, 12), 9);
        return iv;
    }

    // ------------------------------------------------------------------ Telegram

    private View telegramCard() {
        LinearLayout card = NxUi.card(this);
        LinearLayout head = CgUi.hbox(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        boolean ready = tg != null && tg.optBoolean("ready");
        int color = ready ? CgCfg.GOOD : NxUi.BLUE;
        head.addView(NxUi.iconTile(this, NxIcons.icon(CgIcons.SEND, color), NxUi.tint(this, color, 12), 9), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout col = CgUi.vbox(this);
        col.addView(NxUi.text(this, ready ? "إشعارات تليكرام شغّالة ✅" : "إشعارات تليكرام", 15, NxUi.TEXT, true));
        String sub;
        if (tg == null) sub = tgFailed ? "تعذر التحقق من إعداد تليكرام. اضغط تحديث." : "جارِ التحقق…";
        else if (ready) {
            String name = tg.optString("chat_name", "");
            sub = (name.length() > 0 ? "توصل إلى " + name + " • " : "") + "آخر فحص " + (tg.optString("last_scan_ago").length() > 0 ? tg.optString("last_scan_ago") : "—");
        } else sub = tg.optString("message", "");
        TextView st = NxUi.text(this, sub, 11.5f, NxUi.SUB, false);
        col.addView(st);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        cl.setMargins(dp(10), 0, 0, 0);
        head.addView(col, cl);
        card.addView(head);

        if (tg == null) return card;
        LinearLayout actions = CgUi.hbox(this);
        boolean tokenSet = tg.optBoolean("token_set");
        boolean linked = tg.optBoolean("linked");
        if (tokenSet && !linked) {
            TextView link = NxUi.primary(this, linkWaiting ? "تأكيد الربط" : "ربط تليكرام");
            link.setTextSize(13.5f);
            link.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    link(false);
                }
            });
            actions.addView(link, new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f));
        }
        if (linked) {
            TextView test = NxUi.secondary(this, "رسالة تجربة");
            test.setTextSize(13.5f);
            test.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    testTelegram();
                }
            });
            actions.addView(test, new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f));
        }
        if (actions.getChildCount() > 0) card.addView(actions, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        if (!tokenSet || !tg.optBoolean("kv_bound")) {
            TextView how = NxUi.text(this, setupText(), 12, NxUi.SUB, false);
            how.setLineSpacing(dp(2), 1f);
            card.addView(how, NxUi.lp(this, NxUi.MATCH, NxUi.WRAP, 0, 10, 0, 0));
        }
        return card;
    }

    private String setupText() {
        StringBuilder sb = new StringBuilder("من Cloudflare ← cigram-admin-api ← Settings:\n");
        if (!tg.optBoolean("kv_bound")) sb.append("• Bindings ← Add ← KV namespace: الاسم REQUESTS_KV، والقاعدة CIGRAM_KV\n");
        if (!tg.optBoolean("token_set")) sb.append("• Variables and Secrets ← Add ← Secret: الاسم TELEGRAM_BOT_TOKEN، والقيمة توكن البوت من @BotFather\n");
        sb.append("وبعدها ارجع هنا واضغط تحديث.");
        return sb.toString();
    }

    private void link(final boolean silent) {
        exec(new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                return CxApi.post("/admin/requests/telegram/link", new JSONObject());
            }
        }, new Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                if ("linked".equals(r.optString("status"))) {
                    linkWaiting = false;
                    snack(r.optString("message", "تم ربط تليكرام ✅"), CxUi.GOOD);
                    load(true);
                    return;
                }
                String url = r.optString("link", "");
                if (silent) return;
                linkWaiting = true;
                if (url.startsWith("https://t.me/")) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Throwable e) {
                        CgUi.info(NxRequestsActivity.this, "افتح هذا الرابط", url);
                    }
                }
                snack(r.optString("message", "اضغط Start بالبوت، وبعدها ارجع هنا."), CxUi.INFO);
                render();
            }

            @Override
            public void fail(CxApi.Failure f) {
                if (!silent) failDialog(f.getMessage(), null);
            }
        });
    }

    private void testTelegram() {
        task("جارِ الإرسال…", new Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                return CxApi.post("/admin/requests/telegram/test", new JSONObject());
            }
        }, new Ok<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                snack(r.optString("message", "انرسلت ✅"), CxUi.GOOD);
            }
        }, null);
    }
}
