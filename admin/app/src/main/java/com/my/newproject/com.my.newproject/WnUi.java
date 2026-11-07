package com.my.newproject;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * "What's New?" view kit: the cinematic poster card, source rows, validation report and small shared pieces.
 * Dark, translucent "glass" surfaces with a soft accent glow; everything uses the CgCfg palette and the CgUi shapes.
 */
final class WnUi {

    private WnUi() { }

    /** Set by the detail / bulk screens after an import or sync so the discovery list reloads when it comes back. */
    static volatile boolean dirty;

    static final int GLASS = CgUi.alpha(CgCfg.CARD, 0xE0);
    static final int SCRIM = 0xEE080A12;

    // ------------------------------------------------------------------ columns / sizes (phone -> tablet)

    /** 2 columns on phones, 3 from 600dp, 4 from 900dp. */
    static int columns(Activity a) {
        int w = a.getResources().getConfiguration().screenWidthDp;
        return w >= 900 ? 4 : w >= 600 ? 3 : 2;
    }

    static int cardWidthPx(Activity a, int cols) {
        int px = a.getResources().getDisplayMetrics().widthPixels;
        int pad = CgUi.dp(a, 14) * 2;
        int gap = CgUi.dp(a, 10) * (cols - 1);
        return Math.max(CgUi.dp(a, 120), (px - pad - gap) / cols);
    }

    // ------------------------------------------------------------------ small pieces

    static TextView badge(Context c, String text, int color) {
        TextView b = CgUi.badge(c, text, color);
        b.setSingleLine(true);
        return b;
    }

    static View gap(Context c, float w, float h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(CgUi.dp(c, w), CgUi.dp(c, h)));
        return v;
    }

    static LinearLayout glass(Context c) {
        LinearLayout l = CgUi.vbox(c);
        l.setBackground(CgUi.bg(c, GLASS, 18, CgUi.alpha(CgCfg.ACCENT, 0x44)));
        int p = CgUi.dp(c, 14);
        l.setPadding(p, p, p, p);
        CgUi.elevate(l, 3);
        return l;
    }

    /** Fades a view in, staggered by position, so a freshly loaded list settles instead of popping. */
    static void reveal(View v, int index) {
        v.setAlpha(0f);
        v.setTranslationY(CgUi.dp(v.getContext(), 10));
        v.animate().alpha(1f).translationY(0f).setDuration(260).setStartDelay(Math.min(index, 12) * 28L).start();
    }

    static String joinFirst(JSONArray a, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; a != null && i < a.length() && i < max; i++) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(a.optString(i));
        }
        return sb.toString();
    }

    static TextView clamp(TextView t, int lines) {
        t.setMaxLines(lines);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    // ------------------------------------------------------------------ the poster card

    static View posterCard(final Activity a, JSONObject c, int widthPx, boolean selected, boolean selectMode, final Runnable open, final Runnable toggle) {
        boolean series = "series".equals(c.optString("type"));
        LinearLayout card = CgUi.vbox(a);
        card.setBackground(CgUi.bg(a, GLASS, 16, selected ? CgCfg.ACCENT : CgUi.alpha(CgCfg.ACCENT, 0x33)));
        CgUi.elevate(card, selected ? 9 : 3);

        FrameLayout box = new FrameLayout(a);
        box.setBackground(CgUi.bg(a, CgCfg.CARD2, 16, 0));
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(iv, new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.MATCH));
        CgImg.load(iv, c.optString("poster"), widthPx);

        View scrim = new View(a);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00080A12, SCRIM}));
        box.addView(scrim, new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.dp(a, 96), Gravity.BOTTOM));

        LinearLayout over = CgUi.vbox(a);
        over.setPadding(CgUi.dp(a, 10), 0, CgUi.dp(a, 10), CgUi.dp(a, 8));
        TextView title = clamp(CgUi.text(a, c.optString("title"), 14, CgCfg.TEXT, true), 2);
        over.addView(title);
        String year = c.optString("year");
        String rating = WnLogic.ratingText(c.optDouble("rating", 0));
        TextView meta = CgUi.text(a, (year.length() > 0 ? year + "  ·  " : "") + "★ " + rating, 12, CgCfg.WARN, true);
        over.addView(meta);
        box.addView(over, new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP, Gravity.BOTTOM));

        LinearLayout top = CgUi.hbox(a);
        top.setPadding(CgUi.dp(a, 8), CgUi.dp(a, 8), CgUi.dp(a, 8), 0);
        top.addView(badge(a, WnLogic.typeLabel(c.optString("type")), series ? CgCfg.ACCENT_2 : CgCfg.ACCENT), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        boolean imported = c.optBoolean("imported", false);
        if (imported) {
            TextView b = badge(a, "في المكتبة", CgCfg.GOOD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            lp.setMarginStart(CgUi.dp(a, 5));
            top.addView(b, lp);
        } else if (!c.optBoolean("released", true)) {
            TextView b = badge(a, "قريباً", CgCfg.GRAY);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            lp.setMarginStart(CgUi.dp(a, 5));
            top.addView(b, lp);
        }
        box.addView(top, new FrameLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP, Gravity.TOP | Gravity.START));

        if (selectMode || selected) {
            TextView dot = CgUi.text(a, selected ? "✓" : "", 15, CgCfg.ON_ACCENT, true);
            dot.setGravity(Gravity.CENTER);
            dot.setBackground(selected ? CgUi.gradient(a, CgCfg.ACCENT_G1, CgCfg.ACCENT_G2, 20) : CgUi.bg(a, CgUi.alpha(CgCfg.PAGE, 0xAA), 20, CgCfg.TEXT));
            FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(CgUi.dp(a, 26), CgUi.dp(a, 26), Gravity.TOP | Gravity.END);
            dl.setMargins(0, CgUi.dp(a, 8), CgUi.dp(a, 8), 0);
            box.addView(dot, dl);
        }
        card.addView(box, new LinearLayout.LayoutParams(CgUi.MATCH, (int) (widthPx * 1.42f)));

        LinearLayout info = CgUi.vbox(a);
        info.setPadding(CgUi.dp(a, 10), CgUi.dp(a, 8), CgUi.dp(a, 10), CgUi.dp(a, 10));
        String original = c.optString("original_title");
        if (original.length() > 0 && !original.equals(c.optString("title"))) {
            TextView o = clamp(CgUi.text(a, original, 11.5f, CgCfg.MUTED, false), 1);
            CgUi.ltr(o);
            info.addView(o);
        }
        String genres = joinFirst(c.optJSONArray("genres"), 2);
        if (genres.length() > 0) info.addView(clamp(CgUi.text(a, genres, 11.5f, CgCfg.ACCENT, false), 1));
        if (series) {
            StringBuilder s = new StringBuilder();
            int seasons = c.optInt("seasons", 0);
            int eps = c.optInt("episodes", 0);
            if (seasons > 0) s.append(seasons).append(" مواسم");
            if (eps > 0) s.append(s.length() > 0 ? " · " : "").append(eps).append(" حلقة");
            JSONObject le = c.optJSONObject("latest_episode");
            if (le != null && le.optInt("episode") > 0) s.append(s.length() > 0 ? "\n" : "").append("آخر حلقة ").append(WnLogic.epCode(le.optInt("season"), le.optInt("episode")));
            if (c.optBoolean("new_season", false)) s.append(s.length() > 0 ? " · " : "").append("موسم جديد");
            if (c.optInt("episodes_pending", 0) > 0) s.append(s.length() > 0 ? "\n" : "").append(c.optInt("episodes_pending")).append(" حلقة جديدة غير مضافة");
            if (s.length() > 0) info.addView(clamp(CgUi.text(a, s.toString(), 11.5f, CgCfg.CYAN, false), 3));
        }
        String ov = c.optString("overview");
        if (ov.length() > 0) {
            TextView t = clamp(CgUi.text(a, ov, 11.5f, CgCfg.MUTED, false), 2);
            t.setPadding(0, CgUi.dp(a, 3), 0, 0);
            info.addView(t);
        }
        String rd = c.optString("release_date");
        if (rd.length() > 0) {
            TextView d = CgUi.text(a, rd, 11, CgCfg.FAINT, false);
            CgUi.ltr(d);
            d.setGravity(Gravity.START);
            info.addView(d);
        }
        LinearLayout chips = CgUi.vbox(a);   // stacked: two status badges never fit side by side on a phone-width card
        chips.setPadding(0, CgUi.dp(a, 6), 0, 0);
        JSONObject v = c.optJSONObject("video");
        JSONObject sb = c.optJSONObject("subtitle");
        String vs = v == null ? "unknown" : v.optString("state", "unknown");
        String ss = sb == null ? "unknown" : sb.optString("state", "unknown");
        chips.addView(badge(a, WnLogic.cardVideoLabel(vs), WnLogic.cardVideoColor(vs)), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        sl.topMargin = CgUi.dp(a, 4);
        chips.addView(badge(a, WnLogic.cardSubLabel(ss), WnLogic.cardSubColor(ss)), sl);
        info.addView(chips);
        TextView id = CgUi.text(a, "TMDB " + c.optLong("tmdb_id"), 10.5f, CgCfg.FAINT, false);
        CgUi.ltr(id);
        id.setGravity(Gravity.START);
        id.setPadding(0, CgUi.dp(a, 4), 0, 0);
        info.addView(id);
        card.addView(info, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v2) { if (open != null) open.run(); }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v2) { if (toggle != null) toggle.run(); return true; }
        });
        CgUi.pressScale(card);
        return card;
    }

    // ------------------------------------------------------------------ video source row

    static View sourceRow(final Activity a, final JSONObject s, boolean selected, final Runnable pick) {
        String verdict = s.optString("verdict");
        int color = WnLogic.verdictColor(verdict);
        LinearLayout row = CgUi.vbox(a);
        row.setBackground(CgUi.bg(a, selected ? CgUi.alpha(CgCfg.ACCENT_D, 0x2A) : CgCfg.CARD2, 14, selected ? CgCfg.ACCENT : CgUi.alpha(color, 0x66)));
        row.setPadding(CgUi.dp(a, 12), CgUi.dp(a, 10), CgUi.dp(a, 12), CgUi.dp(a, 10));
        LinearLayout head = CgUi.hbox(a);
        TextView name = CgUi.text(a, s.optString("name", s.optString("provider")) + (selected ? "  ✓" : ""), 14, CgCfg.TEXT, true);
        head.addView(name, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        head.addView(badge(a, WnLogic.verdictLabel(verdict), color), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        row.addView(head);
        LinearLayout meta = CgUi.hbox(a);
        meta.addView(CgUi.text(a, "المستوى: " + WnLogic.levelLabel(s.optString("level")), 12, CgCfg.MUTED, false), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        int ms = s.optInt("ms", 0);
        int http = s.optInt("http", 0);
        if (http > 0 || ms > 0) {
            TextView t = CgUi.text(a, (http > 0 ? "HTTP " + http : "") + (ms > 0 ? "  ·  " + ms + "ms" : ""), 11.5f, CgCfg.FAINT, false);
            CgUi.ltr(t);
            meta.addView(t, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        }
        row.addView(meta);
        String url = s.optString("url");
        if (url.length() > 0) {
            TextView u = CgUi.text(a, url, 11.5f, CgCfg.ACCENT, false);
            CgUi.ltr(u);
            u.setGravity(Gravity.START);
            u.setSingleLine(true);
            u.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            u.setPadding(0, CgUi.dp(a, 4), 0, 0);
            row.addView(u);
        }
        final LinearLayout checks = CgUi.vbox(a);
        checks.setVisibility(View.GONE);
        JSONArray cs = s.optJSONArray("checks");
        for (int i = 0; cs != null && i < cs.length(); i++) {
            JSONObject c = cs.optJSONObject(i);
            if (c == null) continue;
            String st = c.isNull("pass") ? "skip" : c.optBoolean("pass") ? "pass" : "fail";
            TextView line = CgUi.text(a, WnLogic.checkMark(st) + "  " + c.optString("label") + (c.optString("detail").length() > 0 ? " — " + c.optString("detail") : ""), 12, WnLogic.checkColor(st), false);
            line.setPadding(0, CgUi.dp(a, 2), 0, 0);
            checks.addView(line);
        }
        row.addView(checks);
        if (s.optBoolean("needs_ack", false) && s.optBoolean("importable", false)) {
            TextView warn = CgUi.text(a, "⚠ لا يمكن إثبات التشغيل من الخادم؛ الاستيراد يحتاج إقرارك.", 11.5f, CgCfg.WARN, false);
            warn.setPadding(0, CgUi.dp(a, 5), 0, 0);
            row.addView(warn);
        }
        TextView more = CgUi.text(a, "تفاصيل الفحص ▾", 11.5f, CgCfg.ACCENT, true);
        more.setPadding(0, CgUi.dp(a, 6), 0, 0);
        final TextView moreRef = more;
        more.setClickable(true);
        more.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean show = checks.getVisibility() != View.VISIBLE;
                checks.setVisibility(show ? View.VISIBLE : View.GONE);
                moreRef.setText(show ? "إخفاء التفاصيل ▴" : "تفاصيل الفحص ▾");
            }
        });
        row.addView(more);
        if (pick != null && s.optBoolean("importable", false)) {
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pick.run(); }
            });
        }
        row.setLayoutParams(CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        return row;
    }

    // ------------------------------------------------------------------ validation report

    static View reportView(Activity a, JSONObject report) {
        LinearLayout box = glass(a);
        if (report == null) {
            box.addView(CgUi.muted(a, "لم يُجرَ التحقق بعد."));
            return box;
        }
        String state = report.optString("state");
        int color = WnLogic.stateColor(state);
        LinearLayout head = CgUi.hbox(a);
        head.addView(CgUi.text(a, "نتيجة التحقق", 15, CgCfg.TEXT, true), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        head.addView(badge(a, WnLogic.stateLabel(state), color), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        box.addView(head);
        JSONArray checks = report.optJSONArray("checks");
        for (int i = 0; checks != null && i < checks.length(); i++) addCheck(a, box, checks.optJSONObject(i), "");
        JSONArray items = report.optJSONArray("items");
        int shown = 0;
        int hidden = 0;
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            boolean bad = !"ready".equals(it.optString("state")) || it.optBoolean("skipped", false);
            if (items.length() > 6 && !bad) { hidden++; continue; }
            if (shown >= 14) { hidden++; continue; }
            shown++;
            TextView t = CgUi.text(a, it.optString("label") + "  ·  " + WnLogic.stateLabel(it.optString("state")), 12.5f, WnLogic.stateColor(it.optString("state")), true);
            t.setPadding(0, CgUi.dp(a, 8), 0, 0);
            box.addView(t);
            JSONArray ic = it.optJSONArray("checks");
            for (int j = 0; ic != null && j < ic.length(); j++) addCheck(a, box, ic.optJSONObject(j), "   ");
        }
        if (hidden > 0) {
            TextView t = CgUi.muted(a, "… و" + hidden + " عنصراً آخر سليماً.");
            t.setPadding(0, CgUi.dp(a, 6), 0, 0);
            box.addView(t);
        }
        JSONArray w = report.optJSONArray("warnings");
        for (int i = 0; w != null && i < w.length() && i < 6; i++) {
            TextView t = CgUi.text(a, "! " + w.optString(i), 12, CgCfg.WARN, false);
            t.setPadding(0, CgUi.dp(a, 4), 0, 0);
            box.addView(t);
        }
        box.setLayoutParams(CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        return box;
    }

    private static void addCheck(Activity a, LinearLayout box, JSONObject c, String indent) {
        if (c == null) return;
        String st = c.optString("status", "skip");
        String text = indent + WnLogic.checkMark(st) + "  " + c.optString("label") + (c.optString("detail").length() > 0 ? " — " + c.optString("detail") : "");
        TextView t = CgUi.text(a, text, 12.5f, WnLogic.checkColor(st), false);
        t.setPadding(0, CgUi.dp(a, 3), 0, 0);
        box.addView(t);
    }

    // ------------------------------------------------------------------ section header

    static View sectionHeader(Activity a, String title, String sub) {
        LinearLayout l = CgUi.vbox(a);
        l.addView(CgUi.sectionTitle(a, title));
        if (sub != null && sub.length() > 0) {
            TextView s = CgUi.muted(a, sub);
            s.setPadding(CgUi.dp(a, 4), 0, CgUi.dp(a, 4), CgUi.dp(a, 6));
            l.addView(s);
        }
        return l;
    }

    /** Horizontal real progress bar (determinate). */
    static android.widget.ProgressBar progress(Context c, int max, int value) {
        android.widget.ProgressBar p = new android.widget.ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(Math.max(1, max));
        p.setProgress(Math.max(0, Math.min(value, Math.max(1, max))));
        try {
            p.getProgressDrawable().setColorFilter(CgCfg.ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
        } catch (Exception ignored) { }
        return p;
    }
}
