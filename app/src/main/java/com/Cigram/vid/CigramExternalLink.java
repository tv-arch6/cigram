package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One external link on the ACCOUNT page, controlled from the admin app ("إدارة الروابط الخارجية"): a download link for the app,
 * or - when there is none - the link of the Telegram channel. It is the last element of the page, centred.
 *
 * Visibility is decided by the server answer only (cigram-plus-api GET /app/external-link): it is {enabled:true, url, ...} only when the
 * admin switched it on AND a valid https link exists. Anything else - no answer yet, no link, switched off, cleared - shows nothing, and a
 * switched-off answer also wipes the cached copy, so the element disappears on the next visit of the page.
 *
 * Look: a 20 dp rounded horizontal card; behind the text a mosaic of posters tilted like "/" (top right to bottom left) with
 * semi-transparent borders between the tiles; the text and icon sit on a dark scrim. Everything uses the Sima font.
 */
public final class CigramExternalLink {

    static final String TAG = "cigram_external_link_card";
    private static final String PREFS = "cigram_external_link";
    private static final long REFRESH_GAP_MS = 60L * 1000L;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static Handler main;
    private static volatile long lastRefreshAt;

    private CigramExternalLink() { }

    private static synchronized Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    // ------------------------------------------------------------------ pure logic (unit-tested)

    static final class Config {
        boolean enabled;
        String url = "";
        String kind = "";      // "app" | "channel"
        String icon = "";
    }

    /** An https link with a real host. Nothing else (no http, javascript:, intent:, file:) is ever opened. */
    static boolean validUrl(String u) {
        if (u == null) return false;
        String s = u.trim();
        if (s.length() < 12 || s.length() > 500 || !s.toLowerCase(Locale.ROOT).startsWith("https://")) return false;
        try {
            Uri uri = Uri.parse(s);
            String host = uri.getHost();
            return host != null && host.contains(".");
        } catch (Throwable t) {
            return false;
        }
    }

    /** The server answer -> what to show. Disabled whenever anything is missing or invalid. */
    static Config parse(String raw) {
        Config c = new Config();
        try {
            if (raw == null || raw.trim().length() == 0) return c;
            JSONObject o = new JSONObject(raw);
            if (!o.optBoolean("ok", false) || !o.optBoolean("enabled", false)) return c;
            String url = o.optString("url", "").trim();
            if (!validUrl(url)) return c;
            c.url = url;
            String kind = o.optString("kind", "");
            c.kind = "app".equals(kind) || "channel".equals(kind) ? kind : (url.toLowerCase(Locale.ROOT).contains("t.me/") ? "channel" : "app");
            String icon = o.optString("icon", "").trim();
            c.icon = validUrl(icon) ? icon : "";
            c.enabled = true;
        } catch (Throwable ignored) { }
        return c;
    }

    static String titleFor(String kind) {
        return "channel".equals(kind) ? "انضم لقناتنا على تليكرام" : "حمّل تطبيقنا الآن";
    }

    static String subtitleFor(String kind) {
        return "channel".equals(kind) ? "اضغط لفتح القناة" : "اضغط لفتح رابط التحميل";
    }

    // ------------------------------------------------------------------ data

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Config current(Context c) {
        try {
            return parse(prefs(c).getString("json", ""));
        } catch (Throwable t) {
            return new Config();
        }
    }

    /** Asks the server (throttled to once a minute). {@code done} runs on the main thread after a real answer. */
    static void refresh(final Context c, final Runnable done) {
        final Context app = c.getApplicationContext();
        long now = System.currentTimeMillis();
        if (now - lastRefreshAt < REFRESH_GAP_MS) return;
        lastRefreshAt = now;
        POOL.execute(new Runnable() {
            @Override public void run() {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(CigramPlus.PLUS_BASE + "/app/external-link").openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("Accept", "application/json");
                    if (conn.getResponseCode() != 200) return;
                    String body = readText(conn.getInputStream(), 16384);
                    JSONObject o = new JSONObject(body);
                    if (!o.optBoolean("ok", false)) return;
                    // a "disabled" answer is stored too: that is what makes the card disappear
                    prefs(app).edit().putString("json", body).apply();
                    if (done != null) main().post(done);
                } catch (Throwable ignored) {
                } finally {
                    if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) { } }
                }
            }
        });
    }

    private static String readText(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < max) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    // ------------------------------------------------------------------ placing it on the account page

    /**
     * Removes the card and, when the saved answer says so, adds it again as the LAST element of {@code host}, centred.
     * Called by the account page every time it arranges its rows, so the card always ends up at the bottom.
     */
    public static void install(final Activity a, final LinearLayout host) {
        try {
            View old = host.findViewWithTag(TAG);
            if (old != null) host.removeView(old);
            Config c = current(a);
            if (c.enabled) {
                int screen = a.getResources().getDisplayMetrics().widthPixels;
                int width = Math.min(screen - CigramUI.dp(a, 40), CigramUI.dp(a, 380));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, CigramUI.dp(a, 112));
                lp.gravity = Gravity.CENTER_HORIZONTAL;
                lp.topMargin = CigramUI.dp(a, 18);
                lp.bottomMargin = CigramUI.dp(a, 28);
                host.addView(build(a, c), lp);
            }
            refresh(a, new Runnable() {
                @Override public void run() { if (!a.isFinishing()) install(a, host); }
            });
        } catch (Throwable t) {
            android.util.Log.w("CigramExternalLink", "install failed", t);
        }
    }

    // ------------------------------------------------------------------ the card

    static View build(final Activity a, final Config c) {
        final FrameLayout card = new FrameLayout(a);
        card.setTag(TAG);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        final float radius = CigramUI.dp(a, 20);
        GradientDrawable base = new GradientDrawable();
        base.setColor(0xFF0B1F2A);
        base.setCornerRadius(radius);
        card.setBackground(base);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
        card.setElevation(CigramUI.dp(a, 4));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(titleFor(c.kind));

        final Mosaic mosaic = new Mosaic(a);
        card.addView(mosaic, new FrameLayout.LayoutParams(-1, -1));

        View scrim = new View(a);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x66000000, 0xCC06131B, 0xEE06131B}));
        card.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setPadding(CigramUI.dp(a, 16), 0, CigramUI.dp(a, 16), 0);

        final ImageView icon = new ImageView(a);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(0x33FFFFFF);
        ib.setCornerRadius(CigramUI.dp(a, 14));
        icon.setBackground(ib);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), CigramUI.dp(a, 14)); }
        });
        try { icon.setImageDrawable(a.getPackageManager().getApplicationIcon(a.getApplicationInfo())); } catch (Throwable ignored) { }
        row.addView(icon, new LinearLayout.LayoutParams(CigramUI.dp(a, 60), CigramUI.dp(a, 60)));

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = CigramUI.label(a, titleFor(c.kind), 18f, 0xFFFFFFFF, true);     // CigramUI.label = Sima
        title.setSingleLine(true);
        title.setShadowLayer(CigramUI.dp(a, 4), 0f, CigramUI.dp(a, 1), 0xAA000000);
        texts.addView(title);
        TextView sub = CigramUI.label(a, subtitleFor(c.kind), 12.5f, 0xCCFFFFFF, false);
        sub.setSingleLine(true);
        texts.addView(sub);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.setMarginStart(CigramUI.dp(a, 14));
        row.addView(texts, tl);

        TextView go = CigramUI.label(a, "‹", 30f, 0xFFFFFFFF, true);
        go.setGravity(Gravity.CENTER);
        row.addView(go, new LinearLayout.LayoutParams(CigramUI.dp(a, 28), -2));
        card.addView(row, new FrameLayout.LayoutParams(-1, -1));

        final String url = c.url;
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    if (!validUrl(url)) return;                    // re-checked at the tap: only https is ever opened
                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Throwable t) {
                    android.widget.Toast.makeText(a, "تعذّر فتح الرابط", android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });

        // pictures: posters of the app for the mosaic, the admin's icon for the icon (both in the background)
        final Context app = a.getApplicationContext();
        final String catalog = catalogJson(app);
        final String iconUrl = c.icon;
        POOL.execute(new Runnable() {
            @Override public void run() {
                final List<Bitmap> pics = new ArrayList<Bitmap>();
                try {
                    for (CigramWidgetData.Slide s : CigramWidgetData.fromCatalog(catalog, 12)) {
                        Bitmap b = CigramWidgetImages.card(app, s.imageUrl);
                        if (b != null) pics.add(b);
                    }
                } catch (Throwable ignored) { }
                final Bitmap ic = iconUrl.length() > 0 ? iconBitmap(app, iconUrl) : null;
                card.post(new Runnable() {
                    @Override public void run() {
                        if (a.isFinishing()) return;
                        if (!pics.isEmpty()) mosaic.setPictures(pics);
                        if (ic != null) icon.setImageBitmap(ic);
                    }
                });
            }
        });
        return card;
    }

    private static String catalogJson(Context c) {
        try {
            return c.getSharedPreferences("cigram_catalog_cache", Context.MODE_PRIVATE).getString("json", "");
        } catch (Throwable t) {
            return "";
        }
    }

    /** The admin's icon (cached on disk, decoded small). */
    private static Bitmap iconBitmap(Context c, String url) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : h) sb.append(String.format(Locale.US, "%02x", x & 255));
            File dir = new File(c.getCacheDir(), "cigram_ext_icon");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, sb.substring(0, 24) + ".img");
            if (!f.isFile() || f.length() == 0) {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("Accept", "image/*");
                if (conn.getResponseCode() != 200) return null;
                InputStream in = conn.getInputStream();
                FileOutputStream out = new FileOutputStream(f);
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 4L * 1024 * 1024) { out.close(); f.delete(); return null; }
                    out.write(buf, 0, n);
                }
                out.close();
                in.close();
            }
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), b);
            int sample = 1;
            while (b.outWidth / (sample * 2) >= 256) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ the tilted poster mosaic

    /** Tiles in a grid, the whole grid turned 18 degrees counter-clockwise so the rows rise to the right ("/"). */
    static final class Mosaic extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int[] palette = {0xFF2B6E8F, 0xFFC9892B, 0xFF8B2BC9, 0xFF2B8F5B, 0xFFB5332B, 0xFF3D4DB5};
        private List<Bitmap> pics = new ArrayList<Bitmap>();

        Mosaic(Context c) {
            super(c);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(Math.max(1f, CigramUI.dp(c, 2)));
            border.setColor(0x44FFFFFF);                  // the semi-transparent borders between tiles
        }

        void setPictures(List<Bitmap> list) {
            pics = list;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0) return;
            float d = getResources().getDisplayMetrics().density;
            float tw = 78f * d, th = 52f * d, gap = 4f * d;
            float reach = (float) Math.hypot(w, h);
            c.save();
            c.rotate(-18f, w / 2f, h / 2f);
            int i = 0;
            int row = 0;
            for (float y = (h - reach) / 2f; y < (h + reach) / 2f; y += th + gap, row++) {
                float shift = (row % 2 == 0) ? 0f : (tw + gap) / 2f;           // brick offset: the diagonal reads better
                for (float x = (w - reach) / 2f - shift; x < (w + reach) / 2f; x += tw + gap, i++) {
                    rect.set(x, y, x + tw, y + th);
                    Bitmap b = pics.isEmpty() ? null : pics.get(i % pics.size());
                    if (b != null) {
                        c.save();
                        android.graphics.Path clip = new android.graphics.Path();
                        clip.addRoundRect(rect, 7f * d, 7f * d, android.graphics.Path.Direction.CW);
                        c.clipPath(clip);
                        float s = Math.max(tw / b.getWidth(), th / b.getHeight());
                        float bw = b.getWidth() * s, bh = b.getHeight() * s;
                        c.drawBitmap(b, null, new RectF(rect.centerX() - bw / 2f, rect.centerY() - bh / 2f, rect.centerX() + bw / 2f, rect.centerY() + bh / 2f), p);
                        c.restore();
                    } else {
                        tint.setColor((palette[i % palette.length] & 0x00FFFFFF) | 0x99000000);
                        c.drawRoundRect(rect, 7f * d, 7f * d, tint);
                    }
                    c.drawRoundRect(rect, 7f * d, 7f * d, border);
                }
            }
            c.restore();
        }
    }
}
