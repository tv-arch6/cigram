// =============================================================================
// CHANNEL REPORTS (v11) — «بلّغ المدير»: a channel that does not play
// =============================================================================
//   POST /channel-reports                 public. { key, name, url, section?, client_id?, app_version?, user_name? }
//                                         One record per channel (reports of the same channel are counted, not piled up).
//   GET  /admin/channel-reports           admin. The open reports, most reported first.
//   POST /admin/channel-reports/resolve   admin. { key } after the link was replaced («استبدل برابط» → حفظ).
// The viewer's phone needs nothing back: it sees the new link in the channels catalogue and says «تم تفعيل القناة».
// Storage: one small JSON object in the admin bucket (CH_REPORTS_NAME), capped at CH_REPORTS_MAX channels.

const CH_REPORTS_NAME = "channel_reports.json";
const CH_REPORTS_MAX = 200;

function chReportKey(v) {
  return cleanString(v).toLowerCase().slice(0, 120);
}

async function handleChannelReportPublic(request, env) {
  const input = await readRequestData(request);
  const key = chReportKey(input.key || input.content_id || input.name);
  const name = reqClean(input.name || input.title || input.channel_name, 120);
  const url = cleanString(input.url || input.channel_url).slice(0, 600);
  if (!key || !name) throw new AdminError("bad_channel", "اسم القناة ناقص.", 400);
  const now = Date.now();
  const client = cleanString(input.client_id).slice(0, 64);
  let isNew = false;
  await mutateJson(adminBucket(env), adminKey(env, CH_REPORTS_NAME), { version: 1, items: {} }, (doc) => {
    if (!doc.items || typeof doc.items !== "object" || Array.isArray(doc.items)) doc.items = {};
    let it = doc.items[key];
    if (!it || it.resolved_at) {
      isNew = true;
      it = { key, name, url, section: reqClean(input.section || input.channel_section, 60), count: 0, first_at: now, clients: [] };
    }
    if (client && !it.clients.includes(client)) {
      if (it.clients.length < 50) it.clients.push(client);
      it.count = (Number(it.count) || 0) + 1;
    } else if (!client) {
      it.count = (Number(it.count) || 0) + 1;
    }
    it.name = name;
    if (url) it.url = url;
    it.last_at = now;
    it.last_user = reqClean(input.user_name, 40);
    it.app_version = reqClean(input.app_version, 24);
    doc.items[key] = it;
    const keys = Object.keys(doc.items);
    if (keys.length > CH_REPORTS_MAX) {
      keys.sort((a, b) => (Number(doc.items[a].last_at) || 0) - (Number(doc.items[b].last_at) || 0));
      for (const k of keys.slice(0, keys.length - CH_REPORTS_MAX)) delete doc.items[k];
    }
    return {};
  });
  if (isNew) {
    try {
      await reqTgSend(env, "📡 القناة <code>" + reqEsc(name) + "</code> متوقفة وتحتاج استبدال رابط.");
    } catch {
      /* Telegram is optional */
    }
  }
  return okResponse({ received: true }, "وصل بلاغك للمدير.");
}

async function handleChannelReportsList(request, env) {
  const cur = await r2ReadJson(adminBucket(env), adminKey(env, CH_REPORTS_NAME), { version: 1, items: {} }, false);
  const items = Object.values((cur.doc && cur.doc.items) || {})
    .filter((it) => it && !it.resolved_at)
    .sort((a, b) => (Number(b.count) || 0) - (Number(a.count) || 0) || (Number(b.last_at) || 0) - (Number(a.last_at) || 0))
    .map((it) => ({
      key: it.key,
      name: it.name,
      url: it.url || "",
      section: it.section || "",
      count: Number(it.count) || 1,
      last_user: it.last_user || "",
      ago: reqAgo(it.last_at, Date.now()),
    }));
  return okResponse({ items }, items.length ? "" : "ماكو قنوات مبلَّغ عنها.");
}

async function handleChannelReportResolve(request, env) {
  const input = await readRequestData(request);
  const key = chReportKey(input.key);
  if (!key) throw new AdminError("bad_channel", "القناة غير محددة.", 400);
  await mutateJson(adminBucket(env), adminKey(env, CH_REPORTS_NAME), { version: 1, items: {} }, (doc) => {
    if (doc.items && doc.items[key]) {
      doc.items[key].resolved_at = Date.now();
      doc.items[key].new_url = cleanString(input.url).slice(0, 600);
    }
    return {};
  });
  return okResponse({ key, resolved: true }, "تم تفعيل القناة ✅");
}

const CHANNEL_REPORT_ROUTES = (() => {
  const A = adminRoute;
  const pub = (handler) => async (request, env, url) => {
    try {
      return await handler(request, env, url);
    } catch (error) {
      return adminErrorResponse(error);
    }
  };
  return {
    "POST /channel-reports": pub(handleChannelReportPublic),
    "GET /admin/channel-reports": A(handleChannelReportsList),
    "POST /admin/channel-reports/resolve": A(handleChannelReportResolve),
  };
})();
