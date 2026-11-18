import { isCancelled, isOnline } from './format.js';

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

/** Только свои пары, без соседней подгруппы и своей подписи: перевод снимка до веб-0.2.0. */
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
  // Непрочитанный день другой группы — пометка с её именем, а не на весь день.
  var marks = extras.map(function (pair) {
    var byDate = {};
    ((pair[1] && pair[1].days) || []).forEach(function (d) { if (d.un) byDate[d.d] = d.un; });
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
  // Свои звонки дня — одни на весь колледж: дню, которого у своей группы нет,
  // они приходят от другой.
  var bells = {};
  extras.forEach(function (pair) {
    ((pair[1] && pair[1].days) || []).forEach(function (d) { if (d.bl) bells[d.d] = d.bl; });
  });
  var days = (main.days || []).concat(Object.keys(added).map(function (date) {
    return bells[date] ? { d: date, l: [], bl: bells[date] } : { d: date, l: [] };
  }))
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
      var others = [];
      marks.forEach(function (byDate, index) {
        if (byDate[day.d]) others.push({ name: extras[index][0], un: byDate[day.d] });
      });
      return others.length ? copy(day, { l: sorted, unOthers: others }) : copy(day, { l: sorted });
    }),
  });
}
