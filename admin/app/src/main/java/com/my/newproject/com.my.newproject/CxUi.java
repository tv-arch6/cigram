package com.my.newproject;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Design system of the content-management screens ("Cx"): hero tiles, step bar, collapsible sections,
 * fields with inline validation, poster boxes with loading / error / aspect hints, wrapping chips,
 * searchable picker, bottom action bar, skeleton and notices. Built on the existing dark-teal identity.
 */
final class CxUi {

    private CxUi() {}

    static final int INFO = 0;
    static final int WARN = 1;
    static final int ERROR = 2;
    static final int GOOD = 3;

    static int levelColor(int level) {
        if (level == WARN) return CgCfg.WARN;
        if (level == ERROR) return CgCfg.BAD;
        if (level == GOOD) return CgCfg.GOOD;
        return CgCfg.ACCENT;
    }

    static int dp(Context c, float v) {
        return CgUi.dp(c, v);
    }

    // ------------------------------------------------------------------ hero tile

    private static StateListDrawable pressable(Context c, int fill, int pressedFill, int stroke, int pressedStroke, float radius) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, CgUi.bg(c, pressedFill, radius, pressedStroke));
        sl.addState(new int[]{}, CgUi.bg(c, fill, radius, stroke));
        return sl;
    }

    /** Large card: icon, title, short description and a helper status line. */
    static LinearLayout tile(Context c, String glyph, String title, String desc, String status, int color, View.OnClickListener click) {
        LinearLayout t = CgUi.vbox(c);
        t.setBackground(pressable(c, CgCfg.CARD, CgCfg.CARD2, CgCfg.STROKE, color, 18));
        t.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
        t.setMinimumHeight(dp(c, 168));
        t.setClickable(true);
        TextView icon = CgUi.text(c, glyph, 22, CgCfg.TEXT, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(CgUi.bg(c, CgUi.alpha(color, 0x2E), 14, CgUi.alpha(color, 0x77)));
        t.addView(icon, new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        TextView ti = CgUi.text(c, title, 15.5f, CgCfg.TEXT, true);
        ti.setPadding(0, dp(c, 10), 0, dp(c, 2));
        t.addView(ti, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        TextView de = CgUi.muted(c, desc);
        de.setTextSize(11.5f);
        de.setMaxLines(3);
        de.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(de, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));
        if (status != null && status.length() > 0) {
            TextView st = CgUi.badge(c, status, color);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            lp.topMargin = dp(c, 8);
            t.addView(st, lp);
        }
        if (click != null) t.setOnClickListener(click);
        return t;
    }

    /** Two equally wide children (second may be null). */
    static LinearLayout pair(Context c, View a, View b) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        la.setMargins(dp(c, 5), dp(c, 5), dp(c, 5), dp(c, 5));
        row.addView(a, la);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        lb.setMargins(dp(c, 5), dp(c, 5), dp(c, 5), dp(c, 5));
        if (b == null) {
            View sp = new View(c);
            b = sp;
        }
        row.addView(b, lb);
        return row;
    }

    // ---------------------------------------------------------------------- notice

    static View notice(Context c, int level, String text) {
        int color = levelColor(level);
        LinearLayout box = CgUi.hbox(c);
        box.setBackground(CgUi.bg(c, CgUi.alpha(color, 0x1A), 12, CgUi.alpha(color, 0x88)));
        box.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        String glyph = level == WARN ? "⚠" : level == ERROR ? "✖" : level == GOOD ? "✔" : "ℹ";
        TextView g = CgUi.text(c, glyph, 16, color, true);
        g.setGravity(Gravity.CENTER);
        box.addView(g, new LinearLayout.LayoutParams(dp(c, 26), CgUi.WRAP));
        TextView t = CgUi.text(c, text, 13, CgCfg.TEXT, false);
        t.setLineSpacing(0, 1.1f);
        box.addView(t, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        box.setLayoutParams(CgUi.lp(c, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 8));
        return box;
    }

    // --------------------------------------------------------------------- step bar

    static final class StepBar extends LinearLayout {
        private final String[] names;
        private final View[] segs;
        private final TextView title;
        private final TextView counter;
        private int current;
        private CgUi.IntCb click;

        StepBar(Context c, String[] names) {
            super(c);
            this.names = names;
            segs = new View[names.length];
            setOrientation(VERTICAL);
            setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 10));
            setBackgroundColor(CgCfg.PAGE);
            LinearLayout bars = CgUi.hbox(c);
            for (int i = 0; i < names.length; i++) {
                final int index = i;
                FrameLayout cell = new FrameLayout(c);
                View seg = new View(c);
                segs[i] = seg;
                FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(CgUi.MATCH, dp(c, 4), Gravity.CENTER_VERTICAL);
                cell.addView(seg, sp);
                cell.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (click != null && index < current) click.run(index);
                    }
                });
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, dp(c, 20), 1f);
                cl.setMargins(dp(c, i == 0 ? 0 : 3), 0, dp(c, i == names.length - 1 ? 0 : 3), 0);
                bars.addView(cell, cl);
            }
            addView(bars, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            LinearLayout row = CgUi.hbox(c);
            title = CgUi.text(c, "", 15, CgCfg.TEXT, true);
            counter = CgUi.text(c, "", 12.5f, CgCfg.MUTED, false);
            row.addView(title, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            row.addView(counter, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            addView(row, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            set(0);
        }

        void onStep(CgUi.IntCb cb) {
            click = cb;
        }

        int current() {
            return current;
        }

        void set(int idx) {
            current = idx;
            Context c = getContext();
            for (int i = 0; i < segs.length; i++) {
                int color = i < idx ? CgCfg.ACCENT_D : i == idx ? CgCfg.ACCENT : CgCfg.CARD2;
                segs[i].setBackground(CgUi.bg(c, color, 2, 0));
            }
            title.setText(idx >= 0 && idx < names.length ? names[idx] : "");
            counter.setText("الخطوة " + (idx + 1) + " من " + names.length);
        }

        String name(int i) {
            return i >= 0 && i < names.length ? names[i] : "";
        }
    }

    // ---------------------------------------------------------------------- section

    static final class Section extends LinearLayout {
        final LinearLayout content;
        private final TextView arrow;
        private final TextView summaryView;
        private boolean open;
        private final boolean collapsible;

        Section(Context c, String title, String sub, boolean collapsible, boolean open) {
            super(c);
            this.collapsible = collapsible;
            this.open = open || !collapsible;
            setOrientation(VERTICAL);
            setBackground(CgUi.bg(c, CgCfg.CARD, 16, CgCfg.STROKE));
            setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
            LinearLayout head = CgUi.hbox(c);
            LinearLayout titles = CgUi.vbox(c);
            titles.addView(CgUi.text(c, title, 14.5f, CgCfg.TEXT, true));
            if (sub != null && sub.length() > 0) {
                TextView s = CgUi.muted(c, sub);
                s.setTextSize(11.5f);
                titles.addView(s);
            }
            summaryView = CgUi.text(c, "", 11.5f, CgCfg.ACCENT, false);
            summaryView.setSingleLine(true);
            summaryView.setEllipsize(TextUtils.TruncateAt.END);
            summaryView.setVisibility(View.GONE);
            titles.addView(summaryView);
            head.addView(titles, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            arrow = CgUi.text(c, "▾", 16, CgCfg.MUTED, true);
            arrow.setGravity(Gravity.CENTER);
            if (collapsible) head.addView(arrow, new LinearLayout.LayoutParams(dp(c, 30), CgUi.WRAP));
            addView(head, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            content = CgUi.vbox(c);
            content.setPadding(0, dp(c, 12), 0, 0);
            addView(content, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            if (collapsible) {
                head.setClickable(true);
                head.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggle(!Section.this.open);
                    }
                });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP);
            lp.bottomMargin = dp(c, 10);
            setLayoutParams(lp);
            refresh();
        }

        void toggle(boolean o) {
            open = o;
            refresh();
        }

        void summary(String s) {
            if (s == null || s.length() == 0 || open) {
                summaryView.setVisibility(View.GONE);
            } else {
                summaryView.setText(s);
                summaryView.setVisibility(View.VISIBLE);
            }
            lastSummary = s;
        }

        private String lastSummary = "";

        private void refresh() {
            content.setVisibility(open ? View.VISIBLE : View.GONE);
            arrow.setText(open ? "▴" : "▾");
            if (collapsible) summary(lastSummary);
        }

        void add(View v) {
            content.addView(v);
        }
    }

    // ------------------------------------------------------------------------ field

    static final int T_TEXT = 0;
    static final int T_MULTI = 1;
    static final int T_URL = 2;
    static final int T_NUMBER = 3;
    static final int T_LTR = 4;

    /** Label + input + inline message (error / warning / ok) + optional hint. */
    static final class Field {
        final LinearLayout view;
        final EditText input;
        private final TextView labelView;
        private final TextView msg;
        private final TextView hintView;
        private final Context c;
        private boolean silent;
        private final ArrayList<Runnable> watchers = new ArrayList<Runnable>();

        Field(Context c, String label, String hint, int type, boolean required) {
            this.c = c;
            view = CgUi.vbox(c);
            labelView = CgUi.text(c, required ? label + " *" : label, 12, CgCfg.MUTED, false);
            labelView.setPadding(dp(c, 4), 0, dp(c, 4), dp(c, 4));
            view.addView(labelView, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            if (type == T_MULTI) input = CgUi.field(c, hint, true);
            else if (type == T_URL) input = CgUi.urlField(c, hint);
            else if (type == T_NUMBER) input = CgUi.numberField(c, hint);
            else input = CgUi.field(c, hint, false);
            if (type == T_LTR) {
                input.setTextDirection(View.TEXT_DIRECTION_LTR);
                input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            }
            view.addView(input, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            msg = CgUi.text(c, "", 11.5f, CgCfg.BAD, false);
            msg.setPadding(dp(c, 6), dp(c, 3), dp(c, 6), 0);
            msg.setVisibility(View.GONE);
            view.addView(msg, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            hintView = CgUi.muted(c, "");
            hintView.setTextSize(11);
            hintView.setPadding(dp(c, 6), dp(c, 3), dp(c, 6), 0);
            hintView.setVisibility(View.GONE);
            view.addView(hintView, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            view.setLayoutParams(CgUi.lp(c, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));
            input.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int a, int b, int d) { }

                @Override
                public void onTextChanged(CharSequence s, int a, int b, int d) { }

                @Override
                public void afterTextChanged(Editable e) {
                    if (silent) return;
                    clear();
                    for (int i = 0; i < watchers.size(); i++) watchers.get(i).run();
                }
            });
        }

        String text() {
            return input.getText().toString().trim();
        }

        void set(String v) {
            silent = true;
            input.setText(v == null ? "" : v);
            silent = false;
        }

        void onChange(Runnable r) {
            watchers.add(r);
        }

        void hint(String h) {
            if (h == null || h.length() == 0) {
                hintView.setVisibility(View.GONE);
            } else {
                hintView.setText(h);
                hintView.setVisibility(View.VISIBLE);
            }
        }

        void label(String l) {
            labelView.setText(l);
        }

        void message(int level, String text) {
            int color = levelColor(level);
            if (level == INFO) color = CgCfg.MUTED;
            if (text == null || text.length() == 0) {
                msg.setVisibility(View.GONE);
                input.setBackground(CgUi.bg(c, CgCfg.FIELD, 12, CgCfg.STROKE));
                return;
            }
            String glyph = level == ERROR ? "✖ " : level == WARN ? "⚠ " : level == GOOD ? "✔ " : "";
            msg.setTextColor(color);
            CgUi.set(msg, glyph + text);
            msg.setVisibility(View.VISIBLE);
            input.setBackground(CgUi.bg(c, CgCfg.FIELD, 12, level == ERROR || level == WARN || level == GOOD ? color : CgCfg.STROKE));
        }

        void error(String text) {
            message(ERROR, text);
            if (text != null && text.length() > 0) input.requestFocus();
        }

        void warn(String text) {
            message(WARN, text);
        }

        void ok(String text) {
            message(GOOD, text);
        }

        void clear() {
            message(INFO, "");
        }

        void enabled(boolean e) {
            input.setEnabled(e);
            input.setAlpha(e ? 1f : 0.55f);
        }
    }

    // ---------------------------------------------------------------------- poster box

    interface RatioHint {
        void onHint(String warning);
    }

    /** Image preview with placeholder, loading and error states and an aspect-ratio hint. */
    static final class PosterBox extends FrameLayout {
        final ImageView img;
        private final TextView overlayText;
        private final ProgressBar spin;
        private final float expectedWH;
        private final String ratioName;
        private final int widthPx;
        private RatioHint hintCb;

        /** ratioHW is height / width (poster 1.5, backdrop 0.5625, logo 1). */
        PosterBox(Context c, int widthDp, float ratioHW, String ratioName) {
            super(c);
            this.expectedWH = 1f / ratioHW;
            this.ratioName = ratioName;
            this.widthPx = dp(c, widthDp);
            setBackground(CgUi.bg(c, CgCfg.CARD2, 12, CgCfg.STROKE));
            img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(img, new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.MATCH));
            overlayText = CgUi.text(c, "", 11.5f, CgCfg.MUTED, false);
            overlayText.setGravity(Gravity.CENTER);
            overlayText.setPadding(dp(c, 6), dp(c, 6), dp(c, 6), dp(c, 6));
            addView(overlayText, new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.MATCH));
            spin = CgUi.spinner(c);
            spin.setVisibility(View.GONE);
            addView(spin, new FrameLayout.LayoutParams(dp(c, 26), dp(c, 26), Gravity.CENTER));
            setLayoutParams(new LinearLayout.LayoutParams(widthPx, Math.round(widthPx * ratioHW)));
            showPlaceholder("🖼\nبدون صورة");
        }

        void onHint(RatioHint h) {
            hintCb = h;
        }

        private void showPlaceholder(String t) {
            img.setImageDrawable(null);
            CgUi.set(overlayText, t);
            overlayText.setVisibility(View.VISIBLE);
            spin.setVisibility(View.GONE);
        }

        void clear() {
            img.setTag(null);
            showPlaceholder("🖼\nبدون صورة");
            if (hintCb != null) hintCb.onHint("");
        }

        void setBitmap(Bitmap b) {
            img.setTag(null);
            if (b == null) {
                clear();
                return;
            }
            img.setImageBitmap(b);
            overlayText.setVisibility(View.GONE);
            spin.setVisibility(View.GONE);
            checkRatio(b.getWidth(), b.getHeight());
        }

        void set(String url) {
            if (url == null || !url.trim().startsWith("http")) {
                if (url != null && url.trim().length() > 0) showPlaceholder("⚠\nرابط غير صالح");
                else clear();
                if (url != null && url.trim().length() > 0 && hintCb != null) hintCb.onHint("");
                return;
            }
            overlayText.setVisibility(View.GONE);
            spin.setVisibility(View.VISIBLE);
            CgImg.load(img, url.trim(), Math.max(widthPx, dp(getContext(), 200)), new CgImg.Listener() {
                @Override
                public void onResult(boolean ok, int w, int h) {
                    spin.setVisibility(View.GONE);
                    if (!ok) {
                        showPlaceholder("⚠\nتعذر تحميل الصورة");
                        if (hintCb != null) hintCb.onHint("تعذر تحميل الصورة — تأكد من الرابط.");
                    } else {
                        overlayText.setVisibility(View.GONE);
                        checkRatio(w, h);
                    }
                }
            });
        }

        private void checkRatio(int w, int h) {
            if (hintCb == null) return;
            if (w <= 0 || h <= 0) {
                hintCb.onHint("");
                return;
            }
            float actual = (float) w / (float) h;
            float diff = Math.abs(actual - expectedWH) / expectedWH;
            if (diff > 0.25f) {
                hintCb.onHint("نسبة الصورة " + String.format(Locale.US, "%.2f", actual) + ":1 تختلف عن المتوقع (" + ratioName + ") وقد تُقصّ في التطبيق.");
            } else {
                hintCb.onHint("");
            }
        }
    }

    // -------------------------------------------------------------------------- flow

    /** Wrapping layout for chips; honours RTL. */
    static final class Flow extends ViewGroup {
        private final int hGap;
        private final int vGap;

        Flow(Context c) {
            super(c);
            hGap = dp(c, 6);
            vGap = dp(c, 6);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int maxW = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
            int x = 0;
            int y = 0;
            int lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch.getVisibility() == GONE) continue;
                ch.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int cw = ch.getMeasuredWidth();
                int chh = ch.getMeasuredHeight();
                if (x > 0 && x + cw > maxW) {
                    x = 0;
                    y += lineH + vGap;
                    lineH = 0;
                }
                x += cw + hGap;
                lineH = Math.max(lineH, chh);
            }
            int total = y + lineH + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(resolveSize(maxW + getPaddingLeft() + getPaddingRight(), wSpec), resolveSize(total, hSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            int maxW = width - getPaddingLeft() - getPaddingRight();
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int x = 0;
            int y = getPaddingTop();
            int lineH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch.getVisibility() == GONE) continue;
                int cw = ch.getMeasuredWidth();
                int chh = ch.getMeasuredHeight();
                if (x > 0 && x + cw > maxW) {
                    x = 0;
                    y += lineH + vGap;
                    lineH = 0;
                }
                int left = rtl ? width - getPaddingRight() - x - cw : getPaddingLeft() + x;
                ch.layout(left, y, left + cw, y + chh);
                x += cw + hGap;
                lineH = Math.max(lineH, chh);
            }
        }
    }

    // ------------------------------------------------------------------- bottom bar

    static LinearLayout bottomBar(Context c) {
        LinearLayout bar = CgUi.hbox(c);
        bar.setBackgroundColor(CgCfg.CARD);
        bar.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
        try {
            bar.setElevation(dp(c, 8));
        } catch (Throwable ignored) { }
        return bar;
    }

    static TextView barButton(Context c, LinearLayout bar, String label, int style, float weight, View.OnClickListener l) {
        TextView b = CgUi.button(c, label, style);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, CgUi.WRAP, weight);
        lp.setMargins(dp(c, 4), 0, dp(c, 4), 0);
        bar.addView(b, lp);
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    static void setEnabled(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setClickable(enabled);
        v.setAlpha(enabled ? 1f : 0.45f);
    }

    // ----------------------------------------------------------------------- skeleton

    static View skeleton(Context c, int rows) {
        LinearLayout box = CgUi.vbox(c);
        for (int i = 0; i < rows; i++) {
            LinearLayout row = CgUi.hbox(c);
            row.setBackground(CgUi.bg(c, CgCfg.CARD, 14, CgCfg.STROKE));
            row.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
            View thumb = new View(c);
            thumb.setBackground(CgUi.bg(c, CgCfg.CARD2, 8, 0));
            row.addView(thumb, new LinearLayout.LayoutParams(dp(c, 52), dp(c, 76)));
            LinearLayout lines = CgUi.vbox(c);
            for (int k = 0; k < 3; k++) {
                View ln = new View(c);
                ln.setBackground(CgUi.bg(c, CgCfg.CARD2, 6, 0));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(k == 0 ? dp(c, 160) : k == 1 ? dp(c, 110) : dp(c, 200), dp(c, 12));
                lp.setMargins(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
                lines.addView(ln, lp);
            }
            row.addView(lines, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            box.addView(row, CgUi.lp(c, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
        AlphaAnimation a = new AlphaAnimation(0.45f, 1f);
        a.setDuration(750);
        a.setRepeatMode(Animation.REVERSE);
        a.setRepeatCount(Animation.INFINITE);
        box.startAnimation(a);
        // A removed skeleton must stop animating, otherwise it keeps drawing ghost rows over the real list.
        box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) { }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.clearAnimation();
            }
        });
        return box;
    }

    // ---------------------------------------------------------------- chips helpers

    static TextView chip(Context c, String label, boolean selected, final Runnable onClick) {
        TextView t = CgUi.chip(c, label, selected);
        if (onClick != null) {
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onClick.run();
                }
            });
        }
        return t;
    }

    static String agoMs(long ms) {
        if (ms <= 0) return "";
        long diff = (System.currentTimeMillis() - ms) / 1000;
        if (diff < 60) return "الآن";
        if (diff < 3600) return "منذ " + (diff / 60) + " دقيقة";
        if (diff < 86400) return "منذ " + (diff / 3600) + " ساعة";
        return "منذ " + (diff / 86400) + " يوم";
    }

    // ----------------------------------------------------------------- searchable picker

    static final class Option {
        final String id;
        final String title;
        final String subtitle;
        final String image; // null = no thumbnail column
        final String search;

        Option(String id, String title, String subtitle, String image) {
            this.id = id == null ? "" : id;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.image = image;
            this.search = CgData.normalize(this.title + " " + this.subtitle + " " + this.id);
        }
    }

    /**
     * Searchable dropdown as a dialog. onCreate (nullable) adds a "create «text»" row when nothing matches exactly.
     */
    static void pick(final Activity a, String title, final List<Option> options, final String selectedId,
                     final String createLabel, final CgUi.Callback<Option> onPick, final CgUi.Callback<String> onCreate) {
        final Handler h = new Handler(Looper.getMainLooper());
        LinearLayout box = CgUi.vbox(a);
        box.setPadding(dp(a, 14), dp(a, 8), dp(a, 14), dp(a, 4));
        final EditText search = CgUi.field(a, "ابحث...", false);
        box.addView(search, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        final LinearLayout list = CgUi.vbox(a);
        ScrollView sv = new ScrollView(a);
        sv.addView(list, new ScrollView.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        int height = (int) (a.getResources().getDisplayMetrics().heightPixels * 0.55f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(CgUi.MATCH, height);
        slp.topMargin = dp(a, 8);
        box.addView(sv, slp);
        try {
            box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        } catch (Throwable ignored) { }
        final AlertDialog dialog = CgUi.dialog(a).setTitle(title).setView(box).setNegativeButton("إغلاق", null).create();

        final Runnable[] fill = new Runnable[1];
        fill[0] = new Runnable() {
            @Override
            public void run() {
                list.removeAllViews();
                final String q = search.getText().toString().trim();
                String nq = CgData.normalize(q);
                int shown = 0;
                boolean exact = false;
                for (int i = 0; i < options.size() && shown < 60; i++) {
                    final Option o = options.get(i);
                    if (nq.length() > 0 && !o.search.contains(nq)) continue;
                    if (nq.length() > 0 && CgData.normalize(o.title).equals(nq)) exact = true;
                    shown++;
                    LinearLayout row = CgUi.hbox(a);
                    boolean sel = o.id.equals(selectedId);
                    row.setBackground(CgUi.bg(a, sel ? CgUi.alpha(CgCfg.ACCENT, 0x2A) : CgCfg.CARD, 12, sel ? CgCfg.ACCENT : CgCfg.STROKE));
                    row.setPadding(dp(a, 8), dp(a, 8), dp(a, 8), dp(a, 8));
                    if (o.image != null) {
                        ImageView iv = new ImageView(a);
                        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        CgImg.load(iv, o.image, 120);
                        row.addView(iv, new LinearLayout.LayoutParams(dp(a, 38), dp(a, 56)));
                    }
                    LinearLayout col = CgUi.vbox(a);
                    TextView t1 = CgUi.text(a, o.title, 14, CgCfg.TEXT, true);
                    t1.setMaxLines(2);
                    col.addView(t1);
                    if (o.subtitle.length() > 0) {
                        TextView t2 = CgUi.muted(a, o.subtitle);
                        t2.setTextSize(11.5f);
                        t2.setMaxLines(2);
                        col.addView(t2);
                    }
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
                    clp.setMargins(dp(a, 10), 0, dp(a, 10), 0);
                    row.addView(col, clp);
                    if (sel) row.addView(CgUi.text(a, "✓", 16, CgCfg.ACCENT, true));
                    row.setClickable(true);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dialog.dismiss();
                            onPick.run(o);
                        }
                    });
                    list.addView(row, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));
                }
                if (shown == 0) list.addView(CgUi.empty(a, "لا توجد نتائج", "جرّب كلمات أخرى."));
                if (onCreate != null && q.length() > 0 && !exact) {
                    TextView create = CgUi.button(a, "＋ " + (createLabel == null ? "إنشاء" : createLabel) + " «" + q + "»", 1);
                    create.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            dialog.dismiss();
                            onCreate.run(q);
                        }
                    });
                    list.addView(create, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 6));
                }
                if (shown >= 60) list.addView(CgUi.muted(a, "اكتب للمزيد من التضييق..."));
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int x, int y, int z) { }

            @Override
            public void onTextChanged(CharSequence s, int x, int y, int z) { }

            @Override
            public void afterTextChanged(Editable e) {
                h.removeCallbacks(fill[0]);
                h.postDelayed(fill[0], 140);
            }
        });
        fill[0].run();
        dialog.show();
    }
}
