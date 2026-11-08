package com.my.newproject;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;

/**
 * Smart TMDB search: by name, TMDB id, IMDb id (tt...) or a themoviedb.org link. Debounced, cached, shows
 * poster / title / year cards and never picks a result on its own.
 */
final class CxSearchBox {

    private static final HashMap<String, JSONArray> CACHE = new HashMap<String, JSONArray>();

    final LinearLayout view;
    final EditText input;
    private final LinearLayout results;
    private final Activity a;
    private final String type;
    private final CgUi.Callback<JSONObject> onPick;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int token;
    private String lastQuery = "";

    private final Runnable debounced = new Runnable() {
        @Override
        public void run() {
            search(input.getText().toString().trim());
        }
    };

    CxSearchBox(Activity activity, String type, CgUi.Callback<JSONObject> onPick) {
        this.a = activity;
        this.type = type;
        this.onPick = onPick;
        view = CgUi.vbox(a);
        input = CgUi.field(a, "ابحث بالاسم أو TMDB ID أو IMDb (tt1234567)", false);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        view.addView(input, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        TextView hint = CgUi.muted(a, "اختر النتيجة الصحيحة بنفسك — لا يتم اختيار أي نتيجة تلقائياً.");
        hint.setTextSize(11.5f);
        hint.setPadding(CgUi.dp(a, 4), CgUi.dp(a, 4), CgUi.dp(a, 4), CgUi.dp(a, 6));
        view.addView(hint);
        results = CgUi.vbox(a);
        view.addView(results, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int x, int y, int z) { }

            @Override
            public void onTextChanged(CharSequence s, int x, int y, int z) { }

            @Override
            public void afterTextChanged(Editable e) {
                h.removeCallbacks(debounced);
                String q = e.toString().trim();
                if (q.length() < 2 && !q.matches("\\d+")) {
                    token++;
                    results.removeAllViews();
                    return;
                }
                h.postDelayed(debounced, 450);
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                h.removeCallbacks(debounced);
                search(input.getText().toString().trim());
                return true;
            }
        });
    }

    void setQuery(String q) {
        input.setText(q);
        if (q != null) input.setSelection(q.length());
    }

    void stop() {
        h.removeCallbacksAndMessages(null);
        token++;
    }

    private void search(final String q) {
        if (q.length() == 0) return;
        if (q.length() < 2 && !q.matches("\\d+")) return;
        final String key = type + "|" + q.toLowerCase(Locale.US);
        lastQuery = q;
        final int my = ++token;
        JSONArray cached = CACHE.get(key);
        if (cached != null) {
            render(cached);
            return;
        }
        results.removeAllViews();
        results.addView(CxUi.skeleton(a, 3));
        CgHttp.async(a, new CgHttp.Work<JSONArray>() {
            @Override
            public JSONArray run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("query", q);
                b.put("type", type);
                JSONObject d = CxApi.post("/admin/tmdb/search", b);
                return d.optJSONArray("results") == null ? new JSONArray() : d.optJSONArray("results");
            }
        }, new CgHttp.Done<JSONArray>() {
            @Override
            public void ok(JSONArray list) {
                if (my != token) return;
                CACHE.put(key, list);
                render(list);
            }

            @Override
            public void fail(String message) {
                if (my != token) return;
                results.removeAllViews();
                results.addView(CgUi.errorCard(a, message, new Runnable() {
                    @Override
                    public void run() {
                        search(lastQuery);
                    }
                }));
            }
        });
    }

    private void render(JSONArray list) {
        results.removeAllViews();
        if (list.length() == 0) {
            results.addView(CgUi.empty(a, "لا توجد نتائج", "جرّب اسماً آخر أو معرّف TMDB / IMDb. يمكنك أيضاً الإدخال يدوياً."));
            return;
        }
        for (int i = 0; i < list.length(); i++) {
            final JSONObject r = list.optJSONObject(i);
            if (r == null) continue;
            results.addView(card(r));
        }
    }

    private View card(final JSONObject r) {
        LinearLayout row = CgUi.hbox(a);
        row.setBackground(CgUi.bg(a, CgCfg.CARD, 14, CgCfg.STROKE));
        row.setPadding(CgUi.dp(a, 8), CgUi.dp(a, 8), CgUi.dp(a, 8), CgUi.dp(a, 8));
        row.setClickable(true);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        CgImg.load(iv, CxJ.s(r, "poster"), 200);
        row.addView(iv, new LinearLayout.LayoutParams(CgUi.dp(a, 60), CgUi.dp(a, 90)));
        LinearLayout col = CgUi.vbox(a);
        TextView title = CgUi.text(a, CxJ.s(r, "title"), 14.5f, CgCfg.TEXT, true);
        title.setMaxLines(2);
        col.addView(title);
        String orig = CxJ.s(r, "original_title");
        if (orig.length() > 0 && !orig.equals(CxJ.s(r, "title"))) {
            TextView o = CgUi.muted(a, orig);
            CgUi.ltr(o);
            o.setSingleLine(true);
            col.addView(o);
        }
        String year = CxJ.s(r, "year");
        double rating = r.optDouble("rating", 0);
        String meta = (year.length() > 0 ? year : "—") + (rating > 0 ? "  •  ★ " + String.format(Locale.US, "%.1f", rating) : "");
        col.addView(CgUi.text(a, meta, 12, CgCfg.ACCENT, true));
        String ov = CxJ.s(r, "overview");
        if (ov.length() > 0) {
            TextView t = CgUi.muted(a, ov);
            t.setTextSize(11.5f);
            t.setMaxLines(2);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(t);
        }
        JSONObject ex = r.optJSONObject("existing");
        if (ex != null) {
            TextView b = CgUi.badge(a, "موجود بالفعل: " + CxJ.s(ex, "title"), CgCfg.WARN);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            lp.topMargin = CgUi.dp(a, 4);
            col.addView(b, lp);
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        clp.setMargins(CgUi.dp(a, 10), 0, CgUi.dp(a, 4), 0);
        row.addView(col, clp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onPick.run(r);
            }
        });
        row.setLayoutParams(CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        return row;
    }
}
