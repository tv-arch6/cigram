var __defProp = Object.defineProperty;
var __name = (target, value) => __defProp(target, "name", { value, configurable: true });

// worker.js
var SLOTS = ["splash", "hero", "inline", "popup", "sticky", "sponsor"];
var STAGES = ["received", "review", "quoted", "awaiting_payment"];
var PAID = ["unpaid", "partial", "paid"];
var ACTIONS = ["link", "whatsapp", "call", "store", "copy", "none"];
var DAY_MS = 864e5;
var MAX_CAMPAIGN_DAYS = 800;
var MEDIA_PREFIX = "ads/creatives/";
var MAX_IMAGE_BYTES = 6 * 1024 * 1024;
var MAX_VIDEO_BYTES = 40 * 1024 * 1024;
var HttpError = class extends Error {
  static {
    __name(this, "HttpError");
  }
  constructor(status, code, message, extra) {
    super(message || code);
    this.status = status;
    this.code = code;
    this.extra = extra || {};
  }
};
function fail(status, code, message, extra) {
  throw new HttpError(status, code, message, extra);
}
__name(fail, "fail");
function corsHeaders(extra) {
  return Object.assign(
    {
      "access-control-allow-origin": "*",
      "access-control-allow-methods": "GET,POST,OPTIONS",
      "access-control-allow-headers": "Content-Type, Authorization, X-Admin-Token",
      "access-control-max-age": "86400",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff"
    },
    extra || {}
  );
}
__name(corsHeaders, "corsHeaders");
function reply(body, status, extraHeaders) {
  return new Response(JSON.stringify(body), {
    status: status || 200,
    headers: corsHeaders(Object.assign({ "content-type": "application/json; charset=utf-8" }, extraHeaders || {}))
  });
}
__name(reply, "reply");
function errorBody(e) {
  if (e instanceof HttpError) {
    return [Object.assign({ ok: false, error: e.code, message: e.message }, e.extra), e.status];
  }
  return [{ ok: false, error: "internal_error", message: "\u062D\u062F\u062B \u062E\u0637\u0623 \u062F\u0627\u062E\u0644\u064A \u0641\u064A \u0627\u0644\u062E\u0627\u062F\u0645." }, 500];
}
__name(errorBody, "errorBody");
function cleanText(v, max) {
  if (v === null || v === void 0) return "";
  let s = String(v).replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F‎‏‪-‮⁦-⁩]/g, "");
  s = s.trim();
  return max && s.length > max ? s.slice(0, max) : s;
}
__name(cleanText, "cleanText");
function reqText(v, max, label) {
  const s = cleanText(v, max);
  if (!s) fail(400, "invalid_field", "\u0627\u0644\u062D\u0642\u0644 \xAB" + label + "\xBB \u0645\u0637\u0644\u0648\u0628.");
  return s;
}
__name(reqText, "reqText");
function num(v, min, max, def) {
  if (v === null || v === void 0 || v === "") return def;
  const n = Number(v);
  if (!isFinite(n)) return def;
  return Math.min(max, Math.max(min, n));
}
__name(num, "num");
function int(v, min, max, def) {
  const n = num(v, min, max, def);
  return n === void 0 || n === null ? n : Math.round(n);
}
__name(int, "int");
function bool(v, def) {
  if (v === true || v === "true" || v === 1 || v === "1") return true;
  if (v === false || v === "false" || v === 0 || v === "0") return false;
  return def;
}
__name(bool, "bool");
function oneOf(v, list, def) {
  return list.indexOf(v) >= 0 ? v : def;
}
__name(oneOf, "oneOf");
function strList(v, maxItems, maxLen) {
  if (!Array.isArray(v)) return [];
  const out = [];
  for (const x of v) {
    const s = cleanText(x, maxLen);
    if (s && out.indexOf(s) < 0) out.push(s);
    if (out.length >= maxItems) break;
  }
  return out;
}
__name(strList, "strList");
function round2(n) {
  return Math.round(n * 100) / 100;
}
__name(round2, "round2");
function randId(prefix, bytes) {
  const a = new Uint8Array(bytes || 9);
  crypto.getRandomValues(a);
  return (prefix || "") + b64url(a);
}
__name(randId, "randId");
function b64url(bytes) {
  let s = "";
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
__name(b64url, "b64url");
function b64urlToBytes(str) {
  const s = String(str).replace(/-/g, "+").replace(/_/g, "/");
  const pad = s.length % 4 === 0 ? "" : "=".repeat(4 - s.length % 4);
  const bin = atob(s + pad);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
__name(b64urlToBytes, "b64urlToBytes");
async function sha256Hex(text) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(d)).map((b) => b.toString(16).padStart(2, "0")).join("");
}
__name(sha256Hex, "sha256Hex");
function timingSafeEqual(a, b) {
  a = String(a || "");
  b = String(b || "");
  let diff = a.length ^ b.length;
  const n = Math.max(a.length, b.length);
  for (let i = 0; i < n; i++) diff |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  return diff === 0;
}
__name(timingSafeEqual, "timingSafeEqual");
function parseTime(v, def) {
  if (v === null || v === void 0 || v === "") return def;
  if (typeof v === "number" && isFinite(v)) return Math.round(v);
  const s = String(v).trim();
  if (/^\d{10,14}$/.test(s)) return parseInt(s, 10);
  const t = Date.parse(s);
  return isNaN(t) ? def : t;
}
__name(parseTime, "parseTime");
function dayKey(ms, offsetMin) {
  const d = new Date(ms + (offsetMin || 0) * 6e4);
  return d.getUTCFullYear() + String(d.getUTCMonth() + 1).padStart(2, "0") + String(d.getUTCDate()).padStart(2, "0");
}
__name(dayKey, "dayKey");
function hourOf(ms, offsetMin) {
  return new Date(ms + (offsetMin || 0) * 6e4).getUTCHours();
}
__name(hourOf, "hourOf");
function safeUrl(v, allowed) {
  const s = cleanText(v, 800);
  if (!s) return "";
  const ok = (allowed || ["https:"]).some((p) => s.toLowerCase().startsWith(p + "//") || s.toLowerCase().startsWith(p));
  if (!ok) fail(400, "invalid_url", "\u0627\u0644\u0631\u0627\u0628\u0637 \u063A\u064A\u0631 \u0635\u0627\u0644\u062D (\u064A\u062C\u0628 \u0623\u0646 \u064A\u0628\u062F\u0623 \u0628\u0640 https://).");
  if (/[\s"'<>]/.test(s)) fail(400, "invalid_url", "\u0627\u0644\u0631\u0627\u0628\u0637 \u064A\u062D\u062A\u0648\u064A \u0639\u0644\u0649 \u0645\u062D\u0627\u0631\u0641 \u063A\u064A\u0631 \u0645\u0633\u0645\u0648\u062D\u0629.");
  return s;
}
__name(safeUrl, "safeUrl");
function digitsOnly(v, max) {
  return String(v || "").replace(/[^\d]/g, "").slice(0, max || 20);
}
__name(digitsOnly, "digitsOnly");
var IQ_CITIES = [
  ["baghdad", "\u0628\u063A\u062F\u0627\u062F"],
  ["basra", "\u0627\u0644\u0628\u0635\u0631\u0629"],
  ["nineveh", "\u0646\u064A\u0646\u0648\u0649"],
  ["erbil", "\u0623\u0631\u0628\u064A\u0644"],
  ["sulaymaniyah", "\u0627\u0644\u0633\u0644\u064A\u0645\u0627\u0646\u064A\u0629"],
  ["duhok", "\u062F\u0647\u0648\u0643"],
  ["kirkuk", "\u0643\u0631\u0643\u0648\u0643"],
  ["najaf", "\u0627\u0644\u0646\u062C\u0641"],
  ["karbala", "\u0643\u0631\u0628\u0644\u0627\u0621"],
  ["babil", "\u0628\u0627\u0628\u0644"],
  ["anbar", "\u0627\u0644\u0623\u0646\u0628\u0627\u0631"],
  ["diyala", "\u062F\u064A\u0627\u0644\u0649"],
  ["saladin", "\u0635\u0644\u0627\u062D \u0627\u0644\u062F\u064A\u0646"],
  ["wasit", "\u0648\u0627\u0633\u0637"],
  ["dhi_qar", "\u0630\u064A \u0642\u0627\u0631"],
  ["maysan", "\u0645\u064A\u0633\u0627\u0646"],
  ["muthanna", "\u0627\u0644\u0645\u062B\u0646\u0649"],
  ["qadisiyyah", "\u0627\u0644\u0642\u0627\u062F\u0633\u064A\u0629"],
  ["halabja", "\u062D\u0644\u0628\u062C\u0629"]
];
var CITY_ALIASES = {
  baghdad: ["baghdad", "bagdad", "baghdad governorate"],
  basra: ["basra", "basrah", "al basrah"],
  nineveh: ["nineveh", "ninawa", "mosul", "nineveh governorate"],
  erbil: ["erbil", "arbil", "irbil", "hewler"],
  sulaymaniyah: ["sulaymaniyah", "sulaimani", "as sulaymaniyah", "slemani"],
  duhok: ["duhok", "dohuk", "dahuk"],
  kirkuk: ["kirkuk", "at ta'mim", "at tamim"],
  najaf: ["najaf", "an najaf"],
  karbala: ["karbala", "karbala'"],
  babil: ["babil", "babylon", "hillah", "al hillah"],
  anbar: ["anbar", "al anbar", "ramadi"],
  diyala: ["diyala", "diyal\xE1", "baqubah"],
  saladin: ["saladin", "salah ad din", "salah al-din", "tikrit"],
  wasit: ["wasit", "kut"],
  dhi_qar: ["dhi qar", "dhi-qar", "thi qar", "nasiriyah"],
  maysan: ["maysan", "maysan governorate", "amarah"],
  muthanna: ["muthanna", "al muthanna", "samawah"],
  qadisiyyah: ["qadisiyyah", "al qadisiyah", "diwaniyah"],
  halabja: ["halabja"]
};
function defaultSettings() {
  const slot = /* @__PURE__ */ __name((label, desc, suited, price, views, hint) => ({
    label,
    desc,
    suited,
    price_per_day: price,
    views_per_day: views,
    enabled: true,
    preview_hint: hint
  }), "slot");
  return {
    version: 1,
    updated_at: 0,
    currency: { code: "USD", symbol: "$", decimals: 0 },
    slots: {
      splash: slot("\u0634\u0627\u0634\u0629 \u0627\u0644\u0627\u0641\u062A\u062A\u0627\u062D (Splash)", "\u0625\u0639\u0644\u0627\u0646 \u0628\u0645\u0644\u0621 \u0627\u0644\u0634\u0627\u0634\u0629 \u0644\u0628\u0636\u0639 \u062B\u0648\u0627\u0646\u064D \u0639\u0646\u062F \u0641\u062A\u062D \u0627\u0644\u062A\u0637\u0628\u064A\u0642 \u0645\u0639 \u0639\u062F\u0651\u0627\u062F \u0648\u0632\u0631 \u062A\u062E\u0637\u064A. \u0623\u0639\u0644\u0649 \u0638\u0647\u0648\u0631 \u0648\u0623\u0642\u0648\u0649 \u0623\u062B\u0631.", "\u0625\u0637\u0644\u0627\u0642 \u0645\u0646\u062A\u062C\u060C \u0639\u0631\u0648\u0636 \u0645\u0648\u0633\u0645\u064A\u0629\u060C \u0639\u0644\u0627\u0645\u0627\u062A \u062A\u062C\u0627\u0631\u064A\u0629 \u0643\u0628\u0631\u0649", 20, 4e3, "full_screen"),
      hero: slot("\u0627\u0644\u0628\u0627\u0646\u0631 \u0627\u0644\u0631\u0626\u064A\u0633\u064A (Hero)", "\u0628\u0627\u0646\u0631 \u0643\u0628\u064A\u0631 \u0623\u0639\u0644\u0649 \u0627\u0644\u0635\u0641\u062D\u0629 \u0627\u0644\u0631\u0626\u064A\u0633\u064A\u0629 \u064A\u062A\u062D\u0631\u0643 \u062A\u0644\u0642\u0627\u0626\u064A\u0627\u064B \u0628\u064A\u0646 \u0627\u0644\u0625\u0639\u0644\u0627\u0646\u0627\u062A \u0648\u064A\u062A\u0648\u0642\u0641 \u0639\u0646\u062F \u0627\u0644\u0644\u0645\u0633.", "\u062D\u0645\u0644\u0627\u062A \u0627\u0644\u0648\u0639\u064A \u0628\u0627\u0644\u0639\u0644\u0627\u0645\u0629\u060C \u0627\u0644\u0639\u0631\u0648\u0636 \u0627\u0644\u062F\u0627\u0626\u0645\u0629", 12, 6e3, "top_banner"),
      inline: slot("\u0628\u0637\u0627\u0642\u0629 \u0636\u0645\u0646 \u0627\u0644\u0645\u062D\u062A\u0648\u0649 (Inline)", "\u0628\u0637\u0627\u0642\u0629 \u0625\u0639\u0644\u0627\u0646\u064A\u0629 \u0628\u064A\u0646 \u0635\u0641\u0648\u0641 \u0627\u0644\u0623\u0641\u0644\u0627\u0645 \u0648\u0627\u0644\u0645\u0633\u0644\u0633\u0644\u0627\u062A \u062A\u0634\u0628\u0647 \u0628\u0637\u0627\u0642\u0627\u062A \u0627\u0644\u0645\u062D\u062A\u0648\u0649 \u0628\u0648\u0636\u0648\u062D \u0639\u0644\u0627\u0645\u0629 \xAB\u0625\u0639\u0644\u0627\u0646\xBB.", "\u0627\u0644\u0645\u062A\u0627\u062C\u0631 \u0648\u0627\u0644\u062E\u062F\u0645\u0627\u062A \u0627\u0644\u0645\u062D\u0644\u064A\u0629\u060C \u062A\u0637\u0628\u064A\u0642\u0627\u062A", 7, 8e3, "card"),
      popup: slot("\u0646\u0627\u0641\u0630\u0629 \u0645\u0646\u0628\u062B\u0642\u0629 (Popup)", "\u0646\u0627\u0641\u0630\u0629 \u062A\u0638\u0647\u0631 \u0645\u0631\u0629 \u0648\u0627\u062D\u062F\u0629 \u064A\u0648\u0645\u064A\u0627\u064B \u0644\u0643\u0644 \u0645\u0633\u062A\u062E\u062F\u0645 \u0645\u0639 \u0632\u0631 \u0625\u063A\u0644\u0627\u0642 \u0648\u0627\u0636\u062D.", "\u0639\u0631\u0648\u0636 \u0645\u062D\u062F\u0648\u062F\u0629 \u0627\u0644\u0645\u062F\u0629\u060C \u0643\u0648\u0628\u0648\u0646\u0627\u062A", 10, 3e3, "dialog"),
      sticky: slot("\u0634\u0631\u064A\u0637 \u062B\u0627\u0628\u062A (Sticky)", "\u0634\u0631\u064A\u0637 \u0631\u0641\u064A\u0639 \u062B\u0627\u0628\u062A \u0623\u0633\u0641\u0644 \u0627\u0644\u0634\u0627\u0634\u0629 \u064A\u0645\u0643\u0646 \u0644\u0644\u0645\u0633\u062A\u062E\u062F\u0645 \u0625\u063A\u0644\u0627\u0642\u0647.", "\u062A\u0630\u0643\u064A\u0631 \u0645\u0633\u062A\u0645\u0631 \u0628\u0627\u0644\u0639\u0644\u0627\u0645\u0629\u060C \u0631\u0648\u0627\u0628\u0637 \u0633\u0631\u064A\u0639\u0629", 5, 7e3, "bottom_bar"),
      sponsor: slot("\u0631\u0639\u0627\u064A\u0629 (Sponsor)", "\u0634\u0639\u0627\u0631 \xAB\u0628\u0631\u0639\u0627\u064A\u0629\xBB \u0628\u062C\u0627\u0646\u0628 \u0642\u0633\u0645 \u0623\u0648 \u0635\u0641\u062D\u0629 \u0645\u062D\u062F\u062F\u0629 \u0628\u0634\u0643\u0644 \u0623\u0646\u064A\u0642 \u0648\u063A\u064A\u0631 \u0645\u0632\u0639\u062C.", "\u0631\u0639\u0627\u064A\u0629 \u0623\u0642\u0633\u0627\u0645\u060C \u0634\u0631\u0627\u0643\u0627\u062A \u0637\u0648\u064A\u0644\u0629 \u0627\u0644\u0623\u0645\u062F", 4, 5e3, "badge")
    },
    durations: [1, 3, 7, 30, 90, 180, 365],
    custom_days: { min: 1, max: 730 },
    discounts: [
      { min_days: 7, pct: 5 },
      { min_days: 30, pct: 10 },
      { min_days: 90, pct: 15 },
      { min_days: 180, pct: 20 },
      { min_days: 365, pct: 30 }
    ],
    addons: {
      exclusive: { label: "\u062D\u0635\u0631\u064A\u0629 \u0627\u0644\u0645\u0633\u0627\u062D\u0629", desc: "\u0644\u0627 \u064A\u0638\u0647\u0631 \u0623\u064A \u0625\u0639\u0644\u0627\u0646 \u0622\u062E\u0631 \u0641\u064A \u0627\u0644\u0645\u0633\u0627\u062D\u0629 \u0637\u0648\u0627\u0644 \u0645\u062F\u0629 \u062D\u0645\u0644\u062A\u0643.", type: "pct", value: 50 },
      video: { label: "\u0625\u0639\u0644\u0627\u0646 \u0641\u064A\u062F\u064A\u0648", desc: "\u0639\u0631\u0636 \u0641\u064A\u062F\u064A\u0648 \u0642\u0635\u064A\u0631 \u0628\u062F\u0644 \u0627\u0644\u0635\u0648\u0631\u0629.", type: "flat", value: 15 },
      design: { label: "\u062A\u0635\u0645\u064A\u0645 \u0645\u0646 \u0641\u0631\u064A\u0642\u0646\u0627", desc: "\u0646\u0635\u0645\u0645 \u0644\u0643 \u0627\u0644\u0645\u0627\u062F\u0629 \u0627\u0644\u0625\u0639\u0644\u0627\u0646\u064A\u0629 \u0628\u0627\u062D\u062A\u0631\u0627\u0641.", type: "flat", value: 30 }
    },
    targeting: {
      cities: IQ_CITIES.map((c) => ({ id: c[0], ar: c[1], aliases: CITY_ALIASES[c[0]] || [] })),
      langs: [{ id: "ar", label: "\u0627\u0644\u0639\u0631\u0628\u064A\u0629" }, { id: "ku", label: "\u0627\u0644\u0643\u0631\u062F\u064A\u0629" }, { id: "en", label: "\u0627\u0644\u0625\u0646\u062C\u0644\u064A\u0632\u064A\u0629" }],
      city_pct: 15,
      lang_pct: 10,
      hours_pct: 10,
      city_reach_factor: 0.5,
      lang_reach_factor: 0.7,
      hours_reach_factor: 0.6
    },
    rules: {
      accepted: [
        { title: "\u0645\u0646\u062A\u062C\u0627\u062A \u0648\u062E\u062F\u0645\u0627\u062A \u0642\u0627\u0646\u0648\u0646\u064A\u0629", text: "\u0645\u062A\u0627\u062C\u0631\u060C \u0645\u0637\u0627\u0639\u0645\u060C \u0639\u064A\u0627\u062F\u0627\u062A\u060C \u0645\u062F\u0627\u0631\u0633\u060C \u062A\u0637\u0628\u064A\u0642\u0627\u062A\u060C \u062E\u062F\u0645\u0627\u062A \u0645\u0631\u062E\u0651\u0635\u0629 \u0648\u0648\u0627\u0636\u062D\u0629." },
        { title: "\u0645\u062D\u062A\u0648\u0649 \u0635\u0627\u062F\u0642", text: "\u0627\u062F\u0639\u0627\u0621\u0627\u062A \u064A\u0645\u0643\u0646 \u0625\u062B\u0628\u0627\u062A\u0647\u0627\u060C \u0623\u0633\u0639\u0627\u0631 \u0648\u0639\u0631\u0648\u0636 \u062D\u0642\u064A\u0642\u064A\u0629\u060C \u0648\u0634\u0631\u0648\u0637 \u0648\u0627\u0636\u062D\u0629." },
        { title: "\u0635\u0648\u0631 \u0628\u062C\u0648\u062F\u0629 \u0639\u0627\u0644\u064A\u0629", text: "\u0635\u0648\u0631 \u0648\u0627\u0636\u062D\u0629 \u063A\u064A\u0631 \u0645\u0634\u0648\u0634\u0629\u060C \u0646\u0635\u0648\u0635 \u0645\u0642\u0631\u0648\u0621\u0629\u060C \u0648\u0647\u0648\u064A\u0629 \u0628\u0635\u0631\u064A\u0629 \u0645\u062A\u0633\u0642\u0629." }
      ],
      rejected: [
        { title: "\u0627\u0644\u0645\u062D\u062A\u0648\u0649 \u0627\u0644\u062C\u0646\u0633\u064A", text: "\u0623\u064A \u0635\u0648\u0631 \u0623\u0648 \u0646\u0635\u0648\u0635 \u0623\u0648 \u0625\u064A\u062D\u0627\u0621\u0627\u062A \u062C\u0646\u0633\u064A\u0629." },
        { title: "\u0627\u0644\u0643\u062D\u0648\u0644 \u0648\u0627\u0644\u0645\u062E\u062F\u0631\u0627\u062A", text: "\u0627\u0644\u062A\u0631\u0648\u064A\u062C \u0644\u0644\u062E\u0645\u0648\u0631 \u0623\u0648 \u0627\u0644\u0645\u062E\u062F\u0631\u0627\u062A \u0623\u0648 \u0623\u062F\u0648\u0627\u062A\u0647\u0627." },
        { title: "\u0627\u0644\u0642\u0645\u0627\u0631", text: "\u0627\u0644\u0645\u0631\u0627\u0647\u0646\u0627\u062A \u0648\u0623\u0644\u0639\u0627\u0628 \u0627\u0644\u062D\u0638 \u0648\u0627\u0644\u0645\u0642\u0627\u0645\u0631\u0629 \u0628\u0623\u0646\u0648\u0627\u0639\u0647\u0627." },
        { title: "\u0627\u0644\u0639\u0646\u0641 \u0648\u0627\u0644\u0643\u0631\u0627\u0647\u064A\u0629", text: "\u0627\u0644\u0645\u062D\u062A\u0648\u0649 \u0627\u0644\u0639\u0646\u064A\u0641 \u0623\u0648 \u0627\u0644\u062A\u062D\u0631\u064A\u0636\u064A \u0623\u0648 \u0627\u0644\u0645\u0633\u064A\u0621 \u0644\u0623\u064A \u0641\u0626\u0629." },
        { title: "\u0627\u0644\u0645\u0646\u062A\u062C\u0627\u062A \u0627\u0644\u0645\u0642\u0644\u062F\u0629", text: "\u062A\u0642\u0644\u064A\u062F \u0627\u0644\u0639\u0644\u0627\u0645\u0627\u062A \u0627\u0644\u062A\u062C\u0627\u0631\u064A\u0629 \u0623\u0648 \u0628\u064A\u0639 \u0645\u0646\u062A\u062C\u0627\u062A \u0645\u0632\u064A\u0641\u0629." },
        { title: "\u0627\u0644\u0627\u062F\u0639\u0627\u0621\u0627\u062A \u0627\u0644\u0643\u0627\u0630\u0628\u0629", text: "\u0648\u0639\u0648\u062F \u0645\u0636\u0644\u0644\u0629\u060C \u0646\u062A\u0627\u0626\u062C \u0645\u0636\u0645\u0648\u0646\u0629 \u063A\u064A\u0631 \u0648\u0627\u0642\u0639\u064A\u0629\u060C \u0623\u0648 \u0623\u0633\u0639\u0627\u0631 \u0648\u0647\u0645\u064A\u0629." },
        { title: "\u0627\u0646\u062A\u062D\u0627\u0644 \u0627\u0644\u0647\u0648\u064A\u0629", text: "\u0627\u0644\u0638\u0647\u0648\u0631 \u0628\u0627\u0633\u0645 \u0634\u0631\u0643\u0629 \u0623\u0648 \u0634\u062E\u0635 \u0622\u062E\u0631 \u062F\u0648\u0646 \u0625\u0630\u0646." },
        { title: "\u0627\u0644\u0645\u062D\u062A\u0648\u0649 \u0627\u0644\u0633\u064A\u0627\u0633\u064A \u0627\u0644\u0645\u062D\u0631\u0651\u0636", text: "\u0627\u0644\u062F\u0639\u0627\u064A\u0629 \u0627\u0644\u0633\u064A\u0627\u0633\u064A\u0629 \u0627\u0644\u062A\u062D\u0631\u064A\u0636\u064A\u0629 \u0623\u0648 \u0627\u0644\u0645\u062B\u064A\u0631\u0629 \u0644\u0644\u0641\u062A\u0646\u0629." },
        { title: "\u0625\u0639\u0644\u0627\u0646\u0627\u062A \u062A\u062D\u0627\u0643\u064A \u0648\u0627\u062C\u0647\u0629 \u0627\u0644\u062A\u0637\u0628\u064A\u0642", text: "\u0625\u0639\u0644\u0627\u0646 \u064A\u0628\u062F\u0648 \u0643\u0632\u0631 \u0623\u0648 \u0625\u0634\u0639\u0627\u0631 \u0623\u0648 \u0631\u0633\u0627\u0644\u0629 \u0646\u0638\u0627\u0645 \u0644\u062E\u062F\u0627\u0639 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645." }
      ]
    },
    policies: [
      { id: "ads", title: "\u0633\u064A\u0627\u0633\u0629 \u0627\u0644\u0625\u0639\u0644\u0627\u0646\u0627\u062A", body: "\u2022 \u062A\u062E\u0636\u0639 \u0643\u0644 \u0645\u0627\u062F\u0629 \u0625\u0639\u0644\u0627\u0646\u064A\u0629 \u0644\u0644\u0645\u0631\u0627\u062C\u0639\u0629 \u0627\u0644\u064A\u062F\u0648\u064A\u0629 \u0642\u0628\u0644 \u0627\u0644\u0646\u0634\u0631\u060C \u0648\u0644\u0627 \u064A\u0638\u0647\u0631 \u0623\u064A \u0625\u0639\u0644\u0627\u0646 \u0642\u0628\u0644 \u0627\u0644\u0645\u0648\u0627\u0641\u0642\u0629 \u0639\u0644\u064A\u0647.\n\u2022 \u0646\u062D\u062A\u0641\u0638 \u0628\u062D\u0642 \u0631\u0641\u0636 \u0623\u0648 \u0625\u064A\u0642\u0627\u0641 \u0623\u064A \u062D\u0645\u0644\u0629 \u062A\u062E\u0627\u0644\u0641 \u0642\u0648\u0627\u0639\u062F \u0627\u0644\u0625\u0639\u0644\u0627\u0646 \u0627\u0644\u0645\u0642\u0628\u0648\u0644 \u0641\u064A \u0623\u064A \u0648\u0642\u062A\u060C \u0645\u0639 \u0628\u064A\u0627\u0646 \u0627\u0644\u0633\u0628\u0628.\n\u2022 \u064A\u0638\u0647\u0631 \u0639\u0644\u0649 \u0643\u0644 \u0625\u0639\u0644\u0627\u0646 \u0648\u0633\u0645 \xAB\u0625\u0639\u0644\u0627\u0646\xBB \u0648\u064A\u0633\u062A\u0637\u064A\u0639 \u0627\u0644\u0645\u0633\u062A\u062E\u062F\u0645 \u0625\u062E\u0641\u0627\u0621\u0647 \u0623\u0648 \u0627\u0644\u0625\u0628\u0644\u0627\u063A \u0639\u0646\u0647.\n\u2022 \u0644\u0627 \u0646\u0636\u0645\u0646 \u0639\u062F\u062F\u0627\u064B \u0645\u062D\u062F\u062F\u0627\u064B \u0645\u0646 \u0627\u0644\u0646\u0642\u0631\u0627\u062A\u061B \u0627\u0644\u062A\u0642\u062F\u064A\u0631\u0627\u062A \u0627\u0644\u0645\u0639\u0631\u0648\u0636\u0629 \u0627\u0633\u062A\u0631\u0634\u0627\u062F\u064A\u0629 \u0645\u0628\u0646\u064A\u0629 \u0639\u0644\u0649 \u0627\u0644\u0623\u0631\u0642\u0627\u0645 \u0627\u0644\u0641\u0639\u0644\u064A\u0629." },
      { id: "privacy", title: "\u0627\u0644\u062E\u0635\u0648\u0635\u064A\u0629 \u0648\u062D\u0645\u0627\u064A\u0629 \u0627\u0644\u0628\u064A\u0627\u0646\u0627\u062A", body: "\u2022 \u0646\u062D\u0645\u064A \u0628\u064A\u0627\u0646\u0627\u062A \u0627\u0644\u0645\u0639\u0644\u0646: \u0644\u0627 \u0646\u0628\u064A\u0639\u0647\u0627 \u0648\u0644\u0627 \u0646\u0634\u0627\u0631\u0643\u0647\u0627 \u0645\u0639 \u0623\u064A \u062C\u0647\u0629.\n\u2022 \u0628\u064A\u0627\u0646\u0627\u062A \u0645\u0633\u062A\u062E\u062F\u0645\u064A \u0627\u0644\u062A\u0637\u0628\u064A\u0642 \u0644\u0627 \u062A\u064F\u0634\u0627\u0631\u0643 \u0648\u0644\u0627 \u062A\u064F\u0628\u0627\u0639 \u0644\u0644\u0645\u0639\u0644\u0646\u064A\u0646 \u0625\u0637\u0644\u0627\u0642\u0627\u064B\u061B \u0627\u0644\u062A\u0642\u0627\u0631\u064A\u0631 \u062A\u062D\u0648\u064A \u0623\u0631\u0642\u0627\u0645\u0627\u064B \u0645\u062C\u0645\u0651\u0639\u0629 \u0641\u0642\u0637 (\u0645\u0634\u0627\u0647\u062F\u0627\u062A \u0648\u0646\u0642\u0631\u0627\u062A).\n\u2022 \u0646\u062E\u0632\u0651\u0646 \u0645\u062D\u0627\u062F\u062B\u0627\u062A \u0627\u0644\u0625\u0639\u0644\u0627\u0646\u0627\u062A (\u0646\u0635\u0648\u0635\u0627\u064B \u0648\u0645\u0644\u0641\u0627\u062A) \u0628\u0635\u064A\u063A\u0629 \u0645\u0634\u0641\u0651\u0631\u0629 \u0644\u063A\u0631\u0636 \u062A\u0648\u062B\u064A\u0642 \u0627\u0644\u0637\u0644\u0628 \u0648\u0627\u0644\u0627\u062A\u0641\u0627\u0642 \u0648\u0645\u062A\u0627\u0628\u0639\u0629 \u0627\u0644\u062D\u0645\u0644\u0629 \u0648\u062D\u0644 \u0623\u064A \u062E\u0644\u0627\u0641.\n\u2022 \u064A\u0645\u0643\u0646\u0643 \u0637\u0644\u0628 \u062D\u0630\u0641 \u0645\u062D\u0627\u062F\u062B\u062A\u0643 \u0648\u0645\u0644\u0641\u0627\u062A\u0643 \u0641\u064A \u0623\u064A \u0648\u0642\u062A \u0648\u0633\u064A\u064F\u062D\u0630\u0641 \u0643\u0644 \u0634\u064A\u0621 \u0645\u0646 \u062E\u0648\u0627\u062F\u0645\u0646\u0627.\n\u2022 \u0627\u0644\u062A\u0634\u0641\u064A\u0631: \u0627\u0644\u0627\u062A\u0635\u0627\u0644 \u0639\u0628\u0631 TLS \u0648\u0627\u0644\u0645\u062E\u0632\u0651\u0646 \u0645\u0634\u0641\u0651\u0631 \u0628\u062E\u0648\u0627\u062F\u0645\u0646\u0627\u060C \u0648\u0644\u064A\u0633 \u062A\u0634\u0641\u064A\u0631\u0627\u064B \u0637\u0631\u0641\u064A\u0627\u064B \u0643\u0627\u0645\u0644\u0627\u064B." },
      { id: "terms", title: "\u0627\u0644\u0634\u0631\u0648\u0637 \u0648\u0627\u0644\u0623\u062D\u0643\u0627\u0645", body: "\u2022 \u064A\u0644\u062A\u0632\u0645 \u0627\u0644\u0645\u0639\u0644\u0646 \u0628\u0635\u062D\u0629 \u0628\u064A\u0627\u0646\u0627\u062A\u0647 \u0648\u0645\u0644\u0643\u064A\u062A\u0647 \u0644\u0643\u0644 \u0645\u0627 \u064A\u0631\u0641\u0639\u0647 \u0645\u0646 \u0645\u0648\u0627\u062F.\n\u2022 \u064A\u064F\u0639\u062A\u0645\u062F \u0627\u0644\u0633\u0639\u0631 \u0627\u0644\u0645\u062A\u0641\u0642 \u0639\u0644\u064A\u0647 \u0641\u064A \u0639\u0631\u0636 \u0627\u0644\u0633\u0639\u0631 \u0627\u0644\u0645\u0642\u0628\u0648\u0644 \u062F\u0627\u062E\u0644 \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629.\n\u2022 \u062A\u0628\u062F\u0623 \u0627\u0644\u062D\u0645\u0644\u0629 \u0641\u064A \u0627\u0644\u062A\u0627\u0631\u064A\u062E \u0627\u0644\u0645\u062A\u0641\u0642 \u0639\u0644\u064A\u0647 \u0628\u0639\u062F \u0627\u0643\u062A\u0645\u0627\u0644 \u0627\u0644\u062F\u0641\u0639 \u0648\u0627\u0644\u0645\u0648\u0627\u0641\u0642\u0629 \u0639\u0644\u0649 \u0627\u0644\u0645\u0627\u062F\u0629.\n\u2022 \u0644\u0627 \u0646\u062A\u062D\u0645\u0644 \u0645\u0633\u0624\u0648\u0644\u064A\u0629 \u0623\u064A \u0646\u0632\u0627\u0639 \u0628\u064A\u0646 \u0627\u0644\u0645\u0639\u0644\u0646 \u0648\u0639\u0645\u0644\u0627\u0626\u0647." },
      { id: "payment", title: "\u0627\u0644\u062F\u0641\u0639 \u0648\u0627\u0644\u0627\u0633\u062A\u0631\u062C\u0627\u0639 \u0648\u0627\u0644\u0625\u0644\u063A\u0627\u0621", body: "\u2022 \u064A\u062A\u0645 \u0627\u0644\u062F\u0641\u0639 \u064A\u062F\u0648\u064A\u0627\u064B \u0628\u0627\u0644\u0637\u0631\u064A\u0642\u0629 \u0627\u0644\u0645\u062A\u0641\u0642 \u0639\u0644\u064A\u0647\u0627 \u0641\u064A \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629 \u0642\u0628\u0644 \u0628\u062F\u0621 \u0627\u0644\u062D\u0645\u0644\u0629.\n\u2022 \u0627\u0644\u0625\u0644\u063A\u0627\u0621 \u0642\u0628\u0644 \u0628\u062F\u0621 \u0627\u0644\u062D\u0645\u0644\u0629: \u0627\u0633\u062A\u0631\u062C\u0627\u0639 \u0643\u0627\u0645\u0644 \u0627\u0644\u0645\u0628\u0644\u063A.\n\u2022 \u0627\u0644\u0625\u0644\u063A\u0627\u0621 \u0628\u0639\u062F \u0627\u0644\u0628\u062F\u0621: \u064A\u064F\u0633\u062A\u0631\u062C\u0639 \u0645\u0627 \u064A\u0639\u0627\u062F\u0644 \u0627\u0644\u0623\u064A\u0627\u0645 \u063A\u064A\u0631 \u0627\u0644\u0645\u0633\u062A\u0647\u0644\u0643\u0629 \u0628\u0639\u062F \u062E\u0635\u0645 \u0631\u0633\u0648\u0645 \u0627\u0644\u062A\u0635\u0645\u064A\u0645 \u0625\u0646 \u0648\u064F\u062C\u062F\u062A.\n\u2022 \u0641\u064A \u062D\u0627\u0644 \u0631\u0641\u0636 \u0627\u0644\u0645\u0627\u062F\u0629 \u0644\u0645\u062E\u0627\u0644\u0641\u062A\u0647\u0627 \u0627\u0644\u0633\u064A\u0627\u0633\u0627\u062A \u064A\u064F\u0645\u0646\u062D \u0627\u0644\u0645\u0639\u0644\u0646 \u0641\u0631\u0635\u0629 \u0644\u062A\u0639\u062F\u064A\u0644\u0647\u0627 \u062F\u0648\u0646 \u0631\u0633\u0648\u0645 \u0625\u0636\u0627\u0641\u064A\u0629." },
      { id: "ip", title: "\u062D\u0642\u0648\u0642 \u0627\u0644\u0645\u0644\u0643\u064A\u0629 \u0648\u0627\u0644\u0645\u0648\u0627\u062F \u0627\u0644\u0625\u0639\u0644\u0627\u0646\u064A\u0629", body: "\u2022 \u064A\u0645\u0646\u062D \u0627\u0644\u0645\u0639\u0644\u0646 \u0627\u0644\u062A\u0637\u0628\u064A\u0642 \u062A\u0631\u062E\u064A\u0635\u0627\u064B \u0645\u062D\u062F\u0648\u062F\u0627\u064B \u0644\u0639\u0631\u0636 \u0645\u0648\u0627\u062F\u0647 \u062E\u0644\u0627\u0644 \u0645\u062F\u0629 \u0627\u0644\u062D\u0645\u0644\u0629 \u0641\u0642\u0637.\n\u2022 \u062A\u0628\u0642\u0649 \u062D\u0642\u0648\u0642 \u0627\u0644\u0645\u0644\u0643\u064A\u0629 \u0644\u0644\u0645\u0639\u0644\u0646\u060C \u0648\u0644\u0627 \u0646\u0633\u062A\u062E\u062F\u0645 \u0645\u0648\u0627\u062F\u0647\u0645 \u062E\u0627\u0631\u062C \u0627\u0644\u062D\u0645\u0644\u0629.\n\u2022 \u0627\u0644\u062A\u0635\u0627\u0645\u064A\u0645 \u0627\u0644\u062A\u064A \u064A\u0646\u062C\u0632\u0647\u0627 \u0641\u0631\u064A\u0642\u0646\u0627 \u062A\u0624\u0648\u0644 \u0645\u0644\u0643\u064A\u062A\u0647\u0627 \u0644\u0644\u0645\u0639\u0644\u0646 \u0628\u0639\u062F \u0627\u0643\u062A\u0645\u0627\u0644 \u0627\u0644\u062F\u0641\u0639." }
    ],
    policies_version: 1,
    policies_updated_at: 0,
    faq: [
      { q: "\u0643\u0645 \u064A\u0633\u062A\u063A\u0631\u0642 \u062A\u062C\u0647\u064A\u0632 \u0627\u0644\u0625\u0639\u0644\u0627\u0646\u061F", a: "\u0639\u0627\u062F\u0629 \u062E\u0644\u0627\u0644 24 \u0633\u0627\u0639\u0629 \u0645\u0646 \u0627\u0643\u062A\u0645\u0627\u0644 \u0627\u0644\u0645\u0627\u062F\u0629 \u0648\u0627\u0644\u062F\u0641\u0639." },
      { q: "\u0647\u0644 \u0623\u0633\u062A\u0637\u064A\u0639 \u062A\u063A\u064A\u064A\u0631 \u0627\u0644\u0625\u0639\u0644\u0627\u0646 \u0623\u062B\u0646\u0627\u0621 \u0627\u0644\u062D\u0645\u0644\u0629\u061F", a: "\u0646\u0639\u0645\u060C \u0623\u0631\u0633\u0644 \u0627\u0644\u0645\u0627\u062F\u0629 \u0627\u0644\u062C\u062F\u064A\u062F\u0629 \u0641\u064A \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629 \u0648\u0633\u0646\u0631\u0627\u062C\u0639\u0647\u0627 \u0648\u0646\u0633\u062A\u0628\u062F\u0644\u0647\u0627." },
      { q: "\u0643\u064A\u0641 \u0623\u0639\u0631\u0641 \u0646\u062A\u064A\u062C\u0629 \u0625\u0639\u0644\u0627\u0646\u064A\u061F", a: "\u0645\u0646 \xAB\u0625\u0639\u0644\u0627\u0646\u0627\u062A\u064A\xBB \u062A\u0631\u0649 \u0627\u0644\u0645\u0634\u0627\u0647\u062F\u0627\u062A \u0648\u0627\u0644\u0646\u0642\u0631\u0627\u062A \u0648\u0646\u0633\u0628\u0629 \u0627\u0644\u0646\u0642\u0631 \u0628\u0634\u0643\u0644 \u064A\u0648\u0645\u064A\u060C \u0648\u064A\u0645\u0643\u0646\u0643 \u062A\u062D\u0645\u064A\u0644 \u062A\u0642\u0631\u064A\u0631." },
      { q: "\u0647\u0644 \u0633\u062A\u0635\u0644 \u0628\u064A\u0627\u0646\u0627\u062A\u064A \u0644\u0644\u0645\u0633\u062A\u062E\u062F\u0645\u064A\u0646\u061F", a: "\u0644\u0627. \u0646\u0639\u0631\u0636 \u0625\u0639\u0644\u0627\u0646\u0643 \u0641\u0642\u0637\u060C \u0648\u0644\u0627 \u0646\u0634\u0627\u0631\u0643 \u0628\u064A\u0627\u0646\u0627\u062A \u0623\u064A \u0637\u0631\u0641." },
      { q: "\u0645\u0627 \u0637\u0631\u0642 \u0627\u0644\u062F\u0641\u0639\u061F", a: "\u0646\u062A\u0641\u0642 \u0639\u0644\u064A\u0647\u0627 \u0641\u064A \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629 \u0648\u0641\u0642 \u0645\u0627 \u064A\u0646\u0627\u0633\u0628\u0643." },
      { q: "\u0647\u0644 \u064A\u0645\u0643\u0646\u0646\u064A \u062A\u062C\u062F\u064A\u062F \u0627\u0644\u062D\u0645\u0644\u0629\u061F", a: "\u0646\u0639\u0645\u060C \u0632\u0631 \xAB\u062A\u062C\u062F\u064A\u062F\xBB \u0641\u064A \u0625\u0639\u0644\u0627\u0646\u0627\u062A\u064A \u064A\u0631\u0633\u0644 \u0644\u0646\u0627 \u0627\u0644\u0637\u0644\u0628 \u0645\u0628\u0627\u0634\u0631\u0629." }
    ],
    contact: { whatsapp: "", whatsapp_message: "\u0645\u0631\u062D\u0628\u0627\u064B\u060C \u0623\u0631\u063A\u0628 \u0628\u0627\u0644\u0625\u0639\u0644\u0627\u0646 \u0641\u064A \u062A\u0637\u0628\u064A\u0642 \u0633\u064A\u062C\u0631\u0627\u0645.", x_url: "", x_handle: "" },
    hours: { enabled: true, tz_offset_min: 180, from: "09:00", to: "22:00", days: [0, 1, 2, 3, 4, 6] },
    auto_reply: "\u0634\u0643\u0631\u0627\u064B \u0644\u062A\u0648\u0627\u0635\u0644\u0643. \u0646\u062D\u0646 \u062E\u0627\u0631\u062C \u0633\u0627\u0639\u0627\u062A \u0627\u0644\u0639\u0645\u0644 \u062D\u0627\u0644\u064A\u0627\u064B \u0648\u0633\u0646\u0631\u062F \u0639\u0644\u064A\u0643 \u0641\u0648\u0631 \u0639\u0648\u062F\u062A\u0646\u0627.",
    hero: { title: "\u0627\u062C\u0639\u0644 \u0639\u0644\u0627\u0645\u062A\u0643 \u0623\u0645\u0627\u0645 \u062C\u0645\u0647\u0648\u0631 \u062D\u0642\u064A\u0642\u064A", subtitle: "\u0625\u0639\u0644\u0627\u0646\u0627\u062A \u0645\u062F\u0631\u0648\u0633\u0629 \u062F\u0627\u062E\u0644 \u062A\u0637\u0628\u064A\u0642 \u064A\u0634\u0627\u0647\u062F\u0647 \u0627\u0644\u0622\u0644\u0627\u0641 \u064A\u0648\u0645\u064A\u0627\u064B.", manual_users: 0, manual_daily_views: 0 }
  };
}
__name(defaultSettings, "defaultSettings");
function computeQuote(settings, input) {
  const slotKey = oneOf(input.slot, SLOTS, null);
  if (!slotKey) fail(400, "invalid_slot", "\u0627\u062E\u062A\u0631 \u0645\u0633\u0627\u062D\u0629 \u0625\u0639\u0644\u0627\u0646\u064A\u0629 \u0635\u062D\u064A\u062D\u0629.");
  const slot = settings.slots[slotKey];
  if (!slot || slot.enabled === false) fail(400, "slot_disabled", "\u0647\u0630\u0647 \u0627\u0644\u0645\u0633\u0627\u062D\u0629 \u063A\u064A\u0631 \u0645\u062A\u0627\u062D\u0629 \u062D\u0627\u0644\u064A\u0627\u064B.");
  const cd = settings.custom_days || { min: 1, max: 730 };
  const days = int(input.days, cd.min || 1, cd.max || 730, null);
  if (!days) fail(400, "invalid_days", "\u062D\u062F\u062F \u0645\u062F\u0629 \u0635\u062D\u064A\u062D\u0629 \u0628\u0627\u0644\u0623\u064A\u0627\u0645.");
  const base = round2(slot.price_per_day * days);
  let pct = 0;
  for (const d of settings.discounts || []) if (days >= d.min_days && d.pct > pct) pct = d.pct;
  const discount = round2(base * pct / 100);
  const subtotal = round2(base - discount);
  const t = settings.targeting || {};
  const tg = input.targeting || {};
  const hasCity = Array.isArray(tg.cities) && tg.cities.length > 0;
  const hasLang = Array.isArray(tg.langs) && tg.langs.length > 0;
  const hasHours = !!(tg.hours && tg.hours.from !== void 0 && tg.hours.to !== void 0 && tg.hours !== null);
  const lines = [];
  let targeting = 0;
  if (hasCity) {
    const v = round2(subtotal * (t.city_pct || 0) / 100);
    targeting += v;
    lines.push({ key: "city", amount: v });
  }
  if (hasLang) {
    const v = round2(subtotal * (t.lang_pct || 0) / 100);
    targeting += v;
    lines.push({ key: "lang", amount: v });
  }
  if (hasHours) {
    const v = round2(subtotal * (t.hours_pct || 0) / 100);
    targeting += v;
    lines.push({ key: "hours", amount: v });
  }
  targeting = round2(targeting);
  const ad = input.addons || {};
  const addonLines = [];
  let addons = 0;
  for (const key of Object.keys(settings.addons || {})) {
    if (!bool(ad[key], false)) continue;
    const a = settings.addons[key];
    const v = a.type === "pct" ? round2(subtotal * a.value / 100) : round2(a.value);
    addons += v;
    addonLines.push({ key, label: a.label, amount: v });
  }
  addons = round2(addons);
  const total = round2(subtotal + targeting + addons);
  let reach = (slot.views_per_day || 0) * days;
  if (hasCity) reach *= t.city_reach_factor || 1;
  if (hasLang) reach *= t.lang_reach_factor || 1;
  if (hasHours) reach *= t.hours_reach_factor || 1;
  reach = Math.round(reach);
  return {
    slot: slotKey,
    days,
    base,
    discount_pct: pct,
    discount,
    subtotal,
    targeting_total: targeting,
    targeting_lines: lines,
    addons_total: addons,
    addon_lines: addonLines,
    total,
    currency: settings.currency,
    estimated_views: reach,
    estimated_views_min: Math.round(reach * 0.8),
    estimated_views_max: Math.round(reach * 1.2)
  };
}
__name(computeQuote, "computeQuote");
function bucketOf(env) {
  return env.ADS_BUCKET || env.MOVIES_BUCKET || null;
}
__name(bucketOf, "bucketOf");
function mediaUrl(origin, key) {
  if (!key) return "";
  if (key.indexOf(MEDIA_PREFIX) === 0) return origin + "/m/c/" + key.slice(MEDIA_PREFIX.length);
  if (key.indexOf("ads/logos/") === 0) return origin + "/m/l/" + key.slice("ads/logos/".length);
  return "";
}
__name(mediaUrl, "mediaUrl");
function normName(s) {
  return String(s || "").toLowerCase().replace(/محافظة|governorate|province|state of/g, " ").replace(/[^\p{L}\p{N}' ]/gu, " ").replace(/\s+/g, " ").trim();
}
__name(normName, "normName");
function resolveCity(settings, ...candidates) {
  const cities = settings.targeting && settings.targeting.cities || [];
  for (const raw of candidates) {
    const n = normName(raw);
    if (!n) continue;
    for (const c of cities) {
      if (normName(c.id) === n || normName(c.ar) === n) return c.id;
      for (const a of c.aliases || []) if (normName(a) === n) return c.id;
    }
  }
  return "";
}
__name(resolveCity, "resolveCity");
function sanitizeTargeting(t) {
  t = t && typeof t === "object" ? t : {};
  let hours = null;
  if (t.hours && typeof t.hours === "object" && t.hours.from !== void 0 && t.hours.to !== void 0) {
    const from = int(t.hours.from, 0, 23, null);
    const to = int(t.hours.to, 0, 24, null);
    if (from !== null && to !== null && from !== to) hours = { from, to };
  }
  return {
    cities: strList(t.cities, 30, 40).map((x) => x.toLowerCase()),
    langs: strList(t.langs, 10, 8).map((x) => x.toLowerCase().slice(0, 2)),
    hours,
    min_version: int(t.min_version, 0, 1e9, 0),
    max_version: int(t.max_version, 0, 1e9, 0)
  };
}
__name(sanitizeTargeting, "sanitizeTargeting");
function inHours(h, hours) {
  if (!hours) return true;
  if (hours.from < hours.to) return h >= hours.from && h < hours.to;
  return h >= hours.from || h < hours.to;
}
__name(inHours, "inHours");
function displayStage(c, now) {
  if (c.status === "rejected") return "rejected";
  if (c.status === "approved" || c.status === "paused") {
    if (c.status === "paused") return "paused";
    if (now < c.start_at) return "scheduled";
    if (now >= c.end_at) return "ended";
    return "running";
  }
  return c.order_stage || "received";
}
__name(displayStage, "displayStage");
var AdsCore = class {
  static {
    __name(this, "AdsCore");
  }
  constructor(state, env) {
    this.state = state;
    this.s = state.storage;
    this.env = env;
    this._settings = null;
    this._cache = null;
    this._rl = /* @__PURE__ */ new Map();
  }
  async fetch(request) {
    let body;
    try {
      body = await request.json();
    } catch (e) {
      return new Response(JSON.stringify({ ok: false, error: "invalid_json", message: "\u0628\u064A\u0627\u0646\u0627\u062A \u063A\u064A\u0631 \u0635\u0627\u0644\u062D\u0629." }), { status: 400 });
    }
    try {
      const fn = this["op_" + body.op];
      if (typeof fn !== "function") fail(404, "unknown_op", "\u0639\u0645\u0644\u064A\u0629 \u063A\u064A\u0631 \u0645\u0639\u0631\u0648\u0641\u0629.");
      const out = await fn.call(this, body.p || {});
      return new Response(JSON.stringify(Object.assign({ ok: true }, out || {})), { status: 200 });
    } catch (e) {
      const [b, st] = errorBody(e);
      if (!(e instanceof HttpError)) console.log("core_error", body && body.op, String(e && e.message));
      return new Response(JSON.stringify(b), { status: st });
    }
  }
  // ------------------------------------------------------------------ infra
  rl(key, limit, windowMs) {
    const now = Date.now();
    if (this._rl.size > 8e3) this._rl.clear();
    let r = this._rl.get(key);
    if (!r || now > r.reset) {
      r = { n: 0, reset: now + windowMs };
      this._rl.set(key, r);
    }
    r.n++;
    return r.n <= limit;
  }
  async ensureAlarm() {
    try {
      if (!await this.s.getAlarm()) await this.s.setAlarm(Date.now() + 36e5);
    } catch (e) {
    }
  }
  async settings() {
    if (this._settings) return this._settings;
    const stored = await this.s.get("settings") || {};
    const d = defaultSettings();
    const merged = Object.assign({}, d, stored);
    for (const k of ["slots", "addons", "targeting", "contact", "hours", "hero", "rules", "currency", "custom_days"]) {
      merged[k] = Object.assign({}, d[k], stored[k] || {});
    }
    for (const k of Object.keys(d.slots)) merged.slots[k] = Object.assign({}, d.slots[k], (stored.slots || {})[k] || {});
    for (const k of Object.keys(d.addons)) merged.addons[k] = Object.assign({}, d.addons[k], (stored.addons || {})[k] || {});
    this._settings = merged;
    return merged;
  }
  async load() {
    if (this._cache) return this._cache;
    const camps = await this.s.list({ prefix: "camp:" });
    const crvs = await this.s.list({ prefix: "crv:" });
    this._cache = { camps, crvs };
    return this._cache;
  }
  dirty() {
    this._cache = null;
  }
  log(c, action, note) {
    c.log = Array.isArray(c.log) ? c.log : [];
    c.log.push({ t: Date.now(), a: action, n: cleanText(note, 200) });
    if (c.log.length > 60) c.log = c.log.slice(-60);
  }
  // ------------------------------------------------------------------ settings
  async op_settings_public(p) {
    const st = await this.settings();
    const now = Date.now();
    const out = JSON.parse(JSON.stringify(st));
    out.hero = await this.heroNumbers(st);
    out.server_time = now;
    return { settings: out };
  }
  async op_settings_admin() {
    const st = await this.settings();
    return { settings: st, hero_measured: await this.heroNumbers(Object.assign({}, st, { hero: Object.assign({}, st.hero, { manual_users: 0, manual_daily_views: 0 }) })) };
  }
  async heroNumbers(st) {
    const now = Date.now();
    const off = st.hours && st.hours.tz_offset_min || 180;
    let dauSum = 0, dauDays = 0, viewsSum = 0;
    for (let i = 1; i <= 7; i++) {
      const dk = dayKey(now - i * DAY_MS, off);
      const dau = await this.s.get("dau:" + dk);
      const views = await this.s.get("day:" + dk);
      if (dau) {
        dauSum += dau;
        dauDays++;
      }
      if (views) viewsSum += views;
    }
    const measuredUsers = dauDays ? Math.round(dauSum / dauDays) : 0;
    const measuredViews = dauDays ? Math.round(viewsSum / dauDays) : 0;
    const mu = st.hero && st.hero.manual_users || 0;
    const mv = st.hero && st.hero.manual_daily_views || 0;
    return {
      title: st.hero.title,
      subtitle: st.hero.subtitle,
      active_users: mu > 0 ? mu : measuredUsers,
      avg_daily_views: mv > 0 ? mv : measuredViews,
      source: mu > 0 || mv > 0 ? "manual" : "measured"
    };
  }
  async op_settings_set(p) {
    const cur = await this.settings();
    const patch = p.patch && typeof p.patch === "object" ? p.patch : {};
    const next = JSON.parse(JSON.stringify(cur));
    const d = defaultSettings();
    if (patch.currency) {
      next.currency = {
        code: cleanText(patch.currency.code, 6).toUpperCase() || "USD",
        symbol: cleanText(patch.currency.symbol, 6) || "$",
        decimals: int(patch.currency.decimals, 0, 3, 0)
      };
    }
    if (patch.slots && typeof patch.slots === "object") {
      for (const k of SLOTS) {
        const s = patch.slots[k];
        if (!s || typeof s !== "object") continue;
        const t = next.slots[k];
        if (s.label !== void 0) t.label = reqText(s.label, 60, "\u0627\u0633\u0645 \u0627\u0644\u0645\u0633\u0627\u062D\u0629");
        if (s.desc !== void 0) t.desc = cleanText(s.desc, 400);
        if (s.suited !== void 0) t.suited = cleanText(s.suited, 200);
        if (s.price_per_day !== void 0) t.price_per_day = num(s.price_per_day, 0, 1e7, t.price_per_day);
        if (s.views_per_day !== void 0) t.views_per_day = int(s.views_per_day, 0, 1e9, t.views_per_day);
        if (s.enabled !== void 0) t.enabled = bool(s.enabled, true);
      }
    }
    if (Array.isArray(patch.durations)) {
      const arr = patch.durations.map((x) => int(x, 1, 730, null)).filter((x) => x).slice(0, 12);
      if (arr.length) next.durations = Array.from(new Set(arr)).sort((a, b) => a - b);
    }
    if (patch.custom_days) {
      next.custom_days = { min: int(patch.custom_days.min, 1, 365, 1), max: int(patch.custom_days.max, 1, 730, 730) };
      if (next.custom_days.max < next.custom_days.min) next.custom_days.max = next.custom_days.min;
    }
    if (Array.isArray(patch.discounts)) {
      next.discounts = patch.discounts.map((x) => ({ min_days: int(x && x.min_days, 1, 730, 0), pct: num(x && x.pct, 0, 90, 0) })).filter((x) => x.min_days > 0).slice(0, 12).sort((a, b) => a.min_days - b.min_days);
    }
    if (patch.addons && typeof patch.addons === "object") {
      for (const k of Object.keys(d.addons)) {
        const a = patch.addons[k];
        if (!a || typeof a !== "object") continue;
        const t = next.addons[k];
        if (a.label !== void 0) t.label = reqText(a.label, 50, "\u0627\u0633\u0645 \u0627\u0644\u0625\u0636\u0627\u0641\u0629");
        if (a.desc !== void 0) t.desc = cleanText(a.desc, 200);
        if (a.type !== void 0) t.type = oneOf(a.type, ["pct", "flat"], t.type);
        if (a.value !== void 0) t.value = num(a.value, 0, 1e7, t.value);
      }
    }
    if (patch.targeting && typeof patch.targeting === "object") {
      const t = patch.targeting, n = next.targeting;
      for (const k of ["city_pct", "lang_pct", "hours_pct"]) if (t[k] !== void 0) n[k] = num(t[k], 0, 500, n[k]);
      for (const k of ["city_reach_factor", "lang_reach_factor", "hours_reach_factor"]) if (t[k] !== void 0) n[k] = num(t[k], 0.01, 1, n[k]);
      if (Array.isArray(t.cities)) {
        n.cities = t.cities.slice(0, 40).map((c) => ({
          id: cleanText(c && c.id, 30).toLowerCase().replace(/[^a-z0-9_]/g, ""),
          ar: cleanText(c && c.ar, 40),
          aliases: strList(c && c.aliases, 10, 40)
        })).filter((c) => c.id && c.ar);
      }
      if (Array.isArray(t.langs)) {
        n.langs = t.langs.slice(0, 12).map((l) => ({ id: cleanText(l && l.id, 8).toLowerCase(), label: cleanText(l && l.label, 30) })).filter((l) => l.id && l.label);
      }
    }
    if (patch.rules && typeof patch.rules === "object") {
      for (const k of ["accepted", "rejected"]) {
        if (Array.isArray(patch.rules[k])) {
          next.rules[k] = patch.rules[k].slice(0, 30).map((x) => ({ title: cleanText(x && x.title, 80), text: cleanText(x && x.text, 400) })).filter((x) => x.title);
        }
      }
    }
    if (Array.isArray(patch.faq)) {
      next.faq = patch.faq.slice(0, 30).map((x) => ({ q: cleanText(x && x.q, 200), a: cleanText(x && x.a, 1500) })).filter((x) => x.q && x.a);
    }
    if (patch.contact && typeof patch.contact === "object") {
      const c = patch.contact;
      if (c.whatsapp !== void 0) next.contact.whatsapp = digitsOnly(c.whatsapp, 18);
      if (c.whatsapp_message !== void 0) next.contact.whatsapp_message = cleanText(c.whatsapp_message, 300);
      if (c.x_url !== void 0) next.contact.x_url = c.x_url ? safeUrl(c.x_url, ["https:"]) : "";
      if (c.x_handle !== void 0) next.contact.x_handle = cleanText(c.x_handle, 40).replace(/^@/, "");
    }
    if (patch.hours && typeof patch.hours === "object") {
      const h = patch.hours;
      const hhmm = /* @__PURE__ */ __name((v, def) => /^([01]\d|2[0-3]):[0-5]\d$/.test(String(v || "")) ? String(v) : def, "hhmm");
      next.hours = {
        enabled: bool(h.enabled, next.hours.enabled),
        tz_offset_min: int(h.tz_offset_min, -720, 840, next.hours.tz_offset_min),
        from: hhmm(h.from, next.hours.from),
        to: hhmm(h.to, next.hours.to),
        days: Array.isArray(h.days) ? Array.from(new Set(h.days.map((x) => int(x, 0, 6, null)).filter((x) => x !== null))) : next.hours.days
      };
    }
    if (patch.auto_reply !== void 0) next.auto_reply = cleanText(patch.auto_reply, 400);
    if (patch.hero && typeof patch.hero === "object") {
      const h = patch.hero;
      if (h.title !== void 0) next.hero.title = cleanText(h.title, 90);
      if (h.subtitle !== void 0) next.hero.subtitle = cleanText(h.subtitle, 200);
      if (h.manual_users !== void 0) next.hero.manual_users = int(h.manual_users, 0, 1e9, 0);
      if (h.manual_daily_views !== void 0) next.hero.manual_daily_views = int(h.manual_daily_views, 0, 1e10, 0);
    }
    if (Array.isArray(patch.policies)) {
      const before = JSON.stringify(cur.policies);
      next.policies = patch.policies.slice(0, 12).map((x, i) => ({
        id: cleanText(x && x.id, 20).replace(/[^a-z0-9_]/gi, "") || "p" + i,
        title: cleanText(x && x.title, 80),
        body: cleanText(x && x.body, 12e3)
      })).filter((x) => x.title && x.body);
      if (JSON.stringify(next.policies) !== before && !bool(patch.keep_policies_version, false)) {
        next.policies_version = (cur.policies_version || 1) + 1;
        next.policies_updated_at = Date.now();
      }
    }
    if (bool(patch.bump_policies_version, false)) {
      next.policies_version = (next.policies_version || 1) + 1;
      next.policies_updated_at = Date.now();
    }
    next.version = (cur.version || 1) + 1;
    next.updated_at = Date.now();
    await this.s.put("settings", next);
    this._settings = null;
    return { settings: await this.settings() };
  }
  async op_quote(p) {
    const st = await this.settings();
    return { quote: computeQuote(st, p) };
  }
  // ------------------------------------------------------------------ advertisers
  async op_adv_list(p) {
    const m = await this.s.list({ prefix: "adv:" });
    const { camps } = await this.load();
    const now = Date.now();
    const q = cleanText(p.q, 60).toLowerCase();
    const out = [];
    for (const a of m.values()) {
      if (q && (a.name || "").toLowerCase().indexOf(q) < 0) continue;
      if (!p.archived && a.archived) continue;
      let count = 0, running = 0, paid = 0;
      for (const c of camps.values()) {
        if (c.advertiser_id !== a.id) continue;
        count++;
        paid += c.paid_amount || 0;
        if (displayStage(c, now) === "running") running++;
      }
      out.push(this.advView(a, p.origin, { campaigns: count, running, paid_total: round2(paid) }));
    }
    out.sort((x, y) => (y.updated_at || 0) - (x.updated_at || 0));
    return { advertisers: out };
  }
  advView(a, origin, extra) {
    return Object.assign({
      id: a.id,
      name: a.name,
      logo_key: a.logo_key || "",
      logo_url: mediaUrl(origin || "", a.logo_key),
      contact: a.contact || {},
      notes: a.notes || "",
      user_key: a.user_key || "",
      banned: !!a.banned,
      archived: !!a.archived,
      tags: a.tags || [],
      created_at: a.created_at,
      updated_at: a.updated_at
    }, extra || {});
  }
  async op_adv_get(p) {
    const a = await this.s.get("adv:" + p.id);
    if (!a) fail(404, "not_found", "\u0627\u0644\u0645\u0639\u0644\u0646 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F.");
    const { camps } = await this.load();
    const now = Date.now();
    const list = [];
    let paid = 0;
    for (const c of camps.values()) {
      if (c.advertiser_id !== a.id) continue;
      paid += c.paid_amount || 0;
      list.push({ id: c.id, name: c.name, slot: c.slot, stage: displayStage(c, now), start_at: c.start_at, end_at: c.end_at, price: c.price, paid_amount: c.paid_amount || 0, paid_status: c.paid_status });
    }
    list.sort((x, y) => y.start_at - x.start_at);
    return { advertiser: this.advView(a, p.origin, { campaigns: list.length, paid_total: round2(paid) }), campaigns: list };
  }
  async op_adv_save(p) {
    const existing = p.id ? await this.s.get("adv:" + p.id) : null;
    if (p.id && !existing) fail(404, "not_found", "\u0627\u0644\u0645\u0639\u0644\u0646 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F.");
    const now = Date.now();
    const c = p.contact && typeof p.contact === "object" ? p.contact : existing && existing.contact || {};
    let logo = existing ? existing.logo_key || "" : "";
    if (p.logo_key !== void 0) {
      logo = cleanText(p.logo_key, 200);
      if (logo && logo.indexOf("ads/logos/") !== 0) fail(400, "invalid_media", "\u0645\u0633\u0627\u0631 \u0627\u0644\u0634\u0639\u0627\u0631 \u063A\u064A\u0631 \u0635\u0627\u0644\u062D.");
    }
    const a = {
      id: existing ? existing.id : randId("a_", 9),
      name: reqText(p.name !== void 0 ? p.name : existing && existing.name, 100, "\u0627\u0633\u0645 \u0627\u0644\u0634\u0631\u0643\u0629"),
      logo_key: logo,
      contact: {
        phone: digitsOnly(c.phone, 18),
        whatsapp: digitsOnly(c.whatsapp, 18),
        email: cleanText(c.email, 120),
        website: cleanText(c.website, 200),
        address: cleanText(c.address, 200),
        person: cleanText(c.person, 80)
      },
      notes: p.notes !== void 0 ? cleanText(p.notes, 3e3) : existing && existing.notes || "",
      user_key: existing ? existing.user_key || "" : cleanText(p.user_key, 120),
      banned: p.banned !== void 0 ? bool(p.banned, false) : !!(existing && existing.banned),
      archived: p.archived !== void 0 ? bool(p.archived, false) : !!(existing && existing.archived),
      tags: p.tags !== void 0 ? strList(p.tags, 10, 24) : existing && existing.tags || [],
      created_at: existing ? existing.created_at : now,
      updated_at: now
    };
    await this.s.put("adv:" + a.id, a);
    if (a.user_key) await this.s.put("advuser:" + a.user_key, a.id);
    return { advertiser: this.advView(a, p.origin) };
  }
  async op_adv_ensure_user(p) {
    const key = cleanText(p.user_key, 120);
    if (!key) fail(400, "invalid_user", "\u0645\u0633\u062A\u062E\u062F\u0645 \u063A\u064A\u0631 \u0635\u0627\u0644\u062D.");
    const id = await this.s.get("advuser:" + key);
    if (id) {
      const a2 = await this.s.get("adv:" + id);
      if (a2) return { advertiser: this.advView(a2, p.origin), created: false };
    }
    const now = Date.now();
    const a = {
      id: randId("a_", 9),
      name: cleanText(p.name, 100) || "\u0645\u0639\u0644\u0646 \u062C\u062F\u064A\u062F",
      logo_key: "",
      contact: { email: cleanText(p.email, 120) },
      notes: "",
      user_key: key,
      banned: false,
      archived: false,
      tags: ["\u062C\u062F\u064A\u062F"],
      created_at: now,
      updated_at: now
    };
    await this.s.put("adv:" + a.id, a);
    await this.s.put("advuser:" + key, a.id);
    return { advertiser: this.advView(a, p.origin), created: true };
  }
  async op_adv_by_user(p) {
    const id = await this.s.get("advuser:" + cleanText(p.user_key, 120));
    if (!id) return { advertiser: null };
    const a = await this.s.get("adv:" + id);
    return { advertiser: a ? this.advView(a, p.origin) : null };
  }
  async op_adv_delete(p) {
    const a = await this.s.get("adv:" + p.id);
    if (!a) fail(404, "not_found", "\u0627\u0644\u0645\u0639\u0644\u0646 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F.");
    const { camps } = await this.load();
    const mine = [];
    for (const c of camps.values()) if (c.advertiser_id === a.id) mine.push(c);
    if (mine.length && !bool(p.force, false)) fail(409, "has_campaigns", "\u0644\u062F\u0649 \u0627\u0644\u0645\u0639\u0644\u0646 \u062D\u0645\u0644\u0627\u062A. \u0623\u0631\u0634\u0641\u0647 \u0623\u0648 \u0627\u062D\u0630\u0641\u0647 \u0645\u0639 \u062D\u0645\u0644\u0627\u062A\u0647 (force).", { campaigns: mine.length });
    for (const c of mine) await this.deleteCampaign(c);
    if (a.logo_key) await this.deleteMedia(a.logo_key, "");
    await this.s.delete("adv:" + a.id);
    if (a.user_key) await this.s.delete("advuser:" + a.user_key);
    return { deleted: true, user_key: a.user_key || "" };
  }
  // ------------------------------------------------------------------ campaigns
  campView(c, now, origin, advMap) {
    const adv = advMap ? advMap.get(c.advertiser_id) : null;
    return {
      id: c.id,
      advertiser_id: c.advertiser_id,
      advertiser_name: adv ? adv.name : "",
      name: c.name,
      slot: c.slot,
      start_at: c.start_at,
      end_at: c.end_at,
      duration_days: c.duration_days,
      price_calc: c.price_calc,
      price: c.price,
      price_manual: !!c.price_manual,
      currency: c.currency,
      paid_status: c.paid_status,
      paid_amount: c.paid_amount || 0,
      priority: c.priority,
      exclusive: !!(c.addons && c.addons.exclusive),
      addons: c.addons || {},
      daily_cap: c.daily_cap || 0,
      targeting: c.targeting,
      status: c.status,
      order_stage: c.order_stage,
      stage: displayStage(c, now),
      reject_reason: c.reject_reason || "",
      approved_at: c.approved_at || 0,
      notes: c.notes || "",
      report_token: c.report_token || "",
      reports: c.reports || 0,
      expiring_soon: c.status === "approved" && c.end_at > now && c.end_at - now <= 3 * DAY_MS,
      log: (c.log || []).slice(-20),
      created_at: c.created_at,
      updated_at: c.updated_at
    };
  }
  async advMap() {
    const m = await this.s.list({ prefix: "adv:" });
    const out = /* @__PURE__ */ new Map();
    for (const a of m.values()) out.set(a.id, a);
    return out;
  }
  async op_camp_list(p) {
    const { camps } = await this.load();
    const advs = await this.advMap();
    const now = Date.now();
    const q = cleanText(p.q, 60).toLowerCase();
    const out = [];
    for (const c of camps.values()) {
      if (p.advertiser_id && c.advertiser_id !== p.advertiser_id) continue;
      if (p.slot && c.slot !== p.slot) continue;
      const v = this.campView(c, now, p.origin, advs);
      if (p.stage && v.stage !== p.stage) continue;
      if (p.expiring && !v.expiring_soon) continue;
      if (q && (v.name + " " + v.advertiser_name).toLowerCase().indexOf(q) < 0) continue;
      const tot = await this.s.get("tot:" + c.id) || { i: 0, c: 0, u: 0 };
      v.totals = { impressions: tot.i, clicks: tot.c, unique: tot.u };
      v.creatives_count = 0;
      out.push(v);
    }
    const { crvs } = await this.load();
    const counts = {};
    for (const r of crvs.values()) counts[r.campaign_id] = (counts[r.campaign_id] || 0) + 1;
    for (const v of out) v.creatives_count = counts[v.id] || 0;
    out.sort((a, b) => b.updated_at - a.updated_at);
    return { campaigns: out.slice(0, int(p.limit, 1, 500, 200)) };
  }
  async op_camp_get(p) {
    const c = await this.s.get("camp:" + p.id);
    if (!c) fail(404, "not_found", "\u0627\u0644\u062D\u0645\u0644\u0629 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F\u0629.");
    const advs = await this.advMap();
    const { crvs } = await this.load();
    const list = [];
    for (const r of crvs.values()) if (r.campaign_id === c.id) list.push(this.crvView(r, p.origin));
    list.sort((a, b) => a.sort - b.sort);
    const tot = await this.s.get("tot:" + c.id) || { i: 0, c: 0, u: 0 };
    const v = this.campView(c, Date.now(), p.origin, advs);
    v.totals = { impressions: tot.i, clicks: tot.c, unique: tot.u };
    return { campaign: v, creatives: list };
  }
  async op_camp_save(p) {
    const st = await this.settings();
    const existing = p.id ? await this.s.get("camp:" + p.id) : null;
    if (p.id && !existing) fail(404, "not_found", "\u0627\u0644\u062D\u0645\u0644\u0629 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F\u0629.");
    const advId = cleanText(p.advertiser_id !== void 0 ? p.advertiser_id : existing && existing.advertiser_id, 40);
    const adv = advId ? await this.s.get("adv:" + advId) : null;
    if (!adv) fail(400, "invalid_advertiser", "\u0627\u062E\u062A\u0631 \u0645\u0639\u0644\u0646\u0627\u064B \u0635\u062D\u064A\u062D\u0627\u064B.");
    const slot = oneOf(p.slot !== void 0 ? p.slot : existing && existing.slot, SLOTS, null);
    if (!slot) fail(400, "invalid_slot", "\u0627\u062E\u062A\u0631 \u0645\u0633\u0627\u062D\u0629 \u0625\u0639\u0644\u0627\u0646\u064A\u0629.");
    const now = Date.now();
    const start = parseTime(p.start_at, existing ? existing.start_at : null);
    if (!start) fail(400, "invalid_time", "\u062D\u062F\u062F \u062A\u0627\u0631\u064A\u062E \u0627\u0644\u0628\u062F\u0627\u064A\u0629.");
    let end = null;
    if (p.end_at !== void 0 && p.end_at !== null && p.end_at !== "") end = parseTime(p.end_at, null);
    else if (p.days !== void 0 && p.days !== null && p.days !== "") end = start + int(p.days, 1, MAX_CAMPAIGN_DAYS, 1) * DAY_MS;
    else if (existing) end = existing.end_at;
    if (!end || end <= start) fail(400, "invalid_time", "\u062A\u0627\u0631\u064A\u062E \u0627\u0644\u0646\u0647\u0627\u064A\u0629 \u064A\u062C\u0628 \u0623\u0646 \u064A\u0643\u0648\u0646 \u0628\u0639\u062F \u0627\u0644\u0628\u062F\u0627\u064A\u0629.");
    if (end - start > MAX_CAMPAIGN_DAYS * DAY_MS) fail(400, "invalid_time", "\u0627\u0644\u0645\u062F\u0629 \u0623\u0637\u0648\u0644 \u0645\u0646 \u0627\u0644\u062D\u062F \u0627\u0644\u0645\u0633\u0645\u0648\u062D.");
    const days = Math.max(1, Math.round((end - start) / DAY_MS));
    const targeting = p.targeting !== void 0 ? sanitizeTargeting(p.targeting) : existing && existing.targeting || sanitizeTargeting({});
    const addIn = p.addons && typeof p.addons === "object" ? p.addons : existing && existing.addons || {};
    const addons = { exclusive: bool(p.exclusive !== void 0 ? p.exclusive : addIn.exclusive, false), video: bool(addIn.video, false), design: bool(addIn.design, false) };
    let calc = 0;
    try {
      calc = computeQuote(st, { slot, days, targeting, addons }).total;
    } catch (e) {
      if (!(e instanceof HttpError)) throw e;
    }
    let price, manual = false;
    if (p.price !== void 0 && p.price !== null && p.price !== "") {
      price = round2(num(p.price, 0, 1e9, calc));
      manual = round2(price) !== round2(calc);
    } else if (existing && existing.price_manual) {
      price = existing.price;
      manual = true;
    } else price = calc;
    let paidStatus = oneOf(p.paid_status !== void 0 ? p.paid_status : existing && existing.paid_status, PAID, "unpaid");
    let paidAmount = p.paid_amount !== void 0 && p.paid_amount !== "" ? round2(num(p.paid_amount, 0, 1e9, 0)) : existing ? existing.paid_amount || 0 : 0;
    if (paidStatus === "paid" && paidAmount < price) paidAmount = price;
    if (paidStatus === "unpaid") paidAmount = 0;
    const c = {
      id: existing ? existing.id : randId("c_", 9),
      advertiser_id: adv.id,
      name: reqText(p.name !== void 0 ? p.name : existing && existing.name, 120, "\u0627\u0633\u0645 \u0627\u0644\u062D\u0645\u0644\u0629"),
      slot,
      start_at: start,
      end_at: end,
      duration_days: days,
      price_calc: calc,
      price,
      price_manual: manual,
      currency: st.currency.code,
      paid_status: paidStatus,
      paid_amount: paidAmount,
      priority: int(p.priority !== void 0 ? p.priority : existing && existing.priority, 1, 10, 5),
      addons,
      daily_cap: int(p.daily_cap !== void 0 ? p.daily_cap : existing && existing.daily_cap, 0, 50, 0),
      targeting,
      status: existing ? existing.status : "draft",
      order_stage: oneOf(p.order_stage !== void 0 ? p.order_stage : existing && existing.order_stage, STAGES, "received"),
      reject_reason: existing ? existing.reject_reason || "" : "",
      approved_at: existing ? existing.approved_at || 0 : 0,
      notes: p.notes !== void 0 ? cleanText(p.notes, 3e3) : existing && existing.notes || "",
      report_token: existing ? existing.report_token || "" : "",
      reports: existing ? existing.reports || 0 : 0,
      expiry_warned: existing && existing.end_at === end ? !!existing.expiry_warned : false,
      source_order: cleanText(p.source_order, 60) || existing && existing.source_order || "",
      log: existing ? existing.log || [] : [],
      created_at: existing ? existing.created_at : now,
      updated_at: now
    };
    if (c.status === "approved") await this.checkExclusive(c);
    this.log(c, existing ? "edit" : "create", "");
    await this.s.put("camp:" + c.id, c);
    this.dirty();
    await this.ensureAlarm();
    return { campaign: this.campView(c, now, p.origin, /* @__PURE__ */ new Map([[adv.id, adv]])) };
  }
  async checkExclusive(c) {
    const { camps } = await this.load();
    for (const o of camps.values()) {
      if (o.id === c.id || o.status !== "approved" || o.slot !== c.slot) continue;
      const overlap = o.start_at < c.end_at && c.start_at < o.end_at;
      if (!overlap) continue;
      const oEx = !!(o.addons && o.addons.exclusive);
      const cEx = !!(c.addons && c.addons.exclusive);
      if (oEx || cEx) {
        fail(409, "exclusive_conflict", "\u062A\u0639\u0627\u0631\u0636 \u062D\u0635\u0631\u064A\u0629: \u0627\u0644\u062D\u0645\u0644\u0629 \xAB" + o.name + "\xBB \u062A\u0634\u063A\u0644 \u0646\u0641\u0633 \u0627\u0644\u0645\u0633\u0627\u062D\u0629 \u0641\u064A \u0641\u062A\u0631\u0629 \u0645\u062A\u062F\u0627\u062E\u0644\u0629.", { conflict_id: o.id });
      }
    }
  }
  async op_camp_action(p) {
    const c = await this.s.get("camp:" + p.id);
    if (!c) fail(404, "not_found", "\u0627\u0644\u062D\u0645\u0644\u0629 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F\u0629.");
    const now = Date.now();
    const act = cleanText(p.action, 20);
    let extra = {};
    if (act === "approve") {
      const { crvs } = await this.load();
      let n = 0;
      for (const r of crvs.values()) if (r.campaign_id === c.id && r.active !== false) n++;
      if (!n) fail(400, "no_creative", "\u0623\u0636\u0641 \u0645\u0627\u062F\u0629 \u0625\u0639\u0644\u0627\u0646\u064A\u0629 \u0641\u0639\u0651\u0627\u0644\u0629 \u0648\u0627\u062D\u062F\u0629 \u0639\u0644\u0649 \u0627\u0644\u0623\u0642\u0644 \u0642\u0628\u0644 \u0627\u0644\u0645\u0648\u0627\u0641\u0642\u0629.");
      if (c.end_at <= now) fail(400, "already_ended", "\u0627\u0644\u062D\u0645\u0644\u0629 \u0645\u0646\u062A\u0647\u064A\u0629\u061B \u0645\u062F\u0651\u062F \u0623\u0648 \u062C\u062F\u0651\u062F \u0642\u0628\u0644 \u0627\u0644\u0645\u0648\u0627\u0641\u0642\u0629.");
      const st = await this.settings();
      if (!st.slots[c.slot] || st.slots[c.slot].enabled === false) fail(400, "slot_disabled", "\u0627\u0644\u0645\u0633\u0627\u062D\u0629 \u063A\u064A\u0631 \u0645\u0641\u0639\u0651\u0644\u0629.");
      await this.checkExclusive(c);
      c.status = "approved";
      c.reject_reason = "";
      c.approved_at = now;
      if (!c.report_token) await this.makeReportToken(c);
      this.log(c, "approve", "");
    } else if (act === "reject") {
      const reason = reqText(p.reason, 400, "\u0633\u0628\u0628 \u0627\u0644\u0631\u0641\u0636");
      c.status = "rejected";
      c.reject_reason = reason;
      this.log(c, "reject", reason);
    } else if (act === "pause") {
      if (c.status !== "approved") fail(400, "bad_state", "\u064A\u0645\u0643\u0646 \u0625\u064A\u0642\u0627\u0641 \u0627\u0644\u062D\u0645\u0644\u0629 \u0627\u0644\u0645\u0639\u062A\u0645\u062F\u0629 \u0641\u0642\u0637.");
      c.status = "paused";
      this.log(c, "pause", cleanText(p.reason, 200));
    } else if (act === "resume") {
      if (c.status !== "paused") fail(400, "bad_state", "\u0627\u0644\u062D\u0645\u0644\u0629 \u0644\u064A\u0633\u062A \u0645\u0648\u0642\u0648\u0641\u0629.");
      c.status = "approved";
      await this.checkExclusive(c);
      this.log(c, "resume", "");
    } else if (act === "extend") {
      const d = int(p.days, 1, 730, null);
      if (!d) fail(400, "invalid_days", "\u0639\u062F\u062F \u0627\u0644\u0623\u064A\u0627\u0645 \u063A\u064A\u0631 \u0635\u062D\u064A\u062D.");
      const base = c.end_at > now ? c.end_at : now;
      c.end_at = base + d * DAY_MS;
      c.duration_days = Math.max(1, Math.round((c.end_at - c.start_at) / DAY_MS));
      c.expiry_warned = false;
      if (c.status === "approved") await this.checkExclusive(c);
      this.log(c, "extend", d + " \u064A\u0648\u0645");
    } else if (act === "renew") {
      const d = int(p.days, 1, 730, c.duration_days);
      const copy = JSON.parse(JSON.stringify(c));
      copy.id = randId("c_", 9);
      copy.start_at = c.end_at > now ? c.end_at : now;
      copy.end_at = copy.start_at + d * DAY_MS;
      copy.duration_days = d;
      copy.status = "draft";
      copy.order_stage = "quoted";
      copy.paid_status = "unpaid";
      copy.paid_amount = 0;
      copy.reject_reason = "";
      copy.approved_at = 0;
      copy.report_token = "";
      copy.reports = 0;
      copy.expiry_warned = false;
      copy.log = [];
      copy.created_at = now;
      copy.updated_at = now;
      copy.name = c.name.replace(/ \(تجديد\)$/, "") + " (\u062A\u062C\u062F\u064A\u062F)";
      const st = await this.settings();
      try {
        copy.price_calc = computeQuote(st, { slot: copy.slot, days: d, targeting: copy.targeting, addons: copy.addons }).total;
      } catch (e) {
      }
      copy.price = copy.price_calc;
      copy.price_manual = false;
      this.log(copy, "renew_from", c.id);
      await this.s.put("camp:" + copy.id, copy);
      const { crvs } = await this.load();
      let sort = 0;
      for (const r of crvs.values()) {
        if (r.campaign_id !== c.id) continue;
        const nr = Object.assign({}, r, { id: randId("v_", 9), campaign_id: copy.id, created_at: now, updated_at: now, sort: sort++ });
        await this.s.put("crv:" + nr.id, nr);
      }
      this.log(c, "renewed", copy.id);
      extra = { renewed_id: copy.id };
    } else if (act === "stage") {
      c.order_stage = oneOf(p.stage, STAGES, c.order_stage);
      this.log(c, "stage", c.order_stage);
    } else if (act === "delete") {
      await this.deleteCampaign(c);
      this.dirty();
      return { deleted: true };
    } else if (act === "new_report_token") {
      await this.makeReportToken(c, true);
    } else fail(400, "unknown_action", "\u0625\u062C\u0631\u0627\u0621 \u063A\u064A\u0631 \u0645\u0639\u0631\u0648\u0641.");
    c.updated_at = now;
    await this.s.put("camp:" + c.id, c);
    this.dirty();
    const advs = await this.advMap();
    return Object.assign({ campaign: this.campView(c, now, p.origin, advs) }, extra);
  }
  async makeReportToken(c, rotate) {
    if (c.report_token && rotate) await this.s.delete("rt:" + c.report_token);
    if (c.report_token && !rotate) return;
    const bytes = new Uint8Array(24);
    crypto.getRandomValues(bytes);
    c.report_token = b64url(bytes);
    await this.s.put("rt:" + c.report_token, c.id);
  }
  async deleteCampaign(c) {
    const { crvs } = await this.load();
    for (const r of Array.from(crvs.values())) {
      if (r.campaign_id !== c.id) continue;
      await this.s.delete("crv:" + r.id);
      this.dirty();
      await this.deleteMedia(r.media_key, r.id);
      await this.deleteMedia(r.thumb_key, r.id);
    }
    const stats = await this.s.list({ prefix: "stat:" + c.id + ":" });
    const keys = Array.from(stats.keys());
    for (let i = 0; i < keys.length; i += 120) await this.s.delete(keys.slice(i, i + 120));
    await this.s.delete("tot:" + c.id);
    if (c.report_token) await this.s.delete("rt:" + c.report_token);
    await this.s.delete("camp:" + c.id);
    this.dirty();
  }
  async mediaInUse(key, exceptId) {
    const { crvs } = await this.load();
    for (const r of crvs.values()) if (r.id !== exceptId && (r.media_key === key || r.thumb_key === key)) return true;
    return false;
  }
  async deleteMedia(key, exceptId) {
    if (!key) return;
    if (await this.mediaInUse(key, exceptId)) return;
    const b = bucketOf(this.env);
    if (!b) return;
    try {
      await b.delete(key);
    } catch (e) {
    }
  }
  // ------------------------------------------------------------------ creatives
  crvView(r, origin) {
    return {
      id: r.id,
      campaign_id: r.campaign_id,
      type: r.type,
      media_key: r.media_key || "",
      media_url: mediaUrl(origin, r.media_key),
      thumb_key: r.thumb_key || "",
      thumb_url: mediaUrl(origin, r.thumb_key),
      title: r.title,
      body: r.body,
      cta: r.cta,
      action: r.action,
      bg_color: r.bg_color || "",
      text_color: r.text_color || "",
      active: r.active !== false,
      sort: r.sort || 0,
      created_at: r.created_at,
      updated_at: r.updated_at
    };
  }
  async op_crv_save(p) {
    const existing = p.id ? await this.s.get("crv:" + p.id) : null;
    if (p.id && !existing) fail(404, "not_found", "\u0627\u0644\u0645\u0627\u062F\u0629 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F\u0629.");
    const campId = cleanText(p.campaign_id !== void 0 ? p.campaign_id : existing && existing.campaign_id, 40);
    const camp = await this.s.get("camp:" + campId);
    if (!camp) fail(400, "invalid_campaign", "\u0627\u0644\u062D\u0645\u0644\u0629 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F\u0629.");
    const type = oneOf(p.type !== void 0 ? p.type : existing && existing.type, ["image", "video", "text"], null);
    if (!type) fail(400, "invalid_type", "\u0646\u0648\u0639 \u0627\u0644\u0645\u0627\u062F\u0629 \u063A\u064A\u0631 \u0635\u062D\u064A\u062D.");
    const b = bucketOf(this.env);
    const checkKey = /* @__PURE__ */ __name(async (k, prefixes, label) => {
      k = cleanText(k, 200);
      if (!k) return "";
      if (!prefixes.some((x) => k.indexOf(x) === 0) || k.indexOf("..") >= 0) fail(400, "invalid_media", "\u0645\u0633\u0627\u0631 " + label + " \u063A\u064A\u0631 \u0635\u0627\u0644\u062D.");
      if (b) {
        const head = await b.head(k);
        if (!head) fail(400, "media_missing", label + " \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F \u0641\u064A \u0627\u0644\u062A\u062E\u0632\u064A\u0646. \u0627\u0631\u0641\u0639\u0647 \u0623\u0648\u0644\u0627\u064B.");
      }
      return k;
    }, "checkKey");
    const mediaKey = await checkKey(p.media_key !== void 0 ? p.media_key : existing && existing.media_key, [MEDIA_PREFIX], "\u0627\u0644\u0648\u0633\u0627\u0626\u0637");
    const thumbKey = await checkKey(p.thumb_key !== void 0 ? p.thumb_key : existing && existing.thumb_key, [MEDIA_PREFIX], "\u0627\u0644\u0635\u0648\u0631\u0629 \u0627\u0644\u0645\u0635\u063A\u0651\u0631\u0629");
    if ((type === "image" || type === "video") && !mediaKey) fail(400, "media_required", "\u0627\u0631\u0641\u0639 \u0627\u0644\u0635\u0648\u0631\u0629 \u0623\u0648 \u0627\u0644\u0641\u064A\u062F\u064A\u0648.");
    const a = p.action && typeof p.action === "object" ? p.action : existing && existing.action || { type: "none" };
    const at = oneOf(a.type, ACTIONS, "none");
    const action = { type: at, value: "", extra: "" };
    if (at === "link") action.value = safeUrl(a.value, ["https:"]);
    else if (at === "store") action.value = safeUrl(a.value, ["https:", "market:"]);
    else if (at === "whatsapp") {
      action.value = digitsOnly(a.value, 18);
      action.extra = cleanText(a.extra, 300);
      if (!action.value) fail(400, "invalid_action", "\u0623\u062F\u062E\u0644 \u0631\u0642\u0645 \u0648\u0627\u062A\u0633\u0627\u0628.");
    } else if (at === "call") {
      action.value = digitsOnly(a.value, 18);
      if (!action.value) fail(400, "invalid_action", "\u0623\u062F\u062E\u0644 \u0631\u0642\u0645 \u0627\u0644\u0627\u062A\u0635\u0627\u0644.");
    } else if (at === "copy") {
      action.value = cleanText(a.value, 60);
      if (!action.value) fail(400, "invalid_action", "\u0623\u062F\u062E\u0644 \u0646\u0635 \u0627\u0644\u0643\u0648\u0628\u0648\u0646.");
    }
    if (at === "link" && !action.value) fail(400, "invalid_action", "\u0623\u062F\u062E\u0644 \u0627\u0644\u0631\u0627\u0628\u0637.");
    if (at === "store" && !action.value) fail(400, "invalid_action", "\u0623\u062F\u062E\u0644 \u0631\u0627\u0628\u0637 \u0627\u0644\u0645\u062A\u062C\u0631.");
    const color = /* @__PURE__ */ __name((v) => /^#[0-9a-fA-F]{6}$/.test(String(v || "")) ? String(v) : "", "color");
    const now = Date.now();
    const r = {
      id: existing ? existing.id : randId("v_", 9),
      campaign_id: camp.id,
      type,
      media_key: mediaKey,
      thumb_key: thumbKey,
      title: cleanText(p.title !== void 0 ? p.title : existing && existing.title, 80),
      body: cleanText(p.body !== void 0 ? p.body : existing && existing.body, 300),
      cta: cleanText(p.cta !== void 0 ? p.cta : existing && existing.cta, 24),
      action,
      bg_color: color(p.bg_color !== void 0 ? p.bg_color : existing && existing.bg_color),
      text_color: color(p.text_color !== void 0 ? p.text_color : existing && existing.text_color),
      active: p.active !== void 0 ? bool(p.active, true) : existing ? existing.active !== false : true,
      sort: int(p.sort !== void 0 ? p.sort : existing && existing.sort, 0, 999, 0),
      created_at: existing ? existing.created_at : now,
      updated_at: now
    };
    if (type === "text" && !r.title && !r.body) fail(400, "empty_creative", "\u0623\u0636\u0641 \u0639\u0646\u0648\u0627\u0646\u0627\u064B \u0623\u0648 \u0646\u0635\u0627\u064B.");
    await this.s.put("crv:" + r.id, r);
    this.dirty();
    if (existing) {
      if (existing.media_key && existing.media_key !== r.media_key) await this.deleteMedia(existing.media_key, r.id);
      if (existing.thumb_key && existing.thumb_key !== r.thumb_key) await this.deleteMedia(existing.thumb_key, r.id);
    }
    camp.updated_at = now;
    this.log(camp, existing ? "creative_edit" : "creative_add", r.id);
    await this.s.put("camp:" + camp.id, camp);
    this.dirty();
    return { creative: this.crvView(r, p.origin) };
  }
  async op_crv_delete(p) {
    const r = await this.s.get("crv:" + p.id);
    if (!r) fail(404, "not_found", "\u0627\u0644\u0645\u0627\u062F\u0629 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F\u0629.");
    await this.s.delete("crv:" + r.id);
    this.dirty();
    await this.deleteMedia(r.media_key, r.id);
    await this.deleteMedia(r.thumb_key, r.id);
    return { deleted: true };
  }
  // ------------------------------------------------------------------ serving
  async op_serve(p) {
    const st = await this.settings();
    const { camps, crvs } = await this.load();
    const now = Date.now();
    const off = st.hours.tz_offset_min || 180;
    const hourNow = hourOf(now, off);
    const slot = oneOf(p.slot, SLOTS, null);
    if (!slot) fail(400, "invalid_slot", "\u0645\u0633\u0627\u062D\u0629 \u063A\u064A\u0631 \u0645\u0639\u0631\u0648\u0641\u0629.");
    const city = resolveCity(st, p.city, p.region, p.region2);
    const lang = cleanText(p.lang, 8).toLowerCase().slice(0, 2);
    const ver = int(p.app_version, 0, 1e9, 0);
    const byCamp = /* @__PURE__ */ new Map();
    for (const r of crvs.values()) {
      if (r.active === false) continue;
      if (!byCamp.has(r.campaign_id)) byCamp.set(r.campaign_id, []);
      byCamp.get(r.campaign_id).push(r);
    }
    let boundary = now + 36e5;
    let hoursUsed = false;
    const matched = [];
    for (const c of camps.values()) {
      if (c.slot !== slot || c.status !== "approved") continue;
      if (c.start_at > now && c.start_at < boundary) boundary = c.start_at;
      if (c.end_at > now && c.end_at < boundary) boundary = c.end_at;
      if (c.start_at > now || c.end_at <= now) continue;
      const t = c.targeting || {};
      if (t.hours) hoursUsed = true;
      if (t.cities && t.cities.length && (!city || t.cities.indexOf(city) < 0)) continue;
      if (t.langs && t.langs.length && (!lang || t.langs.indexOf(lang) < 0)) continue;
      if (!inHours(hourNow, t.hours)) continue;
      if (t.min_version && ver < t.min_version) continue;
      if (t.max_version && (!ver || ver > t.max_version)) continue;
      const list = byCamp.get(c.id);
      if (!list || !list.length) continue;
      matched.push({ c, list });
    }
    if (hoursUsed) {
      const nextHour = now - ((now + off * 6e4) % 36e5 + 36e5) % 36e5 + 36e5;
      if (nextHour < boundary) boundary = nextHour;
    }
    let pool = matched;
    const ex = matched.filter((m) => m.c.addons && m.c.addons.exclusive);
    if (ex.length) pool = ex;
    const ads = [];
    pool.sort((a, b) => b.c.priority - a.c.priority || a.c.created_at - b.c.created_at);
    for (const m of pool) {
      const sorted = m.list.slice().sort((a, b) => (a.sort || 0) - (b.sort || 0));
      for (const r of sorted) {
        ads.push({
          campaign_id: m.c.id,
          creative_id: r.id,
          slot,
          type: r.type,
          priority: m.c.priority,
          exclusive: !!(m.c.addons && m.c.addons.exclusive),
          daily_cap: m.c.daily_cap || 0,
          start_at: m.c.start_at,
          end_at: m.c.end_at,
          media_url: mediaUrl(p.origin, r.media_key),
          thumb_url: mediaUrl(p.origin, r.thumb_key),
          title: r.title,
          body: r.body,
          cta: r.cta,
          action: r.action,
          bg_color: r.bg_color || "",
          text_color: r.text_color || ""
        });
        if (ads.length >= 14) break;
      }
      if (ads.length >= 14) break;
    }
    const ttl = Math.max(1, Math.min(45, Math.floor((boundary - now) / 1e3)));
    return { server_time: now, slot, ttl, ads };
  }
  // ------------------------------------------------------------------ tracking
  async op_track(p) {
    const events = Array.isArray(p.events) ? p.events.slice(0, 20) : [];
    const device = String(p.device || "").replace(/[^A-Za-z0-9_-]/g, "").slice(0, 64);
    if (!device) fail(400, "invalid_device", "\u0645\u0639\u0631\u0651\u0641 \u0627\u0644\u062C\u0647\u0627\u0632 \u0645\u0641\u0642\u0648\u062F.");
    if (!this.rl("dev:" + device, 120, 6e4) || !this.rl("ip:" + (p.ip || ""), 400, 6e4)) fail(429, "rate_limited", "\u0637\u0644\u0628\u0627\u062A \u0643\u062B\u064A\u0631\u0629.");
    const st = await this.settings();
    const off = st.hours.tz_offset_min || 180;
    const { camps, crvs } = await this.load();
    const now = Date.now();
    const day = dayKey(now, off);
    let accepted = 0;
    const ddD = "dd:d:" + day + ":" + device;
    if (!await this.s.get(ddD)) {
      await this.s.put(ddD, now + 2 * DAY_MS);
      await this.s.put("dau:" + day, (await this.s.get("dau:" + day) || 0) + 1);
    }
    for (const ev of events) {
      if (!ev || typeof ev !== "object") continue;
      const type = ev.t === "click" ? "c" : ev.t === "imp" ? "i" : null;
      if (!type) continue;
      const c = camps.get("camp:" + cleanText(ev.cid, 40));
      if (!c || c.status !== "approved" || c.start_at > now + 6e4 || c.end_at < now - 6e4) continue;
      const crid = cleanText(ev.crid, 40);
      if (crid && !(crvs.get("crv:" + crid) || {}).campaign_id) continue;
      if (crid && crvs.get("crv:" + crid).campaign_id !== c.id) continue;
      const win = type === "i" ? Math.floor(now / 18e5) : Math.floor(now / 1e4);
      const ddKey = "dd:" + type + ":" + c.id + ":" + device + ":" + win;
      if (await this.s.get(ddKey)) continue;
      await this.s.put(ddKey, now + (type === "i" ? 18e5 : 1e4) + 6e4);
      const sk = "stat:" + c.id + ":" + day;
      const row = await this.s.get(sk) || { i: 0, c: 0, u: 0 };
      const tot = await this.s.get("tot:" + c.id) || { i: 0, c: 0, u: 0 };
      row[type === "i" ? "i" : "c"]++;
      tot[type === "i" ? "i" : "c"]++;
      if (type === "i") {
        const ddU = "dd:u:" + c.id + ":" + day + ":" + device;
        if (!await this.s.get(ddU)) {
          await this.s.put(ddU, now + 2 * DAY_MS);
          row.u++;
          tot.u++;
        }
        await this.s.put("day:" + day, (await this.s.get("day:" + day) || 0) + 1);
      }
      await this.s.put(sk, row);
      await this.s.put("tot:" + c.id, tot);
      accepted++;
    }
    await this.ensureAlarm();
    return { accepted };
  }
  async op_report_ad(p) {
    const device = String(p.device || "").replace(/[^A-Za-z0-9_-]/g, "").slice(0, 64);
    if (!device) fail(400, "invalid_device", "\u0645\u0639\u0631\u0651\u0641 \u0627\u0644\u062C\u0647\u0627\u0632 \u0645\u0641\u0642\u0648\u062F.");
    if (!this.rl("rep:" + device, 5, 36e5)) fail(429, "rate_limited", "\u0637\u0644\u0628\u0627\u062A \u0643\u062B\u064A\u0631\u0629.");
    const c = await this.s.get("camp:" + cleanText(p.campaign_id, 40));
    if (!c) fail(404, "not_found", "\u0627\u0644\u0625\u0639\u0644\u0627\u0646 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F.");
    const now = Date.now();
    const key = "dd:r:" + c.id + ":" + device;
    if (await this.s.get(key)) return { duplicate: true };
    await this.s.put(key, now + DAY_MS);
    const id = randId("r_", 8);
    await this.s.put("rep:" + id, { id, campaign_id: c.id, reason: oneOf(p.reason, ["misleading", "inappropriate", "annoying", "other"], "other"), note: cleanText(p.note, 300), at: now });
    c.reports = (c.reports || 0) + 1;
    await this.s.put("camp:" + c.id, c);
    this.dirty();
    return { reported: true };
  }
  async op_reports_list(p) {
    const m = await this.s.list({ prefix: "rep:", limit: 300 });
    return { reports: Array.from(m.values()).sort((a, b) => b.at - a.at) };
  }
  async op_reports_clear(p) {
    const m = await this.s.list({ prefix: "rep:" });
    const keys = [];
    for (const [k, v] of m) if (v.campaign_id === p.campaign_id) keys.push(k);
    for (let i = 0; i < keys.length; i += 120) await this.s.delete(keys.slice(i, i + 120));
    const c = await this.s.get("camp:" + p.campaign_id);
    if (c) {
      c.reports = 0;
      await this.s.put("camp:" + c.id, c);
      this.dirty();
    }
    return { cleared: keys.length };
  }
  // ------------------------------------------------------------------ reports
  async buildReport(c, advs) {
    const st = await this.settings();
    const off = st.hours.tz_offset_min || 180;
    const rows = await this.s.list({ prefix: "stat:" + c.id + ":" });
    const daily = [];
    for (const [k, v] of rows) daily.push({ d: k.split(":")[2], i: v.i, c: v.c, u: v.u });
    daily.sort((a, b) => a.d < b.d ? -1 : 1);
    const tot = await this.s.get("tot:" + c.id) || { i: 0, c: 0, u: 0 };
    const adv = advs ? advs.get(c.advertiser_id) : await this.s.get("adv:" + c.advertiser_id);
    const best = daily.reduce((m, r) => r.i > (m ? m.i : -1) ? r : m, null);
    return {
      name: c.name,
      advertiser: adv ? adv.name : "",
      slot: c.slot,
      slot_label: st.slots[c.slot] ? st.slots[c.slot].label : c.slot,
      start_at: c.start_at,
      end_at: c.end_at,
      stage: displayStage(c, Date.now()),
      impressions: tot.i,
      clicks: tot.c,
      unique: tot.u,
      ctr: tot.i ? round2(tot.c / tot.i * 100) : 0,
      daily,
      best_day: best ? best.d : "",
      tz_offset_min: off,
      generated_at: Date.now()
    };
  }
  async op_report_admin(p) {
    const c = await this.s.get("camp:" + p.id);
    if (!c) fail(404, "not_found", "\u0627\u0644\u062D\u0645\u0644\u0629 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F\u0629.");
    return { report: await this.buildReport(c, null), report_token: c.report_token || "" };
  }
  async op_report_public(p) {
    const tok = cleanText(p.token, 80);
    const id = tok ? await this.s.get("rt:" + tok) : null;
    const c = id ? await this.s.get("camp:" + id) : null;
    if (!c) fail(404, "not_found", "\u0627\u0644\u062A\u0642\u0631\u064A\u0631 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F \u0623\u0648 \u0623\u064F\u0644\u063A\u064A \u0631\u0627\u0628\u0637\u0647.");
    return { report: await this.buildReport(c, null) };
  }
  // ------------------------------------------------------------------ advertiser (user) views
  async op_me_campaigns(p) {
    const key = cleanText(p.user_key, 120);
    const id = key ? await this.s.get("advuser:" + key) : null;
    if (!id) return { advertiser: null, campaigns: [] };
    const adv = await this.s.get("adv:" + id);
    const st = await this.settings();
    const { camps } = await this.load();
    const now = Date.now();
    const out = [];
    for (const c of camps.values()) {
      if (c.advertiser_id !== id) continue;
      const stage = displayStage(c, now);
      const rep = await this.buildReport(c, /* @__PURE__ */ new Map([[id, adv]]));
      out.push({
        id: c.id,
        name: c.name,
        slot: c.slot,
        slot_label: st.slots[c.slot] ? st.slots[c.slot].label : c.slot,
        stage,
        start_at: c.start_at,
        end_at: c.end_at,
        price: c.price,
        currency: c.currency,
        paid_status: c.paid_status,
        paid_amount: c.paid_amount || 0,
        reject_reason: stage === "rejected" ? c.reject_reason || "" : "",
        renewable: stage === "running" || stage === "ended",
        stats: { impressions: rep.impressions, clicks: rep.clicks, unique: rep.unique, ctr: rep.ctr, daily: rep.daily.slice(-30) },
        report_url: c.report_token ? p.origin + "/report/" + c.report_token : "",
        updated_at: c.updated_at
      });
    }
    out.sort((a, b) => b.updated_at - a.updated_at);
    return { advertiser: adv ? this.advView(adv, p.origin) : null, campaigns: out };
  }
  async op_consent_get(p) {
    const v = await this.s.get("consent:" + cleanText(p.user_key, 120));
    const st = await this.settings();
    return { consent: v || null, current_version: st.policies_version, valid: !!(v && v.version === st.policies_version) };
  }
  async op_consent_set(p) {
    const st = await this.settings();
    const version = int(p.version, 1, 1e6, 0);
    if (version !== st.policies_version) fail(409, "policies_changed", "\u062A\u063A\u064A\u0651\u0631\u062A \u0627\u0644\u0633\u064A\u0627\u0633\u0627\u062A. \u0623\u0639\u062F \u0642\u0631\u0627\u0621\u062A\u0647\u0627 \u062B\u0645 \u0648\u0627\u0641\u0642.", { current_version: st.policies_version });
    const v = { version, at: Date.now() };
    await this.s.put("consent:" + cleanText(p.user_key, 120), v);
    return { consent: v, valid: true };
  }
  async op_consent_delete(p) {
    await this.s.delete("consent:" + cleanText(p.user_key, 120));
    return {};
  }
  // ------------------------------------------------------------------ overview
  async op_overview(p) {
    const { camps } = await this.load();
    const advs = await this.advMap();
    const now = Date.now();
    const counts = {};
    const expiring = [];
    let revenue = 0, outstanding = 0, flagged = 0, running = 0;
    for (const c of camps.values()) {
      const stg = displayStage(c, now);
      counts[stg] = (counts[stg] || 0) + 1;
      revenue += c.paid_amount || 0;
      if (c.status === "approved" || c.status === "paused") outstanding += Math.max(0, (c.price || 0) - (c.paid_amount || 0));
      if (c.reports > 0) flagged++;
      if (stg === "running") running++;
      if (c.status === "approved" && c.end_at > now && c.end_at - now <= 3 * DAY_MS) {
        const a = advs.get(c.advertiser_id);
        expiring.push({ id: c.id, name: c.name, advertiser_name: a ? a.name : "", end_at: c.end_at });
      }
    }
    const hero = await this.heroNumbers(await this.settings());
    return { counts, running, expiring, revenue: round2(revenue), outstanding: round2(outstanding), flagged, advertisers: advs.size, hero };
  }
  async op_authfail(p) {
    const ok = this.rl("af:" + cleanText(p.ip, 60), 20, 6e5);
    return { blocked: !ok };
  }
  // ------------------------------------------------------------------ housekeeping (alarm)
  async alarm() {
    const now = Date.now();
    let more = false;
    try {
      const rows = await this.s.list({ prefix: "dd:", limit: 3e3 });
      const dead = [];
      for (const [k, v] of rows) if (typeof v === "number" && v < now) dead.push(k);
      for (let i = 0; i < dead.length; i += 120) await this.s.delete(dead.slice(i, i + 120));
      if (rows.size >= 3e3 && dead.length > 1500) more = true;
      const old = dayKey(now - 120 * DAY_MS, 180);
      for (const pre of ["dau:", "day:"]) {
        const m = await this.s.list({ prefix: pre });
        const del = [];
        for (const k of m.keys()) if (k.slice(pre.length) < old) del.push(k);
        if (del.length) await this.s.delete(del.slice(0, 120));
      }
      const { camps } = await this.load();
      for (const c of camps.values()) {
        if (c.status === "approved" && !c.expiry_warned && c.end_at > now && c.end_at - now <= 3 * DAY_MS) {
          c.expiry_warned = true;
          this.log(c, "expiring_soon", "");
          await this.s.put("camp:" + c.id, c);
          this.dirty();
          await this.pushWebhook({ type: "ad_expiring", campaign_id: c.id, name: c.name, end_at: c.end_at });
        }
      }
    } catch (e) {
      console.log("alarm_error", String(e && e.message));
    }
    await this.s.setAlarm(now + (more ? 6e4 : 36e5));
  }
  async pushWebhook(payload) {
    const url = this.env.PUSH_WEBHOOK_URL;
    if (!url) return;
    try {
      await fetch(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(payload) });
    } catch (e) {
    }
  }
};
function clientIp(request) {
  return request.headers.get("cf-connecting-ip") || request.headers.get("x-forwarded-for") || "0.0.0.0";
}
__name(clientIp, "clientIp");
function bearerOf(request) {
  const h = (request.headers.get("authorization") || "").replace(/^Bearer\s+/i, "").trim();
  return h || (request.headers.get("x-admin-token") || "").trim();
}
__name(bearerOf, "bearerOf");
function originOf(request, env) {
  if (env.PUBLIC_BASE) return String(env.PUBLIC_BASE).replace(/\/+$/, "");
  return new URL(request.url).origin;
}
__name(originOf, "originOf");
async function readJson(request, maxBytes) {
  const len = parseInt(request.headers.get("content-length") || "0", 10);
  if (len > (maxBytes || 3e5)) fail(413, "too_large", "\u062D\u062C\u0645 \u0627\u0644\u0637\u0644\u0628 \u0643\u0628\u064A\u0631 \u062C\u062F\u0627\u064B.");
  let text = "";
  try {
    text = await request.text();
  } catch (e) {
    fail(400, "invalid_json", "\u0628\u064A\u0627\u0646\u0627\u062A \u063A\u064A\u0631 \u0635\u0627\u0644\u062D\u0629.");
  }
  if (text.length > (maxBytes || 3e5)) fail(413, "too_large", "\u062D\u062C\u0645 \u0627\u0644\u0637\u0644\u0628 \u0643\u0628\u064A\u0631 \u062C\u062F\u0627\u064B.");
  if (!text.trim()) return {};
  try {
    const v = JSON.parse(text);
    return v && typeof v === "object" ? v : {};
  } catch (e) {
    fail(400, "invalid_json", "\u0628\u064A\u0627\u0646\u0627\u062A \u063A\u064A\u0631 \u0635\u0627\u0644\u062D\u0629.");
  }
}
__name(readJson, "readJson");
function coreStub(env) {
  return env.ADS_CORE.get(env.ADS_CORE.idFromName("core"));
}
__name(coreStub, "coreStub");
async function core(env, op, p) {
  const r = await coreStub(env).fetch("https://core/op", { method: "POST", body: JSON.stringify({ op, p: p || {} }) });
  const body = await r.json();
  if (!body.ok) fail(r.status || 500, body.error || "error", body.message || "\u0641\u0634\u0644 \u0627\u0644\u0637\u0644\u0628.", body.conflict_id ? { conflict_id: body.conflict_id } : body.current_version ? { current_version: body.current_version } : {});
  return body;
}
__name(core, "core");
// 18.2 — developer accounts (optional secret DEVELOPERS = JSON array):
//   [{"id":"dev_1","name":"حسن","avatar":"https://…","role":"owner"|"support","token_sha256":"<hex sha256 of that developer's token>"}]
// ADMIN_TOKEN keeps working as the owner account (DEV_NAME / DEV_AVATAR give its public name / picture).
function devPublic(dev) {
  return dev ? { name: cleanText(dev.name, 40), avatar: /^https:\/\//.test(String(dev.avatar || "")) ? String(dev.avatar).slice(0, 400) : "" } : null;
}
__name(devPublic, "devPublic");
async function developerOf(request, env) {
  const tok = bearerOf(request);
  if (!tok) return null;
  if (env.ADMIN_TOKEN && timingSafeEqual(tok, env.ADMIN_TOKEN)) {
    return { id: "owner", name: cleanText(env.DEV_NAME, 40) || "\u0645\u0637\u0648\u0651\u0631 Cigram", avatar: String(env.DEV_AVATAR || ""), role: "owner" };
  }
  if (!env.DEVELOPERS) return null;
  let list = [];
  try { list = JSON.parse(env.DEVELOPERS); } catch (e) { return null; }
  const h = await sha256Hex(tok);
  for (const d of Array.isArray(list) ? list : []) {
    if (d && d.token_sha256 && timingSafeEqual(h, String(d.token_sha256).toLowerCase())) {
      return { id: cleanText(d.id, 40), name: cleanText(d.name, 40), avatar: String(d.avatar || ""), role: d.role === "owner" ? "owner" : "support" };
    }
  }
  return null;
}
__name(developerOf, "developerOf");
function requireRole(dev, role) {
  if (role === "owner" && (!dev || dev.role !== "owner")) fail(403, "forbidden_role", "\u0647\u0630\u0627 \u0627\u0644\u0625\u062C\u0631\u0627\u0621 \u0644\u0644\u0645\u0627\u0644\u0643 \u0641\u0642\u0637.");
}
__name(requireRole, "requireRole");
async function requireAdmin(request, env) {
  const ip = clientIp(request);
  const tok = bearerOf(request);
  const dev18 = await developerOf(request, env);
  if (dev18) return dev18;
  if (!env.ADMIN_TOKEN && !env.DEVELOPERS) fail(503, "admin_not_configured", "\u0644\u0645 \u064A\u064F\u0636\u0628\u0637 ADMIN_TOKEN \u0641\u064A \u0627\u0644\u0640 Worker.");
  const ok = tok && timingSafeEqual(tok, env.ADMIN_TOKEN);
  if (!ok) {
    const r = await coreStub(env).fetch("https://core/op", { method: "POST", body: JSON.stringify({ op: "authfail", p: { ip } }) });
    const b = await r.json().catch(() => ({}));
    if (b && b.blocked) fail(429, "too_many_attempts", "\u0645\u062D\u0627\u0648\u0644\u0627\u062A \u0643\u062B\u064A\u0631\u0629 \u062E\u0627\u0637\u0626\u0629. \u062D\u0627\u0648\u0644 \u0644\u0627\u062D\u0642\u0627\u064B.");
    fail(401, "unauthorized", "\u0631\u0645\u0632 \u0627\u0644\u0625\u062F\u0627\u0631\u0629 \u063A\u064A\u0631 \u0635\u062D\u064A\u062D.");
  }
}
__name(requireAdmin, "requireAdmin");
var _authCache = /* @__PURE__ */ new Map();
async function userKeyOf(id, email) {
  const base = id ? "id:" + id : "email:" + String(email || "").toLowerCase();
  return "u_" + (await sha256Hex("cigram-ads-user|" + base)).slice(0, 28);
}
__name(userKeyOf, "userKeyOf");
async function verifyJwtHS256(token, secret) {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const header = JSON.parse(new TextDecoder().decode(b64urlToBytes(parts[0])));
    if (!header || header.alg !== "HS256") return null;
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
    const ok = await crypto.subtle.verify("HMAC", key, b64urlToBytes(parts[2]), new TextEncoder().encode(parts[0] + "." + parts[1]));
    if (!ok) return null;
    const claims = JSON.parse(new TextDecoder().decode(b64urlToBytes(parts[1])));
    if (claims.exp && Date.now() / 1e3 > claims.exp) return null;
    if (claims.nbf && Date.now() / 1e3 < claims.nbf) return null;
    return claims;
  } catch (e) {
    return null;
  }
}
__name(verifyJwtHS256, "verifyJwtHS256");
function pickUser(obj) {
  if (!obj || typeof obj !== "object") return null;
  const cand = [obj, obj.user, obj.data, obj.profile].filter((x) => x && typeof x === "object");
  for (const o of cand) {
    const id = o.sub || o.user_id || o.uid || o.id || "";
    const email = o.email || "";
    if (id || email) return { id: String(id || ""), email: String(email || "").toLowerCase(), name: String(o.name || o.username || o.display_name || "").slice(0, 100) };
  }
  return null;
}
__name(pickUser, "pickUser");
async function verifyUser(request, env) {
  const tok = bearerOf(request);
  if (!tok || tok.length > 4e3) fail(401, "login_required", "\u0633\u062C\u0651\u0644 \u0627\u0644\u062F\u062E\u0648\u0644 \u0623\u0648\u0644\u0627\u064B.");
  const verifyUrl = String(env.AUTH_VERIFY_URL || "https://cigram-auth-api.wwq-mixtv.workers.dev/me").trim();
  const ck = await sha256Hex(tok);
  const hit = _authCache.get(ck);
  if (hit && hit.exp > Date.now()) return hit.user;
  let user = null;
  if (env.JWT_SECRET) {
    const claims = await verifyJwtHS256(tok, env.JWT_SECRET);
    if (claims) user = pickUser(claims);
  }
  if (!user && verifyUrl) {
    try {
      const vreq = { headers: { authorization: "Bearer " + tok, accept: "application/json" } };
      const r = env.AUTH_API && typeof env.AUTH_API.fetch === "function" ? await env.AUTH_API.fetch(verifyUrl, vreq) : await fetch(verifyUrl, vreq);
      if (r.ok) user = pickUser(await r.json());
    } catch (e) {
    }
  }
  if (!user) fail(401, "invalid_token", "\u0627\u0646\u062A\u0647\u062A \u0627\u0644\u062C\u0644\u0633\u0629. \u0633\u062C\u0651\u0644 \u0627\u0644\u062F\u062E\u0648\u0644 \u0645\u0646 \u062C\u062F\u064A\u062F.");
  user.key = await userKeyOf(user.id, user.email);
  if (_authCache.size > 2e3) _authCache.clear();
  _authCache.set(ck, { user, exp: Date.now() + 5 * 6e4 });
  return user;
}
__name(verifyUser, "verifyUser");
function sniffMedia(b) {
  if (b.length < 12) return null;
  const eq = /* @__PURE__ */ __name((off, arr) => arr.every((v, i) => b[off + i] === v), "eq");
  if (eq(0, [255, 216, 255])) return { mime: "image/jpeg", ext: "jpg", kind: "image" };
  if (eq(0, [137, 80, 78, 71, 13, 10, 26, 10])) return { mime: "image/png", ext: "png", kind: "image" };
  if (eq(0, [71, 73, 70, 56])) return { mime: "image/gif", ext: "gif", kind: "image" };
  if (eq(0, [82, 73, 70, 70]) && eq(8, [87, 69, 66, 80])) return { mime: "image/webp", ext: "webp", kind: "image" };
  if (eq(4, [102, 116, 121, 112])) return { mime: "video/mp4", ext: "mp4", kind: "video" };
  if (eq(0, [26, 69, 223, 163])) return { mime: "video/webm", ext: "webm", kind: "video" };
  return null;
}
__name(sniffMedia, "sniffMedia");
async function uploadAdMedia(request, env, url) {
  const b = bucketOf(env);
  if (!b) fail(500, "no_bucket", "\u0644\u0627 \u064A\u0648\u062C\u062F R2 Bucket (ADS_BUCKET \u0623\u0648 MOVIES_BUCKET).");
  const kind = oneOf(url.searchParams.get("kind"), ["creative", "thumb", "logo"], "creative");
  const len = parseInt(request.headers.get("content-length") || "0", 10);
  const limit = kind === "creative" ? MAX_VIDEO_BYTES : MAX_IMAGE_BYTES;
  if (len > limit) fail(413, "too_large", "\u0627\u0644\u0645\u0644\u0641 \u0623\u0643\u0628\u0631 \u0645\u0646 \u0627\u0644\u062D\u062F \u0627\u0644\u0645\u0633\u0645\u0648\u062D.");
  const buf = new Uint8Array(await request.arrayBuffer());
  if (!buf.length) fail(400, "empty_body", "\u0627\u0644\u0645\u0644\u0641 \u0641\u0627\u0631\u063A.");
  if (buf.length > limit) fail(413, "too_large", "\u0627\u0644\u0645\u0644\u0641 \u0623\u0643\u0628\u0631 \u0645\u0646 \u0627\u0644\u062D\u062F \u0627\u0644\u0645\u0633\u0645\u0648\u062D.");
  const t = sniffMedia(buf);
  if (!t) fail(415, "unsupported_type", "\u0646\u0648\u0639 \u0627\u0644\u0645\u0644\u0641 \u063A\u064A\u0631 \u0645\u062F\u0639\u0648\u0645 (\u0635\u0648\u0631\u0629 JPG/PNG/WEBP/GIF \u0623\u0648 \u0641\u064A\u062F\u064A\u0648 MP4/WEBM).");
  if (t.kind === "video" && kind !== "creative") fail(415, "unsupported_type", "\u0627\u0644\u0634\u0639\u0627\u0631 \u0648\u0627\u0644\u0635\u0648\u0631\u0629 \u0627\u0644\u0645\u0635\u063A\u0651\u0631\u0629 \u064A\u062C\u0628 \u0623\u0646 \u062A\u0643\u0648\u0646 \u0635\u0648\u0631\u0629.");
  if (t.kind === "image" && buf.length > MAX_IMAGE_BYTES) fail(413, "too_large", "\u0627\u0644\u0635\u0648\u0631\u0629 \u0623\u0643\u0628\u0631 \u0645\u0646 6MB.");
  const prefix = kind === "logo" ? "ads/logos/" : MEDIA_PREFIX;
  const key = prefix + randId("", 14) + "." + t.ext;
  await b.put(key, buf, { httpMetadata: { contentType: t.mime, cacheControl: "public, max-age=31536000, immutable" } });
  return { key, url: mediaUrl(originOf(request, env), key), mime: t.mime, kind: t.kind, size: buf.length };
}
__name(uploadAdMedia, "uploadAdMedia");
async function serveMedia(request, env, prefix, file) {
  const b = bucketOf(env);
  if (!b || !/^[A-Za-z0-9_-]{6,40}\.(jpg|png|webp|gif|mp4|webm)$/.test(file)) return new Response("Not found", { status: 404, headers: corsHeaders() });
  const key = prefix + file;
  const range = request.headers.get("range");
  let opts;
  let start = 0, end = null;
  if (range) {
    const m = /^bytes=(\d*)-(\d*)$/.exec(range.trim());
    if (m && (m[1] || m[2])) {
      const head = await b.head(key);
      if (!head) return new Response("Not found", { status: 404, headers: corsHeaders() });
      const size = head.size;
      if (m[1] === "") {
        start = Math.max(0, size - parseInt(m[2], 10));
        end = size - 1;
      } else {
        start = parseInt(m[1], 10);
        end = m[2] ? Math.min(size - 1, parseInt(m[2], 10)) : size - 1;
      }
      if (start >= size || end < start) return new Response("Range Not Satisfiable", { status: 416, headers: corsHeaders({ "content-range": "bytes */" + size }) });
      opts = { range: { offset: start, length: end - start + 1 } };
      const obj2 = await b.get(key, opts);
      if (!obj2) return new Response("Not found", { status: 404, headers: corsHeaders() });
      return new Response(obj2.body, {
        status: 206,
        headers: corsHeaders({
          "content-type": obj2.httpMetadata && obj2.httpMetadata.contentType || "application/octet-stream",
          "content-range": "bytes " + start + "-" + end + "/" + size,
          "content-length": String(end - start + 1),
          "accept-ranges": "bytes",
          "cache-control": "public, max-age=86400"
        })
      });
    }
  }
  const obj = await b.get(key);
  if (!obj) return new Response("Not found", { status: 404, headers: corsHeaders() });
  return new Response(obj.body, {
    status: 200,
    headers: corsHeaders({
      "content-type": obj.httpMetadata && obj.httpMetadata.contentType || "application/octet-stream",
      "content-length": String(obj.size),
      "accept-ranges": "bytes",
      "cache-control": "public, max-age=86400",
      etag: obj.httpEtag || ""
    })
  });
}
__name(serveMedia, "serveMedia");
function esc(s) {
  return String(s === void 0 || s === null ? "" : s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}
__name(esc, "esc");
function fmtDate(ms, off) {
  const d = new Date(ms + (off || 0) * 6e4);
  return d.getUTCFullYear() + "/" + String(d.getUTCMonth() + 1).padStart(2, "0") + "/" + String(d.getUTCDate()).padStart(2, "0");
}
__name(fmtDate, "fmtDate");
function stageLabel(s) {
  return { received: "\u0645\u0633\u062A\u0644\u0645", review: "\u0642\u064A\u062F \u0627\u0644\u0645\u0631\u0627\u062C\u0639\u0629", quoted: "\u0639\u0631\u0636 \u0633\u0639\u0631", awaiting_payment: "\u0628\u0627\u0646\u062A\u0638\u0627\u0631 \u0627\u0644\u062F\u0641\u0639", scheduled: "\u0645\u062C\u062F\u0648\u0644", running: "\u064A\u0639\u0645\u0644", ended: "\u0645\u0646\u062A\u0647\u064A", rejected: "\u0645\u0631\u0641\u0648\u0636", paused: "\u0645\u062A\u0648\u0642\u0641" }[s] || s;
}
__name(stageLabel, "stageLabel");
function renderReportHtml(r) {
  const max = Math.max(1, ...r.daily.map((d) => d.i));
  const bw = r.daily.length ? Math.max(4, Math.min(28, Math.floor(560 / r.daily.length) - 3)) : 10;
  const bars = r.daily.map((d, i) => {
    const h = Math.round(d.i / max * 120);
    const x = i * (bw + 3);
    return '<rect x="' + x + '" y="' + (130 - h) + '" width="' + bw + '" height="' + h + '" rx="3" fill="#26A5B5"><title>' + esc(d.d) + " \u2014 " + d.i + " \u0645\u0634\u0627\u0647\u062F\u0629 / " + d.c + " \u0646\u0642\u0631\u0629</title></rect>";
  }).join("");
  const w = Math.max(200, r.daily.length * (bw + 3));
  const tile = /* @__PURE__ */ __name((n, l) => '<div class="t"><b>' + esc(Number(n).toLocaleString("en-US")) + "</b><span>" + esc(l) + "</span></div>", "tile");
  return '<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex,nofollow"><title>\u062A\u0642\u0631\u064A\u0631 \u062D\u0645\u0644\u0629 \u2014 ' + esc(r.name) + '</title><style>:root{color-scheme:dark light;--bg:#0B2733;--card:#102F3B;--tx:#fff;--mu:#8FA1AA;--ac:#26A5B5;--st:#1F4553}@media(prefers-color-scheme:light){:root{--bg:#f4f8fa;--card:#fff;--tx:#10222b;--mu:#5a6c75;--st:#d6e2e8}}body{margin:0;background:var(--bg);color:var(--tx);font-family:system-ui,Segoe UI,Tahoma,sans-serif;padding:20px}.w{max-width:760px;margin:auto}h1{font-size:22px;margin:0 0 4px}p.s{color:var(--mu);margin:0 0 20px}.g{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin-bottom:16px}.t,.c{background:var(--card);border:1px solid var(--st);border-radius:16px;padding:16px}.t b{display:block;font-size:28px;color:var(--ac)}.t span{color:var(--mu);font-size:13px}.c{overflow-x:auto}.m{color:var(--mu);font-size:13px;margin-top:16px;text-align:center}</style></head><body><div class="w"><h1>' + esc(r.name) + '</h1><p class="s">' + esc(r.advertiser) + " \xB7 " + esc(r.slot_label) + " \xB7 " + esc(stageLabel(r.stage)) + " \xB7 " + esc(fmtDate(r.start_at, r.tz_offset_min)) + " \u2190 " + esc(fmtDate(r.end_at, r.tz_offset_min)) + '</p><div class="g">' + tile(r.impressions, "\u0645\u0634\u0627\u0647\u062F\u0627\u062A") + tile(r.clicks, "\u0646\u0642\u0631\u0627\u062A") + tile(r.ctr + "%", "\u0646\u0633\u0628\u0629 \u0627\u0644\u0646\u0642\u0631") + tile(r.unique, "\u0648\u0635\u0648\u0644 \u0641\u0631\u064A\u062F (\u064A\u0648\u0645\u064A)") + '</div><div class="c"><b>\u0627\u0644\u0645\u0634\u0627\u0647\u062F\u0627\u062A \u0627\u0644\u064A\u0648\u0645\u064A\u0629</b><br><svg viewBox="0 0 ' + w + ' 140" width="' + w + '" height="140" role="img" aria-label="\u0631\u0633\u0645 \u0627\u0644\u0645\u0634\u0627\u0647\u062F\u0627\u062A \u0627\u0644\u064A\u0648\u0645\u064A\u0629">' + bars + '</svg></div><p class="m">\u062A\u0642\u0631\u064A\u0631 \u0644\u0644\u0642\u0631\u0627\u0621\u0629 \u0641\u0642\u0637 \xB7 \u0623\u064F\u0646\u0634\u0626 ' + esc(fmtDate(r.generated_at, r.tz_offset_min)) + " \xB7 \u0623\u0631\u0642\u0627\u0645 \u0645\u062C\u0645\u0651\u0639\u0629 \u0641\u0642\u0637\u060C \u0644\u0627 \u062A\u062A\u0636\u0645\u0646 \u0623\u064A \u0628\u064A\u0627\u0646\u0627\u062A \u0634\u062E\u0635\u064A\u0629.</p></div></body></html>";
}
__name(renderReportHtml, "renderReportHtml");
async function handleRequest(request, env, ctx) {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders() });
  const url = new URL(request.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";
  const method = request.method;
  const origin = originOf(request, env);
  const q = /* @__PURE__ */ __name((k) => url.searchParams.get(k) || "", "q");
  if (method === "GET" && (path === "/" || path === "/health")) return reply({ ok: true, service: "cigram-ads-api", time: Date.now() });
  if (!env.ADS_CORE) return reply({ ok: false, error: "missing_binding", message: "Durable Object ADS_CORE \u063A\u064A\u0631 \u0645\u0631\u0628\u0648\u0637." }, 500);
  let m;
  if (method === "GET" && (m = /^\/m\/(c|l)\/([^/]+)$/.exec(path))) return serveMedia(request, env, m[1] === "c" ? MEDIA_PREFIX : "ads/logos/", m[2]);
  if (method === "GET" && path === "/ads/config") {
    const r = await core(env, "settings_public", {});
    return reply({ ok: true, settings: r.settings }, 200, { "cache-control": "public, max-age=30" });
  }
  if (method === "POST" && path === "/ads/quote") {
    const body = await readJson(request, 2e4);
    return reply({ ok: true, quote: (await core(env, "quote", body)).quote });
  }
  if (method === "GET" && path === "/ads") {
    const slot = oneOf(q("slot"), SLOTS, null);
    if (!slot) return reply({ ok: false, error: "invalid_slot", message: "\u0645\u0633\u0627\u062D\u0629 \u063A\u064A\u0631 \u0645\u0639\u0631\u0648\u0641\u0629." }, 400);
    const cf = request.cf || {};
    const params = {
      slot,
      city: cleanText(q("city"), 40),
      lang: cleanText(q("lang"), 8).toLowerCase(),
      app_version: int(q("app_version"), 0, 1e9, 0),
      region: cleanText(cf.region, 40),
      region2: cleanText(cf.city, 40),
      origin
    };
    const ck = new Request(url.origin + "/__ads?" + ["slot", "city", "lang", "app_version", "region", "region2"].map((k) => k + "=" + encodeURIComponent(params[k])).join("&"));
    const cache = typeof caches !== "undefined" && caches.default ? caches.default : null;
    if (cache) {
      const hit = await cache.match(ck);
      if (hit) return new Response(hit.body, { status: 200, headers: corsHeaders({ "content-type": "application/json; charset=utf-8", "x-cache": "HIT" }) });
    }
    const r = await core(env, "serve", params);
    const body = JSON.stringify({ ok: true, server_time: r.server_time, slot: r.slot, ttl: r.ttl, ads: r.ads });
    if (cache && ctx && ctx.waitUntil) {
      ctx.waitUntil(cache.put(ck, new Response(body, { headers: { "content-type": "application/json", "cache-control": "public, max-age=" + r.ttl } })));
    }
    return new Response(body, { status: 200, headers: corsHeaders({ "content-type": "application/json; charset=utf-8", "x-cache": "MISS" }) });
  }
  if (method === "POST" && path === "/ads/track") {
    const body = await readJson(request, 2e4);
    const r = await core(env, "track", { events: body.events, device: body.device, ip: clientIp(request) });
    return reply({ ok: true, accepted: r.accepted });
  }
  if (method === "POST" && path === "/ads/report") {
    const body = await readJson(request, 5e3);
    await core(env, "report_ad", { campaign_id: body.campaign_id, reason: body.reason, note: body.note, device: body.device });
    return reply({ ok: true });
  }
  if (method === "GET" && (m = /^\/report\/([A-Za-z0-9_-]{20,60})(\.json)?$/.exec(path))) {
    try {
      const r = await core(env, "report_public", { token: m[1] });
      if (m[2]) return reply({ ok: true, report: r.report });
      return new Response(renderReportHtml(r.report), {
        status: 200,
        headers: corsHeaders({ "content-type": "text/html; charset=utf-8", "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; img-src data:", "x-robots-tag": "noindex" })
      });
    } catch (e) {
      if (e instanceof HttpError && !m[2]) return new Response("<!doctype html><meta charset=utf-8><body dir=rtl style='font-family:sans-serif;padding:40px'>\u0627\u0644\u062A\u0642\u0631\u064A\u0631 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F \u0623\u0648 \u0623\u064F\u0644\u063A\u064A \u0631\u0627\u0628\u0637\u0647.", { status: 404, headers: corsHeaders({ "content-type": "text/html; charset=utf-8" }) });
      throw e;
    }
  }
  if (path === "/ads/consent" || path === "/me/campaigns") {
    const user = await verifyUser(request, env);
    if (method === "GET" && path === "/ads/consent") return reply(await core(env, "consent_get", { user_key: user.key }));
    if (method === "POST" && path === "/ads/consent") {
      const body = await readJson(request, 2e3);
      return reply(await core(env, "consent_set", { user_key: user.key, version: body.version }));
    }
    if (method === "GET" && path === "/me/campaigns") return reply(await core(env, "me_campaigns", { user_key: user.key, origin }));
  }
  if (typeof routeChat === "function") {
    const res = await routeChat(request, env, ctx, url, path);
    if (res) return res;
  }
  if (path.indexOf("/admin/") === 0) {
    await requireAdmin(request, env);
    if (method === "GET" && path === "/admin/overview") return reply(await core(env, "overview", {}));
    if (path === "/admin/settings") {
      if (method === "GET") return reply(await core(env, "settings_admin", {}));
      if (method === "POST") {
        const body = await readJson(request, 2e5);
        return reply(await core(env, "settings_set", { patch: body.patch || body }));
      }
    }
    if (method === "GET" && path === "/admin/advertisers") return reply(await core(env, "adv_list", { q: q("q"), archived: q("archived") === "1", origin }));
    if (method === "GET" && path === "/admin/advertisers/get") return reply(await core(env, "adv_get", { id: q("id"), origin }));
    if (method === "POST" && path === "/admin/advertisers/save") return reply(await core(env, "adv_save", Object.assign(await readJson(request, 2e4), { origin })));
    if (method === "POST" && path === "/admin/advertisers/delete") {
      const body = await readJson(request, 2e3);
      const r = await core(env, "adv_delete", { id: body.id, force: body.force });
      if (r.user_key && typeof purgeChatForUser === "function") await purgeChatForUser(env, r.user_key);
      return reply({ ok: true, deleted: true });
    }
    if (method === "GET" && path === "/admin/campaigns") {
      return reply(await core(env, "camp_list", { q: q("q"), stage: q("stage"), slot: q("slot"), advertiser_id: q("advertiser_id"), expiring: q("expiring") === "1", limit: q("limit"), origin }));
    }
    if (method === "GET" && path === "/admin/campaigns/get") return reply(await core(env, "camp_get", { id: q("id"), origin }));
    if (method === "GET" && path === "/admin/campaigns/report") return reply(await core(env, "report_admin", { id: q("id") }));
    if (method === "POST" && path === "/admin/campaigns/save") return reply(await core(env, "camp_save", Object.assign(await readJson(request, 3e4), { origin })));
    if (method === "POST" && path === "/admin/campaigns/action") {
      const r = await core(env, "camp_action", Object.assign(await readJson(request, 5e3), { origin }));
      if (r.campaign && ctx && ctx.waitUntil && typeof notifyCampaignChange === "function") ctx.waitUntil(notifyCampaignChange(env, r.campaign).catch(() => {
      }));
      return reply(r);
    }
    if (method === "POST" && path === "/admin/creatives/save") return reply(await core(env, "crv_save", Object.assign(await readJson(request, 2e4), { origin })));
    if (method === "POST" && path === "/admin/creatives/delete") return reply(await core(env, "crv_delete", await readJson(request, 2e3)));
    if (method === "POST" && path === "/admin/media") return reply(Object.assign({ ok: true }, await uploadAdMedia(request, env, url)));
    if (method === "GET" && path === "/admin/ad-reports") return reply(await core(env, "reports_list", {}));
    if (method === "POST" && path === "/admin/ad-reports/clear") return reply(await core(env, "reports_clear", await readJson(request, 2e3)));
  }
  return reply({ ok: false, error: "not_found", message: "\u0627\u0644\u0645\u0633\u0627\u0631 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F." }, 404);
}
__name(handleRequest, "handleRequest");
var worker_default = {
  async fetch(request, env, ctx) {
    try {
      return await handleRequest(request, env, ctx);
    } catch (e) {
      const [b, st] = errorBody(e);
      if (!(e instanceof HttpError)) console.log("worker_error", request.method, new URL(request.url).pathname, String(e && e.message));
      return reply(b, st);
    }
  }
};
var CHAT_MAX_TEXT = 4e3;
var CHAT_PART_SIZE = 1024 * 1024;
var CHAT_MAX_FILE = 25 * 1024 * 1024;
var CHAT_STATUSES = ["open", "pending", "quoted", "won", "closed"];
var CHAT_TYPES = ["text", "image", "video", "voice", "file", "quote", "order", "system"];
var CHAT_MEDIA_TTL_MS = 60 * 60 * 1e3;
var CHAT_MEDIA_PREFIX = "ads/chat/";
function toB64(bytes) {
  let s = "";
  const u = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  for (let i = 0; i < u.length; i += 32768) s += String.fromCharCode.apply(null, u.subarray(i, i + 32768));
  return btoa(s);
}
__name(toB64, "toB64");
function fromB64(str) {
  const bin = atob(String(str || ""));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
__name(fromB64, "fromB64");
var _chatKeyCache = /* @__PURE__ */ new Map();
async function chatMaster(env) {
  if (!env.CHAT_KEY) fail(503, "chat_not_configured", "\u0644\u0645 \u064A\u064F\u0636\u0628\u0637 \u0627\u0644\u0633\u0631 CHAT_KEY \u0641\u064A \u0627\u0644\u0640 Worker.");
  let raw;
  try {
    raw = fromB64(String(env.CHAT_KEY).trim());
  } catch (e) {
    raw = null;
  }
  if (!raw || raw.length < 32) fail(503, "chat_key_invalid", "CHAT_KEY \u064A\u062C\u0628 \u0623\u0646 \u064A\u0643\u0648\u0646 32 \u0628\u0627\u064A\u062A \u0639\u0644\u0649 \u0627\u0644\u0623\u0642\u0644 \u0628\u0635\u064A\u063A\u0629 base64.");
  return raw;
}
__name(chatMaster, "chatMaster");
async function chatDerive(env, info, usage, algo) {
  const ck = info + "|" + usage + "|" + String(env.CHAT_KEY).length;
  if (_chatKeyCache.has(ck)) return _chatKeyCache.get(ck);
  const raw = await chatMaster(env);
  const base = await crypto.subtle.importKey("raw", raw, "HKDF", false, ["deriveKey"]);
  const key = await crypto.subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt: new TextEncoder().encode("cigram-ads-chat-v1"), info: new TextEncoder().encode(info) },
    base,
    algo,
    false,
    [usage]
  );
  if (_chatKeyCache.size > 500) _chatKeyCache.clear();
  _chatKeyCache.set(ck, key);
  return key;
}
__name(chatDerive, "chatDerive");
var _aes = { name: "AES-GCM", length: 256 };
async function encJson(env, thread, obj) {
  const key = await chatDerive(env, "msg|" + thread, "encrypt", _aes);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: new TextEncoder().encode(thread) }, key, new TextEncoder().encode(JSON.stringify(obj)));
  return { iv: toB64(iv), ct: toB64(new Uint8Array(ct)) };
}
__name(encJson, "encJson");
async function decJson(env, thread, enc) {
  if (!enc || !enc.iv) return {};
  try {
    const key = await chatDerive(env, "msg|" + thread, "decrypt", _aes);
    const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: fromB64(enc.iv), additionalData: new TextEncoder().encode(thread) }, key, fromB64(enc.ct));
    return JSON.parse(new TextDecoder().decode(pt));
  } catch (e) {
    return { text: "[\u062A\u0639\u0630\u0651\u0631 \u0641\u0643 \u062A\u0634\u0641\u064A\u0631 \u0627\u0644\u0631\u0633\u0627\u0644\u0629]", broken: true };
  }
}
__name(decJson, "decJson");
async function mediaPartEncrypt(env, mediaId, n, bytes) {
  const key = await chatDerive(env, "media|" + mediaId, "encrypt", _aes);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: new TextEncoder().encode(mediaId + ":" + n) }, key, bytes));
  const out = new Uint8Array(12 + ct.length);
  out.set(iv, 0);
  out.set(ct, 12);
  return out;
}
__name(mediaPartEncrypt, "mediaPartEncrypt");
async function mediaPartDecrypt(env, mediaId, n, blob) {
  const key = await chatDerive(env, "media|" + mediaId, "decrypt", _aes);
  return new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: blob.subarray(0, 12), additionalData: new TextEncoder().encode(mediaId + ":" + n) }, key, blob.subarray(12)));
}
__name(mediaPartDecrypt, "mediaPartDecrypt");
async function mediaSign(env, mediaId, thread, exp) {
  const raw = await chatDerive(env, "sign", "sign", { name: "HMAC", hash: "SHA-256", length: 256 }).catch(async () => null);
  const key = raw || null;
  if (!key) fail(503, "chat_not_configured", "CHAT_KEY \u063A\u064A\u0631 \u0645\u0636\u0628\u0648\u0637.");
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(mediaId + "|" + thread + "|" + exp));
  return b64url(new Uint8Array(sig));
}
__name(mediaSign, "mediaSign");
function inWorkHours(st, now) {
  const h = st && st.hours;
  if (!h || !h.enabled) return true;
  const d = new Date(now + (h.tz_offset_min || 0) * 6e4);
  const dow = d.getUTCDay();
  if (Array.isArray(h.days) && h.days.indexOf(dow) < 0) return false;
  const mins = d.getUTCHours() * 60 + d.getUTCMinutes();
  const p = /* @__PURE__ */ __name((s) => {
    const m = /^(\d{1,2}):(\d{2})$/.exec(String(s || ""));
    return m ? parseInt(m[1], 10) * 60 + parseInt(m[2], 10) : null;
  }, "p");
  const a = p(h.from), b = p(h.to);
  if (a === null || b === null) return true;
  return a <= b ? mins >= a && mins < b : mins >= a || mins < b;
}
__name(inWorkHours, "inWorkHours");
function previewOf(type, text) {
  if (type === "image") return "\u{1F4F7} \u0635\u0648\u0631\u0629";
  if (type === "video") return "\u{1F3AC} \u0641\u064A\u062F\u064A\u0648";
  if (type === "voice") return "\u{1F3A4} \u0631\u0633\u0627\u0644\u0629 \u0635\u0648\u062A\u064A\u0629";
  if (type === "file") return "\u{1F4CE} \u0645\u0644\u0641";
  if (type === "quote") return "\u{1F4B0} \u0639\u0631\u0636 \u0633\u0639\u0631";
  if (type === "order") return "\u{1F4DD} \u0637\u0644\u0628 \u0625\u0639\u0644\u0627\u0646";
  return String(text || "").replace(/\s+/g, " ").slice(0, 80);
}
__name(previewOf, "previewOf");
function padSeq(n) {
  return String(n).padStart(10, "0");
}
__name(padSeq, "padSeq");
function sniffChatFile(b, kind) {
  const eq = /* @__PURE__ */ __name((off, arr) => b.length >= off + arr.length && arr.every((v, i) => b[off + i] === v), "eq");
  const img = sniffMedia(b);
  if (kind === "voice") {
    if (eq(4, [102, 116, 121, 112])) return { mime: "audio/mp4", ext: "m4a", kind: "voice" };
    if (eq(0, [255, 241]) || eq(0, [255, 249])) return { mime: "audio/aac", ext: "aac", kind: "voice" };
    if (eq(0, [79, 103, 103, 83])) return { mime: "audio/ogg", ext: "ogg", kind: "voice" };
    if (eq(0, [26, 69, 223, 163])) return { mime: "audio/webm", ext: "webm", kind: "voice" };
    if (eq(0, [35, 33, 65, 77, 82])) return { mime: "audio/amr", ext: "amr", kind: "voice" };
    return null;
  }
  if (img) return { mime: img.mime, ext: img.ext, kind: img.kind };
  if (kind === "file") {
    if (eq(0, [37, 80, 68, 70])) return { mime: "application/pdf", ext: "pdf", kind: "file" };
    if (eq(0, [80, 75, 3, 4])) return { mime: "application/zip", ext: "zip", kind: "file" };
    if (eq(0, [208, 207, 17, 224])) return { mime: "application/msword", ext: "doc", kind: "file" };
  }
  return null;
}
__name(sniffChatFile, "sniffChatFile");
function cleanName(s) {
  return String(s || "\u0645\u0644\u0641").replace(/[\u0000-\u001f\\/:*?"<>|]/g, "_").slice(0, 80) || "\u0645\u0644\u0641";
}
__name(cleanName, "cleanName");
var ChatHub = class {
  static {
    __name(this, "ChatHub");
  }
  constructor(state, env) {
    this.state = state;
    this.s = state.storage;
    this.env = env;
    this._rl = /* @__PURE__ */ new Map();
    this._lastPush = /* @__PURE__ */ new Map();
    try {
      if (typeof WebSocketRequestResponsePair !== "undefined" && state.setWebSocketAutoResponse) state.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
    } catch (e) {
    }
  }
  rl(key, limit, windowMs) {
    const now = Date.now();
    if (this._rl.size > 5e3) this._rl.clear();
    let r = this._rl.get(key);
    if (!r || now > r.reset) {
      r = { n: 0, reset: now + windowMs };
      this._rl.set(key, r);
    }
    r.n++;
    return r.n <= limit;
  }
  async fetch(request) {
    if ((request.headers.get("upgrade") || "").toLowerCase() === "websocket") return this.handleWs(request);
    let body;
    try {
      body = await request.json();
    } catch (e) {
      return new Response(JSON.stringify({ ok: false, error: "bad_request" }), { status: 400 });
    }
    try {
      const fn = this["op_" + body.op];
      if (typeof fn !== "function") fail(404, "unknown_op", "\u0639\u0645\u0644\u064A\u0629 \u063A\u064A\u0631 \u0645\u0639\u0631\u0648\u0641\u0629.");
      const out = await fn.call(this, body.p || {});
      return new Response(JSON.stringify(Object.assign({ ok: true }, out || {})), { status: 200 });
    } catch (e) {
      const [b, st] = errorBody(e);
      if (!(e instanceof HttpError)) console.log("ChatHub error", e && e.stack);
      return new Response(JSON.stringify(b), { status: st });
    }
  }
  // ------------------------------------------------------------ sockets
  socketsOf(tag) {
    let list = [];
    try {
      list = this.state.getWebSockets(tag) || [];
    } catch (e) {
      list = [];
    }
    return list.filter((w) => w.readyState === void 0 || w.readyState === 1);
  }
  adminOnline() {
    return this.socketsOf("a").length > 0;
  }
  userOnline(thread) {
    return this.socketsOf("u:" + thread).length > 0;
  }
  push(tag, ev) {
    const data = JSON.stringify(ev);
    for (const w of this.socketsOf(tag)) {
      try {
        w.send(data);
      } catch (e) {
      }
    }
  }
  emit(thread, ev, sides) {
    if (!sides || sides.indexOf("admin") >= 0) this.push("a", ev);
    if (!sides || sides.indexOf("user") >= 0) this.push("u:" + thread, ev);
  }
  async handleWs(request) {
    const side = request.headers.get("x-side") === "admin" ? "admin" : "user";
    const thread = String(request.headers.get("x-thread") || "");
    if (side === "user" && !thread) return new Response("bad", { status: 400 });
    const pair = new WebSocketPair();
    const client = pair[0], server = pair[1];
    this.state.acceptWebSocket(server, [side === "admin" ? "a" : "u:" + thread]);
    server.serializeAttachment({ side, thread });
    try {
      server.send(JSON.stringify({ t: "hello", side, server_time: Date.now() }));
    } catch (e) {
    }
    if (side === "user") {
      const t = await this.s.get("t:" + thread);
      if (t) {
        t.user_seen = Date.now();
        await this.s.put("t:" + thread, t);
      }
      this.push("a", { t: "presence", thread, side: "user", online: true });
    } else {
      this.push("a", { t: "presence", side: "admin", online: true });
      await this.broadcastAdminPresence();
    }
    return new Response(null, { status: 101, webSocket: client });
  }
  async broadcastAdminPresence() {
    const online = this.adminOnline();
    try {
      for (const w of this.state.getWebSockets()) {
        const a = w.deserializeAttachment ? w.deserializeAttachment() : null;
        if (a && a.side === "user") {
          try {
            w.send(JSON.stringify({ t: "presence", side: "admin", online }));
          } catch (e) {
          }
        }
      }
    } catch (e) {
    }
  }
  async webSocketMessage(ws, message) {
    if (typeof message !== "string" || message.length > 2e3) return;
    if (message === "ping") {
      try {
        ws.send("pong");
      } catch (e) {
      }
      return;
    }
    let m;
    try {
      m = JSON.parse(message);
    } catch (e) {
      return;
    }
    const a = ws.deserializeAttachment ? ws.deserializeAttachment() : null;
    if (!a || !m) return;
    if (m.t === "typing") {
      if (!this.rl("typ:" + (a.thread || "a"), 30, 1e4)) return;
      if (a.side === "user") this.push("a", { t: "typing", thread: a.thread, side: "user", on: !!m.on });
      else if (m.thread) this.push("u:" + String(m.thread), { t: "typing", thread: String(m.thread), side: "admin", on: !!m.on });
    } else if (m.t === "read") {
      const thread = a.side === "user" ? a.thread : String(m.thread || "");
      if (thread) {
        try {
          await this.op_read({ thread, side: a.side, upto: m.upto });
        } catch (e) {
        }
      }
    } else if (m.t === "ping") {
      try {
        ws.send(JSON.stringify({ t: "pong", server_time: Date.now() }));
      } catch (e) {
      }
    }
  }
  async webSocketClose(ws) {
    try {
      ws.close(1e3, "bye");
    } catch (e) {
    }
    const a = ws.deserializeAttachment ? ws.deserializeAttachment() : null;
    if (!a) return;
    if (a.side === "user") {
      const t = await this.s.get("t:" + a.thread);
      if (t) {
        t.user_seen = Date.now();
        await this.s.put("t:" + a.thread, t);
      }
      if (!this.socketsOf("u:" + a.thread).filter((w) => w !== ws).length) this.push("a", { t: "presence", thread: a.thread, side: "user", online: false, last_seen: Date.now() });
    } else {
      const others = this.socketsOf("a").filter((w) => w !== ws);
      try { await this.s.put("admin_last_active", Date.now()); } catch (e) { }
      if (!others.length) {
        try {
          for (const w of this.state.getWebSockets()) {
            const x = w.deserializeAttachment ? w.deserializeAttachment() : null;
            if (x && x.side === "user") {
              try {
                w.send(JSON.stringify({ t: "presence", side: "admin", online: false }));
              } catch (e) {
              }
            }
          }
        } catch (e) {
        }
      }
    }
  }
  async webSocketError(ws) {
    await this.webSocketClose(ws);
  }
  // ------------------------------------------------------------ push (webhook) — اختياري
  async notify(side, thread, title, body, extra) {
    const url = this.env.PUSH_WEBHOOK_URL;
    if (!url) return;
    const k = side + ":" + thread;
    const last = this._lastPush.get(k) || 0;
    if (Date.now() - last < 3e4) return;
    this._lastPush.set(k, Date.now());
    if (this._lastPush.size > 3e3) this._lastPush.clear();
    const p = fetch(url, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(Object.assign({ kind: "chat", to: side, user_key: side === "user" ? thread : void 0, thread, title, body }, extra || {})) }).catch(() => {
    });
    try {
      this.state.waitUntil(p);
    } catch (e) {
    }
  }
  // ------------------------------------------------------------ threads
  async getThread(id, create, info) {
    let t = await this.s.get("t:" + id);
    if (!t && create) {
      const now = Date.now();
      t = {
        id,
        user_key: id,
        adv_id: info && info.adv_id || "",
        name: cleanText(info && info.name, 100) || "\u0645\u0639\u0644\u0646",
        email: cleanText(info && info.email, 120),
        uid: cleanText(info && info.uid, 120),
        status: "open",
        tags: [],
        assigned: "",
        notes: "",
        banned: false,
        archived: false,
        muted: false,
        created: now,
        last_ts: now,
        last_seq: 0,
        last_from: "",
        prev_enc: null,
        unread_admin: 0,
        unread_user: 0,
        user_seen: 0,
        admin_seen: 0,
        auto_reply_at: 0
      };
      await this.s.put("t:" + id, t);
    }
    return t || null;
  }
  async threadView(t, origin) {
    let prev = "";
    if (t.prev_enc) prev = (await decJson(this.env, t.id, t.prev_enc)).text || "";
    return {
      id: t.id,
      adv_id: t.adv_id,
      name: t.name,
      email: t.email,
      status: t.status,
      tags: t.tags,
      assigned: t.assigned,
      notes: t.notes,
      banned: !!t.banned,
      archived: !!t.archived,
      created: t.created,
      last_ts: t.last_ts,
      last_seq: t.last_seq,
      last_from: t.last_from,
      preview: prev,
      unread_admin: t.unread_admin,
      unread_user: t.unread_user,
      user_seen: t.user_seen,
      user_id: t.uid || "",
      online: this.userOnline(t.id)
    };
  }
  userThreadView(t) {
    // 18.2: no internal id in anything the user receives
    return { status: t.status, last_seq: t.last_seq, unread: t.unread_user, banned: !!t.banned, created: t.created };
  }
  async op_thread_open(p) {
    const id = cleanText(p.user_key, 120);
    if (!id) fail(400, "invalid_user", "\u0645\u0633\u062A\u062E\u062F\u0645 \u063A\u064A\u0631 \u0635\u0627\u0644\u062D.");
    const t = await this.getThread(id, true, p);
    if (p.uid && !t.uid) {
      t.uid = cleanText(p.uid, 120);
      await this.s.put("t:" + id, t);
    }
    if (p.name && t.name !== cleanText(p.name, 100) && t.name === "\u0645\u0639\u0644\u0646") {
      t.name = cleanText(p.name, 100);
      await this.s.put("t:" + id, t);
    }
    return { thread: this.userThreadView(t) };
  }
  async op_threads_list(p) {
    const all = await this.s.list({ prefix: "t:" });
    const q = cleanText(p.q, 60).toLowerCase();
    const out = [];
    for (const t of all.values()) {
      if (p.archived ? !t.archived : t.archived) continue;
      if (p.status && t.status !== p.status) continue;
      if (p.unread && !t.unread_admin) continue;
      if (p.tag && (t.tags || []).indexOf(p.tag) < 0) continue;
      if (p.assigned && t.assigned !== p.assigned) continue;
      if (q && (t.name || "").toLowerCase().indexOf(q) < 0 && (t.email || "").toLowerCase().indexOf(q) < 0 && (t.tags || []).join(" ").toLowerCase().indexOf(q) < 0
        && String(t.id || "").toLowerCase().indexOf(q) < 0 && String(t.uid || "").toLowerCase().indexOf(q) < 0) continue;
      out.push(t);
    }
    out.sort((a, b) => (b.unread_admin ? 1 : 0) - (a.unread_admin ? 1 : 0) || b.last_ts - a.last_ts);
    const lim = int(p.limit, 1, 300, 100);
    const views = [];
    let totalUnread = 0;
    for (const t of all.values()) if (!t.archived) totalUnread += t.unread_admin || 0;
    for (const t of out.slice(0, lim)) views.push(await this.threadView(t));
    return { threads: views, unread_total: totalUnread, admin_online: this.adminOnline() };
  }
  async op_unread_total() {
    const all = await this.s.list({ prefix: "t:" });
    let n = 0, threads = 0;
    for (const t of all.values()) if (!t.archived && t.unread_admin) {
      n += t.unread_admin;
      threads++;
    }
    return { unread: n, threads };
  }
  async op_thread_update(p) {
    const t = await this.s.get("t:" + p.thread);
    if (!t) fail(404, "not_found", "\u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F\u0629.");
    if (p.status !== void 0) t.status = oneOf(p.status, CHAT_STATUSES, t.status);
    if (p.tags !== void 0) t.tags = strList(p.tags, 10, 24);
    if (p.assigned !== void 0) t.assigned = cleanText(p.assigned, 40);
    if (p.notes !== void 0) t.notes = cleanText(p.notes, 3e3);
    if (p.banned !== void 0) t.banned = bool(p.banned, false);
    if (p.archived !== void 0) t.archived = bool(p.archived, false);
    if (p.name !== void 0 && cleanText(p.name, 100)) t.name = cleanText(p.name, 100);
    if (p.adv_id !== void 0) t.adv_id = cleanText(p.adv_id, 40);
    await this.s.put("t:" + t.id, t);
    this.emit(t.id, { t: "thread", thread: t.id, status: t.status, banned: !!t.banned }, ["admin", "user"]);
    return { thread: await this.threadView(t) };
  }
  // ------------------------------------------------------------ messages
  async viewMsg(m, origin) {
    const d = await decJson(this.env, m.thread, m.enc);
    const v = { seq: m.seq, id: m.id, from: m.from, type: m.type, ts: m.ts, delivered: m.delivered || 0, read: m.read || 0, deleted: !!m.deleted };
    if (m.deleted) {
      v.text = "";
      return v;
    }
    v.text = d.text || "";
    if (d.dev) v.dev = { name: cleanText(d.dev.name, 40), avatar: String(d.dev.avatar || "") };
    if (d.reply) v.reply = d.reply;
    if (d.quote) v.quote = Object.assign({}, d.quote, { state: m.qstate || "pending" });
    if (d.order) v.order = d.order;
    if (d.media) {
      v.media = Object.assign({}, d.media);
      const exp = Date.now() + CHAT_MEDIA_TTL_MS;
      const sig = await mediaSign(this.env, d.media.id, m.thread, exp);
      v.media.url = origin + "/chat/media/" + d.media.id + "?t=" + encodeURIComponent(m.thread) + "&exp=" + exp + "&sig=" + sig;
    }
    return v;
  }
  async op_messages(p) {
    const thread = cleanText(p.thread, 120);
    const t = await this.s.get("t:" + thread);
    if (!t) return { messages: [], thread: null };
    const side = p.side === "admin" ? "admin" : "user";
    const limit = int(p.limit, 1, 100, 40);
    let rows;
    const after = int(p.after, 0, 1e12, 0);
    if (after > 0) {
      const all = await this.s.list({ prefix: "m:" + thread + ":", start: "m:" + thread + ":" + padSeq(after + 1), limit });
      rows = [...all.values()];
    } else {
      const before = int(p.before, 0, 1e12, 0);
      const opts = { prefix: "m:" + thread + ":", reverse: true, limit };
      if (before) opts.end = "m:" + thread + ":" + padSeq(before);
      const all = await this.s.list(opts);
      rows = [...all.values()].reverse();
    }
    const other = side === "admin" ? "user" : "admin";
    let upto = 0;
    const now = Date.now();
    for (const m of rows) {
      if (m.from === other && !m.delivered) {
        m.delivered = now;
        upto = Math.max(upto, m.seq);
        await this.s.put("m:" + thread + ":" + padSeq(m.seq), m);
      }
    }
    if (upto) this.emit(thread, { t: "delivered", thread, upto, ts: now }, [other]);
    const out = [];
    for (const m of rows) {
      const v = await this.viewMsg(m, p.origin);
      if (side === "admin" && m.dev_id) v.dev_id = m.dev_id;
      out.push(v);
    }
    return { messages: out, thread: side === "admin" ? await this.threadView(t) : this.userThreadView(t), admin_online: this.adminOnline() };
  }
  async op_send(p) {
    const side = p.side === "admin" ? "admin" : "user";
    const thread = cleanText(p.thread, 120);
    const t = await this.getThread(thread, side === "user", p);
    if (!t) fail(404, "not_found", "\u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F\u0629.");
    if (side === "user" && t.banned) fail(403, "banned", "\u062A\u0645 \u062A\u0642\u064A\u064A\u062F \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629 \u0645\u0646 \u0642\u0628\u0644 \u0627\u0644\u0625\u062F\u0627\u0631\u0629.");
    if (!this.rl("send:" + side + ":" + thread, side === "user" ? 20 : 120, 6e4)) fail(429, "rate_limited", "\u0631\u0633\u0627\u0626\u0644 \u0643\u062B\u064A\u0631\u0629. \u0627\u0646\u062A\u0638\u0631 \u0642\u0644\u064A\u0644\u0627\u064B.");
    const type = oneOf(p.type, CHAT_TYPES, "text");
    if (type === "system") fail(400, "invalid_type", "\u0646\u0648\u0639 \u063A\u064A\u0631 \u0645\u0633\u0645\u0648\u062D.");
    if (side === "user" && type === "quote") fail(403, "forbidden", "\u063A\u064A\u0631 \u0645\u0633\u0645\u0648\u062D.");
    const clientId = cleanText(p.client_id, 40);
    if (clientId) {
      const dup = await this.s.get("cid:" + thread + ":" + clientId);
      if (dup) {
        const m2 = await this.s.get("m:" + thread + ":" + padSeq(dup));
        if (m2) return { message: await this.viewMsg(m2, p.origin), duplicate: true };
      }
    }
    const text = cleanText(p.text, CHAT_MAX_TEXT);
    const payload = {};
    if (text) payload.text = text;
    if (side === "admin" && p.dev && typeof p.dev === "object") payload.dev = { name: cleanText(p.dev.name, 40), avatar: String(p.dev.avatar || "").slice(0, 400) };
    if (p.reply_to) {
      const rs = int(p.reply_to, 1, 1e12, 0);
      const rm = rs ? await this.s.get("m:" + thread + ":" + padSeq(rs)) : null;
      if (rm && !rm.deleted) {
        const rd = await decJson(this.env, thread, rm.enc);
        payload.reply = { seq: rm.seq, from: rm.from, preview: previewOf(rm.type, rd.text) };
      }
    }
    if (type === "image" || type === "video" || type === "voice" || type === "file") {
      const meta = await this.s.get("media:" + cleanText(p.media_id, 40));
      if (!meta || meta.thread !== thread || !meta.complete) fail(400, "media_not_ready", "\u0627\u0644\u0645\u0644\u0641 \u0644\u0645 \u064A\u0643\u062A\u0645\u0644 \u0631\u0641\u0639\u0647.");
      if (meta.used) fail(400, "media_used", "\u0647\u0630\u0627 \u0627\u0644\u0645\u0644\u0641 \u0645\u064F\u0631\u0633\u0644 \u0633\u0627\u0628\u0642\u0627\u064B.");
      meta.used = true;
      await this.s.put("media:" + meta.id, meta);
      payload.media = { id: meta.id, kind: meta.kind, mime: meta.mime, size: meta.size, name: meta.name, dur: meta.dur || 0, wave: meta.wave || [], w: meta.w || 0, h: meta.h || 0 };
      if (meta.kind !== type) payload.media.kind = meta.kind;
    } else if (type === "quote") {
      const qd = p.quote || {};
      payload.quote = {
        slot: cleanText(qd.slot, 20),
        slot_label: cleanText(qd.slot_label, 60),
        days: int(qd.days, 1, MAX_CAMPAIGN_DAYS, 1),
        total: num(qd.total, 0, 1e9, 0),
        currency: cleanText(qd.currency, 8) || "USD",
        note: cleanText(qd.note, 600),
        campaign_id: cleanText(qd.campaign_id, 40),
        expires_at: qd.expires_at ? parseTime(qd.expires_at, 0) || 0 : 0
      };
      if (!payload.quote.total) fail(400, "invalid_quote", "\u0623\u062F\u062E\u0644 \u0645\u0628\u0644\u063A \u0639\u0631\u0636 \u0627\u0644\u0633\u0639\u0631.");
      if (!text) payload.text = "\u0639\u0631\u0636 \u0633\u0639\u0631: " + (payload.quote.slot_label || payload.quote.slot) + " \u2014 " + payload.quote.days + " \u064A\u0648\u0645";
    } else if (type === "order") {
      const o = p.order && typeof p.order === "object" ? p.order : null;
      if (!o) fail(400, "invalid_order", "\u0627\u0644\u0637\u0644\u0628 \u063A\u064A\u0631 \u0635\u0627\u0644\u062D.");
      const js = JSON.stringify(o);
      if (js.length > 6e3) fail(413, "too_large", "\u0627\u0644\u0637\u0644\u0628 \u0643\u0628\u064A\u0631.");
      payload.order = JSON.parse(js);
      if (!text) payload.text = "\u0637\u0644\u0628 \u0625\u0639\u0644\u0627\u0646 \u062C\u062F\u064A\u062F";
    } else if (!text) fail(400, "empty_message", "\u0627\u0643\u062A\u0628 \u0631\u0633\u0627\u0644\u0629 \u0623\u0648\u0644\u0627\u064B.");
    const now = Date.now();
    t.last_seq += 1;
    const m = {
      seq: t.last_seq,
      thread,
      id: randId("m_", 8),
      from: side,
      type,
      ts: now,
      client_id: clientId,
      enc: await encJson(this.env, thread, payload),
      delivered: (side === "user" ? this.adminOnline() : this.userOnline(thread)) ? now : 0,
      read: 0,
      deleted: false,
      qstate: type === "quote" ? "pending" : void 0,
      dev_id: side === "admin" ? cleanText(p.dev_id, 40) : void 0
    };
    await this.s.put("m:" + thread + ":" + padSeq(m.seq), m);
    if (clientId) await this.s.put("cid:" + thread + ":" + clientId, m.seq);
    t.last_ts = now;
    t.last_from = side;
    t.prev_enc = await encJson(this.env, thread, { text: previewOf(type, payload.text) });
    if (side === "user") {
      t.unread_admin = (t.unread_admin || 0) + 1;
      if (t.status === "closed" || t.status === "won") t.status = "open";
    } else {
      t.unread_user = (t.unread_user || 0) + 1;
      if (type === "quote") t.status = "quoted";
      else if (t.status === "open") t.status = "pending";
    }
    const view = await this.viewMsg(m, p.origin);
    let auto = null;
    if (side === "user" && !this.adminOnline() && now - (t.auto_reply_at || 0) > 6 * 36e5) {
      try {
        const st = (await this.env.ADS_CORE.get(this.env.ADS_CORE.idFromName("core")).fetch("https://core/op", { method: "POST", body: JSON.stringify({ op: "settings_public", p: {} }) }).then((r) => r.json())).settings;
        if (st && st.auto_reply && !inWorkHours(st, now)) {
          t.last_seq += 1;
          const am = { seq: t.last_seq, thread, id: randId("m_", 8), from: "admin", type: "system", ts: now + 1, enc: await encJson(this.env, thread, { text: cleanText(st.auto_reply, 600), auto: true }), delivered: this.userOnline(thread) ? now : 0, read: 0, deleted: false };
          await this.s.put("m:" + thread + ":" + padSeq(am.seq), am);
          t.auto_reply_at = now;
          t.unread_user = (t.unread_user || 0) + 1;
          auto = await this.viewMsg(am, p.origin);
        }
      } catch (e) {
      }
    }
    await this.s.put("t:" + thread, t);
    const tv = await this.threadView(t);
    this.emit(thread, { t: "msg", thread, msg: view, thread_info: tv }, side === "user" ? ["admin", "user"] : ["user", "admin"]);
    if (auto) this.emit(thread, { t: "msg", thread, msg: auto, thread_info: tv }, ["admin", "user"]);
    if (side === "user" && !this.adminOnline()) await this.notify("admin", thread, "\u0631\u0633\u0627\u0644\u0629 \u062C\u062F\u064A\u062F\u0629 \u0645\u0646 \u0645\u0639\u0644\u0646", t.name + ": " + previewOf(type, payload.text));
    if (side === "admin" && !this.userOnline(thread)) await this.notify("user", thread, payload.dev && payload.dev.name || "Cigram", previewOf(type, payload.text), { dev_name: payload.dev && payload.dev.name || "", ts: String(now) });
    return { message: view, auto, thread: side === "admin" ? tv : this.userThreadView(t) };
  }
  async op_read(p) {
    const thread = cleanText(p.thread, 120);
    const t = await this.s.get("t:" + thread);
    if (!t) return {};
    const side = p.side === "admin" ? "admin" : "user";
    const other = side === "admin" ? "user" : "admin";
    const upto = int(p.upto, 0, 1e12, t.last_seq) || t.last_seq;
    const now = Date.now();
    const rows = await this.s.list({ prefix: "m:" + thread + ":", reverse: true, limit: 120 });
    let changed = 0;
    for (const m of rows.values()) {
      if (m.seq > upto || m.from !== other || m.read) continue;
      m.read = now;
      if (!m.delivered) m.delivered = now;
      await this.s.put("m:" + thread + ":" + padSeq(m.seq), m);
      changed++;
    }
    if (side === "admin") {
      t.unread_admin = 0;
      t.admin_seen = now;
    } else {
      t.unread_user = 0;
      t.user_seen = now;
    }
    await this.s.put("t:" + thread, t);
    this.emit(thread, { t: "read", thread, side, upto, ts: now }, [other]);
    if (side === "user") this.push("a", { t: "thread_unread", thread, unread_admin: t.unread_admin });
    return { changed };
  }
  async op_delete_msg(p) {
    const thread = cleanText(p.thread, 120);
    const key = "m:" + thread + ":" + padSeq(int(p.seq, 1, 1e12, 0));
    const m = await this.s.get(key);
    if (!m) fail(404, "not_found", "\u0627\u0644\u0631\u0633\u0627\u0644\u0629 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F\u0629.");
    if (m.deleted) return {};
    const d = await decJson(this.env, thread, m.enc);
    if (d.media) await this.dropMedia(d.media.id);
    m.deleted = true;
    m.enc = await encJson(this.env, thread, {});
    await this.s.put(key, m);
    this.emit(thread, { t: "deleted", thread, seq: m.seq }, ["admin", "user"]);
    return {};
  }
  async op_quote_respond(p) {
    const thread = cleanText(p.thread, 120);
    const key = "m:" + thread + ":" + padSeq(int(p.seq, 1, 1e12, 0));
    const m = await this.s.get(key);
    if (!m || m.type !== "quote" || m.deleted) fail(404, "not_found", "\u0639\u0631\u0636 \u0627\u0644\u0633\u0639\u0631 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F.");
    if (m.qstate && m.qstate !== "pending") fail(409, "already_answered", "\u062A\u0645 \u0627\u0644\u0631\u062F \u0639\u0644\u0649 \u0647\u0630\u0627 \u0627\u0644\u0639\u0631\u0636 \u0645\u0633\u0628\u0642\u0627\u064B.");
    const d = await decJson(this.env, thread, m.enc);
    if (d.quote && d.quote.expires_at && d.quote.expires_at < Date.now()) fail(410, "quote_expired", "\u0627\u0646\u062A\u0647\u062A \u0635\u0644\u0627\u062D\u064A\u0629 \u0639\u0631\u0636 \u0627\u0644\u0633\u0639\u0631.");
    const accept = !!p.accept;
    m.qstate = accept ? "accepted" : "declined";
    await this.s.put(key, m);
    const t = await this.s.get("t:" + thread);
    if (t) {
      t.status = accept ? "won" : "pending";
      await this.s.put("t:" + thread, t);
    }
    const sys = await this.op_send_system({ thread, text: accept ? "\u2705 \u0648\u0627\u0641\u0642 \u0627\u0644\u0645\u0639\u0644\u0646 \u0639\u0644\u0649 \u0639\u0631\u0636 \u0627\u0644\u0633\u0639\u0631." : "\u274C \u0631\u0641\u0636 \u0627\u0644\u0645\u0639\u0644\u0646 \u0639\u0631\u0636 \u0627\u0644\u0633\u0639\u0631.", origin: p.origin });
    this.emit(thread, { t: "quote_state", thread, seq: m.seq, state: m.qstate }, ["admin", "user"]);
    return { state: m.qstate, message: sys };
  }
  async op_send_system(p) {
    const thread = cleanText(p.thread, 120);
    const t = await this.s.get("t:" + thread);
    if (!t) return null;
    const now = Date.now();
    t.last_seq += 1;
    const m = { seq: t.last_seq, thread, id: randId("m_", 8), from: "admin", type: "system", ts: now, enc: await encJson(this.env, thread, { text: cleanText(p.text, 300), sys: true }), delivered: now, read: now, deleted: false };
    await this.s.put("m:" + thread + ":" + padSeq(m.seq), m);
    t.last_ts = now;
    t.unread_admin = (t.unread_admin || 0) + 1;
    await this.s.put("t:" + thread, t);
    const view = await this.viewMsg(m, p.origin || "");
    this.emit(thread, { t: "msg", thread, msg: view, thread_info: await this.threadView(t) }, ["admin", "user"]);
    return view;
  }
  async op_find_order(p) {
    const thread = cleanText(p.thread, 120);
    const t = await this.s.get("t:" + thread);
    if (!t) fail(404, "not_found", "\u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F\u0629.");
    const rows = await this.s.list({ prefix: "m:" + thread + ":", reverse: true, limit: 200 });
    for (const m of rows.values()) {
      if (m.type !== "order" || m.deleted) continue;
      if (p.seq && m.seq !== int(p.seq, 1, 1e12, 0)) continue;
      const d = await decJson(this.env, thread, m.enc);
      if (d.order) return { order: d.order, seq: m.seq, thread: await this.threadView(t) };
    }
    fail(404, "no_order", "\u0644\u0627 \u064A\u0648\u062C\u062F \u0637\u0644\u0628 \u0625\u0639\u0644\u0627\u0646 \u0641\u064A \u0647\u0630\u0647 \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629.");
  }
  async op_export(p) {
    const thread = cleanText(p.thread, 120);
    const t = await this.s.get("t:" + thread);
    if (!t) fail(404, "not_found", "\u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F\u0629.");
    const rows = await this.s.list({ prefix: "m:" + thread + ":", limit: 5e3 });
    const out = [];
    for (const m of rows.values()) {
      const v = await this.viewMsg(m, p.origin || "");
      if (v.media) delete v.media.url;
      out.push(v);
    }
    return { thread: await this.threadView(t), messages: out, exported_at: Date.now() };
  }
  // ------------------------------------------------------------ media meta
  async op_media_init(p) {
    const thread = cleanText(p.thread, 120);
    const t = await this.getThread(thread, false);
    if (!t) fail(404, "not_found", "\u0627\u0628\u062F\u0623 \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629 \u0623\u0648\u0644\u0627\u064B.");
    if (p.side === "user" && t.banned) fail(403, "banned", "\u062A\u0645 \u062A\u0642\u064A\u064A\u062F \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629.");
    if (!this.rl("up:" + thread, 30, 6e5)) fail(429, "rate_limited", "\u0631\u0641\u0639 \u0645\u0644\u0641\u0627\u062A \u0643\u062B\u064A\u0631. \u0627\u0646\u062A\u0638\u0631 \u0642\u0644\u064A\u0644\u0627\u064B.");
    const size = Math.floor(Number(p.size) || 0);
    if (size < 1 || size > CHAT_MAX_FILE) fail(413, "too_large", "\u0627\u0644\u0645\u0644\u0641 \u0641\u0627\u0631\u063A \u0623\u0648 \u0623\u0643\u0628\u0631 \u0645\u0646 25MB.");
    const kind = oneOf(p.kind, ["image", "video", "voice", "file"], "file");
    const id = randId("f", 14).replace(/[^A-Za-z0-9]/g, "x");
    const meta = {
      id,
      thread,
      owner: p.side === "admin" ? "admin" : "user",
      kind,
      size,
      name: cleanName(p.name),
      mime: "",
      parts: Math.ceil(size / CHAT_PART_SIZE),
      dur: int(p.dur, 0, 36e5, 0),
      w: int(p.w, 0, 2e4, 0),
      h: int(p.h, 0, 2e4, 0),
      wave: Array.isArray(p.wave) ? p.wave.slice(0, 64).map((x) => int(x, 0, 255, 0)) : [],
      created: Date.now(),
      complete: false,
      used: false,
      got: 0
    };
    await this.s.put("media:" + id, meta);
    await this.ensureAlarm();
    return { media_id: id, part_size: CHAT_PART_SIZE, parts: meta.parts };
  }
  async op_media_meta(p) {
    const meta = await this.s.get("media:" + cleanText(p.id, 40));
    if (!meta) fail(404, "not_found", "\u0627\u0644\u0645\u0644\u0641 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F.");
    if (p.thread && meta.thread !== p.thread) fail(403, "forbidden", "\u063A\u064A\u0631 \u0645\u0633\u0645\u0648\u062D.");
    return { meta };
  }
  async op_media_set(p) {
    const meta = await this.s.get("media:" + cleanText(p.id, 40));
    if (!meta) fail(404, "not_found", "\u0627\u0644\u0645\u0644\u0641 \u063A\u064A\u0631 \u0645\u0648\u062C\u0648\u062F.");
    Object.assign(meta, p.patch || {});
    await this.s.put("media:" + meta.id, meta);
    return { meta };
  }
  async dropMedia(id) {
    const meta = await this.s.get("media:" + id);
    if (!meta) return;
    const b = bucketOf(this.env);
    if (b) for (let i = 0; i < meta.parts; i++) {
      try {
        await b.delete(CHAT_MEDIA_PREFIX + id + "/" + i);
      } catch (e) {
      }
    }
    await this.s.delete("media:" + id);
  }
  async op_purge(p) {
    const thread = cleanText(p.thread, 120);
    const msgs = await this.s.list({ prefix: "m:" + thread + ":" });
    const keys = [];
    for (const [k, m] of msgs) {
      keys.push(k);
      if (m.client_id) keys.push("cid:" + thread + ":" + m.client_id);
    }
    const metas = await this.s.list({ prefix: "media:" });
    for (const meta of metas.values()) if (meta.thread === thread) await this.dropMedia(meta.id);
    for (let i = 0; i < keys.length; i += 100) await this.s.delete(keys.slice(i, i + 100));
    await this.s.delete("t:" + thread);
    try {
      for (const w of this.socketsOf("u:" + thread)) w.close(1e3, "purged");
    } catch (e) {
    }
    this.push("a", { t: "thread_gone", thread });
    return { purged: keys.length };
  }
  async op_presence(p) {
    const st = p.settings || null;
    const now = Date.now();
    let state = "offline";
    if (this.adminOnline()) state = "online";
    else if (st && !inWorkHours(st, now)) state = "off_hours";
    const last = this.adminOnline() ? now : (await this.s.get("admin_last_active")) || 0;
    return { state, last_active: last };
  }
  async op_templates_get() {
    return { templates: await this.s.get("templates") || [] };
  }
  async op_templates_set(p) {
    const list = (Array.isArray(p.templates) ? p.templates : []).slice(0, 30).map((t) => ({ title: cleanText(t.title, 40), body: cleanText(t.body, 1200) })).filter((t) => t.title && t.body);
    await this.s.put("templates", list);
    return { templates: list };
  }
  async ensureAlarm() {
    try {
      if (!await this.s.getAlarm()) await this.s.setAlarm(Date.now() + 6 * 36e5);
    } catch (e) {
    }
  }
  async alarm() {
    const now = Date.now();
    const metas = await this.s.list({ prefix: "media:" });
    for (const meta of metas.values()) if (!meta.used && now - meta.created > 24 * 36e5) await this.dropMedia(meta.id);
    const days = parseInt(this.env.CHAT_RETENTION_DAYS || "0", 10);
    if (days > 0) {
      const all = await this.s.list({ prefix: "t:" });
      for (const t of all.values()) if (t.archived && now - t.last_ts > days * DAY_MS) await this.op_purge({ thread: t.id });
    }
    try {
      await this.s.setAlarm(now + 6 * 36e5);
    } catch (e) {
    }
  }
};
function hubStub(env) {
  if (!env.CHAT_HUB) fail(503, "chat_not_configured", "\u0644\u0645 \u064A\u064F\u0631\u0628\u0637 Durable Object \u0628\u0627\u0633\u0645 CHAT_HUB.");
  return env.CHAT_HUB.get(env.CHAT_HUB.idFromName("hub"));
}
__name(hubStub, "hubStub");
async function hub(env, op, p) {
  const r = await hubStub(env).fetch("https://hub/op", { method: "POST", body: JSON.stringify({ op, p: p || {} }) });
  const body = await r.json();
  if (!body.ok) fail(r.status || 500, body.error || "error", body.message || "\u0641\u0634\u0644 \u0627\u0644\u0637\u0644\u0628.");
  return body;
}
__name(hub, "hub");
async function chatWsForward(request, env, side, thread) {
  if ((request.headers.get("upgrade") || "").toLowerCase() !== "websocket") fail(426, "upgrade_required", "\u0647\u0630\u0627 \u0627\u0644\u0645\u0633\u0627\u0631 \u0644\u0644\u0627\u062A\u0635\u0627\u0644 \u0627\u0644\u0644\u062D\u0638\u064A \u0641\u0642\u0637.");
  const h = new Headers(request.headers);
  h.set("x-side", side);
  h.set("x-thread", thread || "");
  h.delete("authorization");
  return hubStub(env).fetch(new Request("https://hub/ws", { headers: h }));
}
__name(chatWsForward, "chatWsForward");
async function requireConsent(env, userKey) {
  const c = await core(env, "consent_get", { user_key: userKey });
  if (!c.valid) fail(403, "consent_required", "\u064A\u062C\u0628 \u0627\u0644\u0645\u0648\u0627\u0641\u0642\u0629 \u0639\u0644\u0649 \u0633\u064A\u0627\u0633\u0627\u062A \u0627\u0644\u0625\u0639\u0644\u0627\u0646\u0627\u062A \u0627\u0644\u062D\u0627\u0644\u064A\u0629 \u0642\u0628\u0644 \u0627\u0644\u062A\u0648\u0627\u0635\u0644.", { current_version: c.current_version });
}
__name(requireConsent, "requireConsent");
function userNameOf(user) {
  return user.name || (user.email ? user.email.split("@")[0] : "") || "\u0645\u0639\u0644\u0646";
}
__name(userNameOf, "userNameOf");
async function chatUploadRoutes(request, env, url, path, method, scope) {
  const q = /* @__PURE__ */ __name((k) => url.searchParams.get(k) || "", "q");
  const b = bucketOf(env);
  if (!b) fail(500, "no_bucket", "\u0644\u0627 \u064A\u0648\u062C\u062F R2 Bucket.");
  if (method === "POST" && path === "/init") {
    const body = await readJson(request, 2e4);
    const r = await hub(env, "media_init", Object.assign({}, body, { side: scope.side, thread: scope.thread }));
    return reply(r);
  }
  if (method === "PUT" && path === "/part") {
    const id = q("id"), n = parseInt(q("n"), 10);
    const m = (await hub(env, "media_meta", { id, thread: scope.thread })).meta;
    if (m.complete) fail(409, "already_complete", "\u0627\u0643\u062A\u0645\u0644 \u0627\u0644\u0645\u0644\u0641.");
    if (!(n >= 0 && n < m.parts)) fail(400, "bad_part", "\u0631\u0642\u0645 \u0627\u0644\u0642\u0637\u0639\u0629 \u063A\u064A\u0631 \u0635\u062D\u064A\u062D.");
    const len = parseInt(request.headers.get("content-length") || "0", 10);
    if (len > CHAT_PART_SIZE) fail(413, "too_large", "\u0627\u0644\u0642\u0637\u0639\u0629 \u0623\u0643\u0628\u0631 \u0645\u0646 1MB.");
    const buf = new Uint8Array(await request.arrayBuffer());
    const expect = n === m.parts - 1 ? m.size - n * CHAT_PART_SIZE : CHAT_PART_SIZE;
    if (buf.length !== expect) fail(400, "bad_part_size", "\u062D\u062C\u0645 \u0627\u0644\u0642\u0637\u0639\u0629 \u063A\u064A\u0631 \u0645\u0637\u0627\u0628\u0642 \u0644\u0644\u0645\u062A\u0648\u0642\u0639.");
    if (n === 0) {
      const t = sniffChatFile(buf, m.kind);
      if (!t) fail(415, "unsupported_type", "\u0646\u0648\u0639 \u0627\u0644\u0645\u0644\u0641 \u063A\u064A\u0631 \u0645\u062F\u0639\u0648\u0645.");
      if (m.kind !== "file" && t.kind !== m.kind) fail(415, "unsupported_type", "\u0627\u0644\u0645\u0644\u0641 \u0644\u0627 \u064A\u0637\u0627\u0628\u0642 \u0627\u0644\u0646\u0648\u0639 \u0627\u0644\u0645\u0639\u0644\u0646.");
      await hub(env, "media_set", { id, patch: { mime: t.mime, kind: t.kind, ext: t.ext } });
    }
    await b.put(CHAT_MEDIA_PREFIX + id + "/" + n, await mediaPartEncrypt(env, id, n, buf), { httpMetadata: { contentType: "application/octet-stream" } });
    return reply({ ok: true, n });
  }
  if (method === "GET" && path === "/status") {
    const id = q("id");
    const m = (await hub(env, "media_meta", { id, thread: scope.thread })).meta;
    const have = [];
    for (let i = 0; i < m.parts; i++) if (await b.head(CHAT_MEDIA_PREFIX + id + "/" + i)) have.push(i);
    return reply({ ok: true, parts: m.parts, have, complete: !!m.complete });
  }
  if (method === "POST" && path === "/complete") {
    const id = q("id");
    const m = (await hub(env, "media_meta", { id, thread: scope.thread })).meta;
    for (let i = 0; i < m.parts; i++) if (!await b.head(CHAT_MEDIA_PREFIX + id + "/" + i)) fail(409, "missing_parts", "\u0642\u0637\u0639 \u0646\u0627\u0642\u0635\u0629. \u0623\u0639\u062F \u0631\u0641\u0639 \u0627\u0644\u0642\u0637\u0639 \u0627\u0644\u0646\u0627\u0642\u0635\u0629.", { missing: i });
    if (!m.mime) fail(400, "unsupported_type", "\u0646\u0648\u0639 \u0627\u0644\u0645\u0644\u0641 \u063A\u064A\u0631 \u0645\u0639\u0631\u0648\u0641.");
    await hub(env, "media_set", { id, patch: { complete: true } });
    return reply({ ok: true, media_id: id });
  }
  return null;
}
__name(chatUploadRoutes, "chatUploadRoutes");
async function serveChatMedia(request, env, id, url) {
  const thread = url.searchParams.get("t") || "";
  const exp = parseInt(url.searchParams.get("exp") || "0", 10);
  const sig = url.searchParams.get("sig") || "";
  if (!/^f[A-Za-z0-9]{6,30}$/.test(id) || !thread || !(exp > Date.now()) || exp > Date.now() + 2 * CHAT_MEDIA_TTL_MS) return new Response("Forbidden", { status: 403, headers: corsHeaders() });
  const good = await mediaSign(env, id, thread, exp);
  if (!timingSafeEqual(good, sig)) return new Response("Forbidden", { status: 403, headers: corsHeaders() });
  let meta;
  try {
    meta = (await hub(env, "media_meta", { id, thread })).meta;
  } catch (e) {
    return new Response("Not found", { status: 404, headers: corsHeaders() });
  }
  if (!meta.complete) return new Response("Not found", { status: 404, headers: corsHeaders() });
  const b = bucketOf(env);
  let start = 0, end = meta.size - 1, status = 200;
  const range = request.headers.get("range");
  if (range) {
    const m = /^bytes=(\d*)-(\d*)$/.exec(range.trim());
    if (m && (m[1] || m[2])) {
      if (m[1] === "") {
        start = Math.max(0, meta.size - parseInt(m[2], 10));
      } else {
        start = parseInt(m[1], 10);
        if (m[2]) end = Math.min(end, parseInt(m[2], 10));
      }
      if (start >= meta.size || end < start) return new Response("Range Not Satisfiable", { status: 416, headers: corsHeaders({ "content-range": "bytes */" + meta.size }) });
      status = 206;
    }
  }
  const first = Math.floor(start / CHAT_PART_SIZE), last = Math.floor(end / CHAT_PART_SIZE);
  const { readable, writable } = new TransformStream();
  const writer = writable.getWriter();
  (async () => {
    try {
      for (let i = first; i <= last; i++) {
        const o = await b.get(CHAT_MEDIA_PREFIX + id + "/" + i);
        if (!o) throw new Error("missing part");
        const blob = new Uint8Array(await (o.arrayBuffer ? o.arrayBuffer() : new Response(o.body).arrayBuffer()));
        const plain = await mediaPartDecrypt(env, id, i, blob);
        const s = i === first ? start - i * CHAT_PART_SIZE : 0;
        const e = i === last ? end - i * CHAT_PART_SIZE + 1 : plain.length;
        await writer.write(plain.subarray(s, e));
      }
      await writer.close();
    } catch (e) {
      try {
        await writer.abort(e);
      } catch (x) {
      }
    }
  })();
  const headers = {
    "content-type": meta.mime || "application/octet-stream",
    "content-length": String(end - start + 1),
    "accept-ranges": "bytes",
    "cache-control": "private, max-age=600",
    "content-security-policy": "default-src 'none'; sandbox",
    "content-disposition": (meta.kind === "file" ? "attachment" : "inline") + "; filename*=UTF-8''" + encodeURIComponent(meta.name || "file")
  };
  if (status === 206) headers["content-range"] = "bytes " + start + "-" + end + "/" + meta.size;
  return new Response(readable, { status, headers: corsHeaders(headers) });
}
__name(serveChatMedia, "serveChatMedia");
async function purgeChatForUser(env, userKey) {
  if (!env.CHAT_HUB) return;
  try {
    await hub(env, "purge", { thread: userKey });
  } catch (e) {
  }
}
__name(purgeChatForUser, "purgeChatForUser");
async function notifyCampaignChange(env, c) {
  if (!env.CHAT_HUB || !c || !c.advertiser_id) return;
  const adv = (await core(env, "adv_get", { id: c.advertiser_id, origin: "" })).advertiser;
  const key = adv && adv.user_key;
  if (!key) return;
  const lines = {
    approved: "\u2705 \u062A\u0645\u062A \u0627\u0644\u0645\u0648\u0627\u0641\u0642\u0629 \u0639\u0644\u0649 \u062D\u0645\u0644\u062A\u0643 \xAB" + c.name + "\xBB \u0648\u0647\u064A \u0627\u0644\u0622\u0646 \u0645\u062C\u062F\u0648\u0644\u0629/\u0641\u0639\u0651\u0627\u0644\u0629 \u062D\u0633\u0628 \u0627\u0644\u062A\u0627\u0631\u064A\u062E.",
    rejected: "\u274C \u062A\u0645 \u0631\u0641\u0636 \u062D\u0645\u0644\u062A\u0643 \xAB" + c.name + "\xBB." + (c.reject_reason ? " \u0627\u0644\u0633\u0628\u0628: " + c.reject_reason : ""),
    paused: "\u23F8 \u062A\u0645 \u0625\u064A\u0642\u0627\u0641 \u062D\u0645\u0644\u062A\u0643 \xAB" + c.name + "\xBB \u0645\u0624\u0642\u062A\u0627\u064B."
  };
  let text = lines[c.status];
  if (!text) return;
  const t = await hub(env, "thread_open", { user_key: key, name: adv.name }).catch(() => null);
  if (!t) return;
  await hub(env, "send_system", { thread: key, text });
}
__name(notifyCampaignChange, "notifyCampaignChange");
async function routeChat(request, env, ctx, url, path) {
  const method = request.method;
  const origin = originOf(request, env);
  const q = /* @__PURE__ */ __name((k) => url.searchParams.get(k) || "", "q");
  if (method === "GET" && path === "/chat/presence") {
    let st = null;
    try {
      st = (await core(env, "settings_public", {})).settings;
    } catch (e) {
    }
    if (!env.CHAT_HUB) return reply({ ok: true, state: "offline" });
    const pr = await hub(env, "presence", { settings: st && { hours: st.hours } });
    pr.developer = { name: cleanText(env.DEV_NAME, 40) || "\u0645\u0637\u0648\u0651\u0631 Cigram", avatar: /^https:\/\//.test(String(env.DEV_AVATAR || "")) ? String(env.DEV_AVATAR) : "" };
    return reply(pr);
  }
  let m;
  if (method === "GET" && (m = /^\/chat\/media\/([A-Za-z0-9]+)$/.exec(path))) return serveChatMedia(request, env, m[1], url);
  if (path.indexOf("/chat/") === 0) {
    const user = await verifyUser(request, env);
    const thread = user.key;
    if (method === "GET" && path === "/chat/ws") return chatWsForward(request, env, "user", thread);
    if (method === "GET" && path === "/chat/thread") return reply(await hub(env, "thread_open", { user_key: thread, name: userNameOf(user), email: user.email, uid: user.id }));
    if (method === "GET" && path === "/chat/messages") {
      return reply(await hub(env, "messages", { thread, side: "user", after: q("after"), before: q("before"), limit: q("limit"), origin }));
    }
    if (method === "GET" && path === "/chat/unread") {
      const t = await hub(env, "thread_open", { user_key: thread, name: userNameOf(user), email: user.email });
      return reply({ ok: true, unread: t.thread.unread, last_seq: t.thread.last_seq });
    }
    if (method === "POST" && path === "/chat/send") {
      const body = await readJson(request, 12e3);
      // 18.2: private support messages do not need the advertising consent; an advertising ORDER still does
      if (body && body.type === "order") await requireConsent(env, thread);
      let adv = null;
      try {
        adv = (await core(env, "adv_ensure_user", { user_key: thread, name: userNameOf(user), email: user.email, origin })).advertiser;
      } catch (e) {
      }
      const r = await hub(env, "send", { side: "user", thread, name: userNameOf(user), email: user.email, adv_id: adv && adv.id, client_id: body.client_id, type: body.type, text: body.text, media_id: body.media_id, reply_to: body.reply_to, order: body.order, origin });
      return reply(r);
    }
    if (method === "POST" && path === "/chat/read") {
      const body = await readJson(request, 1e3);
      return reply(await hub(env, "read", { thread, side: "user", upto: body.upto }));
    }
    if (method === "POST" && path === "/chat/quote/respond") {
      const body = await readJson(request, 1e3);
      const r = await hub(env, "quote_respond", { thread, seq: body.seq, accept: body.accept, origin });
      return reply(r);
    }
    if (method === "POST" && path === "/chat/delete-all") {
      await purgeChatForUser(env, thread);
      return reply({ ok: true });
    }
    if (path.indexOf("/chat/upload/") === 0) {
      const r = await chatUploadRoutes(request, env, url, path.slice("/chat/upload".length), method, { side: "user", thread });
      if (r) return r;
    }
    return null;
  }
  if (path.indexOf("/admin/chat/") === 0) {
    const dev = await requireAdmin(request, env);
    const sub = path.slice("/admin/chat".length);
    // 18.2 roles: "support" developers read / answer / archive; destructive or account-level actions are owner-only
    if (["/purge", "/delete-message", "/convert-order", "/export"].indexOf(sub) >= 0 || (sub === "/templates" && method === "POST")) requireRole(dev, "owner");
    if (method === "GET" && sub === "/ws") return chatWsForward(request, env, "admin", "");
    if (method === "GET" && sub === "/threads") return reply(await hub(env, "threads_list", { q: q("q"), status: q("status"), unread: q("unread") === "1", tag: q("tag"), assigned: q("assigned"), archived: q("archived") === "1", limit: q("limit") }));
    if (method === "GET" && sub === "/unread") return reply(await hub(env, "unread_total", {}));
    if (method === "GET" && sub === "/messages") return reply(await hub(env, "messages", { thread: q("thread"), side: "admin", after: q("after"), before: q("before"), limit: q("limit"), origin }));
    if (method === "POST" && sub === "/send") {
      const body = await readJson(request, 12e3);
      return reply(await hub(env, "send", Object.assign({}, body, { side: "admin", origin, dev: devPublic(dev), dev_id: dev && dev.id || "" })));
    }
    if (method === "POST" && sub === "/read") {
      const body = await readJson(request, 1e3);
      return reply(await hub(env, "read", { thread: body.thread, side: "admin", upto: body.upto }));
    }
    if (method === "POST" && sub === "/thread") {
      const tb = await readJson(request, 8e3);
      if (tb && tb.banned !== void 0) requireRole(dev, "owner");
      return reply(await hub(env, "thread_update", tb));
    }
    if (method === "POST" && sub === "/delete-message") return reply(await hub(env, "delete_msg", await readJson(request, 1e3)));
    if (method === "POST" && sub === "/purge") {
      const body = await readJson(request, 1e3);
      return reply(await hub(env, "purge", { thread: body.thread }));
    }
    if (method === "GET" && sub === "/export") return reply(await hub(env, "export", { thread: q("thread"), origin }));
    if (method === "GET" && sub === "/templates") return reply(await hub(env, "templates_get", {}));
    if (method === "POST" && sub === "/templates") return reply(await hub(env, "templates_set", await readJson(request, 4e4)));
    if (method === "POST" && sub === "/convert-order") {
      const body = await readJson(request, 2e3);
      const f = await hub(env, "find_order", { thread: body.thread, seq: body.seq });
      const o = f.order || {};
      let adv = (await core(env, "adv_by_user", { user_key: body.thread, origin })).advertiser;
      if (!adv) adv = (await core(env, "adv_ensure_user", { user_key: body.thread, name: f.thread.name, email: f.thread.email, origin })).advertiser;
      const start = Date.now() + DAY_MS;
      const camp = await core(env, "camp_save", {
        advertiser_id: adv.id,
        name: "\u0637\u0644\u0628: " + (o.slot_label || o.slot || "\u0625\u0639\u0644\u0627\u0646"),
        slot: o.slot,
        start_at: start,
        days: o.days || 7,
        targeting: { cities: o.cities || [], langs: o.langs || [], hours: o.hours || null },
        addons: o.addons || {},
        source_order: "chat:" + body.thread.slice(-8) + ":" + f.seq,
        origin
      });
      await hub(env, "send_system", { thread: body.thread, text: "\u{1F4DD} \u0623\u064F\u0646\u0634\u0626\u062A \u0645\u0633\u0648\u062F\u0629 \u062D\u0645\u0644\u0629 \u0645\u0646 \u0637\u0644\u0628\u0643 \u0648\u0647\u064A \u0642\u064A\u062F \u0627\u0644\u0645\u0631\u0627\u062C\u0639\u0629.", origin });
      return reply({ ok: true, campaign: camp.campaign });
    }
    if (path.indexOf("/admin/chat/upload/") === 0) {
      const thread = q("thread");
      if (!thread) fail(400, "thread_required", "\u062D\u062F\u062F \u0627\u0644\u0645\u062D\u0627\u062F\u062B\u0629 (thread).");
      const r = await chatUploadRoutes(request, env, url, path.slice("/admin/chat/upload".length), method, { side: "admin", thread });
      if (r) return r;
    }
  }
  return null;
}
__name(routeChat, "routeChat");
export {
  AdsCore,
  ChatHub,
  worker_default as default
};
//# sourceMappingURL=worker.js.map