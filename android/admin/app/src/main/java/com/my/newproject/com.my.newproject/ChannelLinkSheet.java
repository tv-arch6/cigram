package com.my.newproject;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 3.0.2 — «الرابط الحالي / استبدال الرابط»: one small sheet that opens ON TOP of whatever screen the admin is on.
 *
 * <pre>
 *   [channel name]
 *   section · why it is listed (reason of the last scan / «بلّغ عنها N»)
 *   الرابط الحالي   — the link as it is in the catalogue (selectable) · نسخ · فتح
 *   الرابط الجديد   — a field + «لصق»
 *   [استبدال الرابط]  [إدارة القنوات]  [إغلاق]
 * </pre>
 *
 * Before this, a tap on a stopped channel in Cigram Live Health threw the admin into «إدارة القنوات» (a whole page that loads
 * the full catalogue). Now the admin sees the link that stopped and replaces it right there; «إدارة القنوات» is still one tap
 * away, but only when the admin asks for it.
 *
 * Saving is {@link CgChannelReportsActivity#save}: the new link goes into the channels catalogue ({@code /channels/update}) and, when
 * the channel came from a «بلّغ المدير» report, the report is closed afterwards (best effort: the link is already live by then).
 */
final class ChannelLinkSheet {

    private ChannelLinkSheet() { }

    /** The rule the app's player and the old «استبدل برابط» dialog already used: a stream address, never a page of words. */
    static boolean validStream(String s) {
        if (s == null) return false;
        String u = s.trim().toLowerCase(java.util.Locale.ROOT);
        if (u.length() < 8) return false;
        if (!(u.startsWith("http://") || u.startsWith("https://") || u.startsWith("rtmp://") || u.startsWith("rtmps://") || u.startsWith("rtsp://"))) return false;
        String host = u.substring(u.indexOf("://") + 3);
        return host.length() > 0 && !host.startsWith("/") && !host.startsWith("|");
    }

    /** Arabic reason why {@code typed} cannot be saved, or "" when it can. */
    static String problem(String typed, String current) {
        String t = typed == null ? "" : typed.trim();
        if (t.length() == 0) return "الصق الرابط الجديد أولاً.";
        if (!validStream(t)) return "الصق رابط البث كاملاً (يبدأ بـ https://).";
        if (t.equals(current == null ? "" : current.trim())) return "هذا هو الرابط الحالي نفسه، الصق رابطاً جديداً.";
        return "";
    }

    /** Called on the UI thread after the new link was saved (the caller refreshes its list / re-checks the channel). */
    interface Saved {
        void ok(String newUrl);
    }

    /** Runnable flavour for callers that only need to reload. */
    static Saved reload(final Runnable r) {
        return new Saved() {
            @Override public void ok(String newUrl) { if (r != null) r.run(); }
        };
    }

    /**
     * Opens the sheet. {@code ch}: name, url (current link), section, detail (why it is listed), key (report key, optional), id (optional).
     * {@code done} runs after the new link was saved.
     */
    static void show(final CgBase a, final JSONObject ch, final Saved done) {
        if (a == null || a.isFinishing()) return;
        final String name = ch.optString("name");
        final String current = ch.optString("url").trim();

        LinearLayout box = CgUi.vbox(a);
        box.setPadding(CgUi.dp(a, 18), CgUi.dp(a, 4), CgUi.dp(a, 18), CgUi.dp(a, 4));

        String meta = ch.optString("section");
        String detail = ch.optString("detail");
        if (meta.length() > 0 && detail.length() > 0) meta = meta + " · " + detail;
        else if (detail.length() > 0) meta = detail;
        if (meta.length() > 0) box.addView(CgUi.muted(a, meta), CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));

        // ---- the link that is in the catalogue now
        box.addView(CgUi.text(a, "الرابط الحالي", 12, CgCfg.MUTED, true), CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 2, 4, 2, 4));
        TextView cur = CgUi.text(a, current.length() > 0 ? current : "لا يوجد رابط محفوظ", 12.5f, current.length() > 0 ? CgCfg.TEXT : CgCfg.MUTED, false);
        cur.setTextIsSelectable(true);
        cur.setTextDirection(View.TEXT_DIRECTION_LTR);
        cur.setGravity(Gravity.START);
        cur.setMaxLines(6);
        cur.setEllipsize(TextUtils.TruncateAt.END);
        cur.setBackground(CgUi.bg(a, CgCfg.FIELD, 12, CgCfg.STROKE));
        cur.setPadding(CgUi.dp(a, 12), CgUi.dp(a, 10), CgUi.dp(a, 12), CgUi.dp(a, 10));
        box.addView(cur, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        if (current.length() > 0) {
            LinearLayout row = CgUi.hbox(a);
            TextView copy = CgUi.smallButton(a, "نسخ الرابط", 1);
            copy.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { copy(a, name, current); }
            });
            row.addView(copy, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            TextView open = CgUi.smallButton(a, "فتح الرابط", 1);
            open.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openOutside(a, current); }
            });
            row.addView(open, CgUi.lpWeight(a, 1f, 8, 0, 0, 0));
            box.addView(row, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 4));
        }

        // ---- the new link
        box.addView(CgUi.text(a, "الرابط الجديد", 12, CgCfg.MUTED, true), CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 2, 12, 2, 4));
        final EditText field = CgUi.urlField(a, "https://…/stream.m3u8");
        box.addView(field, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        TextView paste = CgUi.smallButton(a, "لصق من الحافظة", 1);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String clip = clipboard(a);
                if (clip.length() == 0) { CgUi.toast(a, "الحافظة فارغة."); return; }
                field.setText(clip);
                field.setSelection(field.getText().length());
            }
        });
        box.addView(paste, CgUi.lp(a, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 4));

        ScrollView sv = new ScrollView(a);
        sv.addView(box);

        final AlertDialog d = CgUi.dialog(a).setTitle(name.length() > 0 ? name : "قناة")
                .setView(sv)
                .setPositiveButton("استبدال الرابط", null)          // set below: an invalid link must keep the sheet open
                .setNeutralButton("إدارة القنوات", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        a.startActivity(new Intent(a, CgChannelsActivity.class));   // only when the admin asks for it
                    }
                })
                .setNegativeButton("إغلاق", null)
                .create();
        d.show();
        try {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    String typed = field.getText().toString().trim();
                    String why = problem(typed, current);
                    if (why.length() > 0) { CgUi.toast(a, why); return; }
                    d.dismiss();
                    CgChannelReportsActivity.save(a, ch, typed, done);
                }
            });
        } catch (Throwable ignored) { }
    }

    private static String clipboard(Context c) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) return "";
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(c);
            return t == null ? "" : t.toString().trim();
        } catch (Throwable e) {
            return "";
        }
    }

    private static void copy(Context c, String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            CgUi.toast(c, "تم نسخ الرابط.");
        } catch (Throwable e) {
            CgUi.toast(c, "تعذر النسخ.");
        }
    }

    private static void openOutside(Context c, String url) {
        try {
            String clean = url;
            int bar = clean.indexOf('|');                      // the app's "url|Header=value" form: open the bare address
            if (bar > 0) clean = clean.substring(0, bar);
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(clean)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable e) {
            CgUi.toast(c, "لا يوجد تطبيق يفتح هذا الرابط.");
        }
    }
}
