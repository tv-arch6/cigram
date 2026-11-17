package com.Cigram.vid;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 19.6 — the fallback player for VidBolt (and the other embed sites) on TV boxes and phones whose hidden browser could
 * not hand a stream to ExoPlayer (an old Android System WebView, a blocked autoplay, a slow box). Instead of
 * «تعذّر تشغيل الفيديو» and a way back out, the title plays in the provider's own player, full screen, inside Cigram:
 *
 *  - streaming only (nothing is downloaded), pop-ups and redirects to other sites are blocked;
 *  - remote control: OK / ▶❚❚ = play / pause, ◀ ▶ = −10 s / +10 s, ▲ ▼ = volume of the page's video, BACK = leave;
 *  - the screen stays on, landscape, no system bars; the video's own fullscreen is honoured.
 */
public class CigramWebPlayerActivity extends Activity {

    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";

    private WebView web;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private TextView hint;
    private String host = "";

    /** Opens {@code embedUrl} in the fallback player. */
    static void open(Context from, String embedUrl, String title) {
        if (from == null || embedUrl == null || embedUrl.trim().length() == 0) return;
        try {
            Intent i = new Intent(from, CigramWebPlayerActivity.class);
            i.putExtra(EXTRA_URL, CigramStreams.cleanUrl(embedUrl.trim()));
            i.putExtra(EXTRA_TITLE, title == null ? "" : title);
            if (!(from instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            from.startActivity(i);
        } catch (Throwable error) {
            android.util.Log.w("CigramWebPlayer", "open failed", error);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try { setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE); } catch (Throwable ignored) { }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        immersive();
        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.length() == 0) { finish(); return; }
        try { host = Uri.parse(url).getHost(); } catch (Throwable ignored) { }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        try {
            web = new WebView(this);
        } catch (Throwable noWebView) {
            // no system browser at all on this box: say so plainly instead of crashing
            TextView t = new TextView(this);
            t.setText("هذا الجهاز لا يحتوي متصفح النظام (Android System WebView).\nثبّته أو حدّثه من المتجر ثم أعد المحاولة.");
            t.setTextColor(Color.WHITE);
            t.setTextSize(18f);
            t.setGravity(Gravity.CENTER);
            root.addView(t, new FrameLayout.LayoutParams(-1, -1));
            setContentView(root);
            return;
        }
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return blocked(request == null || request.getUrl() == null ? "" : request.getUrl().toString());
            }
            @SuppressWarnings("deprecation")
            @Override public boolean shouldOverrideUrlLoading(WebView view, String u) {
                return blocked(u);
            }
            @Override public void onPageFinished(WebView view, String u) {
                // start the video without a tap where the page allows it (TV remotes cannot click a tiny play button)
                view.postDelayed(new Runnable() { @Override public void run() { js("cgPlay()"); } }, 1500L);
                view.postDelayed(new Runnable() { @Override public void run() { if (hint != null) hint.setVisibility(View.GONE); } }, 6000L);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                web.setVisibility(View.GONE);
                immersive();
            }
            @Override public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                web.setVisibility(View.VISIBLE);
                if (customCallback != null) customCallback.onCustomViewHidden();
                customCallback = null;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        ProgressBar spin = new ProgressBar(this);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(spin, sl);
        final ProgressBar fSpin = spin;
        web.postDelayed(new Runnable() { @Override public void run() { fSpin.setVisibility(View.GONE); } }, 4000L);

        hint = new TextView(this);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        hint.setText((title == null || title.length() == 0 ? "" : title + "\n") + "OK تشغيل/إيقاف · ◀ ▶ عشر ثوانٍ · رجوع للخروج");
        hint.setTextColor(0xE6FFFFFF);
        hint.setTextSize(14f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(28, 14, 28, 14);
        hint.setBackgroundColor(0x99000000);
        root.addView(hint, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        setContentView(root);
        web.loadUrl(url);
        web.requestFocus();
    }

    /** Only the embed site itself (and its sub-domains) may load in this window: ads / pop-up redirects are dropped. */
    private boolean blocked(String u) {
        try {
            String h = Uri.parse(u).getHost();
            if (h == null || host == null || host.length() == 0) return true;
            h = h.toLowerCase(java.util.Locale.ROOT);
            String base = host.toLowerCase(java.util.Locale.ROOT);
            return !(h.equals(base) || h.endsWith("." + base) || base.endsWith("." + h));
        } catch (Throwable t) {
            return true;
        }
    }

    private static final String HELPERS =
            "window.cgVid=function(){var v=document.querySelector('video');if(v)return v;var f=document.querySelectorAll('iframe');"
            + "for(var i=0;i<f.length;i++){try{var d=f[i].contentDocument;var x=d&&d.querySelector('video');if(x)return x;}catch(e){}}return null;};"
            + "window.cgPlay=function(){var v=cgVid();if(v){v.play&&v.play();return;}var b=document.querySelector('[class*=play],[id*=play],button,.jw-icon-display,.vjs-big-play-button');if(b)b.click();};"
            + "window.cgToggle=function(){var v=cgVid();if(!v){cgPlay();return;}if(v.paused)v.play();else v.pause();};"
            + "window.cgSeek=function(s){var v=cgVid();if(v&&v.duration)v.currentTime=Math.max(0,Math.min(v.duration-1,v.currentTime+s));};"
            + "window.cgVol=function(d){var v=cgVid();if(v){v.muted=false;v.volume=Math.max(0,Math.min(1,v.volume+d));}};";

    private void js(String call) {
        if (web == null) return;
        try {
            String code = HELPERS + call + ";";
            if (Build.VERSION.SDK_INT >= 19) web.evaluateJavascript(code, null);
            else web.loadUrl("javascript:" + code);
        } catch (Throwable ignored) { }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_SPACE:
                    js("cgToggle()"); return true;
                case KeyEvent.KEYCODE_MEDIA_PLAY: js("cgPlay()"); return true;
                case KeyEvent.KEYCODE_MEDIA_PAUSE: js("var v=cgVid();if(v)v.pause()"); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND: js("cgSeek(-10)"); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: js("cgSeek(10)"); return true;
                case KeyEvent.KEYCODE_DPAD_UP: js("cgVol(0.1)"); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: js("cgVol(-0.1)"); return true;
                default: break;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (customView != null && web != null) {
            try { web.getWebChromeClient(); } catch (Throwable ignored) { }
            if (customCallback != null) customCallback.onCustomViewHidden();
            root.removeView(customView);
            customView = null;
            web.setVisibility(View.VISIBLE);
            return;
        }
        super.onBackPressed();
    }

    private void immersive() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } catch (Throwable ignored) { }
    }

    @Override
    protected void onPause() {
        if (web != null) { try { web.onPause(); } catch (Throwable ignored) { } }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        if (web != null) { try { web.onResume(); } catch (Throwable ignored) { } }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            try {
                web.stopLoading();
                web.loadUrl("about:blank");
                root.removeView(web);
                web.destroy();
            } catch (Throwable ignored) { }
            web = null;
        }
        super.onDestroy();
    }
}
