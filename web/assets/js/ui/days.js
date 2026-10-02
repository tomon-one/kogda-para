// Дни расписания карточками — общий вид для своего и чужого расписания
// (DayCard и LessonRow в TodayScreen.kt).

import { h, actionLink, snackbar, copyText } from './dom.js';
import { dayTitle, capitalize } from '../time.js';
import { currentLessonNumber, daysWithGaps, freeDay, freeOwnDay, lessonTime, shortLabels } from '../schedule.js';
import {
  isCancelled, isKnownWebinar, isOnline, isWebLink, kindName, linkEnd, linkHost, onlineLabel,
  roomLabel, shortenName,
} from '../format.js';

/**
 * Список карточек. `now` — {date, sec} по часам колледжа; `before` — что
 * вставить перед днём `beforeDay` (плашки сбоя — рядом с сегодняшним днём,
 * куда страница и прокручивается).
 */
export function dayCards(schedule, now, before, beforeDay, byName) {
  var days = daysWithGaps(schedule);
  var teacher = schedule.kind === 'teacher';
  var placed = false;
  var list = h('div', { class: 'days' });
  days.forEach(function (day, i) {
    if (before && !placed && beforeDay && day.d >= beforeDay) {
      list.appendChild(before);
      placed = true;
    }
    var nextFree = freeOwnDay(days[i + 1], schedule.groupNames || []);
    list.appendChild(dayCard(day, schedule.bells || {}, now, teacher, schedule.groupNames || [], byName !== false, nextFree));
  });
  if (before && !placed) {
    if (days.length && beforeDay && days[days.length - 1].d < beforeDay) list.appendChild(before);
    else list.insertBefore(before, list.firstChild);
  }
  return list;
}

function dayCard(day, bells, now, teacher, groups, byName, nextFree) {
  var isToday = day.d === now.date;
  var past = day.d < now.date;
  var current = isToday ? currentLessonNumber(bells, day.d, now) : null;
  var lessons = day.l || [];
  var card = h('section', {
    class: 'day' + (isToday ? ' today' : '') + (past ? ' past' : ''),
    'data-day': day.d,
  }, h('h3', { class: 'day-title' }, dayTitle(day.d, now.date)));

  if (day.unread === 'missing') {
    card.appendChild(h('p', { class: 'day-note' }, 'Сервер не смог прочитать этот день в таблице.'));
    return card;
  }
  if (day.unread) {
    card.appendChild(h('p', { class: 'day-note' },
      'Сервер не смог прочитать этот день в таблице: пары — какими были до этого.'));
  }
  if (!lessons.length) {
    card.appendChild(h('p', { class: 'day-empty' }, freeDay(day, teacher, nextFree, now)));
    return card;
  }
  // Своих пар нет, а у выбранных групп есть: «пар нет» — над их серыми
  // строками, как пишет виджет приложения.
  if (groups.length && lessons.every(function (l) { return isForeign(l, groups); })) {
    card.appendChild(h('p', { class: 'day-empty' }, freeDay(day, teacher, nextFree, now)));
  }
  lessons.forEach(function (lesson, index) {
    // Пара только у других групп — не «идёт сейчас»: человек на ней не сидит.
    var foreign = isForeign(lesson, groups);
    // С порядком: у одного номера бывают две пары — подгруппы в разных кабинетах.
    var key = day.d + ':' + lesson.n + ':' + (lesson.gr || (lesson.slots || []).join('-')) + ':' + index;
    card.appendChild(lessonRow(lesson, bells, lesson.n === current && !isCancelled(lesson) && !foreign, key, groups, byName));
  });
  // Считаются только свои пары.
  var own = groups.length ? lessons.filter(function (l) { return !isForeign(l, groups); }) : lessons;
  if (own.length && own.every(isCancelled)) {
    // Преподавателю отмена всех пар — сорванные часы, не удача.
    card.appendChild(h('p', { class: 'day-note' }, teacher ? 'Все пары отменены' : 'Всё отменили. Повезло'));
  }
  return card;
}

function isForeign(lesson, groups) {
  return groups.length > 0 && (lesson.slots || []).indexOf(0) < 0;
}

function lessonRow(lesson, bells, isNow, key, groups, byName) {
  // Пара только у других выбранных групп: её видно сразу, а не только по
  // значкам — серый фон, приглушённый текст и подпись.
  var foreign = isForeign(lesson, groups);
  var time = lessonTime(bells, lesson.n);
  var place;
  if (isOnline(lesson)) {
    place = h('span', { class: 'place' }, capitalize(onlineLabel(lesson)));
  } else {
    // Ни кабинета, ни ссылки — так и говорим: пустая строка читается как «не загрузилось».
    var room = roomLabel(lesson.r);
    place = h('span', { class: 'place' + (room ? '' : ' muted') }, room || 'Не указано');
  }
  var kind = kindName(lesson.k);
  var body = h('div', { class: 'lesson-body' },
    h('div', { class: 'subject' + (isCancelled(lesson) ? ' cancelled' : '') }, lesson.s),
    foreign ? h('div', { class: 'sub' }, 'Не у вашей группы') : null,
    h('div', { class: 'lesson-meta' }, place, kind ? h('span', { class: 'kind' }, kind) : null),
    lesson.u ? onlineLink(lesson.u, key) : null,
    // Подпись группы — вместо преподавателя: у преподавателя важно, кому
    // читается пара. Чья пара у выбранных групп — видно по значкам.
    lesson.gr != null
      ? h('div', { class: 'sub' }, lesson.gr)
      : (lesson.t || []).map(function (t) { return h('div', { class: 'sub' }, shortenName(t)); }),
    note(lesson));
  return h('div', { class: 'lesson' + (isNow ? ' now' : '') + (foreign ? ' foreign' : '') },
    h('div', { class: 'lesson-time' },
      h('div', { class: 'pair' }, lesson.n + ' пара'),
      time ? h('div', { class: 'time' }, time) : null,
      isNow ? nowLabel() : null,
      groups.length ? groupMarks(lesson.slots || [], groups, byName) : null),
    body);
}

/**
 * Значки групп под временем (GroupMarks в GroupMarks.kt) — только тех, у кого
 * пара есть (пустые рамки путают): своя — закрашенным, другие — бледным.
 * Подпись — название (shortLabels) или номер: место в настройках, 1 — своя.
 * Номера — по три в ряд, названия — сколько влезет.
 */
function groupMarks(slots, groups, byName) {
  var whose = slots.slice().sort().map(function (i) { return groups[i]; }).filter(Boolean);
  var labels = byName ? shortLabels(groups) : groups.map(function (g, i) { return String(i + 1); });
  var box = h('div', { class: 'marks' + (byName ? ' names' : ''), role: 'img', 'aria-label': 'Пара у групп: ' + whose.join(', ') });
  labels.forEach(function (label, i) {
    if (slots.indexOf(i) >= 0) box.appendChild(groupMark(label, i === 0, groups[i]));
  });
  return box;
}

/** Один значок. `name` — полное название во всплывающей подсказке: длинное в значке обрезано. */
export function groupMark(label, own, name) {
  return h('span', { class: 'mark' + (own ? ' own' : ''), 'aria-hidden': 'true', title: name || null }, String(label));
}

// Точка у «идёт сейчас» дышит по часам страницы, а не с момента, когда строка
// нарисована: экран перестраивается (пришло расписание, звонок, звезда), и
// новая точка продолжает с той же фазы, а не вспыхивает заново. Период — как
// у анимации pulse в motion.css.
var PULSE_MS = 2400;

function nowLabel() {
  var el = h('div', { class: 'now-label' }, 'идёт сейчас');
  var now = window.performance && performance.now ? performance.now() : Date.now();
  el.style.setProperty('--pulse-shift', -(now % PULSE_MS) + 'ms');
  return el;
}

function keyed(el, key) {
  el.setAttribute('data-key', key);
  return el;
}

function note(lesson) {
  if (isCancelled(lesson)) {
    return h('div', { class: 'cancel-note' }, lesson.c ? 'Отменена — ' + lesson.c : 'Отменена');
  }
  // У замены — «Вместо: Математика»: без неё новая пара выглядела бы ошибкой таблицы.
  return lesson.c ? h('div', { class: 'sub' }, capitalize(lesson.c)) : null;
}

/**
 * Ссылка на занятие: куда ведёт и две кнопки. Хост не с площадки вебинаров
 * колледжа — красным: ссылку мог вписать кто угодно, кто правит таблицу.
 */
function onlineLink(url, key) {
  var known = isKnownWebinar(url);
  var end = linkEnd(url);
  var copy = h('button', { type: 'button', class: 'link-button', 'data-key': 'copy:' + key }, 'Копировать');
  copy.addEventListener('click', function () {
    copyText(url).then(function (ok) {
      snackbar(ok ? 'Ссылка скопирована' : 'Скопировать не вышло');
    });
  });
  return h('div', { class: 'online-link' },
    h('div', { class: 'link-where' + (known ? '' : ' foreign') },
      known ? null : 'чужой адрес: ',
      h('span', { class: 'link-host' }, linkHost(url)),
      // Обычный пробел: перенос — между хостом и хвостом, а не посреди хоста.
      end ? ' ' : null,
      end ? h('span', { class: 'link-end' }, '· …/' + end) : null),
    h('div', { class: 'link-buttons' },
      isWebLink(url) ? keyed(actionLink('Открыть', url, 'link-button'), 'open-link:' + key) : null,
      copy));
}
