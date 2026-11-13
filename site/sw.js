// يخزن هيكل الموقع ليفتح بسرعة وبدون إنترنت. لا يخزن الفيديو.
// كل الملفات تُطلب من الشبكة أولاً، فأي تحديث تنشره للموقع يصل للمستخدم مباشرة،
// والنسخة المحفوظة تُستخدم فقط عند انقطاع الإنترنت.
const VERSION = 'v15';
const SHELL = `shell-${VERSION}`;
const DATA = 'data-v1';
const PLUS_HOST = 'cigram-plus-api.wwq-mixtv.workers.dev';
const API_HOST = 'cigram-admin-api.wwq-mixtv.workers.dev';
const CHANNELS_HOST = 'sigram-channels-api.wwq-mixtv.workers.dev';
const FILES = [
  './', 'index.html', 'css/app.css', 'css/v2.css', 'css/plus.css',
  'js/icons.js', 'js/layout.js', 'js/theme.js', 'js/nav.js', 'js/home-view.js',
  'js/app.js', 'js/config.js', 'js/util.js', 'js/store.js', 'js/catalog.js', 'js/cigram.js', 'js/views.js', 'js/player.js',
  'js/subtitles.js', 'js/subtitle-core.js', 'js/subtitle-view.js', 'js/auth.js', 'js/auth-view.js', 'js/terms.js', 'js/landing.js',
  'js/plus.js', 'js/plus-view.js', 'js/stream.js', 'js/external-link.js', 'js/player-extras.js', 'js/channel-store.js', 'js/channel-ui.js', 'js/channels-view.js', 'js/live-player.js',
  'js/pages.js', 'js/requests.js', 'js/support.js', 'js/ads.js', 'js/newbadge.js',
  'icons/verified.png', 'icons/channels/bein-sports-3.png', 'icons/channels/alsharqiya.png',
  'fonts/mohanad.woff',
  'manifest.webmanifest', 'icons/logo-96.png', 'icons/favicon-64.png', 'icons/icon-192.png', 'icons/app-icon.png',
];

// الصفحة تطلب التحديث فوراً عند وجود نسخة جديدة (js/app.js)
self.addEventListener('message', (e) => { if (e.data === 'skipWaiting') self.skipWaiting(); });

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(SHELL)
      .then((c) => c.addAll(FILES.map((f) => new Request(f, { cache: 'reload' }))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      // آخر كتالوج محفوظ (js/catalog.js) يبقى: هو الذي يجعل الرئيسية تظهر فوراً
      .then((keys) => Promise.all(keys.filter((k) => k !== SHELL && k !== DATA && !k.startsWith('cigram-catalog-snap')).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

function networkFirst(req, cacheName) {
  return fetch(req).then((res) => {
    if (res.ok) { const copy = res.clone(); caches.open(cacheName).then((c) => c.put(req, copy)); }
    return res;
  }).catch(() => caches.match(req).then((hit) => hit || Response.error()));
}

self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET' || req.headers.has('range')) return;
  const url = new URL(req.url);

  // محتوى سيكرام (نفس خادم التطبيق): الشبكة أولاً، ثم آخر نسخة محفوظة
  if (url.hostname === API_HOST && (url.pathname === '/movies' || url.pathname === '/home-sections' || url.pathname === '/carousel' || url.pathname === '/home-layout')) {
    e.respondWith(networkFirst(req, DATA));
    return;
  }
  // قائمة القنوات (نفس خادم صفحة القنوات في التطبيق)
  if (url.hostname === CHANNELS_HOST && url.pathname === '/channels') {
    e.respondWith(networkFirst(req, DATA));
    return;
  }
  // رابط التطبيق الخارجي (صفحة الحساب): الشبكة أولاً
  if (url.hostname === PLUS_HOST && url.pathname === '/app/external-link') {
    e.respondWith(networkFirst(req, DATA));
    return;
  }
  // بروكسي البث: لا يُخزَّن أبداً (بث مباشر ومقاطع)، ولا يمر عبر الـ Service Worker
  if (url.hostname.startsWith('cigram-stream.')) return;
  if (url.origin !== location.origin) return; // الصور والفيديو من مصادرها مباشرة

  e.respondWith(networkFirst(req, SHELL));
});
