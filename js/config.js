// إعدادات الموقع: غيّر القيم هنا فقط.
export const CONFIG = {
  // اسم المنصة (يظهر في الأعلى وعلى الشاشة الرئيسية بعد التثبيت)
  siteName: 'سيكرام',

  // رابط زر «تحميل التطبيق» في صفحة الافتتاح
  downloadUrl: 'https://cigram.wwq-mixtv.workers.dev/',

  // مصدر المحتوى: نفس الخادم الذي يقرأ منه تطبيق سيكرام.
  // 'cigram' = يقرأ GET /movies و GET /home-sections من apiBase ويحوّلها لشكل الموقع (js/cigram.js).
  // 'file'   = يقرأ catalogUrl بشكل data/catalog.json الموضح في README.
  source: 'cigram',
  apiBase: 'https://cigram-admin-api.wwq-mixtv.workers.dev',

  // Worker احتياطي لملفات الترجمة (اختياري): يُستخدم فقط إذا تعذّر تحميل ملف الترجمة من R2 مباشرة (CORS).
  // انشر الملف cigram-subs/worker.js (بجانب مجلد الموقع) باسم cigram-subs، أو اترك الرابط كما هو إن فعّلت CORS في R2. ضع '' لإيقافه.
  subtitleProxy: 'https://cigram-subs.wwq-mixtv.workers.dev',

  // الحسابات: نفس خادم حسابات تطبيق سيكرام، فالحساب واحد في التطبيق والموقع.
  authApi: 'https://cigram-auth-api.wwq-mixtv.workers.dev',

  // معرّف Google OAuth من نوع «Web application» (ينتهي بـ .apps.googleusercontent.com).
  // ضع نفس القيمة في متغير GOOGLE_CLIENT_ID في cigram-auth-api. اتركه '' لإخفاء زر Google.
  googleClientId: '',

  // Cigram Plus: الاشتراك بكود تفعيل. plusApi = Worker الاشتراكات (plus-worker/worker.js).
  plusApi: 'https://cigram-plus-api.wwq-mixtv.workers.dev',
  // واتساب استلام الكود (صيغة دولية بدون +)
  whatsapp: '9647854314651',
  // إظهار الأسعار وزر الاشتراك (الموقع ليس على Google Play). false = بطاقة بدون أسعار.
  plusSales: true,

  // بريد التواصل الذي يظهر في صفحة الشروط والخصوصية (اختياري)
  contactEmail: '',

  // القنوات: نفس قائمة القنوات التي يقرأها تطبيق سيكرام (صفحة «القنوات»).
  // يرجع { ok, categories: [{ name, channels: [{ name, url, image, available }] }] }.
  channelsApi: 'https://sigram-channels-api.wwq-mixtv.workers.dev/channels',

  // بروكسي البث (stream-proxy/worker.js، اسمه cigram-stream): يشغّل القنوات التي رابطها http أو بدون CORS مباشرة داخل الموقع (آيفون وحاسوب) بدون تحويل للتطبيق.
  // ضع '' لإيقافه (فتعود القنوات http للرسالة القديمة).
  streamProxy: 'https://cigram-stream.wwq-mixtv.workers.dev',

  // مكتبة mpegts.js لقنوات البث المباشر بصيغة .ts الخام (تُحمَّل فقط عند الحاجة)
  mpegtsUrl: 'https://cdnjs.cloudflare.com/ajax/libs/mpegts.js/1.7.3/mpegts.js',

  // مكتبة dash.js لروابط القنوات من نوع .mpd (تُحمَّل فقط عند تشغيل قناة من هذا النوع)
  dashUrl: 'https://cdnjs.cloudflare.com/ajax/libs/dashjs/4.7.4/dash.all.min.js',

  // يستخدم فقط عندما source = 'file'
  catalogUrl: 'data/catalog.json',

  // رابط مكتبة hls.js لتشغيل روابط m3u8 في المتصفحات التي لا تدعمها مباشرة (كل المتصفحات ما عدا Safari).
  // لتشغيلها من موقعك: ضع الملف في js/vendor/hls.min.js وغيّر الرابط إلى 'js/vendor/hls.min.js'.
  hlsUrl: 'https://cdnjs.cloudflare.com/ajax/libs/hls.js/1.5.17/hls.min.js',

  // مدة بقاء كل شريحة في البانر الرئيسي (بالثواني)
  heroSeconds: 8,
};
