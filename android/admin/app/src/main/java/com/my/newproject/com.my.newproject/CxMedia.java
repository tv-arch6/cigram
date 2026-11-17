package com.my.newproject;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

/** Link tests, player preview and local image helpers shared by the add screens. */
final class CxMedia {

    private CxMedia() {}

    interface Result {
        void done(boolean ok, JSONObject result);
    }

    /**
     * Light on-demand test of a video or subtitle URL through the Worker (nothing is downloaded in full).
     * A failing video is an error; a failing subtitle is only a warning.
     */
    static void test(final CxBase host, final String kind, final String url, final CxUi.Field field, final Result cb) {
        if (url == null || url.trim().length() == 0) {
            if ("video".equals(kind)) field.error("أدخل رابط الفيديو أولاً.");
            else field.warn("لا توجد ترجمة لاختبارها.");
            if (cb != null) cb.done(false, null);
            return;
        }
        if (!CxJ.isUrl(url)) {
            field.error("الرابط يجب أن يبدأ بـ http:// أو https://");
            if (cb != null) cb.done(false, null);
            return;
        }
        if ("video".equals(kind) && CgVidbolt.is(url)) {
            field.ok("رابط VidBolt — جاهز للمشغل (لا يحتاج فحص ملف).");
            if (cb != null) cb.done(true, null);
            return;
        }
        field.message(CxUi.INFO, "جارٍ الاختبار...");
        host.exec(new CxBase.Job<JSONObject>() {
            @Override
            public JSONObject run() throws Exception {
                JSONObject b = new JSONObject();
                JSONArray arr = new JSONArray();
                JSONObject o = new JSONObject();
                o.put("id", "t");
                o.put("kind", kind);
                o.put("url", url.trim());
                arr.put(o);
                b.put("items", arr);
                JSONObject d = CxApi.post("/admin/check-urls", b);
                JSONArray res = d.optJSONArray("results");
                if (res == null || res.length() == 0) throw new CxApi.Failure("لم يصل رد الاختبار.", "EMPTY", null, 0);
                return res.getJSONObject(0);
            }
        }, new CxBase.Cb<JSONObject>() {
            @Override
            public void ok(JSONObject r) {
                String st = r.optString("status", "");
                boolean good = "ok".equals(st);
                StringBuilder sb = new StringBuilder();
                sb.append(good ? "يعمل" : CgData.checkLabel(st));
                if (r.optLong("size") > 0) sb.append(" • ").append(CgUi.sizeText(r.optLong("size")));
                if (r.optString("type").length() > 0) sb.append(" • ").append(r.optString("type"));
                if (r.optInt("ms") > 0) sb.append(" • ").append(r.optInt("ms")).append("ms");
                if (good) field.ok(sb.toString());
                else if ("video".equals(kind)) field.error(sb.toString());
                else field.warn(sb.toString());
                if (cb != null) cb.done(good, r);
            }

            @Override
            public void fail(CxApi.Failure f) {
                field.warn("تعذر الاختبار الآن: " + f.getMessage());
                if (cb != null) cb.done(false, null);
            }
        });
    }

    static void openPlayer(Activity a, String title, String video, String subtitle) {
        Intent in = new Intent(a, CgPlayerActivity.class);
        in.putExtra("video_url", video == null ? "" : video);
        in.putExtra("subtitle_url", subtitle == null ? "" : subtitle);
        in.putExtra("title", title == null ? "" : title);
        a.startActivity(in);
    }

    // ------------------------------------------------------------------- local images

    static void pickImage(Activity a, int requestCode) {
        Intent in = new Intent(Intent.ACTION_GET_CONTENT);
        in.setType("image/*");
        in.addCategory(Intent.CATEGORY_OPENABLE);
        a.startActivityForResult(Intent.createChooser(in, "اختر صورة"), requestCode);
    }

    /** Decodes a picked image downscaled so its longest side is <= maxSide. Returns null on failure. */
    static Bitmap decode(Activity a, Uri uri, int maxSide) {
        InputStream in = null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            in = a.getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, o);
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            int sample = 1;
            int longest = Math.max(o.outWidth, o.outHeight);
            while (longest / (sample * 2) >= maxSide) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            in = a.getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(in, null, o2);
            if (bmp == null) return null;
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            int big = Math.max(w, h);
            if (big > maxSide) {
                float k = (float) maxSide / (float) big;
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            return bmp;
        } catch (Throwable e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
        }
    }

    static byte[] jpeg(Bitmap b, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }

    /** JPEG with transparent areas flattened onto a solid color (logos are often transparent PNGs). */
    static byte[] jpegFlat(Bitmap b, int bg, int quality) {
        Bitmap out = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        c.drawColor(bg);
        c.drawBitmap(b, 0, 0, null);
        byte[] r = jpeg(out, quality);
        out.recycle();
        return r;
    }

    static String base64(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    /** Downloads an image URL into a bitmap (background thread only). */
    static Bitmap download(String url, int maxSide) {
        java.net.HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) return null;
            in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > 8 * 1024 * 1024) return null;
                out.write(buf, 0, n);
            }
            byte[] data = out.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            int longest = Math.max(o.outWidth, o.outHeight);
            while (longest / (sample * 2) >= maxSide) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable e) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            if (c != null) c.disconnect();
        }
    }

    static String sizeInfo(Bitmap b) {
        if (b == null) return "";
        return String.format(Locale.US, "%dx%d", b.getWidth(), b.getHeight());
    }

    static void enable(View v, boolean e) {
        CxUi.setEnabled(v, e);
    }
}
