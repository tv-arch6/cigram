package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * «المتابعة بحساب Google» — one tap for a new viewer and for a returning one.
 *
 * The phone's Google account picker gives an ID token; the auth worker ({@code POST /google}, already live for the
 * web site) verifies it, signs the account in or creates it, and answers exactly like {@code /login}. The session is
 * then stored the way the e-mail login stores it, so the rest of the app cannot tell the difference.
 *
 * SETUP (once, by the owner — the button stays hidden until it is done):
 *   1. Google Cloud Console → Credentials → an OAuth client of type "Web application"; put its id in
 *      {@link #WEB_CLIENT_ID} here, in {@code googleClientId} of the site's js/config.js and in the worker variable
 *      GOOGLE_CLIENT_ID.
 *   2. In the same project, an OAuth client of type "Android" for package com.Cigram.vid with the SHA-1 of the
 *      signing key (one per key: debug, upload, Play app signing).
 *
 * This class is an Activity without a UI of its own: it shows the picker, talks to the worker and closes.
 */
public class CigramGoogleAuth extends Activity {

    /** The OAuth "Web application" client id. "" = Google sign-in is switched off and its button is not drawn. */
    static final String WEB_CLIENT_ID = "";

    private static final String API = "https://cigram-auth-api.wwq-mixtv.workers.dev/google";
    private static final int REQ = 9041;
    static final String TAG_BUTTON = "cigram_google_button";

    static boolean enabled() { return WEB_CLIENT_ID.trim().length() > 0; }

    // ------------------------------------------------------------------ the button (monochrome, like every icon)

    static View button(final Activity a) {
        final float d = a.getResources().getDisplayMetrics().density;
        CigramPalette pal = CigramPalette.of(a);
        LinearLayout b = new LinearLayout(a);
        b.setTag(TAG_BUTTON);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(27 * d);
        bg.setColor(pal.light ? 0xFF0B0B0C : 0xFFFFFFFF);
        b.setBackground(bg);
        final int ink = pal.light ? 0xFFFFFFFF : 0xFF000000;
        TextView t = CigramUI.label(a, "المتابعة بحساب Google", 15.5f, ink, true);
        t.setTypeface(CigramUI.font(a), Typeface.BOLD);
        b.addView(t);
        View g = new View(a) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF r = new RectF();
            @Override protected void onDraw(Canvas c) {
                float s = Math.min(getWidth(), getHeight()), w = s * 0.17f;
                p.setColor(ink);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(w);
                r.set(w, w, s - w, s - w);
                c.drawArc(r, 40f, 285f, false, p);                     // the open ring of the letter G
                c.drawLine(s / 2f, s / 2f, s - w * 0.6f, s / 2f, p);    // its bar
            }
        };
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams((int) (20 * d), (int) (20 * d));
        gl.leftMargin = (int) (10 * d);
        b.addView(g, gl);
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription("المتابعة بحساب Google");
        CigramAccountDesign.pressEffect(b);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try { a.startActivity(new Intent(a, CigramGoogleAuth.class)); } catch (Throwable ignored) { }
            }
        });
        return b;
    }

    // ------------------------------------------------------------------ the flow

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private GoogleSignInClient client;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!enabled()) { finish(); return; }
        try {
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(WEB_CLIENT_ID.trim()).requestEmail().requestProfile().build();
            client = GoogleSignIn.getClient(this, gso);
            if (state == null) startActivityForResult(client.getSignInIntent(), REQ);
        } catch (Throwable error) {
            android.util.Log.w("CigramGoogleAuth", "picker failed", error);
            finish();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ) return;
        String token = null;
        try {
            GoogleSignInAccount account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult();
            token = account == null ? null : account.getIdToken();
        } catch (Throwable error) {
            android.util.Log.w("CigramGoogleAuth", "no account", error);
        }
        if (token == null || token.length() == 0) { finish(); return; }     // cancelled: back to the page, silently
        exchange(token, false);
    }

    /** {@code acceptTerms}: sent on the second try, when the worker says the account is new and needs the consent. */
    private void exchange(final String idToken, final boolean acceptTerms) {
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject answer = null;
                try {
                    JSONObject body = new JSONObject().put("credential", idToken);
                    if (acceptTerms) body.put("accept_terms", true).put("terms_version", CigramTerms.VERSION);
                    answer = post(body.toString());
                } catch (Throwable ignored) { }
                final JSONObject root = answer;
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        if (root != null && root.optBoolean("terms_required", false) && !acceptTerms) { askTerms(idToken); return; }
                        if (root != null && store(root)) restartAccount();
                        else finish();                                   // no error text: the page stays as it was
                    }
                });
            }
        }, "cigram-google").start();
    }

    /** A brand-new account: the consent is asked once, here, and really saved (device + server). */
    private void askTerms(final String idToken) {
        CigramUI.sheet(this).logo()
                .title("حساب جديد في سيكرام")
                .message("هذا أول دخول بحساب Google هذا. بمتابعتك توافق على الشروط والأحكام.")
                .primary("أوافق وأنشئ الحساب", CigramUI.GREEN, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) { exchange(idToken, true); return false; }
                })
                .secondary("قراءة الشروط", new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) { CigramTerms.open(CigramGoogleAuth.this); return true; }
                })
                .show();
    }

    private boolean store(JSONObject root) {
        try {
            if (!root.optBoolean("success", false)) return false;
            String token = root.optString("token", "").trim();
            JSONObject user = root.optJSONObject("user");
            if (token.length() == 0 || user == null) return false;
            Context app = getApplicationContext();
            CigramTerms.learn(app, user, token);
            String email = user.optString("email", "");
            CigramTerms.recordAcceptance(app, email);
            SharedPreferences auth = getSharedPreferences("auth_session", MODE_PRIVATE);
            auth.edit().putBoolean("logged_in", true)
                    .putString("token", token)
                    .putString("expires_at", root.optString("expires_at", ""))
                    .putString("user_id", user.optString("id", ""))
                    .putString("public_id", user.optString("public_id", ""))
                    .putString("name", user.optString("name", ""))
                    .putString("email", email)
                    .putString("gender", user.optString("gender", "unspecified"))
                    .putString("avatar_url", user.optString("avatar_url", ""))
                    .apply();
            CigramSession.commit(app, email);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    private void restartAccount() {
        try { if (client != null) client.signOut(); } catch (Throwable ignored) { }   // the picker is offered again next time
        Intent i = new Intent(this, AccountActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(i);
        finish();
        overridePendingTransition(0, 0);
    }

    private static JSONObject post(String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(12000);
            c.setReadTimeout(15000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setRequestProperty("Accept", "application/json");
            OutputStream out = c.getOutputStream();
            try { out.write(body.getBytes("UTF-8")); } finally { out.close(); }
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            if (in == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0 && buf.size() < 200000) buf.write(b, 0, n);
            in.close();
            return new JSONObject(buf.toString("UTF-8"));
        } catch (Throwable error) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
