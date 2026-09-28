// Уведомления сайта (Web Push): подписка этого браузера у службы. Что и когда
// прислать, считает служба (server/src/whensclass/push) — по тем же правилам,
// что приложение считает у себя: отмены и замены своей группы на сегодня и
// завтра, напоминание о паре за столько-то минут.
//
// Здесь — только подписаться, поменять и отписаться. Выбор хранится в
// браузере (ключ push) и уходит службе вместе с адресом подписки.

import * as store from './store.js';
import { isIos, standalone } from './ui/dom.js';

var KEY = 'push';
var TIMEOUT_MS = 30000;
// Раз в сутки подписка пересылается службе, даже если ничего не менялось:
// браузер мог сменить её сам, служба — потерять.
var RESYNC_MS = 24 * 60 * 60 * 1000;
// Куда вести нажатие на уведомление: корень или /tested/.
var SITE = store.CHANNEL === 'tested' ? 'tested' : 'main';

export var REMIND_CHOICES = [10, 15, 20, 30, 45, 60, 90, 120, 180, 240];

export function PushError(reason) {
  this.name = 'PushError';
  this.reason = reason;
  this.message = reason;
}
PushError.prototype = Object.create(Error.prototype);
PushError.prototype.constructor = PushError;

function supported() {
  return typeof window !== 'undefined' && window.isSecureContext && 'serviceWorker' in navigator &&
    'PushManager' in window && 'Notification' in window;
}

/**
 * Почему уведомлений тут нет: 'home' — айфон во вкладке Safari (уведомления
 * только у значка на «Домой»), 'unsupported' — браузер не умеет, 'denied' —
 * запрещены в настройках браузера; null — можно.
 */
export function blocker() {
  if (isIos() && !standalone()) return 'home';
  if (!supported()) return 'unsupported';
  if (Notification.permission === 'denied') return 'denied';
  return null;
}

/** Что выбрано: {changes, remind} — remind в минутах, 0 — не напоминать. */
export function choice() {
  var saved = store.get(KEY);
  return { changes: !!(saved && saved.changes), remind: (saved && saved.remind) || 0 };
}

export function enabled() {
  var c = choice();
  return c.changes || c.remind > 0;
}

function withTimeout(promise) {
  var timer = null;
  var timeout = new Promise(function (resolve, reject) {
    timer = setTimeout(function () { reject(new PushError('timeout')); }, TIMEOUT_MS);
  });
  return Promise.race([promise, timeout]).then(function (value) {
    clearTimeout(timer);
    return value;
  }, function (error) {
    clearTimeout(timer);
    throw error;
  });
}

function request(path, body) {
  var init = { cache: 'no-cache', credentials: 'omit' };
  if (body) {
    init.method = 'POST';
    init.headers = { 'Content-Type': 'application/json' };
    init.body = JSON.stringify(body);
  }
  return withTimeout(fetch(path, init).then(function (response) {
    if (!response.ok) throw new PushError(response.status === 404 && !body ? 'off' : 'server');
    return response.status === 204 ? null : response.json();
  }, function () {
    throw new PushError('network');
  }));
}

/** Разрешение показывать уведомления. Старый Safari отвечает не обещанием, а вызовом. */
function askPermission() {
  if (Notification.permission === 'granted') return Promise.resolve('granted');
  return new Promise(function (resolve) {
    var result = Notification.requestPermission(resolve);
    if (result && typeof result.then === 'function') result.then(resolve);
  });
}

function worker() {
  return navigator.serviceWorker.getRegistration().then(function (registration) {
    return registration || navigator.serviceWorker.register('sw.js');
  }).then(function () {
    return withTimeout(navigator.serviceWorker.ready);
  });
}

function keyBytes(text) {
  var base64 = (text + '===='.slice(0, (4 - text.length % 4) % 4)).replace(/-/g, '+').replace(/_/g, '/');
  var raw = atob(base64);
  var out = new Uint8Array(raw.length);
  for (var i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

/**
 * Подписка браузера под нынешний ключ службы. Ключ сменился — прежняя
 * подписка бесполезна: служба рассылки её отвергнет.
 */
function subscription(registration) {
  return request('/v1/push/key').then(function (body) {
    var key = body.key;
    var saved = store.get(KEY);
    return registration.pushManager.getSubscription().then(function (current) {
      if (current && saved && saved.key && saved.key !== key) {
        return current.unsubscribe().then(function () { return null; }, function () { return null; });
      }
      return current;
    }).then(function (current) {
      if (current) return { sub: current, key: key };
      return registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(key) })
        .then(function (sub) { return { sub: sub, key: key }; });
    });
  });
}

/**
 * Включить или поменять. `who` — {kind: 'group'|'teacher', id}. Разрешение
 * спрашивается сразу, в том же нажатии: айфон спрашивает только по нажатию.
 */
export function update(next, who) {
  if (!next.changes && !(next.remind > 0)) return disable();
  var permission = askPermission();
  return permission.then(function (answer) {
    if (answer !== 'granted') throw new PushError('denied');
    return worker();
  }).then(subscription).then(function (found) {
    var json = found.sub.toJSON();
    return request('/v1/push/subscribe', {
      endpoint: json.endpoint,
      keys: json.keys,
      kind: who.kind,
      id: who.id,
      changes: !!next.changes,
      remind: next.remind || 0,
      site: SITE,
    }).then(function () {
      store.set(KEY, {
        changes: !!next.changes, remind: next.remind || 0,
        kind: who.kind, id: who.id, key: found.key, site: SITE, sent: Date.now(),
      });
      return choice();
    });
  });
}

/** Выключить: отписать браузер и стереть запись у службы. */
export function disable() {
  store.remove(KEY);
  if (!supported()) return Promise.resolve(choice());
  return navigator.serviceWorker.getRegistration().then(function (registration) {
    return registration ? registration.pushManager.getSubscription() : null;
  }).then(function (sub) {
    if (!sub) return null;
    var endpoint = sub.endpoint;
    // Сначала служба, потом браузер: отписанный браузер служба и так узнает
    // по ответу 410, но не сразу.
    return request('/v1/push/remove', { endpoint: endpoint }).then(null, function () { return null; })
      .then(function () { return sub.unsubscribe(); });
  }).then(function () { return choice(); }, function () { return choice(); });
}

/**
 * При открытии и после смены своей группы: служба должна знать нынешнюю
 * подписку и нынешнюю группу. Молча; не вышло — попробуем в другой раз.
 */
export function sync(who) {
  var saved = store.get(KEY);
  if (!saved || !enabled() || !who) return Promise.resolve(false);
  if (blocker()) return Promise.resolve(false);
  if (Notification.permission !== 'granted') return Promise.resolve(false);
  // Подписки до 29.09 не знали сайта — переслать сразу, а не через сутки.
  var moved = saved.kind !== who.kind || saved.id !== who.id || saved.site !== SITE;
  if (!moved && Date.now() - (saved.sent || 0) < RESYNC_MS) return Promise.resolve(false);
  return update(choice(), who).then(function () { return true; }, function () { return false; });
}
