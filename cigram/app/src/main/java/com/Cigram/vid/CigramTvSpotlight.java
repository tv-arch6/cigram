package com.Cigram.vid;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CIGRAM TV — Cinema Spotlight (TV edition only; a phone never builds it).
 *
 * The top of the TV home becomes a small cinema: up to six titles take the screen in turn, each on its full-bleed
 * backdrop that drifts slowly towards the viewer (a "Ken Burns" push), under a soft projector beam from the top
 * corner and a dark vignette that keeps the text readable on any picture. Titles change with a cross-fade every
 * nine seconds; a row of light bars shows which one is on and how long it has left.
 *
 * Remote: the hero buttons always act on the title on screen; ◀ / ▶ on the bar row change the title by hand (the
 * rotation pauses for 15 s after any key in the spotlight, so a title never changes under the viewer's finger).
 * On a weak box (Performance Engine = LITE) the push and the beam are left out and the rotation is slower.
 * The animation stops as soon as the panel leaves the screen.
 */
final class CigramTvSpotlight {

    private CigramTvSpotlight() { }

    static final long INTERVAL_MS = 9000L, PAUSE_AFTER_KEY_MS = 15000L, FADE_MS = 900L;

    /** Pure: up to {@code max} spotlight titles, newest first, with a backdrop, alternating films and series, no repeats. */
    static List<JSONObject> pick(List<JSONObject> all, int max) {
        List<JSONObject> films = new ArrayList<JSONObject>(), series = new ArrayList<JSONObject>();
        Set<String> seen = new HashSet<String>();
        for (JSONObject o : all) {
            if (CigramTvCards.str(o, "backdrop", "landscape_poster", "source_backdrop").length() == 0) continue;
            String key = CigramTvCards.str(o, "id", "title");
            if (key.length() == 0 || !seen.add(key)) continue;
            if (CigramTvCards.isSeries(o)) series.add(o); else films.add(o);
        }
        List<JSONObject> out = new ArrayList<JSONObject>();
        int i = 0, j = 0;
        while (out.size() < max && (i < films.size() || j < series.size())) {
            if (i < films.size()) out.add(films.get(i++));
            if (out.size() < max && j < series.size()) out.add(series.get(j++));
        }
        return out;
    }

    static View build(final CigramTvActivity a, final List<JSONObject> items) {
        final boolean rich = CigramPerformance.get(a).animations;
        final int height = Math.min(a.dp(420), (int) (a.getResources().getDisplayMetrics().heightPixels * 0.70f));
        final FrameLayout root = new FrameLayout(a);
        root.setBackgroundColor(0xFF07070A);
        root.setClipChildren(true);

        final ImageView[] layers = {new ImageView(a), new ImageView(a)};
        for (ImageView iv : layers) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setAlpha(0f);
            root.addView(iv, new FrameLayout.LayoutParams(-1, height));
        }
        if (rich) root.addView(new Beam(a), new FrameLayout.LayoutParams(-1, height));
        View side = new View(a);
        side.setBackground(new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, new int[]{0x00000000, 0x990B0B0E, 0xF50B0B0E}));
        root.addView(side, new FrameLayout.LayoutParams(-1, height));
        View bottom = new View(a);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x000E0E10, 0xFF0E0E10}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, a.dp(150), Gravity.BOTTOM));

        // ---- text block (right side, RTL)
        final LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.RIGHT | Gravity.BOTTOM);
        info.setPadding(a.dp(40), a.dp(30), a.dp(44), a.dp(22));
        final int infoWidth = Math.min(a.dp(660), (int) (a.getResources().getDisplayMetrics().widthPixels * 0.56f));

        TextView badge = new TextView(a);
        badge.setText("CINEMA SPOTLIGHT  ·  سينما سبوت لايت");
        badge.setTextColor(0xFFF2C14E);
        badge.setTextSize(12f);
        badge.setLetterSpacing(0.12f);
        badge.setTypeface(a.font(), Typeface.BOLD);
        badge.setGravity(Gravity.RIGHT);
        info.addView(badge, new LinearLayout.LayoutParams(infoWidth, -2));

        final TextView name = new TextView(a);
        name.setTextColor(CigramDesign.TEXT);
        name.setTextSize(34f);
        name.setTypeface(a.font(), Typeface.BOLD);
        name.setGravity(Gravity.RIGHT);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setShadowLayer(a.dp(6), 0, a.dp(2), 0x99000000);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(infoWidth, -2);
        nl.topMargin = a.dp(6);
        info.addView(name, nl);

        final TextView meta = new TextView(a);
        meta.setTextColor(CigramDesign.TEXT_2);
        meta.setTextSize(15f);
        meta.setTypeface(a.font(), Typeface.BOLD);
        meta.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(infoWidth, -2);
        ml.topMargin = a.dp(6);
        info.addView(meta, ml);

        final TextView desc = new TextView(a);
        desc.setTextColor(CigramDesign.TEXT_2);
        desc.setTextSize(14f);
        desc.setTypeface(a.font());
        desc.setMaxLines(2);
        desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        desc.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Math.min(a.dp(580), infoWidth), -2);
        dl.topMargin = a.dp(8);
        info.addView(desc, dl);

        final int[] current = {0};
        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        buttons.setGravity(Gravity.RIGHT);
        buttons.addView(button(a, "التفاصيل", false, items, current), lp(a));
        final View watch = button(a, "شاهد الآن", true, items, current);
        buttons.addView(watch, lp(a));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = a.dp(16);
        info.addView(buttons, bl);

        // light bars: one per title, the current one fills up until the next change; focusable for ◀ ▶
        final Bars bars = new Bars(a, items.size());
        bars.setFocusable(items.size() > 1);
        bars.setContentDescription("اختيار عنوان السبوت لايت بالأسهم");
        LinearLayout.LayoutParams barl = new LinearLayout.LayoutParams(a.dp(26) * Math.max(1, items.size()) + a.dp(8) * items.size(), a.dp(18));
        barl.topMargin = a.dp(14);
        info.addView(bars, barl);
        root.addView(info, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final long[] shownAt = {0}, pausedUntil = {0};
        final int[] front = {0};

        final Runnable[] show = new Runnable[1];
        show[0] = new Runnable() {
            @Override public void run() {
                if (items.isEmpty()) return;
                JSONObject item = items.get(current[0]);
                ImageView in = layers[front[0] ^ 1], out = layers[front[0]];
                front[0] ^= 1;
                in.animate().cancel();
                in.setScaleX(1f);
                in.setScaleY(1f);
                CigramTvImage.load(in, CigramTvCards.str(item, "backdrop", "landscape_poster", "source_backdrop", "image", "poster"), 1280);
                in.animate().alpha(1f).setDuration(rich ? FADE_MS : 250L).start();
                out.animate().alpha(0f).setDuration(rich ? FADE_MS : 250L).start();
                if (rich) in.animate().scaleX(1.08f).scaleY(1.08f).setDuration(INTERVAL_MS + FADE_MS).setInterpolator(new android.view.animation.LinearInterpolator()).start();
                name.setText(CigramTvCards.str(item, "title", "name", "series_title"));
                meta.setText(metaOf(item));
                String d = CigramTvCards.str(item, "description", "overview", "story", "plot");
                desc.setText(d);
                desc.setVisibility(d.length() > 0 ? View.VISIBLE : View.GONE);
                info.setAlpha(0.35f);
                info.animate().alpha(1f).setDuration(450L).start();
                shownAt[0] = android.os.SystemClock.elapsedRealtime();
                bars.set(current[0], 0f);
            }
        };
        final long interval = rich ? INTERVAL_MS : INTERVAL_MS * 2;
        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (a.isFinishing() || !root.isAttachedToWindow()) return;      // the panel was replaced: stop
                long now = android.os.SystemClock.elapsedRealtime();
                if (items.size() > 1 && now >= pausedUntil[0]) {
                    float f = Math.min(1f, (now - shownAt[0]) / (float) interval);
                    bars.set(current[0], f);
                    if (f >= 1f) { current[0] = (current[0] + 1) % items.size(); show[0].run(); }
                } else if (shownAt[0] > 0) {
                    shownAt[0] = now - (long) (bars.progress * interval);   // keep the bar where it is while paused
                }
                h.postDelayed(this, 120L);
            }
        };
        bars.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN || items.size() < 2) return false;
                // RTL: ◀ goes forward in the row, ▶ back
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) current[0] = (current[0] + 1) % items.size();
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) current[0] = (current[0] - 1 + items.size()) % items.size();
                else return false;
                pausedUntil[0] = android.os.SystemClock.elapsedRealtime() + PAUSE_AFTER_KEY_MS;
                show[0].run();
                return true;
            }
        });
        final View.OnFocusChangeListener pause = new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                if (has) pausedUntil[0] = android.os.SystemClock.elapsedRealtime() + PAUSE_AFTER_KEY_MS;
                v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(120L).start();
                if (v instanceof Bars) ((Bars) v).focused = has;
                v.invalidate();
            }
        };
        bars.setOnFocusChangeListener(pause);
        for (int i = 0; i < buttons.getChildCount(); i++) buttons.getChildAt(i).setOnFocusChangeListener(pause);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { h.removeCallbacks(tick); h.postDelayed(tick, 120L); }
            @Override public void onViewDetachedFromWindow(View v) { h.removeCallbacks(tick); for (ImageView iv : layers) iv.animate().cancel(); }
        });
        root.setLayoutParams(new LinearLayout.LayoutParams(-1, height));
        show[0].run();
        root.post(new Runnable() { @Override public void run() { watch.requestFocus(); } });
        return root;
    }

    static String metaOf(JSONObject item) {
        StringBuilder meta = new StringBuilder(CigramTvCards.isSeries(item) ? "مسلسل" : "فيلم");
        String year = CigramTvCards.str(item, "year", "release_year");
        String imdb = CigramTvCards.str(item, "imdb", "rating", "vote_average");
        String cat = CigramTvCards.str(item, "category", "genre", "genres", "section_name");
        if (year.length() > 0) meta.append("   ·   ").append(year);
        if (imdb.length() > 0 && !imdb.startsWith("tt")) meta.append("   ·   IMDb ").append(imdb);
        if (cat.length() > 0) meta.append("   ·   ").append(cat.split("[,،]")[0].trim());
        return meta.toString();
    }

    private static LinearLayout.LayoutParams lp(CigramTvActivity a) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = a.dp(10);
        return lp;
    }

    private static View button(final CigramTvActivity a, String text, boolean primary, final List<JSONObject> items, final int[] current) {
        TextView b = new TextView(a);
        b.setText(text);
        b.setTextColor(primary ? 0xFF0E0E10 : CigramDesign.TEXT);
        b.setTextSize(16f);
        b.setTypeface(a.font(), Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(a.dp(28), a.dp(13), a.dp(28), a.dp(13));
        b.setBackground(primary ? CigramDesign.primaryPill(a, 26) : CigramDesign.ghostPill(a, 26));
        if (primary) b.setTag("cigram_tv_hero_primary");
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (!items.isEmpty()) a.openDetails(items.get(current[0]), v); }
        });
        return b;
    }

    /** The projector beam: a soft warm light from the top-right corner. */
    private static final class Beam extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Beam(android.content.Context c) { super(c); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            p.setShader(new RadialGradient(w * 0.88f, -h * 0.15f, Math.max(w, h) * 0.75f,
                    new int[]{0x33FFF1D6, 0x14FFE7B8, 0x00000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override protected void onDraw(Canvas c) { c.drawRect(0, 0, getWidth(), getHeight(), p); }
    }

    /** One light bar per title; the current one fills. */
    static final class Bars extends View {
        final int count;
        int index;
        float progress;
        boolean focused;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Bars(android.content.Context c, int count) { super(c); this.count = count; }

        void set(int i, float f) { index = i; progress = f; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            if (count <= 1) return;
            float d = getResources().getDisplayMetrics().density, h = getHeight(), bw = 26 * d, gap = 8 * d, bh = 4 * d;
            float x = getWidth();                                   // first title on the right
            for (int i = 0; i < count; i++) {
                x -= bw;
                r.set(x, (h - bh) / 2f, x + bw, (h + bh) / 2f);
                p.setColor(focused ? 0x66FFFFFF : 0x40FFFFFF);
                c.drawRoundRect(r, bh, bh, p);
                if (i == index) {
                    p.setColor(0xFFE21B14);
                    r.set(x + bw * (1f - Math.max(0.06f, progress)), (h - bh) / 2f, x + bw, (h + bh) / 2f);
                    c.drawRoundRect(r, bh, bh, p);
                } else if (i < index) {
                    p.setColor(0xB3FFFFFF);
                    c.drawRoundRect(r, bh, bh, p);
                }
                x -= gap;
            }
        }
    }
}
