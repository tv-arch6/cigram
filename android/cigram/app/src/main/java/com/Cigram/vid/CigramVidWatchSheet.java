package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * CigramVidWatchSheet v1.0
 *
 * الـ bottom sheet الذي يظهر عند الضغط على زر "شاهد الآن" في صفحة التفاصيل.
 * يعرض بالضبط زرّين فقط:
 *
 *  ┌─────────────────────────────────────────────┐
 *  │              ════════                        │
 *  │   🎬  شاهد: Breaking Bad - م1 ح3           │
 *  │                                             │
 *  │  المصدر المحدد: vidfast  [تغيير ▾]         │
 *  │                                             │
 *  │  ┌───────────────┐  ┌───────────────────┐  │
 *  │  │  👥 سوية      │  │  👤 بمفردك  ▶     │  │
 *  │  └───────────────┘  └───────────────────┘  │
 *  │                                             │
 *  │  📄 ترجمة: لا يوجد   [إضافة SRT]          │
 *  └─────────────────────────────────────────────┘
 *
 * طريقة الاستخدام:
 * <pre>
 *   // من صفحة التفاصيل عند ضغط "شاهد":
 *   CigramVidWatchSheet.show(activity, contentItem, season, episode);
 *
 *   // للفيلم:
 *   CigramVidWatchSheet.show(activity, movieItem, 0, 0);
 * </pre>
 *
 * مميزات:
 *  - يستخدم المشغّلات الموجودة في سيكرام (CigramPlayerSheet settings مضمّنة)
 *  - يدعم تحميل ملفات SRT
 *  - يعرض مختار المصدر (vidfast / vidnest / videm / vidbolt)
 *  - يتذكّر آخر مصدر اختاره المستخدم
 */
public final class CigramVidWatchSheet {

    // palette
    private static final int BG       = 0xFF0D1117;
    private static final int CARD     = 0xFF161B22;
    private static final int ACCENT   = 0xFF2563EB;
    private static final int TOGETHER = 0xFF7C3AED;
    private static final int TEXT_P   = 0xFFE6EDF3;
    private static final int TEXT_S   = 0xFF8B949E;
    private static final int DIVIDER  = 0xFF21262D;
    private static final int CHIP_BG  = 0xFF1C2128;
    private static final int GREEN    = 0xFF4CAF50;

    private static final String PREFS = "cigram_watch_sheet";
    private static final String KEY_SOURCE = "last_source";

    private static final String[] SOURCE_NAMES = {"vidfast", "vidnest", "videm", "vidbolt"};
    private static final String[] SOURCE_DOMAINS = {
            "vidfast.vc", "vidnest.fun", "videm.xyz", "vidbolt.pro"
    };
    private static final String[] EMBED_MOVIE = {
            "https://vidfast.vc/movie/%s",
            "https://vidnest.fun/movie/%s",
            "https://videm.xyz/movie/%s",
            "https://vidbolt.pro/movie/%s"
    };
    private static final String[] EMBED_TV = {
            "https://vidfast.vc/tv/%s/%d/%d",
            "https://vidnest.fun/tv/%s/%d/%d",
            "https://videm.xyz/tv/%s/%d/%d",
            "https://vidbolt.pro/tv/%s/%d/%d"
    };

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static CigramVidWatchSheet active;
    public  static final int REQ_SRT = 0x5275;

    // state
    private final Activity activity;
    private final JSONObject item;
    private final int season;
    private final int episode;
    private int selectedSource;
    private String resolvedUrl = "";
    private String srtPath = "";
    private boolean resolving = false;

    // ui
    private PopupWindow popup;
    private TextView sourceLbl;
    private ProgressBar bar;
    private TextView statusTv;
    private TextView btnSolo;
    private TextView btnTogether;
    private TextView srtLbl;

    // ───────── entry ──────────────────────────────────────────────────────
    public static void show(Activity a, JSONObject contentItem, int season, int episode) {
        if (a == null || contentItem == null) return;
        active = new CigramVidWatchSheet(a, contentItem, season, episode);
        active.open();
    }

    private CigramVidWatchSheet(Activity a, JSONObject item, int season, int episode) {
        this.activity = a;
        this.item     = item;
        this.season   = season;
        this.episode  = episode;

        // restore last chosen source
        SharedPreferences p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.selectedSource = p.getInt(KEY_SOURCE, 0);
    }

    // ───────── build ──────────────────────────────────────────────────────
    private void open() {
        if (activity.isFinishing()) return;

        FrameLayout container = new FrameLayout(activity);
        container.setLayoutParams(new ViewGroup.LayoutParams(-1, -1));

        // dim
        View dim = new View(activity);
        dim.setBackgroundColor(0xBB000000);
        dim.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        dim.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { dismiss(); } });
        container.addView(dim);

        // sheet
        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
        sheet.setBackground(bg);
        sheet.setPadding(dp(20), dp(8), dp(20), dp(36));

        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, -2);
        slp.gravity = Gravity.BOTTOM;
        sheet.setLayoutParams(slp);
        container.addView(sheet);

        buildContent(sheet);

        popup = new PopupWindow(container, -1, -1, true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(false);
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.BOTTOM, 0, 0);

        // start resolving immediately
        startResolve();
    }

    private void buildContent(LinearLayout parent) {
        // handle
        View handle = new View(activity);
        GradientDrawable hd = new GradientDrawable();
        hd.setColor(0x60FFFFFF);
        hd.setCornerRadius(dp(2));
        handle.setBackground(hd);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(40), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.setMargins(0, dp(4), 0, dp(20));
        handle.setLayoutParams(hlp);
        parent.addView(handle);

        // title
        String title = tryText("series_title", "title", "name");
        String kind  = CigramContent.isSeries(item) ? "مسلسل" : "فيلم";
        String subTitle = season > 0 ? "  •  م" + season + " ح" + episode : "";

        TextView titleTv = new TextView(activity);
        titleTv.setText("🎬  شاهد: " + title + subTitle);
        titleTv.setTextColor(TEXT_P);
        titleTv.setTextSize(15);
        titleTv.setTypeface(CigramTypefaces.bold());
        titleTv.setGravity(Gravity.RIGHT);
        titleTv.setLayoutParams(fw(0, 0, 0, dp(20)));
        parent.addView(titleTv);

        // source selector row
        LinearLayout srcRow = new LinearLayout(activity);
        srcRow.setOrientation(LinearLayout.HORIZONTAL);
        srcRow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        srcRow.setLayoutParams(fw(0, 0, 0, dp(16)));

        sourceLbl = new TextView(activity);
        sourceLbl.setTextColor(TEXT_P);
        sourceLbl.setTextSize(13);
        sourceLbl.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        sourceLbl.setGravity(Gravity.RIGHT);
        updateSourceLabel();

        TextView srcChangeBtn = new TextView(activity);
        srcChangeBtn.setText("تغيير ▾");
        srcChangeBtn.setTextColor(ACCENT);
        srcChangeBtn.setTextSize(13);
        srcChangeBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showSourcePicker(); } });

        srcRow.addView(srcChangeBtn);
        srcRow.addView(sourceLbl);
        parent.addView(srcRow);

        // progress bar
        bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setVisibility(View.VISIBLE);
        bar.setLayoutParams(fw(0, 0, 0, dp(4)));
        parent.addView(bar);

        // status
        statusTv = new TextView(activity);
        statusTv.setTextColor(TEXT_S);
        statusTv.setTextSize(12);
        statusTv.setGravity(Gravity.RIGHT);
        statusTv.setText("جاري تحضير الرابط...");
        statusTv.setLayoutParams(fw(0, 0, 0, dp(16)));
        parent.addView(statusTv);

        // ── divider ──
        View div = new View(activity);
        div.setBackgroundColor(DIVIDER);
        div.setLayoutParams(fw(0, 0, 0, dp(16)));
        div.getLayoutParams().height = dp(1);
        parent.addView(div);

        // ── action buttons ──
        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setLayoutParams(fw(0, 0, 0, dp(16)));
        btnRow.setGravity(Gravity.CENTER);

        btnTogether = makeBtn("👥 سوية", TOGETHER);
        btnTogether.setAlpha(0.4f);
        btnTogether.setEnabled(false);
        btnTogether.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismiss();
                launchTogether();
            }
        });

        btnSolo = makeBtn("👤 بمفردك  ▶", ACCENT);
        btnSolo.setAlpha(0.4f);
        btnSolo.setEnabled(false);
        btnSolo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismiss();
                launchSolo();
            }
        });

        btnRow.addView(btnTogether);
        addSpace(btnRow, dp(12));
        btnRow.addView(btnSolo);
        parent.addView(btnRow);

        // ── subtitle row ──
        LinearLayout srtRow = new LinearLayout(activity);
        srtRow.setOrientation(LinearLayout.HORIZONTAL);
        srtRow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        srtRow.setLayoutParams(fw(0, dp(4), 0, 0));

        srtLbl = new TextView(activity);
        srtLbl.setText("📄  ترجمة: لا يوجد");
        srtLbl.setTextColor(TEXT_S);
        srtLbl.setTextSize(12);
        LinearLayout.LayoutParams srtLlp = new LinearLayout.LayoutParams(0, -2, 1f);
        srtLbl.setLayoutParams(srtLlp);
        srtLbl.setGravity(Gravity.RIGHT);

        TextView srtBtn = new TextView(activity);
        srtBtn.setText("  إضافة SRT");
        srtBtn.setTextColor(ACCENT);
        srtBtn.setTextSize(12);
        srtBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickSrt(); } });

        srtRow.addView(srtBtn);
        srtRow.addView(srtLbl);
        parent.addView(srtRow);
    }

    // ───────── resolve ────────────────────────────────────────────────────
    private void startResolve() {
        resolving = true;
        final String tmdbId = tryText("tmdb_id", "id", "series_id");
        final boolean isSeries = CigramContent.isSeries(item);
        final int s = season > 0 ? season : 1;
        final int e = episode > 0 ? episode : 1;

        new Thread(new Runnable() { @Override public void run() {
            String url;
            if (isSeries) {
                url = String.format(java.util.Locale.US, EMBED_TV[selectedSource], tmdbId, s, e);
            } else {
                url = String.format(java.util.Locale.US, EMBED_MOVIE[selectedSource], tmdbId);
            }

            // quick HEAD check
            final boolean ok = headCheck(url);
            if (!ok) {
                // try alternate extraction
                String extracted = extractStream(url);
                resolvedUrl = extracted.isEmpty() ? url : extracted;
            } else {
                resolvedUrl = url;
            }

            UI.post(new Runnable() { @Override public void run() { onResolveDone(ok); } });
        } }, "cigram-watch-resolve").start();
    }

    private void onResolveDone(boolean ok) {
        resolving = false;
        bar.setVisibility(View.GONE);

        if (ok || !resolvedUrl.isEmpty()) {
            statusTv.setText("✅  جاهز من " + SOURCE_NAMES[selectedSource]);
            statusTv.setTextColor(GREEN);
            enableButtons(true);
        } else {
            statusTv.setText("⚠️  " + SOURCE_NAMES[selectedSource] + " لم يستجب — جرّب مصدراً آخر");
            statusTv.setTextColor(0xFFE86060);
            enableButtons(false);
        }
    }

    private void enableButtons(boolean on) {
        float a = on ? 1f : 0.4f;
        btnSolo.setAlpha(a);
        btnSolo.setEnabled(on);
        btnTogether.setAlpha(a);
        btnTogether.setEnabled(on);
    }

    // ───────── source picker ──────────────────────────────────────────────
    private void showSourcePicker() {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(activity);
        b.setTitle("اختر المصدر");
        b.setItems(SOURCE_NAMES, new android.content.DialogInterface.OnClickListener() {
          @Override public void onClick(android.content.DialogInterface dialog, int which) {
            selectedSource = which;
            // save preference
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_SOURCE, which).apply();
            updateSourceLabel();
            resolvedUrl = "";
            enableButtons(false);
            bar.setVisibility(View.VISIBLE);
            statusTv.setText("جاري إعادة التحقق...");
            statusTv.setTextColor(TEXT_S);
            startResolve();
          }
        });
        b.show();
    }

    private void updateSourceLabel() {
        if (sourceLbl != null) {
            sourceLbl.setText("المصدر: " + SOURCE_NAMES[selectedSource] + "  •  " + SOURCE_DOMAINS[selectedSource]);
        }
    }

    // ───────── launch players ─────────────────────────────────────────────
    private void launchSolo() {
        try {
            JSONObject playable = buildPlayable();
            // try FilmsAndSeriesActivity (the main details → player flow)
            Intent intent = new Intent(activity,
                    Class.forName(activity.getPackageName() + ".FilmsAndSeriesActivity"));
            intent.putExtra("playable",         playable.toString());
            intent.putExtra("vid_direct_url",   resolvedUrl);
            intent.putExtra("vid_source_name",  SOURCE_NAMES[selectedSource]);
            intent.putExtra("vid_srt_path",     srtPath);
            intent.putExtra("vid_mode",         "solo");
            // keep existing player settings (speed, quality, subtitles) — the player reads from SharedPreferences
            activity.startActivity(intent);
        } catch (ClassNotFoundException ex) {
            // fallback to broadcast
            broadcastPlay("solo");
        } catch (Throwable ex) {
            CigramSilent.toast(activity, "خطأ: " + ex.getMessage(), android.widget.Toast.LENGTH_LONG);
        }
    }

    private void launchTogether() {
        try {
            JSONObject playable = buildPlayable();
            Intent intent = new Intent(activity, CigramTogetherActivity.class);
            intent.putExtra("playable",        playable.toString());
            intent.putExtra("vid_direct_url",  resolvedUrl);
            intent.putExtra("vid_source_name", SOURCE_NAMES[selectedSource]);
            intent.putExtra("vid_srt_path",    srtPath);
            intent.putExtra("vid_mode",        "together");
            activity.startActivity(intent);
        } catch (Throwable ex) {
            CigramSilent.toast(activity, "خطأ: " + ex.getMessage(), android.widget.Toast.LENGTH_LONG);
        }
    }

    private void broadcastPlay(String mode) {
        Intent i = new Intent(activity.getPackageName() + ".ACTION_PLAY_VID");
        i.setPackage(activity.getPackageName());   // explicit: never leaves the app (implicit broadcasts, API 34+)
        i.putExtra("url",   resolvedUrl);
        i.putExtra("title", tryText("title", "series_title", "name"));
        i.putExtra("srt",   srtPath);
        i.putExtra("mode",  mode);
        activity.sendBroadcast(i);
    }

    private JSONObject buildPlayable() throws Exception {
        JSONObject p = new JSONObject(item.toString());
        p.put("url",           resolvedUrl);
        p.put("embed_url",     buildEmbedUrl());
        p.put("source",        SOURCE_NAMES[selectedSource]);
        p.put("source_domain", SOURCE_DOMAINS[selectedSource]);
        if (season > 0) {
            p.put("season",  season);
            p.put("episode", episode);
        }
        if (!srtPath.isEmpty()) p.put("srt_path", srtPath);
        return p;
    }

    private String buildEmbedUrl() {
        String id = tryText("tmdb_id", "id", "series_id");
        if (CigramContent.isSeries(item)) {
            return String.format(java.util.Locale.US, EMBED_TV[selectedSource], id,
                    season > 0 ? season : 1, episode > 0 ? episode : 1);
        }
        return String.format(java.util.Locale.US, EMBED_MOVIE[selectedSource], id);
    }

    // ───────── subtitle ────────────────────────────────────────────────────
    private void pickSrt() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            activity.startActivityForResult(
                    Intent.createChooser(intent, "اختر ملف ترجمة .srt"), REQ_SRT);
        } catch (Throwable t) {
            CigramSilent.toast(activity, "لا يمكن فتح منتقي الملفات", android.widget.Toast.LENGTH_SHORT);
        }
    }

    public static void onSrtResult(Context ctx, Intent data) {
        if (active == null || data == null || data.getData() == null) return;
        try {
            Uri uri = data.getData();
            String fname = "sub_" + System.currentTimeMillis() + ".srt";
            java.io.File dest = new java.io.File(ctx.getCacheDir(), fname);
            java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dest);
            byte[] buf = new byte[4096];
            int n;
            while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            if (in != null) in.close();
            active.srtPath = dest.getAbsolutePath();
            active.srtLbl.setText("📄  ترجمة: " + uri.getLastPathSegment());
            active.srtLbl.setTextColor(GREEN);
        } catch (Throwable t) {
            CigramSilent.toast(ctx, "خطأ في تحميل الترجمة", android.widget.Toast.LENGTH_SHORT);
        }
    }

    // ───────── net helpers ────────────────────────────────────────────────
    private boolean headCheck(String url) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("User-Agent", CigramStreams.UA);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 400;
        } catch (Throwable t) { return false; }
    }

    private String extractStream(String embedUrl) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(embedUrl).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", CigramStreams.UA);
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            c.disconnect();
            String page = sb.toString();

            // scan for media links
            for (String ext : new String[]{".m3u8", ".mp4", ".ts"}) {
                int i = page.indexOf(ext);
                if (i < 0) continue;
                int start = i;
                while (start > 0) {
                    char ch = page.charAt(start);
                    if (ch == '"' || ch == '\'' || ch == ' ') { start++; break; }
                    start--;
                }
                int end = i + ext.length();
                while (end < page.length()) {
                    char ch = page.charAt(end);
                    if (ch == '"' || ch == '\'' || ch == ' ' || ch == '\\') break;
                    end++;
                }
                String cand = page.substring(start, end);
                if (cand.startsWith("http")) return cand;
                if (cand.startsWith("//")) return "https:" + cand;
            }
        } catch (Throwable ignored) { }
        return "";
    }

    // ───────── helpers ────────────────────────────────────────────────────
    private void dismiss() {
        try { if (popup != null) popup.dismiss(); } catch (Throwable ignored) { }
    }

    private String tryText(String... keys) {
        for (String k : keys) {
            try {
                String v = item.optString(k, "");
                if (!v.isEmpty()) return v;
            } catch (Throwable ignored) { }
        }
        return "";
    }

    private TextView makeBtn(String text, int color) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(14);
        tv.setTypeface(CigramTypefaces.bold());
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        tv.setLayoutParams(lp);
        return tv;
    }

    private LinearLayout.LayoutParams fw(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private void addSpace(LinearLayout p, int s) {
        View v = new View(activity);
        v.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        p.addView(v);
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
