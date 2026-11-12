package com.Cigram.vid;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * CIGRAM ID — the signed-in Account page as a personal dashboard.
 *
 * The page keeps every view, id and click listener it always had (the page logic is wired to them). This class
 * only decides how they are grouped and drawn:
 *
 *   حسابي              تعديل الملف الشخصي · الإعدادات
 *   تجربتي             محتوياتي · خطوطي · المظهر · ويدجت · (أيقونة التطبيق · كود Plus)
 *   الدعم والمعلومات   المساعدة والدعم · تواصل معنا · التحقق من التحديثات
 *   إجراءات الحساب     تسجيل الخروج
 *
 * Each group is one rounded surface made of flat rows (a row knows whether it is the first, a middle or the last of its
 * group), with a thin border and no shadows. Rows are drawn with the semantic colours of {@link CigramPalette}.
 *
 * New rows are shortcuts into flows that already exist: «تعديل الملف الشخصي» and «تسجيل الخروج» open the Settings
 * page, which runs its own (unchanged) edit / sign-out dialogs; «المظهر» is the System / Light / Dark choice.
 * Everything here is idempotent: {@link #arrange} is called after every redraw of the page.
 */
final class CigramAccountDash {

    private static final String TAG_TITLE = "cigram_dash_title_";
    private static final String TAG_EDIT = "cigram_row_edit_profile";
    private static final String TAG_THEME = "cigram_row_appearance";
    private static final String TAG_LOGOUT = "cigram_row_logout";
    private static final String TAG_UPDATE = "cigram_update_row";
    private static final String TAG_CONTACT = "cigram_contact_us";
    private static final String TAG_WIDGET = "cigram_widget_row";
    private static final String TAG_PLUS_CODE = "cigram_plus_code";
    // 2026 entries
    private static final String TAG_PLUS = "cigram_row_plus";
    private static final String TAG_SWITCH = "cigram_row_switch";
    private static final String TAG_AD_REQUEST = "cigram_row_ad_request";
    private static final String TAG_ABOUT = "cigram_row_about";
    private static final String TAG_TERMS = "cigram_row_terms";
    private static final String TAG_TELEGRAM = "cigram_row_telegram";
    private static final String TAG_PROFILES = "cigram_row_profiles";
    private static final String TAG_CALENDAR = "cigram_row_calendar";
    private static final String TAG_PERF = "cigram_row_performance";

    /** Row positions inside a group. */
    private static final int SINGLE = 0, FIRST = 1, MIDDLE = 2, LAST = 3;

    private CigramAccountDash() { }

    private static View byId(Activity a, String name) {
        int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
        return id == 0 ? null : a.findViewById(id);
    }

    static int dp(Context c, float v) {
        return CigramPalette.dp(c, v);
    }

    // ================================================================== arrangement

    static void arrange(final Activity a, LinearLayout host) {
        if (a == null || a.isFinishing() || host == null) return;
        final CigramPalette pal = CigramPalette.of(a);
        final boolean logged = CigramUserData.isLoggedIn(a);

        // 2026: a restrained four-stage charcoal gradient behind the whole page (light: four soft greys); the
        // content column itself is transparent so the gradient reads from top to bottom
        host.setBackgroundColor(0);
        View scrollView = byId(a, "vscroll_account");
        if (scrollView != null) {
            scrollView.setBackgroundColor(0);
            if (scrollView.getParent() instanceof View) ((View) scrollView.getParent()).setBackground(pageGradient(pal));
        }

        View profile = byId(a, "linear_profile");
        View settings = byId(a, "linear_account_settings");
        View fonts = byId(a, "linear_account_subtitle_font");
        View mine = byId(a, "linear_my_content");
        View help = byId(a, "linear_help_support");
        View update = host.findViewWithTag(TAG_UPDATE);
        View contact = host.findViewWithTag(TAG_CONTACT);
        View widget = host.findViewWithTag(TAG_WIDGET);
        View plusCode = host.findViewWithTag(TAG_PLUS_CODE);
        View appIcon = host.findViewWithTag(CigramAppIconUI.TAG);
        View plusBox = host.findViewWithTag(CigramPlusUI.TAG_BOX);

        // the rows that are not built by the old code: shortcuts into flows that already exist
        View edit = ensureRow(a, host, TAG_EDIT, "الحساب الشخصي", "الاسم والصورة والبريد الإلكتروني",
                AdsIcons.USER, 0, new View.OnClickListener() {
                    @Override public void onClick(View v) { openSettings(a, "edit_profile"); }
                });
        View theme = ensureRow(a, host, TAG_THEME, "المظهر", themeLabel(a), AdsIcons.SUN, 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showThemeDialog(a); }
                });
        View logout = ensureRow(a, host, TAG_LOGOUT, "تسجيل الخروج", "من هذا الجهاز فقط", AdsIcons.LOGOUT, 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { openSettings(a, "logout"); }
                });
        View plus = ensureRow(a, host, TAG_PLUS, "Cigram Plus", plusLabel(a), AdsIcons.CROWN, 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        CigramPlusUI.openPage(a, CigramPlus.salesEnabled() && !CigramPlus.isPlus(a) ? "pay" : "code");
                    }
                });
        View switchRow = ensureRow(a, host, TAG_SWITCH, "تبديل", switchLabel(a), AdsIcons.SWITCH, 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { showSwitchDialog(a); }
                });
        // 18.2: same entry, new experience — Cigram Private Support (the private conversation with the developer)
        View adRequest = ensureRow(a, host, TAG_AD_REQUEST, "أريد إعلاناً", "الدعم الخاص: تواصل مباشرة مع مطوّر Cigram",
                AdsIcons.CHAT, 0, new View.OnClickListener() {
                    @Override public void onClick(View v) { CigramSupport.open(a); }
                });
        View about = ensureRow(a, host, TAG_ABOUT, "حول التطبيق", "ما هو Cigram ومن يقف خلفه", AdsIcons.INFO, 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { openPage(a, CigramAboutActivity.class); }
                });
        View terms = ensureRow(a, host, TAG_TERMS, "الشروط والأحكام", "شروط استخدام Cigram", AdsIcons.DOC, 0,
                new View.OnClickListener() {
                    @Override public void onClick(View v) { openPage(a, CigramTermsActivity.class); }
                });
        // 19.2: Cigram Profiles · Watch Calendar · Performance Engine
        final View profiles = ensureRow(a, host, TAG_PROFILES, "الملفات الشخصية", "تشاهد الآن باسم: " + CigramProfiles.active(a).name,
                AdsIcons.USERS, 0, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        CigramProfiles.showPicker(a, new Runnable() { @Override public void run() { CigramProfiles.restartHome(a); } });
                    }
                });
        View calendar = ensureRow(a, host, TAG_CALENDAR, "تقويم المشاهدة", "ما شاهدته وما أضيف لمسلسلاتك يوماً بيوم",
                AdsIcons.CALENDAR, 0, new View.OnClickListener() {
                    @Override public void onClick(View v) { openPage(a, CigramWatchCalendarActivity.class); }
                });
        final View perf = ensureRow(a, host, TAG_PERF, "محرك الأداء", CigramPerformance.label(a), AdsIcons.BOLT, 0,
                new View.OnClickListener() {
                    @Override public void onClick(final View v) {
                        CigramPerformance.choose(a, new Runnable() { @Override public void run() { setSubtitle(v, CigramPerformance.label(a)); } });
                    }
                });
        setSubtitle(profiles, "تشاهد الآن باسم: " + CigramProfiles.active(a).name);
        setSubtitle(perf, CigramPerformance.label(a));
        profiles.setVisibility(logged ? View.VISIBLE : View.GONE);
        // 19.2: the last section of the page — the official channel, opened straight in the Telegram app
        View telegram = ensureRow(a, host, TAG_TELEGRAM, "تابعنا على Telegram", "قناة Cigram الرسمية · @" + CigramTelegram.CHANNEL,
                AdsIcons.TELEGRAM, 0, new View.OnClickListener() {
                    @Override public void onClick(View v) { CigramTelegram.open(a); }
                });
        setSubtitle(theme, themeLabel(a));
        setSubtitle(plus, plusLabel(a));
        setSubtitle(switchRow, switchLabel(a));
        plus.setVisibility(logged ? View.VISIBLE : View.GONE);
        // the app-icon picker lives in Settings now (all customisation in one place)
        if (appIcon != null) { detach(appIcon); appIcon = null; }
        // a visitor has no profile to edit and nothing to sign out of; the appearance row is for the signed-in page
        // (a visitor has the same choice as a small link on the welcome card, which disappears with the form)
        edit.setVisibility(logged ? View.VISIBLE : View.GONE);
        logout.setVisibility(logged ? View.VISIBLE : View.GONE);
        theme.setVisibility(logged ? View.VISIBLE : View.GONE);
        // rows built by CigramAccountExtras have an arrow and a label only: give them the same face as the others
        if (widget != null) CigramAccountUI.restyleRow(a, widget, AdsIcons.IMAGE, 0, "على الشاشة الرئيسية");
        if (plusCode != null) CigramAccountUI.restyleRowAs(a, plusCode, "ضع كود التفعيل", "Cigram Plus", AdsIcons.STAR, 0);

        // ---- group «حسابي»: lives inside the profile block, right under the stats
        ArrayList<View> accountRows = new ArrayList<View>();
        if (profile instanceof LinearLayout && settings != null && settings.getParent() == profile) {
            LinearLayout p = (LinearLayout) profile;
            if (edit.getParent() != p) {
                detach(edit);
                p.addView(edit, p.indexOfChild(settings), rowParams(a));
            }
            // «قائمة المشاهدة» joins the personal group, between the profile and the settings
            if (mine != null && mine.getParent() != p) {
                ViewGroup.LayoutParams keep = mine.getLayoutParams();
                detach(mine);
                p.addView(mine, p.indexOfChild(settings), keep instanceof LinearLayout.LayoutParams ? keep : rowParams(a));
            }
            setTitle(a, p, "account", "حسابي", edit);
            accountRows.add(edit);
            if (mine != null) accountRows.add(mine);
            accountRows.add(settings);
        }

        // ---- the groups of the page itself, in this order, right before the external link (if any)
        ArrayList<View> plusRows = new ArrayList<View>();
        add(plusRows, plus, plusCode);
        ArrayList<View> experience = new ArrayList<View>();
        if (mine != null && !accountRows.contains(mine)) experience.add(mine);
        add(experience, profiles, calendar, switchRow, theme, fonts, perf, widget);
        ArrayList<View> support = new ArrayList<View>();
        add(support, adRequest, contact, help, update);
        ArrayList<View> info = new ArrayList<View>();
        add(info, about, terms);
        ArrayList<View> actions = new ArrayList<View>();
        add(actions, logout);
        ArrayList<View> follow = new ArrayList<View>();
        add(follow, telegram);

        View link = host.findViewWithTag(CigramExternalLink.TAG);
        ArrayList<View> sequence = new ArrayList<View>();
        sequence.add(title(a, "plus", "Cigram Plus"));
        sequence.addAll(plusRows);
        if (plusBox != null) sequence.add(plusBox);
        sequence.add(title(a, "experience", "التطبيق"));
        sequence.addAll(experience);
        sequence.add(title(a, "support", "الدعم والتواصل"));
        sequence.addAll(support);
        sequence.add(title(a, "info", "عن Cigram"));
        sequence.addAll(info);
        sequence.add(title(a, "actions", "إجراءات الحساب"));
        sequence.addAll(actions);
        sequence.add(title(a, "follow", "تابعنا"));
        sequence.addAll(follow);
        for (int i = 0; i < sequence.size(); i++) detach(sequence.get(i));
        int at = link != null && link.getParent() == host ? host.indexOfChild(link) : host.getChildCount();
        for (int i = 0; i < sequence.size(); i++) {
            View v = sequence.get(i);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            host.addView(v, at++, lp instanceof LinearLayout.LayoutParams ? (LinearLayout.LayoutParams) lp
                    : isTitle(v) ? new LinearLayout.LayoutParams(-1, -2) : rowParams(a));
        }
        // the old section titles ("الحساب" / "الدعم") are replaced by the ones above
        hideOldTitles(host);

        // ---- shapes + visibility of every group
        style(a, pal, accountRows, host.findViewWithTag(TAG_TITLE + "account"));
        style(a, pal, plusRows, host.findViewWithTag(TAG_TITLE + "plus"));
        style(a, pal, experience, host.findViewWithTag(TAG_TITLE + "experience"));
        style(a, pal, support, host.findViewWithTag(TAG_TITLE + "support"));
        style(a, pal, info, host.findViewWithTag(TAG_TITLE + "info"));
        style(a, pal, actions, host.findViewWithTag(TAG_TITLE + "actions"));
        style(a, pal, follow, host.findViewWithTag(TAG_TITLE + "follow"));
        // the custom boxes keep their own content, but sit on the page with the same margins and colours
        if (plusBox != null) CigramAccountUI.sideMargins(a, plusBox);
    }

    /** Four graduated stops, top to bottom: the page brightens very slightly under the header and settles. */
    static Drawable pageGradient(CigramPalette pal) {
        int[] stops = pal.light
                ? new int[]{0xFFFFFFFF, 0xFFF7F7F9, 0xFFF1F1F4, 0xFFECECEF}
                : new int[]{0xFF19191C, 0xFF131315, 0xFF0F0F11, 0xFF0B0B0C};
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, stops);
    }

    private static String plusLabel(Context c) {
        try {
            if (CigramPlus.isPlus(c)) return "مفعّل · " + CigramPlus.periodText(c);
            return CigramPlus.salesEnabled() ? "اشترك أو أدخل كود التفعيل" : "أدخل كود التفعيل";
        } catch (Throwable t) {
            return "مزايا المشتركين";
        }
    }

    private static void openPage(Activity a, Class<?> page) {
        try {
            a.startActivity(new Intent(a, page));
        } catch (RuntimeException error) {
            Log.w("CigramAccountDash", "open " + page.getSimpleName() + " failed", error);
        }
    }

    // ================================================================== «تبديل» (phone / TV interface)

    /** Current interface, as the subtitle of the «تبديل» row. */
    static String switchLabel(Context c) {
        int m = CigramTvMode.mode(c);
        if (m == CigramTvMode.ON) return "الواجهة الحالية: Cigram TV";
        if (m == CigramTvMode.OFF) return "الواجهة الحالية: الجوال";
        return CigramTvMode.isTv(c) ? "تلقائي · Cigram TV" : "تلقائي · واجهة الجوال";
    }

    /**
     * «تبديل»: switches between the phone interface and the Cigram TV edition. It is the app's existing TV-mode
     * setting (CigramTvMode, also in Settings → «وضع التلفاز»); the app restarts on its Home so the chosen interface
     * is built from scratch (the TV edition replaces the phone Home, see MainActivity / CigramTvActivity).
     */
    static void showSwitchDialog(final Activity a) {
        final CigramPalette pal = CigramPalette.of(a);
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box.setBackground(CigramPalette.round(a, pal.surface, pal.stroke, 22));
        box.setPadding(dp(a, 8), dp(a, 18), dp(a, 8), dp(a, 10));
        TextView head = CigramUI.label(a, "تبديل الواجهة", 18f, pal.text, true);
        head.setGravity(Gravity.RIGHT);
        head.setPadding(dp(a, 16), 0, dp(a, 16), dp(a, 2));
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView sub = CigramUI.label(a, "اختر الواجهة التي تناسب جهازك", 12.5f, pal.text3, false);
        sub.setGravity(Gravity.RIGHT);
        sub.setPadding(dp(a, 16), 0, dp(a, 16), dp(a, 10));
        box.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        final int current = CigramTvMode.mode(a);
        final int[] modes = {CigramTvMode.OFF, CigramTvMode.ON, CigramTvMode.AUTO};
        final String[][] texts = {
                {"واجهة الجوال", "اللمس والتمرير، للجوال والتابلت"},
                {"Cigram TV", "واجهة التلفاز: جهاز التحكم والشاشة الكبيرة"},
                {"تلقائي", "يكتشف Cigram نوع الجهاز بنفسه"}};
        for (int i = 0; i < modes.length; i++) {
            final int mode = modes[i];
            box.addView(optionRow(a, pal, texts[i][0], texts[i][1], mode == current, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    d.dismiss();
                    if (mode == current) return;
                    CigramTvMode.setMode(a, mode);
                    restartOnHome(a);
                }
            }), new LinearLayout.LayoutParams(-1, dp(a, 62)));
        }
        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            d.getWindow().setLayout(Math.min(dp(a, 360), a.getResources().getDisplayMetrics().widthPixels - dp(a, 40)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    /** Starts the app again on its Home in a fresh task (the interface is chosen when Home is created). */
    static void restartOnHome(Activity a) {
        try {
            Intent i = new Intent(a, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            a.startActivity(i);
            a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (RuntimeException error) {
            Log.w("CigramAccountDash", "restart failed", error);
        }
    }

    private static void add(ArrayList<View> out, View... views) {
        for (int i = 0; i < views.length; i++) if (views[i] != null) out.add(views[i]);
    }

    private static void detach(View v) {
        if (v != null && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private static boolean isTitle(View v) {
        Object tag = v.getTag();
        return tag instanceof String && ((String) tag).startsWith(TAG_TITLE);
    }

    private static void hideOldTitles(LinearLayout host) {
        View root = host.getRootView();
        if (root == null) return;
        for (String key : new String[]{"account", "support"}) {
            View old = root.findViewWithTag(CigramAccountHeader.TAG_SECTION + key);
            if (old != null) old.setVisibility(View.GONE);
        }
    }

    // ================================================================== rows

    private static LinearLayout.LayoutParams rowParams(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(c, 62));
        lp.setMargins(dp(c, 14), 0, dp(c, 14), 0);
        return lp;
    }

    private static View ensureRow(Activity a, LinearLayout host, String tag, String title, String sub, int icon,
                                  int accent, View.OnClickListener click) {
        View found = host.getRootView() == null ? null : host.getRootView().findViewWithTag(tag);
        if (found instanceof LinearLayout) return found;
        LinearLayout row = new LinearLayout(a);
        row.setTag(tag);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(click);
        CigramAccountUI.fillRow(a, row, title, sub, icon, accent, false);
        row.setLayoutParams(rowParams(a));
        return row;
    }

    private static void setSubtitle(View row, String text) {
        if (!(row instanceof LinearLayout)) return;
        Object s = row.getTag(R.id.cg_row_subtitle);
        if (s instanceof TextView) ((TextView) s).setText(text);
    }

    /** Applies the group shape to its visible rows and shows / hides the title with them. */
    private static void style(Activity a, CigramPalette pal, ArrayList<View> rows, View title) {
        ArrayList<View> visible = new ArrayList<View>();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getVisibility() == View.VISIBLE) visible.add(rows.get(i));   // what the page logic decided stays
        }
        for (int i = 0; i < visible.size(); i++) {
            int pos = visible.size() == 1 ? SINGLE : i == 0 ? FIRST : i == visible.size() - 1 ? LAST : MIDDLE;
            View r = visible.get(i);
            r.setBackground(rowBackground(a, pal, pos));
            r.setElevation(0f);
            r.setClipToOutline(false);
            ViewGroup.LayoutParams lp = r.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                m.setMargins(dp(a, 14), 0, dp(a, 14), 0);
                m.height = dp(a, 62);
                r.setLayoutParams(m);
            }
        }
        if (title != null) title.setVisibility(visible.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Flat row surface: corners only on the outer edge of the group, a hairline border, a press ripple. */
    static Drawable rowBackground(Context c, CigramPalette pal, int pos) {
        float r = dp(c, 16);
        float top = pos == SINGLE || pos == FIRST ? r : 0f;
        float bottom = pos == SINGLE || pos == LAST ? r : 0f;
        float[] radii = {top, top, top, top, bottom, bottom, bottom, bottom};
        GradientDrawable face = new GradientDrawable();
        face.setColor(pal.surface);
        face.setCornerRadii(radii);
        face.setStroke(Math.max(1, dp(c, 1)), pal.stroke);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{face});
        // the bottom border of an inner row is pushed out of view: the next row's top border is the divider
        if (pos == FIRST || pos == MIDDLE) layers.setLayerInset(0, 0, 0, 0, -Math.max(1, dp(c, 1)));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadii(radii);
        return new RippleDrawable(ColorStateList.valueOf(pal.ripple), layers, mask);
    }

    // ================================================================== titles

    private static View title(Activity a, String key, String text) {
        View root = a.getWindow() == null ? null : a.getWindow().getDecorView();
        View existing = root == null ? null : root.findViewWithTag(TAG_TITLE + key);
        if (existing instanceof TextView) {
            ((TextView) existing).setTextColor(CigramPalette.of(a).text3);
            return existing;
        }
        return makeTitle(a, key, text);
    }

    private static TextView makeTitle(Activity a, String key, String text) {
        CigramPalette pal = CigramPalette.of(a);
        TextView t = CigramUI.label(a, text, 12.5f, pal.text3, true);
        t.setTag(TAG_TITLE + key);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setPadding(dp(a, 24), dp(a, 22), dp(a, 24), dp(a, 8));
        t.setIncludeFontPadding(false);
        t.setLetterSpacing(0.01f);
        return t;
    }

    /** A title placed directly above `anchor` inside `parent` (used inside the profile block). */
    private static void setTitle(Activity a, LinearLayout parent, String key, String text, View anchor) {
        View root = a.getWindow().getDecorView();
        View title = root.findViewWithTag(TAG_TITLE + key);
        if (!(title instanceof TextView)) title = makeTitle(a, key, text);
        ((TextView) title).setTextColor(CigramPalette.of(a).text3);
        detach(title);
        parent.addView(title, Math.max(0, parent.indexOfChild(anchor)), new LinearLayout.LayoutParams(-1, -2));
    }

    // ================================================================== appearance

    static String themeLabel(Context c) {
        String m = CigramTheme.userMode(c);
        return CigramTheme.MODE_LIGHT.equals(m) ? "فاتح" : CigramTheme.MODE_DARK.equals(m) ? "داكن" : "حسب النظام";
    }

    /** System / Light / Dark. The choice is saved at once and every open page is recreated in the new mode. */
    static void showThemeDialog(final Activity a) {
        final CigramPalette pal = CigramPalette.of(a);
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(CigramPalette.round(a, pal.surface, pal.stroke, 22));
        box.setPadding(dp(a, 8), dp(a, 18), dp(a, 8), dp(a, 10));

        TextView head = CigramUI.label(a, "المظهر", 18f, pal.text, true);
        head.setPadding(dp(a, 16), 0, dp(a, 16), dp(a, 2));
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));
        TextView sub = CigramUI.label(a, "اختر شكل التطبيق", 12.5f, pal.text3, false);
        sub.setPadding(dp(a, 16), 0, dp(a, 16), dp(a, 10));
        box.addView(sub, new LinearLayout.LayoutParams(-1, -2));

        final String current = CigramTheme.userMode(a);
        String[][] options = {
                {CigramTheme.MODE_SYSTEM, "حسب النظام", "يتبع إعداد الهاتف تلقائياً"},
                {CigramTheme.MODE_LIGHT, "فاتح", "خلفيات فاتحة ونصوص داكنة"},
                {CigramTheme.MODE_DARK, "داكن", "خلفيات داكنة تناسب المشاهدة ليلاً"}};
        for (int i = 0; i < options.length; i++) {
            final String mode = options[i][0];
            box.addView(optionRow(a, pal, options[i][1], options[i][2], mode.equals(current), new View.OnClickListener() {
                @Override public void onClick(View v) {
                    d.dismiss();
                    if (!mode.equals(current)) CigramTheme.setUserMode(a, mode);
                }
            }), new LinearLayout.LayoutParams(-1, dp(a, 62)));
        }
        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
            d.getWindow().setLayout(Math.min(dp(a, 360), a.getResources().getDisplayMetrics().widthPixels - dp(a, 40)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    private static View optionRow(Activity a, CigramPalette pal, String title, String sub, boolean on, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(a, 14), 0, dp(a, 14), 0);
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(title + (on ? "، محدد" : ""));
        GradientDrawable mask = CigramPalette.round(a, 0xFFFFFFFF, 0, 14);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(pal.ripple),
                on ? CigramPalette.round(a, pal.brandSoft, 0, 14) : null, mask));
        row.setOnClickListener(click);

        FrameLayout mark = new FrameLayout(a);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(on ? pal.brand : 0);
        ring.setStroke(Math.max(1, dp(a, 2)), on ? pal.brand : pal.stroke);
        mark.setBackground(ring);
        if (on) mark.addView(new AdsIcons.Icon(a, AdsIcons.CHECK, 0xFFFFFFFF), new FrameLayout.LayoutParams(dp(a, 14), dp(a, 14), Gravity.CENTER));
        row.addView(mark, new LinearLayout.LayoutParams(dp(a, 22), dp(a, 22)));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        TextView t = CigramUI.label(a, title, 15.5f, pal.text, true);
        t.setIncludeFontPadding(false);
        col.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView s = CigramUI.label(a, sub, 12f, pal.text3, false);
        s.setIncludeFontPadding(false);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = dp(a, 3);
        col.addView(s, sl);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.leftMargin = dp(a, 12);
        row.addView(col, cl);
        return row;
    }

    // ================================================================== shortcuts into Settings

    /** Opens the Settings page, which runs its own edit-profile / sign-out flow ({@code CigramSettings.runRequestedAction}). */
    private static void openSettings(Activity a, String action) {
        try {
            Intent i = new Intent(a, SettingsActivity.class);
            i.putExtra(CigramSettings.EXTRA_ACTION, action);
            a.startActivity(i);
        } catch (RuntimeException error) {
            Log.w("CigramAccountDash", "settings shortcut failed", error);
        }
    }
}
