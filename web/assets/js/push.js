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

/** Встроенный просмотрщик приложения на Android (Telegram и др.): WebView, Push API там нет. */
function webView() {
  return typeof navigator !== 'undefined' && /; wv\)/.test(navigator.userAgent || '');
}

/**
 * Почему уведомлений тут нет: 'home' — айфон во вкладке Safari (уведомления
 * только у значка на «Домой»), 'webview' — встроенный просмотрщик
 * приложения, 'unsupported' — браузер не умеет, 'denied' — запрещены в
 * настройках браузера; null — можно.
 */
export function blocker() {
  if (isIos() && !standalone()) return 'home';
  // Сайт раздаётся ссылкой из Telegram: во встроенном просмотрщике «браузер
  // не умеет» уводило от уведомлений совсем (четвёртый аудит, М57 прогона 1).
  if (!supported() && webView()) return 'webview';
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

/**
 * За сколько напоминать при включении: прежний выбор, а не всегда 20 —
 * выключение стирало минуты (найдено живьём 29.09).
 */
export function lastRemind() {
  var saved = store.get(KEY);
  return (saved && (saved.remind || saved.last)) || 20;
}

/**
 * Включены, но браузер сам снял разрешение (Chrome отзывает его у сайтов, на
 * уведомления которых не нажимают) или его сбросили: подписки больше нет,
 * а выключатели показывали «включено» (М65).
 */
export function revoked() {
  return enabled() && supported() && Notification.permission !== 'granted';
}

/**
 * Служба рассылки этой подписки словами — для «идут через серверы …»: по
 * адресу подписки, а до неё — по браузеру. Раньше выбор был только между
 * Google и Mozilla (М26).
 */
export function service() {
  var saved = store.get(KEY);
  var host = '';
  try { host = saved && saved.endpoint ? new URL(saved.endpoint).hostname : ''; } catch (e) { host = ''; }
  var ua = (typeof navigator !== 'undefined' && navigator.userAgent) || '';
  if (/mozilla\.com$/.test(host) || (!host && /Firefox\//.test(ua))) return 'Mozilla';
  if (/apple\.com$/.test(host) || (!host && /Safari\//.test(ua) && !/Chrome\/|Chromium\/|Edg\//.test(ua))) return 'Apple';
  if (/windows\.com$/.test(host) || (!host && /Edg\//.test(ua))) return 'Microsoft';
  return 'Google';
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
    // 429 — лимит nginx на адрес, который делит вся группа: «подождите», а не
    // «сервер не принял» (М44).
    if (response.status === 429) throw new PushError('busy');
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

// Смены — по одной: быстрые нажатия шли параллельно, и у службы оказывались
// две подписки вместо одной (найдено живьём 29.09).
var queue = Promise.resolve();
function serial(work) {
  var run = queue.then(work, work);
  queue = run.then(function () {}, function () {});
  return run;
}

/**
 * Включить или поменять. `who` — {kind: 'group'|'teacher', id}. Разрешение
 * спрашивается сразу, в том же нажатии: айфон спрашивает только по нажатию.
 */
export function update(next, who) {
  if (!next.changes && !(next.remind > 0)) return disable();
  // Вопрос о разрешении — в этом же нажатии, до очереди: айфон спрашивает
  // только по нажатию.
  var permission = askPermission();
  return serial(function () { return subscribe(next, who, permission); });
}

function subscribe(next, who, permission) {
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
        changes: !!next.changes, remind: next.remind || 0, last: next.remind || lastRemind(),
        kind: who.kind, id: who.id, key: found.key, site: SITE, sent: Date.now(),
        endpoint: json.endpoint,
      });
      return choice();
    });
  });
}

var REMOVE_KEY = 'push-remove';

/** Выключить: отписать браузер и стереть запись у службы. */
export function disable() {
  return serial(function () {
    // Минуты — помнить: при включении снова — прежние, а не 20.
    store.set(KEY, { last: lastRemind() });
    if (!supported()) return choice();
    return navigator.serviceWorker.getRegistration().then(function (registration) {
      return registration ? registration.pushManager.getSubscription() : null;
    }).then(function (sub) {
      if (!sub) return null;
      var endpoint = sub.endpoint;
      // Сначала служба, потом браузер: отписанный браузер служба и так узнает
      // по ответу 410, но не сразу. Не дошло (нет сети, 429) — повторить при
      // следующем открытии: обещано «выключите — запись сотрётся» (М79).
      return request('/v1/push/remove', { endpoint: endpoint }).then(function () {
        store.remove(REMOVE_KEY);
      }, function () {
        store.set(REMOVE_KEY, endpoint);
      }).then(function () { return sub.unsubscribe(); });
    }).then(function () { return choice(); }, function () { return choice(); });
  });
}

/** Отписка, не дошедшая до службы, — ещё раз. */
function retryRemove() {
  var endpoint = store.get(REMOVE_KEY);
  if (!endpoint || enabled()) {
    if (endpoint) store.remove(REMOVE_KEY);
    return Promise.resolve();
  }
  return request('/v1/push/remove', { endpoint: endpoint }).then(function () {
    store.remove(REMOVE_KEY);
  }, function () { /* в другой раз */ });
}

/** Выбор сменился, а служба о нём ещё не знает: пересылка не прошла. */
export function behind(who) {
  var saved = store.get(KEY);
  return !!(saved && enabled() && who && (saved.kind !== who.kind || saved.id !== who.id));
}

/**
 * При открытии и после смены своей группы: служба должна знать нынешнюю
 * подписку и нынешнюю группу. Молча; true — переслано, false — не нужно,
 * 'failed' — не вышло (страница повторит).
 */
export function sync(who) {
  retryRemove();
  var saved = store.get(KEY);
  if (!saved || !enabled() || !who) return Promise.resolve(false);
  if (blocker()) return Promise.resolve(false);
  if (Notification.permission !== 'granted') return Promise.resolve(false);
  // Подписки до 29.09 не знали сайта — переслать сразу, а не через сутки.
  var moved = saved.kind !== who.kind || saved.id !== who.id || saved.site !== SITE;
  var due = moved || Date.now() - (saved.sent || 0) >= RESYNC_MS;
  // Браузер сменил подписку сам, а перенос у службы не вышел (прежней записи
  // уже не было) — адрес не тот, что записан: переслать сразу (М75).
  var changed = navigator.serviceWorker.getRegistration().then(function (registration) {
    return registration ? registration.pushManager.getSubscription() : null;
  }).then(function (sub) {
    return !sub || !saved.endpoint || sub.endpoint !== saved.endpoint;
  }, function () { return false; });
  return changed.then(function (differs) {
    if (!due && !differs) return false;
    return update(choice(), who).then(function () { return true; }, function () { return 'failed'; });
  });
}
