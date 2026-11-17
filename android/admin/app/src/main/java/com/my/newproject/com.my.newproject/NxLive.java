package com.my.newproject;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * Live data of the new home. Every second: asks the Worker for /admin/live (one request in flight at most)
 * and records one sample so the charts move every second. Only runs while the home screen is visible.
 *
 * Sources (all real, nothing estimated on the phone):
 *   active / watching now, ad shows today per placement, users / sessions / plays today -> GET /admin/live
 *   registered accounts -> the Auth Worker stats URL (if the Auth Worker exposes one), every 30 s
 * If the Worker has no /admin/live yet, today's numbers come from /admin/ops/summary?days=1 every 15 s.
 */
final class NxLive {

    interface Listener {
        /** Called once per second on the UI thread. fresh = new server data arrived since the last tick. */
        void onTick(NxLive live, boolean fresh);
    }

    static final String PREF_ACCOUNTS_URL = "nx_accounts_url";
    static final String DEFAULT_AUTH = "https://cigram-auth-api.wwq-mixtv.workers.dev";

    private final Context app;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean running;
    private boolean inFlight;
    private boolean accInFlight;
    private boolean fresh;
    private long tick;
    private long nextAccountsTick;
    private int errors;

    // ------------------------------------------------------------------ state (UI thread only)
    boolean connected;
    boolean everConnected;
    boolean routeMissing;
    String error = "";
    long lastOkAt;
    int active = -1;
    int watching = -1;
    long adShows = -1;
    long adRequests = -1;
    long usersToday = -1;
    long sessionsToday = -1;
    long playsToday = -1;
    int activeWindowMin = 5;
    int watchWindowMin = 35;
    final LinkedHashMap<String, Long> ads = new LinkedHashMap<String, Long>();
    JSONArray watchingTop = new JSONArray();
    /** Watching right now per kind, -1 when the Worker does not send the split. */
    long watchMovies = -1;
    long watchSeries = -1;
    long watchChannels = -1;
    /** Server minute history: {minute_ms, active, watching}. */
    final ArrayList<long[]> history = new ArrayList<long[]>();
    long accounts = -1;
    boolean accountsLinked;
    String accountsNote = "";
    /** true when /admin/live itself answers the accounts (Worker bound to the auth database as AUTH_DB). */
    boolean accountsFromLive;
    long accountsActive = -1;
    long accountsNew24 = -1;
    long accountsNew7 = -1;
    long accountsNewToday = -1;
    long accountsSignedIn = -1;
    String accountsLastAt = "";
    /** Running total per day (last 14 days), oldest first. */
    double[] accountsDaily = new double[0];

    /** One sample per second: {active, watching}. */
    final ArrayList<double[]> seconds = new ArrayList<double[]>();
    final ArrayList<Double> adSeconds = new ArrayList<Double>();
    final ArrayList<Double> accountSamples = new ArrayList<Double>();

    NxLive(Context c) {
        app = c.getApplicationContext();
    }

    void setListener(Listener l) {
        listener = l;
    }

    void start() {
        if (running) return;
        running = true;
        h.removeCallbacks(loop);
        h.post(loop);
    }

    void stop() {
        running = false;
        h.removeCallbacks(loop);
    }

    boolean isRunning() {
        return running;
    }

    /** Forces an accounts lookup on the next tick (after the URL was changed). */
    void retryAccounts() {
        nextAccountsTick = 0;
    }

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            tick++;
            if (!inFlight && shouldFetch()) fetch();
            if (!accountsFromLive && !accInFlight && tick >= nextAccountsTick) fetchAccounts();

            seconds.add(new double[]{Math.max(0, active), Math.max(0, watching)});
            while (seconds.size() > NxChart.Live.CAP + 2) seconds.remove(0);
            adSeconds.add((double) Math.max(0, adShows));
            while (adSeconds.size() > 61) adSeconds.remove(0);

            boolean f = fresh;
            fresh = false;
            if (listener != null) {
                try {
                    listener.onTick(NxLive.this, f);
                } catch (Throwable ignored) { }
            }
            h.postDelayed(this, 1000);
        }
    };

    private boolean shouldFetch() {
        if (routeMissing) return tick % 15 == 1;
        if (errors >= 6) return tick % 10 == 0;
        if (errors >= 3) return tick % 4 == 0;
        return true;
    }

    // ------------------------------------------------------------------ live

    private void fetch() {
        inFlight = true;
        final boolean fallback = routeMissing;
        CgHttp.bg(new Runnable() {
            @Override
            public void run() {
                final CgHttp.Res r = CgHttp.call("GET", CgCfg.API + (fallback ? "/admin/ops/summary?days=1" : "/admin/live"),
                        null, true, 9000, null);
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        inFlight = false;
                        if (fallback) onSummary(r);
                        else onLive(r);
                    }
                });
            }
        });
    }

    private void onLive(CgHttp.Res r) {
        if (r.code == 404 && r.json != null && "not_found".equals(r.json.optString("error")) && r.json.has("path")) {
            routeMissing = true;
            tick = 0; // fallback fetch on the next tick
            return;
        }
        if (!r.ok() || r.json == null) {
            fail(r.message());
            return;
        }
        JSONObject d = r.json.optJSONObject("data");
        if (d == null) d = r.json;
        active = d.optInt("active_now", active);
        watching = d.optInt("watching_now", watching);
        adShows = d.optLong("ad_shows_today", adShows);
        adRequests = d.optLong("ad_requests_today", adRequests);
        usersToday = d.optLong("users_today", usersToday);
        sessionsToday = d.optLong("sessions_today", sessionsToday);
        playsToday = d.optLong("plays_today", playsToday);
        JSONObject w = d.optJSONObject("windows");
        if (w != null) {
            activeWindowMin = w.optInt("active_min", activeWindowMin);
            watchWindowMin = w.optInt("watch_min", watchWindowMin);
        }
        JSONObject pl = d.optJSONObject("ads_by_placement");
        if (pl != null) {
            ads.clear();
            Iterator<String> it = pl.keys();
            while (it.hasNext()) {
                String k = it.next();
                ads.put(k, pl.optLong(k, 0));
            }
        }
        JSONObject acc = d.optJSONObject("accounts");
        if (acc != null) readAccounts(acc);
        JSONArray top = d.optJSONArray("watching_top");
        watchingTop = top == null ? new JSONArray() : top;
        // exact split of who is watching what, when the Worker sends it (otherwise the home derives it from watching_top)
        JSONObject wk = d.optJSONObject("watching_by_type");
        if (wk == null) wk = d.optJSONObject("watching_by_kind");
        if (wk != null) {
            watchMovies = firstLong(wk, "movie", "movies", "film", "films");
            watchSeries = firstLong(wk, "series", "episode", "episodes", "tv");
            watchChannels = firstLong(wk, "channel", "channels", "live");
        }
        JSONArray hist = d.optJSONArray("history");
        if (hist != null) {
            history.clear();
            for (int i = 0; i < hist.length(); i++) {
                JSONArray p = hist.optJSONArray(i);
                if (p == null || p.length() < 3) continue;
                history.add(new long[]{p.optLong(0), p.optLong(1), p.optLong(2)});
            }
        }
        ok();
    }

    private static long firstLong(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k)) return o.optLong(k, -1);
        }
        return -1;
    }

    /** Fallback while the Worker is older than v7: today's numbers only (no "now"). */
    private void onSummary(CgHttp.Res r) {
        if (!r.ok() || r.json == null) {
            fail(r.message());
            return;
        }
        JSONObject d = r.json.optJSONObject("data");
        if (d == null) d = r.json;
        usersToday = d.optLong("users", usersToday);
        sessionsToday = d.optLong("sessions", sessionsToday);
        long plays = 0;
        JSONObject p = d.optJSONObject("plays");
        if (p != null) {
            Iterator<String> it = p.keys();
            while (it.hasNext()) {
                JSONObject row = p.optJSONObject(it.next());
                if (row != null) plays += row.optLong("started", 0);
            }
        }
        playsToday = plays;
        JSONObject a = d.optJSONObject("ads");
        if (a != null) {
            adShows = a.optLong("show", adShows);
            adRequests = a.optLong("request", adRequests);
        }
        ok();
    }

    private void ok() {
        connected = true;
        everConnected = true;
        errors = 0;
        error = "";
        lastOkAt = System.currentTimeMillis();
        fresh = true;
    }

    private void fail(String msg) {
        errors++;
        error = msg == null ? "" : msg;
        if (errors >= 2) connected = false;
        fresh = true;
    }

    // -------------------------------------------------------------- accounts

    /** Accounts as answered by /admin/live (read-only counts from the auth database). */
    private void readAccounts(JSONObject acc) {
        accountsFromLive = true;
        if (!acc.optBoolean("linked", false)) {
            accountsLinked = false;
            accountsNote = acc.optString("message", "");
            if (accountsNote.length() == 0) accountsNote = "no_binding".equals(acc.optString("reason")) ? "اربط قاعدة الحسابات بالـ Worker" : "تعذر قراءة قاعدة الحسابات";
            return;
        }
        long total = acc.optLong("total", -1);
        if (total < 0) return;
        accounts = total;
        accountsLinked = true;
        accountsNote = "";
        accountsActive = acc.optLong("active", -1);
        accountsNew24 = acc.optLong("new_24h", -1);
        accountsNew7 = acc.optLong("new_7d", -1);
        accountsNewToday = acc.optLong("new_today", -1);
        accountsSignedIn = acc.optLong("signed_in", -1);
        accountsLastAt = acc.optString("last_at", "");
        JSONArray days = acc.optJSONArray("daily");
        if (days != null && days.length() > 0) {
            long inWindow = 0;
            for (int i = 0; i < days.length(); i++) {
                JSONArray r = days.optJSONArray(i);
                if (r != null) inWindow += r.optLong(1, 0);
            }
            double run = Math.max(0, total - inWindow);
            double[] out = new double[days.length() + 1];
            out[0] = run;
            for (int i = 0; i < days.length(); i++) {
                JSONArray r = days.optJSONArray(i);
                run += r == null ? 0 : r.optLong(1, 0);
                out[i + 1] = run;
            }
            accountsDaily = out;
        } else {
            accountsDaily = new double[]{total, total};
        }
    }

    static String accountsUrl(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(CgCfg.PREFS, Context.MODE_PRIVATE);
            String u = sp.getString(PREF_ACCOUNTS_URL, "");
            if (u != null && u.trim().length() > 0) return u.trim();
            String base = sp.getString("auth_worker_url", "");
            if (base == null || !base.startsWith("http")) base = DEFAULT_AUTH;
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            return base + "/admin/stats";
        } catch (Throwable t) {
            return DEFAULT_AUTH + "/admin/stats";
        }
    }

    static void setAccountsUrl(Context c, String url) {
        try {
            c.getSharedPreferences(CgCfg.PREFS, Context.MODE_PRIVATE).edit().putString(PREF_ACCOUNTS_URL, url == null ? "" : url.trim()).apply();
        } catch (Throwable ignored) { }
    }

    private void fetchAccounts() {
        accInFlight = true;
        final String url = accountsUrl(app);
        CgHttp.bg(new Runnable() {
            @Override
            public void run() {
                final CgHttp.Res r = CgHttp.call("GET", url, null, true, 10000, null);
                final long n = r.ok() ? count(r.json) : -1;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        accInFlight = false;
                        if (n >= 0) {
                            accounts = n;
                            accountsLinked = true;
                            accountsNote = "";
                            accountSamples.add((double) n);
                            while (accountSamples.size() > 40) accountSamples.remove(0);
                            nextAccountsTick = tick + 30;
                        } else {
                            accountsLinked = false;
                            accountsNote = r.error != null ? CgHttp.describe(r.error)
                                    : r.code == 404 ? "لا يوجد مسار عدّ الحسابات في Auth Worker"
                                    : r.ok() ? "الرد لا يحتوي عدد الحسابات" : r.message();
                            nextAccountsTick = tick + 300; // try again in 5 minutes
                        }
                        fresh = true;
                    }
                });
            }
        });
    }

    /** First count-like number in the reply: total_accounts, accounts, users_total, registered, total, count, users (also under data / stats). */
    static long count(JSONObject o) {
        if (o == null) return -1;
        String[] keys = {"total_accounts", "accounts_total", "accounts", "registered_users", "users_total", "total_users",
                "registered", "total", "count", "users"};
        for (int pass = 0; pass < 3; pass++) {
            JSONObject src = pass == 0 ? o : pass == 1 ? o.optJSONObject("data") : o.optJSONObject("stats");
            if (src == null) continue;
            for (String k : keys) {
                Object v = src.opt(k);
                if (v instanceof Number) return ((Number) v).longValue();
                if (v instanceof String && ((String) v).matches("\\d{1,12}")) return Long.parseLong((String) v);
                if (v instanceof JSONObject) {
                    Object t = ((JSONObject) v).opt("total");
                    if (t instanceof Number) return ((Number) t).longValue();
                }
            }
        }
        return -1;
    }

    // --------------------------------------------------------------- helpers

    /** Value about {@code secondsAgo} seconds ago from the per-second samples (or the minute history). */
    double ago(int series, int secondsAgo) {
        int n = seconds.size();
        if (n > secondsAgo) return seconds.get(n - 1 - secondsAgo)[series];
        if (history.size() >= 2) return history.get(history.size() - 2)[series + 1];
        return n > 0 ? seconds.get(0)[series] : -1;
    }

    double adsAgo(int secondsAgo) {
        int n = adSeconds.size();
        if (n == 0) return -1;
        return adSeconds.get(Math.max(0, n - 1 - secondsAgo));
    }

    double accountsBefore() {
        if (accountsFromLive) return accountsLinked && accountsNew24 >= 0 ? accounts - accountsNew24 : -1; // arrow = new in 24 h
        int n = accountSamples.size();
        if (n < 2) return -1;
        return accountSamples.get(Math.max(0, n - 1 - 10)); // ~5 minutes earlier
    }

    double[] minuteSeries(int series, int maxPoints) {
        int n = history.size();
        int from = Math.max(0, n - maxPoints);
        double[] out = new double[n - from];
        for (int i = from; i < n; i++) out[i - from] = history.get(i)[series + 1];
        return out;
    }

    double[] secondSeries(int series) {
        double[] out = new double[seconds.size()];
        for (int i = 0; i < out.length; i++) out[i] = seconds.get(i)[series];
        return out;
    }

    static double[] toArray(ArrayList<Double> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }
}
