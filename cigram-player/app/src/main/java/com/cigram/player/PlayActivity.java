package com.cigram.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.MediaMetadata;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.video.VideoSize;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Cigram player screen. Opened by the Cigram app, by other apps (VIEW / SHARE), or from this app's own home screen.
 *
 * Playback: ExoPlayer; MP4/MKV/WebM/TS progressive files, HLS (.m3u8), DASH (.mpd), RTSP, local files via the system picker,
 * external subtitles (SRT / VTT / SSA / TTML), resume, rotation, Picture-in-Picture, retry on network loss.
 * Ads: the Cigram campaigns only (pre-roll, mid-rolls, pause card). Ads never block playback: a missing/slow ad server
 * means no ad, and the user's statistics choice ({@link Privacy}) decides whether any id or event leaves the device.
 * Lifecycle: the ExoPlayer is created in onStart and RELEASED in onStop.
 */
public class PlayActivity extends Activity {

    private static final int REQ_SUBTITLE = 41;
    private static final long TICK_MS = 500L;
    private static final long SAVE_EVERY_MS = 10000L;
    private static final long PRELOAD_BEFORE_BREAK_MS = 60000L;
    private static final long BREAK_GRACE_MS = 30000L;
    private static final long PREROLL_WAIT_MS = 2500L;
    private static final long PAUSE_AD_DELAY_MS = 1500L;
    private static final long PAUSE_AD_GAP_MS = 2L * 60000L;
    private static final int AUTO_NEXT_SECONDS = 8;
    private static final int MAX_NETWORK_RETRIES = 3;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private StyledPlayerView playerView;
    private LinearLayout topBar;
    private TextView titleView, nextTopBtn, skipIntroBtn;
    private LinearLayout autoNextBox;
    private TextView autoNextText;

    private PlayerIntent req;
    private ExoPlayer player;
    private Episodes episodes = new Episodes();
    private int epIndex = -1;
    private long[][] skipSegs = new long[0][];

    private long resumeMs;
    private boolean wantPlay = true;
    private boolean prerollDone;
    private boolean ended;
    private boolean pipMode;
    private boolean adShowing;
    private boolean watchNowAd;          // an AdMob interstitial is owed to the press that opened this title
    private boolean visible;             // between onStart and onStop
    private boolean gate;                // the AdMob ad is on screen: playback must not start yet, the player is kept
    private boolean deferredBegin;       // the ad closed while the screen was not visible
    private boolean orientationChosen;
    private int retryCount;
    private int videoW, videoH;

    private long[] breaks = new long[0];
    private int nextBreak;
    private boolean breaksReady;
    private boolean preloadedForBreak;
    private long lastSaveAt;
    private long lastPauseAdAt;
    private long lastPosition, lastDuration;

    private AdView activeAd;
    private PauseAd pauseAd;
    private int autoNextLeft;
    private Episodes.Ep autoNextEp;

    private final List<String> triedMimes = new ArrayList<String>();
    private String currentMime;
    private Uri extraSubtitle;
    private String extraSubtitleMime;
    private Object backCallback;

    // ================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        registerBack();
        if (!accept(getIntent())) leaveWithMessage("الرابط غير صالح أو غير مدعوم.");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        cancelAutoNext();
        releasePlayer();
        dismissAds();
        prerollDone = false;
        if (accept(intent)) startPlayback(); else leaveWithMessage("الرابط غير صالح أو غير مدعوم.");
    }

    /** Never crash on bad input: tell the user on the home screen of the app. */
    private void leaveWithMessage(String message) {
        try {
            startActivity(new Intent(this, HomeActivity.class).putExtra("message", message));
        } catch (Throwable ignored) { }
        finish();
    }

    private boolean accept(Intent i) {
        PlayerIntent p = PlayerIntent.from(i);
        if (p == null) return false;
        req = p;
        episodes = Episodes.parse(p.episodesJson);
        epIndex = episodes.indexOf(p.season, p.episode, p.url);
        applyRequestState();
        resumeMs = p.resumeMs > 0L ? p.resumeMs : ResumeStore.get(this, p.key());
        wantPlay = true;
        ended = false;
        retryCount = 0;
        extraSubtitle = null;
        watchNowAd = p.watchNow;
        return true;
    }

    private void applyRequestState() {
        triedMimes.clear();
        currentMime = req.mime;
        skipSegs = Episodes.parseSkip(req.skipSegments);
        titleView.setText(req.displayTitle());
        nextTopBtn.setVisibility(episodes.next(epIndex) != null ? View.VISIBLE : View.GONE);
        skipIntroBtn.setVisibility(View.GONE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        hideSystemUi();
        if (gate) return;                                   // coming back from the AdMob ad: its callback starts the film
        if (deferredBegin && player != null) { deferredBegin = false; beginPlaying(); return; }
        if (req != null && player == null && !ended && autoNextEp == null) startPlayback();
    }

    @Override
    protected void onStop() {
        visible = false;
        if (gate && !isFinishing()) { super.onStop(); return; }   // the AdMob ad covers this screen: keep the prepared (paused) player
        savePosition();
        if (player != null) wantPlay = player.getPlayWhenReady();
        if (lastPosition > 0L) resumeMs = lastPosition;
        cancelAutoNext();
        dismissAds();
        releasePlayer();
        setResultNow();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        unregisterBack();
        dismissAds();
        releasePlayer();
        AdsClient.flush(this);
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    // ------------------------------------------------------------------ back (predictive back safe)

    private void registerBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                android.window.OnBackInvokedCallback cb = new android.window.OnBackInvokedCallback() {
                    @Override public void onBackInvoked() { handleBack(); }
                };
                getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
                backCallback = cb;
            } catch (Throwable ignored) { }
        }
    }

    private void unregisterBack() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            try {
                getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCallback);
            } catch (Throwable ignored) { }
            backCallback = null;
        }
    }

    /** Android 12 and older (and gesture-less devices) still deliver Back here. */
    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void handleBack() {
        if (autoNextEp != null) { cancelAutoNext(); finish(); return; }
        if (activeAd != null) {
            activeAd.skipIfAllowed();      // ignored only during the first seconds of an ad
            return;
        }
        finish();
    }

    // ------------------------------------------------------------------ PiP

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        enterPip();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        pipMode = isInPictureInPictureMode;
        playerView.setUseController(!pipMode);
        topBar.setVisibility(View.GONE);
        skipIntroBtn.setVisibility(View.GONE);
        if (pipMode) hidePauseAd();
    }

    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26 || player == null || adShowing || !player.isPlaying()) return;
        if (req != null && req.restricted) return;       // Picture-in-Picture is a Cigram Plus service (silent: this also runs on Home)
        try {
            Rational ratio = videoW > 0 && videoH > 0 ? new Rational(Math.min(videoW, 2390), Math.max(1, Math.min(videoH, 2390)))
                    : new Rational(16, 9);
            enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(ratio).build());
        } catch (Throwable ignored) { }
    }

    // ================================================================== UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);

        playerView = new StyledPlayerView(this);
        playerView.setShowSubtitleButton(true);
        playerView.setControllerShowTimeoutMs(3500);
        playerView.setKeepContentOnPlayerReset(true);
        playerView.setShutterBackgroundColor(0x00000000);
        playerView.setControllerVisibilityListener(new StyledPlayerView.ControllerVisibilityListener() {
            @Override public void onVisibilityChanged(int visibility) {
                topBar.setVisibility(visibility == View.VISIBLE && !adShowing && !pipMode && autoNextEp == null
                        ? View.VISIBLE : View.GONE);
            }
        });
        root.addView(playerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        topBar.setBackgroundColor(0x66000000);
        topBar.setVisibility(View.GONE);

        topBar.addView(barButton("✕", "إغلاق", new View.OnClickListener() {
            @Override public void onClick(View v) { handleBack(); }
        }));
        titleView = Ui.text(this, "", 16f, 0xFFFFFFFF, true);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = Ui.dp(this, 8);
        tl.rightMargin = Ui.dp(this, 8);
        topBar.addView(titleView, tl);
        nextTopBtn = barButton("⏭", "الحلقة التالية", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Episodes.Ep n = episodes.next(epIndex);
                if (n != null) playEpisode(n, true);
            }
        });
        nextTopBtn.setVisibility(View.GONE);
        topBar.addView(nextTopBtn);
        topBar.addView(barButton("CC+", "إضافة ترجمة من الجهاز", new View.OnClickListener() {
            @Override public void onClick(View v) { pickSubtitle(); }
        }));
        topBar.addView(barButton("⟳", "تدوير الشاشة", new View.OnClickListener() {
            @Override public void onClick(View v) { toggleOrientation(); }
        }));
        root.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        skipIntroBtn = Ui.button(this, "تخطي المقدمة", 0xE6FFFFFF, 0xFF0B0F14);
        skipIntroBtn.setVisibility(View.GONE);
        skipIntroBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                long pos = player == null ? 0L : player.getCurrentPosition();
                for (long[] s : skipSegs) {
                    if (pos >= s[0] && pos < s[1] && player != null) { player.seekTo(s[1]); break; }
                }
                skipIntroBtn.setVisibility(View.GONE);
            }
        });
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        sl.bottomMargin = Ui.dp(this, 96);
        sl.rightMargin = Ui.dp(this, 24);
        root.addView(skipIntroBtn, sl);

        autoNextBox = new LinearLayout(this);
        autoNextBox.setOrientation(LinearLayout.VERTICAL);
        autoNextBox.setGravity(Gravity.CENTER);
        autoNextBox.setBackgroundColor(0xE6000000);
        autoNextBox.setClickable(true);
        autoNextBox.setVisibility(View.GONE);
        autoNextText = Ui.text(this, "", 18f, 0xFFFFFFFF, true);
        autoNextText.setGravity(Gravity.CENTER);
        autoNextBox.addView(autoNextText);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        TextView now = Ui.button(this, "تشغيل الآن", 0xFFFFB300, 0xFF0B0F14);
        now.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Episodes.Ep n = autoNextEp;
                cancelAutoNext();
                if (n != null) playEpisode(n, true);
            }
        });
        TextView cancel = Ui.button(this, "إلغاء", 0x33FFFFFF, 0xFFFFFFFF);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cancelAutoNext(); finish(); }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.setMargins(Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8), 0);
        row.addView(now, bl);
        row.addView(cancel, bl);
        autoNextBox.addView(row);
        root.addView(autoNextBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private TextView barButton(String glyph, String description, View.OnClickListener l) {
        TextView t = Ui.text(this, glyph, 18f, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(this, 48));
        t.setMinHeight(Ui.dp(this, 48));
        t.setContentDescription(description);
        t.setOnClickListener(l);
        return t;
    }

    private void hideSystemUi() {
        try {
            Window w = getWindow();
            if (Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false);
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ orientation

    private void toggleOrientation() {
        orientationChosen = true;
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    /** Phones: lay the screen out to match the video the first time its size is known. Large screens: leave it alone. */
    private void orientForVideo(int w, int h) {
        if (orientationChosen || w <= 0 || h <= 0) return;
        orientationChosen = true;
        if (getResources().getConfiguration().smallestScreenWidthDp >= 600) return;
        setRequestedOrientation(w >= h ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
    }

    // ================================================================== subtitles from the device

    private void pickSubtitle() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_SUBTITLE);
        } catch (Throwable t) {
            Ui.toast(this, "لا يوجد تطبيق لاختيار الملفات.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SUBTITLE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = displayName(uri);
        String lower = name.toLowerCase(java.util.Locale.US);
        if (!(lower.endsWith(".srt") || lower.endsWith(".vtt") || lower.endsWith(".ass") || lower.endsWith(".ssa")
                || lower.endsWith(".ttml") || lower.endsWith(".xml") || lower.endsWith(".dfxp"))) {
            Ui.toast(this, "اختر ملف ترجمة (SRT أو VTT أو ASS).");
            return;
        }
        extraSubtitle = uri;
        extraSubtitleMime = Streams.subtitleMime(name);
        if (player != null) {
            resumeMs = Math.max(0L, player.getCurrentPosition());
            wantPlay = player.getPlayWhenReady();
            createPlayer();
            beginPlaying();
        }
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment();
    }

    // ================================================================== player

    private void startPlayback() {
        if (req == null) return;
        breaksReady = false;
        breaks = new long[0];
        nextBreak = 0;
        preloadedForBreak = false;
        createPlayer();
        final boolean remote = Streams.isRemote(req.url);
        final boolean admobAd = AppConfig.ADMOB_ENABLED && watchNowAd && req.adsEnabled && remote;
        watchNowAd = false;                                  // one ad per press
        final boolean wantsOwn = AppConfig.OWN_ADS_ENABLED && req.adsEnabled && remote;
        Runnable go = new Runnable() {
            @Override public void run() {
                if (admobAd && !prerollDone) {
                    prerollDone = true;
                    gate = true;
                    AdMobAds.showWatchNowAd(PlayActivity.this, new Runnable() {
                        @Override public void run() {
                            gate = false;
                            if (visible) beginPlaying(); else deferredBegin = true;
                        }
                    });
                } else if (!prerollDone && wantsOwn && resumeMs < 30000L) {
                    prerollDone = true;
                    tryPreroll();
                } else {
                    prerollDone = true;
                    beginPlaying();
                }
            }
        };
        if (wantsOwn && !Privacy.decided(this)) askConsent(go); else go.run();
    }

    /** Asked once, before the first ad. Both answers continue to playback. */
    private void askConsent(final Runnable next) {
        if (!Ui.alive(this)) { next.run(); return; }
        try {
            new AlertDialog.Builder(this)
                    .setTitle("الإعلانات والخصوصية")
                    .setMessage("يعرض هذا المشغل إعلانات من خوادم سيكرام.\n\n"
                            + "هل تسمح بإرسال معرّف عشوائي خاص بهذا التثبيت مع عدد مرات ظهور الإعلانات والنقرات؟ "
                            + "المعرّف لا يرتبط بحسابك ولا بمعرّف الإعلانات في Google.\n\n"
                            + "إن رفضت: ستظهر الإعلانات نفسها دون أي معرّف أو إحصاءات. يمكنك التغيير لاحقاً من «الخصوصية والإعلانات».")
                    .setCancelable(false)
                    .setPositiveButton("موافق", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { Privacy.set(PlayActivity.this, true); next.run(); }
                    })
                    .setNegativeButton("لا، شكراً", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { Privacy.set(PlayActivity.this, false); next.run(); }
                    })
                    .show();
        } catch (Throwable t) {
            next.run();
        }
    }

    private void createPlayer() {
        releasePlayer();

        Map<String, String> headers = new HashMap<String, String>(req.headers);
        String ua = Streams.UA;
        for (Map.Entry<String, String> e : new ArrayList<Map.Entry<String, String>>(headers.entrySet())) {
            if ("user-agent".equalsIgnoreCase(e.getKey())) {
                ua = e.getValue();
                headers.remove(e.getKey());
            }
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setDefaultRequestProperties(headers);

        DefaultTrackSelector selector = new DefaultTrackSelector(this);
        try {
            selector.setParameters(selector.buildUponParameters()
                    .setPreferredAudioLanguages("ar", "en")
                    .setPreferredTextLanguage("ar"));
            if (req.restricted) {                       // Cigram Plus: 1080p - 4K is a Plus service
                selector.setParameters(selector.buildUponParameters().setMaxVideoSize(Integer.MAX_VALUE, 720));
            }
        } catch (Throwable ignored) { }

        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(20000, 75000, 700, 1000)
                .setBackBuffer(60000, true)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        ExoPlayer p = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this).setEnableDecoderFallback(true))
                .setTrackSelector(selector)
                .setLoadControl(load)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http)))
                .setSeekBackIncrementMs(10000L)
                .setSeekForwardIncrementMs(10000L)
                .build();
        p.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        p.setHandleAudioBecomingNoisy(true);
        p.addListener(playerListener);

        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(req.url));
        if (currentMime != null) item.setMimeType(currentMime);
        if (req.displayTitle().length() > 0) item.setMediaMetadata(new MediaMetadata.Builder().setTitle(req.displayTitle()).build());

        List<MediaItem.SubtitleConfiguration> subs = new ArrayList<MediaItem.SubtitleConfiguration>();
        if (extraSubtitle != null) {
            subs.add(new MediaItem.SubtitleConfiguration.Builder(extraSubtitle)
                    .setMimeType(extraSubtitleMime)
                    .setLanguage("ar")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build());
        } else if (req.subtitle.length() > 0) {
            subs.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(Streams.cleanUrl(req.subtitle)))
                    .setMimeType(Streams.subtitleMime(req.subtitle))
                    .setLanguage("ar")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build());
        }
        if (!subs.isEmpty()) item.setSubtitleConfigurations(subs);

        p.setMediaItem(item.build());
        if (resumeMs > 0L) p.seekTo(resumeMs);
        p.setPlayWhenReady(false);
        p.prepare();

        player = p;
        playerView.setPlayer(p);
        lastSaveAt = System.currentTimeMillis();
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, TICK_MS);
    }

    private void releasePlayer() {
        ui.removeCallbacks(tick);
        ui.removeCallbacks(showPauseAd);
        ui.removeCallbacks(retryPrepare);
        hidePauseAd();
        ExoPlayer p = player;
        player = null;
        if (p == null) return;
        try { playerView.setPlayer(null); } catch (Throwable ignored) { }
        try { p.removeListener(playerListener); } catch (Throwable ignored) { }
        try { p.release(); } catch (Throwable ignored) { }
    }

    private void beginPlaying() {
        if (player != null) player.setPlayWhenReady(wantPlay);
    }

    private final Player.Listener playerListener = new Player.Listener() {
        @Override public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY) retryCount = 0;
            if (state == Player.STATE_ENDED && !adShowing) onEnded();
        }

        @Override public void onVideoSizeChanged(VideoSize size) {
            videoW = size.width;
            videoH = size.height;
            orientForVideo(videoW, videoH);
        }

        @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            if (playWhenReady) {
                ui.removeCallbacks(showPauseAd);
                hidePauseAd();
            } else if (!adShowing && !pipMode && !ended) {
                ui.removeCallbacks(showPauseAd);
                ui.postDelayed(showPauseAd, PAUSE_AD_DELAY_MS);
            }
        }

        @Override public void onPlayerError(PlaybackException error) {
            handleError(error);
        }
    };

    // ------------------------------------------------------------------ end / next episode

    private void onEnded() {
        if (ended) return;
        ended = true;
        ResumeStore.clear(this, req.key());
        Episodes.Ep nx = episodes.next(epIndex);
        if (nx != null && Ui.alive(this)) { showAutoNext(nx); return; }
        setResultNow();
        finish();
    }

    private void showAutoNext(Episodes.Ep nx) {
        autoNextEp = nx;
        autoNextLeft = AUTO_NEXT_SECONDS;
        topBar.setVisibility(View.GONE);
        playerView.hideController();
        autoNextBox.setVisibility(View.VISIBLE);
        ui.removeCallbacks(autoNextTick);
        autoNextTick.run();
    }

    private final Runnable autoNextTick = new Runnable() {
        @Override public void run() {
            Episodes.Ep n = autoNextEp;
            if (n == null) return;
            if (autoNextLeft <= 0) {
                cancelAutoNext();
                playEpisode(n, false);      // automatic after the countdown: no press, no ad
                return;
            }
            String name = n.title.length() > 0 ? n.title : ("الحلقة " + n.episode);
            autoNextText.setText("التالي: " + name + "\nخلال " + autoNextLeft + " ث");
            autoNextLeft--;
            ui.postDelayed(this, 1000L);
        }
    };

    private void cancelAutoNext() {
        autoNextEp = null;
        ui.removeCallbacks(autoNextTick);
        autoNextBox.setVisibility(View.GONE);
    }

    private void playEpisode(Episodes.Ep ep, boolean pressed) {
        String raw = ep.video;
        String clean = Streams.cleanUrl(raw);
        if (!Streams.isAllowedScheme(clean)) return;
        releasePlayer();
        dismissAds();
        req.url = clean;
        req.headers = new HashMap<String, String>(Streams.headersOf(raw));
        PlayerIntent.sanitize(req.headers);
        req.mime = Streams.mimeOf(clean);
        req.title = ep.title;
        req.season = ep.season;
        req.episode = ep.episode;
        req.subtitle = ep.subtitle.length() > 0 && Streams.isAllowedScheme(Streams.cleanUrl(ep.subtitle)) ? ep.subtitle : "";
        req.skipSegments = ep.skip;
        req.resumeMs = 0L;
        epIndex = episodes.list.indexOf(ep);
        applyRequestState();
        resumeMs = ResumeStore.get(this, req.key());
        extraSubtitle = null;
        wantPlay = true;
        ended = false;
        retryCount = 0;
        prerollDone = false;
        watchNowAd = pressed;
        lastPosition = 0L;
        lastDuration = 0L;
        startPlayback();
    }

    // ------------------------------------------------------------------ errors

    private final Runnable retryPrepare = new Runnable() {
        @Override public void run() {
            if (player != null) player.prepare();
        }
    };

    private static boolean isNetworkError(int c) {
        return c == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || c == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT;
    }

    private void handleError(PlaybackException e) {
        int c = e.errorCode;
        boolean typeError = c == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || c == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || c == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                || c == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
        if (typeError && Streams.isRemote(req.url)) {
            if (currentMime != null && !triedMimes.contains(currentMime)) triedMimes.add(currentMime);
            for (String m : Streams.FALLBACK) {
                if (!triedMimes.contains(m)) {
                    currentMime = m;
                    triedMimes.add(m);
                    resumeMs = player == null ? resumeMs : Math.max(0L, player.getCurrentPosition());
                    createPlayer();
                    beginPlaying();
                    return;
                }
            }
        }
        if (isNetworkError(c) && retryCount < MAX_NETWORK_RETRIES && player != null) {
            long delay = 2000L << retryCount;     // 2 s, 4 s, 8 s
            retryCount++;
            Ui.toast(this, "انقطع الاتصال. جارٍ إعادة المحاولة…");
            ui.removeCallbacks(retryPrepare);
            ui.postDelayed(retryPrepare, delay);
            return;
        }
        showErrorDialog(messageFor(e));
    }

    private String messageFor(PlaybackException e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof HttpDataSource.InvalidResponseCodeException) {
                int code = ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
                if (code == 401 || code == 403) return "الرابط منتهي الصلاحية أو غير مصرّح بالوصول إليه (" + code + ").";
                if (code == 404 || code == 410) return "لم يعد الفيديو متاحاً على هذا الرابط (" + code + ").";
                if (code >= 500) return "خادم الفيديو غير متاح حالياً (" + code + "). حاول لاحقاً.";
                return "رفض الخادم الطلب (" + code + ").";
            }
            t = t.getCause();
        }
        int c = e.errorCode;
        if (c / 1000 == 6) return "هذا المحتوى محميّ بتقنية لا يدعمها هذا المشغل.";
        if (isNetworkError(c)) return "تعذّر الاتصال بالإنترنت. تحقق من الشبكة ثم حاول مجدداً.";
        if (c == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND || c == PlaybackException.ERROR_CODE_IO_NO_PERMISSION) {
            return "تعذّر فتح الملف. قد يكون حُذف أو لم يعد مسموحاً بالوصول إليه.";
        }
        if (c == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || c == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                || c == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                || c == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || c == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED) {
            return "صيغة الفيديو غير مدعومة على هذا الجهاز.";
        }
        return "تعذّر تشغيل الفيديو.";
    }

    private void showErrorDialog(String message) {
        if (!Ui.alive(this)) return;
        try {
            new AlertDialog.Builder(this)
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton("إعادة المحاولة", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            if (player != null) resumeMs = Math.max(0L, player.getCurrentPosition());
                            triedMimes.clear();
                            retryCount = 0;
                            currentMime = req.mime;
                            createPlayer();
                            beginPlaying();
                        }
                    })
                    .setNegativeButton("إغلاق", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { finish(); }
                    })
                    .show();
        } catch (Throwable ignored) { }
    }

    // ================================================================== position / breaks / skip intro

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            ExoPlayer p = player;
            if (p == null) return;
            try {
                long pos = Math.max(0L, p.getCurrentPosition());
                long dur = p.getDuration();
                if (!adShowing) {
                    lastPosition = pos;
                    lastDuration = dur == C.TIME_UNSET ? 0L : dur;
                    updateSkipIntro(pos);
                }
                if (!breaksReady && p.getPlaybackState() == Player.STATE_READY && dur != C.TIME_UNSET && dur > 0L
                        && AppConfig.OWN_ADS_ENABLED && req.adsEnabled && Streams.isRemote(req.url) && Privacy.decided(PlayActivity.this)) {
                    computeBreaks(dur, pos);
                }
                if (!adShowing && p.isPlaying()) handleBreaks(pos);
                long now = System.currentTimeMillis();
                if (now - lastSaveAt >= SAVE_EVERY_MS) {
                    lastSaveAt = now;
                    savePosition();
                }
            } catch (Throwable ignored) { }
            ui.postDelayed(this, TICK_MS);
        }
    };

    private void updateSkipIntro(long pos) {
        boolean inside = false;
        for (long[] s : skipSegs) if (pos >= s[0] && pos < s[1]) { inside = true; break; }
        boolean show = inside && !pipMode && autoNextEp == null;
        skipIntroBtn.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void computeBreaks(long duration, long position) {
        breaksReady = true;
        breaks = AdBreaks.forItem(req.key(), duration);
        nextBreak = 0;
        while (nextBreak < breaks.length && breaks[nextBreak] <= position) nextBreak++;   // already passed (resume)
        preloadedForBreak = false;
    }

    private void handleBreaks(long pos) {
        while (nextBreak < breaks.length && pos >= breaks[nextBreak] + BREAK_GRACE_MS) nextBreak++;
        if (nextBreak >= breaks.length) return;

        long at = breaks[nextBreak];
        if (!preloadedForBreak && pos >= at - PRELOAD_BEFORE_BREAK_MS) {
            preloadedForBreak = true;
            AdsClient.load(this, "splash", new AdsClient.Cb() {
                @Override public void onAds(List<AdsClient.Ad> eligible) {
                    for (AdsClient.Ad a : eligible) ImageLoader.prefetch(a.posterUrl(), 1600);
                }
            });
        }
        if (pos < at) return;

        nextBreak++;
        preloadedForBreak = false;
        if (!AdsClient.mayShowAd(this)) return;
        List<AdsClient.Ad> hit = AdsClient.cached(this, "splash");
        if (hit == null || hit.isEmpty()) return;     // never stall the film waiting for an ad
        final boolean wasPlaying = player != null && player.getPlayWhenReady();
        showAd(AdsClient.order(this, "splash", hit).get(0), "splash", new Runnable() {
            @Override public void run() {
                if (wasPlaying && player != null) player.setPlayWhenReady(true);
            }
        });
    }

    private void savePosition() {
        if (req == null || ended) return;
        long pos = lastPosition, dur = lastDuration;
        if (player != null) {
            try {
                if (!adShowing) pos = Math.max(0L, player.getCurrentPosition());
                long d = player.getDuration();
                if (d != C.TIME_UNSET && d > 0L) dur = d;
            } catch (Throwable ignored) { }
        }
        lastPosition = pos;
        lastDuration = dur;
        // The link is stored for "continue watching" only when it needs no request headers (they may hold tokens/cookies).
        String storable = req.headers.isEmpty() ? req.url : "";
        if (pos > 0L) ResumeStore.put(this, req.key(), pos, dur, req.displayTitle(), storable);
    }

    private void setResultNow() {
        Intent r = new Intent();
        r.putExtra("position_ms", lastPosition);
        r.putExtra("duration_ms", lastDuration);
        r.putExtra("ended", ended);
        setResult(RESULT_OK, r);
    }

    // ================================================================== ads

    /** Pre-roll: use the local copy at once; otherwise wait at most PREROLL_WAIT_MS, then just play. */
    private void tryPreroll() {
        if (!AdsClient.mayShowAd(this)) { beginPlaying(); return; }
        final boolean[] settled = {false};
        final Runnable timeout = new Runnable() {
            @Override public void run() {
                if (settled[0]) return;
                settled[0] = true;
                beginPlaying();
            }
        };

        List<AdsClient.Ad> hit = AdsClient.cached(this, "splash");
        if (hit != null && !hit.isEmpty()) {
            settled[0] = true;
            showAd(AdsClient.order(this, "splash", hit).get(0), "splash", new Runnable() {
                @Override public void run() { beginPlaying(); }
            });
            AdsClient.load(this, "splash", new AdsClient.Cb() {      // refresh the copy for the next break
                @Override public void onAds(List<AdsClient.Ad> e) { }
            });
            return;
        }

        ui.postDelayed(timeout, PREROLL_WAIT_MS);
        AdsClient.load(this, "splash", new AdsClient.Cb() {
            @Override public void onAds(List<AdsClient.Ad> eligible) {
                if (settled[0]) return;
                settled[0] = true;
                ui.removeCallbacks(timeout);
                if (eligible == null || eligible.isEmpty() || !Ui.alive(PlayActivity.this)) { beginPlaying(); return; }
                showAd(AdsClient.order(PlayActivity.this, "splash", eligible).get(0), "splash", new Runnable() {
                    @Override public void run() { beginPlaying(); }
                });
            }
        });
    }

    private void showAd(final AdsClient.Ad ad, String slot, final Runnable whenDone) {
        if (!Ui.alive(this) || adShowing || pipMode) { whenDone.run(); return; }
        hidePauseAd();
        ui.removeCallbacks(showPauseAd);
        adShowing = true;
        if (player != null) player.setPlayWhenReady(false);
        playerView.hideController();
        topBar.setVisibility(View.GONE);
        skipIntroBtn.setVisibility(View.GONE);
        AdsClient.advance(this, slot);
        AdsClient.markShown(this);
        activeAd = AdView.show(this, root, ad, new AdView.Listener() {
            @Override public void onClosed(boolean skipped) {
                activeAd = null;
                adShowing = false;
                if (Ui.alive(PlayActivity.this)) whenDone.run();
            }
        });
    }

    private void dismissAds() {
        hidePauseAd();
        AdView a = activeAd;
        activeAd = null;
        if (a != null) a.closeQuietly();
        adShowing = false;
    }

    private final Runnable showPauseAd = new Runnable() {
        @Override public void run() {
            final ExoPlayer p = player;
            if (!AppConfig.OWN_ADS_ENABLED || p == null || p.getPlayWhenReady() || adShowing || pipMode || pauseAd != null || !req.adsEnabled
                    || !Streams.isRemote(req.url) || !Privacy.decided(PlayActivity.this)) return;
            long now = System.currentTimeMillis();
            if (now - lastPauseAdAt < PAUSE_AD_GAP_MS) return;
            AdsClient.load(PlayActivity.this, "sticky", new AdsClient.Cb() {
                @Override public void onAds(List<AdsClient.Ad> eligible) {
                    ExoPlayer q = player;
                    if (q == null || q.getPlayWhenReady() || adShowing || pipMode || pauseAd != null
                            || eligible == null || eligible.isEmpty() || !Ui.alive(PlayActivity.this)) return;
                    lastPauseAdAt = System.currentTimeMillis();
                    AdsClient.Ad ad = AdsClient.order(PlayActivity.this, "sticky", eligible).get(0);
                    AdsClient.advance(PlayActivity.this, "sticky");
                    pauseAd = PauseAd.show(PlayActivity.this, root, ad);
                }
            });
        }
    };

    private void hidePauseAd() {
        PauseAd a = pauseAd;
        pauseAd = null;
        if (a != null) a.dismiss();
    }
}
