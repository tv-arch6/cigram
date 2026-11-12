package com.cigram.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Standalone home screen - useful with or without the Cigram app:
 *   - play a link you paste (MP4 / HLS / DASH ...)
 *   - open a video file from the device
 *   - continue watching (local list, long-press to remove)
 *   - privacy &amp; ads settings, about
 *   - optional shortcut to the Cigram app when it is installed (never an install prompt)
 * Shows a message if another screen sent the user here after a bad link.
 */
public class HomeActivity extends Activity {

    private static final int REQ_FILE = 21;

    private EditText input;
    private LinearLayout recentBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        build();
        showMessageIfAny(getIntent());
        // AdMob "app open" ad: only when the app is opened from its icon (not on rotation / return), at most once every 4 h
        if (savedInstanceState == null && !isLaunchedForPlayback()) AdMobAds.showAppOpenIfDue(this);
    }

    private boolean isLaunchedForPlayback() {
        Intent i = getIntent();
        return i != null && i.getStringExtra("message") != null;     // sent back here after a bad link: no ad on top of the message
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showMessageIfAny(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRecent();
    }

    private void showMessageIfAny(Intent i) {
        String m = i == null ? null : i.getStringExtra("message");
        if (m != null && m.length() > 0) {
            Ui.toast(this, m);
            i.removeExtra("message");
        }
    }

    // ------------------------------------------------------------------ UI

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF0B0F14);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, Ui.dp(this, 32), pad, pad);
        scroll.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = Ui.text(this, "مشغل سيكرام", 26f, 0xFFFFFFFF, true);
        col.addView(title);
        TextView sub = Ui.text(this, "شغّل روابط الفيديو المباشرة (MP4 / HLS / DASH) وملفات الفيديو على جهازك.", 14f, 0xB3FFFFFF, false);
        col.addView(sub, lp(0, 6, 0, 0));

        // ---- play a link
        col.addView(section("تشغيل رابط"), lp(0, 24, 0, 8));
        input = new EditText(this);
        input.setHint("https://…");
        input.setHintTextColor(0x66FFFFFF);
        input.setTextColor(0xFFFFFFFF);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setBackground(Ui.pill(this, 0x22FFFFFF, 12));
        input.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        input.setMinHeight(Ui.dp(this, 48));
        col.addView(input);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView paste = Ui.button(this, "لصق", 0x33FFFFFF, 0xFFFFFFFF);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pasteLink(); }
        });
        TextView play = Ui.button(this, "تشغيل", 0xFFFFB300, 0xFF0B0F14);
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { playTyped(); }
        });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        half.topMargin = Ui.dp(this, 10);
        half.rightMargin = Ui.dp(this, 6);
        half.leftMargin = Ui.dp(this, 6);
        row.addView(paste, half);
        row.addView(play, half);
        col.addView(row);

        TextView file = Ui.button(this, "فتح فيديو من الجهاز", 0x33FFFFFF, 0xFFFFFFFF);
        file.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFile(); }
        });
        col.addView(file, lp(0, 10, 0, 0));

        // ---- continue watching
        col.addView(section("متابعة المشاهدة"), lp(0, 28, 0, 8));
        recentBox = new LinearLayout(this);
        recentBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(recentBox);

        // ---- Cigram app (optional)
        col.addView(section("تطبيق سيكرام"), lp(0, 28, 0, 8));
        boolean installed = isMainAppInstalled();
        TextView info = Ui.text(this, installed
                ? "تطبيق سيكرام مثبّت. الأفلام والمسلسلات المدعومة تُفتح هنا تلقائياً من داخل التطبيق."
                : "هذا المشغل يعمل بشكل مستقل. إن ثبّتّ تطبيق سيكرام فسيتمكن من فتح المحتوى هنا مباشرة.",
                14f, 0xB3FFFFFF, false);
        col.addView(info);
        if (installed) {
            TextView open = Ui.button(this, "فتح تطبيق سيكرام", 0x33FFFFFF, 0xFFFFFFFF);
            open.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openMainApp(); }
            });
            col.addView(open, lp(0, 10, 0, 0));
        }

        // ---- privacy / about
        col.addView(section("الخصوصية"), lp(0, 28, 0, 8));
        TextView privacy = Ui.button(this, "الخصوصية والإعلانات", 0x33FFFFFF, 0xFFFFFFFF);
        privacy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(HomeActivity.this, PrivacyActivity.class)); }
        });
        col.addView(privacy);

        TextView notice = Ui.text(this,
                "يشغّل هذا التطبيق الروابط والملفات التي تقدّمها أنت أو التطبيقات التي تفتحها. "
                        + "أنت المسؤول عن امتلاك حق مشاهدة المحتوى. لا يتجاوز المشغل أي حماية (DRM) أو قيود وصول.",
                12f, 0x80FFFFFF, false);
        col.addView(notice, lp(0, 20, 0, 0));
        TextView version = Ui.text(this, "الإصدار " + versionName(), 12f, 0x80FFFFFF, false);
        col.addView(version, lp(0, 8, 0, 0));

        setContentView(scroll);
    }

    private TextView section(String s) {
        return Ui.text(this, s, 16f, 0xFFFFB300, true);
    }

    private LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(Ui.dp(this, l), Ui.dp(this, t), Ui.dp(this, r), Ui.dp(this, b));
        return p;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------ actions

    private void pasteLink() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData d = cm == null ? null : cm.getPrimaryClip();
            CharSequence t = d != null && d.getItemCount() > 0 ? d.getItemAt(0).coerceToText(this) : null;
            String url = t == null ? "" : PlayerIntent.urlInText(t.toString());
            if (url.length() == 0 && t != null) url = t.toString().trim();
            if (url.length() == 0) { Ui.toast(this, "لا يوجد رابط في الحافظة."); return; }
            input.setText(url);
            input.setSelection(url.length());
        } catch (Throwable t) {
            Ui.toast(this, "تعذّر قراءة الحافظة.");
        }
    }

    private void playTyped() {
        String raw = input.getText().toString().trim();
        String clean = Streams.cleanUrl(PlayerIntent.urlInText(raw).length() > 0 ? PlayerIntent.urlInText(raw) : raw);
        if (clean.length() == 0) { Ui.toast(this, "الصق رابط الفيديو أولاً."); return; }
        if (!Streams.isAllowedScheme(clean) || clean.toLowerCase(java.util.Locale.US).startsWith("content://")) {
            Ui.toast(this, "الرابط غير مدعوم. استخدم رابطاً يبدأ بـ https:// أو http://");
            return;
        }
        Intent i = new Intent(this, PlayActivity.class);
        i.putExtra("url", clean);
        i.putExtra("watch_now", true);        // the play button = a "watch now" press -> AdMob interstitial
        startActivity(i);
    }

    private void pickFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("video/*");
            startActivityForResult(i, REQ_FILE);
        } catch (Throwable t) {
            Ui.toast(this, "لا يوجد تطبيق لاختيار الملفات على هذا الجهاز.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        Intent i = new Intent(this, PlayActivity.class);
        i.setData(uri);
        i.setAction(Intent.ACTION_VIEW);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);          // a local file: no ad (AdMob ads are only shown for network titles)
    }

    private void refreshRecent() {
        recentBox.removeAllViews();
        List<ResumeStore.Entry> items = ResumeStore.list(this, 10);
        if (items.isEmpty()) {
            recentBox.addView(Ui.text(this, "لا توجد عناصر بعد. ستظهر هنا الفيديوهات التي توقفت في منتصفها.", 14f, 0x80FFFFFF, false));
            return;
        }
        for (final ResumeStore.Entry e : items) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setBackground(Ui.pill(this, 0x22FFFFFF, 12));
            item.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
            item.setMinimumHeight(Ui.dp(this, 56));
            item.setClickable(true);
            String name = e.title.length() > 0 ? e.title : e.url;
            TextView t = Ui.text(this, name, 15f, 0xFFFFFFFF, true);
            t.setMaxLines(1);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.addView(t);
            item.addView(Ui.text(this, Ui.time(e.position) + (e.duration > 0 ? " / " + Ui.time(e.duration) : ""), 12f, 0xB3FFFFFF, false));
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent i = new Intent(HomeActivity.this, PlayActivity.class);
                    i.putExtra("url", e.url);
                    i.putExtra("title", e.title);
                    i.putExtra("resume_ms", e.position);
                    i.putExtra("watch_now", true);
                    startActivity(i);
                }
            });
            item.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    new AlertDialog.Builder(HomeActivity.this)
                            .setMessage("إزالة «" + (e.title.length() > 0 ? e.title : "العنصر") + "» من القائمة؟")
                            .setPositiveButton("إزالة", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    ResumeStore.clear(HomeActivity.this, e.key);
                                    refreshRecent();
                                }
                            })
                            .setNegativeButton("إلغاء", null)
                            .show();
                    return true;
                }
            });
            recentBox.addView(item, lp(0, 0, 0, 8));
        }
    }

    // ------------------------------------------------------------------ Cigram app (optional)

    private boolean isMainAppInstalled() {
        try {
            getPackageManager().getPackageInfo(AppConfig.MAIN_APP_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private void openMainApp() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(AppConfig.MAIN_APP_PACKAGE);
            if (i == null) { Ui.toast(this, "تعذّر فتح تطبيق سيكرام."); return; }
            startActivity(i);
        } catch (Throwable t) {
            Ui.toast(this, "تعذّر فتح تطبيق سيكرام.");
        }
    }
}
