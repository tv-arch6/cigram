package com.my.newproject;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ads control: master switch, per-placement switches, timing and limits (same ranges the app accepts),
 * ad-free users, TV VAST tag, developer domain, and import of the old (in-app) exemption list.
 */
public class OpsAdsActivity extends OpsBase {

    private static final String[][] SWITCHES = new String[][]{
            {"player_ads_enabled", "إعلان المشغّل"},
            {"mid_roll_enabled", "إعلانات منتصف الفيديو"},
            {"episode_ads_enabled", "إعلانات الحلقات"},
            {"support_ads_enabled", "إعلان الدعم"},
            {"tv_ads_enabled", "إعلانات التلفاز (VAST)"},
    };

    private static final String[][] NUMBERS = new String[][]{
            {"first_ad_delay_min", "تأخير أول إعلان بعد بدء المشاهدة (دقيقة)"},
            {"midroll_interval_min", "الفاصل بين إعلانات منتصف الفيديو (دقيقة)"},
            {"max_ads_per_hour", "أقصى عدد إعلانات في الساعة (0 = إيقاف إعلانات المشغّل والحلقات)"},
            {"cooldown_min", "فترة الانتظار بين إعلانين (دقيقة)"},
    };

    private CgUi.Toggle master;
    private TextView masterWarn;
    private final Map<String, CgUi.Toggle> toggles = new LinkedHashMap<String, CgUi.Toggle>();
    private final Map<String, EditText> numbers = new LinkedHashMap<String, EditText>();
    private final Map<String, int[]> ranges = new LinkedHashMap<String, int[]>();
    private EditText vast;
    private EditText domain;
    private EditText ids;
    private TextView idsCount;

    @Override
    protected String screenTitle() {
        return "تفعيل / إيقاف الإعلانات";
    }

    @Override
    protected void onBuild() {
        bar.action("حفظ", new Runnable() {
            @Override
            public void run() {
                save();
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

    private boolean boolOf(JSONObject cfg, JSONObject def, String key) {
        if (cfg.has(key) && !cfg.isNull(key)) return cfg.optBoolean(key, true);
        return def.optBoolean(key, true);
    }

    private int intOf(JSONObject cfg, JSONObject def, String key, int fallback) {
        if (cfg.has(key) && !cfg.isNull(key)) return cfg.optInt(key, fallback);
        return def.optInt(key, fallback);
    }

    private void render(JSONObject d) {
        if (!alive()) return;
        LinearLayout c = scrollBody();
        boolean exists = d.optBoolean("exists", false);
        JSONObject cfg = d.optJSONObject("config");
        if (cfg == null) cfg = new JSONObject();
        JSONObject def = d.optJSONObject("defaults");
        if (def == null) def = new JSONObject();
        JSONObject rg = d.optJSONObject("ranges");

        ranges.clear();
        for (int i = 0; i < NUMBERS.length; i++) {
            int lo = 0;
            int hi = 1000;
            JSONArray r = rg == null ? null : rg.optJSONArray(NUMBERS[i][0]);
            if (r != null && r.length() >= 2) {
                lo = r.optInt(0, lo);
                hi = r.optInt(1, hi);
            }
            ranges.put(NUMBERS[i][0], new int[]{lo, hi});
        }

        if (!exists) {
            c.addView(verdictCard("info", "لا يوجد إعداد محفوظ",
                    "التطبيق يعمل الآن بالقيم الافتراضية داخل الكود. اضغط «حفظ» لتبدأ بالتحكم من هنا. إن كان لديك مستخدمون معفيّون في الإعداد القديم فاستوردهم أولاً (القسم الأخير)."));
        } else {
            long v = cfg.optLong("version", 0);
            String at = cfg.optString("updatedAt", "");
            c.addView(CgUi.muted(this, "الإصدار المحفوظ " + v + (at.length() > 0 ? " · آخر حفظ " + CgUi.localText(at) : "")),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 4, 0, 4, 8));
        }

        // ---- master
        LinearLayout mc = CgUi.card(this);
        mc.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        master = CgUi.toggle(this, "تشغيل كل الإعلانات (المفتاح الرئيسي)", boolOf(cfg, def, "ads_enabled"));
        mc.addView(master, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        masterWarn = CgUi.text(this, "الإعلانات موقوفة: لن يُطلب أي إعلان AdMob لدى مستخدمي النسخ الجديدة.", 12.5f, CgCfg.BAD, true);
        masterWarn.setPadding(0, CgUi.dp(this, 6), 0, 0);
        mc.addView(masterWarn, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        masterWarn.setVisibility(master.on ? View.GONE : View.VISIBLE);
        master.onChange(new Runnable() {
            @Override
            public void run() {
                masterWarn.setVisibility(master.on ? View.GONE : View.VISIBLE);
            }
        });
        c.addView(mc);

        // ---- placements
        c.addView(CgUi.sectionTitle(this, "المواضع"));
        LinearLayout pc = CgUi.card(this);
        pc.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        toggles.clear();
        for (int i = 0; i < SWITCHES.length; i++) {
            CgUi.Toggle t = CgUi.toggle(this, SWITCHES[i][1], boolOf(cfg, def, SWITCHES[i][0]));
            toggles.put(SWITCHES[i][0], t);
            if (i > 0) pc.addView(CgUi.divider(this));
            pc.addView(t, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }
        c.addView(pc);

        // ---- timing
        c.addView(CgUi.sectionTitle(this, "التوقيت والحدود"));
        LinearLayout tc = CgUi.card(this);
        tc.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        numbers.clear();
        for (int i = 0; i < NUMBERS.length; i++) {
            String key = NUMBERS[i][0];
            int[] r = ranges.get(key);
            EditText e = CgUi.numberField(this, "");
            e.setText(String.valueOf(intOf(cfg, def, key, r[0])));
            numbers.put(key, e);
            tc.addView(CgUi.labeled(this, NUMBERS[i][1] + " — من " + r[0] + " إلى " + r[1], e));
        }
        tc.addView(CgUi.muted(this, "التطبيق يتجاهل أي قيمة خارج هذه الحدود، لذلك يرفضها الخادم عند الحفظ."));
        c.addView(tc);

        // ---- TV + domain
        c.addView(CgUi.sectionTitle(this, "التلفاز والموقع"));
        LinearLayout oc = CgUi.card(this);
        oc.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        vast = CgUi.urlField(this, "https://...");
        vast.setText(cfg.optString("tv_vast_tag", ""));
        oc.addView(CgUi.labeled(this, "رابط VAST للتلفاز (https فقط، اتركه فارغاً لحذفه)", vast));
        domain = CgUi.urlField(this, "example.com");
        domain.setText(cfg.optString("developer_domain", ""));
        oc.addView(CgUi.labeled(this, "نطاق موقع المطوّر في Google Play (لفحص app-ads.txt)", domain));
        c.addView(oc);

        // ---- ad-free users
        c.addView(CgUi.sectionTitle(this, "مستخدمون بلا إعلانات"));
        LinearLayout uc = CgUi.card(this);
        uc.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        ids = CgUi.field(this, "معرّف واحد في كل سطر", true);
        ids.setTextDirection(View.TEXT_DIRECTION_LTR);
        ids.setMinLines(4);
        ids.setMaxLines(12);
        JSONArray arr = cfg.optJSONArray("ad_free_user_ids");
        StringBuilder sb = new StringBuilder();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(arr.optString(i, ""));
            }
        }
        ids.setText(sb.toString());
        uc.addView(ids, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        idsCount = CgUi.muted(this, "");
        idsCount.setPadding(0, CgUi.dp(this, 6), 0, CgUi.dp(this, 6));
        uc.addView(idsCount);
        refreshCount();
        LinearLayout row = CgUi.hbox(this);
        TextView imp = CgUi.smallButton(this, "استيراد من الإعداد القديم", 1);
        imp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importLegacy();
            }
        });
        row.addView(imp, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        View g = new View(this);
        row.addView(g, new LinearLayout.LayoutParams(CgUi.dp(this, 8), 1));
        TextView clean = CgUi.smallButton(this, "حذف التكرار", 1);
        clean.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ids.setText(join(parseIds(ids.getText().toString())));
                refreshCount();
            }
        });
        row.addView(clean, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        uc.addView(row);
        uc.addView(CgUi.muted(this, "الحد الأقصى 500 مستخدم. المعفيّون في النسخ القديمة من التطبيق (داخل الكود) يفقدون إعفاءهم عند التحديث ما لم تستوردهم هنا قبل نشر النسخة الجديدة."),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        c.addView(uc);

        TextView saveBtn = CgUi.button(this, "حفظ الإعدادات", 0);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        c.addView(saveBtn, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
    }

    private ArrayList<String> parseIds(String raw) {
        ArrayList<String> out = new ArrayList<String>();
        String[] parts = raw.split("[\\s,;،]+");
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i].trim();
            if (s.length() > 0 && !out.contains(s)) out.add(s);
        }
        return out;
    }

    private String join(ArrayList<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private void refreshCount() {
        int n = parseIds(ids.getText().toString()).size();
        idsCount.setText(n + " مستخدم" + (n > 500 ? " — أكثر من الحد (500)" : ""));
        idsCount.setTextColor(n > 500 ? CgCfg.BAD : CgCfg.MUTED);
    }

    private void importLegacy() {
        CgUi.input(this, "استيراد المعفيّين من الإعداد القديم",
                "الصق المعرّفات (سطر لكل معرّف أو مفصولة بفاصلة)", "", true, new CgUi.Callback<String>() {
                    @Override
                    public void run(String value) {
                        ArrayList<String> merged = parseIds(ids.getText().toString());
                        ArrayList<String> add = parseIds(value);
                        int added = 0;
                        for (int i = 0; i < add.size(); i++) {
                            if (!merged.contains(add.get(i))) {
                                merged.add(add.get(i));
                                added++;
                            }
                        }
                        ids.setText(join(merged));
                        refreshCount();
                        CgUi.toast(OpsAdsActivity.this, "أُضيف " + added + " معرّفاً. اضغط «حفظ» لتطبيق التغيير.");
                    }
                });
    }

    private void save() {
        if (master == null) return;
        final JSONObject body = new JSONObject();
        try {
            body.put("ads_enabled", master.on);
            for (Map.Entry<String, CgUi.Toggle> e : toggles.entrySet()) body.put(e.getKey(), e.getValue().on);
            for (Map.Entry<String, EditText> e : numbers.entrySet()) {
                String raw = e.getValue().getText().toString().trim();
                int[] r = ranges.get(e.getKey());
                if (raw.length() == 0) {
                    CgUi.info(this, "قيمة ناقصة", "أدخل قيمة لكل حقل في «التوقيت والحدود».");
                    return;
                }
                int v;
                try {
                    v = Integer.parseInt(raw);
                } catch (Exception ex) {
                    CgUi.info(this, "قيمة غير صالحة", "«" + raw + "» ليست رقماً صحيحاً.");
                    return;
                }
                if (v < r[0] || v > r[1]) {
                    CgUi.info(this, "خارج الحدود", e.getKey() + " يجب أن تكون بين " + r[0] + " و" + r[1] + " وإلا يتجاهلها التطبيق.");
                    return;
                }
                body.put(e.getKey(), v);
            }
            String v = vast.getText().toString().trim();
            if (v.length() > 0 && !v.startsWith("https://")) {
                CgUi.info(this, "رابط VAST", "الرابط يجب أن يبدأ بـ https://");
                return;
            }
            body.put("tv_vast_tag", v);
            body.put("developer_domain", domain.getText().toString().trim());
            ArrayList<String> list = parseIds(ids.getText().toString());
            if (list.size() > 500) {
                CgUi.info(this, "عدد كبير", "الحد الأقصى 500 مستخدم بلا إعلانات (لديك " + list.size() + ").");
                return;
            }
            JSONArray arr = new JSONArray();
            for (int i = 0; i < list.size(); i++) arr.put(list.get(i));
            body.put("ad_free_user_ids", arr);
        } catch (Exception e) {
            CgUi.info(this, "تعذر التحضير", String.valueOf(e.getMessage()));
            return;
        }
        if (!master.on) {
            CgUi.confirm(this, "إيقاف كل الإعلانات؟",
                    "سيتوقف عرض الإعلانات لكل مستخدمي النسخ الجديدة خلال دقائق.", "حفظ وإيقاف", true, new Runnable() {
                        @Override
                        public void run() {
                            send(body);
                        }
                    });
        } else {
            send(body);
        }
    }

    private void send(JSONObject body) {
        postJson("/admin/ads/config", body, "جارٍ الحفظ...", new JsonDone() {
            @Override
            public void ok(JSONObject d, JSONObject root) {
                String m = root.optString("message", "");
                CgUi.toast(OpsAdsActivity.this, m.length() > 0 ? m : "تم الحفظ.");
                load();
            }
        });
    }
}
