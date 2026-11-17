package com.Cigram.vid;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Account page section «أيقونة التطبيق»: the active icon in a warm glowing frame with a capsule, a horizontal list of all
 * icons (all of them available) and the apply button.
 */
final class CigramAppIconUI {

    static final String TAG = "cigram_app_icon_section";
    private static final int GOLD = 0xFFF5B83D;

    private CigramAppIconUI() { }

    private static GradientDrawable box(int fill, int radiusDp, int stroke, int strokeDp, Activity a) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(CigramUI.dp(a, radiusDp));
        if (strokeDp > 0) g.setStroke(CigramUI.dp(a, strokeDp), stroke);
        return g;
    }

    private static android.graphics.drawable.Drawable art(Activity a, CigramAppIcon.Variant v) {
        int id = a.getResources().getIdentifier(v.drawable, "drawable", a.getPackageName());
        return id == 0 ? null : a.getResources().getDrawable(id);
    }

    /** 2026: the picker as a sheet opened from Settings → «أيقونة التطبيق» (same section, same rules). */
    static void openDialog(final Activity a) {
        try {
            final Dialog d = new Dialog(a);
            d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            android.widget.ScrollView scroll = new android.widget.ScrollView(a);
            final LinearLayout host = new LinearLayout(a);
            host.setOrientation(LinearLayout.VERTICAL);
            host.setPadding(0, CigramUI.dp(a, 6), 0, CigramUI.dp(a, 6));
            scroll.addView(host, new FrameLayout.LayoutParams(-1, -2));
            install(a, host, 0);
            TextView close = CigramUI.label(a, "إغلاق", 14f, CigramPalette.of(a).text2, true);
            close.setGravity(Gravity.CENTER);
            close.setMinHeight(CigramUI.dp(a, 48));
            close.setClickable(true);
            close.setFocusable(true);
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { d.dismiss(); }
            });
            host.addView(close, new LinearLayout.LayoutParams(-1, -2));
            d.setContentView(scroll);
            if (d.getWindow() != null) {
                d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
                d.getWindow().setLayout(Math.min(CigramUI.dp(a, 460), a.getResources().getDisplayMetrics().widthPixels - CigramUI.dp(a, 24)),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            d.show();
        } catch (Throwable error) {
            android.util.Log.w("CigramAppIcon", "picker dialog failed", error);
        }
    }

    /** Adds (or rebuilds) the section into {@code host} at {@code index}. */
    static void install(final Activity a, final LinearLayout host, int index) {
        View old = host.findViewWithTag(TAG);
        if (old != null) host.removeView(old);
        if (index < 0 || index > host.getChildCount()) index = host.getChildCount();
        host.addView(build(a, host), index, rowParams(a));
    }

    private static LinearLayout.LayoutParams rowParams(Activity a) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(CigramUI.dp(a, 14), CigramUI.dp(a, 10), CigramUI.dp(a, 14), CigramUI.dp(a, 6));
        return lp;
    }

    private static View build(final Activity a, final LinearLayout host) {
        CigramAppIcon.enforce(a);
        final String active = CigramAppIcon.active(a);
        final String[] picked = {active};

        final LinearLayout card = new LinearLayout(a);
        card.setTag(TAG);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        final CigramPalette pal = CigramPalette.of(a);
        card.setBackground(box(pal.surface, 20, pal.stroke, 1, a));
        int pad = CigramUI.dp(a, 14);
        card.setPadding(pad, pad, pad, pad);

        TextView title = CigramUI.label(a, "أيقونة التطبيق", 16f, pal.text, true);
        title.setGravity(Gravity.RIGHT);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView sub = CigramUI.label(a, "اختر شكل Cigram على شاشتك الرئيسية", 12f, pal.text3, false);
        sub.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = CigramUI.dp(a, 2);
        card.addView(sub, sl);

        // --- the active icon: warm glowing frame + capsule
        final LinearLayout hero = new LinearLayout(a);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = CigramUI.dp(a, 12);
        card.addView(hero, hl);

        // --- list of icons
        final HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        final LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.HORIZONTAL);
        list.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        scroll.addView(list, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = CigramUI.dp(a, 14);
        card.addView(scroll, ll);

        final TextView apply = CigramUI.label(a, "تطبيق الأيقونة الآن", 14f, 0xFF1A1200, true);
        apply.setGravity(Gravity.CENTER);
        apply.setVisibility(View.GONE);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, CigramUI.dp(a, 46));
        al.topMargin = CigramUI.dp(a, 12);
        card.addView(apply, al);

        final Runnable[] render = new Runnable[1];
        render[0] = new Runnable() {
            @Override public void run() {
                renderHero(a, hero, CigramAppIcon.active(a));
                list.removeAllViews();
                for (final CigramAppIcon.Variant v : CigramAppIcon.ALL) {
                    list.addView(tile(a, v, v.id.equals(picked[0]), CigramAppIcon.active(a).equals(v.id), new View.OnClickListener() {
                        @Override public void onClick(View view) {
                            picked[0] = v.id;
                            render[0].run();
                        }
                    }));
                }
                boolean changed = !picked[0].equals(CigramAppIcon.active(a)) && CigramAppIcon.canUse(a, CigramAppIcon.find(picked[0]));
                apply.setVisibility(changed ? View.VISIBLE : View.GONE);
            }
        };
        apply.setBackground(box(GOLD, 14, 0, 0, a));
        apply.setClickable(true);
        apply.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CigramAppIcon.Variant t = CigramAppIcon.find(picked[0]);
                if (CigramAppIcon.apply(a, t.id)) {
                    CigramSilent.toast(a, "تم تغيير الأيقونة ✓ (قد يتأخر ظهورها لثوانٍ حسب الشاشة الرئيسية)", android.widget.Toast.LENGTH_LONG);
                } else {
                    CigramSilent.toast(a, "تعذّر تغيير الأيقونة على هذا الجهاز", android.widget.Toast.LENGTH_SHORT);
                }
                picked[0] = CigramAppIcon.active(a);
                render[0].run();
            }
        });
        render[0].run();
        return card;
    }

    private static void renderHero(Activity a, LinearLayout hero, String activeId) {
        hero.removeAllViews();
        CigramAppIcon.Variant v = CigramAppIcon.find(activeId);
        FrameLayout frame = new FrameLayout(a);
        GradientDrawable glow = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFFC857, 0xFFFF7A18, 0xFFFFB347});
        glow.setCornerRadius(CigramUI.dp(a, 26));
        frame.setBackground(glow);
        frame.setElevation(CigramUI.dp(a, 10));
        int p = CigramUI.dp(a, 3);
        frame.setPadding(p, p, p, p);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageDrawable(art(a, v));
        iv.setBackground(box(0xFF000000, 23, 0, 0, a));
        iv.setClipToOutline(true);
        frame.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        hero.addView(frame, new LinearLayout.LayoutParams(CigramUI.dp(a, 92), CigramUI.dp(a, 92)));
        TextView cap = CigramUI.label(a, "الأيقونة النشطة حالياً", 11.5f, 0xFF2B1A00, true);
        cap.setGravity(Gravity.CENTER);
        cap.setBackground(box(GOLD, 14, 0, 0, a));
        cap.setPadding(CigramUI.dp(a, 12), CigramUI.dp(a, 4), CigramUI.dp(a, 12), CigramUI.dp(a, 4));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.topMargin = CigramUI.dp(a, 8);
        hero.addView(cap, cl);
        TextView nm = CigramUI.label(a, v.name, 12.5f, 0xFFFFFFFF, false);
        nm.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.topMargin = CigramUI.dp(a, 4);
        hero.addView(nm, nl);
    }

    private static View tile(final Activity a, CigramAppIcon.Variant v, boolean picked, boolean isActive, View.OnClickListener click) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CigramUI.dp(a, 76), -2);
        lp.setMargins(CigramUI.dp(a, 5), 0, CigramUI.dp(a, 5), 0);
        col.setLayoutParams(lp);
        col.setClickable(true);
        col.setOnClickListener(click);
        col.setContentDescription(v.name);

        FrameLayout f = new FrameLayout(a);
        final CigramPalette pal = CigramPalette.of(a);
        f.setBackground(box(0x00000000, 18, picked ? GOLD : (isActive ? 0x88F5B83D : pal.hairline), picked ? 2 : 1, a));
        int p = CigramUI.dp(a, 3);
        f.setPadding(p, p, p, p);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageDrawable(art(a, v));
        iv.setBackground(box(0xFF000000, 15, 0, 0, a));
        iv.setClipToOutline(true);
        f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        col.addView(f, new LinearLayout.LayoutParams(CigramUI.dp(a, 72), CigramUI.dp(a, 72)));
        TextView n = CigramUI.label(a, v.name, 10.5f, pal.text, false);
        n.setGravity(Gravity.CENTER);
        n.setMaxLines(2);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.topMargin = CigramUI.dp(a, 4);
        col.addView(n, nl);
        return col;
    }
}
