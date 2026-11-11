package com.Cigram.vid;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.AbsListView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

public final class CigramBottomNav {
    private static final String TAG = "CigramBottomNav";
    /** Room kept under the page content for the floating bar (56dp bar + margins). */
    private static final int NAV_SPACE_DP = 58;
    /** v10.40 expanding pill: the open tab is a red capsule with its name; the others are icons. */
    private static final String BAR_TAG = "transparent_cigram_nav";
    /**
     * FLAT = the bottom bar of Meta's apps (Instagram / Facebook): a plain full-width bar with a thin
     * top line, equal icon-only buttons, the open one drawn filled and fully bright, the others
     * outlined and dimmer. No floating pill, no capsule, no labels, no blur, no slide.
     */
    private static final boolean FLAT = true;
    private static final int BAR_HEIGHT_DP = 50;
    private static final int BAR_SIDE_DP = 0;
    private static final int TAB_HEIGHT_DP = 50;
    private static final int RED = 0xFFE21B14;
    private static final int BAR_FILL = 0xFF0E0E10;      // the page colour: the bar and the area under it are one
    private static final int BAR_LINE = 0x33FFFFFF;      // hairline on top, like Instagram
    private static final String LINE_TAG = "cigram_flat_nav_line";
    /** Width difference of the open tab (0 = all tabs equal). */
    private static final float EXTRA = 0f;
    private static final long SLIDE_MS = 300L;
    private static final int HOME_INDEX = 3;
    private static final String CAPSULE_TAG = "cigram_pill_capsule";
    /** Tabs left to right, same order as the page Logic: الحساب، البحث، القنوات، الرئيسية. */
    private static final String[] SCREENS = {
            "AccountActivity", "SearchXmlActivity", "ChannelsActivity", "MainActivity"};
    private static final Map<Activity, Boolean> BACK_HOOKED = new WeakHashMap<Activity, Boolean>();
    /** A tab switch in flight: the new page continues the capsule slide the old page started. */
    private static int pendingFrom = -1;
    private static int pendingTo = -1;
    private static long pendingAt;
    private static long lastTapAt;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Map<View, Padding> ORIGINAL_PADDING = new WeakHashMap<View, Padding>();

    private CigramBottomNav() {
    }

    /**
     * Height of the strip the tab bar occupies at the bottom of the page (bar + the gap above the system bar), or 0 when
     * the page shows no bar. A full-page overlay (for example the offline / retry panel) leaves this strip free, so the
     * tabs stay usable instead of trapping the user on the page with only the Back button.
     */
    static int reservedBottom(Activity activity) {
        try {
            if (!hasBar(activity) || CigramTvMode.isTv(activity)) return 0;
            View root = activity.findViewById(android.R.id.content);
            View bar = root == null ? null : root.findViewWithTag(BAR_TAG);
            if (bar == null || bar.getVisibility() == View.GONE
                    || !(bar.getLayoutParams() instanceof FrameLayout.LayoutParams)) return 0;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
            return Math.max(0, lp.height) + Math.max(0, lp.bottomMargin);
        } catch (RuntimeException error) {
            Log.w(TAG, "reservedBottom failed", error);
            return 0;
        }
    }

    static boolean hasBar(Activity activity) {
        if (activity == null || activity.isFinishing()) return false;
        String name = activity.getClass().getSimpleName();
        return "MainActivity".equals(name)
                || "ChannelsActivity".equals(name)
                || "SearchXmlActivity".equals(name)
                || "AccountActivity".equals(name)
                || "DetailsActivity".equals(name);
    }

    /**
     * v10.47: the same floating bar on the film / series page. The tab pages get theirs from their
     * Logic; this page builds an equivalent one (icons + names, no tab open) on top of its content.
     * Pressing a tab opens that page and closes this one. Safe to call again and again.
     */
    public static void installOnDetails(final Activity activity) {
        try {
            if (activity == null || activity.isFinishing()
                    || !"DetailsActivity".equals(activity.getClass().getSimpleName())) return;
            if (CigramTvMode.isTv(activity)) {
                CigramTvRail.install(activity);   // v10.66: TV only — the side rail instead of the bar
                return;
            }
            View root = activity.findViewById(android.R.id.content);
            if (!(root instanceof FrameLayout)) return;
            FrameLayout content = (FrameLayout) root;
            if (content.findViewWithTag(BAR_TAG) != null) {
                bind(activity);
                return;
            }
            final FrameLayout bar = new FrameLayout(activity);
            bar.setTag(BAR_TAG);
            bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            bar.setClipToPadding(false);
            bar.setClipChildren(false);
            bar.setElevation(0f);

            LinearLayout tabs = new LinearLayout(activity);
            tabs.setOrientation(LinearLayout.HORIZONTAL);
            tabs.setGravity(Gravity.CENTER);
            tabs.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            bar.addView(tabs, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            String[] icons = {"nav_account", "nav_search", "nav_channels", "nav_home"};
            String[] names = {"الحساب", "البحث", "القنوات", "الرئيسية"};
            android.graphics.Typeface font = null;
            try { font = CigramStage1InitProvider.cigramFont(activity); } catch (Throwable ignored) { }
            for (int i = 0; i < icons.length; i++) {
                LinearLayout tab = new LinearLayout(activity);
                tab.setOrientation(LinearLayout.VERTICAL);
                tab.setGravity(Gravity.CENTER);
                tab.setContentDescription(names[i]);
                tab.setSelected(i == HOME_INDEX);   // the film page belongs to home: that tab is open
                tab.setClickable(true);
                tab.setFocusable(true);
                android.widget.ImageView icon = new android.widget.ImageView(activity);
                int id = activity.getResources().getIdentifier(icons[i], "drawable", activity.getPackageName());
                if (id != 0) icon.setImageResource(id);
                icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                icon.setAlpha(0.65f);
                tab.addView(icon, new LinearLayout.LayoutParams(dp(activity, 25), dp(activity, 25)));
                // v10.67: signed in → the saved photo, from the shared in-memory copy (first frame, same as the tab pages)
                if (i == 0) CigramAvatar.applyTo(icon, false, true);
                TextView label = new TextView(activity);
                label.setText(names[i]);
                label.setTextSize(12f);
                label.setTextColor(0xFFB0B0B5);
                label.setGravity(Gravity.CENTER);
                label.setIncludeFontPadding(false);
                label.setSingleLine(true);
                if (font != null) label.setTypeface(font);
                tab.addView(label, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                CigramNavFx.attachPress(tab);
                tabs.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }

            final int base = 0;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    activity.getResources().getDisplayMetrics().widthPixels - 2 * dp(activity, BAR_SIDE_DP),
                    dp(activity, BAR_HEIGHT_DP), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            // v10.67: the SAME height as the tab pages. Their Logic starts at 10dp and adds the real
            // bottom inset; this page used the nav-bar resource height (48dp even with gesture
            // navigation), so its bar sat higher than on the other pages.
            lp.bottomMargin = base + bottomInset(activity);
            content.addView(bar, lp);
            bar.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public android.view.WindowInsets onApplyWindowInsets(
                        View v, android.view.WindowInsets insets) {
                    syncBarBottom(bar, base, Math.max(0, insets.getSystemWindowInsetBottom()));
                    return insets;
                }
            });
            // a sibling may consume the insets before they reach the bar: follow the window as well
            final android.view.ViewTreeObserver.OnGlobalLayoutListener follow =
                    new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    syncBarBottom(bar, base, bottomInset(activity));
                }
            };
            bar.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {
                    try { v.getViewTreeObserver().addOnGlobalLayoutListener(follow); } catch (Throwable ignored) { }
                    v.requestApplyInsets();
                    syncBarBottom(bar, base, bottomInset(activity));
                }
                @Override public void onViewDetachedFromWindow(View v) {
                    try { v.getViewTreeObserver().removeOnGlobalLayoutListener(follow); } catch (Throwable ignored) { }
                }
            });
            bar.requestApplyInsets();
            bind(activity);
        } catch (Throwable error) {
            Log.w(TAG, "details bar failed", error);
        }
    }

    /**
     * v10.42 navigation. The bar looks like v10.40 (floating pill, the open tab is a red capsule with
     * its name) and now behaves like a persistent bar:
     *  - one red capsule SLIDES to the pressed tab while the tab widths flow with it;
     *  - a press opens the page at once (no delay) and the page that comes on top continues the
     *    slide from where it was, so the bar seems never to be rebuilt;
     *  - tab pages stay alive (brought to the front, not recreated), so coming back is instant and
     *    keeps its scroll position; the home page is always kept right under the open tab, so Back
     *    goes to the home page and Back there leaves the app.
     * The page Logic still builds the bar; this reshapes it in place. Called from onActivityStarted
     * (before the first frame) and from bind(); safe to call again and again.
     */
    public static void compact(Activity activity) {
        if (!hasBar(activity)) return;
        if (CigramTvMode.isTv(activity)) {
            // v10.66: TV only. The bottom bar is hidden and the side rail takes its place; Back on the
            // home page still leaves the app (after a first Back that brings the focus to the rail).
            try {
                CigramTvRail.install(activity);
                if (BACK_HOOKED.get(activity) == null && "MainActivity".equals(activity.getClass().getSimpleName())) {
                    BACK_HOOKED.put(activity, Boolean.TRUE);
                    hookHomeBack(activity);
                }
            } catch (Throwable error) {
                Log.w(TAG, "tv rail failed", error);
            }
            return;
        }
        try {
            View root = activity.findViewById(android.R.id.content);
            View bar = root == null ? null : root.findViewWithTag(BAR_TAG);
            if (bar == null || !(bar.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;

            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
            int screen = activity.getResources().getDisplayMetrics().widthPixels;
            int width = screen - 2 * dp(activity, BAR_SIDE_DP);
            int height = dp(activity, BAR_HEIGHT_DP);
            int gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            if (lp.width != width || lp.height != height || lp.gravity != gravity
                    || lp.leftMargin != 0 || lp.rightMargin != 0) {
                lp.width = width;
                lp.height = height;
                lp.gravity = gravity;
                lp.leftMargin = 0;
                lp.rightMargin = 0;
                bar.setLayoutParams(lp);
            }
            // Light mode: a light bar (white, with a thin dark hairline) and BLACK icons; Dark mode: unchanged
            int fill = CigramTheme.isLight(activity) ? 0xFFFFFFFF : BAR_FILL;
            if (!(bar.getBackground() instanceof android.graphics.drawable.ColorDrawable)
                    || ((android.graphics.drawable.ColorDrawable) bar.getBackground()).getColor() != fill) {
                bar.setBackground(new android.graphics.drawable.ColorDrawable(fill));   // flat, no blur / glass
                bar.setElevation(0f);
                bar.setClipToOutline(false);
            }
            if (!(bar instanceof FrameLayout)) return;
            syncFiller(activity, (FrameLayout) bar);
            FrameLayout group = (FrameLayout) bar;
            if (group.findViewWithTag(LINE_TAG) == null) {
                View line = new View(activity);
                line.setTag(LINE_TAG);
                line.setBackgroundColor(CigramTheme.isLight(activity) ? 0x1F000000 : BAR_LINE);
                group.addView(line, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(activity, 0.5f)), Gravity.TOP));
            }
            View lineView = group.findViewWithTag(LINE_TAG);
            if (lineView != null) lineView.setBackgroundColor(CigramTheme.isLight(activity) ? 0x1F000000 : BAR_LINE);
            LinearLayout tabs = null;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof LinearLayout) tabs = (LinearLayout) child;
                else if (!CAPSULE_TAG.equals(child.getTag()) && !LINE_TAG.equals(child.getTag())) {
                    child.setVisibility(View.GONE); // old glass capsule
                }
            }
            if (tabs == null || tabs.getChildCount() == 0) return;

            Pill pill = tabs.getTag() instanceof Pill ? (Pill) tabs.getTag() : null;
            if (pill == null) {
                pill = new Pill(activity, group, tabs, width);
                tabs.setTag(pill);   // lives and dies with the bar (a static map would keep the page alive)
            }
            pill.resize(width);
            int sel = pill.selectedIndex();
            boolean mismatch = !pill.laidOut || Math.abs(pill.pos - sel) > 0.001f;
            if (pendingTo == sel) {
                pendingFrom = -1;
                pendingTo = -1;
            }
            // v10.60: the capsule already slid on the page that was pressed (with the finger), so the
            // page that arrives just shows it at its own tab, before its first frame. No second slide:
            // the home page is busy for a moment when it comes back and that slide used to lag.
            // A page that was just left (still on screen under the opening page) keeps its capsule
            // on the pressed tab; it goes back to its own tab when that page is stopped.
            if (!pill.leaving && !pill.animating() && mismatch) {
                pill.layout(sel);
                if (sel >= 0) pill.visual.apply(sel, sel, 1f);
            }
            if (BACK_HOOKED.get(activity) == null && "MainActivity".equals(activity.getClass().getSimpleName())) {
                BACK_HOOKED.put(activity, Boolean.TRUE);
                hookHomeBack(activity);
            }
        } catch (Throwable error) {
            Log.w(TAG, "compact failed for " + activity.getClass().getSimpleName(), error);
        }
    }

    private static final long TOUCH_SLIDE_MS = 180L;
    private static final long OPEN_AFTER_MS = 160L;

    /**
     * v10.60 (like OTTO): the capsule moves on THIS page the moment the tab is pressed, and the new
     * page opens when it has (almost) landed. All pages share one UI thread, so a slide running while
     * the home page is brought back used to freeze; now it is done before that work starts.
     */
    private static void go(final Activity from, final Pill pill, int fromIndex, final int to, View tapped) {
        long now = SystemClock.uptimeMillis();
        if (now - lastTapAt < 220L) return;
        if (pill != null && pill.leaving) return;
        lastTapAt = now;
        CigramNavFx.tick(tapped);
        final boolean fromDetails = "DetailsActivity".equals(from.getClass().getSimpleName());
        pendingFrom = fromIndex;
        pendingTo = to;
        pendingAt = now;
        long wait = 0L;
        if (pill != null && fromIndex >= 0 && fromIndex != to) {
            pill.leaving = true;
            if (FLAT) {
                pill.layout(to);
                pill.visual.apply(to, to, 1f);   // the pressed icon fills at once; the page opens with no delay
            } else {
                pill.slide(fromIndex, to, TOUCH_SLIDE_MS);
                wait = OPEN_AFTER_MS;
            }
        }
        Runnable open = new Runnable() {
            @Override public void run() {
                if (from.isFinishing() || !open(from, to, fromDetails)) {
                    pendingFrom = -1;
                    pendingTo = -1;
                    if (pill != null) pill.comeBack();
                }
            }
        };
        if (wait > 0L) MAIN.postDelayed(open, wait);
        else open.run();
    }

    /**
     * v10.66: the TV side rail opens a page the same way the bar does (same intents, pages stay
     * alive, the film page closes). Returns false when nothing was opened.
     */
    static boolean openTabTv(Activity from, int to) {
        if (from == null || from.isFinishing() || to < 0 || to >= SCREENS.length) return false;
        long now = SystemClock.uptimeMillis();
        if (now - lastTapAt < 220L) return false;
        lastTapAt = now;
        return open(from, to, "DetailsActivity".equals(from.getClass().getSimpleName()));
    }

    private static boolean open(Activity from, int to, boolean fromDetails) {
        try {
            Intent target = tabIntent(from, to);
            if (target == null) return false;
            if (to != HOME_INDEX) {
                Intent home = tabIntent(from, HOME_INDEX);
                if (home != null) {
                    // home first, then the tab: the stack ends ... home, tab  (Back from the tab = home)
                    from.startActivities(new Intent[]{home, target});
                    if (CigramPerformance.get(from).animations) from.overridePendingTransition(R.anim.cg_fade_in, R.anim.cg_fade_out);   // v10.70: soft switch
                    else from.overridePendingTransition(0, 0);                                                  // 19.2: LITE profile switches instantly
                    if (fromDetails) from.finish();   // a film page is not kept under the tabs
                    return true;
                }
            }
            from.startActivity(target);
            from.overridePendingTransition(R.anim.cg_fade_in, R.anim.cg_fade_out);   // v10.70: soft switch
            if (fromDetails) from.finish();
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "tab switch failed", error);
            return false;
        }
    }

    /** The page is no longer on screen: put its capsule back on its own tab, ready for its return. */
    public static void stopped(Activity activity) {
        if (!hasBar(activity)) return;
        try {
            View root = activity.findViewById(android.R.id.content);
            View bar = root == null ? null : root.findViewWithTag(BAR_TAG);
            if (!(bar instanceof FrameLayout)) return;
            FrameLayout group = (FrameLayout) bar;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof LinearLayout && child.getTag() instanceof Pill) {
                    ((Pill) child.getTag()).comeBack();
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "stopped failed", error);
        }
    }

    /** The real bottom system inset: the number the tab pages' Logic adds under their bar. */
    private static int bottomInset(Activity activity) {
        return Math.max(0, rawBottomInset(activity));
    }

    /** -1 while the window has no insets yet (the page is not attached). */
    private static int rawBottomInset(Activity activity) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                android.view.WindowInsets wi = activity.getWindow().getDecorView().getRootWindowInsets();
                if (wi != null) return Math.max(0, wi.getSystemWindowInsetBottom());
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    private static void syncBarBottom(FrameLayout bar, int base, int inset) {
        try {
            if (!(bar.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) bar.getLayoutParams();
            int wanted = base + Math.max(0, inset);
            if (p.bottomMargin != wanted) {
                p.bottomMargin = wanted;
                bar.setLayoutParams(p);
            }
            if (bar.getContext() instanceof Activity) syncFiller((Activity) bar.getContext(), bar);
        } catch (Throwable ignored) { }
    }

    private static final String FILLER_TAG = "cigram_nav_filler";

    /**
     * Light mode: the strip under the bar (the gap the system bar leaves) is filled with the bar's colour, so the
     * bar and its gap read as one block. Dark mode: not needed (same colour as the page).
     */
    private static void syncFiller(Activity activity, FrameLayout bar) {
        try {
            if (!(bar.getParent() instanceof FrameLayout) || !(bar.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
            FrameLayout shell = (FrameLayout) bar.getParent();
            View filler = shell.findViewWithTag(FILLER_TAG);
            int gap = ((FrameLayout.LayoutParams) bar.getLayoutParams()).bottomMargin;
            boolean want = CigramTheme.isLight(activity) && gap > 0;
            if (!want) {
                if (filler != null) filler.setVisibility(View.GONE);
                return;
            }
            if (filler == null) {
                filler = new View(activity);
                filler.setTag(FILLER_TAG);
                filler.setBackgroundColor(0xFFFFFFFF);
                shell.addView(filler, Math.max(0, shell.indexOfChild(bar)),
                        new FrameLayout.LayoutParams(-1, gap, Gravity.BOTTOM));
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) filler.getLayoutParams();
            if (lp.height != gap) { lp.height = gap; filler.setLayoutParams(lp); }
            filler.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) { }
    }

    private static Intent tabIntent(Activity from, int index) {
        try {
            Class<?> cls = Class.forName(from.getPackageName() + "." + SCREENS[index]);
            Intent intent = new Intent(from, cls);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            intent.putExtra("cigram_nav_from", index);
            return intent;
        } catch (Throwable error) {
            return null;
        }
    }

    /**
     * Tab pages are kept alive under the home page, so Back on the home page has to leave the app
     * instead of showing one of them. Only the home page is touched; every other page keeps the
     * system's Back.
     */
    private static void hookHomeBack(final Activity activity) {
        try {
            final Window window = activity.getWindow();
            final Window.Callback original = window.getCallback();
            if (original == null) return;
            Window.Callback proxy = (Window.Callback) java.lang.reflect.Proxy.newProxyInstance(
                    Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class},
                    new java.lang.reflect.InvocationHandler() {
                        @Override public Object invoke(Object p, java.lang.reflect.Method m, Object[] args)
                                throws Throwable {
                            if ("dispatchKeyEvent".equals(m.getName()) && args != null && args.length == 1
                                    && args[0] instanceof KeyEvent) {
                                KeyEvent e = (KeyEvent) args[0];
                                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                                    // v10.66 (TV only): Back from the page content goes to the rail first
                                    if (CigramTvMode.isTv(activity) && CigramTvRail.has(activity)
                                            && !CigramTvRail.focusInRail(activity)) {
                                        if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
                                            CigramTvRail.focusSelected(activity);
                                        }
                                        return Boolean.TRUE;
                                    }
                                    if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()
                                            && !activity.isFinishing()) {
                                        activity.finishAffinity();
                                    }
                                    return Boolean.TRUE;
                                }
                            }
                            try {
                                return m.invoke(original, args);
                            } catch (java.lang.reflect.InvocationTargetException wrapped) {
                                throw wrapped.getCause() == null ? wrapped : wrapped.getCause();
                            }
                        }
                    });
            window.setCallback(proxy);
        } catch (Throwable error) {
            Log.w(TAG, "home back hook failed", error);
        }
    }

    /** One bar: the sliding red capsule and the flowing tab widths. */
    private static final class Pill {
        final Activity activity;
        final FrameLayout bar;
        final LinearLayout tabs;
        final View capsule;
        final int count;
        final LinearLayout[] tab;
        final TextView[] label;
        final float[] natural;
        final CigramNavFx.Visual visual;
        final int tabHeight;
        final int gap;
        final int labelGap;
        int barWidth;
        float pos = -1f;
        boolean laidOut;
        /** Pressed another tab: the capsule stays there until this page is stopped. */
        boolean leaving;
        long animUntil;
        ValueAnimator anim;

        Pill(Activity a, FrameLayout bar, LinearLayout tabs, int barWidth) {
            this.activity = a;
            this.bar = bar;
            this.tabs = tabs;
            this.barWidth = barWidth;
            count = tabs.getChildCount();
            tab = new LinearLayout[count];
            label = new TextView[count];
            natural = new float[count];
            tabHeight = dp(a, TAB_HEIGHT_DP);
            gap = FLAT ? 0 : dp(a, 2);
            labelGap = dp(a, 6);
            android.widget.ImageView[] icons = new android.widget.ImageView[count];
            int vMargin = (dp(a, BAR_HEIGHT_DP) - tabHeight) / 2;

            for (int i = 0; i < count; i++) {
                final int index = i;
                View v = tabs.getChildAt(i);
                if (!(v instanceof LinearLayout)) continue;
                LinearLayout t = (LinearLayout) v;
                tab[i] = t;
                t.setOrientation(LinearLayout.HORIZONTAL);
                t.setGravity(Gravity.CENTER);
                t.setBackgroundColor(Color.TRANSPARENT);
                if (t.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) t.getLayoutParams();
                    p.width = 0;
                    p.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    p.weight = 1f;
                    p.topMargin = vMargin;
                    p.bottomMargin = vMargin;
                    p.leftMargin = gap;
                    p.rightMargin = gap;
                    t.setLayoutParams(p);
                }
                TextView name = null;
                for (int c = 0; c < t.getChildCount(); c++) {
                    View child = t.getChildAt(c);
                    if (child instanceof TextView && name == null) name = (TextView) child;
                    else if (child instanceof android.widget.ImageView && icons[i] == null) {
                        icons[i] = (android.widget.ImageView) child;
                    }
                }
                label[i] = name;
                if (name != null && FLAT) {
                    name.setVisibility(View.GONE);   // icon-only, like Instagram
                    CharSequence text = name.getText();
                    if (text != null && text.length() > 0) t.setContentDescription(text);
                } else if (name != null) {
                    // right-to-left reading: the name sits to the LEFT of its icon (no supportsRtl)
                    if (t.indexOfChild(name) != 0) {
                        t.removeView(name);
                        t.addView(name, 0);
                    }
                    name.setTextSize(11.5f);
                    name.setSingleLine(true);
                    name.setEllipsize(null);
                    name.setIncludeFontPadding(false);
                    name.setGravity(Gravity.CENTER);
                    name.setTranslationY(-dp(a, 1.5f));   // the Arabic font sits a little low: centre it on the icon
                    natural[i] = name.getPaint().measureText(String.valueOf(name.getText())) + dp(a, 2);
                    CharSequence text = name.getText();
                    if (text != null && text.length() > 0) {
                        t.setContentDescription(text);
                        if (Build.VERSION.SDK_INT >= 26) t.setTooltipText(text);
                    }
                }
                t.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View clicked) {
                        onTab(index, clicked);
                    }
                });
            }

            View old = bar.findViewWithTag(CAPSULE_TAG);
            if (old != null) bar.removeView(old);
            capsule = new View(a);
            capsule.setTag(CAPSULE_TAG);
            GradientDrawable red = new GradientDrawable();
            red.setColor(RED);
            red.setCornerRadius(tabHeight / 2f);
            capsule.setBackground(red);
            bar.addView(capsule, 0, new FrameLayout.LayoutParams(
                    capsuleWidth(), tabHeight, Gravity.LEFT | Gravity.CENTER_VERTICAL));
            if (FLAT) capsule.setVisibility(View.GONE);
            visual = new CigramNavFx.Visual(icons, label, 0);
        }

        private float unit() {
            return (barWidth - count * 2f * gap) / (count + EXTRA);
        }

        private int capsuleWidth() {
            return Math.round(unit() * (1f + EXTRA));
        }

        void resize(int width) {
            if (width == barWidth) return;
            barWidth = width;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) capsule.getLayoutParams();
            lp.width = capsuleWidth();
            capsule.setLayoutParams(lp);
            layout(laidOut ? pos : selectedIndex());
        }

        /** The open tab, or -1 on a page that has none (the film page). */
        int selectedIndex() {
            for (int i = 0; i < count; i++) {
                if (tab[i] != null && tab[i].isSelected()) return i;
            }
            return -1;
        }

        void onTab(int index, View clicked) {
            int current = selectedIndex();
            boolean onFilmPage = "DetailsActivity".equals(activity.getClass().getSimpleName());
            if (index == current && !onFilmPage) {
                View shell = bar.getParent() instanceof ViewGroup ? (ViewGroup) bar.getParent() : null;
                View page = shell instanceof ViewGroup && ((ViewGroup) shell).getChildCount() > 0
                        ? ((ViewGroup) shell).getChildAt(0) : null;
                CigramNavFx.scrollToTop(page, clicked);   // pressing the open tab: back to the top
                return;
            }
            go(activity, this, current, index, clicked);
        }

        /** p = the tab index the capsule is at (fractional while it slides). */
        void layout(float p) {
            pos = p;
            laidOut = true;
            float[] w = new float[count];
            for (int i = 0; i < count; i++) {
                w[i] = 1f + EXTRA * Math.max(0f, 1f - Math.abs(i - p));
            }
            float unit = unit();
            float left = gap;
            float[] center = new float[count];
            for (int i = 0; i < count; i++) {
                center[i] = left + w[i] * unit / 2f;
                left += w[i] * unit + 2f * gap;
                if (tab[i] == null) continue;
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tab[i].getLayoutParams();
                if (Math.abs(lp.weight - w[i]) > 0.002f) {
                    lp.weight = w[i];
                    tab[i].setLayoutParams(lp);
                }
                if (label[i] != null && !FLAT) {
                    float open = Math.max(0f, Math.min(1f, (w[i] - 1f) / EXTRA));
                    // v10.53: the name shows only when the capsule has (almost) arrived. While it slides
                    // across tabs the names of the tabs in between used to show half-cut, outside the capsule.
                    float f = Math.max(0f, Math.min(1f, (open - 0.5f) / 0.5f));
                    LinearLayout.LayoutParams ll = (LinearLayout.LayoutParams) label[i].getLayoutParams();
                    int wanted = Math.round(natural[i] * f);
                    int margin = Math.round(labelGap * f);
                    if (ll.width != wanted || ll.rightMargin != margin) {
                        ll.width = wanted;
                        ll.rightMargin = margin;
                        label[i].setLayoutParams(ll);
                    }
                    label[i].setAlpha(f);
                    label[i].setVisibility(wanted == 0 ? View.GONE : View.VISIBLE);
                }
            }
            capsule.setVisibility(FLAT || p < -0.5f ? View.GONE : View.VISIBLE);
            int i0 = Math.max(0, Math.min(count - 1, (int) Math.floor(p)));
            int i1 = Math.min(count - 1, i0 + 1);
            float fr = Math.max(0f, Math.min(1f, p - i0));
            float c = center[i0] + (center[i1] - center[i0]) * fr;
            capsule.setTranslationX(c - capsuleWidth() / 2f);
        }

        /** Centre x (in the bar) of tab {@code index} when the capsule is at tab {@code p}. */
        private float centerOf(int index, float p) {
            float unit = unit();
            float left = gap;
            float centre = left;
            for (int i = 0; i < count; i++) {
                float w = 1f + EXTRA * Math.max(0f, 1f - Math.abs(i - p));
                if (i == index) centre = left + w * unit / 2f;
                left += w * unit + 2f * gap;
            }
            return centre;
        }

        boolean animating() { return anim != null || SystemClock.uptimeMillis() < animUntil; }

        /**
         * v10.60: widths, names and icons go to their final state at once and the capsule glides from
         * the old tab to the new one, starting on the touch. A safety timer puts it in its final place
         * whatever happens.
         */
        void slide(final int from, final int to, long ms) {
            cancel();
            layout(to);
            visual.apply(to, to, 1f);
            final float end = capsule.getTranslationX();
            final TextView name = to >= 0 ? label[to] : null;
            if (from < 0 || from == to || to < 0) return;
            final float start = Math.max(gap, Math.min(barWidth - gap - capsuleWidth(),
                    centerOf(from, to) - capsuleWidth() / 2f));
            capsule.setTranslationX(start);
            if (name != null) name.setAlpha(0f);
            animUntil = SystemClock.uptimeMillis() + ms + 400L;

            final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(ms);
            a.setInterpolator(new DecelerateInterpolator(1.6f));
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator va) {
                    float v = (Float) va.getAnimatedValue();
                    capsule.setTranslationX(start + (end - start) * v);
                    if (name != null) name.setAlpha(Math.max(0f, Math.min(1f, (v - 0.4f) / 0.6f)));
                }
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(android.animation.Animator x) { cancelled = true; }
                @Override public void onAnimationEnd(android.animation.Animator x) {
                    if (anim == a) anim = null;
                    if (cancelled) return;
                    capsule.setTranslationX(end);
                    if (name != null) name.setAlpha(1f);
                    animUntil = 0L;
                }
            });
            anim = a;
            a.start();
            // safety: whatever happened (busy thread), end up in the right place
            MAIN.postDelayed(new Runnable() {
                @Override public void run() {
                    if (anim != null && anim != a) return;   // a newer slide owns the capsule
                    if (Math.abs(pos - to) > 0.001f) return;
                    if (anim == a) { anim = null; a.cancel(); }
                    capsule.setTranslationX(end);
                    if (name != null) name.setAlpha(1f);
                    animUntil = 0L;
                }
            }, ms + 250L);
        }

        /** Back to this page's own tab, at once (the page is hidden, or the switch failed). */
        void comeBack() {
            leaving = false;
            cancel();
            int sel = selectedIndex();
            layout(sel);
            if (sel >= 0) visual.apply(sel, sel, 1f);
        }

        void cancel() {
            animUntil = 0L;
            if (anim != null) {
                ValueAnimator a = anim;
                anim = null;
                a.cancel();
            }
            capsule.animate().cancel();
            for (TextView t : label) if (t != null) { t.animate().cancel(); t.setAlpha(1f); }
        }
    }

    public static void bind(Activity activity) {
        if (!hasBar(activity)) return;
        String name = activity.getClass().getSimpleName();
        compact(activity);
        if ("MainActivity".equals(name)) {
            // v10.63: the channels tab opens at once — its list is parsed here, while home is idle
            try { CigramChannelStore.prewarm(activity); } catch (Throwable ignored) { }
        }
        try {
            View root = activity.findViewById(android.R.id.content);
            // before the window has insets (first call) keep room for the biggest navigation bar
            int bottomInset = rawBottomInset(activity);
            if (bottomInset < 0) bottomInset = navigationBarHeight(activity);
            // v10.66: a TV has no bottom bar (the side rail), only a small breathing space at the end
            int extra = CigramTvMode.isTv(activity) ? dp(activity, 24) : dp(activity, NAV_SPACE_DP) + bottomInset;
            applyToScrollableChildren(root, extra);
        } catch (Throwable error) {
            Log.w(TAG, "bind failed for " + name, error);
        }
    }

    private static void applyToScrollableChildren(View view, int extraBottom) {
        if (view == null) return;
        // v10.63: the channels page pads its own lists (it also removes the bar in landscape)
        if (isScrollable(view) && !"cigram_nav_managed".equals(view.getTag())) {
            Padding p = ORIGINAL_PADDING.get(view);
            if (p == null) {
                p = new Padding(view.getPaddingLeft(), view.getPaddingTop(),
                        view.getPaddingRight(), view.getPaddingBottom());
                ORIGINAL_PADDING.put(view, p);
            }
            int wanted = p.bottom + extraBottom;
            if (view.getPaddingBottom() != wanted) {
                view.setPadding(p.left, p.top, p.right, wanted);
            }
            if (view instanceof ViewGroup) {
                ((ViewGroup) view).setClipToPadding(false);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyToScrollableChildren(group.getChildAt(i), extraBottom);
            }
        }
    }

    private static boolean isScrollable(View view) {
        if (view instanceof ScrollView || view instanceof AbsListView) return true;
        String name = view.getClass().getName();
        return name.contains("NestedScrollView") || name.contains("RecyclerView");
    }

    private static int navigationBarHeight(Context context) {
        try {
            int id = context.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id > 0) return context.getResources().getDimensionPixelSize(id);
        } catch (Throwable error) {
            Log.w(TAG, "navigationBarHeight failed", error);
        }
        return 0;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class Padding {
        final int left;
        final int top;
        final int right;
        final int bottom;
        Padding(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }
}
