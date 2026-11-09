package com.Cigram.vid;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;

import java.util.WeakHashMap;

/**
 * v10.66 — the TV layer of each page (TV only; called through CigramTvMode, which checks the TV).
 *
 *  - every page: the remote (CigramTvFocus) and the side rail state;
 *  - home: its top bar (logo · name · bell) is hidden, the rail already shows them;
 *  - search: while nothing is typed the search bar sits in the middle of the screen, narrower;
 *    as soon as something is typed it glides to the top and the results take the page.
 */
final class CigramTvUi {

    private static final WeakHashMap<Activity, Boolean> SEARCH_DONE = new WeakHashMap<Activity, Boolean>();

    private CigramTvUi() { }

    static void bound(Activity a) {
        if (a == null || a.isFinishing()) return;
        try {
            CigramTvFocus.attach(a);
            CigramTvRail.install(a);
            String n = a.getClass().getSimpleName();
            if ("MainActivity".equals(n)) {
                View header = byId(a, "linear7");
                if (header != null && header.getVisibility() != View.GONE) header.setVisibility(View.GONE);
                homeBillboard(a);
            } else if ("SearchXmlActivity".equals(n)) {
                setupSearch(a);
            } else if ("AccountActivity".equals(n)) {
                CigramAccountHeader.install(a);   // the profile panel beside the menu
            }
            CigramTvRail.refresh(a);
            fitWidths(a);
            CigramTvFocus.requestScan(a);
        } catch (Throwable error) {
            android.util.Log.w("CigramTvUi", "bound failed", error);
        }
    }

    /**
     * Rows that were told "at least as wide as the screen" (the home rows, the search strip) are
     * made as wide as the page instead: the rail takes the rest, so they would scroll into nothing.
     */
    private static void fitWidths(final Activity a) {
        if (CigramTvRail.pageIndex(a) < 0) return;
        final View content = a.findViewById(android.R.id.content);
        if (content == null) return;
        Runnable pass = new Runnable() {
            @Override public void run() {
                try {
                    if (a.isFinishing()) return;
                    int screen = a.getResources().getDisplayMetrics().widthPixels;
                    int page = screen - CigramTvRail.railWidth(a) - CigramTvRail.lineWidth(a);
                    if (page <= 0) return;
                    fit(content, screen, page);
                    alignRowsRight(content);
                } catch (Throwable ignored) { }
            }
        };
        pass.run();
        content.postDelayed(pass, 1500L);   // the home sections arrive with the catalogue
        content.postDelayed(pass, 4000L);   // slow boxes: the rows are filled a little later
    }

    private static final WeakHashMap<View, Boolean> ROW_AT_START = new WeakHashMap<View, Boolean>();

    /**
     * RTL on a TV: the app's rows are laid out left-to-right by hand (the phone shows the newest card at the
     * right edge). On a TV the rows were left-aligned and a short row left an empty right side. Every
     * horizontal row is aligned to the RIGHT edge, and a long row is opened at its right end (its first
     * card) once, the first time it has content. Rows the user already moved are left alone.
     */
    private static void alignRowsRight(View v) {
        if (v == null) return;
        if (v instanceof android.widget.HorizontalScrollView) {
            android.widget.HorizontalScrollView hs = (android.widget.HorizontalScrollView) v;
            if (hs.getChildCount() == 1 && hs.getWidth() > 0) {
                View c = hs.getChildAt(0);
                ViewGroup.LayoutParams lp = c.getLayoutParams();
                if (lp instanceof android.widget.FrameLayout.LayoutParams) {
                    android.widget.FrameLayout.LayoutParams f = (android.widget.FrameLayout.LayoutParams) lp;
                    int want = android.view.Gravity.RIGHT | (f.gravity > 0 ? (f.gravity & android.view.Gravity.VERTICAL_GRAVITY_MASK) : 0);
                    if (f.gravity != want) {
                        f.gravity = want;
                        c.setLayoutParams(f);
                    }
                }
                int range = c.getWidth() - hs.getWidth();
                synchronized (ROW_AT_START) {
                    if (range > 1 && hs.getScrollX() == 0 && !ROW_AT_START.containsKey(hs)) {
                        ROW_AT_START.put(hs, Boolean.TRUE);
                        hs.scrollTo(range, 0);
                    }
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) alignRowsRight(g.getChildAt(i));
        }
    }

    private static void fit(View v, int screen, int page) {
        if (v == null) return;
        int min = v.getMinimumWidth();
        if (min > page && min <= screen) v.setMinimumWidth(Math.max(0, min - (screen - page)));
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) fit(g.getChildAt(i), screen, page);
        }
    }

    static void resumed(Activity a) {
        try {
            CigramTvFocus.attach(a);
            CigramTvFocus.resumed(a);
            CigramTvRail.refresh(a);
        } catch (Throwable ignored) { }
    }

    static void paused(Activity a) {
        try {
            CigramTvFocus.paused(a);
        } catch (Throwable ignored) { }
    }

    private static View byId(Activity a, String name) {
        try {
            int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
            return id == 0 ? null : a.findViewById(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ================================================================== home

    private static final WeakHashMap<View, Boolean> BILLBOARDS = new WeakHashMap<View, Boolean>();

    /**
     * The home carousel on a TV: one wide card the width of the page (a billboard), about half the
     * screen high, the next card fading in over it. The cards are the same views (same pictures,
     * titles and clicks), only their size and motion change. Cards that arrive later are sized too.
     */
    private static void homeBillboard(final Activity a) {
        View content = a.findViewById(android.R.id.content);
        View holder = content == null ? null : content.findViewWithTag("cigram_admin_hero_carousel");
        if (!(holder instanceof ViewGroup)) return;
        ViewGroup h = (ViewGroup) holder;
        CigramCarousel.Pager pager = null;
        for (int i = 0; i < h.getChildCount(); i++) {
            if (h.getChildAt(i) instanceof CigramCarousel.Pager) pager = (CigramCarousel.Pager) h.getChildAt(i);
        }
        if (pager == null) return;
        final int cardH = Math.round(a.getResources().getDisplayMetrics().heightPixels * 0.46f);
        final int side = CigramUI.dp(a, 16);
        ViewGroup.LayoutParams plp = pager.getLayoutParams();
        if (plp != null && plp.height != cardH + CigramUI.dp(a, 8)) {
            plp.height = cardH + CigramUI.dp(a, 8);
            pager.setLayoutParams(plp);
        }
        for (int i = 0; i < pager.getChildCount(); i++) sizeCard(pager.getChildAt(i), cardH, side);
        synchronized (BILLBOARDS) {
            if (!BILLBOARDS.containsKey(pager)) {
                BILLBOARDS.put(pager, Boolean.TRUE);
                pager.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                    @Override public void onChildViewAdded(View parent, View child) { sizeCard(child, cardH, side); }
                    @Override public void onChildViewRemoved(View parent, View child) { }
                });
            }
        }
        pager.setTvBillboard(true);
    }

    private static void sizeCard(View card, int height, int side) {
        try {
            if (card == null || !(card.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams)) return;
            android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) card.getLayoutParams();
            if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT && lp.height == height
                    && lp.leftMargin == side && lp.rightMargin == side) return;
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = height;
            lp.leftMargin = side;
            lp.rightMargin = side;
            lp.gravity = android.view.Gravity.CENTER;
            card.setLayoutParams(lp);
        } catch (Throwable ignored) { }
    }

    // ================================================================== search

    private static void setupSearch(final Activity a) {
        synchronized (SEARCH_DONE) {
            if (SEARCH_DONE.containsKey(a)) return;
        }
        final View bar = byId(a, "linear_search_top");
        View in = byId(a, "edittext_search");
        final View page = byId(a, "vscroll1");
        if (bar == null || !(in instanceof EditText) || page == null) return;
        if (!(bar.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        synchronized (SEARCH_DONE) { SEARCH_DONE.put(a, Boolean.TRUE); }
        final EditText input = (EditText) in;
        final SearchLift lift = new SearchLift(a, bar, input, page);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { lift.update(true); }
        });
        page.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (b - t != ob - ot || r - l != or - ol) lift.update(false);
            }
        });
        lift.update(false);
        CigramTvFocus.setDefaultFocus(a, input);
    }

    /** Moves the search bar between the middle of the page (empty) and its place at the top. */
    private static final class SearchLift {
        final Activity a;
        final View bar;
        final EditText input;
        final View page;
        final int top0;
        final int left0;
        final int right0;
        ValueAnimator anim;
        boolean centred;
        boolean first = true;

        SearchLift(Activity a, View bar, EditText input, View page) {
            this.a = a;
            this.bar = bar;
            this.input = input;
            this.page = page;
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) bar.getLayoutParams();
            top0 = m.topMargin;
            left0 = m.leftMargin;
            right0 = m.rightMargin;
        }

        void update(boolean animate) {
            try {
                if (a.isFinishing()) return;
                boolean empty = input.getText() == null || input.getText().toString().trim().length() == 0;
                int pageH = page.getHeight();
                int pageW = page.getWidth();
                if (pageH <= 0 || pageW <= 0) {
                    page.post(new Runnable() { @Override public void run() { update(false); } });
                    return;
                }
                int barH = bar.getHeight() > 0 ? bar.getHeight() : CigramUI.dp(a, 56);
                int top = top0;
                int side = 0;
                if (empty) {
                    // a little above the middle: the hint and the recent searches sit under it
                    top = Math.max(top0, pageH / 2 - barH / 2 - CigramUI.dp(a, 36));
                    int width = Math.min(pageW - CigramUI.dp(a, 48), CigramUI.dp(a, 440));
                    side = Math.max(0, (pageW - width) / 2);
                }
                final int toTop = top;
                final int toL = empty ? side : left0;
                final int toR = empty ? side : right0;
                if (empty == centred && !first && anim == null) {
                    apply(toTop, toL, toR);   // same state, the page size changed
                    return;
                }
                centred = empty;
                boolean glide = animate && !first;
                first = false;
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) bar.getLayoutParams();
                final int fromTop = m.topMargin;
                final int fromL = m.leftMargin;
                final int fromR = m.rightMargin;
                if (anim != null) {
                    anim.cancel();
                    anim = null;
                }
                if (!glide) {
                    apply(toTop, toL, toR);
                    return;
                }
                final ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
                va.setDuration(260L);
                va.setInterpolator(new DecelerateInterpolator(1.6f));
                va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(ValueAnimator v) {
                        float f = (Float) v.getAnimatedValue();
                        apply(Math.round(fromTop + (toTop - fromTop) * f),
                                Math.round(fromL + (toL - fromL) * f),
                                Math.round(fromR + (toR - fromR) * f));
                    }
                });
                va.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator x) {
                        if (anim == va) anim = null;
                    }
                    @Override public void onAnimationCancel(android.animation.Animator x) {
                        if (anim == va) anim = null;
                    }
                });
                anim = va;
                va.start();
            } catch (Throwable ignored) { }
        }

        void apply(int top, int left, int right) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) bar.getLayoutParams();
            if (m.topMargin == top && m.leftMargin == left && m.rightMargin == right) return;
            m.topMargin = top;
            m.leftMargin = left;
            m.rightMargin = right;
            bar.setLayoutParams(m);
        }
    }
}
