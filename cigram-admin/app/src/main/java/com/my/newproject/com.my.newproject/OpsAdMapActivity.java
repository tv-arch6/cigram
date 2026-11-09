package com.my.newproject;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Schematic map of where ads appear, drawn from the CURRENT saved config:
 * three screen sketches (green slot = active, dashed gray = off) and a player timeline that
 * simulates a 90-minute film with the saved delay / interval / cooldown / hourly cap.
 * The simulation is an approximation of the app policy, meant for orientation, not billing.
 */
public class OpsAdMapActivity extends OpsBase {

    private static final int FILM_MIN = 90;

    @Override
    protected String screenTitle() {
        return "خريطة الإعلانات";
    }

    @Override
    protected void onBuild() {
        bar.action("تحديث", new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
        load();
    }

    private void load() {
        getJson("/admin/ads/config", new JsonDone() {
            @Override
            public void ok(JSONObject d, JSONObject root) {
                render(d);
            }
        });
    }

    private boolean flag(JSONObject cfg, JSONObject def, String key) {
        if (cfg.has(key) && !cfg.isNull(key)) return cfg.optBoolean(key, true);
        return def.optBoolean(key, true);
    }

    private int num(JSONObject cfg, JSONObject def, String key, int fallback) {
        if (cfg.has(key) && !cfg.isNull(key)) return cfg.optInt(key, fallback);
        return def.optInt(key, fallback);
    }

    private void render(JSONObject d) {
        if (!alive()) return;
        LinearLayout c = scrollBody();
        JSONObject cfg = d.optJSONObject("config");
        if (cfg == null) cfg = new JSONObject();
        JSONObject def = d.optJSONObject("defaults");
        if (def == null) def = new JSONObject();

        boolean all = flag(cfg, def, "ads_enabled");
        boolean player = all && flag(cfg, def, "player_ads_enabled");
        boolean mid = all && flag(cfg, def, "mid_roll_enabled");
        boolean episode = all && flag(cfg, def, "episode_ads_enabled");
        boolean support = all && flag(cfg, def, "support_ads_enabled");
        boolean tv = all && flag(cfg, def, "tv_ads_enabled");
        int first = num(cfg, def, "first_ad_delay_min", 15);
        int interval = num(cfg, def, "midroll_interval_min", 25);
        int cap = num(cfg, def, "max_ads_per_hour", 4);
        int cool = num(cfg, def, "cooldown_min", 10);

        if (!d.optBoolean("exists", false)) {
            c.addView(verdictCard("info", "لا يوجد إعداد محفوظ", "المخطط يعرض القيم الافتراضية. احفظ الإعدادات من شاشة «تفعيل / إيقاف الإعلانات» ليعكس ما اخترته."));
        }
        if (!all) {
            c.addView(verdictCard("bad", "الإعلانات موقوفة", "المفتاح الرئيسي متوقف، لذلك كل المواضع أدناه معطّلة."));
        }

        // ---- screen sketches
        c.addView(CgUi.sectionTitle(this, "أين تظهر"));
        LinearLayout phones = CgUi.hbox(this);
        phones.setGravity(Gravity.TOP);
        phones.addView(phoneColumn("الرئيسية", "بين الأقسام", 0, all), CgUi.lpWeight(this, 1f, 0, 0, 3, 0));
        phones.addView(phoneColumn("الفيلم / المشغّل", "قبل وأثناء التشغيل", 1, player || mid), CgUi.lpWeight(this, 1f, 1, 0, 1, 0));
        phones.addView(phoneColumn("التنقل بين الحلقات", "بين الحلقات", 2, episode), CgUi.lpWeight(this, 1f, 3, 0, 0, 0));
        c.addView(phones, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        c.addView(CgUi.muted(this, "أخضر = موضع مفعّل · رمادي متقطع = متوقف. هذا رسم توضيحي عام لمفاتيح الإعداد وليس لقطة من التطبيق."),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 0, 4, 8));

        LinearLayout other = CgUi.card(this);
        other.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        other.addView(kv("إعلان الدعم", support ? "مفعّل" : "متوقف", support ? CgCfg.GOOD : CgCfg.GRAY));
        other.addView(kv("إعلانات التلفاز (VAST)", tv ? (cfg.optString("tv_vast_tag", "").length() > 0 ? "مفعّل" : "مفعّل — لا يوجد رابط VAST") : "متوقف",
                tv ? CgCfg.GOOD : CgCfg.GRAY));
        JSONArray free = cfg.optJSONArray("ad_free_user_ids");
        other.addView(kv("مستخدمون بلا إعلانات", String.valueOf(free == null ? 0 : free.length()), CgCfg.TEXT));
        c.addView(other);

        // ---- timeline
        c.addView(CgUi.sectionTitle(this, "محاكاة فيلم مدته 90 دقيقة"));
        ArrayList<Integer> times = simulate(player, mid, first, interval, cool, cap);
        LinearLayout tc = CgUi.card(this);
        tc.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        tc.addView(new Timeline(this, times, FILM_MIN), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.dp(this, 78)));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times.size(); i++) {
            if (i > 0) sb.append("، ");
            sb.append("د ").append(times.get(i));
        }
        TextView sum = CgUi.text(this, times.isEmpty()
                ? "لا إعلانات داخل المشغّل بهذه الإعدادات."
                : times.size() + " إعلان في الفيلم: " + sb, 13, CgCfg.TEXT, true);
        sum.setPadding(0, CgUi.dp(this, 8), 0, 0);
        tc.addView(sum);
        tc.addView(CgUi.muted(this, "التأخير الأول " + first + " د · الفاصل " + interval + " د · الانتظار " + cool + " د · الحد " + cap + " في الساعة"));
        tc.addView(CgUi.muted(this, "تقريب لسياسة التطبيق (أول إعلان بعد التأخير، ثم كل فاصل مع احترام الانتظار والحد الساعي)."));
        c.addView(tc);
    }

    /** Minutes at which the player would show an ad (approximation of the app policy). */
    private ArrayList<Integer> simulate(boolean player, boolean mid, int first, int interval, int cool, int cap) {
        ArrayList<Integer> out = new ArrayList<Integer>();
        if (cap <= 0 || (!player && !mid)) return out;
        int gap = Math.max(interval, cool);
        int t = first;
        boolean firstDone = false;
        while (t < FILM_MIN) {
            boolean allowed = firstDone ? mid : (player || mid);
            if (allowed) {
                int inHour = 0;
                for (int i = 0; i < out.size(); i++) if (t - out.get(i) < 60) inHour++;
                if (inHour < cap) {
                    out.add(t);
                    firstDone = true;
                    t += gap;
                    continue;
                }
                t += 1;
                continue;
            }
            break;
        }
        return out;
    }

    private View phoneColumn(String title, String sub, int variant, boolean active) {
        LinearLayout col = CgUi.vbox(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(new Phone(this, variant, active), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.dp(this, 150)));
        TextView t = CgUi.text(this, title, 12.5f, CgCfg.TEXT, true);
        t.setGravity(Gravity.CENTER);
        col.addView(t, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        TextView s = CgUi.text(this, sub + " · " + (active ? "مفعّل" : "متوقف"), 11.5f, active ? CgCfg.GOOD : CgCfg.GRAY, false);
        s.setGravity(Gravity.CENTER);
        col.addView(s, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        return col;
    }

    // ----------------------------------------------------------- drawing

    private static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        return p;
    }

    /** Simple phone sketch with one ad slot. variant: 0 home, 1 movie/player, 2 episodes list. */
    static final class Phone extends View {
        private final int variant;
        private final boolean active;

        Phone(Context c, int variant, boolean active) {
            super(c);
            this.variant = variant;
            this.active = active;
        }

        private void block(Canvas cv, float l, float t, float r, float b, int color) {
            cv.drawRoundRect(new RectF(l, t, r, b), 5f, 5f, fill(color));
        }

        private void slot(Canvas cv, float l, float t, float r, float b) {
            RectF rect = new RectF(l, t, r, b);
            int base = active ? CgCfg.GOOD : CgCfg.GRAY;
            cv.drawRoundRect(rect, 6f, 6f, fill(CgUi.alpha(base, active ? 0x40 : 0x1A)));
            Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
            s.setStyle(Paint.Style.STROKE);
            s.setStrokeWidth(1.6f);
            s.setColor(base);
            if (!active) s.setPathEffect(new DashPathEffect(new float[]{12f, 8f}, 0f));
            cv.drawRoundRect(rect, 6f, 6f, s);
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(base);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setFakeBoldText(true);
            tp.setTextSize(9f);
            cv.drawText("AD", (l + r) / 2f, (t + b) / 2f + 3.2f, tp);
        }

        @Override
        protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            // draw in a 150-unit-high design space, scaled to the view so it looks the same on every density
            float sc = getHeight() / 150f;
            cv.save();
            cv.scale(sc, sc);
            float w = getWidth() / sc;
            float h = 150f;
            float pw = Math.min(w * 0.82f, 92f);
            float l = (w - pw) / 2f;
            float r = l + pw;
            float pad = pw * 0.08f;
            Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(2f);
            frame.setColor(CgCfg.TEXT);
            cv.drawRoundRect(new RectF(l, 2, r, h - 2), 16f, 16f, fill(CgCfg.CARD));
            cv.drawRoundRect(new RectF(l, 2, r, h - 2), 16f, 16f, frame);
            int g = CgUi.alpha(CgCfg.STROKE, 0x88);
            float x1 = l + pad;
            float x2 = r - pad;
            float y = 16;
            if (variant == 0) {
                block(cv, x1, y, x2, y + 34, g);               // hero
                y += 42;
                block(cv, x1, y, x1 + (x2 - x1) * 0.3f, y + 26, g);
                block(cv, x1 + (x2 - x1) * 0.35f, y, x1 + (x2 - x1) * 0.65f, y + 26, g);
                block(cv, x1 + (x2 - x1) * 0.7f, y, x2, y + 26, g);
                y += 34;
                slot(cv, x1, y, x2, y + 24);                  // ad between sections
                y += 32;
                block(cv, x1, y, x1 + (x2 - x1) * 0.3f, y + 26, g);
                block(cv, x1 + (x2 - x1) * 0.35f, y, x1 + (x2 - x1) * 0.65f, y + 26, g);
                block(cv, x1 + (x2 - x1) * 0.7f, y, x2, y + 26, g);
            } else if (variant == 1) {
                block(cv, x1, y, x2, y + 56, CgUi.alpha(CgCfg.TEXT, 0x99)); // video
                Path tri = new Path();
                float cx = (x1 + x2) / 2f;
                float cy = y + 28;
                tri.moveTo(cx - 6, cy - 8);
                tri.lineTo(cx - 6, cy + 8);
                tri.lineTo(cx + 8, cy);
                tri.close();
                cv.drawPath(tri, fill(0xFFFFFFFF));
                slot(cv, x1 + 4, y + 4, x1 + 30, y + 18);       // ad over the video
                y += 64;
                block(cv, x1, y, x2, y + 8, g);
                y += 14;
                block(cv, x1, y, x1 + (x2 - x1) * 0.7f, y + 8, g);
                y += 16;
                block(cv, x1, y, x2, y + 8, g);
                y += 14;
                block(cv, x1, y, x1 + (x2 - x1) * 0.5f, y + 8, g);
            } else {
                for (int i = 0; i < 4; i++) {
                    block(cv, x1, y, x1 + 22, y + 22, g);
                    block(cv, x1 + 28, y + 3, x2, y + 10, g);
                    block(cv, x1 + 28, y + 14, x2 - 14, y + 20, g);
                    y += 28;
                    if (i == 1) {
                        slot(cv, x1, y, x2, y + 20);
                        y += 28;
                    }
                }
            }
            cv.restore();
        }
    }

    /** Horizontal film timeline with an ad marker per simulated ad. */
    static final class Timeline extends View {
        private final ArrayList<Integer> times;
        private final int total;

        Timeline(Context c, ArrayList<Integer> times, int total) {
            super(c);
            this.times = times;
            this.total = total;
        }

        @Override
        protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            Context c = getContext();
            float w = getWidth();
            float h = getHeight();
            float pad = CgUi.dp(c, 8);
            float barY = h * 0.5f;
            float barH = CgUi.dp(c, 10);
            float x0 = pad;
            float x1 = w - pad;
            cv.drawRoundRect(new RectF(x0, barY - barH / 2f, x1, barY + barH / 2f), 8f, 8f, fill(CgCfg.CARD2));
            Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setColor(CgCfg.MUTED);
            txt.setTextSize(CgUi.dp(c, 10));
            if (CgUi.font(c) != null) txt.setTypeface(CgUi.font(c));
            for (int m = 0; m <= total; m += 15) {
                float x = x0 + (x1 - x0) * m / total;
                cv.drawText(String.valueOf(m), x, h - CgUi.dp(c, 6), txt);
            }
            Paint ad = fill(CgCfg.GOOD);
            Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
            label.setTextAlign(Paint.Align.CENTER);
            label.setColor(CgCfg.GOOD);
            label.setFakeBoldText(true);
            label.setTextSize(CgUi.dp(c, 10));
            for (int i = 0; i < times.size(); i++) {
                float x = x0 + (x1 - x0) * times.get(i) / total;
                cv.drawCircle(x, barY, CgUi.dp(c, 7), ad);
                cv.drawText(String.format(Locale.US, "%d", times.get(i)), x, barY - CgUi.dp(c, 12), label);
            }
        }
    }
}
