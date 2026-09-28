// Выбор и обновление расписания. Порядок и правила — как в приложении
// (ScheduleRepository.kt, ScheduleStore.kt): сначала /v1/meta, расписание — если
// сервер разобрал таблицу заново, началась новая неделя или сегодняшнего дня в
// сохранённом нет.

import * as api from './api.js';
import * as store from './store.js';
import { collegeNow, weekStart } from './time.js';
import { DAYS, cleanSchedule, coversDay, mergeSecondGroup, ownOnly, windowMark } from './schedule.js';

/** Сколько сервер может молчать при живой сети, прежде чем это сбой, а не чих. */
var UNREACHABLE_BROKEN_AFTER_MS = 30 * 60 * 1000;
/** Неудачи дальше друг от друга, чем это, — не одна цепочка. */
var UNREACHABLE_STREAK_GAP_MS = 3 * 60 * 60 * 1000;
/** 404 подтверждается повтором не раньше чем через час: опечатку колледж чинит быстрее. */
var GONE_CONFIRM_MS = 60 * 60 * 1000;

export var STATUS_UNREACHABLE = 'unreachable';

// ——— выбор ———

export function isTeacher() {
  return store.get('role') === 'teacher';
}

/** Выбранная группа или сам преподаватель: {id, name} или null. */
export function chosen() {
  return isTeacher() ? store.get('teacher') : store.get('group');
}

export function second() {
  return isTeacher() ? null : store.get('second');
}

function subject() {
  var own = chosen();
  var sub = second();
  return (isTeacher() ? 't:' : 's:') + (own ? own.id : '') + '|' + (sub ? sub.id : '');
}

function dropSchedule() {
  store.remove('schedule');
  store.remove('gen');
  store.remove('fetchedAt');
}

function clearGone() {
  store.remove('gone');
}

/** Студент выбрал группу — это и значит, что он студент. */
export function selectGroup(group) {
  var current = store.get('group');
  var unchanged = !isTeacher() && current && current.id === group.id;
  var sameGroup = current && current.id === group.id;
  var gone = store.get('gone');
  var wasGone = !isTeacher() && gone && gone.confirmed;
  store.set('role', 'student');
  store.set('group', { id: group.id, name: group.name });
  clearGone();
  if (unchanged) return;
  // Расписание прежней группы нельзя показывать ни секунды.
  dropSchedule();
  // Соседняя подгруппа была парой к прежней группе. Но перевыбор после
  // «группы больше нет» — обычно та же группа под новым именем.
  if (!sameGroup && !wasGone) {
    store.remove('second');
    store.remove('secondGone');
  }
}

export function selectSelf(teacher) {
  var current = store.get('teacher');
  var unchanged = isTeacher() && current && current.id === teacher.id;
  store.set('role', 'teacher');
  store.set('teacher', { id: teacher.id, name: teacher.name });
  clearGone();
  if (unchanged) return;
  dropSchedule();
}

/**
 * Соседняя подгруппа выбрана или снята (`group` = null). Свои пары остаются —
 * без пар прежней соседки, с пометкой «неполное», — и без связи экран не пустеет.
 */
export function selectSecond(group) {
  var saved = store.get('schedule');
  if (group) store.set('second', { id: group.id, name: group.name });
  else store.remove('second');
  store.remove('secondGone');
  if (saved && !isTeacher()) {
    store.set('schedule', ownOnly(saved));
    store.set('partial', true);
  } else {
    dropSchedule();
  }
}

// ——— состояние ———

/** Забыть сохранённое расписание — после того как на нём упала отрисовка. */
export function forgetSchedule() {
  dropSchedule();
}

/** Сохранённое расписание, приведённое к форме; испорченное — забыть. */
export function saved() {
  var body = store.get('schedule');
  if (!body) return null;
  try {
    return cleanSchedule(body);
  } catch (e) {
    dropSchedule();
    return null;
  }
}

export function fetchedAt() {
  return store.get('fetchedAt') || 0;
}

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

export function secondGone() {
  var g = store.get('secondGone');
  return !!(g && g.confirmed);
}

function putServerState(status, srcUrl, since) {
  var old = serverState();
  var next = { status: status || 'ok' };
  // Адрес таблицы — только если пришёл: без него плашке сбоя некуда вести.
  if (srcUrl || old.src_url) next.src_url = srcUrl || old.src_url;
  if (since) next.since = since;
  store.set('server', next);
}

/** Отметить неудачу связи: начало цепочки неудач подряд и их число. */
function noteUnreachable(now) {
  var u = store.get('unreachable');
  var streak = u && now - u.last < UNREACHABLE_STREAK_GAP_MS;
  var chain = { since: streak ? u.since : now, last: now, count: streak ? (u.count || 1) + 1 : 1 };
  store.set('unreachable', chain);
  return chain;
}

/** Отметить 404; true — подтверждено повтором через час. */
function noteNotFound(key, now) {
  var g = store.get(key);
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

function online() {
  return typeof navigator === 'undefined' || navigator.onLine !== false;
}

function isNotFound(error) {
  return error instanceof api.HttpError && error.status === 404;
}

// ——— обновление ———

/**
 * Пары соседней подгруппы — запрос уже идёт, рядом со своим. Не достучались —
 * своё расписание как есть: без пары соседей человек обойдётся, без своих — нет.
 */
function withSecondGroup(mine, serverOk, sub, request) {
  if (!sub || !request) return Promise.resolve({ schedule: mine, whole: true });
  return request.then(function (extra) {
    store.remove('secondGone');
    var renamed = extra.g !== sub.id ? { id: extra.g, name: extra.gn } : null;
    return { schedule: mergeSecondGroup(mine, extra), whole: true, secondRenamed: renamed };
  }, function (error) {
    // 404 при здоровом сервере, повторённое через час, — соседки больше нет.
    // Выбор не стираем: вернётся она — вернутся и её пары.
    if (isNotFound(error) && serverOk && noteNotFound('secondGone', Date.now())) {
      return { schedule: mine, whole: true, secondGone: true };
    }
    return { schedule: mine, whole: false };
  });
}

function refreshOnce(force) {
  var asked = subject();
  var teacherMode = isTeacher();
  var own = chosen();
  var sub = second();
  if (!own) return Promise.resolve({ kind: 'nogroup' });
  var today = collegeNow().date;
  // Неделя — одна на весь заход: запрос через полночь воскресенья не должен
  // записать окно прошлой недели с меткой новой.
  var from = weekStart(today);
  var outdated = !coversDay(saved(), today) ||
    store.get('window') !== windowMark(from) ||
    !!store.get('partial');
  var meta = null;

  return api.meta().then(function (m) { meta = m; }, function () { meta = null; })
    .then(function () {
      var now = Date.now();
      if (meta) {
        store.remove('unreachable');
        store.set('lastOk', now);
        putServerState(meta.status, meta.src_url, meta.since);
      } else if (online()) {
        // Сайт открывают не каждый час: от цепочки неудач подряд сбой при
        // редких заходах не виден вовсе. Мерило — последний ответ сервера:
        // молчит дольше получаса — не отвечает, и давность — от него
        // (аудит сайта, прогон 2). Ответа не было никогда — цепочка неудач.
        // Одна неудача — ещё не сбой: у человека могла пропасть своя сеть
        // (Wi-Fi без интернета navigator.onLine не видит). Нужны две подряд —
        // страница повторяет неудачное через минуту (аудит, прогон 3).
        var lastOk = store.get('lastOk');
        var chain = noteUnreachable(now);
        var since = lastOk || chain.since;
        if (chain.count >= 2 && now - since >= UNREACHABLE_BROKEN_AFTER_MS) {
          putServerState(STATUS_UNREACHABLE, null, new Date(since).toISOString());
        }
      }
      if (!force && !outdated && meta && meta.gen === store.get('gen')) {
        // Данные те же, но проверку показать надо: иначе кажется, что кнопка
        // обновления не работает.
        store.set('fetchedAt', now);
        return { kind: 'fresh' };
      }
      // Своё и подгруппа — разом, а не по очереди (аудит сайта, W2). На экран
      // своё попадает вместе с соседкой: её молчание ограничено тайм-аутом
      // запроса, 30 секунд.
      var request = (teacherMode ? api.teacher(own.id, from, DAYS) : api.schedule(own.id, from, DAYS))
        .then(cleanSchedule);
      var subRequest = !teacherMode && sub ? api.schedule(sub.id, from, DAYS).then(cleanSchedule) : null;
      if (subRequest) subRequest.then(null, function () { /* разберёт withSecondGroup */ });
      return request.then(null, function (error) {
        if (isNotFound(error) && meta && meta.status === 'ok' && noteNotFound('gone', Date.now())) {
          return 'gone';
        }
        throw error;
      }).then(function (fresh) {
        if (fresh === 'gone') return { kind: 'gone' };
        clearGone();
        store.remove('unreachable');
        store.set('lastOk', Date.now());
        // Расписание пришло — сервер отвечает, даже если meta сорвался.
        if (serverState().status === STATUS_UNREACHABLE) putServerState('ok', null, null);
        var merging = teacherMode
          ? Promise.resolve({ schedule: fresh, whole: true })
          : withSecondGroup(fresh, !!meta && meta.status === 'ok', sub, subRequest);
        return merging.then(function (merged) {
          // Пока шёл запрос, человек мог сменить выбор: ответ уже чужой.
          if (subject() !== asked) return { kind: 'fresh' };
          // Ответ под другим id — группу или преподавателя переименовали.
          if (fresh.g !== own.id) {
            store.set(teacherMode ? 'teacher' : 'group', { id: fresh.g, name: fresh.gn });
          }
          if (merged.secondRenamed) store.set('second', merged.secondRenamed);
          store.set('schedule', merged.schedule);
          store.set('gen', fresh.gen);
          store.set('fetchedAt', Date.now());
          store.set('partial', !merged.whole);
          store.set('window', windowMark(from));
          return { kind: 'updated' };
        });
      });
    })
    .then(null, function (error) {
      return { kind: 'failed', error: error };
    });
}

var running = null;
var runningForced = false;

/**
 * Обновления — по одному: параллельные затирали бы друг друга. Обычное при
 * идущем — ждёт его; принудительное — встаёт следом.
 */
export function refresh(force) {
  if (running && (!force || runningForced)) return running;
  var start = function () {
    runningForced = !!force;
    var p = refreshOnce(!!force);
    running = p;
    return p.then(function (result) {
      if (running === p) running = null;
      return result;
    });
  };
  return running ? running.then(start, start) : start();
}

export function isRefreshing() {
  return running !== null;
}

// ——— списки ———

export function cachedGroups() {
  return store.get('groups') || [];
}

export function cachedTeachers() {
  return store.get('teachers') || [];
}

/** Свежий список: {list} или {busy} — сервер занят (429), или {} — не ответил. */
function freshList(key, request) {
  return request().then(function (list) {
    var clean = (Array.isArray(list) ? list : []).filter(function (x) {
      return x && typeof x.id === 'string' && typeof x.name === 'string';
    });
    store.set(key, clean);
    return { list: clean };
  }, function (error) {
    return error instanceof api.HttpError && error.status === 429 ? { busy: true } : {};
  });
}

export function freshGroups() {
  return freshList('groups', api.groups);
}

export function freshTeachers() {
  return freshList('teachers', api.teachers);
}

/**
 * Закреплённые — за переименованием: id, которого нет в свежем списке,
 * спрашиваем у сервера, и он по памяти о старом имени отвечает под новым.
 */
export function followRenamedPins(groups, teachers) {
  function follow(key, list, ask) {
    var ids = {};
    list.forEach(function (x) { ids[x.id] = true; });
    var pins = store.get(key) || [];
    return pins.filter(function (id) { return !ids[id]; }).reduce(function (chain, old) {
      return chain.then(function () {
        return ask(old).then(function (body) {
          if (body.g !== old && ids[body.g]) {
            // Новый id мог быть закреплён и сам — без повтора, иначе звезда
            // гасла только со второго нажатия (аудит сайта, W6).
            var now = (store.get(key) || []).map(function (id) { return id === old ? body.g : id; });
            store.set(key, now.filter(function (id, i) { return now.indexOf(id) === i; }));
          }
        }, function () { /* не ответил — в другой раз */ });
      });
    }, Promise.resolve());
  }
  return follow('pinnedGroups', groups, api.scheduleOne)
    .then(function () { return follow('pinnedTeachers', teachers, api.teacherOne); });
}

export function pinned(kind) {
  return store.get(kind === 'teachers' ? 'pinnedTeachers' : 'pinnedGroups') || [];
}

export function togglePin(kind, id) {
  var key = kind === 'teachers' ? 'pinnedTeachers' : 'pinnedGroups';
  var list = store.get(key) || [];
  store.set(key, list.indexOf(id) >= 0 ? list.filter(function (x) { return x !== id; }) : list.concat([id]));
}

/**
 * Чужое расписание — по запросу, не хранится: {schedule} или {notFound} —
 * сервер такого не знает (ссылка устарела, опечатка), или {} — не загрузилось.
 */
export function otherSchedule(kind, id) {
  var from = weekStart(collegeNow().date);
  var request = kind === 'teachers' ? api.teacher(id, from, DAYS) : api.schedule(id, from, DAYS);
  return request.then(function (body) {
    return { schedule: cleanSchedule(body) };
  }, function (error) {
    return isNotFound(error) ? { notFound: true } : {};
  });
}
