package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * v6.0 (design 5 «لوحة كاملة»): the sheet is now a side panel that slides in from the left edge over
 * the player and keeps the video visible on the right. Settings with a few short choices (speed,
 * seek step, screen size) are segmented buttons inside the panel: one tap, no second page. Settings
 * with long or many choices (quality) still open their page inside the panel.
 *
 * Player settings sheet (v5.29, list layout).
 *
 *   ┌──────────────────────────── ━━━ ────────────────────────────┐
 *   │  ‹  1080p                                  دقة الفيديو  ⚙  │
 *   │  ‹  عربي                                       الترجمة  CC │
 *   │  ...                                                        │
 *   │  (●━)                          بدء الحلقة التالية تلقائياً  ▶ │
 *   └─────────────────────────────────────────────────────────────┘
 *
 * An opaque sheet slides up from the bottom over a dimmed player. Each setting is one full-width
 * row: icon + title on the right, current value + chevron on the left. A row with choices opens
 * its own page inside the sheet (back with the arrow or the back key); a two-state row flips a
 * switch in place. Drag the handle down, tap outside or press back to close.
 *
 * The public API (Options / Source / Toggle, item, toggle, setOnDismiss, show, window,
 * isShowing, dismiss) is unchanged: the player Logic builds the sheet exactly as before.
 */
public final class CigramPlayerSheet {

    /** Choices of one setting, read at the moment the row is opened. */
    public static final class Options {
        final String[] labels;
        final int selected;
        final String hint;

        public Options(String[] labels, int selected) {
            this(labels, selected, "");
        }

        public Options(String[] labels, int selected, String hint) {
            this.labels = labels == null ? new String[0] : labels;
            this.selected = selected;
            this.hint = hint == null ? "" : hint;
        }

        /** No choices, only an explanatory message. */
        public static Options message(String hint) {
            return new Options(new String[0], -1, hint);
        }
    }

    public interface Source {
        Options load();

        /** Applies the choice and returns the new value text for the row (or null to keep it). */
        String pick(int which);
    }

    public interface Toggle {
        /** Flips the state and returns the new state. */
        boolean toggle();
    }

    // ------------------------------------------------------------------ auto next episode

    private static final String PREFS = "cigram_player_prefs";
    private static final String KEY_AUTO_NEXT = "auto_next_episode";

    /** «بدء الحلقة التالية تلقائياً»; on by default. Read by both auto-next paths of the player. */
    public static boolean autoNextEnabled(Context c) {
        try {
            return c == null || c.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_NEXT, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void setAutoNext(Context c, boolean on) {
        c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_NEXT, on).apply();
    }

    // ------------------------------------------------------------------ look

    private static final int SCRIM = 0x59000000;
    private static final int SHEET = 0xF208141B;
    private static final int SEG = 0x1FFFFFFF;
    private static final int SEG_ON = 0xFFFFFFFF;
    private static final int ROW = 0xFF1C1C20;
    private static final int ROW_PRESSED = 0xFF12384A;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int VALUE = 0xFFA9B6BD;
    private static final int ICON = 0xFF9AA9B2;
    private static final int ACCENT = 0xFFE21B14;
    private static final long OPEN_MS = 280L;
    private static final long CLOSE_MS = 200L;
    private static final long PAGE_MS = 220L;

    private final Activity activity;
    private final View anchor;
    private final float density;
    private final Typeface font;

    private final FrameLayout root;
    private final LinearLayout sheet;
    private final MaxHeightFrame pages;
    private final ScrollView mainPage;
    private final LinearLayout mainList;
    private ScrollView subPage;

    private Window window;
    private Runnable onDismiss;
    private boolean autoNextAdded;

    public CigramPlayerSheet(Activity activity, View anchor, String title) {
        this.activity = activity;
        this.anchor = anchor;
        this.density = activity.getResources().getDisplayMetrics().density;
        Typeface f = Typeface.DEFAULT;
        try {
            f = CigramTypefaces.get(activity.getAssets(), CigramTypefaces.APP_FONT);
        } catch (Throwable ignored) { }
        this.font = f;

        root = new FrameLayout(activity);
        root.setBackgroundColor(SCRIM);
        root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); }
        });

        sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        sheet.setClickable(true); // taps on the sheet never reach the scrim
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SHEET);
        float r = px(18);
        // panel on the left edge: only its right-hand corners are rounded
        bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        sheet.setBackground(bg);
        sheet.setClipToOutline(true);
        sheet.setContentDescription(title);

        // panel header: title + close (x). Tap outside or back also closes.
        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(6), 0, px(6), 0);
        IconView close = new IconView(activity, Icon.CLOSE_X, TEXT);
        close.setClickable(true);
        close.setContentDescription("إغلاق");
        close.setPadding(px(6), px(6), px(6), px(6));
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dismiss(); }
        });
        head.addView(close, new LinearLayout.LayoutParams(px(34), px(34)));
        TextView ht = label(title == null || title.length() == 0 ? "الإعدادات" : title, 17f, TEXT, true);
        ht.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        head.addView(ht, new LinearLayout.LayoutParams(0, -1, 1f));
        sheet.addView(head, new LinearLayout.LayoutParams(-1, px(52)));

        pages = new MaxHeightFrame(activity);
        mainPage = new ScrollView(activity);
        mainPage.setVerticalScrollBarEnabled(false);
        mainPage.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mainList = new LinearLayout(activity);
        mainList.setOrientation(LinearLayout.VERTICAL);
        mainList.setPadding(0, 0, 0, px(10));
        mainPage.addView(mainList, new ScrollView.LayoutParams(-1, -2));
        pages.addView(mainPage, new FrameLayout.LayoutParams(-1, -2));
        sheet.addView(pages, new LinearLayout.LayoutParams(-1, -2));

        root.addView(sheet, new FrameLayout.LayoutParams(px(340), -1, Gravity.LEFT | Gravity.TOP));
    }

    private int px(float dp) {
        return Math.round(dp * density);
    }

    // ------------------------------------------------------------------ rows

    /** A setting with choices. Returns the value view of the row. */
    public TextView item(final String name, String value, final Source source) {
        Options inline = null;
        try {
            inline = source.load();
        } catch (Throwable ignored) { }
        if (fitsSegments(inline)) return segmentItem(name, value, source, inline);
        final Row row = new Row(name, Icon.forTitle(name));
        row.value.setText(value == null ? "" : value);
        row.view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openChoices(name, row.value, source); }
        });
        addRow(row.view);
        return row.value;
    }

    /** v10.68: a small heading that groups the rows under it (الصورة، الصوت والترجمة، التشغيل …). */
    public void section(String title) {
        TextView t = label(title, 12.5f, VALUE, true);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setPadding(px(6), mainList.getChildCount() == 0 ? px(2) : px(10), px(6), px(6));
        mainList.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    /** v10.68: a read-only row (title on the right, value on the left), e.g. the playing resolution. */
    public TextView info(String name, String value) {
        LinearLayout v = rowShell(name);
        v.setClickable(false);
        v.setBackground(rounded(ROW));
        TextView val = label(value == null ? "" : value, 13.5f, VALUE, false);
        val.setTextDirection(View.TEXT_DIRECTION_LTR);
        v.addView(val, new LinearLayout.LayoutParams(-2, -1));
        addTitle(v, name, Icon.forTitle(name));
        addRow(v);
        return val;
    }

    /** 2..5 short choices fit as one row of buttons inside the panel. */
    private static boolean fitsSegments(Options o) {
        if (o == null || o.labels.length < 2 || o.labels.length > 5 || o.hint.length() > 0) return false;
        int chars = 0;
        for (String l : o.labels) chars += l == null ? 0 : l.length();
        return chars <= 30;
    }

    /** Title line, then the choices as segmented buttons; the chosen one is white. */
    private TextView segmentItem(final String name, String value, final Source source, Options o) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box.setPadding(px(14), px(10), px(14), px(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ROW);
        bg.setCornerRadius(px(12));
        box.setBackground(bg);

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        top.setGravity(Gravity.CENTER_VERTICAL);
        final TextView valueView = label(value == null ? "" : value, 13f, VALUE, false);
        top.addView(valueView, new LinearLayout.LayoutParams(-2, -2));
        TextView t = label(name, 14.5f, TEXT, true);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = px(8);
        tl.rightMargin = px(10);
        top.addView(t, tl);
        top.addView(new IconView(activity, Icon.forTitle(name), ICON), new LinearLayout.LayoutParams(px(20), px(20)));
        box.addView(top, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout seg = new LinearLayout(activity);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        final TextView[] buttons = new TextView[o.labels.length];
        for (int i = 0; i < o.labels.length; i++) {
            final int which = i;
            final TextView b = label(o.labels[i], 13f, TEXT, false);
            b.setGravity(Gravity.CENTER);
            b.setClickable(true);
            b.setContentDescription(name + " " + o.labels[i]);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String shown = null;
                    try {
                        shown = source.pick(which);
                    } catch (Throwable error) {
                        android.util.Log.w("CigramPlayerSheet", "pick failed", error);
                    }
                    if (shown != null) valueView.setText(shown);
                    paintSegments(buttons, which);
                }
            });
            buttons[i] = b;
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, px(34), 1f);
            if (i > 0) bl.setMarginStart(px(6));
            seg.addView(b, bl);
        }
        paintSegments(buttons, o.selected);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = px(9);
        box.addView(seg, sl);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px(8);
        mainList.addView(box, lp);
        return valueView;
    }

    private void paintSegments(TextView[] buttons, int selected) {
        for (int i = 0; i < buttons.length; i++) {
            boolean on = i == selected;
            GradientDrawable d = new GradientDrawable();
            d.setColor(on ? SEG_ON : SEG);
            d.setCornerRadius(px(9));
            buttons[i].setBackground(d);
            buttons[i].setTextColor(on ? 0xFF000000 : TEXT);
            buttons[i].setTypeface(font, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    /** A two-state setting: flips in place with a switch. */
    public void toggle(String name, boolean on, final Toggle toggle) {
        final ToggleRow row = new ToggleRow(name, Icon.forTitle(name), on);
        row.view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    row.sw.set(toggle.toggle(), true);
                } catch (Throwable error) {
                    android.util.Log.w("CigramPlayerSheet", "toggle failed", error);
                }
            }
        });
        addRow(row.view);
    }

    private void addRow(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(52));
        lp.bottomMargin = px(8);
        mainList.addView(v, lp);
    }

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(font, bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private Drawable rowBackground() {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(ROW_PRESSED));
        s.addState(new int[]{}, rounded(ROW));
        return s;
    }

    private Drawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(12));
        return d;
    }

    /** Physical order (LTR): [end widget] [value] ... [title] [icon] → icon at the far right. */
    private LinearLayout rowShell(String title) {
        LinearLayout v = new LinearLayout(activity);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(px(18), 0, px(18), 0);
        v.setBackground(rowBackground());
        v.setClickable(true);
        v.setFocusable(true);
        v.setContentDescription(title);
        return v;
    }

    private final class Row {
        final LinearLayout view;
        final TextView value;

        Row(String title, int icon) {
            view = rowShell(title);
            view.addView(new IconView(activity, Icon.CHEVRON, VALUE), new LinearLayout.LayoutParams(px(16), px(16)));
            value = label("", 14f, VALUE, false);
            LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -1);
            vl.leftMargin = px(10);
            view.addView(value, vl);
            addTitle(view, title, icon);
        }
    }

    private final class ToggleRow {
        final LinearLayout view;
        final SwitchView sw;

        ToggleRow(String title, int icon, boolean on) {
            view = rowShell(title);
            sw = new SwitchView(activity, on);
            view.addView(sw, new LinearLayout.LayoutParams(px(42), px(24)));
            addTitle(view, title, icon);
        }
    }

    private void addTitle(LinearLayout row, String title, int icon) {
        TextView t = label(title, 15f, TEXT, true);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -1, 1f);
        tl.leftMargin = px(12);
        tl.rightMargin = px(14);
        row.addView(t, tl);
        row.addView(new IconView(activity, icon, ICON), new LinearLayout.LayoutParams(px(22), px(22)));
    }

    // ------------------------------------------------------------------ choices page

    private void openChoices(final String title, final TextView valueView, final Source source) {
        Options o;
        try {
            o = source.load();
        } catch (Throwable error) {
            o = Options.message("تعذر قراءة الخيارات");
        }
        final ScrollView page = new ScrollView(activity);
        page.setVerticalScrollBarEnabled(false);
        page.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, px(10));
        page.addView(list, new ScrollView.LayoutParams(-1, -2));

        // header: back arrow + title (back = right side in Arabic)
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(px(18), 0, px(18), 0);
        header.setClickable(true);
        header.setFocusable(true);
        header.setContentDescription("رجوع");
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeChoices(); }
        });
        TextView ht = label(title, 16f, TEXT, true);
        ht.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, -1, 1f);
        hl.rightMargin = px(12);
        header.addView(ht, hl);
        header.addView(new IconView(activity, Icon.BACK, TEXT), new LinearLayout.LayoutParams(px(20), px(20)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, px(48));
        hlp.bottomMargin = px(4);
        list.addView(header, hlp);

        if (o.hint.length() > 0) {
            TextView hint = label(o.hint, 13f, VALUE, false);
            hint.setSingleLine(false);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(px(18), px(6), px(18), px(10));
            list.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        }
        for (int i = 0; i < o.labels.length; i++) {
            final int which = i;
            boolean chosen = i == o.selected;
            LinearLayout row = rowShell(o.labels[i]);
            IconView check = new IconView(activity, Icon.CHECK, ACCENT);
            check.setVisibility(chosen ? View.VISIBLE : View.INVISIBLE);
            row.addView(check, new LinearLayout.LayoutParams(px(20), px(20)));
            TextView t = label(o.labels[i], 15f, chosen ? TEXT : VALUE, chosen);
            t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            row.addView(t, new LinearLayout.LayoutParams(0, -1, 1f));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String shown = null;
                    try {
                        shown = source.pick(which);
                    } catch (Throwable error) {
                        android.util.Log.w("CigramPlayerSheet", "pick failed", error);
                    }
                    if (shown != null) valueView.setText(shown);
                    closeChoices();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(52));
            lp.bottomMargin = px(8);
            list.addView(row, lp);
        }
        showSubPage(page);
    }

    private void showSubPage(ScrollView page) {
        if (subPage != null) pages.removeView(subPage);
        subPage = page;
        pages.addView(page, new FrameLayout.LayoutParams(-1, -2));
        int w = Math.max(pages.getWidth(), px(320));
        // RTL: the new page comes in from the left, the list leaves to the right
        page.setTranslationX(-w);
        page.animate().translationX(0f).setDuration(PAGE_MS).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        mainPage.animate().translationX(w).alpha(0f).setDuration(PAGE_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    private void closeChoices() {
        final ScrollView page = subPage;
        if (page == null) return;
        subPage = null;
        int w = Math.max(pages.getWidth(), px(320));
        page.animate().translationX(-w).setDuration(PAGE_MS).withEndAction(new Runnable() {
            @Override public void run() { pages.removeView(page); }
        }).start();
        mainPage.animate().translationX(0f).alpha(1f).setDuration(PAGE_MS)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    // ------------------------------------------------------------------ show / dismiss

    public void setOnDismiss(Runnable action) {
        onDismiss = action;
    }

    public PopupWindow window() {
        return window;
    }

    public boolean isShowing() {
        return window != null && window.isShowing();
    }

    public void show() {
        if (isShowing()) return;
        addAutoNextRow();
        applySafeInsets();
        window = new Window(root);
        window.setBackgroundDrawable(new ColorDrawable(0x00000000));
        window.setOutsideTouchable(true);
        window.setFocusable(true);
        window.setClippingEnabled(true);
        window.setAnimationStyle(0);
        window.setOnDismissListener(new PopupWindow.OnDismissListener() {
            @Override public void onDismiss() {
                if (onDismiss != null) onDismiss.run();
            }
        });
        root.setAlpha(0f);
        sheet.setTranslationX(-sheet.getLayoutParams().width - px(24));
        window.showAtLocation(anchor, Gravity.NO_GRAVITY, 0, 0);
        CigramTvFocus.attachWindowOf(root);   // v10.66: TV remote inside the settings (TV only)
        root.animate().alpha(1f).setDuration(OPEN_MS).start();
        sheet.animate().translationX(0f).setDuration(OPEN_MS)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    /** Series only: «بدء الحلقة التالية تلقائياً» as the last row. */
    private void addAutoNextRow() {
        if (autoNextAdded) return;
        autoNextAdded = true;
        boolean series;
        try {
            series = CigramStage1InitProvider.playerIsSeries(activity);
        } catch (Throwable ignored) {
            series = false;
        }
        if (!series) return;
        section("الحلقات");
        toggle("بدء الحلقة التالية تلقائياً", autoNextEnabled(activity), new Toggle() {
            @Override public boolean toggle() {
                boolean on = !autoNextEnabled(activity);
                setAutoNext(activity, on);
                return on;
            }
        });
        // v10.62: «تخطي المقدمة تلقائياً» (CigramSkipIntro). Off by default: the button is always shown.
        toggle("تخطي المقدمة تلقائياً", CigramSkipIntro.autoSkipEnabled(activity), new Toggle() {
            @Override public boolean toggle() {
                boolean on = !CigramSkipIntro.autoSkipEnabled(activity);
                CigramSkipIntro.setAutoSkip(activity, on);
                return on;
            }
        });
    }

    public void dismiss() {
        if (window != null) window.dismiss();
    }

    private void applySafeInsets() {
        int left = 0;
        int right = 0;
        int bottom = 0;
        try {
            android.view.WindowInsets insets = anchor.getRootWindowInsets();
            if (insets != null) {
                bottom = insets.getStableInsetBottom();
                if (android.os.Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                    left = insets.getDisplayCutout().getSafeInsetLeft();
                    right = insets.getDisplayCutout().getSafeInsetRight();
                }
            }
        } catch (Throwable ignored) { }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sheet.getLayoutParams();
        int screen = activity.getResources().getDisplayMetrics().widthPixels;
        int h = activity.getResources().getDisplayMetrics().heightPixels;
        boolean landscape = screen > h;
        // landscape: a side panel (about 40% of the width); portrait: the full width
        int panel = landscape ? Math.max(px(300), Math.min(px(380), Math.round(screen * 0.42f))) : screen;
        lp.width = panel + left;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.gravity = Gravity.LEFT | Gravity.TOP;
        sheet.setLayoutParams(lp);
        sheet.setPadding(left + px(12), px(10), px(12), Math.min(bottom, px(24)) + px(6));
        pages.setMaxHeight(h - px(52) - px(20) - Math.min(bottom, px(24)));
    }

    private final class Window extends PopupWindow {
        private boolean closing;

        Window(View content) {
            super(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true);
        }

        @Override public void dismiss() {
            if (closing || !isShowing()) return;
            if (subPage != null) { // back key on a choices page: go back to the list first
                closeChoices();
                return;
            }
            closing = true;
            sheet.animate().cancel();
            root.animate().alpha(0f).setDuration(CLOSE_MS).start();
            sheet.animate().translationX(-sheet.getWidth() - px(24)).setDuration(CLOSE_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(new Runnable() {
                        @Override public void run() { finishDismiss(); }
                    }).start();
        }

        void forceDismiss() {
            subPage = null;
            dismiss();
        }

        void finishDismiss() {
            try {
                super.dismiss();
            } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------ drag to close

    private void installDrag(View handleArea) {
        final int touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        handleArea.setOnTouchListener(new View.OnTouchListener() {
            float downY;
            boolean dragging;
            VelocityTracker tracker;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = e.getRawY();
                        dragging = false;
                        tracker = VelocityTracker.obtain();
                        tracker.addMovement(e);
                        sheet.animate().cancel();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (tracker != null) tracker.addMovement(e);
                        float dy = e.getRawY() - downY;
                        if (!dragging && Math.abs(dy) > touchSlop) dragging = true;
                        if (dragging) sheet.setTranslationY(Math.max(0f, dy));
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float velocity = 0f;
                        if (tracker != null) {
                            tracker.addMovement(e);
                            tracker.computeCurrentVelocity(1000);
                            velocity = tracker.getYVelocity();
                            tracker.recycle();
                            tracker = null;
                        }
                        if (!dragging) return true;
                        float t = sheet.getTranslationY();
                        if (t > sheet.getHeight() * 0.3f || velocity > 1200f * density) {
                            if (window != null) window.forceDismiss();
                        } else {
                            sheet.animate().translationY(0f).setDuration(220L)
                                    .setInterpolator(new DecelerateInterpolator(2f)).start();
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });
    }

    // ------------------------------------------------------------------ pieces

    /** FrameLayout whose height never goes past a maximum (the list then scrolls). */
    static final class MaxHeightFrame extends FrameLayout {
        private int maxHeight = Integer.MAX_VALUE;

        MaxHeightFrame(Context c) { super(c); }

        void setMaxHeight(int h) {
            maxHeight = h;
            requestLayout();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int mode = MeasureSpec.getMode(heightSpec);
            int size = MeasureSpec.getSize(heightSpec);
            int limit = mode == MeasureSpec.UNSPECIFIED ? maxHeight : Math.min(size, maxHeight);
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        }
    }

    /** Small on/off switch: red track when on, the thumb slides to the leading (left) side. */
    final class SwitchView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private boolean on;
        private float pos; // 0 = off (thumb right), 1 = on (thumb left)

        SwitchView(Context c, boolean on) {
            super(c);
            this.on = on;
            this.pos = on ? 1f : 0f;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(boolean value, boolean animate) {
            on = value;
            final float target = value ? 1f : 0f;
            if (!animate) {
                pos = target;
                invalidate();
                return;
            }
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(pos, target);
            a.setDuration(160L);
            a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator va) {
                    pos = (Float) va.getAnimatedValue();
                    invalidate();
                }
            });
            a.start();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;
            track.set(0, 0, w, h);
            paint.setColor(blend(0x40FFFFFF, ACCENT, pos));
            c.drawRoundRect(track, r, r, paint);
            float inset = h * 0.12f;
            float cx = (w - r) - (w - 2 * r) * pos;
            paint.setColor(0xFFFFFFFF);
            c.drawCircle(cx, r, r - inset, paint);
        }

        private int blend(int a, int b, float t) {
            int aa = (a >>> 24), ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = (b >>> 24), br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                    | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
        }
    }

    /** Line icons drawn in code (no image assets). */
    static final class Icon {
        static final int NONE = 0, QUALITY = 1, SUBTITLE = 2, RESIZE = 3, SEEK = 4, SPEED = 5,
                SKIP = 6, AUTO_NEXT = 7, CHEVRON = 8, BACK = 9, CHECK = 10, CLOSE_X = 11,
                AUDIO = 12, TIMER = 13, INFO = 14, TEXT_SIZE = 15;

        static int forTitle(String t) {
            if (t == null) return NONE;
            if (t.contains("معلومات")) return INFO;
            if (t.contains("حجم")) return TEXT_SIZE;
            if (t.contains("صوت")) return AUDIO;
            if (t.contains("مؤقت")) return TIMER;
            if (t.contains("جودة") || t.contains("دقة")) return QUALITY;
            if (t.contains("ترجمة")) return SUBTITLE;
            if (t.contains("أبعاد") || t.contains("ابعاد")) return RESIZE;
            if (t.contains("تمرير")) return SEEK;
            if (t.contains("سرعة")) return SPEED;
            if (t.contains("تخطي")) return SKIP;
            if (t.contains("الحلقة التالية")) return AUTO_NEXT;
            return NONE;
        }
    }

    static final class IconView extends View {
        private final int kind;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint teeth = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF box = new RectF();

        IconView(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setColor(color);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(color);
            teeth.setStyle(Paint.Style.STROKE);
            teeth.setStrokeCap(Paint.Cap.BUTT);
            teeth.setColor(color);
            cut.setColor(ROW); // "holes" in filled icons take the row colour
            cut.setTypeface(CigramTypefaces.bold());
            cut.setTextAlign(Paint.Align.CENTER);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight());
            if (s <= 0) return;
            float ox = (getWidth() - s) / 2f;
            float oy = (getHeight() - s) / 2f;
            c.save();
            c.translate(ox, oy);
            stroke.setStrokeWidth(Math.max(1.5f, s * 0.09f));
            float m = s / 2f;
            path.reset();
            switch (kind) {
                case Icon.CLOSE_X: { // ×
                    c.drawLine(s * 0.32f, s * 0.32f, s * 0.68f, s * 0.68f, stroke);
                    c.drawLine(s * 0.68f, s * 0.32f, s * 0.32f, s * 0.68f, stroke);
                    break;
                }
                case Icon.QUALITY: { // gear
                    teeth.setStrokeWidth(s * 0.16f);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI * i / 4.0;
                        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                        c.drawLine(m + cos * s * 0.30f, m + sin * s * 0.30f,
                                m + cos * s * 0.46f, m + sin * s * 0.46f, teeth);
                    }
                    c.drawCircle(m, m, s * 0.30f, fill);
                    c.drawCircle(m, m, s * 0.12f, cut);
                    break;
                }
                case Icon.SUBTITLE: { // CC
                    box.set(s * 0.06f, s * 0.2f, s * 0.94f, s * 0.8f);
                    c.drawRoundRect(box, s * 0.1f, s * 0.1f, fill);
                    cut.setTextSize(s * 0.36f);
                    c.drawText("CC", m, m + s * 0.13f, cut);
                    break;
                }
                case Icon.RESIZE: { // two outward arrows
                    c.drawLine(s * 0.2f, s * 0.8f, s * 0.8f, s * 0.2f, stroke);
                    path.moveTo(s * 0.52f, s * 0.2f); path.lineTo(s * 0.8f, s * 0.2f); path.lineTo(s * 0.8f, s * 0.48f);
                    path.moveTo(s * 0.2f, s * 0.52f); path.lineTo(s * 0.2f, s * 0.8f); path.lineTo(s * 0.48f, s * 0.8f);
                    c.drawPath(path, stroke);
                    break;
                }
                case Icon.SEEK: { // fast forward (points left in RTL reading = forward)
                    triangle(s * 0.06f, s * 0.5f, s * 0.5f, s * 0.2f, s * 0.5f, s * 0.8f);
                    triangle(s * 0.5f, s * 0.5f, s * 0.94f, s * 0.2f, s * 0.94f, s * 0.8f);
                    c.drawPath(path, fill);
                    break;
                }
                case Icon.SPEED: { // gauge
                    box.set(s * 0.1f, s * 0.14f, s * 0.9f, s * 0.94f);
                    c.drawArc(box, 160, 220, false, stroke);
                    c.drawLine(m, s * 0.54f, s * 0.68f, s * 0.34f, stroke);
                    c.drawCircle(m, s * 0.54f, s * 0.08f, fill);
                    break;
                }
                case Icon.SKIP: { // skip
                    triangle(s * 0.68f, s * 0.5f, s * 0.2f, s * 0.2f, s * 0.2f, s * 0.8f);
                    c.drawPath(path, fill);
                    c.drawLine(s * 0.8f, s * 0.22f, s * 0.8f, s * 0.78f, stroke);
                    break;
                }
                case Icon.AUTO_NEXT: { // play + list
                    triangle(s * 0.62f, s * 0.5f, s * 0.94f, s * 0.26f, s * 0.94f, s * 0.74f);
                    c.drawPath(path, fill);
                    c.drawLine(s * 0.06f, s * 0.28f, s * 0.46f, s * 0.28f, stroke);
                    c.drawLine(s * 0.06f, s * 0.5f, s * 0.46f, s * 0.5f, stroke);
                    c.drawLine(s * 0.06f, s * 0.72f, s * 0.46f, s * 0.72f, stroke);
                    break;
                }
                case Icon.CHEVRON: { // ‹ (forward in Arabic)
                    path.moveTo(s * 0.64f, s * 0.2f); path.lineTo(s * 0.34f, s * 0.5f); path.lineTo(s * 0.64f, s * 0.8f);
                    c.drawPath(path, stroke);
                    break;
                }
                case Icon.BACK: { // → (back in Arabic)
                    c.drawLine(s * 0.14f, m, s * 0.86f, m, stroke);
                    path.moveTo(s * 0.56f, s * 0.22f); path.lineTo(s * 0.86f, m); path.lineTo(s * 0.56f, s * 0.78f);
                    c.drawPath(path, stroke);
                    break;
                }
                case Icon.AUDIO: { // speaker + waves
                    path.moveTo(s * 0.10f, s * 0.38f); path.lineTo(s * 0.28f, s * 0.38f);
                    path.lineTo(s * 0.50f, s * 0.18f); path.lineTo(s * 0.50f, s * 0.82f);
                    path.lineTo(s * 0.28f, s * 0.62f); path.lineTo(s * 0.10f, s * 0.62f); path.close();
                    c.drawPath(path, fill);
                    box.set(s * 0.40f, s * 0.30f, s * 0.80f, s * 0.70f);
                    c.drawArc(box, -50, 100, false, stroke);
                    box.set(s * 0.32f, s * 0.14f, s * 0.96f, s * 0.86f);
                    c.drawArc(box, -50, 100, false, stroke);
                    break;
                }
                case Icon.TIMER: { // clock
                    c.drawCircle(m, s * 0.54f, s * 0.36f, stroke);
                    c.drawLine(m, s * 0.54f, m, s * 0.34f, stroke);
                    c.drawLine(m, s * 0.54f, s * 0.66f, s * 0.62f, stroke);
                    c.drawLine(s * 0.40f, s * 0.08f, s * 0.60f, s * 0.08f, stroke);
                    break;
                }
                case Icon.INFO: { // (i)
                    c.drawCircle(m, m, s * 0.40f, stroke);
                    c.drawLine(m, s * 0.46f, m, s * 0.72f, stroke);
                    c.drawCircle(m, s * 0.31f, s * 0.06f, fill);
                    break;
                }
                case Icon.TEXT_SIZE: { // Aa
                    cut.setColor(stroke.getColor());
                    cut.setTextSize(s * 0.62f);
                    c.drawText("A", s * 0.34f, s * 0.76f, cut);
                    cut.setTextSize(s * 0.40f);
                    c.drawText("a", s * 0.76f, s * 0.76f, cut);
                    cut.setColor(ROW);
                    break;
                }
                case Icon.CHECK: {
                    path.moveTo(s * 0.16f, s * 0.52f); path.lineTo(s * 0.4f, s * 0.76f); path.lineTo(s * 0.86f, s * 0.26f);
                    c.drawPath(path, stroke);
                    break;
                }
                default:
                    break;
            }
            c.restore();
        }

        private void triangle(float x1, float y1, float x2, float y2, float x3, float y3) {
            path.moveTo(x1, y1);
            path.lineTo(x2, y2);
            path.lineTo(x3, y3);
            path.close();
        }
    }
}
