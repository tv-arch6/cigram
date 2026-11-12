package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.WeakHashMap;

/**
 * v10.71 design of the guest / login / register side of the Account page.
 *
 *   - the app logo on a soft red glow (the same hero on the guest page and above the forms);
 *   - one brand look for every action: red gradient pill with a soft red shadow and a press effect;
 *   - fields with a leading icon (mail / person / lock) and a red focus ring;
 *   - the three things an account gives (continue watching, my list, new episodes) on the guest page;
 *   - everything arrives with a short staggered fade, once per visit.
 *
 * Colours come from {@link CigramTheme}, so it is right in Light and Dark mode. It only restyles views that
 * already exist (no id, click or visibility is touched), so the login / register logic is unchanged.
 */
final class CigramAccountDesign {

    static final String TAG_LOGO = "cigram_auth_logo";
    private static final String TAG_GUEST_EXTRAS = "cigram_guest_extras";
    private static final String TAG_GUEST_LINK = "cigram_guest_register_link";
    private static final String TAG_GUEST_THEME = "cigram_guest_theme";

    static final int RED = 0xFFE21B14;
    private static final WeakHashMap<Activity, Boolean> REVEALED = new WeakHashMap<Activity, Boolean>();
    private static final WeakHashMap<View, Boolean> PRESSABLE = new WeakHashMap<View, Boolean>();

    private CigramAccountDesign() { }

    // ================================================================== building blocks

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    /** The app logo, rounded, on a soft red glow. */
    static View logo(Activity a, int sizeDp) {
        int box = dp(a, sizeDp + 56);
        FrameLayout f = new FrameLayout(a);
        f.setTag(TAG_LOGO);
        f.setClipChildren(false);

        View glow = new View(a);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(box / 2f);
        g.setColors(new int[]{CigramTheme.isLight(a) ? 0x55E21B14 : 0x77E21B14, 0x00E21B14});
        glow.setBackground(g);
        f.addView(glow, new FrameLayout.LayoutParams(box, box, Gravity.CENTER));

        ImageView img = new ImageView(a);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int res = a.getResources().getIdentifier("cigram_logo", "drawable", a.getPackageName());
        if (res != 0) img.setImageResource(res);
        final float radius = dp(a, sizeDp * 0.27f);
        img.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
        img.setClipToOutline(true);
        img.setElevation(dp(a, 8));
        f.addView(img, new FrameLayout.LayoutParams(dp(a, sizeDp), dp(a, sizeDp), Gravity.CENTER));
        return f;
    }

    /** Red gradient pill with a ripple: the primary action of the app. */
    static Drawable primary(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFF3B30, 0xFFE21B14, 0xFFB3120C});
        g.setCornerRadius(dp(c, radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), g, null);
    }

    /** Styles a button as the primary action: full width, red gradient, soft red shadow, press effect. */
    static void primaryButton(Activity a, View v, int heightDp, int sideDp, int topDp) {
        if (!(v instanceof TextView)) return;
        TextView b = (TextView) v;
        if (b.getTag() == null) b.setTag(CigramTheme.SKIP_TAG);   // brand red + white text in both themes: never re-coloured
        b.setAllCaps(false);
        b.setTextSize(16f);
        b.setTextColor(0xFFFFFFFF);
        b.setTypeface(CigramUI.font(a), Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundTintList(null);
        b.setBackground(primary(a, heightDp / 2f));
        if (Build.VERSION.SDK_INT >= 21) b.setStateListAnimator(null);
        b.setElevation(dp(a, 6));
        if (Build.VERSION.SDK_INT >= 28) {
            b.setOutlineSpotShadowColor(0xFFE21B14);
            b.setOutlineAmbientShadowColor(0x66E21B14);
        }
        ViewGroup.LayoutParams lp = b.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            m.width = ViewGroup.LayoutParams.MATCH_PARENT;
            m.height = dp(a, heightDp);
            m.leftMargin = dp(a, sideDp);
            m.rightMargin = dp(a, sideDp);
            m.topMargin = dp(a, topDp);
            m.bottomMargin = dp(a, 8);
            b.setLayoutParams(m);
        }
        pressEffect(b);
    }

    /** The view shrinks a little while it is pressed. The touch is not consumed: clicks still work. */
    static void pressEffect(View v) {
        if (PRESSABLE.containsKey(v)) return;
        PRESSABLE.put(v, Boolean.TRUE);
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                int act = e.getActionMasked();
                if (act == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80L).start();
                else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(160L).start();
                }
                return false;
            }
        });
    }

    /** Field background: surface of the mode, thin stroke; the red ring when focused. */
    static Drawable fieldBackground(Context c, boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 16));
        g.setColor(CigramTheme.isLight(c) ? 0xFFFFFFFF : CigramUI.BG_SOFT);
        g.setStroke(focused ? dp(c, 1.6f) : Math.max(1, dp(c, 1)), focused ? RED : CigramTheme.stroke(c));
        return g;
    }

    /** Short staggered fade + rise, once per screen visit. */
    static void reveal(Activity a, View... views) {
        if (Boolean.TRUE.equals(REVEALED.get(a))) return;
        REVEALED.put(a, Boolean.TRUE);
        long delay = 0L;
        for (View v : views) {
            if (v == null || v.getVisibility() != View.VISIBLE) continue;
            v.setAlpha(0f);
            v.setTranslationY(dp(a, 14));
            v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(320L)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
            delay += 70L;
        }
    }

    // ================================================================== line icons for the fields

    static final int MAIL = 0, USER = 1, LOCK = 2, CHECK = 3, LIST = 4, BELL = 5;

    /** Simple line icon drawn with a stroke: crisp at every size, tinted by colour. */
    static final class Glyph extends Drawable {
        private final int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;

        Glyph(Context c, int kind, int color, int sizeDp) {
            this.kind = kind;
            this.size = dp(c, sizeDp);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(Math.max(2f, size * 0.085f));
            p.setColor(color);
        }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            if (s <= 0) return;
            c.save();
            c.translate(b.left + (b.width() - s) / 2f, b.top + (b.height() - s) / 2f);
            Path path = new Path();
            switch (kind) {
                case MAIL: {
                    RectF r = new RectF(s * .10f, s * .24f, s * .90f, s * .76f);
                    c.drawRoundRect(r, s * .10f, s * .10f, p);
                    path.moveTo(s * .14f, s * .32f);
                    path.lineTo(s * .50f, s * .58f);
                    path.lineTo(s * .86f, s * .32f);
                    c.drawPath(path, p);
                    break;
                }
                case USER: {
                    c.drawCircle(s * .50f, s * .34f, s * .17f, p);
                    c.drawArc(new RectF(s * .20f, s * .58f, s * .80f, s * 1.10f), 180f, 180f, false, p);
                    break;
                }
                case LOCK: {
                    c.drawRoundRect(new RectF(s * .22f, s * .46f, s * .78f, s * .88f), s * .10f, s * .10f, p);
                    c.drawArc(new RectF(s * .32f, s * .12f, s * .68f, s * .64f), 180f, 180f, false, p);
                    c.drawLine(s * .50f, s * .60f, s * .50f, s * .72f, p);
                    break;
                }
                case CHECK: {
                    path.moveTo(s * .22f, s * .54f);
                    path.lineTo(s * .43f, s * .74f);
                    path.lineTo(s * .80f, s * .30f);
                    c.drawPath(path, p);
                    break;
                }
                case LIST: {
                    c.drawLine(s * .22f, s * .30f, s * .78f, s * .30f, p);
                    c.drawLine(s * .22f, s * .50f, s * .78f, s * .50f, p);
                    c.drawLine(s * .22f, s * .70f, s * .56f, s * .70f, p);
                    break;
                }
                default: {   // BELL
                    path.moveTo(s * .24f, s * .68f);
                    path.cubicTo(s * .30f, s * .60f, s * .30f, s * .46f, s * .32f, s * .40f);
                    path.cubicTo(s * .36f, s * .22f, s * .64f, s * .22f, s * .68f, s * .40f);
                    path.cubicTo(s * .70f, s * .46f, s * .70f, s * .60f, s * .76f, s * .68f);
                    path.close();
                    c.drawPath(path, p);
                    c.drawLine(s * .42f, s * .80f, s * .58f, s * .80f, p);
                    break;
                }
            }
            c.restore();
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ================================================================== guest page

    private static View byId(Activity a, String name) {
        int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
        return id == 0 ? null : a.findViewById(id);
    }

    /** The page a visitor without an account sees: hero, what an account gives, one clear action. */
    static void guest(final Activity a) {
        try {
            View boxView = byId(a, "linear_guest_account");
            View welcomeView = byId(a, "textview_guest_welcome");
            View hintView = byId(a, "textview_guest_hint");
            final View open = byId(a, "button_open_login");
            if (!(boxView instanceof LinearLayout) || !(welcomeView instanceof TextView)
                    || !(hintView instanceof TextView) || open == null) return;
            LinearLayout box = (LinearLayout) boxView;
            TextView welcome = (TextView) welcomeView;
            TextView hint = (TextView) hintView;

            box.setPadding(dp(a, 24), dp(a, 10), dp(a, 24), dp(a, 16));
            welcome.setText("أهلاً بك في سيكرام");
            welcome.setTextSize(25f);
            welcome.setTextColor(CigramTheme.text(a));
            welcome.setTypeface(CigramUI.font(a), Typeface.BOLD);
            welcome.setGravity(Gravity.CENTER);
            hint.setText("حسابك يحفظ مشاهداتك ويتابع معك على كل جهاز");
            hint.setTextSize(14f);
            hint.setTextColor(CigramTheme.text2(a));
            hint.setTypeface(CigramUI.font(a));
            hint.setGravity(Gravity.CENTER);

            if (box.findViewWithTag(TAG_GUEST_EXTRAS) == null) {
                View logo = logo(a, 84);
                box.addView(logo, 0, new LinearLayout.LayoutParams(-2, -2));

                LinearLayout benefits = new LinearLayout(a);
                benefits.setTag(TAG_GUEST_EXTRAS);
                benefits.setOrientation(LinearLayout.VERTICAL);
                benefits.setPadding(dp(a, 6), dp(a, 12), dp(a, 6), dp(a, 4));
                benefits.addView(benefit(a, CHECK, "أكمل المشاهدة من حيث وقفت"), new LinearLayout.LayoutParams(-1, -2));
                benefits.addView(benefit(a, LIST, "قائمتك الخاصة للمشاهدة لاحقاً"), new LinearLayout.LayoutParams(-1, -2));
                benefits.addView(benefit(a, BELL, "تنبيه بكل حلقة جديدة تتابعها"), new LinearLayout.LayoutParams(-1, -2));
                box.addView(benefits, box.indexOfChild(open), new LinearLayout.LayoutParams(-1, -2));

                // the second, separate action: an outlined pill next to the red «تسجيل الدخول»
                TextView link = new TextView(a);
                link.setTag(TAG_GUEST_LINK);
                link.setText("ليس لديك حساب؟  أنشئ حساباً جديداً");
                link.setTextSize(14f);
                link.setTextColor(CigramPalette.of(a).light ? 0xFFC8140E : RED);
                link.setTypeface(CigramUI.font(a), Typeface.BOLD);
                link.setGravity(Gravity.CENTER);
                link.setBackground(new android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf(CigramPalette.of(a).ripple), CigramPalette.round(a, 0, RED, 26),
                        CigramPalette.round(a, 0xFFFFFFFF, 0, 26)));
                link.setContentDescription("إنشاء حساب جديد");
                link.setClickable(true);
                link.setFocusable(true);
                link.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        open.performClick();                 // the same step as «تسجيل الدخول»
                        CigramAuthPolish.showRegister(a);    // then the register tab
                    }
                });
                // the appearance choice for a visitor: a quiet link under the two actions
                TextView themeLink = new TextView(a);
                themeLink.setTag(TAG_GUEST_THEME);
                themeLink.setTextSize(12.5f);
                themeLink.setTypeface(CigramUI.font(a));
                themeLink.setGravity(Gravity.CENTER);
                themeLink.setPadding(dp(a, 12), dp(a, 14), dp(a, 12), dp(a, 6));
                themeLink.setClickable(true);
                themeLink.setFocusable(true);
                themeLink.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { CigramAccountDash.showThemeDialog(a); }
                });
                LinearLayout.LayoutParams linkLp = new LinearLayout.LayoutParams(-1, dp(a, 52));
                linkLp.topMargin = dp(a, 12);
                linkLp.leftMargin = dp(a, 4);
                linkLp.rightMargin = dp(a, 4);
                // the fastest way in, for a new viewer and a returning one: the phone's Google account, one tap
                if (CigramGoogleAuth.enabled()) {
                    LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, dp(a, 54));
                    gp.topMargin = dp(a, 12);
                    gp.leftMargin = dp(a, 4);
                    gp.rightMargin = dp(a, 4);
                    box.addView(CigramGoogleAuth.button(a), gp);
                }
                // the account that signed out last on this phone: its e-mail is already written in the form
                final String lastMail = CigramLogout.last(a, "email");
                if (lastMail.length() > 0) {
                    View field = byId(a, "edittext_login_email");
                    if (field instanceof TextView && ((TextView) field).getText().length() == 0) ((TextView) field).setText(lastMail);
                    String lastName = CigramLogout.last(a, "name");
                    hint.setText(lastName.length() > 0 ? "أهلاً بعودتك يا " + lastName + " · بياناتك محفوظة" : "أهلاً بعودتك · بياناتك محفوظة");
                }
                box.addView(link, linkLp);
                box.addView(themeLink, new LinearLayout.LayoutParams(-1, -2));
                reveal(a, logo, welcome, hint, benefits, open, link);
            }
            View themeView = box.findViewWithTag(TAG_GUEST_THEME);
            if (themeView instanceof TextView) {
                ((TextView) themeView).setText("المظهر: " + CigramAccountDash.themeLabel(a) + "  ‹");
                ((TextView) themeView).setTextColor(CigramPalette.of(a).text3);
            }
            primaryButton(a, open, 54, 4, 20);
            ((TextView) open).setText("تسجيل الدخول");
        } catch (Throwable error) {
            android.util.Log.w("CigramAccountDesign", "guest failed", error);
        }
    }

    private static View benefit(Activity a, int glyph, String text) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        row.setPadding(0, dp(a, 6), 0, dp(a, 6));

        TextView label = new TextView(a);
        label.setText(text);
        label.setTextSize(14.5f);
        label.setTextColor(CigramTheme.text(a));
        label.setTypeface(CigramUI.font(a));
        label.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

        ImageView tile = new ImageView(a);
        tile.setScaleType(ImageView.ScaleType.CENTER);
        tile.setImageDrawable(new Glyph(a, glyph, RED, 18));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(a, 11));
        bg.setColor(CigramTheme.isLight(a) ? 0x1AE21B14 : 0x26E21B14);
        tile.setBackground(bg);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(a, 38), dp(a, 38));
        tl.leftMargin = dp(a, 12);
        row.addView(tile, tl);
        return row;
    }
}
