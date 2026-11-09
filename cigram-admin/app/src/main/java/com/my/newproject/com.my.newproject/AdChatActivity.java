package com.my.newproject;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * شاشة محادثة الأدمن مع معلن: رسائل لحظية، وسائط وصوت، ردود سريعة (قوالب)، عروض أسعار، تحويل الطلب لحملة،
 * وإدارة المحادثة (حالة، وسوم، ملاحظات داخلية، تعيين، حظر، أرشفة، تصدير). Extras: thread, name.
 */
public class AdChatActivity extends CgBase {

    private static final int REQ_PICK = 81, REQ_PERM_AUDIO = 83;

    static final class Msg {
        long seq;
        String id = "", from = "admin", type = "text", text = "", clientId = "", failMsg = "";
        long ts, delivered, read;
        boolean deleted;
        JSONObject media, quote, order, reply;
        int state; // 0 خادم، 1 إرسال، 2 فشل
        int progress = -1;
        String localPath;
        AtomicBoolean cancel;
        long replyTo;
    }

    private final ArrayList<Msg> items = new ArrayList<Msg>();
    private String thread = "", name = "معلن";
    private JSONObject threadInfo = new JSONObject();
    private boolean loaded, hasMore = true, loadingOlder, atBottom = true, userTyping, userOnline, active;
    private long lastSeq, replyToSeq;
    private String replyPreview;
    private AdWs ws;
    private ListView list;
    private ChatAdapter adapter;
    private EditText input;
    private TextView sub, replyText, timerText;
    private View replyBar, recBar, sendIcon, micIcon, banner;
    private TextView bannerText;
    private AdVoice.Recorder recorder;
    private boolean recCancelled;
    private float recDownX;
    private int maxBubble;
    private JSONObject settings;

    private final Runnable pollRun = new Runnable() {
        @Override public void run() {
            if (!alive() || !active) return;
            if (ws == null || !ws.isOpen()) fetchNew();
            ui().postDelayed(this, 6000);
        }
    };

    @Override protected String screenTitle() { return "محادثة خاصة"; }

    @Override protected void onBuild() {
        thread = getIntent().getStringExtra("thread");
        if (thread == null) thread = "";
        String n = getIntent().getStringExtra("name");
        if (n != null && n.length() > 0) name = n;
        maxBubble = (int) (getResources().getDisplayMetrics().widthPixels * 0.78f);
        bar.titleView.setText(name);
        bar.action("إدارة", new Runnable() { @Override public void run() { showManage(); } });
        bar.action("قوالب", new Runnable() { @Override public void run() { showTemplates(); } });
        bar.action("بحث", new Runnable() { @Override public void run() { searchHistory(); } });
        bannerText = CgUi.text(this, "", 13, 0xFF1B1B1B, true);
        bannerText.setGravity(Gravity.CENTER);
        bannerText.setBackgroundColor(CgCfg.WARN);
        bannerText.setPadding(CgUi.dp(this, 10), CgUi.dp(this, 6), CgUi.dp(this, 10), CgUi.dp(this, 6));
        bannerText.setVisibility(View.GONE);
        body.addView(bannerText, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(0));
        list.setVerticalScrollBarEnabled(false);
        adapter = new ChatAdapter();
        list.setAdapter(adapter);
        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int s) { }
            @Override public void onScroll(android.widget.AbsListView v, int first, int visible, int total) {
                atBottom = total == 0 || first + visible >= total;
                if (atBottom) markRead();
                if (first == 0 && total > 0 && hasMore && !loadingOlder && loaded) loadOlder();
            }
        });
        body.addView(list, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));

        replyBar = CgUi.hbox(this);
        replyBar.setBackgroundColor(CgCfg.CARD2);
        ((LinearLayout) replyBar).setPadding(CgUi.dp(this, 12), CgUi.dp(this, 4), CgUi.dp(this, 4), CgUi.dp(this, 4));
        replyText = CgUi.text(this, "", 12.5f, CgCfg.MUTED, false);
        replyText.setMaxLines(1);
        ((LinearLayout) replyBar).addView(replyText, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        TextView x = CgUi.smallButton(this, "✕", 3);
        x.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setReply(0, null); } });
        ((LinearLayout) replyBar).addView(x);
        replyBar.setVisibility(View.GONE);
        body.addView(replyBar, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        LinearLayout comp = CgUi.hbox(this);
        comp.setBackgroundColor(CgCfg.CARD);
        comp.setPadding(CgUi.dp(this, 6), CgUi.dp(this, 6), CgUi.dp(this, 6), CgUi.dp(this, 8));
        comp.setGravity(Gravity.BOTTOM);
        TextView attach = CgUi.button(this, "📎", 1);
        attach.setTextSize(18);
        attach.setContentDescription("إرفاق");
        attach.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showAttach(); } });
        comp.addView(attach, new LinearLayout.LayoutParams(CgUi.dp(this, 48), CgUi.dp(this, 48)));
        TextView quoteBtn = CgUi.button(this, "💰", 1);
        quoteBtn.setTextSize(18);
        quoteBtn.setContentDescription("إرسال عرض سعر");
        quoteBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showQuoteDialog(); } });
        comp.addView(quoteBtn, CgUi.lp(this, CgUi.dp(this, 48) / 1, CgUi.dp(this, 48), 4, 0, 0, 0));
        FrameLayout box = new FrameLayout(this);
        input = CgUi.field(this, "اكتب رد الإدارة...", true);
        input.setMinLines(1);
        input.setMaxLines(5);
        box.addView(input, new FrameLayout.LayoutParams(-1, -2));
        recBar = CgUi.hbox(this);
        recBar.setBackground(CgUi.bg(this, CgCfg.CARD2, 12, CgCfg.STROKE));
        ((LinearLayout) recBar).setPadding(CgUi.dp(this, 14), 0, CgUi.dp(this, 14), 0);
        ((LinearLayout) recBar).addView(CgUi.text(this, "● ", 16, CgCfg.BAD, true));
        timerText = CgUi.text(this, "0:00", 15, CgCfg.TEXT, true);
        ((LinearLayout) recBar).addView(timerText);
        ((LinearLayout) recBar).addView(CgUi.text(this, "   اسحب للإلغاء", 12.5f, CgCfg.MUTED, false));
        recBar.setVisibility(View.GONE);
        box.addView(recBar, new FrameLayout.LayoutParams(-1, CgUi.dp(this, 46)));
        comp.addView(box, CgUi.lp(this, 0, CgUi.WRAP, 4, 0, 4, 0));
        ((LinearLayout.LayoutParams) box.getLayoutParams()).weight = 1f;
        FrameLayout act = new FrameLayout(this);
        act.setBackground(CgUi.bg(this, CgCfg.ACCENT, 24, 0));
        sendIcon = CgUi.text(this, "➤", 20, 0xFFFFFFFF, true);
        micIcon = CgUi.text(this, "🎤", 20, 0xFFFFFFFF, true);
        ((TextView) sendIcon).setGravity(Gravity.CENTER);
        ((TextView) micIcon).setGravity(Gravity.CENTER);
        act.addView(sendIcon, new FrameLayout.LayoutParams(-1, -1));
        act.addView(micIcon, new FrameLayout.LayoutParams(-1, -1));
        act.setContentDescription("إرسال أو تسجيل صوتي");
        sendIcon.setVisibility(View.GONE);
        comp.addView(act, new LinearLayout.LayoutParams(CgUi.dp(this, 48), CgUi.dp(this, 48)));
        act.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return onActionTouch(e); }
        });
        body.addView(comp, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                boolean has = s.toString().trim().length() > 0;
                sendIcon.setVisibility(has ? View.VISIBLE : View.GONE);
                micIcon.setVisibility(has ? View.GONE : View.VISIBLE);
                if (has) typing();
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        if (thread.length() == 0) { CgUi.toast(this, "محادثة غير صالحة."); finish(); return; }
        loadInitial();
        AdApi.get(this, "/ads/config", new AdApi.Cb() {
            @Override public void on(AdApi.Result r) { if (r.ok) settings = r.json.optJSONObject("settings"); }
        });
    }

    private static volatile String OPEN_THREAD = "";

    /** 19.0: true while this very conversation is on screen (its push notification is then pointless). */
    static boolean isOpen(String threadId) { return threadId != null && threadId.length() > 0 && threadId.equals(OPEN_THREAD); }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        OPEN_THREAD = thread;
        if (ws == null) {
            ws = new AdWs(this, "/admin/chat/ws", new AdWs.Listener() {
                @Override public void onOpen() { setBanner(null); fetchNew(); }
                @Override public void onMessage(JSONObject m) { onEvent(m); }
                @Override public void onClosed(boolean authFailed) { if (authFailed) setBanner("رمز الإدارة غير مقبول في Worker الإعلانات."); }
            });
        }
        ws.start();
        ui().removeCallbacks(pollRun);
        ui().postDelayed(pollRun, 6000);
    }

    @Override protected void onStop() {
        active = false;
        if (thread.equals(OPEN_THREAD)) OPEN_THREAD = "";
        if (ws != null) ws.stop();
        ui().removeCallbacks(pollRun);
        stopRec(true);
        AdVoice.stop();
        super.onStop();
    }

    private void setBanner(String s) {
        if (!alive()) return;
        if (s == null) bannerText.setVisibility(View.GONE); else { bannerText.setText(s); bannerText.setVisibility(View.VISIBLE); }
    }

    private void updateSub() {
        long seen = threadInfo.optLong("user_seen");
        String s = userTyping ? "يكتب الآن..." : userOnline ? "● متصل الآن" : seen > 0 ? "آخر ظهور " + new SimpleDateFormat("d/M HH:mm", Locale.US).format(new Date(seen)) : "";
        String st = AdApi.threadStatusLabel(threadInfo.optString("status"));
        if (threadInfo.optBoolean("banned")) st += " • محظور";
        bar.subtitle((s.length() > 0 ? s + " • " : "") + st);
    }

    // ------------------------------------------------------------------ تحميل

    private void loadInitial() {
        AdApi.get(this, "/admin/chat/messages?thread=" + AdApi.enc(thread) + "&limit=40", new AdApi.Cb() {
            @Override public void on(AdApi.Result r) {
                if (!alive()) return;
                loaded = true;
                if (!r.ok) { setBanner(r.offline ? "لا يوجد اتصال." : r.message); return; }
                JSONObject t = r.json.optJSONObject("thread");
                if (t != null) { threadInfo = t; userOnline = t.optBoolean("online"); if (t.optString("name").length() > 0) { name = t.optString("name"); bar.titleView.setText(name); } updateSub(); }
                JSONArray a = r.json.optJSONArray("messages");
                apply(a, true);
                hasMore = a != null && a.length() >= 40;
                scrollEnd();
                markRead();
            }
        });
    }

    private void fetchNew() {
        if (!loaded) return;
        AdApi.get(this, "/admin/chat/messages?thread=" + AdApi.enc(thread) + "&after=" + lastSeq + "&limit=50", new AdApi.Cb() {
            @Override public void on(AdApi.Result r) {
                if (!alive() || !r.ok) return;
                JSONObject t = r.json.optJSONObject("thread");
                if (t != null) { threadInfo = t; updateSub(); }
                apply(r.json.optJSONArray("messages"), false);
            }
        });
    }

    private void loadOlder() {
        loadOlder(null);
    }

    /** 18.2: with a callback (history search loads older pages until it finds the text). */
    private void loadOlder(final Runnable after) {
        long first = 0;
        for (Msg m : items) if (m.seq > 0) { first = m.seq; break; }
        if (first <= 1) { hasMore = false; if (after != null) after.run(); return; }
        loadingOlder = true;
        AdApi.get(this, "/admin/chat/messages?thread=" + AdApi.enc(thread) + "&before=" + first + "&limit=40", new AdApi.Cb() {
            @Override public void on(AdApi.Result r) {
                loadingOlder = false;
                if (!alive()) return;
                if (!r.ok) { if (after != null) after.run(); return; }
                JSONArray a = r.json.optJSONArray("messages");
                if (a == null || a.length() == 0) { hasMore = false; if (after != null) after.run(); return; }
                int before = list.getFirstVisiblePosition();
                View v0 = list.getChildAt(0);
                int top = v0 == null ? 0 : v0.getTop();
                int added = apply(a, false);
                list.setSelectionFromTop(before + added, top);
                if (a.length() < 40) hasMore = false;
                if (after != null) after.run();
            }
        });
    }

    // ------------------------------------------------------------------ 18.2 history search + identity

    private String searchQuery = "";
    private int searchFrom = Integer.MAX_VALUE;

    private void searchHistory() {
        CgUi.input(this, "بحث في المحادثة", "كلمة أو جملة", searchQuery, false, new CgUi.Callback<String>() {
            @Override public void run(String v) {
                String q = v.trim();
                if (q.length() == 0) return;
                if (!q.equals(searchQuery)) searchFrom = Integer.MAX_VALUE;
                searchQuery = q;
                doSearch(0);
            }
        });
    }

    private void doSearch(final int pages) {
        String q = searchQuery.toLowerCase(Locale.ROOT);
        for (int i = Math.min(items.size(), searchFrom) - 1; i >= 0; i--) {
            Msg m = items.get(i);
            if (m.text != null && m.text.toLowerCase(Locale.ROOT).contains(q)) {
                searchFrom = i;
                list.setSelection(i);
                CgUi.toast(this, "وُجدت — اضغط «بحث» مرة أخرى للنتيجة الأقدم.");
                return;
            }
        }
        if (!hasMore || pages >= 10) {
            CgUi.toast(this, "لا توجد نتائج أخرى" + (hasMore ? " في آخر " + items.size() + " رسالة." : "."));
            searchFrom = Integer.MAX_VALUE;
            return;
        }
        final int sizeBefore = items.size();
        loadOlder(new Runnable() {
            @Override public void run() {
                if (!alive()) return;
                int added = items.size() - sizeBefore;
                searchFrom = added > 0 ? added : 0;   // search only the newly loaded (older) part next
                if (added <= 0) { CgUi.toast(AdChatActivity.this, "لا توجد نتائج."); searchFrom = Integer.MAX_VALUE; return; }
                doSearch(pages + 1);
            }
        });
    }

    /** Internal ids — shown only here, in the authenticated admin app. */
    private void showIdentity() {
        final String uid = threadInfo.optString("user_id", "");
        StringBuilder b = new StringBuilder();
        b.append("المعرّف الداخلي للمحادثة:\n").append(thread).append("\n\n");
        if (uid.length() > 0) b.append("معرّف الحساب (auth):\n").append(uid).append("\n\n");
        if (threadInfo.optString("email").length() > 0) b.append("البريد: ").append(threadInfo.optString("email")).append("\n");
        long created = threadInfo.optLong("created");
        if (created > 0) b.append("بدأت المحادثة: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(created))).append("\n");
        b.append("\nلا تظهر هذه المعرّفات للمستخدم ولا في إشعاراته.");
        CgUi.dialog(this).setTitle("هوية المستخدم").setMessage(b.toString())
                .setPositiveButton("نسخ المعرّف", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("id", uid.length() > 0 ? uid : thread));
                            CgUi.toast(AdChatActivity.this, "نُسخ.");
                        } catch (Throwable ignored) { }
                    }
                }).setNegativeButton("إغلاق", null).show();
    }

    private static Msg parse(JSONObject o) {
        Msg m = new Msg();
        m.seq = o.optLong("seq"); m.id = o.optString("id"); m.from = o.optString("from"); m.type = o.optString("type", "text");
        m.text = o.optString("text"); m.ts = o.optLong("ts"); m.delivered = o.optLong("delivered"); m.read = o.optLong("read");
        m.deleted = o.optBoolean("deleted"); m.media = o.optJSONObject("media"); m.quote = o.optJSONObject("quote");
        m.order = o.optJSONObject("order"); m.reply = o.optJSONObject("reply");
        return m;
    }

    private int apply(JSONArray arr, boolean replace) {
        if (arr == null) return 0;
        if (replace) {
            ArrayList<Msg> keep = new ArrayList<Msg>();
            for (Msg m : items) if (m.seq == 0) keep.add(m);
            items.clear();
            items.addAll(keep);
        }
        int addedBefore = 0;
        boolean incoming = false;
        long firstShown = 0;
        for (Msg m : items) if (m.seq > 0) { firstShown = m.seq; break; }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Msg n = parse(o);
            String cid = o.optString("client_id");
            int idx = -1;
            for (int k = 0; k < items.size(); k++) if (n.seq > 0 && items.get(k).seq == n.seq) { idx = k; break; }
            if (idx >= 0) { items.set(idx, n); continue; }
            for (int k = 0; k < items.size(); k++) { Msg p = items.get(k); if (p.seq == 0 && p.clientId.length() > 0 && p.clientId.equals(cid)) { items.remove(k); break; } }
            int pos = 0;
            while (pos < items.size() && items.get(pos).seq > 0 && items.get(pos).seq < n.seq) pos++;
            items.add(pos, n);
            if (firstShown > 0 && n.seq < firstShown) addedBefore++;
            if ("user".equals(n.from) && n.seq > lastSeq) incoming = true;
            lastSeq = Math.max(lastSeq, n.seq);
        }
        adapter.notifyDataSetChanged();
        if (!replace && incoming && atBottom) { scrollEnd(); markRead(); }
        return addedBefore;
    }

    private void scrollEnd() {
        if (list == null) return;
        list.post(new Runnable() { @Override public void run() { if (alive() && adapter.getCount() > 0) list.setSelection(adapter.getCount() - 1); } });
    }

    private void onEvent(JSONObject e) {
        String t = e.optString("t");
        String th = e.optString("thread");
        if ("presence".equals(t) && thread.equals(th)) { userOnline = e.optBoolean("online"); updateSub(); return; }
        if (!thread.equals(th)) return;
        if ("msg".equals(t)) {
            JSONArray a = new JSONArray();
            a.put(e.optJSONObject("msg"));
            apply(a, false);
            JSONObject ti = e.optJSONObject("thread_info");
            if (ti != null) { threadInfo = ti; userTyping = false; updateSub(); }
        } else if ("read".equals(t) || "delivered".equals(t)) {
            long upto = e.optLong("upto"); boolean rd = "read".equals(t); long now = e.optLong("ts", System.currentTimeMillis());
            for (Msg m : items) if ("admin".equals(m.from) && m.seq > 0 && m.seq <= upto) { if (rd && m.read == 0) m.read = now; if (m.delivered == 0) m.delivered = now; }
            adapter.notifyDataSetChanged();
        } else if ("typing".equals(t)) {
            userTyping = e.optBoolean("on");
            updateSub();
            ui().removeCallbacks(typingOff);
            if (userTyping) ui().postDelayed(typingOff, 5000);
        } else if ("deleted".equals(t)) {
            long seq = e.optLong("seq");
            for (Msg m : items) if (m.seq == seq) { m.deleted = true; m.media = null; m.text = ""; }
            adapter.notifyDataSetChanged();
        } else if ("quote_state".equals(t)) {
            long seq = e.optLong("seq");
            for (Msg m : items) if (m.seq == seq && m.quote != null) { try { m.quote.put("state", e.optString("state")); } catch (Throwable ignored) { } }
            adapter.notifyDataSetChanged();
        } else if ("thread".equals(t)) {
            try { threadInfo.put("status", e.optString("status")); threadInfo.put("banned", e.optBoolean("banned")); } catch (Throwable ignored) { }
            updateSub();
        } else if ("thread_gone".equals(t)) {
            CgUi.toast(this, "حُذفت هذه المحادثة.");
            finish();
        }
    }

    private final Runnable typingOff = new Runnable() { @Override public void run() { userTyping = false; updateSub(); } };
    private boolean typingSent;
    private final Runnable typingStop = new Runnable() {
        @Override public void run() { typingSent = false; if (ws != null) ws.sendText("{\"t\":\"typing\",\"on\":false,\"thread\":\"" + thread + "\"}"); }
    };

    private void typing() {
        if (ws == null || !ws.isOpen()) return;
        if (!typingSent) { typingSent = true; ws.sendText("{\"t\":\"typing\",\"on\":true,\"thread\":\"" + thread + "\"}"); }
        ui().removeCallbacks(typingStop);
        ui().postDelayed(typingStop, 2500);
    }

    private void markRead() {
        if (!active || lastSeq <= 0) return;
        boolean any = false;
        for (Msg m : items) if ("user".equals(m.from) && m.seq > 0 && m.read == 0) { any = true; m.read = System.currentTimeMillis(); }
        if (!any) return;
        try { AdApi.post(this, "/admin/chat/read", new JSONObject().put("thread", thread).put("upto", lastSeq), null); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ إرسال

    private Msg pending(String type, String text) {
        Msg m = new Msg();
        m.clientId = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        m.type = type; m.text = text; m.ts = System.currentTimeMillis(); m.state = 1; m.replyTo = replyToSeq;
        if (replyToSeq > 0 && replyPreview != null) { try { m.reply = new JSONObject().put("preview", replyPreview).put("from", "admin"); } catch (Throwable ignored) { } }
        items.add(m);
        setReply(0, null);
        adapter.notifyDataSetChanged();
        scrollEnd();
        return m;
    }

    private void dispatch(final Msg m) {
        m.state = 1; m.failMsg = "";
        adapter.notifyDataSetChanged();
        try {
            JSONObject b = new JSONObject();
            b.put("thread", thread); b.put("client_id", m.clientId); b.put("type", m.type);
            if (m.text != null && m.text.length() > 0) b.put("text", m.text);
            if (m.replyTo > 0) b.put("reply_to", m.replyTo);
            if (m.quote != null) b.put("quote", m.quote);
            if (m.media != null && m.media.optString("id").length() > 0) b.put("media_id", m.media.optString("id"));
            AdApi.post(this, "/admin/chat/send", b, new AdApi.Cb() {
                @Override public void on(AdApi.Result r) {
                    if (!alive()) return;
                    if (r.ok) {
                        JSONObject mm = r.json.optJSONObject("message");
                        JSONArray a = new JSONArray();
                        if (mm != null) { try { mm.put("client_id", m.clientId); } catch (Throwable ignored) { } a.put(mm); }
                        if (m.localPath != null) new File(m.localPath).delete();
                        apply(a, false);
                        scrollEnd();
                    } else {
                        m.state = 2; m.failMsg = r.message;
                        adapter.notifyDataSetChanged();
                    }
                }
            });
        } catch (Throwable t) { m.state = 2; adapter.notifyDataSetChanged(); }
    }

    private void doSend() {
        String s = input.getText().toString().trim();
        if (s.length() == 0) return;
        input.setText("");
        ui().removeCallbacks(typingStop);
        typingStop.run();
        dispatch(pending("text", s));
    }

    private void setReply(long seq, String preview) {
        replyToSeq = seq; replyPreview = preview;
        if (seq > 0) { replyText.setText("رد على: " + preview); replyBar.setVisibility(View.VISIBLE); } else replyBar.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ مرفقات وصوت

    private void showAttach() {
        CgUi.choose(this, "إرفاق", new CharSequence[]{"صورة من المعرض", "فيديو من المعرض", "ملف (PDF / مستند)"}, new CgUi.IntCb() {
            @Override public void run(int i) {
                try {
                    Intent in = new Intent(Intent.ACTION_GET_CONTENT);
                    in.addCategory(Intent.CATEGORY_OPENABLE);
                    in.setType(i == 0 ? "image/*" : i == 1 ? "video/*" : "*/*");
                    if (i == 2) in.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "application/zip", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"});
                    startActivityForResult(Intent.createChooser(in, "اختر"), REQ_PICK + i * 100);
                } catch (Throwable t) { CgUi.toast(AdChatActivity.this, "لا يوجد تطبيق لاختيار الملف."); }
            }
        });
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (!alive() || res != RESULT_OK || data == null || req < REQ_PICK) return;
        Uri uri = data.getData();
        if (uri == null) return;
        int which = (req - REQ_PICK) / 100;
        final String kind = which == 0 ? "image" : which == 1 ? "video" : "file";
        CgUi.toast(this, "جارٍ تجهيز الملف...");
        AdMedia.prepare(this, uri, kind, new AdMedia.Prepare() {
            @Override public void onReady(AdMedia.Prepared p, String error) {
                if (!alive()) return;
                if (p == null) { CgUi.toast(AdChatActivity.this, error == null ? "تعذر تجهيز الملف." : error); return; }
                startUpload(p, kind, 0, null);
            }
        });
    }

    private void startUpload(AdMedia.Prepared p, String kind, int dur, int[] wave) {
        final Msg m = pending(kind, "");
        m.localPath = p.file.getAbsolutePath();
        m.cancel = new AtomicBoolean(false);
        m.progress = 0;
        try {
            JSONObject md = new JSONObject();
            md.put("name", p.name); md.put("size", p.size); md.put("w", p.w); md.put("h", p.h); md.put("kind", kind); md.put("dur", dur > 0 ? dur : p.durMs);
            if (wave != null) { JSONArray w = new JSONArray(); for (int v : wave) w.put(v); md.put("wave", w); }
            m.media = md;
        } catch (Throwable ignored) { }
        adapter.notifyDataSetChanged();
        AdMedia.upload(this, p, dur, wave, "/admin/chat/upload", "thread=" + AdApi.enc(thread), CgCfg.TOKEN, new AdMedia.Progress() {
            @Override public void onProgress(int pct) { if (alive()) { m.progress = pct; adapter.notifyDataSetChanged(); } }
        }, new AdMedia.Done() {
            @Override public void onDone(boolean ok, String mediaId, String message) {
                if (!alive()) return;
                if (m.cancel.get()) { items.remove(m); adapter.notifyDataSetChanged(); return; }
                m.progress = -1;
                if (ok) { try { m.media.put("id", mediaId); } catch (Throwable ignored) { } dispatch(m); }
                else { m.state = 2; m.failMsg = message; adapter.notifyDataSetChanged(); CgUi.toast(AdChatActivity.this, message); }
            }
        }, m.cancel);
    }

    private boolean onActionTouch(MotionEvent e) {
        int a = e.getActionMasked();
        if (input.getText().toString().trim().length() > 0) { if (a == MotionEvent.ACTION_UP) doSend(); return true; }
        if (a == MotionEvent.ACTION_DOWN) {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, REQ_PERM_AUDIO);
                return true;
            }
            recDownX = e.getRawX(); recCancelled = false;
            recorder = new AdVoice.Recorder();
            if (!recorder.start(this, new AdVoice.Recorder.Tick() { @Override public void onTick(int ms, int amp) { timerText.setText(AdMedia.durLabel(ms)); } })) {
                recorder = null; CgUi.toast(this, "تعذر بدء التسجيل."); return true;
            }
            input.setVisibility(View.INVISIBLE); recBar.setVisibility(View.VISIBLE);
            return true;
        }
        if (recorder == null) return true;
        if (a == MotionEvent.ACTION_MOVE) {
            recCancelled = Math.abs(e.getRawX() - recDownX) > CgUi.dp(this, 90);
            timerText.setTextColor(recCancelled ? CgCfg.BAD : CgCfg.TEXT);
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            boolean cancel = recCancelled || a == MotionEvent.ACTION_CANCEL;
            int dur = recorder.elapsed();
            AdVoice.Recorder.Result r = recorder.stop(cancel || dur < 1000);
            recorder = null; recBar.setVisibility(View.GONE); input.setVisibility(View.VISIBLE); timerText.setTextColor(CgCfg.TEXT);
            if (r != null) {
                AdMedia.Prepared p = new AdMedia.Prepared();
                p.file = r.file; p.kind = "voice"; p.name = "voice.m4a"; p.size = r.file.length();
                startUpload(p, "voice", r.durMs, r.wave);
            } else if (!cancel && dur < 1000) CgUi.toast(this, "اضغط مطولاً للتسجيل");
        }
        return true;
    }

    private void stopRec(boolean cancel) {
        if (recorder != null) { recorder.stop(cancel); recorder = null; recBar.setVisibility(View.GONE); input.setVisibility(View.VISIBLE); }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_PERM_AUDIO) CgUi.toast(this, results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED ? "اضغط مطولاً على الميكروفون للتسجيل." : "يلزم إذن الميكروفون.");
    }

    // ------------------------------------------------------------------ عرض السعر والقوالب

    private void showQuoteDialog() {
        final String[] slots = {"splash", "hero", "inline", "popup", "sticky", "sponsor"};
        final String[] labels = new String[slots.length];
        for (int i = 0; i < slots.length; i++) labels[i] = AdApi.slotLabel(settings, slots[i]);
        final LinearLayout box = CgUi.vbox(this);
        box.setPadding(CgUi.dp(this, 16), CgUi.dp(this, 8), CgUi.dp(this, 16), 0);
        final int[] sel = {1};
        final TextView slotBtn = CgUi.button(this, labels[1], 1);
        slotBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CgUi.choose(AdChatActivity.this, "المساحة", labels, new CgUi.IntCb() { @Override public void run(int i) { sel[0] = i; slotBtn.setText(labels[i]); } });
            }
        });
        box.addView(CgUi.labeled(this, "المساحة", slotBtn));
        final EditText days = CgUi.numberField(this, "7"); days.setText("7");
        box.addView(CgUi.labeled(this, "المدة (أيام)", days));
        final EditText total = CgUi.numberField(this, "المبلغ الإجمالي");
        box.addView(CgUi.labeled(this, "المبلغ", total));
        final EditText hrs = CgUi.numberField(this, "72"); hrs.setText("72");
        box.addView(CgUi.labeled(this, "صلاحية العرض (ساعات، 0 = بلا انتهاء)", hrs));
        final EditText note = CgUi.field(this, "ملاحظة تظهر للمعلن (اختياري)", true);
        box.addView(CgUi.labeled(this, "ملاحظة", note));
        CgUi.dialog(this).setTitle("عرض سعر").setView(box).setNegativeButton("إلغاء", null)
                .setPositiveButton("إرسال", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            double t = Double.parseDouble(total.getText().toString().trim());
                            int dd = Math.max(1, Integer.parseInt(days.getText().toString().trim()));
                            long h = Long.parseLong(hrs.getText().toString().trim().length() == 0 ? "0" : hrs.getText().toString().trim());
                            if (t <= 0) { CgUi.toast(AdChatActivity.this, "أدخل مبلغاً صحيحاً."); return; }
                            Msg m = pending("quote", "");
                            m.quote = new JSONObject().put("slot", slots[sel[0]]).put("slot_label", labels[sel[0]]).put("days", dd).put("total", t)
                                    .put("currency", settings != null && settings.optJSONObject("currency") != null ? settings.optJSONObject("currency").optString("code", "USD") : "USD")
                                    .put("note", note.getText().toString().trim());
                            if (h > 0) m.quote.put("expires_at", System.currentTimeMillis() + h * 3600000L);
                            dispatch(m);
                        } catch (Throwable ex) { CgUi.toast(AdChatActivity.this, "تحقق من القيم المدخلة."); }
                    }
                }).show();
    }

    private void showTemplates() {
        AdApi.get(this, "/admin/chat/templates", new AdApi.Cb() {
            @Override public void on(AdApi.Result r) {
                if (!alive()) return;
                final JSONArray arr = r.json.optJSONArray("templates") == null ? new JSONArray() : r.json.optJSONArray("templates");
                final CharSequence[] labels = new CharSequence[arr.length() + 1];
                for (int i = 0; i < arr.length(); i++) labels[i] = arr.optJSONObject(i).optString("title");
                labels[arr.length()] = "＋ إدارة القوالب";
                CgUi.choose(AdChatActivity.this, "الردود السريعة", labels, new CgUi.IntCb() {
                    @Override public void run(int i) {
                        if (i < arr.length()) { input.setText(arr.optJSONObject(i).optString("body")); input.setSelection(input.getText().length()); }
                        else editTemplates(arr);
                    }
                });
            }
        });
    }

    private void editTemplates(final JSONArray arr) {
        final LinearLayout box = CgUi.vbox(this);
        box.setPadding(CgUi.dp(this, 16), CgUi.dp(this, 8), CgUi.dp(this, 16), 0);
        final ArrayList<EditText[]> rows = new ArrayList<EditText[]>();
        for (int i = 0; i <= arr.length() && i < 12; i++) {
            JSONObject o = i < arr.length() ? arr.optJSONObject(i) : new JSONObject();
            EditText t = CgUi.field(this, "عنوان القالب", false); t.setText(o.optString("title"));
            EditText b = CgUi.field(this, "نص الرد", true); b.setText(o.optString("body"));
            box.addView(t, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 2));
            box.addView(b, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
            rows.add(new EditText[]{t, b});
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        CgUi.dialog(this).setTitle("القوالب (اترك العنوان فارغاً للحذف)").setView(sv).setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            JSONArray out = new JSONArray();
                            for (EditText[] r : rows) if (r[0].getText().toString().trim().length() > 0 && r[1].getText().toString().trim().length() > 0)
                                out.put(new JSONObject().put("title", r[0].getText().toString().trim()).put("body", r[1].getText().toString().trim()));
                            AdApi.post(AdChatActivity.this, "/admin/chat/templates", new JSONObject().put("templates", out), new AdApi.Cb() {
                                @Override public void on(AdApi.Result r) { CgUi.toast(AdChatActivity.this, r.ok ? "تم حفظ القوالب." : r.message); }
                            });
                        } catch (Throwable ignored) { }
                    }
                }).show();
    }

    // ------------------------------------------------------------------ إدارة المحادثة

    private void patchThread(JSONObject patch, final String doneMsg) {
        try {
            patch.put("thread", thread);
            AdApi.post(this, "/admin/chat/thread", patch, new AdApi.Cb() {
                @Override public void on(AdApi.Result r) {
                    if (!alive()) return;
                    if (r.ok) { JSONObject t = r.json.optJSONObject("thread"); if (t != null) threadInfo = t; updateSub(); CgUi.toast(AdChatActivity.this, doneMsg); }
                    else CgUi.toast(AdChatActivity.this, r.message);
                }
            });
        } catch (Throwable ignored) { }
    }

    private void showManage() {
        final boolean banned = threadInfo.optBoolean("banned");
        final CharSequence[] labels = {"تغيير الحالة", "الوسوم", "ملاحظات داخلية (لا يراها المستخدم)", "تعيين مسؤول", "تحويل طلب إعلان إلى حملة (مسودة)",
                "فتح ملف المعلن", banned ? "رفع التقييد" : "تقييد الحساب في المحادثة (إساءة)", threadInfo.optBoolean("archived") ? "إلغاء الأرشفة" : "أرشفة المحادثة", "تصدير المحادثة (نص)", "سياسة حفظ المحادثات",
                "المعرّف الداخلي للمستخدم"};
        CgUi.choose(this, "إدارة المحادثة", labels, new CgUi.IntCb() {
            @Override public void run(int i) {
                try {
                    if (i == 10) { showIdentity(); return; }
                    if (i == 0) {
                        final String[] st = {"open", "pending", "quoted", "won", "closed"};
                        CharSequence[] ls = new CharSequence[st.length];
                        for (int k = 0; k < st.length; k++) ls[k] = AdApi.threadStatusLabel(st[k]);
                        CgUi.choose(AdChatActivity.this, "الحالة", ls, new CgUi.IntCb() { @Override public void run(int k) { try { patchThread(new JSONObject().put("status", st[k]), "تم تغيير الحالة."); } catch (Throwable ignored) { } } });
                    } else if (i == 1) {
                        JSONArray tg = threadInfo.optJSONArray("tags"); StringBuilder sb = new StringBuilder();
                        if (tg != null) for (int k = 0; k < tg.length(); k++) sb.append(k > 0 ? "، " : "").append(tg.optString(k));
                        CgUi.input(AdChatActivity.this, "الوسوم (افصل بفاصلة)", "مهم، vip، متابعة", sb.toString(), false, new CgUi.Callback<String>() {
                            @Override public void run(String v) {
                                try {
                                    JSONArray out = new JSONArray();
                                    for (String s : v.split("[,،]")) if (s.trim().length() > 0) out.put(s.trim());
                                    patchThread(new JSONObject().put("tags", out), "تم حفظ الوسوم.");
                                } catch (Throwable ignored) { }
                            }
                        });
                    } else if (i == 2) {
                        CgUi.input(AdChatActivity.this, "ملاحظات داخلية", "ملاحظات للإدارة فقط", threadInfo.optString("notes"), true, new CgUi.Callback<String>() {
                            @Override public void run(String v) { try { patchThread(new JSONObject().put("notes", v), "تم حفظ الملاحظات."); } catch (Throwable ignored) { } }
                        });
                    } else if (i == 3) {
                        CgUi.input(AdChatActivity.this, "تعيين مسؤول", "اسم الموظف", threadInfo.optString("assigned"), false, new CgUi.Callback<String>() {
                            @Override public void run(String v) { try { patchThread(new JSONObject().put("assigned", v), "تم التعيين."); } catch (Throwable ignored) { } }
                        });
                    } else if (i == 4) {
                        CgUi.confirm(AdChatActivity.this, "تحويل الطلب", "ننشئ مسودة حملة من آخر طلب أرسله المعلن (لا تُنشر قبل موافقتك).", "تحويل", false, new Runnable() {
                            @Override public void run() {
                                try {
                                    AdApi.post(AdChatActivity.this, "/admin/chat/convert-order", new JSONObject().put("thread", thread), new AdApi.Cb() {
                                        @Override public void on(AdApi.Result r) {
                                            if (!alive()) return;
                                            if (!r.ok) { CgUi.toast(AdChatActivity.this, r.message); return; }
                                            Intent in = new Intent(AdChatActivity.this, AdCampaignActivity.class);
                                            in.putExtra("id", r.json.optJSONObject("campaign").optString("id"));
                                            startActivity(in);
                                        }
                                    });
                                } catch (Throwable ignored) { }
                            }
                        });
                    } else if (i == 5) {
                        String adv = threadInfo.optString("adv_id");
                        if (adv.length() == 0) { CgUi.toast(AdChatActivity.this, "لم يُنشأ ملف معلن لهذا المستخدم بعد (يُنشأ مع أول رسالة)."); return; }
                        Intent in = new Intent(AdChatActivity.this, AdAdvertiserActivity.class);
                        in.putExtra("id", adv);
                        startActivity(in);
                    } else if (i == 6) {
                        patchThread(new JSONObject().put("banned", !banned), banned ? "رُفع الحظر." : "تم الحظر.");
                    } else if (i == 7) {
                        patchThread(new JSONObject().put("archived", !threadInfo.optBoolean("archived")), "تم.");
                    } else if (i == 8) {
                        exportChat();
                    } else if (i == 9) {
                        // 19.0: conversations are permanent. There is no delete: the server refuses purge / delete-message (409)
                        CgUi.dialog(AdChatActivity.this).setTitle("المحادثات دائمة")
                                .setMessage("لا يمكن حذف أي محادثة أو رسالة من التطبيق (وكذلك يرفض الخادم ذلك).\n\n"
                                        + "• الأرشفة تُخفي المحادثة من القائمة الرئيسية فقط؛ تبقى كاملة ويمكن البحث فيها واستعادتها.\n"
                                        + "• إذا حذف المستخدم حسابه أو طلب النسيان تُمسح هويته (الاسم والبريد) ويبقى السجل.\n"
                                        + "• يحتفظ الخادم بنسخ احتياطية غير قابلة للتعديل للمحادثات.")
                                .setPositiveButton("حسناً", null).show();
                    }
                } catch (Throwable ignored) { }
            }
        });
    }

    private void exportChat() {
        AdApi.get(this, "/admin/chat/export?thread=" + AdApi.enc(thread), new AdApi.Cb() {
            @Override public void on(AdApi.Result r) {
                if (!alive()) return;
                if (!r.ok) { CgUi.toast(AdChatActivity.this, r.message); return; }
                StringBuilder sb = new StringBuilder("محادثة مع: " + name + "\n\n");
                JSONArray a = r.json.optJSONArray("messages");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject m = a.optJSONObject(i);
                    String who = "user".equals(m.optString("from")) ? name : "الإدارة";
                    String t = m.optString("text");
                    if (m.optJSONObject("media") != null) t = "[" + m.optString("type") + ": " + m.optJSONObject("media").optString("name") + "] " + t;
                    sb.append(AdApi.date(m.optLong("ts"))).append(" — ").append(who).append(": ").append(m.optBoolean("deleted") ? "(محذوفة)" : t).append("\n");
                }
                try {
                    Intent in = new Intent(Intent.ACTION_SEND);
                    in.setType("text/plain");
                    in.putExtra(Intent.EXTRA_TEXT, sb.toString());
                    startActivity(Intent.createChooser(in, "تصدير المحادثة"));
                } catch (Throwable ignored) { }
            }
        });
    }

    // ------------------------------------------------------------------ القائمة

    /** 18.2: 24-hour clock ("19:45"), device time zone; the server stores UTC epoch ms. */
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);

    private static boolean sameDay(long a, long b) {
        Calendar x = Calendar.getInstance(), y = Calendar.getInstance();
        x.setTimeInMillis(a); y.setTimeInMillis(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }

    private final class ChatAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout root;
            if (convert instanceof LinearLayout) root = (LinearLayout) convert;
            else { root = CgUi.vbox(AdChatActivity.this); root.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, -2)); }
            root.removeAllViews();
            root.setPadding(CgUi.dp(AdChatActivity.this, 10), CgUi.dp(AdChatActivity.this, 3), CgUi.dp(AdChatActivity.this, 10), CgUi.dp(AdChatActivity.this, 3));
            Msg m = items.get(pos);
            if (pos == 0 || !sameDay(items.get(pos - 1).ts, m.ts)) {
                TextView d = CgUi.text(AdChatActivity.this, new SimpleDateFormat("d MMMM yyyy", new Locale("ar")).format(new Date(m.ts)), 11.5f, CgCfg.MUTED, false);
                d.setBackground(CgUi.bg(AdChatActivity.this, CgCfg.CARD2, 10, 0));
                d.setPadding(CgUi.dp(AdChatActivity.this, 10), CgUi.dp(AdChatActivity.this, 3), CgUi.dp(AdChatActivity.this, 10), CgUi.dp(AdChatActivity.this, 3));
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
                l.gravity = Gravity.CENTER_HORIZONTAL;
                l.setMargins(0, CgUi.dp(AdChatActivity.this, 6), 0, CgUi.dp(AdChatActivity.this, 6));
                root.addView(d, l);
            }
            row(root, m);
            return root;
        }
    }

    private void row(LinearLayout root, final Msg m) {
        final Context c = this;
        if ("system".equals(m.type)) {
            TextView t = CgUi.text(c, m.text, 12.5f, CgCfg.MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setBackground(CgUi.bg(c, CgCfg.CARD2, 12, 0));
            t.setPadding(CgUi.dp(c, 12), CgUi.dp(c, 5), CgUi.dp(c, 12), CgUi.dp(c, 5));
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.gravity = Gravity.CENTER_HORIZONTAL;
            root.addView(t, l);
            return;
        }
        final boolean mine = "admin".equals(m.from);
        LinearLayout bubble = CgUi.vbox(c);
        bubble.setPadding(CgUi.dp(c, 10), CgUi.dp(c, 7), CgUi.dp(c, 10), CgUi.dp(c, 5));
        bubble.setBackground(CgUi.bg(c, mine ? CgCfg.ACCENT_D : CgCfg.CARD2, 14, mine ? 0 : CgCfg.STROKE));
        int tx = CgCfg.TEXT, sub = mine ? 0xCCFFFFFF : CgCfg.MUTED;
        if (m.reply != null && !m.deleted) {
            TextView r = CgUi.text(c, "↩ " + m.reply.optString("preview"), 12, sub, false);
            r.setMaxLines(2);
            r.setBackground(CgUi.bg(c, 0x33000000, 8, 0));
            r.setPadding(CgUi.dp(c, 8), CgUi.dp(c, 4), CgUi.dp(c, 8), CgUi.dp(c, 4));
            bubble.addView(r, CgUi.lp(c, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 5));
        }
        if (m.deleted) bubble.addView(CgUi.text(c, "رسالة محذوفة", 13, sub, false));
        else if (("image".equals(m.type) || "video".equals(m.type)) && m.media != null) bubble.addView(mediaView(m, "video".equals(m.type)));
        else if ("voice".equals(m.type) && m.media != null) bubble.addView(voiceView(m, mine));
        else if ("file".equals(m.type) && m.media != null) {
            TextView f = CgUi.text(c, "" + m.media.optString("name") + "  (" + AdMedia.sizeLabel(m.media.optLong("size")) + ")", 14, tx, true);
            f.setClickable(true);
            f.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openUrl(m.media.optString("url"), null); } });
            bubble.addView(f);
        } else if ("quote".equals(m.type) && m.quote != null) {
            JSONObject q = m.quote;
            String stt = q.optString("state", "pending");
            bubble.addView(CgUi.text(c, "عرض سعر — " + q.optString("slot_label") + " / " + q.optInt("days") + " يوم", 14, tx, true));
            bubble.addView(CgUi.text(c, AdApi.money(q.optDouble("total"), q.optString("currency")), 20, tx, true), CgUi.lp(c, CgUi.WRAP, CgUi.WRAP, 0, 4, 0, 2));
            if (q.optString("note").length() > 0) bubble.addView(CgUi.text(c, q.optString("note"), 13, tx, false));
            String stLabel = "accepted".equals(stt) ? "قبله المعلن" : "declined".equals(stt) ? "رفضه المعلن" : (q.optLong("expires_at") > 0 && q.optLong("expires_at") < System.currentTimeMillis()) ? "انتهت صلاحيته" : "⏳ بانتظار رد المعلن";
            bubble.addView(CgUi.text(c, stLabel, 12.5f, sub, true), CgUi.lp(c, CgUi.WRAP, CgUi.WRAP, 0, 4, 0, 0));
        } else if ("order".equals(m.type) && m.order != null) {
            JSONObject o = m.order;
            StringBuilder sb = new StringBuilder("طلب إعلان\n" + o.optString("slot_label", o.optString("slot")) + " — " + o.optInt("days") + " يوم");
            JSONArray cn = o.optJSONArray("city_names");
            if (cn != null && cn.length() > 0) { sb.append("\nالمدن: "); for (int i = 0; i < cn.length(); i++) sb.append(i > 0 ? "، " : "").append(cn.optString(i)); }
            JSONObject ad = o.optJSONObject("addons");
            if (ad != null) { if (ad.optBoolean("exclusive")) sb.append("\n• حصرية"); if (ad.optBoolean("video")) sb.append("\n• فيديو"); if (ad.optBoolean("design")) sb.append("\n• تصميم"); }
            if (o.optString("note").length() > 0) sb.append("\n").append(o.optString("note"));
            if (o.optDouble("total", 0) > 0) sb.append("\nالتقدير: ").append(o.optString("total")).append(" ").append(o.optString("currency", "USD"));
            bubble.addView(CgUi.text(c, sb.toString(), 13.5f, tx, false));
        }
        boolean cardOnly = "quote".equals(m.type) || "order".equals(m.type);
        if (!m.deleted && m.text != null && m.text.length() > 0 && !cardOnly && !"voice".equals(m.type)) {
            TextView t = CgUi.text(c, m.text, 14.5f, tx, false);
            t.setAutoLinkMask(android.text.util.Linkify.WEB_URLS | android.text.util.Linkify.PHONE_NUMBERS);
            t.setLinkTextColor(0xFF7FE9F5);
            bubble.addView(t, CgUi.lp(c, CgUi.WRAP, CgUi.WRAP, 0, ("image".equals(m.type) || "video".equals(m.type)) ? 5 : 0, 0, 0));
        }
        String status = m.state == 2 ? (m.failMsg.length() > 0 ? m.failMsg : "فشل — اضغط مطولاً") : timeFmt.format(new Date(m.ts));
        if (mine && m.state == 0) status += m.read > 0 ? "  ✓✓ مقروءة" : m.delivered > 0 ? "  ✓✓" : "  ✓";
        if (mine && m.state == 1) status += "  …";
        bubble.addView(CgUi.text(c, status, 11, m.state == 2 ? 0xFFFFB4AB : sub, false), CgUi.lp(c, CgUi.WRAP, CgUi.WRAP, 0, 3, 0, 0));
        bubble.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { actions(m); return true; } });
        if (m.state == 2) bubble.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { actions(m); } });
        LinearLayout holder = new LinearLayout(c) {
            @Override protected void onMeasure(int w, int h) {
                int size = Math.min(View.MeasureSpec.getSize(w), maxBubble);
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST), h);
            }
        };
        holder.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.gravity = mine ? Gravity.RIGHT : Gravity.LEFT;
        root.addView(holder, hl);
    }

    private View mediaView(final Msg m, final boolean video) {
        int w = m.media.optInt("w"), h = m.media.optInt("h");
        int bw = Math.min(maxBubble - CgUi.dp(this, 24), CgUi.dp(this, 230));
        int bh = (w > 0 && h > 0) ? (int) (bw * (float) h / w) : bw * 3 / 4;
        bh = Math.max(CgUi.dp(this, 100), Math.min(CgUi.dp(this, 300), bh));
        FrameLayout f = new FrameLayout(this);
        AdViews.Round iv = new AdViews.Round(this, CgUi.dp(this, 10));
        iv.setBackgroundColor(0x33000000);
        f.addView(iv, new FrameLayout.LayoutParams(bw, bh));
        if (m.localPath != null && !video) {
            try { BitmapFactory.Options o = new BitmapFactory.Options(); o.inSampleSize = 4; Bitmap b = BitmapFactory.decodeFile(m.localPath, o); if (b != null) iv.setImageBitmap(b); } catch (Throwable ignored) { }
        } else if (m.media.optString("url").length() > 0) AdMedia.loadInto(this, iv, m.media.optString("url"), 640, video);
        if (video) {
            TextView p = CgUi.text(this, "▶", 34, 0xFFFFFFFF, true);
            p.setGravity(Gravity.CENTER);
            f.addView(p, new FrameLayout.LayoutParams(-1, -1));
        }
        if (m.progress >= 0 && m.state == 1) {
            TextView o = CgUi.text(this, m.progress + "%  (اضغط للإلغاء)", 14, 0xFFFFFFFF, true);
            o.setGravity(Gravity.CENTER);
            o.setBackgroundColor(0x88000000);
            o.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (m.cancel != null) m.cancel.set(true); items.remove(m); adapter.notifyDataSetChanged(); } });
            f.addView(o, new FrameLayout.LayoutParams(-1, -1));
        }
        final String url = m.media.optString("url");
        f.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (m.state == 0 && url.length() > 0) openUrl(url, video ? "video/*" : "image/*"); } });
        f.setOnLongClickListener(new View.OnLongClickListener() { @Override public boolean onLongClick(View v) { actions(m); return true; } });
        return f;
    }

    private View voiceView(final Msg m, boolean mine) {
        LinearLayout row = CgUi.hbox(this);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        final String id = m.id.length() > 0 ? m.id : m.clientId;
        boolean playing = id.equals(AdVoice.currentId());
        TextView play = CgUi.text(this, playing ? "❚❚" : "▶", 18, 0xFFFFFFFF, true);
        play.setGravity(Gravity.CENTER);
        play.setBackground(CgUi.bg(this, 0x44FFFFFF, 22, 0));
        row.addView(play, new LinearLayout.LayoutParams(CgUi.dp(this, 44), CgUi.dp(this, 44)));
        AdViews.Wave wave = new AdViews.Wave(this, 0xFFFFFFFF, 0x66FFFFFF);
        JSONArray wa = m.media.optJSONArray("wave");
        int[] bars = new int[wa == null ? 0 : wa.length()];
        for (int i = 0; i < bars.length; i++) bars[i] = wa.optInt(i, 20);
        wave.setBars(bars);
        row.addView(wave, CgUi.lp(this, CgUi.dp(this, 110), CgUi.dp(this, 30), 8, 0, 8, 0));
        row.addView(CgUi.text(this, AdMedia.durLabel(m.media.optInt("dur")), 12, 0xCCFFFFFF, false));
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String url = m.media.optString("url");
                if (url.length() == 0 && m.localPath != null) url = m.localPath;
                if (url.length() == 0) return;
                AdVoice.setListener(new AdVoice.PlayListener() {
                    @Override public void onState(String pid, boolean pl, int pos, int total) { if (alive()) adapter.notifyDataSetChanged(); }
                });
                AdVoice.toggle(id, url);
            }
        });
        return row;
    }

    private void openUrl(String url, String mime) {
        if (url == null || url.length() == 0) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            if (mime != null) i.setDataAndType(Uri.parse(url), mime); else i.setData(Uri.parse(url));
            startActivity(i);
        } catch (Throwable t) { CgUi.toast(this, "لا يوجد تطبيق لفتح الملف."); }
    }

    private static String previewOf(Msg m) {
        if ("image".equals(m.type)) return "صورة";
        if ("video".equals(m.type)) return "فيديو";
        if ("voice".equals(m.type)) return "رسالة صوتية";
        if ("file".equals(m.type)) return "ملف";
        if ("quote".equals(m.type)) return "عرض سعر";
        if ("order".equals(m.type)) return "طلب إعلان";
        String t = m.text == null ? "" : m.text.replace('\n', ' ');
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    private void actions(final Msg m) {
        final ArrayList<String> labels = new ArrayList<String>();
        final ArrayList<Runnable> acts = new ArrayList<Runnable>();
        if (m.state == 2) {
            labels.add("إعادة المحاولة");
            acts.add(new Runnable() { @Override public void run() { if (m.localPath != null && (m.media == null || m.media.optString("id").length() == 0)) { items.remove(m); adapter.notifyDataSetChanged(); CgUi.toast(AdChatActivity.this, "أعد اختيار الملف."); } else dispatch(m); } });
            labels.add("حذف");
            acts.add(new Runnable() { @Override public void run() { items.remove(m); adapter.notifyDataSetChanged(); } });
        } else if (m.seq > 0 && !m.deleted) {
            labels.add("رد");
            acts.add(new Runnable() { @Override public void run() { setReply(m.seq, previewOf(m)); input.requestFocus(); } });
            if (m.text != null && m.text.length() > 0) {
                labels.add("نسخ");
                acts.add(new Runnable() { @Override public void run() { try { ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("m", m.text)); CgUi.toast(AdChatActivity.this, "تم النسخ"); } catch (Throwable ignored) { } } });
            }
        }
        if (labels.isEmpty()) return;
        CgUi.choose(this, "خيارات", labels.toArray(new CharSequence[0]), new CgUi.IntCb() { @Override public void run(int i) { acts.get(i).run(); } });
    }
}
