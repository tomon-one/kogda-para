// Сервис-воркер: страница открывается и без сети — с тем, что браузер
// сохранил. Расписание он не трогает: его хранит сама страница, а запросы к
// службе идут мимо, всегда в сеть.
//
// Номер сборки вписывает скрипт выкладки: сменился любой файл —
// сменился этот, и браузер ставит новый воркер с новым кэшем.

var BUILD = '__BUILD__';
// Хранилище кэшей у сайта одно на весь домен, а воркеров два — основной и
// tested. Имя с адресом, чтобы один не стирал кэш другого.
var PREFIX = 'kogda-para ' + self.registration.scope + ' ';
var CACHE = PREFIX + BUILD;

// Все файлы сайта. Тест web/tests/files.test.mjs сверяет список с каталогом.
var FILES = [
  './',
  '404.html',
  'manifest.json',
  'assets/app.css',
  'assets/boot.js',
  'assets/icon.svg',
  'assets/icon-192.png',
  'assets/icon-512.png',
  'assets/apple-touch-icon.png',
  'assets/js/api.js',
  'assets/js/format.js',
  'assets/js/main.js',
  'assets/js/repo.js',
  'assets/js/schedule.js',
  'assets/js/search.js',
  'assets/js/store.js',
  'assets/js/time.js',
  'assets/js/version.js',
  'assets/js/ui/days.js',
  'assets/js/ui/dom.js',
  'assets/js/ui/lists.js',
  'assets/js/ui/report.js',
  'assets/js/ui/settings.js',
  'assets/js/ui/today.js',
  'assets/js/ui/welcome.js',
];

var URLS = FILES.map(function (f) { return new URL(f, self.registration.scope).href; });

self.addEventListener('install', function (event) {
  event.waitUntil(caches.open(CACHE).then(function (cache) {
    // Мимо HTTP-кэша: иначе в новый кэш легли бы прежние файлы.
    return Promise.all(URLS.map(function (url) {
      return fetch(new Request(url, { cache: 'reload' })).then(function (response) {
        if (!response.ok) throw new Error(url + ': ' + response.status);
        return cache.put(url, response);
      });
    }));
  }).then(function () { return self.skipWaiting(); }));
});

self.addEventListener('activate', function (event) {
  event.waitUntil(caches.keys().then(function (keys) {
    return Promise.all(keys.filter(function (k) {
      return k.indexOf(PREFIX) === 0 && k !== CACHE;
    }).map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.clients.claim(); }));
});

self.addEventListener('fetch', function (event) {
  var request = event.request;
  if (request.method !== 'GET') return;
  var url = new URL(request.url);
  // Страница с «#…» и без — один файл.
  var key = request.mode === 'navigate' && url.href.split('#')[0].split('?')[0] === URLS[0]
    ? URLS[0] : url.origin + url.pathname;
  // Только свои файлы. /v1/, /download/ и сайт tested под основным воркером
  // идут в сеть как есть.
  if (URLS.indexOf(key) < 0) return;
  event.respondWith(caches.open(CACHE).then(function (cache) {
    return cache.match(key).then(function (hit) {
      return hit || fetch(request);
    });
  }));
});
