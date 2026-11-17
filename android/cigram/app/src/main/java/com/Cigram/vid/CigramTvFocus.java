package com.Cigram.vid;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.AbsListView;
import android.widget.AbsSeekBar;
import android.widget.AdapterView;
import android.widget.EditText;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * v10.66 — the TV remote inside the app (TV only; a phone never reaches this class).
 *
 *  - Every view that reacts to a click becomes reachable with the arrows (setFocusable). Lists
 *    (ListView) keep their own row selection; their rows are not touched.
 *  - The view under the remote gets a white outline with a soft glow, drawn on the window's
 *    overlay (no view is restyled, so nothing in the page design changes). The outline glides
 *    from one card to the next and follows scrolling and moving cards.
 *  - A page that loses its focus (a view hidden, a page rebuilt) gives it back: to the page's own
 *    default (the search bar on Search) or to the side rail.
 *  - MENU jumps to the side rail. A page can add its own keys (the players do: play/pause, seek,
 *    next / previous).
 *
 * Nothing here can break a page: every entry point catches everything, and listeners only read
 * the view tree except for setFocusable on clickable views.
 */
final class CigramTvFocus {

    /** A page's own remote keys (players). Return true when the key was used. */
    interface KeyHandler {
        boolean onKey(KeyEvent event);
    }

    /** Lets a page hide the outline (a player whose controls are hidden). */
    interface RingFilter {
        boolean ringVisible(View target);
    }

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final long GLIDE_MS = 140L;
    private static final long GUARD_MS = 450L;
    private static final long SCAN_GAP_MS = 250L;
    /** A clickable view bigger than this share of the window is a backdrop, not a button. */
    private static final float HUGE = 0.72f;
    private static final int LIFT_KEY = 0x7e0c0001;   // setTag key (must be >= 0x01000000 and not an app resource id)

    private static final WeakHashMap<Activity, WeakReference<Host>> ACTIVITIES =
            new WeakHashMap<Activity, WeakReference<Host>>();
    private static final WeakHashMap<View, WeakReference<Host>> WINDOWS =
            new WeakHashMap<View, WeakReference<Host>>();

    /**
     * OK on a list row: lists whose rows handle their own clicks (the channels page) have no item
     * listener, so the list's own OK did nothing. The row's click runs instead (TV only).
     */
    private static final AdapterView.OnItemClickListener ROW_CLICK = new AdapterView.OnItemClickListener() {
        @Override public void onItemClick(AdapterView<?> parent, View row, int position, long id) {
            try {
                if (row != null && row.isEnabled() && row.hasOnClickListeners()) row.performClick();
            } catch (Throwable ignored) { }
        }
    };

    /** A long OK on a list row: the row's own long press (e.g. add a channel to the favourites). */
    private static final AdapterView.OnItemLongClickListener ROW_LONG = new AdapterView.OnItemLongClickListener() {
        @Override public boolean onItemLongClick(AdapterView<?> parent, View row, int position, long id) {
            try {
                return row != null && row.isLongClickable() && row.performLongClick();
            } catch (Throwable ignored) {
                return false;
            }
        }
    };

    private CigramTvFocus() { }

    // ================================================================== API

    /** Arms the remote on this page (idempotent). */
    static void attach(Activity a) {
        try {
            if (a == null || a.isFinishing() || !CigramTvMode.isTv(a)) return;
            if (host(a) != null) {
                requestScan(a);
                return;
            }
            Window w = a.getWindow();
            View decor = w == null ? null : w.getDecorView();
            if (!(decor instanceof ViewGroup)) return;
            Host h = new Host((ViewGroup) decor, a);
            synchronized (ACTIVITIES) { ACTIVITIES.put(a, new WeakReference<Host>(h)); }
            h.start();
            installKeys(a, h);
        } catch (Throwable ignored) { }
    }

    /** Dialogs and pop-ups: call right after show() with any view of that window. */
    static void attachWindowOf(final View anyView) {
        try {
            if (anyView == null || !CigramTvMode.isTv(anyView.getContext())) return;
            anyView.post(new Runnable() {
                @Override public void run() {
                    try {
                        View root = anyView.getRootView();
                        if (!(root instanceof ViewGroup)) return;
                        synchronized (WINDOWS) {
                            WeakReference<Host> ref = WINDOWS.get(root);
                            if (ref != null && ref.get() != null) {
                                ref.get().queueScan(true);
                                return;
                            }
                        }
                        Host h = new Host((ViewGroup) root, null);
                        synchronized (WINDOWS) { WINDOWS.put(root, new WeakReference<Host>(h)); }
                        h.start();
                        h.focusFirstAfterScan = true;
                        h.queueScan(true);
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable ignored) { }
    }

    /** Same for a Dialog (after show()). */
    static void attachDialog(android.app.Dialog d) {
        try {
            if (d != null && d.getWindow() != null) attachWindowOf(d.getWindow().getDecorView());
        } catch (Throwable ignored) { }
    }

    static void resumed(Activity a) {
        Host h = host(a);
        if (h == null) return;
        h.resumed = true;
        UI.removeCallbacks(h.guard);
        UI.postDelayed(h.guard, 120L);
        h.queueScan(false);
    }

    static void paused(Activity a) {
        Host h = host(a);
        if (h == null) return;
        h.resumed = false;
        UI.removeCallbacks(h.guard);
    }

    static void requestScan(Activity a) {
        Host h = host(a);
        if (h != null) h.queueScan(false);
    }

    static void setKeyHandler(Activity a, KeyHandler handler) {
        attach(a);
        Host h = host(a);
        if (h != null) h.keys = handler;
    }

    static void setRingFilter(Activity a, RingFilter filter) {
        attach(a);
        Host h = host(a);
        if (h != null) {
            h.filter = filter;
            h.root.invalidate();
        }
    }

    /**
     * Players: the focus guard only gives the focus back to the page's default view (play / pause)
     * when it is on screen; with the controls hidden nothing else is picked for it.
     */
    static void setPlayerMode(Activity a) {
        attach(a);
        Host h = host(a);
        if (h != null) h.playerMode = true;
    }

    /** The view that takes the focus when the page has none (else: the side rail). */
    static void setDefaultFocus(Activity a, View v) {
        attach(a);
        Host h = host(a);
        if (h != null) h.defaultFocus = v == null ? null : new WeakReference<View>(v);
    }

    /** Gives the page its default focus now (used when a page is shown again). */
    static boolean focusDefault(Activity a) {
        Host h = host(a);
        return h != null && h.focusDefault();
    }

    /** OK on a remote (centre of the arrows), Enter on a keyboard, A on a game pad. */
    static boolean isConfirm(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    private static Host host(Activity a) {
        if (a == null) return null;
        synchronized (ACTIVITIES) {
            WeakReference<Host> ref = ACTIVITIES.get(a);
            return ref == null ? null : ref.get();
        }
    }

    // ================================================================== keys

    private static void installKeys(final Activity a, final Host h) {
        try {
            final Window window = a.getWindow();
            final Window.Callback original = window == null ? null : window.getCallback();
            if (original == null) return;
            Window.Callback proxy = (Window.Callback) java.lang.reflect.Proxy.newProxyInstance(
                    Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class},
                    new java.lang.reflect.InvocationHandler() {
                        @Override public Object invoke(Object p, java.lang.reflect.Method m, Object[] args)
                                throws Throwable {
                            if ("dispatchKeyEvent".equals(m.getName()) && args != null && args.length == 1
                                    && args[0] instanceof KeyEvent) {
                                boolean used = false;
                                try { used = h.onKey((KeyEvent) args[0]); } catch (Throwable ignored) { }
                                if (used) return Boolean.TRUE;
                            }
                            try {
                                return m.invoke(original, args);
                            } catch (java.lang.reflect.InvocationTargetException wrapped) {
                                throw wrapped.getCause() == null ? wrapped : wrapped.getCause();
                            }
                        }
                    });
            window.setCallback(proxy);
        } catch (Throwable ignored) { }
    }

    // ================================================================== one window

    private static final class Host implements ViewTreeObserver.OnGlobalFocusChangeListener,
            ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnGlobalLayoutListener,
            ViewTreeObserver.OnTouchModeChangeListener {

        final ViewGroup root;
        final WeakReference<Activity> activity;
        final Ring ring;
        final float density;
        final RectF shown = new RectF();
        final RectF from = new RectF();
        final RectF to = new RectF();
        final int[] loc = new int[2];
        final int[] rootLoc = new int[2];
        WeakReference<View> target;
        WeakReference<View> defaultFocus;
        long glideStart;
        boolean hasShown;
        boolean scanQueued;
        long lastScanAt;
        boolean resumed;
        boolean focusFirstAfterScan;
        boolean playerMode;
        KeyHandler keys;
        RingFilter filter;

        final Runnable scanTask = new Runnable() {
            @Override public void run() {
                scanQueued = false;
                lastScanAt = SystemClock.uptimeMillis();
                try {
                    float area = (float) root.getWidth() * root.getHeight();
                    scan(root, area);
                } catch (Throwable ignored) { }
                if (focusFirstAfterScan) {
                    focusFirstAfterScan = false;
                    try {
                        if (root.findFocus() == null && !root.isInTouchMode()) root.requestFocus(View.FOCUS_DOWN);
                    } catch (Throwable ignored) { }
                }
            }
        };

        final Runnable guard = new Runnable() {
            @Override public void run() {
                Activity a = activity.get();
                if (a == null || a.isFinishing() || !resumed) return;
                try { ensureFocus(a); } catch (Throwable ignored) { }
                UI.postDelayed(this, GUARD_MS);
            }
        };

        Host(ViewGroup root, Activity a) {
            this.root = root;
            this.activity = new WeakReference<Activity>(a);
            density = root.getResources().getDisplayMetrics().density;
            ring = new Ring(density);
        }

        void start() {
            root.getOverlay().add(ring);
            ring.setBounds(0, 0, Math.max(1, root.getWidth()), Math.max(1, root.getHeight()));
            ViewTreeObserver vto = root.getViewTreeObserver();
            vto.addOnGlobalFocusChangeListener(this);
            vto.addOnPreDrawListener(this);
            vto.addOnGlobalLayoutListener(this);
            vto.addOnTouchModeChangeListener(this);
            queueScan(true);
            View f = root.findFocus();
            if (f != null) onGlobalFocusChanged(null, f);
        }

        // ---------------------------------------------------------- focusability

        void queueScan(boolean now) {
            if (scanQueued) return;
            scanQueued = true;
            long wait = now ? 0L : Math.max(0L, SCAN_GAP_MS - (SystemClock.uptimeMillis() - lastScanAt));
            UI.postDelayed(scanTask, wait);
        }

        @Override public void onGlobalLayout() {
            ring.setBounds(0, 0, Math.max(1, root.getWidth()), Math.max(1, root.getHeight()));
            queueScan(false);
        }

        void scan(View v, float rootArea) {
            if (v == null || v.getVisibility() != View.VISIBLE) return;
            if (v instanceof AbsListView) {
                // the list moves its own selection with the arrows; its rows stay untouched
                AbsListView list = (AbsListView) v;
                if (!v.isFocusable()) v.setFocusable(true);
                if (list.getOnItemClickListener() == null) list.setOnItemClickListener(ROW_CLICK);
                if (list.getOnItemLongClickListener() == null) list.setOnItemLongClickListener(ROW_LONG);
                plain(v);
                return;
            }
            if (v instanceof AbsSeekBar) {
                // a seek bar would swallow LEFT / RIGHT: the remote uses the seek buttons instead
                if (v.isFocusable()) v.setFocusable(false);
                return;
            }
            String cls = v.getClass().getName();
            if (cls.contains("WebView") || cls.startsWith("com.google.android.gms.ads")) return;
            if (!v.isFocusable() && v.isEnabled() && v.hasOnClickListeners() && !(v instanceof EditText)) {
                int w = v.getWidth();
                int h = v.getHeight();
                if (w > 0 && h > 0 && (rootArea <= 0 || w * (float) h < rootArea * HUGE)) {
                    v.setFocusable(true);
                }
            }
            if (v.isFocusable()) plain(v);
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                if (g.getDescendantFocusability() == ViewGroup.FOCUS_BLOCK_DESCENDANTS) return;
                for (int i = 0; i < g.getChildCount(); i++) scan(g.getChildAt(i), rootArea);
            }
        }

        private void plain(View v) {
            // the white outline replaces Android's grey focus tint (not both)
            if (Build.VERSION.SDK_INT >= 26 && !(v instanceof CigramTvRail.Styled)) {
                try { v.setDefaultFocusHighlightEnabled(false); } catch (Throwable ignored) { }
            }
        }

        // ---------------------------------------------------------- focus guard

        void ensureFocus(Activity a) {
            if (!a.hasWindowFocus() || root.isInTouchMode()) return;
            View f = root.findFocus();
            if (f != null && f.isShown() && f.getWidth() > 0 && f.getHeight() > 0) return;
            focusDefault();
        }

        boolean focusDefault() {
            Activity a = activity.get();
            if (a == null || root.isInTouchMode()) return false;
            View d = defaultFocus == null ? null : defaultFocus.get();
            if (d != null && d.isShown() && d.requestFocus()) return true;
            if (playerMode) return false;   // controls hidden: leave the focus alone
            // a film page opens on its own buttons (watch, episodes …), the tab pages on the rail
            if ("DetailsActivity".equals(a.getClass().getSimpleName()) && CigramTvRail.focusPage(a)) return true;
            if (CigramTvRail.focusSelected(a)) return true;
            View content = a.findViewById(android.R.id.content);
            return content != null && content.requestFocus(View.FOCUS_DOWN);
        }

        // ---------------------------------------------------------- keys

        boolean onKey(KeyEvent e) {
            Activity a = activity.get();
            if (a == null || !CigramTvMode.isTv(a)) return false;
            if (keys != null) {
                try {
                    if (keys.onKey(e)) return true;
                } catch (Throwable ignored) { }
            }
            int code = e.getKeyCode();
            final CigramCarousel.Pager pager = pagerOf(root.findFocus());
            if (pager != null) {
                // the home carousel: LEFT / RIGHT turn the cards, OK opens the card in the middle
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
                    if (pager.tvStep(code == KeyEvent.KEYCODE_DPAD_LEFT ? 1 : -1)) {
                        UI.postDelayed(new Runnable() {
                            @Override public void run() {
                                View m = pager.tvMiddlePage();
                                if (m != null && m.isFocusable()) m.requestFocus();
                            }
                        }, 340L);
                        return true;
                    }
                    return false;
                }
                if (isConfirm(code)) {
                    if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) {
                        View m = pager.tvMiddlePage();
                        if (m != null) m.performClick();
                    }
                    return true;
                }
            }
            if (code == KeyEvent.KEYCODE_MENU && CigramTvRail.has(a)) {
                if (e.getAction() == KeyEvent.ACTION_UP && !e.isCanceled()) CigramTvRail.focusSelected(a);
                return true;
            }
            return false;
        }

        // ---------------------------------------------------------- outline

        /** The card under the remote grows a little (and floats above its neighbours); the one it left shrinks back. */
        private void lift(View oldFocus, View newFocus) {
            try {
                if (oldFocus != null && oldFocus.getTag(LIFT_KEY) != null) {
                    oldFocus.setTag(LIFT_KEY, null);
                    oldFocus.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(140L).start();
                }
                if (newFocus == null || root.isInTouchMode() || newFocus instanceof CigramTvRail.Styled
                        || newFocus instanceof android.widget.EditText || newFocus instanceof AbsListView) return;
                int w = newFocus.getWidth(), h = newFocus.getHeight();
                int sw = root.getWidth(), sh = root.getHeight();
                if (w < 70f * density || h < 70f * density || w > sw * 0.45f || h > sh * 0.6f) return;
                // only the cards of horizontal rows (posters, channels, episodes), never buttons or bars
                ViewParent p = newFocus.getParent();
                boolean inRow = false;
                for (int i = 0; i < 8 && p instanceof View && p != root; i++, p = p.getParent()) {
                    if (p instanceof android.widget.HorizontalScrollView || p.getClass().getName().contains("RecyclerView")) { inRow = true; break; }
                }
                if (!inRow) return;
                newFocus.setTag(LIFT_KEY, Boolean.TRUE);
                newFocus.setPivotX(w / 2f);
                newFocus.setPivotY(h / 2f);
                newFocus.animate().scaleX(1.07f).scaleY(1.07f).translationZ(10f * density).setDuration(160L).start();
            } catch (Throwable ignored) { }
        }

        @Override public void onGlobalFocusChanged(View oldFocus, View newFocus) {
            lift(oldFocus, newFocus);
            View t = resolve(newFocus);
            View cur = target == null ? null : target.get();
            if (t == cur) return;
            target = t == null ? null : new WeakReference<View>(t);
            from.set(shown);
            glideStart = SystemClock.uptimeMillis();
            root.invalidate();
        }

        @Override public void onTouchModeChanged(boolean inTouchMode) {
            root.invalidate();
        }

        @Override public boolean onPreDraw() {
            try {
                track();
            } catch (Throwable ignored) { }
            return true;
        }

        private View resolve(View f) {
            if (f == null) return null;
            if (f instanceof AbsListView) return ((AbsListView) f).getSelectedView();
            View middle = carouselMiddle(f);
            return middle != null ? middle : f;
        }

        /** Focus inside the home carousel: the outline sits on the card in the middle. */
        private View carouselMiddle(View f) {
            CigramCarousel.Pager pager = pagerOf(f);
            if (pager == null) return null;
            View m = pager.tvMiddlePage();
            return m != null ? m : pager;
        }

        private CigramCarousel.Pager pagerOf(View f) {
            View v = f;
            while (v != null && v != root) {
                if (v instanceof CigramCarousel.Pager) return (CigramCarousel.Pager) v;
                ViewParent p = v.getParent();
                v = p instanceof View ? (View) p : null;
            }
            return null;
        }

        private void track() {
            View t = target == null ? null : target.get();
            View focused = root.findFocus();
            if (focused instanceof AbsListView || carouselMiddle(focused) != null) {
                // the selected row / middle card changes without a focus change
                View r = resolve(focused);
                if (r != t) {
                    t = r;
                    target = r == null ? null : new WeakReference<View>(r);
                    from.set(shown);
                    glideStart = SystemClock.uptimeMillis();
                }
            }
            boolean visible = t != null && !root.isInTouchMode() && measure(t, to)
                    && (filter == null || filter.ringVisible(t))
                    && !(t instanceof CigramTvRail.Styled);
            if (!visible) {
                if (ring.visible) {
                    ring.visible = false;
                    hasShown = false;
                    root.invalidate();
                }
                return;
            }
            float k = Math.min(1f, (SystemClock.uptimeMillis() - glideStart) / (float) GLIDE_MS);
            if (!hasShown || from.isEmpty()) k = 1f;
            float e = 1f - (1f - k) * (1f - k);
            float l = from.left + (to.left - from.left) * e;
            float tp = from.top + (to.top - from.top) * e;
            float r = from.right + (to.right - from.right) * e;
            float b = from.bottom + (to.bottom - from.bottom) * e;
            boolean changed = !ring.visible || Math.abs(shown.left - l) > 0.5f || Math.abs(shown.top - tp) > 0.5f
                    || Math.abs(shown.right - r) > 0.5f || Math.abs(shown.bottom - b) > 0.5f;
            shown.set(l, tp, r, b);
            hasShown = true;
            float radius = radiusOf(t);
            boolean oval = isRound(t);
            if (changed || ring.radius != radius || ring.oval != oval) {
                ring.rect.set(shown);
                ring.radius = radius;
                ring.oval = oval;
                ring.visible = true;
                root.invalidate();
            }
            if (k < 1f) root.postInvalidateOnAnimation();
        }

        /** The view's box on screen, in the window, plus a small gap for the outline. */
        private boolean measure(View v, RectF out) {
            if (!v.isShown() || v.getWidth() <= 0 || v.getHeight() <= 0) return false;
            if (effectiveAlpha(v) < 0.35f) return false;
            v.getLocationInWindow(loc);
            root.getLocationInWindow(rootLoc);
            float x = loc[0] - rootLoc[0];
            float y = loc[1] - rootLoc[1];
            float w = v.getWidth() * v.getScaleX();
            float h = v.getHeight() * v.getScaleY();
            float rw = root.getWidth();
            float rh = root.getHeight();
            if (rw <= 0 || rh <= 0) return false;
            if (w * h >= rw * rh * HUGE) return false;          // a whole page: no outline
            if (x + w <= 0 || y + h <= 0 || x >= rw || y >= rh) return false;   // scrolled away
            float gap = 3f * density;
            out.set(x - gap, y - gap, x + w + gap, y + h + gap);
            return true;
        }

        private float effectiveAlpha(View v) {
            float alpha = 1f;
            View cur = v;
            while (cur != null && alpha > 0.01f) {
                alpha *= cur.getAlpha();
                ViewParent p = cur.getParent();
                cur = p instanceof View ? (View) p : null;
            }
            return alpha;
        }

        private boolean isRound(View v) {
            int w = v.getWidth();
            int h = v.getHeight();
            if (Math.abs(w - h) > 3f * density || w > 72f * density) return false;
            GradientDrawable g = gradientOf(v.getBackground());
            if (g != null && Build.VERSION.SDK_INT >= 24) {
                try { if (g.getShape() == GradientDrawable.OVAL) return true; } catch (Throwable ignored) { }
            }
            return (v instanceof android.widget.ImageView && CigramAvatarFrame.isPhoto((android.widget.ImageView) v)) || v.getClipToOutline() || w <= 48f * density;
        }

        private float radiusOf(View v) {
            float base = 12f * density;
            GradientDrawable g = gradientOf(v.getBackground());
            if (g != null && Build.VERSION.SDK_INT >= 24) {
                try {
                    float r = g.getCornerRadius();
                    if (r > 0f) base = r;
                } catch (Throwable ignored) { }
            }
            float gap = 3f * density;
            float max = Math.min(v.getWidth(), v.getHeight()) / 2f + gap;
            return Math.min(base + gap, max);
        }

        private GradientDrawable gradientOf(Drawable d) {
            for (int i = 0; i < 4 && d != null; i++) {
                if (d instanceof GradientDrawable) return (GradientDrawable) d;
                if (d instanceof RippleDrawable || d instanceof LayerDrawable) {
                    LayerDrawable l = (LayerDrawable) d;
                    d = l.getNumberOfLayers() > 0 ? l.getDrawable(0) : null;
                } else if (d instanceof InsetDrawable && Build.VERSION.SDK_INT >= 19) {
                    d = ((InsetDrawable) d).getDrawable();
                } else {
                    return null;
                }
            }
            return null;
        }
    }

    // ================================================================== the white outline

    private static final class Ring extends Drawable {
        final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        final RectF tmp = new RectF();
        float radius;
        boolean oval;
        boolean visible;

        Ring(float density) {
            glow.setStyle(Paint.Style.STROKE);
            glow.setColor(0x47FFFFFF);
            glow.setStrokeWidth(6f * density);
            line.setStyle(Paint.Style.STROKE);
            line.setColor(0xFFFFFFFF);
            line.setStrokeWidth(2.6f * density);
        }

        @Override public void draw(Canvas c) {
            if (!visible || rect.isEmpty()) return;
            float g = glow.getStrokeWidth() / 2f + line.getStrokeWidth() / 2f;
            tmp.set(rect.left - g, rect.top - g, rect.right + g, rect.bottom + g);
            if (oval) {
                c.drawOval(tmp, glow);
                c.drawOval(rect, line);
            } else {
                c.drawRoundRect(tmp, radius + g, radius + g, glow);
                c.drawRoundRect(rect, radius, radius, line);
            }
        }

        @Override public void setAlpha(int alpha) {
            line.setAlpha(alpha);
            glow.setAlpha(Math.round(alpha * 0x47 / 255f));
        }

        @Override public void setColorFilter(ColorFilter cf) { }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
