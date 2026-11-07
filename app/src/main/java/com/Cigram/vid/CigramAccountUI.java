package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.WeakHashMap;

/**
 * توحيد تصميم قائمة قسم «الحساب» (v3.6) وإضافة «أعلن هنا» كآخر عنصر.
 *
 * القاعدة الذهبية: لا نغيّر وظيفة أي عنصر. نعيد تنسيق الصف نفسه (View ذاته) فتبقى
 * كل أحداث الضغط وحالات الظهور/الإخفاء التي يضبطها Logic كما هي، ونُعيد فقط بناء محتواه الداخلي:
 *   [ أيقونة Outline داخل مربع ملوّن ]  [ عنوان + سطر وصف ]  [ شارة اختيارية ]  [ سهم RTL ]
 *
 * يُستدعى من {@link CigramAccountExtras#arrange(Activity)} (تعمل بعد كل رسم لصفحة الحساب)، وهو آمن للتكرار.
 */
public final class CigramAccountUI {

    private static final String TAG_ADS = "cigram_ads_row";
    private static final String TAG_UPDATE = "cigram_update_row";
    private static final String TAG_CONTACT = "cigram_contact_us";
    private static final String PREFS = "cigram_ads_v1";
    private static final String KEY_BADGE_SEEN = "ads_row_badge_seen";

    /** الصفوف التي أُعيد بناؤها سابقاً (حتى لا نكررها). */
    private static final WeakHashMap<View, Boolean> DONE = new WeakHashMap<View, Boolean>();

    /** Solid black surface of every account row, with a hairline edge. */
    private static final int ROW_BG = 0xFF000000;
    private static final int ROW_STROKE = 0x1FFFFFFF;
    /**
     * v5.29: account rows use navy #0B2936. The surface is dark, so text, icons and
     * arrows are white (black would be unreadable) and a light hairline separates the rows.
     */
    private static final int ROW_SURFACE = 0xFF0B2936;
    private static final int ROW_LINE = 0x24FFFFFF;
    private static final int ROW_INK = 0xFFFFFFFF;       // inside a coloured tile (always white)
    private static final int ROW_INK_SOFT = 0xB3FFFFFF;
    private static int ink(Context c) { return CigramTheme.isLight(c) ? 0xFF111418 : 0xFFFFFFFF; }
    private static int inkSoft(Context c) { return CigramTheme.isLight(c) ? 0xFF5F6B73 : 0xB3FFFFFF; }

    /**
     * v5.29: every account row spans the full screen width — no side margins, no rounded
     * corners, no gap between rows; a hairline at the bottom separates stacked rows.
     */
    static android.graphics.drawable.Drawable rowSurface(Context c) {
        // v10.67: a raised 3D block (side, face, gloss, rim) that sinks when pressed
        return CigramGlass.ripple(c, CigramDepth.card(c), 18);
    }

    /** Guest "تسجيل الدخول" button: white pill with black text (overrides the glass skin). */
    private static void styleGuestLogin(Activity a) {
        View v = byId(a, "button_open_login");
        if (!(v instanceof TextView)) return;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(CigramUI.dp(a, 26));
        v.setBackgroundTintList(null);
        v.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22000000), bg, null));
        TextView t = (TextView) v;
        t.setTextColor(0xFF000000);
        t.setTypeface(CigramUI.font(a), Typeface.BOLD);
    }

    private CigramAccountUI() { }

    private static View byId(Activity a, String name) {
        int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
        return id == 0 ? null : a.findViewById(id);
    }

    public static void polish(final Activity a) {
        if (a == null || a.isFinishing()) return;
        try {
            View hostView = byId(a, "linear_account_content");
            if (!(hostView instanceof LinearLayout)) return;
            LinearLayout host = (LinearLayout) hostView;

            restyleExisting(a, byId(a, "linear_account_settings"), AdsIcons.GEAR, CigramUI.TEAL, "تخصيص التطبيق والإشعارات");
            restyleExisting(a, byId(a, "linear_account_subtitle_font"), AdsIcons.FONT, CigramUI.AMBER, "شكل وحجم الخط");
            restyleExisting(a, byId(a, "linear_my_content"), AdsIcons.FOLDER, CigramUI.GREEN, "ما أضفته إلى مكتبتك");
            restyleExisting(a, byId(a, "linear_help_support"), AdsIcons.HELP, 0xFF8E7CFF, "مساعدة وأسئلة عن التطبيق");
            restyleExisting(a, host.findViewWithTag(TAG_UPDATE), AdsIcons.REFRESH, CigramUI.TEAL, "");
            restyleExisting(a, host.findViewWithTag(TAG_CONTACT), AdsIcons.HEADSET, 0xFF4DA3FF, "راسل فريق الدعم");
            removeAdsRow(host); // v5.29: «أعلن هنا» is no longer shown anywhere in the app
            CigramAccountDesign.guest(a);   // v10.71: the guest hero + the brand action button
            CigramAuthPolish.apply(a); // v10.29: صفحة الدخول/إنشاء الحساب (التصميم «أ»)
            CigramAccountHeader.install(a);
            arrangeGroups(a, host);
        } catch (Throwable error) {
            android.util.Log.w("CigramAccountUI", "polish failed", error);
        }
        installStats(a);
    }

    /**
     * v5.29 section groups:
     *   «الحساب»  الإعدادات · خطوطي · محتوياتي
     *   (المشاهدة لاحقاً)
     *   «الدعم»   المساعدة والدعم · التحقق من التحديثات · تواصل معنا
     */
    private static void arrangeGroups(Activity a, LinearLayout host) {
        View help = byId(a, "linear_help_support");
        View update = host.findViewWithTag(TAG_UPDATE);
        if (help != null && update != null && help.getParent() == host && update.getParent() == host) {
            host.removeView(help);
            host.addView(help, host.indexOfChild(update));
        }
        CigramAccountHeader.titleAbove(a, byId(a, "linear_account_settings"), "الحساب", "account");
        View supportFirst = help != null && help.getVisibility() == View.VISIBLE ? help : update;
        CigramAccountHeader.titleAbove(a, supportFirst, "الدعم", "support");
    }

    // ------------------------------------------------------------------ v5.1: لوحة الأرقام (Dashboard)

    private static final String TAG_STATS = "cigram_stats_strip";

    /** Three stat boxes right under the profile info card: watched / watch later / favorites. */
    private static void installStats(Activity a) {
        try {
            View info = byId(a, "linear_profile_info");
            if (info == null || !(info.getParent() instanceof LinearLayout)) return;
            LinearLayout parent = (LinearLayout) info.getParent();
            if (parent.findViewWithTag(TAG_STATS) != null) return;
            ViewGroup.LayoutParams ilp = info.getLayoutParams();
            if (ilp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) ilp).bottomMargin = CigramUI.dp(a, 8);
                info.setLayoutParams(ilp);
            }
            StatsStrip strip = new StatsStrip(a);
            strip.setTag(TAG_STATS);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(CigramUI.dp(a, 14), 0, CigramUI.dp(a, 14), CigramUI.dp(a, 4));
            parent.addView(strip, parent.indexOfChild(info) + 1, lp);
        } catch (Throwable error) {
            android.util.Log.w("CigramAccountUI", "installStats failed", error);
        }
    }

    public static final class StatsStrip extends LinearLayout {
        private final Activity act;
        private final TextView[] values = new TextView[3];
        private final java.util.concurrent.atomic.AtomicBoolean busy =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        StatsStrip(Activity a) {
            super(a);
            act = a;
            setOrientation(HORIZONTAL);
            // v5.29: one segmented strip. Physical order left→right: [المفضلة] [لاحقاً] [شاهدت],
            // so «شاهدت» is read first (right). values[] keeps the {seen, later, favorites} order.
            setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            final LinearLayout[] boxes = new LinearLayout[3];
            // v10.67: three separate raised tiles, each with its own accent glow on the face
            final int[] accents = {CigramUI.TEAL, CigramUI.AMBER, CigramUI.RED};
            final String[] labels = {"شاهدت", "المشاهدة لاحقاً", "المفضلة"};
            View.OnClickListener open = new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        View row = byId(act, "linear_my_content");
                        if (row != null && row.getVisibility() == View.VISIBLE) row.performClick();
                    } catch (Throwable ignored) { }
                }
            };
            for (int i = 0; i < 3; i++) {
                LinearLayout box = new LinearLayout(a);
                box.setOrientation(VERTICAL);
                box.setGravity(Gravity.CENTER);
                box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                box.setBackground(CigramGlass.ripple(a, CigramDepth.raised(a,
                        CigramDepth.mix(0xFF174660, accents[i], 0.30f), CigramDepth.mix(0xFF0B2936, accents[i], 0.10f),
                        16f, 4f), 16));
                box.setPadding(0, 0, 0, CigramUI.dp(a, 4));
                box.setClipToOutline(true);
                box.setElevation(CigramUI.dp(a, 2));
                CigramDepth.attachPress(box);
                box.setClickable(true);
                box.setFocusable(true);
                box.setContentDescription(labels[i]);
                box.setOnClickListener(open);

                TextView v = CigramUI.label(a, "—", 22f, 0xFFFFFFFF, false);
                v.setTypeface(CigramUI.font(a), Typeface.BOLD);
                v.setGravity(Gravity.CENTER);
                v.setSingleLine(true);
                v.setIncludeFontPadding(false);
                values[i] = v;
                box.addView(v, new LinearLayout.LayoutParams(-2, -2));

                TextView t = CigramUI.label(a, labels[i], 12f, 0xB3FFFFFF, false);
                t.setGravity(Gravity.CENTER);
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t.setIncludeFontPadding(false);
                t.setPadding(CigramUI.dp(a, 4), 0, CigramUI.dp(a, 4), 0);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
                tl.topMargin = CigramUI.dp(a, 4);
                box.addView(t, tl);
                boxes[i] = box;
            }
            setClipChildren(false);
            for (int i = 2; i >= 0; i--) {
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, CigramUI.dp(a, 80), 1f);
                if (i > 0) bl.rightMargin = CigramUI.dp(a, 10);   // physical gap between the tiles
                addView(boxes[i], bl);
            }
        }

        void refresh() {
            if (act == null || act.isFinishing()) return;
            try { if (!CigramUserData.isLoggedIn(act)) return; } catch (Throwable ignored) { return; }
            if (!busy.compareAndSet(false, true)) return;
            new Thread(new Runnable() {
                @Override public void run() {
                    int seen = -1, later = -1, fav = -1;
                    try { seen = CigramUserData.historyGrouped(act).size(); } catch (Throwable ignored) { }
                    try { later = CigramStage1InitProvider.cigramListRead(act, "watch_later").length(); } catch (Throwable ignored) { }
                    try { fav = CigramStage1InitProvider.cigramListRead(act, "favorites").length(); } catch (Throwable ignored) { }
                    final int[] r = {seen, later, fav};
                    try {
                        act.runOnUiThread(new Runnable() {
                            @Override public void run() {
                                for (int i = 0; i < 3; i++) {
                                    values[i].setText(r[i] < 0 ? "—" : (r[i] > 999 ? "999+" : String.valueOf(r[i])));
                                }
                            }
                        });
                    } finally {
                        busy.set(false);
                    }
                }
            }, "cigram-account-stats").start();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            refresh();
        }

        @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            if (hasWindowFocus) refresh();
        }
    }

    // ------------------------------------------------------------------ صف موجود

    private static void restyleExisting(Activity a, View v, int icon, int accent, String desc) {
        if (!(v instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) v;
        if (DONE.containsKey(v)) {
            // arrange() قد يعيد تنسيق الخلفية والأبعاد؛ نعيد تطبيق شكلنا فقط دون إعادة بناء المحتوى
            applyRowParams(a, row);
            row.setBackground(rowSurface(a));
            row.setClipToOutline(true);
            row.setElevation(CigramUI.dp(a, 2));
            return;
        }
        String title = null;
        String sub = desc;
        // العنوان = أول/آخر TextView حسب نوع الصف
        TextView first = null;
        TextView last = null;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c instanceof TextView && !(c instanceof android.widget.EditText)) {
                if (first == null) first = (TextView) c;
                last = (TextView) c;
            }
        }
        if (first == null) return;
        if (row.getTag() != null && TAG_UPDATE.equals(row.getTag())) {
            // [ سهم ][ إصدار ][ تسمية ] : العنوان هو الأخير والإصدار هو السطر الوصفي
            title = String.valueOf(last.getText());
            sub = first == last ? "" : String.valueOf(first.getText());
        } else {
            title = String.valueOf(first.getText());
        }
        if (title == null || title.trim().length() == 0) return;
        rebuild(a, row, title.trim(), sub, icon, accent, false);
        DONE.put(row, Boolean.TRUE);
    }

    // ------------------------------------------------------------------ أعلن هنا

    private static void removeAdsRow(LinearLayout host) {
        View row = host.findViewWithTag(TAG_ADS);
        if (row != null && row.getParent() instanceof ViewGroup) ((ViewGroup) row.getParent()).removeView(row);
    }

    private static void installAdsRow(final Activity a, LinearLayout host) {
        View existing = host.findViewWithTag(TAG_ADS);
        LinearLayout row;
        if (existing instanceof LinearLayout) {
            row = (LinearLayout) existing;
        } else {
            row = new LinearLayout(a);
            row.setTag(TAG_ADS);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BADGE_SEEN, true).apply();
                        a.startActivity(new Intent(a, AdvertiseActivity.class));
                    } catch (Throwable error) {
                        android.util.Log.w("CigramAccountUI", "open ads page failed", error);
                    }
                }
            });
            boolean seen = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BADGE_SEEN, false);
            rebuild(a, row, "أعلن هنا", "اعرض إعلانك أمام جمهور التطبيق", AdsIcons.MEGAPHONE, CigramUI.RED, !seen);
        }
        // آخر عنصر في القائمة: بعد «تواصل معنا» (أو بعد آخر صف) وقبل زر تسجيل الخروج
        if (row.getParent() instanceof ViewGroup) ((ViewGroup) row.getParent()).removeView(row);
        int at = host.getChildCount();
        View logout = byId(a, "button_logout");
        if (logout != null) {
            View cur = logout;
            while (cur != null && cur.getParent() != host) {
                cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
            }
            if (cur != null) at = host.indexOfChild(cur);
        }
        if (at < 0 || at > host.getChildCount()) at = host.getChildCount();
        host.addView(row, at, new LinearLayout.LayoutParams(-1, CigramUI.dp(a, 64)));
        applyRowParams(a, row);
    }

    // ------------------------------------------------------------------ بناء الصف

    private static void applyRowParams(Context c, LinearLayout row) {
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp == null) lp = new LinearLayout.LayoutParams(-1, CigramUI.dp(c, 64));
        lp.height = CigramUI.dp(c, 68);
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) lp).setMargins(CigramUI.dp(c, 14), CigramUI.dp(c, 4),
                    CigramUI.dp(c, 14), CigramUI.dp(c, 5));
        }
        row.setLayoutParams(lp);
    }

    /** أعمق عنصر داخلي يحمل حدث ضغط (بعض الصفوف يكون الحدث على TextView داخلي لا على الصف نفسه). */
    private static View findClickableDescendant(ViewGroup group) {
        if (group == null) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                View deeper = findClickableDescendant((ViewGroup) child);
                if (deeper != null) return deeper;
            }
            try {
                if (child.hasOnClickListeners()) return child;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static void rebuild(Context c, LinearLayout row, String title, String sub, int icon, int accent, boolean badge) {
        // نلتقط حدث الضغط الموجود على أي عنصر داخلي قبل حذف المحتوى، حتى لا تتعطّل الصفوف
        // التي يكون حدثها على TextView داخلي (مثل «المساعدة والدعم» الذي حدثه على النص «ads»).
        final View clickDelegate = row.hasOnClickListeners() ? null : findClickableDescendant(row);
        row.removeAllViews();
        row.setOrientation(LinearLayout.HORIZONTAL);
        // v4.4: explicit LTR + physical order [chevron][badge][texts][icon] → icon on the right,
        // chevron on the left, in every RTL setting (the app has no supportsRtl).
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(CigramUI.dp(c, 14), 0, CigramUI.dp(c, 14), CigramUI.dp(c, 4));   // 4dp = block thickness
        row.setMinimumHeight(CigramUI.dp(c, 68));
        applyRowParams(c, row);
        // the whole row (title, subtitle, arrow, icon) sits on ONE black surface; everything on it is white
        row.setBackground(rowSurface(c));
        row.setClipToOutline(true);
        row.setElevation(CigramUI.dp(c, 2));
        CigramDepth.attachPress(row);
        row.setContentDescription(sub != null && sub.length() > 0 ? title + "، " + sub : title);

        // سهم يتجه لليسار (اتجاه التقدّم في العربية) — physically the leftmost element
        row.addView(new AdsIcons.Icon(c, AdsIcons.CHEVRON_LEFT, ink(c)), new LinearLayout.LayoutParams(CigramUI.dp(c, 20), CigramUI.dp(c, 20)));

        if (badge) {
            TextView b = CigramUI.label(c, "جديد", 11f, CigramUI.TEXT, true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(CigramUI.dp(c, 9), CigramUI.dp(c, 3), CigramUI.dp(c, 9), CigramUI.dp(c, 3));
            b.setBackground(CigramUI.round(CigramUI.RED, 10, c));
            b.setContentDescription("جديد");
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
            bl.leftMargin = CigramUI.dp(c, 8);
            row.addView(b, bl);
        }

        // عنوان + وصف
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = CigramUI.label(c, title, 16.5f, ink(c), false);
        t.setTypeface(CigramUI.font(c), Typeface.NORMAL);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        if (sub != null && sub.length() > 0) {
            TextView s = CigramUI.label(c, sub, 12f, inkSoft(c), false);
            s.setSingleLine(true);
            s.setIncludeFontPadding(false);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
            sl.topMargin = CigramUI.dp(c, 3);
            col.addView(s, sl);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = CigramUI.dp(c, 8);
        cl.rightMargin = CigramUI.dp(c, 12);
        row.addView(col, cl);

        // v10.67: the icon sits on its own raised "jewel" in the accent colour — physically the rightmost element
        FrameLayout box = new FrameLayout(c);
        box.setBackground(CigramDepth.jewel(c, accent));
        box.setPadding(0, 0, 0, CigramUI.dp(c, 2));
        box.addView(new AdsIcons.Icon(c, icon, ROW_INK), new FrameLayout.LayoutParams(CigramUI.dp(c, 22), CigramUI.dp(c, 22), Gravity.CENTER));
        row.addView(box, new LinearLayout.LayoutParams(CigramUI.dp(c, 46), CigramUI.dp(c, 46)));

        // إعادة ربط حدث الضغط للصف إذا كان الحدث على عنصر داخلي حُذف للتو (إصلاح «المساعدة والدعم»).
        if (clickDelegate != null) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { clickDelegate.performClick(); } catch (Throwable ignored) { }
                }
            });
        }
    }
}
