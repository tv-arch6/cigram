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
        TextView kicker = CgUi.text(host, "اكتشف", 12, CgCfg.CYAN, true);
        titles.addView(kicker);
        LinearLayout nameRow = CgUi.hbox(host);
        nameRow.addView(CgUi.text(host, "ما الجديد؟", 22, CgCfg.TEXT, true));
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        nl.setMarginStart(CgUi.dp(host, 8));
        nameRow.addView(WnUi.newBadge(host), nl);
        titles.addView(nameRow);
        titles.addView(CgUi.muted(host, "اكتشف المحتوى الجديد وأضفه تلقائياً"));
        head.addView(titles, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        ImageView icon = new ImageView(host);
        icon.setImageDrawable(CgIcons.icon(CgIcons.STAR, StudioUi.TILE_GLYPH));
        int ip = CgUi.dp(host, 11);
        icon.setPadding(ip, ip, ip, ip);
        icon.setBackground(StudioUi.blackCircle(host));
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
            @Override public JSONObject run() throws Exception {
                JSONObject s = WnApi.summary();
                // 19.6: the synced titles as full cards (type, TMDB id, latest episode, «in library») for the big posters
                try {
                    JSONObject feed = WnApi.feed(WnLogic.feedRequest(WnLogic.chip("recent"), null, 1, false));
                    JSONArray cards = feed.optJSONArray("cards");
                    if (cards != null && cards.length() > 0) s.put("latest_cards", cards);
                } catch (Throwable ignored) { }
                return s;
            }
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

    /** Minimal cards from the summary's "latest" list (key "movie:123") when the feed could not be read. */
    private static JSONArray cardsFromLatest(JSONArray latest) {
        JSONArray out = new JSONArray();
        for (int i = 0; latest != null && i < latest.length(); i++) {
            JSONObject it = latest.optJSONObject(i);
            if (it == null) continue;
            try {
                JSONObject c = new JSONObject(it.toString());
                String key = it.optString("key");
                int colon = key.indexOf(':');
                if (colon > 0) c.put("tmdb_id", Long.parseLong(key.substring(colon + 1)));
                if (!c.has("type") && colon > 0) c.put("type", key.substring(0, colon));
                out.put(c);
            } catch (Throwable ignored) { }
        }
        return out;
    }

    /** A 128 dp poster (2:3) with the title under it and the live verdict badge on top. */
    private static View bigPoster(final Activity a, final JSONObject card, int widthPx) {
        LinearLayout col = CgUi.vbox(a);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(a);
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(CgUi.bg(a, CgCfg.CARD2, 16, 0));
        if (android.os.Build.VERSION.SDK_INT >= 21) iv.setClipToOutline(true);
        CgUi.elevate(iv, 4);
        CgImg.load(iv, card.optString("poster"), widthPx);
        frame.addView(iv, new android.widget.FrameLayout.LayoutParams(CgUi.MATCH, CgUi.MATCH));
        final TextView badge = WnUi.badge(a, "", CgCfg.GRAY);
        android.widget.FrameLayout.LayoutParams bl = new android.widget.FrameLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP, Gravity.TOP | Gravity.START);
        bl.setMargins(CgUi.dp(a, 6), CgUi.dp(a, 6), CgUi.dp(a, 6), 0);
        frame.addView(badge, bl);
        col.addView(frame, new LinearLayout.LayoutParams(widthPx, (int) (widthPx * 1.5f)));
        TextView t = CgUi.text(a, card.optString("title"), 12.5f, CgCfg.TEXT, true);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(CgUi.dp(a, 2), CgUi.dp(a, 6), CgUi.dp(a, 2), 0);
        col.addView(t, new LinearLayout.LayoutParams(widthPx, CgUi.WRAP));
        final String key = card.optString("key");
        final Runnable paint = new Runnable() {
            @Override public void run() {
                if (card.optBoolean("imported", false)) { badge.setText("في التطبيق"); WnUi.tint(badge, CgCfg.GOOD); return; }
                String st = WnVerify.state(a, key);
                badge.setText(WnVerify.STATE_OK.equals(st) ? "✓ شغال" : WnVerify.STATE_BAD.equals(st) ? "✗ لا يعمل" : WnVerify.STATE_RUNNING.equals(st) ? "يُفحص…" : "بانتظار الفحص");
                WnUi.tint(badge, WnVerify.color(a, key));
            }
        };
        paint.run();
        final WnVerify.Listener l = new WnVerify.Listener() {
            @Override public void changed(String k) { if (key.equals(k)) paint.run(); }
        };
        WnVerify.listen(l);
        col.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { WnVerify.listen(l); paint.run(); }
            @Override public void onViewDetachedFromWindow(View v) { WnVerify.unlisten(l); }
        });
        col.setClickable(true);
        col.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (a instanceof CgBase) WnQuick.open((CgBase) a, card); }
        });
        CgUi.pressScale(col);
        return col;
    }

    /** Shared by the dashboard card and the discovery center header. */
    static void fillStats(final Activity a, LinearLayout box, JSONObject s, boolean openLink) {
        box.removeAllViews();
        JSONObject cron = s.optJSONObject("cron");
        if (cron != null && !cron.optBoolean("healthy", false)) {
            LinearLayout w = CgUi.vbox(a);
            w.setBackground(CgUi.bg(a, CgUi.alpha(CgCfg.BAD, 0x1C), 14, CgCfg.BAD));
            w.setPadding(CgUi.dp(a, 12), CgUi.dp(a, 10), CgUi.dp(a, 12), CgUi.dp(a, 10));
            w.addView(CgUi.text(a, "⚠ المؤشر الزمني (Cron) لا يعمل", 14, CgCfg.BAD, true));
            w.addView(CgUi.text(a, "بدونه تتوقف الإحصائيات وتنبيهات بوت تليكرام للطلبات والمزامنة التلقائية. افتح Cloudflare ← Workers ← cigram-admin-api ← Settings ← Triggers ← Add Cron Trigger وضع * * * * * ثم احفظ.", 12, CgCfg.TEXT, false));
            w.setLayoutParams(CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
            box.addView(w);
        }
        JSONObject c = s.optJSONObject("counts");
        if (c == null) c = new JSONObject();
        box.addView(DxUi.row(a,
                DxUi.kpi(a, "أفلام جديدة", DxUi.num(c.optInt("new_movies")), CgCfg.CH_BLUE),
                DxUi.kpi(a, "مسلسلات جديدة", DxUi.num(c.optInt("new_series")), CgCfg.CH_ORANGE),
                DxUi.kpi(a, "حلقات جديدة", DxUi.num(c.optInt("recent_episodes")), c.optInt("recent_episodes") > 0 ? CgCfg.CH_GREEN : CgCfg.MUTED)));
        int unverified = c.optInt("unverified");   // 19.6: no longer asks for an acknowledgement: the phone checks these itself
        box.addView(DxUi.row(a,
                DxUi.kpi(a, "متاح للاستيراد", DxUi.num(s.optInt("available")), CgCfg.GOOD),
                DxUi.kpi(a, "جاهز ومُتحقَّق", DxUi.num(c.optInt("ready")), CgCfg.GOOD),
                DxUi.kpi(a, "بلا فيديو", DxUi.num(c.optInt("missing_video")), c.optInt("missing_video") > 0 ? CgCfg.BAD : CgCfg.MUTED)));
        if (unverified > 0) {
            TextView u = CgUi.text(a, unverified + " عنوان على VidBolt يُفحص تلقائياً على هذا الهاتف — اضغط أي بوستر «✓ شغال» لإضافته فوراً.", 12, CgCfg.CYAN, false);
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
        // 19.6: the last sync as BIG posters, each with the phone's own VidBolt verdict; a tap opens «أضف الآن»
        JSONArray latest = s.optJSONArray("latest_cards");
        if (latest == null || latest.length() == 0) latest = cardsFromLatest(s.optJSONArray("latest"));
        if (latest != null && latest.length() > 0) {
            HorizontalScrollView hs = new HorizontalScrollView(a);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout strip = CgUi.hbox(a);
            java.util.List<JSONObject> toVerify = new java.util.ArrayList<>();
            int pw = CgUi.dp(a, 128);
            for (int i = 0; i < latest.length() && i < 14; i++) {
                final JSONObject it = latest.optJSONObject(i);
                if (it == null) continue;
                toVerify.add(it);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(pw, CgUi.WRAP);
                lp.setMarginEnd(CgUi.dp(a, 10));
                strip.addView(bigPoster(a, it, pw), lp);
            }
            hs.addView(strip);
            LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP);
            hl.topMargin = CgUi.dp(a, 10);
            box.addView(hs, hl);
            WnVerify.enqueue(a, toVerify, false);   // the sync's titles are checked by themselves, newest first
        }
        if (openLink) {
            TextView open = CgUi.text(a, "فتح «ما الجديد؟» ›", 13.5f, CgCfg.CYAN, true);
            open.setGravity(Gravity.END);
            open.setPadding(0, CgUi.dp(a, 10), 0, 0);
            box.addView(open);
        }
    }
}
