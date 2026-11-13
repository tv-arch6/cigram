package com.my.newproject;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** In-admin previews that approximate how content looks in the Cigram app (no data is stored). */
final class CxPreview {

    private CxPreview() {}

    static int screenWidthDp(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        return (int) (c.getResources().getDisplayMetrics().widthPixels / d);
    }

    private static View gradient(Context c) {
        View g = new View(c);
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x66000000, 0xEE00151C});
        g.setBackground(gd);
        return g;
    }

    /** Movie / series page: backdrop, poster, title, year, rating, category, quality, description, watch button. */
    static View contentPage(Context c, JSONObject st, boolean series, View.OnClickListener watch) {
        LinearLayout card = CgUi.vbox(c);
        card.setBackground(CgUi.bg(c, CgCfg.CARD, 16, CgCfg.STROKE));
        int w = screenWidthDp(c) - 26;
        FrameLayout top = new FrameLayout(c);
        CxUi.PosterBox back = new CxUi.PosterBox(c, w, 0.62f, "16:9");
        String bd = CxJ.firstNonEmpty(CxJ.s(st, "backdrop"), CxJ.s(st, "poster"));
        back.set(bd);
        top.addView(back, new FrameLayout.LayoutParams(CgUi.dp(c, w), Math.round(CgUi.dp(c, w) * 0.62f)));
        top.addView(gradient(c), new FrameLayout.LayoutParams(CgUi.dp(c, w), Math.round(CgUi.dp(c, w) * 0.62f)));
        LinearLayout info = CgUi.hbox(c);
        info.setGravity(Gravity.BOTTOM);
        info.setPadding(CgUi.dp(c, 12), CgUi.dp(c, 12), CgUi.dp(c, 12), CgUi.dp(c, 12));
        CxUi.PosterBox poster = new CxUi.PosterBox(c, 84, 1.5f, "2:3");
        poster.set(CxJ.s(st, "poster"));
        info.addView(poster);
        LinearLayout col = CgUi.vbox(c);
        TextView t = CgUi.text(c, CxJ.firstNonEmpty(CxJ.s(st, "title"), "(بدون عنوان)"), 17, CgCfg.TEXT, true);
        t.setMaxLines(2);
        col.addView(t);
        StringBuilder meta = new StringBuilder();
        if (CxJ.s(st, "year").length() > 0) meta.append(CxJ.s(st, "year"));
        if (CxJ.s(st, "rating").length() > 0) meta.append(meta.length() > 0 ? "  •  " : "").append("IMDb ★ ").append(CxJ.s(st, "rating"));
        if (CxJ.s(st, "duration").length() > 0) meta.append(meta.length() > 0 ? "  •  " : "").append(CxJ.s(st, "duration"));
        if (meta.length() > 0) col.addView(CgUi.text(c, meta.toString(), 12.5f, CgCfg.ACCENT, true));
        if (CxJ.s(st, "category").length() > 0) {
            TextView cat = CgUi.muted(c, CxJ.s(st, "category"));
            cat.setMaxLines(2);
            col.addView(cat);
        }
        LinearLayout badges = CgUi.hbox(c);
        badges.addView(CgUi.badge(c, series ? "مسلسل" : "فيلم", CgCfg.ACCENT));
        if (CxJ.s(st, "quality").length() > 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            lp.setMargins(CgUi.dp(c, 6), 0, 0, 0);
            badges.addView(CgUi.badge(c, CxJ.s(st, "quality"), CgCfg.GOOD), lp);
        }
        col.addView(badges, CgUi.lp(c, CgUi.WRAP, CgUi.WRAP, 0, 6, 0, 0));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        clp.setMargins(CgUi.dp(c, 10), 0, 0, 0);
        info.addView(col, clp);
        top.addView(info, new FrameLayout.LayoutParams(CgUi.dp(c, w), Math.round(CgUi.dp(c, w) * 0.62f), Gravity.BOTTOM));
        card.addView(top, new LinearLayout.LayoutParams(CgUi.dp(c, w), Math.round(CgUi.dp(c, w) * 0.62f)));
        LinearLayout bottom = CgUi.vbox(c);
        bottom.setPadding(CgUi.dp(c, 12), CgUi.dp(c, 10), CgUi.dp(c, 12), CgUi.dp(c, 12));
        String desc = CxJ.s(st, "description");
        TextView d = CgUi.text(c, desc.length() > 0 ? desc : "لا يوجد وصف.", 13, desc.length() > 0 ? CgCfg.TEXT : CgCfg.MUTED, false);
        d.setMaxLines(5);
        d.setEllipsize(android.text.TextUtils.TruncateAt.END);
        d.setLineSpacing(0, 1.15f);
        bottom.addView(d);
        TextView watchBtn = CgUi.button(c, series ? "▶  عرض الحلقات" : "▶  مشاهدة", 0);
        if (watch != null) watchBtn.setOnClickListener(watch);
        bottom.addView(watchBtn, CgUi.lp(c, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        card.addView(bottom, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        return card;
    }

    /** Episode card preview: thumbnail, S/E code, title, duration, availability badges. */
    static View episodeCard(Context c, String thumb, String code, String title, boolean hasVideo, boolean hasSubtitle) {
        LinearLayout row = CgUi.hbox(c);
        row.setBackground(CgUi.bg(c, CgCfg.CARD, 14, CgCfg.STROKE));
        row.setPadding(CgUi.dp(c, 8), CgUi.dp(c, 8), CgUi.dp(c, 8), CgUi.dp(c, 8));
        CxUi.PosterBox box = new CxUi.PosterBox(c, 120, 0.5625f, "16:9");
        box.set(thumb);
        row.addView(box);
        LinearLayout col = CgUi.vbox(c);
        col.addView(CgUi.text(c, code, 12, CgCfg.ACCENT, true));
        TextView t = CgUi.text(c, title, 14.5f, CgCfg.TEXT, true);
        t.setMaxLines(2);
        col.addView(t);
        LinearLayout b = CgUi.hbox(c);
        b.addView(CgUi.badge(c, hasVideo ? "فيديو ✔" : "بدون فيديو", hasVideo ? CgCfg.GOOD : CgCfg.WARN));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        lp.setMargins(CgUi.dp(c, 6), 0, 0, 0);
        b.addView(CgUi.badge(c, hasSubtitle ? "ترجمة ✔" : "بدون ترجمة", hasSubtitle ? CgCfg.GOOD : CgCfg.GRAY), lp);
        col.addView(b, CgUi.lp(c, CgUi.WRAP, CgUi.WRAP, 0, 6, 0, 0));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        clp.setMargins(CgUi.dp(c, 10), 0, 0, 0);
        row.addView(col, clp);
        return row;
    }

    /** Carousel slide close to the Cigram home banner: wide image, gradient, kind badge, title, play button. */
    static final class Slide {
        final FrameLayout view;
        private final CxUi.PosterBox image;
        private final TextView titleView;
        private final TextView kindBadge;
        private final TextView dates;

        Slide(Context c) {
            int w = screenWidthDp(c) - 24;
            int px = CgUi.dp(c, w);
            int h = Math.round(px * 0.5f);
            view = new FrameLayout(c);
            image = new CxUi.PosterBox(c, w, 0.5f, "عريض 2:1");
            view.addView(image, new FrameLayout.LayoutParams(px, h));
            view.addView(gradient(c), new FrameLayout.LayoutParams(px, h));
            LinearLayout info = CgUi.vbox(c);
            info.setGravity(Gravity.BOTTOM);
            info.setPadding(CgUi.dp(c, 14), CgUi.dp(c, 10), CgUi.dp(c, 14), CgUi.dp(c, 12));
            kindBadge = CgUi.badge(c, "", CgCfg.ACCENT);
            info.addView(kindBadge, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            titleView = CgUi.text(c, "", 18, CgCfg.TEXT, true);
            titleView.setMaxLines(2);
            titleView.setPadding(0, CgUi.dp(c, 4), 0, CgUi.dp(c, 2));
            info.addView(titleView, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            LinearLayout actions = CgUi.hbox(c);
            TextView play = CgUi.smallButton(c, "▶  شاهد الآن", 0);
            actions.addView(play, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            dates = CgUi.text(c, "", 11, CgCfg.MUTED, false);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            dl.setMargins(CgUi.dp(c, 10), 0, 0, 0);
            actions.addView(dates, dl);
            info.addView(actions, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            view.addView(info, new FrameLayout.LayoutParams(px, h, Gravity.BOTTOM));
            view.setLayoutParams(CgUi.lp(c, px, h, 0, 0, 0, 8));
        }

        void setImageUrl(String url) {
            image.set(url);
        }

        void setBitmap(Bitmap b) {
            image.setBitmap(b);
        }

        void setTitle(String t, String kind) {
            titleView.setText(t == null || t.length() == 0 ? "اختر المحتوى" : t);
            kindBadge.setText(kind == null || kind.length() == 0 ? "—" : kind);
        }

        void setDates(String text) {
            dates.setText(text == null ? "" : text);
        }

        void onHint(CxUi.RatioHint h) {
            image.onHint(h);
        }
    }

    /** Channel tile as shown in the channels grid, with ONLINE / OFFLINE / UNKNOWN badge. */
    static final class ChannelCard {
        final LinearLayout view;
        private final CxUi.PosterBox logo;
        private final TextView name;
        private final TextView section;
        private final TextView status;

        ChannelCard(Context c) {
            view = CgUi.hbox(c);
            view.setBackground(CgUi.bg(c, CgCfg.CARD, 16, CgCfg.STROKE));
            view.setPadding(CgUi.dp(c, 12), CgUi.dp(c, 12), CgUi.dp(c, 12), CgUi.dp(c, 12));
            logo = new CxUi.PosterBox(c, 64, 1f, "مربع 1:1");
            view.addView(logo);
            LinearLayout col = CgUi.vbox(c);
            name = CgUi.text(c, "اسم القناة", 15.5f, CgCfg.TEXT, true);
            name.setMaxLines(1);
            col.addView(name);
            section = CgUi.muted(c, "—");
            col.addView(section);
            status = CgUi.badge(c, "UNKNOWN", CgCfg.GRAY);
            col.addView(status, CgUi.lp(c, CgUi.WRAP, CgUi.WRAP, 0, 6, 0, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
            lp.setMargins(CgUi.dp(c, 12), 0, 0, 0);
            view.addView(col, lp);
        }

        void set(String n, String sec, String statusText, int color) {
            name.setText(n == null || n.length() == 0 ? "اسم القناة" : n);
            section.setText(sec == null || sec.length() == 0 ? "بدون قسم" : sec);
            status.setText(statusText);
            status.setTextColor(color);
            status.setBackground(CgUi.bg(view.getContext(), CgUi.alpha(color, 0x26), 8, CgUi.alpha(color, 0x66)));
        }

        void setLogoUrl(String u) {
            logo.set(u);
        }

        void setLogoBitmap(Bitmap b) {
            logo.setBitmap(b);
        }

        void onHint(CxUi.RatioHint h) {
            logo.onHint(h);
        }
    }
}
