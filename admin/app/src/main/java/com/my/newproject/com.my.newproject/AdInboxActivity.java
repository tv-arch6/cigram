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
 */
public class AdInboxActivity extends CgBase {

    private String filter = "all";
    private String query = "";
    private AdWs ws;
    private LinearLayout listBox;
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
        final EditText search = CgUi.field(this, "بحث بالاسم أو البريد أو الوسم أو المعرّف الداخلي...", false);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int a, android.view.KeyEvent e) { query = v.getText().toString().trim(); load(true); return true; }
        });
        body.addView(search, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 12, 8, 12, 4));
        final String[][] chips = {{"all", "الكل"}, {"unread", "غير مقروء"}, {"open", "مفتوحة"}, {"pending", "بانتظار المعلن"}, {"quoted", "عرض سعر"}, {"won", "تم الاتفاق"}, {"closed", "مغلقة"}, {"archived", "المؤرشفة"}};
        CgUi.ChipBar cb = new CgUi.ChipBar(this);
        final java.util.ArrayList<TextView> cv = new java.util.ArrayList<TextView>();
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
        // 18.2: the server searches names / e-mails / tags; ids are matched here (the list carries them)
        final boolean idQuery = query.matches("(?i)^(u_|usr_|id:)?[a-z0-9_-]{6,}$") && !query.contains("@");
        if (query.length() > 0 && !idQuery) qs += "&q=" + AdApi.enc(query);
        AdApi.get(this, qs, new AdApi.Cb() {
            @Override public void on(AdApi.Result r) {
                if (!alive() || my != reqId) return;
                boolean hasList = listBox.getChildCount() > 0 && !(listBox.getChildAt(0) instanceof LinearLayout && listBox.getChildCount() == 1 && listBox.getChildAt(0).getAnimation() != null);
                if (!r.ok && !showSpin && hasList) { bar.subtitle("تعذر التحديث — إعادة المحاولة تلقائياً"); return; } // تحديث صامت فاشل: أبقِ القائمة
                listBox.removeAllViews();
                if (!r.ok) {
                    bar.subtitle("");
                    listBox.addView(CgUi.errorCard(AdInboxActivity.this, r.message, new Runnable() { @Override public void run() { load(true); } }));
                    return;
                }
                int unread = r.json.optInt("unread_total");
                lastUnread = unread;
                String live = ws != null && ws.isOpen() ? "مباشر" : "تحديث دوري";
                bar.subtitle((unread > 0 ? unread + " رسالة غير مقروءة • " : "") + live);
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
                if (a == null || a.length() == 0) { listBox.addView(CgUi.empty(AdInboxActivity.this, "لا توجد محادثات", "ستظهر هنا رسائل المستخدمين فور وصولها.")); return; }
                for (int i = 0; i < a.length(); i++) listBox.addView(card(a.optJSONObject(i)), CgUi.lp(AdInboxActivity.this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
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
        TextView p = CgUi.text(this, ("user".equals(t.optString("last_from")) ? "" : "أنت: ") + prev, 13.5f, un > 0 ? CgCfg.TEXT : CgCfg.MUTED, un > 0);
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
