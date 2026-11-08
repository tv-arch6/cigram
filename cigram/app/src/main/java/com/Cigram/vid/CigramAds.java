package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * طبقة الاتصال بـ Worker الإعلانات (cigram-ads-api).
 * - كل الشبكة في خيوط خلفية، والنتيجة تعود دائماً على الخيط الرئيسي.
 * - لا ترمي استثناءات: كل فشل يتحول إلى Result برسالة عربية ودودة.
 * - الإعدادات (الأسعار والسياسات...) تُخزَّن محلياً وتعمل دون اتصال.
 */
public final class CigramAds {

    /** عنوان الـ Worker الجديد (انظر docs/DEPLOY.md). غيّره إذا استخدمت اسماً مختلفاً. */
    public static final String API_BASE = "https://cigram-ads-api.wwq-mixtv.workers.dev";

    private static final String PREFS = "cigram_ads_v1";
    private static final long CONFIG_TTL_MS = 5L * 60L * 1000L;
    private static final int MAX_BODY = 2 * 1024 * 1024;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = CigramPool.fixed(3, "ads");

    private static volatile long serverOffset = 0L;
    private static volatile JSONObject memConfig = null;
    private static volatile long memConfigAt = 0L;

    private CigramAds() { }

    public interface Callback {
        void onResult(Result result);
    }

    public static final class Result {
        public boolean ok;
        public int code;
        public JSONObject json;
        public String error = "";
        public String message = "";
        /** صحيح عندما لم نستطع الوصول للخادم أبداً (لا إنترنت/مهلة). */
        public boolean offline;
        /** صحيح عندما جاءت البيانات من النسخة المحلية المخزّنة. */
        public boolean cached;
    }

    // ------------------------------------------------------------------ هوية الجهاز والوقت

    public static String deviceId(Context c) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = p.getString("device_id", "");
        if (id == null || id.length() < 8) {
            id = UUID.randomUUID().toString().replace("-", "");
            p.edit().putString("device_id", id).apply();
        }
        return id;
    }

    /** وقت الخادم التقريبي (لتجاهل الحملات المنتهية حتى لو كانت ساعة الجهاز خاطئة). */
    public static long now() {
        return System.currentTimeMillis() + serverOffset;
    }

    public static void syncClock(long serverTime) {
        if (serverTime > 1000000000000L) serverOffset = serverTime - System.currentTimeMillis();
    }

    public static boolean isLoggedIn(Context c) {
        try {
            return CigramUserData.isLoggedIn(c) && CigramUserData.token(c).length() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String versionCode(Context c) {
        try {
            return String.valueOf(c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode);
        } catch (Throwable ignored) {
            return "0";
        }
    }

    // ------------------------------------------------------------------ HTTP

    public static void get(final Context c, final String path, final boolean auth, final Callback cb) {
        run(c, "GET", path, null, auth, cb);
    }

    public static void post(final Context c, final String path, final JSONObject body, final boolean auth, final Callback cb) {
        run(c, "POST", path, body == null ? new JSONObject() : body, auth, cb);
    }

    private static void run(Context c, final String method, final String path, final JSONObject body, final boolean auth, final Callback cb) {
        final Context app = c.getApplicationContext();
        final String token = auth ? CigramUserData.token(app) : "";
        POOL.execute(new Runnable() {
            @Override public void run() {
                final Result r = request(method, path, body == null ? null : body.toString(), token);
                if (cb != null) {
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            try { cb.onResult(r); } catch (Throwable t) { android.util.Log.w("CigramAds", "callback failed", t); }
                        }
                    });
                }
            }
        });
    }

    /** طلب متزامن — لا يُستدعى أبداً من الخيط الرئيسي. */
    public static Result request(String method, String path, String json, String bearer) {
        Result r = new Result();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API_BASE + path).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json");
            if (bearer != null && bearer.length() > 0) conn.setRequestProperty("Authorization", "Bearer " + bearer);
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
            r.error = r.json.optString("error", "");
            r.message = r.json.optString("message", "");
            if (!r.ok && r.message.length() == 0) r.message = friendlyCode(r.code, r.error);
        } catch (UnknownHostException e) {
            r.offline = true;
            r.message = "لا يوجد اتصال بالإنترنت. تحقق من الشبكة ثم حاول مجدداً.";
        } catch (SocketTimeoutException e) {
            r.offline = true;
            r.message = "انتهت مهلة الاتصال. حاول مرة أخرى.";
        } catch (Throwable e) {
            r.offline = true;
            r.message = "تعذر الاتصال بالخادم. حاول مرة أخرى بعد قليل.";
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

    public static String friendlyCode(int code, String error) {
        if ("login_required".equals(error) || "invalid_token".equals(error) || code == 401) return "انتهت الجلسة. سجّل الدخول من جديد.";
        if ("consent_required".equals(error)) return "يلزم الموافقة على السياسات أولاً.";
        if (code == 429 || "rate_limited".equals(error)) return "طلبات كثيرة. انتظر قليلاً ثم حاول.";
        if (code == 403) return "غير مسموح بهذا الإجراء.";
        if (code == 404) return "غير موجود.";
        if (code == 413) return "الملف كبير جداً.";
        if (code == 415) return "نوع الملف غير مدعوم.";
        if (code >= 500) return "الخادم مشغول حالياً. حاول بعد قليل.";
        return "تعذر إكمال الطلب.";
    }

    // ------------------------------------------------------------------ الإعدادات (أسعار/سياسات/...)

    /** آخر نسخة محفوظة (قد تكون null في أول تشغيل دون اتصال). */
    public static JSONObject cachedConfig(Context c) {
        JSONObject m = memConfig;
        if (m != null) return m;
        try {
            String s = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("config", "");
            if (s != null && s.length() > 2) {
                memConfig = new JSONObject(s);
                memConfigAt = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("config_at", 0L);
                return memConfig;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * يعيد الإعدادات: من الشبكة إن أمكن، وإلا من النسخة المحلية (result.cached = true).
     * إن لم توجد نسخة ولا اتصال: result.ok = false مع رسالة.
     */
    public static void loadConfig(final Context c, final boolean force, final Callback cb) {
        final Context app = c.getApplicationContext();
        JSONObject cached = cachedConfig(app);
        if (!force && cached != null && System.currentTimeMillis() - memConfigAt < CONFIG_TTL_MS) {
            Result r = new Result();
            r.ok = true;
            r.cached = true;
            r.json = new JSONObject();
            try { r.json.put("settings", cached); } catch (Throwable ignored) { }
            cb.onResult(r);
            return;
        }
        get(app, "/ads/config", false, new Callback() {
            @Override public void onResult(Result r) {
                if (r.ok && r.json.optJSONObject("settings") != null) {
                    JSONObject s = r.json.optJSONObject("settings");
                    syncClock(s.optLong("server_time", 0L));
                    memConfig = s;
                    memConfigAt = System.currentTimeMillis();
                    try {
                        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putString("config", s.toString()).putLong("config_at", memConfigAt).apply();
                    } catch (Throwable ignored) { }
                    cb.onResult(r);
                    return;
                }
                JSONObject fallback = cachedConfig(app);
                if (fallback != null) {
                    Result c2 = new Result();
                    c2.ok = true;
                    c2.cached = true;
                    c2.offline = r.offline;
                    c2.json = new JSONObject();
                    try { c2.json.put("settings", fallback); } catch (Throwable ignored) { }
                    cb.onResult(c2);
                } else {
                    cb.onResult(r);
                }
            }
        });
    }

    // ------------------------------------------------------------------ الموافقة على السياسات

    public static boolean hasLocalConsent(Context c, int version) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("consent_version", 0) == version && version > 0;
    }

    public static void saveLocalConsent(Context c, int version) {
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("consent_version", version).putLong("consent_at", System.currentTimeMillis()).apply();
    }

    /** يرسل الموافقة للخادم (يتطلب تسجيل دخول). يُستدعى أيضاً قبل فتح المحادثة إذا لم تُرسل بعد. */
    public static void pushConsent(Context c, int version, Callback cb) {
        try {
            JSONObject b = new JSONObject();
            b.put("version", version);
            post(c, "/ads/consent", b, true, cb);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ حساب السعر (نفس معادلة الخادم)

    public static final class Line {
        public final String label;
        public final double amount;
        Line(String label, double amount) { this.label = label; this.amount = amount; }
    }

    public static final class Quote {
        public String slot = "";
        public int days;
        public double base;
        public double discountPct;
        public double discount;
        public double subtotal;
        public double targeting;
        public double addons;
        public double total;
        public long viewsMin;
        public long viewsMax;
        public final ArrayList<Line> targetingLines = new ArrayList<Line>();
        public final ArrayList<Line> addonLines = new ArrayList<Line>();
        public boolean valid;
    }

    private static double r2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static Quote quote(JSONObject settings, String slot, int days, int cities, int langs, boolean hours,
                              boolean exclusive, boolean video, boolean design) {
        Quote q = new Quote();
        try {
            JSONObject slots = settings.getJSONObject("slots");
            JSONObject s = slots.optJSONObject(slot);
            if (s == null || !s.optBoolean("enabled", true)) return q;
            JSONObject cd = settings.optJSONObject("custom_days");
            int minD = cd == null ? 1 : cd.optInt("min", 1);
            int maxD = cd == null ? 730 : cd.optInt("max", 730);
            days = Math.max(minD, Math.min(maxD, days));
            q.slot = slot;
            q.days = days;
            q.base = r2(s.optDouble("price_per_day", 0) * days);
            JSONArray disc = settings.optJSONArray("discounts");
            double pct = 0;
            if (disc != null) {
                for (int i = 0; i < disc.length(); i++) {
                    JSONObject d = disc.optJSONObject(i);
                    if (d != null && days >= d.optInt("min_days", 1000000) && d.optDouble("pct", 0) > pct) pct = d.optDouble("pct", 0);
                }
            }
            q.discountPct = pct;
            q.discount = r2(q.base * pct / 100.0);
            q.subtotal = r2(q.base - q.discount);
            JSONObject t = settings.optJSONObject("targeting");
            double cityPct = t == null ? 0 : t.optDouble("city_pct", 0);
            double langPct = t == null ? 0 : t.optDouble("lang_pct", 0);
            double hoursPct = t == null ? 0 : t.optDouble("hours_pct", 0);
            double reach = s.optDouble("views_per_day", 0) * days;
            if (cities > 0) {
                double v = r2(q.subtotal * cityPct / 100.0);
                q.targeting += v;
                q.targetingLines.add(new Line("استهداف المدن (" + cities + ")", v));
                reach *= t == null ? 1 : t.optDouble("city_reach_factor", 1);
            }
            if (langs > 0) {
                double v = r2(q.subtotal * langPct / 100.0);
                q.targeting += v;
                q.targetingLines.add(new Line("استهداف اللغة", v));
                reach *= t == null ? 1 : t.optDouble("lang_reach_factor", 1);
            }
            if (hours) {
                double v = r2(q.subtotal * hoursPct / 100.0);
                q.targeting += v;
                q.targetingLines.add(new Line("ساعات الظهور", v));
                reach *= t == null ? 1 : t.optDouble("hours_reach_factor", 1);
            }
            q.targeting = r2(q.targeting);
            JSONObject ad = settings.optJSONObject("addons");
            String[] keys = new String[]{"exclusive", "video", "design"};
            boolean[] on = new boolean[]{exclusive, video, design};
            for (int i = 0; i < keys.length; i++) {
                if (!on[i] || ad == null) continue;
                JSONObject a = ad.optJSONObject(keys[i]);
                if (a == null) continue;
                double v = "pct".equals(a.optString("type")) ? r2(q.subtotal * a.optDouble("value", 0) / 100.0) : r2(a.optDouble("value", 0));
                q.addons += v;
                q.addonLines.add(new Line(a.optString("label", keys[i]), v));
            }
            q.addons = r2(q.addons);
            q.total = r2(q.subtotal + q.targeting + q.addons);
            q.viewsMin = Math.round(reach * 0.8);
            q.viewsMax = Math.round(reach * 1.2);
            q.valid = true;
        } catch (Throwable ignored) { }
        return q;
    }

    public static String money(JSONObject settings, double v) {
        String sym = "$";
        int dec = 0;
        try {
            JSONObject cur = settings.optJSONObject("currency");
            if (cur != null) {
                sym = cur.optString("symbol", "$");
                dec = cur.optInt("decimals", 0);
            }
        } catch (Throwable ignored) { }
        String n = dec <= 0 && v == Math.rint(v) ? String.format(Locale.US, "%,d", (long) v) : String.format(Locale.US, "%,." + Math.max(2, dec) + "f", v);
        return "‎" + sym + n + "‎";
    }

    public static String count(long v) {
        return String.format(Locale.US, "%,d", v);
    }

    public static String durationLabel(int days) {
        if (days == 1) return "يوم";
        if (days == 3) return "3 أيام";
        if (days == 7) return "أسبوع";
        if (days == 14) return "أسبوعان";
        if (days == 30) return "شهر";
        if (days == 60) return "شهران";
        if (days == 90) return "3 أشهر";
        if (days == 180) return "6 أشهر";
        if (days == 365) return "سنة";
        if (days >= 2 && days <= 10) return days + " أيام";
        return days + " يوماً";
    }
}
