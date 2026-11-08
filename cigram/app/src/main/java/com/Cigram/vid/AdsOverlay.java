package com.Cigram.vid;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Calendar;
import java.util.List;

/**
 * الإعلانات الكاملة/المنبثقة:
 *  - splash: ملء الشاشة عند فتح التطبيق، عدّاد 5 ثوانٍ، زر «تخطي» يظهر بعد ثانيتين. يُعرض مرة واحدة لكل تشغيل وبفاصل 20 دقيقة على الأقل.
 *    إن لم تتوفر نسخة محلية ولم يصل الرد خلال 2.5 ثانية يُلغى العرض (لا نؤخّر التطبيق ولا نقاطع المستخدم بعد أن بدأ).
 *  - popup: نافذة مرة واحدة يومياً لكل جهاز، تظهر بعد 1.5 ثانية من الاستدعاء، وزر إغلاق واضح 48dp.
 * الاستدعاء من النشاط الرئيسي (Sketchware → Add source directly داخل onCreate بعد بناء الواجهة):
 *     AdsOverlay.onAppOpen(this);
 * يستدعي splash ثم popup بالتتابع (popup فقط إن لم يظهر splash).
 */
public final class AdsOverlay {

    private static final String PREFS = "cigram_ads_overlay";
    private static final int SPLASH_SECONDS = 5;
    private static final int SKIP_AFTER = 2;
    private static final long SPLASH_GAP_MS = 20L * 60000L;
    private static final long SPLASH_WAIT_MS = 2500L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean splashShownThisProcess = false;
    private static boolean busy = false;
    private static long busySince;
    /**
     * A dialog closed by its Activity being destroyed never calls its dismiss listener, so
     * "busy" stayed true and no in-house ad (splash, popup, episode break) was shown again until
     * the process died. Older than this, the flag is treated as stale.
     */
    private static final long BUSY_STALE_MS = 3L * 60L * 1000L;

    private static void markBusy() {
        busy = true;
        busySince = System.currentTimeMillis();
    }

    private static boolean isBusy() {
        if (busy && System.currentTimeMillis() - busySince > BUSY_STALE_MS) busy = false;
        return busy;
    }

    private AdsOverlay() { }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static long dayNumber() {
        Calendar cal = Calendar.getInstance();
        return (System.currentTimeMillis() + cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 86400000L;
    }

    public static void onAppOpen(final Activity a) {
        try {
            if (a == null || isBusy() || CigramPlus.isPlus(a)) return;       // Cigram Plus: no splash / popup
            boolean splashDue = !splashShownThisProcess && System.currentTimeMillis() - sp(a).getLong("splash_at", 0L) >= SPLASH_GAP_MS;
            // v10.35: إعلان AdMob عند فتح التطبيق (مرة كل 4 ساعات): يبدأ تحميله الآن بالتوازي مع قرار الـ splash.
            // إذا كان هناك splash داخلي محفوظ محلياً فسيظهر هو بالتأكيد، فلا نرسل طلب AdMob بلا فائدة.
            try {
                List<AdsServe.Ad> cachedSplash = splashDue ? AdsServe.cached(a, "splash") : null;
                if (cachedSplash != null && !cachedSplash.isEmpty()) CigramAppOpenAd.skipThisLaunch();
                else CigramAppOpenAd.preload(a);
            } catch (Throwable ignored) { }
            if (splashDue) {
                splashShownThisProcess = true; // محاولة واحدة لكل تشغيل
                splash(a, new Runnable() {
                    @Override public void run() { appOpenAdOrPopup(a, 600L); }
                });
            } else {
                appOpenAdOrPopup(a, 1500L);
            }
        } catch (Throwable ignored) { }
    }

    /**
     * v10.35: لم يظهر splash داخلي هذه المرة => إعلان AdMob للفتح إن كان مستحقاً (مرة كل 4 ساعات).
     * الـ popup اليومي يظهر فقط إذا لم يظهر إعلان AdMob، حتى لا يأتي إعلانان وراء بعض عند الفتح.
     * الـ splash الداخلي (المدفوع من المعلنين) له الأولوية دائماً.
     */
    private static void appOpenAdOrPopup(final Activity a, final long popupDelay) {
        try {
            CigramAppOpenAd.showOrSkip(a, new Runnable() {
                @Override public void run() { if (AdsUi.alive(a)) popupLater(a, popupDelay); }
            });
        } catch (Throwable t) {
            popupLater(a, popupDelay);
        }
    }

    // ------------------------------------------------------------------ splash

    private static void splash(final Activity a, final Runnable whenNoSplash) {
        markBusy();
        final long started = System.currentTimeMillis();
        final boolean[] decided = {false};
        List<AdsServe.Ad> hit = AdsServe.cached(a, "splash");
        if (hit != null && !hit.isEmpty()) {
            decided[0] = true;
            showSplash(a, AdsServe.order(a, "splash", hit).get(0), whenNoSplash);
            AdsServe.advance(a, "splash");
            AdsServe.load(a, "splash", new AdsServe.Cb() { @Override public void onAds(List<AdsServe.Ad> e) { /* تحديث النسخة المحلية فقط */ } });
            return;
        }
        AdsServe.load(a, "splash", new AdsServe.Cb() {
            @Override public void onAds(List<AdsServe.Ad> eligible) {
                if (decided[0]) return;
                decided[0] = true;
                if (eligible == null || eligible.isEmpty() || !AdsUi.alive(a) || System.currentTimeMillis() - started > SPLASH_WAIT_MS) {
                    busy = false;
                    if (AdsUi.alive(a)) whenNoSplash.run();
                    return;
                }
                showSplash(a, AdsServe.order(a, "splash", eligible).get(0), whenNoSplash);
                AdsServe.advance(a, "splash");
            }
        });
    }

    private static void showSplash(final Activity a, final AdsServe.Ad ad, final Runnable whenNoSplash) {
        showSplash(a, ad, whenNoSplash, null);
    }

    private static void showSplash(final Activity a, final AdsServe.Ad ad, final Runnable whenNoSplash, final Runnable onClosed) {
        if (!AdsUi.alive(a)) { busy = false; if (onClosed != null) onClosed.run(); return; }
        try {
            final Dialog d = new Dialog(a);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            d.setCancelable(false);
            final FrameLayout root = new FrameLayout(a);
            root.setBackgroundColor(0xFF000000);
            root.addView(AdsRender.media(a, ad, true, 1600, ImageView.ScaleType.CENTER_CROP), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View scrim = new View(a);
            scrim.setBackground(AdsRender.scrim());
            root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, AdsUi.dp(a, 260), Gravity.BOTTOM));

            int fg = ad.isText() ? AdsRender.textOf(ad) : 0xFFFFFFFF;
            LinearLayout t = AdsUi.vbox(a);
            t.setPadding(AdsUi.dp(a, 24), 0, AdsUi.dp(a, 24), AdsUi.dp(a, 40));
            if (ad.title.length() > 0) {
                TextView title = AdsUi.text(a, ad.title, 24f, fg, true);
                title.setMaxLines(3);
                t.addView(title);
            }
            if (ad.body.length() > 0) {
                TextView body = AdsUi.text(a, ad.body, 15f, fg, false);
                body.setMaxLines(4);
                t.addView(body, AdsUi.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, AdsUi.dp(a, 6), 0, 0));
            }
            if (ad.clickable()) {
                TextView cta = AdsRender.cta(a, ad.cta.length() > 0 ? ad.cta : "اعرف المزيد", 0xFFFFFFFF, 0xFF0B2530);
                t.addView(cta, AdsUi.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, AdsUi.dp(a, 16), 0, 0));
                cta.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { AdsServe.perform(a, ad); }
                });
            }
            root.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

            FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            ll.topMargin = AdsUi.dp(a, 24);
            root.addView(AdsRender.label(a, ad, new Runnable() {
                @Override public void run() { try { d.dismiss(); } catch (Throwable ignored) { } }
            }), ll);

            final TextView skip = AdsUi.text(a, "تخطي بعد " + (SPLASH_SECONDS - 0) + "", 14f, 0xFFFFFFFF, true);
            skip.setGravity(Gravity.CENTER);
            skip.setMinHeight(AdsUi.dp(a, 48));
            skip.setMinWidth(AdsUi.dp(a, 96));
            skip.setPadding(AdsUi.dp(a, 16), AdsUi.dp(a, 8), AdsUi.dp(a, 16), AdsUi.dp(a, 8));
            skip.setBackground(AdsUi.pill(a, 0x99000000, 24));
            FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            sl.topMargin = AdsUi.dp(a, 24);
            sl.leftMargin = AdsUi.dp(a, 12);
            sl.rightMargin = AdsUi.dp(a, 12);
            root.addView(skip, sl);

            final boolean[] closed = {false};
            final Runnable[] tickRef = new Runnable[1];
            final int[] left = {SPLASH_SECONDS};
            final boolean[] counted = {false};
            d.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface di) {
                    closed[0] = true;
                    MAIN.removeCallbacks(tickRef[0]);
                    busy = false;
                    AdsServe.flush(a);
                    if (onClosed != null) { onClosed.run(); return; }
                    if (AdsUi.alive(a)) popupLater(a, 800L);
                }
            });
            tickRef[0] = new Runnable() {
                @Override public void run() {
                    if (closed[0]) return;
                    int elapsed = SPLASH_SECONDS - left[0];
                    if (elapsed >= 1 && !counted[0]) { counted[0] = true; AdsServe.impression(a, ad); }
                    if (elapsed >= SKIP_AFTER) {
                        skip.setText("تخطي ✕");
                        skip.setClickable(true);
                        skip.setContentDescription("تخطي الإعلان");
                        skip.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) { try { d.dismiss(); } catch (Throwable ignored) { } }
                        });
                        d.setCancelable(true);
                    } else {
                        skip.setText("تخطي بعد " + (SKIP_AFTER - elapsed));
                    }
                    if (left[0] <= 0) { try { d.dismiss(); } catch (Throwable ignored) { } return; }
                    left[0]--;
                    MAIN.postDelayed(this, 1000L);
                }
            };
            d.setContentView(root);
            Window w = d.getWindow();
            if (w != null) {
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
                w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
            d.show();
            if (onClosed == null) sp(a).edit().putLong("splash_at", System.currentTimeMillis()).apply();
            MAIN.post(tickRef[0]);
        } catch (Throwable t) {
            busy = false;
            if (onClosed != null) onClosed.run();
        }
    }

    /**
     * Full-screen admin-app ad shown between two episodes. Uses only what is already cached
     * locally (never waits on the network): returns false when there is nothing to show, and
     * then onClosed is NOT called. When it returns true, onClosed runs once the ad is closed.
     */
    public static boolean showBreak(final Activity a, final Runnable onClosed) {
        try {
            if (a == null || isBusy() || !AdsUi.alive(a) || CigramPlus.isPlus(a)) return false;   // Plus: no break ads
            List<AdsServe.Ad> hit = AdsServe.cached(a, "splash");
            String slot = "splash";
            if (hit == null || hit.isEmpty()) { hit = AdsServe.cached(a, "popup"); slot = "popup"; }
            if (hit == null || hit.isEmpty()) {
                // warm the local copy so the next break has something to show
                AdsServe.load(a, "splash", new AdsServe.Cb() { @Override public void onAds(List<AdsServe.Ad> e) { } });
                return false;
            }
            markBusy();
            final AdsServe.Ad ad = AdsServe.order(a, slot, hit).get(0);
            AdsServe.advance(a, slot);
            showSplash(a, ad, new Runnable() { @Override public void run() { } }, onClosed);
            return true;
        } catch (Throwable t) {
            busy = false;
            return false;
        }
    }

    // ------------------------------------------------------------------ popup

    private static void popupLater(final Activity a, long delay) {
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                if (!AdsUi.alive(a) || isBusy()) return;
                if (sp(a).getLong("popup_day", -1L) == dayNumber()) return;
                markBusy();
                AdsServe.load(a, "popup", new AdsServe.Cb() {
                    @Override public void onAds(List<AdsServe.Ad> eligible) {
                        if (eligible == null || eligible.isEmpty() || !AdsUi.alive(a)) { busy = false; return; }
                        AdsServe.Ad ad = AdsServe.order(a, "popup", eligible).get(0);
                        AdsServe.advance(a, "popup");
                        showPopup(a, ad);
                    }
                });
            }
        }, delay);
    }

    private static void showPopup(final Activity a, final AdsServe.Ad ad) {
        try {
            final Dialog d = new Dialog(a);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            d.setCancelable(true);
            d.setCanceledOnTouchOutside(false);
            LinearLayout card = AdsUi.vbox(a);
            CigramGlass.bind(card, 20, true);
            card.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            FrameLayout top = new FrameLayout(a);
            if (!ad.isText()) {
                FrameLayout m = new FrameLayout(a) {
                    @Override protected void onMeasure(int w, int h) {
                        int width = MeasureSpec.getSize(w);
                        super.onMeasure(w, MeasureSpec.makeMeasureSpec(Math.round(width * 0.75f), MeasureSpec.EXACTLY));
                    }
                };
                m.addView(AdsRender.media(a, ad, true, 1200, ImageView.ScaleType.CENTER_CROP), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                top.addView(m, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                top.setBackgroundColor(AdsRender.bgOf(ad));
                top.setMinimumHeight(AdsUi.dp(a, 96));
            }
            top.addView(AdsRender.label(a, ad, new Runnable() {
                @Override public void run() { try { d.dismiss(); } catch (Throwable ignored) { } }
            }), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
            TextView close = AdsUi.text(a, "✕", 18f, 0xFFFFFFFF, true);
            close.setGravity(Gravity.CENTER);
            close.setMinWidth(AdsUi.dp(a, 48));
            close.setMinHeight(AdsUi.dp(a, 48));
            close.setBackground(AdsUi.pill(a, 0x99000000, 24));
            close.setClickable(true);
            close.setContentDescription("إغلاق الإعلان");
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { try { d.dismiss(); } catch (Throwable ignored) { } }
            });
            FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            cl.setMargins(AdsUi.dp(a, 6), AdsUi.dp(a, 6), AdsUi.dp(a, 6), 0);
            top.addView(close, cl);
            card.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout t = AdsUi.vbox(a);
            t.setPadding(AdsUi.dp(a, 18), AdsUi.dp(a, 14), AdsUi.dp(a, 18), AdsUi.dp(a, 18));
            if (ad.title.length() > 0) {
                TextView title = AdsUi.text(a, ad.title, 19f, CigramGlass.text(a), true);
                title.setMaxLines(3);
                t.addView(title);
            }
            if (ad.body.length() > 0) {
                TextView body = AdsUi.text(a, ad.body, 14f, CigramGlass.muted(a), false);
                body.setMaxLines(5);
                t.addView(body, AdsUi.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, AdsUi.dp(a, 6), 0, 0));
            }
            if (ad.clickable()) {
                TextView cta = AdsRender.cta(a, ad.cta.length() > 0 ? ad.cta : "اعرف المزيد", CigramUI.TEAL, 0xFF04161D);
                cta.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { AdsServe.perform(a, ad); try { d.dismiss(); } catch (Throwable ignored) { } }
                });
                t.addView(cta, AdsUi.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, AdsUi.dp(a, 14), 0, 0));
            }
            card.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final Runnable imp = new Runnable() {
                @Override public void run() { if (d.isShowing()) AdsServe.impression(a, ad); }
            };
            d.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface di) {
                    MAIN.removeCallbacks(imp);
                    busy = false;
                    AdsServe.flush(a);
                }
            });
            d.setContentView(card);
            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                w.setLayout((int) (a.getResources().getDisplayMetrics().widthPixels * 0.9f), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            d.show();
            sp(a).edit().putLong("popup_day", dayNumber()).apply();
            MAIN.postDelayed(imp, 1000L);
        } catch (Throwable t) {
            busy = false;
        }
    }
}
