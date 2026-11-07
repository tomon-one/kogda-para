// Что сайт помнит о сервере: его состояние, неудачи связи подряд и 404 своей
// и остальных групп (как ScheduleStore приложения). Наружу — через repo.js.

import * as api from './api.js';
import * as store from './store.js';

/** Неудачи с таким перерывом — не одна цепочка (UNREACHABLE_STREAK_GAP приложения). */
var UNREACHABLE_STREAK_GAP_MS = 3 * 60 * 60 * 1000;

/** 404 подтверждается повтором не раньше чем через час: опечатку колледж чинит быстрее. */
var GONE_CONFIRM_MS = 60 * 60 * 1000;

export var STATUS_UNREACHABLE = 'unreachable';

/** Автор сказал, что колледж правит таблицу (`refresh` в /v1/meta). */
export var STATUS_EDITING = 'editing';

/** {status, src_url, since} — как сервер сказал о себе в последний раз. */
export function serverState() {
  return store.get('server') || { status: 'ok' };
}

export function serverBroken() {
  var s = serverState();
  return !!s.status && s.status !== 'ok';
}

/** Своё уже отвечало 404 при здоровом сервере — подтверждения ещё нет. */
export function goneSuspected() {
  return !!store.get('gone');
}

export function gone() {
  var g = store.get('gone');
  return !!(g && g.confirmed);
}

/**
 * Состояние обновления. Непрочитанные дни одной-двух групп служба отдаёт
 * общим `stale` для прежних версий, а `refresh` — без них: их пометка — у
 * самих дней (`un` в расписании), не плашкой сбоя на всех.
 */
export function health(meta) {
  return meta.refresh || meta.status;
}

export function putServerState(status, srcUrl, since) {
  var old = serverState();
  var next = { status: status || 'ok' };
  // Адрес таблицы — только если пришёл: без него плашке сбоя некуда вести.
  if (srcUrl || old.src_url) next.src_url = srcUrl || old.src_url;
  if (since) next.since = since;
  store.set('server', next);
}

/** Отметить неудачу связи: начало цепочки неудач подряд и их число. */
export function noteUnreachable(now) {
  var u = store.get('unreachable');
  // Вечерняя неудача и утренняя — не две подряд.
  var streak = !!u && now - (u.last || 0) < UNREACHABLE_STREAK_GAP_MS;
  var chain = { since: streak ? u.since : now, last: now, count: streak ? (u.count || 1) + 1 : 1 };
  store.set('unreachable', chain);
  return chain;
}

/**
 * Пришло уведомление «выберите заново»: служба уже ждала свой час, и
 * следующее 404 своего — подтверждённое, без второго часа. Подсказка живёт
 * сутки и снимается первым же ответом своего и новым выбором: вернулась
 * группа — не повод сразу верить будущему 404.
 */
export function expectGone() {
  store.set('goneHint', Date.now());
  // Свой — запросить в этом же заходе, даже если таблица не менялась: иначе
  // подсказка пролежала бы сутки и подтвердила бы любое будущее 404 сразу.
  store.remove('gen');
}

/** Отметить 404; true — подтверждено повтором через час. */
export function noteNotFound(key, now) {
  var g = store.get(key);
  var hint = key === 'gone' ? store.get('goneHint') : null;
  if (hint != null) store.remove('goneHint');
  if (hint != null && now - hint < 24 * 60 * 60 * 1000) {
    store.set(key, { since: g ? g.since : now, confirmed: true });
    return true;
  }
  if (!g) {
    store.set(key, { since: now, confirmed: false });
    return false;
  }
  if (now - g.since >= GONE_CONFIRM_MS) {
    store.set(key, { since: g.since, confirmed: true });
    return true;
  }
  return !!g.confirmed;
}

/**
 * 404 у одной из остальных групп; true — подтверждено повтором через час.
 * Список — из хранилища, мимо роли: extras() у преподавателя пуст, и 404,
 * пришедшее после переключения на «Я преподаватель», стёрло бы все группы.
 * Группы уже нет в списке — не писать.
 */
export function noteExtraNotFound(id, now) {
  var confirmed = false;
  var list = store.get('extras');
  if (!Array.isArray(list) || !list.some(function (g) { return g.id === id; })) return false;
  store.set('extras', list.map(function (g) {
    if (g.id !== id) return g;
    if (g.goneSince == null) return Object.assign({}, g, { goneSince: now });
    if (now - g.goneSince >= GONE_CONFIRM_MS) confirmed = true;
    return g;
  }));
  return confirmed;
}

export function online() {
  return typeof navigator === 'undefined' || navigator.onLine !== false;
}

export function isNotFound(error) {
  return error instanceof api.HttpError && error.status === 404;
}
