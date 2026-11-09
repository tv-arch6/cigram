# أسرع حل لمشكلة الدردشة (بدون نشر كود)
تطبيق 18.5.0 أثبت أن حسابك سليم عند خدمة الحسابات، وأن Worker الدردشة الحي (نسخة 2026-10-07) هو الذي يرفض التحقق.
جرّب أولاً: لوحة Cloudflare → `cigram-ads-api` → Settings → Bindings → Add → **Service binding**
- Variable name: **`AUTH_API`** (بهذا الاسم تماماً)
- Service: **`cigram-auth-api`**
ثم احفظ. وتأكد في Variables أن `AUTH_VERIFY_URL` غير موجود أو يساوي تماماً `https://cigram-auth-api.wwq-mixtv.workers.dev/me`.
افتح الدردشة من جديد (التطبيق يعيد المحاولة وحده). إن رفضت اللوحة الحفظ بنفس خطأ exports فاتبع الأسفل (wrangler).

---
# نشر cigram-ads-api (حل خطأ Durable Object exports)

## ما الخطأ؟
```
[provisioned_class_missing_from_config] class 'AdsCore' ... class 'ChatHub' ... is not declared in `exports`
```
كلاودفلير صار يطابق كلاسات Durable Object (`AdsCore` و`ChatHub`) مع حقل `exports` في إعدادات الـ Worker.
زر **Deploy** في محرر اللوحة لا يرسل هذا الحقل أبداً، فيرفض النشر. الكود نفسه سليم (يصدّر الكلاسين)، والمحادثات المخزّنة لم تُمَسّ:
الـ Worker الحي ما زال بآخر نسخة قديمة (آخر تعديل 2026-10-07).

## الحل: النشر بـ wrangler (من GitHub أو من حاسوب) وليس من محرر اللوحة
الملف `wrangler.jsonc` هنا يعلن الكلاسين في `exports` (SQLite) ويحافظ على المتغيرات (`keep_vars`) ولا يحذف الأسرار.

### إن كان الـ Worker متصلاً بـ GitHub أصلاً (Workers Builds)
1. في مستودعك ضع `index.js` (= الملف `cigram-ads-api.js`) بدل ملف الـ Worker القديم.
2. ضع بجانبه `wrangler.jsonc` هذا، **أو** أضف إلى إعدادك الحالي فقط:
   ```jsonc
   "exports": {
     "AdsCore": { "type": "durable-object", "storage": "sqlite" },
     "ChatHub": { "type": "durable-object", "storage": "sqlite" }
   }
   ```
   وإن كان في إعدادك القديم `"migrations": [...]` فاحذفه (لا يجتمع مع `exports`).
3. الأمر المستخدم للنشر يجب أن يكون `npx wrangler deploy` (وليس `versions upload`) وعلى الفرع الرئيسي للإنتاج.

### إن لم يكن متصلاً
Worker → Settings → Builds → Connect → المستودع `tv-arch6/cigram`، الفرع `ccr-127bce50-ff66kz`، **Root directory** = `workers/cigram-ads-api`،
Deploy command = `npx wrangler deploy`.

## قبل أول نشر (مهم)
افتح Worker → Settings → Bindings وقارن مع `wrangler.jsonc`: النشر بـ wrangler **يستبدل** الـ bindings بما في الملف.
- `ADS_CORE` و`CHAT_HUB` (Durable Objects)، `ADS_BUCKET` = `cigram-ads`، `MOVIES_BUCKET` = `movies`، `AUTH_API` = خدمة `cigram-auth-api`.
- أي binding موجود عندك وغير مذكور هنا أضفه إلى الملف قبل النشر، وإن كان `ADS_BUCKET` غير موجود عندك فاحذفه من الملف (وإلا تتحوّل ملفات المحادثة إلى حاوية أخرى).
- إن ظهر `storage_type_mismatch` بدّل `"sqlite"` إلى `"legacy-kv"` للكلاسين (لا يمسّ البيانات).

## بعد النشر
`ADMIN_TOKEN=… docs/backend/scripts/post_deploy_smoke.sh` ثم أضف سرّ `FCM_SERVICE_ACCOUNT` (انظر DEPLOY.md القسم 9).
التراجع: Deployments → النسخة السابقة (لا يوجد تغيير في دورة حياة الكلاسات فالتراجع ممكن).
