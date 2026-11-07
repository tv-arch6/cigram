package com.my.newproject;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The prominent «ما الجديد؟» card of the admin dashboard. It only ever reads the Worker's CACHED discovery summary
 * (no TMDB / provider call), so opening or refreshing the dashboard costs nothing; the numbers move when a sync runs.
 */
final class WnHomeCard {

    final LinearLayout view;
    private final CgBase host;
    private final LinearLayout body;
    private boolean loading;

    WnHomeCard(CgBase host) {
        this.host = host;
        view = CgUi.vbox(host);
        view.setBackground(CgUi.gradient(host, CgUi.alpha(CgCfg.ACCENT_2, 0x66), CgUi.alpha(CgCfg.CYAN, 0x1A), 24));
        CgUi.elevate(view, 6);
        int p = CgUi.dp(host, 16);
        view.setPadding(p, p, p, p);

        LinearLayout head = CgUi.hbox(host);
        LinearLayout titles = CgUi.vbox(host);
        TextView kicker = CgUi.text(host, "DISCOVER", 11, CgCfg.CYAN, true);
        CgUi.ltr(kicker);
        kicker.setLetterSpacing(0.2f);
        titles.addView(kicker);
        titles.addView(CgUi.text(host, "ما الجديد؟", 22, CgCfg.TEXT, true));
        titles.addView(CgUi.muted(host, "اكتشف المحتوى الجديد وأضفه تلقائياً"));
        head.addView(titles, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        ImageView icon = new ImageView(host);
        icon.setImageDrawable(CgIcons.icon(CgIcons.STAR, CgCfg.CYAN));
        int ip = CgUi.dp(host, 11);
        icon.setPadding(ip, ip, ip, ip);
        icon.setBackground(CgUi.gradient(host, CgUi.alpha(CgCfg.CYAN, 0x44), CgUi.alpha(CgCfg.ACCENT_2, 0x22), 24));
        head.addView(icon, new LinearLayout.LayoutParams(CgUi.dp(host, 48), CgUi.dp(host, 48)));
        view.addView(head);

        body = CgUi.vbox(host);
        body.setPadding(0, CgUi.dp(host, 10), 0, 0);
        view.addView(body, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        view.setLayoutParams(CgUi.lp(host, CgUi.MATCH, CgUi.WRAP, 2, 2, 2, 14));
        view.setClickable(true);
        view.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.startActivity(new Intent(host, WnActivity.class)); }
        });
        CgUi.pressScale(view);
        body.addView(CgUi.muted(host, "جارٍ قراءة الملخص…"));
    }

    /** Reads the cached summary (never calls TMDB). Safe to call on every resume. */
    void refresh() {
        if (loading) return;
        loading = true;
        CgHttp.async(host, new CgHttp.Work<JSONObject>() {
            @Override public JSONObject run() throws Exception { return WnApi.summary(); }
        }, new CgHttp.Done<JSONObject>() {
            @Override public void ok(JSONObject s) {
                loading = false;
                if (host.isFinishing()) return;
                fillStats(host, body, s, true);
            }

            @Override public void fail(String message) {
                loading = false;
                if (host.isFinishing()) return;
                body.removeAllViews();
                TextView t = CgUi.text(host, "تعذر قراءة ملخص «ما الجديد؟»: " + message, 12.5f, CgCfg.WARN, false);
                body.addView(t);
            }
        });
    }

    /** Shared by the dashboard card and the discovery center header. */
    static void fillStats(final Activity a, LinearLayout box, JSONObject s, boolean openLink) {
        box.removeAllViews();
        JSONObject c = s.optJSONObject("counts");
        if (c == null) c = new JSONObject();
        box.addView(DxUi.row(a,
                DxUi.kpi(a, "أفلام جديدة", DxUi.num(c.optInt("new_movies")), CgCfg.TEXT),
                DxUi.kpi(a, "مسلسلات جديدة", DxUi.num(c.optInt("new_series")), CgCfg.TEXT),
                DxUi.kpi(a, "حلقات جديدة", DxUi.num(c.optInt("recent_episodes")), c.optInt("recent_episodes") > 0 ? CgCfg.CYAN : CgCfg.MUTED)));
        int unverified = c.optInt("unverified");
        box.addView(DxUi.row(a,
                DxUi.kpi(a, "متاح للاستيراد", DxUi.num(s.optInt("available")), CgCfg.GOOD),
                DxUi.kpi(a, "جاهز ومُتحقَّق", DxUi.num(c.optInt("ready")), CgCfg.GOOD),
                DxUi.kpi(a, "بلا فيديو", DxUi.num(c.optInt("missing_video")), c.optInt("missing_video") > 0 ? CgCfg.BAD : CgCfg.MUTED)));
        if (unverified > 0) {
            TextView u = CgUi.text(a, unverified + " عنوان فيديوه غير قابل للتحقق الآلي (يحتاج إقرارك عند الاستيراد).", 12, CgCfg.WARN, false);
            u.setPadding(CgUi.dp(a, 4), CgUi.dp(a, 2), 0, CgUi.dp(a, 4));
            box.addView(u);
        }
        String last = s.optString("last_success_at");
        TextView t;
        if (last.length() > 0) {
            t = CgUi.text(a, "آخر مزامنة ناجحة: " + CgUi.ago(last) + (s.optBoolean("auto_sync") ? " · تلقائية كل " + s.optInt("interval_minutes") + " د" : " · يدوية"), 12, CgCfg.MUTED, false);
        } else {
            t = CgUi.text(a, "لم تتم أي مزامنة بعد — افتح «ما الجديد؟» واضغط «مزامنة الآن» لملء الأرقام.", 12, CgCfg.WARN, false);
        }
        t.setPadding(CgUi.dp(a, 4), CgUi.dp(a, 4), 0, 0);
        box.addView(t);
        JSONArray latest = s.optJSONArray("latest");
        if (latest != null && latest.length() > 0) {
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout strip = CgUi.hbox(a);
            for (int i = 0; i < latest.length() && i < 8; i++) {
                JSONObject it = latest.optJSONObject(i);
                if (it == null) continue;
                ImageView iv = new ImageView(a);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(CgUi.bg(a, CgCfg.CARD2, 10, 0));
                CgUi.elevate(iv, 2);
                CgImg.load(iv, it.optString("poster"), CgUi.dp(a, 70));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.dp(a, 52), CgUi.dp(a, 76));
                lp.setMarginEnd(CgUi.dp(a, 8));
                strip.addView(iv, lp);
            }
            hs.addView(strip);
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP);
            hl.topMargin = CgUi.dp(a, 8);
            box.addView(hs, hl);
        }
        if (openLink) {
            TextView open = CgUi.text(a, "فتح «ما الجديد؟» ›", 13.5f, CgCfg.CYAN, true);
            open.setGravity(Gravity.END);
            open.setPadding(0, CgUi.dp(a, 10), 0, 0);
            box.addView(open);
        }
    }
}
