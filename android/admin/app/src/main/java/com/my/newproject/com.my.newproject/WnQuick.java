package com.my.newproject;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 19.6 — «اضغط على البوستر ← أضفه جاهزاً للتطبيق».
 *
 * A big poster sheet for one synced title: the phone's own VidBolt check (see {@link WnVerify}), the section it goes to
 * (remembered per type after the first choice) and ONE button. «أضف الآن» runs the whole import pipeline (video →
 * subtitle → server validation → import) with no acknowledgement and no approval step: the phone already proved that the
 * link plays. A title whose link does not play cannot be added from here (the reason is shown instead).
 */
final class WnQuick {

    private static final String PREFS = "wn_quick";

    private WnQuick() { }

    static void open(final CgBase a, final JSONObject card) {
        if (a == null || card == null || !a.alive()) return;
        final String key = card.optString("key");
        final boolean series = "series".equals(card.optString("type"));

        ScrollView sv = new ScrollView(a);
        LinearLayout box = CgUi.vbox(a);
        int p = CgUi.dp(a, 16);
        box.setPadding(p, p, p, p);
        sv.addView(box);

        // ---- the big poster
        FrameLayout posterBox = new FrameLayout(a);
        posterBox.setBackground(CgUi.bg(a, CgCfg.CARD2, 18, 0));
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (android.os.Build.VERSION.SDK_INT >= 21) iv.setClipToOutline(true);
        iv.setBackground(CgUi.bg(a, CgCfg.CARD2, 18, 0));
        posterBox.addView(iv, new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.MATCH));
        int w = Math.min(CgUi.dp(a, 230), (int) (a.getResources().getDisplayMetrics().widthPixels * 0.62f));
        CgImg.load(iv, card.optString("poster"), w);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(w, (int) (w * 1.5f));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(posterBox, plp);

        TextView title = CgUi.text(a, card.optString("title"), 19, CgCfg.TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, CgUi.dp(a, 12), 0, 0);
        box.addView(title, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        StringBuilder meta = new StringBuilder(WnLogic.typeLabel(card.optString("type")));
        if (card.optString("year").length() > 0) meta.append(" · ").append(card.optString("year"));
        String genres = WnUi.joinFirst(card.optJSONArray("genres"), 3);
        if (genres.length() > 0) meta.append(" · ").append(genres.replace("، ", " | ").replace(", ", " | "));
        JSONObject le = card.optJSONObject("latest_episode");
        if (series && le != null && le.optInt("episode") > 0) meta.append(" · آخر حلقة ").append(WnLogic.epCode(le.optInt("season"), le.optInt("episode")));
        TextView m = CgUi.muted(a, meta.toString());
        m.setGravity(Gravity.CENTER);
        box.addView(m, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        // ---- the phone's own check
        final TextView status = CgUi.text(a, "", 14, CgCfg.GRAY, true);
        status.setGravity(Gravity.CENTER);
        status.setPadding(CgUi.dp(a, 12), CgUi.dp(a, 10), CgUi.dp(a, 12), CgUi.dp(a, 10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP);
        slp.topMargin = CgUi.dp(a, 12);
        box.addView(status, slp);
        final TextView link = CgUi.text(a, WnVerify.linkFor(card), 11, CgCfg.FAINT, false);
        CgUi.ltr(link);
        link.setGravity(Gravity.CENTER);
        box.addView(link, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        // ---- where it goes
        final TextView section = CgUi.smallButton(a, sectionLabel(a, series), 1);
        LinearLayout.LayoutParams sec = new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP);
        sec.topMargin = CgUi.dp(a, 12);
        box.addView(section, sec);
        section.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickSection(a, series, new Runnable() { @Override public void run() { section.setText(sectionLabel(a, series)); } });
            }
        });

        final TextView add = CgUi.button(a, "أضف الآن إلى سيكرام", 0);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.dp(a, 52));
        alp.topMargin = CgUi.dp(a, 10);
        box.addView(add, alp);
        LinearLayout row = CgUi.hbox(a);
        final TextView again = CgUi.smallButton(a, "إعادة الفحص", 1);
        TextView details = CgUi.smallButton(a, "تفاصيل كاملة", 1);
        row.addView(again, CgUi.lpWeight(a, 1f, 0, 0, 4, 0));
        row.addView(details, CgUi.lpWeight(a, 1f, 4, 0, 0, 0));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP);
        rlp.topMargin = CgUi.dp(a, 8);
        box.addView(row, rlp);

        final AlertDialog dlg = CgUi.dialog(a).setView(sv).create();

        final Runnable refresh = new Runnable() {
            @Override public void run() {
                if (card.optBoolean("imported", false)) {
                    status.setText("موجود في التطبيق مسبقاً ✓");
                    status.setTextColor(CgCfg.GOOD);
                    status.setBackground(CgUi.bg(a, CgUi.alpha(CgCfg.GOOD, 0x18), 14, CgCfg.GOOD));
                    add.setEnabled(false);
                    add.setAlpha(0.45f);
                    return;
                }
                String st = WnVerify.state(a, key);
                int color = WnVerify.color(a, key);
                status.setText(WnVerify.label(a, key));
                status.setTextColor(color);
                status.setBackground(CgUi.bg(a, CgUi.alpha(color, 0x18), 14, color));
                boolean ok = WnVerify.STATE_OK.equals(st);
                add.setEnabled(ok);
                add.setAlpha(ok ? 1f : 0.45f);
                add.setText(ok ? "أضف الآن إلى سيكرام" : WnVerify.STATE_RUNNING.equals(st) ? "انتظر انتهاء الفحص…" : "لا يمكن الإضافة: الفيديو لا يعمل");
            }
        };
        final WnVerify.Listener listener = new WnVerify.Listener() {
            @Override public void changed(String k) { if (key.equals(k) && dlg.isShowing()) refresh.run(); }
        };
        WnVerify.listen(listener);
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) { WnVerify.unlisten(listener); }
        });
        again.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { WnVerify.recheck(a, card); refresh.run(); }
        });
        details.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dlg.dismiss(); WnDetailActivity.open(a, card); }
        });
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!WnVerify.STATE_OK.equals(WnVerify.state(a, key))) return;
                if (sectionId(a, series).length() == 0) {
                    pickSection(a, series, new Runnable() {
                        @Override public void run() { section.setText(sectionLabel(a, series)); dlg.dismiss(); importNow(a, card); }
                    });
                    return;
                }
                dlg.dismiss();
                importNow(a, card);
            }
        });
        refresh.run();
        dlg.show();
        // no verdict yet: check it now, first in line
        if (WnVerify.STATE_NONE.equals(WnVerify.state(a, key)) && !card.optBoolean("imported", false)) {
            List<JSONObject> one = new ArrayList<>();
            one.add(card);
            WnVerify.enqueue(a, one, true);
            refresh.run();
        }
    }

    // ------------------------------------------------------------------ the import

    static void importNow(final CgBase a, final JSONObject card) {
        final boolean series = "series".equals(card.optString("type"));
        final WnPipeline.Options o = new WnPipeline.Options();
        o.subtitles = true;
        o.lang = "auto";
        o.flags.ackUnverified = true;        // proven on this phone (WnVerify): the server cannot prove an embed, the phone did
        o.flags.notify = true;               // the app's own gate checks poster + details + video again before alerting viewers
        o.latestSeasonOnly = false;
        o.placement.sectionId = sectionId(a, series);
        a.showLoading("جارٍ إضافة «" + card.optString("title") + "»…");
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                final WnPipeline.Result r = WnPipeline.run(card, o, new WnPipeline.Listener() {
                    @Override public void step(String key, final String text) {
                        a.runOnUiThread(new Runnable() { @Override public void run() { if (a.alive()) a.loadingMessage(text); } });
                    }
                    @Override public void progress(String key, int done, int total) { }
                    @Override public boolean cancelled() { return !a.alive(); }
                });
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!a.alive()) return;
                        a.hideLoading();
                        if (r.ok()) {
                            try { card.put("imported", true); } catch (Throwable ignored) { }
                            WnUi.dirty = true;
                            String extra = r.notes.isEmpty() ? "" : "\n" + r.notes.get(0);
                            CgUi.info(a, "تمت الإضافة ✓", "«" + card.optString("title") + "» صار في التطبيق"
                                    + (series ? " (" + r.created + " حلقة)" : "") + ". يظهر للمستخدمين خلال ثوانٍ." + extra);
                        } else {
                            CgUi.info(a, "لم تتم الإضافة", WnLogic.stateLabel(r.state) + (r.message.length() > 0 ? "\n" + r.message : ""));
                        }
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ section memory

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String sectionId(Context c, boolean series) {
        return prefs(c).getString(series ? "series_id" : "movie_id", "");
    }

    static String sectionLabel(Context c, boolean series) {
        String name = prefs(c).getString(series ? "series_name" : "movie_name", "");
        return name.length() == 0 ? "اختر القسم الذي يُضاف إليه" : "القسم: " + name + "  (تغيير)";
    }

    static void pickSection(final CgBase a, final boolean series, final Runnable then) {
        a.showLoading("جارٍ تحميل الأقسام…");
        CgHttp.async(a, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception { return CgHttp.require(CgHttp.apiGet("/admin/sections")); }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res r) {
                a.hideLoading();
                JSONArray arr = r.json.optJSONArray("sections");
                final List<JSONObject> list = new ArrayList<>();
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject s = arr.optJSONObject(i);
                    if (s == null || !s.optBoolean("enabled", true)) continue;
                    String t = s.optString("type");
                    if (series && "movie".equals(t)) continue;
                    if (!series && "series".equals(t)) continue;
                    list.add(s);
                }
                if (list.isEmpty()) { CgUi.info(a, "لا توجد أقسام", "أنشئ قسماً من «إدارة الأقسام» أولاً."); return; }
                CharSequence[] labels = new CharSequence[list.size()];
                for (int i = 0; i < list.size(); i++) labels[i] = list.get(i).optString("name");
                CgUi.choose(a, series ? "قسم المسلسلات" : "قسم الأفلام", labels, new CgUi.IntCb() {
                    @Override public void run(int i) {
                        JSONObject s = list.get(i);
                        prefs(a).edit().putString(series ? "series_id" : "movie_id", s.optString("id"))
                                .putString(series ? "series_name" : "movie_name", s.optString("name")).apply();
                        if (then != null) then.run();
                    }
                });
            }
            @Override public void fail(String message) {
                a.hideLoading();
                CgUi.info(a, "تعذر تحميل الأقسام", message);
            }
        });
    }
}
