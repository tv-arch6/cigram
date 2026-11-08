package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * مساحة إعلانية داخل التطبيق: "hero" (بانر دوّار)، "inline" (بطاقة ضمن القوائم)، "sticky" (شريط سفلي قابل للإغلاق)، "sponsor" (شارة رعاية).
 * (splash وpopup في AdsOverlay.)
 *
 * الاستخدام من أي شاشة (Sketchware → Add source directly):
 *     AdSlotView.install(this, linearHome, "hero", 0);          // أعلى الصفحة الرئيسية (index 0)
 *     AdSlotView.install(this, linearHome, "sponsor", 3);
 *     AdSlotView.installSticky(this, 56);                       // شريط سفلي فوق شريط التنقل (56dp هامش سفلي)
 *     row = AdSlotView.create(this, "inline");                  // كصف داخل ListView/RecyclerView/LinearLayout
 *
 * السلوك: لا تأخذ أي حيّز حتى يصل إعلان صالح (تنهار GONE)، والمشاهدة تُحتسب بعد ظهور 50% من المساحة لثانية متواصلة (مرة لكل مادة)،
 * والنقر يُسجَّل ثم يُنفَّذ الإجراء، ولا حاجة لاستدعاء دورة الحياة (يعتمد على attach/detach/نافذة مرئية).
 */
public class AdSlotView extends FrameLayout {

    private static final long TICK_MS = 300L;
    private static final long ROTATE_MS = 5000L;
    private static final long REFRESH_MS = 90000L;
    private static final int HERO_MAX = 6;

    private final String slot;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Rect tmp = new Rect();
    private final Set<String> counted = new HashSet<String>();
    private List<AdsServe.Ad> ads = new ArrayList<AdsServe.Ad>();
    private int index = 0;
    private AdsServe.Ad current;
    private View currentView;
    private LinearLayout dots;
    private boolean attached, windowVisible = true, loading, running;
    private long visibleMs, shownAt, lastLoadAt, lastTouchUp;
    private boolean touching;
    private int loadGen = 0;

    public static AdSlotView create(Context c, String slot) {
        return new AdSlotView(c, slot);
    }

    public static AdSlotView install(Activity a, ViewGroup parent, String slot, int index) {
        AdSlotView v = new AdSlotView(a, slot);
        try {
            if (parent == null) return v;
            LinearLayout.LayoutParams lp = null;
            int m = AdsUi.dp(a, 12);
            if (parent instanceof LinearLayout) {
                lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(m, AdsUi.dp(a, 6), m, AdsUi.dp(a, 6));
                int at = index < 0 || index > parent.getChildCount() ? parent.getChildCount() : index;
                parent.addView(v, at, lp);
            } else {
                parent.addView(v, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
        } catch (Throwable ignored) { }
        return v;
    }

    /** يثبّت شريط "sticky" أسفل الشاشة فوق محتوى النشاط. bottomMarginDp لتجنّب تغطية شريط التنقل السفلي. */
    public static AdSlotView installSticky(Activity a, int bottomMarginDp) {
        AdSlotView v = new AdSlotView(a, "sticky");
        try {
            ViewGroup content = (ViewGroup) a.findViewById(android.R.id.content);
            if (content instanceof FrameLayout) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
                lp.setMargins(AdsUi.dp(a, 8), 0, AdsUi.dp(a, 8), AdsUi.dp(a, bottomMarginDp + 8));
                content.addView(v, lp);
            }
        } catch (Throwable ignored) { }
        return v;
    }

    public AdSlotView(Context c, String slot) {
        super(c);
        this.slot = slot == null ? "inline" : slot;
        setVisibility(GONE);
        setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    }

    // ------------------------------------------------------------------ دورة الحياة

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        counted.clear();
        start();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
        stop();
        loadGen++; // نتجاهل أي رد شبكة متأخر
        loading = false;
        AdsServe.flush(getContext());
    }

    @Override protected void onWindowVisibilityChanged(int v) {
        super.onWindowVisibilityChanged(v);
        windowVisible = v == VISIBLE;
        if (windowVisible) start(); else { stop(); AdsServe.flush(getContext()); }
    }

    private void start() {
        if (CigramPlus.isPlus(getContext())) { setVisibility(GONE); return; }     // Cigram Plus: no in-house ads
        if (!attached || !windowVisible || running) return;
        running = true;
        if (ads.isEmpty() || System.currentTimeMillis() - lastLoadAt > REFRESH_MS) load();
        h.postDelayed(tick, TICK_MS);
    }

    private void stop() {
        running = false;
        h.removeCallbacks(tick);
        visibleMs = 0;
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try {
                checkVisible();
                maybeRotate();
                if (!loading && System.currentTimeMillis() - lastLoadAt > REFRESH_MS) load();
            } catch (Throwable ignored) { }
            if (running) h.postDelayed(this, TICK_MS);
        }
    };

    // ------------------------------------------------------------------ تحميل

    private void load() {
        if (loading || !attached) return;
        loading = true;
        final int gen = ++loadGen;
        final Context c = getContext();
        List<AdsServe.Ad> hit = ads.isEmpty() ? AdsServe.cached(c, slot) : null;
        if (hit != null && !hit.isEmpty()) apply(hit); // عرض فوري من النسخة المحلية ثم تحديث
        AdsServe.load(c, slot, new AdsServe.Cb() {
            @Override public void onAds(List<AdsServe.Ad> eligible) {
                if (gen != loadGen || !attached) return;
                loading = false;
                lastLoadAt = System.currentTimeMillis();
                apply(eligible);
            }
        });
    }

    private void apply(List<AdsServe.Ad> eligible) {
        if (eligible == null || eligible.isEmpty()) {
            ads = new ArrayList<AdsServe.Ad>();
            current = null;
            clear();
            setVisibility(GONE);
            return;
        }
        List<AdsServe.Ad> ordered = AdsServe.order(getContext(), slot, eligible);
        if ("hero".equals(slot) && ordered.size() > HERO_MAX) ordered = new ArrayList<AdsServe.Ad>(ordered.subList(0, HERO_MAX));
        // هل المعروض حالياً لا يزال صالحاً؟ نُبقيه دون وميض
        int keep = -1;
        if (current != null) for (int i = 0; i < ordered.size(); i++) if (ordered.get(i).creativeId.equals(current.creativeId)) { keep = i; break; }
        ads = ordered;
        if (keep >= 0) {
            index = keep;
            buildDots();
            return;
        }
        index = 0;
        AdsServe.advance(getContext(), slot);
        show(ads.get(0), currentView == null);
    }

    // ------------------------------------------------------------------ تدوير البانر

    private void maybeRotate() {
        if (!"hero".equals(slot) || ads.size() < 2 || current == null) return;
        long now = System.currentTimeMillis();
        if (touching || now - lastTouchUp < 3000L || now - shownAt < ROTATE_MS) return;
        index = (index + 1) % ads.size();
        show(ads.get(index), false);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) touching = true;
        else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { touching = false; lastTouchUp = System.currentTimeMillis(); }
        return super.dispatchTouchEvent(e);
    }

    // ------------------------------------------------------------------ المشاهدة

    private void checkVisible() {
        if (current == null || currentView == null || getVisibility() != VISIBLE) { visibleMs = 0; return; }
        boolean vis = false;
        if (isShown() && hasWindowFocus() && getWidth() > 0 && getHeight() > 0 && getGlobalVisibleRect(tmp)) {
            float frac = ((float) tmp.width() * (float) tmp.height()) / ((float) getWidth() * (float) getHeight());
            vis = frac >= 0.5f;
        }
        if (!vis) { visibleMs = 0; return; }
        visibleMs += TICK_MS;
        if (visibleMs >= 1000L && !counted.contains(current.creativeId)) {
            counted.add(current.creativeId);
            AdsServe.impression(getContext(), current);
        }
    }

    // ------------------------------------------------------------------ العرض

    private void clear() {
        removeAllViews();
        currentView = null;
        dots = null;
    }

    private void show(final AdsServe.Ad ad, boolean first) {
        View v;
        try {
            if ("hero".equals(slot)) v = buildHero(ad);
            else if ("sticky".equals(slot)) v = buildSticky(ad);
            else if ("sponsor".equals(slot)) v = buildSponsor(ad);
            else v = buildInline(ad);
        } catch (Throwable t) {
            setVisibility(GONE);
            return;
        }
        final View old = currentView;
        current = ad;
        currentView = v;
        shownAt = System.currentTimeMillis();
        visibleMs = 0;
        setVisibility(VISIBLE);
        boolean anim = AdsUi.animationsOn(getContext());
        addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (anim) {
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(first ? 250 : 350).start();
            if (old != null) {
                old.animate().alpha(0f).setDuration(350).withEndAction(new Runnable() {
                    @Override public void run() { try { removeView(old); } catch (Throwable ignored) { } }
                }).start();
            }
        } else if (old != null) {
            removeView(old);
        }
        buildDots();
    }

    private void buildDots() {
        if (dots != null) { removeView(dots); dots = null; }
        if (!"hero".equals(slot) || ads.size() < 2) return;
        Context c = getContext();
        LinearLayout d = new LinearLayout(c);
        d.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < ads.size(); i++) {
            View dot = new View(c);
            boolean on = i == index;
            dot.setBackground(AdsUi.pill(c, on ? 0xFFFFFFFF : 0x80FFFFFF, 4));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(AdsUi.dp(c, on ? 14 : 6), AdsUi.dp(c, 6));
            lp.setMargins(AdsUi.dp(c, 2), 0, AdsUi.dp(c, 2), 0);
            d.addView(dot, lp);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = AdsUi.dp(c, 6);
        addView(d, lp);
        dots = d;
    }

    private void onHidden() {
        if (current == null) return;
        final String cid = current.campaignId;
        List<AdsServe.Ad> left = new ArrayList<AdsServe.Ad>();
        for (AdsServe.Ad a : ads) if (!a.campaignId.equals(cid)) left.add(a);
        ads = left;
        current = null;
        if (left.isEmpty()) { clear(); setVisibility(GONE); return; }
        index = 0;
        show(left.get(0), false);
    }

    private final Runnable hiddenCb = new Runnable() { @Override public void run() { onHidden(); } };

    private void bindClick(View v, final AdsServe.Ad ad) {
        if (!ad.clickable()) return;
        v.setClickable(true);
        v.setFocusable(true);
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                Activity a = AdsRender.activityOf(getContext());
                if (a != null) AdsServe.perform(a, ad);
            }
        });
    }

    private static void round(View v, final float radiusPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx); }
        });
        v.setClipToOutline(true);
    }

    /** إطار بنسبة عرض/ارتفاع ثابتة. */
    private static final class Ratio extends FrameLayout {
        private final float ratio;
        Ratio(Context c, float ratio) { super(c); this.ratio = ratio; }
        @Override protected void onMeasure(int w, int hSpec) {
            int width = MeasureSpec.getSize(w);
            if (width > 0 && MeasureSpec.getMode(w) != MeasureSpec.UNSPECIFIED) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(Math.round(width * ratio), MeasureSpec.EXACTLY));
            } else {
                super.onMeasure(w, hSpec);
            }
        }
    }

    // ------------------------------------------------------------------ hero

    private View buildHero(AdsServe.Ad ad) {
        Context c = getContext();
        Ratio card = new Ratio(c, 0.46f);
        round(card, AdsUi.dp(c, 16));
        card.addView(AdsRender.media(c, ad, false, 1400, ImageView.ScaleType.CENTER_CROP), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View scrim = new View(c);
        scrim.setBackground(AdsRender.scrim());
        card.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AdsUi.dp(c, 120), Gravity.BOTTOM));
        int fg = ad.isText() ? AdsRender.textOf(ad) : 0xFFFFFFFF;
        LinearLayout t = AdsUi.vbox(c);
        t.setPadding(AdsUi.dp(c, 14), 0, AdsUi.dp(c, 14), AdsUi.dp(c, 26));
        if (ad.title.length() > 0) {
            TextView title = AdsUi.text(c, ad.title, 17f, fg, true);
            title.setMaxLines(2);
            t.addView(title);
        }
        if (ad.body.length() > 0) {
            TextView body = AdsUi.text(c, ad.body, 12.5f, fg, false);
            body.setMaxLines(2);
            t.addView(body, AdsUi.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, AdsUi.dp(c, 2), 0, 0));
        }
        if (ad.cta.length() > 0 && ad.clickable()) {
            TextView cta = AdsRender.cta(c, ad.cta, 0xFFFFFFFF, 0xFF0B2530);
            cta.setMinHeight(AdsUi.dp(c, 40));
            t.addView(cta, AdsUi.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, AdsUi.dp(c, 8), 0, 0));
        }
        card.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        card.addView(AdsRender.label(c, ad, hiddenCb), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
        bindClick(card, ad);
        card.setContentDescription("إعلان: " + ad.title);
        return card;
    }

    // ------------------------------------------------------------------ inline

    private View buildInline(AdsServe.Ad ad) {
        Context c = getContext();
        LinearLayout card = AdsUi.vbox(c);
        CigramGlass.bind(card, 16, true);
        round(card, AdsUi.dp(c, 16));
        if (!ad.isText()) {
            Ratio m = new Ratio(c, 0.5625f);
            m.addView(AdsRender.media(c, ad, true, 1100, ImageView.ScaleType.CENTER_CROP), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            m.addView(AdsRender.label(c, ad, hiddenCb), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
            card.addView(m, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout t = AdsUi.vbox(c);
        t.setPadding(AdsUi.dp(c, 14), AdsUi.dp(c, 10), AdsUi.dp(c, 14), AdsUi.dp(c, 12));
        if (ad.isText()) {
            t.setBackgroundColor(AdsRender.bgOf(ad));
            LinearLayout top = AdsUi.hbox(c);
            top.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
            top.addView(AdsRender.label(c, ad, hiddenCb));
            t.addView(top);
        }
        int fg = ad.isText() ? AdsRender.textOf(ad) : CigramGlass.text(c);
        int fgMuted = ad.isText() ? AdsRender.textOf(ad) : CigramGlass.muted(c);
        if (ad.title.length() > 0) {
            TextView title = AdsUi.text(c, ad.title, 16f, fg, true);
            title.setMaxLines(2);
            t.addView(title);
        }
        if (ad.body.length() > 0) {
            TextView body = AdsUi.text(c, ad.body, 13f, fgMuted, false);
            body.setMaxLines(3);
            t.addView(body, AdsUi.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, AdsUi.dp(c, 3), 0, 0));
        }
        if (ad.cta.length() > 0 && ad.clickable()) {
            TextView cta = AdsRender.cta(c, ad.cta, ad.isText() ? 0xFFFFFFFF : CigramUI.TEAL, 0xFF04161D);
            t.addView(cta, AdsUi.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, AdsUi.dp(c, 8), 0, 0));
        }
        card.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bindClick(card, ad);
        card.setContentDescription("إعلان: " + ad.title);
        return card;
    }

    // ------------------------------------------------------------------ sticky

    private View buildSticky(final AdsServe.Ad ad) {
        final Context c = getContext();
        LinearLayout bar = AdsUi.hbox(c);
        bar.setMinimumHeight(AdsUi.dp(c, 56));
        bar.setPadding(AdsUi.dp(c, 4), AdsUi.dp(c, 4), AdsUi.dp(c, 4), AdsUi.dp(c, 4));
        bar.setBackground(AdsUi.pill(c, ad.isText() ? AdsRender.bgOf(ad) : (CigramGlass.isLight(c) ? 0xFFFFFFFF : 0xFF12303B), 14));
        bar.setElevation(AdsUi.dp(c, 6));
        int fg = ad.isText() ? AdsRender.textOf(ad) : CigramGlass.text(c);
        if (!ad.isText() && ad.posterUrl().length() > 0) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            round(iv, AdsUi.dp(c, 10));
            bar.addView(iv, AdsUi.lp(AdsUi.dp(c, 44), AdsUi.dp(c, 44), AdsUi.dp(c, 6), 0, AdsUi.dp(c, 6), 0));
            AdsMedia.loadInto(c, iv, ad.posterUrl(), 200, ad.posterIsVideoFrame());
        }
        LinearLayout texts = AdsUi.vbox(c);
        TextView title = AdsUi.text(c, ad.title.length() > 0 ? ad.title : "إعلان", 14f, fg, true);
        title.setMaxLines(1);
        texts.addView(title);
        if (ad.body.length() > 0) {
            TextView b = AdsUi.text(c, ad.body, 11.5f, ad.isText() ? fg : CigramGlass.muted(c), false);
            b.setMaxLines(1);
            texts.addView(b);
        }
        bar.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (ad.cta.length() > 0 && ad.clickable()) {
            TextView cta = AdsRender.cta(c, ad.cta, CigramUI.TEAL, 0xFF04161D);
            bar.addView(cta, AdsUi.lp(ViewGroup.LayoutParams.WRAP_CONTENT, AdsUi.dp(c, 44), AdsUi.dp(c, 6), 0, AdsUi.dp(c, 2), 0));
        }
        View label = AdsRender.label(c, ad, hiddenCb);
        bar.addView(label);
        TextView close = AdsUi.text(c, "✕", 16f, ad.isText() ? fg : CigramGlass.muted(c), true);
        close.setGravity(Gravity.CENTER);
        close.setMinWidth(AdsUi.dp(c, 48));
        close.setMinHeight(AdsUi.dp(c, 48));
        close.setClickable(true);
        close.setContentDescription("إغلاق الإعلان");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                AdsServe.hide(c, ad.campaignId, 6L * 3600000L);
                onHidden();
            }
        });
        bar.addView(close);
        bindClick(texts, ad);
        return bar;
    }

    // ------------------------------------------------------------------ sponsor

    private View buildSponsor(AdsServe.Ad ad) {
        Context c = getContext();
        LinearLayout row = AdsUi.hbox(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(AdsUi.dp(c, 48));
        row.setPadding(AdsUi.dp(c, 12), AdsUi.dp(c, 4), AdsUi.dp(c, 4), AdsUi.dp(c, 4));
        row.setBackground(AdsUi.pill(c, CigramGlass.soft(c), 24));
        TextView lead = AdsUi.text(c, "برعاية", 12f, CigramGlass.muted(c), false);
        row.addView(lead, AdsUi.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, AdsUi.dp(c, 8), 0));
        if (!ad.isText() && ad.posterUrl().length() > 0) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            round(iv, AdsUi.dp(c, 12));
            row.addView(iv, AdsUi.lp(AdsUi.dp(c, 24), AdsUi.dp(c, 24), 0, 0, AdsUi.dp(c, 8), 0));
            AdsMedia.loadInto(c, iv, ad.posterUrl(), 96, ad.posterIsVideoFrame());
        }
        TextView t = AdsUi.text(c, ad.title.length() > 0 ? ad.title : "راعٍ رسمي", 13.5f, CigramGlass.text(c), true);
        t.setMaxLines(1);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(AdsRender.label(c, ad, hiddenCb));
        bindClick(row, ad);
        return row;
    }
}
