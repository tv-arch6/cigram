package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.Color;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/**
 * Views of Cigram Plus: the two comparison cards (dark = عادي, gold = Plus), the payment-method chips, the gift
 * celebration. Everything is plain views + ONE gradient drawable per card + hand-drawn vector drawables:
 * no bitmaps, no blur, no XML inflation, no animation that runs forever (the only motion is a 200 ms entrance and a
 * 1.7 s confetti that removes itself; both are skipped when the system animation scale is 0).
 */
final class CigramPlusUI {

    static final String TAG_BOX = "cigram_plus_box";

    static final int GOLD_LIGHT = 0xFFFFE7A0;
    static final int GOLD = 0xFFF2B63D;
    static final int GOLD_DEEP = 0xFFB7770C;
    static final int GOLD_INK = 0xFF2B1B00;
    static final int WA_YELLOW = 0xFFFFC107;
    private static final int DARK_TOP = 0xFF17404B;
    private static final int DARK_BOT = 0xFF0A222C;
    private static final int DARK_STROKE = 0xFF2A5563;
    static final int PAGE_BG = 0xFF071B24;
    static final int SURFACE = 0xFF0E2A35;

    private CigramPlusUI() { }

    // ================================================================== drawables

    static GradientDrawable darkBg(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{DARK_TOP, DARK_BOT});
        g.setCornerRadius(CigramUI.dp(c, radiusDp));
        g.setStroke(CigramUI.dp(c, 1), DARK_STROKE);
        return g;
    }

    static Drawable goldBg(Context c, float radiusDp) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{GOLD_LIGHT, GOLD, GOLD_DEEP});
        base.setCornerRadius(CigramUI.dp(c, radiusDp));
        GradientDrawable gloss = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x55FFFFFF, 0x00FFFFFF});
        gloss.setCornerRadius(CigramUI.dp(c, radiusDp));
        return new LayerDrawable(new Drawable[]{base, gloss});
    }

    static GradientDrawable pill(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(CigramUI.dp(c, radiusDp));
        return g;
    }

    /** Simple crown, drawn from a Path (vector, resolution independent). */
    static final class CrownDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        CrownDrawable(int color) { p.setColor(color); }
        @Override public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height();
            float l = getBounds().left, t = getBounds().top;
            path.reset();
            path.moveTo(l + w * 0.06f, t + h * 0.82f);
            path.lineTo(l + w * 0.02f, t + h * 0.22f);
            path.lineTo(l + w * 0.28f, t + h * 0.50f);
            path.lineTo(l + w * 0.50f, t + h * 0.10f);
            path.lineTo(l + w * 0.72f, t + h * 0.50f);
            path.lineTo(l + w * 0.98f, t + h * 0.22f);
            path.lineTo(l + w * 0.94f, t + h * 0.82f);
            path.close();
            c.drawPath(path, p);
            c.drawRect(l + w * 0.06f, t + h * 0.88f, l + w * 0.94f, t + h * 0.98f, p);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** The Mastercard mark: two overlapping circles (red #EB001B, orange #F79E1B, overlap #FF5F00). */
    static final class MastercardDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path left = new Path(), right = new Path(), both = new Path();
        @Override public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height();
            float r = h / 2f;
            float cx = getBounds().left + w / 2f, cy = getBounds().top + h / 2f;
            float off = r * 0.62f;
            left.reset(); right.reset(); both.reset();
            left.addCircle(cx - off, cy, r, Path.Direction.CW);
            right.addCircle(cx + off, cy, r, Path.Direction.CW);
            both.set(left);
            both.op(right, Path.Op.INTERSECT);
            p.setColor(0xFFEB001B);
            c.drawPath(left, p);
            p.setColor(0xFFF79E1B);
            c.drawPath(right, p);
            p.setColor(0xFFFF5F00);
            c.drawPath(both, p);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    interface Click { void onClick(); }

    // ================================================================== the small box on the account page

    /**
     * The Plus entry of the account page (logged-in accounts only): a gold-framed card. Without Plus it invites to discover the
     * benefits (or to enter a code in the Play build); with Plus it is the member card: dates, days left, progress, and a way into
     * the members' area.
     */
    static View buildBox(final Activity a) {
        final boolean plus = CigramPlus.isPlus(a);
        final long ended = CigramPlus.expiredAt(a);
        final boolean sales = CigramPlus.salesEnabled();

        FrameLayout root = new FrameLayout(a);
        root.setTag(TAG_BOX);
        root.setBackground(CigramPlusArt.hero(a, 24));
        root.setElevation(CigramUI.dp(a, 3));
        View glow = new View(a);
        glow.setBackground(CigramPlusArt.glow(a, 24));
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int pad = CigramUI.dp(a, 18);
        col.setPadding(pad, pad, pad, pad);

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView crown = new ImageView(a);
        crown.setImageDrawable(new CrownDrawable(GOLD));
        head.addView(crown, new LinearLayout.LayoutParams(CigramUI.dp(a, 28), CigramUI.dp(a, 23)));
        TextView t = CigramUI.label(a, "Cigram Plus", 18f, GOLD, true);
        t.setPadding(CigramUI.dp(a, 10), 0, 0, 0);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (plus) {
            TextView ok = CigramUI.label(a, "مفعّل ✓", 12f, 0xFFFFFFFF, true);
            ok.setGravity(Gravity.CENTER);
            ok.setPadding(CigramUI.dp(a, 12), CigramUI.dp(a, 4), CigramUI.dp(a, 12), CigramUI.dp(a, 4));
            ok.setBackground(CigramPlusArt.ring(a, 12, 0xFF22B573, 0, 0));
            head.addView(ok);
        }
        col.addView(head);

        if (plus) {
            TextView period = CigramUI.label(a, CigramPlus.periodText(a), 14f, 0xFFFFFFFF, true);
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, -2);
            pl.topMargin = CigramUI.dp(a, 12);
            col.addView(period, pl);
            col.addView(CigramUI.label(a, "متبقٍّ " + CigramPlus.daysLeft(a) + " يوم", 12f, 0xFF9FD8BF, false));
            long from = CigramPlus.activatedAt(a), to = CigramPlus.until(a);
            if (from > 0L && to > from) {
                float f = Math.max(0f, Math.min(1f, (System.currentTimeMillis() - from) / (float) (to - from)));
                int w = a.getResources().getDisplayMetrics().widthPixels - CigramUI.dp(a, 28 + 36);
                FrameLayout track = new FrameLayout(a);
                track.setBackground(CigramPlusArt.ring(a, 4, 0x33FFFFFF, 0, 0));
                View fill = new View(a);
                fill.setBackground(CigramPlusArt.ring(a, 4, GOLD, 0, 0));
                track.addView(fill, new FrameLayout.LayoutParams(Math.max(CigramUI.dp(a, 8), (int) (w * f)), CigramUI.dp(a, 6), Gravity.RIGHT));
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, CigramUI.dp(a, 6));
                tp.topMargin = CigramUI.dp(a, 10);
                col.addView(track, tp);
            }
        } else {
            String line = ended > 0L ? "انتهى اشتراكك في " + CigramPlus.formatDate(ended) + " · جدّده الآن"
                    : "بدون إعلانات · جودة حتى 4K · صورة داخل صورة · قنوات HD";
            TextView sub = CigramUI.label(a, line, 12.5f, ended > 0L ? 0xFFFF9A9A : 0xCCFFFFFF, false);
            sub.setLineSpacing(0f, 1.2f);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
            sl.topMargin = CigramUI.dp(a, 10);
            col.addView(sub, sl);
        }

        TextView go = CigramUI.label(a, plus ? "أقسام Plus" : (sales ? "اكتشف المزايا" : "ضع كود التفعيل"), 15f, plus ? 0xFFFFFFFF : GOLD_INK, true);
        go.setGravity(Gravity.CENTER);
        go.setMinHeight(CigramUI.dp(a, 48));
        go.setBackground(plus ? CigramPlusArt.ring(a, 16, 0x26F2B63D, 0x88F2B63D, 1f) : goldBg(a, 16));
        go.setClickable(true);
        go.setFocusable(true);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openPage(a, !plus && sales ? "pay" : "code"); }
        });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
        gl.topMargin = CigramUI.dp(a, 14);
        col.addView(go, gl);

        root.addView(col, new FrameLayout.LayoutParams(-1, -2));
        root.setClickable(false);
        return root;
    }

    /** Re-draws the box in place (after the server answered with a new plan). */
    static void refreshBox(Activity a, LinearLayout host) {
        if (host == null) return;
        View old = host.findViewWithTag(TAG_BOX);
        if (old == null) return;
        int at = host.indexOfChild(old);
        ViewGroup.LayoutParams lp = old.getLayoutParams();
        host.removeViewAt(at);
        host.addView(buildBox(a), at, lp);
    }

    /** True when the account has Plus; otherwise shows the small "this is a Plus service" card and returns false. */
    static boolean requirePlus(Activity a, String feature) {
        if (CigramPlus.isPlus(a)) return true;
        upsell(a, feature);
        return false;
    }

    static void upsell(final Activity a, String feature) {
        if (a == null || a.isFinishing()) return;
        try {
            final android.app.Dialog d = new android.app.Dialog(a);
            d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            boolean loggedIn = CigramUserData.isLoggedIn(a);
            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            int pad = CigramUI.dp(a, 20);
            card.setPadding(pad, pad, pad, CigramUI.dp(a, 14));
            card.setBackground(goldBg(a, 22));

            TextView title = CigramUI.label(a, "ميزة Plus", 20f, GOLD_INK, true);
            card.addView(title);
            TextView body = CigramUI.label(a, loggedIn
                    ? feature + " ضمن اشتراك Cigram Plus."
                    : feature + " ضمن اشتراك Cigram Plus. سجّل دخولك من صفحة الحساب أولاً ثم فعّل الاشتراك.", 14f, 0xCC2B1B00, false);
            body.setLineSpacing(0f, 1.2f);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
            bl.topMargin = CigramUI.dp(a, 6);
            card.addView(body, bl);

            if (loggedIn) {
                final boolean sales = CigramPlus.salesEnabled();
                TextView go = CigramUI.label(a, sales ? "عرض Plus" : "ضع كود التفعيل", 15f, GOLD, true);
                go.setGravity(Gravity.CENTER);
                go.setMinHeight(CigramUI.dp(a, 48));
                go.setBackground(pill(a, GOLD_INK, 24));
                go.setClickable(true);
                go.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { d.dismiss(); openPage(a, sales ? "pay" : "code"); }
                });
                LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, -2);
                gl.topMargin = CigramUI.dp(a, 16);
                card.addView(go, gl);
            }
            TextView later = CigramUI.label(a, loggedIn ? "لاحقاً" : "حسناً", 14f, 0xCC2B1B00, true);
            later.setGravity(Gravity.CENTER);
            later.setMinHeight(CigramUI.dp(a, 44));
            later.setClickable(true);
            later.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { d.dismiss(); }
            });
            card.addView(later, new LinearLayout.LayoutParams(-1, -2));

            d.setContentView(card);
            if (d.getWindow() != null) {
                d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
                d.getWindow().setLayout(CigramUI.dp(a, 300), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            d.show();
        } catch (Throwable t) {
            android.widget.Toast.makeText(a, feature + " ضمن اشتراك Plus", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    static void openPage(Activity a, String focus) {
        try {
            Class<?> page = "pay".equals(focus) ? CigramPlusActivity.class : CigramPlusCodeActivity.class;     // pay = the offer page, code = enter the code + members' area
            a.startActivity(new Intent(a, page).putExtra(CigramPlusActivity.EXTRA_FOCUS, focus));
        } catch (Throwable t) {
            android.util.Log.w("CigramPlus", "open page failed", t);
        }
    }

    // ================================================================== payment methods

    interface MethodListener { void onSelect(int index); }

    /** Three selectable chips (Mastercard / ZainCash / Asiacell). selected[0] = -1 when nothing is chosen. */
    static LinearLayout paymentChips(final Activity a, final int[] selected, final MethodListener l) {
        final LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setBaselineAligned(false);
        for (int i = 0; i < CigramPlus.METHOD_KEYS.length; i++) {
            final int index = i;
            LinearLayout chip = new LinearLayout(a);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(CigramUI.dp(a, 6), CigramUI.dp(a, 10), CigramUI.dp(a, 6), CigramUI.dp(a, 8));
            chip.setMinimumHeight(CigramUI.dp(a, 76));
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setContentDescription(CigramPlus.METHOD_LABELS[i]);
            chip.addView(methodIcon(a, i), new LinearLayout.LayoutParams(CigramUI.dp(a, 46), CigramUI.dp(a, 28)));
            TextView name = CigramUI.label(a, CigramPlus.METHOD_LABELS[i], 11.5f, 0xFFFFFFFF, true);
            name.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nl.topMargin = CigramUI.dp(a, 6);
            chip.addView(name, nl);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selected[0] = index;
                    paintChips(a, row, selected[0]);
                    if (l != null) l.onSelect(index);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = CigramUI.dp(a, 4);
            lp.rightMargin = CigramUI.dp(a, 4);
            row.addView(chip, lp);
        }
        paintChips(a, row, selected[0]);
        return row;
    }

    private static void paintChips(Context c, LinearLayout row, int selected) {
        for (int i = 0; i < row.getChildCount(); i++) {
            boolean on = i == selected;
            GradientDrawable g = new GradientDrawable();
            g.setColor(on ? 0xFF16373F : 0xFF0B2530);
            g.setCornerRadius(CigramUI.dp(c, 14));
            g.setStroke(CigramUI.dp(c, on ? 2 : 1), on ? GOLD : DARK_STROKE);
            row.getChildAt(i).setBackground(g);
            row.getChildAt(i).setSelected(on);
        }
    }

    /**
     * Mastercard is drawn here (its mark is two plain circles). ZainCash and Asiacell: put the official logo files at
     * res/drawable/pay_zaincash.png and res/drawable/pay_asiacell.png and they are used automatically; until then a
     * neutral name badge is shown (I do not redraw other companies' logos from memory).
     */
    private static View methodIcon(Context c, int index) {
        if (index == 0) {
            ImageView v = new ImageView(c);
            v.setImageDrawable(new MastercardDrawable());
            return v;
        }
        String res = index == 1 ? "pay_zaincash" : "pay_asiacell";
        int id = c.getResources().getIdentifier(res, "drawable", c.getPackageName());
        if (id != 0) {
            ImageView v = new ImageView(c);
            v.setImageResource(id);
            v.setScaleType(ImageView.ScaleType.FIT_CENTER);
            return v;
        }
        TextView badge = new TextView(c);
        badge.setText(index == 1 ? "ZainCash" : "Asiacell");
        badge.setTextColor(0xFFFFFFFF);
        badge.setTextSize(10.5f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(pill(c, 0xFF2A5563, 8));
        return badge;
    }

    // ================================================================== gift / celebration after a code is accepted

    /** Pure: "3 أشهر" / "شهر واحد" / "N أيام" for the card (months come from the server answer). */
    static String durationText(int months) {
        if (months <= 0) return "";
        if (months == 1) return "شهر واحد";
        if (months == 2) return "شهران";
        return months + (months <= 10 ? " أشهر" : " شهراً");
    }

    /**
     * Activation success: a dark card with a glowing gold crown and confetti, the package, the period "من - إلى", the perks that are
     * active NOW, and one big button that goes straight to watching. Safe to call from any state; removes itself on close.
     */
    static void showGift(final Activity a, long until) {
        final ViewGroup root = (ViewGroup) a.findViewById(android.R.id.content);
        if (root == null) return;
        final boolean animate = animationsOn(a);

        final FrameLayout overlay = new FrameLayout(a);
        overlay.setBackgroundColor(0xE6000000);
        overlay.setClickable(true);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int pad = CigramUI.dp(a, 22);
        card.setPadding(pad, CigramUI.dp(a, 26), pad, pad);
        card.setBackground(CigramPlusArt.hero(a, 28));
        card.setElevation(CigramUI.dp(a, 12));

        FrameLayout crownWrap = new FrameLayout(a);
        View halo = new View(a);
        GradientDrawable hg = new GradientDrawable();
        hg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        hg.setGradientRadius(CigramUI.dp(a, 70));
        hg.setColors(new int[]{0x88F2B63D, 0x33F2B63D, 0x00F2B63D});
        halo.setBackground(hg);
        crownWrap.addView(halo, new FrameLayout.LayoutParams(CigramUI.dp(a, 140), CigramUI.dp(a, 140), Gravity.CENTER));
        ImageView crown = new ImageView(a);
        crown.setImageDrawable(new CrownDrawable(GOLD));
        crownWrap.addView(crown, new FrameLayout.LayoutParams(CigramUI.dp(a, 64), CigramUI.dp(a, 52), Gravity.CENTER));
        card.addView(crownWrap, new LinearLayout.LayoutParams(CigramUI.dp(a, 140), CigramUI.dp(a, 100)));

        TextView title = CigramUI.label(a, "تم تفعيل Cigram Plus بنجاح", 21f, 0xFFFFFFFF, true);
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setBackground(CigramPlusArt.ring(a, 18, 0x14FFFFFF, 0, 0));
        info.setPadding(CigramUI.dp(a, 14), CigramUI.dp(a, 12), CigramUI.dp(a, 14), CigramUI.dp(a, 12));
        info.addView(infoRow(a, "الباقة", "Cigram Plus"));
        info.addView(infoRow(a, "المدة", durationText(CigramPlus.lastMonths)));
        info.addView(infoRow(a, "الفترة", CigramPlus.periodText(a)));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, -2);
        il.topMargin = CigramUI.dp(a, 16);
        card.addView(info, il);

        TextView perksTitle = CigramUI.label(a, "المميزات الفعّالة الآن", 13f, GOLD_LIGHT, true);
        LinearLayout.LayoutParams ptl = new LinearLayout.LayoutParams(-1, -2);
        ptl.topMargin = CigramUI.dp(a, 14);
        card.addView(perksTitle, ptl);
        LinearLayout chips = new LinearLayout(a);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        String[] perks = {"بدون إعلانات", "جودة 4K", "PiP", "قنوات HD"};
        for (String p : perks) {
            TextView c = CigramUI.label(a, p, 11.5f, GOLD_LIGHT, true);
            c.setGravity(Gravity.CENTER);
            c.setPadding(CigramUI.dp(a, 10), CigramUI.dp(a, 5), CigramUI.dp(a, 10), CigramUI.dp(a, 5));
            c.setBackground(CigramPlusArt.ring(a, 14, 0x22F2B63D, 0x66F2B63D, 1f));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.setMarginEnd(CigramUI.dp(a, 6));
            chips.addView(c, cl);
        }
        LinearLayout.LayoutParams chl = new LinearLayout.LayoutParams(-1, -2);
        chl.topMargin = CigramUI.dp(a, 8);
        card.addView(chips, chl);

        TextView ok = CigramUI.label(a, "ابدأ المشاهدة الآن", 17f, GOLD_INK, true);
        ok.setGravity(Gravity.CENTER);
        ok.setMinHeight(CigramUI.dp(a, 56));
        ok.setBackground(goldBg(a, 28));
        ok.setElevation(CigramUI.dp(a, 4));
        ok.setClickable(true);
        ok.setFocusable(true);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(-1, -2);
        ol.topMargin = CigramUI.dp(a, 20);
        card.addView(ok, ol);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(Math.min(CigramUI.dp(a, 340), a.getResources().getDisplayMetrics().widthPixels - CigramUI.dp(a, 24)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        overlay.addView(card, cardLp);

        final ConfettiView confetti = animate ? new ConfettiView(a) : null;
        if (confetti != null) overlay.addView(confetti, new FrameLayout.LayoutParams(-1, -1));

        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (confetti != null) confetti.stop();
                root.removeView(overlay);
                try {                                                     // straight to watching: back to Home
                    Intent home = new Intent(a, Class.forName(a.getPackageName() + ".MainActivity"));
                    home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    a.startActivity(home);
                } catch (Throwable ignored) { }
            }
        });
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        if (animate) {
            card.setAlpha(0f);
            card.setScaleX(0.9f);
            card.setScaleY(0.9f);
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240L).start();
            confetti.start();
        }
    }

    private static View infoRow(Activity a, String name, String value) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        TextView n = CigramUI.label(a, name, 12.5f, 0xFF8FA1AA, false);
        r.addView(n, new LinearLayout.LayoutParams(CigramUI.dp(a, 60), -2));
        TextView v = CigramUI.label(a, value, 13.5f, 0xFFFFFFFF, true);
        r.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
        r.setPadding(0, CigramUI.dp(a, 3), 0, CigramUI.dp(a, 3));
        return r;
    }

    private static boolean animationsOn(Context c) {
        try {
            return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 36 pieces falling for 1.7 s. onDraw allocates nothing; after the end it stops invalidating and hides itself. */
    static final class ConfettiView extends View {
        private static final int N = 36;
        private static final long DURATION = 1700L;
        private final float[] x = new float[N], y = new float[N], vx = new float[N], vy = new float[N], rot = new float[N], size = new float[N];
        private final int[] color = new int[N];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private long startedAt;
        private boolean running;

        ConfettiView(Context c) {
            super(c);
            setClickable(false);
            Random r = new Random();
            int[] palette = {GOLD, GOLD_LIGHT, 0xFFFFFFFF, 0xFF26A5B5, 0xFFE21B14, 0xFF3DDC97};
            float d = c.getResources().getDisplayMetrics().density;
            for (int i = 0; i < N; i++) {
                x[i] = r.nextFloat();
                y[i] = -0.1f - r.nextFloat() * 0.3f;
                vx[i] = (r.nextFloat() - 0.5f) * 0.25f;
                vy[i] = 0.55f + r.nextFloat() * 0.6f;
                rot[i] = r.nextFloat() * 360f;
                size[i] = (4f + r.nextFloat() * 5f) * d;
                color[i] = palette[r.nextInt(palette.length)];
            }
        }

        void start() { startedAt = System.currentTimeMillis(); running = true; postInvalidateOnAnimation(); }

        void stop() { running = false; setVisibility(GONE); }

        @Override protected void onDraw(Canvas c) {
            if (!running) return;
            float t = (System.currentTimeMillis() - startedAt) / 1000f;
            if (t * 1000f > DURATION) { stop(); return; }
            int w = getWidth(), h = getHeight();
            for (int i = 0; i < N; i++) {
                float px = (x[i] + vx[i] * t) * w;
                float py = (y[i] + vy[i] * t + 0.35f * t * t) * h;
                if (py > h) continue;
                c.save();
                c.rotate(rot[i] + t * 220f, px, py);
                paint.setColor(color[i]);
                paint.setAlpha((int) (255 * Math.max(0f, 1f - (t * 1000f / DURATION) * 0.6f)));
                c.drawRect(px - size[i] / 2f, py - size[i] / 3f, px + size[i] / 2f, py + size[i] / 3f, paint);
                c.restore();
            }
            postInvalidateOnAnimation();
        }
    }
}
