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
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CigramVidResolver v1.0
 *
 * المشغل الذكي لتطبيق سيكرام — يحل روابط الأفلام والمسلسلات
 * من المواقع الخارجية ويعرضها بشكل نظيف.
 *
 * طريقة الاستخدام من أي Activity:
 * <pre>
 *     CigramVidResolver.show(activity, contentItem);  // عنصر محتوى موجود
 *     // أو
 *     CigramVidResolver.showSearch(activity);          // بحث من الصفر
 * </pre>
 *
 * المشغلات المدعومة:
 *  - vidfast.vc
 *  - vidnest.fun
 *  - videm.xyz
 *  - vidbolt.pro
 *
 * ─────────────────────────────────────────────────────────────────────
 * التصميم (RTL):
 *
 *   ┌──────────────────────────────────────────────────────┐
 *   │           🔍  ابحث عن فيلم أو مسلسل...              │
 *   │  ╔════════════════════════════════════════════╗      │
 *   │  ║  نتيجة 1: Breaking Bad (2008)             ║      │
 *   │  ║  موسم 1 ▾  │ حلقة 1 ▾                   ║      │
 *   │  ║  المصادر:                                  ║      │
 *   │  ║  ○ vidfast  ○ vidnest  ○ videm  ○ vidbolt ║      │
 *   │  ╠════════════════════════════════════════════╣      │
 *   │  ║  [ 👤 شاهد بمفردك ]  [ 👥 شاهد سوية ]   ║      │
 *   │  ╚════════════════════════════════════════════╝      │
 *   └──────────────────────────────────────────────────────┘
 * ─────────────────────────────────────────────────────────────────────
 */
public final class CigramVidResolver {

    // ───────── colours matching Cigram's dark palette ──────────────────
    private static final int BG        = 0xFF0D1117;   // background sheet
    private static final int CARD      = 0xFF161B22;   // card bg
    private static final int ACCENT    = 0xFF2563EB;   // brand blue
    private static final int ACCENT2   = 0xFF7C3AED;   // together purple
    private static final int TEXT_PRI  = 0xFFE6EDF3;   // primary text
    private static final int TEXT_SEC  = 0xFF8B949E;   // secondary text
    private static final int DIVIDER   = 0xFF21262D;   // dividers
    private static final int CHIP_BG   = 0xFF1C2128;   // source chip bg
    private static final int CHIP_SEL  = 0xFF2563EB;   // selected chip

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final String PREFS_KEY = "cigram_vid_resolver";

    // ───────── المصادر ─────────────────────────────────────────────────
    private static final String[] SOURCE_NAMES = {"vidfast", "vidnest", "videm", "vidbolt"};
    private static final String[] SOURCE_DOMAINS = {
            "vidfast.vc",
            "vidnest.fun",
            "videm.xyz",
            "vidbolt.pro"
    };

    // ───────── نقاط الـ API لكل مصدر ─────────────────────────────────
    // كل مصدر لديه API يقبل اسم/TMDB ID ويرجع stream link
    // الصيغة الموحدة: GET https://<domain>/api/embed?title=X&type=movie|series&season=N&episode=N
    // أو TMDB embed: GET https://<domain>/embed/movie/<tmdb_id>
    //                    https://<domain>/embed/tv/<tmdb_id>/<season>/<episode>
    private static final String[] EMBED_MOVIE  = {
            "https://vidfast.vc/movie/%s",          // %s = tmdb_id
            "https://vidnest.fun/movie/%s",
            "https://videm.xyz/movie/%s",
            "https://vidbolt.pro/movie/%s"
    };
    private static final String[] EMBED_TV = {
            "https://vidfast.vc/tv/%s/%d/%d",       // %s=tmdb, %d=season, %d=episode
            "https://vidnest.fun/tv/%s/%d/%d",
            "https://videm.xyz/tv/%s/%d/%d",
            "https://vidbolt.pro/tv/%s/%d/%d"
    };

    // ───────── حالة داخلية ─────────────────────────────────────────────
    private final Activity activity;
    private PopupWindow popup;
    private View rootSheet;

    // محتوى
    private String contentTitle = "";
    private String contentType  = "movie";   // "movie" or "series"
    private String tmdbId       = "";
    private int selectedSeason  = 1;
    private int selectedEpisode = 1;
    private int totalSeasons    = 1;
    private int totalEpisodes   = 1;
    private int selectedSource  = 0;          // index in SOURCE_DOMAINS
    private boolean resolved    = false;
    private String resolvedUrl  = "";

    // SRT subtitle
    private String srtPath = "";              // empty = no subtitle loaded

    // UI refs
    private LinearLayout episodeRow;
    private LinearLayout sourcesRow;
    private TextView statusText;
    private LinearLayout actionRow;
    private ProgressBar loadingBar;
    private TextView[] sourceChips;
    private TextView seasonChip, epChip;

    // ───────── entry points ─────────────────────────────────────────────

    /** فتح مباشرة من عنصر محتوى موجود (JSON من قاعدة بيانات سيكرام) */
    public static void show(Activity a, JSONObject item) {
        if (a == null || item == null) return;
        CigramVidResolver r = new CigramVidResolver(a);
        try {
            r.contentTitle = CigramContent.text(item, "title", "series_title", "name");
            r.tmdbId       = CigramContent.text(item, "tmdb_id", "id", "series_id");
            r.contentType  = CigramContent.isSeries(item) ? "series" : "movie";
            if ("series".equals(r.contentType)) {
                r.totalSeasons  = item.optInt("seasons_count", 1);
                r.totalEpisodes = item.optInt("episodes_count", 12);
            }
        } catch (Throwable ignored) {
            r.contentTitle = "محتوى";
        }
        r.open();
    }

    /** فتح بحث من الصفر — يسأل المستخدم عن الاسم */
    public static void showSearch(Activity a) {
        if (a == null) return;
        CigramVidResolver r = new CigramVidResolver(a);
        r.open();
    }

    private CigramVidResolver(Activity a) {
        this.activity = a;
    }

    // ───────── بناء الواجهة ─────────────────────────────────────────────
    private void open() {
        if (activity.isFinishing() || activity.isDestroyed()) return;

        // ── container ──
        FrameLayout container = new FrameLayout(activity);
        container.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // dim background tap to dismiss
        View dim = new View(activity);
        dim.setBackgroundColor(0xBB000000);
        dim.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        dim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismiss();
            }
        });
        container.addView(dim);

        // ── bottom sheet ──
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.BOTTOM;
        scroll.setLayoutParams(sp);
        container.addView(scroll);

        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundColor(BG);
        // rounded top
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(BG);
        sheetBg.setCornerRadii(new float[]{dp(20), dp(20), dp(20), dp(20), 0, 0, 0, 0});
        sheet.setBackground(sheetBg);
        sheet.setPadding(dp(16), dp(8), dp(16), dp(32));
        scroll.addView(sheet);
        rootSheet = sheet;

        buildSheet(sheet);

        // ── popup ──
        popup = new PopupWindow(container,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(false);
        popup.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.BOTTOM, 0, 0);

        // if we already have a title, resolve immediately
        if (!contentTitle.isEmpty() && !tmdbId.isEmpty()) {
            resolveSelected();
        }
    }

    private void buildSheet(LinearLayout parent) {
        // ── handle bar ──
        View handle = new View(activity);
        GradientDrawable hd = new GradientDrawable();
        hd.setColor(TEXT_SEC);
        hd.setCornerRadius(dp(2));
        handle.setBackground(hd);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(40), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.setMargins(0, dp(4), 0, dp(16));
        handle.setLayoutParams(hlp);
        parent.addView(handle);

        // ── title label ──
        TextView titleLabel = new TextView(activity);
        titleLabel.setText("مشغّل الفيديو الذكي");
        titleLabel.setTextColor(TEXT_PRI);
        titleLabel.setTextSize(16);
        titleLabel.setTypeface(Typeface.DEFAULT_BOLD);
        titleLabel.setGravity(Gravity.RIGHT);
        titleLabel.setLayoutParams(fullWidth(0, 0, 0, dp(12)));
        parent.addView(titleLabel);

        // ── search box ──
        final EditText searchBox = new EditText(activity);
        searchBox.setHint(contentTitle.isEmpty() ? "🔍  اكتب اسم الفيلم أو المسلسل..." : contentTitle);
        searchBox.setHintTextColor(TEXT_SEC);
        searchBox.setTextColor(TEXT_PRI);
        searchBox.setTextSize(14);
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setSingleLine(true);
        searchBox.setGravity(Gravity.RIGHT);
        searchBox.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(CARD);
        searchBg.setCornerRadius(dp(12));
        searchBg.setStroke(dp(1), DIVIDER);
        searchBox.setBackground(searchBg);
        searchBox.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams slp = fullWidth(0, 0, 0, dp(16));
        searchBox.setLayoutParams(slp);
        parent.addView(searchBox);

        if (!contentTitle.isEmpty()) {
            searchBox.setText(contentTitle);
        }

        // ── type row (فيلم / مسلسل) ──
        LinearLayout typeRow = new LinearLayout(activity);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        typeRow.setLayoutParams(fullWidth(0, 0, 0, dp(12)));
        typeRow.setGravity(Gravity.RIGHT);

        final TextView movieChip = makeChip("🎬 فيلم", "movie".equals(contentType));
        final TextView seriesChip = makeChip("📺 مسلسل", "series".equals(contentType));

        movieChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                contentType = "movie";
                styleChip(movieChip, true);
                styleChip(seriesChip, false);
                episodeRow.setVisibility(View.GONE);
                resolved = false;
                clearActions();
            }
        });
        seriesChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                contentType = "series";
                styleChip(seriesChip, true);
                styleChip(movieChip, false);
                episodeRow.setVisibility(View.VISIBLE);
                resolved = false;
                clearActions();
            }
        });

        typeRow.addView(seriesChip);
        addSpace(typeRow, dp(8));
        typeRow.addView(movieChip);
        parent.addView(typeRow);

        // ── episode row (seasons / episodes) ──
        episodeRow = new LinearLayout(activity);
        episodeRow.setOrientation(LinearLayout.HORIZONTAL);
        episodeRow.setLayoutParams(fullWidth(0, 0, 0, dp(12)));
        episodeRow.setGravity(Gravity.RIGHT);
        episodeRow.setVisibility("series".equals(contentType) ? View.VISIBLE : View.GONE);

        seasonChip = makeChip("موسم " + selectedSeason + " ▾", false);
        epChip    = makeChip("حلقة " + selectedEpisode + " ▾", false);

        seasonChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showNumberPicker("اختر الموسم", 1, Math.max(totalSeasons, 10), selectedSeason, new NumberCallback() {
                    @Override public void onPick(int n) {
                        selectedSeason = n;
                        seasonChip.setText("موسم " + n + " ▾");
                        resolved = false;
                        clearActions();
                    }
                });
            }
        });
        epChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showNumberPicker("اختر الحلقة", 1, Math.max(totalEpisodes, 30), selectedEpisode, new NumberCallback() {
                    @Override public void onPick(int n) {
                        selectedEpisode = n;
                        epChip.setText("حلقة " + n + " ▾");
                        resolved = false;
                        clearActions();
                    }
                });
            }
        });

        episodeRow.addView(epChip);
        addSpace(episodeRow, dp(8));
        episodeRow.addView(seasonChip);
        parent.addView(episodeRow);

        // ── sources row ──
        TextView srcLabel = new TextView(activity);
        srcLabel.setText("المصدر:");
        srcLabel.setTextColor(TEXT_SEC);
        srcLabel.setTextSize(12);
        srcLabel.setGravity(Gravity.RIGHT);
        srcLabel.setLayoutParams(fullWidth(0, 0, 0, dp(6)));
        parent.addView(srcLabel);

        sourcesRow = new LinearLayout(activity);
        sourcesRow.setOrientation(LinearLayout.HORIZONTAL);
        sourcesRow.setLayoutParams(fullWidth(0, 0, 0, dp(16)));
        sourcesRow.setGravity(Gravity.RIGHT);

        sourceChips = new TextView[SOURCE_NAMES.length];
        for (int i = SOURCE_NAMES.length - 1; i >= 0; i--) {
            final int idx = i;
            sourceChips[i] = makeChip(SOURCE_NAMES[i], i == selectedSource);
            sourceChips[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selectedSource = idx;
                    for (int j = 0; j < sourceChips.length; j++)
                        styleChip(sourceChips[j], j == idx);
                    resolved = false;
                    clearActions();
                    resolveSelected();
                }
            });
            sourcesRow.addView(sourceChips[i]);
            if (i > 0) addSpace(sourcesRow, dp(6));
        }
        parent.addView(sourcesRow);

        // ── progress / status ──
        loadingBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        loadingBar.setIndeterminate(true);
        loadingBar.setVisibility(View.GONE);
        loadingBar.setLayoutParams(fullWidth(0, 0, 0, dp(4)));
        parent.addView(loadingBar);

        statusText = new TextView(activity);
        statusText.setTextColor(TEXT_SEC);
        statusText.setTextSize(12);
        statusText.setGravity(Gravity.RIGHT);
        statusText.setVisibility(View.GONE);
        statusText.setLayoutParams(fullWidth(0, 0, 0, dp(12)));
        parent.addView(statusText);

        // ── actions row (buttons) ──
        actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setLayoutParams(fullWidth(0, 0, 0, 0));
        actionRow.setGravity(Gravity.CENTER);
        actionRow.setVisibility(View.GONE);
        parent.addView(actionRow);

        // ── subtitle row ──
        LinearLayout subRow = new LinearLayout(activity);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams subRlp = fullWidth(0, dp(12), 0, 0);
        subRow.setLayoutParams(subRlp);
        subRow.setVisibility(View.GONE);

        final TextView subBtn = new TextView(activity);
        subBtn.setText("📄 إضافة ترجمة SRT");
        subBtn.setTextColor(ACCENT);
        subBtn.setTextSize(13);
        subBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickSubtitle(subBtn);
            }
        });
        subRow.addView(subBtn);
        // we reveal subRow when resolved
        parent.setTag(subRow); // store ref to reveal later

        // ── search listener ──
        searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                        (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                    String q = searchBox.getText().toString().trim();
                    if (q.length() > 0) {
                        contentTitle = q;
                        tmdbId = "";       // will be fetched by resolver
                        resolved = false;
                        clearActions();
                        resolveSelected();
                        hideKeyboard(searchBox);
                    }
                    return true;
                }
                return false;
            }
        });
    }

    // ───────── resolution logic ──────────────────────────────────────────
    /**
     * يبني الرابط المباشر ويتحقق منه.
     * الخوارزمية:
     *  1. إذا عندنا tmdbId → embed مباشرة
     *  2. إذا ما عندنا → TMDB search API → نجيب الـ ID → embed
     */
    private void resolveSelected() {
        showLoading(true, "جاري البحث...");
        clearActions();

        new Thread(new Runnable() { @Override public void run() {
            try {
                // step 1: get tmdb id if missing
                if (tmdbId.isEmpty() && !contentTitle.isEmpty()) {
                    tmdbId = fetchTmdbId(contentTitle, contentType);
                }

                if (tmdbId.isEmpty()) {
                    showError("تعذّر إيجاد المحتوى على TMDB");
                    return;
                }

                // step 2: build embed url
                String embedUrl = buildEmbedUrl(selectedSource, tmdbId, contentType,
                        selectedSeason, selectedEpisode);

                // step 3: an embed link is a WEB PAGE, not a video file. Resolve it to a real stream
                // (HTTP scrape, then a hidden WebView sniffer) so ExoPlayer / Together can open it.
                String direct = CigramEmbedResolver.isEmbed(embedUrl)
                        ? CigramEmbedResolver.resolveBlocking(activity, embedUrl)
                        : embedUrl;
                if (direct == null || direct.isEmpty()) {
                    showError("لم يعمل " + SOURCE_NAMES[selectedSource] + " — جرّب مصدر ثاني");
                    return;
                }
                resolvedUrl = direct;

                resolved = true;
                UI.post(new Runnable() { @Override public void run() { showActionButtons(); } });

            } catch (Throwable e) {
                showError("خطأ: " + e.getMessage());
            }
        } }, "cigram-vid-resolve").start();
    }

    /**
     * يجيب TMDB ID من عنوان المحتوى.
     * يستخدم TMDB search endpoint (لا يحتاج API key للبحث الأساسي عبر الـ embed sites).
     * Fallback: يبحث على vidfast مباشرة.
     */
    private String fetchTmdbId(String title, String type) {
        // Try TMDB search (public, no auth needed for basic search)
        try {
            String kind = "series".equals(type) ? "tv" : "movie";
            String enc = URLEncoder.encode(title, "UTF-8");
            String url = "https://api.themoviedb.org/3/search/" + kind +
                    "?query=" + enc + "&api_key=0b1a3f3f3daa04b38e9f8d0cd0f42f1d"; // public dev key
            String resp = httpGet(url, 6000);
            if (resp != null && resp.contains("\"id\"")) {
                JSONObject json = new JSONObject(resp);
                JSONArray results = json.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    return String.valueOf(results.getJSONObject(0).optInt("id", 0));
                }
            }
        } catch (Throwable ignored) { }

        // Fallback: try vidfast search API
        try {
            String enc = URLEncoder.encode(title, "UTF-8");
            String url = "https://vidfast.vc/search?query=" + enc;
            String resp = httpGet(url, 8000);
            if (resp != null) {
                // parse first tmdb id from response
                int idx = resp.indexOf("/movie/");
                if (idx < 0) idx = resp.indexOf("/tv/");
                if (idx >= 0) {
                    String after = resp.substring(idx).replaceAll("[^0-9].*", "").replaceAll("[^0-9]", "");
                    if (!after.isEmpty()) return after;
                }
            }
        } catch (Throwable ignored) { }

        return "";
    }

    /** يبني embed URL لمصدر معين */
    private String buildEmbedUrl(int srcIdx, String id, String type, int season, int ep) {
        if ("series".equals(type)) {
            return String.format(Locale.US, EMBED_TV[srcIdx], id, season, ep);
        } else {
            return String.format(Locale.US, EMBED_MOVIE[srcIdx], id);
        }
    }

    /**
     * يحاول استخراج stream URL فعلي من صفحة الـ embed
     * (بعض المواقع ترجع iframe/javascript بدل مباشرة)
     */
    private String extractStreamFromEmbed(String embedUrl) {
        try {
            String page = httpGet(embedUrl, 10000);
            if (page == null || page.isEmpty()) return "";

            // look for m3u8 / mp4 links in page source
            String[] patterns = {".m3u8", ".mp4", "master.m3u8", "playlist.m3u8"};
            for (String pat : patterns) {
                int i = page.indexOf(pat);
                if (i >= 0) {
                    // scan back for quote
                    int start = i;
                    while (start > 0 && page.charAt(start) != '"' && page.charAt(start) != '\'') start--;
                    int end = i + pat.length();
                    // scan forward for quote
                    while (end < page.length() && page.charAt(end) != '"' && page.charAt(end) != '\'' && page.charAt(end) != ' ') end++;
                    String candidate = page.substring(start + 1, end);
                    if (candidate.startsWith("http")) return candidate;
                    if (candidate.startsWith("//")) return "https:" + candidate;
                }
            }

            // look for JSON source object
            String[] jsonKeys = {"\"file\":\"", "'file':'", "\"src\":\"", "'src':'"};
            for (String key : jsonKeys) {
                int i = page.indexOf(key);
                if (i >= 0) {
                    int start = i + key.length();
                    int end = page.indexOf(key.contains("\"") ? "\"" : "'", start);
                    if (end > start) {
                        String candidate = page.substring(start, end);
                        if (candidate.startsWith("http") || candidate.startsWith("//")) {
                            return candidate.startsWith("//") ? "https:" + candidate : candidate;
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return "";
    }

    /** HEAD ping لتأكيد الرابط */
    private boolean pingUrl(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("User-Agent", CigramStreams.UA);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 400;
        } catch (Throwable t) {
            return false;
        }
    }

    // ───────── UI helpers ────────────────────────────────────────────────
    private void showLoading(final boolean on, final String msg) {
        UI.post(new Runnable() { @Override public void run() {
            loadingBar.setVisibility(on ? View.VISIBLE : View.GONE);
            if (msg != null && !msg.isEmpty()) {
                statusText.setText(msg);
                statusText.setTextColor(TEXT_SEC);
                statusText.setVisibility(View.VISIBLE);
            }
        } });
    }

    private void showError(final String msg) {
        UI.post(new Runnable() { @Override public void run() {
            loadingBar.setVisibility(View.GONE);
            statusText.setText("⚠️  " + msg);
            statusText.setTextColor(0xFFE86060);
            statusText.setVisibility(View.VISIBLE);
        } });
    }

    private void clearActions() {
        UI.post(new Runnable() { @Override public void run() {
            actionRow.removeAllViews();
            actionRow.setVisibility(View.GONE);
        } });
    }

    /** يعرض زرّي المشاهدة بعد نجاح الحل */
    private void showActionButtons() {
        loadingBar.setVisibility(View.GONE);
        statusText.setText("✅  الرابط جاهز — اختر طريقة المشاهدة");
        statusText.setTextColor(0xFF4CAF50);
        statusText.setVisibility(View.VISIBLE);

        actionRow.removeAllViews();

        // زر المشاهدة المشتركة (Together)
        TextView btnTogether = makeActionBtn("👥 شاهد سوية", ACCENT2);
        btnTogether.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismiss();
                launchTogether();
            }
        });

        // زر المشاهدة المنفردة (Solo)
        TextView btnSolo = makeActionBtn("👤 شاهد بمفردك", ACCENT);
        btnSolo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dismiss();
                launchSolo();
            }
        });

        actionRow.addView(btnTogether);
        addSpace(actionRow, dp(12));
        actionRow.addView(btnSolo);
        actionRow.setVisibility(View.VISIBLE);

        // reveal subtitle picker
        if (rootSheet.getTag() instanceof LinearLayout) {
            ((LinearLayout) rootSheet.getTag()).setVisibility(View.VISIBLE);
        }
    }

    // ───────── player launchers ──────────────────────────────────────────

    /**
     * يشغّل المشغل المنفرد.
     * يستخدم Intent مطابق لما يفتحه تطبيق سيكرام من صفحة التفاصيل
     * مع إضافة الرابط المحلول وملف الترجمة إن وُجد.
     *
     * نمرر البيانات كـ JSON مباشرة داخل الـ Intent، بنفس الطريقة
     * التي يستخدمها CigramTogetherPlayerActivity لاستقبال الـ playable.
     */
    private void launchSolo() {
        try {
            Intent intent = new Intent(activity,
                    Class.forName(activity.getPackageName() + ".FilmsAndSeriesActivity"));

            JSONObject playable = buildPlayable();
            intent.putExtra("playable", playable.toString());
            intent.putExtra("vid_direct_url", resolvedUrl);
            intent.putExtra("vid_source_name", SOURCE_NAMES[selectedSource]);
            intent.putExtra("vid_content_title", contentTitle);
            if (!srtPath.isEmpty()) {
                intent.putExtra("vid_srt_path", srtPath);
            }
            intent.putExtra("vid_mode", "solo");
            activity.startActivity(intent);
        } catch (ClassNotFoundException e) {
            // fallback: try CigramMainActivity
            launchSoloFallback();
        } catch (Exception e) {
            launchSoloFallback();
        }
    }

    /**
     * fallback مباشر: نفتح CigramLivePlayer أو نبني Intent عام للمشغل
     */
    private void launchSoloFallback() {
        try {
            // استخدام SharedPreferences لتمرير الرابط بأمان
            SharedPreferences prefs = activity.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString("pending_url",   resolvedUrl)
                    .putString("pending_title",  contentTitle)
                    .putString("pending_source", SOURCE_NAMES[selectedSource])
                    .putString("pending_srt",    srtPath)
                    .putString("pending_type",   contentType)
                    .putInt("pending_season",    selectedSeason)
                    .putInt("pending_episode",   selectedEpisode)
                    .apply();

            // broadcast للـ activity الحالي أو أي مشغّل مسجّل
            Intent intent = new Intent(activity.getPackageName() + ".ACTION_PLAY_VID");
            intent.putExtra("url",    resolvedUrl);
            intent.putExtra("title",  contentTitle);
            intent.putExtra("srt",    srtPath);
            intent.putExtra("mode",   "solo");
            activity.sendBroadcast(intent);

            Toast.makeText(activity,
                    "▶️  " + contentTitle + "\nجاري التشغيل من " + SOURCE_NAMES[selectedSource],
                    Toast.LENGTH_SHORT).show();

        } catch (Throwable t) {
            Toast.makeText(activity, "تعذّر فتح المشغّل: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * يشغّل مشغّل المشاهدة المشتركة.
     * يفتح CigramTogetherActivity أولاً (إنشاء غرفة / الانضمام)
     * ويمرر الـ playable ليتولى التحكم.
     */
    private void launchTogether() {
        try {
            JSONObject playable = buildPlayable();

            Intent intent = new Intent(activity, CigramTogetherActivity.class);
            intent.putExtra("playable", playable.toString());
            intent.putExtra("vid_direct_url", resolvedUrl);
            intent.putExtra("vid_source_name", SOURCE_NAMES[selectedSource]);
            intent.putExtra("vid_content_title", contentTitle);
            if (!srtPath.isEmpty()) {
                intent.putExtra("vid_srt_path", srtPath);
            }
            intent.putExtra("vid_mode", "together");
            activity.startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(activity, "تعذّر فتح المشاهدة المشتركة: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** يبني JSONObject موحّد يفهمه مشغّل سيكرام */
    private JSONObject buildPlayable() throws Exception {
        JSONObject p = new JSONObject();
        p.put("title",       contentTitle);
        p.put("type",        contentType);
        p.put("tmdb_id",     tmdbId);
        p.put("url",         resolvedUrl);
        p.put("source",      SOURCE_NAMES[selectedSource]);
        p.put("source_domain", SOURCE_DOMAINS[selectedSource]);
        if ("series".equals(contentType)) {
            p.put("season",  selectedSeason);
            p.put("episode", selectedEpisode);
        }
        if (!srtPath.isEmpty()) {
            p.put("srt_path", srtPath);
        }
        // embed url for fallback inside player
        p.put("embed_url", buildEmbedUrl(selectedSource, tmdbId, contentType, selectedSeason, selectedEpisode));
        return p;
    }

    // ───────── subtitle picker ────────────────────────────────────────────
    private void pickSubtitle(TextView subBtn) {
        // open file picker for .srt files
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // store subBtn ref via tag for the result callback
            activity.startActivityForResult(
                    Intent.createChooser(intent, "اختر ملف ترجمة .srt"),
                    REQUEST_SRT);
            // The activity must call CigramVidResolver.onSubtitleResult(uri) in onActivityResult
        } catch (Throwable t) {
            Toast.makeText(activity, "لا يمكن فتح منتقي الملفات", Toast.LENGTH_SHORT).show();
        }
        pendingSubBtn = subBtn;
    }

    // static so the hosting Activity can call back
    private static CigramVidResolver active;
    private TextView pendingSubBtn;
    public static final int REQUEST_SRT = 0x5274;   // 'Sr' = Subtitle request

    /** يجب على الـ Activity استدعاء هذا في onActivityResult */
    public static void onSubtitleResult(Context ctx, Intent data) {
        if (active == null || data == null) return;
        try {
            Uri uri = data.getData();
            if (uri == null) return;

            // copy to private cache for ExoPlayer access
            String fileName = "subtitle_" + System.currentTimeMillis() + ".srt";
            java.io.File dest = new java.io.File(ctx.getCacheDir(), fileName);
            java.io.InputStream in = ctx.getContentResolver().openInputStream(uri);
            java.io.FileOutputStream out = new java.io.FileOutputStream(dest);
            byte[] buf = new byte[4096];
            int n;
            while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            if (in != null) in.close();

            active.srtPath = dest.getAbsolutePath();
            if (active.pendingSubBtn != null) {
                active.pendingSubBtn.setText("✅  ترجمة محمّلة");
                active.pendingSubBtn.setTextColor(0xFF4CAF50);
            }
        } catch (Throwable t) {
            Toast.makeText(ctx, "خطأ في تحميل الترجمة", Toast.LENGTH_SHORT).show();
        }
    }

    // ───────── number picker dialog ───────────────────────────────────────
    interface NumberCallback { void onPick(int n); }

    private void showNumberPicker(String title, int min, int max, int current, final NumberCallback cb) {
        if (activity.isFinishing()) return;

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(activity);
        builder.setTitle(title);

        ScrollView sv = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        sv.addView(list);

        final android.app.AlertDialog[] dlg = new android.app.AlertDialog[1];
        for (int i = min; i <= max; i++) {
            final int val = i;
            TextView item = new TextView(activity);
            item.setText(String.valueOf(i));
            item.setTextColor(i == current ? ACCENT : TEXT_PRI);
            item.setTextSize(15);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(16), dp(12), dp(16), dp(12));
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    cb.onPick(val);
                    if (dlg[0] != null) dlg[0].dismiss();
                }
            });
            list.addView(item);

            // divider
            if (i < max) {
                View div = new View(activity);
                div.setBackgroundColor(DIVIDER);
                div.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
                list.addView(div);
            }
        }

        dlg[0] = builder.setView(sv).setNegativeButton("إلغاء", null).create();
        dlg[0].show();
    }

    // ───────── chip factory ───────────────────────────────────────────────
    private TextView makeChip(String text, boolean selected) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        styleChip(tv, selected);
        return tv;
    }

    private void styleChip(TextView tv, boolean selected) {
        tv.setTextColor(selected ? 0xFFFFFFFF : TEXT_SEC);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? CHIP_SEL : CHIP_BG);
        bg.setCornerRadius(dp(16));
        if (!selected) bg.setStroke(dp(1), DIVIDER);
        tv.setBackground(bg);
    }

    private TextView makeActionBtn(String text, int color) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(20), dp(14), dp(20), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ───────── dismiss ────────────────────────────────────────────────────
    private void dismiss() {
        try { if (popup != null) popup.dismiss(); } catch (Throwable ignored) { }
        active = null;
    }

    // ───────── network util ───────────────────────────────────────────────
    private String httpGet(String urlStr, int timeoutMs) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", CigramStreams.UA);
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code >= 400) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            c.disconnect();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // ───────── layout helpers ─────────────────────────────────────────────
    private LinearLayout.LayoutParams fullWidth(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    private void addSpace(LinearLayout parent, int size) {
        View v = new View(activity);
        v.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        parent.addView(v);
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private void hideKeyboard(View v) {
        try {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Throwable ignored) { }
    }
}
