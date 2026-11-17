package com.Cigram.vid;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * v10.62: «مشاركة» في صفحة التفاصيل.
 *   - زر «مشاركة» يُضاف لصف الأزرار (إعجاب / قائمتي / ...) بدون لمس الأزرار الموجودة.
 *   - ورقة: مشاركة الرابط · مشاركة بطاقة صورة (بوستر + اسم + سنة + تقييم + رابط) · نسخ الرابط.
 * الاستدعاء من DetailsActivity:  CigramShare.installDetails(DetailsActivity.this);
 */
public final class CigramShare {

    private static final String TAG_BTN = "cigram_share_btn";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private CigramShare() { }

    private static View byId(Activity a, String name) {
        int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
        return id == 0 ? null : a.findViewById(id);
    }

    /** Safe to call any number of times (onCreate, onResume, after a rebind). */
    public static void installDetails(final Activity a) {
        if (a == null || a.isFinishing()) return;
        tryInstall(a);
        MAIN.postDelayed(new Runnable() { @Override public void run() { tryInstall(a); } }, 400L);
        MAIN.postDelayed(new Runnable() { @Override public void run() { tryInstall(a); } }, 1500L);
    }

    private static void tryInstall(final Activity a) {
        try {
            if (a.isFinishing()) return;
            View later = byId(a, "linear_watch_later");
            if (later == null || !(later.getParent() instanceof LinearLayout)) return;
            LinearLayout row = (LinearLayout) later.getParent();
            View old = a.getWindow().getDecorView().findViewWithTag(TAG_BTN);
            if (old != null) {
                if (old.getParent() == row) return;
                if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
            }
            LinearLayout btn = new LinearLayout(a);
            btn.setTag(TAG_BTN);
            btn.setOrientation(LinearLayout.HORIZONTAL);
            btn.setGravity(Gravity.CENTER);
            btn.setClickable(true);
            btn.setFocusable(true);
            btn.setContentDescription("مشاركة");
            btn.setBackground(CigramDesign.ghostPill(a, 23f));
            btn.setPadding(CigramUI.dp(a, 14), 0, CigramUI.dp(a, 14), 0);
            btn.addView(new ShareGlyph(a), new LinearLayout.LayoutParams(CigramUI.dp(a, 18), CigramUI.dp(a, 18)));
            TextView label = CigramUI.label(a, "مشاركة", 13.5f, 0xFFFFFFFF, false);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
            tl.leftMargin = CigramUI.dp(a, 6);
            btn.addView(label, tl);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showSheet(a); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, CigramUI.dp(a, 46));
            lp.leftMargin = CigramUI.dp(a, 4);
            lp.rightMargin = CigramUI.dp(a, 4);
            lp.gravity = Gravity.CENTER_VERTICAL;
            row.addView(btn, 0, lp);
        } catch (Throwable error) {
            android.util.Log.w("CigramShare", "install failed", error);
        }
    }

    private static JSONObject currentItem(Activity a) {
        try {
            JSONObject item = CigramStage1InitProvider.castDetailsItem(a);
            if (item != null) return item;
        } catch (Throwable ignored) { }
        return new JSONObject();
    }

    // ------------------------------------------------------------------ sheet

    public static void showSheet(final Activity a) {
        final JSONObject item = currentItem(a);
        final String title = CigramLinks.titleOf(item);
        if (title.length() == 0) {
            CigramSilent.toast(a, "لحظة، جارٍ تحميل البيانات…", android.widget.Toast.LENGTH_SHORT);
            return;
        }
        CigramLinks.refresh(a);
        final CigramUI.Sheet sheet = CigramUI.sheet(a).title("مشاركة").message("«" + title + "»");
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(option(a, sheet, "مشاركة الرابط", "يفتح التطبيق مباشرة عند صديقك", new Runnable() {
            @Override public void run() { shareText(a, CigramLinks.titleShareText(a, item)); }
        }), optionLp(a, false));
        list.addView(option(a, sheet, "مشاركة بطاقة صورة", "بوستر واسم وتقييم — مناسبة للستوري", new Runnable() {
            @Override public void run() { shareCard(a, item); }
        }), optionLp(a, true));
        list.addView(option(a, sheet, "نسخ الرابط", "", new Runnable() {
            @Override public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Cigram", CigramLinks.titleLink(a, item)));
                    CigramSilent.toast(a, "تم نسخ الرابط", android.widget.Toast.LENGTH_SHORT);
                } catch (Throwable ignored) { }
            }
        }), optionLp(a, true));
        sheet.view(list).secondary("إلغاء", null).show();
        try { CigramAnalytics.track(a, "share_sheet_open"); } catch (Throwable ignored) { }
    }

    private static LinearLayout.LayoutParams optionLp(Activity a, boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        if (gap) lp.topMargin = CigramUI.dp(a, 8);
        return lp;
    }

    private static View option(Activity a, final CigramUI.Sheet sheet, String title, String sub, final Runnable run) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        row.setPadding(CigramUI.dp(a, 16), CigramUI.dp(a, 12), CigramUI.dp(a, 16), CigramUI.dp(a, 12));
        row.setBackground(CigramUI.round(CigramUI.BG_SOFT, CigramUI.STROKE, 16, a));
        row.setClickable(true);
        row.setFocusable(true);
        CigramGlass.press(row);
        row.addView(CigramUI.label(a, title, 15.5f, CigramUI.TEXT, true), new LinearLayout.LayoutParams(-1, -2));
        if (sub.length() > 0) row.addView(CigramUI.label(a, sub, 12.5f, CigramUI.MUTED, false), new LinearLayout.LayoutParams(-1, -2));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { sheet.dialog.dismiss(); } catch (Throwable ignored) { }
                run.run();
            }
        });
        return row;
    }

    private static void shareText(Activity a, String text) {
        try {
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("text/plain");
            s.putExtra(Intent.EXTRA_TEXT, text);
            a.startActivity(Intent.createChooser(s, "مشاركة عبر"));
            try { CigramAnalytics.track(a, "share_link"); } catch (Throwable ignored) { }
        } catch (Throwable error) {
            CigramSilent.toast(a, "تعذرت المشاركة", android.widget.Toast.LENGTH_SHORT);
        }
    }

    // ------------------------------------------------------------------ card image

    private static void shareCard(final Activity a, final JSONObject item) {
        final Toast wait = Toast.makeText(a, "جارٍ تجهيز البطاقة…", Toast.LENGTH_SHORT);
        wait.show();
        final Context app = a.getApplicationContext();
        CigramApi.IO.execute(new Runnable() {
            @Override public void run() {
                File out = null;
                try {
                    CigramShareProvider.cleanOld(app);
                    Bitmap poster = download(CigramContent.text(item, "poster", "series_poster", "image", "backdrop"));
                    Bitmap card = drawCard(app, item, poster);
                    out = new File(CigramShareProvider.dir(app), "card_" + System.currentTimeMillis() + ".png");
                    FileOutputStream fos = new FileOutputStream(out);
                    card.compress(Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                    card.recycle();
                } catch (Throwable error) {
                    android.util.Log.w("CigramShare", "card failed", error);
                    out = null;
                }
                final File file = out;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (a.isFinishing()) return;
                        if (file == null) {
                            shareText(a, CigramLinks.titleShareText(a, item));
                            return;
                        }
                        try {
                            Uri uri = CigramShareProvider.uriFor(file);
                            Intent s = new Intent(Intent.ACTION_SEND);
                            s.setType("image/png");
                            s.putExtra(Intent.EXTRA_STREAM, uri);
                            s.putExtra(Intent.EXTRA_TEXT, CigramLinks.titleShareText(a, item));
                            s.setClipData(ClipData.newRawUri("", uri));
                            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            a.startActivity(Intent.createChooser(s, "مشاركة البطاقة"));
                            try { CigramAnalytics.track(a, "share_card"); } catch (Throwable ignored) { }
                        } catch (Throwable error) {
                            shareText(a, CigramLinks.titleShareText(a, item));
                        }
                    }
                });
            }
        });
    }

    private static Bitmap download(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(7000);
            c.setReadTimeout(9000);
            InputStream in = c.getInputStream();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            in.close();
            return b;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    static Bitmap drawCard(Context c, JSONObject item, Bitmap poster) {
        final int W = 1080, H = 1350;
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        // background
        p.setShader(new LinearGradient(0, 0, 0, H, 0xFF12202B, 0xFF111113, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, W, H, p);
        p.setShader(new RadialGradient(W / 2f, 260f, 620f, 0x664A1214, 0x004A1214, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, W, H, p);
        p.setShader(null);

        // poster (2:3) with rounded corners and a soft shadow
        float pw = 520f, ph = 780f;
        float px = (W - pw) / 2f, py = 96f;
        RectF pr = new RectF(px, py, px + pw, py + ph);
        p.setColor(0x66000000);
        cv.drawRoundRect(new RectF(pr.left + 10, pr.top + 22, pr.right + 10, pr.bottom + 26), 36f, 36f, p);
        cv.save();
        Path clip = new Path();
        clip.addRoundRect(pr, 34f, 34f, Path.Direction.CW);
        cv.clipPath(clip);
        if (poster != null) {
            float scale = Math.max(pw / poster.getWidth(), ph / poster.getHeight());
            float dw = poster.getWidth() * scale, dh = poster.getHeight() * scale;
            RectF dst = new RectF(px + (pw - dw) / 2f, py + (ph - dh) / 2f, px + (pw + dw) / 2f, py + (ph + dh) / 2f);
            cv.drawBitmap(poster, null, dst, p);
        } else {
            p.setColor(0xFF1B1B1F);
            cv.drawRect(pr, p);
        }
        cv.restore();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        p.setColor(0x33FFFFFF);
        cv.drawRoundRect(pr, 34f, 34f, p);
        p.setStyle(Paint.Style.FILL);

        Typeface font;
        try { font = CigramUI.font(c); } catch (Throwable ignored) { font = Typeface.DEFAULT; }

        // title
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTextSize(66f);
        tp.setTypeface(Typeface.create(font, Typeface.BOLD));
        String title = CigramLinks.titleOf(item);
        StaticLayout tl = layout(TextUtils.ellipsize(title, tp, 940f * 2f, TextUtils.TruncateAt.END), tp, 940);
        float y = py + ph + 46f;
        cv.save();
        cv.translate((W - 940) / 2f, y);
        tl.draw(cv);
        cv.restore();
        y += tl.getHeight() + 14f;

        // meta: فيلم · 2010 · ★ 8.8
        StringBuilder meta = new StringBuilder(CigramLinks.isSeries(item) ? "مسلسل" : "فيلم");
        String year = CigramContent.text(item, "year", "release_year");
        if (year.length() > 0) meta.append("  ·  ").append(year);
        String imdb = CigramContent.text(item, "imdb", "rating");
        if (imdb.length() > 0 && !"0".equals(imdb) && !"0.0".equals(imdb)) meta.append("  ·  ★ ").append(imdb);
        TextPaint mp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        mp.setColor(0xFFC7C7CC);
        mp.setTextSize(38f);
        mp.setTypeface(font);
        StaticLayout ml = layout(meta.toString(), mp, 940);
        cv.save();
        cv.translate((W - 940) / 2f, y);
        ml.draw(cv);
        cv.restore();

        // footer pill
        TextPaint fp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        fp.setColor(Color.WHITE);
        fp.setTextSize(40f);
        fp.setTypeface(Typeface.create(font, Typeface.BOLD));
        String cta = "شاهده الآن على سيكرام";
        float cw = fp.measureText(cta) + 110f;
        RectF pill = new RectF((W - cw) / 2f, H - 150f, (W + cw) / 2f, H - 62f);
        p.setColor(0xFFE21B14);
        cv.drawRoundRect(pill, 44f, 44f, p);
        StaticLayout fl = layout(cta, fp, (int) cw);
        cv.save();
        cv.translate(pill.left, pill.top + (pill.height() - fl.getHeight()) / 2f);
        fl.draw(cv);
        cv.restore();
        return bmp;
    }

    @SuppressWarnings("deprecation")
    private static StaticLayout layout(CharSequence text, TextPaint paint, int width) {
        if (Build.VERSION.SDK_INT >= 23) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setLineSpacing(0f, 1.05f)
                    .build();
        }
        return new StaticLayout(text, paint, width, Layout.Alignment.ALIGN_CENTER, 1.05f, 0f, false);
    }

    // ------------------------------------------------------------------ icon

    static final class ShareGlyph extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        ShareGlyph(Context c) { super(c); }

        @Override protected void onDraw(Canvas cv) {
            float s = Math.min(getWidth(), getHeight()) / 24f;
            cv.save();
            cv.scale(s, s);
            p.setColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            // arrow up out of a tray
            path.reset();
            path.moveTo(12f, 15f); path.lineTo(12f, 3.5f);
            path.moveTo(7.5f, 8f); path.lineTo(12f, 3.5f); path.lineTo(16.5f, 8f);
            path.moveTo(5f, 12.5f); path.lineTo(5f, 19f); path.quadTo(5f, 20.5f, 6.5f, 20.5f);
            path.lineTo(17.5f, 20.5f); path.quadTo(19f, 20.5f, 19f, 19f); path.lineTo(19f, 12.5f);
            cv.drawPath(path, p);
            cv.restore();
        }
    }
}
