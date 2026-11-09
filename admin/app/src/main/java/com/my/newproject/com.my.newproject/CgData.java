package com.my.newproject;

import android.app.Activity;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * In-memory snapshot of movies.json + admin meta + sections, with the derived logic the admin
 * screens share: item kind, series/episode links, issues & colors, subtitle state, next episode,
 * search/filters and slug generation. Old data without the new fields is always handled.
 */
final class CgData {

    static final int K_MOVIE = 0;
    static final int K_SERIES = 1;
    static final int K_EPISODE = 2;

    static final int SUB_OK = 0;
    static final int SUB_NONE = 1;
    static final int SUB_MISSING = 2;
    static final int SUB_BROKEN = 3;
    static final int SUB_INVALID = 4;

    static final class Issue {
        final int level; // 1 warning, 2 error
        final String text;

        Issue(int level, String text) {
            this.level = level;
            this.text = text;
        }
    }

    static final class Filter {
        int kind = -1;
        boolean noSubtitle;
        boolean noVideo;
        boolean errorsOnly;
        boolean featuredOnly;
        boolean hiddenOnly;
        boolean publishedOnly;
        String sectionId = "";
    }

    // ------------------------------------------------------------ snapshot

    private static volatile CgData cur;

    static CgData cur() {
        return cur;
    }

    static boolean loaded() {
        return cur != null;
    }

    final ArrayList<JSONObject> items = new ArrayList<JSONObject>();
    JSONObject meta = new JSONObject();
    final ArrayList<JSONObject> sections = new ArrayList<JSONObject>();
    boolean sectionsDerived = true;
    boolean adminApi = true;      // false when the Worker has no v2 routes yet
    String warning = "";
    long loadedAt = System.currentTimeMillis();
    boolean fromCache = false;    // true when this snapshot came from the on-device cache, not a fresh server answer

    private static final String CACHE_KEY = "data_cache_v1";
    private static final String CACHE_AT_KEY = "data_cache_at_v1";

    private final HashMap<String, JSONObject> byId = new HashMap<String, JSONObject>();
    private final HashMap<String, JSONObject> headerByKey = new HashMap<String, JSONObject>();
    private final HashMap<String, ArrayList<JSONObject>> epsByKey = new HashMap<String, ArrayList<JSONObject>>();
    private final HashMap<String, Integer> epDup = new HashMap<String, Integer>();
    private final HashSet<String> dupIds = new HashSet<String>();
    private final HashMap<String, ArrayList<Issue>> issueCache = new HashMap<String, ArrayList<Issue>>();
    private final HashMap<String, String> hayCache = new HashMap<String, String>();

    // ------------------------------------------------------------ 18.3: one load at a time, fresh-enough snapshots, no stale overwrite
    //
    // Before: every screen / timer called load() on its own, each one downloaded /movies (5 MB) + /admin/meta + /admin/sections, and
    // an answer that started before an edit could replace the edited snapshot. Now: a call while a load runs JOINS it (one request
    // set, every caller is answered), loadIfStale() skips the request when the snapshot is young, and an answer that was requested
    // before an edit finished never replaces the edited snapshot.

    private static final CgFlight<Waiter> FLIGHT = new CgFlight<Waiter>();
    private static final java.util.concurrent.atomic.AtomicInteger EDITS = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile int loadFailures;

    private static final class Waiter {
        final Activity a;
        final CgHttp.Done<CgData> done;

        Waiter(Activity a, CgHttp.Done<CgData> done) {
            this.a = a;
            this.done = done;
        }
    }

    /** A local edit (replaceItem) happened: a load that started earlier must not overwrite it. */
    static void noteEdit() {
        EDITS.incrementAndGet();
    }

    /** True when the snapshot came from the server less than {@code maxAgeMs} ago (the on-device cache never counts as fresh). */
    static boolean freshWithin(long maxAgeMs) {
        CgData d = cur;
        return CgFlight.isFresh(d != null, d != null && d.fromCache, d == null ? 0L : d.loadedAt, System.currentTimeMillis(), maxAgeMs);
    }

    /** Number of consecutive failed loads (drives the back-off of background refreshes). */
    static int failures() {
        return loadFailures;
    }

    /** The current snapshot when it is young enough, otherwise one (shared) load. */
    static void loadIfStale(Activity a, long maxAgeMs, CgHttp.Done<CgData> done) {
        if (freshWithin(maxAgeMs)) {
            done.ok(cur);
            return;
        }
        load(a, done);
    }

    /** Loads everything in parallel on background threads, then installs the snapshot. A call during a running load joins it. */
    static void load(final Activity a, final CgHttp.Done<CgData> done) {
        if (!FLIGHT.joinOrStart(new Waiter(a, done))) return;     // a load is running: this caller is answered by it
        final int editsAtStart = EDITS.get();
        CgHttp.bg(new Runnable() {
            @Override
            public void run() {
                CgData result = null;
                String err = null;
                try {
                    result = fetchSnapshot(a);
                } catch (Throwable e) {
                    err = CgHttp.friendly(e.getMessage() == null ? "" : e.getMessage());
                }
                if (result != null) {
                    CgData current = cur;
                    if (CgFlight.staleAfterEdit(editsAtStart, EDITS.get(), current != null, result.fromCache)) {
                        result = current;               // an edit finished meanwhile: keep it (the next refresh brings the server's view)
                    } else {
                        cur = result;
                        loadFailures = 0;
                    }
                } else {
                    loadFailures = Math.min(loadFailures + 1, 8);
                }
                for (Waiter w : FLIGHT.finish()) deliver(w, result, err);
            }
        });
    }

    private static void deliver(final Waiter w, final CgData d, final String err) {
        final Activity a = w.a;
        if (a == null || a.isFinishing()) return;
        a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (a.isFinishing()) return;
                if (err != null) w.done.fail(err);
                else w.done.ok(d);
            }
        });
    }

    private static CgData fetchSnapshot(final Activity a) throws Exception {
                final CgHttp.Res[] r = new CgHttp.Res[3];
                Thread t0 = new Thread(new Runnable() {
                    @Override
                    public void run() { r[0] = CgHttp.apiGet("/movies"); }
                });
                Thread t1 = new Thread(new Runnable() {
                    @Override
                    public void run() { r[1] = CgHttp.apiGet("/admin/meta"); }
                });
                Thread t2 = new Thread(new Runnable() {
                    @Override
                    public void run() { r[2] = CgHttp.apiGet("/admin/sections"); }
                });
                t0.start();
                t1.start();
                t2.start();
                t0.join();
                t1.join();
                t2.join();

                // movies.json failed outright (no connection, DNS, server down, ...): fall back to whatever
                // the device last saw rather than leaving the admin with a blank "تعذر التحميل" screen. The
                // GET itself already retried transient hiccups inside CgHttp.apiGet, so a failure here means
                // the server genuinely could not be reached this time.
                if (r[0].error != null || r[0].code < 200 || r[0].code >= 300) {
                    CgData cached = loadCache(a);
                    if (cached != null) return cached;
                    throw new Exception("تعذر تحميل movies.json: " + r[0].message());
                }
                JSONArray arr = r[0].array;
                if (arr == null && r[0].json != null) {
                    arr = r[0].json.optJSONArray("movies");
                    if (arr == null) arr = r[0].json.optJSONArray("items");
                    if (arr == null) arr = r[0].json.optJSONArray("data");
                }
                if (arr == null) arr = new JSONArray();

                CgData d = new CgData();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) d.items.add(o);
                }
                if (r[1].ok() && r[1].json != null) {
                    d.meta = r[1].json;
                } else if (r[1].code == 404 || "not_found".equals(r[1].errorCode())) {
                    d.adminApi = false;
                    d.warning = "الـ Worker الحالي قديم ولا يحتوي مسارات الإدارة الجديدة. ارفع ملف cigram-admin-worker-v2.js إلى Cloudflare أولاً.";
                } else if (r[1].code == 401) {
                    throw new Exception("رمز الإدارة غير صحيح (401). تأكد أن ADMIN_TOKEN في الـ Worker يطابق الرمز في التطبيق.");
                } else if (!r[1].ok()) {
                    d.warning = "تعذر تحميل ملاحظات الأدمن: " + r[1].message();
                }
                if (r[2].ok() && r[2].json != null) {
                    JSONArray secs = r[2].json.optJSONArray("sections");
                    d.sectionsDerived = !"registry".equals(r[2].json.optString("source", "derived"));
                    if (secs != null) {
                        for (int i = 0; i < secs.length(); i++) {
                            JSONObject o = secs.optJSONObject(i);
                            if (o != null) d.sections.add(o);
                        }
                    }
                }
                d.reindex();
                saveCache(a, d);
                return d;
    }

    // ------------------------------------------------------------ on-device cache
    //
    // Keeps the last successful snapshot on disk so a dropped connection or a Worker that is briefly down
    // shows the admin yesterday's numbers (clearly marked as such) instead of an empty error screen.

    private static JSONObject toCacheJson(CgData d) {
        JSONObject o = new JSONObject();
        try {
            JSONArray items = new JSONArray();
            for (int i = 0; i < d.items.size(); i++) items.put(d.items.get(i));
            JSONArray secs = new JSONArray();
            for (int i = 0; i < d.sections.size(); i++) secs.put(d.sections.get(i));
            o.put("items", items);
            o.put("meta", d.meta);
            o.put("sections", secs);
            o.put("sectionsDerived", d.sectionsDerived);
            o.put("adminApi", d.adminApi);
        } catch (Exception ignored) { }
        return o;
    }

    private static void saveCache(Activity a, CgData d) {
        if (a == null) return;
        final JSONObject snapshot = toCacheJson(d);
        final long at = System.currentTimeMillis();
        CgHttp.bg(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences.Editor e = a.getApplicationContext()
                            .getSharedPreferences(CgCfg.PREFS, Activity.MODE_PRIVATE).edit();
                    e.putString(CACHE_KEY, snapshot.toString());
                    e.putLong(CACHE_AT_KEY, at);
                    e.apply();
                } catch (Exception ignored) { }
            }
        });
    }

    /** Rebuilds a snapshot from the on-device cache, or null if there isn't one. */
    static CgData loadCache(Activity a) {
        if (a == null) return null;
        try {
            SharedPreferences sp = a.getApplicationContext().getSharedPreferences(CgCfg.PREFS, Activity.MODE_PRIVATE);
            String raw = sp.getString(CACHE_KEY, "");
            if (raw.length() == 0) return null;
            long at = sp.getLong(CACHE_AT_KEY, 0);
            JSONObject o = new JSONObject(raw);
            CgData d = new CgData();
            JSONArray items = o.optJSONArray("items");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it != null) d.items.add(it);
            }
            JSONObject meta = o.optJSONObject("meta");
            if (meta != null) d.meta = meta;
            JSONArray secs = o.optJSONArray("sections");
            for (int i = 0; secs != null && i < secs.length(); i++) {
                JSONObject s = secs.optJSONObject(i);
                if (s != null) d.sections.add(s);
            }
            d.sectionsDerived = o.optBoolean("sectionsDerived", true);
            d.adminApi = o.optBoolean("adminApi", true);
            d.fromCache = true;
            d.loadedAt = at > 0 ? at : System.currentTimeMillis();
            d.warning = "تعذر الوصول إلى الخادم الآن — هذه بيانات محفوظة من آخر تحميل ناجح (" + CgUi.ago(CgUi.toIso(d.loadedAt)) + "). اضغط «تحديث» لإعادة المحاولة.";
            d.reindex();
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ helpers

    static String s(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return "";
        String v = o.optString(k, "");
        return v == null ? "" : v.trim();
    }

    static int n(JSONObject o, String k) {
        String v = s(o, k);
        if (v.length() == 0) return 0;
        int i = 0;
        while (i < v.length() && Character.isDigit(v.charAt(i))) i++;
        if (i == 0) return 0;
        try {
            return Integer.parseInt(v.substring(0, Math.min(i, 9)));
        } catch (Exception e) {
            return 0;
        }
    }

    static boolean b(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return false;
        Object v = o.opt(k);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        String t = String.valueOf(v).trim().toLowerCase(Locale.US);
        return "true".equals(t) || "1".equals(t);
    }

    static int kind(JSONObject o) {
        String t = s(o, "type").toLowerCase(Locale.US);
        if ("episode".equals(t)) return K_EPISODE;
        if ("series".equals(t)) return n(o, "episode") > 0 ? K_EPISODE : K_SERIES;
        return K_MOVIE;
    }

    static String kindLabel(int k) {
        return k == K_SERIES ? "مسلسل" : k == K_EPISODE ? "حلقة" : "فيلم";
    }

    static String seriesKey(JSONObject header) {
        String sid = s(header, "series_id");
        return sid.length() > 0 ? sid : s(header, "id");
    }

    static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o.toString());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static String epCode(JSONObject ep) {
        return String.format(Locale.US, "S%02dE%02d", n(ep, "season"), n(ep, "episode"));
    }

    // ------------------------------------------------------------- indexes

    private void reindex() {
        byId.clear();
        headerByKey.clear();
        epsByKey.clear();
        epDup.clear();
        dupIds.clear();
        issueCache.clear();
        hayCache.clear();
        for (int i = 0; i < items.size(); i++) {
            JSONObject o = items.get(i);
            String id = s(o, "id");
            if (id.length() > 0) {
                if (byId.containsKey(id)) dupIds.add(id);
                else byId.put(id, o);
            }
            if (kind(o) == K_SERIES) {
                headerByKey.put(seriesKey(o), o);
                if (id.length() > 0 && !headerByKey.containsKey(id)) headerByKey.put(id, o);
            }
        }
        for (int i = 0; i < items.size(); i++) {
            JSONObject o = items.get(i);
            if (kind(o) != K_EPISODE) continue;
            String sid = s(o, "series_id");
            ArrayList<JSONObject> l = epsByKey.get(sid);
            if (l == null) {
                l = new ArrayList<JSONObject>();
                epsByKey.put(sid, l);
            }
            l.add(o);
            String dk = sid + "|" + n(o, "season") + "|" + n(o, "episode");
            Integer c = epDup.get(dk);
            epDup.put(dk, c == null ? 1 : c + 1);
        }
    }

    JSONObject byId(String id) {
        return byId.get(id);
    }

    JSONObject headerOf(JSONObject ep) {
        String sid = s(ep, "series_id");
        if (sid.length() == 0) return null;
        return headerByKey.get(sid);
    }

    ArrayList<JSONObject> episodesOf(JSONObject header) {
        ArrayList<JSONObject> out = new ArrayList<JSONObject>();
        String key = s(header, "series_id");
        String id = s(header, "id");
        ArrayList<JSONObject> a = key.length() > 0 ? epsByKey.get(key) : null;
        if (a != null) out.addAll(a);
        if (id.length() > 0 && !id.equals(key)) {
            ArrayList<JSONObject> b = epsByKey.get(id);
            if (b != null) out.addAll(b);
        }
        Collections.sort(out, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject x, JSONObject y) {
                int sx = n(x, "season");
                int sy = n(y, "season");
                if (sx != sy) return sx < sy ? -1 : 1;
                int ex = n(x, "episode");
                int ey = n(y, "episode");
                return ex < ey ? -1 : ex > ey ? 1 : 0;
            }
        });
        return out;
    }

    /** Next episode from the REAL data: highest season, highest episode number in it, plus one. */
    int[] nextEpisode(JSONObject header) {
        ArrayList<JSONObject> eps = episodesOf(header);
        int maxSeason = 0;
        for (int i = 0; i < eps.size(); i++) maxSeason = Math.max(maxSeason, n(eps.get(i), "season"));
        if (maxSeason == 0) return new int[]{1, 1};
        int maxEp = 0;
        for (int i = 0; i < eps.size(); i++) {
            JSONObject e = eps.get(i);
            if (n(e, "season") == maxSeason) maxEp = Math.max(maxEp, n(e, "episode"));
        }
        return new int[]{maxSeason, maxEp + 1};
    }

    int nextEpisodeInSeason(JSONObject header, int season) {
        ArrayList<JSONObject> eps = episodesOf(header);
        int maxEp = 0;
        for (int i = 0; i < eps.size(); i++) {
            JSONObject e = eps.get(i);
            if (n(e, "season") == season) maxEp = Math.max(maxEp, n(e, "episode"));
        }
        return maxEp + 1;
    }

    ArrayList<Integer> seasonsOf(JSONObject header) {
        ArrayList<JSONObject> eps = episodesOf(header);
        ArrayList<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < eps.size(); i++) {
            Integer sn = Integer.valueOf(n(eps.get(i), "season"));
            if (!out.contains(sn)) out.add(sn);
        }
        Collections.sort(out);
        return out;
    }

    ArrayList<JSONObject> seriesHeaders() {
        ArrayList<JSONObject> out = new ArrayList<JSONObject>();
        for (int i = 0; i < items.size(); i++) {
            if (kind(items.get(i)) == K_SERIES) out.add(items.get(i));
        }
        return out;
    }

    void replaceItem(JSONObject updated) {
        noteEdit();                                    // a load that started before this edit must not overwrite it
        String id = s(updated, "id");
        for (int i = 0; i < items.size(); i++) {
            if (s(items.get(i), "id").equals(id)) {
                items.set(i, updated);
                reindex();
                return;
            }
        }
        items.add(0, updated);
        reindex();
    }

    // ------------------------------------------------------------- slugs

    static String inferSlugFromUrl(String url) {
        if (url == null || !url.startsWith(CgCfg.R2 + "/")) return "";
        String rest = url.substring(CgCfg.R2.length() + 1);
        int slash = rest.indexOf('/');
        if (slash <= 0) return "";
        try {
            return java.net.URLDecoder.decode(rest.substring(0, slash), "UTF-8");
        } catch (Exception e) {
            return rest.substring(0, slash);
        }
    }

    String slugOf(JSONObject it) {
        String stored = s(it, "slug");
        if (stored.length() > 0) return stored;
        if (kind(it) == K_SERIES) {
            ArrayList<JSONObject> eps = episodesOf(it);
            for (int i = 0; i < eps.size(); i++) {
                String sl = inferSlugFromUrl(s(eps.get(i), "video"));
                if (sl.length() > 0) return sl;
            }
        }
        return "";
    }

    private static final String[] AR = {
            "ا", "a", "أ", "a", "إ", "a", "آ", "a", "ء", "", "ؤ", "w", "ئ", "y", "ب", "b", "ت", "t", "ث", "th",
            "ج", "j", "ح", "h", "خ", "kh", "د", "d", "ذ", "dh", "ر", "r", "ز", "z", "س", "s", "ش", "sh", "ص", "s",
            "ض", "d", "ط", "t", "ظ", "z", "ع", "a", "غ", "gh", "ف", "f", "ق", "q", "ك", "k", "ل", "l", "م", "m",
            "ن", "n", "ه", "h", "ة", "a", "و", "w", "ي", "y", "ى", "a", "پ", "p", "گ", "g", "چ", "ch"
    };

    /** Name -> url-safe slug. Arabic is transliterated; the result is lowercase [a-z0-9-]. */
    static String slugify(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        String in = name.trim().toLowerCase(Locale.US);
        for (int i = 0; i < in.length(); i++) {
            String ch = String.valueOf(in.charAt(i));
            String rep = null;
            for (int k = 0; k < AR.length; k += 2) {
                if (AR[k].equals(ch)) {
                    rep = AR[k + 1];
                    break;
                }
            }
            if (rep != null) {
                sb.append(rep);
                continue;
            }
            char c = in.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
            else if (c >= 'ً' && c <= 'ٟ') { /* diacritics dropped */ }
            else sb.append('-');
        }
        String out = sb.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (out.length() > 70) out = out.substring(0, 70).replaceAll("-$", "");
        return out;
    }

    // --------------------------------------------------------- checks/notes

    JSONObject checkOf(String id, String kind) {
        JSONObject checks = meta.optJSONObject("checks");
        if (checks == null) return null;
        JSONObject c = checks.optJSONObject(id);
        if (c == null) return null;
        return c.optJSONObject(kind);
    }

    String noteOf(JSONObject it) {
        JSONObject notes = meta.optJSONObject("notes");
        if (notes == null) return "";
        JSONObject items = notes.optJSONObject("items");
        if (items == null) return "";
        JSONObject n = items.optJSONObject(s(it, "id"));
        return n == null ? "" : n.optString("text", "");
    }

    String seasonNote(String seriesKey, int season) {
        JSONObject notes = meta.optJSONObject("notes");
        if (notes == null) return "";
        JSONObject m = notes.optJSONObject("seasons");
        if (m == null) return "";
        JSONObject n = m.optJSONObject(seriesKey + ":" + season);
        return n == null ? "" : n.optString("text", "");
    }

    void setNoteLocal(String scope, String key, String text) {
        try {
            JSONObject notes = meta.optJSONObject("notes");
            if (notes == null) {
                notes = new JSONObject();
                meta.put("notes", notes);
            }
            String bucket = "item".equals(scope) ? "items" : "season".equals(scope) ? "seasons"
                    : "channel".equals(scope) ? "channels" : "sections";
            JSONObject m = notes.optJSONObject(bucket);
            if (m == null) {
                m = new JSONObject();
                notes.put(bucket, m);
            }
            if (text == null || text.length() == 0) {
                m.remove(key);
            } else {
                JSONObject n = new JSONObject();
                n.put("text", text);
                m.put(key, n);
            }
            hayCache.clear();
        } catch (Exception ignored) { }
    }

    void setCheckLocal(String id, String kind, JSONObject result) {
        try {
            JSONObject checks = meta.optJSONObject("checks");
            if (checks == null) {
                checks = new JSONObject();
                meta.put("checks", checks);
            }
            JSONObject c = checks.optJSONObject(id);
            if (c == null) {
                c = new JSONObject();
                checks.put(id, c);
            }
            JSONObject slim = new JSONObject();
            slim.put("status", result.optString("status"));
            slim.put("http", result.optInt("http"));
            slim.put("ms", result.optInt("ms"));
            slim.put("size", result.optLong("size"));
            slim.put("at", result.optString("at"));
            c.put(kind, slim);
            issueCache.clear();
        } catch (Exception ignored) { }
    }

    static String checkLabel(String status) {
        if ("ok".equals(status)) return "يعمل";
        if ("missing".equals(status)) return "الرابط مفقود";
        if ("bad_url".equals(status)) return "رابط غير صالح";
        if ("forbidden".equals(status)) return "403 ممنوع";
        if ("unauthorized".equals(status)) return "401 غير مصرّح";
        if ("not_found".equals(status)) return "404 غير موجود";
        if ("rate_limited".equals(status)) return "429 طلبات كثيرة";
        if ("server_error".equals(status)) return "خطأ في الخادم";
        if ("http_error".equals(status)) return "خطأ HTTP";
        if ("timeout".equals(status)) return "انتهت المهلة (Timeout)";
        if ("unreachable".equals(status)) return "تعذر الاتصال";
        if ("not_video".equals(status)) return "الرابط ليس فيديو";
        if ("empty".equals(status)) return "الملف فارغ";
        if ("invalid_subtitle".equals(status)) return "ملف الترجمة غير صالح";
        if ("bad_encoding".equals(status)) return "ترميز الترجمة غير UTF-8";
        if ("not_image".equals(status)) return "الرابط ليس صورة";
        return status;
    }

    int subState(JSONObject it) {
        String url = s(it, "subtitle");
        if (url.length() == 0) return SUB_NONE;
        JSONObject c = checkOf(s(it, "id"), "subtitle");
        if (c == null) return SUB_OK;
        String st = c.optString("status", "");
        if (st.length() == 0 || "ok".equals(st)) return SUB_OK;
        if ("not_found".equals(st) || "missing".equals(st)) return SUB_MISSING;
        if ("invalid_subtitle".equals(st) || "bad_encoding".equals(st)) return SUB_INVALID;
        return SUB_BROKEN;
    }

    static String subLabel(int state) {
        if (state == SUB_OK) return "مترجمة";
        if (state == SUB_NONE) return "بدون ترجمة";
        if (state == SUB_MISSING) return "ملف الترجمة غير موجود";
        if (state == SUB_BROKEN) return "رابط الترجمة لا يعمل";
        return "ملف الترجمة غير صالح";
    }

    static int subColor(int state) {
        if (state == SUB_OK) return CgCfg.GOOD;
        if (state == SUB_NONE) return CgCfg.WARN;
        return CgCfg.BAD;
    }

    boolean subChecked(JSONObject it) {
        return checkOf(s(it, "id"), "subtitle") != null;
    }

    // ------------------------------------------------------------- issues

    boolean videoBad(JSONObject it) {
        JSONObject c = checkOf(s(it, "id"), "video");
        if (c == null) return false;
        String st = c.optString("status", "");
        return st.length() > 0 && !"ok".equals(st) && !"missing".equals(st);
    }

    ArrayList<Issue> issues(JSONObject it) {
        String id = s(it, "id");
        String cacheKey = id.length() > 0 ? id : "#" + System.identityHashCode(it);
        ArrayList<Issue> cached = issueCache.get(cacheKey);
        if (cached != null) return cached;
        ArrayList<Issue> out = new ArrayList<Issue>();
        int k = kind(it);

        if (id.length() == 0) out.add(new Issue(2, "بدون ID — لا يمكن تعديله"));
        else if (dupIds.contains(id)) out.add(new Issue(2, "ID مكرر في movies.json"));

        if (k == K_SERIES) {
            if (s(it, "poster").length() == 0 && s(it, "series_poster").length() == 0) out.add(new Issue(1, "بوستر المسلسل مفقود"));
            if (s(it, "title").length() == 0) out.add(new Issue(2, "اسم المسلسل مفقود"));
            ArrayList<JSONObject> eps = episodesOf(it);
            if (eps.isEmpty()) {
                out.add(new Issue(1, "لا توجد حلقات مرتبطة بهذا المسلسل"));
            } else {
                int bad = 0;
                int nosub = 0;
                for (int i = 0; i < eps.size(); i++) {
                    JSONObject e = eps.get(i);
                    int lv = 0;
                    ArrayList<Issue> li = issues(e);
                    for (int j = 0; j < li.size(); j++) lv = Math.max(lv, li.get(j).level);
                    if (lv >= 2) bad++;
                    if (subState(e) != SUB_OK) nosub++;
                }
                if (bad > 0) out.add(new Issue(1, bad + " حلقة بها أخطاء"));
                if (nosub > 0) out.add(new Issue(1, nosub + " حلقة بدون ترجمة صالحة"));
            }
            if (s(it, "section_id").length() == 0) out.add(new Issue(1, "بدون قسم"));
        } else {
            if (s(it, "title").length() == 0 && k == K_MOVIE) out.add(new Issue(2, "العنوان مفقود"));
            String video = s(it, "video");
            if (video.length() == 0) video = s(it, "link");
            if (video.length() == 0) out.add(new Issue(2, "رابط الفيديو مفقود"));
            else if (!video.startsWith("http")) out.add(new Issue(2, "رابط الفيديو غير صالح"));
            if (videoBad(it)) {
                JSONObject c = checkOf(id, "video");
                String st = c == null ? "" : c.optString("status", "");
                out.add(new Issue(2, "رابط الفيديو لا يعمل: " + checkLabel(st)));
                if ("forbidden".equals(st) || "not_found".equals(st) || "unreachable".equals(st)) {
                    out.add(new Issue(2, "مشكلة في R2/التخزين: " + checkLabel(st)));
                }
            }
            if (s(it, "poster").length() == 0) out.add(new Issue(1, "البوستر مفقود"));
            int sub = subState(it);
            if (sub == SUB_NONE) out.add(new Issue(1, "ترجمة مفقودة"));
            else if (sub == SUB_MISSING || sub == SUB_BROKEN) out.add(new Issue(2, "رابط الترجمة لا يعمل (" + subLabel(sub) + ")"));
            else if (sub == SUB_INVALID) {
                JSONObject c = checkOf(id, "subtitle");
                String st = c == null ? "" : c.optString("status", "");
                out.add(new Issue(1, "bad_encoding".equals(st) ? "ترميز ملف الترجمة ليس UTF-8" : "ملف الترجمة غير صالح"));
            }
            if (k == K_EPISODE) {
                String sid = s(it, "series_id");
                if (sid.length() == 0) out.add(new Issue(2, "حلقة غير مرتبطة بمسلسل"));
                else if (!headerByKey.containsKey(sid)) out.add(new Issue(2, "series_id غير صحيح: لا يوجد مسلسل بهذا المعرّف"));
                if (n(it, "season") <= 0 || n(it, "episode") <= 0) out.add(new Issue(2, "رقم الموسم أو الحلقة مفقود"));
                else {
                    Integer c = epDup.get(sid + "|" + n(it, "season") + "|" + n(it, "episode"));
                    if (c != null && c > 1) out.add(new Issue(2, "حلقة مكررة (نفس الموسم والرقم)"));
                }
            } else {
                ArrayList<String> missing = new ArrayList<String>();
                if (s(it, "year").length() == 0) missing.add("السنة");
                if (s(it, "description").length() == 0) missing.add("الوصف");
                if (s(it, "category").length() == 0) missing.add("التصنيف");
                if (!missing.isEmpty()) out.add(new Issue(1, "بيانات ناقصة: " + join(missing, "، ")));
                if (s(it, "section_id").length() == 0) out.add(new Issue(1, "بدون قسم"));
            }
        }
        issueCache.put(cacheKey, out);
        return out;
    }

    /** 0 green, 1 yellow, 2 red, 3 gray (hidden / unpublished). */
    int status(JSONObject it) {
        if (b(it, "hidden")) return 3;
        int lv = 0;
        ArrayList<Issue> l = issues(it);
        for (int i = 0; i < l.size(); i++) lv = Math.max(lv, l.get(i).level);
        return lv;
    }

    static String statusLabel(int st) {
        if (st == 0) return "سليم";
        if (st == 1) return "تحذير";
        if (st == 2) return "خطأ";
        return "مخفي";
    }

    /** First reason as text so color is never the only signal. */
    String statusReason(JSONObject it) {
        int st = status(it);
        if (st == 3) return "مخفي / غير منشور";
        ArrayList<Issue> l = issues(it);
        for (int i = 0; i < l.size(); i++) if (l.get(i).level == st) return l.get(i).text;
        return st == 0 ? "المحتوى سليم" : "";
    }

    static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    // -------------------------------------------------------------- search

    static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        String in = s.toLowerCase(Locale.US);
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c >= 'ً' && c <= 'ٟ') continue;
            if (c == 'ـ') continue;
            if (c == 'أ' || c == 'إ' || c == 'آ') c = 'ا';
            else if (c == 'ى') c = 'ي';
            else if (c == 'ة') c = 'ه';
            sb.append(c);
        }
        return sb.toString();
    }

    private String hay(JSONObject it) {
        String id = s(it, "id");
        String key = id.length() > 0 ? id : "#" + System.identityHashCode(it);
        String h = hayCache.get(key);
        if (h != null) return h;
        StringBuilder sb = new StringBuilder();
        String[] fields = {"title", "id", "imdb", "tmdb", "series_id", "season", "episode", "video", "link",
                "subtitle", "section_name", "section_id", "category", "year", "slug"};
        for (int i = 0; i < fields.length; i++) sb.append(s(it, fields[i])).append(' ');
        int k = kind(it);
        sb.append(slugOf(it)).append(' ');
        if (k == K_EPISODE) {
            int se = n(it, "season");
            int ep = n(it, "episode");
            sb.append(String.format(Locale.US, "s%02de%02d s%de%d %dx%d ", se, ep, se, ep, se, ep));
            JSONObject h2 = headerOf(it);
            if (h2 != null) sb.append(s(h2, "title")).append(' ').append(slugOf(h2)).append(' ');
        }
        if (k == K_SERIES) {
            String sid = s(it, "series_id");
            if (sid.startsWith("series_")) sb.append(sid.substring(7)).append(' ');
        }
        sb.append(noteOf(it));
        h = normalize(sb.toString());
        hayCache.put(key, h);
        return h;
    }

    ArrayList<JSONObject> query(String q, Filter f) {
        ArrayList<JSONObject> out = new ArrayList<JSONObject>();
        String nq = normalize(q == null ? "" : q.trim());
        String[] tokens = nq.length() == 0 ? new String[0] : nq.split("\\s+");
        for (int i = 0; i < items.size(); i++) {
            JSONObject it = items.get(i);
            int k = kind(it);
            if (f != null) {
                if (f.kind >= 0 && k != f.kind) continue;
                if (f.sectionId.length() > 0 && !f.sectionId.equals(s(it, "section_id"))) continue;
                if (f.featuredOnly && !b(it, "featured")) continue;
                if (f.hiddenOnly && !b(it, "hidden")) continue;
                if (f.publishedOnly && b(it, "hidden")) continue;
                if (f.errorsOnly && status(it) != 2) continue;
                if (f.noVideo) {
                    if (k == K_SERIES) continue;
                    if (s(it, "video").length() > 0 && !videoBad(it)) continue;
                }
                if (f.noSubtitle) {
                    if (k == K_SERIES) {
                        boolean any = false;
                        ArrayList<JSONObject> eps = episodesOf(it);
                        for (int j = 0; j < eps.size(); j++) {
                            if (subState(eps.get(j)) != SUB_OK) {
                                any = true;
                                break;
                            }
                        }
                        if (!any) continue;
                    } else if (subState(it) == SUB_OK) {
                        continue;
                    }
                }
            }
            if (tokens.length > 0) {
                String h = hay(it);
                boolean all = true;
                for (int t = 0; t < tokens.length; t++) {
                    if (!h.contains(tokens[t])) {
                        all = false;
                        break;
                    }
                }
                if (!all) continue;
            }
            out.add(it);
        }
        return out;
    }

    // ------------------------------------------------------------ sections

    String sectionName(String id) {
        for (int i = 0; i < sections.size(); i++) {
            if (s(sections.get(i), "id").equals(id)) return s(sections.get(i), "name");
        }
        for (int i = 0; i < items.size(); i++) {
            if (s(items.get(i), "section_id").equals(id)) {
                String nme = s(items.get(i), "section_name");
                if (nme.length() > 0) return nme;
            }
        }
        return id;
    }

    int[] counts() {
        // movies, series, episodes, errors, noSubtitle(episodes+movies), featured, hidden
        int[] c = new int[7];
        for (int i = 0; i < items.size(); i++) {
            JSONObject it = items.get(i);
            int k = kind(it);
            if (k == K_MOVIE) c[0]++;
            else if (k == K_SERIES) c[1]++;
            else c[2]++;
            if (status(it) == 2) c[3]++;
            if (k != K_SERIES && subState(it) != SUB_OK) c[4]++;
            if (b(it, "featured")) c[5]++;
            if (b(it, "hidden")) c[6]++;
        }
        return c;
    }
}
