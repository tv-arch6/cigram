// sigram-channels-api — channels Worker.
// Bindings (unchanged): CHANNELS_BUCKET (R2), ADMIN_TOKEN (secret), PUBLIC_R2_BASE_URL (var).
//
// Routes:
//   GET  /channels          public list (unchanged)
//   POST /channels/add      add a channel (unchanged)
//   POST /channels/update   change a channel's stream link        (new, admin)
//   POST /channels/delete   delete a channel and its logo image   (new, admin)
//
// update / delete body: { id, category, name, old_url, url? }
//   The channel is found by `id`; channels saved without an id are found by
//   category + name (+ old_url when given).

const JSON_KEY = "channels.json";

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "Content-Type, X-Admin-Token",
  "access-control-allow-methods": "GET, POST, OPTIONS"
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: {
      "content-type": "application/json; charset=UTF-8",
      "cache-control": "no-store",
      ...CORS
    }
  });
}

function fail(status, error, message) {
  return json({ ok: false, error, message }, status);
}

async function readDatabase(env) {
  const object = await env.CHANNELS_BUCKET.get(JSON_KEY);

  if (!object) {
    return {
      ok: true,
      categories: [
        { name: "الرياضة", channels: [] },
        { name: "الأخبار", channels: [] }
      ]
    };
  }

  try {
    const parsed = JSON.parse(await object.text());
    if (!Array.isArray(parsed.categories)) parsed.categories = [];
    parsed.ok = true;
    return parsed;
  } catch {
    return { ok: true, categories: [] };
  }
}

async function writeDatabase(env, data) {
  await env.CHANNELS_BUCKET.put(JSON_KEY, JSON.stringify(data, null, 2), {
    httpMetadata: { contentType: "application/json; charset=UTF-8" }
  });
}

function normalizeName(value) {
  return String(value || "").trim().replace(/\s+/g, " ");
}

function isStreamUrl(value) {
  return value.startsWith("http://") || value.startsWith("https://");
}

function extensionFor(mime) {
  const m = String(mime || "").toLowerCase();
  if (m.includes("png")) return "png";
  if (m.includes("webp")) return "webp";
  return "jpg";
}

function decodeBase64(base64) {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function isAdmin(request, env) {
  const sentToken = request.headers.get("X-Admin-Token") || "";
  return Boolean(env.ADMIN_TOKEN) && sentToken === env.ADMIN_TOKEN;
}

async function readBody(request) {
  try {
    return await request.json();
  } catch {
    return null;
  }
}

/** Finds a channel by id, or (for channels without an id) by category + name (+ old_url). */
function findChannel(database, body) {
  const id = body.id === undefined || body.id === null ? "" : String(body.id).trim();
  const category = normalizeName(body.category).toLowerCase();
  const name = normalizeName(body.name);
  const oldUrl = String(body.old_url || "").trim();

  for (const categoryObject of database.categories) {
    if (!categoryObject || !Array.isArray(categoryObject.channels)) continue;
    const list = categoryObject.channels;

    for (let index = 0; index < list.length; index++) {
      const channel = list[index];
      if (!channel || typeof channel !== "object") continue;

      if (id) {
        if (String(channel.id ?? "").trim() === id) return { categoryObject, list, index, channel };
        continue;
      }

      const sameCategory = normalizeName(categoryObject.name).toLowerCase() === category;
      const sameName = normalizeName(channel.name) === name;
      const sameUrl = !oldUrl || String(channel.url || channel.stream || "").trim() === oldUrl;
      if (sameCategory && sameName && sameUrl) return { categoryObject, list, index, channel };
    }
  }
  return null;
}

function channelImage(channel) {
  return String(channel.image || channel.logo || channel.image_url || channel.icon || "").trim();
}

// ----------------------------------------------------------------- handlers

async function handleAdd(request, env) {
  if (!isAdmin(request, env)) return json({ ok: false, error: "Unauthorized" }, 401);

  const body = await readBody(request);
  if (!body) return json({ ok: false, error: "Invalid JSON" }, 400);

  const category = normalizeName(body.category);
  const name = normalizeName(body.name);
  const streamUrl = String(body.url || "").trim();
  const imageBase64 = String(body.image_base64 || "").trim();
  const imageMime = String(body.image_mime || "image/jpeg").trim();

  if (!category) return json({ ok: false, error: "Category is required" }, 400);
  if (!name) return json({ ok: false, error: "Channel name is required" }, 400);
  if (!isStreamUrl(streamUrl)) return json({ ok: false, error: "Invalid stream URL" }, 400);
  if (!imageBase64) return json({ ok: false, error: "Channel image is required" }, 400);

  const id = Date.now();
  const imageKey = `channels/${id}.${extensionFor(imageMime)}`;

  let imageBytes;
  try {
    imageBytes = decodeBase64(imageBase64);
  } catch {
    return json({ ok: false, error: "Invalid base64 image" }, 400);
  }
  if (imageBytes.byteLength > 4 * 1024 * 1024) return json({ ok: false, error: "Image is too large" }, 413);

  await env.CHANNELS_BUCKET.put(imageKey, imageBytes, { httpMetadata: { contentType: imageMime } });

  const baseUrl = String(env.PUBLIC_R2_BASE_URL || "").replace(/\/+$/, "");
  if (!baseUrl) return json({ ok: false, error: "PUBLIC_R2_BASE_URL is missing" }, 500);

  const imageUrl = `${baseUrl}/${imageKey}`;
  const database = await readDatabase(env);

  let categoryObject = database.categories.find(
    item => normalizeName(item.name).toLowerCase() === category.toLowerCase()
  );
  if (!categoryObject) {
    categoryObject = { name: category, channels: [] };
    database.categories.unshift(categoryObject);
  }
  if (!Array.isArray(categoryObject.channels)) categoryObject.channels = [];

  const channel = { id, name, image: imageUrl, url: streamUrl, created_at: new Date().toISOString() };
  categoryObject.channels.unshift(channel);
  database.ok = true;
  await writeDatabase(env, database);

  return json({ ok: true, message: "Channel added", category: categoryObject.name, channel }, 201);
}

async function handleUpdate(request, env) {
  if (!isAdmin(request, env)) return fail(401, "Unauthorized", "رمز الإدارة غير مقبول في Worker القنوات.");

  const body = await readBody(request);
  if (!body) return fail(400, "Invalid JSON", "الطلب غير صالح.");

  const streamUrl = String(body.url || "").trim();
  if (!isStreamUrl(streamUrl)) return fail(400, "Invalid stream URL", "رابط البث يجب أن يبدأ بـ http:// أو https://");

  const database = await readDatabase(env);
  const found = findChannel(database, body);
  if (!found) return fail(404, "channel_not_found", "لم يتم العثور على القناة. حدّث القائمة وحاول مجدداً.");

  const { channel, categoryObject } = found;
  channel.url = streamUrl;
  if ("stream" in channel) channel.stream = streamUrl;
  channel.updated_at = new Date().toISOString();

  database.ok = true;
  await writeDatabase(env, database);

  return json({ ok: true, message: "تم تغيير رابط القناة", category: categoryObject.name, channel });
}

async function handleDelete(request, env) {
  if (!isAdmin(request, env)) return fail(401, "Unauthorized", "رمز الإدارة غير مقبول في Worker القنوات.");

  const body = await readBody(request);
  if (!body) return fail(400, "Invalid JSON", "الطلب غير صالح.");

  const database = await readDatabase(env);
  const found = findChannel(database, body);
  if (!found) return fail(404, "channel_not_found", "لم يتم العثور على القناة. ربما حُذفت من قبل.");

  const { list, index, channel, categoryObject } = found;
  list.splice(index, 1);
  database.ok = true;
  await writeDatabase(env, database);

  // Remove the logo from R2 when it lives in this bucket and no other channel uses it.
  let imageDeleted = false;
  const image = channelImage(channel);
  const baseUrl = String(env.PUBLIC_R2_BASE_URL || "").replace(/\/+$/, "");
  if (image && baseUrl && image.startsWith(`${baseUrl}/channels/`)) {
    const stillUsed = database.categories.some(
      c => c && Array.isArray(c.channels) && c.channels.some(ch => ch && channelImage(ch) === image)
    );
    if (!stillUsed) {
      try {
        await env.CHANNELS_BUCKET.delete(image.slice(baseUrl.length + 1));
        imageDeleted = true;
      } catch {
        // the channel is already gone; a leftover image is harmless
      }
    }
  }

  return json({ ok: true, message: "تم حذف القناة", category: categoryObject.name, channel, image_deleted: imageDeleted });
}

// 18.3 — conditional GET for the public list (see apply_patch.py)
async function handleList(request, env) {
  const object = await env.CHANNELS_BUCKET.get(JSON_KEY, { onlyIf: request.headers });
  if (!object) return json(await readDatabase(env));   // nothing saved yet: the same defaults as before
  const etag = object.httpEtag || "";
  const headers = Object.assign(
    { "content-type": "application/json; charset=UTF-8", "cache-control": "no-cache", "access-control-expose-headers": "ETag" },
    etag ? { etag } : {},
    CORS
  );
  if (object.body === undefined) return new Response(null, { status: 304, headers });   // If-None-Match matched: not modified
  let parsed;
  try {
    parsed = JSON.parse(await object.text());
  } catch {
    parsed = { categories: [] };
  }
  if (!Array.isArray(parsed.categories)) parsed.categories = [];
  parsed.ok = true;
  return new Response(JSON.stringify(parsed, null, 2), { status: 200, headers });
}

// ------------------------------------------------------------------- router

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);

      if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });

      if (request.method === "GET" && url.pathname === "/channels") return await handleList(request, env);
      if (request.method === "POST" && url.pathname === "/channels/add") return await handleAdd(request, env);
      if (request.method === "POST" && url.pathname === "/channels/update") return await handleUpdate(request, env);
      if (request.method === "POST" && url.pathname === "/channels/delete") return await handleDelete(request, env);

      return json({ ok: false, error: "Not found" }, 404);
    } catch (error) {
      return json({ ok: false, error: error && error.message ? error.message : String(error) }, 500);
    }
  }
};
