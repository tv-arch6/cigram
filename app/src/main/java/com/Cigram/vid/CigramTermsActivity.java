package com.Cigram.vid;

import android.app.Activity;
import android.os.Bundle;

/**
 * «الشروط والأحكام» (2026): shown from the registration form (the consent link), the account page and Settings.
 *
 * The text describes how the app actually works (accounts, Plus codes, ads, chat uploads, requests). It is a plain
 * operating text written for the app; it has NOT been reviewed by a lawyer and does not claim compliance with any
 * specific law. Change {@link CigramTerms#VERSION} whenever its substance changes.
 */
public class CigramTermsActivity extends Activity {

    private static final String[][] SECTIONS = {
            {"قبول الشروط",
                    "باستخدامك تطبيق Cigram أو إنشاء حساب فيه فإنك توافق على هذه الشروط. إن لم توافق عليها فلا تُنشئ حساباً ولا تستخدم الخدمة."},
            {"الحساب",
                    "أنت مسؤول عن صحة بيانات حسابك وعن سرية كلمة المرور وعن كل ما يجري من خلال حسابك. "
                            + "أخبرنا فوراً إن اشتبهت في استخدام حسابك دون إذنك. يمكنك حذف حسابك من الإعدادات."},
            {"المحتوى والاستخدام",
                    "المحتوى المعروض في Cigram للمشاهدة الشخصية غير التجارية. لا يجوز نسخه أو إعادة بثه أو بيعه، "
                            + "ولا محاولة تعطيل الخدمة أو الوصول إلى أجزائها بطرق غير مسموح بها."},
            {"Cigram Plus",
                    "يُفعَّل اشتراك Cigram Plus بكود تفعيل لمدة محددة تظهر في حسابك، وينتهي تلقائياً بنهاية مدته دون تجديد آلي. "
                            + "تحدد خوادم Cigram صلاحية الكود وأهليته، والكود المستخدم لا يُعاد استخدامه."},
            {"الإعلانات",
                    "قد يعرض التطبيق إعلانات من Google AdMob ومن معلنين يتعاملون مع Cigram مباشرة، وفق إعدادات الموافقة "
                            + "المعمول بها في بلدك. يحق للمعلن طلب حملة عبر «أريد إعلاناً»، وتخضع كل حملة لمراجعة فريق Cigram قبل نشرها."},
            {"المحادثات والملفات",
                    "الرسائل والصور والفيديوهات والرسائل الصوتية التي ترسلها إلى فريق Cigram تُحفظ على خوادمنا للرد عليك ومتابعة طلبك. "
                            + "لا ترسل محتوى مخالفاً أو لا تملك حق نشره. الحد الأقصى لحجم الفيديو المرفوع 1 غيغابايت."},
            {"الخصوصية",
                    "نجمع ما يلزم لتشغيل الخدمة فقط: بيانات الحساب، وسجل المشاهدة لاستكمال العرض، وإعدادات الإشعارات، "
                            + "وبيانات تقنية عن الأعطال والأداء. لا نبيع بياناتك الشخصية."},
            {"الأذونات",
                    "يطلب التطبيق الإذن فقط عند الحاجة إليه: الإشعارات لتنبيهك بالجديد، والميكروفون للرسائل الصوتية، "
                            + "واختيار الصور والفيديو عند إرفاقها. يمكنك تغيير ذلك من إعدادات جهازك في أي وقت."},
            {"توفر الخدمة",
                    "نعمل على أن تكون الخدمة متاحة ومستقرة، لكن قد يتوقف بعض المحتوى أو القنوات مؤقتاً أو يُزال لأسباب تقنية أو بطلب من أصحابه."},
            {"تعديل الشروط",
                    "قد نحدّث هذه الشروط. عند تغيير جوهري سيُطلب منك الاطلاع على النسخة الجديدة، ويظهر رقم الإصدار أسفل هذه الصفحة."},
            {"التواصل",
                    "لأي سؤال عن هذه الشروط راسلنا من «الدعم والتواصل» في صفحة الحساب أو على البريد " + CigramReport.SUPPORT_EMAIL + "."},
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        CigramPageKit kit = CigramPageKit.attach(this, "الشروط والأحكام");
        kit.paragraph("يرجى قراءة هذه الشروط بعناية قبل إنشاء حسابك واستخدام Cigram.");
        for (int i = 0; i < SECTIONS.length; i++) {
            kit.heading((i + 1) + ". " + SECTIONS[i][0]);
            kit.paragraph(SECTIONS[i][1]);
        }
        kit.footnote(CigramTerms.VERSION_LABEL);
    }
}
