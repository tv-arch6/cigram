package com.Cigram.vid;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * مدخل "شاهد مع صديقك":
 *  - نافذة اختيار عند الضغط على «شاهد هَسه» (شاهد بمفردك / شاهد مع صديقك)؛
 *  - بوابة تسجيل الدخول (لا تمسح الجلسة أبداً)؛
 *  - اختيار الحلقة لمسلسل؛
 *  - بناء بيانات المحتوى (metadata فقط) وتحويلها لرابط تشغيل من كتالوج هذا الجهاز.
 *
 * "شاهد بمفردك" يستدعي الـ Runnable الذي مرّره المستدعي، وهو نفس مسار المشغل القديم حرفياً.
 * أي فشل هنا أو في الخدمة لا يعطّل المشاهدة الفردية: عند أي استثناء نفتح المشغل القديم مباشرة.
 */
public final class CigramTogether {

    /** مفتاح إيقاف سريع للميزة. false = «شاهد هَسه» يفتح المشغل الفردي مباشرة كما في v4.1. */
    public static final boolean ENABLED = true;

    private static boolean sheetShowing;

    private CigramTogether() { }

    // ------------------------------------------------------------------ نافذة الاختيار

    /**
     * @param item  بيانات المحتوى المعروض في صفحة التفاصيل (فيلم أو مسلسل)
     * @param solo  يفتح المشغل الفردي الأصلي (يُستدعى كما كان قبل التعديل)
     */
    public static void onWatchNow(final Activity a, final JSONObject item, final Runnable solo) {
        try {
            if (!ENABLED || !CigramTogetherApi.enabled() || a == null || item == null || a.isFinishing()) {
                solo.run();
                return;
            }
            if (sheetShowing) return;                       // ضغط سريع متكرر: نافذة واحدة فقط
            showChooser(a, item, solo);
        } catch (Throwable t) {
            android.util.Log.w("CigramTogether", "chooser failed, falling back to solo player", t);
            sheetShowing = false;
            solo.run();
        }
    }

    // v4.4 — centered orange card (design requested by the owner):
    //   [title] [شاهد مع صديقك] [شاهد بمفردك] [إلغاء لاحقاً]
    //   card corners = 20% of its shorter side, buttons = 50% (full pill), white buttons with
    //   black text, no icons and no descriptions, sima font everywhere.
    // v6.2: the ad is shown once, right after «شاهد هَسه» and BEFORE this chooser
    // (CigramStage1InitProvider). Neither choice shows an ad any more.
    private static final int CHOOSER_ORANGE = 0xFFFF8A1F;
    private static final int CHOOSER_INK = 0xFF000000;

    private static void showChooser(final Activity a, final JSONObject item, final Runnable solo) {
        sheetShowing = true;
        try { CigramEpisodeAds.preload(a); } catch (Throwable ignored) { }   // ready while the user decides

        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        final android.graphics.Typeface sima = CigramStage1InitProvider.cigramFont(a);

        final LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(CigramUI.dp(a, 24), CigramUI.dp(a, 30), CigramUI.dp(a, 24), CigramUI.dp(a, 22));
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(CHOOSER_ORANGE);
        bg.setCornerRadius(CigramUI.dp(a, 28));
        card.setBackground(bg);
        card.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                int shorter = Math.min(r - l, b - t);
                if (shorter > 0) bg.setCornerRadius(shorter * 0.20f);    // 20%
            }
        });

        TextView title = new TextView(a);
        title.setText("شلون تحب تشاهد؟");
        title.setTextColor(CHOOSER_INK);
        title.setTextSize(21f);
        title.setTypeface(sima, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
        tl.bottomMargin = CigramUI.dp(a, 20);
        card.addView(title, tl);

        card.addView(chooserButton(a, sima, "شاهد مع صديقك", new View.OnClickListener() {
            @Override public void onClick(View v) {
                d.dismiss();
                if (a.isFinishing()) return;
                startTogether(a, item);
            }
        }));
        card.addView(chooserButton(a, sima, "شاهد بمفردك", new View.OnClickListener() {
            @Override public void onClick(View v) {
                d.dismiss();
                if (a.isFinishing()) return;
                try { solo.run(); } catch (Throwable t) { android.util.Log.w("CigramTogether", "solo", t); }
            }
        }));

        final TextView cancel = new TextView(a);
        cancel.setText("إلغاء لاحقاً");
        cancel.setTextColor(CHOOSER_INK);
        cancel.setTextSize(15f);
        cancel.setTypeface(sima, android.graphics.Typeface.BOLD);
        cancel.setGravity(Gravity.CENTER);
        cancel.setClickable(true);
        cancel.setFocusable(true);
        final GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setColor(0x00000000);
        cancelBg.setStroke(Math.max(1, CigramUI.dp(a, 1)), 0x59000000);
        cancel.setBackground(cancelBg);
        pill(cancel, cancelBg);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, CigramUI.dp(a, 46));
        cl.topMargin = CigramUI.dp(a, 18);
        cl.leftMargin = CigramUI.dp(a, 36);
        cl.rightMargin = CigramUI.dp(a, 36);
        card.addView(cancel, cl);

        d.setContentView(card);
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface di) { sheetShowing = false; }
        });
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screen = a.getResources().getDisplayMetrics().widthPixels;
            int width = Math.min((int) (screen * 0.86f), CigramUI.dp(a, 400));
            w.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            w.setDimAmount(0.65f);
        }
        d.show();
        CigramTvFocus.attachDialog(d);   // v10.66: TV remote (TV only)
        card.setAlpha(0f);
        card.setScaleX(0.92f);
        card.setScaleY(0.92f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    /** White pill button with black sima text (corners = 50% of its height). */
    private static View chooserButton(Activity a, android.graphics.Typeface sima, String text,
                                      View.OnClickListener click) {
        TextView b = new TextView(a);
        b.setText(text);
        b.setTextColor(CHOOSER_INK);
        b.setTextSize(17f);
        b.setTypeface(sima, android.graphics.Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setClickable(true);
        b.setFocusable(true);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(0xFFFFFFFF);
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22000000), shape, null));
        pill(b, shape);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, CigramUI.dp(a, 56));
        lp.topMargin = CigramUI.dp(a, 12);
        b.setLayoutParams(lp);
        return b;
    }

    /** Keeps the corner radius at 50% of the view height, whatever the font size makes it. */
    private static void pill(View v, final GradientDrawable shape) {
        v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int l, int t, int r, int b,
                                                 int ol, int ot, int or, int ob) {
                int h = b - t;
                if (h > 0) shape.setCornerRadius(h * 0.5f);
            }
        });
    }

    // ------------------------------------------------------------------ مسار "مع صديقك"

    private static void startTogether(final Activity a, final JSONObject item) {
        // 1) جلسة الحساب: نطلب الدخول بهدوء ولا نمسح شيئاً
        if (!CigramAds.isLoggedIn(a)) {
            CigramUI.sheet(a).icon(CigramUI.ICON_USER, CigramUI.TEAL).title("سجّل الدخول أولاً")
                    .message("غرف المشاهدة مرتبطة بحسابك حتى يعرف صديقك من أنت. سجّل الدخول ثم ارجع واضغط «شاهد هَسه» مرة ثانية.")
                    .primary("تسجيل الدخول", CigramUI.TEAL, new CigramUI.Click() {
                        @Override public boolean onClick(CigramUI.Sheet s) {
                            openAccount(a);
                            return false;
                        }
                    }).secondary("إلغاء", null).show();
            return;
        }
        // 2) مسلسل: نختار الحلقة أولاً. فيلم: نبدأ مباشرة
        boolean series = CigramStage1InitProvider.castIsSeries(item);
        if (series) {
            pickEpisode(a, item, new EpisodeCb() {
                @Override public void picked(JSONObject episode) { openHub(a, contentFor(item, episode)); }
            });
        } else {
            openHub(a, contentFor(item, null));
        }
    }

    private static void openAccount(Activity a) {
        try {
            Intent i = new Intent(a, Class.forName("com.Cigram.vid.AccountActivity"));
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            a.startActivity(i);
        } catch (Throwable e) {
            CigramSilent.toast(a, "افتح صفحة الحساب وسجّل الدخول.", android.widget.Toast.LENGTH_LONG);
        }
    }

    private static void openHub(Activity a, JSONObject content) {
        try {
            Intent i = new Intent(a, CigramTogetherActivity.class);
            i.putExtra("mode", "hub");
            i.putExtra("content", content.toString());
            a.startActivity(i);
        } catch (Throwable e) {
            android.util.Log.w("CigramTogether", "open hub failed", e);
            CigramSilent.toast(a, "تعذر فتح المشاهدة المشتركة.", android.widget.Toast.LENGTH_SHORT);
        }
    }

    // ------------------------------------------------------------------ اختيار حلقة

    public interface EpisodeCb { void picked(JSONObject episode); }

    /** يعرض مواسم/حلقات المسلسل من الكتالوج الفعلي (بلا أسماء أو أعداد ثابتة في الكود). */
    public static void pickEpisode(final Activity a, JSONObject series, final EpisodeCb cb) {
        final JSONArray eps = CigramStage1InitProvider.castEpisodes(series);
        if (eps.length() == 0) {
            CigramSilent.toast(a, "لا توجد حلقات متاحة لهذا المسلسل.", android.widget.Toast.LENGTH_SHORT);
            return;
        }
        final CigramUI.Sheet sheet = CigramUI.sheet(a).icon(CigramUI.ICON_FLAG, CigramUI.RED).title("اختر الحلقة");
        ScrollView sv = new ScrollView(a);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int lastSeason = Integer.MIN_VALUE;
        for (int i = 0; i < eps.length(); i++) {
            final JSONObject ep = eps.optJSONObject(i);
            if (ep == null) continue;
            int season = CigramContent.intOf(ep, "season", "season_number");
            int num = CigramContent.intOf(ep, "episode", "episode_number", "ep", "number");
            if (season != lastSeason) {
                lastSeason = season;
                TextView h = CigramUI.label(a, "الموسم " + (season <= 0 ? 1 : season), 14f, CigramUI.AMBER, true);
                h.setGravity(Gravity.RIGHT);
                h.setPadding(0, CigramUI.dp(a, 10), 0, CigramUI.dp(a, 4));
                list.addView(h, new LinearLayout.LayoutParams(-1, -2));
            }
            String name = CigramContent.text(ep, "title", "name", "episode_title");
            TextView row = CigramUI.label(a, "الحلقة " + num + (name.length() > 0 ? " — " + name : ""), 14f, CigramUI.TEXT, false);
            row.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            row.setMinHeight(CigramUI.dp(a, 44));
            row.setPadding(CigramUI.dp(a, 12), 0, CigramUI.dp(a, 12), 0);
            row.setBackground(CigramUI.round(CigramUI.BG_SOFT, 10, a));
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { sheet.dismiss(); cb.picked(ep); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = CigramUI.dp(a, 6);
            list.addView(row, lp);
        }
        sv.addView(list, new ViewGroup.LayoutParams(-1, -2));
        sheet.view(sv).secondary("إلغاء", null).show();
    }

    // ------------------------------------------------------------------ بيانات المحتوى

    /** metadata فقط (لا روابط فيديو): كل جهاز يحلّ الرابط من كتالوجه الخاص. */
    public static JSONObject contentFor(JSONObject item, JSONObject episode) {
        JSONObject c = new JSONObject();
        try {
            boolean isEp = episode != null;
            String id = CigramContent.text(item, "id", "series_id");
            String title = CigramContent.text(item, "series_title", "title", "name", "title_ar", "title_en");
            c.put("type", isEp ? "episode" : "movie");
            c.put("content_id", id.length() > 0 ? id : title);
            c.put("title", isEp ? title : title);
            c.put("poster", CigramContent.text(isEp ? episode : item, "poster", "backdrop", "image").length() > 0
                    ? CigramContent.text(isEp ? episode : item, "poster", "backdrop", "image")
                    : CigramContent.text(item, "poster", "series_poster", "image", "backdrop"));
            if (isEp) {
                c.put("series_id", id.length() > 0 ? id : title);
                c.put("series_title", title);
                int season = CigramContent.intOf(episode, "season", "season_number");
                c.put("season", season <= 0 ? 1 : season);
                c.put("episode", CigramContent.intOf(episode, "episode", "episode_number", "ep", "number"));
            }
        } catch (Throwable ignored) { }
        return c;
    }

    /**
     * يحلّ المحتوى المتفق عليه في الغرفة إلى رابط تشغيل من كتالوج هذا الجهاز.
     * @return {video, subtitle, title, poster, series_title, series_id, season, episode, type, id} أو null إن لم يوجد
     */
    public static JSONObject resolvePlayable(JSONObject content) {
        try {
            if (content == null) return null;
            boolean isEp = "episode".equals(content.optString("type"));
            JSONObject out = new JSONObject();
            if (!isEp) {
                JSONObject m = CigramContent.find("movie", content.optString("content_id"), content.optString("title"));
                if (m == null) return null;
                String video = CigramContent.text(m, "video", "link", "video_url", "movie_url", "stream_url", "play_url");
                if (video.length() == 0) return null;
                out.put("video", video);
                out.put("subtitle", CigramContent.text(m, "subtitle", "subtitle_url"));
                out.put("title", CigramContent.title(m));
                out.put("poster", CigramContent.text(m, "poster", "backdrop", "image"));
                out.put("type", "movie");
                out.put("id", CigramContent.text(m, "id"));
                return out;
            }
            JSONObject series = CigramContent.find("series", content.optString("series_id"), content.optString("series_title"));
            if (series == null) return null;
            JSONArray eps = CigramStage1InitProvider.castEpisodes(series);
            int season = content.optInt("season", 1), num = content.optInt("episode", 0);
            for (int i = 0; i < eps.length(); i++) {
                JSONObject ep = eps.optJSONObject(i);
                if (ep == null) continue;
                int s = CigramContent.intOf(ep, "season", "season_number");
                if (s <= 0) s = 1;
                if (s != season || CigramContent.intOf(ep, "episode", "episode_number", "ep", "number") != num) continue;
                String video = CigramContent.text(ep, "video", "link", "video_url", "movie_url", "stream_url", "play_url");
                if (video.length() == 0) return null;
                out.put("video", video);
                out.put("subtitle", CigramContent.text(ep, "subtitle", "subtitle_url"));
                out.put("title", CigramContent.text(ep, "title", "name", "episode_title"));
                out.put("poster", CigramContent.text(ep, "poster", "backdrop", "image").length() > 0
                        ? CigramContent.text(ep, "poster", "backdrop", "image") : CigramContent.text(series, "poster", "image"));
                out.put("type", "episode");
                out.put("id", CigramContent.text(series, "id"));
                out.put("series_id", CigramContent.text(series, "id", "series_id"));
                out.put("series_title", CigramContent.title(series));
                out.put("season", season);
                out.put("episode", num);
                return out;
            }
        } catch (Throwable t) {
            android.util.Log.w("CigramTogether", "resolvePlayable failed", t);
        }
        return null;
    }

    /**
     * الحلقة التالية الفعلية بعد حلقة الغرفة الحالية (قد تكون من موسم تالٍ).
     * @return content جاهز للإرسال، أو null إذا لا توجد حلقة تالية متاحة
     */
    public static JSONObject nextContent(JSONObject content) {
        try {
            if (content == null || !"episode".equals(content.optString("type"))) return null;
            JSONObject series = CigramContent.find("series", content.optString("series_id"), content.optString("series_title"));
            if (series == null) return null;
            JSONArray eps = CigramStage1InitProvider.castEpisodes(series);
            int season = content.optInt("season", 1), num = content.optInt("episode", 0);
            for (int i = 0; i < eps.length(); i++) {
                JSONObject ep = eps.optJSONObject(i);
                if (ep == null) continue;
                int s = CigramContent.intOf(ep, "season", "season_number");
                if (s <= 0) s = 1;
                if (s == season && CigramContent.intOf(ep, "episode", "episode_number", "ep", "number") == num) {
                    for (int j = i + 1; j < eps.length(); j++) {
                        JSONObject nx = eps.optJSONObject(j);
                        if (nx == null) continue;
                        String video = CigramContent.text(nx, "video", "link", "video_url", "movie_url", "stream_url", "play_url");
                        if (video.length() == 0) continue;           // حلقة بلا رابط ليست "متاحة"
                        return contentFor(series, nx);
                    }
                    return null;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    public static ArrayList<JSONObject> episodesOf(JSONObject content) {
        ArrayList<JSONObject> out = new ArrayList<JSONObject>();
        try {
            JSONObject series = CigramContent.find("series", content.optString("series_id"), content.optString("series_title"));
            if (series == null) return out;
            JSONArray eps = CigramStage1InitProvider.castEpisodes(series);
            for (int i = 0; i < eps.length(); i++) {
                JSONObject ep = eps.optJSONObject(i);
                if (ep != null) out.add(contentFor(series, ep));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    // ------------------------------------------------------------------ عناصر مشتركة بين شاشات الغرفة

    /** صورة الحساب من avatar_url (إن وُجدت) وإلا دائرة بحرف الاسم الأول. لا صور أو أسماء من العميل: تأتي من الخادم. */
    public static View avatarView(android.content.Context c, JSONObject user, int sizeDp) {
        final int px = CigramUI.dp(c, sizeDp);
        android.widget.FrameLayout f = new android.widget.FrameLayout(c);
        f.setLayoutParams(new ViewGroup.LayoutParams(px, px));
        String name = user == null ? "" : user.optString("name", "");
        TextView t = CigramUI.label(c, name.length() == 0 ? "؟" : name.substring(0, name.offsetByCodePoints(0, 1)), sizeDp * 0.42f, CigramUI.TEXT, true);
        t.setGravity(Gravity.CENTER);
        int[] palette = {0xFF26A5B5, 0xFFE9A23B, 0xFF7C6CF0, 0xFF3DDC97, 0xFFE2546B, 0xFF4A90D9};
        int color = palette[Math.abs((user == null ? "" : user.optString("uid", name)).hashCode()) % palette.length];
        t.setBackground(CigramUI.round(color, sizeDp, c));
        f.addView(t, new android.widget.FrameLayout.LayoutParams(px, px));
        String url = user == null ? "" : user.optString("avatar_url", "");
        if (url.startsWith("https://")) {
            android.widget.ImageView iv = new android.widget.ImageView(c);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); }
            });
            iv.setClipToOutline(true);
            f.addView(iv, new android.widget.FrameLayout.LayoutParams(px, px));
            try { CigramStage1InitProvider.cigramLoadPoster(c, url, iv); } catch (Throwable ignored) { }
        }
        if (false && CigramAvatarFrame.userFramed(c, user)) {   // badge is never drawn on profile photos   // active Plus / verified member: ring around the avatar
            f.setLayoutParams(new android.widget.FrameLayout.LayoutParams(px, px));
            return CigramAvatarFrame.wrap(c, f, px);
        }
        return f;
    }

    public static String contentLabel(JSONObject c) {
        if (c == null) return "";
        if ("episode".equals(c.optString("type"))) {
            return c.optString("series_title", c.optString("title")) + " — الموسم " + c.optInt("season", 1) + " الحلقة " + c.optInt("episode", 0);
        }
        return c.optString("title");
    }
}
