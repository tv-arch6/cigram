package com.Cigram.vid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * Details page additions: data-driven badges + "الإبلاغ عن مشكلة".
 *
 * Reports are small JSON documents (no personal data besides the account id, when signed in).
 * Spam protection on the device: 30 s between reports, one report per (content, issue) per
 * 10 min, 6 per hour. The Worker repeats the checks. Without a connection the report is kept
 * in a local queue (max 20) and sent on the next app start / next successful send.
 */
public final class CigramReport {

    private static final String PREFS = "cigram_report_v1";
    private static final String TAG_ROW = "cigram_v23_report_row";
    private static final String TAG_BADGES = "cigram_v23_details_badges";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long MIN_GAP_MS = 30000L;
    private static final long SAME_ISSUE_MS = 10L * 60L * 1000L;
    private static final long HOUR_MS = 3600000L;
    private static final int MAX_PER_HOUR = 6;
    private static final int MAX_QUEUE = 20;
    private static final int MAX_MESSAGE = 200;

    private static final String[][] ISSUES = new String[][]{
            {"video_broken", "الفيديو لا يعمل"},
            {"subtitle_broken", "الترجمة لا تعمل"},
            {"subtitle_sync", "الترجمة غير متزامنة"},
            {"audio_issue", "الصوت به مشكلة"},
            {"wrong_episode", "الحلقة غير صحيحة"},
            {"missing_episode", "حلقة ناقصة"},
            {"wrong_poster", "الصورة أو البوستر خطأ"},
            {"wrong_info", "المعلومات غير صحيحة"},
            {"other", "مشكلة أخرى"}
    };

    private static boolean flushing = false;

    private CigramReport() { }

    // ------------------------------------------------------------ details UI

    /** Badge chips under the meta line (admin data, up to 5). */
    public static void bindBadges(Activity activity, LinearLayout heroBottom, JSONObject item) {
        if (activity == null || heroBottom == null || item == null) return;
        try {
            View old = heroBottom.findViewWithTag(TAG_BADGES);
            if (old != null) heroBottom.removeView(old);
            List<String> ids = CigramFeatured.badgesFor(item);
            LinearLayout row = new LinearLayout(activity);
            row.setTag(TAG_BADGES);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            row.setGravity(Gravity.CENTER_HORIZONTAL);   // v10.45: centred under the title
            int count = 0;
            for (int i = 0; i < ids.size() && count < 5; i++) {
                String label = CigramContent.badgeLabel(ids.get(i));
                if (label.length() == 0) continue;
                View chip = "new".equals(ids.get(i).trim().toLowerCase(java.util.Locale.US))
                        ? CigramHome.newBadge(activity, 15) : null;
                if (chip == null) chip = CigramHome.badgeView(activity, label,
                        CigramContent.badgeColor(ids.get(i)), 12f);
                ((LinearLayout.LayoutParams) chip.getLayoutParams()).setMarginStart(
                        CigramStage1InitProvider.cigramDp(activity, 6));
                row.addView(chip);
                count++;
            }
            if (count == 0) return;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = CigramStage1InitProvider.cigramDp(activity, 8);
            heroBottom.addView(row, lp);
        } catch (Throwable ignored) { }
    }

    /**
     * v10.37: «الإبلاغ عن مشكلة» was removed from film and series pages. Kept only so an older row
     * (from a page that was already open) is taken away; nothing is added any more.
     */
    public static void bindReportLink(final Activity activity, LinearLayout root, final JSONObject item) {
        if (root == null) return;
        try {
            View old = root.findViewWithTag(TAG_ROW);
            if (old != null) ((android.view.ViewGroup) old.getParent()).removeView(old);
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- dialog

    public static final String SUPPORT_EMAIL = "app.cigram@gmail.com";

    private static void showDialog(final Activity activity, final JSONObject item) {
        if (activity == null || activity.isFinishing()) return;
        final boolean series = CigramContent.isSeries(item);
        final int dp = 1;
        final CigramUI.Sheet sheet = CigramUI.sheet(activity)
                .icon(CigramUI.ICON_FLAG, CigramUI.RED)
                .title("الإبلاغ عن مشكلة");
        String name = CigramContent.title(item);
        sheet.message(name.length() == 0 ? "اختر نوع المشكلة وسنراجعها في أقرب وقت."
                : "بخصوص: " + name);

        final int[] selected = new int[]{-1};
        final View[] rows = new View[ISSUES.length];
        final View[] dots = new View[ISSUES.length];
        final TextView[] names = new TextView[ISSUES.length];

        final LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        final EditText season = new EditText(activity);
        final EditText episode = new EditText(activity);
        final EditText message = new EditText(activity);
        final LinearLayout episodeLine = new LinearLayout(activity);
        final TextView messageTitle = CigramUI.label(activity, "تفاصيل إضافية (اختياري)", 13f,
                CigramUI.MUTED, true);

        for (int i = 0; i < ISSUES.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setClickable(true);
            row.setFocusable(true);
            row.setPadding(CigramUI.dp(activity, 14), 0, CigramUI.dp(activity, 14), 0);
            row.setBackground(CigramUI.round(CigramUI.BG_SOFT, 0xFF303033, 14, activity));

            View dot = new View(activity);
            dot.setBackground(ring(activity, false));
            TextView label = CigramUI.label(activity, ISSUES[i][1], 15f, 0xFFE6EEF2, false);
            row.addView(dot, new LinearLayout.LayoutParams(CigramUI.dp(activity, 20), CigramUI.dp(activity, 20)));
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, -1, 1f);
            ll.setMarginStart(CigramUI.dp(activity, 12));
            row.addView(label, ll);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, CigramUI.dp(activity, 46));
            rl.topMargin = i == 0 ? 0 : CigramUI.dp(activity, 7);
            list.addView(row, rl);
            rows[i] = row;
            dots[i] = dot;
            names[i] = label;

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selected[0] = index;
                    for (int k = 0; k < rows.length; k++) {
                        boolean on = k == index;
                        rows[k].setBackground(on
                                ? CigramUI.round(0x33E21B14, CigramUI.RED, 14, activity)
                                : CigramUI.round(CigramUI.BG_SOFT, 0xFF303033, 14, activity));
                        dots[k].setBackground(ring(activity, on));
                        names[k].setTextColor(on ? 0xFFFFFFFF : 0xFFE6EEF2);
                    }
                    boolean other = "other".equals(ISSUES[index][0]);
                    messageTitle.setText(other ? "اشرح المشكلة (مطلوب)" : "تفاصيل إضافية (اختياري)");
                    message.setHint(other ? "اكتب وصفاً قصيراً للمشكلة" : "أي معلومة تساعدنا على حل المشكلة");
                    String id = ISSUES[index][0];
                    episodeLine.setVisibility(("wrong_episode".equals(id) || "missing_episode".equals(id)
                            || "video_broken".equals(id) || "subtitle_broken".equals(id)
                            || "subtitle_sync".equals(id) || "audio_issue".equals(id)) ? View.VISIBLE : View.GONE);
                }
            });
        }
        sheet.view(list);

        if (series) {
            episodeLine.setOrientation(LinearLayout.HORIZONTAL);
            episodeLine.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            CigramUI.styleInput(season, "رقم الموسم");
            CigramUI.styleInput(episode, "رقم الحلقة");
            season.setInputType(InputType.TYPE_CLASS_NUMBER);
            episode.setInputType(InputType.TYPE_CLASS_NUMBER);
            season.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3)});
            episode.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
            season.setSingleLine(true);
            episode.setSingleLine(true);
            int last = CigramUserData.lastSeason(activity, CigramContent.seriesKey(item));
            if (last > 0) season.setText(String.valueOf(last));
            LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -2, 1f);
            a.setMarginEnd(CigramUI.dp(activity, 8));
            episodeLine.addView(season, a);
            episodeLine.addView(episode, new LinearLayout.LayoutParams(0, -2, 1f));
            episodeLine.setVisibility(View.GONE);
            sheet.view(episodeLine);
        }

        LinearLayout.LayoutParams mt = new LinearLayout.LayoutParams(-1, -2);
        mt.topMargin = CigramUI.dp(activity, 14);
        sheet.body.addView(messageTitle, mt);
        CigramUI.styleInput(message, "أي معلومة تساعدنا على حل المشكلة");
        message.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_MESSAGE)});
        message.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        message.setMinLines(2);
        message.setMaxLines(4);
        message.setGravity(Gravity.RIGHT | Gravity.TOP);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = CigramUI.dp(activity, 6);
        sheet.body.addView(message, mp);

        TextView note = CigramUI.label(activity,
                "يُرفق مع البلاغ إصدار التطبيق ونوع الجهاز (وحسابك إن كنت مسجلاً) لمساعدتنا على الحل.",
                11.5f, 0xFF6F8591, false);
        note.setGravity(Gravity.RIGHT | Gravity.TOP);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, -2);
        np.topMargin = CigramUI.dp(activity, 8);
        sheet.body.addView(note, np);

        final Runnable[] keepOpenGuard = new Runnable[1];
        sheet.primary("إرسال البلاغ", CigramUI.RED, new CigramUI.Click() {
            @Override public boolean onClick(CigramUI.Sheet s) {
                if (selected[0] < 0) {
                    CigramSilent.toast(activity, "اختر نوع المشكلة أولاً", android.widget.Toast.LENGTH_SHORT);
                    return true;
                }
                String issue = ISSUES[selected[0]][0];
                String text = message.getText().toString().trim();
                if ("other".equals(issue) && text.length() < 3) {
                    CigramSilent.toast(activity, "اكتب وصفاً قصيراً للمشكلة", android.widget.Toast.LENGTH_SHORT);
                    message.requestFocus();
                    return true;
                }
                int sNum = series ? parse(season.getText().toString()) : 0;
                int eNum = series ? parse(episode.getText().toString()) : 0;
                submit(activity, item, issue, text, sNum, eNum);
                return false;
            }
        }).secondary("إلغاء", null);
        sheet.show();
    }

    private static android.graphics.drawable.Drawable ring(Context c, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (on) {
            g.setColor(CigramUI.RED);
            g.setStroke(CigramUI.dp(c, 5), 0xFF5A1815);
        } else {
            g.setColor(Color.TRANSPARENT);
            g.setStroke(CigramUI.dp(c, 2), 0xFF4A6573);
        }
        return g;
    }

    private static int parse(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable ignored) { return 0; }
    }

    // ---------------------------------------------------------------- submit

    public static void submit(final Activity activity, JSONObject item, String issue, String message,
                              int season, int episode) {
        if (activity == null || item == null) return;
        final Context app = activity.getApplicationContext();
        String blocked = spamCheck(app, item, issue);
        if (blocked != null) {
            CigramSilent.toast(activity, blocked, android.widget.Toast.LENGTH_SHORT);
            return;
        }
        try {
            JSONObject r = new JSONObject();
            boolean series = CigramContent.isSeries(item);
            String uid = CigramUserData.authUserId(app);
            if (uid.length() > 0) r.put("user_id", uid);
            r.put("content_id", CigramContent.text(item, "id", "movie_id", "item_id", "content_id"));
            r.put("content_type", series ? "series" : "movie");
            r.put("title", cut(CigramContent.title(item), 120));
            if (series) {
                r.put("series_id", CigramContent.text(item, "series_id", "id"));
                if (season > 0 && season <= 200) r.put("season", season);
                if (episode > 0 && episode <= 5000) r.put("episode", episode);
            }
            r.put("issue_type", issue);
            r.put("issue_label", issueLabel(issue));
            r.put("message", cut(message, MAX_MESSAGE));
            r.put("app_version", appVersion(app));
            r.put("timestamp", System.currentTimeMillis());
            r.put("client_id", clientId(app));
            // "صندوق وارد للمستخدمين": the admin inbox groups messages by this field
            r.put("inbox", "users");
            r.put("source", "app_report");
            r.put("support_email", SUPPORT_EMAIL);
            r.put("device", cut(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL, 80));
            r.put("android", android.os.Build.VERSION.RELEASE);
            if (CigramUserData.isLoggedIn(app)) {
                r.put("user_email", CigramUserData.email(app));
                try {
                    String nm = app.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
                            .getString("name", "");
                    if (nm != null && nm.length() > 0) r.put("user_name", cut(nm, 60));
                } catch (Throwable ignored) { }
            }
            markSent(app, item, issue);

            final JSONObject report = r;
            CigramSync.runIO(new Runnable() {
                @Override public void run() {
                    final boolean sent = send(app, report);
                    if (!sent) enqueue(app, report);
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            if (activity.isFinishing()) return;
                            showResult(activity, sent, report);
                        }
                    });
                    if (sent) flushQueue(app);
                }
            });
        } catch (Throwable error) {
            CigramSilent.toast(activity, "تعذر إنشاء البلاغ", android.widget.Toast.LENGTH_SHORT);
        }
    }

    private static String issueLabel(String id) {
        for (int i = 0; i < ISSUES.length; i++) if (ISSUES[i][0].equals(id)) return ISSUES[i][1];
        return id;
    }

    private static void showResult(final Activity activity, boolean sent, final JSONObject report) {
        if (sent) {
            CigramUI.sheet(activity)
                    .icon(CigramUI.ICON_CHECK, CigramUI.GREEN)
                    .title("تم إرسال البلاغ")
                    .message("شكراً لك! وصل بلاغك إلى فريق الإدارة وسنراجعه في أقرب وقت.")
                    .primary("تم", CigramUI.GREEN, null)
                    .show();
            return;
        }
        CigramUI.sheet(activity)
                .icon(CigramUI.ICON_WARN, CigramUI.AMBER)
                .title("تعذر الإرسال الآن")
                .message("لا يوجد اتصال بالخادم. حفظنا بلاغك وسيُرسل تلقائياً عند توفر الاتصال، "
                        + "ويمكنك أيضاً إرساله الآن عبر البريد الإلكتروني.")
                .primary("إرسال عبر البريد", CigramUI.AMBER, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) {
                        sendByEmail(activity, report);
                        return false;
                    }
                })
                .secondary("حسناً", null)
                .show();
    }

    /** Fallback channel: opens the mail app with the report ready to send to app.cigram@gmail.com. */
    public static void sendByEmail(Activity activity, JSONObject r) {
        try {
            StringBuilder b = new StringBuilder();
            b.append("نوع المشكلة: ").append(r.optString("issue_label")).append('\n');
            b.append("العنوان: ").append(r.optString("title")).append('\n');
            b.append("النوع: ").append("series".equals(r.optString("content_type")) ? "مسلسل" : "فيلم").append('\n');
            if (r.has("season")) b.append("الموسم: ").append(r.optInt("season")).append('\n');
            if (r.has("episode")) b.append("الحلقة: ").append(r.optInt("episode")).append('\n');
            if (r.optString("message").length() > 0) b.append("التفاصيل: ").append(r.optString("message")).append('\n');
            b.append("\n— معلومات تقنية —\n");
            b.append("الإصدار: ").append(r.optString("app_version")).append('\n');
            b.append("الجهاز: ").append(r.optString("device")).append(" (Android ").append(r.optString("android")).append(")\n");
            if (r.optString("user_email").length() > 0) b.append("الحساب: ").append(r.optString("user_email")).append('\n');
            b.append("المعرّف: ").append(r.optString("content_id")).append('\n');
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SENDTO);
            i.setData(android.net.Uri.parse("mailto:"));
            i.putExtra(android.content.Intent.EXTRA_EMAIL, new String[]{SUPPORT_EMAIL});
            i.putExtra(android.content.Intent.EXTRA_SUBJECT,
                    "بلاغ: " + r.optString("issue_label") + " — " + r.optString("title"));
            i.putExtra(android.content.Intent.EXTRA_TEXT, b.toString());
            activity.startActivity(android.content.Intent.createChooser(i, "إرسال البلاغ عبر البريد"));
        } catch (Throwable error) {
            CigramSilent.toast(activity, "لا يوجد تطبيق بريد. راسلنا على " + SUPPORT_EMAIL, android.widget.Toast.LENGTH_LONG);
        }
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String appVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Random id of this installation, used only for server-side rate limiting. */
    private static String clientId(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = p.getString("client_id", "");
        if (id == null || id.length() == 0) {
            id = java.util.UUID.randomUUID().toString().replace("-", "");
            p.edit().putString("client_id", id).apply();
        }
        return id;
    }

    // ------------------------------------------------------------ spam guard

    private static String spamCheck(Context c, JSONObject item, String issue) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = p.getLong("last_at", 0L);
        if (now - last < MIN_GAP_MS && now >= last) return "انتظر قليلاً قبل إرسال بلاغ آخر";
        String key = CigramContent.itemKey(item) + "|" + issue;
        if (now - p.getLong("same::" + key.hashCode(), 0L) < SAME_ISSUE_MS) {
            return "أرسلت هذا البلاغ مؤخراً";
        }
        try {
            JSONArray hour = new JSONArray(p.getString("hour", "[]"));
            int n = 0;
            for (int i = 0; i < hour.length(); i++) if (now - hour.optLong(i, 0L) < HOUR_MS) n++;
            if (n >= MAX_PER_HOUR) return "وصلت للحد الأقصى من البلاغات في الساعة";
        } catch (Throwable ignored) { }
        return null;
    }

    private static void markSent(Context c, JSONObject item, String issue) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        JSONArray keep = new JSONArray();
        try {
            JSONArray hour = new JSONArray(p.getString("hour", "[]"));
            for (int i = 0; i < hour.length(); i++) {
                if (now - hour.optLong(i, 0L) < HOUR_MS) keep.put(hour.optLong(i, 0L));
            }
        } catch (Throwable ignored) { }
        keep.put(now);
        String key = CigramContent.itemKey(item) + "|" + issue;
        p.edit().putLong("last_at", now).putString("hour", keep.toString())
                .putLong("same::" + key.hashCode(), now).apply();
    }

    // ----------------------------------------------------------- network/queue

    /**
     * Sends a ready report to the Admin inbox from any page (a paused channel, for one): the common fields are
     * added here, and a report that cannot leave now waits in the same queue as the others.
     */
    static void submit(Context context, final JSONObject report) {
        if (context == null || report == null) return;
        final Context app = context.getApplicationContext();
        try {
            String uid = CigramUserData.authUserId(app);
            if (uid.length() > 0) report.put("user_id", uid);
            report.put("app_version", appVersion(app));
            report.put("client_id", clientId(app));
            report.put("device", cut(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL, 80));
            report.put("android", android.os.Build.VERSION.RELEASE);
            if (CigramUserData.isLoggedIn(app)) report.put("user_email", CigramUserData.email(app));
        } catch (Throwable ignored) { }
        CigramSync.runIO(new Runnable() {
            @Override public void run() {
                if (send(app, report)) flushQueue(app);
                else enqueue(app, report);
            }
        });
    }

    private static boolean send(Context c, JSONObject report) {
        String body = CigramSync.post(CigramSync.API_BASE + "/report",
                CigramUserData.token(c), report.toString());
        if (body == null) return false;
        try {
            return new JSONObject(body).optBoolean("ok", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static synchronized void enqueue(Context c, JSONObject report) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray q = new JSONArray(p.getString("queue", "[]"));
            q.put(report);
            JSONArray out = new JSONArray();
            for (int i = Math.max(0, q.length() - MAX_QUEUE); i < q.length(); i++) out.put(q.opt(i));
            p.edit().putString("queue", out.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** Sends queued reports (called on app start and after a successful send). */
    public static void flushQueue(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        synchronized (CigramReport.class) {
            if (flushing) return;
            flushing = true;
        }
        CigramSync.runIO(new Runnable() {
            @Override public void run() {
                try {
                    SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    JSONArray q = new JSONArray(p.getString("queue", "[]"));
                    JSONArray left = new JSONArray();
                    boolean failed = false;
                    for (int i = 0; i < q.length(); i++) {
                        JSONObject r = q.optJSONObject(i);
                        if (r == null) continue;
                        if (failed || !send(app, r)) {
                            failed = true;
                            left.put(r);
                        }
                    }
                    p.edit().putString("queue", left.toString()).apply();
                } catch (Throwable ignored) {
                } finally {
                    synchronized (CigramReport.class) { flushing = false; }
                }
            }
        });
    }
}
