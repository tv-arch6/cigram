package com.Cigram.vid;

import android.app.Activity;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * «حول Cigram» (2026): a real page (not a dialog, not a web site).
 *
 * Content rule: only what the project itself shows (its features, its own services) and what the owner supplied (the
 * founder's name and portrait). No company registration, founding date, award or team member is stated, because none
 * was supplied.
 *
 * Founder portrait: square with 20 % rounded corners, tilted slightly; behind it a translucent black square of the
 * same shape tilted the other way. The picture keeps its natural colours (no filter).
 */
public class CigramAboutActivity extends Activity {

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        CigramPageKit kit = CigramPageKit.attach(this, "حول التطبيق");
        CigramPalette pal = kit.pal;

        // ---- hero: logo, name, one line
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView logo = new ImageView(this);
        int logoId = getResources().getIdentifier("cigram_logo", "drawable", getPackageName());
        if (logoId != 0) logo.setImageResource(logoId);
        logo.setContentDescription("شعار Cigram");
        hero.addView(logo, new LinearLayout.LayoutParams(kit.dp(88), kit.dp(88)));
        TextView name = CigramUI.label(this, "Cigram", 28f, pal.text, true);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.topMargin = kit.dp(12);
        hero.addView(name, nl);
        TextView line = CigramUI.label(this, "أفلام · مسلسلات · قنوات مباشرة", 14f, pal.text3, false);
        line.setGravity(Gravity.CENTER);
        hero.addView(line, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2);
        hl.topMargin = kit.dp(18);
        kit.column.addView(hero, hl);

        kit.heading("ما هو Cigram؟");
        kit.paragraph("Cigram منصة عربية للمشاهدة تجمع الأفلام والمسلسلات والقنوات المباشرة في تطبيق واحد، "
                + "بواجهة عربية من اليمين إلى اليسار تعمل على الجوال والتابلت والتلفاز.");

        kit.heading("لماذا أُنشئ؟");
        kit.paragraph("أُنشئ Cigram ليكون مكاناً واحداً سهلاً للمشاهدة باللغة العربية: تجد فيه ما تريد مشاهدته بسرعة، "
                + "وتتابع مسلسلاتك من حيث توقفت، وتصلك الحلقات الجديدة دون أن تبحث عنها.");

        kit.heading("كيف بُني؟");
        kit.paragraph("بُني تطبيق Cigram لنظام Android بلغة Java، مع واجهة خاصة بالتلفاز تعمل بجهاز التحكم عن بُعد. "
                + "ويعمل التطبيق مع خوادم Cigram السحابية التي تقدّم الكتالوج والحسابات والإشعارات والإعلانات، "
                + "ويُدار المحتوى من تطبيق إدارة خاص بفريق Cigram.");

        kit.heading("هدفنا");
        kit.paragraph("أن تكون المشاهدة بسيطة وسريعة ومريحة للعين، على أي شاشة لديك.");

        kit.heading("خدماتنا");
        LinearLayout services = kit.card();
        kit.item(services, AdsIcons.PLAY, "الأفلام والمسلسلات", "كتالوج يُحدَّث باستمرار مع متابعة المشاهدة واستئنافها.", false);
        kit.item(services, AdsIcons.TV, "القنوات المباشرة", "بث مباشر منظّم حسب الأقسام مع المفضلة.", false);
        kit.item(services, AdsIcons.CAST, "Cigram TV", "واجهة كاملة للتلفاز وأجهزة Android TV.", false);
        kit.item(services, AdsIcons.USERS, "المشاهدة معاً", "شاهد مع أصدقائك في غرفة واحدة.", false);
        kit.item(services, AdsIcons.CROWN, "Cigram Plus", "اشتراك بمزايا إضافية يُفعَّل بكود.", false);
        kit.item(services, AdsIcons.BELL, "إشعارات الحلقات الجديدة", "يصلك تنبيه عند إضافة حلقة أو عنوان جديد.", false);
        kit.item(services, AdsIcons.HEADSET, "الدعم وطلب العناوين", "اطلب فيلماً أو مسلسلاً أو قناة، وراسل فريق Cigram.", true);

        kit.heading("رؤيتنا");
        kit.paragraph("أن يصبح Cigram الوجهة العربية الأولى للمشاهدة: تجربة متقنة ومستقرة تحترم وقت المشاهد.");

        // ---- founder
        kit.heading("المؤسس");
        kit.column.addView(founderCard(kit), new LinearLayout.LayoutParams(-1, -2));

        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) { }
        kit.footnote("Cigram" + (version == null || version.length() == 0 ? "" : " · الإصدار " + version));
    }

    private View founderCard(CigramPageKit kit) {
        CigramPalette pal = kit.pal;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, kit.dp(18), 0, kit.dp(8));

        final int side = kit.dp(168);
        final float radius = side * 0.20f;   // 20 % rounded corners
        FrameLayout stage = new FrameLayout(this);
        stage.setClipChildren(false);
        stage.setClipToPadding(false);
        int pad = kit.dp(22);
        stage.setPadding(pad, pad, pad, pad);

        // the translucent black square behind the portrait, tilted the opposite way
        View back = new View(this);
        GradientDrawable shadowSquare = new GradientDrawable();
        shadowSquare.setColor(pal.light ? 0x33000000 : 0x8C000000);
        shadowSquare.setCornerRadius(radius);
        shadowSquare.setStroke(Math.max(1, kit.dp(1)), pal.light ? 0x14000000 : 0x1FFFFFFF);
        back.setBackground(shadowSquare);
        back.setRotation(-6f);
        stage.addView(back, new FrameLayout.LayoutParams(side, side, Gravity.CENTER));

        ImageView photo = new ImageView(this);
        int id = getResources().getIdentifier("cigram_founder", "drawable", getPackageName());
        if (id != 0) photo.setImageResource(id);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);   // natural colours: no tint, no filter
        photo.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        photo.setClipToOutline(true);
        photo.setRotation(4f);
        photo.setElevation(kit.dp(6));
        photo.setContentDescription("صورة المؤسس حسن سعيد");
        stage.addView(photo, new FrameLayout.LayoutParams(side, side, Gravity.CENTER));
        box.addView(stage, new LinearLayout.LayoutParams(-2, -2));

        TextView name = CigramUI.label(this, "حسن سعيد", 22f, pal.text, true);
        name.setGravity(Gravity.CENTER);
        name.setTextDirection(View.TEXT_DIRECTION_RTL);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
        nl.topMargin = kit.dp(10);
        box.addView(name, nl);
        TextView role = CigramUI.label(this, "مؤسس Cigram", 14f, pal.text3, false);
        role.setGravity(Gravity.CENTER);
        role.setTextDirection(View.TEXT_DIRECTION_RTL);
        box.addView(role, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }
}
