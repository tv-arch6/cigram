package com.Cigram.vid;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;

/**
 * v10.63 — the small player at the top of the channels page.
 *
 * It starts EMPTY: no decoder, no network, only a calm placeholder. The ExoPlayer is built the first
 * time a channel is chosen, reused for every channel after it (zapping does not rebuild anything),
 * limited to 720p (the picture is a third of the screen; full quality is one tap away in the full
 * screen player) and released whenever the page is not on screen, so it never holds a hardware
 * decoder that the movie player may need.
 *
 * Like the full screen player it has NO Player.Listener: the state is polled twice a second.
 */
final class CigramChannelPlayer {

    /** What the picture is doing right now, for the "now playing" line of the page. */
    static final int ST_IDLE = 0, ST_LOADING = 1, ST_PLAYING = 2, ST_PAUSED = 3, ST_ERROR = 4;

    interface StatusListener {
        /** Main thread; called only when the status or its message changed. */
        void onStatus(int status, String message);
    }

    interface Host {
        /** The full screen button: open the channel in the full player. */
        void onFullscreen();
        /** +1 = the next channel of the list, -1 = the previous one. */
        void onStep(int delta);
    }

    private static final String TAG = "CigramChannelPlayer";
    private static final int RED = 0xFFE21B14;
    private static final int MUTED = 0xFF8E8E94;
    private static final long WATCH_MS = 500L;
    private static final long HIDE_MS = 3200L;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private final Activity a;
    private final FrameLayout stage;
    private final Host host;
    private StatusListener statusListener;
    private int reportedStatus = -1;
    private String reportedMessage = "";
    private TextView emptyHint;

    private PlayerView view;
    private ExoPlayer player;
    private DefaultTrackSelector selector;

    private final LinearLayout empty;
    private final FrameLayout controls;
    private final CigramHubUi.LogoView logo;
    private final TextView title;
    private final View livePill;
    private final CigramHubUi.Icon playIcon;
    private final CigramHubUi.Icon muteIcon;
    private final CigramHubUi.Icon fullIcon;
    private final View nextBtn;
    private final View prevBtn;
    private final ProgressBar spinner;
    private final TextView status;
    private final LinearLayout errorBox;
    private final TextView errorText;
    private TextView reportBtn;

    private CigramChannelStore.Ch channel;
    private String playingUrl = "";
    private String mime;
    private int typeTry = -1;
    private int retries;
    private int lastState = -1;
    private boolean lastPlaying;
    private Object lastError;
    private boolean muted;
    private boolean controlsShown;
    private boolean destroyed;
    private boolean compact;

    private final Runnable watch = new Runnable() {
        @Override public void run() { onWatch(); }
    };
    private final Runnable hide = new Runnable() {
        @Override public void run() { showControls(false); }
    };

    CigramChannelPlayer(Activity activity, FrameLayout stageView, Host hostCallbacks) {
        a = activity;
        stage = stageView;
        host = hostCallbacks;

        final boolean light = CigramTheme.isLight(a);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                light ? new int[]{0xFFFBFCFD, 0xFFE3E8EC} : new int[]{0xFF1B1B1E, 0xFF0C0C0E});
        stage.setBackground(bg);

        // ---- the empty state
        empty = new LinearLayout(a);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        // the app's own mark, white and see-through (ink on the light page), instead of a television glyph
        android.widget.ImageView mark = new android.widget.ImageView(a);
        mark.setImageResource(R.drawable.cigram_mark);
        mark.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        mark.setColorFilter(light ? 0xFF111418 : 0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
        mark.setAlpha(0.72f);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        empty.addView(mark, new LinearLayout.LayoutParams(dp(58), dp(58)));
        TextView t1 = CigramUI.label(a, "اختر قناة لبدء المشاهدة", 15f, light ? 0xFF111418 : 0xFFDCE6EA, true);
        t1.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(-2, -2);
        l1.topMargin = dp(10);
        empty.addView(t1, l1);
        TextView t2 = CigramUI.label(a, "ابدأ بباقة من القائمة في الأسفل", 12f, light ? 0xFF5F6B73 : MUTED, false);
        emptyHint = t2;
        t2.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(-2, -2);
        l2.topMargin = dp(3);
        empty.addView(t2, l2);
        stage.addView(empty, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // ---- spinner + status
        LinearLayout wait = new LinearLayout(a);
        wait.setOrientation(LinearLayout.VERTICAL);
        wait.setGravity(Gravity.CENTER);
        spinner = new ProgressBar(a);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(RED));
        wait.addView(spinner, new LinearLayout.LayoutParams(dp(34), dp(34)));
        status = CigramUI.label(a, "", 12f, 0xFFDCE6EA, false);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ls = new LinearLayout.LayoutParams(-2, -2);
        ls.topMargin = dp(6);
        wait.addView(status, ls);
        wait.setVisibility(View.GONE);
        waitBox = wait;
        stage.addView(wait, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        wait.setTag(CigramTheme.SKIP_TAG);

        // ---- controls (fade in on tap)
        controls = new FrameLayout(a);
        controls.setTag(CigramTheme.SKIP_TAG);   // drawn over the video: never re-coloured
        controls.setVisibility(View.GONE);
        controls.setAlpha(0f);

        View topShade = new View(a);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB0000000, 0x00000000}));
        controls.addView(topShade, new FrameLayout.LayoutParams(-1, dp(70), Gravity.TOP));
        View bottomShade = new View(a);
        bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xA0000000, 0x00000000}));
        controls.addView(bottomShade, new FrameLayout.LayoutParams(-1, dp(60), Gravity.BOTTOM));

        // top right: logo + name (physical right, the app has no RTL support)
        LinearLayout nameRow = new LinearLayout(a);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        title = CigramUI.label(a, "", 14f, Color.WHITE, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setShadowLayer(dp(3), 0f, dp(1), 0xB0000000);
        nameRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        logo = new CigramHubUi.LogoView(a, false, dp(8));
        logo.setTile(0x4D000000);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(32), dp(32));
        ll.leftMargin = dp(8);
        nameRow.addView(logo, ll);
        FrameLayout.LayoutParams nameLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.RIGHT);
        nameLp.topMargin = dp(10);
        nameLp.rightMargin = dp(12);
        nameLp.leftMargin = dp(86);
        controls.addView(nameRow, nameLp);

        // top left: LIVE pill
        LinearLayout pill = new LinearLayout(a);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pillBg = new GradientDrawable();      // iOS glass capsule
        pillBg.setColor(0x47FFFFFF);
        pillBg.setCornerRadius(dp(12));
        pillBg.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
        pill.setBackground(pillBg);
        pill.setPadding(dp(8), dp(3), dp(9), dp(3));
        View dot = new View(a);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(RED);
        dot.setBackground(dotBg);
        pill.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
        TextView live = CigramUI.label(a, "مباشر", 11f, Color.WHITE, true);
        live.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lt = new LinearLayout.LayoutParams(-2, -2);
        lt.leftMargin = dp(5);
        pill.addView(live, lt);
        livePill = pill;
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        pillLp.topMargin = dp(12);
        pillLp.leftMargin = dp(12);
        controls.addView(pill, pillLp);

        // centre: next - play/pause - previous (a zapper, like the full screen player)
        LinearLayout centre = new LinearLayout(a);
        centre.setOrientation(LinearLayout.HORIZONTAL);
        centre.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        centre.setGravity(Gravity.CENTER);
        CigramHubUi.Icon nextIcon = new CigramHubUi.Icon(a, CigramHubUi.NEXT, Color.WHITE);
        nextBtn = circleButton(nextIcon, 42, 19, 0x38FFFFFF, "القناة التالية", new View.OnClickListener() {
            @Override public void onClick(View v) { keepControls(); host.onStep(1); }
        });
        playIcon = new CigramHubUi.Icon(a, CigramHubUi.PAUSE, 0xFF000000);
        View playBtn = circleButton(playIcon, 58, 24, 0xFFFFFFFF, "تشغيل / إيقاف", new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });
        CigramHubUi.Icon prevIcon = new CigramHubUi.Icon(a, CigramHubUi.PREV, Color.WHITE);
        prevBtn = circleButton(prevIcon, 42, 19, 0x38FFFFFF, "القناة السابقة", new View.OnClickListener() {
            @Override public void onClick(View v) { keepControls(); host.onStep(-1); }
        });
        centre.addView(nextBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(58), dp(58));
        pl.leftMargin = dp(22);
        pl.rightMargin = dp(22);
        centre.addView(playBtn, pl);
        centre.addView(prevBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));
        controls.addView(centre, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // bottom left: sound + full screen
        LinearLayout bottom = new LinearLayout(a);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bottomBg = new GradientDrawable();    // one glass capsule for sound + full screen
        bottomBg.setColor(0x38FFFFFF);
        bottomBg.setCornerRadius(dp(21));
        bottomBg.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
        bottom.setBackground(bottomBg);
        muteIcon = new CigramHubUi.Icon(a, CigramHubUi.SPEAKER, Color.WHITE);
        muteIcon.setShadow(true);
        View muteBtn = tapTarget(muteIcon, 22, "الصوت", new View.OnClickListener() {
            @Override public void onClick(View v) { toggleMute(); }
        });
        fullIcon = new CigramHubUi.Icon(a, CigramHubUi.EXPAND, Color.WHITE);
        fullIcon.setShadow(true);
        fullBtn = tapTarget(fullIcon, 22, "ملء الشاشة", new View.OnClickListener() {
            @Override public void onClick(View v) { host.onFullscreen(); }
        });
        bottom.addView(fullBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));
        bottom.addView(muteBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
        bl.leftMargin = dp(4);
        bl.bottomMargin = dp(2);
        controls.addView(bottom, bl);
        stage.addView(controls, new FrameLayout.LayoutParams(-1, -1));

        // ---- error box (above the controls so its button always works)
        errorBox = new LinearLayout(a);
        errorBox.setTag(CigramTheme.SKIP_TAG);
        errorBox.setOrientation(LinearLayout.VERTICAL);
        errorBox.setGravity(Gravity.CENTER);
        errorBox.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable errorBg = new GradientDrawable();
        errorBg.setColor(0xE6000000);
        errorBg.setCornerRadius(dp(20));
        errorBg.setStroke(Math.max(1, dp(1)), 0x40FFFFFF);
        errorBox.setBackground(errorBg);
        errorText = CigramUI.label(a, "", 13.5f, 0xFFFFFFFF, false);
        errorText.setGravity(Gravity.CENTER);
        errorText.setLineSpacing(0f, 1.25f);
        errorBox.addView(errorText, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout errorActions = new LinearLayout(a);
        errorActions.setOrientation(LinearLayout.HORIZONTAL);
        errorActions.setGravity(Gravity.CENTER);
        TextView retry = CigramUI.label(a, "إعادة المحاولة", 13f, 0xFF000000, true);
        retry.setGravity(Gravity.CENTER);
        retry.setBackground(CigramUI.round(0xFFFFFFFF, 16, a));
        retry.setPadding(dp(16), dp(8), dp(16), dp(8));
        retry.setClickable(true);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (channel != null) play(channel);
            }
        });
        reportBtn = CigramUI.label(a, "بلّغ المدير", 13f, 0xFFFFFFFF, true);
        reportBtn.setGravity(Gravity.CENTER);
        GradientDrawable reportBg = new GradientDrawable();
        reportBg.setColor(0x1FFFFFFF);
        reportBg.setCornerRadius(dp(16));
        reportBg.setStroke(Math.max(1, dp(1)), 0x80FFFFFF);
        reportBtn.setBackground(reportBg);
        reportBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        reportBtn.setClickable(true);
        reportBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (channel == null) return;
                CigramChannelReports.report(a, channel);
                reportBtn.setText("تم إبلاغ المدير");
                reportBtn.setEnabled(false);
                reportBtn.setAlpha(0.6f);
            }
        });
        LinearLayout.LayoutParams rb = new LinearLayout.LayoutParams(-2, -2);
        rb.rightMargin = dp(8);
        errorActions.addView(reportBtn, rb);
        errorActions.addView(retry, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams lr = new LinearLayout.LayoutParams(-2, -2);
        lr.topMargin = dp(12);
        errorBox.addView(errorActions, lr);
        errorBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        el.leftMargin = dp(24);
        el.rightMargin = dp(24);
        stage.addView(errorBox, el);

        stage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (channel == null || errorBox.getVisibility() == View.VISIBLE) return;
                showControls(!controlsShown);
            }
        });
    }

    private LinearLayout waitBox;
    private View fullBtn;

    // ================================================================== public

    CigramChannelStore.Ch current() {
        return channel;
    }

    /** Replaces the second line of the empty state (the old text names the package page). */
    void setEmptyHint(String text) {
        if (emptyHint != null && text != null) emptyHint.setText(text);
    }

    /** The page learns about loading / playing / paused / error without polling the player itself. */
    void setStatusListener(StatusListener listener) {
        statusListener = listener;
        reportedStatus = -1;
        report();
    }

    private void report() {
        if (statusListener == null || destroyed) return;
        int st;
        String msg = "";
        if (errorBox.getVisibility() == View.VISIBLE) {
            st = ST_ERROR;
            msg = errorText.getText() == null ? "" : errorText.getText().toString();
        } else if (channel == null) {
            st = ST_IDLE;
        } else if (waitBox != null && waitBox.getVisibility() == View.VISIBLE) {
            st = ST_LOADING;
        } else if (player != null && lastPlaying) {
            st = ST_PLAYING;
        } else if (player != null && lastState == Player.STATE_READY) {
            st = ST_PAUSED;
        } else {
            st = player == null ? ST_IDLE : ST_LOADING;
        }
        if (st == reportedStatus && msg.equals(reportedMessage)) return;
        reportedStatus = st;
        reportedMessage = msg;
        statusListener.onStatus(st, msg);
    }

    boolean isActive() {
        return channel != null;
    }

    /** Plays `ch` (builds the ExoPlayer on the first call). */
    void play(CigramChannelStore.Ch ch) {
        if (ch == null || destroyed) return;
        channel = ch;
        bindChannel(ch);
        empty.setVisibility(View.GONE);
        hideError();
        if (!ch.available || ch.url.length() == 0) {
            releasePlayer();
            setWaiting(false, "");
            channelDown();
            showControls(false);
            return;
        }
        try {
            ensurePlayer();
        } catch (Throwable error) {
            Log.w(TAG, "player build failed", error);
            channelDown();
            return;
        }
        retries = 0;
        typeTry = -1;
        mime = null;
        lastError = null;
        lastState = -1;
        playingUrl = ch.url;
        setWaiting(true, "جارٍ التحميل…");
        showControls(true);
        final String asked = ch.url;
        CigramStreams.sniff(asked, new CigramStreams.MimeCallback() {
            @Override public void onMime(String found) {
                if (destroyed || player == null || !asked.equals(playingUrl)) return;
                start(found);
            }
        });
        try {
            CigramAnalytics.track(a, "channel_open", "section", ch.section == null ? "" : ch.section);
        } catch (Throwable ignored) { }
    }

    /** Back to the empty state. */
    void stop() {
        releasePlayer();
        channel = null;
        playingUrl = "";
        setWaiting(false, "");
        hideError();
        showControls(false);
        empty.setVisibility(View.VISIBLE);
        report();
    }

    /** The page left the screen: free the decoder, keep the channel so {@link #resume()} can play it again. */
    void suspend() {
        releasePlayer();
        setWaiting(false, "");
    }

    /** The page is back: plays the same channel again (a live stream has no position to restore). */
    void resume() {
        if (channel != null && player == null && !destroyed) play(channel);
    }

    void destroy() {
        destroyed = true;
        UI.removeCallbacks(hide);
        releasePlayer();
    }

    /** Landscape: the picture is the whole page, the full screen button has nothing left to do. */
    void setCompact(boolean landscape) {
        compact = landscape;
        fullBtn.setVisibility(landscape ? View.GONE : View.VISIBLE);
    }

    // ================================================================== player

    private void ensurePlayer() {
        if (player != null) return;
        selector = new DefaultTrackSelector(a);
        DefaultTrackSelector.Parameters.Builder pb = selector.buildUponParameters();
        pb.setMaxVideoSize(1280, 720);
        selector.setParameters(pb);
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(8000, 25000, 1200, 2500)
                .build();
        AudioAttributes audio = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();
        player = new ExoPlayer.Builder(a)
                .setTrackSelector(selector)
                .setLoadControl(load)
                .setMediaSourceFactory(CigramStreams.sourceFactory(a))
                .setAudioAttributes(audio, true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        player.setVolume(muted ? 0f : 1f);
        view = new PlayerView(a);
        view.setUseController(false);
        view.setBackgroundColor(Color.BLACK);
        view.setPlayer(player);
        stage.addView(view, 0, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        UI.removeCallbacks(watch);
        UI.postDelayed(watch, WATCH_MS);
    }

    private void releasePlayer() {
        UI.removeCallbacks(watch);
        if (view != null) {
            try { view.setKeepScreenOn(false); } catch (Throwable ignored) { }
            try { view.setPlayer(null); } catch (Throwable ignored) { }
            try { stage.removeView(view); } catch (Throwable ignored) { }
            view = null;
        }
        if (player != null) {
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
        selector = null;
        lastState = -1;
        lastPlaying = false;
    }

    /** Opens the link as the given type (null = unknown: ExoPlayer guesses, then the fallbacks are tried). */
    private void start(String type) {
        mime = type;
        lastError = null;
        String missing = CigramStreams.missingModule(type);
        if (missing.length() > 0) {
            setWaiting(false, "");
            showError(CigramStreams.missingText(missing));
            return;
        }
        try {
            player.setMediaItem(CigramStreams.apply(new MediaItem.Builder(), playingUrl, type).build());
            player.prepare();
            player.play();
        } catch (Throwable error) {
            Log.w(TAG, "play failed", error);
            if (!nextType()) {
                setWaiting(false, "");
                channelDown();
            }
        }
    }

    private boolean nextType() {
        String[] order = CigramStreams.FALLBACK;
        int i = typeTry + 1;
        while (i < order.length && (order[i].equals(mime) || CigramStreams.missingModule(order[i]).length() > 0)) i++;
        if (i >= order.length) return false;
        typeTry = i;
        start(order[i]);
        return true;
    }

    /**
     * The phone is online and the channel still fails: there is nothing to wait for. One quick reconnect, then the
     * viewer is told the truth at once — his internet is fine, the channel is paused — with «بلّغ المدير».
     * The phone is offline: no message at all; the player keeps trying quietly until the connection is back.
     */
    private void retry() {
        if (destroyed || player == null) return;
        boolean online = CigramChannelReports.online(a);
        if (online && retries >= 1) {
            setWaiting(false, "");
            channelDown();
            return;
        }
        if (!online && retries >= 3) retries = 2;       // offline: stay in the quiet loop
        retries++;
        setWaiting(true, online ? "جارٍ التحميل…" : "بانتظار عودة الاتصال…");
        final int attempt = retries;
        UI.postDelayed(new Runnable() {
            @Override public void run() {
                if (destroyed || player == null || attempt != retries) return;
                try {
                    player.seekToDefaultPosition();
                    player.prepare();
                    player.play();
                } catch (Throwable ignored) { }
            }
        }, online ? 1200L : 3000L);
    }

    /** Twice a second: what a Player.Listener would report (state, playing, error). */
    private void onWatch() {
        if (destroyed || player == null) return;
        try {
            PlaybackException error = player.getPlayerError();
            if (error != null && error != lastError) {
                lastError = error;
                Log.w(TAG, "playback error " + error.getErrorCodeName());
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition();
                    player.prepare();
                } else if (CigramStreams.isDrmError(error)) {
                    setWaiting(false, "");
                    showError("هذه القناة مشفّرة (DRM) ولا تعمل إلا داخل تطبيق مالكها");
                } else if (CigramStreams.isTypeError(error) && nextType()) {
                    // handled: the same link is being opened as another type
                } else if (CigramPlaybackError.classify(view == null ? null : view.getContext(), error)
                        == CigramPlaybackError.UNSUPPORTED_FORMAT) {
                    // retrying cannot help a stream this device cannot decode: say so at once
                    setWaiting(false, "");
                    showError(CigramPlaybackError.message(CigramPlaybackError.UNSUPPORTED_FORMAT));
                } else {
                    retry();
                }
            } else if (error == null) {
                lastError = null;
            }
            int state = player.getPlaybackState();
            if (state != lastState) {
                lastState = state;
                if (state == Player.STATE_BUFFERING) {
                    setWaiting(true, "");
                } else if (state == Player.STATE_READY) {
                    setWaiting(false, "");
                    retries = 0;
                    hideError();
                } else if (state == Player.STATE_ENDED) {
                    retry();   // a live stream "ended": reconnect
                } else if (error == null) {
                    setWaiting(false, "");
                }
            }
            boolean playing = player.isPlaying();
            if (playing != lastPlaying) {
                lastPlaying = playing;
                playIcon.set(playing ? CigramHubUi.PAUSE : CigramHubUi.PLAY, 0xFF000000);
                if (view != null) view.setKeepScreenOn(playing);
                if (playing) scheduleHide();
                report();
            }
        } catch (Throwable error) {
            Log.w(TAG, "watch failed", error);
        }
        UI.postDelayed(watch, WATCH_MS);
    }

    // ================================================================== controls

    /** v10.66 (TV remote): 0 = play/pause key, 1 = play key, 2 = pause key. Shows the controls a moment. */
    void tvPlayKey(int which) {
        if (channel == null || destroyed) return;
        if (which == 1 && lastPlaying) return;
        if (which == 2 && !lastPlaying) return;
        togglePlay();
        showControls(true);
    }

    private void togglePlay() {
        if (player == null) {
            if (channel != null) play(channel);
            return;
        }
        try {
            if (player.getPlayWhenReady() && player.getPlaybackState() != Player.STATE_ENDED) {
                player.pause();
                UI.removeCallbacks(hide);
            } else {
                // a paused live stream is behind the live edge: go back to it
                player.seekToDefaultPosition();
                player.prepare();
                player.play();
                scheduleHide();
            }
        } catch (Throwable ignored) { }
    }

    private void toggleMute() {
        muted = !muted;
        if (player != null) player.setVolume(muted ? 0f : 1f);
        muteIcon.set(muted ? CigramHubUi.MUTE : CigramHubUi.SPEAKER, Color.WHITE);
        keepControls();
    }

    private void keepControls() {
        UI.removeCallbacks(hide);
        scheduleHide();
    }

    private void scheduleHide() {
        UI.removeCallbacks(hide);
        if (controlsShown && lastPlaying) UI.postDelayed(hide, HIDE_MS);
    }

    private void showControls(boolean show) {
        if (show && channel == null) return;
        controlsShown = show;
        UI.removeCallbacks(hide);
        if (show) {
            controls.setVisibility(View.VISIBLE);
            controls.animate().cancel();
            controls.animate().alpha(1f).setDuration(140L).start();
            scheduleHide();
        } else {
            controls.animate().cancel();
            controls.animate().alpha(0f).setDuration(160L).withEndAction(new Runnable() {
                @Override public void run() {
                    if (!controlsShown) controls.setVisibility(View.GONE);
                }
            }).start();
        }
    }

    private void bindChannel(CigramChannelStore.Ch ch) {
        title.setText(ch.name);
        logo.show(ch.logoKey(), ch.name, true);
        playIcon.set(CigramHubUi.PAUSE, 0xFF000000);
    }

    private void setWaiting(boolean on, String text) {
        waitBox.setVisibility(on ? View.VISIBLE : View.GONE);
        status.setText(text);
        status.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
        report();
    }

    /** Online, and the channel does not answer: say so and offer to tell the admin. */
    private void channelDown() {
        showError("الإنترنت لديك سليم\nلكن هذه القناة متوقفة مؤقتاً");
        if (reportBtn != null && channel != null) {
            boolean sent = CigramChannelReports.reported(a, channel.key);
            reportBtn.setVisibility(View.VISIBLE);
            reportBtn.setText(sent ? "تم إبلاغ المدير" : "بلّغ المدير");
            reportBtn.setEnabled(!sent);
            reportBtn.setAlpha(sent ? 0.6f : 1f);
        }
    }

    private void showError(String text) {
        if (reportBtn != null) reportBtn.setVisibility(View.GONE);
        errorText.setText(text);
        errorBox.setVisibility(View.VISIBLE);
        showControls(false);
        report();
    }

    private void hideError() {
        errorBox.setVisibility(View.GONE);
        report();
    }

    // ================================================================== small builders

    private int dp(float v) {
        return CigramUI.dp(a, v);
    }

    private View circleButton(CigramHubUi.Icon icon, int sizeDp, int iconDp, int color, String description,
                              View.OnClickListener click) {
        FrameLayout f = new FrameLayout(a);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        if (color != 0xFFFFFFFF) g.setStroke(Math.max(1, dp(1)), 0x4DFFFFFF);    // glass buttons carry a white hairline
        f.setBackground(g);
        f.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                int act = e.getActionMasked();
                if (act == android.view.MotionEvent.ACTION_DOWN) v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).start();
                else if (act == android.view.MotionEvent.ACTION_UP || act == android.view.MotionEvent.ACTION_CANCEL) v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                return false;
            }
        });
        f.setContentDescription(description);
        f.setClickable(true);
        f.setOnClickListener(click);
        f.addView(icon, new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER));
        return f;
    }

    private View tapTarget(CigramHubUi.Icon icon, int iconDp, String description, View.OnClickListener click) {
        FrameLayout f = new FrameLayout(a);
        f.setContentDescription(description);
        f.setClickable(true);
        f.setOnClickListener(click);
        f.addView(icon, new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER));
        return f;
    }
}
