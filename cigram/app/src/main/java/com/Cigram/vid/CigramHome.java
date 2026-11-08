package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Home helpers: badges on poster cards (data-driven: item "badges" / admin overrides) and the
 * personalization helpers used by the dynamic sections (stable per process).
 *
 * v10.39: the home sections «الجديد هذا الأسبوع» and «لأنك شاهدت …» were removed for good
 * (they had been switched off since v10.34).
 */
public final class CigramHome {

    private static final String TAG_BADGES = "cigram_v23_badges";
    // Resolved once: getIdentifier() per card (during badge decoration) added up while scrolling.
    private static volatile int POSTER_ID = -1;

    private static CigramUserData.Affinity frozenAffinity = null;

    private CigramHome() { }

    // --------------------------------------------------------------- entry

    public static void bind(final Activity activity) {
        if (activity == null) return;
        try {
            CigramSync.onAppOpen(activity);
            CigramFeatured.refreshIfStale(activity);
            CigramReport.flushQueue(activity);
            CigramPreload.warm(activity);   // v10.44: the other pages get ready in the background
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------ personalization

    public static CigramUserData.Affinity affinity(Context context) {
        if (frozenAffinity == null) {
            CigramUserData.Affinity a = CigramUserData.affinity(context);
            if (!a.hasEnough()) return a;          // not frozen while there is no data
            frozenAffinity = a;
        }
        return frozenAffinity;
    }

    public static boolean personalizationReady(Context context) {
        return affinity(context).hasEnough();
    }

    /** 0..1: how well the items of a dynamic section match the viewer's taste. */
    public static double sectionScore(Context context, Collection<JSONObject> items) {
        CigramUserData.Affinity a = affinity(context);
        if (!a.hasEnough() || items == null || items.isEmpty()) return 0d;
        String type = a.preferredType();
        int match = 0;
        int total = 0;
        for (JSONObject item : items) {
            total++;
            boolean typeOk = type.length() > 0 && type.equals(CigramContent.typeOf(item));
            boolean genreOk = a.topGenre.length() > 0
                    && CigramContent.genres(item).contains(a.topGenre);
            if (typeOk || genreOk) match++;
        }
        return total == 0 ? 0d : match / (double) total;
    }

    // --------------------------------------------------------------- badges

    /** Overlays the data-driven badges on the poster of a movie_item card. */
    public static void decorateCard(Activity activity, View cell, JSONObject item) {
        if (activity == null || cell == null || item == null) return;
        List<String> labels = onceNew(activity, item, cardBadges(item, 2));
        if (labels.isEmpty()) return;
        int id = POSTER_ID;
        if (id == -1) {
            id = activity.getResources().getIdentifier("imageview_poster", "id", activity.getPackageName());
            POSTER_ID = id;
        }
        View poster = id == 0 ? null : cell.findViewById(id);
        if (poster == null) return;
        ViewParent parent = poster.getParent();
        if (!(parent instanceof LinearLayout)) return;
        LinearLayout box = (LinearLayout) parent;
        if (box.findViewWithTag(TAG_BADGES) != null) return;
        int index = box.indexOfChild(poster);
        ViewGroup.LayoutParams old = poster.getLayoutParams();
        box.removeViewAt(index);
        FrameLayout frame = new FrameLayout(activity);
        frame.addView(poster, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                old == null ? -1 : old.width, -2);
        if (old instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) old;
            flp.setMargins(m.leftMargin, m.topMargin, m.rightMargin, m.bottomMargin);
        }
        box.addView(frame, index, flp);

        LinearLayout stack = new LinearLayout(activity);
        stack.setTag(TAG_BADGES);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        for (int i = 0; i < labels.size(); i++) {
            String[] parts = labels.get(i).split("\\|", 2);
            View image = "جديد".equals(parts[1]) ? newBadge(activity, 12) : null;
            stack.addView(image != null ? image
                    : badgeView(activity, parts[1], Integer.parseInt(parts[0]), 10f));
        }
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
        // v4.4: the inset was on the LEFT while the badge is pinned to the RIGHT, so it touched
        // the poster's rounded right edge.
        slp.setMargins(0, CigramStage1InitProvider.cigramDp(activity, 6),
                CigramStage1InitProvider.cigramDp(activity, 6), 0);
        frame.addView(stack, slp);
    }

    // ------------------------------------------------- v10.67: «جديد» once per user

    private static final String NEW_PREFS = "cigram_new_badge_v1";
    private static final int NEW_MAX = 3000;
    private static final Object NEW_LOCK = new Object();
    /** Titles whose «جديد» is on screen during this run: it stays while the user browses. */
    private static final java.util.HashSet<String> NEW_SESSION = new java.util.HashSet<String>();
    private static java.util.LinkedHashSet<String> newSeen;
    private static String newSeenOwner = "";

    /**
     * The «جديد» picture on a poster is shown to each user ONCE per title (and once per new
     * episode for «حلقة جديدة»): the first time it is drawn it is remembered for the account, and
     * on the next visit it is gone. During the same run it stays, so the user can actually see it.
     */
    private static List<String> onceNew(Context context, JSONObject item, List<String> labels) {
        if (labels == null || labels.isEmpty()) return labels;
        ArrayList<String> kept = new ArrayList<String>();
        for (int i = 0; i < labels.size(); i++) {
            String[] parts = labels.get(i).split("\\|", 2);
            boolean fresh = parts.length == 2 && "جديد".equals(parts[1]);
            boolean episode = parts.length == 2 && "حلقة جديدة".equals(parts[1]);
            if (!fresh && !episode) {
                kept.add(labels.get(i));
                continue;
            }
            String id = CigramContent.isSeries(item) ? CigramContent.seriesKey(item) : CigramContent.movieKey(item);
            if (id == null || id.length() == 0) id = CigramContent.norm(CigramContent.title(item));
            String key = (episode ? "ep:" + CigramContent.newestEpisodeAddedMsIfKnown(item) + ":" : "new:") + id;
            if (allowNew(context, key)) kept.add(labels.get(i));
        }
        return kept;
    }

    private static final android.os.Handler NEW_HANDLER = new android.os.Handler(android.os.Looper.getMainLooper());
    private static boolean newPersistScheduled;

    /** One coalesced background write of the "already seen" list, ~1.5 s after the first change. */
    private static void scheduleNewPersist(final Context app) {
        synchronized (NEW_LOCK) {
            if (newPersistScheduled) return;
            newPersistScheduled = true;
        }
        NEW_HANDLER.postDelayed(new Runnable() {
            @Override public void run() {
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            String owner;
                            String json;
                            synchronized (NEW_LOCK) {
                                newPersistScheduled = false;
                                if (newSeen == null) return;
                                owner = newSeenOwner;
                                org.json.JSONArray arr = new org.json.JSONArray();
                                for (String k : newSeen) arr.put(k);
                                json = arr.toString();
                            }
                            app.getSharedPreferences(NEW_PREFS, Context.MODE_PRIVATE).edit()
                                    .putString("seen::" + owner, json).apply();
                        } catch (Throwable ignored) { }
                    }
                }, "cigram-new-persist").start();
            }
        }, 1500L);
    }

    private static boolean allowNew(Context context, String key) {
        if (context == null || key == null || key.length() == 0) return false;
        Context app = context.getApplicationContext();
        String owner;
        try { owner = CigramUserData.owner(app); } catch (Throwable e) { owner = "guest"; }
        synchronized (NEW_LOCK) {
            String full = owner + "|" + key;
            if (NEW_SESSION.contains(full)) return true;
            if (newSeen == null || !owner.equals(newSeenOwner)) {
                newSeen = new java.util.LinkedHashSet<String>();
                newSeenOwner = owner;
                try {
                    String raw = app.getSharedPreferences(NEW_PREFS, Context.MODE_PRIVATE)
                            .getString("seen::" + owner, "[]");
                    org.json.JSONArray arr = new org.json.JSONArray(raw == null ? "[]" : raw);
                    for (int i = 0; i < arr.length(); i++) newSeen.add(arr.optString(i));
                } catch (Throwable ignored) { }
            }
            if (newSeen.contains(key)) return false;
            newSeen.add(key);
            NEW_SESSION.add(full);
            while (newSeen.size() > NEW_MAX) {
                java.util.Iterator<String> it = newSeen.iterator();
                it.next();
                it.remove();
            }
            scheduleNewPersist(app);   // was: serialise up to 3000 keys + write prefs for EVERY new card, on the main thread
            return true;
        }
    }

    /** "color|label" entries: admin badges first, then automatic "new". */
    public static List<String> cardBadges(JSONObject item, int max) {
        ArrayList<String> out = new ArrayList<String>();
        List<String> ids = CigramFeatured.badgesFor(item);
        for (int i = 0; i < ids.size() && out.size() < max; i++) {
            String label = CigramContent.badgeLabel(ids.get(i));
            if (label.length() > 0) out.add(CigramContent.badgeColor(ids.get(i)) + "|" + label);
        }
        if (out.size() < max && !ids.contains("new")) {
            long now = System.currentTimeMillis();
            if (CigramContent.isRecent(CigramContent.addedAtMs(item), now)) {
                out.add(CigramContent.badgeColor("new") + "|جديد");
            } else if (CigramContent.isSeries(item)
                    && CigramContent.isRecent(CigramContent.newestEpisodeAddedMsIfKnown(item), now)) {
                out.add(CigramContent.badgeColor("new") + "|حلقة جديدة");
            }
        }
        return out;
    }

    /**
     * v10.42: the green word «جديد» is the picture "now" (yellow, rounded corners) instead.
     * Null when the picture is not in the app, so the caller keeps the old text badge.
     */
    public static View newBadge(Context context, int heightDp) {
        try {
            android.graphics.drawable.Drawable d = CigramImdbIcon.roundedNamed(context, "now", 0.28f);
            if (d == null) return null;
            int h = CigramStage1InitProvider.cigramDp(context, heightDp);
            int iw = d.getIntrinsicWidth();
            int ih = d.getIntrinsicHeight();
            int w = iw > 0 && ih > 0 ? Math.round(h * (iw / (float) ih)) : h * 3;
            android.widget.ImageView v = new android.widget.ImageView(context);
            v.setImageDrawable(d);
            v.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
            v.setContentDescription("جديد");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.bottomMargin = CigramStage1InitProvider.cigramDp(context, 3);
            v.setLayoutParams(lp);
            return v;
        } catch (Throwable error) {
            return null;
        }
    }

    public static TextView badgeView(Context context, String label, int color, float sp) {
        TextView t = new TextView(context);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setTypeface(CigramStage1InitProvider.cigramFont(context), Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        int ph = CigramStage1InitProvider.cigramDp(context, 6);
        int pv = CigramStage1InitProvider.cigramDp(context, 2);
        t.setPadding(ph, pv, ph, pv);
        t.setBackground(CigramStage1InitProvider.cigramRounded(color,
                CigramStage1InitProvider.cigramDp(context, 6)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.bottomMargin = CigramStage1InitProvider.cigramDp(context, 3);
        t.setLayoutParams(lp);
        return t;
    }
}
