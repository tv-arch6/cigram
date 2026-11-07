package com.Cigram.vid;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * CIGRAM TV EDITION: the details page of a film or a series, built for a television and the remote.
 *
 *   hero      title, year, rating, genres, story, and the actions (play / continue, from the start,
 *             my list, watch together), next to a "playback readiness" panel
 *   episodes  (series) season chips and an episode strip with each episode's real state
 *   similar   titles of the same genre from the catalogue
 *
 * Nothing on this page is invented: the main button reflects the real saved progress (the same
 * history the player writes), the readiness panel reflects the real result of the stream resolver,
 * and playing goes through the SAME path as the phone (rewarded unlock, then {@code cigramPlay}),
 * so ads, episodes, resume and the player itself behave exactly as before.
 */
public final class CigramTvDetailsActivity extends Activity implements CigramTvHost {

    private static final String EXTRA_TITLE = "cigram_tv_title";
    private static final String EXTRA_TYPE = "cigram_tv_type";

    /**
     * The item being opened. It is NOT put in the Intent: a long series carries its whole episode list,
     * and an Intent over ~1 MB makes Android throw TransactionTooLargeException. Only the title and type
     * travel in the Intent, as a safety net for a restored page.
     */
    private static volatile JSONObject HANDOFF;
    private static final int GREEN = 0xFF3DDC97;
    private static final int AMBER = 0xFFFFB84D;

    // what the main button does
    private static final int MODE_START = 0, MODE_RESUME = 1, MODE_NEXT = 2, MODE_AGAIN = 3;

    /** Opens the TV details page for a catalogue item. */
    static void open(Activity from, JSONObject item) {
        if (from == null || item == null) return;
        try {
            HANDOFF = item;
            Intent intent = new Intent(from, CigramTvDetailsActivity.class);
            intent.putExtra(EXTRA_TITLE, CigramTvCards.str(item, "title", "name", "series_title"));
            intent.putExtra(EXTRA_TYPE, CigramTvCards.str(item, "type"));
            from.startActivity(intent);
            from.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable error) {
            android.util.Log.w("CigramTv", "open TV details failed", error);
        }
    }

    // ------------------------------------------------------------------ state

    private JSONObject item;
    private boolean series;
    private String title = "", seriesTitle = "", description = "", poster = "", backdrop = "";
    private String year = "", imdb = "", category = "", video = "", subtitle = "", seriesKey = "";
    private final ArrayList<JSONObject> episodes = new ArrayList<JSONObject>();

    private int mode = MODE_START;
    private int target = -1;            // the episode the main button plays (series); -1 = none
    private float resumeFraction;       // 0..1 progress of what the button continues
    private long resumeLeftMs;
    private int selectedSeason = -1;
    private float[] episodeProgress = new float[0];   // saved progress per episode, read once per build

    private boolean needsRefresh;
    private List<JSONObject> similar;   // null until loaded
    private LinearLayout similarHolder;
    private FrameLayout stripHolder;
    private final ArrayList<TextView> seasonChips = new ArrayList<TextView>();
    private View primaryButton;
    private TextView listButton;
    private TextView readyPill, readyLine, readyHint, readyRetry;
    private int readyToken;

    // ------------------------------------------------------------------ host (shared card code)

    @Override public Activity activity() { return this; }

    @Override public int dp(float value) { return CigramStage1InitProvider.cigramDp(this, value); }

    @Override public Typeface font() {
        try { return CigramStage1InitProvider.cigramFont(this); } catch (Throwable t) { return Typeface.DEFAULT; }
    }

    /** A card of the "similar" row was chosen: open its own details page. */
    @Override public void openDetails(JSONObject other, View card) { open(this, other); }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } catch (Throwable ignored) { }
        String wantTitle = getIntent().getStringExtra(EXTRA_TITLE);
        String wantType = getIntent().getStringExtra(EXTRA_TYPE);
        item = HANDOFF;
        if (item == null || !CigramTvCards.str(item, "title", "name", "series_title").equals(wantTitle == null ? "" : wantTitle)) {
            item = findInCatalog(wantTitle, wantType);   // restored page: look it up again
        }
        if (item == null) { finish(); return; }
        readFields();
        build();
        try { CigramTvFocus.attach(this); } catch (Throwable ignored) { }
        loadSimilar();
    }

    private static JSONObject findInCatalog(String wantTitle, String wantType) {
        if (wantTitle == null || wantTitle.length() == 0) return null;
        String wanted = CigramTvSearch.normalize(wantTitle);
        for (JSONObject o : CigramTvActivity.sharedCatalog()) {
            if (!CigramTvSearch.normalize(CigramTvCards.str(o, "title", "name", "series_title")).equals(wanted)) continue;
            if (wantType != null && wantType.length() > 0 && !wantType.equalsIgnoreCase(CigramTvCards.str(o, "type"))) continue;
            return o;
        }
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // back from the player: the saved progress changed, so the button and the episode states must too
        if (needsRefresh) {
            needsRefresh = false;
            build();
        }
    }

    @Override
    protected void onDestroy() {
        readyToken++;   // late resolver answers are ignored
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (primaryButton != null) primaryButton.requestFocus();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ data

    private void readFields() {
        series = CigramTvCards.isSeries(item);
        title = CigramTvCards.str(item, "title", "name", "series_title");
        seriesTitle = CigramTvCards.str(item, "series_title", "title", "name");
        description = CigramTvCards.str(item, "description", "overview", "story", "plot");
        poster = CigramTvCards.str(item, "poster", "series_poster", "season_poster", "image");
        backdrop = CigramTvCards.str(item, "backdrop", "landscape_poster", "source_backdrop");
        year = CigramTvCards.str(item, "year", "release_year");
        imdb = CigramTvCards.str(item, "imdb", "rating", "vote_average");
        category = CigramTvCards.str(item, "category", "genre", "genres");
        video = CigramTvCards.str(item, "video", "link", "video_url", "movie_url");
        subtitle = CigramTvCards.str(item, "subtitle", "subtitle_url");

        episodes.clear();
        if (series) {
            seriesKey = CigramContent.seriesKeyOfTitle(seriesTitle);
            try {
                JSONArray all = CigramStage1InitProvider.castEpisodes(item);
                for (int i = 0; i < all.length(); i++) {
                    JSONObject e = all.optJSONObject(i);
                    if (e != null) episodes.add(e);
                }
            } catch (Throwable error) {
                android.util.Log.w("CigramTv", "episodes unreadable", error);
            }
            Collections.sort(episodes, new Comparator<JSONObject>() {
                @Override public int compare(JSONObject x, JSONObject y) {
                    int bySeason = seasonOf(x) - seasonOf(y);
                    return bySeason != 0 ? bySeason : numberOf(x) - numberOf(y);
                }
            });
        }
    }

    private static int intOf(JSONObject o, String... keys) {
        String digits = CigramTvCards.str(o, keys).replaceAll("[^0-9]", "");
        if (digits.length() == 0 || digits.length() > 6) return 0;
        try { return Integer.parseInt(digits); } catch (Throwable ignored) { return 0; }
    }

    private static int seasonOf(JSONObject e) { return Math.max(1, intOf(e, "season", "season_number")); }

    private static int numberOf(JSONObject e) { return intOf(e, "episode", "episode_number", "number"); }

    private String episodeKey(JSONObject e) {
        return CigramContent.episodeKey(seriesKey, seasonOf(e), numberOf(e));
    }

    private String episodeVideo(int index) {
        if (index < 0 || index >= episodes.size()) return "";
        return CigramTvCards.str(episodes.get(index), "video", "link", "video_url", "movie_url", "stream_url", "play_url");
    }

    /** What the main button should do, from the real saved progress. */
    private void computeTarget() {
        mode = MODE_START;
        target = -1;
        resumeFraction = 0f;
        resumeLeftMs = 0L;
        episodeProgress = new float[episodes.size()];
        try {
            if (series && !episodes.isEmpty()) {
                int partial = -1;
                long partialAt = -1L;
                int lastDone = -1;
                for (int i = 0; i < episodes.size(); i++) {
                    String key = episodeKey(episodes.get(i));
                    float p = CigramUserData.progressOf(this, key);
                    episodeProgress[i] = p;
                    if (p >= 0.97f) { lastDone = i; continue; }
                    if (p > 0.02f) {
                        JSONObject entry = CigramUserData.entry(this, key);
                        long at = entry == null ? 0L : entry.optLong("watched_at", 0L);
                        if (at >= partialAt) { partialAt = at; partial = i; }
                    }
                }
                if (partial >= 0) {
                    mode = MODE_RESUME;
                    target = partial;
                    readResume(episodeKey(episodes.get(partial)));
                } else if (lastDone >= 0 && lastDone + 1 < episodes.size()) {
                    mode = MODE_NEXT;
                    target = lastDone + 1;
                } else if (lastDone >= 0) {
                    mode = MODE_AGAIN;
                    target = 0;
                } else {
                    target = 0;
                }
                if (selectedSeason < 0 && target >= 0) selectedSeason = seasonOf(episodes.get(target));
            } else {
                String key = CigramContent.movieKey(item);
                float p = CigramUserData.progressOf(this, key);
                if (p >= 0.97f) {
                    mode = MODE_AGAIN;
                } else if (p > 0.02f) {
                    mode = MODE_RESUME;
                    readResume(key);
                }
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramTv", "progress unreadable", error);
            mode = MODE_START;
        }
    }

    private void readResume(String key) {
        JSONObject entry = CigramUserData.entry(this, key);
        if (entry == null) return;
        long position = entry.optLong("last_position", 0L);
        long duration = entry.optLong("duration", 0L);
        if (duration > 0L && position > 0L) {
            resumeFraction = Math.max(0f, Math.min(1f, position / (float) duration));
            resumeLeftMs = Math.max(0L, duration - position);
        }
    }

    private String primaryLabel() {
        if (series && target >= 0) {
            int n = numberOf(episodes.get(target));
            if (mode == MODE_RESUME) return "تابع الحلقة " + n;
            if (mode == MODE_NEXT) return "شاهد الحلقة " + n;
            if (mode == MODE_AGAIN) return "أعد المشاهدة";
            return "ابدأ المشاهدة";
        }
        if (mode == MODE_RESUME) return "تابع المشاهدة";
        if (mode == MODE_AGAIN) return "أعد المشاهدة";
        return "شاهد الآن";
    }

    private String primarySub() {
        if (mode == MODE_RESUME && resumeLeftMs > 0L) {
            long minutes = Math.max(1L, Math.round(resumeLeftMs / 60000.0));
            return "متبقّي " + minutes + " دقيقة";
        }
        if (series && target >= 0) {
            if (mode == MODE_NEXT) return "الحلقة التالية";
            if (mode == MODE_AGAIN) return "أنهيت كل الحلقات";
            if (mode == MODE_START) return "الحلقة " + numberOf(episodes.get(target));
        } else if (mode == MODE_AGAIN) {
            return "شاهدته من قبل";
        }
        return "";
    }

    // ------------------------------------------------------------------ building the page

    private TextView text(String s, float sp, int color, boolean bold, int gravity) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(font(), bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setGravity(gravity);
        return t;
    }

    private LinearLayout rtlRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);   // children flow right to left, in the order added
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private void lift(View v, boolean on) {
        v.animate().cancel();
        v.animate().scaleX(on ? 1.06f : 1f).scaleY(on ? 1.06f : 1f).setDuration(120L).start();
    }

    private void makeFocusable(View v, final Runnable onClick) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean has) { lift(view, has); }
        });
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { onClick.run(); }
        });
    }

    private void build() {
        computeTarget();
        seasonChips.clear();
        primaryButton = null;
        listButton = null;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(CigramDesign.BG_APP);

        ImageView backdropView = new ImageView(this);
        backdropView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdropView, new FrameLayout.LayoutParams(-1, -1));
        CigramTvImage.load(backdropView, backdrop.length() > 0 ? backdrop : poster, 1080);

        View sideShade = new View(this);
        sideShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT,
                new int[]{0xF700121A, 0xDD00121A, 0x4D00121A}));
        root.addView(sideShade, new FrameLayout.LayoutParams(-1, -1));
        View bottomShade = new View(this);
        bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xFF00121A, 0xE600121A, 0x0000121A}));
        root.addView(bottomShade, new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(48), dp(26), dp(48), dp(40));   // 5% safe area on every edge of a ~960dp screen
        scroll.addView(page, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        page.addView(topBar(), new LinearLayout.LayoutParams(-1, -2));
        page.addView(hero(), new LinearLayout.LayoutParams(-1, -2));
        if (series && !episodes.isEmpty()) {
            page.addView(episodesSection(), new LinearLayout.LayoutParams(-1, -2));
        }
        similarHolder = new LinearLayout(this);
        similarHolder.setOrientation(LinearLayout.VERTICAL);
        page.addView(similarHolder, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        fillSimilar();
        prepareReadiness(false);

        if (primaryButton != null) {
            final View first = primaryButton;
            first.post(new Runnable() { @Override public void run() { first.requestFocus(); } });
        }
    }

    private View topBar() {
        LinearLayout bar = rtlRow();
        TextView brand = text("سيكرام TV", 17f, CigramDesign.TEXT, true, Gravity.RIGHT);
        bar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(keyHint("OK", "تشغيل"));
        bar.addView(keyHint("MENU", "الأزرار"));
        bar.addView(keyHint("BACK", "رجوع"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        bar.setLayoutParams(lp);
        return bar;
    }

    private View keyHint(String key, String label) {
        LinearLayout hint = rtlRow();
        TextView k = text(key, 10f, CigramDesign.TEXT, true, Gravity.CENTER);
        k.setPadding(dp(7), dp(2), dp(7), dp(2));
        k.setBackground(CigramDesign.round(0x00000000, 0xFF8796A0, 5f, this));
        hint.addView(k);
        TextView l = text(label, 11f, CigramDesign.TEXT_2, false, Gravity.CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.rightMargin = dp(6);
        hint.addView(l, llp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(18);
        hint.setLayoutParams(lp);
        return hint;
    }

    // ---- hero

    private View hero() {
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        hero.setMinimumHeight((int) (screenH * 0.58f));

        hero.addView(infoColumn(), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams panelLp = new LinearLayout.LayoutParams(dp(250), -2);
        panelLp.rightMargin = dp(28);
        hero.addView(readinessPanel(), panelLp);
        return hero;
    }

    private View infoColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.RIGHT);

        TextView type = text(series ? "مسلسل" : "فيلم", 11f, CigramDesign.TEXT_2, true, Gravity.CENTER);
        type.setPadding(dp(12), dp(3), dp(12), dp(3));
        type.setBackground(CigramDesign.round(0x00000000, 0xFF8796A0, 12f, this));
        LinearLayout.LayoutParams typeLp = new LinearLayout.LayoutParams(-2, -2);
        typeLp.gravity = Gravity.RIGHT;
        col.addView(type, typeLp);

        TextView name = text(title, 28f, CigramDesign.TEXT, true, Gravity.RIGHT);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-1, -2);
        nameLp.topMargin = dp(10);
        col.addView(name, nameLp);

        col.addView(metaRow(), metaLp());

        if (description.length() > 0) {
            TextView story = text(description, 13f, CigramDesign.TEXT_2, false, Gravity.RIGHT);
            story.setMaxLines(3);
            story.setEllipsize(android.text.TextUtils.TruncateAt.END);
            story.setLineSpacing(0f, 1.2f);
            story.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            LinearLayout.LayoutParams storyLp = new LinearLayout.LayoutParams(-1, -2);
            storyLp.topMargin = dp(10);
            col.addView(story, storyLp);
        }

        LinearLayout actions = actions();
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
        actionsLp.topMargin = dp(18);
        col.addView(actions, actionsLp);
        return col;
    }

    private LinearLayout.LayoutParams metaLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        return lp;
    }

    private View metaRow() {
        LinearLayout row = rtlRow();
        if (year.length() > 0) row.addView(metaText(year));
        if (imdb.length() > 0) {
            LinearLayout rating = rtlRow();
            TextView badge = text("IMDb", 10f, 0xFF000000, true, Gravity.CENTER);
            badge.setPadding(dp(5), dp(1), dp(5), dp(1));
            badge.setBackground(CigramDesign.round(CigramDesign.IMDB, 4f, this));
            rating.addView(badge);
            TextView score = text(imdb, 13f, CigramDesign.IMDB, true, Gravity.CENTER);
            LinearLayout.LayoutParams scoreLp = new LinearLayout.LayoutParams(-2, -2);
            scoreLp.rightMargin = dp(5);
            rating.addView(score, scoreLp);
            LinearLayout.LayoutParams ratingLp = new LinearLayout.LayoutParams(-2, -2);
            ratingLp.leftMargin = dp(14);
            rating.setLayoutParams(ratingLp);
            row.addView(rating);
        }
        String genres = genresText();
        if (genres.length() > 0) row.addView(metaText(genres));
        if (series && !episodes.isEmpty()) row.addView(metaText(episodes.size() + " حلقة"));
        return row;
    }

    private String genresText() {
        if (category.length() == 0) return "";
        String[] parts = category.split("[,،]");
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (String p : parts) {
            String g = p.trim();
            if (g.length() == 0) continue;
            if (used >= 3) break;
            if (used > 0) sb.append("، ");
            sb.append(g);
            used++;
        }
        return sb.toString();
    }

    private View metaText(String s) {
        TextView t = text(s, 13f, CigramDesign.TEXT_2, true, Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(14);
        t.setLayoutParams(lp);
        return t;
    }

    // ---- actions

    private LinearLayout actions() {
        LinearLayout row = rtlRow();

        primaryButton = primaryButton();
        row.addView(primaryButton, new LinearLayout.LayoutParams(-2, dp(52)));

        if (mode == MODE_RESUME || mode == MODE_AGAIN) {
            row.addView(ghostButton("من البداية", new Runnable() {
                @Override public void run() { play(true); }
            }), ghostLp());
        }

        listButton = ghostButton(inList() ? "في قائمتي" : "إضافة إلى قائمتي", new Runnable() {
            @Override public void run() { toggleList(); }
        });
        row.addView(listButton, ghostLp());

        if (togetherAvailable()) {
            row.addView(ghostButton("مشاهدة جماعية", new Runnable() {
                @Override public void run() { watchTogether(); }
            }), ghostLp());
        }
        return row;
    }

    private LinearLayout.LayoutParams ghostLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(52));
        lp.leftMargin = dp(10);
        return lp;
    }

    private View primaryButton() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(30), dp(6), dp(30), dp(6));
        box.setBackground(CigramDesign.primaryPill(this, 26));

        box.addView(text(primaryLabel(), 16f, 0xFFFFFFFF, true, Gravity.CENTER), new LinearLayout.LayoutParams(-2, -2));
        String sub = primarySub();
        if (sub.length() > 0) {
            box.addView(text(sub, 11f, 0xFFFFFFFF, false, Gravity.CENTER), new LinearLayout.LayoutParams(-2, -2));
        }
        if (mode == MODE_RESUME && resumeFraction > 0f) {
            LinearLayout bar = new LinearLayout(this);
            bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);   // the played part grows from the right
            bar.setBackground(CigramDesign.round(0x59FFFFFF, 2f, this));
            View played = new View(this);
            played.setBackground(CigramDesign.round(0xFFFFFFFF, 2f, this));
            bar.addView(played, new LinearLayout.LayoutParams(0, -1, resumeFraction));
            bar.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1f - resumeFraction));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(3));
            barLp.topMargin = dp(4);
            box.addView(bar, barLp);
        }
        makeFocusable(box, new Runnable() {
            @Override public void run() { play(false); }
        });
        return box;
    }

    private TextView ghostButton(String label, Runnable onClick) {
        TextView b = text(label, 14f, CigramDesign.TEXT, true, Gravity.CENTER);
        b.setPadding(dp(22), 0, dp(22), 0);
        b.setBackground(CigramDesign.ghostPill(this, 26));
        makeFocusable(b, onClick);
        return b;
    }

    private boolean inList() {
        try { return CigramStage1InitProvider.cigramIsFavorite(this, item); } catch (Throwable error) { return false; }
    }

    private void toggleList() {
        try {
            CigramStage1InitProvider.cigramToggleFavorite(this, item);
        } catch (Throwable error) {
            android.util.Log.w("CigramTv", "toggle list failed", error);
        }
        boolean now = inList();
        if (listButton != null) listButton.setText(now ? "في قائمتي" : "إضافة إلى قائمتي");
        Toast.makeText(this, now ? "أُضيف إلى قائمتي" : "أُزيل من قائمتي", Toast.LENGTH_SHORT).show();
    }

    private boolean togetherAvailable() {
        try { return CigramTogether.ENABLED && CigramTogetherApi.enabled(); } catch (Throwable error) { return false; }
    }

    private void watchTogether() {
        needsRefresh = true;
        try {
            CigramTogether.onWatchNow(this, item, new Runnable() {
                @Override public void run() { play(false); }
            });
        } catch (Throwable error) {
            android.util.Log.w("CigramTv", "together failed", error);
        }
    }

    // ---- playing (the same path as the phone details page)

    private void play(boolean startOver) {
        needsRefresh = true;
        if (startOver) CigramStage1InitProvider.cigramStartOverOnce();
        if (series && target >= 0) {
            playEpisode(episodes.get(target));
        } else {
            playTitle();
        }
    }

    private void playTitle() {
        final JSONObject current = item;
        final Activity self = this;
        boolean isSeries = false;
        try { isSeries = CigramStage1InitProvider.castIsSeries(current); } catch (Throwable ignored) { }
        CigramRewardUnlock.unlock(this, CigramStage1InitProvider.rewardKeyOf(current), isSeries, null, new Runnable() {
            @Override public void run() {
                if (self.isFinishing()) return;
                CigramStage1InitProvider.cigramPlayTitle(self, current);
            }
        });
    }

    private void playEpisode(final JSONObject episode) {
        needsRefresh = true;
        final JSONObject parent = item;
        final Activity self = this;
        CigramRewardUnlock.unlock(this, CigramStage1InitProvider.rewardKeyOf(episode), true, null, new Runnable() {
            @Override public void run() {
                if (self.isFinishing()) return;
                CigramStage1InitProvider.cigramPlay(self, episode, parent);
            }
        });
    }

    // ---- readiness panel (real resolver state, never a guess)

    private View readinessPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(14));
        panel.setBackground(CigramDesign.round(0xCC0A1F29, 0x24FFFFFF, 16f, this));

        LinearLayout head = rtlRow();
        head.addView(text("جاهزية التشغيل", 14f, CigramDesign.TEXT, true, Gravity.RIGHT),
                new LinearLayout.LayoutParams(0, -2, 1f));
        readyPill = text("", 11f, CigramDesign.TEXT_2, true, Gravity.CENTER);
        readyPill.setPadding(dp(9), dp(2), dp(9), dp(2));
        head.addView(readyPill, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(head, new LinearLayout.LayoutParams(-1, -2));

        readyLine = text("", 11.5f, CigramDesign.TEXT_2, false, Gravity.RIGHT);
        readyLine.setLineSpacing(0f, 1.15f);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-1, -2);
        lineLp.topMargin = dp(8);
        panel.addView(readyLine, lineLp);

        readyHint = text("", 11f, AMBER, false, Gravity.RIGHT);
        readyHint.setLineSpacing(0f, 1.15f);
        readyHint.setVisibility(View.GONE);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.topMargin = dp(6);
        panel.addView(readyHint, hintLp);

        readyRetry = text("إعادة الفحص", 12f, CigramDesign.TEXT, true, Gravity.CENTER);
        readyRetry.setPadding(dp(14), 0, dp(14), 0);
        readyRetry.setBackground(CigramDesign.ghostPill(this, 18));
        makeFocusable(readyRetry, new Runnable() {
            @Override public void run() { prepareReadiness(true); }
        });
        readyRetry.setVisibility(View.GONE);
        LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(-2, dp(36));
        retryLp.topMargin = dp(10);
        retryLp.gravity = Gravity.RIGHT;
        panel.addView(readyRetry, retryLp);

        String subtitleText = subtitle.length() > 0 ? "الترجمة: متوفرة" : "الترجمة: غير متوفرة من المصدر";
        TextView sub = text(subtitleText, 11f, CigramDesign.TEXT_3, false, Gravity.RIGHT);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = dp(10);
        panel.addView(sub, subLp);
        return panel;
    }

    private String readyUrl() {
        if (series && target >= 0) {
            String ep = episodeVideo(target);
            if (ep.length() > 0) return ep;
        }
        return video;
    }

    private void setReady(String pill, int color, String line, String hint, boolean retry) {
        if (readyPill == null) return;
        readyPill.setText(pill);
        readyPill.setTextColor(color);
        readyPill.setBackground(CigramDesign.round(0x00000000, color, 12f, this));
        readyLine.setText(line);
        readyHint.setText(hint);
        readyHint.setVisibility(hint.length() == 0 ? View.GONE : View.VISIBLE);
        readyRetry.setVisibility(retry ? View.VISIBLE : View.GONE);
    }

    /** Checks the source before the user presses play, so a dead source is seen here and not after waiting in the player. */
    private void prepareReadiness(boolean force) {
        final int token = ++readyToken;
        final String url = readyUrl();
        if (url.length() == 0) {
            setReady("غير متوفر", AMBER, "لا يوجد رابط فيديو لهذا العمل.", "", false);
            return;
        }
        if (!CigramEmbedResolver.isKnownHost(url)) {
            setReady("رابط مباشر", CigramDesign.TEXT_2, "المصدر مباشر ولا يحتاج تجهيزاً مسبقاً.", "", false);
            return;
        }
        String problem = CigramEmbedResolver.webViewProblem();
        setReady("جارٍ الفحص", CigramDesign.TEXT_2, "نفحص مصدر الفيديو قبل التشغيل.",
                problem.length() > 0 ? "تنبيه: " + problem + "." : "", false);
        if (force) {
            try { CigramEmbedResolver.invalidate(CigramStreams.cleanUrl(url)); } catch (Throwable ignored) { }
        }
        CigramEmbedResolver.resolveAsync(this, url, new CigramEmbedResolver.Callback() {
            @Override public void onResolved(String resolved) {
                if (isFinishing() || token != readyToken) return;
                setReady("جاهز", GREEN, "تم تجهيز الرابط. يبدأ التشغيل فور الضغط.", "", false);
            }
            @Override public void onFailed(String reason) {
                if (isFinishing() || token != readyToken) return;
                setReady("تعذّر", AMBER, CigramEmbedResolver.friendly(reason), "(" + reason + ")", true);
            }
        });
    }

    // ---- episodes

    private List<Integer> seasonNumbers() {
        ArrayList<Integer> seasons = new ArrayList<Integer>();
        for (JSONObject e : episodes) {
            Integer s = Integer.valueOf(seasonOf(e));
            if (!seasons.contains(s)) seasons.add(s);
        }
        Collections.sort(seasons);
        return seasons;
    }

    private View episodesSection() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setPadding(0, dp(8), 0, 0);

        LinearLayout head = rtlRow();
        head.addView(text("الحلقات", 17f, CigramDesign.TEXT, true, Gravity.RIGHT));
        List<Integer> seasons = seasonNumbers();
        if (seasons.size() > 1) {
            for (final Integer season : seasons) {
                final TextView chip = text("الموسم " + season, 13f, CigramDesign.TEXT_2, true, Gravity.CENTER);
                chip.setPadding(dp(16), dp(6), dp(16), dp(6));
                chip.setTag(season);
                makeFocusable(chip, new Runnable() {
                    @Override public void run() {
                        selectedSeason = season.intValue();
                        styleSeasonChips();
                        fillStrip();
                    }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.rightMargin = dp(12);
                head.addView(chip, lp);
                seasonChips.add(chip);
            }
            styleSeasonChips();
        }
        col.addView(head, new LinearLayout.LayoutParams(-1, -2));

        stripHolder = new FrameLayout(this);
        stripHolder.setClipChildren(false);
        col.addView(stripHolder, new LinearLayout.LayoutParams(-1, -2));
        fillStrip();
        return col;
    }

    private void styleSeasonChips() {
        for (TextView chip : seasonChips) {
            boolean active = ((Integer) chip.getTag()).intValue() == selectedSeason;
            chip.setBackground(CigramDesign.round(active ? 0xFFFFFFFF : 0x18FFFFFF, 16f, this));
            chip.setTextColor(active ? 0xFF00121A : CigramDesign.TEXT_2);
        }
    }

    private void fillStrip() {
        if (stripHolder == null) return;
        stripHolder.removeAllViews();

        final HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        scroll.setFillViewport(true);
        scroll.setPadding(0, dp(10), 0, dp(12));

        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.HORIZONTAL);
        rail.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        rail.setGravity(Gravity.RIGHT);
        rail.setClipChildren(false);

        View targetCard = null;
        for (int i = 0; i < episodes.size(); i++) {
            if (selectedSeason > 0 && seasonOf(episodes.get(i)) != selectedSeason) continue;
            View card = episodeCard(i);
            rail.addView(card, 0);   // prepend: the first episode ends at the far right
            if (i == target) targetCard = card;
        }
        scroll.addView(rail, new HorizontalScrollView.LayoutParams(-2, -2));
        stripHolder.addView(scroll, new FrameLayout.LayoutParams(-1, -2));

        // open on the episode the main button plays (centred), else at the right end (episode 1)
        final View focusCard = targetCard;
        final boolean[] placed = {false};
        scroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int oldL, int oldT, int oldR, int oldB) {
                if (placed[0]) return;
                View content = scroll.getChildAt(0);
                int viewport = r - l;
                if (content == null || content.getWidth() <= 0 || viewport <= 0) return;
                placed[0] = true;
                int max = Math.max(0, content.getWidth() - viewport);
                int x = max;
                if (focusCard != null && focusCard.getWidth() > 0) {
                    x = focusCard.getLeft() - (viewport - focusCard.getWidth()) / 2;
                }
                scroll.scrollTo(Math.max(0, Math.min(max, x)), 0);
            }
        });
    }

    private View episodeCard(final int index) {
        final JSONObject episode = episodes.get(index);
        int number = numberOf(episode);
        float progress = index < episodeProgress.length ? episodeProgress[index] : 0f;
        boolean done = progress >= 0.97f;
        boolean partial = !done && progress > 0.02f;

        final int w = dp(150);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setPadding(dp(5), dp(5), dp(5), dp(5));

        FrameLayout thumb = new FrameLayout(this);
        thumb.setBackground(CigramDesign.round(0xFF12222C, 10f, this));
        thumb.setClipToOutline(true);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.addView(image, new FrameLayout.LayoutParams(-1, -1));
        CigramTvImage.load(image, CigramTvCards.str(episode,
                "still", "thumbnail", "thumb", "episode_image", "backdrop", "image", "poster"), 480);

        if (partial) {
            LinearLayout bar = new LinearLayout(this);
            bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            bar.setBackgroundColor(0x4DFFFFFF);
            View played = new View(this);
            played.setBackgroundColor(CigramDesign.ACCENT);
            bar.addView(played, new LinearLayout.LayoutParams(0, -1, progress));
            bar.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1f - progress));
            thumb.addView(bar, new FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM));
        } else if (index == target && (mode == MODE_NEXT || mode == MODE_START)) {
            TextView badge = text(mode == MODE_NEXT ? "التالية" : "ابدأ", 10f, 0xFFFFFFFF, true, Gravity.CENTER);
            badge.setPadding(dp(9), dp(1), dp(9), dp(1));
            badge.setBackground(CigramDesign.round(CigramDesign.ACCENT, 10f, this));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
            blp.setMargins(dp(6), dp(6), dp(6), dp(6));
            thumb.addView(badge, blp);
        }
        card.addView(thumb, new LinearLayout.LayoutParams(w, dp(84)));

        TextView name = text("الحلقة " + (number > 0 ? String.valueOf(number) : String.valueOf(index + 1)),
                13f, CigramDesign.TEXT, true, Gravity.RIGHT);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(w, -2);
        nameLp.topMargin = dp(6);
        card.addView(name, nameLp);

        String epTitle = CigramTvCards.str(episode, "episode_title", "title", "name");
        if (epTitle.length() > 0 && !epTitle.equalsIgnoreCase(seriesTitle) && !epTitle.equalsIgnoreCase(title)) {
            TextView t = text(epTitle, 11f, CigramDesign.TEXT_3, false, Gravity.RIGHT);
            t.setMaxLines(1);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            card.addView(t, new LinearLayout.LayoutParams(w, -2));
        }
        if (done || partial) {
            TextView state = text(done ? "تمت المشاهدة" : "متابعة", 11f,
                    done ? CigramDesign.TEXT_3 : CigramDesign.TEXT, !done, Gravity.RIGHT);
            card.addView(state, new LinearLayout.LayoutParams(w, -2));
        }

        makeFocusable(card, new Runnable() {
            @Override public void run() { playEpisode(episode); }
        });
        return card;
    }

    // ---- similar titles

    private void loadSimilar() {
        new Thread(new Runnable() {
            @Override public void run() {
                final ArrayList<JSONObject> out = new ArrayList<JSONObject>();
                try {
                    List<JSONObject> all = CigramTvActivity.sharedCatalog();
                    if (all.isEmpty()) {
                        String json = CigramStage1InitProvider.cachedCatalogJson(getApplicationContext());
                        if (json != null && json.length() > 0) {
                            JSONArray flat = new HomeCatalog().parse(CigramTitles.normalizeCatalog(json));
                            ArrayList<JSONObject> parsed = new ArrayList<JSONObject>();
                            for (int i = 0; i < flat.length(); i++) {
                                JSONObject o = flat.optJSONObject(i);
                                if (o != null) parsed.add(o);
                            }
                            all = parsed;
                        }
                    }
                    String genre = firstGenre(category);
                    String mine = CigramTvSearch.normalize(title);
                    if (genre.length() > 0) {
                        for (JSONObject o : all) {
                            if (out.size() >= 20) break;
                            if (CigramTvSearch.normalize(CigramTvCards.str(o, "title", "name", "series_title")).equals(mine)) continue;
                            if (!CigramTvCards.str(o, "category", "genre", "genres").contains(genre)) continue;
                            out.add(o);
                        }
                    }
                } catch (Throwable error) {
                    android.util.Log.w("CigramTv", "similar titles failed", error);
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        similar = out;
                        fillSimilar();
                    }
                });
            }
        }, "cigram-tv-similar").start();
    }

    private static String firstGenre(String category) {
        if (category == null) return "";
        String[] parts = category.split("[,،]");
        return parts.length == 0 ? "" : parts[0].trim();
    }

    private void fillSimilar() {
        if (similarHolder == null) return;
        similarHolder.removeAllViews();
        if (similar == null || similar.size() < 4) return;   // never show a nearly empty row
        similarHolder.addView(CigramTvCards.row(this, "أعمال مشابهة", similar), new LinearLayout.LayoutParams(-1, -2));
    }
}
