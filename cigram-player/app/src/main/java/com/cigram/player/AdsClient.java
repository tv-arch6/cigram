package com.cigram.player;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client of the Cigram ads worker — the SAME campaigns, caps and statistics as the main app
 * (port of the app's AdsServe + CigramAds, no AdMob involved):
 *   GET  /ads?slot=&lang=&app_version=     -> { ads:[...], server_time }
 *   POST /ads/track   { device, events:[{t:"imp"|"click", cid, crid}] }
 *   POST /ads/report  { campaign_id, reason, device }
 * Slots used by the player: "splash" (full-screen pre-roll / mid-roll) and "sticky" (pause card).
 * Privacy: the random per-installation id and the impression/click events are sent ONLY when the user allowed ad
 * statistics ({@link Privacy}); otherwise ads are fetched without any id and nothing is recorded or sent.
 * Never an account id, never the Google advertising ID.
 */
final class AdsClient {

    static final String API_BASE = "https://cigram-ads-api.wwq-mixtv.workers.dev";

    private static final String PREFS = "cigram_player_ads";
    private static final long REFETCH_MS = 60000L;
    private static final long STALE_OK_MS = 6L * 3600000L;
    private static final int QUEUE_MAX = 100;
    private static final int MAX_BODY = 2 * 1024 * 1024;

    /** Safety limits so a long session can never turn into an ad wall. */
    static final long COOLDOWN_MS = 3L * 60000L;
    static final int MAX_PER_HOUR = 12;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final HashMap<String, Long> LAST_FETCH = new HashMap<String, Long>();
    private static volatile long serverOffset = 0L;

    private AdsClient() { }

    // ------------------------------------------------------------------ model

    static final class Ad {
        String campaignId = "", creativeId = "", slot = "", type = "image";
        String title = "", body = "", cta = "", mediaUrl = "", thumbUrl = "", bgColor = "", textColor = "";
        String actionType = "none", actionValue = "", actionExtra = "";
        int priority, dailyCap;
        long startAt, endAt;

        static Ad from(JSONObject o) {
            Ad a = new Ad();
            a.campaignId = o.optString("campaign_id");
            a.creativeId = o.optString("creative_id");
            a.slot = o.optString("slot");
            a.type = o.optString("type", "image");
            a.title = o.optString("title");
            a.body = o.optString("body");
            a.cta = o.optString("cta");
            a.mediaUrl = o.optString("media_url");
            a.thumbUrl = o.optString("thumb_url");
            a.bgColor = o.optString("bg_color");
            a.textColor = o.optString("text_color");
            a.priority = o.optInt("priority");
            a.dailyCap = o.optInt("daily_cap");
            a.startAt = o.optLong("start_at");
            a.endAt = o.optLong("end_at");
            JSONObject act = o.optJSONObject("action");
            if (act != null) {
                a.actionType = act.optString("type", "none");
                a.actionValue = act.optString("value");
                a.actionExtra = act.optString("extra");
            }
            return a;
        }

        boolean isVideo() { return "video".equals(type); }
        boolean isText() { return "text".equals(type); }
        boolean clickable() { return !"none".equals(actionType); }

        /** Picture to show: for video the thumbnail when given. */
        String posterUrl() {
            if (isText()) return "";
            if (isVideo()) return thumbUrl.length() > 0 ? thumbUrl : "";
            return mediaUrl.length() > 0 ? mediaUrl : thumbUrl;
        }
    }

    interface Cb { void onAds(List<Ad> eligible); }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ identity / clock

    /** Random per-installation id; "" when the user did not allow ad statistics. */
    static String deviceId(Context c) {
        if (!Privacy.statsAllowed(c)) return "";
        SharedPreferences p = sp(c);
        String id = p.getString("device_id", "");
        if (id == null || id.length() < 8) {
            id = UUID.randomUUID().toString().replace("-", "");
            p.edit().putString("device_id", id).apply();
        }
        return id;
    }

    static long now() {
        return System.currentTimeMillis() + serverOffset;
    }

    private static void syncClock(long serverTime) {
        if (serverTime > 1000000000000L) serverOffset = serverTime - System.currentTimeMillis();
    }

    private static String versionCode(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return String.valueOf(android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode);
        } catch (Throwable t) {
            return "0";
        }
    }

    // ------------------------------------------------------------------ fetch / cache

    /** Valid local copy (no network), or null. */
    static List<Ad> cached(Context c, String slot) {
        try {
            String s = sp(c).getString("cache_" + slot, "");
            if (s == null || s.length() == 0) return null;
            JSONObject o = new JSONObject(s);
            if (System.currentTimeMillis() - o.optLong("at") > STALE_OK_MS) return null;
            return eligible(c, parse(o.optJSONArray("ads")));
        } catch (Throwable t) {
            return null;
        }
    }

    private static List<Ad> parse(JSONArray arr) {
        List<Ad> out = new ArrayList<Ad>();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Ad a = Ad.from(o);
            if (a.campaignId.length() > 0 && a.creativeId.length() > 0) out.add(a);
        }
        return out;
    }

    /**
     * Delivers the ads that may be shown now, always exactly once, on the main thread.
     * A copy younger than 60 s is used without touching the network; on failure the local copy is used.
     */
    static void load(final Context c, final String slot, final Cb cb) {
        final Context app = c.getApplicationContext();
        Long last = LAST_FETCH.get(slot);
        if (last != null && System.currentTimeMillis() - last < REFETCH_MS) {
            List<Ad> hit = cached(app, slot);
            if (hit != null) { cb.onAds(hit); return; }
        }
        final String path = "/ads?slot=" + enc(slot) + "&lang=" + enc(Locale.getDefault().getLanguage())
                + "&app_version=" + enc(versionCode(app));
        POOL.execute(new Runnable() {
            @Override public void run() {
                final Result r = request("GET", path, null);
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        try {
                            JSONArray arr = r.json == null ? null : r.json.optJSONArray("ads");
                            if (r.ok && arr != null) {
                                LAST_FETCH.put(slot, System.currentTimeMillis());
                                syncClock(r.json.optLong("server_time"));
                                JSONObject store = new JSONObject();
                                store.put("at", System.currentTimeMillis());
                                store.put("ads", arr);
                                sp(app).edit().putString("cache_" + slot, store.toString()).apply();
                                cb.onAds(eligible(app, parse(arr)));
                                return;
                            }
                        } catch (Throwable ignored) { }
                        List<Ad> fallback = cached(app, slot);
                        cb.onAds(fallback == null ? new ArrayList<Ad>() : fallback);
                    }
                });
            }
        });
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Throwable t) { return ""; }
    }

    // ------------------------------------------------------------------ eligibility / rotation

    static List<Ad> eligible(Context c, List<Ad> all) {
        List<Ad> out = new ArrayList<Ad>();
        long now = now();
        JSONObject hidden = hiddenMap(c);
        JSONObject caps = capMap(c);
        for (Ad a : all) {
            if (a.startAt > now || (a.endAt > 0 && a.endAt <= now)) continue;
            if (hidden.has(a.campaignId) && hidden.optLong(a.campaignId) > System.currentTimeMillis()) continue;
            if (a.dailyCap > 0 && caps.optInt(a.campaignId) >= a.dailyCap) continue;
            out.add(a);
        }
        return out;
    }

    private static long dayNumber() {
        Calendar cal = Calendar.getInstance();
        return (System.currentTimeMillis() + cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 86400000L;
    }

    private static JSONObject capMap(Context c) {
        try {
            JSONObject o = new JSONObject(sp(c).getString("caps", "{}"));
            if (o.optLong("day") != dayNumber()) return new JSONObject();
            JSONObject m = o.optJSONObject("m");
            return m == null ? new JSONObject() : m;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    private static void bumpCap(Context c, String campaignId) {
        try {
            JSONObject m = capMap(c);
            m.put(campaignId, m.optInt(campaignId) + 1);
            sp(c).edit().putString("caps", new JSONObject().put("day", dayNumber()).put("m", m).toString()).apply();
        } catch (Throwable ignored) { }
    }

    private static JSONObject hiddenMap(Context c) {
        try {
            JSONObject o = new JSONObject(sp(c).getString("hidden", "{}"));
            long now = System.currentTimeMillis();
            JSONObject keep = new JSONObject();
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (o.optLong(k) > now) keep.put(k, o.optLong(k));
            }
            return keep;
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    /** Hide a campaign on this device for 7 days (the user pressed "hide this ad"). */
    static void hide(Context c, String campaignId) {
        try {
            JSONObject h = hiddenMap(c);
            h.put(campaignId, System.currentTimeMillis() + 7L * 86400000L);
            sp(c).edit().putString("hidden", h.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** Highest priority first; round-robin (persisted) inside the top priority. */
    static List<Ad> order(Context c, String slot, List<Ad> eligible) {
        List<Ad> list = new ArrayList<Ad>(eligible);
        Collections.sort(list, new Comparator<Ad>() {
            @Override public int compare(Ad a, Ad b) { return b.priority - a.priority; }
        });
        if (list.size() < 2) return list;
        int top = list.get(0).priority;
        int n = 0;
        while (n < list.size() && list.get(n).priority == top) n++;
        int shift = sp(c).getInt("rot_" + slot, 0) % n;
        List<Ad> out = new ArrayList<Ad>();
        for (int i = 0; i < n; i++) out.add(list.get((i + shift) % n));
        for (int i = n; i < list.size(); i++) out.add(list.get(i));
        return out;
    }

    static void advance(Context c, String slot) {
        SharedPreferences p = sp(c);
        p.edit().putInt("rot_" + slot, (p.getInt("rot_" + slot, 0) + 1) & 0xFFFF).apply();
    }

    // ------------------------------------------------------------------ frequency limits (player level)

    /** At least COOLDOWN_MS since the last ad and fewer than MAX_PER_HOUR in the last hour. */
    static boolean mayShowAd(Context c) {
        try {
            long now = System.currentTimeMillis();
            if (now - sp(c).getLong("last_ad_at", 0L) < COOLDOWN_MS) return false;
            JSONArray arr = new JSONArray(sp(c).getString("shown", "[]"));
            int inHour = 0;
            for (int i = 0; i < arr.length(); i++) if (now - arr.optLong(i) < 3600000L) inHour++;
            return inHour < MAX_PER_HOUR;
        } catch (Throwable t) {
            return true;
        }
    }

    static void markShown(Context c) {
        try {
            long now = System.currentTimeMillis();
            JSONArray old = new JSONArray(sp(c).getString("shown", "[]"));
            JSONArray keep = new JSONArray();
            for (int i = 0; i < old.length(); i++) if (now - old.optLong(i) < 3600000L) keep.put(old.optLong(i));
            keep.put(now);
            sp(c).edit().putString("shown", keep.toString()).putLong("last_ad_at", now).apply();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ tracking

    private static boolean flushScheduled = false;
    private static volatile boolean flushing = false;

    /** User declined / reset: drop the id and every queued event. */
    static synchronized void forgetTracking(Context c) {
        sp(c).edit().remove("device_id").remove("q").apply();
    }

    static void impression(Context c, Ad ad) {
        bumpCap(c, ad.campaignId);          // local daily cap only (stays on the device)
        enqueue(c, "imp", ad);
    }

    static void click(Context c, Ad ad) {
        enqueue(c, "click", ad);
        flush(c);
    }

    private static synchronized void enqueue(Context c, String t, Ad ad) {
        if (!Privacy.statsAllowed(c)) return;
        try {
            JSONArray q = new JSONArray(sp(c).getString("q", "[]"));
            q.put(new JSONObject().put("t", t).put("cid", ad.campaignId).put("crid", ad.creativeId));
            if (q.length() > QUEUE_MAX) {
                JSONArray cut = new JSONArray();
                for (int i = q.length() - QUEUE_MAX; i < q.length(); i++) cut.put(q.get(i));
                q = cut;
            }
            sp(c).edit().putString("q", q.toString()).apply();
        } catch (Throwable ignored) { }
        scheduleFlush(c);
    }

    private static void scheduleFlush(final Context c) {
        if (flushScheduled) return;
        flushScheduled = true;
        final Context app = c.getApplicationContext();
        MAIN.postDelayed(new Runnable() {
            @Override public void run() { flushScheduled = false; flush(app); }
        }, 6000L);
    }

    /** Sends up to 20 queued events; on failure they stay queued for the next attempt. */
    static void flush(Context c) {
        final Context app = c.getApplicationContext();
        if (flushing) return;
        if (!Privacy.statsAllowed(app)) { forgetTracking(app); return; }
        final JSONArray batch = new JSONArray();
        final int sent;
        synchronized (AdsClient.class) {
            try {
                JSONArray q = new JSONArray(sp(app).getString("q", "[]"));
                if (q.length() == 0) return;
                for (int i = 0; i < q.length() && i < 20; i++) batch.put(q.get(i));
                sent = batch.length();
            } catch (Throwable t) {
                return;
            }
        }
        flushing = true;
        POOL.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject body = new JSONObject().put("device", deviceId(app)).put("events", batch);
                    Result r = request("POST", "/ads/track", body.toString());
                    boolean drop = r.ok || (r.code >= 400 && r.code < 500 && r.code != 429);
                    if (!drop) return;
                    synchronized (AdsClient.class) {
                        JSONArray q = new JSONArray(sp(app).getString("q", "[]"));
                        JSONArray rest = new JSONArray();
                        for (int i = sent; i < q.length(); i++) rest.put(q.get(i));
                        sp(app).edit().putString("q", rest.toString()).apply();
                        if (rest.length() > 0) MAIN.post(new Runnable() { @Override public void run() { scheduleFlush(app); } });
                    }
                } catch (Throwable ignored) {
                } finally {
                    flushing = false;
                }
            }
        });
    }

    static void report(final Context c, final Ad ad, final String reason) {
        POOL.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject body = new JSONObject().put("campaign_id", ad.campaignId).put("reason", reason);
                    String dev = deviceId(c);                       // "" unless statistics were allowed
                    if (dev.length() > 0) body.put("device", dev);
                    request("POST", "/ads/report", body.toString());
                } catch (Throwable ignored) { }
            }
        });
    }

    // ------------------------------------------------------------------ click action

    /** Opens the destination. https only (and market: for store links); phone numbers open the dialer. */
    static void perform(Activity a, Ad ad) {
        click(a, ad);
        try {
            String t = ad.actionType, v = ad.actionValue;
            if ("link".equals(t) || "store".equals(t)) {
                String lower = v.toLowerCase(Locale.US);
                boolean ok = lower.startsWith("https://") || ("store".equals(t) && lower.startsWith("market:"));
                if (!ok) return;
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(v)));
            } else if ("whatsapp".equals(t)) {
                String digits = v.replaceAll("[^0-9]", "");
                if (digits.length() == 0) return;
                String url = "https://wa.me/" + digits + (ad.actionExtra.length() > 0 ? "?text=" + enc(ad.actionExtra) : "");
                a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } else if ("call".equals(t)) {
                String digits = v.replaceAll("[^0-9+]", "");
                if (digits.length() == 0) return;
                a.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + digits)));
            } else if ("copy".equals(t)) {
                ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("coupon", v));
                Ui.toast(a, "تم نسخ الكود: " + v);
            }
        } catch (Throwable t) {
            Ui.toast(a, "تعذّر فتح الرابط.");
        }
    }

    // ------------------------------------------------------------------ HTTP (background threads only)

    private static final class Result {
        boolean ok;
        int code;
        JSONObject json;
    }

    private static Result request(String method, String path, String json) {
        Result r = new Result();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API_BASE + path).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json");
            if (json != null) {
                byte[] data = json.getBytes("UTF-8");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setFixedLengthStreamingMode(data.length);
                OutputStream os = conn.getOutputStream();
                os.write(data);
                os.flush();
                os.close();
            }
            r.code = conn.getResponseCode();
            InputStream in = r.code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = in == null ? "" : readAll(in);
            try {
                r.json = text.length() == 0 ? new JSONObject() : new JSONObject(text);
            } catch (Throwable parse) {
                r.json = new JSONObject();
            }
            r.ok = r.code >= 200 && r.code < 300 && r.json.optBoolean("ok", true);
        } catch (Throwable e) {
            r.ok = false;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) { }
            }
        }
        if (r.json == null) r.json = new JSONObject();
        return r;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY) break;
        }
        in.close();
        return out.toString("UTF-8");
    }
}
