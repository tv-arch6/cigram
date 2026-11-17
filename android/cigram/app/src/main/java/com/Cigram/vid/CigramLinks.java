package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

/**
 * v10.62: روابط المشاركة.
 *
 *  - رابط فيلم/مسلسل:  https://cigram-user-api.../t/movie/<id>?n=<title>&y=<year>&p=<poster>
 *  - رابط غرفة:        https://cigram-user-api.../j/<CODE>
 *
 * روابط https قابلة للضغط في واتساب وتيليجرام وإنستغرام؛ الصفحة تفتح التطبيق مباشرة
 * (cigram://title أو cigram://together) أو تعرض زر التحميل إذا التطبيق غير مثبت.
 * قبل نشر الخادم v2.4 نرجع تلقائياً للرابط القديم cigram:// حتى لا نشارك رابطاً معطلاً.
 */
public final class CigramLinks {

    private CigramLinks() { }

    public static void refresh(Context context) {
        CigramApi.refreshFeatures(context);
    }

    private static boolean webLinks(Context c) {
        return CigramApi.has(c, "links");
    }

    // ------------------------------------------------------------------ rooms

    public static String roomLink(Context c, String code) {
        String clean = code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
        if (webLinks(c)) return CigramApi.BASE + "/j/" + CigramApi.enc(clean);
        return "cigram://together?code=" + clean;
    }

    public static String roomShareText(Context c, String code) {
        return "تعال نشاهد سوه على سيكرام!\nرمز الغرفة: " + code + "\nافتح الرابط للانضمام: " + roomLink(c, code);
    }

    // ------------------------------------------------------------------ titles

    public static boolean isSeries(JSONObject item) {
        try {
            return CigramContent.isSeries(item);
        } catch (Throwable ignored) {
            return "series".equals(CigramContent.text(item, "type"));
        }
    }

    public static String idOf(JSONObject item) {
        return isSeries(item)
                ? CigramContent.text(item, "series_id", "id", "content_id")
                : CigramContent.text(item, "id", "movie_id", "item_id", "content_id");
    }

    public static String titleOf(JSONObject item) {
        return isSeries(item)
                ? CigramContent.text(item, "series_title", "title", "name")
                : CigramContent.text(item, "title", "name");
    }

    public static String titleLink(Context c, JSONObject item) {
        String type = isSeries(item) ? "series" : "movie";
        String id = idOf(item);
        String title = titleOf(item);
        if (webLinks(c) && id.length() > 0) {
            StringBuilder b = new StringBuilder(CigramApi.BASE).append("/t/").append(type).append('/')
                    .append(CigramApi.enc(id)).append("?n=").append(CigramApi.enc(title));
            String year = CigramContent.text(item, "year", "release_year");
            if (year.length() > 0) b.append("&y=").append(CigramApi.enc(year));
            String poster = CigramContent.text(item, "poster", "series_poster", "image");
            if (poster.startsWith("https://") && poster.length() < 400) b.append("&p=").append(CigramApi.enc(poster));
            return b.toString();
        }
        return "cigram://title?type=" + type + "&id=" + CigramApi.enc(id) + "&n=" + CigramApi.enc(title);
    }

    public static String titleShareText(Context c, JSONObject item) {
        String kind = isSeries(item) ? "مسلسل" : "فيلم";
        String title = titleOf(item);
        String year = CigramContent.text(item, "year", "release_year");
        return "شاهد " + kind + " «" + title + "»" + (year.length() > 0 ? " (" + year + ")" : "")
                + " على سيكرام 🎬\n" + titleLink(c, item);
    }

    // ------------------------------------------------------------------ opening links

    /** cigram://title?type=movie&id=123&n=Title  → صفحة التفاصيل. يعيد true إذا عالج الرابط. */
    public static boolean open(final Activity a, Uri uri) {
        if (a == null || uri == null) return false;
        try {
            String host = uri.getHost() == null ? "" : uri.getHost();
            if ("title".equals(host)) {
                String type = "series".equals(uri.getQueryParameter("type")) ? "series" : "movie";
                String id = clean(uri.getQueryParameter("id"));
                String name = clean(uri.getQueryParameter("n"));
                JSONObject found = null;
                try { found = CigramContent.find(type, id, name); } catch (Throwable ignored) { }
                JSONObject item = found != null ? new JSONObject(found.toString()) : new JSONObject();
                item.put("type", type);
                if (id.length() > 0) {
                    if (item.optString("id", "").length() == 0) item.put("id", id);
                    if ("series".equals(type) && item.optString("series_id", "").length() == 0) item.put("series_id", id);
                }
                if (name.length() > 0 && item.optString("title", "").length() == 0) item.put("title", name);
                if ("series".equals(type) && name.length() > 0 && item.optString("series_title", "").length() == 0) {
                    item.put("series_title", name);
                }
                CigramStage1InitProvider.openTitleDetails(a, item, null);
                return true;
            }
            if ("requests".equals(host)) {
                a.startActivity(new Intent(a, Class.forName(a.getPackageName() + ".CommunicationActivity")));
                return true;
            }
            if ("together".equals(host)) {
                Intent i = new Intent(a, CigramTogetherActivity.class);
                i.setData(uri);
                a.startActivity(i);
                return true;
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramLinks", "open failed", error);
        }
        return false;
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }
}
