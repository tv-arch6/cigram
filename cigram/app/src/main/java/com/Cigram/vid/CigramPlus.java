package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cigram Plus - the paid plan, activated ONLY by an activation code.
 *
 * Flow: the user talks to us on WhatsApp (message built here), pays, receives a code, types it in
 * "ضع كود التفعيل". The SERVER checks the code and answers with the end date; the app only caches that answer.
 * The app never trusts a local "I paid" claim: no code accepted by the server = no Plus.
 *
 * Server contract (see PLUS-SERVER-CONTRACT.md) on the auth worker, header "Authorization: Bearer <session token>":
 *   POST /plus/redeem   {"code":"ABCD-1234-EFGH"}  -> {"ok":true,"plan":"plus","plus_until":<epoch ms>}
 *                                                     {"ok":false,"error":"invalid_code|code_used|code_expired|rate_limited"}
 *   GET  /plus/status                              -> {"ok":true,"plan":"plus"|"free","plus_until":<epoch ms>}
 * Until those two routes exist the app shows "خدمة التفعيل غير متاحة بعد" and nothing else changes.
 *
 * What Plus changes in the app: no ads at all (adFreeUser, in-house ads, the companion player gets ads=false).
 */
public final class CigramPlus {

    static final String AUTH_BASE = "https://cigram-auth-api.wwq-mixtv.workers.dev";
    /** The Plus worker (plus-worker/worker.js). If you mount the routes inside the auth worker instead, set this equal to AUTH_BASE. */
    static final String PLUS_BASE = "https://cigram-plus-api.wwq-mixtv.workers.dev";

    /** WhatsApp number, international format, digits only. Given as 7854314651 = Iraqi mobile 0785 431 4651 -> +964. */
    static final String WHATSAPP_NUMBER = "9647854314651";

    static final String[] METHOD_KEYS = {"mastercard", "zaincash", "asiacell"};
    static final String[] METHOD_LABELS = {"ماستر كارد", "زين كاش", "اسياسيل"};

    // result codes of redeem()
    public static final int OK = 0;
    public static final int INVALID = 1;
    public static final int USED = 2;
    public static final int EXPIRED = 3;
    public static final int RATE_LIMITED = 4;
    public static final int NETWORK = 5;
    public static final int NOT_AVAILABLE = 6;
    public static final int LOGIN_REQUIRED = 7;
    public static final int INTRO_NOT_ELIGIBLE = 8;

    public interface Result {
        /** Main thread. {@code until} = end of Plus (epoch ms) when status == OK. */
        void onResult(int status, long until);
    }

    private static final String PREFS = "cigram_plus";
    private static final long REFRESH_GAP_MS = 10L * 60L * 1000L;
    private static Handler mainHandler;

    /** Created on first use (class loading stays free of Looper work). */
    private static synchronized Handler main() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile boolean refreshing;
    private static volatile long lastRefreshAt;

    private CigramPlus() { }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** false in the Google Play build: no purchase button / payment methods / WhatsApp (Play Payments policy). */
    static boolean salesEnabled() {
        return BuildConfig.PLUS_SALES;
    }

    // ------------------------------------------------------------------ state (cached server answer)

    /** True while a server-confirmed Plus period of the CURRENT account has not ended. Cheap: two prefs reads. */
    public static boolean isPlus(Context c) {
        try {
            if (c == null || !CigramUserData.isLoggedIn(c)) return false;
            SharedPreferences p = sp(c);
            if (!CigramUserData.owner(c).equals(p.getString("owner", ""))) return false;   // another account's cache
            return p.getLong("until", 0L) > System.currentTimeMillis();
        } catch (Throwable t) {
            return false;
        }
    }

    public static long until(Context c) {
        try {
            if (!isPlus(c)) return 0L;
            return sp(c).getLong("until", 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static int daysLeft(Context c) {
        long u = until(c);
        if (u <= 0L) return 0;
        return (int) Math.max(0L, (u - System.currentTimeMillis() + 86399999L) / 86400000L);
    }

    public static String formatDate(long ms) {
        return new SimpleDateFormat("yyyy/MM/dd", Locale.US).format(new Date(ms));
    }

    private static void save(Context c, long until) {
        save(c, until, -1L);
    }

    /** {@code from} = activation time of the current Plus (epoch ms), or -1 = keep what is stored. */
    private static void save(Context c, long until, long from) {
        try {
            SharedPreferences.Editor e = sp(c).edit().putString("owner", CigramUserData.owner(c)).putLong("until", Math.max(0L, until));
            if (until <= 0L) e.remove("from");
            else if (from > 0L) e.putLong("from", from);
            e.apply();
        } catch (Throwable ignored) { }
    }

    /** When the running Plus was activated (epoch ms), 0 = unknown. Shown as "from <date> to <date>". */
    public static long activatedAt(Context c) {
        try {
            if (!isPlus(c)) return 0L;
            return sp(c).getLong("from", 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** "من 2026/11/02 إلى 2027/05/02" (just "حتى ..." when the start is unknown). */
    public static String periodText(Context c) {
        long to = until(c);
        if (to <= 0L) return "";
        long from = activatedAt(c);
        return from > 0L ? "من " + formatDate(from) + " إلى " + formatDate(to) : "حتى " + formatDate(to);
    }

    // ------------------------------------------------------------------ server

    /** Ask the server for the plan (throttled; silent on failure: the cached answer keeps working offline). */
    public static void refresh(final Context c, final boolean force, final Runnable done) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        // "done" runs only after a real answer from the server, so callers can safely re-render from it (no loops)
        if (!CigramUserData.isLoggedIn(app)) return;
        long now = System.currentTimeMillis();
        if (refreshing || (!force && now - lastRefreshAt < REFRESH_GAP_MS)) return;
        refreshing = true;
        final String token = CigramUserData.token(app);
        IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    Response r = call("GET", "/plus/status", null, token);
                    if (r.code == 200 && r.json != null && r.json.optBoolean("ok", false)) {
                        long until = "plus".equals(r.json.optString("plan")) ? r.json.optLong("plus_until", 0L) : 0L;
                        save(app, until, r.json.optLong("activated_at", -1L));
                        saveIntro(app, r.json.optBoolean("intro_eligible", true));
                        final String serverIcon = r.json.optString("app_icon", "");
                        main().post(new Runnable() {
                            @Override public void run() {
                                CigramAppIcon.onServerChoice(app, serverIcon);
                                CigramAppIcon.enforce(app);
                            }
                        });
                        lastRefreshAt = System.currentTimeMillis();
                        if (done != null) main().post(done);
                    }
                } catch (Throwable ignored) {
                } finally {
                    refreshing = false;
                }
            }
        });
    }

    /** Tell the server which launcher icon this Plus account uses (fire and forget; the local choice already applies). */
    public static void syncAppIcon(Context c, final String id) {
        if (c == null || !CigramUserData.isLoggedIn(c)) return;
        final String token = CigramUserData.token(c.getApplicationContext());
        IO.execute(new Runnable() {
            @Override public void run() {
                try { call("POST", "/plus/prefs", new JSONObject().put("app_icon", id).toString(), token); } catch (Throwable ignored) { }
            }
        });
    }

    /** Normalise what the user typed: upper-case, no spaces, only A-Z 0-9 and '-'. Empty = unusable. */
    static String cleanCode(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toUpperCase(Locale.US).replaceAll("\\s+", "");
        if (!s.matches("[A-Z0-9-]{6,32}")) return "";
        return s;
    }

    public static void redeem(final Context c, String rawCode, final Result cb) {
        final String code = cleanCode(rawCode);
        if (code.length() == 0) { cb.onResult(INVALID, 0L); return; }
        final Context app = c.getApplicationContext();
        if (!CigramUserData.isLoggedIn(app)) { cb.onResult(LOGIN_REQUIRED, 0L); return; }
        final String token = CigramUserData.token(app);
        IO.execute(new Runnable() {
            @Override public void run() {
                int status = NETWORK;
                long until = 0L;
                try {
                    Response r = call("POST", "/plus/redeem", new JSONObject().put("code", code).toString(), token);
                    if (r.code == 200 && r.json != null && r.json.optBoolean("ok", false)) {
                        until = r.json.optLong("plus_until", 0L);
                        if (until > System.currentTimeMillis()) {
                            save(app, until, r.json.optLong("activated_at", System.currentTimeMillis()));
                            saveIntro(app, false);                       // the first redeem always uses up the intro offer
                            lastMonths = r.json.optInt("months", PLAN_MONTHS);
                            lastRefreshAt = System.currentTimeMillis();
                            status = OK;
                        } else {
                            status = INVALID;
                        }
                    } else if (r.code == 404 || r.code == 501) {
                        status = NOT_AVAILABLE;
                    } else if (r.code == 401 || r.code == 403) {
                        status = LOGIN_REQUIRED;
                    } else if (r.code == 429) {
                        status = RATE_LIMITED;
                    } else if (r.json != null) {
                        String e = r.json.optString("error", "");
                        if ("code_used".equals(e)) status = USED;
                        else if ("intro_not_eligible".equals(e)) { status = INTRO_NOT_ELIGIBLE; saveIntro(app, false); }
                        else if ("code_expired".equals(e)) status = EXPIRED;
                        else if ("rate_limited".equals(e)) status = RATE_LIMITED;
                        else if (r.code >= 400 && r.code < 500) status = INVALID;
                    }
                } catch (Throwable t) {
                    status = NETWORK;
                }
                final int fs = status;
                final long fu = until;
                main().post(new Runnable() {
                    @Override public void run() { cb.onResult(fs, fu); }
                });
            }
        });
    }

    public static String messageFor(int status) {
        switch (status) {
            case OK: return "تم تفعيل Plus";
            case INVALID: return "الكود غير صحيح. تأكد منه وحاول مرة ثانية.";
            case USED: return "هذا الكود مستخدم من قبل.";
            case EXPIRED: return "انتهت صلاحية هذا الكود. تواصل معنا للحصول على كود جديد.";
            case RATE_LIMITED: return "محاولات كثيرة. انتظر قليلاً ثم حاول.";
            case LOGIN_REQUIRED: return "سجّل الدخول أولاً ثم فعّل الكود.";
            case INTRO_NOT_ELIGIBLE: return "هذا كود عرض الـ3 أشهر وهو لأول اشتراك فقط، وحسابك استخدم عرضاً سابقاً. تواصل معنا لكود 6 أشهر.";
            case NOT_AVAILABLE: return "خدمة التفعيل غير متاحة بعد. تواصل معنا على واتساب.";
            default: return "تعذّر الاتصال. تحقق من الإنترنت وحاول مرة ثانية.";
        }
    }

    // ------------------------------------------------------------------ what Plus unlocks

    /** One activation code = 6 months. The SERVER computes plus_until; this number is only used in texts. */
    static final int PLAN_MONTHS = 6;

    /** First subscription of an account: 3 months for 1,500 IQD, once. Afterwards every code is 6 months for 10,000 IQD. Prices are texts only: the SERVER decides. */
    static final int INTRO_MONTHS = 3;
    static final int INTRO_PRICE_IQD = 1500;
    static final int STANDARD_MONTHS = 6;
    static final int STANDARD_PRICE_IQD = 10000;

    /** Months of the code redeemed last in this process (for the gift text). */
    static volatile int lastMonths = PLAN_MONTHS;

    private static void saveIntro(Context c, boolean eligible) {
        try {
            sp(c).edit().putString("intro_owner", CigramUserData.owner(c)).putBoolean("intro", eligible).apply();
        } catch (Throwable ignored) { }
    }

    /** True while this account may still buy the 1,500 IQD / 3 month offer (unknown = true; the server has the last word). */
    static boolean introEligible(Context c) {
        try {
            if (c == null || !CigramUserData.isLoggedIn(c)) return true;
            SharedPreferences p = sp(c);
            if (!CigramUserData.owner(c).equals(p.getString("intro_owner", ""))) return true;
            return p.getBoolean("intro", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** "1,500 د.ع" style number. */
    static String iqd(int v) {
        return String.format(Locale.US, "%,d", v) + " د.ع";
    }

    /** The price line of the Plus card, or null in the Google Play build (no prices there). */
    static String priceLine(Context c) {
        if (!salesEnabled()) return null;
        if (introEligible(c)) return iqd(INTRO_PRICE_IQD) + " لأول " + INTRO_MONTHS + " أشهر · ثم " + iqd(STANDARD_PRICE_IQD) + " كل " + STANDARD_MONTHS + " أشهر";
        return iqd(STANDARD_PRICE_IQD) + " كل " + STANDARD_MONTHS + " أشهر";
    }

    /** Without Plus the picture of titles that offer several qualities stays at 720p or lower; Plus unlocks 1080p up to 4K. */
    static final int FREE_MAX_HEIGHT = 720;

    /** Max video height the track selector may use (no limit with Plus). */
    static int maxVideoHeight(Context c) {
        return isPlus(c) ? Integer.MAX_VALUE : FREE_MAX_HEIGHT;
    }

    /** The preferred quality the player may apply: without Plus never above 720p (0 = automatic becomes 720p too). */
    static int clampQuality(Context c, int wanted) {
        if (isPlus(c)) return wanted;
        return (wanted <= 0 || wanted > FREE_MAX_HEIGHT) ? FREE_MAX_HEIGHT : wanted;
    }

    /** End date of a Plus period that already ended for the CURRENT account, or 0 (never had one / still running). */
    static long expiredAt(Context c) {
        try {
            if (c == null || !CigramUserData.isLoggedIn(c)) return 0L;
            SharedPreferences p = sp(c);
            if (!CigramUserData.owner(c).equals(p.getString("owner", ""))) return 0L;
            long u = p.getLong("until", 0L);
            return (u > 0L && u <= System.currentTimeMillis()) ? u : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ WhatsApp

    /** The exact message the owner asked for. */
    static String subscribeMessage(String methodLabel) {
        return "اريد الاشتراك في Plus Cigram عن طريق «" + methodLabel + "»";
    }

    /** Same message plus the plan line so the seller knows which code to send. */
    static String subscribeMessage(String methodLabel, boolean intro) {
        return subscribeMessage(methodLabel) + "\nالباقة: "
                + (intro ? INTRO_MONTHS + " أشهر - " + iqd(INTRO_PRICE_IQD) + " (عرض أول اشتراك)" : STANDARD_MONTHS + " أشهر - " + iqd(STANDARD_PRICE_IQD));
    }

    /** Priority support for members: the message carries the Plus tag and the account id. */
    static void openSupport(Activity a) {
        String id = CigramPublicId.get(a);
        String text = "أنا عضو Plus في Cigram" + (id.length() > 0 ? "\nالمعرّف: " + id : "") + "\nأحتاج مساعدة:";
        openWhatsAppText(a, text);
    }

    private static void openWhatsAppText(Activity a, String text) {
        Uri uri = Uri.parse("https://wa.me/" + WHATSAPP_NUMBER + "?text=" + Uri.encode(text));
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.setPackage("com.whatsapp");
            a.startActivity(i);
            return;
        } catch (Throwable ignored) { }
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Throwable t) {
            CigramSilent.toast(a, "تعذّر فتح واتساب", android.widget.Toast.LENGTH_SHORT);
        }
    }

    /** Opens the WhatsApp chat with the prepared text (the WhatsApp app if installed, else the browser). */
    static void openWhatsApp(Activity a, String methodLabel) {
        Uri uri = Uri.parse("https://wa.me/" + WHATSAPP_NUMBER + "?text=" + Uri.encode(subscribeMessage(methodLabel, introEligible(a))));
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.setPackage("com.whatsapp");
            a.startActivity(i);
            return;
        } catch (Throwable ignored) { }
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Throwable t) {
            CigramSilent.toast(a, "تعذّر فتح واتساب", android.widget.Toast.LENGTH_SHORT);
        }
    }

    // ------------------------------------------------------------------ http

    private static final class Response {
        int code;
        JSONObject json;
    }

    private static Response call(String method, String path, String body, String token) throws Exception {
        Response r = new Response();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(PLUS_BASE + path).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json");
            if (token != null && token.length() > 0) conn.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] data = body.getBytes("UTF-8");
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
            String text = "";
            if (in != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 65536) out.write(buf, 0, n);
                in.close();
                text = out.toString("UTF-8");
            }
            try {
                r.json = text.length() == 0 ? new JSONObject() : new JSONObject(text);
            } catch (Throwable parse) {
                r.json = new JSONObject();
            }
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) { }
            }
        }
        return r;
    }
}
