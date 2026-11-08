package com.my.newproject;

import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Date;

/** صندوق محادثات المعلنين: فرز غير المقروء أولاً، فلاتر حالة، بحث، وتحديث لحظي عبر WebSocket + استطلاع احتياطي. */
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

    @Override protected String screenTitle() { return "صندوق المعلنين"; }

    @Override protected void onBuild() {
        bar.action("تحديث", new Runnable() { @Override public void run() { load(true); } });
        final EditText search = CgUi.field(this, "بحث بالاسم أو البريد أو الوسم...", false);
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
        if (query.length() > 0) qs += "&q=" + AdApi.enc(query);
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
                if (a == null || a.length() == 0) { listBox.addView(CgUi.empty(AdInboxActivity.this, "لا توجد محادثات", "ستظهر هنا رسائل المعلنين فور وصولها.")); return; }
                for (int i = 0; i < a.length(); i++) listBox.addView(card(a.optJSONObject(i)), CgUi.lp(AdInboxActivity.this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
            }
        });
    }

    private View card(final JSONObject t) {
        LinearLayout c = CgUi.card(this);
        c.setClickable(true);
        LinearLayout head = CgUi.hbox(this);
        TextView name = CgUi.text(this, t.optString("name", "معلن"), 15.5f, CgCfg.TEXT, true);
        head.addView(name, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        if (t.optBoolean("online")) head.addView(CgUi.badge(this, "متصل", CgCfg.GOOD), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
        // 2026: a conversation about advertising is marked at a glance (order card / «أريد إعلاناً» in the app)
        if (AdInboxNotifier.isAdRequest(t)) head.addView(CgUi.badge(this, "طلب إعلان", CgCfg.ACCENT_2), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
        int un = t.optInt("unread_admin");
        if (un > 0) head.addView(CgUi.badge(this, String.valueOf(un), CgCfg.ACCENT), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
        c.addView(head);
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
