package com.Cigram.vid;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.StateSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * v10.66 — the side rail of the TV (TV only: on a phone the floating bottom bar stays exactly as
 * it is; nothing here runs there).
 *
 *   (RTL since v1.6.8: the rail is on the RIGHT edge, the page fills the rest to its left)
 *   ┌────────────────┬──────────────────────────────────────┐
 *   │ (logo) سيكرام 🔔 │                                      │
 *   │                │                                      │
 *   │ ⌂  الرئيسية     │        the page, from this line      │
 *   │ ▢  القنوات      │        to the right edge             │
 *   │ ⌕  البحث        │                                      │
 *   │ ◯  الحساب       │                                      │
 *   └────────────────┴──────────────────────────────────────┘
 *
 * The rail goes into the page's own shell (the FrameLayout the page Logic builds around the
 * page for the bottom bar). The bottom bar is hidden (not removed) and the page gets a left
 * margin of the rail's width, so it fills the rest of the screen. Pages switch exactly like the
 * bottom bar switches them (same intents, pages stay alive).
 *
 * White icons and names; the page you are on has a red mark; the item under the remote is a
 * white pill. An optional side panel (the account page uses it) sits between the line and the page.
 */
final class CigramTvRail {

    /** Views that draw their own focus look: the white outline is not drawn around them. */
    interface Styled { }

    static final String RAIL_TAG = "cigram_tv_rail";
    static final String LINE_TAG = "cigram_tv_rail_line";
    static final String PANEL_TAG = "cigram_tv_side_panel";
    private static final String SHELL_TAG = "cigram_nav_shell_v2";
    private static final String BAR_TAG = "transparent_cigram_nav";

    private static final float RAIL_DP = 188f;
    private static final float LINE_DP = 1f;
    private static final int BG_TOP = 0xFF04202B;
    private static final int BG_BOTTOM = 0xFF000B10;
    private static final int LINE = 0x33FFFFFF;
    private static final int INK_DARK = 0xFF00121A;
    private static final int RED = 0xFFE21B14;
    private static final int SOFT_TEXT = 0xBFFFFFFF;

    /** Rail order top to bottom, as indexes of CigramBottomNav's tabs (0 account … 3 home). */
    private static final int[] ORDER = {3, 2, 1, 0};
    private static final String[] NAMES = {"الرئيسية", "القنوات", "البحث", "الحساب"};
    private static final String[] ICONS = {"nav_home", "nav_channels", "nav_search", "nav_account"};

    /** A page index whose rail takes the focus when that page comes on screen (after a rail press). */
    static volatile int pendingFocus = -1;

    private CigramTvRail() { }

    // ================================================================== which page

    /** Index of the page in CigramBottomNav's tabs, or -1 for a page without a rail. */
    static int pageIndex(Activity a) {
        if (a == null) return -1;
        String n = a.getClass().getSimpleName();
        if ("MainActivity".equals(n) || "DetailsActivity".equals(n)) return 3;
        if ("ChannelsActivity".equals(n)) return 2;
        if ("SearchXmlActivity".equals(n)) return 1;
        if ("AccountActivity".equals(n)) return 0;
        return -1;
    }

    static int railWidth(Activity a) {
        return CigramUI.dp(a, RAIL_DP);
    }

    static int lineWidth(Activity a) {
        return Math.max(1, CigramUI.dp(a, LINE_DP));
    }

    // ================================================================== install

    /** Builds (once) or refreshes the rail of this page. Safe to call again and again. */
    static void install(Activity a) {
        try {
            if (a == null || a.isFinishing() || !CigramTvMode.isTv(a)) return;
            int sel = pageIndex(a);
            if (sel < 0) return;
            FrameLayout shell = shell(a);
            if (shell == null) return;
            View bar = shell.findViewWithTag(BAR_TAG);
            if (bar != null && bar.getVisibility() != View.GONE) bar.setVisibility(View.GONE);
            Rail rail = rail(shell);
            if (rail == null) {
                View page = pageOf(shell);
                if (page == null) return;
                rail = new Rail(a, sel);
                rail.page = new WeakReference<View>(page);
                shell.addView(rail, new FrameLayout.LayoutParams(railWidth(a),
                        ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT | Gravity.TOP));   // RTL: the rail is on the right
                View line = new View(a);
                line.setTag(LINE_TAG);
                line.setBackgroundColor(LINE);
                FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(lineWidth(a),
                        ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT | Gravity.TOP);
                ll.rightMargin = railWidth(a);
                shell.addView(line, ll);
            }
            rail.refresh();
            layoutPage(a, shell, rail);
        } catch (Throwable error) {
            android.util.Log.w("CigramTvRail", "install failed", error);
        }
    }

    /** True when this page has a rail. */
    static boolean has(Activity a) {
        FrameLayout shell = shell(a);
        return shell != null && rail(shell) != null;
    }

    /** Puts the focus on the rail item of this page. */
    static boolean focusSelected(Activity a) {
        try {
            FrameLayout shell = shell(a);
            Rail rail = shell == null ? null : rail(shell);
            if (rail == null) return false;
            return rail.focusSelected();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Puts the focus on the first button of the page itself (not the rail). */
    static boolean focusPage(Activity a) {
        try {
            FrameLayout shell = shell(a);
            Rail rail = shell == null ? null : rail(shell);
            View page = rail == null || rail.page == null ? null : rail.page.get();
            return page != null && page.isShown() && page.requestFocus(View.FOCUS_DOWN);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** True when the focus is inside the rail. */
    static boolean focusInRail(Activity a) {
        try {
            FrameLayout shell = shell(a);
            Rail rail = shell == null ? null : rail(shell);
            return rail != null && rail.hasFocus();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Badge and selection again (after notifications were read, a page came back, ...). */
    static void refresh(Activity a) {
        try {
            FrameLayout shell = shell(a);
            Rail rail = shell == null ? null : rail(shell);
            if (rail == null) return;
            rail.refresh();
            int idx = pageIndex(a);
            if (idx >= 0 && pendingFocus == idx) {
                pendingFocus = -1;
                rail.focusSelected();
            }
        } catch (Throwable ignored) { }
    }

    // ================================================================== side panel

    /** Width of the optional panel between the line and the page (40% of what the page had). */
    static int panelWidth(Activity a) {
        int screen = a.getResources().getDisplayMetrics().widthPixels;
        return Math.round((screen - railWidth(a) - lineWidth(a)) * 0.40f);
    }

    /** The page's side panel (created when asked), or null on a page without a rail. */
    static FrameLayout panel(Activity a, boolean create) {
        try {
            FrameLayout shell = shell(a);
            Rail rail = shell == null ? null : rail(shell);
            if (rail == null) return null;
            View existing = shell.findViewWithTag(PANEL_TAG);
            if (existing instanceof FrameLayout) return (FrameLayout) existing;
            if (!create) return null;
            FrameLayout panel = new FrameLayout(a);
            panel.setTag(PANEL_TAG);
            panel.setClipChildren(true);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(panelWidth(a),
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT | Gravity.TOP);
            lp.rightMargin = railWidth(a) + lineWidth(a);
            shell.addView(panel, lp);
            layoutPage(a, shell, rail);
            return panel;
        } catch (Throwable error) {
            android.util.Log.w("CigramTvRail", "panel failed", error);
            return null;
        }
    }

    /** Removes the side panel (the page widens back). */
    static void removePanel(Activity a) {
        try {
            FrameLayout shell = shell(a);
            if (shell == null) return;
            View p = shell.findViewWithTag(PANEL_TAG);
            if (p != null) shell.removeView(p);
            Rail rail = rail(shell);
            if (rail != null) layoutPage(a, shell, rail);
        } catch (Throwable ignored) { }
    }

    // ================================================================== helpers

    private static FrameLayout shell(Activity a) {
        if (a == null) return null;
        View content = a.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return null;
        View s = content.findViewWithTag(SHELL_TAG);
        if (s instanceof FrameLayout) return (FrameLayout) s;
        // the film page has no shell: its content frame plays that role
        if ("DetailsActivity".equals(a.getClass().getSimpleName()) && content instanceof FrameLayout) {
            return (FrameLayout) content;
        }
        return null;
    }

    private static Rail rail(FrameLayout shell) {
        View r = shell.findViewWithTag(RAIL_TAG);
        return r instanceof Rail ? (Rail) r : null;
    }

    /** The page itself: the first child that is not one of ours or the bottom bar. */
    private static View pageOf(FrameLayout shell) {
        for (int i = 0; i < shell.getChildCount(); i++) {
            View c = shell.getChildAt(i);
            Object tag = c.getTag();
            if (RAIL_TAG.equals(tag) || LINE_TAG.equals(tag) || PANEL_TAG.equals(tag) || BAR_TAG.equals(tag)) continue;
            return c;
        }
        return null;
    }

    private static void layoutPage(Activity a, FrameLayout shell, Rail rail) {
        View page = rail.page == null ? null : rail.page.get();
        if (page == null || page.getParent() != shell) {
            page = pageOf(shell);
            rail.page = new WeakReference<View>(page);
        }
        if (page == null) return;
        View panel = shell.findViewWithTag(PANEL_TAG);
        int right = railWidth(a) + lineWidth(a);   // RTL: rail, line and panel are on the right; the page takes the rest
        if (panel != null && panel.getVisibility() != View.GONE && panel.getLayoutParams() != null) {
            right += Math.max(0, panel.getLayoutParams().width);
        }
        ViewGroup.LayoutParams lp = page.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            if (m.rightMargin != right || m.leftMargin != 0) {
                m.rightMargin = right;
                m.leftMargin = 0;
                page.setLayoutParams(m);
            }
        }
    }

    private static int res(Activity a, String name) {
        try {
            return a.getResources().getIdentifier(name, "drawable", a.getPackageName());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Drawable pill(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    /** v10.67: the item under the remote — a white outline on a light fill (readable on every TV). */
    private static Drawable focusPill(Activity a, float radius, boolean oval) {
        GradientDrawable g = new GradientDrawable();
        if (oval) g.setShape(GradientDrawable.OVAL);
        else g.setCornerRadius(radius);
        g.setColor(0x33FFFFFF);
        g.setStroke(Math.max(2, CigramUI.dp(a, 2.5f)), 0xFFFFFFFF);
        return g;
    }

    /** focused: white pill · pressed: light pill · else: the resting look. */
    private static StateListDrawable states(Drawable focused, Drawable pressed, Drawable rest) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        s.addState(StateSet.WILD_CARD, rest);
        s.setEnterFadeDuration(110);
        s.setExitFadeDuration(110);
        return s;
    }

    // ================================================================== the rail view

    static final class Rail extends LinearLayout {
        final Activity a;
        final int selected;
        final Item[] items = new Item[ORDER.length];
        final View bellDot;
        WeakReference<View> page;

        Rail(final Activity a, int selected) {
            super(a);
            this.a = a;
            this.selected = selected;
            setTag(RAIL_TAG);
            setOrientation(VERTICAL);
            setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{BG_TOP, BG_BOTTOM}));
            int side = CigramUI.dp(a, 12);
            setPadding(side, CigramUI.dp(a, 18), side, CigramUI.dp(a, 14));
            setClickable(true);   // touches on the rail's background do not reach the page under it
            setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

            // ---- header: logo · name · notifications
            LinearLayout head = new LinearLayout(a);
            head.setOrientation(HORIZONTAL);
            head.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPaddingRelative(CigramUI.dp(a, 4), 0, 0, 0);
            ImageView logo = new ImageView(a);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int logoRes = res(a, "hoo");
            if (logoRes != 0) {
                logo.setImageResource(logoRes);
            } else {
                try { logo.setImageDrawable(a.getPackageManager().getApplicationIcon(a.getApplicationInfo())); }
                catch (Throwable ignored) { }
            }
            logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            head.addView(logo, new LayoutParams(CigramUI.dp(a, 32), CigramUI.dp(a, 32)));
            TextView name = CigramUI.label(a, "سيكرام", 19f, Color.WHITE, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            name.setIncludeFontPadding(false);
            LayoutParams nl = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            nl.setMarginStart(CigramUI.dp(a, 9));
            head.addView(name, nl);

            final Bell bell = new Bell(a);
            bellDot = bell.dot;
            head.addView(bell, new LayoutParams(CigramUI.dp(a, 40), CigramUI.dp(a, 40)));
            addView(head, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, CigramUI.dp(a, 44)));

            // ---- the pages
            for (int i = 0; i < ORDER.length; i++) {
                Item item = new Item(a, i, ORDER[i] == selected);
                items[i] = item;
                LayoutParams il = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, CigramUI.dp(a, 48));
                il.topMargin = CigramUI.dp(a, i == 0 ? 26 : 6);
                addView(item, il);
            }
            // the rail keeps the focus moving vertically between its own items
            for (int i = 0; i < items.length; i++) {
                items[i].setId(View.generateViewId());
            }
            bell.setId(View.generateViewId());
            for (int i = 0; i < items.length; i++) {
                items[i].setNextFocusUpId(i == 0 ? bell.getId() : items[i - 1].getId());
                items[i].setNextFocusDownId(i == items.length - 1 ? items[i].getId() : items[i + 1].getId());
            }
            bell.setNextFocusDownId(items[0].getId());
            bell.setNextFocusUpId(bell.getId());
        }

        void refresh() {
            boolean unread = false;
            try { unread = CigramStage1InitProvider.hasStage6Unread(a); } catch (Throwable ignored) { }
            bellDot.setVisibility(unread ? View.VISIBLE : View.GONE);
        }

        boolean focusSelected() {
            for (Item it : items) {
                if (it.screen == selected) return it.requestFocus();
            }
            return items.length > 0 && items[0].requestFocus();
        }

        void open(int screen, View from) {
            boolean onFilmPage = "DetailsActivity".equals(a.getClass().getSimpleName());
            if (screen == selected && !onFilmPage) {
                View p = page == null ? null : page.get();
                CigramNavFx.scrollToTop(p, from);   // the page you are on: back to its top
                return;
            }
            pendingFocus = screen;
            if (!CigramBottomNav.openTabTv(a, screen)) pendingFocus = -1;
        }

        /** One page of the rail: [mark] [icon] [name]. */
        final class Item extends LinearLayout implements Styled {
            final int screen;
            final boolean isSelected;
            final ImageView icon;
            final TextView label;

            Item(Activity a, int pos, boolean isSelected) {
                super(a);
                this.screen = ORDER[pos];
                this.isSelected = isSelected;
                setOrientation(HORIZONTAL);
                setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                setGravity(Gravity.CENTER_VERTICAL);
                setPaddingRelative(CigramUI.dp(a, 12), 0, CigramUI.dp(a, 10), 0);
                setFocusable(true);
                setClickable(true);
                setContentDescription(NAMES[pos]);
                float r = CigramUI.dp(a, 24);
                setBackground(states(focusPill(a, r, false), pill(0x40FFFFFF, r),
                        isSelected ? pill(0x1AFFFFFF, r) : new ColorDrawable(Color.TRANSPARENT)));

                View mark = new View(a);
                mark.setBackground(pill(RED, CigramUI.dp(a, 2)));
                mark.setVisibility(isSelected ? View.VISIBLE : View.INVISIBLE);
                LayoutParams ml = new LayoutParams(CigramUI.dp(a, 3), CigramUI.dp(a, 18));
                ml.setMarginEnd(CigramUI.dp(a, 9));
                addView(mark, ml);

                icon = new ImageView(a);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int id = res(a, ICONS[pos]);
                if (id != 0) icon.setImageResource(id);
                icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                addView(icon, new LayoutParams(CigramUI.dp(a, 22), CigramUI.dp(a, 22)));

                label = CigramUI.label(a, NAMES[pos], 15f, Color.WHITE, isSelected);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                label.setIncludeFontPadding(false);
                LayoutParams tl = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                tl.setMarginStart(CigramUI.dp(a, 13));
                addView(label, tl);

                setOnFocusChangeListener(new OnFocusChangeListener() {
                    @Override public void onFocusChange(View v, boolean has) { paint(has); }
                });
                setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) { open(screen, v); }
                });
                paint(false);
            }

            private int painted = -1;

            void paint(boolean focused) {
                int state = focused ? 1 : 0;
                if (state == painted) return;
                painted = state;
                icon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
                icon.setAlpha(focused || isSelected ? 1f : 0.78f);
                label.setTextColor(focused || isSelected ? Color.WHITE : SOFT_TEXT);
                Typeface f = CigramUI.font(getContext());
                label.setTypeface(f, focused || isSelected ? Typeface.BOLD : Typeface.NORMAL);
            }

            /** The look follows the real focus state, whatever moved the focus. */
            @Override protected void drawableStateChanged() {
                super.drawableStateChanged();
                if (icon != null && label != null) paint(isFocused());
            }
        }

        /** The notifications button of the rail (red dot when something is unread). */
        final class Bell extends FrameLayout implements Styled {
            final ImageView glyph;
            final View dot;

            Bell(final Activity a) {
                super(a);
                setFocusable(true);
                setClickable(true);
                setContentDescription("الإشعارات");
                Drawable white = focusPill(a, 0f, true);
                GradientDrawable press = new GradientDrawable();
                press.setShape(GradientDrawable.OVAL);
                press.setColor(0x40FFFFFF);
                setBackground(states(white, press, new ColorDrawable(Color.TRANSPARENT)));
                glyph = new ImageView(a);
                glyph.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                int id = res(a, "ic_notifications_white");
                if (id != 0) glyph.setImageResource(id);
                glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                addView(glyph, new FrameLayout.LayoutParams(CigramUI.dp(a, 22), CigramUI.dp(a, 22), Gravity.CENTER));
                dot = new View(a);
                GradientDrawable red = new GradientDrawable();
                red.setShape(GradientDrawable.OVAL);
                red.setColor(RED);
                red.setStroke(Math.max(1, CigramUI.dp(a, 1.5f)), BG_TOP);
                dot.setBackground(red);
                dot.setVisibility(View.GONE);
                FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(CigramUI.dp(a, 10), CigramUI.dp(a, 10),
                        Gravity.TOP | Gravity.RIGHT);
                dl.topMargin = CigramUI.dp(a, 7);
                dl.rightMargin = CigramUI.dp(a, 8);
                addView(dot, dl);
                glyph.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
                setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            Class<?> cls = Class.forName(a.getPackageName() + ".NotificationsActivity");
                            a.startActivity(new Intent(a, cls));
                            a.overridePendingTransition(0, 0);
                        } catch (Throwable error) {
                            android.widget.Toast.makeText(a, "تعذر فتح صفحة الاشعارات",
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }
    }
}
