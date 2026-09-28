// Выбор и обновление расписания. Порядок и правила — как в приложении
// (ScheduleRepository.kt, ScheduleStore.kt): сначала /v1/meta, расписание — если
// сервер разобрал таблицу заново, началась новая неделя или сегодняшнего дня в
// сохранённом нет.

import * as api from './api.js';
import * as store from './store.js';
import { collegeNow, weekStart } from './time.js';
import { DAYS, MAX_GROUPS, cleanSchedule, combineGroups, coversDay, ownOnly, windowMark } from './schedule.js';

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

/**
 * Остальные выбранные группы по порядку: [{id, name, gone, goneSince}]. Их
 * пары на экране рядом со своими; своя сюда не входит (ExtraGroup в
 * ScheduleMerge.kt).
 */
export function extras() {
  if (isTeacher()) return [];
  var list = store.get('extras');
  return Array.isArray(list) ? list : [];
}

function setExtras(list) {
  var kept = list.slice(0, MAX_GROUPS - 1);
  if (kept.length) store.set('extras', kept);
  else store.remove('extras');
  // Снимки убранных — прочь: иначе при новом выборе той же группы её
  // прежние пары вернулись бы на экран.
  var schedules = store.get('extraSchedules');
  if (!schedules) return;
  var left = {};
  kept.forEach(function (g) { if (schedules[g.id]) left[g.id] = schedules[g.id]; });
  if (Object.keys(left).length) store.set('extraSchedules', left);
  else store.remove('extraSchedules');
}

function subject() {
  var own = chosen();
  return (isTeacher() ? 't:' : 's:') + (own ? own.id : '') + '|' +
    extras().map(function (g) { return g.id; }).join(',');
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
  store.set('role', 'student');
  store.set('group', { id: group.id, name: group.name });
  clearGone();
  if (unchanged) return;
  // Расписание прежней группы нельзя показывать ни секунды.
  dropSchedule();
  // Остальные группы остаются: выбрать можно любые, к своей они не
  // привязаны. Кроме новой своей — дважды одну не показываем.
  var list = store.get('extras') || [];
  if (list.some(function (g) { return g.id === group.id; })) {
    setExtras(list.filter(function (g) { return g.id !== group.id; }));
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
 * Добавить группы к остальным — в конец, без своей и без повторов, пока
 * влезает MAX_GROUPS. Их пары придут с обновлением.
 */
export function addExtras(groups) {
  var list = extras();
  var own = chosen();
  (groups || []).forEach(function (g) {
    if (own && g.id === own.id) return;
    if (list.some(function (x) { return x.id === g.id; })) return;
    list = list.concat([{ id: g.id, name: g.name }]);
  });
  setExtras(list);
}

/** Убрать группу из остальных — вместе с её парами, сразу и без сети. */
export function removeExtra(id) {
  setExtras(extras().filter(function (g) { return g.id !== id; }));
}

/**
 * Перевод со «соседней подгруппы» (до веб-0.2.0) на список групп. Снимок там
 * лежал склеенным с парами соседки — оставляем свои: остальные теперь
 * хранятся отдельно и склеиваются только для экрана. Второй раз ничего не
 * делает.
 */
export function migrateGroups() {
  var sub = store.get('second');
  if (!sub && store.get('partial') == null) return;
  if (sub && !store.get('extras')) {
    var g = store.get('secondGone');
    store.set('extras', [{ id: sub.id, name: sub.name, gone: !!(g && g.confirmed), goneSince: g ? g.since : null }]);
  }
  var saved = store.get('schedule');
  if (saved) {
    try {
      store.set('schedule', ownOnly(cleanSchedule(saved)));
    } catch (e) {
      dropSchedule();
    }
  }
  store.remove('second');
  store.remove('secondGone');
  store.remove('partial');
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

/** Снимки остальных групп, приведённые к форме: {id: расписание}. Испорченный — без него. */
function extraSchedules() {
  var body = store.get('extraSchedules') || {};
  var out = {};
  Object.keys(body).forEach(function (id) {
    try {
      out[id] = cleanSchedule(body[id]);
    } catch (e) { /* испорченный — как не пришедший */ }
  });
  return out;
}

/**
 * Для экрана: своё расписание вместе с парами остальных групп. Пропавшая из
 * таблицы держит место в значках, но её прежних пар не показываем: они могли
 * уже поменяться.
 */
export function shown() {
  var main = saved();
  var list = extras();
  if (!main || !list.length) return main;
  var schedules = extraSchedules();
  return combineGroups(main, list.map(function (g) { return [g.name, g.gone ? null : schedules[g.id] || null]; }));
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

/** 404 у одной из остальных групп; true — подтверждено повтором через час. */
function noteExtraNotFound(id, now) {
  var confirmed = false;
  store.set('extras', extras().map(function (g) {
    if (g.id !== id) return g;
    if (g.goneSince == null) return Object.assign({}, g, { goneSince: now });
    if (now - g.goneSince >= GONE_CONFIRM_MS) confirmed = true;
    return g;
  }));
  return confirmed;
}

function online() {
  return typeof navigator === 'undefined' || navigator.onLine !== false;
}

function isNotFound(error) {
  return error instanceof api.HttpError && error.status === 404;
}

// ——— обновление ———

/**
 * Ответы остальных групп — запросы уже идут, рядом со своим. Не ответившая —
 * с прежним снимком: своё от неё не зависит.
 */
function collectExtras(requests, serverOk) {
  return Promise.all(requests.map(function (r) {
    return r.request.then(function (schedule) {
      return { group: r.group, schedule: schedule };
    }, function (error) {
      // 404 при здоровом сервере, повторённое через час, — группы нет в
      // таблице. Выбор не стираем: вернётся она — вернутся и её пары.
      var gone = isNotFound(error) && serverOk && noteExtraNotFound(r.group.id, Date.now());
      return { group: r.group, gone: gone };
    });
  }));
}

/** Записать ответы остальных: переименования, пропажи, снимки. */
function writeExtras(answers) {
  var schedules = store.get('extraSchedules') || {};
  var list = extras().map(function (g) {
    var answer = answers.filter(function (a) { return a.group.id === g.id; })[0];
    if (!answer) return g;
    if (answer.schedule) {
      var id = answer.schedule.g || g.id;
      delete schedules[g.id];
      schedules[id] = answer.schedule;
      return { id: id, name: answer.schedule.gn || g.name };
    }
    return answer.gone ? Object.assign({}, g, { gone: true }) : g;
  });
  store.set('extraSchedules', schedules);
  setExtras(list);
}

/** Снимка какой-то из остальных групп нет или он не про сегодня — её пора принести. */
function extrasMissing(today) {
  var schedules = store.get('extraSchedules') || {};
  return extras().some(function (g) { return !g.gone && !coversDay(schedules[g.id], today); });
}

function refreshOnce(force) {
  migrateGroups();
  var asked = subject();
  var teacherMode = isTeacher();
  var own = chosen();
  var others = extras();
  if (!own) return Promise.resolve({ kind: 'nogroup' });
  var today = collegeNow().date;
  // Неделя — одна на весь заход: запрос через полночь воскресенья не должен
  // записать окно прошлой недели с меткой новой.
  var from = weekStart(today);
  var outdated = !coversDay(saved(), today) ||
    store.get('window') !== windowMark(from) ||
    // Группу только что добавили или её снимок не дошёл: gen тот же.
    extrasMissing(today);
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
      // Своё и остальные — разом, а не по очереди (аудит сайта, W2). На экран
      // своё попадает вместе с ними: их молчание ограничено тайм-аутом
      // запроса, 30 секунд.
      var request = (teacherMode ? api.teacher(own.id, from, DAYS) : api.schedule(own.id, from, DAYS))
        .then(cleanSchedule);
      var extraRequests = others.map(function (g) {
        var r = api.schedule(g.id, from, DAYS).then(cleanSchedule);
        r.then(null, function () { /* разберёт collectExtras */ });
        return { group: g, request: r };
      });
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
        return collectExtras(extraRequests, !!meta && meta.status === 'ok').then(function (answers) {
          // Пока шёл запрос, человек мог сменить выбор: ответ уже чужой.
          if (subject() !== asked) return { kind: 'fresh' };
          // Ответ под другим id — группу или преподавателя переименовали.
          if (fresh.g !== own.id) {
            store.set(teacherMode ? 'teacher' : 'group', { id: fresh.g, name: fresh.gn });
          }
          if (answers.length) writeExtras(answers);
          store.set('schedule', fresh);
          store.set('gen', fresh.gen);
          store.set('fetchedAt', Date.now());
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
