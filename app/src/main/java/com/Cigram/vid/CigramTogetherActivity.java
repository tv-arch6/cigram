package com.Cigram.vid;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.UUID;

/**
 * الصفحة الأولى والانتظار في "شاهد مع صديقك":
 *   hub      : بوستر + اسم المحتوى + «إنشاء غرفة» / «الانضمام برمز»
 *   owner    : غرفة الانتظار (رمز + مشاركة + صورة الصديق + قبول/رفض + ابدأ المشاهدة)
 *   guest    : إدخال الرمز ← بانتظار موافقة المالك ← بانتظار البدء
 * عند بدء المالك للمشاهدة (حالة الغرفة تصبح loading/playing) يفتح كلا الطرفين CigramTogetherPlayerActivity.
 *
 * رابط الدعوة cigram://together?code=XXXXXXXX يفتح هذه الشاشة بوضع الانضمام مع تعبئة الرمز.
 */
public class CigramTogetherActivity extends Activity implements CigramTogetherSession.Listener {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CigramTogetherSession session = CigramTogetherSession.get();

    private LinearLayout content;
    private JSONObject roomContent;             // المحتوى المختار عند الإنشاء
    private String stage = "hub";
    private String createKey = UUID.randomUUID().toString();   // نفس المفتاح عند إعادة المحاولة = لا غرفة مكررة
    private boolean busy;
    private boolean playerOpened;
    private boolean joinSheetShowing;
    private int pollTicks;
    private String presetCode = "";
    private TextView guestStatus;
    private View guestSlot;
    private TextView startBtn;
    private LinearLayout guestHolder;

    private final Runnable poll = new Runnable() {
        @Override public void run() { pollPending(); }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        buildShell();
        try { CigramEpisodeAds.preload(this); } catch (Throwable ignored) { }   // إعلان الفاصل جاهز قبل دخول المشغل
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    private void handleIntent(Intent i) {
        try {
            Uri data = i == null ? null : i.getData();
            if (data != null && "cigram".equals(data.getScheme()) && "together".equals(data.getHost())) {
                presetCode = clean(data.getQueryParameter("code"));
                if (!requireLogin()) return;
                showJoin();
                return;
            }
            String raw = i == null ? null : i.getStringExtra("content");
            if (raw != null && raw.length() > 0) roomContent = new JSONObject(raw);
            String mode = i == null ? "" : i.getStringExtra("mode");
            if (session.active() && session.room() != null && "waiting".equals(mode)) { resumeExisting(); return; }
            showHub();
        } catch (Throwable t) {
            android.util.Log.w("CigramTogetherActivity", "handleIntent", t);
            showHub();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        session.register(this);
    }

    @Override protected void onStop() {
        session.unregister(this);
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if ("owner".equals(stage) && session.active()) {
            confirm("إلغاء الغرفة؟", "إذا خرجت ستُغلق الغرفة ويُلغى رمز الدعوة.", "إلغاء الغرفة", new Runnable() {
                @Override public void run() { closeRoom(); }
            });
            return;
        }
        if (("guest_wait".equals(stage) || "guest_pending".equals(stage)) && session.active()) {
            confirm("مغادرة الغرفة؟", "راح تطلع من الغرفة.", "مغادرة", new Runnable() {
                @Override public void run() { leaveRoom(); }
            });
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ shell

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF00121A);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(CigramUI.dp(this, 8), CigramUI.dp(this, 6), CigramUI.dp(this, 14), CigramUI.dp(this, 6));
        TextView back = CigramUI.label(this, "‹", 30f, CigramUI.TEXT, true);
        back.setGravity(Gravity.CENTER);
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        });
        bar.addView(back, new LinearLayout.LayoutParams(CigramUI.dp(this, 44), CigramUI.dp(this, 44)));
        TextView title = CigramUI.label(this, "مشاهدة الأصدقاء", 18f, CigramUI.TEXT, true);
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        content.setPadding(CigramUI.dp(this, 18), CigramUI.dp(this, 8), CigramUI.dp(this, 18), CigramUI.dp(this, 24));
        sv.addView(content, new ViewGroup.LayoutParams(-1, -2));
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    private void clear() { content.removeAllViews(); guestStatus = null; guestSlot = null; startBtn = null; guestHolder = null; }

    private TextView button(String text, int color, View.OnClickListener l) {
        TextView t = CigramUI.label(this, text, 16f, CigramUI.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(CigramUI.round(color, 26, this));
        t.setMinHeight(CigramUI.dp(this, 52));
        t.setClickable(true);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = CigramUI.dp(this, 10);
        t.setLayoutParams(lp);
        return t;
    }

    private void contentHeader() {
        JSONObject c = roomContent != null ? roomContent : session.content();
        if (c == null) return;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(CigramUI.round(CigramUI.BG_SOFT, CigramUI.STROKE, 16, this));
        row.setPadding(CigramUI.dp(this, 12), CigramUI.dp(this, 12), CigramUI.dp(this, 12), CigramUI.dp(this, 12));
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(CigramUI.round(CigramUI.BG, 10, this));
        String url = c.optString("poster", "");
        if (url.startsWith("http")) { try { CigramStage1InitProvider.cigramLoadPoster(this, url, poster); } catch (Throwable ignored) { } }
        row.addView(poster, new LinearLayout.LayoutParams(CigramUI.dp(this, 64), CigramUI.dp(this, 92)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = CigramUI.label(this, c.optString("type").equals("episode") ? c.optString("series_title", c.optString("title")) : c.optString("title"), 17f, CigramUI.TEXT, true);
        t.setGravity(Gravity.RIGHT);
        texts.addView(t, new LinearLayout.LayoutParams(-1, -2));
        if ("episode".equals(c.optString("type"))) {
            TextView s = CigramUI.label(this, "الموسم " + c.optInt("season", 1) + " — الحلقة " + c.optInt("episode", 0), 13f, CigramUI.MUTED, false);
            s.setGravity(Gravity.RIGHT);
            texts.addView(s, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.setMarginStart(CigramUI.dp(this, 12));
        row.addView(texts, tl);
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView note(String text, int color) {
        TextView t = CigramUI.label(this, text, 13.5f, color, false);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0f, 1.2f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = CigramUI.dp(this, 12);
        t.setLayoutParams(lp);
        return t;
    }

    // ------------------------------------------------------------------ screens

    private void showHub() {
        stage = "hub";
        clear();
        contentHeader();
        content.addView(note("أنشئ غرفة خاصة وادعُ صديقك، أو انضم بالرمز الذي وصلك.", CigramUI.MUTED));
        if (roomContent != null) {
            content.addView(button("إنشاء غرفة", CigramUI.RED, new View.OnClickListener() {
                @Override public void onClick(View v) { createRoom(); }
            }));
        }
        content.addView(button("الانضمام برمز", 0xFF1F4553, new View.OnClickListener() {
            @Override public void onClick(View v) { if (requireLogin()) showJoin(); }
        }));
    }

    private boolean requireLogin() {
        if (CigramAds.isLoggedIn(this)) return true;
        CigramUI.sheet(this).icon(CigramUI.ICON_USER, CigramUI.TEAL).title("سجّل الدخول أولاً")
                .message("غرف المشاهدة مرتبطة بحسابك. سجّل الدخول ثم ارجع لهذه الصفحة.")
                .primary("تسجيل الدخول", CigramUI.TEAL, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) {
                        try {
                            Intent i = new Intent(CigramTogetherActivity.this, Class.forName("com.Cigram.vid.AccountActivity"));
                            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            startActivity(i);
                        } catch (Throwable e) { toast("افتح صفحة الحساب وسجّل الدخول."); }
                        return false;
                    }
                }).secondary("إلغاء", null).show();
        return false;
    }

    private void createRoom() {
        if (busy || roomContent == null) return;
        if (!requireLogin()) return;
        busy = true;
        clear();
        contentHeader();
        content.addView(note("جاري إنشاء الغرفة…", CigramUI.MUTED));
        try {
            CigramTogetherApi.post(this, "/rooms", new JSONObject().put("content", roomContent), createKey, new CigramTogetherApi.Callback() {
                @Override public void onResult(CigramTogetherApi.Result r) {
                    busy = false;
                    if (isFinishing()) return;
                    if (r.ok) {
                        session.adopt(CigramTogetherActivity.this, r.json.optJSONObject("room"));
                        session.connect(CigramTogetherActivity.this);
                        showOwnerWaiting(r.json.optString("code", ""));
                    } else {
                        showError(r, new Runnable() { @Override public void run() { createRoom(); } });
                    }
                }
            });
        } catch (Throwable t) { busy = false; showHub(); }
    }

    private void showError(CigramTogetherApi.Result r, final Runnable retry) {
        clear();
        contentHeader();
        String msg = r.authRejected() ? "انتهت صلاحية جلسة حسابك فعلاً. سجّل الدخول من جديد ثم أعد المحاولة." : r.message;
        content.addView(note(msg, CigramUI.AMBER));
        if (r.authRejected()) {
            content.addView(button("تسجيل الدخول", CigramUI.TEAL, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        Intent i = new Intent(CigramTogetherActivity.this, Class.forName("com.Cigram.vid.AccountActivity"));
                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        startActivity(i);
                    } catch (Throwable ignored) { }
                }
            }));
        } else if (retry != null) {
            content.addView(button("أعد المحاولة", CigramUI.RED, new View.OnClickListener() {
                @Override public void onClick(View v) { retry.run(); }
            }));
        }
        content.addView(button("رجوع", 0xFF1F4553, new View.OnClickListener() {
            @Override public void onClick(View v) { showHub(); }
        }));
    }

    // ---- owner

    private void showOwnerWaiting(final String code) {
        stage = "owner";
        clear();
        contentHeader();
        TextView lab = note("رمز الدعوة", CigramUI.MUTED);
        content.addView(lab);
        final String c = code.length() > 0 ? code : (session.room() == null ? "" : session.room().optString("code", ""));
        TextView codeView = CigramUI.label(this, c, 34f, CigramUI.TEXT, true);
        codeView.setGravity(Gravity.CENTER);
        codeView.setTextDirection(View.TEXT_DIRECTION_LTR);
        // v4.4: the code is Latin letters/digits; a monospace face keeps 0/O and 1/I apart and
        // the 8 characters evenly spaced. Long-press selects it as a fallback to «نسخ الرمز».
        codeView.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        codeView.setTextIsSelectable(true);
        codeView.setSingleLine(true);
        codeView.setLetterSpacing(0.18f);
        codeView.setBackground(CigramUI.round(CigramUI.BG_SOFT, CigramUI.STROKE, 16, this));
        codeView.setPadding(0, CigramUI.dp(this, 14), 0, CigramUI.dp(this, 14));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = CigramUI.dp(this, 6);
        content.addView(codeView, cl);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView copy = button("نسخ الرمز", 0xFF1F4553, new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("cigram-room", c)); toast("نُسخ الرمز"); } catch (Throwable ignored) { }
            }
        });
        TextView share = button("مشاركة الدعوة", CigramUI.TEAL, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent s = new Intent(Intent.ACTION_SEND);
                s.setType("text/plain");
                // v10.62: https link (clickable in WhatsApp/Telegram) once the Worker v2.4 is live, cigram:// before
                String text;
                try { text = CigramLinks.roomShareText(CigramTogetherActivity.this, c); }
                catch (Throwable e) { text = "تعال نشاهد سوه على سيكرام!\nرمز الغرفة: " + c + "\nأو افتح الرابط: cigram://together?code=" + c; }
                s.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(s, "شارك الدعوة"));
            }
        });
        LinearLayout.LayoutParams a1 = new LinearLayout.LayoutParams(0, -2, 1f);
        a1.setMarginEnd(CigramUI.dp(this, 6));
        LinearLayout.LayoutParams a2 = new LinearLayout.LayoutParams(0, -2, 1f);
        a2.setMarginStart(CigramUI.dp(this, 6));
        row.addView(copy, a1);
        row.addView(share, a2);
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));

        guestHolder = new LinearLayout(this);
        guestHolder.setOrientation(LinearLayout.HORIZONTAL);
        guestHolder.setGravity(Gravity.CENTER_VERTICAL);
        guestHolder.setBackground(CigramUI.round(CigramUI.BG_SOFT, CigramUI.STROKE, 16, this));
        guestHolder.setPadding(CigramUI.dp(this, 14), CigramUI.dp(this, 12), CigramUI.dp(this, 14), CigramUI.dp(this, 12));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
        gl.topMargin = CigramUI.dp(this, 16);
        content.addView(guestHolder, gl);
        renderGuestSlot();

        startBtn = button("ابدأ المشاهدة", CigramUI.RED, new View.OnClickListener() {
            @Override public void onClick(View v) { startWatching(); }
        });
        content.addView(startBtn);
        content.addView(button("إلغاء الغرفة", 0xFF1F4553, new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        }));
        refreshStartButton();
    }

    private void renderGuestSlot() {
        if (guestHolder == null) return;
        guestHolder.removeAllViews();
        JSONObject room = session.room();
        JSONObject g = room == null ? null : room.optJSONObject("guest");
        JSONObject me = room == null ? null : room.optJSONObject("owner");
        guestHolder.addView(CigramTogether.avatarView(this, me, 44));
        TextView meName = CigramUI.label(this, me == null ? "" : me.optString("name") + " (أنت)", 14f, CigramUI.TEXT, true);
        LinearLayout.LayoutParams n1 = new LinearLayout.LayoutParams(0, -2, 1f);
        n1.setMarginStart(CigramUI.dp(this, 10));
        guestHolder.addView(meName, n1);
        if (g != null) {
            TextView gn = CigramUI.label(this, g.optString("name"), 14f, CigramUI.TEXT, true);
            LinearLayout.LayoutParams n2 = new LinearLayout.LayoutParams(-2, -2);
            n2.setMarginEnd(CigramUI.dp(this, 10));
            guestHolder.addView(gn, n2);
            guestHolder.addView(CigramTogether.avatarView(this, g, 44));
        } else {
            guestStatus = CigramUI.label(this, "بانتظار صديقك…", 14f, CigramUI.AMBER, false);
            guestHolder.addView(guestStatus);
        }
    }

    private void refreshStartButton() {
        if (startBtn == null) return;
        JSONObject room = session.room();
        boolean ready = room != null && room.optJSONObject("guest") != null && session.isConnected();
        startBtn.setAlpha(ready ? 1f : 0.45f);
        startBtn.setEnabled(ready);
        startBtn.setText(ready ? "ابدأ المشاهدة" : (room != null && room.optJSONObject("guest") != null ? "جاري الاتصال…" : "ابدأ المشاهدة (بانتظار الصديق)"));
    }

    private void startWatching() {
        if (busy) return;
        busy = true;
        CigramTogetherApi.post(this, "/rooms/" + session.roomId() + "/start", new JSONObject(), null, new CigramTogetherApi.Callback() {
            @Override public void onResult(CigramTogetherApi.Result r) {
                busy = false;
                if (isFinishing()) return;
                if (!r.ok) { toast(r.message); return; }
                if (r.json.optJSONObject("room") != null) session.adopt(CigramTogetherActivity.this, r.json.optJSONObject("room"));
                openPlayer();
            }
        });
    }

    private void closeRoom() {
        final String id = session.roomId();
        CigramTogetherApi.post(this, "/rooms/" + id + "/close", new JSONObject(), null, new CigramTogetherApi.Callback() {
            @Override public void onResult(CigramTogetherApi.Result r) { }
        });
        session.reset();
        finish();
    }

    private void leaveRoom() {
        final String id = session.roomId();
        if (id.length() > 0) CigramTogetherApi.post(this, "/rooms/" + id + "/leave", new JSONObject(), null, null);
        session.reset();
        main.removeCallbacks(poll);
        finish();
    }

    // ---- guest

    private void showJoin() {
        stage = "join";
        clear();
        content.addView(note("اكتب رمز الدعوة (8 أحرف) الذي وصلك من صديقك.", CigramUI.MUTED));
        final EditText input = new EditText(this);
        CigramUI.styleInput(input, "رمز الدعوة");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setGravity(Gravity.CENTER);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setTextSize(24f);
        // v4.4: same monospace face as the code shown to the owner. The hint stays in sima
        // because a hint uses the field typeface: only switch once the field has text.
        final android.graphics.Typeface hintFace = input.getTypeface();
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                android.graphics.Typeface want = s.length() == 0 ? hintFace
                        : android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE,
                                android.graphics.Typeface.BOLD);
                if (input.getTypeface() != want) input.setTypeface(want);
            }
        });
        input.setText(presetCode);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, CigramUI.dp(this, 60));
        il.topMargin = CigramUI.dp(this, 14);
        content.addView(input, il);
        content.addView(button("انضمام", CigramUI.RED, new View.OnClickListener() {
            @Override public void onClick(View v) { joinWith(clean(input.getText().toString())); }
        }));
    }

    private static String clean(String s) {
        return s == null ? "" : s.toUpperCase(java.util.Locale.US).replaceAll("[^A-Z0-9]", "");
    }

    private void joinWith(String code) {
        if (busy) return;
        if (code.length() != 8) { toast("الرمز لازم يكون 8 أحرف."); return; }
        if (!requireLogin()) return;
        busy = true;
        clear();
        content.addView(note("جاري الانضمام…", CigramUI.MUTED));
        try {
            CigramTogetherApi.post(this, "/rooms/join", new JSONObject().put("code", code), null, new CigramTogetherApi.Callback() {
                @Override public void onResult(CigramTogetherApi.Result r) {
                    busy = false;
                    if (isFinishing()) return;
                    if (!r.ok) {
                        showError(r, new Runnable() { @Override public void run() { showJoin(); } });
                        return;
                    }
                    JSONObject room = r.json.optJSONObject("room");
                    session.adopt(CigramTogetherActivity.this, room);
                    String st = r.json.optString("status", "");
                    if ("member".equals(st)) { session.connect(CigramTogetherActivity.this); showGuestWaitingStart(); }
                    else if ("owner".equals(st)) { session.connect(CigramTogetherActivity.this); showOwnerWaiting(""); }
                    else { showPendingGuest(); }
                }
            });
        } catch (Throwable t) { busy = false; showJoin(); }
    }

    private void showPendingGuest() {
        stage = "guest_pending";
        clear();
        roomContent = session.room() == null ? null : session.room().optJSONObject("content");
        contentHeader();
        content.addView(note("أُرسل طلب الانضمام.\nبانتظار موافقة صاحب الغرفة…", CigramUI.AMBER));
        pollTicks = 0;
        main.removeCallbacks(poll);
        main.postDelayed(poll, 2500L);
    }

    /** الضيف لا يملك WebSocket قبل القبول، فنستعلم عن حالته بخفة (كل 3 ثوانٍ، حتى 5 دقائق). */
    private void pollPending() {
        if (!"guest_pending".equals(stage) || isFinishing()) return;
        if (++pollTicks > 100) { showError(failed("انتهت مهلة انتظار الموافقة."), null); return; }
        CigramTogetherApi.get(this, "/rooms/" + session.roomId(), new CigramTogetherApi.Callback() {
            @Override public void onResult(CigramTogetherApi.Result r) {
                if (isFinishing() || !"guest_pending".equals(stage)) return;
                if (r.ok) {
                    JSONObject room = r.json.optJSONObject("room");
                    String role = room == null ? "" : room.optString("role", "");
                    if ("guest".equals(role)) {
                        session.adopt(CigramTogetherActivity.this, room);
                        session.connect(CigramTogetherActivity.this);
                        showGuestWaitingStart();
                        return;
                    }
                    if ("none".equals(role)) {           // رُفض الطلب
                        session.reset();
                        showError(failed("رفض صاحب الغرفة طلب الانضمام."), null);
                        return;
                    }
                } else if (r.code == 410 || r.code == 404) {
                    session.reset();
                    showError(failed(r.code == 410 ? "أُغلقت الغرفة." : "الغرفة غير موجودة."), null);
                    return;
                }
                main.postDelayed(poll, 3000L);           // أخطاء الشبكة المؤقتة: نكمل المحاولة بصمت
            }
        });
    }

    private CigramTogetherApi.Result failed(String msg) {
        CigramTogetherApi.Result r = new CigramTogetherApi.Result();
        r.message = msg;
        return r;
    }

    private void showGuestWaitingStart() {
        stage = "guest_wait";
        clear();
        roomContent = session.content();
        contentHeader();
        guestHolder = new LinearLayout(this);
        guestHolder.setOrientation(LinearLayout.HORIZONTAL);
        guestHolder.setGravity(Gravity.CENTER_VERTICAL);
        guestHolder.setBackground(CigramUI.round(CigramUI.BG_SOFT, CigramUI.STROKE, 16, this));
        guestHolder.setPadding(CigramUI.dp(this, 14), CigramUI.dp(this, 12), CigramUI.dp(this, 14), CigramUI.dp(this, 12));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
        gl.topMargin = CigramUI.dp(this, 16);
        content.addView(guestHolder, gl);
        JSONObject room = session.room();
        JSONObject owner = room == null ? null : room.optJSONObject("owner");
        JSONObject me = null;
        guestHolder.addView(CigramTogether.avatarView(this, owner, 44));
        TextView on = CigramUI.label(this, owner == null ? "" : owner.optString("name") + " (صاحب الغرفة)", 14f, CigramUI.TEXT, true);
        LinearLayout.LayoutParams n1 = new LinearLayout.LayoutParams(0, -2, 1f);
        n1.setMarginStart(CigramUI.dp(this, 10));
        guestHolder.addView(on, n1);
        content.addView(note("تمت الموافقة. بانتظار أن يبدأ صاحب الغرفة المشاهدة…", CigramUI.GREEN));
        content.addView(button("مغادرة الغرفة", 0xFF1F4553, new View.OnClickListener() {
            @Override public void onClick(View v) { onBackPressed(); }
        }));
        if (room != null) maybeOpenPlayer(room);
    }

    private void resumeExisting() {
        if (session.isOwner()) showOwnerWaiting(""); else showGuestWaitingStart();
    }

    // ------------------------------------------------------------------ session events

    @Override public void onRoom(JSONObject room, String cause) {
        if (isFinishing()) return;
        if ("owner".equals(stage)) { renderGuestSlot(); refreshStartButton(); }
        maybeOpenPlayer(room);
    }

    private void maybeOpenPlayer(JSONObject room) {
        if (playerOpened || room == null) return;
        JSONObject pb = room.optJSONObject("playback");
        boolean started = "active".equals(room.optString("status")) && pb != null
                && (!"paused".equals(pb.optString("status")) || room.optInt("epoch", 0) > 0);
        if (started && (session.isOwner() || "guest_wait".equals(stage))) openPlayer();
    }

    private void openPlayer() {
        if (playerOpened) return;
        playerOpened = true;
        startActivity(new Intent(this, CigramTogetherPlayerActivity.class));
        finish();
    }

    @Override public void onChat(JSONObject message) { }

    @Override public void onEvent(String type, JSONObject e) {
        if (isFinishing()) return;
        if ("join.request".equals(type) && "owner".equals(stage)) showJoinRequest(e.optJSONObject("user"));
        else if ("room.closed".equals(type)) {
            session.reset();
            toast("أُغلقت الغرفة.");
            finish();
        } else if ("kicked".equals(type)) {
            session.reset();
            toast("أزالك صاحب الغرفة.");
            finish();
        }
    }

    @Override public void onConnection(boolean connected) {
        if ("owner".equals(stage)) refreshStartButton();
    }

    private void showJoinRequest(final JSONObject user) {
        if (user == null || joinSheetShowing) return;
        joinSheetShowing = true;
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.addView(CigramTogether.avatarView(this, user, 72));
        TextView name = CigramUI.label(this, user.optString("name") + "\nيريد ينضم لغرفتك", 15f, CigramUI.TEXT, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, CigramUI.dp(this, 10), 0, 0);
        v.addView(name, new LinearLayout.LayoutParams(-2, -2));
        CigramUI.Sheet s = CigramUI.sheet(this).icon(CigramUI.ICON_USER, CigramUI.TEAL).title("طلب انضمام").view(v)
                .primary("قبول", CigramUI.GREEN, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet sh) { respond(true); return false; }
                }).secondary("رفض", new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet sh) { respond(false); return false; }
                });
        s.dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) { joinSheetShowing = false; }
        });
        s.show();
    }

    private void respond(boolean accept) {
        try {
            CigramTogetherApi.post(this, "/rooms/" + session.roomId() + "/respond", new JSONObject().put("accept", accept), null, new CigramTogetherApi.Callback() {
                @Override public void onResult(CigramTogetherApi.Result r) {
                    if (r.ok && r.json.optJSONObject("room") != null) {
                        session.adopt(CigramTogetherActivity.this, r.json.optJSONObject("room"));
                        renderGuestSlot();
                        refreshStartButton();
                    } else if (!r.ok) toast(r.message);
                }
            });
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ helpers

    private void confirm(String title, String message, String yes, final Runnable onYes) {
        CigramUI.sheet(this).icon(CigramUI.ICON_WARN, CigramUI.AMBER).title(title).message(message)
                .danger(yes, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) { onYes.run(); return false; }
                }).secondary("رجوع", null).show();
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
}
