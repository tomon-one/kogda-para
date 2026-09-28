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

// Все файлы сайта, кроме картинки превью ссылки (assets/og.jpg): её
// мессенджеры берут с GitHub. Тест web/tests/files.test.mjs сверяет список с
// каталогом.
var FILES = [
  './',
  '404.html',
  'manifest.json',
  'manifest-tested.json',
  'assets/app.css',
  'assets/boot.js',
  'assets/icon.svg',
  'assets/icon-192.png',
  'assets/icon-512.png',
  'assets/apple-touch-icon.png',
  'assets/js/api.js',
  'assets/js/format.js',
  'assets/js/main.js',
  'assets/js/push.js',
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
    // Сверяясь с сервером (ETag), а не мимо HTTP-кэша: неизменившиеся файлы
    // приходят ответом 304 без тела, а не второй раз целиком (аудит, W2).
    return Promise.all(URLS.map(function (url) {
      return fetch(new Request(url, { cache: 'no-cache' })).then(function (response) {
        if (!response.ok) throw new Error(url + ': ' + response.status);
        if (url !== URLS[0]) return cache.put(url, response);
        // Страница — той же сборки, что воркер: попав в выкладку посреди
        // копирования, он не должен запомнить смесь (аудит сайта, W8). Не та —
        // установка не удалась, браузер повторит позже.
        return response.clone().text().then(function (html) {
          if (html.indexOf('content="' + BUILD + '"') < 0) throw new Error('страница другой сборки');
          return cache.put(url, response);
        });
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

// ——— уведомления (Web Push) ———
// Что прислать, решает служба (server/src/whensclass/push): отмены и замены
// своей группы и напоминания о паре. Здесь — только показать. Айфон требует
// показывать каждое: пришедшее и не показанное он считает нарушением и
// может отписать сайт.

var ZONE = 'Asia/Novosibirsk';
// Больше строк шторка не покажет развёрнутой (как в приложении).
var MAX_LINES = 8;

function collegeToday() {
  try {
    return new Intl.DateTimeFormat('en-CA', { timeZone: ZONE }).format(new Date());
  } catch (e) {
    var d = new Date();
    return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
  }
}

var ICON = new URL('assets/icon-192.png', self.registration.scope).href;

function showChanges(data) {
  // Непрочитанное прежнее не затирать: новые строки — к старым, прошедшие
  // дни — прочь (announceChanges в приложении).
  return self.registration.getNotifications({ tag: 'changes' }).then(function (open) {
    var lines = [];
    open.forEach(function (n) { ((n.data && n.data.lines) || []).forEach(function (l) { lines.push(l); }); });
    (data.lines || []).forEach(function (l) { lines.push(l); });
    var today = collegeToday();
    var seen = {};
    var kept = lines.filter(function (l) {
      if (!l || l[0] < today || seen[l[1]]) return false;
      seen[l[1]] = true;
      return true;
    }).slice(-MAX_LINES);
    if (!kept.length) kept = (data.lines || []).slice(-MAX_LINES);
    return self.registration.showNotification(data.title || 'Расписание изменилось', {
      body: kept.map(function (l) { return l[1]; }).join('\n'),
      tag: 'changes',
      renotify: true,
      icon: ICON,
      data: { url: self.registration.scope, lines: kept },
    });
  }, function () {
    return self.registration.showNotification(data.title || 'Расписание изменилось', {
      body: (data.lines || []).map(function (l) { return l[1]; }).join('\n'),
      tag: 'changes',
      icon: ICON,
      data: { url: self.registration.scope, lines: data.lines || [] },
    });
  });
}

function showLesson(data) {
  // Что пара уже идёт — по часам в момент показа: доставку могли задержать
  // (LessonAlarms.title в приложении).
  var title = data.start && Date.now() > data.start ? 'Пара уже идёт — ' + data.subject : data.title;
  return self.registration.showNotification(title || 'Скоро пара', {
    body: data.body || '',
    tag: 'lesson',
    renotify: true,
    icon: ICON,
    data: { url: self.registration.scope },
  });
}

self.addEventListener('push', function (event) {
  var data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch (e) {
    data = {};
  }
  var shown = data.t === 'changes' ? showChanges(data)
    : data.t === 'lesson' ? showLesson(data)
      : self.registration.showNotification(data.title || 'Когда пара?', { body: data.body || '', icon: ICON,
        data: { url: self.registration.scope } });
  event.waitUntil(shown);
});

self.addEventListener('notificationclick', function (event) {
  event.notification.close();
  var url = (event.notification.data && event.notification.data.url) || self.registration.scope;
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (list) {
    for (var i = 0; i < list.length; i++) {
      if (list[i].url.indexOf(self.registration.scope) === 0 && 'focus' in list[i]) return list[i].focus();
    }
    return self.clients.openWindow ? self.clients.openWindow(url) : null;
  }));
});
