package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * صفحة الدخول وإنشاء الحساب (التصميم «أ · المصقولة»).
 *
 * شكل فقط فوق عناصر account.xml الموجودة؛ طلبات /login و /register وتحققها كما هي في AccountActivity.
 *  - عنوان ورسالة فوق التبويبات يتبدلان مع التبويب.
 *  - تبويبات بشكل segmented: المحدد أبيض بكتابة داكنة.
 *  - حقول بحد يتلوّن عند التركيز، وزر «إظهار» داخل حقول كلمة المرور.
 *  - «تأكيد كلمة المرور» مخفي دائماً ويُملأ تلقائياً من كلمة المرور، حتى يبقى تحقق التسجيل الحالي يعمل.
 *  - أزرار الدخول/الإنشاء بعرض كامل.
 *
 * apply() تُستدعى بعد كل onResume، لذلك كل شيء هنا idempotent ولا يعيد التبويب المختار للبداية.
 */
public final class CigramAuthPolish {

    private CigramAuthPolish() { }

    private static final String TAG_HEADER = "cigram_auth_header";
    private static final int TEXT_2 = 0xFFC3CDD2;
    private static final int HINT = 0xFF6F8591;
    private static final int TAB_ON_TEXT = 0xFF05141F;

    private static View byId(Activity a, String name) {
        int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
        return id == 0 ? null : a.findViewById(id);
    }

    public static void apply(final Activity a) {
        if (a == null || a.isFinishing()) return;
        try {
            final View tabsView = byId(a, "linear_auth_tabs");
            final View loginTab = byId(a, "textview_login_tab");
            final View registerTab = byId(a, "textview_register_tab");
            View contentView = byId(a, "linear_account_content");
            if (!(tabsView instanceof LinearLayout) || !(loginTab instanceof TextView)
                    || !(registerTab instanceof TextView) || !(contentView instanceof LinearLayout)) {
                return;
            }
            final LinearLayout tabs = (LinearLayout) tabsView;
            final LinearLayout content = (LinearLayout) contentView;
            final boolean first = !Boolean.TRUE.equals(tabs.getTag());

            // ---------------------------------------------------------------- header
            LinearLayout header = (LinearLayout) content.findViewWithTag(TAG_HEADER);
            if (header == null) {
                header = new LinearLayout(a);
                header.setTag(TAG_HEADER);
                header.setOrientation(LinearLayout.VERTICAL);
                TextView title = CigramUI.label(a, "", 27f, CigramTheme.text(a), true);
                title.setGravity(Gravity.CENTER);
                header.addView(title, new LinearLayout.LayoutParams(-1, -2));
                TextView sub = CigramUI.label(a, "", 14f, CigramTheme.text2(a), false);
                sub.setLineSpacing(0f, 1.2f);
                sub.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
                sl.topMargin = CigramUI.dp(a, 4);
                header.addView(sub, sl);
                LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
                hl.leftMargin = CigramUI.dp(a, 20);
                hl.rightMargin = CigramUI.dp(a, 20);
                hl.topMargin = CigramUI.dp(a, 24);
                hl.bottomMargin = CigramUI.dp(a, 18);
                content.addView(header, Math.max(0, content.indexOfChild(tabs)), hl);
            }
            // an older build put the logo above the forms (and it showed over the profile): remove it
            View oldLogo = content.findViewWithTag(CigramAccountDesign.TAG_LOGO);
            if (oldLogo != null && oldLogo.getParent() == content) content.removeView(oldLogo);
            final LinearLayout fHeader = header;
            header.setVisibility(tabs.getVisibility());

            // ------------------------------------------------------------------ tabs
            tabs.setBackground(CigramUI.round(CigramTheme.isLight(a) ? 0x12000000 : 0x14FFFFFF, 16, a));
            int pad = CigramUI.dp(a, 4);
            tabs.setPadding(pad, pad, pad, pad);
            ViewGroup.LayoutParams tlp = tabs.getLayoutParams();
            if (tlp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) tlp;
                m.height = CigramUI.dp(a, 50);
                m.leftMargin = CigramUI.dp(a, 20);
                m.rightMargin = CigramUI.dp(a, 20);
                tabs.setLayoutParams(m);
            }
            styleTabText(a, (TextView) loginTab, "تسجيل الدخول");
            styleTabText(a, (TextView) registerTab, "حساب جديد");

            // ---------------------------------------------------------------- fields
            styleField(a, "edittext_login_email", "البريد الإلكتروني", false);
            styleField(a, "edittext_login_password", "كلمة المرور", true);
            styleField(a, "edittext_register_name", "الاسم", false);
            styleField(a, "edittext_register_email", "البريد الإلكتروني", false);
            styleField(a, "edittext_register_password", "كلمة المرور (8 أحرف على الأقل)", true);

            // تأكيد كلمة المرور: مخفي ويتبع كلمة المرور
            final View confirmView = byId(a, "edittext_register_confirm_password");
            View regPassView = byId(a, "edittext_register_password");
            if (confirmView instanceof EditText && regPassView instanceof EditText) {
                final EditText confirm = (EditText) confirmView;
                final EditText regPass = (EditText) regPassView;
                confirm.setVisibility(View.GONE);
                confirm.setText(regPass.getText());
                if (first) {
                    regPass.addTextChangedListener(new TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
                        @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
                        @Override public void afterTextChanged(Editable s) {
                            confirm.setText(s == null ? "" : s.toString());
                        }
                    });
                }
            }

            // ----------------------------------------------------------------- forgot
            View forgot = byId(a, "textview_forgot_password");
            if (forgot instanceof TextView) {
                TextView f = (TextView) forgot;
                f.setTextColor(CigramAccountDesign.RED);
                f.setTextSize(13f);
                f.setTypeface(CigramUI.font(a), Typeface.BOLD);
                f.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                f.setBackground(null);
            }

            // ---------------------------------------------------------------- buttons
            styleButton(a, "button_login", "دخول");
            styleButton(a, "button_register", "إنشاء الحساب");

            // ----------------------------------------------------------- tab switching
            loginTab.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { select(a, false); }
            });
            registerTab.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { select(a, true); }
            });

            View registerForm = byId(a, "linear_register_form");
            boolean onRegister = registerForm != null && registerForm.getVisibility() == View.VISIBLE;
            paint(a, onRegister);

            if (first) {
                tabs.setTag(Boolean.TRUE);
                CigramAccountDesign.reveal(a, fHeader, tabs, byId(a, "linear_login_form"));
                // العنوان يظهر ويختفي مع التبويبات (زر «تسجيل الدخول» للضيف وتسجيل الخروج يغيّرانها)
                content.getViewTreeObserver().addOnGlobalLayoutListener(
                        new ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override public void onGlobalLayout() {
                                int want = tabs.getVisibility();
                                if (fHeader.getVisibility() != want) fHeader.setVisibility(want);
                            }
                        });
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramAuthPolish", "apply failed", error);
        }
    }

    // ------------------------------------------------------------------ switching

    private static void select(Activity a, boolean register) {
        setVis(a, "linear_login_form", !register);
        setVis(a, "edittext_login_email", !register);
        setVis(a, "edittext_login_password", !register);
        setVis(a, "textview_forgot_password", !register);
        setVis(a, "button_login", !register);

        setVis(a, "linear_register_form", register);
        setVis(a, "edittext_register_name", register);
        setVis(a, "edittext_register_email", register);
        setVis(a, "edittext_register_password", register);
        setVis(a, "edittext_register_confirm_password", false);
        setVis(a, "button_register", register);
        paint(a, register);
    }

    private static void setVis(Activity a, String id, boolean visible) {
        View v = byId(a, id);
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private static void paint(Activity a, boolean register) {
        View loginTab = byId(a, "textview_login_tab");
        View registerTab = byId(a, "textview_register_tab");
        if (loginTab instanceof TextView) tabState(a, (TextView) loginTab, !register);
        if (registerTab instanceof TextView) tabState(a, (TextView) registerTab, register);

        View content = byId(a, "linear_account_content");
        if (!(content instanceof ViewGroup)) return;
        View header = content.findViewWithTag(TAG_HEADER);
        if (!(header instanceof LinearLayout) || ((LinearLayout) header).getChildCount() < 2) return;
        LinearLayout h = (LinearLayout) header;
        ((TextView) h.getChildAt(0)).setText(register ? "حساب جديد" : "أهلاً بعودتك");
        ((TextView) h.getChildAt(1)).setText(register
                ? "ثلاث خانات بس، وتبدي تحفظ مشاهداتك."
                : "ادخل لحسابك حتى تكمّل من حيث وقفت.");
    }

    // ---------------------------------------------------------------------- style

    private static void styleTabText(Activity a, TextView t, String text) {
        t.setText(text);
        t.setTextSize(14f);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(CigramUI.font(a), Typeface.BOLD);
    }

    private static void tabState(Activity a, TextView t, boolean on) {
        t.setBackgroundTintList(null);
        if (on) {
            // the open tab is the brand red pill
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xFFFF3B30, 0xFFE21B14, 0xFFB3120C});
            g.setCornerRadius(CigramUI.dp(a, 13));
            t.setBackground(g);
            t.setTextColor(0xFFFFFFFF);
            if (t.getTag() == null) t.setTag(CigramTheme.SKIP_TAG);   // white on brand red in both themes
        } else {
            t.setBackground(null);
            t.setTextColor(CigramTheme.text2(a));
        }
    }

    private static void styleField(final Activity a, String id, String hint, boolean password) {
        View v = byId(a, id);
        if (!(v instanceof EditText)) return;
        final EditText e = (EditText) v;
        e.setHint(hint);
        e.setHintTextColor(CigramTheme.isLight(a) ? 0xFF8A949B : HINT);
        e.setTextColor(CigramTheme.text(a));
        e.setTextSize(15f);
        e.setTypeface(CigramUI.font(a));
        e.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        e.setBackgroundTintList(null);

        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, CigramAccountDesign.fieldBackground(a, true));
        bg.addState(new int[]{}, CigramAccountDesign.fieldBackground(a, false));
        bg.setEnterFadeDuration(140);
        bg.setExitFadeDuration(140);
        e.setBackground(bg);
        e.setPadding(CigramUI.dp(a, 14), 0, CigramUI.dp(a, 14), 0);

        // leading icon on the right (the app is not mirrored): mail / person / lock; the password
        // field keeps its «إظهار» toggle on the left
        int kind = id.contains("password") ? CigramAccountDesign.LOCK
                : id.contains("name") ? CigramAccountDesign.USER : CigramAccountDesign.MAIL;
        Drawable[] old = e.getCompoundDrawables();
        Drawable glyph = new CigramAccountDesign.Glyph(a, kind, CigramTheme.isLight(a) ? 0xFF7C878E : 0xFF8FA1AA, 20);
        glyph.setBounds(0, 0, glyph.getIntrinsicWidth(), glyph.getIntrinsicHeight());
        e.setCompoundDrawables(old[0], null, glyph, null);
        e.setCompoundDrawablePadding(CigramUI.dp(a, 10));

        ViewGroup.LayoutParams lp = e.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            m.height = CigramUI.dp(a, 54);
            m.leftMargin = CigramUI.dp(a, 20);
            m.rightMargin = CigramUI.dp(a, 20);
            m.topMargin = CigramUI.dp(a, 12);
            e.setLayoutParams(m);
        }

        if (!password || Boolean.TRUE.equals(e.getTag())) return;
        e.setTag(Boolean.TRUE);
        e.setTransformationMethod(PasswordTransformationMethod.getInstance());
        final ToggleText toggle = new ToggleText(a, "إظهار");
        toggle.setBounds(0, 0, toggle.getIntrinsicWidth(), toggle.getIntrinsicHeight());
        Drawable[] withIcon = e.getCompoundDrawables();
        e.setCompoundDrawables(toggle, null, withIcon[2], null);
        e.setCompoundDrawablePadding(CigramUI.dp(a, 10));
        e.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent ev) {
                if (ev.getActionMasked() != MotionEvent.ACTION_UP) return false;
                int edge = e.getPaddingLeft() + toggle.getIntrinsicWidth() + CigramUI.dp(a, 8);
                if (ev.getX() > edge) return false;
                boolean hidden = e.getTransformationMethod() instanceof PasswordTransformationMethod;
                int sel = e.getSelectionEnd();
                e.setTransformationMethod(hidden ? null : PasswordTransformationMethod.getInstance());
                toggle.setText(hidden ? "إخفاء" : "إظهار");
                e.setTypeface(CigramUI.font(a));
                if (sel >= 0 && sel <= e.length()) e.setSelection(sel);
                e.invalidate();
                return true;
            }
        });
    }

    private static void styleButton(Activity a, String id, String text) {
        View v = byId(a, id);
        if (!(v instanceof TextView)) return;
        ((TextView) v).setText(text);
        CigramAccountDesign.primaryButton(a, v, 54, 20, 22);
    }

    /** Opens the «حساب جديد» tab (used by the guest page link). */
    static void showRegister(final Activity a) {
        if (a == null) return;
        a.getWindow().getDecorView().post(new Runnable() {
            @Override public void run() { select(a, true); }
        });
    }

    /** «إظهار» / «إخفاء» drawn as text inside the password field (left side; the app has no supportsRtl). */
    private static final class ToggleText extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int w;
        private final int h;
        private String text;

        ToggleText(Context c, String text) {
            this.text = text;
            p.setColor(CigramAccountDesign.RED);
            p.setTextSize(CigramUI.dp(c, 12.5f));
            p.setTypeface(Typeface.create(CigramUI.font(c), Typeface.BOLD));
            p.setTextAlign(Paint.Align.LEFT);
            w = (int) Math.ceil(Math.max(p.measureText("إظهار"), p.measureText("إخفاء")));
            h = CigramUI.dp(c, 20);
        }

        void setText(String value) {
            text = value;
            invalidateSelf();
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            Paint.FontMetrics fm = p.getFontMetrics();
            float y = b.centerY() - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(text, b.left, y, p);
        }

        @Override public int getIntrinsicWidth() { return w; }
        @Override public int getIntrinsicHeight() { return h; }
        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
