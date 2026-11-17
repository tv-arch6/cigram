package com.Cigram.vid;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/**
 * The Plus pop-up (v18.1.0): a white card with 20dp corners, black text and red numbers, a close icon on its top-left
 * corner and a yellow "اشترك بـ Plus" button that opens the subscription page. Shown to FREE users only.
 *
 * Rules (all in {@link #shouldShow}):
 *   - never for Plus accounts, never on TV;
 *   - only on Sunday, Thursday and Friday, once on each of those days (device date);
 *   - at most once per app process, only on Home (MainActivity), only when that screen is in front.
 * Every impression is stored (timestamps only, no personal data) in prefs "cigram_plus_promo".
 * Always closable (X and "لاحقاً"), tapping outside closes it too: no forced click, no hidden close (Play ads policy).
 * Google Play build (no PLUS_SALES): no prices and no purchase wording, the button says "ضع كود التفعيل".
 */
public final class CigramPlusPromo {

    static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;
    static final long DELAY_MS = 2500L;                        // soon after Home opens

    /** The days the pop-up may appear (once on each): Sunday, Thursday, Friday. */
    static boolean dayAllowed(long now, java.util.TimeZone tz) {
        java.util.Calendar c = java.util.Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        int d = c.get(java.util.Calendar.DAY_OF_WEEK);
        return d == java.util.Calendar.SUNDAY || d == java.util.Calendar.THURSDAY || d == java.util.Calendar.FRIDAY;
    }

    private static final String PREFS = "cigram_plus_promo";
    private static volatile boolean shownThisProcess;
    private static volatile boolean scheduled;
    private static Handler handler;

    private CigramPlusPromo() { }

    // ------------------------------------------------------------------ pacing (pure, unit-tested)

    /**
     * {@code history} = timestamps of earlier impressions. Shown only on Sunday, Thursday and Friday (device time),
     * and at most once per calendar day.
     */
    static boolean shouldShow(long[] history, long now, java.util.TimeZone tz) {
        if (!dayAllowed(now, tz)) return false;
        java.util.Calendar today = java.util.Calendar.getInstance(tz);
        today.setTimeInMillis(now);
        if (history != null) {
            for (long t : history) {
                if (t > now) return false;                       // clock went backwards: stay quiet rather than spam
                java.util.Calendar c = java.util.Calendar.getInstance(tz);
                c.setTimeInMillis(t);
                if (c.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
                        && c.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR)) return false;
            }
        }
        return true;
    }

    static boolean shouldShow(long[] history, long now) {
        return shouldShow(history, now, java.util.TimeZone.getDefault());
    }

    /** Counts as one impression made now (the onboarding already spoke to the user today). */
    static void holdToday(Context c) {
        long now = System.currentTimeMillis();
        save(c, record(load(c), now));
    }

    /** Adds {@code now} and drops everything older than a week. */
    static long[] record(long[] history, long now) {
        int keep = 0;
        long[] tmp = new long[(history == null ? 0 : history.length) + 1];
        if (history != null) for (long t : history) if (now - t < WEEK_MS && t <= now) tmp[keep++] = t;
        tmp[keep++] = now;
        long[] out = new long[keep];
        System.arraycopy(tmp, 0, out, 0, keep);
        return out;
    }

    private static long[] load(Context c) {
        try {
            String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("shown", "");
            if (s == null || s.length() == 0) return new long[0];
            String[] parts = s.split(",");
            long[] out = new long[parts.length];
            int n = 0;
            for (String p : parts) {
                try { out[n++] = Long.parseLong(p.trim()); } catch (NumberFormatException ignored) { }
            }
            long[] r = new long[n];
            System.arraycopy(out, 0, r, 0, n);
            return r;
        } catch (Throwable t) {
            return new long[0];
        }
    }

    private static void save(Context c, long[] h) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < h.length; i++) { if (i > 0) sb.append(','); sb.append(h[i]); }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("shown", sb.toString()).apply();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ trigger

    /** Call from the activity lifecycle (onResumed). Cheap: returns at once unless it is Home and nothing was shown yet. */
    public static void onResumed(final Activity a) {
        try {
            if (a == null || shownThisProcess || scheduled) return;
            if (!"MainActivity".equals(a.getClass().getSimpleName())) return;
            if (showLastCrash(a)) return;
            if (CigramPlus.isPlus(a) || CigramTvMode.isTv(a)) return;
            if (!shouldShow(load(a), System.currentTimeMillis())) return;
            scheduled = true;
            if (handler == null) handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    scheduled = false;
                    try { showNow(a); } catch (Throwable t) { android.util.Log.w("CigramPlusPromo", "skip", t); }
                }
            }, DELAY_MS);
        } catch (Throwable ignored) { }
    }

    /**
     * If the last crash of the app involved the Plus code, show its trace once (so it can be photographed and fixed).
     * Reads what CigramStability already stores. Returns true when a dialog was shown.
     */
    private static boolean showLastCrash(Activity a) {
        try {
            SharedPreferences st = a.getSharedPreferences("cigram_stability", Context.MODE_PRIVATE);
            long at = st.getLong("last_crash_at", 0L);
            String trace = st.getString("last_crash_trace", "");
            SharedPreferences mine = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (at == 0L || at <= mine.getLong("crash_seen", 0L) || trace == null) return false;
            if (System.currentTimeMillis() - at > 3L * 24 * 3600 * 1000) return false;
            if (!trace.contains("CigramPlus") && !trace.contains("com.Cigram.vid.CigramPlus")) return false;
            mine.edit().putLong("crash_seen", at).apply();
            android.widget.ScrollView sv = new android.widget.ScrollView(a);
            TextView tv = new TextView(a);
            tv.setText("آخر خطأ في Plus (صوّر الشاشة وأرسلها للمطوّر):\n\n" + trace);
            tv.setTextSize(12f);
            tv.setTextIsSelectable(true);
            int p = CigramUI.dp(a, 16);
            tv.setPadding(p, p, p, p);
            sv.addView(tv);
            new android.app.AlertDialog.Builder(a).setView(sv).setPositiveButton("إغلاق", null).show();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void showNow(Activity a) {
        if (a.isFinishing() || a.isDestroyed() || !a.hasWindowFocus()) return;     // user left or another dialog is up: try next launch
        if (shownThisProcess || CigramPlus.isPlus(a)) return;
        long now = System.currentTimeMillis();
        if (!shouldShow(load(a), now)) return;
        shownThisProcess = true;
        save(a, record(load(a), now));
        show(a);
    }

    // ------------------------------------------------------------------ the card (white, rounded, black text, red numbers)

    /** Corner radius of the card. */
    static final int CORNER_DP = 20;
    private static final int INK = 0xFF111111;
    private static final int RED = 0xFFE21B14;
    private static final int YELLOW = 0xFFFFB400;
    private static final int GREY = 0xFFF3F3F3;

    /** Start/end pairs (flattened) of every run of digits, Latin or Arabic-Indic, with , or . inside a number. */
    static int[] digitRuns(String text) {
        int n = text.length();
        int[] out = new int[n + 2];
        int k = 0;
        int i = 0;
        while (i < n) {
            if (isDigit(text.charAt(i))) {
                int j = i + 1;
                while (j < n && (isDigit(text.charAt(j)) || ((text.charAt(j) == ',' || text.charAt(j) == '.') && j + 1 < n && isDigit(text.charAt(j + 1))))) j++;
                out[k++] = i;
                out[k++] = j;
                i = j;
            } else {
                i++;
            }
        }
        int[] r = new int[k];
        System.arraycopy(out, 0, r, 0, k);
        return r;
    }

    /** Same text with every run of digits in red. */
    static CharSequence redDigits(String text) {
        android.text.SpannableString sp = new android.text.SpannableString(text);
        int[] runs = digitRuns(text);
        for (int i = 0; i + 1 < runs.length; i += 2) {
            sp.setSpan(new android.text.style.ForegroundColorSpan(RED), runs[i], runs[i + 1], android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sp;
    }

    private static boolean isDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= '\u0660' && c <= '\u0669') || (c >= '\u06F0' && c <= '\u06F9');
    }

    private static TextView txt(Context c, CharSequence text, float sp, boolean bold, int gravity) {
        TextView t = CigramUI.label(c, "", sp, INK, bold);
        t.setText(text);
        t.setTextColor(INK);
        t.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        t.setGravity(gravity);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0f, 1.15f);
        return t;
    }

    private static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** A crown drawn with a path, yellow on a soft yellow disc. */
    private static final class Crown extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x33FFB400);
            c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, p);
            p.setColor(YELLOW);
            Path path = new Path();
            float l = w * 0.24f, r = w * 0.76f, top = h * 0.30f, bot = h * 0.68f;
            path.moveTo(l, bot);
            path.lineTo(l - w * 0.02f, top);
            path.lineTo(w * 0.38f, h * 0.46f);
            path.lineTo(w * 0.5f, top - h * 0.04f);
            path.lineTo(w * 0.62f, h * 0.46f);
            path.lineTo(r + w * 0.02f, top);
            path.lineTo(r, bot);
            path.close();
            c.drawPath(path, p);
            c.drawRoundRect(l, bot + h * 0.04f, r, bot + h * 0.10f, h * 0.03f, h * 0.03f, p);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** A check inside a soft disc (feature bullet). */
    private static final class Tick extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x1AE21B14);
            c.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(RED);
            p.setStrokeWidth(w * 0.11f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            Path path = new Path();
            path.moveTo(w * 0.28f, h * 0.52f);
            path.lineTo(w * 0.44f, h * 0.67f);
            path.lineTo(w * 0.73f, h * 0.35f);
            c.drawPath(path, p);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** The close icon: a dark X on a white disc with a soft shadow. */
    private static final class CloseX extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setShadowLayer(w * 0.08f, 0, w * 0.03f, 0x55000000);
            c.drawCircle(w / 2f, h / 2f, Math.min(w, h) * 0.40f, p);
            p.clearShadowLayer();
            p.setStyle(Paint.Style.STROKE);
            p.setColor(INK);
            p.setStrokeWidth(w * 0.07f);
            p.setStrokeCap(Paint.Cap.ROUND);
            float a = w * 0.36f, b = w * 0.64f;
            c.drawLine(a, a, b, b, p);
            c.drawLine(b, a, a, b, p);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static LinearLayout featureRow(Context c, String text) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View tick = new View(c);
        tick.setBackground(new Tick());
        row.addView(tick, new LinearLayout.LayoutParams(CigramUI.dp(c, 24), CigramUI.dp(c, 24)));
        TextView t = txt(c, redDigits(text), 15.5f, false, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.setMarginStart(CigramUI.dp(c, 12));
        row.addView(t, tl);
        return row;
    }

    static void show(final Activity a) {
        final boolean sales = CigramPlus.salesEnabled();
        final boolean loggedIn = CigramUserData.isLoggedIn(a);
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);
        final float radius = CigramUI.dp(a, CORNER_DP);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = CigramUI.dp(a, 22);
        card.setPadding(pad, CigramUI.dp(a, 30), pad, pad);
        card.setBackground(rounded(0xFFFFFFFF, radius));
        card.setElevation(CigramUI.dp(a, 10));
        card.setClipToOutline(true);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });

        View crown = new View(a);
        crown.setBackground(new Crown());
        card.addView(crown, new LinearLayout.LayoutParams(CigramUI.dp(a, 72), CigramUI.dp(a, 72)));

        TextView title = txt(a, "Cigram Plus", 30f, true, Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.topMargin = CigramUI.dp(a, 10);
        card.addView(title, tl);

        TextView sub = txt(a, "مشاهدة بلا حدود، بدون إعلانات", 16f, false, Gravity.CENTER);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = CigramUI.dp(a, 4);
        sl.bottomMargin = CigramUI.dp(a, 14);
        card.addView(sub, sl);

        String[] feats = {
                "بدون أي إعلانات",
                "جودة 1080p وحتى 4K للأفلام والمسلسلات",
                "صورة داخل صورة (PiP)",
                "قنوات HD و4K مثل باقة beIN SPORTS",
                "أيقونات حصرية للتطبيق وشارة توثيق"
        };
        for (String f : feats) {
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = CigramUI.dp(a, 10);
            card.addView(featureRow(a, f), rl);
        }

        if (sales) {
            boolean intro = CigramPlus.introEligible(a);
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            int bp = CigramUI.dp(a, 14);
            box.setPadding(bp, bp, bp, bp);
            box.setBackground(rounded(GREY, CigramUI.dp(a, 16)));
            if (intro) {
                box.addView(txt(a, redDigits(CigramPlus.iqd(CigramPlus.INTRO_PRICE_IQD) + " لأول " + CigramPlus.INTRO_MONTHS + " أشهر"), 22f, true, Gravity.CENTER),
                        new LinearLayout.LayoutParams(-1, -2));
                LinearLayout.LayoutParams th = new LinearLayout.LayoutParams(-1, -2);
                th.topMargin = CigramUI.dp(a, 4);
                box.addView(txt(a, redDigits("ثم " + CigramPlus.iqd(CigramPlus.STANDARD_PRICE_IQD) + " كل " + CigramPlus.STANDARD_MONTHS + " أشهر"), 14.5f, false, Gravity.CENTER), th);
            } else {
                box.addView(txt(a, redDigits(CigramPlus.iqd(CigramPlus.STANDARD_PRICE_IQD) + " كل " + CigramPlus.STANDARD_MONTHS + " أشهر"), 21f, true, Gravity.CENTER),
                        new LinearLayout.LayoutParams(-1, -2));
            }
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
            bl.topMargin = CigramUI.dp(a, 18);
            card.addView(box, bl);
        } else {
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
            hp.topMargin = CigramUI.dp(a, 16);
            card.addView(txt(a, "يُفعَّل بكود التفعيل", 15f, false, Gravity.CENTER), hp);
        }

        // yellow button, white text
        final String label = sales ? "اشترك بـ Plus" : "ضع كود التفعيل";
        TextView go = txt(a, label, 19f, true, Gravity.CENTER);
        go.setTextColor(0xFFFFFFFF);
        go.setShadowLayer(CigramUI.dp(a, 2), 0, CigramUI.dp(a, 1), 0x66000000);
        go.setMinHeight(CigramUI.dp(a, 54));
        go.setBackground(rounded(YELLOW, CigramUI.dp(a, 27)));
        go.setClickable(true);
        go.setFocusable(true);
        go.setContentDescription(label);
        final boolean in = loggedIn;
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                d.dismiss();
                if (in) CigramPlusUI.openPage(a, sales ? "pay" : "code");
                else {
                    try { a.startActivity(new Intent(a, AccountActivity.class)); } catch (Throwable ignored) { }
                }
            }
        });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
        gl.topMargin = CigramUI.dp(a, 18);
        card.addView(go, gl);

        // frame: room for the close icon that sits on the card's top-left corner
        android.widget.FrameLayout frame = new android.widget.FrameLayout(a);
        int m = CigramUI.dp(a, 14);
        frame.setPadding(m, m, m, m);
        frame.addView(card, new android.widget.FrameLayout.LayoutParams(-1, -2));
        View x = new View(a);
        x.setBackground(new CloseX());
        x.setContentDescription("إغلاق");
        x.setClickable(true);
        x.setFocusable(true);
        x.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        android.widget.FrameLayout.LayoutParams xl = new android.widget.FrameLayout.LayoutParams(CigramUI.dp(a, 44), CigramUI.dp(a, 44), Gravity.TOP | Gravity.LEFT);
        frame.addView(x, xl);

        d.setContentView(frame);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.min(CigramUI.dp(a, 350), a.getResources().getDisplayMetrics().widthPixels - CigramUI.dp(a, 16)), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.62f);
        }
        d.show();
    }
}
