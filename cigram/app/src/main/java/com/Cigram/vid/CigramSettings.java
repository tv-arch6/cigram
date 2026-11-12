package com.Cigram.vid;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings page (v10.68) - grouped by purpose, every value visible on its row:
 *
 *   [ profile card: photo, name, e-mail, change photo, quick buttons: تعديل · نسخ البريد · خروج ]
 *   الحساب             تعديل الحساب · البريد الإلكتروني · تسجيل الخروج
 *   المشاهدة والمشغّل  الجودة المفضلة · السرعة · فترة التمرير · الحلقة التالية · تخطي المقدمة
 *   الترجمة            إظهار · الحجم · الخط والألوان
 *   التطبيق            الإشعارات (live state) · وضع التلفاز
 *   التخزين            ذاكرة الصور (الحجم) · سجل البحث · إعادة إعدادات المشغّل
 *   الدعم وحول         التحديثات · تواصل معنا · عن التطبيق
 *   منطقة الخطر        حذف الحساب بشكل نهائي
 *
 * Player values are the same preferences the player reads (cigram_player_prefs /
 * subtitle_settings), so a change here applies to the next video.
 *
 * The old views stay in the layout (hidden) because their logic (edit profile,
 * logout, photo picker) is wired to them; the new rows call that same logic.
 */
public final class CigramSettings {

    private static final int CARD = 0xFF1E1E21;
    private static final int LINE = 0xFF303033;
    /** v5.29: settings sections span the full width on navy #1C1C20 (same as the Account page), white text and icons. */
    private static final int SECTION_BG = 0xFF1C1C20;
    private static final int SECTION_LINE = 0x24FFFFFF;
    private static final int SECTION_INK = 0xFFFFFFFF;
    private static final int SECTION_INK_SOFT = 0xB3FFFFFF;
    private static final String TAG = "cigram_settings_scroll";
    private static final String PLAYER_PREFS = "cigram_player_prefs";
    private static final String SUB_PREFS = "subtitle_settings";

    private CigramSettings() { }

    /** Intent extra of the shortcuts on the Account page: "edit_profile" or "logout" (see CigramAccountDash). */
    static final String EXTRA_ACTION = "cigram_settings_action";

    /**
     * Runs the shortcut the page was opened for, once: the old (hidden) views own the edit-profile and sign-out flows,
     * so the shortcut just presses them. The extra is removed so a recreation does not repeat the action.
     */
    static void runRequestedAction(final Activity a) {
        if (a == null || a.getIntent() == null) return;
        final String action = a.getIntent().getStringExtra(EXTRA_ACTION);
        if (action == null) return;
        a.getIntent().removeExtra(EXTRA_ACTION);
        final String id = "edit_profile".equals(action) ? "textview_settings_edit_profile"
                : "logout".equals(action) ? "textview_settings_logout" : null;
        if (id == null) return;
        View decor = a.getWindow().getDecorView();
        decor.post(new Runnable() {
            @Override public void run() {
                if (!a.isFinishing()) clickOld(a, id);
            }
        });
    }

    private static View byId(Activity a, String name) {
        int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
        return id == 0 ? null : a.findViewById(id);
    }

    /**
     * v4.4: the toolbar arrow (img_settings_back, right side = correct for RTL) had no click
     * handler anywhere, so tapping it did nothing. It now behaves like the system back key.
     * An existing listener (if a later version adds one) is never replaced.
     */
    private static void bindBackArrow(final Activity a) {
        View back = byId(a, "img_settings_back");
        if (back == null || back.hasOnClickListeners()) return;
        back.setClickable(true);
        back.setFocusable(true);
        back.setContentDescription("رجوع");
        int pad = CigramUI.dp(a, 8);
        back.setPadding(pad, pad, pad, pad);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { CigramNav.back(a); }
        });
    }

    public static void bind(final Activity a) {
        if (a == null || a.isFinishing()) return;
        try {
            View rootView = byId(a, "linear_settings_root");
            if (!(rootView instanceof LinearLayout)) return;
            final LinearLayout root = (LinearLayout) rootView;
            bindBackArrow(a);
            if (root.findViewWithTag(TAG) != null) return;

            final boolean logged = CigramUserData.isLoggedIn(a);
            SharedPreferences auth = a.getSharedPreferences("auth_session", Context.MODE_PRIVATE);

            View avatarCard = byId(a, "linear3");
            if (avatarCard != null && avatarCard.getParent() instanceof ViewGroup) {
                ((ViewGroup) avatarCard.getParent()).removeView(avatarCard);
            }
            String[] hide = {"linear_settings_content", "linear_settings_account_card",
                    "linear_settings_delete_avatar", "linear_settings_logout", "linear1", "linear2"};
            for (String id : hide) {
                View v = byId(a, id);
                if (v != null) v.setVisibility(View.GONE);
            }

            ScrollView scroll = new ScrollView(a);
            scroll.setTag(TAG);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            col.setPadding(0, CigramUI.dp(a, 8), 0, CigramUI.dp(a, 32));
            scroll.addView(col, new ViewGroup.LayoutParams(-1, -2));
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

            // ---------------------------------------------------------- profile
            if (logged && avatarCard instanceof LinearLayout) {
                LinearLayout profile = (LinearLayout) avatarCard;
                ViewGroup.LayoutParams plp = profile.getLayoutParams();
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = CigramUI.dp(a, 8);
                lp.leftMargin = CigramUI.dp(a, 16);
                lp.rightMargin = CigramUI.dp(a, 16);
                profile.setLayoutParams(lp);
                profile.setBackground(CigramUI.round(CARD, LINE, 24, a));
                profile.setPadding(CigramUI.dp(a, 16), CigramUI.dp(a, 20), CigramUI.dp(a, 16), CigramUI.dp(a, 18));
                String name = auth.getString("name", "");
                String email = auth.getString("email", "");
                int at = Math.min(1, profile.getChildCount());
                if (name != null && name.trim().length() > 0) {
                    TextView n = CigramUI.label(a, name.trim(), 20f, CigramUI.TEXT, true);
                    n.setGravity(Gravity.CENTER);
                    n.setSingleLine(true);
                    n.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
                    nl.bottomMargin = CigramUI.dp(a, 2);
                    profile.addView(n, at++, nl);
                }
                if (email != null && email.trim().length() > 0) {
                    TextView e = CigramUI.label(a, email.trim(), 13f, CigramUI.MUTED, false);
                    e.setGravity(Gravity.CENTER);
                    e.setTextDirection(View.TEXT_DIRECTION_LTR);
                    e.setSingleLine(true);
                    e.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-1, -2);
                    el.bottomMargin = CigramUI.dp(a, 12);
                    profile.addView(e, at, el);
                }
                col.addView(profile);
            } else if (!logged) {
                col.addView(guestCard(a));
            }

            // v10.68: quick buttons inside the profile card
            if (logged && avatarCard instanceof LinearLayout) {
                ((LinearLayout) avatarCard).addView(quickActions(a), quickLp(a));
            }

            // ----------------------------------------------------------- account
            if (logged) {
                col.addView(heading(a, "الحساب"));
                LinearLayout card = card(a);
                card.addView(row(a, CigramUI.ICON_USER, CigramUI.TEAL, "تعديل الحساب", "الاسم والجنس", false,
                        new View.OnClickListener() {
                            @Override public void onClick(View v) { clickOld(a, "textview_settings_edit_profile"); }
                        }));
                card.addView(divider(a));
                final String mail = CigramUserData.email(a);
                card.addView(row(a, CigramUI.ICON_MAIL, CigramUI.TEAL, "البريد الإلكتروني",
                        mail == null || mail.length() == 0 ? "غير متوفر" : mail, false,
                        new View.OnClickListener() {
                            @Override public void onClick(View v) { copy(a, mail, "تم نسخ البريد"); }
                        }));
                card.addView(divider(a));
                card.addView(row(a, CigramUI.ICON_LOGOUT, CigramUI.AMBER, "تسجيل الخروج", "من هذا الجهاز فقط", false,
                        new View.OnClickListener() {
                            @Override public void onClick(View v) { clickOld(a, "textview_settings_logout"); }
                        }));
                col.addView(card);
            }

            // ------------------------------------------------------------ watching
            col.addView(heading(a, "المشاهدة والمشغّل"));
            LinearLayout watch = card(a);
            final SharedPreferences player = a.getSharedPreferences(PLAYER_PREFS, Context.MODE_PRIVATE);
            final SharedPreferences subs = a.getSharedPreferences(SUB_PREFS, Context.MODE_PRIVATE);
            final int[] qValues = CigramQuality.PRESETS;
            final String[] qLabels = new String[qValues.length];
            for (int i = 0; i < qValues.length; i++) qLabels[i] = CigramQuality.presetLabel(qValues[i]);
            watch.addView(choiceRow(a, CigramUI.ICON_IMAGE, "الجودة المفضلة",
                    "تُطبّق تلقائياً عند توفرها في الفيلم أو المسلسل", qLabels,
                    new Getter() { public int get() { return indexOf(qValues, player.getInt("quality_height", 0), 0); } },
                    new Setter() { public void set(int i) { player.edit().putInt("quality_height", qValues[i]).apply(); } }));
            watch.addView(divider(a));
            final float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
            final String[] speedLabels = {"0.5x", "0.75x", "1x (عادي)", "1.25x", "1.5x", "1.75x", "2x"};
            watch.addView(choiceRow(a, CigramUI.ICON_PROGRESS, "سرعة العرض الافتراضية", null, speedLabels,
                    new Getter() { public int get() {
                        float v = player.getFloat("speed", 1f);
                        for (int i = 0; i < speeds.length; i++) if (Math.abs(speeds[i] - v) < 0.01f) return i;
                        return 2;
                    } },
                    new Setter() { public void set(int i) { player.edit().putFloat("speed", speeds[i]).apply(); } }));
            watch.addView(divider(a));
            final long[] steps = {5000L, 10000L, 15000L, 30000L};
            final String[] stepLabels = {"5 ثوانٍ", "10 ثوانٍ", "15 ثانية", "30 ثانية"};
            watch.addView(choiceRow(a, CigramUI.ICON_UPDATE, "فترة التقديم والترجيع", null, stepLabels,
                    new Getter() { public int get() {
                        long v = player.getLong("seek_step", 10000L);
                        for (int i = 0; i < steps.length; i++) if (steps[i] == v) return i;
                        return 1;
                    } },
                    new Setter() { public void set(int i) { player.edit().putLong("seek_step", steps[i]).apply(); } }));
            watch.addView(divider(a));
            watch.addView(switchRow(a, CigramUI.ICON_PROGRESS, "بدء الحلقة التالية تلقائياً", "في المسلسلات",
                    player.getBoolean("auto_next_episode", true), new Setter() {
                        public void set(int on) { player.edit().putBoolean("auto_next_episode", on == 1).apply(); }
                    }));
            watch.addView(divider(a));
            watch.addView(switchRow(a, CigramUI.ICON_FLAG, "تخطي المقدمة تلقائياً", "عند توفر توقيت المقدمة",
                    CigramSkipIntro.autoSkipEnabled(a), new Setter() {
                        public void set(int on) { CigramSkipIntro.setAutoSkip(a, on == 1); }
                    }));
            col.addView(watch);

            // ----------------------------------------------------------- subtitles
            col.addView(heading(a, "الترجمة"));
            LinearLayout sub = card(a);
            sub.addView(switchRow(a, CigramUI.ICON_INFO, "إظهار الترجمة", "عند وجود ملف ترجمة",
                    subs.getBoolean("subtitle_enabled", true), new Setter() {
                        public void set(int on) { subs.edit().putBoolean("subtitle_enabled", on == 1).apply(); }
                    }));
            sub.addView(divider(a));
            final float[] scales = {0.8f, 1f, 1.25f, 1.5f};
            final String[] scaleLabels = {"صغير", "متوسط", "كبير", "أكبر"};
            sub.addView(choiceRow(a, CigramUI.ICON_INFO, "حجم الترجمة", null, scaleLabels,
                    new Getter() { public int get() {
                        float v = subs.getFloat("subtitle_scale", 1f);
                        for (int i = 0; i < scales.length; i++) if (Math.abs(scales[i] - v) < 0.01f) return i;
                        return 1;
                    } },
                    new Setter() { public void set(int i) { subs.edit().putFloat("subtitle_scale", scales[i]).apply(); } }));
            sub.addView(divider(a));
            sub.addView(row(a, CigramUI.ICON_IMAGE, CigramUI.TEAL, "خط وألوان الترجمة", "الخط، لون النص والخلفية", false,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            try { CigramFonts.openPage(a); } catch (Throwable error) {
                                android.util.Log.w("CigramSettings", "fonts page failed", error);
                            }
                        }
                    }));
            col.addView(sub);

            // -------------------------------------------------------------- app
            col.addView(heading(a, "التطبيق"));
            LinearLayout app = card(a);
            final TextView[] notifSub = new TextView[1];
            View notifRow = row(a, CigramUI.ICON_BELL, CigramUI.RED, "الإشعارات", "", false,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (CigramStage1InitProvider.areStage6SystemNotificationsEnabled(a)) {
                                CigramStage1InitProvider.openStage6NotificationSettings(a);
                            } else {
                                CigramStage1InitProvider.requestStage6NotificationPermission(a, true);
                            }
                        }
                    });
            notifSub[0] = (TextView) notifRow.getTag();
            app.addView(notifRow);
            app.addView(divider(a));
            // وضع التلفاز: اختيار مباشر بدل الضغط المتكرر
            app.addView(choiceRow(a, CigramUI.ICON_IMAGE, "وضع التلفاز", "تلقائي يكتشف التلفاز بنفسه",
                    new String[]{"تلقائي", "مفعّل دائماً", "متوقف"},
                    new Getter() { public int get() {
                        int m = CigramTvMode.mode(a);
                        return m == CigramTvMode.ON ? 1 : m == CigramTvMode.OFF ? 2 : 0;
                    } },
                    new Setter() { public void set(int i) {
                        int next = i == 1 ? CigramTvMode.ON : i == 2 ? CigramTvMode.OFF : CigramTvMode.AUTO;
                        if (next == CigramTvMode.mode(a)) return;
                        CigramTvMode.setMode(a, next);
                        a.recreate();   // يُطبّق فوراً
                    } }));
            // 2026: Cigram Plus customisation lives here (was on the account page): the launcher icon picker
            app.addView(divider(a));
            app.addView(row(a, CigramUI.ICON_IMAGE, CigramUI.TEAL, "أيقونة التطبيق",
                    CigramPlus.isPlus(a) ? "اختر شكل Cigram على شاشتك الرئيسية" : "الكلاسيكية للجميع، والأشكال المميزة لمشتركي Plus",
                    false, new View.OnClickListener() {
                        @Override public void onClick(View v) { CigramAppIconUI.openDialog(a); }
                    }));
            col.addView(app);

            // ---------------------------------------------------------- about
            col.addView(heading(a, "الشروط"));
            LinearLayout aboutCard = card(a);
            aboutCard.addView(row(a, CigramUI.ICON_FLAG, CigramUI.TEAL, "الشروط والأحكام", "شروط استخدام Cigram", false,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { a.startActivity(new android.content.Intent(a, CigramTermsActivity.class)); }
                    }));
            col.addView(aboutCard);

            // ---------------------------------------------------------- storage
            col.addView(heading(a, "التخزين"));
            LinearLayout store = card(a);
            final View cacheRow = row(a, CigramUI.ICON_TRASH, CigramUI.TEAL, "مسح ذاكرة الصور المؤقتة",
                    "جارٍ الحساب…", false, null);
            final TextView cacheSub = (TextView) cacheRow.getTag();
            cacheRow.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    clearImageCache(a, cacheSub);
                }
            });
            store.addView(cacheRow);
            measureImageCache(a, cacheSub);
            store.addView(divider(a));
            store.addView(row(a, CigramUI.ICON_TRASH, CigramUI.TEAL, "مسح سجل البحث", "آخر ما فتحته من نتائج البحث", false,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            a.getSharedPreferences("cigram_search_recent", Context.MODE_PRIVATE).edit().clear().apply();
                            a.getSharedPreferences("cigram_search_filters", Context.MODE_PRIVATE).edit().clear().apply();
                            Toast.makeText(a, "تم مسح سجل البحث والفلاتر", Toast.LENGTH_SHORT).show();
                        }
                    }));
            store.addView(divider(a));
            store.addView(row(a, CigramUI.ICON_UPDATE, CigramUI.TEAL, "إعادة إعدادات المشغّل", "الجودة والسرعة والتمرير والترجمة", false,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            player.edit().remove("quality_height").remove("speed").remove("seek_step")
                                    .remove("auto_next_episode").apply();
                            subs.edit().remove("subtitle_enabled").remove("subtitle_scale").apply();
                            CigramSkipIntro.setAutoSkip(a, false);
                            Toast.makeText(a, "أُعيدت إعدادات المشغّل إلى الافتراضي", Toast.LENGTH_SHORT).show();
                            a.recreate();
                        }
                    }));
            col.addView(store);

            // ---------------------------------------------------------- support
            col.addView(heading(a, "الدعم والتحديثات"));
            LinearLayout help = card(a);
            String version = CigramUpdateChecker.installedName(a);
            help.addView(row(a, CigramUI.ICON_UPDATE, CigramUI.GREEN, "التحقق من التحديثات",
                    version.length() == 0 ? null : "الإصدار الحالي " + version, false,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            CigramUpdateChecker.manualCheck(a);
                        }
                    }));
            help.addView(divider(a));
            help.addView(row(a, CigramUI.ICON_MAIL, CigramUI.TEAL, "تواصل معنا", CigramReport.SUPPORT_EMAIL, false,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            showContact(a);
                        }
                    }));
            help.addView(divider(a));
            help.addView(row(a, CigramUI.ICON_INFO, 0xFF9AA9B2, "عن التطبيق", null, false,
                    new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            showAbout(a);
                        }
                    }));
            col.addView(help);

            // ------------------------------------------------------------ danger
            if (logged) {
                TextView dh = heading(a, "منطقة الخطر");
                dh.setTextColor(CigramUI.DANGER);
                col.addView(dh);
                LinearLayout danger = card(a);
                danger.setBackground(sectionSurface(a));
                danger.addView(row(a, CigramUI.ICON_TRASH, CigramUI.DANGER, "حذف الحساب بشكل نهائي",
                        "يحذف حسابك وكل بياناتك", true,
                        new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                CigramDeleteAccount.show(a);
                            }
                        }));
                col.addView(danger);
            }

            if (version.length() > 0) {
                TextView foot = CigramUI.label(a, "سيكرام · الإصدار " + version, 12f, CigramUI.MUTED, false);
                foot.setGravity(Gravity.CENTER);
                foot.setPadding(0, CigramUI.dp(a, 22), 0, 0);
                col.addView(foot, new LinearLayout.LayoutParams(-1, -2));
            }

            // live state of the notifications row (user may change it in the system settings)
            final Runnable refresh = new Runnable() {
                @Override public void run() {
                    if (notifSub[0] == null) return;
                    boolean on = CigramStage1InitProvider.areStage6SystemNotificationsEnabled(a);
                    notifSub[0].setText(on ? "مفعّلة" : "معطّلة - اضغط للتفعيل");
                    notifSub[0].setTextColor(on ? CigramUI.GREEN : CigramUI.AMBER);
                    notifSub[0].setVisibility(View.VISIBLE);
                }
            };
            refresh.run();
            root.getViewTreeObserver().addOnWindowFocusChangeListener(
                    new ViewTreeObserver.OnWindowFocusChangeListener() {
                        @Override public void onWindowFocusChanged(boolean hasFocus) {
                            if (hasFocus) refresh.run();
                        }
                    });
        } catch (Throwable error) {
            android.util.Log.w("CigramSettings", "bind failed", error);
        }
    }

    // ------------------------------------------------------------------ pieces

    private static TextView heading(Activity a, String text) {
        TextView t = CigramUI.label(a, text, 13.5f, CigramDesign.TEXT_2, true);
        t.setPadding(CigramUI.dp(a, 22), CigramUI.dp(a, 24), CigramUI.dp(a, 24), CigramUI.dp(a, 10));
        // 2026: plain heading, no red accent mark (consistent with the section titles)
        t.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    private static LinearLayout card(Activity a) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        c.setBackground(sectionSurface(a));
        c.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return c;
    }

    /** Full-width navy section: no corners, hairlines only at the top and bottom edges. */
    private static android.graphics.drawable.Drawable sectionSurface(Activity a) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(CigramTheme.adapt(a, SECTION_BG));
        int line = Math.max(1, CigramUI.dp(a, 1));
        g.setStroke(line, CigramTheme.adapt(a, SECTION_LINE));
        return new android.graphics.drawable.InsetDrawable(g, -line, 0, -line, 0);
    }

    private static View divider(Activity a) {
        View v = new View(a);
        v.setBackgroundColor(CigramTheme.adapt(a, SECTION_LINE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
        // v4.4: physical margins — the line starts under the text (icon column is on the right)
        lp.rightMargin = CigramUI.dp(a, 64);
        lp.leftMargin = CigramUI.dp(a, 14);
        v.setLayoutParams(lp);
        return v;
    }

    /** The returned row keeps its subtitle TextView in getTag() so callers can update it. */
    private static View row(Activity a, int glyph, int color, String title, String subtitle, boolean danger,
                            View.OnClickListener click) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        // v4.4: explicit LTR + physical order [chevron][texts][icon] → icon at the right, chevron
        // (pointing left = forward in Arabic) at the left, whatever the RTL support of the app.
        r.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(CigramUI.dp(a, 18), CigramUI.dp(a, 10), CigramUI.dp(a, 18), CigramUI.dp(a, 10));
        r.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), null,
                new android.graphics.drawable.ColorDrawable(0xFFFFFFFF)));
        r.setMinimumHeight(CigramUI.dp(a, 62));
        r.setClickable(true);
        r.setFocusable(true);

        LinearLayout box = new LinearLayout(a);
        box.setGravity(Gravity.CENTER);
        // v4.2: every settings icon is white on a solid black tile (danger row included);
        // `color` is kept in the signature for the existing call sites but no longer tints the icon.
        // v5.29: white icon straight on the navy section (no tile), matching the text
        box.addView(new CigramUI.Glyph(a, glyph, danger ? CigramUI.DANGER : SECTION_INK),
                new LinearLayout.LayoutParams(CigramUI.dp(a, 22), CigramUI.dp(a, 22)));

        r.addView(new CigramUI.Glyph(a, CigramUI.ICON_CHEVRON, danger ? CigramUI.DANGER : SECTION_INK),
                new LinearLayout.LayoutParams(CigramUI.dp(a, 20), CigramUI.dp(a, 20)));

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView t = CigramUI.label(a, title, 16f, danger ? CigramUI.DANGER : SECTION_INK, true);
        texts.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView s = CigramUI.label(a, subtitle == null ? "" : subtitle, 12.5f, SECTION_INK_SOFT, false);
        s.setVisibility(subtitle == null ? View.GONE : View.VISIBLE);
        if ("مفعّلة".equals(subtitle) || (subtitle != null && subtitle.startsWith("الإصدار"))) { /* style set later */ }
        if (subtitle != null && subtitle.indexOf('@') > 0) s.setTextDirection(View.TEXT_DIRECTION_LTR);
        texts.addView(s, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = CigramUI.dp(a, 8);
        tl.rightMargin = CigramUI.dp(a, 12);
        r.addView(texts, tl);
        r.setTag(s);

        r.addView(box, new LinearLayout.LayoutParams(CigramUI.dp(a, 40), CigramUI.dp(a, 40)));

        r.setOnClickListener(click);
        r.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                int act = e.getActionMasked();
                if (act == MotionEvent.ACTION_DOWN) v.setAlpha(0.7f);
                else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) v.setAlpha(1f);
                return false;
            }
        });
        return r;
    }

    private static View guestCard(final Activity a) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        c.setGravity(Gravity.CENTER);
        c.setBackground(CigramUI.round(CARD, LINE, 24, a));
        c.setPadding(CigramUI.dp(a, 18), CigramUI.dp(a, 22), CigramUI.dp(a, 18), CigramUI.dp(a, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = CigramUI.dp(a, 8);
        lp.leftMargin = CigramUI.dp(a, 16);
        lp.rightMargin = CigramUI.dp(a, 16);
        c.setLayoutParams(lp);
        TextView t = CigramUI.label(a, "أهلاً بك في سيكرام", 19f, CigramUI.TEXT, true);
        t.setGravity(Gravity.CENTER);
        c.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView s = CigramUI.label(a, "سجّل دخولك لحفظ سجل مشاهدتك ومفضلتك وإكمال المشاهدة من أي جهاز.",
                13.5f, CigramUI.MUTED, false);
        s.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = CigramUI.dp(a, 6);
        c.addView(s, sl);
        TextView b = new TextView(a);
        b.setText("تسجيل الدخول");
        b.setTextColor(CigramUI.TEXT);
        b.setTextSize(15f);
        b.setTypeface(CigramUI.font(a), Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackground(CigramUI.round(CigramUI.RED, 16, a));
        b.setClickable(true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    a.startActivity(new Intent(a, AccountActivity.class));
                } catch (Throwable ignored) { }
            }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(CigramUI.dp(a, 200), CigramUI.dp(a, 48));
        bl.topMargin = CigramUI.dp(a, 16);
        c.addView(b, bl);
        return c;
    }

    // ------------------------------------------------------- v10.68 rows

    interface Getter { int get(); }

    interface Setter { void set(int value); }

    private static final String[] IMAGE_CACHES = {
            "cigram_image_cache", "cigram_poster_cache", "cigram_hero_cache", "share_cards", "chlogos"
    };

    private static int indexOf(int[] values, int v, int fallback) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return fallback;
    }

    private static void clickOld(Activity a, String id) {
        View t = byId(a, id);
        if (t != null) t.performClick();
    }

    private static void copy(Activity a, String text, String done) {
        if (text == null || text.length() == 0) return;
        try {
            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Cigram", text));
            Toast.makeText(a, done, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }

    /** Three buttons under «تغيير الصورة»: تعديل الحساب · نسخ البريد · تسجيل الخروج. */
    private static View quickActions(final Activity a) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        bar.addView(quick(a, CigramUI.ICON_USER, "تعديل", CigramUI.TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) { clickOld(a, "textview_settings_edit_profile"); }
        }), quickItemLp(a, false));
        bar.addView(quick(a, CigramUI.ICON_MAIL, "نسخ البريد", CigramUI.TEXT, new View.OnClickListener() {
            @Override public void onClick(View v) { copy(a, CigramUserData.email(a), "تم نسخ البريد"); }
        }), quickItemLp(a, true));
        bar.addView(quick(a, CigramUI.ICON_LOGOUT, "خروج", CigramUI.AMBER, new View.OnClickListener() {
            @Override public void onClick(View v) { clickOld(a, "textview_settings_logout"); }
        }), quickItemLp(a, true));
        return bar;
    }

    private static LinearLayout.LayoutParams quickLp(Activity a) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = CigramUI.dp(a, 14);
        return lp;
    }

    private static LinearLayout.LayoutParams quickItemLp(Activity a, boolean gap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, CigramUI.dp(a, 64), 1f);
        if (gap) lp.setMarginStart(CigramUI.dp(a, 8));
        return lp;
    }

    private static View quick(Activity a, int glyph, String text, int color, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF),
                CigramUI.round(0xFF1C1C20, 0x1FFFFFFF, 16, a), null));
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription(text);
        b.addView(new CigramUI.Glyph(a, glyph, color), new LinearLayout.LayoutParams(CigramUI.dp(a, 22), CigramUI.dp(a, 22)));
        TextView t = CigramUI.label(a, text, 12.5f, color, true);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = CigramUI.dp(a, 5);
        b.addView(t, tl);
        b.setOnClickListener(click);
        return b;
    }

    /** A row whose current value is shown under its title; a tap opens the choices sheet. */
    private static View choiceRow(final Activity a, int glyph, final String title, final String note,
                                  final String[] labels, final Getter getter, final Setter setter) {
        final View[] holder = new View[1];
        holder[0] = row(a, glyph, CigramUI.TEAL, title, labels[getter.get()], false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                final TextView value = (TextView) holder[0].getTag();
                final CigramUI.Sheet sheet = CigramUI.sheet(a).title(title);
                if (note != null) sheet.message(note);
                LinearLayout list = new LinearLayout(a);
                list.setOrientation(LinearLayout.VERTICAL);
                int now = getter.get();
                for (int i = 0; i < labels.length; i++) {
                    final int which = i;
                    LinearLayout opt = new LinearLayout(a);
                    opt.setOrientation(LinearLayout.HORIZONTAL);
                    opt.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);   // [check] ...... [label]
                    opt.setGravity(Gravity.CENTER_VERTICAL);
                    opt.setPadding(CigramUI.dp(a, 16), 0, CigramUI.dp(a, 16), 0);
                    boolean on = i == now;
                    opt.setBackground(CigramUI.round(on ? 0xFF12384A : 0xFF1C1C20, on ? 0x66FFFFFF : 0x1FFFFFFF, 14, a));
                    CigramUI.Glyph check = new CigramUI.Glyph(a, CigramUI.ICON_CHECK, CigramUI.GREEN);
                    check.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
                    opt.addView(check, new LinearLayout.LayoutParams(CigramUI.dp(a, 22), CigramUI.dp(a, 22)));
                    TextView t = CigramUI.label(a, labels[i], 15.5f, CigramUI.TEXT, on);
                    t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
                    opt.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
                    opt.setClickable(true);
                    opt.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            sheet.dismiss();
                            value.setText(labels[which]);
                            setter.set(which);
                        }
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, CigramUI.dp(a, 52));
                    if (i > 0) lp.topMargin = CigramUI.dp(a, 8);
                    list.addView(opt, lp);
                }
                sheet.view(list).secondary("إغلاق", null).show();
            }
        });
        return holder[0];
    }

    /** A row with an on/off switch at its left end; setter gets 1 (on) or 0 (off). */
    private static View switchRow(final Activity a, int glyph, String title, String subtitle, boolean on,
                                  final Setter setter) {
        final View r = row(a, glyph, CigramUI.TEAL, title, subtitle, false, null);
        final Switch sw = new Switch(a, on);
        // replace the chevron (first physical child) with the switch
        LinearLayout lr = (LinearLayout) r;
        lr.removeViewAt(0);
        lr.addView(sw, 0, new LinearLayout.LayoutParams(CigramUI.dp(a, 44), CigramUI.dp(a, 26)));
        r.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sw.set(!sw.on);
                setter.set(sw.on ? 1 : 0);
            }
        });
        return r;
    }

    private static final class Switch extends View {
        boolean on;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Switch(Context c, boolean on) {
            super(c);
            this.on = on;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(boolean value) {
            on = value;
            invalidate();
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2f;
            p.setColor(on ? CigramUI.GREEN : 0x40FFFFFF);
            c.drawRoundRect(new android.graphics.RectF(0, 0, w, h), r, r, p);
            p.setColor(0xFFFFFFFF);
            // RTL: «on» puts the knob on the left (the end of the track in Arabic)
            float cx = on ? r : w - r;
            c.drawCircle(cx, r, r - Math.max(2f, h * 0.12f), p);
        }
    }

    private static void measureImageCache(final Activity a, final TextView out) {
        new Thread(new Runnable() {
            @Override public void run() {
                long total = 0;
                for (String d : IMAGE_CACHES) total += size(new java.io.File(a.getCacheDir(), d));
                final String text = "المساحة المستخدمة " + human(total);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() { out.setText(text); out.setVisibility(View.VISIBLE); }
                });
            }
        }, "cigram-cache-size").start();
    }

    private static void clearImageCache(final Activity a, final TextView out) {
        out.setText("جارٍ المسح…");
        new Thread(new Runnable() {
            @Override public void run() {
                for (String d : IMAGE_CACHES) delete(new java.io.File(a.getCacheDir(), d), false);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(a, "تم مسح الصور المؤقتة", Toast.LENGTH_SHORT).show();
                        measureImageCache(a, out);
                    }
                });
            }
        }, "cigram-cache-clear").start();
    }

    private static long size(java.io.File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) n += size(k);
        return n;
    }

    private static void delete(java.io.File f, boolean self) {
        if (f == null || !f.exists()) return;
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) delete(k, true);
        if (self) f.delete();
    }

    private static String human(long bytes) {
        if (bytes < 1024L * 1024L) return Math.max(0, bytes / 1024L) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    // ----------------------------------------------------------------- dialogs

    public static void showContact(final Activity a) {
        CigramUI.sheet(a)
                .icon(CigramUI.ICON_MAIL, CigramUI.TEAL)
                .title("تواصل معنا")
                .message("لأي استفسار أو مشكلة تقنية تتعلق بالتطبيق، راسلنا على البريد التالي وسيرد عليك "
                        + "فريق الدعم في أقرب وقت ممكن.")
                .view(emailBox(a))
                .primary("مراسلتنا", CigramUI.TEAL, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) {
                        try {
                            Intent i = new Intent(Intent.ACTION_SENDTO);
                            i.setData(android.net.Uri.parse("mailto:"));
                            i.putExtra(Intent.EXTRA_EMAIL, new String[]{CigramReport.SUPPORT_EMAIL});
                            i.putExtra(Intent.EXTRA_SUBJECT, "استفسار - تطبيق سيكرام");
                            a.startActivity(Intent.createChooser(i, "مراسلة الدعم"));
                        } catch (Throwable error) {
                            Toast.makeText(a, "لا يوجد تطبيق بريد", Toast.LENGTH_SHORT).show();
                        }
                        return false;
                    }
                })
                .secondary("نسخ البريد", new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) {
                        try {
                            ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("Cigram support", CigramReport.SUPPORT_EMAIL));
                            }
                            Toast.makeText(a, "تم نسخ البريد", Toast.LENGTH_SHORT).show();
                        } catch (Throwable ignored) { }
                        return true;
                    }
                })
                .show();
    }

    private static View emailBox(Activity a) {
        TextView t = CigramUI.label(a, CigramReport.SUPPORT_EMAIL, 16f, CigramUI.TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setTextDirection(View.TEXT_DIRECTION_LTR);
        t.setTextIsSelectable(true);
        t.setBackground(CigramUI.round(CigramUI.BG_SOFT, LINE, 14, a));
        t.setPadding(CigramUI.dp(a, 12), CigramUI.dp(a, 14), CigramUI.dp(a, 12), CigramUI.dp(a, 14));
        return t;
    }

    private static void showAbout(Activity a) {
        String v = CigramUpdateChecker.installedName(a);
        CigramUI.sheet(a)
                .icon(CigramUI.ICON_INFO, CigramUI.TEAL)
                .title("سيكرام")
                .message("منصة مشاهدة الأفلام والمسلسلات." + (v.length() == 0 ? "" : "\nالإصدار " + v))
                .primary("حسناً", CigramUI.TEAL, null)
                .show();
    }
}
