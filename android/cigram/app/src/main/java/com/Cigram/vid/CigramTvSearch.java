package com.Cigram.vid;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * CIGRAM TV EDITION — the Search panel: an on-screen keyboard driven entirely by the remote
 * (Arabic + English), with live results drawn from the SAME catalogue already in memory (no second
 * search engine). The results refresh as the query changes, without blocking the UI.
 */
final class CigramTvSearch {

    private CigramTvSearch() { }

    private static final String[] AR_ROWS = {
            "ض ص ث ق ف غ ع ه خ ح",
            "ش س ي ب ل ا ت ن م ك",
            "ظ ط ذ د ز ر و ة ى ئ",
            "٠ ١ ٢ ٣ ٤ ٥ ٦ ٧ ٨ ٩"
    };
    private static final String[] EN_ROWS = {
            "q w e r t y u i o p",
            "a s d f g h j k l",
            "z x c v b n m",
            "0 1 2 3 4 5 6 7 8 9"
    };

    static View build(final CigramTvActivity a) {
        final StringBuilder query = new StringBuilder();
        final boolean[] english = {false};

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(a.dp(28), a.dp(24), a.dp(40), a.dp(24));

        // results (left, fills the rest)
        final FrameLayout results = new FrameLayout(a);
        root.addView(results, new LinearLayout.LayoutParams(0, -1, 1f));

        // keyboard column (right)
        LinearLayout kb = new LinearLayout(a);
        kb.setOrientation(LinearLayout.VERTICAL);
        kb.setGravity(Gravity.RIGHT);

        final TextView field = new TextView(a);
        field.setText("ابحث عن فيلم أو مسلسل");
        field.setTextColor(CigramDesign.TEXT_2);
        field.setTextSize(18f);
        field.setTypeface(a.font(), Typeface.BOLD);
        field.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        field.setSingleLine(true);
        field.setPadding(a.dp(18), a.dp(14), a.dp(18), a.dp(14));
        android.graphics.drawable.GradientDrawable fieldBg = new android.graphics.drawable.GradientDrawable();
        fieldBg.setCornerRadius(a.dp(12));
        fieldBg.setColor(0xFF1A1A1D);
        fieldBg.setStroke(a.dp(1), CigramDesign.HAIRLINE);
        field.setBackground(fieldBg);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(a.dp(360), -2);
        flp.bottomMargin = a.dp(14);
        kb.addView(field, flp);

        final LinearLayout keysHolder = new LinearLayout(a);
        keysHolder.setOrientation(LinearLayout.VERTICAL);
        kb.addView(keysHolder, new LinearLayout.LayoutParams(a.dp(360), -2));

        final Runnable refresh = new Runnable() {
            @Override public void run() {
                String q = query.toString();
                field.setText(q.length() == 0 ? "ابحث عن فيلم أو مسلسل" : q);
                field.setTextColor(q.length() == 0 ? CigramDesign.TEXT_2 : CigramDesign.TEXT);
                fillResults(a, results, q);
            }
        };

        final Runnable[] rebuildKeys = new Runnable[1];
        rebuildKeys[0] = new Runnable() {
            @Override public void run() {
                keysHolder.removeAllViews();
                String[] rows = english[0] ? EN_ROWS : AR_ROWS;
                for (String row : rows) {
                    LinearLayout line = new LinearLayout(a);
                    line.setOrientation(LinearLayout.HORIZONTAL);
                    line.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                    line.setGravity(Gravity.CENTER);
                    for (String ch : row.split(" ")) {
                        final String key = ch;
                        line.addView(keyButton(a, key, false, new Runnable() {
                            @Override public void run() { query.append(key); refresh.run(); }
                        }));
                    }
                    LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
                    llp.topMargin = a.dp(6);
                    keysHolder.addView(line, llp);
                }
                // controls
                LinearLayout controls = new LinearLayout(a);
                controls.setOrientation(LinearLayout.HORIZONTAL);
                controls.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                controls.setGravity(Gravity.CENTER);
                controls.addView(keyButton(a, english[0] ? "عربي" : "ABC", true, new Runnable() {
                    @Override public void run() { english[0] = !english[0]; rebuildKeys[0].run(); }
                }));
                controls.addView(keyButton(a, "مسافة", true, new Runnable() {
                    @Override public void run() { query.append(' '); refresh.run(); }
                }));
                controls.addView(keyButton(a, "حذف", true, new Runnable() {
                    @Override public void run() {
                        if (query.length() > 0) query.deleteCharAt(query.length() - 1);
                        refresh.run();
                    }
                }));
                controls.addView(keyButton(a, "مسح", true, new Runnable() {
                    @Override public void run() { query.setLength(0); refresh.run(); }
                }));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
                clp.topMargin = a.dp(10);
                keysHolder.addView(controls, clp);
            }
        };
        rebuildKeys[0].run();

        root.addView(kb, new LinearLayout.LayoutParams(-2, -1));
        refresh.run();
        return root;
    }

    private static TextView keyButton(final CigramTvActivity a, String label, boolean wide, final Runnable onPress) {
        TextView k = new TextView(a);
        k.setText(label);
        k.setTextColor(CigramDesign.TEXT);
        k.setTextSize(wide ? 14f : 17f);
        k.setTypeface(a.font(), Typeface.BOLD);
        k.setGravity(Gravity.CENTER);
        k.setFocusable(true);
        k.setClickable(true);
        int h = a.dp(44);
        int w = wide ? a.dp(64) : a.dp(44);
        k.setPadding(a.dp(2), 0, a.dp(2), 0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(a.dp(10));
        bg.setColor(0xFF222226);
        k.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.leftMargin = a.dp(4);
        k.setLayoutParams(lp);
        k.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setCornerRadius(a.dp(10));
                g.setColor(has ? CigramDesign.ACCENT : 0xFF222226);
                v.setBackground(g);
                v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(100L).start();
            }
        });
        k.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onPress.run(); }
        });
        return k;
    }

    // ------------------------------------------------------------------ results

    private static void fillResults(CigramTvActivity a, FrameLayout holder, String query) {
        holder.removeAllViews();
        String q = normalize(query);
        if (q.length() == 0) {
            TextView hint = new TextView(a);
            hint.setText("اكتب للبحث في كل الأفلام والمسلسلات");
            hint.setTextColor(CigramDesign.TEXT_3);
            hint.setTextSize(16f);
            hint.setGravity(Gravity.CENTER);
            holder.addView(hint, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        List<JSONObject> matches = new ArrayList<JSONObject>();
        for (JSONObject o : a.catalog()) {
            String title = titleKey(o);
            if (title.contains(q)) {
                matches.add(o);
                if (matches.size() >= 60) break;
            }
        }
        if (matches.isEmpty()) {
            TextView none = new TextView(a);
            none.setText("لا نتائج لـ \"" + query + "\"");
            none.setTextColor(CigramDesign.TEXT_2);
            none.setTextSize(16f);
            none.setGravity(Gravity.CENTER);
            holder.addView(none, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        holder.addView(CigramTvCards.grid(a, matches), new FrameLayout.LayoutParams(-1, -1));
    }

    /** Light Arabic/Latin folding so "افلام" matches "أفلام" etc. */
    static String normalize(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("ـ", "")
                .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
                .replace('ى', 'ي').replace('ة', 'ه').replace('ؤ', 'و').replace('ئ', 'ي');
        return WS.matcher(t).replaceAll(" ").trim();
    }

    private static final java.util.regex.Pattern WS = java.util.regex.Pattern.compile("\\s+");

    /** Normalised titles, computed once per title (typing used to re-normalise the whole catalogue on every key). */
    private static final java.util.IdentityHashMap<JSONObject, String> TITLE_KEYS =
            new java.util.IdentityHashMap<JSONObject, String>();

    private static String titleKey(JSONObject o) {
        String key = TITLE_KEYS.get(o);
        if (key == null) {
            key = normalize(CigramTvCards.str(o, "title", "name", "series_title"));
            if (TITLE_KEYS.size() > 20000) TITLE_KEYS.clear();
            TITLE_KEYS.put(o, key);
        }
        return key;
    }
}
