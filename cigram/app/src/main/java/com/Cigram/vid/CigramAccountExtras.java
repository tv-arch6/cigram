package com.Cigram.vid;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Account page (v2.5): the footer group of the page.
 *
 *   ... profile / fonts / my content / watch later ...
 *   [ التحقق من التحديثات ]     <- new, always right above "تواصل معنا"
 *   [ تواصل معنا ]
 *   [ تسجيل الخروج ]            <- only when the page has it
 *
 * Both rows share one style. {@link #arrange(Activity)} is idempotent and is called after the
 * account page is built and again whenever the "watch later" section is re-rendered, so the
 * footer always stays at the bottom.
 */
public final class CigramAccountExtras {

    private static final String TAG_UPDATE = "cigram_update_row";
    private static final String TAG_CONTACT = "cigram_contact_us";
    private static final String TAG_PLUS_CODE = "cigram_plus_code";
    private static final String TAG_WIDGET = "cigram_widget_row";

    private CigramAccountExtras() { }

    private static View byId(Activity a, String name) {
        int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
        return id == 0 ? null : a.findViewById(id);
    }

    public static void arrange(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            View hostView = byId(activity, "linear_account_content");
            if (!(hostView instanceof LinearLayout)) return;
            LinearLayout host = (LinearLayout) hostView;

            View contact = host.findViewWithTag(TAG_CONTACT);
            View update = host.findViewWithTag(TAG_UPDATE);
            if (update == null) update = buildUpdateRow(activity);

            // Cigram Plus (logged-in accounts only): the small comparison box + the "ضع كود التفعيل" row, last section above logout
            View plusBox = host.findViewWithTag(CigramPlusUI.TAG_BOX);
            View plusCode = host.findViewWithTag(TAG_PLUS_CODE);
            if (plusBox != null && plusBox.getParent() instanceof ViewGroup) ((ViewGroup) plusBox.getParent()).removeView(plusBox);
            if (plusCode != null && plusCode.getParent() instanceof ViewGroup) ((ViewGroup) plusCode.getParent()).removeView(plusCode);
            // Play build: the box is only worth showing to someone who already has Plus (no selling there)
            final boolean plusShown = CigramUserData.isLoggedIn(activity);
            final boolean plusBoxShown = plusShown && (CigramPlus.salesEnabled() || CigramPlus.isPlus(activity));
            if (plusShown) {
                if (plusBox == null && plusBoxShown) plusBox = CigramPlusUI.buildBox(activity);
                if (plusCode == null) plusCode = buildPlusCodeRow(activity);
                styleRow(activity, plusCode);
            }

            // same look for both rows
            styleRow(activity, update);
            if (contact != null) styleRow(activity, contact);

            if (update.getParent() instanceof ViewGroup) ((ViewGroup) update.getParent()).removeView(update);
            if (contact != null && contact.getParent() instanceof ViewGroup) {
                ((ViewGroup) contact.getParent()).removeView(contact);
            }

            int at = host.getChildCount();
            View logout = byId(activity, "button_logout");
            if (logout != null) {
                View cur = logout;
                while (cur != null && cur.getParent() != host) {
                    cur = cur.getParent() instanceof View ? (View) cur.getParent() : null;
                }
                if (cur != null) at = host.indexOfChild(cur);
            }
            if (at < 0 || at > host.getChildCount()) at = host.getChildCount();

            host.addView(update, at, rowParams(activity));
            if (contact != null) host.addView(contact, at + 1, rowParams(activity));
            if (plusShown) {
                int next = at + (contact != null ? 2 : 1);
                LinearLayout.LayoutParams bp = rowParams(activity);
                bp.height = -2;
                bp.topMargin = CigramUI.dp(activity, 10);
                if (plusBoxShown && plusBox != null) {
                    host.addView(plusBox, next, bp);
                    next++;
                }
                host.addView(plusCode, next, rowParams(activity));
                // server answer changes the plan -> redraw only the box
                final LinearLayout hostRef = host;
                CigramPlus.refresh(activity, false, new Runnable() {
                    @Override public void run() {
                        if (!activity.isFinishing()) CigramPlusUI.refreshBox(activity, hostRef);
                    }
                });
            }

            // "add the Cigram widget to your home screen": optional, so a problem here can never remove the rows above
            try {
                View widgetRow = host.findViewWithTag(TAG_WIDGET);
                if (widgetRow != null && widgetRow.getParent() instanceof ViewGroup) ((ViewGroup) widgetRow.getParent()).removeView(widgetRow);
                if (widgetRow == null) widgetRow = buildWidgetRow(activity);
                styleRow(activity, widgetRow);
                int where = host.indexOfChild(update);
                host.addView(widgetRow, where < 0 ? host.getChildCount() : where, rowParams(activity));
            } catch (Throwable widgetError) {
                android.util.Log.w("CigramAccount", "widget row failed", widgetError);
            }

            // App icon section (logged-in accounts): after the Plus rows, optional so it can never remove the rows above
            try {
                if (CigramUserData.isLoggedIn(activity)) {
                    int pos = host.indexOfChild(update);
                    CigramAppIconUI.install(activity, host, pos < 0 ? host.getChildCount() : pos);
                    CigramPlus.refresh(activity, false, new Runnable() {
                        @Override public void run() {
                            if (activity.isFinishing()) return;
                            try { CigramAppIconUI.install(activity, (LinearLayout) host, host.indexOfChild(host.findViewWithTag(CigramAppIconUI.TAG))); } catch (Throwable ignored) { }
                        }
                    });
                }
            } catch (Throwable iconError) {
                android.util.Log.w("CigramAccount", "app icon section failed", iconError);
            }

            // v3.6: تصميم موحّد لكل عناصر الحساب + عنصر «أعلن هنا» (لا يغيّر وظيفة أي عنصر)
            CigramAccountUI.polish(activity);

            // the external link (app download / Telegram channel) controlled from the admin app: LAST element of the page, centred
            CigramExternalLink.install(activity, host);
        } catch (Throwable error) {
            android.util.Log.w("CigramAccount", "arrange failed", error);
        }
    }

    private static LinearLayout.LayoutParams rowParams(Activity a) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, CigramUI.dp(a, 62));
        lp.setMargins(CigramUI.dp(a, 14), 0, CigramUI.dp(a, 14), 0);   // CIGRAM ID: flat rows of one group
        return lp;
    }

    private static void styleRow(Activity a, View row) {
        row.setBackground(CigramAccountUI.rowSurface(a));
        row.setElevation(0f);
    }

    /** "ضع كود التفعيل": opens the Plus page with the keyboard on the code field. */
    private static View buildPlusCodeRow(final Activity a) {
        LinearLayout row = new LinearLayout(a);
        row.setTag(TAG_PLUS_CODE);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(CigramUI.dp(a, 10), 0, CigramUI.dp(a, 14), 0);
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription("ضع كود التفعيل");

        ImageView arrow = new ImageView(a);
        int res = a.getResources().getIdentifier("hsi", "drawable", a.getPackageName());
        if (res != 0) arrow.setImageResource(res);
        arrow.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        arrow.setAlpha(0.82f);
        row.addView(arrow, new LinearLayout.LayoutParams(CigramUI.dp(a, 30), CigramUI.dp(a, 30)));

        TextView badge = CigramUI.label(a, "Plus", 12f, CigramPlusUI.GOLD_INK, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(CigramUI.dp(a, 10), CigramUI.dp(a, 3), CigramUI.dp(a, 10), CigramUI.dp(a, 3));
        badge.setBackground(CigramPlusUI.goldBg(a, 10));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.leftMargin = CigramUI.dp(a, 8);
        bl.rightMargin = CigramUI.dp(a, 8);
        row.addView(badge, bl);

        TextView label = CigramUI.label(a, "ضع كود التفعيل", 18f, CigramUI.TEXT, false);
        label.setTypeface(CigramUI.font(a), Typeface.NORMAL);
        label.setPadding(CigramUI.dp(a, 8), 0, CigramUI.dp(a, 8), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, -1, 1f));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { CigramPlusUI.openPage(a, "code"); }
        });
        return row;
    }

    /** "أضف ويدجت سيكرام لشاشتك": the moving carousel on the phone's home screen; a tap on a slide opens that title. */
    private static View buildWidgetRow(final Activity a) {
        LinearLayout row = new LinearLayout(a);
        row.setTag(TAG_WIDGET);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(CigramUI.dp(a, 10), 0, CigramUI.dp(a, 14), 0);
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription("أضف ويدجت سيكرام لشاشتك الرئيسية");

        ImageView arrow = new ImageView(a);
        int res = a.getResources().getIdentifier("hsi", "drawable", a.getPackageName());
        if (res != 0) arrow.setImageResource(res);
        arrow.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        arrow.setAlpha(0.82f);
        row.addView(arrow, new LinearLayout.LayoutParams(CigramUI.dp(a, 30), CigramUI.dp(a, 30)));

        TextView label = CigramUI.label(a, "أضف ويدجت سيكرام لشاشتك", 18f, CigramUI.TEXT, false);
        label.setTypeface(CigramUI.font(a), Typeface.NORMAL);
        label.setPadding(CigramUI.dp(a, 8), 0, CigramUI.dp(a, 8), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, -1, 1f));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { CigramCarouselWidget.requestPin(a); }
        });
        return row;
    }

    private static View buildUpdateRow(final Activity a) {
        LinearLayout row = new LinearLayout(a);
        row.setTag(TAG_UPDATE);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(CigramUI.dp(a, 10), 0, CigramUI.dp(a, 14), 0);
        row.setClickable(true);
        row.setFocusable(true);

        ImageView arrow = new ImageView(a);
        int res = a.getResources().getIdentifier("hsi", "drawable", a.getPackageName());
        if (res != 0) arrow.setImageResource(res);
        arrow.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        arrow.setAlpha(0.82f);
        row.addView(arrow, new LinearLayout.LayoutParams(CigramUI.dp(a, 30), CigramUI.dp(a, 30)));

        String name = CigramUpdateChecker.installedName(a);
        TextView version = CigramUI.label(a, name.length() == 0 ? "" : "الإصدار " + name, 12f,
                CigramUI.MUTED, false);
        version.setGravity(Gravity.CENTER);
        version.setPadding(CigramUI.dp(a, 8), 0, CigramUI.dp(a, 8), 0);
        row.addView(version, new LinearLayout.LayoutParams(-2, -1));

        TextView label = CigramUI.label(a, "التحقق من التحديثات", 18f, CigramUI.TEXT, false);
        label.setTypeface(CigramUI.font(a), Typeface.NORMAL);
        label.setPadding(CigramUI.dp(a, 8), 0, CigramUI.dp(a, 8), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, -1, 1f));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CigramUpdateChecker.manualCheck(a);
            }
        });
        return row;
    }
}
