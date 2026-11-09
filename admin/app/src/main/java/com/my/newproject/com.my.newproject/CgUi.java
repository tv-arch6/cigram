package com.my.newproject;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** UI building blocks for the Cigram admin night theme (navy, blue, orange, Cairo font, RTL). */
final class CgUi {

    private CgUi() {}

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    interface Callback<T> {
        void run(T value);
    }

    private static Typeface font;
    private static Typeface fontBold;
    private static boolean fontTried;
    private static final String FONT_ASSET = "fonts/Alyamama.ttf";

    /**
     * App-wide UI typeface: Alyamama (variable weight font, regular = wght 400, bold = wght 700 on Android 8+; synthetic
     * bold before that). Falls back to the device default only if the asset is missing.
     */
    static synchronized Typeface font(Context c) {
        if (!fontTried) {
            fontTried = true;
            android.content.res.AssetManager am = c.getApplicationContext().getAssets();
            Typeface base = null;
            try {
                base = Typeface.createFromAsset(am, FONT_ASSET);
            } catch (Exception e) {
                base = null;
            }
            font = base;
            fontBold = base;
            if (base != null) {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    try {
                        font = new Typeface.Builder(am, FONT_ASSET).setFontVariationSettings("'wght' 400").build();
                        fontBold = new Typeface.Builder(am, FONT_ASSET).setFontVariationSettings("'wght' 700").build();
                    } catch (Throwable t) {
                        font = base;
                        fontBold = Typeface.create(base, Typeface.BOLD);
                    }
                } else {
                    fontBold = Typeface.create(base, Typeface.BOLD);
                }
                if (font == null) font = base;
                if (fontBold == null) fontBold = base;
            }
        }
        return font;
    }

    /**
     * Puts the app font on EVERY TextView under {@code root} (labels, buttons, inputs, dialogs) and keeps doing so for views
     * added later. Bold stays bold. This is what makes the font apply "without exception", not only where applyFont was called.
     */
    static void applyTree(View root) {
        if (root == null) return;
        Context c = root.getContext();
        Typeface reg = font(c);
        if (reg == null) return;
        Typeface bold = fontBold(c);
        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            Typeface cur = tv.getTypeface();
            if (cur != reg && cur != bold) tv.setTypeface(cur != null && cur.isBold() ? bold : reg, Typeface.NORMAL);
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) applyTree(g.getChildAt(i));
            g.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View child) { applyTree(child); }
                @Override public void onChildViewRemoved(View parent, View child) { }
            });
        }
    }

    /** The real Cairo Bold face (crisper than faux-bolding the regular weight). */
    static Typeface fontBold(Context c) {
        font(c); // ensures both are loaded together
        return fontBold;
    }

    static void applyFont(TextView tv, boolean bold) {
        Context c = tv.getContext();
        Typeface f = bold ? fontBold(c) : font(c);
        if (f != null) {
            tv.setTypeface(f, Typeface.NORMAL); // the weight is already baked into the chosen face
        } else {
            tv.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * The one shape factory of the whole admin. Flat and round: corners are softened (x1.35, never a sharp box), and the
     * neutral hairline border (CgCfg.STROKE) is dropped, so cards, fields and buttons are plain tinted surfaces.
     * Only coloured borders (selection / status) are kept.
     */
    static GradientDrawable bg(Context c, int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp <= 0 ? 0 : Math.min(radiusDp * 1.35f, 30f)));
        if (stroke != 0 && stroke != CgCfg.STROKE && (stroke >>> 24) > 0x40) g.setStroke(Math.max(1, dp(c, 1)), stroke);
        return g;
    }

    /** Diagonal two-colour gradient surface (top-start → bottom-end), same rounding rule as {@link #bg}. */
    static GradientDrawable gradient(Context c, int from, int to, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{from, to});
        g.setCornerRadius(dp(c, radiusDp <= 0 ? 0 : Math.min(radiusDp * 1.35f, 30f)));
        return g;
    }

    /** Soft elevation (rounded shadow) on any view; the view's rounded background becomes the shadow outline. */
    static void elevate(View v, float dp) {
        try {
            v.setElevation(dp(v.getContext(), dp));
            v.setClipToOutline(true);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                int sh = 0xFF000000;
                v.setOutlineAmbientShadowColor(sh);
                v.setOutlineSpotShadowColor(sh);
            }
        } catch (Throwable ignored) { }
    }

    /** Lets a container cast/receive child shadows without clipping them at its edges. */
    static void unclip(ViewGroup g) {
        try {
            g.setClipChildren(false);
            g.setClipToPadding(false);
        } catch (Throwable ignored) { }
    }

    static int levelColor(int level) {
        if (level == 0) return CgCfg.GOOD;
        if (level == 1) return CgCfg.WARN;
        if (level == 2) return CgCfg.BAD;
        return CgCfg.GRAY;
    }

    static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    // ----------------------------------------------------- glyph -> icon

    private static int glyphIcon(int cp) {
        switch (cp) {
            case 0x2714: case 0x2713: case 0x2705: case 0x2611: return CgIcons.CHECK;
            case 0x2716: case 0x2715: case 0x274C: return CgIcons.CLOSE;
            case 0x26A0: return CgIcons.WARNING;
            case 0x2139: return CgIcons.INFO;
            case 0x2605: return CgIcons.STAR;
            case 0x2610: return CgIcons.BOX;
            case 0x1F4CE: return CgIcons.CLIP;
            case 0x1F4B0: return CgIcons.CHART;
            case 0x27A4: return CgIcons.SEND;
            case 0x1F3A4: return CgIcons.MIC;
            case 0x21A9: case 0x21BB: return CgIcons.REFRESH;
            case 0x23F8: case 0x275A: return CgIcons.PAUSE;
            case 0x23F3: return CgIcons.HISTORY;
            case 0x2795: return CgIcons.PLUS;
            case 0x2B06: return CgIcons.UPLOAD;
            case 0x1F512: return CgIcons.LOCK;
            case 0x1F4C1: case 0x1F5C2: return CgIcons.FOLDER;
            case 0x1F50E: case 0x1F50D: return CgIcons.SEARCH;
            case 0x1F4DD: return CgIcons.DRAFT;
            case 0x1F3AC: return CgIcons.FILM;
            case 0x1F4FA: return CgIcons.TV;
            case 0x1F5BC: return CgIcons.IMAGE;
            case 0x1F4E1: return CgIcons.SIGNAL;
            case 0x25B6: return CgIcons.PLAY;
            default: return -1;
        }
    }

    /** Page-colored status/navigation bars and window background (no white strips), light icons. */
    static void paintSystemBars(Activity a) {
        try {
            android.view.Window w = a.getWindow();
            w.setBackgroundDrawable(new ColorDrawable(CgCfg.PAGE));
            w.setStatusBarColor(CgCfg.PAGE);
            w.setNavigationBarColor(CgCfg.PAGE);
            int f = w.getDecorView().getSystemUiVisibility();
            f &= ~0x2000;   // light-status-bar flag off -> white status icons on the dark page
            f &= ~0x10;     // light-navigation-bar flag off -> white navigation icons
            w.getDecorView().setSystemUiVisibility(f);
        } catch (Throwable ignored) { }
    }

    /** setText that turns glyphs into vector icons using the view's current color and size. */
    static void set(TextView tv, CharSequence s) {
        float sp = tv.getTextSize() / tv.getResources().getDisplayMetrics().scaledDensity;
        tv.setText(rich(tv.getContext(), s, tv.getCurrentTextColor(), sp));
    }

    /** Replaces emoji / symbol glyphs in the text with real vector icons in the given color. */
    static CharSequence rich(Context c, CharSequence s, int color, float sp) {
        if (s == null) return s;
        String str = s.toString();
        boolean any = false;
        for (int i = 0; i < str.length(); ) {
            int cp = str.codePointAt(i);
            if (glyphIcon(cp) >= 0) { any = true; break; }
            i += Character.charCount(cp);
        }
        if (!any) return s;
        int px = (int) (sp * c.getResources().getDisplayMetrics().scaledDensity * 1.2f);
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        for (int i = 0; i < str.length(); ) {
            int cp = str.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0xFE0F || cp == 0x200D) continue;
            int kind = glyphIcon(cp);
            if (kind < 0) {
                out.append(new String(Character.toChars(cp)));
                continue;
            }
            int start = out.length();
            out.append('\uFFFC');
            android.graphics.drawable.Drawable d = CgIcons.icon(kind, color);
            d.setBounds(0, 0, px, px);
            out.setSpan(new android.text.style.ImageSpan(d, android.text.style.ImageSpan.ALIGN_BOTTOM),
                    start, start + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    // ------------------------------------------------------------- layout

    static LinearLayout.LayoutParams lp(Context c, int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static LinearLayout.LayoutParams lpWeight(Context c, float weight, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, WRAP, weight);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = vbox(c);
        l.setBackground(bg(c, CgCfg.CARD, 18, CgCfg.STROKE));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        elevate(l, 3);
        return l;
    }

    static View space(Context c, float heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(MATCH, dp(c, heightDp)));
        return v;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(CgCfg.STROKE);
        v.setLayoutParams(lp(c, MATCH, 1, 0, 8, 0, 8));
        return v;
    }

    // --------------------------------------------------------------- text

    static TextView text(Context c, CharSequence t, float sp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(rich(c, t, color, sp));
        tv.setTextSize(sp);
        tv.setTextColor(color);
        applyFont(tv, bold);
        tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        // Latin titles (e.g. "Little Lorraine") must align to the right edge like Arabic ones in this RTL UI.
        tv.setTextDirection(View.TEXT_DIRECTION_RTL);
        return tv;
    }

    static TextView title(Context c, CharSequence t) {
        return text(c, t, 17, CgCfg.TEXT, true);
    }

    static TextView muted(Context c, CharSequence t) {
        return text(c, t, 12.5f, CgCfg.MUTED, false);
    }

    static TextView sectionTitle(Context c, CharSequence t) {
        TextView tv = text(c, t, 15, CgCfg.TEXT, true);
        GradientDrawable bar = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{CgCfg.ACCENT_G1, CgCfg.ACCENT_G2});
        bar.setCornerRadius(dp(c, 2));
        bar.setSize(dp(c, 4), dp(c, 18));
        tv.setCompoundDrawablesRelative(bar, null, null, null);
        tv.setCompoundDrawablePadding(dp(c, 8));
        tv.setLayoutParams(lp(c, MATCH, WRAP, 4, 16, 4, 8));
        return tv;
    }

    static void ltr(TextView tv) {
        tv.setTextDirection(View.TEXT_DIRECTION_LTR);
    }

    // ------------------------------------------------------------ buttons

    /** style: 0 primary, 1 secondary, 2 danger, 3 ghost */
    static TextView button(Context c, CharSequence label, int style) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        applyFont(b, true);
        int fill;
        int stroke;
        int pressed;
        int textColor = CgCfg.TEXT;
        if (style == 0) {
            fill = CgCfg.ACCENT_D;
            stroke = 0;
            pressed = CgCfg.ACCENT_P;
            textColor = CgCfg.ON_ACCENT;
        } else if (style == 2) {
            fill = alpha(CgCfg.BAD, 0x1A);
            stroke = CgCfg.BAD;
            pressed = alpha(CgCfg.BAD, 0x38);
            textColor = CgCfg.BAD;
        } else if (style == 3) {
            fill = 0;
            stroke = 0;
            pressed = alpha(CgCfg.TEXT, 0x1A);
            textColor = CgCfg.TEXT;
        } else {
            fill = CgCfg.CARD2;
            stroke = CgCfg.STROKE;
            pressed = CgCfg.STROKE;
        }
        b.setTextColor(textColor);
        b.setText(rich(c, label, textColor, 14));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, bg(c, pressed, 14, stroke));
        sl.addState(new int[]{}, style == 0 ? gradient(c, CgCfg.ACCENT_G1, CgCfg.ACCENT_G2, 14) : bg(c, fill, 14, stroke));
        b.setBackground(sl);
        b.setPadding(dp(c, 16), dp(c, 13), dp(c, 16), dp(c, 13));
        b.setMinHeight(dp(c, 52));
        if (style == 0) elevate(b, 4);
        pressScale(b);
        return b;
    }

    static TextView smallButton(Context c, CharSequence label, int style) {
        TextView b = button(c, label, style);
        b.setTextSize(12.5f);
        b.setMinHeight(dp(c, 38));
        b.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        return b;
    }

    /** Soft press feedback (a 3% shrink) on anything clickable: the whole admin feels responsive. */
    static void pressScale(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, android.view.MotionEvent e) {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(70).start();
                else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                return false;
            }
        });
    }

    static TextView chip(Context c, CharSequence label, boolean selected) {
        TextView t = new TextView(c);
        t.setText(rich(c, label, selected ? CgCfg.ON_ACCENT : CgCfg.TEXT, 12.5f));
        t.setTextSize(12.5f);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        applyFont(t, selected);
        setChipState(t, selected);
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        return t;
    }

    static void setChipState(TextView t, boolean selected) {
        Context c = t.getContext();
        if (selected) {
            t.setBackground(gradient(c, CgCfg.ACCENT_G1, CgCfg.ACCENT_G2, 20));
            t.setTextColor(CgCfg.ON_ACCENT);
        } else {
            t.setBackground(bg(c, CgCfg.CARD2, 20, CgCfg.STROKE));
            t.setTextColor(CgCfg.TEXT);
        }
    }

    static TextView badge(Context c, CharSequence label, int color) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(11.5f);
        t.setTextColor(color);
        t.setText(rich(c, label, color, 11.5f));
        t.setGravity(Gravity.CENTER);
        applyFont(t, true);
        t.setBackground(bg(c, alpha(color, 0x1F), 8, alpha(color, 0x88)));
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        return t;
    }

    // ------------------------------------------------------------- fields

    static EditText field(Context c, String hint, boolean multiline) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(alpha(CgCfg.MUTED, 0xB3));
        e.setTextColor(CgCfg.TEXT);
        e.setHighlightColor(alpha(CgCfg.ACCENT, 0x55));
        e.setTextSize(14);
        applyFont(e, false);
        e.setBackground(bg(c, CgCfg.FIELD, 12, CgCfg.STROKE));
        e.setPadding(dp(c, 12), dp(c, 11), dp(c, 12), dp(c, 11));
        e.setGravity(Gravity.START | (multiline ? Gravity.TOP : Gravity.CENTER_VERTICAL));
        if (multiline) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setMinLines(3);
            e.setMaxLines(8);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT);
            e.setSingleLine(true);
        }
        return e;
    }

    static EditText urlField(Context c, String hint) {
        EditText e = field(c, hint, false);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setTextDirection(View.TEXT_DIRECTION_LTR);
        return e;
    }

    static EditText numberField(Context c, String hint) {
        EditText e = field(c, hint, false);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setTextDirection(View.TEXT_DIRECTION_LTR);
        return e;
    }

    static LinearLayout labeled(Context c, String label, View input) {
        LinearLayout l = vbox(c);
        TextView t = text(c, label, 12, CgCfg.MUTED, false);
        t.setPadding(dp(c, 4), 0, dp(c, 4), dp(c, 4));
        l.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
        l.addView(input, new LinearLayout.LayoutParams(MATCH, WRAP));
        l.setLayoutParams(lp(c, MATCH, WRAP, 0, 0, 0, 10));
        return l;
    }

    /** Pill style on/off toggle with a label. */
    static final class Toggle extends LinearLayout {
        boolean on;
        private final TextView pill;
        private Runnable change;

        Toggle(Context c, String label, boolean initial) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            on = initial;
            TextView l = text(c, label, 14, CgCfg.TEXT, false);
            addView(l, new LinearLayout.LayoutParams(0, WRAP, 1f));
            pill = new TextView(c);
            pill.setGravity(Gravity.CENTER);
            pill.setTextSize(12.5f);
            applyFont(pill, true);
            pill.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
            addView(pill, new LinearLayout.LayoutParams(WRAP, WRAP));
            refresh();
            setPadding(0, dp(c, 4), 0, dp(c, 4));
            setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    set(!on);
                }
            });
        }

        void set(boolean v) {
            on = v;
            refresh();
            if (change != null) change.run();
        }

        void silent(boolean v) {
            on = v;
            refresh();
        }

        void onChange(Runnable r) {
            change = r;
        }

        private void refresh() {
            Context c = getContext();
            pill.setText(on ? "مفعّل" : "متوقف");
            pill.setTextColor(on ? CgCfg.ON_ACCENT : CgCfg.MUTED);
            pill.setBackground(bg(c, on ? CgCfg.ACCENT_D : CgCfg.CARD2, 20, on ? CgCfg.ACCENT : CgCfg.STROKE));
        }
    }

    static Toggle toggle(Context c, String label, boolean initial) {
        return new Toggle(c, label, initial);
    }

    // -------------------------------------------------------- composite UI

    static View empty(Context c, String title, String sub) {
        LinearLayout l = vbox(c);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(dp(c, 20), dp(c, 40), dp(c, 20), dp(c, 40));
        TextView icon = text(c, "∅", 38, CgCfg.STROKE, true);
        icon.setGravity(Gravity.CENTER);
        l.addView(icon, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView t = text(c, title, 16, CgCfg.TEXT, true);
        t.setGravity(Gravity.CENTER);
        l.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
        if (sub != null && sub.length() > 0) {
            TextView s = muted(c, sub);
            s.setGravity(Gravity.CENTER);
            s.setPadding(0, dp(c, 6), 0, 0);
            l.addView(s, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        return l;
    }

    static View errorCard(Context c, String message, final Runnable retry) {
        LinearLayout card = card(c);
        card.setBackground(bg(c, alpha(CgCfg.BAD, 0x1A), 14, CgCfg.BAD));
        card.addView(text(c, "تعذر تحميل البيانات", 15, CgCfg.BAD, true));
        TextView m = muted(c, message);
        m.setPadding(0, dp(c, 6), 0, dp(c, 10));
        card.addView(m);
        if (retry != null) {
            TextView b = button(c, "إعادة المحاولة", 1);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    retry.run();
                }
            });
            card.addView(b, new LinearLayout.LayoutParams(WRAP, WRAP));
        }
        return card;
    }

    static ProgressBar spinner(Context c) {
        ProgressBar p = new ProgressBar(c);
        try {
            p.getIndeterminateDrawable().setColorFilter(CgCfg.ACCENT, PorterDuff.Mode.SRC_IN);
        } catch (Exception ignored) { }
        return p;
    }

    /** Horizontal scrolling chip row. inner is where chips are added. */
    static final class ChipBar {
        final HorizontalScrollView scroll;
        final LinearLayout inner;

        ChipBar(Context c) {
            scroll = new HorizontalScrollView(c);
            scroll.setHorizontalScrollBarEnabled(false);
            inner = new LinearLayout(c);
            inner.setOrientation(LinearLayout.HORIZONTAL);
            inner.setPadding(dp(c, 12), dp(c, 4), dp(c, 12), dp(c, 4));
            scroll.addView(inner, new ViewGroup.LayoutParams(WRAP, WRAP));
        }

        TextView add(String label, boolean selected) {
            Context c = inner.getContext();
            TextView t = chip(c, label, selected);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
            p.setMargins(dp(c, 3), 0, dp(c, 3), 0);
            inner.addView(t, p);
            return t;
        }
    }

    // ---------------------------------------------------------- toolbar

    static final class Toolbar {
        final LinearLayout view;
        final TextView titleView;
        final TextView subtitleView;
        final LinearLayout actions;

        Toolbar(final Activity a, String title, boolean back) {
            view = hbox(a);
            view.setBackgroundColor(CgCfg.PAGE);                      // the toolbar is part of the page: no bar, no line
            view.setPadding(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 6));
            if (back) {
                TextView b = text(a, "→", 22, CgCfg.TEXT, true);
                b.setGravity(Gravity.CENTER);
                b.setClickable(true);
                b.setBackground(bg(a, CgCfg.CARD2, 40, 0));              // round tonal back button
                b.setPadding(0, 0, 0, 0);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        a.finish();
                    }
                });
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(a, 44), dp(a, 44));
                bp.setMarginEnd(dp(a, 10));
                view.addView(b, bp);
            }
            LinearLayout mid = vbox(a);
            titleView = text(a, title, 21, CgCfg.TEXT, true);
            mid.addView(titleView, new LinearLayout.LayoutParams(MATCH, WRAP));
            subtitleView = muted(a, "");
            subtitleView.setVisibility(View.GONE);
            mid.addView(subtitleView, new LinearLayout.LayoutParams(MATCH, WRAP));
            view.addView(mid, new LinearLayout.LayoutParams(0, WRAP, 1f));
            actions = hbox(a);
            view.addView(actions, new LinearLayout.LayoutParams(WRAP, WRAP));
        }

        TextView action(String label, final Runnable r) {
            Context c = view.getContext();
            TextView t = smallButton(c, label, 3);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    r.run();
                }
            });
            actions.addView(t, new LinearLayout.LayoutParams(WRAP, WRAP));
            return t;
        }

        void subtitle(String s) {
            if (s == null || s.length() == 0) {
                subtitleView.setVisibility(View.GONE);
            } else {
                subtitleView.setText(s);
                subtitleView.setVisibility(View.VISIBLE);
            }
        }
    }

    // ----------------------------------------------------------- dialogs

    static AlertDialog.Builder dialog(Activity a) {
        return new CgDialog.Builder(a);
    }

    static void toast(Context c, String msg) {
        // a toast in the app font; falls back to the system toast where custom toast views are not allowed
        try {
            TextView t = text(c, msg, 13.5f, CgCfg.TEXT, false);
            t.setBackground(bg(c, CgCfg.CARD3, 14, CgCfg.ACCENT));
            t.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
            t.setMaxWidth(dp(c, 320));
            Toast toast = new Toast(c.getApplicationContext());
            toast.setDuration(Toast.LENGTH_LONG);
            toast.setView(t);
            toast.show();
        } catch (Throwable e) {
            Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
        }
    }

    static void info(Activity a, String title, String msg) {
        dialog(a).setTitle(title).setMessage(msg).setPositiveButton("حسناً", null).show();
    }

    static void confirm(Activity a, String title, String msg, String yes, boolean danger, final Runnable onYes) {
        AlertDialog d = dialog(a).setTitle(title).setMessage(msg)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton(yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        onYes.run();
                    }
                }).create();
        d.show();
        if (danger) {
            try {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFFFFFFFF);
                d.getButton(AlertDialog.BUTTON_POSITIVE).setBackground(bg(a, CgCfg.BAD, 12, 0));
            } catch (Exception ignored) { }
        }
    }

    static void input(Activity a, String title, String hint, String initial, boolean multiline, final Callback<String> cb) {
        final EditText e = field(a, hint, multiline);
        if (initial != null) e.setText(initial);
        LinearLayout box = vbox(a);
        box.setPadding(dp(a, 18), dp(a, 8), dp(a, 18), 0);
        box.addView(e, new LinearLayout.LayoutParams(MATCH, WRAP));
        dialog(a).setTitle(title).setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        cb.run(e.getText().toString().trim());
                    }
                }).show();
    }

    interface IntCb {
        void run(int index);
    }

    static void choose(Activity a, String title, CharSequence[] labels, final IntCb cb) {
        dialog(a).setTitle(title).setItems(labels, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                cb.run(which);
            }
        }).setNegativeButton("إلغاء", null).show();
    }

    // -------------------------------------------------------- date / time

    private static SimpleDateFormat isoFormat(boolean millis) {
        SimpleDateFormat f = new SimpleDateFormat(millis ? "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" : "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    /** @return epoch millis or -1 */
    static long parseIso(String iso) {
        if (iso == null || iso.trim().length() == 0) return -1;
        String s = iso.trim();
        try {
            return isoFormat(false).parse(s).getTime();
        } catch (Exception ignored) { }
        try {
            return isoFormat(true).parse(s).getTime();
        } catch (Exception ignored) { }
        return -1;
    }

    static String toIso(long ms) {
        return isoFormat(false).format(new Date(ms));
    }

    static String localText(String iso) {
        long ms = parseIso(iso);
        if (ms < 0) return iso == null ? "" : iso;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        return f.format(new Date(ms));
    }

    static String ago(String iso) {
        long ms = parseIso(iso);
        if (ms < 0) return "";
        long diff = (System.currentTimeMillis() - ms) / 1000;
        if (diff < 60) return "الآن";
        if (diff < 3600) return "منذ " + (diff / 60) + " دقيقة";
        if (diff < 86400) return "منذ " + (diff / 3600) + " ساعة";
        return "منذ " + (diff / 86400) + " يوم";
    }

    /** Date then time pickers. Returns ISO UTC string. */
    static void pickDateTime(final Activity a, String initialIso, final Callback<String> cb) {
        final Calendar cal = Calendar.getInstance();
        long init = parseIso(initialIso);
        if (init > 0) cal.setTimeInMillis(init);
        new DatePickerDialog(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int month, int day) {
                cal.set(Calendar.YEAR, year);
                cal.set(Calendar.MONTH, month);
                cal.set(Calendar.DAY_OF_MONTH, day);
                new TimePickerDialog(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker v, int hour, int minute) {
                        cal.set(Calendar.HOUR_OF_DAY, hour);
                        cal.set(Calendar.MINUTE, minute);
                        cal.set(Calendar.SECOND, 0);
                        cb.run(toIso(cal.getTimeInMillis()));
                    }
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show();
            }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ------------------------------------------------------------- misc

    static ColorDrawable color(int c) {
        return new ColorDrawable(c);
    }

    static String sizeText(long bytes) {
        if (bytes <= 0) return "—";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024) return String.format(Locale.US, "%.2f GB", mb / 1024.0);
        if (mb >= 1) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
    }

    static String durationText(long ms) {
        if (ms <= 0) return "—";
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%d:%02d", m, sec);
    }
}
