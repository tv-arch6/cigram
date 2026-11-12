package com.Cigram.vid;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Channels page (v2.7): a new look and a few new features for "القنوات".
 *
 *  - live banner with a one-tap "تابع آخر قناة"
 *  - search field + category chips (الكل / المفضلة / each section)
 *  - favourites (heart or long-press) and "شاهدتها مؤخراً" strip
 *  - 2-column grid; the channel picture fills the whole card with a light scrim behind the name
 *  - sections come from the two built-in channels plus the Admin catalogue (same data as before)
 */
public final class CigramChannels {
    static void trimMemory(int severity) { CigramStability.shrink(MEMORY, severity); }


    private static final String TAG = "CigramChannels";
    private static final String ROOT_TAG = "cigram_channels_v3";
    private static final String PREFS = "cigram_channels_v3";
    private static final String CACHE_PREFS = "cigram_channels_cache_v2";

    private static final int BG = 0xFF0E0E10;
    private static final int CARD = 0xFF1E1E21;
    private static final int RED = 0xFFE21B14;
    private static final int GREEN = 0xFF3DDC97;
    private static final int AMBER = 0xFFE9A23B;
    private static final int TEAL = 0xFF26A5B5;
    private static final int MUTED = 0xFF8E8E94;

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ExecutorService IMAGES = CigramPool.fixed(3, "channels-img");
    // v4.4: bounded by bytes, not by count. 48 entries of up to ~1200px ARGB logos could reach
    // well over 100 MB; now the cache keeps at most 1/8 of the heap (capped at 32 MB).
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(
            (int) Math.max(4L * 1024L * 1024L,
                    Math.min(32L * 1024L * 1024L, Runtime.getRuntime().maxMemory() / 8L))) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value == null ? 1 : Math.max(1, value.getRowBytes() * value.getHeight());
        }
    };
    private static final WeakHashMap<Activity, State> STATES = new WeakHashMap<Activity, State>();

    private CigramChannels() { }

    // ------------------------------------------------------------------ model

    private static final class Ch {
        String key = "";
        String name = "";
        String url = "";
        String image = "";
        String section = "";
        boolean available = true;
        View origin;          // built-in card: its original click opens the player
        Drawable drawable;    // built-in logo
    }

    private static final class State {
        final ArrayList<Ch> all = new ArrayList<Ch>();
        final ArrayList<String> sections = new ArrayList<String>();
        String query = "";
        String chip = "";          // "" = all, "★" = favourites, else a section name
        LinearLayout chipsHost;
        HorizontalScrollView chipScroll;
        LinearLayout sectionsHost;
        TextView bannerTitle;
        TextView bannerSub;
        View bannerPlay;
    }

    /** The page's State holds its views (and so the page): it must be dropped when the page is destroyed. */
    static void onDestroyed(Activity activity) {
        synchronized (STATES) { STATES.remove(activity); }
    }

    // ------------------------------------------------------------------ entry points

    public static void bind(Activity activity) {
        build(activity);
        styleHeaderTitle(activity);
    }

    /** v5.29: the page title «القنوات» uses the app font sima (assets/fonts/sima.ttf). */
    private static void styleHeaderTitle(Activity a) {
        if (!alive(a)) return;
        try {
            View t = byId(a, "textview_my_content_title");
            if (t instanceof TextView) {
                ((TextView) t).setTypeface(CigramStage1InitProvider.cigramFont(a), android.graphics.Typeface.BOLD);
            }
        } catch (Throwable ignored) { }
    }

    /** Called after the Admin catalogue was refreshed from the network. */
    public static void refresh(Activity activity) {
        build(activity);
    }

    private static View byId(Activity a, String name) {
        int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
        return id == 0 ? null : a.findViewById(id);
    }

    private static boolean alive(Activity a) {
        return a != null && !a.isFinishing() && !a.isDestroyed();
    }

    private static void build(final Activity a) {
        if (!alive(a)) return;
        try {
            View rv = byId(a, "linear_channels_root");
            if (!(rv instanceof LinearLayout)) return;
            LinearLayout root = (LinearLayout) rv;

            State old = STATES.get(a);
            String keepQuery = old == null ? "" : old.query;
            String keepChip = old == null ? "" : old.chip;
            int keepScroll = 0;
            View prev = root.findViewWithTag(ROOT_TAG);
            if (prev instanceof ScrollView) keepScroll = prev.getScrollY();

            // hide everything of the old layout except the header
            View header = byId(a, "linear_my_content_header");
            for (int i = 0; i < root.getChildCount(); i++) {
                View c = root.getChildAt(i);
                if (c == header || ROOT_TAG.equals(c.getTag())) continue;
                c.setVisibility(View.GONE);
            }
            if (prev != null) root.removeView(prev);

            State st = new State();
            st.query = keepQuery;
            st.chip = keepChip;
            collect(a, root, st);
            STATES.put(a, st);

            ScrollView scroll = new ScrollView(a);
            scroll.setTag(ROOT_TAG);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setFillViewport(true);
            scroll.setBackgroundColor(BG);
            LinearLayout content = new LinearLayout(a);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            content.setPadding(CigramUI.dp(a, 14), CigramUI.dp(a, 12), CigramUI.dp(a, 14), CigramUI.dp(a, 16));
            scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

            // v4.2: the big "last channel" banner above the search box was removed on request;
            // buildBanner() is no longer called (updateBanner() null-checks its views).
            // v10.34: the «مباشر الآن» line, the search box and the section chips were removed
            // on request; the page starts directly with the sections.
            st.query = "";
            st.sectionsHost = new LinearLayout(a);
            st.sectionsHost.setOrientation(LinearLayout.VERTICAL);
            st.sectionsHost.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            content.addView(st.sectionsHost, new LinearLayout.LayoutParams(-1, -2));

            render(a, st);
            if (keepScroll > 0) {
                final ScrollView sv = scroll;
                final int y = keepScroll;
                sv.post(new Runnable() { @Override public void run() { sv.scrollTo(0, y); } });
            }
            styleHeader(a);
        } catch (Throwable error) {
            Log.w(TAG, "build failed", error);
        }
    }

    private static void styleHeader(Activity a) {
        try {
            View h = byId(a, "linear_my_content_header");
            if (h != null) h.setBackgroundColor(BG);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ data

    private static final java.util.regex.Pattern SEPARATORS = java.util.regex.Pattern.compile("[\\s_\\-]+");   // compiled once

    private static String norm(String v) {
        if (v == null) return "";
        String lowered = v.trim().toLowerCase(Locale.US)
                .replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
                .replace("ة", "ه").replace("ى", "ي");
        return SEPARATORS.matcher(lowered).replaceAll("");
    }

    private static String sectionName(String raw) {
        String k = norm(raw);
        if (k.contains("رياض") || k.contains("sport")) return "الرياضة";
        if (k.contains("اخبار") || k.contains("خبر") || k.contains("news")) return "الأخبارية";
        return raw == null || raw.trim().length() == 0 ? "القنوات" : raw.trim();
    }

    private static void addBuiltIn(Activity a, State st, String card, String image, String text, String section) {
        View c = byId(a, card);
        View iv = byId(a, image);
        View tv = byId(a, text);
        if (c == null || tv == null) return;
        Ch ch = new Ch();
        ch.name = String.valueOf(((TextView) tv).getText()).trim();
        if (ch.name.length() == 0) return;
        ch.key = norm(ch.name);
        ch.section = section;
        ch.origin = c;
        if (iv instanceof ImageView) {
            Drawable d = ((ImageView) iv).getDrawable();
            if (d != null && d.getConstantState() != null) ch.drawable = d.getConstantState().newDrawable().mutate();
            else ch.drawable = d;
        }
        st.all.add(ch);
    }

    private static void collect(Activity a, LinearLayout root, State st) {
        addBuiltIn(a, st, "card_bein3", "image_bein3", "text_bein3", "الرياضة");
        addBuiltIn(a, st, "cardview2", "imageview3", "textview3", "الأخبارية");
        HashSet<String> seen = new HashSet<String>();
        for (int i = 0; i < st.all.size(); i++) seen.add(st.all.get(i).key);
        try {
            String raw = a.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).getString("json", "");
            if (raw.trim().length() > 0) {
                JSONArray groups = new JSONObject(raw).optJSONArray("categories");
                for (int g = 0; groups != null && g < groups.length(); g++) {
                    JSONObject group = groups.optJSONObject(g);
                    if (group == null) continue;
                    String section = sectionName(group.optString("name", ""));
                    JSONArray list = group.optJSONArray("channels");
                    for (int i = 0; list != null && i < list.length(); i++) {
                        JSONObject o = list.optJSONObject(i);
                        if (o == null) continue;
                        String name = o.optString("name", "").trim();
                        String key = norm(name);
                        if (key.length() == 0 || seen.contains(key)) continue;
                        seen.add(key);
                        Ch ch = new Ch();
                        ch.name = name;
                        ch.key = key;
                        ch.section = section;
                        ch.url = o.optString("url", o.optString("link", "")).trim();
                        ch.image = o.optString("image", o.optString("poster", o.optString("logo",
                                o.optString("image_url", o.optString("poster_url", ""))))).trim();
                        ch.available = o.optBoolean("available", true) && ch.url.length() > 0;
                        st.all.add(ch);
                    }
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "catalogue parse failed", error);
        }
        // sections in a stable order: sports, news, then the rest in catalogue order
        ArrayList<String> order = new ArrayList<String>();
        order.add("الرياضة");
        order.add("الأخبارية");
        for (int i = 0; i < st.all.size(); i++) if (!order.contains(st.all.get(i).section)) order.add(st.all.get(i).section);
        for (int i = 0; i < order.size(); i++) {
            for (int j = 0; j < st.all.size(); j++) {
                if (order.get(i).equals(st.all.get(j).section)) {
                    st.sections.add(order.get(i));
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ prefs (favourites / recent)

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // Perf: isFav() ran this (prefs read + JSON parse) once per channel card. The parsed list is
    // remembered while the saved text is unchanged.
    private static final java.util.HashMap<String, String> LIST_RAW = new java.util.HashMap<String, String>();
    private static final java.util.HashMap<String, ArrayList<String>> LIST_VAL = new java.util.HashMap<String, ArrayList<String>>();

    private static ArrayList<String> list(Context c, String key) {
        String raw = "[]";
        try { raw = prefs(c).getString(key, "[]"); } catch (Throwable ignored) { }
        if (raw == null) raw = "[]";
        synchronized (LIST_RAW) {
            if (raw.equals(LIST_RAW.get(key))) return new ArrayList<String>(LIST_VAL.get(key));
        }
        ArrayList<String> out = new ArrayList<String>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
        } catch (Throwable ignored) { }
        synchronized (LIST_RAW) {
            LIST_RAW.put(key, raw);
            LIST_VAL.put(key, new ArrayList<String>(out));
        }
        return out;
    }

    private static void save(Context c, String key, ArrayList<String> v) {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < v.size(); i++) arr.put(v.get(i));
        prefs(c).edit().putString(key, arr.toString()).apply();
    }

    private static boolean isFav(Context c, Ch ch) {
        return list(c, "fav").contains(ch.key);
    }

    private static void toggleFav(Context c, Ch ch) {
        ArrayList<String> f = list(c, "fav");
        if (f.contains(ch.key)) f.remove(ch.key); else f.add(0, ch.key);
        save(c, "fav", f);
    }

    private static void pushRecent(Context c, Ch ch) {
        ArrayList<String> r = list(c, "recent");
        r.remove(ch.key);
        r.add(0, ch.key);
        while (r.size() > 10) r.remove(r.size() - 1);
        save(c, "recent", r);
    }

    /** v10.34: the player marks a channel as watched when the user switches to it. */
    static void noteRecent(Context c, String key) {
        if (c == null || key == null || key.length() == 0) return;
        Ch ch = new Ch();
        ch.key = key;
        pushRecent(c, ch);
    }

    /** v10.34: same cached image loader for the player's logo and channel list. */
    static void loadLogo(String url, ImageView target, View placeholder) {
        if (target == null) return;
        loadImage(url, target, placeholder == null ? new View(target.getContext()) : placeholder);
    }

    private static Ch find(State st, String key) {
        for (int i = 0; i < st.all.size(); i++) if (st.all.get(i).key.equals(key)) return st.all.get(i);
        return null;
    }

    // ------------------------------------------------------------------ playing

    private static void open(Activity a, State st, Ch ch) {
        if (!ch.available) {
            CigramSilent.toast(a, "القناة غير متوفرة حالياً", android.widget.Toast.LENGTH_SHORT);
            return;
        }
        pushRecent(a, ch);
        CigramAnalytics.track(a, "channel_open", "section", ch.section == null ? "" : ch.section);
        try {
            // v10.34: the player gets the whole list (previous / next / channel list in the player)
            ArrayList<CigramLivePlayer.Entry> queue = new ArrayList<CigramLivePlayer.Entry>();
            int at = -1;
            for (int s = 0; s < st.sections.size(); s++) {
                for (int i = 0; i < st.all.size(); i++) {
                    Ch c = st.all.get(i);
                    if (!c.available || !st.sections.get(s).equals(c.section)) continue;
                    if (c == ch) at = queue.size();
                    queue.add(CigramLivePlayer.entry(c.key, c.name, c.url, c.image, c.section, c.origin));
                }
            }
            if (at < 0) {
                at = queue.size();
                queue.add(CigramLivePlayer.entry(ch.key, ch.name, ch.url, ch.image, ch.section, ch.origin));
            }
            CigramLivePlayer.setQueue(queue, at);
        } catch (Throwable error) {
            Log.w(TAG, "queue failed", error);
        }
        try {
            if (ch.origin != null) {
                ch.origin.performClick(); // the original handler opens LiveActivity with its own link
            } else {
                Intent live = new Intent(a, Class.forName(a.getPackageName() + ".LiveActivity"));
                live.putExtra("live_url", ch.url);
                live.putExtra("video", ch.url);
                live.putExtra("movie_url", ch.url);
                live.putExtra("title", ch.name);
                live.putExtra("type", "live");
                live.putExtra("autoplay", true);
                a.startActivity(live);
            }
        } catch (Throwable error) {
            Log.w(TAG, "open failed", error);
            CigramSilent.toast(a, "تعذر فتح القناة", android.widget.Toast.LENGTH_SHORT);
        }
        updateBanner(a, st);
    }

    // ------------------------------------------------------------------ banner / search / chips

    private static void buildBanner(final Activity a, final State st, LinearLayout content) {
        LinearLayout banner = new LinearLayout(a);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT,
                new int[]{0xFF12495A, 0xFF1B1B1F, 0xFF0A1F2B});
        bg.setCornerRadius(CigramUI.dp(a, 22));
        bg.setStroke(CigramUI.dp(a, 1), 0x2AFFFFFF);
        banner.setBackground(bg);
        banner.setPadding(CigramUI.dp(a, 16), CigramUI.dp(a, 14), CigramUI.dp(a, 14), CigramUI.dp(a, 14));

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        texts.setGravity(Gravity.RIGHT);

        LinearLayout live = new LinearLayout(a);
        live.setOrientation(LinearLayout.HORIZONTAL);
        live.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        live.setGravity(Gravity.CENTER_VERTICAL);
        live.setBackground(CigramUI.round(0x33E21B14, 10, a));
        live.setPadding(CigramUI.dp(a, 9), CigramUI.dp(a, 3), CigramUI.dp(a, 9), CigramUI.dp(a, 3));
        View dot = new View(a);
        dot.setBackground(CigramUI.round(RED, 4, a));
        TextView liveText = CigramUI.label(a, "بث مباشر", 11f, 0xFFFFB4B0, true);
        liveText.setPadding(0, 0, CigramUI.dp(a, 6), 0);
        live.addView(liveText, new LinearLayout.LayoutParams(-2, -2));
        live.addView(dot, new LinearLayout.LayoutParams(CigramUI.dp(a, 8), CigramUI.dp(a, 8)));
        try {
            ObjectAnimator pulse = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.2f);
            pulse.setDuration(800L);
            pulse.setRepeatMode(ObjectAnimator.REVERSE);
            pulse.setRepeatCount(ObjectAnimator.INFINITE);
            pulse.start();
        } catch (Throwable ignored) { }
        texts.addView(live, new LinearLayout.LayoutParams(-2, -2));

        st.bannerTitle = CigramUI.label(a, "القنوات", 22f, Color.WHITE, true);
        st.bannerTitle.setGravity(Gravity.RIGHT);
        st.bannerTitle.setSingleLine(true);
        st.bannerTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = CigramUI.dp(a, 8);
        texts.addView(st.bannerTitle, tl);
        st.bannerSub = CigramUI.label(a, "", 13f, 0xFFBDBDC2, false);
        st.bannerSub.setGravity(Gravity.RIGHT);
        texts.addView(st.bannerSub, new LinearLayout.LayoutParams(-2, -2));

        FrameLayout play = new FrameLayout(a);
        play.setBackground(CigramUI.round(RED, 26, a));
        play.setClickable(true);
        TextView arrow = CigramUI.label(a, "▶", 18f, Color.WHITE, false);
        arrow.setGravity(Gravity.CENTER);
        arrow.setPadding(CigramUI.dp(a, 3), 0, 0, 0);
        play.addView(arrow, new FrameLayout.LayoutParams(-1, -1));
        st.bannerPlay = play;
        banner.addView(play, new LinearLayout.LayoutParams(CigramUI.dp(a, 52), CigramUI.dp(a, 52)));
        banner.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        press(play);
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ArrayList<String> r = list(a, "recent");
                Ch ch = r.isEmpty() ? null : find(st, r.get(0));
                if (ch == null && !st.all.isEmpty()) ch = st.all.get(0);
                if (ch != null) open(a, st, ch);
            }
        });
        content.addView(banner, new LinearLayout.LayoutParams(-1, -2));
        updateBanner(a, st);
    }

    private static void updateBanner(Activity a, State st) {
        try {
            ArrayList<String> r = list(a, "recent");
            Ch last = r.isEmpty() ? null : find(st, r.get(0));
            int ready = 0;
            for (int i = 0; i < st.all.size(); i++) if (st.all.get(i).available) ready++;
            if (st.bannerTitle != null) st.bannerTitle.setText(last != null ? last.name : "القنوات المباشرة");
            if (st.bannerSub != null) {
                st.bannerSub.setText(last != null ? "تابع آخر قناة شاهدتها" : ready + " قناة جاهزة للمشاهدة");
            }
        } catch (Throwable ignored) { }
    }

    /** v10.34: one quiet line above the search: «● مباشر الآن · 12 قناة متاحة». */
    private static void buildSummary(Activity a, State st, LinearLayout content) {
        int ready = 0;
        for (int i = 0; i < st.all.size(); i++) if (st.all.get(i).available) ready++;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(a);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(RED);
        dot.setBackground(g);
        row.addView(dot, new LinearLayout.LayoutParams(CigramUI.dp(a, 8), CigramUI.dp(a, 8)));
        final ObjectAnimator pulse = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.25f);
        pulse.setDuration(900L);
        pulse.setRepeatMode(ObjectAnimator.REVERSE);
        pulse.setRepeatCount(ObjectAnimator.INFINITE);
        pulse.start();
        dot.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { if (!pulse.isStarted()) pulse.start(); }
            @Override public void onViewDetachedFromWindow(View v) { pulse.cancel(); }
        });
        TextView t = CigramUI.label(a, "مباشر الآن", 14f, Color.WHITE, true);
        t.setPadding(CigramUI.dp(a, 8), 0, CigramUI.dp(a, 6), 0);
        row.addView(t, new LinearLayout.LayoutParams(-2, -2));
        TextView n = CigramUI.label(a, "· " + ready + (ready == 1 ? " قناة متاحة" : " قنوات متاحة"), 13f, MUTED, false);
        row.addView(n, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = CigramUI.dp(a, 2);
        rl.rightMargin = CigramUI.dp(a, 2);
        content.addView(row, rl);
    }

    private static void buildSearch(final Activity a, final State st, LinearLayout content) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(CigramUI.round(0xFF1B1B1F, 0x22FFFFFF, 16, a));
        box.setPadding(CigramUI.dp(a, 8), 0, CigramUI.dp(a, 14), 0);

        final EditText input = new EditText(a);
        input.setHint("ابحث عن قناة…");
        input.setHintTextColor(MUTED);
        input.setTextColor(Color.WHITE);
        input.setTextSize(15f);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setBackground(null);
        input.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        input.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        input.setTypeface(CigramUI.font(a));
        input.setText(st.query);
        input.setPadding(CigramUI.dp(a, 10), 0, CigramUI.dp(a, 10), 0);

        final TextView clear = CigramUI.label(a, "✕", 15f, MUTED, false);
        clear.setPadding(CigramUI.dp(a, 10), CigramUI.dp(a, 8), CigramUI.dp(a, 10), CigramUI.dp(a, 8));
        clear.setVisibility(st.query.length() > 0 ? View.VISIBLE : View.GONE);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { input.setText(""); }
        });
        box.addView(clear, new LinearLayout.LayoutParams(-2, -2));
        box.addView(input, new LinearLayout.LayoutParams(0, CigramUI.dp(a, 48), 1f));
        TextView icon = CigramUI.label(a, "⌕", 22f, MUTED, false);
        box.addView(icon, new LinearLayout.LayoutParams(-2, -2));

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a1, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a1, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                st.query = e.toString().trim();
                clear.setVisibility(st.query.length() > 0 ? View.VISIBLE : View.GONE);
                render(a, st);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = CigramUI.dp(a, 12);
        content.addView(box, lp);
    }

    private static void buildChips(final Activity a, final State st) {
        st.chipsHost.removeAllViews();
        ArrayList<String> labels = new ArrayList<String>();
        ArrayList<String> values = new ArrayList<String>();
        labels.add("الكل");
        values.add("");
        if (!list(a, "fav").isEmpty()) {
            labels.add("♥ المفضلة");
            values.add("★");
        }
        for (int i = 0; i < st.sections.size(); i++) {
            labels.add(st.sections.get(i));
            values.add(st.sections.get(i));
        }
        for (int i = labels.size() - 1; i >= 0; i--) {
            final String value = values.get(i);
            boolean on = value.equals(st.chip);
            TextView chip = CigramUI.label(a, labels.get(i), 14f, on ? Color.WHITE : 0xFFBDBDC2, false);
            chip.setGravity(Gravity.CENTER);
            chip.setBackground(on ? CigramUI.round(RED, 18, a) : CigramUI.round(0xFF1B1B1F, 0x22FFFFFF, 18, a));
            chip.setPadding(CigramUI.dp(a, 16), CigramUI.dp(a, 8), CigramUI.dp(a, 16), CigramUI.dp(a, 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = CigramUI.dp(a, 8);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    st.chip = value;
                    render(a, st);
                }
            });
            press(chip);
            st.chipsHost.addView(chip, lp);
        }
        if (st.chipScroll != null) {
            final HorizontalScrollView cs = st.chipScroll;
            final LinearLayout host = st.chipsHost;
            cs.post(new Runnable() {
                @Override public void run() { cs.scrollTo(Math.max(0, host.getWidth() - cs.getWidth()), 0); }
            });
        }
    }

    // ------------------------------------------------------------------ sections

    private static void render(final Activity a, final State st) {
        try {
            if ("★".equals(st.chip) && list(a, "fav").isEmpty()) st.chip = "";
            if (st.chipsHost != null) buildChips(a, st);
            st.sectionsHost.removeAllViews();
            if (st.chip.length() > 0) addBackToAll(a, st);
            String q = norm(st.query);
            boolean searching = q.length() > 0;
            int shown = 0;

            // v10.34: «شاهدتها مؤخراً» removed from the page on request.
            // v5.1 (Rails): browsing "all" shows one horizontal row per section; picking a section chip
            // or searching keeps the full grid so every channel of that section is visible at once.
            final boolean rails = !searching && "".equals(st.chip);
            if (!searching && ("".equals(st.chip) || "★".equals(st.chip))) {
                ArrayList<Ch> favs = new ArrayList<Ch>();
                ArrayList<String> f = list(a, "fav");
                for (int i = 0; i < f.size(); i++) {
                    Ch c = find(st, f.get(i));
                    if (c != null) favs.add(c);
                }
                if (!favs.isEmpty()) {
                    addHeader(a, st.sectionsHost, "المفضلة", RED, favs.size(), rails ? chipJump(a, st, "★") : null);
                    if (rails) addRail(a, st, favs); else addGrid(a, st, favs);
                    shown++;
                }
            }
            if (!"★".equals(st.chip)) {
                int colorIndex = 0;
                for (int s = 0; s < st.sections.size(); s++) {
                    String sec = st.sections.get(s);
                    if (st.chip.length() > 0 && !st.chip.equals(sec)) continue;
                    ArrayList<Ch> items = new ArrayList<Ch>();
                    for (int i = 0; i < st.all.size(); i++) {
                        Ch c = st.all.get(i);
                        if (!sec.equals(c.section)) continue;
                        if (searching && !norm(c.name).contains(q)) continue;
                        items.add(c);
                    }
                    if (items.isEmpty()) continue;
                    int accent = "الرياضة".equals(sec) ? GREEN : "الأخبارية".equals(sec) ? AMBER
                            : (colorIndex++ % 2 == 0 ? TEAL : 0xFFB97CF0);
                    addHeader(a, st.sectionsHost, sec, accent, items.size(), rails ? chipJump(a, st, sec) : null);
                    if (rails) addRail(a, st, items); else addGrid(a, st, items);
                    shown++;
                }
            }
            if (shown == 0) {
                TextView empty = CigramUI.label(a, searching ? "لا توجد قناة بهذا الاسم" : "لا توجد قنوات حالياً",
                        16f, MUTED, false);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, CigramUI.dp(a, 60), 0, CigramUI.dp(a, 60));
                st.sectionsHost.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            }
        } catch (Throwable error) {
            Log.w(TAG, "render failed", error);
        }
    }

    /** v10.34: with no chips any more, a section opened from «المزيد» gets a way back. */
    private static void addBackToAll(final Activity a, final State st) {
        TextView back = CigramUI.label(a, "›  كل القنوات", 14f, 0xFFBDBDC2, true);
        back.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        back.setPadding(CigramUI.dp(a, 4), CigramUI.dp(a, 8), CigramUI.dp(a, 4), CigramUI.dp(a, 4));
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                st.chip = "";
                render(a, st);
            }
        });
        st.sectionsHost.addView(back, new LinearLayout.LayoutParams(-1, -2));
    }

    /** v5.1: tapping "عرض الكل" on a rail jumps to that section's full grid (same as its chip). */
    private static View.OnClickListener chipJump(final Activity a, final State st, final String chip) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                st.chip = chip;
                render(a, st);
            }
        };
    }

    private static void addHeader(Activity a, LinearLayout host, String title, int accent, int count,
                                  View.OnClickListener onAll) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // physical order, right to left: bar, title, count
        String countText = count + (count == 1 ? " قناة" : " قنوات");
        TextView n = CigramUI.label(a, onAll != null ? "المزيد" : countText,
                13f, onAll != null ? 0xFFBDBDC2 : MUTED, onAll != null);
        n.setBackground(null);     // v10.34: plain text, no pill
        n.setPadding(CigramUI.dp(a, 4), CigramUI.dp(a, 6), CigramUI.dp(a, 4), CigramUI.dp(a, 6));
        if (onAll != null) {
            n.setClickable(true);
            n.setOnClickListener(onAll);
        }
        row.addView(n, new LinearLayout.LayoutParams(-2, -2));
        TextView t = CigramUI.label(a, title, CigramDesign.SECTION_SP - 2f, Color.WHITE, true);
        // Explicitly use the CIGRAM sima.ttf font for channel section headings.
        t.setTypeface(CigramStage1InitProvider.cigramFont(a), android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setPadding(CigramUI.dp(a, 10), 0, CigramUI.dp(a, 10), 0);
        row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        // 2026: no red accent bar beside section titles (same as every other page)
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = CigramUI.dp(a, host.getChildCount() == 0 ? 14 : 24);
        lp.bottomMargin = CigramUI.dp(a, 12);
        host.addView(row, lp);
    }

    private static void addGrid(Activity a, State st, ArrayList<Ch> items) {
        for (int i = 0; i < items.size(); i += 2) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            for (int k = 1; k >= 0; k--) {     // item 0 ends up on the right
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.leftMargin = k == 0 ? CigramUI.dp(a, 6) : 0;
                lp.rightMargin = k == 1 ? CigramUI.dp(a, 6) : 0;
                lp.bottomMargin = CigramUI.dp(a, 14);
                if (i + k < items.size()) row.addView(makeTile(a, st, items.get(i + k), -1), lp);
                else row.addView(new View(a), lp);
            }
            st.sectionsHost.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    /** Rails (RTL): one horizontally scrolling row of 16:9 tiles per section; item 0 on the right. */
    private static void addRail(Activity a, State st, ArrayList<Ch> items) {
        final HorizontalScrollView scroll = new HorizontalScrollView(a);
        scroll.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        final LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.RIGHT);
        int w = CigramUI.dp(a, 172);
        for (int i = items.size() - 1; i >= 0; i--) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, -2);
            lp.leftMargin = CigramUI.dp(a, 12);
            row.addView(makeTile(a, st, items.get(i), w), lp);
        }
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
        final int viewport = a.getResources().getDisplayMetrics().widthPixels - CigramUI.dp(a, 28);
        row.setMinimumWidth(viewport);
        scroll.post(new Runnable() {
            @Override public void run() { scroll.scrollTo(Math.max(0, row.getWidth() - scroll.getWidth()), 0); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.bottomMargin = CigramUI.dp(a, 4);
        st.sectionsHost.addView(scroll, slp);
    }

    /** Height follows the width: 16:9. */
    private static final class Frame169 extends FrameLayout {
        Frame169(Context c) { super(c); }

        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(Math.round(width * 9f / 16f), MeasureSpec.EXACTLY));
        }
    }

    private static void bindPicture(Ch ch, ImageView image, View letter) {
        if (ch.drawable != null) {
            Drawable d = ch.drawable.getConstantState() != null
                    ? ch.drawable.getConstantState().newDrawable().mutate() : ch.drawable;
            image.setImageDrawable(d);
            letter.setVisibility(View.GONE);
        } else {
            loadImage(ch.image, image, letter);
        }
    }

    /**
     * v10.34 channel tile: 16:9 picture (rounded, hairline frame) with a «● مباشر» chip and the
     * heart on it; the channel name and its section are written UNDER the picture.
     */
    private static View makeTile(final Activity a, final State st, final Ch ch, int widthPx) {
        LinearLayout tile = new LinearLayout(a);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setClickable(true);
        tile.setFocusable(true);

        FrameLayout pic = new Frame169(a);
        pic.setBackground(CigramUI.round(CARD, 14, a));
        pic.setClipToOutline(true);
        // 2026: while the channel's own picture loads, a faint Cigram mark holds its place (it is hidden as soon as
        // the real artwork arrives; the channel's picture is never replaced)
        ImageView letter = new ImageView(a);
        letter.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        letter.setImageDrawable(CigramBrand.placeholder(a));
        letter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int markPad = CigramUI.dp(a, 26);
        letter.setPadding(markPad, markPad, markPad, markPad);
        pic.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(a);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription(ch.name);
        pic.addView(image, new FrameLayout.LayoutParams(-1, -1));
        bindPicture(ch, image, letter);
        if (!ch.available) {
            android.graphics.ColorMatrix gray = new android.graphics.ColorMatrix();
            gray.setSaturation(0f);
            image.setColorFilter(new android.graphics.ColorMatrixColorFilter(gray));
        }
        View shade = new View(a);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000A10, 0x00000A10, 0x00000A10}));
        pic.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        TextView badge = CigramUI.label(a, ch.available ? "●  مباشر" : "غير متاح", 10f, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(CigramUI.round(ch.available ? 0xEEE21B14 : 0xCC4A5A63, 7, a));
        badge.setPadding(CigramUI.dp(a, 7), CigramUI.dp(a, 2), CigramUI.dp(a, 7), CigramUI.dp(a, 2));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        bl.setMargins(CigramUI.dp(a, 8), CigramUI.dp(a, 8), 0, 0);
        pic.addView(badge, bl);

        final boolean fav = isFav(a, ch);
        TextView heart = CigramUI.label(a, fav ? "♥" : "♡", 15f, fav ? 0xFFFF5A52 : 0xDDFFFFFF, false);
        heart.setGravity(Gravity.CENTER);
        heart.setBackground(CigramUI.round(0x8C000A10, 14, a));
        heart.setContentDescription("المفضلة");
        heart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                toggleFav(a, ch);
                render(a, st);
            }
        });
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(CigramUI.dp(a, 28), CigramUI.dp(a, 28),
                Gravity.TOP | Gravity.RIGHT);
        hl.setMargins(0, CigramUI.dp(a, 6), CigramUI.dp(a, 6), 0);
        pic.addView(heart, hl);
        CigramDesign.posterFrame(pic, 14f, false);   // channel artwork: frame only, no watermark
        tile.addView(pic, new LinearLayout.LayoutParams(-1, -2));

        TextView name = CigramUI.label(a, ch.name, 14f, ch.available ? Color.WHITE : MUTED, true);
        name.setGravity(Gravity.RIGHT);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextDirection(View.TEXT_DIRECTION_RTL);
        name.setPadding(CigramUI.dp(a, 2), CigramUI.dp(a, 8), CigramUI.dp(a, 2), 0);
        tile.addView(name, new LinearLayout.LayoutParams(-1, -2));
        TextView sub = CigramUI.label(a, ch.section, 11f, MUTED, false);
        sub.setGravity(Gravity.RIGHT);
        sub.setSingleLine(true);
        sub.setPadding(CigramUI.dp(a, 2), CigramUI.dp(a, 1), CigramUI.dp(a, 2), 0);
        tile.addView(sub, new LinearLayout.LayoutParams(-1, -2));

        tile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(a, st, ch); }
        });
        tile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                toggleFav(a, ch);
                CigramSilent.toast(a, isFav(a, ch) ? "أضيفت إلى المفضلة" : "أزيلت من المفضلة", android.widget.Toast.LENGTH_SHORT);
                render(a, st);
                return true;
            }
        });
        press(tile);
        return tile;
    }

    // ------------------------------------------------------------------ card

    private static void press(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                int act = e.getActionMasked();
                // v5.29: no size change on (long) press, only a light dim
                if (act == MotionEvent.ACTION_DOWN) view.setAlpha(0.85f);
                else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) view.setAlpha(1f);
                return false;
            }
        });
    }

    // ------------------------------------------------------------------ images

    private static void loadImage(final String raw, final ImageView target, final View placeholder) {
        final String url = raw == null ? "" : raw.trim();
        target.setTag(url);
        if (url.length() == 0) return;
        Bitmap cached = MEMORY.get(url);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            placeholder.setVisibility(View.GONE);
            return;
        }
        IMAGES.execute(new Runnable() {
            @Override public void run() {
                Bitmap b = fetch(url);
                if (b == null) {
                    String alt = alternate(url);
                    if (alt.length() > 0) b = fetch(alt);
                }
                final Bitmap result = b;
                if (result != null) MEMORY.put(url, result);
                UI.post(new Runnable() {
                    @Override public void run() {
                        if (result != null && url.equals(String.valueOf(target.getTag()))) {
                            target.setImageBitmap(result);
                            placeholder.setVisibility(View.GONE);
                        }
                    }
                });
            }
        });
    }

    private static String alternate(String url) {
        final String oldHost = "https://pub-eabe41d14a0644e5800344c99ef4d971.r2.dev";
        final String newHost = "https://pub-6ce52a4bbf2543ee88ad9883ae42a806.r2.dev";
        if (url.startsWith(oldHost)) return newHost + url.substring(oldHost.length());
        if (url.startsWith(newHost)) return oldHost + url.substring(newHost.length());
        return "";
    }

    private static Bitmap fetch(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "image/*");
            if (c.getResponseCode() / 100 != 2) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 4000000) out.write(buf, 0, n);
            byte[] data = out.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 600) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable error) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
