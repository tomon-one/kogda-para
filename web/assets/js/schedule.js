// Расписание: окно дней, пропущенные дни, склейка подгрупп, идущая пара.
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
 * Пара из соседней подгруппы. Склейка подписывает на общем номере и свою пару
 * своим именем, поэтому «есть подпись» ещё не значит «чужая»; у преподавателя
 * подпись группы стоит у каждой пары.
 */
export function isNeighbours(schedule, lesson) {
  return schedule.kind !== 'teacher' && lesson.gr != null && lesson.gr !== schedule.gn;
}

function copy(obj, changes) {
  return Object.assign({}, obj, changes);
}

/** Только свои пары: без пар соседней подгруппы и без своей подписи. */
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

function mergeLessons(mine, other, myName, otherName) {
  var extra = other.filter(function (theirs) {
    return !mine.some(function (m) { return same(m, theirs); });
  });
  if (!extra.length) return mine;
  var contested = {};
  extra.forEach(function (l) { contested[l.n] = true; });
  var labelledMine = mine.map(function (l) {
    return contested[l.n] && l.gr == null ? copy(l, { gr: myName }) : l;
  });
  var labelledExtra = extra.map(function (l) { return copy(l, { gr: otherName }); });
  // Устойчивая сортировка: при равном номере своя пара выше чужой.
  var all = labelledMine.concat(labelledExtra);
  return all.map(function (l, i) { return { l: l, i: i }; })
    .sort(function (a, b) { return a.l.n - b.l.n || a.i - b.i; })
    .map(function (x) { return x.l; });
}

/**
 * Пары второй подгруппы, которых нет в своей колонке, — в свой день и с
 * подписью группы. Совпавшие остаются одной строкой без подписи.
 */
export function mergeSecondGroup(primary, secondary) {
  var extraByDate = {};
  (secondary.days || []).forEach(function (d) { extraByDate[d.d] = d.l || []; });
  return copy(primary, {
    days: (primary.days || []).map(function (day) {
      return copy(day, {
        l: mergeLessons(day.l || [], extraByDate[day.d] || [], primary.gn, secondary.gn),
      });
    }),
  });
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

/**
 * Что написать в день без пар. Строка выбирается по дате, у одного дня она
 * всегда одна. Воскресенье и день, которого в ответе нет, — «Выходной».
 */
export function freeDay(day) {
  if (day.absent) return 'Выходной';
  if (!isDate(day.d)) return 'Пар нет';
  if (weekday(day.d) === 6) return 'Выходной';
  return FREE[dayOfYear(day.d) % FREE.length];
}

/** Есть ли в сохранённом окне сегодняшний день. */
export function coversDay(schedule, date) {
  return !!schedule && (schedule.days || []).some(function (d) { return d.d === date; });
}
