package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.text.CueGroup;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.CaptionStyleCompat;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.video.VideoSize;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.UUID;

/**
 * Cigram Together Player — مشغل المشاهدة المشتركة (v5.23).
 *
 * الشكل: الفيديو + شريط التقدم فقط. يسار الشاشة أيقونة محادثة بيضاء (بلا خلفية) عليها شارة حمراء للرسائل
 * الجديدة، ويمين الشاشة مقبض «|» يفتح «اللوحة الذكية» (إعدادات المشاهدة الخاصة بكل مشاهد + الحلقات + أدوات الغرفة).
 * الأيقونتان تختفيان فقط عند قفل الشاشة.
 *
 * - التحكم بالتشغيل والشريط لصاحب الغرفة فقط؛ الصديق يشاهد ويكتب.
 * - لا يعدّل حالة التشغيل محلياً: كل أمر يُرسل للخادم ولا يتغير شيء إلا بعد وصول الحالة المعتمدة منه.
 * - المحاذاة: الموضع المرجعي = position_ms + (ساعة الخادم - ref_ms). فارق صغير يُصحَّح بسرعة 0.95/1.05،
 *   وفارق كبير (> 2 ثانية) بـ seek واحد.
 * - الجودة والتخزين المؤقت يُضبطان حسب شبكة هذا الهاتف (نوعها، سرعتها، هل هي محدودة) — تُقرأ على الجهاز فقط ولا تُرسل لأي خادم.
 * - تقدّم المشاهدة وإعدادات المشاهدة تُحفظ لكل مستخدم على جهازه وليست مشتركة.
 * - أبعاد الشاشة: «متناسق» (الافتراضي، نسبة الفيديو الأصلية مع أشرطة سوداء عند الحاجة) / «ملء الشاشة» / «تكبير»،
 *   كما في المشغّل الرئيسي، وتُحفظ لكل مستخدم مع بقية إعدادات المشاهدة.
 */
public class CigramTogetherPlayerActivity extends Activity implements CigramTogetherSession.Listener {

    private static final String[] REACTIONS = {"like", "laugh", "wow", "clap", "sad"};
    private static final String[] REACTION_EMOJI = {"👍", "😂", "😮", "👏", "😢"};
    private static final String[] QUICK_EMOJI = {"😂", "❤️", "👍", "😮", "😢", "🔥", "👏", "😍", "🤣", "😱"};
    private static final int BUBBLE_BG = 0x33000000;          // خلفية شفافة للرسالة
    private static final int BUBBLE_STROKE = 0x40FFFFFF;      // حد خفيف حول الرسالة
    private static final int DIVIDER = 0x26FFFFFF;

    private static final String Q_AUTO = "auto", Q_HIGH = "high", Q_SAVER = "saver";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CigramTogetherSession session = CigramTogetherSession.get();

    // media
    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private SurfaceView surface;
    private AspectRatioFrameLayout videoFrame;   // يحمل السطح ويحافظ على نسبة الفيديو الأصلية
    private float videoRatio;                    // عرض/ارتفاع الفيديو الحالي (0 = غير معروف بعد)
    private SubtitleView subtitles;
    private JSONObject playable;
    private int loadedEpoch = -1;
    private int readySentEpoch = -1;
    private boolean readyAcked;
    private long lastReadyAttemptAt;
    private long waitStartedAt;
    private static final long WAIT_HINT_MS = 30000L;
    private static final long PREPARE_TIMEOUT_MS = 60000L;
    private boolean prepared;
    private boolean adDone;
    private boolean midAdShowing;
    private long nextMidAdAtMs = -1L;
    private boolean ended;
    private boolean localBuffering;
    private long lastProgressSaveAt;
    private float speed = 1f;

    // network (محلي فقط)
    private String netType = "";
    private int netKbps;
    private boolean netMetered;
    private int wifiLinkMbps;
    private int wifiRssi;
    private long lastNetCheckAt;

    // per-viewer settings
    private SharedPreferences prefs;
    private String quality = Q_AUTO;
    private int subSize = 1;                     // 0 صغير، 1 متوسط، 2 كبير
    private boolean subsOn = true;
    private boolean floatingOn = true;
    private int resizeMode = 0;                  // أبعاد الشاشة: 0 متناسق (طبيعي)، 1 ملء الشاشة، 2 تكبير
    private boolean focusMode;

    // ui
    private FrameLayout root;
    private View controls;
    private View center;
    private TextView playBtn, timeView, bufferView;
    private SeekBar seek;
    private LinearLayout floatBox, chatList;
    private ScrollView chatScroll;
    private View chatPanel;
    private EditText chatInput;
    private FrameLayout chatIconBox;
    private TextView badge;
    private View panelHandle;
    private FrameLayout smartPanel;
    private LinearLayout smartBody;
    private FrameLayout lockLayer;
    private TextView overlayText;
    private View overlay;
    private boolean controlsVisible = true;
    private boolean seeking;
    private boolean locked;
    private boolean chatOpen;
    private boolean panelOpen;
    private int unread;

    private final HashSet<String> shownRequests = new HashSet<String>();
    private boolean leaving;
    private final HashSet<Integer> historyLoaded = new HashSet<Integer>();

    private final Runnable hideControls = new Runnable() {
        @Override public void run() {
            if (!seeking && !chatOpen && !panelOpen && !locked) setControlsVisible(false);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { onTick(); } catch (Throwable t) { android.util.Log.w("CigramTogetherPlayer", "tick", t); }
            main.postDelayed(this, 1000L);
        }
    };
    private int lastPolledState = -1;
    private boolean errorShown;
    private CueGroup lastCues;
    private final Runnable pollPlayer = new Runnable() {
        @Override public void run() {
            if (isFinishing()) return;
            try {
                ExoPlayer p = player;
                if (p != null) {
                    if (p.getPlayerError() != null) {
                        if (!errorShown) {
                            errorShown = true;
                            android.util.Log.w("CigramTogetherPlayer", "player error", p.getPlayerError());
                            showBlockingError("الفيديو ما اشتغل، أعد المحاولة.");
                        }
                    } else {
                        errorShown = false;
                        int st = p.getPlaybackState();
                        if (st != lastPolledState) { lastPolledState = st; onPlayerState(st); }
                        try {
                            CueGroup cg = p.getCurrentCues();
                            if (cg != lastCues) { lastCues = cg; subtitles.setCues(cg.cues); }
                        } catch (Throwable ignored) { }
                        try { applyVideoRatio(p); } catch (Throwable ignored) { }
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("CigramTogetherPlayer", "poll", t);
            }
            main.postDelayed(this, 250L);
        }
    };
    private final Runnable startRunnable = new Runnable() {
        @Override public void run() { applyRoom(session.room(), "start"); }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!session.active()) { finish(); return; }
        try { setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE); } catch (Throwable ignored) { }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        immersive();
        loadPrefs();
        readNetwork();
        buildUi();
        session.register(this);
        session.connect(this);
        main.postDelayed(tick, 1000L);

        JSONObject room = session.room();
        if (room == null) { finish(); return; }
        showOverlay("جاري التجهيز…");
        adDone = true;
        applyRoom(session.room(), "open");
        tvRemote();   // v10.66: TV remote (TV only)
    }

    /** v10.66 (TV only): the remote runs the same actions as the on-screen buttons. */
    private void tvRemote() {
        try {
            if (!CigramTvMode.isTv(this)) return;
            CigramTvKeys.player(this, new CigramTvKeys.PlayerHost() {
                @Override public boolean controlsVisible() { return controlsVisible; }
                @Override public void showControls() { if (!locked) setControlsVisible(true); }
                @Override public void hideControls() { setControlsVisible(false); }
                @Override public boolean isPlaying() {
                    JSONObject room = session.room();
                    JSONObject pb = room == null ? null : room.optJSONObject("playback");
                    return pb != null && "playing".equals(pb.optString("status"));
                }
                @Override public void playPause() { togglePlay(); }
                @Override public void rewind() { skip(-10000L); }
                @Override public void forward() { skip(10000L); }
                @Override public void next() { }
                @Override public void previous() { }
                @Override public View focusTarget() { return playBtn; }
            });
        } catch (Throwable ignored) { }
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override protected void onStart() {
        super.onStart();
        session.register(this);
        if (adDone && session.room() != null) { session.sync(); applyRoom(session.room(), "resume"); }
    }

    @Override protected void onStop() {
        saveProgress(false);
        if (player != null) player.setPlayWhenReady(false);      // الخلفية: نوقف محلياً فقط، الغرفة تكمل
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        session.unregister(this);
        releasePlayer();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (locked) { toast("اضغط مطولاً على القفل لفتحه."); return; }
        if (panelOpen) { togglePanel(false); return; }
        if (chatOpen) { toggleChat(false); return; }
        confirmLeave();
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
    }

    /** التحكم بالتشغيل والشريط لصاحب الغرفة فقط. */
    private boolean iControl() { return session.isOwner(); }

    // ------------------------------------------------------------------ per-viewer settings

    private void loadPrefs() {
        prefs = getSharedPreferences("cigram_together_viewer", MODE_PRIVATE);
        String k = myUid();
        quality = prefs.getString(k + "|quality", Q_AUTO);
        subSize = prefs.getInt(k + "|sub_size", 1);
        subsOn = prefs.getBoolean(k + "|subs", true);
        floatingOn = prefs.getBoolean(k + "|floating", true);
        resizeMode = prefs.getInt(k + "|resize_mode", 0);
        if (resizeMode < 0 || resizeMode > 2) resizeMode = 0;
    }

    private void savePrefs() {
        try {
            String k = myUid();
            prefs.edit().putString(k + "|quality", quality).putInt(k + "|sub_size", subSize)
                    .putBoolean(k + "|subs", subsOn).putBoolean(k + "|floating", floatingOn)
                    .putInt(k + "|resize_mode", resizeMode).apply();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ network (يُقرأ على الجهاز فقط)

    private void readNetwork() {
        lastNetCheckAt = System.currentTimeMillis();
        String type = "none";
        int kbps = 0;
        boolean metered = true;
        int linkMbps = 0, rssi = 0;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= 23) {
                android.net.Network n = cm.getActiveNetwork();
                android.net.NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
                if (nc != null) {
                    if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) type = "wifi";
                    else if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) type = "ethernet";
                    else if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) type = "cellular";
                    else type = "other";
                    kbps = nc.getLinkDownstreamBandwidthKbps();
                    metered = !nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                }
            } else {
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni != null && ni.isConnected()) {
                    type = ni.getType() == ConnectivityManager.TYPE_WIFI ? "wifi" : ni.getType() == ConnectivityManager.TYPE_MOBILE ? "cellular" : "other";
                    metered = cm.isActiveNetworkMetered();
                }
            }
            if ("wifi".equals(type)) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                android.net.wifi.WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                if (wi != null) { linkMbps = Math.max(0, wi.getLinkSpeed()); rssi = wi.getRssi(); }
            }
        } catch (Throwable ignored) { }
        boolean changed = !type.equals(netType) || netMetered != metered;
        netType = type; netKbps = kbps; netMetered = metered; wifiLinkMbps = linkMbps; wifiRssi = rssi;
        if (changed) applyQuality();
    }

    /** تقدير محافظ لما يمكن تحميله فعلاً (bps). 0 = غير معروف. */
    private long usableBps() {
        long est = netKbps > 0 ? netKbps * 1000L : 0L;
        if (wifiLinkMbps > 0) {
            long wifi = wifiLinkMbps * 1000000L / 2L;        // سرعة الربط الفعلية ≈ نصف المعلنة
            est = est <= 0L ? wifi : Math.min(est, wifi);
        }
        if (wifiRssi < -80 && wifiRssi != 0) est = est / 2L;  // إشارة ضعيفة
        return est;
    }

    private String networkLabel() {
        String t = "wifi".equals(netType) ? "Wi‑Fi" : "cellular".equals(netType) ? "بيانات الهاتف" : "ethernet".equals(netType) ? "شبكة سلكية" : "none".equals(netType) ? "غير متصل" : "شبكة";
        StringBuilder sb = new StringBuilder(t);
        long bps = usableBps();
        if (bps > 0L) sb.append(" • ≈").append(Math.max(1L, bps / 1000000L)).append(" Mbps");
        if ("wifi".equals(netType) && wifiRssi != 0) sb.append(wifiRssi >= -60 ? " • إشارة قوية" : wifiRssi >= -75 ? " • إشارة متوسطة" : " • إشارة ضعيفة");
        sb.append(netMetered ? " • محدودة" : " • غير محدودة");
        return sb.toString();
    }

    private DefaultLoadControl buildLoadControl() {
        boolean fast = !netMetered && ("wifi".equals(netType) || "ethernet".equals(netType));
        int min, max;
        if (Q_SAVER.equals(quality)) { min = 15000; max = 40000; }
        else if (fast) { min = 30000; max = 120000; }       // شبكة جيدة: نخزّن أكثر لتجنب أي تقطيع
        else { min = 20000; max = 60000; }
        return new DefaultLoadControl.Builder().setBufferDurationsMs(min, max, 2000, 4000).build();
    }

    /** يطبّق اختيار الجودة لهذا المشاهد (يعمل على البث متعدد الجودات HLS؛ ملف MP4 واحد يبقى بدقته الأصلية). */
    private void applyQuality() {
        DefaultTrackSelector ts = trackSelector;
        if (ts == null) return;
        try {
            DefaultTrackSelector.Parameters.Builder pb = ts.buildUponParameters();
            if (Q_HIGH.equals(quality)) {
                pb.setForceHighestSupportedBitrate(true).setMaxVideoBitrate(Integer.MAX_VALUE).setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE);
            } else if (Q_SAVER.equals(quality)) {
                pb.setForceHighestSupportedBitrate(false).setMaxVideoBitrate(1200000).setMaxVideoSize(854, 480);
            } else {
                long bps = usableBps();
                int cap = bps <= 0L ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, Math.max(800000L, bps * 7L / 10L));
                pb.setForceHighestSupportedBitrate(false).setMaxVideoBitrate(cap).setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE);
            }
            ts.setParameters(pb);
        } catch (Throwable t) {
            android.util.Log.w("CigramTogetherPlayer", "quality", t);
        }
    }

    private String currentResolution() {
        try {
            Format f = player == null ? null : player.getVideoFormat();
            if (f != null && f.height > 0) return f.height + "p";
        } catch (Throwable ignored) { }
        return "—";
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);       // يسار = محادثة، يمين = اللوحة الذكية

        // الفيديو داخل إطار يحترم نسبته الأصلية (بدل تمديده على كل الشاشة). الترجمة والأزرار فوقه على مستوى الشاشة.
        videoFrame = new AspectRatioFrameLayout(this);
        videoFrame.setResizeMode(resizeModeValue());
        root.addView(videoFrame, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        surface = new SurfaceView(this);
        videoFrame.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        subtitles = new SubtitleView(this);
        styleSubtitles();
        root.addView(subtitles, new FrameLayout.LayoutParams(-1, -1));

        View touch = new View(this);
        touch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (panelOpen) { togglePanel(false); return; }
                setControlsVisible(!controlsVisible);
            }
        });
        root.addView(touch, new FrameLayout.LayoutParams(-1, -1));

        buildFloatBox();
        buildControls();
        buildChatIcon();
        buildPanelHandle();
        buildChatPanel();
        buildSmartPanel();
        buildOverlay();
        buildLockLayer();
        setContentView(root);

        // لوحة المفاتيح: نرفع لوحة المحادثة فقط، والفيديو لا يتحرك
        root.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                Rect r = new Rect();
                root.getWindowVisibleDisplayFrame(r);
                int kb = Math.max(0, root.getRootView().getHeight() - r.bottom);
                if (chatPanel != null) {
                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) chatPanel.getLayoutParams();
                    int want = kb > dp(120) ? kb : 0;
                    if (lp.bottomMargin != want) { lp.bottomMargin = want; chatPanel.setLayoutParams(lp); }
                }
            }
        });
    }

    private int dp(float v) { return CigramUI.dp(this, v); }

    private Typeface sima() { return CigramUI.font(this); }

    /** قيمة AspectRatioFrameLayout المقابلة لاختيار المستخدم: متناسق / ملء الشاشة / تكبير. */
    private int resizeModeValue() {
        return resizeMode == 1 ? AspectRatioFrameLayout.RESIZE_MODE_FILL
                : resizeMode == 2 ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : AspectRatioFrameLayout.RESIZE_MODE_FIT;
    }

    private void applyResizeMode() {
        try { if (videoFrame != null) videoFrame.setResizeMode(resizeModeValue()); } catch (Throwable ignored) { }
    }

    /** يقرأ أبعاد الفيديو الفعلية (بدون Listener) ويضبط نسبة الإطار؛ آخر نسبة معروفة تبقى بين الحلقات. */
    private void applyVideoRatio(ExoPlayer p) {
        VideoSize vs = p.getVideoSize();
        if (vs == null || vs.width <= 0 || vs.height <= 0) return;
        float r = (vs.width * vs.pixelWidthHeightRatio) / vs.height;
        if (r > 0f && r != videoRatio) {
            videoRatio = r;
            if (videoFrame != null) videoFrame.setAspectRatio(r);
        }
    }

    private void styleSubtitles() {
        try {
            subtitles.setStyle(new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, sima()));
            float f = subSize == 0 ? 0.85f : subSize == 2 ? 1.3f : 1.05f;
            subtitles.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * f);
            subtitles.setVisibility(subsOn ? View.VISIBLE : View.GONE);
        } catch (Throwable ignored) { }
    }

    private void buildControls() {
        FrameLayout c = new FrameLayout(this);
        controls = c;

        // ---- الوسط (صاحب الغرفة فقط)
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.CENTER);
        TextView back10 = roundBtn("−10", new Runnable() { @Override public void run() { skip(-10000L); } });
        playBtn = roundBtn("▶", new Runnable() { @Override public void run() { togglePlay(); } });
        TextView fwd10 = roundBtn("+10", new Runnable() { @Override public void run() { skip(10000L); } });
        mid.addView(back10, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(68), dp(68));
        pl.setMargins(dp(28), 0, dp(28), 0);
        mid.addView(playBtn, pl);
        mid.addView(fwd10, new LinearLayout.LayoutParams(dp(52), dp(52)));
        center = mid;
        mid.setVisibility(iControl() ? View.VISIBLE : View.GONE);
        c.addView(mid, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        bufferView = CigramUI.label(this, "جاري التحميل…", 12f, Color.WHITE, false);
        bufferView.setVisibility(View.GONE);
        FrameLayout.LayoutParams bl2 = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        bl2.topMargin = dp(64);
        c.addView(bufferView, bl2);

        // ---- شريط التقدم
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB3000000, 0x00000000});
        bottom.setBackground(shade);
        bottom.setPadding(dp(56), dp(18), dp(56), dp(10));
        seek = new SeekBar(this);
        seek.setMax(1000);
        try {
            seek.setProgressTintList(ColorStateList.valueOf(CigramUI.RED));
            seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x66FFFFFF));
            seek.setThumbTintList(ColorStateList.valueOf(CigramUI.RED));
        } catch (Throwable ignored) { }
        if (!iControl()) {                          // الصديق يشاهد فقط: الشريط للعرض
            seek.setEnabled(false);
            seek.setThumb(new ColorDrawable(Color.TRANSPARENT));
        }
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && player != null) timeView.setText(CigramTogetherSession.clock(p * Math.max(1L, player.getDuration()) / 1000L) + " / " + CigramTogetherSession.clock(Math.max(0L, player.getDuration())));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { seeking = true; main.removeCallbacks(hideControls); }
            @Override public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (iControl() && player != null && player.getDuration() > 0) sendControl("seek", s.getProgress() * player.getDuration() / 1000L);
                scheduleHide();
            }
        });
        bottom.addView(seek, new LinearLayout.LayoutParams(0, dp(32), 1f));
        timeView = CigramUI.label(this, "00:00:00 / 00:00:00", 12f, Color.WHITE, false);
        timeView.setTextDirection(View.TEXT_DIRECTION_LTR);
        LinearLayout.LayoutParams tvl = new LinearLayout.LayoutParams(-2, -2);
        tvl.leftMargin = dp(10);
        bottom.addView(timeView, tvl);
        c.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        root.addView(c, new FrameLayout.LayoutParams(-1, -1));
    }

    private TextView roundBtn(String text, final Runnable r) {
        TextView t = CigramUI.label(this, text, 18f, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(CigramUI.round(0x88000000, 40, this));
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { r.run(); scheduleHide(); }
        });
        return t;
    }

    /** يسار الشاشة: أيقونة محادثة بيضاء بلا خلفية + شارة حمراء لعدد الرسائل الجديدة. */
    private void buildChatIcon() {
        chatIconBox = new FrameLayout(this);
        ChatGlyph g = new ChatGlyph(this);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER);
        chatIconBox.addView(g, gl);
        badge = CigramUI.label(this, "", 10f, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setTextDirection(View.TEXT_DIRECTION_LTR);
        badge.setBackground(CigramUI.round(CigramUI.RED, 10, this));
        badge.setPadding(dp(4), 0, dp(4), 0);
        badge.setMinWidth(dp(18));
        badge.setVisibility(View.GONE);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, dp(18), Gravity.TOP | Gravity.RIGHT);
        chatIconBox.addView(badge, bl);
        chatIconBox.setClickable(true);
        chatIconBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleChat(!chatOpen); }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        lp.leftMargin = dp(10);
        root.addView(chatIconBox, lp);
    }

    /** يمين الشاشة: مقبض «|» يفتح اللوحة الذكية. */
    private void buildPanelHandle() {
        FrameLayout h = new FrameLayout(this);
        View bar = new View(this);
        bar.setBackground(CigramUI.round(Color.WHITE, 3, this));
        h.addView(bar, new FrameLayout.LayoutParams(dp(5), dp(42), Gravity.CENTER));
        h.setClickable(true);
        h.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePanel(!panelOpen); }
        });
        panelHandle = h;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(36), dp(72), Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        lp.rightMargin = dp(6);
        root.addView(h, lp);
    }

    private void buildFloatBox() {
        floatBox = new LinearLayout(this);
        floatBox.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(300), -2, Gravity.TOP | Gravity.LEFT);
        lp.topMargin = dp(20);
        lp.leftMargin = dp(66);                           // بجانب أيقونة المحادثة
        root.addView(floatBox, lp);
    }

    private void buildChatPanel() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xB3000000, 0x40000000}));
        p.setClickable(true);

        FrameLayout head = new FrameLayout(this);
        TextView close = CigramUI.label(this, "✕", 16f, Color.WHITE, false);
        close.setGravity(Gravity.CENTER);
        close.setClickable(true);
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { toggleChat(false); } });
        head.addView(close, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.RIGHT));
        p.addView(head, new LinearLayout.LayoutParams(-1, dp(40)));

        chatScroll = new ScrollView(this);
        chatScroll.setVerticalScrollBarEnabled(false);
        chatList = new LinearLayout(this);
        chatList.setOrientation(LinearLayout.VERTICAL);
        chatList.setPadding(dp(10), 0, dp(10), dp(6));
        chatScroll.addView(chatList, new ViewGroup.LayoutParams(-1, -2));
        p.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // إيموجي سريع
        android.widget.HorizontalScrollView es = new android.widget.HorizontalScrollView(this);
        es.setHorizontalScrollBarEnabled(false);
        LinearLayout emojis = new LinearLayout(this);
        emojis.setOrientation(LinearLayout.HORIZONTAL);
        emojis.setPadding(dp(8), 0, dp(8), 0);
        for (final String e : QUICK_EMOJI) {
            TextView t = new TextView(this);
            t.setText(e);
            t.setTextSize(20f);
            t.setGravity(Gravity.CENTER);
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    int at = Math.max(0, chatInput.getSelectionStart());
                    chatInput.getText().insert(at, e);
                }
            });
            emojis.addView(t, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        es.addView(emojis);
        p.addView(es, new LinearLayout.LayoutParams(-1, dp(40)));

        LinearLayout in = new LinearLayout(this);
        in.setOrientation(LinearLayout.HORIZONTAL);
        in.setGravity(Gravity.CENTER_VERTICAL);
        in.setPadding(dp(8), dp(4), dp(8), dp(10));
        chatInput = new EditText(this);
        chatInput.setHint("اكتب رسالة…");
        chatInput.setHintTextColor(0x99FFFFFF);
        chatInput.setTextColor(Color.WHITE);
        chatInput.setTypeface(sima());
        chatInput.setTextSize(14f);
        chatInput.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        chatInput.setBackground(CigramUI.round(BUBBLE_BG, BUBBLE_STROKE, 20, this));
        chatInput.setPadding(dp(14), 0, dp(14), 0);
        chatInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        chatInput.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        chatInput.setSingleLine(true);
        chatInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendChat(); return true; }
                return false;
            }
        });
        in.addView(chatInput, new LinearLayout.LayoutParams(0, dp(42), 1f));
        TextView send = CigramUI.label(this, "➤", 18f, Color.WHITE, true);
        send.setGravity(Gravity.CENTER);
        send.setTextDirection(View.TEXT_DIRECTION_LTR);
        send.setBackground(CigramUI.round(CigramUI.RED, 21, this));
        send.setClickable(true);
        send.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { sendChat(); } });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(42), dp(42));
        sl.leftMargin = dp(6);
        in.addView(send, sl);
        p.addView(in, new LinearLayout.LayoutParams(-1, -2));

        chatPanel = p;
        p.setVisibility(View.GONE);
        root.addView(p, new FrameLayout.LayoutParams(dp(330), -1, Gravity.LEFT));
    }

    private void buildSmartPanel() {
        smartPanel = new FrameLayout(this);
        smartPanel.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2111113);
        float r = dp(18);
        bg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        smartPanel.setBackground(bg);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        smartBody = new LinearLayout(this);
        smartBody.setOrientation(LinearLayout.VERTICAL);
        smartBody.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        smartBody.setPadding(dp(14), dp(12), dp(14), dp(16));
        sv.addView(smartBody, new ViewGroup.LayoutParams(-1, -2));
        smartPanel.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        smartPanel.setVisibility(View.GONE);
        root.addView(smartPanel, new FrameLayout.LayoutParams(dp(340), -1, Gravity.RIGHT));
    }

    private void buildOverlay() {
        FrameLayout o = new FrameLayout(this);
        o.setBackgroundColor(0xE6000000);
        o.setClickable(true);
        overlayText = CigramUI.label(this, "", 16f, Color.WHITE, true);
        overlayText.setGravity(Gravity.CENTER);
        overlayText.setLineSpacing(0f, 1.2f);
        o.addView(overlayText, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        overlay = o;
        o.setVisibility(View.GONE);
        root.addView(o, new FrameLayout.LayoutParams(-1, -1));
    }

    private void buildLockLayer() {
        lockLayer = new FrameLayout(this);
        lockLayer.setClickable(true);
        lockLayer.setVisibility(View.GONE);
        TextView unlock = CigramUI.label(this, "🔒 اضغط مطولاً للفتح", 13f, Color.WHITE, true);
        unlock.setGravity(Gravity.CENTER);
        unlock.setBackground(CigramUI.round(0x88000000, 22, this));
        unlock.setPadding(dp(16), 0, dp(16), 0);
        unlock.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { setLocked(false); return true; }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, dp(44), Gravity.LEFT | Gravity.BOTTOM);
        lp.leftMargin = dp(20);
        lp.bottomMargin = dp(20);
        lockLayer.addView(unlock, lp);
        root.addView(lockLayer, new FrameLayout.LayoutParams(-1, -1));
    }

    private void showOverlay(String text) {
        overlayText.setText(text);
        overlay.setVisibility(View.VISIBLE);
    }

    private void showBlockingError(String text) {
        showOverlay(text + "\n\n(اضغط رجوع للمغادرة)");
    }

    private void hideOverlay() { overlay.setVisibility(View.GONE); }

    private void setControlsVisible(boolean v) {
        controlsVisible = v && !locked;
        controls.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
        main.removeCallbacks(hideControls);
        if (controlsVisible) main.postDelayed(hideControls, 4500L);
    }

    private void scheduleHide() {
        main.removeCallbacks(hideControls);
        main.postDelayed(hideControls, 4500L);
    }

    /** الأيقونتان (المحادثة واللوحة) تختفيان فقط عند القفل. */
    private void refreshEdgeIcons() {
        chatIconBox.setVisibility(locked || chatOpen ? View.GONE : View.VISIBLE);
        panelHandle.setVisibility(locked || panelOpen ? View.GONE : View.VISIBLE);
        badge.setVisibility(unread > 0 && !focusMode ? View.VISIBLE : View.GONE);
        badge.setText(unread > 99 ? "99+" : String.valueOf(unread));
    }

    private void setLocked(boolean on) {
        locked = on;
        lockLayer.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) { toggleChat(false); togglePanel(false); }
        setControlsVisible(!on);
        refreshEdgeIcons();
    }

    private void toggleFocus() {
        focusMode = !focusMode;
        floatBox.setVisibility(focusMode ? View.GONE : View.VISIBLE);
        if (focusMode) toggleChat(false);
        refreshEdgeIcons();
        toast(focusMode ? "وضع التركيز: الرسائل العائمة مخفية والمزامنة مستمرة." : "أُوقف وضع التركيز.");
    }

    private void toggleChat(boolean open) {
        if (focusMode && open) focusMode = false;
        if (open && panelOpen) togglePanel(false);
        chatOpen = open;
        chatPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        floatBox.setVisibility(open || focusMode ? View.GONE : View.VISIBLE);
        if (open) { unread = 0; main.removeCallbacks(hideControls); scrollChatToEnd(); }
        else {
            try { ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(chatInput.getWindowToken(), 0); } catch (Throwable ignored) { }
            scheduleHide();
        }
        refreshEdgeIcons();
    }

    private void togglePanel(boolean open) {
        if (open && chatOpen) toggleChat(false);
        panelOpen = open;
        if (open) { readNetwork(); rebuildSmartPanel(); main.removeCallbacks(hideControls); }
        else scheduleHide();
        smartPanel.setVisibility(open ? View.VISIBLE : View.GONE);
        refreshEdgeIcons();
    }

    // ------------------------------------------------------------------ اللوحة الذكية

    private void rebuildSmartPanel() {
        LinearLayout b = smartBody;
        b.removeAllViews();
        JSONObject room = session.room();
        JSONObject c = session.content();

        // العنوان + الحالة + المشاركون
        TextView title = CigramUI.label(this, CigramTogether.contentLabel(c), 15f, Color.WHITE, true);
        title.setGravity(Gravity.RIGHT);
        b.addView(title, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout who = new LinearLayout(this);
        who.setOrientation(LinearLayout.HORIZONTAL);
        who.setGravity(Gravity.CENTER_VERTICAL);
        who.setPadding(0, dp(6), 0, 0);
        if (room != null) {
            JSONObject presence = room.optJSONObject("presence");
            for (JSONObject u : new JSONObject[]{room.optJSONObject("owner"), room.optJSONObject("guest")}) {
                if (u == null) continue;
                View av = CigramTogether.avatarView(this, u, 26);
                av.setAlpha(presence == null || presence.optBoolean(u.optString("uid"), true) ? 1f : 0.4f);
                LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(26), dp(26));
                al.setMarginEnd(dp(4));
                who.addView(av, al);
            }
        }
        TextView st = CigramUI.label(this, connectionText(room), 12f, CigramUI.MUTED, false);
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(0, -2, 1f);
        stl.setMarginStart(dp(6));
        who.addView(st, stl);
        who.setClickable(true);
        who.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showParticipants(); } });
        b.addView(who, new LinearLayout.LayoutParams(-1, -2));

        // ---- إعدادات المشاهدة (لك فقط)
        section(b, "إعدادات المشاهدة — لك فقط");
        TextView net = CigramUI.label(this, "الشبكة: " + networkLabel() + "\nالدقة الحالية: " + currentResolution(), 12f, CigramUI.MUTED, false);
        net.setGravity(Gravity.RIGHT);
        net.setLineSpacing(0f, 1.15f);
        b.addView(net, new LinearLayout.LayoutParams(-1, -2));
        b.addView(choiceRow("الجودة", new String[]{"تلقائي حسب الشبكة", "أعلى دقة", "توفير البيانات"},
                Q_HIGH.equals(quality) ? 1 : Q_SAVER.equals(quality) ? 2 : 0, new ChoiceCb() {
                    @Override public void picked(int i) {
                        quality = i == 1 ? Q_HIGH : i == 2 ? Q_SAVER : Q_AUTO;
                        savePrefs(); applyQuality(); rebuildSmartPanel();
                    }
                }));
        b.addView(choiceRow("الترجمة", new String[]{"تعمل", "متوقفة"}, subsOn ? 0 : 1, new ChoiceCb() {
            @Override public void picked(int i) { subsOn = i == 0; savePrefs(); styleSubtitles(); rebuildSmartPanel(); }
        }));
        b.addView(choiceRow("حجم الترجمة", new String[]{"صغير", "متوسط", "كبير"}, subSize, new ChoiceCb() {
            @Override public void picked(int i) { subSize = i; savePrefs(); styleSubtitles(); rebuildSmartPanel(); }
        }));
        b.addView(choiceRow("أبعاد الشاشة", new String[]{"متناسق", "ملء الشاشة", "تكبير"}, resizeMode, new ChoiceCb() {
            @Override public void picked(int i) { resizeMode = i; savePrefs(); applyResizeMode(); rebuildSmartPanel(); }
        }));
        b.addView(choiceRow("الرسائل فوق الفيديو", new String[]{"تظهر", "مخفية"}, floatingOn ? 0 : 1, new ChoiceCb() {
            @Override public void picked(int i) { floatingOn = i == 0; savePrefs(); rebuildSmartPanel(); }
        }));

        // ---- الحلقات
        if (c != null && "episode".equals(c.optString("type"))) {
            section(b, "الحلقات");
            ArrayList<JSONObject> eps = CigramTogether.episodesOf(c);
            int lastSeason = Integer.MIN_VALUE;
            for (final JSONObject ep : eps) {
                int s = ep.optInt("season", 1);
                if (s != lastSeason) {
                    lastSeason = s;
                    TextView h = CigramUI.label(this, "الموسم " + s, 12.5f, CigramUI.AMBER, true);
                    h.setGravity(Gravity.RIGHT);
                    h.setPadding(0, dp(6), 0, dp(2));
                    b.addView(h, new LinearLayout.LayoutParams(-1, -2));
                }
                boolean current = s == c.optInt("season", 1) && ep.optInt("episode") == c.optInt("episode");
                b.addView(panelRow((current ? "▶  " : "") + "الحلقة " + ep.optInt("episode"), current, new Runnable() {
                    @Override public void run() { togglePanel(false); proposeEpisode(ep, false); }
                }));
            }
        }

        // ---- الغرفة
        section(b, "الغرفة");
        b.addView(panelRow("😊  تفاعل", false, new Runnable() { @Override public void run() { togglePanel(false); showReactions(); } }));
        b.addView(panelRow("⭐  لحظاتنا", false, new Runnable() { @Override public void run() { togglePanel(false); showMoments(); } }));
        if (!session.isOwner()) b.addView(panelRow("✋  انتظرني", false, new Runnable() { @Override public void run() { waitMe(); } }));
        b.addView(panelRow("🔄  مزامنة الآن", false, new Runnable() { @Override public void run() { forceSync(); } }));
        b.addView(panelRow("🎯  وضع التركيز" + (focusMode ? " (يعمل)" : ""), false, new Runnable() { @Override public void run() { toggleFocus(); rebuildSmartPanel(); } }));
        b.addView(panelRow("🔒  قفل الشاشة", false, new Runnable() { @Override public void run() { setLocked(true); } }));
        b.addView(panelRow("👥  المشاركون", false, new Runnable() { @Override public void run() { showParticipants(); } }));
        if (session.isOwner() && room != null && room.optJSONObject("guest") != null) {
            b.addView(panelRow("إزالة الصديق من الغرفة", false, new Runnable() { @Override public void run() { kick(false); } }));
            b.addView(panelRow("إزالة الصديق وحظره", false, new Runnable() { @Override public void run() { kick(true); } }));
        }
        b.addView(panelRow("إبلاغ عن إساءة", false, new Runnable() { @Override public void run() { report(); } }));
        b.addView(panelRow("⎋  مغادرة", false, new Runnable() { @Override public void run() { togglePanel(false); confirmLeave(); } }));
    }

    private interface ChoiceCb { void picked(int index); }

    private void section(LinearLayout b, String text) {
        TextView t = CigramUI.label(this, text, 13f, Color.WHITE, true);
        t.setGravity(Gravity.RIGHT);
        t.setAlpha(0.75f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(16);
        lp.bottomMargin = dp(4);
        View line = new View(this);
        line.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, 1);
        ll.topMargin = dp(12);
        b.addView(line, ll);
        b.addView(t, lp);
    }

    private View choiceRow(String label, String[] options, int selected, final ChoiceCb cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, 0);
        TextView l = CigramUI.label(this, label, 12.5f, CigramUI.MUTED, false);
        l.setGravity(Gravity.RIGHT);
        box.addView(l, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            boolean on = i == selected;
            TextView o = CigramUI.label(this, options[i], 12f, on ? Color.BLACK : Color.WHITE, on);
            o.setGravity(Gravity.CENTER);
            o.setBackground(on ? CigramUI.round(Color.WHITE, 16, this) : CigramUI.round(0x00000000, BUBBLE_STROKE, 16, this));
            o.setPadding(dp(6), 0, dp(6), 0);
            o.setClickable(true);
            o.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { cb.picked(idx); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            if (i > 0) lp.setMarginStart(dp(6));
            row.addView(o, lp);
        }
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = dp(4);
        box.addView(row, rl);
        return box;
    }

    private View panelRow(String text, boolean highlighted, final Runnable r) {
        TextView t = CigramUI.label(this, text, 13.5f, Color.WHITE, highlighted);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setMinHeight(dp(42));
        t.setBackground(highlighted ? CigramUI.round(0x33E21B14, 0x80E21B14, 12, this) : CigramUI.round(0x14FFFFFF, 12, this));
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { r.run(); } });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private String connectionText(JSONObject room) {
        JSONObject other = session.other();
        JSONObject presence = room == null ? null : room.optJSONObject("presence");
        if (!session.isConnected()) return "جاري الاتصال بالغرفة…";
        if (other == null) return "الصديق غادر الغرفة";
        if (presence != null && !presence.optBoolean(other.optString("uid"), true)) return other.optString("name") + " غير متصل — نحاول نرجعه";
        return "متصل مع " + other.optString("name") + (session.isOwner() ? " • أنت تتحكم بالتشغيل" : " • التحكم لصاحب الغرفة");
    }

    // ------------------------------------------------------------------ media

    private String myUid() {
        JSONObject r = session.room();
        if (r == null) return "";
        JSONObject me = session.isOwner() ? r.optJSONObject("owner") : r.optJSONObject("guest");
        return me == null ? "" : me.optString("uid");
    }

    private void loadEpoch(JSONObject room) {
        loadedEpoch = room.optInt("epoch", 0);
        saveProgress(false);
        ended = false;
        prepared = false;
        readyAcked = false;
        lastReadyAttemptAt = 0L;
        waitStartedAt = System.currentTimeMillis();
        lastPolledState = -1;
        errorShown = false;
        JSONObject c = room.optJSONObject("content");
        playable = CigramTogether.resolvePlayable(c);
        loadHistory();
        if (playable == null) {
            showBlockingError("هذا المحتوى غير متوفر على جهازك الآن.\nحدّث التطبيق أو تأكد من الاتصال بالإنترنت.");
            return;
        }
        // An embed page (vidbolt, vidfast, ...) is a web page, not a video: ExoPlayer cannot open it and
        // used to fail with "الفيديو ما اشتغل". Resolve it to the real stream link first.
        final String rawVideo = playable.optString("video");
        if (CigramEmbedResolver.isEmbed(rawVideo)) {
            final int epochAtStart = loadedEpoch;
            showOverlay("جاري استخراج رابط الفيديو…");
            CigramEmbedResolver.resolveAsync(this, rawVideo, new CigramEmbedResolver.Callback() {
                @Override public void onResolved(String url) {
                    if (isFinishing() || epochAtStart != loadedEpoch || playable == null) return;
                    try { playable.put("video", url); } catch (Throwable ignored) { }
                    prepareMedia();
                }
                @Override public void onFailed(String reason) {
                    if (isFinishing() || epochAtStart != loadedEpoch) return;
                    android.util.Log.w("CigramTogetherPlayer", "embed resolve failed: " + reason);
                    showBlockingError("تعذّر استخراج رابط الفيديو المباشر من هذا المصدر.\nجرّب مصدراً آخر أو أعد المحاولة.");
                }
            });
            return;
        }
        prepareMedia();
    }

    /** Builds the player (once) and loads the playable's video, which is a real stream link by now. */
    private void prepareMedia() {
        if (playable == null) return;
        showOverlay("جاري تجهيز الفيديو…");
        // 18.2: the subtitle is read by CigramSubtitleHub first (correct milliseconds / encoding / SRT or VTT by content)
        final String cgSub = playable.optString("subtitle", "");
        if (cgSub.startsWith("http") && !playable.has("cg_subtitle_local")) {
            final int epochAtStart = loadedEpoch;
            CigramSubtitleSyncEngine.normalizeToFile(this, cgSub, new CigramSubtitleSyncEngine.FileCallback() {
                @Override public void done(String fileUri) {
                    if (isFinishing() || epochAtStart != loadedEpoch || playable == null) return;
                    try { playable.put("cg_subtitle_local", fileUri == null ? "" : fileUri); } catch (Throwable ignored) { }
                    prepareMedia();
                }
            });
            return;
        }
        try {
            if (player == null) {
                readNetwork();
                trackSelector = new DefaultTrackSelector(this);
                long bps = usableBps();
                DefaultBandwidthMeter.Builder bm = new DefaultBandwidthMeter.Builder(this);
                if (bps > 0L) bm.setInitialBitrateEstimate(bps);      // نبدأ بجودة تناسب الشبكة بدل أدنى جودة
                player = new ExoPlayer.Builder(this)
                        .setTrackSelector(trackSelector)
                        .setLoadControl(buildLoadControl())
                        .setBandwidthMeter(bm.build())
                        .setMediaSourceFactory(CigramStreams.sourceFactory(this))
                        .build();
                applyQuality();
                player.setVideoSurfaceView(surface);
                // لا Player.Listener مخصص (D8 في Sketchware ينهار داخل ListenerSet): المتابعة عبر pollPlayer()
                lastPolledState = -1;
                main.removeCallbacks(pollPlayer);
                main.postDelayed(pollPlayer, 250L);
            }
            MediaItem.Builder mb = CigramStreams.apply(new MediaItem.Builder(), playable.optString("video"));
            String sub = playable.optString("subtitle", "");
            String cgLocal = playable.optString("cg_subtitle_local", "");
            if (cgLocal.startsWith("file:")) {
                mb.setSubtitleConfigurations(Collections.singletonList(
                        new MediaItem.SubtitleConfiguration.Builder(Uri.parse(cgLocal)).setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("ar")
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()));
            } else if (sub.startsWith("http")) {
                String mime = sub.toLowerCase(java.util.Locale.US).contains(".srt") ? MimeTypes.APPLICATION_SUBRIP : MimeTypes.TEXT_VTT;
                mb.setSubtitleConfigurations(Collections.singletonList(
                        new MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub)).setMimeType(mime).setLanguage("ar")
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()));
            }
            player.setPlayWhenReady(false);
            player.setMediaItem(mb.build());
            player.prepare();
        } catch (Throwable t) {
            android.util.Log.w("CigramTogetherPlayer", "prepare failed", t);
            showBlockingError("تعذر تجهيز الفيديو.");
        }
    }

    private void onPlayerState(int state) {
        if (state == Player.STATE_BUFFERING) {
            if (!localBuffering) { localBuffering = true; sendBuffering(true); }
            bufferView.setVisibility(View.VISIBLE);
        } else {
            if (localBuffering) { localBuffering = false; sendBuffering(false); }
            bufferView.setVisibility(View.GONE);
        }
        if (state == Player.STATE_READY && !prepared) {
            prepared = true;
            if (readySentEpoch != loadedEpoch) {
                readySentEpoch = loadedEpoch;
                sendReady();
            }
            JSONObject pb = session.room() == null ? null : session.room().optJSONObject("playback");
            if (pb != null && "loading".equals(pb.optString("status"))) showOverlay(waitingText());
            else hideOverlay();
            applyRoom(session.room(), "ready");
        }
        if (state == Player.STATE_ENDED && !ended) {
            ended = true;
            saveProgress(true);
            onEpisodeEnded();
        }
    }

    /** يرسل ready ويعيد المحاولة من watchdog حتى يسجّل الخادم جاهزيتنا فعلاً. */
    private void sendReady() {
        lastReadyAttemptAt = System.currentTimeMillis();
        final int ep = loadedEpoch;
        try {
            session.cmd(new JSONObject().put("t", "ready").put("epoch", ep), new CigramTogetherSession.Ack() {
                @Override public void onAck(boolean ok, String error, String message, JSONObject raw) {
                    if (ep == loadedEpoch && (ok || "stale_epoch".equals(error))) readyAcked = true;
                    if ("stale_epoch".equals(error)) session.sync();       // حلقة تغيّرت: نجلب الحالة الحديثة
                }
            });
        } catch (Throwable ignored) { }
    }

    private void sendBuffering(boolean on) {
        try { session.cmd(new JSONObject().put("t", "buffering").put("on", on), null); } catch (Throwable ignored) { }
    }

    /** هل سجّل الخادم جاهزية هذا المستخدم؟ (الخادم يرسل خريطة ready في كل لقطة). */
    private boolean serverHasMyReady() {
        JSONObject r = session.room();
        JSONObject ready = r == null ? null : r.optJSONObject("ready");
        return ready != null && ready.optBoolean(myUid(), false);
    }

    private String waitingText() {
        JSONObject other = session.other();
        String name = other == null ? "صديقك" : other.optString("name", "صديقك");
        JSONObject r = session.room();
        JSONObject presence = r == null ? null : r.optJSONObject("presence");
        if (!session.isConnected()) return "جاري الاتصال بالغرفة…";
        if (other == null) return "بانتظار انضمام صديقك…";
        if (presence != null && !presence.optBoolean(other.optString("uid"), true)) return name + " غير متصل الآن…\nسنبدأ تلقائياً عند رجوعه.";
        return "بانتظار جاهزية " + name + "…";
    }

    // ------------------------------------------------------------------ applying the authoritative state

    private void applyRoom(JSONObject room, String cause) {
        if (room == null || isFinishing() || !adDone || midAdShowing) return;
        int ep = room.optInt("epoch", 0);
        if (ep != loadedEpoch) {
            if (ep > 0) {
                boolean change = loadedEpoch >= 0;
                loadEpoch(room);
                addSystemLine(CigramTogether.contentLabel(room.optJSONObject("content")), change);
            }
            return;
        }
        center.setVisibility(iControl() ? View.VISIBLE : View.GONE);
        JSONObject pb = room.optJSONObject("playback");
        if (pb == null) return;
        String status = pb.optString("status");
        updatePlayIcon(status);
        handleRequests(room);
        if (panelOpen && !"tick".equals(cause)) rebuildSmartPanel();
        if (player == null || !prepared) return;

        main.removeCallbacks(startRunnable);
        if ("loading".equals(status)) {
            player.setPlayWhenReady(false);
            showOverlay(waitingText());
            return;
        }
        hideOverlay();
        long target = session.referencePositionMs();
        long cur = player.getCurrentPosition();
        if ("paused".equals(status)) {
            player.setPlayWhenReady(false);
            setSpeed(1f);
            if (Math.abs(cur - target) > 300L) player.seekTo(target);
            return;
        }
        long wait = session.msUntilStart();
        if (wait > 0L) {
            player.setPlayWhenReady(false);
            player.seekTo(pb.optLong("position_ms", 0L));
            main.postDelayed(startRunnable, wait);
            return;
        }
        if (Math.abs(cur - target) > 600L) player.seekTo(target);
        setSpeed(1f);
        if (!ended) player.setPlayWhenReady(true);
    }

    private void setSpeed(float s) {
        if (player == null || Math.abs(s - speed) < 0.001f) return;
        speed = s;
        try { player.setPlaybackParameters(new PlaybackParameters(s)); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ mid-roll (v5.20)

    private void maybeMidRoll(long cur, JSONObject pb) {
        try {
            if (!adDone || ended || pb == null || !"playing".equals(pb.optString("status"))) return;
            if (nextMidAdAtMs < 0L) {
                long first = Math.max(60000L, CigramAdConfig.firstAdDelayMs(this));
                nextMidAdAtMs = cur < first ? first : CigramAdConfig.nextBreakAfter(this, cur);
            }
            if (nextMidAdAtMs == Long.MAX_VALUE) return;
            if (cur >= nextMidAdAtMs - CigramAdConfig.PLAYER_PRELOAD_MS) {
                CigramEpisodeAds.preload(this);
            }
            if (cur < nextMidAdAtMs) return;
            String why = CigramAdManager.playerAdBlockReason(this, this, cur, false, !isFinishing());
            if (why.length() > 0) {
                if ("mid_roll_disabled".equals(why) || "ad_free".equals(why)) nextMidAdAtMs = Long.MAX_VALUE;
                else nextMidAdAtMs = cur + 60000L;
                return;
            }
            midAdShowing = true;
            final long at = cur;
            final boolean[] adShown = {false};
            CigramEpisodeAds.midRoll(this, new Runnable() {
                @Override public void run() {
                    adShown[0] = true;
                    try { if (player != null) player.setPlayWhenReady(false); } catch (Throwable ignored) { }
                }
            }, new Runnable() {
                @Override public void run() {
                    midAdShowing = false;
                    nextMidAdAtMs = adShown[0] ? CigramAdConfig.nextBreakAfter(CigramTogetherPlayerActivity.this, Math.max(at, curPos())) : at + 60000L;
                    if (isFinishing()) return;
                    session.sync();
                    applyRoom(session.room(), "resume");
                }
            });
        } catch (Throwable t) {
            midAdShowing = false;
            android.util.Log.w("CigramTogetherPlayer", "midroll", t);
        }
    }

    /** كل ثانية: تصحيح الانحراف، تحديث الشريط، مراقبة الشبكة، حفظ التقدم. */
    private void onTick() {
        watchdog();
        if (System.currentTimeMillis() - lastNetCheckAt > 15000L) readNetwork();     // تغيّر Wi‑Fi/بيانات → تعديل الجودة
        if (player == null || !prepared) return;
        long dur = player.getDuration();
        long cur = player.getCurrentPosition();
        if (!seeking && dur > 0) {
            seek.setProgress((int) Math.min(1000L, cur * 1000L / dur));
            timeView.setText(CigramTogetherSession.clock(cur) + " / " + CigramTogetherSession.clock(dur));
        }
        JSONObject room = session.room();
        JSONObject pb = room == null ? null : room.optJSONObject("playback");
        if (midAdShowing) return;
        maybeMidRoll(cur, pb);
        if (pb != null && "playing".equals(pb.optString("status")) && session.msUntilStart() == 0L
                && player.getPlaybackState() == Player.STATE_READY && !ended) {
            long drift = cur - session.referencePositionMs();
            if (Math.abs(drift) > 2000L) { player.seekTo(session.referencePositionMs()); setSpeed(1f); }
            else if (Math.abs(drift) > 350L) setSpeed(drift > 0 ? 0.95f : 1.05f);
            else setSpeed(1f);
        }
        if (System.currentTimeMillis() - lastProgressSaveAt > 10000L) saveProgress(false);
    }

    /** مهلات الانتظار: إعادة ready حتى يسجّلها الخادم، ونص واضح بدل شاشة سوداء للأبد. */
    private void watchdog() {
        if (isFinishing() || midAdShowing || playable == null || errorShown) return;
        long now = System.currentTimeMillis();
        if (!prepared) {
            if (waitStartedAt > 0L && now - waitStartedAt > PREPARE_TIMEOUT_MS) {
                waitStartedAt = 0L;
                showBlockingError("تجهيز الفيديو يأخذ وقتاً أطول من المعتاد.\nتأكد من الإنترنت أو جرّب لاحقاً.");
            }
            return;
        }
        JSONObject pb = session.room() == null ? null : session.room().optJSONObject("playback");
        boolean loading = pb != null && "loading".equals(pb.optString("status"));
        if (!loading) { waitStartedAt = 0L; return; }
        if (waitStartedAt <= 0L) waitStartedAt = now;
        // الخادم لم يسجّل جاهزيتنا (ack ضاع، أو أعاد ضبطها بعد خروج/رجوع الصديق): نعيد الإرسال كل 3 ثوانٍ
        if (session.isConnected() && (!readyAcked || !serverHasMyReady()) && now - lastReadyAttemptAt > 3000L) sendReady();
        showOverlay(waitingText() + (now - waitStartedAt > WAIT_HINT_MS ? "\n(اضغط رجوع للمغادرة)" : ""));
        if (now - waitStartedAt > WAIT_HINT_MS && now - lastReadyAttemptAt > 10000L) { session.sync(); sendReady(); }
    }

    private void updatePlayIcon(String status) {
        playBtn.setText("playing".equals(status) ? "❚❚" : "▶");
    }

    // ------------------------------------------------------------------ owner commands (كلها عبر الخادم)

    private long curPos() { return player == null ? 0L : Math.max(0L, player.getCurrentPosition()); }

    private void togglePlay() {
        if (!prepared || !iControl()) return;
        JSONObject pb = session.room() == null ? null : session.room().optJSONObject("playback");
        boolean playing = pb != null && "playing".equals(pb.optString("status"));
        sendControl(playing ? "pause" : "play", curPos());
    }

    private void skip(long deltaMs) {
        if (!prepared || player == null || !iControl()) return;
        long dur = player.getDuration();
        long t = session.referencePositionMs() + deltaMs;
        if (t < 0L) t = 0L;
        if (dur > 0L && t > dur - 500L) t = Math.max(0L, dur - 500L);
        sendControl("seek", t);
    }

    private void sendControl(String type, long posMs) {
        if (!iControl()) { toast("التحكم بالتشغيل لصاحب الغرفة فقط."); return; }
        try {
            JSONObject m = new JSONObject().put("t", type).put("epoch", session.epoch()).put("position_ms", posMs);
            session.cmd(m, new CigramTogetherSession.Ack() {
                @Override public void onAck(boolean ok, String error, String message, JSONObject raw) {
                    if (!ok && !"stale_epoch".equals(error) && !"loading".equals(error)) toast(message.length() > 0 ? message : "تعذر تنفيذ الأمر.");
                }
            });
        } catch (Throwable ignored) { }
    }

    private void forceSync() {
        session.sync();
        if (player != null && prepared) player.seekTo(session.referencePositionMs());
        toast("تمت المزامنة");
    }

    private void waitMe() {
        if (session.isOwner()) { toast("أنت صاحب الغرفة: أوقف التشغيل متى شئت."); return; }
        JSONObject wr = session.room() == null ? null : session.room().optJSONObject("wait_request");
        if (wr != null && myUid().equals(wr.optString("from"))) {
            session.cmd("wait.back", new CigramTogetherSession.Ack() {
                @Override public void onAck(boolean ok, String error, String message, JSONObject raw) { if (!ok) toast("تعذر الإرسال."); }
            });
            return;
        }
        session.cmd("wait.request", new CigramTogetherSession.Ack() {
            @Override public void onAck(boolean ok, String error, String message, JSONObject raw) { toast(ok ? "أرسلنا طلب الانتظار لصاحب الغرفة." : (message.length() > 0 ? message : "تعذر الإرسال.")); }
        });
    }

    // ------------------------------------------------------------------ chat

    private void sendChat() {
        String text = chatInput.getText().toString().trim();
        if (text.length() == 0) return;
        chatInput.setText("");
        doSendChat(UUID.randomUUID().toString(), text, curPos(), null);
    }

    private void doSendChat(final String mid, final String text, final long pos, View existing) {
        final View bubble = existing != null ? existing : addBubble(selfProfile(), text, pos, true);
        bubble.setAlpha(0.55f);
        try {
            session.cmd(new JSONObject().put("t", "chat").put("message_id", mid).put("text", text).put("position_ms", pos).put("epoch", session.epoch()),
                    new CigramTogetherSession.Ack() {
                        @Override public void onAck(boolean ok, String error, String message, JSONObject raw) {
                            if (ok) { bubble.setAlpha(1f); bubble.setOnClickListener(null); }
                            else {
                                bubble.setAlpha(1f);
                                toast("فشل إرسال الرسالة — اضغط عليها لإعادة المحاولة");
                                bubble.setOnClickListener(new View.OnClickListener() {
                                    @Override public void onClick(View v) { doSendChat(mid, text, pos, bubble); }   // نفس message_id → لا تكرار
                                });
                            }
                        }
                    });
        } catch (Throwable ignored) { }
    }

    private JSONObject selfProfile() {
        JSONObject r = session.room();
        JSONObject me = r == null ? null : (session.isOwner() ? r.optJSONObject("owner") : r.optJSONObject("guest"));
        return me == null ? new JSONObject() : me;
    }

    /** اتجاه النص من أول حرف قوي: عربي → يمين، إنجليزي → يسار (الإيموجي وحده يُعامل كعربي). */
    private static boolean isRtlText(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            byte d = Character.getDirectionality(cp);
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true;
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    /**
     * رسالة: صورة دائرية فقط (بلا اسم) + صندوق بحواف دائرية وخلفية شفافة وحد خفيف.
     * الجزء العلوي من الصندوق مفصول بخط رفيع ويحمل لحظة الفيديو [HH:MM:SS]؛ النص أبيض بخط sima.
     */
    private View addBubble(final JSONObject user, final String text, final long posMs, boolean mine) {
        boolean rtl = isRtlText(text);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

        View av = CigramTogether.avatarView(this, user, 28);
        row.addView(av, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(CigramUI.round(mine ? 0x40E21B14 : BUBBLE_BG, BUBBLE_STROKE, 14, this));
        box.setPadding(dp(10), dp(4), dp(10), dp(7));

        TextView ts = CigramUI.label(this, CigramTogetherSession.clock(posMs), 10f, 0xB3FFFFFF, false);
        ts.setTextDirection(View.TEXT_DIRECTION_LTR);
        ts.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        if (iControl()) {
            ts.setClickable(true);
            ts.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { askGoTo(posMs); } });
        }
        box.addView(ts, new LinearLayout.LayoutParams(-1, -2));
        View line = new View(this);
        line.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, 1);
        ll.topMargin = dp(3);
        ll.bottomMargin = dp(4);
        box.addView(line, ll);

        TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(14f);
        body.setTextColor(Color.WHITE);
        body.setTypeface(sima());
        body.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        body.setGravity(rtl ? Gravity.RIGHT : Gravity.LEFT);
        body.setLineSpacing(0f, 1.1f);
        box.addView(body, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.setMarginStart(dp(8));
        row.addView(box, bl);
        box.setMinimumWidth(dp(90));
        body.setMaxWidth(dp(220));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);                                   // كل رسالة منفصلة
        chatList.addView(row, lp);
        scrollChatToEnd();
        return row;
    }

    private void addSystemLine(String text, boolean separator) {
        TextView t = CigramUI.label(this, (separator ? "— بدأت الحلقة التالية: " : "— ") + text + " —", 11f, 0x99FFFFFF, false);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        chatList.addView(t, lp);
        scrollChatToEnd();
    }

    private void scrollChatToEnd() {
        chatScroll.post(new Runnable() { @Override public void run() { chatScroll.fullScroll(View.FOCUS_DOWN); } });
    }

    private void loadHistory() {
        final int ep = loadedEpoch;
        if (historyLoaded.contains(ep)) return;
        historyLoaded.add(ep);
        chatList.removeAllViews();
        JSONObject c = session.content();
        String eid = c == null ? "" : c.optString("episode_id", "");
        CigramTogetherApi.get(this, "/rooms/" + session.roomId() + "/messages?episode_id=" + Uri.encode(eid), new CigramTogetherApi.Callback() {
            @Override public void onResult(CigramTogetherApi.Result r) {
                if (!r.ok || isFinishing() || ep != loadedEpoch) return;
                JSONArray a = r.json.optJSONArray("messages");
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject m = a.optJSONObject(i);
                    if (m == null || m.optBoolean("system", false)) continue;
                    addBubble(m, m.optString("text"), m.optLong("position_ms"), myUid().equals(m.optString("uid")));
                }
            }
        });
    }

    private void askGoTo(final long posMs) {
        if (!iControl()) return;
        CigramUI.sheet(this).icon(CigramUI.ICON_PROGRESS, CigramUI.AMBER).title("نرجع لهذه اللقطة؟")
                .message("[" + CigramTogetherSession.clock(posMs) + "]")
                .primary("نرجع", CigramUI.AMBER, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) { sendControl("seek", posMs); return false; }
                }).secondary("لا", null).show();
    }

    /** رسالة عائمة قصيرة بجانب أيقونة المحادثة: صورة + نص، بلا اسم. */
    private void floatComment(JSONObject m) {
        if (!floatingOn || focusMode || chatOpen || locked) return;
        String text = m.optString("text");
        boolean rtl = isRtlText(text);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setBackground(CigramUI.round(0x80000000, BUBBLE_STROKE, 16, this));
        row.setPadding(dp(5), dp(4), dp(12), dp(4));
        row.addView(CigramTogether.avatarView(this, m, 22), new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13f);
        t.setTextColor(Color.WHITE);
        t.setTypeface(sima());
        t.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        t.setMaxLines(2);
        t.setMaxWidth(dp(240));
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = dp(8);
        row.addView(t, tl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(6);
        lp.gravity = Gravity.LEFT;
        floatBox.addView(row, 0, lp);
        while (floatBox.getChildCount() > 3) floatBox.removeViewAt(floatBox.getChildCount() - 1);
        final View ref = row;
        main.postDelayed(new Runnable() {
            @Override public void run() { try { floatBox.removeView(ref); } catch (Throwable ignored) { } }
        }, 5000L);
    }

    // ------------------------------------------------------------------ reactions / moments / participants / room

    private void showReactions() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        final CigramUI.Sheet[] holder = new CigramUI.Sheet[1];
        for (int i = 0; i < REACTIONS.length; i++) {
            final String kind = REACTIONS[i];
            TextView e = CigramUI.label(this, REACTION_EMOJI[i], 30f, Color.WHITE, false);
            e.setGravity(Gravity.CENTER);
            e.setClickable(true);
            e.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { session.cmd(new JSONObject().put("t", "reaction").put("kind", kind), null); } catch (Throwable ignored) { }
                    if (holder[0] != null) holder[0].dismiss();
                }
            });
            row.addView(e, new LinearLayout.LayoutParams(dp(56), dp(56)));
        }
        holder[0] = CigramUI.sheet(this).icon(CigramUI.ICON_FLAG, CigramUI.AMBER).title("تفاعل").view(row).secondary("إغلاق", null).show();
    }

    private void floatReaction(String kind) {
        if (focusMode) return;
        String emoji = "👍";
        for (int i = 0; i < REACTIONS.length; i++) if (REACTIONS[i].equals(kind)) emoji = REACTION_EMOJI[i];
        final TextView t = CigramUI.label(this, emoji, 34f, Color.WHITE, false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        lp.leftMargin = dp(80 + (int) (Math.random() * 140));
        lp.bottomMargin = dp(90);
        root.addView(t, lp);
        t.animate().translationY(-dp(180)).alpha(0f).setDuration(1800L).withEndAction(new Runnable() {
            @Override public void run() { try { root.removeView(t); } catch (Throwable ignored) { } }
        }).start();
    }

    private void showMoments() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.addView(list, new ViewGroup.LayoutParams(-1, -2));
        box.addView(sv, new LinearLayout.LayoutParams(-1, dp(180)));
        JSONObject c = session.content();
        final String eid = c == null ? "" : c.optString("episode_id", "");
        final CigramUI.Sheet sheet = CigramUI.sheet(this).icon(CigramUI.ICON_PROGRESS, CigramUI.AMBER).title("لحظاتنا").view(box)
                .primary("⭐ احفظ لحظة هنا", CigramUI.AMBER, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) { askMoment(); return false; }
                }).secondary("إغلاق", null);
        sheet.show();
        CigramTogetherApi.get(this, "/rooms/" + session.roomId() + "/moments?episode_id=" + Uri.encode(eid), new CigramTogetherApi.Callback() {
            @Override public void onResult(CigramTogetherApi.Result r) {
                JSONArray a = r.json.optJSONArray("moments");
                if (!r.ok || a == null || a.length() == 0) {
                    list.addView(CigramUI.label(CigramTogetherPlayerActivity.this, r.ok ? "لا توجد لحظات محفوظة لهذه الحلقة بعد." : r.message, 13f, CigramUI.MUTED, false));
                    return;
                }
                for (int i = 0; i < a.length(); i++) {
                    final JSONObject m = a.optJSONObject(i);
                    if (m == null) continue;
                    LinearLayout row = new LinearLayout(CigramTogetherPlayerActivity.this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(6), 0, dp(6));
                    TextView ts = CigramUI.label(CigramTogetherPlayerActivity.this, "[" + CigramTogetherSession.clock(m.optLong("position_ms")) + "]", 13f, CigramUI.AMBER, true);
                    ts.setTextDirection(View.TEXT_DIRECTION_LTR);
                    row.addView(ts);
                    LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(24), dp(24));
                    al.setMarginStart(dp(8));
                    row.addView(CigramTogether.avatarView(CigramTogetherPlayerActivity.this, m, 24), al);
                    TextView tx = CigramUI.label(CigramTogetherPlayerActivity.this, m.optString("text"), 13f, CigramUI.TEXT, false);
                    LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
                    tl.setMarginStart(dp(8));
                    row.addView(tx, tl);
                    row.setClickable(true);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) { sheet.dismiss(); askGoTo(m.optLong("position_ms")); }
                    });
                    list.addView(row);
                }
            }
        });
    }

    private void askMoment() {
        final EditText in = new EditText(this);
        CigramUI.styleInput(in, "اكتب تعليقاً على هذه اللقطة");
        in.setSingleLine(true);
        final long pos = curPos();
        CigramUI.sheet(this).icon(CigramUI.ICON_PROGRESS, CigramUI.AMBER).title("لحظة عند " + CigramTogetherSession.clock(pos)).view(in)
                .primary("حفظ", CigramUI.AMBER, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) {
                        String text = in.getText().toString().trim();
                        if (text.length() == 0) return true;
                        try {
                            session.cmd(new JSONObject().put("t", "moment").put("moment_id", UUID.randomUUID().toString()).put("text", text).put("position_ms", pos),
                                    new CigramTogetherSession.Ack() {
                                        @Override public void onAck(boolean ok, String error, String message, JSONObject raw) { toast(ok ? "حُفظت اللحظة" : "تعذر الحفظ"); }
                                    });
                        } catch (Throwable ignored) { }
                        return false;
                    }
                }).secondary("إلغاء", null).show();
    }

    private void showParticipants() {
        JSONObject r = session.room();
        if (r == null) return;
        JSONObject presence = r.optJSONObject("presence");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        for (String key : new String[]{"owner", "guest"}) {
            JSONObject u = r.optJSONObject(key);
            if (u == null) continue;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            row.addView(CigramTogether.avatarView(this, u, 40));
            boolean online = presence == null || presence.optBoolean(u.optString("uid"), true);
            TextView t = CigramUI.label(this, u.optString("name") + (u.optString("uid").equals(myUid()) ? " (أنت)" : "") + "\n"
                    + ("owner".equals(key) ? "صاحب الغرفة — يتحكم بالتشغيل" : "ضيف — يشاهد ويكتب") + " • " + (online ? "متصل" : "غير متصل"), 13.5f, CigramUI.TEXT, false);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
            tl.setMarginStart(dp(10));
            row.addView(t, tl);
            box.addView(row);
        }
        CigramUI.sheet(this).icon(CigramUI.ICON_USER, CigramUI.TEAL).title("المشاركون").view(box).secondary("إغلاق", null).show();
    }

    private void kick(boolean ban) {
        try {
            CigramTogetherApi.post(this, "/rooms/" + session.roomId() + "/kick", new JSONObject().put("ban", ban), null, new CigramTogetherApi.Callback() {
                @Override public void onResult(CigramTogetherApi.Result res) { toast(res.ok ? "أُزيل الصديق" : res.message); }
            });
        } catch (Throwable ignored) { }
    }

    private void report() {
        try {
            CigramTogetherApi.post(this, "/rooms/" + session.roomId() + "/report", new JSONObject().put("reason", "abuse"), null, new CigramTogetherApi.Callback() {
                @Override public void onResult(CigramTogetherApi.Result res) { toast(res.ok ? "وصل البلاغ، شكراً" : res.message); }
            });
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ episodes

    private void onEpisodeEnded() {
        JSONObject c = session.content();
        JSONObject next = CigramTogether.nextContent(c);
        JSONObject room = session.room();
        if (isFinishing()) return;
        if (room != null && room.optJSONObject("pending_change") != null) return;
        if (next == null) {
            CigramUI.sheet(this).icon(CigramUI.ICON_CHECK, CigramUI.GREEN)
                    .title("movie".equals(c == null ? "" : c.optString("type")) ? "انتهى الفيلم" : "انتهت الحلقات المتوفرة")
                    .message("شكراً على المشاهدة سوه!")
                    .primary("مغادرة الغرفة", CigramUI.GREEN, new CigramUI.Click() {
                        @Override public boolean onClick(CigramUI.Sheet s) { leaveNow(false); return false; }
                    }).secondary("ابقَ في الغرفة", null).show();
            return;
        }
        proposeEpisode(next, true);
    }

    private void proposeEpisode(final JSONObject next, boolean fromEnd) {
        String label = CigramTogether.contentLabel(next);
        boolean newSeason = session.content() != null && next.optInt("season") != session.content().optInt("season");
        CigramUI.sheet(this).icon(CigramUI.ICON_FLAG, CigramUI.RED)
                .title(fromEnd ? (newSeason ? "خلص الموسم، نبلش الموسم التالي سوه؟" : "نكمل الحلقة التالية سوه؟") : "تشغيل حلقة")
                .message(label + "\nيحتاج موافقة صديقك قبل أن نبدأ.")
                .primary("نكمل", CigramUI.RED, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) {
                        try {
                            session.cmd(new JSONObject().put("t", "episode.request").put("content", next), new CigramTogetherSession.Ack() {
                                @Override public void onAck(boolean ok, String error, String message, JSONObject raw) {
                                    toast(ok ? "بانتظار موافقة صديقك…" : (message.length() > 0 ? message : "تعذر إرسال الاقتراح."));
                                }
                            });
                        } catch (Throwable ignored) { }
                        return false;
                    }
                }).secondary("مو هسه", null).show();
    }

    /** طلبات تحتاج ردّنا: تغيير حلقة/انتظرني. تظهر مرة واحدة لكل طلب. */
    private void handleRequests(JSONObject room) {
        JSONObject pc = room.optJSONObject("pending_change");
        if (pc != null && !myUid().equals(pc.optString("from"))) {
            final String key = "pc|" + pc.optLong("at");
            if (shownRequests.add(key)) {
                JSONObject content = pc.optJSONObject("content");
                CigramUI.sheet(this).icon(CigramUI.ICON_FLAG, CigramUI.RED).title("نكمل الحلقة التالية سوه؟")
                        .message(pc.optString("name") + " يقترح:\n" + CigramTogether.contentLabel(content))
                        .primary("موافق", CigramUI.GREEN, new CigramUI.Click() {
                            @Override public boolean onClick(CigramUI.Sheet s) { respondEpisode(true); return false; }
                        }).secondary("مو هسه", new CigramUI.Click() {
                            @Override public boolean onClick(CigramUI.Sheet s) { respondEpisode(false); return false; }
                        }).show();
            }
        }
        JSONObject wr = room.optJSONObject("wait_request");
        if (wr != null && session.isOwner() && !wr.optBoolean("accepted", false)) {
            final String key = "wr|" + wr.optLong("at");
            if (shownRequests.add(key)) {
                CigramUI.sheet(this).icon(CigramUI.ICON_PROGRESS, CigramUI.AMBER).title("انتظرني")
                        .message(wr.optString("name") + " يطلب منك الانتظار قليلاً.")
                        .primary("ننتظره", CigramUI.AMBER, new CigramUI.Click() {
                            @Override public boolean onClick(CigramUI.Sheet s) { respondWait(true); return false; }
                        }).secondary("نكمل", new CigramUI.Click() {
                            @Override public boolean onClick(CigramUI.Sheet s) { respondWait(false); return false; }
                        }).show();
            }
        }
    }

    private void respondEpisode(boolean accept) {
        try { session.cmd(new JSONObject().put("t", "episode.respond").put("accept", accept), null); } catch (Throwable ignored) { }
    }

    private void respondWait(boolean accept) {
        try { session.cmd(new JSONObject().put("t", "wait.respond").put("accept", accept), null); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ session callbacks

    @Override public void onRoom(JSONObject room, String cause) {
        if (isFinishing()) return;
        applyRoom(room, cause);
    }

    @Override public void onChat(JSONObject message) {
        if (isFinishing()) return;
        if (!message.optString("episode_id").equals(session.content() == null ? "" : session.content().optString("episode_id"))) return;
        if (myUid().equals(message.optString("uid"))) return;
        addBubble(message, message.optString("text"), message.optLong("position_ms"), false);
        if (!chatOpen) { unread++; refreshEdgeIcons(); }
        floatComment(message);
    }

    @Override public void onEvent(String type, JSONObject e) {
        if (isFinishing()) return;
        if ("reaction".equals(type)) floatReaction(e.optString("kind"));
        else if ("ready".equals(type)) { if (prepared) applyRoom(session.room(), "ready"); }
        else if ("presence".equals(type)) { if (panelOpen) rebuildSmartPanel(); }
        else if ("moment".equals(type)) {
            JSONObject m = e.optJSONObject("moment");
            if (m != null && !myUid().equals(m.optString("uid"))) toast("⭐ حُفظت لحظة عند " + CigramTogetherSession.clock(m.optLong("position_ms")));
        } else if ("ctl.request".equals(type)) {
            // عملاء قدامى قد يرسلون طلب تحكم؛ القرار لصاحب الغرفة
            final JSONObject req = e.optJSONObject("req");
            if (req != null && session.isOwner()) {
                String what = "play".equals(req.optString("action")) ? "تشغيل" : "pause".equals(req.optString("action")) ? "إيقاف" : "الانتقال إلى " + CigramTogetherSession.clock(req.optLong("position_ms"));
                CigramUI.sheet(this).icon(CigramUI.ICON_PROGRESS, CigramUI.TEAL).title("طلب تحكم")
                        .message(req.optString("name") + " يطلب: " + what)
                        .primary("موافق", CigramUI.GREEN, new CigramUI.Click() {
                            @Override public boolean onClick(CigramUI.Sheet s) { ctlRespond(req.optString("req_id"), true); return false; }
                        }).secondary("لا", new CigramUI.Click() {
                            @Override public boolean onClick(CigramUI.Sheet s) { ctlRespond(req.optString("req_id"), false); return false; }
                        }).show();
            }
        }
        else if ("wait.back".equals(type)) toast("✅ " + e.optString("name") + " رجع، نكمل!");
        else if ("buffering".equals(type) && e.optBoolean("on")) toast("صديقك يحمّل الفيديو…");
        else if ("room.closed".equals(type)) { toast("أُغلقت الغرفة."); leaveNow(true); }
        else if ("kicked".equals(type)) { toast("أزالك صاحب الغرفة."); leaveNow(true); }
    }

    private void ctlRespond(String reqId, boolean accept) {
        try { session.cmd(new JSONObject().put("t", "ctl.respond").put("req_id", reqId).put("accept", accept), null); } catch (Throwable ignored) { }
    }

    @Override public void onConnection(boolean connected) {
        if (isFinishing()) return;
        if (panelOpen) rebuildSmartPanel();
        if (connected && adDone) {
            session.sync();                         // بعد إعادة الاتصال: لقطة حديثة، لا أوامر قديمة
            if (prepared) { readyAcked = false; lastReadyAttemptAt = 0L; }   // الخادم قد يحتاج جاهزيتنا من جديد
        }
    }

    // ------------------------------------------------------------------ leaving / progress

    private void confirmLeave() {
        CigramUI.sheet(this).icon(CigramUI.ICON_WARN, CigramUI.AMBER).title("تريد تطلع من الغرفة؟")
                .message(session.isOwner() ? "إذا خرجت ستُغلق الغرفة لك ولصديقك." : "تقدر ترجع بنفس الرمز إذا كانت الغرفة مفتوحة.")
                .danger(session.isOwner() ? "إغلاق الغرفة والخروج" : "الخروج", new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) { leaveNow(false); return false; }
                }).secondary("إلغاء", null).show();
    }

    private void leaveNow(boolean alreadyClosed) {
        if (leaving) return;
        leaving = true;
        saveProgress(false);
        if (!alreadyClosed && session.roomId().length() > 0) {
            CigramTogetherApi.post(this, "/rooms/" + session.roomId() + (session.isOwner() ? "/close" : "/leave"), new JSONObject(), null, null);
        }
        session.reset();
        finish();
    }

    private void saveProgress(boolean finished) {
        try {
            if (player == null || playable == null || !prepared) return;
            lastProgressSaveAt = System.currentTimeMillis();
            long dur = Math.max(0L, player.getDuration());
            CigramUserData.record(this, playable, Math.max(0L, player.getCurrentPosition()), dur, finished);
        } catch (Throwable ignored) { }
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    // ------------------------------------------------------------------ أيقونة المحادثة (مرسومة، بيضاء، بلا خلفية)

    private static final class ChatGlyph extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();

        ChatGlyph(Context c) {
            super(c);
            p.setColor(Color.WHITE);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setShadowLayer(4f, 0f, 1f, 0x99000000);            // يبقى واضحاً فوق المشاهد الفاتحة
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float s = Math.min(w, h);
            p.setStrokeWidth(s * 0.085f);
            float pad = s * 0.1f;
            r.set(pad, pad, w - pad, h * 0.74f);
            float rad = s * 0.22f;
            path.reset();
            path.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.drawPath(path, p);
            path.reset();                                        // ذيل الفقاعة
            path.moveTo(w * 0.30f, h * 0.74f);
            path.lineTo(w * 0.24f, h - pad);
            path.lineTo(w * 0.48f, h * 0.74f);
            c.drawPath(path, p);
            Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
            dot.setColor(Color.WHITE);
            float cy = (r.top + r.bottom) / 2f, dr = s * 0.05f;
            c.drawCircle(w * 0.33f, cy, dr, dot);
            c.drawCircle(w * 0.50f, cy, dr, dot);
            c.drawCircle(w * 0.67f, cy, dr, dot);
        }
    }
}
