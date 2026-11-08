package com.my.newproject;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "إضافة سريعة": one screen to add a movie or a whole series.
 *
 *  1. one search box looks in movies AND series at the same time and shows a poster grid;
 *  2. tap a poster: a bottom sheet with the few options that matter (seasons, Arabic subtitles, section);
 *  3. one big button: the screen shows a live checklist while the Worker does the real work with the same
 *     routes the classic screens use (duplicate check, create, VidBolt episodes, subtitles);
 *  4. success screen: open it, add another, or undo everything that was just done.
 */
public class QuickAddActivity extends CgBase {

    private static final HashMap<String, JSONArray> CACHE = new HashMap<String, JSONArray>();

    private static final int PENDING = 0;
    private static final int RUNNING = 1;
    private static final int OK = 2;
    private static final int WARN = 3;
    private static final int FAIL = 4;

    private interface Job {
        void run() throws Exception;
    }

    /** ScrollView that never grows taller than a given height (used by the bottom sheet). */
    private static final class MaxScroll extends ScrollView {
        private final int max;

        MaxScroll(Context c, int max) {
            super(c);
            this.max = max;
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
        }
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final List<String> undoIds = Collections.synchronizedList(new ArrayList<String>());

    private EditText input;
    private LinearLayout resultsBox;
    private final TextView[] filterChips = new TextView[3];
    private int filter;
    private int token;
    private String lastQuery = "";
    private Dialog sheet;
    private boolean working;

    private TextView[] stepIcons;
    private TextView[] stepNotes;

    private final Runnable debounced = new Runnable() {
        @Override
        public void run() {
            search(input.getText().toString().trim());
        }
    };

    @Override
    protected String screenTitle() {
        return "إضافة سريعة";
    }

    @Override
    protected void onBuild() {
        buildSearchScreen();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        if (sheet != null && sheet.isShowing()) sheet.dismiss();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (working) {
            CgUi.toast(this, "انتظر قليلاً حتى تكتمل العملية.");
            return;
        }
        super.onBackPressed();
    }

    // ================================================================ search

    private void buildSearchScreen() {
        working = false;
        token++;
        body.removeAllViews();
        LinearLayout top = CgUi.vbox(this);
        top.setPadding(CgUi.dp(this, 14), CgUi.dp(this, 12), CgUi.dp(this, 14), CgUi.dp(this, 4));
        top.addView(CgUi.text(this, "ماذا تريد أن تضيف؟", 21, CgCfg.TEXT, true));
        TextView sub = CgUi.muted(this, "اكتب الاسم أو الصق رابط TMDB / IMDb، ثم اضغط على الغلاف الصحيح.");
        sub.setPadding(0, CgUi.dp(this, 2), 0, CgUi.dp(this, 10));
        top.addView(sub);

        LinearLayout row = CgUi.hbox(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        input = CgUi.field(this, "🔍  ابحث عن فيلم أو مسلسل...", false);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setSingleLine(true);
        row.addView(input, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        TextView paste = CgUi.smallButton(this, "لصق", 1);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData cd = cm == null ? null : cm.getPrimaryClip();
                    if (cd != null && cd.getItemCount() > 0) {
                        CharSequence t = cd.getItemAt(0).coerceToText(QuickAddActivity.this);
                        if (t != null && t.toString().trim().length() > 0) {
                            input.setText(t.toString().trim());
                            input.setSelection(input.getText().length());
                            search(input.getText().toString().trim());
                            return;
                        }
                    }
                    CgUi.toast(QuickAddActivity.this, "الحافظة فارغة.");
                } catch (Exception e) {
                    CgUi.toast(QuickAddActivity.this, "تعذر قراءة الحافظة.");
                }
            }
        });
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        pl.setMargins(CgUi.dp(this, 8), 0, 0, CgUi.dp(this, 10));
        row.addView(paste, pl);
        top.addView(row);

        LinearLayout chips = CgUi.hbox(this);
        String[] labels = {"الكل", "🎬 أفلام", "📺 مسلسلات"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView c = CgUi.chip(this, labels[i], i == filter);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    filter = idx;
                    for (int k = 0; k < 3; k++) CgUi.setChipState(filterChips[k], k == filter);
                    String q = input.getText().toString().trim();
                    if (q.length() >= 2 || q.matches("\\d+")) search(q);
                }
            });
            filterChips[i] = c;
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            cl.setMargins(0, 0, CgUi.dp(this, 8), 0);
            chips.addView(c, cl);
        }
        top.addView(chips);
        body.addView(top, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        resultsBox = CgUi.vbox(this);
        resultsBox.setPadding(CgUi.dp(this, 14), CgUi.dp(this, 10), CgUi.dp(this, 14), CgUi.dp(this, 60));
        sv.addView(resultsBox, new ScrollView.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        body.addView(sv, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));
        showIntro();

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int x, int y, int z) { }

            @Override
            public void onTextChanged(CharSequence s, int x, int y, int z) { }

            @Override
            public void afterTextChanged(Editable e) {
                ui().removeCallbacks(debounced);
                String q = e.toString().trim();
                if (q.length() < 2 && !q.matches("\\d+")) {
                    token++;
                    showIntro();
                    return;
                }
                ui().postDelayed(debounced, 450);
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                ui().removeCallbacks(debounced);
                search(input.getText().toString().trim());
                hideKeyboard();
                return true;
            }
        });
    }

    private void showIntro() {
        if (resultsBox == null) return;
        resultsBox.removeAllViews();
        LinearLayout c = CgUi.card(this);
        c.addView(CgUi.text(this, "كيف تعمل؟", 15, CgCfg.TEXT, true));
        c.addView(introStep("١", "ابحث", "الأفلام والمسلسلات تظهر معاً بأغلفتها."));
        c.addView(introStep("٢", "اختر", "اضغط على الغلاف الصحيح وحدد المواسم والترجمة."));
        c.addView(introStep("٣", "أضف", "زر واحد، ونتابع لك كل خطوة حتى النهاية."));
        resultsBox.addView(c);
        TextView hint = CgUi.muted(this, "كل شيء يُحفظ مع نسخة احتياطية، ويمكنك التراجع بعد الإضافة مباشرة.");
        hint.setPadding(CgUi.dp(this, 4), CgUi.dp(this, 12), CgUi.dp(this, 4), 0);
        resultsBox.addView(hint);
    }

    private View introStep(String n, String title, String desc) {
        LinearLayout r = CgUi.hbox(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, CgUi.dp(this, 10), 0, 0);
        TextView b = CgUi.text(this, n, 15, CgCfg.ON_ACCENT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(CgUi.bg(this, CgCfg.ACCENT_D, 16, 0));
        r.addView(b, new LinearLayout.LayoutParams(CgUi.dp(this, 32), CgUi.dp(this, 32)));
        LinearLayout col = CgUi.vbox(this);
        col.addView(CgUi.text(this, title, 14, CgCfg.TEXT, true));
        col.addView(CgUi.muted(this, desc));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        cl.setMargins(CgUi.dp(this, 12), 0, 0, 0);
        r.addView(col, cl);
        return r;
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && input != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        } catch (Exception ignored) { }
    }

    private void search(final String q) {
        if (q.length() == 0) return;
        if (q.length() < 2 && !q.matches("\\d+")) return;
        lastQuery = q;
        final int f = filter;
        final String key = f + "|" + q.toLowerCase(Locale.US);
        final int my = ++token;
        JSONArray cached = CACHE.get(key);
        if (cached != null) {
            renderResults(cached);
            return;
        }
        resultsBox.removeAllViews();
        resultsBox.addView(CxUi.skeleton(this, 3));
        pool.execute(new Runnable() {
            @Override
            public void run() {
                JSONArray list = null;
                String err = null;
                try {
                    list = searchBoth(q, f);
                } catch (Exception e) {
                    err = CgHttp.friendly(e.getMessage() == null ? "" : e.getMessage());
                }
                final JSONArray fl = list;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!alive() || my != token) return;
                        if (fl != null) {
                            CACHE.put(key, fl);
                            renderResults(fl);
                        } else {
                            resultsBox.removeAllViews();
                            resultsBox.addView(CgUi.errorCard(QuickAddActivity.this, fe, new Runnable() {
                                @Override
                                public void run() {
                                    search(lastQuery);
                                }
                            }));
                        }
                    }
                });
            }
        });
    }

    private JSONArray one(String q, String type) throws Exception {
        JSONObject b = new JSONObject();
        b.put("query", q);
        b.put("type", type);
        JSONObject d = CxApi.post("/admin/tmdb/search", b);
        JSONArray r = d.optJSONArray("results");
        return r == null ? new JSONArray() : r;
    }

    /** Movies and series in parallel, merged by popularity (f: 0 all, 1 movies, 2 series). */
    private JSONArray searchBoth(final String q, int f) throws Exception {
        final JSONArray[] out = new JSONArray[2];
        final Exception[] err = new Exception[2];
        Thread t = null;
        if (f != 1) {
            t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        out[1] = one(q, "series");
                    } catch (Exception e) {
                        err[1] = e;
                    }
                }
            });
            t.start();
        }
        if (f != 2) {
            try {
                out[0] = one(q, "movie");
            } catch (Exception e) {
                err[0] = e;
            }
        }
        if (t != null) t.join();
        if (out[0] == null && out[1] == null) {
            Exception e = err[0] != null ? err[0] : err[1];
            throw e != null ? e : new Exception("تعذر البحث.");
        }
        ArrayList<JSONObject> all = new ArrayList<JSONObject>();
        HashSet<String> seen = new HashSet<String>();
        for (int k = 0; k < 2; k++) {
            if (out[k] == null) continue;
            for (int i = 0; i < out[k].length(); i++) {
                JSONObject o = out[k].optJSONObject(i);
                if (o == null) continue;
                String type = k == 1 ? "series" : "movie";
                CxJ.put(o, "type", type);
                if (seen.add(type + o.optInt("tmdb_id"))) all.add(o);
            }
        }
        Collections.sort(all, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return b.optInt("popularity") - a.optInt("popularity");
            }
        });
        JSONArray res = new JSONArray();
        for (int i = 0; i < all.size() && i < 30; i++) res.put(all.get(i));
        return res;
    }

    private void renderResults(JSONArray list) {
        resultsBox.removeAllViews();
        if (list.length() == 0) {
            resultsBox.addView(CgUi.empty(this, "لا توجد نتائج", "جرّب اسماً آخر (بالإنجليزية غالباً أدق) أو الصق رابط TMDB / IMDb."));
            return;
        }
        int sw = getResources().getDisplayMetrics().widthPixels;
        int gap = CgUi.dp(this, 10);
        int cell = (sw - CgUi.dp(this, 28) - gap * 2) / 3;
        LinearLayout row = null;
        for (int i = 0; i < list.length(); i++) {
            if (i % 3 == 0) {
                row = CgUi.hbox(this);
                row.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 14));
                resultsBox.addView(row);
            }
            JSONObject card = list.optJSONObject(i);
            View v = card == null ? new View(this) : cell(card, cell);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cell, CgUi.WRAP);
            if (i % 3 != 2) lp.setMargins(gap, 0, 0, 0);
            row.addView(v, lp);
        }
    }

    private View cell(final JSONObject card, int w) {
        final boolean series = "series".equals(CxJ.s(card, "type"));
        LinearLayout c = CgUi.vbox(this);
        c.setClickable(true);
        FrameLayout pf = new FrameLayout(this);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final int radius = CgUi.dp(this, 12);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        iv.setClipToOutline(true);
        CgImg.load(iv, CxJ.s(card, "poster"), 320);
        pf.addView(iv, new FrameLayout.LayoutParams(w, w * 3 / 2));
        TextView tag = CgUi.badge(this, series ? "مسلسل" : "فيلم", series ? CgCfg.WARN : CgCfg.ACCENT);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP, Gravity.TOP | Gravity.START);
        tl.setMargins(CgUi.dp(this, 5), CgUi.dp(this, 5), CgUi.dp(this, 5), 0);
        pf.addView(tag, tl);
        if (card.optJSONObject("existing") != null) {
            TextView ex = CgUi.text(this, "✔ موجود", 11, CgCfg.GOOD, true);
            ex.setGravity(Gravity.CENTER);
            ex.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.PAGE, 0xE6), 8, CgCfg.GOOD));
            ex.setPadding(CgUi.dp(this, 6), CgUi.dp(this, 3), CgUi.dp(this, 6), CgUi.dp(this, 3));
            FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP, Gravity.BOTTOM | Gravity.END);
            el.setMargins(CgUi.dp(this, 5), 0, CgUi.dp(this, 5), CgUi.dp(this, 5));
            pf.addView(ex, el);
        }
        c.addView(pf, new LinearLayout.LayoutParams(w, w * 3 / 2));
        TextView t = CgUi.text(this, CxJ.s(card, "title"), 12.5f, CgCfg.TEXT, true);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, CgUi.dp(this, 6), 0, 0);
        c.addView(t);
        double rating = card.optDouble("rating", 0);
        String year = CxJ.s(card, "year");
        String meta = (year.length() > 0 ? year : "—") + (rating > 0 ? "  ★ " + String.format(Locale.US, "%.1f", rating) : "");
        TextView m = CgUi.muted(this, meta);
        m.setTextSize(11);
        c.addView(m);
        CgUi.pressScale(c);
        c.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideKeyboard();
                openSheet(card);
            }
        });
        return c;
    }

    // ============================================================== the sheet

    private void openSheet(final JSONObject card) {
        showLoading("جارٍ تجهيز البيانات...");
        pool.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject d = null;
                String err = null;
                try {
                    JSONObject b = new JSONObject();
                    b.put("type", CxJ.s(card, "type"));
                    b.put("tmdb_id", String.valueOf(card.optInt("tmdb_id")));
                    d = CxApi.post("/admin/tmdb/details", b);
                } catch (Exception e) {
                    err = e.getMessage() == null ? "تعذر جلب البيانات." : e.getMessage();
                }
                final JSONObject fd = d;
                final String fe = err;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!alive()) return;
                        hideLoading();
                        if (fd == null) CgUi.info(QuickAddActivity.this, "تعذر جلب البيانات", fe);
                        else showSheet(card, fd);
                    }
                });
            }
        });
    }

    private static String genreText(JSONArray g) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; g != null && i < g.length() && i < 3; i++) {
            String n;
            JSONObject o = g.optJSONObject(i);
            n = o != null ? o.optString("name") : g.optString(i);
            if (n == null || n.length() == 0) continue;
            if (sb.length() > 0) sb.append(" • ");
            sb.append(n);
        }
        return sb.toString();
    }

    private void showSheet(final JSONObject card, final JSONObject d) {
        final boolean series = "series".equals(CxJ.s(card, "type"));
        final JSONArray dups = d.optJSONArray("duplicates");
        final JSONObject existing = dups != null && dups.length() > 0 ? dups.optJSONObject(0) : null;
        final int screenH = getResources().getDisplayMetrics().heightPixels;

        final Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        sheet = dlg;
        LinearLayout root = CgUi.vbox(this);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(CgCfg.CARD);
        float r = CgUi.dp(this, 22);
        shape.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        shape.setStroke(CgUi.dp(this, 1), CgCfg.STROKE);
        root.setBackground(shape);
        root.setPadding(CgUi.dp(this, 16), CgUi.dp(this, 10), CgUi.dp(this, 16), CgUi.dp(this, 16));
        try {
            root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        } catch (Exception ignored) { }

        View handle = new View(this);
        handle.setBackground(CgUi.bg(this, CgCfg.STROKE, 3, 0));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(CgUi.dp(this, 42), CgUi.dp(this, 4));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        hl.bottomMargin = CgUi.dp(this, 12);
        root.addView(handle, hl);

        MaxScroll sv = new MaxScroll(this, (int) (screenH * 0.66f));
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout content = CgUi.vbox(this);
        sv.addView(content, new ScrollView.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        root.addView(sv, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        // header
        LinearLayout head = CgUi.hbox(this);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final int rad = CgUi.dp(this, 12);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), rad);
            }
        });
        iv.setClipToOutline(true);
        CgImg.load(iv, CxJ.s(d, "poster").length() > 0 ? CxJ.s(d, "poster") : CxJ.s(card, "poster"), 320);
        head.addView(iv, new LinearLayout.LayoutParams(CgUi.dp(this, 76), CgUi.dp(this, 114)));
        LinearLayout col = CgUi.vbox(this);
        TextView title = CgUi.text(this, CxJ.s(d, "title"), 18, CgCfg.TEXT, true);
        title.setMaxLines(2);
        col.addView(title);
        String orig = CxJ.s(d, "original_title");
        if (orig.length() > 0 && !orig.equals(CxJ.s(d, "title"))) {
            TextView o = CgUi.muted(this, orig);
            CgUi.ltr(o);
            o.setSingleLine(true);
            col.addView(o);
        }
        String meta = (series ? "مسلسل" : "فيلم") + (CxJ.s(d, "year").length() > 0 ? "  •  " + CxJ.s(d, "year") : "")
                + (CxJ.s(d, "rating_text").length() > 0 ? "  •  ★ " + CxJ.s(d, "rating_text") : "")
                + (series && d.optInt("season_count") > 0 ? "  •  " + d.optInt("season_count") + " مواسم" : "")
                + (!series && CxJ.s(d, "duration").length() > 0 ? "  •  " + CxJ.s(d, "duration") : "");
        col.addView(CgUi.text(this, meta, 12.5f, CgCfg.ACCENT, true));
        String genres = genreText(d.optJSONArray("genres"));
        if (genres.length() > 0) col.addView(CgUi.muted(this, genres));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        cl.setMargins(CgUi.dp(this, 12), 0, 0, 0);
        head.addView(col, cl);
        content.addView(head);

        String desc = CxJ.s(d, "description");
        if (desc.length() > 0) {
            TextView dv = CgUi.muted(this, desc);
            dv.setMaxLines(3);
            dv.setEllipsize(TextUtils.TruncateAt.END);
            dv.setPadding(0, CgUi.dp(this, 10), 0, 0);
            content.addView(dv);
        }

        final LinearLayout actions = CgUi.hbox(this);
        actions.setPadding(0, CgUi.dp(this, 14), 0, 0);
        root.addView(actions, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        // an existing movie: only "open" makes sense
        if (existing != null && !series) {
            content.addView(CxUi.notice(this, CxUi.WARN, "هذا الفيلم موجود بالفعل في المكتبة: " + CxJ.s(existing, "title") + "."), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 0));
            final String exId = CxJ.s(existing, "id");
            TextView open = CgUi.button(this, "فتح الموجود", 0);
            open.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dlg.dismiss();
                    CxNav.openItem(QuickAddActivity.this, exId);
                }
            });
            TextView close = CgUi.button(this, "إغلاق", 1);
            close.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dlg.dismiss();
                }
            });
            actions.addView(open, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1.4f));
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
            ml.setMargins(CgUi.dp(this, 8), 0, 0, 0);
            actions.addView(close, ml);
            showSheetWindow(dlg, root);
            return;
        }

        if (existing != null) {
            content.addView(CxUi.notice(this, CxUi.INFO, "المسلسل موجود بالفعل. اختر المواسم التي تريد إضافتها إليه."), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 0));
        }

        // options
        final String secId[] = {""};
        final String secName[] = {""};
        JSONArray sug = d.optJSONArray("suggested_sections");
        if (sug != null && sug.length() > 0 && sug.optJSONObject(0) != null) {
            secId[0] = CxJ.s(sug.optJSONObject(0), "id");
            secName[0] = CxJ.s(sug.optJSONObject(0), "name");
        }
        final JSONArray seasons = d.optJSONArray("seasons");
        final int sc = seasons == null ? 0 : seasons.length();
        final boolean[] picked = new boolean[sc];
        for (int i = 0; i < sc; i++) picked[i] = true;
        final CgUi.Toggle tVid = CgUi.toggle(this, "🎞  رابط VidBolt تلقائي", true);
        final CgUi.Toggle tSub = CgUi.toggle(this, "🔤  ترجمة عربية تلقائية", !series);
        final TextView go = CgUi.button(this, "", 0);

        final Runnable refreshGo = new Runnable() {
            @Override
            public void run() {
                if (!series) {
                    go.setText(CgUi.rich(QuickAddActivity.this, "✨  أضف الفيلم الآن", CgCfg.ON_ACCENT, 14));
                    return;
                }
                int n = 0;
                for (int i = 0; i < picked.length; i++) if (picked[i]) n++;
                String label = existing != null
                        ? (n > 0 ? "✨  أضف " + n + " موسم" : "اختر موسماً واحداً على الأقل")
                        : (n > 0 ? "✨  أضف المسلسل مع " + n + " موسم" : "✨  أنشئ المسلسل فقط");
                go.setText(CgUi.rich(QuickAddActivity.this, label, CgCfg.ON_ACCENT, 14));
            }
        };

        LinearLayout opts = CgUi.card(this);
        if (series) {
            opts.addView(CgUi.text(this, "المواسم", 14.5f, CgCfg.TEXT, true));
            if (sc == 0) {
                opts.addView(CgUi.muted(this, "لا توجد معلومات مواسم في TMDB؛ سيُنشأ سجل المسلسل فقط ويمكنك إضافة الحلقات لاحقاً."));
            } else {
                opts.addView(CgUi.muted(this, "كل موسم يُنشأ بحلقاته وروابط VidBolt جاهزة."));
                final TextView[] sChips = new TextView[sc];
                HorizontalScrollView hs = new HorizontalScrollView(this);
                hs.setHorizontalScrollBarEnabled(false);
                LinearLayout cr = CgUi.hbox(this);
                final TextView all = CgUi.chip(this, "الكل", true);
                cr.addView(all, chipLp());
                for (int i = 0; i < sc; i++) {
                    final int idx = i;
                    JSONObject so = seasons.optJSONObject(i);
                    int n = so == null ? 0 : so.optInt("season");
                    int ec = so == null ? 0 : so.optInt("episode_count");
                    TextView ch = CgUi.chip(this, "م" + n + (ec > 0 ? " · " + ec : ""), true);
                    ch.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            picked[idx] = !picked[idx];
                            CgUi.setChipState(sChips[idx], picked[idx]);
                            boolean every = true;
                            for (int k = 0; k < picked.length; k++) if (!picked[k]) every = false;
                            CgUi.setChipState(all, every);
                            refreshGo.run();
                        }
                    });
                    sChips[i] = ch;
                    cr.addView(ch, chipLp());
                }
                all.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean every = true;
                        for (int k = 0; k < picked.length; k++) if (!picked[k]) every = false;
                        boolean to = !every;
                        for (int k = 0; k < picked.length; k++) {
                            picked[k] = to;
                            CgUi.setChipState(sChips[k], to);
                        }
                        CgUi.setChipState(all, to);
                        refreshGo.run();
                    }
                });
                hs.addView(cr);
                opts.addView(hs, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 6));
            }
            opts.addView(CgUi.divider(this));
            opts.addView(tSub);
            opts.addView(CgUi.muted(this, "تُجلب لكل حلقة من SubDL ثم SubSource وOpenSubtitles. قد تزيد المدة كثيراً، ويمكنك فعلها لاحقاً من شاشة الترجمة."));
        } else {
            opts.addView(tVid);
            opts.addView(CgUi.muted(this, "يُبنى الرابط من رقم TMDB فلا تحتاج لصق أي شيء."));
            opts.addView(CgUi.divider(this));
            opts.addView(tSub);
            opts.addView(CgUi.muted(this, "تُجلب من SubDL ثم SubSource وOpenSubtitles، تُرفع وتُربط بالفيلم."));
        }
        content.addView(opts, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 0));

        // section
        if (existing == null) {
            JSONArray all = d.optJSONArray("sections");
            final ArrayList<JSONObject> secs = new ArrayList<JSONObject>();
            for (int i = 0; all != null && i < all.length(); i++) {
                JSONObject s = all.optJSONObject(i);
                if (s == null) continue;
                String ty = CxJ.s(s, "type");
                if (ty.length() == 0 || "mixed".equals(ty) || ty.equals(series ? "series" : "movie")) secs.add(s);
            }
            if (!secs.isEmpty()) {
                LinearLayout sc2 = CgUi.card(this);
                sc2.addView(CgUi.text(this, "القسم في الرئيسية", 14.5f, CgCfg.TEXT, true));
                HorizontalScrollView hs = new HorizontalScrollView(this);
                hs.setHorizontalScrollBarEnabled(false);
                LinearLayout cr = CgUi.hbox(this);
                final ArrayList<TextView> chips = new ArrayList<TextView>();
                final ArrayList<String> ids = new ArrayList<String>();
                final ArrayList<String> names = new ArrayList<String>();
                ids.add("");
                names.add("");
                for (int i = 0; i < secs.size(); i++) {
                    ids.add(CxJ.s(secs.get(i), "id"));
                    names.add(CxJ.s(secs.get(i), "name"));
                }
                for (int i = 0; i < ids.size(); i++) {
                    final int idx = i;
                    TextView ch = CgUi.chip(this, i == 0 ? "بدون قسم" : names.get(i), ids.get(i).equals(secId[0]));
                    ch.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            secId[0] = ids.get(idx);
                            secName[0] = names.get(idx);
                            for (int k = 0; k < chips.size(); k++) CgUi.setChipState(chips.get(k), k == idx);
                        }
                    });
                    chips.add(ch);
                    cr.addView(ch, chipLp());
                }
                hs.addView(cr);
                sc2.addView(hs, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
                if (secId[0].length() > 0) sc2.addView(CgUi.muted(this, "اخترنا القسم الأنسب حسب التصنيف، يمكنك تغييره."));
                content.addView(sc2, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
            }
        }

        // actions
        refreshGo.run();
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (series) {
                    int n = 0;
                    for (int i = 0; i < picked.length; i++) if (picked[i]) n++;
                    if (existing != null && n == 0) {
                        CgUi.toast(QuickAddActivity.this, "اختر موسماً واحداً على الأقل.");
                        return;
                    }
                    dlg.dismiss();
                    runSeries(d, existing, picked, tSub.on, secId[0], secName[0]);
                } else {
                    dlg.dismiss();
                    runMovie(d, tVid.on, tSub.on, secId[0], secName[0]);
                }
            }
        });
        TextView cancel = CgUi.button(this, "إلغاء", 1);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
            }
        });
        actions.addView(go, new LinearLayout.LayoutParams(0, CgUi.WRAP, 2.2f));
        LinearLayout.LayoutParams cll = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        cll.setMargins(CgUi.dp(this, 8), 0, 0, 0);
        actions.addView(cancel, cll);
        showSheetWindow(dlg, root);
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        lp.setMargins(0, 0, CgUi.dp(this, 8), 0);
        return lp;
    }

    private void showSheetWindow(Dialog dlg, View root) {
        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(CgUi.MATCH, CgUi.WRAP);
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        dlg.show();
    }

    // ================================================================ progress

    private void showProgress(String title, String poster, String[] labels) {
        working = true;
        body.removeAllViews();
        LinearLayout p = scrollBody();
        LinearLayout head = CgUi.hbox(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final int rad = CgUi.dp(this, 10);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), rad);
            }
        });
        iv.setClipToOutline(true);
        CgImg.load(iv, poster, 200);
        head.addView(iv, new LinearLayout.LayoutParams(CgUi.dp(this, 56), CgUi.dp(this, 84)));
        LinearLayout col = CgUi.vbox(this);
        col.addView(CgUi.text(this, title, 17, CgCfg.TEXT, true));
        col.addView(CgUi.muted(this, "نعمل على الإضافة الآن... لا تغلق الشاشة."));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        cl.setMargins(CgUi.dp(this, 12), 0, 0, 0);
        head.addView(col, cl);
        p.addView(head, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 14));

        LinearLayout card = CgUi.card(this);
        stepIcons = new TextView[labels.length];
        stepNotes = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            LinearLayout r = CgUi.hbox(this);
            r.setPadding(0, CgUi.dp(this, 8), 0, CgUi.dp(this, 8));
            TextView ic = CgUi.text(this, "○", 18, CgCfg.MUTED, true);
            ic.setGravity(Gravity.CENTER);
            r.addView(ic, new LinearLayout.LayoutParams(CgUi.dp(this, 30), CgUi.WRAP));
            LinearLayout c2 = CgUi.vbox(this);
            c2.addView(CgUi.text(this, labels[i], 14.5f, CgCfg.TEXT, true));
            TextView note = CgUi.muted(this, "");
            note.setVisibility(View.GONE);
            c2.addView(note);
            LinearLayout.LayoutParams c2l = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
            c2l.setMargins(CgUi.dp(this, 8), 0, 0, 0);
            r.addView(c2, c2l);
            card.addView(r);
            stepIcons[i] = ic;
            stepNotes[i] = note;
        }
        p.addView(card);
    }

    /** Thread-safe: updates one line of the checklist. */
    private void step(final int i, final int state, final String note) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!alive() || stepIcons == null || i < 0 || i >= stepIcons.length) return;
                TextView ic = stepIcons[i];
                if (state == PENDING) {
                    ic.setText("○");
                    ic.setTextColor(CgCfg.MUTED);
                } else if (state == RUNNING) {
                    ic.setText("◐");
                    ic.setTextColor(CgCfg.ACCENT);
                } else if (state == OK) {
                    ic.setText("✔");
                    ic.setTextColor(CgCfg.GOOD);
                } else if (state == WARN) {
                    ic.setText("⚠");
                    ic.setTextColor(CgCfg.WARN);
                } else {
                    ic.setText("✖");
                    ic.setTextColor(CgCfg.BAD);
                }
                TextView n = stepNotes[i];
                if (note == null || note.length() == 0) {
                    n.setVisibility(View.GONE);
                } else {
                    n.setText(note);
                    n.setTextColor(state == WARN ? CgCfg.WARN : state == FAIL ? CgCfg.BAD : CgCfg.MUTED);
                    n.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void launch(final Job job, final Runnable retry, final String existingId) {
        working = true;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    job.run();
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!alive()) return;
                            String id = existingId;
                            if (e instanceof CxApi.Failure) {
                                CxApi.Failure f = (CxApi.Failure) e;
                                if (f.is("DUPLICATE_CONTENT") || f.is("DUPLICATE_SERIES")) id = CxJ.s(f.existing(), "id");
                            }
                            showFailure(e.getMessage() == null ? "حدث خطأ غير متوقع." : e.getMessage(), retry, id);
                        }
                    });
                }
            }
        });
    }

    private void showFailure(String message, final Runnable retry, final String existingId) {
        working = false;
        if (stepIcons != null) {
            for (int i = 0; i < stepIcons.length; i++) {
                if ("◐".equals(stepIcons[i].getText().toString())) {
                    stepIcons[i].setText("✖");
                    stepIcons[i].setTextColor(CgCfg.BAD);
                }
            }
        }
        LinearLayout p = (LinearLayout) ((ScrollView) body.getChildAt(0)).getChildAt(0);
        p.addView(CxUi.notice(this, CxUi.ERROR, message), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 0));
        LinearLayout row = CgUi.hbox(this);
        if (existingId != null && existingId.length() > 0) {
            TextView open = CgUi.button(this, "فتح الموجود", 0);
            open.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CxNav.openItem(QuickAddActivity.this, existingId);
                }
            });
            row.addView(open, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        } else {
            TextView again = CgUi.button(this, "↻  إعادة المحاولة", 0);
            again.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    retry.run();
                }
            });
            row.addView(again, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1.3f));
        }
        TextView back = CgUi.button(this, "رجوع للبحث", 1);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                buildSearchScreen();
            }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        bl.setMargins(CgUi.dp(this, 8), 0, 0, 0);
        row.addView(back, bl);
        p.addView(row, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 0));
        if (!undoIds.isEmpty()) {
            TextView undo = CgUi.button(this, "↩  تراجع عن كل ما تم", 2);
            undo.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    undoAll();
                }
            });
            p.addView(undo, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        }
    }

    // ================================================================ movie

    private void runMovie(final JSONObject d, final boolean vid, final boolean subs, final String secId, final String secName) {
        final String tmdbId = String.valueOf(d.optInt("tmdb_id"));
        final String title = CxJ.s(d, "title");
        final String poster = CxJ.s(d, "poster");
        ArrayList<String> labels = new ArrayList<String>();
        labels.add("فحص التكرار");
        labels.add(vid ? "حفظ الفيلم مع رابط VidBolt" : "حفظ الفيلم");
        if (subs) labels.add("الترجمة العربية");
        showProgress(title, poster, labels.toArray(new String[0]));
        undoIds.clear();
        launch(new Job() {
            @Override
            public void run() throws Exception {
                step(0, RUNNING, "");
                JSONObject q = new JSONObject();
                q.put("type", "movie");
                q.put("tmdb", tmdbId);
                q.put("imdb", CxJ.s(d, "imdb_id"));
                q.put("slug", CxJ.s(d, "suggested_slug"));
                q.put("title", title);
                q.put("year", CxJ.s(d, "year"));
                JSONArray matches = CxApi.post("/admin/duplicates/check", q).optJSONArray("matches");
                for (int i = 0; matches != null && i < matches.length(); i++) {
                    JSONObject mm = matches.getJSONObject(i);
                    JSONArray why = mm.optJSONArray("reasons");
                    boolean real = false;
                    for (int k = 0; why != null && k < why.length(); k++) if (!"slug".equals(why.optString(k))) real = true;
                    if (real) {
                        step(0, FAIL, "موجود بالفعل: " + CxJ.s(mm, "title"));
                        JSONObject extra = new JSONObject();
                        extra.put("existing", mm);
                        throw new CxApi.Failure("هذا الفيلم موجود بالفعل في المكتبة.", "DUPLICATE_CONTENT", extra, 409);
                    }
                }
                step(0, OK, "غير موجود، يمكن الإضافة");

                step(1, RUNNING, "");
                JSONObject f = new JSONObject();
                String[][] map = {{"title", "title"}, {"original_title", "original_title"}, {"description", "description"}, {"year", "year"},
                        {"category", "category"}, {"poster", "poster"}, {"backdrop", "backdrop"}, {"slug", "suggested_slug"},
                        {"imdb_id", "imdb_id"}, {"duration", "duration"}};
                for (int i = 0; i < map.length; i++) {
                    String v = CxJ.s(d, map[i][1]);
                    if (v.length() > 0) f.put(map[i][0], v);
                }
                if (secId.length() > 0) {
                    f.put("section_id", secId);
                    f.put("section_name", secName);
                }
                f.put("tmdb", tmdbId);
                if (CxJ.s(d, "rating_text").length() > 0) f.put("imdb", CxJ.s(d, "rating_text"));
                String link = vid ? CgVidbolt.movie(tmdbId) : "";
                if (link.length() > 0) {
                    f.put("video", link);
                    f.put("link", link);
                }
                JSONObject b = new JSONObject();
                b.put("type", "movie");
                b.put("fields", f);
                JSONObject saved = CxApi.post("/admin/item/create", b);
                final JSONObject item = saved.optJSONObject("item");
                if (item == null) throw new CxApi.Failure("لم يؤكد الخادم الحفظ. أعد المحاولة.", "NO_ITEM", null, 0);
                if (saved.optString("undo_id", "").length() > 0) undoIds.add(saved.optString("undo_id"));
                step(1, OK, link.length() > 0 ? "تم الحفظ وربط الفيلم بـ VidBolt" : "تم الحفظ بدون رابط فيديو");
                final String id = CxJ.s(item, "id");

                String subNote = "";
                if (subs) {
                    step(2, RUNNING, "نبحث في SubDL ثم SubSource ثم OpenSubtitles...");
                    try {
                        JSONObject sb = new JSONObject();
                        sb.put("item_id", id);
                        sb.put("mode", "upload");
                        sb.put("attach", true);
                        JSONObject r = CxApi.post("/admin/subs/fetch", sb);
                        step(2, OK, sourceName(CxJ.s(r, "source")) + " • " + r.optInt("cues") + " سطراً");
                        subNote = "ومعها ترجمة عربية";
                    } catch (CxApi.Failure sf) {
                        step(2, WARN, sf.getMessage() + " — يمكنك المحاولة لاحقاً من شاشة الترجمة.");
                        subNote = "بدون ترجمة (لم تتوفر الآن)";
                    }
                }
                final String note = (link.length() > 0 ? "جاهز للمشاهدة" : "أُضيف بدون رابط فيديو") + (subNote.length() > 0 ? " • " + subNote : "");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        CgData cur = CgData.cur();
                        if (cur != null) cur.replaceItem(item);
                    }
                });
                finishOk(id, CxJ.s(item, "title"), CxJ.s(item, "poster").length() > 0 ? CxJ.s(item, "poster") : poster, "فيلم", note);
            }
        }, new Runnable() {
            @Override
            public void run() {
                runMovie(d, vid, subs, secId, secName);
            }
        }, "");
    }

    // =============================================================== series

    private static String keyOfHeader(JSONObject dup, String tmdbId) {
        String id = CxJ.s(dup, "id");
        CgData cur = CgData.cur();
        JSONObject h = cur == null || id.length() == 0 ? null : cur.byId(id);
        if (h != null) return CgData.seriesKey(h);
        String sid = CxJ.s(dup, "series_id");
        if (sid.length() > 0) return sid;
        return id.length() > 0 ? id : "series_" + tmdbId;
    }

    private void runSeries(final JSONObject d, final JSONObject existingDup, final boolean[] picked, final boolean subs,
                           final String secId, final String secName) {
        final String tmdbId = String.valueOf(d.optInt("tmdb_id"));
        final String title = CxJ.s(d, "title");
        final String poster = CxJ.s(d, "poster");
        final JSONArray seasons = d.optJSONArray("seasons");
        final ArrayList<JSONObject> chosen = new ArrayList<JSONObject>();
        for (int i = 0; seasons != null && i < seasons.length() && i < picked.length; i++) {
            if (picked[i] && seasons.optJSONObject(i) != null) chosen.add(seasons.optJSONObject(i));
        }
        ArrayList<String> labels = new ArrayList<String>();
        labels.add(existingDup != null ? "تجهيز المسلسل الموجود" : "إنشاء سجل المسلسل");
        for (int i = 0; i < chosen.size(); i++) {
            JSONObject so = chosen.get(i);
            labels.add("الموسم " + so.optInt("season") + (so.optInt("episode_count") > 0 ? " • " + so.optInt("episode_count") + " حلقة" : ""));
        }
        showProgress(title, poster, labels.toArray(new String[0]));
        undoIds.clear();
        launch(new Job() {
            @Override
            public void run() throws Exception {
                step(0, RUNNING, "");
                String seriesKey;
                String headerId;
                String shownTitle = title;
                String shownPoster = poster;
                if (existingDup != null) {
                    seriesKey = keyOfHeader(existingDup, tmdbId);
                    headerId = CxJ.s(existingDup, "id");
                    step(0, OK, "المسلسل موجود، سنضيف إليه");
                } else {
                    JSONObject saved = createSeries(d, secId, secName, true);
                    JSONObject item = saved.optJSONObject("item");
                    if (item == null) throw new CxApi.Failure("لم يؤكد الخادم إنشاء المسلسل. أعد المحاولة.", "NO_ITEM", null, 0);
                    if (saved.optString("undo_id", "").length() > 0) undoIds.add(saved.optString("undo_id"));
                    seriesKey = CgData.seriesKey(item);
                    headerId = CxJ.s(item, "id");
                    shownTitle = CxJ.s(item, "title");
                    if (CxJ.s(item, "poster").length() > 0) shownPoster = CxJ.s(item, "poster");
                    step(0, OK, "تم إنشاء المسلسل");
                }

                int totalEps = 0;
                int doneSeasons = 0;
                ArrayList<String> failed = new ArrayList<String>();
                for (int k = 0; k < chosen.size(); k++) {
                    JSONObject so = chosen.get(k);
                    int sn = so.optInt("season");
                    int idx = k + 1;
                    try {
                        step(idx, RUNNING, "نجهّز الحلقات...");
                        JSONArray eps = new JSONArray();
                        String seasonPoster = so.optString("poster", "");
                        JSONObject pv = null;
                        try {
                            JSONObject pb = new JSONObject();
                            pb.put("series_id", seriesKey);
                            pb.put("season", sn);
                            pb.put("vidbolt", true);
                            pb.put("tmdb", tmdbId);
                            pv = CxApi.post("/admin/season/preview", pb);
                        } catch (CxApi.Failure pf) {
                            if (pf.http == 401 || pf.is("UNAUTHORIZED")) throw pf;
                            pv = null;
                        }
                        JSONArray rows = pv == null ? null : pv.optJSONArray("rows");
                        if (pv != null && CxJ.s(pv, "season_poster").length() > 0) seasonPoster = CxJ.s(pv, "season_poster");
                        if (rows != null && rows.length() > 0) {
                            for (int i = 0; i < rows.length(); i++) {
                                JSONObject r = rows.optJSONObject(i);
                                if (r == null || r.optInt("ep") < 1) continue;
                                JSONObject o = new JSONObject();
                                CxJ.put(o, "ep", r.optInt("ep"));
                                CxJ.put(o, "title", CxJ.s(r, "title"));
                                String v = CxJ.s(r, "video");
                                if (v.length() == 0) v = CgVidbolt.tv(tmdbId, sn, r.optInt("ep"));
                                CxJ.put(o, "video", v);
                                CxJ.put(o, "subtitle", CxJ.s(r, "subtitle"));
                                CxJ.put(o, "thumb", CxJ.s(r, "thumb"));
                                CxJ.put(o, "overview", CxJ.s(r, "overview"));
                                CxJ.put(o, "duration", CxJ.s(r, "duration"));
                                CxJ.put(o, "year", CxJ.s(r, "year"));
                                eps.put(o);
                            }
                        } else {
                            int count = so.optInt("episode_count");
                            if (count <= 0) throw new CxApi.Failure("لم أجد حلقات لهذا الموسم في TMDB.", "NO_EPISODES", null, 0);
                            for (int e = 1; e <= count; e++) {
                                JSONObject o = new JSONObject();
                                CxJ.put(o, "ep", e);
                                CxJ.put(o, "title", "الحلقة " + e);
                                CxJ.put(o, "video", CgVidbolt.tv(tmdbId, sn, e));
                                eps.put(o);
                            }
                        }
                        step(idx, RUNNING, "نحفظ " + eps.length() + " حلقة...");
                        JSONObject cb = new JSONObject();
                        cb.put("series_id", seriesKey);
                        cb.put("season", sn);
                        cb.put("episodes", eps);
                        cb.put("update_existing", false);
                        cb.put("tmdb", tmdbId);
                        if (seasonPoster.length() > 0) cb.put("season_poster", seasonPoster);
                        JSONObject cr = CxApi.post("/admin/season/commit", cb);
                        if (cr.optString("undo_id", "").length() > 0) undoIds.add(cr.optString("undo_id"));
                        int added = cr.optInt("added") + cr.optInt("updated");
                        totalEps += added;
                        String note = cr.optInt("added") + " جديدة" + (cr.optInt("skipped") > 0 ? " • " + cr.optInt("skipped") + " موجودة مسبقاً" : "");

                        int state = OK;
                        if (subs) {
                            int linked = 0;
                            int offset = 0;
                            try {
                                for (int guard = 0; guard < 40; guard++) {
                                    step(idx, RUNNING, note + " • نجلب الترجمة (" + linked + ")...");
                                    JSONObject sb = new JSONObject();
                                    sb.put("series_id", seriesKey);
                                    sb.put("season", sn);
                                    sb.put("offset", offset);
                                    sb.put("batch", 3);
                                    JSONObject sr = CxApi.post("/admin/subs/season", sb);
                                    if (sr.optString("undo_id", "").length() > 0) undoIds.add(sr.optString("undo_id"));
                                    linked += sr.optInt("linked");
                                    offset = sr.optInt("next_offset");
                                    if (sr.optBoolean("done") || sr.optInt("total") == 0) break;
                                }
                                note += " • ترجمة لـ " + linked;
                            } catch (CxApi.Failure sf) {
                                state = WARN;
                                note += " • الترجمة: " + sf.getMessage();
                            }
                        }
                        step(idx, state, note);
                        doneSeasons++;
                    } catch (CxApi.Failure f) {
                        step(idx, FAIL, f.getMessage());
                        failed.add("م" + sn);
                        if (f.http == 401 || f.is("UNAUTHORIZED")) throw f;
                    }
                }
                if (!failed.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < failed.size(); i++) sb.append(i > 0 ? "، " : "").append(failed.get(i));
                    throw new CxApi.Failure("تعذرت بعض المواسم (" + sb + "). أعد المحاولة: ما اكتمل سيتم تخطيه ولن يتكرر.", "SEASON_FAILED", null, 0);
                }
                final String note = chosen.isEmpty() ? "أُنشئ المسلسل فقط، أضف المواسم متى شئت"
                        : doneSeasons + " موسم • " + totalEps + " حلقة جديدة";
                finishOk(headerId, shownTitle, shownPoster, "مسلسل", note);
            }
        }, new Runnable() {
            @Override
            public void run() {
                runSeries(d, existingDup, picked, subs, secId, secName);
            }
        }, "");
    }

    /** Creates the series header; a race with an existing one resolves to that header instead of failing. */
    private JSONObject createSeries(JSONObject d, String secId, String secName, boolean withSlug) throws Exception {
        JSONObject b = new JSONObject();
        b.put("tmdb_id", String.valueOf(d.optInt("tmdb_id")));
        String[][] map = {{"title", "title"}, {"slug", "suggested_slug"}, {"poster", "poster"}, {"backdrop", "backdrop"},
                {"description", "description"}, {"category", "category"}, {"year", "year"}, {"original_title", "original_title"},
                {"imdb_id", "imdb_id"}};
        for (int i = 0; i < map.length; i++) {
            if ("slug".equals(map[i][0]) && !withSlug) continue;
            String v = CxJ.s(d, map[i][1]);
            if (v.length() > 0) b.put(map[i][0], v);
        }
        if (secId.length() > 0) {
            b.put("section_id", secId);
            b.put("section_name", secName);
        }
        try {
            return CxApi.post("/admin/series/create", b);
        } catch (CxApi.Failure f) {
            if (f.is("DUPLICATE_SLUG") && withSlug) return createSeries(d, secId, secName, false);
            throw f;
        }
    }

    private static String sourceName(String s) {
        if ("subdl".equals(s)) return "SubDL";
        if ("subsource".equals(s)) return "SubSource";
        if ("opensubtitles".equals(s)) return "OpenSubtitles";
        return s.length() == 0 ? "ترجمة" : s;
    }

    // ================================================================ success

    private void finishOk(final String id, final String title, final String poster, final String kind, final String note) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!alive()) return;
                working = false;
                refreshData();
                body.removeAllViews();
                LinearLayout p = scrollBody();
                p.setGravity(Gravity.CENTER_HORIZONTAL);
                TextView ok = CgUi.text(this_(), "✔", 34, CgCfg.GOOD, true);
                ok.setGravity(Gravity.CENTER);
                ok.setBackground(CgUi.bg(this_(), CgUi.alpha(CgCfg.GOOD, 0x22), 40, CgCfg.GOOD));
                p.addView(ok, CgUi.lp(this_(), CgUi.dp(this_(), 80), CgUi.dp(this_(), 80), 0, 18, 0, 10));
                TextView h = CgUi.text(this_(), "تمت الإضافة 🎉", 22, CgCfg.TEXT, true);
                h.setGravity(Gravity.CENTER);
                p.addView(h);
                ImageView iv = new ImageView(this_());
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                final int rad = CgUi.dp(this_(), 14);
                iv.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), rad);
                    }
                });
                iv.setClipToOutline(true);
                CgImg.load(iv, poster, 360);
                LinearLayout.LayoutParams il = CgUi.lp(this_(), CgUi.dp(this_(), 120), CgUi.dp(this_(), 180), 0, 16, 0, 10);
                p.addView(iv, il);
                TextView t = CgUi.text(this_(), title, 18, CgCfg.TEXT, true);
                t.setGravity(Gravity.CENTER);
                p.addView(t);
                TextView sub = CgUi.muted(this_(), kind + " • " + note);
                sub.setGravity(Gravity.CENTER);
                sub.setPadding(0, CgUi.dp(this_(), 4), 0, CgUi.dp(this_(), 16));
                p.addView(sub);

                TextView open = CgUi.button(this_(), "فتح " + kind, 0);
                open.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        CxNav.openItem(QuickAddActivity.this, id);
                    }
                });
                p.addView(open, CgUi.lp(this_(), CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
                TextView more = CgUi.button(this_(), "➕  إضافة عمل آخر", 1);
                more.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        undoIds.clear();
                        buildSearchScreen();
                    }
                });
                p.addView(more, CgUi.lp(this_(), CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
                if (!undoIds.isEmpty()) {
                    TextView undo = CgUi.button(this_(), "↩  تراجع عن هذه الإضافة", 2);
                    undo.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            CgUi.confirm(QuickAddActivity.this, "التراجع", "سيُحذف ما أُضيف الآن (" + undoIds.size() + " عملية) ويعود كل شيء كما كان.", "تراجع", true, new Runnable() {
                                @Override
                                public void run() {
                                    undoAll();
                                }
                            });
                        }
                    });
                    p.addView(undo, CgUi.lp(this_(), CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 0));
                }
                p.setAlpha(0f);
                p.animate().alpha(1f).setDuration(260).start();
            }
        });
    }

    private QuickAddActivity this_() {
        return this;
    }

    private void refreshData() {
        try {
            CgData.load(this, new CgHttp.Done<CgData>() {
                @Override
                public void ok(CgData d) { }

                @Override
                public void fail(String message) { }
            });
        } catch (Exception ignored) { }
    }

    private void undoAll() {
        final ArrayList<String> ids = new ArrayList<String>(undoIds);
        showLoading("جارٍ التراجع...");
        pool.execute(new Runnable() {
            @Override
            public void run() {
                int bad = 0;
                for (int i = ids.size() - 1; i >= 0; i--) {
                    try {
                        JSONObject b = new JSONObject();
                        b.put("id", ids.get(i));
                        CxApi.post("/admin/undo", b);
                    } catch (Exception e) {
                        bad++;
                    }
                }
                final int fb = bad;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!alive()) return;
                        hideLoading();
                        undoIds.clear();
                        refreshData();
                        CgUi.toast(QuickAddActivity.this, fb == 0 ? "تم التراجع بنجاح." : "تم التراجع مع تعذر " + fb + " خطوة.");
                        buildSearchScreen();
                    }
                });
            }
        });
    }
}
