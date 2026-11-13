package com.Cigram.vid;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-update checker for Cigram.
 *
 * Reads the public manifest published by "إدارة تحديث التطبيق" (GET /update on the admin Worker,
 * the same cigram_update.json) and shows an update dialog on the home screen:
 *  - optional / important / beta : [تحديث الآن] [لاحقاً]  (snoozed 24h per version)
 *  - force, installed < minVersionCode, or installed version is in blockedVersions :
 *    one button only, cannot be dismissed.
 * Failures are silent: a network/JSON problem never blocks the app.
 */
public final class CigramUpdateChecker {

    private static final String BASE = "https://cigram-admin-api.wwq-mixtv.workers.dev";
    private static final String PREFS = "cigram_update_check";
    private static final String KEY_SNOOZE_CODE = "snooze_code";
    private static final String KEY_SNOOZE_UNTIL = "snooze_until";
    private static final String KEY_DEVICE = "device_id";

    private static final long MIN_INTERVAL_MS = 10L * 60L * 1000L;
    private static final long SNOOZE_MS = 24L * 60L * 60L * 1000L;

    private static final int BG = 0xFF1B1B1F;
    private static final int BG_SOFT = 0xFF26262B;
    private static final int TEAL = 0xFF26A5B5;
    private static final int TEXT_MAIN = 0xFFFFFFFF;
    private static final int TEXT_MUTED = 0xFF8E8E94;
    private static final int WARN = 0xFFE0B04A;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private static volatile boolean running;
    private static volatile boolean showing;
    private static long lastCheckAt;
    private static JSONObject cached;          // last manifest that required a dialog
    private static long shownEventFor = -1L;   // versionCode whose "shown" event was already sent

    private CigramUpdateChecker() {
    }

    /** Call when the home screen is bound. Safe to call many times. */
    public static void check(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (showing) return;

        // A mandatory update must come back even if the check itself is throttled.
        final JSONObject keep = cached;
        if (keep != null && isMandatory(activity, keep)) {
            show(activity, keep);
            return;
        }

        long now = System.currentTimeMillis();
        if (running) return;
        if (lastCheckAt > 0L && now - lastCheckAt < MIN_INTERVAL_MS) return;
        running = true;

        final Context app = activity.getApplicationContext() == null
                ? activity : activity.getApplicationContext();

        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String body = httpGet(BASE + "/update");
                    lastCheckAt = System.currentTimeMillis();
                    if (body == null) return;
                    final JSONObject manifest = new JSONObject(body);
                    if (!needsDialog(app, manifest)) {
                        cached = null;
                        return;
                    }
                    cached = manifest;
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            Activity a = activity;
                            if (a.isFinishing()) return;
                            if (Build.VERSION.SDK_INT >= 17 && a.isDestroyed()) return;
                            openPage(a);
                        }
                    });
                } catch (Throwable ignored) {
                    android.util.Log.w("CigramUpdate", "check failed", ignored);
                } finally {
                    running = false;
                }
            }
        });
    }

    // ------------------------------------------------------------------ decision

    /** A release is waiting: the update page opens and starts by itself (the Play build keeps its dialog → Play). */
    private static void openPage(Activity activity) {
        if (!BuildConfig.SELF_UPDATE) { if (cached != null) show(activity, cached); return; }
        // once a day at most: the page is opened for the viewer, it is not forced on every return to Home
        if (cached != null && !isMandatory(activity, cached)) snooze(activity, cached.optInt("versionCode", 0));
        manualCheck(activity);
    }

    private static int installedCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionCode;
        } catch (Throwable error) {
            return 0;
        }
    }

    private static boolean isBlocked(JSONObject m, int installed) {
        JSONArray blocked = m.optJSONArray("blockedVersions");
        for (int i = 0; blocked != null && i < blocked.length(); i++) {
            if (blocked.optInt(i, -1) == installed) return true;
        }
        return false;
    }

    private static String downloadUrl(JSONObject m) {
        String url = m.optString("downloadUrl", "");
        if (url.length() == 0) url = m.optString("url", "");
        url = url.trim();
        // 19.6: HTTPS only (a plain-http package link can be swapped in transit) and no user-info trick in the host
        return CigramUpdateVerify.isSecureUrl(url) ? url : "";
    }

    /** True when a newer, published release exists that the user can actually download. */
    private static boolean hasUpdate(Context context, JSONObject m) {
        if (m == null || !m.optBoolean("published", false)) return false;
        if (downloadUrl(m).length() == 0) return false;
        int installed = installedCode(context);
        return installed > 0 && m.optInt("versionCode", 0) > installed;
    }

    private static boolean isMandatory(Context context, JSONObject m) {
        if (!hasUpdate(context, m)) return false;
        int installed = installedCode(context);
        String type = m.optString("updateType", "optional");
        int min = m.optInt("minVersionCode", 0);
        return m.optBoolean("force", false)
                || "force".equals(type)
                || (min > 0 && installed < min)
                || isBlocked(m, installed);
    }

    private static boolean needsDialog(Context context, JSONObject m) {
        if (!hasUpdate(context, m)) return false;
        if (isMandatory(context, m)) return true;
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long snoozedCode = p.getLong(KEY_SNOOZE_CODE, -1L);
        long until = p.getLong(KEY_SNOOZE_UNTIL, 0L);
        return !(snoozedCode == m.optInt("versionCode", 0) && System.currentTimeMillis() < until);
    }

    // ------------------------------------------------------------------ dialog

    private static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(CigramUI.font(c), bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setGravity(Gravity.RIGHT);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        return t;
    }

    private static TextView button(Context c, String label, boolean primary) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(CigramUI.font(c), Typeface.BOLD);
        b.setTextColor(primary ? 0xFF062028 : TEXT_MAIN);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(c, 14));
        if (primary) {
            bg.setColor(TEAL);
        } else {
            bg.setColor(BG_SOFT);
            bg.setStroke(dp(c, 1), 0xFF2B4A57);
        }
        b.setBackground(bg);
        b.setPadding(dp(c, 12), dp(c, 13), dp(c, 12), dp(c, 13));
        b.setClickable(true);
        return b;
    }

    private static void show(final Activity activity, final JSONObject m) {
        if (showing || activity == null || activity.isFinishing()) return;
        try {
            final int live = m.optInt("versionCode", 0);
            final boolean mandatory = isMandatory(activity, m);
            final String type = m.optString("updateType", "optional");
            final String url = downloadUrl(m);

            final Dialog dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            card.setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 16));
            GradientDrawable cardBg = new GradientDrawable();
            cardBg.setColor(BG);
            cardBg.setCornerRadius(dp(activity, 22));
            cardBg.setStroke(dp(activity, 1), 0xFF2B4A57);
            card.setBackground(cardBg);

            // app logo at the top of the update card
            try {
                int logoRes = activity.getResources().getIdentifier("cigram_logo", "drawable", activity.getPackageName());
                if (logoRes != 0) {
                    android.widget.ImageView logo = new android.widget.ImageView(activity);
                    logo.setImageResource(logoRes);
                    logo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                    final int lr = dp(activity, 16);
                    logo.setOutlineProvider(new android.view.ViewOutlineProvider() {
                        @Override public void getOutline(View v, android.graphics.Outline o) {
                            o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), lr);
                        }
                    });
                    logo.setClipToOutline(true);
                    LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(activity, 64), dp(activity, 64));
                    llp.gravity = Gravity.CENTER_HORIZONTAL;
                    llp.bottomMargin = dp(activity, 10);
                    card.addView(logo, llp);
                }
            } catch (Throwable ignored) { }

            String badge = mandatory ? "تحديث إجباري"
                    : "important".equals(type) ? "تحديث مهم"
                    : "beta".equals(type) ? "نسخة تجريبية" : "تحديث جديد";
            card.addView(text(activity, badge, 12, mandatory ? WARN : TEAL, true));

            String title = m.optString("title", "").trim();
            if (title.length() == 0) title = "تحديث جديد لسيكرام";
            TextView t = text(activity, title, 20, TEXT_MAIN, true);
            t.setPadding(0, dp(activity, 6), 0, 0);
            card.addView(t);

            String versionName = m.optString("versionName", "").trim();
            if (versionName.length() > 0) {
                String line = "الإصدار " + versionName;
                long size = m.optLong("fileSize", 0L);
                if (size > 0L) line += "  •  " + humanSize(size);
                TextView v = text(activity, line, 13, TEXT_MUTED, false);
                v.setPadding(0, dp(activity, 4), 0, dp(activity, 10));
                card.addView(v);
            }

            LinearLayout body = new LinearLayout(activity);
            body.setOrientation(LinearLayout.VERTICAL);
            String desc = m.optString("description", "").trim();
            if (desc.length() == 0) desc = m.optString("message", "").trim();
            JSONArray log = m.optJSONArray("changelog");
            boolean hasLog = log != null && log.length() > 0;
            // "message" is description + changelog glued together; when we have the list, prefer description only
            if (hasLog && m.optString("description", "").trim().length() == 0) desc = "";
            if (desc.length() > 0) {
                body.addView(text(activity, desc, 14, TEXT_MAIN, false));
            }
            if (hasLog) {
                TextView head = text(activity, "ما الجديد", 14, TEAL, true);
                head.setPadding(0, desc.length() > 0 ? dp(activity, 12) : 0, 0, dp(activity, 4));
                body.addView(head);
                for (int i = 0; i < log.length() && i < 12; i++) {
                    String item = log.optString(i, "").trim();
                    if (item.length() == 0) continue;
                    TextView row = text(activity, "•  " + item, 14, TEXT_MAIN, false);
                    row.setPadding(0, dp(activity, 2), 0, dp(activity, 2));
                    body.addView(row);
                }
            }
            if (body.getChildCount() > 0) {
                ScrollView scroll = new ScrollView(activity);
                scroll.addView(body);
                card.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
                // keep the buttons visible on small screens
                int maxH = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.38f);
                limitHeight(scroll, maxH);
            }

            if (mandatory) {
                TextView note = text(activity, "يجب تحديث التطبيق للمتابعة.", 13, WARN, false);
                note.setPadding(0, dp(activity, 12), 0, 0);
                card.addView(note);
            }

            LinearLayout buttons = new LinearLayout(activity);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
            bp.topMargin = dp(activity, 16);
            card.addView(buttons, bp);

            TextView now = button(activity, "تحديث الآن", true);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(0, -2, 1f);
            buttons.addView(now, np);
            now.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    sendEvent(activity, "update_now", live);
                    startUpdate(activity, m, url);
                    if (!mandatory) dialog.dismiss();
                }
            });

            if (!mandatory) {
                TextView later = button(activity, "لاحقاً", false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.setMarginEnd(dp(activity, 10));
                buttons.addView(later, lp);
                later.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        snooze(activity, live);
                        sendEvent(activity, "later", live);
                        dialog.dismiss();
                    }
                });
            }

            dialog.setContentView(card);
            dialog.setCancelable(!mandatory);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                @Override public void onDismiss(android.content.DialogInterface d) {
                    showing = false;
                }
            });
            if (!mandatory) {
                dialog.setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                    @Override public void onCancel(android.content.DialogInterface d) {
                        snooze(activity, live);
                        sendEvent(activity, "later", live);
                    }
                });
            }

            Window w = dialog.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f);
                w.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }

            showing = true;
            dialog.show();
            CigramTvFocus.attachDialog(dialog);   // v10.66: TV remote (TV only)
            if (shownEventFor != live) {
                shownEventFor = live;
                sendEvent(activity, "shown", live);
            }
        } catch (Throwable error) {
            showing = false;
            android.util.Log.w("CigramUpdate", "show failed", error);
        }
    }

    /** Caps a ScrollView's height without a custom subclass. */
    private static void limitHeight(final ScrollView scroll, final int maxPx) {
        scroll.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        ViewGroup.LayoutParams lp = scroll.getLayoutParams();
                        View child = scroll.getChildAt(0);
                        if (child != null && lp != null && child.getHeight() > maxPx
                                && lp.height != maxPx) {
                            lp.height = maxPx;
                            scroll.setLayoutParams(lp);
                            return false;
                        }
                        return true;
                    }
                });
    }

    private static String humanSize(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1.0) return String.format(java.util.Locale.US, "%.1f MB", mb);
        return Math.max(1, bytes / 1024) + " KB";
    }

    private static void snooze(Context context, int versionCode) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(KEY_SNOOZE_CODE, versionCode)
                    .putLong(KEY_SNOOZE_UNTIL, System.currentTimeMillis() + SNOOZE_MS)
                    .apply();
        } catch (Throwable ignored) {
            android.util.Log.w("CigramUpdate", "snooze failed", ignored);
        }
    }

    // ------------------------------------------------------------ manual check

    /** Current installed version name, e.g. "2.5". */
    public static String installedName(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable error) {
            return "";
        }
    }

    /**
     * "التحقق من التحديثات" row of the account page. Always answers the user:
     * checking -> (update available | you have the latest version | could not check).
     * An update found here is shown even if it was snoozed before.
     */
    /** «التحقق من التحديثات» opens its own page (see {@link CigramUpdateActivity}). */
    public static void manualCheck(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            activity.startActivity(new Intent(activity, CigramUpdateActivity.class));
        } catch (Throwable error) {
            android.util.Log.w("CigramUpdate", "page failed", error);
        }
    }

    // ---- what the page needs (same manifest, same rules as the automatic check) ----

    /** The latest release as the server publishes it, or null when it cannot be read now. Blocking. */
    static JSONObject fetchManifest() {
        String body = null;
        try { body = httpGet(BASE + "/update?t=" + System.currentTimeMillis()); } catch (Throwable ignored) { }
        if (body == null) body = httpGet(BASE + "/update");
        try { return body == null ? null : new JSONObject(body); } catch (Throwable ignored) { return null; }
    }

    static boolean hasUpdateFor(Context context, JSONObject m) { return hasUpdate(context, m); }

    static String urlOf(JSONObject m) { return m == null ? "" : downloadUrl(m); }

    /** Checks the downloaded package and opens Android's installer (no question asked by the app). */
    static void installFromPage(Activity activity, android.app.DownloadManager dm, long id, String url, JSONObject m, int live) {
        sendEvent(activity, "download", live);
        installDownloaded(activity, dm, id, url, m, live);
    }

    // --------------------------------------------------------- download + install

    private static long activeDownload = -1L;

    /**
     * Downloads the APK inside the app (progress dialog) and opens the system installer.
     * Falls back to the browser when the download cannot be started or the file is not an APK.
     */
    public static void startUpdate(final Activity activity, final JSONObject m, final String url) {
        if (activity == null || activity.isFinishing()) return;
        if (!BuildConfig.SELF_UPDATE) { // Google Play build: updates only through Play
            openPlayListing(activity);
            return;
        }
        if (url == null || url.length() == 0) return;
        final int live = m == null ? 0 : m.optInt("versionCode", 0);
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                CigramUI.sheet(activity)
                        .logo()
                        .title("السماح بتثبيت التحديث")
                        .message("لتثبيت النسخة الجديدة مباشرة، فعّل خيار «السماح من هذا المصدر» لسيكرام "
                                + "ثم ارجع واضغط تحديث مرة أخرى.")
                        .primary("فتح الإعدادات", CigramUI.AMBER, new CigramUI.Click() {
                            @Override public boolean onClick(CigramUI.Sheet s) {
                                try {
                                    Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            Uri.parse("package:" + activity.getPackageName()));
                                    activity.startActivity(i);
                                } catch (Throwable error) {
                                    openInBrowser(activity, url);
                                }
                                return false;
                            }
                        })
                        .secondary("فتح الرابط في المتصفح", new CigramUI.Click() {
                            @Override public boolean onClick(CigramUI.Sheet s) {
                                openInBrowser(activity, url);
                                return false;
                            }
                        })
                        .show();
                return;
            }

            final android.app.DownloadManager dm =
                    (android.app.DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) { openInBrowser(activity, url); return; }

            final java.io.File dir = activity.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
            final String fileName = "cigram-update-" + live + ".apk";
            if (dir != null) {
                java.io.File old = new java.io.File(dir, fileName);
                if (old.exists()) old.delete();
            }
            android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(Uri.parse(url));
            req.setTitle("تحديث سيكرام");
            req.setDescription("جارٍ تنزيل النسخة الجديدة");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE);
            req.setAllowedOverMetered(true);
            req.setAllowedOverRoaming(true);
            req.setDestinationInExternalFilesDir(activity, android.os.Environment.DIRECTORY_DOWNLOADS, fileName);
            final long id = dm.enqueue(req);
            activeDownload = id;
            sendEvent(activity, "download", live);

            final TextView percent = CigramUI.label(activity, "جارٍ التنزيل... 0%", 14.5f, 0xCCFFFFFF, false);
            final android.widget.ProgressBar bar = new android.widget.ProgressBar(activity, null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(CigramUI.RED));
            bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF34343A));
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            box.addView(percent, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(activity, 8));
            bp.topMargin = dp(activity, 10);
            box.addView(bar, bp);

            final boolean[] stopped = new boolean[]{false};
            final CigramUI.Sheet sheet = CigramUI.sheet(activity)
                    .logo()
                    .title("تحديث سيكرام")
                    .message(m != null && m.optString("versionName", "").length() > 0
                            ? "تنزيل الإصدار " + m.optString("versionName") : "تنزيل النسخة الجديدة")
                    .view(box)
                    .cancelable(false)
                    .secondary("إلغاء التنزيل", new CigramUI.Click() {
                        @Override public boolean onClick(CigramUI.Sheet s) {
                            stopped[0] = true;
                            try { dm.remove(id); } catch (Throwable ignored) { }
                            return false;
                        }
                    });
            sheet.show();

            final Runnable[] poll = new Runnable[1];
            poll[0] = new Runnable() {
                @Override public void run() {
                    if (stopped[0] || activity.isFinishing()) return;
                    android.database.Cursor c = null;
                    try {
                        c = dm.query(new android.app.DownloadManager.Query().setFilterById(id));
                        if (c == null || !c.moveToFirst()) {
                            MAIN.postDelayed(poll[0], 500L);
                            return;
                        }
                        int status = c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS));
                        long got = c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                            sheet.dismiss();
                            installDownloaded(activity, dm, id, url, m, live);
                            return;
                        }
                        if (status == android.app.DownloadManager.STATUS_FAILED) {
                            sheet.dismiss();
                            downloadFailed(activity, url);
                            return;
                        }
                        if (total > 0L) {
                            int pm = (int) Math.min(1000L, got * 1000L / total);
                            bar.setProgress(pm);
                            percent.setText("جارٍ التنزيل... " + (pm / 10) + "%  ("
                                    + humanSize(got) + " / " + humanSize(total) + ")");
                        } else {
                            percent.setText("جارٍ التنزيل... " + humanSize(got));
                        }
                    } catch (Throwable error) {
                        android.util.Log.w("CigramUpdate", "poll failed", error);
                    } finally {
                        if (c != null) c.close();
                    }
                    MAIN.postDelayed(poll[0], 400L);
                }
            };
            MAIN.postDelayed(poll[0], 400L);
        } catch (Throwable error) {
            android.util.Log.w("CigramUpdate", "start failed", error);
            openInBrowser(activity, url);
        }
    }

    /**
     * 19.3: the package is checked (not empty, the manifest's sha256 when it has one, this app and a newer build) on the
     * updater's own thread BEFORE the installer is opened; a refused file is deleted and the viewer is told why.
     */
    private static void installDownloaded(final Activity activity, final android.app.DownloadManager dm,
                                          final long id, final String url, final JSONObject m, final int live) {
        final java.io.File dir = activity.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        final java.io.File apk = dir == null ? null : new java.io.File(dir, "cigram-update-" + live + ".apk");
        if (apk == null || !apk.exists()) { openInstaller(activity, dm, id, url); return; }
        IO.execute(new Runnable() {
            @Override public void run() {
                final CigramUpdateVerify.Verdict verdict =
                        CigramUpdateVerify.verify(activity, apk, m, installedCode(activity));
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (activity.isFinishing()) return;
                        if (verdict == CigramUpdateVerify.Verdict.OK) { openInstaller(activity, dm, id, url); return; }
                        try { dm.remove(id); } catch (Throwable ignored) { }
                        try { apk.delete(); } catch (Throwable ignored) { }
                        CigramDiag.recovery("updater", "verify", "INTEGRITY", "update refused: " + verdict.name(), null, 0, "failed");
                        CigramUI.sheet(activity)
                                .icon(CigramUI.ICON_WARN, CigramUI.AMBER)
                                .title("لم يُفتح التحديث")
                                .message(CigramUpdateVerify.messageFor(verdict))
                                .primary("حاول مرة أخرى", CigramUI.AMBER, new CigramUI.Click() {
                                    @Override public boolean onClick(CigramUI.Sheet s) {
                                        startUpdate(activity, m, url);
                                        return false;
                                    }
                                })
                                .secondary("إغلاق", null)
                                .show();
                    }
                });
            }
        });
    }

    private static void openInstaller(final Activity activity, android.app.DownloadManager dm,
                                      long id, final String url) {
        try {
            Uri uri = dm.getUriForDownloadedFile(id);
            String type = dm.getMimeTypeForDownloadedFile(id);
            if (uri == null) { downloadFailed(activity, url); return; }
            if (type != null && type.toLowerCase(java.util.Locale.US).contains("html")) {
                // the link was a web page, not a direct APK
                try { dm.remove(id); } catch (Throwable ignored) { }
                openInBrowser(activity, url);
                return;
            }
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Throwable error) {
            android.util.Log.w("CigramUpdate", "install failed", error);
            CigramUI.sheet(activity)
                    .logo()
                    .title("اكتمل التنزيل")
                    .message("افتح إشعار التنزيل أو مجلد التنزيلات لتثبيت النسخة الجديدة.")
                    .primary("فتح التنزيلات", CigramUI.GREEN, new CigramUI.Click() {
                        @Override public boolean onClick(CigramUI.Sheet s) {
                            try {
                                activity.startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            } catch (Throwable ignored) { }
                            return false;
                        }
                    })
                    .secondary("إغلاق", null)
                    .show();
        }
    }

    private static void downloadFailed(final Activity activity, final String url) {
        if (activity == null || activity.isFinishing()) return;
        CigramUI.sheet(activity)
                .icon(CigramUI.ICON_WARN, CigramUI.AMBER)
                .title("تعذر تنزيل التحديث")
                .message("حدثت مشكلة أثناء التنزيل. يمكنك المحاولة مرة أخرى أو فتح رابط التحديث في المتصفح.")
                .primary("فتح في المتصفح", CigramUI.AMBER, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) {
                        openInBrowser(activity, url);
                        return false;
                    }
                })
                .secondary("إغلاق", null)
                .show();
    }

    /** Play flavor: open the store listing (market:// first, https fallback). */
    private static void openPlayListing(Activity activity) {
        final String pkg = activity.getPackageName();
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)));
        } catch (Throwable noStore) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
            } catch (Throwable ignored) { }
        }
    }

    private static void openInBrowser(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable error) {
            CigramSilent.toast(activity, "تعذر فتح رابط التحديث", android.widget.Toast.LENGTH_SHORT);
        }
    }

    // ------------------------------------------------------------------ network

    private static String httpGet(String address) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setUseCaches(false);
            c.setRequestMethod("GET");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "Cigram-Android/1.0");
            if (c.getResponseCode() != 200) return null;
            in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 256 * 1024) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Throwable error) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) { }
            if (c != null) c.disconnect();
        }
    }

    private static String deviceId(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = p.getString(KEY_DEVICE, "");
        if (id == null || id.length() == 0) {
            id = UUID.randomUUID().toString();
            p.edit().putString(KEY_DEVICE, id).apply();
        }
        return id;
    }

    /** Anonymous statistics for the admin screen; never fails the app. */
    private static void sendEvent(final Context context, final String event, final int target) {
        CigramAnalytics.track(context, "update_" + event, "target", String.valueOf(target));
        final Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        IO.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    JSONObject o = new JSONObject();
                    o.put("event", event);
                    o.put("versionCode", target);
                    o.put("currentVersionCode", installedCode(app));
                    o.put("deviceId", deviceId(app));
                    byte[] data = o.toString().getBytes("UTF-8");
                    c = (HttpURLConnection) new URL(BASE + "/update/event").openConnection();
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(6000);
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json");
                    OutputStream out = c.getOutputStream();
                    out.write(data);
                    out.close();
                    c.getResponseCode();
                } catch (Throwable ignored) {
                    // analytics only
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        });
    }
}
