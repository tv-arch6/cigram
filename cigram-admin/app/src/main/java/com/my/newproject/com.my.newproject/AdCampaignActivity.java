package com.my.newproject;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;

/**
 * تفاصيل/محرر الحملة: الحالة والإجراءات (موافقة/رفض/إيقاف/تمديد/تجديد...)، النموذج الكامل مع الاستهداف والتسعير،
 * المواد الإعلانية مع رفع ومعاينة، والتقرير (رابط عام وصورة قابلة للمشاركة). Extras: id أو advertiser_id.
 */
public class AdCampaignActivity extends CgBase {

    private static final int REQ_MEDIA = 91;

    private String id = "", advId = "", advName = "";
    private JSONObject camp = new JSONObject();
    private JSONArray creatives = new JSONArray();
    private JSONObject settings = new JSONObject();
    private JSONObject report;
    private String reportToken = "";

    // حالة النموذج
    private String slot = "hero", paidStatus = "unpaid", orderStage = "received";
    private long startAt, endAt;
    private final HashSet<String> cities = new HashSet<String>();
    private final HashSet<String> langs = new HashSet<String>();
    private boolean hoursOn, excl, video, design;
    private int hFrom = 18, hTo = 23;
    private EditText fName, fPriority, fCap, fPrice, fPaid, fNotes;
    private TextView advBtn, slotBtn, startBtn, endBtn;
    private TextView priceInfo;

    @Override protected String screenTitle() { return "حملة"; }

    @Override protected void onBuild() {
        String i = getIntent().getStringExtra("id");
        id = i == null ? "" : i;
        String a = getIntent().getStringExtra("advertiser_id");
        advId = a == null ? "" : a;
        bar.action("حفظ", new Runnable() { @Override public void run() { save(); } });
        startAt = System.currentTimeMillis() + 3600000L;
        endAt = startAt + 7L * 86400000L;
        reload();
    }

    private void reload() {
        showLoading("جارٍ التحميل...");
        AdApi.get(this, "/admin/settings", new AdApi.Cb() {
            @Override public void on(AdApi.Result s) {
                if (!alive()) return;
                if (!s.ok) { hideLoading(); fatal(s.message); return; }
                settings = s.json.optJSONObject("settings");
                if (id.length() == 0) { hideLoading(); camp = new JSONObject(); creatives = new JSONArray(); loadForm(); render(); return; }
                AdApi.get(AdCampaignActivity.this, "/admin/campaigns/get?id=" + AdApi.enc(id), new AdApi.Cb() {
                    @Override public void on(AdApi.Result r) {
                        if (!alive()) return;
                        if (!r.ok) { hideLoading(); fatal(r.message); return; }
                        camp = r.json.optJSONObject("campaign");
                        creatives = r.json.optJSONArray("creatives") == null ? new JSONArray() : r.json.optJSONArray("creatives");
                        reportToken = camp.optString("report_token");
                        loadForm();
                        AdApi.get(AdCampaignActivity.this, "/admin/campaigns/report?id=" + AdApi.enc(id), new AdApi.Cb() {
                            @Override public void on(AdApi.Result rp) {
                                hideLoading();
                                if (!alive()) return;
                                report = rp.ok ? rp.json.optJSONObject("report") : null;
                                render();
                            }
                        });
                    }
                });
            }
        });
    }

    private void fatal(String m) {
        body.removeAllViews();
        body.addView(CgUi.errorCard(this, m, new Runnable() { @Override public void run() { reload(); } }), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 12, 12, 12, 0));
    }

    private void loadForm() {
        if (id.length() > 0) {
            advId = camp.optString("advertiser_id");
            advName = camp.optString("advertiser_name");
            slot = camp.optString("slot", "hero");
            startAt = camp.optLong("start_at");
            endAt = camp.optLong("end_at");
            paidStatus = camp.optString("paid_status", "unpaid");
            orderStage = camp.optString("order_stage", "received");
            JSONObject t = camp.optJSONObject("targeting");
            cities.clear(); langs.clear();
            hoursOn = false;
            if (t != null) {
                JSONArray c = t.optJSONArray("cities");
                for (int k = 0; c != null && k < c.length(); k++) cities.add(c.optString(k));
                JSONArray l = t.optJSONArray("langs");
                for (int k = 0; l != null && k < l.length(); k++) langs.add(l.optString(k));
                JSONObject h = t.optJSONObject("hours");
                if (h != null) { hoursOn = true; hFrom = h.optInt("from", 18); hTo = h.optInt("to", 23); }
            }
            JSONObject ad = camp.optJSONObject("addons");
            excl = ad != null && ad.optBoolean("exclusive");
            video = ad != null && ad.optBoolean("video");
            design = ad != null && ad.optBoolean("design");
        }
    }

    // ------------------------------------------------------------------ واجهة

    private void render() {
        LinearLayout c = scrollBody();
        final String stage = camp.optString("stage", "");
        if (id.length() > 0) {
            bar.titleView.setText(camp.optString("name", "حملة"));
            LinearLayout head = CgUi.card(this);
            LinearLayout h = CgUi.hbox(this);
            h.addView(CgUi.badge(this, AdApi.stageLabel(stage), AdApi.stageColor(stage)));
            h.addView(CgUi.text(this, "  " + advName, 14, CgCfg.TEXT, true));
            head.addView(h);
            head.addView(CgUi.muted(this, "السعر " + AdApi.money(camp.optDouble("price"), camp.optString("currency")) + " • المدفوع " + AdApi.money(camp.optDouble("paid_amount"), "")));
            if (camp.optString("reject_reason").length() > 0) head.addView(CgUi.text(this, "سبب الرفض: " + camp.optString("reject_reason"), 12.5f, CgCfg.BAD, false));
            if (camp.optBoolean("expiring_soon")) head.addView(CgUi.text(this, "تنتهي خلال 3 أيام", 12.5f, CgCfg.WARN, true));
            if (camp.optInt("reports") > 0) head.addView(CgUi.text(this, "عليها " + camp.optInt("reports") + " بلاغ", 12.5f, CgCfg.BAD, true));
            c.addView(head, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
            actionsRow(c);
            reportCard(c);
        }
        formCard(c);
        if (id.length() > 0) creativesCard(c);
        else c.addView(CgUi.muted(this, "احفظ الحملة أولاً ثم أضف موادها الإعلانية."));
    }

    private void actionsRow(LinearLayout c) {
        String status = camp.optString("status");
        LinearLayout box = CgUi.card(this);
        box.addView(CgUi.sectionTitle(this, "الإجراءات"));
        com.my.newproject.AdFlow flow = new com.my.newproject.AdFlow(this);
        if (!"approved".equals(status)) flow.add(btn("موافقة ونشر", 0, new Runnable() { @Override public void run() { act("approve", null, "تمت الموافقة. الإعلان يظهر حسب الجدول."); } }));
        if (!"rejected".equals(status)) flow.add(btn("رفض", 2, new Runnable() { @Override public void run() { reject(); } }));
        if ("approved".equals(status)) flow.add(btn("⏸ إيقاف", 1, new Runnable() { @Override public void run() { act("pause", null, "تم الإيقاف."); } }));
        if ("paused".equals(status)) flow.add(btn("▶ استئناف", 0, new Runnable() { @Override public void run() { act("resume", null, "تم الاستئناف."); } }));
        flow.add(btn("تمديد", 1, new Runnable() { @Override public void run() { askDays("تمديد الحملة", "عدد الأيام المضافة", "extend"); } }));
        flow.add(btn("تجديد", 1, new Runnable() { @Override public void run() { askDays("تجديد (نسخة جديدة)", "مدة الحملة الجديدة بالأيام", "renew"); } }));
        flow.add(btn("حذف", 2, new Runnable() {
            @Override public void run() {
                // 2026: the campaign is named in the confirmation, never only its internal id
                String cname = camp.optString("name", "").trim();
                CgUi.confirm(AdCampaignActivity.this, "حذف الحملة «" + (cname.length() > 0 ? cname : "بلا اسم") + "»",
                        "ستُحذف حملة «" + (cname.length() > 0 ? cname : "بلا اسم") + "» ومواد الإعلان وإحصاءاتها نهائياً. لا يمكن التراجع.",
                        "حذف", true, new Runnable() { @Override public void run() { act("delete", null, "تم حذف «" + camp.optString("name", "") + "»."); } });
            }
        }));
        box.addView(flow);
        c.addView(box, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
    }

    private TextView btn(String label, int style, final Runnable r) {
        TextView t = CgUi.smallButton(this, label, style);
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { r.run(); } });
        return t;
    }

    private void act(final String action, JSONObject extra, final String doneMsg) {
        try {
            JSONObject b = extra == null ? new JSONObject() : extra;
            b.put("id", id);
            b.put("action", action);
            showLoading("جارٍ التنفيذ...");
            AdApi.post(this, "/admin/campaigns/action", b, new AdApi.Cb() {
                @Override public void on(AdApi.Result r) {
                    hideLoading();
                    if (!alive()) return;
                    if (!r.ok) {
                        String m = r.message;
                        if ("no_creative".equals(r.error)) m = "أضف مادة إعلانية فعّالة قبل الموافقة.";
                        CgUi.info(AdCampaignActivity.this, "تعذر التنفيذ", m);
                        return;
                    }
                    CgUi.toast(AdCampaignActivity.this, doneMsg);
                    if ("delete".equals(action)) { finish(); return; }
                    String renewed = r.json.optString("renewed_id");
                    if (renewed.length() > 0) { Intent in = new Intent(AdCampaignActivity.this, AdCampaignActivity.class); in.putExtra("id", renewed); startActivity(in); finish(); return; }
                    reload();
                }
            });
        } catch (Throwable t) { hideLoading(); }
    }

    private void reject() {
        CgUi.input(this, "سبب الرفض (يصل للمعلن)", "مثال: الصورة غير واضحة / محتوى غير مسموح", "", true, new CgUi.Callback<String>() {
            @Override public void run(String v) {
                if (v.length() == 0) { CgUi.toast(AdCampaignActivity.this, "اكتب سبب الرفض."); return; }
                try { act("reject", new JSONObject().put("reason", v), "تم الرفض وإبلاغ المعلن."); } catch (Throwable ignored) { }
            }
        });
    }

    private void askDays(String title, String hint, final String action) {
        CgUi.input(this, title, hint, "7", false, new CgUi.Callback<String>() {
            @Override public void run(String v) {
                try { int d = Integer.parseInt(v.trim()); act(action, new JSONObject().put("days", d), "تم."); } catch (Throwable e) { CgUi.toast(AdCampaignActivity.this, "أدخل عدد أيام صحيحاً."); }
            }
        });
    }

    // ------------------------------------------------------------------ التقرير

    private void reportCard(LinearLayout c) {
        LinearLayout box = CgUi.card(this);
        box.addView(CgUi.sectionTitle(this, "الأداء والتقرير"));
        if (report != null) {
            LinearLayout row = CgUi.hbox(this);
            row.addView(num("المشاهدات", String.valueOf(report.optLong("impressions"))), CgUi.lpWeight(this, 1f, 0, 0, 4, 0));
            row.addView(num("النقرات", String.valueOf(report.optLong("clicks"))), CgUi.lpWeight(this, 1f, 2, 0, 2, 0));
            row.addView(num("الفريدون", String.valueOf(report.optLong("unique"))), CgUi.lpWeight(this, 1f, 2, 0, 2, 0));
            row.addView(num("CTR", report.optString("ctr") + "%"), CgUi.lpWeight(this, 1f, 4, 0, 0, 0));
            box.addView(row);
            JSONArray d = report.optJSONArray("daily");
            if (d != null && d.length() > 0) box.addView(new Bars(this, d), CgUi.lp(this, CgUi.MATCH, CgUi.dp(this, 70) / 1, 0, 8, 0, 0));
        }
        LinearLayout btns = CgUi.hbox(this);
        TextView share = CgUi.smallButton(this, "رابط التقرير", 0);
        share.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { shareLink(); } });
        TextView img = CgUi.smallButton(this, "صورة التقرير", 1);
        img.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { shareImage(); } });
        btns.addView(share, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
        btns.addView(img);
        box.addView(btns, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        if (reportToken.length() > 0) {
            TextView rev = CgUi.smallButton(this, "إلغاء الرابط الحالي وإنشاء رابط جديد", 3);
            rev.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    CgUi.confirm(AdCampaignActivity.this, "رابط جديد", "الرابط القديم سيتوقف عن العمل فوراً.", "إنشاء", false, new Runnable() { @Override public void run() { act("new_report_token", null, "تم إنشاء رابط جديد."); } });
                }
            });
            box.addView(rev);
        }
        c.addView(box, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
    }

    private View num(String label, String v) {
        LinearLayout l = CgUi.vbox(this);
        l.addView(CgUi.text(this, v, 17, CgCfg.TEXT, true));
        l.addView(CgUi.muted(this, label));
        return l;
    }

    private void shareLink() {
        if (reportToken.length() == 0) { act("new_report_token", null, "تم إنشاء رابط التقرير. اضغط مشاركة مجدداً."); return; }
        String url = AdApi.BASE + "/report/" + reportToken;
        try {
            Intent in = new Intent(Intent.ACTION_SEND);
            in.setType("text/plain");
            in.putExtra(Intent.EXTRA_TEXT, "تقرير حملة «" + camp.optString("name") + "»: " + url);
            startActivity(Intent.createChooser(in, "مشاركة رابط التقرير"));
        } catch (Throwable ignored) { }
    }

    private void shareImage() {
        if (report == null) { CgUi.toast(this, "لا توجد بيانات بعد."); return; }
        try {
            Bitmap b = renderReport();
            String u = MediaStore.Images.Media.insertImage(getContentResolver(), b, "تقرير " + camp.optString("name"), "تقرير حملة إعلانية");
            if (u == null) { CgUi.toast(this, "تعذر حفظ الصورة."); return; }
            Intent in = new Intent(Intent.ACTION_SEND);
            in.setType("image/png");
            in.putExtra(Intent.EXTRA_STREAM, Uri.parse(u));
            in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(in, "مشاركة صورة التقرير"));
        } catch (Throwable t) { CgUi.toast(this, "تعذر إنشاء الصورة."); }
    }

    private Bitmap renderReport() {
        int w = 1080, h = 1000;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(0xFF0B2733);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(54);
        p.setFakeBoldText(true);
        cv.drawText(camp.optString("name"), w / 2f, 110, p);
        p.setFakeBoldText(false);
        p.setTextSize(34);
        p.setColor(0xFF8FA1AA);
        cv.drawText(advName + " • " + AdApi.slotLabel(settings, slot), w / 2f, 170, p);
        String[] labels = {"المشاهدات", "النقرات", "الفريدون", "نسبة النقر"};
        String[] vals = {String.valueOf(report.optLong("impressions")), String.valueOf(report.optLong("clicks")), String.valueOf(report.optLong("unique")), report.optString("ctr") + "%"};
        for (int i = 0; i < 4; i++) {
            float cx = 135 + i * 270;
            p.setColor(0xFFFFFFFF); p.setTextSize(58); p.setFakeBoldText(true);
            cv.drawText(vals[i], cx, 320, p);
            p.setFakeBoldText(false); p.setColor(0xFF8FA1AA); p.setTextSize(30);
            cv.drawText(labels[i], cx, 366, p);
        }
        JSONArray d = report.optJSONArray("daily");
        if (d != null && d.length() > 0) {
            int max = 1;
            for (int i = 0; i < d.length(); i++) max = Math.max(max, d.optJSONObject(i).optInt("i"));
            float slotW = 960f / d.length();
            p.setColor(0xFF26A5B5);
            RectF r = new RectF();
            for (int i = 0; i < d.length(); i++) {
                float bh = 420f * d.optJSONObject(i).optInt("i") / max;
                r.set(60 + i * slotW + slotW * 0.12f, 900 - bh, 60 + (i + 1) * slotW - slotW * 0.12f, 900);
                cv.drawRoundRect(r, 8, 8, p);
            }
        }
        p.setColor(0xFF8FA1AA); p.setTextSize(28);
        cv.drawText(AdApi.date(camp.optLong("start_at")).substring(0, 10) + "  ←  " + AdApi.date(camp.optLong("end_at")).substring(0, 10), w / 2f, 960, p);
        return b;
    }

    private static final class Bars extends View {
        private final int[] v;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        Bars(android.content.Context c, JSONArray a) {
            super(c);
            v = new int[a.length()];
            for (int i = 0; i < v.length; i++) v[i] = a.optJSONObject(i).optInt("i");
        }
        @Override protected void onDraw(Canvas cv) {
            int max = 1;
            for (int x : v) max = Math.max(max, x);
            float slot = getWidth() / (float) v.length;
            p.setColor(CgCfg.ACCENT);
            for (int i = 0; i < v.length; i++) {
                float h = Math.max(2f, getHeight() * v[i] / max);
                r.set(i * slot + slot * 0.15f, getHeight() - h, (i + 1) * slot - slot * 0.15f, getHeight());
                cv.drawRoundRect(r, 3f, 3f, p);
            }
        }
    }

    // ------------------------------------------------------------------ النموذج

    private EditText field(LinearLayout c, String label, String val, boolean multi, boolean numeric) {
        EditText e = numeric ? CgUi.numberField(this, label) : CgUi.field(this, label, multi);
        e.setText(val);
        c.addView(CgUi.labeled(this, label, e));
        return e;
    }

    private void formCard(LinearLayout c) {
        LinearLayout box = CgUi.card(this);
        box.addView(CgUi.sectionTitle(this, id.length() == 0 ? "حملة جديدة" : "تعديل الحملة"));
        fName = field(box, "اسم الحملة", camp.optString("name"), false, false);
        advBtn = CgUi.button(this, advName.length() > 0 ? advName : (advId.length() > 0 ? "معلن محدد" : "اختر المعلن"), 1);
        advBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { chooseAdvertiser(); } });
        box.addView(CgUi.labeled(this, "المعلن", advBtn));
        slotBtn = CgUi.button(this, AdApi.slotLabel(settings, slot), 1);
        slotBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { chooseSlot(); } });
        box.addView(CgUi.labeled(this, "المساحة", slotBtn));
        startBtn = CgUi.button(this, AdApi.date(startAt), 1);
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { CgUi.pickDateTime(AdCampaignActivity.this, CgUi.toIso(startAt), new CgUi.Callback<String>() { @Override public void run(String iso) { long t = CgUi.parseIso(iso); if (t > 0) { startAt = t; startBtn.setText(AdApi.date(t)); updateInfo(); } } }); }
        });
        box.addView(CgUi.labeled(this, "البداية", startBtn));
        endBtn = CgUi.button(this, AdApi.date(endAt), 1);
        endBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { CgUi.pickDateTime(AdCampaignActivity.this, CgUi.toIso(endAt), new CgUi.Callback<String>() { @Override public void run(String iso) { long t = CgUi.parseIso(iso); if (t > 0) { endAt = t; endBtn.setText(AdApi.date(t)); updateInfo(); } } }); }
        });
        box.addView(CgUi.labeled(this, "النهاية", endBtn));
        fPriority = field(box, "الأولوية (1-10، الأعلى يظهر أكثر)", String.valueOf(camp.optInt("priority", 5)), false, true);
        fCap = field(box, "حد الظهور اليومي لكل مستخدم (0 = بلا حد)", String.valueOf(camp.optInt("daily_cap", 0)), false, true);

        // استهداف
        box.addView(CgUi.sectionTitle(this, "الاستهداف (اتركه فارغاً لكل الجمهور)"));
        JSONObject tg = settings.optJSONObject("targeting");
        com.my.newproject.AdFlow cf = new com.my.newproject.AdFlow(this);
        JSONArray ca = tg == null ? null : tg.optJSONArray("cities");
        for (int i = 0; ca != null && i < ca.length(); i++) {
            final String cid = ca.optJSONObject(i).optString("id");
            final TextView t = CgUi.chip(this, ca.optJSONObject(i).optString("ar"), cities.contains(cid));
            t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (cities.contains(cid)) cities.remove(cid); else cities.add(cid); CgUi.setChipState(t, cities.contains(cid)); updateInfo(); } });
            cf.add(t);
        }
        box.addView(cf);
        com.my.newproject.AdFlow lf = new com.my.newproject.AdFlow(this);
        JSONArray la = tg == null ? null : tg.optJSONArray("langs");
        for (int i = 0; la != null && i < la.length(); i++) {
            final String lid = la.optJSONObject(i).optString("id");
            final TextView t = CgUi.chip(this, la.optJSONObject(i).optString("label"), langs.contains(lid));
            t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (langs.contains(lid)) langs.remove(lid); else langs.add(lid); CgUi.setChipState(t, langs.contains(lid)); updateInfo(); } });
            lf.add(t);
        }
        box.addView(CgUi.sectionTitle(this, "اللغات"));
        box.addView(lf);
        final CgUi.Toggle th = CgUi.toggle(this, "تحديد ساعات الظهور", hoursOn);
        final TextView hb = CgUi.button(this, hFrom + ":00 → " + hTo + ":00", 1);
        hb.setVisibility(hoursOn ? View.VISIBLE : View.GONE);
        th.onChange(new Runnable() { @Override public void run() { hoursOn = th.on; hb.setVisibility(hoursOn ? View.VISIBLE : View.GONE); updateInfo(); } });
        hb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String[] hrs = new String[24];
                for (int i = 0; i < 24; i++) hrs[i] = i + ":00";
                CgUi.choose(AdCampaignActivity.this, "من الساعة", hrs, new CgUi.IntCb() {
                    @Override public void run(final int f) {
                        String[] h2 = new String[24];
                        for (int i = 0; i < 24; i++) h2[i] = (i + 1) + ":00";
                        CgUi.choose(AdCampaignActivity.this, "إلى الساعة", h2, new CgUi.IntCb() { @Override public void run(int t) { hFrom = f; hTo = t + 1; hb.setText(hFrom + ":00 → " + hTo + ":00"); updateInfo(); } });
                    }
                });
            }
        });
        box.addView(th);
        box.addView(hb);

        // إضافات
        box.addView(CgUi.sectionTitle(this, "الإضافات"));
        final CgUi.Toggle te = CgUi.toggle(this, "حصرية المساحة", excl);
        te.onChange(new Runnable() { @Override public void run() { excl = te.on; updateInfo(); } });
        final CgUi.Toggle tv = CgUi.toggle(this, "إعلان فيديو", video);
        tv.onChange(new Runnable() { @Override public void run() { video = tv.on; updateInfo(); } });
        final CgUi.Toggle td = CgUi.toggle(this, "تصميم من الفريق", design);
        td.onChange(new Runnable() { @Override public void run() { design = td.on; updateInfo(); } });
        box.addView(te); box.addView(tv); box.addView(td);

        // السعر والدفع
        box.addView(CgUi.sectionTitle(this, "السعر والدفع"));
        priceInfo = CgUi.muted(this, "");
        box.addView(priceInfo);
        fPrice = field(box, "السعر النهائي (اتركه فارغاً ليُحسب تلقائياً)", camp.optBoolean("price_manual") ? String.valueOf(camp.optDouble("price")) : "", false, true);
        final TextView ps = CgUi.button(this, payLabel(paidStatus), 1);
        ps.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(final View v) {
                final String[] k = {"unpaid", "partial", "paid"};
                CgUi.choose(AdCampaignActivity.this, "حالة الدفع", new CharSequence[]{"غير مدفوع", "مدفوع جزئياً", "مدفوع"}, new CgUi.IntCb() { @Override public void run(int i) { paidStatus = k[i]; ((TextView) v).setText(payLabel(paidStatus)); } });
            }
        });
        box.addView(CgUi.labeled(this, "حالة الدفع", ps));
        fPaid = field(box, "المبلغ المدفوع", camp.optDouble("paid_amount", 0) == 0 ? "" : String.valueOf(camp.optDouble("paid_amount")), false, true);
        final TextView os = CgUi.button(this, AdApi.stageLabel(orderStage), 1);
        os.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(final View v) {
                final String[] k = {"received", "review", "quoted", "awaiting_payment"};
                CgUi.choose(AdCampaignActivity.this, "مرحلة الطلب", new CharSequence[]{"مستلم", "قيد المراجعة", "عرض سعر", "بانتظار الدفع"}, new CgUi.IntCb() { @Override public void run(int i) { orderStage = k[i]; ((TextView) v).setText(AdApi.stageLabel(orderStage)); } });
            }
        });
        box.addView(CgUi.labeled(this, "مرحلة الطلب", os));
        fNotes = field(box, "ملاحظات داخلية", camp.optString("notes"), true, false);
        TextView saveBtn = CgUi.button(this, "حفظ الحملة", 0);
        saveBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(); } });
        box.addView(saveBtn, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        c.addView(box, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        updateInfo();
    }

    private static String payLabel(String s) { return "paid".equals(s) ? "مدفوع" : "partial".equals(s) ? "مدفوع جزئياً" : "غير مدفوع"; }

    /** يحسب السعر التقديري بنفس صيغة الخادم (للعرض فقط؛ الخادم هو المرجع). */
    private void updateInfo() {
        if (priceInfo == null) return;
        try {
            int days = (int) Math.max(1, Math.round((endAt - startAt) / 86400000.0));
            JSONObject req = new JSONObject();
            req.put("slot", slot);
            req.put("days", days);
            JSONObject t = new JSONObject();
            t.put("cities", new JSONArray(cities));
            t.put("langs", new JSONArray(langs));
            if (hoursOn) t.put("hours", new JSONObject().put("from", hFrom).put("to", hTo));
            req.put("targeting", t);
            req.put("addons", new JSONObject().put("exclusive", excl).put("video", video).put("design", design));
            AdApi.post(this, "/ads/quote", req, new AdApi.Cb() {
                @Override public void on(AdApi.Result r) {
                    if (!alive() || priceInfo == null) return;
                    if (r.ok) {
                        JSONObject q = r.json.optJSONObject("quote");
                        priceInfo.setText("السعر المحسوب: " + AdApi.money(q.optDouble("total"), q.optString("currency")) + " (" + q.optInt("days") + " يوم) • مشاهدات متوقعة " + q.optLong("views_min") + "–" + q.optLong("views_max"));
                    } else priceInfo.setText("");
                }
            });
        } catch (Throwable ignored) { }
    }

    private void chooseSlot() {
        final String[] slots = {"splash", "hero", "inline", "popup", "sticky", "sponsor"};
        CharSequence[] l = new CharSequence[slots.length];
        for (int i = 0; i < slots.length; i++) l[i] = AdApi.slotLabel(settings, slots[i]);
        CgUi.choose(this, "المساحة", l, new CgUi.IntCb() { @Override public void run(int i) { slot = slots[i]; slotBtn.setText(AdApi.slotLabel(settings, slot)); updateInfo(); } });
    }

    private void chooseAdvertiser() {
        AdApi.get(this, "/admin/advertisers", new AdApi.Cb() {
            @Override public void on(AdApi.Result r) {
                if (!alive()) return;
                final JSONArray a = r.json.optJSONArray("advertisers");
                if (!r.ok || a == null || a.length() == 0) { CgUi.toast(AdCampaignActivity.this, "لا يوجد معلنون. أضف معلناً أولاً."); return; }
                CharSequence[] l = new CharSequence[a.length()];
                for (int i = 0; i < a.length(); i++) l[i] = a.optJSONObject(i).optString("name");
                CgUi.choose(AdCampaignActivity.this, "المعلن", l, new CgUi.IntCb() { @Override public void run(int i) { advId = a.optJSONObject(i).optString("id"); advName = a.optJSONObject(i).optString("name"); advBtn.setText(advName); } });
            }
        });
    }

    private void save() {
        if (advId.length() == 0) { CgUi.toast(this, "اختر المعلن."); return; }
        String name = fName.getText().toString().trim();
        if (name.length() == 0) { CgUi.toast(this, "أدخل اسم الحملة."); return; }
        if (endAt <= startAt) { CgUi.toast(this, "النهاية يجب أن تكون بعد البداية."); return; }
        try {
            JSONObject b = new JSONObject();
            if (id.length() > 0) b.put("id", id);
            b.put("advertiser_id", advId);
            b.put("name", name);
            b.put("slot", slot);
            b.put("start_at", startAt);
            b.put("end_at", endAt);
            b.put("priority", parse(fPriority, 5));
            b.put("daily_cap", parse(fCap, 0));
            JSONObject t = new JSONObject();
            t.put("cities", new JSONArray(cities));
            t.put("langs", new JSONArray(langs));
            if (hoursOn) t.put("hours", new JSONObject().put("from", hFrom).put("to", hTo)); else t.put("hours", JSONObject.NULL);
            b.put("targeting", t);
            b.put("addons", new JSONObject().put("exclusive", excl).put("video", video).put("design", design));
            String pr = fPrice.getText().toString().trim();
            if (pr.length() > 0) b.put("price", Double.parseDouble(pr));
            b.put("paid_status", paidStatus);
            String pa = fPaid.getText().toString().trim();
            b.put("paid_amount", pa.length() == 0 ? 0 : Double.parseDouble(pa));
            b.put("order_stage", orderStage);
            b.put("notes", fNotes.getText().toString().trim());
            showLoading("جارٍ الحفظ...");
            AdApi.post(this, "/admin/campaigns/save", b, new AdApi.Cb() {
                @Override public void on(AdApi.Result r) {
                    hideLoading();
                    if (!alive()) return;
                    if (!r.ok) { CgUi.info(AdCampaignActivity.this, "تعذر الحفظ", r.message); return; }
                    CgUi.toast(AdCampaignActivity.this, "تم الحفظ.");
                    id = r.json.optJSONObject("campaign").optString("id");
                    reload();
                }
            });
        } catch (Throwable e) { hideLoading(); CgUi.toast(this, "تحقق من القيم المدخلة."); }
    }

    private int parse(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Throwable t) { return def; }
    }

    // ------------------------------------------------------------------ المواد الإعلانية

    private void creativesCard(LinearLayout c) {
        c.addView(CgUi.sectionTitle(this, "المواد الإعلانية (" + creatives.length() + ")"));
        for (int i = 0; i < creatives.length(); i++) {
            final JSONObject o = creatives.optJSONObject(i);
            final String label = creativeName(o, i);
            LinearLayout card = CgUi.card(this);
            card.addView(CgUi.text(this, label, 15f, CgCfg.TEXT, true), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));
            card.addView(buildPreview(this, slot, o.optString("type"), o.optString("media_url"), o.optString("thumb_url"), o.optString("title"), o.optString("body"), o.optString("cta"), o.optString("bg_color"), o.optString("text_color")));
            LinearLayout row = CgUi.hbox(this);
            row.addView(CgUi.badge(this, "image".equals(o.optString("type")) ? "صورة" : "video".equals(o.optString("type")) ? "فيديو" : "نص", CgCfg.ACCENT));
            row.addView(CgUi.badge(this, o.optBoolean("active") ? "فعّالة" : "متوقفة", o.optBoolean("active") ? CgCfg.GOOD : CgCfg.GRAY), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 4, 0, 0, 0));
            card.addView(row, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
            LinearLayout btns = CgUi.hbox(this);
            TextView e = CgUi.smallButton(this, "تعديل", 1);
            e.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { editCreative(o); } });
            TextView d = CgUi.smallButton(this, "حذف", 2);
            d.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    CgUi.confirm(AdCampaignActivity.this, "حذف «" + label + "»", "ستُحذف المادة الإعلانية «" + label + "» وملفها نهائياً.", "حذف", true, new Runnable() {
                        @Override public void run() {
                            try { AdApi.post(AdCampaignActivity.this, "/admin/creatives/delete", new JSONObject().put("id", o.optString("id")), new AdApi.Cb() { @Override public void on(AdApi.Result r) { if (r.ok) reload(); else CgUi.toast(AdCampaignActivity.this, r.message); } }); } catch (Throwable ignored) { }
                        }
                    });
                }
            });
            btns.addView(e, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 6, 0));
            btns.addView(d);
            card.addView(btns, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
            c.addView(card, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
        TextView add = CgUi.button(this, "＋ إضافة مادة إعلانية", 0);
        add.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { editCreative(null); } });
        c.addView(add, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
    }

    /**
     * 2026: every ad has a recognisable name. The name the admin typed («اسم الإعلان», saved as "name"); for older
     * records without one, its title; otherwise its type and position — never only an internal id.
     */
    static String creativeName(JSONObject o, int index) {
        if (o == null) return "مادة " + (index + 1);
        String n = o.optString("name", "").trim();
        if (n.length() > 0) return n;
        String t = o.optString("title", "").trim();
        if (t.length() > 0) return t;
        String type = o.optString("type");
        return ("video".equals(type) ? "إعلان فيديو " : "image".equals(type) ? "إعلان صورة " : "إعلان نصي ") + (index + 1);
    }

    /** معاينة تقريبية لشكل الإعلان داخل مساحته. */
    static View buildPreview(android.content.Context ctx, String slot, String type, String mediaUrl, String thumbUrl, String title, String bodyText, String cta, String bg, String fg) {
        int wDp = 300;
        int hDp = "splash".equals(slot) ? 400 : "hero".equals(slot) ? 150 : "popup".equals(slot) ? 260 : "sticky".equals(slot) ? 60 : "sponsor".equals(slot) ? 70 : 120;
        FrameLayout f = new FrameLayout(ctx);
        int bgc = CgCfg.CARD2;
        try { if (bg != null && bg.length() == 7) bgc = Color.parseColor(bg); } catch (Throwable ignored) { }
        int fgc = 0xFFFFFFFF;
        try { if (fg != null && fg.length() == 7) fgc = Color.parseColor(fg); } catch (Throwable ignored) { }
        f.setBackground(CgUi.bg(ctx, bgc, 14, CgCfg.STROKE));
        if (!"text".equals(type)) {
            AdViews.Round iv = new AdViews.Round(ctx, CgUi.dp(ctx, 14));
            f.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            String u = mediaUrl != null && mediaUrl.length() > 0 && "image".equals(type) ? mediaUrl : (thumbUrl != null && thumbUrl.length() > 0 ? thumbUrl : mediaUrl);
            if (u != null && u.length() > 0) AdMedia.loadInto(ctx, iv, u, 640, "video".equals(type) && (thumbUrl == null || thumbUrl.length() == 0));
        }
        LinearLayout over = CgUi.vbox(ctx);
        over.setGravity(Gravity.BOTTOM);
        over.setPadding(CgUi.dp(ctx, 12), CgUi.dp(ctx, 8), CgUi.dp(ctx, 12), CgUi.dp(ctx, 10));
        if (!"text".equals(type)) over.setBackgroundColor(0x55000000);
        if (title != null && title.length() > 0) over.addView(CgUi.text(ctx, title, 15, fgc, true));
        if (bodyText != null && bodyText.length() > 0 && hDp >= 100) { TextView b = CgUi.text(ctx, bodyText, 12.5f, fgc, false); b.setMaxLines(2); over.addView(b); }
        if (cta != null && cta.length() > 0) {
            TextView t = CgUi.text(ctx, cta, 12.5f, 0xFFFFFFFF, true);
            t.setBackground(CgUi.bg(ctx, CgCfg.ACCENT, 14, 0));
            t.setPadding(CgUi.dp(ctx, 12), CgUi.dp(ctx, 5), CgUi.dp(ctx, 12), CgUi.dp(ctx, 5));
            over.addView(t, CgUi.lp(ctx, CgUi.WRAP, CgUi.WRAP, 0, 6, 0, 0));
        }
        f.addView(over, new FrameLayout.LayoutParams(-1, -1));
        TextView tag = CgUi.text(ctx, "إعلان", 10.5f, 0xFFFFFFFF, true);
        tag.setBackground(CgUi.bg(ctx, 0x99000000, 6, 0));
        tag.setPadding(CgUi.dp(ctx, 6), CgUi.dp(ctx, 2), CgUi.dp(ctx, 6), CgUi.dp(ctx, 2));
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        tl.setMargins(CgUi.dp(ctx, 8), CgUi.dp(ctx, 8), 0, 0);
        f.addView(tag, tl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.dp(ctx, wDp), CgUi.dp(ctx, hDp));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        f.setLayoutParams(lp);
        return f;
    }

    // حالة محرر المادة
    private String cType = "image", cMediaKey = "", cMediaUrl = "", cThumbKey = "", cThumbUrl = "", cActionType = "none";
    private boolean cActive = true;
    private EditText cName, cTitle, cBody, cCta, cActionValue, cActionExtra, cBg, cFg;
    private TextView cMediaStatus, cThumbStatus;
    private LinearLayout cPreview;
    private String pickKind = "media";
    private JSONObject editing;

    private void editCreative(JSONObject ex) {
        editing = ex;
        cType = ex == null ? "image" : ex.optString("type", "image");
        cMediaKey = ex == null ? "" : ex.optString("media_key");
        cMediaUrl = ex == null ? "" : ex.optString("media_url");
        cThumbKey = ex == null ? "" : ex.optString("thumb_key");
        cThumbUrl = ex == null ? "" : ex.optString("thumb_url");
        cActive = ex == null || ex.optBoolean("active", true);
        JSONObject act = ex == null ? null : ex.optJSONObject("action");
        cActionType = act == null ? "none" : act.optString("type", "none");
        final LinearLayout box = CgUi.vbox(this);
        box.setPadding(CgUi.dp(this, 14), CgUi.dp(this, 6), CgUi.dp(this, 14), 0);
        cPreview = CgUi.vbox(this);
        box.addView(cPreview);
        com.my.newproject.AdFlow types = new com.my.newproject.AdFlow(this);
        final String[][] tt = {{"image", "صورة"}, {"video", "فيديو"}, {"text", "نص فقط"}};
        final java.util.ArrayList<TextView> tc = new java.util.ArrayList<TextView>();
        for (final String[] t : tt) {
            final TextView ch = CgUi.chip(this, t[1], t[0].equals(cType));
            tc.add(ch);
            ch.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { cType = t[0]; for (int i = 0; i < tc.size(); i++) CgUi.setChipState(tc.get(i), tt[i][0].equals(cType)); refreshPreview(); } });
            types.add(ch);
        }
        box.addView(CgUi.labeled(this, "نوع المادة", types));
        cMediaStatus = CgUi.muted(this, cMediaKey.length() > 0 ? "✓ تم رفع الملف" : "لم يُرفع ملف");
        TextView up = CgUi.smallButton(this, "رفع صورة/فيديو", 0);
        up.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pick("media"); } });
        LinearLayout r1 = CgUi.hbox(this);
        r1.addView(up, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 8, 0));
        r1.addView(cMediaStatus);
        box.addView(r1, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        cThumbStatus = CgUi.muted(this, cThumbKey.length() > 0 ? "✓ صورة مصغّرة" : "اختيارية للفيديو");
        TextView ut = CgUi.smallButton(this, "رفع صورة مصغّرة", 1);
        ut.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pick("thumb"); } });
        LinearLayout r2 = CgUi.hbox(this);
        r2.addView(ut, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 8, 0));
        r2.addView(cThumbStatus);
        box.addView(r2, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        TextWatcher tw = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { refreshPreview(); }
            @Override public void afterTextChanged(Editable s) { }
        };
        cName = field(box, "اسم الإعلان (يظهر لك فقط ليسهل تمييزه)", ex == null ? "" : ex.optString("name"), false, false);
        cTitle = field(box, "العنوان (حتى 80 حرفاً)", ex == null ? "" : ex.optString("title"), false, false);
        cBody = field(box, "النص (حتى 300 حرف)", ex == null ? "" : ex.optString("body"), true, false);
        cCta = field(box, "نص الزر (حتى 24 حرفاً)", ex == null ? "" : ex.optString("cta"), false, false);
        cBg = field(box, "لون الخلفية #RRGGBB (اختياري)", ex == null ? "" : ex.optString("bg_color"), false, false);
        cFg = field(box, "لون النص #RRGGBB (اختياري)", ex == null ? "" : ex.optString("text_color"), false, false);
        cTitle.addTextChangedListener(tw); cBody.addTextChangedListener(tw); cCta.addTextChangedListener(tw); cBg.addTextChangedListener(tw); cFg.addTextChangedListener(tw);
        final String[] ak = {"none", "link", "whatsapp", "call", "store", "copy"};
        final String[] al = {"بدون إجراء", "فتح رابط (https)", "واتساب", "اتصال", "رابط المتجر", "نسخ كوبون"};
        final TextView ab = CgUi.button(this, al[java.util.Arrays.asList(ak).indexOf(cActionType) < 0 ? 0 : java.util.Arrays.asList(ak).indexOf(cActionType)], 1);
        ab.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { CgUi.choose(AdCampaignActivity.this, "إجراء الزر", al, new CgUi.IntCb() { @Override public void run(int i) { cActionType = ak[i]; ab.setText(al[i]); } }); } });
        box.addView(CgUi.labeled(this, "عند الضغط", ab));
        cActionValue = field(box, "القيمة: الرابط / الرقم / الكوبون", act == null ? "" : act.optString("value"), false, false);
        cActionExtra = field(box, "رسالة واتساب الجاهزة (اختياري)", act == null ? "" : act.optString("extra"), false, false);
        final CgUi.Toggle ta = CgUi.toggle(this, "فعّالة", cActive);
        ta.onChange(new Runnable() { @Override public void run() { cActive = ta.on; } });
        box.addView(ta);
        refreshPreview();
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        CgUi.dialog(this).setTitle(ex == null ? "مادة جديدة" : "تعديل المادة").setView(sv).setNegativeButton("إغلاق", null)
                .setPositiveButton("حفظ", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) { saveCreative(); }
                }).show();
    }

    private void refreshPreview() {
        if (cPreview == null) return;
        cPreview.removeAllViews();
        cPreview.addView(buildPreview(this, slot, cType, cMediaUrl, cThumbUrl, cTitle == null ? "" : cTitle.getText().toString(), cBody == null ? "" : cBody.getText().toString(), cCta == null ? "" : cCta.getText().toString(), cBg == null ? "" : cBg.getText().toString().trim(), cFg == null ? "" : cFg.getText().toString().trim()));
    }

    private void pick(String kind) {
        pickKind = kind;
        try {
            Intent in = new Intent(Intent.ACTION_GET_CONTENT);
            in.addCategory(Intent.CATEGORY_OPENABLE);
            if ("thumb".equals(kind) || "image".equals(cType)) { in.setType("image/*"); }
            else { in.setType("video/*"); }
            startActivityForResult(Intent.createChooser(in, "اختر الملف"), REQ_MEDIA);
        } catch (Throwable t) { CgUi.toast(this, "لا يوجد تطبيق لاختيار الملف."); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_MEDIA || res != RESULT_OK || data == null || data.getData() == null || !alive()) return;
        final boolean thumb = "thumb".equals(pickKind);
        final String hint = thumb || "image".equals(cType) ? "image" : "video";
        showLoading("جارٍ تجهيز الملف...");
        AdMedia.prepare(this, data.getData(), hint, new AdMedia.Prepare() {
            @Override public void onReady(final AdMedia.Prepared p, String error) {
                if (!alive()) return;
                if (p == null) { hideLoading(); CgUi.toast(AdCampaignActivity.this, error == null ? "تعذر تجهيز الملف." : error); return; }
                if (p.size > ("video".equals(hint) ? 40L : 6L) * 1024 * 1024) { hideLoading(); CgUi.toast(AdCampaignActivity.this, "الملف أكبر من الحد المسموح (" + ("video".equals(hint) ? "40" : "6") + "MB). الإعلان يُعرض للجمهور، فاستخدم نسخة مضغوطة."); return; }
                loadingMessage("جارٍ الرفع 0%");
                AdMedia.uploadCreative(p, thumb ? "thumb" : "creative", new AdMedia.Progress() { @Override public void onProgress(int pct) { loadingMessage("جارٍ الرفع " + pct + "%"); } }, new AdMedia.CreativeDone() {
                    @Override public void onDone(boolean ok, String key, String url, String mime, String message) {
                        hideLoading();
                        if (!alive()) return;
                        if (!ok) { CgUi.toast(AdCampaignActivity.this, message); return; }
                        if (thumb) { cThumbKey = key; cThumbUrl = url; CgUi.set(cThumbStatus, "✓ تم رفع الصورة المصغّرة"); }
                        else { cMediaKey = key; cMediaUrl = url; CgUi.set(cMediaStatus, "✓ تم رفع الملف"); if (mime != null && mime.startsWith("video/")) cType = "video"; else if (mime != null && mime.startsWith("image/") && "video".equals(cType) == false) cType = "image"; }
                        refreshPreview();
                    }
                });
            }
        });
    }

    private void saveCreative() {
        try {
            JSONObject b = new JSONObject();
            if (editing != null) b.put("id", editing.optString("id"));
            b.put("campaign_id", id);
            String adName = cName == null ? "" : cName.getText().toString().trim();
            if (adName.length() == 0) { CgUi.toast(this, "أدخل اسماً للإعلان ليسهل تمييزه عند التعديل أو الحذف."); return; }
            b.put("name", adName);
            b.put("type", cType);
            b.put("media_key", "text".equals(cType) ? "" : cMediaKey);
            b.put("thumb_key", cThumbKey);
            b.put("title", cTitle.getText().toString().trim());
            b.put("body", cBody.getText().toString().trim());
            b.put("cta", cCta.getText().toString().trim());
            b.put("bg_color", cBg.getText().toString().trim());
            b.put("text_color", cFg.getText().toString().trim());
            b.put("active", cActive);
            b.put("action", new JSONObject().put("type", cActionType).put("value", cActionValue.getText().toString().trim()).put("extra", cActionExtra.getText().toString().trim()));
            showLoading("جارٍ الحفظ...");
            AdApi.post(this, "/admin/creatives/save", b, new AdApi.Cb() {
                @Override public void on(AdApi.Result r) {
                    hideLoading();
                    if (!alive()) return;
                    if (r.ok) { CgUi.toast(AdCampaignActivity.this, "تم حفظ المادة."); reload(); }
                    else CgUi.info(AdCampaignActivity.this, "تعذر الحفظ", r.message);
                }
            });
        } catch (Throwable t) { hideLoading(); }
    }
}
