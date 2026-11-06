# Cigram Admin v2.0 — new home + new design

## Structure
- **DxHomeActivity** (launcher): live numbers (active / watching / today's users), a live chart, 8 section tiles, and the content overview (numbers, things that need attention, bar chart of titles added in 14 days, donut of the content split).
- **DxSectionActivity** (one class, one page per section): المحتوى · القنوات · الرئيسية والواجهة · الإشعارات والطلبات · الإعلانات · الإحصائيات · التحديث والصيانة. Each page = numbers/charts of that section + big rows for what you can do. "اشتراكات Plus" opens its own page directly.
- `DxUi` = the single design kit (cards, KPI, tiles, rows, BarChart / Donut / LineChart). `DxStats` = chart numbers (unit-tested). `DxPanels` = the content overview block.

## Design
One palette in `CgCfg` (ink-blue night, one indigo accent), flat rounded cards: `CgUi.bg()` rounds every corner and drops neutral borders; `NxUi` gradients / dashed borders / tinted borders are flat now. Every older screen inherits it.

## Removed (replaced by the home + sections)
MainActivity (Sketchware home), NxHome, NxClassicActivity, CxHomeActivity, CgHubActivity, CgDash, OpsHubActivity, AdHubActivity, AdHooks, CgHooks, AddMovieActivity, AddchannelActivity, CarouselActivity and their layouts (main / add_movie / addchannel / carousel).
"Add movie / series / channel" now has ONE entry each (the Nx wizards); the classic Cx screens stay reachable from inside them.

## Kept as leaf pages (restyled by the shared design layer)
All add / edit / upload / ads / ops / update screens.

## v2.1 — أكثر ثباتاً في جلب البيانات، وخط جديد

**جلب البيانات (المشكلة الأساسية):**
- `CgHttp.apiGet` يعيد المحاولة تلقائياً (حتى مرتين، بتأخير قصير) عند انقطاع لحظي، مهلة اتصال، أو
  رد 502/503/504/408 من الـ Worker — قبل أن يرى المستخدم أي خطأ. معظم حالات "تعذر تحميل البيانات"
  كانت طلباً واحداً فاشلاً على اتصال غير مستقر، لا عطلاً حقيقياً.
- `CgData` يحفظ آخر لقطة ناجحة من `/movies` + `/admin/meta` + `/admin/sections` على الجهاز
  (`SharedPreferences`). إن فشل الوصول للخادم تماماً بعد المحاولات، تُستخدم هذه النسخة المحفوظة
  بدل شاشة خطأ فارغة، مع شريط تنبيه واضح بأنها بيانات محفوظة ووقت آخر نجاح.
- الرئيسية (`DxHomeActivity`) تعرض الآن "آخر تحديث" فوق نظرة المحتوى، وتُحدّث البيانات تلقائياً
  كل دقيقتين في الخلفية دون تدخل أو شاشة تحميل — فقط إن كان هناك تغيّر.

**إضافة جديدة:**
- زر "بحث" في شريط الرئيسية يفتح بحث المحتوى الكامل مباشرة (بدل الدخول لقسم «المحتوى» أولاً).

**الخط:**
- الخط الأساسي لكل واجهات الإدارة أصبح **Cairo** (Regular + Bold الحقيقي، لا bold مزيّف) بدل
  `airo.ttf` القديم. التبديل مركزي في `CgUi.font()` / `CgUi.applyFont()`، فكل شاشة تستفيد تلقائياً.
- خطوط Amiri، Markazi Text، و Scheherazade New مرفقة أيضاً في `assets/fonts/` لاستخدامها لاحقاً في
  أي عرض نصي عربي طويل (أوصاف، مقالات) لو احتاج التطبيق ذلك مستقبلاً.

## v3.0 — تحديث كامل للشكل (Aurora Night)

تحديث شامل للمظهر، يسري على كل الشاشات تلقائياً لأنه يعدّل طبقة التصميم المركزية فقط
(بدون تغيير أي أسماء دوال، فلا شيء يتكسّر في الشاشات الورقية).

**اللوحة (`CgCfg`):** قاعدة زرقاء-سوداء أعمق، سلّم أسطح من أربع درجات
(`PAGE / CARD / CARD2 / CARD3`)، لون أساسي بنفسجي-أزرق متدرّج (`ACCENT_G1 → ACCENT_G2`)،
ولون ثانٍ سماوي (`CYAN`). الدلالات (جيد/تحذير/خطأ) أنعم وأوضح.

**العمق:** عادت الظلال الناعمة. `CgUi.elevate()` يرفع كل بطاقة/زر أساسي ظلّاً خفيفاً مدوّراً،
و`CgUi.unclip()` في `scrollBody` و`DxUi.row` يمنع قصّ الظلال عند الحواف.

**التدرّجات:** رجعت لكن على الأكسنت فقط — الأزرار الأساسية، الشرائح المختارة، فقاعات
أيقونات الأقسام، أعمدة الرسوم (تدرّج رأسي)، وتعبئة الخط البياني. `NxUi.gradient()` صار تدرّجاً
حقيقياً من جديد، فكل الشاشات التي كانت تطلب تدرّجاً (أزرار Nx) ظهر تدرّجها أخيراً.

**الرئيسية (`DxHomeActivity`):** رأس (hero) بخلفية متدرّجة فيه اسم Cigram، الترحيب، التاريخ،
وحبّة حالة الاتصال المباشر. أرقام حيّة ببطاقات ملوّنة بوهج خفيف، ثم شبكة الأقسام والنظرة على المحتوى.

**الأقسام (`DxSectionActivity`):** لكل قسم رأس ملوّن بأيقونته المتدرّجة واسمه وسطر وصف، فصار كل
قسم له هويّته البصرية. الأرقام والرسوم والصفوف تحته ترث الشكل الجديد.

## v3.1 — اللوحة المباشرة رجعت (DxLive)

الرئيسية في v2/v3 كانت تعرض 3 أرقام وخطاً صغيراً فقط، وضاعت باقي اللوحة المباشرة. الآن `DxLive` (جديد)
يرسمها كاملة من `NxLive` (يسأل الـ Worker كل ثانية):

**أعلى الصفحة (يتحرك كل ثانية):** نشطون الآن · يشاهدون الآن · المسجّلون، كل رقم يعدّ صعوداً/نزولاً بنعومة
مع ▲▼ مقارنة بدقيقة مضت، وشريط حالة يقول «يتحدّث كل ثانية / آخر رد منذ Xث»، ورسم بياني ينزلق نقطة كل ثانية
(الضغط على «نشطون / يشاهدون» يخفي أو يُظهر الخط).

**تحت الأقسام:** ماذا يُشاهَد الآن (أفلام / مسلسلات / قنوات + أكثر العناوين مشاهدةً)، الحسابات المسجّلة
(الإجمالي، جدد اليوم/24 ساعة/7 أيام، نشطون، مسجّلو الدخول، نمو 14 يوماً)، أرقام اليوم (مستخدمون، جلسات،
تشغيلات، إعلانات، نسبة التعبئة)، وآخر ساعة بالدقيقة.

**الأعطال صارت ظاهرة:** إن كان الـ Worker بلا `/admin/live` أو انقطع الاتصال، تظهر لافتة تقول السبب بدل شرطات صامتة.

**ما يحتاجه الـ Worker:** `/admin/live` يرسل `active_now` و`watching_now` و`watching_top` (عناصر فيها اسم ونوع وعدد)،
واختيارياً `watching_by_type: {movie, series, channel}` لتقسيم دقيق. بدونه يُحسب التقسيم من `watching_top`.

## v3.2 — audit & stability fixes
- Manifest: added FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, WAKE_LOCK, ACCESS_WIFI_STATE, RECORD_AUDIO (upload service / notifications / voice notes no longer silently denied on Android 13/14); allowBackup=false (the admin token is no longer exposed to backups); removed the dangling DebugActivity entry.
- SketchApplication: global uncaught-exception handler — a failure on a worker thread is logged instead of killing the app.
- CgHttp.bg(): every background task is wrapped in try/catch(Throwable).
- RequestNetworkController: removed trust-all TLS / always-true hostname verifier (admin token could be intercepted).
- SeriesImportActivity: uses CgCfg.API / CgCfg.TOKEN (single source of truth, no duplicated secret).
- DxHomeActivity: auto-refresh no longer keeps re-posting while the screen is paused; header badge shows "تعذر الاتصال" when the live feed has never connected.
