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
 * icons, Plus-only ones covered with blur + PLUS crown for free accounts, and the apply button for Plus accounts.
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
        final boolean plus = CigramPlus.isPlus(a);
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
        TextView sub = CigramUI.label(a, plus ? "اختر شكل Cigram على شاشتك الرئيسية" : "الأيقونات المميزة حصرية لمشتركي Cigram Plus", 12f, pal.text3, false);
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
                    list.addView(tile(a, v, plus, v.id.equals(picked[0]), CigramAppIcon.active(a).equals(v.id), new View.OnClickListener() {
                        @Override public void onClick(View view) {
                            if (!CigramAppIcon.canUse(a, v)) { showLocked(a); return; }
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
                if (!CigramAppIcon.canUse(a, t)) { showLocked(a); return; }
                if (CigramAppIcon.apply(a, t.id)) {
                    CigramPlus.syncAppIcon(a, t.id);
                    Toast.makeText(a, "تم تغيير الأيقونة ✓ (قد يتأخر ظهورها لثوانٍ حسب الشاشة الرئيسية)", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(a, "تعذّر تغيير الأيقونة على هذا الجهاز", Toast.LENGTH_SHORT).show();
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

    private static View tile(final Activity a, CigramAppIcon.Variant v, boolean plus, boolean picked, boolean isActive, View.OnClickListener click) {
        final boolean locked = v.plus && !plus;
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CigramUI.dp(a, 76), -2);
        lp.setMargins(CigramUI.dp(a, 5), 0, CigramUI.dp(a, 5), 0);
        col.setLayoutParams(lp);
        col.setClickable(true);
        col.setOnClickListener(click);
        col.setContentDescription(v.name + (locked ? " — حصرية لمشتركي Plus" : ""));

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
        if (locked) {
            if (Build.VERSION.SDK_INT >= 31) {
                try { iv.setRenderEffect(RenderEffect.createBlurEffect(14f, 14f, Shader.TileMode.CLAMP)); } catch (Throwable ignored) { }
            }
            View shade = new View(a);
            shade.setBackground(box(0xAA06141C, 15, 0, 0, a));
            f.addView(shade, new FrameLayout.LayoutParams(-1, -1));
            TextView badge = CigramUI.label(a, "👑 PLUS", 9.5f, 0xFF2B1A00, true);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(box(GOLD, 8, 0, 0, a));
            badge.setPadding(CigramUI.dp(a, 5), CigramUI.dp(a, 2), CigramUI.dp(a, 5), CigramUI.dp(a, 2));
            f.addView(badge, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        }
        col.addView(f, new LinearLayout.LayoutParams(CigramUI.dp(a, 72), CigramUI.dp(a, 72)));
        TextView n = CigramUI.label(a, v.name, 10.5f, locked ? pal.text3 : pal.text, false);
        n.setGravity(Gravity.CENTER);
        n.setMaxLines(2);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.topMargin = CigramUI.dp(a, 4);
        col.addView(n, nl);
        return col;
    }

    /** Locked icon tapped (or Plus expired): the exclusive-icon popup with the upgrade button. */
    static void showLocked(final Activity a) {
        try {
            final Dialog d = new Dialog(a);
            d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            final CigramPalette pal = CigramPalette.of(a);
            box.setBackground(box(pal.surface, 22, 0x66F5B83D, 1, a));
            int p = CigramUI.dp(a, 20);
            box.setPadding(p, p, p, p);
            TextView crown = CigramUI.label(a, "👑", 34f, 0xFFFFFFFF, false);
            crown.setGravity(Gravity.CENTER);
            box.addView(crown, new LinearLayout.LayoutParams(-2, -2));
            TextView msg = CigramUI.label(a, "هذه الأيقونة حصرية لمشتركي Cigram Plus 👑. اشترك الآن لتخصيص شكل التطبيق على شاشتك الرئيسية!", 14f, 0xFFFFFFFF, false);
            msg.setGravity(Gravity.CENTER);
            msg.setLineSpacing(0f, 1.2f);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
            ml.topMargin = CigramUI.dp(a, 8);
            msg.setTextColor(pal.text);
            box.addView(msg, ml);
            if (CigramPlus.salesEnabled()) {
                TextView go = CigramUI.label(a, "اشترك الآن", 14f, 0xFF1A1200, true);
                go.setGravity(Gravity.CENTER);
                go.setBackground(box(GOLD, 14, 0, 0, a));
                go.setClickable(true);
                go.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { d.dismiss(); CigramPlusUI.openPage(a, "pay"); }
                });
                LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, CigramUI.dp(a, 46));
                gl.topMargin = CigramUI.dp(a, 16);
                box.addView(go, gl);
            }
            TextView later = CigramUI.label(a, "ليس الآن", 12.5f, pal.text3, false);
            later.setGravity(Gravity.CENTER);
            later.setPadding(0, CigramUI.dp(a, 10), 0, 0);
            later.setClickable(true);
            later.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { d.dismiss(); } });
            box.addView(later, new LinearLayout.LayoutParams(-1, -2));
            d.setContentView(box);
            if (d.getWindow() != null) {
                d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                d.getWindow().setLayout(CigramUI.dp(a, 300), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            d.show();
        } catch (Throwable t) {
            Toast.makeText(a, "هذه الأيقونة حصرية لمشتركي Cigram Plus", Toast.LENGTH_SHORT).show();
        }
    }
}
