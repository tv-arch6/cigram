# سيكرام — ملفات ونشر Cloudflare

يشرح هذا الملف كل ما يخص Cloudflare في المشروع: ما الذي يُرفع، وما الخدمات التي
يعتمد عليها الموقع، وأين تُغيَّر الروابط. مبني على `js/config.js` و`README.md`.

> **تنبيه صريح:** الموقع (هذا المجلد) جزء ثابت يُرفع على **Cloudflare Pages**.
> الخوادم الخلفية (**Workers**) مشاريع منفصلة، وأكوادها (`cigram-subs/worker.js`،
> `stream-proxy/worker.js`، `cigram-auth-api/worker.js`، `plus-worker/worker.js`)
> **ليست ضمن هذا المستودع** — التوزيعة الأصلية تضعها «بجانب مجلد الموقع». إن
> أردت العمل عليها، ارفعها ليُضاف كودها هنا.

---

## 1) ملفات Cloudflare في هذا المجلد

| الملف | الغرض |
|---|---|
| `_headers` | ترويسات Cloudflare Pages: `no-cache` لكل شيء (فالتحديث يصل فوراً)، وكاش طويل للأيقونات والخطوط. |
| `wrangler.toml` | إعداد اختياري لنشر الموقع الثابت عبر `wrangler pages deploy`. |
| `manifest.webmanifest`, `sw.js` | PWA: التثبيت والعمل بلا إنترنت. `sw.js` يستخدم «الشبكة أولاً» فالنشر يصل مباشرة. |

## 2) النشر على Cloudflare Pages

**من اللوحة:**
1. Cloudflare Dashboard ← Workers & Pages ← Create ← Pages ← Upload assets.
2. ارفع **محتوى هذا المجلد كما هو** (أو الملف المضغوط بعد فكه).
3. يعمل الموقع على `*.pages.dev`، وتقدر تربط دومينك.

**من الطرفية (بديل):**
```bash
npx wrangler pages deploy . --project-name cigram-web
```

## 3) خطوة لازمة مرة واحدة: CORS على R2 (لملفات الترجمة والصور وHLS)

المتصفح لا يقرأ ملفاً من `pub-….r2.dev` إلا إذا سمح به ذلك المضيف. في
Cloudflare Dashboard ← R2 ← bucket `movies` ← Settings ← CORS Policy ← Add
CORS policy ← تبويب JSON، والصق:

```json
[
  {
    "AllowedOrigins": ["*"],
    "AllowedMethods": ["GET", "HEAD"],
    "AllowedHeaders": ["Range"],
    "ExposeHeaders": ["Content-Length", "Content-Range"],
    "MaxAgeSeconds": 3600
  }
]
```

هذا لا يغيّر شيئاً في التطبيق (المخزن عام أصلاً)، ويسمح فقط للمتصفح بقراءة الملفات.
البديل الاحتياطي للترجمة هو Worker باسم `cigram-subs` (كوده خارج هذا المستودع).

## 4) الخدمات (Workers + R2) التي يقرأ منها الموقع

كلها مُعرّفة في `js/config.js` — **غيّر الروابط هناك فقط** إن اختلفت عندك:

| الغرض | الرابط (من `config.js`) | الحقل |
|---|---|---|
| المحتوى/الإدارة | `cigram-admin-api.wwq-mixtv.workers.dev` | `apiBase` |
| الحسابات | `cigram-auth-api.wwq-mixtv.workers.dev` | `authApi` |
| Cigram Plus | `cigram-plus-api.wwq-mixtv.workers.dev` | `plusApi` |
| القنوات | `sigram-channels-api.wwq-mixtv.workers.dev/channels` | `channelsApi` |
| بروكسي الترجمة | `cigram-subs.wwq-mixtv.workers.dev` | `subtitleProxy` |
| بروكسي البث | `cigram-stream.wwq-mixtv.workers.dev` | `streamProxy` |
| تخزين الوسائط R2 | `pub-eabe41d14a0644e5800344c99ef4d971.r2.dev` و`pub-6ce52a4bbf2543ee88ad9883ae42a806.r2.dev` | (داخل `js/cigram.js`) |
| تحميل التطبيق | `cigram.wwq-mixtv.workers.dev` | `downloadUrl` |

## 5) إعدادات إضافية في Workers (اختصار من README)

- **بروكسي البث** `cigram-stream`: يحتاج `wrangler secret put STREAM_SECRET` (نص عشوائي طويل). بدونه تعمل قنوات https فقط.
- **الحسابات + Google**: أضف `GOOGLE_CLIENT_ID` في `cigram-auth-api` ونفس القيمة في `googleClientId` بـ`config.js`. طالما القيمة فارغة يختفي زر Google ويبقى الدخول بالبريد.
- **الأمان**: لا توكِنات ولا مفاتيح خاصة في الواجهة — تأكّدت من ذلك. العمليات الإدارية تبقى محميّة في الخادم.

## 6) اختبار محلي

```bash
npx http-server . -p 8080 -c-1   # أي سيرفر ملفات ثابت
```
ثم افتح `http://localhost:8080` (لا تفتح `index.html` بنقر مزدوج — المتصفح يمنع وحدات JS من `file://`).
