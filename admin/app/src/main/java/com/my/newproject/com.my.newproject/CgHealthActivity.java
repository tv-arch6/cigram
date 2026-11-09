package com.my.newproject;

import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** System health page: every Worker / R2 object / feed, with status, HTTP code, latency, last check and error. */
public class CgHealthActivity extends CgBase {

    private static final int OK = 0;
    private static final int WARN = 1;
    private static final int FAIL = 2;
    private static final int UNSET = 3;
    private static final int PENDING = 4;

    private static final class Check {
        final String id;
        final String title;
        String url;
        final boolean auth;
        final boolean ranged;
        final boolean optionalFile;   // a 404 is a warning (not published yet), not a failure
        final boolean lenient;        // any HTTP < 500 means the service answers
        int state = PENDING;
        int http;
        long ms;
        String detail = "";
        long at;

        Check(String id, String title, String url, boolean auth, boolean ranged, boolean optionalFile, boolean lenient) {
            this.id = id;
            this.title = title;
            this.url = url;
            this.auth = auth;
            this.ranged = ranged;
            this.optionalFile = optionalFile;
            this.lenient = lenient;
        }
    }

    private final ArrayList<Check> checks = new ArrayList<Check>();
    private LinearLayout content;
    private int running;

    @Override
    protected String screenTitle() {
        return "صحة النظام";
    }

    private String authUrl() {
        try {
            return getSharedPreferences(CgCfg.PREFS, MODE_PRIVATE).getString("auth_worker_url", "");
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void onBuild() {
        checks.add(new Check("admin", "Admin Worker", CgCfg.API + "/admin/ping", true, false, false, false));
        checks.add(new Check("metadata", "Metadata Worker", CgCfg.METADATA_API + "/", false, false, false, true));
        String au = authUrl();
        checks.add(new Check("auth", "Auth Worker", au, false, false, false, true));
        checks.add(new Check("r2", "R2 (الوصول العام)", CgCfg.R2 + "/movies.json", false, true, false, false));
        checks.add(new Check("movies", "movies.json (عبر Worker)", CgCfg.API + "/movies", false, false, false, false));
        checks.add(new Check("carousel", "الكاروسيل", CgCfg.R2 + "/cigram_carousel.json", false, true, true, false));
        checks.add(new Check("update", "ملف التحديث", CgCfg.R2 + "/cigram_update.json", false, true, true, false));
        checks.add(new Check("channels", "Worker القنوات", CgCfg.CHANNELS_API + "/channels", false, false, false, false));
        checks.add(new Check("home", "ترتيب الرئيسية", CgCfg.API + "/home-sections", false, false, false, false));
        checks.add(new Check("maintenance", "وضع الصيانة", CgCfg.API + "/maintenance", false, false, false, false));
        checks.add(new Check("announcements", "الرسائل العامة", CgCfg.API + "/announcements", false, false, false, false));
        bar.action("فحص الكل", new Runnable() {
            @Override
            public void run() {
                runAll();
            }
        });
        render();
        runAll();
    }

    private void runAll() {
        for (int i = 0; i < checks.size(); i++) run(checks.get(i));
    }

    private void run(final Check c) {
        if (c.id.equals("auth")) c.url = authUrl();
        if (c.url.length() == 0) {
            c.state = UNSET;
            c.detail = "رابط Auth Worker غير مضبوط. اضغط «ضبط الرابط».";
            c.at = System.currentTimeMillis();
            render();
            return;
        }
        c.state = PENDING;
        c.detail = "جارٍ الفحص...";
        running++;
        render();
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() {
                String[][] h = c.ranged ? new String[][]{{"Range", "bytes=0-1"}} : null;
                return CgHttp.call("GET", c.url, null, c.auth, 15000, h);
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                running--;
                apply(c, r);
                render();
            }

            @Override
            public void fail(String message) {
                running--;
                c.state = FAIL;
                c.detail = message;
                c.at = System.currentTimeMillis();
                render();
            }
        });
    }

    private void apply(Check c, CgHttp.Res r) {
        c.at = System.currentTimeMillis();
        c.http = r.code;
        c.ms = r.ms;
        if (r.error != null) {
            c.state = FAIL;
            c.detail = CgHttp.describe(r.error);
            return;
        }
        if (r.code == 401) {
            c.state = FAIL;
            c.detail = "رمز الإدارة مرفوض (401).";
            return;
        }
        if (r.code == 404 && c.optionalFile) {
            c.state = WARN;
            c.detail = "غير منشور بعد (404).";
            return;
        }
        boolean good = r.code >= 200 && r.code < 300 || (c.lenient && r.code < 500);
        if (!good) {
            c.state = FAIL;
            c.detail = r.code >= 500 ? "خطأ في الخادم (HTTP " + r.code + ")." : "رد غير متوقع (HTTP " + r.code + ").";
            return;
        }
        c.state = OK;
        c.detail = "";
        try {
            if ("movies".equals(c.id)) {
                JSONArray a = r.array;
                if (a == null && r.json != null) a = r.json.optJSONArray("movies");
                c.detail = a == null ? "رد غير متوقع: ليس مصفوفة" : a.length() + " عنصر";
                if (a == null) c.state = WARN;
            } else if ("maintenance".equals(c.id) && r.json != null) {
                c.detail = r.json.optBoolean("enabled") ? "الصيانة مفعّلة الآن" : "الصيانة متوقفة";
                if (r.json.optBoolean("enabled")) c.state = WARN;
            } else if ("announcements".equals(c.id) && r.json != null) {
                JSONArray a = r.json.optJSONArray("items");
                c.detail = (a == null ? 0 : a.length()) + " رسالة نشطة";
            } else if ("home".equals(c.id) && r.json != null) {
                JSONArray a = r.json.optJSONArray("sections");
                c.detail = (a == null ? 0 : a.length()) + " قسم" + ("derived".equals(r.json.optString("source")) ? " (مستخرجة من المحتوى)" : "");
            } else if ("channels".equals(c.id) && r.json != null) {
                JSONArray a = r.json.optJSONArray("categories");
                c.detail = (a == null ? 0 : a.length()) + " قسم قنوات";
            } else if ("admin".equals(c.id) && r.json != null) {
                c.detail = "الإصدار " + r.json.optInt("v", 1);
            }
        } catch (Exception ignored) { }
        if (c.ms > 3000 && c.state == OK) {
            c.state = WARN;
            c.detail = (c.detail.length() > 0 ? c.detail + " • " : "") + "بطيء";
        }
    }

    private void render() {
        if (!alive()) return;
        content = scrollBody();
        int ok = 0;
        int warn = 0;
        int fail = 0;
        int pend = 0;
        for (int i = 0; i < checks.size(); i++) {
            int s = checks.get(i).state;
            if (s == OK) ok++;
            else if (s == WARN || s == UNSET) warn++;
            else if (s == FAIL) fail++;
            else pend++;
        }
        int color = fail > 0 ? CgCfg.BAD : (warn > 0 || pend > 0) ? CgCfg.WARN : CgCfg.GOOD;
        String text = pend > 0 ? "جارٍ فحص الخدمات..." : fail > 0 ? "توجد مشاكل: " + fail + " خدمة لا تعمل" : warn > 0 ? "النظام يعمل مع " + warn + " تنبيه" : "كل الخدمات تعمل";
        LinearLayout banner = CgUi.card(this);
        banner.setBackground(CgUi.bg(this, CgUi.alpha(color, 0x22), 14, color));
        banner.addView(CgUi.text(this, "● " + text, 16, color, true));
        banner.addView(CgUi.muted(this, "سليم " + ok + " • تنبيه " + warn + " • فشل " + fail));
        banner.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        content.addView(banner);
        for (int i = 0; i < checks.size(); i++) content.addView(row(checks.get(i)));
    }

    private View row(final Check c) {
        int color;
        String label;
        if (c.state == OK) {
            color = CgCfg.GOOD;
            label = "يعمل";
        } else if (c.state == WARN) {
            color = CgCfg.WARN;
            label = "تنبيه";
        } else if (c.state == FAIL) {
            color = CgCfg.BAD;
            label = "لا يعمل";
        } else if (c.state == UNSET) {
            color = CgCfg.GRAY;
            label = "غير مضبوط";
        } else {
            color = CgCfg.MUTED;
            label = "جارٍ الفحص";
        }
        LinearLayout card = CgUi.card(this);
        LinearLayout top = CgUi.hbox(this);
        top.addView(CgUi.text(this, c.title, 15, CgCfg.TEXT, true), new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        top.addView(CgUi.badge(this, label, color));
        card.addView(top);
        StringBuilder sb = new StringBuilder();
        if (c.http > 0) sb.append("HTTP ").append(c.http).append(" • ");
        if (c.ms > 0) sb.append(c.ms).append(" ms • ");
        if (c.at > 0) sb.append("آخر فحص: ").append(CgUi.ago(CgUi.toIso(c.at)));
        if (sb.length() > 0) card.addView(CgUi.muted(this, sb.toString()));
        if (c.detail.length() > 0) card.addView(CgUi.text(this, c.detail, 12.5f, c.state == FAIL ? CgCfg.BAD : c.state == WARN ? CgCfg.WARN : CgCfg.MUTED, false));
        LinearLayout acts = CgUi.hbox(this);
        TextView re = CgUi.smallButton(this, "إعادة الفحص", 1);
        re.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run(c);
            }
        });
        acts.addView(re, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
        if (c.id.equals("auth")) {
            TextView set = CgUi.smallButton(this, "ضبط الرابط", 0);
            set.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    CgUi.input(CgHealthActivity.this, "رابط Auth Worker", "https://...workers.dev", authUrl(), false, new CgUi.Callback<String>() {
                        @Override
                        public void run(String value) {
                            try {
                                SharedPreferences.Editor e = getSharedPreferences(CgCfg.PREFS, MODE_PRIVATE).edit();
                                e.putString("auth_worker_url", value);
                                e.apply();
                            } catch (Exception ignored) { }
                            CgHealthActivity.this.run(c);
                        }
                    });
                }
            });
            acts.addView(set, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        }
        card.addView(acts, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 0, 0));
        card.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        return card;
    }
}
