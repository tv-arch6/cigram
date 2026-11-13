package com.Cigram.vid;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.WeakHashMap;

/**
 * CIGRAM LIVE — the channels page as ONE continuous, vertically scrolling page, in this exact order:
 *
 *   1. the player (always 16 : 9)
 *   2. what is playing: logo, name, status, favourite
 *   3. search
 *   4. category chips (الكل · المفضلة · شاهدتها مؤخراً · one chip per package of the catalogue)
 *   5. ALL channels (a section of its own, the whole catalogue)
 *   6. every category as a section with its channels
 *
 * There is no package page any more: every channel of the catalogue is on this page. Choosing a chip filters it at
 * once; the search filters the cards that exist (they are shown / hidden, nothing is rebuilt per keystroke); the
 * player is never touched by either, and choosing a channel only retargets it (the ExoPlayer is reused) and scrolls
 * back up to it.
 *
 * Built on the pieces of the previous hub: {@link CigramChannelStore} (catalogue, favourites, recents, one parse per
 * process), {@link CigramChannelPlayer} (the same player, controls, error handling, fullscreen button),
 * {@link CigramLogos} (memory + disk logo cache). Phones in landscape keep the cinema mode of before (the picture is
 * the whole screen); the TV keeps {@link CigramChannelHub}.
 *
 * Availability: every entry of the catalogue is listed. An entry the server marks unavailable (or without a link) is
 * drawn dimmed with «غير متوفرة حالياً», counted separately in the section header, and never started; nothing is
 * dropped from the list.
 */
final class CigramLiveHub implements CigramChannelStore.Listener, CigramChannelPlayer.Host, CigramChannelCard.Listener {

    private static final String TAG = "CigramLiveHub";
    private static final String BAR_TAG = "transparent_cigram_nav";
    private static final String NO_NAV_PAD = "cigram_nav_managed";

    private static final int CH_ALL = 0, CH_FAV = 1, CH_RECENT = 2, CH_PKG = 3;
    private static final long SEARCH_DEBOUNCE_MS = 140L;
    private static final int BUILD_BATCH = 14;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Activity, CigramLiveHub> HUBS = new WeakHashMap<Activity, CigramLiveHub>();
    private static boolean lifecycleRegistered;

    /** Kept for a few seconds when the page is recreated (rotation, theme change). */
    private static long stickyAt;
    private static int stickyKind = CH_ALL;
    private static String stickyName = "";
    private static String stickyChannel = "";
    private static String stickyQuery = "";

    // ------------------------------------------------------------------ model of the page

    private final class Section {
        final int kind;
        final String name;
        final String title;
        ArrayList<CigramChannelStore.Ch> list = new ArrayList<CigramChannelStore.Ch>();
        final ArrayList<CigramChannelCard> cards = new ArrayList<CigramChannelCard>();
        LinearLayout box;
        TextView titleView;
        TextView countView;
        CigramChannelGrid grid;
        int built;

        Section(int kind, String name, String title) {
            this.kind = kind;
            this.name = name;
            this.title = title;
        }
    }

    private final class ChipSpec {
        final int kind;
        final String name;
        final String label;
        TextView view;

        ChipSpec(int kind, String name, String label) {
            this.kind = kind;
            this.name = name;
            this.label = label;
        }
    }

    // ------------------------------------------------------------------ state

    private final Activity a;
    private final CigramPalette pal;
    private final float dp;
    private final boolean cinema;          // phone in landscape: the picture is the whole screen
    private final int maxWidth;            // tablets: the page keeps a readable width
    private CigramChannelStore.Catalog catalog;
    private final ArrayList<Section> sections = new ArrayList<Section>();
    private final ArrayList<ChipSpec> chips = new ArrayList<ChipSpec>();
    private final HashMap<String, ArrayList<CigramChannelCard>> cardsByKey = new HashMap<String, ArrayList<CigramChannelCard>>();
    private final ArrayList<CigramChannelCard> allCards = new ArrayList<CigramChannelCard>();
    private Section allSection, favSection, recentSection;
    private int chipKind = CH_ALL;
    private String chipName = "";
    private String query = "";
    private String activeKey = "";
    private String pendingChannel = "";
    private boolean destroyed;
    private boolean wentFull;
    private boolean immersive;
    private boolean barHidden;
    private boolean imeShown;
    private final Rect frame = new Rect();
    private final ViewTreeObserver.OnGlobalLayoutListener imeWatcher = new ViewTreeObserver.OnGlobalLayoutListener() {
        @Override public void onGlobalLayout() { checkIme(); }
    };
    private int uiBefore;
    private int navPad;
    private int buildAt;                  // next section / card to create
    private int buildSection;
    private boolean logosQueued;
    private Object backCallback;

    // ------------------------------------------------------------------ views

    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout column;
    private AspectFrame stageWrap;
    private FrameLayout stage;
    private CigramChannelPlayer player;
    private TextView infoName;
    private TextView infoSub;
    private FrameLayout infoLogoHost;
    private CigramHubUi.Icon infoHeart;
    private LinearLayout searchRow;
    private EditText input;
    private View clearBtn;
    private HorizontalScrollView chipsScroll;
    private LinearLayout chipsRow;
    private TextView stateView;
    private LinearLayout catalogBox;
    private int lastStatus = -1;
    private String lastStatusMessage = "";

    private final Runnable applyQuery = new Runnable() {
        @Override public void run() { onQueryChanged(); }
    };
    private final Runnable buildStep = new Runnable() {
        @Override public void run() { buildMore(); }
    };
    private final Runnable loadLogos = new Runnable() {
        @Override public void run() {
            logosQueued = false;
            loadVisibleLogos();
        }
    };

    private CigramLiveHub(Activity activity) {
        a = activity;
        pal = CigramPalette.of(activity);
        dp = activity.getResources().getDisplayMetrics().density;
        Configuration cfg = activity.getResources().getConfiguration();
        boolean tablet = cfg.smallestScreenWidthDp >= 600;
        cinema = !tablet && cfg.orientation == Configuration.ORIENTATION_LANDSCAPE;
        maxWidth = tablet ? Math.round(840 * dp) : Integer.MAX_VALUE;
    }

    // ================================================================== entry point

    /**
     * Replaces the content of ChannelsActivity with the live page.
     * @return false for the TV (it keeps its own page) or when the page could not be built: the caller then falls
     *         back to {@link CigramChannelHub} and, if that fails too, to the original page.
     */
    static boolean install(Activity activity) {
        if (activity == null || !"ChannelsActivity".equals(activity.getClass().getSimpleName())) return false;
        if (CigramTvMode.isTv(activity)) return false;
        try {
            registerLifecycle(activity);
            CigramLiveHub hub = new CigramLiveHub(activity);
            hub.build();   // its last step puts the page on screen
            synchronized (HUBS) { HUBS.put(activity, hub); }
            try {
                hub.start();
            } catch (RuntimeException error) {
                Log.w(TAG, "live page started without its catalogue", error);
            }
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "live page failed, using the previous one", error);
            return false;
        }
    }

    // ================================================================== building

    private int dp(float v) {
        return Math.round(v * dp);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = CigramUI.label(a, s, sp, color, bold);
        t.setIncludeFontPadding(false);
        return t;
    }

    private void build() {
        navPad = dp(84) + navigationBarHeight();

        slop = ViewConfiguration.get(a).getScaledTouchSlop();
        root = new FrameLayout(a) {
            @Override public boolean dispatchTouchEvent(MotionEvent ev) {
                trackTouch(ev);
                return super.dispatchTouchEvent(ev);
            }
        };
        root.setBackgroundColor(pal.page);
        root.setTag(CigramTheme.SKIP_TAG);   // this page is themed by its own palette, not by the legacy pass
        // The page itself takes the initial focus. Without this the search field gets it when the window opens,
        // and its focus listener (which lifts the field above the keyboard) would scroll the player out of view.
        // Tapping the field still focuses it, and clearFocus() on it hands the focus back to this root.
        root.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) root.setDefaultFocusHighlightEnabled(false);

        stageWrap = new AspectFrame(a);
        stageWrap.setBackgroundColor(pal.pageDeep);
        stage = new FrameLayout(a);
        stage.setClickable(true);
        stageWrap.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        player = new CigramChannelPlayer(a, stage, this);
        player.setEmptyHint("اختر قناة من القائمة في الأسفل");
        player.setStatusListener(new CigramChannelPlayer.StatusListener() {
            @Override public void onStatus(int status, String message) { showStatus(status, message); }
        });

        if (cinema) {
            // landscape phone: the picture is the whole screen, nothing else is built
            stageWrap.setFill(true);
            root.addView(stageWrap, new FrameLayout.LayoutParams(-1, -1));
            player.setCompact(true);
            enterImmersive();
            finishBuild();
            return;
        }

        scroll = new ScrollView(a);
        scroll.setTag(NO_NAV_PAD);   // CigramBottomNav leaves the bottom padding of this page to the page
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, navPad);
        scroll.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        column = new Column(a, maxWidth);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        // ---- 1. the player
        column.addView(stageWrap, new LinearLayout.LayoutParams(-1, -2));
        // ---- 2. what is playing
        column.addView(buildInfoBar(), new LinearLayout.LayoutParams(-1, dp(64)));
        // ---- 3. search
        buildSearch();
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(-1, dp(46));
        srl.setMargins(dp(14), dp(2), dp(14), dp(10));
        column.addView(searchRow, srl);
        // ---- 4. categories
        chipsScroll = new HorizontalScrollView(a);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        chipsScroll.setFillViewport(true);
        chipsScroll.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        chipsRow = new LinearLayout(a);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        chipsRow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        chipsRow.setPadding(dp(12), 0, dp(12), 0);
        chipsScroll.addView(chipsRow, new FrameLayout.LayoutParams(-1, -1));
        column.addView(chipsScroll, new LinearLayout.LayoutParams(-1, dp(42)));
        // ---- 5 + 6. the catalogue
        stateView = text("جارٍ تحميل القنوات…", 14f, pal.text3, false);
        stateView.setGravity(Gravity.CENTER);
        stateView.setPadding(dp(30), dp(36), dp(30), dp(36));
        column.addView(stateView, new LinearLayout.LayoutParams(-1, -2));
        catalogBox = new LinearLayout(a);
        catalogBox.setOrientation(LinearLayout.VERTICAL);
        catalogBox.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        column.addView(catalogBox, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL);
        scroll.addView(column, clp);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int sx, int sy, int ox, int oy) {
                if (sy != oy) {
                    // only the user's own drag / fling puts the keyboard away: the page also scrolls by itself
                    // (lifting the field above the keyboard, going back up to the picture) and that must not close it
                    if (input != null && input.hasFocus() && userScrolling()) hideKeyboard();
                    queueLogos();
                }
            }
        });
        refreshChips();
        finishBuild();
    }

    private void finishBuild() {
        a.setContentView(root);
        if (!cinema) root.getViewTreeObserver().addOnGlobalLayoutListener(imeWatcher);
        try {
            setupBack();
        } catch (RuntimeException error) {
            Log.w(TAG, "back setup failed", error);
        }
    }

    private View buildInfoBar() {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), 0, dp(14), 0);

        FrameLayout heartBox = new FrameLayout(a);
        heartBox.setClickable(true);
        heartBox.setContentDescription("إضافة إلى المفضلة");
        heartBox.setBackground(ripple(0, 23));
        infoHeart = new CigramHubUi.Icon(a, CigramHubUi.HEART, pal.text3);
        heartBox.addView(infoHeart, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        heartBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CigramChannelStore.Ch ch = player.current();
                if (ch != null) toggleFavourite(ch);
            }
        });
        bar.addView(heartBox, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        infoName = text("لم تختر قناة بعد", 16f, pal.text, true);
        infoName.setSingleLine(true);
        infoName.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(infoName, new LinearLayout.LayoutParams(-1, -2));
        infoSub = text("اختر قناة من القائمة لتبدأ المشاهدة", 12f, pal.text3, false);
        infoSub.setSingleLine(true);
        infoSub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(4);
        col.addView(infoSub, sl);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.rightMargin = dp(12);
        bar.addView(col, cl);

        infoLogoHost = new FrameLayout(a);
        infoLogoHost.setVisibility(View.INVISIBLE);   // nothing is playing yet
        bar.addView(infoLogoHost, new LinearLayout.LayoutParams(dp(56), dp(42)));
        return bar;
    }

    private void buildSearch() {
        searchRow = new LinearLayout(a);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(CigramPalette.round(a, pal.surfaceHi, pal.stroke, 14));

        FrameLayout clear = new FrameLayout(a);
        clear.setClickable(true);
        clear.setContentDescription("مسح البحث");
        clear.setBackground(ripple(0, 14));
        clear.addView(new CigramHubUi.Icon(a, CigramHubUi.CLOSE, pal.text3), new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { input.setText(""); }
        });
        clearBtn = clear;
        searchRow.addView(clear, new LinearLayout.LayoutParams(dp(42), -1));

        input = new EditText(a);
        input.setBackground(null);
        input.setHint("ابحث عن قناة أو باقة…");
        input.setHintTextColor(pal.text3);
        input.setTextColor(pal.text);
        input.setTextSize(14.5f);
        input.setTypeface(CigramUI.font(a));
        input.setPadding(dp(4), 0, dp(4), 0);
        input.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        input.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);   // Arabic flows right-to-left, a latin name left-to-right
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setContentDescription("بحث عن قناة");
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean focused) {
                if (!focused) return;
                // the keyboard is about to cover the lower half: bring the field (and its results) to the top
                v.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!destroyed && scroll != null && input.hasFocus()) {
                            scroll.smoothScrollTo(0, Math.max(0, searchRow.getTop() - dp(10)));
                        }
                    }
                }, 280L);
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                hideKeyboard();
                return true;
            }
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                clearBtn.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                UI.removeCallbacks(applyQuery);
                UI.postDelayed(applyQuery, SEARCH_DEBOUNCE_MS);
            }
        });
        searchRow.addView(input, new LinearLayout.LayoutParams(0, -1, 1f));

        FrameLayout icon = new FrameLayout(a);
        icon.addView(new CigramHubUi.Icon(a, CigramHubUi.SEARCH, pal.text3), new FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER));
        searchRow.addView(icon, new LinearLayout.LayoutParams(dp(44), -1));
    }

    private android.graphics.drawable.Drawable ripple(int fill, float radiusDp) {
        GradientDrawable mask = CigramPalette.round(a, Color.WHITE, 0, radiusDp);
        android.graphics.drawable.Drawable content = fill == 0 ? null : CigramPalette.round(a, fill, 0, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(pal.ripple), content, mask);
    }

    // ================================================================== catalogue -> page

    private void start() {
        restoreSticky();
        CigramChannelStore.load(a, this);
    }

    @Override public void onCatalog(CigramChannelStore.Catalog c, boolean changed) {
        if (destroyed) return;
        try {
            applyCatalog(c);
        } catch (RuntimeException error) {
            Log.w(TAG, "catalogue update failed", error);
        }
    }

    private void applyCatalog(CigramChannelStore.Catalog c) {
        boolean same = c == catalog;
        catalog = c;
        if (!same && !cinema) {
            rebuildCatalog();
        }
        if (pendingChannel.length() > 0) {
            CigramChannelStore.Ch ch = c.byKey.get(pendingChannel);
            pendingChannel = "";
            if (ch != null) choose(ch, false);
        }
    }

    private ArrayList<CigramChannelStore.Ch> channelsOf(int kind) {
        ArrayList<CigramChannelStore.Ch> out = new ArrayList<CigramChannelStore.Ch>();
        if (catalog == null) return out;
        ArrayList<String> keys = kind == CH_FAV ? CigramChannelStore.favourites(a) : CigramChannelStore.recent(a);
        for (int i = 0; i < keys.size(); i++) {
            CigramChannelStore.Ch ch = catalog.byKey.get(keys.get(i));
            if (ch != null) out.add(ch);
        }
        return out;
    }

    /** Builds the (empty) sections and starts filling them with cards, a few at a time, so the page appears at once. */
    private void rebuildCatalog() {
        UI.removeCallbacks(buildStep);
        catalogBox.removeAllViews();
        sections.clear();
        cardsByKey.clear();
        allCards.clear();
        allSection = favSection = recentSection = null;
        buildSection = 0;
        buildAt = 0;
        if (catalog == null || catalog.all.isEmpty()) {
            refreshChips();
            applyFilter();
            return;
        }
        allSection = new Section(CH_ALL, "", "جميع القنوات");
        allSection.list = new ArrayList<CigramChannelStore.Ch>(catalog.all);
        addSection(allSection);
        for (int i = 0; i < catalog.packages.size(); i++) {
            CigramChannelStore.Pkg p = catalog.packages.get(i);
            Section s = new Section(CH_PKG, p.name, p.name);
            s.list = new ArrayList<CigramChannelStore.Ch>(p.channels);
            addSection(s);
        }
        favSection = new Section(CH_FAV, "", "المفضلة");
        recentSection = new Section(CH_RECENT, "", "شاهدتها مؤخراً");
        addSection(favSection);
        addSection(recentSection);
        // a chip of a package that no longer exists falls back to «الكل»
        if (chipKind == CH_PKG && findSection(CH_PKG, chipName) == null) {
            chipKind = CH_ALL;
            chipName = "";
        }
        refreshChips();
        refreshDynamicSections();
        applyFilter();
        UI.post(buildStep);
    }

    private void addSection(Section s) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), 0, dp(14), 0);
        s.countView = text("", 11.5f, pal.text3, false);
        head.addView(s.countView, new LinearLayout.LayoutParams(-2, -2));
        head.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        s.titleView = text(s.title, 17f, pal.text, true);
        s.titleView.setSingleLine(true);
        s.titleView.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(s.titleView, new LinearLayout.LayoutParams(-2, -2));
        View accent = new View(a);
        accent.setBackground(CigramPalette.round(a, pal.brand, 0, 2));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(4), dp(18));
        al.leftMargin = dp(8);
        head.addView(accent, al);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, dp(44));
        hl.topMargin = dp(8);
        box.addView(head, hl);

        s.grid = new CigramChannelGrid(a);
        s.grid.setPadding(dp(14), dp(2), dp(14), dp(8));
        box.addView(s.grid, new LinearLayout.LayoutParams(-1, -2));

        s.box = box;
        sections.add(s);
        catalogBox.addView(box, new LinearLayout.LayoutParams(-1, -2));
    }

    private Section findSection(int kind, String name) {
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            if (s.kind == kind && (kind != CH_PKG || s.name.equals(name))) return s;
        }
        return null;
    }

    /** Creates the cards of the next BUILD_BATCH channels (sections in page order), then schedules itself again. */
    private void buildMore() {
        if (destroyed || catalog == null) return;
        try {
            int made = 0;
            while (made < BUILD_BATCH && buildSection < sections.size()) {
                Section s = sections.get(buildSection);
                if (s.kind == CH_FAV || s.kind == CH_RECENT) {   // built on demand, they are small
                    buildSection++;
                    buildAt = 0;
                    continue;
                }
                if (buildAt >= s.list.size()) {
                    buildSection++;
                    buildAt = 0;
                    continue;
                }
                addCard(s, s.list.get(buildAt++));
                made++;
            }
            queueLogos();
            if (buildSection < sections.size()) UI.postDelayed(buildStep, 16L);
        } catch (RuntimeException error) {
            Log.w(TAG, "building the channel list failed", error);
        }
    }

    private CigramChannelCard addCard(Section s, CigramChannelStore.Ch ch) {
        CigramChannelCard card = new CigramChannelCard(a, ch, this);
        card.setActive(ch.key.equals(activeKey));
        card.setFav(CigramChannelStore.isFav(a, ch.key));
        card.setVisibility(matches(ch) ? View.VISIBLE : View.GONE);
        s.cards.add(card);
        s.grid.addView(card);
        allCards.add(card);
        ArrayList<CigramChannelCard> list = cardsByKey.get(ch.key);
        if (list == null) {
            list = new ArrayList<CigramChannelCard>(2);
            cardsByKey.put(ch.key, list);
        }
        list.add(card);
        s.built++;
        s.countView.setText(countText(s));
        return card;
    }

    /** Favourites / recents change often and are tiny: their cards are simply made again. */
    private void refreshDynamicSections() {
        refreshDynamic(favSection);
        refreshDynamic(recentSection);
    }

    private void refreshDynamic(Section s) {
        if (s == null || s.grid == null) return;
        for (int i = 0; i < s.cards.size(); i++) {
            CigramChannelCard card = s.cards.get(i);
            ArrayList<CigramChannelCard> list = cardsByKey.get(card.ch.key);
            if (list != null) list.remove(card);
            allCards.remove(card);
        }
        s.cards.clear();
        s.grid.removeAllViews();
        s.built = 0;
        s.list = channelsOf(s.kind);
        for (int i = 0; i < s.list.size(); i++) addCard(s, s.list.get(i));
        s.countView.setText(countText(s));
    }

    // ================================================================== filter

    private boolean matches(CigramChannelStore.Ch ch) {
        String nq = CigramChannelStore.norm(query);
        return nq.length() == 0 || ch.search.contains(nq) || ch.sectionKey.contains(nq);
    }

    private boolean sectionShown(Section s) {
        boolean searching = query.length() > 0;
        switch (chipKind) {
            case CH_FAV: return s.kind == CH_FAV;
            case CH_RECENT: return s.kind == CH_RECENT;
            case CH_PKG: return s.kind == CH_PKG && s.name.equals(chipName);
            default:
                if (s.kind == CH_ALL) return true;
                // typing on «الكل»: one list of results instead of the same channels again under their packages
                return s.kind == CH_PKG && !searching;
        }
    }

    private String countText(Section s) {
        int shown = 0, playable = 0, total = s.list.size();
        for (int i = 0; i < s.cards.size(); i++) {
            CigramChannelCard c = s.cards.get(i);
            if (c.getVisibility() == View.VISIBLE) {
                shown++;
                if (c.ch.available) playable++;
            }
        }
        if (s.built < total) shown = Math.max(shown, 0);
        boolean filtering = query.length() > 0;
        int n = filtering ? shown : total;
        int ok = filtering ? playable : playableOf(s);
        return n == ok ? n + " قناة" : n + " قناة · " + ok + " متاحة";
    }

    private int playableOf(Section s) {
        int n = 0;
        for (int i = 0; i < s.list.size(); i++) if (s.list.get(i).available) n++;
        return n;
    }

    /** Shows / hides sections and cards for the chip and the search text. No view is created or destroyed. */
    private void applyFilter() {
        if (catalogBox == null) return;
        boolean searching = query.length() > 0;
        int visibleCards = 0;
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            boolean show = sectionShown(s);
            int shownCards = 0;
            if (show) {
                for (int k = 0; k < s.cards.size(); k++) {
                    CigramChannelCard c = s.cards.get(k);
                    boolean vis = matches(c.ch);
                    int want = vis ? View.VISIBLE : View.GONE;
                    if (c.getVisibility() != want) c.setVisibility(want);
                    if (vis) shownCards++;
                }
                if (s.kind == CH_ALL) s.titleView.setText(searching && chipKind == CH_ALL ? "نتائج البحث" : s.title);
                s.countView.setText(countText(s));
                // a section with nothing to show (all filtered out, or not built yet and empty) keeps no empty header
                boolean pending = s.built < s.list.size() && s.kind != CH_FAV && s.kind != CH_RECENT;
                show = shownCards > 0 || pending && !searching;
            }
            int vis = show ? View.VISIBLE : View.GONE;
            if (s.box.getVisibility() != vis) s.box.setVisibility(vis);
            if (show) visibleCards += shownCards;
        }
        refreshState(visibleCards);
        queueLogos();
    }

    private void refreshState(int visibleCards) {
        if (catalog == null) {
            stateView.setText("جارٍ تحميل القنوات…");
            stateView.setVisibility(View.VISIBLE);
        } else if (catalog.all.isEmpty()) {
            stateView.setText("لا توجد قنوات حالياً\nتحقق من الاتصال بالإنترنت ثم أعد فتح الصفحة");
            stateView.setVisibility(View.VISIBLE);
        } else if (visibleCards == 0 && (query.length() > 0 || chipKind == CH_FAV || chipKind == CH_RECENT)) {
            String msg;
            if (query.length() > 0) msg = "لا توجد قناة بهذا الاسم";
            else if (chipKind == CH_FAV) msg = "لم تضف أي قناة إلى المفضلة بعد\nاضغط مطولاً على قناة (أو على القلب تحت الشاشة) لإضافتها";
            else msg = "لم تشاهد أي قناة بعد";
            stateView.setText(msg);
            stateView.setVisibility(View.VISIBLE);
        } else {
            stateView.setVisibility(View.GONE);
        }
    }

    private void onQueryChanged() {
        if (destroyed) return;
        try {
            String q = input.getText() == null ? "" : input.getText().toString().trim();
            if (q.equals(query)) return;
            query = q;
            applyFilter();
            updateBack();
        } catch (RuntimeException error) {
            Log.w(TAG, "search failed", error);
        }
    }

    // ================================================================== chips

    private void refreshChips() {
        if (chipsRow == null) return;
        chips.clear();
        chips.add(new ChipSpec(CH_ALL, "", "الكل"));
        chips.add(new ChipSpec(CH_FAV, "", "المفضلة"));
        if (!CigramChannelStore.recent(a).isEmpty()) chips.add(new ChipSpec(CH_RECENT, "", "شاهدتها مؤخراً"));
        if (catalog != null) {
            for (int i = 0; i < catalog.packages.size(); i++) {
                chips.add(new ChipSpec(CH_PKG, catalog.packages.get(i).name, catalog.packages.get(i).name));
            }
        }
        chipsRow.removeAllViews();
        // physical order is left-to-right and the row is packed to the right, so the first chip goes in LAST
        for (int i = chips.size() - 1; i >= 0; i--) {
            final ChipSpec spec = chips.get(i);
            TextView chip = text(spec.label, 13f, pal.text2, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), 0, dp(16), 0);
            chip.setSingleLine(true);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setContentDescription("فئة " + spec.label);
            spec.view = chip;
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { selectChip(spec.kind, spec.name); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
            lp.leftMargin = dp(8);
            chipsRow.addView(chip, lp);
        }
        styleChips();
        // open on the right edge, where «الكل» is
        chipsScroll.post(new Runnable() {
            @Override public void run() {
                if (!destroyed) chipsScroll.scrollTo(chipsRow.getWidth(), 0);
            }
        });
    }

    private void styleChips() {
        for (int i = 0; i < chips.size(); i++) {
            ChipSpec spec = chips.get(i);
            if (spec.view == null) continue;
            boolean on = spec.kind == chipKind && (spec.kind != CH_PKG || spec.name.equals(chipName));
            spec.view.setBackground(on
                    ? CigramPalette.round(a, pal.brand, 0, 17)
                    : CigramPalette.round(a, pal.surfaceHi, pal.stroke, 17));
            spec.view.setTextColor(on ? pal.onBrand : pal.text2);
            spec.view.setSelected(on);
        }
    }

    private void selectChip(int kind, String name) {
        if (destroyed) return;
        try {
            hideKeyboard();
            boolean changed = kind != chipKind || !name.equals(chipName);
            chipKind = kind;
            chipName = name;
            if (kind == CH_FAV) refreshDynamic(favSection);
            if (kind == CH_RECENT) refreshDynamic(recentSection);
            styleChips();
            applyFilter();
            updateBack();
            if (changed && scroll != null) {
                int target = chipsScroll.getTop();   // keep the chips in view, start of the list right under them
                if (scroll.getScrollY() > target) scroll.scrollTo(0, target);
                for (int i = 0; i < chips.size(); i++) {
                    ChipSpec spec = chips.get(i);
                    if (spec.view != null && spec.view.isSelected()) {
                        final View v = spec.view;
                        chipsScroll.post(new Runnable() {
                            @Override public void run() {
                                if (!destroyed) chipsScroll.smoothScrollTo(Math.max(0, v.getLeft() - dp(24)), 0);
                            }
                        });
                    }
                }
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "chip failed", error);
        }
    }

    // ================================================================== logos (only when they are near the screen)

    private void queueLogos() {
        if (logosQueued || destroyed) return;
        logosQueued = true;
        UI.postDelayed(loadLogos, 40L);
    }

    private int offsetTop(View v) {
        int y = 0;
        View cur = v;
        while (cur != null && cur != column) {
            y += cur.getTop();
            cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
        }
        return y;
    }

    private void loadVisibleLogos() {
        if (destroyed || scroll == null) return;
        int viewport = Math.max(scroll.getHeight(), a.getResources().getDisplayMetrics().heightPixels);
        int top = scroll.getScrollY() - viewport;
        int bottom = scroll.getScrollY() + viewport * 2;
        for (int i = 0; i < allCards.size(); i++) {
            CigramChannelCard card = allCards.get(i);
            if (card.logoRequested() || card.getVisibility() != View.VISIBLE) continue;
            int y = offsetTop(card);
            if (y + card.getHeight() >= top && y <= bottom) card.ensureLogo();
        }
    }

    // ================================================================== channels

    @Override public void onOpen(CigramChannelCard card) {
        if (destroyed) return;
        try {
            if (!card.ch.available) {
                CigramSilent.toast(a, "القناة غير متوفرة حالياً", android.widget.Toast.LENGTH_SHORT);
                return;
            }
            choose(card.ch, true);
        } catch (RuntimeException error) {
            Log.w(TAG, "open failed", error);
        }
    }

    @Override public void onFavourite(CigramChannelCard card) {
        if (destroyed) return;
        try {
            toggleFavourite(card.ch);
            boolean fav = CigramChannelStore.isFav(a, card.ch.key);
            CigramSilent.toast(a, fav ? "أُضيفت إلى المفضلة" : "أُزيلت من المفضلة", android.widget.Toast.LENGTH_SHORT);
        } catch (RuntimeException error) {
            Log.w(TAG, "favourite failed", error);
        }
    }

    private void choose(CigramChannelStore.Ch ch, boolean reveal) {
        if (ch == null || destroyed) return;
        if (ch.plus && !CigramPlusUI.requirePlus(a, "قنوات 1080p – 4K")) return;      // Cigram Plus tier
        try {
            hideKeyboard();
            String before = activeKey;
            activeKey = ch.key;
            CigramChannelStore.pushRecent(a, ch.key);
            player.play(ch);      // the same player, retargeted: nothing else on the page is rebuilt
            showInfo(ch);
            markActive(before, ch.key);
            if (reveal && scroll != null && scroll.getScrollY() > 0) scroll.smoothScrollTo(0, 0);   // back up to the picture
            refreshChips();
            if (chipKind == CH_RECENT) refreshDynamic(recentSection);
            if (catalog != null && recentSection != null && chipKind != CH_RECENT) refreshDynamic(recentSection);
        } catch (RuntimeException error) {
            Log.w(TAG, "choose failed", error);
        }
    }

    private void markActive(String before, String now) {
        ArrayList<CigramChannelCard> old = cardsByKey.get(before);
        if (old != null) for (int i = 0; i < old.size(); i++) old.get(i).setActive(false);
        ArrayList<CigramChannelCard> cur = cardsByKey.get(now);
        if (cur != null) for (int i = 0; i < cur.size(); i++) cur.get(i).setActive(true);
    }

    private void showInfo(CigramChannelStore.Ch ch) {
        infoName.setText(ch.name);
        infoName.setTextColor(pal.text);
        showStatus(lastStatus < 0 ? CigramChannelPlayer.ST_LOADING : lastStatus, lastStatusMessage);
        infoLogoHost.removeAllViews();
        CigramChannelCard logo = new CigramChannelCard(a, ch, null);
        logo.setLogoOnly(true);
        infoLogoHost.addView(logo, new FrameLayout.LayoutParams(-1, -1));
        infoLogoHost.setVisibility(View.VISIBLE);
        logo.ensureLogo();
        boolean fav = CigramChannelStore.isFav(a, ch.key);
        infoHeart.set(fav ? CigramHubUi.HEART_FILL : CigramHubUi.HEART, fav ? pal.brand : pal.text3);
    }

    /** Second line under the name: package · what the picture is doing. */
    private void showStatus(int status, String message) {
        lastStatus = status;
        lastStatusMessage = message == null ? "" : message;
        if (infoSub == null) return;
        CigramChannelStore.Ch ch = player == null ? null : player.current();
        if (ch == null) {
            infoSub.setText("اختر قناة من القائمة لتبدأ المشاهدة");
            infoSub.setTextColor(pal.text3);
            return;
        }
        String label;
        int color = pal.text3;
        switch (status) {
            case CigramChannelPlayer.ST_PLAYING: label = "● بث مباشر"; color = pal.brand; break;
            case CigramChannelPlayer.ST_PAUSED: label = "متوقفة مؤقتاً"; break;
            case CigramChannelPlayer.ST_ERROR: label = lastStatusMessage.length() > 0 ? lastStatusMessage : "تعذر التشغيل"; color = pal.brand; break;
            default: label = "جارٍ التحميل…";
        }
        String full = label + "  ·  " + ch.section;
        SpannableString span = new SpannableString(full);
        span.setSpan(new ForegroundColorSpan(color), 0, label.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        infoSub.setText(span);
        infoSub.setTextColor(pal.text3);
    }

    private void toggleFavourite(CigramChannelStore.Ch ch) {
        boolean now = CigramChannelStore.toggleFav(a, ch.key);
        try { root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); } catch (RuntimeException ignored) { }
        ArrayList<CigramChannelCard> cards = cardsByKey.get(ch.key);
        if (cards != null) for (int i = 0; i < cards.size(); i++) cards.get(i).setFav(now);
        if (ch.key.equals(activeKey)) {
            infoHeart.set(now ? CigramHubUi.HEART_FILL : CigramHubUi.HEART, now ? pal.brand : pal.text3);
        }
        refreshDynamic(favSection);
        if (chipKind == CH_FAV) applyFilter();
    }

    /** The list a "next / previous" step walks: what is on screen (chip + search), without the duplicates. */
    private ArrayList<CigramChannelStore.Ch> stepList() {
        ArrayList<CigramChannelStore.Ch> out = new ArrayList<CigramChannelStore.Ch>();
        if (catalog == null) return out;
        ArrayList<CigramChannelStore.Ch> base;
        if (chipKind == CH_FAV || chipKind == CH_RECENT) base = channelsOf(chipKind);
        else if (chipKind == CH_PKG) {
            Section s = findSection(CH_PKG, chipName);
            base = s == null ? catalog.all : s.list;
        } else base = catalog.all;
        for (int i = 0; i < base.size(); i++) if (matches(base.get(i))) out.add(base.get(i));
        return out.isEmpty() ? catalog.all : out;
    }

    @Override public void onStep(int delta) {
        if (destroyed) return;
        try {
            ArrayList<CigramChannelStore.Ch> list = stepList();
            int n = list.size();
            if (n == 0) return;
            int at = -1;
            for (int i = 0; i < n; i++) if (list.get(i).key.equals(activeKey)) { at = i; break; }
            for (int step = 1; step <= n; step++) {
                int i = ((at < 0 ? (delta > 0 ? -1 : 0) : at) + delta * step) % n;
                if (i < 0) i += n;
                CigramChannelStore.Ch ch = list.get(i);
                if (!ch.available) continue;
                choose(ch, false);
                return;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "next/previous failed", error);
        }
    }

    @Override public void onFullscreen() {
        CigramChannelStore.Ch ch = player.current();
        if (ch == null || catalog == null) return;
        try {
            ArrayList<CigramLivePlayer.Entry> queue = new ArrayList<CigramLivePlayer.Entry>();
            int at = -1;
            for (int i = 0; i < catalog.all.size(); i++) {
                CigramChannelStore.Ch c = catalog.all.get(i);
                if (!c.available) continue;
                if (c.plus && !CigramPlus.isPlus(a)) continue;     // next/previous in full screen never lands on a Plus channel
                if (c == ch) at = queue.size();
                queue.add(CigramLivePlayer.entry(c.key, c.name, c.url, c.image, c.section, null));
            }
            if (at < 0) {
                at = queue.size();
                queue.add(CigramLivePlayer.entry(ch.key, ch.name, ch.url, ch.image, ch.section, null));
            }
            CigramLivePlayer.setQueue(queue, at);
            Intent live = new Intent(a, Class.forName(a.getPackageName() + ".LiveActivity"));
            live.putExtra("live_url", ch.url);
            live.putExtra("video", ch.url);
            live.putExtra("movie_url", ch.url);
            live.putExtra("title", ch.name);
            live.putExtra("type", "live");
            live.putExtra("autoplay", true);
            wentFull = true;
            player.suspend();   // the full player takes the decoder
            a.startActivity(live);
        } catch (ClassNotFoundException | RuntimeException error) {
            wentFull = false;
            Log.w(TAG, "full screen failed", error);
            player.resume();
            CigramSilent.toast(a, "تعذر فتح القناة", android.widget.Toast.LENGTH_SHORT);
        }
    }

    // ================================================================== immersive (phone landscape), bar, back

    private void enterImmersive() {
        try {
            View decor = a.getWindow().getDecorView();
            if (!immersive) {
                uiBefore = decor.getSystemUiVisibility();
                immersive = true;
            }
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } catch (RuntimeException error) {
            Log.w(TAG, "immersive failed", error);
        }
    }

    private void leaveImmersive() {
        try {
            if (!immersive) return;
            immersive = false;
            a.getWindow().getDecorView().setSystemUiVisibility(uiBefore);
        } catch (RuntimeException error) {
            Log.w(TAG, "leave immersive failed", error);
        }
    }

    /**
     * The soft keyboard resizes the window; the tab bar (part of the window) would then ride on top of the keyboard and
     * hide the search field. While the keyboard is up the bar is hidden and the list gets a small bottom padding.
     */
    private void checkIme() {
        if (destroyed || root == null || cinema) return;
        root.getWindowVisibleDisplayFrame(frame);
        int full = root.getRootView().getHeight();
        boolean shown = full > 0 && full - frame.bottom > full * 0.18f;
        if (shown == imeShown) return;
        imeShown = shown;
        scroll.setPadding(0, 0, 0, shown ? dp(12) : navPad);
        updateNavBar();
    }

    /** The bottom bar belongs to the portrait page only, and not while the keyboard is up. */
    private void updateNavBar() {
        try {
            View parent = (View) root.getParent();
            View bar = parent == null ? null : parent.findViewWithTag(BAR_TAG);
            if (bar == null) return;
            if (cinema || imeShown) {
                if (bar.getVisibility() != View.GONE) {
                    bar.setVisibility(View.GONE);
                    barHidden = true;
                }
            } else if (barHidden) {
                bar.setVisibility(View.VISIBLE);
                barHidden = false;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "bar failed", error);
        }
    }

    private int navigationBarHeight() {
        try {
            int id = a.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) return a.getResources().getDimensionPixelSize(id);
        } catch (RuntimeException error) {
            Log.w(TAG, "nav height unknown", error);
        }
        return 0;
    }

    private void setupBack() {
        if (!(a instanceof androidx.activity.ComponentActivity)) return;
        androidx.activity.OnBackPressedCallback cb = new androidx.activity.OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                if (query.length() > 0) input.setText("");
                else selectChip(CH_ALL, "");
                updateBack();
            }
        };
        ((androidx.activity.ComponentActivity) a).getOnBackPressedDispatcher().addCallback(cb);
        backCallback = cb;
    }

    /** Back first clears the search, then returns to «الكل»; on a clean page it leaves like before. */
    private void updateBack() {
        if (backCallback instanceof androidx.activity.OnBackPressedCallback) {
            ((androidx.activity.OnBackPressedCallback) backCallback).setEnabled(query.length() > 0 || chipKind != CH_ALL);
        }
    }

    // ---- told apart from the page's own scrolling: a touch counts as a scroll only once it has moved past the slop
    private int slop;
    private boolean touching;
    private boolean dragged;
    private float downX, downY;
    private long touchEnd;

    private void trackTouch(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                dragged = false;
                downX = ev.getX();
                downY = ev.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragged && (Math.abs(ev.getY() - downY) > slop || Math.abs(ev.getX() - downX) > slop)) dragged = true;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                touchEnd = SystemClock.uptimeMillis();
                break;
            default:
                break;
        }
    }

    /** True while the finger is dragging the page, and for the moment its fling lasts afterwards. */
    private boolean userScrolling() {
        return dragged && (touching || SystemClock.uptimeMillis() - touchEnd < 1500L);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && input != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            if (input != null && input.hasFocus()) input.clearFocus();
        } catch (RuntimeException error) {
            Log.w(TAG, "keyboard failed", error);
        }
    }

    // ================================================================== lifecycle

    private void onStarted() {
        if (destroyed) return;
        try {
            updateNavBar();
            if (wentFull) {
                wentFull = false;
                // the full player marks the channel it ended on as the most recent one
                ArrayList<String> recent = CigramChannelStore.recent(a);
                if (!recent.isEmpty() && catalog != null && !recent.get(0).equals(activeKey)) {
                    CigramChannelStore.Ch ch = catalog.byKey.get(recent.get(0));
                    if (ch != null) {
                        choose(ch, false);
                        return;
                    }
                }
            }
            player.resume();
            if (catalog != null && !cinema) {
                refreshChips();
                refreshDynamicSections();
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "start failed", error);
        }
    }

    private void onStopped() {
        try {
            player.suspend();
        } catch (RuntimeException error) {
            Log.w(TAG, "stop failed", error);
        }
    }

    private void onDestroyed(boolean changingConfig) {
        destroyed = true;
        UI.removeCallbacks(applyQuery);
        UI.removeCallbacks(buildStep);
        UI.removeCallbacks(loadLogos);
        CigramChannelStore.unlisten(this);
        if (changingConfig) {
            stickyAt = SystemClock.uptimeMillis();
            stickyKind = chipKind;
            stickyName = chipName;
            stickyChannel = player.isActive() && player.current() != null ? player.current().key : "";
            stickyQuery = query;
        }
        try {
            player.setStatusListener(null);
            player.destroy();
        } catch (RuntimeException error) {
            Log.w(TAG, "player release failed", error);
        }
        if (scroll != null) scroll.setOnScrollChangeListener(null);
        try {
            ViewTreeObserver vto = root.getViewTreeObserver();
            if (vto.isAlive()) vto.removeOnGlobalLayoutListener(imeWatcher);
        } catch (RuntimeException error) {
            Log.w(TAG, "ime watcher cleanup", error);
        }
        leaveImmersive();
        allCards.clear();
        cardsByKey.clear();
        sections.clear();
    }

    private void restoreSticky() {
        if (stickyAt == 0L || SystemClock.uptimeMillis() - stickyAt > 5000L) {
            stickyAt = 0L;
            return;
        }
        stickyAt = 0L;
        chipKind = stickyKind;
        chipName = stickyName;
        query = stickyQuery;
        pendingChannel = stickyChannel;
        if (query.length() > 0 && input != null) input.setText(query);
        updateBack();
    }

    private static void registerLifecycle(Activity a) {
        if (lifecycleRegistered) return;
        ((Application) a.getApplicationContext()).registerActivityLifecycleCallbacks(
                new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityCreated(Activity x, Bundle b) { }
                    @Override public void onActivityStarted(Activity x) {
                        CigramLiveHub h = hub(x);
                        if (h != null) h.onStarted();
                    }
                    @Override public void onActivityResumed(Activity x) { }
                    @Override public void onActivityPaused(Activity x) { }
                    @Override public void onActivityStopped(Activity x) {
                        CigramLiveHub h = hub(x);
                        if (h != null) h.onStopped();
                    }
                    @Override public void onActivitySaveInstanceState(Activity x, Bundle b) { }
                    @Override public void onActivityDestroyed(Activity x) {
                        CigramLiveHub h;
                        synchronized (HUBS) { h = HUBS.remove(x); }   // the hub holds its Activity: drop it explicitly
                        if (h != null) h.onDestroyed(x.isChangingConfigurations());
                    }
                });
        lifecycleRegistered = true;
    }

    private static CigramLiveHub hub(Activity x) {
        synchronized (HUBS) { return HUBS.get(x); }
    }

    // ================================================================== small layouts

    /** A frame that is always 16 : 9 (or fills its parent in the cinema mode). */
    private static final class AspectFrame extends FrameLayout {
        private boolean fill;

        AspectFrame(Context c) {
            super(c);
        }

        void setFill(boolean on) {
            fill = on;
            requestLayout();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            if (fill) {
                super.onMeasure(widthSpec, heightSpec);
                return;
            }
            int width = MeasureSpec.getSize(widthSpec);
            int height = Math.round(width * 9f / 16f);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    /** The scrolling column: full width on a phone, a readable maximum width (centred) on a tablet. */
    private static final class Column extends LinearLayout {
        private final int max;

        Column(Context c, int maxWidth) {
            super(c);
            max = maxWidth;
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            if (w > max) widthSpec = MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY);
            super.onMeasure(widthSpec, heightSpec);
        }
    }
}
