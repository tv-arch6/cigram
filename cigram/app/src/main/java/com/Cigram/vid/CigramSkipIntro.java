package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * v10.62: «تخطي المقدمة» + بطاقة «الحلقة التالية» داخل مشغل المسلسلات.
 *
 * من أين نعرف مكان المقدمة؟ (بالأولوية)
 *   1) بيانات الإدارة في الحلقة أو المسلسل: intro_start / intro_end / credits_start  (ثوانٍ أو mm:ss)
 *   2) الخادم (GET /intro): وسيط ما تخطّاه المشاهدون في نفس الموسم (يحتاج مشاهدَين على الأقل)
 *   3) هذا الجهاز: عندما يقفز المستخدم بنفسه للأمام 20-200 ثانية في أول 10 دقائق، نتذكر ذلك للموسم
 *      ونرسله للخادم حتى يستفيد الباقون.
 *
 * بطاقة الحلقة التالية تظهر قبل نهاية الحلقة بـ 30 ثانية (أو عند credits_start)، مع عد تنازلي 10 ثوانٍ
 * إذا كان «بدء الحلقة التالية تلقائياً» مفعلاً، وزر «إلغاء». الانتقال يمر بنفس مسار المشغل (الإعلان ثم الحلقة).
 *
 * لا يغيّر شيئاً في المشغل إذا فشل: كل نداء محاط بـ try/catch في المشغل وهنا.
 */
public final class CigramSkipIntro {

    public interface Host {
        void seekTo(long ms);
        boolean isSeries();
        boolean hasNext();
        void playNext();
        String seriesTitle();
        String seriesId();
        String season();
        String episode();
    }

    private static final String PREFS = "cigram_skip_intro_v1";
    private static final String PLAYER_PREFS = "cigram_player_prefs";
    private static final String KEY_AUTO_SKIP = "auto_skip_intro";
    private static final long SERVER_TTL_MS = 12L * 60L * 60L * 1000L;
    private static final long NEXT_BEFORE_END_MS = 30000L;
    private static final int COUNTDOWN_S = 10;

    private static final int SRC_NONE = 0, SRC_ADMIN = 1, SRC_SERVER = 2, SRC_LOCAL = 3;

    private final Activity activity;
    private final FrameLayout root;
    private final Host host;
    private final boolean enabled;

    private String currentKey = "";
    private String introKey = "";
    private int ticks;
    private long introStart = -1L, introEnd = -1L, creditsAt = -1L;
    private int introSource = SRC_NONE;
    private int localCount;
    private boolean autoSkipped;

    private long lastPos = -1L, lastTickAt = 0L, naturalMs = 0L;
    private long burstStart = -1L, burstEnd = -1L, burstLastAt = 0L;
    private long ignoreJumpsUntil = 0L;

    private boolean nextDismissed;
    private long countdownStartAt = -1L;

    private TextView skipButton;
    private LinearLayout nextCard;
    /** v10.66: «شاهد الآن» of the next-episode card (the TV remote goes to it). */
    private View tvNow;
    private TextView nextCountdown;
    private Ring nextRing;

    private CigramSkipIntro(Activity a, FrameLayout root, Host host) {
        this.activity = a;
        this.root = root;
        this.host = host;
        this.enabled = a != null && root != null && host != null;
    }

    public static CigramSkipIntro attach(Activity a, FrameLayout root, Host host) {
        return new CigramSkipIntro(a, root, host);
    }

    // ------------------------------------------------------------------ settings

    public static boolean autoSkipEnabled(Context c) {
        try {
            return c != null && c.getApplicationContext().getSharedPreferences(PLAYER_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_AUTO_SKIP, false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void setAutoSkip(Context c, boolean on) {
        try {
            c.getApplicationContext().getSharedPreferences(PLAYER_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_AUTO_SKIP, on).apply();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ tick (every 250 ms from updateProgress)

    public void tick(long pos, long dur, boolean playing, boolean dragging) {
        if (!enabled) return;
        try {
            if (activity.isFinishing()) return;
            if (Build.VERSION.SDK_INT >= 24 && activity.isInPictureInPictureMode()) {
                hideSkip();
                hideNext();
                return;
            }
            if (!host.isSeries()) {
                hideSkip();
                hideNext();
                return;
            }
            String key = host.seriesTitle() + "|" + host.season() + "|" + host.episode();
            if (!key.equals(currentKey)) reset(key);
            ticks++;
            long now = SystemClock.elapsedRealtime();

            learn(pos, playing, dragging, now);
            updateSkip(pos, playing, dragging);
            updateNext(pos, dur, playing, now);

            lastPos = pos;
            lastTickAt = now;
        } catch (Throwable error) {
            android.util.Log.w("CigramSkipIntro", "tick failed", error);
        }
    }

    private void reset(String key) {
        currentKey = key;
        ticks = 0;
        introStart = introEnd = creditsAt = -1L;
        introSource = SRC_NONE;
        localCount = 0;
        autoSkipped = false;
        lastPos = -1L;
        naturalMs = 0L;
        burstStart = burstEnd = -1L;
        nextDismissed = false;
        countdownStartAt = -1L;
        hideSkip();
        hideNext();

        int season = toInt(host.season());
        String sKey = CigramContent.seriesKeyOfTitle(host.seriesTitle());
        introKey = sKey.length() == 0 ? "" : sKey + "|" + (season <= 0 ? 1 : season);

        readAdmin();
        if (introSource == SRC_NONE) readServerCache();
        if (introSource == SRC_NONE) readLocal();
        if (introSource != SRC_ADMIN) fetchServer();
    }

    // ------------------------------------------------------------------ sources

    private void readAdmin() {
        try {
            JSONObject series = CigramContent.find("series", host.seriesId(), host.seriesTitle());
            if (series == null) return;
            int season = toInt(host.season());
            int episode = toInt(host.episode());
            JSONObject ep = null;
            JSONArray eps = CigramStage1InitProvider.castEpisodes(series);
            for (int i = 0; eps != null && i < eps.length(); i++) {
                JSONObject e = eps.optJSONObject(i);
                if (e == null) continue;
                int s = toInt(CigramContent.text(e, "season", "season_number"));
                int n = toInt(CigramContent.text(e, "episode", "episode_number", "number"));
                if ((s <= 0 ? 1 : s) == (season <= 0 ? 1 : season) && n == episode) { ep = e; break; }
            }
            long[] range = range(ep);
            if (range == null) range = range(series);
            if (range != null) {
                introStart = range[0];
                introEnd = range[1];
                introSource = SRC_ADMIN;
            }
            long credits = seconds(ep == null ? "" : CigramContent.text(ep, "credits_start", "outro_start", "end_credits"));
            if (credits <= 0L) credits = seconds(CigramContent.text(series, "credits_start", "outro_start"));
            creditsAt = credits > 0L ? credits : -1L;
        } catch (Throwable ignored) { }
    }

    private static long[] range(JSONObject o) {
        if (o == null) return null;
        long s = seconds(CigramContent.text(o, "intro_start", "skip_intro_start"));
        long e = seconds(CigramContent.text(o, "intro_end", "skip_intro_end", "intro"));
        if (e > 0L && s < 0L) s = 0L;
        if (e <= 0L || e <= s) return null;
        return new long[]{Math.max(0L, s), e};
    }

    private SharedPreferences prefs() {
        return activity.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void readLocal() {
        if (introKey.length() == 0) return;
        String raw = prefs().getString("L|" + introKey, "");
        long[] v = parse(raw);
        if (v == null || v.length < 3) return;
        introStart = v[0];
        introEnd = v[1];
        localCount = (int) v[2];
        introSource = SRC_LOCAL;
    }

    private void readServerCache() {
        if (introKey.length() == 0) return;
        long[] v = parse(prefs().getString("S|" + introKey, ""));
        if (v == null || v.length < 3 || v[2] < 2) return;
        introStart = v[0];
        introEnd = v[1];
        introSource = SRC_SERVER;
    }

    private void fetchServer() {
        if (introKey.length() == 0) return;
        final Context app = activity.getApplicationContext();
        if (!CigramApi.has(app, "intro")) {
            CigramApi.refreshFeatures(app);
            return;
        }
        final String key = introKey;
        long at = prefs().getLong("T|" + key, 0L);
        if (System.currentTimeMillis() - at < SERVER_TTL_MS) return;
        prefs().edit().putLong("T|" + key, System.currentTimeMillis()).apply();
        CigramApi.IO.execute(new Runnable() {
            @Override public void run() {
                JSONObject r = CigramApi.get("/intro?key=" + CigramApi.enc(key));
                if (r == null || !r.optBoolean("ok", false)) return;
                int n = r.optInt("n", 0);
                if (n < 2 || !r.has("end")) return;
                final long s = r.optLong("start", 0L) * 1000L;
                final long e = r.optLong("end", 0L) * 1000L;
                prefs().edit().putString("S|" + key, s + "," + e + "," + n).apply();
                activity.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!key.equals(introKey)) return;
                        if (introSource == SRC_NONE || introSource == SRC_LOCAL) {
                            introStart = s;
                            introEnd = e;
                            introSource = SRC_SERVER;
                        }
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ learning from the viewer's own skips

    private void learn(long pos, boolean playing, boolean dragging, long now) {
        if (lastPos < 0L || dragging) return;
        long delta = pos - lastPos;
        long dt = Math.max(1L, now - lastTickAt);
        boolean natural = delta >= -600L && delta <= dt * 3L + 600L;
        if (natural) {
            if (playing && delta > 0L) naturalMs += delta;
        } else if (now >= ignoreJumpsUntil && ticks > 16) {
            if (delta > 3000L) {
                if (burstStart < 0L) {
                    if (naturalMs >= 1500L || lastPos <= 3000L) {
                        burstStart = lastPos;
                        burstEnd = pos;
                        burstLastAt = now;
                    }
                } else {
                    burstEnd = pos;
                    burstLastAt = now;
                }
            } else if (delta < -3000L) {
                if (burstStart >= 0L && pos > burstStart) {   // small overshoot corrected backwards
                    burstEnd = pos;
                    burstLastAt = now;
                } else {
                    burstStart = burstEnd = -1L;
                }
            }
            naturalMs = 0L;
        }
        if (burstStart >= 0L && now - burstLastAt > 3500L) {
            long len = burstEnd - burstStart;
            if (burstStart <= 600000L && len >= 20000L && len <= 200000L
                    && (introSource == SRC_NONE || introSource == SRC_LOCAL)) {
                remember(burstStart, burstEnd);
            }
            burstStart = burstEnd = -1L;
        }
    }

    private void remember(long start, long end) {
        if (introKey.length() == 0) return;
        try {
            long[] old = parse(prefs().getString("L|" + introKey, ""));
            int count = 1;
            if (old != null && old.length >= 3 && Math.abs(old[1] - end) <= 10000L) {
                count = (int) Math.min(50, old[2] + 1);
                start = (old[0] + start) / 2L;
                end = (old[1] + end) / 2L;
            }
            prefs().edit().putString("L|" + introKey, start + "," + end + "," + count).apply();
            if (introSource == SRC_NONE || introSource == SRC_LOCAL) {
                introStart = start;
                introEnd = end;
                localCount = count;
                introSource = SRC_LOCAL;
            }
            final Context app = activity.getApplicationContext();
            if (!CigramApi.has(app, "intro")) return;
            final JSONObject body = new JSONObject();
            body.put("key", introKey);
            body.put("start", start / 1000L);
            body.put("end", end / 1000L);
            body.put("client_id", CigramApi.clientId(app));
            CigramApi.IO.execute(new Runnable() {
                @Override public void run() { CigramApi.post("/intro", body, null); }
            });
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ «تخطي المقدمة»

    private void updateSkip(long pos, boolean playing, boolean dragging) {
        if (introEnd <= 0L || dragging) {
            hideSkip();
            return;
        }
        boolean inWindow = pos >= Math.max(0L, introStart - 2000L) && pos < introEnd - 3000L;
        if (!inWindow) {
            hideSkip();
            return;
        }
        boolean trusted = introSource == SRC_ADMIN || introSource == SRC_SERVER || localCount >= 2;
        if (trusted && playing && !autoSkipped && autoSkipEnabled(activity) && pos >= introStart) {
            autoSkipped = true;
            skip();
            try { Toast.makeText(activity, "تم تخطي المقدمة", Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { }
            return;
        }
        showSkip();
    }

    private void skip() {
        ignoreJumpsUntil = SystemClock.elapsedRealtime() + 2500L;
        burstStart = burstEnd = -1L;
        host.seekTo(introEnd);
        hideSkip();
        try { CigramAnalytics.track(activity, "intro_skipped", "source", String.valueOf(introSource)); } catch (Throwable ignored) { }
    }

    private void showSkip() {
        if (skipButton == null) {
            skipButton = new TextView(activity);
            skipButton.setText("تخطي المقدمة  ⏭");
            skipButton.setTextColor(0xFFFFFFFF);
            skipButton.setTextSize(15f);
            skipButton.setTypeface(CigramUI.font(activity), android.graphics.Typeface.BOLD);
            skipButton.setGravity(Gravity.CENTER);
            skipButton.setPadding(CigramUI.dp(activity, 20), CigramUI.dp(activity, 11), CigramUI.dp(activity, 20), CigramUI.dp(activity, 11));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xD90B1F29);
            bg.setCornerRadius(CigramUI.dp(activity, 24));
            bg.setStroke(Math.max(2, CigramUI.dp(activity, 1.5f)), 0xFFFFFFFF);
            skipButton.setBackground(CigramGlass.ripple(activity, bg, 24));
            skipButton.setClickable(true);
            skipButton.setFocusable(true);
            skipButton.setElevation(CigramUI.dp(activity, 8));
            skipButton.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { skip(); }
            });
            root.addView(skipButton, cornerLp());
        }
        if (skipButton.getParent() == null) root.addView(skipButton, cornerLp());
        skipButton.bringToFront();
        if (skipButton.getVisibility() != View.VISIBLE) {
            skipButton.setAlpha(0f);
            skipButton.setVisibility(View.VISIBLE);
            skipButton.animate().alpha(1f).setDuration(180L).start();
            CigramTvKeys.prompt(skipButton);   // v10.66: on a TV the remote goes to it (OK = skip)
        }
    }

    private void hideSkip() {
        if (skipButton != null && skipButton.getVisibility() == View.VISIBLE) skipButton.setVisibility(View.GONE);
    }

    private FrameLayout.LayoutParams cornerLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
        lp.rightMargin = CigramUI.dp(activity, 28);
        lp.bottomMargin = CigramUI.dp(activity, 104);
        return lp;
    }

    // ------------------------------------------------------------------ «الحلقة التالية»

    private void updateNext(long pos, long dur, boolean playing, long now) {
        if (nextDismissed || dur < 120000L || !host.hasNext()) {
            hideNext();
            return;
        }
        long start = creditsAt > 0L && creditsAt < dur ? creditsAt : dur - NEXT_BEFORE_END_MS;
        if (pos < start - 1500L || pos >= dur - 300L) {
            hideNext();
            countdownStartAt = -1L;
            return;
        }
        if (pos < start) return;
        boolean auto = CigramPlayerSheet.autoNextEnabled(activity);
        showNext(auto);
        if (!auto) return;
        if (countdownStartAt < 0L) countdownStartAt = now;
        if (!playing) countdownStartAt += Math.max(0L, now - lastTickAt);   // paused: freeze the countdown
        long elapsed = now - countdownStartAt;
        int left = (int) Math.max(0L, COUNTDOWN_S - elapsed / 1000L);
        if (nextCountdown != null) nextCountdown.setText("تبدأ خلال " + left);
        if (nextRing != null) nextRing.set(Math.min(1f, elapsed / (COUNTDOWN_S * 1000f)));
        if (elapsed >= COUNTDOWN_S * 1000L) goNext();
    }

    private void goNext() {
        nextDismissed = true;
        hideNext();
        try { CigramAnalytics.track(activity, "next_episode_card"); } catch (Throwable ignored) { }
        host.playNext();
    }

    private void showNext(boolean auto) {
        if (nextCard == null) {
            nextCard = new LinearLayout(activity);
            nextCard.setOrientation(LinearLayout.VERTICAL);
            nextCard.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            nextCard.setPadding(CigramUI.dp(activity, 16), CigramUI.dp(activity, 14), CigramUI.dp(activity, 16), CigramUI.dp(activity, 14));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xEB0B1F29);
            bg.setCornerRadius(CigramUI.dp(activity, 20));
            bg.setStroke(Math.max(1, CigramUI.dp(activity, 1)), 0x40FFFFFF);
            nextCard.setBackground(bg);
            nextCard.setElevation(CigramUI.dp(activity, 10));
            nextCard.setClickable(true);

            LinearLayout top = new LinearLayout(activity);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            nextRing = new Ring(activity);
            top.addView(nextRing, new LinearLayout.LayoutParams(CigramUI.dp(activity, 30), CigramUI.dp(activity, 30)));
            LinearLayout texts = new LinearLayout(activity);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(CigramUI.dp(activity, 10), 0, CigramUI.dp(activity, 10), 0);
            texts.addView(CigramUI.label(activity, "الحلقة التالية", 16f, 0xFFFFFFFF, true), new LinearLayout.LayoutParams(-2, -2));
            nextCountdown = CigramUI.label(activity, "", 12.5f, 0xFFC7C7CC, false);
            texts.addView(nextCountdown, new LinearLayout.LayoutParams(-2, -2));
            top.addView(texts, new LinearLayout.LayoutParams(-2, -2));
            nextCard.addView(top, new LinearLayout.LayoutParams(-2, -2));

            LinearLayout buttons = new LinearLayout(activity);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            TextView now = pill("شاهد الآن", true);
            tvNow = now;
            now.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { goNext(); }
            });
            TextView cancel = pill("إلغاء", false);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    nextDismissed = true;
                    hideNext();
                }
            });
            LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(-2, CigramUI.dp(activity, 38));
            LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(-2, CigramUI.dp(activity, 38));
            b2.rightMargin = CigramUI.dp(activity, 8);
            buttons.addView(now, b1);
            buttons.addView(cancel, b2);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
            bl.topMargin = CigramUI.dp(activity, 12);
            nextCard.addView(buttons, bl);
        }
        if (nextCard.getParent() == null) root.addView(nextCard, cornerLp());
        if (nextRing != null) nextRing.setVisibility(auto ? View.VISIBLE : View.GONE);
        if (nextCountdown != null) nextCountdown.setVisibility(auto ? View.VISIBLE : View.GONE);
        nextCard.bringToFront();
        if (nextCard.getVisibility() != View.VISIBLE) {
            hideSkip();
            nextCard.setAlpha(0f);
            nextCard.setTranslationY(CigramUI.dp(activity, 12));
            nextCard.setVisibility(View.VISIBLE);
            nextCard.animate().alpha(1f).translationY(0f).setDuration(220L).start();
            CigramTvKeys.prompt(nextCard);   // v10.66: both buttons count as pop-ups …
            CigramTvKeys.prompt(tvNow);      // … and the remote goes to «شاهد الآن» (TV only)
        }
    }

    private TextView pill(String text, boolean primary) {
        TextView t = CigramUI.label(activity, text, 14f, 0xFFFFFFFF, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(CigramUI.dp(activity, 18), 0, CigramUI.dp(activity, 18), 0);
        t.setClickable(true);
        t.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(CigramUI.dp(activity, 19));
        if (primary) bg.setColor(CigramUI.RED);
        else {
            bg.setColor(0x00000000);
            bg.setStroke(Math.max(1, CigramUI.dp(activity, 1)), 0x80FFFFFF);
        }
        t.setBackground(CigramGlass.ripple(activity, bg, 19));
        return t;
    }

    private void hideNext() {
        if (nextCard != null && nextCard.getVisibility() == View.VISIBLE) nextCard.setVisibility(View.GONE);
    }

    /** Countdown ring around a small play triangle. */
    static final class Ring extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private final android.graphics.Path tri = new android.graphics.Path();
        private float progress;

        Ring(Context c) { super(c); }

        void set(float v) {
            if (Math.abs(v - progress) < 0.004f) return;
            progress = v;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float stroke = Math.max(2f, w * 0.09f);
            rf.set(stroke, stroke, w - stroke, h - stroke);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0x40FFFFFF);
            c.drawOval(rf, p);
            p.setColor(CigramUI.RED);
            c.drawArc(rf, -90f, 360f * progress, false, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            tri.reset();
            float cx = w / 2f + w * 0.04f, cy = h / 2f, r = w * 0.2f;
            tri.moveTo(cx - r * 0.8f, cy - r);
            tri.lineTo(cx + r, cy);
            tri.lineTo(cx - r * 0.8f, cy + r);
            tri.close();
            c.drawPath(tri, p);
        }
    }

    // ------------------------------------------------------------------ parsing

    /** "95" / "1:35" / "00:01:35" (seconds) → ms; -1 when empty or invalid. */
    static long seconds(String raw) {
        if (raw == null) return -1L;
        String s = raw.trim();
        if (s.length() == 0) return -1L;
        try {
            if (s.indexOf(':') >= 0) {
                String[] parts = s.split(":");
                long total = 0L;
                for (String part : parts) total = total * 60L + Long.parseLong(part.trim());
                return total * 1000L;
            }
            double v = Double.parseDouble(s.replace(',', '.'));
            return v < 0 ? -1L : (long) (v * 1000.0);
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static long[] parse(String raw) {
        if (raw == null || raw.length() == 0) return null;
        try {
            String[] p = raw.split(",");
            long[] out = new long[p.length];
            for (int i = 0; i < p.length; i++) out[i] = Long.parseLong(p[i].trim());
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int toInt(String v) {
        if (v == null) return 0;
        String d = v.replaceAll("[^0-9]", "");
        if (d.length() == 0 || d.length() > 6) return 0;
        try { return Integer.parseInt(d); } catch (Throwable ignored) { return 0; }
    }
}
