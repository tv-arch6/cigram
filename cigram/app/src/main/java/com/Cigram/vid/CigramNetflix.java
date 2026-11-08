package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v10.70 Netflix-style home pieces. Every piece is built from catalogue items and placed / styled by
 * the Admin app through {@link CigramLayoutConfig}:
 *
 *  - {@link #chips}: «مسلسلات» «أفلام» … under the header;
 *  - {@link #billboard}: the big featured title with «تشغيل» and «قائمتي»;
 *  - {@link #top10Row}: «أفضل 10» with the big outlined ranks next to the posters;
 *  - {@link #landscapeRow}: wide 16:9 cards (backdrops);
 *  - {@link #largeRow}: tall, bigger posters.
 *
 * Colours come from {@link CigramTheme}, so every piece is right in Light and Dark mode. Text drawn on a
 * picture is white in both modes (those parts are tagged {@link CigramTheme#SKIP_TAG}).
 */
public final class CigramNetflix {

    static final String TAG_CHIPS = "cg_nf_chips";
    static final String TAG_BILLBOARD = "cg_nf_billboard";

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);

    private CigramNetflix() { }

    // ================================================================== chips

    /** Netflix's top chips. Tapping one opens the matching list page. */
    static View chips(final Activity a) {
        ArrayList<JSONObject> list = CigramLayoutConfig.chips(a);
        if (list.isEmpty()) return null;
        HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setTag(TAG_CHIPS);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.setPadding(dp(a, 12), dp(a, 4), dp(a, 12), dp(a, 10));
        boolean light = CigramTheme.isLight(a);
        // right-to-left: the first chip is the right-most one
        for (int i = list.size() - 1; i >= 0; i--) {
            final JSONObject chip = list.get(i);
            TextView t = new TextView(a);
            t.setText(chip.optString("title"));
            t.setTextSize(14f);
            t.setTextColor(CigramTheme.text(a));
            t.setGravity(Gravity.CENTER);
            t.setIncludeFontPadding(false);
            t.setTypeface(font(a), Typeface.BOLD);
            t.setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 8));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(a, 18));
            bg.setColor(0x00000000);
            bg.setStroke(Math.max(1, dp(a, 1)), light ? 0x40000000 : 0x66FFFFFF);
            t.setBackground(bg);
            t.setClickable(true);
            t.setFocusable(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openChip(a, chip); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(a, 8);
            row.addView(t, lp);
        }
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
        scroll.setMinimumWidth(a.getResources().getDisplayMetrics().widthPixels);
        row.setMinimumWidth(a.getResources().getDisplayMetrics().widthPixels);
        return scroll;
    }

    private static void openChip(Activity a, JSONObject chip) {
        try {
            String type = chip.optString("type", "");
            String id = chip.optString("section_id", "");
            if (id.length() == 0) id = "series".equals(type) ? "featured_series" : "latest_movies";
            if (type.length() == 0) type = "all";
            Intent i = new Intent();
            i.setClassName(a, a.getPackageName() + ".SectionActivity");
            i.putExtra("section_id", id);
            i.putExtra("section_title", chip.optString("title"));
            i.putExtra("section_name", chip.optString("title"));
            i.putExtra("section_type", type);
            a.startActivity(i);
            a.overridePendingTransition(R.anim.cg_fade_in, R.anim.cg_fade_out);
        } catch (Throwable error) {
            android.util.Log.w("CigramNetflix", "chip", error);
        }
    }

    // ================================================================== billboard

    /** The big featured title (portrait card, title, genres, «تشغيل» and «قائمتي»). */
    static View billboard(final Activity a, final JSONObject item) {
        if (item == null) return null;
        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        int screenH = a.getResources().getDisplayMetrics().heightPixels;
        int cardW = screenW - dp(a, 32);
        int cardH = Math.min(Math.round(cardW * 1.36f), Math.round(screenH * 0.66f));

        LinearLayout outer = new LinearLayout(a);
        outer.setTag(TAG_BILLBOARD);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER_HORIZONTAL);
        outer.setPadding(0, dp(a, 4), 0, dp(a, 14));

        final FrameLayout card = new FrameLayout(a);
        card.setTag(CigramTheme.SKIP_TAG);   // everything on the picture stays white in both modes
        final float radius = dp(a, 14);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
        card.setClipToOutline(true);
        card.setElevation(dp(a, 8));
        card.setBackgroundColor(0xFF101418);

        final ImageView image = new ImageView(a);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(image, new FrameLayout.LayoutParams(-1, -1));
        String poster = CigramContent.text(item, "poster", "series_poster", "image", "backdrop");
        loadLarge(a, poster, image);

        View shade = new View(a);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x00000000, 0xB3000000, 0xF2000000}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_HORIZONTAL);
        info.setPadding(dp(a, 16), 0, dp(a, 16), dp(a, 16));

        TextView title = new TextView(a);
        title.setText(CigramContent.title(item));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(26f);
        title.setTypeface(font(a), Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setShadowLayer(dp(a, 6), 0, 0, 0xCC000000);
        info.addView(title, new LinearLayout.LayoutParams(-1, -2));

        String genres = genresLine(item);
        if (genres.length() > 0) {
            TextView g = new TextView(a);
            g.setText(genres);
            g.setTextColor(0xFFE5E5E5);
            g.setTextSize(13f);
            g.setTypeface(font(a));
            g.setGravity(Gravity.CENTER);
            g.setSingleLine(true);
            g.setEllipsize(TextUtils.TruncateAt.END);
            g.setShadowLayer(dp(a, 4), 0, 0, 0xCC000000);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
            glp.topMargin = dp(a, 6);
            info.addView(g, glp);
        }

        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        buttons.setGravity(Gravity.CENTER);
        TextView play = button(a, "▶  تشغيل", 0xFFFFFFFF, 0xFF000000);
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { CigramStage1InitProvider.cigramPlayTitle(a, item); }
        });
        final TextView myList = button(a, "", 0x59808080, 0xFFFFFFFF);
        setListLabel(a, myList, item);
        myList.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CigramStage1InitProvider.cigramToggleFavorite(a, item);
                setListLabel(a, myList, item);
                v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(90L).withEndAction(new Runnable() {
                    @Override public void run() { myList.animate().scaleX(1f).scaleY(1f).setDuration(110L).start(); }
                }).start();
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(a, 44), 1f);
        bp.leftMargin = dp(a, 6);
        bp.rightMargin = dp(a, 6);
        buttons.addView(play, bp);
        buttons.addView(myList, new LinearLayout.LayoutParams(bp));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(a, 14);
        info.addView(buttons, blp);

        card.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { CigramStage1InitProvider.openTitleDetails(a, item, image); }
        });
        outer.addView(card, new LinearLayout.LayoutParams(cardW, cardH));
        return outer;
    }

    private static void setListLabel(Context c, TextView t, JSONObject item) {
        boolean in = CigramStage1InitProvider.cigramIsFavorite(c, item);
        t.setText(in ? "✓  في قائمتي" : "＋  قائمتي");
    }

    private static TextView button(Context c, String text, int fill, int textColor) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(textColor);
        t.setTextSize(15f);
        t.setTypeface(font(c), Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(c, 6));
        t.setBackground(bg);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private static String genresLine(JSONObject item) {
        try {
            Collection<String> g = CigramContent.genres(item);
            if (g != null && !g.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                int n = 0;
                for (String s : g) {
                    if (s == null || s.trim().length() == 0) continue;
                    if (n > 0) sb.append("  •  ");
                    sb.append(s.trim());
                    if (++n == 4) break;
                }
                if (sb.length() > 0) return sb.toString();
            }
        } catch (Throwable ignored) { }
        String cat = CigramContent.text(item, "category", "genre");
        String year = CigramContent.text(item, "year", "release_year");
        return year.length() == 0 ? cat : (cat.length() == 0 ? year : year + "  •  " + cat);
    }

    // ================================================================== rows

    /** «أفضل 10»: the big outlined rank next to each poster (rank 1 at the far right). */
    static View top10Row(final Activity a, List<JSONObject> items) {
        int posterW = Math.round(CigramDesign.cardWidth(a) * 0.95f);
        int posterH = Math.round(posterW * 1.5f);
        int numberW = Math.round(posterW * 0.62f);
        LinearLayout row = rowContainer(a);
        int count = Math.min(10, items.size());
        for (int i = 0; i < count; i++) {
            final JSONObject item = items.get(i);
            FrameLayout cell = new FrameLayout(a);
            RankView rank = new RankView(a, i + 1, posterH);
            cell.addView(rank, new FrameLayout.LayoutParams(numberW, posterH, Gravity.RIGHT | Gravity.BOTTOM));
            final ImageView poster = posterView(a);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(posterW, posterH, Gravity.LEFT | Gravity.BOTTOM);
            cell.addView(poster, plp);
            CigramStage1InitProvider.cigramLoadPoster(a, CigramContent.text(item, "poster", "series_poster", "image"), poster);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { CigramStage1InitProvider.openTitleDetails(a, item, poster); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(posterW + Math.round(numberW * 0.72f), posterH);
            lp.leftMargin = dp(a, 4);
            row.addView(cell, 0, lp);
        }
        return wrap(a, row);
    }

    /** Wide 16:9 cards with the backdrop and the title under it. */
    static View landscapeRow(final Activity a, List<JSONObject> items) {
        int w = Math.round(a.getResources().getDisplayMetrics().widthPixels * 0.62f);
        int h = Math.round(w * 9f / 16f);
        LinearLayout row = rowContainer(a);
        int count = Math.min(20, items.size());
        for (int i = 0; i < count; i++) {
            final JSONObject item = items.get(i);
            LinearLayout cell = new LinearLayout(a);
            cell.setOrientation(LinearLayout.VERTICAL);
            final ImageView image = posterView(a);
            cell.addView(image, new LinearLayout.LayoutParams(w, h));
            String backdrop = CigramContent.text(item, "backdrop", "landscape_poster", "source_backdrop", "poster", "image");
            CigramStage1InitProvider.cigramLoadPoster(a, backdrop, image);
            TextView t = label(a, CigramContent.title(item), 13f, false);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(w, -2);
            tlp.topMargin = dp(a, 6);
            cell.addView(t, tlp);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { CigramStage1InitProvider.openTitleDetails(a, item, image); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, -2);
            lp.leftMargin = dp(a, 8);
            row.addView(cell, 0, lp);
        }
        return wrap(a, row);
    }

    /** Tall, bigger posters (Netflix «Only on» row). */
    static View largeRow(final Activity a, List<JSONObject> items) {
        int w = Math.round(CigramDesign.cardWidth(a) * 1.45f);
        int h = Math.round(w * 1.5f);
        LinearLayout row = rowContainer(a);
        int count = Math.min(20, items.size());
        for (int i = 0; i < count; i++) {
            final JSONObject item = items.get(i);
            final ImageView poster = posterView(a);
            CigramStage1InitProvider.cigramLoadPoster(a, CigramContent.text(item, "poster", "series_poster", "image"), poster);
            poster.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { CigramStage1InitProvider.openTitleDetails(a, item, poster); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.leftMargin = dp(a, 8);
            row.addView(poster, 0, lp);
        }
        return wrap(a, row);
    }

    private static LinearLayout rowContainer(Context a) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.RIGHT | Gravity.BOTTOM);
        row.setPadding(dp(a, 12), 0, dp(a, 12), 0);
        return row;
    }

    /** Same scrolling as the other home rows: LTR, first card at the far right, starts scrolled right. */
    private static View wrap(Context a, LinearLayout row) {
        final HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        scroll.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
        scroll.post(new Runnable() {
            @Override public void run() {
                try { scroll.fullScroll(View.FOCUS_RIGHT); } catch (Throwable ignored) { }
            }
        });
        return scroll;
    }

    private static ImageView posterView(Context a) {
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final float r = dp(a, 8);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        iv.setClipToOutline(true);
        iv.setBackgroundColor(CigramTheme.surface(a));
        return iv;
    }

    private static TextView label(Context a, String text, float sp, boolean secondary) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(CigramTheme.label(a, secondary));
        t.setTypeface(font(a), Typeface.BOLD);
        t.setGravity(Gravity.RIGHT);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** The big outlined number of a «أفضل 10» card, in the page colour with a contrasting outline. */
    static final class RankView extends View {
        private final String text;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        RankView(Context c, int rank, int heightPx) {
            super(c);
            text = String.valueOf(rank);
            float size = heightPx * 0.92f;
            Typeface tf = Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
            fill.setTypeface(tf);
            fill.setTextSize(size);
            fill.setColor(CigramTheme.page(c));
            stroke.setTypeface(tf);
            stroke.setTextSize(size);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(2f, size * 0.035f));
            stroke.setColor(CigramTheme.isLight(c) ? 0xFF9AA3A9 : 0xFF8E8E93);
            fill.setTextAlign(Paint.Align.RIGHT);
            stroke.setTextAlign(Paint.Align.RIGHT);
            setTag(CigramTheme.SKIP_TAG);
        }

        @Override protected void onDraw(Canvas canvas) {
            float x = getWidth() - stroke.getStrokeWidth();
            float y = getHeight() - fill.descent() * 0.4f;
            canvas.drawText(text, x, y, fill);
            canvas.drawText(text, x, y, stroke);
        }
    }

    // ================================================================== images

    /** Billboard picture: a sharper size than the row posters, own cache, soft fade-in. */
    private static void loadLarge(final Context c, String raw, final ImageView target) {
        String url = raw == null ? "" : raw.trim();
        if (url.startsWith("//")) url = "https:" + url;
        if (url.contains("image.tmdb.org/t/p/")) url = url.replaceAll("/t/p/(w[0-9]+|h[0-9]+|original)/", "/t/p/w780/");
        if (url.length() == 0) return;
        final String finalUrl = url;
        final File dir = new File(c.getCacheDir(), "cigram_billboard");
        IO.execute(new Runnable() {
            @Override public void run() {
                Bitmap bmp = null;
                try {
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, Integer.toHexString(finalUrl.hashCode()) + ".img");
                    if (!f.isFile() || f.length() == 0) {
                        HttpURLConnection con = (HttpURLConnection) new URL(finalUrl).openConnection();
                        con.setConnectTimeout(8000);
                        con.setReadTimeout(12000);
                        con.setInstanceFollowRedirects(true);
                        if (con.getResponseCode() / 100 != 2) return;
                        InputStream in = con.getInputStream();
                        File part = new File(dir, f.getName() + ".part");
                        FileOutputStream out = new FileOutputStream(part);
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        out.close();
                        in.close();
                        con.disconnect();
                        if (!part.renameTo(f)) return;
                    }
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inPreferredConfig = Bitmap.Config.RGB_565;
                    bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
                } catch (Throwable ignored) { }
                final Bitmap result = bmp;
                if (result == null) return;
                UI.post(new Runnable() {
                    @Override public void run() {
                        target.setAlpha(0f);
                        target.setImageBitmap(result);
                        target.animate().alpha(1f).setDuration(260L).start();
                    }
                });
            }
        });
    }

    // ================================================================== helpers

    private static Typeface font(Context c) {
        try { return CigramStage1InitProvider.cigramFont(c); } catch (Throwable t) { return Typeface.DEFAULT; }
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Lets a ViewGroup forget a child safely (used when a piece is rebuilt). */
    static void removeTagged(ViewGroup parent, String tag) {
        if (parent == null) return;
        View old = parent.findViewWithTag(tag);
        if (old != null && old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
    }
}
