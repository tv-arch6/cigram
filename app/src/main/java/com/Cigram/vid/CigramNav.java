package com.Cigram.vid;

import android.app.Activity;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * One way back, for every screen.
 *
 * Every visible back button and every back arrow of the app calls {@link #back}; the system back button / gesture reaches the same
 * place through {@code onBackPressed()}. A screen is exactly one entry of the task stack, so «back» is exactly one {@code onBackPressed()}:
 * the activity finishes and the previous screen is shown again, in the state it was left in (it was never destroyed: its scroll position,
 * its list and its search text are as they were).
 *
 * Two taps in a row (or a tap racing the system gesture) must pop ONE level, not two: a second request within
 * {@link #GUARD_MS} for the same screen is ignored.
 */
final class CigramNav {

    /** Faster than a deliberate second tap, slower than a bounce of the same finger. */
    static final long GUARD_MS = 400L;

    private static final Map<Activity, Long> LAST_BACK = new WeakHashMap<Activity, Long>();

    private CigramNav() { }

    /**
     * Goes one level up from {@code activity}. Honors the screen's own back handling (an open drawer, a second page, a search that
     * is open) because it goes through {@code onBackPressed()}, like the system button.
     *
     * @return true when this call acted, false when it was ignored (the screen is already closing, or a second tap).
     */
    static boolean back(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        long now = SystemClock.uptimeMillis();
        synchronized (LAST_BACK) {
            Long last = LAST_BACK.get(activity);
            if (last != null && now - last.longValue() < GUARD_MS) return false;
            LAST_BACK.put(activity, Long.valueOf(now));
        }
        try {
            activity.onBackPressed();
        } catch (Throwable error) {
            android.util.Log.w("CigramNav", "back failed, finishing", error);
            try { activity.finish(); } catch (Throwable ignored) { }
        }
        return true;
    }

    // ------------------------------------------------------------------ every screen has one

    /** Header arrows the XML screens already carry (all point right, at the right edge: correct for Arabic). */
    private static final String[] BACK_IDS = {
            "imageview_my_content_back", "img_ads_back", "img_settings_back", "imageview_communication_back", "image_back"};

    /** Screens that are not pages: the stack root, trampolines without a UI, pop-ups, the television front-end (remote Back key). */
    private static final String[] NO_BACK_BUTTON = {
            "MainActivity", "CigramBannerActivity", "CigramWaitActivity", "CigramLinkActivity", "CigramWidgetOpenActivity",
            "CigramTvActivity", "CigramTvDetailsActivity", "CigramMediaViewer",
            // 19.5: the app's own player is full-screen video: no floating back arrow over the picture
            // (system Back / gesture still closes it through onBackPressed()).
            "PlayerActivity"};

    /**
     * The Channels page, the Account page and every section opened from the Account page show NO back arrow at all:
     * the system Back button / gesture is the way out there (it still goes through {@link #back}'s target,
     * {@code onBackPressed()}).
     */
    private static final String[] HIDE_BACK = {
            "ChannelsActivity", "AccountActivity", "SettingsActivity", "MyContentActivity", "CommunicationActivity",
            "NotificationsActivity", "CigramTermsActivity", "CigramPlusActivity", "CigramPlusCodeActivity",
            "CigramWatchCalendarActivity", "AdsChatActivity", "CigramUpdateActivity", "CigramWatchlistMoreActivity"};

    static boolean hidesBack(String simpleName) {
        for (String n : HIDE_BACK) if (n.equals(simpleName)) return true;
        return false;
    }

    /** Pages build their headers in steps (some after a network answer): the arrow is removed now and again shortly after. */
    private static void hideBackLater(final Activity activity) {
        hideBackNow(activity);
        final View content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        for (long delay : new long[]{120L, 500L, 1500L}) {
            content.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!activity.isFinishing() && !activity.isDestroyed()) hideBackNow(activity);
                }
            }, delay);
        }
    }

    private static void hideBackNow(Activity activity) {
        try {
            for (String id : BACK_IDS) {
                View v = byId(activity, id);
                if (v != null) v.setVisibility(View.GONE);
            }
            View channelsBack = byId(activity, "imageview_channels_back");
            if (channelsBack != null) channelsBack.setVisibility(View.GONE);
            hideBackIn(activity.findViewById(android.R.id.content));
        } catch (Throwable ignored) { }
    }

    private static void hideBackIn(View root) {
        if (root == null) return;
        boolean isBack = CigramTopBar.TAG_BACK.equals(root.getTag());
        CharSequence description = root.getContentDescription();
        if (!isBack && description != null && root.isClickable()) {
            String d = description.toString().trim();
            isBack = d.equals("رجوع") || d.equalsIgnoreCase("back") || d.equals("العودة");
        }
        if (isBack) {
            root.setVisibility(View.GONE);
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) hideBackIn(group.getChildAt(i));
        }
    }

    /**
     * The guarantee: after this call the screen has ONE visible back button that goes through {@link #back}.
     *
     *  1. the screen's own XML header arrow is made a real button (48 dp target, «رجوع», tap = {@link #back}) when nothing else wired it;
     *  2. a back control the screen draws itself (tagged, or described / labelled «رجوع») is left alone;
     *  3. otherwise the shared button is added: in the search header on the Search page, floating at the top-right everywhere else.
     *
     * Safe to call again and again (it never adds a second button). Home is the root of the stack and has none: its Back leaves the app.
     */
    static void install(Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            if (!activity.getClass().getName().startsWith(activity.getPackageName() + ".")) return;
            String name = activity.getClass().getSimpleName();
            for (String skip : NO_BACK_BUTTON) if (skip.equals(name)) return;
            if (CigramTvMode.isTv(activity)) return;
            if (hidesBack(name)) {
                hideBackLater(activity);
                return;
            }
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof ViewGroup)) return;

            boolean found = false;
            for (String id : BACK_IDS) {
                View v = byId(activity, id);
                if (v == null || v.getVisibility() != View.VISIBLE || tiny(activity, v)) continue;   // a 1 dp placeholder is not a button
                adopt(activity, v);
                found = true;
            }
            if (!found) found = hasBackControl(content);
            if (found) return;

            if ("SearchXmlActivity".equals(name)) {
                View header = byId(activity, "linear_search_top");
                if (header instanceof LinearLayout && header.findViewWithTag(CigramTopBar.TAG_BACK) == null) {
                    int tint = CigramTheme.isLight(activity) ? 0xFF111418 : 0xFFFFFFFF;
                    ((LinearLayout) header).addView(CigramTopBar.backButton(activity, tint),
                            new LinearLayout.LayoutParams(CigramUI.dp(activity, 40), CigramUI.dp(activity, 40)));   // last child = right edge
                    return;
                }
            }
            CigramTopBar.floatingBack(activity, (ViewGroup) content);
        } catch (Throwable error) {
            android.util.Log.w("CigramNav", "install failed", error);
        }
    }

    /** An XML arrow becomes a proper button; a listener somebody already set is kept (the details page flies its poster back, for one). */
    private static void adopt(Activity activity, View v) {
        v.setClickable(true);
        v.setFocusable(true);
        if (v.getContentDescription() == null) v.setContentDescription("رجوع");
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        int min = CigramUI.dp(activity, 48);
        if (lp != null && lp.width > 0 && lp.width < min && lp.height > 0 && lp.height < min) {
            lp.width = Math.max(lp.width, min);
            lp.height = Math.max(lp.height, min);
            v.setLayoutParams(lp);
        }
        if (!v.hasOnClickListeners()) bind(activity, v);
    }

    private static boolean tiny(Activity activity, View v) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        int limit = CigramUI.dp(activity, 8);
        return lp != null && ((lp.width > 0 && lp.width <= limit) || (lp.height > 0 && lp.height <= limit));
    }

    private static View byId(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        return id == 0 ? null : activity.findViewById(id);
    }

    /** True when the screen already draws a back control: our tagged button, or something described / labelled «رجوع». */
    static boolean hasBackControl(View root) {
        if (root == null) return false;
        if (CigramTopBar.TAG_BACK.equals(root.getTag())) return true;
        CharSequence description = root.getContentDescription();
        if (description != null && root.getVisibility() == View.VISIBLE && root.isClickable()) {
            String d = description.toString().trim();
            if (d.equals("رجوع") || d.equalsIgnoreCase("back") || d.equals("العودة")) return true;
        }
        if (root instanceof TextView && root.isClickable() && root.getVisibility() == View.VISIBLE) {
            String t = String.valueOf(((TextView) root).getText()).trim();
            if (t.equals("رجوع") || t.equals("عودة")) return true;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasBackControl(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    /** Makes {@code view} a back button: tap = {@link #back}. */
    static void bind(final Activity activity, View view) {
        if (view == null) return;
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { back(activity); }
        });
    }
}
