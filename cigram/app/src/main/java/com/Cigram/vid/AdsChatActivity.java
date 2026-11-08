package com.Cigram.vid;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
 * شاشة محادثة المعلن مع إدارة Cigram: نص، صور، فيديو، ملفات، رسائل صوتية، عروض أسعار، طلبات إعلان،
 * حضور وكتابة لحظية (WebSocket) مع بديل استطلاع، رسائل معلّقة تُعاد تلقائياً، ونسخة محلية مشفّرة.
 * Intent extras: order_json (اختياري) — يُرسل كبطاقة طلب مرة واحدة.
 */
public class AdsChatActivity extends Activity {

    private static final int REQ_PICK = 71, REQ_CAMERA = 72, REQ_PERM_AUDIO = 73, REQ_PERM_NOTIF = 74;
    private static final String PREFS = "cigram_ads_v1";

    static final class Msg {
        long seq;
        String id = "", from = "user", type = "text", text = "", clientId = "", failMsg = "";
        long ts, delivered, read;
        boolean deleted;
        JSONObject media, quote, order, reply;
        int state; // 0 على الخادم، 1 قيد الإرسال، 2 فشل
        int progress = -1;
        String localPath;
        AtomicBoolean cancel;
        long replyTo;

        static Msg fromJson(JSONObject o) {
            Msg m = new Msg();
            m.seq = o.optLong("seq");
            m.id = o.optString("id");
            m.from = o.optString("from", "user");
            m.type = o.optString("type", "text");
            m.text = o.optString("text", "");
            m.ts = o.optLong("ts");
            m.delivered = o.optLong("delivered");
            m.read = o.optLong("read");
            m.deleted = o.optBoolean("deleted");
            m.media = o.optJSONObject("media");
            m.quote = o.optJSONObject("quote");
            m.order = o.optJSONObject("order");
            m.reply = o.optJSONObject("reply");
            return m;
        }
    }

    // الحالة
    private final ArrayList<Msg> items = new ArrayList<Msg>();
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean destroyed, active, loaded, hasMore = true, loadingOlder, atBottom = true, banned, adminOnline;
    private boolean adminTyping, typingSent;
    private int newBelow;
    private long lastSeq;
    private AdsWs ws;
    private String orderJson;
    private String cameraPath;
    private String replyPreview;
    private long replyToSeq;
    private AdsVoice.Recorder recorder;
    private boolean recCancelled;
    private float recDownX;

    // الواجهة
    private ListView list;
    private ChatAdapter adapter;
    private TextView subtitle, banner, fab, replyText, timerText, sendBtnText;
    private View replyBar, recBar, sendBtn, micBtn, attachBtn;
    private EditText input;
    private LinearLayout composer;
    private int maxBubble;

    private final Runnable pollRun = new Runnable() {
        @Override public void run() {
            if (destroyed || !active) return;
            if (ws == null || !ws.isOpen()) fetchNew();
            h.postDelayed(this, 7000);
        }
    };

    // ------------------------------------------------------------------ دورة الحياة

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            getWindow().setStatusBarColor(CigramGlass.bg(this));
            getWindow().setNavigationBarColor(CigramGlass.bg(this));
        } catch (Throwable ignored) { }
        if (!CigramAds.isLoggedIn(this)) {
            AdsUi.toast(this, "سجّل الدخول أولاً.");
            finish();
            return;
        }
        if (b != null) cameraPath = b.getString("cam");
        orderJson = getIntent() == null ? null : getIntent().getStringExtra("order_json");
        maxBubble = (int) (getResources().getDisplayMetrics().widthPixels * 0.78f);
        build();
        AdsFileProvider.cleanOld(this);
        AdsMedia.cleanOld(this);
        restoreCache();
        bootstrap();
        askNotifPermission();
        AdsNotifier.cancelChatNotification(this);
    }

    @Override protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        if (cameraPath != null) b.putString("cam", cameraPath);
    }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        if (ws == null) {
            ws = new AdsWs(this, "/chat/ws", new AdsWs.Listener() {
                @Override public void onOpen() { setBanner(null); fetchNew(); flushPending(); loadPresence(); }
                @Override public void onMessage(JSONObject m) { onEvent(m); }
                @Override public void onClosed(boolean authFailed) {
                    if (authFailed) setBanner("انتهت الجلسة. سجّل الدخول من جديد.");
                }
            });
        }
        ws.start();
        h.removeCallbacks(pollRun);
        h.postDelayed(pollRun, 7000);
        AdsNotifier.schedule(this);
    }

    @Override protected void onStop() {
        active = false;
        if (ws != null) ws.stop();
        h.removeCallbacks(pollRun);
        stopRecording(true);
        AdsVoice.stop();
        persist();
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        AdsVoice.setListener(null);
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ بناء الواجهة

    private void build() {
        FrameLayout shell = new FrameLayout(this);
        shell.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        shell.setBackgroundColor(CigramGlass.bg(this));
        LinearLayout col = AdsUi.vbox(this);
        shell.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = AdsUi.hbox(this);
        top.setPadding(AdsUi.dp(this, 8), AdsUi.dp(this, 6), AdsUi.dp(this, 12), AdsUi.dp(this, 6));
        top.setBackgroundColor(CigramGlass.card(this));
        top.addView(iconBtn(AdsIcons.BACK, "رجوع", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }), new LinearLayout.LayoutParams(AdsUi.dp(this, 48), AdsUi.dp(this, 48)));
        LinearLayout titles = AdsUi.vbox(this);
        titles.addView(AdsUi.text(this, "إدارة Cigram", 18f, CigramGlass.text(this), true));
        subtitle = AdsUi.text(this, "", 12f, CigramGlass.muted(this), false);
        subtitle.setVisibility(View.GONE);
        titles.addView(subtitle, AdsUi.lp(-2, -2, 0, AdsUi.dp(this, 2), 0, 0));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2);
        tl.weight = 1f;
        tl.setMargins(AdsUi.dp(this, 4), 0, 0, 0);
        top.addView(titles, tl);
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        banner = AdsUi.text(this, "", 13f, 0xFF1B1B1B, true);
        banner.setGravity(Gravity.CENTER);
        banner.setBackgroundColor(CigramUI.AMBER);
        banner.setPadding(AdsUi.dp(this, 12), AdsUi.dp(this, 6), AdsUi.dp(this, 12), AdsUi.dp(this, 6));
        banner.setVisibility(View.GONE);
        col.addView(banner, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout mid = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new ColorDrawable(0));
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, AdsUi.dp(this, 8), 0, AdsUi.dp(this, 8));
        adapter = new ChatAdapter();
        list.setAdapter(adapter);
        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int s) { }
            @Override public void onScroll(android.widget.AbsListView v, int first, int visible, int total) {
                boolean was = atBottom;
                atBottom = total == 0 || first + visible >= total;
                if (atBottom && !was) { newBelow = 0; updateFab(); markRead(); }
                else if (!atBottom) updateFab();
                if (first == 0 && total > 0 && hasMore && !loadingOlder && loaded) loadOlder();
            }
        });
        mid.addView(list, new FrameLayout.LayoutParams(-1, -1));
        fab = AdsUi.text(this, "↓", 18f, 0xFFFFFFFF, true);
        fab.setGravity(Gravity.CENTER);
        fab.setBackground(CigramUI.round(CigramUI.TEAL, 24, this));
        fab.setContentDescription("الانتقال لآخر الرسائل");
        fab.setMinWidth(AdsUi.dp(this, 48));
        fab.setMinHeight(AdsUi.dp(this, 48));
        fab.setVisibility(View.GONE);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { scrollToEnd(); }
        });
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        fl.setMargins(AdsUi.dp(this, 14), 0, 0, AdsUi.dp(this, 12));
        mid.addView(fab, fl);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, 0);
        ml.weight = 1f;
        col.addView(mid, ml);

        // شريط الرد
        replyBar = AdsUi.hbox(this);
        ((LinearLayout) replyBar).setPadding(AdsUi.dp(this, 14), AdsUi.dp(this, 6), AdsUi.dp(this, 6), AdsUi.dp(this, 6));
        replyBar.setBackgroundColor(CigramGlass.soft(this));
        replyText = AdsUi.text(this, "", 13f, CigramGlass.muted(this), false);
        replyText.setMaxLines(1);
        LinearLayout.LayoutParams rtl = new LinearLayout.LayoutParams(0, -2);
        rtl.weight = 1f;
        ((LinearLayout) replyBar).addView(replyText, rtl);
        ((LinearLayout) replyBar).addView(iconBtn(AdsIcons.CLOSE, "إلغاء الرد", new View.OnClickListener() {
            @Override public void onClick(View v) { setReply(0, null); }
        }), new LinearLayout.LayoutParams(AdsUi.dp(this, 44), AdsUi.dp(this, 44)));
        replyBar.setVisibility(View.GONE);
        col.addView(replyBar, new LinearLayout.LayoutParams(-1, -2));

        // الكتابة
        composer = AdsUi.hbox(this);
        composer.setPadding(AdsUi.dp(this, 6), AdsUi.dp(this, 6), AdsUi.dp(this, 6), AdsUi.dp(this, 8));
        composer.setBackgroundColor(CigramGlass.card(this));
        composer.setGravity(Gravity.BOTTOM);
        attachBtn = iconBtn(AdsIcons.CLIP, "إرفاق", new View.OnClickListener() {
            @Override public void onClick(View v) { showAttachSheet(); }
        });
        composer.addView(attachBtn, new LinearLayout.LayoutParams(AdsUi.dp(this, 48), AdsUi.dp(this, 48)));

        FrameLayout inputBox = new FrameLayout(this);
        input = new EditText(this);
        CigramUI.styleInput(input, "اكتب رسالة...");
        input.setMaxLines(5);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinHeight(AdsUi.dp(this, 48));
        inputBox.addView(input, new FrameLayout.LayoutParams(-1, -2));
        recBar = AdsUi.hbox(this);
        recBar.setBackground(CigramUI.round(CigramGlass.soft(this), 24, this));
        ((LinearLayout) recBar).setPadding(AdsUi.dp(this, 16), 0, AdsUi.dp(this, 16), 0);
        TextView dot = AdsUi.text(this, "●", 16f, CigramUI.DANGER, true);
        ((LinearLayout) recBar).addView(dot);
        timerText = AdsUi.text(this, "0:00", 16f, CigramGlass.text(this), true);
        ((LinearLayout) recBar).addView(timerText, AdsUi.lp(-2, -2, AdsUi.dp(this, 8), 0, AdsUi.dp(this, 12), 0));
        ((LinearLayout) recBar).addView(AdsUi.text(this, "اسحب للإلغاء", 13f, CigramGlass.muted(this), false));
        recBar.setVisibility(View.GONE);
        inputBox.addView(recBar, new FrameLayout.LayoutParams(-1, AdsUi.dp(this, 48)));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, -2);
        il.weight = 1f;
        il.setMargins(AdsUi.dp(this, 4), 0, AdsUi.dp(this, 4), 0);
        composer.addView(inputBox, il);

        // زر إرسال/مايك
        FrameLayout act = new FrameLayout(this);
        act.setBackground(CigramUI.round(CigramUI.TEAL, 24, this));
        sendBtn = new AdsIcons.Icon(this, AdsIcons.SEND, 0xFFFFFFFF);
        micBtn = new AdsIcons.Icon(this, AdsIcons.MIC, 0xFFFFFFFF);
        act.addView(sendBtn, new FrameLayout.LayoutParams(AdsUi.dp(this, 24), AdsUi.dp(this, 24), Gravity.CENTER));
        act.addView(micBtn, new FrameLayout.LayoutParams(AdsUi.dp(this, 24), AdsUi.dp(this, 24), Gravity.CENTER));
        act.setContentDescription("إرسال أو تسجيل رسالة صوتية");
        sendBtn.setVisibility(View.GONE);
        composer.addView(act, new LinearLayout.LayoutParams(AdsUi.dp(this, 48), AdsUi.dp(this, 48)));
        act.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return onActionTouch(e); }
        });
        col.addView(composer, new LinearLayout.LayoutParams(-1, -2));

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                boolean has = s.toString().trim().length() > 0;
                sendBtn.setVisibility(has ? View.VISIBLE : View.GONE);
                micBtn.setVisibility(has ? View.GONE : View.VISIBLE);
                if (has) notifyTyping();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        setContentView(shell);
    }

    private View iconBtn(int icon, String desc, View.OnClickListener click) {
        FrameLayout f = new FrameLayout(this);
        f.setClickable(true);
        f.setFocusable(true);
        f.setContentDescription(desc);
        f.setBackground(CigramGlass.ripple(this, new GradientDrawable(), 24));
        f.addView(new AdsIcons.Icon(this, icon, CigramGlass.text(this)), new FrameLayout.LayoutParams(AdsUi.dp(this, 24), AdsUi.dp(this, 24), Gravity.CENTER));
        f.setOnClickListener(click);
        return f;
    }

    private void setBanner(String s) {
        if (destroyed) return;
        if (s == null) banner.setVisibility(View.GONE);
        else { banner.setText(s); banner.setVisibility(View.VISIBLE); }
    }

    private void updateSubtitle() {
        if (destroyed) return;
        String s;
        int color = CigramGlass.muted(this);
        if (banned) { s = "المحادثة مقيّدة"; }
        else if (adminTyping) { s = "الإدارة تكتب..."; color = CigramUI.TEAL; }
        else if (adminOnline) { s = "● متصل الآن"; color = CigramUI.GREEN; }
        else { s = presenceText; }
        subtitle.setText(s == null ? "" : s);
        subtitle.setTextColor(color);
        subtitle.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
        boolean can = !banned;
        input.setEnabled(can);
        input.setHint(banned ? "تم تقييد المحادثة من قبل الإدارة" : "اكتب رسالة...");
        attachBtn.setEnabled(can);
    }

    private String presenceText = "";

    private void loadPresence() {
        CigramAds.get(this, "/chat/presence", false, new CigramAds.Callback() {
            @Override public void onResult(CigramAds.Result r) {
                if (destroyed || !r.ok) return;
                String st = r.json.optString("state");
                adminOnline = "online".equals(st);
                presenceText = "off_hours".equals(st) ? "خارج ساعات العمل — سنرد عند عودتنا" : "offline".equals(st) ? "غير متصل — اترك رسالتك" : "";
                updateSubtitle();
            }
        });
    }

    // ------------------------------------------------------------------ تحميل البيانات

    private void restoreCache() {
        JSONObject c = AdsChatStore.load(this);
        JSONArray ms = c.optJSONArray("messages");
        if (ms != null) for (int i = 0; i < ms.length(); i++) {
            Msg m = Msg.fromJson(ms.optJSONObject(i));
            items.add(m);
            lastSeq = Math.max(lastSeq, m.seq);
        }
        JSONArray pd = c.optJSONArray("pending");
        if (pd != null) for (int i = 0; i < pd.length(); i++) {
            JSONObject o = pd.optJSONObject(i);
            Msg m = new Msg();
            m.clientId = o.optString("client_id");
            m.type = o.optString("type", "text");
            m.text = o.optString("text");
            m.order = o.optJSONObject("order");
            m.replyTo = o.optLong("reply_to");
            m.ts = o.optLong("ts", System.currentTimeMillis());
            m.state = 2;
            if (m.clientId.length() > 0) items.add(m);
        }
        adapter.notifyDataSetChanged();
        scrollToEnd();
    }

    private void persist() {
        try {
            JSONArray ms = new JSONArray();
            JSONArray pd = new JSONArray();
            for (Msg m : items) {
                if (m.seq > 0) {
                    JSONObject o = new JSONObject();
                    o.put("seq", m.seq); o.put("id", m.id); o.put("from", m.from); o.put("type", m.type); o.put("text", m.text);
                    o.put("ts", m.ts); o.put("delivered", m.delivered); o.put("read", m.read); o.put("deleted", m.deleted);
                    if (m.media != null) o.put("media", m.media);
                    if (m.quote != null) o.put("quote", m.quote);
                    if (m.order != null) o.put("order", m.order);
                    if (m.reply != null) o.put("reply", m.reply);
                    ms.put(o);
                } else if (m.localPath == null && m.clientId.length() > 0) {
                    JSONObject o = new JSONObject();
                    o.put("client_id", m.clientId); o.put("type", m.type); o.put("text", m.text); o.put("ts", m.ts);
                    if (m.order != null) o.put("order", m.order);
                    if (m.replyTo > 0) o.put("reply_to", m.replyTo);
                    pd.put(o);
                }
            }
            AdsChatStore.save(this, ms, pd);
        } catch (Throwable ignored) { }
    }

    private void bootstrap() {
        CigramAds.get(this, "/chat/thread", true, new CigramAds.Callback() {
            @Override public void onResult(CigramAds.Result r) {
                if (destroyed) return;
                if (!r.ok) {
                    if ("consent_required".equals(r.error)) {
                        AdsUi.toast(AdsChatActivity.this, "يلزم الموافقة على السياسات من صفحة «أعلن هنا».");
                        finish();
                        return;
                    }
                    setBanner(r.offline ? "لا يوجد اتصال — يعرض آخر محادثة محفوظة" : r.message);
                    loaded = true;
                    return;
                }
                JSONObject t = r.json.optJSONObject("thread");
                banned = t != null && t.optBoolean("banned");
                setBanner(null);
                updateSubtitle();
                loadPresence();
                CigramAds.get(AdsChatActivity.this, "/chat/messages?limit=40", true, new CigramAds.Callback() {
                    @Override public void onResult(CigramAds.Result r2) {
                        if (destroyed) return;
                        loaded = true;
                        if (!r2.ok) { setBanner(r2.message); return; }
                        JSONArray arr = r2.json.optJSONArray("messages");
                        applyServer(arr, true);
                        hasMore = arr != null && arr.length() >= 40;
                        scrollToEnd();
                        markRead();
                        flushPending();
                        sendOrderOnce();
                    }
                });
            }
        });
    }

    private void fetchNew() {
        if (!loaded || destroyed) return;
        CigramAds.get(this, "/chat/messages?after=" + lastSeq + "&limit=50", true, new CigramAds.Callback() {
            @Override public void onResult(CigramAds.Result r) {
                if (destroyed || !r.ok) return;
                JSONObject t = r.json.optJSONObject("thread");
                if (t != null) { banned = t.optBoolean("banned"); updateSubtitle(); }
                applyServer(r.json.optJSONArray("messages"), false);
                flushPending();
            }
        });
    }

    private void loadOlder() {
        long first = 0;
        for (Msg m : items) if (m.seq > 0) { first = m.seq; break; }
        if (first <= 1) { hasMore = false; return; }
        loadingOlder = true;
        CigramAds.get(this, "/chat/messages?before=" + first + "&limit=40", true, new CigramAds.Callback() {
            @Override public void onResult(CigramAds.Result r) {
                loadingOlder = false;
                if (destroyed || !r.ok) return;
                JSONArray arr = r.json.optJSONArray("messages");
                if (arr == null || arr.length() == 0) { hasMore = false; return; }
                int before = list.getFirstVisiblePosition();
                View v0 = list.getChildAt(0);
                int top = v0 == null ? 0 : v0.getTop();
                int added = applyServer(arr, false);
                list.setSelectionFromTop(before + added, top);
                if (arr.length() < 40) hasMore = false;
            }
        });
    }

    /** يدمج رسائل الخادم ويعيد عدد العناصر المضافة قبل أول عنصر ظاهر. */
    private int applyServer(JSONArray arr, boolean replace) {
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
            Msg n = Msg.fromJson(o);
            String cid = o.optString("client_id", "");
            int idx = -1;
            for (int k = 0; k < items.size(); k++) if (items.get(k).seq == n.seq && n.seq > 0) { idx = k; break; }
            if (idx >= 0) { items.set(idx, n); continue; }
            // رسالة معلّقة أرسلناها نحن
            for (int k = 0; k < items.size(); k++) {
                Msg p = items.get(k);
                if (p.seq == 0 && "user".equals(n.from) && p.clientId.length() > 0 && p.clientId.equals(cid)) { idx = k; break; }
            }
            if (idx >= 0) { items.remove(idx); }
            int pos = 0;
            while (pos < items.size() && (items.get(pos).seq > 0 && items.get(pos).seq < n.seq)) pos++;
            items.add(pos, n);
            if (firstShown > 0 && n.seq < firstShown) addedBefore++;
            if ("admin".equals(n.from) && n.seq > lastSeq) incoming = true;
            lastSeq = Math.max(lastSeq, n.seq);
        }
        adapter.notifyDataSetChanged();
        if (incoming && !replace) {
            if (atBottom) { scrollToEnd(); markRead(); }
            else { newBelow++; updateFab(); }
        }
        persist();
        return addedBefore;
    }

    // ------------------------------------------------------------------ أحداث لحظية

    private void onEvent(JSONObject e) {
        String t = e.optString("t");
        if ("msg".equals(t)) {
            JSONArray a = new JSONArray();
            JSONObject m = e.optJSONObject("msg");
            if (m == null) return;
            JSONObject copy = m;
            a.put(copy);
            applyServer(a, false);
            JSONObject ti = e.optJSONObject("thread_info");
            if (ti != null) { adminTyping = false; updateSubtitle(); }
        } else if ("read".equals(t) || "delivered".equals(t)) {
            long upto = e.optLong("upto");
            boolean rd = "read".equals(t);
            long now = e.optLong("ts", System.currentTimeMillis());
            for (Msg m : items) if ("user".equals(m.from) && m.seq > 0 && m.seq <= upto) {
                if (rd && m.read == 0) m.read = now;
                if (m.delivered == 0) m.delivered = now;
            }
            adapter.notifyDataSetChanged();
        } else if ("typing".equals(t)) {
            adminTyping = e.optBoolean("on");
            updateSubtitle();
            h.removeCallbacks(typingOff);
            if (adminTyping) h.postDelayed(typingOff, 5000);
        } else if ("presence".equals(t) && "admin".equals(e.optString("side"))) {
            adminOnline = e.optBoolean("online");
            if (adminOnline) presenceText = "";
            updateSubtitle();
        } else if ("thread".equals(t)) {
            banned = e.optBoolean("banned");
            updateSubtitle();
        } else if ("deleted".equals(t)) {
            long seq = e.optLong("seq");
            for (Msg m : items) if (m.seq == seq) { m.deleted = true; m.media = null; m.text = ""; }
            adapter.notifyDataSetChanged();
        } else if ("quote_state".equals(t)) {
            long seq = e.optLong("seq");
            for (Msg m : items) if (m.seq == seq && m.quote != null) { try { m.quote.put("state", e.optString("state")); } catch (Throwable ignored) { } }
            adapter.notifyDataSetChanged();
        } else if ("thread_gone".equals(t)) {
            items.clear();
            adapter.notifyDataSetChanged();
        }
    }

    private final Runnable typingOff = new Runnable() {
        @Override public void run() { adminTyping = false; updateSubtitle(); }
    };
    private final Runnable typingStop = new Runnable() {
        @Override public void run() {
            typingSent = false;
            if (ws != null) ws.sendText("{\"t\":\"typing\",\"on\":false}");
        }
    };

    private void notifyTyping() {
        if (ws == null || !ws.isOpen()) return;
        if (!typingSent) { typingSent = true; ws.sendText("{\"t\":\"typing\",\"on\":true}"); }
        h.removeCallbacks(typingStop);
        h.postDelayed(typingStop, 2500);
    }

    private void markRead() {
        if (!active || destroyed || lastSeq <= 0) return;
        boolean unread = false;
        for (Msg m : items) if ("admin".equals(m.from) && m.seq > 0 && m.read == 0) { unread = true; m.read = System.currentTimeMillis(); }
        if (!unread) return;
        AdsNotifier.cancelChatNotification(this);
        if (ws != null && ws.isOpen()) { ws.sendText("{\"t\":\"read\",\"upto\":" + lastSeq + "}"); return; }
        try {
            JSONObject b = new JSONObject();
            b.put("upto", lastSeq);
            CigramAds.post(this, "/chat/read", b, true, null);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ الإرسال

    private void sendOrderOnce() {
        if (orderJson == null || orderJson.length() == 0) return;
        String key = "order_sent_" + Integer.toHexString(orderJson.hashCode());
        long last = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(key, 0);
        if (System.currentTimeMillis() - last < 10 * 60 * 1000L) { orderJson = null; return; }
        try {
            Msg m = newPending("order", "طلب إعلان جديد");
            m.order = new JSONObject(orderJson);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(key, System.currentTimeMillis()).apply();
            orderJson = null;
            dispatch(m);
        } catch (Throwable ignored) { }
    }

    private Msg newPending(String type, String text) {
        Msg m = new Msg();
        m.clientId = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        m.type = type;
        m.text = text;
        m.ts = System.currentTimeMillis();
        m.state = 1;
        m.replyTo = replyToSeq;
        if (replyToSeq > 0 && replyPreview != null) {
            try { m.reply = new JSONObject().put("preview", replyPreview).put("seq", replyToSeq).put("from", "user"); } catch (Throwable ignored) { }
        }
        items.add(m);
        setReply(0, null);
        adapter.notifyDataSetChanged();
        scrollToEnd();
        return m;
    }

    private void dispatch(final Msg m) {
        m.state = 1;
        m.failMsg = "";
        adapter.notifyDataSetChanged();
        try {
            JSONObject b = new JSONObject();
            b.put("client_id", m.clientId);
            b.put("type", m.type);
            if (m.text != null && m.text.length() > 0 && !"order".equals(m.type)) b.put("text", m.text);
            if (m.replyTo > 0) b.put("reply_to", m.replyTo);
            if (m.order != null) b.put("order", m.order);
            if (m.media != null && m.media.optString("id").length() > 0) b.put("media_id", m.media.optString("id"));
            CigramAds.post(this, "/chat/send", b, true, new CigramAds.Callback() {
                @Override public void onResult(CigramAds.Result r) {
                    if (destroyed) return;
                    if (r.ok) {
                        JSONArray a = new JSONArray();
                        JSONObject mm = r.json.optJSONObject("message");
                        if (mm != null) {
                            try { mm.put("client_id", m.clientId); } catch (Throwable ignored) { }
                            a.put(mm);
                        }
                        JSONObject auto = r.json.optJSONObject("auto");
                        if (auto != null) a.put(auto);
                        if (m.localPath != null) new File(m.localPath).delete();
                        applyServer(a, false);
                        scrollToEnd();
                    } else {
                        m.state = 2;
                        m.failMsg = r.message;
                        if ("banned".equals(r.error)) { banned = true; updateSubtitle(); }
                        if ("consent_required".equals(r.error)) AdsUi.toast(AdsChatActivity.this, "يلزم الموافقة على السياسات الحالية من صفحة «أعلن هنا».");
                        if (r.error != null && ("banned".equals(r.error) || "empty_message".equals(r.error) || "media_not_ready".equals(r.error))) m.localPath = m.localPath;
                        adapter.notifyDataSetChanged();
                        persist();
                    }
                }
            });
        } catch (Throwable t) {
            m.state = 2;
            adapter.notifyDataSetChanged();
        }
    }

    private void flushPending() {
        if (destroyed) return;
        for (Msg m : new ArrayList<Msg>(items)) {
            if (m.seq == 0 && m.state == 2 && m.localPath == null && m.failMsg.length() == 0) dispatch(m);
        }
    }

    private void doSend() {
        String s = input.getText().toString().trim();
        if (s.length() == 0 || banned) return;
        if (s.length() > 4000) s = s.substring(0, 4000);
        input.setText("");
        h.removeCallbacks(typingStop);
        typingStop.run();
        Msg m = newPending("text", s);
        dispatch(m);
    }

    // ------------------------------------------------------------------ المرفقات والصوت

    private void showAttachSheet() {
        if (banned) return;
        LinearLayout box = AdsUi.vbox(this);
        final CigramUI.Sheet[] holder = new CigramUI.Sheet[1];
        String[] labels = {"صورة من المعرض", "فيديو من المعرض", "التقاط صورة", "ملف (PDF / مستند)"};
        int[] icons = {AdsIcons.IMAGE, AdsIcons.VIDEO, AdsIcons.CAMERA, AdsIcons.FILE};
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            LinearLayout row = AdsUi.hbox(this);
            row.setPadding(AdsUi.dp(this, 8), 0, AdsUi.dp(this, 8), 0);
            row.setMinimumHeight(AdsUi.dp(this, 52));
            row.setClickable(true);
            row.setBackground(CigramGlass.ripple(this, new GradientDrawable(), 12));
            row.addView(new AdsIcons.Icon(this, icons[i], CigramUI.TEAL), new LinearLayout.LayoutParams(AdsUi.dp(this, 24), AdsUi.dp(this, 24)));
            row.addView(AdsUi.text(this, labels[i], 16f, CigramGlass.text(this), false), AdsUi.lp(-2, -2, AdsUi.dp(this, 12), 0, 0, 0));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (holder[0] != null) holder[0].dismiss();
                    pick(which);
                }
            });
            box.addView(row, new LinearLayout.LayoutParams(-1, AdsUi.dp(this, 52)));
        }
        holder[0] = CigramUI.sheet(this).title("إرفاق").view(box).secondary("إغلاق", null);
        holder[0].show();
    }

    private void pick(int which) {
        try {
            if (which == 2) {
                File[] f = new File[1];
                Uri u = AdsFileProvider.newCaptureUri(this, f);
                cameraPath = f[0].getAbsolutePath();
                Intent i = new Intent("android.media.action.IMAGE_CAPTURE");
                i.putExtra("output", u);
                i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                i.setClipData(ClipData.newRawUri("", u));
                startActivityForResult(i, REQ_CAMERA);
                return;
            }
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(which == 0 ? "image/*" : which == 1 ? "video/*" : "*/*");
            if (which == 3) i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "application/zip", "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"});
            startActivityForResult(Intent.createChooser(i, "اختر"), REQ_PICK + which * 100);
        } catch (Throwable t) {
            AdsUi.toast(this, "لا يوجد تطبيق مناسب لاختيار الملف.");
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (destroyed || res != RESULT_OK) return;
        Uri uri = null;
        String kind = null;
        if (req == REQ_CAMERA) {
            if (cameraPath != null) uri = Uri.parse("content://" + AdsFileProvider.AUTHORITY + "/capture/" + new File(cameraPath).getName());
            kind = "image";
        } else if (req >= REQ_PICK && data != null) {
            uri = data.getData();
            int which = (req - REQ_PICK) / 100;
            kind = which == 0 ? "image" : which == 1 ? "video" : "file";
        }
        if (uri == null) return;
        final String fk = kind;
        AdsUi.toast(this, "جارٍ تجهيز الملف...");
        AdsMedia.prepare(this, uri, kind, new AdsMedia.Prepare() {
            @Override public void onReady(AdsMedia.Prepared p, String error) {
                if (destroyed) return;
                if (p == null) { AdsUi.toast(AdsChatActivity.this, error == null ? "تعذر تجهيز الملف." : error); return; }
                startUpload(p, fk, 0, null);
            }
        });
    }

    private void startUpload(AdsMedia.Prepared p, String kind, int durMs, int[] wave) {
        final Msg m = newPending(kind, "");
        m.localPath = p.file.getAbsolutePath();
        m.cancel = new AtomicBoolean(false);
        m.progress = 0;
        try {
            JSONObject md = new JSONObject();
            md.put("name", p.name); md.put("size", p.size); md.put("w", p.w); md.put("h", p.h); md.put("kind", kind);
            md.put("dur", durMs > 0 ? durMs : p.durMs);
            if (wave != null) { JSONArray w = new JSONArray(); for (int v : wave) w.put(v); md.put("wave", w); }
            m.media = md;
        } catch (Throwable ignored) { }
        adapter.notifyDataSetChanged();
        AdsMedia.upload(this, p, durMs, wave, "/chat/upload", null, CigramUserData.token(this), new AdsMedia.Progress() {
            @Override public void onProgress(int pct) { if (!destroyed) { m.progress = pct; adapter.notifyDataSetChanged(); } }
        }, new AdsMedia.Done() {
            @Override public void onDone(boolean ok, String mediaId, String message) {
                if (destroyed) return;
                if (m.cancel.get()) { items.remove(m); adapter.notifyDataSetChanged(); return; }
                m.progress = -1;
                if (ok) {
                    try { m.media.put("id", mediaId); } catch (Throwable ignored) { }
                    dispatch(m);
                } else {
                    m.state = 2;
                    m.failMsg = message;
                    adapter.notifyDataSetChanged();
                    AdsUi.toast(AdsChatActivity.this, message);
                }
            }
        }, m.cancel);
    }

    private boolean onActionTouch(MotionEvent e) {
        int a = e.getActionMasked();
        boolean hasText = input.getText().toString().trim().length() > 0;
        if (hasText) {
            if (a == MotionEvent.ACTION_UP) doSend();
            return true;
        }
        if (banned) return true;
        if (a == MotionEvent.ACTION_DOWN) {
            if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, REQ_PERM_AUDIO);
                return true;
            }
            recDownX = e.getRawX();
            recCancelled = false;
            recorder = new AdsVoice.Recorder();
            if (!recorder.start(this, new AdsVoice.Recorder.Tick() {
                @Override public void onTick(int ms, int amp) { timerText.setText(AdsMedia.durLabel(ms)); }
            })) {
                recorder = null;
                AdsUi.toast(this, "تعذر بدء التسجيل.");
                return true;
            }
            input.setVisibility(View.INVISIBLE);
            recBar.setVisibility(View.VISIBLE);
            timerText.setText("0:00");
            return true;
        }
        if (recorder == null) return true;
        if (a == MotionEvent.ACTION_MOVE) {
            boolean cancel = Math.abs(e.getRawX() - recDownX) > AdsUi.dp(this, 90);
            if (cancel != recCancelled) {
                recCancelled = cancel;
                timerText.setTextColor(cancel ? CigramUI.DANGER : CigramGlass.text(this));
            }
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            boolean cancel = recCancelled || a == MotionEvent.ACTION_CANCEL;
            int dur = recorder.elapsed();
            AdsVoice.Recorder.Result r = recorder.stop(cancel || dur < 1000);
            recorder = null;
            recBar.setVisibility(View.GONE);
            input.setVisibility(View.VISIBLE);
            timerText.setTextColor(CigramGlass.text(this));
            if (r != null) {
                AdsMedia.Prepared p = new AdsMedia.Prepared();
                p.file = r.file; p.kind = "voice"; p.name = "voice.m4a"; p.size = r.file.length();
                startUpload(p, "voice", r.durMs, r.wave);
            } else if (!cancel && dur < 1000) {
                AdsUi.toast(this, "اضغط مطولاً للتسجيل");
            }
        }
        return true;
    }

    private void stopRecording(boolean cancel) {
        if (recorder != null) {
            recorder.stop(cancel);
            recorder = null;
            if (recBar != null) recBar.setVisibility(View.GONE);
            if (input != null) input.setVisibility(View.VISIBLE);
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_PERM_AUDIO) {
            boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            AdsUi.toast(this, ok ? "اضغط مطولاً على الميكروفون للتسجيل." : "لا يمكن إرسال رسالة صوتية دون إذن الميكروفون.");
        }
    }

    private void askNotifPermission() {
        try {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
                    && !getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("notif_asked", false)) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("notif_asked", true).apply();
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_PERM_NOTIF);
            }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ الرد والإجراءات

    private void setReply(long seq, String preview) {
        replyToSeq = seq;
        replyPreview = preview;
        if (seq > 0) { replyText.setText("رد على: " + preview); replyBar.setVisibility(View.VISIBLE); }
        else replyBar.setVisibility(View.GONE);
    }

    private void showActions(final Msg m) {
        if (m.deleted) return;
        LinearLayout box = AdsUi.vbox(this);
        final CigramUI.Sheet[] holder = new CigramUI.Sheet[1];
        ArrayList<String> labels = new ArrayList<String>();
        ArrayList<Runnable> acts = new ArrayList<Runnable>();
        if (m.state == 2) {
            labels.add("إعادة المحاولة");
            acts.add(new Runnable() { @Override public void run() {
                if (m.localPath != null && (m.media == null || m.media.optString("id").length() == 0)) { items.remove(m); adapter.notifyDataSetChanged(); AdsUi.toast(AdsChatActivity.this, "أعد اختيار الملف لإرساله."); }
                else dispatch(m);
            } });
            labels.add("حذف");
            acts.add(new Runnable() { @Override public void run() { items.remove(m); adapter.notifyDataSetChanged(); persist(); } });
        } else if (m.seq > 0) {
            labels.add("رد");
            acts.add(new Runnable() { @Override public void run() {
                setReply(m.seq, previewOf(m));
                input.requestFocus();
            } });
            if (m.text != null && m.text.length() > 0) {
                labels.add("نسخ النص");
                acts.add(new Runnable() { @Override public void run() {
                    try {
                        ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("msg", m.text));
                        AdsUi.toast(AdsChatActivity.this, "تم النسخ");
                    } catch (Throwable ignored) { }
                } });
            }
        }
        if (labels.isEmpty()) return;
        for (int i = 0; i < labels.size(); i++) {
            final Runnable r = acts.get(i);
            TextView t = AdsUi.text(this, labels.get(i), 16f, CigramGlass.text(this), false);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(AdsUi.dp(this, 12), 0, AdsUi.dp(this, 12), 0);
            t.setClickable(true);
            t.setBackground(CigramGlass.ripple(this, new GradientDrawable(), 12));
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (holder[0] != null) holder[0].dismiss(); r.run(); }
            });
            box.addView(t, new LinearLayout.LayoutParams(-1, AdsUi.dp(this, 52)));
        }
        holder[0] = CigramUI.sheet(this).view(box).secondary("إغلاق", null);
        holder[0].show();
    }

    private static String previewOf(Msg m) {
        if ("image".equals(m.type)) return "📷 صورة";
        if ("video".equals(m.type)) return "🎬 فيديو";
        if ("voice".equals(m.type)) return "🎤 رسالة صوتية";
        if ("file".equals(m.type)) return "📎 ملف";
        if ("quote".equals(m.type)) return "💰 عرض سعر";
        if ("order".equals(m.type)) return "📝 طلب إعلان";
        String t = m.text == null ? "" : m.text.replace('\n', ' ');
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    private void respondQuote(final Msg m, final boolean accept) {
        try {
            JSONObject b = new JSONObject();
            b.put("seq", m.seq);
            b.put("accept", accept);
            CigramAds.post(this, "/chat/quote/respond", b, true, new CigramAds.Callback() {
                @Override public void onResult(CigramAds.Result r) {
                    if (destroyed) return;
                    if (r.ok) {
                        try { m.quote.put("state", r.json.optString("state")); } catch (Throwable ignored) { }
                        adapter.notifyDataSetChanged();
                        fetchNew();
                    } else AdsUi.toast(AdsChatActivity.this, r.message.length() > 0 ? r.message : "تعذر تنفيذ الطلب.");
                }
            });
        } catch (Throwable ignored) { }
    }

    private void confirmQuote(final Msg m, final boolean accept) {
        CigramUI.sheet(this).icon(accept ? CigramUI.ICON_CHECK : CigramUI.ICON_WARN, accept ? CigramUI.GREEN : CigramUI.AMBER)
                .title(accept ? "قبول عرض السعر؟" : "رفض عرض السعر؟")
                .message(accept ? "سيتم إبلاغ الإدارة بموافقتك لتبدأ إجراءات الحملة والدفع." : "سيتم إبلاغ الإدارة برفضك للعرض، ويمكنك التفاوض معهم بعدها.")
                .primary(accept ? "قبول" : "رفض", accept ? CigramUI.TEAL : CigramUI.DANGER, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) { respondQuote(m, accept); return true; }
                }).secondary("إلغاء", null).show();
    }

    // ------------------------------------------------------------------ قائمة الرسائل

    private void scrollToEnd() {
        if (destroyed || list == null) return;
        list.post(new Runnable() {
            @Override public void run() {
                if (!destroyed && adapter.getCount() > 0) list.setSelection(adapter.getCount() - 1);
            }
        });
        newBelow = 0;
        updateFab();
    }

    private void updateFab() {
        if (fab == null) return;
        boolean show = !atBottom;
        fab.setVisibility(show ? View.VISIBLE : View.GONE);
        fab.setText(newBelow > 0 ? "↓ " + newBelow : "↓");
    }

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("hh:mm a", new Locale("ar"));
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("d MMMM yyyy", new Locale("ar"));

    private String dayLabel(long ts) {
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(ts);
        if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)) return "اليوم";
        a.add(Calendar.DAY_OF_YEAR, -1);
        if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)) return "أمس";
        return dayFmt.format(new Date(ts));
    }

    private static boolean sameDay(long a, long b) {
        Calendar x = Calendar.getInstance();
        Calendar y = Calendar.getInstance();
        x.setTimeInMillis(a);
        y.setTimeInMillis(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }

    private final class ChatAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout root;
            if (convert instanceof LinearLayout) root = (LinearLayout) convert;
            else {
                root = AdsUi.vbox(AdsChatActivity.this);
                root.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, -2));
            }
            root.removeAllViews();
            root.setPadding(AdsUi.dp(AdsChatActivity.this, 10), AdsUi.dp(AdsChatActivity.this, 3), AdsUi.dp(AdsChatActivity.this, 10), AdsUi.dp(AdsChatActivity.this, 3));
            Msg m = items.get(pos);
            if (pos == 0 || !sameDay(items.get(pos - 1).ts, m.ts)) {
                TextView d = AdsUi.text(AdsChatActivity.this, dayLabel(m.ts), 12f, CigramGlass.muted(AdsChatActivity.this), false);
                d.setBackground(CigramUI.round(CigramGlass.soft(AdsChatActivity.this), 12, AdsChatActivity.this));
                d.setPadding(AdsUi.dp(AdsChatActivity.this, 12), AdsUi.dp(AdsChatActivity.this, 4), AdsUi.dp(AdsChatActivity.this, 12), AdsUi.dp(AdsChatActivity.this, 4));
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
                dl.gravity = Gravity.CENTER_HORIZONTAL;
                dl.setMargins(0, AdsUi.dp(AdsChatActivity.this, 8), 0, AdsUi.dp(AdsChatActivity.this, 8));
                root.addView(d, dl);
            }
            buildRow(root, m);
            return root;
        }
    }

    private void buildRow(LinearLayout root, final Msg m) {
        final Context c = this;
        if ("system".equals(m.type)) {
            TextView t = AdsUi.text(c, m.text, 13f, CigramGlass.muted(c), false);
            t.setGravity(Gravity.CENTER);
            t.setBackground(CigramUI.round(CigramGlass.soft(c), 14, c));
            t.setPadding(AdsUi.dp(c, 12), AdsUi.dp(c, 6), AdsUi.dp(c, 12), AdsUi.dp(c, 6));
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.gravity = Gravity.CENTER_HORIZONTAL;
            root.addView(t, l);
            return;
        }
        boolean mine = "user".equals(m.from);
        LinearLayout bubble = AdsUi.vbox(c);
        bubble.setPadding(AdsUi.dp(c, 10), AdsUi.dp(c, 8), AdsUi.dp(c, 10), AdsUi.dp(c, 6));
        bubble.setBackground(CigramUI.round(mine ? 0xFF1E8794 : CigramGlass.soft(c), mine ? 0 : CigramGlass.stroke(c), 16, c));
        int tx = mine ? 0xFFFFFFFF : CigramGlass.text(c);
        int sub = mine ? 0xCCFFFFFF : CigramGlass.muted(c);

        if (m.reply != null && !m.deleted) {
            LinearLayout rp = AdsUi.vbox(c);
            rp.setPadding(AdsUi.dp(c, 8), AdsUi.dp(c, 4), AdsUi.dp(c, 8), AdsUi.dp(c, 4));
            rp.setBackground(CigramUI.round(mine ? 0x33000000 : 0x22000000, 8, c));
            rp.addView(AdsUi.text(c, "رد على: " + ("user".equals(m.reply.optString("from")) ? "أنت" : "الإدارة"), 11f, sub, true));
            TextView pv = AdsUi.text(c, m.reply.optString("preview"), 13f, tx, false);
            pv.setMaxLines(2);
            rp.addView(pv);
            bubble.addView(rp, AdsUi.lp(-1, -2, 0, 0, 0, AdsUi.dp(c, 6)));
        }
        if (m.deleted) {
            TextView t = AdsUi.text(c, "🚫 حُذفت هذه الرسالة", 14f, sub, false);
            t.setTypeface(t.getTypeface(), Typeface.ITALIC);
            bubble.addView(t);
        } else if (("image".equals(m.type) || ("file".equals(m.type) && m.media != null && m.media.optString("mime").startsWith("image/"))) && m.media != null) {
            bubble.addView(imageView(m, false));
        } else if ("video".equals(m.type) && m.media != null) {
            bubble.addView(imageView(m, true));
        } else if ("voice".equals(m.type) && m.media != null) {
            bubble.addView(voiceView(m, tx, sub, mine));
        } else if ("file".equals(m.type) && m.media != null) {
            bubble.addView(fileView(m, tx, sub));
        } else if ("quote".equals(m.type) && m.quote != null) {
            bubble.addView(quoteView(m, tx, sub));
        } else if ("order".equals(m.type) && m.order != null) {
            bubble.addView(orderView(m, tx, sub));
        }
        boolean cardOnly = "quote".equals(m.type) || "order".equals(m.type);
        if (!m.deleted && m.text != null && m.text.length() > 0 && !cardOnly && !"voice".equals(m.type)) {
            TextView t = AdsUi.text(c, m.text, 15f, tx, false);
            t.setLineSpacing(0f, 1.15f);
            t.setAutoLinkMask(android.text.util.Linkify.WEB_URLS | android.text.util.Linkify.PHONE_NUMBERS);
            t.setLinkTextColor(mine ? 0xFFFFFFFF : CigramUI.TEAL);
            t.setTextIsSelectable(false);
            bubble.addView(t, AdsUi.lp(-2, -2, 0, ("image".equals(m.type) || "video".equals(m.type)) ? AdsUi.dp(c, 6) : 0, 0, 0));
        }
        // الوقت والحالة
        LinearLayout foot = AdsUi.hbox(c);
        foot.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        String status = "";
        if (m.state == 2) status = m.failMsg.length() > 0 ? m.failMsg : "فشل الإرسال — اضغط مطولاً";
        TextView tm = AdsUi.text(c, status.length() > 0 ? status : timeFmt.format(new Date(m.ts)), 11f, m.state == 2 ? 0xFFFFB4AB : sub, false);
        foot.addView(tm);
        if (mine) {
            int icon = AdsIcons.CHECK;
            int col = sub;
            if (m.state == 1) icon = AdsIcons.CLOCK;
            else if (m.state == 2) { icon = AdsIcons.ALERT; col = 0xFFFFB4AB; }
            else if (m.read > 0) { icon = AdsIcons.DOUBLE_CHECK; col = 0xFF7FE9F5; }
            else if (m.delivered > 0) icon = AdsIcons.DOUBLE_CHECK;
            foot.addView(new AdsIcons.Icon(c, icon, col), AdsUi.lp(AdsUi.dp(c, 14), AdsUi.dp(c, 14), AdsUi.dp(c, 4), 0, 0, 0));
        }
        bubble.addView(foot, AdsUi.lp(-2, -2, 0, AdsUi.dp(c, 3), 0, 0));
        bubble.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showActions(m); return true; }
        });
        if (m.state == 2) bubble.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showActions(m); }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.gravity = mine ? Gravity.RIGHT : Gravity.LEFT;
        bubble.setMinimumWidth(AdsUi.dp(c, 72));
        FrameLayout cap = new FrameLayout(c);
        cap.addView(bubble, new FrameLayout.LayoutParams(-2, -2));
        root.addView(wrapMax(bubble, mine), bl);
    }

    /** يحدّ عرض الفقاعة بـ78% من الشاشة. */
    private View wrapMax(final LinearLayout bubble, boolean mine) {
        LinearLayout holder = new LinearLayout(this) {
            @Override protected void onMeasure(int w, int hh) {
                int mode = View.MeasureSpec.getMode(w);
                int size = Math.min(View.MeasureSpec.getSize(w), maxBubble);
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(size, mode == View.MeasureSpec.UNSPECIFIED ? View.MeasureSpec.UNSPECIFIED : View.MeasureSpec.AT_MOST), hh);
            }
        };
        holder.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
        return holder;
    }

    private View imageView(final Msg m, final boolean video) {
        int w = m.media.optInt("w"), hh = m.media.optInt("h");
        int bw = Math.min(maxBubble - AdsUi.dp(this, 24), AdsUi.dp(this, 240));
        int bh = (w > 0 && hh > 0) ? (int) (bw * (float) hh / w) : bw * 3 / 4;
        bh = Math.max(AdsUi.dp(this, 110), Math.min(AdsUi.dp(this, 320), bh));
        FrameLayout f = new FrameLayout(this);
        AdsViews.Round iv = new AdsViews.Round(this, AdsUi.dp(this, 10));
        iv.setBackgroundColor(0x33000000);
        iv.setContentDescription(video ? "فيديو" : "صورة");
        f.addView(iv, new FrameLayout.LayoutParams(bw, bh));
        if (m.localPath != null && !video) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 4;
                Bitmap b = BitmapFactory.decodeFile(m.localPath, o);
                if (b != null) iv.setImageBitmap(b);
            } catch (Throwable ignored) { }
        } else if (m.media.optString("url").length() > 0) {
            AdsMedia.loadInto(this, iv, m.media.optString("url"), 640, video);
        }
        if (video) {
            f.addView(new AdsIcons.Icon(this, AdsIcons.PLAY, 0xFFFFFFFF), new FrameLayout.LayoutParams(AdsUi.dp(this, 44), AdsUi.dp(this, 44), Gravity.CENTER));
            if (m.media.optInt("dur") > 0) {
                TextView d = AdsUi.text(this, AdsMedia.durLabel(m.media.optInt("dur")), 11f, 0xFFFFFFFF, true);
                d.setBackground(CigramUI.round(0x99000000, 8, this));
                d.setPadding(AdsUi.dp(this, 6), AdsUi.dp(this, 2), AdsUi.dp(this, 6), AdsUi.dp(this, 2));
                f.addView(d, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT));
            }
        }
        if (m.progress >= 0 && m.state == 1) f.addView(uploadOverlay(m), new FrameLayout.LayoutParams(-1, -1));
        final String url = m.media.optString("url");
        f.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (m.state != 0 || url.length() == 0) return;
                if (video) openExternal(url, "video/*"); else showImage(url);
            }
        });
        f.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showActions(m); return true; }
        });
        return f;
    }

    private View uploadOverlay(final Msg m) {
        LinearLayout o = AdsUi.vbox(this);
        o.setGravity(Gravity.CENTER);
        o.setBackgroundColor(0x88000000);
        o.addView(AdsUi.text(this, m.progress + "%", 16f, 0xFFFFFFFF, true));
        TextView x = AdsUi.text(this, "إلغاء", 13f, 0xFFFFFFFF, false);
        x.setPadding(AdsUi.dp(this, 14), AdsUi.dp(this, 12), AdsUi.dp(this, 14), AdsUi.dp(this, 12));
        x.setMinHeight(AdsUi.dp(this, 48));
        x.setGravity(Gravity.CENTER);
        x.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (m.cancel != null) m.cancel.set(true); items.remove(m); adapter.notifyDataSetChanged(); }
        });
        o.addView(x);
        return o;
    }

    private View voiceView(final Msg m, int tx, int sub, boolean mine) {
        LinearLayout row = AdsUi.hbox(this);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        final FrameLayout play = new FrameLayout(this);
        play.setBackground(CigramUI.round(mine ? 0x33FFFFFF : CigramUI.TEAL, 24, this));
        play.setContentDescription("تشغيل الرسالة الصوتية");
        final AdsIcons.Icon icon = new AdsIcons.Icon(this, AdsIcons.PLAY, 0xFFFFFFFF);
        play.addView(icon, new FrameLayout.LayoutParams(AdsUi.dp(this, 22), AdsUi.dp(this, 22), Gravity.CENTER));
        row.addView(play, new LinearLayout.LayoutParams(AdsUi.dp(this, 44), AdsUi.dp(this, 44)));
        final AdsViews.Wave wave = new AdsViews.Wave(this, mine ? 0xFFFFFFFF : CigramUI.TEAL, mine ? 0x66FFFFFF : 0x55888888);
        int[] bars = new int[0];
        JSONArray wa = m.media.optJSONArray("wave");
        if (wa != null) { bars = new int[wa.length()]; for (int i = 0; i < bars.length; i++) bars[i] = wa.optInt(i, 20); }
        wave.setBars(bars);
        row.addView(wave, AdsUi.lp(AdsUi.dp(this, 120), AdsUi.dp(this, 32), AdsUi.dp(this, 8), 0, AdsUi.dp(this, 8), 0));
        final TextView dur = AdsUi.text(this, AdsMedia.durLabel(m.media.optInt("dur")), 12f, sub, false);
        row.addView(dur);
        final String id = m.id.length() > 0 ? m.id : m.clientId;
        if (id.equals(AdsVoice.currentId())) icon.setTag("playing");
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String url = m.media.optString("url");
                if (url.length() == 0 && m.localPath != null) url = m.localPath;
                if (url.length() == 0) return;
                AdsVoice.setListener(new AdsVoice.PlayListener() {
                    @Override public void onState(String pid, boolean playing, int pos, int total) {
                        if (destroyed) return;
                        if (total < 0) AdsUi.toast(AdsChatActivity.this, "تعذر تشغيل الرسالة الصوتية.");
                        adapter.notifyDataSetChanged();
                    }
                });
                AdsVoice.toggle(id, url);
            }
        });
        return row;
    }

    private View fileView(final Msg m, int tx, int sub) {
        LinearLayout row = AdsUi.hbox(this);
        row.setMinimumHeight(AdsUi.dp(this, 48));
        row.addView(new AdsIcons.Icon(this, AdsIcons.FILE, tx), new LinearLayout.LayoutParams(AdsUi.dp(this, 28), AdsUi.dp(this, 28)));
        LinearLayout col = AdsUi.vbox(this);
        TextView n = AdsUi.text(this, m.media.optString("name", "ملف"), 14f, tx, true);
        n.setMaxLines(2);
        col.addView(n);
        col.addView(AdsUi.text(this, AdsMedia.sizeLabel(m.media.optLong("size")), 12f, sub, false));
        row.addView(col, AdsUi.lp(-2, -2, AdsUi.dp(this, 10), 0, 0, 0));
        if (m.progress >= 0 && m.state == 1) row.addView(AdsUi.text(this, m.progress + "%", 12f, sub, true), AdsUi.lp(-2, -2, AdsUi.dp(this, 10), 0, 0, 0));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String url = m.media.optString("url");
                if (m.state == 0 && url.length() > 0) openExternal(url, null);
            }
        });
        return row;
    }

    private View quoteView(final Msg m, int tx, int sub) {
        JSONObject q = m.quote;
        LinearLayout card = AdsUi.vbox(this);
        card.setPadding(AdsUi.dp(this, 4), AdsUi.dp(this, 2), AdsUi.dp(this, 4), AdsUi.dp(this, 2));
        card.addView(AdsUi.text(this, "💰 عرض سعر", 14f, tx, true));
        String label = q.optString("slot_label", q.optString("slot"));
        card.addView(AdsUi.text(this, label + " — " + CigramAds.durationLabel(q.optInt("days", 1)), 13f, sub, false), AdsUi.lp(-2, -2, 0, AdsUi.dp(this, 4), 0, 0));
        String cur = q.optString("currency", "USD");
        double total = q.optDouble("total", 0);
        card.addView(AdsUi.text(this, (total == Math.floor(total) ? String.valueOf((long) total) : String.valueOf(total)) + " " + cur, 22f, tx, true), AdsUi.lp(-2, -2, 0, AdsUi.dp(this, 6), 0, AdsUi.dp(this, 4)));
        if (q.optString("note").length() > 0) card.addView(AdsUi.text(this, q.optString("note"), 13f, tx, false));
        long exp = q.optLong("expires_at");
        String state = q.optString("state", "pending");
        boolean expired = exp > 0 && exp < CigramAds.now();
        if ("accepted".equals(state)) card.addView(AdsUi.text(this, "✅ تم القبول", 13f, 0xFFB8FFDD, true), AdsUi.lp(-2, -2, 0, AdsUi.dp(this, 6), 0, 0));
        else if ("declined".equals(state)) card.addView(AdsUi.text(this, "❌ تم الرفض", 13f, 0xFFFFB4AB, true), AdsUi.lp(-2, -2, 0, AdsUi.dp(this, 6), 0, 0));
        else if (expired) card.addView(AdsUi.text(this, "انتهت صلاحية العرض", 13f, sub, true), AdsUi.lp(-2, -2, 0, AdsUi.dp(this, 6), 0, 0));
        else if ("admin".equals(m.from) && m.seq > 0) {
            LinearLayout btns = AdsUi.hbox(this);
            TextView ok = AdsUi.button(this, "قبول", 0xFFFFFFFF, 0xFF1B1B1F);
            ok.setMinHeight(AdsUi.dp(this, 48));
            ok.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { confirmQuote(m, true); } });
            TextView no = AdsUi.button(this, "رفض", 0x33000000, 0xFFFFFFFF);
            no.setMinHeight(AdsUi.dp(this, 48));
            no.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { confirmQuote(m, false); } });
            btns.addView(ok, AdsUi.lp(0, -2, 0, 0, AdsUi.dp(this, 6), 0));
            ((LinearLayout.LayoutParams) ok.getLayoutParams()).weight = 1f;
            btns.addView(no, AdsUi.lp(0, -2, 0, 0, 0, 0));
            ((LinearLayout.LayoutParams) no.getLayoutParams()).weight = 1f;
            card.addView(btns, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 8), 0, 0));
        }
        return card;
    }

    private View orderView(Msg m, int tx, int sub) {
        JSONObject o = m.order;
        LinearLayout card = AdsUi.vbox(this);
        card.addView(AdsUi.text(this, "📝 طلب إعلان", 14f, tx, true));
        StringBuilder sb = new StringBuilder();
        sb.append(o.optString("slot_label", o.optString("slot"))).append(" — ").append(CigramAds.durationLabel(o.optInt("days", 1)));
        JSONArray cn = o.optJSONArray("city_names");
        if (cn != null && cn.length() > 0) {
            sb.append("\nالمدن: ");
            for (int i = 0; i < cn.length(); i++) sb.append(i > 0 ? "، " : "").append(cn.optString(i));
        }
        JSONObject ad = o.optJSONObject("addons");
        if (ad != null) {
            if (ad.optBoolean("exclusive")) sb.append("\n• حصرية");
            if (ad.optBoolean("video")) sb.append("\n• فيديو");
            if (ad.optBoolean("design")) sb.append("\n• تصميم");
        }
        card.addView(AdsUi.text(this, sb.toString(), 13f, tx, false), AdsUi.lp(-2, -2, 0, AdsUi.dp(this, 4), 0, 0));
        if (o.optDouble("total", 0) > 0) card.addView(AdsUi.text(this, "التقدير: " + o.optString("total") + " " + o.optString("currency", "USD"), 13f, sub, true), AdsUi.lp(-2, -2, 0, AdsUi.dp(this, 4), 0, 0));
        return card;
    }

    // ------------------------------------------------------------------ عارضات

    private void openExternal(String url, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            if (mime != null) i.setDataAndType(Uri.parse(url), mime); else i.setData(Uri.parse(url));
            startActivity(i);
        } catch (Throwable t) {
            AdsUi.toast(this, "لا يوجد تطبيق لفتح هذا الملف.");
        }
    }

    private void showImage(String url) {
        final Dialog d = new Dialog(this);
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(0xFF000000);
        final AdsViews.Zoom z = new AdsViews.Zoom(this);
        z.setOnSingleTap(new Runnable() { @Override public void run() { d.dismiss(); } });
        f.addView(z, new FrameLayout.LayoutParams(-1, -1));
        TextView close = AdsUi.text(this, "✕", 22f, 0xFFFFFFFF, true);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("إغلاق");
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } });
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(AdsUi.dp(this, 48), AdsUi.dp(this, 48), Gravity.TOP | Gravity.RIGHT);
        cl.setMargins(0, AdsUi.dp(this, 24), AdsUi.dp(this, 8), 0);
        f.addView(close, cl);
        d.requestWindowFeature(1);
        d.setContentView(f);
        try { d.getWindow().setBackgroundDrawable(new ColorDrawable(0xFF000000)); d.getWindow().setLayout(-1, -1); } catch (Throwable ignored) { }
        d.show();
        AdsMedia.loadInto(this, z, url, 2048, false);
        z.postDelayed(new Runnable() { @Override public void run() { z.fit(); } }, 400);
    }
}
