package com.my.newproject;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Widgets shared by the update screens: styled fields, tabs, progress bar, collapsible sections,
 * icon buttons, empty states. Same colors and font as the rest of the admin app.
 */
final class CgUpdateUi {

    private CgUpdateUi() {}

    // ------------------------------------------------------------ small helpers

    static ImageView icon(Context c, int kind, int color, float sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(CgIcons.icon(kind, color));
        v.setLayoutParams(new LinearLayout.LayoutParams(CgUi.dp(c, sizeDp), CgUi.dp(c, sizeDp)));
        return v;
    }

    /** Round tinted badge with an icon inside. */
    static FrameLayout iconCircle(Context c, int kind, int color, float sizeDp, float iconDp) {
        FrameLayout f = new FrameLayout(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(CgUi.alpha(color, 0x26));
        f.setBackground(g);
        ImageView i = new ImageView(c);
        i.setImageDrawable(CgIcons.icon(kind, color));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(CgUi.dp(c, iconDp), CgUi.dp(c, iconDp), Gravity.CENTER);
        f.addView(i, lp);
        f.setLayoutParams(new LinearLayout.LayoutParams(CgUi.dp(c, sizeDp), CgUi.dp(c, sizeDp)));
        return f;
    }

    static View dot(Context c, int color, float sizeDp) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        v.setBackground(g);
        v.setLayoutParams(new LinearLayout.LayoutParams(CgUi.dp(c, sizeDp), CgUi.dp(c, sizeDp)));
        return v;
    }

    /** Subtle press feedback (scale) that never swallows the click. */
    static void press(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent e) {
                int a = e.getAction();
                if (a == MotionEvent.ACTION_DOWN) {
                    view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start();
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                }
                return false;
            }
        });
    }

    static void fadeIn(View v) {
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(200).start();
    }

    /** Two-column row: label at the start, value at the end. */
    static View kv(Context c, String key, String value, int valueColor, boolean ltrValue) {
        LinearLayout row = CgUi.hbox(c);
        row.setPadding(0, CgUi.dp(c, 5), 0, CgUi.dp(c, 5));
        TextView k = CgUi.text(c, key, 13, CgCfg.MUTED, false);
        row.addView(k, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        TextView v = CgUi.text(c, value, 13.5f, valueColor, true);
        v.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        if (ltrValue) CgUi.ltr(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        lp.setMargins(CgUi.dp(c, 12), 0, 0, 0);
        row.addView(v, lp);
        return row;
    }

    /** Tinted note with an icon (warnings, hints). */
    static View note(Context c, int iconKind, String text, int color) {
        LinearLayout box = CgUi.hbox(c);
        box.setBackground(CgUi.bg(c, CgUi.alpha(color, 0x1A), 12, CgUi.alpha(color, 0x77)));
        int p = CgUi.dp(c, 10);
        box.setPadding(p, p, p, p);
        ImageView i = icon(c, iconKind, color, 18);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(CgUi.dp(c, 18), CgUi.dp(c, 18));
        ip.setMargins(0, 0, 0, 0);
        box.addView(i, ip);
        TextView t = CgUi.text(c, text, 12.5f, color, false);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        tp.setMargins(CgUi.dp(c, 8), 0, 0, 0);
        box.addView(t, tp);
        box.setLayoutParams(CgUi.lp(c, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 8));
        return box;
    }

    /** Empty state with an icon, a title and one line of explanation. */
    static View empty(Context c, int iconKind, String title, String sub) {
        LinearLayout l = CgUi.vbox(c);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(CgUi.dp(c, 16), CgUi.dp(c, 18), CgUi.dp(c, 16), CgUi.dp(c, 18));
        FrameLayout circle = iconCircle(c, iconKind, CgCfg.MUTED, 52, 26);
        l.addView(circle);
        TextView t = CgUi.text(c, title, 14.5f, CgCfg.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, CgUi.dp(c, 10), 0, 0);
        l.addView(t, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        if (sub != null && sub.length() > 0) {
            TextView s = CgUi.muted(c, sub);
            s.setGravity(Gravity.CENTER);
            s.setPadding(0, CgUi.dp(c, 4), 0, 0);
            l.addView(s, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }
        return l;
    }

    // ------------------------------------------------------------ scroll with a cap

    static final class MaxHeightScroll extends ScrollView {
        private final int max;

        MaxHeightScroll(Context c, int maxPx) {
            super(c);
            max = maxPx;
            setVerticalScrollBarEnabled(false);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int size = MeasureSpec.getSize(h);
            int cap = max > 0 ? (size > 0 ? Math.min(max, size) : max) : size;
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
        }
    }

    // ------------------------------------------------------------------ button

    /** Button with an optional icon. style: 0 primary, 1 secondary, 2 danger, 3 ghost. */
    static final class Btn extends LinearLayout {
        private final TextView label;
        private final ImageView iconView;
        private final int fgColor;
        private boolean busy;

        Btn(Context c, String text, int iconKind, int style) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            setClickable(true);
            int fill;
            int stroke;
            int pressed;
            int fg = CgCfg.TEXT;
            if (style == 0) {
                fill = CgCfg.ACCENT_D;
                stroke = 0;
                pressed = CgCfg.ACCENT_P;
                fg = CgCfg.ON_ACCENT;
            } else if (style == 2) {
                fill = CgUi.alpha(CgCfg.BAD, 0x1A);
                stroke = CgCfg.BAD;
                pressed = CgUi.alpha(CgCfg.BAD, 0x38);
                fg = CgCfg.BAD;
            } else if (style == 3) {
                fill = 0;
                stroke = 0;
                pressed = CgUi.alpha(CgCfg.TEXT, 0x1A);
            } else {
                fill = CgCfg.CARD2;
                stroke = CgCfg.STROKE;
                pressed = CgCfg.STROKE;
            }
            StateListDrawable sl = new StateListDrawable();
            sl.addState(new int[]{android.R.attr.state_pressed}, CgUi.bg(c, pressed, 14, stroke));
            sl.addState(new int[]{}, CgUi.bg(c, fill, 14, stroke));
            fgColor = fg;
            setBackground(sl);
            setPadding(CgUi.dp(c, 14), CgUi.dp(c, 11), CgUi.dp(c, 14), CgUi.dp(c, 11));
            setMinimumHeight(CgUi.dp(c, 46));
            if (iconKind >= 0) {
                iconView = icon(c, iconKind, fg, 18);
                LayoutParams ip = new LayoutParams(CgUi.dp(c, 18), CgUi.dp(c, 18));
                ip.setMargins(0, 0, CgUi.dp(c, 8), 0);
                addView(iconView, ip);
            } else {
                iconView = null;
            }
            label = CgUi.text(c, text, 14, fg, true);
            label.setGravity(Gravity.CENTER);
            addView(label, new LayoutParams(CgUi.WRAP, CgUi.WRAP));
            press(this);
        }

        void setLabel(String t) {
            label.setText(t);
        }

        /** Swaps the icon (only for buttons created with one). */
        void setIcon(int kind) {
            if (iconView != null) iconView.setImageDrawable(CgIcons.icon(kind, fgColor));
        }

        /** While busy the button ignores taps (no double publish) and looks dimmed. */
        void setBusy(boolean b, String busyText) {
            busy = b;
            setEnabled(!b);
            setAlpha(b ? 0.55f : 1f);
            if (busyText != null) label.setText(busyText);
        }

        boolean busy() {
            return busy;
        }
    }

    static Btn btn(Context c, String text, int iconKind, int style) {
        return new Btn(c, text, iconKind, style);
    }

    // ------------------------------------------------------------------- field

    static final int K_TEXT = 0;
    static final int K_NUMBER = 1;
    static final int K_URL = 2;
    static final int K_MULTI = 3;
    static final int K_NAME = 4;

    /** Label + input with accent border on focus, red border and message on error. */
    static final class Field {
        final LinearLayout view;
        final EditText edit;
        private final TextView errorView;
        private final TextView helpView;
        private final Context c;
        private boolean error;
        private boolean focused;
        private boolean silent;
        private Runnable change;

        Field(Context ctx, String label, String hint, int kind, int maxLen) {
            c = ctx;
            view = CgUi.vbox(c);
            TextView l = CgUi.text(c, label, 12.5f, CgCfg.MUTED, false);
            l.setPadding(CgUi.dp(c, 4), 0, CgUi.dp(c, 4), CgUi.dp(c, 5));
            view.addView(l, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

            edit = new EditText(c);
            edit.setHint(hint);
            edit.setHintTextColor(CgUi.alpha(CgCfg.MUTED, 0xB3));
            edit.setTextColor(CgCfg.TEXT);
            edit.setTextSize(14);
            edit.setHighlightColor(CgUi.alpha(CgCfg.ACCENT, 0x55));
            CgUi.applyFont(edit, false);
            edit.setPadding(CgUi.dp(c, 14), CgUi.dp(c, 12), CgUi.dp(c, 14), CgUi.dp(c, 12));
            if (kind == K_MULTI) {
                edit.setGravity(Gravity.START | Gravity.TOP);
                edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                edit.setMinLines(3);
                edit.setMaxLines(8);
            } else {
                edit.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                edit.setSingleLine(true);
                if (kind == K_NUMBER) {
                    edit.setInputType(InputType.TYPE_CLASS_NUMBER);
                    edit.setTextDirection(View.TEXT_DIRECTION_LTR);
                } else if (kind == K_URL) {
                    edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
                    edit.setTextDirection(View.TEXT_DIRECTION_LTR);
                } else if (kind == K_NAME) {
                    edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                    edit.setTextDirection(View.TEXT_DIRECTION_LTR);
                } else {
                    edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                }
            }
            if (maxLen > 0) edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLen)});
            view.addView(edit, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

            helpView = CgUi.text(c, "", 11.5f, CgCfg.MUTED, false);
            helpView.setPadding(CgUi.dp(c, 4), CgUi.dp(c, 4), CgUi.dp(c, 4), 0);
            helpView.setVisibility(View.GONE);
            view.addView(helpView, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

            errorView = CgUi.text(c, "", 12, CgCfg.BAD, false);
            errorView.setPadding(CgUi.dp(c, 4), CgUi.dp(c, 4), CgUi.dp(c, 4), 0);
            errorView.setVisibility(View.GONE);
            view.addView(errorView, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

            view.setLayoutParams(CgUi.lp(c, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));
            style();

            edit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    focused = hasFocus;
                    style();
                }
            });
            edit.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int d) { }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int d) { }

                @Override
                public void afterTextChanged(Editable s) {
                    if (error) clearError();
                    if (!silent && change != null) change.run();
                }
            });
        }

        private void style() {
            int color = error ? CgCfg.BAD : (focused ? CgCfg.ACCENT : CgCfg.STROKE);
            GradientDrawable g = CgUi.bg(c, CgCfg.FIELD, 14, 0);
            g.setStroke(CgUi.dp(c, (focused || error) ? 1.6f : 1f), color);
            edit.setBackground(g);
        }

        String text() {
            return edit.getText().toString().trim();
        }

        /** Sets the text without notifying the change listener. */
        void set(String s) {
            silent = true;
            edit.setText(s == null ? "" : s);
            silent = false;
        }

        void help(String s) {
            if (s == null || s.length() == 0) {
                helpView.setVisibility(View.GONE);
            } else {
                helpView.setText(s);
                helpView.setVisibility(View.VISIBLE);
            }
        }

        void error(String msg) {
            error = true;
            errorView.setText(msg);
            errorView.setVisibility(View.VISIBLE);
            style();
        }

        void clearError() {
            if (!error) return;
            error = false;
            errorView.setVisibility(View.GONE);
            style();
        }

        boolean hasError() {
            return error;
        }

        void onChange(Runnable r) {
            change = r;
        }

        void setActive(boolean on) {
            edit.setEnabled(on);
            view.setAlpha(on ? 1f : 0.5f);
        }
    }

    static Field field(Context c, String label, String hint, int kind, int maxLen) {
        return new Field(c, label, hint, kind, maxLen);
    }

    // -------------------------------------------------------------------- tabs

    /** Equal-width tabs in a rounded track. A small red dot can flag a tab that has an error. */
    static final class Tabs {
        final LinearLayout view;
        private final LinearLayout[] items;
        private final TextView[] labels;
        private final View[] dots;
        private int index = -1;
        private final CgUi.IntCb listener;

        Tabs(Context c, String[] names, CgUi.IntCb onSelect) {
            listener = onSelect;
            view = CgUi.hbox(c);
            view.setBackground(CgUi.bg(c, CgCfg.CARD2, 14, CgCfg.STROKE));
            int pad = CgUi.dp(c, 4);
            view.setPadding(pad, pad, pad, pad);
            items = new LinearLayout[names.length];
            labels = new TextView[names.length];
            dots = new View[names.length];
            for (int i = 0; i < names.length; i++) {
                final int idx = i;
                LinearLayout item = CgUi.hbox(c);
                item.setGravity(Gravity.CENTER);
                item.setClickable(true);
                item.setPadding(CgUi.dp(c, 4), CgUi.dp(c, 9), CgUi.dp(c, 4), CgUi.dp(c, 9));
                TextView t = CgUi.text(c, names[i], 13, CgCfg.MUTED, false);
                t.setGravity(Gravity.CENTER);
                t.setSingleLine(true);
                item.addView(t, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
                View d = dot(c, CgCfg.BAD, 7);
                LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(CgUi.dp(c, 7), CgUi.dp(c, 7));
                dp.setMargins(CgUi.dp(c, 5), 0, 0, 0);
                d.setVisibility(View.GONE);
                item.addView(d, dp);
                item.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        select(idx, true);
                    }
                });
                items[i] = item;
                labels[i] = t;
                dots[i] = d;
                view.addView(item, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            }
        }

        void select(int i, boolean notify) {
            if (i == index) return;
            index = i;
            Context c = view.getContext();
            for (int k = 0; k < items.length; k++) {
                boolean on = k == i;
                items[k].setBackground(on ? CgUi.bg(c, CgCfg.ACCENT_D, 11, CgCfg.ACCENT) : null);
                labels[k].setTextColor(on ? CgCfg.ON_ACCENT : CgCfg.MUTED);
                CgUi.applyFont(labels[k], on);
            }
            if (notify && listener != null) listener.run(i);
        }

        int index() {
            return index;
        }

        void flag(int i, boolean on) {
            if (i >= 0 && i < dots.length) dots[i].setVisibility(on ? View.VISIBLE : View.GONE);
        }

        void clearFlags() {
            for (int i = 0; i < dots.length; i++) dots[i].setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------- progress

    /** Thin rounded progress bar that fills from the start side (right in RTL). */
    static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float value;

        Bar(Context c) {
            super(c);
            track.setColor(CgCfg.CARD2);
            fill.setColor(CgCfg.ACCENT);
        }

        void set(float v) {
            value = Math.max(0f, Math.min(1f, v));
            invalidate();
        }

        void color(int c) {
            fill.setColor(c);
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), CgUi.dp(getContext(), 8));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float r = h / 2f;
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, r, r, track);
            float fw = Math.max(value > 0f ? h : 0f, w * value);
            if (fw <= 0f) return;
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            if (rtl) rect.set(w - fw, 0, w, h);
            else rect.set(0, 0, fw, h);
            canvas.drawRoundRect(rect, r, r, fill);
        }
    }

    // ----------------------------------------------------------------- section

    /** A card with a header that opens and closes its content with a short height animation. */
    static final class Section extends LinearLayout {
        final LinearLayout content;
        private final LinearLayout header;
        private final ImageView chevron;
        private final LinearLayout badges;
        private boolean open;
        private ValueAnimator anim;
        private Runnable onOpen;

        Section(Context c, int iconKind, String title, int accent, boolean openInitially) {
            super(c);
            setOrientation(VERTICAL);
            setBackground(CgUi.bg(c, CgCfg.CARD, 16, accent == CgCfg.BAD ? CgUi.alpha(CgCfg.BAD, 0x99) : CgCfg.STROKE));
            int p = CgUi.dp(c, 14);
            setPadding(p, p, p, p);

            header = CgUi.hbox(c);
            header.setClickable(true);
            FrameLayout ic = iconCircle(c, iconKind, accent, 36, 20);
            LinearLayout.LayoutParams icp = new LinearLayout.LayoutParams(CgUi.dp(c, 36), CgUi.dp(c, 36));
            icp.setMargins(0, 0, CgUi.dp(c, 10), 0);
            header.addView(ic, icp);
            TextView t = CgUi.text(c, title, 15, CgCfg.TEXT, true);
            header.addView(t, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            badges = CgUi.hbox(c);
            header.addView(badges, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            chevron = icon(c, CgIcons.CHEVRON, CgCfg.MUTED, 22);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(CgUi.dp(c, 22), CgUi.dp(c, 22));
            cp.setMargins(CgUi.dp(c, 8), 0, 0, 0);
            header.addView(chevron, cp);
            addView(header, new LayoutParams(CgUi.MATCH, CgUi.WRAP));

            content = CgUi.vbox(c);
            content.setPadding(0, CgUi.dp(c, 12), 0, 0);
            addView(content, new LayoutParams(CgUi.MATCH, CgUi.WRAP));

            header.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setOpen(!open, true);
                }
            });
            open = openInitially;
            content.setVisibility(openInitially ? View.VISIBLE : View.GONE);
            chevron.setRotation(openInitially ? 180f : 0f);
        }

        void onOpen(Runnable r) {
            onOpen = r;
        }

        /** Small text badge at the end of the header (counts, status). Pass null to clear. */
        void badge(String text, int color) {
            badges.removeAllViews();
            if (text == null || text.length() == 0) return;
            badges.addView(CgUi.badge(getContext(), text, color), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        }

        boolean isOpen() {
            return open;
        }

        void setOpen(boolean want, boolean animate) {
            if (want == open && content.getVisibility() == (want ? View.VISIBLE : View.GONE) && anim == null) return;
            open = want;
            chevron.animate().rotation(want ? 180f : 0f).setDuration(animate ? 180 : 0).start();
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            final ViewGroup.LayoutParams lp = content.getLayoutParams();
            int w = getWidth() - getPaddingLeft() - getPaddingRight();
            if (!animate || w <= 0) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                content.setLayoutParams(lp);
                content.setAlpha(1f);
                content.setVisibility(want ? View.VISIBLE : View.GONE);
                if (want && onOpen != null) onOpen.run();
                return;
            }
            final int from;
            final int to;
            if (want) {
                content.setVisibility(View.VISIBLE);
                content.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                from = 0;
                to = Math.max(1, content.getMeasuredHeight());
            } else {
                from = content.getHeight();
                to = 0;
            }
            lp.height = from;
            content.setLayoutParams(lp);
            final boolean opening = want;
            ValueAnimator va = ValueAnimator.ofInt(from, to);
            va.setDuration(220);
            va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    int v = (Integer) a.getAnimatedValue();
                    lp.height = v;
                    content.setLayoutParams(lp);
                    float f = a.getAnimatedFraction();
                    content.setAlpha(opening ? f : 1f - f * 0.6f);
                }
            });
            va.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    content.setLayoutParams(lp);
                    content.setAlpha(1f);
                    if (!opening) content.setVisibility(View.GONE);
                    anim = null;
                }

                @Override
                public void onAnimationCancel(Animator a) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    content.setLayoutParams(lp);
                    content.setAlpha(1f);
                }
            });
            anim = va;
            va.start();
            if (want && onOpen != null) onOpen.run();
        }
    }

    static Section section(Context c, int iconKind, String title, int accent, boolean open) {
        return new Section(c, iconKind, title, accent, open);
    }
}
