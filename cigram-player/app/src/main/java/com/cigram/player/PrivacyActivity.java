package com.cigram.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * "Privacy &amp; ads": what the app collects and sends, the ad-statistics switch, reset of the installation id,
 * deletion of the local watch list, and the privacy-policy link (shown only when AppConfig.PRIVACY_POLICY_URL is set).
 * Keep the texts here identical in meaning to PRIVACY-DATA-SAFETY.md and to the Play Console Data safety form.
 */
public class PrivacyActivity extends Activity {

    private Switch stats;
    private boolean binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0B0F14);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, Ui.dp(this, 32), pad, pad);
        scroll.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(Ui.text(this, "الخصوصية والإعلانات", 24f, 0xFFFFFFFF, true));

        col.addView(head("ما الذي يحدث عند التشغيل"));
        col.addView(body("• الفيديو يُحمَّل مباشرة من الرابط الذي تشغّله؛ يرى مزوّد ذلك الرابط عنوان IP الخاص بك وما يطلبه المشغل.\n"
                + "• لا حساب ولا تسجيل دخول في هذا التطبيق، ولا نجمع اسمك أو بريدك أو موقعك أو جهات اتصالك أو ملفاتك.\n"
                + "• لا نستخدم مكتبات تحليلات أو تقارير أعطال خاصة بنا."));

        if (AppConfig.ADMOB_ENABLED) {
            col.addView(head("إعلانات Google AdMob"));
            col.addView(body("• يعرض التطبيق إعلاناً عند فتحه من أيقونته، وإعلاناً عند الضغط على «شاهد هسه»، عبر Google AdMob.\n"
                    + "• يعمل AdMob عبر مكتبة من Google داخل التطبيق: تجمع Google معرّف الإعلانات في جهازك ومعلومات الجهاز وعنوان IP "
                    + "ومرات ظهور الإعلانات والنقرات، لعرض الإعلانات وقياسها ومنع الاحتيال، وفق سياسة خصوصية Google.\n"
                    + "• في دول الاتحاد الأوروبي والمملكة المتحدة تُطلب موافقتك أولاً عبر نافذة Google، ويمكنك تغييرها لاحقاً من الزر أدناه.\n"
                    + "• يمكنك إعادة تعيين معرّف الإعلانات أو إلغاء تخصيص الإعلانات من إعدادات أندرويد ← Google ← الإعلانات."));
            if (AdMobAds.privacyOptionsRequired(this)) {
                TextView opts = Ui.button(this, "خيارات الخصوصية للإعلانات", 0x33FFFFFF, 0xFFFFFFFF);
                opts.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { AdMobAds.showPrivacyOptions(PrivacyActivity.this); }
                });
                col.addView(opts, lp(8, 0));
            }
        }

        if (AppConfig.OWN_ADS_ENABLED) buildOwnAdsSection(col);

        col.addView(head("البيانات المحفوظة على جهازك فقط"));
        col.addView(body("قائمة «متابعة المشاهدة» (العنوان والموضع، ورابط الفيديو فقط إن لم يتطلب ترويسات) وتفضيلات الإعلانات."));
        TextView clear0 = Ui.button(this, "حذف قائمة المتابعة", 0x33FFFFFF, 0xFFFFFFFF);
        clear0.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                new AlertDialog.Builder(PrivacyActivity.this)
                        .setMessage("حذف كل عناصر «متابعة المشاهدة»؟")
                        .setPositiveButton("حذف", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                ResumeStore.clearAll(PrivacyActivity.this);
                                Ui.toast(PrivacyActivity.this, "تم الحذف.");
                            }
                        })
                        .setNegativeButton("إلغاء", null)
                        .show();
            }
        });
        col.addView(clear0, lp(8, 0));

        col.addView(head("سياسة الخصوصية"));
        if (AppConfig.PRIVACY_POLICY_URL.length() > 0) {
            TextView link0 = Ui.button(this, "فتح سياسة الخصوصية", 0xFFFFB300, 0xFF0B0F14);
            link0.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(AppConfig.PRIVACY_POLICY_URL)));
                    } catch (Throwable t) {
                        Ui.toast(PrivacyActivity.this, "تعذّر فتح الرابط.");
                    }
                }
            });
            col.addView(link0, lp(4, 0));
        } else {
            col.addView(body("رابط سياسة الخصوصية غير مضبوط في هذه النسخة (AppConfig.PRIVACY_POLICY_URL). يجب ضبطه قبل النشر."));
        }
        if (AppConfig.SUPPORT_EMAIL.length() > 0) col.addView(body("للتواصل: " + AppConfig.SUPPORT_EMAIL));

        setContentView(scroll);
    }

    /** Only used when AppConfig.OWN_ADS_ENABLED is true (the in-house ad system of the Cigram ads worker). */
    private void buildOwnAdsSection(LinearLayout col) {
        col.addView(head("الإعلانات"));
        col.addView(body("• تأتي الإعلانات من خوادم سيكرام (وليس من شبكة إعلانات خارجية) وتحمل دائماً وسم «إعلان».\n"
                + "• عند طلب إعلان يُرسَل إلى خادم سيكرام: لغة الجهاز ورقم إصدار التطبيق (إضافةً إلى عنوان IP الذي يراه أي خادم).\n"
                + "• إن سمحت بالإحصاءات: يُرسَل أيضاً معرّف عشوائي خاص بهذا التثبيت مع أحداث «ظهور» و«نقر» لكل إعلان، لاحتساب الحملات ومنع التكرار المفرط. المعرّف لا يرتبط بحسابك.\n"
                + "• إن رفضت: لا يُرسَل أي معرّف ولا أي حدث، وتبقى الإعلانات تظهر بلا إحصاءات."));

        stats = new Switch(this);
        stats.setText("السماح بإحصاءات الإعلانات (معرّف عشوائي للتثبيت)");
        stats.setTextColor(0xFFFFFFFF);
        stats.setMinHeight(Ui.dp(this, 56));
        stats.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!binding) Privacy.set(PrivacyActivity.this, checked);
            }
        });
        col.addView(stats);

        TextView reset = Ui.button(this, "إعادة تعيين المعرّف العشوائي", 0x33FFFFFF, 0xFFFFFFFF);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                AdsClient.forgetTracking(PrivacyActivity.this);
                Ui.toast(PrivacyActivity.this, "تم حذف المعرّف. سيُنشأ معرّف جديد عند الحاجة إن كانت الإحصاءات مسموحة.");
            }
        });
        col.addView(reset, lp(0, 8));

    }

    @Override
    protected void onResume() {
        super.onResume();
        binding = true;
        if (stats != null) stats.setChecked(Privacy.statsAllowed(this));
        binding = false;
    }

    private TextView head(String s) {
        TextView t = Ui.text(this, s, 16f, 0xFFFFB300, true);
        t.setPadding(0, Ui.dp(this, 22), 0, Ui.dp(this, 6));
        return t;
    }

    private TextView body(String s) {
        TextView t = Ui.text(this, s, 14f, 0xCCFFFFFF, false);
        t.setLineSpacing(0f, 1.2f);
        return t;
    }

    private LinearLayout.LayoutParams lp(int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(this, top);
        p.bottomMargin = Ui.dp(this, bottom);
        return p;
    }
}
