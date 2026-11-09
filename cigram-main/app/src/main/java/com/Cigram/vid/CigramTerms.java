package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.Locale;

/**
 * Terms and Conditions consent for account creation (2026).
 *
 * <ul>
 *   <li>An UNCHECKED box «أوافق على الشروط والأحكام» is placed right above the register button every time the form is
 *       built; it is never pre-selected, not even for someone who accepted before.</li>
 *   <li>{@link #checkBeforeRegister} is called first thing by the register button: without the tick the request is
 *       never sent.</li>
 *   <li>The accepted version and the time are saved on the device (per e-mail) and sent with the register request
 *       ({@code terms_version}, {@code terms_accepted_at}). The auth worker must store them to keep a server-side
 *       record; until it does, the device record is the only one (see the engineering report).</li>
 * </ul>
 * The text itself is {@link CigramTermsActivity}. It has not been reviewed by a lawyer.
 */
final class CigramTerms {

    /** Bump when the text of CigramTermsActivity changes in substance (users then accept the new version). */
    static final int VERSION = 1;
    static final String VERSION_LABEL = "الإصدار 1 · نوفمبر 2026";

    private static final String PREFS = "cigram_terms";
    private static final String TAG_BOX = "cigram_terms_checkbox";

    private CigramTerms() { }

    private static View byId(Activity a, String name) {
        int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
        return id == 0 ? null : a.findViewById(id);
    }

    /** Adds the consent box above the register button (idempotent; the box starts unchecked). */
    static void install(final Activity a) {
        if (a == null || a.isFinishing()) return;
        try {
            View button = byId(a, "button_register");
            if (button == null || !(button.getParent() instanceof LinearLayout)) return;
            LinearLayout parent = (LinearLayout) button.getParent();
            if (parent.findViewWithTag(TAG_BOX) != null) return;
            CigramPalette pal = CigramPalette.of(a);

            CheckBox box = new CheckBox(a);
            box.setTag(TAG_BOX);
            box.setChecked(false);
            // the app has no RTL support switched on, so the tick box is moved to the RIGHT of the text by hand
            try {
                android.content.res.TypedArray ta = a.obtainStyledAttributes(new int[]{android.R.attr.listChoiceIndicatorMultiple});
                android.graphics.drawable.Drawable tick = ta.getDrawable(0);
                ta.recycle();
                if (tick != null) {
                    box.setButtonDrawable(null);
                    tick.setTintList(android.content.res.ColorStateList.valueOf(pal.text));
                    box.setCompoundDrawablesWithIntrinsicBounds(null, null, tick, null);
                    box.setCompoundDrawablePadding(CigramUI.dp(a, 10));
                }
            } catch (Throwable ignored) { }
            box.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            box.setTextColor(pal.text2);
            box.setTextSize(13.5f);
            box.setTypeface(CigramUI.font(a));
            box.setMinHeight(CigramUI.dp(a, 48));
            String before = "\u200Fأوافق على ";
            String link = "الشروط والأحكام";
            SpannableString text = new SpannableString(before + link);
            final int linkColor = pal.light ? 0xFF111114 : Color.WHITE;
            text.setSpan(new ClickableSpan() {
                @Override public void onClick(View widget) { open(a); }
                @Override public void updateDrawState(TextPaint ds) {
                    ds.setColor(linkColor);
                    ds.setUnderlineText(true);
                    ds.setFakeBoldText(true);
                }
            }, before.length(), text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            box.setText(text);
            box.setMovementMethod(LinkMovementMethod.getInstance());
            box.setContentDescription("أوافق على الشروط والأحكام، غير محدد");
            box.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    b.setContentDescription("أوافق على الشروط والأحكام، " + (on ? "محدد" : "غير محدد"));
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = CigramUI.dp(a, 4);
            lp.bottomMargin = CigramUI.dp(a, 6);
            parent.addView(box, parent.indexOfChild(button), lp);
        } catch (Throwable error) {
            android.util.Log.w("CigramTerms", "consent box failed", error);
        }
    }

    /** true = the user ticked the box; false = explains why and keeps the form as it is. */
    static boolean checkBeforeRegister(Activity a) {
        View v = a == null ? null : a.getWindow().getDecorView().findViewWithTag(TAG_BOX);
        if (v instanceof CheckBox && ((CheckBox) v).isChecked()) return true;
        if (v == null) {
            // the box could not be built (unexpected layout): never create an account without consent
            install(a);
            v = a == null ? null : a.getWindow().getDecorView().findViewWithTag(TAG_BOX);
        }
        try {
            Toast.makeText(a, "يجب الموافقة على الشروط والأحكام قبل إنشاء الحساب", Toast.LENGTH_LONG).show();
            if (v != null) {
                v.requestFocus();
                v.animate().translationX(CigramUI.dp(a, 6)).setDuration(60).withEndAction(new Runnable() {
                    @Override public void run() {
                        final View box = a.getWindow().getDecorView().findViewWithTag(TAG_BOX);
                        if (box != null) box.animate().translationX(0f).setDuration(120).start();
                    }
                }).start();
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** Saves the accepted version and time for this e-mail (called when the register request is sent). */
    static void recordAcceptance(Context c, String email) {
        try {
            String key = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
            long now = System.currentTimeMillis();
            SharedPreferences.Editor e = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            e.putInt("last_version", VERSION).putLong("last_accepted_at", now);
            if (key.length() > 0) e.putString("accepted::" + key, VERSION + "|" + now);
            e.apply();
        } catch (Throwable ignored) { }
    }

    static void open(Context c) {
        try {
            Intent i = new Intent(c, CigramTermsActivity.class);
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable error) {
            android.util.Log.w("CigramTerms", "open terms failed", error);
        }
    }
}
