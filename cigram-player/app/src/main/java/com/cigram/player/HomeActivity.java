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
        scroll.setBackgroundColor(Ui.PAGE);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        col.setPadding(pad, Ui.dp(this, 56), pad, Ui.dp(this, 28));
        scroll.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- large title
        TextView title = Ui.text(this, "مشغّل سيكرام", 34f, Ui.INK, true);
        title.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        col.addView(title);
        TextView sub = Ui.text(this, "روابط MP4 و HLS و DASH وملفات الفيديو على جهازك", 14f, Ui.INK2, false);
        sub.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        col.addView(sub, lp(0, 4, 0, 0));

        // ---- play a link: one field with the actions inside the same card
        LinearLayout link = Ui.group(this);
        link.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        input = new EditText(this);
        input.setHint("الصق رابط الفيديو هنا");
        input.setHintTextColor(Ui.INK3);
        input.setTextColor(Ui.INK);
        input.setTextSize(15f);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setBackground(Ui.pill(this, 0xFF2C2C2E, 12));
        input.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        input.setMinHeight(Ui.dp(this, 48));
        link.addView(input);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView play = Ui.button(this, "تشغيل", 0xFFFFFFFF, 0xFF000000);
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { playTyped(); }
        });
        TextView paste = Ui.button(this, "لصق", 0xFF2C2C2E, Ui.INK);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pasteLink(); }
        });
        LinearLayout.LayoutParams wide = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
        wide.topMargin = Ui.dp(this, 12);
        LinearLayout.LayoutParams narrow = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        narrow.topMargin = Ui.dp(this, 12);
        narrow.setMarginStart(Ui.dp(this, 10));
        row.addView(play, wide);
        row.addView(paste, narrow);
        link.addView(row);
        col.addView(link, lp(0, 22, 0, 0));

        // ---- the device
        LinearLayout device = Ui.group(this);
        Ui.row(this, device, Ui.Glyph.FOLDER, "فتح فيديو من الجهاز", "من الملفات أو المعرض", new View.OnClickListener() {
            @Override public void onClick(View v) { pickFile(); }
        });
        col.addView(device, lp(0, 16, 0, 0));

        // ---- continue watching
        col.addView(Ui.caption(this, "متابعة المشاهدة"), lp(0, 26, 0, 8));
        recentBox = Ui.group(this);
        col.addView(recentBox);

        // ---- Cigram app (optional) + privacy
        col.addView(Ui.caption(this, "عام"), lp(0, 26, 0, 8));
        LinearLayout general = Ui.group(this);
        boolean installed = isMainAppInstalled();
        Ui.row(this, general, Ui.Glyph.APP, "تطبيق سيكرام",
                installed ? "مثبّت · يفتح أفلامه ومسلسلاته هنا تلقائياً" : "المشغّل يعمل وحده، ومع سيكرام عند تثبيته",
                installed ? new View.OnClickListener() {
                    @Override public void onClick(View v) { openMainApp(); }
                } : null);
        Ui.row(this, general, Ui.Glyph.SHIELD, "الخصوصية والإعلانات", "الموافقة، البيانات، وإعلانات Google", new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(HomeActivity.this, PrivacyActivity.class)); }
        });
        col.addView(general);

        TextView notice = Ui.text(this,
                "يشغّل هذا التطبيق الروابط والملفات التي تقدّمها أنت أو التطبيقات التي تفتحها. "
                        + "أنت المسؤول عن امتلاك حق مشاهدة المحتوى. لا يتجاوز المشغّل أي حماية (DRM) أو قيود وصول.",
                12f, Ui.INK3, false);
        notice.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        col.addView(notice, lp(0, 18, 0, 0));
        TextView version = Ui.text(this, "الإصدار " + versionName(), 12f, Ui.INK3, false);
        version.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        col.addView(version, lp(0, 6, 0, 0));

        setContentView(scroll);
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
            Ui.row(this, recentBox, Ui.Glyph.CLOCK, "لا شيء بعد", "تظهر هنا الفيديوهات التي توقفت في منتصفها", null);
            return;
        }
        for (final ResumeStore.Entry e : items) {
            String name = e.title.length() > 0 ? e.title : e.url;
            View item = Ui.row(this, recentBox, Ui.Glyph.PLAY, name,
                    Ui.time(e.position) + (e.duration > 0 ? " / " + Ui.time(e.duration) : ""), new View.OnClickListener() {
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
