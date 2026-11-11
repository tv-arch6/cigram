import test from "node:test";
import assert from "node:assert/strict";
import { isAdmin, imageTypeFor, timingSafeEqual, internalError } from "./guards.mjs";

const req = (t) => new Request("https://x/channels/add", { method: "POST", headers: t === undefined ? {} : { "X-Admin-Token": t } });

test("admin token: right one passes, wrong / empty / prefix / longer ones fail", () => {
  const env = { ADMIN_TOKEN: "s3cret-token" };
  assert.equal(isAdmin(req("s3cret-token"), env), true);
  assert.equal(isAdmin(req("s3cret-toke"), env), false);
  assert.equal(isAdmin(req("s3cret-token!"), env), false);
  assert.equal(isAdmin(req(""), env), false);
  assert.equal(isAdmin(req(), env), false);
  assert.equal(isAdmin(req("x"), {}), false, "no ADMIN_TOKEN configured = nobody is admin");
  assert.equal(timingSafeEqual("a", "a"), true);
});

test("logo type: only real image types are stored, anything else is refused", () => {
  assert.deepEqual(imageTypeFor("image/png"), { mime: "image/png", ext: "png" });
  assert.deepEqual(imageTypeFor("IMAGE/WEBP"), { mime: "image/webp", ext: "webp" });
  assert.deepEqual(imageTypeFor(undefined), { mime: "image/jpeg", ext: "jpg" });
  assert.equal(imageTypeFor("text/html"), null);
  assert.equal(imageTypeFor("image/svg+xml"), null);
  assert.equal(imageTypeFor("application/javascript"), null);
});

test("500 never carries the internal message", () => {
  assert.deepEqual(internalError(), { ok: false, error: "internal_error" });
});
