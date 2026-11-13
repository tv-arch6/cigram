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

    private static int ink(Context c) { return CigramPalette.of(c).text; }
    private static int inkSoft(Context c) { return CigramPalette.of(c).text3; }

    /**
     * v5.29: every account row spans the full screen width — no side margins, no rounded
     * corners, no gap between rows; a hairline at the bottom separates stacked rows.
     */
    static android.graphics.drawable.Drawable rowSurface(Context c) {
        // CIGRAM ID: a flat row; CigramAccountDash gives it its real shape (first / middle / last of a group)
        return CigramAccountDash.rowBackground(c, CigramPalette.of(c), 0);
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

            // 2026 CIGRAM ID entries: the same views (ids + listeners), with the requested names and one icon set
            restyleRowAs(a, byId(a, "linear_account_settings"), "الإعدادات", "التطبيق والإشعارات وأيقونة التطبيق", AdsIcons.GEAR, 0);
            restyleExisting(a, byId(a, "linear_account_subtitle_font"), AdsIcons.FONT, 0, "شكل وحجم الخط");
            restyleRowAs(a, byId(a, "linear_my_content"), "قائمة المشاهدة", "المفضلة والمشاهدة لاحقاً وما شاهدته", AdsIcons.BOOKMARK, 0);
            restyleExisting(a, host.findViewWithTag(TAG_UPDATE), AdsIcons.REFRESH, 0, "");
            restyleRowAs(a, host.findViewWithTag(TAG_CONTACT), "أرسل طلبك أو مشكلتك", "اطلب فيلماً أو مسلسلاً أو قناة، أو بلّغ عن مشكلة", AdsIcons.SEND, 0);
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
     *   «الدعم»   الدعم والتواصل · أرسل طلبك أو مشكلتك · التحقق من التحديثات
     */
    private static void arrangeGroups(Activity a, LinearLayout host) {
        CigramAccountDash.arrange(a, host);
    }

    /** Same face as the other rows for a row that was built elsewhere (widget); its click listener stays on the row. */
    static void restyleRow(Activity a, View row, int icon, int accent, String desc) {
        restyleExisting(a, row, icon, accent, desc);
    }

    /** Gives a row a new face with an explicit title / subtitle (the row keeps its own click listener). */
    static void restyleRowAs(Activity a, View v, String title, String sub, int icon, int accent) {
        if (!(v instanceof LinearLayout) || DONE.containsKey(v)) return;
        rebuild(a, (LinearLayout) v, title, sub, icon, accent, false);
        DONE.put(v, Boolean.TRUE);
    }

    /** A custom box (app icons, Plus) sits on the page with the same side margins as the rows. */
    static void sideMargins(Context c, View v) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            m.setMargins(CigramUI.dp(c, 14), CigramUI.dp(c, 12), CigramUI.dp(c, 14), CigramUI.dp(c, 4));
            v.setLayoutParams(m);
        }
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
            CigramPalette pal = CigramPalette.of(a);
            setOrientation(HORIZONTAL);
            // CIGRAM ID: ONE flat surface with three equal columns and hairlines between them.
            // Physical order left→right: [المفضلة] [لاحقاً] [شاهدت], so «شاهدت» is read first (right).
            // values[] keeps the {seen, later, favorites} order.
            setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            setBackground(CigramPalette.round(a, pal.surface, pal.stroke, 16));
            setPadding(0, CigramUI.dp(a, 12), 0, CigramUI.dp(a, 12));
            final String[] labels = {"شاهدت", "المشاهدة لاحقاً", "المفضلة"};
            View.OnClickListener open = new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        View row = byId(act, "linear_my_content");
                        if (row != null && row.getVisibility() == View.VISIBLE) row.performClick();
                    } catch (Throwable ignored) { }
                }
            };
            for (int slot = 0; slot < 3; slot++) {
                final int i = 2 - slot;                       // physical slot 0 (left) shows favourites
                if (slot > 0) {
                    View line = new View(a);
                    line.setBackgroundColor(pal.hairline);
                    LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(Math.max(1, CigramUI.dp(a, 1)), CigramUI.dp(a, 30));
                    ll.gravity = Gravity.CENTER_VERTICAL;
                    addView(line, ll);
                }
                LinearLayout box = new LinearLayout(a);
                box.setOrientation(VERTICAL);
                box.setGravity(Gravity.CENTER);
                box.setClickable(true);
                box.setFocusable(true);
                box.setContentDescription(labels[i]);
                box.setOnClickListener(open);
                box.setBackground(new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(pal.ripple), null,
                        CigramPalette.round(a, 0xFFFFFFFF, 0, 12)));

                TextView v = CigramUI.label(a, "—", 21f, pal.text, true);
                v.setGravity(Gravity.CENTER);
                v.setSingleLine(true);
                v.setIncludeFontPadding(false);
                values[i] = v;
                box.addView(v, new LinearLayout.LayoutParams(-2, -2));

                TextView t = CigramUI.label(a, labels[i], 11.5f, pal.text3, false);
                t.setGravity(Gravity.CENTER);
                t.setSingleLine(true);
                t.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t.setIncludeFontPadding(false);
                t.setPadding(CigramUI.dp(a, 4), 0, CigramUI.dp(a, 4), 0);
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
                tl.topMargin = CigramUI.dp(a, 5);
                box.addView(t, tl);
                addView(box, new LinearLayout.LayoutParams(0, -2, 1f));
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
            row.setElevation(0f);
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

    // 18.2: the «أعلن هنا» row installer was removed with the old advertise page (the row was already hidden
    // since v5.29; removeAdsRow above still cleans it from screens built by older versions).


    // ------------------------------------------------------------------ بناء الصف

    private static void applyRowParams(Context c, LinearLayout row) {
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp == null) lp = new LinearLayout.LayoutParams(-1, CigramUI.dp(c, 62));
        lp.height = CigramUI.dp(c, 62);
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) lp).setMargins(CigramUI.dp(c, 14), 0, CigramUI.dp(c, 14), 0);
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
        fillRow(c, row, title, sub, icon, accent, badge);
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

    /**
     * CIGRAM ID row: [ chevron ] [ badge ] [ title / subtitle ] [ icon tile ] — a flat 62 dp line, no shadow.
     * Physical order is fixed (LTR container) so the icon is always on the right and the chevron on the left, as the
     * app has no layout-direction support. The subtitle view is kept in a tag so a row can change it later.
     */
    static void fillRow(Context c, LinearLayout row, String title, String sub, int icon, int accent, boolean badge) {
        CigramPalette pal = CigramPalette.of(c);
        row.removeAllViews();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(CigramUI.dp(c, 14), 0, CigramUI.dp(c, 14), 0);
        row.setMinimumHeight(CigramUI.dp(c, 62));
        applyRowParams(c, row);
        row.setBackground(rowSurface(c));
        row.setElevation(0f);
        row.setContentDescription(sub != null && sub.length() > 0 ? title + "، " + sub : title);

        row.addView(new AdsIcons.Icon(c, AdsIcons.CHEVRON_LEFT, pal.text3),
                new LinearLayout.LayoutParams(CigramUI.dp(c, 18), CigramUI.dp(c, 18)));

        if (badge) {
            TextView b = CigramUI.label(c, "جديد", 11f, pal.onBrand, true);
            b.setGravity(Gravity.CENTER);
            b.setPadding(CigramUI.dp(c, 9), CigramUI.dp(c, 3), CigramUI.dp(c, 9), CigramUI.dp(c, 3));
            b.setBackground(CigramPalette.round(c, pal.brand, 0, 10));
            b.setContentDescription("جديد");
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
            bl.leftMargin = CigramUI.dp(c, 8);
            row.addView(b, bl);
        }

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        TextView t = CigramUI.label(c, title, 15.5f, pal.text, false);
        t.setTypeface(CigramUI.font(c), Typeface.NORMAL);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView s = CigramUI.label(c, sub == null ? "" : sub, 12f, pal.text3, false);
        s.setSingleLine(true);
        s.setIncludeFontPadding(false);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = CigramUI.dp(c, 3);
        col.addView(s, sl);
        s.setVisibility(sub != null && sub.length() > 0 ? View.VISIBLE : View.GONE);
        row.setTag(R.id.cg_row_subtitle, s);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = CigramUI.dp(c, 8);
        cl.rightMargin = CigramUI.dp(c, 12);
        row.addView(col, cl);

        // 2026: one monochrome line icon per row (white on the dark page, ink on the light one), no coloured tile
        FrameLayout box = new FrameLayout(c);
        box.addView(new AdsIcons.Icon(c, icon, pal.text), new FrameLayout.LayoutParams(CigramUI.dp(c, 22), CigramUI.dp(c, 22), Gravity.CENTER));
        row.addView(box, new LinearLayout.LayoutParams(CigramUI.dp(c, 30), CigramUI.dp(c, 30)));
    }
}
