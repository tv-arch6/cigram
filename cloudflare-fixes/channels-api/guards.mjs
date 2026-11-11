// PROPOSED hardening for sigram-channels-api. NOT deployed. Drop these into worker.js.

/** Constant-time comparison (the deployed isAdmin() uses ===, which stops at the first different character). */
export function timingSafeEqual(a, b) {
  const x = String(a || ""), y = String(b || "");
  let diff = x.length ^ y.length;
  const n = Math.max(x.length, y.length);
  for (let i = 0; i < n; i++) diff |= (x.charCodeAt(i) || 0) ^ (y.charCodeAt(i) || 0);
  return diff === 0;
}

export function isAdmin(request, env) {
  const sent = request.headers.get("X-Admin-Token") || "";
  return Boolean(env.ADMIN_TOKEN) && timingSafeEqual(sent, env.ADMIN_TOKEN);
}

/** The deployed handleAdd stores the logo with whatever content-type the caller sends (e.g. text/html on a public r2.dev URL). */
const IMAGE_MIMES = { "image/jpeg": "jpg", "image/jpg": "jpg", "image/png": "png", "image/webp": "webp" };
export function imageTypeFor(mime) {
  const m = String(mime || "image/jpeg").trim().toLowerCase();
  return IMAGE_MIMES[m] ? { mime: m === "image/jpg" ? "image/jpeg" : m, ext: IMAGE_MIMES[m] } : null;   // null -> answer 415
}

/** The deployed 500 handler returns error.message to the caller. */
export function internalError() {
  return { ok: false, error: "internal_error" };
}
