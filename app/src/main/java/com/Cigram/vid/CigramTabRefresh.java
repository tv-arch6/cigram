package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;

/**
 * 18.3: this class still decides WHAT each page asks for, but no longer forces anything: the catalogue and the channel list
 * follow {@link CigramRefreshPolicy} (TTL per reason, one request at a time, exponential back-off after failures), so
 * switching between Home, Search and Channels does not download the same data again.
 *
 * Pulls the latest updates every time one of the four bottom-bar pages is shown (pressing its
 * button, or coming back to it), so a new film / series / episode, notification, announcement,
 * request answer or channel is there without pulling to refresh.
 *
 *   الرئيسية  : catalogue (films, series, sections), announcements, new-episode alerts, the bell
 *   البحث     : catalogue + the search index
 *   القنوات   : channel list
 *   الحساب    : announcements, request answers, new-episode alerts, pending profile edits
 *
 * Everything runs on background threads inside the helpers it calls; this class only decides what
 * to ask for. Each page is refreshed at most once every {@link #MIN_GAP_MS}, so quickly switching
 * back and forth never floods the server.
 */
final class CigramTabRefresh {

    private static final long MIN_GAP_MS = 20000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final HashMap<String, Long> LAST = new HashMap<String, Long>();

    private CigramTabRefresh() { }

    static boolean isTab(Activity activity) {
        if (activity == null) return false;
        String name = activity.getClass().getSimpleName();
        return "MainActivity".equals(name) || "SearchXmlActivity".equals(name)
                || "ChannelsActivity".equals(name) || "AccountActivity".equals(name);
    }

    /** Call when a tab page comes to the front (onResume). Safe to call as often as you like. */
    static void onTabShown(final Activity activity) {
        if (!isTab(activity) || activity.isFinishing()) return;
        final String name = activity.getClass().getSimpleName();
        long now = android.os.SystemClock.elapsedRealtime();
        synchronized (LAST) {
            Long last = LAST.get(name);
            if (last != null && now - last < MIN_GAP_MS) return;
            LAST.put(name, now);
        }
        final Context app = activity.getApplicationContext();
        final boolean home = "MainActivity".equals(name);
        final boolean channels = "ChannelsActivity".equals(name);
        final boolean account = "AccountActivity".equals(name);

        try {
            // New films / series / episodes / sections. The cold-start download already did this.
            if (home) {
                // 18.3: the shared CigramRefreshPolicy decides (TTL, single flight, back-off); this used to force a download.
                // Search never triggers it: opening the search page loads and fetches nothing.
                CigramStage1InitProvider.refreshCatalogNow(app, null);
            }
        } catch (Throwable ignored) { }

        try {
            if (channels) CigramChannelStore.refresh(app, false);   // 18.3: was forced on every tab switch; the store's own TTL + back-off apply
        } catch (Throwable ignored) { }

        try {
            if (home || account) {
                // admin messages -> notifications page, then the bell on Home shows the new count
                CigramPush.syncAnnouncements(app, true, new CigramPush.ResultCallback() {
                    @Override public void done(boolean added) { updateBell(activity, home); }
                });
                // new episodes of followed series (+ the alert) and new-release alerts
                CigramStage1InitProvider.runStage6UserRefresh(app, new Runnable() {
                    @Override public void run() { updateBell(activity, home); }
                });
            }
        } catch (Throwable ignored) { }

        try {
            if (account) CigramRequests.refresh(app, true, null);   // answers to «طلب فيلم / مسلسل»
        } catch (Throwable ignored) { }
    }

    private static void updateBell(final Activity activity, boolean home) {
        if (!home) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                try {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        CigramSketchwareNotifications.bindHomeBell(activity);
                    }
                } catch (Throwable ignored) { }
            }
        });
    }
}
