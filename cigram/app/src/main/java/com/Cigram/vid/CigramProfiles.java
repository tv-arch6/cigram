package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Cigram Profiles — several viewers on one account, each with their own «أكمل المشاهدة», saved positions and
 * «شاهد لاحقاً».
 *
 * <ul>
 *   <li>The account itself is the first profile («الرئيسي»): its data, and its sync with the server, are exactly what
 *   they were before 19.2 — nobody loses anything by updating.</li>
 *   <li>Up to four more profiles per account (name + colour), stored on this phone. Their watching data is kept under
 *   the account's key plus the profile id ({@link #suffix}), so it never mixes with another profile's and is removed
 *   with the profile.</li>
 *   <li>The account's server history feeds only the main profile (it belongs to the account, not to a person).</li>
 * </ul>
 * Pure parts (names, limits, the stored list) are unit-tested.
 */
final class CigramProfiles {

    private CigramProfiles() { }

    static final String PREFS = "cigram_profiles";
    static final String MAIN = "main";
    static final int MAX_EXTRA = 4;
    static final int[] COLORS = {0xFFE21B14, 0xFF5CC8F0, 0xFF3DDC97, 0xFFF2C14E, 0xFFA78BFA, 0xFFFF6FA5, 0xFFFF9F45};

    static final class Profile {
        String id = MAIN;
        String name = "";
        int color = COLORS[0];

        boolean isMain() { return MAIN.equals(id); }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try { o.put("id", id).put("name", name).put("color", color); } catch (Exception ignored) { }
            return o;
        }

        static Profile of(JSONObject o) {
            Profile p = new Profile();
            p.id = o.optString("id", MAIN);
            p.name = o.optString("name", "");
            p.color = o.optInt("color", COLORS[0]);
            return p;
        }

        String initial() {
            String n = name.trim();
            if (n.length() == 0) return "؟";
            return n.substring(0, Character.charCount(n.codePointAt(0)));
        }
    }

    // ------------------------------------------------------------------ pure rules

    /** A profile name: trimmed, without control / direction characters, 1..18 characters. "" when unusable. */
    static String cleanName(String raw) {
        if (raw == null) return "";
        String t = raw.replaceAll("[\\u0000-\\u001F\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]", "").replaceAll("\\s+", " ").trim();
        if (t.length() > 18) t = t.substring(0, 18).trim();
        return t;
    }

    /** True when a new profile with this name can be added to {@code existing}. */
    static String canAdd(List<Profile> existing, String name) {
        String n = cleanName(name);
        if (n.length() == 0) return "اكتب اسماً للملف.";
        int extra = 0;
        for (Profile p : existing) {
            if (!p.isMain()) extra++;
            if (p.name.equalsIgnoreCase(n)) return "يوجد ملف بهذا الاسم.";
        }
        if (extra >= MAX_EXTRA) return "الحد الأقصى " + (MAX_EXTRA + 1) + " ملفات لكل حساب.";
        return "";
    }

    /** "" for the main profile, "~id" for the others: appended to the account key of watching data. */
    static String suffixOf(String activeId) {
        return activeId == null || activeId.length() == 0 || MAIN.equals(activeId) ? "" : "~" + activeId;
    }

    static JSONArray toArray(List<Profile> list) {
        JSONArray a = new JSONArray();
        for (Profile p : list) if (!p.isMain()) a.put(p.toJson());
        return a;
    }

    // ------------------------------------------------------------------ storage (per account)

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String owner(Context c) {
        try {
            return CigramUserData.isLoggedIn(c) ? CigramUserData.accountHash(c) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** All profiles of the signed-in account, the main one first. A visitor has only the main one. */
    static List<Profile> list(Context c) {
        List<Profile> out = new ArrayList<Profile>();
        Profile main = new Profile();
        String display = "";
        try { display = CigramUserData.displayName(c); } catch (Throwable ignored) { }
        main.name = display == null || display.trim().length() == 0 ? "الرئيسي" : cleanName(display);
        out.add(main);
        String o = owner(c);
        if (o.length() == 0) return out;
        try {
            JSONArray a = new JSONArray(prefs(c).getString("list::" + o, "[]"));
            for (int i = 0; i < a.length() && out.size() <= MAX_EXTRA; i++) {
                JSONObject j = a.optJSONObject(i);
                if (j != null && j.optString("id").length() > 0 && !MAIN.equals(j.optString("id"))) out.add(Profile.of(j));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    static Profile active(Context c) {
        String o = owner(c);
        List<Profile> all = list(c);
        if (o.length() == 0) return all.get(0);
        String id = prefs(c).getString("active::" + o, MAIN);
        for (Profile p : all) if (p.id.equals(id)) return p;
        return all.get(0);
    }

    /** "" while the main profile is active; "~id" otherwise. */
    static String suffix(Context c) {
        if (c == null) return "";
        try { return suffixOf(active(c).id); } catch (Throwable t) { return ""; }
    }

    static boolean isMainActive(Context c) {
        return suffix(c).length() == 0;
    }

    static void setActive(Context c, String id) {
        String o = owner(c);
        if (o.length() == 0) return;
        prefs(c).edit().putString("active::" + o, id == null ? MAIN : id).apply();
    }

    static String add(Context c, String name) {
        String o = owner(c);
        if (o.length() == 0) return "سجّل الدخول أولاً لإضافة ملفات.";
        List<Profile> all = list(c);
        String why = canAdd(all, name);
        if (why.length() > 0) return why;
        Profile p = new Profile();
        p.id = Long.toString(System.currentTimeMillis(), 36);
        p.name = cleanName(name);
        p.color = COLORS[(all.size()) % COLORS.length];
        all.add(p);
        prefs(c).edit().putString("list::" + o, toArray(all).toString()).apply();
        return "";
    }

    static void rename(Context c, String id, String name) {
        String o = owner(c);
        String n = cleanName(name);
        if (o.length() == 0 || n.length() == 0 || MAIN.equals(id)) return;
        List<Profile> all = list(c);
        for (Profile p : all) if (p.id.equals(id)) p.name = n;
        prefs(c).edit().putString("list::" + o, toArray(all).toString()).apply();
    }

    /** Removes a profile and every piece of watching data kept for it on this phone. */
    static void remove(Context c, String id) {
        String o = owner(c);
        if (o.length() == 0 || MAIN.equals(id)) return;
        List<Profile> all = list(c);
        List<Profile> keep = new ArrayList<Profile>();
        for (Profile p : all) if (!p.id.equals(id)) keep.add(p);
        SharedPreferences.Editor e = prefs(c).edit().putString("list::" + o, toArray(keep).toString());
        if (id.equals(prefs(c).getString("active::" + o, MAIN))) e.putString("active::" + o, MAIN);
        e.apply();
        String scoped = o + suffixOf(id);
        try {
            SharedPreferences cw = c.getApplicationContext().getSharedPreferences("continue_watching", Context.MODE_PRIVATE);
            cw.edit().remove("items_" + scoped).apply();
            SharedPreferences wp = c.getApplicationContext().getSharedPreferences("watch_progress", Context.MODE_PRIVATE);
            SharedPreferences.Editor we = wp.edit();
            for (String k : wp.getAll().keySet()) if (k.startsWith("account_" + scoped + "|")) we.remove(k);
            we.apply();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ the picker

    /** Who is watching? A row of round avatars; tap = switch, long press = rename / delete, «+» = add. */
    static void showPicker(final Activity a, final Runnable afterSwitch) {
        if (a == null || a.isFinishing()) return;
        if (!CigramUserData.isLoggedIn(a)) {
            android.widget.Toast.makeText(a, "سجّل الدخول أولاً: الملفات الشخصية تُحفظ داخل حسابك.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        final android.app.Dialog d = new android.app.Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        final float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding((int) (20 * dp), (int) (22 * dp), (int) (20 * dp), (int) (18 * dp));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF111114);
        bg.setCornerRadius(24 * dp);
        bg.setStroke(Math.max(1, (int) dp), 0x22FFFFFF);
        root.setBackground(bg);
        TextView title = new TextView(a);
        title.setText("من يشاهد الآن؟");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(20);
        title.setTypeface(CigramTypefaces.app(a.getAssets()), Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView sub = new TextView(a);
        sub.setText("لكل ملف «أكمل المشاهدة» و«شاهد لاحقاً» خاص به");
        sub.setTextColor(0x99FFFFFF);
        sub.setTextSize(12.5f);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub, new LinearLayout.LayoutParams(-1, -2));

        android.widget.GridLayout grid = new android.widget.GridLayout(a);
        grid.setColumnCount(3);
        grid.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        final List<Profile> all = list(a);
        final String activeId = active(a).id;
        for (final Profile p : all) {
            grid.addView(tile(a, p.initial(), p.name + (p.isMain() ? " (الحساب)" : ""), p.color, p.id.equals(activeId), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    d.dismiss();
                    if (p.id.equals(activeId)) return;
                    setActive(a, p.id);
                    android.widget.Toast.makeText(a, "تشاهد الآن باسم «" + p.name + "»", android.widget.Toast.LENGTH_SHORT).show();
                    if (afterSwitch != null) afterSwitch.run();
                }
            }, p.isMain() ? null : new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { d.dismiss(); manage(a, p, afterSwitch); return true; }
            }));
        }
        if (all.size() <= MAX_EXTRA) {
            grid.addView(tile(a, "+", "إضافة ملف", 0xFF2A2A30, false, new View.OnClickListener() {
                @Override public void onClick(View v) { d.dismiss(); askName(a, null, afterSwitch); }
            }, null));
        }
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
        gl.topMargin = (int) (16 * dp);
        root.addView(grid, gl);
        TextView hint = new TextView(a);
        hint.setText("اضغط مطولاً على ملف لتعديل اسمه أو حذفه");
        hint.setTextColor(0x66FFFFFF);
        hint.setTextSize(11.5f);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = (int) (10 * dp);
        root.addView(hint, hl);
        d.setContentView(root);
        try {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            d.getWindow().setLayout((int) (Math.min(a.getResources().getDisplayMetrics().widthPixels - 32 * dp, 420 * dp)), -2);
        } catch (Throwable ignored) { }
        d.show();
    }

    private static View tile(Activity a, String letter, String label, int color, boolean active, View.OnClickListener click, View.OnLongClickListener longClick) {
        float dp = a.getResources().getDisplayMetrics().density;
        LinearLayout t = new LinearLayout(a);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
        Avatar av = new Avatar(a, letter, color, active);
        t.addView(av, new LinearLayout.LayoutParams((int) (70 * dp), (int) (70 * dp)));
        TextView n = new TextView(a);
        n.setText(label);
        n.setTextColor(active ? 0xFFFFFFFF : 0xB3FFFFFF);
        n.setTextSize(12.5f);
        n.setMaxLines(1);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        n.setGravity(Gravity.CENTER);
        n.setMaxWidth((int) (92 * dp));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.topMargin = (int) (6 * dp);
        t.addView(n, nl);
        t.setClickable(true);
        t.setFocusable(true);
        t.setContentDescription(label + (active ? "، الملف الحالي" : ""));
        t.setOnClickListener(click);
        if (longClick != null) t.setOnLongClickListener(longClick);
        return t;
    }

    private static void manage(final Activity a, final Profile p, final Runnable after) {
        new android.app.AlertDialog.Builder(a).setTitle(p.name)
                .setItems(new CharSequence[]{"تعديل الاسم", "حذف الملف وبيانات مشاهدته"}, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (w == 0) askName(a, p, after);
                        else new android.app.AlertDialog.Builder(a).setTitle("حذف «" + p.name + "»؟")
                                .setMessage("يُحذف الملف و«أكمل المشاهدة» الخاص به من هذا الجهاز. لا يتأثر حسابك.")
                                .setPositiveButton("حذف", new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface dd, int ww) {
                                        boolean wasActive = p.id.equals(active(a).id);
                                        remove(a, p.id);
                                        if (wasActive && after != null) after.run();
                                    }
                                }).setNegativeButton("إلغاء", null).show();
                    }
                }).show();
    }

    private static void askName(final Activity a, final Profile editing, final Runnable after) {
        final EditText in = new EditText(a);
        in.setSingleLine(true);
        in.setHint("اسم الملف (مثال: أحمد، الأطفال)");
        if (editing != null) in.setText(editing.name);
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(a);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(in, new LinearLayout.LayoutParams(-1, -2));
        new android.app.AlertDialog.Builder(a).setTitle(editing == null ? "ملف جديد" : "تعديل الاسم").setView(box)
                .setPositiveButton(editing == null ? "إضافة" : "حفظ", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (editing != null) { rename(a, editing.id, in.getText().toString()); return; }
                        String why = add(a, in.getText().toString());
                        if (why.length() > 0) android.widget.Toast.makeText(a, why, android.widget.Toast.LENGTH_LONG).show();
                        else showPicker(a, after);
                    }
                }).setNegativeButton("إلغاء", null).show();
    }

    /** Round avatar with the profile's initial; the active one carries a white ring. */
    static final class Avatar extends View {
        private final String letter;
        private final int color;
        private final boolean active;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Avatar(Context c, String letter, int color, boolean active) {
            super(c);
            this.letter = letter;
            this.color = color;
            this.active = active;
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f, d = getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.FILL);
            p.setShader(new android.graphics.LinearGradient(0, 0, w, h, color, darker(color), android.graphics.Shader.TileMode.CLAMP));
            c.drawCircle(w / 2f, h / 2f, r - (active ? 4 * d : 1), p);
            p.setShader(null);
            if (active) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(2.2f * d);
                p.setColor(0xFFFFFFFF);
                c.drawCircle(w / 2f, h / 2f, r - 1.2f * d, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(r * 0.85f);
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(letter, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, p);
        }

        private static int darker(int c) {
            return 0xFF000000 | ((int) (((c >> 16) & 0xFF) * 0.6f) << 16) | ((int) (((c >> 8) & 0xFF) * 0.6f) << 8) | (int) ((c & 0xFF) * 0.6f);
        }
    }

    /** Re-opens the home so every row reads the new profile's data. */
    static void restartHome(Activity a) {
        try {
            Intent i = new Intent(a, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            a.startActivity(i);
            a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable ignored) { }
    }
}
