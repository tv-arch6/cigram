package com.my.newproject;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * إعدادات الإعلانات: كل نص/سعر/رقم يظهر في تطبيق المستخدم يُعدَّل من هنا (POST /admin/settings {patch}).
 * الأقسام: العملة، المساحات، المدد والخصومات، الإضافات، الاستهداف، القواعد، السياسات (رفع الإصدار يطلب موافقة جديدة)،
 * الأسئلة الشائعة، التواصل، ساعات العمل والرد التلقائي، أرقام الصفحة الرئيسية.
 * صيغ الحقول النصية المركّبة مكتوبة تحت كل حقل.
 */
public class AdSettingsActivity extends CgBase {

    private static final String[] SLOTS = {"splash", "hero", "inline", "popup", "sticky", "sponsor"};
    private static final String[] ADDONS = {"exclusive", "video", "design"};
    private static final String[] DAYS = {"الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"};

    private JSONObject st = new JSONObject();
    private JSONObject measured = new JSONObject();

    private EditText curCode, curSym, curDec;
    private final EditText[][] slotF = new EditText[SLOTS.length][];
    private final CgUi.Toggle[] slotOn = new CgUi.Toggle[SLOTS.length];
    private EditText durations, cMin, cMax, discounts;
    private final EditText[][] addF = new EditText[ADDONS.length][];
    private final boolean[] addPct = new boolean[ADDONS.length];
    private final TextView[] addTypeBtn = new TextView[ADDONS.length];
    private EditText tCityPct, tLangPct, tHoursPct, tCityRf, tLangRf, tHoursRf, tCities, tLangs;
    private EditText rAccepted, rRejected;
    private LinearLayout polBox, faqBox;
    private final List<View[]> polRows = new ArrayList<View[]>();
    private final List<EditText[]> faqRows = new ArrayList<EditText[]>();
    private CgUi.Toggle bumpVersion;
    private EditText cWa, cWaMsg, cXUrl, cXHandle;
    private CgUi.Toggle hoursOn;
    private EditText hTz, hFrom, hTo, autoReply;
    private final CgUi.Toggle[] dayT = new CgUi.Toggle[7];
    private EditText heroTitle, heroSub, heroUsers, heroViews;

    @Override protected String screenTitle() { return "إعدادات الإعلانات"; }

    @Override protected void onBuild() {
        bar.action("حفظ", new Runnable() { @Override public void run() { save(); } });
        load();
    }

    private void load() {
        body.removeAllViews();
        body.addView(CgUi.spinner(this));
        AdApi.get(this, "/admin/settings", new AdApi.Cb() {
            @Override public void on(AdApi.Result r) {
                if (!alive()) return;
                body.removeAllViews();
                if (!r.ok) {
                    body.addView(CgUi.errorCard(AdSettingsActivity.this, r.message, new Runnable() { @Override public void run() { load(); } }));
                    return;
                }
                st = r.json.optJSONObject("settings") == null ? new JSONObject() : r.json.optJSONObject("settings");
                measured = r.json.optJSONObject("hero_measured") == null ? new JSONObject() : r.json.optJSONObject("hero_measured");
                render();
            }
        });
    }

    // ------------------------------------------------------------------ أدوات بناء

    private EditText field(LinearLayout c, String label, String val, boolean multi, String hint) {
        EditText e = CgUi.field(this, label, multi);
        e.setText(val == null ? "" : val);
        LinearLayout w = CgUi.labeled(this, label, e);
        c.addView(w);
        if (hint != null) c.addView(CgUi.muted(this, hint));
        return e;
    }

    private EditText num(LinearLayout c, String label, String val) {
        EditText e = CgUi.numberField(this, label);
        e.setText(val == null ? "" : val);
        c.addView(CgUi.labeled(this, label, e));
        return e;
    }

    private LinearLayout section(LinearLayout root, String title, String sub) {
        root.addView(CgUi.sectionTitle(this, title));
        LinearLayout card = CgUi.card(this);
        if (sub != null) card.addView(CgUi.muted(this, sub));
        root.addView(card, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        return card;
    }

    private static String trimNum(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        return String.format(Locale.US, "%s", v);
    }

    private static double dbl(EditText e, double def) {
        try { return Double.parseDouble(e.getText().toString().trim().replace(',', '.')); } catch (Throwable t) { return def; }
    }

    private static int in(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Throwable t) { return def; }
    }

    // ------------------------------------------------------------------ العرض

    private void render() {
        LinearLayout root = scrollBody();
        JSONObject cur = st.optJSONObject("currency") == null ? new JSONObject() : st.optJSONObject("currency");

        LinearLayout c = section(root, "العملة", "تظهر في كل الأسعار داخل التطبيق.");
        curCode = field(c, "رمز العملة (مثل USD أو IQD)", cur.optString("code", "USD"), false, null);
        curSym = field(c, "الرمز المعروض (مثل $ أو د.ع)", cur.optString("symbol", "$"), false, null);
        curDec = num(c, "عدد الخانات العشرية (0–3)", String.valueOf(cur.optInt("decimals", 0)));

        JSONObject slots = st.optJSONObject("slots") == null ? new JSONObject() : st.optJSONObject("slots");
        for (int i = 0; i < SLOTS.length; i++) {
            JSONObject s = slots.optJSONObject(SLOTS[i]) == null ? new JSONObject() : slots.optJSONObject(SLOTS[i]);
            LinearLayout sc = section(root, "مساحة: " + SLOTS[i], null);
            EditText a = field(sc, "الاسم", s.optString("label"), false, null);
            EditText b = field(sc, "الوصف", s.optString("desc"), true, null);
            EditText d = field(sc, "مناسب لـ", s.optString("suited"), false, null);
            EditText p = num(sc, "السعر لليوم", trimNum(s.optDouble("price_per_day", 0)));
            EditText v = num(sc, "المشاهدات المتوقعة لليوم (تقدير)", String.valueOf(s.optLong("views_per_day", 0)));
            slotF[i] = new EditText[]{a, b, d, p, v};
            slotOn[i] = CgUi.toggle(this, "المساحة متاحة للحجز", s.optBoolean("enabled", true));
            sc.addView(slotOn[i]);
        }

        c = section(root, "المدد والخصومات", null);
        durations = field(c, "المدد المعروضة بالأيام", joinInts(st.optJSONArray("durations")), false, "افصل بفاصلة، مثال: 1, 3, 7, 30, 90");
        JSONObject cd = st.optJSONObject("custom_days") == null ? new JSONObject() : st.optJSONObject("custom_days");
        cMin = num(c, "أقل مدة مخصصة (يوم)", String.valueOf(cd.optInt("min", 1)));
        cMax = num(c, "أطول مدة مخصصة (يوم)", String.valueOf(cd.optInt("max", 730)));
        StringBuilder ds = new StringBuilder();
        JSONArray dj = st.optJSONArray("discounts");
        for (int i = 0; dj != null && i < dj.length(); i++) {
            JSONObject o = dj.optJSONObject(i);
            if (o == null) continue;
            ds.append(o.optInt("min_days")).append(" = ").append(trimNum(o.optDouble("pct"))).append('\n');
        }
        discounts = field(c, "الخصومات حسب المدة", ds.toString().trim(), true, "سطر لكل خصم بصيغة: عدد الأيام = النسبة المئوية. مثال: 30 = 10");

        JSONObject addons = st.optJSONObject("addons") == null ? new JSONObject() : st.optJSONObject("addons");
        c = section(root, "الإضافات", "النوع: نسبة مئوية من السعر أو مبلغ ثابت.");
        for (int i = 0; i < ADDONS.length; i++) {
            final int idx = i;
            JSONObject a = addons.optJSONObject(ADDONS[i]) == null ? new JSONObject() : addons.optJSONObject(ADDONS[i]);
            c.addView(CgUi.text(this, ADDONS[i], 13, CgCfg.MUTED, true));
            EditText l = field(c, "الاسم", a.optString("label"), false, null);
            EditText dsc = field(c, "الوصف", a.optString("desc"), false, null);
            EditText val = num(c, "القيمة", trimNum(a.optDouble("value", 0)));
            addPct[i] = "pct".equals(a.optString("type", "flat"));
            addTypeBtn[i] = CgUi.button(this, addPct[i] ? "النوع: نسبة مئوية %" : "النوع: مبلغ ثابت", 0);
            addTypeBtn[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    addPct[idx] = !addPct[idx];
                    addTypeBtn[idx].setText(addPct[idx] ? "النوع: نسبة مئوية %" : "النوع: مبلغ ثابت");
                }
            });
            c.addView(addTypeBtn[i], CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 10));
            addF[i] = new EditText[]{l, dsc, val};
        }

        JSONObject t = st.optJSONObject("targeting") == null ? new JSONObject() : st.optJSONObject("targeting");
        c = section(root, "الاستهداف", "الزيادة على السعر، ومعامل تقليل الوصول المتوقع (بين 0.01 و1).");
        tCityPct = num(c, "زيادة استهداف المدينة %", trimNum(t.optDouble("city_pct", 15)));
        tLangPct = num(c, "زيادة استهداف اللغة %", trimNum(t.optDouble("lang_pct", 10)));
        tHoursPct = num(c, "زيادة استهداف الساعات %", trimNum(t.optDouble("hours_pct", 10)));
        tCityRf = num(c, "معامل الوصول للمدينة", trimNum(t.optDouble("city_reach_factor", 0.5)));
        tLangRf = num(c, "معامل الوصول للغة", trimNum(t.optDouble("lang_reach_factor", 0.7)));
        tHoursRf = num(c, "معامل الوصول للساعات", trimNum(t.optDouble("hours_reach_factor", 0.6)));
        StringBuilder cs = new StringBuilder();
        JSONArray cj = t.optJSONArray("cities");
        for (int i = 0; cj != null && i < cj.length(); i++) {
            JSONObject o = cj.optJSONObject(i);
            if (o == null) continue;
            cs.append(o.optString("id")).append(" | ").append(o.optString("ar")).append(" | ").append(joinStrs(o.optJSONArray("aliases"))).append('\n');
        }
        tCities = field(c, "المدن", cs.toString().trim(), true, "سطر لكل مدينة: المعرّف بالإنجليزية | الاسم بالعربية | أسماء بديلة مفصولة بفاصلة");
        StringBuilder ls = new StringBuilder();
        JSONArray lj = t.optJSONArray("langs");
        for (int i = 0; lj != null && i < lj.length(); i++) {
            JSONObject o = lj.optJSONObject(i);
            if (o == null) continue;
            ls.append(o.optString("id")).append(" | ").append(o.optString("label")).append('\n');
        }
        tLangs = field(c, "اللغات", ls.toString().trim(), true, "سطر لكل لغة: الرمز | الاسم. مثال: ar | العربية");

        // 18.2: the user-facing «أعلن هنا» page was removed from the Cigram app. Its content editors (rules, FAQ, contact
        // buttons, page header) are no longer shown and no longer saved — the stored values stay untouched on the server.
        // Prices, policies (still asked before the first support message by the current server) and work hours remain.
        final LinearLayout legacy = CgUi.vbox(this);
        JSONObject rules = st.optJSONObject("rules") == null ? new JSONObject() : st.optJSONObject("rules");
        c = section(legacy, "قواعد الإعلان", "تظهر للمعلن في صفحة «أعلن هنا».");
        rAccepted = field(c, "المقبول", titleText(rules.optJSONArray("accepted")), true, "سطر لكل قاعدة: العنوان | الشرح");
        rRejected = field(c, "المرفوض", titleText(rules.optJSONArray("rejected")), true, "سطر لكل قاعدة: العنوان | الشرح");

        c = section(root, "السياسات والشروط", "تظهر للمستخدم قبل أول رسالة في «الدعم الخاص» ما دام الخادم يطلب الموافقة. أي تعديل على النص يرفع رقم الإصدار تلقائياً فيُطلب من كل معلن الموافقة من جديد قبل التواصل.");
        polBox = CgUi.vbox(this);
        c.addView(polBox);
        JSONArray pj = st.optJSONArray("policies");
        for (int i = 0; pj != null && i < pj.length(); i++) addPolicyRow(pj.optJSONObject(i));
        TextView addP = CgUi.button(this, "＋ إضافة سياسة", 1);
        addP.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { addPolicyRow(null); } });
        c.addView(addP, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 6));
        bumpVersion = CgUi.toggle(this, "رفع الإصدار وطلب موافقة جديدة (حتى بدون تعديل النص)", false);
        c.addView(bumpVersion);
        c.addView(CgUi.muted(this, "الإصدار الحالي: " + st.optInt("policies_version", 1)));

        c = section(legacy, "الأسئلة الشائعة", null);
        faqBox = CgUi.vbox(this);
        c.addView(faqBox);
        JSONArray fj = st.optJSONArray("faq");
        for (int i = 0; fj != null && i < fj.length(); i++) addFaqRow(fj.optJSONObject(i));
        TextView addF2 = CgUi.button(this, "＋ إضافة سؤال", 1);
        addF2.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { addFaqRow(null); } });
        c.addView(addF2, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));

        JSONObject ct = st.optJSONObject("contact") == null ? new JSONObject() : st.optJSONObject("contact");
        c = section(legacy, "التواصل", "تظهر أزرار واتساب وX في صفحة «أعلن هنا» فقط بعد موافقة المعلن على السياسات.");
        cWa = field(c, "رقم واتساب (أرقام فقط مع رمز الدولة)", ct.optString("whatsapp"), false, null);
        cWaMsg = field(c, "رسالة واتساب الجاهزة", ct.optString("whatsapp_message"), true, null);
        cXUrl = field(c, "رابط حساب X (https فقط)", ct.optString("x_url"), false, null);
        cXHandle = field(c, "معرّف X بدون @", ct.optString("x_handle"), false, null);

        JSONObject h = st.optJSONObject("hours") == null ? new JSONObject() : st.optJSONObject("hours");
        c = section(root, "ساعات العمل والرد التلقائي", "خارج الساعات يصل المعلن ردّ تلقائي واحد كل 6 ساعات كحد أقصى.");
        hoursOn = CgUi.toggle(this, "تفعيل ساعات العمل", h.optBoolean("enabled", true));
        c.addView(hoursOn);
        hTz = num(c, "فرق التوقيت عن UTC بالدقائق (بغداد = 180)", String.valueOf(h.optInt("tz_offset_min", 180)));
        hFrom = field(c, "من (HH:MM)", h.optString("from", "09:00"), false, null);
        hTo = field(c, "إلى (HH:MM)", h.optString("to", "22:00"), false, null);
        boolean[] days = new boolean[7];
        JSONArray dayArr = h.optJSONArray("days");
        for (int i = 0; dayArr != null && i < dayArr.length(); i++) {
            int d = dayArr.optInt(i, -1);
            if (d >= 0 && d < 7) days[d] = true;
        }
        for (int i = 0; i < 7; i++) {
            dayT[i] = CgUi.toggle(this, DAYS[i], days[i]);
            c.addView(dayT[i]);
        }
        autoReply = field(c, "نص الرد التلقائي", st.optString("auto_reply"), true, null);

        JSONObject hero = st.optJSONObject("hero") == null ? new JSONObject() : st.optJSONObject("hero");
        c = section(legacy, "رأس صفحة «أعلن هنا»", "الأرقام المقاسة تُحسب من نشاط آخر 7 أيام. اترك الأرقام اليدوية 0 لاستخدام المقاسة. لا تضع رقماً غير حقيقي.");
        heroTitle = field(c, "العنوان", hero.optString("title"), false, null);
        heroSub = field(c, "العنوان الفرعي", hero.optString("subtitle"), true, null);
        heroUsers = num(c, "مستخدمون نشطون يدوياً (0 = تلقائي)", String.valueOf(hero.optLong("manual_users", 0)));
        heroViews = num(c, "مشاهدات يومية يدوياً (0 = تلقائي)", String.valueOf(hero.optLong("manual_daily_views", 0)));
        c.addView(CgUi.muted(this, "المقاس حالياً: مستخدمون " + measured.optLong("active_users") + " • مشاهدات يومية " + measured.optLong("avg_daily_views")));

        TextView save = CgUi.button(this, "حفظ كل الإعدادات", 1);
        save.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(); } });
        root.addView(save, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 24));
    }

    private void addPolicyRow(JSONObject p) {
        final LinearLayout card = CgUi.card(this);
        final String pid = p == null ? "" : p.optString("id");
        EditText ti = CgUi.field(this, "عنوان السياسة", false);
        ti.setText(p == null ? "" : p.optString("title"));
        EditText bo = CgUi.field(this, "النص", true);
        bo.setText(p == null ? "" : p.optString("body"));
        bo.setMinLines(5);
        card.addView(CgUi.labeled(this, "عنوان السياسة", ti));
        card.addView(CgUi.labeled(this, "النص", bo));
        final View[] row = new View[]{card, ti, bo, new View(this)};
        row[3].setTag(pid);
        TextView del = CgUi.smallButton(this, "حذف هذه السياسة", 2);
        del.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { polBox.removeView(card); polRows.remove(row); }
        });
        card.addView(del);
        polRows.add(row);
        polBox.addView(card, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
    }

    private void addFaqRow(JSONObject f) {
        final LinearLayout card = CgUi.card(this);
        EditText q = CgUi.field(this, "السؤال", false);
        q.setText(f == null ? "" : f.optString("q"));
        EditText a = CgUi.field(this, "الجواب", true);
        a.setText(f == null ? "" : f.optString("a"));
        card.addView(CgUi.labeled(this, "السؤال", q));
        card.addView(CgUi.labeled(this, "الجواب", a));
        final EditText[] row = new EditText[]{q, a};
        TextView del = CgUi.smallButton(this, "حذف السؤال", 2);
        del.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { faqBox.removeView(card); faqRows.remove(row); }
        });
        card.addView(del);
        faqRows.add(row);
        faqBox.addView(card, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
    }

    // ------------------------------------------------------------------ تحويلات نصية

    private static String joinInts(JSONArray a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) sb.append(i > 0 ? ", " : "").append(a.optInt(i));
        return sb.toString();
    }

    private static String joinStrs(JSONArray a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) sb.append(i > 0 ? ", " : "").append(a.optString(i));
        return sb.toString();
    }

    private static String titleText(JSONArray a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            sb.append(o.optString("title")).append(" | ").append(o.optString("text")).append('\n');
        }
        return sb.toString().trim();
    }

    private static String[] splitList(String s) {
        return s.split("[,،]");
    }

    private JSONArray parseRules(EditText e) throws Exception {
        JSONArray out = new JSONArray();
        for (String line : e.getText().toString().split("\n")) {
            String l = line.trim();
            if (l.length() == 0) continue;
            int k = l.indexOf('|');
            String t = (k < 0 ? l : l.substring(0, k)).trim();
            String x = k < 0 ? "" : l.substring(k + 1).trim();
            if (t.length() > 0) out.put(new JSONObject().put("title", t).put("text", x));
        }
        return out;
    }

    // ------------------------------------------------------------------ الحفظ

    private void save() {
        if (curCode == null) return;
        try {
            JSONObject patch = new JSONObject();
            patch.put("currency", new JSONObject().put("code", curCode.getText().toString().trim())
                    .put("symbol", curSym.getText().toString().trim()).put("decimals", in(curDec, 0)));

            JSONObject slots = new JSONObject();
            for (int i = 0; i < SLOTS.length; i++) {
                EditText[] f = slotF[i];
                if (f == null) continue;
                if (f[0].getText().toString().trim().length() == 0) { CgUi.toast(this, "اسم المساحة (" + SLOTS[i] + ") مطلوب."); return; }
                slots.put(SLOTS[i], new JSONObject().put("label", f[0].getText().toString().trim()).put("desc", f[1].getText().toString().trim())
                        .put("suited", f[2].getText().toString().trim()).put("price_per_day", dbl(f[3], 0)).put("views_per_day", in(f[4], 0))
                        .put("enabled", slotOn[i].on));
            }
            patch.put("slots", slots);

            JSONArray dur = new JSONArray();
            for (String s : splitList(durations.getText().toString())) {
                try { int d = Integer.parseInt(s.trim()); if (d > 0) dur.put(d); } catch (Throwable ignored) { }
            }
            if (dur.length() == 0) { CgUi.toast(this, "أدخل مدة واحدة على الأقل."); return; }
            patch.put("durations", dur);
            patch.put("custom_days", new JSONObject().put("min", in(cMin, 1)).put("max", in(cMax, 730)));

            JSONArray dis = new JSONArray();
            for (String line : discounts.getText().toString().split("\n")) {
                String[] kv = line.split("=");
                if (kv.length != 2) continue;
                try {
                    dis.put(new JSONObject().put("min_days", Integer.parseInt(kv[0].trim())).put("pct", Double.parseDouble(kv[1].trim().replace(',', '.'))));
                } catch (Throwable ignored) { }
            }
            patch.put("discounts", dis);

            JSONObject add = new JSONObject();
            for (int i = 0; i < ADDONS.length; i++) {
                EditText[] f = addF[i];
                if (f == null) continue;
                if (f[0].getText().toString().trim().length() == 0) { CgUi.toast(this, "اسم الإضافة (" + ADDONS[i] + ") مطلوب."); return; }
                add.put(ADDONS[i], new JSONObject().put("label", f[0].getText().toString().trim()).put("desc", f[1].getText().toString().trim())
                        .put("value", dbl(f[2], 0)).put("type", addPct[i] ? "pct" : "flat"));
            }
            patch.put("addons", add);

            JSONObject tg = new JSONObject();
            tg.put("city_pct", dbl(tCityPct, 15)).put("lang_pct", dbl(tLangPct, 10)).put("hours_pct", dbl(tHoursPct, 10));
            tg.put("city_reach_factor", dbl(tCityRf, 0.5)).put("lang_reach_factor", dbl(tLangRf, 0.7)).put("hours_reach_factor", dbl(tHoursRf, 0.6));
            JSONArray cities = new JSONArray();
            for (String line : tCities.getText().toString().split("\n")) {
                String[] p = line.split("\\|");
                if (p.length < 2) continue;
                JSONArray al = new JSONArray();
                if (p.length > 2) for (String a : splitList(p[2])) if (a.trim().length() > 0) al.put(a.trim());
                cities.put(new JSONObject().put("id", p[0].trim()).put("ar", p[1].trim()).put("aliases", al));
            }
            tg.put("cities", cities);
            JSONArray langs = new JSONArray();
            for (String line : tLangs.getText().toString().split("\n")) {
                String[] p = line.split("\\|");
                if (p.length < 2) continue;
                langs.put(new JSONObject().put("id", p[0].trim()).put("label", p[1].trim()));
            }
            tg.put("langs", langs);
            patch.put("targeting", tg);

            // 18.2: rules / faq / contact / hero belong to the removed «أعلن هنا» page and are not sent (server keeps them)

            JSONArray pols = new JSONArray();
            for (View[] r : polRows) {
                String ti = ((EditText) r[1]).getText().toString().trim();
                String bo = ((EditText) r[2]).getText().toString().trim();
                if (ti.length() == 0 && bo.length() == 0) continue;
                if (ti.length() == 0 || bo.length() == 0) { CgUi.toast(this, "أكمل عنوان ونص كل سياسة أو احذفها."); return; }
                Object tag = r[3].getTag();
                pols.put(new JSONObject().put("id", tag == null ? "" : String.valueOf(tag)).put("title", ti).put("body", bo));
            }
            if (pols.length() == 0) { CgUi.toast(this, "يجب وجود سياسة واحدة على الأقل."); return; }
            patch.put("policies", pols);
            if (bumpVersion.on) patch.put("bump_policies_version", true);

            JSONArray faq = new JSONArray();
            for (EditText[] r : faqRows) {
                String q = r[0].getText().toString().trim(), a = r[1].getText().toString().trim();
                if (q.length() > 0 && a.length() > 0) faq.put(new JSONObject().put("q", q).put("a", a));
            }


            JSONArray days = new JSONArray();
            for (int i = 0; i < 7; i++) if (dayT[i] != null && dayT[i].on) days.put(i);
            patch.put("hours", new JSONObject().put("enabled", hoursOn.on).put("tz_offset_min", in(hTz, 180))
                    .put("from", hFrom.getText().toString().trim()).put("to", hTo.getText().toString().trim()).put("days", days));
            patch.put("auto_reply", autoReply.getText().toString().trim());



            showLoading("جارٍ الحفظ...");
            AdApi.post(this, "/admin/settings", new JSONObject().put("patch", patch), new AdApi.Cb() {
                @Override public void on(AdApi.Result r) {
                    hideLoading();
                    if (!alive()) return;
                    if (r.ok) {
                        CgUi.toast(AdSettingsActivity.this, "تم الحفظ.");
                        load();
                    } else {
                        CgUi.toast(AdSettingsActivity.this, r.message);
                    }
                }
            });
        } catch (Throwable t) {
            hideLoading();
            CgUi.toast(this, "تعذّر تجهيز البيانات. راجع الحقول وأعد المحاولة.");
        }
    }
}
