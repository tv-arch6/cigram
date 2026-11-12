package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cigram Stage 6 bridge for the notifications.xml page.
 *
 * Important:
 * - No fake/programmatic notifications page is created here.
 * - main.xml ImageView id "ball" opens the user's NotificationsActivity.
 * - notifications.xml views are used directly:
 *   swipe_notifications -> scroll_notifications -> linear_notifications_body
 *   -> linear_notifications_list + textview_notifications_empty.
 * - Only runtime notification ROWS are created inside linear_notifications_list.
 * - The red unread dot is an overlay on the existing bell ImageView, not a new page.
 */
public final class CigramNotifyCenter {
    static void trimMemory(int severity) { CigramStability.shrink(IMAGE_CACHE, severity); }


    private static final int ROW_BG = 0xFF1B1B1F;
    private static final int POSTER_BG = 0xFF26262B;
    private static final int TEXT_MAIN = 0xFFFFFFFF;
    private static final int TEXT_MUTED = 0xFF8E8E94;
    private static final int BADGE_RED = 0xFFE51C23;

    private static final String PREF_SUBSCRIPTIONS = "cigram_series_subscriptions";
    private static final String KEY_ITEMS = "items_json";
    private static final String OLD_DYNAMIC_BODY_TAG =
            "cigram_stage6_notifications_body";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IMAGE_IO = CigramPool.fixed(3, "notif-img");

    private static final android.util.LruCache<String, Bitmap> IMAGE_CACHE =
            new android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    if (value == null) return 1;
                    return Math.max(1, value.getByteCount());
                }
            };

    private static final Map<View, Drawable> BELL_BADGES =
            new WeakHashMap<View, Drawable>();

    private static final Map<Activity, PageState> PAGE_STATES =
            new WeakHashMap<Activity, PageState>();

    /** PageState keeps its Activity: a weak key cannot release it, so the entry is dropped when the page is destroyed. */
    static void onDestroyed(Activity activity) {
        boolean wasPage;
        synchronized (PAGE_STATES) { wasPage = PAGE_STATES.remove(activity) != null; }
        // Admin messages and app-update notices are shown ONCE: leaving the page that displayed them deletes them
        if (wasPage && activity != null) {
            try {
                if (CigramStage1InitProvider.burnShownAnnouncements(activity.getApplicationContext()) > 0) notifyUnreadChanged();
            } catch (Throwable ignored) { }
        }
    }

    private static WeakReference<Activity> LAST_MAIN_ACTIVITY =
            new WeakReference<Activity>(null);

    private CigramNotifyCenter() {
    }

    /** Bind the existing bell ImageView (id: ball). */
    public static void bindHomeBell(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (!"MainActivity".equals(activity.getClass().getSimpleName())) return;

        final View bell = findByIdName(activity, "ball");
        if (bell == null) return;

        LAST_MAIN_ACTIVITY = new WeakReference<Activity>(activity);

        bell.setClickable(true);
        bell.setFocusable(true);
        bell.setContentDescription("الاشعارات او التنبيهات");
        bell.setAlpha(1f);
        bell.setBackgroundColor(Color.TRANSPARENT);

        if (bell instanceof ImageView) {
            // Resting state: keep the user's original drawable/colors.
            ((ImageView) bell).clearColorFilter();
        }

        updateBellBadge(activity, bell);

        bell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    Class<?> cls = Class.forName(
                            activity.getPackageName() + ".NotificationsActivity");
                    Intent intent = new Intent(activity, cls);
                    activity.startActivity(intent);
                    activity.overridePendingTransition(0, 0);
                } catch (Throwable error) {
                    CigramSilent.toast(
                            activity,
                            "تعذر فتح صفحة الاشعارات او التنبيهات", android.widget.Toast.LENGTH_SHORT);
                }
            }
        });
    }

    /** Called when a new episode is recorded or when the page is opened. */
    public static void notifyUnreadChanged() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Activity activity = LAST_MAIN_ACTIVITY.get();
                if (activity == null || activity.isFinishing()) return;
                View bell = findByIdName(activity, "ball");
                if (bell != null) updateBellBadge(activity, bell);
            }
        });
    }

    private static void updateBellBadge(final Activity activity, final View bell) {
        if (activity == null || bell == null) return;
        final boolean unread = CigramStage1InitProvider.hasStage6Unread(activity);

        bell.post(new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT < 18) return;

                try {
                    Drawable old = BELL_BADGES.remove(bell);
                    if (old != null) {
                        bell.getOverlay().remove(old);
                    }

                    if (!unread) return;

                    int size = dp(activity, 10);
                    int rightInset = dp(activity, 6);
                    int topInset = dp(activity, 5);
                    int left = Math.max(0, bell.getWidth() - rightInset - size);
                    int top = Math.max(0, topInset);

                    GradientDrawable dot = new GradientDrawable();
                    dot.setShape(GradientDrawable.OVAL);
                    dot.setColor(BADGE_RED);
                    dot.setBounds(left, top, left + size, top + size);

                    bell.getOverlay().add(dot);
                    BELL_BADGES.put(bell, dot);
                } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "run", ignored); }
            }
        });
    }

    /** Bind the user's REAL notifications.xml page. */
    public static void bindNotificationsPage(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (!"NotificationsActivity".equals(activity.getClass().getSimpleName())) return;

        try {
            if (activity.getWindow() != null) {
                activity.getWindow().setWindowAnimations(0);
            }
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "bindNotificationsPage", ignored); }

        // Remove an old Stage-6 runtime overlay if it exists from an older build/process.
        try {
            ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
            if (content != null) {
                View oldBody = content.findViewWithTag(OLD_DYNAMIC_BODY_TAG);
                if (oldBody != null && oldBody.getParent() instanceof ViewGroup) {
                    ((ViewGroup) oldBody.getParent()).removeView(oldBody);
                }
            }
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "bindNotificationsPage", ignored); }

        final TextView title = asTextView(findByIdName(
                activity, "textview_my_content_title"));
        if (title != null) {
            // Preserve the exact title text already set in the layout.
            title.setTextColor(Color.WHITE);
            title.setTextDirection(View.TEXT_DIRECTION_RTL);
            title.setTypeface(loadSima(activity), Typeface.NORMAL);
            title.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (!CigramStage1InitProvider
                            .areStage6SystemNotificationsEnabled(activity)) {
                        CigramStage1InitProvider
                                .requestStage6NotificationPermission(activity, true);
                        return true;
                    }
                    CigramStage1InitProvider.sendStage6TestNotification(activity);
                    CigramSilent.toast(
                            activity,
                            "تم إرسال إشعار تجريبي", android.widget.Toast.LENGTH_SHORT);
                    return true;
                }
            });
        }

        final View back = findByIdName(activity, "imageview_my_content_back");
        if (back != null) {
            back.setClickable(true);
            back.setFocusable(true);
            back.setContentDescription("رجوع");
            CigramNav.bind(activity, back);
        }

        final SwipeRefreshLayout swipe = asSwipeRefresh(
                findByIdName(activity, "swipe_notifications"));
        final View scroll = findByIdName(activity, "scroll_notifications");
        final LinearLayout body = asLinearLayout(
                findByIdName(activity, "linear_notifications_body"));
        final LinearLayout list = asLinearLayout(
                findByIdName(activity, "linear_notifications_list"));
        final TextView empty = asTextView(
                findByIdName(activity, "textview_notifications_empty"));

        if (body == null || list == null || empty == null) {
            android.util.Log.w(
                    "CigramStage6",
                    "notifications.xml ids missing: body/list/empty"
            );
            return;
        }

        final PageState state = new PageState();
        state.activity = activity;
        state.swipe = swipe;
        state.scroll = scroll;
        state.body = body;
        state.list = list;
        state.empty = empty;
        state.sima = loadSima(activity);
        PAGE_STATES.put(activity, state);

        body.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        list.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        list.setOrientation(LinearLayout.VERTICAL);
        empty.setTypeface(state.sima, Typeface.NORMAL);
        empty.setTextColor(TEXT_MUTED);
        empty.setTextSize(17f);
        empty.setGravity(Gravity.CENTER);
        empty.setTextDirection(View.TEXT_DIRECTION_RTL);
        empty.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        empty.setPadding(
                dp(activity, 20),
                dp(activity, 24),
                dp(activity, 20),
                dp(activity, 24)
        );
        ensureEmptyStateHeight(activity, empty);

        // Opening the page clears only the RED DOT. History stays forever until user delete.
        CigramStage1InitProvider.markStage6NotificationsRead(activity);

        if (swipe != null) {
            swipe.setColorSchemeColors(Color.WHITE);
            swipe.setProgressBackgroundColorSchemeColor(0xFF1B1B1F);
            swipe.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
                @Override
                public void onRefresh() {
                    CigramPush.syncAnnouncements(activity, true, null);   // v10.67: admin messages
                    CigramStage1InitProvider.runStage6UserRefresh(
                            activity,
                            new Runnable() {
                                @Override
                                public void run() {
                                    MAIN.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (activity.isFinishing()) return;
                                            if (state.swipe != null) {
                                                state.swipe.setRefreshing(false);
                                            }
                                            render(state);
                                        }
                                    });
                                }
                            }
                    );
                }
            });
        }

        render(state);

        // v10.67: the admin's messages (also the ones Android showed while the app was closed)
        CigramPush.syncAnnouncements(activity, true, new CigramPush.ResultCallback() {
            @Override
            public void done(boolean changed) {
                if (!changed || activity.isFinishing()) return;
                CigramStage1InitProvider.markStage6NotificationsRead(activity);
                render(state);
            }
        });
    }

    public static void refreshNotificationsPage(Activity activity) {
        if (activity == null) return;
        PageState state = PAGE_STATES.get(activity);
        if (state != null) {
            render(state);
        } else {
            bindNotificationsPage(activity);
        }
    }

    private static void render(final PageState state) {
        if (state == null || state.activity == null) return;
        if (state.activity.isFinishing()) return;
        if (state.list == null || state.empty == null) return;

        final Activity activity = state.activity;
        final JSONArray subscriptions = readArray(
                activity, PREF_SUBSCRIPTIONS, KEY_ITEMS);
        final JSONArray history = CigramStage1InitProvider.getStage6History(activity);

        updateSystemNotificationBanner(state);
        state.list.removeAllViews();
        buildNewSection(state);   // v10.54: every new film and series in one section at the top

        // Saved notifications win over the empty-state text (admin messages leave after this one look).
        if (history.length() > 0) {
            state.empty.setVisibility(View.GONE);
            state.list.setVisibility(View.VISIBLE);
            addSectionTitle(state.activity, state.list, state.sima, "الإشعارات");

            for (int i = 0; i < history.length(); i++) {
                JSONObject item = history.optJSONObject(i);
                if (item == null) continue;
                addNotificationRow(state, item);
            }
            return;
        }

        state.list.setVisibility(View.GONE);
        state.empty.setVisibility(View.VISIBLE);
        ensureEmptyStateHeight(activity, state.empty);

        if (subscriptions.length() == 0) {
            state.empty.setText("ماكو إشعارات هسه\nتابع مسلسلًا من صفحة تفاصيله (زر الاشتراك)\nحتى يوصلك تنبيه عند كل حلقة جديدة");
        } else {
            state.empty.setText("ماكو إشعارات جديدة هسه\nراح يوصلك تنبيه أول ما تنضاف حلقة جديدة\nلمسلسل تتابعه");
        }
    }

    private static void updateSystemNotificationBanner(final PageState state) {
        if (state == null || state.activity == null || state.body == null) return;
        final Activity activity = state.activity;
        final boolean enabled = CigramStage1InitProvider
                .areStage6SystemNotificationsEnabled(activity);

        View current = state.body.findViewWithTag(
                "cigram_stage6_notification_permission_banner");
        if (enabled) {
            if (current != null && current.getParent() instanceof ViewGroup) {
                ((ViewGroup) current.getParent()).removeView(current);
            }
            state.permissionBanner = null;
            return;
        }

        if (current != null) {
            state.permissionBanner = current;
            return;
        }

        LinearLayout banner = new LinearLayout(activity);
        banner.setTag("cigram_stage6_notification_permission_banner");
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(
                dp(activity, 14),
                dp(activity, 10),
                dp(activity, 14),
                dp(activity, 10)
        );
        banner.setBackground(rounded(0xFF26262B, dp(activity, 12)));

        TextView text = new TextView(activity);
        text.setText("إشعارات النظام معطلة");
        text.setTextColor(TEXT_MAIN);
        text.setTextSize(13f);
        text.setTypeface(state.sima, Typeface.NORMAL);
        text.setTextDirection(View.TEXT_DIRECTION_RTL);
        text.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        banner.addView(text, new LinearLayout.LayoutParams(0, dp(activity, 42), 1f));

        TextView enable = new TextView(activity);
        enable.setText("تفعيل");
        enable.setTextColor(Color.WHITE);
        enable.setTextSize(12.5f);
        enable.setTypeface(state.sima, Typeface.NORMAL);
        enable.setGravity(Gravity.CENTER);
        enable.setClickable(true);
        enable.setFocusable(true);
        enable.setBackground(rounded(0xFFE21B14, dp(activity, 20)));
        enable.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CigramStage1InitProvider
                        .requestStage6NotificationPermission(activity, true);
            }
        });
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                dp(activity, 82),
                dp(activity, 38)
        );
        buttonLp.setMargins(dp(activity, 10), 0, 0, 0);
        banner.addView(enable, buttonLp);

        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bannerLp.setMargins(
                dp(activity, 12),
                dp(activity, 8),
                dp(activity, 12),
                dp(activity, 10)
        );
        state.body.addView(banner, 0, bannerLp);
        state.permissionBanner = banner;
    }

    private static void ensureEmptyStateHeight(Activity activity, TextView empty) {
        if (activity == null || empty == null) return;
        try {
            int screen = activity.getResources().getDisplayMetrics().heightPixels;
            empty.setMinHeight(Math.max(dp(activity, 320), screen - dp(activity, 190)));
        } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "ensureEmptyStateHeight", ignored); }
    }

    private static TextView label(Activity a, Typeface face, String text, float sp, int color) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(face, Typeface.NORMAL);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        return t;
    }

    private static final String TAG_NEW_SECTION = "cigram_notify_new_section";
    private static final long NEW_WINDOW_MS = 30L * 24L * 3600000L;
    private static final int NEW_FIRST_COUNT = 6;
    private static final int KIND_MOVIE = 0xFFFF6B63;
    private static final int KIND_SERIES = 0xFF5FD0DE;
    private static final int GOLD = 0xFFFFE93B;

    /** "قبل 10 دقائق" style age. */
    private static String timeAgo(long ms) {
        if (ms <= 0L) return "";
        long diff = Math.max(0L, System.currentTimeMillis() - ms);
        long min = diff / 60000L;
        if (min < 1L) return "الآن";
        if (min < 60L) return "قبل " + min + (min == 1L ? " دقيقة" : (min == 2L ? " دقيقتين" : (min <= 10L ? " دقائق" : " دقيقة")));
        long hours = min / 60L;
        if (hours < 24L) return "قبل " + hours + (hours == 1L ? " ساعة" : (hours == 2L ? " ساعتين" : (hours <= 10L ? " ساعات" : " ساعة")));
        long days = hours / 24L;
        return days == 1L ? "أمس" : "قبل " + days + (days == 2L ? " يومين" : (days <= 10L ? " أيام" : " يوماً"));
    }

    private static GradientDrawable pillBg(int fill, int stroke, float radiusDp, Activity a) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(a, radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(a, 1f)), stroke);
        return g;
    }

    private static void addSectionTitle(Activity a, LinearLayout parent, Typeface face, String text) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        TextView t = label(a, face, text, 14f, TEXT_MAIN);
        t.setTypeface(face, Typeface.BOLD);
        row.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View bar = new View(a);
        bar.setBackground(rounded(BADGE_RED, dp(a, 2f)));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(a, 3f), dp(a, 14f));
        bl.leftMargin = dp(a, 7f);
        row.addView(bar, bl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(a, 16f), dp(a, 6f), dp(a, 16f), dp(a, 8f));
        parent.addView(row, lp);
    }

    /**
     * v10.54: ONE section on top of the page with every new film and series (not only the ones
     * followed): filter tabs الكل / أفلام / مسلسلات, then rows with the poster on the right and, beside
     * it, the title, the new season number and the new episode's name. Built in the background, then drawn.
     */
    private static void buildNewSection(final PageState state) {
        final Activity a = state.activity;
        View old = state.body.findViewWithTag(TAG_NEW_SECTION);
        if (old != null && old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
        IMAGE_IO.execute(new Runnable() {
            @Override public void run() {
                final java.util.ArrayList<Entry> found = collectNew();
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (a.isFinishing() || found.isEmpty()) return;
                        View stale = state.body.findViewWithTag(TAG_NEW_SECTION);
                        if (stale != null && stale.getParent() instanceof ViewGroup) {
                            ((ViewGroup) stale.getParent()).removeView(stale);
                        }
                        state.entries = found;
                        drawNewSection(state);
                    }
                });
            }
        });
    }

    private static java.util.ArrayList<Entry> collectNew() {
        java.util.ArrayList<Entry> out = new java.util.ArrayList<Entry>();
        try {
            long now = System.currentTimeMillis();
            java.util.HashSet<String> seen = new java.util.HashSet<String>();
            for (JSONObject item : CigramContent.catalog()) {
                String key = CigramContent.itemKey(item);
                if (key.length() == 0 || !seen.add(key)) continue;
                Entry e = new Entry();
                e.item = item;
                e.series = CigramContent.isSeries(item);
                e.stamp = CigramContent.addedAtMs(item);
                if (e.series) {
                    JSONArray eps = CigramStage1InitProvider.cigramEpisodes(item);
                    JSONObject best = null;
                    long bestAt = 0L;
                    for (int i = 0; eps != null && i < eps.length(); i++) {
                        JSONObject ep = eps.optJSONObject(i);
                        long v = ep == null ? 0L : CigramContent.addedAtMs(ep);
                        if (v > bestAt) { bestAt = v; best = ep; }
                    }
                    if (bestAt > e.stamp) e.stamp = bestAt;
                    if (best != null) {
                        e.season = CigramContent.intOf(best, "season", "season_number");
                        e.episode = CigramContent.text(best, "title", "name", "episode_title");
                        if (e.episode.length() == 0) {
                            int n = CigramContent.intOf(best, "episode", "episode_number");
                            if (n > 0) e.episode = String.valueOf(n);
                        }
                    }
                    if (e.season == 0) e.season = CigramContent.intOf(item, "season", "season_number");
                }
                if (e.stamp <= 0L || now - e.stamp > NEW_WINDOW_MS) continue;
                e.title = e.series ? CigramContent.text(item, "series_title", "title", "name")
                        : CigramContent.text(item, "title", "name");
                e.poster = CigramContent.text(item, "poster", "series_poster", "image", "backdrop");
                out.add(e);
            }
            java.util.Collections.sort(out, new java.util.Comparator<Entry>() {
                @Override public int compare(Entry x, Entry y) {
                    return x.stamp == y.stamp ? 0 : (x.stamp < y.stamp ? 1 : -1);
                }
            });
            while (out.size() > 30) out.remove(out.size() - 1);
        } catch (Throwable error) {
            android.util.Log.w("CigramNotify", "collect new failed", error);
        }
        return out;
    }

    private static void drawNewSection(final PageState state) {
        final Activity a = state.activity;
        final LinearLayout box = new LinearLayout(a);
        box.setTag(TAG_NEW_SECTION);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        addSectionTitle(a, box, state.sima, "الجديد على سيكرام");

        // tabs: physical order left to right, so «الكل» ends up on the right
        final LinearLayout tabs = new LinearLayout(a);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.RIGHT);
        final LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        final String[][] defs = {{"series", "مسلسلات"}, {"movie", "أفلام"}, {"all", "الكل"}};
        final TextView[] chips = new TextView[defs.length];
        final Runnable[] refill = new Runnable[1];
        for (int i = 0; i < defs.length; i++) {
            final String key = defs[i][0];
            TextView chip = label(a, state.sima, defs[i][1], 12.5f, TEXT_MAIN);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(a, 15f), dp(a, 5f), dp(a, 15f), dp(a, 5f));
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    state.tab = key;
                    state.expanded = false;
                    refill[0].run();
                }
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.leftMargin = dp(a, 6f);
            tabs.addView(chip, cp);
            chips[i] = chip;
        }
        refill[0] = new Runnable() {
            @Override public void run() {
                for (int i = 0; i < defs.length; i++) {
                    boolean on = defs[i][0].equals(state.tab);
                    chips[i].setBackground(on ? pillBg(BADGE_RED, 0, 16f, a)
                            : pillBg(ROW_BG, 0xFF34343A, 16f, a));
                    chips[i].setTextColor(on ? Color.WHITE : 0xFFC9D2D7);
                }
                rows.removeAllViews();
                int shown = 0;
                int total = 0;
                for (Entry e : state.entries) {
                    if ("movie".equals(state.tab) && e.series) continue;
                    if ("series".equals(state.tab) && !e.series) continue;
                    total++;
                    if (!state.expanded && shown >= NEW_FIRST_COUNT) continue;
                    addEntryRow(a, rows, e, state.sima);
                    shown++;
                }
                if (total == 0) {
                    TextView none = label(a, state.sima, "ماكو جديد بهذا القسم هسه", 13f, TEXT_MUTED);
                    none.setGravity(Gravity.CENTER);
                    none.setPadding(0, dp(a, 18f), 0, dp(a, 18f));
                    rows.addView(none, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                } else if (!state.expanded && total > shown) {
                    TextView more = label(a, state.sima, "عرض المزيد (" + (total - shown) + ")", 13f, 0xFFE21B14);
                    more.setGravity(Gravity.CENTER);
                    more.setPadding(0, dp(a, 10f), 0, dp(a, 10f));
                    more.setClickable(true);
                    more.setFocusable(true);
                    more.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            state.expanded = true;
                            refill[0].run();
                        }
                    });
                    rows.addView(more, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
            }
        };
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.setMargins(dp(a, 12f), 0, dp(a, 12f), dp(a, 8f));
        box.addView(tabs, tp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.setMargins(dp(a, 12f), 0, dp(a, 12f), dp(a, 4f));
        box.addView(rows, rp);
        refill[0].run();

        int at = state.body.indexOfChild(state.list);
        if (at < 0) at = state.body.getChildCount();
        state.body.addView(box, at, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static TextView kindPill(Activity a, Typeface face, boolean series) {
        TextView t = label(a, face, series ? "مسلسل" : "فيلم جديد", 10.5f, series ? KIND_SERIES : KIND_MOVIE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(a, 9f), dp(a, 1f), dp(a, 9f), dp(a, 1f));
        t.setBackground(pillBg(series ? 0x2926A5B5 : 0x29E21B14, series ? 0x6626A5B5 : 0x66E21B14, 10f, a));
        return t;
    }

    /** Poster on the right; beside it: kind, title, new season + episode name, age. */
    private static void addEntryRow(final Activity a, LinearLayout parent, final Entry e, Typeface face) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(a, 8f), dp(a, 8f), dp(a, 8f), dp(a, 8f));
        row.setBackground(pillBg(ROW_BG, 0xFF34343A, 14f, a));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        LinearLayout kindRow = new LinearLayout(a);
        kindRow.setOrientation(LinearLayout.HORIZONTAL);
        kindRow.setGravity(Gravity.RIGHT);
        kindRow.addView(kindPill(a, face, e.series));
        col.addView(kindRow, new LinearLayout.LayoutParams(full));

        TextView title = label(a, face, e.title.length() > 0 ? e.title : "بدون عنوان", 13.5f, TEXT_MAIN);
        title.setTypeface(face, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(full);
        tl.topMargin = dp(a, 3f);
        col.addView(title, tl);

        if (e.series && (e.season > 0 || e.episode.length() > 0)) {
            LinearLayout line = new LinearLayout(a);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            line.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            if (e.episode.length() > 0) {
                TextView ep = label(a, face, e.episode, 12f, 0xFFC9D2D7);
                ep.setSingleLine(true);
                ep.setEllipsize(android.text.TextUtils.TruncateAt.END);
                line.addView(ep, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            } else {
                line.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
            }
            if (e.season > 0) {
                TextView season = label(a, face, "الموسم " + e.season, 10.5f, 0xFF111111);
                season.setTypeface(face, Typeface.BOLD);
                season.setGravity(Gravity.CENTER);
                season.setPadding(dp(a, 7f), dp(a, 1f), dp(a, 7f), dp(a, 1f));
                season.setBackground(pillBg(GOLD, 0, 5f, a));
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                sl.leftMargin = dp(a, 6f);
                line.addView(season, sl);
            }
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(full);
            ll.topMargin = dp(a, 3f);
            col.addView(line, ll);
        }

        TextView when = label(a, face, timeAgo(e.stamp), 10.5f, TEXT_MUTED);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(full);
        wl.topMargin = dp(a, 3f);
        col.addView(when, wl);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(rounded(POSTER_BG, dp(a, 9f)));
        if (Build.VERSION.SDK_INT >= 21) {
            final float radius = dp(a, 9f);
            poster.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            poster.setClipToOutline(true);
        }
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(dp(a, 58f), dp(a, 84f));
        pl.leftMargin = dp(a, 10f);
        row.addView(poster, pl);
        loadPoster(a, poster, e.poster);

        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    CigramStage1InitProvider.openTitleDetails(a, e.item, poster);
                } catch (Throwable ignored) { }
            }
        });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.bottomMargin = dp(a, 8f);
        parent.addView(row, rl);
    }

    /**
     * v10.54: one notification = one compact card with its OWN message. A film is red ("فيلم")
     * and a series is teal with its new season and episode. Tapping opens the title (new title)
     * or plays the new episode.
     */
    private static void addNotificationRow(
            final PageState state,
            final JSONObject saved
    ) {
        if (state == null || state.activity == null || state.list == null) return;
        final Activity activity = state.activity;
        if ("announcement".equals(saved.optString("kind", ""))) {
            addAnnouncementRow(state, saved);   // v10.67: a message from the Cigram admin
            return;
        }
        // fill anything the saved snapshot lacks (older notifications) from the catalog
        final JSONObject item = CigramStage1InitProvider.stage6CardDetails(saved);

        String name = item.optString("series_title", "").trim();
        if (name.length() == 0) name = item.optString("title", "").trim();
        final boolean newTitle = "new_title".equals(item.optString("kind", ""));
        final boolean isSeriesTitle = "series".equals(item.optString("content_type", ""));
        final boolean series = !newTitle || isSeriesTitle;

        String message;
        String detail;
        String season = item.optString("season", "").trim();
        String episode = item.optString("episode", "").trim();
        String epTitle = item.optString("episode_title", "").trim();
        if (newTitle) {
            message = (isSeriesTitle ? "بدأ مسلسل " : "تمت إضافة فيلم ") + name;
            detail = isSeriesTitle
                    ? (season.length() > 0 ? "الموسم " + season : "مسلسل جديد")
                    : "أضيف حديثاً إلى المكتبة وجاهز للمشاهدة.";
        } else {
            message = "نزلت حلقة جديدة من " + name;
            String epText = epTitle.length() > 0 && !epTitle.equals(name) ? epTitle
                    : (episode.length() > 0 ? "الحلقة " + episode : "حلقة جديدة");
            detail = season.length() > 0 ? "الموسم " + season + "، " + epText : epText;
        }

        final int accent = series ? KIND_SERIES : KIND_MOVIE;
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        card.setPadding(dp(activity, 12f), dp(activity, 12f), dp(activity, 12f), dp(activity, 12f));
        card.setBackground(pillBg(ROW_BG, series ? 0x6626A5B5 : 0x66E21B14, 14f, activity));

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        TextView msg = label(activity, state.sima, message, 13.5f, TEXT_MAIN);
        msg.setTypeface(state.sima, Typeface.BOLD);
        msg.setMaxLines(2);
        msg.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(msg, new LinearLayout.LayoutParams(full));
        TextView sub = label(activity, state.sima, detail, 12f, 0xFFC5D0D6);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(full);
        sl.topMargin = dp(activity, 2f);
        col.addView(sub, sl);
        TextView when = label(activity, state.sima, timeAgo(item.optLong("created_at", 0L)), 10.5f, TEXT_MUTED);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(full);
        wl.topMargin = dp(activity, 4f);
        col.addView(when, wl);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView icon = label(activity, state.sima, series ? "م" : "ف", 16f, accent);
        icon.setTypeface(state.sima, Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(pillBg(series ? 0x2E26A5B5 : 0x2EE21B14, 0, 10f, activity));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(activity, 38f), dp(activity, 38f));
        il.leftMargin = dp(activity, 10f);
        card.addView(icon, il);

        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(message);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (newTitle) CigramStage1InitProvider.openStage6TitleDetails(activity, item);
                else CigramStage1InitProvider.playStage6Episode(activity, item);
            }
        });
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(dp(activity, 12f), 0, dp(activity, 12f), dp(activity, 8f));
        state.list.addView(card, cardLp);
    }

    /** v10.67: one admin message (title, text, age). Tapping shows the whole text. */
    private static void addAnnouncementRow(final PageState state, final JSONObject saved) {
        final Activity activity = state.activity;
        final String title = saved.optString("title", "").trim().length() > 0
                ? saved.optString("title", "").trim() : "سيكرام";
        final String body = saved.optString("body", "").trim();
        String level = saved.optString("level", "info");
        final int accent = "warning".equals(level) ? 0xFFFFB547 : ("success".equals(level) ? 0xFF34D399 : 0xFF4D8EFF);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        card.setPadding(dp(activity, 12f), dp(activity, 12f), dp(activity, 12f), dp(activity, 12f));
        card.setBackground(pillBg(ROW_BG, (accent & 0x00FFFFFF) | 0x66000000, 14f, activity));

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        TextView msg = label(activity, state.sima, title, 13.5f, TEXT_MAIN);
        msg.setTypeface(state.sima, Typeface.BOLD);
        msg.setMaxLines(2);
        msg.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(msg, new LinearLayout.LayoutParams(full));
        if (body.length() > 0) {
            TextView sub = label(activity, state.sima, body, 12f, 0xFFC5D0D6);
            sub.setMaxLines(3);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(full);
            sl.topMargin = dp(activity, 2f);
            col.addView(sub, sl);
        }
        TextView when = label(activity, state.sima,
                "من إدارة سيكرام" + (saved.optLong("created_at", 0L) > 0L ? " · " + timeAgo(saved.optLong("created_at", 0L)) : ""),
                10.5f, TEXT_MUTED);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(full);
        wl.topMargin = dp(activity, 4f);
        col.addView(when, wl);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView icon = label(activity, state.sima, "!", 17f, accent);
        icon.setTypeface(state.sima, Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(pillBg((accent & 0x00FFFFFF) | 0x2E000000, 0, 10f, activity));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(activity, 38f), dp(activity, 38f));
        il.leftMargin = dp(activity, 10f);
        card.addView(icon, il);

        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(title);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    new android.app.AlertDialog.Builder(activity)
                            .setTitle(title)
                            .setMessage(body.length() > 0 ? body : title)
                            .setPositiveButton("تم", null)
                            .show();
                } catch (Throwable ignored) { }
            }
        });
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(dp(activity, 12f), 0, dp(activity, 12f), dp(activity, 8f));
        state.list.addView(card, cardLp);
    }

    private static JSONArray readArray(
            Context context,
            String prefName,
            String key
    ) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    prefName, Context.MODE_PRIVATE);
            String raw = prefs.getString(key, "[]");
            if (raw == null || raw.trim().length() == 0) {
                return new JSONArray();
            }
            return new JSONArray(raw);
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }

    private static void loadPoster(
            final Activity activity,
            final ImageView target,
            String rawUrl
    ) {
        if (target == null) return;

        final String url = normalizeUrl(rawUrl);
        target.setTag(url);
        if (url.length() == 0) return;

        Bitmap cached = IMAGE_CACHE.get(url);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            return;
        }

        IMAGE_IO.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                InputStream input = null;
                try {
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(15000);
                    connection.setUseCaches(true);
                    connection.setInstanceFollowRedirects(true);
                    connection.connect();

                    int code = connection.getResponseCode();
                    if (code < 200 || code >= 300) return;

                    input = new BufferedInputStream(connection.getInputStream());
                    final Bitmap bitmap = BitmapFactory.decodeStream(input);
                    if (bitmap == null) return;

                    IMAGE_CACHE.put(url, bitmap);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            if (activity.isFinishing()) return;
                            Object tag = target.getTag();
                            if (url.equals(tag)) {
                                target.setImageBitmap(bitmap);
                            }
                        }
                    });
                } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "run", ignored); } finally {
                    try {
                        if (input != null) input.close();
                    } catch (Throwable ignored) { android.util.Log.w("CigramInjected", "run", ignored); }
                    if (connection != null) connection.disconnect();
                }
            }
        });
    }

    private static View findByIdName(Activity activity, String idName) {
        if (activity == null || idName == null) return null;
        int id = activity.getResources().getIdentifier(
                idName, "id", activity.getPackageName());
        if (id == 0) return null;
        return activity.findViewById(id);
    }

    private static TextView asTextView(View view) {
        return view instanceof TextView ? (TextView) view : null;
    }

    private static LinearLayout asLinearLayout(View view) {
        return view instanceof LinearLayout ? (LinearLayout) view : null;
    }

    private static SwipeRefreshLayout asSwipeRefresh(View view) {
        return view instanceof SwipeRefreshLayout ? (SwipeRefreshLayout) view : null;
    }

    private static Typeface loadSima(Context context) {
        try {
            return CigramTypefaces.get(context.getAssets(), CigramTypefaces.APP_FONT);
        } catch (Throwable ignored) {
            return Typeface.DEFAULT;
        }
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static String normalizeUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("//")) value = "https:" + value;
        return value.replace(" ", "%20");
    }

    private static int dp(Context context, float value) {
        return Math.round(
                value * context.getResources().getDisplayMetrics().density);
    }

    private static final class PageState {
        Activity activity;
        SwipeRefreshLayout swipe;
        View scroll;
        LinearLayout body;
        LinearLayout list;
        TextView empty;
        View permissionBanner;
        Typeface sima;
        String tab = "all";          // «الجديد» filter: all / movie / series
        boolean expanded;            // «عرض المزيد» pressed
        java.util.ArrayList<Entry> entries = new java.util.ArrayList<Entry>();
    }

    /** One film or series in the «الجديد» section (a series shows its newest episode). */
    private static final class Entry {
        boolean series;
        JSONObject item;
        String title = "";
        String poster = "";
        String episode = "";
        int season;
        long stamp;
    }
}
