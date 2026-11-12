package com.Cigram.vid;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Pattern;

/**
 * The public account id: "C" + 5 digits (for example C48213), unique per account, made by the server (cigram-auth-api v6).
 * New accounts receive it in the login / register answer. Old accounts have none until the server hands one out, so the
 * account page asks GET /me once and keeps the answer. The row on the account page copies it with one tap.
 * Stored in auth_session.public_id (cleared with the rest of auth_session on logout).
 */
public final class CigramPublicId {

    static final String TAG_ROW = "cigram_public_id_chip";
    private static final Pattern SHAPE = Pattern.compile("C\\d{5}");
    private static Handler main;
    private static volatile boolean asking;

    private CigramPublicId() { }

    private static synchronized Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    static boolean valid(String id) {
        return id != null && SHAPE.matcher(id).matches();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("auth_session", Context.MODE_PRIVATE);
    }

    /** The id of the logged-in account, or "" while unknown. */
    public static String get(Context c) {
        try {
            if (c == null || !CigramUserData.isLoggedIn(c)) return "";
            String id = prefs(c).getString("public_id", "");
            return valid(id) ? id : "";
        } catch (Throwable t) {
            return "";
        }
    }

    public static void save(Context c, String id) {
        try {
            if (valid(id)) prefs(c).edit().putString("public_id", id).apply();
        } catch (Throwable ignored) { }
    }

    /** Asks the server for the id when it is not known yet. {@code done} runs on the main thread after a real answer. */
    public static void ensure(final Context c, final Runnable done) {
        if (c == null || asking || get(c).length() > 0 || !CigramUserData.isLoggedIn(c)) return;
        final Context app = c.getApplicationContext();
        final String token = CigramUserData.token(app);
        if (token.length() == 0) return;
        asking = true;
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(CigramPlus.AUTH_BASE + "/me").openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                    if (conn.getResponseCode() == 200) {
                        InputStream in = conn.getInputStream();
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = in.read(buf)) > 0 && out.size() < 65536) out.write(buf, 0, n);
                        in.close();
                        JSONObject user = new JSONObject(out.toString("UTF-8")).optJSONObject("user");
                        String id = user == null ? "" : user.optString("public_id", "");
                        if (valid(id)) {
                            save(app, id);
                            if (done != null) main().post(done);
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (conn != null) { try { conn.disconnect(); } catch (Throwable ignored) { } }
                    asking = false;
                }
            }
        }, "cigram-public-id").start();
    }

    static void copy(Context c) {
        String id = get(c);
        if (id.length() == 0) return;
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Cigram ID", id));
            Toast.makeText(c, "تم نسخ المعرّف " + id, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(c, "تعذّر النسخ", Toast.LENGTH_SHORT).show();
        }
    }

    static final String TAG_TEXT = "cigram_public_id_text";

    /** Two overlapping rounded squares: the usual "copy" icon, drawn here (no resource needed). */
    private static final class CopyIcon extends android.graphics.drawable.Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        CopyIcon(int color) {
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public void draw(android.graphics.Canvas c) {
            float w = getBounds().width(), h = getBounds().height();
            p.setStrokeWidth(Math.max(1.5f, w * 0.085f));
            float r = w * 0.14f;
            c.drawRoundRect(new android.graphics.RectF(w * 0.30f, h * 0.30f, w * 0.86f, h * 0.88f), r, r, p);          // front sheet
            android.graphics.Path back = new android.graphics.Path();                                                  // sheet behind it
            back.moveTo(w * 0.18f, h * 0.62f);
            back.lineTo(w * 0.14f, h * 0.62f);
            back.quadTo(w * 0.12f, h * 0.62f, w * 0.12f, h * 0.60f);
            back.lineTo(w * 0.12f, h * 0.16f);
            back.quadTo(w * 0.12f, h * 0.12f, w * 0.16f, h * 0.12f);
            back.lineTo(w * 0.60f, h * 0.12f);
            back.quadTo(w * 0.64f, h * 0.12f, w * 0.64f, h * 0.16f);
            back.lineTo(w * 0.64f, h * 0.20f);
            c.drawPath(back, p);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * The small chip under the user name on the account page: near-black body, grey text,
     * [copy icon] on the left of the id. A tap copies the id. Hidden until the id is known ({@link #paintChip}).
     */
    static LinearLayout chip(final Activity a) {
        LinearLayout chip = new LinearLayout(a);
        chip.setTag(TAG_ROW);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);     // physical order: [icon] [C12345]
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(CigramUI.dp(a, 10), CigramUI.dp(a, 5), CigramUI.dp(a, 12), CigramUI.dp(a, 5));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xE61A1D22);
        bg.setCornerRadius(CigramUI.dp(a, 14));
        bg.setStroke(Math.max(1, CigramUI.dp(a, 1)), 0xFF3B4048);
        chip.setBackground(bg);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setContentDescription("معرّف حسابك، اضغط للنسخ");

        View icon = new View(a);
        icon.setBackground(new CopyIcon(0xFF9AA1AB));
        chip.addView(icon, new LinearLayout.LayoutParams(CigramUI.dp(a, 16), CigramUI.dp(a, 16)));

        TextView id = CigramUI.label(a, "", 14f, 0xFFB4BAC3, true);
        id.setTag(TAG_TEXT);
        id.setSingleLine(true);
        id.setTextDirection(View.TEXT_DIRECTION_LTR);
        id.setLetterSpacing(0.08f);
        id.setPadding(CigramUI.dp(a, 8), 0, 0, 0);
        id.setIncludeFontPadding(false);
        chip.addView(id, new LinearLayout.LayoutParams(-2, -2));

        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copy(a); }
        });
        chip.setVisibility(View.GONE);
        return chip;
    }

    /** Shows the stored id in the chip (the chip stays hidden while the id is still being fetched). */
    static void paintChip(View chip) {
        try {
            TextView t = (TextView) chip.findViewWithTag(TAG_TEXT);
            if (t == null) return;
            String id = get(chip.getContext());
            t.setText(id);
            chip.setVisibility(id.length() == 0 ? View.GONE : View.VISIBLE);
        } catch (Throwable ignored) { }
    }
}
