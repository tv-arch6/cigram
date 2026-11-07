package com.Cigram.vid;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.WeakHashMap;

/**
 * v10.70 theme system: the app follows the phone's Light / Dark setting (or the mode the Admin app
 * forces, see {@link CigramLayoutConfig#themeMode}).
 *
 * The app was built dark-only: hundreds of screens are drawn in code with fixed colours. Instead of
 * rewriting all of them (and breaking them), the dark palette is the single source of truth and, in
 * light mode, every themed page is re-coloured live: each known dark surface colour is swapped for its
 * light counterpart, and light text that sits on a now-light surface becomes dark text. Brand colours,
 * pictures, posters, badges, buttons and anything over an image are left alone. In dark mode nothing
 * runs at all (zero cost).
 *
 * Players stay dark in both modes (as in Netflix): they are not in {@link #THEMED}.
 *
 * Smooth switches: when a page is recreated because the theme (or the orientation) changed, a picture of
 * the old page is put on top of the new one and faded out, so the change is a soft cross-fade instead of
 * a jump.
 */
public final class CigramTheme {

    private static final String TAG = "CigramTheme";
    /** Views under a view carrying this tag are never re-coloured (billboard, splash, ...). */
    public static final String SKIP_TAG = "cg_theme_skip";

    /** Pages that follow the theme. Everything else (players, ads, splash) keeps its own look. */
    private static final String[] THEMED = {
            "MainActivity", "SearchXmlActivity", "SectionActivity", "DetailsActivity", "AccountActivity",
            "SettingsActivity", "NotificationsActivity", "MyContentActivity", "ChannelsActivity",
            "CommunicationActivity", "AdsActivity", "CigramNotificationsActivity", "CigramDiscoverActivity"
    };

    // ---------------------------------------------------------------- palette (dark RGB -> light RGB)

    /** Dark surfaces of the app and the light colour each one becomes. Alpha is always kept. */
    private static final HashMap<Integer, Integer> SURFACE = new HashMap<Integer, Integer>();
    /** Light text colours (on dark surfaces) and the dark text colour each one becomes. */
    private static final HashMap<Integer, Integer> TEXT = new HashMap<Integer, Integer>();

    static {
        int page = 0xF4F5F7, deep = 0xECEEF1, card = 0xFFFFFF, raised = 0xF0F2F4, line = 0xDADFE3, skel = 0xE3E7EA;
        int[] pages = {0x00121A, 0x05141F, 0x04161D, 0x001B23, 0x0B1F2A, 0x030F16, 0x040F16, 0x0A0608, 0x071E29};
        for (int c : pages) SURFACE.put(c, page);
        SURFACE.put(0x05141F, deep);
        int[] cards = {0x0B2733, 0x0B2936, 0x0B2A38, 0x0B2330, 0x0A1F29, 0x102330, 0x101820, 0x161B22, 0x0D1117,
                0x1A1A1A, 0x181818, 0x0F2230, 0x0C2431, 0x0A202B, 0x102D3A};
        for (int c : cards) SURFACE.put(c, card);
        int[] raisedC = {0x0F2A36, 0x102F3B, 0x173542, 0x173845, 0x1C2128, 0x21262D};
        for (int c : raisedC) SURFACE.put(c, raised);
        int[] lines = {0x1F4553, 0x1F3440, 0x30363D};
        for (int c : lines) SURFACE.put(c, line);
        SURFACE.put(0x0F2A36, skel);

        int strong = 0x111418, mid = 0x3C474E, soft = 0x5F6B73;
        int[] strongT = {0xFFFFFF, 0xF1EFE8, 0xE6EEF2, 0xE8EEF1, 0xDCE6EA, 0xE6EDF3};
        for (int c : strongT) TEXT.put(c, strong);
        int[] midT = {0xC3CDD2, 0xC9D2D7, 0xB9C7CE, 0xADBBC2, 0xB5C0C6, 0xAAB4B9, 0xA9B0BC, 0x9FB4BD, 0xBDA6AC};
        for (int c : midT) TEXT.put(c, mid);
        int[] softT = {0x8FA1AA, 0x8A97A6, 0x84939B, 0x8796A0, 0x707980, 0x6F8591, 0x8B949E};
        for (int c : softT) TEXT.put(c, soft);
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> WATCHERS =
            new WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener>();

    private static Bitmap pendingSnapshot;
    private static long pendingSnapshotAt;
    private static boolean installed;

    private CigramTheme() { }

    // ---------------------------------------------------------------- mode

    /** True when the page should be light (system Light mode, or forced light by the Admin app). */
    public static boolean isLight(Context context) {
        if (context == null) return false;
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night != Configuration.UI_MODE_NIGHT_YES;
    }

    static boolean isThemed(Activity activity) {
        if (activity == null) return false;
        String n = activity.getClass().getSimpleName();
        for (String t : THEMED) if (t.equals(n)) return true;
        return false;
    }

    /** Colour of the page background for this context. */
    public static int page(Context c) { return isLight(c) ? 0xFFF4F5F7 : 0xFF00121A; }
    /** Colour of cards / sheets. */
    public static int surface(Context c) { return isLight(c) ? 0xFFFFFFFF : 0xFF0B2733; }
    /** Main text colour. */
    public static int text(Context c) { return isLight(c) ? 0xFF111418 : 0xFFFFFFFF; }
    /** Secondary text colour. */
    public static int text2(Context c) { return isLight(c) ? 0xFF3C474E : 0xFFC3CDD2; }
    /** Hairlines / strokes. */
    public static int stroke(Context c) { return isLight(c) ? 0xFFDADFE3 : 0xFF1F4553; }
    /** The brand red (same in both modes). */
    public static int brand() { return 0xFFE21B14; }

    /** Light-mode version of a dark SURFACE colour of the app (also the white veils / hairlines); same colour otherwise. */
    public static int adapt(Context c, int argb) {
        if (!isLight(c)) return argb;
        return mapSurface(argb);
    }

    /** Light-mode version of a light TEXT / icon colour of the app (white, soft greys ...); same colour otherwise. */
    public static int adaptText(Context c, int argb) {
        if (!isLight(c)) return argb;
        Integer dark = TEXT.get(argb & 0xFFFFFF);
        return dark == null ? argb : (argb & 0xFF000000) | dark;
    }

    // ---------------------------------------------------------------- install + lifecycle

    /** Once per process (CigramStage1InitProvider). Applies the Admin app's forced mode, if any. */
    public static void install(Application app) {
        if (installed || app == null) return;
        installed = true;
        applyNightMode(CigramLayoutConfig.themeMode(app));
    }

    /** "system" (default) | "light" | "dark": the Admin app may force one mode for everybody. */
    static void applyNightMode(String mode) {
        try {
            int wanted = "light".equals(mode) ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    : "dark".equals(mode) ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
            if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() != wanted) {
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(wanted);   // recreates open pages (cross-faded)
            }
        } catch (Throwable error) {
            android.util.Log.w(TAG, "night mode", error);
        }
    }

    /**
     * Called when a page is being left. If it is only being recreated (theme or orientation changed), a
     * picture of it is kept to cross-fade into the new page.
     */
    public static void onPaused(Activity activity) {
        try {
            if (activity == null || !activity.isChangingConfigurations()) return;
            View decor = activity.getWindow().getDecorView();
            int w = decor.getWidth() / 2, h = decor.getHeight() / 2;
            if (w <= 0 || h <= 0) return;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.scale(0.5f, 0.5f);
            decor.draw(canvas);
            pendingSnapshot = bmp;
            pendingSnapshotAt = SystemClock.uptimeMillis();
        } catch (Throwable error) {
            android.util.Log.w(TAG, "snapshot", error);
        }
    }

    /** Called after a page was created / brought back: window colours, live re-colouring, cross-fade. */
    public static void onResumed(final Activity activity) {
        if (activity == null) return;
        crossFadeIfPending(activity);
        if (!isThemed(activity)) return;
        styleWindow(activity);
        if (!isLight(activity)) return;   // dark mode: the app is already dark, nothing to do
        // v10.83: a tab page that comes back (pages stay alive) is already coloured; its new views are handled by
        // the watcher. Walking the whole page twice on every return was the stutter when going back to Home.
        if (WATCHERS.containsKey(activity)) return;
        recolor(activity);                // before the first frame: no dark flash
        final View decor = activity.getWindow().getDecorView();
        decor.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                ViewTreeObserver o = decor.getViewTreeObserver();
                if (o.isAlive()) o.removeOnPreDrawListener(this);
                recolor(activity);        // after layout: small icons now have their size
                return true;
            }
        });
        watch(activity);
    }

    /** Status / navigation bar colours and icon contrast for the current mode. */
    static void styleWindow(Activity activity) {
        try {
            Window window = activity.getWindow();
            if (window == null) return;
            boolean light = isLight(activity);
            int bg = page(activity);
            window.setBackgroundDrawable(new ColorDrawable(bg));
            window.setStatusBarColor(light ? bg : 0xFF00121A);
            window.setNavigationBarColor(light ? 0xFFFFFFFF : 0xFF00121A);
            View decor = window.getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (light) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                if (light) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                else flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        } catch (Throwable ignored) { }
    }

    private static void crossFadeIfPending(Activity activity) {
        final Bitmap snap = pendingSnapshot;
        if (snap == null) return;
        pendingSnapshot = null;
        if (SystemClock.uptimeMillis() - pendingSnapshotAt > 4000L || snap.isRecycled()) return;
        try {
            final ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            final ImageView cover = new ImageView(activity);
            cover.setTag(SKIP_TAG);
            cover.setScaleType(ImageView.ScaleType.FIT_XY);
            cover.setImageBitmap(snap);
            cover.setElevation(1000f);
            decor.addView(cover, new ViewGroup.LayoutParams(-1, -1));
            cover.animate().alpha(0f).setStartDelay(60L).setDuration(380L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            if (cover.getParent() == decor) decor.removeView(cover);
                        }
                    }).start();
        } catch (Throwable error) {
            android.util.Log.w(TAG, "cross-fade", error);
        }
    }

    /** Re-colours what the page adds later (rows, sheets, dialogs drawn into the page), at most ~4 times a second. */
    private static void watch(final Activity activity) {
        if (WATCHERS.containsKey(activity)) return;
        final View root = activity.getWindow().getDecorView();
        final boolean[] queued = {false};
        final Runnable pass = new Runnable() {
            @Override public void run() {
                queued[0] = false;
                if (activity.isFinishing() || activity.isDestroyed() || !isLight(activity)) return;
                recolor(activity);
            }
        };
        ViewTreeObserver.OnGlobalLayoutListener listener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (queued[0]) return;
                queued[0] = true;
                UI.postDelayed(pass, 250L);
            }
        };
        WATCHERS.put(activity, listener);
        root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
    }

    // ---------------------------------------------------------------- the re-colouring pass

    /** One pass over the whole page. Safe to repeat: already light colours are not in the tables. */
    public static void recolor(Activity activity) {
        if (activity == null || !isLight(activity) || !isThemed(activity)) return;
        SOLID.clear();
        try {
            recolorTree(activity.getWindow().getDecorView(), activity.getResources().getDisplayMetrics().density);
        } catch (Throwable error) {
            android.util.Log.w(TAG, "recolor", error);
        }
    }

    private static void recolorTree(View v, float density) {
        if (v == null || SKIP_TAG.equals(v.getTag()) || "transparent_cigram_nav".equals(v.getTag())) return;   // the bottom bar keeps its own black
        recolorBackground(v);
        if (v instanceof TextView) recolorText((TextView) v);
        else if (v instanceof ImageView) tintIcon((ImageView) v, density);
        else if (v instanceof CigramUI.Glyph) recolorGlyph(v, ((CigramUI.Glyph) v).getColor());
        else if (v instanceof AdsIcons.Icon) recolorGlyph(v, ((AdsIcons.Icon) v).getColor());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) recolorTree(g.getChildAt(i), density);
        }
    }

    private static int mapSurface(int argb) {
        int rgb = argb & 0xFFFFFF;
        Integer light = SURFACE.get(rgb);
        if (light != null) return (argb & 0xFF000000) | light;
        // translucent white veils / hairlines of the dark design -> the same veil in black
        int a = argb >>> 24;
        if (rgb == 0xFFFFFF && a > 0 && a < 0x60) return (a << 24);
        return argb;
    }

    private static void recolorBackground(View v) {
        Drawable bg = v.getBackground();
        if (bg == null) return;
        try {
            if (bg instanceof ColorDrawable) {
                int c = ((ColorDrawable) bg).getColor();
                int m = mapSurface(c);
                if (m != c) v.setBackgroundColor(m);
            } else {
                recolorDrawable(bg);
            }
        } catch (Throwable ignored) { }
    }

    /** Re-colours the plain colour layers inside any background (ripples, layers, insets, gradients). */
    private static void recolorDrawable(Drawable d) {
        if (d == null) return;
        try {
            if (d instanceof ColorDrawable) {
                ColorDrawable cd = (ColorDrawable) d;
                int c = cd.getColor();
                int m = mapSurface(c);
                if (m != c) ((ColorDrawable) cd.mutate()).setColor(m);
            } else if (d instanceof GradientDrawable) {
                GradientDrawable g = (GradientDrawable) d;
                int[] colors = Build.VERSION.SDK_INT >= 24 ? g.getColors() : null;
                if (colors != null && colors.length > 1) {
                    int[] mapped = new int[colors.length];
                    boolean changed = false;
                    for (int i = 0; i < colors.length; i++) {
                        mapped[i] = mapSurface(colors[i]);
                        changed |= mapped[i] != colors[i];
                    }
                    if (changed) ((GradientDrawable) g.mutate()).setColors(mapped);
                } else if (Build.VERSION.SDK_INT >= 24) {
                    ColorStateList csl = g.getColor();
                    if (csl != null) {
                        int c = csl.getDefaultColor();
                        int m = mapSurface(c);
                        if (m != c) ((GradientDrawable) g.mutate()).setColor(m);
                    }
                }
            } else if (d instanceof android.graphics.drawable.LayerDrawable) {   // also RippleDrawable
                android.graphics.drawable.LayerDrawable l = (android.graphics.drawable.LayerDrawable) d;
                for (int i = 0; i < l.getNumberOfLayers(); i++) recolorDrawable(l.getDrawable(i));
            } else if (d instanceof android.graphics.drawable.InsetDrawable) {
                recolorDrawable(((android.graphics.drawable.InsetDrawable) d).getDrawable());
            } else if (Build.VERSION.SDK_INT >= 29 && d instanceof android.graphics.drawable.StateListDrawable) {
                android.graphics.drawable.StateListDrawable sl = (android.graphics.drawable.StateListDrawable) d;
                for (int i = 0; i < sl.getStateCount(); i++) recolorDrawable(sl.getStateDrawable(i));
            }
        } catch (Throwable ignored) { }
    }

    /** Colour of the first solid background found from v upwards (0 when there is none). Custom drawables are sampled. */
    private static int effectiveBackground(View v) {
        View cur = v;
        for (int depth = 0; cur != null && depth < 12; depth++) {
            int c = solidColorOf(cur);
            if ((c >>> 24) >= 0xC0) return c;
            if (cur.getParent() instanceof View) cur = (View) cur.getParent();
            else break;
        }
        return 0;
    }

    private static final java.util.IdentityHashMap<View, Integer> SOLID = new java.util.IdentityHashMap<View, Integer>();

    /** The colour a view's own background really shows at its centre (plain colours are read, anything else is drawn once). */
    private static int solidColorOf(View v) {
        Integer known = SOLID.get(v);
        if (known != null) return known;
        int c = 0;
        Drawable bg = v.getBackground();
        try {
            if (bg instanceof ColorDrawable) {
                c = ((ColorDrawable) bg).getColor();
            } else if (bg instanceof GradientDrawable && Build.VERSION.SDK_INT >= 24
                    && ((GradientDrawable) bg).getColors() == null && ((GradientDrawable) bg).getColor() != null) {
                c = ((GradientDrawable) bg).getColor().getDefaultColor();
            } else if (bg != null && v.getWidth() > 0 && v.getHeight() > 0) {
                c = sample(bg, v.getWidth(), v.getHeight());
            }
        } catch (Throwable ignored) { }
        SOLID.put(v, c);
        return c;
    }

    private static int sample(Drawable d, int w, int h) {
        android.graphics.Rect old = d.copyBounds();
        try {
            Bitmap bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.scale(8f / w, 8f / h);
            d.setBounds(0, 0, w, h);
            d.draw(canvas);
            int px = bmp.getPixel(4, 4);
            bmp.recycle();
            return px;
        } finally {
            d.setBounds(old);
        }
    }

    private static boolean isLightColor(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0 > 0.72;
    }

    /** Text drawn on top of a picture (poster, backdrop, hero) keeps its colour. */
    private static boolean overImage(View v) {
        if (v instanceof TextView && ((TextView) v).getShadowRadius() > 0f) return true;
        View cur = v;
        for (int depth = 0; depth < 4 && cur.getParent() instanceof ViewGroup; depth++) {
            ViewGroup parent = (ViewGroup) cur.getParent();
            if (parent instanceof FrameLayout || parent instanceof RelativeLayout) {
                int area = Math.max(1, parent.getWidth() * parent.getHeight());
                for (int i = 0; i < parent.getChildCount(); i++) {
                    View sib = parent.getChildAt(i);
                    if (sib instanceof ImageView && sib.getVisibility() == View.VISIBLE
                            && sib.getWidth() * sib.getHeight() >= area / 2) return true;
                }
            }
            cur = parent;
        }
        return false;
    }

    private static void recolorText(TextView t) {
        int c = t.getCurrentTextColor();
        Integer dark = TEXT.get(c & 0xFFFFFF);
        if (dark == null) return;
        int under = effectiveBackground(t);
        if (under == 0 || !isLightColor(under) || overImage(t)) return;   // still on a dark / coloured / picture surface
        t.setTextColor((c & 0xFF000000) | dark);
        try {
            ColorStateList hint = t.getHintTextColors();
            if (hint != null && TEXT.containsKey(hint.getDefaultColor() & 0xFFFFFF)) t.setHintTextColor(0xFF8A949B);
        } catch (Throwable ignored) { }
    }

    /** The small drawn glyphs of the app (chevrons, row icons): white ones become dark on a light surface. */
    private static void recolorGlyph(View v, int color) {
        try {
            Integer dark = TEXT.get(color & 0xFFFFFF);
            if (dark == null) return;
            int under = effectiveBackground(v);
            if (under == 0 || !isLightColor(under) || overImage(v)) return;
            int next = (color & 0xFF000000) | dark;
            if (v instanceof CigramUI.Glyph) ((CigramUI.Glyph) v).setColor(next);
            else ((AdsIcons.Icon) v).setColor(next);
        } catch (Throwable ignored) { }
    }

    private static final java.util.WeakHashMap<Drawable, Boolean> PLAIN_WHITE = new java.util.WeakHashMap<Drawable, Boolean>();

    /** True for a one-colour light icon (white line icons). Colour pictures (logo, IMDb badge, avatars) are false. */
    private static boolean isPlainLightIcon(Drawable d) {
        Boolean known = PLAIN_WHITE.get(d);
        if (known != null) return known;
        boolean plain = false;
        try {
            Bitmap bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            android.graphics.Rect old = d.copyBounds();
            d.setBounds(0, 0, 16, 16);
            d.draw(canvas);
            d.setBounds(old);
            int solid = 0;
            boolean colourful = false;
            for (int y = 0; y < 16 && !colourful; y++) {
                for (int x = 0; x < 16; x++) {
                    int p = bmp.getPixel(x, y);
                    if ((p >>> 24) < 160) continue;
                    solid++;
                    int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                    int spread = Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
                    if (spread > 36 || (r + g + b) / 3 < 150) { colourful = true; break; }
                }
            }
            bmp.recycle();
            plain = solid > 0 && !colourful;
        } catch (Throwable ignored) { }
        PLAIN_WHITE.put(d, plain);
        return plain;
    }

    /** White line icons (bar, header, chevrons) become dark on a light surface. Pictures and photos are left alone. */
    private static void tintIcon(ImageView iv, float density) {
        try {
            if (CigramAvatarFrame.isPhoto(iv) || iv.getDrawable() == null) return;               // round photos, empty views
            if (iv.getClass().getName().contains("CircleImageView")) return;
            int w = iv.getWidth(), h = iv.getHeight();
            if (w <= 0 || h <= 0 || w > 40 * density || h > 40 * density) return;        // only small icons
            if (iv.getImageTintList() != null) return;                                    // already styled on purpose
            if (!isPlainLightIcon(iv.getDrawable())) return;                              // the app logo, the IMDb badge, posters: colours stay
            int under = effectiveBackground(iv);
            if (under == 0 || !isLightColor(under) || overImage(iv)) return;
            iv.setColorFilter(0xFF1D2328, PorterDuff.Mode.SRC_IN);
        } catch (Throwable ignored) { }
    }

    /** Text colour for a label drawn by new code (Netflix rows, chips): already right for both modes. */
    public static int label(Context c, boolean secondary) {
        return secondary ? text2(c) : text(c);
    }

    /** A colour that reads well on the given background. */
    public static int on(int background) {
        return isLightColor(background) ? 0xFF111418 : Color.WHITE;
    }
}
