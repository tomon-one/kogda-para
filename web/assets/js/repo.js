// Выбор и обновление расписания. Порядок и правила — как в приложении
// (ScheduleRepository.kt, ScheduleStore.kt): сначала /v1/meta, расписание — если
// сервер разобрал таблицу заново, началась новая неделя или сегодняшнего дня в
// сохранённом нет.

import * as api from './api.js';
import * as store from './store.js';
import { collegeNow, weekStart } from './time.js';
import { DAYS, MAX_GROUPS, cleanSchedule, combineGroups, coversDay, ownOnly, windowMark } from './schedule.js';

// Списки и закреплённые — в catalog.js; экраны и тесты берут их отсюда.
export { cachedGroups, cachedTeachers, freshGroups, freshTeachers, followRenamedPins, pinned, togglePin } from './catalog.js';

/** Сколько сервер может молчать при живой сети, прежде чем это сбой, а не чих. */
var UNREACHABLE_BROKEN_AFTER_MS = 30 * 60 * 1000;
/** Неудачи с таким перерывом — не одна цепочка (UNREACHABLE_STREAK_GAP приложения). */
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

/**
 * Чьё своё расписание — роль и выбранный. Другие группы в сверку не входят,
 * иначе «Убрать» посреди обновления выбросило бы и своё свежее; их пишет
 * writeExtras по нынешнему выбору.
 */
function subject() {
  var own = chosen();
  return (isTeacher() ? 't:' : 's:') + (own ? own.id : '');
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
 * Перевод со «соседней подгруппы» (до веб-0.2.0) на список групп. Прежний
 * снимок склеен с парами соседки — оставляем свои: остальные хранятся
 * отдельно. Повторный вызов ничего не делает.
 */
export function migrateGroups() {
  var sub = store.get('second');
  if (!sub && store.get('partial') == null) return;
  // Соседкой могла быть своя же группа — её не переносим.
  var group = store.get('group');
  if (sub && !store.get('extras') && !(group && group.id === sub.id)) {
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
  // Своя среди остальных бывает только из старого выбора — дважды не показываем.
  var list = extras().filter(function (g) { return !main || g.id !== main.g; });
  if (!main || !list.length) return main;
  var schedules = extraSchedules();
  return combineGroups(main, list.map(function (g) { return [g.name, g.gone ? null : schedules[g.id] || null]; }));
}

/**
 * Состояние обновления. Непрочитанные дни одной-двух групп служба отдаёт
 * общим `stale` для прежних версий, а `refresh` — без них: их пометка — у
 * самих дней (`unread` в расписании), не плашкой сбоя на всех.
 */
export function health(meta) {
  return meta.refresh || meta.status;
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
  // Вечерняя неудача и утренняя — не две подряд.
  var streak = !!u && now - (u.last || 0) < UNREACHABLE_STREAK_GAP_MS;
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

/**
 * 404 у одной из остальных групп; true — подтверждено повтором через час.
 * Список — из хранилища, мимо роли: extras() у преподавателя пуст, и 404,
 * пришедшее после переключения на «Я преподаватель», стёрло бы все группы.
 * Группы уже нет в списке — не писать.
 */
function noteExtraNotFound(id, now) {
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
      // таблице. Выбор не стираем: вернётся она — вернутся и её пары. Первое
      // 404, как и у своей группы, — только подозрение, а не «не обновилась».
      var notFound = isNotFound(error) && serverOk;
      var gone = notFound && noteExtraNotFound(r.group.id, Date.now());
      var saved = store.get('extraSchedules') || {};
      return { group: r.group, gone: gone, missed: !gone && !r.group.gone && !notFound, fresh: !saved[r.group.id] };
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

/**
 * Снимок какой-то из остальных групп не с того же разбора таблицы (gen), что
 * своё, — её пора принести. Проверки «есть ли сегодняшний день» мало: группа,
 * не пришедшая в заходе с новым gen, держала бы прежние пары до следующей
 * правки таблицы.
 */
function extrasMissing() {
  var schedules = store.get('extraSchedules') || {};
  var gen = store.get('gen');
  return extras().some(function (g) { return !g.gone && (!schedules[g.id] || schedules[g.id].gen !== gen); });
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
    extrasMissing();
  var meta = null;

  var busy = null;
  return api.meta().then(function (m) { meta = m; }, function (error) {
    meta = null;
    // 429 — сервер ответил, он занят (лимит nginx на адрес оператора): это не
    // «не отвечает», и плашки сбоя из-за него не будет.
    busy = error instanceof api.HttpError && error.status === 429 ? error : null;
  })
    .then(function () {
      var now = Date.now();
      if (meta) {
        store.remove('unreachable');
        store.set('lastOk', now);
        var state = health(meta);
        putServerState(state, meta.src_url, state === 'ok' ? null : meta.since);
      } else if (online() && !busy) {
        // Мерило — последний ответ сервера, а не цепочка неудач: сайт
        // открывают редко, и цепочки при редких заходах не видно. Молчит
        // дольше получаса — не отвечает (ответа не было никогда — считать от
        // начала цепочки). Но нужны две неудачи подряд (страница повторяет
        // неудачное через минуту): одна бывает от своей сети человека, а
        // Wi-Fi без интернета navigator.onLine не видит.
        var lastOk = store.get('lastOk');
        var chain = noteUnreachable(now);
        var since = lastOk || chain.since;
        if (chain.count >= 2 && now - since >= UNREACHABLE_BROKEN_AFTER_MS) {
          putServerState(STATUS_UNREACHABLE, null, new Date(since).toISOString());
        }
      }
      // Занят — своё и другие группы упрутся в тот же лимит: не тратить его.
      if (busy) return { kind: 'failed', error: busy };
      if (!force && !outdated && meta && meta.gen === store.get('gen')) {
        // Данные те же, но проверку показать надо: иначе кажется, что кнопка
        // обновления не работает.
        store.set('fetchedAt', now);
        return { kind: 'fresh' };
      }
      // Своё и остальные — разом. На экран своё попадает вместе с ними: их
      // молчание ограничено тайм-аутом запроса, 30 секунд.
      var request = (teacherMode ? api.teacher(own.id, from, DAYS) : api.schedule(own.id, from, DAYS))
        .then(cleanSchedule);
      var extraRequests = others.map(function (g) {
        var r = api.schedule(g.id, from, DAYS).then(cleanSchedule);
        r.then(null, function () { /* разберёт collectExtras */ });
        return { group: g, request: r };
      });
      return request.then(null, function (error) {
        if (isNotFound(error) && meta && health(meta) === 'ok' && noteNotFound('gone', Date.now())) {
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
        return collectExtras(extraRequests, !!meta && health(meta) === 'ok').then(function (answers) {
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
          // Своё пришло, другие — не все: без галочки и с их именами.
          // Уже убранные пока шёл запрос — не в счёт.
          var chosenIds = extras().map(function (g) { return g.id; });
          var missedAnswers = answers.filter(function (a) {
            return a.missed && chosenIds.indexOf(a.group.id) >= 0;
          });
          var missed = missedAnswers.map(function (a) { return a.group.name; });
          var loadedNone = missedAnswers.filter(function (a) { return a.fresh; }).map(function (a) { return a.group.name; });
          return missed.length ? { kind: 'partial', missed: missed, fresh: loadedNone } : { kind: 'updated' };
        });
      });
    })
    .then(null, function (error) {
      return { kind: 'failed', error: error };
    });
}

var running = null;
var queued = null;

/**
 * Обновления — по одному: параллельные затирали бы друг друга. Обычное при
 * идущем — ждёт его; принудительное — встаёт следом, и одно на всех, кто
 * пришёл, пока идёт текущее. Отбросить его нельзя: второе «Добавить» или
 * «Добавить» сразу после смены своей группы остались бы без пар.
 */
export function refresh(force) {
  if (running && !force) return running;
  var start = function () {
    queued = null;
    var p = refreshOnce(!!force);
    running = p;
    return p.then(function (result) {
      if (running === p) running = null;
      return result;
    });
  };
  if (!running) return start();
  if (!queued) queued = running.then(start, start);
  return queued;
}

export function isRefreshing() {
  return running !== null;
}

// ——— чужое расписание ———

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
