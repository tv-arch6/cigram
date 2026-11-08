package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 2026: the TV edition's own «الحساب» and «الإعدادات» panels (they used to open the phone pages, cramped and
 * touch-first). Each panel is a heading plus a grid of large focusable tiles laid out for a remote:
 * <ul>
 *   <li>three tiles per row, physical order right → left (the app has no RTL support switched on);</li>
 *   <li>every tile has a visible focus state (white border + slight lift) and reacts to OK / ENTER only once per press;</li>
 *   <li>choices (quality, seek step, speed) change in place on OK and show the new value at once;</li>
 *   <li>everything calls the features that already exist (same preferences, same pages, same dialogs).</li>
 * </ul>
 * Forms that need a keyboard (sign-in, profile editing) still open the regular account page.
 */
final class CigramTvPanels {

    private static final String PLAYER_PREFS = "cigram_player_prefs";   // same file as CigramSettings / the player

    private CigramTvPanels() { }

    // ================================================================== account

    static View account(final CigramTvHost host) {
        final Activity a = host.activity();
        Panel p = new Panel(host, "الحساب");
        boolean logged = CigramUserData.isLoggedIn(a);
        String name = "";
        try { name = a.getSharedPreferences("auth_session", Context.MODE_PRIVATE).getString("name", ""); } catch (Throwable ignored) { }
        String email = CigramUserData.email(a);
        String plus = CigramPlus.isPlus(a) ? "Cigram Plus مفعّل · " + CigramPlus.periodText(a) : "الحساب المجاني";
        p.summary(logged ? (name == null || name.trim().length() == 0 ? "حسابي" : name.trim()) : "زائر",
                logged ? (email + "  ·  " + plus) : "سجّل الدخول لحفظ قائمتك ومتابعة المشاهدة على كل أجهزتك");

        Grid g = p.grid();
        g.tile(AdsIcons.BOOKMARK, "قائمة المشاهدة", "المفضلة والمشاهدة لاحقاً", open(a, MyContentActivity.class));
        g.tile(AdsIcons.CROWN, "Cigram Plus", CigramPlus.isPlus(a) ? "تفاصيل اشتراكك" : "اشترك أو أدخل كود التفعيل", new Runnable() {
            @Override public void run() { CigramPlusUI.openPage(a, CigramPlus.salesEnabled() && !CigramPlus.isPlus(a) ? "pay" : "code"); }
        });
        g.tile(AdsIcons.SWITCH, "تبديل", CigramAccountDash.switchLabel(a), new Runnable() {
            @Override public void run() { CigramAccountDash.showSwitchDialog(a); }
        });
        g.tile(AdsIcons.MEGAPHONE, "أريد إعلاناً", "اطلب حملة إعلانية", open(a, AdvertiseActivity.class));
        g.tile(AdsIcons.HEADSET, "الدعم والتواصل", "راسل فريق Cigram", open(a, CommunicationActivity.class));
        g.tile(AdsIcons.USER, logged ? "إدارة الحساب" : "تسجيل الدخول",
                logged ? "الاسم والصورة والبريد" : "أو إنشاء حساب جديد", open(a, AccountActivity.class));
        g.tile(AdsIcons.INFO, "حول التطبيق", "ما هو Cigram ومن يقف خلفه", open(a, CigramAboutActivity.class));
        g.tile(AdsIcons.DOC, "الشروط والأحكام", "شروط استخدام Cigram", open(a, CigramTermsActivity.class));
        g.end();
        return p.root;
    }

    // ================================================================== settings

    static View settings(final CigramTvHost host) {
        final Activity a = host.activity();
        final SharedPreferences player = a.getSharedPreferences(PLAYER_PREFS, Context.MODE_PRIVATE);
        Panel p = new Panel(host, "الإعدادات");

        Grid g = p.grid();
        final int[] qValues = CigramQuality.PRESETS;
        final String[] qLabels = new String[qValues.length];
        for (int i = 0; i < qValues.length; i++) qLabels[i] = qValues[i] <= 0 ? "تلقائي" : CigramQuality.label(qValues[i]);
        g.choice(AdsIcons.QUALITY, "الجودة المفضلة", qLabels, new Choice() {
            public int get() { return indexOf(qValues, player.getInt("quality_height", 0)); }
            public void set(int i) { player.edit().putInt("quality_height", qValues[i]).apply(); }
        });
        final long[] steps = {10000L, 5000L, 15000L, 30000L};
        g.choice(AdsIcons.FORWARD10, "فترة التقديم والترجيع", new String[]{"10 ثوانٍ", "5 ثوانٍ", "15 ثانية", "30 ثانية"}, new Choice() {
            public int get() {
                long v = player.getLong("seek_step", 10000L);
                for (int i = 0; i < steps.length; i++) if (steps[i] == v) return i;
                return 0;
            }
            public void set(int i) { player.edit().putLong("seek_step", steps[i]).apply(); }
        });
        final float[] speeds = {1f, 1.25f, 1.5f, 2f, 0.75f};
        g.choice(AdsIcons.SPEED, "سرعة العرض", new String[]{"1x عادي", "1.25x", "1.5x", "2x", "0.75x"}, new Choice() {
            public int get() {
                float v = player.getFloat("speed", 1f);
                for (int i = 0; i < speeds.length; i++) if (Math.abs(speeds[i] - v) < 0.01f) return i;
                return 0;
            }
            public void set(int i) { player.edit().putFloat("speed", speeds[i]).apply(); }
        });
        g.tile(AdsIcons.SUN, "المظهر", CigramAccountDash.themeLabel(a), new Runnable() {
            @Override public void run() { CigramAccountDash.showThemeDialog(a); }
        });
        g.tile(AdsIcons.SWITCH, "واجهة العرض", CigramAccountDash.switchLabel(a), new Runnable() {
            @Override public void run() { CigramAccountDash.showSwitchDialog(a); }
        });
        g.tile(AdsIcons.BELL, "الإشعارات", "الحلقات والعناوين الجديدة", new Runnable() {
            @Override public void run() {
                if (CigramStage1InitProvider.areStage6SystemNotificationsEnabled(a)) {
                    CigramStage1InitProvider.openStage6NotificationSettings(a);
                } else {
                    CigramStage1InitProvider.requestStage6NotificationPermission(a, true);
                }
            }
        });
        g.tile(AdsIcons.SUBTITLES, "الترجمة والخطوط", "شكل وحجم خط الترجمة", new Runnable() {
            @Override public void run() {
                try { CigramFonts.openPage(a); } catch (Throwable t) { Toast.makeText(a, "تعذّر فتح إعدادات الترجمة", Toast.LENGTH_SHORT).show(); }
            }
        });
        g.tile(AdsIcons.GEAR, "إعدادات متقدمة", "التخزين والحساب وخيارات أخرى", open(a, SettingsActivity.class));
        g.tile(AdsIcons.INFO, "حول التطبيق", "الإصدار والمؤسس", open(a, CigramAboutActivity.class));
        g.end();
        return p.root;
    }

    // ================================================================== building blocks

    interface Choice {
        int get();
        void set(int index);
    }

    private static int indexOf(int[] values, int v) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return 0;
    }

    private static Runnable open(final Activity a, final Class<?> page) {
        return new Runnable() {
            @Override public void run() {
                try {
                    a.startActivity(new Intent(a, page));
                    a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                } catch (Throwable error) {
                    android.util.Log.w("CigramTv", "open " + page.getSimpleName() + " failed", error);
                }
            }
        };
    }

    /** Heading + an overscan-safe scrolling column. */
    private static final class Panel {
        final CigramTvHost host;
        final ScrollView root;
        final LinearLayout column;

        Panel(CigramTvHost host, String title) {
            this.host = host;
            Activity a = host.activity();
            root = new ScrollView(a);
            root.setFillViewport(true);
            root.setVerticalScrollBarEnabled(false);
            root.setClipToPadding(false);
            column = new LinearLayout(a);
            column.setOrientation(LinearLayout.VERTICAL);
            // 5 % overscan-safe margins of a 960 dp wide TV layout
            column.setPadding(host.dp(48), host.dp(36), host.dp(40), host.dp(48));
            root.addView(column, new FrameLayout.LayoutParams(-1, -2));
            TextView t = text(a, host, CigramPageKit.rtl(title), 30f, CigramDesign.TEXT, true);
            column.addView(t, new LinearLayout.LayoutParams(-1, -2));
        }

        void summary(String line1, String line2) {
            Activity a = host.activity();
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(host.dp(24), host.dp(18), host.dp(24), host.dp(18));
            box.setBackground(CigramDesign.round(0xFF1B1B1F, 0x1FFFFFFF, 18f, a));
            box.addView(text(a, host, CigramPageKit.rtl(line1), 22f, CigramDesign.TEXT, true), new LinearLayout.LayoutParams(-1, -2));
            TextView s = text(a, host, CigramPageKit.rtl(line2), 14f, CigramDesign.TEXT_2, false);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
            sl.topMargin = host.dp(6);
            box.addView(s, sl);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = host.dp(18);
            column.addView(box, lp);
        }

        Grid grid() { return new Grid(this); }
    }

    /** Three tiles per row; each new tile goes to the LEFT of the previous one (right → left reading order). */
    private static final class Grid {
        final Panel panel;
        LinearLayout row;
        int inRow;
        static final int PER_ROW = 3;

        Grid(Panel p) { panel = p; }

        /** The first tile added (top-right): where the remote starts when the panel opens. */
        View first;

        private LinearLayout nextSlot() {
            Activity a = panel.host.activity();
            if (row == null || inRow == PER_ROW) {
                row = new LinearLayout(a);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                row.setClipChildren(false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, panel.host.dp(118));
                lp.topMargin = panel.host.dp(16);
                panel.column.addView(row, lp);
                inRow = 0;
            }
            inRow++;
            return row;
        }

        TextView[] tile(int icon, String title, String sub, final Runnable action) {
            final Activity a = panel.host.activity();
            CigramTvHost h = panel.host;
            LinearLayout r = nextSlot();
            final LinearLayout t = new LinearLayout(a);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            t.setPadding(h.dp(18), h.dp(12), h.dp(18), h.dp(12));
            t.setFocusable(true);
            t.setClickable(true);
            t.setContentDescription(title + "، " + sub);
            final GradientDrawable idle = CigramDesign.round(0xFF1B1B1F, 0x1FFFFFFF, 16f, a);
            final GradientDrawable focused = CigramDesign.round(0xFF26262B, 0xFFFFFFFF, 16f, a);
            focused.setStroke(Math.max(2, h.dp(2)), 0xFFFFFFFF);
            t.setBackground(idle);
            AdsIcons.Icon ic = new AdsIcons.Icon(a, icon, CigramDesign.TEXT);
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(h.dp(28), h.dp(28));
            il.gravity = Gravity.RIGHT;
            t.addView(ic, il);
            TextView tt = text(a, h, CigramPageKit.rtl(title), 17f, CigramDesign.TEXT, true);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
            tl.topMargin = h.dp(8);
            t.addView(tt, tl);
            TextView st = text(a, h, CigramPageKit.rtl(sub), 13f, CigramDesign.TEXT_2, false);
            st.setSingleLine(true);
            st.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.addView(st, new LinearLayout.LayoutParams(-1, -2));
            t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean has) {
                    v.setBackground(has ? focused : idle);
                    v.animate().cancel();
                    v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(140L).start();
                }
            });
            final long[] last = {0L};
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - last[0] < 700L) return;   // one press = one action
                    last[0] = now;
                    try { action.run(); } catch (Throwable error) { android.util.Log.w("CigramTv", "tile failed", error); }
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            lp.leftMargin = h.dp(8);
            lp.rightMargin = h.dp(8);
            r.addView(t, 0, lp);   // physical right → left
            if (first == null) {
                first = t;
                t.post(new Runnable() {
                    @Override public void run() {
                        if (t.isShown() && !t.isInTouchMode()) t.requestFocus();
                    }
                });
            }
            return new TextView[]{tt, st};
        }

        void choice(int icon, String title, final String[] labels, final Choice c) {
            final TextView[] holder = new TextView[2];
            final int[] index = {Math.max(0, Math.min(labels.length - 1, c.get()))};
            TextView[] views = tile(icon, title, labels[index[0]] + "  ·  اضغط للتغيير", new Runnable() {
                @Override public void run() {
                    index[0] = (index[0] + 1) % labels.length;
                    c.set(index[0]);
                    if (holder[1] != null) holder[1].setText(CigramPageKit.rtl(labels[index[0]] + "  ·  اضغط للتغيير"));
                }
            });
            holder[0] = views[0];
            holder[1] = views[1];
        }

        /** Fills the last row with empty space so the tiles keep the same width. */
        void end() {
            if (row == null) return;
            Activity a = panel.host.activity();
            while (inRow < PER_ROW) {
                View gap = new View(a);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
                lp.leftMargin = panel.host.dp(8);
                lp.rightMargin = panel.host.dp(8);
                row.addView(gap, 0, lp);
                inRow++;
            }
        }
    }

    private static TextView text(Activity a, CigramTvHost h, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(h.font(), bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setGravity(Gravity.RIGHT);
        return t;
    }

    static boolean isConfirm(KeyEvent e) {
        int c = e.getKeyCode();
        return c == KeyEvent.KEYCODE_DPAD_CENTER || c == KeyEvent.KEYCODE_ENTER || c == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    static ViewGroup.LayoutParams fill() { return new ViewGroup.LayoutParams(-1, -1); }
}
