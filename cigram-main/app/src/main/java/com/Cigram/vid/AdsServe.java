package com.Cigram.vid;

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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * طبقة بيانات الإعلانات داخل التطبيق (المرحلة هـ):
 *  - جلب GET /ads?slot= (الخادم يخزّنه على حافة Cloudflare لثوانٍ) مع نسخة محلية وعدم إعادة الجلب أكثر من مرة كل 60 ثانية.
 *  - تصفية الحملات المنتهية/المخفية/التي بلغت سقفها اليومي بتوقيت الخادم المُزامَن (CigramAds.now).
 *  - تناوب عادل: الأولوية الأعلى أولاً، وضمن نفس الأولوية دوران دائري محفوظ.
 *  - طابور أحداث (مشاهدة/نقر) محفوظ محلياً ويُرسل دفعات إلى POST /ads/track.
 *  - الخصوصية: لا يُرسل إلا معرّف جهاز عشوائي (UUID) لا يرتبط بحساب المستخدم.
 */
public final class AdsServe {

    private static final String PREFS = "cigram_ads_serve";
    private static final long REFETCH_MS = 60000L;
    private static final long STALE_OK_MS = 6L * 3600000L;
    private static final int QUEUE_MAX = 100;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AdsServe() { }

    // ------------------------------------------------------------------ النموذج

    public static final class Ad {
        public String campaignId = "", creativeId = "", slot = "", type = "image";
        public String title = "", body = "", cta = "", mediaUrl = "", thumbUrl = "", bgColor = "", textColor = "";
        public String actionType = "none", actionValue = "", actionExtra = "";
        public int priority, dailyCap;
        public long startAt, endAt;
        public boolean exclusive;

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
            a.exclusive = o.optBoolean("exclusive");
            JSONObject act = o.optJSONObject("action");
            if (act != null) {
                a.actionType = act.optString("type", "none");
                a.actionValue = act.optString("value");
                a.actionExtra = act.optString("extra");
            }
            return a;
        }

        public boolean isVideo() { return "video".equals(type); }
        public boolean isText() { return "text".equals(type); }
        public boolean clickable() { return !"none".equals(actionType); }

        /** رابط الصورة التي تُعرض (للفيديو: الصورة المصغّرة إن وُجدت وإلا إطار من الفيديو). */
        public String posterUrl() {
            if (isText()) return "";
            if (isVideo()) return thumbUrl.length() > 0 ? thumbUrl : mediaUrl;
            return mediaUrl.length() > 0 ? mediaUrl : thumbUrl;
        }

        public boolean posterIsVideoFrame() { return isVideo() && thumbUrl.length() == 0; }
    }

    public interface Cb { void onAds(List<Ad> eligible); }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ الجلب والتخزين

    private static final java.util.HashMap<String, Long> lastFetch = new java.util.HashMap<String, Long>();

    /** يُرجع فوراً ما في النسخة المحلية الصالحة (دون شبكة) — null إن لم يوجد شيء صالح. */
    public static List<Ad> cached(Context c, String slot) {
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
     * يعيد الإعلانات الصالحة للعرض الآن عبر المستمع على الخيط الرئيسي (دائماً مرة واحدة).
     * إن كانت النسخة المحلية حديثة (أقل من 60ث) لا يطلب الشبكة؛ وإن فشلت الشبكة يستخدم النسخة المحلية.
     */
    public static void load(final Context c, final String slot, final Cb cb) {
        final Context app = c.getApplicationContext();
        Long last = lastFetch.get(slot);
        long nowMs = System.currentTimeMillis();
        if (last != null && nowMs - last < REFETCH_MS) {
            List<Ad> hit = cached(app, slot);
            if (hit != null) { cb.onAds(hit); return; }
        }
        String lang = Locale.getDefault().getLanguage();
        String path = "/ads?slot=" + slot + "&lang=" + enc(lang) + "&app_version=" + enc(CigramAds.versionCode(app));
        CigramAds.get(app, path, false, new CigramAds.Callback() {
            @Override public void onResult(CigramAds.Result r) {
                try {
                    if (r.ok && r.json != null && r.json.optJSONArray("ads") != null) {
                        lastFetch.put(slot, System.currentTimeMillis());
                        CigramAds.syncClock(r.json.optLong("server_time"));
                        JSONObject store = new JSONObject();
                        store.put("at", System.currentTimeMillis());
                        store.put("ads", r.json.optJSONArray("ads"));
                        sp(app).edit().putString("cache_" + slot, store.toString()).apply();
                        cb.onAds(eligible(app, parse(r.json.optJSONArray("ads"))));
                        return;
                    }
                } catch (Throwable ignored) { }
                List<Ad> fallback = cached(app, slot);
                cb.onAds(fallback == null ? new ArrayList<Ad>() : fallback);
            }
        });
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8"); } catch (Throwable t) { return ""; }
    }

    // ------------------------------------------------------------------ الأهلية (وقت، إخفاء، سقف يومي)

    public static List<Ad> eligible(Context c, List<Ad> all) {
        List<Ad> out = new ArrayList<Ad>();
        long now = CigramAds.now();
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

    /** إخفاء حملة عن هذا الجهاز لمدة 7 أيام. */
    public static void hide(Context c, String campaignId) {
        hide(c, campaignId, 7L * 86400000L);
    }

    public static void hide(Context c, String campaignId, long ms) {
        try {
            JSONObject h = hiddenMap(c);
            h.put(campaignId, System.currentTimeMillis() + ms);
            sp(c).edit().putString("hidden", h.toString()).apply();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ التناوب العادل

    /** ترتيب الأولوية الأعلى أولاً، ثم تدوير دائري (محفوظ) ضمن نفس الأولوية. */
    public static List<Ad> order(Context c, String slot, List<Ad> eligible) {
        List<Ad> list = new ArrayList<Ad>(eligible);
        java.util.Collections.sort(list, new java.util.Comparator<Ad>() {
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

    /** يقدّم المؤشر الدوّار خطوة (يُستدعى كلما عُرض إعلان جديد في المساحة). */
    public static void advance(Context c, String slot) {
        SharedPreferences p = sp(c);
        p.edit().putInt("rot_" + slot, (p.getInt("rot_" + slot, 0) + 1) & 0xFFFF).apply();
    }

    // ------------------------------------------------------------------ التتبّع

    private static boolean flushScheduled = false;
    private static boolean flushing = false;

    public static void impression(Context c, Ad ad) {
        bumpCap(c, ad.campaignId);
        enqueue(c, "imp", ad);
    }

    public static void click(Context c, Ad ad) {
        enqueue(c, "click", ad);
        flush(c);
    }

    private static synchronized void enqueue(Context c, String t, Ad ad) {
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

    /** يرسل ما في الطابور (حتى 20 حدثاً في الطلب). عند الفشل يبقى في الطابور للمحاولة لاحقاً. */
    public static void flush(Context c) {
        final Context app = c.getApplicationContext();
        if (flushing) return;
        final JSONArray batch;
        final int sent;
        synchronized (AdsServe.class) {
            try {
                JSONArray q = new JSONArray(sp(app).getString("q", "[]"));
                if (q.length() == 0) return;
                batch = new JSONArray();
                for (int i = 0; i < q.length() && i < 20; i++) batch.put(q.get(i));
                sent = batch.length();
            } catch (Throwable t) {
                return;
            }
        }
        flushing = true;
        try {
            JSONObject body = new JSONObject().put("device", CigramAds.deviceId(app)).put("events", batch);
            CigramAds.post(app, "/ads/track", body, false, new CigramAds.Callback() {
                @Override public void onResult(CigramAds.Result r) {
                    flushing = false;
                    // نحذف المرسَل عند النجاح، وكذلك عند رفض الخادم النهائي (4xx غير 429) كي لا يتكدّس طابور تالف
                    boolean drop = r.ok || (r.code >= 400 && r.code < 500 && r.code != 429);
                    if (!drop) return;
                    synchronized (AdsServe.class) {
                        try {
                            JSONArray q = new JSONArray(sp(app).getString("q", "[]"));
                            JSONArray rest = new JSONArray();
                            for (int i = sent; i < q.length(); i++) rest.put(q.get(i));
                            sp(app).edit().putString("q", rest.toString()).apply();
                            if (rest.length() > 0) scheduleFlush(app);
                        } catch (Throwable ignored) { }
                    }
                }
            });
        } catch (Throwable t) {
            flushing = false;
        }
    }

    // ------------------------------------------------------------------ الإبلاغ

    public static void report(Context c, Ad ad, String reason, final Runnable done) {
        try {
            JSONObject body = new JSONObject().put("campaign_id", ad.campaignId).put("reason", reason).put("device", CigramAds.deviceId(c));
            CigramAds.post(c, "/ads/report", body, false, new CigramAds.Callback() {
                @Override public void onResult(CigramAds.Result r) { if (done != null) done.run(); }
            });
        } catch (Throwable t) {
            if (done != null) done.run();
        }
    }

    // ------------------------------------------------------------------ تنفيذ الإجراء عند النقر

    /** يفتح الوجهة. روابط https فقط (و market: للمتجر)؛ الاتصال يفتح المتصل دون مكالمة تلقائية (لا حاجة لإذن). */
    public static void perform(Activity a, Ad ad) {
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
                AdsUi.toast(a, "تم نسخ الكود: " + v);
            }
        } catch (Throwable t) {
            AdsUi.toast(a, "تعذّر فتح الرابط.");
        }
    }
}
