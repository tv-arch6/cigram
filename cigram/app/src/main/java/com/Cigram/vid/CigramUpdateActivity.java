package com.Cigram.vid;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;

/**
 * «التحقق من التحديثات» — a page of its own.
 *
 * It opens, asks the server for the latest release and shows one of two states:
 *
 *   آخر نسخة        the installed version, a tick, nothing to do;
 *   اكتشف تحديثًا    the new version and what changed, and the update starts BY ITSELF: the package is downloaded
 *                    inside the app with a real progress bar (bytes received / total, refreshed five times a second),
 *                    checked ({@link CigramUpdateVerify}) and handed to Android's installer the moment it is complete.
 *                    The viewer is not asked "update now?" by the app at any point.
 *
 * Nothing leaves the app: no browser, no store page (the Google Play build, where self-update is not allowed, is the
 * one exception — there the page opens the app's Play listing). A failure is not shown as an error text: the page
 * quietly tries again, and offers one «إعادة المحاولة» button when the phone is offline.
 */
public class CigramUpdateActivity extends Activity {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CigramPalette pal;
    private TextView title, line, percent, notes, action;
    private Bar bar;
    private View tick;
    private boolean closed, working, waitingPermission;
    private JSONObject manifest;
    private long downloadId = -1L;
    private int attempts;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        pal = CigramPalette.of(this);
        build();
        check();
    }

    @Override protected void onResume() {
        super.onResume();
        // back from «السماح من هذا المصدر»: carry on without another tap
        if (waitingPermission && manifest != null && canInstall()) {
            waitingPermission = false;
            download();
        }
    }

    @Override protected void onDestroy() {
        closed = true;
        super.onDestroy();
    }

    private int dp(float v) { return CigramPalette.dp(this, v); }

    // ------------------------------------------------------------------ layout

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(CigramAccountDash.pageGradient(pal));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(24), dp(72), dp(24), dp(32));
        scroll.addView(col, new FrameLayout.LayoutParams(-1, -2));

        FrameLayout badge = new FrameLayout(this);
        badge.setBackground(CigramPalette.round(this, pal.surfaceHi, pal.stroke, 34));
        android.widget.ImageView mark = new android.widget.ImageView(this);
        mark.setImageResource(R.drawable.cigram_mark);
        mark.setColorFilter(pal.text, android.graphics.PorterDuff.Mode.SRC_IN);
        badge.addView(mark, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        tick = new AdsIcons.Icon(this, AdsIcons.CHECK_CIRCLE, pal.text);
        tick.setVisibility(View.GONE);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM | Gravity.RIGHT);
        tl.setMargins(0, 0, dp(6), dp(6));
        badge.addView(tick, tl);
        col.addView(badge, new LinearLayout.LayoutParams(dp(108), dp(108)));

        Typeface bold = CigramTypefaces.bold() != null ? CigramTypefaces.bold() : Typeface.DEFAULT_BOLD;
        title = CigramUI.label(this, "التحقق من التحديثات", 24f, pal.text, true);
        title.setTypeface(bold, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        col.addView(title, lp(-1, -2, 22));
        line = CigramUI.label(this, "جارٍ البحث عن آخر إصدار…", 14f, pal.text3, false);
        line.setGravity(Gravity.CENTER);
        col.addView(line, lp(-1, -2, 6));

        bar = new Bar(this, pal);
        bar.setVisibility(View.INVISIBLE);
        col.addView(bar, lp(-1, dp(8), 30));
        percent = CigramUI.label(this, "", 13f, pal.text2, true);
        percent.setGravity(Gravity.CENTER);
        percent.setTextDirection(View.TEXT_DIRECTION_LTR);
        col.addView(percent, lp(-1, -2, 10));

        notes = CigramUI.label(this, "", 13.5f, pal.text2, false);
        notes.setGravity(Gravity.RIGHT);
        notes.setLineSpacing(0f, 1.3f);
        notes.setPadding(dp(16), dp(14), dp(16), dp(14));
        notes.setBackground(CigramPalette.round(this, pal.surface, pal.stroke, 18));
        notes.setVisibility(View.GONE);
        col.addView(notes, lp(-1, -2, 24));

        action = CigramUI.label(this, "إعادة المحاولة", 15f, pal.light ? 0xFFFFFFFF : 0xFF000000, true);
        action.setGravity(Gravity.CENTER);
        action.setBackground(CigramPalette.round(this, pal.light ? 0xFF0B0B0C : 0xFFFFFFFF, 0, 16));
        action.setClickable(true);
        action.setVisibility(View.GONE);
        col.addView(action, lp(-1, dp(50), 26));
        setContentView(scroll);
    }

    private LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(top);
        return p;
    }

    private void offer(String label, View.OnClickListener click) {
        action.setText(label);
        action.setOnClickListener(click);
        action.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ 1) what is the latest release?

    private void check() {
        if (working) return;
        working = true;
        action.setVisibility(View.GONE);
        title.setText("التحقق من التحديثات");
        line.setText("جارٍ البحث عن آخر إصدار…");
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        new Thread(new Runnable() {
            @Override public void run() {
                final JSONObject m = CigramUpdateChecker.fetchManifest();
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        working = false;
                        if (closed) return;
                        if (m == null) { quietRetry(new Runnable() { @Override public void run() { check(); } }); return; }
                        attempts = 0;
                        if (!CigramUpdateChecker.hasUpdateFor(CigramUpdateActivity.this, m)) { upToDate(); return; }
                        manifest = m;
                        found();
                    }
                });
            }
        }, "cigram-update-check").start();
    }

    /** Two silent tries a few seconds apart; then one button (the phone is most likely offline). No error text. */
    private void quietRetry(final Runnable again) {
        attempts++;
        if (attempts <= 2) {
            MAIN.postDelayed(new Runnable() { @Override public void run() { if (!closed) again.run(); } }, 2500L * attempts);
            return;
        }
        attempts = 0;
        bar.setVisibility(View.INVISIBLE);
        percent.setText("");
        line.setText("بانتظار الاتصال بالإنترنت");
        offer("إعادة المحاولة", new View.OnClickListener() { @Override public void onClick(View v) { again.run(); } });
    }

    private void upToDate() {
        bar.setVisibility(View.INVISIBLE);
        tick.setVisibility(View.VISIBLE);
        title.setText("أنت على آخر نسخة");
        String name = CigramUpdateChecker.installedName(this);
        line.setText(name.length() == 0 ? "تطبيقك محدّث بالكامل" : "الإصدار " + name);
    }

    // ------------------------------------------------------------------ 2) «اكتشف تحديثًا»: it starts by itself

    private void found() {
        title.setText("اكتشف تحديثًا");
        String name = manifest.optString("versionName", "");
        line.setText(name.length() > 0 ? "الإصدار " + name + " جاهز لك" : "نسخة جديدة جاهزة لك");
        String changes = manifest.optString("notes", manifest.optString("changelog", manifest.optString("message", ""))).trim();
        if (changes.length() > 0) {
            notes.setText(changes);
            notes.setVisibility(View.VISIBLE);
        }
        if (!BuildConfig.SELF_UPDATE) {             // Google Play build: updates come from Play only
            bar.setVisibility(View.INVISIBLE);
            offer("التحديث من Google Play", new View.OnClickListener() {
                @Override public void onClick(View v) { CigramUpdateChecker.startUpdate(CigramUpdateActivity.this, manifest, CigramUpdateChecker.urlOf(manifest)); }
            });
            return;
        }
        download();
    }

    private boolean canInstall() {
        try {
            return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
        } catch (Throwable e) {
            return true;
        }
    }

    private void download() {
        if (working || closed) return;
        final String url = CigramUpdateChecker.urlOf(manifest);
        if (url.length() == 0) { upToDate(); return; }
        action.setVisibility(View.GONE);
        if (!canInstall()) {
            // Android asks for this once per app; the page opens the switch itself and continues on return
            waitingPermission = true;
            bar.setVisibility(View.INVISIBLE);
            line.setText("فعّل «السماح من هذا المصدر» لسيكرام وسيكتمل التحديث وحده");
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } catch (Throwable ignored) { }
            offer("فتح الإعداد", new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); } catch (Throwable ignored) { }
                }
            });
            return;
        }
        final DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) { quietRetry(new Runnable() { @Override public void run() { download(); } }); return; }
        working = true;
        final int live = manifest.optInt("versionCode", 0);
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            String fileName = "cigram-update-" + live + ".apk";
            if (dir != null) { File old = new File(dir, fileName); if (old.exists()) old.delete(); }
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("تحديث سيكرام");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            req.setAllowedOverMetered(true);
            req.setAllowedOverRoaming(true);
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);
            downloadId = dm.enqueue(req);
        } catch (Throwable error) {
            working = false;
            quietRetry(new Runnable() { @Override public void run() { download(); } });
            return;
        }
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        line.setText("جارٍ تنزيل التحديث داخل التطبيق…");
        final long id = downloadId;
        final long started = android.os.SystemClock.elapsedRealtime();
        final Runnable[] poll = new Runnable[1];
        poll[0] = new Runnable() {
            @Override public void run() {
                if (closed) { try { dm.remove(id); } catch (Throwable ignored) { } return; }
                Cursor c = null;
                try {
                    c = dm.query(new DownloadManager.Query().setFilterById(id));
                    if (c != null && c.moveToFirst()) {
                        int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        long got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            working = false;
                            attempts = 0;
                            bar.setIndeterminate(false);
                            bar.set(1f);
                            percent.setText("100%");
                            line.setText("اكتمل التنزيل · جارٍ التثبيت");
                            CigramUpdateChecker.installFromPage(CigramUpdateActivity.this, dm, id, url, manifest, live);
                            offer("تثبيت التحديث", new View.OnClickListener() {
                                @Override public void onClick(View v) { CigramUpdateChecker.installFromPage(CigramUpdateActivity.this, dm, id, url, manifest, live); }
                            });
                            return;
                        }
                        if (status == DownloadManager.STATUS_FAILED) {
                            working = false;
                            try { dm.remove(id); } catch (Throwable ignored) { }
                            quietRetry(new Runnable() { @Override public void run() { download(); } });
                            return;
                        }
                        if (total > 0L) {
                            float f = Math.min(1f, got / (float) total);
                            bar.setIndeterminate(false);
                            bar.set(f);
                            long secs = Math.max(1L, (android.os.SystemClock.elapsedRealtime() - started) / 1000L);
                            percent.setText((int) (f * 100) + "%  ·  " + size(got) + " / " + size(total) + "  ·  " + size(got / secs) + "/s");
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (c != null) c.close();
                }
                MAIN.postDelayed(poll[0], 200L);
            }
        };
        MAIN.postDelayed(poll[0], 150L);
    }

    private static String size(long bytes) {
        if (bytes >= 1048576L) return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576f);
        return Math.max(0L, bytes / 1024L) + " KB";
    }

    /** A thin rounded bar: the real fraction when it is known, a gliding segment while it is not. */
    static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float shown, target;
        private boolean indeterminate;

        Bar(Context c, CigramPalette pal) {
            super(c);
            track.setColor(pal.light ? 0x1F000000 : 0x29FFFFFF);
            fill.setColor(pal.light ? 0xFF0B0B0C : 0xFFFFFFFF);
        }

        void setIndeterminate(boolean on) { if (indeterminate != on) { indeterminate = on; invalidate(); } }

        void set(float f) { target = Math.max(target, f); invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = h / 2f;
            r.set(0, 0, w, h);
            c.drawRoundRect(r, rad, rad, track);
            if (indeterminate) {
                float t = (android.os.SystemClock.uptimeMillis() % 1100L) / 1100f, seg = w * 0.3f;
                float x = -seg + (w + seg) * t;
                r.set(Math.max(0, x), 0, Math.min(w, x + seg), h);
                c.drawRoundRect(r, rad, rad, fill);
                postInvalidateOnAnimation();
                return;
            }
            shown += (target - shown) * 0.25f;                     // eased towards the real value, never ahead of it
            if (target - shown < 0.002f) shown = target;
            r.set(w * (1f - shown), 0, w, h);                      // fills from the right (Arabic page)
            c.drawRoundRect(r, rad, rad, fill);
            if (shown != target) postInvalidateOnAnimation();
        }
    }
}
