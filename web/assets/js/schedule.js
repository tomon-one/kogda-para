// Расписание: окно дней, пропущенные дни, другие выбранные группы, идущая пара.
// Правила — как в приложении (ScheduleRepository.kt, ScheduleMerge.kt,
// TodayScreen.kt, DayFormat.kt).

import { addDays, dayOfYear, isDate, weekday } from './time.js';
import { isCancelled, isOnline } from './format.js';

/** Дней в окне: эта неделя и следующая, с понедельника. */
export var DAYS = 14;

/**
 * Отметка окна: понедельник и размер. Сменилась неделя — окно перезапрашивается,
 * даже если таблица на сервере та же.
 */
export function windowMark(from) {
  return from + '/' + DAYS;
}

/**
 * Пара из соседней подгруппы — в снимке до веб-0.2.0, где склейка хранилась
 * вместе со своими парами. Склейка подписывала на общем номере и свою пару
 * своим именем, поэтому «есть подпись» ещё не значит «чужая»; у преподавателя
 * подпись группы стоит у каждой пары.
 */
export function isNeighbours(schedule, lesson) {
  return schedule.kind !== 'teacher' && lesson.gr != null && lesson.gr !== schedule.gn;
}

function copy(obj, changes) {
  return Object.assign({}, obj, changes);
}

/** Только свои пары: без пар соседней подгруппы и без своей подписи — перевод снимка до веб-0.2.0. */
export function ownOnly(schedule) {
  return copy(schedule, {
    days: (schedule.days || []).map(function (day) {
      return copy(day, {
        l: (day.l || []).filter(function (l) { return !isNeighbours(schedule, l); })
          .map(function (l) {
            if (l.gr !== schedule.gn) return l;
            var own = copy(l, {});
            delete own.gr;
            return own;
          }),
      });
    }),
  });
}

function trimmed(value) {
  return value == null ? null : String(value).trim();
}

/** Одна ли это пара в двух колонках. Аудитория входит: разные кабинеты — разные пары. */
function same(a, b) {
  return a.n === b.n &&
    a.s.trim() === b.s.trim() &&
    trimmed(a.r) === trimmed(b.r) &&
    (a.u || null) === (b.u || null) &&
    isOnline(a) === isOnline(b) &&
    isCancelled(a) === isCancelled(b);
}

/** Сколько групп можно выбрать вместе со своей: любая группа колледжа целиком, больше всего подгрупп — шесть. */
export var MAX_GROUPS = 6;

/**
 * Своё расписание вместе с парами остальных выбранных групп — для экрана
 * (combineGroups в ScheduleMerge.kt). Одинаковая пара у нескольких групп —
 * одна строка, у неё в `slots` все эти группы, своя — 0: так видно
 * совмещённые. Пара, которой у своей нет, — отдельной строкой без 0. В один
 * номер своя выше чужих. `extras` — [[имя, расписание или null], …] по порядку
 * выбора; дни — только внутри своего окна. В `groupNames` — имена по порядку.
 */
export function combineGroups(main, extras) {
  if (!main || !extras.length || main.kind === 'teacher') return main;
  var own = (main.days || []).map(function (d) { return d.d; }).filter(Boolean).sort();
  var first = own[0];
  var last = own[own.length - 1];
  var theirs = extras.map(function (pair) {
    var byDate = {};
    ((pair[1] && pair[1].days) || []).forEach(function (d) { byDate[d.d] = d.l || []; });
    return byDate;
  });
  var have = {};
  (main.days || []).forEach(function (d) { have[d.d] = true; });
  var added = {};
  theirs.forEach(function (byDate) {
    Object.keys(byDate).forEach(function (date) {
      if (first && date >= first && date <= last && !have[date]) added[date] = true;
    });
  });
  var days = (main.days || []).concat(Object.keys(added).map(function (date) { return { d: date, l: [] }; }))
    .sort(function (a, b) { return a.d < b.d ? -1 : a.d > b.d ? 1 : 0; });
  return copy(main, {
    groupNames: [main.gn].concat(extras.map(function (pair) { return pair[0]; })),
    days: days.map(function (day) {
      var rows = (day.l || []).map(function (l) { return copy(l, { slots: [0] }); });
      theirs.forEach(function (byDate, index) {
        var slot = index + 1;
        (byDate[day.d] || []).forEach(function (lesson) {
          var at = -1;
          for (var i = 0; i < rows.length; i++) {
            if (same(rows[i], lesson) && rows[i].slots.indexOf(slot) < 0) { at = i; break; }
          }
          if (at >= 0) {
            rows[at] = copy(rows[at], { slots: rows[at].slots.concat([slot]) });
          } else {
            var extra = copy(lesson, { slots: [slot] });
            delete extra.gr;
            rows.push(extra);
          }
        });
      });
      // Устойчивая сортировка: в один номер своя выше чужих, чужие — по порядку.
      var sorted = rows.map(function (l, i) { return { l: l, i: i }; })
        .sort(function (a, b) { return a.l.n - b.l.n || a.i - b.i; })
        .map(function (x) { return x.l; });
      return copy(day, { l: sorted });
    }),
  });
}

/**
 * Подписи групп для значков (shortLabels в GroupMarks.kt): подгруппы той же
 * группы, что своя, — коротко, «/1», «/2»; остальные — полным названием.
 */
export function shortLabels(names) {
  var m = /^(.+)\/(\d+)$/.exec(String(names[0] || '').trim());
  return names.map(function (name) {
    var n = /^(.+)\/(\d+)$/.exec(String(name).trim());
    return m && n && n[1] === m[1] ? '/' + n[2] : name;
  });
}

/** Остальные подгруппы своей группы по номеру: для «ИСП-924/1» — «ИСП-924/2», … */
export function subgroupsOf(name, groups) {
  var m = /^(.+)\/(\d+)$/.exec(String(name || '').trim());
  if (!m) return [];
  return (groups || []).map(function (g) {
    var n = /^(.+)\/(\d+)$/.exec(String(g.name || '').trim());
    return n && n[1] === m[1] && g.name.trim() !== name.trim() ? { n: Number(n[2]), g: g } : null;
  }).filter(Boolean)
    .sort(function (a, b) { return a.n - b.n; })
    .map(function (x) { return x.g; });
}

/**
 * Дни, которых нет в ответе, а по листу они есть, — воскресенье или будень без
 * строки. Только между первым и последним пришедшим днём и только внутри
 * `cov`: выдумывать дни за краем листа нельзя. У вставленного `absent`.
 */
export function daysWithGaps(schedule) {
  var present = schedule.days || [];
  if (present.length < 2) return present;
  var cov = schedule.cov || [];
  if (cov.length !== 2 || !isDate(cov[0]) || !isDate(cov[1])) return present;
  var known = present.map(function (d) { return d.d; }).filter(isDate).sort();
  if (!known.length) return present;
  var have = {};
  present.forEach(function (d) { have[d.d] = d; });
  var out = [];
  for (var day = known[0]; day <= known[known.length - 1]; day = addDays(day, 1)) {
    if (have[day]) out.push(have[day]);
    else if (day >= cov[0] && day <= cov[1]) out.push({ d: day, l: [], absent: true });
  }
  return out;
}

/** Первый день не раньше `date`; все раньше — последний. */
export function dayIndex(days, date) {
  for (var i = 0; i < days.length; i++) if (days[i].d >= date) return i;
  return Math.max(0, days.length - 1);
}

/** «09:00» → секунды от полуночи; null — не время. */
export function parseTime(value) {
  var m = /^(\d{1,2}):(\d{2})(?::(\d{2}))?$/.exec(value || '');
  if (!m) return null;
  var h = Number(m[1]);
  var min = Number(m[2]);
  var s = m[3] ? Number(m[3]) : 0;
  if (h > 23 || min > 59 || s > 59) return null;
  return h * 3600 + min * 60 + s;
}

/**
 * Номер пары, которая идёт сейчас, по сетке звонков. Конец включительно с
 * точностью до секунды: в 10:30:01 пара «до 10:30» уже не идёт.
 */
export function currentLessonNumber(bells, day, now) {
  if (!bells || day !== now.date) return null;
  var keys = Object.keys(bells);
  for (var i = 0; i < keys.length; i++) {
    var range = bells[keys[i]] || [];
    var start = parseTime(range[0]);
    var end = parseTime(range[1]);
    if (start != null && end != null && now.sec >= start && now.sec <= end) {
      var n = parseInt(keys[i], 10);
      if (!isNaN(n)) return n;
    }
  }
  return null;
}

/** «09:00–10:30»; одно начало, если конца нет; null — пары нет в сетке. */
export function lessonTime(bells, number) {
  var range = bells && bells[String(number)];
  if (!range || !range[0]) return null;
  return range[1] ? range[0] + '–' + range[1] : range[0];
}

var FREE = [
  'Пар нет. Повезло',
  'Пар нет. Это не ошибка',
  'Пар нет. Совсем',
  'Пусто. Так тоже бывает',
];
/** Ещё одна — только студенту. */
var FREE_STUDENT = ['Пар нет. Можно одичать'];

/**
 * Что написать в день без пар (freeDay в TodayScreen.kt — правила одни).
 * Строка выбирается по дате. Воскресенье и день, которого в ответе нет, —
 * «Выходной». Студенту: следующий будний тоже пуст — «повезло дважды»,
 * сегодня до полудня — про шторы; преподавателю — только прежние фразы.
 * `now` — {date, sec} (collegeNow).
 */
export function freeDay(day, teacher, nextFree, now) {
  if (day.absent) return 'Выходной';
  if (!isDate(day.d)) return 'Пар нет';
  if (weekday(day.d) === 6) return 'Выходной';
  if (!teacher) {
    if (nextFree) return 'Пар нет. Повезло дважды';
    if (now && now.date === day.d && now.sec < 12 * 3600) return 'Пар нет. Можно открыть шторы';
  }
  var phrases = teacher ? FREE : FREE.concat(FREE_STUDENT);
  return phrases[dayOfYear(day.d) % phrases.length];
}

/** День будний, пришёл с сервера и без своих пар — для «повезло дважды». */
export function freeOwnDay(day, groups) {
  if (!day || day.absent || !isDate(day.d) || weekday(day.d) === 6) return false;
  var lessons = day.l || [];
  if (!groups || !groups.length) return !lessons.length;
  return lessons.every(function (l) { return (l.slots || []).indexOf(0) < 0; });
}

/** Есть ли в сохранённом окне сегодняшний день. */
export function coversDay(schedule, date) {
  return !!schedule && (schedule.days || []).some(function (d) { return d.d === date; });
}

function text(value) {
  if (typeof value === 'string') return value;
  return typeof value === 'number' ? String(value) : undefined;
}

/**
 * Ответ службы — к форме из docs/api.md: чужого типа поле отбрасывается или
 * приводится, а не роняет отрисовку. Без этого одно поле не того типа
 * (кабинет числом, звонок строкой) сохранялось и вешало страницу на
 * «Загрузке…» при каждом открытии, и починка службы уже не помогала. Не похоже на расписание вовсе — исключение: обновление не
 * удалось, прежнее остаётся.
 */
export function cleanSchedule(body) {
  if (!body || typeof body !== 'object' || typeof body.g !== 'string' || !Array.isArray(body.days)) {
    throw new Error('ответ службы не похож на расписание');
  }
  var out = { g: body.g, gn: text(body.gn) || body.g, gen: text(body.gen) || '', bells: {}, days: [] };
  ['src', 'src_url', 'col', 'kind'].forEach(function (key) {
    var value = text(body[key]);
    if (value) out[key] = value;
  });
  var cov = body.cov;
  if (Array.isArray(cov) && cov.length === 2 && typeof cov[0] === 'string' && typeof cov[1] === 'string') {
    out.cov = [cov[0], cov[1]];
  }
  if (body.bells && typeof body.bells === 'object') {
    Object.keys(body.bells).forEach(function (n) {
      var range = body.bells[n];
      if (Array.isArray(range)) out.bells[n] = range.filter(function (t) { return typeof t === 'string'; });
    });
  }
  body.days.forEach(function (day) {
    if (!day || typeof day.d !== 'string') return;
    var clean = { d: day.d, l: [] };
    if (typeof day.row === 'number') clean.row = day.row;
    (Array.isArray(day.l) ? day.l : []).forEach(function (l) {
      if (!l || typeof l.n !== 'number') return;
      var lesson = { n: l.n, s: text(l.s) || 'Занятие' };
      ['k', 'r', 'u', 'c', 'gr', 'col'].forEach(function (key) {
        var value = text(l[key]);
        if (value) lesson[key] = value;
      });
      if (Array.isArray(l.t)) {
        var teachers = l.t.filter(function (t) { return typeof t === 'string'; });
        if (teachers.length) lesson.t = teachers;
      }
      if (l.o) lesson.o = 1;
      if (l.x) lesson.x = 1;
      clean.l.push(lesson);
    });
    out.days.push(clean);
  });
  return out;
}
