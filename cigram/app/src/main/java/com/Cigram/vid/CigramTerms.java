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
 *
 * <b>19.2 — an account that accepted is remembered for good.</b>
 * <ul>
 *   <li>The register request now sends {@code accept_terms=true}; the auth worker only stored a consent when that
 *       field was present, so until now NO acceptance reached the server.</li>
 *   <li>Every login / register answer carries the account's {@code terms_accepted_at} / {@code terms_version}
 *       ({@link #learn}): an account that accepted on any phone is known as accepted on this one.</li>
 *   <li>An account that accepted on this phone before the server kept a record is reported once with
 *       {@code POST /terms/accept} (same session, in the background), so the record follows it to other phones.</li>
 *   <li>For an accepted account (same terms version) the consent box is not shown at all and
 *       {@link #checkBeforeRegister} passes: the question never comes back. A new terms {@link #VERSION} asks once more.</li>
 * </ul>
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

    private static final String AUTH_API = "https://cigram-auth-api.wwq-mixtv.workers.dev";

    private static String key(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** True when this account accepted the CURRENT terms version (on this phone, or on any phone per the server). */
    static boolean accepted(Context c, String email) {
        String k = key(email);
        if (c == null || k.length() == 0) return false;
        try {
            String v = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("accepted::" + k, "");
            int bar = v.indexOf('|');
            return bar > 0 && Integer.parseInt(v.substring(0, bar)) >= VERSION;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Called with the {@code user} object of a login / register / profile answer.
     * Server says accepted → remembered here. Server has no record but this phone has one → the server is told once.
     */
    static void learn(final Context c, final org.json.JSONObject user, final String token) {
        if (c == null || user == null) return;
        try {
            final String email = key(user.optString("email", ""));
            if (email.length() == 0) return;
            final Context app = c.getApplicationContext();
            String at = user.optString("terms_accepted_at", "").trim();
            if (at.length() > 0) {
                int version = 1;
                try { version = Integer.parseInt(user.optString("terms_version", "1").trim()); } catch (Throwable ignored) { }
                SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                if (!accepted(app, email) || !p.getBoolean("synced::" + email, false)) {
                    p.edit().putString("accepted::" + email, Math.max(1, version) + "|" + System.currentTimeMillis())
                            .putBoolean("synced::" + email, true).apply();
                }
                return;
            }
            if (!accepted(app, email) || token == null || token.trim().length() == 0) return;
            if (app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("synced::" + email, false)) return;
            new Thread(new Runnable() {
                @Override public void run() {
                    java.net.HttpURLConnection conn = null;
                    try {
                        conn = (java.net.HttpURLConnection) new java.net.URL(AUTH_API + "/terms/accept").openConnection();
                        conn.setRequestMethod("POST");
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(15000);
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Authorization", "Bearer " + token.trim());
                        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                        byte[] body = ("{\"accept_terms\":true,\"terms_version\":\"" + VERSION + "\"}").getBytes("UTF-8");
                        java.io.OutputStream os = conn.getOutputStream();
                        os.write(body);
                        os.close();
                        if (conn.getResponseCode() == 200) {
                            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("synced::" + email, true).apply();
                        }
                    } catch (Throwable ignored) {
                        // offline / old worker: tried again at the next login, the local record already hides the question
                    } finally {
                        if (conn != null) conn.disconnect();
                    }
                }
            }, "cigram-terms-sync").start();
        } catch (Throwable error) {
            android.util.Log.w("CigramTerms", "learn failed", error);
        }
    }

    /** The e-mail typed in the register form ("" when the field is not there). */
    private static String typedEmail(Activity a) {
        View f = a == null ? null : byId(a, "edittext_register_email");
        return f instanceof android.widget.TextView ? ((android.widget.TextView) f).getText().toString() : "";
    }

    /** Shows the consent box only for an account that has not accepted yet. */
    private static void sync(Activity a) {
        if (a == null) return;
        View box = a.getWindow().getDecorView().findViewWithTag(TAG_BOX);
        if (box != null) box.setVisibility(accepted(a, typedEmail(a)) ? View.GONE : View.VISIBLE);
    }

    /** Adds the consent box above the register button (idempotent; the box starts unchecked). */
    static void install(final Activity a) {
        if (a == null || a.isFinishing()) return;
        try {
            View button = byId(a, "button_register");
            if (button == null || !(button.getParent() instanceof LinearLayout)) return;
            LinearLayout parent = (LinearLayout) button.getParent();
            if (parent.findViewWithTag(TAG_BOX) != null) { sync(a); return; }
            View emailField = byId(a, "edittext_register_email");
            if (emailField instanceof android.widget.TextView && emailField.getTag(emailField.getId()) == null) {
                emailField.setTag(emailField.getId(), "cigram-terms-watch");
                ((android.widget.TextView) emailField).addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
                    @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
                    @Override public void afterTextChanged(android.text.Editable e) { sync(a); }
                });
            }
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
                    // the tick itself is the consent: it is saved the moment it is given, not only when the form is sent
                    if (on) recordAcceptance(a, typedEmail(a));
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = CigramUI.dp(a, 4);
            lp.bottomMargin = CigramUI.dp(a, 6);
            parent.addView(box, parent.indexOfChild(button), lp);
            sync(a);
        } catch (Throwable error) {
            android.util.Log.w("CigramTerms", "consent box failed", error);
        }
    }

    /** true = the user ticked the box; false = explains why and keeps the form as it is. */
    static boolean checkBeforeRegister(Activity a) {
        if (a != null && accepted(a, typedEmail(a))) return true;      // this account already accepted: never asked again
        View v = a == null ? null : a.getWindow().getDecorView().findViewWithTag(TAG_BOX);
        if (v instanceof CheckBox && ((CheckBox) v).isChecked()) return true;
        if (v == null) {
            // the box could not be built (unexpected layout): never create an account without consent
            install(a);
            v = a == null ? null : a.getWindow().getDecorView().findViewWithTag(TAG_BOX);
        }
        try {
            CigramSilent.toast(a, "يجب الموافقة على الشروط والأحكام قبل إنشاء الحساب", android.widget.Toast.LENGTH_LONG);
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
