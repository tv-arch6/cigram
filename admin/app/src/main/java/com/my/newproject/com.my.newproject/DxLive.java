package com.my.newproject;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * The live board of the home screen (v3.1). Everything here is driven by {@link NxLive}, which asks the Worker every second:
 *
 *   main : three big numbers that tick every second (active now / watching now / registered accounts), a status line
 *          that says when the last answer came, and a chart that slides one point per second.
 *   more : what is being watched right now (movies / series / channels + the top titles), the registered accounts
 *          (new today, 24 h, 7 days, signed in, 14-day growth), today's users / sessions / plays / ads, and the last hour
 *          minute by minute.
 *
 * When the Worker cannot answer, the board SAYS so (banner + status) instead of showing silent dashes.
 */
final class DxLive {

    private static final int MATCH = CgUi.MATCH;
    private static final int WRAP = CgUi.WRAP;

    private final Activity a;
    /** Live numbers + per-second chart. */
    final LinearLayout main;
    /** Details underneath: watching, accounts, today, last hour. */
    final LinearLayout more;

    // ---- main
    private final Stat sActive;
    private final Stat sWatching;
    private final Stat sAccounts;
    private final NxChart.Live chart;
    private final TextView meta;
    private final TextView banner;

    // ---- more: watching
    private final View kMovies;
    private final View kSeries;
    private final View kChannels;
    private final NxChart.Stack stack;
    private final TextView watchSub;
    private final LinearLayout watchBody;
    private String watchSig = "";

    // ---- more: accounts
    private final LinearLayout accBody;
    private final View sparkCard;
    private final NxChart.Spark spark;
    private String accSig = "";

    // ---- more: today + history
    private final View kUsers;
    private final View kSessions;
    private final View kPlays;
    private final View kAdShows;
    private final View kAdReq;
    private final View kFill;
    private final DxUi.LineChart hist;
    private final View histCard;
    private String histSig = "";

    // =============================================================== one big ticking number

    private final class Stat {
        final LinearLayout view;
        final TextView value;
        final TextView delta;
        double shown = -1;

        Stat(String label, int color) {
            view = CgUi.vbox(a);
            value = CgUi.text(a, "—", 27, color, true);
            value.setSingleLine(true);
            TextView l = CgUi.muted(a, label);
            l.setSingleLine(true);
            delta = CgUi.text(a, " ", 11.5f, CgCfg.MUTED, true);
            delta.setSingleLine(true);
            view.addView(value, new LinearLayout.LayoutParams(MATCH, WRAP));
            view.addView(l, new LinearLayout.LayoutParams(MATCH, WRAP));
            view.addView(delta, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        void set(double v) {
            if (v < 0) {
                value.setText("—");
                shown = -1;
                return;
            }
            if (v == shown) return;
            NxUi.animateNumber(value, shown, v);
            if (shown >= 0) NxUi.bump(value);
            shown = v;
        }

        /** Change against a minute ago. */
        void change(double now, double before) {
            if (now < 0 || before < 0) {
                delta.setText(" ");
                return;
            }
            long d = Math.round(now - before);
            if (d > 0) {
                delta.setText("▲ " + d + " خلال دقيقة");
                delta.setTextColor(NxUi.UP);
            } else if (d < 0) {
                delta.setText("▼ " + (-d) + " خلال دقيقة");
                delta.setTextColor(NxUi.DOWN);
            } else {
                delta.setText("ثابت");
                delta.setTextColor(CgCfg.MUTED);
            }
        }

        void note(String s, int color) {
            delta.setText(s);
            delta.setTextColor(color);
        }
    }

    // =================================================================== build

    DxLive(Activity activity) {
        a = activity;

        // ------------------------------------------------------------ main
        main = CgUi.vbox(a);
        CgUi.unclip(main);
        LinearLayout card = DxUi.card(a);

        LinearLayout head = CgUi.hbox(a);
        View dot = new View(a);
        dot.setBackground(CgUi.bg(a, NxUi.LIVE, 5, 0));
        head.addView(dot, new LinearLayout.LayoutParams(CgUi.dp(a, 10), CgUi.dp(a, 10)));
        NxUi.pulse(dot);
        TextView title = CgUi.text(a, "مباشر الآن", 15.5f, CgCfg.TEXT, true);
        title.setPadding(CgUi.dp(a, 8), 0, 0, 0);
        head.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1f));
        meta = CgUi.muted(a, "جارٍ الاتصال…");
        meta.setTextSize(11.5f);
        head.addView(meta, new LinearLayout.LayoutParams(WRAP, WRAP));
        card.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        sActive = new Stat("نشطون الآن", NxUi.BLUE);
        sWatching = new Stat("يشاهدون الآن", NxUi.VIOLET);
        sAccounts = new Stat("المسجّلون", NxUi.TEAL);
        LinearLayout nums = CgUi.hbox(a);
        nums.setGravity(Gravity.TOP);
        Stat[] all = {sActive, sWatching, sAccounts};
        for (int i = 0; i < all.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, WRAP, 1f);
            p.setMargins(i == 0 ? 0 : CgUi.dp(a, 4), 0, i == all.length - 1 ? 0 : CgUi.dp(a, 4), 0);
            nums.addView(all[i].view, p);
        }
        card.addView(nums, CgUi.lp(a, MATCH, WRAP, 0, 14, 0, 0));

        banner = CgUi.text(a, "", 12.5f, CgCfg.WARN, false);
        banner.setBackground(CgUi.bg(a, CgUi.alpha(CgCfg.WARN, 0x1A), 12, 0));
        int bp = CgUi.dp(a, 10);
        banner.setPadding(bp, bp, bp, bp);
        banner.setVisibility(View.GONE);
        card.addView(banner, CgUi.lp(a, MATCH, WRAP, 0, 12, 0, 0));

        chart = new NxChart.Live(a, new int[]{NxUi.BLUE, NxUi.VIOLET});
        card.addView(chart, CgUi.lp(a, MATCH, CgUi.dp(a, 150), 0, 12, 0, 0));

        LinearLayout legend = CgUi.hbox(a);
        legend.addView(toggleChip("نشطون", 0), chipLp());
        legend.addView(toggleChip("يشاهدون", 1), chipLp());
        card.addView(legend, CgUi.lp(a, MATCH, WRAP, 0, 8, 0, 0));

        card.setLayoutParams(CgUi.lp(a, MATCH, WRAP, 0, 0, 0, 4));
        main.addView(card);

        // ------------------------------------------------------------ more
        more = CgUi.vbox(a);
        CgUi.unclip(more);
        more.addView(DxUi.heading(a, "تفاصيل مباشرة"));

        // watching: three numbers + stack + top titles
        kMovies = DxUi.kpi(a, "يشاهدون أفلاماً", "—", NxUi.BLUE);
        kSeries = DxUi.kpi(a, "يشاهدون مسلسلات", "—", NxUi.VIOLET);
        kChannels = DxUi.kpi(a, "يشاهدون قنوات", "—", NxUi.TEAL);
        more.addView(DxUi.row(a, kMovies, kSeries, kChannels));

        LinearLayout wc = DxUi.card(a);
        wc.addView(CgUi.text(a, "ماذا يُشاهَد الآن", 15, CgCfg.TEXT, true));
        watchSub = CgUi.muted(a, "");
        watchSub.setVisibility(View.GONE);
        wc.addView(watchSub);
        stack = new NxChart.Stack(a);
        wc.addView(stack, CgUi.lp(a, MATCH, CgUi.dp(a, 12), 0, 12, 0, 6));
        watchBody = CgUi.vbox(a);
        wc.addView(watchBody, new LinearLayout.LayoutParams(MATCH, WRAP));
        wc.setLayoutParams(CgUi.lp(a, MATCH, WRAP, 0, 0, 0, 10));
        more.addView(wc);

        // accounts
        LinearLayout ac = DxUi.card(a);
        ac.addView(CgUi.text(a, "الحسابات المسجّلة", 15, CgCfg.TEXT, true));
        accBody = CgUi.vbox(a);
        ac.addView(accBody, CgUi.lp(a, MATCH, WRAP, 0, 10, 0, 0));
        ac.setLayoutParams(CgUi.lp(a, MATCH, WRAP, 0, 0, 0, 10));
        more.addView(ac);

        spark = new NxChart.Spark(a, NxUi.TEAL);
        sparkCard = DxUi.chartCard(a, "نمو الحسابات", "إجمالي المسجّلين آخر 14 يوماً", spark, 70);
        sparkCard.setVisibility(View.GONE);
        more.addView(sparkCard);

        // today
        more.addView(DxUi.heading(a, "اليوم"));
        kUsers = DxUi.kpi(a, "مستخدمو اليوم", "—", NxUi.TEAL);
        kSessions = DxUi.kpi(a, "جلسات", "—", CgCfg.TEXT);
        kPlays = DxUi.kpi(a, "تشغيلات", "—", NxUi.BLUE);
        more.addView(DxUi.row(a, kUsers, kSessions, kPlays));
        kAdShows = DxUi.kpi(a, "إعلانات معروضة", "—", NxUi.ORANGE);
        kAdReq = DxUi.kpi(a, "طلبات الإعلانات", "—", CgCfg.TEXT);
        kFill = DxUi.kpi(a, "نسبة التعبئة", "—", NxUi.UP);
        more.addView(DxUi.row(a, kAdShows, kAdReq, kFill));

        // last hour, minute by minute
        hist = new DxUi.LineChart(a);
        histCard = DxUi.chartCard(a, "آخر ساعة", "نشطون ويشاهدون · نقطة لكل دقيقة", hist, 120);
        histCard.setVisibility(View.GONE);
        more.addView(histCard);
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
        p.setMarginEnd(CgUi.dp(a, 6));
        return p;
    }

    private TextView toggleChip(String label, final int series) {
        final TextView t = CgUi.chip(a, label, true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !chart.isVisible(series);
                chart.setVisible(series, on);
                CgUi.setChipState(t, on);
            }
        });
        return t;
    }

    // =================================================================== tick

    /** Called by the home once per second. */
    void update(NxLive l, boolean fresh) {
        // ---- the three big numbers
        sActive.set(l.active);
        sWatching.set(l.watching);
        sAccounts.set(l.accounts);
        sActive.change(l.active, l.ago(0, 60));
        sWatching.change(l.watching, l.ago(1, 60));
        if (l.accounts >= 0 && l.accountsNewToday >= 0) {
            sAccounts.note("▲ " + l.accountsNewToday + " اليوم", l.accountsNewToday > 0 ? NxUi.UP : CgCfg.MUTED);
        } else if (l.accounts >= 0 && l.accountsNew24 >= 0) {
            sAccounts.note("▲ " + l.accountsNew24 + " خلال 24 ساعة", l.accountsNew24 > 0 ? NxUi.UP : CgCfg.MUTED);
        } else if (l.accounts < 0 && !l.accountsLinked && l.accountsNote.length() > 0) {
            sAccounts.note("غير مرتبط", CgCfg.WARN);
        } else {
            sAccounts.note(" ", CgCfg.MUTED);
        }

        // ---- the chart slides one point every second
        chart.push(new double[]{Math.max(0, l.active), Math.max(0, l.watching)});

        // ---- status line + banner
        long now = System.currentTimeMillis();
        if (l.connected) {
            long age = l.lastOkAt > 0 ? (now - l.lastOkAt) / 1000 : 0;
            meta.setText("يتحدّث كل ثانية" + (age > 3 ? " · آخر رد منذ " + age + "ث" : ""));
            meta.setTextColor(CgCfg.MUTED);
        } else if (l.routeMissing) {
            meta.setText("بدون بث مباشر");
            meta.setTextColor(CgCfg.WARN);
        } else if (l.everConnected || l.error.length() > 0) {
            meta.setText("انقطع الاتصال");
            meta.setTextColor(CgCfg.BAD);
        } else {
            meta.setText("جارٍ الاتصال…");
            meta.setTextColor(CgCfg.MUTED);
        }
        if (l.routeMissing) {
            banner.setTextColor(CgCfg.WARN);
            banner.setText("الـ Worker لا يملك المسار /admin/live (نسخة v7). تظهر أرقام اليوم فقط، أما «نشطون ويشاهدون الآن» "
                    + "فتحتاج نشر نسخة الـ Worker الأحدث.");
            banner.setVisibility(View.VISIBLE);
        } else if (!l.connected && l.error.length() > 0) {
            banner.setTextColor(CgCfg.BAD);
            banner.setText("تعذّر الاتصال بالخادم: " + l.error);
            banner.setVisibility(View.VISIBLE);
        } else {
            banner.setVisibility(View.GONE);
        }

        if (fresh) updateDetails(l);
    }

    private void updateDetails(NxLive l) {
        updateWatching(l);
        updateAccounts(l);

        DxUi.setKpi(kUsers, DxUi.num(l.usersToday));
        DxUi.setKpi(kSessions, DxUi.num(l.sessionsToday));
        DxUi.setKpi(kPlays, DxUi.num(l.playsToday));
        DxUi.setKpi(kAdShows, DxUi.num(l.adShows));
        DxUi.setKpi(kAdReq, DxUi.num(l.adRequests));
        DxUi.setKpi(kFill, l.adRequests > 0 && l.adShows >= 0
                ? String.format(Locale.US, "%d%%", Math.min(100, Math.round(l.adShows * 100.0 / l.adRequests))) : "—");

        int n = l.history.size();
        if (n >= 2) {
            long[] last = l.history.get(n - 1);
            String sig = n + ":" + last[0] + ":" + last[1] + ":" + last[2];
            if (!sig.equals(histSig)) {
                histSig = sig;
                hist.setData(new double[][]{l.minuteSeries(0, 60), l.minuteSeries(1, 60)}, new int[]{NxUi.BLUE, NxUi.VIOLET}, false);
            }
            histCard.setVisibility(View.VISIBLE);
        }
    }

    // ----------------------------------------------------------- watching now

    private static final class Row {
        String name;
        int kind;       // 0 movie, 1 series, 2 channel, -1 other
        long n;
    }

    private static int kindOf(String s) {
        if (s == null) return -1;
        String k = s.toLowerCase(Locale.ROOT);
        if (k.contains("channel") || k.contains("live") || k.contains("قنا") || k.contains("بث")) return 2;
        if (k.contains("series") || k.contains("episode") || k.contains("season") || k.contains("tv") || k.contains("show") || k.contains("مسلسل") || k.contains("حلق")) return 1;
        if (k.contains("movie") || k.contains("film") || k.contains("فيلم") || k.contains("أفلام")) return 0;
        return -1;
    }

    private static String firstText(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v.length() > 0 && !"null".equals(v)) return v;
        }
        return "";
    }

    private static long firstNum(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k)) {
                long v = o.optLong(k, -1);
                if (v >= 0) return v;
            }
        }
        return 1;
    }

    /** Reads watching_top however the Worker shaped it: objects (title / type / count) or arrays [name, count]. */
    private static ArrayList<Row> parse(JSONArray top) {
        ArrayList<Row> out = new ArrayList<Row>();
        if (top == null) return out;
        for (int i = 0; i < top.length(); i++) {
            Row r = new Row();
            JSONObject o = top.optJSONObject(i);
            if (o != null) {
                r.name = firstText(o, "title", "name", "label", "content_title", "series_title", "channel", "id");
                r.kind = kindOf(firstText(o, "type", "kind", "category", "media_type", "content_type"));
                r.n = firstNum(o, "count", "watching", "viewers", "n", "users", "total");
            } else {
                JSONArray arr = top.optJSONArray(i);
                if (arr == null || arr.length() == 0) continue;
                r.name = arr.optString(0, "");
                r.n = arr.length() > 1 ? arr.optLong(arr.length() - 1, 1) : 1;
                r.kind = arr.length() > 2 ? kindOf(arr.optString(1, "")) : -1;
            }
            if (r.name == null || r.name.length() == 0) continue;
            out.add(r);
        }
        return out;
    }

    private static int kindColor(int kind) {
        return kind == 0 ? NxUi.BLUE : kind == 1 ? NxUi.VIOLET : kind == 2 ? NxUi.TEAL : CgCfg.GRAY;
    }

    private static String kindName(int kind) {
        return kind == 0 ? "فيلم" : kind == 1 ? "مسلسل" : kind == 2 ? "قناة" : "";
    }

    private void updateWatching(NxLive l) {
        ArrayList<Row> rows = parse(l.watchingTop);
        long[] k = {l.watchMovies, l.watchSeries, l.watchChannels};
        boolean exact = k[0] >= 0 || k[1] >= 0 || k[2] >= 0;
        boolean derived = false;
        if (!exact && !rows.isEmpty()) {
            k = new long[]{0, 0, 0};
            for (Row r : rows) if (r.kind >= 0) k[r.kind] += r.n;
            derived = true;
        }
        DxUi.setKpi(kMovies, k[0] < 0 ? "—" : DxUi.num(k[0]));
        DxUi.setKpi(kSeries, k[1] < 0 ? "—" : DxUi.num(k[1]));
        DxUi.setKpi(kChannels, k[2] < 0 ? "—" : DxUi.num(k[2]));
        stack.set(new double[]{Math.max(0, k[0]), Math.max(0, k[1]), Math.max(0, k[2])},
                new int[]{NxUi.BLUE, NxUi.VIOLET, NxUi.TEAL});
        if (derived) {
            watchSub.setText("محسوبة من أكثر العناوين مشاهدةً الآن");
            watchSub.setVisibility(View.VISIBLE);
        } else {
            watchSub.setVisibility(View.GONE);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(l.watching).append('|');
        for (Row r : rows) sb.append(r.name).append(':').append(r.n).append(':').append(r.kind).append(';');
        String sig = sb.toString();
        if (sig.equals(watchSig)) return;
        watchSig = sig;

        watchBody.removeAllViews();
        if (rows.isEmpty()) {
            String msg = l.watching == 0 ? "لا أحد يشاهد شيئاً الآن"
                    : l.watching < 0 ? "بانتظار البيانات…"
                    : "الـ Worker لا يرسل أسماء ما يُشاهَد (watching_top)";
            watchBody.addView(CgUi.muted(a, msg));
            return;
        }
        int shown = Math.min(rows.size(), 8);
        for (int i = 0; i < shown; i++) {
            Row r = rows.get(i);
            String kn = kindName(r.kind);
            watchBody.addView(DxUi.legend(a, kindColor(r.kind), r.name + (kn.length() > 0 ? " · " + kn : ""), DxUi.num(r.n)));
        }
    }

    // --------------------------------------------------------------- accounts

    private void updateAccounts(NxLive l) {
        String sig = l.accounts + "|" + l.accountsLinked + "|" + l.accountsNote + "|" + l.accountsNewToday + "|" + l.accountsNew24
                + "|" + l.accountsNew7 + "|" + l.accountsActive + "|" + l.accountsSignedIn + "|" + l.accountsLastAt
                + "|" + l.accountsDaily.length + "|" + l.accountSamples.size();
        if (sig.equals(accSig)) return;
        accSig = sig;

        accBody.removeAllViews();
        if (l.accounts < 0) {
            String msg = l.accountsNote.length() > 0 ? l.accountsNote : "بانتظار بيانات الحسابات…";
            TextView t = CgUi.text(a, msg, 13, l.accountsNote.length() > 0 ? CgCfg.WARN : CgCfg.MUTED, false);
            accBody.addView(t);
            sparkCard.setVisibility(View.GONE);
            return;
        }

        ArrayList<View> ks = new ArrayList<View>();
        ks.add(DxUi.kpi(a, "إجمالي المسجّلين", DxUi.num(l.accounts), NxUi.TEAL));
        if (l.accountsNewToday >= 0) ks.add(DxUi.kpi(a, "جدد اليوم", DxUi.num(l.accountsNewToday), NxUi.UP));
        if (l.accountsNew24 >= 0) ks.add(DxUi.kpi(a, "آخر 24 ساعة", DxUi.num(l.accountsNew24), NxUi.UP));
        if (l.accountsNew7 >= 0) ks.add(DxUi.kpi(a, "آخر 7 أيام", DxUi.num(l.accountsNew7), NxUi.UP));
        if (l.accountsActive >= 0) ks.add(DxUi.kpi(a, "نشطون", DxUi.num(l.accountsActive), NxUi.BLUE));
        if (l.accountsSignedIn >= 0) ks.add(DxUi.kpi(a, "مسجّلو الدخول", DxUi.num(l.accountsSignedIn), NxUi.VIOLET));
        for (int i = 0; i < ks.size(); i += 3) {
            View x = ks.get(i);
            View y = i + 1 < ks.size() ? ks.get(i + 1) : new View(a);
            View z = i + 2 < ks.size() ? ks.get(i + 2) : new View(a);
            accBody.addView(DxUi.row(a, x, y, z));
        }
        String ago = CgUi.ago(l.accountsLastAt);
        if (ago.length() > 0) accBody.addView(CgUi.muted(a, "آخر تسجيل: " + ago));

        double[] daily = l.accountsDaily.length >= 2 ? l.accountsDaily : NxLive.toArray(l.accountSamples);
        if (daily.length >= 2) {
            spark.set(daily);
            sparkCard.setVisibility(View.VISIBLE);
        } else {
            sparkCard.setVisibility(View.GONE);
        }
    }
}
