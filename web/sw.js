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
  'assets/days.css',
  'assets/screens.css',
  'assets/motion.css',
  'assets/boot.js',
  'assets/icon.svg',
  'assets/icon-192.png',
  'assets/icon-512.png',
  'assets/apple-touch-icon.png',
  'assets/qr.png',
  'assets/js/api.js',
  'assets/js/catalog.js',
  'assets/js/data.js',
  'assets/js/format.js',
  'assets/js/main.js',
  'assets/js/push.js',
  'assets/js/render.js',
  'assets/js/repo.js',
  'assets/js/route.js',
  'assets/js/schedule.js',
  'assets/js/search.js',
  'assets/js/site.js',
  'assets/js/state.js',
  'assets/js/store.js',
  'assets/js/time.js',
  'assets/js/version.js',
  'assets/js/ui/days.js',
  'assets/js/ui/dom.js',
  'assets/js/ui/lists.js',
  'assets/js/ui/notifications.js',
  'assets/js/ui/report.js',
  'assets/js/ui/settings.js',
  'assets/js/ui/today.js',
  'assets/js/ui/welcome.js',
];

var URLS = FILES.map(function (f) { return new URL(f, self.registration.scope).href; });

self.addEventListener('install', function (event) {
  event.waitUntil(caches.open(CACHE).then(function (cache) {
    // Сверяясь с сервером (ETag), а не мимо HTTP-кэша: неизменившиеся файлы
    // приходят ответом 304 без тела, а не второй раз целиком.
    return Promise.all(URLS.map(function (url) {
      return fetch(new Request(url, { cache: 'no-cache' })).then(function (response) {
        if (!response.ok) throw new Error(url + ': ' + response.status);
        if (url !== URLS[0]) return cache.put(url, response);
        // Страница — той же сборки, что воркер: попав в выкладку посреди
        // копирования, он не должен запомнить смесь. Не та — установка не
        // удалась, браузер повторит позже.
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
//
// Сообщение — в декларативном формате Apple ({web_push: 8030, notification:
// {title, body, navigate, tag, data}}): айфон с iOS 18.4 покажет его и сам,
// если воркер не проснулся. Здесь оно разбирается так же, как в Chrome и
// Firefox, где декларативного показа нет.

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

/** {t, title, body, …данные} из декларативного сообщения. */
function unpack(raw) {
  var note = raw && raw.notification;
  if (!note) return raw || {};
  var data = note.data || {};
  var out = { title: note.title, body: note.body };
  Object.keys(data).forEach(function (k) { out[k] = data[k]; });
  return out;
}

function show(title, body, tag, extra) {
  var data = { url: self.registration.scope };
  Object.keys(extra || {}).forEach(function (k) { data[k] = extra[k]; });
  return self.registration.showNotification(title, { body: body || '', tag: tag, icon: ICON, data: data });
}

function showChanges(data) {
  var days = data.days || [];
  var who = data.who || '';
  var fresh = String(data.body || '').split('\n').map(function (text, i) { return [days[i] || '', text]; });
  // Непрочитанное прежнее не затирать: новые строки — к старым, прошедшие
  // дни — прочь (announceChanges в приложении). Прежнее закрыть: на айфоне
  // тот же tag не заменяет уведомление, а ставит второе рядом (WebKit 258922).
  // Склеиваются строки только того же расписания, иначе после смены группы
  // висящая отмена прежней читалась бы как своя.
  return self.registration.getNotifications({ tag: 'changes' }).then(function (open) {
    var older = [];
    open.forEach(function (n) {
      var same = ((n.data && n.data.who) || '') === who;
      if (same) ((n.data && n.data.lines) || []).forEach(function (l) { older.push(l); });
      n.close();
    });
    var today = collegeToday();
    function live(l) { return l && !(l[0] && l[0] < today); }
    // Повтор строки — на её последнем месте, а не на первом, иначе «вернули →
    // отменили → вернули» кончалось бы строкой «отменили».
    var all = older.concat(fresh).filter(live);
    var kept = all.filter(function (l, i) {
      return !all.slice(i + 1).some(function (m) { return m[1] === l[1]; });
    });
    if (kept.length > MAX_LINES) {
      // Свежая правка важнее висящих строк, а в ней — сначала сегодня: строки
      // идут по дням.
      var mine = fresh.filter(live);
      var newest = kept.filter(function (l) { return mine.some(function (m) { return m[1] === l[1]; }); });
      if (newest.length >= MAX_LINES) {
        kept = newest.slice(0, MAX_LINES);
      } else {
        // Из висящих уходят сначала строки о более далёком дне, в одном дне —
        // более старые: сегодняшнее важнее завтрашнего.
        var rest = kept.filter(function (l) { return newest.indexOf(l) < 0; });
        var order = rest.map(function (l, i) { return i; }).sort(function (a, b) {
          var da = rest[a][0] || '', db = rest[b][0] || '';
          return da < db ? -1 : da > db ? 1 : b - a;
        });
        var keep = order.slice(0, MAX_LINES - newest.length);
        kept = rest.filter(function (l, i) { return keep.indexOf(i) >= 0; }).concat(newest);
      }
    }
    // Все строки про прошедшие дни (телефон вышел в сеть назавтра) — не
    // показывать вчерашнее как новость.
    var body = kept.length ? kept.map(function (l) { return l[1]; }).join('\n')
      : 'Изменения касались прошедших дней.';
    return show(data.title || 'Расписание изменилось', body, 'changes', { lines: kept, who: who });
  }, function () {
    return show(data.title || 'Расписание изменилось', data.body, 'changes', { lines: fresh, who: who });
  });
}

function showLesson(data) {
  // Что пара уже идёт — по часам в момент показа: доставку могли задержать
  // (LessonAlarms.title в приложении).
  var title = data.start && Date.now() > data.start ? 'Пара уже идёт — ' + data.subject : data.title;
  // Прежние напоминания — прочь: на айфоне tag не заменяет, и они копились бы
  // день за днём, а в Chrome второе с тем же tag приходит беззвучно, заменяя
  // висящее. Как и в приложении, напоминание одно.
  return self.registration.getNotifications({ tag: 'lesson' }).then(function (open) {
    open.forEach(function (n) { n.close(); });
  }, function () { /* нечего закрывать */ }).then(function () {
    return show(title || 'Скоро пара', data.body, 'lesson', { end: data.end });
  });
}

/** Закрыть напоминания о закончившихся парах — при любом приходе. */
function closeEnded() {
  return self.registration.getNotifications({ tag: 'lesson' }).then(function (open) {
    open.forEach(function (n) { if (n.data && n.data.end && Date.now() > n.data.end) n.close(); });
  }, function () { /* нечего закрывать */ });
}

self.addEventListener('push', function (event) {
  var data = {};
  try {
    data = unpack(event.data ? event.data.json() : {});
  } catch (e) {
    data = {};
  }
  var shown = data.t === 'changes' ? showChanges(data)
    : data.t === 'lesson' ? showLesson(data)
      : show(data.title || 'Когда пара?', data.body, data.t || 'other');
  event.waitUntil(Promise.all([shown, data.t === 'lesson' ? null : closeEnded()]));
});

// Окно этого сайта: у корня — не окно /tested/, хоть оно и под тем же
// префиксом.
function ours(client) {
  var scope = self.registration.scope;
  if (client.url.indexOf(scope) !== 0) return false;
  var testedScope = /\/tested\/$/.test(scope);
  return testedScope || client.url.indexOf(scope + 'tested/') !== 0;
}

self.addEventListener('notificationclick', function (event) {
  event.notification.close();
  var data = event.notification.data || {};
  // «Выберите заново»: служба уже ждала час — страница не ждёт своего
  // (main.js). Вид сообщения — в теге уведомления (show).
  var gone = event.notification.tag === 'gone';
  var url = (data.url || self.registration.scope) + (gone ? '?gone=1' : '');
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (list) {
    for (var i = 0; i < list.length; i++) {
      if (ours(list[i]) && 'focus' in list[i]) {
        // Уведомление — о своём расписании: страница уходит к нему, а не
        // остаётся на открытом чужом.
        list[i].postMessage({ t: 'own', gone: gone });
        return list[i].focus();
      }
    }
    return self.clients.openWindow ? self.clients.openWindow(url) : null;
  }));
});

// Браузер сменил подписку сам (Chrome на Android): новый адрес — службе, с
// прежним выбором. На айфоне этого события нет — там подписку пересылает
// страница при открытии (push.js, sync).
self.addEventListener('pushsubscriptionchange', function (event) {
  var old = event.oldSubscription;
  if (!old) return;
  var fresh = event.newSubscription ? Promise.resolve(event.newSubscription)
    : self.registration.pushManager.subscribe(old.options);
  // Не перенеслось (прежней записи уже нет — 404) — перешлёт страница.
  event.waitUntil(fresh.then(function (sub) {
    var json = sub.toJSON();
    return fetch('/v1/push/move', {
      method: 'POST',
      credentials: 'omit',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ old: old.endpoint, endpoint: json.endpoint, keys: json.keys }),
    });
  }).then(null, function () { return null; }));
});
