package com.my.newproject;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 19.6 — «سلاسة التطبيق»: the owner tunes how the Cigram app behaves on every phone, without an app update.
 *
 * Stored by the existing Worker (no deployment needed) as the reserved channel-overlay entry {@link #KEY}; the app reads it
 * from the public /channels-config within minutes (CigramPerfConfig). Every value has a safe default in the app.
 */
public class CgSmoothActivity extends CgBase {

    static final String KEY = "__cigram_perf__";

    private static final int[] PULSE = {10, 20, 30, 60};
    private static final String[] PULSE_L = {"كل 10 ثوانٍ (الأسرع)", "كل 20 ثانية (موصى به)", "كل 30 ثانية", "كل دقيقة (أوفر للبيانات)"};
    private static final int[] PREFETCH = {0, 60, 120, 240};
    private static final String[] PREFETCH_L = {"بدون تحميل مسبق", "60 بوستر", "120 بوستر (موصى به)", "240 بوستر"};
    private static final String[] POSTER = {"w342", "w500", "w780"};
    private static final String[] POSTER_L = {"خفيفة (أسرع تحميل)", "متوازنة (موصى بها)", "عالية (للتلفاز)"};

    private JSONObject cfg = new JSONObject();
    private LinearLayout col;

    @Override
    protected String screenTitle() {
        return "سلاسة التطبيق";
    }

    @Override
    protected void onBuild() {
        col = scrollBody();
        load();
    }

    private void load() {
        showLoading("جارٍ قراءة الإعدادات الحالية…");
        CgHttp.async(this, new CgHttp.Work<JSONObject>() {
            @Override public JSONObject run() throws Exception {
                CgHttp.Res r = CgHttp.require(CgHttp.apiGet("/admin/channel-meta"));
                JSONObject channels = r.json.optJSONObject("channels");
                JSONObject me = channels == null ? null : channels.optJSONObject(KEY);
                String raw = me == null ? "" : me.optString("last_check_at", "");
                return raw.startsWith("{") ? new JSONObject(raw) : new JSONObject();
            }
        }, new CgHttp.Done<JSONObject>() {
            @Override public void ok(JSONObject c) { hideLoading(); cfg = c; render(); }
            @Override public void fail(String message) { hideLoading(); cfg = new JSONObject(); render(); CgUi.toast(CgSmoothActivity.this, "تُعرض القيم الافتراضية: " + message); }
        });
    }

    private void render() {
        col.removeAllViews();
        LinearLayout tip = CgUi.card(this);
        tip.addView(CgUi.text(this, "تتحكم هذه الإعدادات بسلاسة تطبيق سيكرام عند كل المستخدمين وتصلهم خلال دقائق بدون تحديث. القيم الموصى بها مناسبة لأغلب الأجهزة.", 12.5f, CgCfg.MUTED, false));
        tip.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        col.addView(tip);

        final int[] pulse = {indexOf(PULSE, cfg.optInt("pulse_s", 20), 1)};
        final int[] prefetch = {indexOf(PREFETCH, cfg.has("prefetch") ? cfg.optInt("prefetch") : 120, 2)};
        final int[] poster = {indexOf(POSTER, cfg.optString("poster", "w500"), 1)};

        col.addView(choice("ظهور المحتوى الجديد", "كم مرة يتأكد التطبيق المفتوح من وجود فيلم أو مسلسل أضفته (طلب صغير جداً)", PULSE_L, pulse));
        col.addView(choice("التحميل المسبق للبوسترات", "بعد كل تحديث للمكتبة: كم بوستر يُجهَّز مسبقاً للتمرير بدون انتظار", PREFETCH_L, prefetch));
        col.addView(choice("جودة البوسترات في الصفوف", "الأخف أسرع على الإنترنت الضعيف والأجهزة القديمة", POSTER_L, poster));

        LinearLayout box = CgUi.card(this);
        final CgUi.Toggle lite = CgUi.toggle(this, "الوضع الخفيف: بدون حركات انتقال (للأجهزة الضعيفة وأجهزة التلفاز)", cfg.optBoolean("lite", false));
        box.addView(lite);
        final CgUi.Toggle keep = CgUi.toggle(this, "التشغيل في الخلفية افتراضياً (يبقى التطبيق جاهزاً ويصل الجديد فوراً)", cfg.optBoolean("keepalive", true));
        box.addView(keep, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
        box.addView(CgUi.muted(this, "المستخدم يقدر يوقف التشغيل في الخلفية من إعداداته أو من الإشعار؛ هذا الخيار هو الافتراضي فقط."));
        box.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));
        col.addView(box);

        TextView save = CgUi.button(this, "حفظ ونشر للمستخدمين", 0);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    JSONObject next = new JSONObject();
                    next.put("pulse_s", PULSE[pulse[0]]);
                    next.put("prefetch", PREFETCH[prefetch[0]]);
                    next.put("poster", POSTER[poster[0]]);
                    next.put("lite", lite.on);
                    next.put("keepalive", keep.on);
                    next.put("v", System.currentTimeMillis() / 1000L);
                    save(next);
                } catch (Exception ignored) { }
            }
        });
        col.addView(save, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        TextView reset = CgUi.smallButton(this, "استعادة القيم الموصى بها", 3);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cfg = new JSONObject(); render(); }
        });
        col.addView(reset, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
    }

    private View choice(final String title, String sub, final String[] labels, final int[] holder) {
        LinearLayout box = CgUi.card(this);
        box.addView(CgUi.text(this, title, 14.5f, CgCfg.TEXT, true));
        box.addView(CgUi.muted(this, sub));
        final TextView b = CgUi.smallButton(this, labels[holder[0]], 1);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CgUi.choose(CgSmoothActivity.this, title, labels, new CgUi.IntCb() {
                    @Override public void run(int i) { holder[0] = i; b.setText(labels[i]); }
                });
            }
        });
        box.addView(b, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        box.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        return box;
    }

    private void save(final JSONObject next) {
        showLoading("جارٍ النشر…");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                JSONObject fields = new JSONObject();
                fields.put("hidden", true);                                   // never drawn as a channel
                fields.put("note", "Cigram app smoothness settings (do not delete)");
                fields.put("last_check_at", next.toString());                  // the public field the app reads
                JSONObject b = new JSONObject();
                b.put("key", KEY);
                b.put("fields", fields);
                return CgHttp.require(CgHttp.apiPost("/admin/channel-meta/save", b));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res r) {
                hideLoading();
                cfg = next;
                CgUi.info(CgSmoothActivity.this, "تم النشر ✓", "تصل الإعدادات لكل المستخدمين عند فتح التطبيق (خلال 10 دقائق كحد أقصى).");
            }
            @Override public void fail(String message) { hideLoading(); CgUi.info(CgSmoothActivity.this, "لم يُحفظ", message); }
        });
    }

    private static int indexOf(int[] arr, int v, int def) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return def;
    }

    private static int indexOf(String[] arr, String v, int def) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(v)) return i;
        return def;
    }
}
