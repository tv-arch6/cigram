package com.my.newproject;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Photos and videos of the private conversation open INSIDE the app.
 *
 * <b>Photo</b>: the original file is fetched once (shared with the bubble cache), shown zoomable (pinch, double tap,
 * drag); the picture on screen is sampled down only as far as the phone's memory needs, the file itself stays intact.
 *
 * <b>Video</b>: the app's own player — sound, mute, a seek bar with the buffered part, play / pause, 10 seconds back
 * and forward (buttons or a double tap on the left / right half), elapsed and total time, rotation to landscape, audio
 * focus (a phone call pauses it). It streams: nothing has to be downloaded before it starts.
 *
 * <b>«حفظ»</b> writes the ORIGINAL bytes to the phone's gallery (Pictures/Cigram or Movies/Cigram): no re-encoding,
 * so resolution and quality are exactly those of the file that was sent. Android 10+ needs no permission for that;
 * older versions ask for storage access at that moment only.
 *
 * Uses only framework classes (MediaPlayer + TextureView), so the same screen exists in Cigram and in Cigram Admin.
 */
public class CigramMediaViewer extends Activity implements TextureView.SurfaceTextureListener {

    private static final int REQ_STORAGE = 4411;
    private static final int ACCENT = 0xFFE21B14;

    /** Opens a photo or a video. {@code localPath} is the file on this phone when the message is still being sent. */
    static void open(Context c, String url, boolean video, String name, String localPath) {
        try {
            Intent i = new Intent(c, CigramMediaViewer.class);
            i.putExtra("url", url == null ? "" : url);
            i.putExtra("video", video);
            i.putExtra("name", name == null ? "" : name);
            i.putExtra("local", localPath == null ? "" : localPath);
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            android.util.Log.w("CigramMediaViewer", "open failed", t);
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private String url = "", name = "", local = "";
    private boolean video, destroyed;
    private float dp;

    private FrameLayout root;
    private LinearLayout topBar;
    private TextView title, status;
    private ProgressBar spinner;
    private Zoom image;
    private File original;                 // the photo's original bytes on disk
    private final AtomicBoolean saving = new AtomicBoolean(false);

    // video
    private FrameLayout stage;
    private TextureView texture;
    private Surface surface;
    private MediaPlayer mp;
    private boolean prepared, wantPlay = true, muted, ended, dragging, failed;
    private int resumeAt, videoW, videoH, bufferedPct;
    private LinearLayout centre, bottom;
    private Glyph playBtn, muteBtn;
    private SeekBar seek;
    private TextView now, total, errorText;
    private LinearLayout errorBox;
    private boolean controlsShown = true;
    private AudioManager audio;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        Intent in = getIntent();
        url = str(in == null ? null : in.getStringExtra("url"));
        name = str(in == null ? null : in.getStringExtra("name"));
        local = str(in == null ? null : in.getStringExtra("local"));
        video = in != null && in.getBooleanExtra("video", false);
        if (b != null) { resumeAt = b.getInt("pos", 0); muted = b.getBoolean("muted", false); }
        try {
            getWindow().setStatusBarColor(0xFF000000);
            getWindow().setNavigationBarColor(0xFF000000);
            getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF000000));
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(lp);
            }
        } catch (Throwable ignored) { }
        if (url.length() == 0 && local.length() == 0) { finish(); return; }
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        build();
        if (video) startVideo(); else loadPhoto();
    }

    private static String str(String s) { return s == null ? "" : s; }

    private int px(float v) { return (int) (v * dp + 0.5f); }

    @Override protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putInt("pos", position());
        b.putBoolean("muted", muted);
    }

    // ------------------------------------------------------------------ layout

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        if (video) {
            stage = new FrameLayout(this);
            texture = new TextureView(this);
            texture.setSurfaceTextureListener(this);
            stage.addView(texture, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
            final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleControls(); return true; }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    seekBy(e.getX() < root.getWidth() / 2f ? -10000 : 10000);
                    return true;
                }
                @Override public boolean onDown(MotionEvent e) { return true; }
            });
            stage.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) { return gd.onTouchEvent(e); }
            });
            stage.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    if (r - l != or - ol || b - t != ob - ot) fitVideo();
                }
            });
        } else {
            image = new Zoom(this);
            image.onTap = new Runnable() { @Override public void run() { toggleControls(); } };
            root.addView(image, new FrameLayout.LayoutParams(-1, -1));
        }

        spinner = new ProgressBar(this);
        try { spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF)); } catch (Throwable ignored) { }
        root.addView(spinner, new FrameLayout.LayoutParams(px(44), px(44), Gravity.CENTER));

        // top bar: close · title · save
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(px(8), px(30), px(8), px(18));
        GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000});
        topBar.setBackground(scrim);
        Glyph close = new Glyph(this, Glyph.CLOSE, "إغلاق", false);
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        topBar.addView(close, new LinearLayout.LayoutParams(px(46), px(46)));
        title = new TextView(this);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(14.5f);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setGravity(Gravity.CENTER);
        title.setText(name.length() > 0 ? name : (video ? "فيديو" : "صورة"));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.setMargins(px(8), 0, px(8), 0);
        topBar.addView(title, tl);
        Glyph save = new Glyph(this, Glyph.SAVE, "حفظ في الجهاز", false);
        save.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(); } });
        topBar.addView(save, new LinearLayout.LayoutParams(px(46), px(46)));
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(13.5f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(px(16), px(9), px(16), px(9));
        GradientDrawable sb = new GradientDrawable();
        sb.setColor(0xE6161618);
        sb.setCornerRadius(px(20));
        sb.setStroke(Math.max(1, px(1)), 0x33FFFFFF);
        status.setBackground(sb);
        status.setVisibility(View.GONE);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl.setMargins(px(20), 0, px(20), px(video ? 96 : 40));
        root.addView(status, sl);

        if (video) buildVideoControls();
        setContentView(root);
    }

    private void buildVideoControls() {
        centre = new LinearLayout(this);
        centre.setOrientation(LinearLayout.HORIZONTAL);
        centre.setGravity(Gravity.CENTER);
        Glyph rew = new Glyph(this, Glyph.REWIND, "رجوع 10 ثوانٍ", true);
        rew.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { seekBy(-10000); } });
        playBtn = new Glyph(this, Glyph.PAUSE, "تشغيل أو إيقاف مؤقت", true);
        playBtn.setMain(true);
        playBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { togglePlay(); } });
        Glyph fwd = new Glyph(this, Glyph.FORWARD, "تقديم 10 ثوانٍ", true);
        fwd.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { seekBy(10000); } });
        centre.addView(rew, new LinearLayout.LayoutParams(px(54), px(54)));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(px(72), px(72));
        pl.setMargins(px(26), 0, px(26), 0);
        centre.addView(playBtn, pl);
        centre.addView(fwd, new LinearLayout.LayoutParams(px(54), px(54)));
        root.addView(centre, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(px(12), px(22), px(8), px(18));
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x00000000}));
        now = timeLabel("0:00");
        total = timeLabel("--:--");
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setPadding(px(10), px(12), px(10), px(12));
        try {
            seek.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            seek.setSecondaryProgressTintList(android.content.res.ColorStateList.valueOf(0x99FFFFFF));
            seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55FFFFFF));
            GradientDrawable thumb = new GradientDrawable();
            thumb.setShape(GradientDrawable.OVAL);
            thumb.setColor(0xFFFFFFFF);
            thumb.setSize(px(15), px(15));
            seek.setThumb(thumb);
            seek.setSplitTrack(false);
        } catch (Throwable ignored) { }
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && prepared) now.setText(clock((int) ((long) duration() * p / 1000)));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; ui.removeCallbacks(hide); }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (prepared) seekTo((int) ((long) duration() * s.getProgress() / 1000));
                scheduleHide();
            }
        });
        muteBtn = new Glyph(this, Glyph.SOUND, "كتم الصوت أو تشغيله", false);
        muteBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setMuted(!muted); scheduleHide(); } });
        bottom.addView(now, new LinearLayout.LayoutParams(px(48), -2));
        bottom.addView(seek, new LinearLayout.LayoutParams(0, px(44), 1f));
        bottom.addView(total, new LinearLayout.LayoutParams(px(48), -2));
        bottom.addView(muteBtn, new LinearLayout.LayoutParams(px(44), px(44)));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        errorBox = new LinearLayout(this);
        errorBox.setOrientation(LinearLayout.VERTICAL);
        errorBox.setGravity(Gravity.CENTER);
        errorText = new TextView(this);
        errorText.setTextColor(0xFFFFFFFF);
        errorText.setTextSize(15f);
        errorText.setGravity(Gravity.CENTER);
        errorBox.addView(errorText, new LinearLayout.LayoutParams(-2, -2));
        TextView retry = new TextView(this);
        retry.setText("إعادة المحاولة");
        retry.setTextColor(0xFFFFFFFF);
        retry.setTextSize(14.5f);
        retry.setGravity(Gravity.CENTER);
        retry.setPadding(px(22), px(10), px(22), px(10));
        GradientDrawable rb = new GradientDrawable();
        rb.setColor(ACCENT);
        rb.setCornerRadius(px(22));
        retry.setBackground(rb);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { errorBox.setVisibility(View.GONE); failed = false; releasePlayer(); wantPlay = true; startVideo(); }
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
        rl.setMargins(0, px(14), 0, 0);
        errorBox.addView(retry, rl);
        errorBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        el.setMargins(px(28), 0, px(28), 0);
        root.addView(errorBox, el);
    }

    private TextView timeLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(12.5f);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(android.graphics.Typeface.MONOSPACE);
        return t;
    }

    static String clock(int ms) {
        int s = Math.max(0, ms / 1000);
        int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? h + ":" + (m < 10 ? "0" : "") + m + ":" + (sec < 10 ? "0" : "") + sec : m + ":" + (sec < 10 ? "0" : "") + sec;
    }

    // ------------------------------------------------------------------ controls visibility

    private final Runnable hide = new Runnable() {
        @Override public void run() { if (!destroyed && video && playing() && !dragging) showControls(false); }
    };

    private void scheduleHide() {
        ui.removeCallbacks(hide);
        ui.postDelayed(hide, 3400);
    }

    private void toggleControls() {
        showControls(!controlsShown);
        if (controlsShown) scheduleHide();
    }

    private void showControls(boolean show) {
        controlsShown = show;
        float a = show ? 1f : 0f;
        View[] vs = video ? new View[]{topBar, centre, bottom} : new View[]{topBar};
        for (final View v : vs) {
            if (v == null) continue;
            v.animate().cancel();
            if (show) v.setVisibility(View.VISIBLE);
            v.animate().alpha(a).setDuration(180).withEndAction(new Runnable() {
                @Override public void run() { if (!controlsShown) v.setVisibility(View.INVISIBLE); }
            }).start();
        }
        try {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!show) flags |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        } catch (Throwable ignored) { }
    }

    private void say(final String text, final boolean stay) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                ui.removeCallbacks(clearStatus);
                status.setText(text);
                status.setVisibility(View.VISIBLE);
                if (!stay) ui.postDelayed(clearStatus, 3200);
            }
        });
    }

    private final Runnable clearStatus = new Runnable() {
        @Override public void run() { if (!destroyed) status.setVisibility(View.GONE); }
    };

    // ------------------------------------------------------------------ photo

    static String cacheKey(String u) {
        int q = u.indexOf('?');
        return q > 0 ? u.substring(0, q) : u;
    }

    /** Same file the conversation's bubble loader uses (ads_media/&lt;hash&gt;.bin): a photo seen in a bubble opens at once. */
    static File cacheFile(Context c, String u) {
        File dir = new File(c.getCacheDir(), "ads_media");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, Integer.toHexString(cacheKey(u).hashCode()) + ".bin");
    }

    private void loadPhoto() {
        spinner.setVisibility(View.VISIBLE);
        new Thread(new Runnable() {
            @Override public void run() {
                File f = null;
                String err = null;
                try {
                    if (local.length() > 0 && new File(local).isFile()) f = new File(local);
                    else {
                        f = cacheFile(CigramMediaViewer.this, url);
                        if (!f.isFile() || f.length() == 0) fetch(url, f, null);
                    }
                } catch (Throwable t) {
                    err = "تعذر تحميل الصورة. تحقق من الاتصال ثم أعد فتحها.";
                }
                final File file = f;
                Bitmap bmp = null;
                if (err == null && file != null) {
                    try { bmp = decodeForScreen(file); } catch (Throwable t) { bmp = null; }
                    if (bmp == null) err = "تعذر عرض هذه الصورة على هذا الجهاز (يمكنك حفظها).";
                }
                final Bitmap fb = bmp;
                final String fe = err;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (destroyed) { if (fb != null) fb.recycle(); return; }
                        spinner.setVisibility(View.GONE);
                        if (file != null && file.isFile() && file.length() > 0) original = file;
                        if (fb != null) image.setBitmap(fb);
                        if (fe != null) say(fe, true);
                    }
                });
            }
        }, "cigram-viewer-photo").start();
    }

    /** Downloads {@code u} into {@code out} (through a temp file, so a broken download never looks like a finished one). */
    private void fetch(String u, File out, final int[] pctOut) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(30000);
        File tmp = new File(out.getParentFile(), out.getName() + ".v" + System.nanoTime() + ".tmp");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new java.io.IOException("http " + code);
            long len = conn.getContentLength();
            InputStream in = conn.getInputStream();
            OutputStream os = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                long done = 0;
                while ((n = in.read(buf)) > 0) {
                    if (destroyed) throw new java.io.IOException("closed");
                    os.write(buf, 0, n);
                    done += n;
                    if (pctOut != null && len > 0) pctOut[0] = (int) (done * 100 / len);
                }
            } finally {
                try { os.close(); } catch (Throwable ignored) { }
                try { in.close(); } catch (Throwable ignored) { }
            }
            if (tmp.length() == 0) throw new java.io.IOException("empty");
            if (out.exists()) out.delete();
            if (!tmp.renameTo(out)) throw new java.io.IOException("rename");
        } finally {
            conn.disconnect();
            if (tmp.exists()) tmp.delete();
        }
    }

    /**
     * The picture for the SCREEN: at most 4096 px on its long side (GPU texture limit) and at most an eighth of the
     * app's memory, upright (EXIF). The file is not changed.
     */
    private Bitmap decodeForScreen(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        long budget = Math.max(8L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 4096 || (long) (o.outWidth / sample) * (o.outHeight / sample) * 4 > budget) sample *= 2;
        Bitmap b = null;
        for (int attempt = 0; attempt < 3 && b == null; attempt++) {
            try {
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                b = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
            } catch (OutOfMemoryError oom) {
                sample *= 2;
            }
        }
        if (b == null) return null;
        int deg = 0;
        try {
            int or = new android.media.ExifInterface(f.getAbsolutePath()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
            deg = or == 6 ? 90 : or == 3 ? 180 : or == 8 ? 270 : 0;
        } catch (Throwable ignored) { }
        if (deg != 0) {
            try {
                Matrix m = new Matrix();
                m.postRotate(deg);
                Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
                if (r != b) b.recycle();
                b = r;
            } catch (OutOfMemoryError ignored) { }
        }
        return b;
    }

    // ------------------------------------------------------------------ video

    private int position() {
        try { return mp != null && prepared ? mp.getCurrentPosition() : resumeAt; } catch (Throwable t) { return resumeAt; }
    }

    private int duration() {
        try { return mp != null && prepared ? Math.max(0, mp.getDuration()) : 0; } catch (Throwable t) { return 0; }
    }

    private boolean playing() {
        try { return mp != null && prepared && mp.isPlaying(); } catch (Throwable t) { return false; }
    }

    private void startVideo() {
        if (destroyed || mp != null) return;
        spinner.setVisibility(View.VISIBLE);
        prepared = false;
        ended = false;
        try {
            mp = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= 21) {
                mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build());
            }
            if (local.length() > 0 && new File(local).isFile()) mp.setDataSource(local);
            else if (local.startsWith("content://")) mp.setDataSource(this, Uri.parse(local));
            else mp.setDataSource(this, Uri.parse(url));
            if (surface != null) mp.setSurface(surface);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer p) {
                    if (destroyed || p != mp) return;
                    prepared = true;
                    spinner.setVisibility(View.GONE);
                    videoW = p.getVideoWidth();
                    videoH = p.getVideoHeight();
                    fitVideo();
                    total.setText(clock(duration()));
                    applyVolume();
                    if (resumeAt > 0) seekTo(resumeAt);
                    if (wantPlay) play(); else updatePlayIcon();
                    ui.removeCallbacks(tick);
                    ui.post(tick);
                }
            });
            mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override public void onVideoSizeChanged(MediaPlayer p, int w, int h) { videoW = w; videoH = h; fitVideo(); }
            });
            mp.setOnBufferingUpdateListener(new MediaPlayer.OnBufferingUpdateListener() {
                @Override public void onBufferingUpdate(MediaPlayer p, int pct) { bufferedPct = pct; }
            });
            mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override public boolean onInfo(MediaPlayer p, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) spinner.setVisibility(View.VISIBLE);
                    else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) spinner.setVisibility(View.GONE);
                    return false;
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer p) {
                    if (destroyed || failed) return;
                    ended = true;
                    wantPlay = false;
                    updatePlayIcon();
                    showControls(true);
                    keepScreenOn(false);
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer p, int what, int extra) {
                    onVideoError(what, extra);
                    return true;
                }
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            onVideoError(0, 0);
        }
    }

    private void onVideoError(int what, int extra) {
        if (destroyed) return;
        resumeAt = Math.max(resumeAt, position());
        failed = true;
        releasePlayer();
        spinner.setVisibility(View.GONE);
        boolean net = extra == MediaPlayer.MEDIA_ERROR_IO || extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT || !online();
        boolean format = extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED || extra == MediaPlayer.MEDIA_ERROR_MALFORMED;
        errorText.setText(format ? "صيغة هذا الفيديو غير مدعومة على هذا الجهاز.\nيمكنك حفظه وفتحه بتطبيق آخر."
                : net ? "انقطع تحميل الفيديو.\nتحقق من الاتصال ثم أعد المحاولة." : "تعذر تشغيل الفيديو.");
        errorBox.setVisibility(View.VISIBLE);
        showControls(true);
        centre.setVisibility(View.INVISIBLE);
        keepScreenOn(false);
    }

    private boolean online() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) {
            return true;
        }
    }

    private void releasePlayer() {
        ui.removeCallbacks(tick);
        MediaPlayer p = mp;
        mp = null;
        prepared = false;
        if (p != null) {
            try { p.setSurface(null); } catch (Throwable ignored) { }
            try { p.reset(); } catch (Throwable ignored) { }
            try { p.release(); } catch (Throwable ignored) { }
        }
        try { if (audio != null) audio.abandonAudioFocus(focus); } catch (Throwable ignored) { }
    }

    private final AudioManager.OnAudioFocusChangeListener focus = new AudioManager.OnAudioFocusChangeListener() {
        @Override public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                ui.post(new Runnable() { @Override public void run() { pause(false); } });
            }
        }
    };

    private void play() {
        if (!prepared || mp == null) { wantPlay = true; return; }
        try {
            if (ended) { mp.seekTo(0); ended = false; }
            if (!muted && audio != null) audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            mp.start();
            wantPlay = true;
            keepScreenOn(true);
            centre.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            onVideoError(0, 0);
            return;
        }
        updatePlayIcon();
        scheduleHide();
    }

    private void pause(boolean byUser) {
        try { if (mp != null && prepared && mp.isPlaying()) mp.pause(); } catch (Throwable ignored) { }
        if (byUser) wantPlay = false;
        keepScreenOn(false);
        updatePlayIcon();
        if (video) showControls(true);
    }

    private void togglePlay() {
        if (playing()) pause(true); else { wantPlay = true; play(); }
    }

    private void updatePlayIcon() {
        if (playBtn != null) playBtn.setKind(playing() ? Glyph.PAUSE : ended ? Glyph.REPLAY : Glyph.PLAY);
    }

    private void seekBy(int deltaMs) {
        if (!prepared) return;
        int d = duration();
        int to = Math.max(0, Math.min(d > 0 ? d - 300 : Integer.MAX_VALUE, position() + deltaMs));
        seekTo(to);
        say(deltaMs < 0 ? "‹‹ 10 ثوانٍ" : "10 ثوانٍ ››", false);
        showControls(true);
        scheduleHide();
    }

    private void seekTo(int ms) {
        if (!prepared || mp == null) { resumeAt = ms; return; }
        try {
            ended = false;
            if (Build.VERSION.SDK_INT >= 26) mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
            else mp.seekTo(ms);
            updatePlayIcon();
        } catch (Throwable ignored) { }
    }

    private void setMuted(boolean m) {
        muted = m;
        applyVolume();
        say(m ? "الصوت مكتوم" : "الصوت يعمل", false);
    }

    private void applyVolume() {
        try { if (mp != null) mp.setVolume(muted ? 0f : 1f, muted ? 0f : 1f); } catch (Throwable ignored) { }
        if (muteBtn != null) muteBtn.setKind(muted ? Glyph.MUTED : Glyph.SOUND);
    }

    private void keepScreenOn(boolean on) {
        try {
            if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) { }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (destroyed || mp == null) return;
            if (prepared && !dragging) {
                int d = duration(), p = position();
                now.setText(clock(p));
                if (d > 0) {
                    seek.setProgress((int) ((long) p * 1000 / d));
                    seek.setSecondaryProgress(bufferedPct * 10);
                }
            }
            ui.postDelayed(this, 250);
        }
    };

    /** The picture keeps its shape: it fills the width or the height, never both unless the shapes match. */
    private void fitVideo() {
        if (texture == null || stage == null || videoW <= 0 || videoH <= 0) return;
        int sw = stage.getWidth(), sh = stage.getHeight();
        if (sw <= 0 || sh <= 0) return;
        float scale = Math.min(sw / (float) videoW, sh / (float) videoH);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) texture.getLayoutParams();
        int w = Math.max(1, Math.round(videoW * scale)), h = Math.max(1, Math.round(videoH * scale));
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            lp.gravity = Gravity.CENTER;
            texture.setLayoutParams(lp);
        }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        try { if (mp != null) mp.setSurface(surface); } catch (Throwable ignored) { }
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        try { if (mp != null) mp.setSurface(null); } catch (Throwable ignored) { }
        if (surface != null) { try { surface.release(); } catch (Throwable ignored) { } surface = null; }
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onStart() {
        super.onStart();
        if (video && mp == null && !failed) startVideo();
    }

    @Override protected void onPause() {
        if (video && playing()) { try { mp.pause(); } catch (Throwable ignored) { } updatePlayIcon(); }
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (video && wantPlay && prepared && !ended) play();
    }

    @Override protected void onStop() {
        if (video) {
            resumeAt = position();
            releasePlayer();
            keepScreenOn(false);
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
        if (image != null) image.release();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ save to the phone (original bytes)

    private void save() {
        if (Build.VERSION.SDK_INT < 29 && Build.VERSION.SDK_INT >= 23
                && checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.WRITE_EXTERNAL_STORAGE"}, REQ_STORAGE);
            return;
        }
        if (!saving.compareAndSet(false, true)) { say("الحفظ جارٍ…", false); return; }
        say("جارٍ الحفظ…", true);
        new Thread(new Runnable() {
            @Override public void run() {
                String err = null, where = null;
                try {
                    where = saveNow();
                } catch (Throwable t) {
                    android.util.Log.w("CigramMediaViewer", "save failed", t);
                    err = t instanceof java.io.IOException && String.valueOf(t.getMessage()).contains("ENOSPC") ? "لا توجد مساحة كافية في الجهاز." : "تعذر الحفظ. تحقق من الاتصال والمساحة ثم أعد المحاولة.";
                }
                saving.set(false);
                if (!destroyed) say(err != null ? err : "تم الحفظ بالجودة الأصلية في " + where, false);
            }
        }, "cigram-viewer-save").start();
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req != REQ_STORAGE) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) save();
        else say("لا يمكن الحفظ في المعرض دون إذن التخزين.", false);
    }

    /** File extension and MIME type from the name / address of the media. */
    static String[] typeOf(String name, String url, boolean video) {
        String src = (name == null ? "" : name) + " " + cacheKey(url == null ? "" : url);
        String low = src.toLowerCase(Locale.US);
        String[][] known = video
                ? new String[][]{{".mp4", "video/mp4"}, {".m4v", "video/mp4"}, {".mov", "video/quicktime"}, {".webm", "video/webm"}, {".mkv", "video/x-matroska"}, {".3gp", "video/3gpp"}}
                : new String[][]{{".jpg", "image/jpeg"}, {".jpeg", "image/jpeg"}, {".png", "image/png"}, {".webp", "image/webp"}, {".gif", "image/gif"}, {".heic", "image/heic"}, {".heif", "image/heif"}};
        for (String[] k : known) if (low.contains(k[0] + " ") || low.endsWith(k[0])) return new String[]{k[0].equals(".jpeg") ? ".jpg" : k[0], k[1]};
        return video ? new String[]{".mp4", "video/mp4"} : new String[]{".jpg", "image/jpeg"};
    }

    /** The real type of a picture from its first bytes (the cache file has no extension). */
    static String[] sniffImage(File f, String[] fallback) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] h = new byte[12];
            int n = in.read(h);
            if (n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8) return new String[]{".jpg", "image/jpeg"};
            if (n >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return new String[]{".png", "image/png"};
            if (n >= 6 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F') return new String[]{".gif", "image/gif"};
            if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return new String[]{".webp", "image/webp"};
            if (n >= 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p' && h[8] == 'h' && h[9] == 'e') return new String[]{".heic", "image/heic"};
        } catch (Throwable ignored) {
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
        }
        return fallback;
    }

    /** Copies the original file into the gallery and returns the folder name for the confirmation. */
    private String saveNow() throws Exception {
        File src = null;
        if (local.length() > 0 && new File(local).isFile()) src = new File(local);
        else if (!video && original != null && original.isFile()) src = original;
        else if (!video) {
            File f = cacheFile(this, url);
            if (!f.isFile() || f.length() == 0) fetch(url, f, null);
            src = f;
        }
        String[] type = typeOf(name, url, video);
        if (!video && src != null) type = sniffImage(src, type);
        String fileName = "Cigram_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + type[0];
        String folder = (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/Cigram";

        InputStream in = null;
        HttpURLConnection conn = null;
        long length = -1;
        try {
            if (src != null) {
                in = new FileInputStream(src);
                length = src.length();
            } else {
                conn = (HttpURLConnection) new URL(url).openConnection();   // a video is streamed straight into the gallery
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(40000);
                if (conn.getResponseCode() != 200) throw new java.io.IOException("http " + conn.getResponseCode());
                length = conn.getContentLength();
                in = conn.getInputStream();
            }
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                v.put(MediaStore.MediaColumns.MIME_TYPE, type[1]);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri collection = video ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri item = getContentResolver().insert(collection, v);
                if (item == null) throw new java.io.IOException("insert");
                boolean ok = false;
                try {
                    OutputStream os = getContentResolver().openOutputStream(item);
                    if (os == null) throw new java.io.IOException("open");
                    try { copy(in, os, length); } finally { try { os.close(); } catch (Throwable ignored) { } }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(item, done, null, null);
                    ok = true;
                } finally {
                    if (!ok) try { getContentResolver().delete(item, null, null); } catch (Throwable ignored) { }
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), "Cigram");
                if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("mkdirs");
                File out = new File(dir, fileName);
                boolean ok = false;
                try {
                    OutputStream os = new FileOutputStream(out);
                    try { copy(in, os, length); } finally { try { os.close(); } catch (Throwable ignored) { } }
                    ok = true;
                } finally {
                    if (!ok) out.delete();
                }
                android.media.MediaScannerConnection.scanFile(getApplicationContext(), new String[]{out.getAbsolutePath()}, new String[]{type[1]}, null);
            }
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
            if (conn != null) conn.disconnect();
        }
        return folder;
    }

    private void copy(InputStream in, OutputStream os, long length) throws Exception {
        byte[] buf = new byte[128 * 1024];
        int n;
        long done = 0, lastSaid = 0;
        while ((n = in.read(buf)) > 0) {
            if (destroyed) throw new java.io.IOException("closed");
            os.write(buf, 0, n);
            done += n;
            if (length > 2 * 1024 * 1024 && done - lastSaid > length / 50) {
                lastSaid = done;
                say("جارٍ الحفظ " + (done * 100 / length) + "%", true);
            }
        }
        os.flush();
        if (done == 0) throw new java.io.IOException("empty");
    }

    // ------------------------------------------------------------------ icons

    /** The viewer's own line icons (no image files). */
    static final class Glyph extends View {
        static final int CLOSE = 0, SAVE = 1, PLAY = 2, PAUSE = 3, REWIND = 4, FORWARD = 5, SOUND = 6, MUTED = 7, REPLAY = 8;
        private int kind;
        private boolean main;
        private final boolean disc;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();

        Glyph(Context c, int kind, String description, boolean disc) {
            super(c);
            this.kind = kind;
            this.disc = disc;
            setContentDescription(description);
            setClickable(true);
            setFocusable(true);
        }

        void setKind(int k) { if (kind != k) { kind = k; invalidate(); } }

        void setMain(boolean m) { main = m; invalidate(); }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).start();
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) animate().scaleX(1f).scaleY(1f).setDuration(130).start();
            return super.onTouchEvent(e);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, u = Math.min(w, h);
            p.setPathEffect(null);
            if (disc) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(main ? ACCENT : 0x59000000);
                c.drawCircle(cx, cy, u / 2f - 1, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, u * 0.02f));
                p.setColor(main ? 0x55FFFFFF : 0x40FFFFFF);
                c.drawCircle(cx, cy, u / 2f - 1, p);
            }
            float s = disc ? u * 0.42f : u * 0.46f;     // icon box
            p.setColor(0xFFFFFFFF);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(Math.max(2f, u * 0.045f));
            path.reset();
            if (kind == CLOSE) {
                p.setStyle(Paint.Style.STROKE);
                float k = s * 0.36f;
                c.drawLine(cx - k, cy - k, cx + k, cy + k, p);
                c.drawLine(cx + k, cy - k, cx - k, cy + k, p);
            } else if (kind == SAVE) {
                p.setStyle(Paint.Style.STROKE);
                float k = s * 0.42f;
                c.drawLine(cx, cy - k, cx, cy + k * 0.35f, p);
                path.moveTo(cx - k * 0.6f, cy - k * 0.2f);
                path.lineTo(cx, cy + k * 0.4f);
                path.lineTo(cx + k * 0.6f, cy - k * 0.2f);
                c.drawPath(path, p);
                c.drawLine(cx - k, cy + k, cx + k, cy + k, p);
            } else if (kind == PLAY) {
                p.setStyle(Paint.Style.FILL);
                p.setPathEffect(new android.graphics.CornerPathEffect(s * 0.14f));
                float tw = s * 0.82f, th = s * 0.96f, x = cx - tw * 0.38f;
                path.moveTo(x, cy - th / 2f);
                path.lineTo(x + tw, cy);
                path.lineTo(x, cy + th / 2f);
                path.close();
                c.drawPath(path, p);
            } else if (kind == PAUSE) {
                p.setStyle(Paint.Style.FILL);
                float bw = s * 0.26f, bh = s * 0.92f, gap = s * 0.16f, rr = bw * 0.4f;
                r.set(cx - gap - bw, cy - bh / 2f, cx - gap, cy + bh / 2f);
                c.drawRoundRect(r, rr, rr, p);
                r.set(cx + gap, cy - bh / 2f, cx + gap + bw, cy + bh / 2f);
                c.drawRoundRect(r, rr, rr, p);
            } else if (kind == REWIND || kind == FORWARD || kind == REPLAY) {
                boolean fwd = kind == FORWARD;
                p.setStyle(Paint.Style.STROKE);
                float rad = s * 0.62f;
                r.set(cx - rad, cy - rad, cx + rad, cy + rad);
                // an open ring with its gap at the top; the arrow head closes it and gives the direction
                c.drawArc(r, fwd ? -60f : -120f, fwd ? 300f : -300f, false, p);
                p.setStyle(Paint.Style.FILL);
                float a = s * 0.26f, dir = fwd ? 1f : -1f, hy = cy - rad;
                float hx = cx + dir * rad * 0.5f;
                path.moveTo(hx + dir * a * 0.9f, hy + a * 0.15f);
                path.lineTo(hx - dir * a * 0.6f, hy - a * 0.8f);
                path.lineTo(hx - dir * a * 0.6f, hy + a * 1.0f);
                path.close();
                c.drawPath(path, p);
                if (kind != REPLAY) {
                    p.setTextAlign(Paint.Align.CENTER);
                    p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    p.setTextSize(s * 0.56f);
                    Paint.FontMetrics fm = p.getFontMetrics();
                    c.drawText("10", cx, cy - (fm.ascent + fm.descent) / 2f + s * 0.04f, p);
                }
            } else {
                // speaker
                p.setStyle(Paint.Style.FILL);
                p.setPathEffect(new android.graphics.CornerPathEffect(s * 0.08f));
                float k = s * 0.5f, x0 = cx - k;
                path.moveTo(x0, cy - k * 0.36f);
                path.lineTo(x0 + k * 0.5f, cy - k * 0.36f);
                path.lineTo(x0 + k * 1.05f, cy - k * 0.9f);
                path.lineTo(x0 + k * 1.05f, cy + k * 0.9f);
                path.lineTo(x0 + k * 0.5f, cy + k * 0.36f);
                path.lineTo(x0, cy + k * 0.36f);
                path.close();
                c.drawPath(path, p);
                p.setPathEffect(null);
                p.setStyle(Paint.Style.STROKE);
                if (kind == SOUND) {
                    r.set(cx - k * 0.2f, cy - k * 0.55f, cx + k * 0.9f, cy + k * 0.55f);
                    c.drawArc(r, -50f, 100f, false, p);
                    r.set(cx - k * 0.55f, cy - k * 0.95f, cx + k * 1.35f, cy + k * 0.95f);
                    c.drawArc(r, -45f, 90f, false, p);
                } else {
                    float mx = cx + k * 0.75f, q = k * 0.34f;
                    c.drawLine(mx - q, cy - q, mx + q, cy + q, p);
                    c.drawLine(mx + q, cy - q, mx - q, cy + q, p);
                }
            }
        }
    }

    // ------------------------------------------------------------------ zoomable picture

    /** Pinch to zoom (up to 6×), double tap to zoom in / back, drag to move; the picture never leaves the screen. */
    static final class Zoom extends ImageView {
        Runnable onTap;
        private final Matrix m = new Matrix();
        private final float[] v = new float[9];
        private float fit = 1f, scale = 1f;
        private Bitmap bmp;
        private final ScaleGestureDetector sg;
        private final GestureDetector gd;

        Zoom(Context c) {
            super(c);
            setScaleType(ScaleType.MATRIX);
            sg = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector d) {
                    zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                    return true;
                }
            });
            gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent e) { return true; }
                @Override public boolean onSingleTapConfirmed(MotionEvent e) { if (onTap != null) onTap.run(); return true; }
                @Override public boolean onDoubleTap(MotionEvent e) {
                    if (scale > fit * 1.3f) fitNow(); else zoomBy(fit * 2.6f / scale, e.getX(), e.getY());
                    return true;
                }
                @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                    if (scale <= fit * 1.01f) return false;
                    m.postTranslate(-dx, -dy);
                    clamp();
                    return true;
                }
            });
        }

        void setBitmap(Bitmap b) {
            bmp = b;
            setImageBitmap(b);
            fitNow();
        }

        void release() {
            setImageDrawable(null);
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
            bmp = null;
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            fitNow();
        }

        private void fitNow() {
            if (bmp == null || getWidth() == 0 || getHeight() == 0) return;
            fit = Math.min(getWidth() / (float) bmp.getWidth(), getHeight() / (float) bmp.getHeight());
            scale = fit;
            m.reset();
            m.postScale(fit, fit);
            m.postTranslate((getWidth() - bmp.getWidth() * fit) / 2f, (getHeight() - bmp.getHeight() * fit) / 2f);
            setImageMatrix(m);
        }

        private void zoomBy(float f, float px, float py) {
            if (bmp == null) return;
            float target = Math.max(fit, Math.min(fit * 6f, scale * f));
            float k = target / scale;
            scale = target;
            m.postScale(k, k, px, py);
            clamp();
        }

        private void clamp() {
            if (bmp == null) return;
            m.getValues(v);
            float w = bmp.getWidth() * scale, h = bmp.getHeight() * scale;
            float x = v[Matrix.MTRANS_X], y = v[Matrix.MTRANS_Y];
            float nx = w <= getWidth() ? (getWidth() - w) / 2f : Math.max(getWidth() - w, Math.min(0, x));
            float ny = h <= getHeight() ? (getHeight() - h) / 2f : Math.max(getHeight() - h, Math.min(0, y));
            m.postTranslate(nx - x, ny - y);
            setImageMatrix(m);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            sg.onTouchEvent(e);
            if (!sg.isInProgress()) gd.onTouchEvent(e);
            return true;
        }
    }
}
