package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * CIGRAM TV EDITION — the dedicated television front-end (host).
 *
 * This is NOT a magnified phone screen: it is a cinematic 10-foot UI driven entirely by the remote.
 * A persistent right-edge sidebar (RTL) switches between bespoke TV panels (Home / Movies / Series /
 * Search) and hands the deeper, already-TV-ready screens to the existing activities (Channels,
 * My List, Discover, Account, Settings) so NOTHING is duplicated — the same catalogue, player,
 * accounts, Plus and channel systems are reused.
 *
 * Only reached on a television (see {@link #shouldRunTvEdition}); the phone app is untouched.
 */
public final class CigramTvActivity extends Activity implements CigramTvHost {

    static final String PREFS = "cigram_tv_edition";

    private LinearLayout sidebar;
    private FrameLayout content;
    private final List<TextView> navItems = new ArrayList<TextView>();
    private final List<JSONObject> catalog = new ArrayList<JSONObject>();
    private int current = -1;
    private boolean catalogReady;

    // The ten sidebar entries. Index order is used by onNav().
    private static final String[] NAV_LABELS = {
            "الرئيسية", "الأفلام", "المسلسلات", "القنوات المباشرة", "شاهد لاحقاً",
            "متابعة المشاهدة", "اكتشف", "البحث", "الحساب", "الإعدادات"
    };

    /** True only on a television where the user has not turned the TV edition off. */
    public static boolean shouldRunTvEdition(Context c) {
        try {
            if (c == null || !CigramTvMode.isTv(c)) return false;
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override public Activity activity() { return this; }

    @Override public int dp(float v) { return CigramStage1InitProvider.cigramDp(this, v); }

    @Override public Typeface font() {
        try { return CigramStage1InitProvider.cigramFont(this); } catch (Throwable t) { return Typeface.DEFAULT; }
    }

    /** The parsed catalogue, kept in memory so the details page can build "similar titles" without re-parsing it. */
    private static volatile List<JSONObject> SHARED = new ArrayList<JSONObject>();

    static List<JSONObject> sharedCatalog() { return SHARED; }

    List<JSONObject> catalog() { return catalog; }
    boolean catalogReady() { return catalogReady; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } catch (Throwable ignored) { }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(CigramDesign.BG_APP);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.HORIZONTAL);
        // content on the LEFT, sidebar on the RIGHT edge (RTL rail layout)
        content = new FrameLayout(this);
        shell.addView(content, new LinearLayout.LayoutParams(0, -1, 1f));
        sidebar = buildSidebar();
        // 2026: the ten entries do not fit the height of every TV (or a TV-mode phone); in a ScrollView the
        // focused entry is always scrolled into view instead of disappearing under the bottom edge
        android.widget.ScrollView railScroll = new android.widget.ScrollView(this);
        railScroll.setFillViewport(true);
        railScroll.setVerticalScrollBarEnabled(false);
        railScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        railScroll.setBackgroundColor(0xFF0A0A0C);
        railScroll.addView(sidebar, new FrameLayout.LayoutParams(-1, -2));
        shell.addView(railScroll, new LinearLayout.LayoutParams(dp(248), -1));

        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        try { CigramTvFocus.attach(this); } catch (Throwable ignored) { }   // remote glow + arrow reach

        showLoading();
        loadCatalog();
    }

    // --------------------------------------------------------------------- sidebar

    private LinearLayout buildSidebar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(0xFF0A0A0C);   // 2026 charcoal (was navy 0xFF03090E)
        bar.setPadding(dp(10), dp(26), dp(10), dp(18));

        // brand
        TextView brand = new TextView(this);
        brand.setText("سيكرام TV");
        brand.setTextColor(CigramDesign.TEXT);
        brand.setTextSize(22f);
        brand.setTypeface(font(), Typeface.BOLD);
        brand.setGravity(Gravity.RIGHT);
        brand.setPadding(dp(12), 0, dp(12), dp(22));
        bar.addView(brand, new LinearLayout.LayoutParams(-1, -2));

        for (int i = 0; i < NAV_LABELS.length; i++) {
            final int index = i;
            TextView item = new TextView(this);
            item.setText(NAV_LABELS[i]);
            item.setTextColor(CigramDesign.TEXT_2);
            item.setTextSize(17f);
            item.setTypeface(font(), Typeface.BOLD);
            item.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
            item.setSingleLine(true);
            item.setMinHeight(dp(46));
            item.setPadding(dp(20), dp(8), dp(20), dp(8));
            item.setFocusable(true);
            item.setFocusableInTouchMode(false);
            item.setClickable(true);
            item.setBackground(navBackground(false));
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onNav(index); }
            });
            item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean has) {
                    v.setBackground(navBackground(has || index == current));
                    ((TextView) v).setTextColor(has ? CigramDesign.TEXT : CigramDesign.TEXT_2);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(4);
            bar.addView(item, lp);
            navItems.add(item);
        }
        return bar;
    }

    private android.graphics.drawable.GradientDrawable navBackground(boolean active) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(active ? 0x22FFFFFF : 0x00000000);
        if (active) g.setStroke(dp(2), CigramDesign.ACCENT);
        return g;
    }

    private void markNav(int index) {
        for (int i = 0; i < navItems.size(); i++) {
            TextView t = navItems.get(i);
            boolean on = i == index;
            t.setBackground(navBackground(on || t.isFocused()));
            t.setTextColor(on || t.isFocused() ? CigramDesign.TEXT : CigramDesign.TEXT_2);
        }
    }

    // --------------------------------------------------------------------- navigation

    private void onNav(int index) {
        if (index == current && (index == 0 || index == 1 || index == 2 || index == 7 || index == 8 || index == 9)) {
            // same bespoke panel: do nothing (avoid a rebuild / focus jump)
            return;
        }
        switch (index) {
            case 3: launch(ChannelsActivity.class); return;          // القنوات المباشرة
            case 4: launch(MyContentActivity.class); return;         // شاهد لاحقاً / قائمتي
            case 5: launch(MyContentActivity.class); return;         // متابعة المشاهدة
            case 6: launch(CigramDiscoverActivity.class); return;    // اكتشف
            default: break;
        }
        current = index;
        markNav(index);
        content.removeAllViews();
        View panel;
        switch (index) {
            case 1: panel = CigramTvGrid.movies(this); break;
            case 2: panel = CigramTvGrid.series(this); break;
            case 7: panel = CigramTvSearch.build(this); break;
            case 8: panel = CigramTvPanels.account(this); break;     // 2026: TV account panel (was the phone page)
            case 9: panel = CigramTvPanels.settings(this); break;    // 2026: TV settings panel (was the phone page)
            case 0:
            default: panel = CigramTvHome.build(this); break;
        }
        content.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        content.post(new Runnable() {
            @Override public void run() {
                View first = content.findFocus();
                if (first == null) content.requestFocus();
            }
        });
    }

    private void launch(Class<?> target) {
        try {
            startActivity(new Intent(this, target));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        } catch (Throwable error) {
            android.util.Log.w("CigramTv", "launch failed: " + target, error);
        }
    }

    // --------------------------------------------------------------------- catalogue

    private void showLoading() {
        content.removeAllViews();
        TextView t = new TextView(this);
        t.setText("جارٍ تحميل المحتوى…");
        t.setTextColor(CigramDesign.TEXT_2);
        t.setTextSize(18f);
        t.setTypeface(font());
        t.setGravity(Gravity.CENTER);
        content.addView(t, new FrameLayout.LayoutParams(-1, -1));
    }

    private void loadCatalog() {
        new Thread(new Runnable() {
            @Override public void run() {
                List<JSONObject> parsed = new ArrayList<JSONObject>();
                try {
                    String json = CigramStage1InitProvider.loadCatalogJsonBlocking(getApplicationContext());
                    if (json != null && json.trim().length() > 0) {
                        JSONArray flat = new HomeCatalog().parse(CigramTitles.normalizeCatalog(json));
                        for (int i = 0; i < flat.length(); i++) {
                            JSONObject o = flat.optJSONObject(i);
                            if (o != null) parsed.add(o);
                        }
                    }
                } catch (Throwable error) {
                    android.util.Log.w("CigramTv", "catalog parse failed", error);
                }
                final List<JSONObject> result = parsed;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        catalog.clear();
                        catalog.addAll(result);
                        SHARED = new ArrayList<JSONObject>(result);
                        catalogReady = true;
                        if (catalog.isEmpty()) {
                            showError();
                        } else {
                            onNav(0);   // open Home
                        }
                    }
                });
            }
        }, "cigram-tv-catalog").start();
    }

    private void showError() {
        content.removeAllViews();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        TextView t = new TextView(this);
        t.setText("تعذر تحميل المحتوى. تحقق من الاتصال.");
        t.setTextColor(CigramDesign.TEXT);
        t.setTextSize(18f);
        t.setTypeface(font());
        t.setGravity(Gravity.CENTER);
        box.addView(t, new LinearLayout.LayoutParams(-2, -2));
        TextView retry = new TextView(this);
        retry.setText("إعادة المحاولة");
        retry.setTextColor(CigramDesign.TEXT);
        retry.setTextSize(16f);
        retry.setTypeface(font(), Typeface.BOLD);
        retry.setGravity(Gravity.CENTER);
        retry.setFocusable(true);
        retry.setClickable(true);
        retry.setPadding(dp(28), dp(14), dp(28), dp(14));
        retry.setBackground(CigramDesign.primaryPill(this, 26));
        retry.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showLoading(); loadCatalog(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(18);
        box.addView(retry, lp);
        content.addView(box, new FrameLayout.LayoutParams(-1, -1));
        retry.requestFocus();
    }

    // --------------------------------------------------------------------- remote

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (navItems.size() > 0) navItems.get(Math.max(0, current)).requestFocus();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // From a content panel, BACK returns focus to the sidebar instead of leaving the app.
        View focused = getCurrentFocus();
        boolean inContent = focused != null && isDescendant(content, focused);
        if (inContent && navItems.size() > 0) {
            navItems.get(Math.max(0, current)).requestFocus();
            return;
        }
        super.onBackPressed();
    }

    private static boolean isDescendant(ViewGroup parent, View child) {
        View v = child;
        while (v != null) {
            if (v == parent) return true;
            if (!(v.getParent() instanceof View)) return false;
            v = (View) v.getParent();
        }
        return false;
    }

    // --------------------------------------------------------------------- shared helpers for panels

    /** A vertically scrollable container that fills the content area (used by Home / grids). */
    ScrollView verticalScroll() {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.setVerticalScrollBarEnabled(false);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.setClipToPadding(false);
        s.setPadding(dp(40), dp(28), dp(28), dp(28));   // safe area / overscan margins
        return s;
    }

    /** Opens the TV details page for a catalogue item. */
    @Override public void openDetails(JSONObject item, View card) {
        CigramTvDetailsActivity.open(this, item);
    }
}
