package com.Cigram.vid;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/** عناصر واجهة مشتركة لشاشات الإعلانات: رقاقات، أزرار، حركات ظهور وعدّ تصاعدي، تخطيط التفاف RTL. */
public final class AdsUi {

    private AdsUi() { }

    public interface Fmt {
        String format(double value);
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static boolean animationsOn(Context c) {
        try {
            return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static void toast(Context c, String msg) {
        try { CigramSilent.toast(c, msg, android.widget.Toast.LENGTH_SHORT); } catch (Throwable ignored) { }
    }

    public static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = CigramUI.label(c, s, sp, color, bold);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return l;
    }

    public static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    /** بطاقة زجاجية بحشوة مناسبة. */
    public static LinearLayout card(Context c, float radiusDp, float padDp) {
        LinearLayout l = vbox(c);
        int p = dp(c, padDp);
        l.setPadding(p, p, p, p);
        CigramGlass.bind(l, radiusDp, true);
        return l;
    }

    /** عنوان قسم: أيقونة داخل حاوية ملوّنة + عنوان + وصف قصير. */
    public static View sectionHeader(Context c, int icon, int accent, String title, String subtitle) {
        LinearLayout row = hbox(c);
        FrameBox box = new FrameBox(c);
        box.setBackground(CigramUI.round((accent & 0x00FFFFFF) | 0x26000000, 14, c));
        box.addView(new AdsIcons.Icon(c, icon, accent), new android.widget.FrameLayout.LayoutParams(dp(c, 22), dp(c, 22), Gravity.CENTER));
        row.addView(box, new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        LinearLayout col = vbox(c);
        col.addView(text(c, title, 20f, CigramGlass.text(c), true));
        if (subtitle != null && subtitle.length() > 0) {
            TextView s = text(c, subtitle, 13f, CigramGlass.muted(c), false);
            s.setLineSpacing(0f, 1.15f);
            col.addView(s, lp(-2, -2, 0, dp(c, 4), 0, 0));
        }
        row.addView(col, lp(0, -2, 0, 0, dp(c, 12), 0));
        ((LinearLayout.LayoutParams) col.getLayoutParams()).weight = 1f;
        return row;
    }

    /** FrameLayout بسيط (لتجنب تعارض الأسماء). */
    public static final class FrameBox extends android.widget.FrameLayout {
        public FrameBox(Context c) { super(c); }
    }

    // ------------------------------------------------------------------ أزرار ورقاقات

    public static TextView button(Context c, String label, int fill, int textColor) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setTextSize(16f);
        b.setTextColor(textColor);
        b.setTypeface(CigramUI.font(c), Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setMinHeight(dp(c, 52));
        b.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
        b.setBackground(CigramGlass.ripple(c, CigramUI.round(fill, 16, c), 16));
        CigramGlass.press(b);
        return b;
    }

    public static void setButtonEnabled(TextView b, boolean enabled, int fill, int textColor) {
        Context c = b.getContext();
        b.setEnabled(enabled);
        b.setAlpha(1f);
        if (enabled) {
            b.setTextColor(textColor);
            b.setBackground(CigramGlass.ripple(c, CigramUI.round(fill, 16, c), 16));
        } else {
            b.setTextColor(0xFFB8C4CA);
            b.setBackground(CigramUI.round(0xFF3A4E58, 16, c));
        }
    }

    public static TextView chip(Context c, String label, boolean selected, final View.OnClickListener click) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(14f);
        t.setTypeface(CigramUI.font(c));
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        t.setMinHeight(dp(c, 48));
        t.setMinimumWidth(dp(c, 48));
        t.setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        styleChip(t, selected);
        if (click != null) t.setOnClickListener(click);
        CigramGlass.press(t);
        return t;
    }

    public static void styleChip(TextView t, boolean selected) {
        Context c = t.getContext();
        t.setSelected(selected);
        if (selected) {
            t.setTextColor(0xFF111113);
            t.setBackground(CigramUI.round(CigramUI.TEAL, 14, c));
        } else {
            t.setTextColor(CigramGlass.text(c));
            t.setBackground(CigramGlass.ripple(c, CigramUI.round(CigramGlass.soft(c), CigramGlass.stroke(c), 14, c), 14));
        }
    }

    public static GradientDrawable pill(Context c, int color, float radiusDp) {
        return CigramUI.round(color, radiusDp, c);
    }

    // ------------------------------------------------------------------ حركات

    public static void countUp(final TextView view, final double to, final Fmt fmt, long durationMs) {
        if (view == null) return;
        if (!animationsOn(view.getContext()) || to <= 0) {
            view.setText(fmt.format(to));
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(durationMs);
        a.setInterpolator(new DecelerateInterpolator(2f));
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                float f = (Float) animation.getAnimatedValue();
                view.setText(fmt.format(to * f));
            }
        });
        a.start();
    }

    /** ظهور تدريجي لعناصر الصفحة عند التمرير إليها (مرة واحدة لكل عنصر). */
    public static final class Reveal {
        private final ScrollView scroll;
        private final ArrayList<View> pending = new ArrayList<View>();
        private final boolean enabled;
        private final int[] a = new int[2];
        private final int[] b = new int[2];

        public Reveal(ScrollView sv) {
            scroll = sv;
            enabled = animationsOn(sv.getContext());
            sv.getViewTreeObserver().addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
                @Override public void onScrollChanged() { check(); }
            });
        }

        public void add(View v) {
            if (!enabled || v == null) return;
            v.setAlpha(0f);
            v.setTranslationY(dp(v.getContext(), 22));
            pending.add(v);
        }

        public void check() {
            if (pending.isEmpty()) return;
            scroll.getLocationInWindow(b);
            int bottom = b[1] + scroll.getHeight() - dp(scroll.getContext(), 24);
            for (int i = pending.size() - 1; i >= 0; i--) {
                View v = pending.get(i);
                v.getLocationInWindow(a);
                if (a[1] < bottom) {
                    pending.remove(i);
                    v.animate().alpha(1f).translationY(0f).setDuration(380L).setInterpolator(new DecelerateInterpolator(1.6f)).start();
                }
            }
        }
    }

    // ------------------------------------------------------------------ تخطيط الالتفاف (RTL)

    public static final class Flow extends ViewGroup {
        private int hGap;
        private int vGap;

        public Flow(Context c, int hGapDp, int vGapDp) {
            super(c);
            hGap = dp(c, hGapDp);
            vGap = dp(c, vGapDp);
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int maxW = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                v.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int cw = v.getMeasuredWidth(), ch = v.getMeasuredHeight();
                if (x > 0 && x + cw > maxW) {
                    x = 0;
                    y += rowH + vGap;
                    rowH = 0;
                }
                x += cw + hGap;
                rowH = Math.max(rowH, ch);
            }
            int h = y + rowH + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(MeasureSpec.getSize(wSpec), resolveSize(h, hSpec));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            int maxW = width - getPaddingLeft() - getPaddingRight();
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            int x = 0, y = getPaddingTop(), rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getVisibility() == GONE) continue;
                int cw = v.getMeasuredWidth(), ch = v.getMeasuredHeight();
                if (x > 0 && x + cw > maxW) {
                    x = 0;
                    y += rowH + vGap;
                    rowH = 0;
                }
                int left = rtl ? width - getPaddingRight() - x - cw : getPaddingLeft() + x;
                v.layout(left, y, left + cw, y + ch);
                x += cw + hGap;
                rowH = Math.max(rowH, ch);
            }
        }
    }

    /** يمرر ScrollView ليظهر العنصر المطلوب أعلى الشاشة. */
    public static void scrollTo(final ScrollView sv, final View target) {
        sv.post(new Runnable() {
            @Override public void run() {
                int top = 0;
                View v = target;
                while (v != null && v != sv) {
                    top += v.getTop();
                    if (!(v.getParent() instanceof View)) break;
                    v = (View) v.getParent();
                }
                sv.smoothScrollTo(0, Math.max(0, top - dp(sv.getContext(), 12)));
            }
        });
    }

    public static boolean visibleOnScreen(View v) {
        Rect r = new Rect();
        return v.getGlobalVisibleRect(r);
    }

    public static boolean alive(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    // ------------------------------------------------------------------ مفتاح تبديل (Toggle)

    public interface OnToggle {
        void onChanged(boolean on);
    }

    public static final class Toggle extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private boolean on;
        private float pos;
        private OnToggle listener;

        public Toggle(Context c, boolean initial) {
            super(c);
            on = initial;
            pos = initial ? 1f : 0f;
            setClickable(true);
            setFocusable(true);
            setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    setChecked(!on, true);
                    if (listener != null) listener.onChanged(on);
                }
            });
        }

        public void setListener(OnToggle l) { listener = l; }
        public boolean isChecked() { return on; }

        public void setChecked(boolean value, boolean animate) {
            on = value;
            setContentDescription(on ? "مفعّل" : "غير مفعّل");
            if (!animate || !animationsOn(getContext())) {
                pos = on ? 1f : 0f;
                invalidate();
                return;
            }
            ValueAnimator a = ValueAnimator.ofFloat(pos, on ? 1f : 0f);
            a.setDuration(160L);
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator an) {
                    pos = (Float) an.getAnimatedValue();
                    invalidate();
                }
            });
            a.start();
        }

        @Override protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(dp(getContext(), 52), dp(getContext(), 48));
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = dp(getContext(), 46), h = dp(getContext(), 28);
            float l = (getWidth() - w) / 2f, t = (getHeight() - h) / 2f;
            r.set(l, t, l + w, t + h);
            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(on ? CigramUI.TEAL : CigramGlass.stroke(getContext()));
            c.drawRoundRect(r, h / 2f, h / 2f, p);
            float kr = h / 2f - dp(getContext(), 3);
            float cx = l + h / 2f + (w - h) * pos;
            p.setColor(0xFFFFFFFF);
            c.drawCircle(cx, t + h / 2f, kr, p);
        }
    }

    // ------------------------------------------------------------------ قائمة قابلة للطي (Accordion)

    public static void toggleExpand(final View body, final View chevron) {
        final boolean open = body.getVisibility() == View.VISIBLE && body.getHeight() > 0;
        if (chevron != null) chevron.animate().rotation(open ? 0f : 180f).setDuration(200L).start();
        if (!animationsOn(body.getContext())) {
            body.setVisibility(open ? View.GONE : View.VISIBLE);
            return;
        }
        final ViewGroup.LayoutParams lp = body.getLayoutParams();
        if (open) {
            ValueAnimator a = ValueAnimator.ofInt(body.getHeight(), 0);
            a.setDuration(220L);
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator an) {
                    lp.height = (Integer) an.getAnimatedValue();
                    body.setLayoutParams(lp);
                }
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator an) {
                    body.setVisibility(View.GONE);
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    body.setLayoutParams(lp);
                }
            });
            a.start();
        } else {
            View parent = (View) body.getParent();
            int pw = Math.max(1, parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight());
            body.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            final int target = body.getMeasuredHeight();
            lp.height = 0;
            body.setLayoutParams(lp);
            body.setVisibility(View.VISIBLE);
            ValueAnimator a = ValueAnimator.ofInt(0, target);
            a.setDuration(240L);
            a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator an) {
                    lp.height = (Integer) an.getAnimatedValue();
                    body.setLayoutParams(lp);
                }
            });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator an) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    body.setLayoutParams(lp);
                }
            });
            a.start();
        }
    }
}
