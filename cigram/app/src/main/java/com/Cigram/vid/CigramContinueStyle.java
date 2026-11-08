package com.Cigram.vid;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * "أكمل المشاهدة" poster look (v2.5), shared by the home screen and "محتوياتي":
 * dark scrim at the bottom, round play button, red episode/movie tag on the top corner,
 * remaining-time chip and a slim glowing progress line. All text uses the sima font.
 */
public final class CigramContinueStyle {

    private static final String TAG = "cigram_continue_decor";

    private CigramContinueStyle() { }

    private static final class PlayView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        PlayView(Activity a) { super(a); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float r = Math.min(w, h) / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xB3E21B14);
            c.drawCircle(w / 2f, h / 2f, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.08f);
            p.setColor(0x99FFFFFF);
            c.drawCircle(w / 2f, h / 2f, r - r * 0.05f, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            path.reset();
            float cx = w / 2f + r * 0.08f;
            float cy = h / 2f;
            path.moveTo(cx - r * 0.28f, cy - r * 0.38f);
            path.lineTo(cx + r * 0.42f, cy);
            path.lineTo(cx - r * 0.28f, cy + r * 0.38f);
            path.close();
            c.drawPath(path, p);
        }
    }

    private static final class ProgressLine extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float fraction;
        ProgressLine(Activity a, float fraction) {
            super(a);
            this.fraction = Math.max(0.02f, Math.min(1f, fraction));
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            RectF track = new RectF(0, 0, w, h);
            p.setColor(0x66FFFFFF);
            c.drawRoundRect(track, h / 2f, h / 2f, p);
            RectF fill = new RectF(0, 0, w * fraction, h);
            p.setColor(0xFFE21B14);
            c.drawRoundRect(fill, h / 2f, h / 2f, p);
        }
    }

    private static final class PlayDisc extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        PlayDisc(Activity a) { super(a); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float r = Math.min(w, h) / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xF2FFFFFF);
            c.drawCircle(w / 2f, h / 2f, r, p);
            p.setColor(0xFF0B1A22);
            path.reset();
            float cx = w / 2f + r * 0.06f;
            float cy = h / 2f;
            path.moveTo(cx - r * 0.26f, cy - r * 0.36f);
            path.lineTo(cx + r * 0.40f, cy);
            path.lineTo(cx - r * 0.26f, cy + r * 0.36f);
            path.close();
            c.drawPath(path, p);
        }
    }

    /**
     * v2.8: the "إكمال" mark drawn over the middle of a poster whose title is half-watched
     * (white play disc + the word "إكمال"). Home rows use it instead of a separate section.
     * The returned view is not clickable, so taps still reach the poster.
     */
    public static View posterOverlay(Activity a) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setClickable(false);
        box.setVisibility(View.GONE);
        PlayDisc disc = new PlayDisc(a);
        box.addView(disc, new LinearLayout.LayoutParams(CigramUI.dp(a, 44), CigramUI.dp(a, 44)));
        TextView label = CigramUI.label(a, "إكمال", 14f, 0xFFFFFFFF, true);
        label.setGravity(Gravity.CENTER);
        label.setShadowLayer(CigramUI.dp(a, 4), 0f, CigramUI.dp(a, 1), 0xFF000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = CigramUI.dp(a, 6);
        box.addView(label, lp);
        return box;
    }

    private static String num(JSONObject item, String key) {
        String v = item.optString(key, "").trim();
        return v.length() == 0 ? "1" : v;
    }

    /**
     * @param box       poster frame (the poster image is already inside it)
     * @param showTitle also draw the title on the poster (used where no caption exists below)
     * @param hide      old decorations (previous overlay / play button / system progress bar)
     */
    public static void decorate(Activity a, FrameLayout box, JSONObject item, boolean showTitle, View... hide) {
        if (a == null || box == null || item == null) return;
        try {
            View old = box.findViewWithTag(TAG);
            if (old != null) box.removeView(old);
            if (hide != null) {
                for (int i = 0; i < hide.length; i++) if (hide[i] != null) hide[i].setVisibility(View.GONE);
            }
            long pos = item.optLong("position", 0L);
            long dur = item.optLong("duration", 0L);
            boolean serial = "series".equalsIgnoreCase(item.optString("type", ""));
            float fraction = dur > 0L ? pos / (float) dur : 0f;

            FrameLayout layer = new FrameLayout(a);
            layer.setTag(TAG);

            // scrim
            View scrim = new View(a);
            GradientDrawable sg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, 0x99000A10, 0xEE000A10});
            scrim.setBackground(sg);
            layer.addView(scrim, new FrameLayout.LayoutParams(-1, CigramUI.dp(a, 96), Gravity.BOTTOM));

            // play button
            PlayView play = new PlayView(a);
            FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(
                    CigramUI.dp(a, 42), CigramUI.dp(a, 42), Gravity.CENTER);
            layer.addView(play, pl);

            // tag (top right)
            String tagText = serial ? "م" + num(item, "season") + " · ح" + num(item, "episode") : "فيلم";
            TextView tag = CigramUI.label(a, tagText, 11f, 0xFFFFFFFF, true);
            tag.setGravity(Gravity.CENTER);
            tag.setTextDirection(View.TEXT_DIRECTION_RTL);
            tag.setBackground(CigramUI.round(0xE6E21B14, 8, a));
            tag.setPadding(CigramUI.dp(a, 8), CigramUI.dp(a, 3), CigramUI.dp(a, 8), CigramUI.dp(a, 3));
            FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
            tl.setMargins(CigramUI.dp(a, 8), CigramUI.dp(a, 8), CigramUI.dp(a, 8), 0);
            layer.addView(tag, tl);

            // bottom column: title, remaining time, progress
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            col.setPadding(CigramUI.dp(a, 9), 0, CigramUI.dp(a, 9), CigramUI.dp(a, 9));
            if (showTitle) {
                String title = serial ? item.optString("series_title", item.optString("title", ""))
                        : item.optString("title", "");
                TextView t = CigramUI.label(a, title, 13f, 0xFFFFFFFF, true);
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                col.addView(t, new LinearLayout.LayoutParams(-1, -2));
            }
            long left = dur - pos;
            if (dur > 0L && left > 0L) {
                long mins = Math.max(1L, left / 60000L);
                String leftText = mins >= 60L ? "باقي " + (mins / 60L) + " س " + (mins % 60L) + " د"
                        : "باقي " + mins + " د";
                TextView chip = CigramUI.label(a, leftText, 11f, 0xFFD2DDE2, false);
                col.addView(chip, new LinearLayout.LayoutParams(-1, -2));
            }
            ProgressLine line = new ProgressLine(a, fraction);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, CigramUI.dp(a, 4));
            ll.topMargin = CigramUI.dp(a, 6);
            col.addView(line, ll);
            layer.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

            box.addView(layer, new FrameLayout.LayoutParams(-1, -1));
            layer.setClickable(false);
        } catch (Throwable error) {
            android.util.Log.w("CigramContinue", "decorate failed", error);
        }
    }
}
