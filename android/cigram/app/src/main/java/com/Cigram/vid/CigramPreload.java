package com.Cigram.vid;

import android.app.ActivityManager;
import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;

/**
 * v10.44: gets the other pages ready in the background, once per app process, after the home page
 * has drawn. A page is a separate Activity, and the first time it opens most of the wait is
 * loading and verifying its (large) code and reading its layout. Both are done here on a low
 * priority thread, so the first press on a tab is as quick as the later ones.
 *
 * Their data is already prefetched elsewhere (channels list and logos by the home page, the search
 * index by the catalog loader, the account by CigramSync). The pages themselves are not started in
 * the background: an Activity cannot be created without showing it, which would flash on screen.
 */
final class CigramPreload {
    private static final String TAG = "CigramPreload";
    private static boolean started;

    /** Tab pages first, then the screens opened from them. */
    private static final String[] ACTIVITIES = {
            "ChannelsActivity", "SearchXmlActivity", "AccountActivity", "DetailsActivity", "PlayerActivity"};
    /** Layout files of those pages (res/layout name without .xml). */
    private static final String[] LAYOUTS = {
            "channels", "search_xml", "account", "details", "player", "movie_item"};

    private CigramPreload() { }

    static void warm(final Activity home) {
        if (home == null || started) return;
        final Context app = home.getApplicationContext();
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null && am.isLowRamDevice()) return;   // do not spend memory on a weak phone
        } catch (Throwable ignored) { }
        started = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    Thread.sleep(2200L);              // let the home page finish drawing first
                    final String pkg = app.getPackageName();
                    final ClassLoader loader = app.getClassLoader();
                    for (String name : ACTIVITIES) {
                        try {
                            Class.forName(pkg + "." + name, true, loader);
                        } catch (Throwable ignored) { }
                        Thread.sleep(120L);
                    }
                    inflateLayouts(app, pkg);
                } catch (Throwable error) {
                    Log.w(TAG, "warm-up stopped", error);
                }
            }
        }, "cigram-preload");
        t.setDaemon(true);
        t.start();
    }

    /** Reads each page layout once (resource tables, drawables), then drops the views. */
    private static void inflateLayouts(Context app, String pkg) {
        try {
            if (Looper.myLooper() == null) Looper.prepare();
        } catch (Throwable ignored) { }
        int theme = app.getResources().getIdentifier("CigramDarkTheme", "style", pkg);
        Context themed = new ContextThemeWrapper(app,
                theme != 0 ? theme : android.R.style.Theme_Material_NoActionBar);
        LayoutInflater inflater = LayoutInflater.from(themed);
        for (String name : LAYOUTS) {
            try {
                int id = app.getResources().getIdentifier(name, "layout", pkg);
                if (id != 0) inflater.inflate(id, null);
                Thread.sleep(80L);
            } catch (Throwable ignored) { }
        }
    }
}
