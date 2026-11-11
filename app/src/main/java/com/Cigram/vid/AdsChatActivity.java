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
 * 18.2: Cigram Private Support (الدعم الخاص) — the private conversation between the viewer and the Cigram developer
 * (entry «أريد إعلاناً»). Same server thread, same reliability layer as before; new premium black design, developer
 * identity, emoji panel, voice preview, 24-hour times, offline retry with backoff. See {@link CigramSupport}.
 *
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
        /** 18.2: the sender's PUBLIC developer identity (name / picture) on developer messages, when the server sends it. */
        JSONObject dev;
        int state; // 0 على الخادم، 1 قيد الإرسال، 2 فشل
        int progress = -1;
        String localPath;
        /** The prepared file of an upload that failed: «إعادة المحاولة» resumes it (not persisted). */
        AdsMedia.Prepared prepared;
        int uploadDur;
        int[] uploadWave;
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
            m.dev = o.optJSONObject("dev");
            m.clientId = o.optString("client_id", "");
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
    // 18.2 support UI
    private TextView devName;
    private AdsViews.Round devAvatar;
    private CigramEmojiPanel emojiPanel;
    private CigramChatSuggest suggest;
    private boolean recLocked;
    private TextView recHint;
    private LinearLayout voicePreview;
    private TextView voicePreviewTime;
    private AdsIcons.Icon voicePreviewIcon;
    private AdsVoice.Recorder.Result pendingVoice;
    private int retryDelayMs = 2000;
    private final Runnable retryRun = new Runnable() {
        @Override public void run() { if (!destroyed) flushPending(); }
    };

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
            getWindow().setStatusBarColor(CigramSupport.BG);
            getWindow().setNavigationBarColor(CigramSupport.BAR);
            // Android 15+ draws edge-to-edge and ignores the bar colours: what shows behind the transparent bars is the
            // window background, so it must be the screen's own black (also in the phone's Light mode)
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(CigramSupport.BG));
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController wc = getWindow().getInsetsController();
                if (wc != null) wc.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
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
        refreshSuggest();
        bootstrap();
        askNotifPermission();
        AdsNotifier.cancelChatNotification(this);
    }

    @Override protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        if (cameraPath != null) b.putString("cam", cameraPath);
    }

    private static volatile boolean VISIBLE;

    /** True while the private conversation is on screen (a push for it is then pointless: the message is already shown). */
    static boolean isVisible() { return VISIBLE; }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        VISIBLE = true;
        AdsDevice.ensureRegistered(this);        // 19.0: the Worker learns this phone's push token
        if (ws == null) {
            ws = new AdsWs(this, "/chat/ws", new AdsWs.Listener() {
                @Override public void onOpen() { setBanner(null); fetchNew(); flushPending(); loadPresence(); }
                @Override public void onMessage(JSONObject m) { onEvent(m); }
                @Override public void onClosed(boolean authFailed) {
                    if (authFailed) setSessionBanner();    // 18.3: only after one silent renewal failed or none is possible
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
        VISIBLE = false;
        if (ws != null) ws.stop();
        h.removeCallbacks(pollRun);
        stopRecording(true);
        AdsVoice.stop();
        persist();
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (pendingVoice != null && pendingVoice.file != null) pendingVoice.file.delete();
        AdsVoice.setListener(null);
        h.removeCallbacksAndMessages(null);
        CigramEmoji.trimMemory();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ بناء الواجهة

    private void build() {
        FrameLayout shell = new FrameLayout(this);
        shell.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        shell.setBackgroundColor(CigramSupport.BG);
        LinearLayout col = AdsUi.vbox(this);
        shell.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // 18.2 header (physical order, the app has no RTL layout support): [⋯] [name / status ...] [picture] [back]
        CigramSupport.Developer dev = CigramSupport.developer(this);
        LinearLayout top = AdsUi.hbox(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(AdsUi.dp(this, 4), AdsUi.dp(this, 6), AdsUi.dp(this, 4), AdsUi.dp(this, 6));
        top.setBackgroundColor(CigramSupport.BAR);
        top.addView(iconBtn(AdsIcons.MORE, "خيارات", new View.OnClickListener() {
            @Override public void onClick(View v) { showMenu(); }
        }), new LinearLayout.LayoutParams(AdsUi.dp(this, 48), AdsUi.dp(this, 48)));
        LinearLayout titles = AdsUi.vbox(this);
        titles.setGravity(Gravity.RIGHT);
        devName = AdsUi.text(this, dev.name, 17f, CigramSupport.TEXT, true);
        devName.setGravity(Gravity.RIGHT);
        titles.addView(devName, new LinearLayout.LayoutParams(-1, -2));
        subtitle = AdsUi.text(this, "", 12f, CigramSupport.MUTED, false);
        subtitle.setGravity(Gravity.RIGHT);
        subtitle.setVisibility(View.GONE);
        titles.addView(subtitle, AdsUi.lp(-1, -2, 0, AdsUi.dp(this, 1), 0, 0));
        TextView brand = AdsUi.text(this, CigramSupport.TITLE + " · " + CigramSupport.SUBTITLE_EN, 10.5f, CigramSupport.FAINT, false);
        brand.setGravity(Gravity.RIGHT);
        titles.addView(brand, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2);
        tl.weight = 1f;
        tl.setMargins(AdsUi.dp(this, 4), 0, AdsUi.dp(this, 10), 0);
        top.addView(titles, tl);
        devAvatar = new AdsViews.Round(this, AdsUi.dp(this, 21));
        devAvatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        devAvatar.setContentDescription("صورة المطوّر");
        setDevAvatar(dev);
        top.addView(devAvatar, new LinearLayout.LayoutParams(AdsUi.dp(this, 42), AdsUi.dp(this, 42)));
        top.addView(iconBtn(AdsIcons.BACK, "رجوع", new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        }), new LinearLayout.LayoutParams(AdsUi.dp(this, 48), AdsUi.dp(this, 48)));
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));
        View topLine = new View(this);
        topLine.setBackgroundColor(CigramSupport.HAIRLINE);
        col.addView(topLine, new LinearLayout.LayoutParams(-1, Math.max(1, AdsUi.dp(this, 0.5f))));

        banner = AdsUi.text(this, "", 13f, CigramSupport.TEXT, true);
        banner.setGravity(Gravity.CENTER);
        banner.setBackgroundColor(0xFF161618);
        banner.setPadding(AdsUi.dp(this, 12), AdsUi.dp(this, 6), AdsUi.dp(this, 12), AdsUi.dp(this, 6));
        banner.setVisibility(View.GONE);
        col.addView(banner, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout mid = new FrameLayout(this);
        // 18.2: the developer logo as a faint watermark behind the conversation
        android.widget.ImageView mark = new android.widget.ImageView(this);
        int markRes = getResources().getIdentifier("cigram_mark", "drawable", getPackageName());
        if (markRes == 0) markRes = getResources().getIdentifier("cigram_logo", "drawable", getPackageName());
        if (markRes != 0) mark.setImageResource(markRes);
        mark.setAlpha(0.05f);
        mark.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int markSize = (int) (getResources().getDisplayMetrics().widthPixels * 0.55f);
        mid.addView(mark, new FrameLayout.LayoutParams(markSize, markSize, Gravity.CENTER));
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
        fab.setBackground(CigramSupport.round(CigramSupport.MINE, 24, this));
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
        replyBar.setBackgroundColor(0xFF0D0D0F);
        replyText = AdsUi.text(this, "", 13f, CigramSupport.MUTED, false);
        replyText.setGravity(Gravity.RIGHT);
        replyText.setMaxLines(1);
        LinearLayout.LayoutParams rtl = new LinearLayout.LayoutParams(0, -2);
        rtl.weight = 1f;
        ((LinearLayout) replyBar).addView(replyText, rtl);
        ((LinearLayout) replyBar).addView(iconBtn(AdsIcons.CLOSE, "إلغاء الرد", new View.OnClickListener() {
            @Override public void onClick(View v) { setReply(0, null); }
        }), new LinearLayout.LayoutParams(AdsUi.dp(this, 44), AdsUi.dp(this, 44)));
        replyBar.setVisibility(View.GONE);
        col.addView(replyBar, new LinearLayout.LayoutParams(-1, -2));

        // 19.2: suggestion chips (ready sentences; a tap writes one into the field, nothing is sent by itself)
        suggest = new CigramChatSuggest(this);
        suggest.setBackgroundColor(CigramSupport.BG);
        suggest.setListener(new CigramChatSuggest.Listener() {
            @Override public void onPick(String text) {
                if (banned) return;
                input.setText(text);
                input.setSelection(input.getText().length());
                input.requestFocus();
            }
        });
        col.addView(suggest, new LinearLayout.LayoutParams(-1, -2));

        // الكتابة
        composer = AdsUi.hbox(this);
        composer.setPadding(AdsUi.dp(this, 6), AdsUi.dp(this, 6), AdsUi.dp(this, 6), AdsUi.dp(this, 8));
        composer.setBackgroundColor(CigramSupport.BAR);
        composer.setGravity(Gravity.BOTTOM);
        final View emojiBtn = iconBtn(AdsIcons.SMILE, "الرموز التعبيرية", new View.OnClickListener() {
            @Override public void onClick(View v) { toggleEmoji(); }
        });
        // 2026 RTL composer, physical order left → right: [send / mic][video][photo][message ...]
        // (the app has no RTL support switched on, so the order is built by hand). Photo and video open the system
        // photo picker directly; a long press on the photo button opens the full sheet (camera, documents).
        attachBtn = iconBtn(AdsIcons.IMAGE, "إرسال صورة", new View.OnClickListener() {
            @Override public void onClick(View v) { pick(0); }
        });
        attachBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showAttachSheet(); return true; }
        });
        final View videoBtn = iconBtn(AdsIcons.VIDEO, "إرسال فيديو (حتى 1 غيغابايت)", new View.OnClickListener() {
            @Override public void onClick(View v) { pick(1); }
        });

        FrameLayout inputBox = new FrameLayout(this);
        input = new EditText(this);
        CigramUI.styleInput(input, "\u200Fاكتب رسالتك هنا");
        input.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        input.setTextColor(CigramSupport.TEXT);
        input.setHintTextColor(CigramSupport.FAINT);
        input.setBackground(CigramSupport.round(0xFF141416, 24, this));
        input.setPadding(AdsUi.dp(this, 16), AdsUi.dp(this, 10), AdsUi.dp(this, 16), AdsUi.dp(this, 10));
        input.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (emojiPanel != null) emojiPanel.setVisibility(View.GONE); }
        });
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) { if (has && emojiPanel != null) emojiPanel.setVisibility(View.GONE); }
        });
        input.setMaxLines(5);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinHeight(AdsUi.dp(this, 48));
        inputBox.addView(input, new FrameLayout.LayoutParams(-1, -2));
        recBar = AdsUi.hbox(this);
        recBar.setBackground(CigramSupport.round(0xFF141416, 24, this));
        ((LinearLayout) recBar).setPadding(AdsUi.dp(this, 16), 0, AdsUi.dp(this, 16), 0);
        TextView dot = AdsUi.text(this, "●", 16f, CigramUI.DANGER, true);
        ((LinearLayout) recBar).addView(dot);
        timerText = AdsUi.text(this, "0:00", 16f, CigramSupport.TEXT, true);
        ((LinearLayout) recBar).addView(timerText, AdsUi.lp(-2, -2, AdsUi.dp(this, 8), 0, AdsUi.dp(this, 12), 0));
        recHint = AdsUi.text(this, "اسحب للإلغاء", 13f, CigramSupport.MUTED, false);
        ((LinearLayout) recBar).addView(recHint);
        recBar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (recLocked) { stopRecording(true); AdsUi.toast(AdsChatActivity.this, "أُلغي التسجيل."); } }
        });
        recBar.setVisibility(View.GONE);
        inputBox.addView(recBar, new FrameLayout.LayoutParams(-1, AdsUi.dp(this, 48)));
        inputBox.addView(buildVoicePreview(), new FrameLayout.LayoutParams(-1, AdsUi.dp(this, 48)));
        final LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, -2);
        il.weight = 1f;
        il.setMargins(AdsUi.dp(this, 4), 0, AdsUi.dp(this, 4), 0);

        // زر إرسال/مايك
        FrameLayout act = new FrameLayout(this);
        act.setBackground(CigramSupport.round(0xFFFFFFFF, 24, this));
        sendBtn = new AdsIcons.Icon(this, AdsIcons.SEND, 0xFF000000);
        micBtn = new AdsIcons.Icon(this, AdsIcons.MIC, 0xFF000000);
        act.addView(sendBtn, new FrameLayout.LayoutParams(AdsUi.dp(this, 24), AdsUi.dp(this, 24), Gravity.CENTER));
        act.addView(micBtn, new FrameLayout.LayoutParams(AdsUi.dp(this, 24), AdsUi.dp(this, 24), Gravity.CENTER));
        act.setContentDescription("إرسال أو تسجيل رسالة صوتية");
        sendBtn.setVisibility(View.GONE);
        composer.addView(act, new LinearLayout.LayoutParams(AdsUi.dp(this, 48), AdsUi.dp(this, 48)));
        composer.addView(videoBtn, new LinearLayout.LayoutParams(AdsUi.dp(this, 42), AdsUi.dp(this, 48)));
        composer.addView(attachBtn, new LinearLayout.LayoutParams(AdsUi.dp(this, 42), AdsUi.dp(this, 48)));
        composer.addView(emojiBtn, new LinearLayout.LayoutParams(AdsUi.dp(this, 42), AdsUi.dp(this, 48)));
        composer.addView(inputBox, il);
        act.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return onActionTouch(e); }
        });
        View compLine = new View(this);
        compLine.setBackgroundColor(CigramSupport.HAIRLINE);
        col.addView(compLine, new LinearLayout.LayoutParams(-1, Math.max(1, AdsUi.dp(this, 0.5f))));
        col.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        // 19.2: the app's own emoji keyboard (all groups, drawn from the atlas: the same artwork on every phone)
        emojiPanel = new CigramEmojiPanel(this, 0xFF0A0A0B, 0xFFE21B14, new CigramEmojiPanel.Listener() {
            @Override public void onEmoji(String e) { CigramEmoji.insert(input, e); }
            @Override public void onBackspace() { CigramEmoji.backspace(input); }
        });
        emojiPanel.setVisibility(View.GONE);
        col.addView(emojiPanel, new LinearLayout.LayoutParams(-1, AdsUi.dp(this, 286)));
        CigramEmoji.attach(input);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                boolean has = s.toString().trim().length() > 0;
                sendBtn.setVisibility(has ? View.VISIBLE : View.GONE);
                micBtn.setVisibility(has ? View.GONE : View.VISIBLE);
                if (has) notifyTyping();
                refreshSuggest();
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
        f.addView(new AdsIcons.Icon(this, icon, CigramSupport.TEXT), new FrameLayout.LayoutParams(AdsUi.dp(this, 24), AdsUi.dp(this, 24), Gravity.CENTER));
        f.setOnClickListener(click);
        return f;
    }

    private void setBanner(String s) {
        if (destroyed) return;
        banner.setOnClickListener(null);
        banner.setClickable(false);
        if (s == null) banner.setVisibility(View.GONE);
        else { banner.setText(s); banner.setVisibility(View.VISIBLE); }
    }

    /**
     * The account session is really over (the server refused the account even after a silent renewal, or no credentials
     * are stored to renew with). Accurate text + a way out; the saved conversation and the unsent messages stay.
     */
    private void setSessionBanner() {
        if (destroyed) return;
        banner.setText("انتهت جلسة حسابك ولا يمكن تجديدها تلقائياً — اضغط هنا لتسجيل الدخول. محادثتك ورسائلك غير المرسلة محفوظة وستُرسل بعد الدخول.");
        banner.setVisibility(View.VISIBLE);
        banner.setClickable(true);
        banner.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openLogin(); }
        });
    }

    /** Only on the user's own tap: the expired local session marker is removed and the login page opens. Chat data is untouched. */
    private void openLogin() {
        try {
            getSharedPreferences("auth_session", MODE_PRIVATE).edit()
                    .putBoolean("logged_in", false).remove("token").remove("expires_at").apply();
            Intent i = new Intent(this, Class.forName("com.Cigram.vid.AccountActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Throwable t) {
            android.util.Log.w("AdsChat", "open login failed", t);
        }
    }

    private void updateSubtitle() {
        if (destroyed) return;
        String s;
        int color = CigramSupport.MUTED;
        CigramSupport.Developer dev = CigramSupport.developer(this);
        if (devName != null) devName.setText(dev.name);
        if (banned) { s = "المحادثة مقيّدة"; }
        else if (adminTyping) { s = "يكتب الآن…"; color = CigramSupport.TEXT; }
        else if (adminOnline) { s = "● متصل الآن"; color = CigramSupport.ONLINE; }
        else { s = presenceText.length() > 0 ? presenceText : CigramSupport.lastSeen(dev.lastActive); }
        subtitle.setText(s == null ? "" : s);
        subtitle.setTextColor(color);
        subtitle.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
        boolean can = !banned;
        input.setEnabled(can);
        input.setHint(banned ? "تم تقييد المحادثة من قبل الإدارة" : "\u200Fاكتب رسالتك هنا");
        attachBtn.setEnabled(can);
    }

    private String presenceText = "";

    private void loadPresence() {
        CigramAds.get(this, "/chat/presence", false, new CigramAds.Callback() {
            @Override public void onResult(CigramAds.Result r) {
                if (destroyed || !r.ok) return;
                String st = r.json.optString("state");
                adminOnline = "online".equals(st);
                JSONObject dv = r.json.optJSONObject("developer");
                if (dv != null) { CigramSupport.learn(AdsChatActivity.this, dv); setDevAvatar(CigramSupport.developer(AdsChatActivity.this)); }
                long la = r.json.optLong("last_active", dv == null ? 0 : dv.optLong("last_active"));
                String seen = CigramSupport.lastSeen(la);
                presenceText = "off_hours".equals(st) ? "خارج ساعات العمل — سنرد عند عودتنا" : "offline".equals(st) ? (seen.length() > 0 ? seen : "غير متصل — اترك رسالتك") : "";
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
        // a pending copy whose confirmed copy is already stored (same client id) is a leftover of an older build: drop it
        for (int k = items.size() - 1; k >= 0; k--) {
            Msg p = items.get(k);
            if (p.seq != 0 || p.clientId.length() == 0) continue;
            for (Msg q : items) if (q.seq > 0 && p.clientId.equals(q.clientId)) { items.remove(k); break; }
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
                    if (m.clientId.length() > 0) o.put("client_id", m.clientId);
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

    private int bootstrapRetryMs = 3000;
    private final Runnable bootstrapRetryRun = new Runnable() {
        @Override public void run() { if (!destroyed && active) bootstrap(); }
    };

    private void scheduleBootstrapRetry() {
        h.removeCallbacks(bootstrapRetryRun);
        h.postDelayed(bootstrapRetryRun, bootstrapRetryMs);
        bootstrapRetryMs = Math.min(60000, bootstrapRetryMs * 2);
    }

    private void bootstrap() {
        CigramAds.get(this, "/chat/thread", true, new CigramAds.Callback() {
            @Override public void onResult(CigramAds.Result r) {
                if (destroyed) return;
                if (!r.ok) {
                    if ("consent_required".equals(r.error)) {
                        askConsent(null);
                        loaded = true;
                        return;
                    }
                    if (r.authExpired) setSessionBanner();
                    else {
                        setBanner(r.offline ? "لا يوجد اتصال — يعرض آخر محادثة محفوظة" : r.message);
                        // 19.0: a temporary failure (offline, busy / unreachable server, the chat Worker unable to verify the account)
                        // is retried with back-off until it works; the saved conversation stays on screen meanwhile
                        if (r.offline || r.authTransient || r.code == 0 || r.code == 408 || r.code == 429 || r.code >= 500) scheduleBootstrapRetry();
                    }
                    loaded = true;
                    return;
                }
                JSONObject t = r.json.optJSONObject("thread");
                banned = t != null && t.optBoolean("banned");
                bootstrapRetryMs = 3000;
                setBanner(null);
                updateSubtitle();
                loadPresence();
                CigramAds.get(AdsChatActivity.this, "/chat/messages?limit=40", true, new CigramAds.Callback() {
                    @Override public void onResult(CigramAds.Result r2) {
                        if (destroyed) return;
                        loaded = true;
                        if (!r2.ok) { if (r2.authExpired) setSessionBanner(); else setBanner(r2.message); return; }
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

    /** Removes the not-yet-confirmed local copy (seq 0) of the message that was sent with this client id. */
    private void removePending(String cid) {
        for (int k = items.size() - 1; k >= 0; k--) {
            Msg p = items.get(k);
            if (p.seq == 0 && p.clientId.length() > 0 && p.clientId.equals(cid)) items.remove(k);
        }
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
            // 19.1: the local pending copy of a message is ALWAYS dropped when its server copy shows up, wherever that copy came from.
            // Before, the WebSocket event (no HTTP answer yet) added the server copy first, then the HTTP answer found it by seq and
            // `continue`d: the pending bubble (clock icon) stayed next to the real one, and was restored again at every start.
            if (cid.length() > 0 && "user".equals(n.from)) removePending(cid);
            int idx = -1;
            for (int k = 0; k < items.size(); k++) if (items.get(k).seq == n.seq && n.seq > 0) { idx = k; break; }
            if (idx >= 0) { items.set(idx, n); continue; }
            int pos = 0;
            while (pos < items.size() && (items.get(pos).seq > 0 && items.get(pos).seq < n.seq)) pos++;
            items.add(pos, n);
            if (firstShown > 0 && n.seq < firstShown) addedBefore++;
            if ("admin".equals(n.from) && n.seq > lastSeq) incoming = true;
            if ("admin".equals(n.from) && n.dev != null) CigramSupport.learn(this, n.dev);
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
                        retryDelayMs = 2000;
                        applyServer(a, false);
                        scrollToEnd();
                    } else {
                        m.state = 2;
                        m.failMsg = r.message;
                        // 18.2: no connection / busy server → stays queued (never shown as sent) and is retried with backoff
                        boolean transientFail = r.offline || r.code == 0 || r.code == 408 || r.code == 429 || r.code >= 500 || r.authTransient;
                        if (r.authExpired) {
                            // 18.3: queued, never dropped and never retried in a loop: it is sent when the account signs in again
                            m.failMsg = "";
                            setSessionBanner();
                        } else if (transientFail && !"banned".equals(r.error)) {
                            m.failMsg = "";
                            h.removeCallbacks(retryRun);
                            h.postDelayed(retryRun, retryDelayMs);
                            retryDelayMs = Math.min(60000, retryDelayMs * 2);
                        }
                        if ("banned".equals(r.error)) { banned = true; updateSubtitle(); }
                        if ("consent_required".equals(r.error)) { m.failMsg = ""; askConsent(m); }
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
            row.addView(AdsUi.text(this, labels[i], 16f, CigramSupport.TEXT, false), AdsUi.lp(-2, -2, AdsUi.dp(this, 12), 0, 0, 0));
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
            if (which == 0 || which == 1) {
                // photos / videos: the system photo picker (no storage permission at all) where available
                startActivityForResult(CigramPermissions.pickVisualMedia(which == 0 ? "image/*" : "video/*"), REQ_PICK + which * 100);
                return;
            }
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
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
        m.localPath = p.file != null ? p.file.getAbsolutePath() : (p.source != null ? p.source.toString() : null);
        m.prepared = p;
        m.uploadDur = durMs;
        m.uploadWave = wave;
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
        runUpload(m, p, durMs, wave);
    }

    /** Uploads (or, after a failure, resumes) the file of {@code m}; progress and the result update the bubble. */
    private void runUpload(final Msg m, AdsMedia.Prepared p, int durMs, int[] wave) {
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

    /**
     * The round button: with text it sends. Without text it records, in the two ways people expect:
     * hold → talk → release (slide sideways to cancel), or ONE TAP → hands-free recording until the button is tapped
     * again (the bar shows how to cancel). Either way the recording goes to the preview first (listen / delete / send).
     */
    private boolean onActionTouch(MotionEvent e) {
        int a = e.getActionMasked();
        boolean hasText = input.getText().toString().trim().length() > 0;
        if (hasText) {
            if (a == MotionEvent.ACTION_UP) doSend();
            return true;
        }
        if (banned) return true;
        if (recLocked) {                                   // hands-free recording: this tap finishes it
            if (a == MotionEvent.ACTION_UP) finishRecording(false);
            return true;
        }
        if (a == MotionEvent.ACTION_DOWN) {
            if (pendingVoice != null) return true;         // a recording is waiting in the preview
            if (!CigramPermissions.ensure(this, "android.permission.RECORD_AUDIO", REQ_PERM_AUDIO,
                    "يحتاج Cigram إلى الميكروفون لتسجيل رسالتك الصوتية فقط، ولا يستمع إليك في أي وقت آخر.")) {
                return true;
            }
            recDownX = e.getRawX();
            recCancelled = false;
            recorder = new AdsVoice.Recorder();
            if (!recorder.start(this, new AdsVoice.Recorder.Tick() {
                @Override public void onTick(int ms, int amp) { timerText.setText(AdsMedia.durLabel(ms)); }
            })) {
                String why = recorder.lastError;
                recorder = null;
                AdsUi.toast(this, why.length() > 0 ? why : "تعذر بدء التسجيل.");
                return true;
            }
            if (emojiPanel != null) emojiPanel.setVisibility(View.GONE);
            input.setVisibility(View.INVISIBLE);
            recBar.setVisibility(View.VISIBLE);
            recHint.setText("اسحب للإلغاء");
            timerText.setText("0:00");
            refreshSuggest();
            try { getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) { }
            return true;
        }
        if (recorder == null) return true;
        if (a == MotionEvent.ACTION_MOVE) {
            boolean cancel = Math.abs(e.getRawX() - recDownX) > AdsUi.dp(this, 90);
            if (cancel != recCancelled) {
                recCancelled = cancel;
                timerText.setTextColor(cancel ? CigramUI.DANGER : CigramSupport.TEXT);
            }
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            boolean cancel = recCancelled || a == MotionEvent.ACTION_CANCEL;
            if (!cancel && recorder.elapsed() < 600) {     // a tap, not a hold: keep recording hands-free
                recLocked = true;
                recHint.setText("اضغط الزر للإنهاء · اضغط هنا للإلغاء");
                micBtn.setVisibility(View.GONE);
                sendBtn.setVisibility(View.VISIBLE);
                return true;
            }
            finishRecording(cancel);
        }
        return true;
    }

    private void finishRecording(boolean cancel) {
        if (recorder == null) return;
        int dur = recorder.elapsed();
        AdsVoice.Recorder.Result r = recorder.stop(cancel || dur < 1000);
        recorder = null;
        endRecordingUi();
        if (r != null) {
            showVoicePreview(r);   // 18.2: listen, delete or send — nothing leaves the phone before «إرسال»
        } else if (!cancel && dur < 1000) {
            AdsUi.toast(this, "التسجيل قصير جداً.");
        }
        refreshSuggest();
    }

    private void endRecordingUi() {
        recLocked = false;
        if (recBar != null) recBar.setVisibility(View.GONE);
        if (input != null) input.setVisibility(View.VISIBLE);
        if (timerText != null) timerText.setTextColor(CigramSupport.TEXT);
        if (input != null && sendBtn != null && micBtn != null) {
            boolean has = input.getText().toString().trim().length() > 0;
            sendBtn.setVisibility(has ? View.VISIBLE : View.GONE);
            micBtn.setVisibility(has ? View.GONE : View.VISIBLE);
        }
        try { getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); } catch (Throwable ignored) { }
    }

    private void stopRecording(boolean cancel) {
        if (recorder != null) {
            recorder.stop(cancel);
            recorder = null;
            endRecordingUi();
            refreshSuggest();
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
                boolean uploaded = m.media != null && m.media.optString("id").length() > 0;
                if (m.localPath != null && !uploaded && m.prepared != null) {
                    // 2026: resume the same upload session; only the missing parts are sent again
                    m.state = 1;
                    m.failMsg = "";
                    m.progress = 0;
                    m.cancel = new AtomicBoolean(false);
                    adapter.notifyDataSetChanged();
                    runUpload(m, m.prepared, m.uploadDur, m.uploadWave);
                } else if (m.localPath != null && !uploaded) {
                    items.remove(m); adapter.notifyDataSetChanged(); AdsUi.toast(AdsChatActivity.this, "أعد اختيار الملف لإرساله.");
                }
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
            TextView t = AdsUi.text(this, labels.get(i), 16f, CigramSupport.TEXT, false);
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

    /** 18.2: 24-hour clock in the device's time zone ("19:45"); the server keeps UTC epoch milliseconds. */
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.US);
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

    // ------------------------------------------------------------------ 18.2 Private Support helpers

    private void setDevAvatar(CigramSupport.Developer d) {
        if (devAvatar == null) return;
        int res = getResources().getIdentifier("cigram_logo", "drawable", getPackageName());
        if (res != 0) devAvatar.setImageResource(res);
        if (d.avatar.length() > 0) AdsMedia.loadInto(this, devAvatar, d.avatar, 128, false);
    }

    private void showMenu() {
        final CharSequence[] items = {"حملاتي الإعلانية", "إعدادات الإشعارات"};
        new android.app.AlertDialog.Builder(this).setItems(items, new android.content.DialogInterface.OnClickListener() {
            @Override public void onClick(android.content.DialogInterface d, int w) {
                try {
                    if (w == 0) startActivity(new Intent(AdsChatActivity.this, MyAdsActivity.class));
                    else {
                        Intent i = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
                        i.putExtra("android.provider.extra.APP_PACKAGE", getPackageName());
                        startActivity(i);
                    }
                } catch (Throwable ignored) { }
            }
        }).show();
    }

    /**
     * The current server still asks for the policy consent before the first message. The viewer sees the policies the
     * server publishes and agrees explicitly — nothing is accepted on their behalf. A message waiting for it is sent after.
     */
    private boolean consentAsked;

    private void askConsent(final Msg waiting) {
        if (consentAsked || destroyed) return;
        consentAsked = true;
        CigramAds.loadConfig(this, false, new CigramAds.Callback() {
            @Override public void onResult(CigramAds.Result r) {
                if (destroyed) return;
                JSONObject cfg = r.json == null ? null : r.json.optJSONObject("settings");
                final int version = cfg == null ? 1 : Math.max(1, cfg.optInt("policies_version", 1));
                StringBuilder txt = new StringBuilder("قبل أول رسالة يطلب الخادم الموافقة على سياسة التواصل (النسخة " + version + "):\n\n");
                org.json.JSONArray pol = cfg == null ? null : cfg.optJSONArray("policies");
                for (int i = 0; pol != null && i < pol.length(); i++) {
                    JSONObject p = pol.optJSONObject(i);
                    String line = p != null ? (p.optString("title") + (p.optString("body").length() > 0 ? ": " + p.optString("body") : "")) : pol.optString(i);
                    if (line.trim().length() > 0) txt.append("• ").append(line.trim()).append("\n");
                }
                if (pol == null || pol.length() == 0) txt.append("• تُستخدم المحادثة للتواصل الخاص بينك وبين مطوّر Cigram فقط.\n");
                new android.app.AlertDialog.Builder(AdsChatActivity.this).setTitle("سياسة التواصل").setMessage(txt.toString())
                        .setNegativeButton("ليس الآن", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) { consentAsked = false; }
                        })
                        .setPositiveButton("أوافق", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                CigramAds.saveLocalConsent(AdsChatActivity.this, version);
                                CigramAds.pushConsent(AdsChatActivity.this, version, new CigramAds.Callback() {
                                    @Override public void onResult(CigramAds.Result r2) {
                                        consentAsked = false;
                                        if (destroyed) return;
                                        if (!r2.ok) { AdsUi.toast(AdsChatActivity.this, r2.message); return; }
                                        if (waiting != null) dispatch(waiting); else flushPending();
                                        bootstrapAgainIfNeeded();
                                    }
                                });
                            }
                        }).show();
            }
        });
    }

    private void bootstrapAgainIfNeeded() {
        if (lastSeq == 0) bootstrap();
    }

    private void toggleEmoji() {
        if (emojiPanel == null || banned) return;
        boolean show = emojiPanel.getVisibility() != View.VISIBLE;
        emojiPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            emojiPanel.refresh();
            input.clearFocus();
            try {
                android.view.inputmethod.InputMethodManager im = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
            } catch (Throwable ignored) { }
        }
    }

    @Override public void onBackPressed() {
        if (emojiPanel != null && emojiPanel.getVisibility() == View.VISIBLE) { emojiPanel.setVisibility(View.GONE); return; }
        if (recLocked) { stopRecording(true); return; }
        super.onBackPressed();
    }

    /** Which chips are offered follows the conversation; the row hides while the user types, records or is restricted. */
    private void refreshSuggest() {
        if (suggest == null) return;
        boolean typing = input != null && input.getText().toString().trim().length() > 0;
        boolean busy = recorder != null || pendingVoice != null || banned;
        if (typing || busy) { suggest.setVisibility(View.GONE); return; }
        Msg last = null, lastDev = null;
        int real = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            Msg m = items.get(i);
            if ("system".equals(m.type)) continue;
            real++;
            if (last == null) last = m;
            if (lastDev == null && !"user".equals(m.from)) lastDev = m;
            if (real > 40) break;
        }
        boolean pendingQuote = lastDev != null && "quote".equals(lastDev.type) && lastDev.quote != null && "pending".equals(lastDev.quote.optString("state", "pending"));
        suggest.setItems(CigramChatSuggest.forUser(real == 0, pendingQuote ? "quote" : "", last != null && "user".equals(last.from) && !pendingQuote));
        suggest.setVisibility(View.VISIBLE);
    }

    private View buildVoicePreview() {
        voicePreview = AdsUi.hbox(this);
        voicePreview.setGravity(Gravity.CENTER_VERTICAL);
        voicePreview.setBackground(CigramSupport.round(0xFF141416, 24, this));
        voicePreview.setPadding(AdsUi.dp(this, 6), 0, AdsUi.dp(this, 6), 0);
        View del = iconBtn(AdsIcons.TRASH, "حذف التسجيل", new View.OnClickListener() {
            @Override public void onClick(View v) { discardVoice(); }
        });
        voicePreview.addView(del, new LinearLayout.LayoutParams(AdsUi.dp(this, 40), AdsUi.dp(this, 40)));
        voicePreviewTime = AdsUi.text(this, "0:00", 15f, CigramSupport.TEXT, true);
        voicePreviewTime.setGravity(Gravity.CENTER);
        voicePreview.addView(voicePreviewTime, new LinearLayout.LayoutParams(0, -2, 1f));
        FrameLayout play = new FrameLayout(this);
        play.setContentDescription("الاستماع قبل الإرسال");
        voicePreviewIcon = new AdsIcons.Icon(this, AdsIcons.PLAY, CigramSupport.TEXT);
        play.addView(voicePreviewIcon, new FrameLayout.LayoutParams(AdsUi.dp(this, 22), AdsUi.dp(this, 22), Gravity.CENTER));
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (pendingVoice == null) return;
                AdsVoice.setListener(new AdsVoice.PlayListener() {
                    @Override public void onState(String pid, boolean playing, int pos, int total) {
                        if (destroyed || voicePreviewTime == null) return;
                        voicePreviewTime.setText(playing ? AdsMedia.durLabel(pos) + " / " + AdsMedia.durLabel(pendingVoice == null ? total : pendingVoice.durMs) : AdsMedia.durLabel(pendingVoice == null ? 0 : pendingVoice.durMs));
                    }
                });
                AdsVoice.toggle("voice-preview", pendingVoice.file.getAbsolutePath());
            }
        });
        voicePreview.addView(play, new LinearLayout.LayoutParams(AdsUi.dp(this, 44), AdsUi.dp(this, 44)));
        TextView send = AdsUi.text(this, "إرسال", 14f, 0xFF000000, true);
        send.setGravity(Gravity.CENTER);
        send.setBackground(CigramSupport.round(0xFFFFFFFF, 18, this));
        send.setPadding(AdsUi.dp(this, 14), 0, AdsUi.dp(this, 14), 0);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendVoice(); }
        });
        voicePreview.addView(send, new LinearLayout.LayoutParams(-2, AdsUi.dp(this, 36)));
        voicePreview.setVisibility(View.GONE);
        return voicePreview;
    }

    private void showVoicePreview(AdsVoice.Recorder.Result r) {
        pendingVoice = r;
        voicePreviewTime.setText(AdsMedia.durLabel(r.durMs));
        input.setVisibility(View.INVISIBLE);
        voicePreview.setVisibility(View.VISIBLE);
    }

    private void discardVoice() {
        AdsVoice.stop();
        if (pendingVoice != null && pendingVoice.file != null) pendingVoice.file.delete();
        pendingVoice = null;
        voicePreview.setVisibility(View.GONE);
        input.setVisibility(View.VISIBLE);
        refreshSuggest();
    }

    private void sendVoice() {
        AdsVoice.stop();
        AdsVoice.Recorder.Result r = pendingVoice;
        pendingVoice = null;
        voicePreview.setVisibility(View.GONE);
        input.setVisibility(View.VISIBLE);
        refreshSuggest();
        if (r == null) return;
        AdsMedia.Prepared p = new AdsMedia.Prepared();
        p.file = r.file; p.kind = "voice"; p.name = r.name; p.size = r.file.length();
        startUpload(p, "voice", r.durMs, r.wave);
    }

    private final class ChatAdapter extends BaseAdapter {
        @Override public void notifyDataSetChanged() {
            super.notifyDataSetChanged();
            refreshSuggest();          // the chips follow the conversation (same chips = nothing is rebuilt)
        }
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
                TextView d = AdsUi.text(AdsChatActivity.this, dayLabel(m.ts), 12f, CigramSupport.FAINT, false);
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
            TextView t = AdsUi.text(c, m.text, 13f, CigramSupport.MUTED, false);
            t.setGravity(Gravity.CENTER);
            t.setBackground(CigramSupport.round(0xFF0E0E10, CigramSupport.HAIRLINE, 14, c));
            t.setPadding(AdsUi.dp(c, 12), AdsUi.dp(c, 6), AdsUi.dp(c, 12), AdsUi.dp(c, 6));
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
            l.gravity = Gravity.CENTER_HORIZONTAL;
            root.addView(t, l);
            return;
        }
        boolean mine = "user".equals(m.from);
        // 18.2: the developer's public name above the first message of each run (several developers may answer)
        if (!mine) {
            int pos = items.indexOf(m);
            Msg prev = pos > 0 ? items.get(pos - 1) : null;
            String who = m.dev != null && m.dev.optString("name").length() > 0 ? m.dev.optString("name") : CigramSupport.developer(c).name;
            String prevWho = prev == null || !"admin".equals(prev.from) ? null : prev.dev != null && prev.dev.optString("name").length() > 0 ? prev.dev.optString("name") : CigramSupport.developer(c).name;
            if (prevWho == null || !prevWho.equals(who) || "system".equals(prev.type)) {
                TextView wl = AdsUi.text(c, who, 11.5f, CigramSupport.FAINT, true);
                LinearLayout.LayoutParams wll = new LinearLayout.LayoutParams(-2, -2);
                wll.gravity = Gravity.LEFT;
                wll.setMargins(AdsUi.dp(c, 6), AdsUi.dp(c, 6), 0, AdsUi.dp(c, 2));
                root.addView(wl, wll);
            }
        }
        LinearLayout bubble = AdsUi.vbox(c);
        bubble.setPadding(AdsUi.dp(c, 12), AdsUi.dp(c, 8), AdsUi.dp(c, 12), AdsUi.dp(c, 6));
        bubble.setBackground(CigramSupport.round(mine ? CigramSupport.MINE : CigramSupport.THEIRS, mine ? 0 : CigramSupport.THEIRS_STROKE, 18, c));
        int tx = CigramSupport.TEXT;
        int sub = CigramSupport.MUTED;

        if (m.reply != null && !m.deleted) {
            LinearLayout rp = AdsUi.vbox(c);
            rp.setPadding(AdsUi.dp(c, 8), AdsUi.dp(c, 4), AdsUi.dp(c, 8), AdsUi.dp(c, 4));
            rp.setBackground(CigramSupport.round(mine ? 0x33000000 : 0x22000000, 8, c));
            rp.addView(AdsUi.text(c, "رد على: " + ("user".equals(m.reply.optString("from")) ? "أنت" : CigramSupport.developer(c).name), 11f, sub, true));
            TextView pv = AdsUi.text(c, "", 13f, tx, false);
            CigramEmoji.bind(pv, m.reply.optString("preview"));
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
            boolean jumbo = m.media == null && m.reply == null && CigramEmoji.isJumbo(c, m.text);
            TextView t = AdsUi.text(c, "", jumbo ? 34f : 15f, tx, false);
            CigramEmoji.bind(t, m.text);
            t.setLineSpacing(0f, 1.15f);
            // Arabic right-aligned, English left-aligned and never reversed (paragraph direction = first strong letter)
            t.setGravity(CigramSupport.startsRtl(m.text) ? Gravity.RIGHT : Gravity.LEFT);
            t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            t.setAutoLinkMask(android.text.util.Linkify.WEB_URLS | android.text.util.Linkify.PHONE_NUMBERS);
            t.setLinkTextColor(0xFFFFFFFF);
            t.setTextIsSelectable(false);
            bubble.addView(t, AdsUi.lp(-2, -2, 0, ("image".equals(m.type) || "video".equals(m.type)) ? AdsUi.dp(c, 6) : 0, 0, 0));
        }
        // الوقت والحالة
        LinearLayout foot = AdsUi.hbox(c);
        foot.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        String status = "";
        if (m.state == 2) status = m.failMsg.length() > 0 ? m.failMsg : "بانتظار الاتصال — سيُرسل تلقائياً";
        boolean waiting = m.state == 2 && m.failMsg.length() == 0;   // queued: not an error, it will be sent by itself
        TextView tm = AdsUi.text(c, status.length() > 0 ? status : timeFmt.format(new Date(m.ts)), 11f, m.state == 2 && !waiting ? CigramSupport.ERROR : sub, false);
        foot.addView(tm);
        if (mine) {
            int icon = AdsIcons.CHECK;
            int col = sub;
            if (m.state == 1 || (m.state == 2 && m.failMsg.length() == 0)) { icon = AdsIcons.CLOCK; col = CigramSupport.FAINT; }
            else if (m.state == 2) { icon = AdsIcons.ALERT; col = CigramSupport.ERROR; }
            else if (m.read > 0) { icon = AdsIcons.DOUBLE_CHECK; col = 0xFFFFFFFF; }
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
        // 18.2 fix: the bubble used to be added to a throw-away FrameLayout first, so wrapMax() threw
        // "The specified child already has a parent" and the screen crashed as soon as any message was drawn.
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

    /**
     * 19.2: a compact preview that shows the WHOLE picture in its own shape (portrait stays portrait, wide stays wide)
     * with rounded corners; only an extreme panorama / strip is cropped. A tap opens it inside the app
     * ({@link CigramMediaViewer}): zoom + save for a photo, the player with seek / sound for a video.
     */
    private View imageView(final Msg m, final boolean video) {
        int w = m.media.optInt("w"), hh = m.media.optInt("h");
        float ratio = (w > 0 && hh > 0) ? (float) w / hh : (video ? 16f / 9f : 4f / 3f);
        boolean extreme = ratio > 2.2f || ratio < 0.5f;
        ratio = Math.max(0.5f, Math.min(2.2f, ratio));
        int maxW = Math.min(maxBubble - AdsUi.dp(this, 24), AdsUi.dp(this, 232)), maxH = AdsUi.dp(this, 264);
        int bw = maxW, bh = Math.round(bw / ratio);
        if (bh > maxH) { bh = maxH; bw = Math.round(bh * ratio); }
        bw = Math.max(bw, AdsUi.dp(this, 132));
        FrameLayout f = new FrameLayout(this);
        AdsViews.Round iv = new AdsViews.Round(this, AdsUi.dp(this, 14));
        iv.setBackgroundColor(0xFF0C0C0E);
        iv.setScaleType(extreme || w <= 0 ? android.widget.ImageView.ScaleType.CENTER_CROP : android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setContentDescription(video ? "فيديو — اضغط للتشغيل" : "صورة — اضغط للعرض");
        f.addView(iv, new FrameLayout.LayoutParams(bw, bh));
        if (m.localPath != null && !video && new File(m.localPath).isFile()) {
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(m.localPath, o);
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / sample > 1280) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap b = BitmapFactory.decodeFile(m.localPath, o2);
                if (b != null) iv.setImageBitmap(b);
            } catch (Throwable ignored) { }
        } else if (m.media.optString("url").length() > 0) {
            AdsMedia.loadInto(this, iv, m.media.optString("url"), 640, video);
        }
        if (video) {
            FrameLayout badge = new FrameLayout(this);
            badge.setBackground(CigramSupport.round(0x8C000000, 0x59FFFFFF, 26, this));
            badge.addView(new AdsIcons.Icon(this, AdsIcons.PLAY, 0xFFFFFFFF), new FrameLayout.LayoutParams(AdsUi.dp(this, 24), AdsUi.dp(this, 24), Gravity.CENTER));
            f.addView(badge, new FrameLayout.LayoutParams(AdsUi.dp(this, 52), AdsUi.dp(this, 52), Gravity.CENTER));
            if (m.media.optInt("dur") > 0) {
                TextView d = AdsUi.text(this, AdsMedia.durLabel(m.media.optInt("dur")), 11f, 0xFFFFFFFF, true);
                d.setBackground(CigramSupport.round(0xB3000000, 9, this));
                d.setPadding(AdsUi.dp(this, 7), AdsUi.dp(this, 2), AdsUi.dp(this, 7), AdsUi.dp(this, 2));
                FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
                dl.setMargins(AdsUi.dp(this, 7), 0, 0, AdsUi.dp(this, 7));
                f.addView(d, dl);
            }
        }
        if (m.progress >= 0 && m.state == 1) f.addView(uploadOverlay(m), new FrameLayout.LayoutParams(bw, bh));
        final String url = m.media.optString("url");
        f.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String localFile = m.localPath != null && new File(m.localPath).isFile() ? m.localPath : null;
                if (url.length() == 0 && localFile == null) return;
                if (m.state == 1 && m.progress >= 0) return;         // still uploading: the overlay owns the tap
                AdsVoice.stop();
                CigramMediaViewer.open(AdsChatActivity.this, url, video, m.media.optString("name"), localFile);
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
        play.setBackground(CigramSupport.round(mine ? 0x33FFFFFF : CigramUI.TEAL, 24, this));
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
}
