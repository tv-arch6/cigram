package com.my.newproject;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
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

import java.util.ArrayList;

/** Pieces shared by the new add screens: numbered section headers, the poster + info hero, section chips. */
final class NxParts {

    private NxParts() {}

    /** "① اختر الفيلم" — a numbered step header; done = green check instead of the number. */
    static View step(Context c, int n, String title, String sub, boolean done, boolean enabled) {
        LinearLayout row = CgUi.hbox(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View num;
        if (done) {
            num = NxUi.iconTile(c, NxIcons.icon(CgIcons.CHECK, 0xFF06281D), CgUi.bg(c, NxUi.UP, 14, 0), 6);
        } else {
            TextView tn = CgUi.text(c, String.valueOf(n), 14, enabled ? 0xFFFFFFFF : NxUi.DIM, true);
            tn.setGravity(Gravity.CENTER);
            tn.setBackground(enabled ? NxUi.gradient(c, 0xFF4F6BFF, 0xFF6C8CFF, 14) : CgUi.bg(c, NxUi.SURF_HI, 14, NxUi.LINE));
            num = tn;
        }
        row.addView(num, new LinearLayout.LayoutParams(CgUi.dp(c, 28), CgUi.dp(c, 28)));
        LinearLayout col = CgUi.vbox(c);
        col.addView(CgUi.text(c, title, 16.5f, enabled ? NxUi.TEXT : NxUi.DIM, true));
        if (sub != null && sub.length() > 0) col.addView(CgUi.text(c, sub, 11.5f, enabled ? NxUi.SUB : NxUi.DIM, false));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        l.setMargins(CgUi.dp(c, 10), 0, 0, 0);
        row.addView(col, l);
        row.setLayoutParams(NxUi.lp(c, NxUi.MATCH, NxUi.WRAP, 2, 18, 2, 10));
        return row;
    }

    // ====================================================================== hero

    /** Holder of the hero views that change after it is built (video status strip). */
    static final class Hero {
        LinearLayout view;
        LinearLayout statusStrip;
        TextView titleView;
        TextView descView;
    }

    /**
     * Poster + information card like the details page of the app: backdrop, poster beside the title,
     * year / duration / rating pills, genres, description, and a status strip at the bottom.
     */
    static Hero hero(final Activity a, JSONObject st, boolean series, View.OnClickListener change, View.OnClickListener edit) {
        Hero h = new Hero();
        int screen = a.getResources().getDisplayMetrics().widthPixels;
        int w = screen - CgUi.dp(a, 24);
        int backH = Math.round(w * 0.5625f);
        int posterW = CgUi.dp(a, 104);
        int posterH = Math.round(posterW * 1.5f);
        int overlap = CgUi.dp(a, 70);

        LinearLayout card = CgUi.vbox(a);
        card.setBackground(NxUi.surface(a, 22));
        card.setClipToOutline(true);
        h.view = card;

        FrameLayout top = new FrameLayout(a);
        ImageView back = new ImageView(a);
        back.setScaleType(ImageView.ScaleType.CENTER_CROP);
        back.setBackgroundColor(NxUi.SURF_FLAT);
        String bd = CxJ.firstNonEmpty(CxJ.s(st, "backdrop"), CxJ.s(st, "poster"));
        if (bd.startsWith("http")) CgImg.load(back, bd, Math.min(1280, w));
        top.addView(back, new FrameLayout.LayoutParams(w, backH));
        View shade = new View(a);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x330A0A0C, 0x660A0A0C, 0xFF151518}));
        top.addView(shade, new FrameLayout.LayoutParams(w, backH));

        LinearLayout actions = CgUi.hbox(a);
        if (edit != null) actions.addView(roundAction(a, CgIcons.EDIT, "تعديل الاسم أو الوصف", edit));
        if (change != null) {
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(CgUi.dp(a, 38), CgUi.dp(a, 38));
            al.setMargins(CgUi.dp(a, 8), 0, 0, 0);
            actions.addView(roundAction(a, CgIcons.SEARCH, "اختيار عنوان آخر", change), al);
        }
        FrameLayout.LayoutParams acl = new FrameLayout.LayoutParams(NxUi.WRAP, NxUi.WRAP, Gravity.TOP | Gravity.LEFT);
        acl.setMargins(CgUi.dp(a, 10), CgUi.dp(a, 10), 0, 0);
        top.addView(actions, acl);

        LinearLayout info = CgUi.hbox(a);
        info.setGravity(Gravity.BOTTOM);
        info.setPadding(CgUi.dp(a, 14), 0, CgUi.dp(a, 14), 0);
        ImageView poster = new ImageView(a);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(NxUi.flat(a, NxUi.SURF_HI, 14, 0x55FFFFFF));
        poster.setClipToOutline(true);
        poster.setElevation(CgUi.dp(a, 6));
        String p = CxJ.s(st, "poster");
        if (p.startsWith("http")) CgImg.load(poster, p, posterW * 2);
        info.addView(poster, new LinearLayout.LayoutParams(posterW, posterH));
        LinearLayout col = CgUi.vbox(a);
        h.titleView = CgUi.text(a, CxJ.firstNonEmpty(CxJ.s(st, "title"), "(بدون عنوان)"), 19, NxUi.TEXT, true);
        h.titleView.setMaxLines(2);
        h.titleView.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(h.titleView);
        String orig = CxJ.s(st, "original_title");
        if (orig.length() > 0 && !orig.equals(CxJ.s(st, "title"))) {
            TextView o = CgUi.text(a, orig, 12, NxUi.SUB, false);
            CgUi.ltr(o);
            o.setSingleLine(true);
            o.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(o);
        }
        CxUi.Flow pills = new CxUi.Flow(a);
        String kind = series ? "مسلسل" : "فيلم";
        pills.addView(NxUi.pill(a, kind, NxUi.BLUE));
        if (CxJ.s(st, "year").length() > 0) pills.addView(NxUi.pill(a, CxJ.s(st, "year"), NxUi.SUB));
        if (!series && CxJ.s(st, "duration").length() > 0) pills.addView(NxUi.pill(a, CxJ.s(st, "duration"), NxUi.SUB));
        if (series && st.optInt("season_count") > 0) pills.addView(NxUi.pill(a, st.optInt("season_count") + " مواسم", NxUi.SUB));
        if (CxJ.s(st, "rating").length() > 0) pills.addView(NxUi.pill(a, "★ " + CxJ.s(st, "rating"), 0xFFFFD166));
        col.addView(pills, NxUi.lp(a, NxUi.MATCH, NxUi.WRAP, 0, 8, 0, 0));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        cl.setMargins(CgUi.dp(a, 12), 0, 0, CgUi.dp(a, 4));
        info.addView(col, cl);
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(w, NxUi.WRAP, Gravity.BOTTOM);
        top.addView(info, il);
        card.addView(top, new LinearLayout.LayoutParams(w, backH + overlap));

        LinearLayout bottom = CgUi.vbox(a);
        bottom.setPadding(CgUi.dp(a, 14), CgUi.dp(a, 12), CgUi.dp(a, 14), CgUi.dp(a, 14));
        String cat = CxJ.s(st, "category");
        if (cat.length() > 0) {
            TextView g = CgUi.text(a, cat, 12, NxUi.VIOLET, true);
            g.setMaxLines(1);
            g.setEllipsize(TextUtils.TruncateAt.END);
            bottom.addView(g);
        }
        String desc = CxJ.s(st, "description");
        final TextView d = CgUi.text(a, desc.length() > 0 ? desc : "لا يوجد وصف.", 13, desc.length() > 0 ? 0xFFD3DBE8 : NxUi.DIM, false);
        d.setMaxLines(4);
        d.setEllipsize(TextUtils.TruncateAt.END);
        d.setLineSpacing(0, 1.18f);
        d.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.setMaxLines(d.getMaxLines() > 4 ? 4 : 40);
            }
        });
        h.descView = d;
        bottom.addView(d, NxUi.lp(a, NxUi.MATCH, NxUi.WRAP, 0, 6, 0, 0));
        h.statusStrip = CgUi.hbox(a);
        bottom.addView(h.statusStrip, NxUi.lp(a, NxUi.MATCH, NxUi.WRAP, 0, 12, 0, 0));
        card.addView(bottom, new LinearLayout.LayoutParams(NxUi.MATCH, NxUi.WRAP));
        return h;
    }

    private static View roundAction(Context c, int icon, String name, View.OnClickListener l) {
        ImageView iv = NxUi.icon(c, NxIcons.icon(icon, 0xFFFFFFFF), 9);
        NxUi.pressable(iv, CgUi.bg(c, 0x99000000, 19, 0x33FFFFFF), 19);
        NxUi.hint(iv, name);
        iv.setOnClickListener(l);
        iv.setLayoutParams(new LinearLayout.LayoutParams(CgUi.dp(c, 38), CgUi.dp(c, 38)));
        return iv;
    }

    /** Fills the hero status strip: icon + text (+ optional trailing view). */
    static void status(Context c, LinearLayout strip, int icon, int color, String text, View trailing) {
        strip.removeAllViews();
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setBackground(NxUi.tint(c, color, 14));
        strip.setPadding(CgUi.dp(c, 12), CgUi.dp(c, 10), CgUi.dp(c, 10), CgUi.dp(c, 10));
        strip.addView(NxUi.icon(c, NxIcons.icon(icon, color), 0), new LinearLayout.LayoutParams(CgUi.dp(c, 20), CgUi.dp(c, 20)));
        TextView t = CgUi.text(c, text, 12.5f, NxUi.TEXT, true);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        l.setMargins(CgUi.dp(c, 10), 0, CgUi.dp(c, 6), 0);
        strip.addView(t, l);
        if (trailing != null) strip.addView(trailing);
    }

    // ================================================================= sections

    interface Picked {
        void run(String id, String name);
    }

    /** Chips of the suggested home sections + "other sections" picker. */
    static View sections(final Activity a, final JSONObject st, final boolean series, final Picked cb) {
        LinearLayout box = CgUi.vbox(a);
        box.setBackground(NxUi.surface(a, 18));
        box.setPadding(CgUi.dp(a, 14), CgUi.dp(a, 12), CgUi.dp(a, 14), CgUi.dp(a, 12));
        LinearLayout head = CgUi.hbox(a);
        head.addView(NxUi.icon(a, NxIcons.icon(CgIcons.FOLDER, NxUi.ORANGE), 0), new LinearLayout.LayoutParams(CgUi.dp(a, 18), CgUi.dp(a, 18)));
        String cur = CxJ.s(st, "section_name");
        TextView t = CgUi.text(a, cur.length() > 0 ? "القسم: " + cur : "القسم في الصفحة الرئيسية", 13.5f, NxUi.TEXT, true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f);
        tl.setMargins(CgUi.dp(a, 8), 0, 0, 0);
        head.addView(t, tl);
        box.addView(head);
        if (cur.length() == 0) {
            box.addView(CgUi.text(a, "بدون قسم لن يظهر في أي صف بالرئيسية.", 11.5f, NxUi.ORANGE, false), NxUi.lp(a, NxUi.MATCH, NxUi.WRAP, 0, 4, 0, 0));
        }
        CxUi.Flow flow = new CxUi.Flow(a);
        String selId = CxJ.s(st, "section_id");
        JSONArray sug = st.optJSONArray("suggested_sections");
        ArrayList<String> shown = new ArrayList<String>();
        for (int i = 0; sug != null && i < sug.length(); i++) {
            final JSONObject o = sug.optJSONObject(i);
            if (o == null) continue;
            final String id = CxJ.s(o, "id");
            shown.add(id);
            flow.addView(chip(a, CxJ.s(o, "name"), id.equals(selId), new Runnable() {
                @Override
                public void run() {
                    cb.run(id, CxJ.s(o, "name"));
                }
            }));
        }
        if (selId.length() > 0 && !shown.contains(selId)) {
            flow.addView(chip(a, CxJ.firstNonEmpty(cur, selId), true, null));
        }
        flow.addView(chip(a, "أقسام أخرى ▾", false, new Runnable() {
            @Override
            public void run() {
                ArrayList<CxUi.Option> opts = new ArrayList<CxUi.Option>();
                CgData d = CgData.cur();
                for (int i = 0; d != null && i < d.sections.size(); i++) {
                    JSONObject s = d.sections.get(i);
                    String type = CxJ.s(s, "type");
                    if (series && "movie".equals(type)) continue;
                    if (!series && "series".equals(type)) continue;
                    opts.add(new CxUi.Option(CxJ.s(s, "id"), CxJ.s(s, "name"), s.optBoolean("enabled", true) ? "" : "(متوقف)", null));
                }
                CxUi.pick(a, "اختر القسم", opts, CxJ.s(st, "section_id"), null, new CgUi.Callback<CxUi.Option>() {
                    @Override
                    public void run(CxUi.Option o) {
                        cb.run(o.id, o.title);
                    }
                }, null);
            }
        }));
        if (selId.length() > 0) {
            flow.addView(chip(a, "بدون قسم", false, new Runnable() {
                @Override
                public void run() {
                    cb.run("", "");
                }
            }));
        }
        box.addView(flow, NxUi.lp(a, NxUi.MATCH, NxUi.WRAP, 0, 10, 0, 0));
        return box;
    }

    private static TextView chip(Context c, String label, boolean on, final Runnable r) {
        TextView t = CgUi.text(c, label, 12.5f, on ? 0xFFFFFFFF : NxUi.TEXT, true);
        t.setPadding(CgUi.dp(c, 12), CgUi.dp(c, 6), CgUi.dp(c, 12), CgUi.dp(c, 6));
        if (on) {
            t.setBackground(NxUi.gradient(c, 0xFF4F6BFF, 0xFF6C8CFF, 16));
        } else {
            NxUi.pressable(t, CgUi.bg(c, NxUi.SURF_HI, 16, NxUi.LINE), 16);
        }
        if (r != null) {
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    r.run();
                }
            });
        }
        return t;
    }

    // ==================================================================== misc

    static String clipboard(Context c) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return "";
            CharSequence s = cd.getItemAt(0).coerceToText(c);
            return s == null ? "" : s.toString().trim();
        } catch (Throwable t) {
            return "";
        }
    }

    static String host(String url) {
        try {
            String h = new java.net.URL(url).getHost();
            return h == null ? url : h;
        } catch (Throwable t) {
            return url;
        }
    }

    /** Tiny label + value pair used in result cards. */
    static View kv(Context c, String k, String v, int color) {
        LinearLayout r = CgUi.hbox(c);
        r.addView(CgUi.text(c, k, 12.5f, NxUi.SUB, false), new LinearLayout.LayoutParams(0, NxUi.WRAP, 1f));
        TextView tv = CgUi.text(c, v, 12.5f, color, true);
        r.addView(tv);
        r.setLayoutParams(NxUi.lp(c, NxUi.MATCH, NxUi.WRAP, 0, 4, 0, 0));
        return r;
    }
}
