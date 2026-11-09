package com.Cigram.vid;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * v10.63 — the channels tab, redesigned.
 *
 *  ┌──────────────────────────────┐
 *  │   the player (starts EMPTY)  │   a small ExoPlayer that is built on the first channel
 *  ├──────────────────────────────┤
 *  │ ♡  name of the channel   (logo) │   what is playing, favourite button
 *  │ [ ابحث عن قناة…            🔍 ]│
 *  ├──────────────────────────────┤
 *  │ 1) the packages (full width)    │   pick a package …
 *  │ 2) channels | packages          │   … and the second page opens: its channels on the right,
 *  └──────────────────────────────┘      the other packages stay one tap away on the left
 *
 * Built for speed:
 *  - the catalogue is parsed once and kept in memory ({@link CigramChannelStore}); opening the tab again
 *    shows the list in the first frame;
 *  - two plain ListViews with recycled rows (four views per row); logos are drawn by one custom view,
 *    decoded small, cached in memory and on disk ({@link CigramLogos}) and not even requested
 *    while the list is flinging;
 *  - nothing is created until it is needed: the ExoPlayer is built on the first channel and released
 *    as soon as the tab is not on screen.
 *
 * The page replaces the old one only when this class builds successfully ({@link #install}); if it
 * ever throws, ChannelsActivity falls back to its previous code.
 */
final class CigramChannelHub implements CigramChannelStore.Listener, CigramChannelPlayer.Host {

    private static final String TAG = "CigramChannelHub";
    private static final String BAR_TAG = "transparent_cigram_nav";
    private static final String NO_NAV_PAD = "cigram_nav_managed";

    private static final int BG = 0xFF0E0E10;
    private static final int CARD = 0xFF1E1E21;
    private static final int SOFT = 0xFF26262B;
    private static final int STROKE = 0xFF34343A;
    private static final int RED = 0xFFE21B14;
    private static final int MUTED = 0xFF8E8E94;
    private static final int TEXT = 0xFFFFFFFF;

    private static final int K_PKG = 0, K_FAV = 1, K_RECENT = 2, K_ALL = 3;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Activity, CigramChannelHub> HUBS = new WeakHashMap<Activity, CigramChannelHub>();
    private static boolean lifecycleRegistered;

    /** Kept for a few seconds when the screen is recreated by a rotation. */
    private static long stickyAt;
    private static int stickyKind = -1;
    private static String stickyPkg = "";
    private static String stickyChannel = "";
    private static String stickyQuery = "";

    // ------------------------------------------------------------------ rows of the package list

    private static final class PkgItem {
        int kind;
        String title = "";
        int count;
        CigramChannelStore.Pkg pkg;
    }

    // ------------------------------------------------------------------ state

    private final Activity a;
    private final float density;
    private CigramChannelStore.Catalog catalog;
    private final ArrayList<PkgItem> pkgItems = new ArrayList<PkgItem>();
    private final ArrayList<CigramChannelStore.Ch> shown = new ArrayList<CigramChannelStore.Ch>();
    private int selKind = -1;          // -1 = no package chosen: the packages page
    private String selName = "";
    private boolean selByQuery;        // the "all channels" list was opened by typing
    private String query = "";
    private String activeKey = "";
    private boolean flinging;
    private boolean needsRebind;
    private boolean inSecondPage;
    private boolean landscape;
    private boolean destroyed;
    private boolean wentFull;
    private int uiBefore;
    private boolean immersive;
    private boolean barHidden;
    private int navPad;

    // ------------------------------------------------------------------ views

    private LinearLayout root;
    private FrameLayout stageWrap;
    private FrameLayout stage;
    private LinearLayout infoBar;
    private LinearLayout searchRow;
    private FrameLayout body;
    private FrameLayout pageOne;
    private CigramHubUi.Split2 pageTwo;
    private ListView wideList;
    private ListView narrowList;
    private ListView chList;
    private TextView pageOneState;
    private TextView chEmpty;
    private TextView chTitle;
    private TextView chCount;
    private TextView infoName;
    private TextView infoSub;
    private CigramHubUi.LogoView infoLogo;
    private CigramHubUi.Icon infoHeart;
    private EditText input;
    private View clearBtn;
    private PkgAdapter wideAdapter;
    private PkgAdapter narrowAdapter;
    private ChAdapter chAdapter;
    private CigramChannelPlayer player;
    private Object backCallback;
    private int stageHeight;
    private int topInset;

    private final Runnable applyQuery = new Runnable() {
        @Override public void run() { onQueryChanged(); }
    };

    private CigramChannelHub(Activity activity) {
        a = activity;
        density = activity.getResources().getDisplayMetrics().density;
        tv = CigramTvMode.isTv(activity);
    }

    /** v10.66: TV layout (lists on the left, the picture on the right; no cinema mode on rotation). */
    private final boolean tv;

    // ================================================================== entry point

    /**
     * Replaces the content of ChannelsActivity with the new page.
     * @return false when the page could not be built: the caller then keeps the old one.
     */
    static boolean install(Activity activity) {
        if (activity == null || !"ChannelsActivity".equals(activity.getClass().getSimpleName())) return false;
        try {
            registerLifecycle(activity);
            CigramChannelHub hub = new CigramChannelHub(activity);
            hub.build();   // its last step puts the page on screen: from here on there is no way back to the old one
            synchronized (HUBS) { HUBS.put(activity, hub); }
            try {
                hub.start();
            } catch (Throwable error) {
                Log.w(TAG, "channels page started without its catalogue", error);
            }
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "new channels page failed, using the old one", error);
            return false;
        }
    }

    // ================================================================== building

    private int dp(float v) {
        return CigramUI.dp(a, v);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        return CigramUI.label(a, s, sp, CigramTheme.adaptText(a, color), bold);   // v10.72: dark text on the light page
    }

    private void build() {
        Configuration cfg = a.getResources().getConfiguration();
        landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE;
        int screenW = Math.min(a.getResources().getDisplayMetrics().widthPixels,
                a.getResources().getDisplayMetrics().heightPixels);
        int screenH = Math.max(a.getResources().getDisplayMetrics().widthPixels,
                a.getResources().getDisplayMetrics().heightPixels);
        stageHeight = Math.min(Math.round(screenW * 9f / 16f), Math.round(screenH * 0.36f));
        navPad = dp(84) + navigationBarHeight();
        if (tv) {
            // the picture column is a little less than half of what the side rail leaves
            int content = screenH - CigramTvRail.railWidth(a) - CigramTvRail.lineWidth(a);
            int playerW = Math.round(content / 2.15f) - dp(18);
            stageHeight = Math.max(dp(120), Math.round(playerW * 9f / 16f));
            navPad = dp(16);
            landscape = false;
        }

        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        root.setBackgroundColor(BG);

        // ---- 1. the player
        stageWrap = new FrameLayout(a);
        stageWrap.setBackgroundColor(CigramTheme.adapt(a, 0xFF0C0C0E));
        stage = new FrameLayout(a);
        stage.setClickable(true);
        stageWrap.addView(stage, new FrameLayout.LayoutParams(-1, stageHeight, Gravity.BOTTOM));
        root.addView(stageWrap, new LinearLayout.LayoutParams(-1, stageHeight));
        player = new CigramChannelPlayer(a, stage, this);

        // ---- 2. what is playing
        infoBar = new LinearLayout(a);
        infoBar.setOrientation(LinearLayout.HORIZONTAL);
        infoBar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        infoBar.setGravity(Gravity.CENTER_VERTICAL);
        infoBar.setPadding(dp(8), 0, dp(14), 0);
        infoHeart = new CigramHubUi.Icon(a, CigramHubUi.HEART, MUTED);
        FrameLayout heartBox = new FrameLayout(a);
        heartBox.setClickable(true);
        heartBox.setContentDescription("إضافة إلى المفضلة");
        heartBox.addView(infoHeart, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        heartBox.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CigramChannelStore.Ch ch = player.current();
                if (ch != null) toggleFavourite(ch);
            }
        });
        infoBar.addView(heartBox, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout nameCol = new LinearLayout(a);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        nameCol.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        infoName = text("لم تختر قناة بعد", 15f, TEXT, true);
        infoName.setSingleLine(true);
        infoName.setEllipsize(TextUtils.TruncateAt.END);
        nameCol.addView(infoName, new LinearLayout.LayoutParams(-1, -2));
        infoSub = text("اختر باقة ثم قناة لتبدأ المشاهدة", 11.5f, MUTED, false);
        infoSub.setSingleLine(true);
        infoSub.setEllipsize(TextUtils.TruncateAt.END);
        nameCol.addView(infoSub, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams ncl = new LinearLayout.LayoutParams(0, -2, 1f);
        ncl.rightMargin = dp(10);
        infoBar.addView(nameCol, ncl);
        infoLogo = new CigramHubUi.LogoView(a, false, dp(10));
        infoLogo.setVisibility(View.INVISIBLE);   // nothing is playing yet
        infoBar.addView(infoLogo, new LinearLayout.LayoutParams(dp(38), dp(38)));
        root.addView(infoBar, new LinearLayout.LayoutParams(-1, dp(54)));

        // ---- 3. search
        searchRow = new LinearLayout(a);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(CigramUI.round(SOFT, STROKE, 14, a));
        CigramHubUi.Icon clearIcon = new CigramHubUi.Icon(a, CigramHubUi.CLOSE, MUTED);
        FrameLayout clear = new FrameLayout(a);
        clear.setClickable(true);
        clear.setContentDescription("مسح البحث");
        clear.addView(clearIcon, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { input.setText(""); }
        });
        clearBtn = clear;
        searchRow.addView(clear, new LinearLayout.LayoutParams(dp(40), -1));
        input = new EditText(a);
        CigramUI.styleInput(input, "ابحث عن قناة أو باقة…");
        input.setBackground(null);
        input.setPadding(dp(4), 0, dp(4), 0);
        input.setTextSize(14f);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
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
                UI.postDelayed(applyQuery, 110L);
            }
        });
        searchRow.addView(input, new LinearLayout.LayoutParams(0, -1, 1f));
        CigramHubUi.Icon searchIcon = new CigramHubUi.Icon(a, CigramHubUi.SEARCH, MUTED);
        FrameLayout searchBox = new FrameLayout(a);
        searchBox.addView(searchIcon, new FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER));
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(dp(42), -1));
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(-1, dp(44));
        srl.setMargins(dp(12), dp(4), dp(12), dp(8));
        root.addView(searchRow, srl);

        // ---- 4. the two pages
        body = new FrameLayout(a);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        wideAdapter = new PkgAdapter(false);
        narrowAdapter = new PkgAdapter(true);
        chAdapter = new ChAdapter();

        pageOne = new FrameLayout(a);
        wideList = newList(dp(6), dp(8));
        wideList.addHeaderView(sectionHeader("الباقات"), null, false);   // before setAdapter, as old devices need
        wideList.setAdapter(wideAdapter);
        pageOne.addView(wideList, new FrameLayout.LayoutParams(-1, -1));
        pageOneState = text("", 14f, MUTED, false);
        pageOneState.setGravity(Gravity.CENTER);
        pageOneState.setPadding(dp(30), 0, dp(30), dp(60));
        pageOne.addView(pageOneState, new FrameLayout.LayoutParams(-1, -1));

        pageTwo = new CigramHubUi.Split2(a, dp(6));
        LinearLayout chPane = new LinearLayout(a);
        chPane.setOrientation(LinearLayout.VERTICAL);
        chPane.addView(secondPageHeader(), new LinearLayout.LayoutParams(-1, dp(42)));
        FrameLayout chFrame = new FrameLayout(a);
        chList = newList(dp(4), dp(2));
        chList.setAdapter(chAdapter);
        chFrame.addView(chList, new FrameLayout.LayoutParams(-1, -1));
        chEmpty = text("", 13f, MUTED, false);
        chEmpty.setGravity(Gravity.CENTER);
        chEmpty.setPadding(dp(18), 0, dp(18), dp(60));
        chFrame.addView(chEmpty, new FrameLayout.LayoutParams(-1, -1));
        chPane.addView(chFrame, new LinearLayout.LayoutParams(-1, 0, 1f));
        pageTwo.addView(chPane);

        LinearLayout pkgPane = new LinearLayout(a);
        pkgPane.setOrientation(LinearLayout.VERTICAL);
        TextView pkgTitle = text("الباقات", 13f, MUTED, true);
        pkgTitle.setPadding(dp(10), 0, dp(12), 0);
        pkgTitle.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        pkgPane.addView(pkgTitle, new LinearLayout.LayoutParams(-1, dp(42)));
        narrowList = newList(dp(2), dp(2));
        narrowList.setAdapter(narrowAdapter);
        pkgPane.addView(narrowList, new LinearLayout.LayoutParams(-1, 0, 1f));
        pageTwo.addView(pkgPane);

        body.addView(pageOne, new FrameLayout.LayoutParams(-1, -1));

        // ---- window insets (status bar) and size changes (rotation)
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                try {
                    int top = insets == null ? 0 : insets.getSystemWindowInsetTop();
                    if (top != topInset) {
                        topInset = top;
                        applyOrientation(true);
                    }
                } catch (Throwable error) {
                    Log.w(TAG, "insets failed", error);
                }
                return insets;
            }
        });
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                try {
                    if (r - l != or - ol) applyOrientation(false);
                } catch (Throwable error) {
                    Log.w(TAG, "layout change failed", error);
                }
            }
        });

        refreshPageState();
        if (tv) {
            try {
                arrangeForTv();
            } catch (Throwable error) {
                Log.w(TAG, "tv layout failed", error);
            }
        }
        a.setContentView(root);
        try {
            setupBack();
            applyOrientation(true);
        } catch (Throwable error) {
            Log.w(TAG, "channels page layout adjust failed", error);
        }
    }

    /**
     * v10.66 (TV only): [ search + packages / channels ] [ the picture + what is playing ].
     * OK on the picture opens the channel in the full screen player; the channel keys of the
     * remote (and next / previous) zap, play/pause pauses.
     */
    /** Replaces a phone-only hint text inside v (TV layout). */
    private static void tvRetext(View v, String from, String to) {
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (t != null && from.equals(t.toString())) ((TextView) v).setText(to);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) tvRetext(g.getChildAt(i), from, to);
        }
    }

    private void arrangeForTv() {
        root.removeAllViews();
        root.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout listCol = new LinearLayout(a);
        listCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams srl = new LinearLayout.LayoutParams(-1, dp(44));
        srl.setMargins(dp(12), dp(14), dp(12), dp(8));
        listCol.addView(searchRow, srl);
        listCol.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout playerCol = new LinearLayout(a);
        playerCol.setOrientation(LinearLayout.VERTICAL);
        playerCol.setPadding(dp(4), dp(14), dp(14), dp(10));
        playerCol.addView(stageWrap, new LinearLayout.LayoutParams(-1, stageHeight));
        playerCol.addView(infoBar, new LinearLayout.LayoutParams(-1, dp(54)));
        TextView hint = text("اضغط OK على الصورة للمشاهدة بملء الشاشة", 12f, MUTED, false);
        hint.setGravity(Gravity.CENTER);
        playerCol.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        root.addView(listCol, new LinearLayout.LayoutParams(0, -1, 1.15f));
        root.addView(playerCol, new LinearLayout.LayoutParams(0, -1, 1f));

        tvRetext(stage, "ابدأ بباقة من القائمة في الأسفل", "اختر باقة من القائمة ثم قناة");

        // OK on the picture: full screen (nothing to show until a channel plays)
        stage.setContentDescription("المشاهدة بملء الشاشة");
        stage.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (player.current() != null) onFullscreen();
            }
        });
        CigramTvFocus.setKeyHandler(a, new CigramTvFocus.KeyHandler() {
            @Override public boolean onKey(android.view.KeyEvent e) {
                int c = e.getKeyCode();
                boolean down = e.getAction() == android.view.KeyEvent.ACTION_DOWN;
                if (c == android.view.KeyEvent.KEYCODE_CHANNEL_UP || c == android.view.KeyEvent.KEYCODE_MEDIA_NEXT) {
                    if (down) onStep(1);
                    return true;
                }
                if (c == android.view.KeyEvent.KEYCODE_CHANNEL_DOWN || c == android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                    if (down) onStep(-1);
                    return true;
                }
                int which = c == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ? 0
                        : c == android.view.KeyEvent.KEYCODE_MEDIA_PLAY ? 1
                        : c == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE ? 2 : -1;
                if (which >= 0) {
                    if (down && e.getRepeatCount() == 0) player.tvPlayKey(which);
                    return true;
                }
                return false;
            }
        });
    }

    private void start() {
        restoreSticky();
        CigramChannelStore.load(a, this);
    }

    private ListView newList(int sidePx, int dividerPx) {
        ListView list = new ListView(a);
        list.setTag(NO_NAV_PAD);   // CigramBottomNav leaves the padding of this list to the page
        list.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dividerPx);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setHorizontalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setHeaderDividersEnabled(false);
        list.setFooterDividersEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(sidePx, dp(2), sidePx, navPad);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int state) {
                boolean fling = state == AbsListView.OnScrollListener.SCROLL_STATE_FLING;
                if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard();
                if (fling != flinging) {
                    flinging = fling;
                    if (!fling && needsRebind) {
                        needsRebind = false;
                        wideAdapter.notifyDataSetChanged();
                        narrowAdapter.notifyDataSetChanged();
                        chAdapter.notifyDataSetChanged();
                    }
                }
            }
            @Override public void onScroll(AbsListView v, int first, int visible, int total) { }
        });
        return list;
    }

    private View sectionHeader(String title) {
        LinearLayout h = new LinearLayout(a);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(6), 0, dp(6), 0);
        View bar = new View(a);
        bar.setBackground(CigramUI.round(RED, 2, a));
        TextView t = text(title, 17f, TEXT, true);
        t.setIncludeFontPadding(false);
        LinearLayout.LayoutParams spacer = new LinearLayout.LayoutParams(0, 1, 1f);
        h.addView(new View(a), spacer);
        h.addView(t, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(4), dp(18));
        bl.leftMargin = dp(8);
        h.addView(bar, bl);
        h.setLayoutParams(new AbsListView.LayoutParams(-1, dp(38)));
        return h;
    }

    private View secondPageHeader() {
        LinearLayout h = new LinearLayout(a);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(4), 0, dp(8), 0);

        // back to the packages page (left end)
        LinearLayout back = new LinearLayout(a);
        back.setOrientation(LinearLayout.HORIZONTAL);
        back.setGravity(Gravity.CENTER);
        back.setBackground(CigramUI.round(SOFT, STROKE, 14, a));
        back.setPadding(dp(8), 0, dp(10), 0);
        back.setClickable(true);
        back.setContentDescription("كل الباقات");
        back.addView(new CigramHubUi.Icon(a, CigramHubUi.GRID, MUTED), new LinearLayout.LayoutParams(dp(15), dp(15)));
        TextView backText = text("الباقات", 11.5f, 0xFFDCE6EA, true);
        backText.setIncludeFontPadding(false);
        LinearLayout.LayoutParams btl = new LinearLayout.LayoutParams(-2, -2);
        btl.leftMargin = dp(5);
        back.addView(backText, btl);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { backToPackages(); }
        });
        if (tv) back.setVisibility(View.GONE);   // v10.67: the packages are always beside the channels
        h.addView(back, new LinearLayout.LayoutParams(-2, dp(28)));
        h.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));

        chCount = text("", 11.5f, MUTED, false);
        chCount.setIncludeFontPadding(false);
        LinearLayout.LayoutParams ccl = new LinearLayout.LayoutParams(-2, -2);
        ccl.rightMargin = dp(6);
        chTitle = text("", 15f, TEXT, true);
        chTitle.setSingleLine(true);
        chTitle.setEllipsize(TextUtils.TruncateAt.END);
        chTitle.setIncludeFontPadding(false);
        h.addView(chCount, ccl);
        h.addView(chTitle, new LinearLayout.LayoutParams(-2, -2));
        return h;
    }

    // ================================================================== catalogue

    @Override public void onCatalog(CigramChannelStore.Catalog c, boolean changed) {
        if (destroyed) return;
        try {
            applyCatalog(c);
        } catch (Throwable error) {
            Log.w(TAG, "catalogue update failed", error);
        }
    }

    private void applyCatalog(CigramChannelStore.Catalog c) {
        catalog = c;
        rebuildPackages();
        // a package that was open may have disappeared
        if (selKind == K_PKG && findPackage(selName) == null) {
            selKind = -1;
            selName = "";
            switchPage(false);
        }
        refreshChannels();
        refreshPageState();
        if (tv && !inSecondPage) switchPage(false);   // v10.67: TV → straight to the three columns
        // the channel that was playing (rotation) or asked for before the catalogue was there
        if (!stickyChannelPending.isEmpty()) {
            CigramChannelStore.Ch ch = c.byKey.get(stickyChannelPending);
            stickyChannelPending = "";
            if (ch != null) choose(ch);
        }
    }

    private String stickyChannelPending = "";

    private CigramChannelStore.Pkg findPackage(String name) {
        if (catalog == null) return null;
        for (int i = 0; i < catalog.packages.size(); i++) {
            if (catalog.packages.get(i).name.equals(name)) return catalog.packages.get(i);
        }
        return null;
    }

    private void rebuildPackages() {
        pkgItems.clear();
        if (catalog == null) {
            wideAdapter.notifyDataSetChanged();
            narrowAdapter.notifyDataSetChanged();
            return;
        }
        int favCount = channelsOf(K_FAV, "").size();
        int recentCount = channelsOf(K_RECENT, "").size();
        if (favCount > 0) pkgItems.add(item(K_FAV, "المفضلة", favCount, null));
        if (recentCount > 0) pkgItems.add(item(K_RECENT, "شاهدتها مؤخراً", recentCount, null));
        if (!catalog.all.isEmpty()) pkgItems.add(item(K_ALL, "كل القنوات", catalog.all.size(), null));
        for (int i = 0; i < catalog.packages.size(); i++) {
            CigramChannelStore.Pkg p = catalog.packages.get(i);
            pkgItems.add(item(K_PKG, p.name, p.channels.size(), p));
        }
        wideAdapter.notifyDataSetChanged();
        narrowAdapter.notifyDataSetChanged();
    }

    private PkgItem item(int kind, String title, int count, CigramChannelStore.Pkg pkg) {
        PkgItem it = new PkgItem();
        it.kind = kind;
        it.title = title;
        it.count = count;
        it.pkg = pkg;
        return it;
    }

    private ArrayList<CigramChannelStore.Ch> channelsOf(int kind, String name) {
        ArrayList<CigramChannelStore.Ch> out = new ArrayList<CigramChannelStore.Ch>();
        if (catalog == null) return out;
        if (kind == K_ALL) {
            out.addAll(catalog.all);
        } else if (kind == K_PKG) {
            CigramChannelStore.Pkg p = findPackage(name);
            if (p != null) out.addAll(p.channels);
        } else {
            ArrayList<String> keys = kind == K_FAV ? CigramChannelStore.favourites(a) : CigramChannelStore.recent(a);
            for (int i = 0; i < keys.size(); i++) {
                CigramChannelStore.Ch ch = catalog.byKey.get(keys.get(i));
                if (ch != null) out.add(ch);
            }
        }
        return out;
    }

    /** Rebuilds the list of the second page from the open package and the search text. */
    private void refreshChannels() {
        shown.clear();
        if (catalog == null) {
            chAdapter.notifyDataSetChanged();
            return;
        }
        int kind = selKind;
        String name = selName;
        if (kind < 0 && query.length() > 0) kind = K_ALL;   // typing on the packages page searches everything
        if (kind >= 0) {
            ArrayList<CigramChannelStore.Ch> base = channelsOf(kind, name);
            String nq = CigramChannelStore.norm(query);
            for (int i = 0; i < base.size(); i++) {
                CigramChannelStore.Ch ch = base.get(i);
                if (nq.length() == 0 || ch.search.contains(nq) || ch.sectionKey.contains(nq)) shown.add(ch);
            }
        }
        String title;
        if (query.length() > 0 && (selByQuery || kind == K_ALL)) title = "نتائج البحث";
        else if (kind == K_FAV) title = "المفضلة";
        else if (kind == K_RECENT) title = "شاهدتها مؤخراً";
        else if (kind == K_ALL) title = "كل القنوات";
        else title = name;
        chTitle.setText(title);
        chCount.setText(shown.size() + " قناة");
        if (shown.isEmpty() && kind >= 0) {
            chEmpty.setText(query.length() > 0 ? "لا توجد قناة بهذا الاسم" : "لا توجد قنوات هنا بعد");
            chEmpty.setVisibility(View.VISIBLE);
        } else {
            chEmpty.setVisibility(View.GONE);
        }
        chAdapter.notifyDataSetChanged();
        narrowAdapter.notifyDataSetChanged();
    }

    private void refreshPageState() {
        if (catalog == null) {
            pageOneState.setText("جارٍ تحميل القنوات…");
            pageOneState.setVisibility(View.VISIBLE);
        } else if (catalog.all.isEmpty()) {
            pageOneState.setText("لا توجد قنوات حالياً\nتحقق من الاتصال بالإنترنت ثم أعد فتح الصفحة");
            pageOneState.setVisibility(View.VISIBLE);
        } else {
            pageOneState.setVisibility(View.GONE);
        }
    }

    // ================================================================== navigation between the two pages

    private void openPackage(PkgItem it) {
        try {
            openPackageNow(it);
        } catch (Throwable error) {
            Log.w(TAG, "open package failed", error);
        }
    }

    private void openPackageNow(PkgItem it) {
        hideKeyboard();
        selKind = it.kind;
        selName = it.title;
        selByQuery = false;
        refreshChannels();
        switchPage(true);
        chList.setSelection(0);
        // bring the open package into view in the narrow list
        int at = indexOfSelected();
        if (at >= 0) narrowList.setSelection(Math.max(0, at - 1));
    }

    private void backToPackages() {
        try {
            backToPackagesNow();
        } catch (Throwable error) {
            Log.w(TAG, "back to packages failed", error);
        }
    }

    private void backToPackagesNow() {
        hideKeyboard();
        selKind = -1;
        selName = "";
        selByQuery = false;
        if (query.length() > 0) {
            query = "";
            input.setText("");
        }
        refreshChannels();
        switchPage(false);
    }

    private void onQueryChanged() {
        if (destroyed) return;
        try {
            queryNow();
        } catch (Throwable error) {
            Log.w(TAG, "search failed", error);
        }
    }

    private void queryNow() {
        String q = input.getText() == null ? "" : input.getText().toString().trim();
        if (q.equals(query)) return;
        query = q;
        if (selKind < 0) {
            if (q.length() > 0) {
                selByQuery = true;
                refreshChannels();
                switchPage(true);
            } else {
                refreshChannels();
            }
        } else if (q.length() == 0 && selByQuery) {
            selByQuery = false;
            refreshChannels();
            switchPage(false);
        } else {
            refreshChannels();
        }
        updateBack();
    }

    /** v10.67 (TV): the package shown when nothing is chosen: «كل القنوات», else the first one. */
    private PkgItem tvDefaultItem() {
        if (pkgItems.isEmpty()) return null;
        for (int i = 0; i < pkgItems.size(); i++) {
            if (pkgItems.get(i).kind == K_ALL) return pkgItems.get(i);
        }
        return pkgItems.get(0);
    }

    private int indexOfSelected() {
        for (int i = 0; i < pkgItems.size(); i++) {
            if (pkgItems.get(i).kind == selKind && pkgItems.get(i).title.equals(selName)) return i;
        }
        return -1;
    }

    /** The second page slides in from the left (the way "forward" goes in Arabic), the first page back. */
    private void switchPage(boolean second) {
        if (tv && !second) {
            // v10.67 (TV): packages | channels | picture stay on screen; «كل القنوات» instead of page one
            PkgItem it = tvDefaultItem();
            if (it != null) {
                selKind = it.kind;
                selName = it.title;
                selByQuery = false;
                refreshChannels();
                second = true;
            }
        }
        if (inSecondPage == second) {
            updateBack();
            return;
        }
        inSecondPage = second;
        final View in = second ? pageTwo : pageOne;
        final View out = second ? pageOne : pageTwo;
        try {
            if (in.getParent() == null) body.addView(in, new FrameLayout.LayoutParams(-1, -1));
            in.setAlpha(0f);
            in.setTranslationX((second ? -1 : 1) * dp(22));
            in.animate().alpha(1f).translationX(0f).setDuration(190L).start();
            out.animate().cancel();
            out.animate().alpha(0f).setDuration(110L).withEndAction(new Runnable() {
                @Override public void run() {
                    if (out.getParent() == body && out != (inSecondPage ? pageTwo : pageOne)) body.removeView(out);
                    out.setAlpha(1f);
                    out.setTranslationX(0f);
                }
            }).start();
        } catch (Throwable error) {
            if (out.getParent() == body) body.removeView(out);
            if (in.getParent() == null) body.addView(in, new FrameLayout.LayoutParams(-1, -1));
            in.setAlpha(1f);
            in.setTranslationX(0f);
        }
        updateBack();
    }

    // ================================================================== channels

    private void choose(CigramChannelStore.Ch ch) {
        if (ch == null || destroyed) return;
        if (ch.plus && !CigramPlusUI.requirePlus(a, "قنوات 1080p – 4K")) return;      // Cigram Plus tier
        try {
            hideKeyboard();
            activeKey = ch.key;
            CigramChannelStore.pushRecent(a, ch.key);
            player.play(ch);
            showInfo(ch);
            chAdapter.notifyDataSetChanged();
            rebuildPackages();
        } catch (Throwable error) {
            Log.w(TAG, "choose failed", error);
        }
    }

    private void showInfo(CigramChannelStore.Ch ch) {
        infoName.setText(ch.name);
        infoSub.setText(ch.section + "  ·  بث مباشر");
        infoLogo.setVisibility(View.VISIBLE);
        infoLogo.show(ch.logoKey(), ch.name, true);
        infoHeart.set(CigramChannelStore.isFav(a, ch.key) ? CigramHubUi.HEART_FILL : CigramHubUi.HEART,
                CigramChannelStore.isFav(a, ch.key) ? RED : MUTED);
    }

    private void toggleFavourite(CigramChannelStore.Ch ch) {
        try {
            toggleFavouriteNow(ch);
        } catch (Throwable error) {
            Log.w(TAG, "favourite failed", error);
        }
    }

    private void toggleFavouriteNow(CigramChannelStore.Ch ch) {
        boolean now = CigramChannelStore.toggleFav(a, ch.key);
        try { root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); } catch (Throwable ignored) { }
        if (ch.key.equals(activeKey)) showInfo(ch);
        rebuildPackages();
        if (selKind == K_FAV && !now) {
            refreshChannels();   // it left the list that is open
            if (selKind == K_FAV && shown.isEmpty()) backToPackages();
        } else {
            chAdapter.notifyDataSetChanged();
        }
    }

    @Override public void onStep(int delta) {
        if (destroyed) return;
        try {
            stepNow(delta);
        } catch (Throwable error) {
            Log.w(TAG, "next/previous failed", error);
        }
    }

    private void stepNow(int delta) {
        ArrayList<CigramChannelStore.Ch> list = shown.isEmpty() && catalog != null ? catalog.all : shown;
        int n = list.size();
        if (n == 0) return;
        int at = -1;
        for (int i = 0; i < n; i++) {
            if (list.get(i).key.equals(activeKey)) { at = i; break; }
        }
        for (int step = 1; step <= n; step++) {
            int i = ((at < 0 ? (delta > 0 ? -1 : 0) : at) + delta * step) % n;
            if (i < 0) i += n;
            CigramChannelStore.Ch ch = list.get(i);
            if (!ch.available) continue;
            choose(ch);
            if (list == shown) chList.smoothScrollToPosition(i);
            return;
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
        } catch (Throwable error) {
            wentFull = false;
            Log.w(TAG, "full screen failed", error);
            player.resume();
            Toast.makeText(a, "تعذر فتح القناة", Toast.LENGTH_SHORT).show();
        }
    }

    // ================================================================== adapters

    private abstract class Holder {
        View row;
    }

    private final class PkgHolder extends Holder {
        TextView name;
        TextView count;
        CigramHubUi.LogoView logo;
        CigramHubUi.Icon chevron;
        android.graphics.drawable.Drawable normalBg;
        android.graphics.drawable.Drawable selectedBg;
        PkgItem item;
    }

    private final class PkgAdapter extends BaseAdapter {
        private final boolean narrow;

        PkgAdapter(boolean narrow) {
            this.narrow = narrow;
        }

        @Override public int getCount() { return pkgItems.size(); }
        @Override public Object getItem(int i) { return pkgItems.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            PkgHolder h;
            if (convert == null) {
                h = narrow ? buildNarrow() : buildWide();
                h.row.setTag(h);
            } else {
                h = (PkgHolder) convert.getTag();
            }
            try {
                PkgItem it = pkgItems.get(position);
                h.item = it;
                h.name.setText(it.title);
                bindLogo(h, it);
                boolean sel = it.kind == selKind && it.title.equals(selName);
                if (narrow) {
                    h.row.setBackground(sel ? h.selectedBg : h.normalBg);
                    h.name.setTextColor(CigramTheme.adaptText(a, sel ? TEXT : 0xFFBDBDC2));
                } else {
                    h.count.setText(it.count + " قناة");
                }
            } catch (Throwable error) {
                Log.w(TAG, "package row failed", error);
            }
            return h.row;
        }

        private void bindLogo(PkgHolder h, PkgItem it) {
            if (it.kind == K_FAV) {
                h.logo.showGlyph(CigramHubUi.HEART_FILL, RED);
            } else if (it.kind == K_RECENT) {
                h.logo.showGlyph(CigramHubUi.CLOCK, 0xFF7CC4D0);
            } else if (it.kind == K_ALL) {
                h.logo.showGlyph(CigramHubUi.GRID, 0xFFDCE6EA);
            } else {
                CigramChannelStore.Ch cover = it.pkg == null ? null : it.pkg.cover();
                String key = cover == null ? "" : cover.logoKey();
                h.logo.show(key, it.title, !flinging);
                if (!flinging || key.length() == 0 || CigramLogos.peek(key) != null) return;
                needsRebind = true;
            }
        }

        private PkgHolder buildWide() {
            final PkgHolder h = new PkgHolder();
            LinearLayout r = new LinearLayout(a);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(14), 0, dp(12), 0);
            r.setBackground(ripple(CigramUI.round(CARD, STROKE, 18, a), 18));
            r.setLayoutParams(new AbsListView.LayoutParams(-1, dp(66)));
            h.chevron = new CigramHubUi.Icon(a, CigramHubUi.CHEV_LEFT, MUTED);
            r.addView(h.chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));
            h.count = text("", 11.5f, MUTED, false);
            h.count.setIncludeFontPadding(false);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.leftMargin = dp(8);
            r.addView(h.count, cl);
            h.name = text("", 15.5f, TEXT, true);
            h.name.setSingleLine(true);
            h.name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1f);
            nl.leftMargin = dp(8);
            nl.rightMargin = dp(12);
            r.addView(h.name, nl);
            h.logo = new CigramHubUi.LogoView(a, true, 0f);
            r.addView(h.logo, new LinearLayout.LayoutParams(dp(46), dp(46)));
            h.row = r;
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { if (h.item != null) openPackage(h.item); }
            });
            return h;
        }

        private PkgHolder buildNarrow() {
            final PkgHolder h = new PkgHolder();
            LinearLayout r = new LinearLayout(a);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(8), 0, dp(8), 0);
            h.normalBg = ripple(null, 16, true);
            h.selectedBg = ripple(roundStroke(0x33E21B14, RED, 16), 16, false);
            r.setBackground(h.normalBg);
            r.setLayoutParams(new AbsListView.LayoutParams(-1, dp(54)));
            h.name = text("", 12.5f, 0xFFBDBDC2, true);
            h.name.setMaxLines(2);
            h.name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1f);
            nl.rightMargin = dp(8);
            r.addView(h.name, nl);
            h.logo = new CigramHubUi.LogoView(a, true, 0f);
            r.addView(h.logo, new LinearLayout.LayoutParams(dp(36), dp(36)));
            h.row = r;
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (h.item == null) return;
                    if (h.item.kind == selKind && h.item.title.equals(selName)) return;
                    openPackage(h.item);
                }
            });
            return h;
        }
    }

    private final class ChHolder extends Holder {
        TextView name;
        TextView sub;
        CigramHubUi.LogoView logo;
        CigramHubUi.Icon icon;
        View slot;
        Drawable2 backgrounds;
        CigramChannelStore.Ch ch;
    }

    /** The two backgrounds of a channel row: normal and playing. */
    private static final class Drawable2 {
        android.graphics.drawable.Drawable normal;
        android.graphics.drawable.Drawable active;
    }

    private final class ChAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int position, View convert, ViewGroup parent) {
            ChHolder h;
            if (convert == null) {
                h = build();
                h.row.setTag(h);
            } else {
                h = (ChHolder) convert.getTag();
            }
            try {
                CigramChannelStore.Ch ch = shown.get(position);
                h.ch = ch;
                boolean active = ch.key.equals(activeKey);
                h.name.setText(ch.name);
                h.name.setTextColor(CigramTheme.adaptText(a, ch.available ? TEXT : 0xFF6F8591));
                boolean showSub = selKind == K_ALL || selKind == K_FAV || selKind == K_RECENT || selKind < 0;
                if (showSub) {
                    h.sub.setText(ch.available
                            ? (ch.plus ? "Plus · " + CigramQuality.label(ch.quality > 0 ? ch.quality : CigramChannelStore.PLUS_MIN_HEIGHT) + " · " + ch.section : ch.section)
                            : "غير متوفرة حالياً");
                    h.sub.setVisibility(View.VISIBLE);
                } else if (!ch.available) {
                    h.sub.setText("غير متوفرة حالياً");
                    h.sub.setVisibility(View.VISIBLE);
                } else {
                    h.sub.setVisibility(View.GONE);
                }
                h.sub.setTextColor(CigramTheme.adaptText(a, active ? 0xCCFFFFFF : MUTED));
                h.row.setBackground(active ? h.backgrounds.active : h.backgrounds.normal);
                h.logo.setTile(active ? 0x33FFFFFF : 0xFF26262B);
                String key = ch.logoKey();
                h.logo.show(key, ch.name, !flinging);
                if (flinging && key.length() > 0 && CigramLogos.peek(key) == null) needsRebind = true;
                if (active) {
                    h.icon.set(CigramHubUi.PLAY, TEXT);
                } else if (CigramChannelStore.isFav(a, ch.key)) {
                    h.icon.set(CigramHubUi.HEART_FILL, RED);
                } else {
                    h.icon.set(CigramHubUi.HEART, 0xFF5E7681);
                }
                h.row.setAlpha(ch.available ? 1f : 0.55f);
            } catch (Throwable error) {
                Log.w(TAG, "channel row failed", error);
            }
            return h.row;
        }

        private ChHolder build() {
            final ChHolder h = new ChHolder();
            LinearLayout r = new LinearLayout(a);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(2), 0, dp(8), 0);
            h.backgrounds = new Drawable2();
            h.backgrounds.normal = ripple(null, 16, true);
            h.backgrounds.active = ripple(CigramUI.round(RED, 16, a), 16, false);
            r.setBackground(h.backgrounds.normal);
            r.setLayoutParams(new AbsListView.LayoutParams(-1, dp(62)));

            FrameLayout slot = new FrameLayout(a);
            slot.setClickable(true);
            slot.setContentDescription("المفضلة");
            h.icon = new CigramHubUi.Icon(a, CigramHubUi.HEART, MUTED);
            slot.addView(h.icon, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
            slot.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (h.ch == null) return;
                    if (h.ch.key.equals(activeKey)) return;   // the play mark; the heart under the player does this one
                    toggleFavourite(h.ch);
                }
            });
            h.slot = slot;
            r.addView(slot, new LinearLayout.LayoutParams(dp(38), -1));

            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
            h.name = text("", 14f, TEXT, true);
            h.name.setMaxLines(2);
            h.name.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(h.name, new LinearLayout.LayoutParams(-1, -2));
            h.sub = text("", 10.5f, MUTED, false);
            h.sub.setSingleLine(true);
            h.sub.setEllipsize(TextUtils.TruncateAt.END);
            h.sub.setVisibility(View.GONE);
            col.addView(h.sub, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
            cl.leftMargin = dp(2);
            cl.rightMargin = dp(10);
            r.addView(col, cl);

            h.logo = new CigramHubUi.LogoView(a, false, dp(12));
            r.addView(h.logo, new LinearLayout.LayoutParams(dp(46), dp(46)));
            h.row = r;
            r.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        if (h.ch == null) return;
                        if (!h.ch.available) {
                            Toast.makeText(a, "القناة غير متوفرة حالياً", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        choose(h.ch);
                    } catch (Throwable error) {
                        Log.w(TAG, "row click failed", error);
                    }
                }
            });
            r.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    try {
                        if (h.ch == null) return false;
                        toggleFavourite(h.ch);
                        boolean fav = CigramChannelStore.isFav(a, h.ch.key);
                        Toast.makeText(a, fav ? "أُضيفت إلى المفضلة" : "أُزيلت من المفضلة", Toast.LENGTH_SHORT).show();
                        return true;
                    } catch (Throwable error) {
                        Log.w(TAG, "row long click failed", error);
                        return false;
                    }
                }
            });
            return h;
        }
    }

    private android.graphics.drawable.Drawable ripple(GradientDrawable content, float radiusDp) {
        return ripple(content, radiusDp, false);
    }

    private android.graphics.drawable.Drawable ripple(android.graphics.drawable.Drawable content, float radiusDp,
                                                      boolean maskOnly) {
        GradientDrawable mask = CigramUI.round(Color.WHITE, radiusDp, a);
        return new RippleDrawable(ColorStateList.valueOf(CigramTheme.adapt(a, 0x26FFFFFF)), content, mask);
    }

    private GradientDrawable roundStroke(int color, int stroke, float radiusDp) {
        GradientDrawable g = CigramUI.round(color, radiusDp, a);
        g.setStroke(dp(1), CigramTheme.adapt(a, stroke));
        return g;
    }

    // ================================================================== orientation / insets

    private void applyOrientation(boolean force) {
        if (destroyed || root == null) return;
        boolean land = !tv && a.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (land == landscape && !force) return;
        landscape = land;
        LinearLayout.LayoutParams wrap = (LinearLayout.LayoutParams) stageWrap.getLayoutParams();
        FrameLayout.LayoutParams st = (FrameLayout.LayoutParams) stage.getLayoutParams();
        if (land) {
            // cinema mode: the picture is the whole page
            wrap.height = 0;
            wrap.weight = 1f;
            st.height = -1;
            stageWrap.setPadding(0, 0, 0, 0);
            infoBar.setVisibility(View.GONE);
            searchRow.setVisibility(View.GONE);
            body.setVisibility(View.GONE);
            enterImmersive();
        } else {
            wrap.height = stageHeight + topInset;
            wrap.weight = 0f;
            st.height = stageHeight;
            stageWrap.setPadding(0, topInset, 0, 0);
            infoBar.setVisibility(View.VISIBLE);
            searchRow.setVisibility(View.VISIBLE);
            body.setVisibility(View.VISIBLE);
            leaveImmersive();
        }
        stageWrap.setLayoutParams(wrap);
        stage.setLayoutParams(st);
        player.setCompact(land);
        updateNavBar();
        int pad = land ? 0 : navPad;
        padList(wideList, pad);
        padList(narrowList, pad);
        padList(chList, pad);
    }

    private void padList(ListView list, int bottom) {
        if (list.getPaddingBottom() != bottom) {
            list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), bottom);
        }
    }

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
        } catch (Throwable ignored) { }
    }

    private void leaveImmersive() {
        try {
            if (!immersive) return;
            immersive = false;
            a.getWindow().getDecorView().setSystemUiVisibility(uiBefore);
        } catch (Throwable ignored) { }
    }

    /** The bottom bar belongs to the portrait page only. */
    private void updateNavBar() {
        if (tv) return;   // v10.66: a TV has the side rail, the bar stays hidden
        try {
            View parent = (View) root.getParent();
            View bar = parent == null ? null : parent.findViewWithTag(BAR_TAG);
            if (bar == null) return;
            if (landscape) {
                if (bar.getVisibility() != View.GONE) {
                    bar.setVisibility(View.GONE);
                    barHidden = true;
                }
            } else if (barHidden) {
                bar.setVisibility(View.VISIBLE);
                barHidden = false;
            }
        } catch (Throwable ignored) { }
    }

    private int navigationBarHeight() {
        try {
            int id = a.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) return a.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) { }
        return 0;
    }

    // ================================================================== back key, keyboard

    private void setupBack() {
        try {
            if (!(a instanceof androidx.activity.ComponentActivity)) return;
            androidx.activity.OnBackPressedCallback cb = new androidx.activity.OnBackPressedCallback(false) {
                @Override public void handleOnBackPressed() {
                    backToPackages();
                }
            };
            ((androidx.activity.ComponentActivity) a).getOnBackPressedDispatcher().addCallback(cb);
            backCallback = cb;
        } catch (Throwable error) {
            Log.w(TAG, "back setup failed", error);
        }
    }

    /** Back goes from the second page to the first; on the first page it leaves as usual. */
    private void updateBack() {
        try {
            if (backCallback instanceof androidx.activity.OnBackPressedCallback) {
                ((androidx.activity.OnBackPressedCallback) backCallback).setEnabled(inSecondPage && !tv);   // v10.67: TV Back leaves the page
            }
        } catch (Throwable ignored) { }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && input != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            if (input != null && input.hasFocus()) input.clearFocus();
        } catch (Throwable ignored) { }
    }

    // ================================================================== lifecycle

    private void onStarted() {
        if (destroyed) return;
        try {
            startedNow();
        } catch (Throwable error) {
            Log.w(TAG, "start failed", error);
        }
    }

    private void startedNow() {
        applyOrientation(true);
        if (wentFull) {
            wentFull = false;
            // the full player marks the channel it ended on as the most recent one
            ArrayList<String> recent = CigramChannelStore.recent(a);
            if (!recent.isEmpty() && catalog != null && !recent.get(0).equals(activeKey)) {
                CigramChannelStore.Ch ch = catalog.byKey.get(recent.get(0));
                if (ch != null) {
                    choose(ch);
                    return;
                }
            }
        }
        player.resume();
        if (catalog != null) rebuildPackages();
    }

    private void onStopped() {
        try {
            player.suspend();
        } catch (Throwable error) {
            Log.w(TAG, "stop failed", error);
        }
    }

    private void onDestroyed(boolean changingConfig) {
        destroyed = true;
        try {
            UI.removeCallbacks(applyQuery);
            CigramChannelStore.unlisten(this);
            if (changingConfig) {
                stickyAt = SystemClock.uptimeMillis();
                stickyKind = selKind;
                stickyPkg = selName;
                stickyChannel = player.isActive() && player.current() != null ? player.current().key : "";
                stickyQuery = query;
            }
        } catch (Throwable error) {
            Log.w(TAG, "destroy bookkeeping failed", error);
        }
        try {
            player.destroy();
        } catch (Throwable error) {
            Log.w(TAG, "player release failed", error);
        }
        leaveImmersive();
    }

    private void restoreSticky() {
        if (stickyAt == 0L || SystemClock.uptimeMillis() - stickyAt > 5000L) {
            stickyAt = 0L;
            return;
        }
        stickyAt = 0L;
        selKind = stickyKind;
        selName = stickyPkg;
        query = stickyQuery;
        stickyChannelPending = stickyChannel;
        if (query.length() > 0) input.setText(query);
        if (selKind >= 0 || query.length() > 0) {
            selByQuery = selKind < 0;
            switchPage(true);
        }
    }

    private static void registerLifecycle(Activity a) {
        if (lifecycleRegistered) return;
        try {
            ((Application) a.getApplicationContext()).registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        @Override public void onActivityCreated(Activity x, Bundle b) { }
                        @Override public void onActivityStarted(Activity x) {
                            try {
                                CigramChannelHub h = hub(x);
                                if (h != null) h.onStarted();
                            } catch (Throwable ignored) { }
                        }
                        @Override public void onActivityResumed(Activity x) { }
                        @Override public void onActivityPaused(Activity x) { }
                        @Override public void onActivityStopped(Activity x) {
                            try {
                                CigramChannelHub h = hub(x);
                                if (h != null) h.onStopped();
                            } catch (Throwable ignored) { }
                        }
                        @Override public void onActivitySaveInstanceState(Activity x, Bundle b) { }
                        @Override public void onActivityDestroyed(Activity x) {
                            CigramChannelHub h;
                            synchronized (HUBS) { h = HUBS.remove(x); }
                            if (h == null) return;
                            boolean changing = false;
                            try { changing = x.isChangingConfigurations(); } catch (Throwable ignored) { }
                            try {
                                h.onDestroyed(changing);
                            } catch (Throwable ignored) { }
                        }
                    });
            lifecycleRegistered = true;
        } catch (Throwable error) {
            Log.w(TAG, "lifecycle failed", error);
        }
    }

    private static CigramChannelHub hub(Activity x) {
        synchronized (HUBS) { return HUBS.get(x); }
    }
}
