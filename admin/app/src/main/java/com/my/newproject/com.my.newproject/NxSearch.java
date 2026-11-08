package com.my.newproject;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;

/**
 * Name search for the new add screens: type a name (or IMDb tt… / TMDB id / link) and the matching titles from
 * TMDB (which also resolves IMDb ids) appear as big posters with the name under each one. Tapping a poster picks it.
 */
final class NxSearch {

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

    NxSearch(Activity activity, String type, CgUi.Callback<JSONObject> onPick) {
        this.a = activity;
        this.type = type;
        this.onPick = onPick;
        view = CgUi.vbox(a);

        LinearLayout bar = CgUi.hbox(a);
        bar.setBackground(NxUi.surface(a, 18));
        bar.setPadding(dp(12), dp(4), dp(6), dp(4));
        bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView si = NxUi.icon(a, NxIcons.icon(CgIcons.SEARCH, NxUi.SUB), 0);
        bar.addView(si, new LinearLayout.LayoutParams(dp(22), dp(22)));
        input = new EditText(a);
        input.setBackground(null);
        input.setHint("series".equals(type) ? "اكتب اسم المسلسل…" : "اكتب اسم الفيلم…");
        input.setHintTextColor(NxUi.DIM);
        input.setTextColor(NxUi.TEXT);
        input.setTextSize(16);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setTextDirection(View.TEXT_DIRECTION_RTL);
        CgUi.applyFont(input, false);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        il.setMargins(dp(8), 0, dp(4), 0);
        bar.addView(input, il);
        final ImageView clear = NxUi.icon(a, NxIcons.icon(CgIcons.CLOSE, NxUi.SUB), 9);
        clear.setVisibility(View.GONE);
        clear.setClickable(true);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setText("");
            }
        });
        bar.addView(clear, new LinearLayout.LayoutParams(dp(38), dp(38)));
        view.addView(bar, new LinearLayout.LayoutParams(NxUi.MATCH, dp(54)));

        TextView hint = CgUi.text(a, "بالاسم، أو معرّف IMDb مثل tt1375666، أو رابط TMDB. اختر الصحيح بنفسك.", 11.5f, NxUi.DIM, false);
        hint.setPadding(dp(6), dp(6), dp(6), dp(4));
        view.addView(hint);

        results = CgUi.vbox(a);
        view.addView(results, NxUi.lp(a, NxUi.MATCH, NxUi.WRAP, 0, 6, 0, 0));

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int x, int y, int z) { }

            @Override
            public void onTextChanged(CharSequence s, int x, int y, int z) { }

            @Override
            public void afterTextChanged(Editable e) {
                h.removeCallbacks(debounced);
                String q = e.toString().trim();
                clear.setVisibility(q.length() > 0 ? View.VISIBLE : View.GONE);
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

    private int dp(float v) {
        return CgUi.dp(a, v);
    }

    void setQuery(String q) {
        input.setText(q == null ? "" : q);
        input.setSelection(input.getText().length());
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
        results.addView(skeleton());
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

    private int cellWidthPx() {
        int screen = a.getResources().getDisplayMetrics().widthPixels;
        return (screen - dp(24) - dp(12)) / 2;
    }

    private View skeleton() {
        LinearLayout grid = CgUi.vbox(a);
        int w = cellWidthPx();
        for (int r = 0; r < 2; r++) {
            LinearLayout row = CgUi.hbox(a);
            for (int c = 0; c < 2; c++) {
                View v = new View(a);
                v.setBackground(NxUi.flat(a, NxUi.SURF_FLAT, 16, 0));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, Math.round(w * 1.5f));
                lp.setMargins(c == 0 ? 0 : dp(12), 0, 0, 0);
                row.addView(v, lp);
                v.setAlpha(0.6f);
                NxUi.pulse(v);
            }
            grid.addView(row, NxUi.lp(a, NxUi.MATCH, NxUi.WRAP, 0, r == 0 ? 0 : 14, 0, 0));
        }
        return grid;
    }

    private void render(JSONArray list) {
        results.removeAllViews();
        if (list.length() == 0) {
            LinearLayout e = CgUi.vbox(a);
            e.setGravity(Gravity.CENTER_HORIZONTAL);
            e.setBackground(NxUi.dashed(a, NxUi.SUB, 18));
            e.setPadding(dp(16), dp(22), dp(16), dp(22));
            e.addView(NxUi.icon(a, NxIcons.icon(CgIcons.SEARCH, NxUi.SUB), 0), new LinearLayout.LayoutParams(dp(30), dp(30)));
            TextView t = CgUi.text(a, "لا توجد نتائج", 15, NxUi.TEXT, true);
            t.setGravity(Gravity.CENTER);
            e.addView(t, NxUi.lp(a, NxUi.MATCH, NxUi.WRAP, 0, 8, 0, 0));
            TextView s = CgUi.text(a, "جرّب اسماً آخر أو الاسم الإنكليزي أو معرّف IMDb.", 12, NxUi.SUB, false);
            s.setGravity(Gravity.CENTER);
            e.addView(s, new LinearLayout.LayoutParams(NxUi.MATCH, NxUi.WRAP));
            results.addView(e);
            return;
        }
        TextView count = CgUi.text(a, list.length() + " نتيجة — اضغط على البوستر الصحيح", 12, NxUi.SUB, true);
        count.setPadding(dp(4), 0, dp(4), dp(8));
        results.addView(count);
        int w = cellWidthPx();
        LinearLayout row = null;
        for (int i = 0; i < list.length(); i++) {
            final JSONObject r = list.optJSONObject(i);
            if (r == null) continue;
            if (i % 2 == 0) {
                row = CgUi.hbox(a);
                row.setGravity(Gravity.TOP);
                results.addView(row, NxUi.lp(a, NxUi.MATCH, NxUi.WRAP, 0, i == 0 ? 0 : 16, 0, 0));
            }
            View cell = cell(r, w);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, NxUi.WRAP);
            lp.setMargins(i % 2 == 0 ? 0 : dp(12), 0, 0, 0);
            row.addView(cell, lp);
            NxUi.enter(cell, Math.min(8, i) * 40L);
        }
    }

    private View cell(final JSONObject r, int w) {
        LinearLayout col = CgUi.vbox(a);
        FrameLayout poster = new FrameLayout(a);
        int ph = Math.round(w * 1.5f);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(NxUi.flat(a, NxUi.SURF_FLAT, 16, NxUi.LINE));
        iv.setClipToOutline(true);
        String p = CxJ.s(r, "poster");
        if (p.startsWith("http")) CgImg.load(iv, p, Math.max(300, w));
        poster.addView(iv, new FrameLayout.LayoutParams(w, ph));
        if (p.length() == 0) {
            ImageView ph2 = NxUi.icon(a, NxIcons.icon("series".equals(type) ? CgIcons.TV : CgIcons.FILM, NxUi.DIM), 0);
            poster.addView(ph2, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        }
        View shade = new View(a);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x00000000, 0xCC000000});
        g.setCornerRadius(dp(16));
        shade.setBackground(g);
        poster.addView(shade, new FrameLayout.LayoutParams(w, ph));
        double rating = r.optDouble("rating", 0);
        if (rating > 0) {
            TextView rt = CgUi.text(a, String.format(Locale.US, "★ %.1f", rating), 11.5f, 0xFFFFD166, true);
            CgUi.ltr(rt);
            rt.setBackground(CgUi.bg(a, 0xB3000000, 10, 0));
            rt.setPadding(dp(7), dp(2), dp(7), dp(2));
            FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(NxUi.WRAP, NxUi.WRAP, Gravity.TOP | Gravity.LEFT);
            rl.setMargins(dp(8), dp(8), 0, 0);
            poster.addView(rt, rl);
        }
        JSONObject ex = r.optJSONObject("existing");
        if (ex != null) {
            TextView b = CgUi.text(a, "موجود", 11, 0xFF1A1206, true);
            b.setBackground(CgUi.bg(a, CgCfg.WARN, 10, 0));
            b.setPadding(dp(8), dp(2), dp(8), dp(2));
            FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(NxUi.WRAP, NxUi.WRAP, Gravity.TOP | Gravity.RIGHT);
            bl.setMargins(0, dp(8), dp(8), 0);
            poster.addView(b, bl);
        }
        String year = CxJ.s(r, "year");
        if (year.length() > 0) {
            TextView y = CgUi.text(a, year, 12, 0xFFFFFFFF, true);
            CgUi.ltr(y);
            FrameLayout.LayoutParams yl = new FrameLayout.LayoutParams(NxUi.WRAP, NxUi.WRAP, Gravity.BOTTOM | Gravity.RIGHT);
            yl.setMargins(0, 0, dp(10), dp(8));
            poster.addView(y, yl);
        }
        NxUi.pressable(poster, NxUi.flat(a, 0x00000000, 16, 0), 16);
        col.addView(poster, new LinearLayout.LayoutParams(w, ph));

        TextView t = CgUi.text(a, CxJ.s(r, "title"), 13.5f, NxUi.TEXT, true);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t, NxUi.lp(a, NxUi.MATCH, NxUi.WRAP, 2, 8, 2, 0));
        String orig = CxJ.s(r, "original_title");
        if (orig.length() > 0 && !orig.equals(CxJ.s(r, "title"))) {
            TextView o = CgUi.text(a, orig, 11, NxUi.SUB, false);
            CgUi.ltr(o);
            o.setSingleLine(true);
            o.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(o, NxUi.lp(a, NxUi.MATCH, NxUi.WRAP, 2, 0, 2, 0));
        }
        poster.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final View pv = v;
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        pv.setScaleX(1f);
                        pv.setScaleY(1f);
                        try {
                            onPick.run(r);
                        } catch (Throwable ignored) { }
                    }
                }).start();
            }
        });
        return col;
    }
}
