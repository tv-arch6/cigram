package com.Cigram.vid;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Home-screen widget "سيكرام: عناوين مميزة": the posters of the app (the admin's carousel first, then the newest films and series)
 * moving by themselves on the phone's home screen. Every 2 minutes it looks for new titles and redraws itself only if something
 * changed. Tapping a poster opens that film / series in the app ({@link CigramWidgetOpenActivity}).
 *
 * How it works (no RemoteViewsService on purpose: that kind of widget fails to load on several launchers):
 *  - a ViewFlipper holds one child per poster and flips them by itself every 5 s;
 *  - an inexact alarm (every 2 minutes, set again each time) wakes {@link #onReceive}, which does the work off the main thread;
 *  - posters are cards kept on disk, so a refresh only downloads new ones.
 * The picker preview shows the real posters on Android 15+ (setWidgetPreview) and a drawn card on older versions.
 */
public class CigramCarouselWidget extends AppWidgetProvider {

    static final String ACTION_TICK = "com.Cigram.vid.WIDGET_TICK";
    static final String ACTION_NOW = "com.Cigram.vid.WIDGET_NOW";
    static final long TICK_MS = 2L * 60L * 1000L;
    private static final String PREF = "cigram_widget_state";
    private static final String[] SOURCES = {
            "https://cigram-admin-api.wwq-mixtv.workers.dev/carousel",
            "https://pub-eabe41d14a0644e5800344c99ef4d971.r2.dev/cigram_carousel.json"
    };
    private static final String MOVIES = "https://cigram-admin-api.wwq-mixtv.workers.dev/movies";
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    // ------------------------------------------------------------------ provider callbacks

    @Override
    public void onEnabled(Context context) {
        schedule(context);
    }

    @Override
    public void onDisabled(Context context) {
        cancel(context);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) paintStatic(context, manager, id);      // the frame at once, posters follow
        schedule(context);
        refreshAsync(context, true);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, android.os.Bundle options) {
        refreshAsync(context, true);
    }

    /** Keeps the process alive while the background work of this broadcast runs (taken by refreshAsync). */
    private static volatile PendingResult pendingResult;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        try { pendingResult = goAsync(); } catch (Throwable t) { pendingResult = null; }
        try {
            if (ACTION_TICK.equals(action) || ACTION_NOW.equals(action)) {
                schedule(context);
                refreshAsync(context, ACTION_NOW.equals(action));
            } else {
                super.onReceive(context, intent);         // APPWIDGET_UPDATE ... -> onUpdate -> refreshAsync
            }
        } finally {
            PendingResult left = pendingResult;           // nobody started work for this broadcast: release it now
            if (left != null) { pendingResult = null; try { left.finish(); } catch (Throwable ignored) { } }
        }
    }

    // ------------------------------------------------------------------ the 2-minute clock

    private static PendingIntent tickIntent(Context c) {
        Intent i = new Intent(c, CigramCarouselWidget.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    static void schedule(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long at = SystemClock.elapsedRealtime() + TICK_MS;
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, at, tickIntent(c));
            else am.set(AlarmManager.ELAPSED_REALTIME, at, tickIntent(c));
        } catch (Throwable ignored) { }
    }

    static void cancel(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(tickIntent(c));
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ work

    private static int[] ids(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, CigramCarouselWidget.class));
            return ids == null ? new int[0] : ids;
        } catch (Throwable t) {
            return new int[0];
        }
    }

    /** Called by Home after it loaded a fresh carousel / catalogue: the widget follows at once. */
    public static void refreshAll(Context context) {
        try {
            Context app = context.getApplicationContext();
            if (ids(app).length > 0) refreshAsync(app, false);
        } catch (Throwable ignored) { }
    }

    private static void refreshAsync(final Context context, final boolean force) {
        final Context app = context.getApplicationContext();
        final PendingResult pending = pendingResult;
        pendingResult = null;
        POOL.execute(new Runnable() {
            @Override public void run() {
                try {
                    refresh(app, force);
                } catch (Throwable t) {
                    android.util.Log.w("CigramWidget", "refresh failed", t);
                } finally {
                    if (pending != null) { try { pending.finish(); } catch (Throwable ignored) { } }
                }
            }
        });
    }

    /** Looks for new titles; redraws the widget only when the posters changed (or when forced). */
    static void refresh(Context c, boolean force) {
        int[] ids = ids(c);
        if (ids.length == 0) { cancel(c); return; }
        long deadline = SystemClock.elapsedRealtime() + 9000L;

        SharedPreferences prefs = c.getSharedPreferences("cigram_carousel_cache", Context.MODE_PRIVATE);
        String carouselJson = download(SOURCES, 4000, true);
        if (carouselJson != null) prefs.edit().putString("json", carouselJson).apply();
        else carouselJson = prefs.getString("json", "");

        SharedPreferences catalogPrefs = CigramCatalogPrefs.of(c);
        String catalog = catalogPrefs.getString("json", "");
        if (catalog == null || catalog.length() == 0) {              // the app was never opened: ask the server once
            String fresh = download(new String[]{MOVIES}, 6000, false);
            if (fresh != null) catalog = fresh;
        }

        List<CigramWidgetData.Slide> featured = CigramWidgetData.parse(carouselJson);
        if (featured.size() > CigramWidgetData.FEATURED_MAX) featured = new ArrayList<CigramWidgetData.Slide>(featured.subList(0, CigramWidgetData.FEATURED_MAX));
        List<CigramWidgetData.Slide> newest = CigramWidgetData.fromCatalog(catalog, CigramWidgetData.MAX_SLIDES * 2);
        List<CigramWidgetData.Slide> slides = CigramWidgetData.merge(featured, newest, CigramWidgetData.MAX_SLIDES);

        SharedPreferences st = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String sig = CigramWidgetData.signature(slides);
        if (!force && sig.equals(st.getString("sig", "")) && st.getBoolean("painted", false)) return;   // nothing new: no flicker

        // posters: from disk at once, the missing ones downloaded in parallel inside the time budget
        final Context app = c;
        final Bitmap[] bitmaps = new Bitmap[slides.size()];
        List<Future<?>> jobs = new ArrayList<Future<?>>();
        for (int i = 0; i < slides.size(); i++) {
            final int index = i;
            final String url = slides.get(i).imageUrl;
            bitmaps[i] = CigramWidgetImages.cached(c, url);
            if (bitmaps[i] == null) {
                jobs.add(POOL.submit(new Callable<Object>() {
                    @Override public Object call() {
                        bitmaps[index] = CigramWidgetImages.card(app, url);
                        return null;
                    }
                }));
            }
        }
        for (Future<?> j : jobs) {
            long left = deadline - SystemClock.elapsedRealtime();
            if (left <= 0) break;
            try { j.get(left, TimeUnit.MILLISECONDS); } catch (Throwable ignored) { }
        }

        List<CigramWidgetData.Slide> ready = new ArrayList<CigramWidgetData.Slide>();
        List<Bitmap> pics = new ArrayList<Bitmap>();
        for (int i = 0; i < slides.size(); i++) {
            if (bitmaps[i] != null) { ready.add(slides.get(i)); pics.add(bitmaps[i]); }
        }

        AppWidgetManager m = AppWidgetManager.getInstance(c);
        RemoteViews last = null;
        for (int id : ids) {
            last = build(c, id, ready, pics);
            try { m.updateAppWidget(id, last); } catch (Throwable t) { android.util.Log.w("CigramWidget", "update failed", t); }
        }
        boolean complete = ready.size() == slides.size();
        st.edit().putString("sig", complete ? sig : "").putBoolean("painted", !ready.isEmpty()).apply();   // an unfinished set is retried next tick
        if (last != null && !ready.isEmpty()) setPreview(c, m, ready, pics);
    }

    /** First answer from the list that is JSON (a bare array is wrapped like the Home page does). */
    private static String download(String[] urls, int timeoutMs, boolean wrap) {
        for (String base : urls) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(base + (base.contains("?") ? "&" : "?") + "ts=" + System.currentTimeMillis()).openConnection();
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs + 3000);
                conn.setRequestProperty("Accept", "application/json");
                if (conn.getResponseCode() != 200) continue;
                InputStream in = conn.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() + n > 8 * 1024 * 1024) break;
                    out.write(buf, 0, n);
                }
                in.close();
                String body = out.toString("UTF-8").trim();
                if (body.startsWith("[")) {
                    new org.json.JSONArray(body);
                    if (!wrap) return body;
                    org.json.JSONObject w = new org.json.JSONObject();
                    w.put("enabled", true);
                    w.put("items", new org.json.JSONArray(body));
                    return w.toString();
                }
                if (body.startsWith("{")) {
                    new org.json.JSONObject(body);
                    return body;
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) { } }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ drawing

    /** The empty frame (before the first poster is ready): "open Cigram" text, tap opens the app. */
    private static void paintStatic(Context c, AppWidgetManager m, int id) {
        try {
            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.cigram_widget_carousel);
            rv.setOnClickPendingIntent(R.id.cg_w_empty, homeIntent(c, id));
            m.updateAppWidget(id, rv);
        } catch (Throwable t) {
            android.util.Log.w("CigramWidget", "static paint failed", t);
        }
    }

    private static PendingIntent homeIntent(Context c, int id) {
        Intent home = new Intent(c, MainActivity.class);
        return PendingIntent.getActivity(c, 100000 + id, home, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    /** One widget: a ViewFlipper with a child per poster, each child opens its own title. */
    static RemoteViews build(Context c, int widgetId, List<CigramWidgetData.Slide> slides, List<Bitmap> pics) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.cigram_widget_carousel);
        rv.removeAllViews(R.id.cg_w_flipper);
        if (slides.isEmpty()) {
            rv.setViewVisibility(R.id.cg_w_flipper, View.GONE);
            rv.setViewVisibility(R.id.cg_w_empty, View.VISIBLE);
            rv.setOnClickPendingIntent(R.id.cg_w_empty, homeIntent(c, widgetId));
            return rv;
        }
        rv.setViewVisibility(R.id.cg_w_empty, View.GONE);
        rv.setViewVisibility(R.id.cg_w_flipper, View.VISIBLE);
        for (int i = 0; i < slides.size(); i++) {
            CigramWidgetData.Slide s = slides.get(i);
            RemoteViews item = new RemoteViews(c.getPackageName(), R.layout.cigram_widget_item);
            item.setImageViewBitmap(R.id.cg_w_img, pics.get(i));
            item.setTextViewText(R.id.cg_w_title, s.title);
            item.setTextViewText(R.id.cg_w_meta, s.meta);
            item.setOnClickPendingIntent(R.id.cg_w_item_root, openIntent(c, widgetId, i, s));
            rv.addView(R.id.cg_w_flipper, item);
        }
        return rv;
    }

    private static PendingIntent openIntent(Context c, int widgetId, int index, CigramWidgetData.Slide s) {
        Intent open = new Intent(c, CigramWidgetOpenActivity.class);
        open.setAction("com.Cigram.vid.WIDGET_OPEN");
        open.setData(Uri.parse("cigram-widget://slide/" + widgetId + "/" + index + "/" + Integer.toHexString(s.details.hashCode())));   // unique per slide
        open.putExtra(CigramWidgetOpenActivity.EXTRA_ITEM, s.details);
        return PendingIntent.getActivity(c, widgetId * 31 + index, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    /** Android 15+: the widget picker shows the REAL posters of the app (generated preview). Older versions use the drawn card. */
    private static void setPreview(Context c, AppWidgetManager m, List<CigramWidgetData.Slide> slides, List<Bitmap> pics) {
        if (Build.VERSION.SDK_INT < 35) return;
        try {
            SharedPreferences st = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String sig = CigramWidgetData.signature(slides);
            if (sig.equals(st.getString("preview_sig", ""))) return;      // the system limits how often it can be set
            RemoteViews rv = build(c, 0, slides.subList(0, Math.min(3, slides.size())), pics.subList(0, Math.min(3, pics.size())));
            if (m.setWidgetPreview(new ComponentName(c, CigramCarouselWidget.class), android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, rv)) {
                st.edit().putString("preview_sig", sig).apply();
            }
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ "add widget" row

    /** Asks the launcher to pin the widget (Android 8+), otherwise explains the manual way. */
    public static void requestPin(Activity a) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(a);
            if (Build.VERSION.SDK_INT >= 26 && m.isRequestPinAppWidgetSupported()) {
                refreshAsync(a, true);       // posters ready (and cached) before the launcher shows its preview
                m.requestPinAppWidget(new ComponentName(a, CigramCarouselWidget.class), null, null);
                return;
            }
        } catch (Throwable ignored) { }
        CigramSilent.toast(a, "اضغط مطولاً على الشاشة الرئيسية ← ويدجت ← سيكرام", android.widget.Toast.LENGTH_LONG);
    }
}
