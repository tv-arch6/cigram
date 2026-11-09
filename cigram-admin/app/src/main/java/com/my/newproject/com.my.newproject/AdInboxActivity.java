package com.my.newproject;

import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Date;

/**
 * 18.2: Cigram Developer Inbox (صندوق المطوّر) — every private conversation of the app's «أريد إعلاناً» / Private Support
 * entry (and the advertisers' threads, same server). Unread first, state filters, search by name / e-mail / tag / INTERNAL ID
 * (the ids are only shown here, behind the admin token), online state, archive; WebSocket + polling.
 *
 * 19.0: the chips show live counts (all / unread / open / pending / archived come from the server), a search also looks into the
 * ARCHIVE and into the TEXT of every message (the server searches the decrypted history), the user's typing is shown on the card,
 * and «الإشعارات والنسخ» shows the real state of push delivery and of the conversation backups. Conversations cannot be deleted:
 * archiving only hides a conversation from «الكل»; the «المؤرشفة» chip lists them all, and a search always includes them.
 */
public class AdInboxActivity extends CgBase {

    private String filter = "all";
    private String query = "";
    private AdWs ws;
    private LinearLayout listBox;
    private JSONArray lastThreads;
    private JSONObject lastCounts;
    private final java.util.HashMap<String, Long> typingUntil = new java.util.HashMap<String, Long>();
    private final java.util.ArrayList<TextView> chipViews = new java.util.ArrayList<TextView>();
    private final String[][] chips = {{"all", "الكل"}, {"unread", "غير مقروء"}, {"open", "مفتوحة"}, {"pending", "بانتظار المعلن"}, {"quoted", "عرض سعر"}, {"won", "تم الاتفاق"}, {"closed", "مغلقة"}, {"archived", "المؤرشفة"}};
    private boolean active;
    private int reqId;
    private int lastUnread;
    private final Runnable refresh = new Runnable() { @Override public void run() { if (alive() && active) load(false); } };
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!alive() || !active) return;
            if (ws == null || !ws.isOpen()) load(false);
            ui().postDelayed(this, 12000);
        }
    };

    @Override protected String screenTitle() { return "صندوق المطوّر"; }

    @Override protected void onBuild() {
        bar.action("تحديث", new Runnable() { @Override public void run() { load(true); } });
        bar.action("الإشعارات والنسخ", new Runnable() { @Override public void run() { showStatus(); } });
        final EditText search = CgUi.field(this, "بحث بالاسم أو البريد أو الوسم أو المعرّف أو نص الرسائل (يشمل المؤرشفة)...", false);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, android.view.KeyEvent e) { query = v.getText().toString().trim(); load(true); return true; }
        });
        body.addView(search, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 12, 8, 12, 4));
        CgUi.ChipBar cb = new CgUi.ChipBar(this);
        final java.util.ArrayList<TextView> cv = chipViews;
        for (final String[] c : chips) {
            final TextView t = cb.add(c[1], c[0].equals(filter));
            cv.add(t);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    filter = c[0];
                    for (int i = 0; i < cv.size(); i++) CgUi.setChipState(cv.get(i), chips[i][0].equals(filter));
                    load(true);
                }
            });
        }
        body.addView(cb.scroll, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        listBox = CgUi.vbox(this);
        listBox.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 8), CgUi.dp(this, 12), CgUi.dp(this, 80));
        sv.addView(listBox);
        body.addView(sv, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));
        load(true);
    }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        if (ws == null) {
            ws = new AdWs(this, "/admin/chat/ws", new AdWs.Listener() {
                @Override public void onOpen() { }
                @Override public void onMessage(JSONObject m) {
                    String t = m.optString("t");
                    if ("typing".equals(t) && "user".equals(m.optString("side"))) {
                        typingUntil.put(m.optString("thread"), m.optBoolean("on") ? System.currentTimeMillis() + 6000L : 0L);
                        render();
                        if (m.optBoolean("on")) ui().postDelayed(new Runnable() { @Override public void run() { if (alive() && active) render(); } }, 6200L);
                        return;
                    }
                    if ("msg".equals(t) || "thread".equals(t) || "thread_unread".equals(t) || "thread_gone".equals(t) || "presence".equals(t)) {
                        ui().removeCallbacks(refresh);
                        ui().postDelayed(refresh, 400);
                    }
                }
                @Override public void onClosed(boolean authFailed) { }
            });
        }
        ws.start();
        ui().removeCallbacks(poll);
        ui().postDelayed(poll, 12000);
        AdFcm.ensureRegistered(this);               // 19.0: push token (no-op while the Firebase settings are absent)
        AdInboxNotifier.askPermissionOnce(this);
        load(false);
    }

    @Override protected void onStop() {
        active = false;
        if (ws != null) ws.stop();
        ui().removeCallbacks(poll);
        super.onStop();
    }

    private void load(final boolean showSpin) {
        final int my = ++reqId; // ردّ قديم (فلتر/بحث سابق) لا يرسم فوق الأحدث
        if (listBox.getChildCount() == 0) listBox.addView(CxUi.skeleton(this, 4));
        String qs = "/admin/chat/threads?limit=200";
        if ("unread".equals(filter)) qs += "&unread=1";
        else if ("archived".equals(filter)) qs += "&archived=1";
        else if (!"all".equals(filter)) qs += "&status=" + filter;
        // 19.0: a search always includes the archive (archived conversations stay accessible and searchable)
        if (query.length() > 0 && !"archived".equals(filter)) qs += "&archived=all";
        // 18.2: the server searches names / e-mails / tags; ids are matched here (the list carries them)
        final boolean idQuery = query.matches("(?i)^(u_|usr_|id:)?[a-z0-9_-]{6,}$") && !query.contains("@");
        if (query.length() > 0 && !idQuery) qs += "&q=" + AdApi.enc(query);
        AdApi.get(this, qs, new AdApi.Cb() {
            @Override public void on(AdApi.Result r) {
                if (!alive() || my != reqId) return;
                boolean hasList = lastThreads != null;
                if (!r.ok && !showSpin && hasList) { bar.subtitle("تعذر التحديث — إعادة المحاولة تلقائياً"); return; } // تحديث صامت فاشل: أبقِ القائمة
                if (!r.ok) {
                    listBox.removeAllViews();
                    bar.subtitle("");
                    listBox.addView(CgUi.errorCard(AdInboxActivity.this, r.message, new Runnable() { @Override public void run() { load(true); } }));
                    return;
                }
                int unread = r.json.optInt("unread_total");
                lastUnread = unread;
                String live = ws != null && ws.isOpen() ? "مباشر" : "تحديث دوري";
                bar.subtitle((unread > 0 ? unread + " رسالة غير مقروءة • " : "") + live);
                lastCounts = r.json.optJSONObject("counts");
                updateChipCounts();
                JSONArray a = r.json.optJSONArray("threads");
                if (idQuery && a != null) {
                    JSONArray f = new JSONArray();
                    String q = query.toLowerCase(java.util.Locale.ROOT).replace("id:", "");
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject t = a.optJSONObject(i);
                        if (t == null) continue;
                        String hay = (t.optString("id") + " " + t.optString("user_id") + " " + t.optString("name") + " " + t.optString("email")).toLowerCase(java.util.Locale.ROOT);
                        if (hay.contains(q)) f.put(t);
                    }
                    a = f;
                }
                lastThreads = a == null ? new JSONArray() : a;
                render();
                if (query.length() >= 2 && !idQuery) searchMessages(my);
            }
        });
    }

    private void updateChipCounts() {
        for (int i = 0; i < chipViews.size() && i < chips.length; i++) {
            String key = chips[i][0];
            int n = lastCounts == null || !lastCounts.has(key) ? -1 : lastCounts.optInt(key);
            chipViews.get(i).setText(n > 0 ? chips[i][1] + " (" + n + ")" : chips[i][1]);
        }
    }

    private void render() {
        if (!alive() || lastThreads == null) return;
        listBox.removeAllViews();
        JSONArray a = lastThreads;
        if (a.length() == 0) {
            listBox.addView(CgUi.empty(this, "لا توجد محادثات", "ستظهر هنا رسائل المستخدمين فور وصولها."));
            return;
        }
        for (int i = 0; i < a.length(); i++) listBox.addView(card(a.optJSONObject(i)), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        if (lastHits != null && lastHits.length() > 0) appendHits();
    }

    // ---- 19.0: search inside the TEXT of every message (server side, archive included)
    private JSONArray lastHits;

    private void searchMessages(final int my) {
        lastHits = null;
        AdApi.get(this, "/admin/chat/search?q=" + AdApi.enc(query), new AdApi.Cb() {
            @Override public void on(AdApi.Result r) {
                if (!alive() || my != reqId || !r.ok) return;
                lastHits = r.json.optJSONArray("hits");
                if (lastHits != null && lastHits.length() > 0) render();
            }
        });
    }

    private void appendHits() {
        listBox.addView(CgUi.text(this, "في نص الرسائل (" + lastHits.length() + ")", 13f, CgCfg.MUTED, true), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 10, 4, 6));
        for (int i = 0; i < lastHits.length(); i++) {
            final JSONObject h = lastHits.optJSONObject(i);
            if (h == null) continue;
            LinearLayout c = CgUi.card(this);
            c.setClickable(true);
            LinearLayout head = CgUi.hbox(this);
            head.addView(CgUi.text(this, h.optString("name", "مستخدم"), 14f, CgCfg.TEXT, true), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            if (h.optBoolean("archived")) head.addView(CgUi.badge(this, "مؤرشفة", CgCfg.GRAY), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
            head.addView(CgUi.muted(this, AdApi.date(h.optLong("ts"))), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 6, 0, 0, 0));
            c.addView(head);
            TextView snip = CgUi.text(this, ("user".equals(h.optString("from")) ? "" : "أنت: ") + h.optString("snippet"), 13f, CgCfg.MUTED, false);
            snip.setMaxLines(3);
            c.addView(snip, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent in = new Intent(AdInboxActivity.this, AdChatActivity.class);
                    in.putExtra("thread", h.optString("thread"));
                    in.putExtra("name", h.optString("name"));
                    startActivity(in);
                }
            });
            listBox.addView(c, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
    }

    // ---- 19.0: what really delivers the notifications / protects the history (real server answers only)
    private void showStatus() {
        final StringBuilder b = new StringBuilder();
        b.append("هذا الجهاز\n• Firebase في هذا البناء: ").append(AdFcm.configured() ? "مُفعّل" : "غير مُفعّل").append("\n• تسجيل الجهاز: ").append(AdFcm.localState(this)).append("\n");
        java.util.List<String> miss = AdFcm.missing();
        if (!miss.isEmpty()) {
            b.append("ينقص لتفعيله (ثوابت الملف AdFcmConfig.java):\n");
            for (String m : miss) b.append("  - ").append(m).append("\n");
            b.append("حتى ذلك الحين: فحص دوري كل 15 دقيقة فقط.\n");
        }
        b.append("\nالخادم (cigram-ads-api)\n");
        AdApi.get(this, "/admin/chat/push-status", new AdApi.Cb() {
            @Override public void on(AdApi.Result r) {
                if (!alive()) return;
                if (!r.ok) b.append("• تعذر قراءة حالة الإشعارات: ").append(r.code == 404 ? "الخادم لم يُحدَّث بعد (19.0)" : r.message).append("\n");
                else {
                    JSONObject f = r.json.optJSONObject("features");
                    JSONObject dv = r.json.optJSONObject("devices");
                    b.append("• FCM على الخادم: ").append(f != null && f.optBoolean("push_fcm") ? "مُفعّل" : "غير مُفعّل").append("\n");
                    b.append("• Webhook: ").append(f != null && f.optBoolean("push_webhook") ? "مُفعّل" : "لا").append("\n");
                    if (dv != null) b.append("• أجهزة الإدارة المسجّلة: ").append(dv.optInt("admin")).append(" • أجهزة المستخدمين: ").append(dv.optInt("user")).append("\n");
                    b.append("• إشعارات بانتظار إعادة المحاولة: ").append(r.json.optInt("queued")).append("\n");
                    JSONObject st = r.json.optJSONObject("stats");
                    if (st != null) b.append("• أُرسل: ").append(st.optInt("sent")).append(" • فشل: ").append(st.optInt("failed")).append(" • رموز منتهية: ").append(st.optInt("dead")).append(" • بلا قناة: ").append(st.optInt("no_channel")).append("\n");
                    if (st != null && st.optString("last_error").length() > 0) b.append("• آخر خطأ: ").append(st.optString("last_error")).append("\n");
                    JSONArray ms = r.json.optJSONArray("missing");
                    for (int i = 0; ms != null && i < ms.length(); i++) b.append("ينقص على الخادم: ").append(ms.optString(i)).append("\n");
                }
                AdApi.get(AdInboxActivity.this, "/admin/chat/backup", new AdApi.Cb() {
                    @Override public void on(AdApi.Result r2) {
                        if (!alive()) return;
                        b.append("\nنسخ المحادثات الاحتياطية\n");
                        if (!r2.ok) b.append("• ").append(r2.code == 404 ? "الخادم لم يُحدَّث بعد (19.0)" : r2.message).append("\n");
                        else {
                            JSONObject st = r2.json.optJSONObject("state");
                            b.append("• مُعدّة: ").append(r2.json.optBoolean("configured") ? "نعم" : "لا (اربط R2 خاصاً باسم ADS_BUCKET أو CHAT_BACKUP_BUCKET)").append("\n");
                            b.append("• محادثات: ").append(r2.json.optInt("threads")).append(" • متأخرة عن النسخ: ").append(r2.json.optInt("threads_behind")).append(" • رسائل غير منسوخة: ").append(r2.json.optInt("messages_unsaved")).append("\n");
                            if (st != null && st.optLong("last_ok") > 0) b.append("• آخر نسخة ناجحة: ").append(AdApi.date(st.optLong("last_ok"))).append("\n");
                            else b.append("• لم تنجح أي نسخة بعد.\n");
                            if (st != null && st.optString("error").length() > 0) b.append("• خطأ: ").append(st.optString("error")).append("\n");
                        }
                        b.append("\nالمحادثات دائمة: لا حذف ولا مسح تلقائي. الأرشفة تُخفي فقط.");
                        CgUi.dialog(AdInboxActivity.this).setTitle("الإشعارات والنسخ الاحتياطي").setMessage(b.toString()).setPositiveButton("حسناً", null).show();
                    }
                });
            }
        });
    }

    private View card(final JSONObject t) {
        LinearLayout c = CgUi.card(this);
        c.setClickable(true);
        LinearLayout head = CgUi.hbox(this);
        TextView name = CgUi.text(this, t.optString("name", "مستخدم"), 15.5f, CgCfg.TEXT, true);
        head.addView(name, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        if (t.optBoolean("online")) head.addView(CgUi.badge(this, "متصل", CgCfg.GOOD), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
        // 2026: a conversation about advertising is marked at a glance (order card / «أريد إعلاناً» in the app)
        if (AdInboxNotifier.isAdRequest(t)) head.addView(CgUi.badge(this, "طلب إعلان", CgCfg.ACCENT_2), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
        int un = t.optInt("unread_admin");
        if (un > 0) head.addView(CgUi.badge(this, String.valueOf(un), CgCfg.ACCENT), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
        c.addView(head);
        // 18.2: internal ids (admin only) — the thread key and, when the server sends it, the account id
        String ids = "ID " + t.optString("id") + (t.optString("user_id").length() > 0 ? " · " + t.optString("user_id") : "");
        TextView idv = CgUi.text(this, ids, 11.5f, CgCfg.FAINT, false);
        CgUi.ltr(idv);
        c.addView(idv);
        if (!t.optBoolean("online") && t.optLong("user_seen") > 0) {
            c.addView(CgUi.text(this, "آخر ظهور " + new java.text.SimpleDateFormat("d/M HH:mm", java.util.Locale.US).format(new java.util.Date(t.optLong("user_seen"))), 11.5f, CgCfg.MUTED, false));
        }
        String prev = t.optString("preview");
        Long typing = typingUntil.get(t.optString("id"));
        boolean typingNow = typing != null && typing > System.currentTimeMillis();
        if (t.optBoolean("archived")) head.addView(CgUi.badge(this, "مؤرشفة", CgCfg.GRAY), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
        TextView p = CgUi.text(this, typingNow ? "يكتب الآن…" : ("user".equals(t.optString("last_from")) ? "" : "أنت: ") + prev, 13.5f, typingNow ? CgCfg.GOOD : un > 0 ? CgCfg.TEXT : CgCfg.MUTED, un > 0 || typingNow);
        p.setMaxLines(2);
        c.addView(p, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 4));
        LinearLayout foot = CgUi.hbox(this);
        foot.addView(CgUi.badge(this, AdApi.threadStatusLabel(t.optString("status")), CgCfg.WARN));
        JSONArray tags = t.optJSONArray("tags");
        for (int i = 0; tags != null && i < tags.length() && i < 3; i++) foot.addView(CgUi.badge(this, tags.optString(i), CgCfg.ACCENT), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
        if (t.optBoolean("banned")) foot.addView(CgUi.badge(this, "محظور", CgCfg.BAD), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
        if (t.optString("assigned").length() > 0) foot.addView(CgUi.muted(this, "← " + t.optString("assigned")), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 6, 0, 0, 0));
        View sp = new View(this);
        foot.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        foot.addView(CgUi.muted(this, AdApi.date(t.optLong("last_ts"))));
        c.addView(foot);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent in = new Intent(AdInboxActivity.this, AdChatActivity.class);
                in.putExtra("thread", t.optString("id"));
                in.putExtra("name", t.optString("name"));
                startActivity(in);
            }
        });
        return c;
    }
}
