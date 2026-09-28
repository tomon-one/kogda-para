// Запросы к службе — те же, что у приложения (docs/api.md). Адреса
// абсолютные: tested живёт в /tested/, а служба — на корне.
//
// ETag и 304 браузер обрабатывает сам: `cache: 'no-cache'` велит ему каждый
// раз сверяться с сервером, а не отдавать сохранённое молча.

var TIMEOUT_MS = 30000;

export function HttpError(status) {
  this.name = 'HttpError';
  this.status = status;
  this.message = 'сервер ответил ' + status;
}
HttpError.prototype = Object.create(Error.prototype);
HttpError.prototype.constructor = HttpError;

export function NetError(why, timeout) {
  this.name = 'NetError';
  this.message = why || 'нет связи с сервером';
  this.timeout = !!timeout;
}
NetError.prototype = Object.create(Error.prototype);
NetError.prototype.constructor = NetError;

function getJson(path) {
  var controller = typeof AbortController === 'function' ? new AbortController() : null;
  var timer = null;
  var timeout = new Promise(function (resolve, reject) {
    timer = setTimeout(function () {
      if (controller) controller.abort();
      reject(new NetError('сервер не ответил за 30 секунд', true));
    }, TIMEOUT_MS);
  });
  var init = { cache: 'no-cache', credentials: 'omit' };
  if (controller) init.signal = controller.signal;
  var request = fetch(path, init).then(function (response) {
    if (!response.ok) throw new HttpError(response.status);
    return response.json();
  }, function () {
    throw new NetError();
  });
  return Promise.race([request, timeout]).then(function (body) {
    clearTimeout(timer);
    return body;
  }, function (error) {
    clearTimeout(timer);
    throw error;
  });
}

function query(from, days) {
  return '?from=' + encodeURIComponent(from) + '&days=' + days;
}

export function meta() {
  return getJson('/v1/meta');
}

export function groups() {
  return getJson('/v1/groups').then(function (body) { return body.groups || []; });
}

export function teachers() {
  return getJson('/v1/teachers').then(function (body) { return body.teachers || []; });
}

export function schedule(id, from, days) {
  return getJson('/v1/schedule/' + encodeURIComponent(id) + query(from, days));
}

export function teacher(id, from, days) {
  return getJson('/v1/teacher/' + encodeURIComponent(id) + query(from, days));
}

/** Короткий ответ `days=1` — чтобы узнать, под каким id сервер знает старый. */
export function scheduleOne(id) {
  return getJson('/v1/schedule/' + encodeURIComponent(id) + '?days=1');
}

export function teacherOne(id) {
  return getJson('/v1/teacher/' + encodeURIComponent(id) + '?days=1');
}
