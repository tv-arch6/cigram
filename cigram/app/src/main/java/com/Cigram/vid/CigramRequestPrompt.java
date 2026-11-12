package com.Cigram.vid;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * v10.65: «ما لكيت؟ اطلبه» inside the search screen.
 *
 *   - search finds nothing      -> a card under «لا توجد نتائج» with a red «اطلبه» button
 *   - only «ربما تقصد» results  -> a small «مو هذا اللي تدور عليه؟ اطلبه» row after them
 *
 * The request goes through CigramRequests.add, exactly like «طلب فيلم / مسلسل» in «تواصل معنا»:
 * saved in «طلباتي», sent to cigram-user-api, and the viewer is notified when it is added.
 * Nothing else in the search screen changes; any error here only hides the prompt.
 *
 * Called from SearchXmlActivity (CigramSearch.run):
 *   hide(emptyBox)                                   at the start of every render
 *   show(activity, emptyBox, emptyText, query, type) when found == 0
 *   row(activity, query, type)                       appended after the «ربما تقصد» results
 */
public final class CigramRequestPrompt {

    private static final String TAG = "cigram_request_prompt";

    private CigramRequestPrompt() { }

    // ------------------------------------------------------------------ entry points

    public static void hide(View emptyBox) {
        if (!(emptyBox instanceof ViewGroup)) return;
        View old = emptyBox.findViewWithTag(TAG);
        if (old != null && old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
    }

    public static void show(final Activity a, View emptyBox, View emptyText, final String query, final String type) {
        if (a == null || !(emptyBox instanceof ViewGroup) || query == null || query.trim().length() < 2) return;
        final ViewGroup box = (ViewGroup) emptyBox;
        hide(box);
        final LinearLayout card = new LinearLayout(a);
        card.setTag(TAG);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        card.setBackground(CigramUI.round(0x14FFFFFF, 0x22FFFFFF, 22, a));
        int p = CigramUI.dp(a, 18);
        card.setPadding(p, p, p, CigramUI.dp(a, 16));
        fillCard(a, card, query.trim(), type);

        int index = box.getChildCount();
        if (emptyText != null && emptyText.getParent() == box) index = box.indexOfChild(emptyText) + 1;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        int m = CigramUI.dp(a, 20);
        lp.setMargins(m, CigramUI.dp(a, 18), m, CigramUI.dp(a, 8));
        box.addView(card, Math.min(index, box.getChildCount()), lp);
        card.setAlpha(0f);
        card.animate().alpha(1f).setDuration(200L).start();
        tvScan(a);
    }

    /** Compact row shown after «ربما تقصد» results. */
    public static View row(final Activity a, final String query, final String type) {
        final LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setBackground(CigramUI.round(0x14FFFFFF, 0x22FFFFFF, 18, a));
        int p = CigramUI.dp(a, 14);
        row.setPadding(p, CigramUI.dp(a, 10), p, CigramUI.dp(a, 10));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        int m = CigramUI.dp(a, 8);
        rl.setMargins(m, CigramUI.dp(a, 14), m, CigramUI.dp(a, 6));
        row.setLayoutParams(rl);
        fillRow(a, row, query == null ? "" : query.trim(), type);
        tvScan(a);
        return row;
    }

    // ------------------------------------------------------------------ views

    private static void fillCard(final Activity a, final LinearLayout card, final String query, final String type) {
        card.removeAllViews();
        final JSONObject mine = existing(a, query, type);
        TextView head = CigramUI.label(a, mine == null ? "ما لكيت «" + query + "»؟" : "طلبت «" + mine.optString("title", query) + "»",
                16f, CigramUI.TEXT, true);
        head.setGravity(Gravity.CENTER);
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView sub = CigramUI.label(a, mine == null
                        ? "اطلبه وراح نخبرك أول ما ينضاف لسيكرام."
                        : CigramRequests.statusLabel(mine.optString("status")) + " — تكدر تتابعه من «طلباتي».",
                13.5f, CigramUI.MUTED, false);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = CigramUI.dp(a, 4);
        card.addView(sub, sl);

        TextView action = mine == null
                ? pill(a, "اطلبه", CigramUI.RED, CigramUI.TEXT)
                : pill(a, "طلباتي", 0x00000000, CigramUI.TEXT);
        if (mine != null) action.setBackground(CigramUI.round(0x00000000, 0x55FFFFFF, 24, a));
        action.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (existing(a, query, type) != null) openMine(a);
                else ask(a, query, type, new Runnable() {
                    @Override public void run() { fillCard(a, card, query, type); tvScan(a); }
                });
            }
        });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, CigramUI.dp(a, 46));
        al.topMargin = CigramUI.dp(a, 14);
        action.setMinWidth(CigramUI.dp(a, 150));
        card.addView(action, al);
    }

    private static void fillRow(final Activity a, final LinearLayout row, final String query, final String type) {
        row.removeAllViews();
        final JSONObject mine = existing(a, query, type);
        TextView t = CigramUI.label(a, mine == null ? "مو هذا اللي تدور عليه؟" : "طلبته ✓ تابعه من «طلباتي»", 14f, 0xDDFFFFFF, false);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView b = mine == null ? pill(a, "اطلبه", CigramUI.RED, CigramUI.TEXT) : pill(a, "طلباتي", 0x00000000, CigramUI.TEXT);
        if (mine != null) b.setBackground(CigramUI.round(0x00000000, 0x55FFFFFF, 20, a));
        b.setTextSize(14f);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (existing(a, query, type) != null) openMine(a);
                else ask(a, query, type, new Runnable() {
                    @Override public void run() { fillRow(a, row, query, type); tvScan(a); }
                });
            }
        });
        row.addView(b, new LinearLayout.LayoutParams(-2, CigramUI.dp(a, 40)));
    }

    private static TextView pill(Activity a, String text, int fill, int color) {
        TextView b = new TextView(a);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(color);
        b.setTextSize(15f);
        b.setTypeface(CigramUI.font(a), Typeface.BOLD);
        b.setBackground(CigramUI.round(fill, 24, a));
        b.setPadding(CigramUI.dp(a, 22), 0, CigramUI.dp(a, 22), 0);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    // ------------------------------------------------------------------ request sheet

    private static String kindOf(String type) {
        return "series".equals(type) ? CigramRequests.SERIES : "movie".equals(type) ? CigramRequests.MOVIE : "";
    }

    private static void ask(final Activity a, String query, String type, final Runnable after) {
        final String fixed = kindOf(type);
        final String[] kind = {fixed.length() > 0 ? fixed : CigramRequests.MOVIE};

        LinearLayout form = new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        final EditText input = new EditText(a);
        CigramUI.styleInput(input, "الاسم (عربي أو إنجليزي)");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setText(query);
        input.setSelection(input.getText().length());
        form.addView(input, new LinearLayout.LayoutParams(-1, -2));

        if (fixed.length() == 0) {
            final LinearLayout kinds = new LinearLayout(a);
            kinds.setOrientation(LinearLayout.HORIZONTAL);
            kinds.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            final TextView movie = chip(a, "فيلم");
            final TextView series = chip(a, "مسلسل");
            final Runnable paint = new Runnable() {
                @Override public void run() {
                    paintChip(a, movie, CigramRequests.MOVIE.equals(kind[0]));
                    paintChip(a, series, CigramRequests.SERIES.equals(kind[0]));
                }
            };
            movie.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { kind[0] = CigramRequests.MOVIE; paint.run(); }
            });
            series.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { kind[0] = CigramRequests.SERIES; paint.run(); }
            });
            paint.run();
            kinds.addView(movie, new LinearLayout.LayoutParams(0, CigramUI.dp(a, 44), 1f));
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, CigramUI.dp(a, 44), 1f);
            sl.setMarginStart(CigramUI.dp(a, 10));
            kinds.addView(series, sl);
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(-1, -2);
            kl.topMargin = CigramUI.dp(a, 12);
            form.addView(kinds, kl);
        }

        CigramUI.sheet(a)
                .title(fixed.length() == 0 ? "طلب فيلم أو مسلسل" : "طلب " + CigramRequests.kindLabel(fixed))
                .message("تأكد من الاسم، وراح نخبرك أول ما ينضاف.")
                .view(form)
                .primary("إرسال الطلب", CigramUI.RED, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) {
                        String name = input.getText().toString().trim();
                        if (name.length() < 2) { input.setError("اكتب الاسم"); return true; }
                        try {
                            CigramRequests.add(a, kind[0], name, "");
                            CigramAnalytics.track(a, "request_from_search", "kind", kind[0]);
                        } catch (Throwable ignored) { }
                        Toast.makeText(a, "تم إرسال طلبك ✓", Toast.LENGTH_SHORT).show();
                        if (after != null) after.run();
                        return false;
                    }
                })
                .secondary("إلغاء", null)
                .show();
    }

    private static TextView chip(Activity a, String text) {
        TextView t = new TextView(a);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(15f);
        t.setTypeface(CigramUI.font(a), Typeface.BOLD);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private static void paintChip(Activity a, TextView t, boolean on) {
        t.setTextColor(on ? CigramUI.TEXT : CigramUI.MUTED);
        t.setBackground(on ? CigramUI.round(0x33E21B14, CigramUI.RED, 14, a) : CigramUI.round(CigramUI.BG_SOFT, CigramUI.STROKE, 14, a));
    }

    // ------------------------------------------------------------------ helpers

    /** The viewer's own open request with this name (and kind, when the tab fixes it), or null. */
    private static JSONObject existing(Activity a, String query, String type) {
        try {
            String want = CigramContent.norm(query);
            if (want.length() < 2) return null;
            String kind = kindOf(type);
            for (JSONObject o : CigramRequests.list(a)) {
                if (o == null || CigramRequests.ADDED.equals(o.optString("status"))) continue;
                if (CigramRequests.CHANNEL.equals(o.optString("kind"))) continue;
                if (kind.length() > 0 && !kind.equals(o.optString("kind"))) continue;
                if (want.equals(CigramContent.norm(o.optString("title")))) return o;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void openMine(Activity a) {
        try {
            a.startActivity(new Intent(a, Class.forName(a.getPackageName() + ".CommunicationActivity")));
        } catch (Throwable ignored) {
            Toast.makeText(a, "افتح «طلباتي» من الحساب ← تواصل معنا", Toast.LENGTH_SHORT).show();
        }
    }

    private static void tvScan(Activity a) {
        try {
            CigramTvFocus.requestScan(a);
        } catch (Throwable ignored) { }
    }
}
