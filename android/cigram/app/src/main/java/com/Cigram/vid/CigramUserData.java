package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single source of truth for the data that belongs to the viewer:
 * watch history (movies + episodes), "seen" episodes, last opened season,
 * and the modification clocks of the lists that already exist elsewhere
 * (watch later, series subscriptions, favorites).
 *
 * Local-first: everything is read/written in memory + SharedPreferences, the
 * network (CigramSync) only mirrors it. Data is namespaced per owner
 * (logged-in e-mail, or "guest"), so a guest history is never lost and is merged
 * into the account on first sync instead of being overwritten.
 */
public final class CigramUserData {

    public static final String PREFS = "cigram_user_data_v1";
    /** Viewing history is permanent: this is only a safety ceiling, never reached in practice. */
    public static final int MAX_HISTORY = 20000;
    public static final int MAX_SEEN = 60000;
    public static final int MAX_SEASONS = 5000;
    private static final long FLUSH_DELAY_MS = 3000L;
    /** An episode counts as "started" (new badge disappears) after this much playback. */
    private static final long SEEN_AFTER_MS = 30000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    // in-memory copy of the current owner
    private static String loadedOwner = null;
    private static final ArrayList<JSONObject> HISTORY = new ArrayList<JSONObject>();
    private static final HashMap<String, Long> SEEN = new HashMap<String, Long>();
    private static final HashMap<String, long[]> SEASONS = new HashMap<String, long[]>(); // key -> {season, ts}
    private static long clearedAt = 0L;
    private static long wlTs = 0L;
    private static long subTs = 0L;
    private static long favTs = 0L;
    private static boolean flushScheduled = false;
    private static Context appContext = null;

    private CigramUserData() { }

    // ----------------------------------------------------------------- owner

    public static boolean isLoggedIn(Context context) {
        return owner(context).length() > 0 && !"guest".equals(owner(context));
    }

    public static String token(Context context) {
        try {
            String t = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
                    .getString("token", "");
            return t == null ? "" : t.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String email(Context context) {
        try {
            String e = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
                    .getString("email", "");
            return e == null ? "" : e.trim().toLowerCase(Locale.US);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Value of auth_session.user_id (may be empty). */
    public static String authUserId(Context context) {
        try {
            Object v = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
                    .getAll().get("user_id");
            return v == null ? "" : String.valueOf(v).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** "guest" or a stable account identity. */
    public static String owner(Context context) {
        if (context == null) return "guest";
        try {
            SharedPreferences auth = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE);
            Object raw = auth.getAll().get("logged_in");
            boolean logged;
            if (raw instanceof Boolean) {
                logged = ((Boolean) raw).booleanValue();
            } else {
                String s = raw == null ? "" : String.valueOf(raw).trim();
                logged = "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
            }
            String token = token(context);
            if (!logged && token.length() == 0) return "guest";
            String email = email(context);
            if (email.length() > 0) return email;
            String id = authUserId(context);
            if (id.length() > 0) return "id:" + id;
            return token.length() > 0 ? "t:" + Integer.toHexString(token.hashCode()) : "guest";
        } catch (Throwable ignored) {
            return "guest";
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------- load/save

    private static void ensureLoaded(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        String owner = owner(context);
        synchronized (LOCK) {
            if (owner.equals(loadedOwner)) return;
            flushNowLocked();                     // persist the previous owner first
            HISTORY.clear();
            SEEN.clear();
            SEASONS.clear();
            clearedAt = 0L;
            wlTs = 0L;
            subTs = 0L;
            favTs = 0L;
            loadedOwner = owner;
            SharedPreferences p = prefs(context);
            try {
                JSONArray h = new JSONArray(p.getString("history::" + owner, "[]"));
                for (int i = 0; i < h.length(); i++) {
                    JSONObject o = h.optJSONObject(i);
                    if (o != null) HISTORY.add(o);
                }
            } catch (Throwable ignored) { }
            try {
                JSONObject s = new JSONObject(p.getString("seen::" + owner, "{}"));
                Iterator<String> it = s.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    SEEN.put(k, Long.valueOf(s.optLong(k, 0L)));
                }
            } catch (Throwable ignored) { }
            try {
                JSONObject s = new JSONObject(p.getString("seasons::" + owner, "{}"));
                Iterator<String> it = s.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject v = s.optJSONObject(k);
                    if (v != null) SEASONS.put(k, new long[]{v.optLong("s", 0L), v.optLong("t", 0L)});
                }
            } catch (Throwable ignored) { }
            clearedAt = p.getLong("cleared::" + owner, 0L);
            wlTs = p.getLong("wl_ts::" + owner, 0L);
            subTs = p.getLong("sub_ts::" + owner, 0L);
            favTs = p.getLong("fav_ts::" + owner, 0L);
        }
    }

    private static void scheduleFlush() {
        synchronized (LOCK) {
            if (flushScheduled) return;
            flushScheduled = true;
        }
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                flush();
            }
        }, FLUSH_DELAY_MS);
    }

    /** Writes the in-memory state. Cheap; called by the debounce and by the player on stop. */
    public static void flush() {
        synchronized (LOCK) {
            flushScheduled = false;
            flushNowLocked();
        }
        try {
            if (appContext != null) CigramSync.requestSoon(appContext);
        } catch (Throwable ignored) { }
    }

    private static void flushNowLocked() {
        if (appContext == null || loadedOwner == null) return;
        try {
            JSONArray h = new JSONArray();
            for (int i = 0; i < HISTORY.size(); i++) h.put(HISTORY.get(i));
            JSONObject s = new JSONObject();
            for (Map.Entry<String, Long> e : SEEN.entrySet()) s.put(e.getKey(), e.getValue().longValue());
            JSONObject l = new JSONObject();
            for (Map.Entry<String, long[]> e : SEASONS.entrySet()) {
                JSONObject v = new JSONObject();
                v.put("s", e.getValue()[0]);
                v.put("t", e.getValue()[1]);
                l.put(e.getKey(), v);
            }
            prefs(appContext).edit()
                    .putString("history::" + loadedOwner, h.toString())
                    .putString("seen::" + loadedOwner, s.toString())
                    .putString("seasons::" + loadedOwner, l.toString())
                    .putLong("cleared::" + loadedOwner, clearedAt)
                    .putLong("wl_ts::" + loadedOwner, wlTs)
                    .putLong("sub_ts::" + loadedOwner, subTs)
                    .putLong("fav_ts::" + loadedOwner, favTs)
                    .apply();
        } catch (Throwable ignored) { }
    }

    // --------------------------------------------------------------- history

    private static int toInt(Object v) {
        if (v == null) return 0;
        String s = String.valueOf(v).replaceAll("[^0-9]", "");
        if (s.length() == 0 || s.length() > 6) return 0;
        try { return Integer.parseInt(s); } catch (Throwable ignored) { return 0; }
    }

    /**
     * Called by the player while something plays (every few seconds), when it ends,
     * and when an episode is switched.
     *
     * info keys: type, id, title, poster, backdrop, video, series_id, series_title,
     *            season, episode, category, year
     */
    public static void record(Context context, JSONObject info, long position, long duration,
                              boolean finished) {
        if (context == null || info == null) return;
        try {
            ensureLoaded(context);
            int season = toInt(info.opt("season"));
            int episode = toInt(info.opt("episode"));
            String type = CigramContent.norm(CigramContent.text(info, "type"));
            boolean isEpisode = "series".equals(type) || "episode".equals(type)
                    || season > 0 || episode > 0;
            String seriesTitle = CigramContent.text(info, "series_title");
            if (seriesTitle.length() == 0) seriesTitle = CigramContent.text(info, "title");
            String sKey = CigramContent.seriesKeyOfTitle(seriesTitle);
            String key;
            if (isEpisode) {
                if (sKey.length() == 0) return;
                key = CigramContent.episodeKey(sKey, season, episode);
            } else {
                key = CigramContent.movieKey(info);
            }
            if (key.length() == 0) return;
            CigramAnalytics.playProgress(context, key, isEpisode ? "episode" : "movie", position, duration, finished,
                    isEpisode ? CigramContent.text(info, "series_id") : "", isEpisode ? season : 0, isEpisode ? episode : 0);

            long now = System.currentTimeMillis();
            boolean started = finished || position >= SEEN_AFTER_MS
                    || (duration > 0L && position * 10L >= duration);
            synchronized (LOCK) {
                JSONObject old = null;
                for (int i = 0; i < HISTORY.size(); i++) {
                    if (key.equals(HISTORY.get(i).optString("key"))) {
                        old = HISTORY.remove(i);
                        break;
                    }
                }
                JSONObject e = new JSONObject();
                e.put("key", key);
                e.put("type", isEpisode ? "episode" : "movie");
                e.put("id", CigramContent.text(info, "id"));
                e.put("title", CigramContent.text(info, "title"));
                e.put("poster", CigramContent.text(info, "poster", "backdrop"));
                e.put("backdrop", CigramContent.text(info, "backdrop"));
                e.put("video", CigramContent.text(info, "video"));
                e.put("series_id", CigramContent.text(info, "series_id"));
                e.put("series_title", isEpisode ? seriesTitle : "");
                e.put("season", isEpisode ? (season <= 0 ? 1 : season) : 0);
                e.put("episode", isEpisode ? episode : 0);
                e.put("last_position", finished ? 0L : Math.max(0L, position));
                // an unknown duration (0 / TIME_UNSET while a stream is still loading) never wipes a known one: the progress bar stays
                long knownDuration = Math.max(0L, duration);
                if (knownDuration <= 0L && old != null) knownDuration = Math.max(0L, old.optLong("duration", 0L));
                e.put("duration", knownDuration);
                e.put("finished", finished
                        || (knownDuration > 0L && duration > 0L && position >= 0L && duration - position <= 15000L));
                e.put("watched_at", now);
                String category = CigramContent.text(info, "category");
                String year = CigramContent.text(info, "year");
                if (old != null) {
                    if (category.length() == 0) category = old.optString("category");
                    if (year.length() == 0) year = old.optString("year");
                    if (e.optString("poster").length() == 0) e.put("poster", old.optString("poster"));
                }
                if (category.length() == 0 || year.length() == 0) {
                    JSONObject cat = CigramContent.find(isEpisode ? "series" : "movie",
                            isEpisode ? CigramContent.text(info, "series_id") : CigramContent.text(info, "id"),
                            seriesTitle);
                    if (cat != null) {
                        if (category.length() == 0) category = CigramContent.text(cat, "category", "genre", "genres");
                        if (year.length() == 0) year = CigramContent.text(cat, "year", "release_year");
                        String catPoster = CigramContent.text(cat, "poster", "series_poster", "image");
                        if (catPoster.length() > 0 && (isEpisode || e.optString("poster").length() == 0)) {
                            e.put("poster", catPoster);   // vertical poster fits the cards
                        }
                    }
                }
                e.put("category", category);
                e.put("year", year);
                HISTORY.add(0, e);
                while (HISTORY.size() > MAX_HISTORY) HISTORY.remove(HISTORY.size() - 1);

                if (started) putSeen(key, now);
                if (isEpisode) {
                    SEASONS.put(sKey, new long[]{season <= 0 ? 1 : season, now});
                    trimSeasons();
                }
            }
            scheduleFlush();
        } catch (Throwable ignored) { }
    }

    private static void putSeen(String key, long ts) {
        SEEN.put(key, Long.valueOf(ts));
        if (SEEN.size() > MAX_SEEN) {
            String oldest = null;
            long oldestTs = Long.MAX_VALUE;
            for (Map.Entry<String, Long> en : SEEN.entrySet()) {
                if (en.getValue().longValue() < oldestTs) {
                    oldestTs = en.getValue().longValue();
                    oldest = en.getKey();
                }
            }
            if (oldest != null) SEEN.remove(oldest);
        }
    }

    private static void trimSeasons() {
        while (SEASONS.size() > MAX_SEASONS) {
            String oldest = null;
            long oldestTs = Long.MAX_VALUE;
            for (Map.Entry<String, long[]> en : SEASONS.entrySet()) {
                if (en.getValue()[1] < oldestTs) {
                    oldestTs = en.getValue()[1];
                    oldest = en.getKey();
                }
            }
            if (oldest == null) break;
            SEASONS.remove(oldest);
        }
    }

    /** Newest first. */
    public static ArrayList<JSONObject> history(Context context) {
        ensureLoaded(context);
        synchronized (LOCK) {
            return new ArrayList<JSONObject>(HISTORY);
        }
    }

    /** One row per movie, one row per series (its most recent episode). Newest first. */
    public static ArrayList<JSONObject> historyGrouped(Context context) {
        ArrayList<JSONObject> all = history(context);
        ArrayList<JSONObject> out = new ArrayList<JSONObject>();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < all.size(); i++) {
            JSONObject e = all.get(i);
            String g = "episode".equals(e.optString("type"))
                    ? CigramContent.seriesKeyOfTitle(e.optString("series_title"))
                    : e.optString("key");
            if (g.length() == 0 || !seen.add(g)) continue;
            out.add(e);
        }
        return out;
    }

    public static JSONObject entry(Context context, String key) {
        ensureLoaded(context);
        synchronized (LOCK) {
            for (int i = 0; i < HISTORY.size(); i++) {
                if (key.equals(HISTORY.get(i).optString("key"))) return HISTORY.get(i);
            }
        }
        return null;
    }

    /** 0..1, 1 means finished, 0 when unknown. */
    public static float progressOf(Context context, String key) {
        JSONObject e = entry(context, key);
        if (e == null) return 0f;
        if (e.optBoolean("finished", false)) return 1f;
        long d = e.optLong("duration", 0L);
        long p = e.optLong("last_position", 0L);
        if (d <= 0L || p <= 0L) return 0f;
        return Math.max(0f, Math.min(1f, p / (float) d));
    }

    public static boolean isSeen(Context context, String key) {
        if (key == null || key.length() == 0) return false;
        ensureLoaded(context);
        synchronized (LOCK) {
            return SEEN.containsKey(key);
        }
    }

    public static void markSeen(Context context, String key) {
        if (key == null || key.length() == 0) return;
        ensureLoaded(context);
        synchronized (LOCK) {
            putSeen(key, System.currentTimeMillis());
        }
        scheduleFlush();
    }

    public static int lastSeason(Context context, String seriesKey) {
        ensureLoaded(context);
        synchronized (LOCK) {
            long[] v = SEASONS.get(seriesKey);
            return v == null ? 0 : (int) v[0];
        }
    }

    public static void setLastSeason(Context context, String seriesKey, int season) {
        if (seriesKey == null || seriesKey.length() == 0 || season <= 0) return;
        ensureLoaded(context);
        synchronized (LOCK) {
            SEASONS.put(seriesKey, new long[]{season, System.currentTimeMillis()});
            trimSeasons();
        }
        scheduleFlush();
    }

    public static void removeHistory(Context context, String key) {
        ensureLoaded(context);
        synchronized (LOCK) {
            for (int i = 0; i < HISTORY.size(); i++) {
                if (key.equals(HISTORY.get(i).optString("key"))) {
                    HISTORY.remove(i);
                    break;
                }
            }
            // an explicit removal must survive a sync: raise the "cleared" clock only for this
            // entry by remembering it as cleared-before-now through clearedAt is too coarse,
            // so single removals are local until the next record() of the same key.
        }
        scheduleFlush();
    }

    /**
     * The viewing history of an account is permanent (v2.5): the user can no longer wipe it from
     * the UI, so this is intentionally a no-op. Use {@link #wipeAccount(Context)} when the
     * account itself is deleted.
     */
    public static void clearHistory(Context context) {
        // kept for source compatibility only
    }

    /** Account deleted on the server: remove every local trace of this owner. */
    public static void wipeAccount(Context context) {
        if (context == null) return;
        try {
            ensureLoaded(context);
            String owner;
            synchronized (LOCK) {
                owner = loadedOwner == null ? owner(context) : loadedOwner;
                HISTORY.clear();
                SEEN.clear();
                SEASONS.clear();
                clearedAt = 0L;
                wlTs = 0L;
                subTs = 0L;
                favTs = 0L;
                flushScheduled = false;
                loadedOwner = null;
            }
            SharedPreferences p = prefs(context);
            SharedPreferences.Editor ed = p.edit();
            for (String k : new java.util.ArrayList<String>(p.getAll().keySet())) {
                if (k.endsWith("::" + owner)) ed.remove(k);
            }
            ed.apply();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------- stable account key (v2.5)

    private static final String CONTINUE_PREFS = "continue_watching";
    private static final String PROGRESS_PREFS = "watch_progress";
    private static String migratedToken = "";

    /**
     * Stable per-account key: hash of the account e-mail (falls back to the token only when
     * no e-mail is known). Before v2.5 the "continue watching" data was keyed by the session
     * token, which changes on every login, so a returning user lost everything.
     * The first call with a given token moves data stored under the old token key to this key.
     */
    /** The account's display name ("" when unknown). */
    public static String displayName(Context context) {
        try {
            String n = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE).getString("name", "");
            return n == null ? "" : n.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 19.2 Cigram Profiles: the key of the watching data of the PERSON watching now — the account itself for the main
     * profile (unchanged, so nothing moves on update), the account key plus the profile id for the others.
     */
    public static String viewerHash(Context context) {
        String acct = accountHash(context);
        if (acct.length() == 0) return acct;
        return acct + CigramProfiles.suffix(context);
    }

    public static String accountHash(Context context) {
        if (context == null) return "";
        String e = email(context);
        String t = token(context);
        String acct = Integer.toHexString((e.length() > 0 ? e : t).hashCode());
        if (e.length() > 0 && t.length() > 0 && !t.equals(migratedToken)) {
            migrateLegacy(context.getApplicationContext(), acct, Integer.toHexString(t.hashCode()));
            migratedToken = t;
        }
        return acct;
    }

    private static synchronized void migrateLegacy(Context app, String acct, String legacy) {
        if (acct.equals(legacy)) return;
        try {
            SharedPreferences cp = app.getSharedPreferences(CONTINUE_PREFS, Context.MODE_PRIVATE);
            String old = cp.getString("items_" + legacy, null);
            if (old != null) {
                JSONArray merged = mergeContinue(parseArray(cp.getString("items_" + acct, "[]")),
                        parseArray(old));
                cp.edit().putString("items_" + acct, merged.toString())
                        .remove("items_" + legacy).apply();
            }
            SharedPreferences pp = app.getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE);
            String from = "account_" + legacy + "|";
            String to = "account_" + acct + "|";
            SharedPreferences.Editor ed = pp.edit();
            boolean any = false;
            for (Map.Entry<String, ?> en : new java.util.HashMap<String, Object>(pp.getAll()).entrySet()) {
                String k = en.getKey();
                if (k != null && k.startsWith(from) && en.getValue() instanceof Long) {
                    String nk = to + k.substring(from.length());
                    if (!pp.contains(nk)) { ed.putLong(nk, ((Long) en.getValue()).longValue()); any = true; }
                }
            }
            if (any) ed.apply();
        } catch (Throwable ignored) { }
    }

    private static JSONArray parseArray(String raw) {
        try { return new JSONArray(raw == null ? "[]" : raw); } catch (Throwable ignored) { return new JSONArray(); }
    }

    private static String continueIdentity(JSONObject o) {
        boolean serial = "series".equalsIgnoreCase(o.optString("type"))
                || "episode".equalsIgnoreCase(o.optString("type"));
        String title = serial ? o.optString("series_title", o.optString("title"))
                : o.optString("title");
        String id = o.optString("id", "").trim();
        if (serial) return "s|" + CigramContent.norm(title);
        return "m|" + (id.length() > 0 ? id : CigramContent.norm(title));
    }

    /** Union by title/id, newest first, at most 100 entries. */
    private static JSONArray mergeContinue(JSONArray a, JSONArray b) {
        ArrayList<JSONObject> all = new ArrayList<JSONObject>();
        for (int n = 0; n < 2; n++) {
            JSONArray src = n == 0 ? a : b;
            for (int i = 0; i < src.length(); i++) {
                JSONObject o = src.optJSONObject(i);
                if (o != null) all.add(o);
            }
        }
        java.util.Collections.sort(all, new java.util.Comparator<JSONObject>() {
            @Override public int compare(JSONObject x, JSONObject y) {
                return Long.compare(y.optLong("updated_at", 0L), x.optLong("updated_at", 0L));
            }
        });
        java.util.LinkedHashMap<String, JSONObject> chosen = new java.util.LinkedHashMap<String, JSONObject>();
        for (int i = 0; i < all.size(); i++) {
            JSONObject o = all.get(i);
            String id = continueIdentity(o);
            JSONObject have = chosen.get(id);
            if (have == null) { chosen.put(id, o); continue; }
            // same title: newest wins, but keep the richer record when it is the same episode
            boolean sameSpot = have.optInt("season", 0) == o.optInt("season", 0)
                    && have.optInt("episode", 0) == o.optInt("episode", 0);
            if (sameSpot && have.optString("series_episodes_json", "").length() == 0
                    && o.optString("series_episodes_json", "").length() > 0) {
                chosen.put(id, o);
            }
        }
        JSONArray out = new JSONArray();
        for (JSONObject o : chosen.values()) {
            if (out.length() >= 100) break;
            out.put(o);
        }
        return out;
    }

    /**
     * Unfinished titles for "أكمل المشاهدة" (home + محتوياتي), in the legacy item format.
     * Source 1: the per-account store written by the player. Source 2: the permanent history
     * (which is synced to the account), so a new device / re-login still shows them.
     */
    public static JSONArray continueItems(Context context) {
        JSONArray out = new JSONArray();
        if (context == null || !isLoggedIn(context)) return out;
        try {
            Context app = context.getApplicationContext();
            String acct = viewerHash(context);
            SharedPreferences cp = app.getSharedPreferences(CONTINUE_PREFS, Context.MODE_PRIVATE);
            JSONArray legacy = parseArray(cp.getString("items_" + acct, "[]"));
            // the account's server history belongs to the main profile only (19.2 Cigram Profiles)
            ArrayList<JSONObject> rows = CigramProfiles.isMainActive(context) ? historyGrouped(context) : new ArrayList<JSONObject>();
            JSONArray fromHistory = new JSONArray();
            for (int i = 0; i < rows.size() && fromHistory.length() < 60; i++) {
                JSONObject h = rows.get(i);
                long pos = h.optLong("last_position", 0L);
                long dur = h.optLong("duration", 0L);
                if (h.optBoolean("finished", false) || pos < 5000L || dur <= 0L || dur - pos <= 15000L) continue;
                if (h.optString("video").trim().length() == 0) continue;
                boolean ep = "episode".equals(h.optString("type"));
                JSONObject o = new JSONObject();
                o.put("key", h.optString("key"));
                o.put("id", h.optString("id"));
                o.put("title", h.optString("title"));
                o.put("series_title", h.optString("series_title"));
                o.put("series_id", h.optString("series_id"));
                o.put("type", ep ? "series" : "movie");
                o.put("poster", h.optString("poster"));
                o.put("backdrop", h.optString("backdrop"));
                o.put("video", h.optString("video"));
                o.put("season", h.optInt("season", 0));
                o.put("episode", h.optInt("episode", 0));
                o.put("position", pos);
                o.put("duration", dur);
                o.put("updated_at", h.optLong("watched_at", 0L));
                if (ep) enrichEpisode(o);
                fromHistory.put(o);
            }
            // history entries only fill what the player store does not have for the same title
            JSONArray merged = mergeContinue(legacy, fromHistory);
            // an entry that history knows to be finished must not come back from the old store
            for (int i = 0; i < merged.length(); i++) {
                JSONObject o = merged.optJSONObject(i);
                if (o == null) continue;
                long pos = o.optLong("position", 0L);
                long dur = o.optLong("duration", 0L);
                if (pos < 5000L || (dur > 0L && dur - pos <= 15000L)) continue;
                out.put(o);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /**
     * The user removed a title from "أكمل المشاهدة". The history entry stays (history is permanent)
     * but is flagged finished, so the title no longer appears in the continue list.
     */
    public static void dismissContinue(Context context, JSONObject saved) {
        if (context == null || saved == null) return;
        try {
            ensureLoaded(context);
            String wanted = continueIdentity(saved);
            boolean changed = false;
            synchronized (LOCK) {
                for (int i = 0; i < HISTORY.size(); i++) {
                    JSONObject h = HISTORY.get(i);
                    JSONObject probe = new JSONObject();
                    boolean ep = "episode".equals(h.optString("type"));
                    probe.put("type", ep ? "series" : "movie");
                    probe.put("id", h.optString("id"));
                    probe.put("title", h.optString("title"));
                    probe.put("series_title", h.optString("series_title"));
                    if (wanted.equals(continueIdentity(probe)) && !h.optBoolean("finished", false)) {
                        h.put("finished", true);
                        changed = true;
                    }
                }
            }
            if (changed) scheduleFlush();
        } catch (Throwable ignored) { }
    }

    /** Best effort: give a history-derived episode the data the player expects. */
    private static void enrichEpisode(JSONObject o) {
        try {
            JSONObject series = CigramContent.find("series", o.optString("series_id"), o.optString("series_title"));
            if (series == null) return;
            JSONArray eps = CigramStage1InitProvider.cigramEpisodes(series);
            if (eps == null || eps.length() == 0) return;
            o.put("series_episodes_json", eps.toString());
            String poster = CigramContent.text(series, "poster", "series_poster", "image");
            if (poster.length() > 0) o.put("poster", poster);
            for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.optJSONObject(i);
                if (e == null) continue;
                if (toInt(e.opt("season")) == Math.max(1, o.optInt("season", 1))
                        && toInt(e.opt("episode")) == o.optInt("episode", 0)) {
                    String sub = e.optString("subtitle", "");
                    if (sub.length() > 0) o.put("subtitle", sub);
                    break;
                }
            }
        } catch (Throwable ignored) { }
    }

    // -------------------------------------------------------------- affinity

    /** Viewer taste computed from the history. Cheap (history is capped). */
    public static final class Affinity {
        public int movies;
        public int series;
        public final HashMap<String, Integer> genres = new HashMap<String, Integer>();
        public String topGenre = "";
        public int topGenreCount = 0;

        public boolean hasEnough() {
            return movies + series >= 3;
        }

        public String preferredType() {
            if (!hasEnough()) return "";
            if (series >= movies * 2 && series >= 3) return "series";
            if (movies >= series * 2 && movies >= 3) return "movie";
            return "";
        }
    }

    public static Affinity affinity(Context context) {
        Affinity a = new Affinity();
        ArrayList<JSONObject> rows = historyGrouped(context);
        for (int i = 0; i < rows.size(); i++) {
            JSONObject e = rows.get(i);
            if ("episode".equals(e.optString("type"))) a.series++; else a.movies++;
            JSONObject fake = new JSONObject();
            try { fake.put("category", e.optString("category")); } catch (Throwable ignored) { }
            List<String> g = CigramContent.genres(fake);
            for (int k = 0; k < g.size(); k++) {
                Integer c = a.genres.get(g.get(k));
                int n = c == null ? 1 : c.intValue() + 1;
                a.genres.put(g.get(k), Integer.valueOf(n));
                if (n > a.topGenreCount) {
                    a.topGenreCount = n;
                    a.topGenre = g.get(k);
                }
            }
        }
        return a;
    }

    // ---------------------------------------------- clocks of external lists

    /** which: "watch_later", "subscriptions" or "favorites". Call when the user edits the list. */
    public static void touch(Context context, String which) {
        if (context == null) return;
        ensureLoaded(context);
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if ("watch_later".equals(which)) wlTs = now;
            else if ("subscriptions".equals(which)) subTs = now;
            else if ("favorites".equals(which)) favTs = now;
        }
        scheduleFlush();
    }

    // ------------------------------------------------------------------ sync

    /** Complete local state for the server. */
    public static JSONObject exportSnapshot(Context context) {
        ensureLoaded(context);
        JSONObject out = new JSONObject();
        try {
            synchronized (LOCK) {
                JSONArray h = new JSONArray();
                for (int i = 0; i < HISTORY.size(); i++) h.put(HISTORY.get(i));
                JSONObject s = new JSONObject();
                for (Map.Entry<String, Long> e : SEEN.entrySet()) s.put(e.getKey(), e.getValue().longValue());
                JSONObject l = new JSONObject();
                for (Map.Entry<String, long[]> e : SEASONS.entrySet()) {
                    JSONObject v = new JSONObject();
                    v.put("s", e.getValue()[0]);
                    v.put("t", e.getValue()[1]);
                    l.put(e.getKey(), v);
                }
                out.put("history", h);
                out.put("seen", s);
                out.put("seasons", l);
                out.put("cleared_at", clearedAt);
                out.put("watch_later", listObject(context, "watch_later", wlTs));
                out.put("subscriptions", listObject(context, "subscriptions", subTs));
                out.put("favorites", listObject(context, "favorites", favTs));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static JSONObject listObject(Context context, String which, long ts) throws Exception {
        JSONObject o = new JSONObject();
        o.put("ts", ts);
        o.put("items", CigramStage1InitProvider.cigramListRead(context, which));
        return o;
    }

    /** Merges what the server returned. Returns true when local data changed. */
    public static boolean mergeSnapshot(Context context, JSONObject remote, boolean unionLists) {
        if (context == null || remote == null) return false;
        ensureLoaded(context);
        boolean changed = false;
        try {
            synchronized (LOCK) {
                long remoteCleared = remote.optLong("cleared_at", 0L);
                if (remoteCleared > clearedAt) {
                    clearedAt = remoteCleared;
                    changed = true;
                }
                // history: newest watched_at per key wins; anything older than the clear clock is dropped
                HashMap<String, JSONObject> byKey = new HashMap<String, JSONObject>();
                for (int i = 0; i < HISTORY.size(); i++) byKey.put(HISTORY.get(i).optString("key"), HISTORY.get(i));
                JSONArray rh = remote.optJSONArray("history");
                if (rh != null) {
                    for (int i = 0; i < rh.length(); i++) {
                        JSONObject r = rh.optJSONObject(i);
                        if (r == null) continue;
                        String k = r.optString("key");
                        if (k.length() == 0) continue;
                        JSONObject l = byKey.get(k);
                        if (l == null || r.optLong("watched_at", 0L) > l.optLong("watched_at", 0L)) {
                            byKey.put(k, r);
                            changed = true;
                        }
                    }
                }
                ArrayList<JSONObject> merged = new ArrayList<JSONObject>();
                for (JSONObject e : byKey.values()) {
                    if (e.optLong("watched_at", 0L) > clearedAt) merged.add(e);
                }
                java.util.Collections.sort(merged, new java.util.Comparator<JSONObject>() {
                    @Override public int compare(JSONObject a, JSONObject b) {
                        long x = a.optLong("watched_at", 0L);
                        long y = b.optLong("watched_at", 0L);
                        return x == y ? 0 : (x < y ? 1 : -1);
                    }
                });
                if (merged.size() != HISTORY.size()) changed = true;
                HISTORY.clear();
                for (int i = 0; i < merged.size() && i < MAX_HISTORY; i++) HISTORY.add(merged.get(i));

                JSONObject rs = remote.optJSONObject("seen");
                if (rs != null) {
                    Iterator<String> it = rs.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        long t = rs.optLong(k, 0L);
                        Long cur = SEEN.get(k);
                        if (cur == null || cur.longValue() < t) {
                            putSeen(k, t);
                            changed = true;
                        }
                    }
                }
                JSONObject rl = remote.optJSONObject("seasons");
                if (rl != null) {
                    Iterator<String> it = rl.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        JSONObject v = rl.optJSONObject(k);
                        if (v == null) continue;
                        long[] cur = SEASONS.get(k);
                        if (cur == null || cur[1] < v.optLong("t", 0L)) {
                            SEASONS.put(k, new long[]{v.optLong("s", 1L), v.optLong("t", 0L)});
                            changed = true;
                        }
                    }
                    trimSeasons();
                }
                changed |= mergeList(context, remote.optJSONObject("watch_later"), "watch_later", unionLists);
                changed |= mergeList(context, remote.optJSONObject("subscriptions"), "subscriptions", unionLists);
                changed |= mergeList(context, remote.optJSONObject("favorites"), "favorites", unionLists);
            }
            flushNowLocked();
        } catch (Throwable ignored) { }
        return changed;
    }

    /**
     * Caller holds LOCK. Newest clock wins. On the very first sync of an account on this
     * device (union=true) both lists are united so nothing the guest saved is lost.
     */
    private static boolean mergeList(Context context, JSONObject remote, String which, boolean union) {
        if (remote == null) return false;
        try {
            long rts = remote.optLong("ts", 0L);
            JSONArray ritems = remote.optJSONArray("items");
            if (ritems == null || rts == 0L) return false;       // server empty: local is pushed
            long lts = "watch_later".equals(which) ? wlTs : ("subscriptions".equals(which) ? subTs : favTs);
            JSONArray local = CigramStage1InitProvider.cigramListRead(context, which);
            JSONArray result;
            if (union) {
                result = unite(ritems, local);
            } else if (lts == 0L || rts > lts) {
                result = ritems;                                 // account list is newer
            } else {
                return false;                                    // local is newer or equal
            }
            if (result.toString().equals(local.toString())) {
                if (rts > lts) setClock(which, rts);
                return false;
            }
            CigramStage1InitProvider.cigramListWrite(context, which, result);
            if (union) setClock(which, System.currentTimeMillis()); else setClock(which, rts);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void setClock(String which, long ts) {
        if ("watch_later".equals(which)) wlTs = ts;
        else if ("subscriptions".equals(which)) subTs = ts;
        else favTs = ts;
    }

    private static JSONArray unite(JSONArray a, JSONArray b) {
        JSONArray out = new JSONArray();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        JSONArray[] both = new JSONArray[]{a, b};
        for (int n = 0; n < both.length; n++) {
            for (int i = 0; i < both[n].length(); i++) {
                JSONObject o = both[n].optJSONObject(i);
                if (o == null) continue;
                String k = o.optString("watch_later_key", "");
                if (k.length() == 0) k = o.optString("series_key", "");
                if (k.length() == 0) k = CigramContent.itemKey(o);
                if (k.length() == 0 || !seen.add(k)) continue;
                out.put(o);
            }
        }
        return out;
    }

    /**
     * First login on a device that already held guest data: copy the guest data into the
     * account namespace once (union, never delete).
     */
    public static void adoptGuestData(Context context) {
        if (context == null) return;
        String owner = owner(context);
        if ("guest".equals(owner)) return;
        SharedPreferences p = prefs(context);
        if (p.getBoolean("guest_merged::" + owner, false)) return;
        try {
            ensureLoaded(context);
            JSONObject guest = new JSONObject();
            JSONArray h = new JSONArray(p.getString("history::guest", "[]"));
            guest.put("history", h);
            guest.put("seen", new JSONObject(p.getString("seen::guest", "{}")));
            guest.put("seasons", new JSONObject(p.getString("seasons::guest", "{}")));
            // lists are device-wide already (watch later / subscriptions are not per user)
            mergeSnapshot(context, guest, false);
            p.edit().putBoolean("guest_merged::" + owner, true).apply();
        } catch (Throwable ignored) { }
    }
}
