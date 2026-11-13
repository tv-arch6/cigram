// CIGRAM AUTH API — v7 (الأمان)
//
// v7 (إضافات فقط، كل مسارات التطبيق والموقع تعمل كما هي):
//   - CORS صارم اختياري: ALLOWED_ORIGINS="https://serviceh.online,https://www.serviceh.online". عند ضبطه يُرفض (403) أي طلب
//     يحمل Origin من موقع آخر. التطبيق لا يرسل Origin فلا يتأثر. بدون ضبطه يبقى السلوك القديم (*).
//   - قفل الحساب بعد 5 محاولات دخول فاشلة خلال 15 دقيقة (لكل بريد، حتى لو البريد غير موجود) و20 لكل IP، ويُصفَّر بنجاح الدخول.
//   - تساوي زمن الرد (hash وهمي) عندما يكون البريد غير موجود، ورسائل خطأ موحّدة.
//   - الجلسات تحفظ الجهاز والمتصفح والدولة/المدينة التقريبية وآخر نشاط (بدون عنوان IP)، وتُربط ببصمة المتصفح/النظام (بدون رقم الإصدار):
//     عند تغيّرها فجأة تُلغى الجلسة ويُطلب الدخول من جديد. BIND_SESSION="0" لإيقاف الربط.
//   - GET /activity: آخر نشاط على الحساب (دخول، تغيير كلمة المرور...). GET /sessions: قائمة الأجهزة النشطة. POST /sessions/revoke: إنهاء جهاز.
//   - رابط دخول من التطبيق إلى الموقع (QR): POST /link/create (بجلسة التطبيق) ثم POST /link/redeem {token} من المتصفح.
//     الرمز عشوائي 256 بتاً، يُحفظ مجزّأً، صالح 60 ثانية ولمرة واحدة فقط.
//   - حذف الحساب على مرحلتين: POST /account/delete/start (كلمة المرور أو confirm) ثم /account/delete مع delete_token وconfirm="DELETE".
//     الطريقة القديمة (كلمة المرور مباشرة) ما زالت تعمل للتطبيق الحالي.
//   - تنقية الاسم من الرموز غير المرئية و<>، ورؤوس أمان (HSTS, CSP, Permissions-Policy).
//   حدود صريحة: Argon2id غير متاح في Cloudflare Workers، وPBKDF2 محدود فيها بـ100,000 دورة (الحد الأقصى المسموح)؛ الجلسات رموز معتمة
//   تُبطَل فوراً من الخادم (أقوى من JWT قصير العمر في هذا الاستخدام). لا كوكيز في هذا الـ API فلا CSRF (التوثيق بترويسة Authorization).
//
// CIGRAM AUTH API — v5
//
// v5 (إضافات فقط، كل مسارات التطبيق الحالية تعمل كما هي):
//   POST /google          تسجيل الدخول أو إنشاء حساب بحساب Google (رمز Google Identity Services).
//   POST /terms/accept    تسجيل موافقة المستخدم على الشروط.
//   POST /password/set    تعيين كلمة مرور لحساب أُنشئ بـ Google، فيقدر يدخل بها من التطبيق أيضاً.
//   /register يقبل accept_terms وterms_version (اختياري، حتى لا يتعطل التطبيق الحالي).
//   /login و/me يرجعان حقولاً جديدة: terms_accepted_at، terms_version، has_password، auth_provider، google_linked.
//   /account/delete: الحساب الذي ليس له كلمة مرور (Google) يُحذف بإرسال confirm: "DELETE".
//
// الإعدادات (Settings ← Variables and Secrets):
//   GOOGLE_CLIENT_ID  (Text)  معرّف OAuth Client من نوع Web. يمكن وضع أكثر من معرّف مفصولة بفواصل.
//   REQUIRE_TERMS     (Text)  اختياري. "1" = رفض إنشاء حساب بالبريد بدون موافقة على الشروط.
//                             اتركه فارغاً حتى يرسل التطبيق accept_terms أيضاً.
// الأعمدة الجديدة في جدول users تُضاف تلقائياً عند أول طلب (بدون حذف أو تعديل أي بيانات).

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
  "Content-Type": "application/json; charset=UTF-8",
  "Cache-Control": "no-store",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
  "Referrer-Policy": "no-referrer",
};

const GOOGLE_CERTS_URL = "https://www.googleapis.com/oauth2/v3/certs";
const GOOGLE_ISSUERS = ["accounts.google.com", "https://accounts.google.com"];

const schemaByDb = new WeakMap();   // مرة لكل قاعدة بيانات

export default {
  async fetch(request, env) {
    const blocked = originCheck(request, env);
    if (blocked) return blocked;
    return withSecurity(request, env, await handle(request, env));
  },
};

// ---- v7: CORS صارم + رؤوس أمان
function allowedOrigins(env) {
  return String(env.ALLOWED_ORIGINS || "").split(",").map((x) => x.trim().replace(/\/+$/, "")).filter(Boolean);
}

function originCheck(request, env) {
  const list = allowedOrigins(env);
  const origin = request.headers.get("Origin");
  if (!list.length || !origin) return null;            // التطبيق بلا Origin، وبدون إعداد = السلوك القديم
  if (list.includes(origin)) return null;
  return new Response(JSON.stringify({ success: false, message: "غير مسموح" }), { status: 403, headers: { "Content-Type": "application/json; charset=UTF-8", "Cache-Control": "no-store" } });
}

function withSecurity(request, env, response) {
  const headers = new Headers(response.headers);
  const list = allowedOrigins(env);
  const origin = request.headers.get("Origin") || "";
  if (list.length) {
    headers.set("Access-Control-Allow-Origin", list.includes(origin) ? origin : list[0]);
    headers.append("Vary", "Origin");
  }
  headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
  headers.set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
  if ((headers.get("Content-Type") || "").includes("json")) headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
  return new Response(response.body, { status: response.status, headers });
}

async function handle(request, env) {
  {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS });
    }

    const url = new URL(request.url);

    try {
      let schemaReady = schemaByDb.get(env.DB);
      if (!schemaReady) {
        schemaReady = ensureAuxiliaryTables(env).catch((error) => {
          schemaByDb.delete(env.DB); // نعيد المحاولة في الطلب القادم بدل أن يتعطل الـ Worker
          throw error;
        });
        schemaByDb.set(env.DB, schemaReady);
      }
      await schemaReady;

      if (request.method === "GET" && url.pathname === "/") {
        return json({
          success: true,
          service: "Cigram Auth API",
          version: 7,
          security: true,
          reactions: true,
          reaction_id_fix: true,
          google: googleClientIds(env).length > 0,
        });
      }

      if (request.method === "POST" && url.pathname === "/register") {
        return await register(request, env);
      }

      if (request.method === "POST" && url.pathname === "/login") {
        return await login(request, env);
      }

      if (request.method === "POST" && url.pathname === "/google") {
        return await googleLogin(request, env);
      }

      if (request.method === "GET" && url.pathname === "/me") {
        return await me(request, env);
      }

      if (request.method === "POST" && url.pathname === "/terms/accept") {
        return await acceptTerms(request, env);
      }

      if (request.method === "GET" && url.pathname === "/reactions") {
        return await getReactions(request, env, url);
      }

      if (request.method === "POST" && url.pathname === "/reactions") {
        return await setReaction(request, env);
      }

      if (request.method === "POST" && url.pathname === "/logout") {
        return await logout(request, env);
      }

      if (request.method === "POST" && url.pathname === "/logout-all") {
        return await logoutAll(request, env);
      }

      if (request.method === "POST" && url.pathname === "/profile/update") {
        return await updateProfile(request, env);
      }

      if (request.method === "POST" && url.pathname === "/profile/avatar") {
        return await updateAvatar(request, env);
      }

      if (request.method === "POST" && url.pathname === "/profile/avatar/delete") {
        return await deleteAvatar(request, env);
      }

      if (request.method === "POST" && url.pathname === "/password/change") {
        return await changePassword(request, env);
      }

      if (request.method === "POST" && url.pathname === "/password/set") {
        return await setPassword(request, env);
      }

      if (request.method === "GET" && url.pathname === "/activity") {
        return await activity(request, env);
      }

      if (request.method === "POST" && url.pathname === "/link/create") {
        return await linkCreate(request, env);
      }

      if (request.method === "POST" && url.pathname === "/link/redeem") {
        return await linkRedeem(request, env);
      }

      if (request.method === "POST" && url.pathname === "/account/delete/start") {
        return await deleteStart(request, env);
      }

      if (request.method === "GET" && url.pathname === "/sessions") {
        return await listSessions(request, env);
      }

      if (request.method === "POST" && url.pathname === "/sessions/revoke") {
        return await revokeSession(request, env);
      }

      if (request.method === "POST" && url.pathname === "/account/delete") {
        return await deleteAccount(request, env);
      }

      if (request.method === "GET" && url.pathname.startsWith("/avatars/")) {
        return await serveAvatar(url, env);
      }

      return json({ success: false, message: "المسار غير موجود" }, 404);
    } catch (error) {
      console.error(error);
      return json({ success: false, message: "حدث خطأ في الخادم" }, 500);
    }
  }
}

// -----------------------------------------------------------------------------
// إنشاء حساب وتسجيل دخول بالبريد
// -----------------------------------------------------------------------------

async function register(request, env) {
  if (!(await allowAttempt(request, env, "register", 5, 15))) {
    return json({ success: false, message: "محاولات كثيرة، حاول بعد 15 دقيقة" }, 429);
  }
  const body = await readJson(request);
  if (!body) return json({ success: false, message: "البيانات غير صالحة" }, 400);

  const name = cleanName(body.name);
  const email = normalizeEmail(body.email);
  const password = String(body.password || "");
  const gender = normalizeGender(body.gender);
  const terms = termsFromBody(body);

  if (name.length < 2 || name.length > 60) {
    return json({ success: false, message: "الاسم يجب أن يكون من 2 إلى 60 حرفاً" }, 400);
  }
  if (!isValidEmail(email)) {
    return json({ success: false, message: "البريد الإلكتروني غير صحيح" }, 400);
  }
  if (password.length < 8 || password.length > 128) {
    return json({ success: false, message: "كلمة المرور يجب ألا تقل عن 8 أحرف" }, 400);
  }
  if (String(env.REQUIRE_TERMS || "") === "1" && !terms.accepted) {
    return json({ success: false, code: "terms_required", message: "يجب الموافقة على الشروط لإنشاء الحساب" }, 400);
  }

  const existing = await env.DB.prepare(
    "SELECT id FROM users WHERE email = ? LIMIT 1"
  ).bind(email).first();

  if (existing) {
    return json({ success: false, message: "البريد الإلكتروني مستخدم مسبقاً" }, 409);
  }

  const id = crypto.randomUUID();
  const saltBytes = crypto.getRandomValues(new Uint8Array(16));
  const salt = bytesToBase64(saltBytes);
  const passwordHash = await hashPassword(password, saltBytes);
  const termsAt = terms.accepted ? new Date().toISOString() : "";
  const termsVersion = terms.accepted ? terms.version : "";

  await env.DB.prepare(
    `INSERT INTO users (id, name, email, password_hash, password_salt, gender, avatar_url,
      auth_provider, password_set, terms_accepted_at, terms_version)
    VALUES (?, ?, ?, ?, ?, ?, '', 'email', 1, ?, ?)`
  ).bind(id, name, email, passwordHash, salt, gender, termsAt, termsVersion).run();

  await audit(env, id, "register", request);
  if (terms.accepted) await audit(env, id, "terms_accept", request);

  const session = await createSession(env, id, request);
  return json({
    success: true,
    message: "تم إنشاء الحساب بنجاح",
    token: session.token,
    expires_at: session.expiresAt,
    user: await publicUser(env, {
      id, name, email, gender, avatar_url: "",
      auth_provider: "email", password_set: 1, terms_accepted_at: termsAt, terms_version: termsVersion,
    }),
  }, 201);
}

const LOGIN_FAIL_ACCOUNT = 5;   // محاولات فاشلة لكل بريد خلال 15 دقيقة
const LOGIN_FAIL_IP = 20;       // ولكل IP (أكبر لأن شبكات الجوال تشارك عنواناً واحداً)
const DUMMY_SALT = new Uint8Array(16);
const LOGIN_FAIL = "البريد أو كلمة المرور غير صحيحة";

async function login(request, env) {
  if (!(await allowAttempt(request, env, "login", 10, 15))) {
    return json({ success: false, message: "محاولات كثيرة، حاول بعد 15 دقيقة" }, 429);
  }
  const body = await readJson(request);
  if (!body) return json({ success: false, message: "البيانات غير صالحة" }, 400);

  const email = normalizeEmail(body.email);
  const password = String(body.password || "");
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const acctKey = "lf:" + (await sha256(email || "-"));
  const ipKey = "lfi:" + ip;

  if (await isLocked(env, acctKey, LOGIN_FAIL_ACCOUNT, 15) || await isLocked(env, ipKey, LOGIN_FAIL_IP, 15)) {
    return json({ success: false, message: "محاولات كثيرة، حاول بعد 15 دقيقة" }, 429);
  }
  const fail = async (extra) => {
    await recordFailure(env, acctKey, 15);
    await recordFailure(env, ipKey, 15);
    return json({ success: false, message: LOGIN_FAIL, ...(extra || {}) }, 401);
  };

  if (!isValidEmail(email) || !password || password.length > 128) {
    await hashPassword(password.slice(0, 128), DUMMY_SALT);
    return await fail();
  }

  const user = await env.DB.prepare(
    "SELECT * FROM users WHERE email = ? LIMIT 1"
  ).bind(email).first();

  if (!user || Number(user.is_active) !== 1) {
    await hashPassword(password, DUMMY_SALT);       // نفس الزمن تقريباً: لا يُعرف إن كان البريد مسجّلاً
    return await fail();
  }

  const actualHash = await hashPassword(password, base64ToBytes(user.password_salt));
  if (!constantTimeEqual(actualHash, user.password_hash)) {
    if (!hasPassword(user)) {
      await recordFailure(env, acctKey, 15);
      await recordFailure(env, ipKey, 15);
      return json({
        success: false,
        code: "google_account",
        message: "هذا الحساب أُنشئ بحساب Google. ادخل بزر Google، أو عيّن كلمة مرور من صفحة حسابي في الموقع.",
      }, 401);
    }
    return await fail();
  }

  await clearFailures(env, acctKey);
  const session = await createSession(env, user.id, request);
  await audit(env, user.id, "login", request);
  return json({
    success: true,
    message: "تم تسجيل الدخول بنجاح",
    token: session.token,
    expires_at: session.expiresAt,
    user: await publicUser(env, user),
  });
}

// ---- v7: عدّاد المحاولات الفاشلة (جدول rate_limits نفسه)
async function isLocked(env, key, maximum, minutes) {
  const row = await env.DB.prepare("SELECT attempts, window_start FROM rate_limits WHERE key = ?").bind(key).first();
  if (!row) return false;
  if (Date.now() - Date.parse(row.window_start) > minutes * 60000) return false;
  return Number(row.attempts) >= maximum;
}

async function recordFailure(env, key, minutes) {
  const row = await env.DB.prepare("SELECT window_start FROM rate_limits WHERE key = ?").bind(key).first();
  const now = Date.now();
  if (!row || now - Date.parse(row.window_start) > minutes * 60000) {
    await env.DB.prepare(
      `INSERT INTO rate_limits (key, attempts, window_start) VALUES (?, 1, ?)
      ON CONFLICT(key) DO UPDATE SET attempts = 1, window_start = excluded.window_start`
    ).bind(key, new Date(now).toISOString()).run();
  } else {
    await env.DB.prepare("UPDATE rate_limits SET attempts = attempts + 1 WHERE key = ?").bind(key).run();
  }
}

async function clearFailures(env, key) {
  await env.DB.prepare("DELETE FROM rate_limits WHERE key = ?").bind(key).run();
}

// -----------------------------------------------------------------------------
// Google
// -----------------------------------------------------------------------------

function googleClientIds(env) {
  return String(env.GOOGLE_CLIENT_ID || "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);
}

/**
 * يستقبل { credential, accept_terms, terms_version } حيث credential هو رمز ID Token من Google Identity Services.
 * - حساب مربوط بـ Google أو بنفس البريد: تسجيل دخول (ويُربط بـ Google إن لم يكن مربوطاً).
 * - حساب جديد: يُنشأ فقط مع الموافقة على الشروط، وإلا يرجع 428 terms_required.
 */
async function googleLogin(request, env) {
  if (!(await allowAttempt(request, env, "google", 20, 15))) {
    return json({ success: false, message: "محاولات كثيرة، حاول بعد 15 دقيقة" }, 429);
  }
  const audiences = googleClientIds(env);
  if (!audiences.length) {
    return json({ success: false, code: "google_not_configured", message: "تسجيل الدخول بحساب Google غير مفعّل بعد" }, 503);
  }

  const body = await readJson(request);
  const credential = String(body?.credential || "").trim();
  if (!credential || credential.length > 8192) {
    return json({ success: false, message: "البيانات غير صالحة" }, 400);
  }

  let claims;
  try {
    claims = await verifyGoogleIdToken(credential, audiences);
  } catch (error) {
    console.warn("google token rejected:", error && error.message);
    return json({ success: false, code: "google_invalid", message: "تعذّر التحقق من حساب Google، حاول مرة أخرى" }, 401);
  }

  const email = normalizeEmail(claims.email);
  const sub = String(claims.sub || "");
  if (!isValidEmail(email) || !sub) {
    return json({ success: false, code: "google_invalid", message: "حساب Google لا يحتوي بريداً صالحاً" }, 401);
  }
  if (!(claims.email_verified === true || claims.email_verified === "true")) {
    return json({ success: false, code: "google_unverified", message: "بريد حساب Google غير مؤكد" }, 401);
  }

  const terms = termsFromBody(body);

  let user = await env.DB.prepare(
    "SELECT * FROM users WHERE google_sub = ? LIMIT 1"
  ).bind(sub).first();
  if (!user) {
    user = await env.DB.prepare(
      "SELECT * FROM users WHERE email = ? LIMIT 1"
    ).bind(email).first();
  }

  // ---------------------------------------- حساب موجود
  if (user) {
    if (Number(user.is_active) !== 1) {
      return json({ success: false, message: "هذا الحساب موقوف" }, 403);
    }
    if (user.google_sub && user.google_sub !== sub) {
      return json({ success: false, message: "هذا البريد مربوط بحساب Google آخر" }, 409);
    }
    if (!user.google_sub) {
      await env.DB.prepare(
        "UPDATE users SET google_sub = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?"
      ).bind(sub, user.id).run();
      await audit(env, user.id, "google_link", request);
    }
    if (terms.accepted && user.terms_version !== terms.version) {
      await recordTerms(env, user.id, terms.version);
      await audit(env, user.id, "terms_accept", request);
    }

    const fresh = await env.DB.prepare("SELECT * FROM users WHERE id = ? LIMIT 1").bind(user.id).first();
    const session = await createSession(env, user.id, request);
    await audit(env, user.id, "google_login", request);
    return json({
      success: true,
      message: "تم تسجيل الدخول بنجاح",
      created: false,
      token: session.token,
      expires_at: session.expiresAt,
      user: await publicUser(env, fresh),
    });
  }

  // ---------------------------------------- حساب جديد
  if (!terms.accepted) {
    return json({
      success: false,
      code: "terms_required",
      message: "وافق على شروط الاستخدام وسياسة الخصوصية لإنشاء حسابك",
    }, 428);
  }

  const id = crypto.randomUUID();
  let name = String(claims.name || claims.given_name || "").trim().slice(0, 60);
  if (name.length < 2) name = email.split("@")[0].slice(0, 60);
  if (name.length < 2) name = "مستخدم سيكرام";
  const picture = String(claims.picture || "");
  const avatarUrl = /^https:\/\/[^\s"']+$/.test(picture) && picture.length <= 1000 ? picture : "";

  // كلمة مرور عشوائية لا يعرفها أحد: الحساب يدخل بـ Google حتى يعيّن المستخدم كلمة مرور بنفسه
  const saltBytes = crypto.getRandomValues(new Uint8Array(16));
  const randomPassword = bytesToBase64Url(crypto.getRandomValues(new Uint8Array(32)));
  const passwordHash = await hashPassword(randomPassword, saltBytes);
  const termsAt = new Date().toISOString();

  try {
    await env.DB.prepare(
      `INSERT INTO users (id, name, email, password_hash, password_salt, gender, avatar_url,
        google_sub, auth_provider, password_set, terms_accepted_at, terms_version)
      VALUES (?, ?, ?, ?, ?, 'unspecified', ?, ?, 'google', 0, ?, ?)`
    ).bind(id, name, email, passwordHash, bytesToBase64(saltBytes), avatarUrl, sub, termsAt, terms.version).run();
  } catch (error) {
    if (String(error).toLowerCase().includes("unique")) {
      return json({ success: false, message: "البريد الإلكتروني مستخدم مسبقاً، حاول مرة أخرى" }, 409);
    }
    throw error;
  }

  await audit(env, id, "google_register", request);
  await audit(env, id, "terms_accept", request);

  const session = await createSession(env, id, request);
  return json({
    success: true,
    message: "تم إنشاء الحساب بنجاح",
    created: true,
    token: session.token,
    expires_at: session.expiresAt,
    user: await publicUser(env, {
      id, name, email, gender: "unspecified", avatar_url: avatarUrl, google_sub: sub,
      auth_provider: "google", password_set: 0, terms_accepted_at: termsAt, terms_version: terms.version,
    }),
  }, 201);
}

let googleKeys = { keys: [], expires: 0, fetched: 0 };

async function googleKey(kid) {
  const now = Date.now();
  let found = googleKeys.keys.find((key) => key.kid === kid);
  // مفتاح غير معروف: Google بدّل مفاتيحه، نجلبها من جديد (مرة كل دقيقة على الأكثر)
  if (!found && (now > googleKeys.expires || now - googleKeys.fetched > 60000)) {
    const response = await fetch(GOOGLE_CERTS_URL, { cf: { cacheTtl: 3600, cacheEverything: true } });
    if (!response.ok) throw new Error("google certs " + response.status);
    const data = await response.json();
    const maxAge = Number((/max-age=(\d+)/.exec(response.headers.get("cache-control") || "") || [])[1] || 3600);
    googleKeys = { keys: Array.isArray(data.keys) ? data.keys : [], expires: now + maxAge * 1000, fetched: now };
    found = googleKeys.keys.find((key) => key.kid === kid);
  }
  return found || null;
}

async function verifyGoogleIdToken(token, audiences) {
  const parts = token.split(".");
  if (parts.length !== 3) throw new Error("malformed token");
  const header = JSON.parse(base64UrlToString(parts[0]));
  const payload = JSON.parse(base64UrlToString(parts[1]));
  if (header.alg !== "RS256" || !header.kid) throw new Error("unexpected alg");

  const jwk = await googleKey(header.kid);
  if (!jwk) throw new Error("unknown key");
  const key = await crypto.subtle.importKey(
    "jwk",
    { kty: jwk.kty, n: jwk.n, e: jwk.e, alg: "RS256", ext: true },
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["verify"]
  );
  const valid = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    key,
    base64UrlToBytes(parts[2]),
    new TextEncoder().encode(parts[0] + "." + parts[1])
  );
  if (!valid) throw new Error("bad signature");

  const now = Math.floor(Date.now() / 1000);
  if (!GOOGLE_ISSUERS.includes(payload.iss)) throw new Error("bad issuer");
  if (!audiences.includes(payload.aud)) throw new Error("bad audience");
  if (!(Number(payload.exp) > now - 60)) throw new Error("expired");
  if (payload.iat && Number(payload.iat) > now + 300) throw new Error("issued in future");
  return payload;
}

// -----------------------------------------------------------------------------
// الشروط وكلمة المرور
// -----------------------------------------------------------------------------

function termsFromBody(body) {
  const raw = body && body.accept_terms;
  const accepted = raw === true || raw === "true" || raw === "1" || raw === 1;
  let version = String((body && body.terms_version) || "").trim().slice(0, 40);
  if (!/^[\w.\-]+$/.test(version)) version = "1";
  return { accepted, version };
}

async function recordTerms(env, userId, version) {
  await env.DB.prepare(
    "UPDATE users SET terms_accepted_at = ?, terms_version = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?"
  ).bind(new Date().toISOString(), version, userId).run();
}

async function acceptTerms(request, env) {
  const auth = await authenticatedUser(request, env);
  if (!auth) return json({ success: false, message: "يجب تسجيل الدخول" }, 401);
  const body = await readJson(request);
  const terms = termsFromBody({ ...(body || {}), accept_terms: true });

  await recordTerms(env, auth.user.id, terms.version);
  await audit(env, auth.user.id, "terms_accept", request);
  const fresh = await env.DB.prepare("SELECT * FROM users WHERE id = ? LIMIT 1").bind(auth.user.id).first();
  return json({ success: true, message: "تم تسجيل موافقتك على الشروط", user: await publicUser(env, fresh) });
}

/** لحساب أُنشئ بـ Google فقط: يعيّن أول كلمة مرور حتى يدخل بها من التطبيق أيضاً. */
async function setPassword(request, env) {
  const auth = await authenticatedUser(request, env);
  if (!auth) return json({ success: false, message: "يجب تسجيل الدخول" }, 401);
  if (hasPassword(auth.user)) {
    return json({ success: false, message: "للحساب كلمة مرور مسبقاً، استخدم تغيير كلمة المرور" }, 409);
  }
  const body = await readJson(request);
  const password = String(body?.password || "");
  if (password.length < 8 || password.length > 128) {
    return json({ success: false, message: "كلمة المرور يجب ألا تقل عن 8 أحرف" }, 400);
  }

  const saltBytes = crypto.getRandomValues(new Uint8Array(16));
  const passwordHash = await hashPassword(password, saltBytes);
  await env.DB.prepare(
    `UPDATE users SET password_hash = ?, password_salt = ?, password_set = 1,
      updated_at = CURRENT_TIMESTAMP WHERE id = ?`
  ).bind(passwordHash, bytesToBase64(saltBytes), auth.user.id).run();
  await audit(env, auth.user.id, "password_set", request);

  const fresh = await env.DB.prepare("SELECT * FROM users WHERE id = ? LIMIT 1").bind(auth.user.id).first();
  return json({ success: true, message: "تم تعيين كلمة المرور، تقدر تدخل بها من التطبيق", user: await publicUser(env, fresh) });
}

// -----------------------------------------------------------------------------
// الجلسة والملف الشخصي (كما كانت، مع الحقول الجديدة في publicUser)
// -----------------------------------------------------------------------------

async function me(request, env) {
  const auth = await authenticatedUser(request, env);
  if (!auth) return json({ success: false, message: "انتهت الجلسة" }, 401);
  return json({
    success: true,
    user: await publicUser(env, auth.user),
    expires_at: auth.expiresAt,
  });
}

async function getReactions(request, env, url) {
  const contentId = normalizeContentId(url.searchParams.get("content_id"));
  if (!contentId) {
    return json({ success: false, message: "معرف المحتوى غير صالح" }, 400);
  }

  let auth = null;
  if (bearerToken(request)) {
    auth = await authenticatedUser(request, env);
  }

  const summary = await reactionSummary(env, contentId, auth?.user?.id || "");
  return json({
    success: true,
    content_id: contentId,
    likes: summary.likes,
    dislikes: summary.dislikes,
    reaction: summary.reaction,
    authenticated: Boolean(auth),
  });
}

async function setReaction(request, env) {
  const auth = await authenticatedUser(request, env);
  if (!auth) {
    return json({ success: false, message: "يجب تسجيل الدخول" }, 401);
  }

  const body = await readJson(request);
  if (!body) {
    return json({ success: false, message: "البيانات غير صالحة" }, 400);
  }

  const contentId = normalizeContentId(body.content_id);
  if (!contentId) {
    return json({ success: false, message: "معرف المحتوى غير صالح" }, 400);
  }

  const reactionName = String(body.reaction || "").trim().toLowerCase();
  const reaction = reactionName === "like" ? 1 : reactionName === "dislike" ? -1 : 0;
  if (!reaction) {
    return json({ success: false, message: "نوع التفاعل غير صالح" }, 400);
  }

  await env.DB.prepare(
    `INSERT INTO content_reactions (content_id, user_id, reaction, created_at, updated_at)
    VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
    ON CONFLICT(content_id, user_id) DO UPDATE SET
    reaction = excluded.reaction,
    updated_at = CURRENT_TIMESTAMP`
  ).bind(contentId, auth.user.id, reaction).run();

  const summary = await reactionSummary(env, contentId, auth.user.id);
  return json({
    success: true,
    content_id: contentId,
    likes: summary.likes,
    dislikes: summary.dislikes,
    reaction: summary.reaction,
  });
}

async function reactionSummary(env, contentId, userId = "") {
  const counts = await env.DB.prepare(
    `SELECT
    COALESCE(SUM(CASE WHEN reaction = 1 THEN 1 ELSE 0 END), 0) AS likes,
    COALESCE(SUM(CASE WHEN reaction = -1 THEN 1 ELSE 0 END), 0) AS dislikes
    FROM content_reactions
    WHERE content_id = ?`
  ).bind(contentId).first();

  let selected = null;
  if (userId) {
    selected = await env.DB.prepare(
      `SELECT reaction FROM content_reactions
      WHERE content_id = ? AND user_id = ? LIMIT 1`
    ).bind(contentId, userId).first();
  }

  const value = Number(selected?.reaction || 0);
  return {
    likes: Math.max(0, Number(counts?.likes || 0)),
    dislikes: Math.max(0, Number(counts?.dislikes || 0)),
    reaction: value === 1 ? "like" : value === -1 ? "dislike" : "none",
  };
}

function normalizeContentId(value) {
  const id = String(value || "").trim();
  if (!id || id.length > 512 || /[\u0000-\u001F\u007F]/.test(id)) return "";
  return id;
}

async function logout(request, env) {
  const rawToken = bearerToken(request);
  if (rawToken) {
    const tokenHash = await sha256(rawToken);
    await env.DB.prepare("DELETE FROM sessions WHERE token_hash = ?")
      .bind(tokenHash).run();
  }
  return json({ success: true, message: "تم تسجيل الخروج" });
}

async function logoutAll(request, env) {
  const auth = await authenticatedUser(request, env);
  if (!auth) return json({ success: false, message: "يجب تسجيل الدخول" }, 401);

  await env.DB.prepare("DELETE FROM sessions WHERE user_id = ?")
    .bind(auth.user.id).run();
  await audit(env, auth.user.id, "logout_all", request);
  return json({ success: true, message: "تم تسجيل الخروج من جميع الأجهزة" });
}

async function updateProfile(request, env) {
  const auth = await authenticatedUser(request, env);
  if (!auth) return json({ success: false, message: "يجب تسجيل الدخول" }, 401);
  const body = await readJson(request);
  if (!body) return json({ success: false, message: "البيانات غير صالحة" }, 400);

  const name = cleanName(body.name);
  const gender = normalizeGender(body.gender ?? auth.user.gender);
  if (name.length < 2 || name.length > 60) {
    return json({ success: false, message: "الاسم يجب أن يكون من 2 إلى 60 حرفاً" }, 400);
  }

  await env.DB.prepare(
    "UPDATE users SET name = ?, gender = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?"
  ).bind(name, gender, auth.user.id).run();
  await audit(env, auth.user.id, "profile_update", request);
  return json({
    success: true,
    message: "تم تحديث الملف الشخصي",
    user: await publicUser(env, { ...auth.user, name, gender }),
  });
}

async function updateAvatar(request, env) {
  const auth = await authenticatedUser(request, env);
  if (!auth) return json({ success: false, message: "يجب تسجيل الدخول" }, 401);
  const body = await readJson(request);
  const imageBase64 = String(body?.image_base64 || "").replace(/^data:[^;]+;base64,/, "");
  const mimeType = String(body?.mime_type || "image/jpeg").toLowerCase();
  const allowedTypes = new Set(["image/jpeg", "image/png", "image/webp"]);

  if (!env.AVATARS) {
    return json({ success: false, message: "مخزن صور الحسابات غير مربوط" }, 503);
  }
  if (!allowedTypes.has(mimeType)) {
    return json({ success: false, message: "صيغة الصورة غير مدعومة" }, 400);
  }
  if (!imageBase64 || imageBase64.length > 4_000_000) {
    return json({ success: false, message: "الصورة فارغة أو حجمها كبير" }, 400);
  }

  let imageBytes;
  try {
    imageBytes = base64ToBytes(imageBase64);
  } catch {
    return json({ success: false, message: "ملف الصورة غير صالح" }, 400);
  }
  if (imageBytes.byteLength > 3_000_000) {
    return json({ success: false, message: "حجم الصورة يجب ألا يتجاوز 3MB" }, 413);
  }

  const extension = mimeType === "image/png" ? "png" : mimeType === "image/webp" ? "webp" : "jpg";
  const avatarKey = `avatars/${auth.user.id}/${crypto.randomUUID()}.${extension}`;
  const oldAvatarKey = String(auth.user.avatar_key || "");

  await env.AVATARS.put(avatarKey, imageBytes, {
    httpMetadata: { contentType: mimeType, cacheControl: "public, max-age=31536000, immutable" },
    customMetadata: { userId: auth.user.id },
  });

  const avatarUrl = `${new URL(request.url).origin}/${avatarKey}`;

  await env.DB.prepare(
    "UPDATE users SET avatar_url = ?, avatar_key = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?"
  ).bind(avatarUrl, avatarKey, auth.user.id).run();

  if (oldAvatarKey && oldAvatarKey !== avatarKey) {
    await env.AVATARS.delete(oldAvatarKey);
  }
  await audit(env, auth.user.id, "avatar_update", request);
  return json({
    success: true,
    message: "تم تحديث صورة الحساب",
    avatar_url: avatarUrl,
    uses_default_avatar: false,
  });
}

async function deleteAvatar(request, env) {
  const auth = await authenticatedUser(request, env);
  if (!auth) return json({ success: false, message: "يجب تسجيل الدخول" }, 401);

  const oldAvatarKey = String(auth.user.avatar_key || "");
  if (oldAvatarKey && env.AVATARS) await env.AVATARS.delete(oldAvatarKey);
  await env.DB.prepare(
    "UPDATE users SET avatar_url = '', avatar_key = '', updated_at = CURRENT_TIMESTAMP WHERE id = ?"
  ).bind(auth.user.id).run();
  await audit(env, auth.user.id, "avatar_delete", request);
  return json({
    success: true,
    message: "تم حذف الصورة والعودة إلى الصورة الافتراضية",
    avatar_url: "",
    uses_default_avatar: true,
  });
}

async function changePassword(request, env) {
  const auth = await authenticatedUser(request, env);
  if (!auth) return json({ success: false, message: "يجب تسجيل الدخول" }, 401);
  const body = await readJson(request);
  if (!body) return json({ success: false, message: "البيانات غير صالحة" }, 400);
  if (!hasPassword(auth.user)) {
    return json({ success: false, code: "no_password", message: "هذا الحساب بدون كلمة مرور، عيّن كلمة مرور أولاً" }, 400);
  }

  const currentPassword = String(body.current_password || "");
  const newPassword = String(body.new_password || "");
  if (newPassword.length < 8 || newPassword.length > 128) {
    return json({ success: false, message: "كلمة المرور الجديدة يجب ألا تقل عن 8 أحرف" }, 400);
  }

  const record = await env.DB.prepare(
    "SELECT password_hash, password_salt FROM users WHERE id = ? LIMIT 1"
  ).bind(auth.user.id).first();
  const currentHash = await hashPassword(currentPassword, base64ToBytes(record.password_salt));
  if (!constantTimeEqual(currentHash, record.password_hash)) {
    return json({ success: false, message: "كلمة المرور الحالية غير صحيحة" }, 401);
  }

  const saltBytes = crypto.getRandomValues(new Uint8Array(16));
  const salt = bytesToBase64(saltBytes);
  const passwordHash = await hashPassword(newPassword, saltBytes);
  await env.DB.batch([
    env.DB.prepare(
      "UPDATE users SET password_hash = ?, password_salt = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?"
    ).bind(passwordHash, salt, auth.user.id),
    env.DB.prepare("DELETE FROM sessions WHERE user_id = ?").bind(auth.user.id),
  ]);
  await audit(env, auth.user.id, "password_change", request);
  return json({ success: true, message: "تم تغيير كلمة المرور، سجل الدخول مجدداً" });
}

async function listSessions(request, env) {
  const auth = await authenticatedUser(request, env);
  if (!auth) return json({ success: false, message: "يجب تسجيل الدخول" }, 401);
  const result = await env.DB.prepare(
    `SELECT id, created_at, expires_at, device, country, city, last_seen_at,
    CASE WHEN token_hash = ? THEN 1 ELSE 0 END AS current
    FROM sessions WHERE user_id = ? AND datetime(expires_at) > datetime('now')
    ORDER BY created_at DESC`
  ).bind(auth.tokenHash, auth.user.id).all();
  return json({ success: true, sessions: result.results || [] });
}

const ACTION_LABELS = {
  register: "إنشاء الحساب", login: "تسجيل دخول", google_login: "تسجيل دخول بـ Google", google_register: "إنشاء حساب بـ Google",
  google_link: "ربط حساب Google", logout_all: "تسجيل الخروج من كل الأجهزة", password_change: "تغيير كلمة المرور", password_set: "تعيين كلمة مرور",
  session_revoke: "إنهاء جلسة جهاز", profile_update: "تعديل الملف الشخصي", avatar_update: "تغيير الصورة", avatar_delete: "حذف الصورة",
  terms_accept: "الموافقة على الشروط", link_create: "إنشاء رابط دخول من التطبيق", link_redeem: "دخول برابط من التطبيق",
  delete_start: "بدء حذف الحساب", session_fingerprint_mismatch: "إنهاء جلسة لتغيّر بصمة الجهاز",
};

// GET /activity: آخر 30 عملية على الحساب (عنوان IP لا يُرجَع، فقط الدولة)
async function activity(request, env) {
  const auth = await authenticatedUser(request, env);
  if (!auth) return json({ success: false, message: "يجب تسجيل الدخول" }, 401);
  const result = await env.DB.prepare(
    "SELECT action, country, created_at FROM audit_logs WHERE user_id = ? ORDER BY created_at DESC, rowid DESC LIMIT 30"
  ).bind(auth.user.id).all();
  return json({
    success: true,
    events: (result.results || []).map((e) => ({ action: e.action, label: ACTION_LABELS[e.action] || e.action, country: e.country || "", at: e.created_at })),
  });
}

// ---- رابط دخول من التطبيق إلى الموقع (QR): عشوائي 256 بتاً، يُحفظ مجزّأً، 60 ثانية، مرة واحدة
const LINK_TTL_MS = 60 * 1000;

async function linkCreate(request, env) {
  const auth = await authenticatedUser(request, env);
  if (!auth) return json({ success: false, message: "يجب تسجيل الدخول" }, 401);
  if (!(await allowAttempt(request, env, "link_create", 10, 15))) {
    return json({ success: false, message: "محاولات كثيرة، حاول بعد 15 دقيقة" }, 429);
  }
  const token = bytesToBase64Url(crypto.getRandomValues(new Uint8Array(32)));
  const now = Date.now();
  await env.DB.prepare("DELETE FROM login_links WHERE expires_at < ?").bind(now - 60 * 1000).run();
  await env.DB.prepare("INSERT INTO login_links (token_hash, user_id, expires_at, used) VALUES (?, ?, ?, 0)")
    .bind(await sha256(token), auth.user.id, now + LINK_TTL_MS).run();
  await audit(env, auth.user.id, "link_create", request);
  const web = String(env.WEB_URL || "https://serviceh.online").replace(/\/+$/, "");
  return json({ success: true, link_token: token, expires_in: LINK_TTL_MS / 1000, url: `${web}/#/link/${token}` });
}

async function linkRedeem(request, env) {
  if (!(await allowAttempt(request, env, "link_redeem", 20, 15))) {
    return json({ success: false, message: "محاولات كثيرة، حاول بعد 15 دقيقة" }, 429);
  }
  const body = await readJson(request);
  const token = String(body?.token || "");
  const invalid = () => json({ success: false, message: "الرابط غير صالح أو انتهت صلاحيته" }, 400);
  if (token.length < 40 || token.length > 64) return invalid();
  const hash = await sha256(token);
  // مرة واحدة: التحديث الذري ينجح لطلب واحد فقط
  const used = await env.DB.prepare("UPDATE login_links SET used = 1 WHERE token_hash = ? AND used = 0 AND expires_at > ?")
    .bind(hash, Date.now()).run();
  if (!used.meta || Number(used.meta.changes) !== 1) return invalid();
  const link = await env.DB.prepare("SELECT user_id FROM login_links WHERE token_hash = ?").bind(hash).first();
  const user = link && await env.DB.prepare("SELECT * FROM users WHERE id = ? AND is_active = 1 LIMIT 1").bind(link.user_id).first();
  if (!user) return invalid();
  const session = await createSession(env, user.id, request);
  await audit(env, user.id, "link_redeem", request);
  return json({ success: true, token: session.token, expires_at: session.expiresAt, user: await publicUser(env, user) });
}

async function revokeSession(request, env) {
  const auth = await authenticatedUser(request, env);
  if (!auth) return json({ success: false, message: "يجب تسجيل الدخول" }, 401);
  const body = await readJson(request);
  const sessionId = String(body?.session_id || "");
  if (!sessionId) return json({ success: false, message: "معرف الجلسة مطلوب" }, 400);

  await env.DB.prepare("DELETE FROM sessions WHERE id = ? AND user_id = ?")
    .bind(sessionId, auth.user.id).run();
  await audit(env, auth.user.id, "session_revoke", request);
  return json({ success: true, message: "تم إنهاء الجلسة" });
}

// يتحقق من هوية صاحب الحساب قبل الحذف (كلمة المرور، أو confirm="DELETE" لحساب Google). يرجع Response عند الفشل.
async function verifyDeleteCredentials(env, user, body) {
  if (hasPassword(user)) {
    const password = String(body?.password || "");
    const record = await env.DB.prepare(
      "SELECT password_hash, password_salt FROM users WHERE id = ? LIMIT 1"
    ).bind(user.id).first();
    const actualHash = await hashPassword(password, base64ToBytes(record.password_salt));
    if (!constantTimeEqual(actualHash, record.password_hash)) {
      return json({ success: false, message: "كلمة المرور غير صحيحة" }, 401);
    }
  } else if (String(body?.confirm || "") !== "DELETE") {
    // حساب Google بدون كلمة مرور: تأكيد صريح بدل كلمة المرور
    return json({ success: false, code: "confirm_required", message: "أكّد حذف الحساب" }, 400);
  }
  return null;
}

// المرحلة 1: يتحقق من الهوية ويعطي delete_token صالحاً 10 دقائق
async function deleteStart(request, env) {
  const auth = await authenticatedUser(request, env);
  if (!auth) return json({ success: false, message: "يجب تسجيل الدخول" }, 401);
  if (!(await allowAttempt(request, env, "delete_start", 5, 15))) {
    return json({ success: false, message: "محاولات كثيرة، حاول بعد 15 دقيقة" }, 429);
  }
  const body = await readJson(request);
  const bad = await verifyDeleteCredentials(env, auth.user, body);
  if (bad) return bad;
  const token = bytesToBase64Url(crypto.getRandomValues(new Uint8Array(32)));
  await env.DB.prepare("DELETE FROM delete_tokens WHERE user_id = ? OR expires_at < ?").bind(auth.user.id, Date.now()).run();
  await env.DB.prepare("INSERT INTO delete_tokens (token_hash, user_id, expires_at) VALUES (?, ?, ?)")
    .bind(await sha256(token), auth.user.id, Date.now() + 10 * 60 * 1000).run();
  await audit(env, auth.user.id, "delete_start", request);
  return json({ success: true, delete_token: token, expires_in: 600, message: "للحذف النهائي أرسل delete_token مع confirm=DELETE خلال 10 دقائق" });
}

async function deleteAccount(request, env) {
  const auth = await authenticatedUser(request, env);
  if (!auth) return json({ success: false, message: "يجب تسجيل الدخول" }, 401);
  const body = await readJson(request);

  if (body && body.delete_token) {
    // المرحلة 2 (الموقع): رمز المرحلة الأولى + كتابة DELETE
    if (String(body.confirm || "") !== "DELETE") return json({ success: false, code: "confirm_required", message: "اكتب DELETE للتأكيد" }, 400);
    const row = await env.DB.prepare("SELECT user_id, expires_at FROM delete_tokens WHERE token_hash = ?")
      .bind(await sha256(String(body.delete_token))).first();
    if (!row || row.user_id !== auth.user.id || Number(row.expires_at) < Date.now()) {
      return json({ success: false, message: "انتهت صلاحية خطوة التأكيد، ابدأ من جديد" }, 400);
    }
  } else {
    // الطريقة القديمة للتطبيق الحالي: كلمة المرور مباشرة
    const bad = await verifyDeleteCredentials(env, auth.user, body);
    if (bad) return bad;
  }

  await audit(env, auth.user.id, "account_delete", request);
  const avatarKey = String(auth.user.avatar_key || "");
  if (avatarKey && env.AVATARS) await env.AVATARS.delete(avatarKey);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM content_reactions WHERE user_id = ?").bind(auth.user.id),
    env.DB.prepare("DELETE FROM login_links WHERE user_id = ?").bind(auth.user.id),
    env.DB.prepare("DELETE FROM delete_tokens WHERE user_id = ?").bind(auth.user.id),
    env.DB.prepare("DELETE FROM sessions WHERE user_id = ?").bind(auth.user.id),
    env.DB.prepare("DELETE FROM users WHERE id = ?").bind(auth.user.id),
  ]);
  return json({ success: true, message: "تم حذف الحساب" });
}

async function authenticatedUser(request, env) {
  const rawToken = bearerToken(request);
  if (!rawToken) return null;
  const tokenHash = await sha256(rawToken);
  const row = await env.DB.prepare(
    `SELECT u.*, s.id AS _sid, s.ua_sig AS _ua_sig, s.last_seen_at AS _seen, s.expires_at AS _exp
    FROM sessions s JOIN users u ON u.id = s.user_id
    WHERE s.token_hash = ? AND u.is_active = 1
    AND datetime(s.expires_at) > datetime('now') LIMIT 1`
  ).bind(tokenHash).first();
  if (!row) return null;
  const { _sid, _ua_sig, _seen, _exp, ...user } = row;
  // بصمة المتصفح/النظام تغيّرت فجأة: احتمال سرقة الرمز → تُلغى الجلسة ويُطلب الدخول من جديد
  if (_ua_sig && String(env.BIND_SESSION || "") !== "0") {
    const now = deviceInfo(request).sig;
    if (now && now !== _ua_sig) {
      await env.DB.prepare("DELETE FROM sessions WHERE id = ?").bind(_sid).run();
      await audit(env, user.id, "session_fingerprint_mismatch", request);
      return null;
    }
  }
  // آخر نشاط (كتابة واحدة كل 10 دقائق على الأكثر)
  if (!_seen || Date.now() - Date.parse(_seen) > 10 * 60 * 1000) {
    await env.DB.prepare("UPDATE sessions SET last_seen_at = ? WHERE id = ?").bind(new Date().toISOString(), _sid).run();
  }
  return { user, tokenHash, expiresAt: _exp };
}

async function serveAvatar(url, env) {
  if (!env.AVATARS) return new Response("Not found", { status: 404 });
  const key = url.pathname.slice(1);
  if (!/^avatars\/[a-f0-9-]+\/[a-f0-9-]+\.(jpg|png|webp)$/.test(key)) {
    return new Response("Not found", { status: 404 });
  }

  const object = await env.AVATARS.get(key);
  if (!object) return new Response("Not found", { status: 404 });

  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set("etag", object.httpEtag);
  headers.set("X-Content-Type-Options", "nosniff");
  return new Response(object.body, { headers });
}

// -----------------------------------------------------------------------------
// قاعدة البيانات: أعمدة وجداول إضافية (لا تحذف ولا تعدل أي بيانات)
// -----------------------------------------------------------------------------

async function addColumnIfMissing(env, columnNames, name, definition, table = "users") {
  if (columnNames.has(name)) return;
  try {
    await env.DB.prepare(`ALTER TABLE ${table} ADD COLUMN ${name} ${definition}`).run();
  } catch (error) {
    if (!String(error).toLowerCase().includes("duplicate column")) throw error;
  }
}

async function ensureAuxiliaryTables(env) {
  const columnsResult = await env.DB.prepare("PRAGMA table_info(users)").all();
  const columnNames = new Set((columnsResult.results || []).map((column) => column.name));

  await addColumnIfMissing(env, columnNames, "gender", "TEXT NOT NULL DEFAULT 'unspecified'");
  await addColumnIfMissing(env, columnNames, "avatar_url", "TEXT NOT NULL DEFAULT ''");
  await addColumnIfMissing(env, columnNames, "avatar_key", "TEXT NOT NULL DEFAULT ''");
  // v5
  await addColumnIfMissing(env, columnNames, "google_sub", "TEXT NOT NULL DEFAULT ''");
  await addColumnIfMissing(env, columnNames, "auth_provider", "TEXT NOT NULL DEFAULT 'email'");
  await addColumnIfMissing(env, columnNames, "password_set", "INTEGER NOT NULL DEFAULT 1");
  await addColumnIfMissing(env, columnNames, "terms_accepted_at", "TEXT NOT NULL DEFAULT ''");
  await addColumnIfMissing(env, columnNames, "terms_version", "TEXT NOT NULL DEFAULT ''");
  // v6: public account id "C" + 5 digits (shown in the app, copyable; unique)
  await addColumnIfMissing(env, columnNames, "public_id", "TEXT NOT NULL DEFAULT ''");

  // v7: بيانات الجلسات والنشاط
  const sessionCols = new Set(((await env.DB.prepare("PRAGMA table_info(sessions)").all()).results || []).map((c) => c.name));
  for (const [name, def] of [["device", "TEXT NOT NULL DEFAULT ''"], ["ua_sig", "TEXT NOT NULL DEFAULT ''"], ["country", "TEXT NOT NULL DEFAULT ''"],
    ["city", "TEXT NOT NULL DEFAULT ''"], ["last_seen_at", "TEXT NOT NULL DEFAULT ''"]]) {
    await addColumnIfMissing(env, sessionCols, name, def, "sessions");
  }

  await env.DB.batch([
    env.DB.prepare(
      `CREATE TABLE IF NOT EXISTS login_links (
      token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL, expires_at INTEGER NOT NULL, used INTEGER NOT NULL DEFAULT 0
      )`
    ),
    env.DB.prepare(
      `CREATE TABLE IF NOT EXISTS delete_tokens (
      token_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL, expires_at INTEGER NOT NULL
      )`
    ),
    env.DB.prepare(
      `CREATE TABLE IF NOT EXISTS rate_limits (
      key TEXT PRIMARY KEY, attempts INTEGER NOT NULL,
      window_start TEXT NOT NULL
      )`
    ),
    env.DB.prepare(
      `CREATE TABLE IF NOT EXISTS audit_logs (
      id TEXT PRIMARY KEY, user_id TEXT, action TEXT NOT NULL,
      ip TEXT, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
      )`
    ),
    env.DB.prepare(
      `CREATE TABLE IF NOT EXISTS content_reactions (
      content_id TEXT NOT NULL,
      user_id TEXT NOT NULL,
      reaction INTEGER NOT NULL CHECK (reaction IN (1, -1)),
      created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (content_id, user_id),
      FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
      )`
    ),
    env.DB.prepare(
      `CREATE INDEX IF NOT EXISTS idx_content_reactions_content
      ON content_reactions(content_id)`
    ),
    env.DB.prepare(
      `CREATE INDEX IF NOT EXISTS idx_content_reactions_user
      ON content_reactions(user_id)`
    ),
    env.DB.prepare(
      `CREATE INDEX IF NOT EXISTS idx_users_google_sub
      ON users(google_sub)`
    ),
    env.DB.prepare(
      `CREATE UNIQUE INDEX IF NOT EXISTS idx_users_public_id
      ON users(public_id) WHERE public_id <> ''`
    ),
  ]);
  const auditCols = new Set(((await env.DB.prepare("PRAGMA table_info(audit_logs)").all()).results || []).map((c) => c.name));
  await addColumnIfMissing(env, auditCols, "country", "TEXT NOT NULL DEFAULT ''", "audit_logs");
}

async function allowAttempt(request, env, action, maximum, minutes) {
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const key = `${action}:${ip}`;
  const row = await env.DB.prepare(
    "SELECT attempts, window_start FROM rate_limits WHERE key = ?"
  ).bind(key).first();
  const now = Date.now();
  const expired = !row || now - Date.parse(row.window_start) > minutes * 60000;

  if (expired) {
    await env.DB.prepare(
      `INSERT INTO rate_limits (key, attempts, window_start) VALUES (?, 1, ?)
      ON CONFLICT(key) DO UPDATE SET attempts = 1, window_start = excluded.window_start`
    ).bind(key, new Date(now).toISOString()).run();
    return true;
  }
  if (Number(row.attempts) >= maximum) return false;
  await env.DB.prepare("UPDATE rate_limits SET attempts = attempts + 1 WHERE key = ?")
    .bind(key).run();
  return true;
}

async function audit(env, userId, action, request) {
  const ip = request.headers.get("CF-Connecting-IP") || null;
  const country = String((request.cf && request.cf.country) || request.headers.get("CF-IPCountry") || "").slice(0, 2);
  await env.DB.prepare(
    "INSERT INTO audit_logs (id, user_id, action, ip, country) VALUES (?, ?, ?, ?, ?)"
  ).bind(crypto.randomUUID(), userId, action, ip, country).run();
}

async function createSession(env, userId, request, device) {
  const tokenBytes = crypto.getRandomValues(new Uint8Array(32));
  const token = bytesToBase64Url(tokenBytes);
  const tokenHash = await sha256(token);
  const sessionId = crypto.randomUUID();
  const expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();
  const info = request ? deviceInfo(request) : { label: "", sig: "", country: "", city: "" };
  const now = new Date().toISOString();

  await env.DB.prepare(
    `INSERT INTO sessions (id, user_id, token_hash, expires_at, device, ua_sig, country, city, last_seen_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
  ).bind(sessionId, userId, tokenHash, expiresAt, device || info.label, info.sig, info.country, info.city, now).run();

  return { token, expiresAt };
}

// ---- v7: الجهاز والمتصفح من User-Agent (بدون رقم الإصدار حتى لا تنتهي الجلسة مع كل تحديث للمتصفح) والموقع التقريبي من Cloudflare
function parseUA(ua) {
  const u = String(ua || "");
  let os = "";
  if (/iphone|ipad|ipod/i.test(u)) os = "iOS";
  else if (/android/i.test(u)) os = "Android";
  else if (/windows/i.test(u)) os = "Windows";
  else if (/mac os x|macintosh/i.test(u)) os = "macOS";
  else if (/linux|x11/i.test(u)) os = "Linux";
  let browser = "";
  if (/dalvik|okhttp|cigram/i.test(u)) browser = "تطبيق سيكرام";
  else if (/edg\//i.test(u)) browser = "Edge";
  else if (/opr\/|opera/i.test(u)) browser = "Opera";
  else if (/samsungbrowser/i.test(u)) browser = "Samsung Internet";
  else if (/firefox|fxios/i.test(u)) browser = "Firefox";
  else if (/chrome|crios/i.test(u)) browser = "Chrome";
  else if (/safari/i.test(u)) browser = "Safari";
  return { browser, os };
}

function deviceInfo(request) {
  const { browser, os } = parseUA(request.headers.get("User-Agent"));
  const label = [browser || "متصفح", os].filter(Boolean).join(" · ");
  const cf = request.cf || {};
  return {
    label: label.slice(0, 80),
    // التطبيق يتصل بأكثر من مكتبة (Dalvik/okhttp) بنظام مختلف في النص: بصمته واحدة دائماً
    sig: browser === "تطبيق سيكرام" ? "app" : (browser || os ? `${browser}|${os}` : ""),
    country: String(cf.country || request.headers.get("CF-IPCountry") || "").slice(0, 2),
    city: String(cf.city || "").slice(0, 60),
  };
}

async function hashPassword(password, saltBytes) {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    "PBKDF2",
    false,
    ["deriveBits"]
  );

  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: saltBytes, iterations: 100000 },
    key,
    256
  );
  return bytesToBase64(new Uint8Array(bits));
}

async function sha256(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return bytesToBase64(new Uint8Array(digest));
}

function constantTimeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let result = 0;
  for (let i = 0; i < a.length; i++) result |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return result === 0;
}

async function readJson(request) {
  try {
    const contentType = (
      request.headers.get("content-type") || ""
    ).toLowerCase();

    if (contentType.includes("application/json")) {
      return await request.json();
    }

    if (
      contentType.includes("application/x-www-form-urlencoded") ||
      contentType.includes("multipart/form-data")
    ) {
      const form = await request.formData();
      const data = {};

      for (const [key, value] of form.entries()) {
        data[key] = String(value);
      }

      return data;
    }

    return null;
  } catch {
    return null;
  }
}

function bearerToken(request) {
  const authorization = request.headers.get("Authorization") || "";
  return authorization.startsWith("Bearer ") ? authorization.slice(7).trim() : "";
}

function normalizeEmail(value) {
  return String(value || "").trim().toLowerCase();
}

// اسم نظيف: بدون رموز تحكم أو رموز اتجاه مخفية أو <>، ومسافات مفردة
function cleanName(value) {
  return String(value || "")
    .replace(/[\u0000-\u001f\u007f\u200b-\u200f\u202a-\u202e\u2066-\u2069\ufeff<>]/g, "")
    .replace(/\s+/g, " ")
    .trim();
}

function normalizeGender(value) {
  const gender = String(value || "unspecified").trim().toLowerCase();
  return ["male", "female", "unspecified"].includes(gender)
    ? gender
    : "unspecified";
}

function hasPassword(user) {
  return user.password_set === undefined || user.password_set === null || Number(user.password_set) !== 0;
}

// -----------------------------------------------------------------------------
// v6: المعرّف العام للحساب: الحرف C + 5 أرقام (10000-99999) عشوائية وفريدة. الحسابات القديمة تحصل عليه تلقائياً
// عند أول طلب (تسجيل دخول أو /me)، والحسابات الجديدة عند إنشائها. لا يتغيّر بعد ذلك.
// -----------------------------------------------------------------------------
function randomPublicId() {
  // عدد منتظم بدون انحياز (rejection sampling) بين 10000 و99999
  const range = 90000;
  const limit = Math.floor(0x100000000 / range) * range;
  const buf = new Uint32Array(1);
  do { crypto.getRandomValues(buf); } while (buf[0] >= limit);
  return "C" + String(10000 + (buf[0] % range));
}

async function ensurePublicId(env, user) {
  try {
    if (user && /^C\d{5}$/.test(String(user.public_id || ""))) return user.public_id;
    if (!user || !user.id) return "";
    const cur = await env.DB.prepare("SELECT public_id FROM users WHERE id = ? LIMIT 1").bind(user.id).first();
    if (cur && cur.public_id) return cur.public_id;
    for (let attempt = 0; attempt < 40; attempt++) {
      const candidate = randomPublicId();
      try {
        const r = await env.DB.prepare("UPDATE users SET public_id = ? WHERE id = ? AND public_id = ''").bind(candidate, user.id).run();
        if (r && r.meta && Number(r.meta.changes) === 1) return candidate;
        const again = await env.DB.prepare("SELECT public_id FROM users WHERE id = ? LIMIT 1").bind(user.id).first();
        if (again && again.public_id) return again.public_id;      // طلب متزامن سبقنا
      } catch (error) {
        if (!String(error).toLowerCase().includes("unique")) throw error;   // الرقم مأخوذ: نجرب غيره
      }
    }
  } catch (error) {
    console.warn("public id assignment failed:", error && error.message);
  }
  return "";   // لا نكسر الدخول بسببه؛ يُعاد المحاولة في الطلب التالي
}

async function publicUser(env, user) {
  const publicId = await ensurePublicId(env, user);
  const avatarUrl = String(user.avatar_url || "");
  return {
    id: user.id,
    public_id: publicId,
    name: user.name,
    email: user.email,
    gender: normalizeGender(user.gender),
    avatar_url: avatarUrl,
    uses_default_avatar: avatarUrl === "",
    // v5
    auth_provider: String(user.auth_provider || "email"),
    google_linked: Boolean(user.google_sub),
    has_password: hasPassword(user),
    terms_accepted_at: String(user.terms_accepted_at || ""),
    terms_version: String(user.terms_version || ""),
  };
}

function isValidEmail(email) {
  return email.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
}

function bytesToBase64(bytes) {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function bytesToBase64Url(bytes) {
  return bytesToBase64(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function base64ToBytes(value) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function base64UrlToBytes(value) {
  const base64 = String(value).replace(/-/g, "+").replace(/_/g, "/");
  return base64ToBytes(base64 + "===".slice((base64.length + 3) % 4));
}

function base64UrlToString(value) {
  return new TextDecoder().decode(base64UrlToBytes(value));
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: CORS });
}