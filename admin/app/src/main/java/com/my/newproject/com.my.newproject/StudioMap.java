package com.my.newproject;

import java.util.ArrayList;
import java.util.List;

/**
 * Cigram Studio 4 — the map of EVERYTHING the admin can do, in one place.
 *
 * <pre>
 *   Room   a themed area of the app (library, subtitles, app face, live, audience, system)
 *   Group  a titled block of tools inside a room
 *   Tool   one thing the admin can open: id, words people search with, the screen (or action) behind it
 * </pre>
 * The home, the room pages and the search all read this list, so a tool added here shows up everywhere at once.
 * {@code StudioMapTest} reads AndroidManifest.xml and fails when a screen is declared there but is neither a tool,
 * nor reached through an action, nor a known leaf page: nothing in the admin can silently go missing from the Studio.
 *
 * Pure data (no Android calls): the lists are checked on the JVM.
 */
final class StudioMap {

    private StudioMap() { }

    // ------------------------------------------------------------------ rooms

    static final int LIBRARY = 0;
    static final int SUBS = 1;
    static final int FACE = 2;
    static final int LIVE = 3;
    static final int AUDIENCE = 4;
    static final int SYSTEM = 5;

    static final class Room {
        final int index;
        final String title;
        final String sub;
        final int icon;
        final String[] groups;

        Room(int index, String title, String sub, int icon, String... groups) {
            this.index = index;
            this.title = title;
            this.sub = sub;
            this.icon = icon;
            this.groups = groups;
        }
    }

    static final Room[] ROOMS = {
            new Room(LIBRARY, "المكتبة", "أضف الأفلام والمسلسلات والحلقات، وحرّر ونظّم كل ما في الكتالوج", CgIcons.FILM, "إضافة", "إدارة المكتبة"),
            new Room(SUBS, "الترجمة", "العناوين بلا ترجمة، رفع الملفات وربطها بالحلقات", CgIcons.FILE, "الترجمة"),
            new Room(FACE, "واجهة التطبيق", "ما يراه المستخدم أولاً: الكاروسيل والأقسام والترتيب والروابط", CgIcons.IMAGE, "الواجهة الرئيسية", "الروابط"),
            new Room(LIVE, "البث", "من يشاهد الآن، وصحة القنوات وبلاغات المستخدمين عنها", CgIcons.SIGNAL, "القنوات", "المراقبة الحية"),
            new Room(AUDIENCE, "الجمهور والأرباح", "الدعم والإشعارات والطلبات والاشتراكات والإعلانات", CgIcons.CHAT, "المستخدمون", "الإعلانات"),
            new Room(SYSTEM, "النظام", "التحديث والصيانة والمراقبة والخصوصية والأمان", CgIcons.GEAR, "التطبيق", "المراقبة", "الخصوصية والأمان"),
    };

    // ------------------------------------------------------------------ tools

    /** What a tool does when tapped. A_OPEN starts {@link Tool#target}; the rest are calls that need an argument. */
    static final int A_OPEN = 0;
    static final int A_ADD_EPISODE = 1;
    static final int A_ADD_SEASON = 2;
    static final int A_ALL_CONTENT = 3;
    static final int A_DRAFTS = 4;
    static final int A_CAROUSEL = 5;
    static final int A_CAROUSEL_ADD = 6;
    static final int A_PRIVACY = 7;
    static final int A_TOKEN = 8;

    static final class Tool {
        final String id;
        final int room;
        final String group;
        final String title;
        final String sub;
        final String keys;      // extra words (Arabic + English) the search also reads
        final int icon;
        final Class<?> target;  // for A_OPEN
        final int action;
        final String badge;     // "جديد" or ""

        Tool(String id, int room, String group, String title, String sub, String keys, int icon, Class<?> target, int action, String badge) {
            this.id = id;
            this.room = room;
            this.group = group;
            this.title = title;
            this.sub = sub;
            this.keys = keys;
            this.icon = icon;
            this.target = target;
            this.action = action;
            this.badge = badge;
        }
    }

    private static Tool open(String id, int room, String group, String title, String sub, String keys, int icon, Class<?> target, String badge) {
        return new Tool(id, room, group, title, sub, keys, icon, target, A_OPEN, badge);
    }

    private static Tool act(String id, int room, String group, String title, String sub, String keys, int icon, int action, String badge) {
        return new Tool(id, room, group, title, sub, keys, icon, null, action, badge);
    }

    static final Tool[] TOOLS = {
            // ---- المكتبة · إضافة
            open("whats_new", LIBRARY, "إضافة", "ما الجديد؟", "اكتشف الجديد وأضفه مع الفيديو والترجمة", "tmdb discover trending اكتشاف مقترحات", CgIcons.STAR, WnActivity.class, "جديد"),
            open("add_movie", LIBRARY, "إضافة", "إضافة فيلم", "ابحث بالاسم وأضفه برابطه أو من جوالك", "movie film فيلم جديد", CgIcons.FILM, NxMovieActivity.class, ""),
            open("add_series", LIBRARY, "إضافة", "إضافة مسلسل", "مسلسل جديد بمواسمه وحلقاته", "series tv مسلسل انمي", CgIcons.TV, NxSeriesActivity.class, ""),
            act("add_episode", LIBRARY, "إضافة", "إضافة حلقة", "حلقة جديدة لمسلسل موجود", "episode حلقه", CgIcons.PLUS, A_ADD_EPISODE, ""),
            act("add_season", LIBRARY, "إضافة", "إضافة موسم كامل", "موسم دفعة واحدة بالروابط", "season موسم", CgIcons.FOLDER, A_ADD_SEASON, ""),
            open("bulk", LIBRARY, "إضافة", "معالج الرفع الجماعي", "اختر الملفات: يطابقها، يفحصها ثم يسلّمها للرفع", "bulk upload رفع جماعي ملفات", CgIcons.UPLOAD, BulkWizardActivity.class, "جديد"),
            open("quick_add", LIBRARY, "إضافة", "إضافة سريعة", "لصق رابط وإنهاء بخطوة واحدة", "quick paste link لصق رابط نشر", CgIcons.SEND, QuickAddActivity.class, ""),
            // ---- المكتبة · إدارة
            act("content", LIBRARY, "إدارة المكتبة", "كل المحتوى", "بحث وفلترة وتحرير وحذف", "library catalog كتالوج تعديل حذف فلتر", CgIcons.SEARCH, A_ALL_CONTENT, ""),
            act("drafts", LIBRARY, "إدارة المكتبة", "المسودات", "ما بدأت به ولم تكمله", "drafts مسوده", CgIcons.DRAFT, A_DRAFTS, ""),
            open("uploads", LIBRARY, "إدارة المكتبة", "الرفع والتحميلات", "متابعة الملفات الجارية والفاشلة", "upload queue تحميل رفع", CgIcons.CLIP, CgUploadsActivity.class, ""),
            open("merge", LIBRARY, "إدارة المكتبة", "دمج العناوين", "ضم المكرر في عنوان واحد", "merge duplicates تكرار مكرر دمج", CgIcons.COPY, CgMergeActivity.class, ""),

            // ---- الترجمة
            open("sub_center", SUBS, "الترجمة", "مركز الترجمة العربية", "العناوين بلا ترجمة: بحث جماعي، مراجعة، اعتماد ونشر", "subtitles srt ترجمه بدون ترجمه", CgIcons.SHIELD, SubCenterActivity.class, ""),
            open("sub_upload", SUBS, "الترجمة", "رفع ترجمة وربطها", "ملف لعنوان واحد", "subtitle upload srt vtt ملف ترجمه", CgIcons.UPLOAD, CxSubsActivity.class, ""),
            open("sub_season", SUBS, "الترجمة", "ترجمات موسم كامل", "حزمة ZIP أو عدة ملفات تُطابق بالحلقات", "zip season subtitles موسم حلقات", CgIcons.FOLDER, CgSeasonSubsActivity.class, ""),

            // ---- واجهة التطبيق
            act("carousel", FACE, "الواجهة الرئيسية", "الكاروسيل", "الشرائح المتحركة أعلى الرئيسية", "carousel banner slider شرائح بانر", CgIcons.IMAGE, A_CAROUSEL, ""),
            act("carousel_add", FACE, "الواجهة الرئيسية", "إضافة شريحة", "شريحة جديدة للكاروسيل", "carousel add slide شريحه جديده", CgIcons.PLUS, A_CAROUSEL_ADD, ""),
            open("sections", FACE, "الواجهة الرئيسية", "الأقسام", "أسماء الأقسام وما يظهر منها", "sections اقسام تصنيف", CgIcons.FOLDER, CgSectionsActivity.class, ""),
            open("home_order", FACE, "الواجهة الرئيسية", "ترتيب الرئيسية", "رتّب الأقسام كما تريد", "order sort ترتيب الواجهه", CgIcons.EDIT, CgHomeOrderActivity.class, ""),
            open("featured", FACE, "الواجهة الرئيسية", "المحتوى المميز", "ما يظهر في الواجهة أولاً", "featured pinned مميز", CgIcons.STAR, CgFeaturedActivity.class, ""),
            open("links", FACE, "الروابط", "الروابط الخارجية", "رابط التطبيق أو قناة تليكرام بصفحة الحساب", "telegram link تليجرام تيليكرام رابط", CgIcons.LINK, ExternalLinksActivity.class, ""),

            // ---- البث
            open("live_health", LIVE, "القنوات", "Cigram Live Health", "فحص كل القنوات الآن: تعمل · بطيئة · متوقفة، مع السبب", "health channels فحص القنوات صحه البث", CgIcons.SIGNAL, LiveHealthActivity.class, "جديد"),
            open("add_channel", LIVE, "القنوات", "إضافة قناة", "رابط البث والشعار والباقة", "channel add قناه جديده", CgIcons.PLUS, NxChannelActivity.class, ""),
            open("channels", LIVE, "القنوات", "إدارة القنوات", "ترتيب وإخفاء وفحص الروابط", "channels manage قنوات ترتيب اخفاء", CgIcons.EDIT, CgChannelsActivity.class, ""),
            open("channel_reports", LIVE, "القنوات", "قنوات تحتاج رابطاً", "بلاغات «بلّغ المدير» · استبدل برابط", "reports بلاغات قناه متوقفه استبدال", CgIcons.FLAG, CgChannelReportsActivity.class, ""),
            open("pulse", LIVE, "المراقبة الحية", "النبض والأرباح", "رسم حي 24/7 · توقّعات AdMob", "pulse earnings admob ارباح نبض", CgIcons.CHART, CgPulseActivity.class, ""),

            // ---- الجمهور والأرباح
            open("inbox", AUDIENCE, "المستخدمون", "الدعم والتواصل", "الدعم الخاص ورسائل المستخدمين والمعلنين", "support chat inbox دعم محادثه رسائل معلن", CgIcons.CHAT, AdInboxActivity.class, ""),
            open("notify", AUDIENCE, "المستخدمون", "إرسال إشعار", "إشعار فوري لكل المستخدمين", "push notification اشعار تنبيه", CgIcons.BELL, NxNotifyActivity.class, ""),
            open("announcements", AUDIENCE, "المستخدمون", "الرسائل العامة", "رسائل تظهر داخل التطبيق بجدولة", "announcements اعلان رساله عامه جدوله", CgIcons.MEGAPHONE, CgAnnouncementsActivity.class, ""),
            open("requests", AUDIENCE, "المستخدمون", "طلبات المستخدمين", "أفلام ومسلسلات وقنوات طلبها المشاهدون", "requests طلبات المشاهدين تليجرام", CgIcons.USERS, NxRequestsActivity.class, ""),
            open("plus", AUDIENCE, "المستخدمون", "اشتراكات Plus", "أكواد التفعيل والمشتركون", "plus codes subscription اشتراك اكواد تفعيل", CgIcons.STAR, PlusCodesActivity.class, ""),
            open("chat_settings", AUDIENCE, "الإعلانات", "إعدادات المحادثة", "سياسة التواصل وساعات العمل", "chat settings ساعات العمل سياسه", CgIcons.GEAR, AdSettingsActivity.class, ""),
            open("admob", AUDIENCE, "الإعلانات", "AdMob: تشغيل وإيقاف", "المفتاح الرئيسي ومواضع إعلانات AdMob", "ads admob اعلانات تشغيل ايقاف مواضع", CgIcons.MEGAPHONE, OpsAdsActivity.class, ""),
            open("admob_check", AUDIENCE, "الإعلانات", "AdMob: الفحص", "نسبة التعبئة وأسباب الفشل", "ads check fill rate تعبئه فحص", CgIcons.CHART, OpsAdCheckActivity.class, ""),

            // ---- النظام
            open("update", SYSTEM, "التطبيق", "تحديث التطبيق", "نشر إصدار جديد وجدولته", "update release apk اصدار تحديث نشر", CgIcons.UPDATE, CgUpdateActivity.class, ""),
            open("maintenance", SYSTEM, "التطبيق", "وضع الصيانة", "إيقاف التطبيق مؤقتاً برسالة", "maintenance صيانه ايقاف", CgIcons.LOCK, CgMaintenanceActivity.class, ""),
            open("stats", SYSTEM, "المراقبة", "الإحصائيات التفصيلية", "رسوم اليوم والساعة والإصدارات", "stats analytics احصائيات ارقام رسوم", CgIcons.CHART, OpsStatsActivity.class, ""),
            open("errors", SYSTEM, "المراقبة", "أخطاء التطبيق", "انهيارات ومشاكل التشغيل مجمّعة", "errors crashes اخطاء انهيار مشاكل", CgIcons.WARNING, OpsErrorsActivity.class, ""),
            open("health", SYSTEM, "المراقبة", "صحة النظام", "الخوادم والملفات وزمن الاستجابة", "health server latency صحه الخوادم", CgIcons.SIGNAL, CgHealthActivity.class, ""),
            open("history", SYSTEM, "المراقبة", "سجل العمليات والنسخ", "تراجع واستعادة النسخ الاحتياطية", "history backup undo restore نسخ احتياطي سجل تراجع", CgIcons.HISTORY, CgHistoryActivity.class, ""),
            act("privacy", SYSTEM, "الخصوصية والأمان", "خصوصية ملفات الإدارة", "تشفير الرمز، قفل التطبيق، منع تصوير الشاشة، مسح الملفات المؤقتة", "privacy security vault خصوصيه امان قفل تشفير", CgIcons.SHIELD, A_PRIVACY, "جديد"),
            act("token", SYSTEM, "الخصوصية والأمان", "رمز الإدارة", "تغيير الرمز المحفوظ على هذا الجهاز", "token password admin رمز الادمن كلمه المرور", CgIcons.LOCK, A_TOKEN, ""),
    };

    // ------------------------------------------------------------------ coverage (checked against AndroidManifest.xml)

    /** Screens a tool reaches through an action (CxNav / DxPanels / CgVault) instead of by its own class. */
    static final Class<?>[] VIA_ACTION = {
            CgContentActivity.class, CxDraftsActivity.class, CxCarouselActivity.class, CxCarouselEditActivity.class,
            CxEpisodeActivity.class, CxSeasonActivity.class,
    };

    /** Pages opened from inside another screen (an item, a chat, a wizard step): never an entry point of their own. */
    static final Class<?>[] LEAF = {
            AdChatActivity.class, CgItemActivity.class, CgSeriesActivity.class, CgPlayerActivity.class, CigramMediaViewer.class,
            CgMediaUploadActivity.class, CxChannelActivity.class, CxMovieActivity.class, CxSeasonUploadActivity.class,
            CxSeriesAddActivity.class, CxSuccessActivity.class, SeriesImportActivity.class, SubJobActivity.class,
            WnBulkActivity.class, WnCollectionActivity.class, WnDetailActivity.class, WnHistoryActivity.class,
    };

    /** The Studio's own screens, and the previous home's section page (kept in the manifest, no longer linked). */
    static final Class<?>[] SHELL = {
            DxHomeActivity.class, StudioRoomActivity.class, StudioSearchActivity.class, DxSectionActivity.class,
    };

    // ------------------------------------------------------------------ lookups

    static Tool byId(String id) {
        if (id == null) return null;
        for (int i = 0; i < TOOLS.length; i++) if (TOOLS[i].id.equals(id)) return TOOLS[i];
        return null;
    }

    static List<Tool> inRoom(int room) {
        ArrayList<Tool> out = new ArrayList<Tool>();
        for (int i = 0; i < TOOLS.length; i++) if (TOOLS[i].room == room) out.add(TOOLS[i]);
        return out;
    }

    static List<Tool> inGroup(int room, String group) {
        ArrayList<Tool> out = new ArrayList<Tool>();
        for (int i = 0; i < TOOLS.length; i++) if (TOOLS[i].room == room && TOOLS[i].group.equals(group)) out.add(TOOLS[i]);
        return out;
    }
}
